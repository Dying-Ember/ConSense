package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Synthetic bound Word evidence through Auto; no product API, real model or database calls. */
class DraftScopeCoverageOwnerIsolationTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a")),PART=String.join("",Collections.nCopies(64,"b"));

    @Test void markerCheckDoesNotInvalidateIndependentGuidanceActionsOrBorrowTheirCompletion() {
        Map<String,Object> receipt=process(false,true,1);
        Map<String,Object> guidance=child(receipt,0),marker=child(receipt,1);
        assertEquals("completed",guidance.get("status"));
        assertEquals("covered",coverage(guidance).get("status"));
        assertTrue(list(coverage(guidance).get("missingScopeIds")).isEmpty());
        assertEquals("partial",marker.get("status"));
        assertEquals(Collections.singletonList("scope"),coverage(marker).get("missingScopeIds"));
        assertTrue(list(coverage(marker).get("unrepresentedScopeIds")).isEmpty(),"A retained check is represented, but remains unresolved");
        assertTrue(list(coverage(marker).get("sharedFromTaskIds")).isEmpty(),"A marker's own check cannot borrow another owner's completion");
        Map<String,Object> target=map(list(map(list(marker.get("nodes")).get(0)).get("editTargets")).get(0));
        assertEquals("check",target.get("action"));
        assertEquals("partial",receipt.get("status"));
        assertTrue(list(receipt.get("evidenceRounds")).isEmpty());
    }

    @Test void markerWithoutItsOwnCheckStillInheritsTheExactGuidanceDisposition() {
        Map<String,Object> receipt=process(false,false,1),guidance=child(receipt,0),marker=child(receipt,1);
        assertEquals("completed",receipt.get("status"));
        assertEquals("covered",coverage(guidance).get("status"));
        assertEquals("completed",marker.get("status"));
        assertEquals("shared_exact",coverage(marker).get("status"));
        assertEquals(Collections.singletonList(guidance.get("taskId")),coverage(marker).get("sharedFromTaskIds"));
        assertTrue(list(coverage(marker).get("missingScopeIds")).isEmpty());
    }

    @Test void guidanceWithBothActionsAndCheckCannotDonateCompletionToItsMarker() {
        Map<String,Object> receipt=process(true,false,2),guidance=child(receipt,0),marker=child(receipt,1);
        assertEquals("partial",guidance.get("status"));
        assertEquals(Collections.singletonList("scope"),coverage(guidance).get("missingScopeIds"));
        assertTrue(list(coverage(guidance).get("unrepresentedScopeIds")).isEmpty());
        assertEquals("partial",marker.get("status"));
        assertEquals(Collections.singletonList("scope"),coverage(marker).get("missingScopeIds"));
        assertEquals(Collections.singletonList("scope"),coverage(marker).get("unrepresentedScopeIds"));
        assertTrue(list(coverage(marker).get("sharedFromTaskIds")).isEmpty(),"An unresolved donor cannot make another owner complete");
        assertEquals(1,list(receipt.get("evidenceRounds")).size(),"Only the unrepresented marker receives one bounded repair");
    }

    private static Map<String,Object> process(boolean guidanceCheck,boolean markerCheck,int expectedCalls) {
        ObjectNode reading=reading();DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(reading));
        assertEquals(2,plan.tasks().size());assertEquals("guidance",plan.tasks().get(0).get("itemKind"));assertEquals("marker",plan.tasks().get(1).get("itemKind"));
        assertTrue(DraftScopeDutyInheritance.exactMarkerChild(reading,plan.tasks().get(0),plan.tasks().get(1),"scope"),"Fixture must exercise the real inheritance route");
        List<Map<String,Object>> groups=DraftAutomaticDiscovery.planGroups(plan);assertEquals(1,groups.size());int[] calls={0};
        Map<String,Object> receipt=DraftAutomaticDiscovery.processGroup((system,user)->{
            assertTrue(++calls[0]<=expectedCalls,"Do not repair a retained check indefinitely");
            JsonNode packet=JsonUtils.parse(user),wire=packet.has("group")?packet.path("group"):packet;
            String guidance=wire.path("instructions").path(0).path("sourceTaskId").asText(),marker=wire.path("instructions").path(1).path("sourceTaskId").asText();
            String scope=wire.path("instructions").path(0).path("scopeObligationIds").path(0).asText(),alias=alias(wire);
            List<Object> rules=new ArrayList<>();rules.add(rule("keep_facade",guidance,scope,alias,true,"keep"));rules.add(rule("delete_facade",guidance,scope,alias,false,"delete"));
            if(guidanceCheck)rules.add(obj("id","guidance_check","sourceTaskId",guidance,"scopeId",scope,"action","check"));
            if(markerCheck)rules.add(obj("id","marker_check","sourceTaskId",marker,"scopeId",scope,"action","check"));
            return JsonUtils.write(obj("nodes",Collections.emptyList(),"requirements",Collections.emptyList(),"scopeRules",rules,"taskOutcomes",Arrays.asList(obj("sourceTaskId",guidance,"status","identified"),obj("sourceTaskId",marker,"status",markerCheck?"identified":"no_requirement")),"notes",Collections.emptyList()));
        },plan,groups.get(0));
        assertEquals(expectedCalls,calls[0]);return receipt;
    }

    private static Map<String,Object> rule(String id,String owner,String scope,String input,boolean value,String action){return obj("id",id,"sourceTaskId",owner,"scopeId",scope,"when",obj("mode","all","clauses",Collections.singletonList(obj("inputId",input,"operator","eq","value",value))),"action",action);}
    private static String alias(JsonNode wire){for(JsonNode root:wire.path("knownRoots"))if("precastFacadeTenderBasis".equals(root.path("baselineKey").asText()))return root.path("id").asText();throw new AssertionError("Current facade root missing");}
    private static Map<String,Object> child(Map<String,Object> receipt,int index){return map(list(receipt.get("taskReceipts")).get(index));}
    private static Map<String,Object> coverage(Map<String,Object> child){return map(child.get("scopeCoverage"));}

    private static ObjectNode reading() {
        String instruction=DraftBusinessRules.standardParagraph("NTT",96),body=DraftBusinessRules.standardParagraph("NTT",75);
        assertFalse(instruction.isEmpty());assertTrue(body.startsWith("*"));
        ObjectNode reading=JsonUtils.mapper().createObjectNode().put("id","1").put("sourceDocumentId",1L).put("fileKey","NTT").put("format","docx").put("sourceSha256",SHA).put("evidenceStatus","bound").put("analysisRevision","owner-isolation-test").put("analysisInputSha256",SHA).put("analysisSha256",SHA);
        for(String collection:Arrays.asList("guidanceNotes","conditions","sourceMembers","paragraphs"))reading.putArray(collection);
        member(reading,"note-member",instruction);member(reading,"body-member",body);member(reading,"next-member","(f) Next peer clause.");
        ObjectNode note=reading.withArray("guidanceNotes").addObject().put("id","note").put("kind","editorial_parenthetical_candidate").put("text",instruction).put("captureComplete",true).put("sourceSyntaxComplete",true).put("scopeComplete",false);
        note.putArray("orderedMemberIds").add("note-member");note.putArray("memberRanges").add(range(reading,"note-member",instruction.length()));
        ObjectNode condition=reading.withArray("conditions").addObject().put("id","condition").put("guidanceNoteId","note").put("text",instruction).put("status","literal_source_condition_not_project_boolean");condition.set("memberRanges",note.path("memberRanges").deepCopy());condition.putArray("referenceCandidates");
        ObjectNode scope=reading.putArray("groups").addObject().put("id","scope").put("kind","source_unit_instruction_scope").put("guidanceNoteId","note").put("text",body).put("captureComplete",true).put("scopeComplete",true);
        scope.putArray("orderedMemberIds").add("body-member");scope.putArray("memberRanges").add(range(reading,"body-member",body.length()));scope.putObject("startBoundary").put("kind","explicit_native_clause_or_subitem_label").put("memberId","body-member");scope.putObject("endBoundary").put("kind","next_native_peer_or_parent_label").put("memberId","next-member");
        ObjectNode marker=reading.putArray("markerCandidates").addObject().put("id","marker").put("memberId","body-member").put("symbol","*").put("channel","text").put("start",0).put("end",1).put("scopeComplete",true).put("status","source_scope_resolved_project_condition_unknown").put("disposition","conditional_source_target");
        marker.putArray("targetGroupIds").add("scope");marker.putArray("guidanceNoteIds").add("note");marker.putArray("conditionIds").add("condition");marker.putObject("localSentenceObservation").put("text",body).put("start",0).put("end",body.length());return reading;
    }

    private static void member(ObjectNode reading,String id,String value){ObjectNode member=reading.withArray("sourceMembers").addObject().put("id",id).put("identityStatus","source_bound");member.putObject("textObservation").put("text",value);member.putObject("sourcePosition").put("kind","docx_native").put("sourceSha256",SHA).put("partSha256",PART).put("partName","word/document.xml").put("nodePath","/current/"+id);ObjectNode paragraph=reading.withArray("paragraphs").addObject().put("id","p-"+id).put("text",value).put("sourceSha256",SHA).put("paragraphSha256",DraftAdoption.hash(value));paragraph.set("sourcePosition",member.path("sourcePosition").deepCopy());}
    private static ObjectNode range(ObjectNode reading,String id,int length){ObjectNode range=JsonUtils.mapper().createObjectNode().put("memberId",id).put("channel","text").put("start",0).put("end",length);for(JsonNode member:reading.path("sourceMembers"))if(id.equals(member.path("id").asText()))range.set("sourcePosition",member.path("sourcePosition").deepCopy());return range;}
}
