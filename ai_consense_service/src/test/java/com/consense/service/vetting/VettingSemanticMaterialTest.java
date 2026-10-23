package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingSemanticReview.Quote;
import com.consense.service.vetting.VettingSemanticReview.Record;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class VettingSemanticMaterialTest {
    @Test void fullScopeReviewSubmitsUnrankedTextAndBothSidesOfAnExplicitCrossFileReference() throws Exception {
        com.consense.config.ConsenseProperties properties=new com.consense.config.ConsenseProperties();
        properties.getVetting().setSemanticContextChars(4000);properties.getVetting().setSemanticContextExpansionChars(4000);
        com.consense.ai.AiGateway gateway=mock(com.consense.ai.AiGateway.class);when(gateway.chatModel()).thenReturn("fixture");
        when(gateway.completeStructuredJsonList(anyString(),anyString(),eq(Record.class),any(),anyList())).thenReturn(Collections.emptyList());
        VettingRetrievalClient retrieval=mock(VettingRetrievalClient.class);VettingSemanticReview review=VettingTokenFixture.review(gateway,properties,retrieval);
        List<Chunk> chunks=new ArrayList<>();
        String[] text={"The Tenderer shall use electronic submission in accordance with SCT1.","The Tenderer shall deliver signed paper originals for the same submission.","The maintenance period is twelve months after completion."};
        for(int n=0;n<text.length;n++){
            com.consense.domain.SourceDocument source=new com.consense.domain.SourceDocument();source.setId((long)n+1);source.setFileKey(n==1?"SCT":"NTT");source.setFileName("source-"+n+".docx");source.setReviewRole("tender");
            com.consense.document.DocumentBlock block=new com.consense.document.DocumentBlock();block.setId("body:0:paragraph");block.setLocation("body/0/paragraph");block.setKind("paragraph");block.setText(text[n]);
            source.setTextContent(text[n]);source.setStructuredContentJson(JsonUtils.write(Collections.singletonList(block)));Chunk chunk=VettingCorpus.chunks(Collections.singletonList(source)).get(0);
            chunk.setClauseId(n==1?"SCT1":"NTT"+(n+1));chunk.setClauseHeadingLocation(block.getLocation());chunks.add(chunk);
        }
        java.lang.reflect.Method full=VettingSemanticReview.class.getDeclaredMethod("reviewScanned",String.class,VettingSemanticReview.Result.class,List.class,List.class,String.class,List.class,Runnable.class,VettingReviewProbe.class);full.setAccessible(true);
        VettingSemanticReview.Result result=(VettingSemanticReview.Result)full.invoke(review,"fixture-project",new VettingSemanticReview.Result(),chunks,chunks,"en",null,(Runnable)()->{},VettingReviewProbe.start(properties,null,"fixture"));
        assertEquals(1,result.getActualGatewayCallCount());assertTrue(result.getReviewedChunkIds().containsAll(Arrays.asList(chunks.get(0).getId(),chunks.get(1).getId(),chunks.get(2).getId())));
        com.consense.web.dto.VettingDtos.SemanticTopicVO audit=result.getTopicAudits().get(0);assertEquals("full_scope",audit.getReviewKind());assertEquals("not_completed",audit.getCheckState());
        assertTrue(audit.getPacketAudits().stream().anyMatch(p->p.getSubmittedChunkIds().contains(chunks.get(0).getId())&&p.getSubmittedChunkIds().contains(chunks.get(1).getId())));
        verifyNoInteractions(retrieval);
    }

    @Test void oneConsistentAssessmentCannotCertifyAnEntireScannedPacket() {
        com.consense.web.dto.VettingDtos.SemanticTopicVO call=new com.consense.web.dto.VettingDtos.SemanticTopicVO();
        call.setStatus("completed");call.setReturnedAssessments(1);call.setVerifiedConsistentAssessments(1);
        com.consense.web.dto.VettingDtos.SemanticPacketVO packet=new com.consense.web.dto.VettingDtos.SemanticPacketVO();packet.setKind("full_scope");
        packet.setSubmittedChunkIds(Arrays.asList("assessed-source","unassessed-source"));
        VettingPacketCoverage.callResult(call,packet);assertEquals("not_completed",packet.getCheckState());
        call.setAcceptedFindings(1);VettingPacketCoverage.callResult(call,packet);assertEquals("issue",packet.getCheckState());
    }
    @Test void actualUserMessageUsesTheSameShortReferenceAsTheOutputContract() throws Exception {
        Chunk source=VettingContextBudgetTest.chunk("source-"+String.join("",Collections.nCopies(64,"f")),"SCC1","The paper route applies only where expressly selected; otherwise use the electronic route.",120);
        VettingContextBuilder.Selection selection=new VettingContextBuilder.Selection();selection.setChunks(Collections.singletonList(source));
        java.lang.reflect.Method user=VettingSemanticReview.class.getDeclaredMethod("user",String.class,VettingContextBuilder.Selection.class,List.class);
        user.setAccessible(true);String message=(String)user.invoke(null,"Submission method",selection,null);
        assertTrue(message.contains("\"id\":\"R1\""));assertFalse(message.contains(source.getId()));
        assertTrue(message.contains(source.getContent()));assertTrue(message.contains("otherwise use the electronic route"));
    }

    @Test void semanticProjectionKeepsConditionsAndTableMeaningWithoutMachineLayout() {
        Map<String,Object> raw=new LinkedHashMap<>();
        raw.put("id","source-a");raw.put("content","Electronic submission is required, except where the paper procedure is expressly adopted.");
        raw.put("sourcePosition",Collections.singletonMap("nodePath","/w:document/w:body/w:p[90]"));
        raw.put("paragraphProperties",Collections.singletonMap("layoutEvidence",String.join("",Collections.nCopies(2000,"unused layout "))));
        raw.put("tableGroup",JsonUtils.parse("{\"headers\":[\"Submission method\",\"Copies\"],\"rows\":[[\"Paper\",\"3\"]],\"units\":\"copies\",\"mergedCells\":[[0,0,1,0]],\"bbox\":[0,0,1,1]}"));
        raw.put("conditions",Collections.singletonList("Only when expressly adopted"));raw.put("unknowns",Collections.singletonList("adopted method not confirmed"));
        raw.put("sourceDeclaration",JsonUtils.parse("{\"limits\":{\"wholeSourceSemantics\":\"unknown\",\"legalClosure\":\"unknown\"},\"softwareIdentity\":\"audit-only\"}"));
        JsonNode result=VettingSourceMaterial.semantic(raw,Collections.singletonMap("source-a","R1"));
        assertEquals("R1",result.path("id").asText());assertEquals(raw.get("content"),result.path("content").asText());
        assertEquals("Copies",result.path("tableGroup").path("headers").get(1).asText());
        assertEquals("3",result.path("tableGroup").path("rows").get(0).get(1).asText());
        assertFalse(result.path("tableGroup").path("mergedCells").isEmpty());
        assertEquals("adopted method not confirmed",result.path("unknowns").get(0).asText());
        assertEquals("unknown",result.path("sourceDeclaration").path("limits").path("legalClosure").asText());assertFalse(result.path("sourceDeclaration").has("softwareIdentity"));
        assertFalse(result.toString().contains("nodePath"));assertFalse(result.toString().contains("layoutEvidence"));assertFalse(result.toString().contains("bbox"));
        assertTrue(JsonUtils.write(raw).contains("layoutEvidence"));
    }

    @Test void packetReferencesAreBoundToTheSubmittedSourcesAndDecodeBeforeEvidenceValidation() {
        Chunk a=new Chunk();a.setId("a-"+String.join("",Collections.nCopies(64,"1")));Chunk b=new Chunk();b.setId("b-"+String.join("",Collections.nCopies(64,"2")));
        VettingPacketReferences refs=new VettingPacketReferences(Arrays.asList(a,b));
        assertEquals(Arrays.asList("R1","R2"),new ArrayList<>(refs.wireIds()));
        Quote quote=new Quote();quote.setChunkId("R2");quote.setQuote("A precise source quotation.");quote.setSide("reference");
        Record record=new Record();record.setEvidence(Collections.singletonList(quote));refs.resolve(Collections.singletonList(record));assertEquals(b.getId(),quote.getChunkId());
        quote.setChunkId("R3");assertThrows(IllegalArgumentException.class,()->refs.resolve(Collections.singletonList(record)));
        quote.setChunkId(a.getId());assertThrows(IllegalArgumentException.class,()->refs.resolve(Collections.singletonList(record)));
        assertEquals(a.getId(),refs.bindings().get("R1"));
    }
    @Test void sourceReferenceAliasesNeverRewriteRoleOrLiteralOriginalText(){
        JsonNode raw=JsonUtils.parse("{\"id\":\"tender\",\"role\":\"tender\",\"content\":\"tender\",\"coreIds\":[\"tender\"],\"conditions\":[\"tender\"]}");
        JsonNode selected=VettingSourceMaterial.semantic(raw,Collections.singletonMap("tender","R1"));
        assertEquals("R1",selected.path("id").asText());assertEquals("R1",selected.path("coreIds").get(0).asText());
        assertEquals("tender",selected.path("role").asText());assertEquals("tender",selected.path("content").asText());assertEquals("tender",selected.path("conditions").get(0).asText());
    }
}
