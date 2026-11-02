# 比赛五容器交付与部署（2026-11-02 冻结版说明）

本页针对比赛演示交付。北京时间 2026-11-02 12:00 后不再修改、重建或替换本机已冻结的镜像和容器；以下导入与创建命令仅供**目标机器**准备，不应对冻结预演实例执行。五个镜像是启动壳，代码发布物、Linux/JRE/Python 运行环境、配置、模型、原件、数据库和数据目录需另行提供。**当前没有完整的自包含交付目录或经目标环境验证的一键部署**：H800/驱动、GPU/cgroup、现场 vLLM、完整数据和三个模块端到端结果仍待实测。

以 [OER 范围与执行规则](../ai_consense_service/docs/oer-scope-and-rules.md)、[当前实施交接](../ai_consense_service/docs/HANDOFF-ocr-embedding-rerank-20261008.md)及[容器化 spec](../ai_consense_service/docs/specs/competition-container-external-runtime-20261029.md)为准。本机 12:14:48 冻结对象的挂载快照可在本地 `.local/container-rehearsal-20261029/frozen-container-mounts-20261102-1214.json` 查阅；它只记录本机路径和挂载，不应原样当成比赛目录。已有 [五角色详细说明](../ai_consense_service/tools/offline_delivery/competition-delivery-guide.md) 的 release 路径、模型说明和部分验收描述可能早于本次冻结，以本页和实际交付清单为准。

仓库 competition-assets/images/ 已整理五个 tar 和 SHA256SUMS；competition-assets/templates/ 是四份默认模板，competition-assets/drafting-pdf-reference/ 是尚待核对的历史图谱候选。其他运行环境、模型与纯 SL 数据须按最终清单单独提供；目录存在不等于可直接部署。

## 1. 五个镜像及归档

五个 tar 分别交付，其他资产逐文件交付，每个文件不得超过 50 GB。目标 Docker 离线导入前在交付目录核验发布者提供的 `SHA256SUMS`；再逐个导入，核对输出及镜像身份后再打标签。切勿因为 tar 小就认为全部程序或模型已包含在镜像中。现有 tar 及哈希在本机候选目录 `.local/container-rehearsal-20261029/ticket10-five-images-delivery-20261101-a1/`，尚未做独立目标导入实测。

```sh
# 在克隆的仓库根目录执行；如果发布资产被单独移动，请调整此路径。
REPO_ROOT="$(pwd -P)"
cd "$REPO_ROOT/competition-assets/images"
sha256sum -c SHA256SUMS
for role in app h2 qdrant source gpu; do docker load -i "$role.tar"; done
```

| 角色 | 预演冻结镜像 ID | 建议标签 |
| --- | --- | --- |
| App | `sha256:7ef8a66d1ea612a044b60a4e89e16257b5dbefb349e0f55122e211ce23412395` | `consense-competition/app:demo` |
| H2 | `sha256:965d5a05d1c4de1cdb0cb0a1e334b3a980dc290846195e1085a83eac96c03e21` | `consense-competition/h2:demo` |
| Qdrant | `sha256:ee7f83e41b488aa85aabe49cbcee89bfbc50b05fb3b7db80859bdc8f83364bb9` | `consense-competition/qdrant:demo` |
| Source | `sha256:c66fd5f63456746f52bb419a47b5ae9c8090eeadd7747e2a547a4c20d5132ecf` | `consense-competition/source:demo` |
| GPU | `sha256:36e5d4e408c6ff56d0ff20afe179a59fdde3a393b82ca99daf3e837bc4f954d4` | `consense-competition/gpu:demo` |

不同 Docker 版本对导入后 ID 的显示可能不同：先根据导入结果与归档清单核实，再 `docker tag <确认的镜像ID> <上表标签>`；不要猜测标签对应关系或覆盖已有同名版本。

如确需重新从**指定 Docker daemon 的现有镜像**导出 tar，可以在导出机执行下列示意命令；替换 `DOCKER_HOST` 与角色身份，必须先核对 daemon 和镜像身份，不能对运行容器执行 `docker commit` 或修改镜像：

