package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Compiler contracts only; no model, product API, database or browser execution. */
class DraftScopeRuleDraftTest {
    private static final String QUOTE="(Alternative (a) - for tenders with BQ issued in L10Pro system)";

    @Test void readonlyKnownRootCompilesToADerivedScopeActionWithoutCreatingAnotherQuestion() {
        ObjectNode raw=response(rule("a","t1","scope-a","keep","k3","L10Pro"));String before=raw.toString();DraftScopeRuleDraft.Result result=compile(raw);JsonNode node=result.response().path("nodes").path(0);
        assertTrue(result.issues().isEmpty());assertEquals("a",node.path("id").asText());assertEquals("derived",node.path("role").asText());assertEquals("boolean",node.path("type").asText());assertEquals(QUOTE,node.path("sourceQuote").asText());
        assertEquals("k3",node.path("branches").path(0).path("clauses").path(0).path("inputId").asText());assertTrue(node.path("branches").path(0).path("resultValue").asBoolean());assertEquals("scope-a",node.path("effects").path(0).path("scopeId").asText());assertEquals("match",node.path("effects").path(0).path("branchId").asText());
        assertEquals("scope_rules_compilation",node.path("origin").asText());assertEquals("a",node.path("compiledScopeRuleId").asText());JsonNode requirement=result.response().path("requirements").path(0);assertEquals("amendment",requirement.path("kind").asText());assertEquals("a",requirement.path("nodeIds").path(0).asText());assertEquals(QUOTE,requirement.path("sourceQuote").asText());assertEquals("scope_rules_compilation",requirement.path("origin").asText());assertTrue(requirement.path("reason").asText().contains("harness projection"));assertEquals(before,raw.toString());assertEquals(raw.path("scopeRules"),result.response().path("scopeRules"));
    }
    @Test void alternativesAndTwoEnvelopeConditionsKeepSeparateScopesAndActions() {
        ObjectNode first=rule("a","t1","scope-a","keep","k3","L10Pro"),second=rule("b","t2","scope-b","delete","k3","L10Pro");
        first.with("when").withArray("clauses").addObject().put("inputId","k9").put("operator","eq").put("value",true);second.with("when").put("mode","any");DraftScopeRuleDraft.Result result=compile(response(first,second));assertTrue(result.issues().isEmpty());assertEquals(2,result.response().path("nodes").size());
        JsonNode a=result.response().path("nodes").path(0),b=result.response().path("nodes").path(1);assertEquals(2,a.path("branches").path(0).path("clauses").size());assertEquals("all",a.path("branches").path(0).path("mode").asText());assertEquals("any",b.path("branches").path(0).path("mode").asText());assertEquals("scope-a",a.path("effects").path(0).path("scopeId").asText());assertEquals("scope-b",b.path("effects").path(0).path("scopeId").asText());assertEquals("t2",b.path("sourceTaskId").asText());
    }
    @Test void emptyOrUnknownConditionsNeverBecomeAnUnconditionalAmendment() {
        for(String condition:Arrays.asList("absent","null","empty","unknown")) {
            ObjectNode rule=rule("a","t1","scope-a","delete","k3","L10Pro");if("absent".equals(condition))rule.remove("when");if("null".equals(condition))rule.putNull("when");if("empty".equals(condition))rule.with("when").putArray("clauses");if("unknown".equals(condition))rule.put("when","unknown");
            DraftScopeRuleDraft.Result result=compile(response(rule));assertTrue(result.response().path("nodes").isEmpty(),condition);assertEquals(1,result.issues().size(),condition);assertEquals(rule,result.response().path("scopeRules").path(0));
        }
    }
    @Test void missingProjectAnswerCannotAuthorizeDeletion(){
        ObjectNode rule=rule("a","t1","scope-a","delete","k3","L10Pro");((ObjectNode)rule.path("when").path("clauses").path(0)).put("operator","unknown");
        DraftScopeRuleDraft.Result result=DraftScopeRuleDraft.compile(response(rule),quotes(),roots());assertTrue(result.compilations().isEmpty());assertTrue(JsonUtils.write(result.issues()).contains("SCOPE_RULE_UNKNOWN_VALUE_IS_NOT_AUTHORITY"));
    }
    @Test void explicitCheckRemainsVerificationWithoutABranchOrAdoptedValue() {
        ObjectNode check=rule("review","t1","scope-a","check","k3","L10Pro");check.remove("when");DraftScopeRuleDraft.Result result=compile(response(check));assertTrue(result.issues().isEmpty());JsonNode node=result.response().path("nodes").path(0);
        assertEquals("verification",node.path("role").asText());assertTrue(node.path("branches").isEmpty());assertEquals("check",node.path("effects").path(0).path("action").asText());assertFalse(node.path("effects").path(0).has("branchId"));assertFalse(node.has("fixedValue"));assertEquals("review_only",result.compilations().get(0).get("conditionDisposition"));
        ObjectNode conditional=rule("review","t1","scope-a","check","k3","L10Pro");DraftScopeRuleDraft.Result retained=compile(response(conditional));assertTrue(retained.issues().isEmpty());assertTrue(retained.response().path("nodes").path(0).path("branches").isEmpty());assertEquals(conditional.path("when"),retained.response().path("scopeRules").path(0).path("when"));assertEquals(Collections.singletonList("k3"),retained.compilations().get(0).get("inputIds"));
    }
    @Test void unknownOwnerAndForeignOrVerificationValueReferencesFailLocally() {
        for(String problem:Arrays.asList("owner","empty-quote","foreign","verification","missing-value","native")) {
            ObjectNode rule=rule("a","t1","scope-a","keep","k3","L10Pro");Map<String,String> quotes=quotes();Map<String,Map<String,Object>> roots=roots();
            if("owner".equals(problem))rule.put("sourceTaskId","not-supplied");if("empty-quote".equals(problem))quotes.put("t1"," ");if("foreign".equals(problem))((ObjectNode)rule.path("when").path("clauses").path(0)).put("inputId","new-atom");if("verification".equals(problem))roots.get("k3").put("role",obj("kind","verification"));if("missing-value".equals(problem))((ObjectNode)rule.path("when").path("clauses").path(0)).remove("value");if("native".equals(problem))rule.putObject("native").put("start",1);
            DraftScopeRuleDraft.Result result=DraftScopeRuleDraft.compile(response(rule),quotes,roots);assertTrue(result.response().path("nodes").isEmpty(),problem);assertEquals(1,result.issues().size(),problem);
        }
    }
    @Test void ruleIdsCannotOverwriteKnownRootsOrdinaryAtomsOrOneAnother() {
        for(String collision:Arrays.asList("known","atom","rule")) {
            ObjectNode raw=response(rule("a","t1","scope-a","keep","k3","L10Pro"));if("known".equals(collision))((ObjectNode)raw.path("scopeRules").path(0)).put("id","k3");if("atom".equals(collision))raw.withArray("nodes").addObject().put("id","a").put("role","input");if("rule".equals(collision))raw.withArray("scopeRules").add(raw.path("scopeRules").path(0).deepCopy());
            DraftScopeRuleDraft.Result result=compile(raw);assertTrue(result.compilations().isEmpty(),collision);assertFalse(result.issues().isEmpty(),collision);assertEquals(raw.path("nodes"),result.response().path("nodes"));for(Object issue:result.issues())assertEquals("SCOPE_RULE_ID_DUPLICATED",map(issue).get("reason"));
        }
    }
    @Test void descriptiveRuleIdsUpTo64CharactersStayExactAndStable() {
        String observed="rule_t1_keep_yes_excision",longest=String.join("",Collections.nCopies(64,"r"));assertEquals(25,observed.length());assertEquals(64,longest.length());
        ObjectNode raw=response(rule(observed,"t1","scope-a","keep","k3","L10Pro"),rule(longest,"t2","scope-b","delete","k3","Hardcopy"));String before=raw.toString();DraftScopeRuleDraft.Result first=compile(raw),second=compile(raw);
        assertTrue(first.issues().isEmpty());assertEquals(first.response(),second.response());assertEquals(first.compilations(),second.compilations());assertEquals(before,raw.toString());
        for(int index=0;index<2;index++){String id=index==0?observed:longest;assertEquals(id,first.response().path("nodes").path(index).path("id").asText());assertEquals(id,first.response().path("nodes").path(index).path("compiledScopeRuleId").asText());assertEquals(id,first.response().path("requirements").path(index).path("nodeIds").path(0).asText());assertEquals(id,first.compilations().get(index).get("nodeId"));}
    }
    @Test void oversizedAndMalformedRuleIdsAreNotSilentlyTruncatedOrRenamed() {
        for(String id:Arrays.asList("",String.join("",Collections.nCopies(65,"r")),"rule with spaces","_leading","rule.with.dot")){
            ObjectNode raw=response(rule(id,"t1","scope-a","keep","k3","L10Pro"));String before=raw.toString();DraftScopeRuleDraft.Result result=compile(raw);assertTrue(result.compilations().isEmpty(),id);assertTrue(result.response().path("nodes").isEmpty(),id);assertEquals(1,result.issues().size());assertEquals("SCOPE_RULE_ID_INVALID",map(result.issues().get(0)).get("reason"));assertEquals(id,map(result.issues().get(0)).get("scopeRuleId"));assertEquals(before,raw.toString());
        }
        ObjectNode numeric=rule("a","t1","scope-a","keep","k3","L10Pro").put("id",123);assertEquals("SCOPE_RULE_ID_INVALID",map(compile(response(numeric)).issues().get(0)).get("reason"));numeric.remove("id");assertEquals("SCOPE_RULE_ID_INVALID",map(compile(response(numeric)).issues().get(0)).get("reason"));
    }
    @Test void longRuleIdsStillRejectExactDuplicatesAndOccupiedAliases() {
        String id=String.join("",Collections.nCopies(64,"r"));
        for(String collision:Arrays.asList("known","atom","rule")){
            ObjectNode raw=response(rule(id,"t1","scope-a","keep","k3","L10Pro"));Map<String,Map<String,Object>> roots=roots();if("known".equals(collision))roots.put(id,roots.get("k3"));if("atom".equals(collision))raw.withArray("nodes").addObject().put("id",id).put("role","input");if("rule".equals(collision))raw.withArray("scopeRules").add(raw.path("scopeRules").path(0).deepCopy());
            DraftScopeRuleDraft.Result result=DraftScopeRuleDraft.compile(raw,quotes(),roots);assertTrue(result.compilations().isEmpty(),collision);assertEquals(raw.path("nodes"),result.response().path("nodes"));assertEquals("rule".equals(collision)?2:1,result.issues().size());for(Object issue:result.issues())assertEquals("SCOPE_RULE_ID_DUPLICATED",map(issue).get("reason"));
        }
    }
    @Test void originalAtomsRequirementsOutcomesAndRawResponseStayUntouched() {
        ObjectNode raw=response(rule("good","t1","scope-a","keep","k3","L10Pro"),rule("bad","not-supplied","scope-b","delete","k3","Hardcopy"));
        ObjectNode atom=raw.withArray("nodes").addObject().put("id","new-fact").put("role","input").put("nameEn","New independent fact");ObjectNode requirement=raw.withArray("requirements").addObject().put("id","model-req").put("sourceTaskId","t1").put("kind","amendment");requirement.putArray("nodeIds").add("good");raw.putArray("taskOutcomes").addObject().put("sourceTaskId","t1").put("status","unresolved");String before=raw.toString();
        DraftScopeRuleDraft.Result result=compile(raw);assertEquals(1,result.compilations().size());assertEquals(1,result.issues().size());assertEquals(atom,result.response().path("nodes").path(0));assertEquals(requirement,result.response().path("requirements").path(0));assertEquals(raw.path("taskOutcomes"),result.response().path("taskOutcomes"));assertEquals(before,raw.toString());
        ((ObjectNode)result.response().path("nodes").path(1)).put("id","mutated");result.compilations().get(0).put("nodeId","mutated");map(result.issues().get(0)).put("reason","mutated");assertEquals("good",result.response().path("nodes").path(1).path("id").asText());assertEquals("good",result.compilations().get(0).get("nodeId"));assertFalse("mutated".equals(map(result.issues().get(0)).get("reason")));assertFalse(JsonUtils.write(result.compilations()).contains(QUOTE));
    }
    @Test void replacementPayloadIsCarriedVerbatimWithoutInventingMissingContent() {
        ObjectNode rule=rule("a","t1","scope-a","replace","k3","L10Pro").put("replacementEn","Exact model proposed wording").put("valueSourceId","k5");DraftScopeRuleDraft.Result result=compile(response(rule));assertTrue(result.issues().isEmpty());JsonNode effect=result.response().path("nodes").path(0).path("effects").path(0);assertEquals("Exact model proposed wording",effect.path("replacementEn").asText());assertEquals("k5",effect.path("valueSourceId").asText());
        rule.remove(Arrays.asList("replacementEn","valueSourceId"));DraftScopeRuleDraft.Result incomplete=compile(response(rule));assertTrue(incomplete.issues().isEmpty());assertFalse(incomplete.response().path("nodes").path(0).path("effects").path(0).has("replacementEn"));assertFalse(incomplete.response().path("nodes").path(0).path("effects").path(0).has("valueSourceId"));
    }
    @Test void noScopeRulesPreservesTheExistingAtomicProtocol() {
        ObjectNode raw=JsonUtils.mapper().createObjectNode();raw.putArray("nodes").addObject().put("id","atom");DraftScopeRuleDraft.Result result=compile(raw);assertEquals(raw,result.response());assertTrue(result.compilations().isEmpty());assertTrue(result.issues().isEmpty());
    }
    @Test void characterRangeProposalRemainsAProposalForTheExistingSourceBinder(){
        ObjectNode rule=rule("a","t1","scope-a","delete","k3","L10Pro").put("refinedQuote","Exact current phrase");DraftScopeRuleDraft.Result result=compile(response(rule));assertTrue(result.issues().isEmpty());assertEquals("Exact current phrase",result.response().path("nodes").path(0).path("effects").path(0).path("refinedQuote").asText());assertFalse(result.response().path("nodes").path(0).path("effects").path(0).has("native"));
    }
    @Test void allStructuralActionAndCollisionGuardsPrecedeKnownReferenceLookup(){
        Map<String,String> expected=new LinkedHashMap<>();expected.put("later-field","SCOPE_RULE_CLAUSE_FIELD_UNSUPPORTED");expected.put("later-type","SCOPE_RULE_TEXT_REQUIRED: inputId");expected.put("replacement-type","SCOPE_RULE_REPLACEMENT_TEXT_REQUIRED");expected.put("value-source-type","SCOPE_RULE_TEXT_REQUIRED: valueSourceId");expected.put("action-value","SCOPE_RULE_ACTION_VALUE_UNEXPECTED");expected.put("node-limit","SCOPE_RULE_COMBINED_NODE_LIMIT_100");expected.put("csr-collision","SCOPE_RULE_REQUIREMENT_ID_COLLISION");expected.put("clause-limit","SCOPE_RULE_CLAUSE_LIMIT_100");expected.put("unknown-only","SCOPE_RULE_KNOWN_VALUE_REFERENCE_REQUIRED");
        for(Map.Entry<String,String> entry:expected.entrySet()){
            String defect=entry.getKey();ObjectNode candidate=rule("a","t1","scope-a","keep","unprovided_local","L10Pro"),raw=response(candidate);if("later-field".equals(defect))candidate.with("when").withArray("clauses").addObject().put("inputId","k3").put("operator","eq").put("value","L10Pro").put("unexpectedField",true);if("later-type".equals(defect))candidate.with("when").withArray("clauses").addObject().put("inputId",123).put("operator","known");if("replacement-type".equals(defect))candidate.put("action","replace").put("replacementEn",123);if("value-source-type".equals(defect))candidate.put("action","fill").putObject("valueSourceId");if("action-value".equals(defect))candidate.put("replacementEn","Illegal keep payload");if("node-limit".equals(defect))for(int index=0;index<100;index++)raw.withArray("nodes").addObject().put("id","existing_"+index);if("csr-collision".equals(defect)){List<Map<String,Object>> trusted=compile(response(rule("a","t1","scope-a","keep","k3","L10Pro"))).compilations();assertEquals(1,trusted.size());raw.withArray("requirements").addObject().put("id",text(trusted.get(0).get("requirementId")));}if("clause-limit".equals(defect))for(int index=0;index<100;index++)candidate.with("when").withArray("clauses").addObject().put("inputId","k3").put("operator","known");String before=raw.toString();DraftScopeRuleDraft.Result result=compile(raw);assertEquals(1,result.issues().size(),defect);assertEquals(entry.getValue(),map(result.issues().get(0)).get("reason"),defect);assertTrue(result.compilations().isEmpty(),defect);assertEquals(raw.path("nodes"),result.response().path("nodes"));assertEquals(raw.path("requirements"),result.response().path("requirements"));assertEquals(before,raw.toString());
        }
    }

