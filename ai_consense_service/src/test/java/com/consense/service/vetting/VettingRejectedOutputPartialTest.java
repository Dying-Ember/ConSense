package com.consense.service.vetting;

import com.consense.ai.*;
import com.consense.common.BizException;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.VettingSemanticReview.Record;
import com.consense.web.dto.VettingDtos.*;
import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Authored model responses exercise the actual rejection/repair receiving seam. */
class VettingRejectedOutputPartialTest {
    @ParameterizedTest @ValueSource(strings={"[]}","[{}]"})
    void exhaustedReceivedJsonOrSchemaRejectionsRemainFailedAndUnreviewedWithoutFailingTheRun(String raw){
        VettingBusinessShapeRepairTest.Fixture f=new VettingBusinessShapeRepairTest.Fixture();
        VettingSemanticReview.Result result=f.review(f.wire(raw,raw));SemanticPacketVO packet=packet(result);
        assertNull(result.getModelFailure());assertTrue(result.getCandidates().isEmpty());assertTrue(result.getReviewedChunkIds().isEmpty());
        assertEquals(2,result.getActualGatewayCallCount());assertEquals(2,packet.getAttempts().size());assertEquals("failed",packet.getStatus());assertEquals("structured_output_invalid",packet.getFailureKind());
        assertEquals("not_completed",packet.getCheckState());assertFalse(packet.isSemanticScopeVerified());assertFalse(result.getWarnings().isEmpty());
        for(SemanticAttemptVO attempt:packet.getAttempts()){assertEquals("structured_output_invalid",attempt.getStatus());assertNotNull(attempt.getRawResponse());assertNotNull(attempt.getRawResponseSha256());}
    }

    @Test void exhaustedReceivedBusinessShapeRejectionsRemainFailedAndCannotPublish(){
        VettingBusinessShapeRepairTest.Fixture f=new VettingBusinessShapeRepairTest.Fixture();String raw=f.raw("amend",true,f.source.getContent());
        VettingSemanticReview.Result result=f.review(f.wire(raw,raw));SemanticPacketVO packet=packet(result);
        assertNull(result.getModelFailure());assertTrue(result.getCandidates().isEmpty());assertTrue(result.getReviewedChunkIds().isEmpty());
        assertEquals(2,result.getActualGatewayCallCount());assertEquals("failed",packet.getStatus());assertEquals("business_shape_invalid",packet.getFailureKind());
        for(SemanticAttemptVO attempt:packet.getAttempts()){assertEquals(raw,attempt.getRawResponse());assertTrue(attempt.getValidationReason().startsWith("BUSINESS_SHAPE_MISMATCH:"));}
    }

    @Test void aLaterRejectedAttributionDoesNotEraseAnEarlierFatalWireFailure(){
        VettingSourceAttributionRepairTest.Fixture f=new VettingSourceAttributionRepairTest.Fixture();f.topics=2;String raw=f.conflict(true);
        VettingSemanticReview.Result result=f.review(null,raw,raw);
        assertNotNull(result.getModelFailure());assertEquals(3,result.getActualGatewayCallCount());assertEquals(2,result.getTopicAudits().size());
        SemanticPacketVO first=result.getTopicAudits().get(0).getPacketAudits().get(0),second=result.getTopicAudits().get(1).getPacketAudits().get(0);
        assertEquals("review_processing_failure",first.getFailureKind());assertEquals("source_attribution_invalid",second.getFailureKind());
        assertTrue(result.getModelFailure().contains("AUTHORED transport failure"));
        assertTrue(result.getCandidates().isEmpty());assertTrue(result.getReviewedChunkIds().isEmpty());assertEquals(2,second.getAttempts().size());
    }

    @ParameterizedTest @ValueSource(strings={"INVALID_SCHEMA","UNSUPPORTED_SCHEMA","ELEMENT_MAPPING_FAILED"})
    void applicationSchemaAndMappingFailuresStayFatal(String reason){
        VettingSemanticReview.Result result=exceptionResult(StructuredOutputValidationException.Reason.valueOf(reason),true);
        assertNotNull(result.getModelFailure());assertEquals(1,result.getActualGatewayCallCount());assertTrue(result.getCandidates().isEmpty());
    }

    @Test void twoUnrecordedValidationExceptionsCannotBecomeAPartialResult(){
        VettingSemanticReview.Result result=exceptionResult(StructuredOutputValidationException.Reason.INVALID_JSON,false);
        assertNotNull(result.getModelFailure());assertEquals(2,result.getActualGatewayCallCount());assertTrue(result.getReviewedChunkIds().isEmpty());
    }

