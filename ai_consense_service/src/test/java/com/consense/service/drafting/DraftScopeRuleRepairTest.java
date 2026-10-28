package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Merge contracts only; no model, product API, database or browser execution. */
class DraftScopeRuleRepairTest {
    @Test void aSharedScopeRepairCanNameMultipleOwnersWithoutMovingAnyRule(){
        ObjectNode prior=rule("r2","t2","s1"),corrected=withParentPremise(prior);List<Object> issues=new ArrayList<>(),normalizations=new ArrayList<>();
        List<Object> requested=Collections.singletonList(obj("sourceTaskId","t1","sourceTaskIds",Arrays.asList("t1","t2"),"scopeIds",Collections.singletonList("s1"),"ruleIds",Collections.singletonList("r2"),"reason","scope_reference_cases_uncovered"));
        JsonNode merged=DraftAutomaticDiscovery.mergeGroupDraft(response(prior),response(corrected),issues,normalizations,requested);
        assertEquals(corrected,merged.path("scopeRules").path(0));assertTrue(issues.isEmpty());assertEquals("t2",map(normalizations.get(0)).get("sourceTaskId"));
        assertRejected(response(rule("r2","t3","s1")),response(withParentPremise(rule("r2","t3","s1"))),requested);
    }

    @Test void namedPendingRuleCanAcquireMissingPremiseWithoutChangingIdentity() {
        ObjectNode old=rule("r1","t1","s1"),corrected=withParentPremise(old);ObjectNode previous=response(old),next=response(corrected);
        List<Object> issues=new ArrayList<>(),normalizations=new ArrayList<>();String before=previous.toString(),incoming=next.toString();
        JsonNode merged=DraftAutomaticDiscovery.mergeGroupDraft(previous,next,issues,normalizations,repairs("r1","t1","s1"));
        assertEquals(corrected,merged.path("scopeRules").path(0));assertEquals(2,merged.path("scopeRules").path(0).path("when").path("clauses").size());assertTrue(issues.isEmpty());assertEquals(1,normalizations.size());
        Map<String,Object> record=map(normalizations.get(0));assertEquals("scope_rule_targeted_repair",record.get("kind"));assertEquals("r1",record.get("scopeRuleId"));assertEquals("t1",record.get("sourceTaskId"));assertEquals("s1",record.get("scopeId"));
        assertEquals(DraftAdoption.hash(JsonUtils.write(old)),record.get("previousRuleSha256"));assertEquals(DraftAdoption.hash(JsonUtils.write(corrected)),record.get("replacementRuleSha256"));assertEquals("required_parent_premise_missing",record.get("repairReason"));assertNotEquals(record.get("previousRuleSha256"),record.get("replacementRuleSha256"));
        assertEquals(before,previous.toString());assertEquals(incoming,next.toString());((ObjectNode)merged.path("scopeRules").path(0)).put("action","delete");assertEquals(before,previous.toString());assertEquals(incoming,next.toString());
    }

    @Test void ordinaryEvidenceAndUnrequestedRulesCannotOverwritePriorMeaning() {
        ObjectNode prior=rule("r1","t1","s1"),corrected=withParentPremise(prior);
        for(List<Object> requested:Arrays.asList(Collections.<Object>emptyList(),repairs("other","t1","s1"),repairs("r1","other","s1"),repairs("r1","t1","other")))assertRejected(response(prior),response(corrected),requested);
        List<Object> onlyScope=new ArrayList<>();onlyScope.add(obj("sourceTaskId","t1","scopeIds",Collections.singletonList("s1"),"reason","source_scope_disposition_missing"));assertRejected(response(prior),response(corrected),onlyScope);
    }

    @Test void explicitRepairCannotMoveRuleToAnotherOwnerOrScope() {
        ObjectNode prior=rule("r1","t1","s1");
        for(String key:Arrays.asList("sourceTaskId","scopeId")){
            ObjectNode corrected=withParentPremise(prior).put(key,"other");assertRejected(response(prior),response(corrected),repairs("r1","t1","s1"));
            List<Object> widened=new ArrayList<>();widened.add(obj("sourceTaskId",corrected.path("sourceTaskId").asText(),"scopeIds",Arrays.asList("s1","other"),"ruleIds",Collections.singletonList("r1")));assertRejected(response(prior),response(corrected),widened);
        }
    }

    @Test void duplicateIdsInEitherRoundNeverBecomeARepair() {
        ObjectNode prior=rule("r1","t1","s1"),corrected=withParentPremise(prior);
        assertRejected(response(prior,prior.deepCopy()),response(corrected),repairs("r1","t1","s1"));
        assertRejected(response(prior),response(corrected,corrected.deepCopy()),repairs("r1","t1","s1"));
        assertRejected(response(prior),response(corrected,prior.deepCopy()),repairs("r1","t1","s1"));
    }

