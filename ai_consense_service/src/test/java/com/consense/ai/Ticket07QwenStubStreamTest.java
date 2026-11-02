package com.consense.ai;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Local synthetic gateway integration only; no browser, database or real model acceptance. */
class Ticket07QwenStubStreamTest {
    private static final String TOKEN = "ticket07-synthetic-token";
    private static final String EXPECTED = "{\"protocol_stub\":true,\"message\":\"No model inference; token counts are synthetic.\"}";
    private static final String LAUNCH =
            "import sys\n" +
            "sys.path.insert(0,'tools/offline_delivery')\n" +
            "from qwen_protocol_stub import StubServer,StubHandler\n" +
            "class Gate:\n" +
            " def __init__(self,stream): self.stream=stream; self.armed=True\n" +
            " def __getattr__(self,name): return getattr(self.stream,name)\n" +
            " def write(self,data):\n" +
            "  size=self.stream.write(data)\n" +
            "  if self.armed and data.startswith(b'data: '):\n" +
            "   self.armed=False; self.stream.flush(); print('FRAME1',flush=True); sys.stdin.readline()\n" +
            "  return size\n" +
            "class Handler(StubHandler):\n" +
            " def setup(self):\n" +
            "  super().setup(); self.wfile=Gate(self.wfile)\n" +
            " def do_POST(self):\n" +
            "  try: super().do_POST()\n" +
            "  except BaseException:\n" +
            "   import traceback; traceback.print_exc(); print('POST_ERROR',flush=True); raise\n" +
            "  finally: print('POST_END',flush=True)\n" +
            "s=StubServer(('127.0.0.1',0),sys.argv[1]); s.RequestHandlerClass=Handler\n" +
            "print(s.server_address[1],flush=True); s.serve_forever()\n";

    @Test void startupTimeoutReapsChildAndReader() {
        AtomicReference<Process> started = new AtomicReference<>();
        TimeoutException failure = assertThrows(TimeoutException.class,
                () -> new Stub("import time; time.sleep(60)", 300, started::set));
        assertNotNull(started.get());
        assertFalse(started.get().isAlive(), "failed startup must reap the child");
        assertEquals(0, failure.getSuppressed().length, "cleanup must finish without errors");
    }

    @Test void gatewayForwardsBeforeProviderFinishes() throws Exception {
        try (Stub stub = new Stub()) {
            CountDownLatch first = new CountDownLatch(1);
            List<String> chunks = new CopyOnWriteArrayList<>();
            Future<String> result = stub.submit(() -> stub.gateway().chatStructuredStream(
                    turns(), schema(), part -> { chunks.add(part); first.countDown(); }, ignored -> {}));
            assertTrue(first.await(5, TimeUnit.SECONDS), "first body delta must arrive before release");
            assertFalse(result.isDone(), "test gate must keep provider incomplete");
            assertFalse(chunks.isEmpty());
            stub.release();
            assertEquals(EXPECTED, result.get(5, TimeUnit.SECONDS));
            assertEquals(EXPECTED, String.join("", chunks));
            assertEquals(1, stub.posts.get());
            assertFalse(stub.awaitPostEnd());
        }
    }

    @Test void gatewayCancellationStopsTheBoundStreamWithoutSuccess() throws Exception {
        try (Stub stub = new Stub()) {
            CountDownLatch first = new CountDownLatch(1);
            AtomicReference<Runnable> cancel = new AtomicReference<>();
            Future<String> result = stub.submit(() -> stub.gateway().chatStructuredStream(
                    turns(), schema(), part -> first.countDown(), cancel::set));
            assertTrue(first.await(5, TimeUnit.SECONDS), "first body delta must arrive before cancel");
            assertFalse(result.isDone());
            assertNotNull(cancel.get());
            cancel.get().run();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof CancellationException, String.valueOf(failure.getCause()));
            stub.release();
            assertEquals(1, stub.posts.get());
            assertFalse(stub.awaitPostEnd(), "provider handler must end without an uncaught exception");
        }
    }

    private static List<LlmClient.ChatTurn> turns() {
        return Collections.singletonList(LlmClient.ChatTurn.user("synthetic input"));
    }

    private static JsonNode schema() { return JsonUtils.parse("{\"type\":\"object\"}"); }

    private static final class Stub implements AutoCloseable {
        private final Process process;
        private final int port;
        private final BufferedReader stdout;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final AtomicInteger posts = new AtomicInteger();

        Stub() throws Exception { this(LAUNCH, 5000, ignored -> {}); }

        Stub(String launch, long timeoutMs, Consumer<Process> onStart) throws Exception {
            ProcessBuilder builder = new ProcessBuilder("python", "-u", "-c", launch, TOKEN);
            builder.directory(Paths.get(System.getProperty("user.dir")).toFile());
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            process = builder.start();
            stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            try {
                onStart.accept(process);
                Future<String> first = executor.submit(stdout::readLine);
                String value = first.get(timeoutMs, TimeUnit.MILLISECONDS);
                if (value == null || !value.matches("[0-9]{1,5}") || !process.isAlive())
                    throw new IllegalStateException("synthetic stub failed to start");
                port = Integer.parseInt(value);
            } catch (Exception | Error failure) {
                boolean interrupted = false;
                process.destroyForcibly();
                try {
                    if (!process.waitFor(3, TimeUnit.SECONDS))
                        failure.addSuppressed(new IllegalStateException("startup child did not exit"));
                } catch (InterruptedException cleanup) {
                    interrupted = true;
                    failure.addSuppressed(cleanup);
                } finally {
                    executor.shutdownNow();
                    try {
                        if (!executor.awaitTermination(5, TimeUnit.SECONDS))
                            failure.addSuppressed(new IllegalStateException("startup reader did not exit"));
                    } catch (InterruptedException cleanup) {
                        interrupted = true;
                        failure.addSuppressed(cleanup);
                    }
                    if (interrupted) Thread.currentThread().interrupt();
                }
                throw failure;
            }
        }

        Future<String> submit(Callable<String> work) { return executor.submit(work); }
        void release() throws IOException { process.getOutputStream().write('\n'); process.getOutputStream().flush(); }
        boolean awaitPostEnd() throws Exception {
            return executor.submit(() -> {
                boolean error = false;
                String line;
                while ((line = stdout.readLine()) != null) {
                    if ("POST_ERROR".equals(line)) error = true;
                    if ("POST_END".equals(line)) return error;
                }
                throw new EOFException("stub exited without POST_END");
            }).get(5, TimeUnit.SECONDS);
        }

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
                        if ("/v1/chat/completions".equals(url.encodedPath())) posts.incrementAndGet();
                        return chain.proceed(chain.request());
                    }).build());
            ConsenseProperties.Llm cfg = new ConsenseProperties().getQwen();
            cfg.setEnabled(true); cfg.setApiKey(TOKEN); cfg.setBaseUrl("http://127.0.0.1:" + port);
            cfg.setChatModel("qwen3.8-27b"); cfg.setTimeoutMs(5000); cfg.setStructuredMaxTokens(100);
            return new AiGateway(new QwenChatClient(cfg, http));
        }

        @Override public void close() throws Exception {
            try {
                if (process.isAlive()) process.getOutputStream().close();
            } finally {
                try {
                    process.destroy();
                    if (!process.waitFor(3, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        assertTrue(process.waitFor(3, TimeUnit.SECONDS));
                    }
                    assertFalse(process.isAlive());
                } finally {
                    executor.shutdownNow();
                    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "test thread did not exit");
                }
            }
        }
    }
}
