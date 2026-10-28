package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

class DraftDiscoveryAssociatedQuoteTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a"));
    @Test void observationReferenceSurvivesBrowserJsonKeyOrderAndNumericSpelling() {
        Map<String,Object> javaValue=JsonUtils.readMap("{\"10\":{\"2\":1.0,\"1\":1.5},\"2\":-0.0,\"array\":[0.25,1.0,42]}");
        Map<String,Object> browserValue=JsonUtils.readMap("{\"2\":0,\"10\":{\"1\":1.5,\"2\":1},\"array\":[0.25,1,42]}");
        String ref=DraftScopeObservationIntegrity.reference(javaValue);assertNotNull(ref);assertTrue(ref.startsWith("v2:"));assertEquals(ref,DraftScopeObservationIntegrity.reference(browserValue));
        Map<String,Object> graph=obj("graph",obj("requirements",Collections.singletonList(obj("sourceQuoteEvidence",obj("sourceScopeObservationRef",ref))),"sourceScopeObservations",obj(ref,browserValue)));
        assertEquals("valid",DraftScopeObservationIntegrity.inspect(JsonUtils.readMap(JsonUtils.write(graph))).get(ref));
        map(map(state(graph).get("sourceScopeObservations")).get(ref)).put("2",1);assertEquals("invalid",DraftScopeObservationIntegrity.inspect(graph).get(ref));
        assertNull(DraftScopeObservationIntegrity.reference(obj("integer",9007199254740992L)));
        assertNull(DraftScopeObservationIntegrity.reference(obj("decimal",new java.math.BigDecimal("0.10000000000000000001"))));
        String legacy=DraftAdoption.hash(JsonUtils.write(javaValue));map(state(graph).get("sourceScopeObservations")).put(legacy,javaValue);map(map(list(state(graph).get("requirements")).get(0)).get("sourceQuoteEvidence")).put("sourceScopeObservationRef",legacy);assertEquals("valid",DraftScopeObservationIntegrity.inspect(graph).get(legacy));
    }
    @Test void observationReferenceSurvivesJavaJavascriptAndSavedJavaJson() throws Exception {
        Map<String,Object> javaValue=JsonUtils.readMap("{\"10\":{\"2\":1.0,\"1\":1.5},\"2\":-0.0,\"array\":[0.25,1.0,42]}");
        Process node;try{node=new ProcessBuilder("node","-e","process.stdout.write(JSON.stringify(JSON.parse(require('fs').readFileSync(0,'utf8'))))").redirectErrorStream(true).start();}
        catch(java.io.IOException unavailable){Assumptions.assumeTrue(false,"Node is unavailable for the JavaScript round trip");return;}
        try(java.io.OutputStream input=node.getOutputStream()){input.write(JsonUtils.write(javaValue).getBytes(StandardCharsets.UTF_8));}
        String browserJson=new String(node.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertEquals(0,node.waitFor(),browserJson);
        Map<String,Object> browserValue=JsonUtils.readMap(browserJson),savedValue=JsonUtils.readMap(JsonUtils.write(browserValue));
        assertEquals(DraftScopeObservationIntegrity.reference(javaValue),DraftScopeObservationIntegrity.reference(savedValue));
    }
    @Test void requirementRetainsItsOwnAnalysisIdentityWithoutInventingMissingHistoricalIdentity() {
        ObjectNode source=source().put("analysisRevision","analysis-1").put("analysisInputSha256",SHA).put("analysisSha256",String.join("",Collections.nCopies(64,"b")));
        Map<String,Object> req=obj("id","r","sourceQuote","Complete target text.","kind","amendment","nodeIds",new ArrayList<>());
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));
        Map<String,Object> receipt=DraftAutomaticDiscovery.process((s,u)->JsonUtils.write(obj("nodes",new ArrayList<>(),"requirements",Collections.singletonList(req))),plan,task(plan));
        JsonNode retained=JsonUtils.mapper().valueToTree(receipt).path("requirements").path(0);
        assertEquals("analysis-1",retained.path("analysisRevision").asText());
        assertEquals(SHA,retained.path("analysisInputSha256").asText());
        assertEquals(source.path("analysisSha256"),retained.path("analysisSha256"));
        source.remove(Arrays.asList("analysisRevision","analysisInputSha256","analysisSha256"));
        DraftAutomaticDiscovery.Plan legacy=DraftAutomaticDiscovery.plan(Collections.singletonList(source));
        JsonNode unbound=JsonUtils.mapper().valueToTree(DraftAutomaticDiscovery.process((s,u)->JsonUtils.write(obj("nodes",new ArrayList<>(),"requirements",Collections.singletonList(req))),legacy,task(legacy))).path("requirements").path(0);
        assertFalse(unbound.has("analysisRevision"));assertFalse(unbound.has("analysisInputSha256"));assertFalse(unbound.has("analysisSha256"));
        assertEquals("pending",unbound.path("status").asText());assertEquals("Complete target text.",unbound.path("sourceQuote").asText());
    }
    @Test void graphStoresEachCompleteScopeOnceAndRetainsUnboundHistoricalScopeInline() {
        ObjectNode source=source().put("analysisRevision","rev-1").put("analysisInputSha256",SHA).put("analysisSha256",String.join("",Collections.nCopies(64,"b")));
        ObjectNode scope=(ObjectNode)source.path("groups").path(0);scope.putArray("insertionAnchors").addObject().put("id","anchor");
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));
        List<Object> requirements=new ArrayList<>();for(int i=0;i<28;i++)requirements.add(obj("id","r"+i,"sourceQuote","Complete target text.","kind","amendment","nodeIds",new ArrayList<>()));
        Map<String,Object> receipt=DraftAutomaticDiscovery.process((s,u)->JsonUtils.write(obj("nodes",new ArrayList<>(),"requirements",requirements)),plan,task(plan));
        String receiptBefore=JsonUtils.write(receipt);
        Map<String,Object> base=obj("graph",obj("variables",new ArrayList<>(),"events",new ArrayList<>()),"sources",new ArrayList<>());
        JsonNode result=JsonUtils.mapper().valueToTree(DraftAutomaticDiscovery.finish(base,plan,Collections.singletonList(receipt))).path("graph");
        assertEquals(receiptBefore,JsonUtils.write(receipt));assertEquals(28,result.path("requirements").size());assertEquals(1,result.path("sourceScopeObservations").size());
        String ref=result.path("requirements").path(0).path("sourceQuoteEvidence").path("sourceScopeObservationRef").asText();assertFalse(ref.isEmpty());
        for(JsonNode requirement:result.path("requirements")) {assertEquals(ref,requirement.path("sourceQuoteEvidence").path("sourceScopeObservationRef").asText());assertFalse(requirement.path("sourceQuoteEvidence").has("sourceScopeObservation"));assertFalse(requirement.path("sourceQuoteEvidence").has("sourceScopeText"));}
        JsonNode retained=result.path("sourceScopeObservations").path(ref);assertEquals(scope,retained.path("sourceScopeObservation"));assertEquals("Complete target text.",retained.path("sourceScopeText").asText());assertEquals("rev-1",retained.path("analysisRevision").asText());
        Map<String,Object> existing=JsonUtils.mapper().convertValue(obj("graph",result,"sources",new ArrayList<>()),Map.class);
        assertEquals(Collections.singletonMap(ref,"valid"),DraftScopeObservationIntegrity.inspect(existing));
        Map<String,Object> damaged=JsonUtils.readMap(JsonUtils.write(existing));map(map(state(damaged).get("sourceScopeObservations")).get(ref)).put("sourceScopeText","tampered");assertEquals("invalid",DraftScopeObservationIntegrity.inspect(damaged).get(ref));
        map(state(damaged).get("sourceScopeObservations")).remove(ref);assertEquals("missing",DraftScopeObservationIntegrity.inspect(damaged).get(ref));assertEquals(28,list(state(damaged).get("requirements")).size());
        JsonNode repeated=JsonUtils.mapper().valueToTree(DraftAutomaticDiscovery.finish(existing,plan,Collections.singletonList(receipt))).path("graph");assertEquals(28,repeated.path("requirements").size());assertEquals(1,repeated.path("sourceScopeObservations").size());
        Map<String,Object> changed=JsonUtils.readMap(JsonUtils.write(receipt));((Map<String,Object>)((List<?>)changed.get("requirements")).get(0)).put("reason","different reviewed requirement");
        JsonNode merged=JsonUtils.mapper().valueToTree(DraftAutomaticDiscovery.finish(existing,plan,Collections.singletonList(changed))).path("graph");assertEquals(29,merged.path("requirements").size());assertEquals(1,merged.path("sourceScopeObservations").size());assertEquals("",merged.path("requirements").path(0).path("reason").asText());assertEquals("different reviewed requirement",merged.path("requirements").path(28).path("reason").asText());
        source.remove("analysisRevision");DraftAutomaticDiscovery.Plan legacy=DraftAutomaticDiscovery.plan(Collections.singletonList(source));
        Map<String,Object> oldReceipt=DraftAutomaticDiscovery.process((s,u)->JsonUtils.write(obj("nodes",new ArrayList<>(),"requirements",requirements)),legacy,task(legacy));
        JsonNode old=JsonUtils.mapper().valueToTree(DraftAutomaticDiscovery.finish(base,legacy,Collections.singletonList(oldReceipt))).path("graph").path("requirements").path(0).path("sourceQuoteEvidence");
        assertEquals(scope,old.path("sourceScopeObservation"));assertFalse(old.has("sourceScopeObservationRef"));
        JsonNode mixed=JsonUtils.mapper().valueToTree(DraftAutomaticDiscovery.finish(existing,legacy,Collections.singletonList(oldReceipt))).path("graph");assertEquals(56,mixed.path("requirements").size());assertEquals(1,mixed.path("sourceScopeObservations").size());assertEquals(ref,mixed.path("requirements").path(0).path("sourceQuoteEvidence").path("sourceScopeObservationRef").asText());assertEquals(scope,mixed.path("requirements").path(28).path("sourceQuoteEvidence").path("sourceScopeObservation"));
    }
    @Test void relatedGuidanceAndScopeStayBoundAndUnresolvedScopeIsNotAnEdit() {
        ObjectNode source=source();DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));Map<String,Object> task=task(plan);
        Map<String,Object> good=node("one","Delete if precast does not apply"),bad=node("bad","invented");
        Map<String,Object> req=obj("id","r","sourceQuote","Complete target text.","kind","amendment","nodeIds",new ArrayList<>());
        JsonNode result=JsonUtils.mapper().valueToTree(DraftAutomaticDiscovery.process((s,u)->JsonUtils.write(obj("nodes",Arrays.asList(good,bad),"requirements",Collections.singletonList(req))),plan,task));
        assertEquals("partial",result.path("status").asText());assertEquals(1,result.path("nodes").size());
        JsonNode candidate=result.path("nodes").path(0);assertEquals("pending",candidate.path("status").asText());assertEquals(2,candidate.path("sourceAnchors").size());
        assertEquals("related",candidate.path("sourceAnchors").path(1).path("sourceObservationId").asText());assertEquals(source.path("guidanceNotes").path(1),candidate.path("sourceAnchors").path(1).path("sourceObservation"));
        assertTrue(candidate.path("editTargets").isEmpty());assertEquals(1,result.path("requirements").size());
        assertEquals("scope",result.path("requirements").path(0).path("sourceQuoteEvidence").path("sourceObservationId").asText());
        assertEquals("pending",result.path("requirements").path(0).path("status").asText());
    }
    @Test void wrongIdsUnrelatedAmbiguousAndUnboundObservationsAreRejected() {
        ObjectNode source=source();DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));Map<String,Object> task=task(plan);
        assertNull(DraftReviewDiscovery.sourceTaskQuote(source,task,"Delete if precast does not apply","scope"));
        source.withArray("guidanceNotes").addObject().put("id","unrelated").put("text","Unrelated text.").putArray("memberRanges").addObject().put("memberId","main").put("channel","text").put("start",0).put("end",15);
        assertNull(DraftReviewDiscovery.sourceTaskQuote(source,task,"Unrelated text.",""));
        ObjectNode duplicate=source.withArray("guidanceNotes").addObject().put("id","other").put("text","Delete if precast does not apply");
        duplicate.putArray("memberRanges").addObject().put("memberId","other-member").put("channel","text").put("start",0).put("end",32);member(source,"other-member","Delete if precast does not apply");((ObjectNode)source.path("groups").path(0)).withArray("guidanceNoteIds").add("other");
        assertNull(DraftReviewDiscovery.sourceTaskQuote(source,task,"Delete if precast does not apply",""));
        assertNotNull(DraftReviewDiscovery.sourceTaskQuote(source,task,"Delete if precast does not apply","related"));
        ((ObjectNode)source.path("sourceMembers").path(1).path("sourcePosition")).put("sourceSha256","changed");
        assertNull(DraftReviewDiscovery.sourceTaskQuote(source,task,"Delete if precast does not apply","related"));
    }
    @Test void completeScopeWithoutAmendmentRemainsPendingCheck() {
        ObjectNode source=source();JsonNode member=source.path("sourceMembers").path(2);ObjectNode paragraph=source.withArray("paragraphs").addObject().put("id","target-p").put("text","Complete target text.").put("paragraphSha256",SHA).put("sourceSha256",SHA);paragraph.set("sourcePosition",member.path("sourcePosition").deepCopy());
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));Map<String,Object> n=node("one","Main instruction.");n.put("effects",Collections.singletonList(obj("scopeId","scope","quote","Complete target text.","action","replace")));
        JsonNode receipt=JsonUtils.mapper().valueToTree(DraftAutomaticDiscovery.process((s,u)->JsonUtils.write(obj("nodes",Collections.singletonList(n),"requirements",Collections.singletonList(obj("id","r","sourceQuote","Main instruction.","kind","input","nodeIds",Collections.singletonList("one"))))),plan,task(plan)));
        JsonNode target=receipt.path("nodes").path(0).path("editTargets").path(0);assertEquals("check",target.path("action").asText());assertTrue(target.path("needsRebind").asBoolean());assertFalse(target.has("replacementEn"));assertTrue(target.path("native").path("spans").isArray());
    }
    @Test void refinementIsAnExactUniquePendingCharacterRangeWithinTheOriginalScope() {
        ObjectNode source=source();JsonNode member=source.path("sourceMembers").path(2);ObjectNode paragraph=source.withArray("paragraphs").addObject().put("id","target-p").put("text","Complete target text.").put("paragraphSha256",SHA).put("sourceSha256",SHA);paragraph.set("sourcePosition",member.path("sourcePosition").deepCopy());
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));
        for(String refinement:Arrays.asList("target","invented","t")) {
            Map<String,Object> n=node("one","Main instruction.");n.put("effects",Collections.singletonList(obj("scopeId","scope","quote","Complete target text.","action","replace","replacementEn","updated","refinedQuote",refinement)));
            JsonNode receipt=JsonUtils.mapper().valueToTree(DraftAutomaticDiscovery.process((system,user)->JsonUtils.write(obj("nodes",Collections.singletonList(n),"requirements",Collections.singletonList(obj("id","r","sourceQuote","Main instruction.","kind","input","nodeIds",Collections.singletonList("one"))))),plan,task(plan)));
            JsonNode target=receipt.path("nodes").path(0).path("editTargets").path(0);assertEquals("check",target.path("action").asText());assertTrue(target.path("needsRebind").asBoolean());assertTrue(target.path("observedScopeComplete").asBoolean());assertEquals("Complete target text.",target.path("text").asText());
            if("target".equals(refinement)){JsonNode candidate=target.path("refinementCandidate");assertEquals("pending",candidate.path("status").asText());assertFalse(candidate.path("editableAuthority").asBoolean());assertEquals(9,candidate.path("native").path("spans").path(0).path("start").asInt());assertEquals(15,candidate.path("native").path("spans").path(0).path("end").asInt());assertEquals("target",candidate.path("native").path("spans").path(0).path("expectedText").asText());}
            else {assertFalse(target.has("refinementCandidate"));assertEquals("UNIQUE_SOURCE_BOUND_SUBRANGE_REQUIRED",target.path("refinementIssue").asText());}
        }
    }
    @Test void refinementCannotCrossNativeSpansOrSplitUtf16Characters() {
        ObjectNode source=source();String first="Alpha \uD83D\uDE00",second="Second";((ObjectNode)source.path("sourceMembers").path(2).path("textObservation")).put("text",first);member(source,"second",second);
        for(int index:Arrays.asList(2,3)){JsonNode m=source.path("sourceMembers").path(index);ObjectNode p=source.withArray("paragraphs").addObject().put("id","p"+index).put("text",m.path("textObservation").path("text").asText()).put("paragraphSha256",SHA).put("sourceSha256",SHA);p.set("sourcePosition",m.path("sourcePosition").deepCopy());}
        ObjectNode scope=(ObjectNode)source.path("groups").path(0);((ObjectNode)scope.path("memberRanges").path(0)).put("end",first.length());scope.withArray("memberRanges").addObject().put("memberId","second").put("channel","text").put("start",0).put("end",second.length());
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));
        for(String refined:Arrays.asList("\uD83D",first.substring(6)+"\nSecond")){Map<String,Object> n=node("one","Main instruction.");n.put("effects",Collections.singletonList(obj("scopeId","scope","quote",first+"\n"+second,"action","delete","refinedQuote",refined)));
            JsonNode receipt=JsonUtils.mapper().valueToTree(DraftAutomaticDiscovery.process((system,user)->JsonUtils.write(obj("nodes",Collections.singletonList(n),"requirements",Collections.singletonList(obj("id","r","sourceQuote","Main instruction.","kind","input","nodeIds",Collections.singletonList("one"))))),plan,task(plan)));
            JsonNode target=receipt.path("nodes").path(0).path("editTargets").path(0);assertEquals("check",target.path("action").asText());assertTrue(target.path("needsRebind").asBoolean());assertFalse(target.has("refinementCandidate"));assertEquals("UNIQUE_SOURCE_BOUND_SUBRANGE_REQUIRED",target.path("refinementIssue").asText());}
    }
    @Test void wordAndPdfEditionsKeepSeparateTaskAndObservationIdentity() {
        ObjectNode word=source(),pdf=source();String pdfSha=String.join("",Collections.nCopies(64,"b"));
        pdf.put("id","2").put("sourceDocumentId",2).put("sourceSha256",pdfSha).put("format","pdf").put("editionId","pdf-edition");word.put("editionId","word-edition");pdf.putObject("structure").put("physicalPages",1);
        for(JsonNode raw:pdf.path("sourceMembers")){ObjectNode member=(ObjectNode)raw;member.remove("sourcePosition");ObjectNode block=member.putObject("blockObservation").put("id",member.path("id").asText()).put("source","native_pdf").put("pageNo",1);block.putObject("readingContext").put("sourceSha256",pdfSha);}
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Arrays.asList(word,pdf));Map<String,Object> wordTask=task(plan),pdfTask=plan.tasks().stream().filter(t->"2".equals(t.get("sourceId"))&&"main-note".equals(t.get("sourceObservationId"))).findFirst().orElseThrow();
        assertThrows(RuntimeException.class,()->DraftReviewDiscovery.sourceTaskQuote(pdf,wordTask,"Delete if precast does not apply","related"));
        Map<String,Object> pdfQuote=DraftReviewDiscovery.sourceTaskQuote(pdf,pdfTask,"Delete if precast does not apply","related");assertNotNull(pdfQuote);
        JsonNode anchor=JsonUtils.mapper().valueToTree(pdfQuote).path("anchor");assertEquals(pdfSha,anchor.path("sourceSha256").asText());assertEquals(2,anchor.path("sourceDocumentId").asInt());assertFalse(anchor.has("native"));
        Map<String,Object> pdfScope=DraftReviewDiscovery.sourceTaskQuote(pdf,pdfTask,"Complete target text.","scope");assertNotNull(pdfScope);assertFalse(JsonUtils.mapper().valueToTree(pdfScope).has("native"));
    }
    private static ObjectNode source() {
        ObjectNode source=JsonUtils.mapper().createObjectNode().put("id","1").put("sourceDocumentId",1).put("sourceSha256",SHA).put("format","docx").put("fileKey","NTT").put("evidenceStatus","bound");
        for(String key:Arrays.asList("sourceMembers","paragraphs","guidanceNotes","markerCandidates","groups","conditions"))source.putArray(key);
        member(source,"main","Main instruction.");member(source,"related-member","Delete if precast does not apply");member(source,"target","Complete target text.");
        note(source,"main-note","main","Main instruction.");note(source,"related","related-member","Delete if precast does not apply");
        ObjectNode group=source.withArray("groups").addObject().put("id","scope").put("guidanceNoteId","main-note").put("scopeComplete",true);group.putArray("guidanceNoteIds").add("related");group.putArray("memberRanges").addObject().put("memberId","target").put("channel","text").put("start",0).put("end",21);
        return source;
    }
    private static void member(ObjectNode source,String id,String text){ObjectNode m=source.withArray("sourceMembers").addObject().put("id",id).put("identityStatus","source_bound");m.putObject("sourcePosition").put("sourceSha256",SHA).put("partName","word/document.xml").put("nodePath","/"+id);m.putObject("textObservation").put("text",text);}
    private static void note(ObjectNode source,String id,String member,String text){ObjectNode n=source.withArray("guidanceNotes").addObject().put("id",id).put("text",text);n.putArray("memberRanges").addObject().put("memberId",member).put("channel","text").put("start",0).put("end",text.length());}
    private static Map<String,Object> task(DraftAutomaticDiscovery.Plan plan){return plan.tasks().stream().filter(t->"main-note".equals(t.get("sourceObservationId"))).findFirst().orElseThrow();}
    private static Map<String,Object> node(String id,String quote){return obj("id",id,"type","boolean","role","input","nameEn","Question","sourceQuote",quote,"branches",new ArrayList<>(),"effects",new ArrayList<>(),"options",new ArrayList<>());}
}
