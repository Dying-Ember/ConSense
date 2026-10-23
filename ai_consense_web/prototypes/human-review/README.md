# Template question review — disposable prototype

Question: can business reviewers correct recognized questions and relationships beside the source, in Simplified Chinese and English, without editing a graph or code?

Decision: source-first manual correction has passed this prototype's functional acceptance, including source-based recovery and save/restore. This branch captures the prototype only. No production integration is approved by its checks.

Revised question after feedback: can reviewers work directly on original page images, select words to correct a result, and handle one question at a time? The former multi-panel layout was rejected as too complex. This revision removes the permanent inventory, metrics and form panels.

Use the loopback preview because original-page images are fixed local assets. Current edits are in memory and clear on refresh. Explicit Template graphs saves immutable versions in this browser's IndexedDB, and users can download portable backups. No production route, document write, model call or production database is connected.

The current local preview is `http://127.0.0.1:5191/`. Local data and source quotations remain outside Git under `D:/R&D/ConSense/.local/drafting-human-review-prototype-20261016/`. Four template files cover the existing definition set; nested details, PDF verification items and source notes remain distinct. Internal provenance is not rendered in the interface.

Rebuild with the existing local data and page assets (standard-library Python):

```powershell
python ai_consense_web/prototypes/human-review/build_prototype.py --data-root D:/R&D/ConSense/.local/drafting-human-review-prototype-20261016 --output D:/R&D/ConSense/.local/drafting-human-review-prototype-20261016/index.html
```

`prototype-shell.html` contains the document-first shell. `prototype.logic.js` keeps the review state transitions separate from the DOM handlers. `build_prototype.py` inserts existing definitions, concise bilingual business wording and native word coordinates. `preview.py` serves only that page and an exact allowlist of images inside the local prototype directory on loopback. It provides no directory, arbitrary file or write endpoints.

To prepare the local page assets again, use the installed Python environment with `pdfplumber` and `pypdfium2`:

```powershell
python ai_consense_web/prototypes/human-review/build_source_view.py --workspace D:/R&D/ConSense --data-root D:/R&D/ConSense/.local/drafting-human-review-prototype-20261016
python ai_consense_web/prototypes/human-review/preview.py --page D:/R&D/ConSense/.local/drafting-human-review-prototype-20261016/index.html --port 5191
```

Word page images reuse historical visual PDFs whose original DOCX identity and PDF digest match their source binding receipts. They are layout previews, not a new Word-pagination certification. PDF SCC images come from the original PDF. Native word boxes provide a selectable transparent text layer over actual page images; no OCR or semantic model is involved. Exact note matches require the complete normalized quotation and its original page constraint. Unresolved notes keep their page hints and quotations and can be manually linked by selecting the right words.

Interaction: click an existing passage marker to see linked questions. Drag a box around words, then click individual words to add or remove them; double-click selects one word. The selected source text is previewed before adding or linking a question. Split/affected-text selection uses an explicit Use these words step so the same adjustment is available before writing the draft. The question card initially shows Confirm and Correct this. Correction supports rename/answer format/fixed content, split into two source-linked questions, merge duplicates, remove/restore, edit explicit conditions and select affected text on multiple pages. All actions are reversible. Unsaved edits block changing document or question. SCC editions remain isolated. Complex groups with active details must be reviewed individually before splitting/merging/excluding the group. Automatic graph inference, automatic condition inference and actual text replacement are outside this prototype.

Selection correction: SCT page 2 revealed that native PDF content order interleaves the sidebar and contract body. Browser text ranges and a contiguous start/end slice therefore selected the wrong column. Selection now uses page geometry and stores explicit word IDs; legacy ranges remain supported for existing source records. Geometry is visual, with no model or inferred column boundary. Each drag selects whole words within the box; a box deliberately covering two columns can select both. Character-level clipping and touch-box selection are not implemented.