    @Test void anArbitraryExceptionMessageCannotMasqueradeAsAContentRejection(){
        VettingSourceAttributionRepairTest.Fixture f=new VettingSourceAttributionRepairTest.Fixture();AiGateway ai=model();
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(Record.class),any(),anyList())).thenThrow(new BizException("Model response source attribution invalid: AUTHORED spoof"));
        VettingSemanticReview.Result result=review(ai,f);assertNotNull(result.getModelFailure());assertEquals(1,result.getActualGatewayCallCount());
    }

    @ParameterizedTest @ValueSource(strings={"business","attribution","json","schema"})
    void sameRunRecordedOutputRejectionReplaysAsPartialWithoutAThirdAttempt(String kind){
        VettingBusinessShapeRepairTest.Fixture f=new VettingBusinessShapeRepairTest.Fixture();VettingRepairEmptyWarningTest.Store store=new VettingRepairEmptyWarningTest.Store();String raw=rejected(f,kind);
        VettingSemanticReview.Result first=store.review(f,raw,raw);assertNull(first.getModelFailure());SemanticPacketVO original=packet(first);
        for(int repetition=0;repetition<2;repetition++){
            VettingSemanticReview.Result replay=store.review(f);SemanticPacketVO restored=packet(replay);
            assertNull(replay.getModelFailure());assertEquals(0,replay.getActualGatewayCallCount());assertTrue(replay.getCandidates().isEmpty());assertTrue(replay.getReviewedChunkIds().isEmpty());
            assertEquals("failed",restored.getStatus());assertEquals(original.getFailureKind(),restored.getFailureKind());assertEquals("not_completed",restored.getCheckState());
            assertEquals(original.getSourceSnapshotSha256(),restored.getSourceSnapshotSha256());assertEquals(original.getPromptEnvelopeSha256(),restored.getPromptEnvelopeSha256());
            assertEquals(original.getSubmittedChunkIds(),restored.getSubmittedChunkIds());assertEquals(2,restored.getAttempts().size());assertFalse(restored.isSemanticScopeVerified());
            for(int index=0;index<2;index++){assertEquals(original.getAttempts().get(index).getRawResponse(),restored.getAttempts().get(index).getRawResponse());assertEquals(original.getAttempts().get(index).getRawResponseSha256(),restored.getAttempts().get(index).getRawResponseSha256());}
            assertEquals("FAILED",store.row.get().getStatus());
        }
    }

    @ParameterizedTest @ValueSource(strings={"wrong_hash","missing_raw","wrong_status","wrong_ordinal","unknown_reason","application_schema_reason"})
    void corruptOrUnprovenRecordedOutputFailureCannotBecomePartial(String kind){
        VettingBusinessShapeRepairTest.Fixture f=new VettingBusinessShapeRepairTest.Fixture();VettingRepairEmptyWarningTest.Store store=new VettingRepairEmptyWarningTest.Store();String raw=rejected(f,"business");store.review(f,raw,raw);
        VettingCheckpointService.Saved saved=store.saved();SemanticAttemptVO first=saved.getPacket().getAttempts().get(0),last=saved.getPacket().getAttempts().get(1);
        if("wrong_hash".equals(kind))first.setRawResponseSha256(VettingCorpus.hash("different"));
        else if("missing_raw".equals(kind))first.setRawResponse(null);
        else if("wrong_status".equals(kind))first.setStatus("decoded");
        else if("wrong_ordinal".equals(kind))first.setOrdinal(2);
        else if("unknown_reason".equals(kind))first.setValidationReason("Model response source attribution invalid: AUTHORED spoof");
        else last.setValidationReason("INVALID_SCHEMA");
        store.write(saved,"FAILED");VettingSemanticReview.Result result=store.review(f);assertNotNull(result.getModelFailure());assertEquals(0,result.getActualGatewayCallCount());assertTrue(result.getCandidates().isEmpty());assertTrue(result.getReviewedChunkIds().isEmpty());
    }

    @ParameterizedTest @ValueSource(strings={"source_snapshot","model_envelope","schema_envelope"})
    void checkpointCompatibilityStillRejectsChangedSourceOrModelSchemaEnvelope(String changed){
        VettingBusinessShapeRepairTest.Fixture f=new VettingBusinessShapeRepairTest.Fixture();VettingRepairEmptyWarningTest.Store store=new VettingRepairEmptyWarningTest.Store();String raw=rejected(f,"business");store.review(f,raw,raw);
        String before=store.row.get().getResultJson();SemanticPacketVO next=JsonUtils.read(JsonUtils.write(store.saved().getPacket()),SemanticPacketVO.class);
        if("source_snapshot".equals(changed))next.setSourceSnapshotSha256(VettingCorpus.hash(changed));else next.setPromptEnvelopeSha256(VettingCorpus.hash(changed));
        assertThrows(BizException.class,()->store.checkpoint.session("authored","owner").loadOrBegin(next));assertEquals(before,store.row.get().getResultJson());
    }

    private String rejected(VettingBusinessShapeRepairTest.Fixture f,String kind){
        if("json".equals(kind))return "[]}";if("schema".equals(kind))return "[{}]";
        String raw=f.raw("business".equals(kind)?"amend":"review","business".equals(kind),f.source.getContent());
        if("attribution".equals(kind)){com.fasterxml.jackson.databind.JsonNode parsed=JsonUtils.parse(raw);((ObjectNode)parsed.get(0)).put("comment","Clause 99 requires the recorded source obligation.");return JsonUtils.write(parsed);}
        return raw;
    }

    private VettingSemanticReview.Result exceptionResult(StructuredOutputValidationException.Reason reason,boolean recorded){
        VettingSourceAttributionRepairTest.Fixture f=new VettingSourceAttributionRepairTest.Fixture();AiGateway ai=model();
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(Record.class),any(),anyList())).thenAnswer(call->{if(recorded)call.<List<String>>getArgument(4).add("[]}");throw new StructuredOutputValidationException(reason);});
        return review(ai,f);
    }
    private AiGateway model(){AiGateway ai=mock(AiGateway.class);when(ai.available()).thenReturn(true);when(ai.chatModel()).thenReturn("AUTHORED model only");return ai;}
    private VettingSemanticReview.Result review(AiGateway ai,VettingSourceAttributionRepairTest.Fixture f){
        ConsenseProperties props=new ConsenseProperties();props.getVetting().setSemanticTopics(1);props.getVetting().setProjectReferenceComparisons(0);
        VettingRetrievalClient retrieval=mock(VettingRetrievalClient.class);when(retrieval.retrieve(anyString(),anyString(),eq("tender"),anyList())).thenReturn(f.corpus);
        return VettingTokenFixture.review(ai,props,retrieval).review("AUTHORED partial output rejection",f.corpus,"en",(number,text)->{});
    }
    private static SemanticPacketVO packet(VettingSemanticReview.Result result){return result.getTopicAudits().get(0).getPacketAudits().get(0);}
}
