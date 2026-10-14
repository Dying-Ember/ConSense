package com.consense.ai;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real local HTTP streaming with finite synthetic provider responses; no model or business acceptance. */
class ProviderStreamingTest {
    private HttpServer server;
    private final AtomicInteger requests=new AtomicInteger();
    private final AtomicReference<String> sent=new AtomicReference<>();
    private CountDownLatch firstDelta;
    private int waitAfterBytes=-1;
    private final List<LlmClient.ChatTurn> turns=Collections.singletonList(LlmClient.ChatTurn.user("source-bound question"));
    private final com.fasterxml.jackson.databind.JsonNode schema=JsonUtils.parse("{\"type\":\"object\"}");
    @AfterEach void close(){if(server!=null)server.stop(0);Thread.interrupted();}
    private ConsenseProperties.Llm serve(String path,String body,int split) throws IOException {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext(path,exchange->{
            requests.incrementAndGet();sent.set(read(exchange.getRequestBody()));
            exchange.getResponseHeaders().set("Content-Type",path.contains("chat/completions")?"text/event-stream":"application/x-ndjson");
            exchange.sendResponseHeaders(200,0);
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            for(int i=0;i<bytes.length;i+=split){
                int n=Math.min(split,bytes.length-i);exchange.getResponseBody().write(bytes,i,n);exchange.getResponseBody().flush();
                if(waitAfterBytes>0&&i+n>=waitAfterBytes){
                    waitAfterBytes=-1;
                    try {if(!firstDelta.await(2,TimeUnit.SECONDS))throw new IOException("No delta arrived while provider was still generating");}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
                }
            }
            exchange.close();
        });server.start();
        ConsenseProperties.Llm cfg=new ConsenseProperties().getLlm();
        cfg.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());cfg.setTimeoutMs(2000);cfg.setMaxRetry(5);return cfg;
    }
    private static String read(InputStream input)throws IOException {ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[1024];int n;while((n=input.read(b))!=-1)bytes.write(b,0,n);return new String(bytes.toByteArray(),StandardCharsets.UTF_8);}
    private static String sse(String content,String finish){
        com.fasterxml.jackson.databind.node.ObjectNode root=JsonUtils.mapper().createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode choice=root.putArray("choices").addObject();choice.put("index",0);
        choice.putObject("delta").put("content",content).put("reasoning_content","private thinking never published");
        if(finish!=null)choice.put("finish_reason",finish);
        return "data: "+root+"\n\n";
    }
    @Test void openAiForwardsRealContentBeforeProviderCompletionAndSurvivesByteSplits()throws Exception {
        String first="{\"sections\":[{\"content\":\"中文 🐴 { } \\\" \\\\ \"}",last="]}";
        firstDelta=new CountDownLatch(1);waitAfterBytes=sse(first,null).getBytes(StandardCharsets.UTF_8).length;
        ConsenseProperties.Llm cfg=serve("/v1/chat/completions",sse(first,null)+sse(last,"stop")+"data: [DONE]\n\n",1);
        List<String> deltas=new ArrayList<>();AtomicReference<Runnable> cancel=new AtomicReference<>();
        LlmClient client=new OpenAiLlmClient(cfg,new HttpSupport());
        assertTrue(client.supportsStreaming());
        assertEquals(first+last,client.chatStructuredStream(turns,schema,p->{deltas.add(p);firstDelta.countDown();},cancel::set));
        assertEquals(Arrays.asList(first,last),deltas);assertNotNull(cancel.get());assertEquals(1,requests.get());
        assertTrue(JsonUtils.parse(sent.get()).path("stream").booleanValue());
        assertFalse(String.join("",deltas).contains("private thinking"));
    }
    @Test void openAiRequiresNormalFinishAndDoneMarkerWithoutReplay()throws Exception {
        ConsenseProperties.Llm cfg=serve("/v1/chat/completions",sse("{}","length")+"data: [DONE]\n\n",3);
        IncompleteModelResponseException error=assertThrows(IncompleteModelResponseException.class,
                ()->new OpenAiLlmClient(cfg,new HttpSupport()).chatStructuredStream(turns,schema,p->{},r->{}));
        assertEquals(IncompleteModelResponseException.FailureKind.OUTPUT_BUDGET_EXHAUSTED,error.getFailureKind());assertEquals(1,requests.get());
    }
    @Test void openAiTruncatedStreamAndProviderErrorsRemainFailures()throws Exception {
        ConsenseProperties.Llm cfg=serve("/v1/chat/completions",sse("{}",null),2);
        assertThrows(IncompleteModelResponseException.class,()->new OpenAiLlmClient(cfg,new HttpSupport()).chatStructuredStream(turns,schema,p->{},r->{}));
        assertEquals(1,requests.get());
    }
    @Test void ollamaReadsNdjsonAndIgnoresThinking()throws Exception {
        String a="{\"done\":false,\"message\":{\"role\":\"assistant\",\"content\":\"中\",\"thinking\":\"secret thought\"}}\n";
        String b="{\"done\":true,\"done_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"文\"}}\n";
        ConsenseProperties.Llm cfg=serve("/api/chat",a+b,1);List<String> deltas=new ArrayList<>();
        assertEquals("中文",new OllamaLlmClient(cfg,new HttpSupport()).chatStructuredStream(turns,schema,deltas::add,r->{}));
        assertEquals(Arrays.asList("中","文"),deltas);assertTrue(JsonUtils.parse(sent.get()).path("stream").booleanValue());
    }
    @Test void ollamaLengthAndMissingCompletionAreNotAnswers()throws Exception {
        ConsenseProperties.Llm cfg=serve("/api/chat","{\"done\":false,\"message\":{\"role\":\"assistant\",\"content\":\"{}\"}}\n",2);
        assertThrows(IncompleteModelResponseException.class,()->new OllamaLlmClient(cfg,new HttpSupport()).chatStructuredStream(turns,schema,p->{},r->{}));
        assertEquals(1,requests.get());
    }
    @Test void cancellationClosesOnlyTheRegisteredCall()throws Exception {
        ConsenseProperties.Llm cfg=serve("/v1/chat/completions",sse("{}","stop")+"data: [DONE]\n\n",5);
        assertThrows(BizException.class,()->new OpenAiLlmClient(cfg,new HttpSupport()).chatStructuredStream(turns,schema,p->{},Runnable::run));
        assertEquals(0,requests.get());
    }
    @Test void cancellationDuringProviderGenerationClosesTheCurrentSocket()throws Exception {
        String first=sse("{\"sections\":[{}",null);
        firstDelta=new CountDownLatch(1);waitAfterBytes=first.getBytes(StandardCharsets.UTF_8).length;
        ConsenseProperties.Llm cfg=serve("/v1/chat/completions",first+sse("]}","stop")+"data: [DONE]\n\n",1);
        AtomicReference<Runnable> cancel=new AtomicReference<>();
        assertThrows(BizException.class,()->new OpenAiLlmClient(cfg,new HttpSupport()).chatStructuredStream(turns,schema,p->{cancel.get().run();firstDelta.countDown();},cancel::set));
        assertEquals(1,requests.get());
    }
    @Test void overflowedChoiceIndexAndProviderErrorsAreRejectedWithoutReplay()throws Exception {
        String invalid=sse("{}","stop").replace("\"index\":0","\"index\":4294967296");
        ConsenseProperties.Llm cfg=serve("/v1/chat/completions",invalid+"data: [DONE]\n\n",2);
        IncompleteModelResponseException error=assertThrows(IncompleteModelResponseException.class,
                ()->new OpenAiLlmClient(cfg,new HttpSupport()).chatStructuredStream(turns,schema,p->{},r->{}));
        assertEquals(IncompleteModelResponseException.FailureKind.INVALID_CHOICE_COUNT,error.getFailureKind());assertEquals(1,requests.get());
    }
    @Test void readerRejectsByteCapAndMalformedUtf8() {
        assertThrows(IOException.class,()->ProviderStreamReader.read(new ByteArrayInputStream("data: x\n\n".getBytes(StandardCharsets.UTF_8)),3,true,p->{}));
        assertThrows(IOException.class,()->ProviderStreamReader.read(new ByteArrayInputStream(new byte[]{(byte)0xc3,0x28}),20,true,p->{}));
    }
    @Test void secretSplitBetweenDeltasIsRedactedBeforeCallbacks(){
        StringBuilder emitted=new StringBuilder();ProviderStreamReader.SafeDelta safe=new ProviderStreamReader.SafeDelta("synthetic-key",emitted::append);
        safe.accept("begin synthetic-");safe.accept("key end");safe.finish();
        assertEquals("begin [REDACTED] end",emitted.toString());
    }
    @Test void miniMaxAcceptsExplicitStopAtCleanEofWhileGenericOpenAiStillRequiresDone()throws Exception {
        String first="{\"sections\":[",last="]}";firstDelta=new CountDownLatch(1);waitAfterBytes=sse(first,null).getBytes(StandardCharsets.UTF_8).length;
        ConsenseProperties.Llm cfg=serve("/v1/chat/completions",sse(first,null)+sse(last,"stop"),1);List<String> deltas=new ArrayList<>();
        assertEquals(first+last,new MiniMaxChatClient(cfg,new HttpSupport()).chatStructuredStream(turns,schema,p->{deltas.add(p);firstDelta.countDown();},r->{}));assertEquals(Arrays.asList(first,last),deltas);
        assertThrows(IncompleteModelResponseException.class,()->new OpenAiLlmClient(cfg,new HttpSupport()).chatStructuredStream(turns,schema,p->{},r->{}));assertEquals(2,requests.get());
    }
    @Test void miniMaxEmptyAssistantHeaderIsNonterminalAndDoesNotBecomeAnswerText()throws Exception {
        String header="data: {\"choices\":[{\"finish_reason\":\"\",\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}\n\n";
        ConsenseProperties.Llm cfg=serve("/v1/chat/completions",header+sse("{}","stop")+"data: [DONE]\n\n",1);List<String> deltas=new ArrayList<>();
        assertEquals("{}",new MiniMaxChatClient(cfg,new HttpSupport()).chatStructuredStream(turns,schema,deltas::add,r->{}));assertEquals(Collections.singletonList("{}"),deltas);
        assertThrows(IncompleteModelResponseException.class,()->new OpenAiLlmClient(cfg,new HttpSupport()).chatStructuredStream(turns,schema,p->{},r->{}));assertEquals(2,requests.get());
    }
    @ParameterizedTest @ValueSource(strings={"no_stop","length","tool","refusal","error","late","late_done","nontext_finish","malformed"})
    void miniMaxCompatibilityDoesNotTurnFailedOrLateProviderEventsIntoCompletion(String defect)throws Exception {
        String body=sse("{}","stop");
        if("no_stop".equals(defect))body=sse("{}","")+"data: [DONE]\n\n";
        if("length".equals(defect))body=sse("{}","length");
        if("tool".equals(defect))body=body.replace("\"delta\":{","\"delta\":{\"tool_calls\":[{}],");
        if("refusal".equals(defect))body=body.replace("\"delta\":{","\"delta\":{\"refusal\":\"AUTHORED refusal\",");
        if("error".equals(defect))body="data: {\"error\":{\"message\":\"AUTHORED failure\"}}\n\n";
        if("late".equals(defect))body+=sse("late",null);
        if("late_done".equals(defect))body+="data: [DONE]\n\n"+sse("late",null);
        if("nontext_finish".equals(defect))body=body.replace("\"finish_reason\":\"stop\"","\"finish_reason\":{}");
        if("malformed".equals(defect))body="data: {\"choices\":[\n\n";
        ConsenseProperties.Llm cfg=serve("/v1/chat/completions",body,2);IncompleteModelResponseException invalid=assertThrows(IncompleteModelResponseException.class,()->new MiniMaxChatClient(cfg,new HttpSupport()).chatStructuredStream(turns,schema,p->{},r->{}));assertEquals(1,requests.get());
        if("length".equals(defect))assertEquals(IncompleteModelResponseException.FailureKind.OUTPUT_BUDGET_EXHAUSTED,invalid.getFailureKind());
        if("tool".equals(defect))assertEquals(IncompleteModelResponseException.FailureKind.TOOL_CALLS,invalid.getFailureKind());
        if("refusal".equals(defect))assertEquals(IncompleteModelResponseException.FailureKind.REFUSAL,invalid.getFailureKind());
        if("error".equals(defect))assertEquals(IncompleteModelResponseException.FailureKind.PROVIDER_ERROR,invalid.getFailureKind());
    }
    @Test void miniMaxNormalProviderStopDoesNotInventTheMissingAnswerJsonBoundary()throws Exception {
        ConsenseProperties.Llm cfg=serve("/v1/chat/completions",sse("{\"sections\":[","stop"),1);List<com.fasterxml.jackson.databind.JsonNode> closed=new ArrayList<>();StructuredJsonSections sections=new StructuredJsonSections(closed::add);
        assertEquals("{\"sections\":[",new MiniMaxChatClient(cfg,new HttpSupport()).chatStructuredStream(turns,schema,sections::accept,r->{}));
        assertThrows(StructuredJsonSections.InvalidJsonException.class,sections::finish);assertTrue(closed.isEmpty());assertEquals(1,requests.get());
    }
    @Test void miniMaxCompatibilityRetainsPreDispatchAndInFlightCancellation()throws Exception {
        String first=sse("{\"sections\":[",null);firstDelta=new CountDownLatch(1);waitAfterBytes=first.getBytes(StandardCharsets.UTF_8).length;
        ConsenseProperties.Llm cfg=serve("/v1/chat/completions",first+sse("]}","stop"),1);MiniMaxChatClient client=new MiniMaxChatClient(cfg,new HttpSupport());
        assertThrows(BizException.class,()->client.chatStructuredStream(turns,schema,p->{},Runnable::run));assertEquals(0,requests.get());AtomicReference<Runnable> cancel=new AtomicReference<>();
        assertThrows(BizException.class,()->client.chatStructuredStream(turns,schema,p->{cancel.get().run();firstDelta.countDown();},cancel::set));assertEquals(1,requests.get());
    }
}
