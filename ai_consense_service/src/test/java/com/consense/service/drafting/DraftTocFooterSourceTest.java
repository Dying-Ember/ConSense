package com.consense.service.drafting;

import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftNativeHeadingBoundariesTest.*;

/** Authored PDF replay verifies the footer discrimination contract, not source-layout acceptance. */
class DraftTocFooterSourceTest {
    private static final DocxTemplateEditor EDITOR=new DocxTemplateEditor();
    private static String field(String instruction,String cache){return "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText> "+instruction+" </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>"+text(cache)+"<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>";}
    private static byte[] fixture(String literal)throws Exception {
        String document="<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?><w:document xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                +"<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText> TOC \\o \"1-2\" \\h </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r></w:p>"
                +"<w:p>"+marker(1,"CSFooterRow")+end(1)+"<w:hyperlink w:anchor=\"_TocFooter\">"+text("Clause .... ")+field("PAGEREF _TocFooter \\h","1")+"</w:hyperlink></w:p>"
                +"<w:p>"+marker(2,"CSFooterAfter")+end(2)+"<w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>"
                +"<w:p>"+marker(3,"_TocFooter")+text("Heading")+end(3)+"</w:p><w:sectPr><w:footerReference r:id=\"rIdFooter\" w:type=\"default\"/><w:pgNumType w:start=\"1\"/></w:sectPr></w:body></w:document>";
        javax.xml.parsers.DocumentBuilderFactory factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);ByteArrayOutputStream normalized=new ByteArrayOutputStream();javax.xml.transform.TransformerFactory.newInstance().newTransformer().transform(new javax.xml.transform.dom.DOMSource(factory.newDocumentBuilder().parse(new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)))),new javax.xml.transform.stream.StreamResult(normalized));
        Map<String,byte[]> parts=parts(packageXml(normalized.toString("UTF-8")));parts.put("word/footer1.xml",("<w:ftr xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:p>"+field("PAGE \\* MERGEFORMAT","77")+"</w:p><w:p>"+text(literal)+"</w:p></w:ftr>").getBytes(StandardCharsets.UTF_8));
        parts.put("word/_rels/document.xml.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rIdFooter\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer\" Target=\"footer1.xml\"/></Relationships>".getBytes(StandardCharsets.UTF_8));
        parts.put("[Content_Types].xml",new String(parts.get("[Content_Types].xml"),StandardCharsets.UTF_8).replace("</Types>","<Override PartName=\"/word/footer1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml\"/></Types>").getBytes(StandardCharsets.UTF_8));return zip(parts);
    }
    private static byte[] replacePart(byte[] input,String part,String old,String replacement)throws Exception {Map<String,byte[]> parts=parts(input);parts.put(part,new String(parts.get(part),StandardCharsets.UTF_8).replace(old,replacement).getBytes(StandardCharsets.UTF_8));return zip(parts);}
    private static DraftTocFinalizer.Result finalize(byte[] source,String[] literals,boolean splitLiteral) {
        return DraftTocFinalizer.finish(source,input->{try {
            byte[] pdf;try(PDDocument document=new PDDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                document.addPage(new PDPage(PDRectangle.A4));document.addPage(new PDPage(PDRectangle.A4));COSDictionary destinations=new COSDictionary();
                for(String name:Arrays.asList("_TocFooter","CSFooterRow","CSFooterAfter")){PDPageXYZDestination point=new PDPageXYZDestination();point.setPage(document.getPage(name.equals("_TocFooter")?1:0));point.setLeft(72);point.setTop(name.equals("_TocFooter")?600:name.equals("CSFooterRow")?704:680);destinations.setItem(COSName.getPDFName(name),point.getCOSObject());}document.getDocumentCatalog().getCOSObject().setItem(COSName.DESTS,destinations);
                try(PDPageContentStream content=new PDPageContentStream(document,document.getPage(0))){DraftTocFinalizerDynamicHeadingTest.line(content,72,700,10,"Clause .... "+EDITOR.tocPageReferences(input).get(0).getCachedPage());}
                try(PDPageContentStream content=new PDPageContentStream(document,document.getPage(1))){DraftTocFinalizerDynamicHeadingTest.line(content,518,48,10,"2");for(int i=0;i<literals.length;i++)DraftTocFinalizerDynamicHeadingTest.line(content,72+i*100,splitLiteral&&i>0?22:32,10,literals[i]);}
                document.save(out);pdf=out.toByteArray();
            }Map<String,Object> manifest=new LinkedHashMap<>();manifest.put("docxSha256",DraftPdfConverter.sha256(input));manifest.put("pdfSha256",DraftPdfConverter.sha256(pdf));manifest.put("renderProfileHash","authored-source-footer-replay");return new DraftPdfConversionResult(pdf,manifest);
        }catch(IOException failure){throw new UncheckedIOException(failure);}});
    }
    @Test void sourceBoundContractNumberIsNotASecondPageNumber()throws Exception {
        byte[] source=fixture("20250101");TocFooterPageSource footer=EDITOR.tocFooterPageSources(source,DraftPdfConverter.sha256(source)).get("_TocFooter");assertEquals(List.of("20250101"),footer.getLiterals());assertEquals("word/footer1.xml",footer.getPart());assertEquals(DraftPdfConverter.sha256(source),footer.getSourceSha256());
        DraftTocFinalizer.Result result=finalize(source,new String[]{"20250101"},false);assertEquals("PASS",result.proof.get("status"),String.valueOf(result.proof));assertEquals("2",EDITOR.tocPageReferences(result.docx).get(0).getCachedPage());assertArrayEquals(source,result.compiled);assertEquals(features(source),features(result.docx));unchangedPartsExceptDocument(source,result.docx);
    }
    @Test void missingDuplicatedPartialAndExtraTokensRemainUnknown()throws Exception {
        byte[] source=fixture("20250101");for(String[] tokens:new String[][]{{},{"20250101","20250101"},{"120250101"},{"20250101","7"}}){DraftTocFinalizer.Result result=finalize(source,tokens,false);assertEquals("UNKNOWN",result.proof.get("status"),String.valueOf(result.proof));assertArrayEquals(source,result.docx);assertEquals(1,result.proof.get("nativeConversionsAttempted"));assertTrue(String.valueOf(result.proof.get("reason")).startsWith("TOC_FOOTER_"));}
    }
    @Test void literalNumbersWithSpacesMustMatchWholeTokensOnOneRenderedLine()throws Exception {
        byte[] source=fixture("2025 0101");assertEquals("PASS",finalize(source,new String[]{"2025 0101"},false).proof.get("status"));assertEquals("UNKNOWN",finalize(source,new String[]{"2025","0101"},true).proof.get("status"));assertEquals("UNKNOWN",finalize(source,new String[]{"20250101"},false).proof.get("status"));
    }
    @Test void multiplePageFieldsVariantsUnmappedSectionsAndWrongHashStayRejected()throws Exception {
        byte[] source=fixture("20250101");Map<byte[],String> cases=new LinkedHashMap<>();cases.put(replacePart(source,"word/footer1.xml","</w:ftr>","<w:p>"+field("PAGE","77")+"</w:p></w:ftr>"),"TOC_FOOTER_PAGE_FIELD_UNSUPPORTED");cases.put(replacePart(source,"word/document.xml","<w:sectPr>","<w:sectPr><w:titlePg/>"),"TOC_FOOTER_VARIANT_UNSUPPORTED");cases.put(replacePart(source,"word/document.xml","w:type=\"default\"","w:type=\"even\""),"TOC_FOOTER_VARIANT_UNSUPPORTED");cases.put(replacePart(source,"word/document.xml","<w:sectPr><w:footerReference r:id=\"rIdFooter\" w:type=\"default\"/><w:pgNumType w:start=\"1\"/></w:sectPr>",""),"TOC_FOOTER_SECTION_UNMAPPED");
        for(Map.Entry<byte[],String> entry:cases.entrySet()){EditException failure=assertThrows(EditException.class,()->EDITOR.tocFooterPageSources(entry.getKey(),DraftPdfConverter.sha256(entry.getKey())));assertEquals(entry.getValue(),failure.getCode());}assertThrows(EditException.class,()->EDITOR.tocFooterPageSources(source,"wrong"));
    }
}
