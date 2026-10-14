package com.consense.service.advice;

import com.consense.ai.*;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.VettingCorpus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Responses framing over real local HTTP; official endpoint policy remains fixed in the Operation. */
class AdviceResponsesStreamingTest {
    private HttpServer server;
    private AdviceResponsesTransport.Operation operation;
    private String events;
    private final List<String> bodies=new ArrayList<>();
    private final AtomicInteger calls=new AtomicInteger();
    private static final String SOURCE=VettingCorpus.hash("test current sources");
    @BeforeEach void setup()throws IOException {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/responses",exchange->{
            calls.incrementAndGet();bodies.add(read(exchange.getRequestBody()));
            String response=exchange.getRequestURI().getPath().endsWith("input_tokens")
                    ?"{\"object\":\"response.input_tokens\",\"input_tokens\":1000}":events;
            exchange.getResponseHeaders().set("Content-Type",exchange.getRequestURI().getPath().endsWith("input_tokens")?"application/json":"text/event-stream");
            exchange.sendResponseHeaders(200,0);byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
            for(byte value:bytes){exchange.getResponseBody().write(value);exchange.getResponseBody().flush();}exchange.close();
        });server.start();
        String local="http://127.0.0.1:"+server.getAddress().getPort();
        AdviceResponsesTransport.NativeHttp nativeHttp=new AdviceResponsesTransport.NativeHttp();
        AdviceResponsesTransport.WireHttp wire=new AdviceResponsesTransport.WireHttp(){
            public boolean supportsStreaming(){return true;}
            private String local(String endpoint){return endpoint.replace("https://api.minimax.cn",local);}
            public AdviceResponsesTransport.Response post(String endpoint,String body,long timeout,int cap,String auth){return nativeHttp.post(local(endpoint),body,timeout,cap,auth);}
            public AdviceResponsesTransport.Response postCancelable(String endpoint,String body,long timeout,int cap,String auth,Consumer<Runnable> cancel){return nativeHttp.postCancelable(local(endpoint),body,timeout,cap,auth,cancel);}
            public AdviceResponsesTransport.Response postStream(String endpoint,String body,long timeout,int cap,String auth,Consumer<String> event,Consumer<Runnable> cancel){return nativeHttp.postStream(local(endpoint),body,timeout,cap,auth,event,cancel);}
        };
        ConsenseProperties props=new ConsenseProperties();props.getMinimaxCn().setApiKey("synthetic-key");props.getMinimaxCn().setStructuredOpenAiThinkingMode("disabled");
        AdviceModelProperties settings=new AdviceModelProperties();settings.setMinimaxWire("responses");
        settings.getResponses().setEnabled(true);settings.getResponses().setConfiguredContextTokens(32768);settings.getResponses().setSafetyMarginTokens(1024);settings.getResponses().setEstimatePolicy(AdviceResponsesTransport.POLICY);
        LlmProfiles profiles=new LlmProfiles(props,mock(LlmClient.class),mock(LlmClient.class));
        operation=new AdviceResponsesTransport(props,settings,wire).capture(profiles.resolve("minimax-cn"));
        events=stream("{\"sections\":[{\"content\":\"中文 🐴\"}]}");
    }
    @AfterEach void close(){server.stop(0);Thread.interrupted();}
    private static String read(InputStream input)throws IOException{ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[1024];int n;while((n=input.read(b))!=-1)bytes.write(b,0,n);return new String(bytes.toByteArray(),StandardCharsets.UTF_8);}
    private static ObjectNode event(String type){return JsonUtils.mapper().createObjectNode().put("type",type);}
    private static String sse(ObjectNode node){return "event: "+node.path("type").asText()+"\ndata: "+node+"\n\n";}
    private static String stream(String text){
        ObjectNode created=event("response.created");created.putObject("response").put("id","r1").put("object","response").put("model","MiniMax-M3");
        ObjectNode added=event("response.output_item.added").put("output_index",1);added.putObject("item").put("id","m1").put("type","message").put("role","assistant").put("status","in_progress");
        ObjectNode thinking=event("response.reasoning_summary_text.delta").put("delta","private thinking");
        ObjectNode delta=event("response.output_text.delta").put("output_index",1).put("content_index",0).put("item_id","m1").put("delta",text);
        ObjectNode completed=event("response.completed");ObjectNode response=completed.putObject("response");
        response.put("id","r1").put("object","response").put("model","MiniMax-M3").put("status","completed").put("truncation","disabled");
        response.putArray("output").addObject().put("id","m1").put("type","message").put("role","assistant").put("status","completed")
                .putArray("content").addObject().put("type","output_text").put("text",text);
        return sse(created)+sse(added)+sse(thinking)+sse(delta)+sse(completed)+"data: [DONE]\n\n";
    }
    @Test void realEventDeltasKeepFreshCountBindingSourceGuardsAndCancellation() {
        assertTrue(operation.supportsStreaming());AtomicInteger guards=new AtomicInteger(),cancels=new AtomicInteger();List<String> deltas=new ArrayList<>();
        AdviceResponsesTransport.PreparedRequest request=operation.prepareStream("rules","original question and material",SOURCE,0);
        assertTrue(JsonUtils.parse(request.getGenerationJson()).path("stream").asBoolean());
        assertEquals("disabled",JsonUtils.parse(request.getGenerationJson()).path("truncation").asText());
        AdviceResponsesTransport.BudgetResult budget=operation.observe(request,guards::incrementAndGet,r->cancels.incrementAndGet());
        String text=operation.generateStream(request,budget,guards::incrementAndGet,deltas::add,r->cancels.incrementAndGet());
        assertEquals("{\"sections\":[{\"content\":\"中文 🐴\"}]}",text);assertEquals(text,String.join("",deltas));
        assertFalse(String.join("",deltas).contains("private thinking"));
        assertEquals(4,guards.get());assertEquals(2,cancels.get());assertEquals(2,calls.get());
        assertEquals(request.getCounterJson(),bodies.get(0));assertEquals(request.getGenerationJson(),bodies.get(1));
    }
    @Test void incompleteFinalAndChangedOutputAreRejectedWithoutRetry() {
        events=events.replace("\"status\":\"completed\"","\"status\":\"incomplete\"");
        AdviceResponsesTransport.PreparedRequest request=operation.prepareStream("s","u",SOURCE,0);
        AdviceResponsesTransport.BudgetResult budget=operation.observe(request,()->{});
        assertThrows(AdviceResponsesTransport.RequestFailure.class,()->operation.generateStream(request,budget,()->{},d->{},r->{}));assertEquals(2,calls.get());
    }
    @Test void missingFinalOrProviderFailureNeverCompletes() {
        events=events.substring(0,events.indexOf("event: response.completed"));
        AdviceResponsesTransport.PreparedRequest request=operation.prepareStream("s","u",SOURCE,0);
        AdviceResponsesTransport.BudgetResult budget=operation.observe(request,()->{});
        AdviceResponsesTransport.RequestFailure failure=assertThrows(AdviceResponsesTransport.RequestFailure.class,()->operation.generateStream(request,budget,()->{},d->{},r->{}));
        assertEquals("generation_stream_completion_missing",failure.getReason());assertEquals(2,calls.get());
    }
    @Test void synchronousBudgetCannotAuthorizeChangedStreamingWire(){
        AdviceResponsesTransport.PreparedRequest sync=operation.prepare("s","u",SOURCE,0),stream=operation.prepareStream("s","u",SOURCE,0);
        assertNotEquals(sync.getBindingSha256(),stream.getBindingSha256());AdviceResponsesTransport.BudgetResult budget=operation.observe(sync,()->{});
        assertThrows(AdviceResponsesTransport.RequestFailure.class,()->operation.generateStream(stream,budget,()->{},d->{},r->{}));assertEquals(1,calls.get());
    }
    @Test void cancellationPreventsCounterGenerationAndNoFallback(){
        AdviceResponsesTransport.PreparedRequest request=operation.prepareStream("s","u",SOURCE,0);
        assertThrows(AdviceResponsesTransport.RequestFailure.class,()->operation.observe(request,()->{},Runnable::run));assertEquals(0,calls.get());
    }
    @Test void sourceChangeAfterStreamFailsTheWholeOperation(){
        AdviceResponsesTransport.PreparedRequest request=operation.prepareStream("s","u",SOURCE,0);
        AdviceResponsesTransport.BudgetResult budget=operation.observe(request,()->{});AtomicInteger guards=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->operation.generateStream(request,budget,()->{if(guards.incrementAndGet()==2)throw new IllegalStateException("source changed");},d->{},r->{}));
        assertEquals(2,calls.get());
    }
    @Test void overflowedContentIndexCannotWrapIntoValidZero(){
        events=events.replace("\"content_index\":0","\"content_index\":4294967296");
        AdviceResponsesTransport.PreparedRequest request=operation.prepareStream("s","u",SOURCE,0);
        AdviceResponsesTransport.BudgetResult budget=operation.observe(request,()->{});List<String> deltas=new ArrayList<>();
        AdviceResponsesTransport.RequestFailure failure=assertThrows(AdviceResponsesTransport.RequestFailure.class,
                ()->operation.generateStream(request,budget,()->{},deltas::add,r->{}));
        assertEquals("stream_content_identity_invalid",failure.getReason());assertTrue(deltas.isEmpty());assertEquals(2,calls.get());
    }
}
