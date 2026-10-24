# English generated-file delivery - 2026-10-24

Status: implementation, selected software checks and authored browser acceptance are complete for the final isolated candidate. Historical failures remain preserved. Publication and exact private-main alignment are recorded in the separate ignored publication receipt. This is not real-project source-quality or production acceptance.

The user selected automatic translation for generated files, independent of the interface language. Translated source quotations must be labelled as translations, rather than presented as literal source evidence. Original source files and saved original-language records remain available unchanged. Chinese content must be translated on the delivery copy; it must not be deleted or reduced through ASCII filtering.

## Implemented delivery boundary

| Surface | Generated delivery | Original/native boundary |
| --- | --- | --- |
| Vetting | English Word and PDF; English JSON presentation with schema version `2.0` | The frozen report, original quotations, human remarks and authority records remain in the system. PDF renders the same derived English DOCX. |
| Drafting | English Word, PDF and Markdown downloads | Saved native DOCX, manual editing state, original downloads and source/native preview continue to use original bytes and saved revision identity. |
| Frontend | Export language is English for every interface language | Vetting runs retain the selected interface language and model. Source download requests do not request translation. |

The shared component is `com.consense.service.export.EnglishFileOutput`, with current cache policy `english-delivery-copy-v5`. It translates derived file copies and validates complete segment coverage, explicit translation decisions, residual non-Latin letters and ordered digit sequences. Unicode punctuation, currency and other symbols are retained. Latin-script foreign text still needs a semantic language decision; a zero non-Latin count alone is not proof of English or a correct translation.

The final tested implementation is preserved under `.local/english-generated-output-20261024/java-final03/ai_consense_service/src/`. The candidate package and its launch identity are recorded in `.local/english-generated-output-20261024/java-final03/package.log` and `.local/english-generated-output-20261024/ui-acceptance/launch-receipt.json`.

### Vetting JSON references and authority

The JSON file is an English reader presentation, not a serialization replacing the original report. It uses `sourceReportSnapshotId`, `sourceRunId` and source hashes to reference retained records; the old top-level `reportSnapshotId` is absent. `job.result`, raw attempts, prompts, provider responses, full canonical proofs and frozen authority objects are retained server-side.

Source wording appears as `displayQuote`, with `literalSourceEvidence=false`, an explicit quotation-presentation explanation, `originalLocated` and `originalQuoteSha256`. Canonical and fixed-baseline references contain only selected identifiers, hashes and positions. The original quotation and its verification are not rewritten. `commentsTables`, `evidenceIndex` and checklist tables contain reader text and locations, rather than raw source-authority JSON.

The v3 JSON repair selects reader-text container slots before translation and merges results into those exact slots. Schema/version/language values, internal IDs, hashes, categorical states and source/binding references are never submitted for translation. Protected reference/model/budget subtrees cannot opt in through a human-looking key such as `title` or `reason`. If a translated human note shares its original text with a status or ID, only the selected human slot changes. Unsupported explicit non-Latin mechanical fields fail before model dispatch, with the field path reported in English.

Project contract numbers and package/section numbers in the derived reader view are display fields, so non-English values may be romanised or translated and labelled while preserving digits. They are separate from protected internal source and binding IDs.

Before returning an export, Vetting re-reads the current report context and compares the snapshot. A concurrent source or human-record change rejects the old delivery copy. Cache hits also pass this final currentness check. Individual translation-step guards check interruption and captured references; a concurrent external edit may therefore be detected after some translation consumption has already occurred.

### DOCX control boundaries and PDF reuse

The v4 DOCX repair joins ordinary text runs for translation context, but flushes text slots before and after tabs, line/page breaks, fields, hyperlinks and object/drawing containers. Nested Word or DrawingML paragraphs are processed separately. Translation no longer moves text across these controls by putting an entire paragraph into its first text node. Relationship entries, structural identifiers and binary entries are not rewritten for language conversion.

PDF generation consumes the derived English DOCX through the configured renderer. It does not independently translate or reconstruct the report. Existing source, saved-revision, renderer and font requirements remain active. A renderer rejection must remain a rejection; it is not converted into an untranslated or fabricated PDF.

### Saved-native source route and preview SHA repair

The actual Drafting browser flow exposed a separate boundary error: native saved-revision reading used delivery/export bytes, whose translation changed the DOCX SHA expected by native bindings and preview. The saved document now has a dedicated original/native source route, `/api/drafting/{projectId}/documents/{fileKey}/source`, bound to the requested saved revision. Explicit Word/PDF exports continue to use English delivery copies. Native bindings, manual editing and source preview use the unmodified saved DOCX.

