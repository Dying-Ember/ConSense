package com.consense.ai;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.junit.jupiter.api.Assertions.*;

/** Finite guarded loopback exchanges; synthetic credentials, no provider or model calls. */
class QwenNoReplayHttpTest {
    private static HttpSupport localOnlyHttp(HttpServer server){
        return localOnlyHttp(server.getAddress().getPort());
    }
    private static HttpSupport localOnlyHttp(int port){
        HttpSupport http=new HttpSupport();okhttp3.OkHttpClient original=(okhttp3.OkHttpClient)org.springframework.test.util.ReflectionTestUtils.getField(http,"shared");
        org.springframework.test.util.ReflectionTestUtils.setField(http,"shared",original.newBuilder().proxy(java.net.Proxy.NO_PROXY).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).addInterceptor(chain->{
            okhttp3.HttpUrl url=chain.request().url();if(!"http".equals(url.scheme())||!"127.0.0.1".equals(url.host())||url.port()!=port||!"/v1/chat/completions".equals(url.encodedPath())||url.query()!=null||!"POST".equals(chain.request().method()))throw new IllegalStateException("Authored transport blocked an unapproved destination before DNS or connection");return chain.proceed(chain.request());
        }).build());return http;
    }
    private static QwenChatClient client(HttpServer server,long timeoutMs){
        ConsenseProperties.Llm cfg=new ConsenseProperties().getQwen();cfg.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
        cfg.setApiKey("SYNTHETIC_TRANSPORT_KEY_SECRET");cfg.setTimeoutMs(timeoutMs);return new QwenChatClient(cfg,localOnlyHttp(server));
    }
    private static List<LlmClient.ChatTurn> authoredTurns(){return Collections.singletonList(LlmClient.ChatTurn.user("SYNTHETIC_TRANSPORT_PROMPT_SECRET"));}
    private static void assertSafeFailure(RuntimeException error,String kind,String phase){
        assertEquals(kind,QwenChatClient.safeUsageFailureKind(error));assertTrue(error instanceof HttpSupport.ModelRequestFailure);
        HttpSupport.ModelRequestFailure failure=(HttpSupport.ModelRequestFailure)error;assertEquals(phase,failure.phase());assertNull(failure.getCause());
        String safe=failure.getMessage()+failure.diagnostics();assertFalse(safe.contains("SECRET"));assertFalse(safe.contains("127.0.0.1"));assertFalse(safe.contains("/v1"));
    }
    @Test void onlyApplicationCallbacksPreserveTheirTypedFailureIncludingTheFinalRedactedTail(){
        ConsenseProperties.Llm cfg=new ConsenseProperties().getQwen();cfg.setApiKey("SYNTHETIC_CALLBACK_KEY_SECRET");HttpSupport http=org.mockito.Mockito.mock(HttpSupport.class);QwenChatClient client=new QwenChatClient(cfg,http);
        AtomicReference<String> content=new AtomicReference<>();
        org.mockito.Mockito.doAnswer(call->{java.util.function.Consumer<String> event=call.getArgument(6);event.accept("{\"model\":\""+cfg.getChatModel()+"\",\"choices\":[{\"index\":0,\"delta\":{\"content\":"+JsonUtils.write(content.get())+"},\"finish_reason\":\"stop\"}]}");event.accept("[DONE]");return "AUTHORED STREAM RECEIPT";})
                .when(http).postJsonStream(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.eq(true),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        for(String text:Arrays.asList("[]","{\"authored\":\""+"A".repeat(128)+"\"}"))for(RuntimeException application:Arrays.asList(new IllegalArgumentException("AUTHORED application validation"),new java.util.concurrent.CancellationException("AUTHORED application stop"),new BizException(4204,"AUTHORED source changed"))){
            content.set(text);java.util.function.Consumer<String> callback=ignored->{throw application;};
            assertSame(application,assertThrows(RuntimeException.class,()->client.chatStructuredStream(authoredTurns(),JsonUtils.mapper().createObjectNode(),callback,null)));
            assertSame(application,assertThrows(RuntimeException.class,()->client.generate(client.prepare(authoredTurns(),JsonUtils.mapper().createObjectNode(),true),callback,null)));
        }
    }
    @Test void nonCallbackProviderRuntimeFailuresStaySanitizedAcrossBothStreamEntryPoints(){
        ConsenseProperties.Llm cfg=new ConsenseProperties().getQwen();cfg.setApiKey("SYNTHETIC_PROVIDER_KEY_SECRET");HttpSupport http=org.mockito.Mockito.mock(HttpSupport.class);QwenChatClient client=new QwenChatClient(cfg,http);
        org.mockito.Mockito.when(http.postJsonStream(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.eq(true),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalArgumentException("SYNTHETIC_PROVIDER_BODY_SECRET "+cfg.getApiKey()));
        for(boolean prepared:new boolean[]{false,true}){
            BizException failure=assertThrows(BizException.class,()->{if(prepared)client.generate(client.prepare(authoredTurns(),JsonUtils.mapper().createObjectNode(),true),ignored->{},null);else client.chatStructuredStream(authoredTurns(),JsonUtils.mapper().createObjectNode(),ignored->{},null);});
            assertFalse(failure.getMessage().contains("SECRET"));assertNull(failure.getCause());assertFalse(failure instanceof HttpSupport.ModelRequestFailure);
        }
    }
    @Test @ExtendWith(OutputCaptureExtension.class)
    void actualHttpStatusesKeepSafeFactsAcrossAllQwenGenerationPaths(CapturedOutput output)throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger calls=new AtomicInteger(),status=new AtomicInteger();
        server.createContext("/v1/chat/completions",exchange->{calls.incrementAndGet();exchange.getRequestBody().close();byte[] body="SYNTHETIC_PROVIDER_BODY_SECRET".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status.get(),body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        try{
            QwenChatClient client=client(server,2000);
            for(int code:new int[]{400,429,503}){
                status.set(code);int before=calls.get();QwenChatClient.PreparedRequest plain=client.prepare(authoredTurns(),JsonUtils.mapper().createObjectNode(),false);
                assertSafeFailure(assertThrows(RuntimeException.class,()->client.usageProbe(plain,null)),"HTTP"+code,"preflight");
                assertSafeFailure(assertThrows(RuntimeException.class,()->client.generate(plain,null,null)),"HTTP"+code,"generation");
                assertSafeFailure(assertThrows(RuntimeException.class,()->client.generate(client.prepare(authoredTurns(),JsonUtils.mapper().createObjectNode(),true),ignored->{},null)),"HTTP"+code,"generation");
                assertSafeFailure(assertThrows(RuntimeException.class,()->client.chat(authoredTurns())),"HTTP"+code,"generation");assertEquals(4,calls.get()-before);
            }
            assertEquals(12,calls.get());assertFalse(output.getAll().contains("SECRET"));
        }finally{server.stop(0);}
    }
    @Test void explicitCancellationStaysCancellationForBoundedAndStreamGenerations()throws Exception{
        for(boolean stream:new boolean[]{false,true}){
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger calls=new AtomicInteger();java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
            server.createContext("/v1/chat/completions",exchange->{calls.incrementAndGet();exchange.getRequestBody().close();entered.countDown();try{release.await(3,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();
            java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();AtomicReference<Runnable> cancel=new AtomicReference<>();
            try{
                QwenChatClient client=client(server,2000);java.util.concurrent.Future<String> result=worker.submit(()->client.generate(client.prepare(authoredTurns(),JsonUtils.mapper().createObjectNode(),stream),ignored->{},cancel::set));
                assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));assertNotNull(cancel.get());cancel.get().run();
                java.util.concurrent.ExecutionException terminal=assertThrows(java.util.concurrent.ExecutionException.class,()->result.get(2,java.util.concurrent.TimeUnit.SECONDS));assertTrue(terminal.getCause() instanceof java.util.concurrent.CancellationException);
                assertEquals("CANCELLED",QwenChatClient.safeUsageFailureKind((RuntimeException)terminal.getCause()));assertEquals(1,calls.get());
            }finally{release.countDown();server.stop(0);worker.shutdownNow();assertTrue(worker.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS));}
        }
    }
    @Test void refusedLoopbackConnectionKeepsSafeTransportCategory()throws Exception{
        int port;try(java.net.ServerSocket reservation=new java.net.ServerSocket(0,0,java.net.InetAddress.getByName("127.0.0.1"))){port=reservation.getLocalPort();}
        ConsenseProperties.Llm cfg=new ConsenseProperties().getQwen();cfg.setBaseUrl("http://127.0.0.1:"+port);cfg.setApiKey("SYNTHETIC_REFUSED_KEY_SECRET");cfg.setTimeoutMs(2000);QwenChatClient client=new QwenChatClient(cfg,localOnlyHttp(port));
        QwenChatClient.PreparedRequest request=client.prepare(authoredTurns(),JsonUtils.mapper().createObjectNode(),false);
        assertSafeFailure(assertThrows(RuntimeException.class,()->client.usageProbe(request,null)),"CONNECTION_FAILED","preflight");
    }
    @Test void midCallThreadInterruptionIsCancellationWithoutInvokingCancelCallback()throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger calls=new AtomicInteger();java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        server.createContext("/v1/chat/completions",exchange->{calls.incrementAndGet();exchange.getRequestBody().close();entered.countDown();try{release.await(3,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();
        AtomicReference<RuntimeException> terminal=new AtomicReference<>();QwenChatClient client=client(server,1000);
        Thread worker=new Thread(()->{try{client.generate(client.prepare(authoredTurns(),JsonUtils.mapper().createObjectNode(),false),null,null);}catch(RuntimeException error){terminal.set(error);}},"authored-qwen-midcall-interrupt");
        try{worker.start();assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));worker.interrupt();worker.join(3000);assertFalse(worker.isAlive());assertTrue(terminal.get() instanceof java.util.concurrent.CancellationException);assertEquals("CANCELLED",QwenChatClient.safeUsageFailureKind(terminal.get()));assertEquals(1,calls.get());}
        finally{release.countDown();server.stop(0);worker.interrupt();worker.join(3000);assertFalse(worker.isAlive());}
    }
    @Test @ExtendWith(OutputCaptureExtension.class)
    void actualBoundedTimeoutKeepsSafeCategoryWithoutReplayingOrLeaking(CapturedOutput output)throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger calls=new AtomicInteger();
        java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();server.setExecutor(worker);
        server.createContext("/",exchange->{calls.incrementAndGet();exchange.getRequestBody().close();try{Thread.sleep(1500);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();
        try{
            ConsenseProperties.Llm cfg=new ConsenseProperties().getQwen();cfg.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());cfg.setApiKey("SYNTHETIC_TIMEOUT_KEY_SECRET");cfg.setTimeoutMs(500);
            QwenChatClient client=new QwenChatClient(cfg,localOnlyHttp(server));QwenChatClient.PreparedRequest request=client.prepare(Collections.singletonList(LlmClient.ChatTurn.user("SYNTHETIC_TIMEOUT_PROMPT_SECRET")),JsonUtils.mapper().createObjectNode(),false,true);
            RuntimeException failure=assertThrows(RuntimeException.class,()->client.usageProbe(request,null));String category=QwenChatClient.safeUsageFailureKind(failure);
            System.out.println("AUTHORED_QWEN_TIMEOUT_CATEGORY "+category+" calls="+calls.get());
            assertEquals(1,calls.get());assertFalse(output.getAll().contains("SYNTHETIC_TIMEOUT_KEY_SECRET"));assertFalse(output.getAll().contains("SYNTHETIC_TIMEOUT_PROMPT_SECRET"));assertEquals("TIMEOUT",category);
        }finally{server.stop(0);worker.shutdownNow();assertTrue(worker.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS));}
    }

    @Test void redirectsAndRetryStatusesNeverResubmitEitherQwenModel() throws Exception {
        ConsenseProperties props=new ConsenseProperties();
        for(ConsenseProperties.Llm cfg:Arrays.asList(props.getQwen(),props.getQwenFlash()))for(int status:new int[]{307,308,408,503}) {
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            AtomicInteger count=new AtomicInteger();AtomicReference<String> request=new AtomicReference<>();
            server.createContext("/",exchange->{
                count.incrementAndGet();java.io.ByteArrayOutputStream received=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[1024];int n;
                while((n=exchange.getRequestBody().read(buffer))!=-1)received.write(buffer,0,n);request.set(new String(received.toByteArray(),StandardCharsets.UTF_8));
                exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/redirected");
                exchange.getResponseHeaders().set("Retry-After","0");byte[] body="{\"error\":\"synthetic failure\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);exchange.close();
            });
            server.start();
            try {
                cfg.setApiKey("SYNTHETIC_QWEN_TEST_KEY");cfg.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());cfg.setTimeoutMs(2000);cfg.setStructuredMaxTokens(1234);
                QwenChatClient client=new QwenChatClient(cfg,localOnlyHttp(server));
                assertThrows(BizException.class,()->client.chat(Collections.singletonList(LlmClient.ChatTurn.user("TEST ONLY"))));
                assertEquals(1,count.get(),cfg.getChatModel()+" HTTP "+status);
                JsonNode sent=JsonUtils.parse(request.get());assertEquals(cfg.getChatModel(),sent.path("model").asText());
                assertEquals(1,sent.path("n").asInt());assertEquals(1234,sent.path("max_tokens").asInt());
                assertTrue(sent.path("enable_thinking").isBoolean());assertFalse(sent.path("enable_thinking").booleanValue());
                assertFalse(sent.has("thinking"));assertFalse(sent.has("reasoning_split"));
            }finally{server.stop(0);}
        }
    }

    @Test @ExtendWith(OutputCaptureExtension.class)
    void boundedHttp400DiagnosticClassifiesOnlyKnownErrorsWithoutLeakingBodies(CapturedOutput output) throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicInteger calls=new AtomicInteger();
        server.createContext("/v1/chat/completions",exchange->{
            int call=calls.incrementAndGet();exchange.getRequestBody().close();
            String body=call==1
                    ?"{\"error\":{\"code\":\"context_length_exceeded\",\"message\":\"SYNTHETIC_PROVIDER_SECRET\"}}"
                    :"{\"error\":{\"code\":\"SYNTHETIC_PROVIDER_SECRET\",\"message\":\"SYNTHETIC_PROVIDER_SECRET\"}}";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(400,bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        try {
            String url="http://127.0.0.1:"+server.getAddress().getPort()+"/v1/chat/completions";
            String input="{\"prompt\":\"SYNTHETIC_INPUT_SECRET\"}";
            HttpSupport http=localOnlyHttp(server);
            for(int i=0;i<2;i++)assertEquals("Qwen chat HTTP 400",assertThrows(BizException.class,
                    ()->http.postJsonBoundedWithoutReplay(url,input,2000,"Bearer SYNTHETIC_KEY_SECRET",1024,null)).getMessage());
            assertEquals(2,calls.get());
            assertTrue(output.getOut().contains("status=400, category=context_limit, requestBytes="+input.getBytes(StandardCharsets.UTF_8).length));
            assertTrue(output.getOut().contains("status=400, category=unknown, requestBytes="+input.getBytes(StandardCharsets.UTF_8).length));
            assertFalse(output.getAll().contains("SYNTHETIC_PROVIDER_SECRET"));
            assertFalse(output.getAll().contains("SYNTHETIC_INPUT_SECRET"));
            assertFalse(output.getAll().contains("SYNTHETIC_KEY_SECRET"));
        }finally{server.stop(0);}
    }
}
