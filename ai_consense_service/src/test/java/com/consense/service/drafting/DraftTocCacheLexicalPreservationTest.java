package com.consense.service.drafting;
import com.consense.document.DocxTemplateEditor.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftNativeHeadingBoundariesTest.*;

/** Cache-only editing preserves another serializer's exact XML outside verified numeric text slots. */
class DraftTocCacheLexicalPreservationTest {
    @Test void keepsDeclarationWhitespaceAttributeOrderAndLiteralLineBreaks()throws Exception {
        String input=xml(fixture()).replace("?><w:document","?>\r\n<w:document").replace("<w:document xmlns:w=","<w:document z=\"last\" a=\"first\" xmlns:w=").replace("Authored retained body 0","Line one\nLine two");byte[] before=packageXml(input);Map<String,String> measured=new LinkedHashMap<>();for(TocPageReference r:EDITOR.tocPageReferences(before))measured.put(r.getTargetBookmark(),"3");
        DocxEditResult result=EDITOR.updateTocPageCaches(before,DraftPdfConverter.sha256(before),measured);String after=xml(result.getDocxBytes());assertEquals(input.replace("<w:t>2</w:t>","<w:t>3</w:t>"),after);unchangedPartsExceptDocument(before,result.getDocxBytes());assertEquals(features(before),features(result.getDocxBytes()));assertTrue(EDITOR.tocPageReferences(result.getDocxBytes()).stream().allMatch(r->"3".equals(r.getCachedPage())));
        Map<String,String> same=new LinkedHashMap<>();for(TocPageReference r:EDITOR.tocPageReferences(before))same.put(r.getTargetBookmark(),r.getCachedPage());assertEquals(input,xml(EDITOR.updateTocPageCaches(before,DraftPdfConverter.sha256(before),same).getDocxBytes()));
    }
    @Test void xmlCommentsAndCdataCannotSpoofTheNativeTextSlotOrder()throws Exception {
        for(String extra:Arrays.asList("<!--<w:t>2</w:t>-->","<w:r><w:t><![CDATA[<w:t>2</w:t>]]></w:t></w:r>")){
            byte[] before=packageXml(xml(fixture()).replaceFirst("<w:body>","<w:body>"+extra));Map<String,String> measured=new LinkedHashMap<>();for(TocPageReference r:EDITOR.tocPageReferences(before))measured.put(r.getTargetBookmark(),"3");
            try{DocxEditResult result=EDITOR.updateTocPageCaches(before,DraftPdfConverter.sha256(before),measured);String after=xml(result.getDocxBytes());assertTrue(after.contains(extra));assertEquals(xml(before).replace(extra,"").replace("<w:t>2</w:t>","<w:t>3</w:t>"),after.replace(extra,""));assertTrue(EDITOR.tocPageReferences(result.getDocxBytes()).stream().allMatch(r->"3".equals(r.getCachedPage())));}catch(EditException unsafe){assertEquals("TOC_CACHE_XML_NOT_REVERSIBLE",unsafe.getCode());}
        }
    }
}
