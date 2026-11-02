# 离线部署候选方案

本目录实现 UNI-09 的准备和恢复边界。它不会应用资源、构建镜像、连接运行中的数据库、运行模型、修改 Local05，也不构成 Linux、MySQL、Kubernetes 或 H800 环境验收。所有生成回执均保留 `targetAccepted: false`。

当前比赛五容器打包、相对路径、端口和配置请先查看[最新比赛部署说明](../../../docs/COMPETITION-DEPLOYMENT.md)；以下保留预演操作与历史 Kubernetes 候选方案，不能代替实际交付核验。

## 本机五角色 Docker 预演

[比赛五角色交付与启动](competition-delivery-guide.md) 汇总镜像归档、外置资产、现场配置和参数化创建入口；目标环境尚未验证。

[资料打不开时的人工排查](source-troubleshooting.md)涵盖路径、权限、项目版本和服务状态。

比赛部署时，在 `SPRING_PROFILES_ACTIVE` 中包含 `competition`，并将 `CONSENSE_QWEN_BASE_URL` 设置为实际 vLLM 服务的基础地址，**不要带 `/v1`**；现有 Qwen 客户端会自行追加 `/v1/models` 和 `/v1/chat/completions`。通过现有外置配置设置模型和凭据。此 profile 下地址缺失或为空时，Qwen 配置保持未就绪，请求会明确失败，不会回退到通用云端地址。该 profile 不会自动启用 Qwen，也不会更改 OCR、检索或 GPU 准入。`app-source` / `rehearsal-source` 是本机协议替身预演配置，不能作为比赛 vLLM 地址隐式沿用。优先级更高的 Spring 属性可以覆盖 profile；部署时应核对最终生效值及其来源。配置了 URL 不等于 vLLM 已就绪。

独立比赛预演使用 `Dockerfile.app-role-external`、`Dockerfile.h2-external`、`Dockerfile.qdrant-external`、`Dockerfile.source-external` 和 `Dockerfile.gpu-worker-external`。前三者是最小角色镜像，启动脚本或可执行文件、Java、Qdrant、发布文件、配置与状态由磁盘挂载。创建容器时仍须明确指定绑定挂载、限制、回环监听及密钥。H2 镜像默认最大堆于 2026-11-01 调整为 16 GiB；已有容器在替换前保留原命令。GPU 镜像为固定容器准入契约保留原 Python 启动参数，分别放在 `ENTRYPOINT` 和 `CMD`。构建及运行回执位于被 Git 忽略的 `.local/container-rehearsal-20261029/serial-role-images-20261030/`。2026-10-30 构建五个镜像及运行三个 CPU 角色，并不能证明五容器稳定运行或真实模型验收通过。当前预演状态请查看本地票据看板，并独立检查登记实例。

### App 字体运行环境

五角色 App 的字体运行环境应放在 App 发布目录之外，以只读方式挂载到 `/runtime/font-runtime`。准备 `fonts.conf`、`manifest.json`、`lib/`、`fonts/` 和 `licenses/`。七库候选包含 `libfontconfig.so.1`、`libfreetype.so.6`、`libharfbuzz.so.0`、`libgraphite2.so.3`、`libpng16.so.16`、`libjpeg.so.8` 和 `liblcms2.so.2`；字体包括 DejaVu Sans、Sans Bold、Serif、Sans Mono 和文泉驿正黑。应一并提供所有库和字体对应的版权与许可文件，并在部署记录中记录文件及版本；被忽略的本机候选目录本身不属于仓库。

设置 `FONTCONFIG_FILE=/runtime/font-runtime/fonts.conf`，并在 `LD_LIBRARY_PATH` 中包含 `/runtime/font-runtime/lib`，同时保留其他必需的库路径。提供的 fontconfig 文件从 `/runtime/font-runtime/fonts` 查找字体，并使用 `/runtime/tmp/app/fontconfig` 作为缓存；该缓存目录或其现有父目录必须允许 App 的非 root 用户写入。字体运行环境保持只读，缓存置于其外部的可写目录。创建 App 容器时配置绑定挂载及环境变量；`Dockerfile.app-role-external` 本身不会加入这些内容，已有容器仅重启也不能获得新增的挂载或环境设置。

