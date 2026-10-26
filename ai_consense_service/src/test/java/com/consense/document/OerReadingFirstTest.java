package com.consense.document;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.domain.SourceDocument;
import com.consense.ocr.OcrClient;
import com.consense.service.sources.OerSourceMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.interactive.annotation.*;
import org.apache.pdfbox.pdmodel.interactive.form.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Authored CPU controls only; no real source, provider or source-quality acceptance. */
class OerReadingFirstTest {
    @TempDir Path directory;

    private OcrClient forbidden() {
        return new OcrClient() {
            public boolean available(){throw new AssertionError("Ordinary native reading must not wait for optional OCR readiness");}
            public OcrResult recognize(byte[] bytes){throw new AssertionError("No optional raster OCR");}
            @Override public OcrResult observeIdentity(byte[] bytes){throw new AssertionError("No optional identity OCR");}
            @Override public JsonNode observeNativePdf(byte[] bytes,Map<String,Object> context){throw new AssertionError("No optional native-table provider");}
        };
    }
    private OerDocumentIdentity manual(byte[] bytes) {
        String sha=DocumentParseProbe.sha256(bytes);OerDocumentIdentity identity=new OerDocumentIdentity();
        identity.setSourceSha256(sha);identity.setKind("UNKNOWN");identity.setRole("standard");identity.setIdentityStatus("human_confirmed_textual_material");
        identity.getIdentityEvidence().add(new OerDocumentIdentity.Evidence("human_text_contract_material","original:"+sha,"Authored textual-material confirmation"));return identity;
    }
    private List<JsonNode> events(String job)throws Exception {
        try(Stream<Path> paths=Files.list(directory.resolve(job))){return paths.filter(p->p.getFileName().toString().matches("[0-9]{5}-.*\\.json"))
                .map(p->{try{return JsonUtils.parse(Files.readString(p));}catch(Exception e){throw new IllegalStateException(e);}}).collect(Collectors.toList());}
    }
    private void noRasterEvents(String job)throws Exception {
        for(JsonNode event:events(job)) {
            assertNotEquals("pdf_render_image",event.path("phase").asText());
            assertNotEquals("native_pdf_actual_input",event.path("phase").asText());
            if("phase_timing".equals(event.path("phase").asText()))assertFalse(Arrays.asList("pdf_render","pdf_ocr_input_png_encoding","native_pdf_table_candidates").contains(event.path("observations").path("stage").asText()));
        }
    }
    private byte[] formPdf()throws Exception {
        try(PDDocument document=new PDDocument();java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream()){
            PDPage page=new PDPage();document.addPage(page);
            try(PDPageContentStream stream=new PDPageContentStream(document,page)){
                stream.beginText();stream.setFont(PDType1Font.HELVETICA,10);stream.newLineAtOffset(30,700);
                stream.showText("Articles of Agreement. Authored native contract statement remains available while optional appearance work is deferred.");stream.endText();
            }
            PDAcroForm form=new PDAcroForm(document);document.getDocumentCatalog().setAcroForm(form);
            PDTextField field=new PDTextField(form);field.setPartialName("AuthoredField");field.getCOSObject().setString(COSName.V,"Stored only value");form.getFields().add(field);
            PDAnnotationWidget widget=field.getWidgets().get(0);widget.setPage(page);widget.setRectangle(new PDRectangle(30,600,200,20));page.getAnnotations().add(widget);
            PDAppearanceStream appearance=new PDAppearanceStream(document);appearance.setBBox(new PDRectangle(200,20));appearance.setResources(new PDResources());
            try(PDPageContentStream stream=new PDPageContentStream(document,appearance)){
                stream.beginText();stream.setFont(PDType1Font.HELVETICA,10);stream.newLineAtOffset(2,5);stream.showText("Existing appearance value");stream.endText();
            }
            PDAppearanceDictionary ap=new PDAppearanceDictionary();ap.setNormalAppearance(appearance);widget.setAppearance(ap);
            document.save(output);return output.toByteArray();
        }
    }

