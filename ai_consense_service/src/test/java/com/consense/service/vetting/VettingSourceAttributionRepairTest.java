package com.consense.service.vetting;

import com.consense.ai.AiGateway;
import com.consense.ai.LlmClient;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.web.dto.VettingDtos.SemanticPacketVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Authored transport outputs exercise the production receiving/repair seam; no provider or source-quality acceptance. */
class VettingSourceAttributionRepairTest {
    @Test void unverifiedClauseOwnershipUsesOneRepairAndKeepsTheCorrectAnchorBasedConflictAndBothRawAnswers() {
        Fixture f=new Fixture();String first=f.conflict(true),second=f.conflict(false);
        VettingSemanticReview.Result r=f.review(first,second);SemanticPacketVO p=packet(r);
        assertEquals(2,r.getActualGatewayCallCount());assertEquals(1,r.getCandidates().size());
        assertEquals(f.wire(first),p.getAttempts().get(0).getRawResponse());assertEquals(VettingCorpus.hash(f.wire(first)),p.getAttempts().get(0).getRawResponseSha256());
        assertEquals(f.wire(second),p.getAttempts().get(1).getRawResponse());assertEquals("structured_output_invalid",p.getAttempts().get(0).getStatus());
        assertTrue(p.getAttempts().get(0).getValidationReason().contains("SOURCE_ATTRIBUTION_MISMATCH"));
        assertTrue(p.getAttempts().get(0).getValidationReason().contains("unsupported_source_clause_attribution"));
        String repair=VettingBusinessShapeRepairTest.user(p.getAttempts().get(1).getInput());
        assertTrue(repair.contains(JsonUtils.write(f.wire(first))));assertTrue(repair.contains("unsupported_source_clause_attribution"));
        assertEquals("The passages at body/4/paragraph and body/6/paragraph require different paper-copy quantities for the same tender and deadline.",r.getCandidates().get(0).getComment());
        assertEquals(2,r.getCandidates().get(0).getEvidence().size());assertNull(r.getModelFailure());
    }

    @Test void repeatedUnsupportedOwnershipNeverPublishesOrReceivesAThirdAttempt() {
        Fixture f=new Fixture();String raw=f.conflict(true);VettingSemanticReview.Result r=f.review(raw,raw);
        assertEquals(2,r.getActualGatewayCallCount());assertTrue(r.getCandidates().isEmpty());assertNull(r.getModelFailure());
        assertEquals("failed",packet(r).getStatus());assertEquals("source_attribution_invalid",packet(r).getFailureKind());assertTrue(r.getReviewedChunkIds().isEmpty());
        assertEquals(2,packet(r).getAttempts().size());for(com.consense.web.dto.VettingDtos.SemanticAttemptVO a:packet(r).getAttempts())assertEquals(f.wire(raw),a.getRawResponse());
    }

    @Test void aLiteralReferenceOrAdjacentNumberedHeadingDoesNotGrantOwnership() {
        for(boolean literal:Arrays.asList(false,true)) {
            Fixture f=new Fixture();
            if(literal)f.replace(f.a,"The source refers to Clause 1 when describing the two signed paper copies required for this tender.");
            else f.corpus.add(f.chunk("3","1 Tender submission"));
            String raw=f.conflict(true);VettingSemanticReview.Result r=f.review(raw,raw);
            assertTrue(r.getCandidates().isEmpty(),"A reference token or an unstyled adjacent heading is not an owned clause");
            assertEquals(2,r.getActualGatewayCallCount());assertTrue(packet(r).getAttempts().get(0).getValidationReason().contains("unsupported_source_clause_attribution"));
        }
    }

