# 比赛五角色交付与启动

本说明汇总当前预演实际使用的五个镜像、硬盘资产和启动入口，供赛方环境配置时使用。镜像之外的目录、模型、配置和数据必须一并提供；五个微型镜像单独不能启动业务。本说明及配套 `competition-create-role.sh` 尚未在赛方环境从零执行，H800、驱动、网络、扫描受理和真实 Qwen/vLLM 均未验。

**当前实际缺口：**本说明尚不能直接生成五角色的完整现场配置。现有 `fixed_gpu_profile_manifest.py` 只能从已经准备好的 `gpu.json` 派生 profile 摘要；`complete_ocr_config.py` 只校验并固化已提供的 OCR 配置。预演专用换版工具绑定本机容器和目录，不能作为赛方通用配置生成器。创建入口也不自动完成 GPU 设备、cgroup 委派、模型配置或其他四个角色的全部挂载。

**固定标准资料状态：**当前预演 App 的固定标准资料库未启用；部分历史审核发现因此不能通过固定引用的当前版本校验和保存人工处置。恢复时应提供与历史审核记录相匹配的已确认资料及配置，保持来源和版本核对；当前资料位置和赛方适配尚待核实，不得跳过校验冒充可用。

**GPU 可移植性现状（2026-11-01）：**现行 b1 worker 源码支持两种严格绑定容器 ID 的 cgroup v2 布局：本机 cgroupfs `/docker/consense-<name>/<容器ID>` 对应 `/delegated/<容器ID>`，以及 systemd `/system.slice/docker-<容器ID>.scope` 对应 `/delegated/docker-<容器ID>.scope`。systemd 分支还核对真实 scope 与委派目录的文件系统身份；两种布局均要求正确委派、任务子树清理及不可写祖先。其他布局仍须按现场实际适配。本机换版脚本和 `container_admission.py` 仍绑定 WSL/cgroupfs，不是 systemd 环境的通用创建或委派工具。

检索在未观察到可信 GPU PID 时，源码支持原 WSL 驱动路径和 `/runtime/gpu-driver/lib` 下的独立只读驱动目录；后者要求该目录及其子挂载只读、软链接不逃逸目录，且仍须满足 GPU、所属进程树与释放检查。现场应由实际 NVIDIA 驱动和容器运行时提供兼容驱动，准备符合检查的路径，而不是复制本机 `/usr/lib/wsl`；实际软链接布局、权限和驱动能力尚未验证。worker 容器入口仍需要 `/usr/bin/python3`，检索及 OCR 环境使用配置中的 `/runtime/envs/retrieval/bin/python3.12.0` 与 `/runtime/ocr-envs/{paddle,mineru}/bin/python3.12.0`；准备可在目标 Linux 使用的解释器及依赖，并核对符号链接、动态库和配置 pin，不要照搬指向 WSL 宿主的链接。上述源码分支有软件测试，本机只在 WSL 运行；赛方 systemd、H800、驱动和真实模型均未验。目标环境验证前，下面命令不是已验证的一键部署。

## 1. 镜像导入

当前预演所用的五份镜像归档在专用预演机的 `/srv/consense-rehearsal-20261029/evidence/local01/grype-db-import-20261031-a1/input/`。准备赛方硬盘时，将以下五份归档放到部署人员选定的 `IMAGE_DIR`，并以实际核对的文件及镜像 ID 为准。镜像扫描记录不覆盖外置文件，也不代替赛方扫描受理。

| 职责 | 归档 | 预演镜像 ID | 建议标签 |
| --- | --- | --- | --- |
| App | `app-image-scan-attempt1.tar` | `sha256:7ef8a66d1ea612a044b60a4e89e16257b5dbefb349e0f55122e211ce23412395` | `consense-competition/app:demo` |
| H2 | `h2-image-scan-attempt1.tar` | `sha256:965d5a05d1c4de1cdb0cb0a1e334b3a980dc290846195e1085a83eac96c03e21` | `consense-competition/h2:demo` |
| Qdrant | `qdrant-image-scan-attempt1.tar` | `sha256:ee7f83e41b488aa85aabe49cbcee89bfbc50b05fb3b7db80859bdc8f83364bb9` | `consense-competition/qdrant:demo` |
| Source | `source-image-scan-attempt1.tar` | `sha256:c66fd5f63456746f52bb419a47b5ae9c8090eeadd7747e2a547a4c20d5132ecf` | `consense-competition/source:demo` |
| GPU | `gpu-image-scan-attempt1.tar` | `sha256:36e5d4e408c6ff56d0ff20afe179a59fdde3a393b82ca99daf3e837bc4f954d4` | `consense-competition/gpu:demo` |

