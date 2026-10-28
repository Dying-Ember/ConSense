package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Source-only contracts for the observed BQ3(c) omission; no model, product API or database calls. */
class DraftScopeConditionReviewTest {
    @Test void enrichmentReusesTheExistingEvaluatorWithoutChangingTheInputOrCopyingTheCatalog() {
        Map<String,Object> input=packet();String before=JsonUtils.write(input);Map<String,Object> enriched=DraftScopeConditionReview.enrich(input);
        assertEquals(before,JsonUtils.write(input));assertNotSame(input,enriched);JsonNode reference=tree(enriched).path("instructions").path(0).path("fixedActionReferences").path(0);
        assertTrue(reference.path("reviewOnly").asBoolean());assertEquals(DraftBusinessRules.RULE_VERSION,reference.path("referenceRuleVersion").asText());assertEquals(4,reference.path("referenceCases").size());
        for(JsonNode row:reference.path("referenceCases")) {
            Map<String,Object> values=JsonUtils.readMap(row.path("inputs").toString());assertEquals(new HashSet<>(Arrays.asList("twoEnvelopeTendering","electronicTendering")),values.keySet());
            JsonNode action=findAction(DraftBusinessRules.plan(values),"NTT-3C-L10PRO");assertEquals(action.path("action"),row.path("action"));
            if(!row.path("inputs").path("twoEnvelopeTendering").asBoolean())assertEquals("pending",row.path("action").asText());
            assertFalse(row.has("sourceSha256"));assertFalse(row.has("native"));assertFalse(row.has("editTargets"));
        }
        map(list(map(list(enriched.get("instructions")).get(0)).get("fixedActionReferences")).get(0)).put("referenceCases",Collections.emptyList());
        assertEquals(4,tree(DraftScopeConditionReview.enrich(input)).path("instructions").path(0).path("fixedActionReferences").path(0).path("referenceCases").size());
    }

    @Test void observedBuild16RuleRequiresReviewOfTheMissingTwoEnvelopeAliasWithoutBeingRewritten() {
        Map<String,Object> input=packet();ObjectNode raw=response(rule("rule_s2_l10pro","t2","s2","keep",false));String before=raw.toString(),packetBefore=JsonUtils.write(input);
        List<Object> requests=DraftScopeConditionReview.review(DraftScopeConditionReview.enrich(input),raw);assertEquals(1,requests.size());Map<String,Object> request=map(requests.get(0));
        assertEquals("scope_reference_condition_inputs_unaccounted",request.get("reason"));assertEquals("t2",request.get("sourceTaskId"));assertEquals(Collections.singletonList("s2"),request.get("scopeIds"));assertEquals(Collections.singletonList("rule_s2_l10pro"),request.get("ruleIds"));
        assertEquals(Collections.singletonList("k9"),request.get("missingReferenceInputAliases"));assertEquals(Collections.singletonList("twoEnvelopeTendering"),request.get("missingReferenceInputKeys"));assertTrue(list(request.get("unresolvedReferenceInputKeys")).isEmpty());assertEquals(4,list(request.get("referenceCases")).size());
        assertEquals(before,raw.toString());assertEquals(packetBefore,JsonUtils.write(input));assertFalse(request.containsKey("when"));assertFalse(request.containsKey("action"));
    }

    @Test void completeDependencyReferencesDoNotRequireTheSeparateAlternativeProcedureInput() {
        ObjectNode raw=response(rule("rule_s2_l10pro","t2","s2","keep",true));assertTrue(DraftScopeConditionReview.review(packet(),raw).isEmpty());
        assertFalse(raw.toString().contains("k5"));assertFalse(raw.toString().contains("otherTenderingArrangement"));
    }

    @Test void explicitCheckAcceptsAChangedCurrentProcedureWithoutBorrowingTheHistoricalGate() {
        ObjectNode check=rule("review_current_change","t2","s2","check",false);check.remove("when");check.put("reason","Current source changed; request the adopted SCT5 procedure.");String before=check.toString();
        assertTrue(DraftScopeConditionReview.review(packet(),response(check)).isEmpty());assertEquals(before,check.toString());
    }

