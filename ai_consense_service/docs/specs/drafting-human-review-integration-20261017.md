# Drafting source review integration

Status: implementation and isolated code/browser verification complete on 2026-10-17. The user accepted the five-step workflow. Source-quality, model-effectiveness, production database migration and deployment acceptance remain separate.

## Required workflow

Sources → identify candidates → review original sources → adopt project values and inspect actions → preview/export. Preserve template upload/replacement, correspondence upload/removal, failed/partial parse states, extraction history, explicit candidate adoption, three-document generation, body edits/save/discard, regeneration, revision-bound preview and Word/PDF export.

Templates establish questions and rules. Correspondence establishes candidate project facts. Correcting a candidate is not adoption. Saving a rule draft is not publishing a confirmed rule. The old combined-foundation Boolean is not silently copied into two independent facts. Unknown, false, zero and an explicitly empty collection remain distinct.

## One rule authority

The saved review state remains the authoritative graph. Its formal envelope is `{kind:consense-template-graph-v1,graph:{variables,sourceReviews,events,nextId,blank,guidanceMode},sources,selectedDocuments}`. `selectedDocuments` maps NTT/SCT/SCC to the server source ID. Source IDs, original SHA, semantic/source hash and analysis revision are separate identities. Both SCC editions may be reviewed, but their definitions, dependencies and edit targets must stay isolated; generation uses the selected edition.

Each saved revision is immutable. Saving a draft does not change the confirmed head or invalidate the current finished documents. Confirming compares the expected head and draft under the existing project lock, validates sources and definitions, and creates a confirmed revision. Input meanings/types/options/membership have fingerprints: a changed input preserves its historical value but requires re-adoption. Changing only target instructions does not erase unrelated project facts.

No confirmed graph retains the existing catalogue and rules. Once a graph is confirmed, catalogue, allowed field IDs, types, extraction schema, candidates, adoption, plan, target overrides and generation all use that definition. Snapshots include the graph revision, source identities and adopted input state. Ordinary body edits, artifacts and exports retain their existing revision guards.

## Source and edit seam

Reuse OER/native observations and the existing `DocxTemplateEditor`; do not create a parallel parser/canonical model. Source review reads stored evidence without lazy reparse or DB refill. Word reading exposes native paragraph IDs, text including controls, original SHA and paragraph SHA. Its offsets are UTF-16 `editableText` positions, separate from OER named-channel ranges and PDF word boxes.

A source selection may contain multiple exact spans. The server independently binds every span against the chosen original. `nativeBinding` records status, original SHA and spans with paragraph ID/SHA, channel, start/end and exact expected text. Repeated text, malformed or overlapping spans, unsupported fields, missing source or uncertain scope remain explicit. A sparse selection is never expanded to its first/last word. PDF observation does not supply an editable Word original.

Source-ID plus expected-original-SHA asset endpoints prevent a non-primary edition preview from silently opening the primary template. Source originals are content-addressed and immutable for historical revisions. Browser source paths are never accepted.

## Identification and adoption seam

Stored guidance and markers seed pending review items. Optional semantic discovery uses the configured model, independent bounded guidance contexts and deterministic local-ID assembly; it retains raw failures and never confirms or applies its own suggestions. It does not feed accumulated model proposals into subsequent contexts.

Correspondence extraction can name a saved draft revision, producing candidates only. Confirmed extraction fixes both definition and evidence revision at dispatch and checks them again before publishing. Candidate correction/reassignment appends history and stays separate from adoption. Adoption verifies the displayed candidate, graph revision, field fingerprint, original source and exact quote. Reruns retain manual values/reasons, marking actual changes for review. No internal or external model call is required for code tests.

## Verification seams

- Pure graph: scalar/collection typing; independent Boolean conditions; all/any and scoped cases; derived values, conflict/unknown handling, value-source joins; missing references, cycles and SCC edition isolation.
- Native binding/compiler: exact original and paragraph SHA, UTF-16 controls, sparse selections, unsupported fields, overlapping operations and source drift; untouched paragraphs preserved.
- Persistence/adoption: immutable revisions, optimistic head/draft conflict, source replacement, schema compatibility, candidate-only draft extraction/correction, manual retention and project deletion cleanup.
- Consumer integration: confirmed graph reaches catalogue/list/extract/update/plan/snapshot/selected template; legacy projects keep their existing implementation.
- Frontend: actual APIs and dynamic source identities, five-step state, unsaved edits, source/graph change invalidation, literal source interaction and adopted-value display; no localhost prototype iframe or private example data.
- Regression: isolated Java and frontend suites, type checking and build; retain failures and optional/environment-dependent skips. Report these separately from real model/OER/browser/business/deployment acceptance.

