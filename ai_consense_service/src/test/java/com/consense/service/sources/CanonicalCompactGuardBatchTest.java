package com.consense.service.sources;

import com.consense.domain.CanonicalSourceBinding;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Full 33-source authored scope; counts actual repository calls made by the current guard. */
class CanonicalCompactGuardBatchTest {
    @Test void oneFreshHistoryReadCoversAllThirtyThreeSourcesWithoutContentReads()throws Exception {
        try(CompactNormalPreparationTest.Wire wire=new CompactNormalPreparationTest.Wire(33)) {
            CanonicalEvidenceService.CompactNormalPreparation prepared=(CanonicalEvidenceService.CompactNormalPreparation)wire.prepare((source,graph)->{});
            wire.attest(prepared,wire.proofs());clearInvocations(wire.bindings,wire.reader,wire.backend);
            wire.service.assertCompactCurrent(prepared.scope());
            long historyReads=mockingDetails(wire.bindings).getInvocations().stream().filter(call->call.getMethod().getName().startsWith("findCompact")).count();
            assertEquals(1,historyReads,"A fresh 33-source guard must read its complete scalar binding history in one query");
            verify(wire.reader,times(1)).currentVettingMetadata("authored-compact");
            verify(wire.reader,times(33)).requireCurrentStamp(any(),any());
            verify(wire.reader,times(33)).requireOriginalFreshness(any(),any());
            verify(wire.reader,never()).normalDocument(anyString(),anyLong(),anyString());
            verify(wire.reader,never()).currentStamp(anyString(),anyLong(),anyString());
            verify(wire.reader,never()).requireOriginalBytes(any());verifyNoInteractions(wire.backend);
        }
    }
    @Test void everyScalarBindingMutationStillFailsTheFreshBatchedGuard()throws Exception {
        try(CompactNormalPreparationTest.Wire wire=new CompactNormalPreparationTest.Wire(1)) {
            CanonicalEvidenceService.CompactNormalPreparation prepared=(CanonicalEvidenceService.CompactNormalPreparation)wire.prepare((source,graph)->{});
            wire.attest(prepared,wire.proofs());CanonicalSourceBinding row=wire.rows.get(1L);int checked=0;
            for(Field field:CanonicalSourceBinding.class.getDeclaredFields()) {
                if(Modifier.isStatic(field.getModifiers())||field.isSynthetic()||"snapshotJson".equals(field.getName()))continue;
                field.setAccessible(true);Object before=field.get(row);
                field.set(row,field.getType()==String.class?String.valueOf(before)+"-changed":field.getType()==Long.class?((Long)before)+1000:((Instant)before).plusNanos(1));
                assertThrows(CanonicalEvidenceException.class,()->wire.service.assertCompactCurrent(prepared.scope()),field.getName());
                field.set(row,before);wire.service.assertCompactCurrent(prepared.scope());checked++;
            }
            assertEquals(20,checked,"All stored fields other than the separately checked snapshot body must stay pinned");
        }
    }
    @Test void addedOrDeletedHistoryAndDeletedAnchorCannotReuseAPreviousVerdict()throws Exception {
        try(CompactForeignNormalPreparationTest.History history=new CompactForeignNormalPreparationTest.History()) {
            CompactNormalPreparationTest.Wire wire=history.wire;
            CanonicalEvidenceService.CompactNormalPreparation prepared=(CanonicalEvidenceService.CompactNormalPreparation)wire.prepare((source,graph)->{});
            wire.attest(prepared,wire.proofs());wire.service.assertCompactCurrent(prepared.scope());
            when(wire.bindings.findCompactHistoriesForSelected(anyString(),anyList(),any())).thenReturn(Collections.singletonList(CompactNormalPreparationTest.Wire.metadata(history.own)));
            assertThrows(CanonicalEvidenceException.class,()->wire.service.assertCompactCurrent(prepared.scope()),"Deleted newer captured history must be rejected");
            when(wire.bindings.findCompactHistoriesForSelected(anyString(),anyList(),any())).thenReturn(Collections.emptyList());
            assertThrows(CanonicalEvidenceException.class,()->wire.service.assertCompactCurrent(prepared.scope()),"Deleted selected anchor must be rejected");
            CanonicalSourceBinding added=new CanonicalSourceBinding();org.springframework.beans.BeanUtils.copyProperties(history.foreign,added);added.setId(100L);
            when(wire.bindings.findCompactHistoriesForSelected(anyString(),anyList(),any())).thenReturn(Arrays.asList(CompactNormalPreparationTest.Wire.metadata(added),CompactNormalPreparationTest.Wire.metadata(history.foreign),CompactNormalPreparationTest.Wire.metadata(history.own)));
            assertThrows(CanonicalEvidenceException.class,()->wire.service.assertCompactCurrent(prepared.scope()),"New foreign revision must be rejected");
        }
    }
    @Test void eachGuardStillReadsCurrentSourceRevisionsAndFileMetadata()throws Exception {
        try(CompactNormalPreparationTest.Wire wire=new CompactNormalPreparationTest.Wire(1)) {
            CanonicalEvidenceService.CompactNormalPreparation prepared=(CanonicalEvidenceService.CompactNormalPreparation)wire.prepare((source,graph)->{});
            wire.attest(prepared,wire.proofs());clearInvocations(wire.reader,wire.bindings);
            wire.service.assertCompactCurrent(prepared.scope());wire.service.assertCompactCurrent(prepared.scope());
            verify(wire.reader,times(2)).currentVettingMetadata("authored-compact");verify(wire.reader,times(2)).requireOriginalFreshness(any(),any());
            verify(wire.bindings,times(2)).findCompactHistoriesForSelected(eq("authored-compact"),eq(Collections.singletonList(1L)),eq(org.springframework.data.domain.PageRequest.of(0,2)));
            doThrow(new CanonicalEvidenceException(org.springframework.http.HttpStatus.CONFLICT,"SOURCE_ORIGINAL_METADATA_CHANGED")).when(wire.reader).requireOriginalFreshness(any(),any());
            assertThrows(CanonicalEvidenceException.class,()->wire.service.assertCompactCurrent(prepared.scope()));
        }
    }
}
