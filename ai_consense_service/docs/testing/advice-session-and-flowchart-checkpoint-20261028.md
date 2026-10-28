# Advice session recovery and compact flowchart checkpoint

Date: 2026-10-28 (Asia/Shanghai).

## Frontend changes and observed acceptance

The Advice view stores only the selected conversation ID in browser session storage, separately for each project. The saved conversation is checked against the current project's session list before restoration; its source scope and evidence basis come from the saved server session. Stale IDs, unavailable browser storage, a Vetting handoff, a manual scope change and late responses cannot silently replace the selected conversation.

Changing reference scope opens a separate conversation selection. A visible Return to previous conversation action restores the prior conversation and its unsent question while the view remains mounted. The old conversation is not supplied to the new scope.

Clear chat now opens the existing in-page application modal. It names the exact conversation and warns that confirmed deletion is irreversible. Only the separate Clear saved messages button invokes deletion. Cancel, closing the modal, or changing project/session makes no deletion request. New-scope selection and session creation cannot clear hidden legacy messages. The confirmation is bound to the project, session and view/scope epochs.

Embedded-browser observations on the real full 2b project:

- The saved 2026-10-28 09:01:52 reference-only conversation initially restored automatically after refresh, with its two questions, two answers and original reference scope.
- Changing scope cleared the displayed old conversation; returning restored it and the unsent follow-up draft. Clear chat was disabled in new-scope selection.
- A first native window.confirm implementation failed browser acceptance: the click timed out, no dialog was subsequently available, and that test conversation then displayed zero messages. The exact confirmation/deletion sequence was not captured. The record has not been restored. The local incident note preserves the observed questions, answer limitations and exact second-answer paragraph. This failure is not a successful cancel test.
- After replacing the native dialog with the in-page modal, the 2026-10-27 19:35:38 saved conversation was checked. Clicking Clear chat opened the named modal; Cancel preserved the displayed chat text exactly. Refresh restored that same text and selected session. No destructive confirmation was clicked for this corrected live test.

The final frontend gate passed 10 targeted control tests and Vue type checking. The earlier broader control gate ran 61 tests: 59 passed and two existing Vetting late-save/status tests failed, matching the separately preserved baseline failures. Those failures remain open.

Unsent question drafts are not persisted across a browser refresh. Confirmed deletion still has no normal undo flow. Saved-conversation recovery does not claim recovery of an interrupted model generation.

## Compact diagram implementation

The Qwen drawing path accepts an application-level draw_flowchart object with bounded nodes, edges, known source aliases and optional targeted evidence requests. The application validates graph shape and safe labels, creates Mermaid source and saves it through ordinary Advice history. Existing Qwen prepared-request admission, usage preflight, generation records, cancellation and current-source guards remain in place. This is not provider-native tool_calls.

The displayed answer is labeled Draft flowchart - not independently verified. It remains ungrounded and does not imply source applicability, complete evidence or correct legal steps. The current frontend already renders fenced Mermaid content even for a limited/draft answer. Normal and escaped generated syntax parsed with the installed Mermaid library.

Software evidence:

- Final drawing helper, Qwen transport and public ask/save gate: 29 passed, no skips.
- Wider relevant gate: 112 executed, 106 passed and six existing conditional skips; no failures or errors.
- A broader exploratory gate found nine failures/errors in existing Responses streaming and prose repair tests. Recompiling the unchanged baseline reproduced the same nine residuals. These are retained separately.

The five Advice implementation/test paths are adopted in the main working directory. A combined Java package also includes the earlier Vetting change that gives all review directions a first pass before processing requested supplements. The prior 129-test Vetting software gate remains software evidence only.

## Real diagram and conversation acceptance

The user-operated candidate07 replacement succeeded at 09:41:58. The embedded browser submitted the complete Q1.6 Clause 17.1 flowchart question in a new fixed-standard interpretation session at 09:43:25. The real Qwen answer displayed a 28-node SVG within an approximately 100-second observation interval; the Java completion was 09:44:58.208 with ten evidence hits. A follow-up asked for a shorter chart while retaining both initial routes and the Contract Manager response deadline. It displayed a 20-node SVG within approximately 109 seconds; completion was 09:51:25.033 with ten evidence hits. Both answers remain explicitly labeled draft and not independently verified.

Refreshing restored both saved questions and diagrams in the same selected session. Changing to project-contract advice and creating a session at 09:53:10 showed no diagrams from the reference-only conversation. These are real frontend conversation, drawing, persistence and scope-separation checks, not legal-accuracy acceptance.

Visual inspection found two presentation defects. Mermaid with htmlLabels disabled left literal character entities in labels; normalizing only sanitized SVG text nodes corrected them without changing attributes or creating active markup. An obvious Enlarge flowchart / Fit to panel toggle now preserves one rendered SVG. The enlarged diagram has its own bounded scrolling area; a fixed grid column and shrinkable message prevent it from widening the chat. The actual enlarged diagram measured 1,220 pixels of scroll content in an 818-pixel viewport, while the chat history client and scroll widths were both 1,033 pixels. Fourteen focused renderer/component checks and Vue type checking passed; the actual browser confirmed clean ampersands and containment.

After the candidate08 restart, the same two saved diagrams restored again. The answer header now retains only the recorded model name; internal profile/provider identifiers and the attestation sentence are omitted from the normal conversation display. Stored provenance is unchanged. Normal-answer and draft-diagram view assertions and type checking passed. The broader UI run passed 62 of 64 cases; the same two baseline Vetting stale-report failures remain recorded. The draft and insufficient-evidence warnings were retained.

## Current deployment and remaining acceptance

