# Drafting visual consistency

2026-10-06 follow-up to preview P1/P8. Fixed web baseline: `bc672cffca16e1610ceed4f51600580b3258ee85`. The user requested comparison with the original Feishu `ConSense-CAC-Proposal-DemoV2.0.html` and the first interactive prototype because the current page looks inconsistent.

Use the original Demo's existing shared design tokens, font stack, spacing, control dimensions, surfaces and card treatment. Prototype A remains the interaction reference: one vertical editable question list, the current competition scope and dependencies, explicit manual adoption and existing generated-document preview/export. The historical prototype's broader variable catalogue is not authority for current scope.

Unify Drafting input labels, controls, group cards, evidence/diagnostic cards, collection editors, source markers and reading-panel controls with the app theme. Define all referenced CSS variables. Retain serif typography for the original contract text; app controls and inserted business-value displays use the app UI font. Preserve full English business descriptions and quotations.

Keep the project, language and explicit model-source selectors usable. The existing translated explanation of model switching may use a compact native disclosure; model identity and configuration readiness remain visible. Fit the expanded note to narrow viewports.

The optional original-template reading panel uses a bounded side-by-side layout only when the actual work area has enough width. Smaller work areas use an overlay, with a visible close control and internal reading/table scrolling. Existing file switches show active appearance using their current semantic state. Modals remain positioned against the viewport. Scope any new CSS container to the Drafting route.

Do not change API calls, catalogue/rule conditions, values, adoption, dirty-state handling, navigation callbacks, source bytes or generation/export behavior. Keep all three locales. All changed Vue scripts must match the baseline after line-ending normalization; Drafting templates remain unchanged. AppTopbar may wrap existing model information in details/summary without new business state.

Verify against the saved original Demo, local prototype A and the running in-app browser. Use existing focused tests, typecheck/build, independent review, actual viewport and locale observations, and read-only before/after browser-validation API snapshots. CSS edits are reversible and do not require implementation-mirroring tests. Preserve observed failures and corrected screenshots separately.

Evidence directory: `C:/Coding/ConSense/output/drafting-a-implementation-20261005/a24-style-consistency-20261006/`. Track as PREVIEW-07; prior verification remains immutable.
