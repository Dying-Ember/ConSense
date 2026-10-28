package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

class DraftDiscoverySupplementalEvidenceTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a"));
    private static final String MAIN="Review project facts.",TARGET="Complete saved target.";

    @Test void explicitlyRequestedCurrentEvidenceSupportsQuotesOutsideTheInitialInstruction() {
        ObjectNode source=source();Map<String,Object> task=task(source);
        List<Object> supplied=context(source,obj("kind","member","id","separate"),obj("kind","guidance","id","extra-note"),obj("kind","condition","id","extra-condition"),obj("kind","scope","id","saved-scope"));
        for(String[] expected:Arrays.asList(new String[]{"member","separate","An independent project fact."},new String[]{"guidance","extra-note","Choose electronic or hard copy."},new String[]{"condition","extra-condition","When electronic issue is selected."},new String[]{"scope","saved-scope",TARGET})) {
            assertNull(DraftReviewDiscovery.sourceTaskQuote(source,task,expected[2],expected[1]));
            JsonNode quote=JsonUtils.mapper().valueToTree(DraftReviewDiscovery.sourceTaskQuote(source,task,expected[2],expected[1],supplied));
            assertEquals(expected[0],quote.path("kind").asText());assertEquals(expected[1],quote.path("sourceObservationId").asText());assertFalse(quote.path("editableAuthority").asBoolean());
            assertEquals("1",quote.path("sourceId").asText());assertEquals(SHA,quote.path("sourceSha256").asText());assertEquals("analysis-1",quote.path("analysisRevision").asText());
        }
        Map<String,Object> candidate=node("fact","An independent project fact.");candidate.put("sourceObservationId","separate");
        JsonNode result=converted(source,Collections.singletonList(candidate),supplied,new ArrayList<>()).path(0);
        assertEquals("pending",result.path("status").asText());assertEquals("member",result.path("semanticQuoteEvidence").path("kind").asText());
        JsonNode anchor=result.path("sourceAnchors").path(1);assertEquals(source.path("sourceMembers").path(1),anchor.path("sourceObservation"));assertEquals(0,anchor.path("observedRanges").path(0).path("start").asInt());
    }

    @Test void onlyAnExplicitScopeRequestAddsAnEffectScopeAndAdjacentTextDoesNot() {
        ObjectNode source=source();List<Object> memberOnly=context(source,obj("kind","member","id","condition-member"));
        assertTrue(JsonUtils.write(memberOnly).contains(TARGET));
        assertNull(DraftReviewDiscovery.sourceTaskQuote(source,task(source),TARGET,"target",memberOnly));
        JsonNode unavailable=converted(source,Collections.singletonList(withEffect(node("fact",MAIN))),memberOnly,new ArrayList<>()).path(0).path("editTargets").path(0);
        assertEquals("check",unavailable.path("action").asText());assertFalse(unavailable.path("scopeComplete").asBoolean());assertFalse(unavailable.has("sourceScopeObservation"));
        List<Object> scope=context(source,obj("kind","scope","id","saved-scope"));
        JsonNode candidate=converted(source,Collections.singletonList(withEffect(node("fact",MAIN))),scope,new ArrayList<>()).path(0),target=candidate.path("editTargets").path(0);
        assertEquals("pending",candidate.path("status").asText());assertEquals("delete",target.path("action").asText());assertTrue(target.path("scopeComplete").asBoolean());
        assertEquals(source.path("groups").path(0),target.path("sourceScopeObservation"));assertEquals("analysis-1",target.path("analysisRevision").asText());assertEquals("p-target",target.path("native").path("spans").path(0).path("paragraphId").asText());assertEquals(TARGET,target.path("native").path("spans").path(0).path("expectedText").asText());
    }

    @Test void suppliedTextCoordinatesAndCompletenessNeverReplaceTheCurrentObservation() {
        ObjectNode source=source();List<Object> supplied=context(source,obj("kind","scope","id","saved-scope"));Map<String,Object> receipt=map(supplied.get(0));
        receipt.put("text","Fabricated wording.");receipt.put("ranges",Collections.singletonList(obj("memberId","main","start",5,"end",99999)));receipt.put("sourcePosition",obj("nodePath","/invented"));receipt.put("editableAuthority",true);
        assertNull(DraftReviewDiscovery.sourceTaskQuote(source,task(source),"Fabricated wording.","saved-scope",supplied));
        JsonNode target=converted(source,Collections.singletonList(withEffect(node("fact",MAIN))),supplied,new ArrayList<>()).path(0).path("editTargets").path(0);
        assertTrue(target.path("scopeComplete").asBoolean());assertEquals(0,target.path("native").path("spans").path(0).path("start").asInt());assertEquals(TARGET.length(),target.path("native").path("spans").path(0).path("end").asInt());
        ((ObjectNode)source.path("groups").path(0)).put("scopeComplete",false);receipt.put("scopeComplete",true);
        target=converted(source,Collections.singletonList(withEffect(node("fact",MAIN))),supplied,new ArrayList<>()).path(0).path("editTargets").path(0);
        assertEquals("check",target.path("action").asText());assertFalse(target.path("scopeComplete").asBoolean());assertTrue(target.path("needsRebind").asBoolean());assertFalse(target.path("sourceScopeObservation").path("scopeComplete").asBoolean());
    }

    @Test void foreignMissingStaleAndNavigationReceiptsAreRejected() {
        ObjectNode source=source();Map<String,Object> task=task(source);Map<String,Object> receipt=map(context(source,obj("kind","guidance","id","extra-note")).get(0));
        for(String key:Arrays.asList("sourceId","sourceSha256","analysisRevision","analysisInputSha256","analysisSha256")) {
            Map<String,Object> wrong=new LinkedHashMap<>(receipt);wrong.put(key,"foreign-or-stale");assertNull(DraftReviewDiscovery.sourceTaskQuote(source,task,"Choose electronic", "extra-note",Collections.singletonList(wrong)),key);
            Map<String,Object> missing=new LinkedHashMap<>(receipt);missing.remove(key);assertNull(DraftReviewDiscovery.sourceTaskQuote(source,task,"Choose electronic","extra-note",Collections.singletonList(missing)),key);
        }
        Map<String,Object> preview=new LinkedHashMap<>(receipt);preview.put("previewOnly",true);assertNull(DraftReviewDiscovery.sourceTaskQuote(source,task,"Choose electronic","extra-note",Collections.singletonList(preview)));
        Map<String,Object> missingId=new LinkedHashMap<>(receipt);missingId.put("id","missing");assertNull(DraftReviewDiscovery.sourceTaskQuote(source,task,"Choose electronic","missing",Collections.singletonList(missingId)));
        ((ObjectNode)source.path("sourceMembers").path(2).path("sourcePosition")).put("sourceSha256","changed");
        assertNull(DraftReviewDiscovery.sourceTaskQuote(source,task,"Choose electronic","extra-note",Collections.singletonList(receipt)));
    }

    @Test void duplicateIdsAndAmbiguousQuotesRemainUnusable() {
        ObjectNode source=source();List<Object> supplied=context(source,obj("kind","guidance","id","extra-note"));
        source.withArray("guidanceNotes").add(source.path("guidanceNotes").path(1).deepCopy());
        assertNull(DraftReviewDiscovery.sourceTaskQuote(source,task(source),"Choose electronic","extra-note",supplied));
        source=source();member(source,"duplicate-wording","Choose electronic or hard copy.");observation(source,"guidanceNotes","another-note","duplicate-wording","Choose electronic or hard copy.");
        supplied=context(source,obj("kind","guidance","id","extra-note"),obj("kind","guidance","id","another-note"));
        assertNull(DraftReviewDiscovery.sourceTaskQuote(source,task(source),"Choose electronic","",supplied));
        assertNotNull(DraftReviewDiscovery.sourceTaskQuote(source,task(source),"Choose electronic","another-note",supplied));
        source.withArray("conditions").add(source.path("guidanceNotes").path(2).deepCopy());
        assertNull(DraftReviewDiscovery.sourceTaskQuote(source,task(source),"Choose electronic","another-note",supplied));
    }

    @Test void localListScopeAndSavedSourceScopeKeepSeparateNamespaces() {
        ObjectNode source=source();List<Object> supplied=context(source,obj("kind","scope","id","saved-scope"));
        Map<String,Object> group=node("saved-scope",MAIN);group.put("type","list");Map<String,Object> child=node("applies",MAIN);child.put("parentId","saved-scope");
        Map<String,Object> result=withEffect(node("result",MAIN));result.put("branches",Collections.singletonList(obj("id","case","mode","all","scopeId","saved-scope","scopeMode","each","resultValue","true","clauses",Collections.singletonList(obj("inputId","applies","operator","yes")))));
        map(list(result.get("effects")).get(0)).put("branchId","case");List<Object> rejected=new ArrayList<>();
        JsonNode nodes=converted(source,Arrays.asList(group,child,result),supplied,rejected);assertTrue(rejected.isEmpty(),JsonUtils.write(rejected));assertEquals(3,nodes.size());
        assertEquals(nodes.path(0).path("id").asText(),nodes.path(2).path("correction").path("branches").path(0).path("scopeId").asText());assertEquals("saved-scope",nodes.path(2).path("editTargets").path(0).path("sourceScopeId").asText());
        result.put("sourceQuote","An independent project fact.");result.put("sourceObservationId","separate");supplied.addAll(context(source,obj("kind","member","id","separate")));
        rejected=new ArrayList<>();nodes=converted(source,Collections.singletonList(result),supplied,rejected);
        assertEquals(1,nodes.size());assertTrue(nodes.path(0).path("discoveryCoreRetained").asBoolean());assertEquals("member",nodes.path(0).path("semanticQuoteEvidence").path("kind").asText());assertTrue(nodes.path(0).path("editTargets").isEmpty());assertFalse(rejected.isEmpty());
    }

    private static ObjectNode source() {
        ObjectNode source=JsonUtils.mapper().createObjectNode().put("id","1").put("sourceDocumentId",1).put("sourceSha256",SHA).put("format","docx").put("fileKey","SCC").put("editionId","unknown-edition").put("evidenceStatus","bound").put("analysisRevision","analysis-1").put("analysisInputSha256",SHA).put("analysisSha256",SHA);
        for(String key:Arrays.asList("sourceMembers","paragraphs","guidanceNotes","markerCandidates","groups","conditions"))source.putArray(key);
        member(source,"main",MAIN);member(source,"separate","An independent project fact.");member(source,"guidance-member","Choose electronic or hard copy.");member(source,"condition-member","When electronic issue is selected.");member(source,"target",TARGET);
        observation(source,"guidanceNotes","main-note","main",MAIN);observation(source,"guidanceNotes","extra-note","guidance-member","Choose electronic or hard copy.");observation(source,"conditions","extra-condition","condition-member","When electronic issue is selected.");
        observation(source,"groups","saved-scope","target",TARGET).put("scopeComplete",true);return source;
    }
    private static void member(ObjectNode source,String id,String text) {
        ObjectNode member=source.withArray("sourceMembers").addObject().put("id",id).put("identityStatus","source_bound");member.putObject("sourcePosition").put("sourceSha256",SHA).put("partName","word/document.xml").put("nodePath","/"+id);member.putObject("textObservation").put("text",text);
        ObjectNode paragraph=source.withArray("paragraphs").addObject().put("id","p-"+id).put("text",text).put("sourceSha256",SHA).put("paragraphSha256",SHA);paragraph.set("sourcePosition",member.path("sourcePosition").deepCopy());
    }
    private static ObjectNode observation(ObjectNode source,String collection,String id,String member,String text) {ObjectNode observation=source.withArray(collection).addObject().put("id",id).put("text",text);observation.putArray("memberRanges").addObject().put("memberId",member).put("channel","text").put("start",0).put("end",text.length());return observation;}
    private static Map<String,Object> task(ObjectNode source) {return DraftReviewDiscovery.sourceTasks(Collections.singletonList(source)).stream().filter(task->"main-note".equals(task.get("sourceObservationId"))).findFirst().orElseThrow();}
    private static Map<String,Object> node(String id,String quote) {return obj("id",id,"type","boolean","role","input","nameEn","Question","sourceQuote",quote,"branches",new ArrayList<>(),"effects",new ArrayList<>(),"options",new ArrayList<>());}
    private static Map<String,Object> withEffect(Map<String,Object> node) {node.put("effects",Collections.singletonList(obj("scopeId","saved-scope","quote",TARGET,"action","delete")));return node;}
    private static List<Object> context(ObjectNode source,Object...requests) {return new ArrayList<>(list(DraftDiscoveryEvidence.context(source,JsonUtils.mapper().valueToTree(Arrays.asList(requests))).get("items")));}
    private static JsonNode converted(ObjectNode source,List<Map<String,Object>> nodes,List<Object> supplied,List<Object> rejected) {return JsonUtils.mapper().valueToTree(DraftReviewDiscovery.sourceTaskNodes(source,task(source),JsonUtils.mapper().valueToTree(obj("nodes",nodes)),rejected,supplied));}
}
