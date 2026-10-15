package com.consense.ocr;

import com.consense.ai.HttpSupport;
import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParseProbe;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okio.Buffer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaddleNativePdfTransportTest {
    private static final byte[] PDF="%PDF-actual-complete-original".getBytes(StandardCharsets.US_ASCII);
    private static final int CONTEXT_CAP=32*1024*1024;

    @Test void exactly64PagesRemainOneWholeFileRequestAnd65PagesPreserveBothBatchBundles() throws Exception {
        for(int count:new int[]{64,65}) {
            Fixture fixture=new Fixture();Map<String,Object> context=context(count);
            JsonNode result=fixture.client.observeNativePdf(PDF,context);
            assertEquals(count==64?1:2,fixture.requests.size());
            assertEquals(JsonUtils.mapper().valueToTree(context.get("selectedPhysicalPages")),result.path("selectedPhysicalPages"));
            assertEquals(count,result.path("pages").size());
            assertEquals("candidate_observed",result.path("status").asText());
            assertFalse(result.path("sourceQualityAccepted").asBoolean());
            if(count==65) {
                assertEquals(2,result.path("batchResults").size());
                assertEquals(2,result.path("bundleRef").path("batchBundleRefs").size());
                assertEquals("batch-1",result.path("pages").get(0).path("bundleRef").path("bundleSha256").asText());
                assertEquals("batch-2",result.path("pages").get(64).path("bundleRef").path("bundleSha256").asText());
            }
            for(int i=0;i<fixture.requests.size();i++) {
                JsonNode request=fixture.requests.get(i);assertTrue(request.path("selectedPhysicalPages").size()<=64);
                assertEquals(JsonUtils.mapper().valueToTree(context.get("readingPlan")),request.path("readingPlan"));
                assertEquals("retain complete arbitrary metadata",request.path("additionalMetadata").asText());
                for(JsonNode page:request.path("javaNativeEvidence")) {
                    assertEquals("complete-vector-operators",page.path("vectorPathOperators").get(0).asText());
                    assertEquals("complete-painted-regions",page.path("paintedVectorRegions").get(0).asText());
                    assertEquals("complete-glyphs",page.path("glyphs").get(0).asText());
                    assertTrue(request.path("selectedPhysicalPages").toString().contains(page.path("pageNo").asText()));
                }
            }
            assertTemporaryBodiesDeleted(fixture);
        }
    }

    @Test void largeContextUsesJsonFilePartAndSplitsByUtf8BytesWithoutDroppingEvidence() throws Exception {
        Fixture fixture=new Fixture();Map<String,Object> context=context(2);
        String unicode="界".repeat(6*1024*1024);
        for(Map<String,Object> evidence:evidence(context))evidence.put("fullRawUnicode",unicode);
        JsonNode result=fixture.client.observeNativePdf(PDF,context);
        assertEquals(2,fixture.requests.size());assertEquals(2,result.path("pages").size());
        for(int index=0;index<2;index++) {
            assertTrue(fixture.contextLengths.get(index)>8L*1024*1024);
            assertTrue(fixture.contextLengths.get(index)<=CONTEXT_CAP);
            assertEquals(unicode,fixture.requests.get(index).path("javaNativeEvidence").get(0).path("fullRawUnicode").asText());
        }
        assertTemporaryBodiesDeleted(fixture);
    }

    @Test void exact32MiBBoundaryPassesAndSinglePageOneByteOverflowFailsBeforeDispatch() throws Exception {
        for(int overflow:new int[]{0,1}) {
            Fixture fixture=new Fixture();Map<String,Object> context=context(1);Map<String,Object> first=evidence(context).get(0);
            first.put("padding","");int base=JsonUtils.mapper().writeValueAsBytes(context).length;
            first.put("padding","x".repeat(CONTEXT_CAP-base+overflow));
            if(overflow==0) {
                assertEquals(1,fixture.client.observeNativePdf(PDF,context).path("pages").size());
                assertEquals((long)CONTEXT_CAP,fixture.contextLengths.get(0));assertTemporaryBodiesDeleted(fixture);
            } else {
                assertThrows(BizException.class,()->fixture.client.observeNativePdf(PDF,context));
                assertEquals(0,fixture.requests.size());
            }
        }
    }

    @Test void laterTransportFailurePreservesEarlierActualPagesAndEntireRemainingDenominator() throws Exception {
        Fixture fixture=new Fixture();fixture.failureAt=2;
        JsonNode result=fixture.client.observeNativePdf(PDF,context(130));
        assertEquals(2,fixture.requests.size());assertEquals(64,result.path("pages").size());
        assertEquals(130,result.path("selectedPhysicalPages").size());assertEquals(66,result.path("unobservedPhysicalPages").size());
        assertEquals("partial",result.path("status").asText());
        assertEquals("failed",result.path("batchResults").get(1).path("status").asText());
        assertEquals("not_executed",result.path("batchResults").get(2).path("status").asText());
        assertEquals(1,result.path("bundleRef").path("batchBundleRefs").size());assertTemporaryBodiesDeleted(fixture);
    }

    @Test void laterInvalidResponseCannotReplaceItsBatchWithSuccessOrDiscardEarlierEvidence() throws Exception {
        List<Consumer<ObjectNode>> corruptions=List.of(
                response->((ArrayNode)response.path("pages")).remove(0),
                response->((ObjectNode)response.path("source")).put("sourceSha256","0".repeat(64)),
                response->((ArrayNode)response.path("pages").get(0).path("methods")).remove(1),
                response->((ArrayNode)response.path("pages")).add(response.path("pages").get(0)));
        for(Consumer<ObjectNode> corruption:corruptions) {
            Fixture fixture=new Fixture();fixture.corruptSecond=corruption;
            JsonNode result=fixture.client.observeNativePdf(PDF,context(65));
            assertEquals("partial",result.path("status").asText());assertEquals(64,result.path("pages").size());
            assertEquals(JsonUtils.parse("[65]"),result.path("unobservedPhysicalPages"));
            assertEquals("failed",result.path("batchResults").get(1).path("status").asText());
            assertTrue(result.path("batchResults").get(1).path("invalidResponse").isObject());
            assertTemporaryBodiesDeleted(fixture);
        }
    }

    @Test void singleBatchCleanupFailureRetainsActualResponseAndReportsExactResidualPath() throws Exception {
        for(boolean wholeFailure:new boolean[]{false,true}) {
            Fixture fixture=new Fixture();fixture.cleanupFailureAt=1;fixture.failWholeResponse=wholeFailure;
            try {
            JsonNode result=assertDoesNotThrow(()->fixture.client.observeNativePdf(PDF,context(1)));
            assertEquals(wholeFailure?"failed":"partial",result.path("status").asText());assertEquals(wholeFailure?0:1,result.path("pages").size());
            assertEquals("batch-1",result.path("bundleRef").path("batchBundleRefs").get(0).path("bundleSha256").asText());
            assertEquals("not_cleaned",result.path("batchResults").get(0).path("transportCleanupFailure").path("cleanupStatus").asText());
            assertEquals(fixture.residuals.get(0).toAbsolutePath().toString(),result.path("transportCleanupFailures").get(0).path("path").asText());
            assertFalse(result.path("sourceQualityAccepted").asBoolean());assertEquals(wholeFailure?1:0,result.path("unobservedPhysicalPages").size());
            if(wholeFailure)assertEquals("ActualWholeProviderFailure",result.path("batchResults").get(0).path("failure").path("type").asText());
            } finally {fixture.removeOwnedResiduals();}
        }
    }

    @Test void laterCleanupFailureStopsDispatchAndPreservesBothEarlierActualBatchBundles() throws Exception {
        Fixture fixture=new Fixture();fixture.cleanupFailureAt=2;
        try {
            JsonNode result=assertDoesNotThrow(()->fixture.client.observeNativePdf(PDF,context(130)));
            assertEquals(2,fixture.requests.size());assertEquals(128,result.path("pages").size());
            assertEquals(JsonUtils.parse("[129,130]"),result.path("unobservedPhysicalPages"));
            assertEquals(2,result.path("bundleRef").path("batchBundleRefs").size());
            assertEquals("not_executed",result.path("batchResults").get(2).path("status").asText());
            assertEquals(1,result.path("transportCleanupFailures").size());assertEquals("partial",result.path("status").asText());
        } finally {fixture.removeOwnedResiduals();}
    }

    @Test void cleanupAndTransportFailurePreserveOriginalFailureAndEarlierSuccessfulScope() throws Exception {
        Fixture fixture=new Fixture();fixture.cleanupFailureAt=2;fixture.failureAt=2;
        try {
            JsonNode result=assertDoesNotThrow(()->fixture.client.observeNativePdf(PDF,context(130)));
            assertEquals(64,result.path("pages").size());assertEquals(66,result.path("unobservedPhysicalPages").size());
            assertEquals("actual second transport failed",result.path("batchResults").get(1).path("failure").path("message").asText());
            assertEquals("not_cleaned",result.path("batchResults").get(1).path("transportCleanupFailure").path("cleanupStatus").asText());
            assertEquals("not_executed",result.path("batchResults").get(2).path("status").asText());
        } finally {fixture.removeOwnedResiduals();}
    }

    @SuppressWarnings("unchecked") private static List<Map<String,Object>> evidence(Map<String,Object> context) {
        return (List<Map<String,Object>>)context.get("javaNativeEvidence");
    }
    private static Map<String,Object> context(int count) {
        Map<String,Object> context=new LinkedHashMap<>();
        context.put("source",Map.of("sourceFilename","source.pdf","sourceSha256",DocumentParseProbe.sha256(PDF),"mediaType","application/pdf","pageCount",count));
        context.put("selectedPhysicalPages",IntStream.rangeClosed(1,count).boxed().collect(Collectors.toList()));
        context.put("readingPlan",Map.of("sourceSha256",DocumentParseProbe.sha256(PDF),"identityStatus","content_supported"));
        context.put("additionalMetadata","retain complete arbitrary metadata");List<Map<String,Object>> evidence=new ArrayList<>();
        for(int page=1;page<=count;page++)evidence.add(new LinkedHashMap<>(Map.of("pageNo",page,"glyphs",List.of("complete-glyphs"),
                "vectorPathOperators",List.of("complete-vector-operators"),"paintedVectorRegions",List.of("complete-painted-regions"))));
        context.put("javaNativeEvidence",evidence);return context;
    }
    private static void assertTemporaryBodiesDeleted(Fixture fixture) {
        for(RequestBody body:fixture.contextBodies)assertThrows(IOException.class,()->body.writeTo(new Buffer()));
    }
    private static ObjectNode reply(JsonNode context,int index) {
        ObjectNode result=JsonUtils.mapper().createObjectNode();result.put("schema","consense.native-pdf-candidates.v1");
        result.put("status","candidate_observed");result.set("source",context.path("source").deepCopy());
        result.set("selectedPhysicalPages",context.path("selectedPhysicalPages").deepCopy());result.put("sourceQualityAccepted",false);result.put("canonicalAdopted",false);
        result.putObject("bundleRef").put("bundleSha256","batch-"+index);ArrayNode pages=result.putArray("pages");
        for(JsonNode physical:context.path("selectedPhysicalPages")) {
            ObjectNode page=pages.addObject();page.set("physicalPageNo",physical);page.putObject("frame").put("coordinateSpace","PDF_points_crop_top_left");
            ArrayNode methods=page.putArray("methods");
            for(String name:List.of("pdfplumber_lines_lines","camelot_network","camelot_stream")) {
                ObjectNode method=methods.addObject();method.put("method",name);method.put("provider","actual-provider");method.put("status","candidate_observed");
                ObjectNode candidate=method.putObject("candidate");candidate.put("schema","consense.native-pdf-table-candidate.v1");candidate.putArray("tables");candidate.putArray("conditionCandidates");
            }
        }
        return result;
    }
    private static final class Fixture {
        final List<JsonNode> requests=new ArrayList<>();final List<Long> contextLengths=new ArrayList<>();final List<RequestBody> contextBodies=new ArrayList<>();
        final List<Path> residuals=new ArrayList<>();final java.util.Set<Path> existingTemporaryContexts;
        final PaddleOcrClient client;int failureAt=-1,cleanupFailureAt=-1;boolean failWholeResponse;Consumer<ObjectNode> corruptSecond;
        Fixture() throws Exception {
            existingTemporaryContexts=temporaryContexts();
            ConsenseProperties.Ocr cfg=new ConsenseProperties().getOcr();cfg.setMode("local");HttpSupport http=mock(HttpSupport.class);client=new PaddleOcrClient(cfg,http);
            when(http.postMultipart(endsWith("/native-pdf/table-candidates"),any(),anyLong(),anyMap(),eq(8*1024*1024))).thenAnswer(invocation->{
                MultipartBody body=invocation.getArgument(1);assertEquals(2,body.parts().size());Buffer original=new Buffer();body.part(0).body().writeTo(original);assertArrayEquals(PDF,original.readByteArray());
                assertTrue(body.part(1).body().isOneShot(),"The shared multipart boundary must preserve the native context's one-shot flag");
                RequestBody json=body.part(1).body();contextBodies.add(json);
                assertTrue(body.part(1).headers().get("Content-Disposition").contains("filename=\"native-context.json\""));
                assertEquals("application/json",json.contentType().toString());contextLengths.add(json.contentLength());
                Buffer buffer=new Buffer();json.writeTo(buffer);assertEquals(json.contentLength(),buffer.size());JsonNode context=JsonUtils.parse(buffer.readUtf8());requests.add(context);
                if(requests.size()==cleanupFailureAt) {
                    java.util.Set<Path> owned=temporaryContexts();owned.removeAll(existingTemporaryContexts);owned.removeAll(residuals);assertEquals(1,owned.size());
                    Path file=owned.iterator().next();Files.delete(file);Files.createDirectory(file);residuals.add(file);Files.writeString(file.resolve("owned-test-marker"),"Force an actual DirectoryNotEmptyException during native context cleanup");
                }
                if(requests.size()==failureAt)throw new BizException("actual second transport failed");
                ObjectNode result=reply(context,requests.size());
                if(failWholeResponse){result.put("status","failed");result.putArray("pages");result.putObject("failure").put("type","ActualWholeProviderFailure");}
                if(requests.size()==2 && corruptSecond!=null)corruptSecond.accept(result);return JsonUtils.write(result);
            });
        }
        private void removeOwnedResiduals() throws IOException {
            for(Path residual:residuals){Files.deleteIfExists(residual.resolve("owned-test-marker"));Files.deleteIfExists(residual);}
        }
        private static java.util.Set<Path> temporaryContexts() throws IOException {
            try(java.util.stream.Stream<Path> entries=Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
                return entries.filter(path->path.getFileName().toString().startsWith("consense-native-context-")).collect(Collectors.toSet());
            }
        }
    }
}