本机 App 容器曾使用原五库运行环境绘制中英文。随后 PDFBox 渲染真实原件页面时发现缺少 JPEG 和 lcms2 库；独立原件页面探针在七库候选上通过。该运行环境现已挂入本机预演 App，同一 JRE 与 PDFBox 已渲染文档 50 的第一页；业务上传重试已开始，但双路 OCR 结果尚未验证。这些库来自 Ubuntu 24.04，与比赛镜像、其系统库和 ABI 的兼容性以及目标环境渲染仍未验证，须在实际目标上检查，不能由本机结果推定。

### Linux 离线构建入口

准备独立源码副本，提供本机 JDK、Maven、Node、npm 缓存和 Maven 仓库的绝对路径。`npm ci` 会删除并重新安装 `node_modules`；构建会更新所给源码目录中的 `dist` 和 `target`：

```sh
sh scripts/build-competition-offline.sh /path/to/source-copy /path/to/jdk /path/to/maven /path/to/node /path/to/npm-cache /path/to/maven-repository
```

脚本使用 npm 和 Maven 的离线模式，并跳过 Maven 测试执行；所需测试应单独运行。这些参数不会阻止依赖生命周期脚本访问网络，因此真正离线预演时，应隔离构建进程及其子进程的外部网络。记录所用工具与依赖的身份、源码版本及生成的 JAR/web 文件，再按下文准备新发布目录。缓存缺项会导致构建失败；此入口尚未在比赛运行环境完成完整离线构建验证。

### 本机独立 App 发布目录清单

在磁盘上组装**新的**独立 App 发布目录，不要覆盖现有或运行中的发布目录。其顶层包含 `app-entrypoint.sh`、`app.jar`、`web/` 和 `manifest/`。初始 manifest 包含排序后的 `web-files` 列表，以及恰好覆盖 JAR 和所有 web 文件的 `sha256sums`。该根目录只放已声明的普通文件和目录；数据库、上传文件、模型资产、密钥、临时文件和生成的部署输出均放在其外部。保留旧发布版本以供恢复。

从本目录（`ai_consense_service/tools/offline_delivery`）执行：独立确认文件身份后，以固定的预期 entrypoint SHA 和**新的、尚未使用的**发布路径生成完整列表及两个摘要：

```sh
python3 -c 'import json,sys,delivery; print(json.dumps(delivery.prepare_local_app_release_inventory(sys.argv[1],sys.argv[2]), sort_keys=True))' /absolute/path/to/new-app-release EXPECTED_ENTRYPOINT_SHA256
```

该函数拒绝已存在的 `manifest/release-files`、未列入清单的文件及内容不匹配；它仅创建一次该列表，并返回 `releaseFilesSha256` 和 `contentManifestSha256`。独立核验完整发布目录，并将这两个值及 entrypoint 身份保存在单独可信的部署记录中；启动时不能从可能已变化的发布目录反推预期 pin。调用方须传入 `APP_RELEASE_DIRECTORY=.`、`APP_RELEASE_FILES_SHA256=<releaseFilesSha256>`、`APP_CONTENT_MANIFEST_SHA256=<contentManifestSha256>`、`APP_JAR=app.jar` 和 `APP_WEB_DIRECTORY=web`，将该独立根目录只读挂载到 `/artifacts`，并将其 `manifest` 子目录只读挂载到 `/app-files`。缺少清单或 pin 会导致启动失败。真正创建或重启时须核对准确的发布目录、挂载、命令及可信值；历史一次性预演脚本不能作为可复用部署入口。

### 启动和排查已登记的本机 App

以下步骤仅适用于专用预演 daemon 中**已创建且已登记**的 App 容器，不是重新创建命令。根据独立审阅的部署记录，选择完整容器 ID、镜像摘要、独立发布路径、entrypoint 摘要、两个 manifest 摘要及生效配置。不要从正在核验的发布目录取得预期摘要，也不要打印凭据。此前的一次性候选硬编码了历史 CID 和 PID，不能作为当前启动命令重放。

