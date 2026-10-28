package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.consense.service.drafting.DraftReviewGraph.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exact registered wording with synthetic bound native observations; no parser, model or product calls. */
class DraftSctDomesticDefinitionReferenceTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"b"));

    @Test void completeDomesticGuidanceSharesOnePendingRootWithoutSharingItsClauseScopes(){
        ObjectNode reading=source("SCT");for(int paragraph:Arrays.asList(547,859,895))add(reading,"note-"+paragraph,at(paragraph),"Current separately bound clause "+paragraph+".");add(reading,"unrelated","Confirm the site inspection arrangements.","An independent source clause.");
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(reading));List<?> roots=list(plan.descriptor().get("knownRoots"));assertEquals(1,roots.size());assertEquals("domesticBlocks",map(roots.get(0)).get("baselineKey"));
        Set<String> owners=new HashSet<>();for(int paragraph:Arrays.asList(547,859,895)){
            Map<String,Object> task=task(plan,"note-"+paragraph);owners.add(text(task.get("taskId")));List<Object> visible=DraftAutomaticDiscovery.knownRootPacket(plan,Collections.singleton(task.get("taskId")));assertEquals(1,visible.size());assertEquals("domesticBlocks",map(visible.get(0)).get("id"));assertEquals(Collections.singletonList(task.get("taskId")),map(visible.get(0)).get("sourceTaskIds"));
            Map<String,Object> packet=packet(reading,task),alignment=alignment(packet);assertEquals(Collections.singletonList("domesticBlocks"),alignment.get("fixedRootKeys"));assertEquals(Collections.singletonList("domesticBlocks"),packet.get("fixedRootRouteCandidates"));assertFalse(packet.toString().contains("An independent source clause."));
        }
        assertEquals(owners,new HashSet<>(list(map(roots.get(0)).get("taskIds"))));assertTrue(DraftAutomaticDiscovery.knownRootPacket(plan,Collections.singleton(task(plan,"unrelated").get("taskId"))).isEmpty(),"A same-source note without this proof must not see the root");
        Map<String,Object> root=DraftAutomaticDiscovery.receiptNodes(plan,Collections.emptyList()).get(0);assertEquals("pending",root.get("status"));assertEquals(false,map(root.get("catalogAlignment")).get("confirmed"));assertTrue(list(root.get("editTargets")).isEmpty());assertFalse(root.containsKey("adoptedAnswer"));
    }

    @Test void theNewSct4ReferenceStillRequiresModelAndHumanConditionAndScopeReview(){
        ObjectNode reading=source("SCT");add(reading,"domestic",at(547),at(550)+"\n"+at(554));DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(reading));Map<String,Object> packet=packet(reading,task(plan,"domestic")),alignment=alignment(packet);
        assertEquals("definition_reference_only",alignment.get("definitionStatus"));assertEquals("unknown",alignment.get("conditionStatus"));assertEquals("requires_review",alignment.get("scopeStatus"));assertEquals(false,alignment.get("editableAuthority"));assertEquals(true,alignment.get("modelRequired"));assertTrue(list(alignment.get("fixedActionReferences")).isEmpty());assertTrue(list(map(list(packet.get("instructions")).get(0)).get("fixedActionReferences")).isEmpty());
        JsonNode context=JsonUtils.mapper().valueToTree(packet.get("fixedContext"));assertEquals(1,context.path("fields").size());assertEquals("domesticBlocks",context.path("fields").path(0).path("key").asText());assertFalse(context.toString().contains("sourceContext"));assertFalse(context.toString().contains("sourcePosition"));
    }

    @Test void changedSubjectsTruncationAndMissingNativeProofDoNotGainTheReference(){
        for(String defect:Arrays.asList("subject","truncated","incomplete","missing-native","stale-native","different-family")){
            ObjectNode reading=source("different-family".equals(defect)?"NTT":"SCT");String note=at(547);if("subject".equals(defect))note=note.replace("domestic block(s)","commercial block(s)");if("truncated".equals(defect))note=note.substring(0,note.indexOf("Mark it"));add(reading,"note",note,"Current source body.");ObjectNode observation=(ObjectNode)reading.path("guidanceNotes").path(0);
            if("incomplete".equals(defect))observation.put("captureComplete",false);if("missing-native".equals(defect)){observation.remove("memberRanges");observation.remove("orderedMemberIds");}if("stale-native".equals(defect))((ObjectNode)reading.path("sourceMembers").path(0).path("sourcePosition")).put("sourceSha256",String.join("",Collections.nCopies(64,"a")));
            DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(reading));Map<String,Object> packet=packet(reading,task(plan,"note"));assertTrue(list(alignment(packet).get("fixedRootKeys")).isEmpty(),defect);assertTrue(DraftAutomaticDiscovery.knownRootPacket(plan,Collections.singleton(task(plan,"note").get("taskId"))).isEmpty(),defect);
        }
    }

    @Test void precastOptionLabelsDoNotBecomeTheDomesticFactOrTheSeparateNttTenderBasis(){
        ObjectNode reading=source("SCT");add(reading,"without",at(573),at(572));add(reading,"with",at(576),at(575));DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(reading));for(String id:Arrays.asList("without","with")){Map<String,Object> packet=packet(reading,task(plan,id));assertTrue(list(alignment(packet).get("fixedRootKeys")).isEmpty());assertTrue(DraftAutomaticDiscovery.knownRootPacket(plan,Collections.singleton(task(plan,id).get("taskId"))).isEmpty());}
        Map<String,Object> decisions=DraftBusinessRules.plan(obj("domesticBlocks",false,"precastFacadeTenderBasis",true,"precastFacadePermission",false));assertEquals("retain",action(decisions,"NTT-1E-FACADE").get("action"));assertEquals("not_used",action(decisions,"sct-precast-payment-clause").get("action"));assertEquals("not_used",action(decisions,"sct-domestic-entire-clause").get("action"));
        Map<String,Object> permission=DraftBusinessRules.plan(obj("domesticBlocks",false,"precastFacadeTenderBasis",false,"precastFacadePermission",true));assertEquals("delete",action(permission,"NTT-1E-FACADE").get("action"));assertEquals("retain",action(permission,"sct-precast-payment-clause").get("action"));assertEquals("not_used",action(permission,"sct-domestic-entire-clause").get("action"));
    }

    private static Map<String,Object> action(Map<String,Object> plan,String id){return list(plan.get("actions")).stream().map(DraftReviewGraph::map).filter(action->id.equals(action.get("id"))).findFirst().orElseThrow(AssertionError::new);}
    private static String at(int paragraph){String value=DraftBusinessRules.standardParagraph("SCT",paragraph);assertFalse(value.isEmpty());return value;}
    private static Map<String,Object> alignment(Map<String,Object> packet){return map(map(list(packet.get("instructions")).get(0)).get("currentTemplateAlignment"));}
    private static Map<String,Object> packet(ObjectNode reading,Map<String,Object> task){return DraftDiscoveryGroups.payload(Collections.singletonMap("24",reading),Collections.singletonList(task),Collections.emptyList());}
    private static Map<String,Object> task(DraftAutomaticDiscovery.Plan plan,String id){return plan.tasks().stream().filter(task->id.equals(task.get("sourceObservationId"))).findFirst().orElseThrow(AssertionError::new);}
    private static ObjectNode source(String family){ObjectNode reading=JsonUtils.mapper().createObjectNode().put("id","24").put("sourceDocumentId",24L).put("fileKey",family).put("sourceSha256",SHA).put("evidenceStatus","bound").put("format","docx").put("analysisRevision","test-current-analysis").put("analysisInputSha256",SHA).put("analysisSha256",SHA);for(String key:Arrays.asList("guidanceNotes","sourceMembers","paragraphs","groups","conditions","markerCandidates"))reading.putArray(key);return reading;}
    private static void add(ObjectNode reading,String id,String text,String body){ObjectNode note=reading.withArray("guidanceNotes").addObject().put("id",id).put("text",text).put("captureComplete",true);note.putArray("orderedMemberIds").add(id+"-member");note.putArray("memberRanges").addObject().put("memberId",id+"-member").put("channel","text").put("start",0).put("end",text.length());member(reading,id+"-member",text);member(reading,id+"-body",body);ObjectNode scope=reading.withArray("groups").addObject().put("id",id+"-scope").put("text",body).put("scopeComplete",true);scope.putArray("guidanceNoteIds").add(id);scope.putArray("memberRanges").addObject().put("memberId",id+"-body").put("channel","text").put("start",0).put("end",body.length());}
    private static void member(ObjectNode reading,String id,String text){ObjectNode member=reading.withArray("sourceMembers").addObject().put("id",id).put("identityStatus","source_bound");member.putObject("textObservation").put("text",text);member.putObject("sourcePosition").put("sourceSha256",reading.path("sourceSha256").asText()).put("partName","word/document.xml").put("nodePath","/current/"+id);}
}