```sh
# 在导出机上设置一个可写的独立交付目录，按实际情况更换路径。
DEPLOY_ROOT="/path/to/consense-delivery"
mkdir -p "$DEPLOY_ROOT/images"
DOCKER_HOST=unix:///run/consense-docker/docker.sock docker image inspect sha256:7ef8a66d1ea612a044b60a4e89e16257b5dbefb349e0f55122e211ce23412395
DOCKER_HOST=unix:///run/consense-docker/docker.sock docker image save -o "$DEPLOY_ROOT/images/app.tar" sha256:7ef8a66d1ea612a044b60a4e89e16257b5dbefb349e0f55122e211ce23412395
# H2、Qdrant、Source、GPU 分别用上表对应的已核实镜像 ID 导出为同名 tar。
cd "$DEPLOY_ROOT/images"
sha256sum app.tar h2.tar qdrant.tar source.tar gpu.tar > SHA256SUMS
sha256sum -c SHA256SUMS
```

这里的 daemon 路径只属于本机预演；其他主机使用自己的 Docker 连接。已有归档优先复用，本命令没有在目标环境重跑。导出并校验五个 tar 后仍须检查每个文件大小和完整外置资产。

## 2. 目录、相对路径和挂载

目标宿主机可以任选有足够空间的目录，示例以部署根 `DEPLOY_ROOT` 为基准：

```sh
# 在目标机执行，改成实际交付目录。该命令只设置当前 shell 变量。
DEPLOY_ROOT="$(cd /path/to/consense-delivery && pwd -P)"
printf '%s\n' "$DEPLOY_ROOT"
```

建议把镜像放 `images/`，应用发布物 `release/app/`，运行时和工具 `runtime/`，配置 `config/`，模型 `models/`，预置模板及 SL `assets/`，可写状态 `data/`、日志 `logs/`、临时目录 `tmp/`。这只是**建议的目标布局**，须先按逐文件清单收集，不能假设现有候选已经位于这些相对路径。启动前通过 `DEPLOY_ROOT` 拼出 Docker 所需的绝对宿主路径；容器内目标路径保持与配置一致。更新路径时同步配置、清单和 SHA，尤其不能只改挂载目标而不改引用。路径有空格时引用整个变量。

| 部件 | 容器内目标 / 模式 | 实际需要 |
| --- | --- | --- |
| App 发布版 | `/artifacts`、`/app-files`，只读 | 前者含 `app-entrypoint.sh`、`app.jar`、`web/`、`manifest/`，后者指向同一发布版的 `manifest/`；冻结 JAR SHA-256 为 `479ac646c134a4edf581160b143e94c99dd09920fbbc4887c3edf0e0c67f69d1`。两项 manifest pin 应从此发布版的可信记录读取，不可使用旧 release 的值。 |
| 固定模板与图谱 | `/runtime/drafting-defaults`、`/runtime/drafting-pdf-reference`，只读 | NTT、SCT、Word SCC、PDF SCC 四原件；历史 PDF 图谱仅候选。图谱的变量、原子能力和影响范围仍须人工核对，SCC 每项目二选一，替换模板需重新绑定。 |
| App 配置和渲染 | `/runtime/config`、`/runtime/app-source`、`/runtime/competition-fixed`、`/runtime/renderer-config`、`/runtime/logging-config`、`/runtime/font-runtime`，只读 | 现场脱敏配置、日志配置、字体、动态库及外置 LibreOffice；App 的 `/tmp` 和 `/runtime/tmp/app` 均需可写。合成 DOCX→PDF 曾通过，但实际 SCC PDF 因缺字体仍失败。 |
| App 数据 | `/runtime/data/app/uploads`、`/runtime/logs`、`/runtime/tmp/app`，可写 | 由非 root 用户访问的独立新目录；不要把历史项目与本机密钥默认带进比赛。 |
| H2 | `/runtime/jre`、`/etc/java-17-openjdk`、`/runtime/lib/h2.jar`、`/runtime/classes`，只读；`/runtime/data/h2` 可写 | App 通过 H2 TCP 连接，不让多个进程同时直接打开同一数据库文件。使用匹配的初始化库及凭据，不能把本机项目库当纯净种子。 |
| Qdrant | `/runtime/bin/qdrant`、`/runtime/config/qdrant-server.yaml`，只读；`/qdrant/storage`、`/tmp` 可写 | 赛用独立存储中恢复纯 SL 集合及其来源；核对版本、向量维度及所选单一 SL。预演二进制源文件名虽然带 `.partial`，挂载位置为 `/runtime/bin/qdrant`，不可把它与未完成压缩包混为一谈。 |
| Source | `/runtime/tools`、`/runtime/envs/source*`、`/runtime/config/*`、`/runtime/models`，只读；`/runtime/state/vetting`、`/runtime/state/advisory`、`/runtime/tmp` 可写 | 本次冻结的 Source 工具、Python、OCR、SL catalog、scope 和索引配置；两个业务状态目录独立。Source 与 GPU 共享任务目录/private socket，并保护访问权限。 |
| GPU | `/runtime/tools`、`/runtime/envs/retrieval`、`/runtime/ocr-envs/{paddle,mineru}`、`/runtime/models`、`/runtime/ocr-models/*`、`/runtime/ocr-config`，只读；`/runtime/data/gpu`、`/runtime/tmp/gpu` 及符合运行时要求的 `/delegated` 可写 | 同时提供 BGE-M3、reranker、Paddle R76、重训 R153 adapter **及** MinerU 基础模型/ONNX、layout、配置、依赖和 tokenizer。R153 重训产物不等于原已删除权重。设备驱动应由现场 NVIDIA 运行时提供，不带本机 WSL 驱动。 |
| 系统运行环境 | 各角色的 `/usr`、`/etc/ld.so.cache` 及 App/H2 JRE，只读 | 本机镜像依赖外置 Linux 用户态；GPU 的 `/usr` 与其他角色不同。必须准备与目标 Linux ABI、Python/JRE 及原符号链接兼容的运行树并核验可执行权限。 |

