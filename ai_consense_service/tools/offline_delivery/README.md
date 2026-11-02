# Offline deployment candidate

This directory implements UNI-09 preparation and recovery boundaries. It does not apply resources, build images, contact an active database, run models, change Local05, or establish Linux/MySQL/Kubernetes/H800 acceptance. Every generated receipt retains `targetAccepted: false`.

## Local five-role Docker rehearsal

[比赛五角色交付与启动](competition-delivery-guide.md) 汇总镜像归档、外置资产、现场配置和参数化创建入口；目标环境尚未验证。

[资料打不开时的人工排查](source-troubleshooting.md)涵盖路径、权限、项目版本和服务状态。

For competition deployment, include `competition` in `SPRING_PROFILES_ACTIVE` and configure
`CONSENSE_QWEN_BASE_URL` with the actual vLLM service base URL **without** `/v1`; the existing
Qwen client appends `/v1/models` and `/v1/chat/completions`. Set the model and credentials
through the existing external configuration. With this profile, an absent or blank endpoint
leaves the Qwen profile unconfigured and a Qwen request fails explicitly instead of falling
back to the generic cloud endpoint. The profile does not enable Qwen or change OCR, retrieval,
or GPU admission. The `app-source` / `rehearsal-source` configuration is for the local
protocol-stub rehearsal and must not be carried into the competition as an implicit vLLM address.
An explicitly supplied higher-priority Spring property may override the profile; verify its
effective source and value when deploying. A configured URL alone does not prove vLLM readiness.

The separate competition rehearsal uses `Dockerfile.app-role-external`, `Dockerfile.h2-external`, `Dockerfile.qdrant-external`, `Dockerfile.source-external`, and `Dockerfile.gpu-worker-external`. The first three are minimal role images whose startup script or executable, Java, Qdrant, release files, configuration, and state are mounted from the hard disk. Runtime bind mounts, limits, loopback listeners, and secrets remain explicit at container creation. The H2 image default was updated to a 16 GiB maximum heap on 2026-11-01; an existing container retains its previous command until replaced. The GPU image keeps the same Python startup arguments split between `ENTRYPOINT` and `CMD` for the fixed-container admission contract. Build and runtime receipts are in the ignored `.local/container-rehearsal-20261029/serial-role-images-20261030/` directory. On 2026-10-30, five built images and three running CPU roles did not establish five stable containers or real model acceptance. For the current rehearsal status, consult the local ticket board and independently check the registered instances.

### App font runtime

For the five-role App, place the font runtime on disk outside the App release and mount it read-only at `/runtime/font-runtime`. Prepare `fonts.conf`, `manifest.json`, `lib/`, `fonts/`, and `licenses/`. The seven-library candidate contains `libfontconfig.so.1`, `libfreetype.so.6`, `libharfbuzz.so.0`, `libgraphite2.so.3`, `libpng16.so.16`, `libjpeg.so.8`, and `liblcms2.so.2`; its fonts are DejaVu Sans, Sans Bold, Serif, Sans Mono and WenQuanYi Zen Hei. Include the accompanying package copyright and licence files for all libraries and fonts. Record the selected files and versions with the deployment; the ignored local candidate is not itself part of the repository.

Set `FONTCONFIG_FILE=/runtime/font-runtime/fonts.conf` and include `/runtime/font-runtime/lib` in `LD_LIBRARY_PATH`, preserving any other required library paths. The supplied fontconfig file looks for fonts under `/runtime/font-runtime/fonts` and uses `/runtime/tmp/app/fontconfig` as its cache; that cache directory, or its existing parent, must be writable by the App's non-root user. Keep the font runtime read-only and the writable cache outside it. Configure the bind mount and environment when creating the App container; `Dockerfile.app-role-external` alone does not add them, and an existing container cannot acquire them merely by restarting.

