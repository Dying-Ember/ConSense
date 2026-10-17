package com.consense.document;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.ocr.OcrClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.NumberingDocument;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.*;

class DocxFontNumberingTest {
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String FONT="<w:rFonts w:ascii='Wingdings' w:hAnsi='Wingdings'/>";
    private static final String SOURCE="https://www.unicode.org/L2/L2011/11344-wingdings.pdf#page=25";

    @Test void verifiedWingdingsCircleUses26AbAndRetainsRawAliases() {
        for(String code:new String[]{"006c","F06C"}) {
            WordSymbolDecoder.Symbol symbol=WordSymbolDecoder.decode(" Wingdings ",code);
            assertTrue(symbol.isResolved());assertEquals("\u26AB",symbol.getDecodedText());
            assertEquals(" Wingdings ",symbol.getFont());assertEquals(code,symbol.getHexCode());assertEquals(SOURCE,symbol.getMappingSource());
        }
        assertFalse(WordSymbolDecoder.decode("Symbol","F06C").isResolved());
        assertFalse(WordSymbolDecoder.decode("Wingdings 2","F06C").isResolved());
    }

    @Test void explicitNumberingFontDecodesMultiplePuaMarkersWithoutChangingLiteralTextOrRawProperties()throws Exception {
        String raw="前缀：\uF06C；\uF06C。";
        try(XWPFDocument d=document(raw,FONT)) {
            DocxNumberingResolver.NumberingInfo result=resolve(d);JsonNode info=JsonUtils.mapper().valueToTree(result);
            assertTrue(result.isResolved(),result.getReason());assertEquals("前缀：\u26AB；\u26AB。",result.getLabel());
            assertEquals(raw,result.getLevelText());assertEquals(2,info.path("fontSymbols").size());
            for(JsonNode symbol:info.path("fontSymbols")){assertEquals("Wingdings",symbol.path("font").asText());assertEquals("F06C",symbol.path("hexCode").asText());assertEquals(SOURCE,symbol.path("mappingSource").asText());}
            assertEquals("Wingdings",info.path("fontAttributes").path("ascii").asText());assertEquals("Wingdings",info.path("fontAttributes").path("hAnsi").asText());
        }
    }

    @Test void oneUnknownMarkerKeepsWholeLabelUnresolvedWhilePreservingEveryRawMarker()throws Exception {
        String raw="前缀：\uF06C；\uF06D。";
        try(XWPFDocument d=document(raw,FONT)) {
            DocxNumberingResolver.NumberingInfo result=resolve(d);JsonNode info=JsonUtils.mapper().valueToTree(result);
            assertFalse(result.isResolved());assertEquals("",result.getLabel());assertEquals(raw,result.getLevelText());
            assertEquals(2,info.path("fontSymbols").size());assertEquals("resolved",info.path("fontSymbols").get(0).path("resolutionStatus").asText());
            assertEquals("F06D",info.path("fontSymbols").get(1).path("hexCode").asText());
            assertEquals("unresolved_unsupported_character_code",info.path("fontSymbols").get(1).path("resolutionStatus").asText());
        }
    }

    @Test void missingThemeOnlyConflictingAndUnknownFontsNeverUseAnInferredFallback()throws Exception {
        for(String fonts:new String[]{"","<w:rFonts w:asciiTheme='minorHAnsi'/>","<w:rFonts w:ascii='Wingdings' w:hAnsi='Symbol'/>","<w:rFonts w:ascii='Unknown Symbol Font'/>"}) {
            try(XWPFDocument d=document("\uF06C",fonts)) {
                DocxNumberingResolver.NumberingInfo result=resolve(d);JsonNode info=JsonUtils.mapper().valueToTree(result);
                assertFalse(result.isResolved());assertEquals("",result.getLabel());assertEquals("\uF06C",result.getLevelText());
                assertEquals("F06C",info.path("fontSymbols").get(0).path("hexCode").asText());
                assertTrue(info.path("fontSymbols").get(0).path("mappingSource").isNull());
            }
        }
    }

