# Advisory admission and complete fixed identity: local correction receipt

2026-10-13, Asia/Shanghai. Follow-up to byte-guard commit `27b7256d86b9626bff7ce498ea4c080b3d6c1a80`. D6/AC09 apply to every new model generation, including `/ask` without `sessionId` and explicit `reference_only` requests. This receipt supersedes the earlier session-only admission description; it records local contracts, not live model or browser acceptance.

All native Advice generation now uses the existing captured structured Chat path, `sessionTurns`, `sessionBudget` and bound observer. The exact JSON-object turns submitted to the captured client, empty schema, model/configuration identity, original-source snapshot and output reserve are counted for each attempt. Unknown observations, excess input plus reserve, verified context below 262144 or an Ollama context differing from the captured `num-ctx` return safe budget code `4205` before generation. Source and captured model-configuration guards also cover requests without a session. One malformed JSON retry retains the existing literal instruction and requires another complete observation.

Every Responses generation requires configured context of at least 262144 before counter or generation dispatch, then retains the existing bound final-wire provider estimate and configured safety margin. Omitting `sessionId` cannot bypass either transport's budget. The API still supports one independent question without silently creating a session. Reading existing history and deterministic no-hit responses do not generate model output and remain available.

The legacy generation bypass helper was removed. No new observer platform, dependency, runtime configuration, storage helper, Gateway or Drafting change was added. Test adapters explicitly provide authored bound observations; those observations are not actual tokenizer, model or hardware evidence.

## Full fixed evidence identity

`fixed-baseline:fixed:<64 character SHA256>` is 85 characters. `ChatMessage.evidenceId` and exact V18 SQL now allow 256 characters; no identity is shortened or rehashed. The populated upgrade fixture executes V18 and verifies SQL column length 256, writes the full 85-character identity and reads it unchanged through public Advice history HTTP. Existing historical messages retain their identity and content.

Other fixed identity carriers were checked: Advice response/history DTO strings and citation `fixedBaselineEvidence` JSON carry the complete parent identity without a SQL varchar limit. `VettingFinding.evidenceId` is `snapshot:<primary tender SQL document ID>` or `unverified`; fixed parent identity is held in its finding-evidence JSON. That bounded scalar field was not expanded. This migration changes only the Advice scalar that failed during the independent browser run.

## Actual local checks

The final Maven command exited zero with **119 tests, 0 failures, 0 errors and 0 skips**, from the corresponding `target/surefire-reports/TEST-*.xml` files:

| Class | Tests |
| --- | ---: |
| AdviceSessionIntegrationTest | 19 |
| AdviceDocumentScopeIntegrationTest | 15 |
| AdviceModelIdentityMigrationIntegrationTest | 2 |
| AdviceLlmSelectionIntegrationTest | 8 |
| AdviceUnconfiguredProfileIntegrationTest | 1 |
| AdviceFixedBaselineTest | 8 |
| AdviceCanonicalIntegrationTest | 20 |
| AdviceResponsesTransportTest | 18 |
| CanonicalPageExcerptTest | 8 |
| VettingInputBudgetTest | 6 |
| VettingTokenBudgetCliObserverTest | 14 |

`target/advice-all-generation-admission-final-tests.log` is the ignored local command evidence. Bare project asks prove missing observation, input plus reserve overflow and 131072 context produce no Chat generation and no new history, while a bound 262144 observation admits the exact submitted turns without creating a session. Bare fixed-reference asks prove unknown and excess budgets cannot dispatch. Bare Responses asks configured to 32768 prove neither counter nor generation is called. Profile changes during counting are rejected for both explicit and omitted session IDs. Source-byte mutation, historical evidence preservation, JSON retries, canonical scope, selected-profile identity and session concurrency regressions continue to pass.

Checks used isolated H2 with the actual SQL scripts, temporary original files and authored model/counter adapters. `git diff --check` passes. These tests overlap earlier runs and are not added to them.

This correction did not call an external model, migrate an active database, restart a service or validate browser/business/H800 behavior. Existing installations need the exact V18 migration before writing full fixed evidence IDs; an entity change alone does not prove an existing column was expanded. Native generation now intentionally stays blocked until its selected deployment has a valid bound observer and at least 256K effective context.
