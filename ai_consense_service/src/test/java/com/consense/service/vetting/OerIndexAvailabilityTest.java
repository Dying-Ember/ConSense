package com.consense.service.vetting;

import com.consense.domain.CanonicalSourceBinding;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Authored service boundary, with explicit source/client doubles; no live acceptance claim. */
class OerIndexAvailabilityTest {
    @Test void routineStatusUsesIdenticalKeyAndOnlyFreshScalarBindings()throws Exception {
        OerBusinessEvidenceTest.Wire wire=new OerBusinessEvidenceTest.Wire();OerBusinessEvidence.Session reference=wire.load();
        CanonicalSourceBinding compact=compact(wire.binding);
        when(wire.registry.currentDisplayBindings(eq("project"),eq("vetting"),anyList())).thenReturn(Collections.singletonList(compact));
        when(wire.client.indexAvailable(eq(reference.indexKey),eq("vetting"),anyList()))
            .thenReturn(new OerCanonicalRetrievalClient.IndexState(true,"authored",73,null));
        clearInvocations(wire.registry,wire.client);
        OerBusinessEvidence.CurrentIndexStatus result=wire.service.currentIndexAvailability("project","vetting",()->Collections.singletonList(wire.document));
        assertEquals(reference.indexKey,result.indexKey);assertEquals(73,result.chunks);assertEquals(1,result.documentCount);assertTrue(result.state.ready);
        verify(wire.registry,times(2)).currentDisplayBindings(eq("project"),eq("vetting"),anyList());
        verify(wire.client).indexAvailable(eq(reference.indexKey),eq("vetting"),anyList());
        verifyNoMoreInteractions(wire.registry,wire.client);
    }
    @Test void missingReceiptStatusDoesNotFallBackToAFullSession()throws Exception {
        OerBusinessEvidenceTest.Wire wire=new OerBusinessEvidenceTest.Wire();CanonicalSourceBinding compact=compact(wire.binding);
        when(wire.registry.currentDisplayBindings(anyString(),anyString(),anyList())).thenReturn(Collections.singletonList(compact));
        when(wire.client.indexAvailable(anyString(),anyString(),anyList()))
            .thenReturn(new OerCanonicalRetrievalClient.IndexState(false,null,0,"canonical_index_receipt_missing"));
        clearInvocations(wire.registry,wire.client);
        assertFalse(wire.service.currentIndexAvailability("project","vetting",()->Collections.singletonList(wire.document)).state.ready);
        verify(wire.registry,times(2)).currentDisplayBindings(anyString(),anyString(),anyList());verify(wire.client).indexAvailable(anyString(),anyString(),anyList());
        verifyNoMoreInteractions(wire.registry,wire.client);
    }
    @Test void routineStatusRejectsChangedSourceAndRenderPinsAfterLiveWait()throws Exception {
        for(String change:new String[]{"source","render"}){
            OerBusinessEvidenceTest.Wire wire=new OerBusinessEvidenceTest.Wire();CanonicalSourceBinding compact=compact(wire.binding);
            when(wire.registry.currentDisplayBindings(anyString(),anyString(),anyList())).thenReturn(Collections.singletonList(compact));
            when(wire.client.indexAvailable(anyString(),anyString(),anyList())).thenAnswer(call->{
                if("source".equals(change))compact.setSourceRevisionSha256("f".repeat(64));
                else wire.config.getSourcePageRenderEvidenceIdentities().put(compact.getProjectionSha256(),"e".repeat(64));
                return new OerCanonicalRetrievalClient.IndexState(true,"authored",73,null);
            });
            assertThrows(IllegalStateException.class,()->wire.service.currentIndexAvailability("project","vetting",()->Collections.singletonList(wire.document)),change);
            verify(wire.registry,never()).currentBindings(anyString(),anyString(),anyList());
        }
    }
    @Test void availabilityUsesFreshScalarSourceGuardsAndDoesNotRunFullStatus()throws Exception {
        OerBusinessEvidenceTest.Wire wire=new OerBusinessEvidenceTest.Wire();OerBusinessEvidence.Session session=wire.load();
        CanonicalSourceBinding compact=compact(wire.binding);
        when(wire.registry.currentDisplayBindings(eq("project"),eq("vetting"),anyList())).thenReturn(Collections.singletonList(compact));
        when(wire.client.indexAvailable(eq(session.indexKey),eq("vetting"),anyList(),eq(session.chunkCount())))
            .thenReturn(new OerCanonicalRetrievalClient.IndexState(true,"authored",session.chunkCount(),null));
        clearInvocations(wire.registry,wire.client);
        wire.service.ensureAvailable(session,()->Collections.singletonList(wire.document));
        verify(wire.registry,times(2)).currentDisplayBindings(eq("project"),eq("vetting"),anyList());
        verify(wire.registry,never()).currentBindings(anyString(),anyString(),anyList());
        verify(wire.client,never()).indexStatus(anyString(),anyString(),anyList());
    }
    @Test void sourceRevisionChangeDuringAvailabilityRejectsBeforeRetrieval()throws Exception {
        OerBusinessEvidenceTest.Wire wire=new OerBusinessEvidenceTest.Wire();OerBusinessEvidence.Session session=wire.load();
        CanonicalSourceBinding before=compact(wire.binding),after=compact(wire.binding);after.setSnapshotBytesSha256("f".repeat(64));
        when(wire.registry.currentDisplayBindings(eq("project"),eq("vetting"),anyList())).thenReturn(Collections.singletonList(before),Collections.singletonList(after));
        when(wire.client.indexAvailable(anyString(),anyString(),anyList(),anyInt()))
            .thenReturn(new OerCanonicalRetrievalClient.IndexState(true,"authored",session.chunkCount(),null));
        assertThrows(IllegalStateException.class,()->wire.service.ensureAvailable(session,()->Collections.singletonList(wire.document)));
        verify(wire.client,never()).retrieve(anyString(),anyString(),anyString(),nullable(String.class),anyList());
    }
    @Test void routineStatusRejectsAnInPlaceBusinessContentRevisionDuringLiveAvailability()throws Exception {
        OerBusinessEvidenceTest.Wire wire=new OerBusinessEvidenceTest.Wire();wire.document.setBusinessContentSha256("a".repeat(64));
        when(wire.registry.currentDisplayBindings(anyString(),anyString(),anyList())).thenReturn(Collections.singletonList(compact(wire.binding)));
        when(wire.client.indexAvailable(anyString(),anyString(),anyList())).thenAnswer(call->{wire.document.setBusinessContentSha256("b".repeat(64));return new OerCanonicalRetrievalClient.IndexState(true,"authored",73,null);});
        assertThrows(IllegalStateException.class,()->wire.service.currentIndexAvailability("project","vetting",()->Collections.singletonList(wire.document)));
        verify(wire.registry,never()).currentBindings(anyString(),anyString(),anyList());
    }
    @Test void questionAvailabilityRejectsAnInPlaceBusinessContentRevisionDuringLiveAvailability()throws Exception {
        OerBusinessEvidenceTest.Wire wire=new OerBusinessEvidenceTest.Wire();OerBusinessEvidence.Session session=wire.load();wire.document.setBusinessContentSha256("a".repeat(64));
        when(wire.registry.currentDisplayBindings(anyString(),anyString(),anyList())).thenReturn(Collections.singletonList(compact(wire.binding)));
        when(wire.client.indexAvailable(anyString(),anyString(),anyList(),anyInt())).thenAnswer(call->{wire.document.setBusinessContentSha256("b".repeat(64));return new OerCanonicalRetrievalClient.IndexState(true,"authored",session.chunkCount(),null);});
        clearInvocations(wire.registry);
        assertThrows(IllegalStateException.class,()->wire.service.ensureAvailable(session,()->Collections.singletonList(wire.document)));
        verify(wire.registry,never()).currentBindings(anyString(),anyString(),anyList());
    }
    private static CanonicalSourceBinding compact(CanonicalSourceBinding original){
        CanonicalSourceBinding result=new CanonicalSourceBinding();org.springframework.beans.BeanUtils.copyProperties(original,result);result.setSnapshotJson(null);return result;
    }
}
