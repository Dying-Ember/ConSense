# Drafting layout switch: LAYOUT-02 verification

Verified on 2026-10-06 by an agent independent of the implementation author. Spec: [drafting-review-layout-switch.md](../drafting-review-layout-switch.md). Tickets: [drafting-review-layout-switch-tickets.md](../drafting-review-layout-switch-tickets.md).

Implementation and integrated code pin: `398a8b0bfbb104f0ca4316e724758ee4561750de`. Worker: `C:\Coding\ConSense-isolated\drafting-focus-switch-20261006\ai_consense_web`, branch `implement/drafting-review-layout-switch-20261006`. Integration: `C:\Coding\ConSense-isolated\drafting-implementation-20261005\ai_consense_web`, branch `feature/drafting-review-layout-switch-20261006`.

Both checkouts were clean before integration. The worker pin contained integration tip `1edce29aeb150ec19fd5d9292dc8ea98e389aaa7`, and `git merge --ff-only 398a8b0bfbb104f0ca4316e724758ee4561750de` succeeded. The integration checkout remained clean after the merge and the checks.

## Commands and outcomes

All commands ran in the integration checkout using `C:\Users\Murph\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe` (v24.19.0).

| Gate | Command / configuration | Outcome |
| --- | --- | --- |
| Drafting frontend regressions | `node --test` with all 19 sorted files matching `tests/drafting-*.test.cjs` | 19 file tests passed; 0 failed; 0 skipped; exit 0 |
| Actual uploaded-template navigation | `DRAFTING_ACTUAL_SOURCE_EVIDENCE` points to the saved run below, within the same full suite | NTT 445 / SCT 1536 / SCC 1339 exact locations; all three 0 missing, 0 approximate |
| Typecheck | `node node_modules/vue-tsc/bin/vue-tsc.js --noEmit` | exit 0 |
| Production build | `node node_modules/vite/bin/vite.js build` after passing typecheck | exit 0 |

Saved actual-source evidence: `C:\Coding\ConSense\output\drafting-a-implementation-20261005\a21-template-preview-20261006\acceptance\runs\20261006T084631125248Z-source-86b42201`. The regression reads the existing plan/catalogue and NTT/SCT/SCC source-reading JSON only; it makes no live request. Its output confirms the original source scopes and guards against incompatible quotes/replacement editions using ordinal guesses.

Evidence directory: `C:\Coding\ConSense\output\drafting-a-implementation-20261005\review-layout-switch-20261006`:

- `layout02-merge.log`
- `layout02-full-drafting-suite.log`
- `layout02-typecheck.log`
- `layout02-build.log`
- `layout02-verification.json`

The focused rendered suite is included in the full result and exercises grouped A/B editing, unsaved scalar/list retention, missing/conditional controls, catalogue numbering/filter fallback/empty recovery, source/unresolved group selection, stale project focus, delayed language restoration, project view caches and ordinary explicit save. Existing drafting regressions also run. These mechanical results are bounded to the integrated frontend source and saved source fixture; they do not replace independent Spec/Standards review or live-browser layout acceptance.

No service, LLM, project API, correspondence corpus or source-template data was changed by this merger/verification task. No public PR was created. LAYOUT-03 remains pending with the root agent.

## LAYOUT-03 final acceptance

The pending state above records the independent merger's stage; final acceptance is now complete. Standards and Spec reviewers independently reported 0 actionable findings on `6a6b79a...398a8b0`; see the separately preserved reports and [review summary](C:/Coding/ConSense/output/drafting-a-implementation-20261005/review-layout-switch-20261006/review-summary.md).

The root verified the existing in-app browser at `http://127.0.0.1:15240/#/drafting`, project `proj_muwqcyhbbvtk`. A rendered 21 groups; B rendered one current business group and all of its applicable sub-inputs. First/next navigation, direct question selection, filtered stable catalogue numbering and empty reset passed. An actual NTT `electronicTendering` source marker opened B group `method`, focused `input-electronicTendering` and preserved the selected layout. English and traditional Chinese retained that same question; simplified Chinese was restored. At a temporary 1000px viewport the index stacked above the editor with bounded scrolling, full control width and available navigation. The viewport was restored.

Browser QA did not edit, save, adopt, upload, extract or generate. Comparing A after all interactions with the immediate QA-start snapshot found identical values for all 175 controls and identical states for all 69 applicable fields. All four deliberately withheld fields remained empty. Earlier historical snapshots predate two user adoptions during implementation and are retained separately; no assertion relies on those older states matching. Captured browser console errors: 0.

Evidence: `browser-qa-start.json`, `browser-qa-end.json`, `browser-b-contract-type.jpg`, `browser-b-narrow.jpg`, and [final B screenshot](C:/Coding/ConSense/output/drafting-a-implementation-20261005/review-layout-switch-20261006/browser-b-final.jpg). The original tab remains open in B. All three local tickets are done. These results verify the new presentation/navigation; they do not claim new extraction accuracy or document-generation acceptance.

Temporary worktree cleanup was rejected by automatic approval review when removing its verified dependency junction. The isolated worker checkout is retained; live dependencies were not touched. The exact boundary and skill cleanup requirement are recorded in [cleanup outcome](C:/Coding/ConSense/output/drafting-a-implementation-20261005/review-layout-switch-20261006/worktree-cleanup-blocked.md).