The in-app browser checks cover all existing definitions, real word selection, source-linked additions, split and undo, multiple-page affected-text selection, action retention after removing one passage, invalid-page input, draft protection and SCC isolation. Changing an answer format invalidates an existing incompatible manual condition. `node ai_consense_web/prototypes/human-review/check_review_state.cjs` checks state invariants using synthetic data. Preview routing and source matching have small assert checks. These checks do not establish model stability, complete source quality or business acceptance.

The selection regression is checked against synthetic interleaved columns, the real SCT page 2, sparse saved highlights and click lookup:

```powershell
node ai_consense_web/prototypes/human-review/check_word_selection.cjs --source-view D:/R&D/ConSense/.local/drafting-human-review-prototype-20261016/source-view.json
```

The current acceptance starts with **zero questions across all four documents**, retaining imperfect guidance marks and the selectable source. Use Start review to begin. Reviewers derive questions from the original pages; importing the old catalog is not a reconstruction test. A later catalog comparison is an omission/duplication audit after independent findings are frozen, not a count-matching gate.

Manual correction now supports bilingual choice options, record/list detail fields, sharing one question across compatible documents and removing an incorrect source link. Word and PDF SCC sources cannot be combined in a question, condition or detail group. A condition can contain several cases, each with its own all/any conditions and result. Multiple-choice membership uses Includes/Does not include; single-choice conditions use the existing answer options. Each affected passage records its applicable case, action and either fixed replacement wording or the question supplying the answer. These are review instructions; the prototype does not execute document replacement or infer rules.

Cases for a derived Yes / No or single-choice question now retain an explicit typed answer. Older pending cases without that answer remain recoverable and require a result before confirmation. Confirmed empty / Provided with content conditions are available for text, multiple choices and lists; an unanswered field is not declared empty. Answer-sourced passages retain exact prefix/suffix whitespace and punctuation. A scalar detail in its actual list can also be declared as a join of all item answers, with an exact separator and surrounding wording. Join records, their list IDs and their answer-source IDs survive save/load and append remapping; this records the author's instruction without evaluating project answers.

Structural changes that alter a binding retain its old conditions and answer sources as needing rebinding, while preventing confirmation until they are reviewed. Condition inputs, list scopes and their ancestors preserve Word/PDF SCC isolation. The manual-check role remains available when editing an item. Need more information opens the existing drawer with a required explanation and Save / Cancel, avoiding browser prompt support. Selection-toolbar buttons remain mounted across scrolling and selection updates, and sit above notifications so a long selection can still be captured.

Save review prepares an explicit download link. Load review validates a saved file, then asks to append it while retaining current work. A visible copy/paste backup provides the same validation path when a browser restricts local files. Nothing is uploaded to a service. The package retains source identity, sparse word membership, options, parents, cases, affected text, history and unresolved work. Conflicting variable IDs are remapped during append; matching names are never automatically merged. An input change invalidates dependent conditions or answer sources. Pending work remains saveable, and an unresolved target prevents confirmation.

The pure modules `review-fields.js` and `review-pack.js` are embedded in the generated HTML. Run their small self-checks together with the review-state and word-selection checks:

```powershell
node ai_consense_web/prototypes/human-review/check_review_fields.cjs
node ai_consense_web/prototypes/human-review/check_review_pack.cjs
node ai_consense_web/prototypes/human-review/check_review_state.cjs
```

Historical checkpoint: the earlier `acceptance-combined-final.json`, its aggregate counts and restore receipts, and the initial Word definition-only export describe an earlier stage. They do not represent the current combined result or establish complete amendment coverage. In particular, an old definition confirmation is not acceptance of all effects or proof of a real project fact. The independent source reconstructions remain separate from later catalog omission/duplication audits; an old catalog or synthetic fixture is not a reconstructed review.

Current independent evidence remains local under the prototype data root. Use the source-specific packages and receipts for each claim:

