# NEXT-06 Advisory sessions: local implementation receipt

2026-10-13, Asia/Shanghai. Branch `codex/oer-advisory-session-20261013`, based on the preserved source baseline `46206d441ee7e16b8cff7cbc76fc1632529ffca1`. This receipt covers code and isolated contract/persistence checks; it does not close real model, source quality, browser or H800 acceptance.

## Implemented contract

- `GET /api/advice/{projectId}/sessions` returns the project's persistent sessions. `POST` accepts `{scope,basisScope}` and returns `{id,projectId,scope,basisScope,createdAt}`. Scope and basis are immutable; changing either requires a separate session.
- `GET` and `DELETE /messages?sessionId=...` operate only on that project's session. Omitted `sessionId` addresses legacy messages with a null session binding. Existing legacy ask behavior remains available.
- `/ask` accepts optional `sessionId`, requiring the request's normalized scope and basis to match it. `AskResponseVO` and historical messages include `sessionId`; each answer retains its own evidence, compact fixed source unit, clarification and model identity.
- V16 adds the session table, message binding/source snapshot, indices and foreign keys without relabeling or deleting legacy messages. Session clearing increments a persistent revision. Publication locks the session and checks the captured revision; concurrent answers or a clear during generation cannot publish against the old revision.
- Follow-up history contains whole exchanges from the same project/session/scope and current source/model identity. Default limits are `consense.advice.history-max-exchanges=5`, `history-max-messages=10`, `history-max-tokens=6000`. The token cap covers the observed history projection including its instructions; excess history drops whole oldest exchanges. Current evidence and final requests are never truncated to fit.
- Clarification parents must retain session/scope/basis and source version. User background and previous answers are explicitly conversational assumptions rather than contractual evidence or current citation IDs. Source or model changes, unknown/exhausted budgets and incomplete output fail visibly without publishing a new exchange.

## Runtime admission handoff

Explicit sessions fail with code `4205` until the selected route has a bound counter and at least 256K context. No runtime configuration, active database, key, model or service was changed in this implementation.

For M3, the existing Advice route is `consense.advice.minimax-wire=responses` with the selected `X-ConSense-Llm-Profile: minimax-cn`. Its existing `consense.advice.responses` configuration must enable the exact M3 route, set matching model/output reserve/temperature, `configured-context-tokens>=262144`, a positive safety margin and `estimate-policy=bound_provider_estimate_with_absolute_margin_v1`. `AdviceResponsesTransport` counts the final input through `/v1/responses/input_tokens` before `/v1/responses` generation, including history and JSON retries. This is explicitly a provider estimate under a configured margin, not an exact template or measured hardware claim.

For native Chat, reuse `consense.vetting.token-budget-observer-enabled`, `token-budget-observer-command`, `token-budget-observer-recipe-path` and `token-budget-observer-recipe-sha256`. The existing `tools/vetting_eval/bound_native_token_budget.py` now admits the exact Advice JSON-object instruction with an empty schema, alongside the unchanged Vetting array envelope. A new sealed recipe must include the changed script checksum, actual selected model/configuration, tokenizer/template/resource hashes, output reserve and `current_deployment_configuration_bound` context. Never substitute another model's counter. Ollama requires explicit `structured-thinking=false` and an observed context matching `num-ctx`; its configured context must be at least 262144. Old sealed recipes were not edited or activated.

## Actual local checks

- Final focused Maven run: **42 tests, 0 failures/errors/skips** across `AdviceSessionIntegrationTest` (14), `AdviceModelIdentityMigrationIntegrationTest` (1), `AdviceFixedBaselineTest` (7), `VettingTokenBudgetCliObserverTest` (14) and `VettingInputBudgetTest` (6). Evidence is in each class's `target/surefire-reports` XML/text; command output is `target/advice-session-final-tests.log` (ignored local evidence).
- Earlier expanded regression: **87 tests, 0 failures/errors/skips** across the above Advice/session families plus existing canonical scope, selected-profile, unconfigured-profile and Responses transport contracts. That iteration had 12 session cases; the final focused run includes the added profile-change and original-source-identity cases. These overlapping runs must not be added into an acceptance total.
- Python: **7 tests passed** in `test_bound_native_token_budget`, covering strict JSON, supported exact envelopes, nonempty-schema rejection, mutation rejection and preservation of retry/history literals. This is a pure boundary test, without a native tokenizer or model call.
- `git diff --check` passed. Java used the verified JDK17 alias and existing Maven3.9.11; tests used isolated H2 and fixture model/counter adapters. No remote model, production database, active vector owner or browser was used.

The known `CanonicalPageExcerptTest` baseline failure was outside this focused selection and was not deleted, skipped or reclassified. Real M3/native counter integration, original byte/source quality, business answers, frontend acceptance, live MySQL migration and H800 remain unverified. No new dependencies, session deletion/renaming platform, global memory service or Drafting change was added.

## Integrated contract correction

The earlier excerpt failure remains recorded above. The integrated test now decodes the actual lossless model-material dictionary and follows ordered member references, asserting both complete source parts and physical-page bindings. It no longer assumes the superseded inline excerpt layout; no production text was weakened or truncated.

The delivery worktree's `integrated-shared-advice-tests.log` records an exit-zero run of **75 tests, 0 failures/errors/skips**: CanonicalPageExcerptTest (8), DrawingUploadHttpTest (3), AdviceSessionIntegrationTest (14), AdviceCanonicalIntegrationTest (19), AdviceFixedBaselineTest (7), AdviceDocumentScopeIntegrationTest (15), FixedBaselineRetrievalClientTest (6) and FixedBaselineSourceControllerTest (3). Individual Surefire XML files are the count source. These overlap previous runs and are not added to them. This confirms local contracts, not a real model or browser result.

## Admission correction for all new questions

The earlier session-only admission description is superseded by [all-generation admission and full fixed identity correction](oer-advice-all-generation-admission-20261013.md). Every new Advice model call, including requests omitting `sessionId` and explicit fixed-standard explanations, now requires the same final-wire budget and at least 256K context; historical reads remain available. The correction also expands the Advice evidence identity column with V18 without shortening fixed-source identities, and records its own 119-test local run separately.