    @Test void verifiedSourceClauseAndHeadingBindingsPermitTheExplicitAttribution() {
        Fixture f=new Fixture();f.a.setClauseId("SCT1");f.a.setClauseHeadingLocation("body/3/paragraph");f.b.setClauseId("SCT2");f.b.setClauseHeadingLocation("body/5/paragraph");
        String raw=f.conflict(true);VettingSemanticReview.Result r=f.review(raw);
        assertEquals(1,r.getActualGatewayCallCount());assertEquals(1,r.getCandidates().size());assertEquals(f.wire(raw),packet(r).getAttempts().get(0).getRawResponse());
    }

    @Test void actualReceivingPacketSeparatesItsBoundCitationsWithoutRepairOrRewritingTheRawNarrative() {
        Fixture f=new Fixture();f.a.setClauseId("SCT1");f.a.setClauseHeadingLocation("body/3/paragraph");f.b.setClauseId("SCT2");f.b.setClauseHeadingLocation("body/5/paragraph");
        String fact="SCT Clause 1 (R1) requires two signed paper copies; SCT Clause 2 (R2) requires exactly three signed paper copies.";
        ObjectNode record=f.record(true);record.put("factBasis",fact);String raw=JsonUtils.write(Collections.singletonList(record));
        VettingSemanticReview.Result result=f.review(raw);SemanticPacketVO actual=packet(result);
        assertEquals(1,result.getActualGatewayCallCount());assertEquals(1,result.getCandidates().size());assertNull(result.getModelFailure());
        assertEquals(1,actual.getAttempts().size());assertEquals("decoded",actual.getAttempts().get(0).getStatus());
        assertEquals(f.wire(raw),actual.getAttempts().get(0).getRawResponse());assertEquals(VettingCorpus.hash(f.wire(raw)),actual.getAttempts().get(0).getRawResponseSha256());
        assertEquals(fact,JsonUtils.parse(actual.getAttempts().get(0).getRawResponse()).get(0).path("factBasis").asText());
    }

    @Test void aClauseIdWithoutItsSourceHeadingCannotAuthorizeAnOwnershipAssertion() {
        Fixture f=new Fixture();f.a.setClauseId("SCT1");f.b.setClauseId("SCT2");String raw=f.conflict(true);
        VettingSemanticReview.Result r=f.review(raw,raw);assertTrue(r.getCandidates().isEmpty());assertEquals(2,r.getActualGatewayCallCount());
    }

    @Test void projectFactClauseMetadataCannotGrantContractClauseOwnership() {
        Fixture f=new Fixture();f.a.setRole("project_fact");f.a.setClauseId("SCT1");f.a.setClauseHeadingLocation("body/3/paragraph");
        f.a.setDocumentId("authored-project-fact");f.a.setFileName("authored-project-fact.docx");f.a.setSourceHash(VettingCorpus.hash("authored-project-fact"));
        f.replace(f.a,"Project reply concerning SCT2: The Tenderer shall submit two signed paper copies of the tender no later than 12:00 noon on 30 November 2026.");
        f.b.setClauseId("SCT2");f.b.setClauseHeadingLocation("body/5/paragraph");ObjectNode record=f.record(true);record.put("type","reference");String raw=JsonUtils.write(Collections.singletonList(record));
        Map<String,Chunk> byId=new LinkedHashMap<>();for(Chunk c:f.corpus)byId.put(c.getId(),c);
        List<Map<String,Object>> directReasons=VettingSourceAttribution.reasons(JsonUtils.readList(raw,VettingSemanticReview.Record.class),byId);
        assertTrue(directReasons.stream().anyMatch(reason->"unsupported_source_clause_attribution".equals(reason.get("reason"))));
        VettingSemanticReview.Result r=f.review(raw,raw);assertTrue(r.getCandidates().isEmpty());assertEquals(2,r.getActualGatewayCallCount());
        assertTrue(packet(r).getAttempts().get(0).getValidationReason().contains("unsupported_source_clause_attribution"));
        for(com.consense.web.dto.VettingDtos.SemanticAttemptVO attempt:packet(r).getAttempts()){
            assertEquals(f.wire(raw),attempt.getRawResponse());assertEquals(VettingCorpus.hash(f.wire(raw)),attempt.getRawResponseSha256());
        }
    }