在专用 WSL 发行版中，先确认 daemon 和登记的 H2、Qdrant 实例可用，App 端口空闲，不会与其他 App 实例重叠，运行中容器总数不超过五个。根据记录检查选定的已停止 App 对象：镜像和命令、`consense:consense` / UID:GID 10001:10001、只读根文件系统、来自同一登记发布目录的只读 `/artifacts` 与 `/app-files` 挂载、可写的 App 数据和临时挂载、私有配置，以及 `APP_JAR`、`APP_WEB_DIRECTORY`、`APP_RELEASE_DIRECTORY` 和两个摘要值。在本机比较含密钥的环境或配置时不要显示其值；设置不匹配或未知的对象不能启动。已有 Docker 对象的挂载及环境设置不可变；更换发布目录或 pin 必须使用单独审阅的新对象，不能仅对旧对象执行 `docker start`。

将 `APP_CID` 设为核实的完整 ID，`APP_RELEASE` 设为登记的宿主发布目录，将 `ENTRYPOINT_SHA256`、`APP_RELEASE_FILES_SHA256` 和 `APP_CONTENT_MANIFEST_SHA256` 设为独立记录的预期值。设置后，检查发布目录并启动该准确对象：

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

独立检查容器对象是必要的：上述 shell 变量**不会**更新现有容器的环境。启动后检查实际进程身份及登记挂载，再通过允许的浏览器路径执行并读回票据中的既有业务操作。HTTP 成功不能证明业务保存或私有路径隔离成功。

若启动退出，先检查记录的退出状态、准确挂载，并比较可信 entrypoint 与 manifest 摘要；缺文件、错误 pin 或发布目录中的额外文件可能使入口在 Java 启动前退出，也可能没有有用的应用日志。若进程仍运行但页面失败，检查 App 回环监听及登记的 H2/Qdrant 端点和配置，再检查私有日志中的错误类别。日志可能含敏感值，应限制访问，不要粘贴到公开 issue。保留失败对象及证据；不要覆盖发布目录、删除对象或在结果未知时盲目重复操作。

**证据边界：**2026-10-31 的一次性切换运行了已登记发布版，并检查了进程身份、web worker、项目、一个合成 Qdrant 点及一个 PDF；独立的真实容器额外文件负例以退出码 1 结束。2026-10-30 浏览器保存与读回使用的是更早的 App 实例。上述步骤及当前选定对象、配置和业务操作尚未在当前环境端到端执行；不能把历史检查当作当前可用或完整票据验收。

shell 运行环境必须提供 GNU `find`（`-printf`）、`sort`（`-z`）、`sha256sum`（`--strict`）、`tr`、`cmp`、`cut`、`grep`、`mktemp`、`cat`、`rm`、`/bin/sh` 及选定的 Java 运行时；部署时逐项核对。`Dockerfile.backend-external` 将 entrypoint 复制进镜像，所以 entrypoint 变化需要重建该镜像；本机 App 角色则从外置发布目录读取 entrypoint。仅修改源码或准备发布目录不会更新正在运行的容器。

对旧版共享 `/artifacts` 卷，App 范围是 JAR 和 web 目录的共同目录。该范围等于卷根目录时，其中**所有**文件都须登记到发布清单，生成输出必须在其外部；共享 worker 或模型文件只有登记后才允许存在。若无法界定并清点整个根目录，应使用独立 App 子目录，不能静默跳过检查。该检查是启动时快照，将 JAR/web 内容与所提供的 manifest pin 绑定；它不会持续保护宿主文件、认证调用方或核验其他每个已登记文件的内容。本机布局中 shell 此时已经开始执行 entrypoint，因此还须单独保护并验证该文件和 pin 来源。

`delivery.py` 核验完整资产清单，并分别渲染 CPU 资源及五个 GPU Job 模板。应用 `kubernetes.json` 会启动 MySQL、Qdrant Server 和后端；默认模式也启动 web 应用，可选协调器仅在配置时启动。GPU 模板由 `gpu_stage.py` 单独创建，从不放入 CPU List。模型和来源资产只读挂载。NetworkPolicy 拒绝外部出口，DNS 和命名空间内流量仍可用；离线验收前必须确认目标 CNI 实际执行该策略。

## 外置应用发布（可选）

