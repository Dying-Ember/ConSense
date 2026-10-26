# Reading-first OER delivery plan for Vetting and Advisory

Date: 2026-10-24. Status: APPROVED WITH REVISED ORDER. The current execution authority is [the active 08:00 goal](oer-sl-goal-20261025-0800.md): OER reduction, six official full-source SL inputs, built-in version-scoped frontend verification in both modules, then project-contract and SL quality improvements. The competition/2b SL is excluded from new inputs. Detailed seven-source and 2b proposals below are retained as historical investigation; they do not override this revised order.

This plan supersedes the earlier SL-only implementation direction. It is a proposal for the common Vetting and Advisory ingestion, indexing and retrieval chain. No new bulk parsing, embedding, index publication or production deployment is authorized by this document alone. Earlier unfinished source changes and failed evidence remain preserved.

## 1. Outcome and priority

Deliver one usable frontend workflow across the entire agreed input set, at an acceptable measured speed. First establish available text, reading order, observed chapter/clause structure, persisted vectors and retrievable source evidence. Improve complex layout, formulas, merged tables and cross-page table interpretation against that usable baseline afterwards.

A table or OCR quality issue must not prevent already available native paragraphs from being saved, indexed and retrieved. Unreadable pages and uncertain associations stay visible. Available content is not certified legal meaning or complete source quality.

Vetting and Advisory consume the same saved OER observations and source revisions. They must not each parse, recognize or rebuild the same unchanged material independently. Existing BGE-M3, tokenizer windows, Qdrant persistence and retrieval components remain the implementation basis.

## 2. Exact real-source scope

### SL references

Use every downloaded official version from the user-provided Housing Authority website, plus the distinct competition-provided Batch 2 original. The saved website catalog dated 2026-10-19 lists six official downloads. Seven distinct byte sources are held locally when the competition original is included. Refresh the website inventory once at the start of the approved run; add a newly listed source explicitly rather than silently replacing an existing version.

| Exact source | Technical physical pages | Technical-page count |
| --- | --- | ---: |
| SL2022 original | 1042–4093 | 3,052 |
| SL2022 Batch 1 | 1102–4299 | 3,198 |
| Competition SL2022 Batch 2 | 1127–4300 | 3,174 |
| Official SL2022 Batch 2 revision 1 | 1127–4300 | 3,174 |
| SL2022 Batch 3 | 1162–4368 | 3,207 |
| SL2022 Batch 4 | 1181–4389 | 3,209 |
| SL2026 original | 1186–4385 | 3,200 |
| Total | Seven distinct byte sources | 22,214 |

All seven original files were rehashed during this investigation. Saved PDFium page records contain nonempty native text for all 22,214 technical pages. This is reusable saved-text availability, not an OER quality or index acceptance result. Validate the source/page bindings, import that text as explicitly provisional observations, and avoid another full parse solely to recreate already available text. Re-read only missing or unusable observations.

Exclude SL PRE. Keep project PRE from the 2b tender package in scope. Never substitute the official Batch 2 revision for the different competition original.

Evidence: `.local/oer-sl-version-research-20261019/catalog-live.json`, `cross-version/receipt.json`, per-edition `*-pages.jsonl` and `*-inventory.json`; per-source technical partitions under `.local/sl-real-seven-source-units-candidate02/`.

### Project tender/contract documents

Use the actual `Ref/2026-09-04_Documents to Shortlisted Candidates (Contract Advisory)/2b_Tender documents` directory, not simulated correspondence or authored payment fixtures. The current directory contains 35 files: 23 DOCX and 12 PDF. Give each input an explicit result or exclusion reason. Existing drawing/pricing/works-PSS exclusions remain; text lists that refer to drawings remain eligible. Do not predetermine the final eligible-file count before routing.

Representative controls include NTT, SCT, SCC and project PRE; born-digital GCT/APL/APZ PDFs; and scanned FoT/AoA. APL checks remain subcontractor names, project name and contract number; AoA checks remain project name and contract number. These samples support the common workflow and do not expand document-purpose rules.

## 3. Current evidence and code findings

### Real-source observations