- [NTT/SCT latest independent package](D:/R&D/ConSense/.local/drafting-human-review-prototype-20261016/blank-rebuild-ntt-sct/ntt-sct-final-nonempty-complete-guidance-restored.json) and its [semantic coverage record](D:/R&D/ConSense/.local/drafting-human-review-prototype-20261016/blank-rebuild-ntt-sct/ntt-sct-final-independent-semantic-coverage.json).
- [Word SCC independent acceptance report](D:/R&D/ConSense/.local/drafting-human-review-prototype-20261016/blank-rebuild-word-scc/word-final-acceptance-report.md), including its current package, per-family evidence, restoration receipts and unresolved project facts.
- [PDF SCC final independent acceptance report](D:/R&D/ConSense/.local/drafting-human-review-prototype-20261016/acceptance-pdf-scc/pdf-final-acceptance-report.md), covering the 37 source families and later omissions found in the original pages, with its historical failures retained.
- [Real GUI section-join verification](D:/R&D/ConSense/.local/drafting-human-review-prototype-20261016/acceptance-pdf-scc/section-join-real-gui-verification.json): join metadata, switching to a single answer, structural invalidation, refused confirmation and Undo are recorded.
- [Real GUI ancestor-isolation regression](D:/R&D/ConSense/.local/drafting-human-review-prototype-20261016/blank-rebuild-ntt-sct/ancestor-isolation-gui-regression-receipt.json): opposite-edition inputs/scopes are absent from the candidate lists and a normal same-edition rule can be saved. Forced-invalid form/reducer rejection is checked separately by the module regressions.
- [Real GUI Need more information regression](D:/R&D/ConSense/.local/drafting-human-review-prototype-20261016/blank-rebuild-ntt-sct/need-more-information-gui-regression-receipt.json): the drawer rejects an empty explanation, retains the saved request in an actual download, and preserves the baseline variables and source reviews after Undo and Cancel. The original unsupported-prompt failure is retained separately.

The final actual GUI export is [acceptance-combined-final-verified.json](D:/R&D/ConSense/.local/drafting-human-review-prototype-20261016/acceptance-combined-final-verified.json). It contains 291 records: NTT/SCT 62 (including two retained old records), Word SCC 115 and PDF SCC 114. These are questions, groups, detail fields and checks, not 289 independent project inputs. The 594 source anchors, 1,035 affected passages and 365 explicit cases are recomputable per record in the [source-membership audit](D:/R&D/ConSense/.local/drafting-human-review-prototype-20261016/acceptance-final-source-membership-audit.json). Counts are descriptive, not a recall gold standard. Source-specific semantic audits found no remaining definite P1/P2 gaps in the reviewed declaration scope; external project facts remain unknown.

The [independent append audit](D:/R&D/ConSense/.local/drafting-human-review-prototype-20261016/acceptance-final-independent-append-audit.json) confirms all 291 incoming objects survive the real GUI append with collision remapping and exact join bindings. The [independent reference audit](D:/R&D/ConSense/.local/drafting-human-review-prototype-20261016/acceptance-final-independent-reference-audit.json) confirms valid references, same-item scopes and Word/PDF ancestor isolation. Actual GUI refresh, zero-variable restart, file chooser load and another product download preserve variables, source reviews, next ID and guidance mode exactly; see the [restore receipt](D:/R&D/ConSense/.local/drafting-human-review-prototype-20261016/acceptance-combined-final-verified-restore-receipt.json) and [Undo receipt](D:/R&D/ConSense/.local/drafting-human-review-prototype-20261016/acceptance-combined-final-verified-undo-receipt.json). History is retained with additional import/Undo events rather than requiring byte-identical event logs.

The final review repaired actual omissions in wall-tiling branches, the next-page SSF proviso, LA inventory and per-tree serial checks, the two-envelope premise, and standalone-building eligibility. Required text slots use Provided with content. Explicit empty PQS amendment text still enters manual checks; it does not become an automatic replacement. The earlier failed and incomplete snapshots remain local. The [acceptance summary](D:/R&D/ConSense/.local/drafting-human-review-prototype-20261016/acceptance-summary.md) records the final evidence and limits, and the [code-check receipt](D:/R&D/ConSense/.local/drafting-human-review-prototype-20261016/final-code-checks-receipt.json) records four behavior self-checks, preview routing, six syntax checks, diff checks and exact embedded-module checks.

