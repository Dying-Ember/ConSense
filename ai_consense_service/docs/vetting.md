# Tender document vetting

Vetting reviews an uploaded tender source set and stores findings with evidence, coverage
and human review status. The web view starts a background task and can reconnect to it.
The report represents the source snapshot used by that task. A completed task or processed
segment does not establish that a document is free of problems.

## Source documents and evidence

- Tender documents, standard references and project evidence have different roles. Keep
  official vetting comments and answer documents out of the source set used for review.
- The upload UI has a purpose selector. A package upload accepts optional
  `sourceRole=tender|standard|project_fact|package_manifest`; omit it for filename/extension
  inference. The selection applies to the entire batch; upload different purposes separately.
  Standards support review, project facts supply context and inventories describe the package.
  These roles must not be treated as ordinary tender wording in contract conflict comparisons.
  `/files` returns a plain `sourceRole` together with the existing localized `role` label.
- PDF evidence uses physical page numbers when available. DOCX evidence uses body/clause
  anchors and explicit `word-footnote/<id>` / `word-endnote/<id>` locations; those anchors
  are not Word page numbers. Reserved separator notes with IDs -1 and 0 are excluded.
- Clause metadata `owner-clause-v2` recognizes a short, explicit file-owner title on
  the first line of a Word table cell. Empty layout cells are allowed. A title with
  another populated applicability cell ends the previous scope but retains unknown
  ownership; it does not establish an unqualified clause. Explicit same-number
  `Cont'd` titles preserve the original heading location, while a new heading or an
  isolated uppercase Appendix/Annex/Schedule label ends the previous scope. Original
  blocks, effective wording, source revisions and UTF-16 Part offsets are retained.
  All chunk IDs change with this metadata version. Rebuild the retrieval corpus and
  its signature before a new run; do not combine v2 chunks with v1 rankings or probes.
- Parsing status, available characters, processed segments and warnings are exposed in
  the source set and task coverage. Missing scans or unavailable volumes remain limits.
- The review source set uses the latest upload for each category and filename in
  `VETTING_PACKAGE` and `VETTING_SUPPLEMENT`. Different filenames with the same file
  key remain separate sources; drafting templates are not presented as generated documents.
  Only nonempty `PARSED` or `PARTIAL` effective text is reviewed. Deleted/struck Word text
  remains parse metadata and is not used as effective source wording.
- A finding can contain several evidence sides. Each side records its file, quote,
  page or anchor, and whether the quote was located. `verified`, `partial` and `unverified`
  distinguish the result of evidence checking from the reviewer's disposition.
- Rules scan all usable tender contract text. Semantic coverage records only successful model
  calls and counts the union of source ranges, so overlapping chunks do not inflate
  reviewed characters. A processed document can still have unreviewed semantic segments.
- Rule findings and model suggestions retain their source labels. Neither label implies
  that human contractual judgement has been completed.
- Vetting requests schema decoding from local Ollama with supplied chunk IDs and a bounded
  output. Other providers retain prompt decoding unless they implement the optional schema
  method. Invalid records, exhausted output and missing exact quotations remain visible
  limitations. `consistent` and `insufficient_context` assessments never become findings;
  only an `issue` assessment that passes evidence checks can become a model suggestion.
- Each attempted topic persists its actual submitted IDs/characters, omitted and partial
  groups, unresolved IDs, assessment counts, accepted/rejected counts, completion status and
  returned text SHA in task coverage. Failed parsing does not erase the response SHA when
  text was returned. The frontend shows this ledger separately from findings. These are
  submission and validation records, not a recall score or proof that the contract was fully vetted.
- Evidence offers the original upload for visual inspection. PDF links use physical pages;
  other formats download the original. Access checks the project, vetting source category,
  configured storage root and parsed evidence revision. `sourceHash` identifies parsed text
  and structure, not the raw file SHA; it does not establish visual deletion-line effectiveness.
- Personnel count differences are coordination risks: matching role names alone do not
  establish identical work scope, deployment periods or incompatible limits. Compatible
  minima remain excluded.
