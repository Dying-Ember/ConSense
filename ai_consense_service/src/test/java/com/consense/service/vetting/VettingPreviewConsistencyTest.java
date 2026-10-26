package com.consense.service.vetting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VettingPreviewConsistencyTest {
    @Test void missingPreviewObservationPreservesTheFinalPreflightFailure() {
        VettingInputBudget.Result preview=new VettingInputBudget.Result(),actual=new VettingInputBudget.Result();
        preview.setReason("qwen_preflight_http_413");actual.setReason("qwen_preflight_http_413");
        assertFalse(VettingSemanticReview.rejectChangedPreview(preview,actual));
        assertEquals("budget_unknown",actual.getStatus());assertEquals("qwen_preflight_http_413",actual.getReason());
        assertFalse(actual.isExtraDispatchPermitted());
    }

    @Test void knownOverBudgetIsNotRelabelledAsPreviewDrift() {
        VettingInputBudget.Result preview=new VettingInputBudget.Result(),actual=new VettingInputBudget.Result();
        preview.setReason("qwen_preflight_http_413");actual.setStatus("over_budget");actual.setReason("measured_over_budget");
        assertFalse(VettingSemanticReview.rejectChangedPreview(preview,actual));
        assertEquals("over_budget",actual.getStatus());assertEquals("measured_over_budget",actual.getReason());
        assertFalse(actual.isExtraDispatchPermitted());
    }

    @Test void twoValidButDifferentObservationsStillBlockDispatch() {
        VettingInputBudget.Result preview=new VettingInputBudget.Result(),actual=new VettingInputBudget.Result();
        preview.setStatus("provider_estimated_fit");actual.setStatus("provider_estimated_fit");
        preview.setExtraDispatchPermitted(true);actual.setExtraDispatchPermitted(true);
        VettingInputBudget.Observation before=new VettingInputBudget.Observation(),after=new VettingInputBudget.Observation();
        before.setInputTokens(100);after.setInputTokens(101);preview.setObservation(before);actual.setObservation(after);
        assertTrue(VettingSemanticReview.rejectChangedPreview(preview,actual));
        assertEquals("budget_unknown",actual.getStatus());assertEquals("token_preview_final_observation_changed",actual.getReason());
        assertFalse(actual.isExtraDispatchPermitted());
    }
}
