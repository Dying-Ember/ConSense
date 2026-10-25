package com.consense.service.drafting;

import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import java.io.*;
import java.nio.file.*;
import com.consense.domain.DraftPdfArtifact;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static com.consense.service.drafting.DraftNativeHeadingBoundariesTest.*;

class DraftTocTitleCacheTest {
    private static final DocxTemplateEditor EDITOR=new DocxTemplateEditor();
    private static byte[] source() throws Exception {
        String begin="<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>",separate="<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>",end="<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>";
        return packageXml("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                +"<w:p>"+begin+"<w:r><w:instrText> TOC \\o \"1-2\" \\h </w:instrText></w:r>"+separate+"</w:p>"
                +"<w:p><w:hyperlink w:anchor=\"_TocTitle\"><w:r><w:t>SCC1.301</w:t></w:r><w:r><w:tab/></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>Old cached title</w:t></w:r><w:r><w:tab/></w:r>"+begin+"<w:r><w:instrText> PAGEREF _TocTitle \\h </w:instrText></w:r>"+separate+"<w:r><w:t>12</w:t></w:r>"+end+"</w:hyperlink></w:p>"
                +"<w:p>"+end+"</w:p><w:p><w:bookmarkStart w:id=\"7\" w:name=\"_TocTitle\"/><w:r><w:t>SCC1.301</w:t><w:tab/><w:t>Current adopted title</w:t></w:r><w:bookmarkEnd w:id=\"7\"/></w:p><w:sectPr/></w:body></w:document>");
    }

    @Test void exactTitleOnlyChangePreservesFieldsPagesBookmarksFormattingAndOtherParts() throws Exception {
        byte[] original=source();TocTitleCacheUpdate update=EDITOR.updateTocTitleCaches(original,DraftPdfConverter.sha256(original));byte[] changed=update.getResult().getDocxBytes();
        assertEquals(1,update.getTargetCount());assertEquals(1,update.getChanges().size());assertTrue(update.getUnknown().isEmpty());
        assertEquals(xml(original).replace("Old cached title","Current adopted title"),xml(changed));assertEquals(features(original),features(changed));unchangedPartsExceptDocument(original,changed);
        assertEquals("12",EDITOR.tocPageReferences(changed).get(0).getCachedPage());
        assertArrayEquals(changed,EDITOR.updateTocTitleCaches(changed,DraftPdfConverter.sha256(changed)).getResult().getDocxBytes(),"Already synchronized caches must be byte-idempotent");
    }

    @Test void incompleteHeadingDifferentNumberAndSplitFormattingStayUnchangedWithReasons() throws Exception {
        String original=xml(source());Map<String,String> cases=new LinkedHashMap<>();
        cases.put(original.replace("<w:bookmarkStart w:id=\"7\"",text("Outside bookmark")+"<w:bookmarkStart w:id=\"7\""),"TOC_TITLE_HEADING_RANGE_INCOMPLETE");
        cases.put(original.replace("<w:t>SCC1.301</w:t></w:r>","<w:t>SCC9.999</w:t></w:r>"),"TOC_TITLE_LABEL_MISMATCH");
        cases.put(original.replace("<w:t>Old cached title</w:t>","<w:t>Old cached</w:t><w:t> title</w:t>"),"TOC_TITLE_FORMAT_SCOPE_UNSUPPORTED");
        for(Map.Entry<String,String> entry:cases.entrySet()){byte[] bytes=packageXml(entry.getKey());TocTitleCacheUpdate update=EDITOR.updateTocTitleCaches(bytes,DraftPdfConverter.sha256(bytes));assertArrayEquals(bytes,update.getResult().getDocxBytes());assertTrue(update.getChanges().isEmpty());assertEquals(entry.getValue(),update.getUnknown().get(0).get("reason"));}
    }

