package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor;
import java.io.ByteArrayOutputStream;
import java.util.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import static com.consense.service.drafting.DraftReviewGraph.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual package bindings, planning and compilation; no parser, model or project facts are supplied. */
class DraftReviewEditPlacementTest {
    private byte[] docx(String... values) throws Exception {
        try(XWPFDocument document=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            for(String value:values)document.createParagraph().createRun().setText(value);
            document.write(out);return out.toByteArray();
        }
    }
    private Map<String,Object> span(byte[] bytes,int ordinal,int start,int end) {
        DocxTemplateEditor.TemplateIndex index=new DocxTemplateEditor().inspect(bytes);
        DocxTemplateEditor.Paragraph paragraph=index.mainParagraph(ordinal);
        return obj("paragraphId",paragraph.getId(),"paragraphSha256",paragraph.getSha256(),"sourceSha256",index.getSourceSha256(),"channel","editableText","start",start,"end",end,"expectedText",paragraph.getText().substring(start,end));
    }
    private Map<String,Object> target(byte[] bytes,String action,Map<String,Object>... spans) {
        List<String> pieces=new ArrayList<>();for(Map<String,Object> span:spans)pieces.add(text(span.get("expectedText")));
        Map<String,Object> target=obj("id","target","docId","ntt","action",action,"text",String.join("\n",pieces),"valueSourceId","wording","native",obj("sourceSha256",DraftTemplateRouting.sha(bytes),"spans",Arrays.asList(spans)));
        target.put("nativeBinding",JsonUtils.mapper().convertValue(DraftReviewSources.bindTarget(bytes,target),Map.class));return target;
    }
    private Map<String,Object> graph(Map<String,Object> target) {
        Map<String,Object> node=obj("id","wording","nameEn","Reviewed wording","type",obj("kind","text"),"role",obj("kind","input"),"status","confirmed","docIds",Collections.singletonList("ntt"),"editTargets",Collections.singletonList(target));
        return obj("graph",obj("variables",Collections.singletonList(node)),"selectedDocuments",obj("NTT","ntt"));
    }
    private String paragraph(DocxTemplateEditor.DocxEditResult result,int ordinal){return result.getIndex().mainParagraph(ordinal).getText();}

    @Test void exactExplicitInsertionUsesTheSameBindingForPlanAndGeneratedPackage() throws Exception {
        byte[] bytes=docx(" Bill No. 3 \u2013","Unchanged");Map<String,Object> anchor=span(bytes,1,13,13);anchor.put("operation","insert");
        Map<String,Object> target=target(bytes,"insert",anchor),graph=graph(target);
        assertEquals("bound",map(target.get("nativeBinding")).get("status"));
        Map<String,Object> unknown=plan(graph,Collections.emptyMap());assertEquals("pending",map(list(unknown.get("actions")).get(0)).get("action"));
        Map<String,Object> plan=plan(graph,obj("wording"," Foundation works"));
        assertEquals("insert",map(list(plan.get("actions")).get(0)).get("action"));assertTrue(list(plan.get("unresolved")).isEmpty());
        DocxTemplateEditor.DocxEditResult result=compile(bytes,"NTT",graph,plan,Collections.emptyMap(),null);
        assertEquals(" Bill No. 3 \u2013 Foundation works",paragraph(result,1));assertEquals("Unchanged",paragraph(result,2));
        assertEquals(DocxTemplateEditor.EditType.INSERT_TEXT,result.getLedger().get(0).getType());
    }

