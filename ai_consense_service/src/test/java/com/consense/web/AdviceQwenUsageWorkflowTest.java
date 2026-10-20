package com.consense.web;

import com.consense.ai.*;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.StorageService;
import com.consense.service.advice.*;
import com.consense.service.llm.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC/services/JPA; authored transport only. No provider, engine or shared database. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory",
        "consense.qwen.enabled=true","consense.qwen.api-key=AUTHORED-KEY-ONLY","consense.qwen.structured-max-tokens=16384",
        "consense.qwen-flash.enabled=true","consense.qwen-flash.api-key=AUTHORED-KEY-ONLY","consense.qwen-flash.structured-max-tokens=16384",
        "consense.advice.history-max-exchanges=2","consense.advice.history-max-messages=4","consense.advice.history-max-tokens=1024"})
@ActiveProfiles("h2") @AutoConfigureMockMvc
class AdviceQwenUsageWorkflowTest {
    private static final String DB=UUID.randomUUID().toString();
    @DynamicPropertySource static void resources(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:h2:mem:qwen_advice_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");r.add("consense.storage-root",()->java.nio.file.Paths.get("target","qwen-advice-source",DB).toAbsolutePath().toString());}
    @Autowired MockMvc mvc;@Autowired SourceDocumentRepository sources;@Autowired ChatMessageRepository messages;@Autowired QwenGenerationActionRepository actions;
    @Autowired StorageService storage;@Autowired ConsenseProperties properties;@Autowired QwenGenerationLedger ledger;@Autowired LlmProfiles profiles;
    @MockBean(name="llmClient") LlmClient local;@MockBean HttpSupport http;
    private final List<JsonNode> sent=Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger answerNumber=new AtomicInteger();private boolean invalidFirst,wrongProbe;
    @BeforeEach void setup(){
        sent.clear();answerNumber.set(0);invalidFirst=false;wrongProbe=false;actions.deleteAll();
        when(local.available()).thenReturn(true);when(local.chatModel()).thenReturn("AUTHORED-LOCAL");when(local.embedModel()).thenReturn("AUTHORED-EMBED");
        doAnswer(c->{List<float[]> result=new ArrayList<>();for(Object ignored:c.<List<?>>getArgument(0))result.add(new float[]{1,0});return result;}).when(local).embed(anyList());
        doAnswer(c->{JsonNode body=JsonUtils.parse(c.getArgument(1));sent.add(body);boolean probe=body.path("max_tokens").asInt()==1;
            String text=probe?"{":invalidFirst&&answerNumber.getAndIncrement()==0?"invalid":AdviceBudgetFixture.contract("{\"grounded\":true,\"answer\":\"AUTHORED complete supported answer [1]\",\"citations\":[\"[1]\"]}");
            return envelope(wrongProbe&&probe?"WRONG-MODEL":body.path("model").asText(),probe?"length":"stop",text);})
                .when(http).postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any());
    }
    @Test void bothQwenProfilesUseFullAdviceHistoryFinalAndDurablePaidReservations() throws Exception {
        for(String profile:Arrays.asList("qwen","qwen-flash")) {
            String project=project();source(project);data(post("/api/advice/{id}/index/rebuild",project));String session=session(project);
            JsonNode first=data(ask(project,session,profile,"FIRST AUTHORED QUESTION"));assertTrue(first.path("grounded").asBoolean());assertEquals(profile,first.path("modelIdentity").path("profileId").asText());assertEquals("configured_qwen_usage_preflight_policy",first.path("modelIdentity").path("identityScope").asText());
            int before=sent.size();JsonNode second=data(ask(project,session,profile,"FOLLOW UP AUTHORED QUESTION"));assertTrue(second.path("grounded").asBoolean());
            assertEquals(3,sent.size()-before,"Whole-history probe, full-request probe and actual answer are all generations");
            assertTrue(sent.get(before).path("messages").get(1).path("content").asText().contains("FIRST AUTHORED QUESTION"));
            assertTrue(sent.get(before+1).path("messages").get(1).path("content").asText().contains("AUTHORED WHOLE SOURCE"));
        }
        assertEquals(4,actions.count());assertEquals(10,actions.findAll().stream().mapToInt(QwenGenerationAction::getTotal).sum());
        for(QwenGenerationAction action:actions.findAll()){assertTrue(action.getMaxTotal()==6);assertEquals(action.getTotal(),action.getPreflights()+action.getAnswers());for(JsonNode row:JsonUtils.parse(action.getReservationsJson())){assertEquals("COMPLETED",row.path("state").asText());assertTrue(row.path("receipt").path("requestSha256").asText().matches("[a-f0-9]{64}"));assertTrue(row.path("receipt").path("responseSha256").asText().matches("[a-f0-9]{64}"));}}
        assertWirePairs();
    }
    @Test void repairGetsItsOwnPreflightAndAnswerWhileOriginalMaxTwoRemains() throws Exception {
        invalidFirst=true;String project=preparedProject(),session=session(project);assertTrue(data(ask(project,session,"qwen","REPAIR AUTHORED QUESTION")).path("grounded").asBoolean());
        assertEquals(4,sent.size());QwenGenerationAction a=actions.findAll().get(0);assertEquals(2,a.getPreflights());assertEquals(2,a.getAnswers());assertEquals(4,a.getTotal());
        assertTrue(sent.get(2).path("messages").get(1).path("content").asText().contains("one admitted repair"));assertWirePairs();
    }
    @Test void invalidPreflightLeavesDurableUnknownAndPublishesNoHistory() throws Exception {
        wrongProbe=true;String project=preparedProject(),session=session(project);
        mvc.perform(ask(project,session,"qwen-flash","WRONG PREFLIGHT MODEL")).andExpect(jsonPath("$.code").value(4205));
        assertEquals(1,sent.size());QwenGenerationAction a=actions.findAll().get(0);assertEquals(1,a.getPreflights());assertEquals(0,a.getAnswers());assertEquals("DISPATCH_UNKNOWN",JsonUtils.parse(a.getReservationsJson()).get(0).path("state").asText());assertEquals(0,messages.findByProjectIdOrderByIdAsc(project).size());
        QwenGenerationLedger restarted=new QwenGenerationLedger(actions,transactionManager());
        QwenGenerationLedger.Limits limits=new QwenGenerationLedger.Limits(a.getMaxPreflights(),a.getMaxAnswers(),a.getMaxTotal(),a.getLifetimeMs());restarted.open(a.getId(),a.getIdentitySha256(),limits);
        JsonNode row=JsonUtils.parse(a.getReservationsJson()).get(0);assertThrows(IllegalStateException.class,()->restarted.reserve(a.getId(),a.getIdentitySha256(),row.path("phase").asText(),row.path("inputSha256").asText(),false));assertEquals(1,actions.findById(a.getId()).get().getTotal());
    }
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    private org.springframework.transaction.PlatformTransactionManager transactionManager(){return manager;}
    @Test void successfulSseUsesTheSamePreparedBytesAndRecordsUnavailableUsageHonestly() throws Exception {
        String project=preparedProject(),session=session(project);String answer=AdviceBudgetFixture.contract("{\"grounded\":true,\"answer\":\"AUTHORED complete streamed answer [1]\",\"citations\":[\"[1]\"]}");
        doAnswer(call->{JsonNode body=JsonUtils.parse(call.getArgument(1));sent.add(body);java.util.function.Consumer<String> event=call.getArgument(6);
            com.fasterxml.jackson.databind.node.ObjectNode first=JsonUtils.mapper().createObjectNode();first.put("object","chat.completion.chunk");first.put("model",body.path("model").asText());com.fasterxml.jackson.databind.node.ObjectNode choice=first.putArray("choices").addObject();choice.put("index",0);choice.putNull("finish_reason");choice.putObject("delta").put("role","assistant").put("content",answer);
            com.fasterxml.jackson.databind.node.ObjectNode stop=first.deepCopy();com.fasterxml.jackson.databind.node.ObjectNode stopChoice=(com.fasterxml.jackson.databind.node.ObjectNode)stop.path("choices").get(0);stopChoice.put("finish_reason","stop");stopChoice.set("delta",JsonUtils.mapper().createObjectNode());
            String a=JsonUtils.write(first),b=JsonUtils.write(stop);event.accept(a);event.accept(b);event.accept("[DONE]");return "data: "+a+"\n\ndata: "+b+"\n\ndata: [DONE]\n\n";
        }).when(http).postJsonStream(anyString(),anyString(),anyLong(),anyString(),anyInt(),eq(true),any(),any());
        String requestId=UUID.randomUUID().toString().replace("-","");MvcResult pending=mvc.perform(post("/api/advice/{id}/ask/stream",project).header("X-ConSense-Llm-Profile","qwen-flash").header("X-ConSense-Advice-Request",requestId).contentType("application/json").content(body(session,"STREAMED AUTHORED QUESTION"))).andExpect(request().asyncStarted()).andReturn();
        String wire=mvc.perform(asyncDispatch(pending)).andReturn().getResponse().getContentAsString();assertTrue(wire.contains("event:complete"),wire);assertTrue(wire.contains("AUTHORED complete streamed answer"));assertEquals(2,sent.size());assertFalse(sent.get(0).path("stream").asBoolean());assertTrue(sent.get(1).path("stream").asBoolean());assertWirePairs();
        QwenGenerationAction a=actions.findAll().get(0);assertEquals(1,a.getPreflights());assertEquals(1,a.getAnswers());JsonNode receipt=JsonUtils.parse(a.getReservationsJson()).get(1);assertEquals("COMPLETED",receipt.path("state").asText());assertTrue(receipt.path("receipt").path("responseSha256").asText().matches("[a-f0-9]{64}"));assertFalse(receipt.path("receipt").has("promptTokens"));assertFalse(receipt.path("receipt").has("completionTokens"));assertEquals(2,messages.findByProjectIdOrderByIdAsc(project).size());
    }
    @Test void durableDeadlineAndConcurrentTotalCeilingRejectBeforeDispatch() throws Exception {
        String identity=com.consense.service.vetting.VettingCorpus.hash("AUTHORED LEDGER IDENTITY");String action="direct:"+UUID.randomUUID();QwenGenerationLedger.Limits limits=new QwenGenerationLedger.Limits(1,1,1,60000);ledger.open(action,identity,limits);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        try {List<Future<Boolean>> results=new ArrayList<>();for(String phase:Arrays.asList("final","history"))results.add(pool.submit(()->{ready.countDown();assertTrue(start.await(3,TimeUnit.SECONDS));try{ledger.reserve(action,identity,phase,com.consense.service.vetting.VettingCorpus.hash(phase),false);return true;}catch(IllegalStateException rejected){return false;}}));assertTrue(ready.await(3,TimeUnit.SECONDS));start.countDown();int admitted=0;for(Future<Boolean> result:results)if(result.get(5,TimeUnit.SECONDS))admitted++;assertEquals(1,admitted);assertEquals(1,actions.findById(action).get().getTotal());}finally{start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS));}
        String deadlineAction="deadline:"+UUID.randomUUID();ledger.open(deadlineAction,identity,limits);QwenGenerationLedger.Reservation reserved=ledger.reserve(deadlineAction,identity,"final",com.consense.service.vetting.VettingCorpus.hash("deadline"),false);QwenGenerationAction row=actions.findById(deadlineAction).get();row.setDeadlineEpochMs(System.currentTimeMillis()-1);actions.saveAndFlush(row);assertThrows(IllegalStateException.class,()->ledger.dispatch(reserved));assertEquals("RESERVED",JsonUtils.parse(actions.findById(deadlineAction).get().getReservationsJson()).get(0).path("state").asText());assertEquals(0,sent.size());
    }
    @Test void changedSourceAfterPreflightPreventsAnswerAndPersistsConsumedUnknown() throws Exception {
        String project=preparedProject(),session=session(project);SourceDocument original=sources.findByProjectIdAndCategoryOrderByIdAsc(project,SourceDocument.CATEGORY_VETTING_PACKAGE).get(0);
        doAnswer(c->{JsonNode body=JsonUtils.parse(c.getArgument(1));sent.add(body);original.setTextContent("CHANGED DURING PREFLIGHT");sources.saveAndFlush(original);return envelope(body.path("model").asText(),"length","{");}).when(http).postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any());
        mvc.perform(ask(project,session,"qwen","SOURCE DRIFT AUTHORED")).andExpect(jsonPath("$.code").value(4204));assertEquals(1,sent.size());QwenGenerationAction action=actions.findAll().get(0);assertEquals(0,action.getAnswers());assertEquals("DISPATCH_UNKNOWN",JsonUtils.parse(action.getReservationsJson()).get(0).path("state").asText());assertEquals(0,messages.findByProjectIdOrderByIdAsc(project).size());
    }
    @Test void stoppingQueuedAdviceCancelsThePaidPreflightAndNeverStartsAnswer() throws Exception {
        String project=preparedProject(),session=session(project);CountDownLatch entered=new CountDownLatch(1),cancelled=new CountDownLatch(1);
        doAnswer(c->{JsonNode body=JsonUtils.parse(c.getArgument(1));sent.add(body);java.util.function.Consumer<Runnable> cancel=c.getArgument(5);cancel.accept(cancelled::countDown);entered.countDown();try{assertTrue(cancelled.await(5,TimeUnit.SECONDS));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}throw new com.consense.common.BizException(5001,"AUTHORED cancelled transport");}).when(http).postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any());
        String id=UUID.randomUUID().toString().replace("-","");MvcResult pending=mvc.perform(post("/api/advice/{id}/ask/stream",project).header("X-ConSense-Llm-Profile","qwen").header("X-ConSense-Advice-Request",id).contentType("application/json").content(body(session,"STOP PREFLIGHT AUTHORED"))).andExpect(request().asyncStarted()).andReturn();
        assertTrue(entered.await(5,TimeUnit.SECONDS));data(post("/api/advice/{id}/ask/stream/{request}/stop",project,id).param("sessionId",session));assertTrue(cancelled.await(5,TimeUnit.SECONDS));
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<until&&actions.findAll().stream().anyMatch(a->a.getReservationsJson().contains("DISPATCHING")))Thread.sleep(20);
        assertEquals(1,sent.size());QwenGenerationAction a=actions.findAll().get(0);assertEquals(1,a.getPreflights());assertEquals(0,a.getAnswers());assertEquals("DISPATCH_UNKNOWN",JsonUtils.parse(a.getReservationsJson()).get(0).path("state").asText());assertEquals(0,messages.findByProjectIdOrderByIdAsc(project).size());
    }
    private static String envelope(String model,String finish,String content){com.fasterxml.jackson.databind.node.ObjectNode n=JsonUtils.mapper().createObjectNode();n.put("object","chat.completion");n.put("id",UUID.randomUUID().toString());n.put("model",model);com.fasterxml.jackson.databind.node.ObjectNode c=n.putArray("choices").addObject();c.put("index",0);c.put("finish_reason",finish);c.putObject("message").put("role","assistant").put("content",content);n.putObject("usage").put("prompt_tokens",100).put("completion_tokens",1).put("total_tokens",101);return JsonUtils.write(n);}
    private void assertWirePairs(){for(int i=0;i<sent.size();i++){JsonNode p=sent.get(i);if(p.path("max_tokens").asInt()!=1||i+1>=sent.size()||sent.get(i+1).path("max_tokens").asInt()!=16384)continue;com.fasterxml.jackson.databind.node.ObjectNode probe=p.deepCopy(),actual=sent.get(i+1).deepCopy();probe.remove(Arrays.asList("max_tokens","stream"));actual.remove(Arrays.asList("max_tokens","stream"));assertEquals(probe,actual);}}
    private String preparedProject()throws Exception{String p=project();source(p);data(post("/api/advice/{id}/index/rebuild",p));return p;}
    private String project()throws Exception{String p="qwen-"+UUID.randomUUID();data(post("/api/projects").contentType("application/json").content("{\"id\":\""+p+"\",\"nameZhHans\":\"AUTHORED QWEN TEST\"}"));return p;}
    private String session(String p)throws Exception{return data(post("/api/advice/{id}/sessions",p).contentType("application/json").content("{\"scope\":\"contract\",\"basisScope\":\"project_contract\"}")).path("id").asText();}
    private String body(String session,String question){Map<String,Object> n=new LinkedHashMap<>();n.put("sessionId",session);n.put("scope","contract");n.put("basisScope","project_contract");n.put("question",question);return JsonUtils.write(n);}
    private MockHttpServletRequestBuilder ask(String p,String session,String profile,String question){return post("/api/advice/{id}/ask",p).header("X-ConSense-Llm-Profile",profile).contentType("application/json").content(body(session,question));}
    private JsonNode data(MockHttpServletRequestBuilder request)throws Exception{return JsonUtils.parse(mvc.perform(request).andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).path("data");}
    private SourceDocument source(String p){SourceDocument s=new SourceDocument();s.setProjectId(p);s.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);s.setFileKey("SCC");s.setFileName("SCC authored source.txt");s.setReviewRole("tender");s.setTextContent("AUTHORED WHOLE SOURCE ONLY: The contract requires written notice within seven days.");s.setParseStatus("PARSED");byte[] bytes=s.getTextContent().getBytes(java.nio.charset.StandardCharsets.UTF_8);StorageService.StoredFile original=storage.store(p,SourceDocument.CATEGORY_VETTING_PACKAGE,new org.springframework.mock.web.MockMultipartFile("files",s.getFileName(),"text/plain",bytes));s.setStoragePath(original.getPath());AdviceSourceFixture.bind(s,bytes);return sources.saveAndFlush(s);}
}
