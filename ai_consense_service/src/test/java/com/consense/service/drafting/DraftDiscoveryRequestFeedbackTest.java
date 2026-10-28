package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Rejected tool arguments receive bounded owner-local repair choices, never evidence authority. */
class DraftDiscoveryRequestFeedbackTest {
    private static final String OWNER="bill-owner",PEER="same-source-peer",FOREIGN="other-source-owner";

    @Test void billScopeLabelsRequestedAsMembersStayRejectedAndExplainEachCorrectKind(){
        DraftDiscoveryReferences refs=DraftDiscoveryReferences.group(packet());List<Object> requests=new ArrayList<>();for(int i=1;i<=4;i++)requests.add(request("t1","member","s"+i));
        JsonNode raw=tree(obj("sourceContextRequests",requests));String before=raw.toString();JsonNode decoded=refs.decode(raw),feedback=tree(refs.requestFeedback());
        assertEquals(before,raw.toString());assertEquals(4,feedback.size());
        for(int i=0;i<4;i++){
            assertTrue(decoded.path("sourceContextRequests").path(i).path("id").asText().startsWith("unresolved-source-alias:"));
            assertEquals("member",decoded.path("sourceContextRequests").path(i).path("kind").asText());
            assertEquals(i,feedback.path(i).path("requestIndex").asInt());assertEquals("t1",feedback.path(i).path("sourceTaskId").asText());
            assertEquals("source_request_kind_mismatch",feedback.path(i).path("reason").asText());assertEquals(tree(Collections.singletonList(obj("kind","scope","id","s"+(i+1)))),feedback.path(i).path("choices"));
        }
    }

    @Test void guidanceRequestedAsScopeRequiresAnExplicitCorrectedRequestAndKeepsBusinessIds(){
        DraftDiscoveryReferences refs=DraftDiscoveryReferences.group(packet());JsonNode rejected=refs.decode(tree(obj("sourceContextRequests",Collections.singletonList(request("t1","scope","g1")))));
        assertTrue(rejected.path("sourceContextRequests").path(0).path("id").asText().startsWith("unresolved-source-alias:"));
        JsonNode choice=tree(refs.requestFeedback()).path(0).path("choices").path(0);assertEquals(tree(obj("kind","guidance","id","g1")),choice);
        Map<String,Object> node=obj("id","g1","sourceTaskId","t1","branches",Collections.singletonList(obj("scopeId","s1","clauses",Collections.singletonList(obj("inputId","t2")))));
        JsonNode fixed=refs.decode(tree(obj("sourceContextRequests",Collections.singletonList(request("t1",choice.path("kind").asText(),choice.path("id").asText())),"nodes",Collections.singletonList(node))));
        assertEquals("guidance-a",fixed.path("sourceContextRequests").path(0).path("id").asText());assertEquals(OWNER,fixed.path("sourceContextRequests").path(0).path("sourceTaskId").asText());assertTrue(refs.requestFeedback().isEmpty());
        assertEquals("g1",fixed.path("nodes").path(0).path("id").asText());assertEquals("s1",fixed.path("nodes").path(0).path("branches").path(0).path("scopeId").asText());assertEquals("t2",fixed.path("nodes").path(0).path("branches").path(0).path("clauses").path(0).path("inputId").asText());
    }

