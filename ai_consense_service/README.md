# Service: configuration, integration and verification

Competition source preparation and OER development follow the [confirmed scope and rules](docs/oer-scope-and-rules.md); current model/service verification and remaining acceptance are recorded in the [OER handoff](docs/HANDOFF-ocr-embedding-rerank-20261008.md). Read these before downloading documents, changing parser/index recipes or activating the OER profile.

Initial import source: 67ceb7081d42204aa17b12b149f52081e348da71
Current integration: remote drafting baseline `2e75126` plus local vetting/advisory `67ceb708..82ea3d9`; see repository-root `SOURCE-INTEGRATION.json`.
Module: ai_consense_service  
This is a fresh source snapshot. Existing projects, uploaded files, database rows, actual model/runtime captures, old Git history, model caches, and secrets are absent.

The three runtime drafting registries and thirteen Flyway migration files (including V12/V13) are retained. The skills seed is retained byte-for-byte as the generic definition. projects.json, vetting-files.json, and findings.json are empty arrays, so a new installation starts without demo projects, source rows, or findings. guidance-compiler-bindings.json differs only in its three originalSourceDocuments.*.sourcePath strings; all binding rules, source/text hashes, inventory hash, and other bytes are retained.

## Build and local checks

Use Maven and a reviewed JDK with ProcessHandle support (Java 9+ is required by formatted PDF process management; this snapshot was checked on JDK 17). The POM's compiler source/target remains Java 8.

Commands:

    mvn -Dtest=DraftBusinessRulesTest,DocxNativeLayoutGuardTest test
    mvn -DskipTests package

The upstream Oct 9 integration was checked with JDK 17.0.20.1 and Maven 3.9.16 using `mvn -B -Dconsense.test.python=<prepared-interpreter> clean package`. The default suite reported 1,618 tests: 1,550 passed, 68 skipped, zero failures/errors; the executable JAR was built. No real model provider, neural forward, tokenizer load, vector database or OCR operation was performed by the protocol fixtures. These are the upstream integration receipts, not fresh test results for this local working-tree merge.

Five test files require private originals, real runtime captures or actual templates/renderers and are preserved under `tests/external-fixtures/java`, outside the default source root. The five vetting protocol/helper test files are back in the default source root with a frozen non-secret metadata fixture. They require a Python environment with the documented dependencies; set `-Dconsense.test.python=<interpreter>` or `CONSENSE_TEST_PYTHON`, or use the service `.venv`. See `tests/external-fixtures/README.md`. Skipped and external cases are not counted as passed.

The 13 passing rule tests and four skipped native guard cases were initial-import checks, not the current integration totals.

Compiled output under target/ is ignored and must not be committed. Build/test receipts are stored outside the publication tree.

## Configuration

For teammate development through the authenticated MiniMax-M3 relay, see [MiniMax relay handoff](docs/minimax-relay-handoff.md). The `minimax-relay` Spring profile uses a dedicated relay token, preserves non-streaming structured output, and lets the relay own 529 retries. [.env.example](.env.example) contains placeholders only; copy it to an ignored `.env.local` and load it into the backend process environment. The guide includes frontend variable tests and a text-only SDK client for reviewed code suggestions.

The application profile is selected through SPRING_PROFILES_ACTIVE. H2 is a development profile; supply a new empty database or a separate deployment database. Existing local .db files are excluded.

The export adds these environment placeholders:

| Name | Meaning |
| --- | --- |
| CONSENSE_STORAGE_ROOT | Writable upload/project/export storage directory; generic relative fallback only |
| CONSENSE_ALLOWED_ORIGINS | Allowed frontend origins; generic localhost example fallback |
| CONSENSE_LLM_BASE_URL | Generic LLM provider endpoint; empty default |
| CONSENSE_LLM_ENABLED | Optional provider enablement; source default retained |
| CONSENSE_MINIMAX_BASE_URL | MiniMax provider endpoint; empty default |
| CONSENSE_MINIMAX_ENABLED | Optional provider enablement; source default retained |
| CONSENSE_OCR_BASE_URL | OCR endpoint; empty default |
| CONSENSE_OCR_ENABLED | Optional OCR enablement; source default retained |
| CONSENSE_VECTOR_BASE_URL | Vector service endpoint; empty default |
| CONSENSE_VECTOR_API_KEY | Vector service credential; empty default |
| CONSENSE_RETRIEVAL_URL | Hybrid retrieval endpoint for the local vetting profile; empty default |
| CONSENSE_H2_JDBC_URL | Development H2 JDBC connection |
| CONSENSE_VETTING_LOCAL_JDBC_URL | Local vetting profile H2 JDBC connection |