- Existing isolated browser run, APH 85-page reference: saved native text remains readable, including the checked reinforcement tolerances. The current status is PARTIAL with 35 parsed pages, 50 OCR-gap pages and zero OCR pages. Twenty-two CPU native-table requests returned successfully. This does not establish whole-source quality.
- Existing isolated browser run, two-page COM7 gateset reference: both pages have saved native text, but the status counts 0/2 parsed because OCR is unavailable. Flat text interleaves the sliding/swing acceptance columns. Retained pdfplumber observations preserve their separate values and merged cells; a later software repair has passed focused tests but is not a new production acceptance result.
- New proposal probe through visible in-app browser controls: real 2b `03 - NTT.docx` returns PARSED with 110 recorded paragraph/table/header/footer/note positions. Saved text is readable; Word physical pages are not invented. The result was observed within 28.645 seconds of selection, including orchestration delay. That is an observation upper bound, not parser latency or a percentile.
- Real 2b `APL_List of BS Subcon.pdf` returns its initial result in 298 milliseconds but stays IDENTITY_PENDING, with three original pages. Detailed parsing was not started. Fast acknowledgment must not be reported as usable content.

The new 2b probe used candidate Java `java-sl-compact15` on owned port 18186 and frontend 5187, project `proj_mvmh3kijotsc`. It made zero OCR, embedding or business-review model calls. Formal services were not restarted. The complete 2b review/Advisory chain and OCR throughput were not exercised.

Evidence: `.local/sl-text-clause-quality-20261024/reading-first-proposal-probe/receipt.json`, `input-inventory.json`, saved NTT DOM text and source/reader screenshots; `compact-ui03/` logs, screenshots and original source descriptors.

### Code-supported bottlenecks to measure

| Current seam | Evidence in current code | Proposed change |
| --- | --- | --- |
| Upload waits for parsing | `upload-queue.ts` serially awaits upload and source refresh; `VettingService.uploadPackage` performs observation, detailed parsing and persistence inside the upload request. The frontend permits two hours per file. | Return a durable task ID after storage/routing; run parsing as a visible server-owned task. A refresh observes or resumes the same task instead of replaying an upload. |
| Native text waits for optional visual work | `DocumentParser.parsePdf` collects page evidence, extracts text, then runs page OCR when form/layout or visual-gap heuristics request it; native tables are processed after the page loop. | Save and expose native text/structure first. Run OCR for missing/unusable text as required for availability; move optional layout/table refinement to a separate stage. |
| Rich observations accumulate | The PDF parser builds the full native page-evidence list before completing ordinary reading. Compact ordinary persistence removes much of the raw duplication after derivation. | Process bounded page batches and retain the minimum reading carrier. Do not keep every glyph/operator archive in the ordinary live working set. |
| One bad preparation can stop later sources | Advice source preparation loops over sources and exits on an exception. Vetting prepares sources before returning its queued run. | Record per-source outcomes, keep usable sources, continue independent inputs, return a task handle before long preparation. |
| First question can include indexing | Advice opens current source bindings and calls `ensureIndex` before retrieval. | Expose index preparation/progress; distinguish cold model/index preparation from warm retrieval and answer generation. Reuse unchanged persisted vectors. |
| Optional table transport can reject a whole original | The native-table route caps complete PDFs at 64 MiB; the exact Batch 4 original is 79,864,310 bytes. | Keep that optional failure off the native-paragraph path. Use existing source-bound technical units/batches for later table refinement. Do not raise all limits indiscriminately. |
| UI work can repeat | Findings filters call a reload that also reads files and metrics. Existing directory reads already use `Promise.all`. | Narrow unchanged reads if timing shows a material cost; avoid blanket parallelism or more polling. |
| Version selection is hard to read | An existing global fixed-reference selection component already persists explicit editions and source identities, but is collapsed and exposes hashes. | Improve the existing entry with readable version labels, direct actions and clear selected scope. Keep hashes in optional technical details. |

These paths are code findings. Only the explicitly timed probe results above are measurements; they do not establish which stage dominates full-package latency.

## 4. Minimum saved reading model

