package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Observed model formatting contracts only; no model, API, database or browser execution. */
class DraftScopeOptionalFieldsTest {
    @Test void nullOptionalFieldsInTheExcisionResponseCompileExactlyLikeOmittedFields() {
        ObjectNode raw=response();for(JsonNode rule:raw.path("scopeRules"))for(String field:fields())((ObjectNode)rule).putNull(field);
        String original=raw.toString();DraftScopeRuleDraft.Result compiled=compile(raw),omitted=compile(response());
        assertTrue(compiled.issues().isEmpty(),JsonUtils.write(compiled.issues()));assertEquals(2,compiled.compilations().size());assertEquals(original,raw.toString());assertEquals(raw.path("scopeRules"),compiled.response().path("scopeRules"));
        assertEquals(omitted.response().path("nodes"),compiled.response().path("nodes"));assertEquals(omitted.response().path("requirements"),compiled.response().path("requirements"));
        for(JsonNode node:compiled.response().path("nodes")){JsonNode effect=node.path("effects").path(0);for(String field:fields())assertFalse(effect.has(field),field);assertEquals("k10",node.path("branches").path(0).path("clauses").path(0).path("inputId").asText());}
    }

    @Test void nonNullEmptyOrWrongTypedOptionalFieldsStillRejectTheOriginalRule() {
        for(String field:fields())for(Object value:Arrays.asList("",42,true,Collections.emptyList(),Collections.emptyMap())) {
            ObjectNode raw=response();((ObjectNode)raw.path("scopeRules").path(0)).set(field,JsonUtils.mapper().valueToTree(value));String original=raw.toString();DraftScopeRuleDraft.Result result=compile(raw);
            assertEquals(1,result.issues().size(),field+"="+value);assertEquals(1,result.compilations().size());assertEquals("rule_t1_keep_with_excision",result.response().path("nodes").path(0).path("id").asText());assertEquals(original,raw.toString());assertEquals(raw.path("scopeRules"),result.response().path("scopeRules"));
        }
    }

    private static List<String> fields(){return Arrays.asList("replacementEn","refinedQuote","valueSourceId");}
    private static DraftScopeRuleDraft.Result compile(JsonNode raw){return DraftScopeRuleDraft.compile(raw,Collections.singletonMap("t1","(Delete if there are no Works Subject to Excision)"),Collections.singletonMap("k10",obj("id","current-excision","role",obj("kind","input"),"status","pending")));}
    private static ObjectNode response(){
        ObjectNode raw=JsonUtils.mapper().createObjectNode();raw.putArray("nodes");raw.putArray("requirements");raw.putArray("scopeRules");
        for(boolean applies:new boolean[]{false,true}){ObjectNode rule=raw.withArray("scopeRules").addObject().put("id",applies?"rule_t1_keep_with_excision":"rule_t1_delete_no_excision").put("sourceTaskId","t1").put("scopeId","s1").put("action",applies?"keep":"delete");rule.putObject("when").put("mode","all").putArray("clauses").addObject().put("inputId","k10").put("operator","eq").put("value",applies);}
        raw.putArray("taskOutcomes");raw.putArray("notes");return raw;
    }
}
