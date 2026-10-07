# BG-05 selection pool and visible hierarchy verification

User correction on 2026-10-07. Product integration pin: `424548744d5f3de71a1fa106075bf980c01408c7`, owner commit `7fa4a4a2a517912115ccb580482133776be547c3`. Correction baseline `9e5cd601`; refined acceptance `74b2fe35`. Service remains clean at `67ceb7081d42204aa17b12b149f52081e348da71`. Later closure commits change only local documentation.

Evidence root: `C:/Coding/ConSense/output/drafting-business-graph-correction-20261007`.

## Reproduced failure and implementation

Actual browser at 15240 reproduced 79 inputs / 110 actions reducing to 3 inputs / 1 action after selecting foundation inclusion. Original RED DOM identities/positions and screenshot are retained in `browser-red-pool.json` and `browser-red-selection.png`. The readonly hierarchy audit confirms the observed saved Feishu source keeps its already-filtered pool and applies dimming. This correction does not claim fresh online access to the Feishu source.

Explicit filters now define a stable pool. Input, action and structural-child selection highlights the chain and dims unrelated selectable nodes, with related context appended outside the pool. A separate clear-selection control preserves filters; reset-filters retains its existing reset purpose. Groups, confirmed compound parent, existing input fields and schema item fields are visibly distinct. Technical IDs are hidden. Shared conditions and per-row applicability reuse existing evaluators, without new project inputs or rules.

## Acceptance results

- Public rendered controls: four retained RED/GREEN families cover pool retention, hierarchy, clear selection and search/row presentation. Eleven final component slices pass alongside model, original-reader and parent integration gates. Owner receipt: `implementer-gates.json`.
- Merged regression: 35 CJS suites / 39 checks / zero failures at the product pin, including exact step-1/2 scope protection, document workspace, typecheck and build. `final-verification/manifest.json` fixes source identity and log hashes.
- Independent Spec and Standards reviews clear, in `spec-review.md` and `standards-review.md`. The Spec auditor additionally replays frozen actual catalogue/plan/values through the rendered controls; `spec-frozen-actual-receipt.json` confirms 79 inputs / 20 schema children / 110 actions, 20 organising groups plus the one confirmed compound parent.
- Actual in-app browser: opening all groups/files shows the same 79 / 110 pool plus 20 structural fields. Selecting foundation preserves all input/action/child/container/file identities and SVG transforms, with 207 unrelated graph nodes dimmed. Action selection preserves counts; the grey contract-identity input remains selectable; clear selection removes dimming without resetting the pool. Evidence: `browser-green-pool.json`, `browser-selection-controls.json`.
- Actual hierarchy: contractType displays foundation inclusion and 39-month threshold as subvariables; optional month count is a supporting value. Bill list shows seven item fields, no technical ID, and its description inspector shows all 13 current rows. Child search retains the real parent. Filtered child selection preserves original node positions; clearing restores the same pool and search/group values. `browser-bills-child.json`, `browser-filtered-pool.json`.
- Actual navigation: child return opens step 2 and focuses `field-billNos`. Foundation action opens step 3 original NTT and the exact on-demand-bond binding `CS6f9e29991d10c93b8782c05784b8`, without generation. `browser-native-navigation.json` records the original paragraph. Parent/input and native guards also pass their existing suites.
- Actual 650 × 850 breakpoint: no page overflow; graph retains internal horizontal scrolling and the inspector remains accessible. Override is reset. `browser-narrow.json` and screenshot. Three locale labels and readonly values are independently exercised by rendered tests.
- Fresh seven-resource GET snapshots before/after are byte-identical: catalogue, variables, templates, documents, inputs, progress and plan. `preservation-before.json` / `preservation-after.json`. Graph QA performs no save, adopt, extraction, generation or body edit.

The displayed plan remains a current computed plan, not evidence that historical saved DOCX changes have been attributed. Existing historical binding and numbering limitations remain unchanged. No new provider-quality, export-layout or download acceptance is claimed. Earlier ticket closures and failed observations are retained independently; prior cleanup-policy housekeeping is not retried by this correction.
