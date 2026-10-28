package com.consense.service.llm;

import com.consense.ai.*;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.VettingInputBudget;
import com.consense.service.vetting.VettingCorpus;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Authored loopback exchanges only; no provider, model, database, or source files. */
class QwenDispatchReadyHttpTest {
    private static final String SOURCE="a".repeat(64);
    private static final String SYSTEM="Review the authored contract excerpt.";
    private static final String USER="Section 12.3: the fee is \u00a3 100; preserve the quoted text.";
    private static final JsonNode SCHEMA=JsonUtils.parse("{\"type\":\"array\",\"items\":{\"type\":\"object\"}}");

    @Test void readyBindsTheExactHttpBodyAfterLedgerDispatchAndBeforeGenerationPost() throws Exception {
        try(Fixture f=new Fixture();QwenChatUsagePreflight.Operation.ActionScope action=f.op.openAction("test:dispatch-ready",()->{})) {
            VettingInputBudget.Input input=f.op.input(SYSTEM,USER,SCHEMA,SOURCE);
            VettingInputBudget.Result budget=f.budget(input);
            List<QwenChatUsagePreflight.DispatchReady> ready=new ArrayList<>();
            List<Map> answer=f.op.complete(input,budget,SYSTEM,USER,SCHEMA,Map.class,new ArrayList<>(),new LinkedHashMap<>(),null,2,value->{
                f.events.add("ready");ready.add(value);
                assertNull(f.generationBody.get(),"The generation must not have reached HTTP when ready is observed.");
            });
            assertTrue(answer.isEmpty());
            assertEquals(1,ready.size(),"The sealed generation needs one diagnostic readiness receipt.");
            QwenChatUsagePreflight.DispatchReady value=ready.get(0);
            assertEquals(input.getSerializedInputSha256(),value.getPreparedInputSha256());
            assertEquals(SOURCE,value.getSourceSnapshotSha256());assertEquals(2,value.getAttemptOrdinal());
            assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(f.generationBody.get())),value.getGenerationWireSha256());
            assertEquals(input.getQwenWire().getGenerationEntitySha256(),value.getGenerationWireSha256());
            assertEquals(Arrays.asList("ledger-dispatch","http-preflight","ledger-dispatch","ready","http-generation"),f.events);
            assertEquals(2,f.bodies.size());
            JsonNode receipt=JsonUtils.mapper().valueToTree(value);
            assertEquals(VettingCorpus.hash(USER),receipt.path("userSha256").textValue(),"The receipt must identify the actual sealed generation user text.");
            assertEquals(USER.length(),receipt.path("userChars").asInt(-1));
            assertEquals(new HashSet<>(Arrays.asList("preparedInputSha256","generationWireSha256","sourceSnapshotSha256","attemptOrdinal","userSha256","userChars")),fieldNames(receipt));
        }
    }

    @Test void aDiagnosticFailureCannotChangeTheSealedGeneration()throws Exception{
        try(Fixture f=new Fixture();QwenChatUsagePreflight.Operation.ActionScope action=f.op.openAction("test:observer-unavailable",()->{})){
            VettingInputBudget.Input input=f.op.input(SYSTEM,USER,SCHEMA,SOURCE);VettingInputBudget.Result budget=f.budget(input);
            List<Map> answer=f.op.complete(input,budget,SYSTEM,USER,SCHEMA,Map.class,new ArrayList<>(),new LinkedHashMap<>(),null,1,value->{throw new IllegalStateException("Authored observer unavailable");});
            assertTrue(answer.isEmpty());assertNotNull(f.generationBody.get());assertEquals(2,f.bodies.size());
            assertEquals(USER,JsonUtils.parse(new String(f.generationBody.get(),StandardCharsets.UTF_8)).path("messages").get(1).path("content").asText());
        }
    }
    @Test void anObserverErrorAndFailingDiagnosticLoggerCannotAbortTheSealedGeneration()throws Exception{
        ch.qos.logback.classic.Logger logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(QwenChatUsagePreflight.class);
        ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent> failing=new ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent>(){
            @Override protected void append(ch.qos.logback.classic.spi.ILoggingEvent event){if(event.getFormattedMessage().equals("qwen_dispatch_ready_diagnostic_unavailable"))throw new AssertionError("Authored diagnostic logger unavailable");}
        };failing.start();logger.addAppender(failing);
        try(Fixture f=new Fixture();QwenChatUsagePreflight.Operation.ActionScope action=f.op.openAction("test:observer-error",()->{})){
            VettingInputBudget.Input input=f.op.input(SYSTEM,USER,SCHEMA,SOURCE);VettingInputBudget.Result budget=f.budget(input);
            List<Map> answer=f.op.complete(input,budget,SYSTEM,USER,SCHEMA,Map.class,new ArrayList<>(),new LinkedHashMap<>(),null,1,value->{throw new AssertionError("Authored observer error");});
            assertTrue(answer.isEmpty());assertNotNull(f.generationBody.get());assertEquals(2,f.bodies.size());
            verify(f.ledger,times(2)).complete(any(),any());verify(f.ledger,never()).unknown(any(),anyMap());
        }finally{logger.detachAppender(failing);failing.stop();}
    }

    @Test void aDifferentFinalUserCannotPublishTheOldReceiptOrGeneration()throws Exception{
        try(Fixture f=new Fixture();QwenChatUsagePreflight.Operation.ActionScope action=f.op.openAction("test:changed-user",()->{})){
            VettingInputBudget.Input input=f.op.input(SYSTEM,USER,SCHEMA,SOURCE);VettingInputBudget.Result budget=f.budget(input);List<QwenChatUsagePreflight.DispatchReady> ready=new ArrayList<>();
            assertThrows(IllegalArgumentException.class,()->f.op.complete(input,budget,SYSTEM,USER+" changed",SCHEMA,Map.class,new ArrayList<>(),new LinkedHashMap<>(),null,1,ready::add));
            assertTrue(ready.isEmpty());assertNull(f.generationBody.get());assertEquals(1,f.bodies.size(),"Only the original usage preflight may have reached HTTP.");
        }
    }

    private static Set<String> fieldNames(JsonNode node){Set<String> names=new HashSet<>();node.fieldNames().forEachRemaining(names::add);return names;}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final List<String> events=new CopyOnWriteArrayList<>();
        final List<byte[]> bodies=new CopyOnWriteArrayList<>();
        final AtomicReference<byte[]> generationBody=new AtomicReference<>();
        final QwenGenerationLedger ledger=mock(QwenGenerationLedger.class);
        final QwenChatUsagePreflight.Operation op;
        Fixture() throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/v1/chat/completions",exchange->{
                byte[] body=exchange.getRequestBody().readAllBytes();bodies.add(body);
                boolean probe=JsonUtils.parse(new String(body,StandardCharsets.UTF_8)).path("max_tokens").asInt()==1;
                if(probe)events.add("http-preflight");else{events.add("http-generation");generationBody.set(body);}
                String content=probe?"{":"{\"records\":[]}";
                String response="{\"object\":\"chat.completion\",\"model\":\"qwen3.8-27b\",\"choices\":[{\"index\":0,\"finish_reason\":\""+(probe?"length":"stop")+"\",\"message\":{\"role\":\"assistant\",\"content\":"+JsonUtils.write(content)+"}}],\"usage\":{\"prompt_tokens\":42,\"completion_tokens\":1,\"total_tokens\":43}}";
                byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });server.start();
            ConsenseProperties props=new ConsenseProperties();props.getQwen().setEnabled(true);props.getQwen().setApiKey("AUTHORED_DISPATCH_KEY_SECRET");props.getQwen().setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());props.getQwen().setTimeoutMs(2000);
            HttpSupport http=new HttpSupport();okhttp3.OkHttpClient original=(okhttp3.OkHttpClient)org.springframework.test.util.ReflectionTestUtils.getField(http,"shared");
            org.springframework.test.util.ReflectionTestUtils.setField(http,"shared",original.newBuilder().proxy(java.net.Proxy.NO_PROXY).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).addInterceptor(chain->{
                okhttp3.HttpUrl url=chain.request().url();if(!"http".equals(url.scheme())||!"127.0.0.1".equals(url.host())||url.port()!=server.getAddress().getPort()||!"/v1/chat/completions".equals(url.encodedPath())||url.query()!=null||!"POST".equals(chain.request().method()))throw new IllegalStateException("Authored transport blocked an unapproved destination before DNS or connection");return chain.proceed(chain.request());
            }).build());
            doAnswer(call->{events.add("ledger-dispatch");return null;}).when(ledger).dispatch(any());
            LlmClient local=mock(LlmClient.class);LlmOperation selected=new LlmProfiles(props,local,local,new QwenChatClient(props.getQwen(),http),local).resolve("qwen");
            op=new QwenChatUsagePreflight(props,ledger).capture(selected,"vetting");
        }
        VettingInputBudget.Result budget(VettingInputBudget.Input input){VettingInputBudget.Result result=VettingInputBudget.check(input,i->op.observe(i,"final",()->{}));assertTrue(result.isExtraDispatchPermitted());return result;}
        @Override public void close(){server.stop(0);}
    }
}
