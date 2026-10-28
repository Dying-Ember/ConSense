package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.consense.service.drafting.DraftReviewGraph.*;
import static org.junit.jupiter.api.Assertions.*;

class DraftFixedRuleSuggestionsTest {
    private Map<String,Object> node(String id,String key) {
        Map<String,Object> field=DraftCatalogAlignment.definitions().get(key);
        Map<String,Object> node=obj("id",id,"type",obj("kind",field.get("kind")),"role",obj("kind","input"),"status","confirmed","optional",Boolean.TRUE.equals(field.get("optional")),
            "catalogAlignment",obj("status","matched","confirmed",true,"baselineKey",key,"baselineVersion",DraftBusinessRules.RULE_VERSION,"reason","reviewed"));
        if(field.containsKey("options"))node.put("options",field.get("options"));return node;
    }
    private Map<String,Object> graph(Map<String,Object>... nodes){return obj("graph",obj("variables",Arrays.asList(nodes)));}
    private Map<String,Object> suggestion(List<Object> list,String id){return list.stream().map(DraftReviewGraph::map).filter(row->id.equals(row.get("actionId"))).findFirst().orElseThrow();}

    @Test void confirmedInputsShowCatalogConditionAndBoundedHistoricalActionsWithoutEditingGraph() {
        Map<String,Object> issue=node("format","electronicTendering"),two=node("procedure","twoEnvelopeTendering"),graph=graph(issue,two);
        String before=JsonUtils.write(graph);List<Object> suggestions=DraftFixedRuleSuggestions.forGraph(graph);assertEquals(before,JsonUtils.write(graph));
        Map<String,Object> condition=suggestion(suggestions,"field:otherTenderingArrangement");
        assertEquals("field_condition",condition.get("kind"));assertEquals("otherTenderingArrangement",condition.get("targetBaselineKey"));assertTrue(list(condition.get("targetNodeIds")).isEmpty());
        assertEquals("ambiguous",map(condition.get("reviewCoverage")).get("status"));assertEquals("target_mapping_missing",map(condition.get("reviewCoverage")).get("reason"));
        assertEquals(false,map(list(condition.get("conditions")).get(0)).get("value"));assertEquals("eq",map(list(condition.get("conditions")).get(0)).get("operator"));
        assertEquals(Collections.singletonList("procedure"),map(list(condition.get("inputs")).get(0)).get("nodeIds"));
        Map<String,Object> first=suggestion(suggestions,"NTT-2-L10PRO"),second=suggestion(suggestions,"NTT-2-PAPER"),combined=suggestion(suggestions,"NTT-3C-L10PRO");
        assertEquals("partial_examples",first.get("coverage"));assertEquals("partial_examples",second.get("coverage"));
        assertFalse(first.containsKey("reviewCoverage"));assertFalse(second.containsKey("reviewCoverage"));
        assertTrue(JsonUtils.write(combined.get("inputs")).contains("format"));assertTrue(JsonUtils.write(combined.get("inputs")).contains("procedure"));
        for(Object raw:suggestions){Map<String,Object> row=map(raw);assertEquals("unbound",row.get("targetBinding"));assertEquals("pending",row.get("status"));assertEquals(false,row.get("executable"));assertFalse(row.containsKey("paragraphs"));assertFalse(row.containsKey("action"));}
    }
    @Test void unconfirmedAndPdfOnlySourcesCannotProjectWordRules() {
        Map<String,Object> issue=node("format","electronicTendering");map(issue.get("catalogAlignment")).put("confirmed",false);
        assertTrue(DraftFixedRuleSuggestions.forGraph(graph(issue)).isEmpty());
        map(issue.get("catalogAlignment")).put("confirmed",true);issue.put("docIds",Collections.singletonList("pdf"));Map<String,Object> graph=graph(issue);
        graph.put("sources",Collections.singletonList(obj("id","pdf","fileKey","SCC","format","pdf")));
        assertTrue(DraftFixedRuleSuggestions.forGraph(graph).isEmpty());
    }
    @Test void conditionReferencesAreNotLimitedToTenderFormat() {
        Map<String,Object> domestic=node("domestic","domesticBlocks");List<Object> suggestions=DraftFixedRuleSuggestions.forGraph(graph(domestic));
        Map<String,Object> condition=suggestion(suggestions,"field:volumetricPrecastComponents");assertEquals(true,map(list(condition.get("conditions")).get(0)).get("value"));assertTrue(suggestions.stream().map(DraftReviewGraph::map).noneMatch(row->"action_reference".equals(row.get("kind"))));
    }

    @Test void aMissingFixedConditionRemainsVisibleWithoutChangingOrConfirmingTheGraph() {
        Map<String,Object> graph=graph(node("procedure","twoEnvelopeTendering"),node("other","otherTenderingArrangement"));String before=JsonUtils.write(graph);
        Map<String,Object> row=suggestion(DraftFixedRuleSuggestions.forGraph(graph),"field:otherTenderingArrangement");
        assertEquals("missing",map(row.get("reviewCoverage")).get("status"));assertEquals("fixed_condition_not_restored",map(row.get("reviewCoverage")).get("reason"));assertEquals("pending",row.get("status"));assertEquals(false,row.get("executable"));assertEquals("unbound",row.get("targetBinding"));assertEquals(before,JsonUtils.write(graph));
    }

