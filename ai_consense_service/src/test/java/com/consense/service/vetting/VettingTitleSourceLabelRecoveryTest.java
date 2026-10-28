package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingSemanticReview.Record;
import com.consense.web.dto.VettingDtos.SemanticPacketVO;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Authored copies of the observed title failure exercise the actual receiving seam, not live-model accuracy. */
class VettingTitleSourceLabelRecoveryTest {
    private static final String TEXT="8. Allow for all additional time and cost for compliance with this clause. No claim for extension of time or additional cost will be entertained.";
    private static final String TITLE="PRE Clause 8 contains a blanket exclusion of extension of time and cost claims";

    @Test void observedTitleLabelIsRecoveredWithoutRegeneratingOrDroppingItsPendingSibling() {
        VettingSourceAttributionRepairTest.Fixture f=fixture();ObjectNode issue=issue(f),pending=pending(f);
        String raw=JsonUtils.write(Arrays.asList(issue,pending));VettingSemanticReview.Result result=f.review(raw);
        SemanticPacketVO packet=result.getTopicAudits().get(0).getPacketAudits().get(0);
        assertEquals(1,result.getActualGatewayCallCount());assertEquals(1,result.getCandidates().size());assertEquals(0,packet.getRejectedRecords());
        assertEquals("PRE passage contains a blanket exclusion of extension of time and cost claims",result.getCandidates().get(0).getTitle());
        assertEquals(TEXT,result.getCandidates().get(0).getEvidence().get(0).getQuote());
        assertEquals(issue.path("comment").asText(),result.getCandidates().get(0).getComment());
        assertEquals(1,packet.getVerifiedInsufficientContextAssessments());
        assertEquals(f.wire(raw),packet.getAttempts().get(0).getRawResponse());
        assertEquals(TITLE,JsonUtils.parse(packet.getAttempts().get(0).getRawResponse()).get(0).path("title").asText());
        assertEquals(VettingCorpus.hash(f.wire(raw)),packet.getAttempts().get(0).getRawResponseSha256());
        assertTrue(result.getWarnings().stream().anyMatch(w->w.contains("unverified clause number")));
    }

    @Test void inventedOrNonUniqueQuotationCannotAuthorizeTitleRecovery() {
        for(boolean duplicate:Arrays.asList(false,true)) {
            VettingSourceAttributionRepairTest.Fixture f=fixture();ObjectNode issue=issue(f);
            if(duplicate)f.replace(f.a,TEXT+"\n"+TEXT);
            else ((ObjectNode)issue.path("evidence").get(0)).put("quote","The Contractor shall receive every claimed extension without review.");
            assertUnchanged(f,issue);
        }
    }

    @Test void anotherDocumentOrMultipleQuotedSourcesCannotAuthorizeTitleRecovery() {
        for(String mutation:Arrays.asList("another_family","wrong_alias","two_sources","reference")) {
            VettingSourceAttributionRepairTest.Fixture f=fixture();ObjectNode issue=issue(f);
            if("another_family".equals(mutation)){f.a.setFileName("10 - SCC.docx");f.a.setFileKey("PRE");}
            if("wrong_alias".equals(mutation))((ObjectNode)issue.path("evidence").get(0)).put("chunkId",f.b.getId());
            if("two_sources".equals(mutation))issue.withArray("evidence").addObject().put("chunkId",f.b.getId()).put("side","source").put("quote",f.b.getContent());
            if("reference".equals(mutation))((ObjectNode)issue.path("evidence").get(0)).put("side","reference");
            assertUnchanged(f,issue);
        }
    }

    @Test void UnsupportedSubstantiveAttributionOutsideTheTitleStillRejectsTheRecord() {
        VettingSourceAttributionRepairTest.Fixture f=fixture();ObjectNode issue=issue(f);issue.put("comment","PRE Clause 8 requires the Contractor to waive all contractual claims.");
        assertUnchanged(f,issue);
        String raw=JsonUtils.write(Arrays.asList(issue,pending(f)));VettingSemanticReview.Result result=f.review(raw);
        assertEquals(1,result.getActualGatewayCallCount());assertTrue(result.getCandidates().isEmpty());
        assertEquals(1,result.getTopicAudits().get(0).getPacketAudits().get(0).getRejectedRecords());
    }

