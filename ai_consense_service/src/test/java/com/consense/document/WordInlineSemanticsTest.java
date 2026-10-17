package com.consense.document;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.ocr.OcrClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.StylesDocument;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WordInlineSemanticsTest {
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static String run(String properties,String text){return "<w:r>"+(properties.isEmpty()?"":"<w:rPr>"+properties+"</w:rPr>")+"<w:t xml:space='preserve'>"+text+"</w:t></w:r>";}
    private static CTP paragraph(String content)throws Exception{return CTP.Factory.parse("<w:p xmlns:w='"+W+"'>"+content+"</w:p>");}
    private static byte[] fixture(String styles,String... paragraphs)throws Exception {
        try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            if(!styles.isEmpty())word.createStyles().setStyles(StylesDocument.Factory.parse("<w:styles xmlns:w='"+W+"'>"+styles+"</w:styles>").getStyles());
            for(String p:paragraphs)word.createParagraph().getCTP().set(paragraph(p));word.write(out);return out.toByteArray();
        }
    }
    private static DocumentParser parser(OcrClient ocr){ConsenseProperties props=new ConsenseProperties();props.getOcr().setEnabled(false);return new DocumentParser(props,ocr);}
    private static List<JsonNode> values(DocumentParser.ParsedDocument parsed,String key) {
        List<JsonNode> out=new ArrayList<>();for(DocumentBlock block:parsed.getBlocks())JsonUtils.mapper().valueToTree(block).path(key).forEach(out::add);return out;
    }
    private static JsonNode span(DocumentParser.ParsedDocument parsed,String text) {
        return values(parsed,"wordTextSpans").stream().filter(n->text.equals(n.path("sourceText").asText())).findFirst().orElseThrow(()->new AssertionError("Missing source span "+text));
    }

    @Test void directItalicAndVerticalAlignmentKeepSourceRangesWithoutChangingBodyOrDeletingItalics()throws Exception {
        byte[] bytes=fixture("",run("","Area 580m")+run("<w:vertAlign w:val='superscript'/>","2")+run(""," ")+run("<w:i/>","note😀"));
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed=parser(ocr).parse("arbitrary.docx",bytes);
        assertEquals("Area 580m2 note😀",parsed.getText());assertEquals(2,values(parsed,"wordTextSpans").size());
        JsonNode exponent=span(parsed,"2"),italic=span(parsed,"note😀");assertEquals("superscript",exponent.path("format").path("verticalAlign").path("value").asText());
        assertEquals("resolved",italic.path("format").path("italic").path("status").asText());assertTrue(italic.path("format").path("italic").path("value").asBoolean());
        assertEquals("utf16",italic.path("sourceRange").path("offsetUnit").asText());assertEquals(0,italic.path("sourceRange").path("start").asInt());assertEquals(6,italic.path("sourceRange").path("end").asInt());
        assertEquals(DocumentParseProbe.sha256(bytes),italic.path("sourcePosition").path("sourceSha256").asText());assertEquals("word/document.xml",italic.path("sourcePosition").path("partName").asText());
        assertTrue(italic.path("sourcePosition").path("nodePath").asText().endsWith("/{"+W+"}t[1]"));verifyNoInteractions(ocr);
    }

    @Test void paragraphAndCharacterStyleTogglesAndDirectCancellationsFollowTheirOriginalLayers()throws Exception {
        String styles="<w:docDefaults><w:rPrDefault><w:rPr><w:i w:val='0'/></w:rPr></w:rPrDefault></w:docDefaults>"
                +"<w:style w:type='paragraph' w:styleId='PBase'><w:rPr><w:i/><w:vertAlign w:val='superscript'/></w:rPr></w:style>"
                +"<w:style w:type='paragraph' w:styleId='PDerived'><w:basedOn w:val='PBase'/><w:rPr><w:i/><w:vertAlign w:val='baseline'/></w:rPr></w:style>"
                +"<w:style w:type='character' w:styleId='CBase'><w:rPr><w:i/></w:rPr></w:style>"
                +"<w:style w:type='character' w:styleId='CDerived'><w:basedOn w:val='CBase'/><w:rPr><w:i w:val='false'/></w:rPr></w:style>";
        byte[] bytes=fixture(styles,"<w:pPr><w:pStyle w:val='PDerived'/></w:pPr>"+run("<w:rStyle w:val='CDerived'/>","Inherited")
                +run("<w:rStyle w:val='CDerived'/><w:i w:val='off'/><w:vertAlign w:val='subscript'/>","Cancelled"));
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed=parser(ocr).parse("custom-styles.docx",bytes);
        assertEquals("InheritedCancelled",parsed.getText());JsonNode inherited=span(parsed,"Inherited"),cancelled=span(parsed,"Cancelled");
        assertTrue(inherited.path("format").path("italic").path("value").asBoolean(),"A false toggle in a style does not cancel the inherited true value");
        assertEquals("baseline",inherited.path("format").path("verticalAlign").path("value").asText());assertFalse(cancelled.path("format").path("italic").path("value").asBoolean());
        assertEquals("resolved",cancelled.path("format").path("italic").path("status").asText());assertEquals("subscript",cancelled.path("format").path("verticalAlign").path("value").asText());
        assertTrue(inherited.path("styleTrace").findValues("partName").stream().anyMatch(n->n.asText().equals("word/styles.xml")));verifyNoInteractions(ocr);
    }

    @Test void documentDefaultsArePreservedWhileCyclesAndUnknownStylesNeverInventAResolvedFormat()throws Exception {
        String defaults="<w:docDefaults><w:rPrDefault><w:rPr><w:i/></w:rPr></w:rPrDefault></w:docDefaults>";
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument ordinary=parser(ocr).parse("defaults.docx",fixture(defaults,run("","Default italic")));
        assertTrue(span(ordinary,"Default italic").path("format").path("italic").path("value").asBoolean());
        String cycle="<w:style w:type='paragraph' w:styleId='A'><w:basedOn w:val='B'/></w:style><w:style w:type='paragraph' w:styleId='B'><w:basedOn w:val='A'/></w:style>";
        for(String style:new String[]{"A","missing"}) {
            DocumentParser.ParsedDocument parsed=parser(ocr).parse("unresolved-style.docx",fixture(cycle,"<w:pPr><w:pStyle w:val='"+style+"'/></w:pPr>"+run("","Keep source")));
            assertEquals("A".equals(style)?"[unresolved Word numbering] Keep source":"Keep source",parsed.getText());
            if("A".equals(style))assertEquals("unresolved",parsed.getBlocks().get(0).getWordNumbering().get(0).getResolutionStatus());
            else assertTrue(parsed.getBlocks().get(0).getWordNumbering().isEmpty());
            JsonNode item=span(parsed,"Keep source");assertEquals("unknown",item.path("format").path("italic").path("status").asText());
            assertTrue(item.path("format").path("italic").path("value").isNull());
        }verifyNoInteractions(ocr);
    }

    @Test void realComplexAndSimpleFieldsKeepCachedRolesAcrossParagraphsAndNestedPageReferences()throws Exception {
        String begin="<w:r><w:fldChar w:fldCharType='begin'/><w:instrText xml:space='preserve'> TO</w:instrText><w:instrText>C \\o &quot;1-3&quot; </w:instrText><w:fldChar w:fldCharType='separate'/><w:t>Clause one </w:t></w:r>";
        String nested="<w:r><w:fldChar w:fldCharType='begin'/><w:instrText>PAGEREF anchor</w:instrText><w:fldChar w:fldCharType='separate'/><w:t>8</w:t><w:fldChar w:fldCharType='end'/></w:r><w:r><w:fldChar w:fldCharType='end'/></w:r>";
        byte[] bytes=fixture("",begin,run("","Clause two ")+nested,run("","TOC is ordinary body text"),"<w:fldSimple w:instr='REF separate_anchor'>"+run("","Cross reference")+"</w:fldSimple>");
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed=parser(ocr).parse("renamed.docx",bytes);
        assertTrue(parsed.getText().contains("Clause one"));assertTrue(parsed.getText().contains("Clause two 8"));assertTrue(parsed.getText().contains("TOC is ordinary body text"));
        assertEquals("toc_result",span(parsed,"Clause one ").path("fieldRole").asText());assertEquals("toc_result",span(parsed,"Clause two ").path("fieldRole").asText());assertEquals("toc_result",span(parsed,"8").path("fieldRole").asText());
        assertEquals("field_result",span(parsed,"Cross reference").path("fieldRole").asText());assertFalse(values(parsed,"wordTextSpans").stream().anyMatch(n->n.path("sourceText").asText().equals("TOC is ordinary body text")));
        List<JsonNode> fields=values(parsed,"wordFields");assertTrue(fields.stream().anyMatch(n->n.path("role").asText().equals("toc")&&n.path("rawInstruction").asText().equals(" TOC \\o \"1-3\" ")&&n.path("structureStatus").asText().equals("complete")));
        JsonNode toc=fields.stream().filter(n->n.path("role").asText().equals("toc")).findFirst().orElseThrow();
        assertEquals(toc.path("sourcePosition"),span(parsed,"8").path("fieldSourcePosition"));
        JsonNode simple=fields.stream().filter(n->n.path("kind").asText().equals("simple")).findFirst().orElseThrow();
        assertEquals(simple.path("sourcePosition"),span(parsed,"Cross reference").path("fieldSourcePosition"));
        for(JsonNode field:fields)assertEquals("cached_not_recalculated",field.path("resultStatus").asText());verifyNoInteractions(ocr);
    }

    @Test void incompleteFieldKeepsItsTextAndAnExplicitUnknownStructure()throws Exception {
        byte[] bytes=fixture("","<w:r><w:fldChar w:fldCharType='begin'/><w:instrText>TOC</w:instrText><w:fldChar w:fldCharType='separate'/><w:t>Cached text</w:t></w:r>");
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed=parser(ocr).parse("unfinished-field.docx",bytes);
        assertEquals("Cached text",parsed.getText());assertTrue(values(parsed,"wordFields").stream().anyMatch(n->n.path("structureStatus").asText().equals("unknown")));verifyNoInteractions(ocr);
    }

    @Test void alternateContentSelectsNativeBranchesAndOnlyStrictlyEquivalentPlainTextOnce()throws Exception {
        String start="<mc:AlternateContent xmlns:mc='http://schemas.openxmlformats.org/markup-compatibility/2006' xmlns:x='urn:unimplemented' xmlns:y='urn:also-unimplemented'>",end="</mc:AlternateContent>";
        String nativeChoice=start+"<mc:Choice Requires='w'>"+run("","Selected")+"</mc:Choice><mc:Fallback>"+run("","Other")+"</mc:Fallback>"+end;
        String fallback=start+"<mc:Choice Requires='x'>"+run("","Unsupported")+"</mc:Choice><mc:Fallback>"+run("","Fallback")+"</mc:Fallback>"+end;
        String equal=start+"<mc:Choice Requires='x'>"+run("","Same")+"</mc:Choice><mc:Choice Requires='y'>"+run("","Same")+"</mc:Choice>"+end;
        String different=start+"<mc:Choice Requires='x'>"+run("","Red")+"</mc:Choice><mc:Choice Requires='y'>"+run("","Blue")+"</mc:Choice>"+end;
        byte[] bytes=fixture("",nativeChoice,fallback,equal,different);OcrClient ocr=mock(OcrClient.class);DocumentParser p=parser(ocr);DocumentParser.ParsedDocument parsed=p.parse("compatibility.docx",bytes);
        assertEquals("Selected",parsed.getBlocks().get(0).getText());assertEquals("Fallback",parsed.getBlocks().get(1).getText());assertEquals("Same",parsed.getBlocks().get(2).getText());
        assertEquals("[unresolved Word AlternateContent]",parsed.getBlocks().get(3).getText());
        for(int i=0;i<4;i++)assertTrue(parsed.getBlocks().get(i).getWordNativeObjects().stream().anyMatch(n->n.has("alternateContent")));
        assertTrue(p.projectLegacyDocxMath(bytes).getText().contains("SameSame"));verifyNoInteractions(ocr);
    }

    @Test void cellSpansAndLegacyIsolationRetainTheirActualOwners()throws Exception {
        byte[] bytes;try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            word.createTable(1,2).getRow(0).getCell(0).getParagraphs().get(0).getCTP().set(paragraph(run("<w:i/>","Cell italic")));
            word.getTables().get(0).getRow(0).getCell(1).getParagraphs().get(0).getCTP().set(paragraph(run("<w:vertAlign w:val='superscript'/>","2")));word.write(out);bytes=out.toByteArray();
        }
        OcrClient ocr=mock(OcrClient.class);DocumentParser p=parser(ocr);DocumentParser.ParsedDocument parsed=p.parse("cells.docx",bytes);JsonNode block=JsonUtils.mapper().valueToTree(parsed.getBlocks().get(0));
        assertEquals(2,block.path("wordTextSpans").size());assertEquals(1,block.path("wordCells").get(0).path("wordTextSpans").size());assertEquals(1,block.path("wordCells").get(1).path("wordTextSpans").size());
        assertTrue(block.path("wordCells").get(0).path("wordTextSpans").get(0).path("sourcePosition").path("nodePath").asText().contains("tc[1]"));
        DocumentParser.ParsedDocument legacy=p.projectLegacyDocxMath(bytes);assertEquals(parsed.getText(),legacy.getText());assertTrue(values(legacy,"wordTextSpans").isEmpty());assertTrue(values(legacy,"wordFields").isEmpty());verifyNoInteractions(ocr);
    }

    @Test void emptyFieldControlAndInstructionParagraphsRetainTheSameSourceBoundField()throws Exception {
        byte[] bytes=fixture("","<w:r><w:fldChar w:fldCharType='begin'/></w:r>","<w:r><w:instrText>TOC</w:instrText></w:r>",
                "<w:r><w:fldChar w:fldCharType='separate'/></w:r>",run("","Cached entry"),"<w:r><w:fldChar w:fldCharType='end'/></w:r>");
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed=parser(ocr).parse("control-only.docx",bytes);
        assertEquals("Cached entry",parsed.getText());assertEquals(5,parsed.getBlocks().size());
        JsonNode field=values(parsed,"wordFields").get(0);assertEquals("complete",field.path("structureStatus").asText());assertEquals("TOC",field.path("rawInstruction").asText());assertEquals(3,field.path("controls").size());
        for(JsonNode control:field.path("controls"))assertEquals("word/document.xml",control.path("sourcePosition").path("partName").asText());
        assertFalse(parsed.getBlocks().get(4).getWordFields().isEmpty());assertEquals("toc_result",span(parsed,"Cached entry").path("fieldRole").asText());verifyNoInteractions(ocr);
    }

    @Test void omittedParagraphStyleTypeAndAbsentSimpleInstructionKeepTheirNativeMeaning()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser p=parser(ocr);
        DocumentParser.ParsedDocument styled=p.parse("implicit-type.docx",fixture("<w:style w:styleId='P'><w:rPr><w:i/></w:rPr></w:style>","<w:pPr><w:pStyle w:val='P'/></w:pPr>"+run("","Italic")));
        assertTrue(span(styled,"Italic").path("format").path("italic").path("value").asBoolean());
        DocumentParser.ParsedDocument absent=p.parse("absent-instruction.docx",fixture("","<w:fldSimple>"+run("","Keep cache")+"</w:fldSimple>"));
        assertEquals("Keep cache",absent.getText());assertEquals("unknown",values(absent,"wordFields").get(0).path("structureStatus").asText());verifyNoInteractions(ocr);
    }

    @Test void compatibilityWhitespaceAttributesCannotBeGuessedEquivalent()throws Exception {
        String content="<mc:AlternateContent xmlns:mc='http://schemas.openxmlformats.org/markup-compatibility/2006' xmlns:x='urn:unknown' xmlns:y='urn:other'>"
                +"<mc:Choice Requires='x'><w:r><w:t xml:space='preserve'> Same </w:t></w:r></mc:Choice>"
                +"<mc:Choice Requires='y'><w:r><w:t> Same </w:t></w:r></mc:Choice></mc:AlternateContent>";
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed=parser(ocr).parse("space.docx",fixture("",content));
        assertEquals("[unresolved Word AlternateContent]",parsed.getText());assertEquals("unknown_no_supported_branch",parsed.getBlocks().get(0).getWordNativeObjects().get(0).path("alternateContent").path("selectionStatus").asText());verifyNoInteractions(ocr);
    }

    @Test void conditionalTableRunPropertiesStayUnknownUntilAnExplicitRunOverride()throws Exception {
        byte[] bytes;try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            word.createStyles().setStyles(StylesDocument.Factory.parse("<w:styles xmlns:w='"+W+"'><w:style w:type='table' w:styleId='Conditional'><w:tblStylePr w:type='firstRow'><w:rPr><w:i/></w:rPr></w:tblStylePr></w:style></w:styles>").getStyles());
            org.apache.poi.xwpf.usermodel.XWPFTable table=word.createTable(1,1);table.getCTTbl().getTblPr().addNewTblStyle().setVal("Conditional");
            table.getRow(0).getCell(0).getParagraphs().get(0).getCTP().set(paragraph(run("","Unknown conditional")+run("<w:i w:val='off'/>","Direct off")));word.write(out);bytes=out.toByteArray();
        }
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed=parser(ocr).parse("conditional.docx",bytes);
        assertEquals("unknown",span(parsed,"Unknown conditional").path("format").path("italic").path("status").asText());
        assertEquals("resolved",span(parsed,"Direct off").path("format").path("italic").path("status").asText());assertFalse(span(parsed,"Direct off").path("format").path("italic").path("value").asBoolean());verifyNoInteractions(ocr);
    }

    @Test void malformedCompatibilityOrderChoiceAndRequiresNeverSelectOrDeduplicate()throws Exception {
        String text=run("","Text"),fallback="<mc:Fallback>"+text+"</mc:Fallback>";
        String[] malformed={fallback+"<mc:Choice Requires='w'>"+text+"</mc:Choice>",fallback,
                "<mc:Choice>"+text+"</mc:Choice>"+fallback,"<mc:Choice Requires='unbound'>"+text+"</mc:Choice>"};
        OcrClient ocr=mock(OcrClient.class);DocumentParser p=parser(ocr);
        for(String branches:malformed){
            String alternative="<mc:AlternateContent xmlns:mc='http://schemas.openxmlformats.org/markup-compatibility/2006'>"+branches+"</mc:AlternateContent>";
            DocumentParser.ParsedDocument parsed=p.parse("malformed-compatibility.docx",fixture("",alternative));
            assertEquals("[unresolved Word AlternateContent]",parsed.getText());JsonNode object=parsed.getBlocks().get(0).getWordNativeObjects().get(0);
            assertEquals("unknown_invalid_branches",object.path("alternateContent").path("selectionStatus").asText());
            assertTrue(object.path("nativeXml").asText().contains("Text"));assertEquals("word/document.xml",object.path("sourcePosition").path("partName").asText());
        }verifyNoInteractions(ocr);
    }
}