为将经常编辑的应用产物放在磁盘上，manifest 可增加 `"externalApp": {"jar": "releases/r1/app.jar", "webDirectory": "releases/r1/web"}`。两条路径均相对于资产卷。JAR 和 web 目录下每个普通文件（包括 `index.html`）均须在 `assets` 中声明；额外文件、符号链接和路径逃逸会使准备失败。JAR 不能位于提供静态访问的 web 目录内。使用 `Dockerfile.backend-external` 构建后端镜像，基础 Java 运行时镜像须按摘要固定，并包含 `/bin/sh`、`java`、`find`、`sort`、`cmp`、`mktemp`、`sha256sum` 及现有字体。镜像包含启动防护和运行资产，不包含 JAR 或 `dist`。此模式不要求 `images.web`；前端由后端 Service 的 8080 端口提供，`/api` 保持同源。外部入口应指向 `consense-backend:8080`，而非已移除的 `consense-web:80`，并支持长请求及流式请求；网关配置及行为需在目标验证。未配置 `externalApp` 的现有 manifest 保持原本独立的后端和 web 容器。

渲染的后端以只读方式挂载资产 PVC，并挂载一个不可变、以发布版本命名的 ConfigMap，其中包含完整预期 web 文件列表和 SHA-256 校验值。entrypoint 启动 Java 前核验文件清单与哈希，然后将 Spring 静态资源位置指向挂载目录。此启动检查不是持续完整性监控：运行时底层发布目录必须保持不可变，且须在目标环境验证文件权限、卷语义、可执行依赖及 Spring 的实际 HTTP 行为。每次更新使用新的 `release` 和资产目录，不要原地编辑运行中的发布版本。源码、构建输出、Python 协调器/worker 业务代码、权重及状态仍分别管理；该选项仅外置 Java JAR 和前端，当前协调器与 GPU 镜像仍包含 Python 代码。

启用协调器时，此可选拓扑在 GPU 阶段活动期间生成四个固定的应用/数据库/协调器容器及一个 GPU Job。它不实现常驻 GPU worker，也不证明平台允许顺序 Job、多个镜像、ConfigMap、共享 PVC 挂载或此容器数量。GPU 进程释放及既有请求/Job 身份规则保持不变。准备过程不代表已构建镜像、应用 Kubernetes 资源或通过目标验收。

默认独立 web 的资源计划在一个 GPU 阶段活动期间，不含协调器合计请求 27 CPU、242 GB 内存；含协调器为 27 CPU、246 GB。可选 `externalApp` 模式移除 web 容器的 1 CPU 和 2 GB：不含协调器合计 26 CPU、240 GB，含协调器为 26 CPU、244 GB。含协调器时 GPU 阶段请求 14 CPU 而非 15，协调器请求 1 CPU 和 4 GB。快速 PVC 请求为 MySQL 50 GB、Qdrant 200 GB、workspace 400 GB，合计 650 GB；资产 PVC 请求 1250 GB 普通存储。这些是请求量，并非实测消耗。单副本和 Recreate 避免 CPU worker 重叠；Vetting 的 SQL 租约/检查点所有权仍控制发布，PVC RWO 不是进程锁。通过 manifest 的 `gpuNodeSelector` 选择实际 H800 节点，不猜测 GPU 产品标签。

私有 manifest 契约为 `consense-offline-delivery-v1`，要求如下：

- `sourceCommit`：完整 40 位提交；`namespace` 和 `release`：符合约束的 Kubernetes 名称。
- `images`：`mysql`、`qdrant`、`backend`、`paddle`、`mineru`、`document-embedding`、`query-embedding-rerank`、`business-llm` 的精确 `name@sha256:<64位十六进制>`；默认模式还需 `web`，配置协调器时还需 `coordinator`。标签或缺失的必需摘要会导致准备失败。MySQL 8.4 LTS 仍是目标候选，采用前须真实验证现有 JDBC/Flyway 迁移和恢复链。
- `storageClass`：明确指定 `fast` 和 `general` 类名；`gpuNodeSelector`：目标节点的实际标签。
- `assets`：完整相对路径、字节数及 SHA256 清单，包括源码/运行脚本、Maven/npm/Linux 哈希锁及离线产物、每个模型/tokenizer/processor/模板/自定义代码文件、字体、许可证以及冻结来源/向量 manifest。工具核验每个声明文件，清单是否完整和镜像内容是否准确仍须发布审查。
- `stages`：恰好按顺序包含上述五个阶段名。每个阶段声明现有的有限 worker `command` 数组、固定 `runtimeAssets`、workspace 中完整且互不重复的输出路径，以及有界 `timeoutSeconds`。使用发布版本专属输出路径，避免新生成覆盖旧结果。预计算索引本身不构成 Paddle/MinerU/native-layout/新来源流水线。