The active main workspace contains independent OER work. Implementation tests use its frozen current source in an isolated worktree. Only the reviewed implementation delta may be transferred back, with conflict checks that preserve unrelated changes. Do not deploy, restart active services, run new OCR/model calls, push or publish originals as part of this implementation.

## Implemented and verified

The formal Vue workflow now uses the project graph APIs and immutable database revisions. It includes source selection, exact original interaction, question split/merge and cross-file unification, typed conditions, per-item actions, graph overview, draft history, explicit confirmation, correspondence candidates and explicit project-value adoption. Unconfirmed projects retain the existing catalogue. Confirmed projects use one graph authority throughout extraction, adoption, planning, source selection, snapshots and generation.

The saved-document reader keeps existing identity and revision guards. When a complete edit projection cannot be verified, it displays the sanitized actual saved document, clears unsupported source-only change interactions, and labels the mapping unverified. Original-template mode remains a separate carrier. A missing mapping never substitutes old template text for the current saved body.

- Final backend `mvn verify`: 3,307 tests, 3,188 passed, 119 existing conditional skips, zero failures/errors; packaging succeeded. The 51 new review/migration seam tests all executed without skips, including the saved 291-record graph structure check.
- Final frontend: 189 tests passed, zero skips; `npm run build` passed with type checking.
- Actual isolated browser/API verification: upload synthetic NTT/SCT/SCC and correspondence; create and bind questions from the original; merge NTT/SCT duration into one input; save/confirm rules; manually adopt 42; generate the three documents; inspect actual DOCX and LibreOffice PDF; navigate the full graph and source; restore history as a new draft; edit and save a body paragraph as a new document revision. Changing a source invalidated old adoption; merely saving a rule draft preserved the current confirmed output.
- V23 was executed as written in isolated H2 MySQL mode and checked for old-row preservation and input-binding uniqueness. This is not a production MySQL/Flyway migration run.

## Standards

Independent review and the final reading-fallback supplement found no unresolved blocker. The implementation reuses the OER/native source chain, existing document editor, sanitization, project lock and artifact revision guards. Source-only adoption must preserve the dirty OER baseline and all active main build artifacts.

## Spec

Independent connected-code review and the final seam check found no unresolved blocker in draft/confirmed authority, graph consumers, SCC edition isolation, immutable originals, or saved-body fallback. Optional model discovery remains bounded and candidate-only. No new real model inference or OCR/effectiveness evaluation ran in this implementation.

Earlier failed checks are retained in the local evidence directory. Four existing Vetting fixtures were migrated to the already-established empty-source v2 hash; the frozen Q1.6 expected material gains only the existing ordinal-based public citation alias in memory. Original file hashes, complete ordered-material assertions and business negative gates remain intact; no failure was hidden by adding a skip.

Full logs, conditional-skip reasons, baseline comparisons, browser screenshots and source-adoption receipts are under the ignored local directory `D:/R&D/ConSense/.local/drafting-human-review-implementation-20261017/`. The existing graph experiment HTML ledger records this implementation separately from the historical 67 model generations and 11 input estimates; those budgets and model-effectiveness verdicts are not resumed or upgraded.

## 2026-10-17 OER consumer seam follow-up

The current Word sources support source-bound reading, human rule review, explicit project-value adoption and generation from the selected original. This does not grant complete automatic template interpretation. The existing path remains `DocumentParser.parseTemplate -> OerTemplateEvidence -> SourceDocument -> DraftReviewSources -> reviewed graph -> adopted project values -> DocxTemplateEditor -> revision-bound artifacts`.

A fresh audit found that the source evidence was present but the discovery projection dropped information: 16 of the 63 complete semantic scopes used ordered members or nested targets and became empty text; 3 SCT fields used a pre-normalized named channel; one SCT Bill endpoint had no guidance association. The projection now preserves these representations and the complete field observation, including field labels, insertion anchors and unresolved states. Only the unassociated endpoint gets a new pending source-review item. The other 27 field candidates remain reachable through their guidance associations.

