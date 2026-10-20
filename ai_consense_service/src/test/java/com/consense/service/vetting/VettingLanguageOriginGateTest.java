package com.consense.service.vetting;

import com.consense.ai.AiGateway;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingSemanticReview.Record;
import com.consense.service.vetting.VettingSemanticReview.Quote;
import com.consense.service.vetting.VettingSemanticReview.Result;
import com.consense.web.dto.VettingDtos.SemanticPacketVO;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Public review seam: located tender wording is not evidence of project authorship. */
class VettingLanguageOriginGateTest {
    @TempDir Path probes;
    private List<JsonNode> gates;

    @Test void explicitSameClauseAmendmentAllowsProjectLanguageButWrongClauseOrUnknownRawIdentityDoesNot()throws Exception {
        Chunk tender=chunk("amended","tender","SCC81.4 is amended by substituting: The Contractor are responsible for the additional harbour inspections.");
        tender.setSourceHash(VettingCorpus.hash("AUTHORED parsed amendment"));tender.setOriginalSourceSha256(VettingCorpus.hash("AUTHORED original amendment bytes"));
        Result accepted=review(Collections.singletonList(tender),record("language",tender));
        assertEquals(1,accepted.getCandidates().size());assertEquals("language",accepted.getCandidates().get(0).getType());
        tender.setClauseId("SCC99.2");assertHeld(review(Collections.singletonList(tender),record("language",tender)));
        tender.setClauseId("SCC81.4");tender.setOriginalSourceSha256(null);assertHeld(review(Collections.singletonList(tender),record("language",tender)));
    }

    @Test void modelClaimWithoutBaselineCannotCertifyProjectAddedWording()throws Exception {
        Chunk tender=chunk("authored","tender","The Contractor are responsible for the additional harbour inspections.");
        assertHeld(review(Collections.singletonList(tender),record("language",tender)));
    }

    @Test void missingQuoteInSameClauseStandardFragmentDoesNotProveAnAmendment()throws Exception {
        Chunk tender=chunk("authored","tender","The Contractor are responsible for the additional harbour inspections.");
        Chunk fragment=chunk("fragment","standard","The Contractor shall retain the original register for inspection.");
        assertHeld(review(Arrays.asList(tender,fragment),record("language",tender)));
    }

    @Test void rejectedLanguageLeavesIndependentRiskAndReferenceInTheSamePacket()throws Exception {
        Chunk a=chunk("left","tender","The contractor shall retain the performance security throughout the contract.");
        Chunk b=chunk("right","tender","The contractor shall release the performance security before completion of the contract.");
        Result result=review(Arrays.asList(a,b),record("language",a),record("risk",a),record("reference",a,b));
        assertEquals(2,result.getCandidates().size());
        assertEquals(new HashSet<>(Arrays.asList("risk","reference")),new HashSet<>(Arrays.asList(result.getCandidates().get(0).getType(),result.getCandidates().get(1).getType())));
        assertEquals(1,packet(result).getRejectedRecords());assertEquals("completed_with_rejections",packet(result).getStatus());
        assertEquals("issue",packet(result).getCheckState());assertNull(result.getModelFailure());
    }

    @Test void reliableConsistentAssessmentCannotHideRejectedLanguageAsCheckedPass()throws Exception {
        Chunk tender=chunk("authored","tender","The Contractor are responsible for the additional harbour inspections.");
        Record consistent=record("risk",tender);consistent.setAssessment("consistent");
        Result result=review(Collections.singletonList(tender),record("language",tender),consistent);
        assertHeld(result);assertEquals(1,packet(result).getVerifiedConsistentAssessments());
        assertEquals(1,packet(result).getVerifiedAssessments().size());
    }

    private void assertHeld(Result result) {
        assertTrue(result.getCandidates().isEmpty());assertEquals(1,packet(result).getRejectedRecords());
        assertEquals("completed_with_rejections",packet(result).getStatus());assertEquals("not_completed",packet(result).getCheckState());
        assertNull(result.getModelFailure());assertTrue(result.getWarnings().stream().anyMatch(s->s.contains("项目新增或改写未确认")));
        assertTrue(gates.stream().anyMatch(g->g.path("observations").path("reasons").toString().contains("project_wording_origin_unconfirmed")));
    }

    private SemanticPacketVO packet(Result result){return result.getTopicAudits().get(0).getPacketAudits().get(0);}

    private Result review(List<Chunk> corpus,Record... records)throws Exception {
        Result result=reviewRaw(corpus,records);
        assertNull(result.getModelFailure(),"The replay must reach per-record gates without a schema/mock failure");
        assertEquals(records.length,gates.size(),"Every declared record must reach the actual per-record gate");
        System.out.println("LANGUAGE_ORIGIN_SEAM "+JsonUtils.write(VettingReviewProbe.map("packet",packet(result),"candidates",result.getCandidates(),"warnings",result.getWarnings(),"gates",gates)));
        return result;
    }

    private Result reviewRaw(List<Chunk> corpus,Record... records)throws Exception {
        AiGateway ai=mock(AiGateway.class);when(ai.available()).thenReturn(true);when(ai.chatModel()).thenReturn("AUTHORED-only");
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(Record.class),any(),anyList(),anyMap())).thenReturn(Arrays.asList(records));
        ConsenseProperties props=new ConsenseProperties();props.getVetting().setSemanticTopics(1);props.getVetting().setProjectReferenceComparisons(0);props.getVetting().setProbeDirectory(probes.toString());
        VettingRetrievalClient retrieval=mock(VettingRetrievalClient.class);
        when(retrieval.retrieve(anyString(),anyString(),anyString(),anyList(),any(VettingReviewProbe.Call.class))).thenAnswer(call->{String role=call.getArgument(2);List<Chunk> out=new ArrayList<>();for(Chunk c:corpus)if(role.equals(c.getRole()))out.add(c);return out;});
        String run="AUTHORED-origin-"+UUID.randomUUID();
        Result result=VettingTokenFixture.review(ai,props,retrieval).reviewWithProgress(run,"AUTHORED-project",corpus,"en",(n,total,message)->{});
        gates=new ArrayList<>();try(Stream<Path> files=Files.list(probes.resolve(run))){for(Path path:(Iterable<Path>)files.filter(p->p.getFileName().toString().endsWith("-record_evidence_applicability_gate.json"))::iterator)gates.add(JsonUtils.parse(new String(Files.readAllBytes(path),StandardCharsets.UTF_8)));}
        return result;
    }

    private static Record record(String type,Chunk... sources) {
        Record r=new Record();com.consense.service.vetting.VettingCompetitionFixture.details(r);r.setAssessment("issue");r.setType(type);r.setSeverity("low");r.setTitle("Authored observation");r.setComment("The model claims this is project-added prose.");
        List<Quote> quotes=new ArrayList<>();for(Chunk c:sources){Quote q=new Quote();q.setChunkId(c.getId());q.setSide("source");q.setQuote(c.getContent());quotes.add(q);}r.setEvidence(quotes);return r;
    }

    private static Chunk chunk(String id,String role,String text) {
        Chunk c=new Chunk();c.setId(id);c.setDocumentId("doc-"+id);c.setSourceHash("AUTHORED-"+id);c.setFileKey("SCC");c.setClauseId("SCC81.4");c.setRole(role);c.setAnchor("body/"+id);c.setContent(text);return c;
    }
}
