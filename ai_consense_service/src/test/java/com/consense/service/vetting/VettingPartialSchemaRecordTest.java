package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.VettingDtos.SemanticPacketVO;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class VettingPartialSchemaRecordTest {
    @Test void invalidSecondRecordDoesNotDiscardTheFirstButIsRecordedAsUnassessed() {
        VettingBusinessShapeRepairTest.Fixture f = new VettingBusinessShapeRepairTest.Fixture();
        ArrayNode records = (ArrayNode) JsonUtils.parse(f.raw("amend", false, f.source.getContent()));
        records.add("AUTHORED invalid record TEST ONLY");
        String raw = JsonUtils.write(records);
        VettingSemanticReview.Result result = f.review(f.wire(raw, raw));
        SemanticPacketVO packet = result.getTopicAudits().get(0).getPacketAudits().get(0);
        assertEquals(1, result.getCandidates().size(), "The valid first record still needs all source and business gates");
        assertEquals("completed_with_rejections", packet.getStatus());
        assertEquals(2, packet.getAttempts().size());
        assertEquals(raw, packet.getAttempts().get(0).getRawResponse());
        assertEquals(raw, packet.getAttempts().get(1).getRawResponse());
        assertTrue(result.getWarnings().stream().anyMatch(s -> s.contains("structural validation") && s.contains("unassessed")));
    }

    @Test void theLastResponseIsNotCombinedWithAnEarlierDifferentValidRecord() {
        VettingBusinessShapeRepairTest.Fixture f = new VettingBusinessShapeRepairTest.Fixture();
        ArrayNode first=(ArrayNode)JsonUtils.parse(f.raw("amend",false,f.source.getContent()));
        ((ObjectNode)first.get(0)).put("title","AUTHORED first response TEST ONLY"); first.add("invalid first");
        ArrayNode second=(ArrayNode)JsonUtils.parse(f.raw("amend",false,f.source.getContent()));
        ((ObjectNode)second.get(0)).put("title","AUTHORED second response TEST ONLY"); second.add("invalid second");
        VettingSemanticReview.Result result=f.review(f.wire(JsonUtils.write(first),JsonUtils.write(second)));
        assertEquals(1,result.getCandidates().size());
        assertEquals("AUTHORED second response TEST ONLY",result.getCandidates().get(0).getTitle());
    }

    @ParameterizedTest @ValueSource(strings={"duplicate", "trailing", "invented"})
    void strictJsonAndSourceChecksCannotBeBypassed(String kind) {
        VettingBusinessShapeRepairTest.Fixture f=new VettingBusinessShapeRepairTest.Fixture();
        String quote="invented".equals(kind)?"AUTHORED invented quote not in source":f.source.getContent();
        ArrayNode records=(ArrayNode)JsonUtils.parse(f.raw("amend",false,quote)); records.add("invalid record");
        String raw=JsonUtils.write(records);
        if ("duplicate".equals(kind)) raw=raw.replace("\"assessment\":\"issue\"","\"assessment\":\"issue\",\"assessment\":\"issue\"");
        if ("trailing".equals(kind)) raw+=" trailing";
        VettingSemanticReview.Result result=f.review(f.wire(raw,raw));
        assertTrue(result.getCandidates().isEmpty(),kind);
        if (!"invented".equals(kind)) assertEquals("failed",result.getTopicAudits().get(0).getPacketAudits().get(0).getStatus());
    }

    @Test void checkpointReplayRetainsPartialWarningAndRawResponsesWithoutAnotherGeneration() {
        VettingBusinessShapeRepairTest.Fixture f=new VettingBusinessShapeRepairTest.Fixture();
        VettingRepairEmptyWarningTest.Store store=new VettingRepairEmptyWarningTest.Store();
        ArrayNode records=(ArrayNode)JsonUtils.parse(f.raw("amend",false,f.source.getContent())); records.add("invalid record");
        String raw=JsonUtils.write(records); VettingSemanticReview.Result first=store.review(f,raw,raw), replay=store.review(f);
        assertEquals(1,first.getCandidates().size()); assertEquals(1,replay.getCandidates().size());
        assertEquals(0,replay.getActualGatewayCallCount());
        SemanticPacketVO packet=replay.getTopicAudits().get(0).getPacketAudits().get(0);
        assertEquals("completed_with_rejections",packet.getStatus()); assertEquals(1,packet.getRejectedRecords());
        assertEquals(raw,packet.getAttempts().get(0).getRawResponse()); assertEquals(raw,packet.getAttempts().get(1).getRawResponse());
        assertTrue(replay.getWarnings().stream().anyMatch(s->s.contains("structural validation")&&s.contains("unassessed")));
    }

    @Test void predecessorAndNewRecoveryPolicyFingerprintsDiffer() {
        List<Object> prior=new ArrayList<>(Arrays.asList(VettingPromptPolicy.metadata(),VettingOutputSchema.contractSha256(),
                "rejected-candidate-repair-context-v2-schema-length-diagnostics","missing-evidence-only-repair-v2-whitespace-quote-policy",
                "progressive-direction-queries-v3-required-project-source-selection","business-shape-partial-isolation-v2",
                "qwen-27b-json-object-records-v1",VettingSourceAttribution.POLICY));
        for(String lang:Arrays.asList("en","zh-Hans","zh-Hant"))prior.add(ReflectionTestUtils.invokeMethod(VettingSemanticReview.class,"system",lang));
        for(String lang:Arrays.asList("en","zh-Hans","zh-Hant"))prior.add(ReflectionTestUtils.invokeMethod(VettingSemanticReview.class,"system",lang,true));
        for(String lang:Arrays.asList("en","zh-Hans","zh-Hant"))prior.add(ReflectionTestUtils.invokeMethod(VettingSemanticReview.class,"system",lang,false,true));
        for(String lang:Arrays.asList("en","zh-Hans","zh-Hant"))prior.add(ReflectionTestUtils.invokeMethod(VettingSemanticReview.class,"system",lang,true,true));
        assertNotEquals(VettingCorpus.hash(JsonUtils.write(prior)),VettingSemanticReview.policyContractSha256());
    }
}