The local App container has drawn Chinese and English text with the original five-library runtime. PDFBox rendering of a real source page subsequently exposed missing JPEG and lcms2 libraries; an independent original-page probe passed with the seven-library candidate. That runtime is now mounted in the local rehearsal App, where the same JRE and PDFBox rendered the first page of document 50. The business upload retry has begun; its dual OCR result is not yet verified. The libraries came from Ubuntu 24.04; compatibility with the competition image, its system libraries and ABI, and target-environment rendering remain unverified. Check those on the actual target rather than assuming the local result proves competition readiness.

### Linux offline build entry

Prepare a separate source copy and supply absolute paths to the local JDK, Maven, Node, npm cache and Maven repository. `npm ci` removes and reinstalls `node_modules`; the build updates `dist` and `target` in the supplied source directory:

```sh
sh scripts/build-competition-offline.sh /path/to/source-copy /path/to/jdk /path/to/maven /path/to/node /path/to/npm-cache /path/to/maven-repository
```

The script uses npm and Maven offline modes and skips Maven test execution; run the required tests separately. Those flags do not prevent network access by dependency lifecycle scripts: for an actual offline rehearsal, isolate the build process and its children from external networking. Record the supplied tool and dependency identities, source version and generated JAR/web files, then prepare a new release as described below. Missing cache entries fail the build. This entry has not yet been validated as a full offline build in the competition runtime.

### Local dedicated App release inventory

Assemble a **new** dedicated App release directory on disk; never overwrite an existing or running release. Its top level contains `app-entrypoint.sh`, `app.jar`, `web/` and `manifest/`. The manifest initially has a sorted `web-files` list and a `sha256sums` file covering exactly the JAR and all web files. Only declared regular files and directories belong in this root. Keep databases, uploads, model assets, secrets, temporary files and generated deployment output outside it. Preserve the old release for recovery.

From this directory (`ai_consense_service/tools/offline_delivery`), after the files have been independently identified, use the pinned expected entrypoint SHA and the **new, not yet in-use** release path to generate the complete list and two digests:

```sh
python3 -c 'import json,sys,delivery; print(json.dumps(delivery.prepare_local_app_release_inventory(sys.argv[1],sys.argv[2]), sort_keys=True))' /absolute/path/to/new-app-release EXPECTED_ENTRYPOINT_SHA256
```

The function refuses an existing `manifest/release-files`, unlisted files and mismatched content; it creates that list once and returns `releaseFilesSha256` and `contentManifestSha256`. Independently verify the complete release and preserve both values and the entrypoint identity in a separate trusted deployment record. Do not derive the expected pin from a possibly changed release at startup. The caller must pass `APP_RELEASE_DIRECTORY=.`, `APP_RELEASE_FILES_SHA256=<releaseFilesSha256>`, and `APP_CONTENT_MANIFEST_SHA256=<contentManifestSha256>` along with `APP_JAR=app.jar` and `APP_WEB_DIRECTORY=web`; mount the exact dedicated root read-only at `/artifacts` and its `manifest` subdirectory read-only at `/app-files`. Missing inventory or pins causes startup to fail. An actual creation or restart caller must verify the exact release, mount, command and trusted values; historical one-off rehearsal scripts are not reusable deployment callers.

### Start and diagnose the registered local App

These steps apply to an **already created**, registered App container in the dedicated rehearsal daemon; they are not a command to recreate it. Select the full container ID, image digest, dedicated release path, entrypoint digest, both manifest digests and effective configuration from the independently reviewed deployment record. Do not obtain expected digests from the release being checked or print credentials. The previously used one-off candidate hard-codes historical CIDs and PIDs; do not replay it as a current startup command.

In the dedicated WSL distribution, first confirm the daemon and registered H2 and Qdrant instances are available, the App's port is free, no other App instance would overlap it, and the total remains within five running containers. Inspect the selected stopped App object against the record: image and command, `consense:consense` / UID:GID 10001:10001, read-only root filesystem, read-only `/artifacts` and `/app-files` mounts from the same registered release, writable App data and temporary mounts, private configuration, and the `APP_JAR`, `APP_WEB_DIRECTORY`, `APP_RELEASE_DIRECTORY` and both digest values. Compare secret-bearing environment or configuration locally without displaying them. Do not start an object with mismatched or unknown settings. An existing Docker object has immutable mount and environment settings; changing the release or its pins needs a separately reviewed new object, not `docker start` on the old one.

