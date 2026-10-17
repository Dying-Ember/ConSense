package com.consense.document;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.NumberingDocument;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.StylesDocument;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.*;

class DocxNumberingDirectedTest {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    @Test void repeatedLatinBoundaryExamplesComeFromEcmaNotExcelColumns() throws Exception {
        long[] values = {26,27,28,52,53};
        String[] lower = {"z","aa","bb","zz","aaa"}, upper = {"Z","AA","BB","ZZ","AAA"};
        for (int i=0; i<values.length; i++) for (String format : new String[]{"lowerLetter","upperLetter"}) {
            try (XWPFDocument d=document(level(0,values[i],format,"(%1)",""),"")) {
                DocxNumberingResolver.NumberingInfo result=resolve(new DocxNumberingResolver(d),d,0);
                assertTrue(result.isResolved(),result.getReason());
                assertEquals("("+("lowerLetter".equals(format)?lower[i]:upper[i])+")",result.getLabel());
                assertEquals(values[i],result.getValue());
            }
        }
    }

    @Test void overrideRestartParentPlaceholdersStoriesAndRereadsKeepNativeCounters() throws Exception {
        String levels=level(0,1,"decimal","%1.","")+level(1,27,"lowerLetter","%1(%2)","");
        String override="<w:lvlOverride w:ilvl='1'><w:startOverride w:val='28'/></w:lvlOverride>";
        try (XWPFDocument d=document(levels,override)) {
            DocxNumberingResolver r=new DocxNumberingResolver(d);
            assertEquals("1.",resolve(r,d,0).getLabel()); assertEquals("1(bb)",resolve(r,d,1).getLabel());
            XWPFParagraph p=paragraph(d,1); DocxNumberingResolver.NumberingInfo cc=r.resolve(p.getCTP().getDomNode());
            assertEquals("1(cc)",cc.getLabel()); assertSame(cc,r.resolve(p.getCTP().getDomNode()));
            assertEquals("2.",resolve(r,d,0).getLabel()); assertEquals("2(bb)",resolve(r,d,1).getLabel());
            assertEquals("1(bb)",resolve(r.newStory(),d,1).getLabel());
        }
    }

    @Test void romanThousandsContinuePast3999AsSpecifiedByEcma() throws Exception {
        for (String format:new String[]{"upperRoman","lowerRoman"}) try (XWPFDocument d=document(level(0,3999,format,"%1.",""),"")) {
            DocxNumberingResolver r=new DocxNumberingResolver(d);
            assertEquals("upperRoman".equals(format)?"MMMCMXCIX.":"mmmcmxcix.",resolve(r,d,0).getLabel());
            assertEquals("upperRoman".equals(format)?"MMMM.":"mmmm.",resolve(r,d,0).getLabel());
            assertEquals("upperRoman".equals(format)?"MMMMI.":"mmmmi.",resolve(r,d,0).getLabel());
        }
    }

    @Test void acyclicStyleInheritanceCanExceed64Styles() throws Exception {
        try (XWPFDocument d=document(level(0,5,"decimal","%1.","<w:pStyle w:val='S0'/>"),"")) {
            StringBuilder xml=new StringBuilder("<w:styles xmlns:w='"+W+"'>");
            xml.append("<w:style w:type='paragraph' w:styleId='S0'><w:pPr><w:numPr><w:numId w:val='14'/></w:numPr></w:pPr></w:style>");
            for(int i=1;i<=70;i++) xml.append("<w:style w:type='paragraph' w:styleId='S").append(i).append("'><w:basedOn w:val='S").append(i-1).append("'/></w:style>");
            xml.append("</w:styles>");d.createStyles().setStyles(StylesDocument.Factory.parse(xml.toString()).getStyles());
            XWPFParagraph p=d.createParagraph();p.setStyle("S70");
            DocxNumberingResolver.NumberingInfo result=new DocxNumberingResolver(d).resolve(p.getCTP().getDomNode());
            assertTrue(result.isResolved(),result.getReason());assertEquals("5.",result.getLabel());
        }
    }

    @Test void realStyleCycleStillRemainsExplicitlyUnresolved() throws Exception {
        try (XWPFDocument d=document(level(0,1,"decimal","%1.","<w:pStyle w:val='A'/>"),"")) {
            d.createStyles().setStyles(StylesDocument.Factory.parse("<w:styles xmlns:w='"+W+"'><w:style w:type='paragraph' w:styleId='A'><w:basedOn w:val='B'/><w:pPr><w:numPr><w:numId w:val='14'/></w:numPr></w:pPr></w:style><w:style w:type='paragraph' w:styleId='B'><w:basedOn w:val='A'/></w:style></w:styles>").getStyles());
            XWPFParagraph p=d.createParagraph();p.setStyle("A");
            DocxNumberingResolver.NumberingInfo result=new DocxNumberingResolver(d).resolve(p.getCTP().getDomNode());
            assertFalse(result.isResolved());assertEquals("style_inheritance_cycle",result.getReason());
        }
    }

    @Test void unrepresentableOutputLengthIsRejectedBeforeAllocation() throws Exception {
        for(String format:new String[]{"lowerLetter","upperRoman"}) try(XWPFDocument d=document(level(0,Long.MAX_VALUE,format,"%1.",""),"")) {
            DocxNumberingResolver.NumberingInfo result=resolve(new DocxNumberingResolver(d),d,0);
            assertFalse(result.isResolved());assertEquals("numbering_label_exceeds_string_capacity",result.getReason());assertEquals("",result.getLabel());
        }
    }