- Inclusive clause ranges are checked against numbered `Not used` declarations owned
  by the source's known clause namespace. Contents styles and another document's bare
  reference do not establish operative clause status; a declaration in the same source
  takes precedence when several owner sources repeat the same clause label. The hint
  asks for reference precision and does not claim that an inactive clause was reinstated.
- The abbreviation rule requires an explicit glossary heading, introduction or table
  header, complete long names and one identified source scope. A dash in an OCR drawing
  title does not establish a definition. Separate clauses or documents may legitimately
  use the same short form differently; a difference without evidence of an affected use
  does not create a finding. Hyphen, punctuation, conjunction and plural variants are
  normalized for comparison, while both evidence quotations remain unchanged. Unknown
  applicability or global use still needs source review; this narrow rule does not infer it.

## API

All paths below are prefixed by `/api/vetting/{projectId}`. JSON API responses use the
existing `ApiResponse` envelope. Report routes return a downloadable file.

| Method and path | Purpose |
| --- | --- |
| `GET /files` | Source files with parsing notes |
| `POST /package/upload` | Multipart upload using repeated `files` fields; optional `sourceRole` query |
| `POST /runs?lang=en` | Start or reconnect to the project's background task |
| `GET /runs/latest` | Latest task, or `null` when no task has been recorded |
| `GET /runs/{id}` | Task status, progress, result and coverage |
| `GET /findings` | Findings; optional `search`, `group`, `scope`, `fileKey`, `page` filters |
| `GET /metrics` | Finding category counts |
| `GET /findings/{code}/evidence` | Evidence sides with individual verification results |
| `GET /sources/{documentId}/original?sourceHash=...` | Matching original upload; PDF inline, other formats downloadable |
| `POST /findings/{code}/status?status=Handled` | Update human review status |
| `PUT /findings/{code}/review` | Replace project-team remarks, action taken and the addendum decision |
| `GET /report.json?lang=en` | Structured report |
| `GET /report.docx?lang=en` | Editable Word report |
| `GET /report.pdf?lang=en` | PDF report |

The earlier synchronous `POST /run` route is retained for compatibility. New UI work
uses `/runs`; it does not hold an HTTP request open while the model processes the package.
Task states are `QUEUED`, `RUNNING`, `COMPLETED` and `FAILED`. The UI polls sequentially,
cleans up timers when a project changes, and treats a connection failure as unavailable
progress rather than declaring that the background task failed.

The task is committed before it is sent to one worker with a queue capacity of eight.
Model/OCR calls do not hold a database transaction. Interrupted tasks are marked failed
when the application becomes ready after a restart. Findings and task completion are
saved atomically in a short transaction.

`Open`, `Handled` and `Assigned` are human review states. When a finding is detected
again with the same identity, its disposition is preserved. Do not equate an evidence
verification result with a handled finding. A new or materially changed issue needs review.
The fingerprint uses the rule identity and sorted filename, source digest, anchor and
whitespace-normalized exact quotes. Findings that do not recur become inactive rather
than being deleted. Legacy findings without a saved evidence snapshot remain unverified;
the evidence endpoint does not attempt fuzzy reconstruction.

## Project-team review records

The current source supports a human response to each finding separately from the
generated comment and its `Open`/`Handled`/`Assigned` status. `FindingVO` and JSON
reports return four nullable fields:

| Field | Meaning |
| --- | --- |
| `reviewRemarks` | Project-team response, retained as entered |
| `actionTaken` | Human description of the action taken, retained as entered |
| `addendumRequired` | `null`: undecided; `true`: required; `false`: not required |
| `reviewUpdatedAt` | Read-only server timestamp of the latest review-record save |

`PUT /findings/{code}/review` accepts a non-null JSON object with the first three
fields and replaces all three together. Omitted or explicit-null fields clear the
corresponding saved value; `{}` clears all three and records a new save timestamp.
The timestamp is not accepted as an editable field. Each text field accepts at most
4000 characters, counted consistently by Java and JavaScript string length (UTF-16
code units). Invalid input is rejected before any field is written. Valid text is
not trimmed, clipped, translated or rewritten: leading/trailing spaces, line breaks
and Unicode content remain in storage and structured exports.