    @Test void paperBranchUsesItsOwnReferenceTableAndStillRequiresTwoEnvelopeReview() {
        Map<String,Object> input=packet();Map<String,Object> instruction=map(list(input.get("instructions")).get(0));map(list(instruction.get("fixedActionReferences")).get(0)).put("actionId","NTT-3C-PAPER");
        ObjectNode paper=rule("rule_s3_hardcopy","t2","s2","keep",false);((ObjectNode)paper.path("when").path("clauses").path(0)).put("value","Hardcopy");List<Object> requests=DraftScopeConditionReview.review(input,response(paper));
        assertEquals(1,requests.size());assertEquals(Collections.singletonList("k9"),map(requests.get(0)).get("missingReferenceInputAliases"));
        for(Object raw:list(map(requests.get(0)).get("referenceCases"))){Map<String,Object> row=map(raw),values=map(row.get("inputs"));assertEquals("NTT-3C-PAPER",row.get("actionId"));if(Boolean.TRUE.equals(values.get("twoEnvelopeTendering")))assertEquals("Hardcopy".equals(values.get("electronicTendering"))?"retain":"delete",row.get("action"));else assertEquals("pending",row.get("action"));}
    }

    @Test void unknownExcludedOrAmbiguousAliasMappingsRemainUnresolvedAndAreNeverGuessedFromNames() {
        for(String problem:Arrays.asList("missing","excluded","ambiguous","duplicate-alias","names-only")) {
            Map<String,Object> input=packet();List<Object> roots=mutableList(input.get("knownRoots"));Map<String,Object> two=map(roots.get(1));
            if("missing".equals(problem))roots.remove(1);if("excluded".equals(problem))two.put("status","excluded");if("ambiguous".equals(problem))roots.add(obj("id","k10","baselineKey","twoEnvelopeTendering"));if("duplicate-alias".equals(problem))roots.add(obj("id","k9","baselineKey","anotherFact"));if("names-only".equals(problem)){two.remove("baselineKey");two.put("questionEn","Two-envelope tendering system");}
            List<Object> requests=DraftScopeConditionReview.review(input,response(rule("a","t2","s2","keep",false)));assertEquals(1,requests.size(),problem);Map<String,Object> request=map(requests.get(0));assertEquals("scope_reference_input_mapping_unresolved",request.get("reason"),problem);assertEquals(Collections.singletonList("twoEnvelopeTendering"),request.get("unresolvedReferenceInputKeys"),problem);assertTrue(list(request.get("missingReferenceInputAliases")).isEmpty(),problem);
        }
    }

    @Test void explicitCurrentDefinitionMappingsAreSupportedButAmbiguousInstructionReferencesStillNeedReview() {
        Map<String,Object> input=packet();Map<String,Object> two=map(list(input.get("knownRoots")).get(1));two.remove("baselineKey");two.put("currentDefinitionReference",obj("baselineKey","twoEnvelopeTendering"));assertEquals(Collections.singletonList("k9"),map(DraftScopeConditionReview.review(input,response(rule("a","t2","s2","keep",false))).get(0)).get("missingReferenceInputAliases"));
        map(map(list(input.get("instructions")).get(0)).get("currentTemplateAlignment")).put("definitionStatus","ambiguous_instruction_reference");List<Object> requests=DraftScopeConditionReview.review(input,response(rule("a","t2","s2","keep",true)));assertEquals(1,requests.size());assertEquals("scope_reference_current_alignment_requires_review",map(requests.get(0)).get("reason"));
    }

    @Test void unrelatedForeignAndUnprovenScopesCannotAcquireThisReferenceReview() {
        for(String problem:Arrays.asList("foreign-owner","foreign-scope","missing-own-ref","wrong-source","other-action","marker")) {
            Map<String,Object> input=packet();Map<String,Object> instruction=map(list(input.get("instructions")).get(0));ObjectNode candidate=rule("a","t2","s2","keep",false);
            if("foreign-owner".equals(problem))candidate.put("sourceTaskId","t99");if("foreign-scope".equals(problem))candidate.put("scopeId","s99");if("missing-own-ref".equals(problem))instruction.put("evidenceRefs",Collections.emptyList());if("wrong-source".equals(problem))instruction.put("sourceId","foreign");if("other-action".equals(problem))map(list(instruction.get("fixedActionReferences")).get(0)).put("actionId","NTT-2-L10PRO");if("marker".equals(problem))instruction.remove("fixedActionReferences");
            assertTrue(DraftScopeConditionReview.review(input,response(candidate)).isEmpty(),problem);
        }
    }

