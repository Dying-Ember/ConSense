package com.consense.service.vetting;

import java.util.Collections;

/** Authored CPU declarations satisfy the current wire contract without claiming project facts. */
public final class VettingCompetitionFixture {
    private VettingCompetitionFixture() { }
    public static void details(VettingSemanticReview.Record record) {
        record.setSubDimension("Source-supported review");
        record.setFactBasis("Authored source evidence supplied by this CPU fixture; project applicability is not asserted.");
        record.setPendingFacts(Collections.emptyList());record.setSuggestedAction("review");record.setChecklistAssessments(Collections.emptyList());
    }
    public static void details(java.util.Map<String,Object> record) {
        record.put("subDimension","Source-supported review");
        record.put("factBasis","Authored source evidence supplied by this CPU fixture; project applicability is not asserted.");
        record.put("pendingFacts",Collections.emptyList());record.put("suggestedAction","review");record.put("checklistAssessments",Collections.emptyList());
    }
}
