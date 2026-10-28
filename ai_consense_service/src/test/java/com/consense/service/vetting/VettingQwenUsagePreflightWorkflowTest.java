package com.consense.service.vetting;

import com.consense.ai.*;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.domain.QwenGenerationAction;
import com.consense.repository.QwenGenerationActionRepository;
import com.consense.service.llm.QwenChatUsagePreflight;
import com.consense.service.sources.SourceContentDigestMigration;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual MVC/profile/async/H2 flow; only the external transport is replaced. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false",
    "consense.ocr.enabled=false","consense.vector.provider=memory","consense.llm.enabled=true",
    "consense.llm.base-url=https://local-fixture.invalid","consense.minimax-cn.enabled=true",
    "consense.minimax-cn.base-url=https://minimax-fixture.invalid","consense.minimax-cn.api-key=MINIMAX_FAKE_ONLY",
    "consense.qwen.enabled=true","consense.qwen.api-key=QWEN_FAKE_ONLY",
    "consense.qwen-flash.enabled=true","consense.qwen-flash.api-key=QWEN_FAKE_ONLY",
    "consense.vetting.semantic-topics=1","consense.vetting.project-reference-comparisons=0"})
@ActiveProfiles("h2") @AutoConfigureMockMvc
class VettingQwenUsagePreflightWorkflowTest {
    static final String DB=UUID.randomUUID().toString();
    @DynamicPropertySource static void resources(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:h2:mem:qwen_usage_vetting_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        r.add("consense.storage-root",()->Paths.get("target","qwen-usage-vetting-uploads",DB).toAbsolutePath().toString());
    }
    @Autowired MockMvc mvc; @Autowired ConsenseProperties props;
    @Autowired QwenGenerationActionRepository actions;
    @Autowired JdbcTemplate fixtureSql;
    @Autowired com.consense.repository.SourceDocumentRepository sources;
    @Autowired com.consense.repository.VettingRunRepository runs; @Autowired VettingService service;
    @Autowired LlmProfiles profiles; @Autowired QwenChatUsagePreflight usage;
    @MockBean(name="llmClient") LlmClient local;
    @MockBean(name="miniMaxChatClient") LlmClient miniMax;
    @MockBean HttpSupport http;
    final List<JsonNode> bodies=Collections.synchronizedList(new ArrayList<>());
    final Queue<String> answers=new ConcurrentLinkedQueue<>();
    volatile long promptTokens; volatile Runnable answerHook;
    volatile CountDownLatch entered,release;