    @Test void bothDisseminationReferencesExposeOnlyTheTwoRelevantEvaluatorCases() {
        for(String action:Arrays.asList("NTT-2-L10PRO","NTT-2-PAPER")) {
            JsonNode reference=tree(DraftScopeConditionReview.enrich(verifiedPacket(action))).path("instructions").path(0).path("fixedActionReferences").path(0);assertEquals(2,reference.path("referenceCases").size());
            for(JsonNode row:reference.path("referenceCases")){Map<String,Object> inputs=JsonUtils.readMap(row.path("inputs").toString());assertEquals(Collections.singleton("electronicTendering"),inputs.keySet());assertEquals(findAction(DraftBusinessRules.plan(inputs),action).path("action"),row.path("action"));}
        }
    }

    @Test void oneKeepDoesNotCompleteEitherDisseminationAlternativeAndBothExplicitBranchesDo() {
        for(String action:Arrays.asList("NTT-2-L10PRO","NTT-2-PAPER")) {
            boolean paper=action.endsWith("PAPER");Map<String,Object> input=verifiedPacket(action);ObjectNode keep=rule("keep","t2","s2","keep",false),remove=rule("delete","t2","s2","delete",false);
            value(keep,paper?"Hardcopy":"L10Pro");value(remove,paper?"L10Pro":"Hardcopy");String original=keep.toString();List<Object> requests=DraftScopeConditionReview.review(input,response(keep));assertEquals(1,requests.size());Map<String,Object> request=map(requests.get(0));
            assertEquals("scope_reference_cases_uncovered",request.get("reason"));assertEquals(Collections.singletonList("keep"),request.get("ruleIds"));assertEquals(1,list(request.get("missingReferenceCases")).size());Map<String,Object> missing=map(list(request.get("missingReferenceCases")).get(0));assertEquals("delete",missing.get("action"));assertEquals(paper?"L10Pro":"Hardcopy",map(missing.get("inputs")).get("electronicTendering"));assertEquals(original,keep.toString());
            assertTrue(DraftScopeConditionReview.review(input,response(keep,remove)).isEmpty());
        }
    }

    @Test void returnBranchesNeedBothFormatsButPendingOtherProceduresDoNotBecomeAutomaticDeletes() {
        for(String action:Arrays.asList("NTT-3C-L10PRO","NTT-3C-PAPER")) {
            boolean paper=action.endsWith("PAPER");Map<String,Object> input=verifiedPacket(action);ObjectNode keep=rule("keep","t2","s2","keep",true),remove=rule("delete","t2","s2","delete",true);value(keep,paper?"Hardcopy":"L10Pro");value(remove,paper?"L10Pro":"Hardcopy");
            List<Object> requests=DraftScopeConditionReview.review(input,response(keep));assertEquals(1,requests.size());List<?> missing=list(map(requests.get(0)).get("missingReferenceCases"));assertEquals(1,missing.size());assertEquals(true,map(map(missing.get(0)).get("inputs")).get("twoEnvelopeTendering"));assertEquals("delete",map(missing.get(0)).get("action"));
            assertTrue(DraftScopeConditionReview.review(input,response(keep,remove)).isEmpty());assertEquals(2,remove.path("when").path("clauses").size());
        }
    }

    @Test void finiteMatcherSupportsTypedComparisonsBooleanPredicatesAndAllOrAnyWithoutGuessing() {
        Map<String,Object> input=verifiedPacket("NTT-3C-L10PRO");ObjectNode keep=rule("keep","t2","s2","keep",true),remove=rule("delete","t2","s2","delete",true);
        ((ObjectNode)keep.path("when").path("clauses").path(1)).put("operator","yes").remove("value");((ObjectNode)remove.path("when").path("clauses").path(1)).put("operator","yes").remove("value");((ObjectNode)remove.path("when").path("clauses").path(0)).put("operator","ne");
        assertTrue(DraftScopeConditionReview.review(input,response(keep,remove)).isEmpty());
        Map<String,Object> issue=verifiedPacket("NTT-2-L10PRO");ObjectNode any=rule("any_keep","t2","s2","keep",false),other=rule("delete","t2","s2","delete",false);any.with("when").put("mode","any");any.with("when").withArray("clauses").add(any.path("when").path("clauses").path(0).deepCopy());((ObjectNode)other.path("when").path("clauses").path(0)).put("operator","ne");assertTrue(DraftScopeConditionReview.review(issue,response(any,other)).isEmpty());
    }

