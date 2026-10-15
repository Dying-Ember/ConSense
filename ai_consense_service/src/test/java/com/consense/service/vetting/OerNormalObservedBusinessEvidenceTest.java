package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.sources.NormalObservedContract;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Authored Python-produced normal wire; registry boundary doubled, no source or model execution. */
class OerNormalObservedBusinessEvidenceTest {
    private OerBusinessEvidenceTest.Wire wire() throws Exception {
        OerBusinessEvidenceTest.Wire w=new OerBusinessEvidenceTest.Wire();
        JsonNode snapshot;try(InputStream input=Objects.requireNonNull(getClass().getResourceAsStream("/oer/normal-observed/authored-business-view.json"))){snapshot=JsonUtils.parse(new String(input.readAllBytes(),StandardCharsets.UTF_8));}
        w.snapshot.removeAll();w.snapshot.setAll((ObjectNode)snapshot);
        w.binding.setProjectionSha256(snapshot.path("projectionSha256").asText());w.binding.setOriginalSourceSha256(snapshot.path("source").path("sourceSha256").asText());
        w.binding.setCanonicalSourceHash(snapshot.path("sourceHash").asText());w.binding.setSourceRevisionSha256(snapshot.path("source").path("sourceRevisionSha256").asText());
        w.binding.setProjectionRevisionSha256(snapshot.path("projectionRevisionSha256").asText());w.store();
        when(w.registry.pointers(eq("project"),eq(42L),anyString(),eq("vetting"))).thenAnswer(call->{
            List<EvidencePointer> result=w.pointers();for(EvidencePointer pointer:result){
                pointer.setProjectionSchema(NormalObservedContract.SCHEMA);pointer.setProducerKind(NormalObservedContract.PRODUCER);
                pointer.setAddressKind("normal_observed_unit");pointer.setBboxConvention(null);pointer.setOriginalGeometryStatus("unknown");pointer.setVisualReturnStatus("unknown");
            }return result;
        });return w;
    }

    @Test void normalViewsPreserveLiteralPositionsAndUnknownAuthorityWithoutInventingPagesOrReview() throws Exception {
        OerBusinessEvidenceTest.Wire w=wire();OerBusinessEvidence.Session session=assertDoesNotThrow(w::load);
        assertEquals(2,session.chunkCount());assertEquals("P3",session.chunks().get(0).getPageNo());
        VettingCorpus.Chunk word=session.chunks().get(1);assertNull(word.getPageNo());assertNull(word.getBbox());
        for(VettingCorpus.Chunk chunk:session.chunks()){
            assertNull(OerBusinessEvidence.canonicalViewStatus(chunk));assertFalse(chunk.getCanonicalEvidence().isHumanConfirmed());
            assertTrue(chunk.getSourceQuality().getLimitations().stream().anyMatch(value->value.contains("unreviewed")));
            for(int n=0;n<chunk.getParts().size();n++){
                VettingCorpus.Part part=chunk.getParts().get(n);JsonNode raw=chunk.getCanonicalPayload().path("parts").get(n);
                assertEquals("normal_observed_candidate",part.getExtractionSource());assertEquals(raw.get("sourcePosition"),part.getSourcePosition());
                assertEquals(raw.get("wordObservation"),part.getWordObservation());assertNotEquals("P0",part.getPageNo());
            }
            assertNull(OerBusinessEvidence.canonicalViewStatus(JsonUtils.read(JsonUtils.write(chunk),VettingCorpus.Chunk.class)),"read-only transport preserves absent adoption");
        }
        assertEquals(word.getContent().length(),word.getParts().get(0).getEndOffset());assertTrue(word.getContent().contains("\uD83D\uDE80"));
        assertDoesNotThrow(()->VettingSourceMaterial.project(session.chunks()));
        JsonNode model=new OerModelMaterial.Request().project(word.getCanonicalEvidence(),word.getCanonicalPayload(),word.getRole());
        JsonNode part=model.path("parts").get(0),rawPart=word.getCanonicalPayload().path("parts").get(0);
        assertEquals(rawPart.path("wordCells"),part.path("wordCells"));assertEquals(rawPart.path("wordNativeObjects"),part.path("wordNativeObjects"));
        assertEquals(rawPart.path("wordObservation"),part.path("wordObservation"));assertTrue(model.path("adoption").isNull());assertEquals("candidate_unverified",model.path("sourceQuality").path("adoptionStatus").asText());
        for(String key:Arrays.asList("kind","requiredMemberBlockIds","qualifiersComplete","unknowns"))assertEquals(word.getCanonicalPayload().path(key),model.path(key),key);
        assertEquals(rawPart.path("readingContext"),part.path("readingContext"));
        w.service.ruleSources(session).forEach(source->source.getBlocks().forEach(block->assertEquals("normal_observed_candidate",block.getSource())));
    }

