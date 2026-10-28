package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParser;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.vetting.VettingSemanticReview.*;
import com.consense.service.export.EnglishFileOutput;
import com.consense.web.dto.VettingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual H2 persistence and exports with fixture candidates; no model, OCR or network. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
        "consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2")
class VettingGeneratedRevisionWorkflowTest {
    private static final String ID=UUID.randomUUID().toString();
    @DynamicPropertySource static void isolation(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:h2:mem:generated_revision_"+ID+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        r.add("consense.storage-root",()->Paths.get("target","generated-revision-uploads",ID).toAbsolutePath().toString());
    }
    @Autowired VettingService service;
    @Autowired ConsenseProperties props;
    @Autowired DocumentParser parser;
    @Autowired ProjectRepository projects;
    @Autowired SourceDocumentRepository documents;
    @Autowired VettingFindingRepository findings;
    @Autowired VettingReportSnapshotRepository reportSnapshots;
    @MockBean VettingSemanticReview semantic;
    @MockBean EnglishFileOutput englishFiles;
    @BeforeEach void resetFixture() {
        props.getVetting().setServiceProbeDirectory(null);props.getVetting().setProbeDirectory(null);props.getVetting().setExportProbeDirectory(null);props.getVetting().setMaxRiskFindings(20);
        when(englishFiles.json(any(JsonNode.class),anyString(),any(Runnable.class),anySet())).thenAnswer(call->{call.<Runnable>getArgument(2).run();return call.getArgument(0);});
        when(englishFiles.docx(any(byte[].class),anyString(),any(Runnable.class),anySet())).thenAnswer(call->{call.<Runnable>getArgument(2).run();return call.getArgument(0);});
    }

    @Test void sevenClassesPersistFilterCountAndExportAsInternalMetadata() throws Exception {
        SourceDocument doc=source();String project=doc.getProjectId();
        when(semantic.reviewWithProgress(anyString(),eq(project),anyList(),eq("en"),any(ReviewProgress.class),anyList(),any(Runnable.class))).thenAnswer(call->{
            Result all=new Result();all.setModel("fixture-only");
            for(VettingIssueType type:VettingIssueType.values()) {
                Candidate c=result(doc,"").getCandidates().get(0);c.setRuleId("fixture-"+type.getGroup());c.setType(type.getGroup());all.getCandidates().add(c);
            }
            return all;
        });
        RunResultVO result=service.run(project,"en");assertEquals(7,result.getTotal());
        MetricsVO metrics=result.getMetrics();assertEquals(7,metrics.getReference()+metrics.getConflict()+metrics.getLanguage()+metrics.getRisk()+metrics.getGap()+metrics.getSubstantive()+metrics.getRequirement());
        assertEquals(1,metrics.getGap());assertEquals(1,metrics.getSubstantive());assertEquals(1,metrics.getRequirement());
        for(VettingIssueType type:VettingIssueType.values()) {
            List<FindingVO> filtered=service.listFindings(project,null,type.getGroup(),null,null,null);assertEquals(1,filtered.size());
            assertEquals(type.getCode(),filtered.get(0).getIssueTypeCode());assertEquals(Collections.singletonList(type.getStoredType()),filtered.get(0).getTypes());
        }
        assertEquals(VettingPromptPolicy.metadata(),result.getPromptPolicy());
        com.fasterxml.jackson.databind.JsonNode report=JsonUtils.parse(new String(service.exportJson(project,"en"),StandardCharsets.UTF_8));
        JsonNode originalReport=savedReport(report);
        assertEquals(VettingPromptPolicy.sha256(),originalReport.path("job").path("result").path("promptPolicy").path("sha256").asText());
        assertFalse(report.has("promptPolicy"),"Internal prompt metadata stays in the immutable source snapshot");
        assertEquals(5,report.path("commentsTables").get(0).path("headings").size(),"Seven classes must not add an official report column");
        Set<String> codes=new HashSet<>();originalReport.path("findings").forEach(f->codes.add(f.path("issueTypeCode").asText()));assertEquals(7,codes.size());
        assertEquals("Authored source review",report.path("findings").get(0).path("businessDetails").path("subDimension").asText());
        assertEquals(44,report.path("automaticChecklistResults").size());
    }

