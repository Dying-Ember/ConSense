package com.consense.service.drafting;

import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import java.io.ByteArrayOutputStream;
import java.util.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import static com.consense.service.drafting.DraftReviewGraph.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure planning and synthetic native-edit contracts; no model or frontend acceptance. */
class DraftExecutionBridgeTest {
    @Test void confirmedFixedDefinitionAndAdoptedValueRecoverActionsWithoutGraphTargets() {
        Map<String,Object> graph=confirmedBq(),plan=plan(id(graph,"electronicTendering"),"Hardcopy");
        assertTrue(nodes(graph).stream().allMatch(node->list(node.get("editTargets")).isEmpty()));
        DraftExecutionBridge.prepare(graph,plan);
        assertEquals("delete",action(plan,"NTT-2-L10PRO").get("action"));assertEquals("retain",action(plan,"NTT-2-PAPER").get("action"));
        assertEquals(Collections.singletonList(id(graph,"electronicTendering")),action(plan,"NTT-2-L10PRO").get("inputKeys"));assertEquals(Collections.singletonList("electronicTendering"),action(plan,"NTT-2-L10PRO").get("catalogInputKeys"));
        assertEquals("Hardcopy",map(plan.get("fixedRuleValues")).get("electronicTendering"));
        assertEquals(list(DraftBusinessRules.plan(Collections.emptyMap()).get("actions")).size(),map(plan.get("fixedRuleCoverage")).get("expectedActions"));
        assertEquals("partial_requires_review",map(plan.get("fixedRuleCoverage")).get("status"));assertFalse(list(plan.get("unresolved")).isEmpty());
    }

    @Test void visibleValuesOrDescriptorAloneNeverAdoptAnAnswerOrConfirmADefinition() {
        Map<String,Object> graph=DraftFixedBaseline.restore(blank()),plan=plan(id(graph,"electronicTendering"),"Hardcopy");
        DraftExecutionBridge.prepare(graph,plan);assertEquals("pending",action(plan,"NTT-2-L10PRO").get("action"));assertFalse(map(plan.get("fixedRuleValues")).containsKey("electronicTendering"));
        graph=confirmedBq();plan=plan(null,null);map(plan.get("inputValues")).put(id(graph,"electronicTendering"),"Hardcopy");
        DraftExecutionBridge.prepare(graph,plan);assertEquals("pending",action(plan,"NTT-2-L10PRO").get("action"));assertFalse(map(plan.get("fixedRuleValues")).containsKey("electronicTendering"));
    }

    @Test void changedFixedTargetAndNewCustomUnboundTargetCannotBorrowOldScopes() {
        Map<String,Object> graph=confirmedBq(),node=field(graph,"electronicTendering");node.put("editTargets",Collections.singletonList(obj("id","changed","docId","n","action","delete","needsRebind",true)));
        Map<String,Object> plan=plan(id(graph,"electronicTendering"),"Hardcopy");DraftExecutionBridge.prepare(graph,plan);
        assertEquals("pending",action(plan,"NTT-2-L10PRO").get("action"));assertEquals("FixedDefinitionCustomized",action(plan,"sct-issue-alternative").get("executionIssue"));
        assertFalse(map(plan.get("fixedRuleValues")).containsKey("electronicTendering"));
        graph=confirmedBq();Map<String,Object> custom=custom();custom.put("editTargets",Collections.singletonList(obj("id","unknown-current-range","docId","n","action","delete","needsRebind",true)));((List<Object>)state(graph).get("variables")).add(custom);
        plan=plan(id(graph,"electronicTendering"),"Hardcopy");DraftExecutionBridge.prepare(graph,plan);
        assertTrue(list(plan.get("actions")).stream().map(DraftReviewGraph::map).filter(a->"NTT".equals(a.get("document"))).allMatch(a->"pending".equals(a.get("action"))));
        assertEquals("FixedGraphUnknownScope",action(plan,"NTT-2-L10PRO").get("executionIssue"));assertEquals("amend",action(plan,"sct-issue-alternative").get("action"));
    }

