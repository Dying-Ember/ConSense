# 比赛外置运行：票据 01 方案决定

**状态：**2026-10-29 的本机运行方案决定及后续验证约定；不是业务部署回执，不关闭票据 01，也不授予 02–04 开工或通过状态。本文只新增运行决定，不修改实现、活动服务、数据库或 Git 引用。

依据：[实施说明](competition-container-external-runtime-20261029.md)、[票据 01](../../../.scratch/competition-external-runtime/issues/01-baseline-and-runtime-plan.md)、[目标架构候选图](competition-container-target-architecture-20261029.md)、[文件布局](competition-file-layout-20261029.md)。冻结对照仍为 `8a40dfcca04730ba7b40cdefb36b596a37bbcb53`。此前取消的未完成工作不恢复；保留失败、未知和历史证据，不以旧质量 pins 为零作为本次运行改造的前置门。

## 选定职责与边界

本机采用 **四个常驻职责容器加一个固定 GPU 容器**。固定容器按阶段运行现有启动和进程所有权机制，使用相互隔离的 Python 环境；不为每次任务另建 Docker 容器。选择理由是：现有 ownedBoot／LinuxTree 机制已有可复用路径，非 root 精确 cgroup 委派的 CPU 探针已证明最小可行接缝；改接现有阶段入口比引入 Docker 临时任务、绕接 Kubernetes 真实 Job 身份以及开放宽权限 Docker socket 更小。该选择不等于 GPU 模型与依赖共存、统一准入或业务链已经通过。

| 位置 | 职责 | 本机决定与后续条件 |
| --- | --- | --- |
| 1 App | Java 业务、完整网页、既有起草／审核／咨询接口 | 外置 JAR、完整网页及必需业务资源；由同一 Java 服务提供网页，不再加网页容器。 |
| 2 资料／协调 | 保留现有资料读取、原文原页、索引与任务协调 | 外置相邻的 `vetting_eval/` 与 `offline_delivery/`；不能用有限离线入口替代冻结基线的完整资料入口。 |
| 3 关系数据库 | 独立服务管理持久关系数据 | **同版 H2 TCP server 容器**，从受保护副本建立新的可写实例；不是 H2 内存库，也不是 MySQL。 |
| 4 向量数据库 | Qdrant server 与持久向量数据 | 实际 server、版本和客户端适配在后续验证；既有 local Qdrant／SQLite 数据不得直接当作 server storage 使用。 |
| 5 GPU | 已采用双路 OCR、embedding、rerank、业务模型调用 | 一个固定容器，复用现有阶段启动与进程树所有权；按 Paddle／MinerU／检索三类依赖隔离 venv；现有已登记隔离环境只作版本来源，未证明三环境已在本次容器建成。真实 vLLM 另核依赖，不能假定任一 venv 已兼容。本机 Qwen 仅为标记占位和协议替身；替身也在此位置计数。 |

正常状态总数为 5；未启 GPU 时为 4。这是设计计数，不是全生命周期已验。初始化、离线构建、调试、换版、失败恢复和平台注入容器也计入上限；无空位时先停止并确认旧实例退出，再启动替换实例，不能启动第六个。无需运行构建容器的外置构建工具优先；确需容器则先释放槽位。赛方计数、权限和网络口径仍待确认。

票据 04 必须保全所有 GPU 入口共用的准入、队列／拒绝语义、真实任务身份、逐段输出、取消、超时、失败落盘及资源释放。只验证一个阶段或一个入口不足以关闭。无法确认旧进程与显存释放时，停止后继阶段并保留失败；不通过关闭流式、停止或已有功能解决接线问题。

## H2 TCP 选择与现有程序依赖

实施说明将“应用、资料协调、MySQL、Qdrant、GPU”明确写为可研究方案，同时禁止无条件数据库迁移；票据 02 要求真实持久关系库，13 要求选定持久关系库并明确排除 H2 **内存**替代。因此本机选同版 H2 文件持久化加 TCP server 符合本次范围，避免将 H2→MySQL 迁移加入外置改造。候选图中的 MySQL 是此前候选，不能据图把当前 H2 文件称为 MySQL 数据。