    @Test void reviewedManualAndUnknownStateRecordsAreNotRawPendingCandidates() {
        for(String field:Arrays.asList("confirmed","adopted","manual","reviewStatus","status","approval"))for(boolean priorState:Arrays.asList(true,false)){
            ObjectNode prior=rule("r1","t1","s1"),corrected=withParentPremise(prior);ObjectNode state=priorState?prior:corrected;
            if(Arrays.asList("confirmed","adopted","manual").contains(field))state.put(field,true);else state.put(field,"confirmed");assertRejected(response(prior),response(corrected),repairs("r1","t1","s1"));
        }
        ObjectNode pending=rule("r1","t1","s1").put("status","unexpected_state");assertRejected(response(pending),response(withParentPremise(pending)),repairs("r1","t1","s1"));
    }

    @Test void identicalEchoDoesNotCreateARepairOrConflict() {
        ObjectNode prior=rule("r1","t1","s1");List<Object> issues=new ArrayList<>(),normalizations=new ArrayList<>();
        JsonNode merged=DraftAutomaticDiscovery.mergeGroupDraft(response(prior),response(prior.deepCopy()),issues,normalizations,repairs("r1","t1","s1"));
        assertEquals(1,merged.path("scopeRules").size());assertEquals(prior,merged.path("scopeRules").path(0));assertTrue(issues.isEmpty());assertTrue(normalizations.isEmpty());
    }

    @Test void repairPreservesOtherRulesAtomsRequirementsNotesAndNewIds() {
        ObjectNode prior=rule("r1","t1","s1"),unrelated=rule("r2","t2","s2"),added=rule("r3","t3","s3");ObjectNode previous=response(prior,unrelated),next=response(withParentPremise(prior),added);
        previous.withArray("nodes").addObject().put("id","business-root").put("sourceTaskId","t1").put("role","input");previous.withArray("requirements").addObject().put("id","original-requirement").put("sourceTaskId","t1").put("kind","input");previous.withArray("notes").add("original unresolved evidence");next.withArray("notes").add("repair explanation");
        String before=previous.toString(),incoming=next.toString();List<Object> issues=new ArrayList<>(),normalizations=new ArrayList<>();JsonNode merged=DraftAutomaticDiscovery.mergeGroupDraft(previous,next,issues,normalizations,repairs("r1","t1","s1"));
        assertEquals(3,merged.path("scopeRules").size());assertEquals(unrelated,merged.path("scopeRules").path(1));assertEquals(added,merged.path("scopeRules").path(2));assertEquals(previous.path("nodes"),merged.path("nodes"));assertEquals(previous.path("requirements"),merged.path("requirements"));assertEquals(2,merged.path("notes").size());assertEquals("original unresolved evidence",merged.path("notes").path(0).asText());assertEquals("repair explanation",merged.path("notes").path(1).asText());assertTrue(issues.isEmpty());assertEquals(1,normalizations.size());assertEquals(before,previous.toString());assertEquals(incoming,next.toString());
    }

    @Test void nontextualIdentityCannotBeAuthorizedByItsStringRendering() {
        for(String field:Arrays.asList("id","sourceTaskId","scopeId")){
            ObjectNode prior=rule("r1","t1","s1").put(field,1),corrected=withParentPremise(prior);
            assertRejected(response(prior),response(corrected),repairs(prior.path("id").asText(),prior.path("sourceTaskId").asText(),prior.path("scopeId").asText()));
        }
    }

    private static void assertRejected(ObjectNode previous,ObjectNode next,List<Object> repairs){
        List<Object> issues=new ArrayList<>(),normalizations=new ArrayList<>();String before=previous.toString(),incoming=next.toString();JsonNode merged=DraftAutomaticDiscovery.mergeGroupDraft(previous,next,issues,normalizations,repairs);
        assertEquals(previous.path("scopeRules").path(0),merged.path("scopeRules").path(0));assertTrue(normalizations.isEmpty());assertTrue(issues.stream().anyMatch(raw->"scope_rule_identity_conflict".equals(map(raw).get("reason"))));assertEquals(before,previous.toString());assertEquals(incoming,next.toString());
    }
    private static List<Object> repairs(String id,String owner,String scope){List<Object> result=new ArrayList<>();result.add(obj("sourceTaskId",owner,"scopeIds",Collections.singletonList(scope),"ruleIds",Collections.singletonList(id),"reason","required_parent_premise_missing"));return result;}
    private static ObjectNode response(ObjectNode... rules){ObjectNode response=JsonUtils.mapper().createObjectNode();for(String key:Arrays.asList("nodes","requirements","scopeRules","taskOutcomes","notes"))response.putArray(key);for(ObjectNode rule:rules)response.withArray("scopeRules").add(rule);return response;}
    private static ObjectNode rule(String id,String owner,String scope){ObjectNode rule=JsonUtils.mapper().createObjectNode().put("id",id).put("sourceTaskId",owner).put("scopeId",scope).put("action","keep");rule.putObject("when").put("mode","all").putArray("clauses").addObject().put("inputId","k3").put("operator","eq").put("value","L10Pro");return rule;}
    private static ObjectNode withParentPremise(ObjectNode prior){ObjectNode result=prior.deepCopy();result.with("when").withArray("clauses").addObject().put("inputId","k9").put("operator","yes");return result;}
}
