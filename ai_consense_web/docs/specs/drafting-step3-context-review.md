# Drafting step 3: complete document review

Approved design: prototype A at commit 4fa7dab. User clarification on 2026-10-07: preview and comparison belong only to Drafting step 3. Steps 1 (upload/parse) and 2 (extraction/confirmation) must remain as implemented.

## Scope and invariants

Replace step 3's PDF-first location interface with a continuous, structured reading of the actual original and saved DOCX. Keep business inputs, catalogue, conditions, evidence intake, adoption, plan, generation, body-save and revision-bound export contracts unchanged. Do not import prototype inputs, six-variable fixtures, trial substitutions or frozen results. Current user values do not rewrite a saved document: stale/pending states remain visible until existing generation/save succeeds.

## Acceptance

1. Original, review and saved-result modes show complete main-document content in reading order, with headings, tables, lists, inline formatting and Office Math retained. Online layout may differ from exported Word/PDF. PDF is an optional final-layout check, not the main interaction surface.
2. Read-only source and revision-bound DOCX/binding calls are verified by SHA-256 before conversion. Exact native XML paragraph paths, not guessed text matches, locate bindings. Sanitise HTML; reject mismatched versions and discard responses from old projects/files/revisions. Native unsupported content/coverage limitations must be visible.
3. Review shows only the selected variable's associated actual insertions, deletions and changed tokens. Unchanged words are not coloured. A whole alternative deletion is explicitly distinguished from value substitution and retained branch membership. Guidance cleanup is separately marked. Source-only placeholders and added results must not assert a verified one-to-one mapping that the binding ledger lacks.
4. The document is the main surface, with a read-only inspector for selected input value/status, evidence, actions and all linked locations. Click linked wording to select its input; select an input/location to scroll to the corresponding native range. Multiple field associations offer explicit selection; no guessed Bill-record identity. Return to the existing step-2 input preserves the current dirty/adoption state.
5. Only linked variable/adjustment ranges are selectable/marked. Exact literal value frames may be used only when uniquely verified within a bound paragraph. No all-text highlighting, paragraph dots or fabricated geometric precision.
6. Immersive mode gives the document the available viewport height, with independently scrolling inspector. Three locales, loading/error/retry, keyboard operation and project/file/revision switching work. Existing body editing, save/discard and exports remain available; unsaved body edits block navigation/export as before.

## Agreed public verification seams

Reuse the previously approved typed DOCX reading/navigation interface and rendered DraftingView/reading controls from drafting-template-preview.md. Test one red/green slice at a time: dynamic DOCX conversion through its public interface, actual rendered step-3 controls/navigation and stale-response guards. Verify the unchanged first two rendered steps against the integration baseline. Provider calls may be simulated; native identity, conversion, differences and Vue state are real. Final browser checks use current project API data and actual NTT/SCT/SCC, not prototype fixtures.

## Implementation graph

See drafting-step3-context-review-tickets.md. Single integration branch: feature/drafting-step3-context-review-20261007, based on aaa0e928. Local Markdown tracker closes work with commit and verification evidence. No paid technology or backend business changes required.
