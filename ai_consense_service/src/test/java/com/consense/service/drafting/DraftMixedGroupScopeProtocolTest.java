package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.consense.service.drafting.DraftReviewGraph.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Auto orchestration with synthetic native evidence and deterministic replies; no model or product calls. */
class DraftMixedGroupScopeProtocolTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"b")),KEY="electronicTendering";
    private static final String PERMIT="State the project-specific access permit identifier.",MARKER="*SCT1 Tender documents and other information issued";
    private static final String ADOPTED_ANSWER="Hardcopy";

    @Test void mixedGroupCompilesReadOnlyRootRulesAndKeepsMarkerReviewAndNewAtomicFactThroughFinish(){
        Fixture fixture=new Fixture();String before=JsonUtils.write(fixture.base),rootBefore=JsonUtils.write(fixture.root);int[] calls={0};String[] raw={null};
        Map<String,Object> receipt=DraftAutomaticDiscovery.processGroup((system,user)->{
            assertEquals(1,++calls[0]);assertMixedPrompt(system,user);JsonNode wire=JsonUtils.parse(user);assertMixedPacket(wire);
            raw[0]=JsonUtils.write(reply(wire,false));return raw[0];
        },fixture.plan,fixture.group,true);
        assertEquals(1,calls[0]);assertEquals(raw[0],receipt.get("rawResponse"));assertEquals("partial",receipt.get("status"));assertTrue(list(receipt.get("stopReasons")).isEmpty(),JsonUtils.write(receipt));assertTrue(list(receipt.get("evidenceRounds")).isEmpty());
        assertEquals(2,list(receipt.get("scopeRuleCompilations")).size());
        Map<String,Object> known=child(receipt,fixture.task("bq-note")),marker=child(receipt,fixture.task("marker-start")),fresh=child(receipt,fixture.task("permit-note"));
        assertEquals(1,list(known.get("nodes")).size());assertEquals(1,list(marker.get("nodes")).size());assertEquals(1,list(fresh.get("nodes")).size());
        Map<String,Object> knownTreatment=map(list(known.get("nodes")).get(0)),markerTreatment=map(list(marker.get("nodes")).get(0)),fact=map(list(fresh.get("nodes")).get(0));
        assertEquals("scope_rules_compilation",knownTreatment.get("origin"));assertEquals("scope_rules_compilation",markerTreatment.get("origin"));assertFalse(fact.containsKey("origin"));
        JsonNode retainedCondition=JsonUtils.mapper().valueToTree(knownTreatment.get("compiledScopeCondition"));assertEquals(KEY,retainedCondition.path("clauses").path(0).path("inputId").asText());assertEquals("L10Pro",retainedCondition.path("clauses").path(0).path("value").asText());
        for(Map<String,Object> item:Arrays.asList(knownTreatment,markerTreatment,fact)){
            assertEquals("pending",item.get("status"));assertEquals(1,list(item.get("editTargets")).size());Map<String,Object> target=map(list(item.get("editTargets")).get(0));assertEquals("check",target.get("action"));assertEquals("check",target.get("proposedAction"));assertEquals("shared-scope",target.get("sourceScopeId"));assertEquals("24",target.get("docId"));
        }
        assertEquals(fixture.task("bq-note"),knownTreatment.get("sourceTaskId"));assertEquals(fixture.task("marker-start"),markerTreatment.get("sourceTaskId"));assertEquals(fixture.task("permit-note"),fact.get("sourceTaskId"));
        assertEquals("input",map(fact.get("role")).get("kind"));assertEquals("text",map(fact.get("type")).get("kind"));assertEquals(PERMIT,fact.get("semanticSourceQuote"));assertEquals(Collections.singletonList(fact.get("id")),map(list(fresh.get("requirements")).get(0)).get("nodeIds"));
        assertEquals("partial",marker.get("status"));assertTrue(list(marker.get("issues")).stream().anyMatch(value->"source_scope_treatment_unresolved".equals(map(value).get("reason"))));
        String receiptBefore=JsonUtils.write(receipt);Map<String,Object> finished=DraftAutomaticDiscovery.finish(fixture.base,fixture.plan,receipts(receipt));
        assertEquals(rootBefore,JsonUtils.write(findRoot(nodes(finished))));assertEquals(before,JsonUtils.write(fixture.base));assertEquals(receiptBefore,JsonUtils.write(receipt));
        assertEquals(1,nodes(finished).stream().filter(node->KEY.equals(map(node.get("catalogAlignment")).get("baselineKey"))).count());assertEquals(1,nodes(finished).stream().filter(node->"permit".equals(node.get("modelLocalId"))).count());assertEquals(2,nodes(finished).stream().filter(node->"scope_rules_compilation".equals(node.get("origin"))).count());
        assertEquals(ADOPTED_ANSWER,findRoot(nodes(finished)).get("adoptedAnswer"));assertEquals("partial",map(state(finished).get("automaticDiscovery")).get("scanStatus"));
    }

    @Test void anExtraBqInputStillStopsAsDuplicateAndUnmappedEvenBesideValidMixedRules(){
        Fixture fixture=new Fixture();String before=JsonUtils.write(fixture.base),rootBefore=JsonUtils.write(fixture.root);int[] calls={0};
        Map<String,Object> receipt=DraftAutomaticDiscovery.processGroup((system,user)->{
            assertEquals(1,++calls[0]);assertMixedPrompt(system,user);JsonNode wire=JsonUtils.parse(user);assertMixedPacket(wire);return JsonUtils.write(reply(wire,true));
        },fixture.plan,fixture.group,true);
        assertEquals(1,calls[0]);assertTrue(list(receipt.get("evidenceRounds")).isEmpty());assertTrue(list(receipt.get("stopReasons")).contains("supplied_business_definition_duplicated"));assertTrue(list(receipt.get("stopReasons")).contains("node_without_supported_requirement_mapping"));
        Map<String,Object> owner=child(receipt,fixture.task("bq-note"));Map<String,Object> extra=list(owner.get("nodes")).stream().map(DraftReviewGraph::map).filter(node->"n1".equals(node.get("modelLocalId"))).findFirst().orElseThrow(AssertionError::new);
        assertTrue(list(owner.get("issues")).stream().anyMatch(value->"node_without_supported_requirement_mapping".equals(map(value).get("reason"))&&extra.get("id").equals(map(value).get("nodeId"))));
        for(Object requirement:list(owner.get("requirements")))assertFalse(list(map(requirement).get("nodeIds")).contains(extra.get("id")));
        JsonNode raw=JsonUtils.parse(JsonUtils.extractJson(text(receipt.get("rawResponse"))));assertEquals(KEY,raw.path("requirements").path(1).path("nodeIds").path(0).asText());assertEquals("n1",raw.path("nodes").path(1).path("id").asText());
        Map<String,Object> finished=DraftAutomaticDiscovery.finish(fixture.base,fixture.plan,receipts(receipt));assertEquals(rootBefore,JsonUtils.write(findRoot(nodes(finished))));assertEquals(before,JsonUtils.write(fixture.base));
        assertTrue(nodes(finished).stream().anyMatch(node->"n1".equals(node.get("modelLocalId"))),"The failed candidate must remain reviewable, not be silently merged into the known root");assertEquals("partial",map(state(finished).get("automaticDiscovery")).get("scanStatus"));
    }

    @Test void aNewFactAndItsDerivedTreatmentKeepTheirDependencyAndOwnScopeThroughFinish(){
        String treatment="Retain the access permit schedule when this identifier is provided.",body="Access permit schedule for the project.";
        ObjectNode source=sourceShell();guidance(source,"permit-note",PERMIT+" "+treatment);member(source,"permit-body",body);
        ObjectNode scope=source.withArray("groups").addObject().put("id","permit-scope").put("text",body).put("scopeComplete",true);scope.putArray("guidanceNoteIds").add("permit-note");scope.putArray("memberRanges").addObject().put("memberId","permit-body").put("channel","text").put("start",0).put("end",body.length());
        Map<String,Object> base=graph(source);String before=JsonUtils.write(base);DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source),base);List<Map<String,Object>> groups=DraftAutomaticDiscovery.planGroups(plan);assertEquals(1,plan.tasks().size());assertEquals(1,groups.size());int[] calls={0};
        Map<String,Object> receipt=DraftAutomaticDiscovery.processGroup((system,user)->{
            assertEquals(1,++calls[0]);assertMixedPrompt(system,user);JsonNode wire=JsonUtils.parse(user);assertEquals(1,wire.path("instructions").size());assertTrue(wire.path("knownRoots").isEmpty());JsonNode instruction=wire.path("instructions").path(0);String owner=instruction.path("sourceTaskId").asText(),scopeId=instruction.path("scopeObligationIds").path(0).asText();assertFalse(scopeId.isEmpty());
            Map<String,Object> fact=obj("id","permit","sourceTaskId",owner,"nameEn","Access permit identifier","questionEn","What is the project access permit identifier?","type","text","role","input","optional",true,"sourceQuote",PERMIT,"options",Collections.emptyList(),"branches",Collections.emptyList(),"effects",Collections.emptyList());
            Map<String,Object> branch=obj("id","identifier_provided","mode","all","clauses",Collections.singletonList(obj("inputId","permit","operator","notempty")),"resultValue",true);
            Map<String,Object> derived=obj("id","permit_schedule","sourceTaskId",owner,"nameEn","Retain access permit schedule","type","boolean","role","derived","optional",false,"sourceQuote",treatment,"options",Collections.emptyList(),"branches",Collections.singletonList(branch),"effects",Collections.singletonList(obj("scopeId",scopeId,"action","keep","branchId","identifier_provided")));
            List<Object> requirements=Arrays.asList(obj("id","permit_req","sourceTaskId",owner,"sourceQuote",PERMIT,"kind","input","nodeIds",Collections.singletonList("permit"),"reason","The source requires an independent project permit identifier."),obj("id","schedule_req","sourceTaskId",owner,"sourceQuote",treatment,"kind","amendment","nodeIds",Collections.singletonList("permit_schedule"),"reason","The current source derives the schedule treatment from the identifier, without another manual question."));
            return JsonUtils.write(obj("nodes",Arrays.asList(fact,derived),"requirements",requirements,"scopeRules",Collections.emptyList(),"taskOutcomes",Collections.singletonList(obj("sourceTaskId",owner,"status","identified")),"notes",Collections.emptyList()));
        },plan,groups.get(0),true);
        assertEquals(1,calls[0]);assertTrue(list(receipt.get("stopReasons")).isEmpty(),JsonUtils.write(receipt));assertTrue(list(receipt.get("evidenceRounds")).isEmpty());assertTrue(list(receipt.get("scopeRuleCompilations")).isEmpty());
        String owner=text(plan.tasks().get(0).get("taskId"));Map<String,Object> child=child(receipt,owner);assertEquals("completed",child.get("status"));assertTrue(list(child.get("issues")).isEmpty(),JsonUtils.write(child));assertEquals(2,list(child.get("nodes")).size());assertEquals(2,list(child.get("requirements")).size());
        String receiptBefore=JsonUtils.write(receipt);Map<String,Object> finished=DraftAutomaticDiscovery.finish(base,plan,receipts(receipt));assertEquals(before,JsonUtils.write(base));assertEquals(receiptBefore,JsonUtils.write(receipt));assertEquals(2,nodes(finished).size());
        Map<String,Object> fact=nodes(finished).stream().filter(node->"permit".equals(node.get("modelLocalId"))).findFirst().orElseThrow(AssertionError::new),derived=nodes(finished).stream().filter(node->"permit_schedule".equals(node.get("modelLocalId"))).findFirst().orElseThrow(AssertionError::new);
        assertEquals(1,nodes(finished).stream().filter(node->"input".equals(map(node.get("role")).get("kind"))).count());assertEquals("derived",map(derived.get("role")).get("kind"));assertNotEquals("permit",fact.get("id"));assertNotEquals("permit_schedule",derived.get("id"));assertTrue(list(fact.get("editTargets")).isEmpty());
        for(Map<String,Object> node:Arrays.asList(fact,derived)){assertEquals("pending",node.get("status"));assertEquals(owner,node.get("sourceTaskId"));assertFalse(node.containsKey("adoptedAnswer"));assertFalse(node.containsKey("discoveryRejectedParts"));}
        Map<String,Object> correction=map(derived.get("correction"));assertEquals(Collections.singletonList(fact.get("id")),correction.get("inputIds"));assertEquals(Collections.singletonList(fact.get("id")),derived.get("relatedInputIds"));assertEquals(1,list(correction.get("branches")).size());Map<String,Object> branch=map(list(correction.get("branches")).get(0)),condition=map(list(branch.get("clauses")).get(0));assertEquals(fact.get("id"),condition.get("inputId"));assertNotEquals(derived.get("id"),condition.get("inputId"));assertEquals("notempty",condition.get("operator"));assertEquals("true",branch.get("resultValue"));
        assertEquals(1,list(derived.get("editTargets")).size());Map<String,Object> target=map(list(derived.get("editTargets")).get(0));assertEquals("permit-scope",target.get("sourceScopeId"));assertEquals(owner,target.get("sourceTaskId"));assertEquals("24",target.get("docId"));assertEquals(body,target.get("text"));assertEquals("keep",target.get("action"));assertEquals("keep",target.get("proposedAction"));assertEquals(branch.get("id"),target.get("branchId"));assertEquals(false,target.get("needsRebind"));
        Set<Object> mapped=new HashSet<>();for(Object value:list(child.get("requirements"))){Map<String,Object> requirement=map(value);assertEquals("pending",requirement.get("status"));mapped.addAll(list(requirement.get("nodeIds")));}assertEquals(new HashSet<>(Arrays.asList(fact.get("id"),derived.get("id"))),mapped);
    }

    private static void assertMixedPrompt(String system,String user){
        assertTrue(system.startsWith("Build one pending drafting graph"),"Unmatched local tasks must keep the atomic mode");
        assertTrue(system.contains("Return JSON {nodes:[],requirements:[],scopeRules:[],taskOutcomes:[],notes:[]}"));assertTrue(system.contains("Each scopeRule is"));assertEquals(system.indexOf("Each scopeRule is"),system.lastIndexOf("Each scopeRule is"));
        assertTrue(system.contains("including when sourceTaskIds is empty or rulePatchAllowed=false"));assertTrue(system.contains("new local facts retain the atomic branches/effects path"));assertTrue(system.contains("genuinely new independently answerable facts absent from knownRoots and their necessary derived or verification treatment nodes"));assertTrue(system.contains("Derived and verification treatments are not additional business questions; known-root scope treatments still use scopeRules"));
        assertFalse(system.contains("emit only nodes:[{id:knownAlias"));assertFalse(system.contains("in a new derived action node referencing the read-only root"));for(JsonNode root:JsonUtils.parse(user).path("knownRoots"))assertFalse(root.has("adoptedAnswer"));
    }
    private static void assertMixedPacket(JsonNode wire){
        assertEquals(3,wire.path("instructions").size());assertEquals(1,wire.path("knownRoots").size());JsonNode root=wire.path("knownRoots").path(0);assertEquals(KEY,root.path("id").asText());assertFalse(root.path("rulePatchAllowed").asBoolean(true));assertFalse(root.has("adoptedAnswer"));
        JsonNode known=instruction(wire,"known"),marker=instruction(wire,"marker"),fresh=instruction(wire,"fresh");assertEquals(KEY,known.path("currentTemplateAlignment").path("fixedRootKeys").path(0).asText());assertEquals("requires_review",known.path("currentTemplateAlignment").path("scopeStatus").asText());
        for(JsonNode item:Arrays.asList(marker,fresh)){assertEquals("unmatched",item.path("currentTemplateAlignment").path("definitionStatus").asText());assertTrue(item.path("currentTemplateAlignment").path("fixedRootKeys").isEmpty());}for(JsonNode item:wire.path("instructions"))assertEquals(1,item.path("scopeObligationIds").size());
    }
    private static Map<String,Object> reply(JsonNode wire,boolean duplicate){
        JsonNode known=instruction(wire,"known"),marker=instruction(wire,"marker"),fresh=instruction(wire,"fresh"),definition=wire.path("knownRoots").path(0);String owner=known.path("sourceTaskId").asText(),permitOwner=fresh.path("sourceTaskId").asText();
        Map<String,Object> knownCheck=check("bq_check",known);knownCheck.put("when",obj("mode","all","clauses",Collections.singletonList(obj("inputId",KEY,"operator","eq","value","L10Pro"))));
        Map<String,Object> permit=obj("id","permit","sourceTaskId",permitOwner,"nameZh","通行许可证编号","nameEn","Access permit identifier","questionZh","项目通行许可证编号是什么？","questionEn","What is the project access permit identifier?","type","text","role","input","optional",false,"options",Collections.emptyList(),"sourceQuote",fresh.path("text").asText(),"branches",Collections.emptyList(),"effects",Collections.singletonList(obj("scopeId",fresh.path("scopeObligationIds").path(0).asText(),"action","check")));
        List<Object> nodes=new ArrayList<>(Collections.singletonList(permit)),requirements=new ArrayList<>(Collections.singletonList(obj("id","permit_req","sourceTaskId",permitOwner,"sourceQuote",fresh.path("text").asText(),"kind","input","nodeIds",Collections.singletonList("permit"),"reason","BQ issue format does not supply the independent access permit identifier.")));
        if(duplicate){nodes.add(obj("id","n1","sourceTaskId",owner,"nameZh",definition.path("questionZh").asText(),"nameEn",definition.path("questionEn").asText(),"questionZh",definition.path("questionZh").asText(),"questionEn",definition.path("questionEn").asText(),"type",definition.path("type").path("kind").asText(),"role","input","optional",false,"options",definition.path("options"),"sourceQuote",known.path("text").asText(),"branches",Collections.emptyList(),"effects",Collections.emptyList()));requirements.add(obj("id","known_ref","sourceTaskId",owner,"sourceQuote",known.path("text").asText(),"kind","reference","nodeIds",Collections.singletonList(KEY),"reason","The requirement references the supplied business definition, leaving n1 unsupported."));}
        List<Object> outcomes=new ArrayList<>();for(JsonNode row:wire.path("instructions"))outcomes.add(obj("sourceTaskId",row.path("sourceTaskId").asText(),"status",row==fresh?"identified":"unresolved","reason","Current scope checks remain pending human review."));
        return obj("nodes",nodes,"requirements",requirements,"scopeRules",Arrays.asList(knownCheck,check("marker_check",marker)),"taskOutcomes",outcomes,"notes",Collections.emptyList());
    }
    private static Map<String,Object> check(String id,JsonNode owner){return obj("id",id,"sourceTaskId",owner.path("sourceTaskId").asText(),"scopeId",owner.path("scopeObligationIds").path(0).asText(),"action","check");}
    private static JsonNode instruction(JsonNode wire,String kind){for(JsonNode row:wire.path("instructions")){if("marker".equals(kind)&&"marker".equals(row.path("kind").asText()))return row;if("fresh".equals(kind)&&PERMIT.equals(row.path("text").asText()))return row;if("known".equals(kind)&&KEY.equals(row.path("currentTemplateAlignment").path("fixedRootKeys").path(0).asText()))return row;}throw new AssertionError("Missing mixed instruction: "+kind);}
    private static Map<String,Object> findRoot(List<Map<String,Object>> nodes){return nodes.stream().filter(node->KEY.equals(map(node.get("catalogAlignment")).get("baselineKey"))).findFirst().orElseThrow(()->new AssertionError("Missing BQ definition"));}
    private static Map<String,Object> child(Map<String,Object> receipt,String owner){return list(receipt.get("taskReceipts")).stream().map(DraftReviewGraph::map).filter(row->owner.equals(row.get("taskId"))).findFirst().orElseThrow(AssertionError::new);}
    private static List<Map<String,Object>> receipts(Map<String,Object> group){List<Map<String,Object>> result=new ArrayList<>();result.add(group);for(Object row:list(group.get("taskReceipts")))result.add(map(row));return result;}

    private static final class Fixture {
        final ObjectNode source=source();final Map<String,Object> base=graph(source);final Map<String,Object> root;final DraftAutomaticDiscovery.Plan plan;final Map<String,Object> group;
        Fixture(){
            DraftAutomaticDiscovery.Plan initial=DraftAutomaticDiscovery.plan(Collections.singletonList(source));root=findRoot(DraftAutomaticDiscovery.receiptNodes(initial,Collections.emptyList()));root.put("status","confirmed");map(root.get("catalogAlignment")).put("confirmed",true);map(root.get("catalogAlignment")).put("reason","The synthetic current SCT instruction and native scope establish the supplied BQ issue-format definition.");root.put("definitionReview",obj("confirmed",true,"basis",null));root.put("adoptedAnswer",ADOPTED_ANSWER);state(base).put("variables",new ArrayList<>(Collections.singletonList(root)));
            plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source),base);List<Map<String,Object>> groups=DraftAutomaticDiscovery.planGroups(plan);assertEquals(3,plan.tasks().size());assertEquals(1,groups.size(),"All three tasks share a current native scope");group=groups.get(0);
        }
        String task(String observation){return text(plan.tasks().stream().filter(row->observation.equals(row.get("sourceObservationId"))).findFirst().orElseThrow(AssertionError::new).get("taskId"));}
    }
    private static ObjectNode source(){
        String note=DraftBusinessRules.standardParagraph("SCT",55),body=MARKER+"\nBills of Quantities\n"+DraftBusinessRules.standardParagraph("SCT",86)+"\nBills of Quantities issued in hard copy.";assertFalse(note.isEmpty());
        ObjectNode source=sourceShell();guidance(source,"bq-note",note);guidance(source,"permit-note",PERMIT);member(source,"body-member",body);
        ObjectNode scope=source.withArray("groups").addObject().put("id","shared-scope").put("text",body).put("scopeComplete",true);scope.putArray("guidanceNoteIds").add("bq-note").add("permit-note");scope.putArray("memberRanges").addObject().put("memberId","body-member").put("channel","text").put("start",0).put("end",body.length());
        ObjectNode marker=source.withArray("markerCandidates").addObject().put("id","marker-start").put("memberId","body-member").put("channel","text");marker.putObject("localSentenceObservation").put("text",MARKER).put("start",0).put("end",MARKER.length());marker.putArray("targetGroupIds").add("shared-scope");return source;
    }
    private static ObjectNode sourceShell(){ObjectNode source=JsonUtils.mapper().createObjectNode().put("id","24").put("sourceDocumentId",24).put("fileKey","SCT").put("format","docx").put("sourceSha256",SHA).put("evidenceStatus","bound").put("analysisRevision","mixed-analysis").put("analysisInputSha256",SHA).put("analysisSha256",SHA);for(String field:Arrays.asList("guidanceNotes","groups","markerCandidates","conditions","sourceMembers","paragraphs"))source.putArray(field);return source;}
    private static void guidance(ObjectNode source,String id,String quote){ObjectNode note=source.withArray("guidanceNotes").addObject().put("id",id).put("text",quote).put("captureComplete",true);note.putArray("orderedMemberIds").add(id+"-member");note.putArray("memberRanges").addObject().put("memberId",id+"-member").put("channel","text").put("start",0).put("end",quote.length());member(source,id+"-member",quote);}
    private static void member(ObjectNode source,String id,String quote){ObjectNode member=source.withArray("sourceMembers").addObject().put("id",id).put("identityStatus","source_bound");member.putObject("textObservation").put("text",quote);member.putObject("sourcePosition").put("sourceSha256",SHA).put("partName","word/document.xml").put("nodePath","/mixed/"+id);ObjectNode paragraph=source.withArray("paragraphs").addObject().put("id","p-"+id).put("text",quote).put("sourceSha256",SHA).put("paragraphSha256",DraftAdoption.hash(quote));paragraph.set("sourcePosition",member.path("sourcePosition").deepCopy());}
    private static Map<String,Object> graph(ObjectNode source){return obj("kind","consense-template-graph-v1","sources",Collections.singletonList(obj("id","24","sourceDocumentId",24,"fileKey","SCT","format","docx","sourceSha256",SHA,"evidenceStatus","bound","analysisRevision",source.path("analysisRevision").asText(),"analysisInputSha256",SHA,"analysisSha256",SHA)),"selectedDocuments",obj("SCT","24"),"graph",obj("variables",new ArrayList<>(),"events",new ArrayList<>(),"blank",true));}
}
