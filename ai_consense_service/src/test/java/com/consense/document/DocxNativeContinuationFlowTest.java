package com.consense.document;

import com.consense.document.DocxTemplateEditor.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Authored source-structure controls only; no native renderer, real-document or business acceptance. */
class DocxNativeContinuationFlowTest {
    private final DocxTemplateEditor editor=new DocxTemplateEditor();
    private static String mark(int id,String name,String text){return "<w:bookmarkStart w:id=\""+id+"\" w:name=\""+name+"\"/><w:r><w:t>"+text+"</w:t></w:r><w:bookmarkEnd w:id=\""+id+"\"/>";}
    private static String p(int id,String name,String text){return "<w:p>"+mark(id,name,text)+"</w:p>";}
    private static byte[] fixture(String body)throws Exception {
        Map<String,byte[]> entries=DocxClearedNativeLayoutGuardTest.parts(DocxClearedNativeLayoutGuardTest.fixture(""));
        entries.put("word/document.xml",("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"+body+"<w:sectPr><w:pgSz w:h=\"16838\" w:w=\"11906\"/></w:sectPr></w:body></w:document>").getBytes(StandardCharsets.UTF_8));
        try(ByteArrayOutputStream bytes=new ByteArrayOutputStream();ZipOutputStream zip=new ZipOutputStream(bytes)){for(Map.Entry<String,byte[]> e:entries.entrySet()){zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue());zip.closeEntry();}zip.finish();return bytes.toByteArray();}
    }
    private static String xml(byte[] bytes)throws Exception{return new String(DocxClearedNativeLayoutGuardTest.parts(bytes).get("word/document.xml"),StandardCharsets.UTF_8);}
    private static String sha(byte[] bytes)throws Exception {StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format("%02x",b&255));return out.toString();}
    private NativeLayoutPlan plan(TemplateIndex index,List<NativeLayoutParagraphBoundary> boundaries){return new NativeLayoutPlan(index.getSourceSha256(),"authored-native-continuation",Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),boundaries);}
    private DocxEditResult apply(byte[] source,List<Edit> edits,NativeLayoutPlan plan){return editor.applyWithParagraphBookmarks(source,new SourceEditBatch(editor.inspect(source).getSourceSha256(),edits),Collections.emptyMap(),false,plan);}
    private NativeLayoutParagraphBoundary edge(TemplateIndex index,int a,int b,boolean release){List<NodeAnchor> gaps=new ArrayList<>();for(int i=a+1;i<b;i++)gaps.add(index.anchor(index.mainParagraph(i).getId()));return new NativeLayoutParagraphBoundary(index.anchor(index.mainParagraph(a).getId()),index.anchor(index.mainParagraph(b).getId()),gaps,true,release);}
    private static Set<String> names(TemplateIndex index){Set<String> names=new HashSet<>();for(Feature f:index.getFeatures())if("BOOKMARK".equals(f.getKind())){assertTrue(names.add(f.getName()));assertTrue(f.isComplete());}return names;}
    private void preserved(byte[] source,DocxEditResult result)throws Exception {
        TemplateIndex before=editor.inspect(source),after=result.getIndex();for(int i=1;i<=before.getMainParagraphs().size();i++)assertEquals(before.mainParagraph(i).getCatalogText(),after.mainParagraph(i).getCatalogText());
        assertTrue(names(after).containsAll(names(before)));assertTrue(xml(result.getDocxBytes()).contains("<w:sectPr><w:pgSz w:h=\"16838\" w:w=\"11906\"/></w:sectPr>"));
        Map<String,byte[]> a=DocxClearedNativeLayoutGuardTest.parts(source),b=DocxClearedNativeLayoutGuardTest.parts(result.getDocxBytes());assertEquals(a.keySet(),b.keySet());for(String key:a.keySet())if(!"word/document.xml".equals(key))assertArrayEquals(a.get(key),b.get(key),key);
    }
    @Test void adjacentIntroChainsShareOnlyTheirExactEndpointAndRepairFromTheRoot()throws Exception {
        byte[] source=fixture(p(1,"CSroot","Clause heading")+p(2,"CSintro","Substitute with:-")+p(3,"CSitem","(1) Retained first item"));TemplateIndex index=editor.inspect(source);
        DocxEditResult compiled=apply(source,Collections.emptyList(),plan(index,Arrays.asList(edge(index,1,2,false),edge(index,2,3,false))));
        assertTrue(compiled.getIndex().mainParagraph(1).hasNativeHeadingFlow());assertTrue(compiled.getIndex().mainParagraph(2).hasNativeHeadingFlow());assertFalse(compiled.getIndex().mainParagraph(3).hasNativeKeepNext());preserved(source,compiled);
        List<NativeHeadingFlow> flows=Arrays.asList(new NativeHeadingFlow("CSroot","CSintro",0),new NativeHeadingFlow("CSintro","CSitem",0));
        assertEquals(Collections.singleton("CSroot"),DocxTemplateEditor.nativeHeadingRepairRoots(flows,Collections.singleton("CSintro")));
        DocxEditResult repaired=editor.updateNativeHeadingBoundaries(compiled.getDocxBytes(),compiled.getDocxSha256(),flows,Collections.singleton("CSintro"));
        assertTrue(repaired.getIndex().mainParagraph(1).hasNativeHeadingPageBreak());assertFalse(repaired.getIndex().mainParagraph(2).hasNativeHeadingPageBreak());assertEquals(compiled.getIndex().mainParagraph(2).getSha256(),repaired.getIndex().mainParagraph(2).getSha256());preserved(source,repaired);
    }
    @Test void thirteenGapsCompactOnlyAfterAllSevenGuidanceTextsWereCleared()throws Exception {
        StringBuilder body=new StringBuilder(p(1,"CSroot","Clause heading"));for(int i=0;i<13;i++)body.append(i<7?p(i+2,"CSguidance"+i,"Preparer guidance "+i):"<w:p/>");body.append(p(20,"CSoption","Option A"));byte[] source=fixture(body.toString());TemplateIndex index=editor.inspect(source);NativeLayoutPlan plan=plan(index,Collections.singletonList(edge(index,1,15,false)));List<Edit> edits=new ArrayList<>();for(int i=2;i<=8;i++)edits.add(Edit.clearParagraph("clear-guide-"+i,index.mainParagraph(i)));
        DocxEditResult incomplete=apply(source,edits.subList(0,6),plan);assertEquals("SKIPPED_NOT_CLEARED",incomplete.getNativeLayoutLedger().get(0).getStatus());assertFalse(incomplete.getIndex().mainParagraph(1).hasNativeKeepNext());
        DocxEditResult complete=apply(source,edits,plan);assertEquals(27,complete.getNativeLayoutLedger().size());assertEquals(index.mainParagraph(15).getSha256(),complete.getIndex().mainParagraph(15).getSha256());for(int i=2;i<=14;i++){assertTrue(complete.getIndex().mainParagraph(i).hasCompactClearedLayoutSpacing());assertTrue(complete.getIndex().mainParagraph(i).hasNativeHeadingFlow());assertEquals("",complete.getIndex().mainParagraph(i).getText());}assertTrue(names(complete.getIndex()).containsAll(names(index)));
    }
    @Test void oneBoundTrailingPageBreakReleasesWithoutLosingTextMarkersSectionsOrOtherParts()throws Exception {
        byte[] source=fixture("<w:p>"+mark(1,"CSlead","Substitute with:-")+"<w:r><w:br w:type=\"page\"/></w:r></w:p>"+p(2,"CSitem","(1) Retained first item"));TemplateIndex index=editor.inspect(source);DocxEditResult result=apply(source,Collections.emptyList(),plan(index,Collections.singletonList(edge(index,1,2,true))));
        assertEquals(2,result.getNativeLayoutLedger().size());assertEquals("release_verified_trailing_page_break",result.getNativeLayoutLedger().get(0).getAction());assertTrue(result.getIndex().mainParagraph(1).hasReleasedNativeTrailingPageBreak());assertEquals(index.mainParagraph(1).getText(),result.getIndex().mainParagraph(1).getText()+"\n");assertEquals(index.mainParagraph(2).getSha256(),result.getIndex().mainParagraph(2).getSha256());preserved(source,result);
        String restored=xml(result.getDocxBytes());for(NativeLayoutChange change:result.getNativeLayoutLedger()){Matcher marker=Pattern.compile("<w:bookmarkStart w:id=\"([0-9]+)\" w:name=\""+Pattern.quote(change.getLayoutId())+"\"/>").matcher(restored);assertTrue(marker.find());String id=marker.group(1);restored=restored.replace(marker.group(),"").replace("<w:bookmarkEnd w:id=\""+id+"\"/>","");}
        restored=restored.replace("<w:pPr><w:keepNext w:val=\"1\"/><w:keepLines w:val=\"1\"/></w:pPr>","").replace("<w:r/>","<w:r><w:br w:type=\"page\"/></w:r>");assertEquals(xml(source),restored);
    }
    @Test void trailingBreakPermissionRejectsSecondBreakFollowingContentWrongKindSectionsAndTables()throws Exception {
        String lead=mark(1,"CSlead","Substitute with:-"),item=p(2,"CSitem","(1) First item");for(String extra:Arrays.asList("<w:br w:type=\"page\"/><w:br w:type=\"page\"/>","<w:br w:type=\"page\"/><w:t>Following text</w:t>","<w:br w:type=\"page\"/><w:tab/>","<w:br w:type=\"textWrapping\"/>")){
            byte[] source=fixture("<w:p>"+lead+"<w:r>"+extra+"</w:r></w:p>"+item);TemplateIndex index=editor.inspect(source);assertThrows(EditException.class,()->apply(source,Collections.emptyList(),plan(index,Collections.singletonList(edge(index,1,2,true)))));
        }
        for(String between:Arrays.asList("<w:tbl><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl>","<w:p><w:pPr><w:sectPr/></w:pPr></w:p>")){byte[] source=fixture(p(1,"CSlead","Substitute with:-")+between+item);TemplateIndex index=editor.inspect(source);int end=between.startsWith("<w:tbl")?2:3;assertThrows(EditException.class,()->apply(source,Collections.emptyList(),plan(index,Collections.singletonList(edge(index,1,end,false)))));}
    }
    @Test void duplicateEdgesAliasesReverseOrderAndInsertedOrNonemptyGapsStayRejected()throws Exception {
        String body=p(1,"CSroot","Heading")+p(2,"CSintro","Intro:-")+p(3,"CSitem","Item");List<NativeHeadingFlow> valid=Arrays.asList(new NativeHeadingFlow("CSroot","CSintro",0),new NativeHeadingFlow("CSintro","CSitem",0));
        List<List<NativeHeadingFlow>> invalid=Arrays.asList(Arrays.asList(valid.get(0),valid.get(0)),Arrays.asList(valid.get(0),new NativeHeadingFlow("CSroot","CSitem",1)),Arrays.asList(new NativeHeadingFlow("CSintro","CSroot",0)),Arrays.asList(new NativeHeadingFlow("CSroot","CSitem",1)));
        byte[] source=fixture(body);for(List<NativeHeadingFlow> flows:invalid)assertThrows(EditException.class,()->editor.updateNativeHeadingBoundaries(source,sha(source),flows,Collections.emptySet()));
        byte[] alias=fixture(body.replace("Heading</w:t></w:r>","Heading</w:t></w:r>"+mark(9,"CSalias","")));assertThrows(EditException.class,()->editor.updateNativeHeadingBoundaries(alias,sha(alias),Arrays.asList(valid.get(0),new NativeHeadingFlow("CSalias","CSitem",1)),Collections.emptySet()));
        byte[] inserted=fixture(body.replace(p(2,"CSintro","Intro:-"),"<w:p/>"+p(2,"CSintro","Intro:-")));assertThrows(EditException.class,()->editor.updateNativeHeadingBoundaries(inserted,sha(inserted),valid,Collections.emptySet()));
    }

    private static List<String> originalBookmarkMarkers(byte[] bytes,Set<String> ids)throws Exception {
        List<String> result=new ArrayList<>();Matcher marker=Pattern.compile("<w:bookmark(?:Start|End)\\b[^>]*/>").matcher(xml(bytes));while(marker.find()){Matcher id=Pattern.compile("w:id=\"([0-9]+)\"").matcher(marker.group());if(id.find()&&ids.contains(id.group(1)))result.add(marker.group());}return result;
    }
    @Test void zeroDisplayNativeBodyBookmarksRemainVerbatimAcrossCaptureApplyAndDynamicRepair()throws Exception {
        String field="<w:p><w:fldSimple w:instr=\"DATE\"><w:r><w:t>2026-10-21</w:t></w:r></w:fldSimple></w:p>";
        String nativeEnd="<w:bookmarkEnd w:id=\"50\"/>",nativePair="<w:bookmarkStart w:id=\"51\" w:name=\"NativeBody\"/><w:bookmarkEnd w:id=\"51\"/>";
        byte[] source=fixture("<w:p>"+mark(1,"CSlead","Substitute with:-")+"<w:bookmarkStart w:id=\"50\" w:name=\"NativeWide\"/></w:p>"+nativeEnd+"<w:p/>"+nativePair+p(2,"CSitem","(1) Retained first item")+field);TemplateIndex index=editor.inspect(source);
        DocxEditResult compiled=apply(source,Collections.emptyList(),plan(index,Collections.singletonList(edge(index,1,3,false))));assertEquals(3,compiled.getNativeLayoutLedger().size());for(NativeLayoutChange change:compiled.getNativeLayoutLedger())assertEquals("APPLIED",change.getStatus());assertTrue(compiled.getIndex().mainParagraph(2).hasCompactClearedLayoutSpacing());preserved(source,compiled);
        Set<String> originalIds=new HashSet<>(Arrays.asList("1","2","50","51"));assertEquals(8,originalBookmarkMarkers(source,originalIds).size());assertEquals(originalBookmarkMarkers(source,originalIds),originalBookmarkMarkers(compiled.getDocxBytes(),originalIds));assertTrue(xml(compiled.getDocxBytes()).contains(nativePair));assertTrue(xml(compiled.getDocxBytes()).contains(field));
        List<NativeHeadingFlow> flows=Collections.singletonList(new NativeHeadingFlow("CSlead","CSitem",1));DocxEditResult repaired=editor.updateNativeHeadingBoundaries(compiled.getDocxBytes(),compiled.getDocxSha256(),flows,Collections.singleton("CSlead"));assertTrue(repaired.getIndex().mainParagraph(1).hasNativeHeadingPageBreak());assertEquals(xml(compiled.getDocxBytes()),xml(repaired.getDocxBytes()).replace("<w:pageBreakBefore w:val=\"1\"/>",""));assertEquals(originalBookmarkMarkers(source,originalIds),originalBookmarkMarkers(repaired.getDocxBytes(),originalIds));preserved(source,repaired);
    }
    @Test void onlyEmptyNativeBodyMarkersWithExactAllowedAttributesAreTransparent()throws Exception {
        List<String> blockers=Arrays.asList("<w:bookmarkEnd w:id=\"50\"><w:r><w:t>Hidden content</w:t></w:r></w:bookmarkEnd>","<w:bookmarkEnd w:id=\"50\"> </w:bookmarkEnd>","<w:bookmarkEnd w:id=\"50\" w:displacedByCustomXml=\"next\"/>","<w:bookmarkEnd w:id=\"50\" w:name=\"Extra\"/>","<w:bookmarkEnd w:id=\"invalid\"/>","<w:bookmarkEnd w:id=\"-1\"/>","<w:bookmarkEnd w:id=\"+1\"/>","<w:bookmarkEnd xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" w:id=\"50\"/>","<w:bookmarkStart w:id=\"50\"/>","<w:bookmarkStart w:id=\"50\" w:name=\" \"/>","<w:bookmarkStart w:id=\"50\" w:name=\"Native\" w:colFirst=\"0\"/>","<other:bookmarkEnd xmlns:other=\"urn:other\" other:id=\"50\"/>","<w:tbl><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl>","<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>","<w:sectPr/>","<w:sdt/>");
        for(String blocker:blockers){byte[] source=fixture(p(1,"CSlead","Substitute with:-")+blocker+p(2,"CSitem","(1) Retained first item"));TemplateIndex index=editor.inspect(source);assertThrows(EditException.class,()->apply(source,Collections.emptyList(),plan(index,Collections.singletonList(edge(index,1,2,false)))),blocker);assertThrows(EditException.class,()->editor.updateNativeHeadingBoundaries(source,sha(source),Collections.singletonList(new NativeHeadingFlow("CSlead","CSitem",0)),Collections.emptySet()),blocker);}
    }
    @Test void transparentNativeMarkersDoNotRelaxSourceAnchorsGapCountsOrCompleteCsRanges()throws Exception {
        String marker="<w:bookmarkEnd w:id=\"50\"/>",body=p(1,"CSlead","Substitute with:-")+marker+p(2,"CSitem","(1) Retained first item");byte[] source=fixture(body);TemplateIndex index=editor.inspect(source);NativeLayoutPlan plan=plan(index,Collections.singletonList(edge(index,1,2,false)));List<NativeHeadingFlow> flow=Collections.singletonList(new NativeHeadingFlow("CSlead","CSitem",0));
        for(String inserted:Arrays.asList("<w:p/>","<w:p><w:r><w:t>Inserted content</w:t></w:r></w:p>")){byte[] altered=fixture(body.replace(marker,marker+inserted));assertThrows(EditException.class,()->apply(altered,Collections.emptyList(),plan));assertThrows(EditException.class,()->editor.updateNativeHeadingBoundaries(altered,sha(altered),flow,Collections.emptySet()));}
        byte[] duplicateEnd=fixture(body.replace(marker,marker+"<w:bookmarkEnd w:id=\"1\"/>"));assertThrows(EditException.class,()->editor.updateNativeHeadingBoundaries(duplicateEnd,sha(duplicateEnd),flow,Collections.emptySet()));
        byte[] wrongRange=fixture(body.replace("<w:bookmarkEnd w:id=\"1\"/>","").replace(marker,marker+"<w:bookmarkEnd w:id=\"1\"/>"));assertThrows(EditException.class,()->editor.updateNativeHeadingBoundaries(wrongRange,sha(wrongRange),flow,Collections.emptySet()));assertThrows(EditException.class,()->editor.updateNativeHeadingBoundaries(source,"wrong-sha",flow,Collections.emptySet()));
        TemplateIndex alternate=editor.inspect(fixture(body.replace("Substitute with:-","Different heading")));NativeLayoutParagraphBoundary edge=new NativeLayoutParagraphBoundary(alternate.anchor(alternate.mainParagraph(1).getId()),index.anchor(index.mainParagraph(2).getId()),Collections.emptyList(),true,false);assertThrows(EditException.class,()->apply(source,Collections.emptyList(),plan(index,Collections.singletonList(edge))));
    }

    @Test void bodyWidowsChangeOnlyTwoRegisteredControlsAndPreserveWordsStylesMarkersAndOtherParts()throws Exception {
        String first=p(1,"CSfirst","Base Index Figure shall apply forty-two days before the Tender Closing Date.").replace("<w:p>","<w:p><w:pPr><w:pStyle w:val=\"BodyOne\"/><w:widowControl w:val=\"0\"/><w:spacing w:after=\"100\"/><w:ind w:left=\"360\"/></w:pPr>");
        String second=p(2,"CSsecond","In the event of a change to the index, the retained terms continue to apply.").replace("<w:p>","<w:p><w:pPr><w:pStyle w:val=\"BodyTwo\"/><w:spacing w:after=\"180\"/></w:pPr>");
        String untouched=p(3,"CSuntouched","This unregistered paragraph keeps its original control.").replace("<w:p>","<w:p><w:pPr><w:widowControl w:val=\"0\"/></w:pPr>");
        byte[] source=fixture(first+second+untouched);TemplateIndex index=editor.inspect(source);
        NativeLayoutPlan layout=plan(index,Collections.emptyList()).withBodyParagraphWidows(Arrays.asList(index.anchor(index.mainParagraph(1).getId()),index.anchor(index.mainParagraph(2).getId())));
        DocxEditResult result=apply(source,Collections.emptyList(),layout);assertEquals(2,result.getNativeLayoutLedger().size());Set<String> controls=new HashSet<>();
        for(NativeLayoutChange change:result.getNativeLayoutLedger()){assertEquals("keep_verified_body_paragraph_widows",change.getAction());assertEquals("APPLIED",change.getStatus());assertNotNull(change.getLayoutId());assertTrue(controls.add(change.getLayoutId()));}
        for(int i=1;i<=2;i++){assertTrue(result.getIndex().mainParagraph(i).hasNativeWidowControl());assertFalse(result.getIndex().mainParagraph(i).hasNativeKeepNext());}
        assertEquals(index.mainParagraph(3).getSha256(),result.getIndex().mainParagraph(3).getSha256());assertFalse(result.getIndex().mainParagraph(3).hasNativeWidowControl());preserved(source,result);
        Set<String> originalIds=new HashSet<>(Arrays.asList("1","2","3"));assertEquals(6,originalBookmarkMarkers(source,originalIds).size());assertEquals(originalBookmarkMarkers(source,originalIds),originalBookmarkMarkers(result.getDocxBytes(),originalIds));
        String restored=xml(result.getDocxBytes());assertFalse(restored.contains("<w:keepNext"));assertFalse(restored.contains("<w:keepLines"));
        for(NativeLayoutChange change:result.getNativeLayoutLedger()){Matcher marker=Pattern.compile("<w:bookmarkStart w:id=\"([0-9]+)\" w:name=\""+Pattern.quote(change.getLayoutId())+"\"/>").matcher(restored);assertTrue(marker.find());String id=marker.group(1);restored=restored.replace(marker.group(),"").replace("<w:bookmarkEnd w:id=\""+id+"\"/>","");}
        restored=restored.replace("<w:pStyle w:val=\"BodyTwo\"/><w:widowControl w:val=\"1\"/>","<w:pStyle w:val=\"BodyTwo\"/>").replace("<w:widowControl w:val=\"1\"/>","<w:widowControl w:val=\"0\"/>");assertEquals(xml(source),restored);
    }
    @Test void bodyWidowsRejectNonordinaryContentTableCellsDuplicateTargetsAndWrongSourceAnchors()throws Exception {
        List<String> invalid=Arrays.asList("<w:p/>","<w:p><w:fldSimple w:instr=\"DATE\"><w:r><w:t>2026-10-21</w:t></w:r></w:fldSimple></w:p>","<w:p><w:pPr><w:sectPr/></w:pPr><w:r><w:t>Section body</w:t></w:r></w:p>","<w:p>"+mark(1,"CSbreak","Retained body")+"<w:r><w:br w:type=\"page\"/></w:r></w:p>","<w:p>"+mark(1,"CSdrawing","Retained body")+"<w:r><w:drawing/></w:r></w:p>");
        for(String paragraph:invalid){byte[] source=fixture(paragraph+p(2,"CSuntouched","Unregistered body"));TemplateIndex index=editor.inspect(source);assertThrows(EditException.class,()->apply(source,Collections.emptyList(),plan(index,Collections.emptyList()).withBodyParagraphWidows(Collections.singletonList(index.anchor(index.mainParagraph(1).getId())))),paragraph);}
        byte[] source=fixture(p(1,"CSfirst","Registered body")+p(2,"CSsecond","Unregistered body"));TemplateIndex index=editor.inspect(source);NodeAnchor target=index.anchor(index.mainParagraph(1).getId());NativeLayoutPlan valid=plan(index,Collections.emptyList()).withBodyParagraphWidows(Collections.singletonList(target));
        assertThrows(EditException.class,()->apply(source,Collections.emptyList(),plan(index,Collections.emptyList()).withBodyParagraphWidows(Arrays.asList(target,target))));
        byte[] changedSource=fixture(p(1,"CSfirst","Different original body")+p(2,"CSsecond","Unregistered body"));assertThrows(EditException.class,()->apply(changedSource,Collections.emptyList(),valid));
        TemplateIndex alternate=editor.inspect(changedSource);assertThrows(EditException.class,()->apply(source,Collections.emptyList(),plan(index,Collections.emptyList()).withBodyParagraphWidows(Collections.singletonList(alternate.anchor(alternate.mainParagraph(1).getId())))));
        byte[] cellSource=fixture(p(1,"CSbody","Unregistered body")+"<w:tbl><w:tr><w:tc>"+p(2,"CScell","Table cell body")+"</w:tc></w:tr></w:tbl>");TemplateIndex cellIndex=editor.inspect(cellSource);Paragraph cell=cellIndex.getParagraphs().stream().filter(paragraph->paragraph.getCellId()!=null).findFirst().orElseThrow(AssertionError::new);assertThrows(EditException.class,()->apply(cellSource,Collections.emptyList(),plan(cellIndex,Collections.emptyList()).withBodyParagraphWidows(Collections.singletonList(cellIndex.anchor(cell.getId())))));
    }
    @Test void bodyWidowsSkipClearedOrRemovedParagraphsWithoutAddingControlsOrMarkers()throws Exception {
        String first=p(1,"CSremoved","Registered body to remove").replace("<w:p>","<w:p><w:pPr><w:widowControl w:val=\"0\"/></w:pPr>"),second=p(2,"CScleared","Registered body to clear").replace("<w:p>","<w:p><w:pPr><w:widowControl w:val=\"0\"/></w:pPr>");
        byte[] source=fixture(first+second+p(3,"CSuntouched","Unregistered body"));TemplateIndex index=editor.inspect(source);NativeLayoutPlan layout=plan(index,Collections.emptyList()).withBodyParagraphWidows(Arrays.asList(index.anchor(index.mainParagraph(1).getId()),index.anchor(index.mainParagraph(2).getId())));
        DocxEditResult result=apply(source,Arrays.asList(Edit.deleteParagraph("delete-registered",index.mainParagraph(1)),Edit.clearParagraph("clear-registered",index.mainParagraph(2))),layout);assertEquals(2,result.getNativeLayoutLedger().size());
        assertEquals("SKIPPED_REMOVED",result.getNativeLayoutLedger().get(0).getStatus());assertEquals("SKIPPED_CLEARED",result.getNativeLayoutLedger().get(1).getStatus());for(NativeLayoutChange change:result.getNativeLayoutLedger()){assertEquals("keep_verified_body_paragraph_widows",change.getAction());assertNull(change.getLayoutId());}
        assertEquals(2,result.getIndex().getMainParagraphs().size());assertEquals("",result.getIndex().mainParagraph(1).getText());assertFalse(result.getIndex().mainParagraph(1).hasNativeWidowControl());assertEquals(index.mainParagraph(3).getSha256(),result.getIndex().mainParagraph(2).getSha256());assertEquals(new HashSet<>(Arrays.asList("CScleared","CSuntouched")),names(result.getIndex()));
        Set<String> remainingIds=new HashSet<>(Arrays.asList("2","3"));assertEquals(4,originalBookmarkMarkers(source,remainingIds).size());assertEquals(originalBookmarkMarkers(source,remainingIds),originalBookmarkMarkers(result.getDocxBytes(),remainingIds));assertFalse(xml(result.getDocxBytes()).contains("<w:widowControl w:val=\"1\"/>"));
        Map<String,byte[]> before=DocxClearedNativeLayoutGuardTest.parts(source),after=DocxClearedNativeLayoutGuardTest.parts(result.getDocxBytes());assertEquals(before.keySet(),after.keySet());for(String part:before.keySet())if(!"word/document.xml".equals(part))assertArrayEquals(before.get(part),after.get(part),part);
    }
}
