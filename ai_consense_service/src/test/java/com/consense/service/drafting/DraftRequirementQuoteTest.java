package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Synthetic current-source contracts only; this test does not invoke a provider or parser. */
class DraftRequirementQuoteTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a"));
    private static final String FIRST="*(c) The original hard copy of the Bills of Quantities and General Summary shall be priced and submitted in Envelope 2 as detailed in Condition SCT5(4).";
    private static final String DVD="The tenderer shall also include a DVD-ROM containing the electronic files of the priced Bills of Quantities and General Summary as detailed in Conditions SCT3(2) and SCT3(3).";
    private static final String FULL=FIRST+" "+DVD+" The DVD-ROM shall not form part of the Contract in the event of the tender being accepted.";

    @Test void strictProofRemainsStrictWhileRequirementRecordsItsUniqueCurrentScopeCorrection() {
        ObjectNode source=source();Map<String,Object> task=task(source),diagnostic=new LinkedHashMap<>();String before=source.toString();
        assertNull(DraftReviewDiscovery.sourceTaskQuote(source,task,DVD,"marker",Collections.emptyList()));
        Map<String,Object> corrected=resolve(source,task,DVD,"marker",Collections.emptyList(),diagnostic);
        assertNotNull(corrected);assertEquals("scope",corrected.get("kind"));assertEquals("scope",corrected.get("sourceObservationId"));assertEquals("marker",corrected.get("modelClaimedObservationId"));
        assertEquals("unique_authorized_current_source_quote",corrected.get("normalizationReason"));assertEquals("pending",corrected.get("status"));assertEquals(false,corrected.get("editableAuthority"));assertEquals(FULL,corrected.get("sourceScopeText"));assertEquals(before,source.toString());
        assertNull(DraftReviewDiscovery.sourceTaskQuote(source,task,DVD,"marker",Collections.emptyList()),"Node and strict quote proof must not inherit requirement normalization");
        assertFalse(DraftReviewDiscovery.sourceTaskQuote(source,task,DVD,"scope",Collections.emptyList()).containsKey("normalizationReason"));
    }

    @Test void duplicateAuthorizedScopesAndRepeatedPassagesCannotBeChosenByCorrection() {
        ObjectNode source=source();ObjectNode second=((ObjectNode)source.path("groups").path(0)).deepCopy();second.put("id","second-scope");source.withArray("groups").add(second);((ObjectNode)source.path("markerCandidates").path(0)).withArray("targetGroupIds").add("second-scope");
        Map<String,Object> diagnostic=new LinkedHashMap<>();assertNull(resolve(source,task(source),DVD,"marker",Collections.emptyList(),diagnostic));assertEquals("requirement_quote_ambiguous_in_authorized_evidence",diagnostic.get("reason"));assertEquals(2,diagnostic.get("matchingObservationCount"));
        source=source();String repeated=FIRST+" "+DVD+" "+DVD;((ObjectNode)source.path("sourceMembers").path(0).path("textObservation")).put("text",repeated);((ObjectNode)source.path("groups").path(0)).put("text",repeated);((ObjectNode)source.path("groups").path(0).path("memberRanges").path(0)).put("end",repeated.length());
        diagnostic.clear();assertNull(resolve(source,task(source),DVD,"marker",Collections.emptyList(),diagnostic));assertEquals("requirement_quote_ambiguous_in_authorized_evidence",diagnostic.get("reason"));
    }

    @Test void fallbackDoesNotUseTheMainInstructionEarlyReturnToHideAnotherMatch() {
        ObjectNode source=source();source.withArray("conditions").addObject().put("id","condition").put("text","Alternative (b) applies only to hardcopy BQ.");((ObjectNode)source.path("markerCandidates").path(0)).withArray("conditionIds").add("condition");Map<String,Object> task=task(source),diagnostic=new LinkedHashMap<>();
        assertNotNull(DraftReviewDiscovery.sourceTaskQuote(source,task,FIRST,"",Collections.emptyList()),"Existing strict interface remains unchanged");
        assertNull(resolve(source,task,FIRST,"condition",Collections.emptyList(),diagnostic));assertEquals("requirement_quote_ambiguous_in_authorized_evidence",diagnostic.get("reason"));assertEquals(new HashSet<>(Arrays.asList("marker","scope")),new HashSet<>(list(diagnostic.get("matchingObservationIds"))));
    }

    @Test void foreignObservationLabelsAndUnrelatedReadingScopesDoNotGainAuthority() {
        ObjectNode source=source();source.withArray("guidanceNotes").addObject().put("id","other-task-note").put("text","Other task evidence.");Map<String,Object> diagnostic=new LinkedHashMap<>();
        assertNull(resolve(source,task(source),DVD,"other-task-note",Collections.emptyList(),diagnostic));assertEquals("requirement_quote_observation_not_authorized",diagnostic.get("reason"));
        ((ObjectNode)source.path("markerCandidates").path(0)).putArray("targetGroupIds");diagnostic.clear();assertNull(resolve(source,task(source),DVD,"marker",Collections.emptyList(),diagnostic));assertEquals("requirement_quote_not_in_authorized_evidence",diagnostic.get("reason"));
    }

    @Test void onlyExplicitCurrentSupplementalReceiptsPermitTheUnrelatedScope() {
        ObjectNode source=source();((ObjectNode)source.path("markerCandidates").path(0)).putArray("targetGroupIds");Map<String,Object> task=task(source),receipt=obj("sourceId","1","sourceSha256",SHA,"kind","scope","id","scope"),diagnostic=new LinkedHashMap<>();
        assertNotNull(resolve(source,task,DVD,"marker",Collections.singletonList(receipt),diagnostic));
        for(Map<String,Object> patch:Arrays.asList(obj("sourceId","2"),obj("sourceSha256",String.join("",Collections.nCopies(64,"b"))),obj("analysisRevision","old"),obj("previewOnly",true),obj("kind","fixed_catalog_embedded_reference"))) {
            Map<String,Object> stale=new LinkedHashMap<>(receipt);stale.putAll(patch);diagnostic.clear();assertNull(resolve(source,task,DVD,"marker",Collections.singletonList(stale),diagnostic),JsonUtils.write(patch));assertEquals("requirement_quote_not_in_authorized_evidence",diagnostic.get("reason"));
        }
        receipt.put("text","Fabricated receipt text.");diagnostic.clear();assertNull(resolve(source,task,"Fabricated receipt text.","marker",Collections.singletonList(receipt),diagnostic));
    }

    @Test void staleMemberPositionsAndChangedTaskSourceRemainUnresolved() {
        ObjectNode source=source();Map<String,Object> task=task(source),diagnostic=new LinkedHashMap<>();((ObjectNode)source.path("sourceMembers").path(0).path("sourcePosition")).put("sourceSha256",String.join("",Collections.nCopies(64,"b")));
        assertNull(resolve(source,task,DVD,"marker",Collections.emptyList(),diagnostic));assertEquals("requirement_quote_current_ranges_unverified",diagnostic.get("reason"));
        task=new LinkedHashMap<>(task);task.put("sourceSha256","changed");diagnostic.clear();assertNull(resolve(source,task,DVD,"marker",Collections.emptyList(),diagnostic));assertEquals("requirement_quote_source_identity_mismatch",diagnostic.get("reason"));
    }

    @Test void singleAndGroupCallersKeepTheCorrectedRequirementPendingWithoutFailFast() {
        ObjectNode source=source();DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));Map<String,Object> task=plan.tasks().get(0);String owner=text(task.get("taskId"));
        Map<String,Object> requirement=requirement(owner);String raw=JsonUtils.write(obj("nodes",Collections.emptyList(),"requirements",Collections.singletonList(requirement),"taskOutcomes",Collections.singletonList(obj("sourceTaskId",owner,"status","identified")),"notes",Collections.emptyList()));
        int[] calls={0};Map<String,Object> single=DraftAutomaticDiscovery.process((system,user)->{calls[0]++;return raw;},plan,task,true);assertEquals(1,calls[0]);assertEquals("completed",single.get("status"));assertTrue(list(single.get("stopReasons")).isEmpty());assertCorrected(map(list(single.get("requirements")).get(0)));assertEquals(raw,single.get("rawResponse"));
        calls[0]=0;Map<String,Object> group=DraftAutomaticDiscovery.processGroup((system,user)->{calls[0]++;return raw;},plan,DraftAutomaticDiscovery.planGroups(plan).get(0),true);assertEquals(1,calls[0]);assertEquals("completed",group.get("status"));assertTrue(list(group.get("stopReasons")).isEmpty());assertCorrected(map(list(map(list(group.get("taskReceipts")).get(0)).get("requirements")).get(0)));assertEquals(raw,group.get("rawResponse"));
    }

    @Test void ambiguousRequirementStillStopsAndExplainsTheSpecificMismatch() {
        ObjectNode source=source();ObjectNode second=((ObjectNode)source.path("groups").path(0)).deepCopy();second.put("id","second-scope");source.withArray("groups").add(second);((ObjectNode)source.path("markerCandidates").path(0)).withArray("targetGroupIds").add("second-scope");DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));String owner=text(plan.tasks().get(0).get("taskId"));int[] calls={0};
        String raw=JsonUtils.write(obj("nodes",Collections.emptyList(),"requirements",Collections.singletonList(requirement(owner)),"taskOutcomes",Collections.singletonList(obj("sourceTaskId",owner,"status","identified"))));
        Map<String,Object> group=DraftAutomaticDiscovery.processGroup((system,user)->{calls[0]++;return raw;},plan,DraftAutomaticDiscovery.planGroups(plan).get(0),true);assertEquals(1,calls[0]);assertEquals("partial",group.get("status"));assertTrue(list(group.get("stopReasons")).contains("unsupported_requirement_quote_kind_or_node_reference"));Map<String,Object> child=map(list(group.get("taskReceipts")).get(0)),issue=list(child.get("issues")).stream().map(DraftReviewGraph::map).filter(item->"unsupported_requirement_quote_kind_or_node_reference".equals(item.get("reason"))).findFirst().orElseThrow();assertEquals("requirement_quote_ambiguous_in_authorized_evidence",map(issue.get("quoteDiagnostic")).get("reason"));assertTrue(list(child.get("requirements")).isEmpty());
    }

    private static Map<String,Object> resolve(ObjectNode source,Map<String,Object> task,String quote,String observation,List<Object> supplemental,Map<String,Object> diagnostic){return DraftReviewDiscovery.sourceRequirementQuote(source,task,quote,observation,supplemental,diagnostic);}
    private static Map<String,Object> task(ObjectNode source){return DraftReviewDiscovery.sourceTasks(Collections.singletonList(source)).stream().filter(item->"marker".equals(item.get("itemKind"))).findFirst().orElseThrow();}
    private static Map<String,Object> requirement(String owner){return obj("id","dvd","sourceTaskId",owner,"sourceQuote",DVD,"sourceObservationId","marker","kind","verification","nodeIds",Collections.emptyList(),"reason","Verify the DVD-ROM requirement within the current scope.");}
    private static void assertCorrected(Map<String,Object> requirement){assertEquals("pending",requirement.get("status"));Map<String,Object> evidence=map(requirement.get("sourceQuoteEvidence"));assertEquals("scope",evidence.get("sourceObservationId"));assertEquals("marker",evidence.get("modelClaimedObservationId"));assertEquals("unique_authorized_current_source_quote",evidence.get("normalizationReason"));assertEquals("pending",evidence.get("status"));assertEquals(false,evidence.get("editableAuthority"));}
    private static ObjectNode source(){
        ObjectNode source=JsonUtils.mapper().createObjectNode().put("id","1").put("sourceDocumentId",1).put("sourceSha256",SHA).put("format","docx").put("fileKey","NTT").put("fileName","NTT.docx").put("evidenceStatus","bound");for(String key:Arrays.asList("sourceMembers","paragraphs","guidanceNotes","markerCandidates","groups","conditions"))source.putArray(key);
        ObjectNode member=source.withArray("sourceMembers").addObject().put("id","body").put("identityStatus","source_bound");member.putObject("textObservation").put("text",FULL);ObjectNode position=member.putObject("sourcePosition").put("sourceSha256",SHA).put("partName","word/document.xml").put("nodePath","/body/p1");
        ObjectNode paragraph=source.withArray("paragraphs").addObject().put("id","p1").put("sourceSha256",SHA).put("paragraphSha256",SHA).put("text",FULL);paragraph.set("sourcePosition",position.deepCopy());
        ObjectNode marker=source.withArray("markerCandidates").addObject().put("id","marker").put("memberId","body").put("channel","text");marker.putObject("localSentenceObservation").put("text",FIRST).put("start",0).put("end",FIRST.length());marker.putArray("targetGroupIds").add("scope");
        ObjectNode scope=source.withArray("groups").addObject().put("id","scope").put("kind","complete_alternative_scope").put("text",FULL).put("scopeComplete",true);scope.putArray("memberRanges").addObject().put("memberId","body").put("channel","text").put("start",0).put("end",FULL.length());return source;
    }
}
