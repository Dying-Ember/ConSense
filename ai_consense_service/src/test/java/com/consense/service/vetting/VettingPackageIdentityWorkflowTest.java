package com.consense.service.vetting;

import com.consense.common.*;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentBlock;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.StorageService;
import com.consense.service.drafting.DraftingService;
import com.consense.service.export.EnglishFileOutput;
import com.consense.service.vetting.VettingSemanticReview.*;
import com.consense.web.dto.VettingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Prepared authored workflow fixtures on disposable H2 only. Storage match, authority and semantic results are replacements. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2")
class VettingPackageIdentityWorkflowTest {
    private static final String ID=UUID.randomUUID().toString();
    @DynamicPropertySource static void isolation(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:h2:mem:package_identity_"+ID+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");}
    @Autowired VettingService service;@Autowired ConsenseProperties props;
    @Autowired ProjectRepository projects;@Autowired SourceDocumentRepository sources;@Autowired VettingRunRepository runs;
    @Autowired VettingFindingRepository findings;
    @Autowired VettingReportSnapshotRepository reportSnapshots;
    @MockBean DraftingService drafting;
    @MockBean VettingSemanticReview semantic;
    @MockBean OerBusinessEvidence oer;
    @MockBean StorageService storage;
    @MockBean EnglishFileOutput englishFiles;
    @BeforeEach void setup()throws Exception {
        when(englishFiles.json(any(JsonNode.class),anyString(),any(Runnable.class))).thenAnswer(call->{call.<Runnable>getArgument(2).run();return call.getArgument(0);});
        when(englishFiles.docx(any(byte[].class),anyString(),any(Runnable.class),anySet())).thenAnswer(call->{call.<Runnable>getArgument(2).run();return call.getArgument(0);});
        props.getVetting().setServiceProbeDirectory(null);props.getVetting().setProbeDirectory(null);props.getVetting().setExportProbeDirectory(null);
        when(storage.matchesStoredBytes(eq("AUTHORED_STORAGE"),anyString())).thenReturn(true);
        authority("PKG-1","INPUT");when(semantic.reviewWithProgress(isNull(),anyString(),anyList(),eq("en"),any(ReviewProgress.class),anyList(),any(Runnable.class))).thenReturn(empty());
    }
    private void authority(String number,String audit)throws Exception {when(drafting.packageIdentitySnapshot(anyString())).thenAnswer(call->{JsonNode authority=VettingPackageIdentityLifecycleTest.authority(number,"AUTHORED adopted title",audit);((com.fasterxml.jackson.databind.node.ObjectNode)authority.path("core")).put("projectId",call.<String>getArgument(0));return VettingPackageIdentityLifecycleTest.resolverSnapshot(authority);});}
    private Result empty(){Result result=new Result();result.setModel("AUTHORED semantic replacement only");return result;}
    private String project() {
        Project project=new Project();project.setId("identity-workflow-"+UUID.randomUUID());project.setNameEn("AUTHORED temporary mirror");project.setContractNo("TEMP-MIRROR");projects.saveAndFlush(project);
        String text="Contract No: PKG-2";DocumentBlock block=new DocumentBlock();block.setId("AUTHORED-IDENTITY");block.setLocation("AUTHORED/source");block.setKind("paragraph");block.setText(text);
        SourceDocument source=new SourceDocument();source.setProjectId(project.getId());source.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);source.setReviewRole("tender");source.setDocumentScope("package");source.setFileKey("NTT");source.setFileName("AUTHORED-NATIVE-TEXT.txt");source.setParseStatus("PARSED");source.setTextContent(text);source.setStructuredContentJson(JsonUtils.write(Collections.singletonList(block)));source.setSourceSha256(VettingCorpus.hash("AUTHORED bytes, storage matcher replaced"));source.setStoragePath("AUTHORED_STORAGE");sources.saveAndFlush(source);return project.getId();
    }
    private VettingJobVO await(String project,String id)throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<until){VettingJobVO job=service.getRun(project,id);if(Arrays.asList("COMPLETED","FAILED").contains(job.getStatus()))return job;Thread.sleep(10);}throw new AssertionError("Authored workflow did not terminate");}
    @Test void initialFreezeSurvivesQueueAndChangedAuthorityCannotPublish()throws Exception {
        String project=project();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(semantic.reviewWithProgress(isNull(),eq(project),anyList(),eq("en"),any(ReviewProgress.class),anyList(),any(Runnable.class))).thenAnswer(call->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return empty();});
        VettingJobVO queued=service.startRun(project,"en");
        try{assertTrue(entered.await(10,TimeUnit.SECONDS));JsonNode frozen=service.getRun(project,queued.getId()).getCoverage().getPackageIdentityReview();assertEquals("not_executed",frozen.path("executionStatus").asText());assertEquals("PKG-1",frozen.path("authority").path("core").path("fields").path("number").path("value").asText());authority("PKG-3","INPUT");}
        finally{release.countDown();}
        VettingJobVO failed=await(project,queued.getId());assertEquals("FAILED",failed.getStatus());assertTrue(findings.findByProjectIdOrderByCodeAsc(project).isEmpty());
    }
    @Test void auditOnlyRerunPreservesExactGeneratedRevisionAndHumanDisposition()throws Exception {
        String project=project();assertEquals(1,service.run(project,"en").getTotal());FindingVO first=service.listFindings(project,null,null,null,null,null).get(0);
        VettingHumanReviewFixture.updateStatus(service,project,first.getCode(),"Handled");FindingVO human=VettingHumanReviewFixture.updateReview(service,project,first.getCode(),new ReviewUpdateRequest("AUTHORED HUMAN NOTE","AUTHORED ACTION",false));
        authority("PKG-1","PROJECT_IDENTITY");Project mirror=projects.findById(project).get();mirror.setContractNo("DISPLAY-CHANGED");projects.saveAndFlush(mirror);
        assertEquals(1,service.run(project,"en").getTotal());FindingVO second=service.listFindings(project,null,null,null,null,null).get(0);
        assertEquals(first.getCode(),second.getCode());assertEquals(first.getGeneratedRevisionSha256(),second.getGeneratedRevisionSha256());assertEquals("Handled",second.getStatus());assertEquals(human.getReviewRemarks(),second.getReviewRemarks());assertEquals(human.getReviewUpdatedAt(),second.getReviewUpdatedAt());
        assertEquals("executed",service.latestRun(project).getCoverage().getPackageIdentityReview().path("executionStatus").asText());
    }
    @Test void completedExportAllowsMirrorAuditButRejectsChangedAdoptedCore()throws Exception {
        String project=project();service.run(project,"en");Project mirror=projects.findById(project).get();mirror.setContractNo("DISPLAY-ONLY");projects.saveAndFlush(mirror);
        JsonNode exported=JsonUtils.parse(new String(service.exportJson(project,"en"),java.nio.charset.StandardCharsets.UTF_8));
        JsonNode original=savedReport(exported);
        assertTrue(original.path("job").path("coverage").path("packageIdentityMirrorAudit").path("changed").asBoolean());assertEquals("PKG-1",original.path("job").path("coverage").path("packageIdentityReview").path("authority").path("core").path("fields").path("number").path("value").asText());
        assertFalse(exported.path("job").path("coverage").has("packageIdentityMirrorAudit"));assertFalse(exported.path("job").path("coverage").has("packageIdentityReview"));
        authority("PKG-9","INPUT");assertThrows(BizException.class,()->service.exportJson(project,"en"));assertEquals(1,findings.findByProjectIdOrderByCodeAsc(project).stream().filter(VettingFinding::getActive).count());
    }
    @Test void recoveryCannotReauthorizeSavedWorkUnderAnotherAuthority()throws Exception {
        String project=project();service.run(project,"en");VettingJobVO job=service.latestRun(project);VettingRun run=runs.findById(job.getId()).get();run.setStatus("RUNNING");run.setRecoveryStatus("AUTHORED_INTERRUPTED");run.setExecutionOwner(null);run.setLeaseUntil(Instant.EPOCH);runs.saveAndFlush(run);
        authority("PKG-8","INPUT");clearInvocations(semantic);service.recoverInterruptedRuns();assertEquals("FAILED",service.getRun(project,run.getId()).getStatus());
        verify(semantic,never()).reviewWithProgress(any(),anyString(),anyList(),anyString(),any(ReviewProgress.class),anyList(),any(Runnable.class));assertEquals(1,findings.findByProjectIdOrderByCodeAsc(project).stream().filter(VettingFinding::getActive).count());
    }
    private JsonNode savedReport(JsonNode exported) {
        assertEquals("2.0",exported.path("schemaVersion").asText());assertEquals("en",exported.path("language").asText());assertTrue(exported.path("presentationOnly").asBoolean());
        String snapshotId=exported.path("sourceReportSnapshotId").asText();assertTrue(snapshotId.matches("[a-f0-9]{64}"));
        VettingReportSnapshot saved=reportSnapshots.findById(snapshotId).orElseThrow(AssertionError::new);
        assertEquals(saved.getRunId(),exported.path("sourceRunId").asText());assertEquals(saved.getRunId(),exported.path("job").path("id").asText());
        assertTrue(exported.path("diagnostics").path("retainedServerSide").asBoolean());assertFalse(exported.path("job").has("result"));
        assertEquals(snapshotId,VettingCorpus.hash("vetting-report-snapshot-v1\n"+saved.getPayloadJson()));
        JsonNode original=JsonUtils.parse(saved.getPayloadJson());
        assertEquals(original.path("job").path("sourceSnapshotSha256").asText(),exported.path("sourceSnapshotSha256").asText());
        return original;
    }
}