Saving review records checks the project and finding identity under the existing
project lock and transaction. It does not change status, evidence, the machine
comment or recommendation. Conversely, the status endpoint does not change review
records. A later run that detects the same finding identity retains all four human
fields and the status. These fields are excluded from the review corpus and model
prompts. V5 adds nullable columns without replacing legacy data; findings from
before V5 start without a recorded response or addendum decision.

The Vue view provides English, Simplified Chinese and Traditional Chinese review
labels. It retains an unsaved draft when another finding is opened, when status is
changed or when saving fails. Drafts are kept only in the current project's view
session and are cleared when the project changes; they are not server records
until Save succeeds. A late save response from another project is ignored. Export
and a new review are disabled while any finding has unsaved review edits or a save
is pending. Save or discard those edits before exporting or starting another run.
The API exports saved server values; unsaved browser drafts cannot appear in them.

## Word and PDF reports

`VettingReportWriter` builds both formats from one outline, including project and task
metadata, model, document coverage, document/clause groups, comments, recommendations,
review states, project-team responses, action taken, the three-state addendum decision,
review-record save time, all evidence sides, and a final section for missing materials and unreviewed
scope. A completed task may have zero findings and still needs a report describing its limits.
Exports require the latest task to be `COMPLETED` and use findings from that task. The
JSON report includes `schemaVersion`, `language`, `project`, `job` and `findings`.
Word and PDF use translated labels for review records, scope, severity and check
source while preserving human text and source quotations. An empty response/action
is displayed as `Not recorded`; a null addendum decision is displayed as `Undecided`.
Neither `Handled` nor a located quote implicitly sets the addendum decision.

PDF text wraps by font width and continues on additional A4 pages. It embeds a Unicode
TTF when available. Configure a suitable static font on hosts that lack a CJK font:

```powershell
$env:CONSENSE_REPORT_FONT_PATH = 'C:\Windows\Fonts\simhei.ttf'
```

The equivalent Spring property is `consense.report.font-path`. Windows SimHei/SimKai and
common Linux Noto/WenQuanYi TTF paths are checked automatically. Unsupported characters
cause a clear export error instead of replacing source text with question marks. Word files
retain Unicode text and specify Arial and Microsoft YaHei; Word can substitute installed fonts.

Word headings stay with the following paragraph and enable widow/orphan control. Coverage
records and missing-scope notes use compact spacing at the same text size; source quotations
and reviewer comments retain their normal spacing. Both formats use 1.05 line spacing for
compact records, with 0 pt space after compact body paragraphs and 1 pt after compact headings.
Labeled PDF details keep their opening two lines together; long quotations can continue across
pages. PDF headings use black text. Layout changes
must preserve every quotation, anchor, coverage record and warning, including partially parsed
documents and segments that never reached a successful semantic call.

## Local verification

Java 8 source compatibility remains configured in Maven. The workspace validation uses a
temporary JDK 17 and Maven under `C:\Coding\ConSense\tmp\java_tools`; no binary is committed.
The combined `h2,vetting-local` profile uses port 18080 and an independent local data path.
Use the H2 profile for development when MySQL is unavailable. Model, OCR and retrieval
addresses are configured in `src/main/resources/application.yml`; local experiments and
their limitations are documented in [the evaluation tools](../tools/vetting_eval/README.md).

```powershell
mvn test
```

Report tests verify both evidence sides and anchors, review status, coverage warnings,
long evidence over several PDF pages, and a report with no findings. The Windows Chinese
test writes PDF, DOCX and page images to `target/report-qa` for layout inspection.
The suite also covers parsing gaps, rule evidence, semantic quote validation, source
version hashes, chunk overlap, persisted tasks, preserved manual states and H2 migration.
The migration test removes UTF-8 BOM and historical MySQL table options only in its H2
adapter; production migration scripts are unchanged.

DOCX parsing accepts legitimate compressed tender images below POI's default 1% inflate
ratio. The process sets POI's ratio to 0.1% once and checks the archive before loading it:
actual expanded bytes are capped at 128 MiB per entry, 32 MiB per XML/rels entry and
256 MiB in total, with at most 5,000 entries. `consense.document.max-zip-entry-bytes`,
`max-zip-xml-bytes`, `max-zip-total-bytes` and `max-zip-entries` may tighten those limits.
The preflight counts bytes read from the inflater rather than trusting declared ZIP sizes.
Regression cases cover compressed media, unknown entry sizes and every expansion/count limit.