The failed browser window is preserved as `.local/english-generated-output-20261024/ui-acceptance/drafting-preview-translation-sha-failure.jpg`; this image is context evidence, not a complete error-response receipt. Route behavior is covered by the later independent frontend window and `DraftEnglishFileOutputTest` in `java-final02`. The report does not infer full preview acceptance from those software checks.

### Finite Qwen export action

The `file-export` scope permits at most **24 preflight generations, 24 answer generations and 48 total generations**, under the captured action lifetime and the existing durable reservation ledger. These are ceilings, not observed usage or a promise to make every permitted call. Input admission and completion use the same sealed input, schema and captured model operation. Reserved or dispatch-unknown calls are not refunded or silently replayed.

Raw evidence for these limits is `.local/english-generated-output-20261024/java-final02/ai_consense_service/src/main/java/com/consense/service/llm/QwenChatUsagePreflight.java`; limit meanings and ledger transitions are in the adjacent `QwenGenerationLedger.java`. The relevant software receipt is `.local/english-generated-output-20261024/java-final02/ai_consense_service/target/surefire-reports/TEST-com.consense.service.llm.QwenChatUsagePreflightTest.xml`. These checks do not establish provider-side translation quality.

## Software verification windows

Counts below are execution-window counts. Repeated cases across windows must not be added as unique coverage. Pass means tests minus failures, errors and skips; skipped cases are excluded from pass.

| Window | Actual result | Evidence and interpretation |
| --- | --- | --- |
| `java-final02` | **71 tests: 70 pass, 0 failures, 0 errors, 1 skip; BUILD SUCCESS** | `.local/english-generated-output-20261024/java-final02/maven.log` and its `ai_consense_service/target/surefire-reports/TEST-*.xml`. The skip is `DraftingWorkflowTest.realStandardAndTenderNttRespectInstructionDifference`, because local reference documents are not shipped with the repository. |
| Earlier helper in `java-final02` | **21/21 pass** | `.local/english-generated-output-20261024/java-final02/ai_consense_service/target/surefire-reports/TEST-com.consense.service.export.EnglishFileOutputTest.xml`. Includes JSON mechanical-value isolation and DOCX control-boundary regressions. |
| `java-final01`, retained failed window | **241 tests: 195 pass, 1 failure, 0 errors, 45 skips; BUILD FAILURE** | `.local/english-generated-output-20261024/java-final01/maven.log` and its Surefire XML. The helper test still asserted cache policy v3 after the DOCX repair advanced production policy to v4. The expected-policy assertion was corrected; the original failed run remains preserved. |
| `java-regression01`, earlier broad window | **234 tests: 188 pass, 1 failure, 0 errors, 45 skips** | `.local/english-generated-output-20261024/java-regression01/maven.log`, its Surefire XML, and `.local/english-generated-output-20261024/java-regression-summary.json`. The retained native-flow failure requires an explicit finite renderer window. It was not changed into a pass by the later targeted run. |
| `java-unit03`, earlier selected core window | **49/49 pass** | `.local/english-generated-output-20261024/java-unit03/ai_consense_service/target/surefire-reports/TEST-*.xml` and `.local/english-generated-output-20261024/java-regression-summary.json`. This tested the earlier helper snapshot and is not final-v4 coverage. |
| Earlier final02 candidate package | **BUILD SUCCESS; test phase explicitly skipped during packaging** | `.local/english-generated-output-20261024/java-final02/package.log`. Its independent preceding test window is the `java-final02` row above. Packaging is not a second test pass. |

The earlier `java-unit01`/`java-unit02` errors and fixes remain recorded in `.local/english-generated-output-20261024/java-regression-summary.json` and their original Surefire directories. In particular, a final-class Mockito fixture error is a fixture failure, not a provider or translation-quality result. The summary predates the final targeted window and must not be read as the final source-freeze verdict.

The retained broad native-flow failure is `DraftNativeNormalFlowIntegrationTest.qualifiedStandardPackageThreePreviewsRetainFullNativeGenerationEvidence`: `Explicit finite renderer window required`. Its exact test source and XML are under `.local/english-generated-output-20261024/java-regression01/ai_consense_service/`; original source material, a unique renderer work root and a separate artifact-evidence namespace remain external acceptance requirements.

## Frontend windows