具体的本机来源、容器内目标和候选缺项见本地 `.local/container-rehearsal-20261029/ticket10-delivery-next-20261102-a1/MOUNT-MAPPING.md`、`README.md`；这些文件仅定位候选，不证明它们已进入仓库或交付包。固有的绝对路径（包括 SL catalog、索引和来源引用）需要按目标布局重写并验证，不等于复制目录后自动可用。保留文件模式、软链接与许可证；运行状态、凭据和历史项目数据不应混入预置资产。七版 SL 的纯数据可供不同项目选择，每个项目只选一版；项目资料及向量分别按项目和来源版本隔离。

## 3. 网络、端口和 Qwen

本机五角色使用 host 网络/回环与共享 Unix socket，历史预演端口如下，部署时先核对实际配置和占用：

| 用途 | 本机 WSL 端口 / 通道 | 访问 |
| --- | --- | --- |
| App | TCP `28680` | 外部入口；本机浏览器曾使用 WSL 内网地址的临时诊断桥 28685 转至 App 回环 28680，这不是通用容器端口映射。目标应按现场提供访问入口。 |
| Source Vetting / OCR | TCP `127.0.0.1:28681` | 内部服务，不向外公开。 |
| H2 TCP | `127.0.0.1:28682` | 内部服务，凭据和可写持久数据由现场提供。 |
| Qdrant HTTP | `127.0.0.1:28683` | 内部服务，按配置鉴权。 |
| Source Advisory | TCP `127.0.0.1:28685` | 内部服务；WSL 回环地址与历史诊断桥的内网地址是不同绑定，不能视为同一个监听。 |
| GPU worker | Source/GPU 共享私有 Unix socket | 不要假设另有 GPU TCP 端口；socket 路径及双方身份按实际配置绑定。 |

`--network host` 模式下 Docker 不使用 `-p` 端口映射，监听的是目标机网络命名空间中的端口。若现场要求容器 bridge 网络，当前 Source 监听回环与 H2 的本机访问限制不能只靠改 `-p` 接通，必须先适配监听/访问控制并真实复核，不能擅自放宽数据库暴露。反向代理/防火墙应只开放必须的 App 入口；端口号和地址以现场实际配置为准。

