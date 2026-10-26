package com.consense.service.sources;

import com.consense.domain.CanonicalSourceBinding;
import com.consense.repository.CanonicalSourceBindingRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Authored display classification only; full registration/evidence authority is tested separately. */
class CanonicalReviewedDisplayPinsTest {
    @Test void positiveReviewedAndFamilyPinsDoNotReadOrRequireAnEarlySnapshotHeader(){
        for(boolean family:new boolean[]{false,true}){
            CanonicalSourceBinding row=reviewed(family);Wire wire=new Wire(row,row);
            assertFalse(wire.normal(row));
            verify(wire.repository,times(2)).findCompactById(row.getId());verifyNoMoreInteractions(wire.repository);
            verifyNoInteractions(wire.backend,wire.sources);
        }
    }
    @Test void changedReviewSnapshotRoutingAndScopePinsRejectAfterTheScalarRead(){
        for(Consumer<CanonicalSourceBinding> change:java.util.Arrays.<Consumer<CanonicalSourceBinding>>asList(
            b->b.setReviewReferenceSha256("b".repeat(64)),b->b.setSnapshotContentSha256("b".repeat(64)),
            b->b.setSnapshotBytesSha256("b".repeat(64)),b->b.setProjectionSha256("b".repeat(64)),
            b->b.setRoutingSha256("b".repeat(64)),b->b.setEligibleModulesJson("[\"vetting\"]"),
            b->b.setFamilyCompositionSha256("b".repeat(64)))){
            CanonicalSourceBinding before=reviewed(false),after=copy(before);change.accept(after);
            Wire wire=new Wire(before,after);unavailable(wire,before);
        }
    }
    @Test void absentChangedOrIncompleteReviewMarkersNeverBecomeSharedReviewedAuthority(){
        for(Consumer<CanonicalSourceBinding> change:java.util.Arrays.<Consumer<CanonicalSourceBinding>>asList(
            b->b.setSnapshotContentSha256(null),b->b.setSnapshotContentSha256("b".repeat(64)),
            b->b.setReviewReferenceSha256(null),b->b.setAdoptionManifestSha256("invalid"),
            b->{b.setFamilyCompositionSha256("b".repeat(64));b.setFamilyComponentSetSha256("c".repeat(64));},
            b->{b.setAdoptionManifestSha256(null);b.setReviewReferenceSha256(null);} )){
            CanonicalSourceBinding row=reviewed(false);change.accept(row);unavailable(new Wire(row,row),row);
        }
    }
    @Test void familyCompositionMutationRejectsInsteadOfReusingTheOldClassification(){
        CanonicalSourceBinding before=reviewed(true),after=copy(before);after.setFamilyComponentSetSha256("b".repeat(64));
        unavailable(new Wire(before,after),before);
    }
    private static void unavailable(Wire wire,CanonicalSourceBinding row){
        assertEquals("CANONICAL_AUTHORITY_INSPECTION_REQUIRED",assertThrows(CanonicalEvidenceException.class,()->wire.normal(row)).getMessage());
    }
    private static CanonicalSourceBinding reviewed(boolean family){
        CanonicalSourceBinding b=new CanonicalSourceBinding();String digest="a".repeat(64);
        b.setId(7L);b.setProjectId("authored");b.setDocumentId(42L);b.setProjectionSha256(digest);b.setOriginalSourceSha256(digest);
        b.setJavaParsedSourceHash(digest);b.setRoutingSha256(digest);b.setSourceRevisionSha256(digest);b.setProjectionRevisionSha256(digest);
        b.setCanonicalSourceHash(digest);b.setScopePolicySha256(digest);b.setSnapshotBytesSha256(digest);b.setSnapshotContentSha256(digest);
        b.setDeclaredModule("vetting");b.setEligibleModulesJson("[\"vetting\",\"advisory\"]");b.setCreatedAt(Instant.EPOCH);
        if(family){b.setFamilyCompositionSha256(digest);b.setFamilyComponentSetSha256(digest);}
        else{b.setAdoptionManifestSha256(digest);b.setReviewReferenceSha256(digest);}
        return b;
    }
    private static CanonicalSourceBinding copy(CanonicalSourceBinding value){CanonicalSourceBinding b=new CanonicalSourceBinding();org.springframework.beans.BeanUtils.copyProperties(value,b);return b;}
    static final class Wire {
        final CanonicalSourceBindingRepository repository=mock(CanonicalSourceBindingRepository.class);
        final CanonicalEvidenceBackend backend=mock(CanonicalEvidenceBackend.class);
        final CanonicalCurrentSourceReader sources=mock(CanonicalCurrentSourceReader.class);
        final CanonicalEvidenceService service=new CanonicalEvidenceService(repository,backend,sources);
        Wire(CanonicalSourceBinding before,CanonicalSourceBinding after){when(repository.findCompactById(before.getId())).thenReturn(Optional.of(metadata(before)),Optional.of(metadata(after)));}
        boolean normal(CanonicalSourceBinding row){return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(service,"displayNormalSnapshot",row));}
        private static CanonicalSourceBindingRepository.CompactMetadata metadata(CanonicalSourceBinding row){
            return mock(CanonicalSourceBindingRepository.CompactMetadata.class,call->CanonicalSourceBinding.class.getMethod(call.getMethod().getName()).invoke(row));
        }
    }
}
