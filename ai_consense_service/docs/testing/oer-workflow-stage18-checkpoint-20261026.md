# Stage18 complete-package workflow checkpoint

Checkpoint: 2026-10-26 approximately 02:44 Asia/Shanghai. The 07:30 goal remains ACTIVE.

## Complete-original frontend progress

All business actions used actual in-app browser interaction. No command-line ConSense business function or direct business API request was used.

The complete 35-original 2b inventory now has **17 parsed, 16 partially parsed, zero failed, zero identification-pending and two excluded drawings**. All 33 non-drawing originals have confirmed purposes and usable processing states. These states establish workflow availability, not source-quality or review acceptance.

| Complete original | Browser result | Reported page coverage | Seconds to observed terminal |
| --- | --- | --- | --- |
| GCT | Recovered from failure to PARTIAL | 2/2 processed, zero OCR, no unprocessed pages | 29.066 |
| Annex 1 to 6 | Recovered from failure and unknown purpose to PARTIAL | 47/47 processed, zero OCR, no unprocessed pages | 143.518 |
| Appendix H | Confirmed Tender documents and reached PARTIAL | 55/55 processed, zero OCR, no unprocessed pages | 77.113 |

These are observation upper bounds, including time spent inspecting another tab. Existing observations were not forcibly discarded. They are not cold OCR benchmarks. The GCT/Annex local-reference failure and failed/unknown-purpose recovery were verified through the frontend. Earlier Stage17 complete-file results remain in the preceding checkpoint.

Two saved Advisory answers, for SCC5.305 and SCC7.303, survived successor reload. The saved warranty citation opened `10 - SCC.docx` and visibly highlighted the ten-year warranty/certificate-of-completion paragraph. Existing answer-quality gaps remain recorded. One DOM inspection timed out for three seconds during original rendering; the next observation showed the source loaded. Visible Refresh subsequently reached READY for the complete three-original scope, with 4,691 chunks after **353.047 seconds to observed readiness**. This bound is not an exact backend duration or a measured speedup ratio.

A different question about HOMES delivery after 5:45 p.m., SCC22.304(5), returned **Answer with basis** and citation[1] after **230.747 seconds to observed terminal**. Its next eligible day conclusion matches the independent native XML source judgment recorded before submission; no answer key was used. The citation opened the SCC original and visibly highlighted the correct paragraph. The new exchange also survived an actual reload and saved-session selection. The last separately observed phase was Retrieving contract evidence at approximately 111 seconds; model-phase duration was not separately observed. This establishes one cited three-original answer, not whole-2b Advisory or acceptable speed.

## Full 2b review failure

A new Run vetting action selected the complete 2b package with 33 non-drawing originals. It reached a terminal **FAILED, 0/19 execution steps and 0/33 documents reviewed**, after **308.856 seconds to observed terminal**. No report or export passed.

The actual worker stderr identifies `OutOfMemoryError: Java heap space` at `CanonicalEvidenceBackend.Reply.json`, while decoding the complete normal-observed response into a String. The call chain is `normalObserved` -> `prepareNormalObserved` -> `VettingService.executeRun`. This differs from the preceding Stage17 concurrent status-GET CLOB failure and must remain a separate failure.

The exact passive producer capture contains a **141,657,394-byte request** and **294,538,129-byte response**. The current decoder allocates a complete UTF-16 buffer and then a String; the caller subsequently extracts a complete projection String and parses the complete receipt. The passive response contains 294,105,019 UTF-16 units with non-Latin text: the final String backing alone can require 588,210,038 bytes, separately from the full decoder workspace. An authored 64 MiB/224 MiB-heap test reproduced the same `Reply.json` failure. A bounded candidate removes these redundant copies, extracts one projection, and streams request bytes while preserving source/seal/UTF-8 checks. Its nine focused cases pass, and an independent read-only review found no blocking regression; actual 294 MB passive validation, connected regression, packaging and browser retry remain pending. Remaining body-buffer and contract-validation copies are explicitly retained as limitations. No heap increase or source-quality gate change is the proposed remedy.

The failed browser receipt/screenshot are under `goal26-workflow18-browser/full2b-review-failed-stage18b.*`. Native logs remain under the live Stage18b directory. JVM thread inspection was unavailable because the installed diagnostic runtime lacks its native attach library; no diagnostic installation or service restart was used to work around it.

## Tested code and runtime

The coherent Workflow18 Java package completed **29 selected suites, 511 cases: 502 successful executions and nine conditional skips, zero failures/errors**, with native Maven test/package exits zero. Five skips require the offline Python producer; four require saved COM11 fixtures. All 1,076 frozen source hashes were rechecked after packaging. JAR SHA-256 is `07152aef288f4905bf707d2ba8938b663669003c8a3046069144af78c5dfbba5`.

The changes remove full Session construction from routine Advisory status, avoid complete-vector checks per question, preserve scalar before/after content-revision rejection, handle reviewed shared sources with late headers, and restore complete-file source retries. The initial Qwen material remains text-first and bounded, with concrete supplementary evidence requests. These improvements have not yet established acceptable complete-package review or question speed.

Stage17 was stopped only after its browser work reached terminal states. Its closed database/logs remain sealed; database SHA-256 is `2007687bba6619ff7a0f4f2a7c971ec3f57456b2426512e227a7d613ef8bfc40`. The first Stage18 startup failed when Windows refused the database open because another program held the file. Post-start database hashing was a plausible launcher race; unique file-handle attribution was not captured. The unchanged failed database and logs remain preserved. Stage18b hashes the database before starting Java and started successfully from a fresh copy.

Current owned acceptance runtime: Java **35956/18186**, Advisory dual07 **46628/18189**, unchanged Vetting dual06 **13280/18185**, and enabled local CPU OCR **23980/18190**. Frontend5187, six-edition SL18183, separate SL18187, H2 owner18082, Drafting18086 and unified8875 were preserved. These are acceptance services, not a claim of formal application deployment.

The exact tested delta was integrated into shared root with hash guards and backups: seven Java production files, ten Java test files, three Python production files and one Python regression test. The new offline availability regression passed six tests, native exit zero, with fake providers and no real business/model calls. The two tested frontend files were adopted narrowly; the previously recorded 41 frontend tests/typecheck remain separate software evidence. A further presentation-only change moves the existing task card intact ahead of the35-file processing history. It passed55 mounted controls tests/typecheck and actual browser FAILED-state visual inspection; RUNNING-state browser verification awaits the next complete run. Unrelated Drafting changes were preserved.

## Next work and limits

Repair the measured normal-observed response allocation/duplication, package one coherent successor, and repeat the complete 2b review through the browser. Continue independent Advisory questions and measure source preparation, retrieval and model time separately. Initial Advisory Ask still opens duplicate complete source-reading epochs; its deferred design is not implemented. The task-status placement fix is already browser-verified in its FAILED state.

Full 2b review/report, whole-2b Advisory, acceptable question/review speed, English export and complete source quality remain open. No answer key was used to generate the two source-based questions. The six-edition SL availability/retrieval baseline remains preserved. Root changes are uncommitted; no synchronized Git delivery is claimed. HEAD/local main/origin tracking and the last verified private remote main were `e2ef7948246dd760a9a5c4d4e30178ea10074402` before this checkpoint.
