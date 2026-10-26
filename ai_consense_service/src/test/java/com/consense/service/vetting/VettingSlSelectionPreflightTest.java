package com.consense.service.vetting;

import com.consense.ai.LlmProfiles;
import com.consense.common.BizException;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParser;
import com.consense.repository.SourceDocumentRepository;
import com.consense.repository.VettingFindingRepository;
import com.consense.repository.VettingRunRepository;
import com.consense.service.ProjectService;
import com.consense.service.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VettingSlSelectionPreflightTest {
    @Test void missingOrStaleSlSelectionFailsBeforeProjectSourcesAreRead() {
        for(String reason:Arrays.asList("fixed_sl_selection_required_or_stale","fixed_sl_selection_outside_catalog","fixed_sl_selection_empty")) {
            SourceDocumentRepository sources=mock(SourceDocumentRepository.class);
            VettingRunRepository runs=mock(VettingRunRepository.class);
            LlmProfiles profiles=mock(LlmProfiles.class);
            FixedBaselineRetrievalClient fixed=mock(FixedBaselineRetrievalClient.class);
            when(runs.findFirstByProjectIdOrderByStartedAtDesc("project")).thenReturn(Optional.empty());
            when(fixed.enabled()).thenReturn(true);
            when(fixed.projectSelection("project")).thenThrow(new IllegalStateException("Fixed development reference rejected: "+reason));
            VettingService service=new VettingService(mock(ProjectService.class),mock(StorageService.class),mock(DocumentParser.class),new ConsenseProperties(),
                    sources,mock(VettingFindingRepository.class),runs,mock(VettingSemanticReview.class),mock(VettingReportWriter.class),mock(PlatformTransactionManager.class),profiles);
            service.setFixedBaselineRetrievalClient(fixed);
            try {
                BizException failure=assertThrows(BizException.class,()->service.startRun("project","en"));
                assertEquals(4104,failure.getCode());
                assertTrue(failure.getMessage().contains("Select and save at least one current SL edition"));
                assertFalse(failure.getMessage().contains(reason));
                verify(sources,never()).findSelectedBusinessSourcesByProjectId(anyString(),anyString(),anyString());
                verifyNoInteractions(profiles);
                verify(runs,never()).saveAndFlush(any());
            } finally { service.stopWorker(); }
        }
    }

    @Test void unrelatedFrozenReferenceFailureIsNotMisreportedAsAnEditionChoice() {
        SourceDocumentRepository sources=mock(SourceDocumentRepository.class);
        VettingRunRepository runs=mock(VettingRunRepository.class);
        FixedBaselineRetrievalClient fixed=mock(FixedBaselineRetrievalClient.class);
        when(runs.findFirstByProjectIdOrderByStartedAtDesc("project")).thenReturn(Optional.empty());
        when(fixed.enabled()).thenReturn(true);
        when(fixed.projectSelection("project")).thenThrow(new IllegalStateException("frozen_corpus_sha_mismatch"));
        VettingService service=new VettingService(mock(ProjectService.class),mock(StorageService.class),mock(DocumentParser.class),new ConsenseProperties(),
                sources,mock(VettingFindingRepository.class),runs,mock(VettingSemanticReview.class),mock(VettingReportWriter.class),mock(PlatformTransactionManager.class),mock(LlmProfiles.class));
        service.setFixedBaselineRetrievalClient(fixed);
        try {
            assertEquals("frozen_corpus_sha_mismatch",assertThrows(IllegalStateException.class,()->service.startRun("project","en")).getMessage());
            verify(sources,never()).findSelectedBusinessSourcesByProjectId(anyString(),anyString(),anyString());
        } finally { service.stopWorker(); }
    }
}
