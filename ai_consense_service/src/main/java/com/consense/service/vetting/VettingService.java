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
import com.fasterxml.jackson.databind.JsonNode;
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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
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
    private VettingCheckpointService checkpoints;
    private com.consense.repository.VettingReportSnapshotRepository reportSnapshots;
    private final String executionOwner=UUID.randomUUID().toString();
    @org.springframework.beans.factory.annotation.Autowired
    public void setCheckpoints(VettingCheckpointService service){this.checkpoints=service;}
    @org.springframework.beans.factory.annotation.Autowired
    public void setReportSnapshots(com.consense.repository.VettingReportSnapshotRepository repository){this.reportSnapshots=repository;}
    private FixedBaselineRetrievalClient fixedBaseline;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setFixedBaselineRetrievalClient(FixedBaselineRetrievalClient client){this.fixedBaseline=client;}
    private OerBusinessEvidence oer;
    @org.springframework.beans.factory.annotation.Autowired
    public void setOerBusinessEvidence(OerBusinessEvidence service){this.oer=service;}
    private com.consense.service.sources.CanonicalEvidenceService evidencePreparation;
    @org.springframework.beans.factory.annotation.Autowired
    public void setEvidencePreparation(com.consense.service.sources.CanonicalEvidenceService service){this.evidencePreparation=service;}
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
            List<SourceDocument> snapshot=freshSourceSet(saved.getProjectId());
            String supplement=semantic.configuredSupplement();
            if(chat==null||!recoveryContract(supplement,selected).equals(saved.getRecoveryContractSha256())||!Objects.equals(saved.getSourceSnapshotSha256(),sourceSetHash(snapshot))
                    ||!Objects.equals(saved.getModelIdentityJson(),JsonUtils.write(selected==null?chat.getIdentity():selected.getIdentity()))||sourceSetStale(saved))
                throw new BizException(4101,"来源、模型或审查配方与检查点不兼容，需要重新运行；可靠包和人工记录已保留。");
            tx().execute(status->{VettingRun run=checkpoints.owned(saved.getId(),executionOwner);run.setRecoveryStatus("RESUMING");run.setPhase("recovering");run.setMessage("恢复已核验的来源包；结果不明的包将显式重试。");runRepository.saveAndFlush(run);return null;});
            completions.put(saved.getId(),done);if(probe.enabled())serviceProbes.put(saved.getId(),probe);
            dispatch(saved,snapshot,chat,selected,done,probe,supplement);
        }catch(Exception e){failRun(saved.getId(),brief(e),done,probe);}
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
        worker.execute(()->{try(com.consense.ai.LlmProfiles.Scope ignored=llmProfiles.bind(chat);VettingSemanticReview.OperationScope operation=semantic.bindOperation(selected);
                VettingSemanticReview.SupplementScope supplemental=semantic.bindSupplement(supplement);
                 VettingSemanticReview.CheckpointScope durable=semantic.bindCheckpoint(checkpoints==null?null:checkpoints.session(run.getId(),executionOwner,()->assertRunSourcesCurrent(run,snapshot)));
                 com.consense.service.llm.QwenChatUsagePreflight.Operation.ActionScope usage=selected!=null&&selected.isQwen()?selected.getQwen().openAction("vetting:"+run.getId(),()->assertRunSourcesCurrent(run,snapshot)):null){executeRun(run.getId(),run.getProjectId(),run.getLang(),snapshot,done,probe);}
            catch(RuntimeException failure){probe.originalFailure("worker_action_initialization_failed",failure);try{failRun(run.getId(),brief(failure),done,probe);}finally{probe.finish("action_initialization_failed",failure);serviceProbes.remove(run.getId(),probe);}}});
    }
    @PreDestroy public void stopWorker() {
        if(checkpoints!=null)for(String id:completions.keySet())checkpoints.release(id,executionOwner);
        worker.shutdownNow();
        for(VettingServiceProbe probe:serviceProbes.values())probe.observe("worker_shutdown_requested",()->VettingServiceProbe.map("boundary","Original shutdownNow returned; this event does not assert an interrupted run transaction was committed"));
    }

    public List<VettingFileVO> listFiles(String projectId) {
        projectService.require(projectId); List<VettingFileVO> out = new ArrayList<>();
        List<SourceDocument> displayed=fileDisplaySourceSet(projectId);
        boolean active=displayed.stream().anyMatch(d->roleConfirmationEligible(d)||identityConfirmationEligible(d))
                && runRepository.existsByProjectIdAndStatusIn(projectId,ACTIVE_STATES);
        for(SourceDocument d:displayed) {
            VettingFileVO row=fileVO(d);
            if(validRoleHash(d.getSourceSha256()) && (roleConfirmationEligible(d)||identityConfirmationEligible(d)
                    || Arrays.asList("PARSING","FAILED").contains(d.getParseStatus()))) {
                SourceDocument bound=roleSourceSnapshot(d.getId());
                if(Objects.equals(d.getProjectId(),bound.getProjectId()) && Objects.equals(d.getFileName(),bound.getFileName()) && Objects.equals(d.getTextContent(),bound.getTextContent())
                        && com.consense.service.sources.CanonicalEvidenceService.routingHash(d).equals(com.consense.service.sources.CanonicalEvidenceService.routingHash(bound))
                        && Objects.equals(d.getParseStatus(),bound.getParseStatus())) {
                    row.setOriginalSourceSha256(bound.getSourceSha256());row.setSourceHash(VettingCorpus.sourceHash(bound));
                    row.setRoutingSha256(com.consense.service.sources.CanonicalEvidenceService.routingHash(bound));
                    row.setRoleConfirmationAvailable(!active&&roleConfirmationEligible(bound));
                    row.setIdentityConfirmationAvailable(!active&&identityConfirmationEligible(bound));
                }
            }
            out.add(row);
        } return out;
    }
    private VettingFileVO fileVO(SourceDocument d) {
        List<String> notes=parseWarnings(d);
        String exclusion=ProjectDocumentScope.exclusionReason(d,"fullset");
        if(exclusion!=null)notes.add("source_identity_unresolved".equals(exclusion)?"身份待确认，原文已保留；确认文件用途后可加入源集。":"该文件被来源身份或显式范围规则排除。");
        VettingFileVO row=new VettingFileVO(fileKey(d),roleLabel(VettingCorpus.sourceRole(d)),d.getFileName(),nvl(d.getParseMessage()),
                d.getParseStatus(),false,reviewable(d),d.getPageCount()==null?0:d.getPageCount(),d.getParseStatus(),d.getParseMessage(),nvl(d.getTextContent()).length(),Boolean.TRUE.equals(d.getOcrUsed()),notes,VettingCorpus.sourceRole(d),d.getDocumentScope(),exclusion);
        row.setDocumentId(d.getId()==null?null:String.valueOf(d.getId()));return row;
    }
    private boolean roleConfirmationEligible(SourceDocument d) {
        return ProjectDocumentScope.reviewCategory(d) && ProjectDocumentScope.parseUsable(d)
                && nvl(d.getTextContent()).codePoints().anyMatch(c->!Character.isWhitespace(c)&&!Character.isSpaceChar(c))
                && JsonUtils.isBlankText(d.getReviewRole()) && "unknown".equals(VettingCorpus.sourceRole(d))
                && "source_identity_unresolved".equals(ProjectDocumentScope.exclusionReason(d,"fullset","vetting"));
    }
    private boolean identityConfirmationEligible(SourceDocument d) {
        if(!ProjectDocumentScope.reviewCategory(d)||!"IDENTITY_PENDING".equals(d.getParseStatus())
                ||d.getTextContent()==null||!JsonUtils.isBlankText(d.getReviewRole())&&!Arrays.asList("tender","standard","project_fact","package_manifest").contains(d.getReviewRole())
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
        if(request==null || !Arrays.asList("tender","standard","project_fact","package_manifest").contains(request.getSourceRole())
                || !validRoleHash(request.getExpectedOriginalSourceSha256()) || !validRoleHash(request.getExpectedSourceHash()) || !validRoleHash(request.getExpectedRoutingSha256()))
            throw new BizException(4101,"请选择有效文件用途，并刷新来源版本后确认。");
        if(request.isConfirmTextContractMaterial())return resumeIdentityPending(projectId,documentId,request);
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
            VettingFileVO row=fileDisplaySourceSet(projectId).stream().filter(d->Objects.equals(d.getId(),documentId)).findFirst().map(this::fileVO)
                    .orElseThrow(()->new BizException(4104,"来源文件已变化，本次用途确认未保存。"));
            row.setOriginalSourceSha256(after.getSourceSha256());row.setSourceHash(VettingCorpus.sourceHash(after));
            row.setRoutingSha256(com.consense.service.sources.CanonicalEvidenceService.routingHash(after));return row;
        }); }
    }

    /** A human confirms the original is textual material; the usual parser still decides usable coverage. */
    private VettingFileVO resumeIdentityPending(String projectId,Long documentId,SourceRoleConfirmationRequest request) {
        SourceDocument claimed;byte[] sourceBytes;
        synchronized(lock(projectId)) {
            SourceDocument before=roleSourceSnapshot(documentId);
            if(!Objects.equals(projectId,before.getProjectId())||!identityConfirmationEligible(before))
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
        String parseJobId=DocumentParseProbe.configured(props)?"parse-"+claimed.getSourceSha256().substring(0,12)+"-"+UUID.randomUUID():null;
        try {
            DocumentParser.ParsedDocument parsed=parseJobId==null?documentParser.parse(claimed.getFileName(),sourceBytes)
                    :documentParser.parse(claimed.getFileName(),sourceBytes,parseJobId,String.valueOf(documentId),claimed.getSourceSha256());
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
        source.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));source.setParseCoverageJson(JsonUtils.write(parsed.getCoverage()));
        OerSourceMetadata.capture(source,parsed,original);if(confirmedText)OerSourceMetadata.confirmSourceUse(source,true);NormalSourceCandidateGraph.capture(source);
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
                DocumentParser.ParsedDocument observation=documentParser.observe(stored.getOriginalName(),sourceBytes);
                d.setParseStatus(observation.getParseStatus());d.setParseMessage(truncate(observation.getMessage(),1024));
                d.setPageCount(observation.getPageCount());d.setOcrUsed(observation.isOcrUsed());d.setTextContent(observation.getText());
                d.setStructuredContentJson(JsonUtils.write(observation.getBlocks()));d.setParseCoverageJson(JsonUtils.write(observation.getCoverage()));
                boolean readable=com.consense.service.sources.OerSourceMetadata.captureObservation(d,observation,sourceBytes);
                String exclusion=com.consense.service.sources.ProjectDocumentScope.exclusionReason(d,"fullset","vetting");
                if(!readable||exclusion!=null&&!"source_identity_unresolved".equals(exclusion)) {
                    d.setParseStatus(!readable?"IDENTITY_PENDING":"EXCLUDED");
                    d.setParseMessage("IDENTITY_PENDING".equals(d.getParseStatus())?"身份待确认，已保留原件和初步观察；确认前不进行详细解析。":"文件不在本次审查解析范围内："+exclusion);
                    sourceDocumentRepository.saveAndFlush(d);messages.add(d.getFileName()+" · "+d.getParseStatus()+" · "+d.getParseMessage());continue;
                }
                d.setParseStatus("PARSING");d.setParseMessage("文件识别观察已保存，正在进行详细解析；最终结果尚未就绪。");
                sourceDocumentRepository.saveAndFlush(d);
                DocumentParser.ParsedDocument p;
                if(DocumentParseProbe.configured(props)) {
                    rawSha256=DocumentParseProbe.sha256(sourceBytes);
                    parseJobId="parse-"+rawSha256.substring(0,12)+"-"+UUID.randomUUID();
                    p=documentParser.parse(stored.getOriginalName(),sourceBytes,parseJobId,String.valueOf(d.getId()),rawSha256,observation,com.consense.service.sources.OerSourceMetadata.identity(d));
                }else p=documentParser.parse(stored.getOriginalName(),sourceBytes,observation,com.consense.service.sources.OerSourceMetadata.identity(d));
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
            VettingRun latest=runRepository.findFirstByProjectIdOrderByStartedAtDesc(projectId).orElse(null);
            if(latest!=null && ACTIVE_STATES.contains(latest.getStatus())) return jobVO(latest);
            if(sourceDocumentRepository.existsCurrentParsingByProjectId(projectId))throw new BizException(4104,"仍有文件正在解析，请待完成后再开始审查。");
            com.consense.ai.LlmOperation operation=llmProfiles.capture();
            VettingLlmOperation vettingOperation=semantic.captureOperation(operation);
            String supplement=semantic.configuredSupplement();
            List<SourceDocument> snapshot=sourceSet(projectId);
            if(!originalSourcesCurrent(snapshot))throw new BizException(4104,"审查原件字节与已保存版本不一致，请重新上传和解析后审查。");
            if(snapshot.isEmpty()||(!canonicalEnabled()&&snapshot.stream().noneMatch(this::reviewable))) throw new BizException(4101,"审查源集没有可用正文，请上传并检查解析状态");
            VettingRun run=new VettingRun(); run.setId(UUID.randomUUID().toString()); run.setProjectId(projectId); run.setLang(normalizeLang(lang)); run.setStartedAt(Instant.now());
            run.setSourceSnapshotSha256(sourceSetHash(snapshot));
            run.setRecoveryContractSha256(recoveryContract(supplement,vettingOperation));run.setModelProfileExplicit(operation.isExplicit());run.setRecoveryStatus("NEW");
            if(checkpoints!=null){run.setExecutionOwner(executionOwner);run.setLeaseUntil(Instant.now().plusSeconds(VettingCheckpointService.LEASE_SECONDS));}
            run.setModelIdentityJson(JsonUtils.write(vettingOperation==null?operation.getIdentity():vettingOperation.getIdentity()));
            run.setStatus("QUEUED"); run.setPhase("queued"); run.setTotalUnits(topicCount()+2); run.setMessage("审查已排队，人工处置按问题指纹保留。");
            CoverageVO queuedCoverage=coverage(snapshot,Collections.emptyList(),Collections.emptySet(),Collections.emptyList(),false);
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

    private void executeRun(String id,String projectId,String lang,List<SourceDocument> snapshot,CompletableFuture<RunResultVO> done,VettingServiceProbe probe) {
        Throwable originalFailure=null;String outcome="aborted",stage="rules_progress";long stageStarted=System.nanoTime(),stageOverhead=probe.overheadNanos();
        long runStarted=stageStarted,runOverhead=stageOverhead;
        try {
            VettingRun capturedRun=runRepository.findById(id).orElseThrow(()->new BizException(4102,"Review run no longer exists"));
            CoverageVO capturedCoverage=JsonUtils.read(capturedRun.getCoverageJson(),CoverageVO.class);
            final JsonNode frozenIdentity=capturedCoverage==null?null:capturedCoverage.getPackageIdentityReview();
            if(frozenIdentity==null)throw new BizException(4104,"This run has no frozen package identity contract; rerun instead of upgrading historical results.");
            Runnable sourceGuard=()->{assertMaterialSourcesCurrent(projectId,snapshot);assertPackageIdentityCurrent(projectId,frozenIdentity,capturedRun.getSourceSnapshotSha256());};
            sourceGuard.run();
            if(canonicalEnabled()) {
                stage="source_preparation";progress(id,0,"preparing","准备当前来源的完整原始观察；不代替原件质量确认。");
                for(SourceDocument document:snapshot)evidencePreparation.prepareNormalObserved(projectId,document.getId(),"vetting");
                assertMaterialSourcesCurrent(projectId,snapshot);
            }
            progress(id,0,"rules","对可解析的招标合同条款执行规则检查。");
            stage="rule_sources_construction";stageStarted=System.nanoTime();stageOverhead=probe.overheadNanos();
            final OerBusinessEvidence.Session canonicalSession=canonicalEnabled()?oer.load(projectId,"vetting",snapshot):null;
            List<SourceDocument> docs=canonicalSession!=null?snapshot:snapshot.stream().filter(this::reviewable).collect(Collectors.toList());
            List<VettingRuleEngine.Source> sources=canonicalSession!=null?oer.ruleSources(canonicalSession,snapshot):docs.stream().filter(this::contractSource).map(d -> new VettingRuleEngine.Source(String.valueOf(d.getId()),fileKey(d),d.getFileName(),VettingCorpus.sourceHash(d),VettingCorpus.blocks(d),d.getTextContent())).collect(Collectors.toList());
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
            if(canonicalSession!=null)for(Candidate c:candidates)oer.bindRuleEvidence(canonicalSession,c.getEvidence());
            bindRuleOriginalSources(candidates,snapshot);
            for(Candidate candidate:candidates)bindPackageIdentityEvidence(candidate,completedIdentity);
            progress(id,1,"semantic","规则检查完成，开始按主题检索语义审查片段。");
            stage="corpus_and_semantic_review";stageStarted=System.nanoTime();stageOverhead=probe.overheadNanos();
            List<Chunk> chunks=canonicalSession!=null?canonicalSession.chunks():VettingCorpusConstructionProbe.chunks(props,id,projectId,docs);
            VettingSemanticReview.Result semanticResult=JsonUtils.isBlankText(props.getVetting().getProbeDirectory())
                    ? semantic.reviewWithProgress(null,projectId,chunks,"en",(count,total,message) -> progress(id,count+1,total+2,"semantic",message),docs,sourceGuard)
                    : semantic.reviewWithProgress(id,projectId,chunks,"en",(count,total,message) -> progress(id,count+1,total+2,"semantic",message),docs,sourceGuard);
            probe.elapsed(stage,stageStarted,stageOverhead,"Original corpus and SemanticReview call, inclusive of its independent probes; not pure model latency",true);
            probe.observe("semantic_result_raw_candidates",()->VettingServiceProbe.map("result",semanticResult,
                    "boundary","Actual returned candidates, warnings, reviewed IDs and topic audits before service sorting/source normalization"));
            candidates.addAll(semanticResult.getCandidates());
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
            if(canonicalSession!=null)coverage.setCanonicalCorpusIdentitySha256(canonicalSession.identitySha256());
            coverage.setSemanticTopics(semanticResult.getTopicAudits());
            coverage.setPackageIdentityReview(completedIdentity);
            coverage.setPackageIdentityMirrorAudit(capturedCoverage.getPackageIdentityMirrorAudit());
            coverage.setChecklistDependencyLedger(VettingChecklistDependencies.capture(projectId,canonicalSession,snapshot,
                    sourceDocumentRepository.findBusinessSourcesByProjectId(projectId),unique.values(),semanticResult.getTopicAudits()));
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
                    if(canonicalSession!=null)oer.assertSelected(canonicalSession,freshSourceSet(projectId));
                    sourceGuard.run();
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
            originalFailure=e;probe.elapsed(stage,stageStarted,stageOverhead,"Original Error in this stage; original Error propagates unchanged",false);probe.originalFailure("service_run_failure",e);throw e;
        } finally {
            probe.elapsed("execute_run_total",runStarted,runOverhead,"Original background execution inclusive of original stages; observer wall excluded",originalFailure==null&&"completed".equals(outcome));
            probe.finish(outcome,originalFailure);completions.remove(id,done);serviceProbes.remove(id,probe);
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
        if(!runRepository.findFirstByProjectIdOrderByStartedAtDesc(projectId).map(latest->id.equals(latest.getId())).orElse(false))throw new BizException(4101,"项目已有新的审查任务，旧执行者不能发布当前结果");
        if(sourceSetStale(run))throw new BizException(4104,"审查来源已变化，旧结果不能作为当前报告保存；可靠包仍保留。");
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
        if(reportSnapshots!=null)saveReportSnapshot(projectService.require(projectId),run,current.stream().map(this::toVO).collect(Collectors.toList()));
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
        long started=System.nanoTime(),overhead=probe.overheadNanos();
        try {
            tx().execute(status -> { runRepository.lockById(id).ifPresent(run -> { if("COMPLETED".equals(run.getStatus())||checkpoints!=null&&(!executionOwner.equals(run.getExecutionOwner())||run.getLeaseUntil()==null||!run.getLeaseUntil().isAfter(Instant.now()))) return; run.setStatus("FAILED"); run.setPhase("failed");run.setRecoveryStatus("NEEDS_RERUN");run.setExecutionOwner(null);run.setLeaseUntil(null); run.setError(truncate(message,2048)); run.setMessage(truncate(message,2048)); run.setFinishedAt(Instant.now()); runRepository.saveAndFlush(run);
                probe.observe("failed_run_pending_commit",()->VettingServiceProbe.map("run",run,"committed",false)); }); return null; });
            probe.elapsed("fail_run_transaction",started,overhead,"Original REQUIRED failure-persistence tx.execute; own commit or still-active outer participation as recorded",true);
            probe.observe("fail_run_transaction_returned",()->VettingServiceProbe.returnedTransaction("records",probe.reference("failed_run_pending_commit"),
                    "boundary","Original failure policy skips an already-completed run; missing records are not fabricated"));
        } catch(RuntimeException|Error e){probe.elapsed("fail_run_transaction",started,overhead,"Original failure persistence transaction failed",false);probe.originalFailure("fail_run_transaction_failure",e);throw e;}
        finally { done.completeExceptionally(new BizException(4101,message)); completions.remove(id,done); }
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
        List<String> warnings=new ArrayList<>(extra); List<DocumentCoverageVO> documents=new ArrayList<>(); int processed=0;
        boolean canonical=OerBusinessEvidence.canonicalCorpus(chunks);
        warnings.add(canonical?"规则与语义仅处理已采用的 canonical 来源观察；未采用区域、整份原件完整性、编辑状态及采用关系保持声明的未知。字符分母仅为已采用观察的 UTF16 范围并集，不代表原件全文覆盖。":rulesScanned?"规则扫描全部可解析招标合同条款；标准文件仅作参考对照，项目事实和文件目录仅作上下文，均未当作实际合同执行规则检查。语义覆盖仅计实际成功的模型片段，未检索片段不代表完成语义审查。":"任务尚未完成规则和语义审查，当前覆盖仅反映源文件解析状态。");
        if(props.getVetting().isDiffAgainstBaseline()) warnings.add("本次未执行标准模板差异过滤，不能解读为只审查项目改动内容。");
        for(SourceDocument d:snapshot) {
            List<String> notes=parseWarnings(d); boolean usable=canonical||reviewable(d); if(excluded(d)) notes.add("source_identity_unresolved".equals(com.consense.service.sources.ProjectDocumentScope.exclusionReason(d,"fullset"))?"身份待确认，原文已保留；确认文件用途后可加入源集。":"该文件被来源身份或显式范围规则排除。");
            List<Chunk> parts=chunks.stream().filter(c -> String.valueOf(d.getId()).equals(c.getDocumentId())).collect(Collectors.toList()); List<Chunk> checked=parts.stream().filter(c -> reviewed.contains(c.getId())).collect(Collectors.toList());
            int chars=canonical?OerBusinessEvidence.observedChars(parts):nvl(d.getTextContent()).length(),covered=canonical?OerBusinessEvidence.observedChars(checked):Math.min(chars,reviewedChars(d,checked));
            notes.add("资料类型：" + roleLabel(VettingCorpus.sourceRole(d)).getZhHans());
            if(canonical)notes.add("片段与字符统计以已采用 canonical 观察为分母；未采用区域及原件完整性未确认。");
            else if(usable) notes.add(rulesScanned?(contractSource(d)?"规则已扫描全部可解析招标合同正文。":
                    "standard".equals(VettingCorpus.sourceRole(d))?"标准文件仅作参考对照，规则未当作实际合同执行。":"项目事实或文件目录不作为有效合同条款执行规则扫描。" ):"规则和语义审查尚未完成。");
            if(usable && rulesScanned && checked.size()<parts.size()) notes.add("语义仅覆盖 "+checked.size()+"/"+parts.size()+" 片段，其他片段未进入成功的模型调用。");
            if(usable && !checked.isEmpty()) processed++;
            documents.add(new DocumentCoverageVO(String.valueOf(d.getId()),fileKey(d),d.getFileName(),d.getParseStatus(),chars,covered,parts.size(),checked.size(),notes));
        } return new CoverageVO(snapshot.size(),processed,documents,new ArrayList<>(new LinkedHashSet<>(warnings)));
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
        List<SourceDocument> documents=sourceDocumentRepository.findBusinessSourcesByProjectId(projectId);
        for(SourceDocument d:documents)if(JsonUtils.isBlankText(d.getFileKey()))d.setFileKey(matchFileKey(d.getFileName()));
        return com.consense.service.sources.ProjectDocumentScope.selected(projectId,documents,"fullset");
    }
    /** Display retains pending and excluded originals; business selection remains separate. */
    private List<SourceDocument> fileDisplaySourceSet(String projectId) {
        List<SourceDocument> documents=new ArrayList<>();
        for(SourceDocumentRepository.FileDisplayMetadata metadata:sourceDocumentRepository.findFileDisplayMetadataByProjectId(projectId)) {
            SourceDocument d=new SourceDocument();d.setId(metadata.getId());d.setProjectId(metadata.getProjectId());d.setCategory(metadata.getCategory());
            d.setFileKey(metadata.getFileKey());d.setFileName(metadata.getFileName());d.setReviewRole(metadata.getReviewRole());d.setDocumentScope(metadata.getDocumentScope());
            d.setParseStatus(metadata.getParseStatus());d.setParseMessage(metadata.getParseMessage());d.setOerIdentityJson(metadata.getOerIdentityJson());d.setSourceSha256(metadata.getSourceSha256());
            d.setPageCount(metadata.getPageCount());d.setOcrUsed(metadata.getOcrUsed());d.setTextContent(metadata.getTextContent());d.setParseCoverageJson(metadata.getParseCoverageJson());
            if(JsonUtils.isBlankText(d.getFileKey()))d.setFileKey(matchFileKey(d.getFileName()));
            documents.add(d);
        }
        return com.consense.service.sources.ProjectDocumentScope.currentCatalog(projectId,documents);
    }
    private boolean reviewable(SourceDocument d) { return ("PARSED".equals(d.getParseStatus())||"PARTIAL".equals(d.getParseStatus())) && !JsonUtils.isBlankText(d.getTextContent()) && !excluded(d); }
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
        if(!JsonUtils.isBlankText(d.getParseCoverageJson())) try { ParseCoverage c=JsonUtils.read(d.getParseCoverageJson(),ParseCoverage.class); out.addAll(c.getLimitations()); if(!c.getFailedPages().isEmpty()) out.add("未完成解析的物理页："+c.getFailedPages()); if(!c.isComplete()) out.add("文档解析不完整，未解析部分不纳入审查。"); }
        catch(Exception e) { out.add("解析覆盖记录无法读取，请重新解析。"); }
        else out.add("旧文件缺结构和覆盖记录，仅使用字符锚点，不推测Word页码。");
        if(Boolean.TRUE.equals(d.getOcrUsed())) out.add("OCR 不识别删除线、表格行列关系及图形含义；引文、删除状态和表格对应关系须对照原始物理页复核。");
        if(!"PARSED".equals(d.getParseStatus())&&!"PARTIAL".equals(d.getParseStatus())) out.add("没有可用的已解析正文："+nvl(d.getParseMessage())); return out;
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
        if(!"COMPLETED".equals(run.getStatus())||!runRepository.findFirstByProjectIdOrderByStartedAtDesc(run.getProjectId()).map(latest->run.getId().equals(latest.getId())).orElse(false)||sourceSetStale(run))return false;
        try{assertFixedReferencesCurrent(run,activeFindings(run.getProjectId()).stream().filter(f->run.getId().equals(f.getRunId())).map(this::toVO).collect(Collectors.toList()));return true;}
        catch(BizException stale){if(stale.getCode()!=4104)throw stale;return false;}
    }
    public EvidenceVO evidence(String projectId,String code) {
        projectService.require(projectId); VettingFinding f=findingRepository.findByProjectIdAndCode(projectId,code).orElseThrow(() -> new BizException(4102,"审查发现不存在"));
        List<FindingEvidence> saved=readEvidence(f); List<EvidenceItemVO> items=new ArrayList<>();
        for(FindingEvidence e:saved) {
            EvidenceItemVO item=new EvidenceItemVO(nvl(e.getFileKey())+" · "+nvl(e.getFileName()),e.getPageNo(),e.getQuote(),e.getSide(),e.getDocumentId(),e.getFileKey(),e.getFileName(),e.getAnchor(),e.getQuote(),e.isLocated(),e.getSourceHash(),e.getBbox(),e.getPacketId(),e.getPacketSourceSnapshotSha256(),e.getCanonicalEvidence(),e.getFixedBaselineEvidence(),e.getOriginalSourceSha256());
            item.setSourceRole(e.getSourceRole());item.setNativePositions(e.getNativePositions());items.add(item);
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
        if(ProjectDocumentScope.exclusionReason(source,"fullset","vetting")!=null)throw new BizException(4104,"原件不属于当前审查来源范围。");
        String name=nvl(source.getFileName()).trim().toLowerCase(Locale.ROOT);
        boolean superseded=sourceDocumentRepository.findCatalogMetadataByProjectId(projectId).stream().anyMatch(d->d.getId()>documentId
                &&(SourceDocument.CATEGORY_VETTING_PACKAGE.equals(d.getCategory())||SourceDocument.CATEGORY_VETTING_SUPPLEMENT.equals(d.getCategory()))
                &&name.equals(nvl(d.getFileName()).trim().toLowerCase(Locale.ROOT)));
        if(superseded)throw new BizException(4104,"原件已被较新上传取代，请刷新审查结果。");
        return source;
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
            byte[] bytes;
            if("pdf".equals(format)||"docx".equals(format)) {
                byte[] docx=reportWriter.docx(c.project,c.findings,c.job,"en",probe.enabled()?probe:null,checklist);
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
                    out=new LinkedHashMap<>();out.put("schemaVersion","1.0");out.put("language",normalizeLang(lang));out.put("project",projectService.toVO(c.project));out.put("job",c.job);out.put("findings",c.findings);
                    out.put("reportSnapshotId",c.job.getReportSnapshotId());
                    out.put("reportStructureVersion","competition-comments-and-checklist-v1");out.put("reportLanguage","en");
                    out.put("promptPolicy",c.job.getResult()==null?null:c.job.getResult().getPromptPolicy());
                    out.put("commentsTables",VettingReportPresentation.readerComments(c.findings,c.job.getCoverage()));
                    out.put("sectionSummaries",VettingReportPresentation.sectionSummaries(c.findings,c.job.getCoverage()));
                    List<com.consense.web.dto.VettingChecklistDtos.AutomaticGroupVO> automatic=VettingChecklistAutomaticResults.aggregate(c.job.getStatus(),JsonUtils.mapper().valueToTree(c.job.getCoverage()),JsonUtils.mapper().valueToTree(c.job.getResult()));
                    out.put("automaticChecklistResults",automatic);out.put("checklistTables",VettingReportPresentation.readerChecklist(checklist,automatic));out.put("checklistResults",checklist);
                    out.put("evidenceIndex",VettingReportPresentation.readerEvidenceIndex(c.findings,c.job.getCoverage()));
                }
                try(VettingExportProbe.Timer timer=probe.measure("report_json_serialization","Original JsonUtils.write and UTF8 encoding of returned JSON report")){bytes=JsonUtils.write(out).getBytes(StandardCharsets.UTF_8);}
            }
            probe.returned(bytes);returnedNormally=true;return bytes;
        }catch(RuntimeException|Error e){operationFailure=e;probe.originalFailure("report_export_failure",e);throw e;}
        finally{probe.finish(returnedNormally?"returned_normally":"aborted",operationFailure);}
    }
    private byte[] bindPdfSnapshot(byte[] rendererPdf,String reportSnapshotId) {
        try(org.apache.pdfbox.pdmodel.PDDocument document=org.apache.pdfbox.pdmodel.PDDocument.load(new ByteArrayInputStream(rendererPdf));ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            document.getDocumentInformation().setCustomMetadataValue("ConSenseReportSnapshotId",reportSnapshotId);
            document.save(out);return out.toByteArray();
        }catch(IOException failure){throw new BizException(4100,"Vetting PDF snapshot metadata could not be bound. The Word report remains available: "+failure.getMessage());}
    }
    private ReportContext reportContext(String projectId) {
        projectService.require(projectId); synchronized(lock(projectId)) { return tx().execute(status -> {
            Project p=projectService.require(projectId); VettingRun latest=runRepository.findFirstByProjectIdOrderByStartedAtDesc(projectId).orElseThrow(() -> new BizException(4103,"尚未完成审查，无法导出报告"));
            VettingRun run=runRepository.lockById(latest.getId()).orElseThrow(()->new BizException(4103,"审查任务不存在"));
            if(!"COMPLETED".equals(run.getStatus())) throw new BizException(4103,"最近审查任务尚未成功完成，请等待或重新运行");
            if(sourceSetStale(run)) throw new BizException(4104,"审查文件包或资料版本已变化，请重新审查后导出本次报告；原审查及人工核对记录仍保留。");
            List<FindingVO> findings=activeFindings(projectId).stream().filter(f -> run.getId().equals(f.getRunId())).map(this::toVO).collect(Collectors.toList());
            assertFixedReferencesCurrent(run,findings);
            return saveReportSnapshot(p,run,findings);
        }); }
    }
    private ReportContext saveReportSnapshot(Project project,VettingRun run,List<FindingVO> findings) {
        findings=annotateSourceCandidateContainers(findings);
        VettingJobVO job=jobVO(run);job.setReliablePackets(Collections.emptyList());job.setReportSnapshotId(null);
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
        List<VettingChecklistCatalog.Result> checklist=checklistService==null?Collections.emptyList():checklistService.resultsForRun(run.getProjectId(),run.getId());
        ReportSnapshotPayload current=new ReportSnapshotPayload();current.setProject(project);current.setJob(job);current.setFindings(findings);current.setChecklist(checklist);
        String payload=JsonUtils.write(current),id=VettingCorpus.hash("vetting-report-snapshot-v1\n"+payload);
        if(reportSnapshots!=null) {
            VettingReportSnapshot saved=reportSnapshots.findById(id).orElse(null);
            if(saved==null){saved=new VettingReportSnapshot();saved.setId(id);saved.setRunId(run.getId());saved.setPayloadJson(payload);saved.setCreatedAt(Instant.now());reportSnapshots.saveAndFlush(saved);}
            if(!id.equals(VettingCorpus.hash("vetting-report-snapshot-v1\n"+saved.getPayloadJson()))||!run.getId().equals(saved.getRunId()))throw new BizException(4103,"报告快照校验失败，不能导出");
            current=JsonUtils.read(saved.getPayloadJson(),ReportSnapshotPayload.class);run.setReportSnapshotSha256(id);runRepository.saveAndFlush(run);
        }
        current.getJob().setReportSnapshotId(id);return new ReportContext(current.getProject(),current.getJob(),current.getFindings(),current.getChecklist());
    }
    public String pdfFileName(String id) { return reportFileName(id,"pdf"); }
    public String reportFileName(String id,String format) { return "ConSense_Vetting_Report_"+nvl(projectService.require(id).getContractNo()).replaceAll("[\\\\/:*?\"<>|]","_")+"."+format; }
    private static final class ReportContext { final Project project; final VettingJobVO job; final List<FindingVO> findings;final List<VettingChecklistCatalog.Result> checklist; ReportContext(Project p,VettingJobVO j,List<FindingVO> f,List<VettingChecklistCatalog.Result> c){project=p;job=j;findings=f;checklist=c;} }
    @lombok.Data @lombok.NoArgsConstructor
    public static class ReportSnapshotPayload {private Project project;private VettingJobVO job;private List<FindingVO> findings;private List<VettingChecklistCatalog.Result> checklist;}
    private VettingJobVO jobVO(VettingRun r) { VettingJobVO job=new VettingJobVO(r.getId(),r.getStatus(),r.getPhase(),r.getCompletedUnits(),r.getTotalUnits(),r.getMessage(),r.getStartedAt(),r.getFinishedAt(),JsonUtils.read(r.getResultJson(),RunResultVO.class),r.getError(),JsonUtils.read(r.getCoverageJson(),CoverageVO.class));job.setModelIdentity(JsonUtils.read(r.getModelIdentityJson(),com.consense.ai.ModelIdentity.class));job.setSourceSnapshotSha256(r.getSourceSnapshotSha256());job.setSourceSetStale(sourceSetStale(r));job.setRecoveryStatus(r.getRecoveryStatus());job.setReportSnapshotId(r.getReportSnapshotSha256());if(checkpoints!=null)job.setReliablePackets(checkpoints.reliable(r.getId()));return job; }
    private static String sourceSetHash(List<SourceDocument> documents) {
        List<String> records = documents.stream().map(d -> {List<Object> identity=new ArrayList<>(Arrays.asList(d.getId(),d.getFileName(),d.getFileKey(),
                VettingCorpus.sourceRole(d),d.getDocumentScope(),d.getParseStatus(),VettingCorpus.sourceHash(d),
                VettingCorpus.hash(nvl(d.getParseCoverageJson())),VettingCorpus.hash(nvl(d.getNormalCandidateJson()))));
            if(!JsonUtils.isBlankText(d.getSourceSha256()))identity.add(d.getSourceSha256());return JsonUtils.write(identity);})
                .sorted().collect(Collectors.toList());
        return VettingCorpus.hash("competition-review-package-v2\n"+JsonUtils.write(records));
    }
    private boolean sourceSetStale(VettingRun run) {
        List<SourceDocument> current=canonicalEnabled()?freshSourceSet(run.getProjectId()):sourceSet(run.getProjectId());
        if(!originalSourcesCurrent(current))return true;
        CoverageVO recorded=JsonUtils.read(run.getCoverageJson(),CoverageVO.class);
        if(!packageIdentityCurrent(run,recorded))return true;
        if(!JsonUtils.isBlankText(run.getSourceSnapshotSha256())){
            if(!run.getSourceSnapshotSha256().equals(sourceSetHash(current)))return true;
            if(recorded!=null&&recorded.getCanonicalCorpusIdentitySha256()!=null){
                if(!canonicalEnabled())return true;
                try{return !recorded.getCanonicalCorpusIdentitySha256().equals(oer.currentIdentity(run.getProjectId(),"vetting",current));}
                catch(RuntimeException stale){return true;}
            }return false;
        }
        // Legacy runs have no content hash. At least reject replacement/new document identities.
        Set<String> recordedIds=recorded==null||recorded.getDocuments()==null?Collections.emptySet():recorded.getDocuments().stream()
                .map(DocumentCoverageVO::getDocumentId).collect(Collectors.toSet());
        return !recordedIds.equals(current.stream().map(d->String.valueOf(d.getId())).collect(Collectors.toSet()));
    }
    private void assertRunSourcesCurrent(VettingRun run,List<SourceDocument> captured){List<SourceDocument> current=freshSourceSet(run.getProjectId());
        if(!Objects.equals(run.getSourceSnapshotSha256(),sourceSetHash(current))||!originalSourcesCurrent(current))
            throw new BizException(4104,"审查来源或原件字节已变化，不能保存旧来源包；已保存结果仍保留。");
        VettingSourceMaterial.requireCurrentDocuments(captured,current);
        CoverageVO recorded=JsonUtils.read(run.getCoverageJson(),CoverageVO.class);
        assertPackageIdentityCurrent(run.getProjectId(),recorded==null?null:recorded.getPackageIdentityReview(),run.getSourceSnapshotSha256());}
    private void assertMaterialSourcesCurrent(String projectId,List<SourceDocument> captured){List<SourceDocument> current=freshSourceSet(projectId);
        if(!originalSourcesCurrent(current))throw new BizException(4104,"审查原件字节已变化，不能提交或保存旧来源材料。");
        VettingSourceMaterial.requireCurrentDocuments(captured,current);}
    private boolean originalSourcesCurrent(List<SourceDocument> documents){
        return documents.stream().allMatch(document->JsonUtils.isBlankText(document.getSourceSha256())
                ||storageService.matchesStoredBytes(document.getStoragePath(),document.getSourceSha256()));
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
            }catch(RuntimeException stale){throw new BizException(4104,"固定开发基线证据已变化或不可用，不能导出旧引用作为当前报告；快照仍保留。");}
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
        List<FindingEvidence> e=readEvidence(f); long located=e.stream().filter(FindingEvidence::isLocated).count(); String v=e.isEmpty()||located==0?"unverified":located==e.size()?"verified":"partial";
        return new FindingVO(f.getCode(),JsonUtils.isBlankText(f.getTypes())?Collections.emptyList():Arrays.asList(f.getTypes().split(",")),f.getGroupKey(),f.getScope(),f.getSeverity(),f.getStatus(),LocalizedText.of(f.getTitleZhHans(),f.getTitleZhHant(),f.getTitleEn()),LocalizedText.of(f.getBodyZhHans(),f.getBodyZhHant(),f.getBodyEn()),LocalizedText.of(f.getImpactZhHans(),f.getImpactZhHant(),f.getImpactEn()),LocalizedText.of(f.getSuggestionZhHans(),f.getSuggestionZhHant(),f.getSuggestionEn()),f.getPatternText(),f.getRefs(),f.getLocation(),f.getExpected(),"verified".equals(v)?f.getEvidenceId():EVIDENCE_UNVERIFIED,f.getFileKey(),f.getPageNo(),f.getBucketKey(),f.getFingerprint(),f.getRunId(),f.getSource(),v,e,f.getReviewRemarks(),f.getActionTaken(),f.getAddendumRequired(),f.getReviewUpdatedAt(),generatedRevision(f),f.getRunId()==null?null:runRepository.findById(f.getRunId()).map(VettingRun::getSourceSnapshotSha256).orElse(null),JsonUtils.isBlankText(f.getBusinessDetailsJson())?null:JsonUtils.read(f.getBusinessDetailsJson(),BusinessDetailsVO.class));
    }
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
