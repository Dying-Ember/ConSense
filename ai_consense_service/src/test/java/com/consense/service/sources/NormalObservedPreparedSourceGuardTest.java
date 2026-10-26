package com.consense.service.sources;

import com.consense.domain.CanonicalSourceBinding;
import com.consense.domain.SourceDocument;
import com.consense.repository.CanonicalSourceBindingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Existing committed-stamp reuse must never turn a changed source into a legacy fallback. */
class NormalObservedPreparedSourceGuardTest {
    @Test void verifiedCompleteSourceUsesTheExistingFreshStampGuard(){
        Wire wire=new Wire();assertDoesNotThrow(wire::check);
        verify(wire.reader).assertSourceStampCurrent(wire.binding,"vetting",wire.source);
        verify(wire.reader,never()).assertSourceCurrent(any(),anyString());
    }
    @Test void onlyMissingCommittedRevisionFallsBackToTheCompleteSourceCheck(){
        Wire wire=new Wire();doThrow(new CanonicalEvidenceException(HttpStatus.CONFLICT,"SOURCE_CONTENT_REVISION_UNAVAILABLE"))
            .when(wire.reader).assertSourceStampCurrent(wire.binding,"vetting",wire.source);
        assertDoesNotThrow(wire::check);
        org.mockito.InOrder order=inOrder(wire.reader);
        order.verify(wire.reader).assertSourceStampCurrent(wire.binding,"vetting",wire.source);
        order.verify(wire.reader).assertSourceCurrent(wire.binding,"vetting");
    }
    @Test void staleIdentityOriginalAndWrongStatusErrorsNeverFallBack(){
        for(CanonicalEvidenceException failure:new CanonicalEvidenceException[]{
            new CanonicalEvidenceException(HttpStatus.CONFLICT,"CANONICAL_SOURCE_STALE"),
            new CanonicalEvidenceException(HttpStatus.CONFLICT,"SOURCE_ORIGINAL_BYTES_CHANGED"),
            new CanonicalEvidenceException(HttpStatus.CONFLICT,"SOURCE_DOCUMENT_SUPERSEDED"),
            new CanonicalEvidenceException(HttpStatus.FORBIDDEN,"SOURCE_MODULE_ELIGIBILITY_UNRESOLVED_OR_EXCLUDED"),
            new CanonicalEvidenceException(HttpStatus.BAD_GATEWAY,"SOURCE_CONTENT_REVISION_UNAVAILABLE")}){
            Wire wire=new Wire();doThrow(failure).when(wire.reader).assertSourceStampCurrent(wire.binding,"vetting",wire.source);
            assertSame(failure,assertThrows(CanonicalEvidenceException.class,wire::check));
            verify(wire.reader,never()).assertSourceCurrent(any(),anyString());
        }
    }
    @Test void legacyCompleteSourceFailureStillPropagates(){
        Wire wire=new Wire();CanonicalEvidenceException stale=new CanonicalEvidenceException(HttpStatus.CONFLICT,"CANONICAL_SOURCE_STALE");
        doThrow(new CanonicalEvidenceException(HttpStatus.CONFLICT,"SOURCE_CONTENT_REVISION_UNAVAILABLE"))
            .when(wire.reader).assertSourceStampCurrent(wire.binding,"vetting",wire.source);
        doThrow(stale).when(wire.reader).assertSourceCurrent(wire.binding,"vetting");
        assertSame(stale,assertThrows(CanonicalEvidenceException.class,wire::check));
    }
    private static final class Wire {
        final CanonicalCurrentSourceReader reader=mock(CanonicalCurrentSourceReader.class);
        final CanonicalEvidenceService service=new CanonicalEvidenceService(mock(CanonicalSourceBindingRepository.class),mock(CanonicalEvidenceBackend.class),reader);
        final CanonicalSourceBinding binding=new CanonicalSourceBinding();final SourceDocument source=new SourceDocument();
        void check(){ReflectionTestUtils.invokeMethod(service,"assertPreparedNormalSourceCurrent",binding,"vetting",source);}
    }
}
