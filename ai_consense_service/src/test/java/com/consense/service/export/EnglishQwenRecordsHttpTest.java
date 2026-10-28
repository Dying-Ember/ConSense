package com.consense.service.export;

import com.consense.ai.*;
import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.llm.QwenChatUsagePreflight;
import com.consense.service.llm.QwenGenerationLedger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EnglishQwenRecordsHttpTest {
    @TempDir Path files;

    @ParameterizedTest @ValueSource(booleans={false,true})
    void fullThirtyTwoSegmentExportUsesTheExistingStrictRecordsWireAndExactCache(boolean mixed)throws Exception{
        try(Fixture f=new Fixture("valid",mixed);LlmProfiles.Scope ignored=f.profiles.bind(f.profiles.resolve("qwen"))){
            JsonNode before=f.input.deepCopy(),result=f.exporter.json(f.input,"authored:first",()->{});
            for(int i=0;i<(mixed?30:32);i++)assertEquals(f.input.path("notes").get(i),result.path("notes").get(i));
            if(mixed){assertEquals("English translation: Payment is due within 14 days.",result.path("notes").get(30).asText());assertEquals("English translation: Payment is due within 14 days.",result.path("notes").get(31).asText());}
            assertEquals(before,f.input,"Delivery must not modify the original source");
            assertEquals(2,f.requests.size(),"One cap-one preflight and one generation, without a repair");
            JsonNode probe=f.requests.get(0),generation=f.requests.get(1);
            assertEquals("json_object",probe.path("response_format").path("type").asText());
            assertEquals("json_object",generation.path("response_format").path("type").asText());
            assertEquals(1,probe.path("max_tokens").asInt());assertEquals(16384,generation.path("max_tokens").asInt());
            assertFalse(generation.path("enable_thinking").asBoolean(true));
            assertTrue(generation.path("messages").get(0).path("content").asText().contains("exactly one key, \"records\""));
            assertFalse(generation.path("messages").get(0).path("content").asText().contains("bare JSON array"));
            ObjectNode commonProbe=((ObjectNode)probe).deepCopy(),commonGeneration=((ObjectNode)generation).deepCopy();
            commonProbe.remove(List.of("max_tokens","stream"));commonGeneration.remove(List.of("max_tokens","stream"));assertEquals(commonProbe,commonGeneration);
            assertEquals(result,f.exporter.json(f.input,"authored:another-report",()->{}));
            assertEquals(2,f.requests.size(),"Exact validated segments remain reusable across report references");
            try(java.util.stream.Stream<Path> entries=Files.list(files.resolve("english-file-output").resolve(EnglishFileOutput.POLICY))){assertEquals(32,entries.filter(p->p.getFileName().toString().startsWith("segment-")).count());}
            verify(f.ledger).reserve(anyString(),anyString(),eq("language-0"),anyString(),eq(false));
            verify(f.ledger).reserve(anyString(),anyString(),eq("answer"),anyString(),eq(true));
        }
    }

    @ParameterizedTest @ValueSource(strings={"empty-array","empty-records","missing-id","duplicate-id","changed-digits","foreign-untranslated"})
    void invalidOrEmptyClassificationNeverBecomesAnEnglishDelivery(String mutation)throws Exception{
        try(Fixture f=new Fixture(mutation,true);LlmProfiles.Scope ignored=f.profiles.bind(f.profiles.resolve("qwen"))){
            JsonNode before=f.input.deepCopy();assertThrows(BizException.class,()->f.exporter.json(f.input,"authored:invalid",()->{}));
            assertEquals(before,f.input);assertTrue(f.requests.size()<=4,"The existing one-repair ceiling is retained");
            assertFalse(Files.exists(files.resolve("english-file-output").resolve(EnglishFileOutput.POLICY)),"Invalid classifications cannot seed reusable segments");
            for(JsonNode request:f.requests)assertEquals("json_object",request.path("response_format").path("type").asText());
        }
    }

    @Test void currentSourceGuardStillRejectsAfterActualHttpGeneration()throws Exception{
        try(Fixture f=new Fixture("valid",true);LlmProfiles.Scope ignored=f.profiles.bind(f.profiles.resolve("qwen"))){
            assertThrows(RuntimeException.class,()->f.exporter.json(f.input,"authored:stale",()->{if(f.requests.stream().anyMatch(n->n.path("max_tokens").asInt()>1))throw new IllegalStateException("Authored current source changed");}));
            assertEquals(2,f.requests.size());assertFalse(Files.exists(files.resolve("english-file-output").resolve(EnglishFileOutput.POLICY)));
        }
    }

    private final class Fixture implements AutoCloseable {
        final HttpServer server;
        final ConsenseProperties props=new ConsenseProperties();
        final QwenGenerationLedger ledger=mock(QwenGenerationLedger.class);
        final List<JsonNode> requests=Collections.synchronizedList(new ArrayList<>());
        final ObjectNode input=JsonUtils.mapper().createObjectNode();
        final LlmProfiles profiles;
        final EnglishFileOutput exporter;
        Fixture(String mutation,boolean mixed)throws Exception{
            ArrayNode notes=input.putArray("notes");for(int i=0;i<(mixed?30:32);i++)notes.add("Payment under clause "+i+" is due within 14 days.");
            if(mixed){notes.add("Bonjour, paiement dans 14 jours.");notes.add("\u8bf7\u5728 14 \u5929\u5185\u4ed8\u6b3e\u3002");}
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/v1/chat/completions",exchange->{
                JsonNode request=JsonUtils.parse(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));requests.add(request);
                boolean probe=request.path("max_tokens").asInt()==1;String content="{";
                if(!probe){
                    JsonNode supplied=JsonUtils.parse(request.path("messages").get(1).path("content").asText());ArrayNode rows=JsonUtils.mapper().createArrayNode();
                    for(int i=supplied.size()-1;i>=0;i--){JsonNode slot=supplied.get(i);String source=slot.path("text").asText();boolean french=source.startsWith("Bonjour"),chinese=source.startsWith("\u8bf7");
                        ObjectNode row=rows.addObject().put("id",slot.path("id").asText()).put("sourceLanguage",french?"fr":chinese?"zh":"en").put("translated",french||chinese?1:0).put("text",french||chinese?"Payment is due within 14 days.":"");
                        if("changed-digits".equals(mutation)&&(french||chinese))row.put("text","Payment is due within 21 days.");
                        if("foreign-untranslated".equals(mutation)&&french)row.put("translated",0).put("text","");
                    }
                    if("empty-records".equals(mutation))rows.removeAll();
                    if("missing-id".equals(mutation))((ObjectNode)rows.get(0)).put("id","T99");
                    if("duplicate-id".equals(mutation))((ObjectNode)rows.get(0)).put("id",rows.get(1).path("id").asText());
                    content="empty-array".equals(mutation)?"[]":JsonUtils.write(JsonUtils.mapper().createObjectNode().set("records",rows));
                }
                ObjectNode body=JsonUtils.mapper().createObjectNode().put("object","chat.completion").put("id","authored-http").put("model","qwen3.8-27b");
                ObjectNode choice=body.putArray("choices").addObject().put("index",0).put("finish_reason",probe?"length":"stop");choice.putObject("message").put("role","assistant").put("content",content);
                body.putObject("usage").put("prompt_tokens",1024).put("completion_tokens",1).put("total_tokens",1025);
                byte[] bytes=JsonUtils.write(body).getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });server.start();
            props.setStorageRoot(files.toString());props.getQwen().setEnabled(true);props.getQwen().setApiKey("AUTHORED-NOT-A-REAL-KEY");props.getQwen().setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
            LlmClient local=mock(LlmClient.class);QwenChatClient qwen=new QwenChatClient(props.getQwen(),new HttpSupport());profiles=new LlmProfiles(props,local,local,qwen,local);
            exporter=new EnglishFileOutput(new AiGateway(local,profiles),new QwenChatUsagePreflight(props,ledger),props);
        }
        public void close(){server.stop(0);}
    }
}
