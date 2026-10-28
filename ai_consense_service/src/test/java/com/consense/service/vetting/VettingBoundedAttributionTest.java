package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.web.dto.VettingDtos.SemanticPacketVO;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Compact authored reproductions of bounded checklist questions; not source or model acceptance. */
class VettingBoundedAttributionTest {
    @Test void fiveBoundedMissingEvidenceRecordsDoNotAssertClauseOwnership() {
        VettingSourceAttributionRepairTest.Fixture f=new VettingSourceAttributionRepairTest.Fixture();
        for(ObjectNode record:bounded(f))assertTrue(reasons(f,record).isEmpty(),record.toString());
    }

    @Test void mixedUncertaintyStillRejectsItsSeparateAffirmativeOwnershipClaim() {
        VettingSourceAttributionRepairTest.Fixture f=new VettingSourceAttributionRepairTest.Fixture();
        ObjectNode record=pending(f,"Without these passages, it is impossible to verify whether the plant listed in sub-clauses 1, 8 and 10 is consistent with the appendices.");
        reason(record,"APP-I-034","Only sub-clause 1 is available; sub-clauses 8 and 10 are missing from the packet.");
        List<Map<String,Object>> failures=reasons(f,record);
        assertEquals(1,failures.size());assertEquals("checklistAssessments[0].reason",failures.get(0).get("field"));assertEquals("1",failures.get(0).get("clauseId"));
    }

    @Test void unsupportedPositiveAssertionsStayRejectedInEveryNarrativeField() {
        VettingSourceAttributionRepairTest.Fixture f=new VettingSourceAttributionRepairTest.Fixture();
        for(String field:Arrays.asList("title","comment","impact","suggestion","factBasis","pendingFacts","checklistAssessments")) {
            ObjectNode record=pending(f,"The quoted source does not establish the requested comparison.");
            if("pendingFacts".equals(field))record.putArray(field).add("GCC Clause 5.7 requires a different deadline.");
            else if("checklistAssessments".equals(field))reason(record,"APP-I-017","GCC Clause 5.7 requires a different deadline.");
            else record.put(field,"GCC Clause 5.7 requires a different deadline.");
            assertFalse(reasons(f,record).isEmpty(),field);
        }
    }

    @Test void inquiryFramingDoesNotHideALaterIndependentAssertion() {
        VettingSourceAttributionRepairTest.Fixture f=new VettingSourceAttributionRepairTest.Fixture();
        for(String text:Arrays.asList(
                "The checklist asks whether PRE.B6.050 changes the deadline, but GCC Clause 5.7 requires payment.",
                "The checklist requires confirming PRE.B6.050; GCC Clause 5.7 requires payment.",
                "Neither PRE.B6.050 nor the text of Clause 1 is present, but Clause 2 requires payment.",
                "It is impossible to verify whether Clause 2 applies, but Clause 99 requires a bond.",
                "GCC Clause 5.7 has not been searched but requires a bond.",
                "GCC Clause 5.7 has not been searched and requires a bond.",
                "GCC Clause 5.7 has not been searched and it requires a bond.",
                "GCC Clause 5.7 has not been searched or requires a bond.",
                "GCC Clause 5.7 has not been searched yet requires a bond.",
                "It is impossible to verify whether Clause 2 applies, yet Clause 99 requires a bond.",
                "The checklist asks whether Clause 2 applies, and Clause 99 requires a bond.",
                "The checklist asks whether Clause 2 applies, and PRE.B11.010 sub-clause 99 requires a bond.",
                "The checklist asks whether PRE.B6.050 changes the deadline. GCC Clause 5.7 requires payment."))
            assertFalse(reasons(f,pending(f,text)).isEmpty(),text);
    }

    @Test void boundedMissingAndNotYetSearchedTargetsRemainPending() {
        VettingSourceAttributionRepairTest.Fixture f=new VettingSourceAttributionRepairTest.Fixture();
        for(String text:Arrays.asList("GCC Clause 5.7 has not yet been searched.","Sub-clauses 8 and 10 are missing from this packet.","Clause 2 is absent from the supplied excerpts."))
            assertTrue(reasons(f,pending(f,text)).isEmpty(),text);
    }

    @Test void newBoundedExemptionsAreLimitedToInsufficientContext() {
        VettingSourceAttributionRepairTest.Fixture f=new VettingSourceAttributionRepairTest.Fixture();
        for(String assessment:Arrays.asList("issue","consistent"))for(ObjectNode record:bounded(f)) {
            record.put("assessment",assessment);assertFalse(reasons(f,record).isEmpty(),record.toString());
        }
    }

    @Test void aFullStopAfterADottedSourceIdentifierStillEndsTheSentence() {
        VettingSourceAttributionRepairTest.Fixture f=new VettingSourceAttributionRepairTest.Fixture();
        ObjectNode record=pending(f,"The checklist asks whether the programme complies with PRE.B6.050. Clause 9 requires payment.");
        assertEquals("9",reasons(f,record).get(0).get("clauseId"));
    }

