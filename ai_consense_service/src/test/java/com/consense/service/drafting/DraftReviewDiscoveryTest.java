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

    @Test void sourceRelationshipsDereferenceIndependentConditionsWithoutTurningThemIntoProjectAnswers() {
        ObjectNode source=reading("1","Local guidance remains independent.");
        ObjectNode group=source.withArray("groups").addObject().put("id","child").put("guidanceNoteId","note-1").put("text","Target");
        group.putArray("sourceInstructionRelationships").addObject().put("id","nested").put("kind","nested_source_condition")
                .put("parentGroupId","parent").put("parentGuidanceNoteId","parent-note").put("parentConditionId","parent-condition")
                .put("childGroupId","child").put("childGuidanceNoteId","note-1").put("childConditionId","child-condition")
                .put("status","physical_source_containment_local_conditions_independent");
        source.putArray("conditions").addObject().put("id","parent-condition").put("guidanceNoteId","parent-note").put("text","If the parent applies.");
        source.withArray("conditions").addObject().put("id","child-condition").put("guidanceNoteId","note-1").put("text","If the local choice applies.");
        source.withArray("conditions").addObject().put("id","unrelated").put("guidanceNoteId","foreign").put("text","Unrelated condition must stay outside this call.");
        AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenReturn(response());
        Map<String,Object> result=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(source),1);
        ArgumentCaptor<String> users=ArgumentCaptor.forClass(String.class);verify(ai).complete(anyString(),users.capture());JsonNode payload=JsonUtils.parse(users.getValue());
        assertEquals(2,payload.path("sourceConditions").size());assertEquals(1,payload.path("sourceRelationships").size());
        assertEquals("parent-condition",payload.path("sourceRelationships").path(0).path("parentConditionId").asText());
        assertFalse(users.getValue().contains("Unrelated condition must stay outside"));assertTrue(fields(result).isEmpty());
        assertEquals("pending",checklist(result).get("status"));assertEquals(0,map(state(result).get("discoverySummary")).get("adoptedValues"));
    }

    @Test void unknownMarkerKeepsItsOwnSourceReferencesWhileContextGroupsCannotAuthorizeEdits() {
        // Actual local discover payload with a mock gateway; no real model or business acceptance.
        String instruction="* Review the unresolved source choice.",clause="The whole source clause remains context, including this second sentence.";
        ObjectNode source=reading("1","Use the actual project source for the local choice. Keep this complete second sentence.");
        ObjectNode parentNote=source.withArray("guidanceNotes").addObject().put("id","parent-note").put("text","The parent condition is independent. Preserve its full instruction.").put("captureComplete",true).put("scopeComplete",false);
        ObjectNode localCondition=source.putArray("conditions").addObject().put("id","local-condition").put("guidanceNoteId","note-1").put("text","If the local choice applies.").put("status","source_condition_candidate");
        ObjectNode parentCondition=source.withArray("conditions").addObject().put("id","parent-condition").put("guidanceNoteId","parent-note").put("text","If the independent parent applies.").put("status","source_condition_candidate");
        source.withArray("conditions").addObject().put("id","unrelated-condition").put("guidanceNoteId","unrelated-note").put("text","Unrelated condition stays outside this payload.");
        ObjectNode marker=source.withArray("markerCandidates").addObject().put("id","unknown-marker").put("memberId","marked-member").put("symbol","*").put("disposition","unknown");
        marker.putObject("localSentenceObservation").put("text",instruction);marker.putArray("guidanceNoteIds").add("note-1");marker.putArray("conditionIds").add("local-condition");
        marker.putArray("contextGroupIds").add("field-context").add("whole-context");
        ObjectNode relationship=marker.putArray("sourceInstructionRelationships").addObject().put("id","independent-conditions").put("kind","nested_source_condition")
                .put("parentGroupId","whole-context").put("childGroupId","field-context").put("parentGuidanceNoteId","parent-note").put("childGuidanceNoteId","note-1")
                .put("parentConditionId","parent-condition").put("childConditionId","local-condition").put("status","physical_source_containment_local_conditions_independent");
        ObjectNode field=source.withArray("groups").addObject().put("id","field-context").put("kind","native_source_field_candidate").put("fieldLabel","person_title_choice").put("scopeComplete",false).put("sourceFieldRangeComplete",true).put("text","Mr/Ms");
        field.putArray("guidanceNoteIds").add("note-1");
        ObjectNode whole=source.withArray("groups").addObject().put("id","whole-context").put("kind","source_unit_instruction_scope").put("guidanceNoteId","note-1").put("scopeComplete",true).put("text",clause);
        ObjectNode member=source.withArray("sourceMembers").addObject().put("id","clause-member");member.putObject("textObservation").put("text",clause);
        ObjectNode position=member.putObject("sourcePosition").put("sourceSha256",SHA).put("partName","word/document.xml").put("nodePath","/native/context-clause");
        ObjectNode paragraph=source.withArray("paragraphs").addObject().put("id","editable-context-clause").put("paragraphSha256",SHA).put("sourceSha256",SHA).put("text",clause);paragraph.set("sourcePosition",position.deepCopy());
        whole.putArray("memberRanges").addObject().put("memberId","clause-member").put("channel","text").put("start",0).put("end",clause.length());
        Map<String,Object> seeded=DraftReviewDiscovery.seed(graph(),Collections.singletonList(source));
        for(Map<String,Object> seed:nodes(seeded))if(!"unknown-marker".equals(JsonUtils.mapper().valueToTree(seed.get("sourceObservation")).path("id").asText()))map(seed.get("discovery")).put("status","completed");
        Map<String,Object> proposed=modelNode("choice","boolean","decision",instruction);proposed.put("effects",Arrays.asList(obj("scopeId","field-context","quote","Mr/Ms","action","delete"),obj("scopeId","whole-context","quote",clause,"action","delete")));
        AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenReturn(response(proposed));
        Map<String,Object> result=DraftReviewDiscovery.discover(ai,seeded,Collections.singletonList(source),1);
        ArgumentCaptor<String> users=ArgumentCaptor.forClass(String.class);verify(ai).complete(anyString(),users.capture());JsonNode payload=JsonUtils.parse(users.getValue());
        assertEquals(marker,payload.path("instruction").path("observation"));assertEquals("marker",payload.path("instruction").path("kind").asText());assertEquals(0,payload.path("scopeCandidates").size());
        assertEquals(2,payload.path("relatedGuidanceNotes").size());assertEquals(source.path("guidanceNotes").path(0),payload.path("relatedGuidanceNotes").path(0));assertEquals(parentNote,payload.path("relatedGuidanceNotes").path(1));
        assertEquals(2,payload.path("sourceConditions").size());assertEquals(localCondition,payload.path("sourceConditions").path(0));assertEquals(parentCondition,payload.path("sourceConditions").path(1));
        assertEquals(1,payload.path("sourceRelationships").size());assertEquals(relationship,payload.path("sourceRelationships").path(0));assertEquals(0,payload.path("unresolvedConditionIds").size());assertFalse(users.getValue().contains("Unrelated condition stays outside"));
        Map<String,Object> candidate=find(result,"-choice");assertEquals("pending",candidate.get("status"));assertFalse(candidate.containsKey("fixedValue"));assertEquals(2,list(candidate.get("editTargets")).size());
        for(Object raw:list(candidate.get("editTargets"))){Map<String,Object> target=map(raw);assertEquals("check",target.get("action"));assertEquals("delete",target.get("proposedAction"));assertEquals(false,target.get("scopeComplete"));assertEquals(true,target.get("needsRebind"));assertFalse(target.containsKey("sourceScopeObservation"));}
        assertEquals(0,map(state(result).get("discoverySummary")).get("adoptedValues"));assertEquals("unknown",marker.path("disposition").asText());assertFalse(marker.has("targetGroupIds"));
    }

    @Test void exactBillInsertionPointMapsOnlyTheKnownParagraphNewlineAndKeepsFutureListUnknown() {
        String instruction="Insert all Bill Nos. and descriptions.";ObjectNode source=reading("1",instruction);
        ObjectNode member=source.withArray("sourceMembers").addObject().put("id","bill");
        member.putObject("textObservation").put("text","Bill No. 3 \u2013");
        member.putObject("preNormalizedTextObservation").put("status","observed_parser_channels_before_whitespace_normalization").put("text"," Bill No. 3 \u2013\n");
        ObjectNode position=member.putObject("sourcePosition").put("sourceSha256",SHA).put("partName","word/document.xml").put("nodePath","/native/bill");
        ObjectNode paragraph=source.withArray("paragraphs").addObject().put("id","editable-bill").put("paragraphSha256",SHA).put("sourceSha256",SHA).put("text"," Bill No. 3 \u2013");paragraph.set("sourcePosition",position.deepCopy());
        ObjectNode group=source.withArray("groups").addObject().put("id","bill-scope").put("kind","native_source_field_candidate").put("guidanceNoteId","note-1")
                .put("scopeComplete",false).put("sourceInsertionAnchorComplete",true).put("listCardinalityStatus","unknown_from_existing_numbered_source_rows");
        group.putArray("insertionAnchors").addObject().put("memberId","bill").put("channel","preNormalizedTextObservation.text").put("start",13).put("end",13).put("observedText","");
        Map<String,Object> node=modelNode("description","text","input",instruction);node.put("effects",Collections.singletonList(obj("scopeId","bill-scope","quote","","action","insert","valueSourceId","description")));
        AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenReturn(response(node));
        Map<String,Object> result=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(source),1),target=map(list(find(result,"-description").get("editTargets")).get(0));
        assertEquals("insert",target.get("action"));assertEquals(false,target.get("needsRebind"));assertEquals("",target.get("text"));
        Map<String,Object> span=map(list(map(target.get("native")).get("spans")).get(0));assertEquals(13,span.get("start"));assertEquals("insert",span.get("operation"));
        assertEquals("pending",find(result,"-description").get("status"));assertFalse(find(result,"-description").containsKey("fixedValue"));
        assertEquals("unknown_from_existing_numbered_source_rows",group.path("listCardinalityStatus").asText());
        paragraph.put("text","Bill No. 3 \u2013");Map<String,Object> mismatch=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(source),1);
        assertEquals("check",map(list(find(mismatch,"-description").get("editTargets")).get(0)).get("action"));
    }

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
        Map<String,Object> foundation=modelNode("foundation","boolean","input",instruction),duration=modelNode("duration","number","input",instruction),bond=modelNode("bond","choice","derived",instruction);
        bond.put("options",Arrays.asList(obj("value","g1","labelEn","G1"),obj("value","g1a","labelEn","G1a")));
        bond.put("branches",Collections.singletonList(obj("id","long","mode","all","clauses",Arrays.asList(obj("inputId","foundation","operator","yes"),obj("inputId","duration","operator","ge","value",39)),"resultValue","g1a")));
        bond.put("effects",Collections.singletonList(obj("quote","G1a","action","replace","branchId","long","valueSourceId","bond")));
        AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenReturn(response(foundation,duration,bond));
        Map<String,Object> result=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(source),1);assertEquals(2,fields(result).size());
        Map<String,Object> derived=find(result,"-bond"),branch=map(list(map(derived.get("correction")).get("branches")).get(0));assertEquals("all",branch.get("mode"));assertEquals(2,list(branch.get("clauses")).size());
        for(Object raw:list(branch.get("clauses")))assertTrue(text(map(raw).get("inputId")).startsWith("discovery-"));
        Map<String,Object> target=map(list(derived.get("editTargets")).get(0));assertEquals("check",target.get("action"));assertEquals(true,target.get("needsRebind"));
        for(Map<String,Object> node:nodes(result))assertEquals("pending",node.get("status"));assertEquals(0,map(state(result).get("discoverySummary")).get("adoptedValues"));
        for(Map<String,Object> field:fields(result)){assertEquals("unreviewed",map(field.get("catalogAlignment")).get("status"));assertEquals(false,map(field.get("catalogAlignment")).get("confirmed"));}
        assertFalse(derived.containsKey("catalogAlignment"));
        ArgumentCaptor<String> system=ArgumentCaptor.forClass(String.class);verify(ai,times(1)).complete(system.capture(),anyString());assertTrue(system.getValue().contains("foundation works included is ONE boolean"));assertFalse(system.getValue().contains("TWO independent booleans"));
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

    @Test void truncatedResponseRetainsOnlyCompleteSourceSupportedNodesForManualReview() {
        String instruction="Review the stated duration and independent foundation fact.";
        String raw="{\"nodes\":["+JsonUtils.write(modelNode("duration","number","input",instruction))+",{\"id\":\"foundation\",\"type\":\"boolean\"";
        AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenReturn(raw);
        Map<String,Object> result=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(reading("1",instruction)),1);
        assertEquals(1,fields(result).size());assertEquals("pending",find(result,"-duration").get("status"));
        Map<String,Object> attempt=map(checklist(result).get("discovery"));assertEquals("partial",attempt.get("status"));assertEquals(raw,attempt.get("rawResponse"));
        assertEquals(true,attempt.get("jsonIncomplete"));assertFalse(list(attempt.get("rejectedNodes")).isEmpty());assertEquals(1,map(state(result).get("discoverySummary")).get("partial"));
        validate(result);verify(ai,times(1)).complete(anyString(),anyString());
    }

    @Test void invalidSiblingAndItsDependantsDoNotDiscardIndependentCompleteQuestions() {
        String instruction="Each project fact requires independent source review.";
        Map<String,Object> valid=modelNode("duration","number","input",instruction),bad=modelNode("bad","boolean","input","invented quote"),dependent=modelNode("dependent","boolean","derived",instruction);
        dependent.put("branches",Collections.singletonList(obj("id","case","mode","all","clauses",Collections.singletonList(obj("inputId","bad","operator","yes")),"resultValue","true")));
        AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenReturn(response(valid,bad,dependent));
        Map<String,Object> result=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(reading("1",instruction)),1);
        assertEquals(1,fields(result).size());assertEquals("pending",find(result,"-duration").get("status"));assertEquals(2,nodes(result).size());
        Map<String,Object> attempt=map(checklist(result).get("discovery"));assertEquals("partial",attempt.get("status"));assertEquals(2,list(attempt.get("rejectedNodes")).size());
        assertTrue(JsonUtils.write(attempt).contains("DISCOVERY_SOURCE_QUOTE_NOT_IN_INSTRUCTION"));validate(result);
    }

    @Test void partialAssemblyKeepsAValidClosedDependencyGroupAndRejectsOnlyCyclicSiblings() {
        String instruction="Review independent project facts before deriving a decision.";
        Map<String,Object> decision=modelNode("decision","boolean","derived",instruction),fact=modelNode("fact","boolean","input",instruction),a=modelNode("a","boolean","derived",instruction),b=modelNode("b","boolean","derived",instruction);
        decision.put("branches",Collections.singletonList(obj("id","case","mode","all","clauses",Collections.singletonList(obj("inputId","fact","operator","yes")),"resultValue","true")));
        a.put("branches",Collections.singletonList(obj("id","case","mode","all","clauses",Collections.singletonList(obj("inputId","b","operator","yes")),"resultValue","true")));
        b.put("branches",Collections.singletonList(obj("id","case","mode","all","clauses",Collections.singletonList(obj("inputId","a","operator","yes")),"resultValue","true")));
        AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenReturn(response(decision,a,fact,b));
        Map<String,Object> result=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(reading("1",instruction)),1);
        assertEquals(3,nodes(result).size());assertNotNull(find(result,"-decision"));assertNotNull(find(result,"-fact"));
        assertEquals("partial",map(checklist(result).get("discovery")).get("status"));validate(result);
    }

    @Test void foreignReferencesAndInventedSourceQuotesStayUnknownInsteadOfPartiallyMerging() {
        String instruction="One exact source instruction.";AiGateway ai=mock(AiGateway.class);Map<String,Object> node=modelNode("local","boolean","input",instruction);node.put("parentId","existing-other-document");when(ai.complete(anyString(),anyString())).thenReturn(response(node));
        Map<String,Object> result=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(reading("1",instruction)),1);assertTrue(fields(result).isEmpty());assertTrue(text(map(checklist(result).get("discovery")).get("reason")).contains("DISCOVERY_FOREIGN_REFERENCE"));
        AiGateway fabricated=mock(AiGateway.class);when(fabricated.complete(anyString(),anyString())).thenReturn(response(modelNode("local","boolean","input","invented source")));
        Map<String,Object> rejected=DraftReviewDiscovery.discover(fabricated,graph(),Collections.singletonList(reading("1",instruction)),1);assertTrue(fields(rejected).isEmpty());assertEquals("DISCOVERY_SOURCE_QUOTE_NOT_IN_INSTRUCTION",map(checklist(rejected).get("discovery")).get("reason"));
    }

    @Test void sourceSupportedQuestionSurvivesBadEdgesAndLongModelIdsWithoutAcquiringEditAuthority() {
        String instruction="(Delete if precast façade is not applicable)",local="q:precast_facade_applicable";
        Map<String,Object> node=modelNode(local,"boolean","input",instruction);
        node.put("branches",Collections.singletonList(obj("id","yes","mode","all","scopeId","source_unit_instruction_scope:external-source-key","scopeMode","each","clauses",Collections.singletonList(obj("inputId",local,"operator","yes")),"resultValue","keep")));
        String raw=response(node);AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenReturn(raw);
        Map<String,Object> result=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(reading("1",instruction)),1);
        Map<String,Object> core=nodes(result).stream().filter(n->local.equals(n.get("modelLocalId"))).findFirst().orElseThrow();
        assertEquals(1,fields(result).size());assertEquals("boolean",map(core.get("type")).get("kind"));assertEquals("input",map(core.get("role")).get("kind"));assertEquals("pending",core.get("status"));assertEquals(instruction,core.get("semanticSourceQuote"));
        assertTrue(text(core.get("id")).matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"));assertTrue(Boolean.TRUE.equals(map(core.get("correction")).get("needsRebind")));assertTrue(list(core.get("editTargets")).isEmpty());assertTrue(list(core.get("relatedInputIds")).isEmpty());
        Map<String,Object> attempt=map(checklist(result).get("discovery"));assertEquals("partial",attempt.get("status"));assertEquals(raw,attempt.get("rawResponse"));assertEquals(1,list(attempt.get("localIdMappings")).size());assertFalse(list(attempt.get("rejectedNodes")).isEmpty());assertEquals(0,map(state(result).get("discoverySummary")).get("adoptedValues"));
        validate(result);verify(ai,times(1)).complete(anyString(),anyString());
    }

    @Test void remappedLocalIdsKeepOnlyExplicitUniqueLocalReferencesConsistent() {
        String instruction="Review the project fact before deriving the local decision.",local="q:project_fact";
        Map<String,Object> input=modelNode(local,"boolean","input",instruction),decision=modelNode("decision","boolean","derived",instruction);
        decision.put("branches",Collections.singletonList(obj("id","yes","mode","all","clauses",Collections.singletonList(obj("inputId",local,"operator","yes")),"resultValue","true")));
        AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenReturn(response(input,decision));
        Map<String,Object> result=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(reading("1",instruction)),1);Map<String,Object> mapped=nodes(result).stream().filter(n->local.equals(n.get("modelLocalId"))).findFirst().orElseThrow();
        Map<String,Object> clause=map(list(map(list(map(find(result,"-decision").get("correction")).get("branches")).get(0)).get("clauses")).get(0));assertEquals(mapped.get("id"),clause.get("inputId"));assertEquals("completed",map(checklist(result).get("discovery")).get("status"));validate(result);
        when(ai.complete(anyString(),anyString())).thenReturn(response(input,input));Map<String,Object> duplicates=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(reading("1",instruction)),1);assertTrue(fields(duplicates).isEmpty());assertEquals("failed",map(checklist(duplicates).get("discovery")).get("status"));
    }

    @Test void graphInvalidEdgesMayLeaveAQuestionCoreButInvalidCoreOptionsOrParentScopeCannotBeRescued() {
        String instruction="One exact supported question.";AiGateway ai=mock(AiGateway.class);
        Map<String,Object> invalidOperator=modelNode("fact","boolean","input",instruction);invalidOperator.put("branches",Collections.singletonList(obj("id","bad","mode","all","clauses",Collections.singletonList(obj("inputId","fact","operator","invented")))));
        when(ai.complete(anyString(),anyString())).thenReturn(response(invalidOperator));Map<String,Object> result=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(reading("1",instruction)),1);
        assertEquals(1,fields(result).size());assertEquals(true,map(find(result,"-fact").get("correction")).get("needsRebind"));assertEquals("partial",map(checklist(result).get("discovery")).get("status"));validate(result);
        Map<String,Object> noOptions=modelNode("choice","choice","input",instruction);noOptions.put("branches",invalidOperator.get("branches"));when(ai.complete(anyString(),anyString())).thenReturn(response(noOptions));assertTrue(fields(DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(reading("1",instruction)),1)).isEmpty());
        Map<String,Object> parent=modelNode("parent","boolean","input",instruction),child=modelNode("child","text","input",instruction);child.put("parentId","parent");when(ai.complete(anyString(),anyString())).thenReturn(response(parent,child));Map<String,Object> scoped=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(reading("1",instruction)),1);assertEquals(1,fields(scoped).size());assertNotNull(find(scoped,"-parent"));assertTrue(nodes(scoped).stream().noneMatch(n->text(n.get("id")).endsWith("-child")));validate(scoped);
    }

    @Test void parentAndChildQuestionCoresSurviveBadRulesInEitherResponseOrderWithoutDroppingParentScope() {
        String instruction="Review the parent details and this independent child fact.";
        for(boolean childFirst:Arrays.asList(true,false)) {
            Map<String,Object> parent=modelNode("parent","record","input",instruction),child=modelNode("child","boolean","input",instruction);child.put("parentId","parent");
            parent.put("branches",Collections.singletonList(obj("id","bad","mode","all","clauses",Collections.singletonList(obj("inputId","child","operator","invented")))));
            child.put("branches",Collections.singletonList(obj("id","bad","mode","all","clauses",Collections.singletonList(obj("inputId","child","operator","invented")))));
            AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenReturn(childFirst?response(child,parent):response(parent,child));
            Map<String,Object> result=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(reading("1",instruction)),1);assertEquals(3,nodes(result).size(),"Both supported cores plus the original source seed: childFirst="+childFirst);
            Map<String,Object> savedParent=find(result,"-parent"),savedChild=find(result,"-child");assertEquals(savedParent.get("id"),savedChild.get("parentId"));
            for(Map<String,Object> core:Arrays.asList(savedParent,savedChild)){assertEquals("pending",core.get("status"));assertEquals(true,map(core.get("correction")).get("needsRebind"));assertTrue(list(core.get("editTargets")).isEmpty());assertEquals(instruction,core.get("semanticSourceQuote"));}
            assertEquals("partial",map(checklist(result).get("discovery")).get("status"));validate(result);
        }
    }

    @Test void mutuallyReferencedBadInputRulesCannotBlockEitherIndependentQuestionCore() {
        String instruction="The two project facts require separate source review.";
        for(boolean reversed:Arrays.asList(false,true)) {
            Map<String,Object> first=modelNode("first","boolean","input",instruction),second=modelNode("second","boolean","input",instruction);
            first.put("branches",Collections.singletonList(obj("id","bad","mode","all","clauses",Collections.singletonList(obj("inputId","second","operator","invented")))));
            second.put("branches",Collections.singletonList(obj("id","bad","mode","all","clauses",Collections.singletonList(obj("inputId","first","operator","invented")))));
            AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenReturn(reversed?response(second,first):response(first,second));
            Map<String,Object> result=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(reading("1",instruction)),1);assertEquals(2,fields(result).size());
            for(String id:Arrays.asList("first","second")){Map<String,Object> core=find(result,"-"+id);assertNull(core.get("parentId"));assertEquals(true,map(core.get("correction")).get("needsRebind"));assertTrue(list(core.get("relatedInputIds")).isEmpty());assertFalse(core.containsKey("fixedValue"));assertEquals("pending",core.get("status"));}
            assertEquals("partial",map(checklist(result).get("discovery")).get("status"));validate(result);
        }
    }

    @Test void validMutualInputConditionsRemainUsableWithoutInventingAnInputCycleProhibition() {
        String instruction="The two independently answered project facts jointly inform treatment.";
        Map<String,Object> first=modelNode("first","boolean","input",instruction),second=modelNode("second","boolean","input",instruction);
        first.put("branches",Collections.singletonList(obj("id","first_case","mode","all","clauses",Collections.singletonList(obj("inputId","second","operator","yes")))));
        second.put("branches",Collections.singletonList(obj("id","second_case","mode","all","clauses",Collections.singletonList(obj("inputId","first","operator","yes")))));
        AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenReturn(response(first,second));Map<String,Object> result=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(reading("1",instruction)),1);
        assertEquals(2,fields(result).size());assertEquals("completed",map(checklist(result).get("discovery")).get("status"));
        for(String id:Arrays.asList("first","second")){Map<String,Object> node=find(result,"-"+id);assertEquals("pending",node.get("status"));assertFalse(Boolean.TRUE.equals(map(node.get("correction")).get("needsRebind")));assertFalse(node.containsKey("fixedValue"));}
        validate(result);
    }

    @Test void parentCyclesRemainRejectedEvenWhenBothQuestionsHaveOtherwiseUsableCores() {
        String instruction="Keep the actual detail ownership unknown when it is invalid.";
        Map<String,Object> first=modelNode("first","record","input",instruction),second=modelNode("second","record","input",instruction);first.put("parentId","second");second.put("parentId","first");
        for(Map<String,Object> node:Arrays.asList(first,second))node.put("branches",Collections.singletonList(obj("id","bad","mode","all","clauses",Collections.singletonList(obj("inputId","first","operator","invented")))));
        AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenReturn(response(first,second));Map<String,Object> result=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(reading("1",instruction)),1);
        assertTrue(fields(result).isEmpty());assertEquals(1,nodes(result).size());assertEquals("failed",map(checklist(result).get("discovery")).get("status"));validate(result);
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

    @Test void explicitNullOptionalFieldsMatchOmittedFieldsWithoutDiscardingTheQuestionOrTreatment() {
        String instruction="Delete the full clause if the fact applies.",target="* Complete source clause.";
        ObjectNode source=reading("1",instruction);
        source.withArray("paragraphs").addObject().put("id","target-paragraph").put("paragraphSha256",SHA).put("text",target).put("sourceSha256",SHA);
        source.withArray("groups").addObject().put("id","scope-1").put("guidanceNoteId","note-1").put("scopeComplete",true).put("text",target);
        Map<String,Object> node=modelNode("fact","boolean","input",instruction);
        node.put("branches",Collections.singletonList(obj("id","yes","mode","all","clauses",Collections.singletonList(obj("inputId","fact","operator","yes")))));
        node.put("effects",Collections.singletonList(obj("scopeId","scope-1","quote",target,"action","delete")));
        AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenReturn(response(node));
        Map<String,Object> expected=find(DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(source),1),"-fact");
        assertEquals("delete",map(list(expected.get("editTargets")).get(0)).get("action"));
        assertAll(Arrays.asList("parentId","scopeId","scopeMode","branchId","valueSourceId","replacementEn").stream().map(key->(org.junit.jupiter.api.function.Executable)()->{
            Map<String,Object> nullable=JsonUtils.readMap(JsonUtils.write(node));
            Map<String,Object> owner="parentId".equals(key)?nullable:Arrays.asList("scopeId","scopeMode").contains(key)?map(list(nullable.get("branches")).get(0)):map(list(nullable.get("effects")).get(0));
            owner.put(key,null);String raw=response(nullable);when(ai.complete(anyString(),anyString())).thenReturn(raw);
            Map<String,Object> result=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(source),1);
            assertEquals(1,fields(result).size(),key+" must keep the complete question");
            assertEquals(expected,find(result,"-fact"),key+" null must behave like omission");
            assertEquals("completed",map(checklist(result).get("discovery")).get("status"),key);validate(result);
        }));
    }

    @Test void literalNullStringRemainsAnExplicitLocalReferenceAndReplacement() {
        String instruction="Review the complete clause.",target="* Complete source clause.";ObjectNode source=reading("1",instruction);
        source.withArray("paragraphs").addObject().put("id","target-paragraph").put("paragraphSha256",SHA).put("text",target).put("sourceSha256",SHA);
        source.withArray("groups").addObject().put("id","scope-1").put("guidanceNoteId","note-1").put("scopeComplete",true).put("text",target);
        Map<String,Object> question=modelNode("fact","boolean","input",instruction),literal=modelNode("null","text","input",instruction);
        question.put("effects",Collections.singletonList(obj("scopeId","scope-1","quote",target,"action","check","valueSourceId","null","replacementEn","null")));
        AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenReturn(response(question,literal));
        Map<String,Object> result=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(source),1),effect=map(list(find(result,"-fact").get("editTargets")).get(0));
        assertEquals(find(result,"-null").get("id"),effect.get("valueSourceId"));assertEquals("null",effect.get("replacementEn"));
        assertEquals("completed",map(checklist(result).get("discovery")).get("status"));validate(result);
    }

    @Test void foreignStringReferencesStillIsolateBadPartsAndKeepIndependentQuestions() {
        String instruction="Review every independent fact.",target="* Complete source clause.";ObjectNode source=reading("1",instruction);
        source.withArray("paragraphs").addObject().put("id","target-paragraph").put("paragraphSha256",SHA).put("text",target).put("sourceSha256",SHA);
        source.withArray("groups").addObject().put("id","scope-1").put("guidanceNoteId","note-1").put("scopeComplete",true).put("text",target);
        for(String key:Arrays.asList("parentId","scopeId","branchId","valueSourceId")) {
            Map<String,Object> bad=modelNode("bad","boolean","input",instruction),independent=modelNode("independent","boolean","input",instruction);
            Map<String,Object> branch=obj("id","yes","mode","all","clauses",Collections.singletonList(obj("inputId","bad","operator","yes")));
            Map<String,Object> effect=obj("scopeId","scope-1","quote",target,"action","delete");bad.put("branches",Collections.singletonList(branch));bad.put("effects",Collections.singletonList(effect));
            ("parentId".equals(key)?bad:"scopeId".equals(key)?branch:effect).put(key,"null");
            AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenReturn(response(bad,independent));
            Map<String,Object> result=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(source),1);
            assertEquals("pending",find(result,"-independent").get("status"));assertEquals("partial",map(checklist(result).get("discovery")).get("status"),key);
            assertFalse(list(map(checklist(result).get("discovery")).get("rejectedNodes")).isEmpty(),key);
            if(!"parentId".equals(key)){assertTrue(list(find(result,"-bad").get("editTargets")).isEmpty(),key);assertEquals(true,map(find(result,"-bad").get("correction")).get("needsRebind"),key);}
            validate(result);
        }
    }

    @Test void invalidOrAbsentItemBudgetMakesNoGatewayCall() {
        AiGateway ai=mock(AiGateway.class);assertThrows(BizException.class,()->DraftReviewDiscovery.discover(ai,graph(),Collections.emptyList(),0));assertThrows(BizException.class,()->DraftReviewDiscovery.discover(ai,graph(),Collections.emptyList(),11));verifyNoInteractions(ai);
    }

    @Test void namedChannelFieldsAndInsertionAnchorsReachDiscoveryWithoutInventingEditableScopes() {
        ObjectNode source=reading("1","(Guidance Notes: Insert all Bill Nos. and descriptions.)");
        ObjectNode fieldMember=source.withArray("sourceMembers").addObject().put("id","field-member");fieldMember.putObject("preNormalizedTextObservation").put("text","Mr/Ms      )");fieldMember.putObject("textObservation").put("text","Mr/Ms )");
        ObjectNode field=source.withArray("groups").addObject().put("id","field-1").put("kind","native_source_field_candidate")
                .put("fieldLabel","person_name").put("sourceFieldRangeComplete",true).put("scopeComplete",false).put("requiredAction","project_source_value_required");
        field.putArray("guidanceNoteIds").add("note-1");field.putArray("memberRanges").addObject().put("memberId","field-member").put("channel","preNormalizedTextObservation.text").put("start",5).put("end",11);
        ObjectNode insertion=source.withArray("groups").addObject().put("id","insertion-1").put("kind","native_source_field_candidate")
                .put("fieldLabel","bill_description").put("sourceInsertionAnchorComplete",true).put("scopeComplete",false).put("listCardinalityStatus","unknown_from_existing_numbered_source_rows");
        insertion.putArray("guidanceNoteIds").add("note-1");insertion.putArray("orderedMemberIds").add("field-member");insertion.putArray("insertionAnchors").addObject().put("memberId","field-member").put("channel","preNormalizedTextObservation.text").put("start",11).put("end",11);
        AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenReturn(response());
        DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(source),1);
        ArgumentCaptor<String> users=ArgumentCaptor.forClass(String.class);verify(ai).complete(anyString(),users.capture());JsonNode scopes=JsonUtils.parse(users.getValue()).path("scopeCandidates");
        assertEquals("      ",scopes.path(0).path("text").asText());
        assertEquals(field,scopes.path(0).path("observation"));assertEquals(insertion,scopes.path(1).path("observation"));
        assertFalse(scopes.path(1).path("scopeComplete").asBoolean());assertEquals("",scopes.path(1).path("text").asText());
    }

    @Test void completeObservedMemberAndNestedTargetScopesReachTheModelInOriginalOrder() {
        ObjectNode source=reading("1","(Guidance Notes: Select the complete alternative.)");
        for(String id:Arrays.asList("first","second"))source.withArray("sourceMembers").addObject().put("id",id).putObject("textObservation").put("text",id+" clause member.");
        ObjectNode alternative=source.withArray("groups").addObject().put("id","alternative-1").put("kind","complete_alternative_scope").put("scopeComplete",true).put("guidanceNoteId","note-1");
        alternative.putArray("orderedMemberIds").add("first").add("second");
        ObjectNode reference=source.withArray("groups").addObject().put("id","reference-1").put("kind","explicit_reference_scope").put("scopeComplete",true).put("guidanceNoteId","note-1");
        reference.putArray("targets").addObject().putArray("memberRanges").addObject().put("memberId","second").put("channel","text").put("start",0).put("end",6);
        AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenReturn(response());DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(source),1);
        ArgumentCaptor<String> users=ArgumentCaptor.forClass(String.class);verify(ai).complete(anyString(),users.capture());JsonNode scopes=JsonUtils.parse(users.getValue()).path("scopeCandidates");
        assertEquals("first clause member.\nsecond clause member.",scopes.path(0).path("text").asText());assertEquals("second",scopes.path(1).path("text").asText());
        assertEquals(reference,scopes.path(1).path("observation"));
    }

    @Test void unmarkedFieldWithoutDedicatedGuidanceRemainsAVisiblePendingSourceItem() {
        ObjectNode source=reading("1","Unrelated guidance.");source.putArray("guidanceNotes");
        source.withArray("sourceMembers").addObject().put("id","range-member").putObject("textObservation").put("text","Bills Nos. 1 to ______");
        ObjectNode field=source.withArray("groups").addObject().put("id","unmarked-field").put("kind","native_source_field_candidate").put("fieldLabel","bill_range_endpoint")
                .put("scopeComplete",false).put("sourceFieldRangeComplete",true).put("fieldValueStatus","unknown_requires_project_source");
        field.putArray("guidanceNoteIds");field.putArray("memberRanges").addObject().put("memberId","range-member").put("channel","text").put("start",15).put("end",21);
        Map<String,Object> seeded=DraftReviewDiscovery.seed(graph(),Collections.singletonList(source));assertEquals(1,nodes(seeded).size());
        Map<String,Object> item=checklist(seeded);assertEquals("pending",item.get("status"));assertEquals("verification",map(item.get("role")).get("kind"));assertEquals(field,JsonUtils.mapper().valueToTree(item.get("sourceObservation")));
        AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenReturn(response());DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(source),1);
        ArgumentCaptor<String> users=ArgumentCaptor.forClass(String.class);verify(ai).complete(anyString(),users.capture());assertTrue(users.getValue().contains("Bills Nos. 1 to ______"));assertTrue(fields(seeded).isEmpty());
    }

    @Test void completeMultiParagraphScopesUseOnlySourcePositionAndEqualNamedChannelsForEditableSpans() {
        String instruction="Delete the complete alternative if it does not apply.";ObjectNode source=reading("1",instruction);
        for(String id:Arrays.asList("first","second")) {
            ObjectNode member=source.withArray("sourceMembers").addObject().put("id",id);member.putObject("textObservation").put("text",id+" clause member.");
            ObjectNode position=member.putObject("sourcePosition").put("sourceSha256",SHA).put("partName","word/document.xml").put("nodePath","/native/"+id);
            ObjectNode paragraph=source.withArray("paragraphs").addObject().put("id","editable-"+id).put("paragraphSha256",SHA).put("text",id+" clause member.").put("sourceSha256",SHA);paragraph.set("sourcePosition",position.deepCopy());
        }
        ObjectNode scope=source.withArray("groups").addObject().put("id","scope-1").put("guidanceNoteId","note-1").put("scopeComplete",true);
        for(String id:Arrays.asList("first","second"))scope.withArray("memberRanges").addObject().put("memberId",id).put("channel","text").put("start",0).put("end",id.length()+15);
        Map<String,Object> node=modelNode("alternative","boolean","decision",instruction);node.put("effects",Collections.singletonList(obj("scopeId","scope-1","quote","first clause member.\nsecond clause member.","action","delete")));
        AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenReturn(response(node));Map<String,Object> result=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(source),1);
        Map<String,Object> target=map(list(find(result,"-alternative").get("editTargets")).get(0));assertEquals("delete",target.get("action"));assertEquals(2,list(map(target.get("native")).get("spans")).size());assertEquals("pending",find(result,"-alternative").get("status"));
        for(String action:Arrays.asList("replace","fill")) {
            node.put("effects",Collections.singletonList(obj("scopeId","scope-1","quote","first clause member.\nsecond clause member.","action",action)));
            when(ai.complete(anyString(),anyString())).thenReturn(response(node));Map<String,Object> amendment=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(source),1);
            Map<String,Object> pending=map(list(find(amendment,"-alternative").get("editTargets")).get(0));assertEquals("check",pending.get("action"));assertEquals(action,pending.get("proposedAction"));assertEquals(true,pending.get("needsRebind"));assertEquals(2,list(map(pending.get("native")).get("spans")).size());
        }
        ((ObjectNode)source.path("sourceMembers").path(0).path("textObservation")).put("text","first  clause member.");
        Map<String,Object> changed=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(source),1);assertEquals("check",map(list(find(changed,"-alternative").get("editTargets")).get(0)).get("action"));
    }

    @Test void explicitMemberRangesCannotBorrowALiteralMatchFromAnotherParagraphOrInvalidUtf16Boundary() {
        String instruction="Review the selected exact source span.",target="A conditional paragraph.";ObjectNode source=reading("1",instruction);
        source.withArray("paragraphs").addObject().put("id","unrelated-literal-match").put("paragraphSha256",SHA).put("sourceSha256",SHA).put("text",target);
        ObjectNode member=source.withArray("sourceMembers").addObject().put("id","body");member.putObject("textObservation").put("text",target);
        member.putObject("sourcePosition").put("sourceSha256",SHA).put("partName","word/document.xml").put("nodePath","/source/absent");
        ObjectNode scope=source.withArray("groups").addObject().put("id","scope-1").put("guidanceNoteId","note-1").put("scopeComplete",true);
        ObjectNode range=scope.putArray("memberRanges").addObject().put("memberId","body").put("channel","text").put("start",0).put("end",target.length());
        Map<String,Object> node=modelNode("selection","boolean","decision",instruction);node.put("effects",Collections.singletonList(obj("scopeId","scope-1","quote",target,"action","delete")));
        AiGateway ai=mock(AiGateway.class);when(ai.complete(anyString(),anyString())).thenReturn(response(node));
        Map<String,Object> result=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(source),1),effect=map(list(find(result,"-selection").get("editTargets")).get(0));
        assertEquals("check",effect.get("action"));assertFalse(effect.containsKey("native"));
        String actual="Different actual text.";ObjectNode mapped=source.withArray("paragraphs").addObject().put("id","source-paragraph").put("paragraphSha256",SHA).put("sourceSha256",SHA).put("text",actual);mapped.set("sourcePosition",member.path("sourcePosition").deepCopy());
        ((ObjectNode)member.path("textObservation")).put("text",actual);range.put("end",actual.length());scope.put("text",target);
        Map<String,Object> inconsistent=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(source),1);assertEquals("check",map(list(find(inconsistent,"-selection").get("editTargets")).get(0)).get("action"));
        scope.remove("text");((ObjectNode)member.path("textObservation")).put("text","\uD83D\uDE00");range.put("start",0).put("end",1);
        Map<String,Object> split=DraftReviewDiscovery.discover(ai,graph(),Collections.singletonList(source),1);assertEquals("check",map(list(find(split,"-selection").get("editTargets")).get(0)).get("action"));
        ArgumentCaptor<String> users=ArgumentCaptor.forClass(String.class);verify(ai,times(3)).complete(anyString(),users.capture());assertEquals("",JsonUtils.parse(users.getAllValues().get(2)).path("scopeCandidates").path(0).path("text").asText());
    }
}
