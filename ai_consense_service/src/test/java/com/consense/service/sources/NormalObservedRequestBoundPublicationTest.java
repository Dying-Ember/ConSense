package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The transport may omit request-owned trees only if the complete unchanged v1 seal reconstructs. */
class NormalObservedRequestBoundPublicationTest {
    @Test void optionalCapabilityUsesTheNewFormatOnlyWhenExplicitlyAdvertised(){
        for(String health:new String[]{"{}","{\"normalObserved\":{}}","{\"normalObserved\":{\"publicationResponseFormats\":true}}",
            "{\"normalObserved\":{\"publicationResponseFormats\":[null,true,\"unknown\"]}}"})assertFalse(NormalObservedContract.requestBoundPublication(JsonUtils.parse(health)));
        assertTrue(NormalObservedContract.requestBoundPublication(JsonUtils.parse("{\"normalObserved\":{\"publicationResponseFormats\":[\""+NormalObservedContract.REQUEST_BOUND_FORMAT+"\"]}}")));
    }
    @Test void restoresTheExactFrozenPythonProjectionAndValidatesWithoutCloningRequestObservations()throws Exception{
        String expected=NormalObservedContractTest.resource("authored-projection.json");JsonNode full=JsonUtils.parse(expected);
        assertEquals(expected,CanonicalJson.nodeWire(full));
        JsonNode graph=full.path("manifest").path("candidateGraph"),binding=full.path("manifest").path("sourceBinding");
        NormalObservedContract.Publication actual=NormalObservedContract.publication(receipt(compact(full),NormalObservedContract.REQUEST_BOUND_FORMAT),graph,binding,true);
        assertTrue(actual.raw.startsWith("{\"schema\":\""+NormalObservedContract.SCHEMA+"\",\"producerKind\":\""+NormalObservedContract.PRODUCER+"\","));
        assertEquals(CanonicalJson.hash(expected,null),CanonicalJson.hash(actual.raw,null));assertEquals(full,actual.projection);
        assertSame(graph,actual.projection.path("manifest").path("candidateGraph"));assertSame(graph.path("units"),actual.projection.path("canonical").path("units"));
        assertEquals(full,NormalObservedContract.validate(actual.raw,full.path("projectionSha256").asText(),NormalObservedContract.MAX_BYTES,actual.projection));
        NormalObservedContract.Publication old=NormalObservedContract.publication(receipt(full,null),graph,binding,false);
        assertEquals(full,old.projection);assertEquals(expected,old.raw);
    }
    @Test void restoredPublicationKeepsItsAuthorityHeaderInsideTheBoundedDisplayRead()throws Exception{
        JsonNode full=JsonUtils.parse(NormalObservedContractTest.resource("authored-projection.json"));
        NormalObservedContract.Publication restored=NormalObservedContract.publication(receipt(compact(full),NormalObservedContract.REQUEST_BOUND_FORMAT),
            full.path("manifest").path("candidateGraph"),full.path("manifest").path("sourceBinding"),true);
        assertTrue(restored.raw.length()>16384,"The actual frozen publication must exceed the display header budget");
        CanonicalDisplayHeaderTest.Counted reader=new CanonicalDisplayHeaderTest.Counted(restored.raw);
        assertTrue(CanonicalEvidenceService.normalDisplayHeader(reader));assertTrue(reader.characters<=16384);
    }
    @Test void missingUnknownUnrequestedAndChangedReferencesCannotSelectCallerData()throws Exception{
        JsonNode full=JsonUtils.parse(NormalObservedContractTest.resource("authored-projection.json"));
        JsonNode graph=full.path("manifest").path("candidateGraph"),binding=full.path("manifest").path("sourceBinding");
        for(String format:new String[]{null,"unknown-format"})assertThrows(CanonicalEvidenceException.class,()->NormalObservedContract.publication(receipt(compact(full),format),graph,binding,true));
        assertThrows(CanonicalEvidenceException.class,()->NormalObservedContract.publication(receipt(compact(full),NormalObservedContract.REQUEST_BOUND_FORMAT),graph,binding,false));
        assertThrows(CanonicalEvidenceException.class,()->NormalObservedContract.publication(receipt(full,NormalObservedContract.REQUEST_BOUND_FORMAT),graph,binding,true));
        for(String change:new String[]{"manifest","canonical","extra"}){
            ObjectNode wire=compact(full);
            if("manifest".equals(change))((ObjectNode)wire.path("manifest")).put("$requestManifest","0".repeat(64));
            else if("canonical".equals(change))((ObjectNode)wire.path("canonical")).put("$requestCanonical","0".repeat(64));
            else ((ObjectNode)wire.path("manifest")).put("unknown",true);
            assertThrows(CanonicalEvidenceException.class,()->NormalObservedContract.publication(receipt(wire,NormalObservedContract.REQUEST_BOUND_FORMAT),graph,binding,true));
        }
    }
    @Test void changedCallerGraphBindingAndChunkStillFailTheCompleteProjectionSeal()throws Exception{
        JsonNode full=JsonUtils.parse(NormalObservedContractTest.resource("authored-projection.json"));
        for(String change:new String[]{"graph","binding","chunk"}){
            ObjectNode graph=(ObjectNode)full.path("manifest").path("candidateGraph").deepCopy();
            ObjectNode binding=(ObjectNode)full.path("manifest").path("sourceBinding").deepCopy(),wire=compact(full);
            if("graph".equals(change))((ObjectNode)graph.path("source")).put("documentId","changed-source");
            else if("binding".equals(change))binding.put("routingSha256","0".repeat(64));
            else ((ObjectNode)wire.path("chunks").get(0)).put("content","changed source text");
            NormalObservedContract.Publication restored=NormalObservedContract.publication(receipt(wire,NormalObservedContract.REQUEST_BOUND_FORMAT),graph,binding,true);
            assertEquals("NORMAL_OBSERVED_SEAL_MISMATCH",assertThrows(CanonicalEvidenceException.class,()->
                NormalObservedContract.validate(restored.raw,full.path("projectionSha256").asText(),NormalObservedContract.MAX_BYTES,restored.projection)).getMessage());
        }
    }
    @Test void canonicalReconstructionKeepsPythonBinary64AndUnicodeSpelling(){
        String expected="{\"a\":[1e-05,-0.0,1e+16,1.2345678901234567,0.0001,1e-07],\"\ue000\":\"/\\n\u754c\ud83d\ude00\",\"\ud83d\ude00\":9223372036854775808}";
        assertEquals(expected,CanonicalJson.nodeWire(JsonUtils.parse(expected)));
        assertEquals(CanonicalJson.hash(expected,null),CanonicalJson.hash(CanonicalJson.nodeWire(JsonUtils.parse(expected)),null));
    }
    private static ObjectNode compact(JsonNode full){
        ObjectNode wire=(ObjectNode)full.deepCopy();wire.putObject("manifest").put("$requestManifest",full.path("projectionIdentity").path("observationManifestSha256").asText());
        wire.putObject("canonical").put("$requestCanonical",full.path("sourceHash").asText());return wire;
    }
    private static CanonicalJson.NormalReceipt receipt(JsonNode projection,String format){
        ObjectNode receipt=JsonUtils.mapper().createObjectNode();receipt.set("projection",projection);
        if(format!=null)receipt.put("responseFormat",format);
        return CanonicalJson.normalObservedReceipt(CanonicalJson.nodeWire(receipt).getBytes(StandardCharsets.UTF_8));
    }
}
