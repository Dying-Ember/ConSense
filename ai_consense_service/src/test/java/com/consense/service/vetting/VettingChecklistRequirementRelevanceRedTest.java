package com.consense.service.vetting;

import com.consense.ai.AiGateway;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingSemanticReview.ChecklistAssessment;
import com.consense.service.vetting.VettingSemanticReview.Quote;
import com.consense.service.vetting.VettingSemanticReview.Record;
import com.consense.service.vetting.VettingSemanticReview.Result;
import com.consense.web.dto.VettingChecklistDtos.AutomaticItemVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Proposed RED seam tests. All text is authored; no provider, original file, database or service. */
class VettingChecklistRequirementRelevanceRedTest {
    private static final String LEFT="The Tenderer shall submit two signed paper copies of the tender in the stated envelope.";
    private static final String RIGHT="The Tenderer shall submit three signed paper copies of the same tender in the same envelope.";

    @ParameterizedTest @ValueSource(strings={"APP-I-002","APP-I-007","APP-I-008"})
    void realSemanticPublicationKeepsTheCopiesFindingWithoutCertifyingAnUnrelatedRequirement(String id) {
        AiGateway ai=mock(AiGateway.class);when(ai.available()).thenReturn(true);when(ai.chatModel()).thenReturn("authored-only");
        ConsenseProperties props=new ConsenseProperties();props.getVetting().setSemanticTopics(1);props.getVetting().setProjectReferenceComparisons(0);
        VettingRetrievalClient retrieval=mock(VettingRetrievalClient.class);Chunk left=chunk("left","body/4/paragraph",LEFT),right=chunk("right","body/6/paragraph",RIGHT);
        List<Chunk> corpus=Arrays.asList(left,right);
        when(retrieval.retrieve(eq("authored-project"),anyString(),eq("tender"),anyList())).thenReturn(corpus);
        Record record=new Record();VettingCompetitionFixture.details(record);record.setAssessment("issue");record.setType("conflict");record.setSeverity("medium");
        record.setTitle("Two different submission copy counts");record.setComment("The two supplied tender provisions specify different copy counts.");
        record.setFactBasis("The same submission is described with two different paper-copy counts.");record.setPendingFacts(Collections.emptyList());record.setSuggestedAction("review");
        record.setEvidence(Arrays.asList(quote(left),quote(right)));
        ChecklistAssessment check=new ChecklistAssessment();check.setItemId(id);check.setStatus("not_fulfilled");check.setReason("The two paper-copy counts differ.");record.setChecklistAssessments(Collections.singletonList(check));
        String before=JsonUtils.write(record);
        when(ai.completeStructuredJsonList(anyString(),anyString(),eq(Record.class),any(),anyList())).thenReturn(Collections.singletonList(record));
        Result reviewed=VettingTokenFixture.review(ai,props,retrieval).review("authored-project",corpus,"en",(n,s)->{});
        assertEquals(1,reviewed.getCandidates().size(),"A bad Checklist association must not delete the located general conflict.");
        assertEquals(2,reviewed.getCandidates().get(0).getEvidence().size());assertEquals(before,JsonUtils.write(record),"Original model claim stays immutable.");
        assertFalse(reviewed.getCandidates().get(0).getBusinessDetails().getChecklistItemIds().contains(id),
                "Source-located copy counts do not certify this requirement's selection/conditional-works/appendix predicate.");
    }

    @ParameterizedTest @ValueSource(strings={"APP-I-002","APP-I-007","APP-I-008"})
    void actualAggregatorCannotPromoteTheSameUnsupportedCopiesClaimViaLegacyPacket(String id) {
        Packet fixture=new Packet(id);String before=JsonUtils.write(fixture.coverage);
        AutomaticItemVO projected=fixture.item();
        assertEquals("insufficient_evidence",projected.getStatus(),"A correct packet/evidence identity cannot prove a different requirement's predicate.");
        assertEquals(1,projected.getAssessments().size(),"Retain the complete model claim, reason and located evidence for review.");
        assertEquals("not_fulfilled",projected.getAssessments().get(0).path("status").asText());
        assertEquals(before,JsonUtils.write(fixture.coverage),"Legacy saved packet must not be migrated or rewritten during projection.");
    }

