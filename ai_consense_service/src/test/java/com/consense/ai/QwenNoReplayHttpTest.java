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
import static org.junit.jupiter.api.Assertions.*;

/** Eight finite loopback exchanges; synthetic credentials, no provider or model calls. */
class QwenNoReplayHttpTest {
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
                QwenChatClient client=new QwenChatClient(cfg,new HttpSupport());
                assertThrows(BizException.class,()->client.chat(Collections.singletonList(LlmClient.ChatTurn.user("TEST ONLY"))));
                assertEquals(1,count.get(),cfg.getChatModel()+" HTTP "+status);
                JsonNode sent=JsonUtils.parse(request.get());assertEquals(cfg.getChatModel(),sent.path("model").asText());
                assertEquals(1,sent.path("n").asInt());assertEquals(1234,sent.path("max_tokens").asInt());
                assertTrue(sent.path("enable_thinking").isBoolean());assertFalse(sent.path("enable_thinking").booleanValue());
                assertFalse(sent.has("thinking"));assertFalse(sent.has("reasoning_split"));
            }finally{server.stop(0);}
        }
    }
}