本机复测使用官方 Qwen `qwen3.8-27b`；比赛通过外置配置切回同模型自部署 vLLM。启用 `competition` profile，设置 `CONSENSE_QWEN_BASE_URL` 为实际服务基址，**不要加 `/v1`**，客户端会追加 `/v1/models` 和 `/v1/chat/completions`；再按配置启用模型与鉴权并核对生效优先级。GPU 模型服务、请求头超时和实际输出均须实测。密钥通过目标安全渠道单独提供，不写进 README 或提交明文。

### 配置键示例与相对路径（需现场填值）

现有 [`ai_consense_service/.env.example`](../ai_consense_service/.env.example) 用于普通 H2/MiniMax relay，Spring Boot 不会自动读取它；[`app-rehearsal-source.env`](../ai_consense_service/tools/offline_delivery/app-rehearsal-source.env) 只含本机 OCR 三项，不是比赛完整配置。以下是需提供的**部分键名**，`<...>` 为占位符，不能原样启动；五角色的完整挂载、manifest、OCR、GPU 设备、cgroup 和权限仍须按上文配置。系统变量是容器环境变量或外置配置中的键值，配置文件需实际挂载并传入进程，创建前修改的值才会进入该容器。

```dotenv
# App: 按现场配置合并其他已需 profile，不要遗漏 competition。
SPRING_PROFILES_ACTIVE=h2,competition
CONSENSE_QWEN_ENABLED=true
CONSENSE_QWEN_MODEL=qwen3.8-27b
CONSENSE_QWEN_BASE_URL=http://<实际vllm地址及端口>
# 从现场受限渠道注入实际值；不要将凭据放入版本控制。
CONSENSE_QWEN_API_KEY=<现场单独提供>
CONSENSE_H2_JDBC_URL=jdbc:h2:tcp://127.0.0.1:28682/<现场数据库路径>
SPRING_DATASOURCE_PASSWORD=<现场单独提供>
CONSENSE_VECTOR_BASE_URL=http://127.0.0.1:28683
CONSENSE_VECTOR_API_KEY=<现场单独提供>
CONSENSE_OCR_ENABLED=true
CONSENSE_OCR_MODE=local
CONSENSE_OCR_BASE_URL=http://127.0.0.1:28681
APP_JAR=app.jar
APP_WEB_DIRECTORY=web
APP_RELEASE_DIRECTORY=.
APP_RELEASE_FILES_SHA256=<此发布版release-files的可信SHA256>
APP_CONTENT_MANIFEST_SHA256=<此发布版sha256sums的可信SHA256>
```

Qwen 客户端在基础地址后自行添加 `/v1/models` 和 `/v1/chat/completions`；即使现场 vLLM 不校验鉴权，当前 Qwen 配置选择仍要求 `CONSENSE_QWEN_API_KEY` 非空，并发送 Bearer 值，须按现场服务要求配置（`LlmProfiles.java`、`QwenChatClient.java`）。`APP_*` 两个 SHA 不能从其他 release 沿用；还需 Java `PATH`、heap、字体、日志、H2 TCP 与实际 Spring 额外配置，详见 `app-entrypoint.sh` 和五角色说明。

```dotenv
# Source: 绝对路径都是容器内路径；目录和文件必须在创建容器前准备并挂载。
CONSENSE_WORKSPACE_ROOT=<容器内已存在的工作目录绝对路径>
CONSENSE_SL_SCOPE_MANIFEST=/runtime/config/sl-scope.json
CONSENSE_SOURCE_VETTING_STATE=/runtime/state/vetting
CONSENSE_SOURCE_ADVISORY_STATE=/runtime/state/advisory
CONSENSE_SOURCE_VETTING_PORT=28681
CONSENSE_SOURCE_ADVISORY_PORT=28685
CONSENSE_QDRANT_URL=http://127.0.0.1:28683
CONSENSE_QDRANT_API_KEY_FILE=<容器内独立密钥文件绝对路径>
```

