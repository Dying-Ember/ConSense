package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Synthetic current-source observations; no parser, gateway, or product API calls. */
class DraftDiscoveryScopeReferenceTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a"));
    private static final String MAIN="Review the project alternative.",TARGET="Complete saved target.";

    @Test void aNamedScopeRestoresTheSameExactTargetAsALegacyFullQuote() {
        ObjectNode source=source();Map<String,Object> compact=effect();
        JsonNode restored=target(source,compact,Collections.emptyList());
        compact.put("quote",TARGET);JsonNode legacy=target(source,compact,Collections.emptyList());
        assertEquals(legacy,restored);assertEquals(TARGET,restored.path("text").asText());
        assertEquals("delete",restored.path("action").asText());assertTrue(restored.path("scopeComplete").asBoolean());
        assertEquals(TARGET,restored.path("native").path("spans").path(0).path("expectedText").asText());
        assertEquals("pending",converted(source,effect(),Collections.emptyList()).path("status").asText());
        assertEquals("missing",restored.path("nativeBinding").path("status").asText(),"Restoration does not grant original-byte binding or human confirmation");
    }

    @Test void explicitWrongEmptyNullOrNonTextQuotesAreNeverReplacedWithTheCorrectScope() {
        for(Object quote:Arrays.asList("Wrong source wording.","",null,42)) {
            Map<String,Object> effect=effect();effect.put("quote",quote);JsonNode retained=target(source(),effect,Collections.emptyList());
            assertEquals("check",retained.path("action").asText());assertFalse(retained.path("scopeComplete").asBoolean());
            assertEquals("EXPLICIT_SCOPE_QUOTE_MISMATCH",retained.path("scopeQuoteIssue").asText());assertTrue(retained.path("needsRebind").asBoolean());
            assertNotEquals(TARGET,retained.path("text").asText());
            if(!(quote instanceof String))assertEquals(JsonUtils.mapper().valueToTree(quote),retained.path("modelQuote"));
        }
    }

    @Test void incompleteMissingUnrelatedOrDuplicateScopesCannotGainEditAuthority() {
        for(String defect:Arrays.asList("incomplete","missing","unrelated","duplicate","pdf","changed-native")) {
            ObjectNode source=source();ObjectNode scope=(ObjectNode)source.path("groups").path(0);
            switch(defect) {
                case "incomplete":scope.put("scopeComplete",false);break;
                case "missing":source.putArray("groups");break;
                case "unrelated":scope.remove("guidanceNoteId");break;
                case "duplicate":source.withArray("groups").add(scope.deepCopy());break;
                case "pdf":source.put("format","pdf");break;
                case "changed-native":((ObjectNode)source.path("paragraphs").path(1)).put("text","Changed actual paragraph.");break;
            }
            JsonNode retained=target(source,effect(),Collections.emptyList());
            assertEquals("check",retained.path("action").asText(),defect);assertFalse(retained.path("scopeComplete").asBoolean(),defect);assertTrue(retained.path("needsRebind").asBoolean(),defect);
        }
    }

    @Test void characterRefinementStillRequiresAUniqueExactSourceSubrangeAndHumanReview() {
        for(String refinement:Arrays.asList("saved","fabricated")) {
            Map<String,Object> effect=effect();effect.put("refinedQuote",refinement);JsonNode retained=target(source(),effect,Collections.emptyList());
            assertEquals(TARGET,retained.path("text").asText());assertEquals("check",retained.path("action").asText());assertTrue(retained.path("observedScopeComplete").asBoolean());
            if("saved".equals(refinement)) {assertEquals("saved",retained.path("refinementCandidate").path("text").asText());assertFalse(retained.path("refinementCandidate").path("editableAuthority").asBoolean());}
            else {assertFalse(retained.has("refinementCandidate"));assertEquals("UNIQUE_SOURCE_BOUND_SUBRANGE_REQUIRED",retained.path("refinementIssue").asText());}
        }
        ObjectNode source=source();String repeated="saved saved";((ObjectNode)source.path("sourceMembers").path(1).path("textObservation")).put("text",repeated);((ObjectNode)source.path("paragraphs").path(1)).put("text",repeated);((ObjectNode)source.path("groups").path(0).path("memberRanges").path(0)).put("end",repeated.length());
        Map<String,Object> effect=effect();effect.put("refinedQuote","saved");assertFalse(target(source,effect,Collections.emptyList()).has("refinementCandidate"));
    }

    @Test void requestedScopeIdsResolveFromTheCurrentReadingRatherThanCopiedReceiptText() {
        ObjectNode source=source();((ObjectNode)source.path("groups").path(0)).remove("guidanceNoteId");
        assertEquals("check",target(source,effect(),Collections.emptyList()).path("action").asText());
        List<Object> supplied=new ArrayList<>(list(DraftDiscoveryEvidence.context(source,JsonUtils.mapper().valueToTree(Collections.singletonList(obj("kind","scope","id","scope")))).get("items")));
        map(supplied.get(0)).put("text","Fabricated receipt copy.");map(supplied.get(0)).put("scopeComplete",true);
        JsonNode retained=target(source,effect(),supplied);assertEquals("delete",retained.path("action").asText());assertEquals(TARGET,retained.path("text").asText());
        ((ObjectNode)source.path("groups").path(0)).put("scopeComplete",false);retained=target(source,effect(),supplied);assertEquals("check",retained.path("action").asText());assertFalse(retained.path("sourceScopeObservation").path("scopeComplete").asBoolean());
    }

    @Test void aNamedInsertionPointRestoresAnEmptyQuoteWithoutTreatingExplicitNullAsOmission() {
        ObjectNode source=source();ObjectNode scope=(ObjectNode)source.path("groups").path(0);scope.remove("memberRanges");scope.put("scopeComplete",false).put("sourceInsertionAnchorComplete",true);
        scope.putArray("insertionAnchors").addObject().put("memberId","target").put("channel","text").put("start",TARGET.length()).put("end",TARGET.length()).put("offsetUnit","utf16");
        Map<String,Object> effect=effect();effect.put("action","insert");JsonNode retained=target(source,effect,Collections.emptyList());
        assertEquals("",retained.path("text").asText());assertEquals("insert",retained.path("action").asText());assertTrue(retained.path("scopeComplete").asBoolean());assertEquals("insert",retained.path("native").path("spans").path(0).path("operation").asText());
        effect.put("quote",null);retained=target(source,effect,Collections.emptyList());assertEquals("check",retained.path("action").asText());assertEquals("EXPLICIT_SCOPE_QUOTE_MISMATCH",retained.path("scopeQuoteIssue").asText());
    }

    @Test void exactEchoAndOmittedQuoteMergeOnceInEitherOrderWithoutMutatingRawResponses() {
        ObjectNode source=source();Map<String,Object> legacy=effect();legacy.put("quote",TARGET);
        JsonNode raw=response(legacy),compact=response(effect());String original=JsonUtils.write(raw);
        JsonNode normalized=DraftReviewDiscovery.compactScopeQuotes(source,task(source),raw,Collections.emptyList());
        assertFalse(normalized.path("nodes").path(0).path("effects").path(0).has("quote"));assertEquals(original,JsonUtils.write(raw));
        for(boolean legacyFirst:Arrays.asList(true,false)) {
            List<Object> issues=new ArrayList<>();JsonNode merged=DraftDiscoveryEvidence.mergeResponse(legacyFirst?normalized:compact,legacyFirst?compact:normalized,issues);
            assertEquals(1,merged.path("nodes").path(0).path("effects").size());assertTrue(issues.isEmpty());
            List<Map<String,Object>> nodes=DraftReviewDiscovery.sourceTaskNodes(source,task(source),merged,new ArrayList<>());
            assertEquals(1,list(nodes.get(0).get("editTargets")).size());assertEquals(TARGET,map(list(nodes.get(0).get("editTargets")).get(0)).get("text"));
        }
        for(Object quote:Arrays.asList("Wrong source wording.","",null,42)) {Map<String,Object> invalid=effect();invalid.put("quote",quote);JsonNode bad=response(invalid);assertEquals(bad,DraftReviewDiscovery.compactScopeQuotes(source,task(source),bad,Collections.emptyList()));}
        ((ObjectNode)source.path("groups").path(0)).remove("guidanceNoteId");assertEquals(raw,DraftReviewDiscovery.compactScopeQuotes(source,task(source),raw,Collections.emptyList()),"An unrelated unrequested ID cannot normalize a quote");
    }

    @Test void anIsolatedQuestionRetainsSmallErrorReferencesWithoutCopyingModelWording() {
        ObjectNode source=source();String wrong=String.join("",Collections.nCopies(20,"Wrong model quote. "));Map<String,Object> effect=effect();effect.put("quote",wrong);effect.put("valueSourceId","foreign-node");JsonNode raw=response(effect);String original=JsonUtils.write(raw);List<Object> rejected=new ArrayList<>();
        List<Map<String,Object>> converted=DraftReviewDiscovery.sourceTaskNodes(source,task(source),raw,rejected);assertEquals(1,converted.size());JsonNode node=JsonUtils.mapper().valueToTree(converted.get(0));
        assertTrue(node.path("discoveryCoreRetained").asBoolean());assertTrue(node.path("editTargets").isEmpty());assertFalse(rejected.isEmpty());
        JsonNode retained=node.path("discoveryRejectedParts").path(0);assertEquals("fact",retained.path("modelLocalId").asText());assertEquals(0,retained.path("effectIndex").asInt());assertEquals("scope",retained.path("sourceScopeId").asText());assertEquals("EXPLICIT_SCOPE_QUOTE_MISMATCH",retained.path("reason").asText());assertFalse(JsonUtils.write(node.path("discoveryRejectedParts")).contains(wrong));assertEquals(original,JsonUtils.write(raw));
    }

    private static ObjectNode source() {
        ObjectNode source=JsonUtils.mapper().createObjectNode().put("id","1").put("sourceDocumentId",1).put("sourceSha256",SHA).put("format","docx").put("fileKey","SCT").put("editionId","test-edition").put("evidenceStatus","bound").put("analysisRevision","test-analysis").put("analysisInputSha256",SHA).put("analysisSha256",SHA);
        for(String key:Arrays.asList("sourceMembers","paragraphs","guidanceNotes","markerCandidates","groups","conditions"))source.putArray(key);
        member(source,"main",MAIN);member(source,"target",TARGET);
        source.withArray("guidanceNotes").addObject().put("id","note").put("text",MAIN).put("captureComplete",true);
        source.withArray("groups").addObject().put("id","scope").put("guidanceNoteId","note").put("scopeComplete",true).putArray("memberRanges").addObject().put("memberId","target").put("channel","text").put("start",0).put("end",TARGET.length());
        return source;
    }
    private static void member(ObjectNode source,String id,String text) {
        ObjectNode member=source.withArray("sourceMembers").addObject().put("id",id);member.putObject("sourcePosition").put("sourceSha256",SHA).put("partName","word/document.xml").put("nodePath","/"+id);member.putObject("textObservation").put("text",text);
        ObjectNode paragraph=source.withArray("paragraphs").addObject().put("id","p-"+id).put("text",text).put("sourceSha256",SHA).put("paragraphSha256",SHA);paragraph.set("sourcePosition",member.path("sourcePosition").deepCopy());
    }
    private static Map<String,Object> effect(){return obj("scopeId","scope","action","delete");}
    private static Map<String,Object> task(ObjectNode source){return DraftReviewDiscovery.sourceTasks(Collections.singletonList(source)).stream().filter(item->"note".equals(item.get("sourceObservationId"))).findFirst().orElseThrow();}
    private static JsonNode response(Map<String,Object> effect){return JsonUtils.mapper().valueToTree(obj("nodes",Collections.singletonList(obj("id","fact","type","boolean","role","input","nameEn","Project alternative","sourceQuote",MAIN,"branches",new ArrayList<>(),"effects",Collections.singletonList(effect),"options",new ArrayList<>()))));}
    private static JsonNode target(ObjectNode source,Map<String,Object> effect,List<Object> supplied){return converted(source,effect,supplied).path("editTargets").path(0);}
    private static JsonNode converted(ObjectNode source,Map<String,Object> effect,List<Object> supplied) {
        List<Object> rejected=new ArrayList<>();List<Map<String,Object>> nodes=DraftReviewDiscovery.sourceTaskNodes(source,task(source),response(effect),rejected,supplied);
        assertTrue(rejected.isEmpty(),JsonUtils.write(rejected));assertEquals(1,nodes.size());return JsonUtils.mapper().valueToTree(nodes.get(0));
    }
}
