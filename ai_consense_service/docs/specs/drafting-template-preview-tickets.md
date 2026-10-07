# Drafting preview integration tickets

Spec: [drafting-template-preview.md](drafting-template-preview.md). Local tracker; no remote publication. Close only with integration commits and observed verification. Preserve RED/failure evidence separately.

| Ticket | Dependencies | Scope | Status | Evidence |
|---|---|---|---|---|
| PREVIEW-01 | none | Read-only original-source bytes and native paragraph metadata HTTP APIs, edition check; source/removal public tests | Done | Service `34309dc`, merge `1799f5f`; 13 focused tests and actual-source HTTP acceptance. [Verification](verification/drafting-template-preview.md) |
| PREVIEW-02 | none | DOCX reading component, sanitized content, typed navigation, current source/approximate anchors, multiple-location controls, component tests | Done | Web `0131f51`, `d8f4538`, `249ee066`; actual source/quote/native mapping, sanitization and component regressions. [Verification](verification/drafting-template-preview.md) |
| PREVIEW-03 | PREVIEW-01, PREVIEW-02 | Integrate optional panel with prototype A, shared field editing/adoption, selected-field/target card and source deletion UI; rendered tests | Done | Web `b7b8ec6`, `ceba21c`, `249ee066`; shared editing/adoption/clear/derived/dirty and removal rendered/browser checks. [Verification](verification/drafting-template-preview.md) |
| PREVIEW-04 | PREVIEW-03 | Two-axis review, correction, full focused checks, actual-source and browser verification, runtime delivery and local closure | Done | Web integrated `604af177`, service `070985c`; no outstanding Spec/Standards findings; final 16 frontend files/typecheck/build, real browser/source/runtime and worktree cleanup passed. [Verification](verification/drafting-template-preview.md) |