Set `APP_CID` to the full verified ID, `APP_RELEASE` to its registered host release directory, and `ENTRYPOINT_SHA256`, `APP_RELEASE_FILES_SHA256` and `APP_CONTENT_MANIFEST_SHA256` to the independently recorded expected values. With those values set, check the release and start that exact object:

```sh
set -eu
: "${APP_CID:?}" "${APP_RELEASE:?}" "${ENTRYPOINT_SHA256:?}" "${APP_RELEASE_FILES_SHA256:?}" "${APP_CONTENT_MANIFEST_SHA256:?}"
test "$(sha256sum "$APP_RELEASE/app-entrypoint.sh" | cut -d' ' -f1)" = "$ENTRYPOINT_SHA256"
test "$(sha256sum "$APP_RELEASE/manifest/release-files" | cut -d' ' -f1)" = "$APP_RELEASE_FILES_SHA256"
test "$(sha256sum "$APP_RELEASE/manifest/sha256sums" | cut -d' ' -f1)" = "$APP_CONTENT_MANIFEST_SHA256"
(cd "$APP_RELEASE" && sha256sum --strict -c manifest/sha256sums)
docker --host unix:///run/consense-docker/docker.sock start "$APP_CID"
docker --host unix:///run/consense-docker/docker.sock inspect --format '{{.State.Status}} pid={{.State.Pid}} exit={{.State.ExitCode}}' "$APP_CID"
for attempt in $(seq 1 30); do
    if curl --fail --silent --output /dev/null http://127.0.0.1:28680/; then break; fi
    if [ "$attempt" = 30 ]; then echo 'App page did not become ready' >&2; exit 1; fi
    sleep 1
done
```

The separate object inspection is essential: these shell variables do **not** update the container's existing environment. Check the actual process identity and registered mounts after start, then use the permitted browser path to perform and read back the ticket's existing business operation. HTTP success alone does not prove a business save or private-path isolation.

If startup exits, inspect the recorded exit status and exact mounts and compare the trusted entrypoint and manifest digests first; missing files, wrong pins or an extra release file can make the entrypoint exit before Java and may produce no useful application log. If it stays running but the page fails, check the App loopback listener and the registered H2/Qdrant endpoints and configuration, then inspect the relevant private logs for the error category. Logs may contain sensitive values: keep them access-restricted and do not paste them into a public issue. Preserve the failed object and its evidence; do not overwrite the release, remove the object, or blindly repeat an operation with an unknown outcome.

**Evidence boundary:** the 2026-10-31 one-time switch ran a registered release and checked its process identity, web worker, projects, a synthetic Qdrant point and one PDF; a separate actual-container extra-file negative exited 1. The 2026-10-30 browser save/readback used an earlier App instance. The instructions above, including the chosen current object, its configuration and business operation, have not yet been executed end to end for the current environment. Do not treat those historical checks as present availability or full ticket acceptance.

The shell runtime must supply GNU `find` (`-printf`), `sort` (`-z`), `sha256sum` (`--strict`), `tr`, `cmp`, `cut`, `grep`, `mktemp`, `cat`, `rm`, `/bin/sh`, and the selected Java runtime. Verify those utilities in the deployed runtime. `Dockerfile.backend-external` copies the entrypoint into its image, so an entrypoint change requires rebuilding that image; the local App role instead reads the entrypoint from its external release. Neither changing source nor preparing a release updates a running container.

For the older shared `/artifacts` volume, the App scope is the common directory of the JAR and web directory. When that scope is the volume root, **all** files in it must be registered in the release inventory and generated output must be outside it; a shared worker or model file is allowed only if registered. If the complete root cannot be bounded and inventoried, use a dedicated App subdirectory rather than silently skipping the check. The check is a startup snapshot and binds JAR/web content to the supplied manifest pin; it does not continuously protect the host files, authenticate the caller or validate the contents of every other registered file. In the local layout the shell has already begun executing the entrypoint itself, so protect and verify that file and the pin source separately.