四份归档没有内置 RepoTag，不能假设导入后自动出现上表标签。以下是目标机的导入示例；导入和按 ID 打标签尚未在目标机验证：

```sh
for role in app h2 qdrant source gpu; do
  docker load -i "$IMAGE_DIR/$role-image-scan-attempt1.tar"
done
docker image tag sha256:7ef8a66d1ea612a044b60a4e89e16257b5dbefb349e0f55122e211ce23412395 consense-competition/app:demo
docker image tag sha256:965d5a05d1c4de1cdb0cb0a1e334b3a980dc290846195e1085a83eac96c03e21 consense-competition/h2:demo
docker image tag sha256:ee7f83e41b488aa85aabe49cbcee89bfbc50b05fb3b7db80859bdc8f83364bb9 consense-competition/qdrant:demo
docker image tag sha256:c66fd5f63456746f52bb419a47b5ae9c8090eeadd7747e2a547a4c20d5132ecf consense-competition/source:demo
docker image tag sha256:36e5d4e408c6ff56d0ff20afe179a59fdde3a393b82ca99daf3e837bc4f954d4 consense-competition/gpu:demo
```

表内 ID 是当前预演 Docker 识别的镜像身份；归档中的 OCI manifest 身份与该运行镜像已核对。不同版本的导入工具可能显示配置对象的另一摘要，不能仅按显示形式推断内容相同；如目标机不接受以上 ID，先核对导入结果与归档 manifest 的对应关系，再使用目标机识别的 ID 打标签。不要以此处示例代替实际导入核验。

## 2. 硬盘资产清单

表中容器路径是本机实际程序使用的路径；赛方宿主目录自行指定并挂载，不需要复用本机绝对路径。`T` 指本机预演根 `/srv/consense-rehearsal-20261029`，仅供定位现有资产，不是赛方路径。不要覆盖运行中的数据库、来源、模型或发布目录；需要历史数据时使用已确认的一致副本。

| 资产 | 本机来源或准备方式 | 容器路径及权限 |
| --- | --- | --- |
| App JAR、网页、启动脚本、清单 | `T/releases/app-release-ticket14-scc-toc-20261101-a1/`（11 月 1 日预演运行版，JAR SHA-256 `ac533e0b645f2407f46358f9180e6c3bf4f4bc161cae9a29d13c0e9a698ef54f`；最终交付以实际选定版本和清单为准） | 整个 release 只读挂 `/artifacts`，其 `manifest/` 另只读挂 `/app-files`；JAR `app.jar`、网页 `web/` |
| App 字体及图像运行库 | `T/candidates/ticket08-font-jpeg-color-runtime-20261101-a1/` | 只读挂 `/runtime/font-runtime`；包含 `fonts.conf`、manifest、字体、版权文件和七个库 |
| Linux 用户态、JRE | 本机 `/srv/consense-docker/test-rootfs/usr`、其 `etc/ld.so.cache`，以及 `T/toolchains/jre-runtime-stage-attempt1/rootfs/` 中 Java 17 与配置 | 角色需要兼容的 `/usr`、`/etc/ld.so.cache`；App/H2 另挂 `/runtime/jre`、`/etc/java-17-openjdk`，均只读 |
| App 配置、历史路径映射、上传和日志 | `T/instances/local01/app-serial02-source-20261030/config/`、`T/candidates/ticket03-retrieval-timeout-20261101-a1/config/`；历史/新上传、日志按所选数据副本准备 | 配置只读挂 `/runtime/config`、`/runtime/app-source`；上传挂 `/runtime/data/app/uploads`、临时目录挂 `/runtime/tmp/app`、日志挂 `/runtime/logs`，按实际需要可写；历史原件只读 |
| H2 | `T/toolchains/h2-2.1.214-stage-20261029-attempt2.jar`，与本机一致的数据库副本 | JAR 只读挂 `/runtime/lib/h2.jar`；数据库及其 tmp 挂 `/runtime/data/h2` 并由 H2 用户可写；App 通过 TCP 接入，不直接打开同一数据库文件 |
| Qdrant | `T/toolchains/qdrant-server-1.19.2-stage-attempt1/qdrant.partial`，以及所选存储、配置副本 | 可执行文件只读挂 `/runtime/bin/qdrant`，配置只读挂 `/runtime/config/qdrant-server.yaml`；数据挂 `/qdrant/storage`、临时目录挂 `/tmp`，由 Qdrant 用户可写 |
| Source 代码与环境 | `T/candidates/ticket07-advice-cancel-source-tools-20261101-a1/tools/`；`T/instances/local01/envs/source-20261030/` 及 identity OCR、extra、GL 依赖 | 对应 `/runtime/tools`、`/runtime/envs/source`、`/runtime/envs/source-identity-ocr`、`/runtime/envs/source-ocr-extra`、`/runtime/envs/source-identity-ocr-gl`，只读；状态目录 `/runtime/state/vetting`、`/runtime/state/advisory` 和 `/runtime/tmp` 可写 |
| GPU 代码、环境及模型 | `T/candidates/ticket07-advice-visible-gpu-tools-20261101-a1/tools/`；`T/envs/retrieval/`、`T/envs/ocr-paddle-adopted-attempt4-20261030/`、`T/envs/ocr-mineru-copy-attempt2-20261030/`；`T/releases/ocr-support-20261030-attempt1/`、`T/releases/ocr-runtime-closure-20261030-attempt1/` 和本机 `.local/oer/model-publications/` | 工具、各环境及已采用模型分别只读挂到 `/runtime/tools`、`/runtime/envs/retrieval`、`/runtime/ocr-envs/{paddle,mineru}`、`/runtime/ocr-models/*`、`/runtime/data/gpu/mineru-models/*`、`/runtime/models`；配套配置、共享任务目录及临时目录按下节配置 |
| 来源状态、索引和任务 | Source 状态及 Qdrant 的一致副本、OCR 输入输出、必要历史索引和当前 receipt | 与其项目、来源版本及 Qdrant 集合一起准备；不能只搬模型或只搬数据库并假设历史引用可用 |

