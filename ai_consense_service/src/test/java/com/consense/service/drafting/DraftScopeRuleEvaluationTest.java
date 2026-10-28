package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Synthetic evaluation contracts only; no model, product API or file generation. */
class DraftScopeRuleEvaluationTest {
    @Test void aCompiledScopePredicateReturnsTrueOrFalseWithoutInventingAnOppositeAction(){
        Map<String,Object> graph=graph(input("answer"),predicate());String before=JsonUtils.write(graph);
        for(boolean answer:Arrays.asList(true,false)){
            Map<String,Object> result=plan(graph,obj("answer",answer),true);
            assertEquals(answer,map(result.get("derived")).get("rule"));assertFalse(hasIssue(result,"DerivedUnknown","rule"));
            assertEquals(1,list(result.get("actions")).size());assertEquals(answer?"retain":"not_adopted",action(result).get("action"));assertFalse(JsonUtils.write(result.get("actions")).contains("\"action\":\"delete\""));
            assertEquals(answer,map(result.get("effectiveValues")).get("answer"));assertEquals(before,JsonUtils.write(graph));
        }
    }

    @Test void aGenuinelyUnknownPredicateRemainsUnknownAndItsActionRemainsPending(){
        Map<String,Object> result=plan(graph(input("answer"),predicate()),Collections.emptyMap(),true);
        assertFalse(map(result.get("derived")).containsKey("rule"));assertFalse(map(result.get("effectiveValues")).containsKey("answer"));
        assertTrue(hasIssue(result,"DerivedUnknown","rule"));assertTrue(hasIssue(result,"InputMissing","answer"));assertEquals("pending",action(result).get("action"));
    }

    @Test void anAlreadyFalseConjunctionDoesNotTreatItsUnansweredBusinessInputAsNo(){
        Map<String,Object> rule=predicate();branch(rule).put("clauses",Arrays.asList(clause("answer","yes"),clause("later","yes")));
        Map<String,Object> result=plan(graph(input("answer"),input("later"),rule),obj("answer",false),true);
        assertEquals(false,map(result.get("derived")).get("rule"));assertFalse(hasIssue(result,"DerivedUnknown","rule"));assertEquals("not_adopted",action(result).get("action"));
        assertFalse(map(result.get("effectiveValues")).containsKey("later"));assertTrue(hasIssue(result,"InputMissing","later"));assertEquals(1,list(result.get("actions")).size());
        Map<String,Object> presence=predicate();branch(presence).put("clauses",Collections.singletonList(clause("answer","known")));
        Map<String,Object> absent=plan(graph(input("answer"),presence),Collections.emptyMap(),true);assertEquals(false,map(absent.get("derived")).get("rule"));assertTrue(hasIssue(absent,"InputMissing","answer"));assertFalse(map(absent.get("effectiveValues")).containsKey("answer"));assertEquals("not_adopted",action(absent).get("action"));
    }

    @Test void ordinaryDerivedValuesAndChangedCompilerShapesKeepTheExistingUnknownBehavior(){
        for(String change:Arrays.asList("ordinary","missing-rule-id","review-only","different-type","false-result","two-branches")){
            Map<String,Object> rule=predicate();
            if("ordinary".equals(change))rule.remove("origin");
            if("missing-rule-id".equals(change))rule.remove("compiledScopeRuleId");
            if("review-only".equals(change))rule.put("conditionDisposition","review_only");
            if("different-type".equals(change))rule.put("type",obj("kind","text"));
            if("false-result".equals(change))branch(rule).put("resultValue",false);
            if("two-branches".equals(change)){Map<String,Object> other=JsonUtils.readMap(JsonUtils.write(branch(rule)));other.put("id","other");map(rule.get("correction")).put("branches",Arrays.asList(branch(rule),other));}
            Map<String,Object> result=plan(graph(input("answer"),rule),obj("answer",false),true);assertFalse(map(result.get("derived")).containsKey("rule"),change);assertTrue(hasIssue(result,"DerivedUnknown","rule"),change);assertEquals("not_adopted",action(result).get("action"),change);
        }
    }

    private static Map<String,Object> input(String id){return node(id,"boolean","input");}
    private static Map<String,Object> node(String id,String type,String role){return obj("id",id,"nameEn",id,"questionEn",id,"parentId",null,"type",obj("kind",type),"role",obj("kind",role),"status","confirmed","docIds",Collections.singletonList("ntt"),"options",Collections.emptyList(),"editTargets",Collections.emptyList());}
    private static Map<String,Object> predicate(){
        Map<String,Object> node=node("rule","boolean","derived");node.put("origin","scope_rules_compilation");node.put("compiledScopeRuleId","scope-rule");node.put("conditionDisposition","preserved_as_branch");
        node.put("correction",obj("kind","rule","needsRebind",false,"branches",Collections.singletonList(obj("id","match","mode","all","clauses",Collections.singletonList(clause("answer","yes")),"resultValue","true"))));
        node.put("editTargets",Collections.singletonList(obj("id","keep-current","docId","ntt","action","keep","branchId","match","needsRebind",false)));return node;
    }
    private static Map<String,Object> clause(String input,String operator){return obj("inputId",input,"operator",operator);}
    private static Map<String,Object> branch(Map<String,Object> node){return map(list(map(node.get("correction")).get("branches")).get(0));}
    private static Map<String,Object> graph(Map<String,Object>... nodes){return obj("kind","consense-template-graph-v1","selectedDocuments",obj("NTT","ntt"),"graph",obj("variables",Arrays.asList(nodes)));}
    private static Map<String,Object> action(Map<String,Object> plan){assertEquals(1,list(plan.get("actions")).size());return map(list(plan.get("actions")).get(0));}
    private static boolean hasIssue(Map<String,Object> plan,String kind,String id){return list(plan.get("unresolved")).stream().anyMatch(raw->(kind+":"+id).equals(map(raw).get("id")));}
}