已核源码：`pom.xml` 同时含 H2 与 MySQL 驱动；`application-h2.yml` 使用 `org.h2.Driver`、`H2Dialect`、禁用 Flyway，且已有 `CONSENSE_H2_JDBC_URL` 覆盖入口。可保持 H2 profile，以外部配置改连 TCP 持久实例。原有 `MODE=MySQL` 仅是 H2 兼容模式，不是数据库引擎身份。默认 file URL 的 `AUTO_SERVER=TRUE` 不应机械复制进 TCP URL；按同版 H2 的实际支持项验证。H2 内部 LOB／Trigger 依赖要求 server JAR 与当前实际数据库／客户端版本逐一登记并匹配，不能随意升级 H2。若赛方后续明确强制 MySQL，再针对该新约束评估迁移。此处只确认可采用的接缝，未证明 TCP 连接、触发器、自动建表、历史业务读写或重启恢复通过。

后续先在新可写副本验证连接、鉴权及网络限制、schema 行为、LOB／来源摘要触发器、代表性读写与重启重建后重读。不得对只读基线运行 `ddl-auto:update`。数据库服务只挂自身目录，App 不直接打开同一 `.mv.db` 文件；真实连接凭据单独受限提供，不照搬示例空密码暴露服务。

## 专用硬盘、挂载和生效表

逻辑根 `T` 为本次任务独占的 Linux 预演根，固定为任务发行版 ext4 `/srv/consense-rehearsal-20261029/`；部署 ID 为 `r1-20261029`，实例为 `local01`。Windows 外置准备根为仓库忽略目录 `.local/competition-external-runtime-20261029/`，已有探针证据另存 `.local/container-rehearsal-20261029/`，不混作服务数据目录。目标资产与实际来源逐项见[资产及保护登记](../testing/competition-protected-assets-20261029.md)及其私有清单；目标创建、复制、业务权限和挂载由后票实施，不因登记完成便声称已运行。物理 VHD 位于 D 盘任务目录。D 盘忽略目录的只读 H2／source／index 基线保留，运行副本另拷入 ext4，不以 DrvFS 属主证明 POSIX 权限。source／models／toolchains 按登记位置和实际 ABI 核验。下表是职责约定，实际固定目标以逐项清单为准。不同容器视图通过逐文件哈希与真实读取核对，不能仅靠路径字符串相同判定。数据库、来源、模型、证据及秘密均在忽略目录，禁止写入网页目录。

