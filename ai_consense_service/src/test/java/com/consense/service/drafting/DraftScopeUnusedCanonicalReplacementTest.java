package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Source-only compiler contracts; no model, product API, persistence or browser execution. */
class DraftScopeUnusedCanonicalReplacementTest {
    @Test void onlyTheCanonicalUnusedTextIsOmittedFromTheCompiledEffectAndRecorded(){
        for(String value:Arrays.asList("Not used"," \tNot used\r\n")){
            ObjectNode rule=rule("unused").put("replacementEn",value);rule.put("refinedQuote","Current source scope");ObjectNode raw=response(rule);String before=raw.toString();DraftScopeRuleDraft.Result result=compile(raw);
            assertTrue(result.issues().isEmpty());assertEquals(1,result.compilations().size());JsonNode node=result.response().path("nodes").path(0),effect=node.path("effects").path(0);
            assertEquals("unused",effect.path("action").asText());assertFalse(effect.has("replacementEn"));assertFalse(effect.has("valueSourceId"));assertEquals("Current source scope",effect.path("refinedQuote").asText());assertEquals("t1",effect.path("sourceTaskId").asText());assertEquals("s1",effect.path("scopeId").asText());assertEquals(rule.path("when").path("clauses"),node.path("branches").path(0).path("clauses"));
            assertEquals(rule,result.response().path("scopeRules").path(0));assertEquals(before,raw.toString());Map<String,Object> normalization=map(list(result.compilations().get(0).get("normalizations")).get(0));assertEquals("unused_canonical_replacement_omitted",normalization.get("kind"));assertEquals("replacementEn",normalization.get("field"));assertEquals("Not used",normalization.get("canonicalValue"));assertEquals("original_scope_rule",normalization.get("retained"));
        }
    }

    @Test void unusedCannotCarryCustomTextOrAValueSource(){
        for(String value:Arrays.asList("9. Not used","NOT USED","Not Used","Not used.","Not  used","Other wording"))assertRejected(rule("unused").put("replacementEn",value));
        assertRejected(rule("unused").put("valueSourceId","wtoGpaApplies"));assertRejected(rule("unused").put("replacementEn","Not used").put("valueSourceId","wtoGpaApplies"));
    }

    @Test void keepDeleteAndCheckStillRejectReplacementAndValuePayloads(){
        for(String action:Arrays.asList("keep","delete","check")){
            assertRejected(rule(action).put("replacementEn","Not used"));assertRejected(rule(action).put("valueSourceId","wtoGpaApplies"));
        }
    }

    @Test void anUnusedRuleWithoutRedundancyAndARealReplacementAreNotNormalized(){
        DraftScopeRuleDraft.Result unused=compile(response(rule("unused")));assertTrue(unused.issues().isEmpty());assertFalse(unused.compilations().get(0).containsKey("normalizations"));
        DraftScopeRuleDraft.Result replacement=compile(response(rule("replace").put("replacementEn","Not used")));assertTrue(replacement.issues().isEmpty());assertEquals("Not used",replacement.response().path("nodes").path(0).path("effects").path(0).path("replacementEn").asText());assertFalse(replacement.compilations().get(0).containsKey("normalizations"));
    }

    private static void assertRejected(ObjectNode rule){ObjectNode raw=response(rule);String before=raw.toString();DraftScopeRuleDraft.Result result=compile(raw);assertTrue(result.compilations().isEmpty());assertTrue(result.response().path("nodes").isEmpty());assertEquals(1,result.issues().size());assertEquals("SCOPE_RULE_ACTION_VALUE_UNEXPECTED",map(result.issues().get(0)).get("reason"));assertEquals(raw,result.response());assertEquals(before,raw.toString());}
    private static DraftScopeRuleDraft.Result compile(JsonNode raw){return DraftScopeRuleDraft.compile(raw,Collections.singletonMap("t1","(“Not used” if the contract is not subject to WTO GPA)"),Collections.singletonMap("wtoGpaApplies",obj("id","current-wto","role",obj("kind","input"),"status","pending")));}
    private static ObjectNode response(ObjectNode rule){ObjectNode raw=JsonUtils.mapper().createObjectNode();raw.putArray("nodes");raw.putArray("requirements");raw.putArray("scopeRules").add(rule);return raw;}
    private static ObjectNode rule(String action){ObjectNode rule=JsonUtils.mapper().createObjectNode().put("id","rule_t1_wto_not_used").put("sourceTaskId","t1").put("scopeId","s1").put("action",action);rule.putObject("when").put("mode","all").putArray("clauses").addObject().put("inputId","wtoGpaApplies").put("operator","eq").put("value",false);return rule;}
}
