package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.common.Utf8;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.repository.CanonicalSourceBindingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
    @Test void boundedLookupChecksForeignNormalThenStopsAtSharedReviewedAuthority()throws Exception {
        CanonicalSourceBindingRepository repository=mock(CanonicalSourceBindingRepository.class);
        javax.persistence.EntityManager entities=mock(javax.persistence.EntityManager.class);
        CanonicalSourceBinding foreign=normal();foreign.setId(30L);
        CanonicalSourceBinding reviewed=new CanonicalSourceBinding();reviewed.setId(20L);reviewed.setDeclaredModule("vetting");reviewed.setSnapshotJson("{\"authoredReviewedSnapshot\":true}");
        org.springframework.data.domain.Pageable one=org.springframework.data.domain.PageRequest.of(0,1);
        when(repository.findByProjectIdAndDocumentIdOrderByIdDesc("bounded-project",22L,one)).thenReturn(Collections.singletonList(foreign));
        when(repository.findByProjectIdAndDocumentIdAndIdLessThanOrderByIdDesc("bounded-project",22L,30L,one)).thenReturn(Collections.singletonList(reviewed));
        when(repository.findByProjectIdAndDocumentIdOrderByIdDesc("bounded-project",22L)).thenThrow(new AssertionError("Latest binding lookup must not hydrate every historical snapshot"));
        when(entities.contains(foreign)).thenReturn(true);
        assertSame(reviewed,CanonicalEvidenceService.latestForModule(repository,entities,"bounded-project",22L,"advisory").get(0));
        verify(repository).findByProjectIdAndDocumentIdOrderByIdDesc("bounded-project",22L,one);
        verify(repository).findByProjectIdAndDocumentIdAndIdLessThanOrderByIdDesc("bounded-project",22L,30L,one);verifyNoMoreInteractions(repository);
        verify(entities).contains(foreign);verify(entities).detach(foreign);verifyNoMoreInteractions(entities);
    }
    @Test void boundedLookupRejectsCorruptForeignNormalBeforeReadingAnyOlderRevision()throws Exception {
        for(String change:Arrays.asList("bytes","pins")) {
            CanonicalSourceBindingRepository repository=mock(CanonicalSourceBindingRepository.class);CanonicalSourceBinding foreign=normal();foreign.setId(30L);
            if("bytes".equals(change))foreign.setSnapshotBytesSha256("0".repeat(64));else foreign.setRoutingSha256("0".repeat(64));
            org.springframework.data.domain.Pageable one=org.springframework.data.domain.PageRequest.of(0,1);
            when(repository.findByProjectIdAndDocumentIdOrderByIdDesc("bounded-project",22L,one)).thenReturn(Collections.singletonList(foreign));
            when(repository.findByProjectIdAndDocumentIdOrderByIdDesc("bounded-project",22L)).thenThrow(new AssertionError("Latest binding lookup must not hydrate every historical snapshot"));
            String expected="bytes".equals(change)?"CANONICAL_STORED_BYTES_CHANGED":"CANONICAL_BINDING_TYPED_PINS_CHANGED";
            assertEquals(expected,assertThrows(CanonicalEvidenceException.class,()->CanonicalEvidenceService.latestForModule(repository,null,"bounded-project",22L,"advisory")).getMessage());
            verify(repository).findByProjectIdAndDocumentIdOrderByIdDesc("bounded-project",22L,one);verifyNoMoreInteractions(repository);
        }
    }
    @Test void boundedLookupReturnsLatestOwnFailureToValidationWithoutFallingBack()throws Exception {
        CanonicalSourceBindingRepository repository=mock(CanonicalSourceBindingRepository.class);CanonicalSourceBinding latest=normal();latest.setId(30L);latest.setSnapshotBytesSha256("0".repeat(64));
        org.springframework.data.domain.Pageable one=org.springframework.data.domain.PageRequest.of(0,1);
        when(repository.findByProjectIdAndDocumentIdOrderByIdDesc("bounded-project",22L,one)).thenReturn(Collections.singletonList(latest));
        when(repository.findByProjectIdAndDocumentIdAndProjectionSha256("bounded-project",22L,latest.getProjectionSha256())).thenReturn(Optional.of(latest));
        CanonicalEvidenceService service=new CanonicalEvidenceService(repository,mock(CanonicalEvidenceBackend.class),mock(CanonicalCurrentSourceReader.class));
        assertEquals("CANONICAL_STORED_BYTES_CHANGED",assertThrows(CanonicalEvidenceException.class,()->service.currentBindings("bounded-project",latest.getDeclaredModule(),Collections.singletonList(22L))).getMessage());
        verify(repository).findByProjectIdAndDocumentIdOrderByIdDesc("bounded-project",22L,one);verify(repository).findByProjectIdAndDocumentIdAndProjectionSha256("bounded-project",22L,latest.getProjectionSha256());verifyNoMoreInteractions(repository);
    }
}