字体目录的七库为 `libfontconfig.so.1`、`libfreetype.so.6`、`libharfbuzz.so.0`、`libgraphite2.so.3`、`libpng16.so.16`、`libjpeg.so.8`、`liblcms2.so.2`。字体包括 DejaVu 和文泉驿；同时保留随目录提供的版权文件。App 设置 `LD_LIBRARY_PATH=/runtime/font-runtime/lib`、`FONTCONFIG_FILE=/runtime/font-runtime/fonts.conf`，并使 `/runtime/tmp/app/fontconfig` 可写。不要只把库放进镜像的 `/usr/lib`：本机 `/usr` 是覆盖挂载，会遮住镜像中相同位置。

上表当前 App release 的 JAR SHA-256 为 `9f6e7ebd212275929ff4009368af0a1426f733812bb55fe60211896b7de12fca`；`APP_RELEASE_FILES_SHA256` 为 `8bd75474a91bb64ce23ed1b1f712b803ba99f1a95ba69f80fd1912363aa4a754`，`APP_CONTENT_MANIFEST_SHA256` 为 `b1261dcc0b5b50262a670a25b6695e7b0687e3236ccbbba1159261bb63a4bb4e`。这三个值只适用于该 release；如采用后续版本，从可信发布记录取对应值。

本机该 release 已在内置浏览器的 HTTP 页面成功打开合成项目的模板条款预览；既有目录版本不匹配提示仍保留。该检查不代表真实模板质量或赛方环境已验。

本机 GPU 还分别挂载 `T/candidates/paddle-libgomp-isolated-20261031-a1/deps` 到 `/runtime/ocr-deps`，`T/releases/source-identity-ocr-gl-20261030-attempt1/lib` 到 `/runtime/ocr-gl`，以及 OCR runtime 的 `config`、`runtime-assets` 到 `/runtime/ocr-config`、`/runtime/ocr-models/runtime-assets`；这些不是模型权重本身。Source 的 `/runtime/state/vetting/ocr-observations` 同时只读挂给 GPU 的 `/runtime/data/gpu/artifacts`，两角色还共享 GPU 工作目录中的 private socket。若现场改变其中任何容器路径，须同步现有配置里的引用和校验值。