At the user's explicit request to run the prepared lifecycle operation directly, the agent executed the guarded candidate08 replacement successfully. Java PID 36140 started at 10:04:51 on port 18186. The JAR SHA-256 is 9CE0763B7C53201E6FDFF1F0759B761C0800BFF1C30B7760BE821B7C3A6B5002. Existing database and indexes were retained, native services were not restarted, and independent Drafting was not stopped.

Candidate08 changes only the finite Vetting defaults relative to candidate07: 54 answers, 108 preflights and 162 total calls, sufficient for 27 initial directions with one repair each. Explicit overrides, Advice budgets and the 24-minute scheduling deadline are unchanged. The admission regression failed against the earlier cap and passed after the repair; all 80 focused cases passed. Eleven workflow cases initially failed at H2 trigger setup under Maven's no-fork classloader and passed in the corrected isolated fork. Those original failures remain recorded.

Full 2b Vetting under the new scheduler and budget is undergoing frontend acceptance as recorded below. The preceding completed review remains two of 33 documents reached and all 27 directions requiring follow-up. Increasing the finite call budget does not prove the directions can finish within the deadline. No complete review or diagram legal-accuracy acceptance is claimed.

## Candidate08 completed full-scope review: acceptance failed

The browser searched All selected project documents for Envelope 1, Envelope 2 and L10Pro. It displayed 33 of 33 documents available and attributed passages from NTT, SCT and other selected sources. The search's first complete observation was within 177.8 seconds; this is an observation bound, not exact service latency. Its actual OER request took 49.552 seconds, including 18.392 seconds in metadata processing and approximately 17.6 seconds after the ranking completion event.

One complete 2b review was submitted through Run vetting at approximately 10:08:27. Java compact source preparation took 24.533 seconds. All 24,831 source origins were read across 25 batches by 10:10:13; scan finalization was 10:10:35. The first directional project retrieval ran from 10:12:51 to 10:13:39 and took 48.397 seconds. The first model call ran from 10:14:32 to 10:14:56 and returned nonempty structured records. The UI subsequently remained in quotation/source verification and did not advance to the second direction until the 10:18:26 observation. The second model call ran from 10:20:54 to 10:22:10; two records were subsequently rejected for unsupported source-clause attribution. The review is still in progress at this checkpoint; no terminal coverage or complete-review acceptance is recorded.

Two concrete reduction candidates are being tested separately. Selected evidence pointers currently reload full normal-source fields repeatedly despite a verified local source stamp. The native canonical digest also repeats Python per-scalar encoding over the complete corpus. Neither proposed repair is accepted as a runtime speed improvement until a replacement passes the same frontend workflow.

The first terminal browser observation was at 10:40:16, within 1,909.754 seconds (31.83 minutes) of Run. The visible ledger showed seven of 27 directions submitted, 20 not submitted because the scheduling time budget was exhausted, no accepted semantic findings and two retained rule leads. Four submitted directions returned and passed their stated checks; three had some evidence rejected. All 27 directions still required follow-up or review. The displayed document reach remained two of 33. This fails both complete-review coverage and the agreed 30-minute elapsed target. The retained ledger is `candidate08/full-review-terminal-ledger.txt`. A lease-renewal transaction warning was logged at 10:38:00; it is not established as the cause of the explicitly recorded scheduling omissions.

## Candidate09 runtime replacement

The agent executed both guarded replacements after the preceding review ended. Vetting OER now runs as listener 44548 / launcher 39204 on port 18185 from candidate09-native, using the original index state and an exact predecessor adapter. Java now runs as PID 15048 on port 18186 and completed startup at 10:45:07. Its JAR SHA-256 is 90EE36762D95E5B21A12386FC6F4FDFF98ED7244225942728A2847A9F1E86136. Advice OER and independent Drafting were not replaced by these operations. Current deployment receipts are `runtime11-oer/launch.json` and `runtime12-java/launch.json`.

Java reuses the verified local source stamp in five existing pointer/source-context freshness checks. Supported text-first Vetting quotations now reuse the existing selected-normal-text verification path and retain registered original parts/positions without eagerly loading the complete Word chapter graph. The production preparation seam and real SQL/native boundaries passed 146 tests, with zero failures, errors or skips. Successful quotation binding used zero complete source/binding ORM reads; changed text, offsets, revisions, original bytes, CAS bytes and stored snapshot bytes still failed validation. Unsupported paths retain their original fallback.

The native encoder change uses the standard JSON C encoder one parent/window at a time while retaining all entry/exit hashes and mutation checks. The complete retained 561,204,858-byte index diagnostic found identical persisted canonical digests: 24,831 parents took 8.451 seconds before and 1.165 seconds after; 49,674 windows took 0.622 and 0.606 seconds, while the largest window encoding buffer fell from 89,150,388 to 5,037 bytes. This is an isolated full-artifact diagnostic, not frontend speed acceptance. All 100 native checks and three actual-index signature checks passed.

The first new complete 33-document browser search is in progress. Its Java compact preparation took 12.176 seconds. No new complete-review result is yet available for candidate09.

Local evidence is under the ignored `.local/vetting-query-budget-20261027/candidate07` directory: `advice-recovery-modal-final.log`, `advice-recovery-modal-typecheck.log`, `advice-clear-confirmation.png`, `advice-restored-conversation.png`, `observed-q16-session-incident.md`, `flowchart-renderer-boundary.log`, `flowchart-final-regression.log` and `flowchart-baseline-recompiled-residuals.log`. The complete visible Q1.6 conversation text was recovered from the actual pre-incident browser tool output as `q16-full-observed-browser-transcript.txt`; this preserves displayed text, not original database message IDs or complete source metadata. Original files, model payloads and operational evidence are not part of source publication.