    @Test void explicitFontsWithThemeOverridesRemainUnknownAndRetainAllAttributes()throws Exception {
        for(String theme:new String[]{"asciiTheme","hAnsiTheme","eastAsiaTheme","cstheme"}) {
            String fonts="<w:rFonts w:ascii='Wingdings' w:hAnsi='Wingdings' w:eastAsia='Wingdings' w:cs='Wingdings' w:"+theme+"='minorHAnsi'/>";
            try(XWPFDocument d=document("\uF06C \uF06C",fonts)) {
                DocxNumberingResolver.NumberingInfo result=resolve(d);JsonNode info=JsonUtils.mapper().valueToTree(result);
                assertFalse(result.isResolved());assertEquals("",result.getLabel());assertEquals("\uF06C \uF06C",result.getLevelText());
                assertEquals(5,info.path("fontAttributes").size());assertEquals("minorHAnsi",info.path("fontAttributes").path(theme).asText());
                for(String slot:new String[]{"ascii","hAnsi","eastAsia","cs"})assertEquals("Wingdings",info.path("fontAttributes").path(slot).asText());
                assertEquals(2,info.path("fontSymbols").size());
                for(JsonNode symbol:info.path("fontSymbols")) {
                    assertEquals("Wingdings",symbol.path("font").asText());assertEquals("F06C",symbol.path("hexCode").asText());
                    assertEquals("unresolved_numbering_theme_font",symbol.path("resolutionStatus").asText());
                    assertEquals("",symbol.path("decodedText").asText());assertTrue(symbol.path("mappingSource").isNull());
                }
            }
        }
    }

    @Test void nativeSingleByteAliasUsesTheSameMappingAndUnknownByteIsNotCast()throws Exception {
        try(XWPFDocument d=document("l",FONT)) {
            DocxNumberingResolver.NumberingInfo result=resolve(d);JsonNode info=JsonUtils.mapper().valueToTree(result);
            assertTrue(result.isResolved());assertEquals("\u26AB",result.getLabel());assertEquals("l",result.getLevelText());
            assertEquals("006C",info.path("fontSymbols").get(0).path("hexCode").asText());
        }
        try(XWPFDocument d=document("v",FONT)){assertFalse(resolve(d).isResolved());}
    }

    @Test void repeatedSingleByteFontCodesAreNotRejectedByAnArtificialOneCharacterGate()throws Exception {
        for(String raw:new String[]{"ll","l l","\uF06C \uF06C"})try(XWPFDocument d=document(raw,FONT)) {
            DocxNumberingResolver.NumberingInfo result=resolve(d);JsonNode info=JsonUtils.mapper().valueToTree(result);
            assertTrue(result.isResolved(),result.getReason());assertEquals(raw.contains(" ")?"\u26AB \u26AB":"\u26AB\u26AB",result.getLabel());assertEquals(raw,result.getLevelText());
            assertEquals(2,info.path("fontSymbols").size());
            for(JsonNode symbol:info.path("fontSymbols")){assertEquals(raw.startsWith("l")?"006C":"F06C",symbol.path("hexCode").asText());assertEquals(SOURCE,symbol.path("mappingSource").asText());}
        }
        try(XWPFDocument d=document("lv",FONT)) {
            DocxNumberingResolver.NumberingInfo result=resolve(d);JsonNode info=JsonUtils.mapper().valueToTree(result);
            assertFalse(result.isResolved());assertEquals("",result.getLabel());assertEquals("lv",result.getLevelText());
            assertEquals(2,info.path("fontSymbols").size());assertEquals("0076",info.path("fontSymbols").get(1).path("hexCode").asText());
        }
    }

