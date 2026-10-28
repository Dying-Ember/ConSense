package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Real registered wording in synthetic source observations; no model, parser, API or database calls. */
class DraftCurrentActionTargetRoutingTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a")),PINNED="60bd8796aa828863c7edfe8617b7a5c56ee383afe8ba87a089e820f0fd9a982c";

    @Test void identicalPaperNotesSelectDistinctDisseminationAndReturnActionsByCurrentBody(){
        ObjectNode source=source("NTT",SHA);add(source,"issue",at(135),paragraphs(127,134));add(source,"return",at(178),at(168));String before=source.toString();
        JsonNode issue=align(source,"issue"),returned=align(source,"return");assertEquals(at(135),at(178));
        assertEquals(Collections.singleton("NTT-2-PAPER"),actions(issue));assertEquals(Collections.singleton("electronicTendering"),strings(issue.path("fixedRootKeys")));
        assertEquals(Collections.singleton("NTT-3C-PAPER"),actions(returned));assertEquals(new HashSet<>(Arrays.asList("electronicTendering","twoEnvelopeTendering","otherTenderingArrangement")),strings(returned.path("fixedRootKeys")));
        for(JsonNode result:Arrays.asList(issue,returned)){assertEquals("exact_instruction_reference",result.path("definitionStatus").asText());assertEquals("current_observation_complete",result.path("scopeStatus").asText());assertTrue(strings(result.path("reasonCodes")).contains("CURRENT_TARGET_DISAMBIGUATES_INSTRUCTION"));assertFalse(result.path("editableAuthority").asBoolean());assertTrue(result.path("modelRequired").asBoolean());}
        assertEquals(before,source.toString());
    }

    @Test void electronicIssueContinuationAndReturnRemainSeparateAfterParagraphMovement(){
        ObjectNode source=source("NTT",SHA);add(source,"issue",paragraphs(107,109),paragraphs(97,106));add(source,"continuation",paragraphs(122,124),paragraphs(117,120));add(source,"return",paragraphs(172,173),at(166));
        for(String id:Arrays.asList("issue","continuation")){JsonNode result=align(source,id);assertEquals(Collections.singleton("NTT-2-L10PRO"),actions(result));assertEquals(Collections.singleton("electronicTendering"),strings(result.path("fixedRootKeys")));assertEquals("exact_instruction_reference",result.path("definitionStatus").asText());}
        assertEquals(Collections.singleton("NTT-3C-L10PRO"),actions(align(source,"return")));
    }

    @Test void matchingNoteWithoutMatchingBodyOnlySuppliesCommonDefinitionNavigation(){
        ObjectNode source=source("NTT",SHA);add(source,"unknown",at(135),"The altered clause governs another object.");JsonNode result=align(source,"unknown");
        assertTrue(actions(result).isEmpty());assertEquals(Collections.singleton("electronicTendering"),strings(result.path("fixedRootKeys")));assertEquals("definition_reference_only",result.path("definitionStatus").asText());assertEquals("requires_review",result.path("scopeStatus").asText());assertTrue(strings(result.path("reasonCodes")).contains("CURRENT_ACTION_TARGET_UNPROVEN"));
    }

    @Test void incompleteStaleOrTruncatedCurrentScopeCannotAcquireAnAction(){
        for(String gap:Arrays.asList("incomplete","stale","truncated","source-mismatch")){
            ObjectNode source=source("NTT",SHA);add(source,"issue",at(135),paragraphs(127,134));ObjectNode scope=scope(source,"issue");
            if("incomplete".equals(gap))scope.put("scopeComplete",false);
            if("stale".equals(gap))((ObjectNode)member(source,"issue-body").path("sourcePosition")).put("sourceSha256","stale");
            if("truncated".equals(gap)){String text=at(130);scope.put("text",text);scope.putArray("memberRanges").addObject().put("memberId","issue-body").put("channel","text").put("start",0).put("end",text.length());((ObjectNode)member(source,"issue-body").path("textObservation")).put("text",text);}
            if("source-mismatch".equals(gap))((ObjectNode)member(source,"issue-body").path("textObservation")).put("text","Different actual source text");
            JsonNode result=align(source,"issue");assertTrue(actions(result).isEmpty(),gap);assertEquals("definition_reference_only",result.path("definitionStatus").asText(),gap);
        }
    }

    @Test void verifiedGuidanceOrdinalAloneCannotSupplyActionOrItsExtraInputs(){
        ObjectNode source=source("NTT",PINNED);add(source,"issue",at(135),paragraphs(127,134));pinNote(source,"issue",178);
        JsonNode packet=packet(source),instruction=packet.path("instructions").path(0);assertEquals(Collections.singleton("NTT-2-PAPER"),actions(instruction));assertEquals(Collections.singleton("electronicTendering"),strings(packet.path("fixedRootRouteCandidates")));
        ObjectNode broad=source("NTT",PINNED);add(broad,"broad","Electronic format controls the stated alternative.","Unrelated source text.");pinNote(broad,"broad",172);JsonNode withheld=packet(broad);
        assertTrue(actions(withheld.path("instructions").path(0)).isEmpty());assertTrue(withheld.path("fixedRootRouteCandidates").isEmpty());
    }

    @Test void aWideScopeContainingBothActionsDoesNotAssignEitherActionToEveryTarget(){
        ObjectNode source=source("NTT",SHA);add(source,"wide",at(135),paragraphs(127,134)+"\n"+at(168));JsonNode result=align(source,"wide");assertTrue(actions(result).isEmpty());assertEquals(Collections.singleton("electronicTendering"),strings(result.path("fixedRootKeys")));
        ObjectNode multiple=source("NTT",SHA);add(multiple,"issue",at(135),paragraphs(127,134));add(multiple,"return",at(178),at(168));Map<String,Object> context=context(multiple,"issue");((List<Object>)context.get("scopeCandidates")).add(scopePacket(multiple,"return"));
        JsonNode mixed=tree(DraftCurrentTemplateAlignment.index(multiple).align(task(multiple,"issue"),context));assertTrue(actions(mixed).isEmpty());
    }

    @Test void layoutWhitespaceMayChangeButMeaningNumbersAndObjectsCannot(){
        ObjectNode source=source("NTT",SHA);String body=paragraphs(127,134).replace("(a)The","(a) The").replace("(b)Electronic","(b) Electronic").replace("(c)Upon","(c) Upon").replace("  ","\n\t");add(source,"layout",at(135),body);assertEquals(Collections.singleton("NTT-2-PAPER"),actions(align(source,"layout")));
        for(String changed:Arrays.asList(at(168).replace("shall be priced","shall not be priced"),at(168).replace("SCT5(4)","SCT5(5)"),at(168).replace("hard copy","hardcopy"))){ObjectNode different=source("NTT",SHA);add(different,"changed",at(178),changed);assertTrue(actions(align(different,"changed")).isEmpty());}
    }

    @Test void documentEditionAndNativeSourceIdentityGuardsRemainInForce(){
        ObjectNode other=source("SCC",SHA);add(other,"other",at(135),paragraphs(127,134));assertTrue(actions(align(other,"other")).isEmpty());
        ObjectNode pdf=source("NTT",SHA);add(pdf,"other",at(135),paragraphs(127,134));pdf.put("format","pdf");assertTrue(actions(align(pdf,"other")).isEmpty());assertTrue(align(pdf,"other").path("fixedRootKeys").isEmpty());
        ObjectNode unbound=source("NTT",SHA);add(unbound,"other",at(135),paragraphs(127,134));unbound.put("evidenceStatus","unbound");assertTrue(actions(align(unbound,"other")).isEmpty());
    }

    @Test void theCompleteWtoClauseRetainsItsNativeListNumberAndExistingNotUsedMeaning(){
        ObjectNode source=source("NTT",SHA);add(source,"wto",at(439),wtoBody());JsonNode result=align(source,"wto");
        assertEquals("exact_instruction_reference",result.path("definitionStatus").asText());assertEquals(Collections.singleton("wtoGpaApplies"),strings(result.path("fixedRootKeys")));assertEquals(Collections.singleton("NTT-9-WTO"),actions(result));
        assertTrue(result.path("fixedActionReferences").path(0).path("detailEn").asText().contains("No retains number 9 with Not used"));assertFalse(result.path("editableAuthority").asBoolean());
        assertNull(DraftSourceAnchors.target("NTT-9-WTO"),"Discovery references must not broaden the older edit handler's target paragraphs");
        for(String body:Arrays.asList(wtoBody().replace("(e)",""),wtoBody().replace(at(469),""),wtoBody().replace("10 working days","11 working days"))){ObjectNode changed=source("NTT",SHA);add(changed,"wto",at(439),body);JsonNode pending=align(changed,"wto");assertTrue(actions(pending).isEmpty());assertEquals("definition_reference_only",pending.path("definitionStatus").asText());}
    }

    @Test void theRegisteredUnclosedRailwayNoteOnlySuppliesADefinitionAndKeepsItsWarning(){
        String note=String.join("\n",at(614),at(617),at(620)),body=String.join("\n",at(612),at(613),at(619));assertFalse(note.endsWith(")"));ObjectNode source=source("NTT",SHA);add(source,"railway",note,body);((ObjectNode)source.path("guidanceNotes").path(0)).put("captureComplete",false).put("sourceSyntaxKnown",true).put("sourceSyntaxComplete",false);String before=source.toString();JsonNode result=align(source,"railway");
        assertEquals("definition_reference_only",result.path("definitionStatus").asText());assertEquals(Collections.singleton("railwayProtectionAreaWorks"),strings(result.path("fixedRootKeys")));assertTrue(actions(result).isEmpty());assertTrue(result.path("currentScopeIds").isEmpty());assertEquals("unknown",result.path("conditionStatus").asText());assertEquals("requires_review",result.path("scopeStatus").asText());assertFalse(result.path("editableAuthority").asBoolean());assertTrue(strings(result.path("reasonCodes")).contains("COMPLETE_CURRENT_GUIDANCE_REQUIRED"));assertTrue(strings(result.path("reasonCodes")).contains(DraftCurrentTemplateAlignment.REGISTERED_INCOMPLETE_NTT17_REFERENCE));assertEquals(before,source.toString());assertNull(DraftSourceAnchors.target("NTT-17-RAILWAY"));
    }

    private static ObjectNode source(String document,String sha){ObjectNode source=JsonUtils.mapper().createObjectNode().put("id","source").put("fileKey",document).put("sourceSha256",sha).put("evidenceStatus","bound").put("format","docx");for(String key:Arrays.asList("guidanceNotes","groups","sourceMembers","paragraphs","markerCandidates","conditions"))source.putArray(key);return source;}
    private static void add(ObjectNode source,String id,String note,String body){ObjectNode guidance=source.withArray("guidanceNotes").addObject().put("id",id+"-note").put("text",note).put("captureComplete",true);guidance.putArray("orderedMemberIds").add(id+"-note-member");ObjectNode scope=source.withArray("groups").addObject().put("id",id+"-scope").put("text",body).put("scopeComplete",true);scope.putArray("guidanceNoteIds").add(id+"-note");scope.putArray("memberRanges").addObject().put("memberId",id+"-body").put("channel","text").put("start",0).put("end",body.length());addMember(source,id+"-note-member",note);addMember(source,id+"-body",body);}
    private static void addMember(ObjectNode source,String id,String text){ObjectNode member=source.withArray("sourceMembers").addObject().put("id",id).put("identityStatus","source_bound");member.putObject("textObservation").put("text",text);member.putObject("sourcePosition").put("sourceSha256",source.path("sourceSha256").asText()).put("partName","word/document.xml").put("nodePath","/current/moved/"+id);}
    private static JsonNode member(ObjectNode source,String id){for(JsonNode member:source.path("sourceMembers"))if(id.equals(member.path("id").asText()))return member;throw new AssertionError(id);}
    private static ObjectNode scope(ObjectNode source,String id){for(JsonNode scope:source.path("groups"))if((id+"-scope").equals(scope.path("id").asText()))return (ObjectNode)scope;throw new AssertionError(id);}
    private static Map<String,Object> task(ObjectNode source,String id){return obj("taskId",id+"-task","sourceId","source","sourceSha256",source.path("sourceSha256").asText(),"sourceObservationId",id+"-note","itemKind","guidance");}
    private static Map<String,Object> scopePacket(ObjectNode source,String id){JsonNode scope=scope(source,id);return obj("id",scope.path("id").asText(),"text",scope.path("text").asText(),"scopeComplete",scope.path("scopeComplete").asBoolean(),"observation",scope);}
    private static Map<String,Object> context(ObjectNode source,String id){JsonNode note=null;for(JsonNode row:source.path("guidanceNotes"))if((id+"-note").equals(row.path("id").asText()))note=row;assertNotNull(note);return obj("instruction",obj("id",note.path("id").asText(),"text",note.path("text").asText()),"scopeCandidates",new ArrayList<>(Collections.singletonList(scopePacket(source,id))),"sourceConditions",Collections.emptyList(),"unresolvedConditionIds",Collections.emptyList());}
    private static JsonNode align(ObjectNode source,String id){return tree(DraftCurrentTemplateAlignment.index(source).align(task(source,id),context(source,id)));}
    private static JsonNode packet(ObjectNode source){return tree(DraftDiscoveryGroups.payload(Collections.singletonMap("source",source),DraftReviewDiscovery.sourceTasks(Collections.singletonList(source)),Collections.emptyList()));}
    private static void pinNote(ObjectNode source,String id,int ordinal){JsonNode member=member(source,id+"-note-member");ObjectNode paragraph=source.withArray("paragraphs").addObject().put("id",id+"-p").put("ordinal",ordinal).put("sourceSha256",source.path("sourceSha256").asText()).put("text",member.path("textObservation").path("text").asText());paragraph.set("sourcePosition",member.path("sourcePosition").deepCopy());}
    private static Set<String> actions(JsonNode result){Set<String> ids=new HashSet<>();for(JsonNode action:result.path("fixedActionReferences"))ids.add(action.path("actionId").asText());return ids;}
    private static Set<String> strings(JsonNode values){Set<String> result=new HashSet<>();for(JsonNode value:values)result.add(value.asText());return result;}
    private static String at(int ordinal){return DraftBusinessRules.standardParagraph("NTT",ordinal);}
    private static String wtoBody(){return String.join("\n",at(435),at(436),at(438),at(445),at(453),at(461),"(e) "+at(469));}
    private static String paragraphs(int start,int end){List<String> values=new ArrayList<>();for(int ordinal=start;ordinal<=end;ordinal++)values.add(at(ordinal));return String.join("\n",values);}
    private static JsonNode tree(Object value){return JsonUtils.mapper().valueToTree(value);}
}
