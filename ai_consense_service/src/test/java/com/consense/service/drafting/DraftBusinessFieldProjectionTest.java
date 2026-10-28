package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

class DraftBusinessFieldProjectionTest {
    @Test void inputTreesExcludeNonBusinessChildrenWithoutChangingTheSavedGraph() {
        Map<String,Object> rows=node("rows","list","input",null),name=node("name","text","input","rows"),details=node("details","record","content","rows"),quantity=node("quantity","number","decision","details");
        List<Map<String,Object>> all=new ArrayList<>(Arrays.asList(rows,name,details,quantity));
        for(String role:Arrays.asList("verification","derived","fixed","system"))all.add(node(role,"text",role,"rows"));
        Map<String,Object> seed=node("seed","text","input","details");seed.put("definitionKind","source_seed");all.add(seed);
        Map<String,Object> editorial=node("editorial","text","input","details");editorial.put("editorialReview",obj("kind","editorial","confirmed",true));all.add(editorial);
        Map<String,Object> graph=graph(all),projected=fields(graph).get(0);String before=JsonUtils.write(graph);
        assertEquals(Arrays.asList("name","details"),ids(list(projected.get("children"))));
        assertEquals(Collections.singletonList("quantity"),ids(list(map(list(projected.get("children")).get(1)).get("children"))));
        assertEquals("[{\"name\":\"Bill 1\",\"details\":{\"quantity\":2}}]",normalize(projected,"[{\"name\":\"Bill 1\",\"details\":{\"quantity\":2}}]"));
        Map<String,Object> catalogRows=map(list(map(list(catalog(graph).get("groups")).get(0)).get("fields")).get(0));
        assertEquals(Arrays.asList("name","details"),keys(list(catalogRows.get("columnFields"))));
        assertEquals(1,list(catalog(graph).get("systemFields")).size(),"Derived nodes remain available in the system-value view");
        assertEquals(before,JsonUtils.write(graph));assertEquals(10,nodes(graph).size());
    }

    @Test void extractionProjectionAlsoRejectsNonBusinessItemsInAnAlreadyBuiltTree() {
        Map<String,Object> root=node("root","record","input",null),child=node("answer","text","input","root"),note=node("note","text","verification","root");
        Map<String,Object> nested=node("nested","record","content","root"),action=node("action","text","derived","nested");nested.put("children",Collections.singletonList(action));
        root.put("children",Arrays.asList(child,note,nested));String before=JsonUtils.write(root);
        List<Map<String,Object>> projected=extractionDefinitions(Arrays.asList(note,root));assertEquals(1,projected.size());
        assertEquals(Arrays.asList("answer","nested"),ids(list(projected.get(0).get("children"))));
        assertTrue(list(map(list(projected.get(0).get("children")).get(1)).get("children")).isEmpty());assertEquals(before,JsonUtils.write(root));
    }

    @Test void fixedAndDerivedConditionValuesKeepTheirExistingExecutionPath() {
        Map<String,Object> input=node("answer","boolean","input",null),fixed=node("fixed","boolean","fixed",null),derived=node("derived","boolean","derived",null);
        fixed.put("fixedValue",true);derived.put("correction",obj("kind","rule","branches",Collections.singletonList(obj("id","yes","mode","all","clauses",Collections.singletonList(obj("inputId","fixed","operator","yes")),"resultValue",true))));
        Map<String,Object> graph=graph(Arrays.asList(input,fixed,derived));String before=JsonUtils.write(graph);
        assertDoesNotThrow(()->validate(graph));assertEquals(Collections.singletonList("answer"),ids(fields(graph)));
        assertEquals(true,map(plan(graph,obj("answer",false)).get("derived")).get("derived"));
        assertEquals(before,JsonUtils.write(graph));
    }

    private static Map<String,Object> node(String id,String type,String role,String parent) {return obj("id",id,"nameZh",id,"nameEn",id,"type",obj("kind",type),"role",obj("kind",role),"parentId",parent,"status","confirmed","docIds",Collections.singletonList("ntt"),"options",new ArrayList<>(),"editTargets",new ArrayList<>());}
    private static Map<String,Object> graph(List<Map<String,Object>> nodes) {return obj("kind","consense-template-graph-v1","graph",obj("variables",nodes),"selectedDocuments",obj("NTT","ntt"));}
    private static List<String> ids(List<?> items) {List<String> result=new ArrayList<>();for(Object item:items)result.add(text(map(item).get("id")));return result;}
    private static List<String> keys(List<?> items) {List<String> result=new ArrayList<>();for(Object item:items)result.add(text(map(item).get("key")));return result;}
}
