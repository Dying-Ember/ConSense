package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Synthetic source-only contracts; no live model, API, database or browser. */
class DraftGroupPendingProtocolTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a")),NOTE="Review the current alternative.",BODY="Complete current alternative.";

    @Test void pureEvidenceMergingDoesNotInventEmptyBusinessArrays(){
        JsonNode first=JsonUtils.mapper().valueToTree(obj("sourceSearchRequests",Collections.singletonList(obj("sourceTaskId","t1","query",BODY)))),next=JsonUtils.mapper().valueToTree(obj("sourceContextRequests",Collections.singletonList(obj("sourceTaskId","t1","kind","scope","id","s1"))));
        List<Object> issues=new ArrayList<>();JsonNode merged=DraftAutomaticDiscovery.mergeGroupDraft(first,next,issues,new ArrayList<>(),Collections.emptyList());assertTrue(issues.isEmpty());assertFalse(merged.has("taskOutcomes"));assertFalse(merged.has("nodes"));assertFalse(merged.has("requirements"));assertTrue(DraftDiscoveryProbe.evidenceOnly(merged));
        ((ObjectNode)next).putArray("taskOutcomes");merged=DraftAutomaticDiscovery.mergeGroupDraft(first,next,issues,new ArrayList<>(),Collections.emptyList());assertTrue(merged.has("taskOutcomes"));assertFalse(DraftDiscoveryProbe.evidenceOnly(merged),"Explicit business fields must not be erased to relabel a bad schema as evidence only");
    }

    @Test void twoEvidenceOnlyRoundsCanReachTheThirdAnswerWithoutSchemaFailure(){
        DraftAutomaticDiscovery.Plan plan=plan(true,true,true,false);int[] calls={0};
        Map<String,Object> result=DraftAutomaticDiscovery.processGroup((system,user)->{
            JsonNode packet=JsonUtils.parse(user),group=packet.has("group")?packet.path("group"):packet,instruction=group.path("instructions").path(0);String owner=instruction.path("sourceTaskId").asText(),scope=instruction.path("scopeObligationIds").path(0).asText();
            int call=++calls[0];if(call==1)return JsonUtils.write(obj("sourceContextRequests",Collections.singletonList(obj("sourceTaskId",owner,"kind","scope","id",scope))));
            if(call==2)return JsonUtils.write(obj("sourceSearchRequests",Collections.singletonList(obj("sourceTaskId",owner,"query",BODY))));
            assertEquals(3,call);return response(plan,"keep",false);
        },plan,group(plan),true);
        assertEquals(3,calls[0]);assertTrue(list(result.get("stopReasons")).isEmpty());assertEquals("completed",result.get("status"));assertEquals(2,list(result.get("evidenceRounds")).size());
    }

    @Test void anIncompleteScopeRetainsItsCheckAsNonfatalPartialWithoutAnotherCall(){
        DraftAutomaticDiscovery.Plan plan=plan(false,true,true);int[] calls={0};Map<String,Object> result=DraftAutomaticDiscovery.processGroup((system,user)->{calls[0]++;return response(plan,"check",false);},plan,group(plan),true);
        assertEquals(1,calls[0]);assertEquals("partial",result.get("status"));assertTrue(list(result.get("stopReasons")).isEmpty());Map<String,Object> child=child(result,0),target=target(result);
        assertEquals("partial",child.get("status"));assertTrue(list(child.get("issues")).stream().map(DraftReviewGraph::map).anyMatch(issue->"source_scope_treatment_unresolved".equals(issue.get("reason"))));assertEquals("check",target.get("action"));assertEquals(true,target.get("needsRebind"));assertEquals(BODY,target.get("text"));
    }

    @Test void duplicatePendingDutySuppressesRepairWithoutClaimingCoverageAndDifferentDutiesStillRepair(){
        for(String variant:Arrays.asList("exact","different-directive","different-scope")){
            DraftAutomaticDiscovery.Plan plan=plan(true,!"different-scope".equals(variant),!"different-directive".equals(variant));String raw=response(plan,"keep",true);int[] calls={0};Map<String,Object> result=DraftAutomaticDiscovery.processGroup((system,user)->{calls[0]++;return raw;},plan,group(plan),false);
            assertEquals("exact".equals(variant)?1:2,calls[0],variant);assertEquals(raw,result.get("rawResponse"));assertEquals("Wrong quoted wording.",target(result).get("text"));assertEquals("EXPLICIT_SCOPE_QUOTE_MISMATCH",target(result).get("scopeQuoteIssue"));assertEquals("check",target(result).get("action"));
            for(int i=0;i<2;i++){Map<String,Object> coverage=map(child(result,i).get("scopeCoverage"));assertEquals("unresolved",coverage.get("status"));assertFalse(list(coverage.get("missingScopeIds")).isEmpty());assertTrue(list(coverage.get("sharedFromTaskIds")).isEmpty());}
            assertEquals("exact".equals(variant),list(map(child(result,1).get("scopeCoverage")).get("unrepresentedScopeIds")).isEmpty(),variant);
        }
    }

    private static String response(DraftAutomaticDiscovery.Plan plan,String action,boolean wrong){
        String owner=text(plan.tasks().get(0).get("taskId"));Map<String,Object> effect=obj("scopeId","scope-a","action",action);if(wrong)effect.put("quote","Wrong quoted wording.");
        Map<String,Object> node=obj("id","fact","sourceTaskId",owner,"type","boolean","role","input","nameEn","Current alternative","sourceQuote",NOTE,"sourceObservationId","note-a","options",Collections.emptyList(),"branches",Collections.emptyList(),"effects",Collections.singletonList(effect));List<Object> outcomes=new ArrayList<>();for(Map<String,Object> task:plan.tasks())outcomes.add(obj("sourceTaskId",task.get("taskId"),"status",owner.equals(task.get("taskId"))?"identified":"unresolved"));
        return JsonUtils.write(obj("nodes",Collections.singletonList(node),"requirements",Collections.singletonList(obj("id","r","sourceTaskId",owner,"sourceQuote",NOTE,"sourceObservationId","note-a","kind","input","nodeIds",Collections.singletonList("fact"))),"taskOutcomes",outcomes));
    }
    private static DraftAutomaticDiscovery.Plan plan(boolean complete,boolean shared,boolean duplicate){return plan(complete,shared,duplicate,complete);}
    private static DraftAutomaticDiscovery.Plan plan(boolean complete,boolean shared,boolean duplicate,boolean second){
        ObjectNode source=JsonUtils.mapper().createObjectNode().put("id","1").put("sourceDocumentId",1).put("fileKey","SCT").put("format","docx").put("sourceSha256",SHA).put("evidenceStatus","bound");for(String key:Arrays.asList("guidanceNotes","groups","conditions","sourceMembers","paragraphs","markerCandidates"))source.putArray(key);
        source.withArray("guidanceNotes").addObject().put("id","note-a").put("text",NOTE).put("captureComplete",true);source.withArray("conditions").addObject().put("id","common").put("text","The current fact controls this alternative.");member(source,"body-a",BODY);ObjectNode scope=scope(source,"scope-a","body-a",complete);scope.putArray("guidanceNoteIds").add("note-a");
        if(second){source.withArray("guidanceNotes").addObject().put("id","note-b").put("text",duplicate?NOTE:"A different instruction for the current alternative.").put("captureComplete",true);if(shared)scope.withArray("guidanceNoteIds").add("note-b");else{member(source,"body-b",BODY);scope(source,"scope-b","body-b",true).putArray("guidanceNoteIds").add("note-b");}}
        return DraftAutomaticDiscovery.plan(Collections.singletonList(source));
    }
    private static ObjectNode scope(ObjectNode source,String id,String member,boolean complete){ObjectNode scope=source.withArray("groups").addObject().put("id",id).put("scopeComplete",complete);scope.putArray("conditionIds").add("common");scope.putArray("memberRanges").addObject().put("memberId",member).put("channel","text").put("start",0).put("end",BODY.length());return scope;}
    private static void member(ObjectNode source,String id,String text){ObjectNode member=source.withArray("sourceMembers").addObject().put("id",id).put("identityStatus","source_bound");member.putObject("sourcePosition").put("sourceSha256",SHA).put("partName","word/document.xml").put("nodePath","/"+id);member.putObject("textObservation").put("text",text);ObjectNode paragraph=source.withArray("paragraphs").addObject().put("id","p-"+id).put("text",text).put("sourceSha256",SHA).put("paragraphSha256",SHA);paragraph.set("sourcePosition",member.path("sourcePosition").deepCopy());}
    private static Map<String,Object> group(DraftAutomaticDiscovery.Plan plan){List<Map<String,Object>> groups=DraftAutomaticDiscovery.planGroups(plan);assertEquals(1,groups.size());return groups.get(0);}
    private static Map<String,Object> child(Map<String,Object> receipt,int index){return map(list(receipt.get("taskReceipts")).get(index));}
    private static Map<String,Object> target(Map<String,Object> receipt){return map(list(map(list(child(receipt,0).get("nodes")).get(0)).get("editTargets")).get(0));}
}
