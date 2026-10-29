package com.consense.service.vetting;

import com.consense.ai.AiGateway;
import com.consense.ai.StructuredOutputValidationException;
import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.web.dto.VettingDtos.FindingEvidence;
import com.consense.web.dto.VettingDtos.SemanticPacketVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Authored outputs exercise actual schema and source gates, not provider or legal acceptance. */
class VettingShortQuotationTest {
    private static final String SHORT="retained.";

    @Test void nineCharacterLiteralDoesNotPoisonAnotherSchemaValidRecord() {
        VettingBusinessShapeRepairTest.Fixture f=new VettingBusinessShapeRepairTest.Fixture();
        ArrayNode rows=(ArrayNode)JsonUtils.parse(f.raw("review",false,SHORT));
        rows.add(JsonUtils.parse(f.raw("review",false,f.source.getContent())).get(0));
        JsonNode schema=VettingOutputSchema.forChunkIds(Collections.singleton("R1"));
        for(boolean wrapper:new boolean[]{false,true}) {
            String raw=wrapper?JsonUtils.write(Collections.singletonMap("records",rows)):JsonUtils.write(rows);
            List<VettingSemanticReview.Record> decoded=AiGateway.decodeStructuredJson(raw,VettingSemanticReview.Record.class,schema,wrapper);
            assertEquals(2,decoded.size());assertEquals(9,decoded.get(0).getEvidence().get(0).getQuote().length());
            assertEquals(SHORT,decoded.get(0).getEvidence().get(0).getQuote());
            assertEquals(f.source.getContent(),decoded.get(1).getEvidence().get(0).getQuote());
        }
    }

    @Test void actualReceivingCallerPublishesTheLocatedShortLiteralWithoutRepair() {
        VettingBusinessShapeRepairTest.Fixture f=new VettingBusinessShapeRepairTest.Fixture();
        String raw=f.raw("review",false,SHORT);
        VettingSemanticReview.Result result=f.review(f.wire(raw,raw));
        assertEquals(1,result.getActualGatewayCallCount());assertEquals(1,result.getCandidates().size());
        String system=packet(result).getAttempts().get(0).getInput().path("messages").get(0).path("content").asText();
        assertTrue(system.contains("1–16 necessary evidence entries with nonblank exact quotations of up to 1200 characters from supplied passages."),"The actual receiving request must agree with the nonblank short-quote schema and source gates");
        FindingEvidence evidence=result.getCandidates().get(0).getEvidence().get(0);
        assertTrue(evidence.isLocated());assertEquals(SHORT,evidence.getQuote());
        assertEquals(f.source.getDocumentId(),evidence.getDocumentId());assertEquals(f.source.getAnchor(),evidence.getAnchor());
        assertEquals(f.source.getSourceHash(),evidence.getSourceHash());assertEquals(raw,packet(result).getAttempts().get(0).getRawResponse());
    }

    @Test void evidenceOnlyRepairMergesTheSameLocatedShortLiteral() {
        VettingBusinessShapeRepairTest.Fixture f=new VettingBusinessShapeRepairTest.Fixture();
        ArrayNode original=(ArrayNode)JsonUtils.parse(f.raw("review",false,SHORT));
        ((ObjectNode)original.get(0)).remove("evidence");String first=JsonUtils.write(original);
        String patch=VettingEvidenceRepairTest.patch(0,"R1",SHORT);
        VettingSemanticReview.Result result=f.review(f.wire(first,patch));
        assertEquals(2,result.getActualGatewayCallCount());assertEquals(1,result.getCandidates().size());
        FindingEvidence evidence=result.getCandidates().get(0).getEvidence().get(0);
        assertTrue(evidence.isLocated());assertEquals(SHORT,evidence.getQuote());assertEquals(f.source.getAnchor(),evidence.getAnchor());
        assertEquals(first,packet(result).getAttempts().get(0).getRawResponse());
        assertEquals(patch,packet(result).getAttempts().get(1).getRawResponse());
        assertTrue(packet(result).getAttempts().get(1).getInput().path("schema").path("items").path("properties").has("recordIndex"));
    }