Existing placeholders remain:

| Name | Meaning |
| --- | --- |
| MYSQL_HOST / MYSQL_PORT / MYSQL_DATABASE / MYSQL_USER | Deployment database connection |
| MYSQL_PASSWORD | Database credential, default empty |
| MiniMaxCN_Token_Plan_API_Key | MiniMax credential, default empty |
| SPRING_PROFILES_ACTIVE | Profile selection |

No actual credential belongs in a committed example file. Provision secret values through the deployment secret store or process environment. Configure provider, model IDs, timeouts, token budgets, retrieval contract fields, and vector collection through the matching consense.* Spring properties.

For a source-only local start without optional services, set CONSENSE_LLM_ENABLED=false, CONSENSE_MINIMAX_ENABLED=false and CONSENSE_OCR_ENABLED=false in that process. This does not make real model/OCR/retrieval functionality available; configure the required service endpoints before using those operations.

For PDF rendering, supply consense.drafting.renderer-kind, renderer-executable, renderer-asset-root, renderer-font-cache, renderer-work-root and renderer-timeout-ms. The x2t route requires read-only immutable assets, a pregenerated AllFonts.js and nonempty font_selection.bin with its referenced fonts present, and a separate writable render work directory. The LibreOffice route requires headless soffice, fonts and a separate writable work root. Report font configuration is consense.report.font-path. The rendering binaries/fonts are not bundled with this snapshot.

Original NTT/SCT/SCC documents and correspondence must be uploaded through the application; a source snapshot cannot recreate an existing project or previously verified layout environment.

## Optional Python OCR/retrieval tools

tools/vetting_eval contains the implementation and pinned requirements. Torch/CUDA and model weights/cache are provisioned separately. Formal OER model entrypoints require CUDA and fail when it is unavailable; they do not offer a CPU/auto device selector or silently fall back. Use the separate `.local/oer/venvs/retrieval-gpu` environment for `scripts/oer-local.ps1`; historical CPU evaluation environments and frozen runs remain baselines. Source-bound model/device/dtype and index identities must match, so rebuild in a separate state for the CUDA recipe. See [OER scope and runtime rules](docs/oer-scope-and-rules.md) and [implementation handoff](docs/HANDOFF-ocr-embedding-rerank-20261008.md) for the current verified state.

samples.json preserves historical sample evaluation settings: eight source entries, five OCR cases and eight queries. It contains relative references and sample needles, not the required original files or a fresh accuracy result. The server imports this model-config structure; supply deployment model/runtime settings and authorized external sources when required.

Source-identity inventories are preserved in `tests/external-fixtures/manifests/`; they contain relative filenames, hashes and metadata, not document bodies. Absolute-path historical query replays, real source excerpt corpora, model/runtime traces and outputs remain external. An old prompt probe is retained as an explicitly historical document under `docs/archive`. Evaluation scripts still need authorized external originals and configured models.

## Verification boundary

The initial packaging checks remain in `SOURCE-SNAPSHOT.json`. Current integration sources, tests and limits are recorded in repository-root `SOURCE-INTEGRATION.json`. V12/V13, scope/checklist/report source changes, paired frontend APIs, test fixtures and historical documents were integrated while preserving remote drafting PR #3.

The upstream integration recorded passing source compatibility and default-suite/build checks. Those receipts do not verify this local working-tree merge or live deployment. Live provider capacity, real model output quality, real OCR/retrieval precision, real MySQL/Flyway deployment and final original-document PDF layout remain separate acceptance tasks. No historical or synthetic result is presented as new business acceptance.
