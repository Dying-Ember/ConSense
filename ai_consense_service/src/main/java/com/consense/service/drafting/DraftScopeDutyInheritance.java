package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Share a recorded source duty only; a native parent/marker link never approves an amendment. */
final class DraftScopeDutyInheritance {
    private DraftScopeDutyInheritance() { }

    static boolean exactMarkerChild(JsonNode reading,Map<String,Object> parentTask,Map<String,Object> markerTask,String scopeId) {
        try {
            // shortcut: native Word links only; PDF inheritance needs its own source-range contract.
            if(reading==null||!"docx".equals(reading.path("format").asText())||!"bound".equals(reading.path("evidenceStatus").asText())||scopeId==null||scopeId.isEmpty())return false;
            if(!"guidance".equals(parentTask.get("itemKind"))||!"marker".equals(markerTask.get("itemKind"))||!taskSource(reading,parentTask)||!taskSource(reading,markerTask))return false;
            String guidanceId=text(parentTask.get("sourceObservationId")),markerId=text(markerTask.get("sourceObservationId"));
            if(guidanceId.isEmpty()||markerId.isEmpty()||guidanceId.equals(markerId)||guidanceId.equals(scopeId)||markerId.equals(scopeId))return false;
            JsonNode guidance=unique(reading.path("guidanceNotes"),guidanceId),marker=unique(reading.path("markerCandidates"),markerId),scope=unique(reading.path("groups"),scopeId);
            if(guidance==null||marker==null||scope==null||!guidance.path("captureComplete").asBoolean(false)||!guidance.path("sourceSyntaxComplete").asBoolean(false)||guidance.path("crossTableContinuationCandidate").asBoolean(false))return false;
            if(!scope.path("captureComplete").asBoolean(false)||!scope.path("scopeComplete").asBoolean(false)||!marker.path("scopeComplete").asBoolean(false)||!marker.path("conditionIds").isArray()||!reading.path("conditions").isArray()||!"conditional_source_target".equals(marker.path("disposition").asText())||!"source_scope_resolved_project_condition_unknown".equals(marker.path("status").asText()))return false;
            if(!guidanceId.equals(scope.path("guidanceNoteId").asText())&&!contains(scope.path("guidanceNoteIds"),guidanceId))return false;
            if(!contains(marker.path("targetGroupIds"),scopeId)||!contains(marker.path("guidanceNoteIds"),guidanceId))return false;
            Map<String,Object> anchor=DraftReviewDiscovery.sourceTaskAnchor(reading,parentTask);if(anchor==null||anchor.isEmpty()||!nativeTargetMarker(reading,marker,scope,guidance))return false;
            Map<String,Object> parent=DraftReviewDiscovery.sourceTaskPayload(reading,parentTask),child=DraftReviewDiscovery.sourceTaskPayload(reading,markerTask);
            if(!parent.containsKey("unresolvedConditionIds")||!child.containsKey("unresolvedConditionIds")||!list(parent.get("unresolvedConditionIds")).isEmpty()||!list(child.get("unresolvedConditionIds")).isEmpty())return false;
            Set<String> parentConditions=conditions(reading,parent),childConditions=conditions(reading,child);
            Set<String> recorded=new TreeSet<>();for(JsonNode id:marker.path("conditionIds"))if(!id.isTextual()||id.asText().isEmpty()||!recorded.add(id.asText()))return false;
            return parentConditions!=null&&!parentConditions.isEmpty()&&parentConditions.equals(childConditions)&&parentConditions.equals(recorded);
        }catch(RuntimeException unavailable){return false;}
    }

