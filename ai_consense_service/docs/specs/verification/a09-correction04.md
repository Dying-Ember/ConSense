# A09 correction04 — native control-only editing capability

Actual final live05 DTOs advertised native control-only paragraphs as editable:12 NTT,18 SCT and85 SCC blocks contained only tabs or breaks, with `editable=true` and no unsupported reason. These native controls do not provide an ordinary text span. Permanent read-only observation `a09/a09-control-only-editable-observation.json` binds all affected blocks to the exact final JSON, revision and Word identities; its SHA-256 is `efbd6ce5d03a0bb54ae718e3d0f1813f7f50c4fa18a200f42b64861c2e4e85dd`.

The shared capability guard now checks zero actual `w:t` characters through `getCatalogText().isEmpty()`, rather than flattened text which includes native controls. Such blocks expose `EMPTY_SPAN_UNSUPPORTED` and reject forged edits through the same guard. Visible ordinary text, including actual SCC P1409's `(i.e.` text beside native math and a break, remains editable. Existing field protection and rejection of blank replacement beside native math are preserved.

Existing immutable artifacts can contain the old persisted capability flags. `documentVO` conditionally derives their current blocks from the exact frozen DOCX when stored flags advertise a control-only block as editable. It does not update stored blocks JSON, bytes, content, source/input snapshot, revision pointer, Word/PDF hash or cache. Correct new metadata takes the existing direct-read path. There is no compiler, source editor, business rule, catalog, rule-version, frontend or renderer change; version remains `drafting-20261006.6-docx`.

## Public RED and focused GREEN

Permanent evidence is under `C:/Coding/ConSense/output/drafting-a-implementation-20261005/a08/`:

| Observation | Evidence and result |
|---|---|
| Actual source DTO and forged-batch RED | `correction04-control-only-red.log` / `correction04-control-only-red-junit.xml`:2 tests,2 assertion failures,0 errors/skips. Control-only DTO flags are wrong; the forged batch reports the downstream inline-control error instead of the shared capability reason. |
| Existing artifact public GET RED | `correction04-existing-capabilities-red.log` / `correction04-existing-capabilities-red-junit.xml`:1 test,1 assertion failure,0 errors/skips. Persisted old flags remain incorrectly editable. |
| Final focused GREEN | `correction04-capabilities-focused.log` / `correction04-capabilities-focused-junit.xml`:7 tests,0 failures/errors/skips, BUILD SUCCESS, completed2026-10-06T06:43:23+08:00. |

The tests observe public generation, GET metadata, PUT body batches and exact Word export with the actual three standards. The existing-artifact test explicitly appends a test-only pre-correction artifact copy in its isolated H2 database and selects that fixture; it leaves the original immutable artifact untouched. Assertions remain at public GET/save/export seams. Corrected capability flags preserve the fixture's exact revision, content, source/input snapshot and Word bytes; a forged control replacement preserves that same pointer and bytes. A batch containing an ordinary amendment followed by a forged control replacement rejects before publishing any revision. The ordinary body-edit/replay, protected-empty, visible-math and field-protection cases are also rechecked.

Focused selector:

`DraftingArtifactIntegrationTest#nativeControlOnlySourceBlocksAreProtectedWhileOrdinaryTextAroundMathStaysEditable+aForgedNativeControlOnlyReplacementRejectsTheEntireBodyBatchBeforePublishing+preCorrectionCapabilityFlagsAreRefreshedWithoutRebindingTheFrozenArtifact+aProtectedEmptyBlockRejectsTheEntireBodyBatchWithoutPublishingARevision+visibleTextAroundNativeMathCanBeAmendedButCannotBeClearedIntoAnOrphanFormula+sourceFieldRangesAndNativeMathClearRejectForgedSavesAndPreserveTheRevision+sourceBoundBodyEditPublishesANewRevisionAndRejectsReplayedOrFlatEdits`

The command uses command-local JDK17/Maven3.9.16 and the actual `consense.acceptance.sourceDir`; exported evidence is retained in `correction04-artifacts/` by content SHA. This classification-focused run does not configure a renderer or claim new PDF layout acceptance. Root's accepted live05 Word/PDF/raster files remain immutable; the next runtime/full regression belongs to root.

## Prepared package and identity boundary

`correction04-package.log` records BUILD SUCCESS for `mvn -DskipTests package`. JAR: `C:/Coding/ConSense-isolated/drafting-implementation-20261005/tickets/a08-service/target/consense-service-1.0.0.jar`, SHA-256 `e9b92288a59fc321236c26efe0ae1bd9b5e1f20cf8862dfc9c7821e7a775eeec`. Outside `correction04-ready.json` binds the exact ready commit/production tree, clean latest-integration ancestry, prepared JAR and unchanged accepted live05 artifact hashes.

The retained accepted live05 files were rehashed unchanged during this work:

| File | DOCX SHA-256 | Cached API PDF SHA-256 |
|---|---|---|
| NTT | `6944d2990f3ac4fc6d46ef4770ec0df24e6db04eda04a27240a3adb4caa0754b` | `55c3e31eb59d4f20c8834807e5843c7ab9024e4c3f18d7e9f2ffea760f71e1af` |
| SCT | `bf8e7bcd4b1f35f5ebc99bed4733217f83ebcbafe5bfb05d29111ea840a7af9c` | `6d4b7ce25f0c5051163d195ac4e4ae376b4c9dc59e66d0b6a4bd8e3903e91bce` |
| SCC | `f8344ebcaa7ec7c151667a6d3bd8c8fff5ad0ed2f289f14d934277d4bb0f5291` | `441437832095ca46fece0c832df0180d23a552d9da735e500541e0883ebf6a49` |

This is retained-artifact identity, not a claim of a post-merge runtime run. Independent review/merge and root's final existing-artifact capability/atomic-save check remain separate. Field refresh, inherited source rendering limitations and the measured portable-renderer profile-path constraint remain explicit.