    @Test void unknownExtraFactsUnsupportedOperatorsAndConflictingActionsCannotCompleteAReferenceCase() {
        for(String problem:Arrays.asList("extra-fact","operator","missing-when","conflict")) {
            Map<String,Object> input=verifiedPacket("NTT-2-L10PRO");ObjectNode keep=rule("keep","t2","s2","keep",false),remove=rule("delete","t2","s2","delete",false);value(remove,"Hardcopy");
            if("extra-fact".equals(problem))remove.with("when").withArray("clauses").addObject().put("inputId","k5").put("operator","known");if("operator".equals(problem))((ObjectNode)remove.path("when").path("clauses").path(0)).put("operator","has");if("missing-when".equals(problem))remove.remove("when");if("conflict".equals(problem))value(remove,"L10Pro");
            List<Object> requests=DraftScopeConditionReview.review(input,response(keep,remove));assertEquals(1,requests.size(),problem);List<?> missing=list(map(requests.get(0)).get("missingReferenceCases"));assertFalse(missing.isEmpty(),problem);assertTrue(missing.stream().map(DraftReviewGraph::map).anyMatch(row->("conflict".equals(problem)?"conflicting_actions":"unknown").equals(row.get("coverageStatus"))),problem);
        }
    }

    @Test void repeatedGuidanceSharesTheSameSourceScopeCoverageAndDoesNotDuplicateItsMissingCases() {
        Map<String,Object> input=verifiedPacket("NTT-2-L10PRO");Map<String,Object> duplicate=JsonUtils.readMap(JsonUtils.write(list(input.get("instructions")).get(0)));duplicate.put("sourceTaskId","t3");mutableList(input.get("instructions")).add(duplicate);
        ObjectNode keep=rule("keep","t2","s2","keep",false),remove=rule("delete","t3","s2","delete",false);value(remove,"Hardcopy");assertTrue(DraftScopeConditionReview.review(input,response(keep,remove)).isEmpty());
        List<Object> once=DraftScopeConditionReview.review(input,response(keep));assertEquals(1,once.size());assertEquals(1,list(map(once.get(0)).get("missingReferenceCases")).size());assertEquals(Collections.singletonList("t2"),map(once.get(0)).get("sourceTaskIds"));
        value(remove,"L10Pro");remove.put("action","keep");List<Object> both=DraftScopeConditionReview.review(input,response(keep,remove));assertEquals(1,both.size());Map<String,Object> request=map(both.get(0));assertEquals(new LinkedHashSet<>(Arrays.asList("t2","t3")),new LinkedHashSet<>(list(request.get("sourceTaskIds"))));assertEquals(Arrays.asList("keep","delete"),request.get("ruleIds"));assertEquals(1,list(request.get("missingReferenceCases")).size());
    }

    @Test void foreignOwnersScopesAndDifferentSourcesCannotCompleteTheCurrentScope() {
        for(String problem:Arrays.asList("owner","scope","source")) {
            Map<String,Object> input=verifiedPacket("NTT-2-L10PRO");ObjectNode keep=rule("keep","t2","s2","keep",false),remove=rule("delete","t3","s2","delete",false);value(remove,"Hardcopy");
            if("scope".equals(problem))remove.put("scopeId","s99");if("source".equals(problem)){Map<String,Object> foreign=JsonUtils.readMap(JsonUtils.write(list(input.get("instructions")).get(0)));foreign.put("sourceTaskId","t3");foreign.put("sourceId","other-source");mutableList(input.get("instructions")).add(foreign);}
            List<Object> requests=DraftScopeConditionReview.review(input,response(keep,remove));Map<String,Object> request=requests.stream().map(DraftReviewGraph::map).filter(row->row.containsKey("missingReferenceCases")).findFirst().orElseThrow(AssertionError::new);assertEquals(Collections.singletonList("keep"),request.get("ruleIds"),problem);assertEquals(Collections.singletonList("t2"),request.get("sourceTaskIds"),problem);assertEquals(1,list(request.get("missingReferenceCases")).size(),problem);
        }
    }