    @BeforeEach void resetWire() {
        fixtureSql.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try { SourceContentDigestMigration.install(connection); return null; }
            catch (Exception failure) { throw new java.sql.SQLException(failure); }
        });
        bodies.clear();answers.clear();promptTokens=100;answerHook=null;entered=null;release=null;
        props.getQwenUsagePreflight().setEnabled(true);
        props.getQwenUsagePreflight().setVettingMaxPreflights(68);
        props.getQwenUsagePreflight().setVettingMaxAnswers(34);
        props.getQwenUsagePreflight().setVettingMaxTotal(102);
        props.getQwen().setStructuredMaxTokens(16384);props.getQwenFlash().setStructuredMaxTokens(16384);
        when(http.postJson(anyString(),anyString(),anyLong())).thenThrow(new IllegalStateException("fixture retrieval unavailable"));
        when(http.postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),nullable(Consumer.class))).thenAnswer(call->{
            assertTrue(call.<String>getArgument(0).endsWith("/v1/chat/completions"));
            assertEquals("Bearer QWEN_FAKE_ONLY",call.<String>getArgument(3));
            JsonNode body=JsonUtils.parse(call.getArgument(1));bodies.add(body);
            List<QwenGenerationAction> dispatching=new ArrayList<>();
            for(QwenGenerationAction a:actions.findAll())for(JsonNode row:JsonUtils.parse(a.getReservationsJson()))if("DISPATCHING".equals(row.path("state").asText()))dispatching.add(a);
            assertEquals(1,dispatching.size(),"The reservation must be committed before every transport invocation");
            assertEquals(Boolean.FALSE,body.path("enable_thinking").booleanValue());
            assertFalse(body.path("stream").asBoolean());
            if("qwen3.8-27b".equals(body.path("model").asText()))assertEquals("json_object",body.path("response_format").path("type").asText());
            else assertFalse(body.has("response_format"));
            boolean probe=body.path("max_tokens").asInt()==1;
            if(probe) {
                CountDownLatch start=entered,blocked=release;
                if(start!=null&&start.getCount()>0){start.countDown();assertTrue(blocked.await(8,TimeUnit.SECONDS));}
            } else {assertEquals(16384,body.path("max_tokens").asInt());Runnable hook=answerHook;if(hook!=null)hook.run();}
            String content=probe?"x":answers.poll();if(content==null)content=body.has("response_format")?"{\"records\":[]}":"[]";
            return JsonUtils.write(Map.of("id",UUID.randomUUID().toString(),"object","chat.completion","model",body.path("model").asText(),
                "choices",Collections.singletonList(Map.of("index",0,"finish_reason",probe?"length":"stop","message",Map.of("role","assistant","content",content))),
                "usage",Map.of("prompt_tokens",promptTokens,"completion_tokens",probe?1:2,"total_tokens",promptTokens+(probe?1:2))));
        });
    }
    @AfterEach void restore() {if(release!=null)release.countDown();props.getQwen().setStructuredMaxTokens(16384);props.getQwenFlash().setStructuredMaxTokens(16384);}

    @Test void selectedQwenWithoutSharedPolicyComponentFailsClosed() {
        assertThrows(IllegalStateException.class,()->VettingLlmOperation.capture(profiles.resolve("qwen"),props,null));
        assertTrue(bodies.isEmpty());verifyNoInteractions(local,miniMax);
    }

    @ParameterizedTest @ValueSource(strings={"qwen","qwen-flash"})
    void selectedQwenProfileUsesPaidNonExactPreflightsAndDurableAnswer(String profile)throws Exception {
        String project=project();upload(project);JsonNode queued=start(project,profile),saved=await(project,queued.path("id").asText());
        assertEquals("COMPLETED",saved.path("status").asText());
        assertEquals(profile,queued.path("modelIdentity").path("profileId").asText());
        assertEquals("qwen-chat-completions",queued.path("modelIdentity").path("provider").asText());
        assertEquals("configured_qwen_usage_preflight_policy",queued.path("modelIdentity").path("identityScope").asText());
        assertEquals(queued.path("modelIdentity"),saved.path("modelIdentity"));assertEquals(queued.path("modelIdentity"),saved.path("result").path("modelIdentity"));
        JsonNode packet=packet(saved),observation=packet.path("inputBudgetMetadata").path("observation");
        assertEquals("provider_estimated_fit",packet.path("inputBudgetStatus").asText());
        assertEquals(QwenChatUsagePreflight.KIND,observation.path("qwenUsageEstimate").path("kind").asText());
        assertFalse(observation.path("completeChatTemplateAndSchemaObserved").asBoolean());
        for(String field:Arrays.asList("tokenizerIdentitySha256","chatTemplateIdentitySha256","effectiveContextIdentitySha256"))assertTrue(observation.path(field).isNull());
        assertFalse(observation.has("providerEstimate"));assertFalse(saved.path("coverage").path("semanticTopics").get(0).path("semanticScopeVerified").asBoolean());
        assertEquals("accepted",packet.path("executionLedger").path("mechanicallyAccepted").asText());
        assertLedger(queued,1,1,Arrays.asList("final","answer"));assertEquals(2,bodies.size());
        for(JsonNode body:bodies)assertEquals("qwen".equals(profile)?"qwen3.8-27b":"qwen3.8-flash",body.path("model").asText());
        verifyNoInteractions(local,miniMax);assertFalse(saved.toString().contains("QWEN_FAKE_ONLY"));
    }
    @Test void malformedFirstAnswerCountsOnlyTheTwoDistinctInputsAndTwoBoundAnswers()throws Exception {
        answers.add("[]}");answers.add("{\"records\":[]}");String project=project();upload(project);JsonNode queued=start(project,"qwen"),saved=await(project,queued.path("id").asText());
        assertEquals("COMPLETED",saved.path("status").asText());JsonNode attempts=packet(saved).path("attempts");assertEquals(2,attempts.size());
        assertEquals("[]}",attempts.get(0).path("rawResponse").asText());assertEquals("structured_output_invalid",attempts.get(0).path("status").asText());assertEquals("decoded",attempts.get(1).path("status").asText());
        JsonNode original=attempts.get(0).path("input"),repair=attempts.get(1).path("input");assertNotEquals(original.path("serializedInputSha256"),repair.path("serializedInputSha256"));
        assertEquals(original.path("sourceSnapshotSha256"),repair.path("sourceSnapshotSha256"));assertEquals(original.path("schema"),repair.path("schema"));
        assertTrue(repair.path("messages").get(1).path("content").asText().endsWith(original.path("messages").get(1).path("content").asText()));
        assertLedger(queued,2,2,Arrays.asList("final","answer","repair","answer"));assertEquals(4,bodies.size());verifyNoInteractions(local,miniMax);
    }
    @Test void twoInvalidAnswersKeepBothFailuresAndNeverConsumeThirdAnswer()throws Exception {
        answers.add("[]}");answers.add("[{}]");answers.add("[]");String project=project();upload(project);JsonNode queued=start(project,"qwen-flash"),saved=await(project,queued.path("id").asText());
        assertEquals("COMPLETED",saved.path("status").asText(),"Exhausted received-output rejections retain the existing partial task result");
        assertEquals("failed",packet(saved).path("status").asText());assertEquals("not_completed",packet(saved).path("checkState").asText());assertFalse(packet(saved).path("semanticScopeVerified").asBoolean());
        assertEquals(0,saved.path("reliablePackets").size());assertEquals(2,packet(saved).path("attempts").size());assertEquals(1,answers.size());
        assertEquals("[]}",packet(saved).path("attempts").get(0).path("rawResponse").asText());assertEquals("[{}]",packet(saved).path("attempts").get(1).path("rawResponse").asText());
        assertLedger(queued,2,2,Arrays.asList("final","answer","repair","answer"));assertEquals(4,bodies.size());verifyNoInteractions(local,miniMax);
    }
    @Test void missingEvidencePatchUsesItsOwnQwenAdmissionAndKeepsTheOriginalAnswerBudget()throws Exception {
        String quote="The minimum amount for interim payment shall be twenty dollars.";
        JsonNode original=JsonUtils.parse(new VettingBusinessShapeRepairTest.Fixture().raw("amend",false,quote));
        ((com.fasterxml.jackson.databind.node.ObjectNode)original.get(0)).remove("evidence");
        String first="{\"records\":"+original+"}",patch="{\"records\":"+VettingEvidenceRepairTest.patch(0,"R1",quote)+"}";
        answers.add(first);answers.add(patch);answers.add("{\"records\":[]}");
        String project=project();upload(project);JsonNode queued=start(project,"qwen"),saved=await(project,queued.path("id").asText());
        assertEquals("COMPLETED",saved.path("status").asText());JsonNode attempts=packet(saved).path("attempts");assertEquals(2,attempts.size());
        assertEquals("SCHEMA_MISMATCH",attempts.get(0).path("validationReason").asText());assertEquals("decoded",attempts.get(1).path("status").asText());
        assertEquals(first,attempts.get(0).path("rawResponse").asText());assertEquals(patch,attempts.get(1).path("rawResponse").asText());
        JsonNode before=attempts.get(0).path("input"),after=attempts.get(1).path("input");
        assertNotEquals(before.path("schema"),after.path("schema"));assertTrue(after.path("schema").path("items").path("properties").has("recordIndex"));
        assertEquals(before.path("sourceSnapshotSha256"),after.path("sourceSnapshotSha256"));
        assertEquals(after.path("serializedInputSha256"),attempts.get(1).path("inputBudget").path("serializedInputSha256"));
        assertEquals(bodies.get(2).path("messages"),bodies.get(3).path("messages"),"Paid preflight and generation must bind the exact patch prompt and schema envelope");
        assertLedger(queued,2,2,Arrays.asList("final","answer","repair","answer"));assertEquals(4,bodies.size());assertEquals(1,answers.size());verifyNoInteractions(local,miniMax);
    }
    @Test void evidencePatchStillCannotPassTheFiniteQwenAdmissionCeiling()throws Exception {
        props.getQwenUsagePreflight().setVettingMaxPreflights(1);props.getQwenUsagePreflight().setVettingMaxAnswers(2);props.getQwenUsagePreflight().setVettingMaxTotal(3);
        JsonNode original=JsonUtils.parse(new VettingBusinessShapeRepairTest.Fixture().raw("amend",false,"The minimum amount for interim payment shall be twenty dollars."));
        ((com.fasterxml.jackson.databind.node.ObjectNode)original.get(0)).remove("evidence");
        answers.add("{\"records\":"+original+"}");answers.add("{\"records\":[]}");
        String project=project();upload(project);JsonNode queued=start(project,"qwen"),saved=await(project,queued.path("id").asText());
        assertEquals("FAILED",saved.path("status").asText());assertEquals(1,packet(saved).path("attempts").size());
        assertLedger(queued,1,1,Arrays.asList("final","answer"));assertEquals(2,bodies.size());assertEquals(1,answers.size());verifyNoInteractions(local,miniMax);
    }
    @Test void durablePreflightCeilingStopsTheNewRepairInputBeforeAnotherPaidDispatch()throws Exception {
        props.getQwenUsagePreflight().setVettingMaxPreflights(1);props.getQwenUsagePreflight().setVettingMaxAnswers(2);props.getQwenUsagePreflight().setVettingMaxTotal(3);
        answers.add("[]}");answers.add("{\"records\":[]}");String project=project();upload(project);JsonNode queued=start(project,"qwen"),saved=await(project,queued.path("id").asText());
        assertEquals("FAILED",saved.path("status").asText());assertEquals("budget_unknown",packet(saved).path("inputBudgetStatus").asText());
        assertEquals(1,packet(saved).path("attempts").size());assertEquals("[]}",packet(saved).path("attempts").get(0).path("rawResponse").asText());
        assertLedger(queued,1,1,Arrays.asList("final","answer"));assertEquals(2,bodies.size());assertEquals(1,answers.size());verifyNoInteractions(local,miniMax);
    }
    @Test void retryCeilingPreservesFirstFailureWithoutRepairOrSecondAnswer()throws Exception {
        props.getQwenUsagePreflight().setVettingMaxPreflights(2);props.getQwenUsagePreflight().setVettingMaxAnswers(2);props.getQwenUsagePreflight().setVettingMaxTotal(2);
        answers.add("[]}");answers.add("{\"records\":[]}");String project=project();upload(project);JsonNode queued=start(project,"qwen"),saved=await(project,queued.path("id").asText());
        assertEquals("FAILED",saved.path("status").asText());assertEquals(1,packet(saved).path("attempts").size());assertEquals("[]}",packet(saved).path("attempts").get(0).path("rawResponse").asText());
        assertLedger(queued,1,1,Arrays.asList("final","answer"));assertEquals(2,bodies.size());assertEquals(1,answers.size());
    }
    @Test void queuedProfilesRemainCapturedAndActiveReuseDoesNotSwitchTheFirstRun()throws Exception {
        String first=project(),second=project();upload(first);upload(second);entered=new CountDownLatch(1);release=new CountDownLatch(1);
        JsonNode queued=start(first,"qwen");assertTrue(entered.await(5,TimeUnit.SECONDS));
        JsonNode reused=start(first,"qwen-flash"),other=start(second,"qwen-flash");assertEquals(queued.path("id"),reused.path("id"));assertEquals(queued.path("modelIdentity"),reused.path("modelIdentity"));
        release.countDown();JsonNode saved=await(first,queued.path("id").asText()),next=await(second,other.path("id").asText());
        assertEquals("COMPLETED",saved.path("status").asText());assertEquals("COMPLETED",next.path("status").asText());assertEquals("qwen",saved.path("modelIdentity").path("profileId").asText());assertEquals("qwen-flash",next.path("modelIdentity").path("profileId").asText());
        assertLedger(queued,1,1,Arrays.asList("final","answer"));assertLedger(other,1,1,Arrays.asList("final","answer"));assertEquals(4,bodies.size());
    }
    @Test void queuedPolicyDriftFailsBothRunsWithoutLeavingTheSecondQueuedForever()throws Exception {
        String first=project(),second=project();upload(first);upload(second);entered=new CountDownLatch(1);release=new CountDownLatch(1);
        JsonNode queued=start(first,"qwen");assertTrue(entered.await(5,TimeUnit.SECONDS));JsonNode other=start(second,"qwen");
        props.getQwenUsagePreflight().setVettingMaxTotal(101);release.countDown();
        JsonNode firstSaved=await(first,queued.path("id").asText()),secondSaved=await(second,other.path("id").asText());
        assertEquals("COMPLETED",firstSaved.path("status").asText());assertEquals("FAILED",secondSaved.path("status").asText());
        assertEquals(0,firstSaved.path("coverage").path("reviewedDocuments").asInt());
        assertEquals(0,firstSaved.path("reliablePackets").size());
        assertEquals("budget_unknown",packet(firstSaved).path("inputBudgetStatus").asText());
        assertEquals("not_submitted",packet(firstSaved).path("executionLedger").path("submitted").asText());
        assertFalse(packet(firstSaved).path("actualGatewayCallStarted").asBoolean());
        QwenGenerationAction a=actions.findById("vetting:"+queued.path("id").asText()).orElseThrow();assertEquals(1,a.getTotal());assertEquals("DISPATCH_UNKNOWN",JsonUtils.parse(a.getReservationsJson()).get(0).path("state").asText());
        assertFalse(actions.findById("vetting:"+other.path("id").asText()).isPresent());assertEquals(1,bodies.size());verifyNoInteractions(local,miniMax);
    }
    @Test void sourceDriftAfterMalformedAnswerKeepsConsumedUnknownAndStopsRetry()throws Exception {
        String project=project();upload(project);answers.add("[]}");answers.add("{\"records\":[]}");answerHook=()->{com.consense.domain.SourceDocument d=sources.findByProjectIdOrderByIdAsc(project).get(0);d.setTextContent(d.getTextContent()+" changed source TEST ONLY");sources.saveAndFlush(d);};
        JsonNode queued=start(project,"qwen"),saved=await(project,queued.path("id").asText());assertEquals("FAILED",saved.path("status").asText());
        QwenGenerationAction a=actions.findById("vetting:"+queued.path("id").asText()).orElseThrow();assertEquals(2,a.getTotal());assertEquals(1,a.getPreflights());assertEquals(1,a.getAnswers());
        assertEquals("DISPATCH_UNKNOWN",JsonUtils.parse(a.getReservationsJson()).get(1).path("state").asText());assertEquals(2,bodies.size());assertEquals(1,answers.size());
    }
    @Test void checkpointRecoveryRetainsDeadlineAndRefusesPaidReplay()throws Exception {
        String project=project();upload(project);JsonNode queued=start(project,"qwen"),saved=await(project,queued.path("id").asText());assertEquals("COMPLETED",saved.path("status").asText());
        String id="vetting:"+queued.path("id").asText();QwenGenerationAction before=actions.findById(id).orElseThrow();JsonNode original=packet(saved).path("attempts").get(0).path("input");
        QwenChatUsagePreflight.Operation operation=usage.capture(profiles.resolve("qwen"),"vetting");String suffix="\nReturn only one JSON object with exactly one key, \"records\". Its value must be an array conforming to this schema:\n"+JsonUtils.write(original.path("schema"));
        String fullSystem=original.path("messages").get(0).path("content").asText();assertTrue(fullSystem.endsWith(suffix));
        VettingInputBudget.Input in=operation.input(fullSystem.substring(0,fullSystem.length()-suffix.length()),original.path("messages").get(1).path("content").asText(),original.path("schema"),original.path("sourceSnapshotSha256").asText());assertEquals(original.path("serializedInputSha256").asText(),in.getSerializedInputSha256());
        try(QwenChatUsagePreflight.Operation.ActionScope scope=operation.openAction(id,()->{})) {IllegalStateException rejected=assertThrows(IllegalStateException.class,()->operation.observe(in,"final",()->{}));assertEquals("qwen_generation_already_reserved_no_replay",rejected.getMessage());}
        QwenGenerationAction after=actions.findById(id).orElseThrow();assertEquals(before.getDeadlineEpochMs(),after.getDeadlineEpochMs());assertEquals(before.getReservationsJson(),after.getReservationsJson());assertEquals(2,bodies.size());
        Map<?,?> pending=(Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(service,"completions");
        for(int i=0;i<100&&pending.containsKey(queued.path("id").asText());i++)Thread.sleep(10);
        assertFalse(pending.containsKey(queued.path("id").asText()));
        com.consense.domain.VettingRun interrupted=runs.findById(queued.path("id").asText()).orElseThrow();interrupted.setStatus("RUNNING");interrupted.setExecutionOwner(null);interrupted.setLeaseUntil(java.time.Instant.EPOCH);interrupted.setFinishedAt(null);runs.saveAndFlush(interrupted);
        service.recoverInterruptedRuns();JsonNode recovered=await(project,queued.path("id").asText());assertEquals("COMPLETED",recovered.path("status").asText(),"The process terminal state is separate from semantic acceptance");
        JsonNode refused=packet(recovered);assertEquals("budget_unknown",refused.path("inputBudgetStatus").asText());assertFalse(refused.path("actualGatewayCallStarted").asBoolean());
        assertEquals("not_submitted",refused.path("executionLedger").path("submitted").asText());assertEquals("not_completed",refused.path("checkState").asText());
        assertEquals("not_submitted",recovered.path("coverage").path("semanticTopics").get(0).path("aggregateReviewStatus").asText());assertFalse(recovered.path("coverage").path("semanticTopics").get(0).path("semanticScopeVerified").asBoolean());
        assertTrue(recovered.path("coverage").path("warnings").toString().contains("未进入模型的切片不代表已经完成语义审查"));
        assertEquals(saved.path("reliablePackets").get(0).path("resultSha256"),recovered.path("reliablePackets").get(0).path("resultSha256"),"Previously reliable packets stay distinct from the refused current semantic submission");
        QwenGenerationAction recoveredAction=actions.findById(id).orElseThrow();assertEquals(before.getDeadlineEpochMs(),recoveredAction.getDeadlineEpochMs());assertEquals(before.getReservationsJson(),recoveredAction.getReservationsJson());assertEquals(2,bodies.size(),"Recovery must refuse duplicate paid generations rather than refund or replay");
    }
    JsonNode packet(JsonNode saved){return saved.path("coverage").path("semanticTopics").get(0).path("packetAudits").get(0);}
    void assertLedger(JsonNode run,int preflights,int answerCount,List<String> phases) {
        QwenGenerationAction a=actions.findById("vetting:"+run.path("id").asText()).orElseThrow();assertEquals(preflights,a.getPreflights());assertEquals(answerCount,a.getAnswers());assertEquals(preflights+answerCount,a.getTotal());
        JsonNode rows=JsonUtils.parse(a.getReservationsJson());assertEquals(phases.size(),rows.size());for(int i=0;i<rows.size();i++){assertEquals(phases.get(i),rows.get(i).path("phase").asText());assertEquals("COMPLETED",rows.get(i).path("state").asText());assertEquals(i+1,rows.get(i).path("ordinal").asInt());assertTrue(rows.get(i).path("receipt").path("requestSha256").asText().matches("[a-f0-9]{64}"));}
    }
    String project()throws Exception {String id="qwen-"+UUID.randomUUID();mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{\"id\":\""+id+"\",\"nameZhHans\":\"TEST ONLY\"}")).andExpect(jsonPath("$.code").value(0));return id;}
    void upload(String id)throws Exception {byte[] bytes;try(XWPFDocument d=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){d.createParagraph().createRun().setText("NTT 1.1 The minimum amount for interim payment shall be twenty dollars.");d.write(out);bytes=out.toByteArray();}mvc.perform(multipart("/api/vetting/{id}/package/upload",id).file(new MockMultipartFile("files","NTT.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",bytes)).param("sourceRole","tender").param("documentScope","package")).andExpect(jsonPath("$.data.parsed").value(1));}
    JsonNode start(String project,String profile)throws Exception {return JsonUtils.parse(mvc.perform(post("/api/vetting/{id}/runs",project).header("X-ConSense-Llm-Profile",profile)).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).path("data");}
    JsonNode await(String project,String run)throws Exception {for(int i=0;i<300;i++){JsonNode job=JsonUtils.parse(mvc.perform(get("/api/vetting/{id}/runs/{run}",project,run)).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).path("data");if(Arrays.asList("COMPLETED","FAILED").contains(job.path("status").asText()))return job;Thread.sleep(20);}throw new AssertionError("bounded Qwen fixture run did not finish");}
}