OCR 模型的实际映射包括 `ocr-support-20261030-attempt1/paddle-layout`、`paddle-vl` 分别到 `/runtime/ocr-models/paddle-layout`、`/runtime/ocr-models/paddle-vl`，`ocr-support-20261030-attempt1/mineru-small/MinerU-4_models_onnx` 和 `ocr-runtime-closure-20261030-attempt1/mineru-view/MinerU2.5-Pro-2605-1.2B` 到 `/runtime/data/gpu/mineru-models/` 下对应同名目录；前述相对路径均在 `T/releases/` 下。Source 还需实际选用的 `sl-scope.json`、模型 manifest、完整 OCR 配置、GPU profiles、状态和鉴权文件。历史索引依赖的 receipt 是具体项目和版本的证据，迁移时应按实际使用的项目核对，不要固定引用预演项目。

## 3. 现场配置和创建角色

先由现场准备各角色的只读配置和受限凭据文件，再通过 `competition-create-role.sh` 创建。脚本只调用 `docker create`，不复制文件、不生成密钥、不启动或删除容器；它使用上表标签，保留现有各角色入口，并为 H2 显式设置 16 GiB 最大堆。旧 H2 镜像归档自身默认值为 2 GiB，不能只依赖镜像默认命令。若现场需要 32 GiB，可设置 `H2_HEAP=32g`；内存、CPU、进程和线程上限依据冻结基准及现场配额确定，不照搬预演中临时设置的限制。

脚本的其余参数会原样传给 `docker create`，用于现场的 `--mount`、`--env-file`、网络和 GPU 设备等配置。例如，以下仅演示 H2 的路径参数；执行前设置这几个变量并确认实际文件及权限：

```sh
sh competition-create-role.sh h2 --network host --read-only \
  --mount "type=bind,src=$SYSTEM_USR,dst=/usr,readonly" \
  --mount "type=bind,src=$LD_CACHE,dst=/etc/ld.so.cache,readonly" \
  --mount "type=bind,src=$JRE,dst=/runtime/jre,readonly" \
  --mount "type=bind,src=$JRE_ETC,dst=/etc/java-17-openjdk,readonly" \
  --mount "type=bind,src=$H2_JAR,dst=/runtime/lib/h2.jar,readonly" \
  --mount "type=bind,src=$H2_DATA,dst=/runtime/data/h2"
```

调用格式：`sh competition-create-role.sh app|h2|qdrant|source|gpu [docker create 选项...]`。默认名称为 `consense-角色`，可用 `CONTAINER_NAME` 覆盖；采用上述 cgroupfs 布局时 GPU 名称须匹配 `consense-[a-z0-9-]+`，systemd 分支按 scope 和容器 ID 校验。标签前缀可用 `IMAGE_PREFIX` 覆盖。脚本在调用者选项之后固定 `--user consense:consense`。对其他角色，同样按上表及以下清单传入各自实际的挂载、配置和网络参数。这个入口不会自动推断缺失挂载；只有 H2 示例，不代表其他四个角色已具备可运行的完整命令。

| 角色 | 现有入口 | 现场还需配置 |
| --- | --- | --- |
| App | `/bin/sh /artifacts/app-entrypoint.sh` | `APP_JAR=app.jar`、`APP_WEB_DIRECTORY=web`、`APP_ARTIFACT_ROOT=/artifacts`、`APP_FILE_MANIFEST=/app-files`、`APP_RELEASE_DIRECTORY=.`，以及可信发布记录中的 `APP_RELEASE_FILES_SHA256` 和 `APP_CONTENT_MANIFEST_SHA256`；Java PATH、16 GiB 堆、字体变量、数据、日志、Source/Qdrant/H2 地址及凭据 |
| H2 | `/runtime/jre/bin/java ... org.h2.tools.Server` | JRE、H2 JAR、持久库、可写 tmp 和应用数据库凭据；`H2_BIND_ADDRESS`、`H2_TCP_PORT` 可按现场网络覆盖默认的 `127.0.0.1:28682` |
| Qdrant | `/runtime/bin/qdrant --config-path /runtime/config/qdrant-server.yaml` | 服务配置、鉴权、已有集合、可写数据及临时目录 |
| Source | `/runtime/envs/source/bin/python -u /runtime/tools/offline_delivery/source_service_entrypoint.py` | Source 配置、Vetting/Advisory 状态、所需模型和环境；完整 OCR 配置及其 SHA、GPU profile manifest 及其 SHA、共享 socket、Qdrant 地址与受限凭据；确保 Source 和 GPU 的 socket 路径及 UID/GID 对应 |
| GPU | `/usr/bin/python3 /runtime/tools/offline_delivery/fixed_gpu_worker.py --config /runtime/config/gpu.json` | 实际设备/驱动、可用 Python 和编译器、各模型/环境、任务目录及可写 tmp；使用现场容器 ID、GPU UUID、cgroup 委派和 socket 生成 `gpu.json`，并同步 Source 配置及其 SHA |

