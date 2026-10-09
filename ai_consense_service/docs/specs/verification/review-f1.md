# REVIEW-F1 verification

Date: 2026-10-06. Branch: `fix/drafting-review-f1-20261006`. Integration base merged before the final run: `eec9110514063cadb1fcdea16e89d2167778e0df`.

Authority: `docs/specs/drafting-independent-review-fixes.md` requirement F1 and the original independent audit's F1 at `C:/Coding/ConSense/output/drafting-a-implementation-20261005/a19-clean-context-review-20261006/review.md`. The original audit and its artifacts remain unchanged.

## Result and boundaries

`DraftExtractionHarness` now records quotation/value mismatch for bounded direct evidence checks. CON8's explicit used/not-used statements and its unanswered requests remain separate from other Boolean classifications. Inspection dates preserve normalized shared-month, full-date and labelled start/end roles; requests containing proposed dates do not establish an answer. Telephone punctuation normalization preserves the original digits. Direct text, numeric and contract sibling literals must occur in an answer passage, including legitimate typography and numeric scale. A declared title survives a broader quote's unrelated question.

Bill number and formal description bind to one source entry and the quoted entry. Compact lists split only at explicit next entries; description-internal semicolons remain intact. The saved A10 fixture's exact Bill12 `Site Safety; Environmental Management and Other Sundry Requirements (All Provisional)` and all 13 Bill identities in that test-only source survive. Partial contract/Bill identifiers remain partial. Existing optional Bill metadata classification remains separate from the protected description.

Harness version is `draft-extraction-20261006.6`. Existing `quote_value_mismatch` and `lexical_support_missing` diagnostics, targeted repair and immutable attempt/history contracts are reused. No new UI reason code, catalog field, provider, prompt scope, merge rule, source-edit/compiler rule, adoption policy or global configuration was introduced. Accepted intake remains an editable suggestion, not a certification of general semantic truth. Existing adopted/manual values are protected.

## Public acceptance seam

The new `DraftingQuoteGroundingIntegrationTest` uses project creation, real upload and DOCX parsing, extraction, variables and immutable history HTTP controllers through MockMvc. H2 and storage are isolated per class; only external `LlmClient` is doubled. There are no reflection/private-helper regression tests. The already agreed TDD seam is recorded in the correction spec; no new seam was required.

The three original 2c DOCX fixtures are byte-identical to the supplied source files and have provenance/SHA-256 recorded in `src/test/resources/drafting/original-2c/README.md`. Their model responses and project names are TEST ONLY. Original Architect correspondence supports `2761 6161` and `Between 17th and 21st February 2025`; the original QS request does not establish a date or CON8 usage. The A10 Bill12 fixture is explicitly simulated TEST ONLY correspondence, used solely for formal-description preservation.

## RED / GREEN evidence

Immutable logs are in `C:/Coding/ConSense/output/drafting-a-implementation-20261005/a20-independent-review-fixes-20261006/f1/`:

| Slice | RED | GREEN |
|---|---|---|
| Original CON8 negation, retained wrong attempt and false repair | `01-con8-red.log` | `02-con8-green.log` |
| Original date request, real range endpoints and bounded failed repair | `03-date-red.log` | `04-date-green.log` |
| Original phone digits and supported punctuation | `05-phone-red.log` | `06-phone-green.log` |
| Direct literals, normalization and partial contract identity | `07-direct-literals-red.log` | `08-direct-literals-green.log` |
| Swapped Bill number/description pairs | `09-bill-pairs-red.log` | `10-bill-pairs-green.log` |
| Shared-month range endpoint role | `11-date-endpoint-red.log` | `12-date-endpoint-green.log` |
| Partial Bill identifier | `13-partial-bill-red.log` | `14-partial-bill-green.log` |
| Positive decimal full stop / compact Bill regression repair | `15-integrated-regression.log` | `16-numeric-punctuation-green.log`, `17-compact-bill-green.log` |
| Original CON8 request cannot establish true or false | `18-con8-request-red.log` | `19-con8-request-green.log` |
| Full and labelled inspection date endpoint roles | `20-full-date-endpoint-red.log` | `21-full-date-endpoint-green.log` |
| Proposed date inside a question / actual table reply | `22-date-question-red.log` | `23-date-question-green.log` |
| Proposed phone inside a question / actual contact reply | `24-phone-question-red.log` | `25-phone-question-green.log` |
| Ordinary future CON8 negation / affirmative usage | `26-con8-future-red.log` | `27-con8-future-green.log` |
| Actual Bill12 internal semicolon | `29-formal-bill-semicolon-red.log` | `30-formal-bill-semicolon-green.log` |
| Direct text/number requests and unrelated-question title counterexample | `31-direct-literal-question-red.log` | `32-direct-literal-question-green.log` |

