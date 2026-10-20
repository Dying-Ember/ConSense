package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.common.Utf8;
import com.consense.domain.CanonicalSourceBinding;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CanonicalModuleBindingsTest {
    private static CanonicalSourceBinding normal()throws Exception {
        String raw=NormalObservedContractTest.resource("authored-projection.json");JsonNode snapshot=JsonUtils.parse(raw),identity=snapshot.path("projectionIdentity");
        CanonicalSourceBinding binding=new CanonicalSourceBinding();binding.setDeclaredModule(identity.path("module").asText());
        binding.setSnapshotJson(raw);binding.setSnapshotBytesSha256(Utf8.sha256(raw));binding.setProjectionSha256(snapshot.path("projectionSha256").asText());
        binding.setOriginalSourceSha256(identity.path("originalSourceSha256").asText());binding.setJavaParsedSourceHash(identity.path("javaParsedSourceHash").asText());
        binding.setRoutingSha256(identity.path("routingSha256").asText());binding.setScopePolicySha256(identity.path("scopePolicySha256").asText());
        binding.setCanonicalSourceHash(snapshot.path("sourceHash").asText());binding.setSourceRevisionSha256(identity.path("sourceRevisionSha256").asText());
        binding.setProjectionRevisionSha256(snapshot.path("projectionRevisionSha256").asText());binding.setEligibleModulesJson(identity.path("eligibleModules").toString());return binding;
    }

    @Test void foreignNormalStateDoesNotBecomeAnotherModulesBindingOrHideItsOwnLatest()throws Exception {
        CanonicalSourceBinding vetting=normal();
        assertTrue(CanonicalEvidenceService.latestForModule(Collections.singletonList(vetting),"advisory").isEmpty());
        assertSame(vetting,CanonicalEvidenceService.latestForModule(Collections.singletonList(vetting),"vetting").get(0));
        CanonicalSourceBinding advisory=new CanonicalSourceBinding();advisory.setDeclaredModule("advisory");
        assertSame(advisory,CanonicalEvidenceService.latestForModule(Arrays.asList(vetting,advisory),"advisory").get(0));
        CanonicalSourceBinding older=new CanonicalSourceBinding();older.setDeclaredModule("advisory");
        assertSame(advisory,CanonicalEvidenceService.latestForModule(Arrays.asList(vetting,advisory,older),"advisory").get(0),"A stale latest own-module binding must reach the ordinary validation gate rather than falling back");
    }

    @Test void reviewedAdoptionRemainsSharedAndCannotBeSkippedForAnOlderNormal()throws Exception {
        CanonicalSourceBinding reviewed=new CanonicalSourceBinding();reviewed.setDeclaredModule("vetting");reviewed.setSnapshotJson("{\"authoredReviewedSnapshot\":true}");
        assertSame(reviewed,CanonicalEvidenceService.latestForModule(Arrays.asList(reviewed,normal()),"advisory").get(0));
    }

    @Test void foreignNormalBytesAndTypedIdentityMustBeIntactBeforeItsStateCanBeSkipped()throws Exception {
        CanonicalSourceBinding bytes=normal();bytes.setSnapshotBytesSha256("0".repeat(64));
        assertEquals("CANONICAL_STORED_BYTES_CHANGED",assertThrows(CanonicalEvidenceException.class,()->CanonicalEvidenceService.latestForModule(Collections.singletonList(bytes),"advisory")).getMessage());
        CanonicalSourceBinding pins=normal();pins.setRoutingSha256("0".repeat(64));
        assertEquals("CANONICAL_BINDING_TYPED_PINS_CHANGED",assertThrows(CanonicalEvidenceException.class,()->CanonicalEvidenceService.latestForModule(Collections.singletonList(pins),"advisory")).getMessage());
    }
}
