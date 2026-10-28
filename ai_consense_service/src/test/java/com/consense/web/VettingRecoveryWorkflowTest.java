package com.consense.web;

import com.consense.ai.*;
import com.consense.common.*;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParser;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.*;
import com.consense.service.export.EnglishFileOutput;
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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","consense.llm.enabled=false","consense.llm.num-ctx=524288","consense.llm.structured-thinking=false","consense.ocr.enabled=false","consense.vector.provider=memory","consense.vetting.semantic-topics=2","consense.vetting.project-reference-comparisons=0"})
@AutoConfigureMockMvc @ActiveProfiles("h2")
class VettingRecoveryWorkflowTest {
    private static final String ID=UUID.randomUUID().toString();
    @DynamicPropertySource static void isolation(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:h2:mem:recovery_"+ID+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");r.add("consense.storage-root",()->Paths.get("target","recovery-uploads",ID).toAbsolutePath().toString());}
    @Autowired MockMvc http;
    @Autowired VettingService service;
    @Autowired com.consense.service.prompt.PromptService promptService;
    @Autowired ConsenseProperties props;
    @Autowired ProjectRepository projects;
    @Autowired SourceDocumentRepository documents;
    @Autowired org.springframework.jdbc.core.JdbcTemplate fixtureSql;
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
    @SpyBean com.consense.service.drafting.DraftingService drafting;
    @javax.persistence.PersistenceContext javax.persistence.EntityManager entityManager;
    @MockBean AiGateway ai;
    @MockBean VettingRetrievalClient retrieval;
    @MockBean EnglishFileOutput englishFiles;
    private final AtomicReference<List<VettingCorpus.Chunk>> corpus=new AtomicReference<>();
    @AfterEach void waitForOriginalWorkerBeforeResettingSharedMocks() throws Exception {
        // A recovered run can be terminal while its superseded worker still uses shared mocks.
        ExecutorService worker=(ExecutorService)org.springframework.test.util.ReflectionTestUtils.getField(service,"worker");
        worker.submit(()->{}).get(10,TimeUnit.SECONDS);
    }
    @BeforeEach void fixture(){
        // This isolated H2 schema disables Flyway; install the same content-digest write boundary used by live storage.
        fixtureSql.execute((org.springframework.jdbc.core.ConnectionCallback<Void>)connection->{
            try { com.consense.service.sources.SourceContentDigestMigration.install(connection);return null; }
            catch(Exception failure) { throw new java.sql.SQLException(failure); }
        });
        when(englishFiles.json(any(JsonNode.class),anyString(),any(Runnable.class),anySet())).thenAnswer(call->{call.<Runnable>getArgument(2).run();return call.<JsonNode>getArgument(0).deepCopy();});
        when(englishFiles.docx(any(byte[].class),anyString(),any(Runnable.class),anySet())).thenAnswer(call->{call.<Runnable>getArgument(2).run();return call.<byte[]>getArgument(0).clone();});
        // Explicit renderer replacement: durable recovery/content only, not LibreOffice acceptance.
        doAnswer(call->VettingDocxPdfFixture.render(call.getArgument(0))).when(drafting).renderPdf(any(byte[].class));
        promptService.reset(com.consense.service.prompt.PromptCatalog.KEY_VETTING);
        semantic.setInputBudgetObserver(VettingTokenFixture::observe);
        props.getVetting().setSemanticTopics(2);props.getVetting().setProjectReferenceComparisons(0);props.getVetting().setProbeDirectory(null);
        when(ai.available()).thenReturn(true);when(ai.chatModel()).thenReturn("fixture-only");
        doAnswer(call->{corpus.set(call.getArgument(1));return null;}).when(retrieval).index(anyString(),anyList());
        when(retrieval.retrieve(anyString(),anyString(),anyString(),anyList())).thenAnswer(call->((List<VettingCorpus.Chunk>)call.getArgument(3)).stream().filter(c->call.getArgument(2).equals(c.getRole())).collect(Collectors.toList()));
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(VettingSemanticReview.Record.class),any(),anyList())).thenAnswer(call->records("issue",call.getArgument(1)));
    }
    @Test void persistedSupplementIsFrozenForEveryPacketAndBindsTheNextGeneratedRevision() throws Exception {
        String key=com.consense.service.prompt.PromptCatalog.KEY_VETTING;
        String first="AUTHORED_SUPPLEMENT_A: keep the fixture register.",second="AUTHORED_SUPPLEMENT_B: retain the fixture register.";
        promptService.save(key,first,promptService.userTemplate(key));
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);List<String> systems=new CopyOnWriteArrayList<>();AtomicInteger ordinal=new AtomicInteger();
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(VettingSemanticReview.Record.class),any(),anyList())).thenAnswer(call->{
            systems.add(call.getArgument(0));if(ordinal.incrementAndGet()==1){entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));}return records("issue",call.getArgument(1));
        });
        String project=project(),run=start(project);
        assertTrue(entered.await(10,TimeUnit.SECONDS));try{promptService.save(key,second,promptService.userTemplate(key));}finally{release.countDown();}
        JsonNode completed=await(project,run,"COMPLETED");assertEquals(VettingPromptPolicy.supplementSha256(first),completed.path("result").path("promptPolicy").path("supplementSha256").asText());
        assertTrue(systems.size()>=2);for(String system:systems){assertTrue(system.contains(first));assertFalse(system.contains(second));assertTrue(system.indexOf(first)<system.indexOf("R1"),"Mandatory policy follows supplementary configuration");}
        FindingVO original=service.listFindings(project,null,null,null,null,null).get(0);int previous=systems.size();
        promptService.save(key,second,promptService.userTemplate(key)+"\nAUTHORED_USER_TEMPLATE_EXTRA");String effective=promptService.vettingSupplement();
        String next=start(project);JsonNode changed=await(project,next,"COMPLETED");assertEquals(VettingPromptPolicy.supplementSha256(effective),changed.path("result").path("promptPolicy").path("supplementSha256").asText());
        assertTrue(systems.subList(previous,systems.size()).stream().allMatch(system->system.contains(second)&&system.contains("AUTHORED_USER_TEMPLATE_EXTRA")&&!system.contains(first)));
        FindingVO current=service.listFindings(project,null,null,null,null,null).get(0);assertNotEquals(original.getFingerprint(),current.getFingerprint());assertNotEquals(original.getCode(),current.getCode());
    }

    @Test void repairedEmptyWarningSurvivesDurableCoverageAndEnglishReportsWithoutPublishingTheRejectedRaw() throws Exception {
        props.getVetting().setSemanticTopics(1);String project=project();AtomicInteger calls=new AtomicInteger();AtomicReference<String> firstRaw=new AtomicReference<>();
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(VettingSemanticReview.Record.class),any(),anyList())).thenAnswer(call->{
            List<String> raw=call.getArgument(4);
            if(calls.incrementAndGet()==1){List<VettingSemanticReview.Record> rejected=records("issue",call.getArgument(1));rejected.get(0).setPendingFacts(Collections.singletonList("AUTHORED unconfirmed fact"));rejected.get(0).setSuggestedAction("amend");firstRaw.set(JsonUtils.write(rejected));raw.add(firstRaw.get());return rejected;}
            raw.add("[]");return Collections.emptyList();
        });
        String id=start(project);JsonNode completed=await(project,id,"COMPLETED");assertEquals(2,calls.get());assertEquals(0,completed.path("result").path("total").asInt());
        JsonNode packet=completed.path("coverage").path("semanticTopics").get(0).path("packetAudits").get(0);assertEquals("completed_empty",packet.path("status").asText());assertEquals(2,packet.path("attempts").size());assertEquals(firstRaw.get(),packet.path("attempts").get(0).path("rawResponse").asText());assertEquals("[]",packet.path("attempts").get(1).path("rawResponse").asText());
        String marker="修复答复为空数组";assertTrue(completed.path("coverage").path("warnings").toString().contains(marker));
        List<ReliablePacketVO> reliable=checkpoints.reliable(id);assertEquals(1,reliable.size());assertTrue(reliable.get(0).getWarnings().stream().anyMatch(w->w.contains(marker)));assertTrue(reliable.get(0).getCandidates().isEmpty());
        assertTrue(findings.findByProjectIdOrderByCodeAsc(project).isEmpty());byte[] json=http.perform(get("/api/vetting/"+project+"/report.json")).andReturn().getResponse().getContentAsByteArray();JsonNode exported=JsonUtils.parse(new String(json,StandardCharsets.UTF_8));JsonNode originalReport=savedReport(exported);assertTrue(originalReport.path("job").path("coverage").path("warnings").toString().contains(marker));String snapshot=exported.path("sourceReportSnapshotId").asText();
        assertEquals(firstRaw.get(),originalReport.path("job").path("coverage").path("semanticTopics").get(0).path("packetAudits").get(0).path("attempts").get(0).path("rawResponse").asText());
        assertFalse(exported.path("job").path("coverage").path("semanticTopics").get(0).path("packetAudits").get(0).has("attempts"));
        for(String format:Arrays.asList("docx","pdf")){byte[] bytes=http.perform(get("/api/vetting/"+project+"/report."+format)).andReturn().getResponse().getContentAsByteArray();String text;
            if("docx".equals(format)){try(XWPFDocument doc=new XWPFDocument(new ByteArrayInputStream(bytes))){assertEquals(snapshot,doc.getProperties().getCoreProperties().getIdentifier());text=doc.getParagraphs().stream().map(p->p.getText()).collect(Collectors.joining("\n"));}}
            else {try(PDDocument doc=PDDocument.load(bytes)){assertEquals(snapshot,doc.getDocumentInformation().getCustomMetadataValue("ConSenseReportSnapshotId"));text=new PDFTextStripper().getText(doc);}}
            String compact=text.replaceAll("\\s+","");assertTrue(compact.contains("therepairreturnedanemptyarray"));assertTrue(compact.contains("werenotaccepted"));assertTrue(compact.contains("Manuallycheckthesavedoriginalattempts"));assertFalse(text.contains(marker));
        }
        byte[] after=http.perform(get("/api/vetting/"+project+"/report.json")).andReturn().getResponse().getContentAsByteArray();assertArrayEquals(json,after);assertEquals("incomplete",completed.path("coverage").path("reviewCompleteness").asText());assertEquals(2,calls.get());
    }
    @Test void aFailedSecondPacketKeepsTheVerifiedFirstPacketAndRejectsEveryFormalExport() throws Exception {
        String project=project();AtomicInteger calls=new AtomicInteger();
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(VettingSemanticReview.Record.class),any(),anyList())).thenAnswer(call->{if(calls.incrementAndGet()==2)throw new IllegalStateException("fixture model interrupted");return records("issue",call.getArgument(1));});
        String id=start(project);JsonNode saved=await(project,id,"FAILED");
        assertEquals("NEEDS_RERUN",saved.path("recoveryStatus").asText());assertEquals(1,saved.path("reliablePackets").size());
        assertEquals("issue",saved.path("reliablePackets").get(0).path("packet").path("checkState").asText());
        assertEquals(1,saved.path("reliablePackets").get(0).path("candidates").size());assertEquals("incomplete",saved.path("coverage").path("reviewCompleteness").asText());
        assertEquals(2,packets.findByRunIdOrderByUpdatedAtAsc(id).size());assertEquals("not_completed",saved.path("coverage").path("semanticTopics").get(1).path("checkState").asText());
        for(String format:Arrays.asList("json","pdf","docx")){org.springframework.mock.web.MockHttpServletResponse response=http.perform(get("/api/vetting/"+project+"/report."+format)).andReturn().getResponse();assertNull(response.getHeader("Content-Disposition"));assertNotEquals(0,JsonUtils.parse(response.getContentAsString()).path("code").asInt());}
    }
    @Test void allFormatsUseTheSavedSnapshotAndHumanEditsCreateANewRetainedRevision() throws Exception {
        String project=project();String id=start(project);await(project,id,"COMPLETED");
        byte[] json=http.perform(get("/api/vetting/"+project+"/report.json")).andReturn().getResponse().getContentAsByteArray();JsonNode first=JsonUtils.parse(new String(json,StandardCharsets.UTF_8));savedReport(first);String snapshot=first.path("sourceReportSnapshotId").asText();
        byte[] pdf=http.perform(get("/api/vetting/"+project+"/report.pdf")).andReturn().getResponse().getContentAsByteArray();try(PDDocument doc=PDDocument.load(pdf)){assertEquals(snapshot,doc.getDocumentInformation().getCustomMetadataValue("ConSenseReportSnapshotId"));}
        byte[] docx=http.perform(get("/api/vetting/"+project+"/report.docx")).andReturn().getResponse().getContentAsByteArray();try(XWPFDocument doc=new XWPFDocument(new ByteArrayInputStream(docx))){assertEquals(snapshot,doc.getProperties().getCoreProperties().getIdentifier());}
        JsonNode loaded=first.path("findings").get(0);String code=loaded.path("code").asText();http.perform(put("/api/vetting/"+project+"/findings/"+code+"/review").contentType("application/json").content(reviewBody(loaded,"human retained remark"))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(0));
        JsonNode changed=JsonUtils.parse(http.perform(get("/api/vetting/"+project+"/report.json")).andReturn().getResponse().getContentAsString());assertNotEquals(snapshot,changed.path("sourceReportSnapshotId").asText());assertTrue(snapshots.findById(snapshot).isPresent());assertTrue(savedReport(changed).path("findings").toString().contains("human retained remark"));
        SourceDocument source=documents.findByProjectIdOrderByIdAsc(project).get(0);source.setTextContent(source.getTextContent()+" changed source");documents.saveAndFlush(source);
        org.springframework.mock.web.MockHttpServletResponse stale=http.perform(get("/api/vetting/"+project+"/report.json")).andReturn().getResponse();assertNull(stale.getHeader("Content-Disposition"));assertNotEquals(0,JsonUtils.parse(stale.getContentAsString()).path("code").asInt());
    }
    @Test void completedSnapshotUsesStoredTimestampPrecisionBeforeReloadAndDownloads() throws Exception {
        String project=project(),id=start(project);await(project,id,"COMPLETED");
        Instant precise=Instant.parse("2026-10-19T18:33:56.886187100Z");
        String first=new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->{
            VettingRun run=runs.findById(id).orElseThrow(AssertionError::new);run.setStartedAt(precise.minusSeconds(60));run.setFinishedAt(precise);runs.saveAndFlush(run);
            assertEquals(precise,run.getFinishedAt(),"The managed entity still contains the pre-roundtrip value");
            Instant stored=runs.findProgress(project,id).orElseThrow(AssertionError::new).getFinishedAt();
            assertEquals(Instant.parse("2026-10-19T18:33:56.886187Z"),stored,"Actual H2 timestamp(6) roundtrip");
            Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(service);
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(target,"saveReportSnapshot",projects.findById(project).orElseThrow(AssertionError::new),run,service.listFindings(project,null,null,null,null,null));
            String snapshot=run.getReportSnapshotSha256();JsonNode payload=JsonUtils.parse(snapshots.findById(snapshot).orElseThrow(AssertionError::new).getPayloadJson());
            assertEquals(runs.findProgress(project,id).orElseThrow(AssertionError::new).getStartedAt().toString(),payload.path("job").path("startedAt").asText());
            assertEquals(stored.toString(),payload.path("job").path("finishedAt").asText(),"First completion snapshot must use the stored precision");
            assertEquals(precise,run.getFinishedAt(),"Snapshot construction must not rewrite the managed run");entityManager.clear();return snapshot;
        });
        JsonNode reloaded=JsonUtils.parse(http.perform(get("/api/vetting/"+project+"/runs/"+id)).andReturn().getResponse().getContentAsString()).path("data");
        assertEquals(first,reloaded.path("reportSnapshotId").asText());
        JsonNode exported=JsonUtils.parse(http.perform(get("/api/vetting/"+project+"/report.json")).andReturn().getResponse().getContentAsString());savedReport(exported);assertEquals(first,exported.path("sourceReportSnapshotId").asText());
        byte[] pdf=http.perform(get("/api/vetting/"+project+"/report.pdf")).andReturn().getResponse().getContentAsByteArray();try(PDDocument doc=PDDocument.load(pdf)){assertEquals(first,doc.getDocumentInformation().getCustomMetadataValue("ConSenseReportSnapshotId"));}
        byte[] docx=http.perform(get("/api/vetting/"+project+"/report.docx")).andReturn().getResponse().getContentAsByteArray();try(XWPFDocument doc=new XWPFDocument(new ByteArrayInputStream(docx))){assertEquals(first,doc.getProperties().getCoreProperties().getIdentifier());}
        String original=snapshots.findById(first).orElseThrow(AssertionError::new).getPayloadJson();
        VettingRun changed=runs.findById(id).orElseThrow(AssertionError::new);changed.setFinishedAt(precise.plusSeconds(1));runs.saveAndFlush(changed);
        JsonNode changedReport=JsonUtils.parse(http.perform(get("/api/vetting/"+project+"/report.json")).andReturn().getResponse().getContentAsString());
        savedReport(changedReport);assertNotEquals(first,changedReport.path("sourceReportSnapshotId").asText(),"A real persisted time change must produce a new content hash");
        assertEquals(original,snapshots.findById(first).orElseThrow(AssertionError::new).getPayloadJson(),"The earlier snapshot remains immutable");
    }
    @Test void missingForeignOrIncompleteStoredRunCannotCreateASnapshot() throws Exception {
        String project=project(),id=start(project);await(project,id,"COMPLETED");
        VettingRun run=runs.findById(id).orElseThrow(AssertionError::new);long before=snapshots.count();
        for(String mode:Arrays.asList("missing","foreign","incomplete")) {
            VettingRunRepository repository=mock(VettingRunRepository.class);VettingRunRepository.ProgressView stored=mock(VettingRunRepository.ProgressView.class);
            when(stored.getId()).thenReturn("foreign".equals(mode)?"different-run":id);when(stored.getStatus()).thenReturn("incomplete".equals(mode)?"RUNNING":"COMPLETED");
            when(repository.findProgress(project,id)).thenReturn("missing".equals(mode)?Optional.empty():Optional.of(stored));
            VettingService guarded=new VettingService(projectService,storage,parser,props,documents,findings,repository,semantic,writer,transactions,profiles);guarded.setReportSnapshots(snapshots);guarded.setDraftingIdentity(drafting);guarded.setEnglishFileOutput(englishFiles);
            try {
                BizException failure=assertThrows(BizException.class,()->org.springframework.test.util.ReflectionTestUtils.invokeMethod(guarded,"saveReportSnapshot",projects.findById(project).orElseThrow(AssertionError::new),run,service.listFindings(project,null,null,null,null,null)),mode);
                assertEquals(4103,failure.getCode());assertEquals(before,snapshots.count(),"No fallback snapshot is saved: "+mode);
            }finally{guarded.stopWorker();}
        }
    }
    @Test void reconstructedWorkerReusesSavedPacketAndAnExpiredOwnerCannotPublish() throws Exception {
        String project=project();AtomicInteger calls=new AtomicInteger();CountDownLatch waiting=new CountDownLatch(1),release=new CountDownLatch(1);
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(VettingSemanticReview.Record.class),any(),anyList())).thenAnswer(call->{if(calls.incrementAndGet()==2){waiting.countDown();assertTrue(release.await(20,TimeUnit.SECONDS));}return records("issue",call.getArgument(1));});
        String id=start(project);assertTrue(waiting.await(10,TimeUnit.SECONDS));assertEquals(1,checkpoints.reliable(id).size());
        VettingRun interrupted=runs.findById(id).orElseThrow(AssertionError::new);String oldOwner=interrupted.getExecutionOwner();assertFalse(checkpoints.claim(id,oldOwner),"Duplicate recovery dispatch cannot reacquire an active lease");interrupted.setLeaseUntil(Instant.now().minusSeconds(1));runs.saveAndFlush(interrupted);
        VettingService restarted=new VettingService(projectService,storage,parser,props,documents,findings,runs,semantic,writer,transactions,profiles);restarted.setCheckpoints(checkpoints);restarted.setReportSnapshots(snapshots);restarted.setChecklistService(checklist);restarted.setDraftingIdentity(drafting);restarted.setEnglishFileOutput(englishFiles);
        try{restarted.recoverInterruptedRuns();await(project,id,"COMPLETED");assertEquals(3,calls.get(),"Only the uncertain second packet is repeated; the verified first packet is restored");assertEquals(2,checkpoints.reliable(id).size());
            assertThrows(BizException.class,()->checkpoints.renew(id,oldOwner));release.countDown();Thread.sleep(100);assertEquals("COMPLETED",runs.findById(id).orElseThrow(AssertionError::new).getStatus());assertEquals(2,findings.findByProjectIdOrderByCodeAsc(project).size());
        }finally{release.countDown();restarted.stopWorker();}
    }
    @ParameterizedTest @ValueSource(strings={"source","recipe","retrieval_recipe","model","legacy_language_recipe","legacy_attempt_recipe"})
    void incompatibleInputsRequireRerunAndRetainVerifiedPackets(String changed)throws Exception{
        String project=project();AtomicInteger calls=new AtomicInteger();CountDownLatch waiting=new CountDownLatch(1),release=new CountDownLatch(1);
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(VettingSemanticReview.Record.class),any(),anyList())).thenAnswer(call->{if(calls.incrementAndGet()==2){waiting.countDown();assertTrue(release.await(20,TimeUnit.SECONDS));}return records("issue",call.getArgument(1));});
        String id=start(project);assertTrue(waiting.await(10,TimeUnit.SECONDS));assertEquals(1,checkpoints.reliable(id).size());
        VettingRun saved=runs.findById(id).orElseThrow(AssertionError::new);saved.setLeaseUntil(Instant.now().minusSeconds(1));
        if("recipe".equals(changed))saved.setRecoveryContractSha256("incompatible-recipe");
        String legacyPacket=null;
        if("legacy_language_recipe".equals(changed)||"legacy_attempt_recipe".equals(changed)) {
            saved.setRecoveryContractSha256(previousRecipe("legacy_language_recipe".equals(changed)?"vetting-checkpoint-v4-bound-native-profile-minimum-256k":"vetting-checkpoint-v5-bound-native-profile-minimum-256k-language-origin-gate"));
            VettingPacketCheckpoint old=packets.findByRunIdOrderByUpdatedAtAsc(id).get(0);
            VettingCheckpointService.Saved accepted=JsonUtils.read(old.getResultJson(),VettingCheckpointService.Saved.class);
            if("legacy_language_recipe".equals(changed)){accepted.getCandidates().get(0).setType("language");accepted.getCandidates().get(0).setRuleId("semantic-language");}
            else accepted.getPacket().getAttempts().clear();
            legacyPacket=JsonUtils.write(accepted);old.setResultJson(legacyPacket);old.setResultSha256(VettingCorpus.hash(legacyPacket));packets.saveAndFlush(old);
        }
        if("model".equals(changed))saved.setModelIdentityJson(JsonUtils.write(new ModelIdentity("fixture","fixture","different fixture model",null,"test_only")));
        runs.saveAndFlush(saved);String previousRecipe=props.getVetting().getExpectedRetrievalRecipeFingerprint();
        if("retrieval_recipe".equals(changed))props.getVetting().setExpectedRetrievalRecipeFingerprint("changed-retrieval-recipe");
        if("source".equals(changed)){SourceDocument source=documents.findByProjectIdOrderByIdAsc(project).get(0);source.setTextContent(source.getTextContent()+" changed");documents.saveAndFlush(source);}
        VettingService restarted=new VettingService(projectService,storage,parser,props,documents,findings,runs,semantic,writer,transactions,profiles);restarted.setCheckpoints(checkpoints);restarted.setReportSnapshots(snapshots);restarted.setChecklistService(checklist);restarted.setDraftingIdentity(drafting);restarted.setEnglishFileOutput(englishFiles);
        try{restarted.recoverInterruptedRuns();JsonNode failed=await(project,id,"FAILED");assertEquals("NEEDS_RERUN",failed.path("recoveryStatus").asText());assertEquals(1,failed.path("reliablePackets").size());assertEquals(2,calls.get(),"No incompatible model call may resume");
            if(legacyPacket!=null){assertEquals(legacyPacket,packets.findByRunIdOrderByUpdatedAtAsc(id).get(0).getResultJson());assertEquals("legacy_language_recipe".equals(changed)?"language":"risk",failed.path("reliablePackets").get(0).path("candidates").get(0).path("type").asText());}}
        finally{release.countDown();restarted.stopWorker();props.getVetting().setExpectedRetrievalRecipeFingerprint(previousRecipe);}
    }
    /** Exact v4 recipe: retain its checkpoint as historical evidence, never restore it under the origin gate. */
    private String previousRecipe(String prefix){ConsenseProperties.Vetting v=props.getVetting();return VettingCorpus.hash(prefix+"\n"+JsonUtils.write(Arrays.asList(
            v.isSemanticEnabled(),v.getSemanticTopics(),v.getSemanticContextChars(),v.getSemanticContextExpansionChars(),v.getProjectReferenceComparisons(),v.getProjectReferenceContextChars(),
            v.getCandidateLimit(),v.getTopK(),v.isDiffAgainstBaseline(),v.isRequireHybridRetrieval(),v.getRetrievalUrl(),v.getExpectedRetrievalContract(),v.getExpectedRetrievalSignatureVersion(),v.getExpectedRetrievalGranularity(),
            v.getExpectedEmbeddingWindowMaxTokens(),v.getExpectedRerankWindowMaxTokens(),v.getExpectedWindowOverlapTokens(),v.getExpectedStructuralEmbeddingMaxTokens(),v.getExpectedStructuralRerankMaxTokens(),v.isExpectedStructuralRerankStrict(),
            v.getExpectedRetrievalRecipeFingerprint(),v.getExpectedRetrievalSignature(),v.isTokenBudgetObserverEnabled(),v.getTokenBudgetObserverRecipeSha256(),v.getMinimaxCnWire())));}
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
    private List<VettingSemanticReview.Record> records(String assessment,String user){VettingCorpus.Chunk source=corpus.get().stream().filter(c->"tender".equals(c.getRole())).findFirst().orElseThrow(AssertionError::new);VettingSemanticReview.Record r=new VettingSemanticReview.Record();com.consense.service.vetting.VettingCompetitionFixture.details(r);r.setAssessment(assessment);r.setType("risk");r.setSeverity("medium");r.setTitle("Fixture assessment");r.setComment("Fixture record; no live model quality claim");VettingSemanticReview.Quote q=new VettingSemanticReview.Quote();q.setChunkId(VettingTokenFixture.reference(user,source));q.setSide("source");q.setQuote(source.getContent().trim());r.setEvidence(Collections.singletonList(q));return Collections.singletonList(r);}
    @ParameterizedTest @ValueSource(strings={"new_run","parsed_source","raw_bytes","generated_revision","missing_binding"})
    void obsoleteScreenWritesAreRejectedAtomicallyAndKeepHumanHistory(String change)throws Exception{
        String project=project();SourceDocument source=bindOriginal(project);String firstRun=start(project);await(project,firstRun,"COMPLETED");
        JsonNode loaded=JsonUtils.parse(http.perform(get("/api/vetting/"+project+"/findings")).andReturn().getResponse().getContentAsString()).path("data").get(0);String code=loaded.path("code").asText();
        String reviewUrl="/api/vetting/"+project+"/findings/"+code+"/review",statusUrl="/api/vetting/"+project+"/findings/"+code+"/status",checklistUrl="/api/vetting/"+project+"/runs/"+firstRun+"/checklist";
        http.perform(put(reviewUrl).contentType("application/json").content(reviewBody(loaded,"original human remark"))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(0));
        http.perform(post(statusUrl).param("status","Assigned").param("expectedRunId",loaded.path("runId").asText()).param("expectedGeneratedRevisionSha256",loaded.path("generatedRevisionSha256").asText()).param("expectedSourceSnapshotSha256",loaded.path("sourceSnapshotSha256").asText())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(0));
        JsonNode checklistLoaded=JsonUtils.parse(http.perform(get(checklistUrl)).andReturn().getResponse().getContentAsString()).path("data");
        Map<String,Object> checklistBody=new LinkedHashMap<>();checklistBody.put("expectedRunId",firstRun);checklistBody.put("expectedSourceSnapshotSha256",checklistLoaded.path("sourceSnapshotSha256").asText());checklistBody.put("runSnapshotSha256",checklistLoaded.path("runSnapshotSha256").asText());checklistBody.put("items",Collections.singletonList(Map.of("id","APP-I-001","status","fulfilled","remarks","original checklist decision")));
        http.perform(put(checklistUrl).contentType("application/json").content(JsonUtils.write(checklistBody))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(0));
        if("new_run".equals(change)){String newer=start(project);assertNotEquals(firstRun,newer);await(project,newer,"COMPLETED");}
        if("parsed_source".equals(change)){source.setTextContent(source.getTextContent()+" changed scope");documents.saveAndFlush(source);}
        if("generated_revision".equals(change)){VettingFinding changed=findings.findByProjectIdAndCode(project,code).orElseThrow(AssertionError::new);changed.setBodyEn("Changed generated observation");findings.saveAndFlush(changed);}
        if("raw_bytes".equals(change)){Path file=Paths.get(source.getStoragePath());java.nio.file.attribute.FileTime time=Files.getLastModifiedTime(file);byte[] changed=Files.readAllBytes(file);changed[changed.length-1]^=1;Files.write(file,changed);Files.setLastModifiedTime(file,time);}
        String rejectedBody=reviewBody(loaded,"must not overwrite");
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder statusWrite=post(statusUrl).param("status","Handled");
        if("missing_binding".equals(change)){rejectedBody="{\"reviewRemarks\":\"must not overwrite\"}";checklistBody.remove("expectedRunId");checklistBody.remove("expectedSourceSnapshotSha256");}
        else statusWrite.param("expectedRunId",loaded.path("runId").asText()).param("expectedGeneratedRevisionSha256",loaded.path("generatedRevisionSha256").asText()).param("expectedSourceSnapshotSha256",loaded.path("sourceSnapshotSha256").asText());
        http.perform(put(reviewUrl).contentType("application/json").content(rejectedBody)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(4104));
        http.perform(statusWrite).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(4104));
        checklistBody.put("items",Collections.singletonList(Map.of("id","APP-I-001","status","unchecked","remarks","must not overwrite")));if("generated_revision".equals(change))checklistBody.put("expectedSourceSnapshotSha256","wrong displayed source");http.perform(put(checklistUrl).contentType("application/json").content(JsonUtils.write(checklistBody))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(4104));
        VettingFinding retained=findings.findByProjectIdAndCode(project,code).orElseThrow(AssertionError::new);assertEquals("original human remark",retained.getReviewRemarks());assertEquals("reviewed",retained.getActionTaken());assertTrue(retained.getAddendumRequired());assertEquals("Assigned",retained.getStatus());
        JsonNode retainedChecklist=JsonUtils.parse(http.perform(get(checklistUrl)).andReturn().getResponse().getContentAsString()).path("data");assertEquals("fulfilled",retainedChecklist.path("items").get(0).path("status").asText());assertEquals("original checklist decision",retainedChecklist.path("items").get(0).path("remarks").asText());
    }
    @Test void originalHttpRequiresSavedRawIdentityAndRejectsSameSizeSameMtimeReplacement()throws Exception{
        String project=project();SourceDocument source=bindOriginal(project);String id=start(project);await(project,id,"COMPLETED");
        JsonNode finding=JsonUtils.parse(http.perform(get("/api/vetting/"+project+"/findings")).andReturn().getResponse().getContentAsString()).path("data").get(0);
        JsonNode evidence=JsonUtils.parse(http.perform(get("/api/vetting/"+project+"/findings/"+finding.path("code").asText()+"/evidence")).andReturn().getResponse().getContentAsString()).path("data").path("items").get(0);
        String raw=source.getSourceSha256(),revision=VettingCorpus.sourceHash(source),url="/api/vetting/"+project+"/sources/"+source.getId()+"/original";assertEquals(raw,evidence.path("originalSourceSha256").asText());
        byte[] before=Files.readAllBytes(Paths.get(source.getStoragePath()));assertArrayEquals(before,http.perform(get(url).param("sourceHash",revision).param("originalSourceSha256",raw).param("sourceRole","tender")).andReturn().getResponse().getContentAsByteArray());
        http.perform(get(url).param("sourceHash",revision).param("sourceRole","tender")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(4104));
        http.perform(get(url).param("sourceHash",revision).param("originalSourceSha256","wrong original").param("sourceRole","tender")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(4104));
        http.perform(get(url).param("sourceHash",revision).param("originalSourceSha256",raw)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(4104));
        fixtureSql.update("update source_document set review_role=? where id=?","standard",source.getId());assertEquals(revision,VettingCorpus.sourceHash(source));
        http.perform(get(url).param("sourceHash",revision).param("originalSourceSha256",raw).param("sourceRole","tender")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(4104));
        fixtureSql.update("update source_document set review_role=? where id=?","tender",source.getId());
        Path file=Paths.get(source.getStoragePath());java.nio.file.attribute.FileTime time=Files.getLastModifiedTime(file);byte[] replacement=before.clone();replacement[replacement.length-1]^=1;Files.write(file,replacement);Files.setLastModifiedTime(file,time);
        org.springframework.mock.web.MockHttpServletResponse rejected=http.perform(get(url).param("sourceHash",revision).param("originalSourceSha256",raw).param("sourceRole","tender")).andReturn().getResponse();assertEquals(4104,JsonUtils.parse(rejected.getContentAsString()).path("code").asInt());assertNull(rejected.getHeader("Content-Disposition"));assertFalse(Arrays.equals(replacement,rejected.getContentAsByteArray()));
    }
    @Test void uploadedRuleFindingReturnsItsCapturedOriginalThroughPublicHttp()throws Exception{
        String project=project();documents.deleteAll(documents.findByProjectIdOrderByIdAsc(project));when(ai.available()).thenReturn(false);
        byte[] uploaded;try(XWPFDocument doc=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            doc.createParagraph().createRun().setText("Special Conditions of Contract");
            doc.createParagraph().createRun().setText("Contract No: AUTHORED-RECOVERY-TEST");
            doc.createParagraph().createRun().setText("SCC 9.1 Clause 25.2(4) of the General Conditions of Contract is deleted.");
            doc.createParagraph().createRun().setText("SCC 18.1 The contractor shall comply with GCC25.2(4) when applying for interim payment.");
            doc.write(out);uploaded=out.toByteArray();
        }
        http.perform(multipart("/api/vetting/"+project+"/package/upload").file(new org.springframework.mock.web.MockMultipartFile("files","SCC.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",uploaded)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(0));
        String run=start(project);JsonNode completed=await(project,run,"COMPLETED");
        JsonNode listed=JsonUtils.parse(http.perform(get("/api/vetting/"+project+"/findings")).andReturn().getResponse().getContentAsString()).path("data");assertEquals(1,listed.size());JsonNode finding=listed.get(0);assertEquals("rule",finding.path("source").asText());
        assertEquals(VettingPromptPolicy.supplementSha256(""),completed.path("result").path("promptPolicy").path("supplementSha256").asText());
        JsonNode details=finding.path("businessDetails");assertTrue(details.isObject());assertFalse(details.path("factBasis").asText().trim().isEmpty());
        assertEquals("review",details.path("suggestedAction").asText());assertFalse(details.path("pendingFacts").isEmpty());
        List<String> legacySides=new ArrayList<>();for(JsonNode side:finding.path("evidence"))legacySides.add(side.path("fileName").asText().toLowerCase(Locale.ROOT)+"|"+side.path("sourceHash").asText()+"|"+side.path("anchor").asText()+"|"+VettingCorpus.normalize(side.path("quote").asText()));
        Collections.sort(legacySides);assertNotEquals(VettingCorpus.hash("deleted_clause_reference\n"+String.join("\n",legacySides)),finding.path("fingerprint").asText(),"Current rule candidates cannot reuse the old evidence-only identity");
        JsonNode evidence=JsonUtils.parse(http.perform(get("/api/vetting/"+project+"/findings/"+finding.path("code").asText()+"/evidence")).andReturn().getResponse().getContentAsString()).path("data").path("items");assertFalse(evidence.isEmpty());
        String raw=com.consense.service.sources.OerSourceMetadata.originalSha256(uploaded);SourceDocument source=documents.findByProjectIdOrderByIdAsc(project).get(0);
        for(JsonNode item:evidence){assertEquals(String.valueOf(source.getId()),item.path("documentId").asText());assertEquals(VettingCorpus.sourceHash(source),item.path("sourceHash").asText());assertEquals(raw,item.path("originalSourceSha256").asText());
            assertEquals("tender",item.path("sourceRole").asText());assertTrue(item.path("nativePositions").isArray()&&item.path("nativePositions").size()>0,"Rule evidence preserves exact original native blocks, not a matching first phrase");
            assertEquals(raw,item.path("nativePositions").get(0).path("sourceSha256").asText());assertEquals("word/document.xml",item.path("nativePositions").get(0).path("partName").asText());}
        JsonNode item=evidence.get(0);String url="/api/vetting/"+project+"/sources/"+item.path("documentId").asText()+"/original";
        org.springframework.mock.web.MockHttpServletResponse original=http.perform(get(url).param("sourceHash",item.path("sourceHash").asText()).param("originalSourceSha256",item.path("originalSourceSha256").asText()).param("sourceRole","tender")).andReturn().getResponse();assertEquals(200,original.getStatus());assertNotNull(original.getHeader("Content-Disposition"));assertArrayEquals(uploaded,original.getContentAsByteArray());
        assertTrue(org.springframework.http.MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document").isCompatibleWith(org.springframework.http.MediaType.parseMediaType(original.getContentType())));assertEquals(raw,original.getHeader("X-Source-SHA256"));
        http.perform(get(url).param("sourceHash",item.path("sourceHash").asText()).param("originalSourceSha256",raw).param("sourceRole","standard")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(4104));
        Path file=Paths.get(source.getStoragePath());java.nio.file.attribute.FileTime timestamp=Files.getLastModifiedTime(file);byte[] replaced=uploaded.clone();replaced[replaced.length-1]^=1;Files.write(file,replaced);Files.setLastModifiedTime(file,timestamp);
        org.springframework.mock.web.MockHttpServletResponse rejected=http.perform(get(url).param("sourceHash",item.path("sourceHash").asText()).param("originalSourceSha256",item.path("originalSourceSha256").asText()).param("sourceRole","tender")).andReturn().getResponse();assertEquals(4104,JsonUtils.parse(rejected.getContentAsString()).path("code").asInt());assertNull(rejected.getHeader("Content-Disposition"));
        Files.write(file,uploaded);
        VettingService responseBoundary=mock(VettingService.class);
        when(responseBoundary.originalSource(project,source.getId(),item.path("sourceHash").asText(),raw,"tender")).thenAnswer(call->{
            VettingService.OriginalSource captured=service.originalSource(project,source.getId(),item.path("sourceHash").asText(),raw,"tender");
            Files.write(file,replaced);Files.setLastModifiedTime(file,timestamp);return captured;
        });
        MockMvc delayed=http==null?null:org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new VettingController(responseBoundary)).build();
        org.springframework.mock.web.MockHttpServletResponse captured=delayed.perform(get(url).param("sourceHash",item.path("sourceHash").asText()).param("originalSourceSha256",raw).param("sourceRole","tender")).andReturn().getResponse();
        assertArrayEquals(uploaded,captured.getContentAsByteArray(),"Response serialization must use the exact verified bytes even if the backing file changes after service return");assertEquals(raw,captured.getHeader("X-Source-SHA256"));
        Files.write(file,uploaded);
        http.perform(multipart("/api/vetting/"+project+"/package/upload").file(new org.springframework.mock.web.MockMultipartFile("files","SCC.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",uploaded)).param("documentScope","example"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(0));
        http.perform(get(url).param("sourceHash",item.path("sourceHash").asText()).param("originalSourceSha256",raw).param("sourceRole","tender"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(4104));
        verify(ai,never()).completeStructuredJsonList(anyString(),anyString(),eq(VettingSemanticReview.Record.class),any(),anyList());
    }
    private JsonNode savedReport(JsonNode exported){
        assertEquals("2.0",exported.path("schemaVersion").asText());assertEquals("en",exported.path("language").asText());assertTrue(exported.path("presentationOnly").asBoolean());
        String id=exported.path("sourceReportSnapshotId").asText();assertTrue(id.matches("[a-f0-9]{64}"));VettingReportSnapshot saved=snapshots.findById(id).orElseThrow(AssertionError::new);
        assertEquals(id,VettingCorpus.hash("vetting-report-snapshot-v1\n"+saved.getPayloadJson()));assertEquals(saved.getRunId(),exported.path("sourceRunId").asText());assertEquals(saved.getRunId(),exported.path("job").path("id").asText());
        assertTrue(exported.path("diagnostics").path("retainedServerSide").asBoolean());assertFalse(exported.path("job").has("result"));JsonNode original=JsonUtils.parse(saved.getPayloadJson());
        assertEquals(original.path("project").path("id").asText(),exported.path("project").path("id").asText());assertEquals(original.path("job").path("sourceSnapshotSha256").asText(),exported.path("sourceSnapshotSha256").asText());return original;
    }
    private SourceDocument bindOriginal(String project)throws Exception{SourceDocument source=documents.findByProjectIdOrderByIdAsc(project).get(0);Path file=Paths.get(props.getStorageRoot(),project,"SCC.txt");Files.createDirectories(file.getParent());byte[] bytes=source.getTextContent().getBytes(StandardCharsets.UTF_8);Files.write(file,bytes);source.setStoragePath(file.toString());source.setSourceSha256(com.consense.service.sources.OerSourceMetadata.originalSha256(bytes));return documents.saveAndFlush(source);}
    private static String reviewBody(JsonNode loaded,String remarks){return JsonUtils.write(Map.of("expectedRunId",loaded.path("runId").asText(),"expectedGeneratedRevisionSha256",loaded.path("generatedRevisionSha256").asText(),"expectedSourceSnapshotSha256",loaded.path("sourceSnapshotSha256").asText(),"reviewRemarks",remarks,"actionTaken","reviewed","addendumRequired",true));}
    private String project(){
        Project p=new Project();p.setId("recovery-"+UUID.randomUUID());p.setNameEn("Recovery fixture");p.setContractNo("TEST");projects.saveAndFlush(p);
        String body="SCC 9.1 The contractor shall protect the works and maintain insurance throughout the contract.";
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        StorageService.StoredFile original=storage.store(p.getId(),SourceDocument.CATEGORY_VETTING_PACKAGE,
                new org.springframework.mock.web.MockMultipartFile("files","SCC.txt","text/plain",bytes));
        DocumentParser.ParsedDocument parsed=parser.parse("SCC.txt",bytes);
        assertEquals("PARSED",parsed.getParseStatus());
        SourceDocument d=new SourceDocument();d.setProjectId(p.getId());d.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);
        d.setReviewRole("tender");d.setDocumentScope("package");d.setFileKey("SCC");d.setFileName("SCC.txt");
        d.setStoragePath(original.getPath());d.setParseStatus(parsed.getParseStatus());d.setTextContent(parsed.getText());d.setOcrUsed(parsed.isOcrUsed());
        com.consense.service.sources.OerSourceMetadata.captureOrdinary(d,parsed,bytes);
        com.consense.service.sources.OerSourceMetadata.confirmSourceUse(d,true);
        documents.saveAndFlush(d);return p.getId();
    }
    private String start(String project)throws Exception{return JsonUtils.parse(http.perform(post("/api/vetting/"+project+"/runs")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).path("data").path("id").asText();}
    private JsonNode await(String project,String id,String status)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);JsonNode progress;
        String url="/api/vetting/"+project+"/runs/"+id;
        do{
            progress=JsonUtils.parse(http.perform(get(url+"/progress")).andReturn().getResponse().getContentAsString()).path("data");
            if(status.equals(progress.path("status").asText())){
                JsonNode saved=JsonUtils.parse(http.perform(get(url)).andReturn().getResponse().getContentAsString()).path("data");
                assertEquals(status,saved.path("status").asText());return saved;
            }
            Thread.sleep(20);
        }while(System.nanoTime()<deadline);
        StringBuilder workers=new StringBuilder("\nWorker/lock diagnostics:");
        for(java.lang.management.ThreadInfo thread:java.lang.management.ManagementFactory.getThreadMXBean().dumpAllThreads(true,true))
            if(thread.getThreadName().contains("consense-vetting")||thread.getThreadName().contains("scheduling")){
                workers.append('\n').append(thread);
                for(StackTraceElement frame:thread.getStackTrace())workers.append("\n\tat ").append(frame);
            }
        fail("Run did not reach "+status+": "+progress+workers);return null;
    }
}