    @Test void wrongShortLiteralCannotPassTheActualSourceGate() {
        VettingBusinessShapeRepairTest.Fixture f=new VettingBusinessShapeRepairTest.Fixture();
        String raw=f.raw("review",false,"invented.");
        assertFalse(VettingCorpus.evidence(f.source,"source","invented.").isLocated());
        VettingSemanticReview.Result result=f.review(f.wire(raw,raw));
        assertEquals(1,result.getActualGatewayCallCount());assertTrue(result.getCandidates().isEmpty());
        assertEquals(1,packet(result).getRejectedRecords());assertEquals(raw,packet(result).getAttempts().get(0).getRawResponse());
    }

    @Test void blankQuotesCannotPassOrdinaryOrRepairSourceMembership() {
        for(String blank:Arrays.asList(""," ","\n\t","\u00a0\u2003")) {
            VettingBusinessShapeRepairTest.Fixture f=new VettingBusinessShapeRepairTest.Fixture();
            assertFalse(VettingCorpus.evidence(f.source,"source",blank).isLocated());
            ArrayNode original=(ArrayNode)JsonUtils.parse(f.raw("review",false,SHORT));((ObjectNode)original.get(0)).remove("evidence");
            VettingSemanticReview.Result result=f.review(f.wire(JsonUtils.write(original),VettingEvidenceRepairTest.patch(0,"R1",blank)));
            assertEquals(2,result.getActualGatewayCallCount());assertTrue(result.getCandidates().isEmpty());
        }
    }

    @Test void shortPacketCitationStillNeedsExactUniqueSourceOwnership() {
        VettingBusinessShapeRepairTest.Fixture f=new VettingBusinessShapeRepairTest.Fixture();
        f.source.setClauseId("SCT1");f.source.setClauseHeadingLocation("body/heading/SCT1");
        ObjectNode record=(ObjectNode)JsonUtils.parse(f.raw("review",false,SHORT)).get(0);
        record.put("comment","SCT Clause 1 (R1) requires the record to be retained.");
        ((ObjectNode)record.path("evidence").get(0)).put("chunkId",f.source.getId());
        List<VettingSemanticReview.Record> decoded=AiGateway.decodeStructuredJson("["+record+"]",VettingSemanticReview.Record.class,
                VettingOutputSchema.forChunkIds(Collections.singleton(f.source.getId())),false);
        assertTrue(attribution(f.source,decoded).isEmpty());
        f.source.setContent(f.source.getContent()+" "+SHORT);
        assertFalse(attribution(f.source,decoded).isEmpty(),"A repeated literal cannot establish packet-local clause ownership");
        f.source.setContent("This different source does not contain the quoted wording.");
        assertFalse(attribution(f.source,decoded).isEmpty(),"A missing literal cannot establish packet-local clause ownership");
    }

    @Test void requiredTypeEmptyAndMaximumQuoteGuardsRemainOnTheProductionSchema() {
        VettingBusinessShapeRepairTest.Fixture f=new VettingBusinessShapeRepairTest.Fixture();
        JsonNode schema=VettingOutputSchema.forChunkIds(Collections.singleton("R1"));
        for(String kind:Arrays.asList("empty","missing","null","numeric","too_long")) {
            ArrayNode rows=(ArrayNode)JsonUtils.parse(f.raw("review",false,SHORT));ObjectNode evidence=(ObjectNode)rows.get(0).path("evidence").get(0);
            if("empty".equals(kind))evidence.put("quote","");
            if("missing".equals(kind))evidence.remove("quote");
            if("null".equals(kind))evidence.putNull("quote");
            if("numeric".equals(kind))evidence.put("quote",9);
            if("too_long".equals(kind))evidence.put("quote",String.join("",Collections.nCopies(1201,"x")));
            assertThrows(StructuredOutputValidationException.class,()->AiGateway.decodeStructuredJson(JsonUtils.write(rows),VettingSemanticReview.Record.class,schema,false),kind);
        }
        assertEquals(1,AiGateway.decodeStructuredJson(f.raw("review",false,"x"),VettingSemanticReview.Record.class,schema,false).size());
        assertEquals(1,AiGateway.decodeStructuredJson(f.raw("review",false,String.join("",Collections.nCopies(1200,"x"))),VettingSemanticReview.Record.class,schema,false).size());
    }

    private static List<Map<String,Object>> attribution(Chunk source,List<VettingSemanticReview.Record> records) {
        return VettingSourceAttribution.reasons(records,Collections.singletonMap(source.getId(),source),Collections.emptyMap(),Collections.singletonMap("R1",source.getId()));
    }
    private static SemanticPacketVO packet(VettingSemanticReview.Result result){return result.getTopicAudits().get(0).getPacketAudits().get(0);}
}
