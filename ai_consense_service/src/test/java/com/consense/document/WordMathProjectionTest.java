package com.consense.document;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.ocr.OcrClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WordMathProjectionTest {
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String M="http://schemas.openxmlformats.org/officeDocument/2006/math";
    private static String run(String text){return "<m:r><m:t>"+text+"</m:t></m:r>";}
    private static String math(String children){return "<m:oMath>"+children+"</m:oMath>";}
    private static String fraction(String numerator,String denominator){return "<m:f><m:num>"+numerator+"</m:num><m:den>"+denominator+"</m:den></m:f>";}
    private static CTP paragraph(String children)throws Exception{return CTP.Factory.parse("<w:p xmlns:w='"+W+"' xmlns:m='"+M+"'>"+children+"</w:p>");}
    private static byte[] document(String children)throws Exception {
        try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            word.createParagraph().getCTP().set(paragraph(children));word.write(out);return out.toByteArray();
        }
    }
    private static DocumentParser parser(OcrClient ocr){ConsenseProperties properties=new ConsenseProperties();properties.getOcr().setEnabled(false);return new DocumentParser(properties,ocr);}
    private static List<JsonNode> objects(DocumentParser.ParsedDocument parsed) {
        List<JsonNode> result=new ArrayList<>();for(DocumentBlock block:parsed.getBlocks())result.addAll(block.getWordNativeObjects());return result;
    }
    private static void projected(JsonNode object,String text) {
        assertEquals("math",object.path("kind").asText());assertEquals("native_ooxml_projected",object.path("status").asText());
        assertEquals("unknown",object.path("semanticStatus").asText());assertTrue(object.path("nativeXml").asText().contains("oMath"));
        assertEquals("omml_linear_v1",object.path("mathProjection").path("encoding").asText());
        assertEquals("projected",object.path("mathProjection").path("status").asText());assertEquals(text,object.path("mathProjection").path("text").asText());
    }

    @Test void fractionsAndAllScriptRolesProjectWithExplicitGroupingAndKeepNativeSource()throws Exception {
        String sub="<m:sSub><m:e>"+run("P+Q")+"</m:e><m:sub>"+run("i+j")+"</m:sub></m:sSub>";
        String sup="<m:sSup><m:e>"+run("x+y")+"</m:e><m:sup>"+run("n+1")+"</m:sup></m:sSup>";
        String both="<m:sSubSup><m:e>"+run("T")+"</m:e><m:sub>"+run("k")+"</m:sub><m:sup>"+run("2")+"</m:sup></m:sSubSup>";
        String expected="R=((P+Q)_(i+j))/((x+y)^(n+1))+(T)_(k)^(2)";
        byte[] original=document(math(run("R=")+fraction(sub,sup)+run("+")+both));byte[] before=original.clone();
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed=parser(ocr).parse("varied-equation.docx",original);
        assertEquals(expected,parsed.getText());assertEquals(expected,parsed.getBlocks().get(0).getOriginalText());
        assertEquals(1,objects(parsed).size());JsonNode object=objects(parsed).get(0);projected(object,expected);
        assertEquals("/word/document.xml",object.path("sourcePart").asText());assertTrue(object.path("location").asText().startsWith("body/"));
        assertEquals(DocumentParseProbe.sha256(original),parsed.getBlocks().get(0).getSourcePosition().path("sourceSha256").asText());
        assertTrue(parsed.getBlocks().get(0).getWordWarnings().isEmpty());assertTrue(parsed.getCoverage().isComplete());
        assertEquals(0,parsed.getPageCount());assertArrayEquals(before,original);verifyNoInteractions(ocr);
    }

    @Test void mathParagraphAndFormattingPropertiesRetainOrderedLiteralText()throws Exception {
        String f="<m:f><m:fPr><m:type m:val='bar'/></m:fPr><m:num>"+run("a+b")+"</m:num><m:den>"+run("c-d")+"</m:den></m:f>";
        String body="<w:r><w:t>Rate </w:t></w:r><m:oMathPara><m:oMathParaPr><m:jc m:val='center'/></m:oMathParaPr>"
                +math("<m:r><m:rPr><m:sty m:val='p'/></m:rPr><w:rPr><w:i/></w:rPr><m:t xml:space='preserve'>A = </m:t></m:r>"+f)
                +"</m:oMathPara><w:r><w:t> applies.</w:t></w:r>";
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed=parser(ocr).parse("paragraph-math.docx",document(body));
        assertEquals("Rate A = (a+b)/(c-d) applies.",parsed.getText());assertEquals(1,objects(parsed).size());
        projected(objects(parsed).get(0),"A = (a+b)/(c-d)");assertTrue(parsed.getBlocks().get(0).getWordWarnings().isEmpty());verifyNoInteractions(ocr);
    }

    @Test void unsupportedMalformedAndInternallyRevisedMathRemainExplicitlyUnresolved()throws Exception {
        String[] bodies={"<m:rad><m:e>"+run("x")+"</m:e></m:rad>",
                "<m:f><m:num>"+run("1")+"</m:num></m:f>",
                "<m:sSub><m:e>"+run("x")+"</m:e><m:e>"+run("y")+"</m:e><m:sub>"+run("k")+"</m:sub></m:sSub>",
                "<m:f><m:fPr><m:type m:val='noBar'/></m:fPr><m:num>"+run("1")+"</m:num><m:den>"+run("2")+"</m:den></m:f>",
                "<w:del w:id='1'>"+run("removed")+"</w:del>"+run("active"),
                "<m:r><w:rPr><w:strike/></w:rPr><m:t>struck</m:t></m:r>"+run("active"),
                "<fake:f xmlns:fake='urn:not-omml'>"+run("1")+run("2")+"</fake:f>"};
        for(String body:bodies) {
            OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed=parser(ocr).parse("unknown-equation.docx",document(math(body)));
            assertEquals("[unresolved Word math]",parsed.getText(),body);assertEquals(1,objects(parsed).size());JsonNode object=objects(parsed).get(0);
            assertEquals("native_ooxml_unresolved",object.path("status").asText());assertEquals("unknown",object.path("semanticStatus").asText());
            assertTrue(object.path("nativeXml").asText().contains("oMath"));assertEquals("unresolved",object.path("mathProjection").path("status").asText());
            assertEquals("",object.path("mathProjection").path("text").asText());assertFalse(object.path("mathProjection").path("reason").asText().isEmpty());
            assertFalse(parsed.getCoverage().isComplete());assertFalse(parsed.getBlocks().get(0).getWordWarnings().isEmpty());verifyNoInteractions(ocr);
        }
    }

    @Test void outsideDeletedAndStruckMathUseTheExistingFourTextChannels()throws Exception {
        String active=math(fraction(run("1"),run("2"))),deleted=math(fraction(run("3"),run("4"))),struck=math(fraction(run("5"),run("6")));
        byte[] bytes=document(active+"<w:del w:id='1'>"+deleted+"</w:del><w:r><w:rPr><w:strike/></w:rPr>"+struck+"</w:r>");
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed=parser(ocr).parse("revision-math.docx",bytes);DocumentBlock block=parsed.getBlocks().get(0);
        assertEquals("(1)/(2)",parsed.getText());assertEquals("(1)/(2)(3)/(4)(5)/(6)",block.getOriginalText());
        assertEquals("(3)/(4)",block.getDeletedText());assertEquals("(5)/(6)",block.getStrikeText());assertEquals(3,objects(parsed).size());
        assertTrue(objects(parsed).get(1).path("deleted").asBoolean());assertTrue(objects(parsed).get(2).path("struck").asBoolean());verifyNoInteractions(ocr);
    }

    @Test void tableAndHeaderStoriesShareTheSameNativeMathProjection()throws Exception {
        byte[] bytes;try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            word.createTable(1,1).getRow(0).getCell(0).getParagraphs().get(0).getCTP().set(paragraph(math(fraction(run("7"),run("8")))));
            word.createHeader(HeaderFooterType.DEFAULT).createParagraph().getCTP().set(paragraph(math(run("H="))));word.write(out);bytes=out.toByteArray();
        }
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed=parser(ocr).parse("story-math.docx",bytes);
        assertTrue(parsed.getText().contains("(7)/(8)"));assertTrue(parsed.getText().contains("H="));assertEquals(2,objects(parsed).size());
        DocumentBlock row=parsed.getBlocks().stream().filter(block->"table_row".equals(block.getKind())).findFirst().orElseThrow();
        assertEquals("(7)/(8)",row.getCells().get(0));assertEquals("(7)/(8)",row.getWordCells().get(0).getText());
        for(JsonNode object:objects(parsed))assertEquals("native_ooxml_projected",object.path("status").asText());verifyNoInteractions(ocr);
    }

    @Test void observeRouteAndNormalParseUseTheSharedProjectionWithoutOcr()throws Exception {
        byte[] bytes;try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            word.createParagraph().createRun().setText("SPECIAL CONDITIONS OF CONTRACT");word.createParagraph().createRun().setText("Contract No: TEST/2046/02");
            word.createParagraph().getCTP().set(paragraph(math(fraction(run("9"),run("10")))));word.write(out);bytes=out.toByteArray();
        }
        OcrClient ocr=mock(OcrClient.class);DocumentParser parser=parser(ocr);DocumentParser.ParsedDocument observed=parser.observe("renamed.docx",bytes);
        assertTrue(observed.getText().contains("(9)/(10)"));OerDocumentIdentity identity=new OerSourceRouting().route("renamed.docx",DocumentParseProbe.sha256(bytes),observed);
        assertEquals("SCC",identity.getKind());DocumentParser.ParsedDocument parsed=parser.parse("renamed.docx",bytes,observed,identity);
        assertTrue(parsed.getText().contains("(9)/(10)"));assertEquals(observed.getText(),parsed.getText());projected(objects(parsed).get(0),"(9)/(10)");
        assertEquals(0,parsed.getCoverage().getOcrPages());verifyNoInteractions(ocr);
    }

    @Test void legacyMathKeepsItsExactHistoricalTextAndPerNodeMetadata()throws Exception {
        byte[] bytes=document("<w:r><w:t>Rate </w:t></w:r>"+math(fraction(run("1"),run("2")))+"<w:r><w:t> applies.</w:t></w:r>");
        OcrClient ocr=mock(OcrClient.class);DocumentParser parser=parser(ocr);DocumentParser.ParsedDocument legacy=parser.projectLegacyDocxMath(bytes);
        assertEquals("Rate 12 applies.",legacy.getText());assertEquals(8,objects(legacy).size());
        for(JsonNode object:objects(legacy)) {
            assertEquals("native_ooxml_unrendered",object.path("status").asText());assertEquals("unknown",object.path("semanticStatus").asText());
            assertFalse(object.has("mathProjection"));assertTrue(object.has("nativeXml"));
        }
        assertEquals(8,legacy.getBlocks().get(0).getWordWarnings().size());assertFalse(legacy.getCoverage().isComplete());verifyNoInteractions(ocr);
    }
}