`delivery.py` verifies a complete artifact inventory and renders CPU resources separately from five GPU Job templates. Applying `kubernetes.json` starts MySQL, Qdrant Server and the backend; the default mode also starts the web app, and the optional coordinator is started only when configured. GPU templates are created individually by `gpu_stage.py`; they are never included in the CPU List. Model/source artifacts mount read-only. NetworkPolicy denies external egress; DNS and traffic within the namespace remain available. The target CNI must actually enforce NetworkPolicy before offline acceptance.

## External application release (opt-in)

To keep frequently edited application artifacts on disk, the manifest may add `"externalApp": {"jar": "releases/r1/app.jar", "webDirectory": "releases/r1/web"}`. Both paths are relative to the artifact volume. Declare the JAR and every regular file under the web directory in `assets`, including `index.html`; extra files, symlinks and path escapes fail preparation. The JAR must not be inside the served web directory. Build the backend image from `Dockerfile.backend-external` using a pinned Java runtime image containing `/bin/sh`, `java`, `find`, `sort`, `cmp`, `mktemp` and `sha256sum`, and the existing fonts. The image contains its boot guard and runtime assets, not the JAR or `dist`. `images.web` is not required in this mode; the frontend is served on the backend Service's port 8080 and `/api` remains same-origin. Route the external entry to `consense-backend:8080` instead of the removed `consense-web:80`, including long-running and streaming requests; gateway configuration and behavior require target validation. Existing manifests without `externalApp` keep the original separate backend and web containers.

The rendered backend mounts the artifact PVC read-only and an immutable, release-named ConfigMap containing the complete expected web file list and SHA-256 checks. Its entrypoint verifies the file inventory and hashes before starting Java, then points Spring's static resource location at the mounted directory. This startup check is not a continuous integrity monitor: the underlying release directory must remain immutable while running, and filesystem permission, volume semantics, executable dependencies and Spring's actual HTTP behavior must be verified on the target. Use a fresh `release` and new artifact directory for each update; do not edit an active release in place. Source, build output, Python coordinator/worker business code, weights and state still have separate ownership; this option externalizes the Java JAR and frontend only, and the current coordinator and GPU images still contain their Python code.

With the coordinator enabled, the opt-in topology renders four fixed application/database/coordinator containers plus one GPU Job while a stage is active. It does not implement a permanently running GPU worker or prove the platform permits sequential Jobs, multiple images, ConfigMaps, shared PVC mounts or this container count. GPU process release and existing request/Job identity rules stay unchanged. No image build, Kubernetes apply, or target acceptance is implied by preparation.

The default separate-web resource plan totals 27 CPU and 242 GB memory while one GPU stage is active without the coordinator, or 27 CPU and 246 GB with the coordinator. The opt-in `externalApp` mode removes the web container's 1 CPU and 2 GB: its totals are 26 CPU and 240 GB without the coordinator, or 26 CPU and 244 GB with it. With a coordinator, the GPU stage requests 14 CPU instead of 15, and the coordinator requests 1 CPU and 4 GB. Fast PVC requests are 50 GB MySQL, 200 GB Qdrant and 400 GB workspace, totalling 650 GB. The artifact PVC requests 1250 GB general storage. These are requested resources, not measured consumption. One replica and Recreate avoid overlapping CPU workers; Vetting's SQL lease/checkpoint ownership still governs publication. PVC RWO is not a process lock. Select the actual H800 node using the manifest's `gpuNodeSelector`; no GPU product label is guessed.

The private manifest contract is `consense-offline-delivery-v1`. It requires:

- `sourceCommit`: full 40-character commit, `namespace` and `release`: bounded Kubernetes names.
- `images`: exact `name@sha256:<64 hex>` for `mysql`, `qdrant`, `backend`, `paddle`, `mineru`, `document-embedding`, `query-embedding-rerank`, `business-llm`, plus `web` in the default mode and `coordinator` when configured. Tags and missing required digests fail preparation. MySQL 8.4 LTS remains a target candidate; the existing JDBC/Flyway chain needs a real migration/restore check before adoption.
- `storageClass`: explicit `fast` and `general` class names; `gpuNodeSelector`: actual target node labels.
- `assets`: complete relative-path, byte-count and SHA256 inventory. Include the source/runtime scripts, Maven/npm/Linux hash locks and their offline artifacts, every model/tokenizer/processor/template/custom-code file, fonts, licenses and frozen source/vector manifests. The tool validates every declared file; inventory completeness and exact image contents still require release review.
- `stages`: exactly the ordered five stage names above. Each declares its existing finite worker `command` array, pinned `runtimeAssets`, complete distinct output file paths in the workspace and a bounded `timeoutSeconds`. Use release-specific output paths so a new generation cannot overwrite old results. A precomputed index alone does not constitute the Paddle/MinerU/native-layout/new-source pipeline.

Prepare in a fresh output directory:

```sh
python delivery.py --manifest /private/delivery-manifest.json --artifacts /artifacts --out /private/prepared-new
```

The artifact PVC must contain the byte-exact prepared manifest at `/artifacts/delivery-manifest.json`. GPU images must include this directory at `/app/offline_delivery` plus their already locked, offline stage environments. The generated Job invokes `stage_worker.py`, which checks the assigned manifest and all declared assets, executes that manifest's actual worker command, and fsyncs declared outputs and raw logs before saving a fresh receipt. Failure, timeout, missing output and pre-existing output cannot produce a `persisted` receipt. A configured image digest is labelled as configured; it is not provider-attested model identity, measured GPU readiness, or source quality.

`gpu_stage.py` defaults to a dry plan and makes no cluster calls. Its `--execute` path checks the live workspace PVC UID and the preceding succeeded Job UID against the actual persisted receipt, re-hashes every output, deletes only that preceding Job with foreground propagation, waits for its pods to disappear, and verifies no labelled GPU owner remains before creating the next Job. A sleep never substitutes for resource release. Duplicate creation fails instead of replacing a running Job. A failed or uncertain previous stage needs a reviewed recovery plan and a fresh generation; it is not retried automatically.

```sh
python gpu_stage.py --prepared /private/prepared-new --artifacts /artifacts --workspace /workspace \
  --stage mineru --expected-manifest-sha256 <reviewed-sha256> \
  --previous-receipt /workspace/previous-paddle.receipt.json
```

The dry plan is reviewable before adding `--execute`. Initial CPU resources, namespace/database secret creation and artifact population are separate, explicitly authorized deployment steps. No secrets are generated or embedded here. The application image Dockerfiles require digest-pinned runtime base images supplied by the build plan; build with network disabled after the exact jar/dist/fonts and base images are present. Final OCI digests, scans, font rendering, full dependency/model inventories, target image loads, GPU readiness/peaks/waiting and offline boot remain acceptance work.

`mysql_backup.py` also defaults to a dry plan. Native dumps use a private client option file and a fresh output directory, keep routines/events/triggers, and save the exact SQL SHA and receipt. Restore requires an externally pinned receipt, unchanged SQL bytes, a distinct empty target database and a target account whose grants are restricted to that disposable schema. Global privileges, roles and grant option fail admission. The target must be isolated from other writers; other users' session visibility can depend on server permissions. Native import completion is not a successful schema migration, row-count check, backup recovery acceptance or cutover.

```sh
python mysql_backup.py backup --defaults /private/source-client.cnf --database consense --out /private/fresh-backup
python mysql_backup.py restore --defaults /private/isolated-target-client.cnf --database consense_restore_new \
  --receipt /private/fresh-backup/backup.json --expected-receipt-sha256 <reviewed-sha256>
```

The restore database must already exist and be independently owned. SQL/model/original-file backups remain in ignored private storage. Failed outputs and earlier receipts are never removed. Read-only originals and fixed vectors are not copied into an active collection by these tools; use the existing `vetting_eval/migrate_qdrant_server.py` and its sealed full readback/query contract under a separate adoption plan.