在新的输出目录中准备：

```sh
python delivery.py --manifest /private/delivery-manifest.json --artifacts /artifacts --out /private/prepared-new
```

资产 PVC 必须在 `/artifacts/delivery-manifest.json` 包含与准备结果字节完全一致的 manifest。GPU 镜像必须在 `/app/offline_delivery` 包含本目录，并包含其已锁定的离线阶段运行环境。生成的 Job 调用 `stage_worker.py`，核验分配的 manifest 及所有声明资产，执行 manifest 中实际 worker 命令，并在保存新回执前对声明输出和原始日志执行 fsync。失败、超时、缺输出或输出已存在均不能产生 `persisted` 回执。配置的镜像摘要只标为配置值，不是供应方证明的模型身份、GPU 实测就绪或来源质量。

`gpu_stage.py` 默认仅生成预演计划，不调用集群。其 `--execute` 路径将实时 workspace PVC UID 和前一成功 Job UID 与实际持久化回执核对，重新计算每个输出的哈希，仅以前台级联方式删除该前序 Job，等待其 Pod 消失，并确认没有带标签的 GPU owner 后才创建下一个 Job。等待固定时间不能代替资源释放。重复创建会失败，不会替换运行中的 Job。前一阶段失败或状态不明时，需要经审阅的恢复方案和新 generation，不自动重试。

```sh
python gpu_stage.py --prepared /private/prepared-new --artifacts /artifacts --workspace /workspace \
  --stage mineru --expected-manifest-sha256 <reviewed-sha256> \
  --previous-receipt /workspace/previous-paddle.receipt.json
```

添加 `--execute` 前可审阅预演计划。初始 CPU 资源、命名空间/数据库密钥创建及资产填充均为单独明确授权的部署步骤；这里不会生成或嵌入密钥。应用镜像 Dockerfile 要求构建计划提供按摘要固定的运行时基础镜像；在准确的 jar/dist/字体和基础镜像齐备后，断网构建。最终 OCI 摘要、扫描、字体渲染、完整依赖/模型清单、目标镜像加载、GPU 就绪/峰值/等待及离线启动仍需验收。

`mysql_backup.py` 同样默认预演计划。原生导出使用私有客户端选项文件和新的输出目录，保留 routines/events/triggers，并保存 SQL 的精确 SHA 与回执。恢复要求外部固定的回执、未变更的 SQL 字节、独立空目标数据库，以及权限限于该一次性 schema 的目标账号；全局权限、角色和 grant option 均不允许。目标须与其他写入者隔离；其他用户会话的可见性可能取决于服务端权限。原生导入完成不等于 schema 迁移、行数检查、备份恢复验收或切换成功。

```sh
python mysql_backup.py backup --defaults /private/source-client.cnf --database consense --out /private/fresh-backup
python mysql_backup.py restore --defaults /private/isolated-target-client.cnf --database consense_restore_new \
  --receipt /private/fresh-backup/backup.json --expected-receipt-sha256 <reviewed-sha256>
```

恢复数据库必须预先存在并由独立主体拥有。SQL/模型/原件备份留在被忽略的私有存储中；失败输出及早期回执不删除。这些工具不会把只读原件和固定向量复制进活动集合；应在单独的采用方案下使用现有 `vetting_eval/migrate_qdrant_server.py` 及其密封完整读回/查询契约。

从本目录运行 `python -m unittest discover -v` 执行软件边界检查。测试使用编写的资产、真实的极小 Python 子进程以及 Kubernetes/MySQL 的替代 CLI 边界，不会连接 Docker、集群、数据库、模型或 OCR 服务，也不构成目标验收。

`complete_ocr_config.py --config <private-resolved.json> --out <fresh-directory>` 生成 `complete-ocr.json`、其精确的 `CONSENSE_COMPLETE_OCR_CONFIG_SHA256` 环境 pin，以及可审阅的原生启动方案。输入包含正有限值 `timeoutSeconds` 和 `paddleocr-vl`/`mineru` 行，各行提供字面 IP 的 `baseUrl`、精确解析器版本/引擎、不可变模型 SHA 或版本，以及已解析的 `identity.config`。现有远程 API 标为 `remote_handoff_unverified`；其可用性不能证明单 H800 串行运行。

