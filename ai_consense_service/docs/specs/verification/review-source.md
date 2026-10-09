# Independent review source corrections: F3 / F4 / F5

Date: 2026-10-06. Scope: `drafting-independent-review-fixes.md` REVIEW-F3, REVIEW-F4, REVIEW-F5. Rule identity: `drafting-20261006.7-docx`.

## Source authority and observed defects

The independent report is `C:/Coding/ConSense/output/drafting-a-implementation-20261005/a19-clean-context-review-20261006/review.md`. The new tests read the actual supplied 2a DOCX files from `C:/Coding/ConSense/2026-09-04_Documents to Shortlisted Candidates (Contract Advisory)/2a_Standard documents` and use project creation, template upload, variable adoption, plan, generation and binary DOCX export HTTP APIs. There are no internal mocks, reflection tests, provider calls or modifications to the user's project.

* SCC SHA256: `9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208`. SCC20.303 starts P1550; its non-adoption guidance is P1554. SCC20.304 starts P1665; its guidance is P1669. Native equations occur at P1409, P1539, P1654 and P1768. The two removed specialist clauses also contain complete combined labour/material formula tables. SCC20.304 ends before **P1779 SCC22 GENERAL**, rather than at P1781 SCC22.301.
* SCC14.305(1), P1216, has the exact repeated singular list `Clause SCC20.302, Clause SCC20.303 and Clause SCC20.304 of the Special Conditions of Contract*`. P1212 instructs the preparer to synchronize this collection and remove the mark. Both surrounding GCC references and the rest of the payment paragraph must survive. The separate plural list remains at P1430.
* NTT SHA256: `60bd8796aa828863c7edfe8617b7a5c56ee383afe8ba87a089e820f0fd9a982c`. The verified NTT9 clause occupies P435–471, including its number cell and complete rows; P439 provides the WTO negative instruction. The following NTT10 substantive paragraph P484 stays intact.

## Changes and explicit limits

F3 compiles only the explicitly adopted **delete** / **not_used** decisions for SCC20.303/.304 into verified structural removals. Not used keeps the native numbered heading; delete removes it. The reviewed SCC package identity and complete paragraph/boundary checks certify this narrow scope. Whole-clause native OMML editing outside that scope, including SCC20.302, retains its previous rejection. Unknown DOCX package editions remain unresolved.

The original P1666 is empty except for three bookmark starts whose paired ends are direct body children. That paragraph and its pairs remain intact; all substantive .304 paragraphs, table nodes and equation nodes are removed. The global partial-bookmark safety check is unchanged. The semantic/public source-text range is also stopped before P1779, so the displayed target scope agrees with the actual DOCX scope.

F4 replaces the exact singular collection at P1216 using the effective adopted specialist clauses. It preserves the payment paragraph and GCC20.2 wording and removes the instruction mark. Existing plural-reference behavior at P1430 is unchanged. A three-clause retention test isolates this fix from F3; it is a mechanical source test, not a claim that mutually exclusive fire trade options form a real project scope.

F5 recognizes the specific `NTT-9-WTO` target identity for explicit Not used/delete, rather than parsing arbitrary range labels as complete clauses. Direct Not used reuses the ordinary WTO=false heading/body mapping. Delete removes the reviewed complete rows, including the number cell. The supplied NTT edition is required for this newly certified manual mapping; unsupported amendments and other labels keep their existing exact-target controls.

The rule-version increment makes the corrected generation distinct from older frozen generations. No old artifact bytes, project inputs, catalogue entries, provider settings, candidate merging, extraction harness or shared task tracker are edited here.

## Vertical TDD evidence

Evidence root: `C:/Coding/ConSense/output/drafting-a-implementation-20261005/a20-independent-review-fixes-20261006/source`.

| Slice | RED evidence | GREEN evidence | Verified boundary |
| --- | --- | --- | --- |
| F3 actual formula-bearing negative targets | `f3-red.log`: output retains 4 OMML instead of 2 | `f3-green-v4.log` | Both explicit actions, 2 preserved equations, 1 surviving specialist formula table, native neighbour/anchor paragraphs, non-main package parts |
| F3 public displayed source boundary | `f3-source-boundary-red.log`: sourceText includes SCC22 GENERAL | `f3-source-boundary-green.log` | Only .304 explicit negative target; both plan source scope and DOCX preserve following chapter heading |
| F4 original singular collection | `f4-red.log`: public action unresolved | `f4-green.log` | Three clauses retained, exact payment paragraph except instruction mark, all 4 original equations |
| F5 direct target negative | `f5-red.log`: public action unresolved | `f5-green.log` | Direct Not used DOCX byte-identical to ordinary WTO=false; delete has no NTT9 number cell; NTT10 and opaque parts retained |

All logs are retained. The first F3 patch attempt, `f3-green.log`, failed on the actual partial-bookmark boundary and prompted preserving the empty anchor. `f3-green-v2.log` and `f3-green-v3.log` are test-expectation corrections: the original has one combined formula table per specialist clause, and cached native TOC titles must be distinguished from operative clause headings. The final test asserts exact actual operative heading removal/replacement and leaves cached field refresh status explicit.

## Regression and remaining verification