    @Test void duplicateTargetsAndWrongSourceHashRemainRejectedWithoutOrdinaryFieldEditing() throws Exception {
        byte[] original=source();assertThrows(EditException.class,()->EDITOR.updateTocTitleCaches(original,"wrong"));
        byte[] duplicate=packageXml(xml(original).replace("<w:bookmarkStart w:id=\"7\"",marker(8,"_TocTitle")+end(8)+"<w:bookmarkStart w:id=\"7\""));
        assertThrows(EditException.class,()->EDITOR.updateTocTitleCaches(duplicate,DraftPdfConverter.sha256(duplicate)));
        Paragraph row=EDITOR.inspect(original).mainParagraph(2);assertEquals("FIELD_TEXT_EDIT_UNSUPPORTED",DraftFormattedRules.blockUnsupportedReason(EDITOR.inspect(original),row));
        assertThrows(EditException.class,()->EDITOR.apply(original,new SourceEditBatch(DraftPdfConverter.sha256(original),List.of(Edit.clearParagraph("still-protected",row)))));
    }

    @Test void unconfiguredOrFailedPaginationKeepsSafeTitlesAndReportsPaginationUnknown() throws Exception {
        byte[] original=source();DraftTocFinalizer.Result unconfigured=DraftTocFinalizer.unchanged(original,"TEST_RENDERER_UNAVAILABLE");
        assertArrayEquals(original,unconfigured.compiled);assertEquals("PASS",unconfigured.proof.get("titleCacheStatus"));assertEquals("UNKNOWN",unconfigured.proof.get("status"));assertTrue(xml(unconfigured.docx).contains("Current adopted title"));
        DraftTocFinalizer.Result failed=DraftTocFinalizer.finish(original,bytes->{throw new IllegalStateException("TEST_RENDER_FAILED");});
        assertArrayEquals(unconfigured.docx,failed.docx);assertArrayEquals(original,failed.compiled);assertEquals("TEST_RENDER_FAILED",failed.proof.get("reason"));assertEquals("PASS",failed.proof.get("titleCacheStatus"));assertNull(failed.pdf);
    }

    @Test void immutableCacheConflictAfterTitleAndPageChangesKeepsTheFirstTitledPair() throws Exception {
        String row=marker(8,"CSTitleRow")+end(8),after=marker(9,"CSTitleAfter")+end(9);
        byte[] original=packageXml(xml(source()).replace("<w:t>12</w:t>","<w:t>1</w:t>").replace("<w:p><w:hyperlink","<w:p>"+row+"<w:hyperlink").replace("<w:p><w:r><w:fldChar w:fldCharType=\"end\"/>","<w:p>"+after+"<w:r><w:fldChar w:fldCharType=\"end\"/>"));
        List<byte[]> rendered=new ArrayList<>();DraftTocFinalizer.Result finalized=DraftTocFinalizer.finish(original,input->{
            try {
                rendered.add(input.clone());byte[] pdf;
                try(PDDocument document=new PDDocument();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                    document.addPage(new PDPage(PDRectangle.A4));document.addPage(new PDPage(PDRectangle.A4));COSDictionary destinations=new COSDictionary();
                    for(String name:Arrays.asList("_TocTitle","CSTitleRow","CSTitleAfter")){PDPageXYZDestination point=new PDPageXYZDestination();point.setPage(document.getPage(name.equals("_TocTitle")?1:0));point.setLeft(72);point.setTop(name.equals("_TocTitle")?600:name.equals("CSTitleRow")?704:680);destinations.setItem(COSName.getPDFName(name),point.getCOSObject());}
                    document.getDocumentCatalog().getCOSObject().setItem(COSName.DESTS,destinations);
                    try(PDPageContentStream content=new PDPageContentStream(document,document.getPage(0))){DraftTocFinalizerDynamicHeadingTest.line(content,72,700,10,"Current adopted title .... "+EDITOR.tocPageReferences(input).get(0).getCachedPage());}
                    for(int page=0;page<2;page++)try(PDPageContentStream content=new PDPageContentStream(document,document.getPage(page),PDPageContentStream.AppendMode.APPEND,true)){DraftTocFinalizerDynamicHeadingTest.line(content,250,30,10,String.valueOf(page+1));}
                    document.save(bytes);pdf=bytes.toByteArray();
                }
                Map<String,Object> manifest=new LinkedHashMap<>();manifest.put("docxSha256",DraftPdfConverter.sha256(input));manifest.put("pdfSha256",DraftPdfConverter.sha256(pdf));manifest.put("renderProfileHash","authored-title-cache-conflict-replay");return new DraftPdfConversionResult(pdf,manifest);
            }catch(IOException failure){throw new UncheckedIOException(failure);}
        });
        assertEquals("PASS",finalized.proof.get("status"),String.valueOf(finalized.proof));assertEquals("PASS",finalized.proof.get("titleCacheStatus"));assertEquals(2,rendered.size());assertEquals("2",EDITOR.tocPageReferences(finalized.docx).get(0).getCachedPage());assertTrue(xml(rendered.get(0)).contains("Current adopted title"));
        DraftPdfArtifact conflicting=new DraftPdfArtifact();conflicting.setDocxSha256("wrong");conflicting.setRenderProfileHash(finalized.pdf.getRenderProfileHash());conflicting.setPdfBytes(finalized.pdf.getPdfBytes());conflicting.setPdfSha256(finalized.pdf.getPdfSha256());DraftTocFinalizer.Result rejected=finalized.useExisting(conflicting);
        assertEquals("UNKNOWN",rejected.proof.get("status"));assertEquals("TOC_EXISTING_CACHE_IDENTITY_MISMATCH",rejected.proof.get("reason"));assertEquals("PASS",rejected.proof.get("titleCacheStatus"));assertArrayEquals(original,rejected.compiled);assertArrayEquals(rendered.get(0),rejected.docx);assertEquals("1",EDITOR.tocPageReferences(rejected.docx).get(0).getCachedPage());assertArrayEquals(finalized.first.getPdfBytes(),rejected.pdf.getPdfBytes());assertEquals(DraftPdfConverter.sha256(rejected.docx),rejected.pdf.getDocxSha256());assertEquals(DraftPdfConverter.sha256(rejected.docx),rejected.proof.get("finalDocxSha256"));assertEquals(2,rendered.size());
    }