This acceptance concerns human-authored definitions, conditions, amendment instructions and their save/restore behavior. It does not establish actual project evaluation, automatic list aggregation, contract replacement/generation, OER source quality or model effectiveness. No new model/API calls were made for this acceptance. The prototype remains on its isolated branch without integration into main, production services or a production database.

Template-bound graph saving was added after that fixed acceptance snapshot. Use the header's **Template graphs** button, name the graph and choose **Save a new version**. The complete existing review state is the graph's single authoritative representation: variables, parent/detail membership, options, conditional cases, answer sources, joins, exact source/target word memberships, history and unresolved/excluded states. Nothing is reduced to confirmed records, and there is no second inferred edge model.

`template-graph.js` wraps that state in `consense-template-graph-v1`. Each graph records four distinct template bindings: document ID, original filename/format, original SHA-256, visual-PDF SHA-256 and the canonical layout-data SHA-256. Building the page verifies actual original and visual bytes against the local source receipt before embedding the path-free bindings. The original review source key remains unchanged for old review-file compatibility. Graph restore checks that key, every template binding, existing `ReviewPack` constraints and exact selected source wording before replacing current work. Replacement requires the visible **Use this version** action and can be undone. Old review-file import continues to append separately.

IndexedDB saves use `add` and report success only after transaction completion; each save keeps its own immutable ID. Refreshing the page leaves the saved versions available. Local quota/transaction failures retain the failed snapshot for a download and explicitly notify even if the reviewer has left the graph panel. Browser storage is specific to this browser and origin; clearing it or changing machines requires the downloaded JSON backup. Neither automatic template migration nor production graph execution is implemented. Source-view rebuilding names images with their visual PDF digest so changed documents do not reuse an unrelated cached page image.

Run the graph and original-binding checks alongside the existing checks:

```powershell
node ai_consense_web/prototypes/human-review/check_template_graph.cjs
python ai_consense_web/prototypes/human-review/check_template_bindings.py
```

The real GUI persistence/restore evidence is separate from the earlier acceptance: [template graph verification receipt](D:/R&D/ConSense/.local/drafting-human-review-prototype-20261016/template-graph-20261017/gui-verification-receipt.json). Its actual product downloads retain all 291 records, 594 source passages, 1,035 affected passages and 365 branches from the accepted review. Tests cover refresh and reload from browser storage, a second immutable version, actual backup-file import, a deliberately invalid binding fixture rejected without changing work, and Undo of graph replacement. The quota failure is a deferred-transaction code regression, not a claim of a real browser quota exhaustion or a complete browser restart test.


The header's **Graph overview** now follows the formal Drafting business graph's presentation: a white modal, readable unscaled nodes, three left-to-right columns, and a right-hand inspector. Inputs and real detail fields are green, recorded amendment instructions pale orange, and source documents blue. The selected chain uses orange borders and lines; other nodes remain dimmed and selectable. Fullscreen, keyboard focus containment, Escape and returning to the source are supported. No speculative business-topic groups or document-card grid is shown. Parent/detail indentation comes only from existing parentId records.

The complete current review remains available. Retired records are shown by default and can be hidden. The file selector narrows the view to NTT, SCT, Word SCC or PDF SCC while retaining direct shared context; the SCC editions remain separate. Search presents matching variable names and marks them without deleting the rest of the graph. Selecting a result locates it at readable size rather than fitting every record into tiny text.

`graph-business-view.js` reads the existing `GraphOverview` projection and produces display-only variables, recorded conditional amendment instructions and four file destinations. Every explicit condition branch has its own instruction; unbranched targets, missing-case targets, split records needing review and earlier reference-only descriptions remain distinct. A branch with no exact amendment location stays visible and requires review. Different branches and different actions for the same passage are not merged. These are all recorded possibilities, not a plan evaluated from actual project answers.

