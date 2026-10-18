package com.consense.document;

import com.consense.config.ConsenseProperties;
import com.consense.ocr.OcrClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.*;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OerDocxSourceScopesTest {
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    @Test void explicitManualSequenceKeepsAllPayloadAndResolvedListContinuationsInsideItsNativeChapter()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=document()){
            paragraph(d,"Authored chapter","heading");paragraph(d,"6.Provide the following system:","clause");
            numbered(d,"First item");paragraph(d,"Continuation of first item",null);d.createParagraph();
            numbered(d,"Second item");XWPFTableCell cell=d.createTable(1,1).getRow(0).getCell(0);cell.setText("Intermediate table payload");cell.getParagraphs().get(0).setStyle("heading");
            XWPFParagraph next=paragraph(d,"7.Provide next mock-up","clause");next.getCTP().getPPr().addNewInd().setLeftChars(BigInteger.valueOf(993));
            paragraph(d,"Next authored chapter","heading");parsed=parse(d,ocr);
        }
        JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes"),six=range(scopes,"manual-sequence","6"),seven=range(scopes,"manual-sequence","7");
        assertTrue(six.path("scopeComplete").asBoolean());assertTrue(seven.path("scopeComplete").asBoolean());
        assertEquals("source_interval_containment_only",six.path("hierarchyStatus").asText());
        assertEquals(seven.path("startBoundary").path("sourcePosition"),six.path("endBoundary").path("sourcePosition"));
        List<JsonNode> body=members(scopes,six);assertTrue(body.stream().anyMatch(m->text(m).isEmpty()));
        assertTrue(body.stream().anyMatch(m->text(m).equals("Intermediate table payload")));assertFalse(body.stream().anyMatch(m->text(m).startsWith("7.")));
        JsonNode a=range(scopes,"native-list","6(a)"),b=range(scopes,"native-list","6(b)");
        assertEquals(six.path("id"),a.path("parentId"));assertEquals(b.path("startBoundary").path("sourcePosition"),a.path("endBoundary").path("sourcePosition"));
        assertEquals(Arrays.asList("(a) First item","Continuation of first item",""),members(scopes,a).stream().map(this::text).toList());
        assertNull(parsed.getCoverage().getNativeDocxParagraphs(),"Ordinary route does not expose the opt-in XML inventory");
        for(JsonNode member:scopes.path("members"))assertFalse(member.has("nativeXml"));
        verifyNoInteractions(ocr);
    }

    @Test void isolatedNumbersDatesAndDifferentStylesDoNotInventASourceSequence()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=document()){
            paragraph(d,"Chapter","heading");paragraph(d,"2026.10.17 date","clause");paragraph(d,"1.0 version","clause");
            paragraph(d,"6.Standalone number","clause");paragraph(d,"7.Different source style","other");paragraph(d,"Next chapter","heading");parsed=parse(d,ocr);
        }
        for(JsonNode range:parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes").path("ranges"))assertEquals("native-outline",range.path("kind").asText());
        verifyNoInteractions(ocr);
    }

    @Test void repeatedManualReferencesAndAnUnprovedParentEndNeverBecomeComplete()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=document()){
            paragraph(d,"Chapter","heading");paragraph(d,"5.Previous interval","clause");paragraph(d,"6.First source interval","clause");numbered(d,"Item ending at an ambiguous peer");
            paragraph(d,"8.Nonconsecutive peer","clause");paragraph(d,"9.First duplicate","clause");paragraph(d,"9.Second duplicate","clause");paragraph(d,"10.Next peer","clause");paragraph(d,"Next chapter","heading");parsed=parse(d,ocr);
        }
        JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes");int repeated=0;
        for(JsonNode range:scopes.path("ranges"))if("manual-sequence".equals(range.path("kind").asText())&&"9".equals(range.path("reference").asText())){repeated++;assertFalse(range.path("scopeComplete").asBoolean());}
        assertEquals(2,repeated);assertFalse(range(scopes,"native-list","6(a)").path("scopeComplete").asBoolean());verifyNoInteractions(ocr);
    }

    @Test void ordinaryScopesReuseNativeTocFieldRolesWithoutXmlOrConfusingTheDirectoryWithTheBody()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument normal,template;byte[] bytes;
        try(XWPFDocument d=document()){
            paragraph(d,"SPECIAL CONDITIONS OF CONTRACT",null);
            d.createParagraph().getCTP().set(CTP.Factory.parse("<xml-fragment xmlns:w='"+W+"'><w:fldSimple w:instr='TOC'><w:hyperlink w:anchor='_Toc1'><w:r><w:t>SCC1 Authored clause</w:t></w:r><w:fldSimple w:instr='PAGEREF _Toc1'><w:r><w:t> 1</w:t></w:r></w:fldSimple></w:hyperlink></w:fldSimple></xml-fragment>"));
            paragraph(d,"SCC1 Authored clause",null);paragraph(d,"Body source text",null);paragraph(d,"SCC2 Next authored clause",null);
            ByteArrayOutputStream out=new ByteArrayOutputStream();d.write(out);bytes=out.toByteArray();
        }
        DocumentParser parser=new DocumentParser(new ConsenseProperties(),ocr);normal=parser.parse("source.docx",bytes);template=parser.parseTemplate("SCC","source.docx",bytes);
        JsonNode scopes=normal.getCoverage().getNativeDocxOutline().path("sourceClauseScopes");assertEquals(2,scopes.path("ranges").size());
        JsonNode first=scopes.path("ranges").get(0);assertEquals("SCC1",first.path("reference").asText());assertTrue(first.path("scopeComplete").asBoolean());
        assertEquals(Arrays.asList("SCC1 Authored clause","Body source text"),members(scopes,first).stream().map(this::text).toList());
        assertEquals(scopes,template.getCoverage().getNativeDocxOutline().path("sourceClauseScopes"));
        JsonNode copied=scopes.path("members").get(1);assertFalse(copied.has("nativeXml"));assertEquals("toc_result",copied.path("nativeFieldRoles").get(0).path("fieldRole").asText());verifyNoInteractions(ocr);
    }

    @Test void paragraphToCarrierRangesAreExactAndOrderedWhileRepeatedTextStaysUnmapped()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=document()){
            XWPFTableCell cell=d.createTable(1,1).getRow(0).getCell(0);cell.setText("Unique first source paragraph");
            cell.addParagraph().createRun().setText("Unique continuation "+"long source text ".repeat(120));
            XWPFTableCell repeated=d.createTable(1,1).getRow(0).getCell(0);repeated.setText("Repeated paragraph");repeated.addParagraph().createRun().setText("Repeated paragraph");parsed=parse(d,ocr);
        }
        Map<String,DocumentBlock> blocks=new HashMap<>();parsed.getBlocks().forEach(b->blocks.put(b.getId(),b));int unique=0,repeated=0;
        for(JsonNode member:parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes").path("members")){
            if(text(member).startsWith("Unique")){unique++;assertEquals(1,member.path("retainedBlockRanges").size());JsonNode range=member.path("retainedBlockRanges").get(0);DocumentBlock block=blocks.get(range.path("blockId").asText());assertEquals(text(member),block.getText().substring(range.path("startOffset").asInt(),range.path("endOffset").asInt()));assertEquals(DocumentParseProbe.sha256(block.getText().getBytes(java.nio.charset.StandardCharsets.UTF_8)),range.path("parentEffectiveTextSha256").asText());}
            if(text(member).equals("Repeated paragraph")){repeated++;assertTrue(member.path("retainedBlockRanges").isEmpty());}
        }
        assertEquals(2,unique);assertEquals(2,repeated);verifyNoInteractions(ocr);
    }

    private XWPFDocument document()throws Exception {
        XWPFDocument d=new XWPFDocument();d.createStyles().setStyles(StylesDocument.Factory.parse("<w:styles xmlns:w='"+W+"'><w:style w:type='paragraph' w:styleId='heading'><w:pPr><w:outlineLvl w:val='2'/></w:pPr></w:style><w:style w:type='paragraph' w:styleId='clause'/><w:style w:type='paragraph' w:styleId='other'/></w:styles>").getStyles());
        d.createNumbering().setNumbering(NumberingDocument.Factory.parse("<w:numbering xmlns:w='"+W+"'><w:abstractNum w:abstractNumId='1'><w:lvl w:ilvl='0'><w:start w:val='1'/><w:numFmt w:val='lowerLetter'/><w:lvlText w:val='(%1)'/></w:lvl></w:abstractNum><w:num w:numId='1'><w:abstractNumId w:val='1'/></w:num></w:numbering>").getNumbering());return d;
    }
    private XWPFParagraph paragraph(XWPFDocument d,String text,String style){XWPFParagraph p=d.createParagraph();if(style!=null)p.setStyle(style);p.createRun().setText(text);return p;}
    private void numbered(XWPFDocument d,String text){XWPFParagraph p=paragraph(d,text,"clause");p.setNumID(BigInteger.ONE);p.setNumILvl(BigInteger.ZERO);}
    private DocumentParser.ParsedDocument parse(XWPFDocument d,OcrClient ocr)throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();d.write(out);return new DocumentParser(new ConsenseProperties(),ocr).parse("source.docx",out.toByteArray());}
    private JsonNode range(JsonNode scopes,String kind,String reference){List<JsonNode> matches=new ArrayList<>();for(JsonNode range:scopes.path("ranges"))if(kind.equals(range.path("kind").asText())&&reference.equals(range.path("reference").asText()))matches.add(range);assertEquals(1,matches.size(),kind+" "+reference);return matches.get(0);}
    private List<JsonNode> members(JsonNode scopes,JsonNode range){Map<String,JsonNode> byId=new HashMap<>();scopes.path("members").forEach(m->byId.put(m.path("id").asText(),m));List<JsonNode> out=new ArrayList<>();range.path("orderedMemberIds").forEach(id->out.add(Objects.requireNonNull(byId.get(id.asText()))));return out;}
    private String text(JsonNode member){return member.path("textObservation").path("text").asText();}
}
