package com.consense.service.drafting;

import com.consense.ai.AiGateway;
import com.consense.ai.IncompleteModelResponseException;
import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

class DraftAutomaticDiscoveryTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a"));

    @Test void completedObservationsRetireUntouchedSourcePlaceholdersWithoutLosingEvidence() {
        ObjectNode source=source("1");String quote="Provide project name.";member(source,"body",quote,"main");guidance(source,"body",quote);
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));
        Map<String,Object> base=DraftReviewDiscovery.seed(graph(),Collections.singletonList(source));
        AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenReturn(JsonUtils.write(obj("nodes",Collections.singletonList(node("name",quote)),"requirements",Collections.singletonList(obj("id","name","sourceQuote",quote,"kind","input","nodeIds",Collections.singletonList("name"))))));
        Map<String,Object> receipt=DraftAutomaticDiscovery.process(ai,plan,plan.tasks().get(0));
        Map<String,Object> finished=DraftAutomaticDiscovery.finish(base,plan,Collections.singletonList(receipt));
        JsonNode seed=JsonUtils.mapper().valueToTree(finished).path("graph").path("variables").path(0);
        assertEquals("excluded",seed.path("status").asText());assertEquals(1,seed.path("replacedBy").size());assertEquals(quote,seed.path("sourceObservation").path("text").asText());
        assertEquals("pending",nodes(base).get(0).get("status"),"The prior immutable revision is untouched");
        Map<String,Object> linked=JsonUtils.readMap(JsonUtils.write(base));
        Map<String,Object> dependent=JsonUtils.readMap(JsonUtils.write(nodes(base).get(0)));dependent.put("id","manual-question");dependent.put("manual",true);dependent.put("relatedInputIds",Collections.singletonList(nodes(base).get(0).get("id")));List<Object> linkedVariables=new ArrayList<>(list(state(linked).get("variables")));linkedVariables.add(dependent);state(linked).put("variables",linkedVariables);
        assertEquals("pending",nodes(DraftAutomaticDiscovery.finish(linked,plan,Collections.singletonList(receipt))).get(0).get("status"),"Referenced source items remain available to their rules");
        for(String protectedStatus:Arrays.asList("confirmed","changed","help")) {
            Map<String,Object> protectedGraph=JsonUtils.readMap(JsonUtils.write(base));nodes(protectedGraph).get(0).put("status",protectedStatus);
            assertEquals(protectedStatus,nodes(DraftAutomaticDiscovery.finish(protectedGraph,plan,Collections.singletonList(receipt))).get(0).get("status"));
        }
        nodes(base).get(0).put("manual",true);
        assertEquals("pending",nodes(DraftAutomaticDiscovery.finish(base,plan,Collections.singletonList(receipt))).get(0).get("status"));
        nodes(base).get(0).put("manual",false);receipt.put("status","partial");
        assertEquals("pending",nodes(DraftAutomaticDiscovery.finish(base,plan,Collections.singletonList(receipt))).get(0).get("status"),"Partial results must retain unresolved source review");
        Map<String,Object> noRequirement=DraftAutomaticDiscovery.process((system,user)->"{\"nodes\":[],\"requirements\":[]}",plan,plan.tasks().get(0));
        assertEquals("excluded",nodes(DraftAutomaticDiscovery.finish(base,plan,Collections.singletonList(noRequirement))).get(0).get("status"),"An observed marker with no business requirement is not a new variable");
    }

    @Test void usesOnlyTemplateOerObservationsAndKeepsEveryNativeMemberInAudit() {
        ObjectNode source=source("1");member(source,"plain","The contract period shall be provided.","main");member(source,"header","Project title: ______","header");member(source,"blank","  ","footer");
        assertTrue(DraftAutomaticDiscovery.plan(Collections.singletonList(source)).tasks().isEmpty(),"Raw prose is not a second structural-discovery route");
        guidance(source,"plain","The contract period shall be provided.");
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));
        assertEquals(3,plan.coverage().get("totalMembers"));assertEquals(1,plan.coverage().get("blankMembers"));assertEquals(1,plan.tasks().size());
        assertEquals(Collections.singletonList("plain"),plan.tasks().get(0).get("memberIds"));
        assertEquals(JsonUtils.write(plan.descriptor()),JsonUtils.write(DraftAutomaticDiscovery.plan(Collections.singletonList(source)).descriptor()));
        assertFalse(JsonUtils.write(plan.descriptor()).contains("contract period"));
        AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenAnswer(call->{JsonNode payload=JsonUtils.parse(call.getArgument(1));assertFalse(payload.has("members"));assertEquals("The contract period shall be provided.",payload.path("instruction").path("text").asText());assertFalse(call.<String>getArgument(0).contains("all unmarked prose"));assertFalse(call.<String>getArgument(1).contains("Project title"));return "{\"requirements\":[],\"nodes\":[],\"notes\":[]}";});
        Map<String,Object> receipt=DraftAutomaticDiscovery.process(ai,plan,plan.tasks().get(0));assertEquals("reviewed_no_requirement",receipt.get("status"));
        Map<String,Object> graph=DraftAutomaticDiscovery.finish(graph(),plan,Collections.singletonList(receipt));JsonNode result=JsonUtils.mapper().valueToTree(graph);
        assertEquals(1,result.path("graph").path("automaticDiscovery").path("scannedMembers").asInt());assertEquals(1,result.path("graph").path("automaticDiscovery").path("membersNotListedAsRequirements").asInt());assertEquals("completed",result.path("graph").path("automaticDiscovery").path("scanStatus").asText());assertEquals("requires_human_review",result.path("graph").path("automaticDiscovery").path("semanticCompleteness").asText());
    }

    @Test void unavailableSourcesDuplicateMembersAndUnexecutedWorkRemainInCoverageDenominator() {
        ObjectNode source=source("1");member(source,"same","First paragraph.","main");member(source,"same","Second paragraph.","main");member(source,"good","Unmarked content.","main");
        guidance(source,"good","Unmarked content.");
        ObjectNode unavailable=source("2");member(unavailable,"unknown","Unknown source.","main");unavailable.put("evidenceStatus","invalid");
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Arrays.asList(source,unavailable));assertEquals(4,plan.coverage().get("totalMembers"));assertEquals(3,plan.coverage().get("blockedMembers"));assertEquals(1,plan.coverage().get("sourceIssues"));
        JsonNode result=JsonUtils.mapper().valueToTree(DraftAutomaticDiscovery.finish(graph(),plan,Collections.emptyList())).path("graph").path("automaticDiscovery");assertEquals(1,result.path("unexecutedMembers").asInt());assertEquals("partial",result.path("scanStatus").asText());
    }

    @Test void nativeXmlLayoutAndDuplicateMetadataStayInPlanWhileCompleteSemanticContextReachesModel() {
        ObjectNode source=source("1");String quote="Provide the project title.";member(source,"large",quote,"main");guidance(source,"large",quote);
        String huge=String.join("",Collections.nCopies(300000,"x"));((ObjectNode)source.path("sourceMembers").path(0)).put("nativeXml",huge);((ObjectNode)source.path("guidanceNotes").path(0)).putObject("sourcePosition").put("nodePath",huge);
        ObjectNode scope=source.withArray("groups").addObject().put("id","target").put("guidanceNoteId","note").put("scopeComplete",false).put("text","Complete target wording.");scope.putObject("nativeOutlineObservation").put("nativeXml",huge);
        String before=JsonUtils.write(source);DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));
        Map<String,Object> receipt=DraftAutomaticDiscovery.process((system,user)->{assertTrue(user.length()<10000);assertFalse(user.contains("nativeXml"));assertFalse(user.contains("nodePath"));assertTrue(user.contains(quote));assertTrue(user.contains("Complete target wording."));assertFalse(JsonUtils.parse(user).path("scopeCandidates").path(0).path("scopeComplete").asBoolean());return "{\"requirements\":[],\"nodes\":[]}";},plan,plan.tasks().get(0));assertEquals("reviewed_no_requirement",receipt.get("status"));assertEquals(before,JsonUtils.write(source));
    }

    @Test void retainsCompleteIndependentNodesWhenAnotherNodeOrRequirementIsInvalid() {
        ObjectNode source=source("1");String quote="Provide project name and contract number.";member(source,"body",quote,"main");guidance(source,"body",quote);DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));
        Map<String,Object> good=node("contract-number",quote),bad=node("bad",quote);bad.put("sourceQuote","invented quote");
        AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenReturn(JsonUtils.write(obj("nodes",Arrays.asList(good,bad),"requirements",Arrays.asList(obj("id","identity","sourceQuote",quote,"kind","input","nodeIds",Collections.singletonList("contract-number")),obj("id","fabricated","sourceQuote","not in source","kind","input","nodeIds",Collections.singletonList("bad"))))));
        Map<String,Object> receipt=DraftAutomaticDiscovery.process(ai,plan,plan.tasks().get(0));assertEquals("partial",receipt.get("status"));JsonNode result=JsonUtils.mapper().valueToTree(receipt);assertEquals(1,result.path("nodes").size());assertEquals(1,result.path("requirements").size());assertEquals(result.path("nodes").path(0).path("id"),result.path("requirements").path(0).path("nodeIds").path(0));assertEquals("pending",result.path("nodes").path(0).path("status").asText());
    }

    @Test void malformedJsonRetainsOnlyFullyDecodedSupportedMembers() {
        ObjectNode source=source("1");String quote="Provide a project name.";member(source,"body",quote,"main");guidance(source,"body",quote);DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));
        AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenReturn("{\"requirements\":[],\"nodes\":["+JsonUtils.write(node("name",quote))+",{\"id\":\"unfinished");
        JsonNode receipt=JsonUtils.mapper().valueToTree(DraftAutomaticDiscovery.process(ai,plan,plan.tasks().get(0)));assertEquals("partial",receipt.path("status").asText());assertEquals(1,receipt.path("nodes").size());assertTrue(receipt.path("issues").size()>0);
    }

    @Test void providerOutputLimitRetainsCompleteCandidatesButRefusalAndToolResultsRemainRejected() {
        ObjectNode source=source("1");String quote="Provide a project name.";member(source,"body",quote,"main");guidance(source,"body",quote);DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));
        String content="{\"requirements\":["+JsonUtils.write(obj("id","name","sourceQuote",quote,"kind","input","nodeIds",Collections.singletonList("name")))+"],\"nodes\":["+JsonUtils.write(node("name",quote))+",{\"id\":\"unfinished";
        for(IncompleteModelResponseException.FailureKind kind:Arrays.asList(IncompleteModelResponseException.FailureKind.OUTPUT_BUDGET_EXHAUSTED,IncompleteModelResponseException.FailureKind.INCOMPLETE_FINISH,IncompleteModelResponseException.FailureKind.REFUSAL,IncompleteModelResponseException.FailureKind.TOOL_CALLS)) {
            Map<String,Object> receipt=DraftAutomaticDiscovery.process((system,user)->{throw new IncompleteModelResponseException("observed incomplete","provider-envelope",JsonUtils.mapper().getNodeFactory().textNode(content),kind,null);},plan,plan.tasks().get(0));JsonNode observed=JsonUtils.mapper().valueToTree(receipt);
            assertEquals(kind.name(),receipt.get("providerFailureKind"));boolean allowed=kind==IncompleteModelResponseException.FailureKind.OUTPUT_BUDGET_EXHAUSTED||kind==IncompleteModelResponseException.FailureKind.INCOMPLETE_FINISH;
            assertEquals(allowed?"partial":"failed",receipt.get("status"));assertEquals(allowed?1:0,observed.path("nodes").size());assertEquals(allowed?1:0,observed.path("requirements").size());
        }
    }

    @Test void meteredCallIsInvokedOnlyAfterCompletePayloadPassesItsBound() {
        ObjectNode source=source("1");String quote="Provide a project name.";member(source,"body",quote,"main");guidance(source,"body",quote);source.withArray("groups").addObject().put("id","huge-target").put("guidanceNoteId","note").put("scopeComplete",false).put("text",String.join("",Collections.nCopies(300000,"x")));
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));int[] calls={0};Map<String,Object> receipt=DraftAutomaticDiscovery.process((system,user)->{calls[0]++;throw new AssertionError("No dispatch may be metered for a rejected payload");},plan,plan.tasks().get(0));assertEquals(0,calls[0]);assertEquals(0,receipt.get("modelCalls"));assertEquals("blocked",receipt.get("status"));
    }

    @Test void reusesObservedScopesAndConditionsWithoutInventingEditableCoverage() {
        ObjectNode source=source("1");String quote="Provide the project title.";member(source,"body",quote,"main");
        guidance(source,"body",quote);
        JsonNode position=source.path("sourceMembers").path(0).path("sourcePosition");ObjectNode paragraph=source.withArray("paragraphs").addObject().put("id","native-p1").put("text",quote).put("paragraphSha256",SHA).put("sourceSha256",SHA);paragraph.set("sourcePosition",position.deepCopy());
        ObjectNode group=source.withArray("groups").addObject().put("id","scope").put("guidanceNoteId","note").put("kind","literal_clause_candidate").put("text",quote).put("captureComplete",true).put("scopeComplete",true);group.putArray("orderedMemberIds").add("body");group.putArray("memberRanges").addObject().put("memberId","body").put("channel","text").put("start",0).put("end",quote.length());group.putArray("conditionIds").add("condition");source.withArray("conditions").addObject().put("id","condition").put("text","If this contract applies.");
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));Map<String,Object> proposed=node("title",quote);proposed.put("effects",Collections.singletonList(obj("scopeId","scope","quote",quote,"action","fill","valueSourceId","title")));
        AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenAnswer(call->{JsonNode payload=JsonUtils.parse(call.getArgument(1));assertEquals(1,payload.path("scopeCandidates").size());assertEquals("condition",payload.path("sourceConditions").path(0).path("id").asText());return JsonUtils.write(obj("nodes",Collections.singletonList(proposed),"requirements",Collections.singletonList(obj("id","title","sourceQuote",quote,"kind","input","nodeIds",Collections.singletonList("title")))));});
        JsonNode receipt=JsonUtils.mapper().valueToTree(DraftAutomaticDiscovery.process(ai,plan,plan.tasks().get(0)));assertEquals("completed",receipt.path("status").asText());assertEquals("fill",receipt.path("nodes").path(0).path("editTargets").path(0).path("action").asText());assertTrue(receipt.path("nodes").path(0).path("editTargets").path(0).path("scopeComplete").asBoolean());
    }

    @Test void relationProposalsKeepOccurrencesSeparateAndRejectForeignReferences() {
        ObjectNode a=source("1"),b=source("2");String quote="Provide project name.";member(a,"body",quote,"main");member(b,"body",quote,"main");guidance(a,"body",quote);guidance(b,"body",quote);DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Arrays.asList(a,b));
        AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenReturn(JsonUtils.write(obj("nodes",Collections.singletonList(node("name",quote)),"requirements",Collections.singletonList(obj("id","name","sourceQuote",quote,"kind","input","nodeIds",Collections.singletonList("name"))))));
        List<Map<String,Object>> receipts=new ArrayList<>();for(Map<String,Object> task:plan.tasks())receipts.add(DraftAutomaticDiscovery.process(ai,plan,task));
        String first=JsonUtils.mapper().valueToTree(receipts.get(0)).path("nodes").path(0).path("id").asText(),second=JsonUtils.mapper().valueToTree(receipts.get(1)).path("nodes").path(0).path("id").asText();assertNotEquals(first,second);
        Map<String,Object> relationTask=DraftAutomaticDiscovery.planRelations(plan,receipts).get(0);when(ai.complete(anyString(),anyString())).thenReturn(JsonUtils.write(obj("relations",Arrays.asList(obj("fromId",first,"toId",second,"kind","same_fact","fromQuote",quote,"toQuote",quote,"reason","Source-supported candidate only."),obj("fromId",first,"toId","foreign","kind","dependency","fromQuote",quote,"toQuote",quote)))));
        Map<String,Object> relation=DraftAutomaticDiscovery.processRelation(ai,plan,receipts,relationTask);assertEquals("partial",relation.get("status"));receipts.add(relation);
        JsonNode graph=JsonUtils.mapper().valueToTree(DraftAutomaticDiscovery.finish(graph(),plan,receipts)).path("graph");assertEquals(2,graph.path("variables").size());assertEquals(1,graph.path("relationProposals").size());assertFalse(graph.path("relationProposals").path(0).path("executable").asBoolean());assertEquals("pending",graph.path("relationProposals").path(0).path("status").asText());assertEquals("partial",graph.path("automaticDiscovery").path("scanStatus").asText());
    }

    @Test void rejectsTaskAndReceiptIdentityChangesAndDoesNotMutateBaseGraph() {
        ObjectNode source=source("1");member(source,"body","Provide project name.","main");guidance(source,"body","Provide project name.");DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));Map<String,Object> altered=new LinkedHashMap<>(plan.tasks().get(0));altered.put("sourceSha256","changed");assertThrows(BizException.class,()->DraftAutomaticDiscovery.process(mock(AiGateway.class),plan,altered));
        AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenReturn("{\"requirements\":[],\"nodes\":[]}");Map<String,Object> receipt=DraftAutomaticDiscovery.process(ai,plan,plan.tasks().get(0));Map<String,Object> base=graph();String before=JsonUtils.write(base);DraftAutomaticDiscovery.finish(base,plan,Collections.singletonList(receipt));assertEquals(before,JsonUtils.write(base));receipt.put("sourceId","other");assertThrows(BizException.class,()->DraftAutomaticDiscovery.finish(base,plan,Collections.singletonList(receipt)));
    }

    private static ObjectNode source(String id){ObjectNode source=JsonUtils.mapper().createObjectNode().put("id",id).put("sourceDocumentId",Long.parseLong(id)).put("sourceSha256",SHA).put("format","docx").put("fileKey","NTT").put("fileName","NTT.docx").put("evidenceStatus","bound");for(String key:Arrays.asList("sourceMembers","paragraphs","guidanceNotes","markerCandidates","groups","conditions"))source.putArray(key);return source;}
    private static void member(ObjectNode source,String id,String text,String story){ObjectNode member=source.withArray("sourceMembers").addObject().put("id",id).put("storyKind",story).put("identityStatus","source_bound");member.putObject("sourcePosition").put("sourceSha256",SHA).put("partName","word/"+story+".xml").put("nodePath","/"+id);member.putObject("textObservation").put("text",text);}
    private static void guidance(ObjectNode source,String memberId,String text){ObjectNode note=source.withArray("guidanceNotes").addObject().put("id","note").put("text",text).put("scopeComplete",false);note.putArray("memberRanges").addObject().put("memberId",memberId).put("channel","text").put("start",0).put("end",text.length());}
    private static Map<String,Object> graph(){return obj("graph",obj("variables",new ArrayList<>(),"events",new ArrayList<>()),"sources",new ArrayList<>());}
    private static Map<String,Object> node(String id,String quote){return obj("id",id,"type","text","role","input","nameZh","项目名称","nameEn","Project name","questionZh","请填写","questionEn","Provide","sourceQuote",quote,"branches",new ArrayList<>(),"effects",new ArrayList<>(),"options",new ArrayList<>());}
}