    @Test void followUpEncodePreservesFeedbackWireLabelsEvenWhenTheyAlsoExistAsActualIds(){
        Map<String,Object> packet=packet(),first=map(list(packet.get("instructions")).get(0));
        List<Object> evidenceRefs=new ArrayList<>(list(first.get("evidenceRefs")));evidenceRefs.add("source-A|scope|s1");first.put("evidenceRefs",evidenceRefs);
        List<Object> instructions=new ArrayList<>(list(packet.get("instructions")));instructions.add(obj("sourceTaskId","t1","sourceId","source-A","kind","guidance","sourceObservationId","alias-collision-guidance","evidenceRefs",Collections.singletonList("source-A|scope|s1")));packet.put("instructions",instructions);
        DraftDiscoveryReferences refs=DraftDiscoveryReferences.group(packet);JsonNode initial=refs.encode(packet);
        assertNotEquals("s1",alias(refs,"scope","s1"));assertNotEquals("t1",alias(refs,"task","t1"));
        JsonNode raw=tree(obj("sourceContextRequests",Arrays.asList(request("t1","member","s1"),request("t1","scope","g1")),"nodes",Collections.singletonList(obj("id","s1","sourceTaskId","t1","sourceObservationId","g1"))));String rawBefore=raw.toString();
        JsonNode decoded=refs.decode(raw);List<Object> feedback=refs.requestFeedback();JsonNode expectedFeedback=tree(Arrays.asList(
                obj("requestIndex",0,"sourceTaskId","t1","reason","source_request_kind_mismatch","choices",Collections.singletonList(obj("kind","scope","id","s1"))),
                obj("requestIndex",1,"sourceTaskId","t1","reason","source_request_kind_mismatch","choices",Collections.singletonList(obj("kind","guidance","id","g1")))));
        assertEquals(expectedFeedback,tree(feedback));
        // Match Auto's groupDraft + groupEvidence boundary: failed contexts contain no successful items.
        Map<String,Object> priorDraft=obj("nodes",decoded.path("nodes"));
        Map<String,Object> supplement=obj("currentSourceContext",Collections.singletonList(obj("sourceTaskId",OWNER,"context",obj("sourceId","source-A","items",Collections.emptyList(),"issues",Collections.singletonList(obj("reason","source_context_identity_missing_or_duplicated"))))),"sourceSearchResults",Collections.emptyList(),"fixedContext",obj("fields",Collections.emptyList()),"fixedSearchResults",obj("results",Collections.emptyList()));
        supplement.put("sourceRequestFeedback",feedback);String supplementBefore=JsonUtils.write(supplement),receiptBefore=JsonUtils.write(refs.receipt());
        JsonNode wire=refs.encode(obj("group",packet,"priorDraft",priorDraft,"supplement",supplement));
        assertEquals(expectedFeedback,wire.path("supplement").path("sourceRequestFeedback"));assertEquals(initial,wire.path("group"));assertEquals(receiptBefore,JsonUtils.write(refs.receipt()));
        assertEquals("t1",wire.path("supplement").path("currentSourceContext").path(0).path("sourceTaskId").asText());assertTrue(wire.path("supplement").path("currentSourceContext").path(0).path("context").path("items").isEmpty());
        assertEquals("s1",wire.path("priorDraft").path("nodes").path(0).path("id").asText());assertEquals("t1",wire.path("priorDraft").path("nodes").path(0).path("sourceTaskId").asText());assertEquals(rawBefore,raw.toString());assertEquals(supplementBefore,JsonUtils.write(supplement));
        List<Object> correctedRequests=new ArrayList<>();for(JsonNode row:wire.path("supplement").path("sourceRequestFeedback")){JsonNode choice=row.path("choices").path(0);correctedRequests.add(request(row.path("sourceTaskId").asText(),choice.path("kind").asText(),choice.path("id").asText()));}
        JsonNode corrected=refs.decode(tree(obj("sourceContextRequests",correctedRequests)));assertTrue(refs.requestFeedback().isEmpty());assertEquals(OWNER,corrected.path("sourceContextRequests").path(0).path("sourceTaskId").asText());assertEquals("bill-scope-1",corrected.path("sourceContextRequests").path(0).path("id").asText());assertEquals("guidance-a",corrected.path("sourceContextRequests").path(1).path("id").asText());
        JsonNode denied=refs.decode(tree(obj("sourceContextRequests",Arrays.asList(request("t2","scope","s1"),request("t3","scope","s1"),request("t1","scope",alias(refs,"scope","peer-scope"))))));
        for(JsonNode row:denied.path("sourceContextRequests"))assertTrue(row.path("id").asText().startsWith("unresolved-source-alias:"));assertEquals(3,refs.requestFeedback().size());assertEquals(receiptBefore,JsonUtils.write(refs.receipt()));
    }

    @Test void sameSourceOtherOwnerAndForeignSourceCannotBeRepairedByLeakingTheirChoices(){
        DraftDiscoveryReferences refs=DraftDiscoveryReferences.group(packet());JsonNode decoded=refs.decode(tree(obj("sourceContextRequests",Arrays.asList(request("t1","scope","s5"),request("t1","scope","s6"),request("t1","scope","peer-scope")))));JsonNode feedback=tree(refs.requestFeedback());
        assertEquals(3,feedback.size());for(int i=0;i<3;i++){
            assertTrue(decoded.path("sourceContextRequests").path(i).path("id").asText().startsWith("unresolved-source-alias:"));assertEquals("source_request_reference_unavailable_for_owner",feedback.path(i).path("reason").asText());
            assertEquals(tree(Arrays.asList(obj("kind","scope","id","s1"),obj("kind","scope","id","s2"),obj("kind","scope","id","s3"),obj("kind","scope","id","s4"))),feedback.path(i).path("choices"));
        }
        String text=feedback.toString();for(String forbidden:Arrays.asList(PEER,FOREIGN,"source-B","peer-scope","foreign-scope","s5","s6","g2","g3"))assertFalse(text.contains(forbidden),forbidden);
    }