    @Test void allChecksStayUnresolvedAndAnUnprovenOrChangedBodyNeverBorrowsReferenceCoverage() {
        Map<String,Object> input=verifiedPacket("NTT-2-L10PRO");ObjectNode check=rule("review","t2","s2","check",false);check.remove("when");String original=check.toString();List<Object> requests=DraftScopeConditionReview.review(input,response(check));assertEquals(1,requests.size());assertEquals(2,list(map(requests.get(0)).get("missingReferenceCases")).size());assertEquals(original,check.toString());assertFalse(map(requests.get(0)).containsKey("when"));assertFalse(map(requests.get(0)).containsKey("action"));
        for(String problem:Arrays.asList("body","alignment","kind","source")){Map<String,Object> changed=verifiedPacket("NTT-2-L10PRO"),alignment=map(map(list(changed.get("instructions")).get(0)).get("currentTemplateAlignment"));if("body".equals(problem))map(list(changed.get("sharedEvidence")).get(0)).put("scopeComplete",false);if("alignment".equals(problem))alignment.put("scopeStatus","requires_review");if("kind".equals(problem))alignment.put("referenceKind","historical_reference");if("source".equals(problem))alignment.put("sourceId","other-source");List<Object> pending=DraftScopeConditionReview.review(changed,response(rule("keep","t2","s2","keep",false)));assertTrue(pending.stream().map(DraftReviewGraph::map).noneMatch(row->row.containsKey("missingReferenceCases")),problem);}
    }

    @Test void explicitStableRootIdsWorkWithoutTemporaryKLabels() {
        Map<String,Object> input=verifiedPacket("NTT-2-L10PRO");map(list(input.get("knownRoots")).get(0)).put("id","current-format-root");ObjectNode keep=rule("keep","t2","s2","keep",false),remove=rule("delete","t2","s2","delete",false);value(remove,"Hardcopy");for(ObjectNode candidate:Arrays.asList(keep,remove))((ObjectNode)candidate.path("when").path("clauses").path(0)).put("inputId","current-format-root");assertTrue(DraftScopeConditionReview.review(input,response(keep,remove)).isEmpty());
    }

    @Test void anOrRuleCannotApplyAStandardTreatmentToTheEvaluatorsPendingProcedureCases() {
        Map<String,Object> input=verifiedPacket("NTT-3C-L10PRO");ObjectNode keep=rule("keep","t2","s2","keep",true),remove=rule("delete","t2","s2","delete",true);value(remove,"Hardcopy");keep.with("when").put("mode","any");((ObjectNode)keep.path("when").path("clauses").path(1)).put("operator","no").remove("value");
        List<Object> requests=DraftScopeConditionReview.review(input,response(keep,remove));assertEquals(1,requests.size());List<?> missing=list(map(requests.get(0)).get("missingReferenceCases"));assertEquals(2,missing.size());for(Object raw:missing){Map<String,Object> row=map(raw);assertEquals("pending",row.get("action"));assertEquals("pending_case_treated",row.get("coverageStatus"));assertEquals("check",row.get("expectedAction"));assertEquals(false,map(row.get("inputs")).get("twoEnvelopeTendering"));}
    }

