package com.consense.service.vetting;

import com.consense.common.*;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParser;
import com.consense.document.DocumentParseProbe;
import com.consense.document.DocumentBlock;
import com.consense.document.ParseCoverage;
import com.consense.domain.*;
import com.consense.repository.SourceDocumentRepository;
import com.consense.repository.VettingFindingRepository;
import com.consense.repository.VettingRunRepository;
import com.consense.service.ProjectService;
import com.consense.service.StorageService;
import com.consense.service.sources.ProjectDocumentScope;
import com.consense.service.sources.OerSourceMetadata;
import com.consense.service.sources.SourceContentDigests;
import com.consense.service.sources.CanonicalEvidenceService;
import com.consense.service.sources.CanonicalCurrentSourceReader;
import com.consense.service.sources.CanonicalCurrentSourceReader.OriginalFileState;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingSemanticReview.Candidate;
import com.consense.web.dto.DraftingDtos.UploadResultVO;
import com.consense.web.dto.VettingDtos.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import javax.annotation.PreDestroy;
import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Bounded background review, immutable evidence snapshots and preserved human disposition. */
@Slf4j @Service @RequiredArgsConstructor
public class VettingService {
    public static final String EVIDENCE_UNVERIFIED = "unverified";
    private static final List<String> FILE_ORDER = Arrays.asList("NTT", "SCT", "SCC", "GCT", "FT", "AA", "GCC", "SL", "PRE", "SP", "BQ", "GS");
    private static final List<String> ACTIVE_STATES = Arrays.asList("QUEUED", "RUNNING");
    private final ProjectService projectService;
    private final StorageService storageService;
    private final DocumentParser documentParser;
    private final ConsenseProperties props;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final VettingFindingRepository findingRepository;
    private final VettingRunRepository runRepository;
    private final VettingSemanticReview semantic;
    private final VettingReportWriter reportWriter;
    private final PlatformTransactionManager transactionManager;
    private final com.consense.ai.LlmProfiles llmProfiles;
    private DataSource fileListDataSource;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setFileListDataSource(DataSource dataSource){this.fileListDataSource=dataSource;}
    private VettingCheckpointService checkpoints;
    private com.consense.repository.VettingReportSnapshotRepository reportSnapshots;
    private com.consense.service.export.EnglishFileOutput englishFiles;
    private final String executionOwner=UUID.randomUUID().toString();
    @org.springframework.beans.factory.annotation.Autowired
    public void setCheckpoints(VettingCheckpointService service){this.checkpoints=service;}
    @org.springframework.beans.factory.annotation.Autowired
    public void setReportSnapshots(com.consense.repository.VettingReportSnapshotRepository repository){this.reportSnapshots=repository;}
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setEnglishFileOutput(com.consense.service.export.EnglishFileOutput output){this.englishFiles=output;}
    private FixedBaselineRetrievalClient fixedBaseline;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setFixedBaselineRetrievalClient(FixedBaselineRetrievalClient client){this.fixedBaseline=client;}
    private OerBusinessEvidence oer;
    @org.springframework.beans.factory.annotation.Autowired
    public void setOerBusinessEvidence(OerBusinessEvidence service){this.oer=service;}
    private com.consense.service.sources.CanonicalEvidenceService evidencePreparation;
    @org.springframework.beans.factory.annotation.Autowired
    public void setEvidencePreparation(com.consense.service.sources.CanonicalEvidenceService service){this.evidencePreparation=service;}
    private com.consense.service.sources.SourceContentDigestPreparation sourceDigestPreparation;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setSourceDigestPreparation(com.consense.service.sources.SourceContentDigestPreparation service){this.sourceDigestPreparation=service;}
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private CanonicalCurrentSourceReader currentSources;
    private boolean canonicalEnabled(){return oer!=null&&oer.enabled();}
    private com.consense.service.drafting.DraftingService draftingIdentity;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setDraftingIdentity(com.consense.service.drafting.DraftingService service){this.draftingIdentity=service;}
    private VettingChecklistService checklistService;
    @org.springframework.beans.factory.annotation.Autowired
    public void setChecklistService(VettingChecklistService service) { this.checklistService = service; }
    private final ConcurrentMap<String,Object> locks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String,CompletableFuture<RunResultVO>> completions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String,VettingServiceProbe> serviceProbes = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8),
            task -> { Thread t = new Thread(task,"consense-vetting"); t.setDaemon(true); return t; },new ThreadPoolExecutor.AbortPolicy());

    static final String PACKAGE_IDENTITY_LIFECYCLE="vetting-package-identity-lifecycle-v1";
    private JsonNode packageAuthority(String projectId) {
        if(draftingIdentity==null)return null;
        // Reuse the adopted-input resolver in a fresh read transaction, not a managed entity cached by publication.
        TransactionTemplate read=new TransactionTemplate(transactionManager);read.setReadOnly(true);
        read.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return read.execute(status->{JsonNode authority=draftingIdentity.packageIdentitySnapshot(projectId).toJson();
            if(!Objects.equals(projectId,authority.path("core").path("projectId").asText()))throw new BizException(4104,"Package identity authority belongs to another project");
            return authority;});
    }
    static String packageAuthorityCore(JsonNode authority) {
        if(authority==null||authority.isNull())return VettingCorpus.hash("package-identity-authority-unavailable-v1");
        if(!com.consense.service.drafting.DraftingService.PackageIdentitySnapshot.POLICY.equals(authority.path("policy").asText())
                ||!authority.path("core").isObject()||!Objects.equals(authority.path("authoritySha256").asText(),VettingCorpus.hash(JsonUtils.write(authority.path("core")))))
            throw new IllegalArgumentException("Adopted package identity snapshot is not bound to its original authority core");
        return authority.path("authoritySha256").asText();
    }
    private static JsonNode sealPackageIdentity(JsonNode value) {
        com.fasterxml.jackson.databind.node.ObjectNode sealed=(com.fasterxml.jackson.databind.node.ObjectNode)value.deepCopy();
        sealed.remove(Arrays.asList("ledgerSha256","decisionSha256"));
        com.fasterxml.jackson.databind.node.ObjectNode decision=sealed.deepCopy();
        if(decision.path("authority").isObject())((com.fasterxml.jackson.databind.node.ObjectNode)decision.path("authority")).remove("audit");
        sealed.put("decisionSha256",VettingCorpus.hash(JsonUtils.write(decision)));
        sealed.put("ledgerSha256",VettingCorpus.hash(JsonUtils.write(sealed)));return sealed;
    }
    static JsonNode freezePackageIdentity(JsonNode authority,String sourceSha) {
        com.fasterxml.jackson.databind.node.ObjectNode frozen=(com.fasterxml.jackson.databind.node.ObjectNode)VettingPackageIdentity.review(Collections.emptyList(),authority).ledger();
        frozen.put("lifecyclePolicy",PACKAGE_IDENTITY_LIFECYCLE);frozen.put("sourceSnapshotSha256",sourceSha);
        frozen.put("authorityCoreSha256",packageAuthorityCore(authority));frozen.put("executionStatus","not_executed");frozen.put("status","frozen_unexecuted");
        return sealPackageIdentity(frozen);
    }
    static boolean packageIdentityReviewValid(JsonNode review,String sourceSha) {
        if(review==null||!review.isObject()||!PACKAGE_IDENTITY_LIFECYCLE.equals(review.path("lifecyclePolicy").asText())
                ||!VettingPackageIdentity.POLICY.equals(review.path("policy").asText())||!Objects.equals(sourceSha,review.path("sourceSnapshotSha256").asText()))return false;
        try{return Objects.equals(review.path("authorityCoreSha256").asText(),packageAuthorityCore(review.get("authority")))
                &&Objects.equals(review.path("decisionSha256").asText(),sealPackageIdentity(review).path("decisionSha256").asText())
                &&Objects.equals(review.path("ledgerSha256").asText(),sealPackageIdentity(review).path("ledgerSha256").asText());}
        catch(RuntimeException invalid){return false;}
    }
    static JsonNode completePackageIdentity(JsonNode frozen,VettingPackageIdentity.Review review) {
        if(frozen==null||!packageIdentityReviewValid(frozen,frozen.path("sourceSnapshotSha256").asText()))throw new IllegalArgumentException("Missing or changed frozen package identity");
        com.fasterxml.jackson.databind.node.ObjectNode ledger=(com.fasterxml.jackson.databind.node.ObjectNode)review.ledger();
        if(!Objects.equals(frozen.path("authorityCoreSha256").asText(),packageAuthorityCore(ledger.get("authority"))))throw new IllegalArgumentException("Identity review used another authority core");
        ledger.put("lifecyclePolicy",PACKAGE_IDENTITY_LIFECYCLE);ledger.put("sourceSnapshotSha256",frozen.path("sourceSnapshotSha256").asText());
        ledger.put("authorityCoreSha256",frozen.path("authorityCoreSha256").asText());ledger.put("executionStatus","executed");return sealPackageIdentity(ledger);
    }
    static JsonNode packageMirrorAudit(JsonNode captured,Project project) {
        com.fasterxml.jackson.databind.node.ObjectNode current=JsonUtils.mapper().createObjectNode();
        current.put("contractNo",project.getContractNo());current.put("nameZhHans",project.getNameZhHans());current.put("nameZhHant",project.getNameZhHant());current.put("nameEn",project.getNameEn());
        com.fasterxml.jackson.databind.node.ObjectNode audit=JsonUtils.mapper().createObjectNode();
        JsonNode original=captured==null||captured.isNull()?current:captured;
        audit.set("captured",original.deepCopy());audit.set("current",current);audit.put("changed",!original.equals(current));audit.put("authorityEffect","none_project_display_is_not_package_authority");return audit;
    }
    private void assertPackageIdentityCurrent(String projectId,JsonNode frozen,String sourceSha) {
        if(frozen==null)return; // Historical runs remain explicitly unexecuted; they are never upgraded by reading them.
        if(!packageIdentityReviewValid(frozen,sourceSha)||!Objects.equals(frozen.path("authorityCoreSha256").asText(),packageAuthorityCore(packageAuthority(projectId))))
            throw new BizException(4104,"Adopted package identity changed; rerun the review. Frozen results and human decisions are retained.");
    }
    private boolean packageIdentityCurrent(VettingRun run,CoverageVO recorded) {
        try{assertPackageIdentityCurrent(run.getProjectId(),recorded==null?null:recorded.getPackageIdentityReview(),run.getSourceSnapshotSha256());return true;}
        catch(RuntimeException stale){return false;}
    }
    static void bindPackageIdentityEvidence(Candidate candidate,JsonNode review) {
        if(!Arrays.asList("package_identity_number_coordination","package_identity_title_coordination").contains(candidate.getRuleId()))return;
        String field=candidate.getRuleId().contains("_number_")?"number":"title";
        for(FindingEvidence quote:candidate.getEvidence()) {
            com.fasterxml.jackson.databind.node.ObjectNode link=JsonUtils.mapper().createObjectNode();
            link.put("policy",VettingPackageIdentity.POLICY);link.put("decisionSha256",review.path("decisionSha256").asText());
            link.put("authorityCoreSha256",review.path("authorityCoreSha256").asText());link.put("field",field);
            com.fasterxml.jackson.databind.node.ArrayNode ids=link.putArray("occurrenceIds");
            for(JsonNode declaration:review.path("declarations"))if(field.equals(declaration.path("field").asText())&&"identity_candidate".equals(declaration.path("classification").asText())
                    &&Objects.equals(quote.getDocumentId(),declaration.path("documentId").asText())&&Objects.equals(quote.getSourceHash(),declaration.path("sourceHash").asText())
                    &&Objects.equals(quote.getAnchor(),declaration.path("anchor").asText())&&Objects.equals(quote.getQuote(),declaration.path("quote").asText()))ids.add(declaration.path("occurrenceId").asText());
            if(ids.isEmpty())throw new IllegalArgumentException("Identity candidate quote has no exact frozen declaration link");
            quote.setPackageIdentityLink(link);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=30000,initialDelay=30000)
    public void recoverInterruptedRuns() {
        List<VettingRun> resumable=new ArrayList<>();
        List<VettingServiceProbe> recovery=new ArrayList<>();long started=System.nanoTime();
        try {
            tx().execute(status -> {
                for (VettingRun run : runRepository.findByStatusIn(ACTIVE_STATES)) {
                    if(checkpoints!=null&&run.getRecoveryContractSha256()!=null){resumable.add(run);continue;}
                    VettingServiceProbe probe=VettingServiceProbe.start(props,run.getId(),run.getProjectId(),"restart_recovery");recovery.add(probe);
                    probe.observe("interrupted_run_before_recovery",()->VettingServiceProbe.map("run",run));
                    run.setStatus("FAILED"); run.setPhase("interrupted"); run.setFinishedAt(Instant.now());
                    run.setRecoveryStatus("NEEDS_RERUN");run.setError("服务重启导致审查中断。没有兼容检查点，已有人工处置保留，请重新运行审查。"); run.setMessage(run.getError()); runRepository.save(run);
                    probe.observe("interrupted_run_pending_commit",()->VettingServiceProbe.map("run",run,"committed",false));
                } return null;
            });
            long elapsed=System.nanoTime()-started;
            for(VettingServiceProbe probe:recovery){probe.observe("restart_recovery_transaction_returned",()->VettingServiceProbe.returnedTransaction("inclusiveTransactionWallNanos",elapsed,
                    "records",probe.reference("interrupted_run_pending_commit"),"boundary","Original REQUIRED recovery callback/transaction returned, including observers; no extra DAO read; outer commit may remain unknown"));probe.finish("recovery_transaction_returned",null);}
        } catch(RuntimeException|Error e){for(VettingServiceProbe probe:recovery){probe.originalFailure("restart_recovery_failed",e);probe.finish("recovery_failed",e);}throw e;}
        for(VettingRun saved:resumable)recover(saved);
    }
    private void recover(VettingRun saved) {
        if(completions.containsKey(saved.getId())||!checkpoints.claim(saved.getId(),executionOwner))return;
        CompletableFuture<RunResultVO> done=new CompletableFuture<>();VettingServiceProbe probe=VettingServiceProbe.start(props,saved.getId(),saved.getProjectId(),"checkpoint_recovery");
        try {
            com.consense.ai.ModelIdentity identity=JsonUtils.read(saved.getModelIdentityJson(),com.consense.ai.ModelIdentity.class);
            com.consense.ai.LlmOperation chat=Boolean.TRUE.equals(saved.getModelProfileExplicit())&&identity!=null?llmProfiles.resolve(identity.getProfileId()):llmProfiles.resolve(null);
            VettingLlmOperation selected=semantic.captureOperation(chat);
            List<SourceDocument> selectedSources=canonicalEnabled()?freshSourceStamps(saved.getProjectId()):freshSourceSet(saved.getProjectId());
            CoverageVO recoveredCoverage=JsonUtils.read(saved.getCoverageJson(),CoverageVO.class);
            if(recoveredCoverage!=null&&!JsonUtils.isBlankText(recoveredCoverage.getSourceStampSha256())
                    &&!Objects.equals(recoveredCoverage.getSourceStampSha256(),sourceStampHash(selectedSources)))
                throw new BizException(4104,"The interrupted source preparation changed before its owned receipt was frozen; rerun is required.");
            if(!Objects.equals(saved.getSourceSnapshotSha256(),compactSourceSetHash(selectedSources)))selectedSources=freshSourceSet(saved.getProjectId());
            List<SourceDocument> snapshot=selectedSources;
            String supplement=semantic.configuredSupplement();
            if(chat==null||!recoveryContract(supplement,selected).equals(saved.getRecoveryContractSha256())||!sourceSnapshotMatches(saved.getSourceSnapshotSha256(),snapshot)
                    ||!Objects.equals(saved.getModelIdentityJson(),JsonUtils.write(selected==null?chat.getIdentity():selected.getIdentity()))||sourceSetStale(saved,true,!compactSources(snapshot)))
                throw new BizException(4101,"来源、模型或审查配方与检查点不兼容，需要重新运行；可靠包和人工记录已保留。");
            tx().execute(status->{VettingRun run=checkpoints.owned(saved.getId(),executionOwner);run.setRecoveryStatus("RESUMING");run.setPhase("recovering");run.setMessage("恢复已核验的来源包；结果不明的包将显式重试。");runRepository.saveAndFlush(run);return null;});
            completions.put(saved.getId(),done);if(probe.enabled())serviceProbes.put(saved.getId(),probe);
            dispatch(saved,snapshot,chat,selected,done,probe,supplement);
        }catch(Exception e){failRun(saved.getId(),brief(e),done,probe);}
        catch(Error failure){failFatalRun(saved.getId(),failure,done,probe);throw failure;}
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=20000,initialDelay=20000)
    public void renewLeases(){if(checkpoints!=null)for(String id:completions.keySet())try{checkpoints.renew(id,executionOwner);}catch(RuntimeException lost){log.warn("审查 {} 执行租约无法续期：{}",id,brief(lost));}}
    private String recoveryContract(String supplement){ConsenseProperties.Vetting v=props.getVetting();return VettingCorpus.hash("vetting-checkpoint-v8-frozen-package-identity\n"+JsonUtils.write(Arrays.asList(
            PACKAGE_IDENTITY_LIFECYCLE,VettingPackageIdentity.POLICY,com.consense.service.drafting.DraftingService.PackageIdentitySnapshot.POLICY,
            VettingPromptPolicy.metadata(supplement),VettingOutputSchema.contractSha256(),VettingSemanticReview.policyContractSha256(),
            v.isSemanticEnabled(),v.getSemanticTopics(),v.getSemanticContextChars(),v.getSemanticContextExpansionChars(),v.getProjectReferenceComparisons(),v.getProjectReferenceContextChars(),
            v.getCandidateLimit(),v.getTopK(),v.isDiffAgainstBaseline(),v.isRequireHybridRetrieval(),v.getRetrievalUrl(),v.getExpectedRetrievalContract(),v.getExpectedRetrievalSignatureVersion(),v.getExpectedRetrievalGranularity(),
            v.getExpectedEmbeddingWindowMaxTokens(),v.getExpectedRerankWindowMaxTokens(),v.getExpectedWindowOverlapTokens(),v.getExpectedStructuralEmbeddingMaxTokens(),v.getExpectedStructuralRerankMaxTokens(),v.isExpectedStructuralRerankStrict(),
            v.getExpectedRetrievalRecipeFingerprint(),v.getExpectedRetrievalSignature(),v.isTokenBudgetObserverEnabled(),v.getTokenBudgetObserverRecipeSha256(),v.getMinimaxCnWire())));}
    private String recoveryContract(String supplement,VettingLlmOperation selected){
        String prior=recoveryContract(supplement);
        return selected!=null&&selected.isQwen()?VettingCorpus.hash(prior+"|qwen-usage-preflight|"+selected.getQwen().getPolicySha256()):prior;
    }
    private void dispatch(VettingRun run,List<SourceDocument> snapshot,com.consense.ai.LlmOperation chat,VettingLlmOperation selected,CompletableFuture<RunResultVO> done,VettingServiceProbe probe,String supplement){
        Map<Long,OriginalFileState> originalPins=new LinkedHashMap<>();
        Runnable guard=()->{assertRunSourcesCurrent(run,snapshot);requireOriginalFreshness(snapshot,originalPins);};
        worker.execute(()->{try(com.consense.ai.LlmProfiles.Scope ignored=llmProfiles.bind(chat);VettingSemanticReview.OperationScope operation=semantic.bindOperation(selected);
                VettingSemanticReview.SupplementScope supplemental=semantic.bindSupplement(supplement);
                 VettingSemanticReview.CheckpointScope durable=semantic.bindCheckpoint(checkpoints==null?null:checkpoints.session(run.getId(),executionOwner,guard));
                 com.consense.service.llm.QwenChatUsagePreflight.Operation.ActionScope usage=selected!=null&&selected.isQwen()?selected.getQwen().openAction("vetting:"+run.getId(),guard):null){executeRun(run,run.getProjectId(),run.getLang(),snapshot,originalPins,done,probe);}
            catch(RuntimeException failure){probe.originalFailure("worker_action_initialization_failed",failure);try{failRun(run.getId(),brief(failure),done,probe);}finally{probe.finish("action_initialization_failed",failure);serviceProbes.remove(run.getId(),probe);}}
            catch(Error failure){failFatalRun(run.getId(),failure,done,probe);throw failure;}
            finally{completions.remove(run.getId(),done);serviceProbes.remove(run.getId(),probe);}});
    }
    @PreDestroy public void stopWorker() {
        if(checkpoints!=null)for(String id:completions.keySet())checkpoints.release(id,executionOwner);
        worker.shutdownNow();
        for(VettingServiceProbe probe:serviceProbes.values())probe.observe("worker_shutdown_requested",()->VettingServiceProbe.map("boundary","Original shutdownNow returned; this event does not assert an interrupted run transaction was committed"));
    }

    public List<VettingFileVO> listFiles(String projectId) {
        projectService.require(projectId); List<VettingFileVO> out = new ArrayList<>();
        List<FileDisplay> displayed=fileDisplaySourceSet(projectId);
        boolean active=displayed.stream().anyMatch(d->roleConfirmationEligible(d)||identityConfirmationEligible(d))
                && runRepository.existsByProjectIdAndStatusIn(projectId,ACTIVE_STATES);
        for(FileDisplay display:displayed) {
            SourceDocument d=display.source;
            VettingFileVO row=fileVO(display);
            if(validRoleHash(d.getSourceSha256())) {
                row.setOriginalSourceSha256(d.getSourceSha256());row.setRoutingSha256(display.routingSha);
            }
            boolean needsLegacyHash=roleConfirmationEligible(display)||identityConfirmationEligible(display)
                    ||Arrays.asList("PARSING","FAILED").contains(d.getParseStatus())
                    ||!SourceContentDigests.stamped(d)&&!excluded(d);
            // shortcut: parsing retries use the bounded legacy pins; reuse stamped CAS when a failed source exceeds the existing 1M-character cap.
            if(validRoleHash(d.getSourceSha256())&&needsLegacyHash&&display.safeForLegacyHash()) {
                SourceDocument bound=roleSourceSnapshot(d.getId());
                if(Objects.equals(d.getProjectId(),bound.getProjectId()) && Objects.equals(d.getFileName(),bound.getFileName())
                        && Objects.equals(d.getSourceSha256(),bound.getSourceSha256())
                        && (!SourceContentDigests.valid(d.getTextContentSha256()) || SourceContentDigests.field(bound.getTextContent()).equals(d.getTextContentSha256()))
                        && display.routingSha.equals(CanonicalEvidenceService.routingHash(bound))
                        && Objects.equals(d.getParseStatus(),bound.getParseStatus())) {
                    row.setOriginalSourceSha256(bound.getSourceSha256());row.setSourceHash(VettingCorpus.sourceHash(bound));
                    row.setRoutingSha256(com.consense.service.sources.CanonicalEvidenceService.routingHash(bound));
                    row.setRoleConfirmationAvailable(!active&&roleConfirmationEligible(bound));
                    row.setIdentityConfirmationAvailable(!active&&identityConfirmationEligible(bound));
                }
            }
            if(SourceContentDigests.stamped(d)) {
                row.setOriginalSourceSha256(d.getSourceSha256());row.setRoutingSha256(display.routingSha);
                row.setBusinessContentSha256(d.getBusinessContentSha256());
                if(validRoleHash(d.getSourceSha256())&&!display.safeForLegacyHash()&&roleConfirmationEligible(display))
                    row.setRoleConfirmationAvailable(!active);
            }
            out.add(row);
        } return out;
    }
    public List<SourceDocumentRepository.ParseProgressMetadata> parseProgress(String projectId) {
        projectService.require(projectId);return sourceDocumentRepository.findParseProgressByProjectId(projectId);
    }
    @EventListener(ApplicationReadyEvent.class)
    // shortcut: this local runtime has one backend owner; use a parser lease before sharing its database across instances.
    public void markInterruptedParses() { sourceDocumentRepository.markInterruptedVettingParses(); }

    private AutoCloseable observeParseProgress(SourceDocument source) {
        return DocumentParser.observeProgress(message->sourceDocumentRepository.updateParseProgress(source.getId(),source.getSourceSha256(),
                truncate(message+" · 更新于 "+Instant.now(),1024)));
    }
    private VettingFileVO fileVO(SourceDocument d) {
        List<String> notes=parseWarnings(d);
        return fileVO(d,nvl(d.getTextContent()).length(),reviewable(d),notes);
    }
    private VettingFileVO fileVO(FileDisplay display) {
        SourceDocument d=display.source;
        return fileVO(d,display.textChars,Arrays.asList("PARSED","PARTIAL").contains(d.getParseStatus())&&display.hasText&&!excluded(d),
                parseWarnings(display.coverageHeader,display.hasCoverage,d));
    }
    private VettingFileVO fileVO(SourceDocument d,int textChars,boolean parsed,List<String> notes) {
        String exclusion=ProjectDocumentScope.exclusionReason(d,"fullset");
        if(exclusion!=null)notes.add("source_identity_unresolved".equals(exclusion)?"身份待确认，原文已保留；确认文件用途后可加入源集。":"该文件被来源身份或显式范围规则排除。");
        VettingFileVO row=new VettingFileVO(fileKey(d),roleLabel(VettingCorpus.sourceRole(d)),d.getFileName(),nvl(d.getParseMessage()),
                d.getParseStatus(),false,parsed,d.getPageCount()==null?0:d.getPageCount(),d.getParseStatus(),d.getParseMessage(),textChars,Boolean.TRUE.equals(d.getOcrUsed()),notes,VettingCorpus.sourceRole(d),d.getDocumentScope(),exclusion);
        row.setDocumentId(d.getId()==null?null:String.valueOf(d.getId()));return row;
    }
    private boolean roleConfirmationEligible(SourceDocument d) {
        return ProjectDocumentScope.reviewCategory(d) && ProjectDocumentScope.parseUsable(d)
                && nvl(d.getTextContent()).codePoints().anyMatch(c->!Character.isWhitespace(c)&&!Character.isSpaceChar(c))
                && roleConfirmationScopeEligible(d);
    }
    private boolean roleConfirmationEligible(FileDisplay display) {
        SourceDocument d=display.source;
        return ProjectDocumentScope.reviewCategory(d)&&Arrays.asList("PARSED","PARTIAL").contains(d.getParseStatus())
                &&display.hasText&&roleConfirmationScopeEligible(d);
    }
    private boolean roleConfirmationScopeEligible(SourceDocument d) {
        return JsonUtils.isBlankText(d.getReviewRole()) && "unknown".equals(VettingCorpus.sourceRole(d))
                && "source_identity_unresolved".equals(ProjectDocumentScope.exclusionReason(d,"fullset","vetting"));
    }
    private boolean identityConfirmationEligible(SourceDocument d) {
        return identityConfirmationEligible(d,d.getTextContent()!=null);
    }
    private boolean identityConfirmationEligible(FileDisplay display) {
        return identityConfirmationEligible(display.source,display.hasTextValue);
    }
    private boolean identityConfirmationEligible(SourceDocument d,boolean hasTextValue) {
        boolean failed="FAILED".equals(d.getParseStatus())&&Arrays.asList("unknown","tender","standard","project_fact","package_manifest").contains(VettingCorpus.sourceRole(d));
        if(!ProjectDocumentScope.reviewCategory(d)||!failed&&!"IDENTITY_PENDING".equals(d.getParseStatus())
                ||!hasTextValue||!JsonUtils.isBlankText(d.getReviewRole())&&!Arrays.asList("tender","standard","project_fact","package_manifest").contains(d.getReviewRole())
                ||Arrays.asList("drawing","example","pricing","works_pss").contains(d.getDocumentScope()))return false;
        com.consense.document.OerDocumentIdentity identity=OerSourceMetadata.identity(d);
        return identity!=null&&!"source_identity_mismatch".equals(identity.getIdentityStatus())
                &&!Arrays.asList("DRAWING","BQ","SOR","GENERAL_SUMMARY","WORKS_PSS","SL_NON_TECHNICAL").contains(identity.getKind())
                &&!Arrays.asList("standard_template","evaluation_answer","drawing").contains(identity.getSourcePurpose())
                &&OerSourceMetadata.moduleExclusion(d,"vetting")==null&&OerSourceMetadata.moduleExclusion(d,"advisory")==null
                &&identity.getCandidates().stream().noneMatch(c->"DRAWING".equals(c.getKind()));
    }
    private static boolean validRoleHash(String value){return value!=null&&value.matches("[a-f0-9]{64}");}
    private SourceDocument roleSourceSnapshot(Long id) {
        SourceDocumentRepository.VerificationMetadata m=sourceDocumentRepository.findVerificationMetadataById(id)
                .orElseThrow(()->new BizException(4104,"来源文件已变化，请刷新后确认用途。"));
        SourceDocument d=new SourceDocument();d.setId(m.getId());d.setProjectId(m.getProjectId());d.setCategory(m.getCategory());
        d.setFileName(m.getFileName());d.setFileKey(m.getFileKey());d.setReviewRole(m.getReviewRole());d.setDocumentScope(m.getDocumentScope());
        d.setParseStatus(m.getParseStatus());d.setSourceSha256(m.getSourceSha256());d.setStoragePath(m.getStoragePath());
        d.setOerIdentityJson(m.getOerIdentityJson());d.setTextContent(m.getTextContent());d.setStructuredContentJson(m.getStructuredContentJson());return d;
    }
    private void assertRoleSourceCurrent(SourceDocument d) {
        List<SourceDocument> catalog=new ArrayList<>();
        for(SourceDocumentRepository.CatalogMetadata m:sourceDocumentRepository.findCatalogMetadataByProjectId(d.getProjectId())) {
            SourceDocument item=new SourceDocument();item.setId(m.getId());item.setProjectId(m.getProjectId());item.setCategory(m.getCategory());
            item.setFileName(m.getFileName());item.setFileKey(m.getFileKey());catalog.add(item);
        }
        if(ProjectDocumentScope.currentCatalog(d.getProjectId(),catalog).stream().noneMatch(item->Objects.equals(item.getId(),d.getId())))
            throw new BizException(4104,"来源文件已被同名新版本替代，请刷新后确认用途。");
    }
    public VettingFileVO confirmSourceRole(String projectId,Long documentId,SourceRoleConfirmationRequest request) {
        projectService.require(projectId);
        boolean legacy=request!=null&&validRoleHash(request.getExpectedSourceHash());
        boolean stamped=request!=null&&validRoleHash(request.getExpectedBusinessContentSha256());
        if(request==null || !Arrays.asList("tender","standard","project_fact","package_manifest").contains(request.getSourceRole())
                || !validRoleHash(request.getExpectedOriginalSourceSha256()) || legacy==stamped || !validRoleHash(request.getExpectedRoutingSha256())
                || request.isConfirmTextContractMaterial()&&!legacy)
            throw new BizException(4101,"请选择有效文件用途，并刷新来源版本后确认。");
        if(request.isConfirmTextContractMaterial())return resumeIdentityPending(projectId,documentId,request);
        if(stamped)return confirmSourceRoleStamped(projectId,documentId,request);
        synchronized(lock(projectId)) { return tx().execute(status->{
            if(runRepository.existsByProjectIdAndStatusIn(projectId,ACTIVE_STATES))throw new BizException(4104,"审查正在运行，请完成后再确认文件用途。");
            SourceDocument before=roleSourceSnapshot(documentId);
            if(!Objects.equals(projectId,before.getProjectId()) || !roleConfirmationEligible(before))throw new BizException(4104,"此文件当前不能确认用途，请刷新并检查解析状态和范围。");
            assertRoleSourceCurrent(before);
            if(!Objects.equals(request.getExpectedOriginalSourceSha256(),before.getSourceSha256())
                    || !Objects.equals(request.getExpectedSourceHash(),VettingCorpus.sourceHash(before))
                    || !Objects.equals(request.getExpectedRoutingSha256(),com.consense.service.sources.CanonicalEvidenceService.routingHash(before)))
                throw new BizException(4104,"来源版本已变化，请刷新后确认用途。");
            if(!storageService.matchesStoredBytes(before.getStoragePath(),before.getSourceSha256()))throw new BizException(4104,"原件字节已变化，不能确认当前用途。");
            if(sourceDocumentRepository.compareAndSetReviewRole(before.getId(),projectId,before.getSourceSha256(),before.getCategory(),before.getFileName(),before.getOerIdentityJson(),
                    before.getTextContent(),before.getStructuredContentJson(),before.getFileKey(),before.getReviewRole(),before.getDocumentScope(),before.getParseStatus(),before.getStoragePath(),request.getSourceRole())!=1)
                throw new BizException(4104,"来源或审查状态已变化，请刷新后确认用途。");
            SourceDocument after=roleSourceSnapshot(documentId);String chosen=after.getReviewRole();after.setReviewRole(before.getReviewRole());
            if(!request.getSourceRole().equals(chosen) || !request.getExpectedSourceHash().equals(VettingCorpus.sourceHash(after))
                    || !request.getExpectedRoutingSha256().equals(com.consense.service.sources.CanonicalEvidenceService.routingHash(after)))
                throw new BizException(4104,"来源版本已变化，本次用途确认未保存。");
            after.setReviewRole(chosen);assertRoleSourceCurrent(after);
            if(runRepository.existsByProjectIdAndStatusIn(projectId,ACTIVE_STATES) || !storageService.matchesStoredBytes(after.getStoragePath(),after.getSourceSha256()))
                throw new BizException(4104,"原件或审查状态已变化，本次用途确认未保存。");
            String previousIdentity=after.getOerIdentityJson();OerSourceMetadata.confirmSourceUse(after,false);
            if(!Objects.equals(previousIdentity,after.getOerIdentityJson())&&sourceDocumentRepository.compareAndSetRoutingIdentity(documentId,projectId,after.getSourceSha256(),previousIdentity,after.getOerIdentityJson())!=1)
                throw new BizException(4104,"来源许可已变化，本次用途确认未保存。");
            VettingFileVO row=fileDisplaySourceSet(projectId).stream().filter(d->Objects.equals(d.source.getId(),documentId)).findFirst().map(this::fileVO)
                    .orElseThrow(()->new BizException(4104,"来源文件已变化，本次用途确认未保存。"));
            row.setOriginalSourceSha256(after.getSourceSha256());row.setSourceHash(VettingCorpus.sourceHash(after));
            row.setRoutingSha256(com.consense.service.sources.CanonicalEvidenceService.routingHash(after));return row;
        }); }
    }

    private VettingFileVO confirmSourceRoleStamped(String projectId,Long documentId,SourceRoleConfirmationRequest request) {
        synchronized(lock(projectId)) { return tx().execute(status->{
            if(runRepository.existsByProjectIdAndStatusIn(projectId,ACTIVE_STATES))throw new BizException(4104,"审查正在运行，请完成后再确认文件用途。");
            FileDisplay display=fileDisplaySourceSet(projectId).stream().filter(row->Objects.equals(row.source.getId(),documentId)).findFirst()
                    .orElseThrow(()->new BizException(4104,"来源文件已变化，请刷新后确认用途。"));
            SourceDocument before=display.source;
            if(!roleConfirmationEligible(display)||!SourceContentDigests.stamped(before)
                    ||!Objects.equals(request.getExpectedBusinessContentSha256(),before.getBusinessContentSha256())
                    ||!Objects.equals(request.getExpectedOriginalSourceSha256(),before.getSourceSha256())
                    ||!Objects.equals(request.getExpectedRoutingSha256(),display.routingSha))
                throw new BizException(4104,"来源版本已变化，请刷新后确认用途。");
            assertRoleSourceCurrent(before);
            if(!storageService.matchesStoredBytes(before.getStoragePath(),before.getSourceSha256()))throw new BizException(4104,"原件字节已变化，不能确认当前用途。");
            if(sourceDocumentRepository.compareAndSetReviewRoleStamped(before.getId(),projectId,before.getSourceSha256(),before.getBusinessContentSha256(),
                    before.getCategory(),before.getFileName(),before.getOerIdentityJson(),before.getFileKey(),before.getReviewRole(),
                    before.getDocumentScope(),before.getParseStatus(),before.getStoragePath(),request.getSourceRole())!=1)
                throw new BizException(4104,"来源或审查状态已变化，请刷新后确认用途。");
            SourceDocument after=sourceDocumentRepository.findScopeMetadataById(documentId).map(SourceDocumentRepository::scopeSnapshot)
                    .orElseThrow(()->new BizException(4104,"来源文件已变化，本次用途确认未保存。"));
            String chosen=after.getReviewRole();after.setReviewRole(before.getReviewRole());
            if(!request.getSourceRole().equals(chosen)||!Objects.equals(request.getExpectedBusinessContentSha256(),after.getBusinessContentSha256())
                    ||!Objects.equals(request.getExpectedRoutingSha256(),CanonicalEvidenceService.routingHash(after)))
                throw new BizException(4104,"来源版本已变化，本次用途确认未保存。");
            after.setReviewRole(chosen);assertRoleSourceCurrent(after);
            if(runRepository.existsByProjectIdAndStatusIn(projectId,ACTIVE_STATES)||!storageService.matchesStoredBytes(after.getStoragePath(),after.getSourceSha256()))
                throw new BizException(4104,"原件或审查状态已变化，本次用途确认未保存。");
            String previousIdentity=after.getOerIdentityJson();OerSourceMetadata.confirmSourceUse(after,false,display.hasText);
            if(!Objects.equals(previousIdentity,after.getOerIdentityJson())&&sourceDocumentRepository.compareAndSetRoutingIdentity(documentId,projectId,after.getSourceSha256(),previousIdentity,after.getOerIdentityJson())!=1)
                throw new BizException(4104,"来源许可已变化，本次用途确认未保存。");
            SourceDocument current=sourceDocumentRepository.findScopeMetadataById(documentId).map(SourceDocumentRepository::scopeSnapshot)
                    .orElseThrow(()->new BizException(4104,"来源文件已变化，本次用途确认未保存。"));
            if(!SourceContentDigests.stamped(current)||!Objects.equals(request.getExpectedBusinessContentSha256(),current.getBusinessContentSha256())
                    ||!Objects.equals(request.getSourceRole(),current.getReviewRole())||!storageService.matchesStoredBytes(current.getStoragePath(),current.getSourceSha256()))
                throw new BizException(4104,"来源版本已变化，本次用途确认未保存。");
            VettingFileVO row=fileDisplaySourceSet(projectId).stream().filter(d->Objects.equals(d.source.getId(),documentId)).findFirst().map(this::fileVO)
                    .orElseThrow(()->new BizException(4104,"来源文件已变化，本次用途确认未保存。"));
            row.setOriginalSourceSha256(current.getSourceSha256());row.setRoutingSha256(CanonicalEvidenceService.routingHash(current));
            row.setBusinessContentSha256(current.getBusinessContentSha256());return row;
        }); }
    }

    /** A human confirms the original is textual material; the usual parser still decides usable coverage. */
    private VettingFileVO resumeIdentityPending(String projectId,Long documentId,SourceRoleConfirmationRequest request) {
        SourceDocument claimed;byte[] sourceBytes;
        synchronized(lock(projectId)) {
            SourceDocument before=roleSourceSnapshot(documentId);
            if(!Objects.equals(projectId,before.getProjectId())||!identityConfirmationEligible(before)
                    ||"FAILED".equals(before.getParseStatus())&&!"unknown".equals(VettingCorpus.sourceRole(before))&&!Objects.equals(VettingCorpus.sourceRole(before),request.getSourceRole()))
                throw new BizException(4104,"此文件当前不能恢复详细解析，请刷新并核对身份和范围。");
            if(sourceDocumentRepository.existsCurrentParsingByProjectId(projectId))throw new BizException(4104,"仍有文件正在解析，请待完成后再确认其他文件。");
            assertRoleSourceCurrent(before);
            if(!Objects.equals(request.getExpectedOriginalSourceSha256(),before.getSourceSha256())
                    ||!Objects.equals(request.getExpectedSourceHash(),VettingCorpus.sourceHash(before))
                    ||!Objects.equals(request.getExpectedRoutingSha256(),com.consense.service.sources.CanonicalEvidenceService.routingHash(before)))
                throw new BizException(4104,"来源版本已变化，请刷新后确认原件。");
            if(!storageService.matchesStoredBytes(before.getStoragePath(),before.getSourceSha256()))throw new BizException(4104,"原件字节已变化，不能恢复解析。");
            sourceBytes=storageService.read(before.getStoragePath());
            if(!before.getSourceSha256().equals(OerSourceMetadata.originalSha256(sourceBytes))
                    ||com.consense.document.OerSourceRouting.isDrawing(before.getDocumentScope(),before.getFileName(),sourceBytes))
                throw new BizException(4104,"原件字节或图纸身份不允许恢复详细解析。");
            SourceDocument known=new SourceDocument();OerSourceMetadata.captureKnownIdentity(known,sourceBytes);
            if(OerSourceMetadata.moduleExclusion(known,"vetting")!=null||OerSourceMetadata.moduleExclusion(known,"advisory")!=null)
                throw new BizException(4104,"已知排除原件不能由人工确认覆盖。");
            claimed=tx().execute(status->{
                if(sourceDocumentRepository.compareAndSetReviewRole(before.getId(),projectId,before.getSourceSha256(),before.getCategory(),before.getFileName(),before.getOerIdentityJson(),
                        before.getTextContent(),before.getStructuredContentJson(),before.getFileKey(),before.getReviewRole(),before.getDocumentScope(),before.getParseStatus(),before.getStoragePath(),request.getSourceRole())!=1)
                    throw new BizException(4104,"来源或审查状态已变化，本次未启动详细解析。");
                SourceDocument current=sourceDocumentRepository.findById(documentId).orElseThrow(()->new BizException(4104,"来源文件已变化。"));
                current.setParseStatus("PARSING");current.setParseMessage("已人工核对原件为文字合同材料，正在详细解析；确认不代表全文质量已通过。");
                sourceDocumentRepository.saveAndFlush(current);return roleSourceSnapshot(documentId);
            });
        }
        SourceDocument outcome=new SourceDocument();outcome.setId(claimed.getId());outcome.setProjectId(claimed.getProjectId());outcome.setCategory(claimed.getCategory());
        outcome.setFileName(claimed.getFileName());outcome.setFileKey(claimed.getFileKey());outcome.setReviewRole(claimed.getReviewRole());outcome.setDocumentScope(claimed.getDocumentScope());outcome.setSourceSha256(claimed.getSourceSha256());
        com.consense.document.OerDocumentIdentity readingIdentity=OerSourceMetadata.identity(claimed);
        readingIdentity.setIdentityStatus("human_confirmed_textual_material");readingIdentity.setRole(claimed.getReviewRole());
        readingIdentity.getIdentityEvidence().add(new com.consense.document.OerDocumentIdentity.Evidence("human_text_contract_material","original:"+claimed.getSourceSha256(),
                "Explicit original-byte-bound textual reading confirmation; source family, edition, quality and legal applicability are not newly established"));
        String parseJobId=DocumentParseProbe.configured(props)?"parse-"+claimed.getSourceSha256().substring(0,12)+"-"+UUID.randomUUID():null;
        try (AutoCloseable progress=observeParseProgress(claimed)) {
            DocumentParser.ParsedDocument parsed=parseJobId==null?documentParser.parseOrdinary(claimed.getFileName(),sourceBytes,null,readingIdentity)
                    :documentParser.parseOrdinary(claimed.getFileName(),sourceBytes,parseJobId,String.valueOf(documentId),claimed.getSourceSha256(),null,readingIdentity);
            if("FAILED".equals(parsed.getParseStatus())) {outcome.setParseStatus("FAILED");outcome.setParseMessage(truncate(parsed.getMessage(),1024));}
            else captureParsedSource(outcome,parsed,sourceBytes,true);
        } catch(Exception failure) {outcome.setParseStatus("FAILED");outcome.setParseMessage(truncate(brief(failure),1024));}
        VettingFileVO result;
        synchronized(lock(projectId)) {
            result=tx().execute(status->{
                if(sourceDocumentRepository.compareAndSetReviewRole(claimed.getId(),projectId,claimed.getSourceSha256(),claimed.getCategory(),claimed.getFileName(),claimed.getOerIdentityJson(),
                        claimed.getTextContent(),claimed.getStructuredContentJson(),claimed.getFileKey(),claimed.getReviewRole(),claimed.getDocumentScope(),claimed.getParseStatus(),claimed.getStoragePath(),claimed.getReviewRole())!=1)
                    throw new BizException(4104,"来源或审查状态已变化，解析结果未覆盖当前来源。");
                SourceDocument current=sourceDocumentRepository.findById(documentId).orElseThrow(()->new BizException(4104,"来源文件已变化。"));
                if(!storageService.matchesStoredBytes(current.getStoragePath(),current.getSourceSha256())) {outcome.setParseStatus("FAILED");outcome.setParseMessage("解析期间原件字节已变化，本次结果未采用；原观察已保留。");}
                if(!"FAILED".equals(outcome.getParseStatus())) {
                    current.setPageCount(outcome.getPageCount());current.setOcrUsed(outcome.getOcrUsed());current.setTextContent(outcome.getTextContent());current.setStructuredContentJson(outcome.getStructuredContentJson());
                    current.setParseCoverageJson(outcome.getParseCoverageJson());current.setOerIdentityJson(outcome.getOerIdentityJson());current.setNativePdfEvidenceJson(outcome.getNativePdfEvidenceJson());
                    current.setNormalCandidateJson(outcome.getNormalCandidateJson());current.setFileKey(outcome.getFileKey());
                }
                current.setParseStatus(outcome.getParseStatus());current.setParseMessage(outcome.getParseMessage());sourceDocumentRepository.saveAndFlush(current);
                VettingFileVO row=fileVO(current);row.setOriginalSourceSha256(current.getSourceSha256());row.setSourceHash(VettingCorpus.sourceHash(current));
                row.setRoutingSha256(com.consense.service.sources.CanonicalEvidenceService.routingHash(current));return row;
            });
        }
        if(parseJobId!=null)DocumentParseProbe.bindPersisted(props,parseJobId,String.valueOf(documentId),claimed.getSourceSha256(),result.getSourceHash(),result.getParseStatus());
        return result;
    }
    private void captureParsedSource(SourceDocument source,DocumentParser.ParsedDocument parsed,byte[] original,boolean confirmedText) {
        source.setParseStatus(parsed.getParseStatus());source.setParseMessage(truncate(parsed.getMessage(),1024));source.setPageCount(parsed.getPageCount());source.setOcrUsed(parsed.isOcrUsed());source.setTextContent(parsed.getText());
        OerSourceMetadata.captureOrdinary(source,parsed,original);if(confirmedText)OerSourceMetadata.confirmSourceUse(source,true);NormalSourceCandidateGraph.capture(source);
    }

    /** No transaction is held during parsing or OCR. */
    public UploadResultVO uploadPackage(String projectId,List<MultipartFile> files) {
        return uploadPackage(projectId, files, null);
    }
    public UploadResultVO uploadPackage(String projectId,List<MultipartFile> files,String sourceRole) {
        return uploadPackage(projectId,files,sourceRole,null);
    }
    public UploadResultVO uploadPackage(String projectId,List<MultipartFile> files,String sourceRole,String documentScope) {
        projectService.require(projectId);
        if (!projectId.matches("[A-Za-z0-9_-]+")) throw new BizException(4101,"项目编号含无效路径字符");
        String role = validatedRole(sourceRole);
        String boundary=com.consense.service.sources.ProjectDocumentScope.normalizeDocumentScope(documentScope);
        int accepted=0,parsed=0,failed=0; List<String> messages=new ArrayList<>();
        for (MultipartFile file : files) {
            if (file==null || file.isEmpty()) continue; accepted++;
            StorageService.StoredFile stored=storageService.store(projectId,SourceDocument.CATEGORY_VETTING_PACKAGE,file);
            SourceDocument d=new SourceDocument(); d.setProjectId(projectId); d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE); d.setFileKey(matchFileKey(stored.getOriginalName()));
            d.setFileName(stored.getOriginalName()); d.setContentType(stored.getContentType()); d.setSizeBytes(stored.getSize()); d.setStoragePath(stored.getPath()); d.setCreatedAt(Instant.now()); d.setParseStatus("PARSING");
            d.setParseMessage("正在识别文件身份与解析范围 · 更新于 "+Instant.now());
            // Preserve only an explicit choice; auto inference remains distinguishable and can improve safely.
            d.setReviewRole(role);
            d.setDocumentScope(boundary);
            if ("package_manifest".equals(d.getReviewRole()) && "OTHER".equals(d.getFileKey())) d.setFileKey("INDEX");
            sourceDocumentRepository.saveAndFlush(d);
            String parseJobId=null,rawSha256=null;
            try {
                byte[] sourceBytes=storageService.read(stored.getPath());
                d.setSourceSha256(com.consense.service.sources.OerSourceMetadata.originalSha256(sourceBytes));
                if (com.consense.document.OerSourceRouting.isDrawing(boundary,d.getFileName(),sourceBytes)) {
                    com.consense.service.sources.OerSourceMetadata.captureDrawing(d,sourceBytes);
                    d.setParseStatus("EXCLUDED"); d.setParseMessage("图纸文件仅识别身份，已排除解析和业务取证。");
                    d.setPageCount(0); d.setOcrUsed(false); d.setTextContent(""); d.setStructuredContentJson("[]");
                    sourceDocumentRepository.saveAndFlush(d);
                    messages.add(d.getFileName()+" · EXCLUDED · "+d.getParseMessage());
                    continue;
                }
                boolean known=com.consense.service.sources.OerSourceMetadata.captureKnownIdentity(d,sourceBytes);
                String knownExclusion=known?com.consense.service.sources.OerSourceMetadata.moduleExclusion(d,"vetting"):null;
                if(knownExclusion!=null||Arrays.asList("example","pricing","works_pss").contains(boundary)) {
                    d.setParseStatus("EXCLUDED");d.setParseMessage("文件不在本次审查解析范围内："+(knownExclusion==null?boundary:knownExclusion));
                    d.setOcrUsed(false);d.setTextContent("");d.setStructuredContentJson("[]");
                    sourceDocumentRepository.saveAndFlush(d);messages.add(d.getFileName()+" · EXCLUDED · "+d.getParseMessage());continue;
                }
                DocumentParser.ParsedDocument observation=documentParser.observeOrdinary(stored.getOriginalName(),sourceBytes);
                d.setParseStatus(observation.getParseStatus());d.setParseMessage(truncate(observation.getMessage(),1024));
                d.setPageCount(observation.getPageCount());d.setOcrUsed(observation.isOcrUsed());d.setTextContent(observation.getText());
                boolean readable=com.consense.service.sources.OerSourceMetadata.captureOrdinaryObservation(d,observation,sourceBytes);
                String exclusion=com.consense.service.sources.ProjectDocumentScope.exclusionReason(d,"fullset","vetting");
                if(!readable||exclusion!=null&&!"source_identity_unresolved".equals(exclusion)) {
                    d.setParseStatus(!readable?"IDENTITY_PENDING":"EXCLUDED");
                    d.setParseMessage("IDENTITY_PENDING".equals(d.getParseStatus())?"身份待确认，已保留原件和初步观察；确认前不进行详细解析。":"文件不在本次审查解析范围内："+exclusion);
                    sourceDocumentRepository.saveAndFlush(d);messages.add(d.getFileName()+" · "+d.getParseStatus()+" · "+d.getParseMessage());continue;
                }
                d.setParseStatus("PARSING");d.setParseMessage("文件识别观察已保存，正在进行详细解析；最终结果尚未就绪。");
                sourceDocumentRepository.saveAndFlush(d);
                DocumentParser.ParsedDocument p;
                try(AutoCloseable progress=observeParseProgress(d)) {
                if(DocumentParseProbe.configured(props)) {
                    rawSha256=DocumentParseProbe.sha256(sourceBytes);
                    parseJobId="parse-"+rawSha256.substring(0,12)+"-"+UUID.randomUUID();
                    p=documentParser.parseOrdinary(stored.getOriginalName(),sourceBytes,parseJobId,String.valueOf(d.getId()),rawSha256,observation,com.consense.service.sources.OerSourceMetadata.identity(d));
                }else p=documentParser.parseOrdinary(stored.getOriginalName(),sourceBytes,observation,com.consense.service.sources.OerSourceMetadata.identity(d));
                }
                sourceDocumentRepository.updateParseProgress(d.getId(),d.getSourceSha256(),"解析已返回，正在保存正文、结构与来源观察 · 更新于 "+Instant.now());
                captureParsedSource(d,p,sourceBytes,false);
                if ("FAILED".equals(p.getParseStatus())) failed++; else parsed++;
                messages.add(d.getFileName()+" · "+p.getParseStatus()+" · "+p.getMessage());
            } catch(Exception e) { d.setParseStatus("FAILED"); d.setParseMessage(truncate(brief(e),1024)); failed++; messages.add(d.getFileName()+" 解析失败："+brief(e)); }
            sourceDocumentRepository.saveAndFlush(d);
            if(parseJobId!=null)DocumentParseProbe.bindPersisted(props,parseJobId,String.valueOf(d.getId()),rawSha256,VettingCorpus.sourceHash(d),d.getParseStatus());
        } return new UploadResultVO(accepted,parsed,failed,messages);
    }

    public VettingJobVO startRun(String projectId,String lang) {
        projectService.require(projectId);
        synchronized(lock(projectId)) {
            VettingRun latest=sourceDigestPreparation==null?runRepository.findFirstByProjectIdOrderByStartedAtDesc(projectId).orElse(null):
                    runRepository.findLatestProgress(projectId,org.springframework.data.domain.PageRequest.of(0,1)).stream().findFirst()
                    .filter(row->ACTIVE_STATES.contains(row.getStatus())).flatMap(row->runRepository.findById(row.getId())).orElse(null);
            if(latest!=null && ACTIVE_STATES.contains(latest.getStatus())) return jobVO(latest);
            if(sourceDocumentRepository.existsCurrentParsingByProjectId(projectId))throw new BizException(4104,"仍有文件正在解析，请待完成后再开始审查。");
            if(fixedBaseline!=null&&fixedBaseline.enabled()) {
                try { fixedBaseline.projectSelection(projectId); }
                catch(IllegalStateException failure) {
                    if(failure.getMessage()!=null&&failure.getMessage().startsWith("Fixed development reference rejected: fixed_sl_selection_"))
                        throw new BizException(4104,"Select and save at least one current SL edition in 'SL editions and library' before running Vetting. Refresh its status if the saved selection is stale.","fixed_sl_selection_required_or_stale",failure);
                    throw failure;
                }
            }
            com.consense.ai.LlmOperation operation=llmProfiles.capture();
            VettingLlmOperation vettingOperation=semantic.captureOperation(operation);
            String supplement=semantic.configuredSupplement();
            List<SourceDocument> selectedSources=canonicalEnabled()&&sourceDigestPreparation!=null?freshSourceStamps(projectId):sourceSet(projectId);
            if(!compactSources(selectedSources)&&canonicalEnabled()&&sourceDigestPreparation!=null)selectedSources=sourceSet(projectId);
            final List<SourceDocument> snapshot=selectedSources;
            boolean compact=compactSources(snapshot);
            List<SourceDocument> admitted=snapshot;
            List<SourceDocument> preparedStamps=null;
            if(canonicalEnabled()&&!compact) {
                // Freeze the prepared graph with its source, before run hashes and identity closures are captured.
                admitted=new ArrayList<>();
                for(SourceDocument document:snapshot)try {
                    JsonNode prepared=evidencePreparation.prepareNormalCandidate(projectId,document.getId(),"vetting");
                    if(prepared!=null)document.setNormalCandidateJson(JsonUtils.write(prepared));
                    admitted.add(document);
                }catch(com.consense.service.sources.CanonicalEvidenceException unavailable){
                    if(!missingObservedEvidence(unavailable))throw unavailable;
                    log.warn("Selected Vetting source {} '{}' has no usable observed evidence and remains unreviewed (parseStatus={})",
                            document.getId(),document.getFileName(),document.getParseStatus());
                }
                assertMaterialSourcesCurrent(projectId,snapshot);
                preparedStamps=currentSourceStamps(projectId,snapshot);
                Map<Long,SourceDocument> byId=new HashMap<>();for(SourceDocument stamp:preparedStamps)
                    if(byId.put(stamp.getId(),stamp)!=null)throw new BizException(4104,"Duplicate source revision");
                if(byId.size()!=snapshot.size())throw new BizException(4104,"Review source selection changed");
                for(SourceDocument document:snapshot){SourceDocument stamp=byId.remove(document.getId());
                    if(stamp==null)throw new BizException(4104,"Review source selection changed");
                    if(SourceContentDigests.stamped(stamp))SourceContentDigests.rebindVerifiedCurrent(document,stamp);}
            }
            if(!compact&&!originalSourcesCurrent(snapshot))throw new BizException(4104,"审查原件字节与已保存版本不一致，请重新上传和解析后审查。");
            if(snapshot.isEmpty()||(canonicalEnabled()?admitted.isEmpty():snapshot.stream().noneMatch(this::reviewable)))
                throw new BizException(4101,"No reviewable source text is available. Check the selected files' parsing status.");
            String sourceStamp=sourceDigestPreparation==null?null:sourceStampHash(preparedStamps==null?currentSourceStamps(projectId,snapshot):preparedStamps);
            if(sourceDigestPreparation!=null&&sourceStamp==null)throw new BizException(4104,"Selected source content revisions are unavailable; repair the source digest before review.");
            VettingRun run=new VettingRun(); run.setId(UUID.randomUUID().toString()); run.setProjectId(projectId); run.setLang(normalizeLang(lang)); run.setStartedAt(Instant.now());
            run.setSourceSnapshotSha256(compact?compactSourceSetHash(snapshot):sourceSetHash(snapshot));
            run.setRecoveryContractSha256(recoveryContract(supplement,vettingOperation));run.setModelProfileExplicit(operation.isExplicit());run.setRecoveryStatus("NEW");
            if(checkpoints!=null){run.setExecutionOwner(executionOwner);run.setLeaseUntil(Instant.now().plusSeconds(VettingCheckpointService.LEASE_SECONDS));}
            run.setModelIdentityJson(JsonUtils.write(vettingOperation==null?operation.getIdentity():vettingOperation.getIdentity()));
            run.setStatus("QUEUED"); run.setPhase("queued"); run.setTotalUnits(topicCount()+2); run.setMessage("审查已排队，人工处置按问题指纹保留。");
            CoverageVO queuedCoverage=coverage(snapshot,Collections.emptyList(),Collections.emptySet(),Collections.emptyList(),false);
            if(canonicalEnabled())queuedCoverage.setCanonicalSourceDocumentIds(admitted.stream().map(SourceDocument::getId).collect(Collectors.toList()));
            queuedCoverage.setSourceStampSha256(sourceStamp);
            queuedCoverage.setPackageIdentityReview(freezePackageIdentity(packageAuthority(projectId),run.getSourceSnapshotSha256()));
            queuedCoverage.setPackageIdentityMirrorAudit(packageMirrorAudit(null,projectService.require(projectId)));
            run.setCoverageJson(JsonUtils.write(queuedCoverage));
            // Original REQUIRED insert call before dispatch; a caller-owned outer transaction can remain active.
            tx().execute(status -> runRepository.saveAndFlush(run));
            CompletableFuture<RunResultVO> done=new CompletableFuture<>(); completions.put(run.getId(),done);
            VettingServiceProbe lifecycle=VettingServiceProbe.start(props,run.getId(),projectId,"queued_transaction_returned");
            if(lifecycle.enabled())serviceProbes.put(run.getId(),lifecycle);
            lifecycle.observe("queued_transaction_returned",()->VettingServiceProbe.returnedTransaction("run",run,"sourceSnapshot",sourceMetadata(snapshot),
                    "boundary","Original REQUIRED queued insert returned; outer transaction commit can remain unknown. Snapshots do not enter corpus/prompts"));
            try { dispatch(run,snapshot,operation,vettingOperation,done,lifecycle,supplement); }
            catch(RejectedExecutionException e) {
                lifecycle.originalFailure("worker_dispatch_rejected",e);
                try{failRun(run.getId(),"审查队列已满，请稍后重试。",done,lifecycle);}finally{lifecycle.finish("queue_rejected",e);serviceProbes.remove(run.getId(),lifecycle);}
            }
            return jobVO(runRepository.findById(run.getId()).orElse(run));
        }
    }
    public RunResultVO run(String projectId,String lang) {
        VettingJobVO job=startRun(projectId,lang); CompletableFuture<RunResultVO> done=completions.get(job.getId());
        if(done==null) { VettingJobVO saved=getRun(projectId,job.getId()); if("COMPLETED".equals(saved.getStatus())) return saved.getResult(); throw new BizException(4101,available(saved.getError(),"任务无法继续")); }
        try { return done.get(); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new BizException(4101,"等待审查已中断，后台任务仍可通过任务接口查看"); }
        catch(ExecutionException e) { throw new BizException(4101,brief(e.getCause())); }
    }
    public VettingJobVO latestRun(String projectId) { projectService.require(projectId); return runRepository.findFirstByProjectIdOrderByStartedAtDesc(projectId).map(this::jobVO).orElse(null); }
    public VettingJobVO getRun(String projectId,String id) { projectService.require(projectId); return runRepository.findById(id).filter(r -> projectId.equals(r.getProjectId())).map(this::jobVO).orElseThrow(() -> new BizException(4102,"审查任务不存在")); }
    public JsonNode getRunDisplay(String projectId,String id) {
        projectService.require(projectId);
        VettingRun run=runRepository.findById(id).filter(r->projectId.equals(r.getProjectId()))
                .orElseThrow(()->new BizException(4102,"审查任务不存在"));
        VettingJobVO job=runSummary(run);
        job.setSourceSetStale(sourceSetStale(run,true));
        ObjectNode display=JsonUtils.mapper().valueToTree(job);
        display.set("coverage",displayCoverage(JsonUtils.parse(run.getCoverageJson())));
        return display;
    }

    private static ObjectNode displayCoverage(JsonNode source) {
        ObjectNode coverage=displayFields(source,"totalDocuments","reviewedDocuments","documents","warnings","reviewCompleteness");
        ArrayNode topics=coverage.putArray("semanticTopics");
        for(JsonNode topic:source.path("semanticTopics")) {
            ObjectNode shown=displayFields(topic,"topicIndex","topic","reviewKind","referenceIds","status","globalCallStatus",
                    "aggregateReviewStatus","reviewCoverageScope","semanticScopeVerified","checkState","error","sourceRequestCount",
                    "pendingSourceRequestCount","extraPacketCount","failedPacketCount","notSubmittedPacketCount","submittedChars",
                    "budgetDroppedGroups","partialContextGroups","unresolvedSegments","issueAssessments","consistentAssessments",
                    "insufficientContextAssessments","acceptedFindings","rejectedRecords");
            shown.put("submittedChunkCount",topic.path("submittedChunkIds").size());
            ArrayNode packets=shown.putArray("packetAudits");
            for(JsonNode packet:topic.path("packetAudits")) {
                ObjectNode summary=displayFields(packet,"packetId","packetIndex","kind","status","checkState","inputBudgetStatus",
                        "actualGatewayCallStarted","failureKind","error","sourceObservationPacketId","sourceSnapshotSha256");
                JsonNode observation=packet.path("inputBudgetMetadata").path("observation");
                if(observation.isObject())summary.putObject("inputBudgetMetadata").set("observation",
                        displayFields(observation,"inputTokens","outputReserveTokens","effectiveContextTokens"));
                packets.add(summary);
            }
            ArrayNode requests=shown.putArray("sourceRequests");
            for(JsonNode request:topic.path("sourceRequests")) {
                ObjectNode summary=displayFields(request,"requestId","transportStatus","reviewExecutionStatus","unknownReason","packetId");
                summary.put("requiredMemberCount",request.path("requiredChunkIds").size());
                summary.put("missingMemberCount",request.path("missingChunkIds").size());
                requests.add(summary);
            }
            topics.add(shown);
        }
        return coverage;
    }

    private static ObjectNode displayFields(JsonNode source,String... names) {
        ObjectNode shown=JsonUtils.mapper().createObjectNode();
        for(String name:names)if(source.has(name))shown.set(name,source.get(name));
        return shown;
    }

    public VettingProgressVO latestProgress(String projectId) {
        projectService.require(projectId);
        return runRepository.findLatestProgress(projectId,org.springframework.data.domain.PageRequest.of(0,1))
                .stream().findFirst().map(this::progressVO).orElse(null);
    }
    public VettingProgressVO getProgress(String projectId,String id) {
        projectService.require(projectId);
        return runRepository.findProgress(projectId,id).map(this::progressVO)
                .orElseThrow(() -> new BizException(4102,"审查任务不存在"));
    }
    private VettingProgressVO progressVO(VettingRunRepository.ProgressView r) {
        return new VettingProgressVO(r.getId(),r.getStatus(),r.getPhase(),r.getCompletedUnits(),r.getTotalUnits(),
                r.getMessage(),r.getError(),r.getStartedAt(),r.getFinishedAt(),r.getSourceSnapshotSha256(),
                JsonUtils.read(r.getModelIdentityJson(),com.consense.ai.ModelIdentity.class),r.getRecoveryStatus());
    }

    private void executeRun(VettingRun dispatchedRun,String projectId,String lang,List<SourceDocument> snapshot,Map<Long,OriginalFileState> originalPins,CompletableFuture<RunResultVO> done,VettingServiceProbe probe) {
        String id=dispatchedRun.getId();
        Throwable originalFailure=null;String outcome="aborted",stage="rules_progress";long stageStarted=System.nanoTime(),stageOverhead=probe.overheadNanos();
        long runStarted=stageStarted,runOverhead=stageOverhead;
        try {
            VettingRun capturedRun=runRepository.findById(id).orElseThrow(()->new BizException(4102,"Review run no longer exists"));
            CoverageVO capturedCoverage=JsonUtils.read(capturedRun.getCoverageJson(),CoverageVO.class);
            JsonNode queuedIdentity=capturedCoverage==null?null:capturedCoverage.getPackageIdentityReview();
            if(queuedIdentity==null)throw new BizException(4104,"This run has no frozen package identity contract; rerun instead of upgrading historical results.");
            boolean compact=compactSources(snapshot);
            if(compact&&currentSources!=null)for(SourceDocument source:snapshot)originalPins.put(source.getId(),currentSources.observeOriginal(source));
            assertMaterialSourcesCurrent(projectId,snapshot,!compact);assertPackageIdentityCurrent(projectId,queuedIdentity,capturedRun.getSourceSnapshotSha256());
            List<SourceDocument> admitted=snapshot;
            if(canonicalEnabled()&&!compact) {
                stage="source_preparation";progress(id,0,"preparing","准备当前来源的完整原始观察；不代替原件质量确认。");
                admitted=new ArrayList<>();
                for(SourceDocument document:snapshot)try {
                    evidencePreparation.prepareNormalObservedForSemanticRead(projectId,document.getId(),"vetting");
                    admitted.add(document);
                }catch(com.consense.service.sources.CanonicalEvidenceException unavailable){
                    if(!missingObservedEvidence(unavailable))throw unavailable;
                    log.warn("Selected Vetting source {} '{}' has no usable observed evidence and remains unreviewed (parseStatus={})",
                            document.getId(),document.getFileName(),document.getParseStatus());
                }
                assertMaterialSourcesCurrent(projectId,snapshot);
            }
            if(compact)progress(id,0,"preparing","Preparing the selected source observations one document at a time.");
            stage="rule_sources_construction";stageStarted=System.nanoTime();stageOverhead=probe.overheadNanos();
            final OerBusinessEvidence.Session canonicalSession=canonicalEnabled()?oer.loadSemantic(projectId,"vetting",admitted):null;
            if(compact){
                admitted=snapshot.stream().filter(d->canonicalSession.documentIds.contains(d.getId())).collect(Collectors.toList());
                freezePreparedSources(dispatchedRun,capturedRun,capturedCoverage,snapshot,canonicalSession);
            }
            final JsonNode frozenIdentity=capturedCoverage.getPackageIdentityReview();
            Runnable sourceGuard=()->{assertMaterialSourcesCurrent(projectId,snapshot,!compact);requireOriginalFreshness(snapshot,originalPins);assertPackageIdentityCurrent(projectId,frozenIdentity,capturedRun.getSourceSnapshotSha256());};
            sourceGuard.run();progress(id,0,"rules","对可解析的招标合同条款执行规则检查。");
            if(canonicalSession!=null){progress(id,0,"reading","按顺序读取本次声明范围的完整语义单元，保留条件、表格及未知状态。");oer.scan(canonicalSession);}
            List<SourceDocument> docs=canonicalSession!=null?admitted:snapshot.stream().filter(this::reviewable).collect(Collectors.toList());
            List<VettingRuleEngine.Source> sources=canonicalSession!=null?oer.ruleSources(canonicalSession,admitted):docs.stream().filter(this::contractSource).map(d -> new VettingRuleEngine.Source(String.valueOf(d.getId()),fileKey(d),d.getFileName(),VettingCorpus.sourceHash(d),VettingCorpus.blocks(d),d.getTextContent())).collect(Collectors.toList());
            List<VettingRuleEngine.Source> identitySources=canonicalSession!=null?sources:OerBusinessEvidence.ordinaryRuleSources(projectId,docs.stream().filter(this::contractSource).collect(Collectors.toList()));
            probe.elapsed(stage,stageStarted,stageOverhead,"Original reviewable/contractSource filters and Source construction only",true);
            probe.observe("rule_sources_constructed",()->VettingServiceProbe.map("sources",sources,"ruleSourceCount",sources.size(),"reviewableDocumentCount",docs.size(),
                    "boundary","Exact original RuleEngine inputs; non-contract roles are not operative rule sources"));
            stage="rule_engine_review";stageStarted=System.nanoTime();stageOverhead=probe.overheadNanos();
            sourceGuard.run();
            VettingPackageIdentity.Review identityReview=VettingPackageIdentity.review(identitySources,frozenIdentity.get("authority"));
            JsonNode completedIdentity=completePackageIdentity(frozenIdentity,identityReview);
            List<VettingRuleEngine.Output> rawRules=new ArrayList<>(new VettingRuleEngine().review(sources));rawRules.addAll(identityReview.outputs());
            probe.elapsed(stage,stageStarted,stageOverhead,"Original RuleEngine.review invocation, before observer serialization",true);
            probe.observe("rule_engine_raw_outputs",()->VettingServiceProbe.map("outputs",rawRules));
            Map<String,Map<String,JsonNode>> nativePositions=canonicalSession==null?ruleNativePositions(sources):Collections.emptyMap();
            List<Candidate> candidates=new ArrayList<>(); for(VettingRuleEngine.Output o:rawRules) candidates.add(ruleCandidate(o,nativePositions));
            if(canonicalSession!=null)oer.bindReviewEvidence(canonicalSession,candidates.stream().flatMap(c->c.getEvidence().stream()).collect(Collectors.toList()));
            bindRuleOriginalSources(candidates,snapshot);
            for(Candidate candidate:candidates)bindPackageIdentityEvidence(candidate,completedIdentity);
            progress(id,1,"semantic","规则检查完成，开始完整范围审查及必要的联合对照。");
            stage="corpus_and_semantic_review";stageStarted=System.nanoTime();stageOverhead=probe.overheadNanos();
            List<Chunk> chunks=canonicalSession!=null?canonicalSession.chunks():VettingCorpusConstructionProbe.chunks(props,id,projectId,docs);
            VettingSemanticReview.Result semanticResult=JsonUtils.isBlankText(props.getVetting().getProbeDirectory())
                    ? semantic.reviewWithProgress(null,projectId,chunks,"en",(count,total,message) -> progress(id,count+1,total+2,"semantic",message),docs,sourceGuard)
                    : semantic.reviewWithProgress(id,projectId,chunks,"en",(count,total,message) -> progress(id,count+1,total+2,"semantic",message),docs,sourceGuard);
            probe.elapsed(stage,stageStarted,stageOverhead,"Original corpus and SemanticReview call, inclusive of its independent probes; not pure model latency",true);
            probe.observe("semantic_result_raw_candidates",()->VettingServiceProbe.map("result",semanticResult,
                    "boundary","Actual returned candidates, warnings, reviewed IDs and topic audits before service sorting/source normalization"));
            candidates.addAll(semanticResult.getCandidates());
            bindEvidenceContentRevisions(candidates,snapshot);
            IdentityHashMap<Candidate,List<Integer>> originalOrdinals=new IdentityHashMap<>();
            if(probe.enabled())for(int i=0;i<candidates.size();i++)originalOrdinals.computeIfAbsent(candidates.get(i),value->new ArrayList<>()).add(i);
            probe.observe("combined_candidates_before_sort",()->VettingServiceProbe.map("candidates",candidateOrder(candidates,originalOrdinals)));
            stage="candidate_sort_dedup_risk_cap";stageStarted=System.nanoTime();stageOverhead=probe.overheadNanos();
            candidates.sort(Comparator.comparingInt(c -> severityRank(c.getSeverity())));
            probe.observe("combined_candidates_after_sort",()->VettingServiceProbe.map("candidates",candidateOrder(candidates,originalOrdinals),"ordering","Original stable severityRank comparator"));
            List<String> warnings=new ArrayList<>(semanticResult.getWarnings()); Map<String,Candidate> unique=new LinkedHashMap<>(); int risk=0,skipped=0;
            int sortedOrdinal=0;
            for(Candidate c:candidates) {
                c.setPromptSupplementSha256(VettingPromptPolicy.supplementSha256(semantic.currentSupplement()));
                final int ordinal=sortedOrdinal++;final String sourceBefore=c.getSource();
                c.setSource("rule".equalsIgnoreCase(c.getSource())?"rule":"model"); String fingerprint=fingerprint(c);
                VettingFinding projection=new VettingFinding();apply(projection,c);
                String publicationKey=fingerprint+"\n"+generatedRevision(projection);
                final int riskBefore=risk;
                if(unique.containsKey(publicationKey)) {
                    probe.observe("candidate_filter_decision",()->VettingServiceProbe.map("candidate",c,"originalOrdinals",originalOrdinals.get(c),"sortedOrdinal",ordinal,
                            "sourceBeforeNormalization",sourceBefore,"fingerprint",fingerprint,"publicationKey",publicationKey,"decision","dropped","reason","duplicate_fingerprint_and_generated_revision",
                            "retainedCandidate",unique.get(publicationKey),"riskCounterBefore",riskBefore,"riskCounterAfter",riskBefore));
                    continue;
                }
                if("risk".equals(group(c.getType())))risk++;
                unique.put(publicationKey,c);final int riskAfter=risk;
                probe.observe("candidate_filter_decision",()->VettingServiceProbe.map("candidate",c,"originalOrdinals",originalOrdinals.get(c),"sortedOrdinal",ordinal,
                        "sourceBeforeNormalization",sourceBefore,"fingerprint",fingerprint,"publicationKey",publicationKey,"decision","retained","reason","first_unique",
                        "riskCounterBefore",riskBefore,"riskCounterAfter",riskAfter));
            }
            probe.elapsed(stage,stageStarted,stageOverhead,"Stable severity ordering, source normalization and exact fingerprint/generated revision dedup; no risk count cap; observer wall excluded",true);
            final int skippedFinal=skipped,riskFinal=risk;
            probe.observe("final_selected_candidates",()->VettingServiceProbe.map("candidatesByPublicationKey",unique,"riskCounter",riskFinal,"riskSkippedCount",skippedFinal,"warnings",warnings));
            stage="coverage_construction";stageStarted=System.nanoTime();stageOverhead=probe.overheadNanos();
            CoverageVO coverage=coverage(snapshot,chunks,semanticResult.getReviewedChunkIds(),warnings,true);
            if(canonicalSession!=null)coverage.setCanonicalSourceDocumentIds(admitted.stream().map(SourceDocument::getId).collect(Collectors.toList()));
            coverage.setSourceStampSha256(sourceStampHash(currentSourceStamps(projectId,snapshot)));
            if(canonicalSession!=null)coverage.setCanonicalCorpusIdentitySha256(canonicalSession.identitySha256());
            coverage.setSemanticTopics(semanticResult.getTopicAudits());
            coverage.setPackageIdentityReview(completedIdentity);
            coverage.setPackageIdentityMirrorAudit(capturedCoverage.getPackageIdentityMirrorAudit());
            coverage.setChecklistDependencyLedger(VettingChecklistDependencies.capture(projectId,canonicalSession,snapshot,
                    sourceDocumentRepository.findScopeSourcesByProjectId(projectId),unique.values(),semanticResult.getTopicAudits()));
            probe.elapsed(stage,stageStarted,stageOverhead,"Original coverage construction and semantic-topic assignment",true);
            probe.observe("coverage_constructed",()->VettingServiceProbe.map("coverage",coverage,"boundary","Submission coverage, not professional review acceptance"));
            sourceGuard.run();
            if(!JsonUtils.isBlankText(semanticResult.getModelFailure())) {
                tx().execute(status->{VettingRun run=ownedRun(id);run.setCoverageJson(JsonUtils.write(coverage));runRepository.saveAndFlush(run);return null;});
                throw new BizException(4101,semanticResult.getModelFailure());
            }
            progress(id,semanticResult.getPlannedCallCount()+1,semanticResult.getPlannedCallCount()+2,"saving","保存证据快照和发现，保留人工状态。");
            RunResultVO result;stage="merge_and_complete_transaction";
            synchronized(lock(projectId)) {
                stageStarted=System.nanoTime();stageOverhead=probe.overheadNanos();
                result=tx().execute(status -> {
                    if(canonicalSession!=null)oer.assertSelected(canonicalSession,freshSourceStamps(projectId));
                    if(canonicalSession!=null)oer.assertCorpusIntegrity(canonicalSession);
                    sourceGuard.run();
                    if(compact)assertMaterialSourcesCurrent(projectId,snapshot,true);
                    return mergeAndComplete(id,projectId,unique,coverage,semanticResult.getModel(),probe);
                });
                probe.elapsed(stage,stageStarted,stageOverhead,"Actual original REQUIRED tx.execute; includes own commit when no outer participant, otherwise outer commit is unobserved; lock acquisition excluded; observer wall excluded",true);
            }
            probe.observe("merge_and_complete_transaction_returned",()->VettingServiceProbe.returnedTransaction("result",result,
                    "finalRecords",probe.reference("merge_pending_commit_records"),"boundary","Original REQUIRED TransactionTemplate returned; see commit observation. Referenced records are after original saves/flush, before transaction completion; no extra DAO read."));
            done.complete(result);outcome="completed";
        } catch(Exception e) {
            originalFailure=e;probe.elapsed(stage,stageStarted,stageOverhead,"Original operation failed in this stage; observer wall excluded",false);
            probe.originalFailure("service_run_failure",e);log.error("审查任务 {} 失败",id,e);failRun(id,brief(e),done,probe);
        } catch(Error e) {
            originalFailure=e;throw e;
        } finally {
            try {
                // The outer worker handles fatal errors before allocating observer payloads.
                if(!(originalFailure instanceof Error)) {
                    probe.elapsed("execute_run_total",runStarted,runOverhead,"Original background execution inclusive of original stages; observer wall excluded",originalFailure==null&&"completed".equals(outcome));
                    probe.finish(outcome,originalFailure);
                }
            } finally {completions.remove(id,done);serviceProbes.remove(id,probe);}
        }
    }
    private List<Map<String,Object>> candidateOrder(List<Candidate> candidates,IdentityHashMap<Candidate,List<Integer>> originalOrdinals){
        List<Map<String,Object>> out=new ArrayList<>();for(int i=0;i<candidates.size();i++){Candidate c=candidates.get(i);out.add(VettingServiceProbe.map("order",i,"originalOrdinals",originalOrdinals.get(c),"candidate",c));}return out;
    }
    private List<Map<String,Object>> sourceMetadata(List<SourceDocument> documents){
        List<Map<String,Object>> out=new ArrayList<>();
        for(SourceDocument d:documents)out.add(VettingServiceProbe.map("documentId",String.valueOf(d.getId()),"projectId",d.getProjectId(),"category",d.getCategory(),
                "fileKey",fileKey(d),"fileName",d.getFileName(),"reviewRole",VettingCorpus.sourceRole(d),"parseStatus",d.getParseStatus(),"pageCount",d.getPageCount(),
                "ocrUsed",d.getOcrUsed(),"textCharsUtf16",nvl(d.getTextContent()).length(),"structuredJsonCharsUtf16",nvl(d.getStructuredContentJson()).length(),
                "boundary","Identity metadata only; full rule-source text/blocks are recorded separately. Corpus/source snapshots have their independent probe."));
        return out;
    }
    private RunResultVO mergeAndComplete(String id,String projectId,Map<String,Candidate> candidates,CoverageVO coverage,String model,VettingServiceProbe probe) {
        projectService.require(projectId); VettingRun run=ownedRun(id);
        if(!ACTIVE_STATES.contains(run.getStatus())) throw new BizException(4101,"任务已停止，不能覆盖结果");
        if(!isLatestRun(projectId,id))throw new BizException(4101,"项目已有新的审查任务，旧执行者不能发布当前结果");
        if(sourceSetStale(run,false,false))throw new BizException(4104,"审查来源已变化，旧结果不能作为当前报告保存；可靠包仍保留。");
        assertPackageIdentityCurrent(projectId,coverage.getPackageIdentityReview(),run.getSourceSnapshotSha256());
        List<VettingFinding> previous=findingRepository.findByProjectIdOrderByCodeAsc(projectId);
        Map<String,Map<String,VettingFinding>> byFingerprintAndRevision=new LinkedHashMap<>(); int next=1;
        probe.observe("merge_previous_records",()->VettingServiceProbe.map("records",previous,"boundary","Original lookup before inactive marking/generated-field refresh; includes original human notes/decision/time"));
        for(VettingFinding f:previous) {
            if(!JsonUtils.isBlankText(f.getFingerprint())) {
                Map<String,VettingFinding> revisions=byFingerprintAndRevision.computeIfAbsent(f.getFingerprint(),key->new LinkedHashMap<>());
                String revision=generatedRevision(f);VettingFinding existing=revisions.get(revision);
                if(existing==null||preferExistingFinding(f,existing)) revisions.put(revision,f);
            }
            Matcher matcher=Pattern.compile("VT-(\\d+)").matcher(nvl(f.getCode())); if(matcher.matches()) next=Math.max(next,Integer.parseInt(matcher.group(1))+1);
        }
        previous.forEach(f->f.setActive(false));
        probe.observe("merge_previous_records_after_inactive_marking",()->VettingServiceProbe.map("records",previous,"committed",false));
        List<VettingFinding> current=new ArrayList<>();
        for(Candidate candidate:candidates.values()) {
            String fingerprint=fingerprint(candidate);
            VettingFinding projection=new VettingFinding();apply(projection,candidate);String revision=generatedRevision(projection);
            VettingFinding f=byFingerprintAndRevision.getOrDefault(fingerprint,Collections.emptyMap()).get(revision);
            boolean reused=f!=null;
            if(f==null) { f=projection; f.setProjectId(projectId); f.setCode(String.format("VT-%03d",next++)); f.setStatus(VettingFinding.STATUS_OPEN); f.setCreatedAt(Instant.now()); }
            apply(f,candidate); f.setFingerprint(fingerprint); f.setRunId(id); f.setActive(true); current.add(f);
            String code=f.getCode();probe.observe("finding_generated_revision_match",()->VettingServiceProbe.map("fingerprint",fingerprint,
                    "generatedRevision",revision,"protocol","exact-generated-fields-v1","reusedExistingRecord",reused,"code",code,
                    "boundary","Human disposition is reused only for identical generated fields and evidence metadata; this is not semantic verification"));
        }
        assertPackageIdentityCurrent(projectId,coverage.getPackageIdentityReview(),run.getSourceSnapshotSha256());
        findingRepository.saveAll(previous); findingRepository.saveAll(current);
        RunResultVO result=new RunResultVO(current.size(),metricsOf(current),coverage.getWarnings(),model);
        result.setPromptPolicy(VettingPromptPolicy.metadata(semantic.currentSupplement()));
        result.setModelIdentity(JsonUtils.read(run.getModelIdentityJson(),com.consense.ai.ModelIdentity.class));
        run.setStatus("COMPLETED"); run.setPhase("completed"); run.setCompletedUnits(run.getTotalUnits()); run.setFinishedAt(Instant.now()); run.setMessage("审查完成，共 "+current.size()+" 条发现；覆盖与限制见报告。");
        run.setResultJson(JsonUtils.write(result)); run.setCoverageJson(JsonUtils.write(coverage));
        run.setRecoveryStatus("COMPLETED");run.setExecutionOwner(null);run.setLeaseUntil(null);runRepository.saveAndFlush(run);
        if(reportSnapshots!=null)saveReportSnapshot(projectService.require(projectId),run,current.stream().map(f->toVO(f,run.getSourceSnapshotSha256())).collect(Collectors.toList()));
        probe.observe("merge_pending_commit_records",()->VettingServiceProbe.map("committed",false,"run",run,"currentRecords",current,
                "findingVos",current.stream().map(this::toVO).collect(Collectors.toList()),"previousRecordsAfterMergeRefresh",previous,"result",result,
                "boundary","Actual entities after original saveAll(previous), saveAll(current), and run.saveAndFlush; enclosing transaction has not committed yet"));
        return result;
    }
    private void progress(String id,int units,String phase,String message) {
        progress(id,units,0,phase,message);
    }
    private void progress(String id,int units,int totalUnits,String phase,String message) {
        VettingServiceProbe probe=serviceProbes.get(id);long started=System.nanoTime(),overhead=probe==null?0:probe.overheadNanos();
        try {
            VettingRun changed=tx().execute(status -> { VettingRun run=ownedRun(id);
                if(!ACTIVE_STATES.contains(run.getStatus())) throw new BizException(4101,"任务已停止");
                if(totalUnits>0)run.setTotalUnits(totalUnits);
                run.setStatus("RUNNING"); run.setCompletedUnits(units); run.setPhase(phase); run.setMessage(truncate(message,2048)); runRepository.saveAndFlush(run); return run; });
            if(probe!=null){probe.elapsed("progress_transaction",started,overhead,"Original REQUIRED progress tx.execute; own commit or still-active outer participation as recorded",true);
                probe.observe("progress_transaction_returned",()->VettingServiceProbe.returnedTransaction("status",changed.getStatus(),"phase",changed.getPhase(),
                        "completedUnits",changed.getCompletedUnits(),"totalUnits",changed.getTotalUnits(),"message",changed.getMessage()));}
        } catch(RuntimeException|Error e){if(probe!=null){probe.elapsed("progress_transaction",started,overhead,"Original progress transaction failed",false);probe.originalFailure("progress_transaction_failure",e);}throw e;}
    }
    private void failRun(String id,String message,CompletableFuture<RunResultVO> done,VettingServiceProbe probe) {
        failRun(id,message,done,probe,null);
    }
    private void failRun(String id,String message,CompletableFuture<RunResultVO> done,VettingServiceProbe probe,Throwable originalFailure) {
        long started=System.nanoTime(),overhead=probe.overheadNanos();
        try {
            tx().execute(status -> { runRepository.lockById(id).ifPresent(run -> { if("COMPLETED".equals(run.getStatus())||checkpoints!=null&&(!executionOwner.equals(run.getExecutionOwner())||run.getLeaseUntil()==null||!run.getLeaseUntil().isAfter(Instant.now()))) return; run.setStatus("FAILED"); run.setPhase("failed");run.setRecoveryStatus("NEEDS_RERUN");run.setExecutionOwner(null);run.setLeaseUntil(null); run.setError(truncate(message,2048)); run.setMessage(truncate(message,2048)); run.setFinishedAt(Instant.now()); runRepository.saveAndFlush(run);
                probe.observe("failed_run_pending_commit",()->VettingServiceProbe.map("run",run,"committed",false)); }); return null; });
            probe.elapsed("fail_run_transaction",started,overhead,"Original REQUIRED failure-persistence tx.execute; own commit or still-active outer participation as recorded",true);
            probe.observe("fail_run_transaction_returned",()->VettingServiceProbe.returnedTransaction("records",probe.reference("failed_run_pending_commit"),
                    "boundary","Original failure policy skips an already-completed run; missing records are not fabricated"));
        } catch(RuntimeException|Error e){probe.elapsed("fail_run_transaction",started,overhead,"Original failure persistence transaction failed",false);probe.originalFailure("fail_run_transaction_failure",e);throw e;}
        finally {try {if(!done.isDone())done.completeExceptionally(originalFailure==null?new BizException(4101,message):originalFailure);}
            finally {completions.remove(id,done);}}
    }
    private void failFatalRun(String id,Error failure,CompletableFuture<RunResultVO> done,VettingServiceProbe probe) {
        // A dead worker must stop renewing its lease even if failure persistence also fails.
        completions.remove(id,done);serviceProbes.remove(id,probe);
        try {done.completeExceptionally(failure);}catch(RuntimeException|Error secondary){retainFatalFailure(failure,secondary);}
        try {failRun(id,"审查发生不可恢复的系统错误；已有来源和人工记录保留，请检查后重新运行。",done,probe,failure);}
        catch(RuntimeException|Error secondary){retainFatalFailure(failure,secondary);}
        try {probe.originalFailure("service_run_failure",failure);probe.finish("fatal_error",failure);}
        catch(RuntimeException|Error secondary){retainFatalFailure(failure,secondary);}
    }
    private static void retainFatalFailure(Error original,Throwable secondary) {
        if(original!=secondary)try {original.addSuppressed(secondary);}catch(RuntimeException|Error unavailable){/* Preserve the original fatal error under memory pressure. */}
    }
    private Map<String,Map<String,JsonNode>> ruleNativePositions(List<VettingRuleEngine.Source> sources) {
        Map<String,Map<String,JsonNode>> result=new HashMap<>();
        for(VettingRuleEngine.Source source:sources){
            Map<String,JsonNode> positions=new HashMap<>();Set<String> seen=new HashSet<>();
            for(DocumentBlock block:source.getBlocks())if(!JsonUtils.isBlankText(block.getId())){
                // A repeated block ID cannot designate one original occurrence.
                if(!seen.add(block.getId()))positions.remove(block.getId());
                else if(block.getSourcePosition()!=null)positions.put(block.getId(),block.getSourcePosition());
            }
            result.put(source.getId(),positions);
        }
        return result;
    }
    private Candidate ruleCandidate(VettingRuleEngine.Output output,Map<String,Map<String,JsonNode>> nativePositions) {
        Candidate c=new Candidate(); c.setRuleId(output.getRuleId()); c.setType(output.getType()); c.setTitle(output.getTitle()); c.setComment(output.getSummary()); c.setSuggestion(output.getRecommendation()); c.setSeverity(output.getRisk()); c.setSource("rule");
        c.setBusinessDetails(new BusinessDetailsVO(truncate(output.getTitle(),160),truncate(output.getSummary(),1200),
                Collections.singletonList("Confirm the project fact baseline and applicability of the quoted clauses before adopting this rule observation."),"review"));
        List<FindingEvidence> sides=new ArrayList<>(); int index=0;
        for(VettingRuleEngine.Evidence e:output.getEvidence()) {
            FindingEvidence side=new FindingEvidence(index++==0?"source":"reference",e.getDocumentId(),e.getFileKey(),e.getFileName(),e.getPageNo()==null?null:"P"+e.getPageNo(),available(e.getAnchor(),e.getLocation()),e.getQuote(),true,e.getSourceHash(),e.getBbox()==null?null:Arrays.stream(e.getBbox()).boxed().collect(Collectors.toList()));
            JsonNode position=nativePositions.getOrDefault(e.getDocumentId(),Collections.emptyMap()).get(e.getBlockId());
            if(position!=null)side.setNativePositions(Collections.singletonList(position.deepCopy()));
            sides.add(side);
        }
        c.setEvidence(sides); return c;
    }
    /** Rule quotes inherit raw identity only from the verified inputs captured for this run. */
    static void bindRuleOriginalSources(List<Candidate> candidates,List<SourceDocument> snapshot) {
        Map<String,SourceDocument> sources=snapshot.stream().collect(Collectors.toMap(d->String.valueOf(d.getId()),d->d));
        for(Candidate candidate:candidates)for(FindingEvidence quote:candidate.getEvidence()) {
            SourceDocument source=sources.get(quote.getDocumentId());
            if(source==null||!Objects.equals(VettingCorpus.sourceHash(source),quote.getSourceHash())
                    ||!JsonUtils.isBlankText(quote.getOriginalSourceSha256())&&!Objects.equals(source.getSourceSha256(),quote.getOriginalSourceSha256()))
                throw new IllegalStateException("Rule evidence differs from captured source identity");
            quote.setOriginalSourceSha256(JsonUtils.isBlankText(source.getSourceSha256())?null:source.getSourceSha256());
            quote.setSourceRole(VettingCorpus.sourceRole(source));
            if(quote.getNativePositions()!=null)quote.setNativePositions(quote.getNativePositions().stream()
                    .map(position->VettingCorpus.nativePosition(position,source.getSourceSha256())).filter(Objects::nonNull).collect(Collectors.toList()));
        }
    }
    /** Bind a content stamp only to evidence that still names the captured parsed and original source. */
    static void bindEvidenceContentRevisions(List<Candidate> candidates,List<SourceDocument> snapshot) {
        Map<String,SourceDocument> sources=snapshot.stream().collect(Collectors.toMap(d->String.valueOf(d.getId()),d->d));
        Map<String,String> parsedHashes=new HashMap<>();
        for(SourceDocument source:snapshot)if(SourceContentDigests.stamped(source)
                &&Objects.equals(SourceContentDigests.actual(source),source.getBusinessContentSha256()))
            parsedHashes.put(String.valueOf(source.getId()),VettingCorpus.sourceHash(source));
        for(Candidate candidate:candidates)for(FindingEvidence quote:candidate.getEvidence()==null?Collections.<FindingEvidence>emptyList():candidate.getEvidence()) {
            SourceDocument source=sources.get(quote.getDocumentId());
            if(source!=null&&parsedHashes.containsKey(quote.getDocumentId())
                    &&Objects.equals(quote.getSourceHash(),parsedHashes.get(quote.getDocumentId()))
                    &&SourceContentDigests.valid(source.getSourceSha256())
                    &&Objects.equals(quote.getOriginalSourceSha256(),source.getSourceSha256()))
                quote.setBusinessContentSha256(source.getBusinessContentSha256());
        }
    }
    private void apply(VettingFinding f,Candidate c) {
        // Refresh generated observations only. Human review records are owned by updateReview.
        List<FindingEvidence> sides=c.getEvidence()==null?Collections.emptyList():c.getEvidence(); FindingEvidence primary=sides.isEmpty()?null:sides.get(0);
        String group=group(c.getType()); f.setGroupKey(group); f.setTypes(typeCode(c.getType())); f.setSeverity(normalizeSeverity(c.getSeverity())); f.setScope(sides.stream().map(FindingEvidence::getDocumentId).filter(Objects::nonNull).distinct().count()>1?"inter":"intra");
        f.setTitleZhHans(c.getTitle()); f.setTitleZhHant(c.getTitle()); f.setTitleEn(c.getTitle()); f.setBodyZhHans(c.getComment()); f.setBodyZhHant(c.getComment()); f.setBodyEn(c.getComment());
        f.setImpactZhHans(c.getImpact()); f.setImpactZhHant(c.getImpact()); f.setImpactEn(c.getImpact()); f.setSuggestionZhHans(c.getSuggestion()); f.setSuggestionZhHant(c.getSuggestion()); f.setSuggestionEn(c.getSuggestion());
        f.setBusinessDetailsJson(c.getBusinessDetails()==null?null:JsonUtils.write(c.getBusinessDetails()));
        f.setSource(c.getSource()); f.setEvidenceJson(JsonUtils.write(sides)); long located=sides.stream().filter(FindingEvidence::isLocated).count();
        f.setVerification(sides.isEmpty()||located==0?"unverified":located==sides.size()?"verified":"partial"); f.setEvidenceId("verified".equals(f.getVerification())?"snapshot:"+nvl(primary.getDocumentId()):EVIDENCE_UNVERIFIED);
        f.setFileKey(primary==null?"OTHER":truncate(available(primary.getFileKey(),"OTHER"),16)); f.setPageNo(primary==null?null:truncate(primary.getPageNo(),16)); f.setPatternText(primary==null?null:truncate(primary.getQuote(),512));
        f.setRefs(truncate(sides.stream().map(e->JsonUtils.isBlankText(e.getClauseId())?e.getAnchor():e.getClauseId()).filter(Objects::nonNull).distinct().collect(Collectors.joining(" / ")),512));
        f.setLocation(truncate(sides.stream().map(e -> e.getFileName()+" · "+available(e.getPageNo(),nvl(e.getAnchor()))).collect(Collectors.joining("; ")),512)); f.setExpected(null); f.setBucketKey("reference".equals(group)?"missing":"language".equals(group)?"grammar":"particulars");
    }
    static String fingerprint(Candidate c) {
        List<String> sides=(c.getEvidence()==null?Collections.<FindingEvidence>emptyList():c.getEvidence()).stream().map(e -> nvl(e.getFileName()).toLowerCase(Locale.ROOT)+"|"+nvl(e.getSourceHash())+"|"+nvl(e.getAnchor())+"|"+VettingCorpus.normalize(e.getQuote())).sorted().collect(Collectors.toList());
        String legacy=nvl(c.getRuleId())+"\n"+String.join("\n",sides);
        // Historical candidates have neither contract field; every current run binds the supplement before fingerprinting.
        if(c.getBusinessDetails()!=null||!JsonUtils.isBlankText(c.getPromptSupplementSha256())) {
            String supplement=JsonUtils.isBlankText(c.getPromptSupplementSha256())?VettingPromptPolicy.supplementSha256(""):c.getPromptSupplementSha256();
            legacy=VettingPromptPolicy.sha256()+"\n"+VettingSemanticReview.policyContractSha256()+"\n"+supplement+"\n"+JsonUtils.write(c.getBusinessDetails())+"\n"+legacy;
        }
        if("rule".equalsIgnoreCase(c.getSource()))return VettingCorpus.hash(legacy);
        boolean anyPacket=!JsonUtils.isBlankText(c.getPacketId())||!JsonUtils.isBlankText(c.getSourceSnapshotSha256())
                ||(c.getEvidence()!=null&&c.getEvidence().stream().anyMatch(e->!JsonUtils.isBlankText(e.getPacketId())||!JsonUtils.isBlankText(e.getPacketSourceSnapshotSha256())));
        if(!anyPacket)return VettingCorpus.hash(legacy);
        if(JsonUtils.isBlankText(c.getPacketId())||!nvl(c.getSourceSnapshotSha256()).matches("[a-f0-9]{64}")||!c.getPacketId().endsWith(c.getSourceSnapshotSha256())
                ||c.getEvidence()==null||c.getEvidence().isEmpty()||c.getEvidence().stream().anyMatch(e->!e.isLocated()||!Objects.equals(c.getPacketId(),e.getPacketId())||!Objects.equals(c.getSourceSnapshotSha256(),e.getPacketSourceSnapshotSha256())))throw new IllegalArgumentException("Model candidate packet/source provenance is incomplete or inconsistent");
        return VettingCorpus.hash(legacy+"\npacket_scope_v1|"+c.getPacketId()+"|"+c.getSourceSnapshotSha256());
    }
    /** Exact persisted generated content, excluding every human-owned and run identity field. */
    static String generatedRevision(VettingFinding f) {
        return VettingCorpus.hash(JsonUtils.write(Arrays.asList("exact-generated-fields-v1",f.getGroupKey(),f.getTypes(),f.getSeverity(),f.getScope(),
                f.getTitleZhHans(),f.getTitleZhHant(),f.getTitleEn(),f.getBodyZhHans(),f.getBodyZhHant(),f.getBodyEn(),
                f.getImpactZhHans(),f.getImpactZhHant(),f.getImpactEn(),f.getSuggestionZhHans(),f.getSuggestionZhHant(),f.getSuggestionEn(),
                f.getSource(),f.getEvidenceJson(),f.getVerification(),f.getEvidenceId(),f.getFileKey(),f.getPageNo(),f.getPatternText(),
                f.getRefs(),f.getLocation(),f.getExpected(),f.getBucketKey(),f.getBusinessDetailsJson())));
    }
    private static boolean preferExistingFinding(VettingFinding candidate,VettingFinding existing) {
        boolean candidateActive=Boolean.TRUE.equals(candidate.getActive()),existingActive=Boolean.TRUE.equals(existing.getActive());
        if(candidateActive!=existingActive)return candidateActive;
        Instant candidateTime=candidate.getCreatedAt()==null?Instant.EPOCH:candidate.getCreatedAt();
        Instant existingTime=existing.getCreatedAt()==null?Instant.EPOCH:existing.getCreatedAt();
        int timeOrder=candidateTime.compareTo(existingTime);if(timeOrder!=0)return timeOrder>0;
        return candidate.getId()!=null&&(existing.getId()==null||candidate.getId()>existing.getId());
    }
    private CoverageVO coverage(List<SourceDocument> snapshot,List<Chunk> chunks,Set<String> reviewed,List<String> extra,boolean rulesScanned) {
        List<String> warnings=new ArrayList<>(extra); List<DocumentCoverageVO> documents=new ArrayList<>(); int processed=0,unreviewed=0;
        boolean canonical=OerBusinessEvidence.canonicalCorpus(chunks);
        Map<Long,FileDisplay> summaries=compactSources(snapshot)?runSourceSummaries(snapshot):Collections.emptyMap();
        warnings.add(canonical?"规则与语义仅处理已采用的 canonical 来源观察；未采用区域、整份原件完整性、编辑状态及采用关系保持声明的未知。字符分母仅为已采用观察的 UTF16 范围并集，不代表原件全文覆盖。":rulesScanned?"规则扫描全部可解析招标合同条款；标准文件仅作参考对照，项目事实和文件目录仅作上下文，均未当作实际合同执行规则检查。语义覆盖仅计实际成功的模型片段，未检索片段不代表完成语义审查。":"任务尚未完成规则和语义审查，当前覆盖仅反映源文件解析状态。");
        if(props.getVetting().isDiffAgainstBaseline()) warnings.add("本次未执行标准模板差异过滤，不能解读为只审查项目改动内容。");
        for(SourceDocument d:snapshot) {
            FileDisplay summary=summaries.get(d.getId());
            List<String> notes=summary==null?parseWarnings(d):parseWarnings(summary.coverageHeader,summary.hasCoverage,d); if(excluded(d)) notes.add("source_identity_unresolved".equals(com.consense.service.sources.ProjectDocumentScope.exclusionReason(d,"fullset"))?"身份待确认，原文已保留；确认文件用途后可加入源集。":"该文件被来源身份或显式范围规则排除。");
            List<Chunk> parts=chunks.stream().filter(c -> String.valueOf(d.getId()).equals(c.getDocumentId())).collect(Collectors.toList()); List<Chunk> checked=parts.stream().filter(c -> reviewed.contains(c.getId())).collect(Collectors.toList());
            boolean usable=canonical?!parts.isEmpty():summary==null?reviewable(d):summary.textChars>0&&Arrays.asList("PARSED","PARTIAL").contains(d.getParseStatus())&&!excluded(d);
            if(!usable){unreviewed++;if(!"FAILED".equals(d.getParseStatus()))notes.add("No usable source evidence was available; this document was not reviewed.");}
            int chars=canonical?OerBusinessEvidence.observedChars(parts):summary==null?nvl(d.getTextContent()).length():summary.textChars,covered=canonical?OerBusinessEvidence.observedChars(checked):summary==null?Math.min(chars,reviewedChars(d,checked)):0;
            notes.add("资料类型：" + roleLabel(VettingCorpus.sourceRole(d)).getZhHans());
            if(canonical)notes.add("片段与字符统计以已采用 canonical 观察为分母；未采用区域及原件完整性未确认。");
            else if(usable) notes.add(rulesScanned?(contractSource(d)?"规则已扫描全部可解析招标合同正文。":
                    "standard".equals(VettingCorpus.sourceRole(d))?"标准文件仅作参考对照，规则未当作实际合同执行。":"项目事实或文件目录不作为有效合同条款执行规则扫描。" ):"规则和语义审查尚未完成。");
            if(usable && rulesScanned && checked.size()<parts.size()) notes.add("语义仅覆盖 "+checked.size()+"/"+parts.size()+" 片段，其他片段未进入成功的模型调用。");
            if(usable && !checked.isEmpty()) processed++;
            documents.add(new DocumentCoverageVO(String.valueOf(d.getId()),fileKey(d),d.getFileName(),d.getParseStatus(),chars,covered,parts.size(),checked.size(),notes));
        } if(unreviewed>0)warnings.add(unreviewed+" selected source file(s) had no usable evidence and were not reviewed.");
        CoverageVO result=new CoverageVO(snapshot.size(),processed,documents,new ArrayList<>(new LinkedHashSet<>(warnings)));
        if(OerBusinessEvidence.isSemanticCorpus(chunks)){JsonNode read=OerBusinessEvidence.session(chunks).semanticReadCoverage();result.setSemanticSourceRead(read);
            if(!read.path("blockedOrigins").isEmpty()){result.setReviewCompleteness("incomplete");result.getWarnings().add("部分来源单元超过有界读取预算，已保留原件与精确未完成范围；可读内容继续审查，不代表声明范围完整完成。"+JsonUtils.write(read));
                for(DocumentCoverageVO document:documents){int denied=0;for(JsonNode row:read.path("blockedOrigins"))if(document.getDocumentId().equals(row.path("documentId").asText()))denied++;if(denied>0){document.setTotalSegments(document.getTotalSegments()+denied);document.getWarnings().add("另有 "+denied+" 个来源单元未读取，字符分母仅覆盖可读部分，缺失范围见 semanticSourceRead。");}}}}
        return result;
    }

    /** Count the union of source block ranges; overlapping model chunks do not inflate coverage. */
    static int reviewedChars(SourceDocument document,List<Chunk> checked) {
        List<DocumentBlock> blocks=VettingCorpus.blocks(document); Map<String,List<int[]>> ranges=new LinkedHashMap<>();
        Map<String, Integer> blockIndices = new HashMap<>(); Set<String> ambiguousIds = new HashSet<>();
        for (int index = 0; index < blocks.size(); index++) {
            String id = blocks.get(index).getId();
            if (id == null || id.isEmpty()) continue;
            if (blockIndices.putIfAbsent(id, index) != null) ambiguousIds.add(id);
        }
        for(Chunk c:checked) for(VettingCorpus.Part p:c.getParts()==null?Collections.<VettingCorpus.Part>emptyList():c.getParts()) {
            if (!JsonUtils.isBlankText(p.getBlockId())) {
                Integer index = blockIndices.get(p.getBlockId());
                if (index == null || ambiguousIds.contains(p.getBlockId())) continue;
                String text = nvl(blocks.get(index).getText()); int start = p.getStartOffset(), end = p.getEndOffset();
                if (start < 0 || end < start || end > text.length() || !text.substring(start, end).equals(p.getText())) continue;
                ranges.computeIfAbsent(String.valueOf(index), k -> new ArrayList<>()).add(new int[]{start, end});
                continue;
            }
            for(int index=0;index<blocks.size();index++) {
                DocumentBlock b=blocks.get(index); String text=nvl(b.getText()),location=nvl(b.getLocation()),anchor=nvl(p.getAnchor()); int start=0;
                if(text.isEmpty()||location.isEmpty()) continue;
                if(anchor.endsWith(location)) { if(!text.equals(p.getText())) continue; }
                else { Matcher offset=Pattern.compile(Pattern.quote(location)+" @(\\d+)$").matcher(anchor); if(!offset.find()) continue; start=Integer.parseInt(offset.group(1));
                    if(start>text.length()||!text.startsWith(nvl(p.getText()),start)) continue; }
                ranges.computeIfAbsent(String.valueOf(index),k -> new ArrayList<>()).add(new int[]{start,Math.min(text.length(),start+nvl(p.getText()).length())}); break;
            }
        }
        int total=0; for(List<int[]> intervals:ranges.values()) {
            intervals.sort(Comparator.comparingInt(a -> a[0])); int start=-1,end=-1;
            for(int[] a:intervals) { if(start<0) {start=a[0];end=a[1];} else if(a[0]<=end) end=Math.max(end,a[1]); else {total+=end-start;start=a[0];end=a[1];} }
            if(start>=0) total+=end-start;
        } return total;
    }
    private List<SourceDocument> sourceSet(String projectId) {
        List<SourceDocument> documents=sourceDocumentRepository.findSelectedBusinessSourcesByProjectId(projectId,"fullset","vetting");
        for(SourceDocument d:documents)if(JsonUtils.isBlankText(d.getFileKey()))d.setFileKey(matchFileKey(d.getFileName()));
        return com.consense.service.sources.ProjectDocumentScope.selected(projectId,documents,"fullset");
    }
    private static final class FileDisplay {
        final SourceDocument source;
        final int textChars,structureChars;
        boolean hasText;
        final boolean hasTextValue,hasCoverage;
        String coverageHeader;
        final String routingSha;
        FileDisplay(SourceDocument source,SourceDocumentRepository.FileDisplayMetadata metadata) {
            this.source=source;this.textChars=metadata.getTextChars()==null?0:metadata.getTextChars();
            this.structureChars=metadata.getStructureChars()==null?0:metadata.getStructureChars();
            this.hasTextValue=Boolean.TRUE.equals(metadata.getHasTextValue());
            this.hasCoverage=Boolean.TRUE.equals(metadata.getHasCoverage());
            this.routingSha=CanonicalEvidenceService.routingHash(source);
        }
        boolean safeForLegacyHash(){return (long)textChars+structureChars<=1_000_000;}
    }
    private Map<Long,FileDisplay> runSourceSummaries(List<SourceDocument> captured){
        String project=captured.get(0).getProjectId();Map<Long,SourceDocument> selected=new LinkedHashMap<>();for(SourceDocument source:captured)selected.put(source.getId(),source);
        Map<Long,FileDisplay> result=new LinkedHashMap<>();
        for(SourceDocumentRepository.FileDisplayMetadata metadata:sourceDocumentRepository.findFileDisplayMetadataByProjectId(project)){
            SourceDocument source=selected.get(metadata.getId());if(source==null)continue;
            SourceDocument current=SourceDocumentRepository.scopeSnapshot(metadata);if(JsonUtils.isBlankText(current.getFileKey()))current.setFileKey(matchFileKey(current.getFileName()));
            if(!ProjectDocumentScope.sameScopeRevision(source,current)||!Objects.equals(source.getBusinessContentSha256(),current.getBusinessContentSha256()))throw new BizException(4104,"Review coverage source revision changed");
            source.setParseMessage(metadata.getParseMessage());source.setPageCount(metadata.getPageCount());source.setOcrUsed(metadata.getOcrUsed());
            result.put(source.getId(),new FileDisplay(source,metadata));
        }
        if(result.size()!=captured.size())throw new BizException(4104,"Review coverage source selection changed");readCoverageHeaders(project,result);return result;
    }
    /** Display retains pending and excluded originals; business selection remains separate. */
    private List<FileDisplay> fileDisplaySourceSet(String projectId) {
        Map<Long,FileDisplay> byId=new LinkedHashMap<>();
        for(SourceDocumentRepository.FileDisplayMetadata metadata:sourceDocumentRepository.findFileDisplayMetadataByProjectId(projectId)) {
            SourceDocument d=SourceDocumentRepository.scopeSnapshot(metadata);d.setParseMessage(metadata.getParseMessage());
            d.setPageCount(metadata.getPageCount());d.setOcrUsed(metadata.getOcrUsed());
            FileDisplay display=new FileDisplay(d,metadata);
            if(JsonUtils.isBlankText(d.getFileKey()))d.setFileKey(matchFileKey(d.getFileName()));
            byId.put(d.getId(),display);
        }
        readTextPresence(projectId,byId);
        readCoverageHeaders(projectId,byId);
        List<SourceDocument> documents=byId.values().stream().map(display->display.source).collect(Collectors.toList());
        return ProjectDocumentScope.currentCatalog(projectId,documents).stream().map(d->byId.get(d.getId())).collect(Collectors.toList());
    }
    private void readTextPresence(String projectId,Map<Long,FileDisplay> byId) {
        if(byId.values().stream().noneMatch(d->d.textChars>0))return;
        if(fileListDataSource==null)throw new IllegalStateException("Source file text reader unavailable");
        try(Connection connection=fileListDataSource.getConnection()) {
            String product=connection.getMetaData().getDatabaseProductName();
            if("H2".equals(product)) {
                try(PreparedStatement query=connection.prepareStatement("select id,text_content from source_document where project_id=? and category in ('VETTING_PACKAGE','VETTING_SUPPLEMENT')")) {
                    query.setString(1,projectId);
                    try(ResultSet rows=query.executeQuery()) {
                        while(rows.next()) {
                            FileDisplay display=byId.get(rows.getLong(1));if(display==null||display.textChars<=0)continue;
                            Clob clob=rows.getClob(2);if(clob==null)continue;
                            try(Reader reader=clob.getCharacterStream()) {
                                char[] chars=new char[4096];int count;
                                while(!display.hasText&&(count=reader.read(chars))!=-1)
                                    for(int i=0;i<count;i++)if(!Character.isWhitespace(chars[i])&&!Character.isSpaceChar(chars[i])) {display.hasText=true;break;}
                            } finally {clob.free();}
                        }
                    }
                }
            } else if("MySQL".equals(product)||"MariaDB".equals(product)) {
                // Connector/J materializes getClob(); return bounded server-side slices instead.
                try(PreparedStatement query=connection.prepareStatement("select substring(text_content,?,16384) from source_document where id=? and project_id=? and category in ('VETTING_PACKAGE','VETTING_SUPPLEMENT')")) {
                    for(FileDisplay display:byId.values()) {
                        if(display.textChars<=0)continue;
                        long position=1;
                        while(!display.hasText) {
                            query.setLong(1,position);query.setLong(2,display.source.getId());query.setString(3,projectId);
                            try(ResultSet rows=query.executeQuery()) {
                                if(!rows.next())break;
                                String chunk=rows.getString(1);if(chunk==null||chunk.isEmpty())break;
                                for(int i=0;i<chunk.length();i++)if(!Character.isWhitespace(chunk.charAt(i))&&!Character.isSpaceChar(chunk.charAt(i))) {display.hasText=true;break;}
                                position+=chunk.codePointCount(0,chunk.length());
                            }
                        }
                    }
                }
            } else throw new SQLException("Unsupported source file text reader database: "+product);
        } catch(IOException | SQLException error) {throw new IllegalStateException("Source file text availability unavailable",error);}
    }
    private void readCoverageHeaders(String projectId,Map<Long,FileDisplay> byId) {
        if(fileListDataSource==null||byId.values().stream().noneMatch(d->d.hasCoverage))return;
        try(Connection connection=fileListDataSource.getConnection()) {
            String product=connection.getMetaData().getDatabaseProductName();
            boolean h2="H2".equals(product);
            if(!h2&&!"MySQL".equals(product)&&!"MariaDB".equals(product))throw new SQLException("Unsupported source file coverage reader database: "+product);
            String sql="select id,"+(h2?"parse_coverage_json":"substring(parse_coverage_json,1,16384)")+",parse_coverage_sha256 from source_document where project_id=? and category in ('VETTING_PACKAGE','VETTING_SUPPLEMENT')";
            try(PreparedStatement query=connection.prepareStatement(sql)) {
                query.setString(1,projectId);
                try(ResultSet rows=query.executeQuery()) {
                    while(rows.next()) {
                        FileDisplay display=byId.get(rows.getLong(1));
                        if(display==null||!display.hasCoverage||!Objects.equals(display.source.getParseCoverageSha256(),rows.getString(3)))continue;
                        if(!h2) {display.coverageHeader=rows.getString(2);continue;}
                        Clob clob=rows.getClob(2);if(clob==null)continue;
                        try(Reader reader=clob.getCharacterStream()) {
                            char[] chars=new char[4096];StringBuilder header=new StringBuilder(16384);
                            while(header.length()<16384) {
                                int count=reader.read(chars,0,Math.min(chars.length,16384-header.length()));
                                if(count<0)break;if(count>0)header.append(chars,0,count);
                            }
                            display.coverageHeader=header.toString();
                        } catch(IOException | SQLException error) {log.warn("Source file coverage summary unavailable for document {}",display.source.getId(),error);}
                        finally {clob.free();}
                    }
                }
            }
        } catch(SQLException error) {log.warn("Source file coverage summaries unavailable for project {}",projectId,error);}
    }
    private boolean reviewable(SourceDocument d) { return ("PARSED".equals(d.getParseStatus())||"PARTIAL".equals(d.getParseStatus())) && !JsonUtils.isBlankText(d.getTextContent()) && !excluded(d); }
    private static boolean missingObservedEvidence(com.consense.service.sources.CanonicalEvidenceException failure) {
        return "NORMAL_OBSERVED_SOURCE_EVIDENCE_MISSING".equals(failure.getMessage());
    }
    private boolean contractSource(SourceDocument d) { return "tender".equals(VettingCorpus.sourceRole(d)); }
    private static String validatedRole(String role) {
        String value = nvl(role).trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || "auto".equals(value)) return null;
        if (Arrays.asList("tender", "standard", "project_fact", "package_manifest").contains(value)) return value;
        throw new BizException(4101, "资料类型必须为 auto、tender、standard、project_fact 或 package_manifest");
    }
    private static LocalizedText roleLabel(String role) {
        if ("standard".equals(role)) return LocalizedText.of("标准参考文件", "標準參考文件", "Standard reference");
        if ("project_fact".equals(role)) return LocalizedText.of("项目事实资料", "項目事實資料", "Project facts");
        if ("package_manifest".equals(role)) return LocalizedText.of("文件目录及范围清单", "文件目錄及範圍清單", "Package manifest");
        if ("tender".equals(role)) return LocalizedText.of("招标合同条款", "招標合同條款", "Tender provisions");
        return LocalizedText.of("身份待确认", "身份待確認", "Identity pending confirmation");
    }
    private boolean excluded(SourceDocument document) { return com.consense.service.sources.ProjectDocumentScope.exclusionReason(document,"fullset")!=null; }
    private List<String> parseWarnings(SourceDocument d) {
        List<String> out=new ArrayList<>();
        if("FAILED".equals(d.getParseStatus()))out.add("Parsing failed; this document was not reviewed.");
        else if(!JsonUtils.isBlankText(d.getParseCoverageJson())) try { ParseCoverage c=JsonUtils.read(d.getParseCoverageJson(),ParseCoverage.class); out.addAll(c.getLimitations()); if(!c.getFailedPages().isEmpty()) out.add("未完成解析的物理页："+c.getFailedPages()); if(!c.isComplete()) out.add("文档解析不完整，未解析部分不纳入审查。"); }
        catch(Exception e) { out.add("解析覆盖记录无法读取，请重新解析。"); }
        else out.add("旧文件缺结构和覆盖记录，仅使用字符锚点，不推测Word页码。");
        if(Boolean.TRUE.equals(d.getOcrUsed())) out.add("OCR 不识别删除线、表格行列关系及图形含义；引文、删除状态和表格对应关系须对照原始物理页复核。");
        if(!"PARSED".equals(d.getParseStatus())&&!"PARTIAL".equals(d.getParseStatus())) out.add("没有可用的已解析正文："+nvl(d.getParseMessage())); return out;
    }
    private List<String> parseWarnings(String header,boolean hasCoverage,SourceDocument d) {
        List<String> out=new ArrayList<>();
        if(!hasCoverage)out.add("旧文件缺结构和覆盖记录，仅使用字符锚点，不推测Word页码。");
        else if(header==null)out.add("解析覆盖摘要暂不可用，请对照已保存的解析状态。");
        else {
            boolean complete=false,sawComplete=false,sawLimitations=false,sawFailedPages=false,truncated=false;
            List<Integer> failed=new ArrayList<>();int failedCount=0;
            try(JsonParser parser=JsonUtils.mapper().getFactory().createParser(header)) {
                if(parser.nextToken()!=JsonToken.START_OBJECT)throw new IOException("Coverage is not an object");
                while(parser.nextToken()!=JsonToken.END_OBJECT) {
                    if(parser.currentToken()!=JsonToken.FIELD_NAME)throw new IOException("Invalid coverage field");
                    String field=parser.getCurrentName();JsonToken value=parser.nextToken();
                    if("complete".equals(field)) {complete=parser.getBooleanValue();sawComplete=true;}
                    else if("limitations".equals(field)&&value==JsonToken.START_ARRAY) {
                        while(parser.nextToken()!=JsonToken.END_ARRAY)out.add(parser.getValueAsString());
                        sawLimitations=true;
                    } else if("failedPages".equals(field)&&value==JsonToken.START_ARRAY) {
                        while(parser.nextToken()!=JsonToken.END_ARRAY) {if(failed.size()<20)failed.add(parser.getIntValue());failedCount++;}
                        sawFailedPages=true;
                    } else if(sawComplete&&sawLimitations&&sawFailedPages)break;
                    else parser.skipChildren();
                }
            } catch(Exception e) {truncated=true;}
            if(failedCount>0)out.add("未完成解析的物理页："+failed+(failedCount>failed.size()?"（另有 "+(failedCount-failed.size())+" 页）":""));
            if(sawComplete&&!complete)out.add("文档解析不完整，未解析部分不纳入审查。");
            if(truncated||!sawComplete)out.add("解析覆盖摘要不完整，请对照原件和已保存的解析状态。");
        }
        if(Boolean.TRUE.equals(d.getOcrUsed()))out.add("OCR 不识别删除线、表格行列关系及图形含义；引文、删除状态和表格对应关系须对照原始物理页复核。");
        if(!"PARSED".equals(d.getParseStatus())&&!"PARTIAL".equals(d.getParseStatus()))out.add("没有可用的已解析正文："+nvl(d.getParseMessage()));
        return out;
    }

    @Transactional(readOnly=true)
    public List<FindingVO> listFindings(String projectId,String search,String group,String scope,String fileKey,String page) {
        projectService.require(projectId); String keyword=nvl(search).trim().toLowerCase(Locale.ROOT);
        List<VettingFinding> active=activeFindings(projectId);
        Map<String,FindingVO> annotated=new LinkedHashMap<>();
        for(FindingVO row:annotateSourceCandidateContainers(active.stream().map(this::toVO).collect(Collectors.toList())))annotated.put(row.getCode(),row);
        return active.stream().filter(f -> filter(group,f.getGroupKey())).filter(f -> filter(scope,f.getScope()))
                .filter(f -> filter(fileKey,f.getFileKey())||readEvidence(f).stream().anyMatch(e -> filter(fileKey,e.getFileKey())))
                .filter(f -> filter(page,f.getPageNo())||readEvidence(f).stream().anyMatch(e -> filter(page,e.getPageNo())))
                .filter(f -> keyword.isEmpty()||searchable(f).toLowerCase(Locale.ROOT).contains(keyword)).map(f->annotated.get(f.getCode())).collect(Collectors.toList());
    }
    public MetricsVO metrics(String projectId) { projectService.require(projectId); return metricsOf(activeFindings(projectId)); }
    public FindingVO updateStatus(String projectId,String code,String status) {
        return updateStatus(projectId,code,status,null,null,null);
    }
    public FindingVO updateStatus(String projectId,String code,String status,String expectedRunId,String expectedGeneratedRevisionSha256,String expectedSourceSnapshotSha256) {
        projectService.require(projectId); String normalized;
        if("open".equalsIgnoreCase(status)) normalized=VettingFinding.STATUS_OPEN; else if("handled".equalsIgnoreCase(status)) normalized=VettingFinding.STATUS_HANDLED; else if("assigned".equalsIgnoreCase(status)) normalized=VettingFinding.STATUS_ASSIGNED; else throw new BizException(4102,"状态必须为 Open、Handled 或 Assigned");
        synchronized(lock(projectId)) { return tx().execute(tx -> {VettingFinding f=editableFinding(projectId,code,expectedRunId,expectedGeneratedRevisionSha256,expectedSourceSnapshotSha256); f.setStatus(normalized); findingRepository.saveAndFlush(f); return currentAnnotatedFinding(f); }); }
    }
    public FindingVO updateReview(String projectId,String code,ReviewUpdateRequest request) {
        projectService.require(projectId);
        if(request==null) throw new BizException(4102,"人工审查记录不能为空请求体");
        String remarks=request.getReviewRemarks(),action=request.getActionTaken();
        Boolean decision=request.getAddendumRequired();
        // Validate the complete replacement before mutating any persisted field. Never trim user text.
        if(remarks!=null && remarks.length()>4000)
            throw new BizException(4102,"项目团队备注不能超过 4000 字符");
        if(action!=null && action.length()>4000)
            throw new BizException(4102,"实际处理记录不能超过 4000 字符");
        synchronized(lock(projectId)) { return tx().execute(tx -> {
            VettingFinding finding=editableFinding(projectId,code,request.getExpectedRunId(),request.getExpectedGeneratedRevisionSha256(),request.getExpectedSourceSnapshotSha256());
            finding.setReviewRemarks(remarks);
            finding.setActionTaken(action);
            finding.setAddendumRequired(decision);
            finding.setReviewUpdatedAt(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
            findingRepository.saveAndFlush(finding);
            return currentAnnotatedFinding(finding);
        }); }
    }
    private VettingFinding editableFinding(String projectId,String code,String runId,String generatedSha,String sourceSha){
        requireCurrentReviewRun(projectId,runId,sourceSha);
        VettingFinding finding=findingRepository.lockByProjectIdAndCode(projectId,code).orElseThrow(()->new BizException(4102,"审查发现不存在"));
        if(!Boolean.TRUE.equals(finding.getActive())||!Objects.equals(runId,finding.getRunId())||JsonUtils.isBlankText(generatedSha)||!generatedRevision(finding).equals(generatedSha))
            throw new BizException(4104,"审查发现版本已变化，请刷新后保存；原人工记录保持不变。");return finding;
    }
    /** Caller holds its write transaction; the same run lock also serializes final publication and export. */
    public VettingRun requireCurrentReviewRun(String projectId,String runId,String sourceSha){
        if(JsonUtils.isBlankText(runId)||JsonUtils.isBlankText(sourceSha))throw new BizException(4104,"保存人工记录必须提供当前审查与来源版本。");
        VettingRun run=runRepository.lockById(runId).filter(r->projectId.equals(r.getProjectId())).orElseThrow(()->new BizException(4104,"审查版本不可用，请刷新后保存。"));
        if(!currentReviewRun(run)||!sourceSha.equals(run.getSourceSnapshotSha256()))throw new BizException(4104,"审查或原件版本已变化，请刷新后保存；原人工记录保持不变。");return run;
    }
    public boolean currentReviewRun(VettingRun run){
        if(!"COMPLETED".equals(run.getStatus())||!isLatestRun(run.getProjectId(),run.getId())||sourceSetStale(run))return false;
        try{assertFixedReferencesCurrent(run,activeFindings(run.getProjectId()).stream().filter(f->run.getId().equals(f.getRunId())).map(this::toVO).collect(Collectors.toList()));return true;}
        catch(BizException stale){if(stale.getCode()!=4104)throw stale;return false;}
    }
    public EvidenceVO evidence(String projectId,String code) {
        projectService.require(projectId); VettingFinding f=findingRepository.findByProjectIdAndCode(projectId,code).orElseThrow(() -> new BizException(4102,"审查发现不存在"));
        List<FindingEvidence> saved=readEvidence(f); List<EvidenceItemVO> items=new ArrayList<>();
        for(FindingEvidence e:saved) {
            EvidenceItemVO item=new EvidenceItemVO(nvl(e.getFileKey())+" · "+nvl(e.getFileName()),e.getPageNo(),e.getQuote(),e.getSide(),e.getDocumentId(),e.getFileKey(),e.getFileName(),e.getAnchor(),e.getQuote(),e.isLocated(),e.getSourceHash(),e.getBbox(),e.getPacketId(),e.getPacketSourceSnapshotSha256(),e.getCanonicalEvidence(),e.getFixedBaselineEvidence(),e.getOriginalSourceSha256());
            item.setSourceRole(e.getSourceRole());item.setNativePositions(e.getNativePositions());item.setBusinessContentSha256(e.getBusinessContentSha256());items.add(item);
        }
        return new EvidenceVO(code+" · "+nvl(f.getRefs()),!saved.isEmpty()&&saved.stream().allMatch(FindingEvidence::isLocated),items);
    }
    /** Original upload for visual review. A parsed source revision is distinct from the raw file digest. */
    public OriginalSource originalSource(String projectId,long documentId,String expectedSourceHash) {
        return originalSource(projectId,documentId,expectedSourceHash,null);
    }
    public OriginalSource originalSource(String projectId,long documentId,String expectedSourceHash,String expectedOriginalSourceSha256) {
        return originalSource(projectId,documentId,expectedSourceHash,expectedOriginalSourceSha256,null);
    }
    public OriginalSource originalSource(String projectId,long documentId,String expectedSourceHash,String expectedOriginalSourceSha256,String expectedSourceRole) {
        projectService.require(projectId);
        SourceDocument d=currentOriginalSource(projectId,documentId);
        String revision=VettingCorpus.sourceHash(d);
        if(JsonUtils.isBlankText(expectedSourceHash)||!revision.equals(expectedSourceHash))
            throw new BizException(4102,"原文版本与证据快照不一致，请刷新审查结果");
        if(JsonUtils.isBlankText(d.getStoragePath())) throw new BizException(4102,"审查原始文件不可用");
        if(JsonUtils.isBlankText(expectedOriginalSourceSha256)||!Objects.equals(d.getSourceSha256(),expectedOriginalSourceSha256)
                ||JsonUtils.isBlankText(expectedSourceRole)||!expectedSourceRole.equals(VettingCorpus.sourceRole(d)))throw new BizException(4104,"原件字节或角色与证据版本不一致，不能回取当前替换文件。");
        try {
            Path root=Paths.get(props.getStorageRoot()).toAbsolutePath().normalize().toRealPath();
            Path path=Paths.get(d.getStoragePath()).toAbsolutePath().normalize().toRealPath();
            if(!path.startsWith(root)||!Files.isRegularFile(path)) throw new BizException(4102,"审查原始文件不可用");
            byte[] bytes=Files.readAllBytes(path);
            if(!expectedOriginalSourceSha256.equals(OerSourceMetadata.originalSha256(bytes)))throw new BizException(4104,"原件字节与证据版本不一致，不能回取当前替换文件。");
            if(unconfirmedOriginalReadable(d)) {
                if(com.consense.document.OerSourceRouting.isDrawing(d.getDocumentScope(),d.getFileName(),bytes))throw new BizException(4104,"图纸原件不属于当前审查来源范围。");
                SourceDocument known=new SourceDocument();OerSourceMetadata.captureKnownIdentity(known,bytes);
                if(OerSourceMetadata.moduleExclusion(known,"vetting")!=null||OerSourceMetadata.moduleExclusion(known,"advisory")!=null)
                    throw new BizException(4104,"已知排除原件不属于当前审查来源范围。");
            }
            SourceDocument after=currentOriginalSource(projectId,documentId);
            if(!revision.equals(VettingCorpus.sourceHash(after))||!expectedOriginalSourceSha256.equals(after.getSourceSha256())
                    ||!Objects.equals(VettingCorpus.sourceRole(d),VettingCorpus.sourceRole(after)))throw new BizException(4104,"原件版本已变化，请刷新审查结果。");
            return new OriginalSource(path,d.getFileName(),bytes.length,revision,bytes,expectedOriginalSourceSha256);
        } catch(java.io.IOException | java.nio.file.InvalidPathException e) { throw new BizException(4102,"审查原始文件不可用"); }
    }
    private SourceDocument currentOriginalSource(String projectId,long documentId) {
        SourceDocumentRepository.VerificationMetadata metadata=sourceDocumentRepository.findVerificationMetadataById(documentId)
                .filter(d->projectId.equals(d.getProjectId())&&(SourceDocument.CATEGORY_VETTING_PACKAGE.equals(d.getCategory())||SourceDocument.CATEGORY_VETTING_SUPPLEMENT.equals(d.getCategory())))
                .orElseThrow(()->new BizException(4102,"审查原始文件不存在"));
        // Scalar reads bypass a request's previously managed entity on the after-read guard.
        SourceDocument source=new SourceDocument();source.setId(metadata.getId());source.setProjectId(metadata.getProjectId());source.setCategory(metadata.getCategory());
        source.setFileKey(metadata.getFileKey());source.setFileName(metadata.getFileName());source.setReviewRole(metadata.getReviewRole());source.setDocumentScope(metadata.getDocumentScope());
        source.setParseStatus(metadata.getParseStatus());source.setOerIdentityJson(metadata.getOerIdentityJson());source.setSourceSha256(metadata.getSourceSha256());source.setStoragePath(metadata.getStoragePath());
        source.setTextContent(metadata.getTextContent());source.setStructuredContentJson(metadata.getStructuredContentJson());
        String exclusion=ProjectDocumentScope.exclusionReason(source,"fullset","vetting");
        if(exclusion!=null&&!retainedDrawingOriginalReadable(source)&&(!"source_identity_unresolved".equals(exclusion)||!unconfirmedOriginalReadable(source)))
            throw new BizException(4104,"原件不属于当前审查来源范围。");
        String name=nvl(source.getFileName()).trim().toLowerCase(Locale.ROOT);
        boolean superseded=sourceDocumentRepository.findCatalogMetadataByProjectId(projectId).stream().anyMatch(d->d.getId()>documentId
                &&(SourceDocument.CATEGORY_VETTING_PACKAGE.equals(d.getCategory())||SourceDocument.CATEGORY_VETTING_SUPPLEMENT.equals(d.getCategory()))
                &&name.equals(nvl(d.getFileName()).trim().toLowerCase(Locale.ROOT)));
        if(superseded)throw new BizException(4104,"原件已被较新上传取代，请刷新审查结果。");
        return source;
    }
    /** Downloading a positively excluded original never admits it to parsing or business evidence. */
    private boolean retainedDrawingOriginalReadable(SourceDocument source) {
        if(!"EXCLUDED".equals(source.getParseStatus())||!"DRAWING".equals(source.getFileKey()))return false;
        com.consense.document.OerDocumentIdentity identity=OerSourceMetadata.identity(source);
        if(identity==null||!"DRAWING".equals(identity.getKind())||!"drawing".equals(identity.getSourcePurpose())
                ||identity.getEligibleModules()==null||!identity.getEligibleModules().isEmpty()||identity.getIdentityEvidence()==null)return false;
        boolean explicit="drawing".equals(source.getDocumentScope());
        return (explicit?"explicit_scope":"format_supported").equals(identity.getIdentityStatus())
                &&identity.getIdentityEvidence().stream().anyMatch(evidence->evidence!=null
                    &&(explicit?"upload_document_scope":"native_drawing_format").equals(evidence.getBasis()));
    }
    /** Exact original inspection may precede purpose confirmation; this never selects a business source. */
    private boolean unconfirmedOriginalReadable(SourceDocument source) {
        if(!identityConfirmationEligible(source)&&!roleConfirmationEligible(source))return false;
        return unconfirmedOriginalIdentityAllowed(source);
    }
    private boolean unconfirmedOriginalIdentityAllowed(SourceDocument source) {
        com.consense.document.OerDocumentIdentity identity=OerSourceMetadata.identity(source);
        return identity!=null&&!"source_identity_mismatch".equals(identity.getIdentityStatus())
                &&!Arrays.asList("DRAWING","BQ","SOR","GENERAL_SUMMARY","WORKS_PSS","SL_NON_TECHNICAL").contains(identity.getKind())
                &&!Arrays.asList("standard_template","evaluation_answer","drawing").contains(identity.getSourcePurpose())
                &&OerSourceMetadata.moduleExclusion(source,"vetting")==null&&OerSourceMetadata.moduleExclusion(source,"advisory")==null
                &&identity.getCandidates().stream().noneMatch(c->"DRAWING".equals(c.getKind()));
    }
    private static final int PARSED_TEXT_MAX_CHARS=2_000_000, PARSED_TEXT_PAGE_MAX_CHARS=65_536;
    /** The revision header is explicitly the trusted business stamp, never the legacy sourceHash. */
    public OriginalSource readerOriginal(String projectId,long documentId,String originalSha,String routingSha,String businessSha) {
        projectService.require(projectId);
        if(!validRoleHash(originalSha)||!validRoleHash(routingSha)||!validRoleHash(businessSha))throw new BizException(4101,"请刷新来源版本后阅读原件。");
        SourceDocument before=readerOriginalSource(projectId,documentId,originalSha,routingSha,businessSha);
        try {
            Path root=Paths.get(props.getStorageRoot()).toAbsolutePath().normalize().toRealPath();
            Path path=Paths.get(before.getStoragePath()).toAbsolutePath().normalize().toRealPath();
            if(!path.startsWith(root)||!Files.isRegularFile(path))throw new BizException(4104,"登记原件不可用。");
            byte[] bytes=Files.readAllBytes(path);
            if(!originalSha.equals(OerSourceMetadata.originalSha256(bytes)))throw new BizException(4104,"原件字节已变化，请刷新来源后核对。");
            assertReaderOriginalIdentity(before,bytes);
            SourceDocument after=readerOriginalSource(projectId,documentId,originalSha,routingSha,businessSha);
            if(!ProjectDocumentScope.sameScopeRevision(before,after)||!storageService.matchesStoredBytes(after.getStoragePath(),originalSha))
                throw new BizException(4104,"阅读期间来源版本已变化，请刷新来源后核对。");
            return new OriginalSource(path,before.getFileName(),bytes.length,businessSha,bytes,originalSha);
        } catch(java.io.IOException|java.nio.file.InvalidPathException unavailable){throw new BizException(4104,"登记原件不可用。");}
    }
    /** Legacy rows without a content stamp may expose only their byte-verified registered original. */
    public OriginalSource registeredOriginal(String projectId,long documentId,String originalSha,String routingSha) {
        projectService.require(projectId);
        if(!validRoleHash(originalSha)||!validRoleHash(routingSha))throw new BizException(4101,"Please refresh the source before opening its original.");
        SourceDocument before=registeredOriginalSource(projectId,documentId,originalSha,routingSha);
        try {
            Path root=Paths.get(props.getStorageRoot()).toAbsolutePath().normalize().toRealPath();
            Path path=Paths.get(before.getStoragePath()).toAbsolutePath().normalize().toRealPath();
            if(!path.startsWith(root)||!Files.isRegularFile(path))throw new BizException(4104,"The registered original is unavailable.");
            byte[] bytes=Files.readAllBytes(path);
            if(!originalSha.equals(OerSourceMetadata.originalSha256(bytes)))throw new BizException(4104,"The registered original changed.");
            assertReaderOriginalIdentity(before,bytes);
            SourceDocument after=registeredOriginalSource(projectId,documentId,originalSha,routingSha);
            if(!ProjectDocumentScope.sameScopeRevision(before,after)||!storageService.matchesStoredBytes(after.getStoragePath(),originalSha))
                throw new BizException(4104,"The source changed while opening its original.");
            return new OriginalSource(path,before.getFileName(),bytes.length,routingSha,bytes,originalSha);
        } catch(java.io.IOException|java.nio.file.InvalidPathException unavailable){throw new BizException(4104,"The registered original is unavailable.");}
    }
    /** Saved parser observations only. No entity/structure/native payload, parser, adoption, or model call. */
    public Map<String,Object> parsedText(String projectId,long documentId,String originalSha,String routingSha,String businessSha,int page) {
        projectService.require(projectId);
        if(!validRoleHash(originalSha)||!validRoleHash(routingSha)||!validRoleHash(businessSha)||page<1||page>100_000)
            throw new BizException(4101,"请刷新来源版本，并选择有效页码后阅读保存的解析文字。");
        SourceDocument before=parsedTextSource(projectId,documentId,originalSha,routingSha,businessSha);
        assertSavedReaderOriginal(before,originalSha);
        SourceDocumentRepository.ParsedTextMetadata saved=sourceDocumentRepository.findBoundedParsedText(documentId,projectId,originalSha,businessSha,PARSED_TEXT_MAX_CHARS)
                .orElseThrow(()->new BizException(4104,"保存的解析文字版本已变化、不可用或超过阅读预算，请刷新来源或下载原件核对。"));
        String text=saved.getTextContent();
        if(text!=null&&text.length()>PARSED_TEXT_MAX_CHARS||!SourceContentDigests.field(text).equals(before.getTextContentSha256()))
            throw new BizException(4104,"保存的解析文字与登记版本不一致，请刷新来源后核对。");
        text=nvl(text);
        int pageCount=saved.getPageCount()==null?0:saved.getPageCount();
        if(pageCount>0&&page>pageCount)throw new BizException(4101,"所选页码超出保存的原件页数。");
        String status="saved_text",authority="saved_parser_physical_page_markers",content="";
        int textPartCount=0;
        if("IDENTITY_PENDING".equals(before.getParseStatus()))status="detailed_parsing_not_started";
        else if(!nvl(before.getFileName()).toLowerCase(Locale.ROOT).endsWith(".pdf")&&pageCount==0) {
            authority="unpaginated_saved_text";
            int from=0;
            do {
                int to=Math.min(text.length(),from+PARSED_TEXT_PAGE_MAX_CHARS);
                if(to<text.length()) {
                    int lineEnd=text.lastIndexOf('\n',to-1);
                    if(lineEnd>=from+PARSED_TEXT_PAGE_MAX_CHARS/2)to=lineEnd+1;
                    else if(Character.isHighSurrogate(text.charAt(to-1))&&Character.isLowSurrogate(text.charAt(to)))to--;
                }
                if(++textPartCount==page)content=text.substring(from,to);
                from=to;
            } while(from<text.length());
            if(page>textPartCount)throw new BizException(4101,"所选文字段号超出保存文字的分段数。");
        } else {
            Matcher markers=Pattern.compile("(?m)^--- P([1-9][0-9]*) ---\\r?$").matcher(text);
            int previous=0,start=-1,end=-1;
            while(markers.find()) {
                long physical;try{physical=Long.parseLong(markers.group(1));}catch(NumberFormatException invalid){throw new BizException(4104,"保存的解析分页标记不可核实，请下载原件核对。");}
                if(physical<=previous||physical>100_000||pageCount>0&&physical>pageCount)
                    throw new BizException(4104,"保存的解析分页标记存在歧义，请下载原件核对。");
                if(start>=0&&end<0)end=markers.start();
                if(physical==page)start=markers.end();previous=(int)physical;
            }
            if(start<0)status="no_saved_text";
            else content=text.substring(start,end<0?text.length():end).replaceFirst("^\\r?\\n","").replaceFirst("\\n\\n$","");
        }
        if(content.length()>PARSED_TEXT_PAGE_MAX_CHARS)throw new BizException(4104,"本页保存文字超过阅读预算，请下载原件核对。");
        SourceDocument after=parsedTextSource(projectId,documentId,originalSha,routingSha,businessSha);
        if(!ProjectDocumentScope.sameScopeRevision(before,after)||!storageService.matchesStoredBytes(after.getStoragePath(),originalSha))
            throw new BizException(4104,"阅读期间来源版本已变化，请刷新后重新核对。");
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("documentId",String.valueOf(documentId));result.put("fileName",before.getFileName());result.put("parseStatus",before.getParseStatus());
        result.put("originalSourceSha256",originalSha);result.put("routingSha256",routingSha);result.put("businessContentSha256",businessSha);
        result.put("textContentSha256",before.getTextContentSha256());result.put("physicalPage",textPartCount>0?null:page);result.put("pageCount",pageCount);
        if(textPartCount>0){result.put("textPart",page);result.put("textPartCount",textPartCount);}
        result.put("status",status);result.put("pageNumberAuthority",authority);result.put("text",content);
        result.put("semanticQuality","unknown");result.put("canonicalAdoption","not_performed");return result;
    }
    /** Reads bounded compact observations only; never loads a native archive or invokes a parser/provider. */
    public Map<String,Object> parsedTables(String projectId,long documentId,String originalSha,String routingSha,String businessSha,int page){
        projectService.require(projectId);
        if(!validRoleHash(originalSha)||!validRoleHash(routingSha)||!validRoleHash(businessSha)||page<1||page>100_000)throw new BizException(4101,"Choose a valid saved source revision and physical page.");
        SourceDocument before=parsedTextSource(projectId,documentId,originalSha,routingSha,businessSha);
        assertSavedReaderOriginal(before,originalSha);
        Map<String,Object> result=new LinkedHashMap<>();result.put("documentId",String.valueOf(documentId));result.put("physicalPage",page);result.put("originalSourceSha256",originalSha);result.put("routingSha256",routingSha);result.put("businessContentSha256",businessSha);result.put("tableStatus","unavailable");result.put("tables",Collections.emptyList());result.put("semanticQuality","unknown");result.put("canonicalAdoption","not_performed");
        if(!"IDENTITY_PENDING".equals(before.getParseStatus())){
            Optional<SourceDocumentRepository.ParsedReadingMetadata> saved=sourceDocumentRepository.findBoundedParsedReading(documentId,projectId,originalSha,businessSha,16*1024*1024);
            if(saved.isPresent()){
                SourceDocumentRepository.ParsedReadingMetadata row=saved.get();if(row.getPageCount()!=null&&row.getPageCount()>0&&page>row.getPageCount())throw new BizException(4101,"The selected page exceeds the registered original page count.");
                if(SourceContentDigests.field(row.getStructuredContentJson()).equals(before.getStructuredContentSha256())&&SourceContentDigests.field(row.getParseCoverageJson()).equals(before.getParseCoverageSha256())){
                    try{
                        JsonNode coverage=JsonUtils.parse(row.getParseCoverageJson());
                        if(coverage.has(NormalPdfReadingProjection.FIELD)){
                            List<com.consense.document.DocumentBlock> blocks=JsonUtils.readList(row.getStructuredContentJson(),com.consense.document.DocumentBlock.class);
                            JsonNode groups=NormalPdfReadingProjection.validate(blocks,originalSha,coverage);List<Map<String,Object>> tables=NormalPdfReadingProjection.tableReadings(groups,page);
                            result.put("tables",tables);result.put("tableStatus",tables.isEmpty()?"no_saved_tables":"saved_tables");
                        }else result.put("tableStatus","not_supported");
                    }catch(RuntimeException invalid){result.put("tableStatus","unavailable");result.put("tables",Collections.emptyList());}
                }
            }
        }
        SourceDocument after=parsedTextSource(projectId,documentId,originalSha,routingSha,businessSha);
        if(!ProjectDocumentScope.sameScopeRevision(before,after)||!storageService.matchesStoredBytes(after.getStoragePath(),originalSha))throw new BizException(4104,"The source revision changed while reading saved tables.");
        return result;
    }
    private void assertSavedReaderOriginal(SourceDocument source,String originalSha){
        if(!storageService.matchesStoredBytes(source.getStoragePath(),originalSha))throw new BizException(4104,"The registered original is unavailable or has changed.");
        try{
            Path original=Paths.get(source.getStoragePath()).toAbsolutePath().normalize().toRealPath();
            if(!original.startsWith(Paths.get(props.getStorageRoot()).toAbsolutePath().normalize().toRealPath()))throw new BizException(4104,"The registered original is unavailable.");
            try(java.io.InputStream input=Files.newInputStream(original)){assertReaderOriginalIdentity(source,input.readNBytes(64));}
        }catch(java.io.IOException unavailable){throw new BizException(4104,"The registered original is unavailable.");}
    }
    private SourceDocument parsedTextSource(String projectId,long documentId,String originalSha,String routingSha,String businessSha) {
        return savedSource(projectId,documentId,originalSha,routingSha,businessSha,false);
    }
    private SourceDocument readerOriginalSource(String projectId,long documentId,String originalSha,String routingSha,String businessSha) {
        return savedSource(projectId,documentId,originalSha,routingSha,businessSha,true);
    }
    private SourceDocument registeredOriginalSource(String projectId,long documentId,String originalSha,String routingSha) {
        FileDisplay display=fileDisplaySourceSet(projectId).stream().filter(row->Objects.equals(row.source.getId(),documentId)).findFirst()
                .orElseThrow(()->new BizException(4104,"The registered original is no longer current."));
        SourceDocument source=display.source;
        String exclusion=ProjectDocumentScope.exclusionReason(source,"fullset","vetting");
        if(source.getBusinessContentSha256()!=null||!originalSha.equals(source.getSourceSha256())
                ||!routingSha.equals(display.routingSha)
                ||exclusion!=null&&(!"source_identity_unresolved".equals(exclusion)
                    ||!(roleConfirmationEligible(display)||identityConfirmationEligible(display))
                    ||!unconfirmedOriginalIdentityAllowed(source)))
            throw new BizException(4104,"The registered original identity or scope changed.");
        return source;
    }
    private SourceDocument savedSource(String projectId,long documentId,String originalSha,String routingSha,String businessSha,boolean originalOnly) {
        SourceDocument source=sourceDocumentRepository.findScopeMetadataById(documentId).map(SourceDocumentRepository::scopeSnapshot)
                .filter(d->projectId.equals(d.getProjectId())&&ProjectDocumentScope.reviewCategory(d))
                .orElseThrow(()->new BizException(4104,"当前项目的保存解析来源不可用，请刷新来源列表。"));
        String exclusion=ProjectDocumentScope.exclusionReason(source,"fullset","vetting");
        if(!Arrays.asList("PARSED","PARTIAL","IDENTITY_PENDING").contains(source.getParseStatus())
                &&(!originalOnly||!Arrays.asList("PARSING","FAILED").contains(source.getParseStatus()))
                ||exclusion!=null&&(!"source_identity_unresolved".equals(exclusion)||!unconfirmedOriginalIdentityAllowed(source))
                ||!SourceContentDigests.stamped(source)||!originalSha.equals(source.getSourceSha256())
                ||!routingSha.equals(CanonicalEvidenceService.routingHash(source))||!businessSha.equals(source.getBusinessContentSha256()))
            throw new BizException(4104,"保存的解析来源范围、状态或可信版本不可用，请刷新来源或下载原件核对。");
        assertRoleSourceCurrent(source);return source;
    }
    private void assertReaderOriginalIdentity(SourceDocument source,byte[] prefix) {
        if(com.consense.document.OerSourceRouting.isDrawing(source.getDocumentScope(),source.getFileName(),prefix))
            throw new BizException(4104,"图纸原件不属于当前审查来源范围。");
        SourceDocument known=new SourceDocument();known.setSourceSha256(source.getSourceSha256());
        known.setOerIdentityJson(JsonUtils.write(OerSourceMetadata.refreshedFixedIdentity(source,source.getSourceSha256())));
        if(OerSourceMetadata.moduleExclusion(known,"vetting")!=null||OerSourceMetadata.moduleExclusion(known,"advisory")!=null)
            throw new BizException(4104,"已知排除原件不属于当前审查来源范围。");
    }
    @lombok.Value public static class OriginalSource { Path path; String fileName; long size; String sourceRevision; byte[] bytes; String sha256; }
    public byte[] exportPdf(String projectId,String lang) { return exportObserved(projectId,lang,"pdf"); }
    public byte[] exportDocx(String projectId,String lang) { return exportObserved(projectId,lang,"docx"); }
    public byte[] exportJson(String projectId,String lang) { return exportObserved(projectId,lang,"json"); }
    private byte[] exportObserved(String projectId,String lang,String format) {
        VettingExportProbe probe=VettingExportProbe.start(props,projectId,format,lang);boolean returnedNormally=false;Throwable operationFailure=null;
        try {
            ReportContext c;long started=System.nanoTime();
            try{c=reportContext(projectId);}
            catch(RuntimeException|Error e){probe.originalFailureElapsed("report_context_lookup_failed_elapsed",e,System.nanoTime()-started);throw e;}
            long contextElapsed=System.nanoTime()-started;
            probe.bindRun(c.job.getId());
            probe.event("report_context_lookup_elapsed",DocumentParseProbe.map("wallNanos",contextElapsed,"seconds",contextElapsed/1_000_000_000.0,
                    "boundary","Actual unchanged reportContext lookup and payload DTO construction, measured before probe I/O"));
            probe.event("report_payload",DocumentParseProbe.map("project",c.project,"job",c.job,"findings",c.findings));
            List<VettingChecklistCatalog.Result> checklist = c.checklist;
            if(englishFiles==null)throw new BizException(4100,"English file output is unavailable. No untranslated report was returned.");
            String sourceReference="vetting-report:"+c.job.getId()+":"+c.job.getReportSnapshotId();
            String frozenId=c.job.getReportSnapshotId(),frozenRun=c.job.getId(),frozenSource=c.job.getSourceSnapshotSha256();
            Runnable frozenGuard=()->{
                if(Thread.currentThread().isInterrupted())throw new BizException(4100,"English report export was cancelled.");
                if(!Objects.equals(frozenId,c.job.getReportSnapshotId())||!Objects.equals(frozenRun,c.job.getId())||!Objects.equals(frozenSource,c.job.getSourceSnapshotSha256()))
                    throw new BizException(4104,"The frozen report reference changed during English export.");
            };
            byte[] bytes;
            if("pdf".equals(format)||"docx".equals(format)) {
                Set<String> appAuthoredEnglish=new HashSet<>();
                byte[] originalDocx=reportWriter.docx(c.project,c.findings,c.job,"en",probe.enabled()?probe:null,checklist,appAuthoredEnglish);
                byte[] docx=englishFiles.docx(originalDocx,sourceReference,frozenGuard,appAuthoredEnglish);
                if(docx==null||docx.length==0)throw new BizException(4100,"The English Word report was not completed.");
                if("docx".equals(format))bytes=docx;
                else {
                    if(draftingIdentity==null||!"libreoffice".equals(props.getDrafting().getRendererKind()))throw new BizException(4100,"NOT_CONFIGURED: Vetting PDF export requires the configured LibreOffice document renderer. The Word report remains available.");
                    probe.event("report_pdf_input",DocumentParseProbe.map("reportSnapshotId",c.job.getReportSnapshotId(),"docxSha256",com.consense.service.drafting.DraftPdfConverter.sha256(docx),"renderer","libreoffice"));
                    byte[] rendererPdf;
                    try(VettingExportProbe.Timer timer=probe.measure("report_docx_pdf_conversion","Shared exact-DOCX LibreOffice conversion/cache; no independent report pagination")){rendererPdf=draftingIdentity.renderPdf(docx);}
                    bytes=bindPdfSnapshot(rendererPdf,c.job.getReportSnapshotId());
                    probe.event("report_pdf_snapshot_binding",DocumentParseProbe.map("reportSnapshotId",c.job.getReportSnapshotId(),"docxSha256",com.consense.service.drafting.DraftPdfConverter.sha256(docx),
                            "rendererPdfSha256",com.consense.service.drafting.DraftPdfConverter.sha256(rendererPdf),"pdfSha256",com.consense.service.drafting.DraftPdfConverter.sha256(bytes),"bytes",bytes.length,
                            "boundary","Document information snapshot key only; converter cache/manifest retains the original renderer PDF hash; returned artifact records the final download hash"));
                }
            }
            else {
                Map<String,Object> out;
                try(VettingExportProbe.Timer timer=probe.measure("report_json_payload_construction","Original JSON report DTO/map construction")){
                    out=englishReportPresentation(c);
                }
                JsonNode english=englishFiles.json(JsonUtils.mapper().valueToTree(out),sourceReference,frozenGuard);
                if(english==null||!english.isObject())throw new BizException(4100,"The English report presentation was not completed.");
                try(VettingExportProbe.Timer timer=probe.measure("report_json_serialization","English presentation JSON serialization; the original frozen snapshot remains server-side")){bytes=JsonUtils.write(english).getBytes(StandardCharsets.UTF_8);}
            }
            frozenGuard.run();
            ReportContext latest=reportContext(projectId,false);
            if(!Objects.equals(frozenId,latest.job.getReportSnapshotId()))throw new BizException(4104,"The source set or human review changed during English export. Generate the report again; original records are retained.");
            probe.returned(bytes);returnedNormally=true;return bytes;
        }catch(RuntimeException|Error e){operationFailure=e;probe.originalFailure("report_export_failure",e);throw e;}
        finally{probe.finish(returnedNormally?"returned_normally":"aborted",operationFailure);}
    }
    /** A derived reader view, never a replacement for the signed source/report snapshot or raw model record. */
    private Map<String,Object> englishReportPresentation(ReportContext c) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("schemaVersion","2.0");out.put("language","en");out.put("reportLanguage","en");out.put("presentationOnly",true);
        out.put("presentationVersion","vetting-english-presentation-v2");out.put("reportStructureVersion","competition-comments-and-checklist-v1");
        out.put("sourceReportSnapshotId",c.job.getReportSnapshotId());out.put("sourceRunId",c.job.getId());out.put("sourceSnapshotSha256",c.job.getSourceSnapshotSha256());
        out.put("authorityMeaning","This English presentation is derived from the referenced original snapshot. Translated wording is not original evidence or a new source authority.");
        com.fasterxml.jackson.databind.node.ObjectNode project=reportFields(c.project,"id","contractNo","packageRef");
        project.put("name",LocalizedText.of(c.project.getNameZhHans(),c.project.getNameZhHant(),c.project.getNameEn()).pick("en"));project.put("displayOnly",true);out.put("project",project);
        Map<String,Object> identity=new LinkedHashMap<>();identity.put("contractNumber",VettingReportWriter.reportIdentity(c.job,"number"));identity.put("projectOrContractTitle",VettingReportWriter.reportIdentity(c.job,"title"));identity.put("displayOnly",true);identity.put("originalAuthorityRetainedServerSide",true);out.put("packageIdentityPresentation",identity);
        com.fasterxml.jackson.databind.node.ObjectNode job=reportFields(c.job,"id","status","phase","completedUnits","totalUnits","message","startedAt","finishedAt","modelIdentity","sourceSetStale","recoveryStatus");
        if(c.job.getResult()!=null){job.put("recordedFindingCount",c.job.getResult().getTotal());job.set("metrics",JsonUtils.mapper().valueToTree(c.job.getResult().getMetrics()));}job.set("coverage",reportCoverage(c.job.getCoverage()));out.put("job",job);
        List<Object> findings=new ArrayList<>();for(FindingVO finding:c.findings){
            com.fasterxml.jackson.databind.node.ObjectNode row=reportFields(finding,"code","types","issueTypeCode","group","scope","severity","status","expected","refs","location","fileKey","pageNo","runId","source","verification","reviewRemarks","actionTaken","addendumRequired","reviewUpdatedAt","generatedRevisionSha256","sourceSnapshotSha256","businessDetails","sourceCandidateContainer");
            row.put("title",reportText(finding.getTitle()));row.put("body",reportText(finding.getBody()));row.put("impact",reportText(finding.getImpact()));row.put("suggestion",reportText(finding.getSuggestion()));
            row.put("verificationMeaning","Verification refers to the original retained evidence only, not the translated presentation.");row.set("evidence",reportEvidence(finding.getEvidence()));findings.add(row);
        }out.put("findings",findings);
        List<com.consense.web.dto.VettingChecklistDtos.AutomaticGroupVO> automatic=VettingChecklistAutomaticResults.aggregate(c.job.getStatus(),JsonUtils.mapper().valueToTree(c.job.getCoverage()),JsonUtils.mapper().valueToTree(c.job.getResult()));
        out.put("commentsTables",VettingReportPresentation.readerComments(c.findings,c.job.getCoverage()));out.put("sectionSummaries",VettingReportPresentation.sectionSummaries(c.findings,c.job.getCoverage()));
        out.put("automaticChecklistResults",reportAutomatic(automatic));out.put("checklistTables",VettingReportPresentation.readerChecklist(c.checklist,automatic));
        List<Object> checklist=new ArrayList<>();for(VettingChecklistCatalog.Result result:c.checklist)checklist.add(reportFields(result,"id","status","remarks","updatedAt","reviewedRunId","reviewScope","sourceSnapshotSha256","dependencyStatus","previousDecisionStatus","previousRemarks","previousReviewedRunId"));out.put("checklistResults",checklist);
        out.put("evidenceIndex",VettingReportPresentation.readerEvidenceIndex(c.findings,c.job.getCoverage()));
        Map<String,Object> diagnostics=new LinkedHashMap<>();diagnostics.put("retainedServerSide",true);diagnostics.put("sourceRunId",c.job.getId());diagnostics.put("sourceReportSnapshotId",c.job.getReportSnapshotId());
        diagnostics.put("meaning","Original prompts, provider responses, source proofs, frozen identity records and unmodified human records remain in the system. This file contains their English reader presentation and references only.");out.put("diagnostics",diagnostics);out.put("diagnosticsRetainedServerSide",true);
        return out;
    }
    private static String reportText(LocalizedText text){return text==null?"":text.pick("en");}
    private static com.fasterxml.jackson.databind.node.ObjectNode reportFields(Object original,String... fields){
        JsonNode raw=original instanceof JsonNode?(JsonNode)original:JsonUtils.mapper().valueToTree(original);com.fasterxml.jackson.databind.node.ObjectNode out=JsonUtils.mapper().createObjectNode();
        if(raw!=null)for(String field:fields)if(raw.has(field))out.set(field,raw.get(field).deepCopy());return out;
    }
    private static com.fasterxml.jackson.databind.node.ArrayNode reportEvidence(List<FindingEvidence> evidence){
        com.fasterxml.jackson.databind.node.ArrayNode out=JsonUtils.mapper().createArrayNode();for(FindingEvidence original:evidence==null?Collections.<FindingEvidence>emptyList():evidence)if(original!=null){
            com.fasterxml.jackson.databind.node.ObjectNode row=reportFields(original,"side","documentId","fileKey","fileName","pageNo","anchor","clauseId","sourceHash","originalSourceSha256","sourceRole","packetId","packetSourceSnapshotSha256");
            row.put("displayQuote",nvl(original.getQuote()));row.put("quotePresentation","English translation/presentation of source quotation; original wording is retained in the system. This display is not literal source evidence.");
            row.put("literalSourceEvidence",false);row.put("originalQuoteSha256",VettingCorpus.hash(nvl(original.getQuote())));row.put("originalLocated",original.isLocated());
            if(original.getCanonicalEvidence()!=null)row.set("canonicalEvidenceReference",reportFields(original.getCanonicalEvidence(),"projectId","documentId","canonicalChunkId","unitId","originalSourceSha256","javaParsedSourceHash","sourceRevisionSha256","projectionSha256","canonicalSourceHash"));
            if(original.getFixedBaselineEvidence()!=null)row.set("fixedBaselineReference",reportFields(original.getFixedBaselineEvidence(),"parentId","manifestSha256","baselineVersion","sourceFamily","sourceRole","originalSourceSha256","originalPageNos"));out.add(row);
        }return out;
    }
    private static com.fasterxml.jackson.databind.node.ObjectNode reportCoverage(CoverageVO original){
        com.fasterxml.jackson.databind.node.ObjectNode out=reportFields(original,"totalDocuments","reviewedDocuments","documents","warnings","reviewCompleteness","canonicalCorpusIdentitySha256");
        if(original!=null&&original.getSemanticSourceRead()!=null)out.set("semanticSourceRead",reportFields(original.getSemanticSourceRead(),"status","totalOrigins","readableOrigins"));
        com.fasterxml.jackson.databind.node.ArrayNode topics=out.putArray("semanticTopics");for(SemanticTopicVO topic:original==null||original.getSemanticTopics()==null?Collections.<SemanticTopicVO>emptyList():original.getSemanticTopics())if(topic!=null){
            com.fasterxml.jackson.databind.node.ObjectNode row=reportFields(topic,"topicIndex","topic","status","error","reviewKind","selectionStrategy","failureKind","referenceIds","submittedChars","budgetDroppedGroups","partialContextGroups","unresolvedSegments","initialContextBudgetChars","effectiveContextBudgetChars","missingLocatedReferenceTargets","contextExpansionAttempted","contextExpanded","returnedAssessments","issueAssessments","consistentAssessments","insufficientContextAssessments","acceptedFindings","rejectedRecords","verifiedConsistentAssessments","verifiedInsufficientContextAssessments","verifiedNotApplicableAssessments","checkState","globalCallStatus","aggregateReviewStatus","requestPacketPolicy","reviewCoverageScope","sourceRequestCount","pendingSourceRequestCount","extraPacketCount","failedPacketCount","notSubmittedPacketCount","semanticScopeVerified");
            com.fasterxml.jackson.databind.node.ArrayNode packets=row.putArray("packetAudits");for(SemanticPacketVO packet:topic.getPacketAudits()==null?Collections.<SemanticPacketVO>emptyList():topic.getPacketAudits())if(packet!=null){
                com.fasterxml.jackson.databind.node.ObjectNode p=reportFields(packet,"packetId","sourceObservationPacketId","kind","status","failureKind","error","packetIndex","submittedChars","requestIds","inputBudgetStatus","returnedAssessments","issueAssessments","consistentAssessments","insufficientContextAssessments","acceptedFindings","rejectedRecords","actualGatewayCallStarted","semanticScopeVerified","verifiedConsistentAssessments","verifiedInsufficientContextAssessments","verifiedNotApplicableAssessments","checkState","checkpointStatus","executionLedger");
                JsonNode budget=packet.getInputBudgetMetadata();com.fasterxml.jackson.databind.node.ObjectNode inputBudget=reportFields(budget,"status","reason","extraDispatchPermitted","serializedInputSha256");
                JsonNode observed=budget==null?null:budget.get("observation");com.fasterxml.jackson.databind.node.ObjectNode observation=reportFields(observed,"model","inputTokens","effectiveContextTokens","outputReserveTokens","scope","completeChatTemplateAndSchemaObserved");
                if(observed!=null){observation.set("qwenUsageEstimate",reportFields(observed.get("qwenUsageEstimate"),"kind","version","configuredContextTokens","safetyMarginTokens","completionTokens","modelIdentityScope","finishReason"));observation.set("providerEstimate",reportFields(observed.get("providerEstimate"),"kind","estimatedInputTokens","configuredContextTokens","outputReserveTokens","safetyMarginTokens","scope"));}inputBudget.set("observation",observation);p.set("inputBudget",inputBudget);
                p.set("modelResponseMetadata",reportFields(packet.getModelResponseMetadata(),"model","usage","usageUnavailable","scope","responseSha256"));
                com.fasterxml.jackson.databind.node.ArrayNode checks=p.putArray("verifiedAssessments");for(VerifiedAssessmentVO assessment:packet.getVerifiedAssessments()==null?Collections.<VerifiedAssessmentVO>emptyList():packet.getVerifiedAssessments())if(assessment!=null){com.fasterxml.jackson.databind.node.ObjectNode a=reportFields(assessment,"assessment","title","comment");a.set("evidence",reportEvidence(assessment.getEvidence()));checks.add(a);}packets.add(p);
            }
            com.fasterxml.jackson.databind.node.ArrayNode requests=row.putArray("sourceRequests");for(SemanticSourceRequestVO request:topic.getSourceRequests()==null?Collections.<SemanticSourceRequestVO>emptyList():topic.getSourceRequests())requests.add(reportFields(request,"requestId","kind","observationId","transportStatus","unknownReason","reviewExecutionStatus","packetId","packIndex","contentChars","requiredChunkIds","missingChunkIds","applicability","qualifiersComplete","semanticScopeVerified"));topics.add(row);
        }return out;
    }
    private static List<Object> reportAutomatic(List<com.consense.web.dto.VettingChecklistDtos.AutomaticGroupVO> original){
        List<Object> out=new ArrayList<>();for(com.consense.web.dto.VettingChecklistDtos.AutomaticGroupVO group:original){com.fasterxml.jackson.databind.node.ObjectNode row=reportFields(group,"id","status","symbol","reason","complete","sourceItemIds");com.fasterxml.jackson.databind.node.ArrayNode items=row.putArray("subItemAssessments");
            for(com.consense.web.dto.VettingChecklistDtos.AutomaticItemVO item:group.getSubItemAssessments()==null?Collections.<com.consense.web.dto.VettingChecklistDtos.AutomaticItemVO>emptyList():group.getSubItemAssessments()){
                com.fasterxml.jackson.databind.node.ObjectNode entry=reportFields(item,"itemId","status","reason");entry.put("assessmentCount",item.getAssessments()==null?0:item.getAssessments().size());com.fasterxml.jackson.databind.node.ArrayNode refs=entry.putArray("assessmentReferences");
                for(JsonNode assessment:item.getAssessments()==null?Collections.<JsonNode>emptyList():item.getAssessments())refs.add(reportFields(assessment,"packetId","itemId","status","reason","issueTypeCode","evidenceChunkIds","sourceSnapshotSha256"));items.add(entry);
            }out.add(row);
        }return out;
    }
    private byte[] bindPdfSnapshot(byte[] rendererPdf,String reportSnapshotId) {
        try(org.apache.pdfbox.pdmodel.PDDocument document=org.apache.pdfbox.pdmodel.PDDocument.load(new ByteArrayInputStream(rendererPdf));ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            document.getDocumentInformation().setCustomMetadataValue("ConSenseReportSnapshotId",reportSnapshotId);
            document.save(out);return out.toByteArray();
        }catch(IOException failure){throw new BizException(4100,"Vetting PDF snapshot metadata could not be bound. The Word report remains available: "+failure.getMessage());}
    }
    private ReportContext reportContext(String projectId) {
        return reportContext(projectId,true);
    }
    private ReportContext reportContext(String projectId,boolean persist) {
        projectService.require(projectId); synchronized(lock(projectId)) { return tx().execute(status -> {
            Project p=projectService.require(projectId);String latestId=sourceDigestPreparation==null
                    ?runRepository.findFirstByProjectIdOrderByStartedAtDesc(projectId).map(VettingRun::getId).orElse(null)
                    :runRepository.findLatestProgress(projectId,org.springframework.data.domain.PageRequest.of(0,1)).stream().findFirst().map(VettingRunRepository.ProgressView::getId).orElse(null);
            if(latestId==null)throw new BizException(4103,"尚未完成审查，无法导出报告");
            VettingRun run=runRepository.lockById(latestId).orElseThrow(()->new BizException(4103,"审查任务不存在"));
            if(!"COMPLETED".equals(run.getStatus())) throw new BizException(4103,"最近审查任务尚未成功完成，请等待或重新运行");
            // shortcut: Frozen-report export checks current pins, not live CAS bytes; recheck bytes if exports consume live canonical content.
            if(sourceSetStale(run,true)) throw new BizException(4104,"审查文件包或资料版本已变化，请重新审查后导出本次报告；原审查及人工核对记录仍保留。");
            List<FindingVO> findings=activeFindings(projectId).stream().filter(f -> run.getId().equals(f.getRunId())).map(f->toVO(f,run.getSourceSnapshotSha256())).collect(Collectors.toList());
            assertFixedReferencesCurrent(run,findings);
            return saveReportSnapshot(p,run,findings,persist);
        }); }
    }
    private ReportContext saveReportSnapshot(Project project,VettingRun run,List<FindingVO> findings) {
        return saveReportSnapshot(project,run,findings,true);
    }
    private ReportContext saveReportSnapshot(Project project,VettingRun run,List<FindingVO> findings,boolean persist) {
        findings=annotateSourceCandidateContainers(findings);
        VettingJobVO job=runSummary(run);job.setCoverage(JsonUtils.read(run.getCoverageJson(),CoverageVO.class));job.setReportSnapshotId(null);
        if(job.getCoverage()!=null&&job.getCoverage().getPackageIdentityMirrorAudit()!=null)
            job.getCoverage().setPackageIdentityMirrorAudit(packageMirrorAudit(job.getCoverage().getPackageIdentityMirrorAudit().get("captured"),project));
        if(reportSnapshots!=null) {
            VettingRunRepository.ProgressView stored=runRepository.findProgress(run.getProjectId(),run.getId())
                    .orElseThrow(()->new BizException(4103,"已保存的审查任务不存在，不能创建报告快照。"));
            if(!Objects.equals(run.getId(),stored.getId())||!"COMPLETED".equals(run.getStatus())||!"COMPLETED".equals(stored.getStatus()))
                throw new BizException(4103,"已保存的审查任务身份或完成状态不一致，不能创建报告快照。");
            // Scalar reads use the database's actual timestamp precision after the caller's flush.
            job.setStartedAt(stored.getStartedAt());job.setFinishedAt(stored.getFinishedAt());
        }
        List<VettingChecklistCatalog.Result> checklist=checklistService==null?Collections.emptyList():checklistService.resultsForRun(run);
        ReportSnapshotPayload current=new ReportSnapshotPayload();current.setProject(project);current.setJob(job);current.setFindings(findings);current.setChecklist(checklist);
        String payload=JsonUtils.write(current),id=VettingCorpus.hash("vetting-report-snapshot-v1\n"+payload);
        if(reportSnapshots!=null&&persist) {
            VettingReportSnapshot saved=reportSnapshots.findById(id).orElse(null);
            if(saved==null){saved=new VettingReportSnapshot();saved.setId(id);saved.setRunId(run.getId());saved.setPayloadJson(payload);saved.setCreatedAt(Instant.now());reportSnapshots.saveAndFlush(saved);}
            if(!id.equals(VettingCorpus.hash("vetting-report-snapshot-v1\n"+saved.getPayloadJson()))||!run.getId().equals(saved.getRunId()))throw new BizException(4103,"报告快照校验失败，不能导出");
            run.setReportSnapshotSha256(id);runRepository.saveAndFlush(run);
        }
        current.getJob().setReportSnapshotId(id);return new ReportContext(current.getProject(),current.getJob(),current.getFindings(),current.getChecklist());
    }
    public String pdfFileName(String id) { return reportFileName(id,"pdf"); }
    public String reportFileName(String id,String format) { projectService.require(id);return "ConSense_Vetting_Report."+format; }
    private static final class ReportContext { final Project project; final VettingJobVO job; final List<FindingVO> findings;final List<VettingChecklistCatalog.Result> checklist; ReportContext(Project p,VettingJobVO j,List<FindingVO> f,List<VettingChecklistCatalog.Result> c){project=p;job=j;findings=f;checklist=c;} }
    @lombok.Data @lombok.NoArgsConstructor
    public static class ReportSnapshotPayload {private Project project;private VettingJobVO job;private List<FindingVO> findings;private List<VettingChecklistCatalog.Result> checklist;}
    private VettingJobVO jobVO(VettingRun r) {VettingJobVO job=runSummary(r);job.setCoverage(JsonUtils.read(r.getCoverageJson(),CoverageVO.class));job.setSourceSetStale(sourceSetStale(r));if(checkpoints!=null)job.setReliablePackets(checkpoints.reliable(r.getId()));return job;}
    private VettingJobVO runSummary(VettingRun r) {VettingJobVO job=new VettingJobVO(r.getId(),r.getStatus(),r.getPhase(),r.getCompletedUnits(),r.getTotalUnits(),r.getMessage(),r.getStartedAt(),r.getFinishedAt(),JsonUtils.read(r.getResultJson(),RunResultVO.class),r.getError(),null);job.setModelIdentity(JsonUtils.read(r.getModelIdentityJson(),com.consense.ai.ModelIdentity.class));job.setSourceSnapshotSha256(r.getSourceSnapshotSha256());job.setRecoveryStatus(r.getRecoveryStatus());job.setReportSnapshotId(r.getReportSnapshotSha256());return job;}
    private static String sourceSetHash(List<SourceDocument> documents) {
        List<String> records = documents.stream().map(d -> {List<Object> identity=new ArrayList<>(Arrays.asList(d.getId(),d.getFileName(),d.getFileKey(),
                VettingCorpus.sourceRole(d),d.getDocumentScope(),d.getParseStatus(),VettingCorpus.sourceHash(d),
                coverageContentHash(d),normalCandidateContentHash(d)));
            if(!JsonUtils.isBlankText(d.getSourceSha256()))identity.add(d.getSourceSha256());return JsonUtils.write(identity);})
                .sorted().collect(Collectors.toList());
        return VettingCorpus.hash("competition-review-package-v2\n"+JsonUtils.write(records));
    }
    private static boolean compactSources(List<SourceDocument> documents){return documents!=null&&!documents.isEmpty()
            &&documents.stream().allMatch(d->SourceContentDigests.stamped(d)&&d.getTextContent()==null&&d.getStructuredContentJson()==null
            &&d.getParseCoverageJson()==null&&d.getNormalCandidateJson()==null);}
    private static String compactSourceSetHash(List<SourceDocument> documents){String stamp=sourceStampHash(documents);
        return stamp==null?null:VettingCorpus.hash("competition-review-package-v3-content-stamps\n"+stamp);}
    private static boolean sourceSnapshotMatches(String recorded,List<SourceDocument> documents){
        return SourceContentDigests.valid(recorded)&&(Objects.equals(recorded,compactSourceSetHash(documents))||!compactSources(documents)&&Objects.equals(recorded,sourceSetHash(documents)));}
    private void freezePreparedSources(VettingRun dispatched,VettingRun captured,CoverageVO coverage,List<SourceDocument> snapshot,OerBusinessEvidence.Session session){
        assertMaterialSourcesCurrent(captured.getProjectId(),snapshot,false);String prepared=compactSourceSetHash(snapshot),prior=captured.getSourceSnapshotSha256();
        if(coverage.getCanonicalCorpusIdentitySha256()!=null&&(!Objects.equals(prior,prepared)
                ||!Objects.equals(coverage.getCanonicalCorpusIdentitySha256(),session.identitySha256())))
            throw new BizException(4104,"The prepared source scope differs from the recovered frozen run.");
        coverage.setSourceStampSha256(sourceStampHash(snapshot));coverage.setCanonicalSourceDocumentIds(session.documentIds);
        coverage.setCanonicalCorpusIdentitySha256(session.identitySha256());
        coverage.setPackageIdentityReview(freezePackageIdentity(coverage.getPackageIdentityReview().get("authority"),prepared));
        String raw=JsonUtils.write(coverage);
        tx().execute(status->{VettingRun run=ownedRun(captured.getId());
            if(!ACTIVE_STATES.contains(run.getStatus())||!Objects.equals(prior,run.getSourceSnapshotSha256()))throw new BizException(4104,"The queued review identity changed during preparation.");
            run.setSourceSnapshotSha256(prepared);run.setCoverageJson(raw);run.setPhase("prepared");runRepository.saveAndFlush(run);return null;});
        for(VettingRun run:Arrays.asList(captured,dispatched)){run.setSourceSnapshotSha256(prepared);run.setCoverageJson(raw);run.setPhase("prepared");}
    }
    static String sourceStampHash(List<SourceDocument> documents) {
        if(documents==null||!documents.stream().allMatch(SourceContentDigests::stamped))return null;
        List<String> records=documents.stream().map(d->JsonUtils.write(Arrays.asList(d.getId(),d.getProjectId(),d.getCategory(),
                d.getFileName(),d.getFileKey(),d.getReviewRole(),d.getDocumentScope(),d.getParseStatus(),
                d.getOerIdentityJson(),d.getSourceSha256(),d.getStoragePath(),d.getBusinessContentSha256())))
                .sorted().collect(Collectors.toList());
        return VettingCorpus.hash("vetting-selected-source-stamps-v1\n"+JsonUtils.write(records));
    }
    private static String coverageContentHash(SourceDocument d){if(d.getCalculatedCoverageHash()==null)d.setCalculatedCoverageHash(VettingCorpus.hash(nvl(d.getParseCoverageJson())));return d.getCalculatedCoverageHash();}
    private static String normalCandidateContentHash(SourceDocument d){if(d.getCalculatedNormalCandidateHash()==null)d.setCalculatedNormalCandidateHash(VettingCorpus.hash(nvl(d.getNormalCandidateJson())));return d.getCalculatedNormalCandidateHash();}
    private boolean sourceSetStale(VettingRun run) {return sourceSetStale(run,false);}
    private boolean sourceSetStale(VettingRun run,boolean display) {
        return sourceSetStale(run,display,true);
    }
    private boolean sourceSetStale(VettingRun run,boolean display,boolean verifyOriginal) {
        CoverageVO recorded=JsonUtils.read(run.getCoverageJson(),CoverageVO.class);
        boolean compact=recorded!=null&&!JsonUtils.isBlankText(recorded.getSourceStampSha256());
        List<SourceDocument> current=compact?freshSourceStamps(run.getProjectId()):canonicalEnabled()?freshSourceSet(run.getProjectId()):sourceSet(run.getProjectId());
        if(verifyOriginal&&!originalSourcesCurrent(current))return true;
        if(compact&&!Objects.equals(recorded.getSourceStampSha256(),sourceStampHash(current)))return true;
        if(!packageIdentityCurrent(run,recorded))return true;
        boolean compactIdentity=compact&&Objects.equals(run.getSourceSnapshotSha256(),compactSourceSetHash(current));
        if(!JsonUtils.isBlankText(run.getSourceSnapshotSha256())){
            if(compact&&!compactIdentity){
                List<SourceDocument> legacy=canonicalEnabled()?freshSourceSet(run.getProjectId()):sourceSet(run.getProjectId());
                if(!Objects.equals(run.getSourceSnapshotSha256(),sourceSetHash(legacy)))return true;
            }else if(!compact&&!run.getSourceSnapshotSha256().equals(sourceSetHash(current)))return true;
            if(recorded!=null&&recorded.getCanonicalCorpusIdentitySha256()!=null){
                if(!canonicalEnabled())return true;
                List<SourceDocument> canonicalCurrent=current;
                List<Long> admittedIds=recorded.getCanonicalSourceDocumentIds();
                if(admittedIds!=null){
                    Set<Long> ids=new LinkedHashSet<>(admittedIds);
                    canonicalCurrent=current.stream().filter(d->ids.contains(d.getId())).collect(Collectors.toList());
                    if(ids.size()!=admittedIds.size()||canonicalCurrent.size()!=ids.size())return true;
                }
                try{return !recorded.getCanonicalCorpusIdentitySha256().equals(compactIdentity||display&&compact
                        ?oer.currentDisplayIdentity(run.getProjectId(),"vetting",canonicalCurrent)
                        :oer.currentIdentity(run.getProjectId(),"vetting",canonicalCurrent));}
                catch(RuntimeException stale){return true;}
            }return false;
        }
        // Legacy runs have no content hash. At least reject replacement/new document identities.
        Set<String> recordedIds=recorded==null||recorded.getDocuments()==null?Collections.emptySet():recorded.getDocuments().stream()
                .map(DocumentCoverageVO::getDocumentId).collect(Collectors.toSet());
        return !recordedIds.equals(current.stream().map(d->String.valueOf(d.getId())).collect(Collectors.toSet()));
    }
    private void assertRunSourcesCurrent(VettingRun run,List<SourceDocument> captured){List<SourceDocument> stamps=currentSourceStamps(run.getProjectId(),captured);
        boolean stamped=stamps.stream().allMatch(com.consense.service.sources.SourceContentDigests::stamped);
        List<SourceDocument> current=stamped?stamps:freshSourceSet(run.getProjectId());
        if(!sourceSnapshotMatches(run.getSourceSnapshotSha256(),stamped?captured:current)||!compactSources(captured)&&!originalSourcesCurrent(current))
            throw new BizException(4104,"审查来源或原件字节已变化，不能保存旧来源包；已保存结果仍保留。");
        if(stamped)requireCurrentSourceStamps(captured,current);else VettingSourceMaterial.requireCurrentDocuments(captured,current);
        CoverageVO recorded=JsonUtils.read(run.getCoverageJson(),CoverageVO.class);
        assertPackageIdentityCurrent(run.getProjectId(),recorded==null?null:recorded.getPackageIdentityReview(),run.getSourceSnapshotSha256());}
    private void assertMaterialSourcesCurrent(String projectId,List<SourceDocument> captured){assertMaterialSourcesCurrent(projectId,captured,true);}
    private void assertMaterialSourcesCurrent(String projectId,List<SourceDocument> captured,boolean verifyOriginal){List<SourceDocument> stamps=currentSourceStamps(projectId,captured);
        boolean stamped=stamps.stream().allMatch(com.consense.service.sources.SourceContentDigests::stamped);
        List<SourceDocument> current=stamped?stamps:freshSourceSet(projectId);
        if(verifyOriginal&&!originalSourcesCurrent(current))throw new BizException(4104,"审查原件字节已变化，不能提交或保存旧来源材料。");
        if(stamped)requireCurrentSourceStamps(captured,current);else VettingSourceMaterial.requireCurrentDocuments(captured,current);}
    private void requireCurrentSourceStamps(List<SourceDocument> captured,List<SourceDocument> current){
        Map<Long,SourceDocument> byId=new HashMap<>();for(SourceDocument source:current)if(byId.put(source.getId(),source)!=null)throw new BizException(4104,"Duplicate source revision");
        if(captured.size()!=byId.size())throw new BizException(4104,"Review source selection changed");
        for(SourceDocument before:captured){SourceDocument after=byId.remove(before.getId());
            if(after==null)throw new BizException(4104,"Review source selection changed");
            if(compactSources(Collections.singletonList(before))){
                if(!ProjectDocumentScope.sameScopeRevision(before,after)||!SourceContentDigests.stamped(after)
                        ||!Objects.equals(before.getBusinessContentSha256(),after.getBusinessContentSha256()))throw new BizException(4104,"Review source content or authorization changed");
            }else com.consense.service.sources.SourceContentDigests.requireCurrent(before,after);}
    }
    private boolean originalSourcesCurrent(List<SourceDocument> documents){
        return documents.stream().allMatch(document->JsonUtils.isBlankText(document.getSourceSha256())
                ||storageService.matchesStoredBytes(document.getStoragePath(),document.getSourceSha256()));
    }
    private void requireOriginalFreshness(List<SourceDocument> captured,Map<Long,OriginalFileState> pins){
        if(pins.isEmpty())return;
        for(SourceDocument source:captured)currentSources.requireOriginalFreshness(source,pins.get(source.getId()));
    }
    private void assertFixedReferencesCurrent(VettingRun run,List<FindingVO> findings){
        List<com.fasterxml.jackson.databind.JsonNode> references=new ArrayList<>();
        if(checkpoints!=null)for(ReliablePacketVO saved:checkpoints.reliable(run.getId()))references.addAll(saved.getPacket().getFixedBaselineEvidence());
        for(FindingVO finding:findings)for(FindingEvidence evidence:finding.getEvidence())if(evidence.getFixedBaselineEvidence()!=null)references.add(evidence.getFixedBaselineEvidence());
        Set<String> verified=new HashSet<>();for(com.fasterxml.jackson.databind.JsonNode saved:references){
            String identity=JsonUtils.write(saved);if(!verified.add(identity))continue;
            try{if(fixedBaseline==null)throw new IllegalStateException("fixed reference backend unavailable");
                com.fasterxml.jackson.databind.JsonNode current=fixedBaseline.evidence(fixedBaseline.readOnlyHitById(saved.path("parentId").asText())).pointer();
                for(String key:Arrays.asList("evidenceBackend","parentId","manifestSha256","baselineVersion","sourceFamily","sourceRole","originalSourceSha256","originalPageNos","pageNo"))
                    if(!Objects.equals(saved.get(key),current.get(key)))throw new IllegalStateException("fixed reference identity changed");
            }catch(RuntimeException stale){
                log.warn("Fixed reference export verification failed for run {} and parent {}",run.getId(),saved.path("parentId").asText(),stale);
                throw new BizException(4104,"固定开发基线证据已变化或不可用，不能导出旧引用作为当前报告；快照仍保留。",null,stale);
            }
        }
    }
    private List<VettingFinding> activeFindings(String id) { return findingRepository.findByProjectIdOrderByCodeAsc(id).stream().filter(f -> !Boolean.FALSE.equals(f.getActive())).collect(Collectors.toList()); }
    private FindingVO currentAnnotatedFinding(VettingFinding finding) {
        return annotateSourceCandidateContainers(activeFindings(finding.getProjectId()).stream().map(this::toVO).collect(Collectors.toList())).stream()
                .filter(row->Objects.equals(finding.getCode(),row.getCode())).findFirst().orElseGet(()->toVO(finding));
    }
    /** Exact shared source observations organize candidates; they never establish issue equivalence. */
    static List<FindingVO> annotateSourceCandidateContainers(List<FindingVO> rows) {
        Map<String,List<FindingVO>> groups=new LinkedHashMap<>();
        for(FindingVO row:rows)if(row!=null) {
            row.setSourceCandidateContainer(null);String identity=sourceCandidateIdentity(row);
            if(identity!=null)groups.computeIfAbsent(identity,key->new ArrayList<>()).add(row);
        }
        for(Map.Entry<String,List<FindingVO>> entry:groups.entrySet()) {
            List<String> codes=entry.getValue().stream().map(FindingVO::getCode).collect(Collectors.toList());
            SourceCandidateContainerVO container=new SourceCandidateContainerVO("source-candidates:"+VettingCorpus.hash("exact-bound-evidence-candidates-v1\n"+entry.getKey()),
                    "exact_bound_evidence_candidates","unresolved_issue_equivalence",codes,codes.size(),null);
            for(FindingVO row:entry.getValue())row.setSourceCandidateContainer(container);
        }
        return rows;
    }
    private static String sourceCandidateIdentity(FindingVO row) {
        if(JsonUtils.isBlankText(row.getCode())||JsonUtils.isBlankText(row.getRunId())||!nvl(row.getSourceSnapshotSha256()).matches("[a-f0-9]{64}")||row.getEvidence()==null||row.getEvidence().isEmpty())return null;
        List<String> sources=new ArrayList<>();
        for(FindingEvidence side:row.getEvidence()) {
            if(side==null||!side.isLocated()||JsonUtils.isBlankText(side.getDocumentId())||JsonUtils.isBlankText(side.getAnchor())||JsonUtils.isBlankText(side.getQuote())
                    ||!nvl(side.getSourceHash()).matches("[a-f0-9]{64}")||!nvl(side.getOriginalSourceSha256()).matches("[a-f0-9]{64}"))return null;
            com.fasterxml.jackson.databind.node.ObjectNode source=JsonUtils.mapper().valueToTree(side);
            JsonNode canonical=source.path("canonicalEvidence");
            if(!canonical.isMissingNode()&&!canonical.isNull()&&(!canonical.isObject()
                    ||!Objects.equals(side.getDocumentId(),canonical.path("documentId").asText())
                    ||!Objects.equals(side.getSourceHash(),canonical.path("javaParsedSourceHash").asText())
                    ||!Objects.equals(side.getOriginalSourceSha256(),canonical.path("originalSourceSha256").asText())
                    ||!canonical.path("sourceRevisionSha256").asText().matches("[a-f0-9]{64}")))return null;
            source.remove(Arrays.asList("packetId","packetSourceSnapshotSha256"));
            sources.add(stableSourceJson(source).toString());
        }
        Collections.sort(sources);return JsonUtils.write(Arrays.asList(row.getRunId(),row.getSourceSnapshotSha256(),sources));
    }
    private static JsonNode stableSourceJson(JsonNode node) {
        if(node.isObject()) {
            Map<String,JsonNode> fields=new TreeMap<>();node.fields().forEachRemaining(entry->fields.put(entry.getKey(),entry.getValue()));
            com.fasterxml.jackson.databind.node.ObjectNode result=JsonUtils.mapper().createObjectNode();fields.forEach((key,value)->result.set(key,stableSourceJson(value)));return result;
        }
        if(node.isArray()) {com.fasterxml.jackson.databind.node.ArrayNode result=JsonUtils.mapper().createArrayNode();for(JsonNode item:node)result.add(stableSourceJson(item));return result;}
        return node.deepCopy();
    }
    private List<FindingEvidence> readEvidence(VettingFinding f) { try{return JsonUtils.readList(f.getEvidenceJson(),FindingEvidence.class);}catch(Exception e){log.warn("发现 {} 证据快照不可读",f.getCode());return Collections.emptyList();} }
    private FindingVO toVO(VettingFinding f) {
        String source=f.getRunId()==null?null:runRepository.findProgress(f.getProjectId(),f.getRunId()).map(VettingRunRepository.ProgressView::getSourceSnapshotSha256).orElse(null);
        return toVO(f,source);
    }
    private FindingVO toVO(VettingFinding f,String sourceSnapshot) {
        List<FindingEvidence> e=readEvidence(f); long located=e.stream().filter(FindingEvidence::isLocated).count(); String v=e.isEmpty()||located==0?"unverified":located==e.size()?"verified":"partial";
        return new FindingVO(f.getCode(),JsonUtils.isBlankText(f.getTypes())?Collections.emptyList():Arrays.asList(f.getTypes().split(",")),f.getGroupKey(),f.getScope(),f.getSeverity(),f.getStatus(),LocalizedText.of(f.getTitleZhHans(),f.getTitleZhHant(),f.getTitleEn()),LocalizedText.of(f.getBodyZhHans(),f.getBodyZhHant(),f.getBodyEn()),LocalizedText.of(f.getImpactZhHans(),f.getImpactZhHant(),f.getImpactEn()),LocalizedText.of(f.getSuggestionZhHans(),f.getSuggestionZhHant(),f.getSuggestionEn()),f.getPatternText(),f.getRefs(),f.getLocation(),f.getExpected(),"verified".equals(v)?f.getEvidenceId():EVIDENCE_UNVERIFIED,f.getFileKey(),f.getPageNo(),f.getBucketKey(),f.getFingerprint(),f.getRunId(),f.getSource(),v,e,f.getReviewRemarks(),f.getActionTaken(),f.getAddendumRequired(),f.getReviewUpdatedAt(),generatedRevision(f),sourceSnapshot,JsonUtils.isBlankText(f.getBusinessDetailsJson())?null:JsonUtils.read(f.getBusinessDetailsJson(),BusinessDetailsVO.class));
    }
    private boolean isLatestRun(String project,String id){return sourceDigestPreparation==null
            ?runRepository.findFirstByProjectIdOrderByStartedAtDesc(project).map(run->id.equals(run.getId())).orElse(false)
            :runRepository.findLatestProgress(project,org.springframework.data.domain.PageRequest.of(0,1)).stream().findFirst().map(run->id.equals(run.getId())).orElse(false);}
    private MetricsVO metricsOf(List<VettingFinding> f){return new MetricsVO(count(f,"reference"),count(f,"conflict"),count(f,"language"),count(f,"risk"),count(f,"gap"),count(f,"substantive"),count(f,"requirement"),f.size(),(int)f.stream().filter(v -> "inter".equals(v.getScope())).count());}
    private int count(List<VettingFinding> f,String group){return (int)f.stream().filter(v -> group.equals(v.getGroupKey())).count();}
    private String searchable(VettingFinding f){return Stream.of(f.getCode(),f.getTitleZhHans(),f.getTitleEn(),f.getBodyZhHans(),f.getBodyEn(),f.getRefs(),f.getLocation(),f.getPatternText(),f.getEvidenceJson()).filter(Objects::nonNull).collect(Collectors.joining(" "));}
    private boolean filter(String v,String a){return JsonUtils.isBlankText(v)||"all".equalsIgnoreCase(v)||v.equalsIgnoreCase(nvl(a));}
    private Object lock(String id){return locks.computeIfAbsent(id,k -> new Object());}
    private TransactionTemplate tx(){return new TransactionTemplate(transactionManager);}
    private VettingRun ownedRun(String id){return checkpoints==null?runRepository.findById(id).orElseThrow(()->new BizException(4102,"审查任务已移除")):checkpoints.owned(id,executionOwner);}
    private List<SourceDocument> freshSourceSet(String projectId){TransactionTemplate read=tx();
        read.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);read.setReadOnly(true);
        return read.execute(status->sourceSet(projectId));}
    private List<SourceDocument> currentSourceStamps(String projectId,List<SourceDocument> captured){
        List<SourceDocument> stamps=freshSourceStamps(projectId);
        if(sourceDigestPreparation==null||stamps.stream().allMatch(SourceContentDigests::stamped))return stamps;
        Map<Long,SourceDocument> byId=new HashMap<>();for(SourceDocument row:stamps)byId.put(row.getId(),row);
        for(SourceDocument source:captured){SourceDocument current=byId.get(source.getId());
            if(current!=null&&!SourceContentDigests.stamped(current))sourceDigestPreparation.prepareSourceStamp(source,"vetting");}
        return freshSourceStamps(projectId);
    }
    private List<SourceDocument> freshSourceStamps(String projectId){TransactionTemplate read=tx();
        read.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);read.setReadOnly(true);
        return read.execute(status->{List<SourceDocument> rows=sourceDocumentRepository.findScopeSourcesByProjectId(projectId);
            for(SourceDocument d:rows)if(JsonUtils.isBlankText(d.getFileKey()))d.setFileKey(matchFileKey(d.getFileName()));
            return com.consense.service.sources.ProjectDocumentScope.selected(projectId,rows,"fullset","vetting");});}
    private int topicCount(){return Math.min(VettingSemanticReview.maxTopics(),Math.max(0,props.getVetting().getSemanticTopics()));}
    private String normalizeLang(String lang){return "en".equalsIgnoreCase(lang)?"en":"zh-Hant".equalsIgnoreCase(lang)?"zh-Hant":"zh-Hans";}
    private String group(String type){return VettingIssueType.require(type).getGroup();}
    private String typeCode(String type){return VettingIssueType.storedType(type);}
    private int severityRank(String value){String s=normalizeSeverity(value);return "high".equals(s)?0:"medium".equals(s)?1:2;}
    private String normalizeSeverity(String s){String v=nvl(s).toLowerCase(Locale.ROOT);return Arrays.asList("high","medium","low").contains(v)?v:"medium";}
    private String fileKey(SourceDocument d){return JsonUtils.isBlankText(d.getFileKey())?matchFileKey(d.getFileName()):d.getFileKey();}
    static String matchFileKey(String filename) {
        String n=nvl(filename).toUpperCase(Locale.ROOT).replace('_',' '); Matcher ap=Pattern.compile("\\b(AP[A-Z])\\b").matcher(n); if(ap.find())return ap.group(1);
        if(n.contains("BILL")&&n.contains("QUANTIT")||n.matches(".*\\b(?:BQ|BOQ)\\b.*"))return "BQ";
        if(n.contains("GENERAL SUMMARY"))return "GS";
        if(n.contains("APPENDIX")){Matcher a=Pattern.compile("APPENDIX\\s+([A-Z]\\d{0,3})").matcher(n);if(a.find())return "APP-"+a.group(1);}
        if(n.matches(".*\\bFOT\\b.*")||n.contains("FORM OF TENDER"))return "FT";if(n.matches(".*\\bAOA\\b.*")||n.contains("ARTICLES OF AGREEMENT"))return "AA";
        if(n.matches(".*\\bCOC\\b.*")||n.contains("GENERAL CONDITIONS OF CONTRACT"))return "GCC";if(n.contains("GENERAL CONDITIONS OF TENDER"))return "GCT";
        if(n.contains("SPECIFICATION LIBRARY"))return "SL";if(n.contains("SPECIAL CONDITIONS OF CONTRACT"))return "SCC";if(n.contains("SPECIAL CONDITIONS OF TENDER")||n.contains("SPECIAL CONDITION OF TENDER"))return "SCT";if(n.contains("NOTES TO TENDERER"))return "NTT";
        for(String key:FILE_ORDER)if(Pattern.compile("\\b"+key+"\\b").matcher(n).find())return key;if(n.contains("PRELIMINAR"))return "PRE";if(n.contains("SPECIFICATION"))return "SP";return "OTHER";
    }
    private static String truncate(String v,int max){return v==null||v.length()<=max?v:v.substring(0,max);}
    private static String available(String v,String fallback){return JsonUtils.isBlankText(v)?fallback:v;}
    private static String nvl(String v){return v==null?"":v;}
    private static String brief(Throwable e){return e==null?"Unknown error":truncate(available(e.getMessage(),e.getClass().getSimpleName()),2048);}
}
