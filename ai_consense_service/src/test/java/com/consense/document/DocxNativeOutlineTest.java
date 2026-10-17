package com.consense.document;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.domain.SourceDocument;
import com.consense.ocr.OcrClient;
import com.consense.service.vetting.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.w3c.dom.Node;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocxNativeOutlineTest {
    @Test void customStyleInheritanceDefinesTableScopeUntilTheNextPeerWithoutOcr() throws Exception {
        try (XWPFDocument doc = document("<w:style w:type='paragraph' w:styleId='base'><w:name w:val='Custom Contract'/><w:pPr><w:outlineLvl w:val='3'/></w:pPr></w:style>"
                + "<w:style w:type='paragraph' w:styleId='local'><w:name w:val='项目自定'/><w:basedOn w:val='base'/></w:style>")) {
            paragraph(doc,"LOCAL ABBREVIATIONS","local");paragraph(doc,"Original introduction",null);
            XWPFTable table=doc.createTable(2,2);table.getRow(0).getCell(0).setText("ABC");table.getRow(1).getCell(0).setText("ZIS");
            XWPFParagraph child=paragraph(doc,"Child provision",null);child.getCTP().addNewPPr().addNewOutlineLvl().setVal(java.math.BigInteger.valueOf(4));
            paragraph(doc,"Child text",null);paragraph(doc,"NEXT SPECIFICATION","local");
            DocumentParser.ParsedDocument parsed=parse(doc);JsonNode inventory=parsed.getCoverage().getNativeDocxOutline();
            assertNotNull(inventory);assertEquals(3,inventory.path("headings").size());
            JsonNode first=inventory.path("headings").get(0),nested=inventory.path("headings").get(1),next=inventory.path("headings").get(2);
            assertEquals(3,first.path("outlineLevel").asInt());assertEquals("paragraph_style",first.path("properties").path("outlineBasis").asText());
            assertEquals(0,nested.path("parentOrdinalZeroBased").asInt());assertTrue(next.path("parentOrdinalZeroBased").isNull());
            assertEquals(next.path("sourcePosition"),first.path("endBoundary").path("sourcePosition"));
            List<DocumentBlock> blocks=parsed.getBlocks();Set<String> members=new HashSet<>();first.path("retainedBlockIds").forEach(id->members.add(id.asText()));
            for(DocumentBlock b:blocks)if("table_row".equals(b.getKind()))assertTrue(members.contains(b.getId()));
            assertFalse(members.contains(blocks.get(blocks.size()-1).getId()));
            assertEquals("native_heading",blocks.get(0).getReadingContext().path("role").asText());
            assertEquals(first.path("sourcePosition"),blocks.get(0).getSourcePosition());
            assertEquals("unknown",blocks.get(0).getReadingContext().path("ownerStatus").asText());
            com.consense.domain.SourceDocument source=new com.consense.domain.SourceDocument();source.setId(925L);source.setProjectId("authored-outline");source.setFileName("authored.docx");
            source.setFileKey("SCT");source.setReviewRole("tender");source.setDocumentScope("package");source.setSourceSha256(parsed.getSourceSha256());
            source.setTextContent(parsed.getText());source.setParseStatus(parsed.getParseStatus());source.setStructuredContentJson(com.consense.common.JsonUtils.write(blocks));source.setParseCoverageJson(com.consense.common.JsonUtils.write(parsed.getCoverage()));
            com.consense.service.vetting.NormalSourceCandidateGraph.capture(source);
            assertEquals(inventory,com.consense.service.vetting.NormalSourceCandidateGraph.requireBound(source).path("nativeDocxOutline"));
            java.util.List<com.consense.service.vetting.VettingCorpus.Chunk> chunks=com.consense.service.vetting.VettingCorpus.chunks(Collections.singletonList(source));
            JsonNode materials=com.consense.common.JsonUtils.mapper().valueToTree(com.consense.service.vetting.VettingSourceMaterial.project(chunks,Collections.singletonList(source)));
            boolean selected=false;for(JsonNode material:materials)for(JsonNode heading:material.path("candidateStructure").path("nativeDocxOutline").path("headings")){
                selected=true;assertEquals(inventory.path("headings").get(heading.path("ordinalZeroBased").asInt()),heading);
            }
            assertTrue(selected,"Native source hierarchy reaches ordinary selected material");
        }
    }

    @Test void directBodyOutlineCancelsInheritedHeadingAndANameAloneDoesNotCreateOne() throws Exception {
        try(XWPFDocument doc=document("<w:style w:type='paragraph' w:styleId='looks'><w:name w:val='Heading looking name'/></w:style>"
                +"<w:style w:type='paragraph' w:styleId='actual'><w:pPr><w:outlineLvl w:val='2'/></w:pPr></w:style>")){
            paragraph(doc,"Name is only a name","looks");
            XWPFParagraph body=paragraph(doc,"Explicit body level","actual");body.getCTP().getPPr().addNewOutlineLvl().setVal(java.math.BigInteger.valueOf(9));
            DocumentParser.ParsedDocument parsed=parse(doc);
            assertNotNull(parsed.getCoverage().getNativeDocxOutline());assertTrue(parsed.getCoverage().getNativeDocxOutline().path("headings").isEmpty());
            for(DocumentBlock block:parsed.getBlocks())assertNotEquals("native_heading",block.getReadingContext().path("role").asText());
        }
    }

    @Test void perDocumentDefaultsAndStyleCyclesRemainDistinct() throws Exception {
        try(XWPFDocument doc=document("<w:docDefaults><w:pPrDefault><w:pPr><w:outlineLvl w:val='1'/></w:pPr></w:pPrDefault></w:docDefaults>"
                +"<w:style w:type='paragraph' w:styleId='a'><w:basedOn w:val='b'/></w:style>"
                +"<w:style w:type='paragraph' w:styleId='b'><w:basedOn w:val='a'/></w:style>")){
            paragraph(doc,"Document-specific default",null);paragraph(doc,"Cyclic source style","a");
            DocumentParser.ParsedDocument parsed=parse(doc);assertNotNull(parsed.getCoverage().getNativeDocxOutline());JsonNode headings=parsed.getCoverage().getNativeDocxOutline().path("headings");
            assertEquals(1,headings.size());assertEquals(1,headings.get(0).path("outlineLevel").asInt());
            assertEquals("style_inheritance_cycle",parsed.getBlocks().get(1).getReadingContext().path("nativeDocxParagraph").path("status").asText());
        }
    }

    @Test void directOutlineDoesNotDependOnAnUnneededBrokenStyleAncestor() throws Exception {
        try(XWPFDocument doc=document("<w:style w:type='paragraph' w:styleId='local'><w:basedOn w:val='missing'/></w:style>")){
            XWPFParagraph p=paragraph(doc,"Explicit native heading","local");p.getCTP().getPPr().addNewOutlineLvl().setVal(java.math.BigInteger.valueOf(2));
            DocumentParser.ParsedDocument parsed=parse(doc);JsonNode heading=parsed.getCoverage().getNativeDocxOutline().path("headings").get(0);
            assertEquals(2,heading.path("outlineLevel").asInt());assertEquals("paragraph",heading.path("properties").path("outlineBasis").asText());
            assertEquals("missing_paragraph_style",heading.path("properties").path("styleChainStatus").asText());
        }
    }

    private XWPFDocument document(String styles)throws Exception{
        XWPFDocument doc=new XWPFDocument();doc.createStyles().setStyles(StylesDocument.Factory.parse("<w:styles xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'>"+styles+"</w:styles>").getStyles());return doc;
    }
    private XWPFParagraph paragraph(XWPFDocument doc,String text,String style){XWPFParagraph p=doc.createParagraph();if(style!=null)p.setStyle(style);p.createRun().setText(text);return p;}
    private DocumentParser.ParsedDocument parse(XWPFDocument doc)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();doc.write(bytes);OcrClient ocr=mock(OcrClient.class);
        DocumentParser.ParsedDocument parsed=new DocumentParser(new ConsenseProperties(),ocr).parse("authored.docx",bytes.toByteArray());verifyNoInteractions(ocr);return parsed;
    }

    // I29: source-bound headings from the existing normal paragraph pass.

    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String STYLE="<w:style w:type='paragraph' w:styleId='Local'><w:pPr><w:outlineLvl w:val='2'/></w:pPr></w:style>";
    private static CTP p(String content)throws Exception{return CTP.Factory.parse("<xml-fragment xmlns:w='"+W+"'>"+content+"</xml-fragment>");}
    private static String run(String text){return "<w:r><w:t xml:space='preserve'>"+text+"</w:t></w:r>";}
    private static String heading(String text){return "<w:pPr><w:pStyle w:val='Local'/></w:pPr>"+run(text);}
    private static XWPFDocument document()throws Exception{XWPFDocument d=new XWPFDocument();d.createStyles().setStyles(StylesDocument.Factory.parse("<w:styles xmlns:w='"+W+"'>"+STYLE+"</w:styles>").getStyles());return d;}
    private static byte[] save(XWPFDocument d)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();d.write(out);byte[] bytes=out.toByteArray();
        Path evidence=Paths.get("target","i29-fixture-evidence");Files.createDirectories(evidence);Files.write(evidence.resolve(DocumentParseProbe.sha256(bytes)+".docx"),bytes);return bytes;
    }
    private static DocumentParser parser(OcrClient ocr){ConsenseProperties props=new ConsenseProperties();props.getOcr().setEnabled(false);return new DocumentParser(props,ocr);}
    private static JsonNode headings(DocumentParser.ParsedDocument parsed){return parsed.getCoverage().getNativeDocxOutline().path("headings");}
    private static void channels(JsonNode observation,DocumentBlock block){
        assertEquals("observed_parser_paragraph_channels",observation.path("status").asText());
        assertEquals(block.getText(),observation.path("text").asText());assertEquals(block.getOriginalText(),observation.path("originalText").asText());
        assertEquals(block.getDeletedText(),observation.path("deletedText").asText());assertEquals(block.getStrikeText(),observation.path("strikeText").asText());
        assertEquals(block.getSourcePosition(),observation.path("sourcePosition"));
    }
    private static SourceDocument source(DocumentParser.ParsedDocument parsed){
        SourceDocument s=new SourceDocument();s.setId(-2901L);s.setCategory("VETTING_PACKAGE");s.setDocumentScope("package");s.setFileName("authored-heading.docx");
        s.setSourceSha256(parsed.getSourceSha256());s.setTextContent(parsed.getText());s.setParseStatus(parsed.getParseStatus());
        s.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));s.setParseCoverageJson(JsonUtils.write(parsed.getCoverage()));return s;
    }

    @Test void lastSelectedTableRowsCarryOwnerAndNextHeadingWordingThroughOrdinaryAndWire()throws Exception {
        byte[] bytes;try(XWPFDocument d=document()){
            d.createParagraph().getCTP().set(p(heading("LOCAL ABBREVIATIONS")));
            XWPFTable table=d.createTable(18,2);for(int i=0;i<18;i++){table.getRow(i).getCell(0).setText("Term "+i);table.getRow(i).getCell(1).setText((i==17?"FINAL TABLE ROW ":"Definition ")+"source text ".repeat(24));}
            d.createParagraph().getCTP().set(p(heading("NEXT SPECIFICATION")));
            d.createParagraph().getCTP().set(p(run("UNSELECTED NEXT BODY MUST NOT BECOME THE BOUNDARY TITLE")));bytes=save(d);
        }
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed=parser(ocr).parse("arbitrary.docx",bytes);
        SourceDocument s=source(parsed);NormalSourceCandidateGraph.capture(s);List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(s));
        DocumentBlock last=parsed.getBlocks().stream().filter(b->b.getText().contains("FINAL TABLE ROW")).findFirst().orElseThrow();
        VettingCorpus.Chunk selected=chunks.stream().filter(c->c.getParts().stream().anyMatch(part->last.getId().equals(part.getBlockId()))).findFirst().orElseThrow();
        JsonNode ordinary=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(Collections.singletonList(selected),Collections.singletonList(s)));
        JsonNode expected=headings(parsed).get(0);assertEquals("LOCAL ABBREVIATIONS",expected.path("textObservation").path("text").asText());
        assertEquals("NEXT SPECIFICATION",expected.path("endBoundaryTextObservation").path("text").asText());
        assertEquals(expected.path("endBoundary").path("sourcePosition"),expected.path("endBoundaryTextObservation").path("sourcePosition"));
        assertFalse(expected.path("endBoundaryTextObservation").toString().contains("UNSELECTED NEXT BODY"));
        JsonNode retained=ordinary.get(0).path("candidateStructure").path("nativeDocxOutline").path("headings");
        assertTrue(retained.findValues("ordinalZeroBased").stream().anyMatch(n->n.asInt()==0));
        assertTrue(java.util.stream.StreamSupport.stream(retained.spliterator(),false).anyMatch(expected::equals));
        JsonNode wire=VettingSourceMetadataWire.encode(ordinary),decoded=VettingSourceMetadataWire.decode(wire);assertEquals(ordinary,decoded);
        assertEquals(expected.path("retainedBlockIds"),NormalSourceCandidateGraph.requireBound(s).path("nativeDocxOutline").path("headings").get(0).path("retainedBlockIds"));
        verifyNoInteractions(ocr);
    }

    @Test void headingChannelsReuseParsedNumberedRevisionsWithoutAdvancingTheListAgain()throws Exception {
        byte[] bytes;try(XWPFDocument d=document()){
            XWPFNumbering numbering=d.createNumbering();CTAbstractNum raw=CTAbstractNum.Factory.newInstance();raw.setAbstractNumId(BigInteger.ZERO);
            CTLvl level=raw.addNewLvl();level.setIlvl(BigInteger.ZERO);level.addNewStart().setVal(BigInteger.ONE);level.addNewNumFmt().setVal(STNumberFormat.DECIMAL);level.addNewLvlText().setVal("%1.");
            BigInteger num=numbering.addNum(numbering.addAbstractNum(new XWPFAbstractNum(raw)));
            XWPFParagraph first=d.createParagraph();first.setStyle("Local");first.setNumID(num);first.createRun().setText("Heading live");
            XWPFRun struck=first.createRun();struck.setText(" strike");struck.setStrikeThrough(true);first.getCTP().addNewDel().addNewR().addNewDelText().setStringValue(" deleted");
            XWPFParagraph second=d.createParagraph();second.setNumID(num);second.createRun().setText("Body second");
            XWPFParagraph third=d.createParagraph();third.setStyle("Local");third.setNumID(num);third.createRun().setText("Next heading");bytes=save(d);
        }
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed=parser(ocr).parse("numbered.docx",bytes);
        assertEquals(3,parsed.getBlocks().stream().mapToInt(b->b.getWordNumbering().size()).sum());
        assertEquals(Arrays.asList("1. Heading live","2. Body second","3. Next heading"),parsed.getBlocks().stream().map(DocumentBlock::getText).toList());
        channels(headings(parsed).get(0).path("textObservation"),parsed.getBlocks().get(0));
        channels(headings(parsed).get(0).path("endBoundaryTextObservation"),parsed.getBlocks().get(2));
        assertTrue(headings(parsed).get(0).path("textObservation").path("originalText").asText().contains("deleted"));
        assertFalse(headings(parsed).get(0).path("textObservation").path("text").asText().contains("deleted"));verifyNoInteractions(ocr);
    }

    @Test void cellHeadingUsesItsOwnParagraphChannelsAndNeverTheWholeRowOrNeighbourCell()throws Exception {
        byte[] bytes;try(XWPFDocument d=document()){
            XWPFTable table=d.createTable(1,2);XWPFTableCell cell=table.getRow(0).getCell(0);
            cell.getParagraphs().get(0).getCTP().set(p(heading("CELL OWNER")));cell.addParagraph().getCTP().set(p(run("Cell body")));cell.addParagraph().getCTP().set(p(heading("CELL NEXT")));
            table.getRow(0).getCell(1).setText("NEIGHBOUR CELL MUST NOT BE A HEADING");bytes=save(d);
        }
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed=parser(ocr).parse("cells.docx",bytes);JsonNode first=headings(parsed).get(0);
        assertEquals("CELL OWNER",first.path("textObservation").path("text").asText());assertEquals("CELL NEXT",first.path("endBoundaryTextObservation").path("text").asText());
        assertEquals(first.path("sourcePosition"),first.path("textObservation").path("sourcePosition"));
        assertFalse(first.path("textObservation").toString().contains("Cell body"));assertFalse(first.path("textObservation").toString().contains("NEIGHBOUR"));verifyNoInteractions(ocr);
    }

    @Test void textboxStorySharesCapturedParagraphsAndNeverUsesOuterParagraphText()throws Exception {
        byte[] bytes;try(XWPFDocument d=document()){
            String textbox="<w:r><w:pict><v:shape xmlns:v='urn:schemas-microsoft-com:vml'><v:textbox><w:txbxContent>"
                    +"<w:p>"+heading("BOX OWNER")+"</w:p><w:p>"+run("BOX BODY")+"</w:p><w:p>"+heading("BOX NEXT")+"</w:p>"
                    +"</w:txbxContent></v:textbox></v:shape></w:pict></w:r>";
            d.createParagraph().getCTP().set(p(run("OUTER TEXT ")+textbox));bytes=save(d);
        }
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed=parser(ocr).parse("textbox.docx",bytes);JsonNode first=headings(parsed).get(0);
        assertEquals("BOX OWNER",first.path("textObservation").path("text").asText());assertEquals("BOX NEXT",first.path("endBoundaryTextObservation").path("text").asText());
        assertFalse(first.path("textObservation").toString().contains("OUTER TEXT"));assertFalse(first.path("textObservation").toString().contains("BOX BODY"));verifyNoInteractions(ocr);
    }

    @Test void compatibilityAndCachedFieldChannelsStayTheNormalParsersActualObservation()throws Exception {
        byte[] bytes;try(XWPFDocument d=document()){
            String ac="<mc:AlternateContent xmlns:mc='http://schemas.openxmlformats.org/markup-compatibility/2006' xmlns:x='urn:unknown' xmlns:y='urn:other'>"
                    +"<mc:Choice Requires='x'>"+run("RED")+"</mc:Choice><mc:Choice Requires='y'>"+run("BLUE")+"</mc:Choice></mc:AlternateContent>";
            d.createParagraph().getCTP().set(p("<w:pPr><w:pStyle w:val='Local'/></w:pPr>"+ac));
            d.createParagraph().getCTP().set(p("<w:pPr><w:pStyle w:val='Local'/></w:pPr><w:fldSimple w:instr='REF existing_anchor'>"+run("CACHED TITLE")+"</w:fldSimple>"));bytes=save(d);
        }
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed=parser(ocr).parse("complex.docx",bytes);
        channels(headings(parsed).get(0).path("textObservation"),parsed.getBlocks().get(0));channels(headings(parsed).get(1).path("textObservation"),parsed.getBlocks().get(1));
        assertEquals("[unresolved Word AlternateContent]",headings(parsed).get(0).path("textObservation").path("text").asText());
        assertEquals("unknown_no_supported_branch",parsed.getBlocks().get(0).getWordNativeObjects().get(0).path("alternateContent").path("selectionStatus").asText());
        assertEquals("cached_not_recalculated",parsed.getBlocks().get(1).getWordFields().get(0).path("resultStatus").asText());verifyNoInteractions(ocr);
    }

    @Test void normalizationMatchesTheExistingBlockAcrossNbspTabsLineBreaksAndOuterWhitespace()throws Exception {
        byte[] bytes;try(XWPFDocument d=document()){
            d.createParagraph().getCTP().set(p("<w:pPr><w:pStyle w:val='Local'/></w:pPr>"+run("  Alpha\u00a0")
                    +"<w:r><w:tab/><w:t>Beta</w:t><w:br/><w:br/><w:br/><w:t xml:space='preserve'>Tail  </w:t></w:r>"));bytes=save(d);
        }
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed=parser(ocr).parse("spacing.docx",bytes);
        assertEquals("Alpha Beta\n\nTail",parsed.getBlocks().get(0).getText());channels(headings(parsed).get(0).path("textObservation"),parsed.getBlocks().get(0));verifyNoInteractions(ocr);
    }

    @Test void emptyFieldControlHeadingIsObservedEmptyAndTheNextEmptyPeerIsARealBoundaryObservation()throws Exception {
        byte[] bytes;try(XWPFDocument d=document()){
            d.createParagraph().getCTP().set(p("<w:pPr><w:pStyle w:val='Local'/></w:pPr><w:r><w:fldChar w:fldCharType='begin'/><w:instrText>TOC</w:instrText><w:fldChar w:fldCharType='separate'/></w:r>"));
            d.createParagraph().getCTP().set(p(run("Cached source entry")));
            d.createParagraph().getCTP().set(p("<w:pPr><w:pStyle w:val='Local'/></w:pPr><w:r><w:fldChar w:fldCharType='end'/></w:r>"));bytes=save(d);
        }
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed=parser(ocr).parse("empty-controls.docx",bytes);
        assertEquals(3,parsed.getBlocks().size());assertEquals(2,headings(parsed).size());assertEquals("",parsed.getBlocks().get(0).getText());assertEquals("",parsed.getBlocks().get(2).getText());
        channels(headings(parsed).get(0).path("textObservation"),parsed.getBlocks().get(0));channels(headings(parsed).get(0).path("endBoundaryTextObservation"),parsed.getBlocks().get(2));
        assertTrue(headings(parsed).get(0).path("textObservation").has("text"));assertFalse(parsed.getBlocks().get(0).getWordFields().isEmpty());
        assertEquals("complete",parsed.getBlocks().get(0).getWordFields().get(0).path("structureStatus").asText());verifyNoInteractions(ocr);
    }

    @Test void missingCapturedParagraphIsUnknownContainerEndIsNotExpandedAndLegacyShapeStaysUnextended()throws Exception {
        try(XWPFDocument d=document()){
            d.createParagraph().getCTP().set(p(heading("NOT PROVIDED BY THE READER")));
            Function<Node,ObjectNode> position=node->{ObjectNode out=JsonUtils.mapper().createObjectNode();out.put("kind","docx_native");out.put("sourceSha256","a".repeat(64));out.put("partName","word/document.xml");out.put("partSha256","b".repeat(64));out.put("nodePath","authored/"+node.getLocalName());return out;};
            try(XWPFDocument loaded=new XWPFDocument(new ByteArrayInputStream(save(d)))){
            JsonNode legacy=OerNativeDocxOutline.observe(loaded,Collections.emptyList(),Collections.emptyMap(),position);
            JsonNode current=OerNativeDocxOutline.observe(loaded,Collections.emptyList(),Collections.emptyMap(),position,node->null);
            JsonNode old=legacy.path("headings").get(0),heading=current.path("headings").get(0);
            assertFalse(old.has("textObservation"));assertFalse(old.has("endBoundaryTextObservation"));
            assertEquals("unknown",heading.path("textObservation").path("status").asText());assertFalse(heading.path("textObservation").has("text"));
            assertEquals("native_container_end",heading.path("endBoundary").path("kind").asText());assertFalse(heading.has("endBoundaryTextObservation"));
            assertEquals(old.path("nativeMemberPaths"),heading.path("nativeMemberPaths"));assertEquals(old.path("retainedBlockIds"),heading.path("retainedBlockIds"));assertEquals(old.path("endBoundary"),heading.path("endBoundary"));
            }
        }
    }

    @Test void uncapturedNextPeerGetsAnUnknownBoundaryObservationWithoutReadingItsRawDomText()throws Exception {
        try(XWPFDocument d=document()){
            d.createParagraph().getCTP().set(p(heading("CAPTURED FIRST")));d.createParagraph().getCTP().set(p(heading("RAW NEXT MUST NOT BE GUESSED")));
            Function<Node,ObjectNode> position=node->{ObjectNode out=JsonUtils.mapper().createObjectNode();out.put("kind","docx_native");out.put("sourceSha256","a".repeat(64));out.put("partName","word/document.xml");out.put("partSha256","b".repeat(64));out.put("nodePath","authored/"+node.getLocalName());return out;};
            try(XWPFDocument loaded=new XWPFDocument(new ByteArrayInputStream(save(d)))){
            JsonNode inventory=OerNativeDocxOutline.observe(loaded,Collections.emptyList(),Collections.emptyMap(),position,node->null);
            JsonNode first=inventory.path("headings").get(0);assertEquals("next_native_outline_heading",first.path("endBoundary").path("kind").asText());
            JsonNode missing=first.path("endBoundaryTextObservation");assertEquals("unknown",missing.path("status").asText());assertFalse(missing.has("text"));assertFalse(missing.toString().contains("RAW NEXT"));
            assertEquals(first.path("endBoundary").path("sourcePosition"),missing.path("sourcePosition"));
            }
        }
    }
}