    @Test void onlyTheCompleteCurrentAllConditionIsCoveredAndStillRequiresReview() {
        Map<String,Object> graph=pseGraph();adopt(graph,"pse","pseSubmissions");String before=JsonUtils.write(graph);
        Map<String,Object> row=suggestion(DraftFixedRuleSuggestions.forGraph(graph),"field:pseSubmissions");assertEquals(2,list(row.get("conditions")).size());assertEquals("covered",map(row.get("reviewCoverage")).get("status"));assertEquals("pending",row.get("status"));assertEquals(false,row.get("executable"));assertEquals(before,JsonUtils.write(graph));
        Map<String,Object> incomplete=JsonUtils.readMap(before),target=field(incomplete,"pse");List<Object> clauses=new ArrayList<>(list(map(target.get("applicability")).get("clauses")));clauses.remove(1);map(target.get("applicability")).put("clauses",clauses);assertEquals("missing",coverage(incomplete,"pseSubmissions").get("status"));
        Map<String,Object> any=JsonUtils.readMap(before);map(field(any,"pse").get("applicability")).put("mode","any");assertEquals("missing",coverage(any,"pseSubmissions").get("status"));assertEquals("logical_mode_differs",coverage(any,"pseSubmissions").get("reason"));
    }

    @Test void oppositeOperatorsValuesAndAdditionalNegationCannotBeCalledCovered() {
        Map<String,Object> graph=graph(node("procedure","twoEnvelopeTendering"),node("other","otherTenderingArrangement"));adopt(graph,"other","otherTenderingArrangement");String valid=JsonUtils.write(graph);
        for(String defect:Arrays.asList("operator","value","not","extra_rule")) {
            Map<String,Object> changed=JsonUtils.readMap(valid),rule=map(field(changed,"other").get("applicability")),term=map(list(rule.get("clauses")).get(0));
            if("operator".equals(defect))term.put("operator","ne");else if("value".equals(defect))term.put("value",true);else if("not".equals(defect))term.put("not",true);else rule.put("not",true);
            assertNotEquals("covered",coverage(changed,"otherTenderingArrangement").get("status"),defect);assertEquals("pending",suggestion(DraftFixedRuleSuggestions.forGraph(changed),"field:otherTenderingArrangement").get("status"));
        }
    }

    @Test void changedDefinitionBindingIsStaleWhileMultipleMappingsAndCustomLogicStayAmbiguous() {
        Map<String,Object> graph=graph(node("procedure","twoEnvelopeTendering"),node("other","otherTenderingArrangement"));adopt(graph,"other","otherTenderingArrangement");field(graph,"procedure").put("questionEn","Changed current prerequisite meaning");assertEquals("stale",coverage(graph,"otherTenderingArrangement").get("status"));
        Map<String,Object> a=node("other-a","otherTenderingArrangement"),b=node("other-b","otherTenderingArrangement");map(a.get("catalogAlignment")).put("scope","Scope A");map(b.get("catalogAlignment")).put("scope","Scope B");Map<String,Object> ambiguous=graph(node("procedure","twoEnvelopeTendering"),a,b);assertEquals("ambiguous",coverage(ambiguous,"otherTenderingArrangement").get("status"));assertEquals("target_mapping_ambiguous",coverage(ambiguous,"otherTenderingArrangement").get("reason"));
        Map<String,Object> custom=graph(node("procedure","twoEnvelopeTendering"),node("other","otherTenderingArrangement"));field(custom,"other").put("correction",obj("kind","rule","branches",Collections.singletonList(obj("id","custom","mode","any","clauses",Collections.singletonList(obj("inputId","procedure","operator","no"))))));assertEquals("ambiguous",coverage(custom,"otherTenderingArrangement").get("status"));assertEquals("custom_rule_requires_review",coverage(custom,"otherTenderingArrangement").get("reason"));
    }

    private Map<String,Object> pseGraph(){Map<String,Object> column=node("pse-text","pseSubmissions.text");column.put("parentId","pse");return graph(node("domestic","domesticBlocks"),node("precast","volumetricPrecastComponents"),node("pse","pseSubmissions"),column);}
    private Map<String,Object> coverage(Map<String,Object> graph,String key){return map(suggestion(DraftFixedRuleSuggestions.forGraph(graph),"field:"+key).get("reviewCoverage"));}
    private Map<String,Object> field(Map<String,Object> graph,String id){return nodes(graph).stream().filter(node->id.equals(node.get("id"))).findFirst().orElseThrow();}
    private void adopt(Map<String,Object> graph,String id,String key){Map<String,Object> row=suggestion(DraftFixedRuleSuggestions.forGraph(graph),"field:"+key);List<Object> clauses=new ArrayList<>();for(Object raw:list(row.get("conditions"))){Map<String,Object> expected=map(raw);Map<String,Object> input=list(row.get("inputs")).stream().map(DraftReviewGraph::map).filter(item->Objects.equals(expected.get("field"),item.get("baselineKey"))).findFirst().orElseThrow();clauses.add(obj("inputId",list(input.get("nodeIds")).get(0),"baselineKey",expected.get("field"),"operator",expected.get("operator"),"value",expected.get("value")));}field(graph,id).put("applicability",obj("kind","fixed_catalog","ruleVersion",row.get("ruleVersion"),"targetBaselineKey",key,"bindingFingerprint",row.get("bindingFingerprint"),"mode","all","confirmed",true,"reason","Human reviewed this fixed condition","clauses",clauses));}
}
