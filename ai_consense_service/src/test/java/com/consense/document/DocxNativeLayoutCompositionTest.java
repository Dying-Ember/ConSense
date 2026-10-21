package com.consense.document;

import com.consense.document.DocxTemplateEditor.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.*;
import static org.junit.jupiter.api.Assertions.*;

/** Authored native composition contracts; no renderer, real-source or business acceptance. */
class DocxNativeLayoutCompositionTest {
    private static final String MAIN="word/document.xml", W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String SLOT="normalize_verified_latin_font_slot", WIDOW="keep_verified_body_paragraph_widows";
    private static final String FONTS="<w:rPr><w:rFonts w:ascii=\"Times New Roman\" w:hAnsi=\"Times New Roman\" w:eastAsia=\"Adobe Heiti Std R\"/><w:b/><w:sz w:val=\"24\"/><w:lang w:val=\"en-GB\" w:eastAsia=\"zh-CN\"/></w:rPr>";
    private final DocxTemplateEditor editor=new DocxTemplateEditor();
    private static String run(String value,boolean hint){return "<w:r>"+(hint?FONTS.replace(" w:eastAsia=\"Adobe Heiti Std R\""," w:eastAsia=\"Adobe Heiti Std R\" w:hint=\"eastAsia\""):FONTS)+"<w:t xml:space=\"preserve\">"+value+"</w:t></w:r>";}
    private static byte[] fixture()throws Exception {
        String tabFonts=FONTS.replace(" w:eastAsia=\"Adobe Heiti Std R\""," w:eastAsia=\"Adobe Heiti Std R\" w:hint=\"eastAsia\"");
        String xml="<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\""+W+"\"><w:body>"
            +"<w:p><w:pPr><w:pStyle w:val=\"IntroStyle\"/><w:widowControl w:val=\"0\"/><w:spacing w:after=\"120\"/><w:ind w:left=\"360\"/></w:pPr><w:bookmarkStart w:id=\"1\" w:name=\"CSlead\"/>"+run("The retained source lead ends with:-",true)+"<w:r>"+tabFonts+"<w:tab/></w:r><w:bookmarkEnd w:id=\"1\"/></w:p>"
            +"<w:p><w:pPr><w:spacing w:before=\"120\" w:after=\"180\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:p>"
            +"<w:p><w:pPr><w:pStyle w:val=\"BodyStyle\"/><w:spacing w:after=\"100\"/><w:ind w:left=\"720\"/></w:pPr><w:bookmarkStart w:id=\"2\" w:name=\"CSbody\"/>"+run("(a) Retained first body with exact Latin words.",false)+"<w:bookmarkEnd w:id=\"2\"/></w:p>"
            +"<w:p><w:pPr><w:pStyle w:val=\"TailStyle\"/><w:spacing w:after=\"160\"/></w:pPr><w:bookmarkStart w:id=\"3\" w:name=\"CStail\"/><w:r><w:t>Untouched following paragraph.</w:t></w:r><w:bookmarkEnd w:id=\"3\"/></w:p>"
            +"<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr></w:body></w:document>";
        Map<String,byte[]> entries=new LinkedHashMap<>();entries.put(MAIN,xml.getBytes(StandardCharsets.UTF_8));
        entries.put("[Content_Types].xml",("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>").getBytes(StandardCharsets.UTF_8));
        entries.put("word/styles.xml",("<w:styles xmlns:w=\""+W+"\"><w:style w:type=\"paragraph\" w:styleId=\"IntroStyle\"/><w:style w:type=\"paragraph\" w:styleId=\"BodyStyle\"/><w:style w:type=\"paragraph\" w:styleId=\"TailStyle\"/></w:styles>").getBytes(StandardCharsets.UTF_8));
        entries.put("word/media/opaque.bin",new byte[]{0,1,2,(byte)255});
        try(ByteArrayOutputStream out=new ByteArrayOutputStream();ZipOutputStream zip=new ZipOutputStream(out)){for(Map.Entry<String,byte[]> e:entries.entrySet()){zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue());zip.closeEntry();}zip.finish();return out.toByteArray();}
    }
    private NativeLayoutPlan plan(TemplateIndex index){
        NodeAnchor intro=index.anchor(index.mainParagraph(1).getId()),body=index.anchor(index.mainParagraph(3).getId());
        NativeLayoutParagraphBoundary edge=new NativeLayoutParagraphBoundary(intro,body,Collections.singletonList(index.anchor(index.mainParagraph(2).getId())),true,false,true);
        return new NativeLayoutPlan(index.getSourceSha256(),"authored-layout-composition",Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.singletonList(edge))
            .withLatinFontSlotParagraphs(Collections.singletonList(body)).withBodyParagraphWidows(Collections.singletonList(intro));
    }
    private DocxEditResult apply(byte[] source,TemplateIndex index,List<Edit> edits){return editor.applyWithParagraphBookmarks(source,new SourceEditBatch(index.getSourceSha256(),edits),Collections.emptyMap(),false,plan(index));}
    private static Map<String,byte[]> parts(byte[] bytes)throws Exception {Map<String,byte[]> out=new LinkedHashMap<>();try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry entry;byte[] buffer=new byte[4096];while((entry=in.getNextEntry())!=null){ByteArrayOutputStream value=new ByteArrayOutputStream();int n;while((n=in.read(buffer))!=-1)value.write(buffer,0,n);out.put(entry.getName(),value.toByteArray());}}return out;}
    private static Document document(byte[] bytes)throws Exception {DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);return factory.newDocumentBuilder().parse(new ByteArrayInputStream(parts(bytes).get(MAIN)));}
    private static Element p(Document document,int ordinal){return (Element)document.getElementsByTagNameNS(W,"p").item(ordinal-1);}
    private static List<Element> direct(Element parent,String local){List<Element> out=new ArrayList<>();for(Node n=parent.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element&&W.equals(n.getNamespaceURI())&&local.equals(n.getLocalName()))out.add((Element)n);return out;}
    private static Element one(Element parent,String local){List<Element> values=direct(parent,local);assertEquals(1,values.size(),local);return values.get(0);}
    private static void removeOne(Element parent,String local){Element value=one(parent,local);parent.removeChild(value);}
    private static NativeLayoutChange change(DocxEditResult result,String action,String source){NativeLayoutChange found=null;for(NativeLayoutChange row:result.getNativeLayoutLedger())if(action.equals(row.getAction())&&source.equals(row.getSource().getId())){assertNull(found,action);found=row;}assertNotNull(found,action);return found;}
    private static void status(DocxEditResult result,String action,String source,String expected){NativeLayoutChange row=change(result,action,source);assertEquals(expected,row.getStatus(),action);if("APPLIED".equals(expected))assertNotNull(row.getLayoutId());else assertNull(row.getLayoutId());}
    private static void otherParts(byte[] source,byte[] result)throws Exception {Map<String,byte[]> before=parts(source),after=parts(result);assertEquals(before.keySet(),after.keySet());for(String name:before.keySet())if(!MAIN.equals(name))assertArrayEquals(before.get(name),after.get(name),name);}
    private static void removeLayoutMarkers(Document document,List<NativeLayoutChange> changes){
        for(NativeLayoutChange row:changes){String name=row.getLayoutId();assertNotNull(name);Element start=null;NodeList starts=document.getElementsByTagNameNS(W,"bookmarkStart");for(int i=0;i<starts.getLength();i++){Element candidate=(Element)starts.item(i);if(name.equals(candidate.getAttributeNS(W,"name"))){assertNull(start);start=candidate;}}assertNotNull(start,name);String id=start.getAttributeNS(W,"id");Element end=null;NodeList ends=document.getElementsByTagNameNS(W,"bookmarkEnd");for(int i=0;i<ends.getLength();i++){Element candidate=(Element)ends.item(i);if(id.equals(candidate.getAttributeNS(W,"id"))){assertNull(end);end=candidate;}}assertNotNull(end);start.getParentNode().removeChild(start);end.getParentNode().removeChild(end);}
    }
    @Test void sourceFontAndWidowControlsComposeWithBoundaryKeepNextAndWholeBodyLines()throws Exception {
        byte[] source=fixture();TemplateIndex index=editor.inspect(source);DocxEditResult result=apply(source,index,Collections.emptyList());
        String intro=index.mainParagraph(1).getId(),body=index.mainParagraph(3).getId();status(result,SLOT,body,"APPLIED");status(result,WIDOW,intro,"APPLIED");
        assertEquals(6,result.getNativeLayoutLedger().size());Set<String> names=new HashSet<>();for(NativeLayoutChange row:result.getNativeLayoutLedger()){assertEquals("APPLIED",row.getStatus());assertTrue(names.add(row.getLayoutId()));}
        assertTrue(result.getIndex().mainParagraph(1).hasNativeKeepNext());assertTrue(result.getIndex().mainParagraph(1).hasNativeBodyLines());assertTrue(result.getIndex().mainParagraph(1).hasNativeWidowControl());assertTrue(result.getIndex().mainParagraph(3).hasNativeBodyLines());assertFalse(result.getIndex().mainParagraph(3).hasNativeKeepNext());
        for(int i=1;i<=4;i++)assertEquals(index.mainParagraph(i).getText(),result.getIndex().mainParagraph(i).getText());assertEquals(index.mainParagraph(4).getSha256(),result.getIndex().mainParagraph(4).getSha256());otherParts(source,result.getDocxBytes());
        Document before=document(source),after=document(result.getDocxBytes());NodeList fonts=after.getElementsByTagNameNS(W,"rFonts");assertEquals(3,fonts.getLength());assertEquals("eastAsia",((Element)fonts.item(0)).getAttributeNS(W,"hint"),"An unregistered hint is not implicitly normalized");assertEquals("eastAsia",((Element)fonts.item(1)).getAttributeNS(W,"hint"),"Protected tab-only run retains its hint");assertFalse(((Element)fonts.item(2)).hasAttributeNS(W,"eastAsia"));
        removeLayoutMarkers(after,result.getNativeLayoutLedger());Element introProps=one(p(after,1),"pPr");removeOne(introProps,"keepNext");removeOne(introProps,"keepLines");one(introProps,"widowControl").setAttributeNS(W,"w:val","0");
        Element gapProps=one(p(after,2),"pPr");assertEquals(3,direct(gapProps,"keepNext").size()+direct(gapProps,"keepLines").size()+direct(gapProps,"spacing").size());Element spacing=one(gapProps,"spacing");assertEquals("0",spacing.getAttributeNS(W,"before"));assertEquals("0",spacing.getAttributeNS(W,"after"));assertEquals("1",spacing.getAttributeNS(W,"line"));assertEquals("exact",spacing.getAttributeNS(W,"lineRule"));p(after,2).replaceChild(after.importNode(one(p(before,2),"pPr"),true),gapProps);
        removeOne(one(p(after,3),"pPr"),"keepLines");NodeList originalFonts=before.getElementsByTagNameNS(W,"rFonts");for(int i=0;i<fonts.getLength();i++)for(String attribute:Arrays.asList("eastAsia","hint")){Element old=(Element)originalFonts.item(i),now=(Element)fonts.item(i);if(old.hasAttributeNS(W,attribute)&&!now.hasAttributeNS(W,attribute))now.setAttributeNS(W,"w:"+attribute,old.getAttributeNS(W,attribute));}
        assertTrue(before.getDocumentElement().isEqualNode(after.getDocumentElement()),"Reversing only declared layout controls and font attributes restores every native property, text run and original bookmark");assertEquals(index.getSourceSha256(),editor.inspect(source).getSourceSha256());
    }
    @Test void businessTextChangesClearsAndDeletesRemainIneligibleForEveryComposedRepair()throws Exception {
        byte[] source=fixture();TemplateIndex index=editor.inspect(source);Paragraph intro=index.mainParagraph(1),body=index.mainParagraph(3);
        for(int kind=0;kind<3;kind++){
            List<Edit> edits=kind==0?Arrays.asList(Edit.replaceSpan("lead-edit",intro,0,intro.getCatalogText().length(),intro.getCatalogText(),"Adopted lead"),Edit.replaceSpan("body-edit",body,0,body.getText().length(),body.getText(),"Adopted body")):kind==1?Arrays.asList(Edit.clearParagraph("lead-clear",intro),Edit.clearParagraph("body-clear",body)):Arrays.asList(Edit.deleteParagraph("lead-delete",intro),Edit.deleteParagraph("body-delete",body));
            DocxEditResult result=apply(source,index,edits),businessOnly=editor.apply(source,new SourceEditBatch(index.getSourceSha256(),edits));
            status(result,SLOT,body.getId(),kind==2?"SKIPPED_REMOVED":"SKIPPED_CHANGED");status(result,WIDOW,intro.getId(),kind==2?"SKIPPED_REMOVED":kind==1?"SKIPPED_CLEARED":"SKIPPED_CHANGED");status(result,"keep_verified_heading_flow",intro.getId(),"SKIPPED_NOT_CLEARED");
            assertEquals(3,result.getNativeLayoutLedger().size());for(NativeLayoutChange row:result.getNativeLayoutLedger()){assertNotEquals("APPLIED",row.getStatus());assertNull(row.getLayoutId());}for(Feature feature:result.getIndex().getFeatures())assertFalse(feature.getName()!=null&&feature.getName().startsWith("CL"));
            assertTrue(document(businessOnly.getDocxBytes()).getDocumentElement().isEqualNode(document(result.getDocxBytes()).getDocumentElement()),"Business edits retain their exact native result; no boundary, font or widow cleanup can attach to changed, cleared or removed source paragraphs");otherParts(source,result.getDocxBytes());
        }
    }

    @Test void independentSourceHintComposesWithBodyFontSlotAndBoundaryWhileKeepingTabOnlyHint()throws Exception {
        byte[] source=fixture();TemplateIndex index=editor.inspect(source);NodeAnchor intro=index.anchor(index.mainParagraph(1).getId());
        NativeLayoutPlan selected=plan(index).withBodyParagraphWidows(Collections.emptyList()).withLatinFontHintParagraphs(Collections.singletonList(intro));
        DocxEditResult result=editor.applyWithParagraphBookmarks(source,new SourceEditBatch(index.getSourceSha256(),Collections.emptyList()),Collections.emptyMap(),false,selected);
        status(result,"normalize_verified_latin_font_hint",intro.getId(),"APPLIED");status(result,SLOT,index.mainParagraph(3).getId(),"APPLIED");assertEquals(6,result.getNativeLayoutLedger().size());Set<String> names=new HashSet<>();for(NativeLayoutChange row:result.getNativeLayoutLedger()){assertEquals("APPLIED",row.getStatus());assertNotEquals(WIDOW,row.getAction());assertTrue(names.add(row.getLayoutId()));}
        assertTrue(result.getIndex().mainParagraph(1).hasNativeKeepNext());assertTrue(result.getIndex().mainParagraph(1).hasNativeBodyLines());assertFalse(result.getIndex().mainParagraph(1).hasNativeWidowControl(),"The unregistered source widow=0 remains intact");assertTrue(result.getIndex().mainParagraph(3).hasNativeBodyLines());assertFalse(result.getIndex().mainParagraph(3).hasNativeKeepNext());
        for(int i=1;i<=4;i++)assertEquals(index.mainParagraph(i).getText(),result.getIndex().mainParagraph(i).getText());assertEquals(index.mainParagraph(4).getSha256(),result.getIndex().mainParagraph(4).getSha256());otherParts(source,result.getDocxBytes());
        Document before=document(source),after=document(result.getDocxBytes());NodeList fonts=after.getElementsByTagNameNS(W,"rFonts"),originalFonts=before.getElementsByTagNameNS(W,"rFonts");assertEquals(3,fonts.getLength());Element textFonts=(Element)fonts.item(0),tabFonts=(Element)fonts.item(1),bodyFonts=(Element)fonts.item(2);assertFalse(textFonts.hasAttributeNS(W,"hint"));assertEquals("Adobe Heiti Std R",textFonts.getAttributeNS(W,"eastAsia"));assertEquals("eastAsia",tabFonts.getAttributeNS(W,"hint"));assertEquals("Adobe Heiti Std R",tabFonts.getAttributeNS(W,"eastAsia"));assertFalse(bodyFonts.hasAttributeNS(W,"eastAsia"));
        removeLayoutMarkers(after,result.getNativeLayoutLedger());Element introProps=one(p(after,1),"pPr");removeOne(introProps,"keepNext");removeOne(introProps,"keepLines");assertEquals("0",one(introProps,"widowControl").getAttributeNS(W,"val"));Element gapProps=one(p(after,2),"pPr");p(after,2).replaceChild(after.importNode(one(p(before,2),"pPr"),true),gapProps);removeOne(one(p(after,3),"pPr"),"keepLines");
        textFonts.setAttributeNS(W,"w:hint",((Element)originalFonts.item(0)).getAttributeNS(W,"hint"));bodyFonts.setAttributeNS(W,"w:eastAsia",((Element)originalFonts.item(2)).getAttributeNS(W,"eastAsia"));assertTrue(before.getDocumentElement().isEqualNode(after.getDocumentElement()),"Only the declared text hint, body font slot and native boundary controls may change; the tab-only hint, widow value and all other source XML remain exact");
    }
}
