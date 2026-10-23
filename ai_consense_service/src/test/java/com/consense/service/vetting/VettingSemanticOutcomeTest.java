package com.consense.service.vetting;

import com.consense.ai.*;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingSemanticReview.Result;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VettingSemanticOutcomeTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"subDimension,160","factBasis,1200","pendingFacts,600","reason,1200"})
    void businessMetadataLimitsCountUnicodeCodePointsLikeTheProductionSchema(String field,int limit) {
        Chunk source=VettingContextBudgetTest.chunk("source","PRE.B6.590","The contractor shall retain the maintenance record for this contract.",120);
        source.setOriginalSourceSha256(VettingCorpus.hash("AUTHORED original fixture bytes"));
        VettingSemanticReview.Record record=reference(source,source);record.setType("requirement");
        VettingSemanticReview.ChecklistAssessment check=new VettingSemanticReview.ChecklistAssessment();check.setItemId("APP-I-026");check.setStatus("not_fulfilled");
        for(int extra=0;extra<=1;extra++) {
            String value=String.join("",Collections.nCopies(limit+extra,"\uD83D\uDE00"));
            switch(field) {
                case "subDimension":record.setSubDimension(value);break;
                case "factBasis":record.setFactBasis(value);break;
                case "pendingFacts":record.setPendingFacts(Collections.singletonList(value));break;
                default:check.setReason(value);record.setChecklistAssessments(Collections.singletonList(check));
            }
            Result result=review(response(record,source),properties(),Collections.singletonList(source),Collections.singletonList(source));
            assertEquals(extra==0?1:0,result.getCandidates().size(),field+" at "+(limit+extra)+" Unicode code points");
            if("reason".equals(field))assertEquals(extra==0?1:0,result.getTopicAudits().get(0).getPacketAudits().get(0).getChecklistAssessments().size());
            if(extra==1)assertEquals("failed",result.getTopicAudits().get(0).getStatus());
        }
    }

    @Test void issueBusinessDetailsRemainTypedAndUnknownFactsOnlyPermitReview() {
        Chunk source=VettingContextBudgetTest.chunk("source","XYZ1","The contractor shall provide the named maintenance staff throughout the contract.",120);
        VettingSemanticReview.Record record=reference(source,source);record.setType("requirement");record.setSubDimension("Obligation owner unclear");
        record.setFactBasis("The supplied tender names maintenance staff without a confirmed provider.");record.setPendingFacts(Collections.singletonList("Confirm the responsible specialist subcontractor."));record.setSuggestedAction("review");
        Result result=review(response(record,source),properties(),Collections.singletonList(source),Collections.singletonList(source));
        assertEquals(1,result.getCandidates().size());assertEquals(record.getPendingFacts(),result.getCandidates().get(0).getBusinessDetails().getPendingFacts());
        assertEquals(record.getSubDimension(),result.getCandidates().get(0).getBusinessDetails().getSubDimension());assertEquals(record.getFactBasis(),result.getCandidates().get(0).getBusinessDetails().getFactBasis());
        record.setSuggestedAction("delete");Result invalid=review(response(record,source),properties(),Collections.singletonList(source),Collections.singletonList(source));
        assertTrue(invalid.getCandidates().isEmpty());assertEquals("failed",invalid.getTopicAudits().get(0).getStatus());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"subDimension","factBasis","pendingFacts","suggestedAction"})
    void missingRequiredIssueMetadataCannotCreateAFormalFinding(String field) {
        Chunk source=VettingContextBudgetTest.chunk("source","XYZ1","The contractor shall provide the named maintenance staff throughout the contract.",120);
        VettingSemanticReview.Record record=reference(source,source);record.setType("gap");
        switch(field){case "subDimension":record.setSubDimension(" ");break;case "factBasis":record.setFactBasis(null);break;case "pendingFacts":record.setPendingFacts(null);break;default:record.setSuggestedAction("ignore");}
        Result invalid=review(response(record,source),properties(),Collections.singletonList(source),Collections.singletonList(source));
        assertTrue(invalid.getCandidates().isEmpty());assertEquals("failed",invalid.getTopicAudits().get(0).getStatus());
    }

    @Test void checklistProducerKeepsOnlyLocatedRecordEvidenceAndBindsNormalChunkIdentity() {
        Chunk source=VettingContextBudgetTest.chunk("source","PRE.B6.590","The contractor shall retain the maintenance record for this contract.",120);
        source.setOriginalSourceSha256(VettingCorpus.hash("AUTHORED original fixture bytes"));
        VettingSemanticReview.Record record=reference(source,source);record.setType("requirement");
        VettingSemanticReview.ChecklistAssessment check=new VettingSemanticReview.ChecklistAssessment();check.setItemId("APP-I-026");check.setStatus("not_fulfilled");check.setReason("Authored fixture requires confirmation of the named requirement.");record.setChecklistAssessments(Collections.singletonList(check));
        Result result=review(response(record,source),properties(),Collections.singletonList(source),Collections.singletonList(source));
        com.consense.web.dto.VettingDtos.SemanticPacketVO packet=result.getTopicAudits().get(0).getPacketAudits().get(0);
        assertEquals(1,packet.getChecklistAssessments().size());com.consense.web.dto.VettingDtos.ChecklistAssessmentVO saved=packet.getChecklistAssessments().get(0);
        assertEquals(Arrays.asList("source","source"),saved.getEvidenceChunkIds());assertEquals(packet.getPacketId(),saved.getPacketId());assertEquals(packet.getSourceSnapshotSha256(),saved.getSourceSnapshotSha256());
        assertEquals(VettingPromptPolicy.sha256(),saved.getPolicySha256());assertEquals(source.getOriginalSourceSha256(),saved.getEvidence().get(0).getOriginalSourceSha256());
        assertEquals("REQ",saved.getIssueTypeCode());
        assertEquals(source.getClauseId(),saved.getEvidence().get(0).getClauseId());assertEquals(source.getAnchor(),saved.getEvidence().get(0).getAnchor());
        assertEquals(Collections.singletonList("APP-I-026"),result.getCandidates().get(0).getBusinessDetails().getChecklistItemIds());
        com.consense.web.dto.VettingDtos.SemanticPacketVO restored=new com.consense.web.dto.VettingDtos.SemanticPacketVO();VettingPacketCoverage.restore(packet,new com.consense.web.dto.VettingDtos.SemanticTopicVO(),restored);
        assertEquals(JsonUtils.write(packet.getChecklistAssessments()),JsonUtils.write(restored.getChecklistAssessments()));
        record.getEvidence().get(0).setQuote("This quotation is not present in the supplied tender.");
        Result rejected=review(response(record,source),properties(),Collections.singletonList(source),Collections.singletonList(source));
        assertTrue(rejected.getTopicAudits().get(0).getPacketAudits().get(0).getChecklistAssessments().isEmpty());assertTrue(rejected.getCandidates().isEmpty());
    }

    @Test void checklistPassedAndNoOpinionStatesAreNeverInferredFromAnIssueOrAnEmptyResult() {
        Chunk source=VettingContextBudgetTest.chunk("source","PRE.B6.590","The contractor shall retain the maintenance record for this contract.",120);
        VettingSemanticReview.Record record=reference(source,source);record.setType("risk");
        VettingSemanticReview.ChecklistAssessment check=new VettingSemanticReview.ChecklistAssessment();check.setItemId("APP-I-026");check.setStatus("fulfilled");check.setReason("Authored fixture statement.");record.setChecklistAssessments(Collections.singletonList(check));
        Result mismatch=review(response(record,source),properties(),Collections.singletonList(source),Collections.singletonList(source));assertTrue(mismatch.getCandidates().isEmpty());assertEquals("failed",mismatch.getTopicAudits().get(0).getStatus());
        record.setAssessment("consistent");Result explicit=review(response(record,source),properties(),Collections.singletonList(source),Collections.singletonList(source));
        assertTrue(explicit.getCandidates().isEmpty());assertEquals(1,explicit.getTopicAudits().get(0).getPacketAudits().get(0).getChecklistAssessments().size());
        check.setItemId("APP-I-068");Result annex=review(response(record,source),properties(),Collections.singletonList(source),Collections.singletonList(source));assertTrue(annex.getTopicAudits().get(0).getPacketAudits().get(0).getChecklistAssessments().isEmpty());
    }

    @Test void unresolvedApplicabilityKeepsTheReviewFindingWithoutIssuingAFailedCountercheck() {
        Chunk source=VettingContextBudgetTest.chunk("source","PRE.B6.590","The contractor shall retain the maintenance record for this contract.",120);
        VettingSemanticReview.Record record=reference(source,source);record.setType("requirement");record.setPendingFacts(Collections.singletonList("Confirm whether this requirement applies to this project."));
        VettingSemanticReview.ChecklistAssessment check=new VettingSemanticReview.ChecklistAssessment();check.setItemId("APP-I-026");check.setStatus("not_fulfilled");check.setReason("Applicability still needs confirmation.");record.setChecklistAssessments(Collections.singletonList(check));
        Result result=review(response(record,source),properties(),Collections.singletonList(source),Collections.singletonList(source));
        assertEquals(1,result.getCandidates().size());assertEquals("review",result.getCandidates().get(0).getBusinessDetails().getSuggestedAction());
        assertTrue(result.getTopicAudits().get(0).getPacketAudits().get(0).getChecklistAssessments().isEmpty(),"Unconfirmed applicability cannot produce a cross.");
        assertTrue(result.getCandidates().get(0).getBusinessDetails().getChecklistItemIds().isEmpty());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"gap","substantive","requirement"})
    void extendedClassesSurviveTheLocatedEvidenceGateWithoutBecomingRisk(String type) {
        Chunk source=VettingContextBudgetTest.chunk("source","XYZ1","The contractor shall provide the named maintenance staff throughout the contract.",120);
        VettingSemanticReview.Record record=reference(source,source);record.setType(type);
        Result result=review(response(record,source),properties(),Collections.singletonList(source),Collections.singletonList(source));
        assertEquals(1,result.getCandidates().size());assertEquals(type,result.getCandidates().get(0).getType());
        assertEquals("semantic-"+type,result.getCandidates().get(0).getRuleId());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"unknown","REF","f2-iii",""})
    void unknownModelTypeCannotSilentlyCreateARiskFinding(String type) {
        Chunk source=VettingContextBudgetTest.chunk("source","XYZ1","The contractor shall provide the named maintenance staff throughout the contract.",120);
        VettingSemanticReview.Record record=reference(source,source);record.setType(type);
        Result result=review(response(record,source),properties(),Collections.singletonList(source),Collections.singletonList(source));
        assertTrue(result.getCandidates().isEmpty());assertEquals("failed",result.getTopicAudits().get(0).getStatus());
        assertEquals("not_completed",result.getTopicAudits().get(0).getCheckState());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"risk,consistent","language,consistent","risk,not_applicable","language,not_applicable"})
    void aMissingNecessaryMemberRejectsOnlyItsDependentAssessment(String type,String assessment){
        Chunk affected=VettingContextBudgetTest.chunk("affected","XYZ1","Except as specified in XYZ99(2), interim payment shall not apply to this contract.",150);
        Chunk reliable=VettingContextBudgetTest.chunk("reliable","XYZ2","The contractor shall keep the performance security valid throughout the contract.",150);
        VettingSemanticReview.Record dependent=reference(affected,affected);dependent.setType(type);dependent.setAssessment(assessment);dependent.setComment("This scoped provision is consistent or explicitly excluded, subject to its referenced exception.");
        VettingSemanticReview.Record independent=reference(reliable,reliable);independent.setType("risk");independent.setComment("Reliable independent scope requires business review.");
        AiGateway ai=response(dependent,affected,reliable);when(ai.completeStructuredJsonList(anyString(),anyString(),eq(VettingSemanticReview.Record.class),any(),anyList())).thenAnswer(call->VettingTokenFixture.records(call.getArgument(1),Arrays.asList(affected,reliable),Arrays.asList(dependent,independent)));
        List<Chunk> chunks=Arrays.asList(affected,reliable);Result result=review(ai,properties(),chunks,chunks);
        com.consense.web.dto.VettingDtos.SemanticPacketVO packet=result.getTopicAudits().get(0).getPacketAudits().get(0);
        assertEquals(1,packet.getRejectedRecords());assertEquals(0,packet.getVerifiedConsistentAssessments());assertEquals(0,packet.getVerifiedNotApplicableAssessments());assertTrue(packet.getVerifiedAssessments().isEmpty());
        assertEquals(1,result.getCandidates().size());assertEquals(reliable.getContent().trim(),result.getCandidates().get(0).getEvidence().get(0).getQuote());
        assertEquals("issue",packet.getCheckState(),"An unrelated reliable assessment continues despite the missing member in A");
    }
    @Test void outputBudgetExhaustionHasItsOwnOutcomeAndNeverClaimsReviewedCoverage() {
        HttpSupport http = mock(HttpSupport.class);
        when(http.get(anyString(),anyLong())).thenReturn("{\"data\":[]}");
        when(http.postJson(anyString(),anyString(),anyLong(),isNull(),anyInt())).thenReturn(
                "{\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":1024,\"completion_tokens_details\":{\"reasoning_tokens\":1023}},\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"reasoning_content\":\"untrusted hidden text\"}}]}");
        ConsenseProperties props = properties();
        Chunk source = VettingContextBudgetTest.chunk("a","XYZ1","The original requirement shall be retained.",100);
        Result result = review(new AiGateway(new OpenAiLlmClient(props.getLlm(),http)),props,Collections.singletonList(source),Collections.singletonList(source));
        assertEquals("failed",result.getTopicAudits().get(0).getStatus());
        assertEquals("not_completed",result.getTopicAudits().get(0).getCheckState());
        assertEquals("output_budget_exhausted",result.getTopicAudits().get(0).getFailureKind());
        assertNotNull(result.getTopicAudits().get(0).getModelResponseMetadata());
        assertFalse(result.getTopicAudits().get(0).getModelResponseMetadata().toString().contains("untrusted hidden text"));
        assertNull(result.getTopicAudits().get(0).getRawResponseSha256());
        assertTrue(result.getReviewedChunkIds().isEmpty()); assertTrue(result.getCandidates().isEmpty());
        verify(http,times(1)).postJson(anyString(),anyString(),anyLong(),isNull(),eq(0));
    }

    @Test void anActualEmptyArrayRemainsACompletedEmptyAssessmentWithNoFinding() {
        HttpSupport http = mock(HttpSupport.class);
        when(http.get(anyString(),anyLong())).thenReturn("{\"data\":[]}");
        when(http.postJson(anyString(),anyString(),anyLong(),isNull(),anyInt())).thenReturn(
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"[]\"}}]}");
        ConsenseProperties props = properties();
        Chunk source = VettingContextBudgetTest.chunk("a","XYZ1","The original requirement shall be retained.",100);
        Result result = review(new AiGateway(new OpenAiLlmClient(props.getLlm(),http)),props,Collections.singletonList(source),Collections.singletonList(source));
        assertEquals("completed_empty",result.getTopicAudits().get(0).getStatus());
        assertEquals("not_completed",result.getTopicAudits().get(0).getCheckState(),"An empty model array is no explicit pass assessment");
        assertNull(result.getTopicAudits().get(0).getFailureKind()); assertEquals(0,result.getTopicAudits().get(0).getAcceptedFindings());
        assertEquals(Collections.singleton("a"),result.getReviewedChunkIds()); assertTrue(result.getCandidates().isEmpty());
    }

    @Test void wrappedEmptyResultIsAnInvalidOutputNotACompletedEmptyAssessment() {
        HttpSupport http = mock(HttpSupport.class);
        when(http.get(anyString(),anyLong())).thenReturn("{\"data\":[]}");
        when(http.postJson(anyString(),anyString(),anyLong(),isNull(),anyInt())).thenReturn(
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"items\\\":[]}\"}}]}");
        ConsenseProperties props = properties();
        Chunk source = VettingContextBudgetTest.chunk("a","XYZ1","The original requirement shall be retained.",100);
        Result result = review(new AiGateway(new OpenAiLlmClient(props.getLlm(),http)),props,Collections.singletonList(source),Collections.singletonList(source));
        assertEquals("failed",result.getTopicAudits().get(0).getStatus());
        assertEquals("structured_output_invalid",result.getTopicAudits().get(0).getFailureKind());
        assertTrue(result.getReviewedChunkIds().isEmpty()); assertTrue(result.getCandidates().isEmpty());
        com.consense.web.dto.VettingDtos.SemanticPacketVO packet=result.getTopicAudits().get(0).getPacketAudits().get(0);
        assertEquals(2,packet.getAttempts().size());assertTrue(packet.getAttempts().stream().allMatch(a->"structured_output_invalid".equals(a.getStatus())&&"SCHEMA_MISMATCH".equals(a.getValidationReason())));
        assertEquals(2,result.getActualGatewayCallCount());
        verify(http,times(2)).postJson(anyString(),anyString(),anyLong(),isNull(),eq(0));
    }

    @Test void theProductionReviewUsesExpandedWindowAndKeepsUnknownSubclauseScope() {
        ConsenseProperties props = properties(); props.getVetting().setSemanticContextChars(10000);
        List<Chunk> corpus = VettingContextBudgetTest.parent(4000);
        AiGateway ai = mock(AiGateway.class); when(ai.available()).thenReturn(true); when(ai.chatModel()).thenReturn("fixture");
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(VettingSemanticReview.Record.class),any(),anyList())).thenAnswer(call -> {
            String prompt = call.getArgument(1);
            assertTrue(prompt.contains("referenced subsection (6)")); assertFalse(prompt.contains("\"clauseHeadingLocation\""));assertTrue(VettingTokenFixture.excerpts(prompt).size()>0);
            assertTrue(prompt.contains("qualifiersComplete=unknown")); return Collections.emptyList();
        });
        Result result = review(ai,props,corpus,Collections.singletonList(corpus.get(0)));
        assertTrue(result.getTopicAudits().get(0).isContextExpanded()); assertTrue(result.getTopicAudits().get(0).isContextExpansionAttempted());
        assertEquals(20000,result.getTopicAudits().get(0).getEffectiveContextBudgetChars());
        assertEquals(10000,result.getTopicAudits().get(0).getInitialContextBudgetChars());
        assertEquals(0,result.getTopicAudits().get(0).getMissingLocatedReferenceTargets());
        assertTrue(result.getTopicAudits().get(0).getUnresolvedChunkIds().contains("a"));
        assertEquals(3,result.getReviewedChunkIds().size());
    }

    @Test void aBareAppendixLabelCannotTurnAnAbsenceDeclarationIntoAReferenceFinding() {
        ConsenseProperties props=properties();
        Chunk origin=VettingContextBudgetTest.chunk("origin","XYZ1","The implementation details shall follow APPENDIX XYZ/A.",70);
        Chunk label=VettingContextBudgetTest.chunk("label","XYZ2","APPENDIX XYZ/A",15);
        List<Chunk> chunks=Arrays.asList(origin,label);
        Result result=review(response(reference(origin,label),origin,label),props,chunks,chunks);
        assertEquals("completed_with_rejections",result.getTopicAudits().get(0).getStatus());
        assertEquals(1,result.getTopicAudits().get(0).getRejectedRecords());
        assertTrue(result.getCandidates().isEmpty());
    }

    @Test void aReferenceIssueWithTwoSubstantiveSourcePassagesRemainsEligibleForReview() {
        ConsenseProperties props=properties();
        Chunk origin=VettingContextBudgetTest.chunk("origin","XYZ1","The implementation details shall follow APPENDIX XYZ/A.",70);
        Chunk target=VettingContextBudgetTest.chunk("target","XYZ2","This appendix records flushing results and does not describe the referenced functions.",100);
        List<Chunk> chunks=Arrays.asList(origin,target);
        Result result=review(response(reference(origin,target),origin,target),props,chunks,chunks);
        assertEquals("completed",result.getTopicAudits().get(0).getStatus());
        assertEquals(1,result.getCandidates().size());
        assertEquals("issue",result.getTopicAudits().get(0).getCheckState());
    }

    @Test void onlyEvidenceVerifiedDeclarationsCanBecomePassOrInsufficientEvidence() {
        Chunk source=VettingContextBudgetTest.chunk("source","SCC","The contractor shall retain insurance throughout the contract.",80);
        for(String assessment:Arrays.asList("consistent","insufficient_context")){
            VettingSemanticReview.Record record=reference(source,source);record.setAssessment(assessment);record.setType("risk");
            Result result=review(response(record,source),properties(),Collections.singletonList(source),Collections.singletonList(source));
            assertEquals("consistent".equals(assessment)?"checked_pass":"insufficient_evidence",result.getTopicAudits().get(0).getPacketAudits().get(0).getCheckState());assertTrue(result.getCandidates().isEmpty());
            record.getEvidence().get(0).setQuote("A quotation absent from the original source.");
            Result rejected=review(response(record,source),properties(),Collections.singletonList(source),Collections.singletonList(source));assertEquals("not_completed",rejected.getTopicAudits().get(0).getCheckState());
        }
    }

    @Test void decodingAndPromptDoNotReintroduceAThreeFindingCap() {
        assertFalse(VettingOutputSchema.forChunkIds(Collections.singleton("source")).has("maxItems"));
    }

    @Test void explicitNotApplicableKeepsItsScopeEvidenceAndCannotHideUnknownCoverage(){
        Chunk source=VettingContextBudgetTest.chunk("source","SCC","Interim payment under GCC clause 14.2 shall not apply to this contract; the stated fixed payment schedule governs.",140);
        VettingSemanticReview.Record record=reference(source,source);record.setAssessment("not_applicable");record.setType("risk");record.setTitle("Interim payment exclusion");record.setComment("The quoted project clause explicitly excludes this interim-payment provision from this contract.");
        Result result=review(response(record,source),properties(),Collections.singletonList(source),Collections.singletonList(source));
        com.consense.web.dto.VettingDtos.SemanticTopicVO topic=result.getTopicAudits().get(0);com.consense.web.dto.VettingDtos.SemanticPacketVO packet=topic.getPacketAudits().get(0);
        assertEquals("not_applicable",packet.getCheckState());assertEquals(1,packet.getVerifiedNotApplicableAssessments());assertTrue(result.getCandidates().isEmpty());
        assertEquals(record.getComment(),packet.getVerifiedAssessments().get(0).getComment());assertEquals(source.getSourceHash(),packet.getVerifiedAssessments().get(0).getEvidence().get(0).getSourceHash());assertEquals(packet.getPacketId(),packet.getVerifiedAssessments().get(0).getEvidence().get(0).getPacketId());
        assertTrue(topic.getPendingSourceRequestCount()>0);assertEquals("not_completed",topic.getCheckState(),"Scoped NA cannot mark an unknown topic complete");
    }

    @Test void notApplicableWithoutAValidLocatedScopeCannotBecomeAnAutomaticState(){
        Chunk source=VettingContextBudgetTest.chunk("source","SCC","Interim payment shall not apply to this contract; the fixed payment schedule governs.",120);
        VettingSemanticReview.Record record=reference(source,source);record.setAssessment("not_applicable");record.setType("risk");record.getEvidence().get(0).setQuote("There is no matching exclusion in the supplied project source.");
        Result absent=review(response(record,source),properties(),Collections.singletonList(source),Collections.singletonList(source));assertEquals("not_completed",absent.getTopicAudits().get(0).getPacketAudits().get(0).getCheckState());assertEquals(0,absent.getTopicAudits().get(0).getVerifiedNotApplicableAssessments());
        record.getEvidence().get(0).setQuote(source.getContent());source.setSourceHash(null);
        Result unidentified=review(response(record,source),properties(),Collections.singletonList(source),Collections.singletonList(source));assertEquals("not_completed",unidentified.getTopicAudits().get(0).getPacketAudits().get(0).getCheckState());assertEquals(0,unidentified.getTopicAudits().get(0).getVerifiedNotApplicableAssessments());
    }

    private static AiGateway response(VettingSemanticReview.Record record,Chunk... sources) {
        AiGateway ai=mock(AiGateway.class);when(ai.available()).thenReturn(true);when(ai.chatModel()).thenReturn("fixture");
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(VettingSemanticReview.Record.class),any(),anyList())).thenAnswer(call->VettingTokenFixture.records(call.getArgument(1),Arrays.asList(sources),Collections.singletonList(record)));
        return ai;
    }
    private static VettingSemanticReview.Record reference(Chunk first,Chunk second) {
        VettingSemanticReview.Record r=new VettingSemanticReview.Record();com.consense.service.vetting.VettingCompetitionFixture.details(r);r.setAssessment("issue");r.setType("reference");r.setSeverity("medium");
        r.setTitle("Check reference");r.setComment("The quoted reference requires source verification.");
        List<VettingSemanticReview.Quote> quotes=new ArrayList<>();
        for(Chunk c:Arrays.asList(first,second)){VettingSemanticReview.Quote q=new VettingSemanticReview.Quote();q.setChunkId(c.getId());q.setSide("source");q.setQuote(c.getContent().trim());quotes.add(q);}
        r.setEvidence(quotes);return r;
    }

    private static ConsenseProperties properties() {
        ConsenseProperties p = new ConsenseProperties(); p.getVetting().setSemanticTopics(1); p.getVetting().setProjectReferenceComparisons(0); return p;
    }
    private static Result review(AiGateway ai, ConsenseProperties props,List<Chunk> corpus,List<Chunk> ranked) {
        VettingRetrievalClient retrieval = mock(VettingRetrievalClient.class);
        when(retrieval.retrieve(anyString(),anyString(),eq("tender"),anyList())).thenReturn(ranked);
        Result result = VettingTokenFixture.review(ai,props,retrieval).review("fixture",corpus,"en",(n,s)->{});
        verify(retrieval,times(1)).index("fixture",corpus);
        verify(retrieval,times(1)).retrieve(eq("fixture"),anyString(),eq("tender"),eq(corpus));
        return result;
    }
}
