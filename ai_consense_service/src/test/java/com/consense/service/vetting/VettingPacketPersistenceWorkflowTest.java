package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentBlock;
import com.consense.document.OerDocumentIdentity;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.sources.OerSourceMetadata;
import com.consense.service.sources.SourceContentDigests;
import com.consense.service.vetting.VettingSemanticReview.*;
import com.consense.service.export.EnglishFileOutput;
import com.consense.web.dto.VettingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Isolated unique in-memory H2 with saved fixture blocks and mock review; no parser/HTTP/model. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2")
class VettingPacketPersistenceWorkflowTest {
    private static final String ID=UUID.randomUUID().toString();
    @DynamicPropertySource static void isolation(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:h2:mem:packet_persistence_"+ID+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");r.add("consense.storage-root",()->Paths.get("target/packet-persistence-uploads",ID).toAbsolutePath().toString());}
    @Autowired VettingService service;@Autowired ConsenseProperties props;@Autowired ProjectRepository projects;@Autowired SourceDocumentRepository documents;@Autowired VettingFindingRepository findings;@Autowired VettingReportSnapshotRepository reportSnapshots;@Autowired VettingRunRepository runs;@Autowired VettingChecklistService checklist;@Autowired JdbcTemplate jdbc;@MockBean VettingSemanticReview semantic;
    @MockBean EnglishFileOutput englishFiles;
    @BeforeEach void settings(){
        props.getVetting().setMaxRiskFindings(20);props.getVetting().setServiceProbeDirectory(null);props.getVetting().setProbeDirectory(null);props.getVetting().setExportProbeDirectory(null);
        when(englishFiles.json(any(JsonNode.class),anyString(),any(Runnable.class),anySet())).thenAnswer(call->{call.<Runnable>getArgument(2).run();return call.getArgument(0);});
        when(englishFiles.docx(any(byte[].class),anyString(),any(Runnable.class),anySet())).thenAnswer(call->{call.<Runnable>getArgument(2).run();return call.getArgument(0);});
    }
    private SourceDocument source(){return source(Collections.singletonList("The original register shall be retained."));}
    private SourceDocument source(List<String> texts){
        Project p=new Project();p.setId("packet-"+UUID.randomUUID());p.setNameEn("Packet persistence fixture");p.setContractNo("TEST");projects.saveAndFlush(p);
        List<DocumentBlock> blocks=new ArrayList<>();for(int i=0;i<texts.size();i++){DocumentBlock block=new DocumentBlock();block.setId(i==0?"fixture-block":"fixture-block-"+i);block.setKind("paragraph");block.setLocation(i==0?"body/fixture":"body/fixture/"+i);block.setSource("fixture_saved_text");block.setText(texts.get(i));block.setPageNo(i+1);blocks.add(block);}
        SourceDocument s=new SourceDocument();s.setProjectId(p.getId());s.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);s.setReviewRole("tender");s.setFileKey("SCT");s.setFileName("project-SCT.docx");s.setParseStatus("PARSED");s.setTextContent(String.join("\n",texts));s.setStructuredContentJson(JsonUtils.write(blocks));s.setParseCoverageJson("{\"complete\":true}");
        try(XWPFDocument original=new XWPFDocument();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            for(String text:texts)original.createParagraph().createRun().setText(text);original.write(bytes);
            byte[] content=bytes.toByteArray();Path path=Paths.get(props.getStorageRoot()).resolve(p.getId()).resolve(s.getFileName());
            Files.createDirectories(path.getParent());Files.write(path,content);
            s.setStoragePath(path.toString());s.setSourceSha256(OerSourceMetadata.originalSha256(content));
        }catch(IOException failure){throw new UncheckedIOException(failure);}
        OerDocumentIdentity identity=new OerDocumentIdentity();identity.setSourceSha256(s.getSourceSha256());identity.setKind("SCT");identity.setRole("tender");
        identity.setSourcePurpose("project_document");identity.setIdentityStatus("content_supported");identity.setEligibleModules(Arrays.asList("vetting","advisory"));
        s.setOerIdentityJson(JsonUtils.write(identity));NormalSourceCandidateGraph.capture(s);
        s.setTextContentSha256(SourceContentDigests.field(s.getTextContent()));s.setStructuredContentSha256(SourceContentDigests.field(s.getStructuredContentJson()));
        s.setParseCoverageSha256(SourceContentDigests.field(s.getParseCoverageJson()));s.setNormalCandidateSha256(SourceContentDigests.field(s.getNormalCandidateJson()));
        s.setBusinessContentSha256(SourceContentDigests.combined(s.getTextContentSha256(),s.getStructuredContentSha256(),s.getParseCoverageSha256(),s.getNormalCandidateSha256()));
        s=documents.saveAndFlush(s);
        jdbc.update("update source_document set text_content_sha256=?,structured_content_sha256=?,parse_coverage_sha256=?,normal_candidate_sha256=?,business_content_sha256=? where id=?",
                s.getTextContentSha256(),s.getStructuredContentSha256(),s.getParseCoverageSha256(),s.getNormalCandidateSha256(),s.getBusinessContentSha256(),s.getId());
        return s;
    }
    private Candidate candidate(SourceDocument s,String variant){String sha=VettingCorpus.hash("synthetic-bound-source-snapshot:"+variant);String packet="topic-1-packet-1-"+sha;Candidate c=new Candidate();c.setRuleId("semantic-risk");c.setSource("model");c.setType("risk");c.setSeverity("high");c.setTitle("Same synthetic finding");c.setComment("Same source quotation, distinct source input scope.");c.setImpact("Manual review");c.setSuggestion("Check original");c.setPacketId(packet);c.setSourceSnapshotSha256(sha);FindingEvidence e=new FindingEvidence("source",String.valueOf(s.getId()),s.getFileKey(),s.getFileName(),null,"body/fixture",s.getTextContent(),true,VettingCorpus.sourceHash(s),null);e.setPacketId(packet);e.setPacketSourceSnapshotSha256(sha);c.setEvidence(Collections.singletonList(e));return c;}
    private Result result(Candidate... cs){Result r=new Result();r.setModel("synthetic-review-fixture");r.getCandidates().addAll(Arrays.asList(cs));SemanticTopicVO a=new SemanticTopicVO();a.setTopicIndex(1);a.setStatus("completed");a.setGlobalCallStatus("completed");a.setAggregateReviewStatus("partial");a.setSourceRequestCount(2);a.setPendingSourceRequestCount(1);r.getTopicAudits().add(a);return r;}
    @Test void exactPacketMetadataSurvivesDatabaseListJsonSourceDialogAndCoverage() throws Exception {
        SourceDocument s=source();Candidate a=candidate(s,"A"),b=candidate(s,"B");when(semantic.reviewWithProgress(anyString(),eq(s.getProjectId()),anyList(),eq("en"),any(ReviewProgress.class),anyList(),any(Runnable.class))).thenReturn(result(a,b));assertEquals(2,service.run(s.getProjectId(),"en").getTotal());List<FindingVO> vos=service.listFindings(s.getProjectId(),null,null,null,null,null);assertEquals(2,vos.size());assertNotEquals(vos.get(0).getFingerprint(),vos.get(1).getFingerprint());
        for(FindingVO vo:vos){FindingEvidence e=vo.getEvidence().get(0);assertNotNull(e.getPacketId());assertTrue(e.getPacketId().endsWith(e.getPacketSourceSnapshotSha256()));assertEquals(e.getPacketId(),service.evidence(s.getProjectId(),vo.getCode()).getItems().get(0).getPacketId());assertEquals(e.getPacketSourceSnapshotSha256(),service.evidence(s.getProjectId(),vo.getCode()).getItems().get(0).getPacketSourceSnapshotSha256());VettingFinding row=findings.findByProjectIdAndCode(s.getProjectId(),vo.getCode()).get();assertTrue(row.getEvidenceJson().contains(e.getPacketId()));}
        JsonNode exported=JsonUtils.parse(new String(service.exportJson(s.getProjectId(),"en"),StandardCharsets.UTF_8));String raw=savedReport(exported).toString();
        assertTrue(raw.contains(a.getPacketId()));assertTrue(raw.contains(b.getPacketId()));
        assertEquals("partial",exported.path("job").path("coverage").path("semanticTopics").get(0).path("aggregateReviewStatus").asText());
        for(JsonNode finding:exported.path("findings"))for(JsonNode evidence:finding.path("evidence")){
            assertFalse(evidence.path("literalSourceEvidence").asBoolean(true));assertFalse(evidence.has("quote"));assertTrue(evidence.path("displayQuote").isTextual());
        }
        assertEquals("partial",service.latestRun(s.getProjectId()).getCoverage().getSemanticTopics().get(0).getAggregateReviewStatus());
    }
    @Test void completedPartialRunKeepsRuleAndAcceptedPacketWhileFailedOriginsStayUnreviewedInSavedExport() {
        // The semantic result is authored here; separate real-wire tests prove the typed retry classification.
        SourceDocument s=source(Arrays.asList("The original register shall be retained.","SCT1 Not used.","Include Clauses SCT1 to SCT2 in the downstream agreement."));
        List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(s));assertEquals(3,chunks.size());
        Candidate accepted=candidate(s,"accepted-only");FindingEvidence quote=VettingCorpus.evidence(chunks.get(0),"source","The original register shall be retained.");quote.setPacketId(accepted.getPacketId());quote.setPacketSourceSnapshotSha256(accepted.getSourceSnapshotSha256());accepted.setEvidence(Collections.singletonList(quote));
        Result partial=result(accepted);partial.getReviewedChunkIds().add(chunks.get(0).getId());partial.setPlannedCallCount(2);partial.setActualGatewayCallCount(3);partial.getWarnings().add("One source packet exhausted two rejected attribution attempts and remains unreviewed.");
        SemanticTopicVO audit=partial.getTopicAudits().get(0);audit.setFailedPacketCount(1);audit.setAcceptedFindings(1);audit.setSubmittedChunkIds(Collections.singletonList(chunks.get(0).getId()));audit.setUnresolvedChunkIds(Arrays.asList(chunks.get(1).getId(),chunks.get(2).getId()));
        SemanticPacketVO good=new SemanticPacketVO();good.setPacketId(accepted.getPacketId());good.setSourceSnapshotSha256(accepted.getSourceSnapshotSha256());good.setStatus("completed");good.setAcceptedFindings(1);good.setActualGatewayCallStarted(true);good.setInputBudgetStatus("observed_tokens");good.setSubmittedChunkIds(Collections.singletonList(chunks.get(0).getId()));good.setCheckState("issue");audit.getPacketAudits().add(good);
        SemanticPacketVO failed=VettingPacketCoverage.packet(1,2,"source_request",chunks.subList(1,3),Collections.singletonList("failed-request"));failed.setStatus("failed");failed.setFailureKind("source_attribution_invalid");failed.setError("SOURCE_ATTRIBUTION_MISMATCH: authored unknown reference");failed.setActualGatewayCallStarted(true);failed.setInputBudgetStatus("observed_tokens");
        for(int ordinal=1;ordinal<=2;ordinal++){SemanticAttemptVO attempt=new SemanticAttemptVO();attempt.setOrdinal(ordinal);attempt.setStatus("structured_output_invalid");attempt.setValidationReason("SOURCE_ATTRIBUTION_MISMATCH: authored unknown reference");attempt.setRawResponse("Authored rejected attribution response "+ordinal);attempt.setRawResponseSha256(VettingCorpus.hash(attempt.getRawResponse()));failed.getAttempts().add(attempt);}audit.getPacketAudits().add(failed);
        SemanticSourceRequestVO request=new SemanticSourceRequestVO();request.setRequestId("failed-request");request.setPacketId(failed.getPacketId());request.setReviewExecutionStatus("failed");request.setEligibleOriginIds(new ArrayList<>(failed.getSubmittedChunkIds()));request.setRequiredChunkIds(new ArrayList<>(failed.getSubmittedChunkIds()));audit.getSourceRequests().add(request);
        when(semantic.reviewWithProgress(anyString(),eq(s.getProjectId()),anyList(),eq("en"),any(ReviewProgress.class),anyList(),any(Runnable.class))).thenReturn(partial);
        assertNull(partial.getModelFailure());assertEquals(2,service.run(s.getProjectId(),"en").getTotal());VettingJobVO saved=service.latestRun(s.getProjectId());assertEquals("COMPLETED",saved.getStatus());assertEquals("incomplete",saved.getCoverage().getReviewCompleteness());
        DocumentCoverageVO document=saved.getCoverage().getDocuments().get(0);assertEquals(3,document.getTotalSegments());assertEquals(1,document.getReviewedSegments());assertTrue(document.getReviewedChars()>0);assertTrue(document.getReviewedChars()<document.getTextChars());
        List<FindingVO> kept=service.listFindings(s.getProjectId(),null,null,null,null,null);assertEquals(2,kept.size());assertEquals(2,findings.findAll().stream().filter(row->s.getProjectId().equals(row.getProjectId())&&row.getActive()).count());
        assertTrue(kept.stream().anyMatch(row->"rule".equals(row.getSource())));FindingVO model=kept.stream().filter(row->"model".equals(row.getSource())).findFirst().orElseThrow(AssertionError::new);assertEquals(accepted.getPacketId(),service.evidence(s.getProjectId(),model.getCode()).getItems().get(0).getPacketId());
        JsonNode exported=JsonUtils.parse(new String(service.exportJson(s.getProjectId(),"en"),StandardCharsets.UTF_8));JsonNode original=savedReport(exported);assertEquals(2,exported.path("findings").size());assertEquals("COMPLETED",exported.path("job").path("status").asText());assertEquals("incomplete",exported.path("job").path("coverage").path("reviewCompleteness").asText());assertEquals(1,exported.path("job").path("coverage").path("documents").get(0).path("reviewedSegments").asInt());
        JsonNode persistedAudit=original.path("job").path("coverage").path("semanticTopics").get(0);assertEquals("partial",persistedAudit.path("aggregateReviewStatus").asText());assertEquals(1,persistedAudit.path("failedPacketCount").asInt());assertEquals(2,persistedAudit.path("packetAudits").get(1).path("attempts").size());assertEquals("failed",persistedAudit.path("sourceRequests").get(0).path("reviewExecutionStatus").asText());assertEquals(2,persistedAudit.path("sourceRequests").get(0).path("eligibleOriginIds").size());
    }
    @Test void changedPacketSourceScopeNeverInheritsPreviousHumanDecisionAndExactScopeDoes() {
        SourceDocument s=source();AtomicReference<String> variant=new AtomicReference<>("A");when(semantic.reviewWithProgress(anyString(),eq(s.getProjectId()),anyList(),eq("en"),any(ReviewProgress.class),anyList(),any(Runnable.class))).thenAnswer(call->result(candidate(s,variant.get())));service.run(s.getProjectId(),"en");FindingVO old=service.listFindings(s.getProjectId(),null,null,null,null,null).get(0);VettingHumanReviewFixture.updateStatus(service,s.getProjectId(),old.getCode(),"Handled");FindingVO manual=VettingHumanReviewFixture.updateReview(service,s.getProjectId(),old.getCode(),new ReviewUpdateRequest("Review applies to packet A only","No amendment",false));service.run(s.getProjectId(),"en");FindingVO same=service.listFindings(s.getProjectId(),null,null,null,null,null).get(0);assertEquals(old.getCode(),same.getCode());assertEquals("Handled",same.getStatus());assertEquals(manual.getReviewRemarks(),same.getReviewRemarks());
        variant.set("B");service.run(s.getProjectId(),"en");FindingVO changed=service.listFindings(s.getProjectId(),null,null,null,null,null).get(0);assertNotEquals(old.getFingerprint(),changed.getFingerprint());assertEquals("Open",changed.getStatus());assertNull(changed.getReviewRemarks());assertNull(changed.getActionTaken());VettingFinding retained=findings.findByProjectIdAndCode(s.getProjectId(),old.getCode()).get();assertFalse(retained.getActive());assertEquals("Handled",retained.getStatus());assertEquals(manual.getReviewRemarks(),retained.getReviewRemarks());
    }
    @Test void classifiedModelFailurePublishesOnlyAcceptedPacketsWithFailedTerminalAndIncompleteReadableReport() throws Exception {
        for(String failureKind:Arrays.asList("transport_failure","input_budget_unknown")) {
            SourceDocument s=source(Arrays.asList("The original register shall be retained.","The Contractor shall keep an inspection log."));
            List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(s));assertEquals(2,chunks.size());
            Candidate accepted=boundCandidate(s,chunks.get(0),"accepted-"+failureKind);
            Candidate rejected=boundCandidate(s,chunks.get(1),"failed-"+failureKind);
            Result partial=failedResult(accepted,rejected,chunks,failureKind);
            when(semantic.reviewWithProgress(anyString(),eq(s.getProjectId()),anyList(),eq("en"),any(ReviewProgress.class),anyList(),any(Runnable.class))).thenReturn(partial);
            assertThrows(com.consense.common.BizException.class,()->service.run(s.getProjectId(),"en"));
            VettingJobVO saved=service.latestRun(s.getProjectId());assertEquals("FAILED",saved.getStatus());assertEquals("failed",saved.getPhase());
            assertEquals(partial.getModelFailure(),saved.getError());assertEquals("NEEDS_RERUN",saved.getRecoveryStatus());assertNotNull(saved.getFinishedAt());
            assertNotNull(saved.getResult(),"Earlier accepted packets must remain usable after a later model failure");
            assertTrue(JsonUtils.mapper().valueToTree(saved.getResult()).path("partialResults").asBoolean());assertEquals(1,saved.getResult().getTotal());
            assertEquals("incomplete",saved.getCoverage().getReviewCompleteness());
            DocumentCoverageVO coverage=saved.getCoverage().getDocuments().get(0);assertEquals(2,coverage.getTotalSegments());assertEquals(1,coverage.getReviewedSegments());
            List<FindingVO> published=service.listFindings(s.getProjectId(),null,null,null,null,null);assertEquals(1,published.size());
            assertEquals(accepted.getPacketId(),published.get(0).getEvidence().get(0).getPacketId());
            assertFalse(service.currentReviewRun(run(saved)),"A failed partial run cannot unlock human write or reuse gates");
            assertFalse(checklist.get(s.getProjectId(),saved.getId()).isWritable(),"Accepted failed-run assessments remain read only");
            SemanticPacketVO failed=saved.getCoverage().getSemanticTopics().get(0).getPacketAudits().get(1);
            assertEquals("failed",failed.getStatus());assertEquals(failureKind,failed.getFailureKind());assertEquals(1,failed.getAttempts().size());
            JsonNode exported=JsonUtils.parse(new String(service.exportJson(s.getProjectId(),"en"),StandardCharsets.UTF_8));
            JsonNode report=savedReport(exported);assertEquals("FAILED",report.path("job").path("status").asText());
            assertTrue(report.path("job").path("result").path("partialResults").asBoolean());
            assertEquals(1,report.path("job").path("coverage").path("documents").get(0).path("reviewedSegments").asInt());
            assertEquals("FAILED",exported.path("job").path("status").asText());assertTrue(exported.toString().contains("Review run failed"));
            try(XWPFDocument word=new XWPFDocument(new java.io.ByteArrayInputStream(service.exportDocx(s.getProjectId(),"en")))) {
                String text=word.getParagraphs().stream().map(org.apache.poi.xwpf.usermodel.XWPFParagraph::getText).collect(java.util.stream.Collectors.joining("\n"));
                assertTrue(text.contains("Review run failed. Only partial results from accepted source packets are available."));
                assertTrue(text.contains("Unfinished source requests remain unreviewed."));
            }
            VettingRun marked=run(saved);String committedResult=marked.getResultJson();com.fasterxml.jackson.databind.node.ObjectNode malformed=(com.fasterxml.jackson.databind.node.ObjectNode)JsonUtils.parse(committedResult);malformed.put("partialResults","true");marked.setResultJson(JsonUtils.write(malformed));runs.saveAndFlush(marked);
            assertEquals(4103,assertThrows(com.consense.common.BizException.class,()->service.exportJson(s.getProjectId(),"en")).getCode(),"Textual truth cannot unlock failed-run export");
            marked.setResultJson(committedResult);runs.saveAndFlush(marked);
            Files.write(Paths.get(s.getStoragePath()),"AUTHORED replaced original bytes after publication".getBytes(StandardCharsets.UTF_8));
            assertEquals(4104,assertThrows(com.consense.common.BizException.class,()->service.exportJson(s.getProjectId(),"en")).getCode());
        }
    }
    @Test void guardCancellationOwnershipAndSourceChangesCannotPublishPartialCandidatesOrAlterHistoricalHumanRecords() {
        for(String guard:Arrays.asList("unclassified","no_completed","ambiguous_packet","cancelled","lease","source","newer")) {
            SourceDocument s=source(Arrays.asList("The original register shall be retained.","The Contractor shall keep an inspection log."));
            List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(s));
            Candidate before=boundCandidate(s,chunks.get(0),"before-"+guard);
            when(semantic.reviewWithProgress(anyString(),eq(s.getProjectId()),anyList(),eq("en"),any(ReviewProgress.class),anyList(),any(Runnable.class))).thenReturn(result(before));
            service.run(s.getProjectId(),"en");FindingVO original=service.listFindings(s.getProjectId(),null,null,null,null,null).get(0);
            VettingHumanReviewFixture.updateReview(service,s.getProjectId(),original.getCode(),new ReviewUpdateRequest("Keep this historical human remark","Manual decision",false));
            VettingHumanReviewFixture.updateStatus(service,s.getProjectId(),original.getCode(),"Handled");
            Candidate accepted=boundCandidate(s,chunks.get(0),"after-"+guard),rejected=boundCandidate(s,chunks.get(1),"failed-"+guard);
            Result partial=failedResult(accepted,rejected,chunks,"unclassified".equals(guard)?"review_processing_failure":"transport_failure");
            if("no_completed".equals(guard)){SemanticPacketVO first=partial.getTopicAudits().get(0).getPacketAudits().get(0);first.setStatus("failed");first.setFailureKind("transport_failure");}
            if("ambiguous_packet".equals(guard)){SemanticPacketVO duplicate=JsonUtils.read(JsonUtils.write(partial.getTopicAudits().get(0).getPacketAudits().get(0)),SemanticPacketVO.class);duplicate.setSubmittedChunkIds(Collections.singletonList(chunks.get(1).getId()));partial.getTopicAudits().get(0).getPacketAudits().add(duplicate);}
            when(semantic.reviewWithProgress(anyString(),eq(s.getProjectId()),anyList(),eq("en"),any(ReviewProgress.class),anyList(),any(Runnable.class))).thenAnswer(call->{
                VettingRun executing=run(service.latestRun(s.getProjectId()));
                if("cancelled".equals(guard))executing.setStatus("CANCELLED");
                if("lease".equals(guard))executing.setExecutionOwner("another-execution-owner");
                if("source".equals(guard)){SourceDocument current=documents.findById(s.getId()).get();current.setTextContent("AUTHORED changed source text");documents.saveAndFlush(current);}
                if("newer".equals(guard)){VettingRun next=new VettingRun();next.setId(UUID.randomUUID().toString());next.setProjectId(s.getProjectId());next.setLang("en");next.setStatus("QUEUED");next.setStartedAt(Instant.now().plusSeconds(60));runs.saveAndFlush(next);}
                else runs.saveAndFlush(executing);
                return partial;
            });
            assertThrows(com.consense.common.BizException.class,()->service.run(s.getProjectId(),"en"),guard);
            List<VettingFinding> retained=findings.findByProjectIdOrderByCodeAsc(s.getProjectId());assertEquals(1,retained.size(),guard);
            VettingFinding historical=retained.get(0);assertEquals(original.getRunId(),historical.getRunId());assertTrue(historical.getActive());
            assertEquals("Handled",historical.getStatus());assertEquals("Keep this historical human remark",historical.getReviewRemarks());assertEquals("Manual decision",historical.getActionTaken());
        }
    }
    private Candidate boundCandidate(SourceDocument source,VettingCorpus.Chunk chunk,String variant) {
        Candidate candidate=candidate(source,variant);FindingEvidence quote=VettingCorpus.evidence(chunk,"source",chunk.getContent());
        quote.setPacketId(candidate.getPacketId());quote.setPacketSourceSnapshotSha256(candidate.getSourceSnapshotSha256());
        candidate.setEvidence(Collections.singletonList(quote));return candidate;
    }
    private Result failedResult(Candidate accepted,Candidate rejected,List<VettingCorpus.Chunk> chunks,String failureKind) {
        Result partial=result(accepted,rejected);partial.setModelFailure("AUTHORED model failure: "+failureKind);partial.setPlannedCallCount(2);partial.setActualGatewayCallCount(2);
        partial.getReviewedChunkIds().add(chunks.get(0).getId());partial.getReviewedChunkIds().add(chunks.get(1).getId());
        SemanticTopicVO topic=partial.getTopicAudits().get(0);topic.setAggregateReviewStatus("partial");topic.setFailedPacketCount(1);
        SemanticPacketVO good=new SemanticPacketVO();good.setPacketId(accepted.getPacketId());good.setSourceSnapshotSha256(accepted.getSourceSnapshotSha256());good.setStatus("completed");good.setActualGatewayCallStarted(true);good.setSubmittedChunkIds(Collections.singletonList(chunks.get(0).getId()));good.setAcceptedFindings(1);topic.getPacketAudits().add(good);
        SemanticPacketVO failed=new SemanticPacketVO();failed.setPacketId(rejected.getPacketId());failed.setSourceSnapshotSha256(rejected.getSourceSnapshotSha256());failed.setStatus("failed");failed.setFailureKind(failureKind);failed.setError(partial.getModelFailure());failed.setActualGatewayCallStarted(true);failed.setSubmittedChunkIds(Collections.singletonList(chunks.get(1).getId()));
        SemanticAttemptVO attempt=new SemanticAttemptVO();attempt.setOrdinal(1);attempt.setStatus("failed");attempt.setValidationReason(failureKind);failed.getAttempts().add(attempt);topic.getPacketAudits().add(failed);
        SemanticSourceRequestVO request=new SemanticSourceRequestVO();request.setRequestId("failed-source-request");request.setPacketId(failed.getPacketId());request.setReviewExecutionStatus("failed");request.setRequiredChunkIds(failed.getSubmittedChunkIds());topic.getSourceRequests().add(request);
        return partial;
    }
    private VettingRun run(VettingJobVO job){return runs.findById(job.getId()).orElseThrow(AssertionError::new);}
    @Test void committedPartialMergeRetainsHistoricalRowsAndExactExistingHumanDisposition() {
        SourceDocument s=source(Arrays.asList("The original register shall be retained.","The Contractor shall keep an inspection log."));
        List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(s));
        Candidate same=boundCandidate(s,chunks.get(0),"unchanged-accepted-packet"),other=boundCandidate(s,chunks.get(1),"old-other-packet");
        when(semantic.reviewWithProgress(anyString(),eq(s.getProjectId()),anyList(),eq("en"),any(ReviewProgress.class),anyList(),any(Runnable.class))).thenReturn(result(same,other));
        service.run(s.getProjectId(),"en");List<FindingVO> before=service.listFindings(s.getProjectId(),null,null,null,null,null);
        FindingVO original=before.stream().filter(f->same.getPacketId().equals(f.getEvidence().get(0).getPacketId())).findFirst().get();
        FindingVO historical=before.stream().filter(f->other.getPacketId().equals(f.getEvidence().get(0).getPacketId())).findFirst().get();
        VettingHumanReviewFixture.updateReview(service,s.getProjectId(),original.getCode(),new ReviewUpdateRequest("Keep the accepted finding remark","Saved action",false));
        VettingHumanReviewFixture.updateStatus(service,s.getProjectId(),original.getCode(),"Handled");
        VettingHumanReviewFixture.updateReview(service,s.getProjectId(),historical.getCode(),new ReviewUpdateRequest("Keep the historical remark","Historical action",true));
        Candidate rejected=boundCandidate(s,chunks.get(1),"new-failed-packet");Result partial=failedResult(same,rejected,chunks,"transport_failure");
        when(semantic.reviewWithProgress(anyString(),eq(s.getProjectId()),anyList(),eq("en"),any(ReviewProgress.class),anyList(),any(Runnable.class))).thenReturn(partial);
        assertThrows(com.consense.common.BizException.class,()->service.run(s.getProjectId(),"en"));
        List<FindingVO> current=service.listFindings(s.getProjectId(),null,null,null,null,null);assertEquals(1,current.size());
        assertEquals(original.getCode(),current.get(0).getCode());assertEquals("Handled",current.get(0).getStatus());
        assertEquals("Keep the accepted finding remark",current.get(0).getReviewRemarks());assertEquals("Saved action",current.get(0).getActionTaken());
        VettingFinding retained=findings.findByProjectIdAndCode(s.getProjectId(),historical.getCode()).get();assertFalse(retained.getActive());
        assertEquals(historical.getRunId(),retained.getRunId());assertEquals("Keep the historical remark",retained.getReviewRemarks());assertEquals("Historical action",retained.getActionTaken());assertTrue(retained.getAddendumRequired());
        assertEquals(2,findings.findByProjectIdOrderByCodeAsc(s.getProjectId()).size(),"No historical row is deleted by partial publication");
    }
    @Test void legacyAndRuleFingerprintsAndEvidenceJsonRemainByteCompatibleButPartialScopeIsRejected() {
        SourceDocument s=source();Candidate c=candidate(s,"A");FindingEvidence e=c.getEvidence().get(0);c.setPacketId(null);c.setSourceSnapshotSha256(null);e.setPacketId(null);e.setPacketSourceSnapshotSha256(null);String legacyJson=JsonUtils.write(e);assertFalse(legacyJson.contains("packetId"));assertFalse(legacyJson.contains("packetSourceSnapshotSha256"));String side=s.getFileName().toLowerCase(Locale.ROOT)+"|"+VettingCorpus.sourceHash(s)+"|body/fixture|"+VettingCorpus.normalize(s.getTextContent());String expected=VettingCorpus.hash(c.getRuleId()+"\n"+side);assertEquals(expected,VettingService.fingerprint(c));assertEquals(legacyJson,JsonUtils.write(JsonUtils.read(legacyJson,FindingEvidence.class)));c.setSource("rule");c.setPacketId("ignored-rule-metadata");assertEquals(expected,VettingService.fingerprint(c));c.setSource("model");assertThrows(IllegalArgumentException.class,()->VettingService.fingerprint(c));
    }
    @Test void currentRunCannotReuseLegacyFingerprintWhenAnAcceptedCandidateHasNoBusinessMetadata() {
        SourceDocument s=source();Candidate c=candidate(s,"current-null-metadata");String historical=VettingService.fingerprint(c);
        assertNull(c.getBusinessDetails());assertNull(c.getPromptSupplementSha256());
        when(semantic.reviewWithProgress(anyString(),eq(s.getProjectId()),anyList(),eq("en"),any(ReviewProgress.class),anyList(),any(Runnable.class))).thenReturn(result(c));
        service.run(s.getProjectId(),"en");FindingVO saved=service.listFindings(s.getProjectId(),null,null,null,null,null).get(0);
        assertEquals(VettingPromptPolicy.supplementSha256(""),c.getPromptSupplementSha256());
        assertNotEquals(historical,saved.getFingerprint(),"A current worker always binds policy even when a legacy-shaped fixture has no business fields");
        assertEquals(VettingService.fingerprint(c),saved.getFingerprint());
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