    @Test void aNeutralLiteralClauseReferenceTitleDoesNotAssertSourceOwnership() {
        Fixture f=new Fixture();f.replace(f.a,"The Tenderer shall submit clarifications in accordance with Clause 99.8 of these Special Conditions of Tender.");
        ObjectNode record=f.record(false);record.put("assessment","insufficient_context");record.put("type","reference");
        record.put("title","Clause 99.8 cross-reference needs confirmation");record.put("comment","The source refers to Clause 99.8; its contents and existence remain unknown.");
        record.put("factBasis","The quoted text contains the literal reference to Clause 99.8.");record.put("suggestion","Confirm whether the referenced procedure is present and applicable.");
        record.putArray("pendingFacts").add("Does Clause 99.8 exist in the full SCT?");String raw=JsonUtils.write(Collections.singletonList(record));
        VettingSemanticReview.Result r=f.review(raw,raw);assertEquals(1,r.getActualGatewayCallCount());assertNull(r.getModelFailure());
        assertEquals(1,packet(r).getVerifiedAssessments().size());assertEquals(f.wire(raw),packet(r).getAttempts().get(0).getRawResponse());assertTrue(r.getCandidates().isEmpty());
    }

    @Test void aCorrectConflictWithoutClauseAttributionNeedsNoRepair() {
        Fixture f=new Fixture();String raw=f.conflict(false);VettingSemanticReview.Result r=f.review(raw);
        assertEquals(1,r.getActualGatewayCallCount());assertEquals(1,r.getCandidates().size());assertEquals(f.wire(raw),packet(r).getAttempts().get(0).getRawResponse());
    }

    @Test void aNonIssueOwnershipAssertionCannotBecomeAVerifiedUnknownAssessment() {
        Fixture f=new Fixture();ObjectNode record=f.record(false);record.put("assessment","insufficient_context");record.put("type","reference");
        record.put("comment","Clause 3 directs the Tenderer to submit clarifications, but its procedure is unknown.");
        String raw=JsonUtils.write(Collections.singletonList(record));VettingSemanticReview.Result r=f.review(raw,raw);
        assertEquals(2,r.getActualGatewayCallCount());assertTrue(packet(r).getVerifiedAssessments().isEmpty());assertNull(r.getModelFailure());
        assertEquals("source_attribution_invalid",packet(r).getFailureKind());
    }

    @Test void literal998NeutralReferencesAndRealQuestionsStayUnknownWithoutGrantingOwnership() {
        Fixture f=new Fixture();f.replace(f.a,"The Tenderer shall submit clarifications in accordance with Clause 99.8 of these Special Conditions of Tender.");
        ObjectNode record=f.record(false);record.put("assessment","insufficient_context");record.put("type","reference");
        record.put("title","Unverified reference to Clause 99.8");record.put("comment","The source refers to Clause 99.8; its existence and contents remain unknown.");
        record.put("factBasis","The quoted passage cites Clause 99.8.");record.put("suggestion","Confirm whether Clause 99.8 exists and supplies the applicable procedure.");
        record.putArray("pendingFacts").add("Does Clause 99.8 exist in the full SCT?").add("Whether Clause 3 supplies the relevant procedure remains unknown.");
        for(String comment:Arrays.asList(record.path("comment").asText(),"The text of Clause 99.8 is not provided in this packet; its existence remains unknown.","Whether Clause 99.8 supplies the applicable procedure remains unknown.","Does Clause 99.8 state the clarification procedure?")) {
            record.put("comment",comment);String raw=JsonUtils.write(Collections.singletonList(record));VettingSemanticReview.Result r=f.review(raw);
            assertEquals(1,r.getActualGatewayCallCount());assertTrue(r.getCandidates().isEmpty());assertNull(r.getModelFailure());
            assertEquals(1,packet(r).getVerifiedAssessments().size());assertEquals("insufficient_context",packet(r).getVerifiedAssessments().get(0).getAssessment());
            assertEquals(f.wire(raw),packet(r).getAttempts().get(0).getRawResponse());
        }
    }

