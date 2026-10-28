package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Registered wording with synthetic current observations; no model, API or database calls. */
class DraftCurrentActionSemanticReferenceTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a"));

    @Test void partialNscSentenceReceivesBusinessMeaningWithoutTheWholeParagraphTarget(){
        ObjectNode source=source(at(547),at(546),nscSentence());String before=source.toString();JsonNode result=align(source),semantic=result.path("fixedActionSemanticReferences").path(0);
        assertEquals("definition_reference_only",result.path("definitionStatus").asText());assertEquals("requires_review",result.path("scopeStatus").asText());assertEquals("unknown",result.path("conditionStatus").asText());
        assertTrue(result.path("fixedActionReferences").isEmpty());assertFalse(result.path("editableAuthority").asBoolean());assertTrue(result.path("modelRequired").asBoolean());
        assertEquals(Collections.singleton("scope"),strings(result.path("currentScopeIds")));assertTrue(strings(result.path("reasonCodes")).contains("CURRENT_ACTION_TARGET_UNPROVEN"));
        assertEquals("NTT-13-SUBCONTRACT",semantic.path("actionId").asText());assertEquals("fixed_action_semantic_reference",semantic.path("referenceKind").asText());assertFalse(semantic.path("editableAuthority").asBoolean());assertEquals("requires_review",semantic.path("scopeStatus").asText());
        assertEquals(new HashSet<>(Arrays.asList("subcontractArrangement","nscAlternativeText")),strings(semantic.path("inputKeys")));
        String detail=semantic.path("detailEn").asText();assertTrue(detail.contains("NSC retains the standard wording"));assertTrue(detail.contains("BSSSC uses the exact adopted NTT13 replacement"));assertTrue(detail.contains("Preserve the clause number and requirement"));
        assertTrue(semantic.path("notice").asText().contains("does not by itself authorize deletion"));assertTrue(semantic.path("notice").asText().contains("whole provision to a partial current scope"));
        Set<String> fields=new HashSet<>();semantic.fieldNames().forEachRemaining(fields::add);assertEquals(new HashSet<>(Arrays.asList("actionId","document","historicalClause","inputKeys","detailEn","referenceKind","editableAuthority","scopeStatus","notice")),fields);
        assertEquals(before,source.toString(),"Semantic context must not enlarge or rewrite the observed scope");
    }

    @Test void groupedWirePacketKeepsMeaningSeparateFromActionAndSourceAuthority(){
        ObjectNode source=source(at(547),at(546),nscSentence());List<Map<String,Object>> tasks=DraftReviewDiscovery.sourceTasks(Collections.singletonList(source));
        Map<String,Object> packet=DraftDiscoveryGroups.payload(Collections.singletonMap("source",source),tasks,Collections.emptyList());JsonNode wire=DraftDiscoveryReferences.group(packet).encode(packet),instruction=wire.path("instructions").path(0),alignment=instruction.path("currentTemplateAlignment");
        assertEquals(1,alignment.path("fixedActionSemanticReferences").size());assertFalse(instruction.has("fixedActionReferences"));assertFalse(alignment.has("fixedActionReferences"));
        assertEquals("NTT-13-SUBCONTRACT",alignment.path("fixedActionSemanticReferences").path(0).path("actionId").asText());
        assertEquals(Collections.singleton("s1"),strings(instruction.path("scopeObligationIds")));assertEquals(Collections.singleton("s1"),strings(alignment.path("currentScopeIds")));assertTrue(wire.path("reviewedRootKeys").isEmpty());
        JsonNode currentScope=null;for(JsonNode evidence:wire.path("sharedEvidence"))if("scope".equals(evidence.path("kind").asText()))currentScope=evidence;
        assertNotNull(currentScope);assertEquals(nscSentence(),currentScope.path("text").asText());assertNotEquals(at(546),currentScope.path("text").asText());
    }

    @Test void aCompleteMatchingTargetKeepsItsExistingActionReference(){
        JsonNode result=align(source(at(547),at(546),at(546)));assertEquals("exact_instruction_reference",result.path("definitionStatus").asText());assertEquals("current_observation_complete",result.path("scopeStatus").asText());
        assertEquals("NTT-13-SUBCONTRACT",result.path("fixedActionReferences").path(0).path("actionId").asText());assertFalse(result.has("fixedActionSemanticReferences"));
    }

    @Test void unprovenChangedOrAmbiguousInstructionsDoNotAcquireSemanticReferences(){
        for(String gap:Arrays.asList("changed","incomplete","stale-note","unbound","different-document")){
            ObjectNode source=source("changed".equals(gap)?"(*Delete if NSC is not applicable)":at(547),at(546),nscSentence());
            if("incomplete".equals(gap))((ObjectNode)source.path("guidanceNotes").path(0)).put("captureComplete",false);
            if("stale-note".equals(gap))((ObjectNode)source.path("sourceMembers").path(0).path("sourcePosition")).put("sourceSha256","stale");
            if("unbound".equals(gap))source.put("evidenceStatus","unbound");if("different-document".equals(gap))source.put("fileKey","SCC");
            assertFalse(align(source).has("fixedActionSemanticReferences"),gap);
        }
        JsonNode ambiguous=align(source(at(135),"Another target.","Another target."));assertTrue(ambiguous.path("fixedActionReferences").isEmpty());assertFalse(ambiguous.has("fixedActionSemanticReferences"));
    }

    private static ObjectNode source(String note,String fullBody,String selected){
        ObjectNode source=JsonUtils.mapper().createObjectNode().put("id","source").put("fileKey","NTT").put("sourceSha256",SHA).put("evidenceStatus","bound").put("format","docx");
        for(String field:Arrays.asList("guidanceNotes","groups","sourceMembers","paragraphs","markerCandidates","conditions"))source.putArray(field);
        ObjectNode guidance=source.withArray("guidanceNotes").addObject().put("id","note").put("text",note).put("captureComplete",true);guidance.putArray("orderedMemberIds").add("note-member");
        int start=fullBody.indexOf(selected);assertTrue(start>=0);ObjectNode scope=source.withArray("groups").addObject().put("id","scope").put("text",selected).put("scopeComplete",true);scope.putArray("guidanceNoteIds").add("note");scope.putArray("memberRanges").addObject().put("memberId","body-member").put("channel","text").put("start",start).put("end",start+selected.length());
        member(source,"note-member",note);member(source,"body-member",fullBody);return source;
    }
    private static void member(ObjectNode source,String id,String text){ObjectNode member=source.withArray("sourceMembers").addObject().put("id",id).put("identityStatus","source_bound");member.putObject("textObservation").put("text",text);member.putObject("sourcePosition").put("sourceSha256",SHA).put("partName","word/document.xml").put("nodePath","/current/"+id);}
    private static JsonNode align(ObjectNode source){JsonNode note=source.path("guidanceNotes").path(0),scope=source.path("groups").path(0);Map<String,Object> task=obj("taskId","task","sourceId","source","sourceSha256",SHA,"sourceObservationId","note","itemKind","guidance");Map<String,Object> context=obj("instruction",obj("id","note","text",note.path("text").asText()),"scopeCandidates",Collections.singletonList(obj("id","scope","text",scope.path("text").asText(),"scopeComplete",true,"observation",scope)),"sourceConditions",Collections.emptyList(),"unresolvedConditionIds",Collections.emptyList());return JsonUtils.mapper().valueToTree(DraftCurrentTemplateAlignment.index(source).align(task,context));}
    private static Set<String> strings(JsonNode values){Set<String> result=new HashSet<>();for(JsonNode value:values)result.add(value.asText());return result;}
    private static String at(int ordinal){return DraftBusinessRules.standardParagraph("NTT",ordinal);}
    private static String nscSentence(){String body=at(546);int start=body.indexOf("*Such requirement");assertTrue(start>=0);return body.substring(start);}
}
