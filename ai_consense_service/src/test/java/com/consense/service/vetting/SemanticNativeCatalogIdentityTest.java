package com.consense.service.vetting;

import com.consense.domain.SourceDocument;
import com.consense.service.sources.CanonicalEvidenceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

/** Producer-side native catalogs do not assert the separately verified Java parsed hash. */
class SemanticNativeCatalogIdentityTest extends CanonicalRecheckAdviceRealSeamTest {
    private String alteration="none";

    @Override ObjectNode sourceReadFixture(JsonNode request)throws Exception {
        ObjectNode result=super.sourceReadFixture(request);
        if(!"semantic_units".equals(request.path("view").asText())||!"prepare".equals(request.path("operation").asText()))return result;
        for(JsonNode source:result.path("sources")){
            ObjectNode row=(ObjectNode)source;Long id=Long.valueOf(row.path("documentId").asText());JsonNode actual=snapshots.get(id);
            row.set("producerKind",actual.path("producerKind").deepCopy());
            row.set("projectionRevisionSha256",actual.path("projectionRevisionSha256").deepCopy());
            if("normal_missing_hash".equals(alteration)&&id==43L)row.putNull("javaParsedSourceHash");
            if(id!=42L)continue;
            if("wrong_hash".equals(alteration))row.put("javaParsedSourceHash",VettingCorpus.hash("AUTHORED wrong parsed identity"));
            if("forged_producer".equals(alteration))row.put("producerKind","normal_observed");
            if("unknown_producer".equals(alteration))row.put("producerKind","unverified");
            if("source_revision".equals(alteration))row.put("sourceRevisionSha256",VettingCorpus.hash("AUTHORED wrong source revision"));
            if("projection_revision".equals(alteration))row.put("projectionRevisionSha256",VettingCorpus.hash("AUTHORED wrong projection revision"));
            if("missing_revision".equals(alteration))row.remove("projectionRevisionSha256");
        }
        return result;
    }

    private List<SourceDocument> selected(){return documents.findScopeSourcesByProjectId(PROJECT);}

    @Test void verifiedReviewedNativePdfAndWordUseIndependentJavaSourceProof() {
        configureVettingSemanticPreparation();
        assertEquals(2,business.loadSemantic(PROJECT,"vetting",selected()).documentIds.size());
        assertEquals(1,semanticPrepareCalls.get());assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }

    @ParameterizedTest @ValueSource(strings={"wrong_hash","forged_producer","unknown_producer","source_revision","projection_revision","missing_revision"})
    void nativeCatalogCannotForgeIdentityOrRevisions(String change) {
        alteration=change;configureVettingSemanticPreparation();
        RuntimeException rejected=assertThrows(RuntimeException.class,()->business.loadSemantic(PROJECT,"vetting",selected()),change);
        assertTrue(rejected.getMessage().contains("semantic source identity differs"),change+": "+rejected.getMessage());
        assertEquals(1,semanticPrepareCalls.get());
        assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }

    @Test void normalObservedStillRequiresItsProducerParsedHash()throws Exception {
        replaceReviewedDocxWithUnregisteredNormal();configureVettingSemanticPreparation();
        assertTrue(evidence.prepareNormalObserved(PROJECT,43L,"vetting"));alteration="normal_missing_hash";
        RuntimeException rejected=assertThrows(RuntimeException.class,()->business.loadSemantic(PROJECT,"vetting",selected()));
        assertTrue(rejected.getMessage().contains("semantic source identity differs"),rejected.getMessage());
        assertEquals(1,semanticPrepareCalls.get());
        assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }

    @Test void bindingChangedDuringNativePrepareIsRejected() {
        configureVettingSemanticPreparation();semanticPrepareMutation.set(()->mutate(42L,"binding_revision"));
        assertThrows(RuntimeException.class,()->business.loadSemantic(PROJECT,"vetting",selected()));
        assertEquals(1,semanticPrepareCalls.get());
        assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }

    @Test void ordinaryBindingListCannotClaimVerifiedNativeAuthority() {
        configureVettingSemanticPreparation();
        OerBusinessEvidence.Session session=business.loadSemantic(PROJECT,"vetting",selected());
        CanonicalEvidenceService.CompactBindingScope scope=(CanonicalEvidenceService.CompactBindingScope)
                org.springframework.test.util.ReflectionTestUtils.getField(session,"semanticScope");
        assertNotNull(scope);
        assertThrows(RuntimeException.class,()->canonicalRetrieval.prepareSemanticSources("vetting",new ArrayList<>(scope.bindings())));
        assertEquals(0,chatCalls.get());assertEquals(0,history.count());
    }
}
