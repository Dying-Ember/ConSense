package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

class DraftScopeDutyInheritanceTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a")),PART=String.join("",Collections.nCopies(64,"b"));
    @Test void exactParentGuidanceAndItsNativeMarkerShareOnlyTheSameScopeDuty(){
        ObjectNode reading=reading();String before=reading.toString();assertTrue(check(reading));assertEquals(before,reading.toString());
        ObjectNode scope=(ObjectNode)reading.path("groups").path(0);scope.remove("memberRanges");scope.put("kind","complete_alternative_scope");scope.putArray("targetAnchorMemberIds").add("body-member");scope.putArray("targetMemberIds").add("body-member");assertTrue(check(reading),"An exact current native alternative heading is an explicit target anchor.");
    }
    @Test void anotherResolvedLocalConditionPreventsInheritance(){
        ObjectNode reading=reading();String extra="(Delete if another independent fact applies)";member(reading,"extra-member",extra);ObjectNode note=note(reading,"extra-note","extra-member",extra);condition(reading,"extra-condition",note);((ObjectNode)reading.path("markerCandidates").path(0)).withArray("guidanceNoteIds").add("extra-note");((ObjectNode)reading.path("markerCandidates").path(0)).withArray("conditionIds").add("extra-condition");assertFalse(check(reading));
    }
    @Test void differentGuidanceScopeOrSourceCannotBorrowTheParentDisposition(){
        ObjectNode guide=reading();((ObjectNode)guide.path("groups").path(0)).put("guidanceNoteId","another-note");assertFalse(check(guide));
        ObjectNode scope=reading();((ObjectNode)scope.path("markerCandidates").path(0)).putArray("targetGroupIds").add("another-scope");assertFalse(check(scope));
        ObjectNode source=reading();List<Map<String,Object>> tasks=tasks(source);Map<String,Object> marker=new LinkedHashMap<>(tasks.get(1));marker.put("sourceSha256",PART);assertFalse(DraftScopeDutyInheritance.exactMarkerChild(source,tasks.get(0),marker,"scope"));marker.put("sourceSha256",SHA);marker.put("sourceId","another-source");assertFalse(DraftScopeDutyInheritance.exactMarkerChild(source,tasks.get(0),marker,"scope"));
    }
    @Test void overlapContextAndUnboundOrAmbiguousEvidenceRemainUnresolved(){
        for(String issue:Arrays.asList("context-only","range","native-sha","missing-condition","missing-boundary","duplicate-scope","unknown-condition","incomplete-scope","pdf")){
            ObjectNode reading=reading(),marker=(ObjectNode)reading.path("markerCandidates").path(0),scope=(ObjectNode)reading.path("groups").path(0);
            if("context-only".equals(issue)){marker.putArray("targetGroupIds");marker.putArray("contextGroupIds").add("scope");}
            if("range".equals(issue))((ObjectNode)scope.path("memberRanges").path(0)).put("end",0);
            if("native-sha".equals(issue))((ObjectNode)reading.path("sourceMembers").path(1).path("sourcePosition")).put("sourceSha256",PART);
            if("missing-condition".equals(issue))marker.remove("conditionIds");
            if("missing-boundary".equals(issue))scope.remove("endBoundary");
            if("duplicate-scope".equals(issue))reading.withArray("groups").add(scope.deepCopy());
            if("unknown-condition".equals(issue))marker.withArray("conditionIds").add("unresolved-condition");
            if("incomplete-scope".equals(issue))scope.put("scopeComplete",false);
            if("pdf".equals(issue))reading.put("format","pdf");
            assertFalse(check(reading),issue);
        }
    }
    private static boolean check(ObjectNode reading){List<Map<String,Object>> tasks=tasks(reading);return DraftScopeDutyInheritance.exactMarkerChild(reading,tasks.get(0),tasks.get(1),"scope");}
    private static List<Map<String,Object>> tasks(ObjectNode reading){List<Map<String,Object>> result=new ArrayList<>();for(String kind:Arrays.asList("guidance","marker"))for(Map<String,Object> task:DraftReviewDiscovery.sourceTasks(Collections.singletonList(reading)))if(kind.equals(task.get("itemKind"))&&("guidance".equals(kind)?"note":"marker").equals(task.get("sourceObservationId"))){result.add(task);break;}assertEquals(2,result.size());return result;}
    private static ObjectNode reading(){
        ObjectNode reading=JsonUtils.mapper().createObjectNode().put("id","1").put("sourceDocumentId",1L).put("fileKey","NTT").put("format","docx").put("sourceSha256",SHA).put("evidenceStatus","bound").put("analysisRevision","current-test").put("analysisInputSha256",SHA).put("analysisSha256",SHA);reading.putArray("guidanceNotes");reading.putArray("conditions");reading.putArray("sourceMembers");String instruction="(Delete if the project option is not applicable)",body="*(e) Complete current clause wording.";member(reading,"note-member",instruction);member(reading,"body-member",body);member(reading,"next-member","(f) Next peer clause.");ObjectNode note=note(reading,"note","note-member",instruction);condition(reading,"condition",note);
        ObjectNode scope=reading.putArray("groups").addObject().put("id","scope").put("kind","source_unit_instruction_scope").put("guidanceNoteId","note").put("text",body).put("captureComplete",true).put("scopeComplete",true);scope.putArray("orderedMemberIds").add("body-member");scope.putArray("memberRanges").add(range(reading,"body-member",body.length()));scope.putObject("startBoundary").put("kind","explicit_native_clause_or_subitem_label").put("memberId","body-member");scope.putObject("endBoundary").put("kind","next_native_peer_or_parent_label").put("memberId","next-member");
        ObjectNode marker=reading.putArray("markerCandidates").addObject().put("id","marker").put("memberId","body-member").put("symbol","*").put("channel","text").put("start",0).put("end",1).put("scopeComplete",true).put("status","source_scope_resolved_project_condition_unknown").put("disposition","conditional_source_target");marker.putArray("targetGroupIds").add("scope");marker.putArray("guidanceNoteIds").add("note");marker.putArray("conditionIds").add("condition");marker.putObject("localSentenceObservation").put("text",body).put("start",0).put("end",body.length());return reading;
    }
    private static ObjectNode note(ObjectNode reading,String id,String member,String text){ObjectNode note=reading.withArray("guidanceNotes").addObject().put("id",id).put("kind","editorial_parenthetical_candidate").put("text",text).put("captureComplete",true).put("sourceSyntaxComplete",true).put("scopeComplete",false);note.putArray("orderedMemberIds").add(member);note.putArray("memberRanges").add(range(reading,member,text.length()));return note;}
    private static void condition(ObjectNode reading,String id,ObjectNode note){ObjectNode condition=reading.withArray("conditions").addObject().put("id",id).put("guidanceNoteId",note.path("id").asText()).put("text",note.path("text").asText()).put("status","literal_source_condition_not_project_boolean");condition.set("memberRanges",note.path("memberRanges").deepCopy());condition.putArray("referenceCandidates");}
    private static void member(ObjectNode reading,String id,String text){ObjectNode member=reading.withArray("sourceMembers").addObject().put("id",id).put("identityStatus","source_bound");member.putObject("textObservation").put("text",text);member.putObject("sourcePosition").put("kind","docx_native").put("sourceSha256",SHA).put("partSha256",PART).put("partName","word/document.xml").put("nodePath","/current/"+id);}
    private static ObjectNode range(ObjectNode reading,String id,int length){ObjectNode range=JsonUtils.mapper().createObjectNode().put("memberId",id).put("channel","text").put("start",0).put("end",length);for(JsonNode member:reading.path("sourceMembers"))if(id.equals(member.path("id").asText()))range.set("sourcePosition",member.path("sourcePosition").deepCopy());return range;}
}