    @Test void negativeAndZeroDecimalStartsAndOverridesKeepNativeValues() throws Exception {
        try(XWPFDocument d=document(level(0,-2,"decimal","%1.",""),"")) {
            DocxNumberingResolver r=new DocxNumberingResolver(d);
            assertEquals("-2.",resolve(r,d,0).getLabel());assertEquals("-1.",resolve(r,d,0).getLabel());
            assertEquals("0.",resolve(r,d,0).getLabel());assertEquals("1.",resolve(r,d,0).getLabel());
        }
        try(XWPFDocument d=document(level(0,1,"decimal","%1.",""),"<w:lvlOverride w:ilvl='0'><w:startOverride w:val='-3'/></w:lvlOverride>")) {
            assertEquals("-3.",resolve(new DocxNumberingResolver(d),d,0).getLabel());
        }
    }

    @Test void styleLinkIsAFriendlyNameAndDoesNotDisableItsNativeLevel() throws Exception {
        for(boolean styleExists:new boolean[]{false,true}) try(XWPFDocument d=document("<w:styleLink w:val='NamedNumbering'/>"+level(0,28,"upperLetter","%1.",""),"")) {
            if(styleExists)d.createStyles().setStyles(StylesDocument.Factory.parse("<w:styles xmlns:w='"+W+"'><w:style w:type='numbering' w:styleId='NamedNumbering'><w:pPr><w:numPr><w:numId w:val='14'/></w:numPr></w:pPr></w:style></w:styles>").getStyles());
            DocxNumberingResolver.NumberingInfo result=resolve(new DocxNumberingResolver(d),d,0);
            assertTrue(result.isResolved(),result.getReason());assertEquals("BB.",result.getLabel());
        }
    }

    @Test void numStyleLinkRemainsAnExplicitSeparateUnimplementedReference() throws Exception {
        try(XWPFDocument d=document("<w:numStyleLink w:val='InheritedNumbering'/>"+level(0,1,"decimal","%1.",""),"")) {
            DocxNumberingResolver.NumberingInfo result=resolve(new DocxNumberingResolver(d),d,0);
            assertFalse(result.isResolved());assertEquals("numbering_style_reference_not_supported",result.getReason());assertEquals("",result.getLabel());
        }
    }

    @Test void earlierParentRestartsDescendantEvenWhenIntermediateLevelIsSkipped() throws Exception {
        String levels=level(0,1,"decimal","%1.","")+level(1,1,"decimal","%2.","")+level(2,1,"decimal","%3.","");
        try(XWPFDocument d=document(levels,"")) {
            DocxNumberingResolver r=new DocxNumberingResolver(d);
            assertEquals("1.",resolve(r,d,0).getLabel());assertEquals("1.",resolve(r,d,1).getLabel());
            assertEquals("1.",resolve(r,d,2).getLabel());assertEquals("2.",resolve(r,d,2).getLabel());
            assertEquals("2.",resolve(r,d,0).getLabel());assertEquals("1.",resolve(r,d,2).getLabel());
        }
    }

    @Test void forwardLevelPlaceholderIsIgnoredWithoutRejectingCurrentLabel() throws Exception {
        try(XWPFDocument d=document(level(0,1,"decimal","%1/%2",""),"")) {
            DocxNumberingResolver.NumberingInfo result=resolve(new DocxNumberingResolver(d),d,0);
            assertTrue(result.isResolved(),result.getReason());assertEquals("1/",result.getLabel());
        }
    }

    @Test void isolatedPercentSignsStayLiteralInLevelText() throws Exception {
        try(XWPFDocument d=document(level(0,1,"decimal","%1 at 50% or %",""),"")) {
            DocxNumberingResolver.NumberingInfo result=resolve(new DocxNumberingResolver(d),d,0);
            assertTrue(result.isResolved(),result.getReason());assertEquals("1 at 50% or %",result.getLabel());
        }
    }

    @Test void explicitNonemptyLevelTextTakesPriorityOverNullFlag() throws Exception {
        String nativeLevel=level(0,1,"decimal","%1.","").replace("<w:lvlText w:val='%1.'/>","<w:lvlText w:null='1' w:val='%1.'/>");
        try(XWPFDocument d=document(nativeLevel,"")) {
            DocxNumberingResolver.NumberingInfo result=resolve(new DocxNumberingResolver(d),d,0);
            assertTrue(result.isResolved(),result.getReason());assertEquals("1.",result.getLabel());
        }
    }

    private static XWPFParagraph paragraph(XWPFDocument d,int level) {XWPFParagraph p=d.createParagraph();p.setNumID(BigInteger.valueOf(14));p.setNumILvl(BigInteger.valueOf(level));return p;}
    private static DocxNumberingResolver.NumberingInfo resolve(DocxNumberingResolver r,XWPFDocument d,int level){return r.resolve(paragraph(d,level).getCTP().getDomNode());}
    private static String level(int i,long start,String format,String text,String extra){return "<w:lvl w:ilvl='"+i+"'><w:start w:val='"+start+"'/><w:numFmt w:val='"+format+"'/><w:lvlText w:val='"+text+"'/>"+extra+"</w:lvl>";}
    private static XWPFDocument document(String levels,String overrides)throws Exception {
        try(XWPFDocument d=new XWPFDocument()){
            d.createNumbering().setNumbering(NumberingDocument.Factory.parse("<w:numbering xmlns:w='"+W+"'><w:abstractNum w:abstractNumId='10'>"+levels+"</w:abstractNum><w:num w:numId='14'><w:abstractNumId w:val='10'/>"+overrides+"</w:num></w:numbering>").getNumbering());
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();d.write(bytes);return new XWPFDocument(new ByteArrayInputStream(bytes.toByteArray()));
        }
    }
}