    @Test void ordinaryTextReturnsWithoutAnyOptionalProviderOrRenderAndRetainsExplicitPageGaps()throws Exception {
        byte[] bytes=DocumentParseProbeTest.pdf(true);ConsenseProperties properties=new ConsenseProperties();properties.getOcr().setEnabled(false);properties.getDocument().setProbeDirectory(directory.toString());
        DocumentParser parser=new DocumentParser(properties,forbidden());DocumentParser.ParsedDocument observed=parser.observeOrdinary("reading.pdf",bytes);
        assertTrue(observed.getText().contains("The contractor shall submit"));assertEquals("IDENTITY_OBSERVED",observed.getParseStatus());
        DocumentParser.ParsedDocument result=parser.parseOrdinary("reading.pdf",bytes,"native-first","source",DocumentParseProbe.sha256(bytes),observed,manual(bytes));
        assertTrue(result.getText().contains("The contractor shall submit"));assertEquals(3,result.getPageCount());assertEquals("PARTIAL",result.getParseStatus());
        assertEquals(1,result.getCoverage().getParsedPages());assertEquals(Arrays.asList(2,3),result.getCoverage().getFailedPages());assertTrue(result.getCoverage().getBlankPages().isEmpty());
        assertEquals(0,result.getCoverage().getOcrPages());assertTrue(result.getNativePdfEvidence().isEmpty());assertFalse(result.getCoverage().isComplete());
        assertEquals("deferred_native_first",result.getCoverage().getNativeTableCandidates().path("status").asText());
        assertTrue(result.getBlocks().stream().anyMatch(b->!b.getPdfGlyphs().isEmpty()&&DocumentParseProbe.sha256(bytes).equals(b.getReadingContext().path("sourceSha256").asText())));
        String originalBlocks=JsonUtils.write(result.getBlocks());SourceDocument saved=new SourceDocument();
        saved.setProjectId("authored-reading");saved.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);saved.setFileName("reading.pdf");
        saved.setTextContent(result.getText());saved.setPageCount(result.getPageCount());saved.setParseStatus(result.getParseStatus());
        OerSourceMetadata.captureOrdinary(saved,result,bytes);
        assertTrue(JsonUtils.parse(saved.getParseCoverageJson()).has("normalPdfReadingObservation"));
        List<DocumentBlock> storedBlocks=JsonUtils.readList(saved.getStructuredContentJson(),DocumentBlock.class);
        assertTrue(storedBlocks.stream().allMatch(b->b.getPdfGlyphs().isEmpty()));assertNull(saved.getNativePdfEvidenceJson());
        assertEquals(originalBlocks,JsonUtils.write(result.getBlocks()),"Compact persistence must not strip transient clause witnesses from the parser result");
        assertEquals(result.getBlocks().stream().map(DocumentBlock::getText).collect(Collectors.toList()),storedBlocks.stream().map(DocumentBlock::getText).collect(Collectors.toList()));
        assertEquals(result.getText(),saved.getTextContent());
        noRasterEvents("native-first");
    }

    @Test void ordinaryFormMetadataDoesNotPromoteStoredValueOrDecodeAppearanceAndRichApiStillDoes()throws Exception {
        byte[] bytes=formPdf(),before=bytes.clone();ConsenseProperties properties=new ConsenseProperties();properties.getOcr().setEnabled(false);DocumentParser parser=new DocumentParser(properties,forbidden());
        OerDocumentIdentity identity=manual(bytes);identity.setKind("AA");DocumentParser.ParsedDocument result=parser.parseOrdinary("form.pdf",bytes,null,identity);
        assertTrue(result.getText().contains("Agreement"));assertEquals("PARTIAL",result.getParseStatus());assertFalse(result.getText().contains("Stored only value"));assertFalse(result.getText().contains("Existing appearance value"));
        PdfPageEvidence.PdfFormField field=result.getBlocks().stream().map(DocumentBlock::getPdfFormField).filter(Objects::nonNull).findFirst().orElseThrow();
        assertTrue(field.isAppearancePresent());assertEquals("Stored only value",field.getFieldValue());assertEquals("appearance_deferred_native_first",field.getValueStatus());assertEquals("deferred_native_first",field.getAppearanceStreamObservationStatus());assertEquals("deferred_native_first",field.getAppearanceGlyphObservationStatus());
        assertNull(field.getAppearanceValue());assertNull(field.getAppearanceRawStreamBytesBase64());assertTrue(field.getAppearanceGlyphs().isEmpty());
        DocumentParser.ParsedDocument rich=parser.parse("rich.pdf",bytes);
        assertFalse(rich.getNativePdfEvidence().isEmpty());assertFalse(rich.getNativePdfEvidence().get(0).getGlyphs().isEmpty());
        assertTrue(rich.getNativePdfEvidence().get(0).getNativeBlocks().stream().anyMatch(b->!b.getPdfGlyphs().isEmpty()));
        PdfPageEvidence.PdfFormField full=rich.getNativePdfEvidence().get(0).getFormFields().get(0);assertEquals("Existing appearance value",full.getAppearanceValue());assertNotNull(full.getAppearanceRawStreamBytesBase64());assertFalse(full.getAppearanceGlyphs().isEmpty());assertTrue(full.isConflict());assertArrayEquals(before,bytes);
        assertFalse(result.getCoverage().getReadingPlan().path("optionalReadingStatus").asText().isEmpty());
    }

    @Test void richDisabledOrUnavailableOcrIsAdmittedBeforeRenderingWithoutClaimingBlank()throws Exception {
        byte[] bytes=DocumentParseProbeTest.pdf(false);
        for(boolean enabled:Arrays.asList(false,true)){
            ConsenseProperties properties=new ConsenseProperties();properties.getOcr().setEnabled(enabled);properties.getDocument().setProbeDirectory(directory.toString());
            List<String> calls=new ArrayList<>();OcrClient unavailable=new OcrClient(){public boolean available(){calls.add("available");return false;}public OcrResult recognize(byte[] input){throw new AssertionError("Unavailable OCR must not run");}};
            String job="unavailable-"+enabled;DocumentParser.ParsedDocument result=new DocumentParser(properties,unavailable).parse("scan.pdf",bytes,job,"source",DocumentParseProbe.sha256(bytes));
            assertEquals(enabled?Collections.singletonList("available"):Collections.emptyList(),calls);assertTrue(result.getCoverage().getBlankPages().isEmpty());assertEquals(Collections.singletonList(1),result.getCoverage().getFailedPages());noRasterEvents(job);
        }
    }

    @Test void noNativeTextDoesNotBecomeACompleteScanOrAConfirmedBlankPage()throws Exception {
        byte[] bytes=DocumentParseProbeTest.pdf(false);ConsenseProperties properties=new ConsenseProperties();properties.getOcr().setEnabled(false);DocumentParser parser=new DocumentParser(properties,forbidden());
        DocumentParser.ParsedDocument observed=parser.observeOrdinary("unreadable.pdf",bytes);
        assertEquals("IDENTITY_OBSERVED",observed.getParseStatus());assertEquals(0,observed.getCoverage().getParsedPages());
        assertEquals(Collections.singletonList(1),observed.getCoverage().getFailedPages());assertEquals(Collections.singletonList(1),observed.getCoverage().getNeedsReviewPages());assertTrue(observed.getCoverage().getBlankPages().isEmpty());
        DocumentParser.ParsedDocument result=parser.parseOrdinary("unreadable.pdf",bytes,null,manual(bytes));
        assertEquals("FAILED",result.getParseStatus());assertTrue(result.getText().isEmpty());assertFalse(result.getCoverage().isComplete());
        assertEquals(0,result.getCoverage().getParsedPages());assertEquals(Collections.singletonList(1),result.getCoverage().getFailedPages());assertTrue(result.getCoverage().getBlankPages().isEmpty());
        assertEquals(Collections.singletonList(1),result.getCoverage().getNeedsReviewPages());
        assertTrue(result.getCoverage().getLimitations().stream().anyMatch(limit->limit.contains("OCR disabled or service unavailable")));
    }

    @Test void confirmedTextualScanUsesRequiredOcrWithoutPromotingSourceQuality()throws Exception {
        byte[] bytes=DocumentParseProbeTest.pdf(false);String sha=DocumentParseProbe.sha256(bytes);List<String> calls=new ArrayList<>();
        OcrClient ocr=new OcrClient(){
            public boolean available(){calls.add("available");return true;}
            public OcrResult recognize(byte[] image){calls.add("recognize");assertTrue(image.length>0);return new OcrResult("Scanned contract clause",.9,
                    Collections.singletonList(new OcrLine("Scanned contract clause",.9,new double[]{10,10,40,10})));}
        };
        DocumentParser parser=new DocumentParser(new ConsenseProperties(),ocr);
        DocumentParser.ParsedDocument result=parser.parseOrdinary("scan.pdf",bytes,null,manual(bytes));
        assertEquals(Arrays.asList("available","recognize"),calls);
        assertEquals("--- P1 ---\nScanned contract clause",result.getText());assertEquals(sha,result.getSourceSha256());
        assertEquals(1,result.getCoverage().getOcrPages());assertEquals(1,result.getCoverage().getParsedPages());
        assertTrue(result.getCoverage().getFailedPages().isEmpty());assertEquals(Collections.singletonList(1),result.getCoverage().getNeedsReviewPages());
        assertFalse(result.getCoverage().isComplete());assertEquals("PARTIAL",result.getParseStatus());
        assertTrue(result.getBlocks().stream().anyMatch(block->"ocr".equals(block.getSource())&&"Scanned contract clause".equals(block.getText())));
        calls.clear();DocumentParser.ParsedDocument unconfirmed=parser.parseOrdinary("scan.pdf",bytes,parser.observeOrdinary("scan.pdf",bytes),null);
        assertTrue(calls.isEmpty());assertEquals("FAILED",unconfirmed.getParseStatus());assertEquals(0,unconfirmed.getCoverage().getOcrPages());
    }

    @Test void ordinaryEntrypointsRetainExactIdentityAndLiveObservationGuards()throws Exception {
        byte[] bytes=OerEngineIntegrationTest.vectorScopePdf(false);ConsenseProperties properties=new ConsenseProperties();DocumentParser parser=new DocumentParser(properties,forbidden());
        DocumentParser.ParsedDocument observed=parser.observeOrdinary("reading.pdf",bytes);OerDocumentIdentity wrong=manual(bytes);wrong.setSourceSha256("0".repeat(64));
        assertThrows(IllegalArgumentException.class,()->parser.parseOrdinary("reading.pdf",bytes,null,wrong));
        assertThrows(IllegalArgumentException.class,()->parser.parseOrdinary("reading.pdf",bytes,"wrong","source",DocumentParseProbe.sha256(bytes),null,wrong));
        OerDocumentIdentity excluded=manual(bytes);excluded.setKind("DRAWING");assertThrows(IllegalArgumentException.class,()->parser.parseOrdinary("reading.pdf",bytes,null,excluded));
        OerDocumentIdentity nonmanual=manual(bytes);nonmanual.setIdentityStatus("content_supported");assertThrows(IllegalArgumentException.class,()->parser.parseOrdinary("reading.pdf",bytes,null,nonmanual));
        properties.getOcr().setEnabled(!properties.getOcr().isEnabled());assertThrows(IllegalArgumentException.class,()->parser.parseOrdinary("reading.pdf",bytes,observed,manual(bytes)));
    }
}