    private static DraftScopeRuleDraft.Result compile(JsonNode response){return DraftScopeRuleDraft.compile(response,quotes(),roots());}
    private static Map<String,String> quotes(){Map<String,String> quotes=new LinkedHashMap<>();quotes.put("t1",QUOTE);quotes.put("t2","(Alternative (b) - for tenders with BQ issued in hard copy)");return quotes;}
    private static Map<String,Map<String,Object>> roots(){Map<String,Map<String,Object>> roots=new LinkedHashMap<>();for(String alias:Arrays.asList("k3","k5","k9"))roots.put(alias,obj("id","canonical-"+alias,"role",obj("kind","input"),"status","pending","currentDefinitionReference",obj("taskIds",Collections.emptyList())));return roots;}
    private static ObjectNode response(ObjectNode... rules){ObjectNode response=JsonUtils.mapper().createObjectNode();response.putArray("nodes");response.putArray("requirements");response.putArray("scopeRules");for(ObjectNode rule:rules)response.withArray("scopeRules").add(rule);return response;}
    private static ObjectNode rule(String id,String owner,String scope,String action,String alias,String value){ObjectNode rule=JsonUtils.mapper().createObjectNode().put("id",id).put("sourceTaskId",owner).put("scopeId",scope).put("action",action);rule.putObject("when").put("mode","all").putArray("clauses").addObject().put("inputId",alias).put("operator","eq").put("value",value);return rule;}
}