    @Test void normalParserKeepsNumberingFontProvenanceApartFromBodySymbolsAndRespectsDeletedAndStruckText()throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(XWPFDocument d=document("\uF06C",FONT)) {
            d.getParagraphs().get(0).createRun().setText("kept");
            XWPFParagraph struck=d.createParagraph();struck.setNumID(BigInteger.valueOf(14));struck.setNumILvl(BigInteger.ZERO);
            struck.createRun().setText("struck");struck.getRuns().get(0).setStrikeThrough(true);
            XWPFParagraph deleted=d.createParagraph();deleted.getCTP().set(CTP.Factory.parse("<w:p xmlns:w='"+W+"'><w:pPr><w:numPr><w:ilvl w:val='0'/><w:numId w:val='14'/></w:numPr></w:pPr><w:del w:id='1' w:author='fixture'><w:r><w:delText>removed</w:delText></w:r></w:del></w:p>"));
            XWPFParagraph actualSymbol=d.createParagraph();actualSymbol.getCTP().set(CTP.Factory.parse("<w:p xmlns:w='"+W+"'><w:r><w:t>tick</w:t><w:sym w:font='Wingdings' w:char='F0FC'/></w:r></w:p>"));
            d.write(bytes);
        }
        ConsenseProperties properties=new ConsenseProperties();properties.getOcr().setEnabled(false);
        OcrClient blocked=(OcrClient)Proxy.newProxyInstance(OcrClient.class.getClassLoader(),new Class<?>[]{OcrClient.class},(proxy,method,args)->{throw new AssertionError("Unexpected OCR "+method.getName());});
        DocumentParser.ParsedDocument parsed=new DocumentParser(properties,blocked).parse("numbering-font.docx",bytes.toByteArray());
        assertTrue(parsed.getText().contains("\u26AB kept"));assertFalse(parsed.getText().contains("struck"));assertFalse(parsed.getText().contains("removed"));
        int bodySymbols=0,numberingSymbols=0;
        for(DocumentBlock block:parsed.getBlocks()) {
            bodySymbols+=block.getWordSymbols().size();JsonNode tree=JsonUtils.mapper().valueToTree(block);
            for(JsonNode numbering:tree.path("wordNumbering")) {
                JsonNode symbol=numbering.path("fontSymbols").get(0);numberingSymbols++;
                assertEquals("Wingdings",symbol.path("font").asText());assertEquals("F06C",symbol.path("hexCode").asText());assertEquals("\u26AB",symbol.path("decodedText").asText());assertEquals(SOURCE,symbol.path("mappingSource").asText());
                assertEquals("\uF06C",numbering.path("levelText").asText());assertEquals("Wingdings",numbering.path("fontAttributes").path("ascii").asText());
                assertTrue(symbol.path("location").asText().contains("/numbering/font-symbol/"));
                if(block.getOriginalText().contains("struck")){assertTrue(symbol.path("struck").asBoolean());assertFalse(numbering.path("appliedToEffectiveText").asBoolean());}
                if(block.getOriginalText().contains("removed")){assertTrue(symbol.path("deleted").asBoolean());assertFalse(numbering.path("appliedToEffectiveText").asBoolean());}
            }
        }
        assertEquals(1,bodySymbols,"Only the real body w:sym belongs to the old symbol inventory");assertEquals(3,numberingSymbols);
    }

    @Test void normalParserKeepsUnknownNumberingFontAndCodeWithoutAResolvedLabel()throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(XWPFDocument d=document("\uF06C","<w:rFonts w:ascii='Unknown Symbol Font'/>")){d.getParagraphs().get(0).createRun().setText("unknown");d.write(bytes);}
        ConsenseProperties properties=new ConsenseProperties();properties.getOcr().setEnabled(false);
        OcrClient blocked=(OcrClient)Proxy.newProxyInstance(OcrClient.class.getClassLoader(),new Class<?>[]{OcrClient.class},(proxy,method,args)->{throw new AssertionError("Unexpected OCR "+method.getName());});
        DocumentParser.ParsedDocument parsed=new DocumentParser(properties,blocked).parse("unknown-numbering-font.docx",bytes.toByteArray());
        JsonNode numbering=JsonUtils.mapper().valueToTree(parsed.getBlocks().get(0)).path("wordNumbering").get(0);
        assertEquals("unresolved",numbering.path("resolutionStatus").asText());assertEquals("",numbering.path("label").asText());
        assertEquals("Unknown Symbol Font",numbering.path("fontSymbols").get(0).path("font").asText());assertEquals("F06C",numbering.path("fontSymbols").get(0).path("hexCode").asText());
        assertEquals("unresolved_unsupported_font",numbering.path("fontSymbols").get(0).path("resolutionStatus").asText());
        assertTrue(parsed.getText().contains("[unresolved Word numbering]"));assertEquals(0,parsed.getBlocks().get(0).getWordSymbols().size());
    }

    private static DocxNumberingResolver.NumberingInfo resolve(XWPFDocument d){return new DocxNumberingResolver(d).resolve(d.getParagraphs().get(0).getCTP().getDomNode());}
    private static XWPFDocument document(String text,String fonts)throws Exception {
        try(XWPFDocument created=new XWPFDocument()) {
            created.createNumbering().setNumbering(NumberingDocument.Factory.parse("<w:numbering xmlns:w='"+W+"'><w:abstractNum w:abstractNumId='10'><w:lvl w:ilvl='0'><w:start w:val='1'/><w:numFmt w:val='bullet'/><w:lvlText w:val='"+text+"'/><w:rPr>"+fonts+"</w:rPr></w:lvl></w:abstractNum><w:num w:numId='14'><w:abstractNumId w:val='10'/></w:num></w:numbering>").getNumbering());
            XWPFParagraph p=created.createParagraph();p.setNumID(BigInteger.valueOf(14));p.setNumILvl(BigInteger.ZERO);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();created.write(bytes);return new XWPFDocument(new ByteArrayInputStream(bytes.toByteArray()));
        }
    }
}
