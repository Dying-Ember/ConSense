package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.VettingDtos.SemanticPacketVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Authored provider responses exercise the real retry, schema and quotation gates. */
class VettingEvidenceRepairTest {
    @Test void actualRepairCallerAcceptsTheSameWhitespaceNormalizedQuoteAsTheOrdinaryGate() {
        VettingBusinessShapeRepairTest.Fixture f=new VettingBusinessShapeRepairTest.Fixture();
        String quote=VettingCorpus.normalize(f.source.getContent());
        String originalText="\u00a0"+f.source.getContent().replace(" ","\n  ")+"\u2003";f.source.setContent(originalText);
        assertFalse(originalText.contains(quote));assertTrue(VettingCorpus.evidence(f.source,"source",quote).isLocated());
        JsonNode original=JsonUtils.parse(f.raw("amend",false,quote));((ObjectNode)original.get(0)).remove("evidence");
        VettingSemanticReview.Result result=f.review(f.wire(JsonUtils.write(original),patch(0,"R1",quote)));
        assertEquals(2,result.getActualGatewayCallCount());
        assertEquals(1,result.getCandidates().size(),"Evidence repair must apply the same existing whitespace policy as the ordinary quote gate");
        assertEquals("decoded",packet(result).getAttempts().get(1).getStatus());assertEquals(originalText,f.source.getContent());
    }
    @ParameterizedTest @ValueSource(strings={"semantic_rewrite","cross_chunk"})
    void normalizedRepairStillRejectsChangedWordingAndJoinedChunks(String kind){
        VettingBusinessShapeRepairTest.Fixture f=new VettingBusinessShapeRepairTest.Fixture();String originalText=f.source.getContent();
        List<VettingCorpus.Chunk> sources="cross_chunk".equals(kind)?org.springframework.test.util.ReflectionTestUtils.invokeMethod(f.harness,"corpus"):f.corpus;
        String quote="cross_chunk".equals(kind)?sources.get(0).getContent()+" "+sources.get(1).getContent():originalText.replace("shall","may");
        assertNotEquals(originalText,quote);assertFalse(VettingCorpus.evidence(f.source,"source",quote).isLocated());
        JsonNode original=JsonUtils.parse(f.raw("amend",false,originalText));((ObjectNode)original.get(0)).remove("evidence");
        VettingResponsesTransportTest.Http wire=f.wire(JsonUtils.write(original),patch(0,"R1",quote));
        VettingSemanticReview.Result result=org.springframework.test.util.ReflectionTestUtils.invokeMethod(f.harness,"review",sources,f.props,wire,org.mockito.Mockito.mock(com.consense.ai.LlmClient.class));
        assertEquals(2,result.getActualGatewayCallCount());assertTrue(result.getCandidates().isEmpty());assertEquals(2,packet(result).getAttempts().size());
        assertEquals(originalText,f.source.getContent());if("cross_chunk".equals(kind))assertTrue(VettingBusinessShapeRepairTest.user(packet(result).getAttempts().get(1).getInput()).contains(sources.get(1).getContent()),"Both separately supplied chunks must remain available without permitting joined quotation text");
    }
    @Test void disabledPayloadProbesStillLocateOriginalRelaxedPatchAndMergeFailures(){
        ch.qos.logback.classic.Logger logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(VettingReviewProbe.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> trace=new ch.qos.logback.core.read.ListAppender<>();trace.start();logger.addAppender(trace);
        try{
            for(String kind:Arrays.asList("relaxed","patch","merge")){
                VettingBusinessShapeRepairTest.Fixture f=new VettingBusinessShapeRepairTest.Fixture();JsonNode original=JsonUtils.parse(f.raw("amend",false,f.source.getContent()));ObjectNode first=(ObjectNode)original.get(0);first.remove("evidence");first.put("comment","PRIVATE_MODEL_VALUE_SENTINEL");
                if("relaxed".equals(kind))first.remove("title");
                String repaired="relaxed".equals(kind)?f.raw("amend",false,f.source.getContent()):patch(0,"R1","patch".equals(kind)?"short":"PRIVATE_QUOTE_SENTINEL is not supplied source text.");
                f.review(f.wire(JsonUtils.write(original),repaired));
            }
            String logs=trace.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).reduce("",(a,b)->a+"\n"+b);
            for(String purpose:Arrays.asList("original","relaxed_eligibility","evidence_patch","merged"))assertTrue(logs.contains("purpose="+purpose),purpose);
            assertTrue(logs.contains("schemaPath=$[0].evidence[0].quote"));assertTrue(logs.contains("constraint=minLength"));assertTrue(logs.contains("reason=quote_mismatch"));
            assertTrue(logs.contains("directionIndex=1"));assertTrue(logs.contains("attemptOrdinal=2"));assertTrue(logs.contains("quoteOrdinal=1"));assertTrue(logs.contains("rawMatch=false"));assertTrue(logs.contains("normalizedMatch=false"));
            assertFalse(logs.contains("PRIVATE_MODEL_VALUE_SENTINEL"));assertFalse(logs.contains("PRIVATE_QUOTE_SENTINEL"));
        }finally{logger.detachAppender(trace);}
    }
    @Test void missingOnlyEvidenceUsesTheExistingRetryForAnEvidencePatch() {
        VettingBusinessShapeRepairTest.Fixture f=new VettingBusinessShapeRepairTest.Fixture();
        JsonNode original=JsonUtils.parse(f.raw("amend",false,f.source.getContent()));
        ((ObjectNode)original.get(0)).put("title","Inconsistent terminology: 'Surveyor' and 'Contract Manager' (AUTHORED TEST ONLY)");
        ((ObjectNode)original.get(0)).remove("evidence");
        String rejected=JsonUtils.write(original),patch=patch(0,"R1",f.source.getContent());
        VettingResponsesTransportTest.Http wire=f.wire(rejected,patch);
        VettingSemanticReview.Result result=f.review(wire);
        assertEquals(2,result.getActualGatewayCallCount());
        assertEquals(1,result.getCandidates().size(),"Missing evidence alone must be supplied by the model without regenerating the candidate");
        SemanticPacketVO packet=packet(result);
        assertEquals(rejected,packet.getAttempts().get(0).getRawResponse());
        assertEquals(patch,packet.getAttempts().get(1).getRawResponse(),"Audit must preserve the actual patch, not pretend the model returned a merged record");
        JsonNode first=packet.getAttempts().get(0).getInput(),second=packet.getAttempts().get(1).getInput();
        assertTrue(second.path("schema").path("items").path("properties").has("recordIndex"));
        assertFalse(second.path("schema").path("items").path("properties").has("title"));
        assertEquals(first.path("sourceSnapshotSha256"),second.path("sourceSnapshotSha256"));
        assertEquals(second.path("serializedInputSha256"),packet.getAttempts().get(1).getInputBudget().path("serializedInputSha256"));
        assertNotEquals(first.path("serializedInputSha256"),second.path("serializedInputSha256"));
        assertEquals(4,wire.endpoints.stream().filter(e->e.endsWith("/input_tokens")).count());
        assertEquals(2,wire.endpoints.stream().filter(e->e.endsWith("/responses")).count());
    }
    @ParameterizedTest @ValueSource(strings={"missing","extra","duplicate","non_target","malformed","duplicate_key","trailing","unknown_alias","invented_quote","extra_field","full_record","null_evidence"})
    void invalidPatchesCannotPublishOrConsumeAThirdGeneration(String kind) {
        VettingBusinessShapeRepairTest.Fixture f=new VettingBusinessShapeRepairTest.Fixture();
        JsonNode original=JsonUtils.parse(f.raw("amend",false,f.source.getContent()));
        ((ObjectNode)original.get(0)).remove("evidence");
        String patch=patch(0,"R1",f.source.getContent());
        switch(kind) {
            case "missing":patch="[]";break;
            case "extra":patch="["+JsonUtils.parse(patch).get(0)+","+JsonUtils.parse(patch).get(0)+"]";break;
            case "duplicate":((com.fasterxml.jackson.databind.node.ArrayNode)original).add(original.get(0).deepCopy());patch="["+JsonUtils.parse(patch).get(0)+","+JsonUtils.parse(patch).get(0)+"]";break;
            case "non_target":((com.fasterxml.jackson.databind.node.ArrayNode)original).add(JsonUtils.parse(f.raw("amend",false,f.source.getContent())).get(0));patch=patch(1,"R1",f.source.getContent());break;
            case "malformed":patch="[{";break;
            case "duplicate_key":patch=patch.replace("\"recordIndex\":0","\"recordIndex\":0,\"recordIndex\":0");break;
            case "trailing":patch+=" trailing";break;
            case "unknown_alias":patch=patch(0,"R999",f.source.getContent());break;
            case "invented_quote":patch=patch(0,"R1","This invented quotation is not in the selected source.");break;
            case "extra_field":JsonNode changed=JsonUtils.parse(patch);((ObjectNode)changed.get(0)).put("title","Attempted decision rewrite");patch=JsonUtils.write(changed);break;
            case "full_record":patch=f.raw("amend",false,f.source.getContent());break;
            case "null_evidence":JsonNode absent=JsonUtils.parse(patch);((ObjectNode)absent.get(0)).putNull("evidence");patch=JsonUtils.write(absent);break;
            default:fail(kind);
        }
        VettingResponsesTransportTest.Http wire=f.wire(JsonUtils.write(original),patch);
        VettingSemanticReview.Result result=f.review(wire);
        assertEquals(2,result.getActualGatewayCallCount());assertTrue(result.getCandidates().isEmpty(),kind);
        assertEquals(2,packet(result).getAttempts().size());assertEquals(patch,packet(result).getAttempts().get(1).getRawResponse());
        assertEquals("structured_output_invalid",packet(result).getAttempts().get(1).getStatus());
        assertEquals(2,wire.endpoints.stream().filter(e->e.endsWith("/responses")).count());
    }
    @Test void aPatchForTheMiddleRecordPreservesEveryOriginalDecisionAndExistingEvidence() {
        VettingBusinessShapeRepairTest.Fixture f=new VettingBusinessShapeRepairTest.Fixture();
        com.fasterxml.jackson.databind.node.ArrayNode original=JsonUtils.mapper().createArrayNode();
        for(int i=0;i<3;i++){ObjectNode record=(ObjectNode)JsonUtils.parse(f.raw("amend",false,f.source.getContent())).get(0);record.put("title","AUTHORED retained decision "+i);original.add(record);}
        ((ObjectNode)original.get(1)).remove("evidence");
        VettingSemanticReview.Result result=f.review(f.wire(JsonUtils.write(original),patch(1,"R1",f.source.getContent())));
        assertEquals(3,result.getCandidates().size());
        for(int i=0;i<3;i++)assertEquals("AUTHORED retained decision "+i,result.getCandidates().get(i).getTitle());
        assertEquals("decoded",packet(result).getAttempts().get(1).getStatus());
    }
    @ParameterizedTest @ValueSource(strings={"missing_title","long_title","null_evidence","empty_evidence","unknown_field","business_mismatch","malformed_original"})
    void otherFailuresKeepTheExistingFullRecordRepair(String kind) {
        VettingBusinessShapeRepairTest.Fixture f=new VettingBusinessShapeRepairTest.Fixture();
        JsonNode original=JsonUtils.parse(f.raw("amend",false,f.source.getContent()));ObjectNode first=(ObjectNode)original.get(0);first.remove("evidence");
        switch(kind) {
            case "missing_title":first.remove("title");break;
            case "long_title":first.put("title",String.join("",Collections.nCopies(161,"x")));break;
            case "null_evidence":first.putNull("evidence");break;
            case "empty_evidence":first.putArray("evidence");break;
            case "unknown_field":first.put("authority","invented");break;
            case "business_mismatch":first.putArray("pendingFacts").add("AUTHORED unconfirmed fact");break;
            case "malformed_original":break;
            default:fail(kind);
        }
        String raw=JsonUtils.write(original)+("malformed_original".equals(kind)?" trailing":"");
        VettingSemanticReview.Result result=f.review(f.wire(raw,f.raw("amend",false,f.source.getContent())));
        assertEquals(2,result.getActualGatewayCallCount());assertEquals(1,result.getCandidates().size());
        assertEquals(packet(result).getAttempts().get(0).getInput().path("schema"),packet(result).getAttempts().get(1).getInput().path("schema"));
    }
    @Test void missingEvidenceIsNeverAutoAttachedWhenTheModelRepeatsItsUnsupportedRecord() {
        VettingBusinessShapeRepairTest.Fixture f=new VettingBusinessShapeRepairTest.Fixture();JsonNode original=JsonUtils.parse(f.raw("amend",false,f.source.getContent()));
        ((ObjectNode)original.get(0)).remove("evidence");String raw=JsonUtils.write(original);
        VettingSemanticReview.Result result=f.review(f.wire(raw,raw));
        assertEquals(2,result.getActualGatewayCallCount());assertTrue(result.getCandidates().isEmpty());
        assertEquals(raw,packet(result).getAttempts().get(0).getRawResponse());assertEquals(raw,packet(result).getAttempts().get(1).getRawResponse());
    }
    @Test void directionTwoLanguageShapeCanBeDecodedButCannotBypassTheOriginalAmendmentGate() {
        VettingBusinessShapeRepairTest.Fixture f=new VettingBusinessShapeRepairTest.Fixture();JsonNode original=JsonUtils.parse(f.raw("amend",false,f.source.getContent()));
        ((ObjectNode)original.get(0)).put("type","language").put("title","Inconsistent terminology: 'Surveyor' used where 'Contract Manager' is defined (AUTHORED TEST ONLY)");
        ((ObjectNode)original.get(0)).remove("evidence");
        VettingSemanticReview.Result result=f.review(f.wire(JsonUtils.write(original),patch(0,"R1",f.source.getContent())));
        assertEquals(2,result.getActualGatewayCallCount());assertEquals("decoded",packet(result).getAttempts().get(1).getStatus());
        assertTrue(result.getCandidates().isEmpty(),"A valid evidence patch does not establish amendment authority or semantic correctness");
    }
    @Test void unknownOrOverBudgetEvidenceRepairCannotReserveASecondGeneration() {
        for(boolean unknown:Arrays.asList(false,true)) {
            VettingBusinessShapeRepairTest.Fixture f=new VettingBusinessShapeRepairTest.Fixture();JsonNode original=JsonUtils.parse(f.raw("amend",false,f.source.getContent()));
            ((ObjectNode)original.get(0)).remove("evidence");
            VettingResponsesTransportTest.Http wire=f.wire(JsonUtils.write(original),patch(0,"R1",f.source.getContent()));
            wire.counterHook=()->{if(wire.bodies.get(wire.bodies.size()-1).contains("only evidence was omitted")){if(unknown)wire.counterOverride="{}";else wire.count=262144;}};
            VettingSemanticReview.Result result=f.review(wire);assertEquals(1,result.getActualGatewayCallCount());assertEquals(1,packet(result).getAttempts().size());assertTrue(result.getCandidates().isEmpty());
            assertEquals(1,wire.endpoints.stream().filter(e->e.endsWith("/responses")).count());
        }
    }
    static String patch(int index,String alias,String quote){return JsonUtils.write(Collections.singletonList(Map.of("recordIndex",index,"evidence",Collections.singletonList(Map.of("chunkId",alias,"side","source","quote",quote)))));}
    static SemanticPacketVO packet(VettingSemanticReview.Result result){return result.getTopicAudits().get(0).getPacketAudits().get(0);}
}
