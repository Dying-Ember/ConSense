package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.consense.service.drafting.DraftReviewGraph.*;
import static org.junit.jupiter.api.Assertions.*;

class DraftKnownRootReviewTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a"));

    @Test void currentCandidatesCanBeConfirmedTogetherWithoutAFullFixedPackage(){
        ObjectNode reading=source(DraftBusinessRules.standardParagraph("NTT",244),DraftBusinessRules.standardParagraph("NTT",243));Map<String,Object> graph=seed(reading),node=nodes(graph).get(0);String before=JsonUtils.write(graph);
        assertTrue(DraftKnownRootReview.candidate(graph,node));assertFalse(DraftFixedBaseline.installed(graph));
        Map<String,Object> confirmed=DraftKnownRootReview.confirmGroup(graph,Collections.singletonList(reading),Collections.singletonList(text(node.get("id"))),"Reviewed this definition and current source.");Map<String,Object> result=nodes(confirmed).get(0);
        assertEquals("confirmed",result.get("status"));assertEquals(true,map(result.get("catalogAlignment")).get("confirmed"));assertEquals(true,map(result.get("definitionReview")).get("confirmed"));assertEquals(before,JsonUtils.write(graph));assertFalse(state(confirmed).containsKey("fixedRuleBaseline"));assertFalse(DraftKnownRootReview.candidate(confirmed,result));
    }
    @Test void changedCoreAndMissingCurrentEvidenceCannotUseTheGroupShortcut(){
        ObjectNode reading=source(DraftBusinessRules.standardParagraph("NTT",244),DraftBusinessRules.standardParagraph("NTT",243));Map<String,Object> graph=seed(reading),node=nodes(graph).get(0);String id=text(node.get("id"));
        node.put("questionEn","A different project fact");assertThrows(RuntimeException.class,()->DraftKnownRootReview.confirmGroup(graph,Collections.singletonList(reading),Collections.singletonList(id),"Review"));
        Map<String,Object> missing=seed(reading);nodes(missing).get(0).put("sourceAnchors",Collections.emptyList());assertThrows(RuntimeException.class,()->DraftKnownRootReview.confirmGroup(missing,Collections.singletonList(reading),Collections.singletonList(id),"Review"));
        Map<String,Object> existing=seed(reading);map(nodes(existing).get(0).get("currentDefinitionReference")).put("existing",true);assertFalse(DraftKnownRootReview.candidate(existing,nodes(existing).get(0)));
    }
    @Test void explicitPrerequisiteMustBeSelectedOrAlreadyReviewed(){
        ObjectNode reading=source(DraftBusinessRules.standardParagraph("NTT",172)+"\n"+DraftBusinessRules.standardParagraph("NTT",173),DraftBusinessRules.standardParagraph("NTT",166));Map<String,Object> graph=seed(reading);String procedure=id(graph,"twoEnvelopeTendering"),other=id(graph,"otherTenderingArrangement");
        assertThrows(RuntimeException.class,()->DraftKnownRootReview.confirmGroup(graph,Collections.singletonList(reading),Collections.singletonList(other),"Review procedure first."));
        Map<String,Object> first=DraftKnownRootReview.confirmGroup(graph,Collections.singletonList(reading),Collections.singletonList(procedure),"Reviewed the procedure definition.");Map<String,Object> second=DraftKnownRootReview.confirmGroup(first,Collections.singletonList(reading),Collections.singletonList(other),"Reviewed the conditional alternative procedure.");
        Map<String,Object> condition=map(find(second,other).get("applicability"));assertEquals(true,condition.get("confirmed"));assertFalse(text(condition.get("bindingFingerprint")).isEmpty());Map<String,Map<String,Object>> byId=new LinkedHashMap<>();for(Map<String,Object> node:nodes(second))byId.put(text(node.get("id")),node);assertTrue(DraftReviewGraph.applicabilityBindingValid(second,find(second,other),byId));
    }
    @Test void confirmingDefinitionsDoesNotAuthorizeUnknownAmendmentRanges(){
        ObjectNode reading=source(DraftBusinessRules.standardParagraph("NTT",244),DraftBusinessRules.standardParagraph("NTT",243));Map<String,Object> graph=seed(reading),node=nodes(graph).get(0),target=obj("id","unknown-target","docId","1","text","Unresolved target wording","action","check","proposedAction","delete","needsRebind",true,"scopeComplete",false,"sourceScopeId","scope");node.put("editTargets",Collections.singletonList(target));String before=JsonUtils.write(target);
        Map<String,Object> confirmed=DraftKnownRootReview.confirmGroup(graph,Collections.singletonList(reading),Collections.singletonList(text(node.get("id"))),"Reviewed definition only.");assertEquals(before,JsonUtils.write(list(nodes(confirmed).get(0).get("editTargets")).get(0)));assertEquals("unknown",map(nodes(confirmed).get(0).get("currentDefinitionReference")).get("conditionStatus"));
    }
    private static Map<String,Object> seed(ObjectNode reading){Map<String,Object> graph=obj("kind","consense-template-graph-v1","sources",Collections.singletonList(obj("id","1","sourceDocumentId",1L,"fileKey","NTT","sourceSha256",SHA,"evidenceStatus","bound","format","docx")),"selectedDocuments",obj("NTT","1"),"graph",obj("variables",new ArrayList<>(),"events",new ArrayList<>()));Map<String,JsonNode> readings=Collections.singletonMap("1",reading);DraftKnownRoots.Result result=DraftKnownRoots.build(readings,DraftReviewDiscovery.sourceTasks(Collections.singletonList(reading)),DraftDiscoveryGroups.currentTemplateIndexes(readings),graph);state(graph).put("variables",result.roots());assertFalse(nodes(graph).isEmpty());return graph;}
    private static String id(Map<String,Object> graph,String key){for(Map<String,Object> node:nodes(graph))if(key.equals(map(node.get("catalogAlignment")).get("baselineKey")))return text(node.get("id"));throw new AssertionError(key);}
    private static Map<String,Object> find(Map<String,Object> graph,String id){for(Map<String,Object> node:nodes(graph))if(id.equals(node.get("id")))return node;throw new AssertionError(id);}
    private static ObjectNode source(String note,String body){ObjectNode reading=JsonUtils.mapper().createObjectNode().put("id","1").put("sourceDocumentId",1L).put("fileKey","NTT").put("sourceSha256",SHA).put("evidenceStatus","bound").put("format","docx").put("analysisRevision","test-analysis").put("analysisInputSha256",SHA).put("analysisSha256",SHA);ObjectNode guidance=reading.putArray("guidanceNotes").addObject().put("id","note").put("text",note).put("captureComplete",true);guidance.putArray("orderedMemberIds").add("note-member");guidance.putArray("memberRanges").addObject().put("memberId","note-member").put("channel","text").put("start",0).put("end",note.length());ObjectNode scope=reading.putArray("groups").addObject().put("id","scope").put("text",body).put("scopeComplete",true);scope.putArray("guidanceNoteIds").add("note");scope.putArray("memberRanges").addObject().put("memberId","body-member").put("channel","text").put("start",0).put("end",body.length());reading.putArray("sourceMembers");member(reading,"note-member",note);member(reading,"body-member",body);return reading;}
    private static void member(ObjectNode reading,String id,String text){ObjectNode member=reading.withArray("sourceMembers").addObject().put("id",id);member.putObject("textObservation").put("text",text);member.putObject("sourcePosition").put("sourceSha256",SHA).put("partName","word/document.xml").put("nodePath","/current/"+id);}
}
