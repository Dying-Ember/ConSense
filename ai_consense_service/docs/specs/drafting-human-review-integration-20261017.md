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
