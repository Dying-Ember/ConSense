# BG-06 readable graph layout verification

User correction on 2026-10-07: oversized nodes and narrow gutters crowd graph connections. Baseline `6f4c13e0b90095bf00f7f2d238678a1102abc9ad`; refined specification `bceacc547a17f71dcdcdfb19f6ddb26722b51766`. Owner commit `413288edb74ec34cbc87384fc0cbb362542c9cd5`; verified integration product `5d9c9f81726f412e12cdeec9b6be9af9ae83202b`. Later closure commits change only documentation. Service remains clean and unchanged at `67ceb7081d42204aa17b12b149f52081e348da71`.

Evidence root: `C:/Coding/ConSense/output/drafting-business-graph-layout-20261007`.

## Reproduction and correction

Actual 1544px in-app browser measurements reproduced approximately 317px input cards and 300px action cards, with only 40/62px gutters. The all-groups/all-files Bill chain had 42 selected relations; long paths reused centre attachment points and a narrow control corridor. Before observations remain in `browser-before.json`, `browser-before.png` and `browser-pool-before.json`.

Native SVG dimensions prevent automatic card enlargement. Compact cards keep readable 12px labels, full accessible titles and inspector values. Wider gutters and deterministic per-edge ports/lanes separate fan-out/fan-in; reservations use the complete eligible relation set, so selection or appended context cannot reroute the existing pool. Background lines are subdued and selected relations render above them. Structural nesting and dotted prerequisite relations remain distinct. Long catalogue-only routes bypass the action column through a reserved band. Action-aware canvas height preserves the last card and bottom margin.

| Actual desktop measure | Before | After |
|---|---:|---:|
| Input card width | 316.82px | 214px |
| Action card width | 300.15px | 210px |
| Input to action gutter | 40.50px | 192px |
| Action to file gutter | 61.94px | 254px |
| Main label font | Enlarged with SVG | 12px native size |

All relationship identities remain present; layout does not redefine parentage, applicability, catalogue fields or business treatments. This correction adds no backend change or paid dependency.

## Acceptance

- Four retained RED/GREEN families cover compact geometry, many-to-many routing, long potential bypass and action-column bottom clearance. Eight owner gates pass, including fourteen rendered graph slices, model/navigation/parent integration, step scope, workspace, typecheck and build. `implementer-gates.json` records logs.
- Fixed integration regression passes 35 CJS suites plus scope, workspace, typecheck and build: 39 checks, zero failures. `final-verification/manifest.json` pins source and log hashes.
- Independent Spec and Standards reviews have no actionable findings: `spec-review.md`, `standards-review.md`. Independent actual DTO reproduction preserves 373 relations: 20 structural, 217 input dependencies, 26 prerequisites and 110 destinations. All 42 selected Bill relations remain; its ten dependency paths have ten distinct source ports, with six SCT/four SCC receiving ports. Independent samples of 353 nonstructural routes against unrelated node interiors find no crossings. Current actual data has no eligible potential routes; six clearly synthetic potential routes separately verify bypass clearance below headings and above the first action. `spec-dense-independent-receipt.json` distinguishes both scopes.
- Actual current-project browser shows 79 inputs, 20 child fields, 110 actions and all 373 edges before/after. The selected Bill chain remains 42 edges with ten distinct Bill outgoing starts. Actual measured gutters are 192/254px and the main label font is 12px. `browser-after.json`, `browser-after-bill-chain.png`, `browser-final.png`; an additional explicit Bill-group view is `browser-bills-group.png`. This group view is not used to substitute for all-pool acceptance.
- Clearing selection and clicking a previously grey contract identity node keep all current node SVG transforms and all existing paths byte-identical, without resetting filters. `browser-selection-stability.json`.
- Actual 650×850 responsive check has no page overflow and retains 1080px internal diagram scrolling, with the inspector accessible. Viewport override is reset and desktop restoration is subsequently observed at 1544px. `browser-narrow.json`, `browser-narrow.png`.
- Actual Bill description child returns to step 2 and focuses `field-billNos`. The confirmed contractType compound parent still contains foundation inclusion and the 39-month threshold as subvariables. Foundation action navigation returns to step 3 original NTT and exactly focuses on-demand-bond binding `CS6f9e29991d10c93b8782c05784b8`, without generation. `browser-input-navigation.json`, `browser-source-navigation-completed.json`; the earlier loading observation remains separately in `browser-source-navigation.json`.
- Fresh before/after seven-resource GET captures are byte-identical for catalogue, variables, templates, documents, inputs, progress and plan. `preservation-before.json`, `preservation-after.json`. Browser QA performs no save, adopt, extraction, generation or body edit.

Scope is graph presentation/layout only. Steps 1/2, extraction/harness, business conditions, adoption, generation/export and prior acceptance limitations remain unchanged. Three locales and readonly/project/dirty/busy guards pass existing rendered integration checks. No new model-quality or exported Word/PDF layout acceptance is claimed. Dense all-pool graphs may still contain legitimate crossing relationships; the correction separates attachment points, expands clear routing space and avoids unrelated node interiors rather than deleting relationships. Prior retained-worktree housekeeping is not retried.
