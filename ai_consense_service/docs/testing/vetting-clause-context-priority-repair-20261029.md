# Vetting assigned-clause context repair

Recorded on 29 October 2026, Asia/Shanghai. This is a software and source-adoption checkpoint; the overall substantive-review goal remains active.

## Problem and resulting behavior

The complete official-to-original comparison identified source-backed cases where the delivered assessment reported missing operative text. Existing aggregate logs cannot prove the historical target's exact retrieval or admission membership. Authored tests against the actual current selector nevertheless reproduce two concrete loss mechanisms: appendix/project-suffix recognition and unrelated ranked seeds exhausting space before an affordable assigned clause body.

`OerBusinessEvidence.reviewTextContext()` now recognizes `.APPEND<number>` before the optional `.P` suffix. It preserves numbered subsections within the same explicit clause/heading owner and selects explicitly requested, same-source clause text before unrelated ranked seeds consume the allowance. Exact source text, document/role/revision/projection isolation, whole-chunk budgeting and the existing 64-member window remain intact. It does not expand a complete appendix or make an oversized clause a hard failure of the review.

## Reproduction and verification

Before production changes, the actual selector suite ran 30 cases with three expected assertion failures and no errors/skips. The new cases exercise unowned and declared appendix metadata plus an affordable 1,247-character target competing with nine unrelated 1,000-character seeds under the unchanged 10,000-character allowance. All fixtures in source tests are authored, not copied competition originals.

After repair, 155 distinct cases in five related suites passed, with no failures, errors or skips. This count includes 46 predecessor workflow cases. Two earlier Surefire launch failures executed zero tests and remain separate environment evidence.

The shared source-delivery coordinator then copied the sealed Candidate22 source and exact POM (`c3ccab0ef824081a1c80b7fd1f13097046e915a00b5c3b7840ca2996a26f9f9c`), applied the exact two context paths and already-published mixed-scope pair, and recompiled the combination. The five affected suites passed 19 + 4 + 30 + 56 + 47 = 156 cases, with no failures, errors or skips. This includes the new mixed-scope workflow case. The older 155-case gate overlaps and must not be added. An earlier 77-case check used a different POM and is not the canonical composition gate.

The two already-published scope dependencies in that exact four-path composition are `VettingReviewDirections.java` at `08dc863d1f17bfb2b8ef4d96752bf0c43cfd0408473ff1b20513e10f5ab2af62` and `VettingDirectionWorkflowTest.java` at `c71ae986138942ac1cccc5aa36449d05b721dfcc9535a25e9a90b558b49119be`. The two context hashes are listed below. These four source/test bytes and the pinned POM identify the software gate; they do not identify a deployed package.

## Exact adopted scope

| Path | SHA-256 after repair |
|---|---|
| `src/main/java/com/consense/service/vetting/OerBusinessEvidence.java` | `b9d0371f371cea563373f7adc3da03187e226b6c3afab7eb9023299e144beb3c` |
| `src/test/java/com/consense/service/vetting/OerReviewTextContextTest.java` | `95e04dbc90642398e58f38ea780596043a5b606c02ece9b5b6daf9ba864e72db` |

Exactly these two source/test paths differ from the sealed Candidate22 base in this repair lane. Root checked both predecessor and result hashes before adoption, retained backups and did not replace a running service. The earlier [mixed-scope repair](vetting-official-baseline-and-scope-repair-20261029.md) is a separate source batch.

Local evidence stays ignored: `.local/vetting-official-comparison-20261028/repair-context/validation-receipt.json` has SHA-256 `414916ca8ce58874cbd89a6d08f8aee0468c0e2b5ddb2f7b834d5fbe22a97ce0`; the source/test patches, authored RED/GREEN logs, five-suite XML and root-adoption receipt are adjacent. The coordinator's canonical 156-case log is `.local/git-delivery-20261028/vetting-sealed-five-suites.log`.

## Unfinished acceptance

The repair does not prove neural retrieval relevance, the historical model request, provider interpretation or a new correct report. A long appendix can still require a specific subsection or progressive supplementation. Final-dispatch membership diagnostics are independently under development and are not part of this two-path delivery. No complete prompt, response, reasoning, graph, original file, DB or index is included in the source delivery.

No new paid complete review was started. The running Candidate22 package remains unchanged. Full successor composition, packaging, controlled deployment, complete-file embedded-browser/model verification and same-matrix reassessment are still pending; this source checkpoint must not be presented as frontend or substantive review acceptance.