两个 Source 状态目录必须分别存在、可写且不能互为父目录，端口必须不同；Qdrant 凭据文件须非链接、由运行用户拥有、权限 `0600`，不得以 `CONSENSE_QDRANT_API_KEY` 环境变量直接传给 Source。Source 固定监听 `127.0.0.1`，以上地址只适用于共用网络命名空间的方案；依据 `source_service_entrypoint.py`。其他实际必需项包括 `CONSENSE_COMPLETE_OCR_CONFIG` / `CONSENSE_COMPLETE_OCR_CONFIG_SHA256`、`CONSENSE_FIXED_GPU_URL` / `CONSENSE_FIXED_GPU_CONFIG_SHA256`、`CONSENSE_FIXED_GPU_PROFILE_MANIFEST` / `CONSENSE_FIXED_GPU_PROFILE_MANIFEST_SHA256` 与模型路径；这些 SHA 必须针对**最终的目标配置内容**计算并经部署记录核对，不能照抄本机值。

部署根路径在目标宿主机解析成绝对路径后用于 `docker --mount` 的 `src=`；示例 `"$DEPLOY_ROOT/release/app"` 不会自动生成发布目录。容器内部应用、配置和 manifest 使用上文的固定目标路径；改路径必须同步重绑配置和原件来源身份。密钥和目标 UUID、CID、socket、数据库位置使用现场独立注入的值。


## 4. 创建、启动与权限

提供目标 Linux 所需 Docker、NVIDIA 驱动及容器运行时。现场准备 GPU 设备、容器自身 ID、UUID、cgroup v2 委派、共享 socket、配置文件和各角色数据目录；预演 GPU 配置中的 CID、路径和 WSL 驱动不能直接复制。全部运行进程使用 `consense:consense`（UID/GID 10001）；可写目录须在运行前由现场存储管理员赋予实际权限。预演 App 最大堆为 32 GiB，H2 16 GiB；按现场 RAM 为堆外、检索/OCR 和宿主预留空间，结合实际配额决定 CPU 和内存限制。

已提供的 `ai_consense_service/tools/offline_delivery/competition-create-role.sh` 只是参数化 `docker create` 入口：`sh competition-create-role.sh app|h2|qdrant|source|gpu [docker create 选项...]`。它固定非 root 身份，并不生成模型配置、所有挂载、设备/cgroup、密钥或启动服务；现有[详细说明](../ai_consense_service/tools/offline_delivery/competition-delivery-guide.md)有 H2 单角色示例。它不能作为完整一键部署。依次准备 H2、Qdrant、GPU、Source、App；先逐个核对命令、文件、网络和权限，再由现场启动对应对象，总数不得超过五个。受控升级前先处理在途任务，保留数据和失败记录。

App 示例的两个只读挂载如何从相对布局解析（**并非完整创建命令**）：

```sh
--mount "type=bind,src=$DEPLOY_ROOT/release/app,dst=/artifacts,readonly"
--mount "type=bind,src=$DEPLOY_ROOT/release/app/manifest,dst=/app-files,readonly"
```

实际还需对应用户态、JRE、配置、环境变量、字体/renderer、数据库和可写数据的全部挂载；不要复制本机容器的 secret 挂载或旧项目状态。部署后分别检查容器身份、监听、外置文件读写、服务日志、来源和项目隔离，并从前端验证 Drafting、Vetting、Advisory 的真实输出；存活、健康检查或镜像导入不能代替业务结果。

## 5. 已知未完成与证据边界

- 模型、原件、所有外置运行时与纯 SL 数据尚未形成完整逐文件白名单及逐文件 50 GB 检查；两份 SL 快照需在目标 Qdrant 恢复并重绑来源路径。
- Drafting 图谱尚未全部人工确认，三份草稿尚无完整导出；SCC 真实 PDF 预览仍缺字体。Vetting 新任务及 Advisory 仍未取得完整结果。
- 官方 Qwen 请求曾约 600 秒无响应后 I/O 失败，根因未明；比赛 vLLM 是另一实际链路，需分别验证。GPU、cgroup、符号链接、现场配额和所有目标挂载仍需实际验证。
- 五镜像、代码、配置、权重和数据应以已核对的发布清单为准。不要将旧回执、历史实验、未完成的 `.partial` tar 或项目答案当作完整赛用资产。