Retain one effective body/table carrier, with the original file as recovery source:

1. Exact document/version identity, source role, original page or native Word anchor.
2. Ordered paragraphs/lines and their literal reader text; original text only where decoding or revision distinctions require it.
3. Observed chapter, clause identifier, parent path, start/end membership and any unresolved boundary.
4. For tables, original cell text once, row/column origin, row/column span and source-region/page link. Preserve observed header/note/continuation relations when supported; leave missing relations unknown.
5. Page/unit availability, missing-text/decoding/layout flags and the parser/recipe identity needed to reproduce or selectively refresh it.

Remove duplicate full-text/native-block copies, ordinary per-character glyph arrays, raw graphics/operator inventories and redundant expanded archives from the ordinary persisted representation. Keep private historical diagnostics and source originals intact. Do not introduce a new metadata catalog just to describe every possible parser detail.

## 5. Availability and quality policy

### Hard boundaries retained

- Exact source bytes/version, current revision and module/project scope.
- Drawing and confirmed excluded-material routing; SL PRE exclusion.
- Readable, bounded input and valid persisted vectors. Never put NaN vectors or content from a different version into the index.
- Unambiguous association of a returned hit with its source text and locator.

### Quality flags that do not block independent text

PARTIAL status, optional OCR failure where native text exists, uncertain formulas, complex table layout, absent printed pagination, ambiguous chapter/clause membership and unconfirmed cross-page table association must not reject the whole document or package.

An uncertain clause remains searchable under its confirmed document/chapter and a provisional anchor. Do not invent a clause number. Blank or unreadable text cannot be presented as parsed content; it receives an explicit gap and later OCR work. A retrieval or review result must disclose omitted scope without claiming the failed units were reviewed.

The code already allows PARSED/PARTIAL documents with text in `ProjectDocumentScope.parseUsable`, and saved-normal preparation does not require source-quality acceptance. Repair the blocking caller/stage coupling rather than removing all validation.

## 6. Proposed implementation sequence after approval

### Batch A — Establish the common usable path

- Separate transport/routing acknowledgment, native reading, missing-text OCR, optional layout/table refinement, indexing and review into visible stages of the existing task flow.
- Preserve source-native DOCX paragraph/outline/numbering/story order. Read PDF paragraphs/lines in source order and retain source page boundaries.
- Persist native reading results progressively, with per-source/per-page outcomes and safe resume. A failed unit does not restart successful independent units.
- Both Vetting and Advisory use this same saved revision. Avoid another source-identity questionnaire when the selected source and purpose are already confirmed.
- Keep model/GPU dispatch within the existing bounded coordinator. Native extraction can use a small CPU queue; do not run unbounded OCR/model workers or duplicate model loads.

### Batch B — Complete all-source chapter/clause indexing

- Import the seven saved technical text inventories with their exact original/page bindings. Report all 22,214 technical pages, with quality flags rather than demanding full quality acceptance first.
- Build chapter/clause parent units using observed literal numbering and native headings. Where observed continuity supports it, retain a continuing paragraph within the same source/clause. Otherwise preserve separate page fragments and a provisional boundary. Stop at a new source or version.
- Split oversized clauses into the existing tokenizer windows with parent links. Reuse BGE-M3 and persisted Qdrant collections; retrieval returns the matching clause context and original locator.
- Preserve edition filters before ranking. Support explicit comparison across selected versions without silently mixing their contract meaning.
- Index 2b saved observations through the same normal OER path, refreshing only changed or newly available units. Retain an earlier usable index while a replacement is built, then switch the active revision after successful publication.

### Batch C — Frontend whole-flow acceptance and speed

- Complete the actual 2b upload/read/prepare/index/Vetting/Advisory/citation/refresh/download workflow through in-app browser interaction only. Use the project-contract basis for 2b Advice.
- Exercise every SL source in version-filtered clause and semantic searches, not only an arbitrary local sample.
- Show document/version names, current stage, progress, usable text/index counts, missing units and direct source actions. Technical hashes and long warnings must not dominate ordinary reading.
- Preserve successful source results when another file fails. Provide an explicit retry/resume action for the affected unit, without automatically replaying expensive requests after a disconnect.