| Window | Actual result | Evidence and scope |
| --- | --- | --- |
| Earlier English/v2 download controls | **77 runner test records pass; 0 failures/skips** | `.local/english-generated-output-20261024/frontend/controls-v2.log`; supporting component and build logs are `vetting-view-v2.log`, `drafting-feedback-head.log` and `build-v2.log` in the same directory. |
| Earlier type checking and production build | **Pass** | `.local/english-generated-output-20261024/frontend/build-v2.log`: `vue-tsc --noEmit` followed by the Vite production build. Source-window identity is in `frontend/source-consistency-v2.json`. |
| Later native-source-route window | **9 runner test records pass; 0 failures/skips** | `.local/english-generated-output-20261024/frontend-native-document-source/controls.log`. Several CJS files report multiple authored assertions within one Node runner record; this is not a count of every assertion or unique business scenario. |
| Later type checking and production build | **Pass** | `.local/english-generated-output-20261024/frontend-native-document-source/build.log`; exact source-window comparison is `source-consistency.json` in that directory. |

The frontend windows are separate. They are not added together or called complete browser acceptance. The later tests distinguish original/native reads from explicit English exports and retain project, saved-revision, DOCX and transport guards.

## Actual browser observations and preserved failures

All business actions in this acceptance stage use visible controls in the in-app browser. The isolated candidate reuses the owned acceptance database and uploaded originals. No direct ConSense function/API call is used to substitute for an upload, human save, generation or download.

The current owned candidate is recorded in `.local/english-generated-output-20261024/ui-acceptance/launch-receipt.json`: backend **18186**, frontend **5187**, JAR SHA256 `dddf84caac925049743877aebc1e631bf03cbfb16b161dbcfadd537c0a163d74`. That receipt records `productServicesRestarted=false` and `ocrEnabled=false`. Candidate operation is not production deployment or OCR/source-quality acceptance.

| Observation | Current status | Preserved evidence and limit |
| --- | --- | --- |
| Real Qwen3.8-27B Vetting response | **FAILED, retained** | The coordinator observed wrapped structured JSON and the run remained failed. `.local/english-generated-output-20261024/ui-acceptance/qwen-review-structured-failure.jpg` preserves the browser context/model selection. The exact saved run/raw-response receipt is linked in the final ledger below; the screenshot alone does not show the complete failure body. |
| v2 JSON download | **REJECTED by the client; no valid downloaded JSON** | `.local/english-generated-output-20261024/ui-acceptance/json-contract-rejected-v2.jpg` preserves browser context. The actual cache below proves the mechanical-language corruption; the rejection is not a successful delivery. |
| Initial authored fixture purpose | **FAILED fixture window, retained** | `.local/english-generated-output-20261024/ui-acceptance/authored-inputs/input-manifest.json` and its authored inputs remain distinct from `authored-template-inputs-v2/`. A later correctly routed fixture does not make the first purpose failure pass. An exact saved rejection-body receipt was not found; this remains an observed fixture failure with an evidence gap. |
| Saved original-language manual content | **Retained in the actual UI observation** | `.local/english-generated-output-20261024/ui-acceptance/original-chinese-notes-retained.jpg`. Original prose is not copied into this English report. This does not establish preservation of every database row. |
| v4 Drafting NTT Word | **Actual UI download obtained; bounded English meaning reviewed** | `.local/english-generated-output-20261024/ui-acceptance/downloads/drafting-NTT-v4.docx`; SHA256 `9978aa067849a61d3f1f4eca27a4a39c5a28abb7ffae416133b7c662c2e3e794`. The extracted paragraph is quoted below. This is the authored fixture, not a real-project contract. |
| Drafting PDF after the Word download | **BLOCKED by missing declared font in the authored template** | The fontless fixture is preserved under `.local/english-generated-output-20261024/ui-acceptance/authored-template-inputs-v2/`. The replacement template declares Arial under `authored-template-inputs-v3-fonts/`, with its own manifest. This historical rejection remains preserved. Final Arial-template PDF downloads succeeded in the final browser window below. |

The preserved v2 JSON cache is `.local/java-baseline-failures-repair-20261024/ui-acceptance/uploads/english-file-output/english-delivery-copy-v2/d1f59b3950c713f2fe1eca8d80bdf0c5085fe98fd51bb2748c79cbd8dba448b5.json`. It contains **501 segments** and a segment for the original string `en`, SHA256 `dbd3a49d0d906b4ed9216b73330d2fb080ef2f758c12f3885068222e5e17151c`, marked `translated=1` with text `English`. Applying that result to every matching JSON string changed `language`/`reportLanguage` into labelled prose. This actual failure caused the selected-slot repair; it is not hidden or accepted through weaker client validation.

The actual downloaded v4 Drafting NTT paragraph reads:

> English translation: Payment shall be made on the 14th day after certification. This paragraph is authored software acceptance material and does not represent adoption in a real project. Manual verification supplement: retain the original record, to be adopted only after QS review.