    @Test void normalWireWordObservationsReachModelMaterialWithoutAnAdoptionClaim()throws Exception{
        OerBusinessEvidenceTest.Wire w=wire();VettingCorpus.Chunk word=w.load().chunks().get(1);
        ObjectNode raw=(ObjectNode)word.getCanonicalPayload().path("parts").get(0),pointer=(ObjectNode)word.getCanonicalEvidence().getParts().get(0);
        for(String key:Arrays.asList("wordNumbering","wordSymbols","wordWarnings","wordCells")){com.fasterxml.jackson.databind.node.ArrayNode observation=JsonUtils.mapper().createArrayNode().add("AUTHORED "+key);raw.set(key,observation);pointer.set(key,observation.deepCopy());}
        ObjectNode nativeObject=JsonUtils.mapper().createObjectNode();nativeObject.put("kind","field");nativeObject.put("status","observed");nativeObject.put("semanticStatus","unknown");raw.putArray("wordNativeObjects").add(nativeObject);pointer.set("wordNativeObjects",raw.path("wordNativeObjects").deepCopy());
        for(String key:Arrays.asList("visualRegion","visualFrame","visualProvenance","readingContext","nativeTableCandidate","parserPageGroup","pdfFormField")){ObjectNode observation=JsonUtils.mapper().createObjectNode();observation.put("status","observed");observation.put("semanticScopeVerified",false);observation.put("adoptionStatus","candidate_unverified");raw.set(key,observation);pointer.set(key,observation.deepCopy());}
        assertNull(OerBusinessEvidence.canonicalViewStatus(word));JsonNode material=new OerModelMaterial.Request().project(word.getCanonicalEvidence(),word.getCanonicalPayload(),word.getRole());JsonNode part=material.path("parts").get(0);
        for(String key:Arrays.asList("wordNumbering","wordSymbols","wordWarnings","wordCells","wordNativeObjects","wordObservation"))assertEquals(raw.path(key),part.path(key),key);
        for(String key:Arrays.asList("visualRegion","visualFrame","visualProvenance","readingContext","nativeTableCandidate","parserPageGroup","pdfFormField"))assertEquals(raw.path(key),part.path(key),key);
        assertTrue(material.path("adoption").isNull());assertFalse(material.path("sourceQuality").path("sourceQualityAccepted").asBoolean(true));
    }

    @Test void normalViewsRejectReviewAuthorityInventedFramesAndChangedNativePositions() throws Exception {
        for(String mutation:Arrays.asList("schema","producer","address","adoption","human","geometry","page","position","wordObservation")){
            OerBusinessEvidenceTest.Wire w=wire();VettingCorpus.Chunk chunk=w.load().chunks().get(1);EvidencePointer pointer=chunk.getCanonicalEvidence();
            if("schema".equals(mutation))pointer.setProjectionSchema("consense.canonical-projection.v1");
            if("producer".equals(mutation))pointer.setProducerKind("native_docx");
            if("address".equals(mutation))pointer.setAddressKind("canonical_adoption_unit");
            if("adoption".equals(mutation))pointer.setAdoption(JsonUtils.parse("{\"humanConfirmed\":true}"));
            if("human".equals(mutation))pointer.setHumanConfirmed(true);
            if("geometry".equals(mutation))pointer.setOriginalGeometryStatus("reviewed");
            if("page".equals(mutation))chunk.getParts().get(0).setPageNo("P0");
            if("position".equals(mutation))chunk.getParts().get(0).setSourcePosition(JsonUtils.parse("{\"nodePath\":\"borrowed\"}"));
            if("wordObservation".equals(mutation))chunk.getParts().get(0).setWordObservation(JsonUtils.parse("{\"effectiveText\":\"borrowed\"}"));
            assertNotNull(OerBusinessEvidence.canonicalViewStatus(chunk),mutation);
            assertThrows(IllegalArgumentException.class,()->VettingSourceMaterial.project(Collections.singletonList(chunk)),mutation);
        }
    }
}
