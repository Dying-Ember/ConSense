package com.consense.document;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.domain.SourceDocument;
import com.consense.ocr.OcrClient;
import com.consense.service.sources.OerSourceMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Authored parser/context controls only; no real source, model or source-quality acceptance. */
class OerManualTextReadingTest {
    @TempDir Path directory;

    @Test void explicitTextIdentityUsesItsActualFamilyAndOriginalForNativeTableReadingWithAndWithoutProbe() throws Exception {
        byte[] original=OerEngineIntegrationTest.vectorScopePdf(true);String sha=DocumentParseProbe.sha256(original);
        for(boolean probe:Arrays.asList(false,true)){
            List<Map<String,Object>> calls=new ArrayList<>();ConsenseProperties properties=new ConsenseProperties();properties.getOcr().setEnabled(false);if(probe)properties.getDocument().setProbeDirectory(directory.resolve("probe").toString());
            OcrClient client=new OcrClient(){public boolean available(){throw new AssertionError("Native CPU reading does not enable raster OCR");}public OcrResult recognize(byte[] input){throw new AssertionError("No raster/model in this control");}
                @Override public JsonNode observeNativePdf(byte[] actual,Map<String,Object> context){assertArrayEquals(original,actual);calls.add(context);return OerEngineIntegrationTest.nativeTableReply(context);}};
            DocumentParser parser=new DocumentParser(properties,client);OerDocumentIdentity identity=manual(sha,"APH");DocumentParser.ParsedDocument parsed=probe
                    ?parser.parse("APH-reference.pdf",original,"manual-reading","authored-source",sha,null,identity):parser.parse("APH-reference.pdf",original,null,identity);
            assertEquals(1,calls.size());JsonNode plan=JsonUtils.mapper().valueToTree(calls.get(0).get("readingPlan"));assertEquals("APH",plan.path("documentFamily").asText());assertEquals(sha,plan.path("sourceSha256").asText());assertEquals("human_confirmed_textual_material",plan.path("identityStatus").asText());assertEquals("unknown",plan.path("versionStatus").asText());assertEquals("unverified",plan.path("sourceQualityStatus").asText());assertEquals(sha,parsed.getSourceSha256());assertNotEquals("not_executed_identity_unbound",parsed.getCoverage().getNativeTableCandidates().path("status").asText());
        }
    }

    @Test void nullObservationStillRequiresExactManualByteRoleAndExclusionEvidence() throws Exception {
        byte[] original=OerEngineIntegrationTest.vectorScopePdf(true);String sha=DocumentParseProbe.sha256(original);DocumentParser parser=new DocumentParser(new ConsenseProperties(),new OcrClient(){public boolean available(){throw new AssertionError("Must reject before reading");}public OcrResult recognize(byte[] input){throw new AssertionError("Must reject before reading");}});
        for(int mode=0;mode<10;mode++){
            OerDocumentIdentity identity=manual(sha,"APH");if(mode==0)identity.setSourceSha256("0".repeat(64));if(mode==1)identity.setIdentityStatus("content_supported");if(mode==2)identity.setKind("DRAWING");if(mode==3)identity.setRole("unknown");if(mode==4)identity.setEligibleModules(Collections.emptyList());if(mode==5)identity.setSourcePurpose("evaluation_answer");if(mode==6)identity.getCandidates().add(new OerDocumentIdentity.Candidate("DRAWING","unknown","Excluded drawing candidate"));if(mode==7)identity.getIdentityEvidence().clear();if(mode==8)identity.getIdentityEvidence().get(0).setLocation("original:"+"0".repeat(64));if(mode==9)identity.setKind("SL_NON_TECHNICAL");
            assertThrows(IllegalArgumentException.class,()->parser.parse("APH-reference.pdf",original,null,identity),"mode "+mode);
        }
        assertThrows(IllegalArgumentException.class,()->parser.parse("APH-reference.pdf",original,(DocumentParser.ParsedDocument)null,(OerDocumentIdentity)null));
        OerDocumentIdentity unknown=manual(sha,"UNKNOWN");OerReadingPlan plan=new OerReadingPlan(sha,"pdf",unknown);assertTrue(plan.confirmedNonDrawing());assertEquals("UNKNOWN",plan.metadata().path("documentFamily").asText());assertTrue(plan.metadata().path("documentVersion").isNull());
    }