    @Test void actualSccWith24SavedBodyEditsChangesOnlyTheTwoResidualTocTitles() throws Exception {
        String input=System.getProperty("consense.acceptance.manualArtifact");assumeTrue(input!=null,"Explicit saved manual SCC artifact is required");
        byte[] original=Files.readAllBytes(Paths.get(input));TocTitleCacheUpdate update=EDITOR.updateTocTitleCaches(original,DraftPdfConverter.sha256(original));byte[] changed=update.getResult().getDocxBytes();
        assertEquals(92,update.getTargetCount());assertEquals(2,update.getChanges().size());assertEquals(4,update.getUnknown().size());
        Set<String> unknownTargets=new HashSet<>();for(Map<String,String> row:update.getUnknown()){unknownTargets.add(row.get("targetBookmark"));assertEquals("TOC_TITLE_HEADING_RANGE_INCOMPLETE",row.get("reason"));}assertEquals(Set.of("_Toc183592382","_Toc183592393","_Toc183592403","_Toc183592446"),unknownTargets);
        assertEquals(Set.of("_Toc183592395","_Toc183592419"),new HashSet<>(Arrays.asList(update.getChanges().get(0).get("targetBookmark"),update.getChanges().get(1).get("targetBookmark"))));
        String restored=xml(changed);for(Map<String,String> receipt:update.getChanges()){int start=restored.indexOf("<w:hyperlink w:anchor=\""+receipt.get("targetBookmark")+"\""),end=restored.indexOf("</w:hyperlink>",start)+"</w:hyperlink>".length();String row=restored.substring(start,end);restored=restored.substring(0,start)+row.replace("<w:t>"+receipt.get("after")+"</w:t>","<w:t>"+receipt.get("before")+"</w:t>")+restored.substring(end);}
        assertEquals(xml(original),restored,"Reversing only the two TOC text nodes must restore every body, field, bookmark and format byte");
        assertEquals(features(original),features(changed));unchangedPartsExceptDocument(original,changed);
        List<TocPageReference> before=EDITOR.tocPageReferences(original),after=EDITOR.tocPageReferences(changed);for(int i=0;i<92;i++){assertEquals(before.get(i).getTargetBookmark(),after.get(i).getTargetBookmark());assertEquals(before.get(i).getCachedPage(),after.get(i).getCachedPage());}
        assertArrayEquals(original,Files.readAllBytes(Paths.get(input)));
        String output=System.getProperty("consense.acceptance.titleOutput");if(output!=null)Files.write(Paths.get(output),changed);
    }
}