Selecting a variable highlights its recorded inputs, potentially affected items, all its own instructions and destinations. Selecting a specific instruction highlights only that instruction's inputs, owner and destinations, so mutually exclusive cases are not presented as shared prerequisites. Parent membership, historical references, excluded, missing, incompatible, stale and unresolved links do not propagate potential effects. Unknown condition combinations and unhandled cases remain explicitly pending.

The inspector contains full bilingual condition wording, all/any combinations, answer sources, joins, amendment text and source evidence. It can open either a selected instruction's exact target range or the selected variable's complete potential downstream range on the original document. Clearing, editing, importing, replacing or undoing state clears the previous presentation-only range. Changing the graph's document filter also synchronizes the underlying source before closing, so word selection cannot run against the wrong document.

The saved review state remains the single authoritative graph representation. No second graph or rewritten business package is persisted by this presentation. Run the projection and actual-rendering checks with the existing review, persistence, binding and word-selection checks:

```powershell
node ai_consense_web/prototypes/human-review/check_graph_overview.cjs 'D:/R&D/ConSense/.local/drafting-human-review-prototype-20261016/template-graph-20261017/graph-v2-original.json'
node ai_consense_web/prototypes/human-review/check_graph_business_view.cjs 'D:/R&D/ConSense/.local/drafting-human-review-prototype-20261016/template-graph-20261017/graph-v2-original.json'
node ai_consense_web/prototypes/human-review/check_graph_overview_ui.cjs
```

The previous grid-view evidence remains historical under graph-overview-20261017; it does not describe this revised presentation. Current GUI evidence is recorded separately under [graph-style-restore-20261017](D:/R&D/ConSense/.local/drafting-human-review-prototype-20261016/graph-style-restore-20261017/acceptance-report.md). This remains an isolated prototype, without main integration, production writes, new model calls, actual project evaluation or contract generation acceptance.

## Drafting integration study — pending user confirmation

This revision answers a workflow question: can source-based rule and fact review replace the fixed-catalog review step without losing template upload, correspondence extraction, explicit adoption or the existing document workflow? It mounts the existing editor inside the real application shell at `http://127.0.0.1:5192/#/drafting?reviewPrototype=1`. The previous standalone editor remains available on 5191. This is one logic-flow study using the already chosen document interaction, rather than another set of visual alternatives.

The developer-only entry follows five stages: sources, candidate recognition, original-source review, adopted project values/actions, and preview/export. Template requirements produce candidate questions and rules; correspondence produces candidate project facts. Both remain proposals until their respective review/adoption steps. Local file selection is session-only and is not parsing. The explicit built-in demonstration uses the existing source-bound template graph and a fictional meeting note; unknown selected files never inherit these assets or results. Recognition is explicitly an offline demonstration, with no model call. All four template sources remain available; the project explicitly chooses the Word or PDF SCC edition. An incomplete review is a draft, not an approved generation basis. The bond example demonstrates only one recorded chain; it does not execute the other graph rules or generate a contract. PDF rule review does not supply an editable Word original.

Run the editor and the isolated application in separate terminals:

```powershell
python ai_consense_web/prototypes/human-review/preview.py --page 'D:/R&D/ConSense/.local/drafting-human-review-prototype-20261016/index.html' --port 5191 --frame-origin http://127.0.0.1:5192
cd ai_consense_web
node node_modules/vite/bin/vite.js --config vite.review-prototype.config.ts
```

The dedicated Vite config provides an example project and an exact read-only mapping to the saved sample graph. It has no backend proxy: all service mutations are rejected and all unlisted API reads fail. The iframe uses the original state, validation and local version storage. Its optional integration messages require the fixed loopback origin and matching parent/frame window, validate imported template identity, and refuse replacement after review starts. Moving between workflow steps keeps the editor mounted. The parent retains only an in-memory review snapshot; it does not persist a second graph representation.

