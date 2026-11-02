package com.consense.ai;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Local synthetic protocol integration only; no database, real model or business acceptance. */
class Ticket06QwenStubIntegrationTest {
    private static final String TOKEN = "ticket06-synthetic-token";
    private static final String LAUNCH =
            "import sys; sys.path.insert(0, 'tools/offline_delivery'); " +
            "from qwen_protocol_stub import StubServer,load_fixture,CONTENT; " +
            "c=load_fixture(sys.argv[1]) if sys.argv[1] else CONTENT; " +
            "s=StubServer(('127.0.0.1',0),sys.argv[2],content=c); " +
            "print(s.server_address[1],flush=True); s.serve_forever()";
    @TempDir Path tmp;

    @Test void ordinaryGatewayUsesExplicitSyntheticNodesFixture() throws Exception {
        try (Stub stub = start("{\"nodes\":[],\"notes\":\"synthetic protocol fixture\"}")) {
            JsonNode raw = JsonUtils.parse(stub.gateway().complete("synthetic system", "synthetic input"));
            assertTrue(raw.path("nodes").isArray());
            assertEquals("synthetic protocol fixture", raw.path("notes").asText());
        }
    }

    @Test void structuredGatewayAcceptsRecordsAndRejectsDefaultFixture() throws Exception {
        JsonNode schema = JsonUtils.parse("{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}}");
        try (Stub stub = start("{\"records\":[]}")) {
            List<JsonNode> records = stub.gateway().completeStructuredJsonRecords("synthetic system", "synthetic input", JsonNode.class, schema, null, null);
            assertTrue(records.isEmpty());
        }
        try (Stub stub = start(null)) {
            StructuredOutputValidationException failure = assertThrows(StructuredOutputValidationException.class,
                    () -> stub.gateway().completeStructuredJsonRecords("synthetic system", "synthetic input", JsonNode.class, schema, null, null));
            assertEquals(StructuredOutputValidationException.Reason.SCHEMA_MISMATCH, failure.getReason());
        }
    }

    private Stub start(String fixture) throws Exception {
        Path path = tmp.resolve("fixture-" + System.nanoTime() + ".json");
        if (fixture != null) Files.write(path, fixture.getBytes(StandardCharsets.UTF_8));
        // The launcher binds port 0 itself and publishes its bound port; no external service is selected.
        ProcessBuilder builder = new ProcessBuilder("python", "-u", "-c", LAUNCH, fixture == null ? "" : path.toString(), TOKEN);
        builder.directory(Paths.get(System.getProperty("user.dir")).toFile());
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process process = builder.start();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            Future<String> line = reader.submit(() -> new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)).readLine());
            String port = line.get(5, TimeUnit.SECONDS);
            if (port == null || !port.matches("[0-9]{1,5}") || !process.isAlive()) throw new IllegalStateException("synthetic stub failed to start");
            return new Stub(process, Integer.parseInt(port));
        } catch (Exception failure) {
            process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS); throw failure;
        } finally { reader.shutdownNow(); }
    }

    private static final class Stub implements AutoCloseable {
        private final Process process;
        private final int port;
        Stub(Process process, int port) { this.process = process; this.port = port; }
        AiGateway gateway() {
            HttpSupport http = new HttpSupport();
            okhttp3.OkHttpClient original = (okhttp3.OkHttpClient) ReflectionTestUtils.getField(http, "shared");
            ReflectionTestUtils.setField(http, "shared", original.newBuilder().proxy(Proxy.NO_PROXY)
                    .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                    .addInterceptor(chain -> {
                        okhttp3.HttpUrl url = chain.request().url();
                        if (!"http".equals(url.scheme()) || !"127.0.0.1".equals(url.host()) || url.port() != port
                                || url.query() != null || !("/v1/models".equals(url.encodedPath()) || "/v1/chat/completions".equals(url.encodedPath())))
                            throw new IllegalStateException("unexpected synthetic destination");
                        return chain.proceed(chain.request());
                    }).build());
            ConsenseProperties.Llm cfg = new ConsenseProperties().getQwen();
            cfg.setEnabled(true); cfg.setApiKey(TOKEN); cfg.setBaseUrl("http://127.0.0.1:" + port);
            cfg.setChatModel("qwen3.8-27b"); cfg.setTimeoutMs(3000); cfg.setStructuredMaxTokens(100);
            return new AiGateway(new QwenChatClient(cfg, http));
        }
        @Override public void close() throws Exception {
            process.destroy();
            if (!process.waitFor(3, TimeUnit.SECONDS)) { process.destroyForcibly(); assertTrue(process.waitFor(3, TimeUnit.SECONDS)); }
        }
    }
}
