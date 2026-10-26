# OER, Vetting and Advisory workflow goal

Checkpoint: **2026-10-26 07:30 Asia/Shanghai (2026-10-25 23:30 UTC)**.
Status: **ACTIVE**. This replaces the previous 19:00 and 08:00 execution priorities. Report actual progress at the checkpoint and continue if acceptance remains incomplete.

## Objective and priority

Make the complete-file frontend workflow usable and acceptably fast before improving answer accuracy, reading order, cross-page continuity or complex tables. Use only `qwen3.8-27b` as the chat LLM. Do not concentrate on Q1.4 or another isolated sample while other workflow improvements can advance.

1. Reduce evidence weight and repeated work across OER, Java, browser responses and model requests. Trace actual bytes and elapsed time, then remove duplicate full-source/CLOB reads, repeated complete snapshots, unnecessary fields, redundant serialization and repeated heavyweight verification. Keep original identity checks and lightweight freshness checks at external waits.
2. Start with useful text. The first model request should contain the question or review direction, selected clause text, necessary clause/section context, and minimal source/page identifiers. Ask whether that evidence supports the requested conclusion. If it does not, require a concrete request for the missing clause, adjacent text, table context or other evidence; disclose only that requested material. Do not send complete parser graphs, geometry, vector data, model history or every stored field by default.
3. Prove Vetting and Advisory through the in-app browser on complete originals. Cover source disposition, preparation/indexing, submission, visible progress, terminal state, saved results, citations/source opening and English export. Preserve partial usable results and explicit unreviewed sources. A failed or optional source must not silently count as reviewed or unnecessarily prevent usable sources from progressing.
4. Preserve the six-edition SL availability baseline and edition-bound selection/retrieval in both modules. Defer further SL/source-quality tuning until workflow and speed are demonstrated.

## Materials and evidence

- Use the complete 35-original 2b package as the inventory and disposition denominator. Identify and exclude the two drawings; omit the duplicate 2b SL from new SL input. Confirm textual-document and inventory purposes through the frontend. Do not substitute a few pages for complete-file timing.
- Use the 2d Appendix I checklist as review instructions. Use the 2d Comments report only as an offline deliverable exemplar. Neither supplies project evidence or findings to the same review.
- Use 2e only for offline comparison after an independent judgment from the question and original contract sources. Investigate differences; the sample answer can also be wrong.
- Keep original bytes and failed attempts authoritative. Generated files must be entirely English; translated source quotations must be labelled. Essential UI controls and explanations must remain readable without raw hashes or deep nesting.
- Code tests, actual model calls, runtime delivery, frontend workflow, complete-source coverage and content accuracy are separate claims. Follow the main hotfix synchronization rule when delivering code.

## Execution and checkpoint report

Execution-order update, 2026-10-26: finish the complete upload-to-export data-flow audit and implement the coherent repairs before starting another validation cycle. Cover OER production and publication, preparation, index reuse, retrieval, evidence packing, model preflight and generation, persistence, export and browser source loading. Do not launch another complete-file trial after each local fix. Retain completed and failed checks, but start the next combined software gate and then serial complete-file browser acceptance only after all shared call paths and large-object lifetimes have been reviewed and repaired.

Use small complete changes and existing mechanisms. Measure before and after on the same complete originals and source scope. Do not solve repeated heavyweight data movement by adding more metadata, gates or parallel representations. Preserve trust-boundary identity and error handling while keeping normal operation light.

At 07:30 report by priority and module: changes; tested versus untested code; actual browser actions and outcomes; included, excluded, failed and unreviewed file counts; observed stage and total times; request/response sizes where measured; saved results and English files; blockers; and Git/runtime delivery state. State unknowns explicitly. Do not mark complete until the requested full workflow is true and verified.

## Starting state

At 2026-10-25 23:57, six official SL editions retain the 19,040-page browser-started build and bounded 12-query retrieval baseline. The complete 2b inventory is visible, but its latest Vetting start failed on confirmed GCT parsing failure; a focused fix is green but not deployed or browser-verified. Advisory still has an observed 12.5-minute uncited Q1.4 failure, repeated large source/status reads, and an unverified CLOB reduction. These remain open workflow/performance problems. See the [progress ledger](../testing/oer-sl-product-progress-20261025.md).
