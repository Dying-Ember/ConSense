package com.consense.service.vetting;

import com.consense.ai.HttpSupport;
import com.consense.common.BizException;
import okhttp3.OkHttpClient;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class HttpDiagnosticCompatibilityTest {
    private HttpSupport.HttpResponseFailure rejected(int status, String body) {
        return rejected(status, body.getBytes(StandardCharsets.UTF_8));
    }

    private HttpSupport.HttpResponseFailure rejected(int status, byte[] body) {
        return rejected(status, body, HttpSupport.JSON);
    }

    private HttpSupport.HttpResponseFailure rejected(int status, byte[] body, MediaType mediaType) {
        HttpSupport http = new HttpSupport();
        OkHttpClient original = (OkHttpClient) ReflectionTestUtils.getField(http, "shared");
        ReflectionTestUtils.setField(http, "shared", original.newBuilder().addInterceptor(chain ->
            new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Rejected")
                .body(ResponseBody.create(body, mediaType)).build()
        ).build());
        return assertThrows(HttpSupport.HttpResponseFailure.class, () -> http.get("http://localhost/test", 1000));
    }

    @Test void unboundedFailurePreservesLargeBodyTruncationAndDecoding() {
        String kindPrefix = "{\"detail\":{\"kind\":\"QDRANT_BACKEND_UNAVAILABLE\",\"message\":\"";
        String large = kindPrefix + "x".repeat(1024 * 1024) + "\"}}";
        HttpSupport.HttpResponseFailure largeFailure = rejected(503, large);
        assertNull(largeFailure.errorKind());
        assertTrue(largeFailure.getMessage().endsWith(large.substring(0, 300) + "..."));

        String latin = "{\"detail\":{\"kind\":\"QDRANT_BACKEND_UNAVAILABLE\",\"message\":\"caf\u00e9\"}}";
        HttpSupport.HttpResponseFailure latinFailure = rejected(503, latin.getBytes(StandardCharsets.ISO_8859_1),
                MediaType.get("application/json; charset=iso-8859-1"));
        assertNull(latinFailure.errorKind());
        assertTrue(latinFailure.getMessage().endsWith(latin));

        byte[] json = latin.getBytes(StandardCharsets.UTF_8);
        byte[] bom = new byte[json.length + 3];
        bom[0] = (byte) 0xef; bom[1] = (byte) 0xbb; bom[2] = (byte) 0xbf;
        System.arraycopy(json, 0, bom, 3, json.length);
        HttpSupport.HttpResponseFailure bomFailure = rejected(503, bom, MediaType.get("application/json; charset=iso-8859-1"));
        assertTrue(bomFailure.getMessage().endsWith(latin));
    }

    @Test void kindRequiresValidUtf8WithinByteLimit() {
        String prefix = "{\"detail\":{\"kind\":\"QDRANT_BACKEND_UNAVAILABLE\",\"message\":\"";
        String suffix = "\"}}";
        int fixedBytes = (prefix + suffix).getBytes(StandardCharsets.UTF_8).length;
        String within = prefix + "x".repeat(4096 - fixedBytes - 3) + "中" + suffix;
        assertEquals(4096, within.getBytes(StandardCharsets.UTF_8).length);
        assertEquals("QDRANT_BACKEND_UNAVAILABLE", rejected(503, within).errorKind());
        String over = prefix + "x".repeat(4096 - fixedBytes - 2) + "中" + suffix;
        assertEquals(4097, over.getBytes(StandardCharsets.UTF_8).length);
        assertTrue(over.length() < 4096);
        assertNull(rejected(503, over).errorKind());

        byte[] before = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] after = suffix.getBytes(StandardCharsets.UTF_8);
        byte[] invalid = new byte[before.length + 1 + after.length];
        System.arraycopy(before, 0, invalid, 0, before.length);
        invalid[before.length] = (byte) 0xff;
        System.arraycopy(after, 0, invalid, before.length + 1, after.length);
        HttpSupport.HttpResponseFailure failure = rejected(503, invalid);
        assertNull(failure.errorKind());
        assertTrue(failure.getMessage().contains("\ufffd"));
    }

    @Test void boundedFailureRetainsMessageAndKind() {
        HttpSupport http = new HttpSupport();
        OkHttpClient original = (OkHttpClient) ReflectionTestUtils.getField(http, "shared");
        String body = "{\"detail\":{\"kind\":\"QDRANT_BACKEND_UNAVAILABLE\",\"message\":\"service down\"}}";
        ReflectionTestUtils.setField(http, "shared", original.newBuilder().addInterceptor(chain ->
            new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("Rejected")
                .body(ResponseBody.create(body, HttpSupport.JSON)).build()
        ).build());
        HttpSupport.HttpResponseFailure failure = assertThrows(HttpSupport.HttpResponseFailure.class,
                () -> http.getBounded("http://localhost/test", 1000, 4096));
        assertEquals("QDRANT_BACKEND_UNAVAILABLE", failure.errorKind());
        assertTrue(failure.getMessage().contains(body));
        assertThrows(BizException.class, () -> http.getBounded("http://localhost/test", 1000, 8));
    }

    @Test void onlyKnownComplete503KindsAreExposed() {
        for (String kind : new String[]{"QDRANT_BACKEND_UNAVAILABLE", "QDRANT_BACKEND_RELEASE_UNKNOWN"}) {
            HttpSupport.HttpResponseFailure failure = rejected(503,
                    "{\"detail\":{\"kind\":\"" + kind + "\",\"message\":\"synthetic-secret\"}}");
            assertEquals(503, failure.httpStatus());
            assertEquals(kind, failure.errorKind());
            assertFalse(failure.errorKind().contains("synthetic-secret"));
        }
        String known = "{\"detail\":{\"kind\":\"QDRANT_BACKEND_UNAVAILABLE\"}}";
        assertNull(rejected(422, known).errorKind());
        assertNull(rejected(503, "{\"detail\":{\"kind\":\"synthetic-secret\"}}").errorKind());
        assertNull(rejected(503, "{\"detail\":{\"kind\":42}}").errorKind());
        assertNull(rejected(503, known + " {}").errorKind());
        assertNull(rejected(503, "{\"detail\":{\"kind\":\"QDRANT_BACKEND_UNAVAILABLE\",\"kind\":\"QDRANT_BACKEND_RELEASE_UNKNOWN\"}}").errorKind());
        assertNull(rejected(503, "{\"detail\":{\"kind\":\"QDRANT_BACKEND_UNAVAILABLE\",\"message\":\"" + "x".repeat(4096) + "\"}}").errorKind());
    }

    @Test void realHttpSupportFailureStillDrivesQueryBudgetDecision() {
        HttpSupport http = new HttpSupport();
        OkHttpClient original = (OkHttpClient) ReflectionTestUtils.getField(http, "shared");
        ReflectionTestUtils.setField(http, "shared", original.newBuilder().addInterceptor(chain ->
            new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(422).message("Unprocessable")
                .body(ResponseBody.create("{\"detail\":{\"kind\":\"QUERY_TOKEN_BUDGET_EXHAUSTED\"}}", HttpSupport.JSON)).build()
        ).build());
        BizException failure = assertThrows(BizException.class, () -> http.get("http://localhost/test", 1000));
        assertTrue(OerCanonicalRetrievalClient.queryBudgetRejected(failure));
        assertFalse(OerCanonicalRetrievalClient.queryBudgetRejected(new BizException("HTTP 422 {\"detail\":{\"kind\":\"OTHER\"}}")));
    }
}
