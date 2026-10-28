package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Real Auto wiring with synthetic bound readings; no product API, real model or database calls. */
class DraftScopeConditionRepairIntegrationTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a"));
    private static final String RULE="bq_return",STOP="scope_rule_condition_reference_unresolved";

    @Test void omittedTwoEnvelopeDependencyReceivesOneSameIdRepairAndDoesNotSurviveInTheFinalDraft() {
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source()));String[] firstRaw={null},twoAlias={null};int[] calls={0};
        Map<String,Object> receipt=DraftAutomaticDiscovery.processGroup((system,user)->{
            JsonNode packet=JsonUtils.parse(user);int call=++calls[0];assertTrue(call<=2,"Only one bounded condition repair is permitted");
            if(call==1){JsonNode reference=packet.path("instructions").path(0).path("fixedActionReferences").path(0);assertTrue(reference.path("reviewOnly").asBoolean());assertEquals(4,reference.path("referenceCases").size());twoAlias[0]=alias(packet,"twoEnvelopeTendering");firstRaw[0]=reply(packet,false);return firstRaw[0];}
            JsonNode repair=conditionRequest(packet.path("supplement").path("repairRequests"));assertEquals(RULE,repair.path("ruleIds").path(0).asText());assertEquals(twoAlias[0],repair.path("missingReferenceInputAliases").path(0).asText());
            JsonNode prior=packet.path("priorDraft").path("scopeRules").path(0);assertEquals(RULE,prior.path("id").asText());assertEquals(1,prior.path("when").path("clauses").size());
            return reply(packet.path("group"),true);
        },plan,DraftAutomaticDiscovery.planGroups(plan).get(0),true);
        assertEquals(2,calls[0]);assertEquals(firstRaw[0],receipt.get("rawResponse"));assertEquals(1,list(receipt.get("evidenceRounds")).size());
        JsonNode kept=tree(receipt.get("retainedResponse"));assertEquals(2,kept.path("scopeRules").size());assertEquals(RULE,kept.path("scopeRules").path(0).path("id").asText());assertEquals(2,kept.path("scopeRules").path(0).path("when").path("clauses").size());assertEquals("delete",kept.path("scopeRules").path(1).path("action").asText());
        assertTrue(receipt.containsKey("conditionReviewRequests"));assertTrue(list(receipt.get("conditionReviewRequests")).isEmpty());assertFalse(list(receipt.get("stopReasons")).contains(STOP));
        assertTrue(list(receipt.get("normalizations")).stream().map(DraftReviewGraph::map).anyMatch(row->"scope_rule_targeted_repair".equals(row.get("kind"))&&RULE.equals(row.get("scopeRuleId"))));
        Map<String,Object> node=compiledNode(receipt);assertEquals(2,tree(node.get("compiledScopeCondition")).path("clauses").size());assertEquals(2,list(receipt.get("scopeRuleCompilations")).size());
        assertEquals(1,JsonUtils.parse(firstRaw[0]).path("scopeRules").path(0).path("when").path("clauses").size(),"The failed first proposal remains original evidence");
    }

    @Test void aStillIncompleteRepairKeepsTheOriginalWhenAndTargetAsCheckThenStops() {
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source()));String[] firstRaw={null};int[] calls={0};
        Map<String,Object> receipt=DraftAutomaticDiscovery.processGroup((system,user)->{
            JsonNode packet=JsonUtils.parse(user);int call=++calls[0];assertTrue(call<=2,"A failed condition repair must not loop");
            if(call==1){firstRaw[0]=reply(packet,false);return firstRaw[0];}
            conditionRequest(packet.path("supplement").path("repairRequests"));return reply(packet.path("group"),false);
        },plan,DraftAutomaticDiscovery.planGroups(plan).get(0),true);
        assertEquals(2,calls[0]);assertEquals(firstRaw[0],receipt.get("rawResponse"));assertEquals(1,list(receipt.get("evidenceRounds")).size());assertEquals(1,list(receipt.get("conditionReviewRequests")).size());
        assertTrue(list(receipt.get("stopReasons")).contains(STOP),JsonUtils.write(receipt.get("stopReasons")));Map<String,Object> node=compiledNode(receipt),target=map(list(node.get("editTargets")).get(0));
        assertEquals("check",target.get("action"));assertEquals("keep",target.get("proposedAction"));assertEquals(true,target.get("needsRebind"));assertEquals(false,target.get("scopeComplete"));
        JsonNode originalWhen=JsonUtils.parse(firstRaw[0]).path("scopeRules").path(0).path("when");assertEquals(originalWhen,tree(node.get("compiledScopeCondition")));
        JsonNode kept=tree(receipt.get("retainedResponse"));assertEquals(1,kept.path("scopeRules").size());assertEquals(originalWhen,kept.path("scopeRules").path(0).path("when"));
        Map<String,Object> child=map(list(receipt.get("taskReceipts")).get(0));assertEquals("unresolved",map(child.get("scopeCoverage")).get("status"));assertFalse(list(map(child.get("scopeCoverage")).get("missingScopeIds")).isEmpty());
    }

    private static JsonNode conditionRequest(JsonNode requests) {
        for(JsonNode request:requests)if("scope_reference_condition_inputs_unaccounted".equals(request.path("reason").asText())){assertEquals(1,request.path("scopeIds").size());assertEquals(1,request.path("ruleIds").size());assertEquals(RULE,request.path("ruleIds").path(0).asText());return request;}
        throw new AssertionError("Missing targeted condition request: "+requests);
    }
    private static String reply(JsonNode packet,boolean includeTwo) {
        JsonNode instruction=packet.path("instructions").path(0);String owner=instruction.path("sourceTaskId").asText(),scope=instruction.path("scopeObligationIds").path(0).asText();assertFalse(owner.isEmpty());assertFalse(scope.isEmpty());
        List<Object> clauses=new ArrayList<>();clauses.add(obj("inputId",alias(packet,"electronicTendering"),"operator","eq","value","L10Pro"));if(includeTwo)clauses.add(obj("inputId",alias(packet,"twoEnvelopeTendering"),"operator","eq","value",true));
        Map<String,Object> rule=obj("id",RULE,"sourceTaskId",owner,"scopeId",scope,"when",obj("mode","all","clauses",clauses),"action","keep");List<Object> rules=new ArrayList<>();rules.add(rule);if(includeTwo)rules.add(obj("id",RULE+"_other","sourceTaskId",owner,"scopeId",scope,"when",obj("mode","all","clauses",Arrays.asList(obj("inputId",alias(packet,"electronicTendering"),"operator","eq","value","Hardcopy"),obj("inputId",alias(packet,"twoEnvelopeTendering"),"operator","eq","value",true))),"action","delete"));List<Object> outcomes=new ArrayList<>();for(JsonNode item:packet.path("instructions"))outcomes.add(obj("sourceTaskId",item.path("sourceTaskId").asText(),"status","identified"));
        return JsonUtils.write(obj("nodes",Collections.emptyList(),"requirements",Collections.emptyList(),"scopeRules",rules,"taskOutcomes",outcomes,"notes",Collections.emptyList()));
    }
    private static String alias(JsonNode packet,String key){for(JsonNode root:packet.path("knownRoots"))if(key.equals(root.path("id").asText()))return key;throw new AssertionError("Missing explicit known-root key: "+key);}
    private static Map<String,Object> compiledNode(Map<String,Object> receipt){List<?> children=list(receipt.get("taskReceipts"));assertEquals(1,children.size());return list(map(children.get(0)).get("nodes")).stream().map(DraftReviewGraph::map).filter(node->RULE.equals(node.get("compiledScopeRuleId"))).findFirst().orElseThrow(AssertionError::new);}
    private static JsonNode tree(Object value){return JsonUtils.mapper().valueToTree(value);}

    private static ObjectNode source() {
        String note=DraftBusinessRules.standardParagraph("NTT",172)+"\n"+DraftBusinessRules.standardParagraph("NTT",173),body=DraftBusinessRules.standardParagraph("NTT",166);assertFalse(body.isEmpty());
        ObjectNode reading=JsonUtils.mapper().createObjectNode().put("id","one").put("sourceDocumentId",1).put("fileKey","NTT").put("format","docx").put("sourceSha256",SHA).put("evidenceStatus","bound").put("analysisRevision","analysis-one").put("analysisInputSha256",SHA).put("analysisSha256",SHA);
        ObjectNode noteRow=reading.putArray("guidanceNotes").addObject().put("id","one-note").put("text",note).put("captureComplete",true);noteRow.putArray("orderedMemberIds").add("note-member");noteRow.putArray("memberRanges").addObject().put("memberId","note-member").put("channel","text").put("start",0).put("end",note.length());
        ObjectNode scope=reading.putArray("groups").addObject().put("id","one-scope").put("text",body).put("scopeComplete",true);scope.putArray("guidanceNoteIds").add("one-note");scope.putArray("memberRanges").addObject().put("memberId","body-member").put("channel","text").put("start",0).put("end",body.length());
        reading.putArray("sourceMembers");reading.putArray("paragraphs");member(reading,"note-member",note,"/current/note");member(reading,"body-member",body,"/current/body");return reading;
    }
    private static void member(ObjectNode reading,String id,String text,String path) {
        ObjectNode member=reading.withArray("sourceMembers").addObject().put("id",id).put("identityStatus","source_bound");member.putObject("textObservation").put("text",text);member.putObject("sourcePosition").put("sourceSha256",SHA).put("partName","word/document.xml").put("nodePath",path);
        ObjectNode paragraph=reading.withArray("paragraphs").addObject().put("id","p-"+id).put("text",text).put("sourceSha256",SHA).put("paragraphSha256",DraftAdoption.hash(text));paragraph.set("sourcePosition",member.path("sourcePosition").deepCopy());
    }
}