The first wider run (`15`) had 68 tests with four failures caused by new positive-boundary regressions; those are retained, with targeted corrected GREENs. The next 78-test run (`28-final-bounded-regression.log`) exposed two formal-description semicolon failures, also retained. Corrected run `30` passed 18 tests, including the actual A10 eight-DOCX/13-Bill replay and the existing semicolon/type counterexample. Run `32` passed all 17 new focused public tests.

## Final relevant regression

`33-final-bounded-regression.log` runs `DraftingQuoteGroundingIntegrationTest`, `DraftingHarnessIntegrationTest`, `DraftingExtractionHotfixIntegrationTest`, `DraftingAdoptionIntegrationTest`, `DraftingTradeConflictIntegrationTest` and `DraftingValueDecodingIntegrationTest`, with the original 2a standard DOCX directory supplied explicitly. Result: **80 tests, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESS, finished 2026-10-06 14:56:24 +08:00. This includes 17 new public F1 cases, original 2c parsing, all 13 A10 Bill identities, pending/null/false/empty-list distinctions, bounded repair/history, concurrent manual adoption, source-change atomicity, row metadata and integrated F2 trade conflicts. Byte identity of the original 2c fixtures is also saved in `fixture-sha256.json`.

Java 17: `C:/Coding/ConSense/tmp/java_tools/jdk-17.0.20.1+1`; Maven 3.9.16: `C:/Coding/ConSense/tmp/java_tools/apache-maven-3.9.16/bin/mvn.cmd`. `JAVA_HOME` was set only in each shell process. No live app, project database, browser, model configuration or global environment changed.

This record covers controlled-model public HTTP intake/persistence/history and related regressions. Real provider extraction, deployed browser behavior, integrated package/source export acceptance and document rendering remain root integration gates and are not claimed here.

## Independent Spec review closure: Bill request passages

The fresh independent Spec review found a remaining F1 family case: `Please confirm whether Bill No. 1 is Preliminaries?` still admitted the proposed full Bill identity. The dedicated worktree first merged integration `86efb31edd9dc977dff3235f2b3b315f1c21c29a`. Public HTTP RED `34-bill-request-role-red.log` reproduced **expected rejected but accepted** (1 test, 1 failure); the authoritative review evidence remains unchanged.

The focused correction keeps the number/description association within its actual source entry and evaluates the number anchor in the original passage, including any request prefix before `Bill No.`. A number-only request and a description-only request likewise supply no answer. Existing passage checks also reject the same requests written without a question mark. An explicit full or partial Bill declaration still supplies a suggestion when a broader quote ends with an unrelated site-inspection question. A missing description stays missing. Description-internal semicolons and compact numbered lists retain their prior behavior. Only the grounding helper, its public acceptance tests and this verification note changed in this closure; the `.6` harness diagnostic contract and other modules remain as recorded above.

GREEN `35-bill-request-role-green.log` passed **18 tests, 0 failures, 0 errors, 0 skipped** in the focused public class, including the new full/partial request regression and the existing Bill12, compact-list, original correspondence and repair/history boundaries.

The next bounded run `36-bill-request-final-bounded-regression.log` reported **82 tests, 0 assertion failures, 1 error**. It retained an existing positive regression: `Bill1 Preliminaries: Is this a BQ?` supplies the Bill identity but only asks for its optional classification. The initial passage filter rejected that declared identity and the unchanged existing test observed an absent stored list. The corrected Bill-specific passage boundary also separates a colon that introduces a following question; it preserves the declared number/description while the existing metadata sanitizer leaves the requested type unknown. The existing test and its expectations were not changed.

Corrected GREEN `37-bill-identity-before-type-question-green.log` passed **20 tests, 0 failures, 0 errors, 0 skipped**: all 19 focused F1 HTTP cases plus that unchanged identity/type regression. The two added public methods exercise six request-only extractions (full, number-only, description-only, with and without question marks) and three declared full/partial identities with a later unrelated question.

Final closure regression `38-bill-request-final-bounded-regression.log` ran the same six public integration classes and original 2a source directory as run `33`, now on integration `86efb31edd9dc977dff3235f2b3b315f1c21c29a` plus this focused correction. Result: **82 tests, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESS, finished 2026-10-06 15:21:03 +08:00. This includes all 19 focused F1 cases, the unchanged identity/type question case, formal Bill12 semicolon preservation, compact lists, all 13 A10 Bill identities, original 2c positive/negative evidence, repair/history, manual adoption, F2 conflict behavior and integrated source-change atomicity. The earlier failure logs and original handoff receipt remain immutable; a separate closure receipt records the new frozen commit. Deployed real-provider and package/export gates remain the parent's responsibility.
