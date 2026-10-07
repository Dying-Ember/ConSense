# ConSense

Private source snapshot of the current ConSense application: contract drafting, vetting, evidence inspection and advice. The Vue frontend and Spring Boot service are kept together so that the reviewed UI, extraction harness, business bindings and document-generation APIs can be developed against the same snapshot.

## Repository layout

| Directory | Contents |
| --- | --- |
| `ai_consense_web/` | Vue 3 / TypeScript / Vite application, three-language UI, drafting review and document comparison, business relationship graph, frontend checks |
| `ai_consense_service/` | Spring Boot service, extraction/evidence harness, business registries, DOCX generation, document rendering adapters, vetting/retrieval tools and service tests |
| `SOURCE-SNAPSHOT.json` | Source revisions, publication-only changes and verification summary |

This is a new repository with a clean initial history. It does not import the original repositories' history, local databases, uploaded documents, private correspondence, model weights, credentials or generated runtime outputs. Authored synthetic fixtures remain explicitly test-only. Existing verification documents describe historical local observations; a fresh clone must establish its own runtime and business-output acceptance.

## Start locally

The publication was compiled with JDK 17 and Maven 3.9, and the frontend was checked with Node.js 24 and the existing locked dependencies. Install a compatible Node.js runtime, npm, JDK 17 and Maven. Installed runtimes and dependencies are not shipped in this repository.

For a local H2 service, start in `ai_consense_service/`:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'h2'
$env:CONSENSE_ALLOWED_ORIGINS = 'http://localhost:5173,http://127.0.0.1:5173'
# Set up model/OCR services before enabling these profiles.
$env:CONSENSE_LLM_ENABLED = 'false'
$env:CONSENSE_MINIMAX_ENABLED = 'false'
$env:CONSENSE_OCR_ENABLED = 'false'
mvn spring-boot:run
```

The default HTTP port is 8080. H2 data and uploads are created under the service's local `data/` directory and ignored by Git. The application profile defaults to MySQL when `SPRING_PROFILES_ACTIVE` is not set; see the service README for MySQL and full runtime configuration. New project data must be created or uploaded locally.

In a second terminal, start in `ai_consense_web/`:

```powershell
npm ci
$env:CONSENSE_API_TARGET = 'http://localhost:8080'
npm run dev -- --host 127.0.0.1 --port 5173
```

Open `http://127.0.0.1:5173/#/drafting`. Set the API target and service allowed origins to match your actual ports. The frontend proxies `/api` to the companion service.

## Models, OCR and document export

The service exposes explicit local-model and MiniMax China Token Plan selections. Configure their actual deployment endpoints with `CONSENSE_LLM_BASE_URL` and `CONSENSE_MINIMAX_BASE_URL`, and enable the corresponding profile. Supply the MiniMax key through the service process environment variable `MiniMaxCN_Token_Plan_API_Key`; no key is included in source control. Model readiness and extraction quality must be tested against the selected deployment.

OCR, embeddings, reranking and vector retrieval have separate runtime dependencies. DOCX generation uses the uploaded editable templates and checked-in business bindings. PDF generation requires a separately installed, configured rendering runtime and fonts; online document reading/comparison and exported PDF layout have different purposes. See the service README and rendering configuration for the supported adapters and external assets. No paid renderer or commercial licence is provisioned by this snapshot.

## Verification

The exported frontend passed type checking, a Vite production build, 35 CommonJS checks and three workspace/scope/view checks. The exported service passed Maven packaging and 13 business-rule tests without calling a real model provider. Four native-layout guard cases were skipped because their external runtime was not supplied. These checks used existing local dependency caches; they do not establish a fresh dependency installation, external service readiness, native PDF layout or business acceptance of a new deployment.

Run the frontend checks described in its README and the service checks described in its README. Tests that require excluded private originals or actual runtime captures are identified separately under `ai_consense_service/tests/external-fixtures/`; their Java source is retained, but they are outside the default Maven test source set until authorised fixtures are restored. The optional frontend OCR diagnostics replay also requires an external fixture. Missing original evidence must not be replaced with an unlabelled synthetic result.

## Snapshot provenance

| Component | Source revision |
| --- | --- |
| Frontend | `629ef98d197cd83e4e7128e468b292492e4443f2` |
| Service | `67ceb7081d42204aa17b12b149f52081e348da71` |

Application business logic and variable answering behavior are preserved. Publication-only changes make the scope baseline portable, remove local/private data, replace machine configuration with environment placeholders, clear demonstration project seeds, and document external runtime/fixture requirements. The source worktrees and the running local application were not changed by this export.