| 资产／生产者 → 读取者 | T 下宿主位置 → 容器位置 | 权限与 tmp | 身份与生效 |
| --- | --- | --- | --- |
| 源码／离线构建 → 发布准备 | `source/`、`build-tools/`、`build-cache/` → 构建所登记路径 | 源码／工具受控；独立可写构建输出／tmp | JDK、Maven、Node/npm、锁文件与缓存清单；Java、网页分别重建完整版本。 |
| 发布准备 → App | `releases/<id>/app.jar`、`web/`、业务资源、字体 → `/runtime/release/` | RO；`data/app/`、`tmp/app/` 独立 RW | 源码提交、产物 SHA、网页清单；处理在途任务后重启／切换，保留旧网页资源。 |
| 发布准备 → 协调与 GPU | `releases/<id>/tools/{vetting_eval,offline_delivery}/` → `/runtime/tools/` | RO，保持相邻导入；各角色独立 `tmp/` RW | 实际脚本与相邻导入清单；新版本经停任务／重启生效，禁止运行中原地覆盖。 |
| 配置维护 → 各角色 | `config/<id>/`、受限 `secrets/` → `/runtime/config/`、`/run/secrets/` | RO；只授权读取的角色可见 | 记录覆盖顺序、脱敏有效值与配置摘要；按实际读取时机重启或新任务，秘密不进日志。 |
| 模型准备 → GPU | `models/<model-id>/` 与 `envs/<lock-id>/` → `/runtime/models/`、`/runtime/envs/` | RO；显式 `tmp/gpu/`／缓存 RW | 完整权重、tokenizer、模板、锁和资产清单；阶段重新加载后核对。Qwen 本机占位单列，禁止真加载。 |
| 保护副本 → 恢复准备 | `baseline/<cut-id>/` → `/baseline/`（仅恢复准备需要） | 始终 RO，不作服务数据目录 | H2、副本原件、索引各自清单与切点；不覆盖活动源或原只读副本。 |
| 恢复准备／H2 → H2 server | `instances/<run-id>/h2/` → `/runtime/data/h2/` | 仅 H2 RW；`tmp/h2/` RW | 同版 server／client JAR SHA、库身份、恢复点；停止后备份，不能以旧程序回退代替数据库回退。 |
| 恢复准备／Qdrant → Qdrant server | `instances/<run-id>/qdrant-server/` → `/qdrant/storage/` | 仅 Qdrant RW；独立 tmp | server／client 版本、collection／模型维度、seal；local SQLite 保留或受控迁移／读取方式须验证，不混写格式。 |
| 上传／资料／GPU → App 与资料读取 | `instances/<run-id>/files/`、`tasks/`、`results/` → 各角色登记的 `/runtime/data/...` | 生产者最小 RW；读者 RO 或按职责细分；tmp 分角色 | 原件 hash、来源／项目／版本、任务／输出身份；实际重读和历史绝对路径映射另验。 |
| 各程序 → 排障／证据保护 | `logs/<run-id>/`、`evidence/<run-id>/` → `/runtime/logs/`、受限证据目录 | 本角色 RW，证据单独保护 | 请求／任务、版本、起止／错误／取消；脱敏、轮转、磁盘不足另验，成功须在关键证据落盘后发布。 |

所有实际容器规划 `consense` UID/GID `10001:10001`，包含数据库和初始化职责。已测通用镜像不代替各业务镜像；后续逐角色核验 `/proc` 实际身份、目录和设备权限，不以 Dockerfile USER 声明作为实际通过证据。宿主准备权限与容器非 root 身份分别记录。

## 外置依赖与必要镜像内容

业务 JAR、网页、Python、项目启动脚本、规则／提示策略、配置、字体、模型与配套、构建工具及可搬运依赖优先外置。Linux venv 必须按实际绝对路径／ABI 准备，Windows venv 不能直接挂入代替；只读 venv 也需登记可写缓存位置。H2 server JAR、Qdrant 可执行程序及可外置的配套库亦先评估外置，不能因属于数据库就默认必须烘进镜像。

用户空间外置接缝已有实际证据：`isolated-docker-01/external-runtime-e41f/` 的正例使用 20,480 字节镜像 tar，18 个条目仅有 12 目录、4 个 merged-usr 链接和两份账户文件；`/etc/passwd` 47 字节、`/etc/group` 18 字节，没有解释器、ELF、业务或模型文件。只读挂载外部 `/usr` 及对应 NSS、加载器缓存和 Python 配置后，具名 `consense` 10001 实际执行 `id`、`getent`、shell 和 Python 3.12 的 `pwd/json/hashlib/tempfile` 检查通过。不是只能外置业务代码；解释器、动态链接器、标准库及共享库可随兼容闭包外置。该 CPU 探针不证明任何业务／GPU 依赖闭包已具备。

