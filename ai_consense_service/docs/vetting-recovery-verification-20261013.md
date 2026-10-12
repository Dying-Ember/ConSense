# Vetting NEXT-05 implementation verification — 2026-10-13

Scope: spec `oer-vetting-advisory-next-development-20261012.md`, D8/D9 and AC11/12/13/15. The implementation uses the existing Java/SQL/bounded worker. This receipt records fixture and code verification, not original-source quality or business acceptance.

## Main implementation

- Commit `0fb16dcd437b290a7ef9ff2a0d5188704fec04eb`: SQL owner/lease, durable packet checkpoints, verified saved-packet recovery, explicit uncertain retries, reliable partial records, check states, fixed-reference pointers, fresh original-byte guards and immutable report snapshots.
- Follow-up `506c1a2d82559c7b8f611c5f27ecc82cf96d9d25`: bind checkpoint compatibility to configured retrieval recipe/signature/window settings and token-observer recipe; use the platform's Java executable in the restart harness.
- Exact main command: `mvn -q -Dtest=Vetting*,FixedBaselineRetrievalClientTest,LlmVettingProfileIntegrationTest test`.
- Main result at `0fb16dcd`: **785 tests, 0 failures, 0 errors, 2 skips**, Maven exit 0. Per-suite counts and XML paths are retained in `target/vetting-final-full-green-receipt.json`; log: `target/vetting-final-full-green.log`.
- Both skips are `VettingLegacyPolicyCompatibilityTest` actual-Python mode-capture cases: explicit `CONSENSE_OER_MODE_FIXTURE` was absent. They remain unverified.
- Final configuration-binding follow-up: `VettingProcessRecoveryTest` 5 + `VettingRecoveryWorkflowTest` 9 = **14 tests, 0 failures/errors/skips**. Log: `target/vetting-recovery-final-identity.log`.

## Actual isolated process restart and report fixtures

`VettingProcessRecoveryTest` creates and terminates only its own child JVMs, with its own file-backed H2 database and loopback HTTP server. It verifies **QUEUED, BEFORE_CALL, BEFORE_SAVE, AFTER_PACKET and BEFORE_PUBLISH** crash boundaries, persisted checkpoint count, restarted public progress/detail/report, no extra gateway call for saved packets and no duplicate final publication. Its clock advancement expires only the test lease. Gateway/retrieval are local fixtures; no live model/index is used.

The 24-page Chinese/English authored PDF fixture at `target/report-qa/vetting-report-zh.pdf` was rendered to `pdf-page-1.png` through `pdf-page-24.png`. All 24 pages were visually inspected for glyphs, table alignment, pagination, long quotations, footer separation and the tail marker. They passed this fixture inspection.

DOCX shared-content and OOXML checks passed; **actual Word-rendered pagination was not performed**. The PDF/DOCX fixture is not a real project business report. The final local receipt is `target/vetting-final-handoff-receipt.json`. These ignored receipts/artifacts are under the implementation worktree `C:\Users\Administrator\.codex\worktrees\oer-vetting-next\ConSense\ai_consense_service` and are not committed source inputs.

## Public write and original-source bindings

Finding responses provide `runId`, `generatedRevisionSha256` and `sourceSnapshotSha256`. Review PUT bodies and status POST query parameters must supply `expectedRunId`, `expectedGeneratedRevisionSha256` and `expectedSourceSnapshotSha256` from the loaded screen. Missing, stale or inactive/current-run mismatches reject with business code **4104** before changing any human field.

Checklist responses provide `runId`, `runSnapshotSha256` and `sourceSnapshotSha256`. Update bodies retain `runSnapshotSha256/items` and add `expectedRunId/expectedSourceSnapshotSha256`. A transaction-held run lock checks latest completed run and fresh current source bytes; item/binding validation precedes all row writes. Historical checklist rows remain readable.

Saved finding/source-dialog evidence provides `originalSourceSha256`. Original GET requests require `sourceHash` and `originalSourceSha256`; the latter must match the saved upload identity and a fresh storage SHA256. Missing or changed bytes reject with business code 4104 and no file response. Legacy unknown raw identity is not silently bound to a replacement file.

Known unresolved necessary members reject consistent/NA declarations and the existing reference/conflict comparisons quoting the affected origin. Unrelated reliable assessments continue. No topic-wide unknown check substitutes for this origin-level gate.

The final guard regression used the same broad command: **796 tests, 0 failures, 0 errors, 2 skips**, Maven exit 0. Per-suite evidence: `target/vetting-review-guard-full-green-receipt.json`; log: `target/vetting-review-guard-full-green.log`. This includes `VettingRecoveryWorkflowTest` **15/15** and the actual child-JVM restart test **5/5**. The two skips remain the explicit actual-Python mode-capture gates above.

The added HTTP cases prove old R1-screen review/status/checklist writes are atomically refused after R2, parsed-source changes, same-size/same-mtime raw-byte replacement, missing bindings and incorrect displayed/generated revisions. Original human text/status and prior checklist decisions remain intact. Original-source HTTP tests verify saved raw SHA propagation, exact valid bytes, missing/wrong identity refusal, and no substituted file response after byte replacement. Four risk/language consistent/NA cases reject the missing `XYZ99(2)` dependency in A while an independent B assessment continues.

An earlier 796-test attempt retained two failures from legacy assertions that expected a known-incomplete consistent declaration to remain accepted; the assertions now preserve raw declaration counts but require `verifiedConsistent=0/rejected=1`. Source fixtures and trust gates were not weakened. Earlier logs remain in `target/vetting-review-guard-full.log` and the focused-attempt logs.

Final merged-branch regression is still required. Live model results, original parsing quality, browser business acceptance, Linux offline artifacts and K8s/H800 acceptance remain separate gates.
