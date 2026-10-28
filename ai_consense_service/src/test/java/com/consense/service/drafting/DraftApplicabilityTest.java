package com.consense.service.drafting;

import com.consense.common.BizException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.consense.service.drafting.DraftReviewGraph.*;
import static org.junit.jupiter.api.Assertions.*;

class DraftApplicabilityTest {
    private Map<String,Object> node(String id,String key) {
        Map<String,Object> field=DraftCatalogAlignment.definitions().get(key);
        String kind=text(field.get("kind"));if(Arrays.asList("records","table","bills").contains(kind))kind="list";
        Map<String,Object> result=obj("id",id,"nameEn",id,"type",obj("kind",kind),"role",obj("kind","input"),"status","confirmed","optional",Boolean.TRUE.equals(field.get("optional")),"docIds",Collections.emptyList(),"sourceAnchors",Collections.emptyList(),"editTargets",Collections.emptyList(),"catalogAlignment",obj("status","matched","confirmed",true,"baselineKey",key,"baselineVersion",DraftBusinessRules.RULE_VERSION,"reason","Reviewed current source"));
        if(field.containsKey("options"))result.put("options",field.get("options"));return result;
    }
    private Map<String,Object> graph(Map<String,Object>... nodes){return obj("graph",obj("variables",Arrays.asList(nodes)));}
    private void bind(Map<String,Object> graph,Map<String,Object> target) {
        Map<String,Map<String,Object>> nodes=new LinkedHashMap<>();for(Map<String,Object> node:DraftReviewGraph.nodes(graph))nodes.put(text(node.get("id")),node);
        Map<String,Object> rule=map(target.get("applicability"));rule.put("bindingFingerprint",DraftReviewGraph.applicabilityFingerprint(graph,target,list(rule.get("clauses")),nodes));
    }
    private Map<String,Object> condition(String target,Object... mappings) {
        Map<String,String> ids=new LinkedHashMap<>();for(int i=0;i<mappings.length;i+=2)ids.put((String)mappings[i],(String)mappings[i+1]);
        List<Object> clauses=new ArrayList<>();for(Object raw:list(map(DraftCatalogAlignment.definitions().get(target).get("condition")).get("all"))){Map<String,Object> term=map(raw);clauses.add(obj("baselineKey",term.get("field"),"inputId",ids.get(text(term.get("field"))),"operator",term.get("operator"),"value",term.get("value")));}
        return obj("kind","fixed_catalog","ruleVersion",DraftBusinessRules.RULE_VERSION,"targetBaselineKey",target,"mode","all","confirmed",true,"reason","Reviewed applicability against this template","clauses",clauses);
    }
    @Test void confirmedConditionPreservesInactiveManualAnswerAndUnknownNeedsReview() {
        Map<String,Object> root=node("domestic","domesticBlocks"),child=node("precast","volumetricPrecastComponents");child.put("applicability",condition("volumetricPrecastComponents","domesticBlocks","domestic"));Map<String,Object> graph=graph(root,child);bind(graph,child);
        Map<String,Object> saved=obj("domestic",false,"precast",true),inactive=plan(graph,saved,true);
        assertEquals("no",map(inactive.get("applicabilityStates")).get("precast"));assertFalse(map(inactive.get("effectiveValues")).containsKey("precast"));assertEquals(true,map(inactive.get("inputValues")).get("precast"));assertFalse(list(inactive.get("unresolved")).stream().map(DraftReviewGraph::map).anyMatch(i->"InputMissing:precast".equals(i.get("id"))));
        Map<String,Object> active=plan(graph,obj("domestic",true,"precast",true),true);assertEquals("yes",map(active.get("applicabilityStates")).get("precast"));assertEquals(true,map(active.get("effectiveValues")).get("precast"));
        Map<String,Object> unknown=plan(graph,obj("precast",true),true);assertEquals("unknown",map(unknown.get("applicabilityStates")).get("precast"));assertTrue(list(unknown.get("unresolved")).stream().map(DraftReviewGraph::map).anyMatch(i->"InputApplicabilityUnknown:precast".equals(i.get("id"))));assertFalse(map(unknown.get("effectiveValues")).containsKey("precast"));
        Map<String,Object> result=catalog(graph),group=map(list(result.get("groups")).get(0));Map<String,Object> display=list(group.get("fields")).stream().map(DraftReviewGraph::map).filter(f->"precast".equals(f.get("key"))).findFirst().get();assertEquals("domestic",map(list(map(display.get("condition")).get("all")).get(0)).get("field"));
    }
    @Test void changedMappingsOrRuleDoNotExecuteAndCanStillBeSaved() {
        Map<String,Object> root=node("domestic","domesticBlocks"),child=node("precast","volumetricPrecastComponents"),rule=condition("volumetricPrecastComponents","domesticBlocks","domestic");child.put("applicability",rule);Map<String,Object> graph=graph(root,child);bind(graph,child);
        String before=fieldHash(fields(graph).get(1));rule.put("reason","Rechecked the source");assertNotEquals(before,fieldHash(fields(graph).get(1)));
        map(root.get("catalogAlignment")).put("confirmed",false);assertDoesNotThrow(()->validate(graph));Map<String,Object> result=plan(graph,obj("domestic",false,"precast",true),false);assertEquals("unknown",map(result.get("applicabilityStates")).get("precast"));assertEquals(true,map(result.get("inputValues")).get("precast"));
        Map<String,Object> display=map(list(map(list(catalog(graph).get("groups")).get(0)).get("fields")).get(1));assertEquals(true,display.get("applicabilityReviewRequired"));assertEquals(true,map(display.get("condition")).get("reviewRequired"));
        map(root.get("catalogAlignment")).put("confirmed",true);map(list(rule.get("clauses")).get(0)).put("value",false);assertEquals("unknown",map(plan(graph,obj("domestic",false),false).get("applicabilityStates")).get("precast"));
        rule.put("reason"," ");assertThrows(BizException.class,()->validate(graph));
    }
    @Test void listConditionsUseConfirmedDynamicChildIdentity() {
        Map<String,Object> trees=node("trees","oldValuableTrees"),serial=node("serial-actual","oldValuableTrees.serial"),target=node("spec","ovtProjectSpecificText");serial.put("parentId","trees");target.put("applicability",condition("ovtProjectSpecificText","oldValuableTrees","trees"));Map<String,Object> graph=graph(trees,serial,target);bind(graph,target);
        Map<String,Object> result=plan(graph,obj("trees",Arrays.asList(obj("serial-actual","T1"),obj("serial-actual","T2")),"spec","Confirmed wording"),true);assertEquals("yes",map(result.get("applicabilityStates")).get("spec"));
        Map<String,Object> display=map(list(map(list(catalog(graph).get("groups")).get(0)).get("fields")).get(1));assertEquals("serial-actual",map(list(map(display.get("condition")).get("all")).get(0)).get("identityKey"));
    }
    @Test void allConditionsUseThreeValuedLogicAcrossDependentInputs() {
        Map<String,Object> domestic=node("domestic","domesticBlocks"),precast=node("precast","volumetricPrecastComponents"),pse=node("pse","pseSubmissions"),text=node("pse-text","pseSubmissions.text");text.put("parentId","pse");
        precast.put("applicability",condition("volumetricPrecastComponents","domesticBlocks","domestic"));pse.put("applicability",condition("pseSubmissions","domesticBlocks","domestic","volumetricPrecastComponents","precast"));Map<String,Object> graph=graph(domestic,precast,pse,text);bind(graph,precast);bind(graph,pse);
        Map<String,Object> inactive=plan(graph,obj("domestic",false),true);assertEquals("no",map(inactive.get("applicabilityStates")).get("pse"));
        Map<String,Object> unknown=plan(graph,obj("domestic",true),true);assertEquals("unknown",map(unknown.get("applicabilityStates")).get("pse"));
        Map<String,Object> active=plan(graph,obj("domestic",true,"precast",true,"pse",Collections.singletonList(obj("pse-text","submit calculations"))),true);assertEquals("yes",map(active.get("applicabilityStates")).get("pse"));assertTrue(map(active.get("effectiveValues")).containsKey("pse"));
    }
    @Test void componentConditionUsesConfirmedDynamicColumnAndNeverAssumesAbsentColumnMeansNo() {
        Map<String,Object> rows=node("responsibilities","designResponsibilities"),component=node("component-id","designResponsibilities.component"),design=node("design-id","designResponsibilities.design"),execution=node("execution-id","designResponsibilities.execution"),target=node("footings","footingsServeBuildingsOrMajorExternalStructures");
        for(Map<String,Object> child:Arrays.asList(component,design,execution))child.put("parentId","responsibilities");target.put("applicability",condition("footingsServeBuildingsOrMajorExternalStructures","designResponsibilities","responsibilities"));Map<String,Object> graph=graph(rows,component,design,execution,target);bind(graph,target);
        Map<String,Object> values=obj("responsibilities",Collections.singletonList(obj("component-id","footings","design-id",true,"execution-id",false)),"footings",true);Map<String,Object> result=plan(graph,values,true);assertEquals("yes",map(result.get("applicabilityStates")).get("footings"));
        Map<String,Object> display=map(list(map(list(catalog(graph).get("groups")).get(0)).get("fields")).get(1));assertEquals("component-id",map(list(map(display.get("condition")).get("all")).get(0)).get("componentKey"));
        map(component.get("catalogAlignment")).put("confirmed",false);map(rows.get("catalogAlignment")).put("confirmed",false);result=plan(graph,values,true);assertEquals("unknown",map(result.get("applicabilityStates")).get("footings"));
    }
    @Test void suggestedFingerprintBindsCurrentSourceAndDefinitionsWithoutCopyingSourceText() {
        Map<String,Object> root=node("domestic","domesticBlocks"),target=node("precast","volumetricPrecastComponents");
        root.put("docIds",Collections.singletonList("ntt"));root.put("sourceAnchors",Collections.singletonList(obj("docId","ntt","text","Original source text")));
        target.put("docIds",Collections.singletonList("ntt"));Map<String,Object> graph=graph(root,target);Map<String,Object> source=obj("id","ntt","fileKey","NTT","sourceSha256","a","analysisRevision","1");graph.put("sources",Collections.singletonList(source));graph.put("selectedDocuments",obj("NTT","ntt"));
        Map<String,Object> suggestion=DraftFixedRuleSuggestions.forGraph(graph).stream().map(DraftReviewGraph::map).filter(r->"field:volumetricPrecastComponents".equals(r.get("actionId"))).findFirst().get();
        String fingerprint=text(suggestion.get("bindingFingerprint"));assertFalse(fingerprint.isEmpty());assertFalse(fingerprint.contains("Original source text"));
        Map<String,Object> rule=condition("volumetricPrecastComponents","domesticBlocks","domestic");rule.put("bindingFingerprint",fingerprint);target.put("applicability",rule);
        assertEquals("yes",map(plan(graph,obj("domestic",true,"precast",true),true).get("applicabilityStates")).get("precast"));
        root.put("sourceAnchors",Collections.singletonList(obj("text","Original source text","docId","ntt")));assertEquals("yes",map(plan(graph,obj("domestic",true,"precast",true),true).get("applicabilityStates")).get("precast"));
        source.put("sourceSha256","b");assertEquals("unknown",map(plan(graph,obj("domestic",true,"precast",true),true).get("applicabilityStates")).get("precast"));source.put("sourceSha256","a");
        map(list(root.get("sourceAnchors")).get(0)).put("text","Changed source text");assertEquals("unknown",map(plan(graph,obj("domestic",true),true).get("applicabilityStates")).get("precast"));map(list(root.get("sourceAnchors")).get(0)).put("text","Original source text");
        root.put("questionEn","Changed definition");assertEquals("unknown",map(plan(graph,obj("domestic",true),true).get("applicabilityStates")).get("precast"));
    }
    @Test void rowScopedPrerequisiteIsNotProposedAsAProjectWideCondition() {
        Map<String,Object> group=obj("id","row","nameEn","row","type",obj("kind","record"),"role",obj("kind","input"),"status","confirmed");
        Map<String,Object> root=node("domestic","domesticBlocks"),target=node("precast","volumetricPrecastComponents");root.put("parentId","row");map(root.get("catalogAlignment")).put("scope","row-specific");Map<String,Object> graph=graph(group,root,target);
        Map<String,Object> suggestion=DraftFixedRuleSuggestions.forGraph(graph).stream().map(DraftReviewGraph::map).filter(r->"field:volumetricPrecastComponents".equals(r.get("actionId"))).findFirst().get();assertFalse(suggestion.containsKey("bindingFingerprint"));
        target.put("applicability",condition("volumetricPrecastComponents","domesticBlocks","domestic"));bind(graph,target);assertEquals("unknown",map(plan(graph,obj("row",obj("domestic",true),"precast",true),true).get("applicabilityStates")).get("precast"));
    }
}