    @Test void build19TwelveRulesKeepNormalCasesCoveredAndIdentifyOnlyThePendingProcedureTreatments() {
        // Observed raw rules from build19 group-648b27574513be5718c6665e; packet identities are authored locally.
        Map<String,Object> input=build19Packet();ObjectNode raw=response(
            assignedRule("rule_t1_keep_l10pro","t1","s1","keep","electronicTendering","L10Pro"),assignedRule("rule_t1_delete_hardcopy","t1","s1","delete","electronicTendering","Hardcopy"),
            assignedRule("rule_t2_keep_l10pro","t2","s1","keep","electronicTendering","L10Pro"),assignedRule("rule_t2_delete_hardcopy","t2","s1","delete","electronicTendering","Hardcopy"),
            assignedRule("rule_t3_keep_hardcopy","t3","s2","keep","electronicTendering","Hardcopy"),assignedRule("rule_t3_delete_l10pro","t3","s2","delete","electronicTendering","L10Pro"),
            assignedRule("rule_t4_keep_two_env_l10pro","t4","s3","keep","twoEnvelopeTendering",true,"electronicTendering","L10Pro"),assignedRule("rule_t4_delete_not_two_env","t4","s3","delete","twoEnvelopeTendering",false),assignedRule("rule_t4_delete_two_env_hardcopy","t4","s3","delete","twoEnvelopeTendering",true,"electronicTendering","Hardcopy"),
            assignedRule("rule_t5_keep_two_env_hardcopy","t5","s4","keep","twoEnvelopeTendering",true,"electronicTendering","Hardcopy"),assignedRule("rule_t5_delete_not_two_env","t5","s4","delete","twoEnvelopeTendering",false),assignedRule("rule_t5_delete_two_env_l10pro","t5","s4","delete","twoEnvelopeTendering",true,"electronicTendering","L10Pro"));
        String before=raw.toString();assertEquals(12,raw.path("scopeRules").size());List<Object> requests=DraftScopeConditionReview.review(input,raw);assertEquals(2,requests.size());
        Set<String> scopes=new HashSet<>();for(Object value:requests){Map<String,Object> request=map(value);assertEquals("scope_reference_cases_uncovered",request.get("reason"));assertTrue(list(request.get("missingReferenceInputKeys")).isEmpty(),"A false-only rule needs no irrelevant format dependency");String scope=text(list(request.get("scopeIds")).get(0));scopes.add(scope);assertEquals(3,list(request.get("ruleIds")).size());List<?> cases=list(request.get("missingReferenceCases"));assertEquals(2,cases.size());for(Object item:cases){Map<String,Object> row=map(item);assertEquals("pending_case_treated",row.get("coverageStatus"));assertEquals("pending",row.get("action"));assertEquals("check",row.get("expectedAction"));assertEquals(false,map(row.get("inputs")).get("twoEnvelopeTendering"));}}
        assertEquals(new HashSet<>(Arrays.asList("s3","s4")),scopes);assertEquals(before,raw.toString());
    }

    @Test void falseAndUnknownIsFalseRegardlessOfOrderWithoutInventingTheUnknownFact() {
        for(boolean unknownFirst:Arrays.asList(false,true)) {
            Map<String,Object> input=verifiedPacket("NTT-3C-L10PRO");ObjectNode keep=rule("keep","t2","s2","keep",true),remove=rule("delete","t2","s2","delete",true);value(remove,"Hardcopy");ObjectNode pending=assignedRule("other_procedure","t2","s2","delete","k9",false);ObjectNode unknown=JsonUtils.mapper().createObjectNode().put("inputId","k5").put("operator","eq").put("value","adopted procedure");
            if(unknownFirst){JsonNode known=pending.path("when").path("clauses").path(0).deepCopy();pending.with("when").putArray("clauses").add(unknown).add(known);}else pending.with("when").withArray("clauses").add(unknown);
            String original=pending.toString();assertTrue(DraftScopeConditionReview.review(input,response(keep,remove,pending)).isEmpty());assertEquals(original,pending.toString());
        }
    }

    @Test void trueOrUnknownCoversTheTrueCaseButLeavesTheOtherCaseUnknown() {
        for(boolean unknownFirst:Arrays.asList(false,true)) {
            Map<String,Object> input=verifiedPacket("NTT-2-L10PRO");ObjectNode keep=rule("keep","t2","s2","keep",false),remove=rule("delete","t2","s2","delete",false);value(remove,"Hardcopy");keep.with("when").put("mode","any");ObjectNode unknown=JsonUtils.mapper().createObjectNode().put("inputId","k5").put("operator","eq").put("value","adopted procedure");if(unknownFirst){JsonNode known=keep.path("when").path("clauses").path(0).deepCopy();keep.with("when").putArray("clauses").add(unknown).add(known);}else keep.with("when").withArray("clauses").add(unknown);
            List<Object> requests=DraftScopeConditionReview.review(input,response(keep,remove));assertEquals(1,requests.size());List<?> cases=list(map(requests.get(0)).get("missingReferenceCases"));assertEquals(1,cases.size());assertEquals("Hardcopy",map(map(cases.get(0)).get("inputs")).get("electronicTendering"));assertEquals("unknown",map(cases.get(0)).get("coverageStatus"));
        }
    }

