package com.consense.ocr;

import com.consense.ai.HttpSupport;
import com.consense.common.BizException;
import com.consense.config.ConsenseProperties;
import okhttp3.MultipartBody;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PaddleOcrRequestTransportTest {
    @Test void identityAndParseHaveOneShotBodiesBoundedRepliesAndOneCallerDeadline() {
        for (boolean identity : new boolean[]{false, true}) {
            AtomicInteger calls = new AtomicInteger(); long started = System.currentTimeMillis();
            HttpSupport http = new HttpSupport() {
                @Override public String postMultipart(String url, MultipartBody body, long timeout, Map<String,String> headers, int cap) {
                    calls.incrementAndGet();
                    assertEquals(32*1024*1024, cap);
                    assertTrue(body.parts().stream().anyMatch(part -> part.body().isOneShot()));
                    assertTrue(headers.get("X-Consense-Ocr-Task-Id").matches("[a-f0-9]{32}"));
                    long deadline = Long.parseLong(headers.get("X-Consense-Ocr-Deadline-Ms"));
                    assertTrue(deadline >= started + 2000 && deadline <= System.currentTimeMillis() + 2000);
                    assertEquals(identity ? "identity" : "parse", headers.get("X-Consense-Ocr-Purpose"));
                    return "{\"text\":\"authored\",\"lines\":[]}";
                }
            };
            PaddleOcrClient client = client(http);
            assertEquals("authored", (identity ? client.observeIdentity(new byte[]{1}) : client.recognize(new byte[]{1})).getText());
            assertEquals(1, calls.get());
        }
    }

    @Test void unknownOutcomeQueriesAndCancelsOnlyItsOwnTaskWithoutAnotherPost() {
        AtomicInteger posts = new AtomicInteger(), queries = new AtomicInteger(), cancellations = new AtomicInteger();
        String[] task = {null};
        HttpSupport http = new HttpSupport() {
            @Override public String postMultipart(String url, MultipartBody body, long timeout, Map<String,String> headers, int cap) {
                posts.incrementAndGet(); task[0] = headers.get("X-Consense-Ocr-Task-Id");
                throw new BizException("Authored disconnected response");
            }
            @Override public String getBounded(String url, long timeout, int cap) {
                queries.incrementAndGet(); assertTrue(url.endsWith("/ocr/tasks/" + task[0]));
                assertTrue(timeout <= 2000); assertEquals(65536, cap);
                return "{\"taskId\":\"" + task[0] + "\",\"status\":\"running\"}";
            }
            @Override public String deleteBounded(String url, long timeout, int cap) {
                cancellations.incrementAndGet(); assertTrue(url.endsWith("/ocr/tasks/" + task[0]));
                return "{\"status\":\"running\",\"cancellationRequested\":true}";
            }
        };
        BizException failure = assertThrows(BizException.class, () -> client(http).recognize(new byte[]{1}));
        assertTrue(failure.getMessage().contains(task[0]));
        assertEquals(1, posts.get()); assertEquals(1, queries.get()); assertEquals(1, cancellations.get());
    }

    @Test void lostPostResponseRecoversCompletedSameTaskWithoutAnotherPostOrCancellation() {
        AtomicInteger posts = new AtomicInteger(), statusQueries = new AtomicInteger(), resultQueries = new AtomicInteger(), cancellations = new AtomicInteger();
        String[] task = {null};
        HttpSupport http = new HttpSupport() {
            @Override public String postMultipart(String url, MultipartBody body, long timeout, Map<String,String> headers, int cap) {
                posts.incrementAndGet(); task[0] = headers.get("X-Consense-Ocr-Task-Id");
                throw new BizException("Authored lost completed POST response");
            }
            @Override public String getBounded(String url, long timeout, int cap) {
                assertNotNull(task[0]); assertTrue(timeout <= 2000);
                String taskUrl = "http://127.0.0.1:1/ocr/tasks/" + task[0];
                if (url.equals(taskUrl)) {
                    statusQueries.incrementAndGet(); assertEquals(65536, cap);
                    return "{\"taskId\":\"" + task[0] + "\",\"status\":\"completed\",\"resultRef\":{\"byteCount\":128,\"sha256\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\"}}";
                }
                assertEquals(taskUrl + "/result", url); assertEquals(32*1024*1024, cap);
                resultQueries.incrementAndGet();
                return "{\"text\":\"authored recovered result\",\"lines\":[],\"provenance\":{\"requestTaskId\":\"" + task[0] + "\"}}";
            }
            @Override public String deleteBounded(String url, long timeout, int cap) {
                cancellations.incrementAndGet(); fail("A completed task must not be cancelled"); return "{}";
            }
        };
        assertEquals("authored recovered result", client(http).recognize(new byte[]{1}).getText());
        assertEquals(1, posts.get()); assertEquals(1, statusQueries.get()); assertEquals(1, resultQueries.get()); assertEquals(0, cancellations.get());
    }

    private static PaddleOcrClient client(HttpSupport http) {
        ConsenseProperties.Ocr config = new ConsenseProperties().getOcr();
        config.setMode("local"); config.setBaseUrl("http://127.0.0.1:1"); config.setTimeoutMs(2000);
        return new PaddleOcrClient(config, http);
    }
}
