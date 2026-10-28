package com.consense.service.vetting;

import com.consense.ai.LlmClient;
import com.consense.ai.AiGateway;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.web.dto.VettingDtos.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Authored mixed replies use the actual decode, evidence gate and durable receiving seams. */
class VettingMixedAttributionTest {
    @Test void insufficientContextDoesNotCountAsCompletedSemanticCoverage(){
        VettingSourceAttributionRepairTest.Fixture f=fixture();ObjectNode pending=record(f,f.a,false);
        pending.put("assessment","insufficient_context");pending.put("subDimension","");pending.put("factBasis","");
        VettingSemanticReview.Result result=f.review(JsonUtils.write(Collections.singletonList(pending)));
        assertEquals(1,packet(result).getVerifiedInsufficientContextAssessments());
        assertTrue(result.getReviewedChunkIds().isEmpty(),"A need for more evidence is saved without completing the quoted source review");
    }
    @Test void aValidAssessmentDoesNotCompleteUnassessedSubmittedText(){
        VettingSourceAttributionRepairTest.Fixture f=fixture();
        VettingSemanticReview.Result result=f.review(JsonUtils.write(Collections.singletonList(record(f,f.a,false))));
        assertEquals(Collections.singleton(f.a.getId()),result.getReviewedChunkIds());
    }
    @Test void mixedReplyRetainsTheIndependentValidRecordAndOriginalRejectedIndexWithoutAnotherGeneration(){
        VettingSourceAttributionRepairTest.Fixture f=fixture();String raw=mixed(f,false);
        VettingSemanticReview.Result result=f.review(raw);SemanticPacketVO packet=packet(result);
        assertNull(result.getModelFailure());assertEquals(1,result.getActualGatewayCallCount());assertEquals(1,result.getCandidates().size());
        assertEquals("completed_with_rejections",packet.getStatus());assertEquals(1,packet.getRejectedRecords());
        assertEquals(Collections.singleton(f.a.getId()),result.getReviewedChunkIds());assertFalse(packet.isSemanticScopeVerified());
        assertEquals(f.wire(raw),packet.getAttempts().get(0).getRawResponse());
        assertEquals(VettingCorpus.hash(f.wire(raw)),packet.getAttempts().get(0).getRawResponseSha256());
        assertTrue(packet.getAttempts().get(0).getValidationReason().contains("\"recordIndex\":1"));
        assertTrue(packet.getAttempts().get(0).getValidationReason().contains("unsupported_source_clause_attribution"));
        assertFalse(result.getCandidates().get(0).getComment().contains("Clause 99"));
    }

    @Test void oneInvalidBusinessRecordDoesNotRegenerateOrDiscardIndependentValidRecords(){
        VettingSourceAttributionRepairTest.Fixture f=fixture();ObjectNode invalid=record(f,f.b,false);
        invalid.putArray("pendingFacts").add("The intended application remains unconfirmed.");invalid.put("suggestedAction","amend");
        String raw=JsonUtils.write(Arrays.asList(invalid,record(f,f.a,false)));
        VettingSemanticReview.Result result=f.review(raw,raw);SemanticPacketVO packet=packet(result);
        assertEquals(1,result.getActualGatewayCallCount());assertEquals(1,result.getCandidates().size());
        assertEquals("completed_with_rejections",packet.getStatus());assertEquals(1,packet.getRejectedRecords());
        assertEquals(Collections.singleton(f.a.getId()),result.getReviewedChunkIds());assertFalse(packet.isSemanticScopeVerified());
        assertTrue(packet.getAttempts().get(0).getValidationReason().contains("pending_facts_require_review"));
        assertEquals(f.wire(raw),packet.getAttempts().get(0).getRawResponse());
        assertEquals("partial",result.getTopicAudits().get(0).getAggregateReviewStatus());
        assertTrue(result.getTopicAudits().get(0).getSourceRequests().stream().allMatch(r->r.getReviewExecutionStatus().startsWith("partial")));
    }

