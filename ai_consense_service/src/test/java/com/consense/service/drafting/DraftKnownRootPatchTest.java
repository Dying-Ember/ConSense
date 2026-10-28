package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Synthetic contracts only; no source parsing, product API, model or browser execution. */
class DraftKnownRootPatchTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a"));

    @Test void currentRootSurvivesNoModelNodesWithoutClaimingModelCompletion() {
        ObjectNode source=source("one");List<JsonNode> readings=Collections.singletonList(source);
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(readings);
        Map<String,Object> result=DraftAutomaticDiscovery.finish(graph(readings),plan,Collections.emptyList());
        Map<String,Object> root=excision(nodes(result));
        assertEquals("pending",root.get("status"));assertFalse(Boolean.TRUE.equals(map(root.get("catalogAlignment")).get("confirmed")));
        assertTrue(list(root.get("editTargets")).isEmpty());assertFalse(list(plan.descriptor().get("knownRoots")).isEmpty());
        assertEquals("partial",map(state(result).get("automaticDiscovery")).get("scanStatus"));
        assertEquals(0,map(state(result).get("automaticDiscovery")).get("candidateCount"));
    }

    @Test void twoTasksFoldRulesOntoOneCoreWithoutRewritingReceipts() {
        List<JsonNode> readings=Arrays.asList(source("one"),source("two"));DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(readings);
        Map<String,Object> seed=excision(DraftAutomaticDiscovery.receiptNodes(plan,Collections.emptyList()));String id=text(seed.get("id"));
        Map<String,Object> a=patch(seed,plan.tasks().get(0),"group-a"),b=patch(seed,plan.tasks().get(1),"group-b");
        List<Map<String,Object>> receipts=Arrays.asList(receipt(plan.tasks().get(0),a),receipt(plan.tasks().get(1),b));String before=JsonUtils.write(receipts);
        Map<String,Object> merged=excision(DraftAutomaticDiscovery.receiptNodes(plan,receipts));
        assertEquals(id,merged.get("id"));assertEquals(seed.get("questionEn"),merged.get("questionEn"));
        assertEquals(2,list(map(merged.get("correction")).get("branches")).size());assertEquals(2,list(merged.get("editTargets")).size());
        assertEquals(before,JsonUtils.write(receipts));assertTrue(list(seed.get("editTargets")).isEmpty());
    }

    @Test void sameSourceReadOnlyAliasCannotAdvertiseRulePatchPermission() {
        ObjectNode reading=source("one");String quote="Review this separate current scope without repeating its business question.";
        ObjectNode note=reading.withArray("guidanceNotes").addObject().put("id","read-only-note").put("text",quote).put("captureComplete",true);
        note.putArray("guidanceNoteIds").add("one-note");
        note.putArray("orderedMemberIds").add("read-only-member");note.putArray("memberRanges").addObject().put("memberId","read-only-member").put("channel","text").put("start",0).put("end",quote.length());member(reading,"read-only-member",quote,"/current/read-only");
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(reading));
        String reader=text(plan.tasks().stream().filter(task->"read-only-note".equals(task.get("sourceObservationId"))).findFirst().orElseThrow(AssertionError::new).get("taskId"));
        String owner=text(plan.tasks().stream().filter(task->"one-note".equals(task.get("sourceObservationId"))).findFirst().orElseThrow(AssertionError::new).get("taskId"));
        List<Object> readOnly=DraftAutomaticDiscovery.knownRootPacket(plan,Collections.singletonList(reader));assertFalse(readOnly.isEmpty());
        for(Object raw:readOnly){Map<String,Object> root=map(raw);assertTrue(list(root.get("sourceTaskIds")).isEmpty());assertEquals(false,root.get("rulePatchAllowed"));}
        List<Object> writable=DraftAutomaticDiscovery.knownRootPacket(plan,Collections.singletonList(owner));assertTrue(writable.stream().anyMatch(raw->Boolean.TRUE.equals(map(raw).get("rulePatchAllowed"))));
    }

    @Test void pendingExistingRootKeepsItsRulesAndUnresolvedRecords() {
        List<JsonNode> readings=Collections.singletonList(source("one"));DraftAutomaticDiscovery.Plan initial=DraftAutomaticDiscovery.plan(readings);
        Map<String,Object> base=graph(readings),seed=excision(DraftAutomaticDiscovery.receiptNodes(initial,Collections.emptyList()));
        Map<String,Object> prior=patch(seed,initial.tasks().get(0),"earlier");state(base).put("variables",Collections.singletonList(prior));
        state(base).put("automaticDiscoveryIssues",Collections.singletonList(obj("reason","earlier_range_unresolved")));
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(readings,base);Map<String,Object> canonical=excision(DraftAutomaticDiscovery.receiptNodes(plan,Collections.emptyList()));
        Map<String,Object> proposal=patch(canonical,plan.tasks().get(0),"later");String before=JsonUtils.write(base);
        Map<String,Object> result=DraftAutomaticDiscovery.finish(base,plan,Collections.singletonList(receipt(plan.tasks().get(0),proposal)));
        Map<String,Object> kept=excision(nodes(result));assertEquals(prior.get("id"),kept.get("id"));assertEquals(2,list(kept.get("editTargets")).size());
        assertTrue(JsonUtils.write(state(result).get("automaticDiscoveryIssues")).contains("earlier_range_unresolved"));assertEquals(before,JsonUtils.write(base));
    }

    @Test void manualAndConfirmedRootsRemainUnchangedAndNewRulesAreExplicitlyUnapplied() {
        for(boolean confirmed:Arrays.asList(false,true)){
        List<JsonNode> readings=Collections.singletonList(source("one"));DraftAutomaticDiscovery.Plan initial=DraftAutomaticDiscovery.plan(readings);
        Map<String,Object> base=graph(readings),manual=excision(DraftAutomaticDiscovery.receiptNodes(initial,Collections.emptyList()));
        manual.put("manual",!confirmed);if(confirmed)manual.put("status","confirmed");manual.put("questionEn","Human-reviewed business question");state(base).put("variables",Collections.singletonList(manual));
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(readings,base);Map<String,Object> canonical=excision(DraftAutomaticDiscovery.receiptNodes(plan,Collections.emptyList()));
        Map<String,Object> proposal=patch(canonical,plan.tasks().get(0),"new-rule"),receipt=receipt(plan.tasks().get(0),proposal);String originalReceipt=JsonUtils.write(receipt);
        Map<String,Object> result=DraftAutomaticDiscovery.finish(base,plan,Collections.singletonList(receipt));
        assertEquals(manual,excision(nodes(result)));assertTrue(JsonUtils.write(state(result).get("automaticDiscoveryIssues")).contains("existing_reviewed_root_rule_patch_not_applied"));
        assertEquals(originalReceipt,JsonUtils.write(receipt));}
    }

    @Test void immutableCoreOrUnauthorisedOwnerCannotUseKnownRootException() {
        List<JsonNode> readings=Arrays.asList(source("one"),source("two"));DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(readings);
        Map<String,Object> seed=excision(DraftAutomaticDiscovery.receiptNodes(plan,Collections.emptyList()));Map<String,Object> changed=patch(seed,plan.tasks().get(0),"bad");changed.put("questionEn","A different fact");
        assertThrows(RuntimeException.class,()->DraftAutomaticDiscovery.receiptNodes(plan,Collections.singletonList(receipt(plan.tasks().get(0),changed))));
        Map<String,Object> wrongOwner=patch(seed,plan.tasks().get(1),"wrong-owner");
        assertThrows(RuntimeException.class,()->DraftAutomaticDiscovery.receiptNodes(plan,Collections.singletonList(receipt(plan.tasks().get(0),wrongOwner))));
    }

    @Test void ordinaryDuplicateAndConflictingKnownRuleIdsStillFail() {
        List<JsonNode> readings=Arrays.asList(source("one"),source("two"));DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(readings);
        Map<String,Object> ordinary=obj("id","ordinary-duplicate");
        assertThrows(RuntimeException.class,()->DraftAutomaticDiscovery.receiptNodes(plan,Arrays.asList(receipt(plan.tasks().get(0),ordinary),receipt(plan.tasks().get(1),ordinary))));
        Map<String,Object> seed=excision(DraftAutomaticDiscovery.receiptNodes(plan,Collections.emptyList())),a=patch(seed,plan.tasks().get(0),"same-id"),b=patch(seed,plan.tasks().get(1),"same-id");
        map(list(map(b.get("correction")).get("branches")).get(0)).put("effectEn","Conflicting proposed treatment");
        assertThrows(RuntimeException.class,()->DraftAutomaticDiscovery.receiptNodes(plan,Arrays.asList(receipt(plan.tasks().get(0),a),receipt(plan.tasks().get(1),b))));
    }

    private static Map<String,Object> patch(Map<String,Object> root,Map<String,Object> task,String group) {
        Map<String,Object> node=JsonUtils.readMap(JsonUtils.write(root));String id=text(root.get("id")),branch=group+"-b1";
        node.put("sourceTaskId",task.get("taskId"));node.put("modelLocalId","worksSubjectToExcision");
        node.put("correction",obj("kind","rule","branches",Collections.singletonList(obj("id",branch,"mode","all","clauses",Collections.singletonList(obj("inputId",id,"operator","eq","value",true)))),"inputIds",Collections.singletonList(id),"needsRebind",true));
        node.put("relatedInputIds",Collections.singletonList(id));
        node.put("editTargets",Collections.singletonList(obj("id",group+"-target-0","docId",task.get("sourceId"),"sourceTaskId",task.get("taskId"),"sourceScopeId",task.get("sourceId")+"-scope","action","check","proposedAction","delete","branchId",branch,"text","Current local scope","needsRebind",true)));
        return node;
    }
    private static Map<String,Object> receipt(Map<String,Object> task,Map<String,Object> node){return obj("taskId",task.get("taskId"),"sourceId",task.get("sourceId"),"sourceSha256",task.get("sourceSha256"),"memberIds",task.get("memberIds"),"nodes",Collections.singletonList(node),"requirements",Collections.emptyList(),"issues",Collections.emptyList(),"status","partial");}
    private static Map<String,Object> excision(List<Map<String,Object>> nodes){return nodes.stream().filter(node->"worksSubjectToExcision".equals(map(node.get("catalogAlignment")).get("baselineKey"))).findFirst().orElseThrow(()->new AssertionError("Current guidance root missing"));}
    private static Map<String,Object> graph(List<JsonNode> readings){List<Object> sources=new ArrayList<>();for(JsonNode reading:readings)sources.add(obj("id",reading.path("id").asText(),"sourceDocumentId",reading.path("sourceDocumentId").asLong(),"fileKey","NTT","format","docx","sourceSha256",SHA,"evidenceStatus","bound","analysisRevision",reading.path("analysisRevision").asText(),"analysisInputSha256",SHA,"analysisSha256",SHA));return obj("kind","consense-template-graph-v1","sources",sources,"selectedDocuments",obj("NTT",readings.get(0).path("id").asText()),"graph",obj("variables",new ArrayList<>(),"events",new ArrayList<>(),"blank",true));}
    private static ObjectNode source(String id){
        String note=DraftBusinessRules.standardParagraph("NTT",244),body=DraftBusinessRules.standardParagraph("NTT",243);assertFalse(note.isEmpty());
        ObjectNode reading=JsonUtils.mapper().createObjectNode().put("id",id).put("sourceDocumentId","one".equals(id)?1:2).put("fileKey","NTT").put("format","docx").put("sourceSha256",SHA).put("evidenceStatus","bound").put("analysisRevision","analysis-"+id).put("analysisInputSha256",SHA).put("analysisSha256",SHA);
        ObjectNode guidance=reading.putArray("guidanceNotes").addObject().put("id",id+"-note").put("text",note).put("captureComplete",true);guidance.putArray("orderedMemberIds").add(id+"-note-member");guidance.putArray("memberRanges").addObject().put("memberId",id+"-note-member").put("channel","text").put("start",0).put("end",note.length());
        ObjectNode scope=reading.putArray("groups").addObject().put("id",id+"-scope").put("text",body).put("scopeComplete",true);scope.putArray("guidanceNoteIds").add(id+"-note");scope.putArray("memberRanges").addObject().put("memberId",id+"-body-member").put("channel","text").put("start",0).put("end",body.length());
        reading.putArray("sourceMembers");member(reading,id+"-note-member",note,"/current/note");member(reading,id+"-body-member",body,"/current/body");return reading;
    }
    private static void member(ObjectNode reading,String id,String text,String path){ObjectNode member=reading.withArray("sourceMembers").addObject().put("id",id).put("identityStatus","source_bound");member.putObject("textObservation").put("text",text);member.putObject("sourcePosition").put("sourceSha256",SHA).put("partName","word/document.xml").put("nodePath",path);}
}
