package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.consense.service.drafting.DraftReviewGraph.*;
import static org.junit.jupiter.api.Assertions.*;

/** Complete issue-format notes with current native proof; definition references never authorize clause edits. */
class DraftSctBqDefinitionReferenceTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"b"));
    private static final String SUBMISSION="*(4) Envelope 2 shall contain the Form of Tender, the Bills of Quantities and other pricing documents as follows:\nThe original hard copy of the Bills of Quantities completed with each item priced, extended and totaled.\nOne DVD-ROM of the priced Bills of Quantities in Excel format.";
    private static final String BOTH="The Bills of Quantities are issued in L10Pro or issued in hard copy.";

    @Test void completeIssueNotesShareOnePendingDefinitionWithoutRepeatingIssueWordsInTheirScopes(){
        ObjectNode reading=source("SCT");add(reading,"electronic",at(60,65,66),SUBMISSION);add(reading,"paper",at(231,236),SUBMISSION);add(reading,"paper-hyphen",at(518,523),SUBMISSION);add(reading,"paper-repeat",at(231,236),SUBMISSION);add(reading,"other","Check the tender submission format.",BOTH);String before=reading.toString();
        assertFalse(SUBMISSION.contains("L10Pro"));assertFalse(SUBMISSION.contains("issued in hard copy"));
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(reading));List<?> roots=list(plan.descriptor().get("knownRoots"));assertEquals(1,roots.size());assertEquals("electronicTendering",map(roots.get(0)).get("baselineKey"));Set<String> owners=new HashSet<>();
        for(String id:Arrays.asList("electronic","paper","paper-hyphen","paper-repeat")){
            Map<String,Object> task=task(plan,id);owners.add(text(task.get("taskId")));Map<String,Object> packet=packet(reading,task),alignment=alignment(packet);assertEquals(Collections.singletonList("electronicTendering"),alignment.get("fixedRootKeys"));assertEquals(Collections.singletonList("electronicTendering"),packet.get("fixedRootRouteCandidates"));assertDefinitionOnly(alignment);
            List<Object> visible=DraftAutomaticDiscovery.knownRootPacket(plan,Collections.singleton(task.get("taskId")));assertEquals(1,visible.size());assertEquals("electronicTendering",map(visible.get(0)).get("id"));assertEquals(Collections.singletonList(task.get("taskId")),map(visible.get(0)).get("sourceTaskIds"));
        }
        assertEquals(owners,new HashSet<>(list(map(roots.get(0)).get("taskIds"))));assertTrue(DraftAutomaticDiscovery.knownRootPacket(plan,Collections.singleton(task(plan,"other").get("taskId"))).isEmpty());Map<String,Object> root=DraftAutomaticDiscovery.receiptNodes(plan,Collections.emptyList()).get(0);assertEquals("pending",root.get("status"));assertEquals(false,map(root.get("catalogAlignment")).get("confirmed"));assertTrue(list(root.get("editTargets")).isEmpty());assertFalse(root.containsKey("adoptedAnswer"));assertEquals(before,reading.toString());
    }

    @Test void anExplicitIssueNoteCanExplainTheDefinitionWhileItsEditScopeRemainsUnknown(){
        for(int[] paragraphs:Arrays.asList(new int[]{60,65,66},new int[]{231,236},new int[]{518,523})){
            ObjectNode reading=source("SCT");add(reading,"issue",at(paragraphs),null);DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(reading));Map<String,Object> packet=packet(reading,task(plan,"issue"));assertDefinitionOnly(alignment(packet));assertTrue(list(alignment(packet).get("currentScopeIds")).isEmpty());assertTrue(list(map(list(packet.get("instructions")).get(0)).get("scopeObligationIds")).isEmpty());
            JsonNode context=JsonUtils.mapper().valueToTree(packet.get("fixedContext"));assertEquals(1,context.path("fields").size());assertEquals("electronicTendering",context.path("fields").path(0).path("key").asText());assertFalse(context.toString().contains("sourcePosition"));
        }
    }

    @Test void genericSelectionsStillNeedBothIssueAlternativesInTheirOwnCurrentSourceContext(){
        for(int paragraph:Arrays.asList(55,476))for(String context:Arrays.asList("both","electronic-only","paper-only","other-owner","stale-scope")){
            ObjectNode reading=source("SCT");String body="electronic-only".equals(context)?"Bills of Quantities issued in L10Pro.":"paper-only".equals(context)?"Bills of Quantities issued in hard copy.":"other-owner".equals(context)?SUBMISSION:BOTH;add(reading,"generic",at(paragraph),body);
            if("other-owner".equals(context))add(reading,"other","Check another submission requirement.",BOTH);if("stale-scope".equals(context))((ObjectNode)reading.path("sourceMembers").path(1).path("sourcePosition")).put("sourceSha256",String.join("",Collections.nCopies(64,"a")));
            DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(reading));Map<String,Object> task=task(plan,"generic"),alignment=alignment(packet(reading,task));assertEquals("both".equals(context)?Collections.singletonList("electronicTendering"):Collections.emptyList(),alignment.get("fixedRootKeys"),paragraph+":"+context);assertEquals("both".equals(context)?1:0,DraftAutomaticDiscovery.knownRootPacket(plan,Collections.singleton(task.get("taskId"))).size(),paragraph+":"+context);
        }
    }

    @Test void submissionFormatOtherSubjectsAndUnprovenNativeNotesDoNotGainAnIssueDefinition(){
        for(String defect:Arrays.asList("submission-format","other-subject","truncated","incomplete","missing-native","stale-native","unbound","pdf","different-family")){
            ObjectNode reading=source("different-family".equals(defect)?"NTT":"SCT");String note=at(231,236);if("submission-format".equals(defect))note=note.replace("issued","submitted");if("other-subject".equals(defect))note=note.replace("BQ","drawings");if("truncated".equals(defect))note=note.substring(0,note.length()-1);add(reading,"note",note,BOTH);ObjectNode observation=(ObjectNode)reading.path("guidanceNotes").path(0);
            if("incomplete".equals(defect))observation.put("captureComplete",false);if("missing-native".equals(defect))((ObjectNode)reading.path("sourceMembers").path(0).path("sourcePosition")).remove("partName");if("stale-native".equals(defect))((ObjectNode)reading.path("sourceMembers").path(0).path("sourcePosition")).put("sourceSha256",String.join("",Collections.nCopies(64,"a")));if("unbound".equals(defect))reading.put("evidenceStatus","unknown");if("pdf".equals(defect))reading.put("format","pdf");
            DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(reading));if("unbound".equals(defect)){assertTrue(plan.tasks().isEmpty());assertTrue(list(plan.descriptor().get("knownRoots")).isEmpty());continue;}Map<String,Object> task=task(plan,"note");assertTrue(list(alignment(packet(reading,task)).get("fixedRootKeys")).isEmpty(),defect);assertTrue(DraftAutomaticDiscovery.knownRootPacket(plan,Collections.singleton(task.get("taskId"))).isEmpty(),defect);
        }
    }

    private static void assertDefinitionOnly(Map<String,Object> alignment){assertEquals("definition_reference_only",alignment.get("definitionStatus"));assertEquals("unknown",alignment.get("conditionStatus"));assertEquals("requires_review",alignment.get("scopeStatus"));assertEquals(false,alignment.get("editableAuthority"));assertEquals(true,alignment.get("modelRequired"));assertTrue(list(alignment.get("fixedActionReferences")).isEmpty());}
    private static String at(int... paragraphs){List<String> text=new ArrayList<>();for(int paragraph:paragraphs){String value=DraftBusinessRules.standardParagraph("SCT",paragraph);assertFalse(value.isEmpty());text.add(value);}return String.join("\n",text);}
    private static Map<String,Object> alignment(Map<String,Object> packet){return map(map(list(packet.get("instructions")).get(0)).get("currentTemplateAlignment"));}
    private static Map<String,Object> packet(ObjectNode reading,Map<String,Object> task){return DraftDiscoveryGroups.payload(Collections.singletonMap("24",reading),Collections.singletonList(task),Collections.emptyList());}
    private static Map<String,Object> task(DraftAutomaticDiscovery.Plan plan,String id){return plan.tasks().stream().filter(task->id.equals(task.get("sourceObservationId"))).findFirst().orElseThrow(AssertionError::new);}
    private static ObjectNode source(String family){ObjectNode reading=JsonUtils.mapper().createObjectNode().put("id","24").put("sourceDocumentId",24L).put("fileKey",family).put("sourceSha256",SHA).put("evidenceStatus","bound").put("format","docx").put("analysisRevision","test-current-analysis").put("analysisInputSha256",SHA).put("analysisSha256",SHA);for(String key:Arrays.asList("guidanceNotes","sourceMembers","paragraphs","groups","conditions","markerCandidates"))reading.putArray(key);return reading;}
    private static void add(ObjectNode reading,String id,String text,String body){ObjectNode note=reading.withArray("guidanceNotes").addObject().put("id",id).put("text",text).put("captureComplete",true).put("sourceSyntaxKnown",true).put("sourceSyntaxComplete",true);note.putArray("orderedMemberIds").add(id+"-member");note.putArray("memberRanges").addObject().put("memberId",id+"-member").put("channel","text").put("start",0).put("end",text.length());member(reading,id+"-member",text);if(body==null)return;member(reading,id+"-body",body);ObjectNode scope=reading.withArray("groups").addObject().put("id",id+"-scope").put("text",body).put("scopeComplete",true);scope.putArray("guidanceNoteIds").add(id);scope.putArray("memberRanges").addObject().put("memberId",id+"-body").put("channel","text").put("start",0).put("end",body.length());}
    private static void member(ObjectNode reading,String id,String text){ObjectNode member=reading.withArray("sourceMembers").addObject().put("id",id).put("identityStatus","source_bound");member.putObject("textObservation").put("text",text);member.putObject("sourcePosition").put("sourceSha256",reading.path("sourceSha256").asText()).put("partName","word/document.xml").put("nodePath","/current/"+id);}
}