Run the software boundary checks with `python -m unittest discover -v` from this directory. They use authored artifacts, a real tiny Python child process, and replacement CLI boundaries for Kubernetes/MySQL. They never contact Docker, a cluster, a database, models or OCR services and do not grant target acceptance.

`complete_ocr_config.py --config <private-resolved.json> --out <fresh-directory>` generates `complete-ocr.json`, its exact `CONSENSE_COMPLETE_OCR_CONFIG_SHA256` environment pin, and a reviewable native boot plan. The input has a positive finite `timeoutSeconds` and `paddleocr-vl`/`mineru` rows with a literal-IP `baseUrl`, exact parser version/engine, immutable model SHA or revision and resolved `identity.config`. Existing remote APIs are labelled `remote_handoff_unverified`; their availability never proves single-H800 serialization.

To own local native providers, each row additionally declares `ownedBoot`: an absolute executable/argument `command` array, absolute `cwd`, complete explicit `env`, `readyPath`, finite `readyTimeoutSeconds`, `cleanupTimeoutSeconds` at most 2, and `runtimeAssets` records with absolute `path`, exact `byteCount`/`sha256` and `kind` (`executable`, `runtime`, `config`, `model`). Every model declaration binds `assetPaths` to the pinned model inventory; configuration assets must be referenced by the command or environment. BF16 is the default declaration. The declarations and declared file inventory are verified without claiming complete image/library contents or provider-attested identity.

