package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.VettingDtos.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** DTO/wire compatibility only. Does not verify any requirement predicate or source binding. */
class VettingChecklistNullableRelationContractTest {
    @Test void oldPacketRecordKeepsItsClaimAndOmitsTheMissingNullableRelation() {
        ChecklistAssessmentVO record=JsonUtils.read("{\"itemId\":\"APP-I-008\",\"status\":\"not_fulfilled\",\"reason\":\"Authored old model claim.\",\"evidenceChunkIds\":[\"quoted-a\"]}",ChecklistAssessmentVO.class);
        assertNull(record.getRequirementRelation());assertEquals("not_fulfilled",record.getStatus());
        JsonNode json=JsonUtils.mapper().valueToTree(record);assertFalse(json.has("requirementRelation"));
        assertEquals(Collections.singletonList("quoted-a"),record.getEvidenceChunkIds());
        assertFalse(JsonUtils.mapper().copy().setSerializationInclusion(JsonInclude.Include.NON_NULL).valueToTree(record).has("requirementRelation"));
    }

    @Test void serverUnknownRelationRoundTripsWithoutChangingTheClaimOrCertifyingIt() {
        ChecklistAssessmentVO record=claim();ObjectNode prior=JsonUtils.mapper().valueToTree(record);
        ObjectNode relation=JsonUtils.mapper().createObjectNode();relation.put("gateVersion","authored-contract-only");relation.put("status","unknown");
        relation.put("reasonCode","source_field_and_same_object_binding_unavailable");relation.putArray("sourceBindings");record.setRequirementRelation(relation);
        ChecklistAssessmentVO restored=JsonUtils.read(JsonUtils.write(record),ChecklistAssessmentVO.class);
        assertEquals(relation,restored.getRequirementRelation());assertEquals("not_fulfilled",restored.getStatus());assertEquals(record.getReason(),restored.getReason());
        ObjectNode current=JsonUtils.mapper().valueToTree(restored);current.remove("requirementRelation");assertEquals(prior,current,"Nullable metadata must not rewrite original claim/provenance/evidence.");
        record.setRequirementRelation(null);assertEquals(prior,JsonUtils.mapper().valueToTree(record));
    }

    @Test void nullableModelFactReferencesAndServerVerdictHaveDifferentContracts() {
        JsonNode properties=VettingOutputSchema.forChunkIds(Collections.singletonList("quoted-a")).path("items").path("properties").path("checklistAssessments").path("items").path("properties");
        assertFalse(properties.has("requirementRelation"),"A model response cannot directly submit the server verdict field.");
        JsonNode choices=properties.path("support").path("anyOf");assertEquals("null",choices.get(0).path("type").asText());
        JsonNode facts=choices.get(1).path("properties").path("facts").path("items").path("properties");
        for(String field:Arrays.asList("slot","evidenceIndex","text"))assertTrue(facts.has(field));
        for(String forbidden:Arrays.asList("nativePositionIndex","startUtf16","endUtf16","value","relevant","verified","gateVersion","requirementSha256","status"))assertFalse(facts.has(forbidden));
        VettingSemanticReview.ChecklistAssessment model=new VettingSemanticReview.ChecklistAssessment();model.setItemId("APP-I-008");model.setStatus("not_fulfilled");
        assertNull(model.getSupport());assertNull(new ChecklistAssessmentVO().getRequirementRelation());
    }

    @Test void vt02IdentityFieldsAndLegacyConstructorsCoexistWithNestedNullableChecklistMetadata() {
        FindingEvidence evidence=new FindingEvidence("source","71","SCT","Authored.docx",null,"body/4/paragraph","Authored exact quote for persistence only.",true,VettingCorpus.hash("authored source"),null);
        ObjectNode link=JsonUtils.mapper().createObjectNode().put("status","unexecuted_authored_contract");evidence.setPackageIdentityLink(link);
        assertEquals(link,JsonUtils.read(JsonUtils.write(evidence),FindingEvidence.class).getPackageIdentityLink());
        ChecklistAssessmentVO record=claim();record.setEvidence(Collections.singletonList(evidence));record.setRequirementRelation(JsonUtils.mapper().createObjectNode().put("status","unknown"));
        SemanticPacketVO packet=new SemanticPacketVO();packet.setChecklistAssessments(Collections.singletonList(record));SemanticTopicVO topic=new SemanticTopicVO();topic.getPacketAudits().add(packet);
        CoverageVO coverage=new CoverageVO(1,1,Collections.emptyList(),Collections.emptyList(),Collections.singletonList(topic),"incomplete",null,null);
        ObjectNode review=JsonUtils.mapper().createObjectNode().put("status","unexecuted_authored_review"),mirror=JsonUtils.mapper().createObjectNode().put("authorityEffect","none");
        coverage.setPackageIdentityReview(review);coverage.setPackageIdentityMirrorAudit(mirror);CoverageVO restored=JsonUtils.read(JsonUtils.write(coverage),CoverageVO.class);
        assertEquals(review,restored.getPackageIdentityReview());assertEquals(mirror,restored.getPackageIdentityMirrorAudit());
        ChecklistAssessmentVO nested=restored.getSemanticTopics().get(0).getPacketAudits().get(0).getChecklistAssessments().get(0);
        assertEquals("unknown",nested.getRequirementRelation().path("status").asText());assertEquals(link,nested.getEvidence().get(0).getPackageIdentityLink());assertEquals("not_fulfilled",nested.getStatus());
    }

    private ChecklistAssessmentVO claim(){
        ChecklistAssessmentVO record=new ChecklistAssessmentVO();record.setItemId("APP-I-008");record.setStatus("not_fulfilled");record.setReason("Authored retained model suggestion; relevance is not certified.");
        record.setPolicySha256(VettingCorpus.hash("authored policy"));record.setPacketId("authored-packet");record.setSourceSnapshotSha256(VettingCorpus.hash("authored source snapshot"));
        record.setIssueTypeCode("CONF");record.setEvidenceChunkIds(Collections.singletonList("quoted-a"));return record;
    }
}
