# Advisory project original byte guard: local correction receipt

2026-10-13, Asia/Shanghai. Based on delivery source `43d5f9b`, plus the shared `StorageService.matchesStoredBytes` helper from Vetting commit `0fb16dcd437b290a7ef9ff2a0d5188704fec04eb` (local dependency cherry-pick `5fdc2db`). This correction extends the existing Advice source snapshot to noncanonical project originals for both explicit sessions and legacy requests without a session.

Advice freshly streams each selected original through the shared SHA256 verifier and compares against the stored upload identity. Database hash fields, file size and modification time alone cannot establish current source identity. Missing files, unknown identities, paths outside the real storage root or changed bytes reject with safe code `4204` before sending and before publication; previous messages and their citations stay unchanged.

The legacy project Chat route retains its existing JSON-object instruction and one retry, now through the captured client with source checks after availability, immediately before generation, after the return and at each publication boundary. Changed bytes after an invalid output stop the retry. Explicit session Chat also checks after availability. Canonical and fixed-reference routes retain their existing source guards and transport contracts. The optional StorageService field preserves the existing 11-argument fixture constructor; no new dependency or configuration was added.

The direct-source Advice fixtures now write real local originals and bind their actual SHA256 rather than inventing database-only identities. The populated Advice upgrade fixture executes the actual V17 SQL after V16 so Vetting startup recovery can read its new columns; old Advice messages remain unmodified.

## Actual checks

The final Maven command exited zero with **115 tests, 0 failures, 0 errors and 0 skips**. Counts come from the corresponding `target/surefire-reports/TEST-*.xml` files:

| Class | Tests |
| --- | ---: |
| AdviceSessionIntegrationTest | 18 |
| AdviceDocumentScopeIntegrationTest | 15 |
| AdviceModelIdentityMigrationIntegrationTest | 1 |
| AdviceLlmSelectionIntegrationTest | 8 |
| AdviceUnconfiguredProfileIntegrationTest | 1 |
| AdviceFixedBaselineTest | 7 |
| AdviceCanonicalIntegrationTest | 19 |
| AdviceResponsesTransportTest | 18 |
| CanonicalPageExcerptTest | 8 |
| VettingInputBudgetTest | 6 |
| VettingTokenBudgetCliObserverTest | 14 |

`target/advice-source-bytes-final-tests.log` is the ignored local command evidence. The new HTTP/SQL tests prove same-size original replacement with the exact mtime restored rejects both explicit and legacy answers after exactly one model call, retains the unchanged database SHA and every historical JSON field, rejects changed bytes before either route sends, prevents dispatch when availability probing changes bytes, and retains one successful JSON retry for unchanged legacy originals. An invalid JSON return accompanied by byte mutation cannot dispatch a second request.

The first run recorded 67 tests with one context error because the hand-authored migration fixture stopped at V16; the exact V17 fixture correction resolved it, without disabling recovery or skipping the test. `git diff --check` passes. All checks use isolated H2, real temporary original files and fixture model/counter adapters; overlapping prior runs are not added to this count.

This correction did not call a remote model, change an active service/database/vector owner or validate H800/browser/business answers. Repeated full-original hashing adds disk reads; production performance was not benchmarked.
