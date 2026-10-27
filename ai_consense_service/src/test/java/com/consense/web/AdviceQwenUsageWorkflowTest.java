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
@org.springframework.context.annotation.Import(AdviceQwenUsageWorkflowTest.LocalTransportConfiguration.class)
class AdviceQwenUsageWorkflowTest {
    private static final AtomicReference<com.sun.net.httpserver.HttpHandler> LOOPBACK_HANDLER=new AtomicReference<>();
    private static final com.sun.net.httpserver.HttpServer LOOPBACK_SERVER=loopbackServer();
    private static final AtomicInteger ALLOWED_LOCAL_REQUESTS=new AtomicInteger(),BLOCKED_REQUESTS=new AtomicInteger();
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class LocalTransportConfiguration {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        HttpSupport guardedHttpSupport(){return new GuardedHttpSupport();}
    }
    static class GuardedHttpSupport extends HttpSupport {
        GuardedHttpSupport(){
            okhttp3.OkHttpClient original=(okhttp3.OkHttpClient)org.springframework.test.util.ReflectionTestUtils.getField(this,"shared");
            okhttp3.OkHttpClient guarded=original.newBuilder().proxy(java.net.Proxy.NO_PROXY).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).addInterceptor(chain->{
                okhttp3.HttpUrl url=chain.request().url();
                if(!"http".equals(url.scheme())||!"127.0.0.1".equals(url.host())||url.port()!=LOOPBACK_SERVER.getAddress().getPort()||!"/v1/chat/completions".equals(url.encodedPath())||url.query()!=null||!"POST".equals(chain.request().method())){
                    BLOCKED_REQUESTS.incrementAndGet();throw new IllegalStateException("Authored transport blocked an unapproved destination before DNS or connection");
                }
                ALLOWED_LOCAL_REQUESTS.incrementAndGet();return chain.proceed(chain.request());
            }).build();
            org.springframework.test.util.ReflectionTestUtils.setField(this,"shared",guarded);
        }
    }
    private static com.sun.net.httpserver.HttpServer loopbackServer(){try{
        com.sun.net.httpserver.HttpServer server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{com.sun.net.httpserver.HttpHandler handler=LOOPBACK_HANDLER.get();if(handler==null){exchange.sendResponseHeaders(503,-1);exchange.close();}else handler.handle(exchange);});server.start();return server;
    }catch(java.io.IOException failure){throw new ExceptionInInitializerError(failure);}}
    private static String loopbackUrl(){return "http://127.0.0.1:"+LOOPBACK_SERVER.getAddress().getPort();}
    @AfterAll static void stopLoopbackServer(){LOOPBACK_SERVER.stop(0);}
    @Test @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void actualHttpGenerationFailureKeepsSafeCategoryAndPhaseInAdviceAndLedger(org.springframework.boot.test.system.CapturedOutput output)throws Exception {
        AtomicInteger probes=new AtomicInteger(),answers=new AtomicInteger();
        LOOPBACK_HANDLER.set(exchange->{JsonNode body=JsonUtils.mapper().readTree(exchange.getRequestBody());boolean probe=body.path("max_tokens").asInt()==1;String text;
            if(probe){probes.incrementAndGet();text=envelope(body.path("model").asText(),"length","{");}
            else{answers.incrementAndGet();text="{\"error\":{\"code\":\"rate_limit\",\"message\":\"SYNTHETIC_PROVIDER_MESSAGE_SECRET\"}}";}
            byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(probe?200:429,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});
        try{
            doAnswer(c->{assertEquals(loopbackUrl()+"/v1/chat/completions",c.getArgument(0),"Actual HTTP fixture must fail closed before any non-loopback dispatch");return c.callRealMethod();}).when(http).postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any());
            String project=preparedProject(),session=session(project);JsonNode result=JsonUtils.parse(mvc.perform(ask(project,session,"qwen","AUTHORED safe failure classification")).andReturn().getResponse().getContentAsString());
            assertEquals(4203,result.path("code").asInt());assertEquals(1,probes.get());assertEquals(1,answers.get());assertEquals(2,ALLOWED_LOCAL_REQUESTS.get());assertEquals(0,BLOCKED_REQUESTS.get());
            List<QwenGenerationAction> rows=actions.findAll();assertEquals(1,rows.size());JsonNode reservations=JsonUtils.parse(rows.get(0).getReservationsJson());JsonNode answer=null;for(JsonNode row:reservations)if("answer_generation".equals(row.path("kind").asText()))answer=row;
            assertNotNull(answer);System.out.println("AUTHORED_QWEN_ADVICE_FAILURE code="+result.path("code").asInt()+" category="+answer.path("receipt").path("failureKind").asText()+" phase="+answer.path("phase").asText()+" probes="+probes.get()+" answers="+answers.get());
            assertEquals(0,messages.findByProjectIdOrderByIdAsc(project).size());assertFalse(output.getAll().contains("SYNTHETIC_PROVIDER_MESSAGE_SECRET"));assertFalse(output.getAll().contains("AUTHORED-KEY-ONLY"));assertFalse(output.getAll().contains(properties.getQwen().getBaseUrl()));
            assertTrue(result.path("message").asText().contains("rate limited"));assertEquals("HTTP429",answer.path("receipt").path("failureKind").asText());assertEquals("DISPATCH_UNKNOWN",answer.path("state").asText());
        }finally{LOOPBACK_HANDLER.set(null);}
    }
    @Test @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void actualHttpPreflightFailureKeepsSafeCategoryAndSendsNoAnswer(org.springframework.boot.test.system.CapturedOutput output)throws Exception{
        AtomicInteger probes=new AtomicInteger(),answers=new AtomicInteger();
        LOOPBACK_HANDLER.set(exchange->{JsonNode body=JsonUtils.mapper().readTree(exchange.getRequestBody());if(body.path("max_tokens").asInt()==1)probes.incrementAndGet();else answers.incrementAndGet();byte[] bytes="SYNTHETIC_PREFLIGHT_BODY_SECRET".getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(503,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});
        try{
            doAnswer(c->{assertEquals(loopbackUrl()+"/v1/chat/completions",c.getArgument(0));return c.callRealMethod();}).when(http).postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any());
            String project=preparedProject(),session=session(project);JsonNode result=JsonUtils.parse(mvc.perform(ask(project,session,"qwen","AUTHORED preflight failure classification")).andReturn().getResponse().getContentAsString());
            assertEquals(4205,result.path("code").asInt());assertTrue(result.path("message").asText().contains("provider busy"));assertEquals(1,probes.get());assertEquals(0,answers.get());assertEquals(1,ALLOWED_LOCAL_REQUESTS.get());assertEquals(0,BLOCKED_REQUESTS.get());
            List<QwenGenerationAction> rows=actions.findAll();assertEquals(1,rows.size());assertEquals(0,rows.get(0).getAnswers());JsonNode reservation=JsonUtils.parse(rows.get(0).getReservationsJson()).get(0);
            assertEquals("HTTP503",reservation.path("receipt").path("failureKind").asText());assertEquals("DISPATCH_UNKNOWN",reservation.path("state").asText());assertEquals("final",reservation.path("phase").asText());
            assertEquals(0,messages.findByProjectIdOrderByIdAsc(project).size());assertFalse(output.getAll().contains("SYNTHETIC_PREFLIGHT_BODY_SECRET"));assertFalse(output.getAll().contains("AUTHORED-KEY-ONLY"));assertFalse(output.getAll().contains(properties.getQwen().getBaseUrl()));
        }finally{LOOPBACK_HANDLER.set(null);}
    }
    @Test @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void stoppingActualHttpPreflightKeepsCancellationAndNeverStartsAnswer(org.springframework.boot.test.system.CapturedOutput output)throws Exception{
        AtomicInteger probes=new AtomicInteger(),answers=new AtomicInteger();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        LOOPBACK_HANDLER.set(exchange->{JsonNode body=JsonUtils.mapper().readTree(exchange.getRequestBody());if(body.path("max_tokens").asInt()==1)probes.incrementAndGet();else answers.incrementAndGet();entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}finally{exchange.close();}});
        try{
            doAnswer(c->{assertEquals(loopbackUrl()+"/v1/chat/completions",c.getArgument(0));return c.callRealMethod();}).when(http).postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any());
            String project=preparedProject(),session=session(project),id=UUID.randomUUID().toString().replace("-","");
            mvc.perform(post("/api/advice/{id}/ask/stream",project).header("X-ConSense-Llm-Profile","qwen").header("X-ConSense-Advice-Request",id).contentType("application/json").content(body(session,"AUTHORED actual preflight cancellation"))).andExpect(request().asyncStarted());
            assertTrue(entered.await(3,TimeUnit.SECONDS));data(post("/api/advice/{id}/ask/stream/{request}/stop",project,id).param("sessionId",session));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(System.nanoTime()<until&&actions.findAll().stream().anyMatch(a->a.getReservationsJson().contains("DISPATCHING")))Thread.sleep(20);
            List<QwenGenerationAction> rows=actions.findAll();assertEquals(1,rows.size());assertEquals(0,rows.get(0).getAnswers());JsonNode reservation=JsonUtils.parse(rows.get(0).getReservationsJson()).get(0);
            assertEquals("DISPATCH_UNKNOWN",reservation.path("state").asText());assertEquals("CANCELLED",reservation.path("receipt").path("failureKind").asText());assertEquals(1,probes.get());assertEquals(0,answers.get());assertEquals(1,ALLOWED_LOCAL_REQUESTS.get());assertEquals(0,BLOCKED_REQUESTS.get());
            assertEquals(0,messages.findByProjectIdOrderByIdAsc(project).size());assertFalse(output.getAll().contains("Advice model request failed"));assertFalse(output.getAll().contains("AUTHORED-KEY-ONLY"));assertFalse(output.getAll().contains(properties.getQwen().getBaseUrl()));
        }finally{release.countDown();LOOPBACK_HANDLER.set(null);}
    }

    private static final String DB=UUID.randomUUID().toString();
    @DynamicPropertySource static void resources(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:h2:mem:qwen_advice_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");r.add("consense.storage-root",()->java.nio.file.Paths.get("target","qwen-advice-source",DB).toAbsolutePath().toString());r.add("consense.qwen.base-url",AdviceQwenUsageWorkflowTest::loopbackUrl);r.add("consense.qwen-flash.base-url",AdviceQwenUsageWorkflowTest::loopbackUrl);}
    @Autowired MockMvc mvc;@Autowired SourceDocumentRepository sources;@Autowired ChatMessageRepository messages;@Autowired QwenGenerationActionRepository actions;
    @Autowired StorageService storage;@Autowired ConsenseProperties properties;@Autowired QwenGenerationLedger ledger;@Autowired LlmProfiles profiles;
    @MockBean(name="llmClient") LlmClient local;@org.springframework.boot.test.mock.mockito.SpyBean(name="guardedHttpSupport") HttpSupport http;
    private final List<JsonNode> sent=Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger answerNumber=new AtomicInteger();private boolean invalidFirst,wrongProbe;
    @BeforeEach void setup(){
        sent.clear();answerNumber.set(0);invalidFirst=false;wrongProbe=false;actions.deleteAll();
        ALLOWED_LOCAL_REQUESTS.set(0);BLOCKED_REQUESTS.set(0);
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
            assertEquals(2,sent.size()-before,"The final whole-prompt probe includes bounded history and evidence before its answer");
            for(int index=before;index<sent.size();index++){
                String material=sent.get(index).path("messages").get(1).path("content").asText();assertTrue(material.contains("FIRST AUTHORED QUESTION"));assertTrue(material.contains("AUTHORED WHOLE SOURCE"));
            }
            assertEquals(1,sent.get(before).path("max_tokens").asInt());assertEquals(16384,sent.get(before+1).path("max_tokens").asInt());
        }
        assertEquals(4,actions.count());assertEquals(8,actions.findAll().stream().mapToInt(QwenGenerationAction::getTotal).sum());
        List<org.junit.jupiter.api.function.Executable> ledgerAssertions=new ArrayList<>();
        for(QwenGenerationAction action:actions.findAll()){
            System.out.println("AUTHORED_QWEN_HISTORY_LIMITS preflightMax="+action.getMaxPreflights()+" answerMax="+action.getMaxAnswers()+" totalMax="+action.getMaxTotal());
            ledgerAssertions.add(()->assertEquals(6,action.getMaxPreflights(),"Existing whole-Advice preflight ceiling"));
            ledgerAssertions.add(()->assertEquals(4,action.getMaxAnswers(),"Existing whole-Advice answer ceiling"));
            ledgerAssertions.add(()->assertEquals(10,action.getMaxTotal(),"Existing whole-Advice total ceiling"));
            ledgerAssertions.add(()->assertEquals(action.getTotal(),action.getPreflights()+action.getAnswers(),"Paid total must equal the recorded preflights plus answers"));
            for(JsonNode row:JsonUtils.parse(action.getReservationsJson())){
                ledgerAssertions.add(()->assertEquals("COMPLETED",row.path("state").asText(),"Successful authored generation reservation state"));
                ledgerAssertions.add(()->assertTrue(row.path("receipt").path("requestSha256").asText().matches("[a-f0-9]{64}"),"Successful authored generation has a safe request hash"));
                ledgerAssertions.add(()->assertTrue(row.path("receipt").path("responseSha256").asText().matches("[a-f0-9]{64}"),"Successful authored generation has a safe response hash"));
            }
        }
        assertAll("Whole-Advice ledger proof",ledgerAssertions);
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