| 本次镜像内容决定 | 保留／外置理由与实测边界 |
| --- | --- |
| `consense` 10001 的最小 passwd／group 记录保留镜像 | 本方案采用具名 `USER consense:consense`。反例在镜像内移除账户、仅 RO 挂载外部 passwd／group 后，Docker 在启动挂载生效前报 `unable to find user consense`；因此本次具名启动保留这两份记录。没有测试 numeric USER 替代，不推导所有账户数据在所有平台都不能外置。 |
| 挂载点目录与四个 merged-usr 链接保留最小根布局 | 正例依此使 `/bin/sh` 与 ELF 加载器路径解析到外置 `/usr`；仅是本次已测布局，不将目录／链接宣称为不可外置的运行库。后票按实际角色核对挂载点及入口路径。 |
| USER、PATH、HOME、WORKDIR 记录默认值 | 镜像默认值可被容器启动参数覆盖；属于启动契约，不是不能外置的运行时文件。最终有效值须由 inspect 与实际进程读取核对。 |
| shell、Python、链接器、标准库、共享库和所需配置外置 | 正例整个闭包一起只读挂载并运行成功；外部 9,633 条文件／链接 manifest 前后一致，71 份 Linux 证据导出逐文件 SHA 相同。不能将单个可执行文件脱离 ABI／加载器／配置便视为完整外置。 |
| Java、H2 server、Qdrant、Paddle／MinerU／检索及真实 vLLM 配套继续优先外置 | 02–04 按各角色建立并验证准确闭包、版本、绝对路径和可写缓存；尚无证据要求它们必须留镜像，也尚未完成实际角色镜像 digest。若实测必须保留额外文件，逐项记录无法外置的原因及失败证据后再更新清单。 |

CUDA、PyTorch、Transformers 等按既有隔离环境锁定并实测，不合并互相冲突版本。用户空间正例只使用独立 noexec tmpfs，不含 GPU／业务挂载；swap limit 不受内核支持的 warning 保留，不能声称 memory+swap 限额已验。宿主内核／GPU 驱动由平台提供并另核兼容。说明文档留本地与远端，程序实际读取的业务资源仍须搬运。

## 当前证据与没有通过的事项

| 项目 | 当前有界结论 | 证据／限制 |
| --- | --- | --- |
| Docker 基础 | 通用探针 `consense` 10001 的 RO／RW 挂载、同文件不同路径 hash、GPU 可见、cap-drop／no-new-privileges 已验 | `.local/container-rehearsal-20261029/isolated-docker-01/receipt.md`、inspect／hash 回执；RTX A4000 16376 MiB，不是 H800，不是模型推理。 |
| 本机网络／CPU 树 | host 网络专用回环互通、容器级 CPU 父子孙取消／父早退清理已有证据 | 同证据根 `host-network-test/` 与 `cpu-lifecycle-probe-retry/`；不是赛方网络或业务验收。 |
| 用户空间外置 | 最小根镜像以具名 10001 运行外置 shell／Python 闭包通过；缺镜像账户反例按预期在具名启动解析失败 | 同证据根 `external-runtime-e41f/{receipt.md,result.json,inspect-summary.json,positive-tar-list.json,export-verification.json}`；只证明该 CPU 闭包和具名启动边界，不证明业务／GPU。 |
| LinuxTree 最小接缝 | 任务 ownparent 精确委派，父 cpu／memory／pids 限制 root-owned，只对 ownparent RW，host cgroupns／private PID 下非 root 入组与父子孙取消释放通过 | 任务 WSL `/srv/consense-docker/cgroup-probe-20261029-1859-c39e/` 与 D 盘 `isolated-docker-01/cgroup-probe-{launch,check}-c39e.*`；三轮已导出至 `.local/container-rehearsal-20261029/isolated-docker-01/cgroup-owned-boot/`，39 文件 SHA256SUMS 校验及三轮源／副本 diff 一致；最终 `container.stdout`、`parent-after.txt`（populated 0）、`container-id.txt`／`remove.txt` 保留对应证据；前两次因探针人为 controller 空／子集假设失败仍保留。未做 GPU 上下文释放或限额压力验收。 |
| H2／原件保护 | 10 月 29 日 10:02:10 切点封闭 H2 副本及 44 原件预期 hash 匹配 | `.local/offline-ticket01-20261029/db-copy/acceptance.mv.db`、`evidence/source-copy-summary.json`（相对同一证据根）；只保护该切点，不覆盖其后活动变更；不是全部历史结果或生成文件恢复验收。 |
| 索引副本 | 5 索引副本冷读全 seal 通过；points 依回执顺序为 49,674／9,382／10,118／9,166／49,674；73/74 绑定匹配 | `.local/offline-ticket01-20261029/index-copy-candidate01/phase-a-receipt.private.json` 与 `phase-b/{1..5}/result.private.json`；剩余 1 项原本无 metadata，保持 unknown，不推断成功／零问题，不恢复旧质量状态。点数与 seal 不等于业务或模型质量，也不等于 Qdrant server 格式已兼容。 |
| 拟用端口 | 2026-10-29 约 19:04，Windows `Get-NetTCPConnection -State Listen` 与任务 WSL `ss -H -lnt` 均未见下列 7 端口监听 | Windows／任务发行版只读快照，原 Ubuntu-24.04／ocr 的 ss 检查也未匹配 7 端口（由主任务回执保留）；曾有 awk 引号探针失败，随后直接完整 `ss` 读回成功。未绑定或预留端口，启动前必须重查。 |

