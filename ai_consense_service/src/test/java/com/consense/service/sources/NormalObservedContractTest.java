package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NormalObservedContractTest {
    static String resource(String name)throws Exception{return new String(NormalObservedContractTest.class.getResourceAsStream("/canonical/normal-observed/"+name).readAllBytes(),StandardCharsets.UTF_8);}
    @Test void frozenActualPythonProjectionPreservesFloatsWholeTableEmojiAndEmptyUnit()throws Exception{
        String raw=resource("authored-projection.json");JsonNode p=JsonUtils.parse(raw);
        assertEquals(p,CanonicalEvidenceService.validateSnapshot(raw,p.path("projectionSha256").asText()));
        assertEquals(3,p.path("canonical").path("units").size());assertEquals(2,p.path("chunks").size());assertEquals(1,p.path("nonRetrievableUnits").size());
        assertEquals("no_observed_text",p.path("nonRetrievableUnits").get(0).path("reason").asText());
        NormalObservedContract.validateCapability(JsonUtils.parse(resource("authored-health.json")).path("oer"));
        String receipt="{\"projection\":"+raw+",\"chunks\":2}";
        assertEquals(raw,CanonicalJson.field(receipt,"projection"));
    }
    @Test void recomputingOuterSealCannotGrantQualityOrDropACompleteMember()throws Exception{
        JsonNode original=JsonUtils.parse(resource("authored-projection.json"));
        for(String defect:new String[]{"quality","member","unknown","empty","position","content","routing","adoption"}){
            ObjectNode p=(ObjectNode)original.deepCopy();
            switch(defect){
                case "quality":p.put("sourceQualityAccepted",true);break;
                case "member":((com.fasterxml.jackson.databind.node.ArrayNode)p.path("canonical").path("units")).remove(0);break;
                case "unknown":p.putArray("unknowns");break;
                case "empty":p.putArray("nonRetrievableUnits");break;
                case "position":((ObjectNode)p.path("chunks").get(0).path("parts").get(0)).put("physicalPageNo",0);break;
                case "content":((ObjectNode)p.path("chunks").get(0)).put("content","fabricated text");break;
                case "routing":((ObjectNode)p.path("projectionIdentity")).put("sourcePurpose","evaluation_answer");break;
                default:p.putObject("adoption").put("reviewStatus","explicit_source_reviewed_selection");
            }
            p.put("projectionSha256",CanonicalJson.hash(JsonUtils.write(p),"projectionSha256"));String raw=JsonUtils.write(p);
            assertThrows(CanonicalEvidenceException.class,()->CanonicalEvidenceService.validateSnapshot(raw,p.path("projectionSha256").asText()),defect);
        }
    }
    @Test void duplicateKeysAndOversizedSnapshotsFailClosed()throws Exception{
        String raw=resource("authored-projection.json");assertThrows(CanonicalEvidenceException.class,()->CanonicalJson.field("{\"projection\":"+raw+",\"projection\":{}}","projection"));
        assertThrows(CanonicalEvidenceException.class,()->NormalObservedContract.validate(" ".repeat(1025),"0".repeat(64),1024));
    }
    @Test void blankUnitDecisionMatchesPythonStripIncludingNonbreakingSpaces(){
        for(String blank:new String[]{""," \t\r\n","\u00a0","\u2007","\u202f","\u0085","\u001c\u001f"})assertTrue(NormalObservedContract.noObservedText(blank));
        for(String literal:new String[]{"\u200b","\u00a0actual\u202f","0"})assertFalse(NormalObservedContract.noObservedText(literal));
    }
    @Test void normalCapabilityAndConfiguredSnapshotCapRemainFiniteAndTyped()throws Exception{
        ObjectNode oer=(ObjectNode)JsonUtils.parse(resource("authored-health.json")).path("oer").deepCopy();
        ObjectNode capability=(ObjectNode)oer.path("normalObserved");
        capability.put("maximumMessageBytes",NormalObservedContract.MAX_BYTES);
        assertDoesNotThrow(()->NormalObservedContract.validateCapability(oer));
        for(String value:new String[]{"true","1.5","0","-1","2147483648","1073741825"}){
            capability.set("maximumMessageBytes",JsonUtils.parse(value));
            assertThrows(CanonicalEvidenceException.class,()->NormalObservedContract.validateCapability(oer));
        }
        String raw=resource("authored-projection.json");String address=JsonUtils.parse(raw).path("projectionSha256").asText();
        int bytes=raw.getBytes(StandardCharsets.UTF_8).length;
        assertEquals(JsonUtils.parse(raw),CanonicalEvidenceService.validateSnapshot(raw,address,bytes));
        assertEquals("NORMAL_OBSERVED_TOO_LARGE",assertThrows(CanonicalEvidenceException.class,()->
            CanonicalEvidenceService.validateSnapshot(raw,address,bytes-1)).getMessage());
    }

}
