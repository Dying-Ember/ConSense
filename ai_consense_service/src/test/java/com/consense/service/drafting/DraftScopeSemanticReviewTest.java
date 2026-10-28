package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Source-only contracts for semantic references; no model, product API, database or browser calls. */
class DraftScopeSemanticReviewTest {
    @Test void build21AmendMisreadAsDeleteRequestsOneNamedReviewWithoutRewritingTheDraft() {
        Map<String,Object> payload=packet();ObjectNode draft=response(rule("rule_t1_keep_nsc","keep"),rule("rule_t1_delete_nsc","delete"));
        ((ObjectNode)draft.path("scopeRules").path(1).path("when").path("clauses").path(0)).put("operator","ne");
        String sourceBefore=JsonUtils.write(payload),draftBefore=draft.toString();List<Object> requests=DraftScopeConditionReview.review(payload,draft);assertEquals(1,requests.size());Map<String,Object> request=map(requests.get(0));
        assertEquals("scope_semantic_reference_without_bound_target",request.get("reason"));assertEquals("t1",request.get("sourceTaskId"));assertEquals(Collections.singletonList("t1"),request.get("sourceTaskIds"));
        assertEquals(Collections.singletonList("s1"),request.get("scopeIds"));assertEquals(Arrays.asList("rule_t1_keep_nsc","rule_t1_delete_nsc"),request.get("ruleIds"));assertEquals(Collections.singletonList("NTT-13-SUBCONTRACT"),request.get("referenceActionIds"));
        assertEquals(false,request.get("editableAuthority"));assertEquals(true,request.get("reviewOnly"));assertFalse(request.containsKey("action"));assertFalse(request.containsKey("when"));assertFalse(request.containsKey("referenceCases"));assertFalse(request.containsKey("missingReferenceCases"));
        assertTrue(text(request.get("reviewInstruction")).contains("return check"));assertTrue(text(request.get("reviewInstruction")).contains("including keep"));assertTrue(text(request.get("reviewInstruction")).contains("independent source-bound new scope"));
        assertEquals(sourceBefore,JsonUtils.write(payload));assertEquals(draftBefore,draft.toString());
    }

    @Test void everyNonCheckActionOnAnUnboundSemanticTargetStillNeedsItsOwnRange() {
        for(String action:Arrays.asList("keep","delete","unused","replace","fill","insert")) {
            ObjectNode candidate=rule("pending_rule",action);if("replace".equals(action))candidate.put("replacementEn","The exact adopted replacement for the whole provision.").put("refinedQuote","Such requirement");
            List<Object> requests=DraftScopeConditionReview.review(packet(),response(candidate));assertEquals(1,requests.size(),action);assertEquals(Collections.singletonList("pending_rule"),map(requests.get(0)).get("ruleIds"),action);
        }
        assertTrue(DraftScopeConditionReview.review(packet(),response(rule("unresolved","check"))).isEmpty());
    }

    @Test void completeObservedHalfSentenceAndFetchedParentDoNotGrantEditingAuthority() {
        Map<String,Object> payload=packet();map(list(payload.get("sharedEvidence")).get(0)).put("scopeComplete",true);
        list(payload.get("sharedEvidence")).add(obj("kind","member","id","parent","sourceId","source","text","The complete current parent paragraph."));
        ObjectNode candidate=rule("replace_half","replace");candidate.put("valueSourceId","nscAlternativeText").put("refinedQuote","Such requirement");
        assertEquals(1,DraftScopeConditionReview.review(payload,response(candidate)).size());
    }

    @Test void aCurrentBoundExactReferenceDoesNotEnterTheSemanticOnlyGuard() {
        Map<String,Object> payload=packet();Map<String,Object> instruction=instruction(payload),alignment=alignment(payload);alignment.put("definitionStatus","exact_instruction_reference");alignment.put("scopeStatus","current_observation_complete");
        alignment.remove("fixedActionSemanticReferences");instruction.put("fixedActionReferences",Collections.singletonList(obj("actionId","NTT-13-SUBCONTRACT","inputKeys",Arrays.asList("subcontractArrangement","nscAlternativeText"))));
        assertTrue(DraftScopeConditionReview.review(payload,response(rule("current_pending_replacement","replace"))).isEmpty(),"This guard adds no authority and must not supersede the separately proven current-target path");
    }

    @Test void thisGuardRequiresTheExplicitSemanticContractRatherThanAnAmendKeyword() {
        Map<String,Object> payload=packet();instruction(payload).put("text","An instruction without an amend keyword.");assertEquals(1,DraftScopeConditionReview.review(payload,response(rule("remove","delete"))).size());
        for(String problem:Arrays.asList("kind","authority-true","authority-missing","scope-status","no-semantic-reference")) {
            payload=packet();Map<String,Object> reference=map(list(alignment(payload).get("fixedActionSemanticReferences")).get(0));
            if("kind".equals(problem))reference.put("referenceKind","historical_reference");if("authority-true".equals(problem))reference.put("editableAuthority",true);if("authority-missing".equals(problem))reference.remove("editableAuthority");if("scope-status".equals(problem))reference.put("scopeStatus","bound");if("no-semantic-reference".equals(problem))alignment(payload).remove("fixedActionSemanticReferences");
            assertTrue(DraftScopeConditionReview.review(payload,response(rule("remove","delete"))).isEmpty(),problem);
        }
    }

