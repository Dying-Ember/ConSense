package com.consense.document;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.ocr.OcrClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class OerNativePdfBatchIntakeTest {
    @Test void normalIntakeKeepsActualFirstBatchBundleAndSecondBatchUnknownWithoutClaimingCompleteQuality() throws Exception {
        DocumentParser.ParsedDocument parsed=parse(response->{});
        JsonNode state=parsed.getCoverage().getNativeTableCandidates();
        assertEquals("partial",state.path("status").asText());assertEquals(JsonUtils.parse("[1,2]"),state.path("requestedPhysicalPages"));
        assertEquals(JsonUtils.parse("[2]"),state.path("unobservedPhysicalPages"));assertFalse(state.path("sourceQualityAccepted").asBoolean());
        List<DocumentBlock> methods=parsed.getBlocks().stream().filter(block->"native_table_candidate".equals(block.getKind())).collect(Collectors.toList());
        assertEquals(3,methods.size());
        for(DocumentBlock method:methods) {
            assertEquals(1,method.getPageNo());
            assertEquals(state.path("batchResults").get(0).path("bundleRef"),method.getNativeTableCandidate().path("bundleRef"));
            assertNotEquals(state.path("bundleRef"),method.getNativeTableCandidate().path("bundleRef"));
        }
        assertTrue(parsed.getCoverage().getLimitations().stream().anyMatch(text->text.contains("not observed for physical pages [2]")));
        assertTrue(parsed.getCoverage().getNeedsReviewPages().containsAll(List.of(1,2)));
    }

    @Test void absentAggregateMarkerOrForgedMissingPageBasisCannotBypassNormalWholePageGuard() throws Exception {
        List<Consumer<ObjectNode>> mutations=List.of(response->response.remove("transportSchema"),
                response->((ArrayNode)response.path("unobservedPhysicalPages")).removeAll(),
                response->((ObjectNode)response.path("batchResults").get(1)).remove("failure"),
                response->((ObjectNode)response.path("pages").get(0)).set("bundleRef",response.path("bundleRef")),
                response->((ArrayNode)response.path("pages")).add(response.path("pages").get(0)),
                response->((ArrayNode)response.path("batchResults").get(1).path("selectedPhysicalPages")).add(1));
        for(Consumer<ObjectNode> mutation:mutations) {
            DocumentParser.ParsedDocument parsed=parse(mutation);
            assertEquals("native_candidate_task_failed",parsed.getCoverage().getNativeTableCandidates().path("status").asText());
            assertTrue(parsed.getBlocks().stream().noneMatch(block->"native_table_candidate".equals(block.getKind())));
        }
    }

    @Test void successAfterAnEarlierFailedBatchIsRejectedForAlternateClients() throws Exception {
        DocumentParser.ParsedDocument parsed=parse(response->{
            ArrayNode batches=(ArrayNode)response.path("batchResults");
            ObjectNode success=((ObjectNode)batches.get(0)).deepCopy(),failure=((ObjectNode)batches.get(1)).deepCopy();
            success.put("batchIndex",1);success.set("selectedPhysicalPages",JsonUtils.parse("[2]"));
            success.set("responseObservedPhysicalPages",JsonUtils.parse("[2]"));
            failure.put("batchIndex",0);failure.set("selectedPhysicalPages",JsonUtils.parse("[1]"));
            batches.removeAll();batches.add(failure);batches.add(success);
            ((ObjectNode)response.path("pages").get(0)).put("physicalPageNo",2);
            response.set("unobservedPhysicalPages",JsonUtils.parse("[1]"));
        });
        assertEquals("native_candidate_task_failed",parsed.getCoverage().getNativeTableCandidates().path("status").asText());
        assertTrue(parsed.getBlocks().stream().noneMatch(block->"native_table_candidate".equals(block.getKind())));
    }

    private static DocumentParser.ParsedDocument parse(Consumer<ObjectNode> mutation) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(PDDocument original=PDDocument.load(OerEngineIntegrationTest.vectorScopePdf(true));PDDocument pdf=new PDDocument()) {
            pdf.importPage(original.getPage(0));pdf.importPage(original.getPage(0));pdf.save(bytes);
        }
        byte[] original=bytes.toByteArray();ConsenseProperties properties=new ConsenseProperties();properties.getOcr().setEnabled(false);
        OcrClient client=new OcrClient() {
            @Override public boolean available(){throw new AssertionError("Native CPU candidate intake must not enable raster OCR");}
            @Override public OcrResult recognize(byte[] image){throw new AssertionError("No model/visual parse in this test");}
            @Override public JsonNode observeNativePdf(byte[] actual,Map<String,Object> context) {
                assertArrayEquals(original,actual);assertEquals(JsonUtils.parse("[1,2]"),JsonUtils.mapper().valueToTree(context.get("selectedPhysicalPages")));
                ObjectNode first=OerEngineIntegrationTest.nativeTableReply(Map.of("source",context.get("source"),"selectedPhysicalPages",List.of(1)));
                ObjectNode aggregate=first.deepCopy();aggregate.put("transportSchema","consense.native-pdf-batch-transport.v1");
                aggregate.set("selectedPhysicalPages",JsonUtils.parse("[1,2]"));aggregate.set("unobservedPhysicalPages",JsonUtils.parse("[2]"));
                aggregate.put("sourceQualityAccepted",false);aggregate.put("canonicalAdopted",false);
                ((ObjectNode)aggregate.path("pages").get(0)).set("bundleRef",first.path("bundleRef"));
                ObjectNode composite=aggregate.putObject("bundleRef");composite.put("schema","consense.native-pdf-batch-bundle-refs.v1");composite.putArray("batchBundleRefs").add(first.path("bundleRef"));
                ArrayNode batches=aggregate.putArray("batchResults");ObjectNode complete=first.deepCopy();complete.remove("pages");complete.put("batchIndex",0);
                complete.set("responseObservedPhysicalPages",JsonUtils.parse("[1]"));batches.add(complete);
                ObjectNode failed=batches.addObject();failed.put("batchIndex",1);failed.put("status","failed");failed.set("source",first.path("source"));
                failed.set("selectedPhysicalPages",JsonUtils.parse("[2]"));failed.putArray("responseObservedPhysicalPages");
                failed.putObject("failure").put("type","NativePdfBatchTransportFailure").put("message","Actual test transport failure retained as unknown");
                aggregate.putObject("failure").put("type","NativePdfBatchIncomplete");mutation.accept(aggregate);return aggregate;
            }
        };
        DocumentParser parser=new DocumentParser(properties,client);DocumentParser.ParsedDocument observation=parser.observe("gcc.pdf",original);
        OerDocumentIdentity identity=new OerSourceRouting().route("gcc.pdf",DocumentParseProbe.sha256(original),observation);
        return parser.parse("gcc.pdf",original,observation,identity);
    }
}
