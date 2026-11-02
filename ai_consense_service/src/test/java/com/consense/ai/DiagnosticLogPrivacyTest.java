package com.consense.ai;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.consense.common.BizException;
import com.consense.common.ApiResponse;
import com.consense.common.GlobalExceptionHandler;
import com.consense.config.AiConfig;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParser;
import com.consense.ocr.OcrClient;
import com.consense.ocr.PaddleOcrClient;
import com.consense.vector.QdrantVectorStore;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DiagnosticLogPrivacyTest {
    private static final String SECRET = "SYNTHETIC_PRIVATE_CONTENT";

    private static ListAppender<ILoggingEvent> capture(Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static String messages(ListAppender<ILoggingEvent> appender) {
        StringBuilder result = new StringBuilder();
        for (ILoggingEvent event : appender.list) result.append(event.getFormattedMessage()).append('\n');
        return result.toString();
    }

    @Test void ocrEmptyAndInvalidResponsesKeepBusinessMessageWithoutLoggingContent() {
        HttpSupport http = mock(HttpSupport.class);
        ConsenseProperties.Ocr cfg = new ConsenseProperties().getOcr();
        cfg.setMode("classic");
        String longRaw = SECRET + String.join("", Collections.nCopies(201, "x"));
        when(http.postJson(anyString(), anyString(), anyLong())).thenReturn("{\"detail\":\"" + SECRET + "\"}", SECRET, longRaw);
        PaddleOcrClient client = new PaddleOcrClient(cfg, http);
        Logger logger = (Logger) LoggerFactory.getLogger(PaddleOcrClient.class);
        Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> logs = capture(logger);
        ListAppender<ILoggingEvent> handlerLogs = capture(handlerLogger);
        try {
            assertTrue(client.recognize(new byte[]{1}).getText().isEmpty());
            BizException failure = assertThrows(BizException.class, () -> client.recognize(new byte[]{1}));
            assertEquals("OCR 响应不是合法 JSON: " + SECRET, failure.getMessage());
            assertEquals(failure.getMessage(), new GlobalExceptionHandler().handleBiz(failure).getMessage());
            BizException longFailure = assertThrows(BizException.class, () -> client.recognize(new byte[]{1}));
            assertEquals("OCR 响应不是合法 JSON: " + longRaw.substring(0, 200) + "...", longFailure.getMessage());
            assertTrue(messages(logs).contains("OCR 未解析出文本"));
            assertFalse(messages(logs).contains(SECRET));
            assertFalse(messages(handlerLogs).contains(SECRET));
        } finally { logger.detachAppender(logs); logs.stop(); handlerLogger.detachAppender(handlerLogs); handlerLogs.stop(); }
    }

    @Test void configuredUrlsAreNotLoggedForAnyProvider() {
        ConsenseProperties props = new ConsenseProperties();
        String url = "http://user:" + SECRET + "@localhost:1234/path?token=" + SECRET;
        props.getLlm().setBaseUrl(url); props.getOcr().setBaseUrl(url); props.getVector().setBaseUrl(url);
        AiConfig config = new AiConfig(); HttpSupport http = mock(HttpSupport.class);
        Logger logger = (Logger) LoggerFactory.getLogger(AiConfig.class);
        ListAppender<ILoggingEvent> logs = capture(logger);
        try {
            props.getLlm().setProvider("openai"); config.llmClient(props, http);
            props.getLlm().setProvider("ollama"); config.llmClient(props, http);
            config.ocrClient(props, http);
            props.getVector().setProvider("memory"); config.vectorStore(props, http);
            props.getVector().setProvider("qdrant"); config.vectorStore(props, http);
            assertTrue(messages(logs).contains("openai-compatible"));
            assertTrue(messages(logs).contains("qdrant"));
            assertFalse(messages(logs).contains(SECRET));
            assertFalse(messages(logs).contains(url));
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    @Test void retryLogsKeepCountAndClassWithoutUrlOrExceptionMessage() {
        HttpSupport http = new HttpSupport(); AtomicInteger calls = new AtomicInteger();
        OkHttpClient original = (OkHttpClient) ReflectionTestUtils.getField(http, "shared");
        ReflectionTestUtils.setField(http, "shared", original.newBuilder().addInterceptor(chain -> {
            calls.incrementAndGet(); throw new IOException(SECRET);
        }).build());
        Logger logger = (Logger) LoggerFactory.getLogger(HttpSupport.class);
        Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> logs = capture(logger);
        ListAppender<ILoggingEvent> handlerLogs = capture(handlerLogger);
        try {
            BizException failure = assertThrows(BizException.class, () -> http.postJson(
                    "http://user:" + SECRET + "@localhost/" + SECRET + "?key=" + SECRET, "{}", 1000, null, 1));
            assertEquals(2, calls.get());
            assertTrue(messages(logs).contains("(1/2)"));
            assertTrue(messages(logs).contains("(2/2)"));
            assertTrue(messages(logs).contains("IOException"));
            assertFalse(messages(logs).contains(SECRET));
            assertEquals("无法连接 localhost:80，请确认本地服务已启动（" + SECRET + "）", failure.getMessage());
            assertEquals(failure.getMessage(), new GlobalExceptionHandler().handleBiz(failure).getMessage());
            assertFalse(messages(handlerLogs).contains(SECRET));
        } finally { logger.detachAppender(logs); logs.stop(); handlerLogger.detachAppender(handlerLogs); handlerLogs.stop(); }
    }

    @Test void nonSuccessResponseStillExposesBodyToExistingBusinessDecision() {
        HttpSupport http = new HttpSupport();
        OkHttpClient original = (OkHttpClient) ReflectionTestUtils.getField(http, "shared");
        ReflectionTestUtils.setField(http, "shared", original.newBuilder().addInterceptor(chain ->
            new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(422).message("Unprocessable")
                .body(ResponseBody.create("{\"detail\":{\"kind\":\"QUERY_TOKEN_BUDGET_EXHAUSTED\"}}", HttpSupport.JSON)).build()
        ).build());
        BizException failure = assertThrows(BizException.class, () -> http.get("http://localhost/test", 1000));
        assertTrue(failure.getMessage().contains("HTTP 422"));
        assertTrue(failure.getMessage().contains("QUERY_TOKEN_BUDGET_EXHAUSTED"));
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> logs = capture(logger);
        try {
            ApiResponse<Void> response = new GlobalExceptionHandler().handleBiz(failure);
            assertEquals(failure.getMessage(), response.getMessage());
            assertTrue(messages(logs).contains("HTTP=422"));
            assertFalse(messages(logs).contains("QUERY_TOKEN_BUDGET_EXHAUSTED"));
            BizException modelFailure = assertThrows(BizException.class, () -> http.postJsonBoundedWithoutReplay(
                    "http://localhost/test", "{}", 1000, null, 1024, null));
            new GlobalExceptionHandler().handleBiz(modelFailure);
            assertTrue(messages(logs).contains("category=HTTP"));
            assertTrue(messages(logs).contains("HTTP=422"));
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    @Test void handlerKeepsPublicResponsesButLogsOnlySafeFacts() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> logs = capture(logger);
        try {
            GlobalExceptionHandler handler = new GlobalExceptionHandler();
            assertEquals(SECRET, handler.handleBiz(new BizException(4012, SECRET)).getMessage());
            assertEquals(5000, handler.handleOther(new IllegalArgumentException(SECRET)).getCode());
            assertTrue(messages(logs).contains("code=4012"));
            assertTrue(messages(logs).contains("IllegalArgumentException"));
            assertFalse(messages(logs).contains(SECRET));
            for (ILoggingEvent event : logs.list) assertNull(event.getThrowableProxy());
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    @Test void parserAndProgressErrorsDoNotLogFilenamesOrExceptionMessages() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(DocumentParser.class);
        ListAppender<ILoggingEvent> logs = capture(logger);
        try {
            DocumentParser parser = new DocumentParser(new ConsenseProperties(), mock(OcrClient.class));
            BizException failure = assertThrows(BizException.class, () -> parser.parse(SECRET + ".pdf", new byte[]{1, 2, 3}));
            assertTrue(failure.getMessage().contains(SECRET));
            try (AutoCloseable ignored = DocumentParser.observeProgress(message -> { throw new IllegalStateException(SECRET); })) {
                DocumentParser.progress("ignored");
            }
            assertTrue(messages(logs).contains("解析文件失败"));
            assertTrue(messages(logs).contains("IllegalStateException"));
            assertFalse(messages(logs).contains(SECRET));
            for (ILoggingEvent event : logs.list) assertNull(event.getThrowableProxy());
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    @Test void providerFallbackAndHealthLogsDoNotExposeHttpErrorBodies() {
        HttpSupport http = new HttpSupport();
        OkHttpClient original = (OkHttpClient) ReflectionTestUtils.getField(http, "shared");
        ReflectionTestUtils.setField(http, "shared", original.newBuilder().addInterceptor(chain -> {
            String path = chain.request().url().encodedPath();
            boolean embeddingFallback = path.equals("/api/embeddings");
            boolean collectionCheck = path.equals("/collections/consense_evidence");
            int status = embeddingFallback || collectionCheck ? 200 : 422;
            String body = embeddingFallback ? "{\"embedding\":[0.5]}" : collectionCheck ? "{}" : "{\"detail\":\"" + SECRET + "\"}";
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status)
                .message(status == 200 ? "OK" : "Unprocessable")
                .body(ResponseBody.create(body, HttpSupport.JSON)).build();
        }).build());
        ConsenseProperties props = new ConsenseProperties();
        props.getLlm().setBaseUrl("http://localhost"); props.getVector().setBaseUrl("http://localhost");
        Logger ollamaLog = (Logger) LoggerFactory.getLogger(OllamaLlmClient.class);
        Logger openAiLog = (Logger) LoggerFactory.getLogger(OpenAiLlmClient.class);
        Logger qdrantLog = (Logger) LoggerFactory.getLogger(QdrantVectorStore.class);
        Level ollamaLevel = ollamaLog.getLevel(), openAiLevel = openAiLog.getLevel(), qdrantLevel = qdrantLog.getLevel();
        ollamaLog.setLevel(Level.DEBUG); openAiLog.setLevel(Level.DEBUG); qdrantLog.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> ollama = capture(ollamaLog), openAi = capture(openAiLog), qdrant = capture(qdrantLog);
        try {
            OllamaLlmClient ollamaClient = new OllamaLlmClient(props.getLlm(), http);
            assertEquals(0.5f, ollamaClient.embed(Collections.singletonList("synthetic input")).get(0)[0], 0.001f);
            assertFalse(ollamaClient.available());
            assertFalse(ollamaClient.embeddingAvailable());
            assertFalse(new OpenAiLlmClient(props.getLlm(), http).available());
            QdrantVectorStore vector = new QdrantVectorStore(props.getVector(), http);
            assertEquals(0, vector.count("synthetic-project"));
            assertFalse(vector.available());
            vector.deleteByProject("synthetic-project");
            assertTrue(messages(ollama).contains("Ollama /api/embed 不可用"));
            assertTrue(messages(ollama).contains("Ollama 不可达"));
            assertTrue(messages(ollama).contains("Ollama configured embedding model is unavailable"));
            assertTrue(messages(openAi).contains("OpenAI 兼容端点不可达"));
            assertTrue(messages(qdrant).contains("Qdrant count 失败"));
            assertTrue(messages(qdrant).contains("Qdrant 不可达"));
            assertTrue(messages(qdrant).contains("删除 Qdrant 向量失败"));
            for (ListAppender<ILoggingEvent> logs : Arrays.asList(ollama, openAi, qdrant)) {
                assertTrue(messages(logs).contains("HTTP=422"));
                assertFalse(messages(logs).contains(SECRET));
                for (ILoggingEvent event : logs.list) {
                    assertTrue(event.getFormattedMessage().contains("HTTP=422"));
                    assertNull(event.getThrowableProxy());
                }
            }
        } finally {
            ollamaLog.detachAppender(ollama); openAiLog.detachAppender(openAi); qdrantLog.detachAppender(qdrant);
            ollama.stop(); openAi.stop(); qdrant.stop();
            ollamaLog.setLevel(ollamaLevel); openAiLog.setLevel(openAiLevel); qdrantLog.setLevel(qdrantLevel);
        }
    }
}
