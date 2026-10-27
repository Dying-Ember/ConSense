package com.consense.service.vetting;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Authored source seam only. Run this method explicitly after placing it in the isolated Java gate. */
class AdvisoryInitialTextScopeRedTest extends CanonicalRecheckAdviceRealSeamTest {
    @Test
    void initialRankedTextDoesNotPrepareWholeSelectedDocumentsBeforeAnyEvidenceRequest() {
        ReflectionTestUtils.invokeMethod(this, "rankedNormalTextCase", "none");
        assertEquals(0, compactAdvicePrepareRequests.size(),
                "Initial ranked text must reach Qwen without preparing every origin in its selected documents");
    }
    @Test
    void aPublicDeferredMarkerAloneDoesNotProveTextAdmission(){
        com.consense.web.dto.AdviceDtos.CitationVO source=new com.consense.web.dto.AdviceDtos.CitationVO();
        source.setEvidenceId("[1]");source.setSourceRole("tender");source.setCanonicalEvidence(java.util.Collections.singletonList(new com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer()));
        source.setCanonicalSourceUnit(com.consense.common.JsonUtils.parse("{\"status\":\"unknown\",\"reason\":\"additional_source_context_deferred\",\"semanticScopeVerified\":false}"));
        assertFalse(ReflectionTestUtils.<Boolean>invokeMethod(advice,"incorporationSupported","project_contract",java.util.Collections.singletonList("[1]"),java.util.Collections.singletonList(source)));
    }
}
