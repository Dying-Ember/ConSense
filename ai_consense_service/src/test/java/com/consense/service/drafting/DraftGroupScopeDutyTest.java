package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Saved synthetic source fixtures and deterministic responses only; no real model or product calls. */
class DraftGroupScopeDutyTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a")),FIRST="Review the shared issue format.",SECOND="Apply that format to the other alternative.",BODY="Complete current alternative.";

    @Test void exactDuplicateDirectivesShareOneValidScopeDispositionWithoutAnotherCall() {
        DraftAutomaticDiscovery.Plan plan=plan(true,false,true);List<Map<String,Object>> tasks=plan.tasks();String raw=response(tasks,true,false,false);int[] calls={0};
        Map<String,Object> result=DraftAutomaticDiscovery.processGroup((system,user)->{assertTrue(system.contains("branch scopeId is ONLY a local list node ID")&&system.contains("scopeMode is each|any|all|none")&&system.contains("effects.scopeId, not branch.scopeId")&&system.contains("effects.action, never in scopeMode")&&system.contains("resultValue must match its node type and declared choice options")&&system.contains("input, decision and content receive external adopted answers")&&system.contains("may test their own adopted answer")&&system.contains("do not create content boolean questions merely to preserve their text")&&system.contains("reference or verification with empty nodeIds")&&system.contains("must never reference itself")&&system.contains("never a clauses inputId")&&system.contains("neither case creates another independently answerable manual question")&&system.contains("all parent/local conditions"));calls[0]++;return raw;},plan,group(plan));
        assertEquals(1,calls[0]);assertEquals("completed",result.get("status"));assertEquals(1,list(child(result,0).get("nodes")).size());assertTrue(list(child(result,1).get("nodes")).isEmpty());assertEquals("completed",child(result,1).get("status"));assertEquals("shared_exact",map(child(result,1).get("scopeCoverage")).get("status"));assertTrue(list(child(result,1).get("issues")).isEmpty());
    }

    @Test void twoDifferentScopesRemainPartialWhenNoNewInputIsMistakenForNoDuty() {
        DraftAutomaticDiscovery.Plan plan=plan(false,false,false);String raw=response(plan.tasks(),false,false,false);int[] calls={0};
        Map<String,Object> result=DraftAutomaticDiscovery.processGroup((system,user)->{calls[0]++;return raw;},plan,group(plan));
        assertEquals(2,calls[0],"Only one specific repair is permitted; an unchanged result is not retried indefinitely");assertEquals("partial",result.get("status"));assertEquals(raw,result.get("rawResponse"));
        for(int i=0;i<2;i++){assertEquals("partial",child(result,i).get("status"));assertFalse(list(map(child(result,i).get("scopeCoverage")).get("missingScopeIds")).isEmpty());}
        Map<String,Object> node=map(list(child(result,0).get("nodes")).get(0));assertTrue(list(node.get("editTargets")).isEmpty(),"The harness does not invent missing amendments");assertEquals(1,list(result.get("evidenceRounds")).size());assertEquals("scope_disposition_repair",map(list(result.get("evidenceRounds")).get(0)).get("kind"));
    }

    @Test void oneBoundedRepairAddsScopeWorkToTheSameRootAndPreservesOriginalRaw() {
        DraftAutomaticDiscovery.Plan plan=plan(false,false,false);String first=response(plan.tasks(),false,false,false),second=response(plan.tasks(),true,true,false);int[] calls={0};
        Map<String,Object> result=DraftAutomaticDiscovery.processGroup((system,user)->{calls[0]++;if(calls[0]==1)return first;JsonNode feedback=JsonUtils.parse(user);assertFalse(feedback.path("supplement").path("repairRequests").isEmpty());assertTrue(feedback.path("supplement").path("repairRequests").path(0).path("sourceTaskId").asText().matches("t[0-9]+"));assertEquals("format",feedback.path("priorDraft").path("nodes").path(0).path("id").asText());assertEquals("t1",feedback.path("priorDraft").path("nodes").path(0).path("sourceTaskId").asText());assertTrue(system.contains("primary owners"));return second;},plan,group(plan));
        assertEquals(2,calls[0]);assertTrue(calls[0]<=DraftAutomaticDiscovery.GROUP_MAX_CALLS);assertEquals("completed",result.get("status"));assertEquals(first,result.get("rawResponse"));assertEquals(second,map(list(result.get("evidenceRounds")).get(0)).get("rawResponse"));
        assertEquals(1,list(child(result,0).get("nodes")).size());assertTrue(list(child(result,1).get("nodes")).isEmpty());Map<String,Object> node=map(list(child(result,0).get("nodes")).get(0));assertEquals("format",node.get("modelLocalId"));assertEquals(2,list(node.get("editTargets")).size());assertEquals("completed",child(result,1).get("status"));
    }

    @Test void overlappingRangesCannotBorrowTheOtherScopesDisposition() {
        DraftAutomaticDiscovery.Plan plan=plan(false,true,true);String raw=response(plan.tasks(),true,false,false);int[] calls={0};Map<String,Object> result=DraftAutomaticDiscovery.processGroup((system,user)->{calls[0]++;return raw;},plan,group(plan));
        assertEquals(2,calls[0]);assertEquals("completed",child(result,0).get("status"));assertEquals("partial",child(result,1).get("status"));assertEquals(Collections.singletonList("scope-b"),map(child(result,1).get("scopeCoverage")).get("missingScopeIds"));assertTrue(list(map(child(result,1).get("scopeCoverage")).get("sharedFromTaskIds")).isEmpty());
    }

    @Test void anExplicitWrongQuoteStaysVisibleInsteadOfBeingWashedThroughTheRepair() {
        DraftAutomaticDiscovery.Plan plan=plan(true,false,true);String raw=response(plan.tasks(),true,false,true);int[] calls={0};Map<String,Object> result=DraftAutomaticDiscovery.processGroup((system,user)->{calls[0]++;return raw;},plan,group(plan));
        assertEquals(1,calls[0],"A retained erroneous target is not treated as an absent target for this repair");assertEquals("partial",result.get("status"));Map<String,Object> target=map(list(map(list(child(result,0).get("nodes")).get(0)).get("editTargets")).get(0));assertEquals("Wrong quoted wording.",target.get("text"));assertEquals("EXPLICIT_SCOPE_QUOTE_MISMATCH",target.get("scopeQuoteIssue"));assertEquals("check",target.get("action"));assertEquals(raw,result.get("rawResponse"));
    }

    @Test void aGeneralInputReferenceCannotReplaceTheOtherTasksDifferentScopeDirective() {
        DraftAutomaticDiscovery.Plan plan=plan(true,false,false);List<Map<String,Object>> tasks=plan.tasks();Map<String,Object> proposed=JsonUtils.readMap(response(tasks,true,false,false));String second=text(tasks.get(1).get("taskId"));
        ((List<Object>)proposed.get("requirements")).add(obj("id","r2","sourceTaskId",second,"sourceQuote",SECOND,"sourceObservationId","note-b","kind","input","nodeIds",Collections.singletonList("format")));map(list(proposed.get("taskOutcomes")).get(1)).put("status","identified");String raw=JsonUtils.write(proposed);int[] calls={0};
        Map<String,Object> result=DraftAutomaticDiscovery.processGroup((system,user)->{calls[0]++;return raw;},plan,group(plan));assertEquals(2,calls[0]);assertEquals("partial",child(result,1).get("status"));assertEquals(Collections.singletonList("scope-a"),map(child(result,1).get("scopeCoverage")).get("missingScopeIds"));assertTrue(list(map(child(result,1).get("scopeCoverage")).get("sharedFromTaskIds")).isEmpty());
    }

    @Test void aSpecificScopeRepairCannotEraseAnUnrelatedOldUnresolvedOutcome() {
        ObjectNode source=source(false,false,false);source.withArray("guidanceNotes").addObject().put("id","note-c").put("text","A separate meaning remains unresolved.").put("captureComplete",true).putArray("conditionIds").add("common");DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));List<Map<String,Object>> tasks=plan.tasks();assertEquals(3,tasks.size());String third=text(tasks.get(2).get("taskId"));
        Map<String,Object> initial=JsonUtils.readMap(response(tasks,false,false,false));((List<Object>)initial.get("taskOutcomes")).add(obj("sourceTaskId",third,"status","unresolved","reason","Independent meaning still requires review"));String first=JsonUtils.write(initial);
        Map<String,Object> repaired=JsonUtils.readMap(response(tasks,true,true,false));((List<Object>)repaired.get("taskOutcomes")).add(obj("sourceTaskId",third,"status","no_requirement","reason","No extra input"));String next=JsonUtils.write(repaired);int[] calls={0};
        Map<String,Object> result=DraftAutomaticDiscovery.processGroup((system,user)->++calls[0]==1?first:next,plan,group(plan));assertEquals(2,calls[0]);assertEquals("completed",child(result,0).get("status"));assertEquals("completed",child(result,1).get("status"));assertEquals("partial",child(result,2).get("status"));assertTrue(JsonUtils.write(child(result,2).get("issues")).contains("Independent meaning still requires review"));assertEquals("unresolved",JsonUtils.mapper().valueToTree(result.get("retainedResponse")).path("taskOutcomes").path(2).path("status").asText());
    }

    @Test void aCorrectScopeAliasDoesNotAuthorizeAnotherTasksExclusiveScopeInTheSameSource() {
        DraftAutomaticDiscovery.Plan plan=plan(false,false,false);int[] calls={0};String[] raw={null};
        Map<String,Object> result=DraftAutomaticDiscovery.processGroup((system,user)->{calls[0]++;JsonNode wire=JsonUtils.parse(user),first=wire.path("instructions").path(0),second=wire.path("instructions").path(1);String owner=first.path("sourceTaskId").asText(),foreignScope=second.path("scopeObligationIds").path(0).asText();assertEquals(first.path("sourceId"),second.path("sourceId"));assertTrue(foreignScope.matches("s[0-9]+"));assertNotEquals(first.path("scopeObligationIds").path(0).asText(),foreignScope);
            Map<String,Object> node=obj("id","format","sourceTaskId",owner,"sourceObservationId",first.path("sourceObservationId").asText(),"sourceQuote",FIRST,"type","boolean","role","input","nameEn","Issue format","branches",Collections.emptyList(),"options",Collections.emptyList(),"effects",Collections.singletonList(obj("sourceTaskId",owner,"scopeId",foreignScope,"action","delete")));
            raw[0]=JsonUtils.write(obj("nodes",Collections.singletonList(node),"requirements",Collections.singletonList(obj("id","r","sourceTaskId",owner,"sourceObservationId",first.path("sourceObservationId").asText(),"sourceQuote",FIRST,"kind","input","nodeIds",Collections.singletonList("format"))),"taskOutcomes",Arrays.asList(obj("sourceTaskId",owner,"status","identified"),obj("sourceTaskId",second.path("sourceTaskId").asText(),"status","unresolved"))));return raw[0];},plan,group(plan),true);
        assertEquals(1,calls[0]);assertEquals(raw[0],result.get("rawResponse"));assertEquals("partial",result.get("status"));assertTrue(list(result.get("stopReasons")).contains("effect_scope_not_supplied_to_owner"));Map<String,Object> target=map(list(map(list(child(result,0).get("nodes")).get(0)).get("editTargets")).get(0));assertEquals("check",target.get("action"));assertEquals(true,target.get("needsRebind"));assertFalse(target.containsKey("sourceScopeObservation"));
    }

    private static Map<String,Object> child(Map<String,Object> result,int index){return map(list(result.get("taskReceipts")).get(index));}
    private static Map<String,Object> group(DraftAutomaticDiscovery.Plan plan){List<Map<String,Object>> groups=DraftAutomaticDiscovery.planGroups(plan);assertEquals(1,groups.size());return groups.get(0);}
    private static DraftAutomaticDiscovery.Plan plan(boolean shared,boolean overlap,boolean duplicate){return DraftAutomaticDiscovery.plan(Collections.singletonList(source(shared,overlap,duplicate)));}
    private static String response(List<Map<String,Object>> tasks,boolean firstEffect,boolean bothEffects,boolean badQuote) {
        String first=text(tasks.get(0).get("taskId")),second=text(tasks.get(1).get("taskId"));List<Object> effects=new ArrayList<>();if(firstEffect){Map<String,Object> effect=obj("sourceTaskId",first,"scopeId","scope-a","action","keep");if(badQuote)effect.put("quote","Wrong quoted wording.");effects.add(effect);}if(bothEffects)effects.add(obj("sourceTaskId",second,"scopeId","scope-b","action","delete"));
        Map<String,Object> node=obj("id","format","sourceTaskId",first,"type","boolean","role","input","nameEn","Issue format","sourceQuote",FIRST,"sourceObservationId","note-a","branches",new ArrayList<>(),"options",new ArrayList<>(),"effects",effects);
        List<Object> requirements=new ArrayList<>();requirements.add(obj("id","r1","sourceTaskId",first,"sourceQuote",FIRST,"sourceObservationId","note-a","kind","input","nodeIds",Collections.singletonList("format")));if(bothEffects)requirements.add(obj("id","r2","sourceTaskId",second,"sourceQuote",SECOND,"sourceObservationId","note-b","kind","amendment","nodeIds",Collections.singletonList("format")));
        return JsonUtils.write(obj("nodes",Collections.singletonList(node),"requirements",requirements,"taskOutcomes",Arrays.asList(obj("sourceTaskId",first,"status","identified"),obj("sourceTaskId",second,"status",bothEffects?"identified":"no_requirement","reason","No additional input is introduced")),"notes",Collections.singletonList("Keep the existing root and original evidence")));
    }
    private static ObjectNode source(boolean shared,boolean overlap,boolean duplicate) {
        ObjectNode source=JsonUtils.mapper().createObjectNode().put("id","1").put("sourceDocumentId",1).put("fileKey","SCT").put("sourceSha256",SHA).put("evidenceStatus","bound").put("format","docx").put("editionId","synthetic").put("analysisRevision","a1").put("analysisInputSha256",SHA).put("analysisSha256",SHA);
        for(String collection:Arrays.asList("sourceMembers","paragraphs","guidanceNotes","markerCandidates","groups","conditions"))source.putArray(collection);
        source.withArray("guidanceNotes").addObject().put("id","note-a").put("text",FIRST).put("captureComplete",true);source.withArray("guidanceNotes").addObject().put("id","note-b").put("text",duplicate?FIRST:SECOND).put("captureComplete",true);source.withArray("conditions").addObject().put("id","common").put("text","The selected issue format controls these alternatives.");
        member(source,"body-a",BODY);if(!shared&&!overlap)member(source,"body-b","Other complete alternative.");ObjectNode first=scope(source,"scope-a","body-a",0,BODY.length());first.putArray("guidanceNoteIds").add("note-a");
        if(shared)first.withArray("guidanceNoteIds").add("note-b");else {ObjectNode second=scope(source,"scope-b",overlap?"body-a":"body-b",overlap?3:0,overlap?BODY.length():"Other complete alternative.".length());second.putArray("guidanceNoteIds").add("note-b");}return source;
    }
    private static ObjectNode scope(ObjectNode source,String id,String member,int start,int end){ObjectNode scope=source.withArray("groups").addObject().put("id",id).put("scopeComplete",true);scope.putArray("conditionIds").add("common");scope.putArray("memberRanges").addObject().put("memberId",member).put("channel","text").put("start",start).put("end",end);return scope;}
    private static void member(ObjectNode source,String id,String value){ObjectNode member=source.withArray("sourceMembers").addObject().put("id",id).put("identityStatus","source_bound");member.putObject("sourcePosition").put("sourceSha256",SHA).put("partName","word/document.xml").put("nodePath","/"+id);member.putObject("textObservation").put("text",value);ObjectNode paragraph=source.withArray("paragraphs").addObject().put("id","p-"+id).put("text",value).put("sourceSha256",SHA).put("paragraphSha256",SHA);paragraph.set("sourcePosition",member.path("sourcePosition").deepCopy());}
}
