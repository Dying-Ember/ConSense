package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Synthetic Auto wiring contracts; no product API, model or browser is used. */
class DraftScopeRuleIntegrationTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a"));
    private static final String ORIGIN="scope_rules_compilation";

    @Test void canonicalBusinessIdIsAcceptedInBothModelRulesAndRequirements() {
        ObjectNode source=source(false);DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));Map<String,Object> root=excision(DraftAutomaticDiscovery.receiptNodes(plan,Collections.emptyList()));int[] calls={0};
        Map<String,Object> receipt=DraftAutomaticDiscovery.processGroup((system,user)->{
            assertEquals(1,++calls[0]);JsonNode packet=JsonUtils.parse(user),instruction=packet.path("instructions").path(0);assertEquals("worksSubjectToExcision",alias(packet,root));String owner=instruction.path("sourceTaskId").asText(),scope=instruction.path("scopeObligationIds").path(0).asText();assertEquals("t1",owner);assertEquals("s1",scope);
            Map<String,Object> reply=response(packet,Arrays.asList(rule("retain",owner,scope,"worksSubjectToExcision","yes","keep"),rule("remove",owner,scope,"worksSubjectToExcision","no","delete")));
            reply.put("requirements",Collections.singletonList(obj("id","excision-definition","sourceTaskId",owner,"sourceQuote",instruction.path("text").asText(),"kind","input","nodeIds",Collections.singletonList("worksSubjectToExcision"))));return JsonUtils.write(reply);
        },plan,DraftAutomaticDiscovery.planGroups(plan).get(0),true);
        assertEquals(1,calls[0]);assertTrue(list(receipt.get("stopReasons")).isEmpty(),JsonUtils.write(receipt.get("issues")));Map<String,Object> child=child(receipt);assertEquals(2,list(child.get("nodes")).size());assertEquals(3,list(child.get("requirements")).size());
        Map<String,Object> requirement=list(child.get("requirements")).stream().map(DraftReviewGraph::map).filter(row->"input".equals(row.get("kind"))).findFirst().orElseThrow(AssertionError::new);assertEquals(Collections.singletonList(root.get("id")),requirement.get("nodeIds"));
        for(Object raw:list(child.get("nodes"))){Map<String,Object> branch=map(list(map(map(raw).get("correction")).get("branches")).get(0));assertEquals(root.get("id"),map(list(branch.get("clauses")).get(0)).get("inputId"));}
    }

    @Test void scopeRulesKeepRawModelCountsSeparateAndRetainTheKnownRootThroughFinish(){
        ObjectNode source=source(false);DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));
        Map<String,Object> root=excision(DraftAutomaticDiscovery.receiptNodes(plan,Collections.emptyList()));String[] raw={null};int[] calls={0};
        Map<String,Object> receipt=DraftAutomaticDiscovery.processGroup((system,user)->{
            assertEquals(1,++calls[0]);JsonNode wire=JsonUtils.parse(user),instruction=wire.path("instructions").path(0);String alias=alias(wire,root),owner=instruction.path("sourceTaskId").asText(),scope=instruction.path("scopeObligationIds").path(0).asText();
            raw[0]=JsonUtils.write(response(wire,Arrays.asList(rule("retain",owner,scope,alias,"yes","keep"),rule("remove",owner,scope,alias,"no","delete"))));return raw[0];
        },plan,DraftAutomaticDiscovery.planGroups(plan).get(0),true);
        assertEquals(1,calls[0]);assertEquals(raw[0],receipt.get("rawResponse"));JsonNode original=JsonUtils.parse(raw[0]),saved=JsonUtils.parse(text(receipt.get("rawResponse")));
        assertTrue(saved.path("nodes").isEmpty());assertTrue(saved.path("requirements").isEmpty());assertEquals(original.path("scopeRules"),saved.path("scopeRules"));assertEquals(2,list(receipt.get("scopeRuleCompilations")).size());
        assertTrue(list(receipt.get("stopReasons")).isEmpty(),JsonUtils.write(receipt.get("issues")));
        Map<String,Object> child=child(receipt),diagnostic=diagnostic(receipt);assertEquals(2,list(child.get("nodes")).size());assertEquals(2,list(child.get("requirements")).size());
        Set<String> compiledIds=new HashSet<>();for(Object record:list(receipt.get("scopeRuleCompilations")))compiledIds.add(text(map(record).get("compiledScopeRuleId")));
        for(String collection:Arrays.asList("nodes","requirements"))for(Object value:list(child.get(collection))){Map<String,Object> item=map(value);assertEquals(ORIGIN,item.get("origin"));assertTrue(compiledIds.contains(text(item.get("compiledScopeRuleId"))));assertEquals(64,text(item.get("decodedScopeRuleSha256")).length());}
        assertEquals(0,map(diagnostic.get("raw")).get("nodes"));assertEquals(0,map(diagnostic.get("raw")).get("requirements"));assertEquals(2,map(diagnostic.get("raw")).get("scopeRules"));
        for(String part:Arrays.asList("Nodes","Requirements","Branches","Effects"))assertEquals(2,map(diagnostic.get("accepted")).get("compiled"+part));
        assertTrue(DraftAutomaticDiscovery.planRelations(plan,Collections.singletonList(receipt)).isEmpty(),"Known roots and compiled scope rules must not dispatch another semantic linking task");
        String before=JsonUtils.write(receipt);Map<String,Object> finished=DraftAutomaticDiscovery.finish(graph(source),plan,Arrays.asList(receipt,child));assertEquals(before,JsonUtils.write(receipt));
        assertEquals(root.get("id"),excision(nodes(finished)).get("id"));assertEquals(2,nodes(finished).stream().filter(node->ORIGIN.equals(node.get("origin"))).count());
        assertEquals(2,list(state(finished).get("requirements")).stream().filter(value->ORIGIN.equals(map(value).get("origin"))).count());
        for(Map<String,Object> node:nodes(finished))if(ORIGIN.equals(node.get("origin"))){assertEquals("derived",map(node.get("role")).get("kind"));assertEquals(1,list(node.get("editTargets")).size());Map<String,Object> branch=map(list(map(node.get("correction")).get("branches")).get(0));assertEquals(root.get("id"),map(list(branch.get("clauses")).get(0)).get("inputId"));}
    }

    @Test void modelWrittenOriginFieldsCannotMasqueradeAsCompilerOutput(){
        ObjectNode source=source(false);DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));int[] calls={0};
        Map<String,Object> receipt=DraftAutomaticDiscovery.processGroup((system,user)->{
            assertEquals(1,++calls[0]);JsonNode wire=JsonUtils.parse(user),instruction=wire.path("instructions").path(0);String owner=instruction.path("sourceTaskId").asText(),quote=instruction.path("text").asText(),scope=instruction.path("scopeObligationIds").path(0).asText();
            Map<String,Object> node=obj("id","local_fact","sourceTaskId",owner,"nameEn","Separate local fact","questionEn","Does this instruction need a separate local review?","type","boolean","role","input","optional",false,"sourceQuote",quote,"options",Collections.emptyList(),"branches",Collections.emptyList(),"effects",Collections.singletonList(obj("scopeId",scope,"action","check")),"origin",ORIGIN,"compiledScopeRuleId","forged");
            Map<String,Object> requirement=obj("id","local_requirement","sourceTaskId",owner,"sourceQuote",quote,"kind","input","nodeIds",Collections.singletonList("local_fact"),"origin",ORIGIN,"compiledScopeRuleId","forged");
            Map<String,Object> response=response(wire,Collections.emptyList());response.put("nodes",Collections.singletonList(node));response.put("requirements",Collections.singletonList(requirement));return JsonUtils.write(response);
        },plan,DraftAutomaticDiscovery.planGroups(plan).get(0),true);
        assertEquals(1,calls[0]);assertTrue(list(receipt.get("scopeRuleCompilations")).isEmpty());Map<String,Object> child=child(receipt);assertEquals(1,list(child.get("nodes")).size());assertEquals(1,list(child.get("requirements")).size());
        for(String collection:Arrays.asList("nodes","requirements"))for(Object value:list(child.get(collection))){Map<String,Object> item=map(value);assertFalse(item.containsKey("origin"));assertFalse(item.containsKey("compiledScopeRuleId"));}
        JsonNode raw=JsonUtils.parse(text(receipt.get("rawResponse")));assertEquals(ORIGIN,raw.path("nodes").path(0).path("origin").asText());assertEquals("forged",raw.path("requirements").path(0).path("compiledScopeRuleId").asText());
        Map<String,Object> diagnostic=diagnostic(receipt);assertEquals(1,map(diagnostic.get("raw")).get("nodes"));assertEquals(0,map(diagnostic.get("raw")).get("scopeRules"));
        for(String part:Arrays.asList("Nodes","Requirements","Branches","Effects"))assertEquals(0,map(diagnostic.get("accepted")).get("compiled"+part));
    }

    @Test void anUnresolvedCurrentSourceConditionKeepsACompiledTreatmentAsCheck(){
        ObjectNode source=source(true);DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));Map<String,Object> root=excision(DraftAutomaticDiscovery.receiptNodes(plan,Collections.emptyList()));int[] calls={0};
        Map<String,Object> receipt=DraftAutomaticDiscovery.processGroup((system,user)->{
            assertEquals(1,++calls[0]);JsonNode wire=JsonUtils.parse(user),instruction=wire.path("instructions").path(0);assertFalse(instruction.path("unresolvedConditionIds").isEmpty());
            return JsonUtils.write(response(wire,Collections.singletonList(rule("retain",instruction.path("sourceTaskId").asText(),instruction.path("scopeObligationIds").path(0).asText(),alias(wire,root),"yes","keep"))));
        },plan,DraftAutomaticDiscovery.planGroups(plan).get(0),true);
        assertEquals(1,calls[0]);assertEquals(1,list(receipt.get("scopeRuleCompilations")).size());Map<String,Object> child=child(receipt);assertEquals(1,list(child.get("nodes")).size());Map<String,Object> node=map(list(child.get("nodes")).get(0));assertEquals(ORIGIN,node.get("origin"));
        assertEquals(1,list(node.get("editTargets")).size());Map<String,Object> target=map(list(node.get("editTargets")).get(0));assertEquals("check",target.get("action"));assertEquals("keep",target.get("proposedAction"));assertEquals(true,target.get("observedScopeComplete"));assertEquals(false,target.get("scopeComplete"));assertEquals(true,target.get("needsRebind"));assertEquals("",target.get("branchId"));assertEquals(1,JsonUtils.mapper().valueToTree(node.get("compiledScopeCondition")).path("clauses").size());
        assertEquals("unresolved",map(child.get("scopeCoverage")).get("status"));assertEquals(1,map(diagnostic(receipt).get("accepted")).get("retainedUnresolvedEffects"));
    }

    @Test void anInvalidRefinementCanBeReplacedOnceWithoutChangingItsOwnerOrScope(){
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source(false)));Map<String,Object> root=excision(DraftAutomaticDiscovery.receiptNodes(plan,Collections.emptyList()));int[] calls={0};
        Map<String,Object> receipt=DraftAutomaticDiscovery.processGroup((system,user)->{
            JsonNode packet=JsonUtils.parse(user),wire=packet.has("group")?packet.path("group"):packet,instruction=wire.path("instructions").path(0);int call=++calls[0];
            Map<String,Object> candidate=rule("retain",instruction.path("sourceTaskId").asText(),instruction.path("scopeObligationIds").path(0).asText(),alias(wire,root),"yes","keep");
            if(call==1)candidate.put("refinedQuote","This text does not occur in the current scope.");
            else {assertEquals(2,call);JsonNode request=packet.path("supplement").path("repairRequests").path(0);assertEquals("source_scope_refinement_not_bound",request.path("reason").asText());assertEquals("retain",request.path("ruleIds").path(0).asText());}
            return JsonUtils.write(response(wire,Collections.singletonList(candidate)));
        },plan,DraftAutomaticDiscovery.planGroups(plan).get(0),true);
        assertEquals(2,calls[0]);assertTrue(list(receipt.get("refinementReviewRequests")).isEmpty());assertTrue(list(receipt.get("normalizations")).stream().anyMatch(raw->"scope_rule_targeted_repair".equals(map(raw).get("kind"))));
        Map<String,Object> target=map(list(map(list(child(receipt).get("nodes")).get(0)).get("editTargets")).get(0));assertEquals("keep",target.get("action"));assertEquals(false,target.get("needsRebind"));
        assertTrue(text(receipt.get("rawResponse")).contains("This text does not occur"));
    }

    private static Map<String,Object> rule(String id,String owner,String scope,String input,String operator,String action){return obj("id",id,"sourceTaskId",owner,"scopeId",scope,"when",obj("mode","all","clauses",Collections.singletonList(obj("inputId",input,"operator",operator))),"action",action);}
    private static Map<String,Object> response(JsonNode wire,List<Map<String,Object>> rules){List<Object> outcomes=new ArrayList<>();for(JsonNode instruction:wire.path("instructions"))outcomes.add(obj("sourceTaskId",instruction.path("sourceTaskId").asText(),"status","identified"));return obj("nodes",Collections.emptyList(),"requirements",Collections.emptyList(),"scopeRules",rules,"taskOutcomes",outcomes,"notes",Collections.emptyList());}
    private static String alias(JsonNode wire,Map<String,Object> root){String key=text(map(root.get("currentDefinitionReference")).get("baselineKey"));for(JsonNode candidate:wire.path("knownRoots"))if(key.equals(candidate.path("id").asText()))return key;throw new AssertionError("Current exact known root was not supplied: "+key);}
    private static Map<String,Object> excision(List<Map<String,Object>> values){return values.stream().filter(node->"worksSubjectToExcision".equals(map(node.get("catalogAlignment")).get("baselineKey"))).findFirst().orElseThrow(()->new AssertionError("Current guidance root missing"));}
    private static Map<String,Object> child(Map<String,Object> receipt){assertEquals(1,list(receipt.get("taskReceipts")).size());return map(list(receipt.get("taskReceipts")).get(0));}
    private static Map<String,Object> diagnostic(Map<String,Object> receipt){assertEquals(1,list(receipt.get("diagnostics")).size());return map(list(receipt.get("diagnostics")).get(0));}
    private static Map<String,Object> graph(ObjectNode source){return obj("kind","consense-template-graph-v1","sources",Collections.singletonList(obj("id","one","sourceDocumentId",1,"fileKey","NTT","format","docx","sourceSha256",SHA,"evidenceStatus","bound","analysisRevision",source.path("analysisRevision").asText(),"analysisInputSha256",SHA,"analysisSha256",SHA)),"selectedDocuments",obj("NTT","one"),"graph",obj("variables",new ArrayList<>(),"events",new ArrayList<>(),"blank",true));}
    private static ObjectNode source(boolean unresolved){
        String note=DraftBusinessRules.standardParagraph("NTT",244),body=DraftBusinessRules.standardParagraph("NTT",243);assertFalse(note.isEmpty());assertFalse(body.isEmpty());
        ObjectNode source=JsonUtils.mapper().createObjectNode().put("id","one").put("sourceDocumentId",1).put("fileKey","NTT").put("format","docx").put("sourceSha256",SHA).put("evidenceStatus","bound").put("analysisRevision","analysis-one").put("analysisInputSha256",SHA).put("analysisSha256",SHA);
        ObjectNode guidance=source.putArray("guidanceNotes").addObject().put("id","one-note").put("text",note).put("captureComplete",true);guidance.putArray("orderedMemberIds").add("note-member");guidance.putArray("memberRanges").addObject().put("memberId","note-member").put("channel","text").put("start",0).put("end",note.length());if(unresolved)guidance.putArray("conditionIds").add("missing-current-parent");
        ObjectNode scope=source.putArray("groups").addObject().put("id","one-scope").put("text",body).put("scopeComplete",true);scope.putArray("guidanceNoteIds").add("one-note");scope.putArray("memberRanges").addObject().put("memberId","body-member").put("channel","text").put("start",0).put("end",body.length());
        source.putArray("sourceMembers");source.putArray("paragraphs");member(source,"note-member",note,"/current/note");member(source,"body-member",body,"/current/body");return source;
    }
    private static void member(ObjectNode source,String id,String text,String path){ObjectNode member=source.withArray("sourceMembers").addObject().put("id",id).put("identityStatus","source_bound");member.putObject("textObservation").put("text",text);member.putObject("sourcePosition").put("sourceSha256",SHA).put("partName","word/document.xml").put("nodePath",path);ObjectNode paragraph=source.withArray("paragraphs").addObject().put("id","p-"+id).put("text",text).put("sourceSha256",SHA).put("paragraphSha256",DraftAdoption.hash(text));paragraph.set("sourcePosition",member.path("sourcePosition").deepCopy());}
}