From the web repository:

```powershell
node scripts/test-vetting-view.mjs
pnpm build
```

The view regression harness executes the actual Vue script with controlled API boundaries.
It checks task completion and reconnect, project/evidence request races, review status,
project-team saving, false versus null addendum decisions, draft retention and save
response isolation, report-format selection, export gating, upload purpose and timer cleanup. Production build
also checks TypeScript. Completed zero-finding tasks can export; legacy findings without
a completed task and a failed latest task cannot export a stale report. Uploading new
sources disables export and invalidates an earlier in-flight latest-task response.

The report fixtures are synthetic layout tests. They are not measured detection accuracy
on competition documents. DOCX structural assertions and PDF rendering are separate checks;
a Word layout claim requires rendering the DOCX through a Word-compatible renderer.

The saved real two-finding report from run `1c72f814-0753-4ace-9942-a15028cec6dc` was
replayed for layout QA on 2026-10-02 using its 10-document coverage snapshot. English and
Simplified Chinese Word reports were rendered through the official document renderer and a
workspace-only LibreOffice administrative extraction; native PDF reports were rendered with
Poppler. All four outputs have four pages and all 16 pages were visually inspected. The content
check preserved five evidence quotations, all coverage filenames and 53 warning records, plus
the missing BQ/General Summary, GCC page-2 OCR gap and APL `0/3` semantic coverage notes.
This verifies report layout and snapshot preservation only. It does not score the competition
cases or establish detection recall. The replay uses the later manual disposition snapshot
(`VT-001` handled), while the original downloads and their original review state remain intact.
QA outputs and the standalone replay helper are under the workspace `tmp/vetting_end_to_end`
directory and must not be committed with source code.

The later real six-finding snapshot, run `e36cf12e-9e11-4c52-8092-8a5e532f484d`, was also
checked on 2026-10-02. Actual English/Simplified Chinese JSON, DOCX and PDF API downloads
remain unchanged under `tmp/vetting_end_to_end_coordination/<project>/layout-qa`. Patched
writer outputs are an independent replay of that API JSON in `layout-qa/fixed-pass-2`; they
are not exports from the latest packaged API. Official Word rendering and Poppler produced
5 English Word pages, 6 Chinese Word pages and 5 native PDF pages in each language; all 21
pages were individually inspected. Four-format checks preserve all six findings, fourteen
source-verified quotations, ten coverage documents, 53 stored warning records and ten
additional derived coverage-limit paragraphs. Complete DOCX body text matches the actual
API downloads; complete native PDF text also matches after whitespace/footer normalization.
The English sparse Word tail and native PDF one-line comment split were removed without
changing quote/comment font sizes or spacing. Chinese Word naturally continues its eleven
APL/INDEX limitation paragraphs onto the final page. `content-qa.json` and
`layout-qa-result.json` record the checks and artifact hashes beside the replay.

This remains a ten-source local export/coordination example, not a completed contract review
or an accuracy measurement for all 44 original sources. `VT-001` Handled and `VT-002`
Assigned are functional test states, not professional QS acceptance. The immutable snapshot
still carries an older GCC physical page-2 OCR warning; independent original-page inspection
confirmed that page is blank, so the warning must not be read as missing contract text.

The local model stack is CPU RapidOCR, CUDA FP16 BGE-M3/reranker and Ollama `qwen2.5:3b`.
It is a feasibility baseline for the 8 GB host. The evaluation README provides independent
stages, the four-service startup sequence, immutable jar deployment, offline cache
preparation, original-file integration and H800 migration gates. Its small OCR/retrieval
measurements do not establish full-package contractual accuracy.