Reading now exposes the existing native SHA/part/expanded-name paragraph path from the shared OER path function. Discovery maps a named-channel range to an editable span only when source position is unique, the entire named-channel text equals editable text, the UTF-16 range and observed text agree, and the displayed quotation equals the ordered span text. Structured source ranges cannot fall back to a different same-text paragraph. Multiple spans remain separate; zero-length insertion anchors never become whole-paragraph replacements.

All explicit multi-span bindings now validate the displayed quotation using the existing newline-join convention. Multi-span replacement/fill requires one exact replacement placement: discovery keeps it under review, the shared plan returns an unresolved placement issue, new target overrides are rejected, and historical invalid overrides remain pending and require review. An unchanged historical entry may be retained while another entry is edited, but it cannot become executable. Retain/delete/not-used keep their existing treatment semantics.

Verification: 92-suite broad regression: 991 tests, 892 passed and 99 conditional/environment skips, zero failures/errors. After the final unchanged-history compatibility adjustment, the six affected/source suites were rerun: 61 tests, 60 passed and one pre-existing capture-dependent skip, zero failures/errors. These runs overlap and must not be added together. The opt-in real-source test uploaded all three actual Word files through the current parser and isolated H2/API methods, read the review projection, and asserted all 28 field candidates (22 SCT fields plus Mr/Ms, and 5 SCC fields), 3 pre-normalized ranges, 14 insertion anchors, the unassociated endpoint seed and complete NTT multi-paragraph evidence. Mock discovery tested transport only; no real model or OCR inference ran. Red reproductions, final reports, source/build hashes and source-only adoption checks are retained under `.local/oer-drafting-audit-20261017/`.

The source-level `scopeComplete`, project applicability and editing permission remain separate. Seven Word markers, fourteen broader conditional links, Bill description cardinality/placement and project values still require source or human decisions. SCC native PDF remains PARTIAL; NTT scan defects and the unexecuted SCT scan remain unchanged. The full 27-Word source-quality census, browser workflow, real-model quality, active services, production DB/index and deployment were not rerun or changed by this consumer repair.


## 2026-10-18 source-range and native-PDF closure

Word source intervals were adopted in I32. I33 adds bound native-PDF source groups and preserves complete group members, relations and unknowns through ordinary and offline canonical material. Actual SCC PDF evidence was recomposed from the complete saved observations and passed `DraftReviewSources.reading`: all six source evidence fields remain exact and `canEdit=false`. Nine explicit targets, shared tails, physical page context and the precise two-line last sentence passed the independent frozen source gate. Three Word template evidence objects remain value-identical.

The final 11-path source delta is adopted without main compilation or changes to active services, production DB or indexes. The 133-suite composite has 1373 passed and 116 conditional skips; the initial environment errors remain in the ledger. SCC p54 geometry, Roofing Installation secondary literal/conditional resolution, project applicability and automatic editing remain open. The complete SCC replay required a bounded 32 GiB offline JVM after preserving an 8 GiB OOM; current service capacity and whole-PDF source quality remain separate gates. Receipts: `.local/oer-word-pdf-closure-20261017/pdf-stage-verification.json` and `pdf-preparation/pdf-post-adoption-independent07.json`; the same HTML ledger continues at I33.


## 2026-10-18 cross-module variable seams

Source revision guards compare the existing original SHA, parsed source hash, algorithm revision, analysis input SHA, evidence generation SHA and evidence status. A changed or invalid OER generation cannot reuse an older reviewed graph even when the original and text are unchanged. Preview display, effective values, actions and target overrides consume one normalized proposal without persisting adoption. Declared fixed/system/derived literals use the same recursive typed schema before confirmation and during evaluation. A business detail named `id` is explicitly visible in the reviewed catalog and stays independent of `_itemId`; legacy hidden metadata remains hidden.

The same OER authority and explicit adoption boundaries remain. Strict published-parent JSON comparison now preserves Boolean/number types at index admission. Unicode whitespace-only quotations and required advisory fields fail their existing presence/location gates. Final scoped regression, exact source adoption, preserved failures and remaining live/source-quality/capacity boundaries are recorded in `.local/oer-module-seams-20261018/verification.json` and the same HTML ledger I34.
