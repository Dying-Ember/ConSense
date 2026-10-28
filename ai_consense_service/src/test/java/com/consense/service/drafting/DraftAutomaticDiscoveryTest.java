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
    @Test void restoredMeaningsAreAvailableBeforeDiscoveryAndInCrossBaselineRelationsWithoutProjectAnswers() {
        String quote="Provide the project name.";ObjectNode source=source("1");member(source,"body",quote,"main");guidance(source,"body",quote);List<JsonNode> readings=Collections.singletonList(source);
        DraftAutomaticDiscovery.Plan original=DraftAutomaticDiscovery.plan(readings);Map<String,Object> base=DraftAutomaticDiscovery.finish(graph(),original,Collections.singletonList(atomicReceipt(original,Collections.singletonList(node("known",quote)))));
        Map<String,Object> known=nodes(base).get(0);known.put("status","confirmed");known.put("adoptedValue","PROJECT_ANSWER_MUST_STAY_PRIVATE");String knownId=text(known.get("id"));
        base.put("sources",Collections.singletonList(obj("id","1","sourceSha256",SHA)));map(base.get("graph")).put("baselineRestoration",obj("status","partial_requires_review","fieldIds",Collections.singletonList(knownId)));
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(readings,base);
        Map<String,Object> fresh=DraftAutomaticDiscovery.process((system,user)->{assertEquals(knownId,JsonUtils.parse(user).path("restoredDefinitionReferences").path(0).path("id").asText());assertFalse(user.contains("PROJECT_ANSWER_MUST_STAY_PRIVATE"));return JsonUtils.write(obj("nodes",Collections.singletonList(node("fresh",quote)),"requirements",Collections.singletonList(obj("id","identity","sourceQuote",quote,"kind","input","nodeIds",Collections.singletonList("fresh")))));},plan,plan.tasks().get(0));
        Map<String,Object> relationTask=DraftAutomaticDiscovery.planRelations(plan,Collections.singletonList(fresh)).get(0);assertTrue(list(relationTask.get("catalogNodeIds")).contains(knownId));assertFalse(list(relationTask.get("nodeIds")).contains(knownId));
        Map<String,Object> relation=DraftAutomaticDiscovery.processRelation((system,user)->{assertTrue(user.contains(knownId));assertFalse(user.contains("PROJECT_ANSWER_MUST_STAY_PRIVATE"));return "{\"relations\":[],\"nodeClassifications\":[]}";},plan,Collections.singletonList(fresh),relationTask);
        assertNotEquals("failed",relation.get("status"));
    }
    @Test void changedEditionCanRequestCurrentEvidenceWithoutLosingThePriorDraft() {
        ObjectNode source=source("1");String quote="Provide the project name.";member(source,"body",quote,"main");member(source,"context","The project name is an independent contract identity.","main");guidance(source,"body",quote);
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));int[] calls={0};
        Map<String,Object> result=DraftAutomaticDiscovery.process((system,user)->{
            JsonNode payload=JsonUtils.parse(user);
            if(++calls[0]==1)return JsonUtils.write(obj("nodes",Collections.singletonList(node("name",quote)),"requirements",Collections.singletonList(obj("id","identity","sourceQuote",quote,"kind","input","nodeIds",Collections.singletonList("name"))),"notes",Collections.singletonList("The editing range remains unconfirmed."),"sourceContextRequests",Collections.singletonList(obj("kind","member","id","context"))));
            assertFalse(payload.path("priorDraft").path("nodes").isEmpty());assertEquals("context",payload.path("currentSourceContext").path("items").path(0).path("id").asText());
            return "{\"nodes\":[],\"requirements\":[],\"notes\":[]}";
        },plan,plan.tasks().get(0));
        assertEquals(2,calls[0]);assertEquals(1,list(result.get("nodes")).size());assertEquals(1,list(result.get("requirements")).size());assertTrue(JsonUtils.write(result.get("notes")).contains("unconfirmed"));
    }

    @Test void newRunCannotSilentlyRemoveExistingScopeUncertainty() {
        String quote="Provide the project name.";ObjectNode source=source("1");member(source,"body",quote,"main");guidance(source,"body",quote);DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));
        Map<String,Object> base=graph();map(base.get("graph")).put("automaticDiscoveryIssues",Collections.singletonList(obj("sourceId","1","scopeId","historic-scope","reason","scope_binding_unresolved")));
        Map<String,Object> result=DraftAutomaticDiscovery.finish(base,plan,Collections.singletonList(atomicReceipt(plan,Collections.singletonList(node("name",quote)))));
        assertTrue(list(map(result.get("graph")).get("automaticDiscoveryIssues")).stream().anyMatch(raw->"scope_binding_unresolved".equals(map(raw).get("reason"))));
    }
    private static final String SHA=String.join("",Collections.nCopies(64,"a"));

    @Test void exactEditionMayRequestOnlyShownFixedDefinitionsBeforeAtomicDiscovery() {
        ObjectNode source=source("1");String sha="60bd8796aa828863c7edfe8617b7a5c56ee383afe8ba87a089e820f0fd9a982c",quote="The contract period is 39 months or more.";source.put("sourceSha256",sha);member(source,"body",quote,"main");((ObjectNode)source.path("sourceMembers").path(0).path("sourcePosition")).put("sourceSha256",sha);guidance(source,"body",quote);
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));int[] calls={0};
        Map<String,Object> receipt=DraftAutomaticDiscovery.process((system,user)->{calls[0]++;JsonNode payload=JsonUtils.parse(user);if(calls[0]==1){assertTrue(payload.path("fixedBaselineIndex").path("fields").toString().contains("periodAtLeast39Months"));return JsonUtils.write(obj("baselineContextRequests",Arrays.asList("periodAtLeast39Months","inventedKey")));}
            assertEquals("periodAtLeast39Months",payload.path("fixedContext").path("fields").path(0).path("key").asText());assertFalse(user.contains("inventedKey"));return JsonUtils.write(obj("nodes",Collections.singletonList(node("duration",quote)),"requirements",Collections.singletonList(obj("id","duration","sourceQuote",quote,"kind","input","nodeIds",Collections.singletonList("duration")))));},plan,plan.tasks().get(0));
        assertEquals(2,calls[0]);assertEquals(2,receipt.get("modelCalls"));assertEquals("completed",receipt.get("status"));assertEquals(1,list(receipt.get("nodes")).size());assertEquals(1,list(receipt.get("baselineEvidenceRounds")).size());
    }

    @Test void changedSccEditionCanRequestFixedDefinitionsWithoutInheritingHistoricalAuthority() {
        ObjectNode source=source("1");source.put("fileKey","SCC");String quote="The contract period is 39 months or more.";member(source,"body",quote,"main");guidance(source,"body",quote);DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));
        int[] calls={0};Map<String,Object> receipt=DraftAutomaticDiscovery.process((system,user)->{JsonNode payload=JsonUtils.parse(user);if(++calls[0]==1){JsonNode index=payload.path("fixedBaselineIndex");assertTrue(index.path("fields").toString().contains("periodAtLeast39Months"));assertEquals("fixed_definition_reference",index.path("referenceKind").asText());assertFalse(index.path("editableAuthority").asBoolean());assertFalse(payload.has("fixedContext"));assertTrue(system.contains("never current-source evidence"));return JsonUtils.write(obj("baselineContextRequests",Collections.singletonList("periodAtLeast39Months")));}
            assertEquals(1,payload.path("fixedContext").path("fields").size());assertEquals("periodAtLeast39Months",payload.path("fixedContext").path("fields").path(0).path("key").asText());assertFalse(payload.path("fixedContext").path("editableAuthority").asBoolean());assertFalse(payload.path("sourceTask").has("fixedActionReferences"));return "{\"nodes\":[],\"requirements\":[]}";},plan,plan.tasks().get(0));assertEquals(2,calls[0]);assertEquals("reviewed_no_requirement",receipt.get("status"));
    }

    @Test void aDefinitionSearchReturnsNavigationBeforeAnExplicitSingleItemRead() {
        ObjectNode source=source("1");source.put("fileKey","SCC");String quote="Select the BQ issue format.";member(source,"body",quote,"main");guidance(source,"body",quote);DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));int[] calls={0};String[] key={null};
        Map<String,Object> receipt=DraftAutomaticDiscovery.process((system,user)->{JsonNode payload=JsonUtils.parse(user);if(++calls[0]==1)return JsonUtils.write(obj("baselineSearchRequests",Collections.singletonList("tender issue format")));
            if(calls[0]==2){assertTrue(payload.path("fixedContext").path("fields").isEmpty());assertFalse(payload.path("fixedSearchResults").path("fields").isEmpty());assertFalse(payload.path("fixedSearchResults").path("editableAuthority").asBoolean());key[0]=payload.path("fixedSearchResults").path("fields").path(0).path("key").asText();return JsonUtils.write(obj("baselineContextRequests",Collections.singletonList(key[0])));}
            assertEquals(1,payload.path("fixedContext").path("fields").size());assertEquals(key[0],payload.path("fixedContext").path("fields").path(0).path("key").asText());assertFalse(payload.path("fixedContext").path("editableAuthority").asBoolean());return "{\"nodes\":[],\"requirements\":[]}";},plan,plan.tasks().get(0));
        assertEquals(3,calls[0]);assertEquals(3,receipt.get("modelCalls"));assertTrue(list(receipt.get("nodes")).isEmpty());
    }

    @Test void historicalFixedContextStillCannotProveANodeInTheChangedCurrentSource() {
        ObjectNode source=source("1");source.put("fileKey","SCC");String quote="Project manager must state whether IASM surfacing materials form part of this project.";member(source,"body",quote,"main");guidance(source,"body",quote);DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));int[] calls={0};
        Map<String,Object> receipt=DraftAutomaticDiscovery.process((system,user)->{JsonNode payload=JsonUtils.parse(user);if(++calls[0]==1){assertTrue(payload.path("fixedBaselineIndex").path("fields").toString().contains("iasmIncluded"));return JsonUtils.write(obj("baselineContextRequests",Collections.singletonList("iasmIncluded")));}
            JsonNode excerpt=payload.path("fixedContext").path("fields").path(0).path("sourceContext").path(0);assertEquals("fixed_catalog_embedded_reference",excerpt.path("sourceKind").asText());assertEquals("9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208",excerpt.path("referenceSourceSha256").asText());String historical=excerpt.path("text").asText();assertFalse(historical.isEmpty());assertFalse(quote.contains(historical));return JsonUtils.write(obj("nodes",Collections.singletonList(node("borrowed",historical)),"requirements",Collections.emptyList()));},plan,plan.tasks().get(0));
        assertEquals(2,calls[0]);assertTrue(list(receipt.get("nodes")).isEmpty());assertTrue(JsonUtils.write(receipt.get("issues")).contains("DISCOVERY_SOURCE_QUOTE_NOT_IN_INSTRUCTION"));
    }

    @Test void unboundEvidenceCannotUseFixedSourceIdentityEvenWhenItsHashIsKnown() {
        ObjectNode source=source("1");String sha="60bd8796aa828863c7edfe8617b7a5c56ee383afe8ba87a089e820f0fd9a982c",quote="The contract period is 39 months or more.";source.put("sourceSha256",sha);source.put("evidenceStatus","invalid");member(source,"body",quote,"main");((ObjectNode)source.path("sourceMembers").path(0).path("sourcePosition")).put("sourceSha256",sha);guidance(source,"body",quote);DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));
        assertTrue(plan.tasks().isEmpty());assertEquals(1,plan.coverage().get("sourceIssues"));
    }

    @Test void unavailableFollowUpKeepsAlreadySupportedAtomicResultsForReview() {
        ObjectNode source=source("1");String sha="60bd8796aa828863c7edfe8617b7a5c56ee383afe8ba87a089e820f0fd9a982c",quote="The contract period is 39 months or more.";source.put("sourceSha256",sha);member(source,"body",quote,"main");((ObjectNode)source.path("sourceMembers").path(0).path("sourcePosition")).put("sourceSha256",sha);guidance(source,"body",quote);DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));int[] calls={0};
        Map<String,Object> result=DraftAutomaticDiscovery.process((system,user)->{if(++calls[0]>1)throw new IllegalStateException("synthetic context failure");return JsonUtils.write(obj("nodes",Collections.singletonList(node("duration",quote)),"requirements",Collections.singletonList(obj("id","duration","sourceQuote",quote,"kind","input","nodeIds",Collections.singletonList("duration"))),"baselineContextRequests",Collections.singletonList("periodAtLeast39Months")));},plan,plan.tasks().get(0));
        assertEquals(2,calls[0]);assertEquals("partial",result.get("status"));assertEquals(1,list(result.get("nodes")).size());assertEquals(1,list(result.get("requirements")).size());assertEquals("failed",map(list(result.get("baselineEvidenceRounds")).get(0)).get("status"));
    }

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
        JsonNode graph=JsonUtils.mapper().valueToTree(DraftAutomaticDiscovery.finish(graph(),plan,receipts)).path("graph");assertEquals(2,graph.path("variables").size());assertEquals(1,graph.path("relationProposals").size());assertFalse(graph.path("relationProposals").path(0).path("executable").asBoolean());assertEquals("pending",graph.path("relationProposals").path(0).path("status").asText());assertEquals("partial",graph.path("automaticDiscovery").path("scanStatus").asText());assertTrue(graph.path("automaticDiscoveryIssues").toString().contains("relation_source_or_reference_invalid"));assertTrue(graph.path("automaticDiscoveryIssues").toString().contains(text(relationTask.get("taskId"))));
    }

    @Test void rejectsTaskAndReceiptIdentityChangesAndDoesNotMutateBaseGraph() {
        ObjectNode source=source("1");member(source,"body","Provide project name.","main");guidance(source,"body","Provide project name.");DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));Map<String,Object> altered=new LinkedHashMap<>(plan.tasks().get(0));altered.put("sourceSha256","changed");assertThrows(BizException.class,()->DraftAutomaticDiscovery.process(mock(AiGateway.class),plan,altered));
        AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenReturn("{\"requirements\":[],\"nodes\":[]}");Map<String,Object> receipt=DraftAutomaticDiscovery.process(ai,plan,plan.tasks().get(0));Map<String,Object> base=graph();String before=JsonUtils.write(base);DraftAutomaticDiscovery.finish(base,plan,Collections.singletonList(receipt));assertEquals(before,JsonUtils.write(base));receipt.put("sourceId","other");assertThrows(BizException.class,()->DraftAutomaticDiscovery.finish(base,plan,Collections.singletonList(receipt)));
    }

    @Test void laterDiscoveryDoesNotEraseExistingReviewedOrRestoredRelations() {
        Map<String,Object> base=graph();Map<String,Object> relation=obj("fromId","existing-a","toId","existing-b","kind","dependency","status","pending","reason","Previously restored");state(base).put("relationProposals",Collections.singletonList(relation));
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.emptyList());Map<String,Object> result=DraftAutomaticDiscovery.finish(base,plan,Collections.emptyList());
        assertEquals(Collections.singletonList(relation),list(state(result).get("relationProposals")));assertEquals(Collections.singletonList(relation),list(state(base).get("relationProposals")));
        assertEquals(1,list(state(DraftAutomaticDiscovery.finish(result,plan,Collections.emptyList())).get("relationProposals")).size());
    }

    @Test void reidentificationKeepsDifferentReviewedDefinitionsAndRemapsTheCompleteNewAtomicGroup() {
        String quote="Delete clause A if foundation works are not included.";ObjectNode source=source("1");member(source,"body",quote,"main");guidance(source,"body",quote);
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));
        Map<String,Object> fact=node("fact",quote);fact.put("type","boolean");
        Map<String,Object> first=atomicReceipt(plan,Collections.singletonList(fact));Map<String,Object> old=map(list(first.get("nodes")).get(0));
        old.put("nameEn","Different reviewed project flag");old.put("questionEn","Does the independent package apply?");old.put("status","confirmed");old.put("manual",true);old.put("catalogAlignment",obj("status","new","confirmed",true,"reason","Reviewed independent meaning"));
        Map<String,Object> base=DraftReviewDiscovery.seed(graph(),Collections.singletonList(source));List<Object> initial=new ArrayList<>(list(state(base).get("variables")));initial.add(old);state(base).put("variables",initial);
        Map<String,Object> outcome=node("outcome",quote);outcome.put("type","boolean");outcome.put("role","derived");outcome.put("branches",Collections.singletonList(obj("id","case","mode","all","clauses",Collections.singletonList(obj("inputId","fact","operator","no")),"resultValue","true")));
        outcome.put("effects",Collections.singletonList(obj("quote",quote,"action","fill","branchId","case","valueSourceId","fact")));
        Map<String,Object> rerun=atomicReceipt(plan,Arrays.asList(fact,outcome));String originalFact=text(map(list(rerun.get("nodes")).get(0)).get("id")),originalOutcome=text(map(list(rerun.get("nodes")).get(1)).get("id"));
        Map<String,Object> relation=DraftAutomaticDiscovery.processRelation((system,user)->JsonUtils.write(obj("relations",Collections.singletonList(obj("fromId",originalFact,"toId",originalOutcome,"kind","dependency","fromQuote",quote,"toQuote",quote)))),plan,Collections.singletonList(rerun),DraftAutomaticDiscovery.planRelations(plan,Collections.singletonList(rerun)).get(0));
        List<Map<String,Object>> receipts=Arrays.asList(rerun,relation);String baseBefore=JsonUtils.write(base),receiptsBefore=JsonUtils.write(receipts),oldBefore=JsonUtils.write(old);
        Map<String,Object> finished=DraftAutomaticDiscovery.finish(base,plan,receipts);
        assertEquals(4,nodes(finished).size(),"The seed, reviewed node and both complete new candidates must survive");
        Map<String,Object> newFact=nodes(finished).stream().filter(n->"fact".equals(n.get("modelLocalId"))&&!originalFact.equals(n.get("id"))).findFirst().orElseThrow();
        Map<String,Object> newOutcome=nodes(finished).stream().filter(n->"outcome".equals(n.get("modelLocalId"))).findFirst().orElseThrow();
        assertNotEquals(originalOutcome,newOutcome.get("id"));assertEquals("pending",newFact.get("status"));assertEquals("pending",newOutcome.get("status"));
        Map<String,Object> branch=map(list(map(newOutcome.get("correction")).get("branches")).get(0)),target=map(list(newOutcome.get("editTargets")).get(0));
        assertEquals(newFact.get("id"),map(list(branch.get("clauses")).get(0)).get("inputId"));assertEquals(newFact.get("id"),target.get("valueSourceId"));assertEquals("case",target.get("branchId"));assertTrue(text(target.get("id")).startsWith(text(newOutcome.get("id"))));
        assertEquals(newFact.get("id"),list(newOutcome.get("relatedInputIds")).get(0));assertEquals(newFact.get("id"),list(map(newOutcome.get("correction")).get("inputIds")).get(0));
        assertEquals(Arrays.asList(newFact.get("id"),newOutcome.get("id")),map(list(state(finished).get("requirements")).get(0)).get("nodeIds"));
        Map<String,Object> linked=map(list(state(finished).get("relationProposals")).get(0));assertEquals(newFact.get("id"),linked.get("fromId"));assertEquals(newOutcome.get("id"),linked.get("toId"));
        assertEquals(Arrays.asList(newFact.get("id"),newOutcome.get("id")),nodes(finished).get(0).get("replacedBy"));
        assertEquals(oldBefore,JsonUtils.write(nodes(finished).stream().filter(n->originalFact.equals(n.get("id"))).findFirst().orElseThrow()));
        assertTrue(JsonUtils.write(state(finished).get("automaticDiscoveryIssues")).contains("existing_node_definition_changed"));
        assertEquals(baseBefore,JsonUtils.write(base));assertEquals(receiptsBefore,JsonUtils.write(receipts));
        assertEquals(4,nodes(DraftAutomaticDiscovery.finish(finished,plan,receipts)).size(),"The same isolated candidates are reused on another identical run");validate(finished);
    }

    @Test void identicalReidentificationReusesReviewedNodesWithoutTreatingReviewMetadataAsNewMeaning() {
        String quote="Provide the project name.";ObjectNode source=source("1");member(source,"body",quote,"main");guidance(source,"body",quote);DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));
        Map<String,Object> receipt=atomicReceipt(plan,Collections.singletonList(node("name",quote)));Map<String,Object> base=DraftAutomaticDiscovery.finish(graph(),plan,Collections.singletonList(receipt)),old=nodes(base).get(0);
        old.put("status","confirmed");old.put("manual",true);old.put("catalogAlignment",obj("status","new","confirmed",true,"reason","Reviewed"));String before=JsonUtils.write(old);
        Map<String,Object> finished=DraftAutomaticDiscovery.finish(base,plan,Collections.singletonList(receipt));assertEquals(1,nodes(finished).size());assertEquals(before,JsonUtils.write(nodes(finished).get(0)));
    }

    @Test void isolationRemapsListParentsScopesAndValueSourcesWithoutRewritingSourceEvidenceOrHistory() {
        String quote="Provide the applicable items and their completion decisions.";ObjectNode source=source("1");member(source,"body",quote,"main");guidance(source,"body",quote);DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));
        Map<String,Object> group=node("items",quote),child=node("included",quote),outcome=node("outcome",quote);group.put("type","list");child.put("type","boolean");child.put("parentId","items");outcome.put("type","boolean");outcome.put("role","derived");
        group.put("effects",Collections.singletonList(obj("quote",quote,"action","fill","valueSourceId","items")));
        outcome.put("branches",Collections.singletonList(obj("id","each","mode","all","scopeId","items","scopeMode","each","clauses",Collections.singletonList(obj("inputId","included","operator","yes")),"resultValue","true")));
        outcome.put("effects",Collections.singletonList(obj("quote",quote,"action","fill","branchId","each","valueSourceId","included")));
        Map<String,Object> first=atomicReceipt(plan,Arrays.asList(group,child));Map<String,Object> base=DraftAutomaticDiscovery.finish(graph(),plan,Collections.singletonList(first));
        String oldGroup=text(nodes(base).get(0).get("id")),oldChild=text(nodes(base).get(1).get("id"));nodes(base).get(1).put("questionEn","A different reviewed item flag");
        Map<String,Object> history=obj("id","historic","type",obj("kind","text"),"role",obj("kind","verification"),"status","excluded","replacedBy",Arrays.asList(oldGroup,oldChild));List<Object> original=new ArrayList<>(list(state(base).get("variables")));original.add(history);state(base).put("variables",original);String before=JsonUtils.write(base);
        Map<String,Object> rerun=JsonUtils.readMap(JsonUtils.write(atomicReceipt(plan,Arrays.asList(group,child,outcome))));Map<String,Object> proposed=map(list(rerun.get("nodes")).get(2)),target=map(list(proposed.get("editTargets")).get(0));target.put("valueMode","join");target.put("listId",oldGroup);target.put("itemKey",oldChild);target.put("needsRebind",false);
        map(map(list(proposed.get("sourceAnchors")).get(0)).get("sourceObservation")).put("inputId",oldGroup);
        proposed.put("superseded",Collections.singletonList(obj("inputId",oldChild,"parentId",oldGroup)));proposed.put("customAudit",obj("inputId",oldChild));
        Map<String,Object> finished=DraftAutomaticDiscovery.finish(base,plan,Collections.singletonList(rerun));assertEquals(6,nodes(finished).size());
        List<?> retainedRequirements=list(state(finished).get("requirements"));assertEquals(2,retainedRequirements.size());assertEquals(JsonUtils.write(list(state(base).get("requirements")).get(0)),JsonUtils.write(retainedRequirements.get(0)));
        List<?> refs=list(map(retainedRequirements.get(1)).get("nodeIds"));assertEquals(3,refs.size());String newGroup=text(refs.get(0)),newChild=text(refs.get(1));
        Map<String,Object> isolatedGroup=nodes(finished).stream().filter(n->newGroup.equals(n.get("id"))).findFirst().orElseThrow(),isolatedChild=nodes(finished).stream().filter(n->newChild.equals(n.get("id"))).findFirst().orElseThrow(),isolatedOutcome=nodes(finished).stream().filter(n->refs.get(2).equals(n.get("id"))).findFirst().orElseThrow();
        assertEquals(newGroup,isolatedChild.get("parentId"));assertEquals(newGroup,map(list(map(isolatedOutcome.get("correction")).get("branches")).get(0)).get("scopeId"));
        assertEquals(newGroup,map(list(isolatedGroup.get("editTargets")).get(0)).get("valueSourceId"));Map<String,Object> isolatedTarget=map(list(isolatedOutcome.get("editTargets")).get(0));assertEquals(newGroup,isolatedTarget.get("listId"));assertEquals(newChild,isolatedTarget.get("itemKey"));assertEquals(newChild,isolatedTarget.get("valueSourceId"));
        assertEquals(oldGroup,map(map(list(isolatedOutcome.get("sourceAnchors")).get(0)).get("sourceObservation")).get("inputId"),"Literal source observations never use the graph's replacement namespace");
        assertEquals(proposed.get("superseded"),isolatedOutcome.get("superseded"));assertEquals(proposed.get("customAudit"),isolatedOutcome.get("customAudit"));
        assertEquals(Arrays.asList(oldGroup,oldChild),nodes(finished).stream().filter(n->"historic".equals(n.get("id"))).findFirst().orElseThrow().get("replacedBy"));assertEquals(before,JsonUtils.write(base));
        for(Object id:refs)assertTrue(text(id).matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}"));validate(finished);
    }

    @Test void changedIsolatedCandidatesReceiveAnotherStableIdentityInsteadOfOverwritingOrGrowingOnEveryRun() {
        String quote="Provide the project name.";ObjectNode source=source("1");member(source,"body",quote,"main");guidance(source,"body",quote);DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));Map<String,Object> receipt=atomicReceipt(plan,Collections.singletonList(node("name",quote)));
        Map<String,Object> base=DraftAutomaticDiscovery.finish(graph(),plan,Collections.singletonList(receipt));nodes(base).get(0).put("questionEn","A previously reviewed other question");
        Map<String,Object> second=DraftAutomaticDiscovery.finish(base,plan,Collections.singletonList(receipt));assertEquals(2,nodes(second).size());nodes(second).get(1).put("questionEn","A separately reviewed interpretation");nodes(second).get(1).put("manual",true);String previous=JsonUtils.write(nodes(second));
        Map<String,Object> third=DraftAutomaticDiscovery.finish(second,plan,Collections.singletonList(receipt));assertEquals(3,nodes(third).size());assertEquals(previous,JsonUtils.write(nodes(third).subList(0,2)));assertEquals("pending",nodes(third).get(2).get("status"));
        assertEquals(3,nodes(DraftAutomaticDiscovery.finish(third,plan,Collections.singletonList(receipt))).size());
    }

    private static Map<String,Object> atomicReceipt(DraftAutomaticDiscovery.Plan plan,List<Map<String,Object>> nodes) {
        List<Object> ids=new ArrayList<>();for(Map<String,Object> node:nodes)ids.add(node.get("id"));String quote=text(nodes.get(0).get("sourceQuote"));
        return DraftAutomaticDiscovery.process((system,user)->JsonUtils.write(obj("nodes",nodes,"requirements",Collections.singletonList(obj("id","requirement","sourceQuote",quote,"kind","amendment","nodeIds",ids)))),plan,plan.tasks().get(0));
    }

    private static ObjectNode source(String id){ObjectNode source=JsonUtils.mapper().createObjectNode().put("id",id).put("sourceDocumentId",Long.parseLong(id)).put("sourceSha256",SHA).put("format","docx").put("fileKey","NTT").put("fileName","NTT.docx").put("evidenceStatus","bound");for(String key:Arrays.asList("sourceMembers","paragraphs","guidanceNotes","markerCandidates","groups","conditions"))source.putArray(key);return source;}
    private static void member(ObjectNode source,String id,String text,String story){ObjectNode member=source.withArray("sourceMembers").addObject().put("id",id).put("storyKind",story).put("identityStatus","source_bound");member.putObject("sourcePosition").put("sourceSha256",SHA).put("partName","word/"+story+".xml").put("nodePath","/"+id);member.putObject("textObservation").put("text",text);}
    private static void guidance(ObjectNode source,String memberId,String text){ObjectNode note=source.withArray("guidanceNotes").addObject().put("id","note").put("text",text).put("scopeComplete",false);note.putArray("memberRanges").addObject().put("memberId",memberId).put("channel","text").put("start",0).put("end",text.length());}
    private static Map<String,Object> graph(){return obj("graph",obj("variables",new ArrayList<>(),"events",new ArrayList<>()),"sources",new ArrayList<>());}
    private static Map<String,Object> node(String id,String quote){return obj("id",id,"type","text","role","input","nameZh","项目名称","nameEn","Project name","questionZh","请填写","questionEn","Provide","sourceQuote",quote,"branches",new ArrayList<>(),"effects",new ArrayList<>(),"options",new ArrayList<>());}
}