    @Test void aFormatOnlyReturnRuleIsCaughtInThePendingRowsWithoutRequiringEveryDimensionInTheMatcher() {
        Map<String,Object> input=verifiedPacket("NTT-3C-L10PRO");ObjectNode keep=rule("keep","t2","s2","keep",false),remove=rule("delete","t2","s2","delete",false);value(remove,"Hardcopy");List<Object> requests=DraftScopeConditionReview.review(input,response(keep,remove));assertTrue(requests.stream().map(DraftReviewGraph::map).flatMap(row->list(row.get("missingReferenceCases")).stream()).map(DraftReviewGraph::map).anyMatch(row->"pending_case_treated".equals(row.get("coverageStatus"))));assertTrue(requests.stream().map(DraftReviewGraph::map).flatMap(row->list(row.get("missingReferenceCases")).stream()).map(DraftReviewGraph::map).noneMatch(row->"unknown".equals(row.get("coverageStatus"))));
    }

    @Test void wtoFalseRequiresUnusedRatherThanDeletingTheNumberedProvision() {
        Map<String,Object> input=verifiedPacket("NTT-9-WTO");input.put("knownRoots",new ArrayList<>(Collections.singletonList(obj("id","wtoGpaApplies"))));map(list(map(list(input.get("instructions")).get(0)).get("fixedActionReferences")).get(0)).put("inputKeys",Collections.singletonList("wtoGpaApplies"));map(list(input.get("sharedEvidence")).get(0)).put("text",DraftBusinessRules.standardParagraph("NTT",438));ObjectNode keep=assignedRule("wto_keep","t2","s2","keep","wtoGpaApplies",true),remove=assignedRule("wto_false","t2","s2","delete","wtoGpaApplies",false);String sourceBefore=JsonUtils.write(input);
        JsonNode reference=tree(DraftScopeConditionReview.enrich(input)).path("instructions").path(0).path("fixedActionReferences").path(0);assertEquals(2,reference.path("referenceCases").size());for(JsonNode row:reference.path("referenceCases"))assertEquals(findAction(DraftBusinessRules.plan(JsonUtils.readMap(row.path("inputs").toString())),"NTT-9-WTO").path("action"),row.path("action"));
        List<Object> requests=DraftScopeConditionReview.review(input,response(keep,remove));assertEquals(1,requests.size());List<?> missing=list(map(requests.get(0)).get("missingReferenceCases"));assertEquals(1,missing.size());Map<String,Object> gap=map(missing.get(0));assertEquals("conflicting_actions",gap.get("coverageStatus"));assertEquals(false,map(gap.get("inputs")).get("wtoGpaApplies"));assertEquals("not_used",gap.get("action"));assertEquals("unused",gap.get("expectedAction"));
        remove.put("action","unused");assertTrue(DraftScopeConditionReview.review(input,response(keep,remove)).isEmpty());assertEquals(sourceBefore,JsonUtils.write(input),"The reference check neither deletes source text nor changes numbering");
    }

