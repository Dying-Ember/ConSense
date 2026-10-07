# Drafting review layout switch tickets

Tracker: local Markdown, as documented in `docs/agents/issue-tracker.md`. Spec: `drafting-review-layout-switch.md`.

| ID | Work | Blocked by | Status |
| --- | --- | --- | --- |
| LAYOUT-01 | Implement shared A/B review layouts, focus navigation, filtering and project view restoration using rendered-control TDD | none | done |
| LAYOUT-02 | Independently merge and verify focused tests, existing drafting regression suites, typecheck and production build | LAYOUT-01 | done |
| LAYOUT-03 | Independent Standards and Spec reviews, fix actionable issues, in-app browser QA and close with evidence | LAYOUT-02 | done |

Evidence directory: `C:\Coding\ConSense\output\drafting-a-implementation-20261005\review-layout-switch-20261006`.

No public PR or backend changes are required. Keep the original prototype and unrelated modules untouched.

## LAYOUT-01 implementation evidence

Implemented on `implement/drafting-review-layout-switch-20261006`, based on integration tip `1edce29aeb150ec19fd5d9292dc8ea98e389aaa7`. The shared editor now renders either the existing A list or one current catalogue group in B; no catalogue, business rule, API, template, extraction or corpus changes were made.

Rendered-control TDD evidence in the directory above:

- `layout01-01-red.log` / `layout01-01-green.log`: accessible switch, grouped sub-inputs, unsaved scalar/candidate retention and navigation with zero writes/model calls.
- `layout01-02-red.log` / `layout01-02-green.log`: stable filter selection/numbering, missing and conditional controls, retained structured values, empty recovery, template/unresolved return and stale-project focus rejection.
- `layout01-03-red.log` / `layout01-03-green.log`: delayed language remount restoration, project-specific layout/group/dirty-value caches and ordinary save refresh; only an explicit save writes.
- `layout01-native-source.log`: real DOCX source markers select the correct B group before input location and clear incompatible filters; source selection, readable card and unsaved values remain coordinated.
- `layout01-focused.log`: new layout tests plus drafting state, template view/selection, Bill controls and extraction view regressions passed.
- `layout01-typecheck.log` and `layout01-build.log`: `vue-tsc --noEmit` and production Vite build passed.

The B index scrolls within a bounded height. The editor keeps a zero minimum width; an input-stack container query puts the index above it when the available width is below 800 px, including when a source reader reserves screen space. Independent merge, review and live-browser acceptance remain LAYOUT-02/03 work.
## LAYOUT-02 independent integration verification

LAYOUT-01 implementation is integrated at `398a8b0bfbb104f0ca4316e724758ee4561750de`. The clean worker contained integration tip `1edce29aeb150ec19fd5d9292dc8ea98e389aaa7`; the clean live integration branch `feature/drafting-review-layout-switch-20261006` fast-forwarded to the exact implementation commit.

Independent verification at that implementation pin passed all 19 `tests/drafting-*.test.cjs` files (19 passed, 0 failed, 0 skipped), including the actual uploaded-template regression with saved source evidence enabled. `vue-tsc --noEmit` and the production Vite build also passed, using bundled Node v24.19.0. Logs and source scope are recorded in [LAYOUT-02 verification](verification/drafting-review-layout-switch.md) and the configured external evidence directory.

LAYOUT-02 is done. Independent Standards/Spec review, any review corrections and live-browser acceptance remain LAYOUT-03; this verification does not claim that those gates are complete.

## LAYOUT-03 independent review and live acceptance

Done against implementation `398a8b0bfbb104f0ca4316e724758ee4561750de`, with baseline `6a6b79a8389b839794419e2b88382fc15e380708`. Independent Standards review reported 0 documented violations and 0 actionable smell findings; independent Spec review reported 0 actionable findings. Reports are retained separately as `standards-review.md` and `spec-review.md` in the evidence directory.

The existing in-app browser at port 15240 received the implementation through Vite HMR. Live A/B switching, next-question navigation, catalogue numbering under filtering, empty-filter reset, missing fields, an actual uploaded NTT marker returning to B's BQ group, input focus, three locales and a 1000px viewport were verified. Switching back to A showed all 21 groups. All 175 observable controls and all 69 applicable field states matched the QA-start snapshot exactly; the four deliberately withheld inputs remained empty. The user adopted two inputs during implementation, before this QA baseline, so the earlier historical snapshot is not used as an unchanged-state assertion.

No project value was filled, saved or adopted during browser QA. No extraction, generation, upload or LLM call was performed. The browser ended in B with the original locale and viewport restored, and no captured console errors. Screenshot: `browser-b-final.jpg`. Detailed evidence and bounded acceptance: [verification](verification/drafting-review-layout-switch.md).