    @Test void unknownOwnerGetsNoChoicesAndUnknownAliasIsNotInventedOrSilentlyRewritten(){
        DraftDiscoveryReferences refs=DraftDiscoveryReferences.group(packet());JsonNode decoded=refs.decode(tree(obj("sourceContextRequests",Arrays.asList(request("unknown-owner","scope","s1"),request("t1","scope","never-seen"),request("t1","scope",""),request("t1","relationship","r1")))));JsonNode feedback=tree(refs.requestFeedback());
        assertEquals("source_request_owner_unavailable",feedback.path(0).path("reason").asText());assertEquals("",feedback.path(0).path("sourceTaskId").asText());assertTrue(feedback.path(0).path("choices").isEmpty());
        assertEquals("never-seen",decoded.path("sourceContextRequests").path(1).path("id").asText());assertEquals("source_request_reference_unknown",feedback.path(1).path("reason").asText());
        assertEquals("source_request_arguments_invalid",feedback.path(2).path("reason").asText());assertEquals("source_request_arguments_invalid",feedback.path(3).path("reason").asText());
        for(JsonNode row:feedback)for(JsonNode choice:row.path("choices"))assertTrue(Arrays.asList("member","scope","guidance","condition").contains(choice.path("kind").asText()));
    }

    @Test void feedbackIsLimitedToExecutableRequestBudgetAndDefensivelyCopied(){
        DraftDiscoveryReferences refs=DraftDiscoveryReferences.group(packet());List<Object> requests=new ArrayList<>();for(int i=0;i<7;i++)requests.add(request("t1","scope","missing-"+i));refs.decode(tree(obj("sourceContextRequests",requests)));
        List<Object> feedback=refs.requestFeedback();assertEquals(4,feedback.size());for(Object item:feedback)assertTrue(list(map(item).get("choices")).size()<=4);
        assertThrows(UnsupportedOperationException.class,()->map(feedback.get(0)).put("sourceTaskId","t2"));assertThrows(UnsupportedOperationException.class,()->list(map(feedback.get(0)).get("choices")).clear());
        feedback.clear();assertEquals(4,refs.requestFeedback().size());refs.decode(tree(obj("notes",Collections.singletonList("No new request"))));assertTrue(refs.requestFeedback().isEmpty());
    }

    @Test void supplementalMemberNavigationAndSearchPreviewRemainLocalToTheirRecipient(){
        Map<String,Object> packet=packet();DraftDiscoveryReferences refs=DraftDiscoveryReferences.group(packet);
        Map<String,Object> supplement=obj("currentSourceContext",Collections.singletonList(obj("sourceTaskId",OWNER,"context",obj("sourceId","source-A","items",Collections.singletonList(obj("kind","member","id","new-member","adjacentMembers",Collections.singletonList(obj("id","next-member","text","navigation"))))))),"sourceSearchResults",Collections.singletonList(obj("sourceTaskId",OWNER,"search",obj("sourceId","source-A","results",Collections.singletonList(obj("kind","scope","id","found-scope","previewOnly",true,"preview","navigation only"))))));
        JsonNode follow=refs.encode(obj("group",packet,"supplement",supplement));String member=follow.path("supplement").path("currentSourceContext").path(0).path("context").path("items").path(0).path("id").asText(),adjacent=follow.path("supplement").path("currentSourceContext").path(0).path("context").path("items").path(0).path("adjacentMembers").path(0).path("id").asText(),scope=follow.path("supplement").path("sourceSearchResults").path(0).path("search").path("results").path(0).path("id").asText();
        JsonNode accepted=refs.decode(tree(obj("sourceContextRequests",Arrays.asList(request("t1","member",member),request("t1","member",adjacent),request("t1","scope",scope)))));assertTrue(refs.requestFeedback().isEmpty());assertEquals("new-member",accepted.path("sourceContextRequests").path(0).path("id").asText());assertEquals("next-member",accepted.path("sourceContextRequests").path(1).path("id").asText());assertEquals("found-scope",accepted.path("sourceContextRequests").path(2).path("id").asText());
        JsonNode denied=refs.decode(tree(obj("sourceContextRequests",Arrays.asList(request("t2","member",member),request("t2","scope",scope)))));assertEquals(2,refs.requestFeedback().size());for(JsonNode row:denied.path("sourceContextRequests"))assertTrue(row.path("id").asText().startsWith("unresolved-source-alias:"));
        assertEquals(tree(packet).path("instructions").path(0).path("evidenceRefs").size(),follow.path("group").path("instructions").path(0).path("evidenceRefs").size());assertTrue(follow.path("supplement").path("sourceSearchResults").path(0).path("search").path("results").path(0).path("previewOnly").asBoolean());
    }