App 的 Java 路径可设 `PATH=/runtime/jre/bin:/usr/bin:/bin`，堆和临时目录可设 `JAVA_TOOL_OPTIONS=-Xmx16g -Djava.io.tmpdir=/runtime/tmp/app -Duser.home=/runtime/tmp/app`；其中环境文件中的整行是一个变量值。Source 入口必填 `CONSENSE_WORKSPACE_ROOT`、`CONSENSE_SL_SCOPE_MANIFEST`、`CONSENSE_SOURCE_VETTING_STATE`、`CONSENSE_SOURCE_ADVISORY_STATE`、`CONSENSE_SOURCE_VETTING_PORT` 和 `CONSENSE_SOURCE_ADVISORY_PORT`；工作目录和 scope 文件须存在，两状态目录须独立且可写，端口须不同。其他实际配置键包括 `CONSENSE_QDRANT_URL`、`CONSENSE_QDRANT_API_KEY_FILE`、`CONSENSE_COMPLETE_OCR_CONFIG` / `CONSENSE_COMPLETE_OCR_CONFIG_SHA256`、`CONSENSE_FIXED_GPU_URL` / `CONSENSE_FIXED_GPU_CONFIG_SHA256`、`CONSENSE_FIXED_GPU_PROFILE_MANIFEST` / `CONSENSE_FIXED_GPU_PROFILE_MANIFEST_SHA256`，以及现有模型 manifest 和凭据文件配置。Source 使用 Qdrant 时，密钥文件须由 Source 实际 UID/GID 拥有、权限为 `0600`，且不能把密钥直接放入 `CONSENSE_QDRANT_API_KEY` 环境变量。须按现场实际配置生成对应值，不能把本机的 hash 当成改动后配置的 hash。

本机 Source 同时使用了 OCR、本机 Qwen 替身和检索配置；不能为了移除替身而直接丢掉整份配置。赛方须保留或重设 OCR 指向 Source 的 `consense.ocr.mode=local`、`consense.ocr.base-url` 和检索/证据服务地址，同时启用 `competition` profile 并通过 `CONSENSE_QWEN_BASE_URL` 提供实际 vLLM 基址（不含 `/v1`）与认证。核对配置优先级，不能让本机 Source 回环地址成为赛方 Qwen 的默认目标。预演的 `cpu-protocol-stub` 和占位权重不是真实 Qwen。

本机 App、H2、Qdrant、Source 采用 host 网络和本机回环，GPU 用共享 Unix socket。现有 Source 入口固定监听 `127.0.0.1`，H2 默认拒绝非本地客户端；按现有绑定与调用地址直接运行需要使用 host 或共享网络命名空间。若现场采用独立容器网络，仅修改调用 URL 和 H2 绑定地址不足以接通，须按实际网络最小适配 Source 监听及 H2 远程访问控制，并重新核验权限与连接。预演 `gpu.json` 中的容器 ID、GPU UUID、cgroup 和 socket 是本机值，不得照搬。先创建 GPU 容器以获取实际 ID，再按现场 GPU/cgroup 生成配置并在启动 Source 前同步其引用和 SHA；将配置目录而非不存在的单个文件预先挂入容器。cgroup 何时创建及如何委派取决于现场运行时，须按实际平台操作，不能假定创建容器对象后已完成委派。WSL 驱动目录、设备路径和本机 Python 挂载仅用于预演，H800 的设备和驱动由现场容器运行时提供并验证。

建议依次准备并确认 H2、Qdrant、GPU、Source、App；所有运行角色使用 `consense:consense`（UID/GID 10001），总数不超过五个。创建后检查实际挂载、配置和用户，再由操作人员启动各已创建对象；更换挂载或环境需要创建新对象，不是重启旧对象即可生效。按现有应用/Source/GPU 日志排错，必要时保留旧 release 和数据以恢复。五个归档的重新导入、赛方挂载/网络、真实 Qwen、H800 和完整业务均尚待实测。