由本机拥有原生 provider 时，每行还须声明 `ownedBoot`：绝对可执行文件/参数的 `command` 数组、绝对 `cwd`、完整显式 `env`、`readyPath`、有限的 `readyTimeoutSeconds`、不超过 2 秒的 `cleanupTimeoutSeconds`，以及含绝对 `path`、精确 `byteCount`/`sha256` 和 `kind`（`executable`、`runtime`、`config`、`model`）的 `runtimeAssets` 记录。每个模型声明通过 `assetPaths` 绑定已固定的模型清单；配置资产必须被命令或环境引用。默认声明为 BF16。系统核验声明及所列文件，但不声称覆盖完整镜像/库内容或证明供应方身份。

运行时辅助程序 `vetting_eval/native_provider_lifecycle.py` 以挂起状态启动 Windows 子进程，先将其加入 Job Object 再恢复运行，并在下一 provider 前终止及读回完整 Job 进程树。原生 Linux 要求明确委派的 cgroup v2 `cgroupRoot`（含 `cgroup.kill`），并在 `runtimeAssets` 中提供按 SHA 固定的准入 Python 可执行文件（该辅助程序当前解释器）；进程在原生 exec 前确认已进入新的 cgroup。缺少所有权支持时按失败处理。WSL 桥接命令、shell 启动命令、已占用端点和变更的声明资产均被拒绝。就绪记录仅证明通信可用；调用方剩余总预算覆盖启动、就绪及 API 工作，最终清理另有单独报告的最长 2 秒预算。原始子进程日志和新退出回执在失败后保留；无法证明进程树已释放时抛错并阻止下一 provider。此辅助程序不会结束已有服务或无关进程。

## 请求绑定的 Kubernetes 运行时

`request_stage.py` 复用 `stage_worker.py` 持久化契约，使每个 GPU Job 处理一个真实请求。共享 workspace 上的内核锁授予一个协调器所有权，过期 JSON 不能授予所有权。请求冻结实际字节、来源声明、所选 profile 及其哈希。响应必须绑定该请求、worker manifest、实际 Job/Pod/PVC UID 及重新计算哈希的持久化输出。控制器删除其准确拥有的 Job，等待其 Pod，并在返回 HTTP 结果前读回确认已不存在。失败或不确定输出保留在磁盘，不能授权下一阶段。CPU 测试运行真实的极小 HTTP 子进程并使用真实 CAS 文件；Kubernetes CLI 响应为替代实现，因此只证明软件边界。

私有交付 manifest 可提供 `requestStages`：将阶段名映射到精确 `command`、`runtimeAssets` 和完整 `profile`。命令为不经 shell 运行的参数数组。具体适配器如下：

- `window_operation_worker.py`，profile `adapter=consense.window-runtime.v1`，调用现有完整 `server.index` 或 `server.retrieve`。环境显式绑定 CUDA、dtype、离线模式、`/workspace` 状态和 Qdrant Server。CPU 协调器保留现有不执行推理的健康契约及声明的 worker 配方；实际 GPU worker 在执行前后核对完整配方指纹，CPU 协调器不加载模型。
- `native_session_worker.py`，profile `adapter=consense.native-api-session.v1`，在完整 Paddle 或 MinerU API 事务期间保持准确的原生服务命令存活，包括上传和所有 MinerU 轮询。`nativeIdentity` 必须等于配置的 provider 身份。完整 OCR 调用方提供 `taskBinding`，仅在输入、实际 HTTP 响应及全部原生输出 CAS 字节持久化后结束；worker 在退出前独立重读这些 CAS 字节及原始物理页面绑定。事务缺失或失败会中止 Job。
- `chat_operation_worker.py`，profile `adapter=consense.native-chat.v1`，启动已固定的本地原生服务，将准确 JSON body 转发到明确声明的 API 路径，保留实际 HTTP 状态及 body。模型、回环端点、原生命令、离线 CUDA 环境、就绪路径和超时均显式指定。API 路径映射不转换 schema，也不虚构 token 数。不支持的计数协议会明确失败。此有限 Job 路径支持状态和最终输出，拒绝 `stream=true`；设置 `consense.llm.body-streaming-enabled=false`，普通远程 profile 保留其现有能力。

