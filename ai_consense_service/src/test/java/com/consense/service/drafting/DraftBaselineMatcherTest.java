package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.consense.service.drafting.DraftReviewGraph.*;
import static org.junit.jupiter.api.Assertions.*;

/** Post-atomic matching uses the existing linking call; deterministic returned JSON, no real model calls. */
class DraftBaselineMatcherTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a"));
    private static final String QUOTE="The package includes foundations; verify the independent facade and duration requirements.";

    @Test void linkingSuggestsSemanticBaselineMatchesWithoutChangingAtomsOrAddingCalls() {
        DraftAutomaticDiscovery.Plan plan=plan();int[] calls={0};Map<String,Object> atomic=atomic(plan,calls,node("scope","boolean","Is piling part of this package?"));
        Map<String,Object> before=JsonUtils.readMap(JsonUtils.write(map(list(atomic.get("nodes")).get(0))));String id=text(before.get("id")),atomicBefore=JsonUtils.write(atomic);
        Map<String,Object> linked=DraftAutomaticDiscovery.processRelation((system,user)->{calls[0]++;assertTrue(system.contains("catalogMatches"));assertTrue(user.contains("foundationIncluded"));assertFalse(user.contains("paragraphs"));assertFalse(user.contains("affects"));assertFalse(user.contains("sourcePosition"));return response(Collections.singletonList(match(id,candidate("foundationIncluded",QUOTE))),Collections.emptyList());},plan,Collections.singletonList(atomic),DraftAutomaticDiscovery.planRelations(plan,Collections.singletonList(atomic)).get(0));
        Map<String,Object> graph=DraftAutomaticDiscovery.finish(graph(),plan,Arrays.asList(atomic,linked)),after=nodes(graph).get(0),alignment=map(after.get("catalogAlignment"));
        assertEquals(2,calls[0]);assertEquals(1,linked.get("modelCalls"));assertEquals("unreviewed",alignment.get("status"));assertEquals(false,alignment.get("confirmed"));assertEquals("post_atomic_semantic",alignment.get("proposalMethod"));assertEquals("matched",alignment.get("proposalStatus"));assertFalse(alignment.containsKey("baselineKey"));
        Map<String,Object> proposed=map(list(alignment.get("proposedMatches")).get(0));assertEquals("foundationIncluded",proposed.get("baselineKey"));assertEquals(DraftBusinessRules.RULE_VERSION,proposed.get("baselineVersion"));assertEquals(true,proposed.get("compatible"));assertEquals(QUOTE,proposed.get("sourceQuote"));
        before.remove("catalogAlignment");Map<String,Object> preserved=JsonUtils.readMap(JsonUtils.write(after));preserved.remove("catalogAlignment");assertEquals(before,preserved);assertEquals(atomicBefore,JsonUtils.write(atomic));validate(graph);
    }

    @Test void missingMatchingOutputKeepsValidRelationsAndMarksEveryQuestionUnavailable() {
        DraftAutomaticDiscovery.Plan plan=plan();Map<String,Object> atomic=atomic(plan,new int[]{0},node("a","boolean","Scope fact"),node("b","boolean","Independent fact"));String a=text(map(list(atomic.get("nodes")).get(0)).get("id")),b=text(map(list(atomic.get("nodes")).get(1)).get("id"));
        Map<String,Object> relation=obj("fromId",a,"toId",b,"kind","dependency","fromQuote",QUOTE,"toQuote",QUOTE),linked=link(plan,atomic,JsonUtils.write(obj("relations",Collections.singletonList(relation))));
        Map<String,Object> finished=DraftAutomaticDiscovery.finish(graph(),plan,Arrays.asList(atomic,linked));assertEquals("partial",linked.get("status"));assertEquals(1,list(state(finished).get("relationProposals")).size());assertEquals(2,nodes(finished).size());for(Map<String,Object> node:nodes(finished))assertEquals("unavailable",map(node.get("catalogAlignment")).get("proposalStatus"));
        Map<String,Object> unexecuted=DraftAutomaticDiscovery.finish(graph(),plan,Collections.singletonList(atomic));for(Map<String,Object> node:nodes(unexecuted))assertEquals("unavailable",map(node.get("catalogAlignment")).get("proposalStatus"));
    }

    @Test void ambiguousUnmatchedAndIncompatibleSuggestionsRemainSeparateAndUnconfirmed() {
        DraftAutomaticDiscovery.Plan plan=plan();Map<String,Object> atomic=atomic(plan,new int[]{0},node("facade","boolean","Facade proposal applicability"),node("new","text","Project-specific description"),node("period","number","Tender period"));List<?> atoms=list(atomic.get("nodes"));
        String a=text(map(atoms.get(0)).get("id")),b=text(map(atoms.get(1)).get("id")),c=text(map(atoms.get(2)).get("id"));
        Map<String,Object> linked=link(plan,atomic,response(Arrays.asList(match(a,candidate("precastFacadeTenderBasis",QUOTE),candidate("precastFacadePermission",QUOTE)),match(b),match(c,candidate("contractPeriodMonths",QUOTE))),Collections.emptyList()));
        List<Map<String,Object>> result=nodes(DraftAutomaticDiscovery.finish(graph(),plan,Arrays.asList(atomic,linked)));assertEquals("ambiguous",map(result.get(0).get("catalogAlignment")).get("proposalStatus"));assertEquals(2,list(map(result.get(0).get("catalogAlignment")).get("proposedMatches")).size());assertEquals("unmatched",map(result.get(1).get("catalogAlignment")).get("proposalStatus"));
        Map<String,Object> alignment=map(result.get(2).get("catalogAlignment")),candidate=map(list(alignment.get("proposedMatches")).get(0));assertEquals("ambiguous",alignment.get("proposalStatus"));assertEquals(false,candidate.get("compatible"));assertTrue(list(candidate.get("issues")).contains("unit_mismatch"));assertTrue(list(candidate.get("issues")).contains("optional_mismatch"));
        for(Map<String,Object> node:result){assertEquals(false,map(node.get("catalogAlignment")).get("confirmed"));assertEquals("pending",node.get("status"));}validate(DraftAutomaticDiscovery.finish(graph(),plan,Arrays.asList(atomic,linked)));
    }

    @Test void invalidKeysQuotesDuplicateRowsAndMissingFocusAnswersNeverBecomeMatches() {
        DraftAutomaticDiscovery.Plan plan=plan();Map<String,Object> atomic=atomic(plan,new int[]{0},node("fact","boolean","Scope fact"));String id=text(map(list(atomic.get("nodes")).get(0)).get("id"));
        for(String raw:Arrays.asList(response(Collections.singletonList(match(id,candidate("inventedKey",QUOTE))),Collections.emptyList()),response(Collections.singletonList(match(id,candidate("foundationIncluded","fabricated quotation"))),Collections.emptyList()),response(Arrays.asList(match(id,candidate("foundationIncluded",QUOTE)),match(id)),Collections.emptyList()),response(Collections.emptyList(),Collections.emptyList()),response(Collections.singletonList(match("foreign-node",candidate("foundationIncluded",QUOTE))),Collections.emptyList()))) {
            Map<String,Object> linked=link(plan,atomic,raw),finished=DraftAutomaticDiscovery.finish(graph(),plan,Arrays.asList(atomic,linked));assertEquals("partial",linked.get("status"));assertEquals("unavailable",map(nodes(finished).get(0).get("catalogAlignment")).get("proposalStatus"));assertEquals(1,nodes(finished).size());
        }
    }

    @Test void matchingPreservesOwnAnswerBranchesTargetsAndExistingHumanConfirmations() {
        DraftAutomaticDiscovery.Plan plan=plan();Map<String,Object> question=node("fact","boolean","Foundation package applicability");question.put("branches",Collections.singletonList(obj("id","no","mode","all","clauses",Collections.singletonList(obj("inputId","fact","operator","no")))));question.put("effects",Collections.singletonList(obj("action","delete","quote",QUOTE,"branchId","no")));
        Map<String,Object> atomic=atomic(plan,new int[]{0},question),original=JsonUtils.readMap(JsonUtils.write(map(list(atomic.get("nodes")).get(0))));String id=text(original.get("id"));Map<String,Object> linked=link(plan,atomic,response(Collections.singletonList(match(id,candidate("foundationIncluded",QUOTE))),Collections.emptyList())),base=DraftAutomaticDiscovery.finish(graph(),plan,Arrays.asList(atomic,linked));
        Map<String,Object> actual=JsonUtils.readMap(JsonUtils.write(nodes(base).get(0)));original.remove("catalogAlignment");actual.remove("catalogAlignment");assertEquals(original,actual);
        Map<String,Object> reviewed=nodes(base).get(0);reviewed.put("status","confirmed");reviewed.put("catalogAlignment",obj("status","matched","confirmed",true,"baselineKey","foundationIncluded","baselineVersion",DraftBusinessRules.RULE_VERSION,"reason","Source and definition reviewed"));String reviewedBefore=JsonUtils.write(reviewed);
        Map<String,Object> differentProposal=link(plan,atomic,response(Collections.singletonList(match(id,candidate("precastFacadePermission",QUOTE))),Collections.emptyList()));Map<String,Object> finished=DraftAutomaticDiscovery.finish(base,plan,Arrays.asList(atomic,differentProposal));assertEquals(reviewedBefore,JsonUtils.write(nodes(finished).get(0)));assertEquals(1,nodes(finished).size());
    }

    @Test void completeMatchingRowsSurviveTruncationWhileUnfinishedRowsStayUnavailable() {
        DraftAutomaticDiscovery.Plan plan=plan();Map<String,Object> atomic=atomic(plan,new int[]{0},node("scope","boolean","Foundation package"),node("period","boolean","Period threshold"));String a=text(map(list(atomic.get("nodes")).get(0)).get("id")),b=text(map(list(atomic.get("nodes")).get(1)).get("id"));
        String raw="{\"relations\":[],\"catalogMatches\":["+JsonUtils.write(match(a,candidate("foundationIncluded",QUOTE)))+",{\"nodeId\":"+JsonUtils.write(b)+",\"candidates\":[";
        Map<String,Object> linked=link(plan,atomic,raw),finished=DraftAutomaticDiscovery.finish(graph(),plan,Arrays.asList(atomic,linked));assertEquals("partial",linked.get("status"));assertEquals("matched",map(nodes(finished).get(0).get("catalogAlignment")).get("proposalStatus"));assertEquals("unavailable",map(nodes(finished).get(1).get("catalogAlignment")).get("proposalStatus"));assertEquals(2,nodes(finished).size());
    }

    @Test void versionsConfidenceAndDefinitionShapeAreCheckedWithoutChangingTheAtom() {
        DraftAutomaticDiscovery.Plan plan=plan();Map<String,Object> atomic=atomic(plan,new int[]{0},node("fact","boolean","Foundation package"));String id=text(map(list(atomic.get("nodes")).get(0)).get("id"));
        Map<String,Object> stale=candidate("foundationIncluded",QUOTE);stale.put("baselineVersion","old-catalog");Map<String,Object> malformed=candidate("foundationIncluded",QUOTE);malformed.put("confidence","high");
        for(Map<String,Object> invalid:Arrays.asList(stale,malformed)){Map<String,Object> linked=link(plan,atomic,response(Collections.singletonList(match(id,invalid)),Collections.emptyList()));assertEquals("unavailable",map(nodes(DraftAutomaticDiscovery.finish(graph(),plan,Arrays.asList(atomic,linked))).get(0).get("catalogAlignment")).get("proposalStatus"));}
        Map<String,Object> wrongType=link(plan,atomic,response(Collections.singletonList(match(id,candidate("contractPeriodMonths",QUOTE))),Collections.emptyList()));Map<String,Object> suggestion=map(list(map(nodes(DraftAutomaticDiscovery.finish(graph(),plan,Arrays.asList(atomic,wrongType))).get(0).get("catalogAlignment")).get("proposedMatches")).get(0));assertEquals(false,suggestion.get("compatible"));assertTrue(list(suggestion.get("issues")).contains("type_mismatch"));
    }

    @Test void sameIdentityReceivesNewMachineSuggestionsWithoutLosingAnyOtherSavedContent() {
        DraftAutomaticDiscovery.Plan plan=plan();Map<String,Object> atomic=atomic(plan,new int[]{0},node("fact","boolean","Foundation package"));String id=text(map(list(atomic.get("nodes")).get(0)).get("id"));Map<String,Object> base=graph(),old=JsonUtils.readMap(JsonUtils.write(map(list(atomic.get("nodes")).get(0))));old.remove("catalogAlignment");old.put("status","confirmed");old.put("manual",true);state(base).put("variables",new ArrayList<>(Collections.singletonList(old)));
        Map<String,Object> linked=link(plan,atomic,response(Collections.singletonList(match(id,candidate("foundationIncluded",QUOTE))),Collections.emptyList())),finished=DraftAutomaticDiscovery.finish(base,plan,Arrays.asList(atomic,linked));Map<String,Object> after=JsonUtils.readMap(JsonUtils.write(nodes(finished).get(0)));assertEquals("matched",map(after.get("catalogAlignment")).get("proposalStatus"));after.remove("catalogAlignment");assertEquals(old,after);assertEquals(1,nodes(finished).size());assertFalse(old.containsKey("catalogAlignment"));
        for(String humanStatus:Arrays.asList("new","ambiguous")){Map<String,Object> human=JsonUtils.readMap(JsonUtils.write(base)),decision=obj("status",humanStatus,"confirmed",false,"reason","Explicit human definition decision");nodes(human).get(0).put("catalogAlignment",decision);Map<String,Object> kept=DraftAutomaticDiscovery.finish(human,plan,Arrays.asList(atomic,linked));assertEquals(decision,nodes(kept).get(0).get("catalogAlignment"));}
    }

    @Test void matchingCannotBlockAnOriginalRelationRequestThatFitsItsExistingBudget() {
        DraftAutomaticDiscovery.Plan plan=plan();Map<String,Object> atomic=atomic(plan,new int[]{0},node("fact","boolean","Foundation package")),node=map(list(atomic.get("nodes")).get(0));String oversized=String.join("",Collections.nCopies(70000,"x"));node.put("nameEn",oversized);node.put("questionEn",oversized);int[] calls={0};
        Map<String,Object> linked=DraftAutomaticDiscovery.processRelation((system,user)->{calls[0]++;assertFalse(system.contains("catalogMatches"));assertFalse(user.contains("fixedCatalog"));assertTrue(user.length()<160000);return "{\"relations\":[]}";},plan,Collections.singletonList(atomic),DraftAutomaticDiscovery.planRelations(plan,Collections.singletonList(atomic)).get(0));
        assertEquals(1,calls[0]);assertEquals("partial",linked.get("status"));assertTrue(JsonUtils.write(linked.get("issues")).contains("catalog_matching_context_budget_exceeded"));assertEquals("unavailable",map(nodes(DraftAutomaticDiscovery.finish(graph(),plan,Arrays.asList(atomic,linked))).get(0).get("catalogAlignment")).get("proposalStatus"));
    }

    @Test void matchingCatalogExcludesInternalIdsThatTheHumanCatalogCannotSelect() {
        Set<String> keys=new HashSet<>();for(Object raw:list(DraftBaselineMatcher.catalog().get("fields")))keys.add(text(map(raw).get("key")));assertFalse(keys.contains("billNos.id"));assertFalse(keys.contains("sections.id"));assertFalse(keys.contains("contractTitle"));assertTrue(keys.contains("foundationIncluded"));
    }

    private static DraftAutomaticDiscovery.Plan plan(){ObjectNode source=JsonUtils.mapper().createObjectNode().put("id","1").put("sourceDocumentId",1).put("sourceSha256",SHA).put("format","docx").put("fileKey","NTT").put("fileName","NTT.docx").put("evidenceStatus","bound");for(String key:Arrays.asList("sourceMembers","paragraphs","guidanceNotes","markerCandidates","groups","conditions"))source.putArray(key);ObjectNode member=source.withArray("sourceMembers").addObject().put("id","body").put("storyKind","main").put("identityStatus","source_bound");member.putObject("sourcePosition").put("sourceSha256",SHA).put("partName","word/document.xml").put("nodePath","/body");member.putObject("textObservation").put("text",QUOTE);ObjectNode note=source.withArray("guidanceNotes").addObject().put("id","note").put("text",QUOTE).put("scopeComplete",false);note.putArray("memberRanges").addObject().put("memberId","body").put("channel","text").put("start",0).put("end",QUOTE.length());return DraftAutomaticDiscovery.plan(Collections.singletonList(source));}
    private static Map<String,Object> graph(){return obj("graph",obj("variables",new ArrayList<>(),"events",new ArrayList<>()),"sources",new ArrayList<>());}
    private static Map<String,Object> node(String id,String type,String name){return obj("id",id,"type",type,"role","input","nameZh",name,"nameEn",name,"questionEn",name,"sourceQuote",QUOTE,"options",new ArrayList<>(),"branches",new ArrayList<>(),"effects",new ArrayList<>());}
    @SafeVarargs private static Map<String,Object> atomic(DraftAutomaticDiscovery.Plan plan,int[] calls,Map<String,Object>... nodes){List<Object> ids=new ArrayList<>();for(Map<String,Object> node:nodes)ids.add(node.get("id"));return DraftAutomaticDiscovery.process((system,user)->{calls[0]++;return JsonUtils.write(obj("nodes",Arrays.asList(nodes),"requirements",Collections.singletonList(obj("id","requirement","sourceQuote",QUOTE,"kind","input","nodeIds",ids))));},plan,plan.tasks().get(0));}
    private static Map<String,Object> link(DraftAutomaticDiscovery.Plan plan,Map<String,Object> atomic,String raw){return DraftAutomaticDiscovery.processRelation((system,user)->raw,plan,Collections.singletonList(atomic),DraftAutomaticDiscovery.planRelations(plan,Collections.singletonList(atomic)).get(0));}
    @SafeVarargs private static Map<String,Object> match(String id,Map<String,Object>... candidates){return obj("nodeId",id,"candidates",Arrays.asList(candidates));}
    private static Map<String,Object> candidate(String key,String quote){return obj("baselineKey",key,"sourceQuote",quote,"reason","The source fact has the same business meaning despite a different question label.","confidence",0.91);}
    private static String response(List<?> matches,List<?> relations){return JsonUtils.write(obj("relations",relations,"catalogMatches",matches));}
}