    @Test void aSavedMixedBusinessReplyRestoresItsValidRecordAndPendingRejection(){
        VettingSourceAttributionRepairTest.Fixture f=fixture();ObjectNode invalid=record(f,f.b,false);
        invalid.putArray("pendingFacts").add("The intended application remains unconfirmed.");invalid.put("suggestedAction","amend");
        String raw=JsonUtils.write(Arrays.asList(invalid,record(f,f.a,false)));VettingRepairEmptyWarningTest.Store store=new VettingRepairEmptyWarningTest.Store();
        VettingSemanticReview.Result first=stored(f,store,raw),restored=stored(f,store);
        assertEquals(1,first.getCandidates().size());assertEquals(0,restored.getActualGatewayCallCount());
        assertEquals(first.getReviewedChunkIds(),restored.getReviewedChunkIds());assertEquals(1,packet(restored).getRejectedRecords());
        assertEquals(packet(first).getAttempts().get(0).getRawResponse(),packet(restored).getAttempts().get(0).getRawResponse());
        assertTrue(packet(restored).getAttempts().get(0).getValidationReason().contains("pending_facts_require_review"));
    }

    @Test void everyUnsupportedAttributionStillUsesOnlyTheExistingRepairAndPublishesNothing(){
        VettingSourceAttributionRepairTest.Fixture f=fixture();String raw=JsonUtils.write(Arrays.asList(record(f,f.a,true),record(f,f.b,true)));
        VettingSemanticReview.Result result=f.review(raw,raw);SemanticPacketVO packet=packet(result);
        assertEquals(2,result.getActualGatewayCallCount());assertTrue(result.getCandidates().isEmpty());assertTrue(result.getReviewedChunkIds().isEmpty());
        assertEquals("failed",packet.getStatus());assertEquals("source_attribution_invalid",packet.getFailureKind());assertNull(result.getModelFailure());
    }

    @Test void malformedRecordSchemaStillRejectsTheEntireReply(){
        VettingSourceAttributionRepairTest.Fixture f=fixture();ObjectNode invalid=record(f,f.b,true);invalid.remove("title");
        String raw=JsonUtils.write(Arrays.asList(record(f,f.a,false),invalid));VettingSemanticReview.Result result=f.review(raw,raw);
        assertTrue(result.getCandidates().isEmpty());assertTrue(result.getReviewedChunkIds().isEmpty());assertEquals(2,result.getActualGatewayCallCount());
        assertEquals("failed",packet(result).getStatus());assertEquals("structured_output_invalid",packet(result).getFailureKind());
    }

    @Test void aPartiallyRejectedPacketCannotMarkAllItsSourceRequestsDecoded(){
        VettingSourceAttributionRepairTest.Fixture f=fixture();VettingSemanticReview.Result result=f.review(mixed(f,false));
        SemanticTopicVO audit=result.getTopicAudits().get(0);assertTrue(audit.getSourceRequestCount()>0);
        assertEquals(audit.getSourceRequestCount(),audit.getPendingSourceRequestCount());
        assertTrue(audit.getSourceRequests().stream().allMatch(r->"partial_source_attribution_rejection".equals(r.getReviewExecutionStatus())));
        assertEquals("partial",audit.getAggregateReviewStatus());
    }

    @Test void savedMixedReplyRestoresOnlyItsAcceptedEvidenceCoverageAndKeepsTheRawRejection(){
        VettingSourceAttributionRepairTest.Fixture f=fixture();String raw=mixed(f,false);VettingRepairEmptyWarningTest.Store store=new VettingRepairEmptyWarningTest.Store();
        VettingSemanticReview.Result first=stored(f,store,raw);assertEquals("SAVED",store.row.get().getStatus());
        assertEquals(Collections.singleton(f.a.getId()),store.saved().getReviewedChunkIds());String saved=store.row.get().getResultJson();
        VettingSemanticReview.Result restored=stored(f,store);assertEquals(0,restored.getActualGatewayCallCount());assertEquals(1,restored.getCandidates().size());
        assertEquals(first.getReviewedChunkIds(),restored.getReviewedChunkIds());assertEquals(saved,store.row.get().getResultJson());
        assertEquals(packet(first).getAttempts().get(0).getRawResponse(),packet(restored).getAttempts().get(0).getRawResponse());
        assertEquals(packet(first).getAttempts().get(0).getValidationReason(),packet(restored).getAttempts().get(0).getValidationReason());
    }