    @ParameterizedTest @ValueSource(strings={"subDimension","factBasis","pendingFacts","suggestedAction","checklistItemIds"})
    void changedCompetitionRationaleCannotInheritAnEarlierHumanDecision(String field) throws Exception {
        SourceDocument doc=source();String project=doc.getProjectId();AtomicReference<Boolean> changed=new AtomicReference<>(false);
        when(semantic.reviewWithProgress(anyString(),eq(project),anyList(),eq("en"),any(ReviewProgress.class),anyList(),any(Runnable.class))).thenAnswer(call->{
            Result value=result(doc,"");BusinessDetailsVO business=value.getCandidates().get(0).getBusinessDetails();
            if(changed.get())switch(field){case "subDimension":business.setSubDimension("Changed authored dimension");break;case "factBasis":business.setFactBasis("Changed authored source rationale");break;case "pendingFacts":business.setPendingFacts(Collections.singletonList("Confirm authored scope"));break;case "suggestedAction":business.setSuggestedAction("amend");break;default:business.setChecklistItemIds(Collections.singletonList("APP-I-026"));}
            return value;
        });
        FindingVO original=run(project);VettingHumanReviewFixture.updateStatus(service,project,original.getCode(),"Handled");
        changed.set(true);FindingVO current=run(project);assertNotEquals(original.getCode(),current.getCode());assertNotEquals(original.getFingerprint(),current.getFingerprint());
        assertNotEquals(original.getGeneratedRevisionSha256(),current.getGeneratedRevisionSha256());assertEquals("Open",current.getStatus());assertNull(current.getReviewRemarks());
        assertEquals("Handled",findings.findByProjectIdAndCode(project,original.getCode()).orElseThrow(AssertionError::new).getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings={"comment","title","impact","suggestion","severity","type"})
    void changedGeneratedObservationDoesNotInheritAnEarlierHumanDecision(String changedField) throws Exception {
        SourceDocument doc=source();String project=doc.getProjectId();AtomicReference<String> variant=new AtomicReference<>("");
        when(semantic.reviewWithProgress(anyString(),eq(project),anyList(),eq("en"),any(ReviewProgress.class),anyList(),any(Runnable.class)))
                .thenAnswer(call->result(doc,variant.get()));
        FindingVO first=run(project);VettingHumanReviewFixture.updateStatus(service,project,first.getCode(),"Handled");
        FindingVO reviewed=VettingHumanReviewFixture.updateReview(service,project,first.getCode(),new ReviewUpdateRequest(" old manual \r\n原文😀 "," old action ",false));
        Instant reviewedAt=reviewed.getReviewUpdatedAt();String originalRun=first.getRunId();
        variant.set(changedField);FindingVO changed=run(project);
        assertEquals(first.getFingerprint(),changed.getFingerprint(),"This regression has identical rule and evidence identity");
        assertNotEquals(first.getCode(),changed.getCode(),"A new generated observation must require its own human review");
        assertEquals("Open",changed.getStatus());assertNull(changed.getReviewRemarks());assertNull(changed.getActionTaken());
        assertNull(changed.getAddendumRequired());assertNull(changed.getReviewUpdatedAt());
        VettingFinding old=findings.findByProjectIdAndCode(project,first.getCode()).orElseThrow(AssertionError::new);
        assertFalse(old.getActive());assertEquals("Handled",old.getStatus());assertEquals(originalRun,old.getRunId());
        assertEquals(reviewed.getReviewRemarks(),old.getReviewRemarks());assertEquals(reviewed.getActionTaken(),old.getActionTaken());
        assertEquals(Boolean.FALSE,old.getAddendumRequired());assertEquals(reviewedAt,old.getReviewUpdatedAt());
        assertEquals(first.getBody().getEn(),old.getBodyEn());
        JsonNode report=JsonUtils.parse(new String(service.exportJson(project,"en"),StandardCharsets.UTF_8));
        assertEquals(1,report.path("findings").size());assertEquals(changed.getCode(),report.path("findings").get(0).path("code").asText());
        assertTrue(savedReport(report).path("findings").get(0).path("reviewRemarks").isNull());
        VettingHumanReviewFixture.updateStatus(service,project,changed.getCode(),"Assigned");
        FindingVO newManual=VettingHumanReviewFixture.updateReview(service,project,changed.getCode(),new ReviewUpdateRequest(" new review "," new action ",true));
        FindingVO repeated=run(project);
        assertEquals(changed.getCode(),repeated.getCode(),"An identical regenerated observation must reuse its own revision");
        assertEquals("Assigned",repeated.getStatus());assertEquals(newManual.getReviewRemarks(),repeated.getReviewRemarks());
        assertEquals(newManual.getReviewUpdatedAt(),repeated.getReviewUpdatedAt());assertEquals(Boolean.TRUE,repeated.getAddendumRequired());
        assertEquals(2,findings.findByProjectIdOrderByCodeAsc(project).size());
    }

    @Test void returningToAnExactEarlierRevisionPreservesThatRevisionsHistory() throws Exception {
        SourceDocument doc=source();String project=doc.getProjectId();AtomicReference<String> variant=new AtomicReference<>("");
        when(semantic.reviewWithProgress(anyString(),eq(project),anyList(),eq("en"),any(ReviewProgress.class),anyList(),any(Runnable.class))).thenAnswer(call->result(doc,variant.get()));
        FindingVO original=run(project);VettingHumanReviewFixture.updateStatus(service,project,original.getCode(),"Handled");
        FindingVO manual=VettingHumanReviewFixture.updateReview(service,project,original.getCode(),new ReviewUpdateRequest(" Original version only ",null,false));
        variant.set("comment");FindingVO second=run(project);assertNotEquals(original.getCode(),second.getCode());
        variant.set("");FindingVO returned=run(project);
        assertEquals(original.getCode(),returned.getCode());assertEquals("Handled",returned.getStatus());
        assertEquals(manual.getReviewRemarks(),returned.getReviewRemarks());assertEquals(manual.getReviewUpdatedAt(),returned.getReviewUpdatedAt());
        assertEquals(2,findings.findByProjectIdOrderByCodeAsc(project).size());
    }

    @Test void incompleteLegacyGeneratedContentDoesNotCertifyANewObservation() throws Exception {
        SourceDocument doc=source();String project=doc.getProjectId();
        when(semantic.reviewWithProgress(anyString(),eq(project),anyList(),eq("en"),any(ReviewProgress.class),anyList(),any(Runnable.class))).thenAnswer(call->result(doc,""));
        FindingVO original=run(project);VettingHumanReviewFixture.updateStatus(service,project,original.getCode(),"Handled");
        VettingHumanReviewFixture.updateReview(service,project,original.getCode(),new ReviewUpdateRequest(" Legacy manual ",null,null));
        VettingFinding legacy=findings.findByProjectIdAndCode(project,original.getCode()).orElseThrow(AssertionError::new);
        legacy.setBodyEn(null);findings.saveAndFlush(legacy);
        FindingVO current=run(project);assertNotEquals(original.getCode(),current.getCode());assertEquals("Open",current.getStatus());
        assertNull(current.getReviewRemarks());assertNull(current.getAddendumRequired());
        VettingFinding retained=findings.findByProjectIdAndCode(project,original.getCode()).orElseThrow(AssertionError::new);
        assertEquals(" Legacy manual ",retained.getReviewRemarks());assertFalse(retained.getActive());assertNull(retained.getBodyEn());
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

    private FindingVO run(String project) {assertEquals(1,service.run(project,"en").getTotal());return service.listFindings(project,null,null,null,null,null).get(0);}
    private SourceDocument source() throws Exception {
        Project p=new Project();p.setId("revision-"+UUID.randomUUID());p.setNameEn("Generated revision fixture");p.setContractNo("TEST");projects.saveAndFlush(p);
        String text="NTT 1.1 The fixture register shall be retained.";DocumentParser.ParsedDocument parsed=parser.parse("NTT.txt",text.getBytes(StandardCharsets.UTF_8));
        SourceDocument doc=new SourceDocument();doc.setProjectId(p.getId());doc.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);doc.setFileKey("NTT");doc.setFileName("NTT.txt");
        doc.setReviewRole("tender");doc.setDocumentScope("package");
        doc.setTextContent(parsed.getText());doc.setParseStatus(parsed.getParseStatus());doc.setPageCount(parsed.getPageCount());doc.setOcrUsed(false);
        doc.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));doc.setParseCoverageJson(JsonUtils.write(parsed.getCoverage()));return documents.saveAndFlush(doc);
    }
    private static Result result(SourceDocument doc,String changedField) {
        Candidate c=new Candidate();c.setRuleId("fixture-stable-evidence");c.setType("risk");c.setSeverity("high");c.setTitle("Fixture original title");
        c.setComment("Fixture original observation");c.setImpact("Fixture original impact");c.setSuggestion("Fixture original suggestion");c.setSource("model");
        c.setBusinessDetails(new BusinessDetailsVO("Authored source review","Authored fixture source rationale; no project fact is inferred.",Collections.emptyList(),"review"));
        c.setEvidence(Collections.singletonList(new FindingEvidence("source",String.valueOf(doc.getId()),doc.getFileKey(),doc.getFileName(),null,"body fixture",doc.getTextContent(),true,VettingCorpus.sourceHash(doc),null)));
        switch(changedField) {
            case "comment":c.setComment("Fixture changed observation");break;
            case "title":c.setTitle("Fixture changed title");break;
            case "impact":c.setImpact("Fixture changed impact");break;
            case "suggestion":c.setSuggestion("Fixture changed suggestion");break;
            case "severity":c.setSeverity("low");break;
            case "type":c.setType("language");break;
            default:break;
        }
        Result result=new Result();result.setModel("fixture-only");result.getCandidates().add(c);return result;
    }
}
