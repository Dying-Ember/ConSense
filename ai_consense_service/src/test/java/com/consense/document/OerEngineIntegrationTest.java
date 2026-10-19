package com.consense.document;

import com.consense.ai.HttpSupport;
import com.consense.config.ConsenseProperties;
import com.consense.common.JsonUtils;
import com.consense.domain.SourceDocument;
import com.consense.service.sources.OerSourceMetadata;
import javax.imageio.ImageIO;
import com.consense.ocr.OcrClient;
import com.consense.ocr.PaddleOcrClient;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OerEngineIntegrationTest {
    @Test void denseNativeBorderlessColumnsRequestWholeCpuCandidatesWithoutRasterOrTableClaims() throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(PDDocument pdf=new PDDocument()) {
            for(int pageNo=1;pageNo<=2;pageNo++){
                PDPage page=new PDPage();pdf.addPage(page);
                try(PDPageContentStream stream=new PDPageContentStream(pdf,page)){
                    stream.beginText();stream.setFont(PDType1Font.HELVETICA,10);stream.newLineAtOffset(30,750);
                    stream.showText("General Conditions of Contract");stream.endText();
                    for(int row=0;row<8;row++){
                        stream.beginText();stream.setFont(PDType1Font.HELVETICA,10);stream.newLineAtOffset(30,720-row*20);
                        stream.showText(pageNo==1?"Native item "+row:"Actual complete ordinary source paragraph remains unchanged.");
                        if(pageNo==1){stream.newLineAtOffset(190,0);stream.showText("Quantity "+row);stream.newLineAtOffset(160,0);stream.showText("Measured amount");}
                        stream.endText();
                    }
                }
            }pdf.save(out);
        }
        byte[] original=out.toByteArray();List<Map<String,Object>> calls=new ArrayList<>();
        OcrClient client=new OcrClient(){
            public boolean available(){fail("Dense native borderless text must not require raster OCR readiness");return false;}
            public OcrResult recognize(byte[] ignored){throw new AssertionError("Native candidates use the original PDF only");}
            @Override public com.fasterxml.jackson.databind.JsonNode observeNativePdf(byte[] actual,Map<String,Object> context){assertArrayEquals(original,actual);calls.add(context);return nativeTableReply(context);}
        };
        DocumentParser parser=new DocumentParser(new ConsenseProperties(),client);
        DocumentParser.ParsedDocument observation=parser.observe("arbitrary-source.pdf",original);
        OerDocumentIdentity identity=new OerSourceRouting().route("arbitrary-source.pdf",DocumentParseProbe.sha256(original),observation);
        DocumentParser.ParsedDocument result=parser.parse("arbitrary-source.pdf",original,observation,identity);
        assertEquals(1,calls.size(),"Spatially separated native text rows need CPU structure observations even with no painted lines/images");
        com.fasterxml.jackson.databind.JsonNode context=JsonUtils.mapper().valueToTree(calls.get(0));assertEquals(JsonUtils.parse("[1]"),context.path("selectedPhysicalPages"));
        assertEquals(2,context.path("source").path("pageCount").asInt());assertEquals(0,result.getCoverage().getOcrPages());
        assertTrue(result.getNativePdfEvidence().get(0).getPaintedVectorRegions().isEmpty());assertTrue(result.getNativePdfEvidence().get(0).getPlacedImageRegions().isEmpty());
        assertTrue(result.getText().contains("Native item 7"));assertTrue(result.getText().contains("Actual complete ordinary source paragraph"));
        com.fasterxml.jackson.databind.JsonNode state=result.getCoverage().getNativeTableCandidates();assertEquals(JsonUtils.parse("[2]"),state.path("unselectedPhysicalPages"));
        assertEquals("unknown",state.path("unselectedNativeTableStatus").asText());assertFalse(state.path("sourceQualityAccepted").asBoolean());assertFalse(state.path("canonicalAdopted").asBoolean());
    }
    @Test void reviewedWholePageSelectionKeepsItsExactRegionInventoryAcrossTheWire() throws Exception {
        byte[] original=scanPdf();com.fasterxml.jackson.databind.node.ObjectNode group=(com.fasterxml.jackson.databind.node.ObjectNode)wholePageGroup(DocumentParseProbe.sha256(original));
        group.put("selectionStatus","source_review_selected_candidate");group.put("selectedParser","mineru");group.put("reviewManifestSha256",String.join("",Collections.nCopies(64,"c")));
        com.fasterxml.jackson.databind.JsonNode candidate=group.path("candidates").get(1);com.fasterxml.jackson.databind.node.ArrayNode reviewed=group.putArray("reviewedBlockIds");
        ((com.fasterxml.jackson.databind.node.ObjectNode)candidate.path("features")).put("allGeometryBound",true);
        for(com.fasterxml.jackson.databind.JsonNode block:candidate.path("blocks")){((com.fasterxml.jackson.databind.node.ObjectNode)block).set("blockId",block.path("id"));reviewed.add(block.path("blockId"));}
        ConsenseProperties properties=new ConsenseProperties();properties.getOcr().setMode("local");HttpSupport http=mock(HttpSupport.class);
        when(http.postMultipart(anyString(),any(),anyLong())).thenReturn("{\"text\":\"reviewed whole source\",\"wholePageGroups\":["+group+"]}");
        PaddleOcrClient client=new PaddleOcrClient(properties.getOcr(),http);Map<String,Object> context=DocumentParseProbe.map("physicalPageNo",1,"source",DocumentParseProbe.map("sourceSha256",DocumentParseProbe.sha256(original)));
        assertEquals("mineru",client.recognize(new byte[]{1},context).getWholePageGroups().get(0).path("selectedParser").asText());
        for(int mode=0;mode<6;mode++) {
            com.fasterxml.jackson.databind.node.ObjectNode bad=group.deepCopy();
            if(mode==0)((com.fasterxml.jackson.databind.node.ArrayNode)bad.path("reviewedBlockIds")).removeAll();
            if(mode==1)bad.put("reviewManifestSha256","unbound");
            if(mode==2)bad.put("selectedParser","paddleocr-vl");
            if(mode==3)((com.fasterxml.jackson.databind.node.ArrayNode)bad.path("reviewedBlockIds")).add(reviewed.get(0));
            if(mode==4)((com.fasterxml.jackson.databind.node.ObjectNode)bad.path("candidates").get(1).path("features")).put("completeRawCapture",false);
            if(mode==5)((com.fasterxml.jackson.databind.node.ObjectNode)bad.path("candidates").get(1).path("blocks").get(0)).remove("blockId");
            when(http.postMultipart(anyString(),any(),anyLong())).thenReturn("{\"wholePageGroups\":["+bad+"]}");
            assertThrows(com.consense.common.BizException.class,()->client.recognize(new byte[]{1},context),"Bad reviewed inventory mode "+mode);
        }
    }
    @Test void wholeParserPageGroupsRejectDifferentOriginalPageMissingAlternativeAndSourceAcceptanceClaim() throws Exception {
        byte[] original=rotatedPdf();BufferedImage image=new BufferedImage(10,20,BufferedImage.TYPE_INT_RGB);ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);
        com.fasterxml.jackson.databind.node.ObjectNode group=(com.fasterxml.jackson.databind.node.ObjectNode)wholePageGroup(DocumentParseProbe.sha256(original));
        group.put("physicalPageNo",2);Map<String,Object> context=DocumentParseProbe.map("physicalPageNo",2,"source",DocumentParseProbe.map("sourceSha256",DocumentParseProbe.sha256(original)));
        ConsenseProperties.Ocr cfg=new ConsenseProperties().getOcr();cfg.setMode("local");HttpSupport http=mock(HttpSupport.class);
        when(http.postMultipart(anyString(),any(),anyLong())).thenReturn("{\"text\":\"\",\"regions\":[],\"wholePageGroups\":["+group+"]}");
        PaddleOcrClient client=new PaddleOcrClient(cfg,http);assertFalse(client.recognize(out.toByteArray(),context).isEmpty());
        group.put("physicalPageNo",1);when(http.postMultipart(anyString(),any(),anyLong())).thenReturn("{\"wholePageGroups\":["+group+"]}");
        assertThrows(com.consense.common.BizException.class,()->client.recognize(out.toByteArray(),context));
        group.put("physicalPageNo",2);((com.fasterxml.jackson.databind.node.ArrayNode)group.path("candidates")).remove(1);
        when(http.postMultipart(anyString(),any(),anyLong())).thenReturn("{\"wholePageGroups\":["+group+"]}");
        assertThrows(com.consense.common.BizException.class,()->client.recognize(out.toByteArray(),context));
        group=(com.fasterxml.jackson.databind.node.ObjectNode)wholePageGroup(DocumentParseProbe.sha256(original));group.put("physicalPageNo",2);group.put("sourceQualityAccepted",true);
        when(http.postMultipart(anyString(),any(),anyLong())).thenReturn("{\"wholePageGroups\":["+group+"]}");
        assertThrows(com.consense.common.BizException.class,()->client.recognize(out.toByteArray(),context));
    }
    @Test void unselectedCompleteParserPageGroupsSurviveWireParserAndStoredJsonWithNoQuotedText() throws Exception {
        byte[] original=scanPdf();com.fasterxml.jackson.databind.JsonNode group=wholePageGroup(DocumentParseProbe.sha256(original));
        ConsenseProperties properties=new ConsenseProperties();properties.getOcr().setMode("local");HttpSupport http=mock(HttpSupport.class);
        when(http.get(anyString(),anyLong())).thenReturn("{}");
        when(http.postMultipart(endsWith("/ocr"),any(),anyLong())).thenReturn("{\"text\":\"\",\"lines\":[],\"regions\":[],\"wholePageGroups\":["+group+"]}");
        DocumentParser.ParsedDocument result=new DocumentParser(properties,new PaddleOcrClient(properties.getOcr(),http)).parse("scan.pdf",original);
        assertEquals("PARTIAL",result.getParseStatus(),"nontextual complete alternatives must not disappear as an empty OCR failure");
        assertTrue(result.getCoverage().getFailedPages().isEmpty());assertTrue(result.getText().isEmpty());
        com.fasterxml.jackson.databind.JsonNode saved=JsonUtils.mapper().valueToTree(result.getCoverage()).path("wholePageGroups");
        assertEquals(1,saved.size());assertEquals(group,saved.get(0));
        List<DocumentBlock> groups=new ArrayList<>();for(DocumentBlock block:result.getBlocks())if("visual_parser_group".equals(block.getKind()))groups.add(block);
        assertEquals(1,groups.size());assertEquals("",groups.get(0).getEffectiveText());
        DocumentBlock stored=JsonUtils.read(JsonUtils.write(groups.get(0)),DocumentBlock.class);
        assertEquals(group,JsonUtils.mapper().valueToTree(stored).path("parserPageGroup"));
        assertTrue(JsonUtils.mapper().valueToTree(stored).path("parserPageGroup").path("selectedParser").isNull());
        assertEquals(2,JsonUtils.mapper().valueToTree(stored).path("parserPageGroup").path("candidates").size());
    }
    @Test void normalNativeTableIntakeUsesActualOriginalAndKeepsWholeMethodsInIndependentFrames() throws Exception {
        byte[] bytes=vectorScopePdf(true);List<Map<String,Object>> calls=new ArrayList<>();
        OcrClient client=new OcrClient() {
            public boolean available(){return true;}
            public OcrResult recognize(byte[] input){return new OcrResult("Visual candidate",.8,Collections.emptyList());}
            @Override public com.fasterxml.jackson.databind.JsonNode observeNativePdf(byte[] actual,Map<String,Object> context) {
                assertArrayEquals(bytes,actual);calls.add(context);
                return nativeTableReply(context);
            }
        };
        DocumentParser parser=new DocumentParser(new ConsenseProperties(),client);DocumentParser.ParsedDocument observation=parser.observe("native-source.pdf",bytes);
        OerDocumentIdentity identity=new OerSourceRouting().route("native-source.pdf",DocumentParseProbe.sha256(bytes),observation);
        DocumentParser.ParsedDocument result=parser.parse("native-source.pdf",bytes,observation,identity);
        assertEquals(1,calls.size(),"the normal confirmed non-drawing parse must submit the original only once");
        com.fasterxml.jackson.databind.JsonNode context=JsonUtils.mapper().valueToTree(calls.get(0));assertEquals(1,context.path("source").path("pageCount").asInt());
        assertEquals(JsonUtils.parse("[1]"),context.path("selectedPhysicalPages"));assertTrue(context.path("javaNativeEvidence").get(0).path("paintedVectorRegions").size()>0);
        List<DocumentBlock> methods=new ArrayList<>();for(DocumentBlock block:result.getBlocks())if("native_table_candidate".equals(block.getKind()))methods.add(block);
        assertEquals(3,methods.size());assertNotEquals(methods.get(0).getId(),methods.get(1).getId());
        for(DocumentBlock block:methods){assertNull(block.getBbox());assertNull(block.getVisualRegion());assertEquals("",block.getEffectiveText());}
        com.fasterxml.jackson.databind.JsonNode first=JsonUtils.mapper().valueToTree(methods.get(0)).path("nativeTableCandidate");
        assertEquals("PDF_points_crop_top_left",first.path("frame").path("coordinateSpace").asText());
        assertEquals("actual-method-table",first.path("candidate").path("tables").get(0).path("tableId").asText());
        assertEquals("partial",JsonUtils.mapper().valueToTree(result.getCoverage()).path("nativeTableCandidates").path("status").asText());
    }
    @Test void localNativePdfWirePreservesOriginalAndRejectsBorrowedPageOrMissingWholeMethod() throws Exception {
        byte[] bytes=vectorScopePdf(true);Map<String,Object> context=DocumentParseProbe.map("source",DocumentParseProbe.map(
                "sourceFilename","native-source.pdf","sourceSha256",DocumentParseProbe.sha256(bytes),"mediaType","application/pdf","pageCount",1),
                "selectedPhysicalPages",Collections.singletonList(1),"readingPlan",DocumentParseProbe.map("schemaVersion","oer-reading-plan-v1",
                        "sourceSha256",DocumentParseProbe.sha256(bytes),"documentFamily","GCC","identityStatus","content_supported"),
                "javaNativeEvidence",Collections.emptyList());
        ConsenseProperties.Ocr cfg=new ConsenseProperties().getOcr();cfg.setMode("local");HttpSupport http=mock(HttpSupport.class);
        when(http.postMultipart(endsWith("/native-pdf/table-candidates"),any(),anyLong(),anyMap(),eq(8*1024*1024))).thenAnswer(invocation->{
            okhttp3.MultipartBody body=invocation.getArgument(1);assertEquals(2,body.parts().size());
            okio.Buffer actual=new okio.Buffer();body.part(0).body().writeTo(actual);assertArrayEquals(bytes,actual.readByteArray());
            assertTrue(body.part(0).headers().get("Content-Disposition").contains("native-source.pdf"));
            okio.Buffer json=new okio.Buffer();body.part(1).body().writeTo(json);assertEquals(JsonUtils.mapper().valueToTree(context),JsonUtils.parse(json.readUtf8()));
            assertTrue(body.part(1).headers().get("Content-Disposition").contains("name=\"nativeContext\""));
            return JsonUtils.write(nativeTableReply(context));
        });
        PaddleOcrClient client=new PaddleOcrClient(cfg,http);assertEquals("partial",client.observeNativePdf(bytes,context).path("status").asText());
        com.fasterxml.jackson.databind.node.ObjectNode wrong=nativeTableReply(context);
        ((com.fasterxml.jackson.databind.node.ObjectNode)wrong.path("pages").get(0)).put("physicalPageNo",2);
        doReturn(JsonUtils.write(wrong)).when(http).postMultipart(anyString(),any(),anyLong(),anyMap(),eq(8*1024*1024));
        assertThrows(com.consense.common.BizException.class,()->client.observeNativePdf(bytes,context));
        wrong=nativeTableReply(context);((com.fasterxml.jackson.databind.node.ArrayNode)wrong.path("pages").get(0).path("methods")).remove(1);
        doReturn(JsonUtils.write(wrong)).when(http).postMultipart(anyString(),any(),anyLong(),anyMap(),eq(8*1024*1024));
        assertThrows(com.consense.common.BizException.class,()->client.observeNativePdf(bytes,context));
        wrong=nativeTableReply(context);wrong.put("status","failed");wrong.putArray("pages");wrong.set("failure",JsonUtils.parse("{\"type\":\"ReplyByteCapExceeded\",\"message\":\"whole raw bundle retained\"}"));
        doReturn(JsonUtils.write(wrong)).when(http).postMultipart(anyString(),any(),anyLong(),anyMap(),eq(8*1024*1024));
        assertEquals("failed",client.observeNativePdf(bytes,context).path("status").asText());
        cfg.setMode("classic");assertNull(client.observeNativePdf(bytes,context));
    }
    @Test void actualPaintedVectorScopeTriggersLayoutAndDoesNotPretendItIsTableStructure() throws Exception {
        OcrClient client=mock(OcrClient.class,CALLS_REAL_METHODS);when(client.available()).thenReturn(true);
        when(client.recognize(any())).thenReturn(new OcrClient.OcrResult("Visual layout candidate",.8,Collections.emptyList()));
        DocumentParser.ParsedDocument result=new DocumentParser(new ConsenseProperties(),client).parse("native-source.pdf",vectorScopePdf(true));
        verify(client).recognize(any());assertEquals(1,result.getCoverage().getOcrPages());
        com.fasterxml.jackson.databind.JsonNode scopes=JsonUtils.mapper().valueToTree(result.getNativePdfEvidence().get(0)).path("paintedVectorRegions");
        assertEquals(1,scopes.size());assertEquals("S",scopes.get(0).path("paintOperator").asText());
        assertTrue(scopes.get(0).path("requiresVisualObservation").asBoolean());assertEquals("unknown",scopes.get(0).path("semanticKind").asText());
        assertTrue(result.getText().contains("Actual native source statement"));
    }
    @Test void unpaintedNativePathDoesNotBecomeLayoutGapOrTableEvidence() throws Exception {
        OcrClient client=mock(OcrClient.class,CALLS_REAL_METHODS);
        DocumentParser.ParsedDocument result=new DocumentParser(new ConsenseProperties(),client).parse("native-source.pdf",vectorScopePdf(false));
        verifyNoInteractions(client);assertEquals(0,JsonUtils.mapper().valueToTree(result.getNativePdfEvidence().get(0)).path("paintedVectorRegions").size());
    }
    @Test void actualSmallPlacedScanTriggersGapReadingDespiteDenseNativeText() throws Exception {
        OcrClient client=mock(OcrClient.class,CALLS_REAL_METHODS);when(client.available()).thenReturn(true);
        when(client.recognize(any())).thenReturn(new OcrClient.OcrResult("Actual small scan observation",.8,Collections.emptyList()));
        DocumentParser.ParsedDocument result=new DocumentParser(new ConsenseProperties(),client).parse("source.pdf",imageResourcePdf(true));
        verify(client).recognize(any());assertEquals(1,result.getCoverage().getOcrPages());
        assertTrue(JsonUtils.mapper().valueToTree(result.getNativePdfEvidence().get(0)).path("placedImageRegions").size()>0);
    }
    @Test void unusedLargeImageResourceDoesNotTriggerGapReading() throws Exception {
        OcrClient client=mock(OcrClient.class,CALLS_REAL_METHODS);when(client.available()).thenReturn(true);
        new DocumentParser(new ConsenseProperties(),client).parse("source.pdf",imageResourcePdf(false));
        verifyNoInteractions(client);
    }
    @Test void confirmedFormPlanReadsDenseNativeLayoutAndKeepsRepeatedWritingAreasUnknown() throws Exception {
        OcrClient client=mock(OcrClient.class,CALLS_REAL_METHODS);when(client.available()).thenReturn(true);
        com.fasterxml.jackson.databind.JsonNode regions=JsonUtils.parse("[{\"id\":\"first-name\",\"kind\":\"writing_area\",\"text\":\"\",\"bbox\":[20,700,200,50]},{\"id\":\"second-name\",\"kind\":\"writing_area\",\"text\":\"\",\"bbox\":[20,900,200,50]}]");
        when(client.recognize(any())).thenReturn(new OcrClient.OcrResult("",0,Collections.emptyList(),regions,null,null,null));
        DocumentParser parser=new DocumentParser(new ConsenseProperties(),client);byte[] bytes=denseFormPdf();
        DocumentParser.ParsedDocument observation=parser.observe("ordinary-source.pdf",bytes);
        SourceDocument source=new SourceDocument();source.setFileName("ordinary-source.pdf");source.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);
        assertTrue(OerSourceMetadata.captureObservation(source,observation,bytes));
        DocumentParser.ParsedDocument result=parser.parse("ordinary-source.pdf",bytes,observation,OerSourceMetadata.identity(source));
        verify(client,times(1)).recognize(any());
        assertEquals(1,result.getCoverage().getOcrPages(),"confirmed form identity must trigger layout even with dense native text");
        com.fasterxml.jackson.databind.JsonNode coverage=JsonUtils.mapper().valueToTree(result.getCoverage());
        assertEquals("AA",coverage.path("readingPlan").path("documentFamily").asText());
        assertEquals("unknown",coverage.path("readingPlan").path("versionStatus").asText());
        List<DocumentBlock> writing=new ArrayList<>();for(DocumentBlock block:result.getBlocks())if("writing_area".equals(block.getKind()))writing.add(block);
        assertEquals(2,writing.size());assertNotEquals(writing.get(0).getId(),writing.get(1).getId());
        for(DocumentBlock block:writing) {
            com.fasterxml.jackson.databind.JsonNode context=JsonUtils.mapper().valueToTree(block).path("readingContext");
            assertEquals(block.getId(),context.path("occurrenceId").asText());assertEquals("unknown",context.path("valueState").asText());
            assertEquals("unknown",context.path("ownerStatus").asText());
        }
    }
    @Test void readingIdentityMustMatchOriginalBytesAndGenericReadableIdentityCanStayUnresolvedInPurpose() throws Exception {
        byte[] bytes=denseFormPdf();OcrClient client=mock(OcrClient.class,CALLS_REAL_METHODS);
        DocumentParser parser=new DocumentParser(new ConsenseProperties(),client);DocumentParser.ParsedDocument observation=parser.observe("source.pdf",bytes);
        OerDocumentIdentity identity=new OerDocumentIdentity();identity.setSourceSha256(DocumentParseProbe.sha256(scanPdf()));identity.setIdentityStatus("content_supported_non_drawing");
        assertThrows(IllegalArgumentException.class,()->parser.parse("source.pdf",bytes,observation,identity));
        identity.setSourceSha256(DocumentParseProbe.sha256(bytes));
        DocumentParser.ParsedDocument result=parser.parse("source.pdf",bytes,observation,identity);
        assertEquals("UNKNOWN",result.getCoverage().getReadingPlan().path("documentFamily").asText());
        assertEquals("generic_source",result.getCoverage().getReadingPlan().path("profile").asText());
        assertTrue(result.getText().contains("Actual contract statement"));verifyNoInteractions(client);
        identity.setIdentityStatus("unknown");assertThrows(IllegalArgumentException.class,()->parser.parse("source.pdf",bytes,observation,identity));
    }
    @Test void partiallyOverlappingVisualGroupRetainsCompleteCandidateAndAffectedNativeScope() throws Exception {
        OcrClient client=mock(OcrClient.class,CALLS_REAL_METHODS);when(client.available()).thenReturn(true);
        com.fasterxml.jackson.databind.JsonNode region=JsonUtils.parse("[{\"id\":\"whole-table\",\"kind\":\"table\",\"text\":\"Native caption\\nScanned complete table body\",\"bbox\":[0,0,1650,2200],\"cells\":[{\"text\":\"Scanned complete table body\"}]}]");
        when(client.recognize(any())).thenReturn(new OcrClient.OcrResult("Native caption\nScanned complete table body",.8,Collections.emptyList(),region,null,null,null));
        DocumentParser.ParsedDocument result=new DocumentParser(new ConsenseProperties(),client).parse("mixed.pdf",mixedPdf());
        DocumentBlock whole=result.getBlocks().stream().filter(block->"table".equals(block.getKind())).findFirst().orElseThrow();
        assertEquals("Native caption\nScanned complete table body",whole.getText(),"retain the full indivisible conflicting group");
        assertEquals("ocr_candidate",whole.getSource());assertEquals(region.get(0),whole.getVisualRegion());
        assertTrue(result.getText().contains("Native contract condition"));assertFalse(result.getText().contains("Scanned complete table body"));
        com.fasterxml.jackson.databind.JsonNode context=JsonUtils.mapper().valueToTree(whole).path("readingContext");
        assertEquals("blocked_native_visual_conflict",context.path("adoptionStatus").asText());assertTrue(context.path("conflictingNativeBlockIds").size()>0);
        assertEquals("",whole.getEffectiveText());
    }
    @Test void originalPdfPageIdentityAndRenderTransformReachIdentityAndDetailParsers() throws Exception {
        byte[] bytes=rotatedPdf();
        List<com.fasterxml.jackson.databind.JsonNode> identityContexts=new ArrayList<>(), detailContexts=new ArrayList<>();
        OcrClient client=new OcrClient() {
            public boolean available(){return true;}
            public OcrResult recognize(byte[] image){return new OcrResult("Original scanned contract",.8,Collections.emptyList());}
            @Override public OcrResult recognize(byte[] image,Map<String,Object> context){capture(image,context,detailContexts);return recognize(image);}
            @Override public OcrResult observeIdentity(byte[] image,Map<String,Object> context){capture(image,context,identityContexts);return recognize(image);}
            public boolean canReuseIdentityObservation(OcrResult observation){return false;}
            private void capture(byte[] image,Map<String,Object> context,List<com.fasterxml.jackson.databind.JsonNode> target){
                try {
                    com.fasterxml.jackson.databind.JsonNode value=JsonUtils.mapper().valueToTree(context);
                    BufferedImage actual=ImageIO.read(new java.io.ByteArrayInputStream(image));
                    assertEquals(DocumentParseProbe.sha256(image),value.path("imageSha256").asText());
                    assertEquals(actual.getWidth(),value.path("width").asInt());assertEquals(actual.getHeight(),value.path("height").asInt());
                    target.add(value);
                } catch(java.io.IOException failure){throw new IllegalStateException(failure);}
            }
        };
        DocumentParser parser=new DocumentParser(new ConsenseProperties(),client);
        DocumentParser.ParsedDocument observation=parser.observe("original-contract.pdf",bytes);
        DocumentParser.ParsedDocument result=parser.parse("original-contract.pdf",bytes,observation);
        assertEquals(1,identityContexts.size(),"the identity OCR must receive the original PDF binding");
        assertEquals(2,detailContexts.size(),"each physical page needs its own original PDF binding");
        assertEquals(2,result.getCoverage().getOcrPages());
        for(int index=0;index<detailContexts.size();index++) {
            com.fasterxml.jackson.databind.JsonNode context=detailContexts.get(index);
            assertEquals(DocumentParseProbe.sha256(bytes),context.path("source").path("sourceSha256").asText());
            assertEquals("original-contract.pdf",context.path("source").path("sourceFilename").asText());
            assertEquals(2,context.path("source").path("pageCount").asInt());assertEquals(index+1,context.path("physicalPageNo").asInt());
            com.fasterxml.jackson.databind.JsonNode transform=context.path("originalPageTransform"),frame=transform.path("targetFrame");
            assertEquals("affine",transform.path("kind").asText());assertEquals("original_page",frame.path("coordinateFrame").asText());
            assertEquals("pt",frame.path("coordinateUnit").asText());assertEquals(index+1,frame.path("physicalPageNo").asInt());
            assertEquals(800,frame.path("width").asDouble());assertEquals(600,frame.path("height").asDouble());
            assertEquals(90,frame.path("rotation").asInt());assertEquals(2,frame.path("userUnit").asDouble());
            assertEquals(JsonUtils.parse("[50.0,60.0,300.0,400.0]"),frame.path("cropBox"));
            assertEquals(JsonUtils.parse("[0.0,0.0,600.0,800.0]"),frame.path("mediaBox"));
            assertEquals("top-left",frame.path("origin").asText());assertEquals("rotated CropBox display",frame.path("frameMeaning").asText());
            assertEquals(800.0/context.path("width").asInt(),transform.path("matrix").get(0).asDouble(),1e-9);
            assertEquals(600.0/context.path("height").asInt(),transform.path("matrix").get(4).asDouble(),1e-9);
            assertEquals(context.path("imageSha256"),transform.path("inputFrame").path("sha256"));
        }
        assertEquals(detailContexts.get(0).path("source"),identityContexts.get(0).path("source"));
        assertEquals(detailContexts.get(0).path("originalPageTransform"),identityContexts.get(0).path("originalPageTransform"));
    }
    @Test void denseNativeTextDoesNotHideScanAndIsKeptWhenSupplemented() throws Exception {
        OcrClient client=mock(OcrClient.class,CALLS_REAL_METHODS); when(client.available()).thenReturn(true);
        when(client.recognize(any())).thenReturn(new OcrClient.OcrResult("Scanned qualification",.8,Collections.singletonList(new OcrClient.OcrLine("Scanned qualification",.8,new double[]{20,500,300,40}))));
        DocumentParser.ParsedDocument result=new DocumentParser(new ConsenseProperties(),client).parse("source.pdf",mixedPdf());
        verify(client).recognize(any());
        assertTrue(result.getText().contains("Native contract condition"));
        assertTrue(result.getText().contains("Scanned qualification"));
        assertTrue(result.getBlocks().stream().anyMatch(b->"native".equals(b.getSource())));
        assertEquals("PARTIAL",result.getParseStatus());
    }
    @Test void localMultipartPreservesOriginalContextAndRefusesProviderPageAsOriginalPage() throws Exception {
        BufferedImage image=new BufferedImage(10,20,BufferedImage.TYPE_INT_RGB);ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);
        byte[] input=out.toByteArray();Map<String,Object> context=DocumentParseProbe.map("physicalPageNo",2,
                "source",DocumentParseProbe.map("sourceFilename","original.pdf","sourceSha256",DocumentParseProbe.sha256(rotatedPdf()),"pageCount",2),
                "imageSha256",DocumentParseProbe.sha256(input),"width",10,"height",20);
        ConsenseProperties.Ocr cfg=new ConsenseProperties().getOcr();cfg.setMode("local");HttpSupport http=mock(HttpSupport.class);
        when(http.postMultipart(anyString(),any(),anyLong())).thenAnswer(invocation->{
            okhttp3.MultipartBody body=invocation.getArgument(1);assertEquals(2,body.parts().size());
            okio.Buffer bytes=new okio.Buffer();body.part(0).body().writeTo(bytes);assertArrayEquals(input,bytes.readByteArray());
            okio.Buffer json=new okio.Buffer();body.part(1).body().writeTo(json);
            assertTrue(body.part(1).headers().get("Content-Disposition").contains("name=\"sourceContext\""));
            assertEquals(JsonUtils.mapper().valueToTree(context),JsonUtils.parse(json.readUtf8()));
            return "{\"text\":\"body\",\"provenance\":{\"sourceContext\":"+JsonUtils.write(context)+"}}";
        });
        PaddleOcrClient client=new PaddleOcrClient(cfg,http);assertEquals("body",client.recognize(input,context).getText());
        com.fasterxml.jackson.databind.node.ObjectNode wrong=JsonUtils.mapper().valueToTree(context);wrong.put("physicalPageNo",1);
        doReturn("{\"text\":\"body\",\"provenance\":{\"sourceContext\":"+wrong+"}}").when(http).postMultipart(anyString(),any(),anyLong());
        assertThrows(com.consense.common.BizException.class,()->client.recognize(input,context));
    }
    @Test void layoutOnlyResponseIsAnObservationRatherThanEmptyOcr() {
        ConsenseProperties.Ocr cfg=new ConsenseProperties().getOcr();cfg.setMode("local");
        HttpSupport http=mock(HttpSupport.class);
        when(http.postMultipart(anyString(),any(),anyLong())).thenReturn("{\"text\":\"\",\"lines\":[],\"regions\":[{\"id\":\"control1\",\"kind\":\"checkbox\",\"text\":\"\",\"bbox\":[1,2,3,4],\"fields\":[{\"state\":\"unknown\"}]}]}");
        assertFalse(new PaddleOcrClient(cfg,http).recognize(new byte[]{1}).isEmpty());
    }
    @Test void nativeDrawingFormatIdentityDoesNotDependOnItsDisplayFilename() {
        byte[] cad="AC1032 original native drawing bytes".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertTrue(OerSourceRouting.isDrawing(null, "drawing.dwg", cad));
        assertTrue(OerSourceRouting.isDrawing(null, "renamed.pdf", cad));
        assertFalse(OerSourceRouting.isDrawing(null, "drawing.dwg", "Native contract paragraph".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    }
    @Test void weakPdfDrawingMetadataCannotConfirmDrawingIdentity() throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(PDDocument pdf=new PDDocument()){pdf.addPage(new PDPage());pdf.getDocumentInformation().setTitle("Drawing");pdf.save(out);}
        assertFalse(OerSourceRouting.isDrawing(null,"document.pdf",out.toByteArray()));
    }
    @Test void identityObservationsArePartialAndReuseOnlyTheSameSourceAndConfiguration() throws Exception {
        byte[] bytes=scanPdf(); OcrClient client=mock(OcrClient.class,CALLS_REAL_METHODS);when(client.available()).thenReturn(true);
        when(client.recognize(any())).thenReturn(new OcrClient.OcrResult("General Conditions of Contract",.8,Collections.emptyList()));
        ConsenseProperties properties=new ConsenseProperties();DocumentParser parser=new DocumentParser(properties,client);
        DocumentParser.ParsedDocument observation=parser.observe("contract.pdf",bytes);
        assertEquals("IDENTITY_OBSERVED",observation.getParseStatus());assertFalse(observation.getCoverage().isComplete());
        assertEquals(1,observation.getPageCount());assertEquals(DocumentParseProbe.sha256(bytes),observation.getSourceSha256());
        SourceDocument source=new SourceDocument();source.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);source.setFileName("contract.pdf");
        assertTrue(OerSourceMetadata.captureObservation(source,observation,bytes));
        assertEquals("GCC",OerSourceMetadata.identity(source).getKind());
        DocumentParser.ParsedDocument result=parser.parse("renamed.pdf",bytes,observation);
        assertEquals("PARTIAL",result.getParseStatus());assertTrue(result.getText().contains("General Conditions of Contract"));
        verify(client,times(1)).recognize(any());verify(client,times(1)).available();
        verify(client).observeIdentity(any());verify(client).canReuseIdentityObservation(any());
        verify(client).observeIdentity(any(),anyMap());
        byte[] changed=java.util.Arrays.copyOf(bytes,bytes.length+1);
        assertThrows(IllegalArgumentException.class,()->parser.parse("contract.pdf",changed,observation));
        properties.getOcr().setTimeoutMs(properties.getOcr().getTimeoutMs()+1);
        assertThrows(IllegalArgumentException.class,()->parser.parse("contract.pdf",bytes,observation));
        verifyNoMoreInteractions(client);
    }
    @Test void emptyWritingAndCompleteCandidateTopologyRemainUnknownAndPersistable() throws Exception {
        OcrClient client=mock(OcrClient.class,CALLS_REAL_METHODS);when(client.available()).thenReturn(true);
        com.fasterxml.jackson.databind.JsonNode regions=JsonUtils.parse("[{\"id\":\"a\",\"kind\":\"checkbox\",\"text\":\"\",\"bbox\":[-1,2,3,4],\"polygon\":[[-1,2],[2,2],[2,6],[-1,6]],\"fields\":[{\"state\":\"unknown\"}],\"relations\":[{\"kind\":\"parent\",\"status\":\"unknown\"}]}]");
        when(client.recognize(any())).thenReturn(new OcrClient.OcrResult("",0,Collections.emptyList(),regions,null,null,
                JsonUtils.parse("{\"status\":\"unknown\",\"missing\":[\"field_state\"]}")));
        DocumentParser.ParsedDocument result=new DocumentParser(new ConsenseProperties(),client).parse("scan.pdf",scanPdf());
        assertEquals("PARTIAL",result.getParseStatus());assertTrue(result.getCoverage().getBlankPages().isEmpty());
        DocumentBlock block=result.getBlocks().get(0);assertNull(block.getBbox(),"out-of-frame geometry must not be clipped into a plausible location");
        DocumentBlock readBack=JsonUtils.read(JsonUtils.write(block),DocumentBlock.class);
        assertEquals(regions.get(0),readBack.getVisualRegion());assertEquals("unknown",readBack.getVisualRegion().path("fields").get(0).path("state").asText());
        assertEquals("unknown",result.getCoverage().getVisualCoverage().get(0).path("coverage").path("status").asText());
    }
    @Test void advertisedImageIdentityAndDimensionsMustMatchActualWireInput() throws Exception {
        BufferedImage image=new BufferedImage(10,20,BufferedImage.TYPE_INT_RGB);ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);
        byte[] bytes=out.toByteArray();String sha=DocumentParseProbe.sha256(bytes);
        ConsenseProperties.Ocr cfg=new ConsenseProperties().getOcr();cfg.setMode("local");HttpSupport http=mock(HttpSupport.class);
        String wrongFrame="{\"text\":\"body\",\"frame\":{\"imageSha256\":\""+sha+"\",\"width\":11,\"height\":20,\"coordinateUnit\":\"pixel\",\"bboxFormat\":\"xywh\"}}";
        when(http.postMultipart(anyString(),any(),anyLong())).thenReturn(wrongFrame);
        assertThrows(com.consense.common.BizException.class,()->new PaddleOcrClient(cfg,http).recognize(bytes));
        when(http.postMultipart(anyString(),any(),anyLong())).thenReturn("{\"text\":\"body\",\"provenance\":{\"inputSha256\":\"different-input\"}}");
        assertThrows(com.consense.common.BizException.class,()->new PaddleOcrClient(cfg,http).recognize(bytes));
    }
    @Test void localIdentityPurposeDoesNotReuseBaselineAsCompleteParserTask() throws Exception {
        ConsenseProperties properties=new ConsenseProperties();properties.getOcr().setMode("local");
        HttpSupport http=mock(HttpSupport.class);when(http.get(anyString(),anyLong())).thenReturn("{} ");
        when(http.postMultipart(anyString(),any(),anyLong(),anyMap())).thenReturn("{\"text\":\"General Conditions of Contract\",\"lines\":[]}");
        when(http.postMultipart(anyString(),any(),anyLong())).thenReturn("{\"text\":\"General Conditions of Contract\",\"regions\":[{\"id\":\"body\",\"kind\":\"text\",\"text\":\"General Conditions of Contract\",\"bbox\":[20,20,200,20]}],\"adoptionStatus\":\"candidate_unverified\"}");
        DocumentParser parser=new DocumentParser(properties,new PaddleOcrClient(properties.getOcr(),http));byte[] bytes=scanPdf();
        DocumentParser.ParsedDocument observation=parser.observe("source.pdf",bytes);
        DocumentParser.ParsedDocument result=parser.parse("source.pdf",bytes,observation);
        verify(http).postMultipart(anyString(),any(),anyLong(),eq(Collections.singletonMap("X-Consense-Ocr-Purpose","identity")));
        verify(http).postMultipart(anyString(),any(),anyLong());
        assertEquals("PARTIAL",result.getParseStatus());
        assertEquals("candidate_unverified",result.getCoverage().getVisualCoverage().get(0).path("parserEvidence").path("adoptionStatus").asText());
    }
    static com.fasterxml.jackson.databind.JsonNode wholePageGroup(String originalSha) {
        com.fasterxml.jackson.databind.node.ObjectNode group=(com.fasterxml.jackson.databind.node.ObjectNode)JsonUtils.parse(
                "{\"physicalPageNo\":1,\"scope\":\"whole_page\",\"selectionStatus\":\"candidate_unverified\",\"selectedParser\":null,\"policySha256\":null,\"sourceQualityAccepted\":false,\"adoptionStatus\":\"candidate_unverified\",\"reason\":\"no approved generic feature policy\",\"candidates\":[{\"parser\":\"paddleocr-vl\",\"runId\":\"paddle-run\",\"pageId\":\"paddle-page\",\"features\":{\"hasTables\":false,\"allTableStructuresObserved\":false,\"allGeometryBound\":false,\"completeRawCapture\":true},\"blocks\":[{\"id\":\"paddle-control\",\"kind\":\"checkbox\",\"text\":\"\",\"state\":\"unknown\"}],\"page\":{\"frame\":{\"status\":\"unknown\"},\"unknowns\":[\"control state\"]}},{\"parser\":\"mineru\",\"runId\":\"mineru-run\",\"pageId\":\"mineru-page\",\"features\":{\"hasTables\":true,\"allTableStructuresObserved\":false,\"allGeometryBound\":false,\"completeRawCapture\":true},\"blocks\":[{\"id\":\"mineru-table\",\"kind\":\"table\",\"text\":\"\",\"relations\":[],\"unknowns\":[\"whole table state\"]}],\"page\":{\"frame\":{\"status\":\"unknown\"},\"unknowns\":[\"whole table state\"]}}]}");
        group.set("source",JsonUtils.mapper().valueToTree(DocumentParseProbe.map("sourceFilename","scan.pdf","sourceSha256",originalSha,"mediaType","application/pdf","pageCount",1)));
        group.set("dualEvidenceRef",JsonUtils.parse("{\"sha256\":\"raw-dual-evidence-sha\"}"));return group;
    }
    static com.fasterxml.jackson.databind.node.ObjectNode nativeTableReply(Map<String,Object> context) {
        com.fasterxml.jackson.databind.node.ObjectNode response=JsonUtils.mapper().createObjectNode();
        response.put("schema","consense.native-pdf-candidates.v1");response.put("status","partial");
        response.set("source",JsonUtils.mapper().valueToTree(context.get("source")));response.set("selectedPhysicalPages",JsonUtils.mapper().valueToTree(context.get("selectedPhysicalPages")));
        response.set("pages",JsonUtils.parse("[{\"physicalPageNo\":1,\"frame\":{\"coordinateSpace\":\"PDF_points_crop_top_left\",\"bboxFormat\":\"xyxy\"},\"nativeGlyphNamespace\":\"pdfplumber/page/1\",\"methods\":[{\"method\":\"pdfplumber_lines_lines\",\"provider\":\"pdfplumber\",\"status\":\"candidate_observed\",\"candidate\":{\"schema\":\"consense.native-pdf-table-candidate.v1\",\"conditionCandidates\":[],\"tables\":[{\"tableId\":\"actual-method-table\",\"bbox\":[30,100,330,250],\"state\":\"unknown\"}]},\"failure\":null},{\"method\":\"camelot_network\",\"provider\":\"camelot\",\"status\":\"failed\",\"candidate\":null,\"failure\":{\"type\":\"MethodUnavailable\",\"message\":\"method unavailable\",\"traceback\":[]}},{\"method\":\"camelot_stream\",\"provider\":\"camelot\",\"status\":\"candidate_observed\",\"candidate\":{\"schema\":\"consense.native-pdf-table-candidate.v1\",\"tables\":[],\"conditionCandidates\":[]},\"failure\":null}],\"coverage\":{\"sourceStatus\":\"unknown\"}}]"));
        response.set("bundleRef",JsonUtils.parse("{\"sha256\":\"saved-raw-bundle-sha\",\"kind\":\"raw_native_table_candidates\"}"));return response;
    }
    static byte[] scanPdf() throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(PDDocument pdf=new PDDocument()){
            PDPage page=new PDPage();pdf.addPage(page);BufferedImage image=new BufferedImage(600,300,BufferedImage.TYPE_INT_RGB);
            Graphics2D g=image.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,600,300);g.setColor(Color.BLACK);g.drawString("Original source",20,80);g.dispose();
            try(PDPageContentStream stream=new PDPageContentStream(pdf,page)){stream.drawImage(LosslessFactory.createFromImage(pdf,image),30,100,300,150);}pdf.save(out);
        }
        return out.toByteArray();
    }
    static byte[] rotatedPdf() throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(PDDocument pdf=new PDDocument()) {
            for(int index=0;index<2;index++) {
                PDPage page=new PDPage(new PDRectangle(600,800));page.setCropBox(new PDRectangle(50,60,300,400));page.setRotation(90);page.setUserUnit(2);pdf.addPage(page);
                try(PDPageContentStream stream=new PDPageContentStream(pdf,page)){stream.addRect(100+index*10,100,20,30);stream.fill();}
            }
            pdf.save(out);
        }
        return out.toByteArray();
    }
    static byte[] denseFormPdf() throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(PDDocument pdf=new PDDocument()) {
            PDPage page=new PDPage();pdf.addPage(page);
            try(PDPageContentStream stream=new PDPageContentStream(pdf,page)) {
                stream.beginText();stream.setFont(PDType1Font.HELVETICA,10);stream.newLineAtOffset(30,740);
                stream.showText("Articles of Agreement");stream.newLineAtOffset(0,-20);
                for(int index=0;index<8;index++){stream.showText("Actual contract statement and complete source context.");stream.newLineAtOffset(0,-15);}
                stream.showText("Name:");stream.newLineAtOffset(0,-80);stream.showText("Name:");stream.endText();
            }
            pdf.save(out);
        }
        return out.toByteArray();
    }
    static byte[] imageResourcePdf(boolean placed) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(PDDocument pdf=new PDDocument()) {
            PDPage page=new PDPage();pdf.addPage(page);BufferedImage image=new BufferedImage(placed?30:600,placed?20:300,BufferedImage.TYPE_INT_RGB);
            org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject asset=LosslessFactory.createFromImage(pdf,image);
            page.setResources(new PDResources());page.getResources().add(asset);
            try(PDPageContentStream stream=new PDPageContentStream(pdf,page)) {
                stream.beginText();stream.setFont(PDType1Font.HELVETICA,10);stream.newLineAtOffset(30,740);
                for(int index=0;index<8;index++){stream.showText("Actual native source statement stays complete.");stream.newLineAtOffset(0,-15);}stream.endText();
                if(placed)stream.drawImage(asset,30,100,300,150);
            }
            pdf.save(out);
        }
        return out.toByteArray();
    }
    static byte[] vectorScopePdf(boolean painted) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(PDDocument pdf=new PDDocument()) {
            PDPage page=new PDPage();pdf.addPage(page);
            try(PDPageContentStream stream=new PDPageContentStream(pdf,page)) {
                stream.beginText();stream.setFont(PDType1Font.HELVETICA,10);stream.newLineAtOffset(30,740);
                stream.showText("General Conditions of Contract");stream.newLineAtOffset(0,-20);
                for(int index=0;index<8;index++){stream.showText("Actual native source statement stays complete.");stream.newLineAtOffset(0,-15);}stream.endText();
                stream.addRect(30,100,300,150);stream.moveTo(150,100);stream.lineTo(150,250);
                if(painted)stream.stroke();
            }
            pdf.save(out);
        }
        return out.toByteArray();
    }
    static byte[] mixedPdf() throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(PDDocument pdf=new PDDocument()){
            PDPage page=new PDPage();pdf.addPage(page);
            BufferedImage image=new BufferedImage(600,300,BufferedImage.TYPE_INT_RGB);
            Graphics2D g=image.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,600,300);g.setColor(Color.BLACK);g.drawString("Scanned qualification",20,80);g.dispose();
            try(PDPageContentStream s=new PDPageContentStream(pdf,page)){
                s.beginText();s.setFont(PDType1Font.HELVETICA,9);s.newLineAtOffset(30,730);
                for(int i=0;i<8;i++){s.showText("Native contract condition remains unchanged.");s.newLineAtOffset(0,-15);}s.endText();
                s.drawImage(LosslessFactory.createFromImage(pdf,image),30,100,300,150);
            }
            pdf.save(out);
        }
        return out.toByteArray();
    }
}
