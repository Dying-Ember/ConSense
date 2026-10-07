# REVIEW-F2 frontend conflict visibility verification

Date: 2026-10-06. Integration web checkout: `C:/Coding/ConSense-isolated/drafting-implementation-20261005/ai_consense_web`, branch `feature/drafting-a-business-rules-20261005`, starting HEAD `2efd4d72b521bdddd82e256cb1960daac1fcc323`.

## Actual failure and correction

The real MiniMax-M3 TEST ONLY project `review-fix-test-e7d4bea8-add3-4d8b-9303-08375c6e962a` has `subcontractors.value=""`, two candidates (`["Electrical"]` and `[]`) and server `adoptionState="conflict"`. Its extraction trace records `candidate_conflict`. The root browser check nevertheless found no matching question in the candidate-conflict filter because `DraftingView` returned `missing` before considering the server conflict.

The two-line correction identifies an untouched server conflict in the existing missing-value branch and prevents stale conflict metadata from overriding a valid local edit. Field badges and the existing group/filter derivation now retain blank conflicts. A locally edited valid selection follows the existing manual state; ordinary unanswered values remain missing and invalid edits retain the existing needs-review validation. Applicability, review diagnostics, catalog, group precedence, labels, adoption controls and other UX rules are unchanged.

## Agreed public test seam and evidence

The requested existing Node CJS frontend test pattern compiles and mounts the real `DraftingView`, real local components/modules and Vue/Pinia runtime. Only the backend API and unrelated external PDF boundary are doubled. Assertions use rendered badges, question groups, checkbox interaction and status-filter controls; they do not invoke or extract the private `state` function. No test infrastructure or new dependency was added.

Immutable logs are in `C:/Coding/ConSense/output/drafting-a-implementation-20261005/a20-independent-review-fixes-20261006/frontend-conflict/`:

- `01-blank-candidate-conflict-red.log`: actual rendered `state missing`, expected `state conflict`.
- `02-blank-candidate-conflict-green.log`: field/group conflict, both candidate source labels and conflict-filter visibility pass.
- `03-local-selection-overrides-conflict-red.log`: after a rendered checkbox selection, actual `state conflict`, expected `state manual`.
- `04-local-selection-overrides-conflict-green.log`: local selection appears in the manual filter and leaves the conflict filter; no save/adoption API writes occur.
- `05-view-controls-boundaries-green.log`: all three added view scenarios pass, including ordinary missing and a negative manual number remaining needs-review. Existing extraction diagnostics/history/race scenarios also pass.
- `06-nine-frontend-regression.log`: all **9 existing drafting CJS test files pass**, `FAILED False`.
- `07-typecheck.log`: `vue-tsc --noEmit`, **exit code 0**.

The original real-provider failure records and the two supplemental provider directories remain unchanged. This change has no backend, provider, model configuration, extraction, source document or existing-project mutation. Vite HMR is already managed by root; the real-browser post-fix check remains root's separate acceptance evidence.

## Commit scope

Only `src/views/DraftingView.vue`, `tests/drafting-extraction-view.test.cjs` and this note belong to this fix. Coordinator-owned edits to `docs/specs/drafting-independent-review-fixes.md`, `docs/specs/drafting-independent-review-fixes-tickets.md` and `docs/agents/issue-tracker.md` are excluded.
