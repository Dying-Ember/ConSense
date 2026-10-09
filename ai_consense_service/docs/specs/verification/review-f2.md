# REVIEW-F2 verification

Date: 2026-10-06. Implementation branch: `fix/drafting-review-f2-20261006`.
Initial integration base: `b9478ddafc98d9c091a5377ff06089dc352ad008`.
Defect authority: F2 in the independent review at
`C:/Coding/ConSense/output/drafting-a-implementation-20261005/a19-clean-context-review-20261006/review.md`.

The project extraction API now preserves conflicting complete subcontractor sets as competing candidates. An unadopted input stays blank with `adoptionState=conflict`, and the extraction report field has `status=candidate_conflict`. This includes explicit none (`[]`) against a nonempty list. A user can select either original candidate, including none, through the existing adoption API.

`DraftTradeCandidates` evaluates the original normalized candidate sets together. It combines only quotations that expressly describe partial trade confirmations or additional selected trades. A complete set must agree with every other complete set and contain the partial positive facts. An explicit full-list assertion takes precedence over incidental discussion of future additions. Ambiguous differing lists remain alternatives for user choice. This bounded source-scope policy does not choose the newest document or claim general natural-language entailment. No DTO, prompt, harness, catalog, source/compiler, model configuration, or frontend change was needed.

The existing adoption lifecycle remains in place: manual and previously selected values survive re-extraction; changed related candidates require review; original candidate file names, source IDs, source hashes, quotations, confidence and normalized option identities remain available. Historic extraction decisions and raw responses stay immutable when a conflicting source is removed.

## Public-seam TDD evidence

All new tests use project creation, actual upload/parser, extraction, variables, input deletion, candidate/manual adoption and report/history HTTP APIs through MockMvc. They use the actual service/JPA implementation with an isolated H2 in-memory database and a unique test upload directory. The only mocked dependency is the external `LlmClient`. All correspondence fixtures and model replies are visibly TEST ONLY synthetic cases, not historical project evidence.

Four vertical RED -> minimal GREEN cycles were retained under
`C:/Coding/ConSense/output/drafting-a-implementation-20261005/a20-independent-review-fixes-20261006/f2/`:

- `01-red-complete-sets.log`: the existing merge returned `suggested` and `[Electrical,Lift]` for two explicitly complete alternatives. `02-green-complete-sets.log` verifies conflict and user candidate selection.
- `03-red-additive-partials.log`: the first safe conflict guard prevented justified additive partial suggestions. `04-green-additive-partials.log` verifies their combination while retaining the distinct `Lift and escalator` option.
- `05-red-complete-subset.log`: an agreeing partial fact inside a complete set was incorrectly treated as conflict. `06-green-complete-subset.log` verifies agreement in either candidate order.
- `07-red-complete-precedence.log`: incidental conditional discussion of an additional trade caused an explicit full list to be enlarged. `08-green-complete-precedence.log` verifies complete-scope precedence.

The final seven new integration cases additionally cover none/nonempty conflict in either order, adoption of none, semantically equal lists with reversed option order, selected-candidate re-extraction, preserved manual adoption, source resolution, review and immutable historic traces.

## Bounded regression result

Command (process-only Java 17 environment):

```powershell
& 'C:/Coding/ConSense/tmp/java_tools/apache-maven-3.9.16/bin/mvn.cmd' -q '-Dtest=DraftingTradeConflictIntegrationTest,DraftingAdoptionIntegrationTest,DraftingHarnessIntegrationTest,DraftingExtractionHotfixIntegrationTest,DraftingValueDecodingIntegrationTest' test
```

`09-bounded-regressions.log` completed with exit code 0. The five preserved JUnit XML reports and `09-bounded-regressions-summary.json` record 63 reported cases, 60 executed, 3 existing reference-template cases skipped, zero failures and zero errors. All 7 new trade-conflict cases executed successfully. The run used Java `17.0.20.1+1` and Maven `3.9.16`; no persistent environment was changed.

This evidence proves controlled-model public API behavior and relevant lifecycle regressions. It does not claim real-provider extraction quality, actual browser behavior, original-template DOCX rendering or PDF visual quality. Integration deployment and those wider acceptance checks remain with REVIEW-INTEGRATE; this note does not close the shared tracker ticket.