    @Test void theIndependentRecordStillCannotPublishAnInventedQuoteOrReceiveCoverage(){
        VettingSourceAttributionRepairTest.Fixture f=fixture();VettingSemanticReview.Result result=f.review(mixed(f,true));
        assertTrue(result.getCandidates().isEmpty());assertTrue(result.getReviewedChunkIds().isEmpty());
        assertEquals(2,packet(result).getRejectedRecords());assertEquals("completed_with_rejections",packet(result).getStatus());
        assertEquals(1,result.getActualGatewayCallCount());assertTrue(packet(result).getVerifiedAssessments().isEmpty());
    }

    private static VettingSourceAttributionRepairTest.Fixture fixture(){
        VettingSourceAttributionRepairTest.Fixture f=new VettingSourceAttributionRepairTest.Fixture();
        f.corpus.add(f.chunk("8","The Contractor shall retain the separately supplied site register for inspection."));return f;
    }
    private static ObjectNode record(VettingSourceAttributionRepairTest.Fixture f,Chunk source,boolean bad){
        ObjectNode record=f.record(false);record.put("type","requirement");record.put("title",bad?"Authored unsupported source attribution":"Authored supported requirement review");
        record.put("comment",bad?"Clause 99 requires a separate tender delivery.":"The quoted tender passage requires a specific paper-copy quantity; confirm its intended application.");
        record.put("factBasis",source.getContent());record.putArray("evidence").addObject().put("chunkId",source.getId()).put("side","source").put("quote",source.getContent());return record;
    }
    private static String mixed(VettingSourceAttributionRepairTest.Fixture f,boolean fabricated){
        ObjectNode good=record(f,f.a,false);if(fabricated)((ObjectNode)good.path("evidence").get(0)).put("quote","This invented quotation is absent from every authored source.");
        return JsonUtils.write(Arrays.asList(good,record(f,f.b,true)));
    }
    private static VettingSemanticReview.Result stored(VettingSourceAttributionRepairTest.Fixture f,VettingRepairEmptyWarningTest.Store store,String... answers){
        LlmClient provider=mock(LlmClient.class);AtomicInteger count=new AtomicInteger();
        when(provider.available()).thenReturn(true);when(provider.chatModel()).thenReturn("AUTHORED mixed-record model");
        when(provider.chatStructured(anyList(),any())).thenAnswer(call->{int index=count.getAndIncrement();assertTrue(index<answers.length,"No unexpected generation");
            f.sourceUser=call.<List<LlmClient.ChatTurn>>getArgument(0).get(1).getContent();return f.wire(answers[index]);});
        ConsenseProperties props=new ConsenseProperties();props.getLlm().setChatModel("AUTHORED mixed-record model");props.getLlm().setStructuredMaxTokens(100);
        props.getVetting().setSemanticTopics(1);props.getVetting().setProjectReferenceComparisons(0);
        VettingRetrievalClient retrieval=mock(VettingRetrievalClient.class);when(retrieval.retrieve(anyString(),anyString(),eq("tender"),anyList())).thenReturn(f.corpus);
        VettingSemanticReview service=VettingTokenFixture.review(new AiGateway(provider),props,retrieval);VettingSemanticReview.Result result;
        try(VettingSemanticReview.CheckpointScope ignored=service.bindCheckpoint(store.checkpoint.session("authored","owner"))){result=service.review("AUTHORED stored mixed reply",f.corpus,"en",(n,text)->{});}
        assertEquals(answers.length,count.get());return result;
    }
    private static SemanticPacketVO packet(VettingSemanticReview.Result result){return result.getTopicAudits().get(0).getPacketAudits().get(0);}
}