端口登记：App `28680`，资料／协调 `28681`，H2 TCP `28682`，Qdrant HTTP `28683`，GPU 对外入口 `28684`；provider 内部子端口 `28771`／`28772`。Qdrant gRPC 默认不额外暴露，若现有客户端确需使用须另登记空闲端口。以上仅专用预演计划，未启动服务；host 网络共享宿主命名空间，不能因此声称网络隔离已通过。已存在的 18185／18186／18189、其他独立 Drafting／SL／Vite、WSL 服务及采集进程保持受保护。

## 后续实际门与人工执行顺序

1. 按[资产及保护登记](../testing/competition-protected-assets-20261029.md)和私有清单复核票据 01 的现有来源、保护边界、固定目标与证据。未知旧目录继续排除；不能为了补齐准备表擅自重读活动库、搬模型或恢复旧质量目标。同版 H2 JAR 与各角色运行闭包按既有版本准备，实际复制、非 root 业务目录／设备权限留在 02–04 的真实运行核验。
2. 在票据依赖允许后，由同一预演环境所有者准备完整版本和脱敏配置，重查上述端口、现有容器总数／GPU 活动与受保护服务，禁止各票独自并行拉栈。依次数据库、资料协调、App，再使用唯一 GPU 槽；每步核实身份与挂载，失败就保留日志，不启动额外替代容器掩盖失败。
3. 02 验证真实持久 H2 TCP、Qdrant server、App 与网页读写；03 验证外置 Python 所有实际导入／子进程；04 验证所有 GPU 入口、统一准入、流式与取消、限额和 GPU 释放。稳定双路 OCR、embedding、rerank 必须真实权重 Docker 推理；Qwen 协议替身须明确标记，真实 Qwen／vLLM 另验。
4. 后续按票验证上传、来源／原文原页、索引、引用、人工记录与生成文件、浏览器起草／审核／咨询，以及同一新实例停止／重建后重读；核对冷副本的历史关联与 unknown。Qdrant local 数据的读取／迁移方式独立留证，不能只比点数。
5. 在途任务与草稿先处理；完整版本受控切换，旧网页资源和回退数据保留。验证断开外部依赖后的离线重启、失败恢复、磁盘不足和日志；普通停止先由所属任务入口取消，再确认进程树／GPU 释放并按依赖停服务，严禁盲杀端口。

状态检查以实际容器清单、进程身份、挂载、配置摘要、文件 hash、业务读回与证据落盘为准。排障先核版本／路径／权限，再检查请求或任务链、阶段错误及资源释放；健康检查不能升级为业务通过。赛方 Docker／cgroup／GPU 权限、扫描受理、网络、文件大小口径、H800 与真实 Qwen 的差异单列未验。本文不代替票据验收，不实施 02–04，不把旧质量缺口带回成本次全面整改。
