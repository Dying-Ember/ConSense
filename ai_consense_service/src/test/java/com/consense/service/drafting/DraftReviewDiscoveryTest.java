package com.consense.service.drafting;

import com.consense.ai.AiGateway;
import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Persisted synthetic observations and a mocked gateway only; zero parser or real model calls. */
class DraftReviewDiscoveryTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a"));
    private Map<String,Object> graph(){return obj("kind","consense-template-graph-v1","graph",obj("variables",new ArrayList<>(),"events",new ArrayList<>()),"sources",new ArrayList<>());}
    private ObjectNode reading(String id,String instruction) {
        ObjectNode source=JsonUtils.mapper().createObjectNode().put("id",id).put("sourceDocumentId",Long.parseLong(id)).put("fileKey","SCC").put("sourceSha256",SHA).put("evidenceStatus","bound").put("format","docx").put("editionId","scc-docx-"+SHA);
        source.putArray("guidanceNotes").addObject().put("id","note-1").put("text",instruction).put("captureComplete",true).put("scopeComplete",false);
        source.putArray("markerCandidates");source.putArray("groups");source.putArray("sourceMembers");
        source.putArray("paragraphs").addObject().put("id","word/document.xml#/w:document[1]/w:body[1]/w:p[1]").put("paragraphSha256",SHA).put("text",instruction).put("sourceSha256",SHA).put("channel","editableText");return source;
    }
    private Map<String,Object> modelNode(String id,String type,String role,String quote){return obj("id",id,"type",type,"role",role,"nameZh",id,"nameEn",id,"questionEn",id,"sourceQuote",quote,"options",new ArrayList<>(),"branches",new ArrayList<>(),"effects",new ArrayList<>());}
    private String response(Map<String,Object>... nodes){return JsonUtils.write(obj("nodes",Arrays.asList(nodes),"notes",Collections.singletonList("Synthetic candidate only")));}
    private Map<String,Object> find(Map<String,Object> graph,String suffix){return nodes(graph).stream().filter(node->text(node.get("id")).endsWith(suffix)).findFirst().get();}
    private Map<String,Object> checklist(Map<String,Object> graph){return nodes(graph).stream().filter(node->"source_seed".equals(node.get("definitionKind"))).findFirst().get();}

    @Test void seedingPreservesTheCompleteInstructionAndLiteralMarkerWithoutUsingAModel() {
        String instruction="(Guidance Notes: If A applies, review the entire named clause. Do not omit this second sentence.)";
        ObjectNode source=reading("1",instruction);source.withArray("markerCandidates").addObject().put("id","marker-1").put("memberId","member-1").put("symbol","*").putObject("localSentenceObservation").put("text","* Select the applicable source option.");
        Map<String,Object> original=graph(),seeded=DraftReviewDiscovery.seed(original,Collections.singletonList(source));
        assertTrue(nodes(original).isEmpty());assertEquals(2,nodes(seeded).size());assertTrue(fields(seeded).isEmpty());
        Map<String,Object> seed=checklist(seeded);assertEquals("verification",map(seed.get("role")).get("kind"));assertEquals("pending",seed.get("status"));
        Map<String,Object> passage=map(list(seed.get("sourceAnchors")).get(0));assertEquals(instruction,passage.get("text"));assertTrue(passage.containsKey("native"));assertEquals("missing",map(passage.get("nativeBinding")).get("status"));
        assertEquals(2,nodes(DraftReviewDiscovery.seed(seeded,Collections.singletonList(source))).size());
        map(seed.get("guidance")).put("en","Edited display");assertEquals(instruction,source.path("guidanceNotes").path(0).path("text").asText());
    }

    @Test void foreignOrInvalidStoredEvidenceCannotSupplySeedsAndEditionsRemainSeparate() {
        ObjectNode word=reading("1","Same source wording"),pdf=reading("2","Same source wording");pdf.put("format","pdf").put("editionId","scc-pdf-"+SHA);pdf.putArray("paragraphs");
        Map<String,Object> result=DraftReviewDiscovery.seed(graph(),Arrays.asList(word,pdf));assertEquals(2,nodes(result).size());
        assertNotEquals(nodes(result).get(0).get("id"),nodes(result).get(1).get("id"));assertFalse(map(list(nodes(result).get(1).get("sourceAnchors")).get(0)).containsKey("native"));
        word.put("evidenceStatus","invalid");pdf.put("sourceSha256","unbound");assertTrue(nodes(DraftReviewDiscovery.seed(graph(),Arrays.asList(word,pdf))).isEmpty());
    }

    @Test void independentFactsAndAnAllBranchBecomePendingDefinitionsWithNamespacedReferences() {
        String instruction="Use G1a for a combined contract and foundation work when the duration is at least 39 months.";ObjectNode source=reading("1",instruction);
        Map<String,Object> combined=modelNode("combined","boolean","input",instruction),foundation=modelNode("foundation","boolean","input",instruction),duration=modelNode("duration","number","input",instruction),bond=modelNode("bond","choice","derived",instruction);
        bond.put("options",Arrays.asList(obj("value","g1","labelEn","G1"),obj("value","g1a","labelEn","G1a")));
        bond.put("branches",Collections.singletonList(obj("id","long","mode","all","clauses",Arrays.asList(obj("inputId","combined","operator","yes"),obj("inputId","foundation","operator","yes"),obj("inputId","duration","operator","ge","value",39)),"resultValue","g1a")));
        bond.put("effects",Collections.singletonList(obj("quote","G1a","action","replace","branchId","long","valueSourceId","bond")));
        AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenReturn(response(combined,foundation,duration,bond));
        Map<String,Object> result=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(source),1);assertEquals(3,fields(result).size());
        Map<String,Object> derived=find(result,"-bond"),branch=map(list(map(derived.get("correction")).get("branches")).get(0));assertEquals("all",branch.get("mode"));assertEquals(3,list(branch.get("clauses")).size());
        for(Object raw:list(branch.get("clauses")))assertTrue(text(map(raw).get("inputId")).startsWith("discovery-"));
        Map<String,Object> target=map(list(derived.get("editTargets")).get(0));assertEquals("check",target.get("action"));assertEquals(true,target.get("needsRebind"));
        for(Map<String,Object> node:nodes(result))assertEquals("pending",node.get("status"));assertEquals(0,map(state(result).get("discoverySummary")).get("adoptedValues"));
        ArgumentCaptor<String> system=ArgumentCaptor.forClass(String.class);verify(ai,times(1)).complete(system.capture(),anyString());assertTrue(system.getValue().contains("TWO independent booleans"));
        assertEquals("completed",map(checklist(result).get("discovery")).get("status"));assertNotNull(map(checklist(result).get("discovery")).get("rawResponse"));
    }

    @Test void explicitBudgetUsesFreshContextsAndLaterRunsDoNotReplaceReviewedCandidates() {
        String first="First original instruction.",second="Second unrelated original instruction.";List<JsonNode> readings=Arrays.asList(reading("1",first),reading("2",second));AiGateway ai=mock(AiGateway.class);
        when(ai.complete(anyString(),anyString())).thenReturn(response(modelNode("question","text","input",first)),response(modelNode("question","text","input",second)));
        Map<String,Object> one=DraftReviewDiscovery.discover(ai,graph(),readings,1);assertEquals(1,map(state(one).get("discoverySummary")).get("skippedBudget"));
        Map<String,Object> reviewed=find(one,"-question");String reviewedId=text(reviewed.get("id"));reviewed.put("status","confirmed");reviewed.put("nameEn","Human corrected question");
        Map<String,Object> two=DraftReviewDiscovery.discover(ai,one,readings,1);assertEquals(4,nodes(two).size());
        Map<String,Object> preserved=nodes(two).stream().filter(node->reviewedId.equals(node.get("id"))).findFirst().get();assertEquals("confirmed",preserved.get("status"));assertEquals("Human corrected question",preserved.get("nameEn"));
        ArgumentCaptor<String> users=ArgumentCaptor.forClass(String.class);verify(ai,times(2)).complete(anyString(),users.capture());
        assertTrue(users.getAllValues().get(0).contains(first));assertFalse(users.getAllValues().get(0).contains(second));assertTrue(users.getAllValues().get(1).contains(second));assertFalse(users.getAllValues().get(1).contains(first));assertFalse(users.getAllValues().get(1).contains("Human corrected question"));
        DraftReviewDiscovery.discover(ai,two,readings,1);verify(ai,times(2)).complete(anyString(),anyString());
    }

    @Test void invalidOutputRetainsRawFailureAndDoesNotTriggerAnImplicitRetry() {
        ObjectNode source=reading("1","A complete instruction remains visible.");AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenReturn("not json: preserve exactly");
        Map<String,Object> failed=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(source),1),attempt=map(checklist(failed).get("discovery"));
        assertEquals("failed",attempt.get("status"));assertEquals("not json: preserve exactly",attempt.get("rawResponse"));assertFalse(text(attempt.get("reason")).isEmpty());assertTrue(fields(failed).isEmpty());
        Map<String,Object> again=DraftReviewDiscovery.discover(ai,failed,Collections.singletonList(source),1);verify(ai,times(1)).complete(anyString(),anyString());assertEquals(0,map(state(again).get("discoverySummary")).get("attempted"));
        assertTrue(list(state(failed).get("events")).stream().map(DraftReviewGraph::map).anyMatch(event->"not json: preserve exactly".equals(map(event.get("data")).get("rawResponse"))));
    }

    @Test void foreignReferencesAndInventedSourceQuotesStayUnknownInsteadOfPartiallyMerging() {
        String instruction="One exact source instruction.";AiGateway ai=mock(AiGateway.class);Map<String,Object> node=modelNode("local","boolean","input",instruction);node.put("parentId","existing-other-document");when(ai.complete(anyString(),anyString())).thenReturn(response(node));
        Map<String,Object> result=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(reading("1",instruction)),1);assertTrue(fields(result).isEmpty());assertTrue(text(map(checklist(result).get("discovery")).get("reason")).contains("DISCOVERY_FOREIGN_REFERENCE"));
        AiGateway fabricated=mock(AiGateway.class);when(fabricated.complete(anyString(),anyString())).thenReturn(response(modelNode("local","boolean","input","invented source")));
        Map<String,Object> rejected=DraftReviewDiscovery.discover(fabricated,graph(),Collections.singletonList(reading("1",instruction)),1);assertTrue(fields(rejected).isEmpty());assertEquals("DISCOVERY_SOURCE_QUOTE_NOT_IN_INSTRUCTION",map(checklist(rejected).get("discovery")).get("reason"));
    }

    @Test void onlyACompleteObservedScopeWithUniqueEditableTextMayProposeATreatment() {
        String instruction="Delete the marked full clause when the project selects the alternative.",target="* Full clause text.";ObjectNode source=reading("1",instruction);
        source.withArray("paragraphs").addObject().put("id","target-paragraph").put("paragraphSha256",SHA).put("text",target).put("sourceSha256",SHA);
        source.withArray("groups").addObject().put("id","scope-1").put("guidanceNoteId","note-1").put("scopeComplete",true).put("text",target);
        Map<String,Object> node=modelNode("alternative","boolean","decision",instruction);node.put("effects",Collections.singletonList(obj("scopeId","scope-1","quote",target,"action","delete")));
        AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenReturn(response(node));
        Map<String,Object> result=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(source),1),effect=map(list(find(result,"-alternative").get("editTargets")).get(0));
        assertEquals("delete",effect.get("action"));assertEquals("missing",map(effect.get("nativeBinding")).get("status"));assertTrue(effect.containsKey("native"));assertEquals("pending",find(result,"-alternative").get("status"));
        source.put("format","pdf");Map<String,Object> pdf=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(source),1);assertEquals("check",map(list(find(pdf,"-alternative").get("editTargets")).get(0)).get("action"));
    }

    @Test void invalidOrAbsentItemBudgetMakesNoGatewayCall() {
        AiGateway ai=mock(AiGateway.class);assertThrows(BizException.class,()->DraftReviewDiscovery.discover(ai,graph(),Collections.emptyList(),0));assertThrows(BizException.class,()->DraftReviewDiscovery.discover(ai,graph(),Collections.emptyList(),11));verifyNoInteractions(ai);
    }
}