The later 44-original-source owner/ledger baseline used immutable JAR SHA256
`235382395d7514819d2069cb0ab2f8e9585b54d723a6bfe9468925f8f6af758e`
(104 tests) and actual run `21ecc408-ceb7-4f53-bec8-b517745bda5d`.
Its three rule findings contain eight independently located Word quotations; the earlier
eight abbreviation/OCR false positives no longer recur. Actual English API exports have
13 rendered Word pages and 12 native PDF pages, all individually inspected, with findings,
quotes, 44-source coverage and warning numbers preserved. Browser checks also observed
successful Word/PDF export requests and a downloaded original Word file whose byte digest
matches the frozen upload. The completed run and original-source access tests are technical
verification, not professional acceptance. All 16 model calls returned valid structured
responses, but their 18 nonempty assessments were `insufficient_context`, two topics
returned empty arrays and no model issue was accepted. Successful submissions covered
142 of 13,270 chunks, with no project-fact or package-manifest submission. This limits
semantic conclusions even though all 44 source documents parsed successfully. Actual
reports, full-page QA, raw model/ledger reconciliation and resource observations are under
`tmp/vetting_full_case_revision_20261001T233330-f43358e7/`; source-linked comparison-pack
experiments must remain distinguishable from that immutable production baseline.

Structured Ollama calls now support the optional setting
`consense.llm.structured-thinking`. An unset value omits the native `think` field;
an explicit boolean is sent at the request root for structured calls. Ordinary chat
requests keep their existing payload. This uses the native
[Ollama chat control](https://docs.ollama.com/api/chat), rather than a model-name
branch or a prompt suffix. Build this source revision before using the setting;
the immutable 104-test baseline JAR predates it. Model-specific supported values
and defaults are available from `/api/show`. The cached Qwen3.5-4B candidate reports
support for false/true and a default of true. Its independent comparison requests
use `think:false`, context 8192, temperature 0.2 and output limit 2048; the existing
local application profile continues to identify the 3B feasibility baseline.

Evidence validation has separate levels. Native JSON conformity checks the response
shape. Exact source copying checks the quoted text. Binding fields to one evidence
span checks a necessary source relationship, but cannot prove classification,
applicability or completeness. In the five single-chunk 3B diagnostics, 20 records
passed same-chunk literal copying; eight paired personnel names with qualifications
from a later, different clause branch. A separate evidence-span binding check rejects
those eight, while the other twelve still require semantic review. These experiments
are evaluation artifacts under `tmp/`, with frozen inputs and raw responses; they do
not replace source-grounded findings or establish whole-contract acceptance.

Model-generated `issue/reference` records now require at least two distinct located
`documentId|anchor` provisions, and none of their quoted chunks may have a known
unresolved comparison context. Deterministic rules keep their existing behavior.
The independent offline replay of the five actual 4B requests rejected all four
returned issue records under this source policy, including two that passed the old
gate. The original model requests, responses and two old-gate candidates remain
unchanged. This is a conservative evidence requirement: it may omit reference
formatting defects in one passage, and it does not establish semantic correctness
or improved recall. The source-policy replay and its exact source/response hashes
are documented in `tools/vetting_eval/README.md`.

The preserved 126-test package completed one actual 44-source application
run, `a2ebfeb7-a4fd-45fe-8d7e-390e27882808`, using immutable JAR SHA256
`bf3def545e435d41250e4a7f2300be2096155d2ed195f3d88f69f5785e272ed1`.
This run uses a copied evaluation database, unchanged original upload bytes and a new
`owner-clause-v2` index. The 44 documents contain 13,293 chunks; independent interval
unions cover all 13,266,431 effective original UTF-16 characters with no omitted
ranges or changed Part text. This establishes preservation of the persisted parse,
not OCR accuracy, correct legal ownership, or full semantic review.

The fresh index reports `cached:false` and 225.753 server seconds. The complete
index-and-review job takes 428.230 seconds, excluding the earlier upload, parse and
OCR stages. Native capture verifies the sole index entity and all 64 role-specific
retrieval responses without rewriting the request or response entity bytes. Sixteen
actual structured model responses match the persisted call ledger. The 1-second
resource sampler observes a 6,135 MiB whole-machine GPU peak on the 8,188 MiB local
GPU; this is a sampled peak, not an absolute peak or an isolated model allocation.

The actual model receives 144 unique chunks from 23 of 44 documents: 138 tender,
five standard, one project fact and no manifest chunks. All three project facts and
the manifest are returned by retrieval for each of the 16 topics, but the context
selector omits facts 102/103 entirely, submits fact 104 only to topic 9 (whose response
is empty), and submits no manifest. Tender SCT7 enters topic 4 without project facts;
SCT6 and SCT8 owned chunks enter no actual prompt. The earlier fixed SCT6/7/8
diagnostic is therefore not evidence that this application run compares those
clauses with all three project messages. The fact/manifest exclusion occurs in
context packing after retrieval. Independently recorded final retrieval hits also
contain no owned SCT6/SCT8 chunk, while SCT7 is returned only in topic 4. Clause
nomination must therefore also follow original project-fact/source references;
packing alone cannot complete the missing clause side. The native endpoint exposes
final ranked hits, not pre-rerank candidate lists.

The raw responses contain two `issue` and seventeen `insufficient_context` records;
both issues fail evidence validation and zero LLM findings are accepted. Independent
source review of all nineteen records classifies eleven as absence claims directly
contradicted by supplied text, one as an unsupported repeated-definition/scope
requirement, two as unsupported issues, and five as bounded observations requiring
the recorded quotation and scope qualifications. These are manual classifications
of this frozen sample, not precision, recall, or a professional acceptance score.
They demonstrate that evidence rejection and a valid JSON response do not establish
successful model reading or adequate review coverage.

The three resulting deterministic findings retain the previous limited reference,
personnel-coordination and clause-range observations and eight independently located
Word quotations. All remain Open. English API exports render to thirteen Word pages
and twelve native PDF pages. All twenty-five page images were individually inspected;
601 field/paragraph preservation checks pass. Actual frontend Word/PDF export clicks
return native HTTP 200 response bodies with matching document contents. Byte differences
are confined to DOCX creation/ZIP timestamps and the PDF trailer ID. The browser's
managed report-download event was not captured, so an OS-saved report download is not
claimed. The displayed SCC original link does download a Word file with exactly the
frozen upload SHA256. Source-set UI counts are 35 tender, five standard, three project
facts and one manifest, all parsed; these do not imply all forty-four were semantically
reviewed. Actual reports, full-page QA, UI/network evidence and source-semantic audits
are preserved under `tmp/vetting_full_case_v2_20261002T020023/`.

## Project-reference comparisons in the current source revision

`VettingSemanticReview` now calls `VettingReviewPackBuilder.buildFactComparisons`
when planning the configured ordinary-topic review. This source integration addresses
the observed SCT6/7/8 nomination gap: explicit references in original
`project_fact` material nominate owned tender clauses independently of retrieval
rank. The older ranked `build(...)` comparison-pack path remains an unused
experiment. Neither that experiment nor the new source integration changes the
preserved 126-test application run described above. The integrated revision has
not yet completed a deployed full44 application run.

A project-reference comparison retains the original chunks of the corresponding
owned tender scope and the referring project messages. If a single corresponding
standard scope is found and fits the budget, its original chunks are added as
supporting context. Source ambiguity, an unlocated requested subparagraph and
omitted standard support are recorded. If the complete tender scope plus project
context exceeds the budget, or the comparison cap is reached, the comparison is
omitted with a warning. Project messages identify facts and requested comparisons;
they do not become competing contractual obligations. Existing quotation,
tender-evidence and applicability gates still apply to model candidates.

The current source also adds a generic system instruction only for
project-reference comparisons. It distinguishes request cells from populated
replies, treats blank replies as unknown, separates standard-template placeholders
from adopted tender values, and requires absence claims to check all supplied
continuations. It contains no nominated SCT numbers or evaluation answers. The
ordinary sixteen topic prompts remain unchanged. A new comparison-basis guard
uses the actual quoted source roles: two distinct tender provisions can support
a comparison, a project fact can support a reference/value comparison, or a
quoted tender passage must explicitly identify the differently owned standard
target. A matching template alone does not establish adoption. Passing this
guard still does not prove field correctness, applicability or contractual effect.

The current defaults in `ConsenseProperties` and `application-vetting-local.yml`
are:

| Setting | Default | Meaning |
| --- | ---: | --- |
| `consense.vetting.semantic-topics` | 16 | Maximum ordinary retrieval topics |
| `consense.vetting.semantic-context-chars` | 10000 | Ordinary-topic source-content budget |
| `consense.vetting.project-reference-comparisons` | 16 | Maximum additional project-reference comparisons |
| `consense.vetting.project-reference-context-chars` | 12000 | Source-content budget for each additional comparison |

These budgets count Java string characters, not tokens or the complete prompt.
Additional comparisons run only when semantic review is enabled, the model is
available and the configured ordinary-topic count is greater than zero. The
planned model-call count is the configured ordinary-topic count plus the actual
number of admitted comparison packs. Once that plan is available, the job total is
updated to planned calls plus the rules and saving stages; the initial queued
estimate is not the final total. The frontend uses the server's `totalUnits` and
shows `reviewKind: project_reference` entries with a translated project-reference
badge and their `referenceIds` in the call ledger. This badge describes the call
kind, not acceptance of a finding or completion of its legal scope.

The independent offline replay under
`tmp/review_pack_replays/integrated-fact-candidate-20261002T024647/` checks the
integrated selection and production prompt construction. Its three additional
comparisons contain:

| Owned tender clause | Source-content characters | Tender chunks | Project-fact chunks | Standard chunks |
| --- | ---: | ---: | ---: | ---: |
| SCT6 | 10784 | 3 | 3 | 4 |
| SCT7, requested subparagraphs (1)/(2)/(3) | 6021 | 2 | 3 | 3 |
| SCT8 | 2703 | 1 | 3 | 1 |

All three retain the three original project-message sources. Selected material has
no omitted support IDs or known unresolved comparison IDs in this replay, but
`qualifiersComplete` remains `unknown`. GCT, SCT1, CON8 and APE2 cross-references
have not been closed by this selection. A whole selected clause is therefore not
proof that every parent exception, referenced target or operative qualification
is present. The source/offset/accounting audit passes for 2,371 Part occurrences
and 19 constructed production prompts: sixteen ordinary-topic prompts and three
project-reference prompts. The ordinary-topic submissions and prompts match the
preserved 126-test baseline. This replay makes zero model, HTTP, database, index,
OCR or application-review calls and establishes no semantic-quality acceptance.

Earlier integration evidence comprised the full133 pass, the affected 25-test
warning rerun and the targeted nine-test workflow pass, giving a then-current
134-test inventory rather than a full134 rerun. The workflow regression
uses the service's role-aware upload and asynchronous worker with controlled
empty model responses, pauses the extra comparison call and checks persisted
progress: 19 total units, 17 completed while paused, and 19 completed at finish.
It restores and exports the `project_reference` ledger entry and `referenceIds`,
checks tender/fact coverage, and confirms an empty comparison creates no finding.

The earlier source revision, including the generic project-only prompt and comparison-basis
guard, passed one complete Maven run at 11:13:21–11:13:29 local time on
2026-10-02: 15 suites, 138 tests, zero failures, errors or skips, exit 0. This
is a single complete run, not a sum of prior partial runs. The inventory is the
previous 133 tests plus one workflow and four semantic regressions. Its XML and
source/class identities are frozen in
`tmp/test_snapshots/source-fact-basis-138-20261002T1113/test_manifest.json`.
The frontend build and `node scripts/test-vetting-view.mjs` also passed earlier;
no new deployed UI verification is inferred from the Java run. The offline
selection replay above remains bound to its own earlier source/class hashes,
before the warning and project-only prompt changes.

## Actual native project-reference diagnostics

Three rounds each make three standalone native calls on the SCT6/7/8 comparisons,
for nine calls in total. Their raw responses, native stop reasons and schema checks
are recorded separately from application runs:

| Round | Raw assessments | Located / total quote sides | Historical evidence-gate issue candidates | Independent source judgment |
| --- | --- | ---: | ---: | --- |
| Original system, 3B (`025513`) | 6 insufficient-context records | 7 / 11 | 0 | Five false absence claims and one unsupported template-actor substitution |
| Generic project-only system, 3B (`030129`) | 1 issue, 3 insufficient-context records | 7 / 10 | 0 | Unsupported table issue and three false absence claims |
| Same generic system, 4B (`030454`) | 1 issue, 1 insufficient-context, 1 consistent record | 6 / 7 | 1 | Unsupported blank-reply/subparagraph inference, false template-edition issue and one bounded officer-context observation |

All nine responses end with native `stop` and pass native schema checks. None of
the rounds passes semantic-quality acceptance. The 4B historical candidate treats
the old standard-template edition as the adopted tender requirement; it must not
be accepted as a supported issue. A separate CPU replay of all thirteen frozen
records with the current comparison-basis guard reduces candidate issues from
one to zero, without changing the historical responses or gate results. That
rejection is a necessary source-basis check, not evidence that model reading is
now correct. The 4B officer observation does not establish the full clause's dates
or applicability.

The technical manifests are in each round's `native_execution/` directory under
`tmp/review_pack_probes/production-facts-latest-3b-20261002T025513/`,
`tmp/review_pack_probes/production-facts-latest-3b-20261002T030129/` and
`tmp/review_pack_model_comparisons/project-facts-generic-4b-20261002T030454/`.
Separate source-semantic audits of all 6/4/3 records are under
`tmp/independent_fact_response_audits/`; exact filenames and gate-replay provenance
are listed in the evaluation README. These are direct fixed-prompt diagnostics
with warm resident parsing/retrieval models, not full application or cold-index
runs. No new application deployment, full44 run, report or finding-status change
results from them. The deployed application remains immutable126 and the default
local model remains 3B. Professional acceptance remains pending.

## Latest source verification and cell diagnostics

The subsequent human-review source revision passed a complete Maven run on
2026-10-02: 17 XML suites, 155 tests, zero failures, errors or skips, exit 0.
The XML modification window is 11:45:25–11:45:33 Asia/Shanghai; the execution log
is `tmp/human-review-full-tests-20261002.log` at the workspace root. This is a new
full run, separate from the preserved full138 proof and earlier targeted counts.
The independent XML/source/class snapshot is
`tmp/test_snapshots/human-review-155-20261002T034717590368Z/test_summary.json`,
SHA256 `4b5f07efc8fb6b4e10ce5dd97643a52a57f5c39b136aa24e5d770a11c5dc6502`.
It binds 413 files, including V5/configuration, original execution log and frontend
source/build artifacts, with 184 production and 17 test classes. Frontend command
results record the root agent's observed exit codes; the freeze script does not
rerun them.
It covers H2 persistence and V5 legacy compatibility, atomic validation and project
scoping, API raw-text/nullable-decision round trips, status independence, stable
identity across asynchronous reruns, exclusion of review text from actual assembled
prompts, and Word/PDF presentation of all three addendum decisions.

The frontend `vue-tsc`/Vite build and `node scripts/test-vetting-view.mjs` also
completed with exit 0. Four additional controlled UI regressions cover saving raw
text and false/null decisions, drafts across findings and status updates, a stale
save response after a project change, and draft retention after a save failure.
These tests do not constitute a new deployed UI review or all-page rendering of a
new report. The deployed application and preserved full44 exports remain immutable126;
the current source has not completed a new deployed full44 application run.

Separately, source-grounded table-cell diagnostics use frozen classes from an
earlier 55-test focused pass, before the human-review edits. The actual fixed 3B
round (`033230`) makes three calls and returns five insufficient-context records:
9 of 14 quoted sides are located, two records pass the evidence gate, and zero
issue candidates result. Independent source review rejects all five records;
cell/continuation misbinding and denial of supplied values remain semantic failures.
The same-cell 7B round (`033850`) makes three calls and returns three issue records,
with SCT6 returning `[]`: 6 of 8 quoted sides are located, one record passes the
current gate and two are rejected. The gate-passing SCT8 record incorrectly treats
the same supplied date as inconsistent. It cannot be adopted as a correct finding;
the completed independent source audit supports none of the three issue records.
Its 34 item-level checks record three source-contradicted claims, three unsupported
object associations and 28 unassessed items, including all twelve SCT6 items after
the empty response. These are audit classifications, not an accuracy/recall score
or evidence of comprehensive quality. The audit is
`tmp/independent_fact_response_audits/native-cell7b-20261002T034705308429Z/independent_native_cell7b_three_issue_source_semantic_audit.json`.
These direct diagnostic calls change neither the default 3B model nor the
application database, index, report or review status. Evidence-gate success and
additional cells do not establish correct field binding or contractual judgment.