    @Test void mismatchedSupplementSourceDoesNotRegisterNewAliasesOrGiveAnotherOwnerNavigation(){
        Map<String,Object> packet=packet();DraftDiscoveryReferences refs=DraftDiscoveryReferences.group(packet);String receipt=JsonUtils.write(refs.receipt());
        refs.encode(obj("group",packet,"supplement",obj("currentSourceContext",Collections.singletonList(obj("sourceTaskId",OWNER,"context",obj("sourceId","source-B","items",Collections.singletonList(obj("kind","member","id","foreign-secret"))))))));assertEquals(receipt,JsonUtils.write(refs.receipt()));
        JsonNode denied=refs.decode(tree(obj("sourceContextRequests",Collections.singletonList(request("t1","member","foreign-secret")))));assertEquals("foreign-secret",denied.path("sourceContextRequests").path(0).path("id").asText());assertEquals("source_request_reference_unknown",tree(refs.requestFeedback()).path(0).path("reason").asText());assertFalse(JsonUtils.write(refs.requestFeedback()).contains("foreign-secret"));
    }

    @Test void nestedCurrentEvidenceOffersMemberNavigationButDoesNotChangeGraphAuthority(){
        DraftDiscoveryReferences refs=DraftDiscoveryReferences.group(packet());String member=alias(refs,"member","scope-member"),relationMember=alias(refs,"member","relation-member");JsonNode decoded=refs.decode(tree(obj("sourceContextRequests",Arrays.asList(request("t1","member",member),request("t1","member",relationMember)),"scopeRules",Collections.singletonList(obj("id","business-rule","sourceTaskId","t2","scopeId","s1")))));
        assertTrue(refs.requestFeedback().isEmpty());assertEquals("scope-member",decoded.path("sourceContextRequests").path(0).path("id").asText());assertEquals("relation-member",decoded.path("sourceContextRequests").path(1).path("id").asText());assertEquals("business-rule",decoded.path("scopeRules").path(0).path("id").asText());
        // The reference layer still only translates graph IDs; downstream evidence checks own execution authority.
        assertEquals("bill-scope-1",decoded.path("scopeRules").path(0).path("scopeId").asText());
    }

    private static JsonNode tree(Object value){return JsonUtils.mapper().valueToTree(value);}
    private static Map<String,Object> request(String owner,String kind,String id){return obj("sourceTaskId",owner,"kind",kind,"id",id);}
    private static String alias(DraftDiscoveryReferences refs,String kind,String id){for(Object item:list(refs.receipt().get("aliases"))){Map<String,Object> row=map(item);if(kind.equals(row.get("kind"))&&id.equals(row.get("id")))return text(row.get("alias"));}throw new AssertionError("Fixture reference absent");}
    private static Map<String,Object> packet(){
        List<Object> refs=new ArrayList<>(),scopes=new ArrayList<>(),evidence=new ArrayList<>();for(int i=1;i<=4;i++){String id="bill-scope-"+i;refs.add("source-A|scope|"+id);scopes.add(id);Map<String,Object> row=obj("sourceId","source-A","kind","scope","id",id);if(i==1)row.put("orderedMemberIds",Collections.singletonList("scope-member"));evidence.add(row);}refs.add("source-A|relationship|relationship-a");
        evidence.add(obj("sourceId","source-A","kind","relationship","id","relationship-a","memberId","relation-member"));evidence.add(obj("sourceId","source-A","kind","scope","id","peer-scope","orderedMemberIds",Collections.singletonList("peer-member")));evidence.add(obj("sourceId","source-B","kind","scope","id","foreign-scope"));
        Map<String,Object> first=obj("sourceTaskId",OWNER,"sourceId","source-A","kind","guidance","sourceObservationId","guidance-a","evidenceRefs",refs,"scopeObligationIds",scopes);
        Map<String,Object> peer=obj("sourceTaskId",PEER,"sourceId","source-A","kind","guidance","sourceObservationId","guidance-peer","evidenceRefs",Collections.singletonList("source-A|scope|peer-scope"));
        Map<String,Object> foreign=obj("sourceTaskId",FOREIGN,"sourceId","source-B","kind","guidance","sourceObservationId","guidance-foreign","evidenceRefs",Collections.singletonList("source-B|scope|foreign-scope"));
        return obj("instructions",Arrays.asList(first,peer,foreign),"sharedEvidence",evidence);
    }
}
