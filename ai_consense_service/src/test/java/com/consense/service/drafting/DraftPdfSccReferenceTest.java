package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.consense.service.drafting.DraftReviewGraph.*;
import static org.junit.jupiter.api.Assertions.*;

class DraftPdfSccReferenceTest {
    @TempDir Path temp;
    private Map<String,Object> source(String sha,String format){return obj("id","17","fileKey","SCC","format",format,"sourceSha256",sha);}
    private Map<String,Object> revision(Object source,boolean stale){return obj("sourceStale",stale,"graph",obj("selectedDocuments",obj("SCC","17"),"sources",Collections.singletonList(source)));}
    private DraftPdfSccReference reference() throws Exception {
        List<Object> points=new ArrayList<>(),rules=new ArrayList<>();
        for(int i=0;i<74;i++)points.add(obj("id",String.format("PDF-SCC-%04d",i),"label","synthetic "+i,"kind","verification","clause","SCC"+i,"meaning","Example "+i,"relationStatus","unknown","wordCandidates",Collections.singletonList("not returned"),"evidence",Collections.singletonList(obj("page",i+1,"quote","synthetic source"))));
        for(int i=0;i<138;i++)rules.add(obj("id","rule-"+i,"chainIds",Collections.singletonList(String.format("PDF-SCC-%04d",i%74)),"evidence",Collections.singletonList("not duplicated"),"inputs",i==0?Arrays.asList(obj("ref","pdf:input:twoEnvelopeTendering","valueType","V1","stage","effective","role","primary"),obj("ref","pdf:input:verificationEvidence:0000","valueType","V7","stage","source_evidence","role","evidence"),obj("ref","pdf:input:adoptedPdf42Branch","valueType","V5","stage","final_adopted","role","adopted_decision")):i==1?Collections.singletonList(obj("ref","pdf:output:derived","stage","derived","role","prerequisite")):Collections.emptyList(),"cases",i==0?Collections.singletonList(obj("id","conditional","when",obj("op","eq"),"outputs",Collections.singletonList(obj("ref","pdf:output:derived")))):Collections.emptyList()));
        byte[] pdf=JsonUtils.write(obj("sourceId","scc-pdf-1bebab1afbc0","sourceSha256",DraftPdfSccReference.SOURCE_SHA,"points",points)).getBytes(StandardCharsets.UTF_8);
        byte[] atomic=JsonUtils.write(obj("sourceId","scc-pdf-1bebab1afbc0","sourceSha256",DraftPdfSccReference.SOURCE_SHA,"rules",rules,"inputs",Arrays.asList(obj("ref","pdf:input:twoEnvelopeTendering","valueType","V1"),obj("ref","pdf:input:verificationEvidence:0000","valueType","V7"),obj("ref","pdf:input:adoptedPdf42Branch","valueType","V5")))).getBytes(StandardCharsets.UTF_8);
        Files.write(temp.resolve("pdf.json"),pdf);Files.write(temp.resolve("atomic-pdf.json"),atomic);
        return new DraftPdfSccReference(temp.toString(),DraftPdfConverter.sha256(pdf),DraftPdfConverter.sha256(atomic));
    }
    @Test void exactSelectedPdfHasBoundedPagesAndSinglePointDetail() throws Exception {
        DraftPdfSccReference reference=reference();Map<String,Object> rev=revision(source(DraftPdfSccReference.SOURCE_SHA,"pdf"),false);
        Map<String,Object> page=reference.search(rev,"17","",0);assertEquals(true,page.get("available"));assertEquals(74,page.get("total"));assertEquals(12,list(page.get("candidates")).size());assertEquals(12,page.get("nextOffset"));assertEquals(false,page.get("executable"));
        List<?> facts=list(reference.facts(rev,"17").get("facts"));assertEquals(1,facts.size());assertEquals("pdf:input:twoEnvelopeTendering",map(facts.get(0)).get("inputRef"));
        assertEquals(1,reference.search(rev,"17","synthetic 73",0).get("total"));
        Map<String,Object> detail=reference.detail(rev,"17","PDF-SCC-0001");assertEquals(true,detail.get("available"));Map<String,Object> point=map(detail.get("point"));assertFalse(point.containsKey("wordCandidates"));assertEquals("unknown",point.get("relationStatus"));assertEquals(1,list(point.get("evidence")).size());assertFalse(map(list(detail.get("rules")).get(0)).containsKey("evidence"));
    }
    @Test void rejectsStaleUnselectedWrongEditionAndAmbiguousSource() throws Exception {
        DraftPdfSccReference reference=reference();Map<String,Object> s=source(DraftPdfSccReference.SOURCE_SHA,"pdf");
        assertEquals(false,reference.search(revision(s,true),"17","",0).get("available"));
        assertEquals(false,reference.search(revision(s,false),"18","",0).get("available"));
        assertEquals(false,reference.search(revision(source("f".repeat(64),"pdf"),false),"17","",0).get("available"));
        assertEquals(false,reference.search(revision(source(DraftPdfSccReference.SOURCE_SHA,"docx"),false),"17","",0).get("available"));
        Map<String,Object> duplicate=revision(s,false);map(duplicate.get("graph")).put("sources",Arrays.asList(s,s));assertEquals(false,reference.search(duplicate,"17","",0).get("available"));
    }
    @Test void missingOrModifiedDataNeverFallsBackToWord() throws Exception {
        DraftPdfSccReference reference=reference();Files.write(temp.resolve("pdf.json"),"{}".getBytes(StandardCharsets.UTF_8));
        Map<String,Object> result=reference.search(revision(source(DraftPdfSccReference.SOURCE_SHA,"pdf"),false),"17","",0);
        assertEquals(false,result.get("available"));assertEquals("reference_missing_or_integrity_failed",result.get("status"));
    }
    @Test void postAtomicPdfMatchingUsesOnlyCurrentSourceAndRequestsEvidenceWithoutAdopting() throws Exception {
        DraftPdfSccReference reference=reference();String quote="Whether two envelopes are required";
        ObjectNode source=JsonUtils.mapper().createObjectNode().put("id","17").put("sourceDocumentId",17).put("sourceSha256",DraftPdfSccReference.SOURCE_SHA).put("format","pdf").put("fileKey","SCC").put("fileName","SCC.pdf").put("evidenceStatus","bound");
        for(String key:Arrays.asList("sourceMembers","paragraphs","guidanceNotes","markerCandidates","groups","conditions"))source.putArray(key);
        ObjectNode member=source.withArray("sourceMembers").addObject().put("id","body").put("storyKind","main").put("identityStatus","source_bound");member.putObject("textObservation").put("text",quote);
        ObjectNode note=source.withArray("guidanceNotes").addObject().put("id","note").put("text",quote).put("scopeComplete",false);note.putArray("memberRanges").addObject().put("memberId","body").put("channel","text").put("start",0).put("end",quote.length());
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));Map<String,Object> modelNode=obj("id","fact","type","boolean","role","input","nameZh","two envelope","nameEn","two envelope","questionEn","two envelope","sourceQuote",quote,"options",new ArrayList<>(),"branches",new ArrayList<>(),"effects",new ArrayList<>());
        Map<String,Object> atomic=DraftAutomaticDiscovery.process((system,user)->JsonUtils.write(obj("nodes",Collections.singletonList(modelNode),"requirements",Collections.singletonList(obj("id","req","sourceQuote",quote,"kind","input","nodeIds",Collections.singletonList("fact"))))),plan,plan.tasks().get(0));
        String id=text(map(list(atomic.get("nodes")).get(0)).get("id"));int[] calls={0};String ref="pdf:input:twoEnvelopeTendering";
        Map<String,Object> linked=DraftAutomaticDiscovery.processRelation((system,user)->{calls[0]++;if(calls[0]==1){assertTrue(user.contains(ref));assertFalse(user.contains("verificationEvidence"));assertFalse(user.contains("adoptedPdf42Branch"));assertFalse(user.contains("fixedCatalog"));return JsonUtils.write(obj("relations",Collections.emptyList(),"pdfReferenceMatches",Collections.emptyList(),"pdfReferenceContextRequests",Collections.singletonList(ref)));}
            assertTrue(user.contains("synthetic source"));return JsonUtils.write(obj("relations",Collections.emptyList(),"pdfReferenceMatches",Arrays.asList(obj("nodeId",id,"inputRef",ref,"sourceQuote",quote,"reason","Same possible fact","confidence",0.9),obj("nodeId",id,"inputRef","pdf:input:adoptedPdf42Branch","sourceQuote",quote,"reason","Not an effective fact","confidence",0.8))));
        },plan,Collections.singletonList(atomic),DraftAutomaticDiscovery.planRelations(plan,Collections.singletonList(atomic)).get(0),reference,"17");
        assertEquals(2,calls[0]);Map<String,Object> graph=obj("graph",obj("variables",new ArrayList<>(),"events",new ArrayList<>()),"sources",new ArrayList<>());Map<String,Object> node=nodes(DraftAutomaticDiscovery.finish(graph,plan,Arrays.asList(atomic,linked))).get(0);Map<String,Object> suggestions=map(node.get("pdfReferenceSuggestions"));
        assertEquals("pending",suggestions.get("status"));assertEquals("17",suggestions.get("sourceId"));assertEquals(DraftPdfSccReference.SOURCE_SHA,suggestions.get("sourceSha256"));assertEquals(1,list(suggestions.get("candidates")).size());assertEquals("unverified",map(list(suggestions.get("candidates")).get(0)).get("semanticStatus"));assertFalse(Boolean.TRUE.equals(map(node.get("catalogAlignment")).get("confirmed")));
    }
    private Map<String,Object> alignedNode(){String quote="Whether two envelopes are required";return obj("id","fact","type",obj("kind","boolean"),"role",obj("kind","input"),"nameEn","Two envelopes","questionEn",quote,"semanticSourceQuote",quote,"docIds",Collections.singletonList("17"),"sourceAnchors",Collections.singletonList(obj("docId","17","sourceSha256",DraftPdfSccReference.SOURCE_SHA,"text",quote,"sourceObservationBinding",obj("status","bound"))),"pdfReferenceAlignment",obj("status","confirmed","confirmed",true,"sourceId","17","sourceSha256",DraftPdfSccReference.SOURCE_SHA,"inputRef","pdf:input:twoEnvelopeTendering","sourceQuote",quote,"reason","Reviewed equivalent fact","requestId",UUID.randomUUID().toString(),"basis",null));}
    private Map<String,Object> graphWithNode(Map<String,Object> node){Map<String,Object> graph=map(revision(source(DraftPdfSccReference.SOURCE_SHA,"pdf"),false).get("graph"));graph.put("graph",obj("variables",Collections.singletonList(node)));return graph;}
    private Map<String,Object> copy(Map<String,Object> value){return JsonUtils.readMap(JsonUtils.write(value));}
    @Test void humanConfirmationIsBoundAndChangingMeaningCannotSilentlyResign() throws Exception {
        DraftPdfSccReference reference=reference();Map<String,Object> node=alignedNode(),graph=graphWithNode(node);DraftPdfSccAlignment.reconcile(graph,Collections.emptyMap(),reference);
        Map<String,Object> revision=obj("sourceStale",false,"graph",graph);Map<String,Object> related=reference.relatedRules(revision,"17",0);assertEquals(2,related.get("total"));assertEquals("unbound",map(list(related.get("rules")).get(0)).get("targetBinding"));assertEquals(false,map(list(related.get("rules")).get(0)).get("executable"));
        String basis=text(map(node.get("pdfReferenceAlignment")).get("basis"));assertFalse(basis.isEmpty());Map<String,Object> saved=copy(graph);
        node.put("questionEn","A different question");assertEquals(0,reference.relatedRules(revision,"17",0).get("total"));DraftPdfSccAlignment.reconcile(graph,saved,reference);Map<String,Object> stale=map(node.get("pdfReferenceAlignment"));assertEquals(false,stale.get("confirmed"));assertEquals("stale",stale.get("status"));assertEquals(basis,stale.get("basis"));
        Map<String,Object> staleSaved=copy(graph);stale.put("confirmed",true);stale.put("status","confirmed");DraftPdfSccAlignment.reconcile(graph,staleSaved,reference);assertEquals(false,map(node.get("pdfReferenceAlignment")).get("confirmed"));
        Map<String,Object> recheck=map(node.get("pdfReferenceAlignment"));recheck.put("confirmed",true);recheck.put("status","confirmed");recheck.put("requestId",UUID.randomUUID().toString());recheck.put("reason","Reviewed changed definition");DraftPdfSccAlignment.reconcile(graph,staleSaved,reference);assertEquals(true,map(node.get("pdfReferenceAlignment")).get("confirmed"));assertNotEquals(basis,map(node.get("pdfReferenceAlignment")).get("basis"));
    }
    @Test void explicitConfirmationRejectsForeignSourceUnsupportedOrEvidenceOnlyReferences() throws Exception {
        DraftPdfSccReference reference=reference();Map<String,Object> node=alignedNode(),graph=graphWithNode(node),alignment=map(node.get("pdfReferenceAlignment"));
        alignment.put("inputRef","pdf:input:verificationEvidence:0000");assertThrows(RuntimeException.class,()->DraftPdfSccAlignment.reconcile(graph,Collections.emptyMap(),reference));
        alignment.put("inputRef","pdf:input:adoptedPdf42Branch");assertThrows(RuntimeException.class,()->DraftPdfSccAlignment.reconcile(graph,Collections.emptyMap(),reference));
        alignment.put("inputRef","pdf:input:twoEnvelopeTendering");alignment.put("sourceQuote","not in source");assertThrows(RuntimeException.class,()->DraftPdfSccAlignment.reconcile(graph,Collections.emptyMap(),reference));
        alignment.put("sourceQuote","Whether two envelopes are required");map(map(list(node.get("sourceAnchors")).get(0)).get("sourceObservationBinding")).put("status","missing");assertThrows(RuntimeException.class,()->DraftPdfSccAlignment.reconcile(graph,Collections.emptyMap(),reference));map(map(list(node.get("sourceAnchors")).get(0)).get("sourceObservationBinding")).put("status","bound");
        assertThrows(RuntimeException.class,()->DraftPdfSccAlignment.reconcile(graph,Collections.emptyMap(),null));
        alignment.put("sourceQuote","Whether two envelopes are required");map(graph.get("selectedDocuments")).put("SCC","other");assertThrows(RuntimeException.class,()->DraftPdfSccAlignment.reconcile(graph,Collections.emptyMap(),reference));
    }
    @Test void manuallyAddedPdfFactCanUseItsServerBoundSourceAnchor() throws Exception {
        DraftPdfSccReference reference=reference();Map<String,Object> node=alignedNode();node.remove("semanticSourceQuote");node.put("definitionKind","added");node.put("manual",true);Map<String,Object> graph=graphWithNode(node);
        DraftPdfSccAlignment.reconcile(graph,Collections.emptyMap(),reference);assertEquals(true,map(node.get("pdfReferenceAlignment")).get("confirmed"));
    }
}