    @Test void acceptedPendingRecordsRetainTheirNeedsAndMixedAffirmativeRejection() {
        VettingSourceAttributionRepairTest.Fixture f=new VettingSourceAttributionRepairTest.Fixture();
        List<ObjectNode> records=bounded(f);ObjectNode bad=pending(f,"The relevant comparison remains unverified.");
        reason(bad,"APP-I-034","Only sub-clause 1 is available; the other required texts are missing.");records.add(bad);
        String raw=JsonUtils.write(records);VettingSemanticReview.Result result=f.review(raw);SemanticPacketVO packet=packet(result);
        assertEquals(1,result.getActualGatewayCallCount());assertEquals(5,packet.getVerifiedAssessments().size());
        assertEquals(1,packet.getRejectedRecords());assertTrue(result.getCandidates().isEmpty());assertTrue(result.getReviewedChunkIds().isEmpty());
        assertTrue(packet.getVerifiedAssessments().stream().allMatch(r->"insufficient_context".equals(r.getAssessment())&&!r.getPendingFacts().isEmpty()));
        List<String> ids=Arrays.asList("APP-I-017","APP-I-018","APP-I-040","APP-I-052","APP-I-053");
        List<VettingSemanticReview.EvidenceNeed> needs=VettingSemanticReview.directionEvidenceNeeds(ids,packet,f.corpus);
        for(int i=0;i<5;i++){
            final String itemId=ids.get(i),query=records.get(i).path("pendingFacts").get(0).asText();
            assertTrue(needs.stream().anyMatch(n->query.equals(n.query)&&n.itemIds.contains(itemId)),itemId+" retains its named evidence request");
        }
        assertEquals(f.wire(raw),packet.getAttempts().get(0).getRawResponse());
        assertTrue(packet.getAttempts().get(0).getValidationReason().contains("\"recordIndex\":5"));
    }

    @Test void aBoundedQuestionStillCannotRetainAnInventedQuotation() {
        VettingSourceAttributionRepairTest.Fixture f=new VettingSourceAttributionRepairTest.Fixture();
        ObjectNode bad=bounded(f).get(0);bad.putArray("evidence").addObject().put("chunkId",f.a.getId()).put("side","source").put("quote","This authored quotation is absent from the supplied sources.");
        VettingSemanticReview.Result result=f.review(JsonUtils.write(Collections.singletonList(bad)));
        assertTrue(packet(result).getVerifiedAssessments().isEmpty());assertTrue(result.getCandidates().isEmpty());assertEquals(1,packet(result).getRejectedRecords());
    }

    private static List<ObjectNode> bounded(VettingSourceAttributionRepairTest.Fixture f) {
        List<ObjectNode> records=new ArrayList<>();
        records.add(pending(f,"The checklist asks whether PRE.B6.050 specifies a different time frame under GCC Clause 5.7, and if so whether the acceptance letter reflects it. GCC Clause 5.7 has not been searched."));
        records.add(pending(f,"The checklist requires confirming that members listed in PRE.B6.060 sub-clause 1 are under direct employment. The actual lists are not supplied."));
        records.add(pending(f,"The checklist requires ensuring that if the entrusted works are included, PRE.B8.4030 sub-clause 2(c)(ii) has been adopted. The source heading does not establish adoption."));
        for(int clause:Arrays.asList(2,1)) {
            ObjectNode record=pending(f,"The two required lists are not supplied; their consistency cannot be assessed.");
            reason(record,clause==2?"APP-I-052":"APP-I-053","Neither the list under PRE.B12.720 nor the content of PRE.B11.010 sub-clause "+clause+" is present in the supplied excerpts; comparison cannot be performed.");records.add(record);
        }
        List<String> ids=Arrays.asList("APP-I-017","APP-I-018","APP-I-040");
        for(int i=0;i<3;i++)reason(records.get(i),ids.get(i),"The named comparison cannot be verified from the supplied passages.");
        List<String> facts=Arrays.asList("Retrieve PRE.B6.050 and GCC Clause 5.7 for programme submission.","Retrieve PRE.B6.060 sub-clause 1 and both personnel lists.","Retrieve PRE.B8.4030 sub-clause 2(c)(ii) to verify adoption.","Retrieve PRE.B11.010 sub-clause 2 and PRE.B12.720 for comparison.","Retrieve PRE.B11.010 sub-clause 1 and PRE.B12.720 for comparison.");
        for(int i=0;i<records.size();i++)records.get(i).putArray("pendingFacts").add(facts.get(i));
        return records;
    }
    private static ObjectNode pending(VettingSourceAttributionRepairTest.Fixture f,String comment) {
        ObjectNode record=f.record(false);record.put("assessment","insufficient_context");record.put("type","reference");
        record.put("title","Required clause comparison remains unverified");record.put("comment",comment);record.put("subDimension","");record.put("factBasis","");
        record.putArray("pendingFacts").add("Provide the full named clause text to verify the comparison.");return record;
    }
    private static void reason(ObjectNode record,String item,String text) {
        record.putArray("checklistAssessments").addObject().put("itemId",item).put("status","insufficient_evidence").put("reason",text).putNull("support");
    }
    private static List<Map<String,Object>> reasons(VettingSourceAttributionRepairTest.Fixture f,ObjectNode record) {
        Map<String,Chunk> byId=new LinkedHashMap<>();for(Chunk chunk:f.corpus)byId.put(chunk.getId(),chunk);
        return VettingSourceAttribution.reasons(JsonUtils.readList(JsonUtils.write(Collections.singletonList(record)),VettingSemanticReview.Record.class),byId);
    }
    private static SemanticPacketVO packet(VettingSemanticReview.Result result){return result.getTopicAudits().get(0).getPacketAudits().get(0);}
}