`source-positive-safety-controls.log` passed 2 tests / 0 failures / 0 errors / 0 skips. A mixed explicit .303 delete / .304 Not used scenario with Electrical retained produces the exact surviving reference collection at both P1216 and P1430, retaining surrounding wording. A font-only package-edition change for NTT and SCC preserves the unsupported negative target and all original native equations, with `SOURCE_STRUCTURAL_CLAUSE_EDITION_UNVERIFIED`; it does not gain permission from an explicit decision alone.

Final `source-scoped-regression.log` completed with **45 tests / 0 failures / 0 errors / 0 skips, BUILD SUCCESS**, at 2026-10-06 14:52:15 +08:00. Breakdown: 5 new public HTTP source methods, 3 selected actual-standard artifact methods, 5 clause-rule tests, 13 business-rule tests, 8 public package-editor tests and 11 package-editor safety tests. The selected artifact controls preserve the old SCC20.302 formula-scope rejection, verify ordinary full-clause neighbours, and allow ordinary text amendment beside math while rejecting a clear that would orphan the native formula. The new source methods executed with the actual standards, including both negative actions and the isolated reference control.

The bounded selector was `DraftingReviewSourceIntegrationTest,DraftClauseRulesTest,DraftBusinessRulesTest,DocxTemplateEditorTest,DocxTemplateEditorSafetyTest,DraftingArtifactIntegrationTest#wholeClauseWithNativeFormulaRemainsUnresolvedAndPreservesAllSourceEquations+wholeAdoptedClausesReplaceCompleteSourceScopesAndKeepTheirNeighbors+visibleTextAroundNativeMathCanBeAmendedButCannotBeClearedIntoAnOrphanFormula`. Java 17 and Maven 3.9.16 were configured only for the invoked command. Integration tip `eec9110514063cadb1fcdea16e89d2167778e0df` was merged before this regression. Final source hashes and report copies are recorded in the output evidence receipt.

`consense.acceptance.sourceDir` is supplied explicitly, so the actual-standard source tests execute rather than skip. Test exports are immutable hash-named DOCX files under `f3-artifacts`, `f4-artifacts`, `f5-artifacts` and `control-artifacts`.

DOM equality checks verify original native equation content and properties; all DOCX parts other than `word/document.xml` are compared byte for byte. These tests do not certify PDF rendering, Word pagination or field refresh. Cached TOC/REF/PAGE fields retain the existing separate unrefreshed/pagination review diagnostics. The parent performs integrated package, actual API/source-counter and renderer checks independently. Prior failed reports remain evidence and are not overwritten.

## F5 follow-up: NTT9 Not used number marker

The parent read-only review of `generation-01` found that ordinary WTO=false and the equivalent direct Not used package both retained source P435 `*9.` next to P436 `Not used`. This is existing ordinary-false behavior, rather than a failure to apply the direct target. The actual number and title occupy separate cells in one native table row. The parent explicitly authorized cleaning this one editing star while preserving those cells and their formatting; the rule version is now `drafting-20261006.8-docx`.

Only the source compiler's NTT9 whole-clause Not used mapping is changed: when the exact replacement is `9. Not used` and the source number is exactly `*9.`, it edits that number to `9.` using the existing bounded text spans. The delete mapping and all other clause markers are unchanged. Both ordinary WTO=false and explicit Not used still produce identical packages; an unknown manual-source edition still reports unresolved.

The existing public actual-source F5 test now requires a native `9.` paragraph and adjacent `Not used`/empty-guidance cells for both routes. It compares the complete source number paragraph with only the single star removed, so its runs, styles, tabs and properties must stay intact. Original P243, including its unrelated Works Subject to Excision editing star and native properties, must remain exactly equal. The previous NTT10 neighbour and opaque-package-part checks remain.

`ntt9-star-red.log` is the required RED: one executed public test fails with `Missing source paragraph: 9.` in the ordinary-false output. The first `ntt9-star-green.log` passes the new number/run/row assertions, then fails on a mistaken counterexample expecting `*17.`; the original NTT actually has `17.`. This failed log and its hash-named export are retained. The counterexample was corrected to the actual source P243 star without changing the production patch or relaxing the NTT9/format requirements. New evidence uses a separate `ntt9-star-scoped-green.log` and `ntt9-star-final-artifacts` directory.

The independent QA recheck is recorded outside the repository in `a20/artifact-structural-recheck.md`. It preserves the original 11 failed structural checks and explains XML attribute-order false positives and the native table-row comparator. The separate normalized historical audit passes 36/36 checks while explicitly recording the old star observation; it does not relabel old exports as already cleaned. Parent post-fix export acceptance must use `audit-artifacts-normalized.py generation-02 --require-clean-ntt9` and newly frozen documents. No previous export bytes or evidence reports are changed here.

Final `ntt9-star-scoped-green.log` completed at 2026-10-06 15:48:39 +08:00 with **8 tests / 0 failures / 0 errors / 0 skips, BUILD SUCCESS**: all 5 actual-source public review scenarios and the same 3 native-artifact safety/context controls described above. Actual sourceDir was supplied. The ordinary-false and explicit Not used package is now SHA256 `b0d9c248e61d396db257f6fb231864a638a318db426f3cd72e3d7d7fe446b657`; the explicit-delete output retains its prior SHA256 `f6aeeb9c4ac9e57a6b9a460c70b28141918e5d95ea92ce9732718141f02e2697`. This patch and run started from integrated service pin `ea6098d8845592f4ac317f41973ec9cb31a21b12`. Parent integrated runtime/PDF checks remain a separate acceptance step.
