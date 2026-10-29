package com.consense.service.vetting;

import com.consense.ai.LlmClient;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Reproduces a model naming an undefined role while the same role is explicitly pending. */
class VettingPendingDefinitionTest {
    @Test void unknownRoleDefinitionIsPendingRatherThanPublishedAsAConfirmedDefect() {
        VettingResponsesSemanticHarnessTest harness = new VettingResponsesSemanticHarnessTest();
        List<Chunk> sources = ReflectionTestUtils.invokeMethod(harness,"corpus");
        ConsenseProperties props = ReflectionTestUtils.invokeMethod(harness,"props",sources);
        ObjectNode record = (ObjectNode) JsonUtils.parse(new VettingBusinessShapeRepairTest.Fixture().raw("review",true,sources.get(0).getContent())).get(0);
        record.put("type","reference");
        record.put("title","AUTHORED provision references undefined 'Contract Manager' role");
        record.put("comment","The supplied snippets do not define 'Contract Manager'. Its definition elsewhere needs checking.");
        record.put("factBasis","AUTHORED excerpts use Contract Manager but its governing definition is not supplied.");
        record.putArray("pendingFacts").add("Whether 'Contract Manager' is defined in GCC or other adopted documents");
        record.putArray("evidence").addObject().put("chunkId","R1").put("side","source").put("quote",sources.get(0).getContent());
        record.withArray("evidence").addObject().put("chunkId","R2").put("side","reference").put("quote",sources.get(1).getContent());
        String raw=JsonUtils.write(Collections.singletonList(record));
        VettingResponsesTransportTest.Http http=new VettingResponsesTransportTest.Http();
        http.generation=VettingResponsesTransportTest.completed(raw);
        VettingSemanticReview.Result result=ReflectionTestUtils.invokeMethod(harness,"review",sources,props,http,mock(LlmClient.class));
        assertEquals(1,result.getActualGatewayCallCount());
        assertEquals(raw,result.getTopicAudits().get(0).getPacketAudits().get(0).getAttempts().get(0).getRawResponse(),"The actual model response remains available for audit");
        assertTrue(result.getCandidates().isEmpty(),"The pending definition cannot become an issue from two located unrelated passages");
        assertTrue(result.getTopicAudits().get(0).getPacketAudits().get(0).getVerifiedAssessments().stream().anyMatch(a -> "insufficient_context".equals(a.getAssessment()) && a.getPendingFacts().stream().anyMatch(f -> f.contains("Contract Manager"))),"Retain the model's evidence need so the existing targeted supplement can retrieve the applicable definition");
        assertTrue(VettingSemanticReview.directionEvidenceNeeds(Collections.emptyList(),result.getTopicAudits().get(0).getPacketAudits().get(0),sources).stream().anyMatch(n -> n.query.contains("GCC")),"The verified pending record must enter the existing evidence-need mechanism");
    }

    @Test void independentIssuesAndOtherPendingFactsRemainFindings() {
        for (String[] pair : Arrays.asList(
                new String[]{"Undefined 'Surveyor' role", "Whether 'Contract Manager' is defined in GCC"},
                new String[]{"Undefined 'Surveyor' role", "Whether 'Surveyor' was appointed by the employer"},
                new String[]{"Conflicting appointment roles", "Whether 'Contract Manager' is defined in GCC"})) {
            VettingSemanticReview.Record record = new VettingSemanticReview.Record();
            record.setAssessment("issue"); record.setSuggestedAction("review"); record.setTitle(pair[0]);
            record.setPendingFacts(Collections.singletonList(pair[1])); record.setChecklistAssessments(Collections.emptyList());
            assertEquals(0,VettingSemanticReview.retainContradictoryIssuesAsPending(Collections.singletonList(record)));
            assertEquals("issue",record.getAssessment()); assertEquals(pair[0],record.getTitle());
        }
    }

    @Test void categoryDoesNotDependOnOneContractRole() {
        VettingSemanticReview.Record record = new VettingSemanticReview.Record();
        record.setAssessment("issue"); record.setSuggestedAction("review"); record.setTitle("Potentially undefined 'Employer Representative'");
        record.setPendingFacts(Collections.singletonList("Whether 'Employer Representative' has been defined in GCC"));
        record.setChecklistAssessments(Collections.emptyList());
        assertEquals(1,VettingSemanticReview.retainContradictoryIssuesAsPending(Collections.singletonList(record)));
        assertEquals("insufficient_context",record.getAssessment());
        assertEquals("Definition of 'Employer Representative' requires confirmation",record.getTitle());
    }
}