    @Test void unboundReadingReferenceIsNotAnUnlocatedEditAndOtherSccCannotBorrowWordRules() {
        Map<String,Object> graph=confirmedBq(),custom=custom();custom.put("sourceAnchors",Collections.singletonList(obj("docId","n","text","A reading reference, not an edit.")));((List<Object>)state(graph).get("variables")).add(custom);
        Map<String,Object> plan=plan(id(graph,"electronicTendering"),"Hardcopy");DraftExecutionBridge.prepare(graph,plan);assertEquals("delete",action(plan,"NTT-2-L10PRO").get("action"));
        for(Object raw:list(graph.get("sources")))if("SCC".equals(map(raw).get("fileKey")))map(raw).put("sourceSha256",DraftPdfSccReference.SOURCE_SHA);
        plan=plan(id(graph,"electronicTendering"),"Hardcopy");DraftExecutionBridge.prepare(graph,plan);assertEquals("pending",action(plan,"NTT-2-L10PRO").get("action"));assertTrue(map(plan.get("fixedRuleValues")).isEmpty());
    }

    @Test void conflictingCompilersDeferBothActionsAndKeepIndependentEditsOnTheSameOriginal() throws Exception {
        byte[] original=synthetic();DocxTemplateEditor editor=new DocxTemplateEditor();TemplateIndex index=editor.inspect(original);Paragraph paragraph=index.mainParagraph(1);
        Map<String,Object> fixed=obj("id","fixed","document","NTT","executionKind","fixed_catalog","action","delete"),graph=obj("id","graph","document","NTT","action","amend"),safe=obj("id","safe","document","NTT","action","amend");
        Map<String,Object> plan=obj("actions",new ArrayList<>(Arrays.asList(fixed,graph,safe)),"unresolved",new ArrayList<>(),"fixedRuleCoverage",new LinkedHashMap<>());
        SourceEditBatch merged=DraftExecutionBridge.mergeBatches(original,"NTT",plan,batch(index,Edit.replaceSpan("fixed-0",paragraph,0,5,"Alpha","")),batch(index,Edit.replaceSpan("graph-0",paragraph,0,5,"Alpha","Gamma"),Edit.replaceSpan("safe-0",paragraph,6,10,"Beta","B")),batch(index));
        assertEquals("pending",fixed.get("action"));assertEquals("pending",graph.get("action"));assertEquals("amend",safe.get("action"));assertEquals(1,merged.getEdits().size());
        assertEquals("Alpha B Tail",editor.apply(original,merged).getIndex().mainParagraph(1).getText());
        assertEquals("Alpha Beta Tail",editor.inspect(original).mainParagraph(1).getText());
        assertTrue(list(plan.get("unresolved")).stream().map(DraftReviewGraph::map).anyMatch(issue->"FixedGraphEditConflict".equals(issue.get("kind"))&&list(issue.get("actionIds")).containsAll(Arrays.asList("fixed","graph"))));
    }

    @Test void explicitGraphRetentionProtectsItsRangeWithoutApplyingTheProtectionProjection() throws Exception {
        byte[] original=synthetic();TemplateIndex index=new DocxTemplateEditor().inspect(original);Paragraph paragraph=index.mainParagraph(1);
        Map<String,Object> fixed=obj("id","fixed","document","NTT","executionKind","fixed_catalog","action","delete"),keep=obj("id","keep","document","NTT","action","retain");Map<String,Object> plan=obj("actions",new ArrayList<>(Arrays.asList(fixed,keep)),"unresolved",new ArrayList<>(),"fixedRuleCoverage",new LinkedHashMap<>());
        SourceEditBatch merged=DraftExecutionBridge.mergeBatches(original,"NTT",plan,batch(index,Edit.replaceSpan("fixed-0",paragraph,0,5,"Alpha","")),batch(index),batch(index,Edit.replaceSpan("keep-0",paragraph,0,5,"Alpha","Alpha")));
        assertTrue(merged.getEdits().isEmpty());assertEquals("pending",fixed.get("action"));assertEquals("pending",keep.get("action"));
    }

    @Test void partialDefinitionsPendingActionsOrNewIssuesNeverPermitGlobalEditorialCleanup() {
        Map<String,Object> graph=confirmedBq(),plan=plan(id(graph,"electronicTendering"),"Hardcopy");DraftExecutionBridge.prepare(graph,plan);
        assertFalse(DraftExecutionBridge.editorialAllowed(graph,plan));
        List<String> roots=new ArrayList<>();for(Object id:list(map(state(graph).get("baselineRestoration")).get("fieldIds")))roots.add(text(id));graph=DraftFixedBaseline.confirmGroup(graph,roots,"Human reviewed all fixed definitions separately from project answers.");
        plan=plan(null,null);DraftExecutionBridge.prepare(graph,plan);assertFalse(DraftExecutionBridge.editorialAllowed(graph,plan));
        list(plan.get("unresolved")).clear();list(plan.get("actions")).clear();assertFalse(DraftExecutionBridge.editorialAllowed(graph,plan),"A descriptor and empty lists are not a completed fixed-action evaluation.");
        ((List<Object>)plan.get("unresolved")).add(obj("kind","NewConflict"));assertFalse(DraftExecutionBridge.editorialAllowed(graph,plan));
    }

