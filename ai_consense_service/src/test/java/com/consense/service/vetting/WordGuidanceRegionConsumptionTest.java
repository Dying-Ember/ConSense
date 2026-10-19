package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParseProbe;
import com.consense.document.DocumentParser;
import com.consense.domain.SourceDocument;
import com.consense.ocr.OcrClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.util.*;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.StylesDocument;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Same real DOCX bytes exercise the source-region producer and its ordinary consumer. */
class WordGuidanceRegionConsumptionTest {
    @Test void explicitlyBoundGuidanceRegionDisambiguatesRestartedNumbersThroughSelectedModelAndCompact()throws Exception{
        OcrClient ocr=mock(OcrClient.class);byte[] original;
        try(XWPFDocument document=new XWPFDocument()){
            document.createStyles().setStyles(StylesDocument.Factory.parse("<w:styles xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:style w:type='paragraph' w:styleId='heading'><w:pPr><w:outlineLvl w:val='2'/></w:pPr></w:style><w:style w:type='paragraph' w:styleId='clause'/></w:styles>").getStyles());
            paragraph(document,"Policy","heading");paragraph(document,"1.Body first interval","clause");paragraph(document,"2.Body second interval","clause");document.createParagraph();
            paragraph(document,"(Guidance Notes to the Contractor:",null);document.createParagraph();paragraph(document,"1. Guidance first (nested clarification)","clause");paragraph(document,"2. Guidance second)","clause");
            paragraph(document,"Following unnumbered payload",null);paragraph(document,"Next chapter","heading");ByteArrayOutputStream out=new ByteArrayOutputStream();document.write(out);original=out.toByteArray();
        }
        DocumentParser.ParsedDocument parsed=new DocumentParser(new ConsenseProperties(),ocr).parse("authored-guidance.docx",original);
        SourceDocument source=new SourceDocument();source.setId(98L);source.setProjectId("authored");source.setFileName("authored-guidance.docx");source.setFileKey("PRE");source.setReviewRole("tender");source.setDocumentScope("fullset");source.setParseStatus(parsed.getParseStatus());source.setSourceSha256(DocumentParseProbe.sha256(original));source.setTextContent(parsed.getText());source.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));source.setParseCoverageJson(JsonUtils.write(parsed.getCoverage()));
        JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes");JsonNode region=null;Set<String> expectedManual=new LinkedHashSet<>();
        for(JsonNode range:scopes.path("ranges")){if("source-region".equals(range.path("kind").asText()))region=range;if("manual-sequence".equals(range.path("kind").asText()))expectedManual.add(range.path("id").asText());}
        assertNotNull(region,"The real source must distinguish its explicit guidance interval");assertEquals(4,expectedManual.size());
        NormalSourceCandidateGraph.capture(source);List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));Set<String> selectedManual=new LinkedHashSet<>();boolean regionSelected=false;
        for(VettingCorpus.Chunk chunk:chunks){JsonNode selected=chunk.getCandidateStructure().path("sourceClauseScopes");
            assertFalse(selected.path("issues").toString().contains("ambiguous_source_reference"),"Same number in a different real source-region parent is not a duplicate inside one interval");
            for(JsonNode range:selected.path("ranges")){if("manual-sequence".equals(range.path("kind").asText()))selectedManual.add(range.path("id").asText());if(region.path("id").equals(range.path("id")))regionSelected=true;}
        }
        assertEquals(expectedManual,selectedManual);assertTrue(regionSelected);
        JsonNode material=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(chunks,Collections.singletonList(source)));
        assertTrue(material.toString().contains("ordinary_docx_source_clause"));assertTrue(material.toString().contains("source-region"));assertTrue(material.toString().contains("Guidance first (nested clarification)"));assertTrue(material.toString().contains("Body first interval"));
        assertEquals(material,VettingSourceMetadataWire.decode(VettingSourceMetadataWire.write(material)));verifyNoInteractions(ocr);
    }
    private static void paragraph(XWPFDocument document,String text,String style){XWPFParagraph paragraph=document.createParagraph();if(style!=null)paragraph.setStyle(style);paragraph.createRun().setText(text);}
}
