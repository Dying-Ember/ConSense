package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Source-only synthetic contracts; no product, parser, model, database or browser calls. */
class DraftCurrentTemplateAlignmentTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a"));

    @Test void changedFileAndMovedParagraphKeepOnlyTheExactInstructionReference() {
        ObjectNode reading=source("NTT",textAt("NTT",244),textAt("NTT",243));
        JsonNode result=align(reading);
        assertEquals("exact_instruction_reference",result.path("definitionStatus").asText());
        assertEquals("worksSubjectToExcision",result.path("fixedRootKeys").path(0).asText());
        assertEquals("NTT-5F-EXCISION",result.path("fixedActionReferences").path(0).path("actionId").asText());
        assertEquals("current_observation_complete",result.path("scopeStatus").asText());
        assertEquals("current-scope",result.path("currentScopeIds").path(0).asText());
        assertEquals(SHA,result.path("sourceSha256").asText());assertTrue(result.path("modelRequired").asBoolean());
        String json=result.toString();for(String forbidden:Arrays.asList("P243","P244","nativeBinding","editTargets","confirmed"))assertFalse(json.contains(forbidden));
    }

    @Test void whitespaceDoesNotChangeAReferenceButNegationNumbersAndObjectsDo() {
        String note=textAt("NTT",244);ObjectNode layout=source("NTT",note.replace(" there ","\n\tthere  "),textAt("NTT",243));
        assertEquals("exact_instruction_reference",align(layout).path("definitionStatus").asText());
        for(String changed:Arrays.asList(note.replace("no Works","Works"),note.replace("Excision","Excavation"),note+" (only above 39 months)")) {
            JsonNode result=align(source("NTT",changed,textAt("NTT",243)));
            assertTrue(result.path("fixedRootKeys").isEmpty());assertTrue(result.path("modelRequired").asBoolean());
        }
    }

    @Test void completeBqNoteIsAReferenceWithoutClaimingTheMissingParentConditions() {
        String note=textAt("NTT",172)+"\n"+textAt("NTT",173);JsonNode result=align(source("NTT",note,textAt("NTT",166)));
        assertEquals("NTT-3C-L10PRO",result.path("fixedActionReferences").path(0).path("actionId").asText());
        assertTrue(result.path("fixedRootKeys").toString().contains("twoEnvelopeTendering"));
        assertEquals("unknown",result.path("conditionStatus").asText());assertTrue(result.path("modelRequired").asBoolean());
        assertTrue(align(source("NTT",textAt("NTT",172),textAt("NTT",166))).path("fixedRootKeys").isEmpty());
    }

    @Test void incompleteOrStaleCurrentEvidenceCannotAcquireAFixedReference() {
        ObjectNode incomplete=source("NTT",textAt("NTT",244),textAt("NTT",243));((ObjectNode)incomplete.path("guidanceNotes").path(0)).put("captureComplete",false);
        assertTrue(align(incomplete).path("fixedRootKeys").isEmpty());
        ObjectNode stale=source("NTT",textAt("NTT",244),textAt("NTT",243));((ObjectNode)stale.path("sourceMembers").path(0).path("sourcePosition")).put("sourceSha256","old-source");
        assertTrue(align(stale).path("fixedRootKeys").isEmpty());
        ObjectNode copied=source("NTT",textAt("NTT",244),textAt("NTT",243));((ObjectNode)copied.path("sourceMembers").path(0).path("textObservation")).put("text","Different actual text");
        assertTrue(align(copied).path("fixedRootKeys").isEmpty());
    }

    @Test void uncertainCurrentScopeDoesNotEraseTheValidDefinitionReference() {
        ObjectNode reading=source("NTT",textAt("NTT",244),textAt("NTT",243));((ObjectNode)reading.path("groups").path(0)).put("scopeComplete",false);
        JsonNode result=align(reading);assertFalse(result.path("fixedRootKeys").isEmpty());
        assertEquals("requires_review",result.path("scopeStatus").asText());assertTrue(result.path("modelRequired").asBoolean());
        assertTrue(result.path("fixedActionReferences").isEmpty());assertEquals("definition_reference_only",result.path("definitionStatus").asText());
        assertFalse(result.has("condition"));assertFalse(result.has("actions"));
    }

    @Test void changedObjectTextStillRequiresInterpretationAndNeverReusesAFixedAction() {
        JsonNode result=align(source("NTT",textAt("NTT",244),"The altered clause now governs another object."));
        assertFalse(result.path("fixedRootKeys").isEmpty(),"The unchanged note still supplies a navigation reference");
        assertTrue(result.path("modelRequired").asBoolean());assertFalse(result.has("actions"));assertFalse(result.has("editTargets"));
        assertTrue(result.path("fixedActionReferences").isEmpty());assertEquals("definition_reference_only",result.path("definitionStatus").asText());
        assertEquals("unknown",result.path("conditionStatus").asText());
    }

    @Test void aDifferentSccNumberingEditionCannotBorrowOldParagraphCoordinates() {
        ObjectNode reading=source("SCC",textAt("NTT",244),"SCC 41.2 Excision in another edition.");
        JsonNode result=align(reading);assertTrue(result.path("fixedActionReferences").isEmpty());assertTrue(result.path("modelRequired").asBoolean());
    }

    @Test void groupConsumesCurrentReferencesWithoutPromotingReviewedRootsOrDroppingScopeTasks() {
        ObjectNode reading=source("NTT",textAt("NTT",244),textAt("NTT",243));
        Map<String,JsonNode> readings=Collections.singletonMap("current-source",reading);List<Map<String,Object>> tasks=DraftReviewDiscovery.sourceTasks(Collections.singletonList(reading));
        Map<String,DraftCurrentTemplateAlignment.Index> indexes=DraftDiscoveryGroups.currentTemplateIndexes(readings);
        JsonNode packet=JsonUtils.mapper().valueToTree(DraftDiscoveryGroups.payload(readings,tasks,Collections.emptyList(),indexes));
        assertEquals(tasks.size(),packet.path("instructions").size());assertTrue(packet.path("reviewedRootKeys").isEmpty());
        assertEquals("worksSubjectToExcision",packet.path("fixedRootRouteCandidates").path(0).asText());
        assertEquals("worksSubjectToExcision",packet.path("fixedContext").path("fields").path(0).path("key").asText());
        JsonNode instruction=packet.path("instructions").path(0),alignment=instruction.path("currentTemplateAlignment");
        assertEquals("current-scope",instruction.path("scopeObligationIds").path(0).asText());assertTrue(alignment.path("modelRequired").asBoolean());
        assertFalse(alignment.path("editableAuthority").asBoolean());assertFalse(alignment.has("fixedActionReferences"),"Action references are already supplied beside this compact receipt");
        assertEquals("NTT-5F-EXCISION",instruction.path("fixedActionReferences").path(0).path("actionId").asText());
        Set<String> available=new HashSet<>();for(JsonNode evidence:packet.path("sharedEvidence"))available.add(evidence.path("ref").asText());
        for(JsonNode ref:alignment.path("currentEvidenceRefs"))assertTrue(available.contains(ref.asText()));
        assertEquals(packet,JsonUtils.mapper().valueToTree(DraftDiscoveryGroups.payload(readings,tasks,Collections.emptyList())));
        List<Map<String,Object>> groups=DraftDiscoveryGroups.plan(readings,tasks,Collections.emptyList(),indexes);
        assertEquals(1,groups.size());assertEquals(Collections.singletonList(tasks.get(0).get("taskId")),groups.get(0).get("taskIds"));
    }

    @Test void repeatedInstructionsWithAProvenScopeRemainUnreviewedAndIncompleteNotesStayUnrouted() {
        ObjectNode reading=source("NTT",textAt("NTT",244),textAt("NTT",243));
        ObjectNode second=((ObjectNode)reading.path("guidanceNotes").path(0)).deepCopy();second.put("id","second-note");reading.withArray("guidanceNotes").add(second);((ObjectNode)reading.path("groups").path(0)).withArray("guidanceNoteIds").add("second-note");
        Map<String,JsonNode> readings=Collections.singletonMap("current-source",reading);List<Map<String,Object>> tasks=DraftReviewDiscovery.sourceTasks(Collections.singletonList(reading));
        JsonNode packet=JsonUtils.mapper().valueToTree(DraftDiscoveryGroups.payload(readings,tasks,Collections.emptyList()));
        assertEquals(2,packet.path("instructions").size());assertTrue(packet.path("reviewedRootKeys").isEmpty());
        for(JsonNode instruction:packet.path("instructions")){assertEquals("exact_instruction_reference",instruction.path("currentTemplateAlignment").path("definitionStatus").asText());assertEquals("current_observation_complete",instruction.path("currentTemplateAlignment").path("scopeStatus").asText());assertTrue(instruction.path("currentTemplateAlignment").path("modelRequired").asBoolean());assertFalse(instruction.path("currentTemplateAlignment").path("editableAuthority").asBoolean());}
        for(JsonNode note:reading.path("guidanceNotes"))((ObjectNode)note).put("captureComplete",false);
        JsonNode incomplete=JsonUtils.mapper().valueToTree(DraftDiscoveryGroups.payload(readings,tasks,Collections.emptyList()));
        assertEquals(2,incomplete.path("instructions").size());assertTrue(incomplete.path("fixedRootRouteCandidates").isEmpty());assertTrue(incomplete.path("reviewedRootKeys").isEmpty());
        assertFalse(incomplete.path("instructions").path(0).path("scopeObligationIds").isEmpty());
    }

    @Test void exactSourceRoutingAndCurrentInstructionReferenceDoNotDuplicateAnAction() {
        ObjectNode reading=source("NTT",textAt("NTT",244),textAt("NTT",243));String original="60bd8796aa828863c7edfe8617b7a5c56ee383afe8ba87a089e820f0fd9a982c";reading.put("sourceSha256",original);
        for(JsonNode member:reading.path("sourceMembers"))((ObjectNode)member.path("sourcePosition")).put("sourceSha256",original);
        ObjectNode paragraph=reading.putArray("paragraphs").addObject().put("id","current-note-p").put("ordinal",244).put("sourceSha256",original).put("text",textAt("NTT",244));paragraph.set("sourcePosition",reading.path("sourceMembers").path(0).path("sourcePosition").deepCopy());
        List<Map<String,Object>> tasks=DraftReviewDiscovery.sourceTasks(Collections.singletonList(reading));JsonNode packet=JsonUtils.mapper().valueToTree(DraftDiscoveryGroups.payload(Collections.singletonMap("current-source",reading),tasks,Collections.emptyList()));
        int count=0;for(JsonNode action:packet.path("instructions").path(0).path("fixedActionReferences"))if("NTT-5F-EXCISION".equals(action.path("actionId").asText()))count++;
        assertEquals(1,count);assertTrue(packet.path("reviewedRootKeys").isEmpty());
    }

    private static JsonNode align(ObjectNode reading) {
        JsonNode note=reading.path("guidanceNotes").path(0),scope=reading.path("groups").path(0);
        Map<String,Object> task=obj("taskId","current-task","sourceId","current-source","sourceSha256",SHA,"sourceObservationId","current-note","itemKind","guidance");
        Map<String,Object> payload=obj("instruction",obj("id",note.path("id").asText(),"text",note.path("text").asText()),
                "scopeCandidates",Collections.singletonList(obj("id",scope.path("id").asText(),"text",scope.path("text").asText(),"scopeComplete",scope.path("scopeComplete").asBoolean(),"observation",scope)),
                "sourceConditions",Collections.emptyList(),"unresolvedConditionIds",Collections.emptyList());
        return JsonUtils.mapper().valueToTree(DraftCurrentTemplateAlignment.index(reading).align(task,payload));
    }

    private static ObjectNode source(String document,String noteText,String body) {
        ObjectNode reading=JsonUtils.mapper().createObjectNode().put("id","current-source").put("fileKey",document).put("sourceSha256",SHA).put("evidenceStatus","bound").put("format","docx");
        ObjectNode note=reading.putArray("guidanceNotes").addObject().put("id","current-note").put("text",noteText).put("captureComplete",true);note.putArray("orderedMemberIds").add("current-note-member");
        ObjectNode scope=reading.putArray("groups").addObject().put("id","current-scope").put("text",body).put("scopeComplete",true);scope.putArray("guidanceNoteIds").add("current-note");
        scope.putArray("memberRanges").addObject().put("memberId","current-body-member").put("channel","text").put("start",0).put("end",body.length());
        reading.putArray("sourceMembers");member(reading,"current-note-member",noteText,"/current/shifted/p999");member(reading,"current-body-member",body,"/current/shifted/p998");return reading;
    }
    private static void member(ObjectNode reading,String id,String text,String path) {
        ObjectNode member=reading.withArray("sourceMembers").addObject().put("id",id);member.putObject("textObservation").put("text",text);
        member.putObject("sourcePosition").put("sourceSha256",SHA).put("partName","word/document.xml").put("nodePath",path);
    }
    private static String textAt(String document,int ordinal) {return DraftBusinessRules.standardParagraph(document,ordinal);}
}