    @Test void firstPassOrdinaryPdfPersistenceDropsGlyphsButKeepsItsLiveTokenAndDrawingHints() throws Exception {
        byte[] original=OerEngineIntegrationTest.vectorScopePdf(true);ConsenseProperties properties=new ConsenseProperties();properties.getOcr().setEnabled(false);DocumentParser parser=new DocumentParser(properties,org.mockito.Mockito.mock(OcrClient.class));DocumentParser.ParsedDocument observation=parser.observe("APH-reference.pdf",original);String before=JsonUtils.write(observation.getBlocks());
        SourceDocument ordinary=new SourceDocument();ordinary.setFileName("APH-reference.pdf");ordinary.setParseStatus(observation.getParseStatus());assertTrue(OerSourceMetadata.captureOrdinaryObservation(ordinary,observation,original));assertNull(ordinary.getNativePdfEvidenceJson());assertEquals(before,JsonUtils.write(observation.getBlocks()));assertTrue(observation.getBlocks().stream().anyMatch(block->!block.getPdfGlyphs().isEmpty()));
        for(DocumentBlock block:JsonUtils.readList(ordinary.getStructuredContentJson(),DocumentBlock.class))assertTrue(block.getPdfGlyphs().isEmpty());JsonNode coverage=JsonUtils.parse(ordinary.getParseCoverageJson());assertFalse(coverage.path("complete").asBoolean());assertEquals(DocumentParseProbe.sha256(original),coverage.path("normalPdfReadingObservation").path("sourceSha256").asText());
        DocumentParser.ParsedDocument detailed=parser.parse("APH-reference.pdf",original,observation,OerSourceMetadata.identity(ordinary));assertEquals(observation.getSourceSha256(),detailed.getSourceSha256());
        SourceDocument rich=new SourceDocument();rich.setFileName("APH-reference.pdf");OerSourceMetadata.captureObservation(rich,observation,original);assertNotNull(rich.getNativePdfEvidenceJson());assertNull(rich.getStructuredContentJson(),"Explicit rich observation API retains its existing caller-owned structure behavior");
        byte[] drawingMetadata;try(org.apache.pdfbox.pdmodel.PDDocument pdf=new org.apache.pdfbox.pdmodel.PDDocument();java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){org.apache.pdfbox.pdmodel.PDPage page=new org.apache.pdfbox.pdmodel.PDPage();pdf.addPage(page);pdf.getDocumentInformation().setTitle("Drawings");try(org.apache.pdfbox.pdmodel.PDPageContentStream stream=new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf,page)){stream.beginText();stream.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font.HELVETICA,10);stream.newLineAtOffset(40,700);stream.showText("Authored unclassified textual material with no source family assertion.");stream.endText();}pdf.save(out);drawingMetadata=out.toByteArray();}
        DocumentParser.ParsedDocument hinted=parser.observe("unidentified.pdf",drawingMetadata);SourceDocument retained=new SourceDocument();retained.setFileName("unidentified.pdf");assertFalse(OerSourceMetadata.captureOrdinaryObservation(retained,hinted,drawingMetadata));assertEquals("IDENTITY_PENDING",retained.getParseStatus());assertEquals(DocumentParseProbe.sha256(drawingMetadata),retained.getSourceSha256());assertEquals("unknown",OerSourceMetadata.identity(retained).getIdentityStatus());assertTrue(OerSourceMetadata.identity(retained).getCandidates().stream().anyMatch(candidate->"DRAWING".equals(candidate.getKind())));assertNull(retained.getNativePdfEvidenceJson());for(DocumentBlock block:JsonUtils.readList(retained.getStructuredContentJson(),DocumentBlock.class))assertTrue(block.getPdfGlyphs().isEmpty());
    }

    private static OerDocumentIdentity manual(String sha,String family){OerDocumentIdentity identity=new OerDocumentIdentity();identity.setSourceSha256(sha);identity.setKind(family);identity.setRole("tender");identity.setIdentityStatus("human_confirmed_textual_material");identity.getIdentityEvidence().add(new OerDocumentIdentity.Evidence("human_text_contract_material","original:"+sha,"Authored explicit textual reading confirmation only"));return identity;}
}