    @ParameterizedTest @ValueSource(strings={"APP-I-002","APP-I-007","APP-I-008"})
    void aCorrectRequirementHashAndModelSelfDeclarationDoNotSupplyMissingPredicateFacts(String id) {
        Packet fixture=new Packet(id);VettingChecklistCatalog.Item requirement=VettingChecklistCatalog.items().stream().filter(i->id.equals(i.getId())).findFirst().get();
        fixture.record.put("requirementSha256",VettingChecklistDependencies.requirementHash(requirement));
        fixture.record.put("relevant",true);fixture.record.put("requirementDescription",requirement.getDescription());
        assertEquals("insufficient_evidence",fixture.item().getStatus());
        assertEquals("not_fulfilled",fixture.record.path("status").asText());
    }

    @Test void actualCatalogueKeepsThreeDifferentRequirementsAndTheWholeDenominator() {
        assertEquals(69,VettingChecklistCatalog.items().size());assertEquals(44,VettingChecklistCatalog.logicalGroups().size());
        assertEquals(67,VettingChecklistCatalog.logicalGroups().stream().mapToInt(g->g.getSourceItemIds().size()).sum());
        assertTrue(VettingChecklistCatalog.logicalGroups().stream().filter(g->g.getId().equals("CK-002")).findFirst().get().getSourceItemIds().contains("APP-I-002"));
        assertEquals(Collections.singletonList("APP-I-007"),VettingChecklistCatalog.logicalGroups().stream().filter(g->g.getId().equals("CK-003")).findFirst().get().getSourceItemIds());
        assertEquals(Collections.singletonList("APP-I-008"),VettingChecklistCatalog.logicalGroups().stream().filter(g->g.getId().equals("CK-004")).findFirst().get().getSourceItemIds());
    }

    private static Chunk chunk(String id,String anchor,String text) {
        Chunk c=new Chunk();c.setId(id);c.setDocumentId("71");c.setFileName("Authored-SCT.docx");c.setFileKey("SCT");c.setContent(text);c.setRole("tender");c.setAnchor(anchor);
        c.setSourceHash(VettingCorpus.hash("authored parsed source"));c.setOriginalSourceSha256(VettingCorpus.hash("authored original bytes"));return c;
    }
    private static Quote quote(Chunk c){Quote q=new Quote();q.setChunkId(c.getId());q.setSide("source");q.setQuote(c.getContent());return q;}
    private static final class Packet {
        final ObjectNode coverage=JsonUtils.mapper().createObjectNode(),result=JsonUtils.mapper().createObjectNode(),record;
        final String id;
        Packet(String id){
            this.id=id;String policy=VettingCorpus.hash("authored frozen policy"),snapshot=VettingCorpus.hash("authored packet snapshot");
            result.putObject("promptPolicy").put("sha256",policy);
            ObjectNode packet=coverage.putArray("semanticTopics").addObject().putArray("packetAudits").addObject();
            packet.put("packetId","authored-packet").put("sourceSnapshotSha256",snapshot).put("actualGatewayCallStarted",true).put("status","completed");
            packet.putArray("submittedChunkIds").add("left").add("right");record=packet.putArray("checklistAssessments").addObject();
            record.put("itemId",id).put("status","not_fulfilled").put("reason","The two paper-copy counts differ.").put("issueTypeCode","CONF")
                    .put("producerVersion","pm-checklist-packet-v1").put("policySha256",policy).put("packetId","authored-packet").put("sourceSnapshotSha256",snapshot);
            record.putArray("evidenceChunkIds").add("left").add("right");ArrayNode evidence=record.putArray("evidence");int at=0;
            for(String text:Arrays.asList(LEFT,RIGHT))evidence.addObject().put("side","source").put("documentId","71").put("fileKey","SCT").put("anchor",++at==1?"body/4/paragraph":"body/6/paragraph")
                    .put("quote",text).put("located",true).put("sourceRole","tender").put("sourceHash",VettingCorpus.hash("authored parsed source"))
                    .put("originalSourceSha256",VettingCorpus.hash("authored original bytes")).put("packetId","authored-packet").put("packetSourceSnapshotSha256",snapshot);
        }
        AutomaticItemVO item(){return VettingChecklistAutomaticResults.aggregate("COMPLETED",coverage,result).stream().flatMap(g->g.getSubItemAssessments().stream()).filter(r->id.equals(r.getItemId())).findFirst().get();}
    }
}
