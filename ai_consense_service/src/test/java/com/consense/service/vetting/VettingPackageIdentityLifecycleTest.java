package com.consense.service.vetting;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentBlock;
import com.consense.document.DocumentParser;
import com.consense.domain.Project;
import com.consense.repository.*;
import com.consense.service.ProjectService;
import com.consense.service.StorageService;
import com.consense.service.drafting.DraftingService;
import com.consense.service.vetting.VettingSemanticReview.Candidate;
import com.consense.web.dto.VettingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Authored pure lifecycle/serialization contracts; repositories, transactions and resolver are replacements. */
class VettingPackageIdentityLifecycleTest {
    static final String SOURCE=VettingCorpus.hash("AUTHORED source set only");
    static JsonNode authority(String number,String title,String audit) {
        ObjectNode root=JsonUtils.mapper().createObjectNode();root.put("policy",DraftingService.PackageIdentitySnapshot.POLICY);
        ObjectNode core=root.putObject("core");core.put("projectId","p");core.put("provenanceKind","explicit_manual");ObjectNode fields=core.putObject("fields");
        for(String key:Arrays.asList("number","title")){String value="number".equals(key)?number:title;ObjectNode field=fields.putObject(key);field.put("eligible",value!=null);field.put("value",value);}
        root.put("authoritySha256",VettingCorpus.hash(JsonUtils.write(core)));root.putObject("audit").put("confirmedFrom",audit);return root;
    }
    static VettingRuleEngine.Source source(String text) {
        DocumentBlock block=new DocumentBlock();block.setId("authored-block");block.setLocation("authored/source/identity");block.setText(text);
        ObjectNode context=JsonUtils.mapper().createObjectNode(),binding=context.putObject(VettingPackageIdentity.CONTEXT);
        binding.put("status","source_bound");binding.put("role","tender");binding.put("documentScope","package");binding.put("startOffset",0);binding.put("endOffset",text.length());
        binding.put("selectedStartsAtSourceLineBoundary",true);binding.put("selectedEndsAtSourceLineBoundary",true);block.setReadingContext(context);
        return new VettingRuleEngine.Source("1","NTT","AUTHORED NTT.txt",VettingCorpus.hash(text),Collections.singletonList(block),text);
    }
    static JsonNode executed(JsonNode authority,String text) {
        JsonNode frozen=VettingService.freezePackageIdentity(authority,SOURCE);
        return VettingService.completePackageIdentity(frozen,VettingPackageIdentity.review(Collections.singletonList(source(text)),authority));
    }
    static VettingJobVO job(JsonNode review) {
        CoverageVO coverage=new CoverageVO(1,1,Collections.emptyList(),Collections.emptyList());coverage.setPackageIdentityReview(review);
        VettingJobVO job=new VettingJobVO();job.setStatus("COMPLETED");job.setSourceSnapshotSha256(SOURCE);job.setCoverage(coverage);return job;
    }
    static DraftingService.PackageIdentitySnapshot resolverSnapshot(JsonNode raw)throws Exception {
        Constructor<DraftingService.PackageIdentitySnapshot> constructor=DraftingService.PackageIdentitySnapshot.class.getDeclaredConstructor(Map.class,Map.class);constructor.setAccessible(true);
        return constructor.newInstance(JsonUtils.read(JsonUtils.write(raw.path("core")),Map.class),JsonUtils.read(JsonUtils.write(raw.path("audit")),Map.class));
    }
    private void guard(JsonNode frozen,JsonNode current)throws Exception {
        PlatformTransactionManager tx=mock(PlatformTransactionManager.class);when(tx.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        DraftingService drafting=mock(DraftingService.class);when(drafting.packageIdentitySnapshot("p")).thenReturn(resolverSnapshot(current));
        VettingService service=new VettingService(mock(ProjectService.class),mock(StorageService.class),mock(DocumentParser.class),new ConsenseProperties(),mock(SourceDocumentRepository.class),mock(VettingFindingRepository.class),mock(VettingRunRepository.class),mock(VettingSemanticReview.class),new VettingReportWriter(),tx,mock(com.consense.ai.LlmProfiles.class));
        service.setDraftingIdentity(drafting);
        try {Method method=VettingService.class.getDeclaredMethod("assertPackageIdentityCurrent",String.class,JsonNode.class,String.class);method.setAccessible(true);method.invoke(service,"p",frozen,SOURCE);
            verify(tx).getTransaction(argThat(definition->definition.isReadOnly()&&definition.getPropagationBehavior()==TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        } finally {service.stopWorker();}
    }
    @Test void startFreezeIsDetachedAndUnexecutedNotAnAgreement() {
        JsonNode adopted=authority("PKG-1","Adopted title","INPUT"),frozen=VettingService.freezePackageIdentity(adopted,SOURCE);
        ((ObjectNode)adopted.path("core").path("fields").path("number")).put("value","MUTATED");
        assertEquals("PKG-1",frozen.path("authority").path("core").path("fields").path("number").path("value").asText());
        assertEquals("not_executed",frozen.path("executionStatus").asText());assertTrue(VettingService.packageIdentityReviewValid(frozen,SOURCE));
        assertTrue(VettingReportWriter.reportIdentity(job(frozen),"number").contains("not executed"));
    }
    @Test void authorityAuditMarkerChangesDoNotChangeCoreOrInvalidateGuard()throws Exception {
        JsonNode adopted=authority("PKG-1","Title","INPUT"),later=authority("PKG-1","Title","PROJECT_IDENTITY");
        assertEquals(VettingService.packageAuthorityCore(adopted),VettingService.packageAuthorityCore(later));
        guard(VettingService.freezePackageIdentity(adopted,SOURCE),later);
        JsonNode first=executed(adopted,"Contract No: PKG-2"),second=executed(later,"Contract No: PKG-2");
        assertNotEquals(first.path("ledgerSha256"),second.path("ledgerSha256"),"Audit remains preserved in each exact frozen ledger");
        assertEquals(first.path("decisionSha256"),second.path("decisionSha256"),"Audit-only changes must not churn generated finding revisions or human reuse");
    }
    @Test void changedAdoptedCoreCannotPassCheckpointOrPublicationGuard()throws Exception {
        JsonNode frozen=VettingService.freezePackageIdentity(authority("PKG-1","Title","INPUT"),SOURCE);
        InvocationTargetException error=assertThrows(InvocationTargetException.class,()->guard(frozen,authority("PKG-2","Title","INPUT")));
        assertTrue(error.getCause() instanceof BizException);assertTrue(error.getCause().getMessage().contains("Adopted package identity changed"));
    }
    @Test void sourceAndLedgerTamperingCannotBeReportedAsCurrent() {
        JsonNode frozen=VettingService.freezePackageIdentity(authority("PKG-1","Title","INPUT"),SOURCE);
        assertFalse(VettingService.packageIdentityReviewValid(frozen,VettingCorpus.hash("other sources")));
        ((ObjectNode)frozen).put("executionStatus","executed");assertFalse(VettingService.packageIdentityReviewValid(frozen,SOURCE));
        assertTrue(VettingReportWriter.reportIdentity(job(frozen),"number").contains("invalid"));
    }
    @Test void projectMirrorAuditCannotChangeAuthorityOrRewriteCapturedValues() {
        JsonNode adopted=authority("PKG-1","Title","INPUT"),frozen=VettingService.freezePackageIdentity(adopted,SOURCE);String before=JsonUtils.write(frozen);
        Project project=new Project();project.setContractNo("TEMP-1");project.setNameEn("Temporary display");JsonNode first=VettingService.packageMirrorAudit(null,project);
        project.setContractNo("DISPLAY-CHANGED");JsonNode later=VettingService.packageMirrorAudit(first.path("captured"),project);
        assertTrue(later.path("changed").asBoolean());assertEquals("TEMP-1",later.path("captured").path("contractNo").asText());assertEquals(before,JsonUtils.write(frozen));
        ((ObjectNode)later.path("captured")).put("contractNo","MUTATED");assertEquals("TEMP-1",first.path("captured").path("contractNo").asText());
    }
    @Test void matchingLabelsKeepScopeUnknownAndMissingSiblingRemainsUnavailable() {
        JsonNode review=executed(authority("PKG-1",null,"INPUT"),"Contract No: PKG-1");
        assertEquals("candidate_agreement_scope_unknown",review.path("fields").path("number").path("status").asText());
        assertEquals(0,review.path("fields").path("number").path("ownDeclarations").asInt());assertEquals("PKG-1",VettingReportWriter.reportIdentity(job(review),"number"));
        assertTrue(VettingReportWriter.reportIdentity(job(review),"title").contains("unavailable"));assertFalse(JsonUtils.write(review).contains("\"PASS\""));
    }
    @Test void externalReferenceNeverBecomesPackageDeclarationOrFinding() {
        JsonNode adopted=authority("PKG-1",null,"INPUT");VettingPackageIdentity.Review result=VettingPackageIdentity.review(Collections.singletonList(source("Other Contract No: OTHER-9")),adopted);
        assertTrue(result.outputs().isEmpty());JsonNode review=VettingService.completePackageIdentity(VettingService.freezePackageIdentity(adopted,SOURCE),result);
        assertEquals("external_reference",review.path("declarations").get(0).path("classification").asText());assertEquals(0,review.path("fields").path("number").path("candidateDeclarations").asInt());
    }
    @Test void identityEvidenceLinksOnlyExactFrozenDeclarationAndDoesNotInventBaselineQuote() {
        JsonNode adopted=authority("PKG-1",null,"INPUT");VettingPackageIdentity.Review pure=VettingPackageIdentity.review(Collections.singletonList(source("Contract No: PKG-2")),adopted);
        JsonNode review=VettingService.completePackageIdentity(VettingService.freezePackageIdentity(adopted,SOURCE),pure);VettingRuleEngine.Output output=pure.outputs().get(0);
        VettingRuleEngine.Evidence raw=output.getEvidence().get(0);FindingEvidence quote=new FindingEvidence("source",raw.getDocumentId(),raw.getFileKey(),raw.getFileName(),null,raw.getAnchor(),raw.getQuote(),true,raw.getSourceHash(),null);
        Candidate candidate=new Candidate();candidate.setRuleId(output.getRuleId());candidate.setEvidence(Collections.singletonList(quote));VettingService.bindPackageIdentityEvidence(candidate,review);
        assertEquals(1,candidate.getEvidence().size());assertEquals(review.path("decisionSha256"),quote.getPackageIdentityLink().path("decisionSha256"));assertEquals(1,quote.getPackageIdentityLink().path("occurrenceIds").size());
        quote.setQuote("Contract No: FABRICATED");assertThrows(IllegalArgumentException.class,()->VettingService.bindPackageIdentityEvidence(candidate,review));
    }
    @Test void ordinaryFindingJsonAndFormerFullConstructorsStayCompatible() {
        FindingEvidence quote=new FindingEvidence("source","1","NTT","AUTHORED.txt",null,"authored",null,"AUTHORED quote",true,"hash",null,null,null,null,null,null,"tender",null);
        Candidate other=new Candidate();other.setRuleId("existing-rule");other.setEvidence(Collections.singletonList(quote));String before=JsonUtils.write(quote);
        VettingService.bindPackageIdentityEvidence(other,executed(null,"Contract No: PKG-2"));assertEquals(before,JsonUtils.write(quote));assertFalse(before.contains("packageIdentityLink"));
        CoverageVO old=new CoverageVO(1,0,Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),"incomplete",null,null);
        assertNull(old.getPackageIdentityReview());assertFalse(JsonUtils.write(old).contains("packageIdentityReview"));
        CoverageVO restored=JsonUtils.read(JsonUtils.write(old),CoverageVO.class);assertNull(restored.getPackageIdentityMirrorAudit());
    }
}
