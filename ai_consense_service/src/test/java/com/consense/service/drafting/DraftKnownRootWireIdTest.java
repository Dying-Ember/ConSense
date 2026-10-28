package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Business-ID boundary contracts; no model, product API, persistence or browser execution. */
class DraftKnownRootWireIdTest {
    private static final String TASK="source-task-current";

    @Test void businessIdsStayStableWhenOtherRootsAreInsertedOrReordered() {
        Map<String,Object> root=root("human-root","worksSubjectToExcision"),other=root("another-root","electronicTendering");
        DraftKnownRoots.Result original=result(root),expanded=result(other,root),reordered=result(root,other);
        for(DraftKnownRoots.Result known:Arrays.asList(original,expanded,reordered)) {
            Map<String,Map<String,Object>> visible=known.forTasks(Collections.singletonList(TASK));assertEquals("human-root",visible.get("worksSubjectToExcision").get("id"));assertFalse(visible.containsKey("k1"));
            Map<String,Object> descriptor=known.descriptor().stream().filter(row->"human-root".equals(row.get("id"))).findFirst().orElseThrow(AssertionError::new);
            assertEquals("worksSubjectToExcision",descriptor.get("wireId"));assertEquals(descriptor.get("baselineKey"),descriptor.get("wireId"));
        }
    }

    @Test void missingOrUnknownExplicitKeysCannotBeGuessedFromNamesOrCatalogMetadata() {
        for(String key:Arrays.asList("","unknownBusinessDefinition"," worksSubjectToExcision ")) {
            Map<String,Object> node=root("internal",key);node.put("nameEn","worksSubjectToExcision");node.put("catalogAlignment",obj("baselineKey","worksSubjectToExcision"));
            IllegalArgumentException failure=assertThrows(IllegalArgumentException.class,()->result(node));assertTrue(failure.getMessage().startsWith(key.isEmpty()?"KNOWN_ROOT_BASELINE_KEY_REQUIRED":"KNOWN_ROOT_BASELINE_KEY_UNKNOWN"));
        }
        Map<String,Object> absent=root("internal","worksSubjectToExcision");absent.remove("currentDefinitionReference");assertThrows(IllegalArgumentException.class,()->result(absent));
    }

    @Test void duplicateBusinessKeysFailBeforeEitherDefinitionCanBeExposed() {
        IllegalArgumentException failure=assertThrows(IllegalArgumentException.class,()->result(root("first","worksSubjectToExcision"),root("second","worksSubjectToExcision")));
        assertEquals("KNOWN_ROOT_BASELINE_KEY_DUPLICATED: worksSubjectToExcision",failure.getMessage());
    }

    @Test void dottedChildKeyRemainsAnExactConditionAndValueReference() {
        DraftKnownRoots.Result known=result(root("persisted-number","contractTitle.number"));Map<String,Map<String,Object>> visible=known.forTasks(Collections.singletonList(TASK));
        Map<String,Object> rule=rule("fill-number","contractTitle.number","fill");rule.put("valueSourceId","contractTitle.number");
        JsonNode raw=JsonUtils.mapper().valueToTree(obj("nodes",Collections.emptyList(),"requirements",Collections.emptyList(),"scopeRules",Collections.singletonList(rule)));
        DraftScopeRuleDraft.Result compiled=DraftScopeRuleDraft.compile(raw,Collections.singletonMap(TASK,"Insert the current contract number."),visible);
        assertTrue(compiled.issues().isEmpty());assertEquals(1,compiled.compilations().size());JsonNode node=compiled.response().path("nodes").path(0);
        assertEquals("contractTitle.number",node.path("branches").path(0).path("clauses").path(0).path("inputId").asText());assertEquals("contractTitle.number",node.path("effects").path(0).path("valueSourceId").asText());assertEquals("persisted-number",visible.get("contractTitle.number").get("id"));
    }

    @Test void aRuleCannotReplaceTheKnownBusinessId() {
        DraftKnownRoots.Result known=result(root("persisted-root","worksSubjectToExcision"));JsonNode raw=JsonUtils.mapper().valueToTree(obj("nodes",Collections.emptyList(),"requirements",Collections.emptyList(),"scopeRules",Collections.singletonList(rule("worksSubjectToExcision","worksSubjectToExcision","keep"))));
        DraftScopeRuleDraft.Result compiled=DraftScopeRuleDraft.compile(raw,Collections.singletonMap(TASK,"Retain the clause when applicable."),known.forTasks(Collections.singletonList(TASK)));
        assertTrue(compiled.compilations().isEmpty());assertEquals("SCOPE_RULE_ID_DUPLICATED",map(compiled.issues().get(0)).get("reason"));assertTrue(compiled.response().path("nodes").isEmpty());
    }

    private static Map<String,Object> rule(String id,String input,String action){return obj("id",id,"sourceTaskId",TASK,"scopeId","current-scope","when",obj("mode","all","clauses",Collections.singletonList(obj("inputId",input,"operator","notempty"))),"action",action);}
    private static Map<String,Object> root(String id,String key){return obj("id",id,"nameEn",key,"status","pending","type",obj("kind","text"),"role",obj("kind","input"),"currentDefinitionReference",obj("baselineKey",key));}
    @SafeVarargs private static DraftKnownRoots.Result result(Map<String,Object>... nodes) {
        Map<String,Map<String,Object>> roots=new LinkedHashMap<>();Map<String,Set<String>> owners=new LinkedHashMap<>(),dependencies=new LinkedHashMap<>(),sources=new LinkedHashMap<>();Map<String,Map<String,String>> quotes=new LinkedHashMap<>();
        for(Map<String,Object> node:nodes){String id=text(node.get("id"));roots.put(id,node);owners.put(id,Collections.singleton(TASK));dependencies.put(id,Collections.singleton(id));sources.put(id,Collections.singleton("source-current"));quotes.put(id,Collections.singletonMap(TASK,"Current registered instruction."));}
        return new DraftKnownRoots.Result(roots,owners,quotes,dependencies,Collections.singletonMap(TASK,"source-current"),sources,DraftCatalogAlignment.definitions().keySet());
    }
}
