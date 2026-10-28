package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

class DraftRelationReviewTest {
    @Test void explicitReviewClosesEachSelectedProposalWithoutExecutingOrChangingItsEvidence(){
        Map<String,Object> prior=graph(),next=copy(prior);String nodesBefore=JsonUtils.write(nodes(next));
        for(String decision:Arrays.asList("implemented","rejected","not_applicable")){
            next=copy(prior);proposal(next).put("review",obj("decision",decision,"reason"," Reviewed both source passages ","basis",null));
            DraftTemplateGraphService.reconcileRelationReviews(next,prior);Map<String,Object> review=map(proposal(next).get("review"));
            assertEquals(0,DraftTemplateGraphService.unresolvedGraphRelations(next));assertEquals("reviewed",review.get("status"));assertNotNull(review.get("basis"));assertNotNull(review.get("confirmedAt"));assertEquals("Reviewed both source passages",review.get("reason"));
            assertEquals(false,proposal(next).get("executable"));assertEquals(nodesBefore,JsonUtils.write(nodes(next)));
            for(String key:Arrays.asList("fromId","toId","kind","fromQuote","toQuote","reason"))assertEquals(proposal(prior).get(key),proposal(next).get(key));
            assertFalse(proposal(prior).containsKey("review"));assertFalse(review.containsKey("reviewHistory"));
        }
    }

    @Test void aClosedStatusAloneAndTamperedStampedReviewCannotBypassHumanReview(){
        Map<String,Object> prior=graph(),next=copy(prior);proposal(next).put("status","confirmed");
        assertEquals(1,DraftTemplateGraphService.unresolvedGraphRelations(next));DraftTemplateGraphService.reconcileRelationReviews(next,prior);assertEquals("unresolved",proposal(next).get("status"));
        for(Map<String,Object> invalid:Arrays.asList(obj("decision","implemented","reason"," ","basis",null),obj("decision","unknown","reason","Reason","basis",null))){Map<String,Object> rejected=copy(prior);proposal(rejected).put("review",invalid);assertThrows(BizException.class,()->DraftTemplateGraphService.reconcileRelationReviews(rejected,prior));}
        Map<String,Object> reviewed=reviewed(),forged=copy(reviewed);map(proposal(forged).get("review")).put("reason","Forged stamped review");DraftTemplateGraphService.reconcileRelationReviews(forged,reviewed);assertEquals(map(proposal(reviewed).get("review")),map(proposal(forged).get("review")),"A client stamp cannot replace the server's saved decision");
        Map<String,Object> rewritten=copy(prior);proposal(rewritten).put("fromQuote","A different claim");proposal(rewritten).put("review",obj("decision","rejected","reason","Hide changed evidence","basis",null));assertThrows(BizException.class,()->DraftTemplateGraphService.reconcileRelationReviews(rewritten,prior));
        proposal(rewritten).remove("review");assertThrows(BizException.class,()->DraftTemplateGraphService.reconcileRelationReviews(rewritten,prior));
        Map<String,Object> deleted=copy(prior);state(deleted).put("relationProposals",Collections.emptyList());assertThrows(BizException.class,()->DraftTemplateGraphService.reconcileRelationReviews(deleted,prior));
        Map<String,Object> extra=copy(prior),newProposal=copy(proposal(prior));newProposal.put("kind","dependency");newProposal.put("status","confirmed");newProposal.put("review",obj("decision","implemented","reason","Invented stamped review","basis","fake","status","reviewed"));state(extra).put("relationProposals",Arrays.asList(proposal(extra),newProposal));DraftTemplateGraphService.reconcileRelationReviews(extra,prior);assertEquals("unresolved",newProposal.get("status"));assertEquals(2,DraftTemplateGraphService.unresolvedGraphRelations(extra));
    }

