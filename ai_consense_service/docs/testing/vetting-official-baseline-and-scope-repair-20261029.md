# Vetting official comparison and mixed-scope instruction repair

Recorded on 29 October 2026, Asia/Shanghai. The substantive review goal remains active.

## Comparison baseline

The local comparison now covers every one of the official report's 88 numbered positions and all 69 checklist rows. Each row maps the official requirement or sample to the actual source and existing delivered result, with bounded independent judgments and explicit unknowns. The 69 checklist rows comprise 67 main requirements and two supplementary pricing examples; 55 in-scope or mixed rows have original-source judgments, and 14 pricing rows/examples have scope decisions.

The sample has 26 source-supported review positions. The existing report fully covers one bounded precision point, partially covers two and has no equivalent finding for 23. These are review-position counts, including clarity and editorial matters, not distinct legal-defect counts or an accuracy percentage. Four other incomplete checklist assessments are not counted as partial finding detections. Conditional, blank, questionable, excluded and unverifiable sample positions are classified separately.

The four existing findings were independently checked for source ownership, exact clause identity and conclusion strength. Two model findings overstate consequences or misattribute a clause. Valid source quotations alone do not establish substantive correctness. Official examples themselves are not presumed correct and are never supplied as model evidence.

Private originals, short excerpts, visual transcriptions, full matrices and source receipts remain in the ignored local evidence directory: `.local/vetting-official-comparison-20261028/`. Its `BASELINE.md` and `baseline-receipt.json` are the current navigation and exact artifact inventory. None of those private evidence files is part of this source delivery.

## Actual instruction defect and smallest repair

Current assigned instructions already include full checklist descriptions. The older generic-instructions-only diagnosis is obsolete for this source. However, the four mixed contract/pricing descriptions omit their existing per-row scope note when composed into the model user message.

`VettingReviewDirections.instructions()` now appends the existing `Item.getReviewScope()` and `getScopeNote()` only for mixed rows. It asks for independently supported contract-text assessments, keeps excluded pricing/drawing portions unassessed, retains genuine insufficient context and prohibits marking an entire mixed row fulfilled from a partial check. Original requirements, source text, assignments, human counterchecks, output schema and budgets are preserved.

The regression exercises the real `textFirstPacket()` composition for APP-I-007, 027, 048 and 055. It checks the scope note, complete requirement, unchanged single source-text occurrence and absence of additional retrieval/gateway work.

## Software verification and adoption

- The single new regression first failed on the missing scope note, with one expected assertion failure and no errors or skips.
- After repair, all 47 distinct cases in `VettingDirectionWorkflowTest` passed, with no failures, errors or skips. The RED execution is not added to the GREEN count.
- The isolated sealed Candidate22 base contains 1,134 source/resource files. Exactly two paths changed; 1,132 remained byte-identical, with the same POM.
- Root adoption checked both predecessor and candidate SHA-256 values before copying either path, retained backups and verified the resulting bytes. It did not modify runtime or Git state.

| Root-adopted path | SHA-256 after repair |
|---|---|
| `src/main/java/com/consense/service/vetting/VettingReviewDirections.java` | `08dc863d1f17bfb2b8ef4d96752bf0c43cfd0408473ff1b20513e10f5ab2af62` |
| `src/test/java/com/consense/service/vetting/VettingDirectionWorkflowTest.java` | `c71ae986138942ac1cccc5aa36449d05b721dfcc9535a25e9a90b558b49119be` |

The exact local scope manifest is `.local/vetting-official-comparison-20261028/repair-scope/source-manifest.json`, SHA-256 `d5cf9ece98bbcbdd50e8023558044dda2643e27501a0ceddef66b17cc719a092`. Validation and root-adoption receipts are adjacent.

## Remaining data-flow work

Two source-backed cases have decisive original text but insufficient-evidence delivered outcomes. Historical scalar logs prove substantial upstream context reduction; they do not identify whether the target paragraphs were raw hits, candidates, selected context or actually dispatched text. A final zero omission count uses an already reduced denominator. The old run's exact membership remains unknown, and its logs do not support output-budget exhaustion for those two calls.

Targeted selector reproductions and a bounded final-dispatch membership receipt are separate follow-on repairs. They must preserve exact text, source isolation, finite budgets and progressive supplementation without writing complete prompts, responses, reasoning or graphs. A new complete paid review has not been started for this comparison or instruction repair.

This checkpoint proves bounded source comparison, software behavior and root adoption only. Successor packaging, deployment, actual model adherence, complete-file embedded-browser acceptance, same-matrix reassessment and final source publication remain separate gates. The currently running Candidate22 remains the prior package.