    @Test void foreignOrAmbiguousOwnersAndUnprovenScopeIdentityCannotBorrowTheSemanticReference() {
        for(String problem:Arrays.asList("foreign-owner","foreign-scope","wrong-source","missing-evidence-ref","duplicate-evidence","duplicate-owner","unlisted-scope","duplicate-rule")) {
            Map<String,Object> payload=packet();ObjectNode candidate=rule("remove","delete");
            if("foreign-owner".equals(problem))candidate.put("sourceTaskId","t2");if("foreign-scope".equals(problem))candidate.put("scopeId","s2");if("wrong-source".equals(problem))alignment(payload).put("sourceId","foreign");if("missing-evidence-ref".equals(problem))instruction(payload).put("evidenceRefs",Collections.emptyList());
            if("duplicate-evidence".equals(problem))list(payload.get("sharedEvidence")).add(list(payload.get("sharedEvidence")).get(0));if("duplicate-owner".equals(problem))list(payload.get("instructions")).add(instruction(payload));if("unlisted-scope".equals(problem))alignment(payload).put("currentScopeIds",Collections.emptyList());
            assertTrue(DraftScopeConditionReview.review(payload,"duplicate-rule".equals(problem)?response(candidate,candidate.deepCopy()):response(candidate)).isEmpty(),problem);
        }
    }

    @Test void semanticReferencesDoNotGainFiniteCoverageOrAlterSavedEvidenceDuringEnrichment() {
        Map<String,Object> payload=packet();String before=JsonUtils.write(payload);Map<String,Object> enriched=DraftScopeConditionReview.enrich(payload);assertEquals(before,JsonUtils.write(payload));
        JsonNode reference=JsonUtils.mapper().valueToTree(enriched).path("instructions").path(0).path("currentTemplateAlignment").path("fixedActionSemanticReferences").path(0);
        assertFalse(reference.path("editableAuthority").asBoolean());assertFalse(reference.has("referenceCases"));assertFalse(reference.has("native"));assertFalse(reference.has("editTargets"));assertEquals(1,DraftScopeConditionReview.review(enriched,response(rule("remove","delete"))).size());
    }

    @Test void evenAnActionWithFiniteCasesKeepsItsSemanticReferenceSeparateFromCoverage() {
        Map<String,Object> payload=packet();map(list(alignment(payload).get("fixedActionSemanticReferences")).get(0)).put("actionId","NTT-3C-L10PRO");
        List<Object> requests=DraftScopeConditionReview.review(DraftScopeConditionReview.enrich(payload),response(rule("pending","keep")));assertEquals(1,requests.size());Map<String,Object> request=map(requests.get(0));
        assertEquals("scope_semantic_reference_without_bound_target",request.get("reason"));assertFalse(request.containsKey("referenceCases"));assertFalse(request.containsKey("missingReferenceCases"));assertEquals(Collections.singletonList("NTT-3C-L10PRO"),request.get("referenceActionIds"));
    }

    private static Map<String,Object> packet() {
        Map<String,Object> semantic=obj("actionId","NTT-13-SUBCONTRACT","referenceKind","fixed_action_semantic_reference","editableAuthority",false,"scopeStatus","requires_review",
                "inputKeys",Arrays.asList("subcontractArrangement","nscAlternativeText"),"detailEn","NSC retains the standard wording; BSSSC uses the exact adopted NTT13 replacement. Preserve the clause number and requirement.");
        Map<String,Object> alignment=obj("definitionStatus","definition_reference_only","scopeStatus","requires_review","referenceKind","current_instruction_reference","editableAuthority",false,"sourceId","source","currentScopeIds",Collections.singletonList("s1"),"fixedActionSemanticReferences",new ArrayList<>(Collections.singletonList(semantic)));
        Map<String,Object> instruction=obj("sourceTaskId","t1","sourceId","source","text","(*Amend if NSC is not applicable)","scopeObligationIds",new ArrayList<>(Collections.singletonList("s1")),"evidenceRefs",Collections.singletonList("source|scope|s1"),"currentTemplateAlignment",alignment);
        return obj("instructions",new ArrayList<>(Collections.singletonList(instruction)),"sharedEvidence",new ArrayList<>(Collections.singletonList(obj("ref","source|scope|s1","kind","scope","sourceId","source","id","s1","scopeComplete",true,"text","Such requirement does not apply to engagement of Nominated Sub-contractor."))),"knownRoots",Collections.emptyList());
    }
    private static ObjectNode rule(String id,String action){ObjectNode result=JsonUtils.mapper().createObjectNode().put("id",id).put("sourceTaskId","t1").put("scopeId","s1").put("action",action);result.putObject("when").put("mode","all").putArray("clauses").addObject().put("inputId","subcontractArrangement").put("operator","eq").put("value","NSC");return result;}
    private static ObjectNode response(ObjectNode... rules){ObjectNode result=JsonUtils.mapper().createObjectNode();result.putArray("scopeRules");for(ObjectNode rule:rules)result.withArray("scopeRules").add(rule);return result;}
    private static Map<String,Object> instruction(Map<String,Object> payload){return map(list(payload.get("instructions")).get(0));}
    private static Map<String,Object> alignment(Map<String,Object> payload){return map(instruction(payload).get("currentTemplateAlignment"));}
    @SuppressWarnings("unchecked") private static List<Object> list(Object value){return value instanceof List?(List<Object>)value:Collections.emptyList();}
}