    @Test void onlyRelatedDefinitionRuleScopeOrSourceChangesReopenTheRetainedDecision(){
        Map<String,Object> prior=reviewed();String oldBasis=String.valueOf(map(proposal(prior).get("review")).get("basis"));
        List<Consumer<Map<String,Object>>> changes=Arrays.asList(
            g->node(g,"a").put("nameEn","Different business meaning"),
            g->node(g,"b").put("correction",obj("kind","rule","branches",Collections.singletonList(obj("id","yes","clauses",Collections.singletonList(obj("inputId","c","operator","known")),"resultValue",true)))),
            g->node(g,"a").put("editTargets",Collections.singletonList(obj("docId","41","text","A different range","action","delete"))),
            g->node(g,"c").put("options",Arrays.asList("electronic","paper","hybrid")),
            g->map(list(g.get("sources")).get(0)).put("analysisSha256","changed-analysis"),
            g->map(g.get("selectedDocuments")).put("NTT","other-source"));
        for(Consumer<Map<String,Object>> change:changes){Map<String,Object> next=copy(prior);change.accept(next);assertTrue(DraftTemplateGraphService.unresolvedGraphRelations(next)>0);DraftTemplateGraphService.reconcileRelationReviews(next,prior);assertEquals("unresolved",proposal(next).get("status"));Map<String,Object> review=map(proposal(next).get("review"));assertEquals("needs_review",review.get("status"));assertEquals(oldBasis,review.get("basis"));assertEquals("Explicit human decision",review.get("reason"));}
        Map<String,Object> unrelated=copy(prior);node(unrelated,"unrelated").put("nameEn","An unrelated warranty");node(unrelated,"a").put("status","confirmed");DraftTemplateGraphService.reconcileRelationReviews(unrelated,prior);assertEquals(0,DraftTemplateGraphService.unresolvedGraphRelations(unrelated));assertEquals(map(proposal(prior).get("review")),map(proposal(unrelated).get("review")));
    }

    @Test void mergedOriginalEndpointsRemainReviewableAndReplacementChangesReopenThem(){
        Map<String,Object> prior=graph();node(prior,"a").put("status","excluded");node(prior,"a").put("replacedBy",Collections.singletonList("b"));
        Map<String,Object> next=copy(prior);proposal(next).put("review",obj("decision","implemented","reason","Merged a into b; both ranges retained","basis",null));DraftTemplateGraphService.reconcileRelationReviews(next,prior);assertEquals(0,DraftTemplateGraphService.unresolvedGraphRelations(next));assertEquals("a",proposal(next).get("fromId"));
        Map<String,Object> changed=copy(next);node(changed,"b").put("nameEn","Changed retained variable");DraftTemplateGraphService.reconcileRelationReviews(changed,next);assertEquals("needs_review",map(proposal(changed).get("review")).get("status"));assertEquals(false,proposal(changed).get("executable"));
    }

    @Test void undoAfterSaveKeepsTheServerReviewAndDoesNotRejectTheOlderClientStamp(){
        Map<String,Object> original=reviewed(),edited=copy(original);node(edited,"a").put("nameEn","Edited definition");DraftTemplateGraphService.reconcileRelationReviews(edited,original);assertEquals("needs_review",map(proposal(edited).get("review")).get("status"));
        Map<String,Object> undone=copy(original);assertDoesNotThrow(()->DraftTemplateGraphService.reconcileRelationReviews(undone,edited));assertEquals("a",node(undone,"a").get("nameEn"));assertEquals(map(proposal(edited).get("review")),map(proposal(undone).get("review")));assertEquals("unresolved",proposal(undone).get("status"),"Undoing an edit must not silently reconfirm a relationship");
    }

    private static Map<String,Object> reviewed(){Map<String,Object> prior=graph(),next=copy(prior);proposal(next).put("review",obj("decision","implemented","reason","Explicit human decision","basis",null));DraftTemplateGraphService.reconcileRelationReviews(next,prior);return next;}
    private static Map<String,Object> graph(){Map<String,Object> a=node("a"),b=node("b"),c=node("c"),unrelated=node("unrelated");a.put("relatedInputIds",Collections.singletonList("c"));return obj("kind","consense-template-graph-v1","selectedDocuments",obj("NTT","41"),"sources",Collections.singletonList(obj("id","41","docId","41","fileKey","NTT","sourceSha256","original","analysisRevision","analysis-1","analysisSha256","analysis")),"graph",obj("variables",Arrays.asList(a,b,c,unrelated),"relationProposals",Collections.singletonList(obj("fromId","a","toId","b","kind","same_fact","fromQuote","Original note A","toQuote","Original note B","reason","Original model reason","status","pending","executable",false))));}
    private static Map<String,Object> node(String id){return obj("id",id,"nameEn",id,"type",obj("kind","text"),"role",obj("kind","input"),"status","pending","docIds",Collections.singletonList("41"),"editTargets",new ArrayList<>());}
    private static Map<String,Object> node(Map<String,Object> graph,String id){return nodes(graph).stream().filter(item->id.equals(item.get("id"))).findFirst().orElseThrow();}
    private static Map<String,Object> proposal(Map<String,Object> graph){return map(list(state(graph).get("relationProposals")).get(0));}
    private static Map<String,Object> copy(Map<String,Object> graph){return JsonUtils.readMap(JsonUtils.write(graph));}
}
