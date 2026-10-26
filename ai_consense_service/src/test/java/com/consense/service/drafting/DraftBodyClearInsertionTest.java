package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import com.consense.web.dto.DraftingDtos.*;
import java.io.ByteArrayInputStream;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.*;
import static org.junit.jupiter.api.Assertions.*;

/** Same body-save DTO composition as SCT: clear/replace one bound paragraph and insert siblings. */
class DraftBodyClearInsertionTest {
    private static final DocxTemplateEditor EDITOR=new DocxTemplateEditor();
    private static final List<String> SEVEN=Arrays.asList("(d) Additional tender submissions:","Submit a pedestrian diversion plan.","Submit a vibration monitoring proposal.","The final foundation design remains subject to checking and approval.","(e) PSE endorsed submissions:","Submit PSE connection details.","Submit PSE lifting and temporary stability calculations.");
    private static final List<String> THREE=Arrays.asList("Book seven working days ahead.","At most four visitors with PPE.","Stay with the escort and outside protected areas.");
    private static final String TABLE="<w:tbl><w:tr><w:tc><w:p><w:pPr><w:ind w:left=\"240\"/></w:pPr><w:bookmarkStart w:id=\"900\" w:name=\"guideReceipt\"/><w:r><w:rPr><w:b/></w:rPr><w:t>Guidance anchor</w:t></w:r><w:bookmarkEnd w:id=\"900\"/></w:p></w:tc></w:tr><w:tr><w:tc><w:p><w:r><w:t>Protected SCT6(2) obligation</w:t></w:r></w:p><w:p><w:r><w:t>Inspection anchor</w:t></w:r></w:p></w:tc></w:tr></w:tbl>";
    private static Paragraph paragraph(TemplateIndex index,String text){return index.getMainParagraphs().stream().filter(p->p.getText().equals(text)).findFirst().orElseThrow(AssertionError::new);}
    private static List<String> texts(TemplateIndex index){List<String> result=new ArrayList<>();for(Paragraph p:index.getMainParagraphs())result.add(p.getText());return result;}

    @Test void oneBodySaveClearsGuidanceAndInsertsSevenWhileAnotherBlockReplacesAndInsertsThree()throws Exception {
        DraftBodyParagraphRemovalTest.Saved s=new DraftBodyParagraphRemovalTest.Saved(TABLE);
        byte[] immutable=s.original.getDocxBytes();TemplateIndex before=EDITOR.inspect(immutable);
        BlockPatch clear=s.block("Guidance anchor",null);clear.setText("");clear.setInsertAfter(SEVEN);
        BlockPatch replace=s.block("Inspection anchor",null);replace.setText("Reviewed inspection period; end date awaits confirmation.");replace.setInsertAfter(THREE);
        DraftDocumentVO saved=s.save(s.patch(clear,replace));assertNotEquals(s.original.getId(),saved.getRevisionId());assertArrayEquals(immutable,s.original.getDocxBytes());
        TemplateIndex after=EDITOR.inspect(s.current().getDocxBytes());List<String> expected=new ArrayList<>();expected.add("Retained before");expected.add("");expected.addAll(SEVEN);expected.add("Protected SCT6(2) obligation");expected.add(replace.getText());expected.addAll(THREE);expected.add("Retained after");assertEquals(expected,texts(after));
        assertEquals(before.getMainParagraphs().size()+10,after.getMainParagraphs().size());
        for(String protectedText:Arrays.asList("Retained before","Protected SCT6(2) obligation","Retained after"))assertEquals(paragraph(before,protectedText).getSha256(),paragraph(after,protectedText).getSha256());
        assertArrayEquals(DraftBodyParagraphRemovalTest.entry(immutable,"word/media/opaque.bin"),DraftBodyParagraphRemovalTest.entry(s.current().getDocxBytes(),"word/media/opaque.bin"));
        List<Map<String,Object>> bindings=DraftDocumentBindings.read(s.current().getBindingsJson());
        Map<String,Object> cleared=bindings.stream().filter(row->clear.getBindingId().equals(row.get("bindingId"))).findFirst().orElseThrow(AssertionError::new);assertEquals("body_edited",cleared.get("applicationStatus"));assertNotEquals("body_deleted",cleared.get("applicationStatus"));assertEquals("",cleared.get("text"));
        assertEquals(7,bindings.stream().filter(row->"body_added".equals(row.get("applicationStatus"))&&clear.getBindingId().equals(row.get("parentBindingId"))).count());
        assertEquals(3,bindings.stream().filter(row->"body_added".equals(row.get("applicationStatus"))&&replace.getBindingId().equals(row.get("parentBindingId"))).count());
    }

    @Test void nonemptyReplacementAndInsertAfterAlreadyUseTheSameBodySaveContract()throws Exception {
        DraftBodyParagraphRemovalTest.Saved s=new DraftBodyParagraphRemovalTest.Saved(TABLE);BlockPatch patch=s.block("Inspection anchor",null);patch.setText("Reviewed period.");patch.setInsertAfter(THREE);s.save(s.patch(patch));
        assertEquals(Arrays.asList("Retained before","Guidance anchor","Protected SCT6(2) obligation","Reviewed period.",THREE.get(0),THREE.get(1),THREE.get(2),"Retained after"),texts(EDITOR.inspect(s.current().getDocxBytes())));
    }