    @Test void zeroLengthReplacementAndUndeclaredInsertionNeverAcquireEditAuthority() throws Exception {
        byte[] bytes=docx("Bill No. 1 -");Map<String,Object> anchor=span(bytes,1,12,12);
        assertEquals("unsafe",map(target(bytes,"replace",anchor).get("nativeBinding")).get("status"));
        assertEquals("unsafe",map(target(bytes,"insert",anchor).get("nativeBinding")).get("status"));
        anchor.put("operation","insert");assertEquals("unsafe",map(target(bytes,"fill",anchor).get("nativeBinding")).get("status"));
        Map<String,Object> target=target(bytes,"insert",anchor);anchor.put("paragraphSha256","wrong");
        assertEquals("NATIVE_PARAGRAPH_HASH_MISMATCH",DraftReviewSources.bindTarget(bytes,target).path("reason").asText());
    }

    @Test void selectedReplacementSpanReceivesTextOnceAndOnlyOtherSelectedTextIsCleared() throws Exception {
        byte[] bytes=docx("prefix FIRST suffix","left SECOND right","Unchanged");
        for(int placement:Arrays.asList(0,1)) {
            Map<String,Object> target=target(bytes,"replace",span(bytes,1,7,12),span(bytes,2,5,11));target.put("replacementPlacement",obj("spanIndex",placement));
            Map<String,Object> graph=graph(target),plan=plan(graph,obj("wording","NEW"));
            assertEquals("amend",map(list(plan.get("actions")).get(0)).get("action"));assertTrue(list(plan.get("unresolved")).isEmpty());
            DocxTemplateEditor.DocxEditResult output=compile(bytes,"NTT",graph,plan,Collections.emptyMap(),null);
            assertEquals(placement==0?"prefix NEW suffix":"prefix  suffix",paragraph(output,1));
            assertEquals(placement==1?"left NEW right":"left  right",paragraph(output,2));assertEquals("Unchanged",paragraph(output,3));
        }
    }

    @Test void missingFractionalOrOutOfRangeReplacementPlacementRemainsUnresolved() throws Exception {
        byte[] bytes=docx("FIRST","SECOND");Map<String,Object> target=target(bytes,"replace",span(bytes,1,0,5),span(bytes,2,0,6)),graph=graph(target);
        for(Object index:Arrays.asList(null,-1,2,0.5,"0")) {
            target.put("replacementPlacement",obj("spanIndex",index));Map<String,Object> plan=plan(graph,obj("wording","NEW"));
            assertEquals("pending",map(list(plan.get("actions")).get(0)).get("action"));assertThrows(BizException.class,()->compile(bytes,"NTT",graph,plan,Collections.emptyMap(),null));
        }
    }

    @Test void duplicateInsertionPointsAndPointsInsideAnotherEditAreRejected() throws Exception {
        byte[] bytes=docx("ABCDE");Map<String,Object> point=span(bytes,1,2,2);point.put("operation","insert");
        assertEquals("unsafe",map(target(bytes,"insert",point,point).get("nativeBinding")).get("status"));
        Map<String,Object> insert=target(bytes,"insert",point),replace=target(bytes,"replace",span(bytes,1,1,4));
        Map<String,Object> action=obj("id","insert","document","NTT","action","insert","value","X","nativeBinding",insert.get("nativeBinding"),"target",insert);
        Map<String,Object> other=obj("id","replace","document","NTT","action","amend","value","Y","nativeBinding",replace.get("nativeBinding"),"target",replace);
        assertThrows(BizException.class,()->compile(bytes,"NTT",obj(),obj("actions",Arrays.asList(action,other)),Collections.emptyMap(),null));
    }

    @Test void explicitInsertionCanFillAnEmptyParagraphWithoutDeletingItsNeighbours() throws Exception {
        byte[] bytes=docx("Before","","After");Map<String,Object> point=span(bytes,2,0,0);point.put("operation","insert");Map<String,Object> target=target(bytes,"insert",point),graph=graph(target);
        DocxTemplateEditor.DocxEditResult output=compile(bytes,"NTT",graph,plan(graph,obj("wording","Inserted")),Collections.emptyMap(),null);
        assertEquals("Before",paragraph(output,1));assertEquals("Inserted",paragraph(output,2));assertEquals("After",paragraph(output,3));
    }
}