Implementation after workflow approval must replace the rule source consistently across catalog, allowed inputs, extraction, validation, plan and generation. Replacing only the frontend graph would leave the old fixed business rules active. Reuse the normal OER/native/canonical source chain, exact original bindings, source editor and existing stale-document checks; add a versioned human-review draft/confirmation contract rather than storing template definitions in project answers. Keep ordinary Drafting available until that full path is implemented and verified. The current main checkout contains other ongoing OER changes and is not overwritten by this study.

### Compatibility decisions that the implementation must keep

| Existing capability | Place in the revised flow | Required behavior |
| --- | --- | --- |
| Template upload and explicit replacement | Sources | Preserve actual source format, identity, parse/partial/failure state and edition. Choosing a file is not a successful parse. |
| Correspondence upload/removal | Sources | Project facts come from these documents or explicit human input. Templates are not factual project evidence. |
| Recognition, partial/failure results and trace history | Candidate recognition | Candidate rules and values are linked to the source and definition revision used by that run. A failed or late run cannot masquerade as the current successful result. |
| Candidate correction and adoption | Source review, then adopted values | Correcting a candidate does not adopt it. Only explicitly adopted compatible values enter the plan. Reruns preserve human values and show review needs instead of overwriting them. |
| Exact source interaction | Source review | Keep word selection, missing-question addition, split/merge, real detail membership, shared cross-document values, conditions, answer sources and exact target edits. Keep SCC editions separate. |
| Typed atomic interpretation | Source review | Present the requirement, answer shape, scope, prerequisites, joint conditions, result and target action in business language. Do not turn every processing/check node into a project question. |
| Template graph save/restore | Source review | Save drafts separately from confirmation for drafting. Retain source bindings, unresolved work and old versions. A parent in-memory snapshot is not a durable save or backend publication. |
| Plan, unresolved items and target decisions | Adopted values/actions | Use the same confirmed rule/definition version as the form, extraction and generation. Unknown is not No, and not adopted is not a deletion instruction. |
| Three-document generation and precise edits | Preview/export | Reuse original bindings, compilation guards and all-or-nothing publication. Only actual generation receipts may label edits applied. |
| Body editing, save/discard, regeneration and Word/PDF export | Preview/export | Preserve artifact revision, stale/current checks and unsaved-edit protection. Regeneration creates a new version; do not promise automatic replay of prior body edits. |

The legacy field `foundationIncluded` actually asks whether this is a combined contract including foundation works. It is not an exact synonym for either of the two independent atomic questions. Migration must retain the old candidate and quotation, and explicitly review its mapping; it must not silently copy either Boolean answer into two separately adopted facts. The demonstration splits this compound candidate and keeps its two source-supported facts unadopted until the reviewer adopts each one.

Rule draft changes preserve the current confirmed version and its documents. Confirming a new rule version rechecks candidate/schema compatibility and invalidates dependent plans/results; incomplete dependency knowledge retains the existing whole-document stale protection. Splitting, merging, type/meaning changes and source replacements preserve historical values while making incompatible mappings pending. Merely changing a rule or renaming a display label must not clear unrelated human-adopted project facts.

The prototype's correspondence editor and three scalar facts are a bounded interaction example. Full dynamic extraction, all atomic recipes, list/scoped rule evaluation, target overrides, body editing and real exports remain implementation work. Existing production capabilities are retained by the ordinary Drafting route and are not replaced by this sandbox. Replacing the old workflow is complete only when these consumers share the confirmed graph/definition version and the existing end-to-end contracts pass.

The revised five-step prototype is ready for workflow confirmation. The local [full-flow acceptance report](D:/R&D/ConSense/.local/drafting-human-review-prototype-20261016/integration-full-flow-20261017/acceptance-report.md) distinguishes actual browser checks from mounted-message tests and unexecuted formal capabilities. Source correction does not adopt facts; reruns retain manual values and reasons. Unsaved candidate edits block switching or continuing. Saved illustrative body text becomes read-only stale content when its plan changes and requires explicit regeneration before replacement. The existing 26 editor commands, four integration checks, type checking and production build pass; the ordinary build contains no HumanReview prototype chunks. No new model/API calls or main integration occurred.