    private static Map<String,Object> packet() {
        Map<String,Object> instruction=obj("sourceTaskId","t2","sourceId","16","kind","guidance","scopeObligationIds",new ArrayList<>(Collections.singletonList("s2")),"evidenceRefs",new ArrayList<>(Collections.singletonList("16|scope|s2")),
                "currentTemplateAlignment",obj("definitionStatus","exact_instruction_reference"),"fixedActionReferences",new ArrayList<>(Collections.singletonList(obj("actionId","NTT-3C-L10PRO","inputKeys",Arrays.asList("twoEnvelopeTendering","electronicTendering","otherTenderingArrangement")))));
        return obj("instructions",new ArrayList<>(Collections.singletonList(instruction)),"sharedEvidence",new ArrayList<>(Collections.singletonList(obj("ref","16|scope|s2","kind","scope","sourceId","16","id","s2","text","Submit in Envelope 2 as detailed in SCT5(4)."))),
                "knownRoots",new ArrayList<>(Arrays.asList(obj("id","k3","baselineKey","electronicTendering"),obj("id","k9","baselineKey","twoEnvelopeTendering","conditionStatus","unknown"),obj("id","k5","baselineKey","otherTenderingArrangement"))));
    }
    private static ObjectNode rule(String id,String owner,String scope,String action,boolean two) {
        ObjectNode rule=JsonUtils.mapper().createObjectNode().put("id",id).put("sourceTaskId",owner).put("scopeId",scope).put("action",action);rule.putObject("when").put("mode","all").putArray("clauses").addObject().put("inputId","k3").put("operator","eq").put("value","L10Pro");
        if(two)rule.with("when").withArray("clauses").addObject().put("inputId","k9").put("operator","eq").put("value",true);return rule;
    }
    private static Map<String,Object> verifiedPacket(String action){Map<String,Object> input=packet(),instruction=map(list(input.get("instructions")).get(0)),alignment=map(instruction.get("currentTemplateAlignment"));map(list(instruction.get("fixedActionReferences")).get(0)).put("actionId",action);alignment.put("scopeStatus","current_observation_complete");alignment.put("referenceKind","current_instruction_reference");alignment.put("sourceId","16");alignment.put("currentScopeIds",Collections.singletonList("s2"));map(list(input.get("sharedEvidence")).get(0)).put("scopeComplete",true);return input;}
    private static void value(ObjectNode rule,String value){((ObjectNode)rule.path("when").path("clauses").path(0)).put("value",value);}
    private static ObjectNode assignedRule(String id,String owner,String scope,String action,Object... pairs){ObjectNode rule=JsonUtils.mapper().createObjectNode().put("id",id).put("sourceTaskId",owner).put("scopeId",scope).put("action",action);rule.putObject("when").put("mode","all").putArray("clauses");for(int index=0;index<pairs.length;index+=2)rule.with("when").withArray("clauses").addObject().put("inputId",text(pairs[index])).put("operator","eq").set("value",tree(pairs[index+1]));return rule;}
    private static Map<String,Object> build19Packet(){Map<String,Object> input=packet();List<Object> instructions=new ArrayList<>(),scopes=new ArrayList<>();String[] actions={"NTT-2-L10PRO","NTT-2-L10PRO","NTT-2-PAPER","NTT-3C-L10PRO","NTT-3C-PAPER"},ids={"s1","s1","s2","s3","s4"};Set<String> seen=new HashSet<>();for(int index=0;index<actions.length;index++){Map<String,Object> instruction=map(list(verifiedPacket(actions[index]).get("instructions")).get(0)),alignment=map(instruction.get("currentTemplateAlignment"));instruction.put("sourceTaskId","t"+(index+1));instruction.put("sourceId","19");instruction.put("scopeObligationIds",Collections.singletonList(ids[index]));instruction.put("evidenceRefs",Collections.singletonList("19|scope|"+ids[index]));alignment.put("sourceId","19");alignment.put("currentScopeIds",Collections.singletonList(ids[index]));instructions.add(instruction);if(seen.add(ids[index]))scopes.add(obj("ref","19|scope|"+ids[index],"kind","scope","sourceId","19","id",ids[index],"scopeComplete",true));}input.put("instructions",instructions);input.put("sharedEvidence",scopes);input.put("knownRoots",Arrays.asList(obj("id","electronicTendering"),obj("id","twoEnvelopeTendering")));return input;}
    private static ObjectNode response(ObjectNode... rules){ObjectNode response=JsonUtils.mapper().createObjectNode();response.putArray("scopeRules");for(ObjectNode rule:rules)response.withArray("scopeRules").add(rule);return response;}
    private static JsonNode tree(Object value){return JsonUtils.mapper().valueToTree(value);}
    @SuppressWarnings("unchecked") private static List<Object> mutableList(Object value){return (List<Object>)value;}
    private static JsonNode findAction(Map<String,Object> plan,String id){for(Object raw:list(plan.get("actions")))if(id.equals(map(raw).get("id")))return tree(raw);throw new AssertionError(id);}
}
