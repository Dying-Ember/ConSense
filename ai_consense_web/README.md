# ConSense web — current source snapshot

This frontend is exported from source commit `629ef98d197cd83e4e7128e468b292492e4443f2` into a new repository without the source repository's old Git history. That initial source was subsequently updated by remote drafting PRs and the Oct 9 local vetting/advice integration. The npm lockfile was repaired for its jsdom/canvas optional peer dependency and tested with fresh npm ci. See repository-root SOURCE-INTEGRATION.json for the current sources and verification. Only the historical scope-check baseline lookup has been made portable; the exact old baseline is included as a test fixture.

## Install, start and build

Use a current Node.js installation compatible with the checked-in Vite/Vue toolchain. From this directory:

```powershell
npm ci
$env:CONSENSE_API_TARGET = 'http://localhost:8080'
npm run dev -- --host 127.0.0.1 --port 5173
```

Set the API target to the port on which the companion service actually starts. The frontend calls `/api`; provider keys and data storage belong to service configuration and are not included in this frontend snapshot. A local running frontend alone does not establish backend/model readiness.

For the current shared MiniMax-M3 relay, follow [MiniMax relay setup, testing and development handoff](docs/minimax-relay-handoff.md). Run the companion backend with `h2,minimax-relay`, set `CONSENSE_API_TARGET` to that local backend, and keep the independent relay token in backend/client configuration. The frontend does not store a token. The handoff also describes using MiniMax for real-model regression tests and code analysis/modification, followed by independent validation.

```powershell
npm run typecheck
npm run build
```

## Portable checks

The authored control/model fixtures and test helpers are included. Run the frontend's CommonJS seams from this directory:

```powershell
Get-ChildItem -LiteralPath ./tests -Filter '*.test.cjs' | ForEach-Object {
  node $_.FullName
  if ($LASTEXITCODE -ne 0) { throw "Failed: $($_.Name)" }
}
node scripts/test-drafting-step3-scope.mjs
node scripts/test-drafting-step3-workspace.mjs
node scripts/test-vetting-view.mjs
node scripts/test-advice-scope-view.mjs
```

`tests/fixtures/template-reading.synthetic.docx` is authored synthetic test data. `tests/fixtures/drafting-step12-baseline.vue` is a byte-for-byte copy of `src/views/DraftingView.vue` at original source revision `aaa0e928`. It exists solely to reproduce the original first/second-step template scope check without importing old Git history; it is not a new business rule or a competition document. The scope script reads this relative fixture instead of running `git show` on an unavailable old commit.

## Upstream integration checks (historical)

For the upstream integration on 2026-10-09, fresh npm ci, typecheck and build passed. CommonJS checks reported 36 passed, zero failed and one skipped actual-template navigation test; all four view/workspace scripts passed. The optional actual-document reading branch and OCR diagnostics replay require excluded external evidence and were not run. These are the upstream integration receipts, not fresh test results for this local working-tree merge.

## External diagnostics fixture and historical records

`scripts/test-inspection-ocr-diagnostics.mjs` is preserved, but it requires `tests/fixtures/inspection-ocr-diagnostics.actual.json`. That original local OCR runtime-output fixture is intentionally excluded from this publication. The diagnostics script is therefore **not runnable from this snapshot alone**. Supply a separately authorised exact external fixture to replay the historical test, or define and clearly label a new synthetic/sanitised fixture and corresponding test; do not call a substitute the original observation. The included fixture README documents the original boundary.

Optional generated H800 planning report PDF/HTML assets under `public/reports` are omitted; no application code depends on them. Personal communications, competition source originals, model keys, databases, uploads, installed dependencies and generated runtime outputs are not included.

Documents under `docs/specs/verification` and old tracker entries remain historical records. Their absolute local evidence paths and original repository commit IDs may not resolve in this new snapshot. They are not new validation results for a clone of this repository. Use the portable checks above, and evaluate actual business output with independently provided authorised project documents.
