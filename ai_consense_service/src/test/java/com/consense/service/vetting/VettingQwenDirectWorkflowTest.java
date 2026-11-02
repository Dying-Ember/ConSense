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
class VettingQwenDirectWorkflowTest {
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

    @ParameterizedTest @ValueSource(strings={"qwen","qwen-flash"})
    void completePacketUsesOneAnswerAndNoRemotePreflight(String profile)throws Exception {
        String project=project();upload(project);JsonNode queued=start(project,profile),saved=await(project,queued.path("id").asText());
        assertEquals("COMPLETED",saved.path("status").asText());
        JsonNode packet=packet(saved);assertEquals("direct_unmeasured",packet.path("inputBudgetStatus").asText());
        assertTrue(packet.path("inputBudgetMetadata").path("observation").isNull());
        assertEquals("token_not_counted_provider_validates",packet.path("inputBudgetMetadata").path("reason").asText());
        assertTrue(packet.path("actualGatewayCallStarted").asBoolean());assertFalse(packet.path("semanticScopeVerified").asBoolean());
        assertEquals(1,bodies.size());assertEquals(16384,bodies.get(0).path("max_tokens").asInt());
        assertLedger(queued,0,1,Collections.singletonList("answer"));verifyNoInteractions(local,miniMax);
    }
    @Test void malformedAnswerRepairUsesOnlyTwoBoundAnswerRequests()throws Exception {
        answers.add("[]}");answers.add("{\"records\":[]}");String project=project();upload(project);
        JsonNode queued=start(project,"qwen"),saved=await(project,queued.path("id").asText());
        JsonNode attempts=packet(saved).path("attempts");assertEquals(2,attempts.size());
        assertEquals("structured_output_invalid",attempts.get(0).path("status").asText());assertEquals("decoded",attempts.get(1).path("status").asText());
        assertNotEquals(attempts.get(0).path("input").path("serializedInputSha256"),attempts.get(1).path("input").path("serializedInputSha256"));
        assertEquals(attempts.get(0).path("input").path("sourceSnapshotSha256"),attempts.get(1).path("input").path("sourceSnapshotSha256"));
        assertEquals(2,bodies.size());for(JsonNode body:bodies)assertEquals(16384,body.path("max_tokens").asInt());
        assertLedger(queued,0,2,Arrays.asList("answer","answer"));
    }
    @Test void providerContextRejectionIsFailedAndNeverAutomaticallyReplayed()throws Exception {
        doAnswer(call->{
            bodies.add(JsonUtils.parse(call.getArgument(1)));throw new com.consense.common.BizException("Qwen chat HTTP 400");
        }).when(http).postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),nullable(Consumer.class));
        String project=project();upload(project);JsonNode queued=start(project,"qwen"),saved=await(project,queued.path("id").asText());
        assertEquals("failed",packet(saved).path("status").asText());assertEquals("not_completed",packet(saved).path("checkState").asText());
        assertEquals(1,bodies.size());assertEquals(16384,bodies.get(0).path("max_tokens").asInt());
        QwenGenerationAction action=actions.findById("vetting:"+queued.path("id").asText()).orElseThrow();
        assertEquals(0,action.getPreflights());assertEquals(1,action.getAnswers());assertEquals(1,action.getTotal());
        assertEquals("DISPATCH_UNKNOWN",JsonUtils.parse(action.getReservationsJson()).get(0).path("state").asText());
    }
    @Test void directMarkerCannotAuthorizeChangedInputOrForgedTokenObservation() {
        QwenChatUsagePreflight.Operation op=usage.capture(profiles.resolve("qwen"),"vetting");
        VettingInputBudget.Input input=op.input("system","user",JsonUtils.mapper().createObjectNode(),"a".repeat(64));
        VettingInputBudget.Result budget=QwenChatUsagePreflight.directVettingBudget(input);
        assertTrue(QwenChatUsagePreflight.directVettingAccepted(input,budget));assertNull(budget.getObservation());
        VettingInputBudget.Input changed=VettingInputBudget.copy(input);changed.setSourceSnapshotSha256("b".repeat(64));
        assertFalse(QwenChatUsagePreflight.directVettingAccepted(changed,budget));
        budget.setObservation(new VettingInputBudget.Observation());assertFalse(QwenChatUsagePreflight.directVettingAccepted(input,budget));
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
