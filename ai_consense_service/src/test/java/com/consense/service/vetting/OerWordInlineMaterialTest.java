package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParser;
import com.consense.domain.SourceDocument;
import com.consense.ocr.OcrClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OerWordInlineMaterialTest {
    @Test void nativeSpansAndFieldsReachGraphAndOrdinaryWithoutChangingTextOrClaimingBlockOffsets() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(XWPFDocument doc=new XWPFDocument()) {
            XWPFParagraph p=doc.createParagraph();p.createRun().setText("Area m");
            XWPFRun exponent=p.createRun();exponent.setSubscript(VerticalAlign.SUPERSCRIPT);exponent.setText("2");
            XWPFRun guidance=p.createRun();guidance.setItalic(true);guidance.setText(" guidance");
            doc.createParagraph().getCTP().addNewFldSimple().setInstr("TOC \\o \"1-3\"");doc.write(bytes);
        }
        ConsenseProperties props=new ConsenseProperties();props.getOcr().setEnabled(false);
        DocumentParser.ParsedDocument parsed=new DocumentParser(props,mock(OcrClient.class)).parse("authored.docx",bytes.toByteArray());
        SourceDocument source=NormalSourceCandidateGraphTest.ordinaryWordSource();source.setSourceSha256(parsed.getSourceSha256());
        source.setTextContent(parsed.getText());source.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));source.setParseCoverageJson(JsonUtils.write(parsed.getCoverage()));NormalSourceCandidateGraph.capture(source);
        JsonNode graph=NormalSourceCandidateGraph.requireBound(source);assertFalse(graph.findValues("wordTextSpans").isEmpty());assertFalse(graph.findValues("wordFields").isEmpty());
        List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));
        JsonNode ordinary=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(chunks));
        assertFalse(ordinary.findValues("wordTextSpans").isEmpty());assertFalse(ordinary.findValues("wordFields").isEmpty());
        JsonNode original=JsonUtils.mapper().valueToTree(parsed.getBlocks().get(0));
        assertTrue(ordinary.findValues("wordTextSpans").contains(original.path("wordTextSpans")));
        assertEquals("Area m2 guidance",parsed.getBlocks().get(0).getEffectiveText());
        for(JsonNode span:original.path("wordTextSpans")){assertFalse(span.has("startOffset"));assertTrue(span.hasNonNull("sourcePosition"));}
    }
    @Test void objectSourceAndAlternateSelectionReachModelButRawXmlDoesNot() {
        JsonNode objects=JsonUtils.parse("[{\"kind\":\"object\",\"location\":\"body/1/object\",\"sourcePart\":\"word/document.xml\",\"sourcePosition\":{\"kind\":\"docx_native\",\"nodePath\":\"/document/body/p/object\"},\"relationshipRefs\":[{\"targetPart\":\"/word/media/example.emf\",\"sha256\":\"source-asset-hash\"}],\"nativeXml\":\"raw-native-proof\",\"alternateContent\":{\"selectionStatus\":\"fallback\"}}]");
        String before=JsonUtils.write(objects);JsonNode material=OerModelMaterial.wordNativeObjects(objects);String wire=JsonUtils.write(material);
        assertEquals(objects.get(0).path("sourcePosition"),material.get(0).path("sourcePosition"));assertEquals(objects.get(0).path("relationshipRefs"),material.get(0).path("relationshipRefs"));
        assertEquals("fallback",material.get(0).path("alternateContent").path("selectionStatus").asText());
        assertFalse(wire.contains("raw-native-proof"));assertEquals(before,JsonUtils.write(objects));
    }
}
