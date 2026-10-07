# Drafting independent-review fixes

Date: 2026-10-06. User instruction: "按照独立审查报告修好".
Integration branch: feature/drafting-a-business-rules-20261005.
Baseline service: ded0b86b60c18fca1b966779936ebfdf1b5fca15.
Baseline web: ca332aa96a160bcc1ec24f6ab920c73adb2f8b7d.

Authoritative defect evidence:
<LOCAL_PATH_REDACTED>
and its findings.json / primary-manifest.json / pure-reproduction-results.json.
Primary authority remains the competition brief, Comments, actual 2a templates and 2c correspondence.
This is a bounded correction of five reviewed findings, not a replacement catalog.

## Requirements

1. F1: At the public extraction seam, reject a nonblank candidate when its quotation explicitly contradicts its value or only requests the missing answer. In particular, original 2c CON8 "not used" cannot support true; a request for inspection dates cannot support an invented date; phone 2761 6161 cannot support 9999 9999. Check direct literal values against the quotation with legitimate typographic/date/phone normalization. For structured Bill identities bind number and description to the same source entry, rather than accepting individually occurring but swapped identities. Positive, sourced values remain editable suggestions. Do not infer unrelated classifications by generic keyword guessing, promise universal semantic validation, auto-adopt, or overwrite manual values. Reuse existing rejection/repair/diagnostic contracts where sufficient. Preserve raw failed attempts and bounded targeted repair.
2. F2: Explicitly conflicting complete subcontractor/trade sets, including [] versus nonempty, must remain competing candidates and be reported candidate_conflict / conflict at public project APIs. Do not silently union two complete alternatives. Preserve documented additive partial-source combinations where no complete-set contradiction exists, semantic option identity, manual adoption protection, and source provenance. The user chooses this draft's adopted value; do not assume newest wins.
3. F3: Explicit target delete and not_used for SCC20.303/.304 must work against the actual supplied SCC DOCX containing native OMML. Use verified full-clause structural scope, including contained paragraphs/table/formula nodes, rather than flattening or replacing formula runs. Delete and numbered Not used remain distinct. Unaffected clause text, formula bytes, package parts and formatting must remain intact; cross references follow adopted surviving clauses. Unsafe or unsupported mappings still report unresolved.
4. F4: SCC14.305(1) must recognize the original repeated singular Clause list at P1216 and replace that exact reference collection from the effective adopted specialist-clause set; remove the applicable instruction mark. Retain GCC20.2 and surrounding payment wording. Existing SCC20.301 plural-reference behavior remains valid. Verify this independently of negative formula-removal branches.
5. F5: Direct NTT-9-WTO not_used target decisions must use the same verified full-clause mapping as ordinary wtoGpaApplies=false, preserving "9. Not used". Both delete and not_used must follow their explicitly chosen semantics; do not reinterpret arbitrary range labels as whole clauses. Preserve source-version rejection and exact-wording target controls.
6. Regression: No change to the 21 groups/79 visible inputs, model options/providers, prompt scope, local project data, tri-language values, hidden-input cache, adoption lifecycle, frozen artifact/source identity, or unrelated Vetting/Advice. New rejected candidates stay manually editable, with explained existing diagnostics.
7. Verification must distinguish controlled-model HTTP tests, real provider extraction, DOCX structural/body validation and PDF visual checks. Preserve prior failed reports; do not rerun into or overwrite the user's existing project. Use a dedicated visibly test-only acceptance project on the same isolated runtime.

## Previously agreed acceptance seams

The existing drafting-a spec/tracker records project-scoped upload/extraction/variables/history/plan/generation/binary export, original DOCX through the public parser/source-rule compiler, and frontend observable controls. This user-authorized correction uses those same seams. Persistent regression tests must use these public interfaces, not reflection on private helpers from the disposable review probe. External model doubles are acceptable at the actual LlmClient boundary; in-memory test DB and original template fixtures are isolated.

Each implementer performs vertical RED -> minimal GREEN slices and saves immutable logs in its assigned output directory. Write source-based positive counterexamples alongside each defect regression. Run bounded relevant regressions before integration. Root performs integrated package/API/real-provider/source checks; independent standards and spec reviewers assess the pinned baseline diff before closure.

## Scope boundaries

No remote publication, no competition hardware deployment, no global configuration changes.
Existing unresolved business decisions remain unresolved; do not invent a generic non-adoption policy.
Do not claim the previous A09 native browser-download acceptance or universal Word visual quality from this correction.

## Implementation and verification closure

All REVIEW-F1 through REVIEW-F5, REVIEW-INTEGRATE, REVIEW-REVIEW and REVIEW-CLOSE are Done for this bounded local correction. The final production and independent-review pins are service `aa22042363e57e9374be5e60f27289814532a02c` and web `c7de743b80d4562533e9d094f4ad1aea06494a5b`. The subsequent repository closure commits change only this spec, its task graph and the local tracker. The root's [measured verification report](<LOCAL_PATH_REDACTED>

The implementation records retain public-seam vertical RED/GREEN and positive boundaries: [F1 grounding and Bill requests](<LOCAL_PATH_REDACTED>`9. Not used` with only its editing star removed; delete and unsupported-edition behavior remain distinct.

The first independent Spec review at `86efb31` found a Bill request-only identity gap. That failed report, its public RED and the separate `ea6098d` correction/review are retained. Final real QA then reopened F2 for blank conflicts omitted by the web filter and F5 for a retained NTT9 star; the new rendered/source regressions, fixes and [actual conflict browser recheck](<LOCAL_PATH_REDACTED>

Controlled-model/public HTTP regression evidence remains separate from package output. The `ea6098d` [83-case integrated package run](<LOCAL_PATH_REDACTED>`drafting-20261006.8-docx` and immutable JAR SHA256 `32a58e5d5753f010de8e84a7c88ca175c1ff1800c5e1f1587755bcb62bd2ab34` on the isolated preview.

Real MiniMax-M3 [simulated-01](<LOCAL_PATH_REDACTED>`[]`; [conflict-01](<LOCAL_PATH_REDACTED>`[]` for both generic-trade sources. Those failed omission records did not exercise rejection/merge handling. Separate single-request [provider-arch-only-01](<LOCAL_PATH_REDACTED>

New actual public [generation-02](<LOCAL_PATH_REDACTED>`9.` without a star, distinct deletion/numbered Not used, exact surviving references, native OMML and unrelated package preservation. The [PDF visual record](<LOCAL_PATH_REDACTED>

The final browser restored the original profile/language/edit step; [after-02](<LOCAL_PATH_REDACTED>
