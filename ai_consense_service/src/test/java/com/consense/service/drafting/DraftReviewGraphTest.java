package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.document.DocxTemplateEditor;
import java.io.ByteArrayOutputStream;
import java.util.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

class DraftReviewGraphTest {
    private Map<String,Object> node(String id,String type,String role){return obj("id",id,"nameZh",id,"nameEn",id,"questionEn",id,"parentId",null,"type",obj("kind",type),"role",obj("kind",role),"status","confirmed","docIds",Collections.singletonList("ntt"),"options",new ArrayList<>(),"editTargets",new ArrayList<>());}
    private Map<String,Object> graph(Map<String,Object>... nodes){return obj("kind","consense-template-graph-v1","graph",obj("variables",Arrays.asList(nodes)),"selectedDocuments",obj("NTT","ntt"));}
    private Map<String,Object> clause(String id,String op,String value){return obj("inputId",id,"operator",op,"value",value);}
    private Map<String,Object> branch(String id,String mode,Object... clauses){return obj("id",id,"mode",mode,"clauses",Arrays.asList(clauses),"scopeId","","scopeMode","");}
    private Map<String,Object> bondGraph(){
        Map<String,Object> combined=node("combined","boolean","input"),foundations=node("foundations","boolean","input"),months=node("months","number","input"),bond=node("bond","choice","derived");
        bond.put("options",Arrays.asList(obj("value","g1","labelEn","G1"),obj("value","g1a","labelEn","G1a")));
        Map<String,Object> yes=branch("long","all",clause("combined","yes",""),clause("foundations","yes",""),clause("months","ge","39"));yes.put("resultValue","g1a");
        Map<String,Object> no=branch("standard","any",clause("combined","no",""),clause("foundations","no",""),clause("months","lt","39"));no.put("resultValue","g1");
        bond.put("correction",obj("branches",Arrays.asList(yes,no)));return graph(combined,foundations,months,bond);
    }
    @Test void independentFactsAndUnknownsAreNotFlattened(){
        Map<String,Object> graph=bondGraph();validate(graph);assertEquals(3,fields(graph).size());
        assertEquals("g1a",map(plan(graph,obj("combined",true,"foundations",true,"months",39)).get("derived")).get("bond"));
        assertEquals("g1",map(plan(graph,obj("combined",true,"months",38)).get("derived")).get("bond"));
        assertFalse(map(plan(graph,obj("combined",true,"months",39)).get("derived")).containsKey("bond"));
        assertEquals("g1",map(plan(graph,obj("combined",false,"months",100)).get("derived")).get("bond"));
    }
    @Test void scalarAndStructuredTypesPreserveFalseZeroEmptyAndWhitespace(){
        assertEquals("false",normalize(node("b","boolean","input"),"no"));assertEquals("0",normalize(node("n","number","input"),"0.0"));
        assertEquals("  exact text \n",normalize(node("t","text","input"),"  exact text \n"));
        assertEquals("",normalize(node("n","number","input"),""));
        assertThrows(BizException.class,()->normalize(node("n","number","input"),"NaN"));
        assertThrows(BizException.class,()->normalize(node("date","date","input"),"2026-02-30"));
        Map<String,Object> list=node("rows","list","input"),detail=node("name","text","input");detail.put("parentId","rows");
        Map<String,Object> enriched=fields(graph(list,detail)).get(0);
        assertEquals("[]",normalize(enriched,"[]"));assertEquals("[{\"name\":\"A\"}]",normalize(enriched,"[{\"name\":\"A\"}]"));
        assertThrows(BizException.class,()->normalize(enriched,"[{\"wrong\":\"A\"}]"));
        assertEquals(1,fields(graph(list,detail)).size());
    }
    @Test void definitionFingerprintDoesNotDependOnDisplayLabelOrTargetButTracksMeaning(){
        Map<String,Object> field=node("x","boolean","input");String before=fieldHash(field);
        field.put("nameZh","另一个显示名");field.put("editTargets",Collections.singletonList(obj("action","keep")));assertEquals(before,fieldHash(field));
        field.put("questionEn","A different project fact");assertNotEquals(before,fieldHash(field));
    }
    @Test void cyclesMissingReferencesAndOptionsAreRejected(){
        Map<String,Object> a=node("a","boolean","derived"),b=node("b","boolean","derived");
        Map<String,Object> ar=branch("a1","all",clause("b","yes","")),br=branch("b1","all",clause("a","yes",""));ar.put("resultValue","yes");br.put("resultValue","yes");
        a.put("correction",obj("branches",Collections.singletonList(ar)));b.put("correction",obj("branches",Collections.singletonList(br)));
        assertThrows(BizException.class,()->validate(graph(a,b)));assertThrows(BizException.class,()->validate(graph(a)));
        assertThrows(BizException.class,()->validate(graph(node("x","choice","input"))));
    }
    @Test void scopedConditionsAndDeclaredJoinUseOneActualRowAtATime(){
        Map<String,Object> rows=node("rows","list","input"),name=node("name","text","input"),enabled=node("enabled","boolean","input"),result=node("result","boolean","derived");name.put("parentId","rows");enabled.put("parentId","rows");
        Map<String,Object> some=branch("some","all",clause("enabled","yes",""));some.put("scopeId","rows");some.put("scopeMode","any");some.put("resultValue","yes");
        Map<String,Object> none=branch("none","all",clause("enabled","yes",""));none.put("scopeId","rows");none.put("scopeMode","none");none.put("resultValue","no");
        result.put("correction",obj("branches",Arrays.asList(some,none)));
        Map<String,Object> target=obj("docId","ntt","action","replace","valueSourceId","name","valueMode","join","listId","rows","prefix","[ ","separator"," / ","suffix"," ]","nativeBinding",obj("status","bound"));result.put("editTargets",Collections.singletonList(target));
        Map<String,Object> graph=graph(rows,name,enabled,result);
        Map<String,Object> plan=plan(graph,obj("rows",Arrays.asList(obj("name","A","enabled",true),obj("name","B","enabled",false))));
        assertEquals(true,map(plan.get("derived")).get("result"));assertEquals("[ A / B ]",map(list(plan.get("actions")).get(0)).get("value"));
        assertEquals(false,map(plan(graph,obj("rows",Collections.emptyList())).get("derived")).get("result"));
        assertFalse(map(plan(graph,Collections.emptyMap()).get("derived")).containsKey("result"));
    }
    @Test void listOwnerSelfScopeJoinsOnlyMatchingRowsAndEvaluatesDerivedChildren(){
        Map<String,Object> rows=node("rows","list","input"),name=node("name","text","input"),enabled=node("enabled","boolean","input"),derived=node("wording","text","derived");name.put("parentId","rows");enabled.put("parentId","rows");derived.put("parentId","rows");
        Map<String,Object> yes=branch("yes","all",clause("enabled","yes",""));yes.put("scopeId","rows");yes.put("scopeMode","each");Map<String,Object> no=branch("no","all",clause("enabled","no",""));no.put("scopeId","rows");no.put("scopeMode","each");
        rows.put("correction",obj("kind","rule","branches",Arrays.asList(yes,no)));Map<String,Object> dy=new LinkedHashMap<>(yes),dn=new LinkedHashMap<>(no);dy.put("resultValue","Included");dn.put("resultValue","Omitted");derived.put("correction",obj("branches",Arrays.asList(dy,dn)));
        rows.put("editTargets",Collections.singletonList(obj("id","joined","docId","ntt","action","fill","branchId","yes","valueSourceId","wording","valueMode","join","listId","rows","separator"," / ","nativeBinding",obj("status","bound"))));
        Map<String,Object> graph=graph(rows,name,enabled,derived);validate(graph);Map<String,Object> result=plan(graph,obj("rows",Arrays.asList(obj("_itemId","a","name","A","enabled",true),obj("_itemId","b","name","B","enabled",false))));Map<String,Object> action=map(list(result.get("actions")).get(0));
        assertEquals("amend",action.get("action"));assertEquals("Included",action.get("value"));assertEquals(Arrays.asList(true,false),Arrays.asList(map(list(action.get("scopeResults")).get(0)).get("matches"),map(list(action.get("scopeResults")).get(1)).get("matches")));
        assertEquals("Included",map(list(map(result.get("derivedItems")).get("wording")).get(0)).get("value"));assertFalse(map(result.get("derived")).containsKey("wording"));
        enabled.put("optional",true);Map<String,Object> pending=plan(graph,obj("rows",Arrays.asList(obj("name","A","enabled",true),obj("name","B"))));assertEquals("pending",map(list(pending.get("actions")).get(0)).get("action"));
    }
    @Test void eachEditRequiresUniqueItemIdentityAndKeepsItsOwnPlacement(){
        Map<String,Object> rows=node("rows","list","input"),name=node("name","text","input"),enabled=node("enabled","boolean","input");name.put("parentId","rows");enabled.put("parentId","rows");Map<String,Object> each=branch("case-1","all",clause("enabled","yes",""));each.put("scopeId","rows");each.put("scopeMode","each");rows.put("correction",obj("branches",Collections.singletonList(each)));
        Map<String,Object> binding=obj("status","bound","spans",Collections.singletonList(obj("paragraphId","exact-a")));Map<String,Object> placed=obj("id","a","docId","ntt","action","fill","branchId","case-1","valueSourceId","name","itemId","a","nativeBinding",binding),unplaced=new LinkedHashMap<>(placed);unplaced.put("id","unplaced");unplaced.remove("itemId");rows.put("editTargets",Arrays.asList(placed,unplaced));
        Map<String,Object> result=plan(graph(rows,name,enabled),obj("rows",Arrays.asList(obj("_itemId","a","name","Alpha","enabled",true),obj("_itemId","b","name","Beta","enabled",true))));
        assertEquals("Alpha",map(list(result.get("actions")).get(0)).get("value"));assertEquals(binding,map(list(result.get("actions")).get(0)).get("nativeBinding"));assertEquals("pending",map(list(result.get("actions")).get(1)).get("action"));
        Map<String,Object> unrelated=node("contractCode","text","input"),wrong=new LinkedHashMap<>(unplaced);wrong.put("itemKey","contractCode");wrong.put("itemValue","Tree-X");rows.put("editTargets",Collections.singletonList(wrong));assertThrows(BizException.class,()->plan(graph(rows,name,enabled,unrelated),obj("contractCode","Tree-X","rows",Collections.singletonList(obj("name","Tree-A","enabled",true)))));
        Map<String,Object> field=fields(graph(rows,name,enabled)).get(0);assertThrows(BizException.class,()->normalize(field,"[{\"_itemId\":\"a\",\"name\":\"A\",\"enabled\":true},{\"_itemId\":\"a\",\"name\":\"B\",\"enabled\":true}]"));
    }
    @Test void textEqualityDoesNotCoerceIdentifiersAndLegacySingleRuleRemainsExecutable(){
        Map<String,Object> code=node("code","text","input"),match=node("match","boolean","derived");Map<String,Object> condition=obj("kind","rule","mode","all","clauses",Collections.singletonList(clause("code","eq","1")),"resultValue","yes");match.put("correction",condition);
        assertFalse(map(plan(graph(code,match),obj("code","001")).get("derived")).containsKey("match"));assertEquals(true,map(plan(graph(code,match),obj("code","1")).get("derived")).get("match"));assertEquals("   ",normalize(code,"   "));
        code.put("type",obj("kind","list"));assertThrows(BizException.class,()->validate(graph(code,match)));
    }
    @Test void selectedSccEditionFiltersQuestionsAndDependencyClosureButKeepsSharedQuestions(){
        Map<String,Object> shared=node("shared","text","input"),word=node("word","text","input"),pdf=node("pdf","text","input"),dependent=node("dependent","boolean","input");word.put("docIds",Collections.singletonList("w"));pdf.put("docIds",Collections.singletonList("p"));dependent.put("correction",obj("branches",Collections.singletonList(branch("dep","all",clause("pdf","known","")))));
        Map<String,Object> graph=graph(shared,word,pdf,dependent);graph.put("sources",Arrays.asList(obj("id","w","docId","scc-word","fileKey","SCC"),obj("id","p","docId","scc-pdf","fileKey","SCC")));graph.put("selectedDocuments",obj("NTT","ntt","SCC","w"));validate(graph);
        assertEquals(Arrays.asList("shared","word"),Arrays.asList(fields(graph).get(0).get("id"),fields(graph).get(1).get("id")));assertTrue(list(plan(graph,obj("shared","x","word","y")).get("unresolved")).isEmpty());
        word.put("sourceAnchors",Collections.singletonList(obj("docId","p","text","hidden PDF edition")));assertThrows(BizException.class,()->validate(graph));word.put("docIds",Arrays.asList("w","p"));assertThrows(BizException.class,()->validate(graph));
    }
    @Test void unresolvedHistoricalBindingsCanBeSavedButStayPending(){
        Map<String,Object> node=node("pending","boolean","derived");node.put("correction",obj("kind","rule","needsRebind",true,"branches",Collections.singletonList(branch("old","all",clause("removed","yes","")))));node.put("editTargets",Collections.singletonList(obj("docId","ntt","action","fill","branchId","old","valueSourceId","removed","needsRebind",true)));
        validate(graph(node));Map<String,Object> result=plan(graph(node),Collections.emptyMap());assertEquals("pending",map(list(result.get("actions")).get(0)).get("action"));assertFalse(list(result.get("unresolved")).isEmpty());
    }
    @Test void blankFixedScalarIsUnknownWhileExplicitFixedEmptyTextIsKnown(){
        Map<String,Object> fixed=node("fixed","number","fixed"),derived=node("known","boolean","derived");fixed.put("fixedValue","");Map<String,Object> unknown=branch("unknown","all",clause("fixed","unknown",""));unknown.put("resultValue",true);derived.put("correction",obj("branches",Collections.singletonList(unknown)));
        assertEquals(true,map(plan(graph(fixed,derived),Collections.emptyMap()).get("derived")).get("known"));fixed.put("type",obj("kind","text"));Map<String,Object> empty=branch("empty","all",clause("fixed","empty",""));empty.put("resultValue",true);derived.put("correction",obj("branches",Collections.singletonList(empty)));assertEquals(true,map(plan(graph(fixed,derived),Collections.emptyMap()).get("derived")).get("known"));
    }
    @Test void optionalLocalSaved291GraphIsValidatedWithoutCommittingSourceCorpus()throws Exception{
        String path=System.getProperty("consense.reviewGraphFixture");org.junit.jupiter.api.Assumptions.assumeTrue(path!=null&&!path.isEmpty());Map<String,Object> saved=com.consense.common.JsonUtils.readMap(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)),java.nio.charset.StandardCharsets.UTF_8));assertEquals(291,nodes(saved).size());validate(saved);
    }
    @Test void exactNativeCompilationPreservesUntouchedTextAndRejectsOverlapOrChangedSource()throws Exception{
        byte[] original;try(XWPFDocument doc=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){doc.createParagraph().createRun().setText("Before *G1/*G1a after");doc.createParagraph().createRun().setText("Keep this paragraph");doc.write(out);original=out.toByteArray();}
        DocxTemplateEditor.TemplateIndex index=new DocxTemplateEditor().inspect(original);DocxTemplateEditor.Paragraph paragraph=index.getMainParagraphs().get(0);
        Map<String,Object> binding=obj("status","bound","sourceSha256",index.getSourceSha256(),"spans",Collections.singletonList(obj("paragraphId",paragraph.getId(),"paragraphSha256",paragraph.getSha256(),"start",7,"end",15,"expectedText","*G1/*G1a","channel","editableText")));
        Map<String,Object> action=obj("id","bond-target","document","NTT","action","amend","value","G1a","nativeBinding",binding);
        Map<String,Object> plan=obj("actions",Collections.singletonList(action));
        DocxTemplateEditor.DocxEditResult output=compile(original,"NTT",obj(),plan,Collections.emptyMap(),null);
        assertEquals("Before G1a after",output.getIndex().getMainParagraphs().get(0).getText());assertEquals("Keep this paragraph",output.getIndex().getMainParagraphs().get(1).getText());
        plan.put("actions",Arrays.asList(action,action));assertThrows(BizException.class,()->compile(original,"NTT",obj(),plan,Collections.emptyMap(),null));
        plan.put("actions",Collections.singletonList(action));binding.put("sourceSha256",String.join("",Collections.nCopies(64,"0")));assertThrows(BizException.class,()->compile(original,"NTT",obj(),plan,Collections.emptyMap(),null));
    }
}
