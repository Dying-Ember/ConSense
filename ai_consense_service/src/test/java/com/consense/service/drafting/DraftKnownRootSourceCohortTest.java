package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.consense.service.drafting.DraftReviewGraph.*;
import static org.junit.jupiter.api.Assertions.*;

class DraftKnownRootSourceCohortTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a")),OTHER_SHA=String.join("",Collections.nCopies(64,"b"));

    @Test void anAddedTemplateDoesNotRequireAnOldRootToAlreadyContainItsNewProofs() {
        ObjectNode ntt=ntt(),sct=sct();Map<String,Object> graph=seed(ntt),root=field(graph,"twoEnvelopeTendering");addSource(graph,sct);
        String id=text(root.get("id")),proofs=JsonUtils.write(map(root.get("currentDefinitionReference")).get("sourceReferences")),anchors=JsonUtils.write(root.get("sourceAnchors"));
        Map<String,Object> target=obj("docId","1","text","Unknown current passage","action","check","needsRebind",true,"scopeComplete",false);root.put("editTargets",Collections.singletonList(target));
        Map<String,Object> empty=copy(graph);state(empty).put("variables",new ArrayList<>());Map<String,Object> expanded=find(build(empty,ntt,sct).roots(),id);
        assertTrue(list(map(expanded.get("currentDefinitionReference")).get("sourceReferences")).size()>list(map(root.get("currentDefinitionReference")).get("sourceReferences")).size(),"SCT must actually add a proof of the shared root");
        String before=JsonUtils.write(graph);Map<String,Object> result=DraftKnownRootReview.confirmGroup(graph,Arrays.asList(ntt,sct),Collections.singletonList(id),"Reviewed this NTT definition.");Map<String,Object> confirmed=field(result,"twoEnvelopeTendering");
        assertEquals("confirmed",confirmed.get("status"));assertEquals(proofs,JsonUtils.write(map(confirmed.get("currentDefinitionReference")).get("sourceReferences")));assertEquals(anchors,JsonUtils.write(confirmed.get("sourceAnchors")));
        assertEquals(Collections.singletonList("1"),confirmed.get("docIds"));assertEquals(JsonUtils.write(target),JsonUtils.write(list(confirmed.get("editTargets")).get(0)));assertEquals(before,JsonUtils.write(graph));
    }

    @Test void aReviewedPrerequisiteKeepsItsOwnSourceProofsWhenAnotherTemplateIsAdded() {
        ObjectNode ntt=ntt(),sct=sct();Map<String,Object> graph=seed(ntt);String procedure=text(field(graph,"twoEnvelopeTendering").get("id")),other=text(field(graph,"otherTenderingArrangement").get("id"));
        Map<String,Object> first=DraftKnownRootReview.confirmGroup(graph,Collections.singletonList(ntt),Collections.singletonList(procedure),"Reviewed the procedure definition.");addSource(first,sct);
        Map<String,Object> result=DraftKnownRootReview.confirmGroup(first,Arrays.asList(ntt,sct),Collections.singletonList(other),"Reviewed the conditional wording definition.");
        assertEquals("confirmed",field(result,"twoEnvelopeTendering").get("status"));assertEquals("confirmed",field(result,"otherTenderingArrangement").get("status"));assertEquals(true,map(field(result,"otherTenderingArrangement").get("applicability")).get("confirmed"));
        assertFalse(text(map(field(result,"otherTenderingArrangement").get("applicability")).get("bindingFingerprint")).isEmpty());
    }

    @Test void anotherCurrentProofCannotRescueChangedOriginalEvidenceOrAnalysis() {
        for(String change:Arrays.asList("sha","analysis","member","observation")) {
            ObjectNode original=ntt(),current=original.deepCopy(),sct=sct();Map<String,Object> graph=seed(original);addSource(graph,sct);String id=text(field(graph,"twoEnvelopeTendering").get("id"));
            if("sha".equals(change))current.put("sourceSha256",OTHER_SHA);
            if("analysis".equals(change))current.put("analysisRevision","new-analysis");
            if("member".equals(change))((ObjectNode)current.path("sourceMembers").path(0).path("sourcePosition")).put("sourceSha256",OTHER_SHA);
            if("observation".equals(change)){((ObjectNode)current.path("guidanceNotes").path(0)).put("id","replaced-note");((ObjectNode)current.path("groups").path(0)).putArray("guidanceNoteIds").add("replaced-note");}
            assertThrows(RuntimeException.class,()->DraftKnownRootReview.confirmGroup(graph,Arrays.asList(current,sct),Collections.singletonList(id),"Review"),change);
        }
    }

    @Test void changedStoredProofsCoreOrSelectedEditionStillCannotUseGroupConfirmation() {
        for(String change:Arrays.asList("source-reference","anchor-analysis","missing-anchor","core","selected-edition")) {
            ObjectNode ntt=ntt(),sct=sct();Map<String,Object> graph=seed(ntt);addSource(graph,sct);Map<String,Object> node=field(graph,"twoEnvelopeTendering");String id=text(node.get("id"));
            if("source-reference".equals(change))map(list(map(node.get("currentDefinitionReference")).get("sourceReferences")).get(0)).put("sourceId","2");
            if("anchor-analysis".equals(change))map(list(node.get("sourceAnchors")).get(0)).put("analysisSha256",OTHER_SHA);
            if("missing-anchor".equals(change))node.put("sourceAnchors",Collections.emptyList());
            if("core".equals(change))node.put("questionEn","An unrelated business fact");
            if("selected-edition".equals(change))map(graph.get("selectedDocuments")).put("NTT","different-edition");
            assertThrows(RuntimeException.class,()->DraftKnownRootReview.confirmGroup(graph,Arrays.asList(ntt,sct),Collections.singletonList(id),"Review"),change);
        }
    }

    private static Map<String,Object> seed(ObjectNode reading){Map<String,Object> graph=obj("kind","consense-template-graph-v1","sources",new ArrayList<>(),"selectedDocuments",new LinkedHashMap<>(),"graph",obj("variables",new ArrayList<>(),"events",new ArrayList<>()));addSource(graph,reading);state(graph).put("variables",build(graph,reading).roots());assertNotNull(field(graph,"twoEnvelopeTendering"));return graph;}
    private static void addSource(Map<String,Object> graph,ObjectNode reading){String id=reading.path("id").asText(),family=reading.path("fileKey").asText();DraftBusinessRules.list(graph.get("sources")).add(obj("id",id,"sourceDocumentId",Long.parseLong(id),"fileKey",family,"sourceSha256",reading.path("sourceSha256").asText(),"evidenceStatus","bound","format","docx"));map(graph.get("selectedDocuments")).put(family,id);}
    private static DraftKnownRoots.Result build(Map<String,Object> graph,ObjectNode... readings){Map<String,JsonNode> sources=new LinkedHashMap<>();for(ObjectNode reading:readings)sources.put(reading.path("id").asText(),reading);return DraftKnownRoots.build(sources,DraftReviewDiscovery.sourceTasks(new ArrayList<>(sources.values())),DraftDiscoveryGroups.currentTemplateIndexes(sources),graph);}
    private static Map<String,Object> field(Map<String,Object> graph,String key){for(Map<String,Object> node:nodes(graph))if(key.equals(map(node.get("catalogAlignment")).get("baselineKey")))return node;throw new AssertionError(key);}
    private static Map<String,Object> find(List<Map<String,Object>> nodes,String id){for(Map<String,Object> node:nodes)if(id.equals(node.get("id")))return node;throw new AssertionError(id);}
    private static Map<String,Object> copy(Map<String,Object> value){return JsonUtils.readMap(JsonUtils.write(value));}
    private static ObjectNode ntt(){return source("1","NTT",SHA,DraftBusinessRules.standardParagraph("NTT",172)+"\n"+DraftBusinessRules.standardParagraph("NTT",173),DraftBusinessRules.standardParagraph("NTT",166));}
    private static ObjectNode sct(){return source("2","SCT",OTHER_SHA,DraftBusinessRules.standardParagraph("SCT",848),DraftBusinessRules.standardParagraph("SCT",846));}
    private static ObjectNode source(String id,String family,String sha,String note,String body){
        assertFalse(note.isEmpty());assertFalse(body.isEmpty());ObjectNode reading=JsonUtils.mapper().createObjectNode().put("id",id).put("sourceDocumentId",Long.parseLong(id)).put("fileKey",family).put("sourceSha256",sha).put("evidenceStatus","bound").put("format","docx").put("analysisRevision","analysis-"+id).put("analysisInputSha256",sha).put("analysisSha256",sha);
        ObjectNode guidance=reading.putArray("guidanceNotes").addObject().put("id","note").put("text",note).put("captureComplete",true);guidance.putArray("orderedMemberIds").add("note-member");guidance.putArray("memberRanges").addObject().put("memberId","note-member").put("channel","text").put("start",0).put("end",note.length());
        ObjectNode scope=reading.putArray("groups").addObject().put("id","scope").put("text",body).put("scopeComplete",true);scope.putArray("guidanceNoteIds").add("note");scope.putArray("memberRanges").addObject().put("memberId","body-member").put("channel","text").put("start",0).put("end",body.length());reading.putArray("sourceMembers");member(reading,"note-member",note,sha);member(reading,"body-member",body,sha);return reading;
    }
    private static void member(ObjectNode reading,String id,String text,String sha){ObjectNode member=reading.withArray("sourceMembers").addObject().put("id",id);member.putObject("textObservation").put("text",text);member.putObject("sourcePosition").put("sourceSha256",sha).put("partName","word/document.xml").put("nodePath","/current/"+id);}
}