    @Test void VerifiedLocatorAndMultipleOrBoundCitationLabelsAreNotRewritten() {
        for(String title:Arrays.asList("PRE Clause 8 and Clause 9 contain inconsistent obligations","PRE Clause 8 (R2) contains an exclusion")) {
            VettingSourceAttributionRepairTest.Fixture f=fixture();ObjectNode issue=issue(f);issue.put("title",title);assertUnchanged(f,issue);
        }
        VettingSourceAttributionRepairTest.Fixture f=fixture();f.a.setClauseId("PRE8");f.a.setClauseHeadingLocation("AUTHORED verified heading");
        List<Record> records=records(issue(f));Map<String,Chunk> sources=sources(f);
        assertTrue(VettingSourceAttribution.reasons(records,sources).isEmpty());
        assertTrue(VettingSourceAttribution.recoverTitleLabels(records,sources,Collections.emptyList()).isEmpty());assertEquals(TITLE,records.get(0).getTitle());
    }

    private void assertUnchanged(VettingSourceAttributionRepairTest.Fixture f,ObjectNode issue) {
        List<Record> records=records(issue);String before=JsonUtils.write(records);Map<String,Chunk> sources=sources(f);
        List<Map<String,Object>> reasons=VettingSourceAttribution.reasons(records,sources);assertFalse(reasons.isEmpty());
        assertTrue(VettingSourceAttribution.recoverTitleLabels(records,sources,reasons).isEmpty());assertEquals(before,JsonUtils.write(records));
    }
    private static List<Record> records(ObjectNode issue){return JsonUtils.readList(JsonUtils.write(Collections.singletonList(issue)),Record.class);}
    private static Map<String,Chunk> sources(VettingSourceAttributionRepairTest.Fixture f){Map<String,Chunk> out=new LinkedHashMap<>();for(Chunk c:f.corpus)out.put(c.getId(),c);return out;}
    private static VettingSourceAttributionRepairTest.Fixture fixture(){VettingSourceAttributionRepairTest.Fixture f=new VettingSourceAttributionRepairTest.Fixture();f.a.setFileName("25 - Specification - PRE (base tender).docx");f.a.setFileKey("PRE");f.replace(f.a,TEXT);return f;}
    private static ObjectNode issue(VettingSourceAttributionRepairTest.Fixture f){
        ObjectNode issue=f.record(false);issue.put("type","risk");issue.put("title",TITLE);
        issue.put("comment","The quoted PRE passage excludes all claims for extension of time or additional cost; clarify its relationship with the Conditions of Contract.");
        issue.put("impact","The exclusion may create ambiguity over contractual entitlements.");issue.put("suggestion","Review the blanket exclusion against the Conditions of Contract.");
        issue.put("subDimension","Time and cost entitlement");issue.put("factBasis","The quoted source uses the blanket exclusion identified by APP-I-006.");issue.put("suggestedAction","review");
        issue.putArray("evidence").addObject().put("chunkId",f.a.getId()).put("side","source").put("quote",TEXT);
        issue.putArray("checklistAssessments").addObject().put("itemId","APP-I-006").put("status","not_fulfilled").put("reason","The tender passage contains a blanket time and cost claim exclusion.").putNull("support");return issue;
    }
    private static ObjectNode pending(VettingSourceAttributionRepairTest.Fixture f){
        ObjectNode pending=f.record(false);pending.put("assessment","insufficient_context");pending.put("type","requirement");pending.put("title","Separate source scope remains unconfirmed");
        pending.put("comment","The quoted paper-copy requirement needs its matching project context.");pending.put("subDimension","");pending.put("factBasis","");pending.put("suggestedAction","review");
        pending.putArray("evidence").addObject().put("chunkId",f.b.getId()).put("side","source").put("quote",f.b.getContent());return pending;
    }
}
