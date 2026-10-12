package com.consense.web;

import com.consense.ai.*;
import com.consense.common.*;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParser;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.*;
import com.consense.service.vetting.*;
import com.consense.web.dto.VettingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Public HTTP, durable H2 records and real bounded worker; model/retrieval are local fixtures. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory","consense.vetting.semantic-topics=2","consense.vetting.project-reference-comparisons=0"})
@AutoConfigureMockMvc @ActiveProfiles("h2")
class VettingRecoveryWorkflowTest {
    private static final String ID=UUID.randomUUID().toString();
    @DynamicPropertySource static void isolation(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:h2:mem:recovery_"+ID+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");r.add("consense.storage-root",()->Paths.get("target","recovery-uploads",ID).toAbsolutePath().toString());}
    @Autowired MockMvc http;
    @Autowired VettingService service;
    @Autowired ConsenseProperties props;
    @Autowired ProjectRepository projects;
    @Autowired SourceDocumentRepository documents;
    @Autowired VettingRunRepository runs;
    @Autowired VettingFindingRepository findings;
    @Autowired VettingPacketCheckpointRepository packets;
    @Autowired VettingReportSnapshotRepository snapshots;
    @Autowired VettingCheckpointService checkpoints;
    @Autowired VettingSemanticReview semantic;
    @Autowired ProjectService projectService;
    @Autowired StorageService storage;
    @Autowired DocumentParser parser;
    @Autowired VettingReportWriter writer;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired LlmProfiles profiles;
    @Autowired VettingChecklistService checklist;
    @MockBean AiGateway ai;
    @MockBean VettingRetrievalClient retrieval;
    private final AtomicReference<List<VettingCorpus.Chunk>> corpus=new AtomicReference<>();
    @BeforeEach void fixture(){
        props.getVetting().setSemanticTopics(2);props.getVetting().setProjectReferenceComparisons(0);props.getVetting().setProbeDirectory(null);
        when(ai.available()).thenReturn(true);when(ai.chatModel()).thenReturn("fixture-only");
        doAnswer(call->{corpus.set(call.getArgument(1));return null;}).when(retrieval).index(anyString(),anyList());
        when(retrieval.retrieve(anyString(),anyString(),anyString(),anyList())).thenAnswer(call->((List<VettingCorpus.Chunk>)call.getArgument(3)).stream().filter(c->call.getArgument(2).equals(c.getRole())).collect(Collectors.toList()));
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(VettingSemanticReview.Record.class),any(),anyList())).thenAnswer(call->records("issue"));
    }
    @Test void aFailedSecondPacketKeepsTheVerifiedFirstPacketAndRejectsEveryFormalExport() throws Exception {
        String project=project();AtomicInteger calls=new AtomicInteger();
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(VettingSemanticReview.Record.class),any(),anyList())).thenAnswer(call->{if(calls.incrementAndGet()==2)throw new IllegalStateException("fixture model interrupted");return records("issue");});
        String id=start(project);JsonNode saved=await(project,id,"FAILED");
        assertEquals("NEEDS_RERUN",saved.path("recoveryStatus").asText());assertEquals(1,saved.path("reliablePackets").size());
        assertEquals("issue",saved.path("reliablePackets").get(0).path("packet").path("checkState").asText());
        assertEquals(1,saved.path("reliablePackets").get(0).path("candidates").size());assertEquals("incomplete",saved.path("coverage").path("reviewCompleteness").asText());
        assertEquals(2,packets.findByRunIdOrderByUpdatedAtAsc(id).size());assertEquals("not_completed",saved.path("coverage").path("semanticTopics").get(1).path("checkState").asText());
        for(String format:Arrays.asList("json","pdf","docx")){org.springframework.mock.web.MockHttpServletResponse response=http.perform(get("/api/vetting/"+project+"/report."+format)).andReturn().getResponse();assertNull(response.getHeader("Content-Disposition"));assertNotEquals(0,JsonUtils.parse(response.getContentAsString()).path("code").asInt());}
    }
    @Test void allFormatsUseTheSavedSnapshotAndHumanEditsCreateANewRetainedRevision() throws Exception {
        String project=project();String id=start(project);await(project,id,"COMPLETED");
        byte[] json=http.perform(get("/api/vetting/"+project+"/report.json")).andReturn().getResponse().getContentAsByteArray();JsonNode first=JsonUtils.parse(new String(json,StandardCharsets.UTF_8));String snapshot=first.path("reportSnapshotId").asText();assertTrue(snapshot.matches("[a-f0-9]{64}"));assertTrue(snapshots.findById(snapshot).isPresent());
        byte[] pdf=http.perform(get("/api/vetting/"+project+"/report.pdf")).andReturn().getResponse().getContentAsByteArray();try(PDDocument doc=PDDocument.load(pdf)){assertTrue(new PDFTextStripper().getText(doc).replaceAll("\\s","").contains(snapshot));}
        byte[] docx=http.perform(get("/api/vetting/"+project+"/report.docx")).andReturn().getResponse().getContentAsByteArray();try(XWPFDocument doc=new XWPFDocument(new ByteArrayInputStream(docx))){assertTrue(doc.getParagraphs().stream().anyMatch(p->p.getText().contains(snapshot)));}
        String code=first.path("findings").get(0).path("code").asText();http.perform(put("/api/vetting/"+project+"/findings/"+code+"/review").contentType("application/json").content("{\"reviewRemarks\":\"human retained remark\",\"actionTaken\":\"reviewed\",\"addendumRequired\":true}"));
        JsonNode changed=JsonUtils.parse(http.perform(get("/api/vetting/"+project+"/report.json")).andReturn().getResponse().getContentAsString());assertNotEquals(snapshot,changed.path("reportSnapshotId").asText());assertTrue(snapshots.findById(snapshot).isPresent());assertTrue(changed.path("findings").toString().contains("human retained remark"));
        SourceDocument source=documents.findByProjectIdOrderByIdAsc(project).get(0);source.setTextContent(source.getTextContent()+" changed source");documents.saveAndFlush(source);
        org.springframework.mock.web.MockHttpServletResponse stale=http.perform(get("/api/vetting/"+project+"/report.json")).andReturn().getResponse();assertNull(stale.getHeader("Content-Disposition"));assertNotEquals(0,JsonUtils.parse(stale.getContentAsString()).path("code").asInt());
    }
    @Test void reconstructedWorkerReusesSavedPacketAndAnExpiredOwnerCannotPublish() throws Exception {
        String project=project();AtomicInteger calls=new AtomicInteger();CountDownLatch waiting=new CountDownLatch(1),release=new CountDownLatch(1);
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(VettingSemanticReview.Record.class),any(),anyList())).thenAnswer(call->{if(calls.incrementAndGet()==2){waiting.countDown();assertTrue(release.await(20,TimeUnit.SECONDS));}return records("issue");});
        String id=start(project);assertTrue(waiting.await(10,TimeUnit.SECONDS));assertEquals(1,checkpoints.reliable(id).size());
        VettingRun interrupted=runs.findById(id).orElseThrow(AssertionError::new);String oldOwner=interrupted.getExecutionOwner();assertFalse(checkpoints.claim(id,oldOwner),"Duplicate recovery dispatch cannot reacquire an active lease");interrupted.setLeaseUntil(Instant.now().minusSeconds(1));runs.saveAndFlush(interrupted);
        VettingService restarted=new VettingService(projectService,storage,parser,props,documents,findings,runs,semantic,writer,transactions,profiles);restarted.setCheckpoints(checkpoints);restarted.setReportSnapshots(snapshots);restarted.setChecklistService(checklist);
        try{restarted.recoverInterruptedRuns();await(project,id,"COMPLETED");assertEquals(3,calls.get(),"Only the uncertain second packet is repeated; the verified first packet is restored");assertEquals(2,checkpoints.reliable(id).size());
            assertThrows(BizException.class,()->checkpoints.renew(id,oldOwner));release.countDown();Thread.sleep(100);assertEquals("COMPLETED",runs.findById(id).orElseThrow(AssertionError::new).getStatus());assertEquals(2,findings.findByProjectIdOrderByCodeAsc(project).size());
        }finally{release.countDown();restarted.stopWorker();}
    }
    @ParameterizedTest @ValueSource(strings={"source","recipe","model"})
    void incompatibleInputsRequireRerunAndRetainVerifiedPackets(String changed)throws Exception{
        String project=project();AtomicInteger calls=new AtomicInteger();CountDownLatch waiting=new CountDownLatch(1),release=new CountDownLatch(1);
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(VettingSemanticReview.Record.class),any(),anyList())).thenAnswer(call->{if(calls.incrementAndGet()==2){waiting.countDown();assertTrue(release.await(20,TimeUnit.SECONDS));}return records("issue");});
        String id=start(project);assertTrue(waiting.await(10,TimeUnit.SECONDS));assertEquals(1,checkpoints.reliable(id).size());
        VettingRun saved=runs.findById(id).orElseThrow(AssertionError::new);saved.setLeaseUntil(Instant.now().minusSeconds(1));
        if("recipe".equals(changed))saved.setRecoveryContractSha256("incompatible-recipe");
        if("model".equals(changed))saved.setModelIdentityJson(JsonUtils.write(new ModelIdentity("fixture","fixture","different fixture model",null,"test_only")));
        runs.saveAndFlush(saved);
        if("source".equals(changed)){SourceDocument source=documents.findByProjectIdOrderByIdAsc(project).get(0);source.setTextContent(source.getTextContent()+" changed");documents.saveAndFlush(source);}
        VettingService restarted=new VettingService(projectService,storage,parser,props,documents,findings,runs,semantic,writer,transactions,profiles);restarted.setCheckpoints(checkpoints);restarted.setReportSnapshots(snapshots);restarted.setChecklistService(checklist);
        try{restarted.recoverInterruptedRuns();JsonNode failed=await(project,id,"FAILED");assertEquals("NEEDS_RERUN",failed.path("recoveryStatus").asText());assertEquals(1,failed.path("reliablePackets").size());assertEquals(2,calls.get(),"No incompatible model call may resume");}
        finally{release.countDown();restarted.stopWorker();}
    }
    @Test void corruptCheckpointCannotSupplyReliableOrFormalResults()throws Exception{
        String project=project();String id=start(project);await(project,id,"COMPLETED");VettingPacketCheckpoint corrupt=packets.findByRunIdOrderByUpdatedAtAsc(id).get(0);corrupt.setResultJson(corrupt.getResultJson()+" ");packets.saveAndFlush(corrupt);
        assertThrows(BizException.class,()->checkpoints.reliable(id));
        for(String format:Arrays.asList("json","pdf","docx")){org.springframework.mock.web.MockHttpServletResponse response=http.perform(get("/api/vetting/"+project+"/report."+format)).andReturn().getResponse();assertNull(response.getHeader("Content-Disposition"));assertNotEquals(0,JsonUtils.parse(response.getContentAsString()).path("code").asInt());}
    }
    @Test void sameSizeOriginalChangeWithPreservedTimestampRejectsAllFormatsAndANewRun()throws Exception{
        String project=project();SourceDocument source=documents.findByProjectIdOrderByIdAsc(project).get(0);Path file=Paths.get(props.getStorageRoot(),project,"SCC.txt");Files.createDirectories(file.getParent());byte[] original=source.getTextContent().getBytes(StandardCharsets.UTF_8);Files.write(file,original);source.setStoragePath(file.toString());source.setSourceSha256(com.consense.service.sources.OerSourceMetadata.originalSha256(original));documents.saveAndFlush(source);
        String id=start(project);await(project,id,"COMPLETED");java.nio.file.attribute.FileTime before=Files.getLastModifiedTime(file);byte[] changed=original.clone();changed[0]=(byte)'X';Files.write(file,changed);Files.setLastModifiedTime(file,before);assertEquals(original.length,Files.size(file));
        for(String format:Arrays.asList("json","pdf","docx")){org.springframework.mock.web.MockHttpServletResponse response=http.perform(get("/api/vetting/"+project+"/report."+format)).andReturn().getResponse();assertNull(response.getHeader("Content-Disposition"));assertNotEquals(0,JsonUtils.parse(response.getContentAsString()).path("code").asInt());}
        assertThrows(BizException.class,()->service.startRun(project,"en"));assertEquals(2,checkpoints.reliable(id).size());
    }
    private List<VettingSemanticReview.Record> records(String assessment){VettingCorpus.Chunk source=corpus.get().stream().filter(c->"tender".equals(c.getRole())).findFirst().orElseThrow(AssertionError::new);VettingSemanticReview.Record r=new VettingSemanticReview.Record();r.setAssessment(assessment);r.setType("risk");r.setSeverity("medium");r.setTitle("Fixture assessment");r.setComment("Fixture record; no live model quality claim");VettingSemanticReview.Quote q=new VettingSemanticReview.Quote();q.setChunkId(source.getId());q.setSide("source");q.setQuote(source.getContent().trim());r.setEvidence(Collections.singletonList(q));return Collections.singletonList(r);}
    private String project(){Project p=new Project();p.setId("recovery-"+UUID.randomUUID());p.setNameEn("Recovery fixture");p.setContractNo("TEST");projects.saveAndFlush(p);SourceDocument d=new SourceDocument();d.setProjectId(p.getId());d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);d.setFileKey("SCC");d.setFileName("SCC.txt");d.setParseStatus("PARSED");d.setTextContent("SCC 9.1 The contractor shall protect the works and maintain insurance throughout the contract.");d.setOcrUsed(false);documents.saveAndFlush(d);return p.getId();}
    private String start(String project)throws Exception{return JsonUtils.parse(http.perform(post("/api/vetting/"+project+"/runs")).andReturn().getResponse().getContentAsString()).path("data").path("id").asText();}
    private JsonNode await(String project,String id,String status)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);JsonNode saved;do{saved=JsonUtils.parse(http.perform(get("/api/vetting/"+project+"/runs/"+id)).andReturn().getResponse().getContentAsString()).path("data");if(status.equals(saved.path("status").asText()))return saved;Thread.sleep(20);}while(System.nanoTime()<deadline);fail("Run did not reach "+status+": "+saved);return null;}
}