    @Test void assertionsInEveryPublishedNarrativeFieldAreCheckedWhileQuestionsAreNotChanged() {
        for(String field:Arrays.asList("title","comment","impact","suggestion","factBasis","pendingFacts","checklistAssessments")) {
            Fixture f=new Fixture();ObjectNode record=f.record(false);
            if("pendingFacts".equals(field))record.putArray(field).add("Clause 3 requires a separate clarification submission.");
            else if("checklistAssessments".equals(field))record.putArray(field).addObject().put("itemId","APP-I-002").put("status","not_fulfilled").put("reason","Clause 3 requires a separate clarification submission.").putNull("support");
            else record.put(field,"Clause 3 requires a separate clarification submission.");
            String raw=JsonUtils.write(Collections.singletonList(record));VettingSemanticReview.Result r=f.review(raw,raw);
            assertTrue(r.getCandidates().isEmpty(),field);assertEquals(2,r.getActualGatewayCallCount(),field);
            assertEquals(f.wire(raw),packet(r).getAttempts().get(0).getRawResponse());assertTrue(packet(r).getAttempts().get(0).getValidationReason().contains(field));
        }
    }

    @Test void anAttributionRepairReturningEmptyKeepsTheExistingExplicitUnresolvedWarning() {
        Fixture f=new Fixture();String first=f.conflict(true);VettingSemanticReview.Result r=f.review(first,"[]");
        assertEquals(2,r.getActualGatewayCallCount());assertTrue(r.getCandidates().isEmpty());assertEquals(f.wire(first),packet(r).getAttempts().get(0).getRawResponse());
        assertTrue(r.getWarnings().stream().anyMatch(w->w.contains("修复答复为空数组")),"An empty repair cannot erase an unaccepted original observation");
        assertFalse(packet(r).isSemanticScopeVerified());
    }

