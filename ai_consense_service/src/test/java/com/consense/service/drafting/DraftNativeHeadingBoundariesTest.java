package com.consense.service.drafting;

import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Authored software fixture using all approved source bindings; no source, service or renderer acceptance. */
class DraftNativeHeadingBoundariesTest {
    static final String ORIGINAL_SOURCE_SHA="9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208";
    static final DocxTemplateEditor EDITOR=new DocxTemplateEditor();
    static List<NativeHeadingFlow> allFlows(){List<NativeHeadingFlow> rows=DraftNativeLayout.headingFlows(ORIGINAL_SOURCE_SHA,"libreoffice");assertEquals(104,rows.size());return rows;}
    // The original seventy source relations stay separate from the thirty-four measured continuation repairs.
    static List<NativeHeadingFlow> flows(){return allFlows().subList(0,70);}
    static Set<String> continuationNames(List<NativeHeadingFlow> rows,String root){Set<String> names=new LinkedHashSet<>();names.add(root);boolean changed;do{changed=false;for(NativeHeadingFlow row:rows)if(names.contains(row.getHeadingBookmark()))changed|=names.add(row.getBodyBookmark());}while(changed);return names;}
    static String marker(int id,String name){return "<w:bookmarkStart w:id=\""+id+"\" w:name=\""+name+"\"/>";}
    static String end(int id){return "<w:bookmarkEnd w:id=\""+id+"\"/>";}
    static String text(String value){return "<w:r><w:t>"+value+"</w:t></w:r>";}
    static String tocTarget(int i){return "_TocDynamic"+i;}
    static String rowMarker(int i){return "CSdynamicRow"+i;}
    static byte[] fixture()throws IOException {
        StringBuilder xml=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>");
        xml.append("<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText> TOC \\o \"1-2\" \\h </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r></w:p>");
        int id=1;
        for(int i=0;i<70;i++){int row=id++;xml.append("<w:p>").append(marker(row,rowMarker(i))).append("<w:hyperlink w:anchor=\"").append(tocTarget(i)).append("\">").append(text("Authored clause "+i+" .... ")).append("<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText> PAGEREF ").append(tocTarget(i)).append(" \\h </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>").append(text("2")).append("<w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:hyperlink>").append(end(row)).append("</w:p>");}
        xml.append("<w:p><w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>");int after=id++;xml.append("<w:p>").append(marker(after,"CSdynamicAfterRows")).append(text("End of authored TOC")).append(end(after)).append("</w:p>");
        List<NativeHeadingFlow> all=allFlows();Map<String,NativeHeadingFlow> outgoing=new LinkedHashMap<>();Set<String> targets=new HashSet<>();Map<String,Integer> legacyHeadings=new HashMap<>(),bodyNumbers=new HashMap<>(),flowNumbers=new HashMap<>();
        for(int i=0;i<all.size();i++){NativeHeadingFlow flow=all.get(i);outgoing.put(flow.getHeadingBookmark(),flow);targets.add(flow.getBodyBookmark());bodyNumbers.putIfAbsent(flow.getBodyBookmark(),i);flowNumbers.put(flow.getHeadingBookmark(),i);if(i<70)legacyHeadings.put(flow.getHeadingBookmark(),i);}
        Set<String> written=new HashSet<>();for(NativeHeadingFlow first:all)if(!targets.contains(first.getHeadingBookmark())){
            String name=first.getHeadingBookmark();while(name!=null){assertTrue(written.add(name));NativeHeadingFlow next=outgoing.get(name);int paragraph=id++;xml.append("<w:p><w:pPr>");if(next!=null)xml.append("<w:keepNext w:val=\"1\"/>");xml.append("<w:keepLines/><w:spacing w:after=\"120\"/></w:pPr>").append(marker(paragraph,name));
                Integer legacy=legacyHeadings.get(name);int target=legacy==null?-1:id++;if(legacy!=null)xml.append(marker(target,tocTarget(legacy)));xml.append(text(legacy==null?"Authored retained body "+bodyNumbers.get(name):"Authored heading "+legacy));if(legacy!=null)xml.append(end(target));xml.append(end(paragraph)).append("</w:p>");
                if(next==null){name=null;continue;}int i=flowNumbers.get(name);for(int gap=0;gap<next.getEmptyParagraphs();gap++){int control=id++;xml.append("<w:p><w:pPr><w:keepNext w:val=\"1\"/><w:keepLines/><w:spacing w:after=\"0\" w:before=\"0\" w:line=\"1\" w:lineRule=\"exact\"/></w:pPr>").append(marker(control,"CLdynamic"+i+"Gap"+gap)).append(end(control)).append("</w:p>");}name=next.getBodyBookmark();
            }
        }
        Set<String> endpoints=new HashSet<>(outgoing.keySet());endpoints.addAll(targets);assertEquals(endpoints,written);
        xml.append("</w:body></w:document>");return packageXml(xml.toString());
    }
    static byte[] packageXml(String xml)throws IOException {
        Map<String,byte[]> parts=new LinkedHashMap<>();parts.put("word/document.xml",xml.getBytes(StandardCharsets.UTF_8));parts.put("[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>".getBytes(StandardCharsets.UTF_8));parts.put("word/media/opaque.bin",new byte[]{0,1,2,(byte)255});return zip(parts);
    }
    static byte[] zip(Map<String,byte[]> parts)throws IOException {try(ByteArrayOutputStream out=new ByteArrayOutputStream();ZipOutputStream zip=new ZipOutputStream(out)){for(Map.Entry<String,byte[]> e:parts.entrySet()){ZipEntry entry=new ZipEntry(e.getKey());entry.setTime(0);zip.putNextEntry(entry);zip.write(e.getValue());zip.closeEntry();}zip.finish();return out.toByteArray();}}
    static Map<String,byte[]> parts(byte[] bytes)throws IOException {Map<String,byte[]> result=new LinkedHashMap<>();try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry e;while((e=zip.getNextEntry())!=null)result.put(e.getName(),zip.readAllBytes());}return result;}
    static String xml(byte[] bytes)throws IOException{return new String(parts(bytes).get("word/document.xml"),StandardCharsets.UTF_8);}
    static String paragraph(String xml,String bookmark){Matcher p=Pattern.compile("<w:p(?:\\s[^>]*)?>.*?</w:p>",Pattern.DOTALL).matcher(xml);while(p.find())if(p.group().contains("w:name=\""+bookmark+"\""))return p.group();throw new AssertionError("Missing fixture paragraph: "+bookmark);}
    static int bookmarkId(String xml,String name){Matcher m=Pattern.compile("<w:bookmarkStart w:id=\"([0-9]+)\" w:name=\""+Pattern.quote(name)+"\"/>").matcher(xml);assertTrue(m.find());return Integer.parseInt(m.group(1));}
    static Set<String> features(byte[] bytes){Set<String> result=new LinkedHashSet<>();for(Feature f:EDITOR.inspect(bytes).getFeatures())result.add(f.getKind()+"|"+f.getName()+"|"+f.getStartId()+"|"+f.getEndId()+"|"+f.isComplete());return result;}
    static void unchangedPartsExceptDocument(byte[] before,byte[] after)throws IOException {Map<String,byte[]> a=parts(before),b=parts(after);assertEquals(a.keySet(),b.keySet());for(String key:a.keySet())if(!"word/document.xml".equals(key))assertArrayEquals(a.get(key),b.get(key),key);}
    static void allBodiesAndMarkersUnchanged(byte[] before,byte[] after)throws IOException {String a=xml(before),b=xml(after);for(NativeHeadingFlow flow:flows())assertEquals(paragraph(a,flow.getBodyBookmark()),paragraph(b,flow.getBodyBookmark()),flow.getBodyBookmark());assertEquals(features(before),features(after));unchangedPartsExceptDocument(before,after);}
    static List<String> invalidBindingXml(String original){NativeHeadingFlow first=flows().get(0);String name=first.getHeadingBookmark();int id=bookmarkId(original,name);String start=marker(id,name),finish=end(id),heading=paragraph(original,name);List<String> cases=new ArrayList<>();
        cases.add(original.replace(start,""));
        cases.add(original.replace(start,start+marker(900000,name)+end(900000)));
        cases.add(original.replace(finish,finish+finish));
        cases.add(original.replace(finish,"").replace(start,finish+start));
        cases.add(original.replace(finish,"").replace(marker(bookmarkId(original,first.getBodyBookmark()),first.getBodyBookmark()),marker(bookmarkId(original,first.getBodyBookmark()),first.getBodyBookmark())+finish));
        cases.add(original.replace(heading,heading+"<w:p/>"));
        cases.add(original.replace(heading,heading+"<w:p>"+text("Authorized inserted paragraph")+"</w:p>"));
        String gap=paragraph(original,"CLdynamic0Gap0");cases.add(original.replace(gap,gap.replace("</w:p>",text("Gap is no longer empty")+"</w:p>")));
        cases.add(original.replace(gap,gap.replace("</w:p>","<w:r><w:drawing/></w:r></w:p>")));return cases;
    }
    @Test void validatesAllSeventyAndChangesOnlyMeasuredHeadingProperties()throws Exception {
        byte[] original=fixture();List<NativeHeadingFlow> full=flows();Set<String> split=new LinkedHashSet<>(Arrays.asList(full.get(0).getHeadingBookmark(),full.get(69).getHeadingBookmark()));DocxEditResult changed=EDITOR.updateNativeHeadingBoundaries(original,DraftPdfConverter.sha256(original),full,split);
        allBodiesAndMarkersUnchanged(original,changed.getDocxBytes());String before=xml(original),after=xml(changed.getDocxBytes());for(NativeHeadingFlow flow:full){String prior=paragraph(before,flow.getHeadingBookmark()),next=paragraph(after,flow.getHeadingBookmark());if(split.contains(flow.getHeadingBookmark())){assertTrue(next.contains("<w:keepLines/>"));assertTrue(next.contains("<w:pageBreakBefore w:val=\"1\"/>"));assertEquals(prior,next.replace("<w:pageBreakBefore w:val=\"1\"/>",""));}else assertEquals(prior,next);}
        assertEquals(before,after.replace("<w:pageBreakBefore w:val=\"1\"/>",""));
    }
    @Test void emptySplitSetStillValidatesTheFullBindingSet()throws Exception {
        byte[] original=fixture();DocxEditResult unchanged=EDITOR.updateNativeHeadingBoundaries(original,DraftPdfConverter.sha256(original),flows(),Collections.emptySet());assertEquals(xml(original),xml(unchanged.getDocxBytes()));unchangedPartsExceptDocument(original,unchanged.getDocxBytes());
        for(String bad:invalidBindingXml(xml(original))){byte[] altered=packageXml(bad);assertThrows(EditException.class,()->EDITOR.updateNativeHeadingBoundaries(altered,DraftPdfConverter.sha256(altered),flows(),Collections.emptySet()));}
    }
    @Test void rejectsWrongIdentityUnknownSelectionAndDuplicateFlowDeclarations()throws Exception {
        byte[] original=fixture();assertThrows(EditException.class,()->EDITOR.updateNativeHeadingBoundaries(original,"wrong",flows(),Collections.emptySet()));assertThrows(EditException.class,()->EDITOR.updateNativeHeadingBoundaries(original,DraftPdfConverter.sha256(original),flows(),Collections.singleton("CSunknownHeading")));
        List<NativeHeadingFlow> duplicate=new ArrayList<>(flows());duplicate.add(duplicate.get(0));assertThrows(EditException.class,()->EDITOR.updateNativeHeadingBoundaries(original,DraftPdfConverter.sha256(original),duplicate,Collections.emptySet()));
    }
    @Test void authorizedBodyTextEditsRemainBoundButInsertedParagraphsDoNot()throws Exception {
        byte[] original=fixture(),edited=packageXml(xml(original).replace("Authored retained body 0","Authorized changed body text"));DocxEditResult result=EDITOR.updateNativeHeadingBoundaries(edited,DraftPdfConverter.sha256(edited),flows(),Collections.singleton(flows().get(0).getHeadingBookmark()));allBodiesAndMarkersUnchanged(edited,result.getDocxBytes());
    }
}