This observation preserves the day-14 condition, the software-fixture limitation and the pending QS adoption decision. It does not establish general translation accuracy, a day-21 comparison, complete source review, applicability to a real project or PDF layout quality. The authored source meaning and source-text hash are recorded in `.local/english-generated-output-20261024/ui-acceptance/authored-template-inputs-v2/input-manifest.json`.

Earlier `vetting-simplified.docx` and `vetting-traditional.pdf` files under the current `ui-acceptance/downloads/` directory belong to an earlier delivery window. Their existence is not final-v4 Vetting acceptance.

## Final v5 software and browser completion ledger

The final candidate uses backend 18186/PID 41296 and frontend 5187/PID 25200, with the final03 JAR SHA above. Its package is 80,808,488 bytes. The earlier candidate launch receipt, logs and stopped-candidate database copy are preserved under `ui-acceptance/preserved-v4-candidate/`. No formal production service or OCR process was restarted.

### Final software window

`java-final03` is a fresh isolated run of **9 suites, 96 cases = 95 pass + 1 skip, 0 failures/errors**, followed by a successful package. The helper passes **29/29** and the reader-presentation suite **17/17**. The sole skip is `DraftingWorkflowTest#realStandardAndTenderNttRespectInstructionDifference`: local reference originals are not shipped. Evidence is `java-final03/maven.log`, `package.log`, `execution-receipt.json` and its Surefire XML, under the same owned English-output evidence root.

The final latest-observation union is **27 suites, 269 cases = 224 pass + 45 conditional skips**. It combines final01's 173 selected observations (129 pass, 44 skips) with final03's 96; all selected final02 cases are now superseded. This is not one fresh run of 269 tests. Raw observations total 408 = 269 selected identities + 139 replaced observations. The authoritative receipt is `.local/english-generated-output-20261024/final-reconciliation-v5-final.json`, SHA256 `ae63a8ab030bfd9524264ebde3f7d5e1400430e993259b11702bafcc1ceb79d6`. The earlier failed windows, the broader native-flow renderer failure and original reconciliation files are retained.

All **1,050 source-tree files** matched root, final03 snapshot and post-run manifests, including **296 production Java and 498 Java test files**. The latest **213 frontend files** still match its independent tested manifest. Packaging explicitly skipped its test phase and is not counted as a second test pass.

### Additional v5 boundaries

The final helper also covers human-readable tooltip, alias, dropdown/form display labels and the JSON `unknownReason` and filename-bearing `location` fields. Unknown Latin XML text is language-checked without rewriting its carrier; if it needs translation, export fails explicitly. Relationship IDs, formatting and dropdown mechanical values retain their identity. Long legacy Markdown uses existing line boundaries and preserves its exact CR/LF separators; a single indivisible segment over 32,000 characters still fails.

Images, `.vml`/`.svg` and referenced embedded carriers cannot be certified as English by this text workflow. They cause a readable rejection with no generated delivery file. No image OCR, drawing interpretation, vector transformation or embedded-document rewriting is performed. Original files remain available. This replaces the earlier v4 media-preservation limit with an explicit v5 export gate.

The actual English PDF exposed an excessively narrow human-remarks column. The five reader columns and wording remain, with widths changed from 7/11/15/52/15 to **7/10/15/43/25**. Final page 1 keeps both human records together, with `simultaneously` intact and each translation label attached to its text. Legacy audit-table widths are unchanged.

### Final actual downloads

All business actions used visible in-app browser controls at `http://127.0.0.1:5187`. No direct ConSense function/API call, browser fetch or database insertion substituted for upload, generation, human save or download. Local commands prepared authored fixtures, built/started only the owned candidate, and inspected downloaded files.

The Drafting project is `Authored English delivery acceptance`, ID `proj_mvlsrfjnwra4`, with visibly uploaded templates under `ui-acceptance/authored-template-inputs-v3-fonts/`. Its synthetic identity was explicitly confirmed; unresolved variables and source placeholders were not invented. Vetting uses `proj_mvlpprif54u6`, display contract `QA20261024B`, and finding `VT-002`.

All files below are actual browser downloads under `.local/english-generated-output-20261024/ui-acceptance/downloads/`:

| Final action | Actual files | Observed result |
| --- | --- | --- |
| Drafting NTT/SCT/SCC, English UI | `drafting-{NTT,SCT,SCC}-v5-arial.docx` and `.pdf` | Six English delivery files; each PDF has one page. |
| Drafting NTT, Simplified Chinese UI | `drafting-NTT-v5-simplified.docx` | Additional cross-locale control. |
| Drafting SCT, Traditional Chinese UI | `drafting-SCT-v5-traditional.pdf` | Additional cross-locale control. |
| Vetting, Simplified Chinese UI | `vetting-simplified-v5.docx` | English report and translated human records. |
| Vetting, Traditional Chinese UI | `vetting-traditional-v5.pdf` | English, 24 rendered pages. |
| Vetting, English UI | `vetting-english-v5.json` | Strict schema-v2 client validation remained active. |
| Bound original evidence, final candidate | `original-Exact-NTT-v5.docx`, `original-Exact-SCC-v5.docx` | Untranslated original-byte downloads. |

There are **nine primary generated files plus two locale controls**, separate from the two original downloads. Exact file hashes, extraction and JSON checks are in `artifact-checks/v5-browser/final-checks.json`; the 11-generated/2-original manifest is `manifest-11-generated-plus-2-originals.json`, and independent semantic/visual review is `manual-review-final-v5-originals.json` in that directory. The earlier `manual-review.json` selected a metadata table for one inspection; `manual-review-final-v5-originals.json` records the corrected reader-table inspection, without modifying the source artifacts. The checker reads explicit local browser downloads, hashes them before/after and does not rewrite artifacts. Earlier preparation-only receipts checked zero files and are not counted as acceptance. v2/v4 artifacts remain historical.

The final generated text scan found no non-Latin letters. Independent manual review also checked the day-14/day-21 relationship, the incompatibility for the same payment, pending QS coordination, retention of both original wordings, no current contract amendment and no real-project adoption. JSON retains `en` language values and machine references, marks quotation presentation as nonliteral and excludes raw authority/proofs. Word/PDF/JSON snapshot bindings agree. JSON and Word translations may differ in literal wording; meaning is reviewed separately.

The NTT Chinese paragraph and its manually saved Chinese QS-review supplement remain visible after reload and final English Word export. The final Vetting modal retains its original Chinese remarks/action and last-saved timestamp. Context screenshots are `ui-acceptance/drafting-native-chinese-v5.png` and `vetting-chinese-notes-v5.png`. These are observed-record preservation checks, not a denominator for every database row.

Both final original downloads match their source SHA values: NTT `6e767be7a517ff7f499e53ec1aeac2c1675f1e67f42ecbbdba740334f9dcd96d`, SCC `6fa60dd4814a99745ef88cfb66c5c5526ab63dec40e4d72a53fd59c35facb486`.

Visual review covers all three single-page Drafting PDFs at full size, all 24 Vetting pages through contact sheets and Vetting page 1 at full size. No clipping or overlap was observed. A residual pagination issue remains: page 2 repeats an empty NTT header before the SCC summary and contains generous whitespace. No content loss was observed; this is not a claim of flawless pagination.

### Exact retained failure references

- The wrapped Qwen3.8-27B response for run `f2d540f0-af78-4ea7-b2b7-1b2561e0803c` and strict rejection remain in `ui-acceptance/preserved-v2-candidate/backend.stdout.log`, lines 343-364. A later bounded visible UI run using the configured Flash selection succeeded. No output extraction was added.
- The earlier v2 JSON corruption and client rejection remain represented by the original 501-segment cache described above. Final JSON acceptance uses actual v5 downloads, not that cache.
- The fontless-template rejection `FONT_CAPABILITY_UNVERIFIED` is in `ui-acceptance/preserved-v4-candidate/backend.stdout.log`, line 64. Arial replacements produced the actual final PDFs without relaxing the gate.
- The first fixture-purpose rejection has its inputs and observed UI context but no exact saved error-body receipt; that evidence gap remains explicit.
- Earlier download observers timed out while the page still showed work in progress. Subsequent state inspection and explicit visible downloads obtained the validated files. The observation timeouts are neither successful downloads nor established product failures.

### Delivery limits and publication

The legacy Markdown export has software coverage; no visible browser Markdown download was exercised. Authored fixtures establish a bounded translation/export workflow, not general legal translation quality, real 2b/seven-edition SL/source-quality acceptance or production/business acceptance. Unsupported media and oversized indivisible text remain explicit rejections.

Protected listeners remain 18086/PID 47460, H2 18082/PID 32040, frontend 5177/PID 24568, OER 8874/PID 43560 and 8875/PID 44812, relay 8092/PID 20084. OCR 18780/18771/18772 remains stopped. The deferred source-work stash is preserved. No private model pin, original source or adopted authority was changed.

Publication uses committed Git blob bytes, not raw worktree CRLF bytes. `.local/english-generated-output-20261024/publication-v5/` records the actual published commit, raw-object SHA verification, clean worktree and exact equality of HEAD, local main, origin/main and private remote main. Matching trees alone do not satisfy this delivery gate.
