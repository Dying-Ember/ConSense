package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.consense.service.drafting.DraftReviewGraph.*;
import static org.junit.jupiter.api.Assertions.*;

/** Post-atomic matching uses the existing linking call; deterministic returned JSON, no real model calls. */
class DraftBaselineMatcherTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a"));
    private static final String QUOTE="The package includes foundations; verify the independent facade and duration requirements.";

    @Test void sameFactCannotMergeMonthNumbersBillNumbersAndPricingChoicesByTopic() {
        Map<String,Object> months=obj("id","months","type",obj("kind","number","unit","months"),"role",obj("kind","input"),"nameEn","Pricing information"),
            bill=obj("id","bill","type",obj("kind","text"),"role",obj("kind","input"),"nameEn","Pricing information"),
            pricing=obj("id","pricing","type",obj("kind","choice"),"role",obj("kind","input"),"nameEn","Pricing information","options",Arrays.asList(obj("value","BQ"),obj("value","SOR")));
        assertTrue(DraftBaselineMatcher.sameFactIssues(months,pricing).contains("same_fact_type_mismatch"));
        assertTrue(DraftBaselineMatcher.sameFactIssues(bill,pricing).contains("same_fact_type_mismatch"));
        Map<String,Map<String,Object>> byId=new LinkedHashMap<>();byId.put("months",months);byId.put("pricing",pricing);
        List<Object> focused=DraftBaselineMatcher.focusDefinitions(Arrays.asList("months","pricing"),byId);
        assertEquals(months.get("type"),map(focused.get(0)).get("type"));assertEquals(pricing.get("role"),map(focused.get(1)).get("role"));assertEquals(pricing.get("options"),map(focused.get(1)).get("options"));
    }

    @Test void sameFactKeepsDifferentValueDomainsRolesAndConfirmedDefinitionsUnresolved() {
        Map<String,Object> left=obj("id","left","type",obj("kind","choice"),"role",obj("kind","input"),"options",Arrays.asList(obj("value","BQ"),obj("value","SOR")));
        Map<String,Object> right=JsonUtils.readMap(JsonUtils.write(left));right.put("id","right");right.put("options",Arrays.asList(obj("value","L10Pro"),obj("value","Hardcopy")));
        assertTrue(DraftBaselineMatcher.sameFactIssues(left,right).contains("same_fact_option_domain_mismatch"));right.put("options",left.get("options"));
        right.put("parentId","another-list");assertTrue(DraftBaselineMatcher.sameFactIssues(left,right).contains("same_fact_parent_review_required"));right.remove("parentId");
        right.put("definitionScope","different-work-section");assertTrue(DraftBaselineMatcher.sameFactIssues(left,right).contains("same_fact_scope_review_required"));right.remove("definitionScope");
        right.put("role",obj("kind","decision"));assertTrue(DraftBaselineMatcher.sameFactIssues(left,right).contains("same_fact_role_mismatch"));right.put("role",obj("kind","derived"));assertTrue(DraftBaselineMatcher.sameFactIssues(left,right).contains("same_fact_business_value_required"));right.put("role",left.get("role"));
        left.put("catalogAlignment",obj("status","matched","confirmed",true,"baselineKey","one","baselineVersion","v1"));right.put("catalogAlignment",obj("status","matched","confirmed",true,"baselineKey","two","baselineVersion","v1"));
        assertTrue(DraftBaselineMatcher.sameFactIssues(left,right).contains("same_fact_confirmed_definition_conflict"));
    }

    @Test void knownNonBusinessRecordsCannotEnterMatchingButPendingClassificationsRemainReviewable() {
        Map<String,Object> fact=obj("id","fact","type",obj("kind","boolean"),"role",obj("kind","input"),"semanticSourceQuote",QUOTE,"editorialSuggestion",obj("kind","editorial","confirmed",false));
        Map<String,Object> seed=JsonUtils.readMap(JsonUtils.write(fact));seed.put("id","seed");seed.put("definitionKind","source_seed");
        Map<String,Object> editorial=JsonUtils.readMap(JsonUtils.write(fact));editorial.put("id","editorial");editorial.put("editorialReview",obj("kind","editorial","confirmed",true));
        Map<String,Object> target=obj("id","target","action","delete","text",QUOTE);Map<String,Map<String,Object>> byId=new LinkedHashMap<>();for(Map<String,Object> node:Arrays.asList(fact,seed,editorial,target))byId.put(text(node.get("id")),node);
        List<Object> focus=DraftBaselineMatcher.focusDefinitions(new ArrayList<>(byId.keySet()),byId);assertEquals(1,focus.size());assertEquals("fact",map(focus.get(0)).get("id"));
        Map<String,Object> suggestions=DraftBaselineMatcher.read(JsonUtils.parse("{\"catalogMatches\":[]}"),new ArrayList<>(byId.keySet()),byId);assertEquals(Collections.singleton("fact"),map(suggestions.get("alignments")).keySet());
        editorial.put("editorialReview",obj("kind","business_value","confirmed",true));assertTrue(DraftCatalogAlignment.businessCandidate(editorial));
    }

    @Test void boundedOnDemandContextUsesVersionedFixedSourceWithoutCopyingEditingAuthority() {
        Map<String,Object> index=DraftBaselineMatcher.catalogIndex();
        assertTrue(list(index.get("fields")).stream().anyMatch(row->"iasmIncluded".equals(map(row).get("key"))));
        assertFalse(JsonUtils.write(index).contains("Impact Absorbing Surfacing Materials"));
        Map<String,Object> context=DraftBaselineMatcher.requestedContext(Arrays.asList("iasmIncluded","electronicTendering","inventedKey"));
        String raw=JsonUtils.write(context);
        assertTrue(raw.contains("Impact Absorbing Surfacing Materials"));
        assertTrue(raw.contains("SCC"));assertTrue(raw.contains("698"));
        assertTrue(raw.contains(DraftBusinessRules.RULE_VERSION));assertFalse(raw.contains("inventedKey"));
        assertFalse(raw.contains("sourcePosition"));assertFalse(raw.contains("actionCatalog"));
        assertTrue(raw.length()<15000);
    }
    @Test void modelCanRequestBoundedContextAndFlagSemanticConflictWithoutChangingSource() {
        DraftAutomaticDiscovery.Plan plan=plan();Map<String,Object> atomic=atomic(plan,new int[]{0},node("iasm","boolean","Does IASM mean the interactive audio visual system?"));
        String id=text(map(list(atomic.get("nodes")).get(0)).get("id"));int[] calls={0};
        Map<String,Object> receipt=DraftAutomaticDiscovery.processRelation((system,user)->{
            calls[0]++;assertTrue(system.length()+user.length()<=160000);if(calls[0]==1)return JsonUtils.write(obj("relations",Collections.emptyList(),"catalogMatches",Collections.singletonList(match(id,candidate("iasmIncluded",QUOTE))),"catalogContextRequests",Collections.singletonList("iasmIncluded"),"nodeClassifications",Collections.singletonList(obj("nodeId",id,"kind","business_value","sourceQuote",QUOTE,"reason","A project fact"))));
            assertTrue(user.contains("Impact Absorbing Surfacing Materials"));assertTrue(user.contains("698"));assertFalse(user.contains("sourcePosition"));
            return JsonUtils.write(obj("catalogAssessments",Collections.singletonList(obj("nodeId",id,"baselineKey","iasmIncluded","semanticStatus","conflict","reason","Audio visual system differs from surfacing materials"))));
        },plan,Collections.singletonList(atomic),DraftAutomaticDiscovery.planRelations(plan,Collections.singletonList(atomic)).get(0));
        Map<String,Object> node=nodes(DraftAutomaticDiscovery.finish(graph(),plan,Arrays.asList(atomic,receipt))).get(0),alignment=map(node.get("catalogAlignment")),candidate=map(list(alignment.get("proposedMatches")).get(0));
        assertEquals(2,calls[0]);assertEquals(2,receipt.get("modelCalls"));assertEquals("conflict",candidate.get("semanticStatus"));assertTrue(list(candidate.get("issues")).contains("semantic_conflict_review_required"));assertEquals("ambiguous",alignment.get("proposalStatus"));assertEquals(false,alignment.get("confirmed"));
        assertEquals("business_value",map(node.get("editorialSuggestion")).get("kind"));assertEquals(false,map(node.get("editorialSuggestion")).get("confirmed"));Map<String,Object> anchor=map(list(node.get("sourceAnchors")).get(0));assertEquals("1",anchor.get("docId"));assertEquals(SHA,anchor.get("sourceSha256"));assertEquals(QUOTE,anchor.get("text"));
    }

    @Test void classificationDoesNotTrustForeignQuotesOrDuplicateResponses() {
        Map<String,Object> node=node("a","boolean","Fact");node.put("id","a");node.put("semanticSourceQuote",QUOTE);node.put("role",obj("kind","input"));Map<String,Map<String,Object>> nodes=Collections.singletonMap("a",node);
        JsonNode response=JsonUtils.mapper().valueToTree(obj("nodeClassifications",Arrays.asList(obj("nodeId","a","kind","editorial","sourceQuote",QUOTE,"reason","Instruction"),obj("nodeId","a","kind","business_value","sourceQuote",QUOTE,"reason","Conflicting"),obj("nodeId","foreign","kind","editorial","sourceQuote",QUOTE,"reason","Foreign"))));
        assertTrue(DraftBaselineMatcher.classifications(response,Collections.singletonList("a"),nodes).isEmpty());
    }
    @Test void choiceBranchProposalKeepsSeparateEvidenceAndNeverCopiesOldTemplateCoordinates() {
        Map<String,Object> word=obj("id","word","semanticSourceQuote",QUOTE,"type",obj("kind","boolean"),"role",obj("kind","input"),"sourceAnchors",Collections.singletonList(obj("docId","scc-word","sourceSha256",SHA)));
        Map<String,Object> pdf=obj("id","pdf","semanticSourceQuote",QUOTE,"type",obj("kind","boolean"),"role",obj("kind","input"),"sourceAnchors",Collections.singletonList(obj("docId","scc-pdf","sourceSha256","b"+SHA.substring(1))));
        Map<String,Object> matching=obj("alignments",obj("word",obj("proposedMatches",new ArrayList<>(Collections.singletonList(obj("baselineKey","electronicTendering")))),"pdf",obj("proposedMatches",new ArrayList<>(Collections.singletonList(obj("baselineKey","electronicTendering"))))));
        JsonNode assessment=JsonUtils.mapper().valueToTree(obj("catalogAssessments",Arrays.asList(obj("nodeId","word","baselineKey","electronicTendering","semanticStatus","uncertain","reason","Review choice branch","branchValue","L10Pro","branchPolarity",true),obj("nodeId","pdf","baselineKey","electronicTendering","semanticStatus","uncertain","reason","No matching PDF evidence","branchValue","invented","branchPolarity",true))));
        DraftBaselineMatcher.assess(matching,assessment,Collections.singletonList("electronicTendering"));
        Map<String,Object> a=map(list(map(map(matching.get("alignments")).get("word")).get("proposedMatches")).get(0)),b=map(list(map(map(matching.get("alignments")).get("pdf")).get("proposedMatches")).get(0));
        assertEquals("L10Pro",map(a.get("factProjection")).get("branchValue"));assertEquals(false,map(a.get("factProjection")).get("confirmed"));assertFalse(b.containsKey("factProjection"));
        assertNotEquals(map(list(word.get("sourceAnchors")).get(0)).get("sourceSha256"),map(list(pdf.get("sourceAnchors")).get(0)).get("sourceSha256"));assertFalse(JsonUtils.write(matching).contains("sourcePosition"));
    }
    @Test void aLocalWindowCanAskForAnotherCandidateWithoutPretendingTheWindowIsComplete() {
        Map<String,Object> atom=node("x","boolean","A special project question");atom.put("role",obj("kind","input"));atom.put("semanticSourceQuote",QUOTE);Map<String,Map<String,Object>> atoms=Collections.singletonMap("x",atom);
        Map<String,Object> window=DraftBaselineMatcher.catalogWindow(Collections.singletonList("x"),atoms);assertEquals(false,window.get("complete"));assertTrue(list(window.get("fields")).size()<=12);
        Map<String,Object> results=DraftBaselineMatcher.searchIndex(Collections.singletonList("IASM"));assertTrue(list(results.get("fields")).stream().anyMatch(row->"iasmIncluded".equals(map(row).get("key"))));
        Map<String,Object> matching=obj("alignments",obj("x",obj("proposalStatus","unmatched","proposedMatches",new ArrayList<>())));
        JsonNode reply=JsonUtils.mapper().valueToTree(obj("catalogMatches",Collections.singletonList(match("x",candidate("iasmIncluded",QUOTE)))));
        DraftBaselineMatcher.addRequestedMatches(matching,reply,Collections.singletonList("x"),atoms,Collections.singletonList("iasmIncluded"));assertEquals("iasmIncluded",map(list(map(map(matching.get("alignments")).get("x")).get("proposedMatches")).get(0)).get("baselineKey"));
        Map<String,Object> rejected=obj("alignments",obj("x",obj("proposalStatus","unmatched","proposedMatches",new ArrayList<>())));DraftBaselineMatcher.addRequestedMatches(rejected,reply,Collections.singletonList("x"),atoms,Collections.singletonList("other"));assertTrue(list(map(map(rejected.get("alignments")).get("x")).get("proposedMatches")).isEmpty());
        Map<String,Object> first=DraftBaselineMatcher.read(reply,Collections.singletonList("x"),atoms,Collections.singletonList("combinedContractWithFoundationWorks"));assertEquals("unavailable",map(map(first.get("alignments")).get("x")).get("proposalStatus"));assertTrue(JsonUtils.write(first.get("issues")).contains("catalog_match_candidate_invalid"));
    }
    @Test void catalogReferenceRequiresBoundCurrentSourcesButNotTheHistoricalShaOrFormat() {
        List<com.fasterxml.jackson.databind.JsonNode> sources=Arrays.asList(
            JsonUtils.mapper().valueToTree(obj("id","scc-word","fileKey","SCC","format","docx","evidenceStatus","bound","sourceSha256","9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208")),
            JsonUtils.mapper().valueToTree(obj("id","scc-other-word","fileKey","SCC","format","docx","evidenceStatus","bound","sourceSha256",SHA)),
            JsonUtils.mapper().valueToTree(obj("id","scc-unbound","fileKey","SCC","format","docx","evidenceStatus","missing","sourceSha256","9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208")),
            JsonUtils.mapper().valueToTree(obj("id","scc-pdf","fileKey","SCC","format","pdf","evidenceStatus","bound","sourceSha256",DraftPdfSccReference.SOURCE_SHA)),
            JsonUtils.mapper().valueToTree(obj("id","ntt","fileKey","NTT","format","docx","evidenceStatus","bound")));
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(sources);Map<String,Map<String,Object>> nodes=new LinkedHashMap<>();
        nodes.put("word",obj("docIds",Collections.singletonList("scc-word")));nodes.put("otherWord",obj("docIds",Collections.singletonList("scc-other-word")));nodes.put("unbound",obj("docIds",Collections.singletonList("scc-unbound")));nodes.put("pdf",obj("docIds",Collections.singletonList("scc-pdf")));nodes.put("both",obj("docIds",Arrays.asList("ntt","scc-other-word")));nodes.put("ntt",obj("docIds",Collections.singletonList("ntt")));nodes.put("unknown",obj("docIds",Collections.singletonList("missing")));
        assertEquals(Arrays.asList("word","otherWord","pdf","both","ntt"),DraftAutomaticDiscovery.fixedCatalogFocus(plan,Arrays.asList("word","otherWord","unbound","pdf","both","ntt","unknown"),nodes));
    }
    @Test void changedWordSccReceivesOnlyPendingSemanticReferenceWithoutOldRangeAuthority() {
        DraftAutomaticDiscovery.Plan plan=plan("SCC",SHA);Map<String,Object> atomic=atomic(plan,new int[]{0},node("fact","boolean","SCC fact"));String id=text(map(list(atomic.get("nodes")).get(0)).get("id"));
        Map<String,Object> linked=DraftAutomaticDiscovery.processRelation((system,user)->{JsonNode payload=JsonUtils.parse(user);assertEquals("fixed_definition_reference",payload.path("fixedCatalog").path("referenceKind").asText());assertFalse(payload.path("fixedCatalog").path("editableAuthority").asBoolean());assertTrue(system.contains("catalogMatches"));assertTrue(system.contains("graft old conditions or editing coordinates"));return response(Collections.singletonList(match(id)),Collections.emptyList());},plan,Collections.singletonList(atomic),DraftAutomaticDiscovery.planRelations(plan,Collections.singletonList(atomic)).get(0));
        assertTrue(list(linked.get("catalogMatchingDeferredSourceIdentity")).isEmpty());assertEquals(true,linked.get("catalogMatchingRequested"));Map<String,Object> result=nodes(DraftAutomaticDiscovery.finish(graph(),plan,Arrays.asList(atomic,linked))).get(0);
        assertEquals(QUOTE,result.get("semanticSourceQuote"));assertEquals("pending",result.get("status"));assertEquals(false,map(result.get("catalogAlignment")).get("confirmed"));assertFalse(map(result.get("catalogAlignment")).containsKey("baselineKey"));assertTrue(list(result.get("editTargets")).isEmpty());
    }

    @Test void catalogSearchNavigatesBeforeReadingOnlyTheExplicitlyRequestedDefinition() {
        DraftAutomaticDiscovery.Plan plan=plan("SCC",SHA);Map<String,Object> atomic=atomic(plan,new int[]{0},node("fact","boolean","IASM"));String id=text(map(list(atomic.get("nodes")).get(0)).get("id"));int[] calls={0};String[] key={null};
        Map<String,Object> linked=DraftAutomaticDiscovery.processRelation((system,user)->{JsonNode payload=JsonUtils.parse(user);if(++calls[0]==1)return JsonUtils.write(obj("relations",Collections.emptyList(),"catalogMatches",Collections.singletonList(match(id)),"catalogSearchRequests",Collections.singletonList("IASM")));
            if(calls[0]==2){assertTrue(payload.path("fixedContext").path("fields").isEmpty());assertFalse(payload.path("catalogSearchResults").path("fields").isEmpty());assertFalse(payload.path("catalogSearchResults").path("editableAuthority").asBoolean());key[0]=payload.path("catalogSearchResults").path("fields").path(0).path("key").asText();assertEquals("iasmIncluded",key[0]);return JsonUtils.write(obj("catalogContextRequests",Collections.singletonList(key[0])));}
            assertEquals(1,payload.path("fixedContext").path("fields").size());assertEquals(key[0],payload.path("fixedContext").path("fields").path(0).path("key").asText());assertFalse(payload.path("fixedContext").path("editableAuthority").asBoolean());for(JsonNode excerpt:payload.path("fixedContext").path("fields").path(0).path("sourceContext"))assertEquals("fixed_catalog_embedded_reference",excerpt.path("sourceKind").asText());return "{\"relations\":[],\"catalogMatches\":[]}";},plan,Collections.singletonList(atomic),DraftAutomaticDiscovery.planRelations(plan,Collections.singletonList(atomic)).get(0));
        assertEquals(3,calls[0]);assertEquals(3,linked.get("modelCalls"));assertEquals(1,list(atomic.get("nodes")).size());
    }
    @Test void relationWindowReportsUnseenNodesAndCanRequestMoreWithoutLosingAtoms() {
        DraftAutomaticDiscovery.Plan plan=plan();Map<String,Object>[] raw=new Map[50];for(int i=0;i<raw.length;i++)raw[i]=node("id"+i,"boolean",i==49?"Uniquedistanttoken":"Ordinary question "+i);
        Map<String,Object> atomic=atomic(plan,new int[]{0},raw);List<?> all=list(atomic.get("nodes"));String a=text(map(all.get(0)).get("id")),last=text(map(all.get(49)).get("id"));int[] calls={0};
        Map<String,Object> receipt=DraftAutomaticDiscovery.processRelation((system,user)->{
            calls[0]++;if(calls[0]==1){assertFalse(user.contains("Uniquedistanttoken"));return JsonUtils.write(obj("relations",Collections.singletonList(obj("fromId",a,"toId",last,"kind","same_fact","fromQuote",QUOTE,"toQuote",QUOTE)),"catalogMatches",Collections.emptyList(),"relationSearchRequests",Collections.singletonList("Uniquedistanttoken")));}
            assertTrue(user.contains("Uniquedistanttoken"));return JsonUtils.write(obj("relations",Collections.singletonList(obj("fromId",a,"toId",last,"kind","same_fact","fromQuote",QUOTE,"toQuote",QUOTE,"reason","Needs review"))));
        },plan,Collections.singletonList(atomic),DraftAutomaticDiscovery.planRelations(plan,Collections.singletonList(atomic)).get(0));
        assertEquals(2,calls[0]);assertEquals(false,map(receipt.get("relationCoverage")).get("complete"));assertEquals("partial",receipt.get("status"));assertTrue(JsonUtils.write(receipt.get("issues")).contains("relation_source_or_reference_invalid"));assertEquals(1,list(receipt.get("relations")).size());assertEquals(50,nodes(DraftAutomaticDiscovery.finish(graph(),plan,Arrays.asList(atomic,receipt))).size());
    }
    @Test void followupContextIsBoundedAuditableAndExplicitlyPendingAtLimit() {
        DraftAutomaticDiscovery.Plan plan=plan();Map<String,Object> atomic=atomic(plan,new int[]{0},node("fact","boolean","Foundation package"));String id=text(map(list(atomic.get("nodes")).get(0)).get("id"));int[] calls={0};
        Map<String,Object> receipt=DraftAutomaticDiscovery.processRelation((system,user)->{calls[0]++;if(calls[0]==1)return JsonUtils.write(obj("relations",Collections.emptyList(),"catalogMatches",Collections.singletonList(match(id,candidate("combinedContractWithFoundationWorks",QUOTE))),"catalogContextRequests",Collections.singletonList("combinedContractWithFoundationWorks")));return JsonUtils.write(obj("catalogSearchRequests",Collections.singletonList(calls[0]==2?"facade":"duration")));},plan,Collections.singletonList(atomic),DraftAutomaticDiscovery.planRelations(plan,Collections.singletonList(atomic)).get(0));
        assertEquals(3,calls[0]);assertEquals(3,receipt.get("modelCalls"));assertEquals(2,list(receipt.get("contextRounds")).size());assertTrue(JsonUtils.write(receipt.get("issues")).contains("context_round_limit_reached"));assertEquals("partial",receipt.get("status"));
    }

    @Test void linkingSuggestsSemanticBaselineMatchesWithoutChangingAtomsOrAddingCalls() {
        DraftAutomaticDiscovery.Plan plan=plan();int[] calls={0};Map<String,Object> atomic=atomic(plan,calls,node("scope","boolean","Is piling part of this package?"));
        Map<String,Object> before=JsonUtils.readMap(JsonUtils.write(map(list(atomic.get("nodes")).get(0))));String id=text(before.get("id")),atomicBefore=JsonUtils.write(atomic);
        Map<String,Object> linked=DraftAutomaticDiscovery.processRelation((system,user)->{calls[0]++;assertTrue(system.contains("catalogMatches"));assertTrue(user.contains("combinedContractWithFoundationWorks"));assertFalse(user.contains("paragraphs"));assertFalse(user.contains("affects"));assertFalse(user.contains("sourcePosition"));return response(Collections.singletonList(match(id,candidate("combinedContractWithFoundationWorks",QUOTE))),Collections.emptyList());},plan,Collections.singletonList(atomic),DraftAutomaticDiscovery.planRelations(plan,Collections.singletonList(atomic)).get(0));
        Map<String,Object> graph=DraftAutomaticDiscovery.finish(graph(),plan,Arrays.asList(atomic,linked)),after=nodes(graph).get(0),alignment=map(after.get("catalogAlignment"));
        assertEquals(2,calls[0]);assertEquals(1,linked.get("modelCalls"));assertEquals("unreviewed",alignment.get("status"));assertEquals(false,alignment.get("confirmed"));assertEquals("post_atomic_semantic",alignment.get("proposalMethod"));assertEquals("matched",alignment.get("proposalStatus"));assertFalse(alignment.containsKey("baselineKey"));
        Map<String,Object> proposed=map(list(alignment.get("proposedMatches")).get(0));assertEquals("combinedContractWithFoundationWorks",proposed.get("baselineKey"));assertEquals(DraftBusinessRules.RULE_VERSION,proposed.get("baselineVersion"));assertEquals(true,proposed.get("compatible"));assertEquals(QUOTE,proposed.get("sourceQuote"));
        before.remove("catalogAlignment");Map<String,Object> preserved=JsonUtils.readMap(JsonUtils.write(after));preserved.remove("catalogAlignment");assertEquals(before,preserved);assertEquals(atomicBefore,JsonUtils.write(atomic));validate(graph);
    }

    @Test void missingMatchingOutputKeepsValidRelationsAndMarksEveryQuestionUnavailable() {
        DraftAutomaticDiscovery.Plan plan=plan();Map<String,Object> atomic=atomic(plan,new int[]{0},node("a","boolean","Scope fact"),node("b","boolean","Independent fact"));String a=text(map(list(atomic.get("nodes")).get(0)).get("id")),b=text(map(list(atomic.get("nodes")).get(1)).get("id"));
        Map<String,Object> relation=obj("fromId",a,"toId",b,"kind","dependency","fromQuote",QUOTE,"toQuote",QUOTE),linked=link(plan,atomic,JsonUtils.write(obj("relations",Collections.singletonList(relation))));
        Map<String,Object> finished=DraftAutomaticDiscovery.finish(graph(),plan,Arrays.asList(atomic,linked));assertEquals("partial",linked.get("status"));assertEquals(1,list(state(finished).get("relationProposals")).size());assertEquals(2,nodes(finished).size());for(Map<String,Object> node:nodes(finished))assertEquals("unavailable",map(node.get("catalogAlignment")).get("proposalStatus"));
        Map<String,Object> unexecuted=DraftAutomaticDiscovery.finish(graph(),plan,Collections.singletonList(atomic));for(Map<String,Object> node:nodes(unexecuted))assertEquals("unavailable",map(node.get("catalogAlignment")).get("proposalStatus"));
    }

    @Test void ambiguousUnmatchedAndIncompatibleSuggestionsRemainSeparateAndUnconfirmed() {
        DraftAutomaticDiscovery.Plan plan=plan();Map<String,Object> atomic=atomic(plan,new int[]{0},node("facade","boolean","Precast facade tender basis and permission"),node("new","text","Project-specific description"),node("period","number","Contract period in months"));List<?> atoms=list(atomic.get("nodes"));
        String a=text(map(atoms.get(0)).get("id")),b=text(map(atoms.get(1)).get("id")),c=text(map(atoms.get(2)).get("id"));
        Map<String,Object> linked=link(plan,atomic,response(Arrays.asList(match(a,candidate("precastFacadeTenderBasis",QUOTE),candidate("precastFacadePermission",QUOTE)),match(b),match(c,candidate("contractPeriodMonths",QUOTE))),Collections.emptyList()));
        List<Map<String,Object>> result=nodes(DraftAutomaticDiscovery.finish(graph(),plan,Arrays.asList(atomic,linked)));assertEquals("ambiguous",map(result.get(0).get("catalogAlignment")).get("proposalStatus"));assertEquals(2,list(map(result.get(0).get("catalogAlignment")).get("proposedMatches")).size());assertEquals("unmatched",map(result.get(1).get("catalogAlignment")).get("proposalStatus"));
        Map<String,Object> alignment=map(result.get(2).get("catalogAlignment")),candidate=map(list(alignment.get("proposedMatches")).get(0));assertEquals("ambiguous",alignment.get("proposalStatus"));assertEquals(false,candidate.get("compatible"));assertTrue(list(candidate.get("issues")).contains("unit_mismatch"));assertTrue(list(candidate.get("issues")).contains("optional_mismatch"));
        for(Map<String,Object> node:result){assertEquals(false,map(node.get("catalogAlignment")).get("confirmed"));assertEquals("pending",node.get("status"));}validate(DraftAutomaticDiscovery.finish(graph(),plan,Arrays.asList(atomic,linked)));
    }

    @Test void invalidKeysQuotesDuplicateRowsAndMissingFocusAnswersNeverBecomeMatches() {
        DraftAutomaticDiscovery.Plan plan=plan();Map<String,Object> atomic=atomic(plan,new int[]{0},node("fact","boolean","Scope fact"));String id=text(map(list(atomic.get("nodes")).get(0)).get("id"));
        for(String raw:Arrays.asList(response(Collections.singletonList(match(id,candidate("inventedKey",QUOTE))),Collections.emptyList()),response(Collections.singletonList(match(id,candidate("combinedContractWithFoundationWorks","fabricated quotation"))),Collections.emptyList()),response(Arrays.asList(match(id,candidate("combinedContractWithFoundationWorks",QUOTE)),match(id)),Collections.emptyList()),response(Collections.emptyList(),Collections.emptyList()),response(Collections.singletonList(match("foreign-node",candidate("combinedContractWithFoundationWorks",QUOTE))),Collections.emptyList()))) {
            Map<String,Object> linked=link(plan,atomic,raw),finished=DraftAutomaticDiscovery.finish(graph(),plan,Arrays.asList(atomic,linked));assertEquals("partial",linked.get("status"));assertEquals("unavailable",map(nodes(finished).get(0).get("catalogAlignment")).get("proposalStatus"));assertEquals(1,nodes(finished).size());
        }
    }

    @Test void matchingPreservesOwnAnswerBranchesTargetsAndExistingHumanConfirmations() {
        DraftAutomaticDiscovery.Plan plan=plan();Map<String,Object> question=node("fact","boolean","Foundation package applicability");question.put("branches",Collections.singletonList(obj("id","no","mode","all","clauses",Collections.singletonList(obj("inputId","fact","operator","no")))));question.put("effects",Collections.singletonList(obj("action","delete","quote",QUOTE,"branchId","no")));
        Map<String,Object> atomic=atomic(plan,new int[]{0},question),original=JsonUtils.readMap(JsonUtils.write(map(list(atomic.get("nodes")).get(0))));String id=text(original.get("id"));Map<String,Object> linked=link(plan,atomic,response(Collections.singletonList(match(id,candidate("combinedContractWithFoundationWorks",QUOTE))),Collections.emptyList())),base=DraftAutomaticDiscovery.finish(graph(),plan,Arrays.asList(atomic,linked));
        Map<String,Object> actual=JsonUtils.readMap(JsonUtils.write(nodes(base).get(0)));original.remove("catalogAlignment");actual.remove("catalogAlignment");assertEquals(original,actual);
        Map<String,Object> reviewed=nodes(base).get(0);reviewed.put("status","confirmed");reviewed.put("catalogAlignment",obj("status","matched","confirmed",true,"baselineKey","combinedContractWithFoundationWorks","baselineVersion",DraftBusinessRules.RULE_VERSION,"reason","Source and definition reviewed"));String reviewedBefore=JsonUtils.write(reviewed);
        Map<String,Object> differentProposal=link(plan,atomic,response(Collections.singletonList(match(id,candidate("precastFacadePermission",QUOTE))),Collections.emptyList()));Map<String,Object> finished=DraftAutomaticDiscovery.finish(base,plan,Arrays.asList(atomic,differentProposal));assertEquals(reviewedBefore,JsonUtils.write(nodes(finished).get(0)));assertEquals(1,nodes(finished).size());
    }

    @Test void completeMatchingRowsSurviveTruncationWhileUnfinishedRowsStayUnavailable() {
        DraftAutomaticDiscovery.Plan plan=plan();Map<String,Object> atomic=atomic(plan,new int[]{0},node("scope","boolean","Foundation package"),node("period","boolean","Period threshold"));String a=text(map(list(atomic.get("nodes")).get(0)).get("id")),b=text(map(list(atomic.get("nodes")).get(1)).get("id"));
        String raw="{\"relations\":[],\"catalogMatches\":["+JsonUtils.write(match(a,candidate("combinedContractWithFoundationWorks",QUOTE)))+",{\"nodeId\":"+JsonUtils.write(b)+",\"candidates\":[";
        Map<String,Object> linked=link(plan,atomic,raw),finished=DraftAutomaticDiscovery.finish(graph(),plan,Arrays.asList(atomic,linked));assertEquals("partial",linked.get("status"));assertEquals("matched",map(nodes(finished).get(0).get("catalogAlignment")).get("proposalStatus"));assertEquals("unavailable",map(nodes(finished).get(1).get("catalogAlignment")).get("proposalStatus"));assertEquals(2,nodes(finished).size());
    }

    @Test void versionsConfidenceAndDefinitionShapeAreCheckedWithoutChangingTheAtom() {
        DraftAutomaticDiscovery.Plan plan=plan();Map<String,Object> atomic=atomic(plan,new int[]{0},node("fact","boolean","Foundation package contract period months"));String id=text(map(list(atomic.get("nodes")).get(0)).get("id"));
        Map<String,Object> stale=candidate("combinedContractWithFoundationWorks",QUOTE);stale.put("baselineVersion","old-catalog");Map<String,Object> malformed=candidate("combinedContractWithFoundationWorks",QUOTE);malformed.put("confidence","high");
        for(Map<String,Object> invalid:Arrays.asList(stale,malformed)){Map<String,Object> linked=link(plan,atomic,response(Collections.singletonList(match(id,invalid)),Collections.emptyList()));assertEquals("unavailable",map(nodes(DraftAutomaticDiscovery.finish(graph(),plan,Arrays.asList(atomic,linked))).get(0).get("catalogAlignment")).get("proposalStatus"));}
        Map<String,Object> wrongType=link(plan,atomic,response(Collections.singletonList(match(id,candidate("contractPeriodMonths",QUOTE))),Collections.emptyList()));Map<String,Object> suggestion=map(list(map(nodes(DraftAutomaticDiscovery.finish(graph(),plan,Arrays.asList(atomic,wrongType))).get(0).get("catalogAlignment")).get("proposedMatches")).get(0));assertEquals(false,suggestion.get("compatible"));assertTrue(list(suggestion.get("issues")).contains("type_mismatch"));
    }

    @Test void sameIdentityReceivesNewMachineSuggestionsWithoutLosingAnyOtherSavedContent() {
        DraftAutomaticDiscovery.Plan plan=plan();Map<String,Object> atomic=atomic(plan,new int[]{0},node("fact","boolean","Foundation package"));String id=text(map(list(atomic.get("nodes")).get(0)).get("id"));Map<String,Object> base=graph(),old=JsonUtils.readMap(JsonUtils.write(map(list(atomic.get("nodes")).get(0))));old.remove("catalogAlignment");old.put("status","confirmed");old.put("manual",true);state(base).put("variables",new ArrayList<>(Collections.singletonList(old)));
        Map<String,Object> linked=link(plan,atomic,response(Collections.singletonList(match(id,candidate("combinedContractWithFoundationWorks",QUOTE))),Collections.emptyList())),finished=DraftAutomaticDiscovery.finish(base,plan,Arrays.asList(atomic,linked));Map<String,Object> after=JsonUtils.readMap(JsonUtils.write(nodes(finished).get(0)));assertEquals("matched",map(after.get("catalogAlignment")).get("proposalStatus"));after.remove("catalogAlignment");assertEquals(old,after);assertEquals(1,nodes(finished).size());assertFalse(old.containsKey("catalogAlignment"));
        for(String humanStatus:Arrays.asList("new","ambiguous")){Map<String,Object> human=JsonUtils.readMap(JsonUtils.write(base)),decision=obj("status",humanStatus,"confirmed",false,"reason","Explicit human definition decision");nodes(human).get(0).put("catalogAlignment",decision);Map<String,Object> kept=DraftAutomaticDiscovery.finish(human,plan,Arrays.asList(atomic,linked));assertEquals(decision,nodes(kept).get(0).get("catalogAlignment"));}
    }

    @Test void matchingCannotBlockAnOriginalRelationRequestThatFitsItsExistingBudget() {
        DraftAutomaticDiscovery.Plan plan=plan();Map<String,Object> atomic=atomic(plan,new int[]{0},node("fact","boolean","Foundation package")),node=map(list(atomic.get("nodes")).get(0));String oversized=String.join("",Collections.nCopies(55000,"x"));node.put("nameEn",oversized);node.put("questionEn",oversized);int[] calls={0};
        Map<String,Object> linked=DraftAutomaticDiscovery.processRelation((system,user)->{calls[0]++;assertFalse(system.contains("catalogMatches"));assertFalse(user.contains("fixedCatalog"));assertTrue(user.length()+system.length()<=160000);return "{\"relations\":[]}";},plan,Collections.singletonList(atomic),DraftAutomaticDiscovery.planRelations(plan,Collections.singletonList(atomic)).get(0));
        assertEquals(1,calls[0]);assertEquals("partial",linked.get("status"));assertTrue(JsonUtils.write(linked.get("issues")).contains("catalog_matching_context_budget_exceeded"));assertEquals("unavailable",map(nodes(DraftAutomaticDiscovery.finish(graph(),plan,Arrays.asList(atomic,linked))).get(0).get("catalogAlignment")).get("proposalStatus"));
    }

    @Test void oversizedFocusBlocksRelationCallWithoutDroppingAtomicNode() {
        DraftAutomaticDiscovery.Plan plan=plan();Map<String,Object> atomic=atomic(plan,new int[]{0},node("fact","boolean","Foundation package"));map(list(atomic.get("nodes")).get(0)).put("nameEn",String.join("",Collections.nCopies(160000,"x")));int[] calls={0};
        Map<String,Object> linked=DraftAutomaticDiscovery.processRelation((system,user)->{calls[0]++;return "{}";},plan,Collections.singletonList(atomic),DraftAutomaticDiscovery.planRelations(plan,Collections.singletonList(atomic)).get(0));assertEquals(0,calls[0]);assertEquals("blocked",linked.get("status"));assertEquals("focus_relation_context_exceeds_budget",linked.get("reason"));assertEquals(1,nodes(DraftAutomaticDiscovery.finish(graph(),plan,Arrays.asList(atomic,linked))).size());
    }

    @Test void matchingCatalogExcludesInternalIdsThatTheHumanCatalogCannotSelect() {
        Set<String> keys=new HashSet<>();for(Object raw:list(DraftBaselineMatcher.catalog().get("fields")))keys.add(text(map(raw).get("key")));assertFalse(keys.contains("billNos.id"));assertFalse(keys.contains("sections.id"));assertFalse(keys.contains("contractTitle"));assertTrue(keys.contains("combinedContractWithFoundationWorks"));
    }

    private static DraftAutomaticDiscovery.Plan plan(){return plan("NTT",SHA);}
    private static DraftAutomaticDiscovery.Plan plan(String family,String sha){ObjectNode source=JsonUtils.mapper().createObjectNode().put("id","1").put("sourceDocumentId",1).put("sourceSha256",sha).put("format","docx").put("fileKey",family).put("fileName",family+".docx").put("evidenceStatus","bound");for(String key:Arrays.asList("sourceMembers","paragraphs","guidanceNotes","markerCandidates","groups","conditions"))source.putArray(key);ObjectNode member=source.withArray("sourceMembers").addObject().put("id","body").put("storyKind","main").put("identityStatus","source_bound");member.putObject("sourcePosition").put("sourceSha256",sha).put("partName","word/document.xml").put("nodePath","/body");member.putObject("textObservation").put("text",QUOTE);ObjectNode note=source.withArray("guidanceNotes").addObject().put("id","note").put("text",QUOTE).put("scopeComplete",false);note.putArray("memberRanges").addObject().put("memberId","body").put("channel","text").put("start",0).put("end",QUOTE.length());return DraftAutomaticDiscovery.plan(Collections.singletonList(source));}
    private static Map<String,Object> graph(){return obj("graph",obj("variables",new ArrayList<>(),"events",new ArrayList<>()),"sources",new ArrayList<>());}
    private static Map<String,Object> node(String id,String type,String name){return obj("id",id,"type",type,"role","input","nameZh",name,"nameEn",name,"questionEn",name,"sourceQuote",QUOTE,"options",new ArrayList<>(),"branches",new ArrayList<>(),"effects",new ArrayList<>());}
    @SafeVarargs private static Map<String,Object> atomic(DraftAutomaticDiscovery.Plan plan,int[] calls,Map<String,Object>... nodes){List<Object> ids=new ArrayList<>();for(Map<String,Object> node:nodes)ids.add(node.get("id"));return DraftAutomaticDiscovery.process((system,user)->{calls[0]++;return JsonUtils.write(obj("nodes",Arrays.asList(nodes),"requirements",Collections.singletonList(obj("id","requirement","sourceQuote",QUOTE,"kind","input","nodeIds",ids))));},plan,plan.tasks().get(0));}
    private static Map<String,Object> link(DraftAutomaticDiscovery.Plan plan,Map<String,Object> atomic,String raw){return DraftAutomaticDiscovery.processRelation((system,user)->raw,plan,Collections.singletonList(atomic),DraftAutomaticDiscovery.planRelations(plan,Collections.singletonList(atomic)).get(0));}
    @SafeVarargs private static Map<String,Object> match(String id,Map<String,Object>... candidates){return obj("nodeId",id,"candidates",Arrays.asList(candidates));}
    private static Map<String,Object> candidate(String key,String quote){return obj("baselineKey",key,"sourceQuote",quote,"reason","The source fact has the same business meaning despite a different question label.","confidence",0.91);}
    private static String response(List<?> matches,List<?> relations){return JsonUtils.write(obj("relations",relations,"catalogMatches",matches));}
}
