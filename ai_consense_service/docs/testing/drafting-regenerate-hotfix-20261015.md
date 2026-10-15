# Drafting Regenerate hotfix, 2026-10-15

The local Drafting project could not regenerate its three documents after the native Word parser upgrade. Both the visible error and the backend log reported `SOURCE_TEXT_DIVERGENCE: SCC`. The original SCC DOCX and its saved cache differed only at four OMML formulas: each historical text projection included 86 additional characters. NTT and SCT still matched exactly. This was a parser projection compatibility failure, not evidence that the user had changed the template.

Generation now compares the saved cache against an exact historical math projection reconstructed from the same original DOCX only when the existing current/blank-row projections do not match. The normal parser still retains native math with unknown semantics. Neither the original bytes nor saved text/structured provenance is rewritten. A different formula or surrounding clause must continue to fail. All three templates must still compile before current revision pointers are published.

The UI presents one combined stale/read-only status. Layout, numbering and change-mapping limitations remain explicitly unverified, with their full details available on expansion. Failure preserves the old revision and its editing/preview/export restrictions; regeneration remains available.

Validation and runtime evidence are kept in the ignored `.local/drafting-regenerate-hotfix-20261015/` directory. Actual original snapshots and text differences are private local evidence and are not committed.

- Frontend: 185/185 tests passed, zero failures/skips/cancellations; default typecheck, isolated production build and both existing Drafting MJS checks passed. `web-freeze15-receipt.json` binds the tested sources. `web-deploy-receipt.json` records the installed build.
- Backend: the original regression failed before the fix. Ten focused classes then completed with 91 passed, zero failures/errors and 36 existing conditional skips (127 total); the skipped IDs already belonged to the preceding full03 baseline. They include an actual service generate test and rejection of changed formula text with zero partial document publication. `actual-template-math-green01.json` confirms all three real templates match while their original bytes, saved text and normal fresh text/blocks remain unchanged.
- The preceding Java full03 result was 2,812 passed, zero failures/errors and 123 unchanged conditional skips (2,935 total). It predates this Drafting delta and does not by itself certify the new compatibility path.

## Local verification

The verified package was installed on port 8080 (JAR SHA-256 `3fe51ad2dd8a6f41e468fb612fbf44e907ce017545a541b04358551c1649d971`) and the verified frontend on 5173. A single real browser Regenerate click in the original project succeeded. Independent document readback confirmed all three documents are generated and `stale=false` with one snapshot. The UI retains 62/62 adopted and zero pending inputs; editing and export controls are available again. Source original/text readbacks and the complete variables response are unchanged. `actual-regenerate-success-receipt.json` records the checks and all revision identities; the screenshot remains local.

- NTT revision: `90c211e3-5202-48f6-b25c-74521e475009`.
- SCT revision: `caee2e73-9d75-427c-a378-d5f5b003b44d`.
- SCC revision: `06935848-5999-4ce1-a902-a91800096e02`.

This hotfix does not establish OCR/model quality or final Word/PDF layout acceptance. Those limits remain visible and no such effect validation is started for this repair.
