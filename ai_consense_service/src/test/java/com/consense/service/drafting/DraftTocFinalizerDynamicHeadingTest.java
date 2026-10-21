package com.consense.service.drafting;

import com.consense.document.DocxTemplateEditor.*;
import com.consense.domain.DraftPdfArtifact;
import java.io.*;
import java.util.*;
import java.util.function.Function;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftNativeHeadingBoundariesTest.*;

/** Authored PDFBox replay proves software decisions only; zero native processes and no real document acceptance. */
class DraftTocFinalizerDynamicHeadingTest {
    enum Mode { NO_SPLIT, REPAIR_SUCCESS, STILL_SPLIT, MOVED_FINAL_POINT, WRONG_PAIR, PROFILE_CHANGED, STALE_FINAL }
    static final class AuthoredRenderer implements Function<byte[],DraftPdfConversionResult> {
        final Mode mode;final List<byte[]> inputs=new ArrayList<>(),pdfs=new ArrayList<>();
        AuthoredRenderer(Mode mode){this.mode=mode;}
        public DraftPdfConversionResult apply(byte[] input){
            try {
                inputs.add(input.clone());int call=inputs.size();assertTrue(call<=3,"The software finalizer may request at most three conversions");String document=xml(input);List<NativeHeadingFlow> all=allFlows();boolean repaired=mode!=Mode.NO_SPLIT&&paragraph(document,all.get(0).getHeadingBookmark()).contains("<w:pageBreakBefore w:val=\"1\"/>");
                Map<String,Integer> pages=new LinkedHashMap<>();Map<String,Float> tops=new LinkedHashMap<>();
                String root=all.get(0).getHeadingBookmark();Set<String> moved=continuationNames(all,root);
                for(int i=0;i<all.size();i++){NativeHeadingFlow flow=all.get(i);for(String name:Arrays.asList(flow.getHeadingBookmark(),flow.getBodyBookmark())){int page=mode!=Mode.NO_SPLIT&&moved.contains(name)?3:2;if(name.equals(root)&&(!repaired||mode==Mode.STILL_SPLIT))page=2;pages.put(name,page);tops.putIfAbsent(name,600f-i);}if(i<70){pages.put(tocTarget(i),pages.get(flow.getHeadingBookmark()));tops.put(tocTarget(i),600f-i);}for(int g=0;g<flow.getEmptyParagraphs();g++){pages.put("CLdynamic"+i+"Gap"+g,mode!=Mode.NO_SPLIT&&moved.contains(flow.getHeadingBookmark())?3:2);tops.put("CLdynamic"+i+"Gap"+g,590f-i);}}
                for(int i=0;i<70;i++){pages.put(rowMarker(i),1);tops.put(rowMarker(i),704f-i*8);}pages.put("CSdynamicAfterRows",1);tops.put("CSdynamicAfterRows",144f);
                byte[] pdf;try(PDDocument output=new PDDocument();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                    for(int i=0;i<3;i++)output.addPage(new PDPage(PDRectangle.A4));COSDictionary destinations=new COSDictionary();Set<String> added=new HashSet<>();
                    for(Feature feature:EDITOR.inspect(input).getFeatures())if("BOOKMARK".equals(feature.getKind())&&feature.getName()!=null&&added.add(feature.getName())){String name=feature.getName();PDPageXYZDestination location=new PDPageXYZDestination();location.setPage(output.getPage(pages.getOrDefault(name,2)-1));location.setLeft(mode==Mode.MOVED_FINAL_POINT&&call==3&&name.equals(all.get(69).getBodyBookmark())?73:72);location.setTop(Math.round(tops.getOrDefault(name,500f)));destinations.setItem(COSName.getPDFName(name),location.getCOSObject());}
                    output.getDocumentCatalog().getCOSObject().setItem(COSName.DESTS,destinations);
                    List<TocPageReference> refs=EDITOR.tocPageReferences(input);assertEquals(70,refs.size());try(PDPageContentStream content=new PDPageContentStream(output,output.getPage(0))){for(int i=0;i<refs.size();i++){String value=mode==Mode.STALE_FINAL&&call==3&&i==0?"2":refs.get(i).getCachedPage();line(content,70,700-i*8,6,"Authored clause "+i+" .... "+value);}}
                    for(int i=0;i<3;i++)try(PDPageContentStream content=new PDPageContentStream(output,output.getPage(i),PDPageContentStream.AppendMode.APPEND,true)){line(content,250,30,10,String.valueOf(i+1));}
                    output.save(bytes);pdf=bytes.toByteArray();
                }
                pdfs.add(pdf);Map<String,Object> manifest=new LinkedHashMap<>();manifest.put("docxSha256",mode==Mode.WRONG_PAIR&&call==2?"wrong":DraftPdfConverter.sha256(input));manifest.put("pdfSha256",DraftPdfConverter.sha256(pdf));manifest.put("renderProfileHash",mode==Mode.PROFILE_CHANGED&&call==2?"changed-authored-profile":"authored-software-replay-profile");manifest.put("fixtureMeaning","AUTHORED_SOFTWARE_ONLY_NO_LIBREOFFICE_OR_SOURCE_LAYOUT_ACCEPTANCE");return new DraftPdfConversionResult(pdf,manifest);
            }catch(IOException failure){throw new UncheckedIOException(failure);}
        }
    }
    static void line(PDPageContentStream stream,float x,float y,int size,String text)throws IOException{stream.beginText();stream.setFont(PDType1Font.HELVETICA,size);stream.newLineAtOffset(x,y);stream.showText(text);stream.endText();}
    static void fallback(byte[] original,DraftTocFinalizer.Result result,AuthoredRenderer rendered){assertEquals("UNKNOWN",result.proof.get("status"),String.valueOf(result.proof));assertArrayEquals(original,result.compiled);assertArrayEquals(original,result.docx);assertNotNull(result.first);assertNotNull(result.pdf);assertArrayEquals(rendered.pdfs.get(0),result.first.getPdfBytes());assertArrayEquals(rendered.pdfs.get(0),result.pdf.getPdfBytes());assertEquals(DraftPdfConverter.sha256(original),result.pdf.getDocxSha256());}
    @Test void noSplitKeepsAllSeventyAndUsesTwoConversions()throws Exception {
        byte[] original=fixture();AuthoredRenderer renderer=new AuthoredRenderer(Mode.NO_SPLIT);DraftTocFinalizer.Result result=DraftTocFinalizer.finish(original,ORIGINAL_SOURCE_SHA,renderer);
        assertEquals("PASS",result.proof.get("status"),String.valueOf(result.proof));assertFalse(result.proof.containsKey("reason"));assertEquals("native-heading-flow-then-toc-bounded-v2",result.proof.get("policy"));assertEquals(2,renderer.inputs.size());assertEquals(104,result.proof.get("headingFlowDenominator"));assertEquals(0,result.proof.get("headingFlowRepairCount"));assertEquals(104,((List<?>)result.proof.get("finalHeadingPairs")).size());assertEquals(result.proof.get("firstPoints"),result.proof.get("finalPoints"));allBodiesAndMarkersUnchanged(original,result.docx);assertFalse(xml(result.docx).contains("<w:pageBreakBefore"));
    }
    @Test void measuredSplitRepairsOnlyItsHeadingThenProvesSecondToThirdPassStable()throws Exception {
        byte[] original=fixture();AuthoredRenderer renderer=new AuthoredRenderer(Mode.REPAIR_SUCCESS);DraftTocFinalizer.Result result=DraftTocFinalizer.finish(original,ORIGINAL_SOURCE_SHA,renderer);
        assertEquals("PASS",result.proof.get("status"),String.valueOf(result.proof));assertFalse(result.proof.containsKey("reason"));assertEquals("native-heading-flow-then-toc-bounded-v2",result.proof.get("policy"));assertEquals(3,renderer.inputs.size());assertEquals(104,result.proof.get("headingFlowDenominator"));assertEquals(1,result.proof.get("headingFlowRepairCount"));assertEquals(2,result.proof.get("measurementPass"));assertEquals(3,result.proof.get("finalConversionPass"));assertEquals(2,result.proof.get("stablePointComparisonFromPass"));assertNotEquals(result.proof.get("firstPoints"),result.proof.get("measurementPoints"));assertEquals(result.proof.get("measurementPoints"),result.proof.get("finalPoints"));assertEquals(104,((List<?>)result.proof.get("finalHeadingPairs")).size());
        allBodiesAndMarkersUnchanged(original,result.docx);String before=xml(original),after=xml(result.docx);for(int i=0;i<70;i++){String name=flows().get(i).getHeadingBookmark();if(i==0)assertEquals(paragraph(before,name),paragraph(after,name).replace("<w:pageBreakBefore w:val=\"1\"/>",""));else assertEquals(paragraph(before,name),paragraph(after,name));}assertEquals(DraftPdfConverter.sha256(result.docx),result.pdf.getDocxSha256());assertArrayEquals(renderer.inputs.get(2),result.docx);
    }
    @Test void invalidFullBindingSetCannotBeSilentlyReducedEvenWhenNoSplitWasMeasured()throws Exception {
        byte[] original=fixture();String[] reasons={"HEADING_FLOW_BOOKMARK_AMBIGUOUS","TOC_BINDING_NAME_AMBIGUOUS","HEADING_FLOW_BOOKMARK_AMBIGUOUS","HEADING_FLOW_BOOKMARK_RANGE_CHANGED","HEADING_FLOW_BOOKMARK_RANGE_CHANGED","HEADING_FLOW_CHAIN_CHANGED","HEADING_FLOW_CHAIN_CHANGED","HEADING_FLOW_CHAIN_CHANGED","LAYOUT_CLEARED_INLINE_UNSUPPORTED"};int i=0;for(String invalid:invalidBindingXml(xml(original))){byte[] altered=packageXml(invalid);AuthoredRenderer renderer=new AuthoredRenderer(Mode.NO_SPLIT);DraftTocFinalizer.Result result=DraftTocFinalizer.finish(altered,ORIGINAL_SOURCE_SHA,renderer);assertEquals(1,renderer.inputs.size());fallback(altered,result,renderer);assertTrue(String.valueOf(result.proof.get("reason")).startsWith(reasons[i++]),String.valueOf(result.proof));}assertEquals(reasons.length,i);
    }
    @Test void failedRepairOrChangedFinalPairAlwaysKeepsTheOriginalFirstPair()throws Exception {
        Map<Mode,String> reasons=new LinkedHashMap<>();reasons.put(Mode.STILL_SPLIT,"HEADING_FLOW_STILL_SPLIT_AFTER_REPAIR");reasons.put(Mode.MOVED_FINAL_POINT,"TOC_TARGET_OR_BINDING_MOVED");reasons.put(Mode.WRONG_PAIR,"TOC_CONVERSION_IDENTITY_MISMATCH");reasons.put(Mode.PROFILE_CHANGED,"TOC_RENDER_PROFILE_CHANGED");reasons.put(Mode.STALE_FINAL,"TOC_SECOND_PASS_STILL_STALE");for(Map.Entry<Mode,String> expected:reasons.entrySet()){Mode mode=expected.getKey();byte[] original=fixture();AuthoredRenderer renderer=new AuthoredRenderer(mode);DraftTocFinalizer.Result result=DraftTocFinalizer.finish(original,ORIGINAL_SOURCE_SHA,renderer);assertEquals(mode==Mode.STILL_SPLIT||mode==Mode.WRONG_PAIR||mode==Mode.PROFILE_CHANGED?2:3,renderer.inputs.size(),mode.name());fallback(original,result,renderer);assertEquals(expected.getValue(),result.proof.get("reason"));}
    }
    @Test void unverifiedSourceCannotDisableTheFullRegisteredDenominator()throws Exception {
        byte[] original=fixture();AuthoredRenderer renderer=new AuthoredRenderer(Mode.NO_SPLIT);DraftTocFinalizer.Result result=DraftTocFinalizer.finish(original,"unverified-source",renderer);assertEquals(1,renderer.inputs.size());fallback(original,result,renderer);assertEquals("HEADING_FLOW_SOURCE_UNVERIFIED",result.proof.get("reason"));
    }
    private static DraftPdfArtifact existingPdf(DraftTocFinalizer.Result finalized,byte[] bytes){
        DraftPdfArtifact cached=new DraftPdfArtifact();cached.setDocxSha256(finalized.pdf.getDocxSha256());cached.setRenderProfileHash(finalized.pdf.getRenderProfileHash());cached.setPdfBytes(bytes);cached.setPdfSha256(DraftPdfConverter.sha256(bytes));return cached;
    }
    @Test void immutableCachedPdfWithDifferentMetadataReusesItsBytesOnlyAfterTheSameLayoutIsVerified()throws Exception {
        byte[] original=fixture();AuthoredRenderer renderer=new AuthoredRenderer(Mode.REPAIR_SUCCESS);DraftTocFinalizer.Result finalized=DraftTocFinalizer.finish(original,ORIGINAL_SOURCE_SHA,renderer);
        assertEquals("PASS",finalized.proof.get("status"));assertFalse(Arrays.equals(original,finalized.docx));int conversions=renderer.inputs.size();byte[] cachedBytes;
        try(PDDocument document=PDDocument.load(finalized.pdf.getPdfBytes());ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            document.getDocumentInformation().setCustomMetadataValue("AuthoredImmutableCache","earlier-render-of-the-same-finalized-document");document.save(bytes);cachedBytes=bytes.toByteArray();
        }
        assertFalse(Arrays.equals(cachedBytes,finalized.pdf.getPdfBytes()),"Equivalent measured layout need not have the newly rendered PDF bytes");DraftPdfArtifact existing=existingPdf(finalized,cachedBytes);
        DraftTocFinalizer.Result reused=finalized.useExisting(existing);
        assertEquals("PASS",reused.proof.get("status"));assertEquals("PASS",reused.proof.get("headingFlowStatus"));assertEquals(true,reused.proof.get("reusedImmutablePdf"));assertEquals(existing.getPdfSha256(),reused.proof.get("adoptedPdfSha256"));
        assertArrayEquals(original,reused.compiled);assertArrayEquals(finalized.docx,reused.docx);assertArrayEquals(cachedBytes,reused.pdf.getPdfBytes());assertEquals(existing.getPdfSha256(),reused.pdf.getPdfSha256());assertEquals(DraftPdfConverter.sha256(finalized.docx),reused.pdf.getDocxSha256());
        assertEquals(finalized.proof.get("finalPoints"),reused.proof.get("finalPoints"));assertEquals(finalized.proof.get("finalPages"),reused.proof.get("finalPages"));assertEquals(finalized.proof.get("finalActualTocPages"),reused.proof.get("finalActualTocPages"));assertEquals(reused.proof,reused.pdf.getManifest().get("tocFinalization"));
        assertEquals(conversions,renderer.inputs.size(),"Reusing a measured immutable cache must not request another render");assertArrayEquals(cachedBytes,existing.getPdfBytes());assertEquals("PASS",finalized.proof.get("status"));assertFalse(finalized.proof.containsKey("reusedImmutablePdf"));
    }
    @Test void cacheIdentityConflictsAndAHashVerifiedMovedBindingKeepTheUnmodifiedFirstPair()throws Exception {
        byte[] original=fixture();AuthoredRenderer renderer=new AuthoredRenderer(Mode.REPAIR_SUCCESS);DraftTocFinalizer.Result finalized=DraftTocFinalizer.finish(original,ORIGINAL_SOURCE_SHA,renderer);
        assertEquals("PASS",finalized.proof.get("status"));assertFalse(Arrays.equals(original,finalized.docx));int conversions=renderer.inputs.size();byte[] finalPdf=finalized.pdf.getPdfBytes(),movedPdf;
        try(PDDocument document=PDDocument.load(finalPdf);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            COSDictionary destinations=(COSDictionary)document.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.DESTS);String target=allFlows().get(69).getBodyBookmark();COSArray point=(COSArray)destinations.getDictionaryObject(COSName.getPDFName(target));assertNotNull(point);assertEquals(72,((COSNumber)point.getObject(2)).intValue());point.set(2,COSInteger.get(73));document.save(bytes);movedPdf=bytes.toByteArray();
        }
        List<DraftPdfArtifact> conflicts=new ArrayList<>();DraftPdfArtifact wrongDocx=existingPdf(finalized,finalPdf);wrongDocx.setDocxSha256(DraftPdfConverter.sha256(original));conflicts.add(wrongDocx);
        DraftPdfArtifact wrongProfile=existingPdf(finalized,finalPdf);wrongProfile.setRenderProfileHash("different-authored-renderer-profile");conflicts.add(wrongProfile);
        DraftPdfArtifact wrongHash=existingPdf(finalized,finalPdf);wrongHash.setPdfSha256(DraftPdfConverter.sha256(new byte[]{1,2,3}));conflicts.add(wrongHash);conflicts.add(existingPdf(finalized,movedPdf));
        for(int index=0;index<conflicts.size();index++){
            DraftPdfArtifact existing=conflicts.get(index);byte[] before=existing.getPdfBytes();String beforeHash=existing.getPdfSha256();DraftTocFinalizer.Result rejected=finalized.useExisting(existing);
            fallback(original,rejected,renderer);assertEquals(index==3?"TOC_EXISTING_CACHE_LAYOUT_MISMATCH":"TOC_EXISTING_CACHE_IDENTITY_MISMATCH",rejected.proof.get("reason"));assertEquals("UNKNOWN",rejected.proof.get("headingFlowStatus"));assertEquals(DraftPdfConverter.sha256(original),rejected.proof.get("finalDocxSha256"));assertFalse(rejected.proof.containsKey("reusedImmutablePdf"));
            assertArrayEquals(before,existing.getPdfBytes());assertEquals(beforeHash,existing.getPdfSha256());assertEquals(conversions,renderer.inputs.size());
        }
        assertEquals("PASS",finalized.proof.get("status"));assertArrayEquals(finalPdf,finalized.pdf.getPdfBytes());assertFalse(finalized.proof.containsKey("reusedImmutablePdf"));
    }
}
