# Drafting A task graph

Integration branch: `feature/drafting-a-business-rules-20261005` in both repositories. The frontend and service are separate Git repositories, so each has its own commit lineage on the same named integration branch.

Spec: `drafting-a-implementation.md`. Local tracker: this task graph and the linked acceptance records. No remote issue tracker or PR closing policy has been configured. Remote tickets have not been created or closed.

## Existing implementation at workflow handover

The implementation was already substantially written when the user invoked `implement-spec`. Preserve it as an integration checkpoint. Earlier tests include both test-first and implementation-followed-by-acceptance work; do not represent that history as universal TDD. Every newly discovered defect must use a failing public-behavior regression before the minimal fix, where a behavioral test is appropriate.

## Tickets and blocking relationships

| Ticket | Result required | Spec stories | Blocks / depends on | Status |
|---|---|---|---|---|
| DRAFT-A-00 | Preserve isolated existing implementation and spec checkpoint | Isolation decision | No dependencies; enables01/02/03 | Closed: web e92c996 / service ba58e0b |
| DRAFT-A-01 | Reconcile catalog, applicability, derived results and precise source edits against actual competition templates | 3–8,20–38,40–51,55 | Depends00; blocks04 | Closed: verification/a01.md;32 tests passed; merger b0157f4 |
| DRAFT-A-02 | Reconcile human adoption, evidence-linked suggestions, persistence, target source revisions, shared snapshot and stale API guards | 12–19,49–54,56 | Depends00; uses implemented01 contracts; blocks04 | Closed: verification/a02.md;25 executed passed/1 legacy skip; merger09aa962 |
| DRAFT-A-03 | Reconcile actual prototype A UI, tri-language editing, retained conditional values, Bills distribution, target edits and export controls | 1–11,18–19,22–24,31–34,49–50,54,56 | Depends00; uses implemented01/02 contracts; blocks04 | Closed: web verification/a03.md; renderer merge1acac56; actual IAB page/flip/document-switch green |
| DRAFT-A-04 | Integrate01/02/03, run full regression, real7B trace and actual browser/source/binary acceptance | All stories at agreed acceptance seams | Depends01/02/03; blocks05 | Closed: verification/a04.md; full915/0fail/0error/1skip; actual source/model/binary/stale API and PDF pixels passed |
| DRAFT-A-05 | Review integration diff independently for standards and spec; fix every actionable finding through one implementer | Original stories1–56 | Depends04; blocks08 | Closed: verification/a05.md; all8 findings fixed; service merge7d46aff / web mergeeeceb0b;68 executed service tests and web/typecheck/build/3locale SSR passed |
| DRAFT-A-06 | Record closure evidence, show the running actual app, preserve source exceptions and remove only temporary implementer worktrees | Delivery | Depends05/07/08/09 | Delivery prepared: verification/a06.md; nine implementer worktrees cleaned; final closure awaits A09 native IAB download check |
| DRAFT-A-07 | Implement verified DOCX package/run/cell editing from original source, preserving untouched formats and package parts | 57/58/61 | Depends04; blocks08 | Closed: verification/a07.md;21 executed tests passed/0 skips; independent merger d263848 |
| DRAFT-A-08C | Convert exact DOCX bytes through an explicit isolated document-layout engine with bounded failures, renderer identity and field/font capability reporting | 59/61/63 | Depends04; blocks08 | Closed: verification/a08c.md;14 executed tests passed/0 skips; independent merger cf866444 |
| DRAFT-A-08 | Freeze generated template-faithful artifacts, integrate generation/body editing/Word export and PDF conversion from the same bytes | 57–63 | Depends05/07/08C; blocks09 | Closed: verification/a08.md;109 focused tests/0fail/0error/2 optional skips;7 actual artifact scenarios execute; independent service merger facd579 / web afe36e7 |
| DRAFT-A-09 | Verify all actual template/final page patterns and final artifacts; rerun regression/browser and both code-review axes on extended spec | 57–64 plus regression1–56 | Depends08; blocks06 | Implementation verified: verification/a09.md; full05 976/0fail/0error/1skip; both code-review axes 0 remaining findings; 127 final pages accepted; native IAB download completion remains unaccepted |
| DRAFT-A-10 | Accept complete model JSON with native structured candidate values through the existing canonical input validation; preserve source support, unknowns and manual adoption | 12/14/18/19; defect reported 2026-10-06 | Depends02/04; regression follow-up | Closed: verification/a10.md; independent merger9af7364;30 executed regression passes/0fail/0error/1 legacy skip; both review axes0 findings; actual IAB/7B eight sources/nine valid responses/0 format retries; candidates remain unadopted |
| DRAFT-A-11 | Repair dedicated extraction harness: native compact protocol, complete item/attempt diagnostics, append-only run history, bounded repair and literal identifier grounding | drafting-extraction-harness.md1–9/14 | Depends10; blocks13 | Closed: verification/a11.md and a13.md; independent service merger0234ee2; expanded12 HTTP cases pass;76 unique bounded relevant passes/1 legacy skip retained; both review axes0 remaining findings |
| DRAFT-A-12 | Render tri-language per-field and run diagnostics; distinguish rejected/unanswered/no-candidate/disagreeing suggestions while preserving manual editing | drafting-extraction-harness.md10–13 | Contract agreed; live acceptance depends11; blocks13 | Closed: verification/a12.md and a13.md; independent web merger8271cee;8 frontend test files/typecheck/build pass; actual three-language field/process diagnostics and persisted7B report observed |
| DRAFT-A-13 | Integrate/review harness, evaluate retained source-grounded cases, run actual current 7B and browser, preserve artifacts | drafting-extraction-harness.md14–17 | Depends11/12 | Closed for harness delivery: verification/a13.md; exact8-source/9-part7B run,11 attempts/all109 decisions, readonly persisted history,6 prior export bytes and source hashes preserved,2 clean merged worktrees removed. Bounded semantic quality remains false; A09/A06 downloads remain pending |

01/02/03 are the initial verification frontier because their required code contracts already exist in00. This is a reconciliation of existing work, not a fabricated history of tickets completed under this skill. Implementers work from the integration checkpoint in their own worktree/branch, read the `tdd` skill, merge the integration tip before completion, and commit the evidence or any necessary correction. A separate merger brings each result into integration.

## Acceptance seams and boundaries

Use real project-scoped API persistence/generation/binary export; actual standard DOCX through the parser and source rule interface; frontend observable state and browser controls. These are the seams already recorded in the spec and existing tests. Do not test private implementation details or claim model doubles establish semantic accuracy.

Known source questions are implemented as marked, editable target-level decisions. Their unresolved business answer is not an incomplete UI or an invented automatic rule. Preserve the precise scope listed in the spec. Do not expand the21 groups/79 visible inputs or change vetting/advice.

Keep independent red/green logs for newly found defects, full-run failures, final regression report, live model trace, browser observations and real exports. Close local tickets only after their result and blocking dependencies are verified; report remote closure as unavailable rather than claiming it occurred.
