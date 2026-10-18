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
