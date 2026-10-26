# Stage17 complete-original workflow checkpoint

Checkpoint: 2026-10-26 approximately 01:54 Asia/Shanghai. The 07:30 goal remains ACTIVE.

## Browser observations

All business actions below used the existing in-app browser. No command-line ConSense business function or direct business API invocation was used.

The complete 35-original 2b inventory now shows **17 parsed, 13 partially parsed, two failed, one identification-pending and two excluded drawings**. These are processing states, not review or source-quality coverage. All 23 DOCX purposes remain confirmed. The complete APB, APL, 2b Appendix I, APU, AoA, FoT and APZ PDFs also have explicitly confirmed purposes; Appendix H remains pending and the failed Annex still has no established purpose. The latest English browser inventory is `package-browser-inventory-0155.json`.

| Complete original | Browser terminal | Coverage reported by frontend | Elapsed to observed terminal |
| --- | --- | --- | --- |
| APB, 25,419,506 bytes, 61 pages | PARTIAL | 61/61 processed, 61 OCR, no unprocessed pages | 363.942 seconds |
| APL, three pages | PARTIAL | 3/3 processed, three OCR | See English receipt |
| 2b Appendix I, two pages | PARTIAL | 2/2 processed, zero OCR | See English receipt |
| APU, 20 pages | PARTIAL | 20/20 processed, 20 OCR | 248.620 seconds |
| AoA, four pages | PARTIAL | 4/4 processed, four OCR | See English receipt |
| FoT, nine pages | PARTIAL | 9/9 processed, nine OCR | See English receipt |
| APZ, six pages | PARTIAL | 6/6 processed, six OCR | See English receipt |

These elapsed values are upper bounds to the browser observation, not exact parser CPU time. The operator also worked in another tab between observations. OCR text remains explicitly unverified against the originals. Original identity was visually checked in the frontend, including APB page2, APL pages2/3, 2b Appendix I pages1/2 and APU pages2/20. The 2b Appendix I is a tender restriction/workload attachment, separate from the offline 2d review checklist.

In a fresh fullset project-contract Advisory session using the complete SCC and CoC DOCX originals plus the complete GCC PDF, visible Refresh evidence index returned **4,691 chunks** after **570.359 seconds to observed readiness**. The saved GCC parse still covers 73/74 pages. This is a three-original workflow observation, not complete 2b Advisory acceptance.

A new SCC5.305 care-of-works question returned a **cited limited answer** after **588.473 seconds to observed terminal**. Its continuation about outstanding and Enhancement Works is supported, but its narrative wrongly attributes an insurance amendment to SCC5.305 rather than SCC5.311. Speed and complete answer quality have not passed. Citation1 opened the SCC original-source view; saved reload remains pending. A different SCC7.303 structural-warranty question returned **Answer with basis**, with citations, after **422.411 seconds to observed terminal**. Its ten-year period, GCC8.7 certificate start and Employer/Contract Manager identification match the independent source judgment; requested substantive exclusions were omitted and the subclause label is imprecise. These quality details are recorded without delaying the workload fixes. Independent expected answers were recorded from the native SCC original XML before comparing any answer key; 2e was not opened for these checks.

## Runtime and failure boundary

Stage17 Java is PID38624 on18186 with the tested Workflow17 JAR `a22e2e4d9b2e615655686be71d02af79ee0aed73c533e8c23a982cbab4060b14`. Dual06 Vetting is13280/18185 and Advisory is22680/18189. CPU OCR23980/18190 is enabled in this isolated Java candidate. The six-edition SL worker and unrelated services remain preserved. Stage16 was stopped and its closed database/logs were sealed before copying; database SHA256 is `368f0520445c08b74f1f550ab78a091612560cc8c84e9001ebc7301432bfc426`.

The first OER cutover saw a stale TCP listener immediately after the old Advisory process exited. The stopped original state was retained; a separate recovery script copied the closed Advisory state and launched its frozen successor. Both startup failure and recovery receipts remain available.

At01:01:03.756, a simultaneous GET failed with Java heap exhaustion in Hibernate CLOB materialization. The complete APB parse nevertheless reached its terminal state. This is a real runtime failure and is not erased by later successful reads. The JVM has a5GiB heap. Routine Advisory GET status was subsequently confirmed to reconstruct a complete Session while explicit Refresh was constructing another. Live dual06 logs also confirm actual304 unchanged snapshot responses; source-side validation still runs.

## Software candidates and next action

Workflow17 passed324 Java tests with zero failures/errors and five conditional skips, packaged the running JAR, and passed the separately recorded Python conditional-transport and frontend checks. Its initial model evidence is text-first with a96KiB evidence cap; concrete supplementary evidence requests reuse the existing bounded follow-up flow. Failed sources remain explicitly unreviewed instead of failing the complete review preflight.

The isolated Workflow18 successor adds scalar Advisory readiness/status, no whole-vector scroll per question, local PDF-reference ownership correction, and failed/unknown-purpose source recovery. Its first493-case attempt failed23/148 and is preserved. A corrected505-case attempt still failed33/1 and is also preserved. Connected tests exposed a real compatibility issue: reviewed shared-source snapshots can place their producer header beyond the bounded display peek. The narrow correction uses existing positive reviewed scalar pins under exact current snapshot-digest and before/after scalar checks; explicit preparation and retrieved-evidence validation retain full checks. Source-mutation and direct-database mutation rejection tests are preserved. **No Workflow18 package or deployment is accepted yet.** A fresh dedicated builder owns the single Maven window.

A further isolated candidate removes heavy preparability probing and foreign-binding graph loading from fresh/mixed-package status GETs. Explicit Prepare, Refresh and actual retrieved-evidence validation retain full checks. A frontend candidate also prevents an early terminal-progress poll from reloading the source list before the confirmation response, which could otherwise create a false stale-source rejection.

Next: finish the connected regressions, package one coherent successor, seal only terminal owned runtime state, deploy the tested candidate, verify saved answers and reduced status/retrieval cost through the frontend, complete the remaining full originals, and run the full selected2b Vetting/report workflow. Reading order, complex tables and answer-detail improvements remain after workflow and speed.

English receipts and screenshots are under `.local/sl-text-clause-quality-20261024/goal26-workflow17-browser/`. Original bytes, native logs and all failed attempts remain authoritative. Shared main advanced through unrelated Drafting work during this checkpoint; this OER candidate is not yet a synchronized Git delivery.