对原生 OCR，将 provider 的 `ownedBoot` 替换为 `ownedJob`，其中包含绝对挂载的 `prepared`、`artifacts`、`workspace`、精确 `manifestSha256`、`stage`、`serviceName`、有限 `readyTimeoutSeconds` 和 `cleanupTimeoutSeconds`。manifest 同时用 `nativeServices.paddle/mineru` 声明精确名称、字面 ClusterIP 和端口。使用就绪状态前，控制器先核对实际 Endpoints 目标 Pod UID 与 Job owner。这些 Service 仅选择指定 release/stage。Kubernetes 完成及实际删除 Pod 可证明资源释放，服务等待本身不能。本机 Windows/cgroup 所有权能力对非 Kubernetes 运行仍保持可用且不变。

可选 `coordinator` manifest 节声明 `port`、`/artifacts/...` 的 `preparedPath`、完整 `runtimeAssets`、环境变量、`apiServerCidr`（一个实际 API server IP）、`apiServerPort` 和 `backendConfig`。渲染器创建 CPU 协调器 Service/Deployment，以及命名空间范围的 Job/Pod/PVC/Endpoint RBAC。仅该 Pod 获得明确的 API server 出口，命名空间及 DNS 流量保留现有离线策略。目标 CNI/NAT 的实际执行仍需验收。所有 CPU 组件选择目标节点，使共享 RWO workspace 能被每个顺序 GPU Job 挂载。

`backendConfig.consense` 明确使用本地 `/ocr` 和协调器检索 URL，禁用默认公共 MiniMax，并且要么明确禁用本地 LLM，要么将其 OpenAI 基础 URL 绑定到协调器根地址、禁用 body streaming，且使用原生 profile 中准确的 `chat-model`。Java 客户端会添加 `/v1/chat/completions` 和 `/v1/models`；配置基础 URL 包含 `/v1` 会被拒绝。不执行推理的 `/v1/models` 目录只公开该配置模型，明确保留 `modelReady: false` 与 `targetAccepted: false`；每个实际操作仍需要经验证的 GPU Job 结果。`SPRING_APPLICATION_JSON` 保留准确属性名。`Dockerfile.coordinator` 将冻结源码和固定的 kubectl 放入预构建、锁定的 Python 镜像；`Dockerfile.gpu-worker` 将相同代码加入各自冻结的原生运行时镜像，不安装或下载任何内容。模型/processor/tokenizer/字体/原生库清单和所有准确的 OCI/基础镜像摘要仍是必需发布输入。

身份观察使用 CPU RapidOCR，与详细 Paddle/MinerU 任务分开。协调器环境必须将 `CONSENSE_OCR_MODEL_DIRECTORY` 绑定到只读 `/artifacts/` 挂载内，并将 `CONSENSE_OCR_RECIPE_SHA256` 绑定到准确的 `ocr_runtime.ocr_cache_recipe(directory, require_verified_defaults=True)` 身份。普通 `/ocr` 路径在构造任何引擎前验证本地 Det/Cls/Rec 权重、字典/配置/软件身份和 recipe pin，然后传入显式 `local_paths`，绕过 RapidOCR 下载及修复路径。初始化后再次核验 recipe，并将 recipe SHA 保留在观察来源记录中；资产缺失、损坏或变化会明确失败，仅设置 HF 离线变量不足以满足要求。发布前须在实际锁定运行时镜像中计算 pin；元数据校验不能证明推理或来源质量。


准备结果会分别报告已实现的适配器和缺失的运行配置。`completeNewSourcePipeline` 与 `targetAccepted` 保持 false：配置好的 manifest、编写的 CPU 契约、模型就绪响应或成功的来源合并都不能证明真实离线模型/来源/MySQL/Kubernetes/H800 链路。这些工具没有虚构或采用任何运行 profile、目标 ClusterIP、镜像摘要或模型身份。

[外置 App 诊断日志](app-competition-logging.md)介绍可选持久日志及框架异常路由。