The runtime helper `vetting_eval/native_provider_lifecycle.py` starts Windows children suspended, assigns them to a Job Object before resuming, and terminates/readbacks the complete Job tree before the next provider. Native Linux requires an explicitly delegated cgroup v2 `cgroupRoot` with `cgroup.kill` and a SHA-pinned admission Python executable (the helper's current interpreter) in `runtimeAssets`; the process confirms entry into its fresh cgroup before native exec. Missing ownership support fails closed. WSL bridge commands, shell boot commands, occupied endpoints and changed declared assets are rejected. Readiness records only wire availability. The caller's remaining total budget covers launch, readiness and API work; final cleanup has its separately reported maximum 2-second budget. Raw child logs and fresh exit receipts survive failure. An unproven tree release raises and prevents the next provider. This helper never kills an existing service or unrelated process.

## Request-bound Kubernetes runtime

`request_stage.py` reuses the same `stage_worker.py` persistence contract for one actual request per GPU Job. A kernel lock on the shared workspace grants one coordinator ownership; stale JSON cannot grant it. The request freezes actual bytes, source declarations, selected profile and their hashes. A response must bind that request, the worker manifest, actual Job/Pod/PVC UIDs and freshly rehashed persisted output. The controller deletes its exact owned Job, waits for its Pods, and reads back that they are absent before returning an HTTP result. Failed or uncertain outputs remain on disk and never authorize the next stage. CPU tests run actual tiny HTTP children and actual CAS files; Kubernetes CLI responses are replacements, so they establish software boundaries only.

The private delivery manifest may provide `requestStages`: stage name to exact `command`, `runtimeAssets` and complete `profile`. Commands are argument arrays and run without a shell. The concrete adapters are:

- `window_operation_worker.py`, profile `adapter=consense.window-runtime.v1`, calls the existing complete `server.index` or `server.retrieve`. Its environment explicitly binds CUDA, dtype, offline mode, `/workspace` state and Qdrant Server. The CPU coordinator retains the existing inference-free health contract with a declared worker recipe; the actual GPU worker checks that complete recipe fingerprint before and after execution. No model loads in the CPU coordinator.
- `native_session_worker.py`, profile `adapter=consense.native-api-session.v1`, keeps one exact native server command alive for the complete Paddle or MinerU API transaction, including uploads and all MinerU polling. `nativeIdentity` must equal the configured provider identity. The complete OCR caller supplies `taskBinding` and only finishes after input, actual HTTP responses and all native output CAS bytes are persisted. The worker independently rereads those CAS bytes and original physical-page binding before exiting. A missing/failed transaction aborts the Job.
- `chat_operation_worker.py`, profile `adapter=consense.native-chat.v1`, launches the pinned local native server, forwards the exact JSON body to an explicitly declared API path, and preserves actual HTTP status/body. Its model, loopback endpoint, native command, offline CUDA environment, readiness path and timeout are explicit. API path mappings do not translate schemas or invent token counts. Unsupported counter protocols fail visibly. This finite Job route supports status plus final output; it rejects `stream=true`. Set `consense.llm.body-streaming-enabled=false`; ordinary remote profiles retain their existing capability.

For native OCR, replace a provider's `ownedBoot` with `ownedJob` containing absolute mounted `prepared`, `artifacts`, `workspace`, exact `manifestSha256`, `stage`, `serviceName`, finite `readyTimeoutSeconds` and `cleanupTimeoutSeconds`. The manifest also declares `nativeServices.paddle/mineru` with exact name, literal ClusterIP and port. The controller matches actual Endpoints target Pod UID and Job owner before using readiness. These Services select only the named release/stage. Kubernetes completion plus actual Pod deletion establishes resource release; a server sleep does not. Local Windows/cgroup ownership remains available and unchanged for non-Kubernetes operation.

An optional `coordinator` manifest section declares `port`, `/artifacts/...` `preparedPath`, complete `runtimeAssets`, environment, `apiServerCidr` (one actual API-server IP), `apiServerPort`, and `backendConfig`. The renderer creates the CPU coordinator Service/Deployment and namespace-scoped Job/Pod/PVC/Endpoint RBAC. Only that Pod gets explicit API-server egress; namespace and DNS traffic retain the existing offline policy. Target CNI/NAT enforcement remains a real acceptance gate. All CPU components select the target node so the shared RWO workspace can be mounted by each sequential GPU Job.

`backendConfig.consense` explicitly uses local `/ocr` and the coordinator retrieval URL, disables default public MiniMax, and either explicitly disables the local LLM or binds its OpenAI base URL to the coordinator root with body streaming disabled and the exact `chat-model` from the native profile. The Java client adds `/v1/chat/completions` and `/v1/models`; including `/v1` in the configured base URL is rejected. The inference-free `/v1/models` catalog exposes only that configured model and explicitly retains `modelReady: false` and `targetAccepted: false`; each actual operation still needs its verified GPU Job result. `SPRING_APPLICATION_JSON` preserves exact property names. `Dockerfile.coordinator` includes the frozen source and pinned kubectl in a prebuilt locked Python image; `Dockerfile.gpu-worker` adds the same code to each separately frozen native runtime image. They install and download nothing. Model/processor/tokenizer/font/native-library inventories and all exact OCI/base-image digests remain required release inputs.

Identity observation uses CPU RapidOCR separately from the detailed Paddle/MinerU tasks. The coordinator environment must bind `CONSENSE_OCR_MODEL_DIRECTORY` under the read-only `/artifacts/` mount and `CONSENSE_OCR_RECIPE_SHA256` to the exact `ocr_runtime.ocr_cache_recipe(directory, require_verified_defaults=True)` identity. Before constructing any engine, the normal `/ocr` route verifies local Det/Cls/Rec weights, dictionary/config/software identity and the recipe pin, then passes explicit `local_paths` to bypass RapidOCR's downloader and repair path. It verifies the recipe again after initialization and keeps the recipe SHA in observation provenance. Missing, corrupt or changed assets fail visibly; an HF offline variable alone is insufficient. Compute the pin in the actual locked runtime image before release; metadata validation does not establish inference or source quality.


Preparation reports implemented adapters and missing runtime configuration separately. `completeNewSourcePipeline` and `targetAccepted` remain false: a configured manifest, authored CPU contract, model-ready response or successful source merge cannot prove the actual offline model/source/MySQL/Kubernetes/H800 chain. No runtime profiles, target ClusterIPs, image digests or model identities have been invented or adopted by these tools.

[External App diagnostic logging](app-competition-logging.md) describes the optional persistent logs and framework exception routing.