### Batch D — Quality improvements after the usable baseline

Optimize complex reading order, repeated headers/footers, equations, merged tables, row ownership and cross-page table headers against the published baseline. Do not reparse every unchanged file for one localized defect. Keep existing successful text and retrieval available while refinements run.

## 7. First-delivery checks and speed targets

The following are proposed targets for approval, not measured achievements or full-source quality gates. Confirm the actual hardware/model profile and record cold versus warm results before setting a final whole-corpus ETA.

| User-visible operation | Initial target |
| --- | --- |
| Task acknowledgment after bytes are accepted | At most 2 seconds; progress visible without a long silent request |
| Small native DOCX first usable text | At most 10 seconds on the fixed representative inputs |
| Native PDF of at most 100 pages, first usable batch | At most 30 seconds; no wait for all optional table/layout work |
| Existing saved SL technical-text import and chapter/clause preparation | At most 10 minutes for the seven-source inventory; embedding measured separately |
| Warm top-10 retrieval | P95 at most 5 seconds; record reranking separately, initially targeting at most 10 seconds including reranking |
| Unchanged-scope Advice question | No full reparse or full re-embedding; separate source/index, retrieval, first token and final response timing |
| Failed independent unit | One recorded failure with an explicit continuation/retry path; no package-wide rollback or automatic full rerun |

For embedding throughput, measure the first fixed 2,000 windows on the existing model/hardware, publish the remaining-window count and ETA, and keep reporting progress. Do not invent an unmeasured finish time for the entire library. Cache/reuse only verified unchanged source/model/recipe identities; a file-size/mtime-only shortcut is insufficient for mutable originals.

First-delivery denominators:

- All downloaded official sources plus the distinct competition source are listed, with technical page counts, available-text counts, clause-parent counts and indexed-window counts.
- Every one of the current 35 2b inputs has a visible terminal or active task state, usable content or an explicit exclusion/gap. No single bad input silently drops later files.
- Use a fixed 40-query first-pass retrieval set: 21 SL clause-ID queries, seven SL technical-semantic queries and 12 real 2b retrieval/citation scenarios. Target at least 20/21 clause-ID queries in top 3 and at least 16/19 remaining scenarios in top 10; report every miss. These are first-release sample targets, not corpus accuracy percentages.
- Zero wrong-version/source citations in that set. Missing content stays missing; do not change the denominator to hide it.
- One complete real 2b Vetting run and one Advisory interaction produce reviewable results with readable partial coverage. Refresh preserves task/result state. Outputs remain English; non-English source quotations are translated and labelled, while original downloads remain unchanged.

## 8. Bound work and avoid local repair loops

Keep one fixed real-input suite and an explicit package-level progress ledger. Make only the changes required for the common path and the measured dominant waits. Investigate each isolated nonblocking defect for at most two focused cycles in this delivery, record the result and continue independent work. Source/version corruption or loss of all readable content is a release blocker; a complex-table quality issue is a recorded follow-up when other usable text is intact.

Re-use current parser, task, normal OER, embedding and retrieval modules. A separate SL parsing engine, vector framework or standalone UI is not a prerequisite. Improve the existing source/version controls before adding another surface.

## 9. Approval scope and current stop state

Recommended approval: Batches A–C as the first availability/speed delivery, with Batch D deferred until those acceptance results are visible. Include an isolated representative OCR test on FoT/AoA with the current OER engine, under the existing coordinator, before the approved bulk missing-text stage. No blanket OCR service restart or unbounded bulk replay is implied.

At the user's plan-first instruction, new SL library implementation was stopped. Root's newly authored `SlLibraryController.java` and `SlLibraryControllerTest.java` are unfinished, untracked WIP: they were not compiled, deployed or invoked and are not an approved architecture. No library UI/server, all-source build, embedding or index job was started. Earlier compact/text/table fixes and test windows remain separate historical work; they are not proof that the proposed all-source delivery is complete.

This proposal and its bounded real-source investigation are the current deliverable. Implementation and source publication of the broader change await the user's approval.
