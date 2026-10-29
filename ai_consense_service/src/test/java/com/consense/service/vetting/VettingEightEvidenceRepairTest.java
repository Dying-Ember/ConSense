package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VettingEightEvidenceRepairTest {
    @Test void eightSourceBoundQuotesSurviveMissingEvidenceRepair() {
        VettingBusinessShapeRepairTest.Fixture f=new VettingBusinessShapeRepairTest.Fixture();
        List<String> quotes=quotes(f); JsonNode original=JsonUtils.parse(f.raw("amend",false,quotes.get(0)));
        ((ObjectNode)original.get(0)).remove("evidence");
        VettingSemanticReview.Result result=f.review(f.wire(JsonUtils.write(original),patch(quotes)));
        assertEquals(2,result.getActualGatewayCallCount());
        assertEquals(1,result.getCandidates().size(),"Eight independently located quotations must not reject the whole repaired record");
        assertEquals(8,result.getCandidates().get(0).getEvidence().size());
        assertTrue(result.getCandidates().get(0).getEvidence().stream().allMatch(e->e.isLocated()));
    }
    @Test void eighthInventedQuoteStillFailsSourceVerification() {
        VettingBusinessShapeRepairTest.Fixture f=new VettingBusinessShapeRepairTest.Fixture();
        List<String> quotes=quotes(f); JsonNode original=JsonUtils.parse(f.raw("amend",false,quotes.get(0)));
        ((ObjectNode)original.get(0)).remove("evidence"); quotes.set(7,"Invented eighth quotation absent from the source.");
        VettingSemanticReview.Result result=f.review(f.wire(JsonUtils.write(original),patch(quotes)));
        assertEquals(2,result.getActualGatewayCallCount()); assertTrue(result.getCandidates().isEmpty());
        assertEquals("structured_output_invalid",VettingEvidenceRepairTest.packet(result).getAttempts().get(1).getStatus());
    }
    @Test void evidenceIndexSchemaCoversTheWholeBoundedEvidenceArray() {
        JsonNode fields=VettingOutputSchema.forChunkIds(Collections.singleton("R1")).path("items").path("properties");
        assertEquals(16,fields.path("evidence").path("maxItems").asInt());
        assertEquals(15,fields.path("checklistAssessments").path("items").path("properties").path("support").path("anyOf").get(1).path("properties").path("facts").path("items").path("properties").path("evidenceIndex").path("maximum").asInt());
    }
    @Test void seventeenQuotesRemainBoundedAndCannotPublish() {
        VettingBusinessShapeRepairTest.Fixture f=new VettingBusinessShapeRepairTest.Fixture();
        List<String> quotes=quotes(f); JsonNode original=JsonUtils.parse(f.raw("amend",false,quotes.get(0)));
        ((ObjectNode)original.get(0)).remove("evidence"); while(quotes.size()<17)quotes.add(quotes.get(0));
        VettingSemanticReview.Result result=f.review(f.wire(JsonUtils.write(original),patch(quotes)));
        assertEquals(2,result.getActualGatewayCallCount());assertTrue(result.getCandidates().isEmpty());
        assertEquals("structured_output_invalid",VettingEvidenceRepairTest.packet(result).getAttempts().get(1).getStatus());
    }
    @Test void oldCheckpointKeepsItsOwnPromptIdentityAndRejectsANewContractAtTheSamePacket() {
        JsonNode current=VettingOutputSchema.forChunkIds(Collections.singleton("R1"));JsonNode old=current.deepCopy();
        ObjectNode fields=(ObjectNode)old.path("items").path("properties");((ObjectNode)fields.path("evidence")).put("maxItems",4);
        ((ObjectNode)fields.path("checklistAssessments").path("items").path("properties").path("support").path("anyOf").get(1).path("properties").path("facts").path("items").path("properties").path("evidenceIndex")).put("maximum",3);
        String sourceSha=VettingCorpus.hash("authored checkpoint source");
        VettingInputBudget.Input previous=VettingInputBudget.input("synthetic","model","system","source",old,100,sourceSha);
        VettingInputBudget.Input changed=VettingInputBudget.input("synthetic","model","system","source",current,100,sourceSha);
        assertNotEquals(previous.getSerializedInputSha256(),changed.getSerializedInputSha256());
        com.consense.web.dto.VettingDtos.SemanticPacketVO packet=new com.consense.web.dto.VettingDtos.SemanticPacketVO();packet.setPacketId("authored-packet");packet.setSourceSnapshotSha256(sourceSha);packet.setPromptEnvelopeSha256(previous.getSerializedInputSha256());
        com.consense.domain.VettingPacketCheckpoint row=new com.consense.domain.VettingPacketCheckpoint();row.setPacketId(packet.getPacketId());row.setSourceSnapshotSha256(sourceSha);row.setPromptEnvelopeSha256(packet.getPromptEnvelopeSha256());
        assertDoesNotThrow(()->org.springframework.test.util.ReflectionTestUtils.invokeMethod(VettingCheckpointService.class,"compatible",row,packet));
        packet.setPromptEnvelopeSha256(changed.getSerializedInputSha256());
        assertThrows(com.consense.common.BizException.class,()->org.springframework.test.util.ReflectionTestUtils.invokeMethod(VettingCheckpointService.class,"compatible",row,packet));
    }
    @Test void nineDifferentSubmittedDocumentsRemainBoundToTheirOwnQuotesAfterRepair() throws Exception {
        VettingResponsesSemanticHarnessTest harness=new VettingResponsesSemanticHarnessTest();
        List<VettingCorpus.Chunk> sources=new ArrayList<>();
        for(int i=0;i<9;i++)sources.add(org.springframework.test.util.ReflectionTestUtils.invokeMethod(harness,"c","source-"+i,"document-"+i,"AAA."+i,"AUTHORED independently scoped obligation number "+i+" shall be checked."));
        com.consense.config.ConsenseProperties props=org.springframework.test.util.ReflectionTestUtils.invokeMethod(harness,"props",sources);
        props.getVetting().setSemanticContextChars(10000);props.getVetting().setSemanticContextExpansionChars(10000);
        VettingBusinessShapeRepairTest.Fixture f=new VettingBusinessShapeRepairTest.Fixture();
        JsonNode original=JsonUtils.parse(f.raw("amend",false,sources.get(0).getContent()));((ObjectNode)original.get(0)).remove("evidence");
        VettingResponsesTransportTest.Http wire=new VettingResponsesTransportTest.Http(){int calls;
            @Override public VettingResponsesTransport.Response post(String endpoint,String body,long timeout,int cap,String auth){
                if(endpoint.endsWith("/responses")){String user=JsonUtils.parse(body).path("input").get(0).path("content").asText();
                    List<Map<String,String>> evidence=new ArrayList<>();
                    for(VettingCorpus.Chunk source:sources){String alias=VettingTokenFixture.reference(user,source);assertTrue(alias.matches("R[0-9]+"),"Each distinct document must be admitted to the submitted packet");evidence.add(Map.of("chunkId",alias,"side","source","quote",source.getContent()));}
                    generation=VettingResponsesTransportTest.completed(++calls==1?JsonUtils.write(original):JsonUtils.write(Collections.singletonList(Map.of("recordIndex",0,"evidence",evidence))));}
                return super.post(endpoint,body,timeout,cap,auth);
            }};
        VettingRetrievalClient retrieval=org.mockito.Mockito.mock(VettingRetrievalClient.class);
        org.mockito.Mockito.when(retrieval.retrieve(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq("tender"),org.mockito.ArgumentMatchers.anyList())).thenReturn(sources);
        VettingSemanticReview review=new VettingSemanticReview(new com.consense.ai.AiGateway(org.mockito.Mockito.mock(com.consense.ai.LlmClient.class)),props,retrieval);
        review.setResponsesTransport(new VettingResponsesTransport(props,wire));
        VettingSemanticReview.Result result=review.review("authored-nine-documents",sources,"en",(n,text)->{});
        assertEquals(2,result.getActualGatewayCallCount());assertEquals(1,result.getCandidates().size());
        assertEquals(9,result.getCandidates().get(0).getEvidence().size());
        assertEquals(9,result.getCandidates().get(0).getEvidence().stream().map(e->e.getDocumentId()).distinct().count());
        assertTrue(result.getCandidates().get(0).getEvidence().stream().allMatch(e->e.isLocated()));
        VettingSemanticReview.Candidate candidate=result.getCandidates().get(0);
        VettingCheckpointService.Saved saved=new VettingCheckpointService.Saved();saved.setCandidates(Collections.singletonList(candidate));
        VettingCheckpointService.Saved restored=JsonUtils.read(JsonUtils.write(saved),VettingCheckpointService.Saved.class);
        assertEquals(9,restored.getCandidates().get(0).getEvidence().size());
        com.consense.domain.VettingFinding stored=new com.consense.domain.VettingFinding();
        VettingService service=org.mockito.Mockito.mock(VettingService.class,org.mockito.Mockito.CALLS_REAL_METHODS);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"apply",stored,candidate);
        assertEquals(9,JsonUtils.parse(stored.getEvidenceJson()).size());
        JsonNode export=org.springframework.test.util.ReflectionTestUtils.invokeMethod(VettingService.class,"reportEvidence",candidate.getEvidence());
        assertEquals(9,export.size());
        com.consense.web.dto.VettingDtos.FindingVO finding=new com.consense.web.dto.VettingDtos.FindingVO();finding.setCode("AUTHORED");finding.setFileKey("AAA");finding.setStatus("Open");
        finding.setTitle(com.consense.common.LocalizedText.same("Authored title"));finding.setBody(com.consense.common.LocalizedText.same("Authored review"));finding.setEvidence(candidate.getEvidence());
        com.consense.domain.Project project=new com.consense.domain.Project();project.setId("authored");project.setNameEn("Authored project");
        byte[] wordBytes=new VettingReportWriter().docx(project,Collections.singletonList(finding),null,"en");
        try(org.apache.poi.xwpf.usermodel.XWPFDocument word=new org.apache.poi.xwpf.usermodel.XWPFDocument(new java.io.ByteArrayInputStream(wordBytes))){
            String content=word.getTables().stream().map(t->t.getText()).reduce("",(a,b)->a+"\n"+b);
            for(VettingCorpus.Chunk source:sources)assertTrue(content.contains(source.getContent()),"Each distinct quotation must survive the Word report");
        }
    }
    private static List<String> quotes(VettingBusinessShapeRepairTest.Fixture f) {
        List<String> result=new ArrayList<>();
        for(int i=1;i<=8;i++)result.add("AUTHORED source obligation number "+i+" shall be checked.");
        f.source.setContent(String.join(" ",result)); return result;
    }
    private static String patch(List<String> quotes) {
        List<Map<String,String>> evidence=new ArrayList<>();
        for(String quote:quotes)evidence.add(Map.of("chunkId","R1","side","source","quote",quote));
        return JsonUtils.write(Collections.singletonList(Map.of("recordIndex",0,"evidence",evidence)));
    }
}