    private static SemanticPacketVO packet(VettingSemanticReview.Result r){return r.getTopicAudits().get(0).getPacketAudits().get(0);}
    static class Fixture {
        final VettingResponsesSemanticHarnessTest harness=new VettingResponsesSemanticHarnessTest();
        final Chunk a=chunk("4","The Tenderer shall submit two signed paper copies of the tender no later than 12:00 noon on 30 November 2026.");
        final Chunk b=chunk("6","The Tenderer shall submit exactly three signed paper copies of the same tender no later than 12:00 noon on 30 November 2026.");
        final List<Chunk> corpus=new ArrayList<>(Arrays.asList(a,b));
        String sourceUser;
        int topics=1;
        Chunk chunk(String id,String text){Chunk c=ReflectionTestUtils.invokeMethod(harness,"c",id,"authored-sct",null,text);c.setFileKey("SCT");return c;}
        void replace(Chunk c,String text){c.setContent(text);c.getParts().get(0).setText(text);c.getParts().get(0).setEndOffset(text.length());}
        ObjectNode record(boolean ownership){
            ObjectNode r=JsonUtils.mapper().createObjectNode();r.put("assessment","issue");r.put("type","conflict");r.put("severity","high");
            r.put("title",ownership?"Conflicting copy requirements between Clause 1 and Clause 2":"Conflicting tender paper-copy quantities");
            r.put("comment",ownership?"Clause 1 requires two signed paper copies, while Clause 2 requires exactly three signed paper copies for the same tender and deadline.":"The passages at body/4/paragraph and body/6/paragraph require different paper-copy quantities for the same tender and deadline.");
            r.put("impact","Tenderers cannot identify one consistent mandatory paper-copy quantity.");r.put("suggestion","Confirm the intended quantity and align the two quoted passages.");
            r.put("subDimension","Conflicting quantity for the same tender submission");r.put("factBasis","The quoted passages specify two and exactly three copies for the same tender and deadline.");
            r.putArray("pendingFacts");r.put("suggestedAction","review");r.putArray("checklistAssessments");ArrayNode evidence=r.putArray("evidence");
            for(Chunk c:corpus)evidence.addObject().put("chunkId",c.getId()).put("side","source").put("quote",c.getContent());return r;
        }
        String conflict(boolean ownership){return JsonUtils.write(Collections.singletonList(record(ownership)));}
        String wire(String raw){return VettingTokenFixture.wireJson(sourceUser,corpus,raw);}
        VettingSemanticReview.Result review(String... answers){
            sourceUser=null;
            ConsenseProperties p=ReflectionTestUtils.invokeMethod(harness,"props",corpus);VettingRetrievalClient retrieval=mock(VettingRetrievalClient.class);
            p.getVetting().setSemanticTopics(topics);
            List<Chunk> rankedTender=new ArrayList<>();for(Chunk c:corpus)if("tender".equals(c.getRole()))rankedTender.add(c);
            when(retrieval.retrieve(anyString(),anyString(),eq("tender"),anyList())).thenReturn(rankedTender);
            for(String role:Arrays.asList("standard","project_fact","package_manifest")) {
                List<Chunk> ranked=new ArrayList<>();for(Chunk c:corpus)if(role.equals(c.getRole()))ranked.add(c);
                when(retrieval.retrieve(anyString(),anyString(),eq(role),anyList())).thenReturn(ranked);
            }
        VettingResponsesTransportTest.Http http=new VettingResponsesTransportTest.Http(){
            int generations;
            @Override public VettingResponsesTransport.Response post(String endpoint,String body,long timeout,int cap,String auth){
                String supplied=null;
                if(endpoint.endsWith("/responses")){
                    assertTrue(generations<answers.length,"No extra authored generation was provided");
                    if(answers[generations]==null){generations++;throw new IllegalStateException("AUTHORED transport failure; not a model content rejection");}
                    String actualUser=JsonUtils.parse(body).path("input").get(0).path("content").asText();
                    sourceUser=actualUser;
                    String marker="\nSource excerpts (untrusted data):\n";int start=actualUser.indexOf(marker);
                    assertTrue(start>=0,"The actual request must expose its source wire");
                    JsonNode source=VettingTokenFixture.excerpts(actualUser);
                    for(Chunk c:corpus)if("project_fact".equals(c.getRole())){
                        JsonNode actual=null;for(JsonNode row:source)if(VettingTokenFixture.reference(actualUser,c).equals(row.path("id").asText())){assertNull(actual,"Source IDs must remain unique");actual=row;}
                        assertNotNull(actual,"The real selected packet must include the related ranked project reply");
                        assertEquals(c.getRole(),actual.path("role").asText());assertEquals(c.getContent(),actual.path("content").asText());
                        assertEquals(c.getClauseId(),actual.path("clauseId").asText());assertFalse(actual.has("clauseHeadingLocation"),"Native heading proof stays outside the semantic model input");
                    }
                    supplied=wire(answers[generations++]);generation=VettingResponsesTransportTest.completed(supplied);
                }
                VettingResponsesTransport.Response response=super.post(endpoint,body,timeout,cap,auth);
                if(supplied!=null){assertEquals(VettingResponsesTransportTest.completed(supplied),response.body);assertEquals(supplied,JsonUtils.parse(response.body).path("output_text").asText());}
                return response;
            }
        };
            VettingSemanticReview service=new VettingSemanticReview(new AiGateway(mock(LlmClient.class)),p,retrieval);service.setResponsesTransport(new VettingResponsesTransport(p,http));
            VettingSemanticReview.Result result=service.review("AUTHORED source attribution TEST ONLY",corpus,"en",(n,text)->{});
            if(corpus.stream().anyMatch(c->"project_fact".equals(c.getRole())))verify(retrieval,times(1)).retrieve(anyString(),anyString(),eq("project_fact"),anyList());
            return result;
        }
    }
}