    @Test void graphRowIdentityStaysInTheGraphWhileBqAndSorWithTheSameNumberRemainDistinct() {
        Map<String,Object> graph=DraftFixedBaseline.restore(blank());graph=DraftFixedBaseline.confirmGroup(graph,Collections.singletonList(id(graph,"billNos")),"Human reviewed the Bill list definition and business columns.");
        Map<String,Object> bq=obj("_itemId","graph-bq-row",id(graph,"billNos.number"),"1",id(graph,"billNos.description"),"Works Bill",id(graph,"billNos.type"),"BQ"),sor=obj("_itemId","graph-sor-row",id(graph,"billNos.number"),"1",id(graph,"billNos.description"),"Rates Schedule",id(graph,"billNos.type"),"SOR");
        List<Object> adopted=new ArrayList<>(Arrays.asList(bq,sor));Map<String,Object> plan=plan(id(graph,"billNos"),adopted);DraftExecutionBridge.prepare(graph,plan);
        List<?> projected=list(map(plan.get("fixedRuleValues")).get("billNos"));assertEquals(2,projected.size());assertEquals(2,DraftBusinessRules.physicalBills(projected).size());assertNotEquals(DraftBusinessRules.billIdentity(map(projected.get(0))),DraftBusinessRules.billIdentity(map(projected.get(1))));
        assertEquals("graph-bq-row",bq.get("_itemId"));assertEquals("graph-sor-row",sor.get("_itemId"));assertSame(adopted,map(plan.get("effectiveValues")).get(id(graph,"billNos")));
    }

    private static Map<String,Object> plan(String id,Object adopted){Map<String,Object> values=new LinkedHashMap<>();if(id!=null)values.put(id,adopted);return obj("actions",new ArrayList<>(),"unresolved",new ArrayList<>(),"inputValues",new LinkedHashMap<>(values),"effectiveValues",values);}
    private static Map<String,Object> confirmedBq(){Map<String,Object> graph=DraftFixedBaseline.restore(blank());return DraftFixedBaseline.confirmGroup(graph,Collections.singletonList(id(graph,"electronicTendering")),"Human reviewed the fixed BQ definition; project value is adopted separately.");}
    private static Map<String,Object> blank(){List<Object> sources=new ArrayList<>();Map<String,Object> selected=new LinkedHashMap<>();for(String file:Arrays.asList("NTT","SCT","SCC")){String id=file.equals("NTT")?"n":file.equals("SCT")?"t":"c";selected.put(file,id);sources.add(obj("id",id,"docId",id,"editionId",id,"fileKey",file,"format","docx","evidenceStatus","bound","sourceSha256",DraftFormattedRules.verifiedSourceHash(file)));}return obj("kind","consense-template-graph-v1","sources",sources,"selectedDocuments",selected,"graph",obj("variables",new ArrayList<>(),"events",new ArrayList<>(),"sourceReviews",new LinkedHashMap<>(),"blank",true));}
    private static Map<String,Object> field(Map<String,Object> graph,String key){return nodes(graph).stream().filter(node->key.equals(map(node.get("catalogAlignment")).get("baselineKey"))).findFirst().orElseThrow();}
    private static String id(Map<String,Object> graph,String key){return text(field(graph,key).get("id"));}
    private static Map<String,Object> action(Map<String,Object> plan,String id){return list(plan.get("actions")).stream().map(DraftReviewGraph::map).filter(action->id.equals(action.get("id"))).findFirst().orElseThrow();}
    private static Map<String,Object> custom(){return obj("id","custom-review","nameEn","Custom source review","type",obj("kind","boolean"),"role",obj("kind","verification"),"status","pending","docIds",Collections.singletonList("n"));}
    private static SourceEditBatch batch(TemplateIndex index,Edit...edits){return new SourceEditBatch(index.getSourceSha256(),Arrays.asList(edits));}
    private static byte[] synthetic() throws Exception {try(XWPFDocument document=new XWPFDocument();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){document.createParagraph().createRun().setText("Alpha Beta Tail");document.write(bytes);return bytes.toByteArray();}}
}
