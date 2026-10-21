package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.*;
import static org.junit.jupiter.api.Assertions.*;

/** Source-bound paragraph switches, preserving native table shape and real pagination. */
class DraftSctCellFlowCandidateTest {
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main", MAIN="word/document.xml";
    private final DocxTemplateEditor editor=new DocxTemplateEditor();
    private static byte[] fixture(String controls)throws Exception {
        return zip(Map.of(MAIN,("<w:document xmlns:w=\""+W+"\"><w:body><w:tbl><w:tr><w:tc>"
                +"<w:p><w:pPr><w:tabs><w:tab w:val=\"left\" w:pos=\"900\"/></w:tabs><w:ind w:left=\"120\"/></w:pPr><w:r><w:rPr><w:sz w:val=\"24\"/></w:rPr><w:t>SCT title</w:t>"+controls+"</w:r></w:p><w:p/>"
                +"</w:tc><w:tc><w:p><w:r><w:t>Source guidance</w:t></w:r></w:p></w:tc></w:tr><w:tr><w:tc>"
                +"<w:p><w:pPr><w:widowControl w:val=\"0\"/><w:spacing w:before=\"120\" w:line=\"240\"/></w:pPr><w:r><w:t>First body paragraph</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Outside cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
                +"<w:p><w:r><w:t>Outside body paragraph</w:t></w:r></w:p></w:body></w:document>").getBytes(StandardCharsets.UTF_8),"[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>".getBytes(StandardCharsets.UTF_8),"word/media/opaque.bin",new byte[]{2,4,6}));
    }
    private static byte[] zip(Map<String,byte[]> entries)throws Exception{try(ByteArrayOutputStream out=new ByteArrayOutputStream();ZipOutputStream zip=new ZipOutputStream(out)){for(Map.Entry<String,byte[]> e:entries.entrySet()){zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue());zip.closeEntry();}zip.finish();return out.toByteArray();}}
    private static Map<String,byte[]> unzip(byte[] bytes)throws Exception{Map<String,byte[]> result=new LinkedHashMap<>();try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry entry;while((entry=in.getNextEntry())!=null)result.put(entry.getName(),in.readAllBytes());}return result;}
    private static Document xml(byte[] bytes)throws Exception{DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);return f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));}
    private static Element paragraph(Document d,int n){return (Element)d.getElementsByTagNameNS(W,"p").item(n-1);}
    private static List<Element> direct(Element e,String name){List<Element> result=new ArrayList<>();for(Node n=e.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element&&W.equals(n.getNamespaceURI())&&name.equals(n.getLocalName()))result.add((Element)n);return result;}
    private static String shape(Node node){if(node instanceof Element){SortedMap<String,String> attrs=new TreeMap<>();NamedNodeMap values=node.getAttributes();for(int i=0;i<values.getLength();i++){Node a=values.item(i);if(!"http://www.w3.org/2000/xmlns/".equals(a.getNamespaceURI()))attrs.put(a.getNodeName(),a.getNodeValue());}StringBuilder s=new StringBuilder(node.getNodeName()+attrs);for(Node n=node.getFirstChild();n!=null;n=n.getNextSibling())s.append(shape(n));return s.toString();}return node.getNodeType()+":"+node.getNodeValue();}
    private NativeLayoutPlan plan(TemplateIndex index,List<NodeAnchor> next,List<NodeAnchor> widows){return new NativeLayoutPlan(index.getSourceSha256(),"test-cell-flow",List.of(),List.of()).withCellParagraphFlow(next,widows);}
    private List<NodeAnchor> anchors(TemplateIndex index,int... ordinals){List<NodeAnchor> result=new ArrayList<>();for(int ordinal:ordinals)result.add(index.anchor(index.mainParagraph(ordinal).getId()));return result;}
    private DocxEditResult apply(byte[] source,NativeLayoutPlan plan,List<Edit> edits){return editor.applyWithParagraphBookmarks(source,new SourceEditBatch(editor.inspect(source).getSourceSha256(),edits),Map.of(),false,plan);}

    @Test void cellHeadingChainAndBodyWidowsChangeOnlyVerifiedSwitchesAndMarkers()throws Exception {
        byte[] source=fixture("");TemplateIndex index=editor.inspect(source);DocxEditResult result=apply(source,plan(index,anchors(index,1,2,3),anchors(index,4)),List.of());
        assertEquals(4,result.getNativeLayoutLedger().size());for(NativeLayoutChange c:result.getNativeLayoutLedger()){assertEquals("APPLIED",c.getStatus());assertNotNull(c.getLayoutId());}
        for(int i:List.of(1,2,3))assertTrue(result.getIndex().mainParagraph(i).hasNativeKeepNext(),"CELL_HEADING_REQUIRES_KEEP_NEXT");
        assertTrue(result.getIndex().mainParagraph(4).hasNativeWidowControl(),"BODY_REQUIRES_WIDOW_PROTECTION");
        assertEquals(index.mainParagraph(5).getSha256(),result.getIndex().mainParagraph(5).getSha256());assertEquals(index.mainParagraph(6).getSha256(),result.getIndex().mainParagraph(6).getSha256());
        Map<String,byte[]> before=unzip(source),after=unzip(result.getDocxBytes());assertArrayEquals(before.get("word/media/opaque.bin"),after.get("word/media/opaque.bin"));Document a=xml(before.get(MAIN)),b=xml(after.get(MAIN));
        Set<String> ids=new HashSet<>();for(NativeLayoutChange c:result.getNativeLayoutLedger()){NodeList markers=b.getElementsByTagNameNS(W,"bookmarkStart");for(int i=0;i<markers.getLength();i++){Element e=(Element)markers.item(i);if(c.getLayoutId().equals(e.getAttributeNS(W,"name")))ids.add(e.getAttributeNS(W,"id"));}}
        for(String tag:List.of("bookmarkStart","bookmarkEnd")){List<Element> remove=new ArrayList<>();NodeList nodes=b.getElementsByTagNameNS(W,tag);for(int i=0;i<nodes.getLength();i++)if(ids.contains(((Element)nodes.item(i)).getAttributeNS(W,"id")))remove.add((Element)nodes.item(i));for(Element n:remove)n.getParentNode().removeChild(n);}
        for(int i:List.of(1,2,3,4)){Element original=paragraph(a,i),generated=paragraph(b,i);for(Element p:direct(generated,"pPr"))generated.removeChild(p);for(Element p:direct(original,"pPr"))generated.insertBefore(b.importNode(p,true),generated.getFirstChild());}
        assertEquals(shape(a.getDocumentElement()),shape(b.getDocumentElement()),"Native text, formatting, table topology and all other XML remain exact");
    }
    @Test void realHardPageBreakAndSectionControlsRemainProtected()throws Exception {
        for(String control:List.of("<w:br w:type=\"page\"/>","</w:r><w:pPr><w:sectPr/></w:pPr><w:r>","</w:r><w:pPr><w:pageBreakBefore/></w:pPr><w:r>")){byte[] source=fixture(control);TemplateIndex index=editor.inspect(source);assertThrows(EditException.class,()->apply(source,plan(index,anchors(index,1),List.of()),List.of()));}
    }
    @Test void cellFlowRejectsForeignSourceStaleAnchorBodyScopeAndDuplicateControls()throws Exception {
        byte[] source=fixture("");TemplateIndex index=editor.inspect(source);NativeLayoutPlan wrong=new NativeLayoutPlan("wrong","test",List.of(),List.of()).withCellParagraphFlow(anchors(index,1),List.of());assertEquals("LAYOUT_SOURCE_HASH_MISMATCH",assertThrows(EditException.class,()->apply(source,wrong,List.of())).getCode());
        TemplateIndex other=editor.inspect(fixture("<w:t>Changed</w:t>"));assertEquals("LAYOUT_ANCHOR_MISMATCH",assertThrows(EditException.class,()->apply(source,plan(index,anchors(other,1),List.of()),List.of())).getCode());
        assertEquals("LAYOUT_CELL_FLOW_SCOPE_CHANGED",assertThrows(EditException.class,()->apply(source,plan(index,anchors(index,6),List.of()),List.of())).getCode());
        Map<String,byte[]> parts=unzip(source);parts.put(MAIN,new String(parts.get(MAIN),StandardCharsets.UTF_8).replace("<w:widowControl w:val=\"0\"/>","<w:widowControl w:val=\"0\"/><w:widowControl/>").getBytes(StandardCharsets.UTF_8));byte[] duplicate=zip(parts);TemplateIndex duplicated=editor.inspect(duplicate);assertEquals("LAYOUT_CELL_FLOW_SCOPE_CHANGED",assertThrows(EditException.class,()->apply(duplicate,plan(duplicated,List.of(),anchors(duplicated,4)),List.of())).getCode());
    }
    @Test void authorizedTitleTextEditsSurviveAndRemovedOrClearedTargetsSkip()throws Exception {
        byte[] source=fixture("");TemplateIndex index=editor.inspect(source);Paragraph heading=index.mainParagraph(1);DocxEditResult edited=apply(source,plan(index,anchors(index,1),anchors(index,4)),List.of(Edit.replaceSpan("authorized-title",heading,0,heading.getText().length(),heading.getText(),"Adopted SCT title"),Edit.clearParagraph("authorized-clear",index.mainParagraph(4))));
        assertEquals("Adopted SCT title",edited.getIndex().mainParagraph(1).getText());assertTrue(edited.getIndex().mainParagraph(1).hasNativeKeepNext());assertEquals("SKIPPED_CLEARED",edited.getNativeLayoutLedger().get(1).getStatus());assertFalse(edited.getIndex().mainParagraph(4).hasNativeWidowControl());
        DocxEditResult removed=apply(source,plan(index,anchors(index,1),List.of()),List.of(Edit.deleteParagraph("authorized-remove",heading)));assertEquals("SKIPPED_REMOVED",removed.getNativeLayoutLedger().get(0).getStatus());
    }
    @Test void savedLayoutLedgerRejectsChangedSwitchAndMissingPersistentMarker()throws Exception {
        byte[] source=fixture("");TemplateIndex index=editor.inspect(source);DocxEditResult saved=apply(source,plan(index,anchors(index,1),anchors(index,4)),List.of());Map<String,Object> info=DraftNativeLayout.info("SCT","libreoffice",saved);String ledger=JsonUtils.write(DraftNativeLayout.ledger(saved,info));
        Map<String,byte[]> parts=unzip(saved.getDocxBytes());String text=new String(parts.get(MAIN),StandardCharsets.UTF_8);
        for(String replacement:List.of(text.replace("<w:keepNext w:val=\"1\"/>","<w:keepNext w:val=\"0\"/>"),text.replace("<w:widowControl w:val=\"1\"/>","<w:widowControl w:val=\"0\"/>"),text.replace(saved.getNativeLayoutLedger().get(0).getLayoutId(),"FOREIGN_MARKER"))){Map<String,byte[]> changed=new LinkedHashMap<>(parts);changed.put(MAIN,replacement.getBytes(StandardCharsets.UTF_8));byte[] bytes=zip(changed);DocxEditResult current=editor.apply(bytes,new SourceEditBatch(editor.inspect(bytes).getSourceSha256(),List.of()));Map<String,Object> row=DraftBusinessRules.asMap(DraftNativeLayout.bodyLedger(ledger,current).get(0));assertEquals("unavailable",row.get("status"));}
    }
    @Test void actualSctRegistryCoversAllConfirmedHeadingsLeadInsAndBodyParagraphs()throws Exception {
        Path dir=Paths.get(Objects.requireNonNull(System.getProperty("consense.acceptance.sourceDir")));byte[] source=Files.readAllBytes(dir.resolve("02_Special Conditions of Tender (SCT).docx"));TemplateIndex index=editor.inspect(source);NativeLayoutPlan plan=DraftNativeLayout.plan(index,"SCT","libreoffice");assertNotNull(plan);DocxEditResult result=apply(source,plan,List.of());
        List<NativeLayoutChange> keep=result.getNativeLayoutLedger().stream().filter(c->c.getAction().equals("keep_verified_cell_paragraph_with_next")).toList(),widows=result.getNativeLayoutLedger().stream().filter(c->c.getAction().equals("keep_verified_cell_paragraph_widows")).toList();assertEquals(218,keep.size());assertEquals(231,widows.size());for(NativeLayoutChange c:keep){assertEquals("APPLIED",c.getStatus());assertTrue(result.getIndex().paragraph(c.getGeneratedId()).hasNativeCellKeepNext());}for(NativeLayoutChange c:widows){assertEquals("APPLIED",c.getStatus());assertTrue(result.getIndex().paragraph(c.getGeneratedId()).hasNativeWidowControl());}
        Set<String> kept=new HashSet<>(),ordinary=new HashSet<>();for(NativeLayoutChange c:keep)kept.add(c.getSource().getId());for(NativeLayoutChange c:widows)ordinary.add(c.getSource().getId());assertEquals(218,kept.size());assertEquals(231,ordinary.size());assertTrue(Collections.disjoint(kept,ordinary));
        for(int ordinal:new int[]{58,100,159,218,229,259,309,375,392,425,478,508,516,533,550,569,585,615,665,705,745,782,839,883,919,958,966,1015,1048,1065,1074,1104,1118,1141,1176,1197,1205,1211})assertTrue(kept.contains(index.mainParagraph(ordinal).getId()),"Every one of the38 reviewed native underlined SCT title instances is required");
        for(int ordinal:new int[]{5,67,70,83,109,130,237,240,266,273,320,396,428,486,596,642,672,685,695,722,786,789,805,849,862,886,938,970,1025,1078,1089,1145,1215,103,261,311,618,668,708,843,112,162,276,325,164,327,598,620,845,885})assertTrue(kept.contains(index.mainParagraph(ordinal).getId()),"Every reviewed source lead-in and four native empty-body bridges is required");
        for(int ordinal:new int[]{102,161,163,518,1105}){assertFalse(kept.contains(index.mainParagraph(ordinal).getId()));assertFalse(ordinary.contains(index.mainParagraph(ordinal).getId()),"Known cleared authoring guidance stays outside paragraph flow scope");}
        assertNull(DraftNativeLayout.plan(index,"SCT","unknown-renderer"));assertNull(DraftNativeLayout.plan(index,"NTT","libreoffice"));assertEquals(index.getEntryHashes().get("word/styles.xml"),result.getIndex().getEntryHashes().get("word/styles.xml"));
    }
    @Test void exactNativeBodyEmptyBridgeKeepsBothControlsAndNonemptyBodyStaysRejected()throws Exception {
        byte[] source=fixture("");Map<String,byte[]> entries=unzip(source);String original=new String(entries.get(MAIN),StandardCharsets.UTF_8);entries.put(MAIN,original.replace("<w:p><w:r><w:t>Outside body paragraph</w:t></w:r></w:p>","<w:p/>").getBytes(StandardCharsets.UTF_8));byte[] empty=zip(entries);TemplateIndex index=editor.inspect(empty);DocxEditResult result=apply(empty,plan(index,anchors(index,6),List.of()),List.of());assertTrue(result.getIndex().mainParagraph(6).hasNativeCellKeepNext());assertEquals("APPLIED",result.getNativeLayoutLedger().get(0).getStatus());TemplateIndex nonempty=editor.inspect(source);assertEquals("LAYOUT_CELL_FLOW_SCOPE_CHANGED",assertThrows(EditException.class,()->apply(source,plan(nonempty,anchors(nonempty,6),List.of()),List.of())).getCode());
    }
    @Test void savedHeadingFlowLosesItsClaimWhenOnlyItsWidowProtectionChanges()throws Exception {
        byte[] source=fixture("");TemplateIndex index=editor.inspect(source);DocxEditResult saved=apply(source,plan(index,anchors(index,1),List.of()),List.of());Map<String,Object> info=DraftNativeLayout.info("SCT","libreoffice",saved);String ledger=JsonUtils.write(DraftNativeLayout.ledger(saved,info));Map<String,byte[]> entries=unzip(saved.getDocxBytes());String original=new String(entries.get(MAIN),StandardCharsets.UTF_8);assertTrue(original.contains("<w:widowControl w:val=\"1\"/>"));entries.put(MAIN,original.replace("<w:widowControl w:val=\"1\"/>","<w:widowControl w:val=\"0\"/>").getBytes(StandardCharsets.UTF_8));byte[] bytes=zip(entries);DocxEditResult current=editor.apply(bytes,new SourceEditBatch(editor.inspect(bytes).getSourceSha256(),List.of()));Map<String,Object> row=DraftBusinessRules.asMap(DraftNativeLayout.bodyLedger(ledger,current).get(0));assertEquals("unavailable",row.get("status"));assertTrue(DraftBusinessRules.list(row.get("actions")).stream().map(DraftBusinessRules::asMap).anyMatch(a->"CHANGED_LAYOUT_CONTROL".equals(a.get("status"))));
    }

}