    @Test void plainParagraphClearAndSiblingInsertionAreOrderIndependent()throws Exception {
        byte[] source=DraftBodyParagraphRemovalTest.fixture(TABLE);TemplateIndex index=EDITOR.inspect(source);Paragraph p=paragraph(index,"Guidance anchor");
        Edit clear=Edit.clearParagraph("clear",p),insert=Edit.insertParagraphs("insert",p,true,THREE,p);
        byte[] first=EDITOR.apply(source,new SourceEditBatch(index.getSourceSha256(),Arrays.asList(clear,insert))).getDocxBytes();
        byte[] second=EDITOR.apply(source,new SourceEditBatch(index.getSourceSha256(),Arrays.asList(insert,clear))).getDocxBytes();assertArrayEquals(first,second);
        assertEquals("",EDITOR.inspect(first).getMainParagraphs().get(1).getText());assertEquals(index.mainParagraph(1).getSha256(),EDITOR.inspect(first).mainParagraph(1).getSha256());
        TemplateIndex after=EDITOR.inspect(first);for(String line:THREE)assertEquals(p.getRuns().get(0).getPropertiesSha256(),paragraph(after,line).getRuns().get(0).getPropertiesSha256());
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        String namespace="http://schemas.openxmlformats.org/wordprocessingml/2006/main";NodeList paragraphs=factory.newDocumentBuilder().parse(new ByteArrayInputStream(DraftBodyParagraphRemovalTest.entry(first,"word/document.xml"))).getElementsByTagNameNS(namespace,"p");int checked=0;
        for(int i=0;i<paragraphs.getLength();i++){Element node=(Element)paragraphs.item(i);if(THREE.contains(node.getTextContent())){NodeList ind=node.getElementsByTagNameNS(namespace,"ind");assertEquals(1,ind.getLength());assertEquals("240",((Element)ind.item(0)).getAttributeNS(namespace,"left"));checked++;}}assertEquals(3,checked);
    }

    @Test void plainClearReceiptsRetainTextClearedMeaningWithAndWithoutSiblingInsertion()throws Exception {
        byte[] source=DraftBodyParagraphRemovalTest.fixture(TABLE);TemplateIndex index=EDITOR.inspect(source);Paragraph p=paragraph(index,"Guidance anchor");
        for(boolean withInsertion:Arrays.asList(false,true)) {
            List<Edit> edits=new ArrayList<>();edits.add(Edit.clearParagraph("clear",p));if(withInsertion)edits.add(Edit.insertParagraphs("insert",p,true,THREE,p));
            DocxEditResult result=EDITOR.apply(source,new SourceEditBatch(index.getSourceSha256(),edits));FeatureImpact impact=result.getAffectedFeatures().stream().filter(row->"guideReceipt".equals(row.getFeature().getName())).findFirst().orElseThrow(AssertionError::new);
            assertEquals("TEXT_CLEARED_ANCHOR_PRESERVED",impact.getStatus());assertTrue(impact.getOperationIds().contains("clear"));
            assertTrue(result.getIndex().getFeatures().stream().anyMatch(feature->"guideReceipt".equals(feature.getName())));
        }
    }

    @Test void deletedAnchorsNumberingChangesAndAncestorScopesStillCannotComposeWithInsertion()throws Exception {
        byte[] source=DraftBodyParagraphRemovalTest.fixture(TABLE);TemplateIndex index=EDITOR.inspect(source);Paragraph p=paragraph(index,"Guidance anchor");Edit insert=Edit.insertParagraphs("insert",p,true,THREE,p);
        for(Edit unsafe:Arrays.asList(Edit.deleteParagraph("delete",p),Edit.removeNodes("remove",Collections.singletonList(index.anchor(p.getId()))),Edit.clearParagraphAndNumbering("numbering",p),Edit.clearEditorialCell("cell",index.anchor(p.getCellId())))) {
            EditException failure=assertThrows(EditException.class,()->EDITOR.apply(source,new SourceEditBatch(index.getSourceSha256(),Arrays.asList(unsafe,insert))));assertEquals("OVERLAPPING_OPERATIONS",failure.getCode());
            assertArrayEquals(source,EDITOR.apply(source,new SourceEditBatch(index.getSourceSha256(),Collections.emptyList())).getDocxBytes());
        }
    }

    @Test void fieldClearProtectionStillRejectsTheWholeBodySaveBeforeAnyRevisionIsSaved()throws Exception {
        DraftBodyParagraphRemovalTest.Saved s=new DraftBodyParagraphRemovalTest.Saved("<w:p><w:fldSimple w:instr=\"PAGE\"><w:r><w:t>Protected field</w:t></w:r></w:fldSimple></w:p>");BlockPatch patch=s.block("Protected field",null);patch.setText("");patch.setInsertAfter(THREE);
        assertTrue(assertThrows(BizException.class,()->s.save(s.patch(patch))).getMessage().contains("FIELD_TEXT_EDIT_UNSUPPORTED"));s.unchanged();
    }
}
