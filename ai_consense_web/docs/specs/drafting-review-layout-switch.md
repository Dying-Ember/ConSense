# Drafting A/B review layout switch

The user wants the original prototype B added to the actual Drafting review step, alongside the current A list. The prototype's unit is one independent business question, with its related sub-variables together. The current competition catalogue and rule order remain authoritative; do not restore the prototype's old catalogue or adoption behaviour.

## Acceptance

1. Step 2 provides an accessible A List / B One question switch. A remains the initial default. B displays one current catalogue business-question group, including all currently applicable sub-fields and its existing evidence, validation, save/adopt/discard and clause actions. Use the same editor rendering and values for both layouts.
2. B has a compact question index with localized titles and current group states. It permits direct selection and previous/next navigation in catalogue order; display the current position and number of matching questions. Navigation never saves or adopts answers automatically. Both layouts preserve unsaved values, candidates, adoption status and conditional retained values.
3. Existing search/status filters operate in both modes. Selection is anchored to group ID, not an unstable numeric index. If the current group is filtered out, select a matching group; if there are no matches, show the existing empty state and usable filter reset. Stable catalogue numbering avoids renumbering identities after filtering.
4. All missing active fields remain visible/editable in B. Existing applicability and dependency logic is unchanged. Do not change backend rules, extraction, templates, generation, counts, or source data.
5. Template-to-input and unresolved-item-to-input navigation clears incompatible filters and selects the field's group in B before scrolling/focusing the existing field control. The user remains in their chosen layout. A/B changes do not disturb the template reader or selected source position.
6. Language remounts and same-project revisits restore the chosen layout and question through the existing project draft cache. Old cached views remain compatible and default to A. Project changes restore that project's own view without transferring another project's selection or values.
7. All controls/help/empty states work in English, simplified Chinese and traditional Chinese. B collapses to a usable narrow-screen arrangement without hiding navigation or form controls. Existing A styling remains intact.

## Verification seams

The existing drafting implementation tickets already agree on rendered frontend controls and project-scoped API boundaries. Continue there: real Vue view/components with only the external API stubbed, plus the running in-app browser. Verify changing a value in B and returning to A without saving; missing fields; filters and empty recovery; project/language remount restoration; template bidirectional navigation; and conditional rendering. Browser QA must not modify the user's project values or invoke an LLM.

Implementation follows the previously requested implement-spec/TDD workflow and the local Markdown issue tracker. The independent review baseline is `6a6b79a8389b839794419e2b88382fc15e380708`.