    private static boolean taskSource(JsonNode reading,Map<String,Object> task){String id=reading.path("id").asText(),sha=reading.path("sourceSha256").asText();return !id.isEmpty()&&sha.matches("[a-fA-F0-9]{64}")&&id.equals(text(task.get("sourceId")))&&sha.equals(text(task.get("sourceSha256")));}
    private static Set<String> conditions(JsonNode reading,Map<String,Object> payload){
        if(!(payload.get("sourceConditions") instanceof List))return null;Set<String> ids=new TreeSet<>();
        for(Object raw:list(payload.get("sourceConditions"))){JsonNode condition=JsonUtils.mapper().valueToTree(raw);String id=condition.path("id").asText(),noteId=condition.path("guidanceNoteId").asText();JsonNode current=unique(reading.path("conditions"),id),note=unique(reading.path("guidanceNotes"),noteId);
            if(id.isEmpty()||!ids.add(id)||current==null||!current.equals(condition)||note==null||!note.path("captureComplete").asBoolean(false)||!note.path("sourceSyntaxComplete").asBoolean(false)||note.path("crossTableContinuationCandidate").asBoolean(false)||!"literal_source_condition_not_project_boolean".equals(condition.path("status").asText())||!condition.path("text").equals(note.path("text"))||!condition.path("memberRanges").equals(note.path("memberRanges")))return null;
            for(JsonNode reference:condition.path("referenceCandidates"))if(!"resolved_explicit_source_target".equals(reference.path("status").asText()))return null;
        }return ids;
    }
    private static boolean nativeTargetMarker(JsonNode reading,JsonNode marker,JsonNode scope,JsonNode guidance){
        String memberId=marker.path("memberId").asText();JsonNode member=unique(reading.path("sourceMembers"),memberId);if(member==null||!nativeMember(reading,member)||!contains(scope.path("orderedMemberIds"),memberId)||!"text".equals(marker.path("channel").asText()))return false;
        JsonNode text=member.path("textObservation").path("text"),start=marker.path("start"),end=marker.path("end");String symbol=marker.path("symbol").asText();
        if(!text.isTextual()||symbol.isEmpty()||!start.isIntegralNumber()||!end.isIntegralNumber()||!start.canConvertToInt()||!end.canConvertToInt())return false;
        int from=start.asInt(),to=end.asInt();String value=text.asText();if(from<0||to>value.length()||to-from!=symbol.length()||!symbol.equals(value.substring(from,to))||!value.substring(0,from).trim().isEmpty())return false;
        JsonNode local=marker.path("localSentenceObservation");if(!local.path("start").isIntegralNumber()||!local.path("end").isIntegralNumber()||!local.path("start").canConvertToInt()||!local.path("end").canConvertToInt()||!local.path("text").isTextual())return false;
        int localStart=local.path("start").asInt(),localEnd=local.path("end").asInt();if(localStart<0||localStart>from||localEnd<to||localEnd>value.length()||!local.path("text").asText().equals(value.substring(localStart,localEnd)))return false;
        JsonNode firstNoteMember=unique(reading.path("sourceMembers"),guidance.path("memberRanges").path(0).path("memberId").asText());if(firstNoteMember==null||!nativeMember(reading,firstNoteMember)||!samePart(member,firstNoteMember))return false;
        for(String boundary:Arrays.asList("startBoundary","endBoundary")){JsonNode bound=unique(reading.path("sourceMembers"),scope.path(boundary).path("memberId").asText());if(bound==null||!nativeMember(reading,bound)||!samePart(member,bound))return false;}
        if(contains(scope.path("targetAnchorMemberIds"),memberId)&&(!scope.has("targetMemberIds")||contains(scope.path("targetMemberIds"),memberId)))return true;
        for(JsonNode range:scope.path("memberRanges"))if(memberId.equals(range.path("memberId").asText())&&"text".equals(range.path("channel").asText())&&range.path("start").isIntegralNumber()&&range.path("end").isIntegralNumber()&&range.path("start").canConvertToInt()&&range.path("end").canConvertToInt()&&range.path("start").asInt()>=0&&range.path("start").asInt()<=from&&range.path("end").asInt()>=to&&range.path("end").asInt()<=value.length()&&range.path("sourcePosition").equals(member.path("sourcePosition")))return true;
        return false;
    }
    private static boolean nativeMember(JsonNode reading,JsonNode member){JsonNode position=member.path("sourcePosition");return "docx_native".equals(position.path("kind").asText())&&reading.path("sourceSha256").equals(position.path("sourceSha256"))&&position.path("partSha256").asText().matches("[a-fA-F0-9]{64}")&&!position.path("partName").asText().isEmpty()&&!position.path("nodePath").asText().isEmpty();}
    private static boolean samePart(JsonNode first,JsonNode second){JsonNode a=first.path("sourcePosition"),b=second.path("sourcePosition");return a.path("partName").equals(b.path("partName"))&&a.path("partSha256").equals(b.path("partSha256"));}
    private static JsonNode unique(JsonNode rows,String id){if(!rows.isArray()||id==null||id.isEmpty())return null;JsonNode result=null;for(JsonNode row:rows)if(id.equals(row.path("id").asText())){if(result!=null)return null;result=row;}return result;}
    private static boolean contains(JsonNode rows,String id){if(!rows.isArray()||id==null||id.isEmpty())return false;for(JsonNode row:rows)if(row.isTextual()&&id.equals(row.asText()))return true;return false;}
}
