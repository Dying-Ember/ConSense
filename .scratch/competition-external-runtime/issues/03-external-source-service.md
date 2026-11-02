# 03: 从硬盘运行资料服务并读回历史资料

## 2026-11-01 比赛 demo 验收口径

本票保持代表性范围已完成：沿冻结基准入口和数据流读取正确项目、来源版本、原文／原页及已有索引，资料服务和必要数据使用外置路径。保留身份、版本与权限检查，已有可用索引优先复用，不为 demo 增建恢复系统或重复读取、重建。历史项目搜索和来源回查证据可复用；尚无样本的入口、资料质量和赛方环境仍记未验。

**2026-10-31 范围纠偏（历史）：**当时按原票目标、验收清单和 Blocked by 核对；当前 demo 口径以顶部说明为准。下方历次运行状态、串行方式、额外开放理由与百分比均为历史记录；当前运行状态以看板和当次现场核对为准。

**2026-11-01 与权威 spec／冻结基线对齐：**原清单曾写“后端、资料服务及任务程序通过各自容器内路径读到同一份历史原件”。经核，基线检索任务接收来源文本、普通 PDF OCR 接收页图，另有原生 PDF 分支接收原件字节；spec 要求按实际数据流保持原件、页面、来源和任务输入的身份对应，并未要求所有角色各自直接打开整份原件。下方该项按实际链路核验，保留原件／页图身份和必要路径权限检查，不为测试改变原有数据流；未被下游独立读取的原件字节不得报告为下游已独立核验。

**What to build:** 外置资料服务继续通过现有业务入口提供历史原件、原页、规范来源和已有索引读取，使用者能打开正确项目及正确版本的依据。

**Blocked by:** 01 — 确定本次保留的行为和本机实际运行方案。

**Status:** closed — 2026-11-01，按已确认收紧范围完成代表性验收；未覆盖项按下述边界保留

## 2026-11-01 原票验收与证据边界

以票据原范围和本页已注明的 spec／基线数据流纠偏为准，独立复核认为现有代表性证据支持关票：非 root 外置服务和实际业务入口运行；历史来源、正确原件和 PDF 页面读回；实际 App 使用原历史索引搜索 33 份选中资料并返回 10 条命中，来源身份、版本与任务输入回查；实际路径／权限负例、跨项目和错误版本负例；Source 重启及 H2 换版后的代表性历史资料和任务记录读回。操作步骤见 [资料排查说明](../../../ai_consense_service/tools/offline_delivery/source-troubleshooting.md)，详细对照见 `.local/container-rehearsal-20261029/ticket03-evidence-and-source-troubleshooting-20261101.md`、`ticket03-sample-identity-task-readback-20261101.json` 和 `ticket03-entrypoint-regression-20261101-a1/`。

此关票不声称所有资料、项目和入口均已真实运行穷举：真实模板跨模块负例及当前预演中无适用样本的 `registered-original`／source-pages／asset 真实路径仍未知；原页和注册原件契约测试属于软件检查。浏览器回查有当次记录但截图未另存，十次原件逐条回查未另存独立回执；逐项人工采用状态、来源质量及九条页眉编号映射未全部验证。底层 Qdrant ACL、真实 Qwen、双路 OCR、新资料索引、全栈验收和搜索性能分别按原范围及后续票处理，不由本票关票推定通过。真实失败和历史状态保留。

## 2026-10-31 旧索引读取内存修正

旧索引的 Qdrant Local 初始化在 3 GiB 虚拟地址空间限制下整库加载失败；已将迁移工具的源读取改为 SQLite 只读事务及至多256点分页，保留现有项目／payload／向量逐点核验、原封印和 writer authority 合同。独立复核拒绝直接合入另一候选中会改变 writer 返回值合同的附加改动；共享源码只合入本次读取改动及相应测试。

合入后在共享源码、非 root、隔离小数据上运行迁移、流式读取、checkpoint 和 writer composition 共32项，exit0、全部通过；日志 `.local/container-rehearsal-20261029/ticket03-stream-shared-regression-20261031.log` SHA256 `4b6f264875357a0a2191d01c6139a0425a7c5e2ed5bb26f377a59d72e6eed5d3`。先前候选夹具和测试环境失败记录保留。尚未运行真实49,674点完整迁移或切换运行 Source，CLI transfer 仍不开放，不能将软件测试称作业务验收。

随后以 UID10001、单 CPU 和1 GiB地址空间上限，在隔离的真实索引工作副本上运行共享 `ColdLocalReader`：精确计数49,674、读首个1,024维点、取得下一页游标，ID、原始 payload 和 float32 向量摘要均与之前的独立冷读取一致，exit0；未连接 Server。脚本 `.local/container-rehearsal-20261029/ticket03-stream-reader-real-sample-20261031.py`；这是一个点的代码与真实格式兼容证据，不是完整分页封印或迁移验收。

之后对同一独立 SQLite 副本和已核 SHA 的登记 metadata，在受限宿主进程调用共享 reader 及原 `verify_reuse`，49,674个1,024维点及原有精确封印通过，最大 RSS 2,921,336 KiB，exit0；点集摘要及范围见 `.local/container-rehearsal-20261029/ticket03-index1-full-seal-20261031.md`。未写 Server 或调用模型；本机 Source 的2 GiB总上限、真实迁移、容器读回及业务仍待处理。

随后真实向运行 Qdrant 的唯一 staging 集合迁移该索引，Source保持运行；49,674点及真实 payload 的目标端原封印与源端一致，命令 exit0、尚未采用为业务索引。完成journal SHA256 `02416679b35b8436a2022bf075c0dd11a05927abfb795ff0b43633f8932d88a3`，执行及Qdrant资源证据见 `.local/container-rehearsal-20261029/ticket03-staged-transfer-attempt-20261031.md`。当前来源范围兼容、Source读取、重启持久、项目隔离与业务查询仍未验；全局Qdrant key可访问 staging，不声称集合ACL已通过。

迁移器 staging 目标名透传及回执绑定经独审合入共享源码；原默认调用保留旧回执字段和 writer authority 返回合同，显式 staging 仅允许固定无凭据 loopback 地址。合入后共享源码41项软件回归通过，日志 `.local/container-rehearsal-20261029/ticket03-staging-shared-regression-20261031.log` SHA256 `38cc3d62ac63619f05d230b4b0c1227f158865b5eb1790a2e0e238fe98400298`。实际已完成迁移使用前一隔离候选，回执格式与合入版兼容；不是 Source 已采用该集合的证据。

隔离原 metadata 的一次 JSON 加载在 3 GiB 虚拟地址空间限制下 exit0，峰值 RSS 2,851,160 KiB；当前 Source 容器总内存上限为2 GiB，分页读取向量不能单独解决这一限制。完整封印与读取的资源峰值、实际配置和赛方限制仍待核。记录 `.local/container-rehearsal-20261029/ticket03-index1-metadata-memory-20261031.md`。

## 2026-10-30 17:46 Source BGE 发布目录已换入，模型任务未验

attempt2 越过外层 `SO_REUSEADDR` 端口探针，但共享 `container_admission._ports_available` 裸 `bind` 又报 `[Errno 98] Address already in use`；旧 Source 恢复，事件见专用发行版 `source-bge-switch-20261030-attempt2/events.jsonl`，原失败保留。主控在共享端口检查补 `SO_REUSEADDR`／`bind`／`listen`，隔离 7 项测试通过后，执行 attempt3 的现场 `--check` 与一次 `--switch`。事件 `source-bge-switch-20261030-attempt3/events.jsonl` 于 17:39:35 记录 `switch-passed`、运行数 5、新 Source `e1da63c49787d9aed6f09ce78ddddd5bc43740baa06ec5dd4c167b16b52ba5bb`；旧 `f340223…` 退出。17:46 独立 Docker 只读点查 App／H2／Qdrant／Source／GPU 5／5，Source PID91352、GPU 原 PID86286。新 Source 内 embedding 固定 revision 的 10 文件和 reranker 固定 revision 的 6 文件身份读取均 `identity_ok`，详见本地 `source-bge-switch-a3-20261030/source-embedding-identity-attempt1.log` 与 `source-reranker-identity-attempt1.log`。仅证明模型资产身份和可见性；真实固定 worker 推理、任务后释放、原页／旧索引／完整业务、五包重载与扫描仍未验，本票不关、准备估计不提高。

## 2026-10-30 16:33 Source 0644 镜像和外置 r7 已准备，运行版未变

固定底座已含正确的 `consense` 账户文件，故共享 Source Dockerfile 删掉两条在本机 legacy builder 不支持的 `COPY --chmod`。首轮构建失败回执保留；第二轮镜像 `sha256:c66fd5f63456746f52bb419a47b5ae9c8090eeadd7747e2a547a4c20d5132ecf` 构建、导出和 OCI／账户 0644/root:root 静态核验通过，tar 10,752字节、SHA-256 `3c4f39114d700934977c34563d4d29cbdff6a6b1d22d4476766fa86d2d264813`。原 Source 恢复双 health，五个原运行 CID 和 GPU 进程未变。新外置 tools r7 473项／7,586,579字节、manifest SHA-256 `6749f734789a0a60a36f699d4aaff1ba8e50b5a40b1af4b9d8dd8987c2ec803d` 已由 UID10001 逐项核同。当前 Source 仍运行旧镜像/r3，尚未挂 r7、Pillow 或新 GPU 配置；真实 OCR、历史全量来源和业务验收未通过，本票不关。

## 2026-10-30 15:06 微型资料镜像换版

专用 Docker 从旧 Source `49d999…` 受控切至新 Source `30a053…`，运行数5→4→5，其他四角色 ID 不变；新镜像 `sha256:81fe670e3d3e92db7ac9a7dcd2e65d7a3b62c773e384c438927b0f5436689f3f` 在 docker image ls 中显示119 kB（inspect .Size 为3,988 B，导出包大小未测），旧对象和同口径约596MB旧镜像保留。Vetting／Advisory 双 HTTP、文档23两套独立原文指针经 Source 和 App 再读回，响应字节 SHA 与换版前一致；Source→Qdrant 鉴权及 GPU UDS 401／404 接缝仍通。见本地 `ticket-03-progress.md` 15:06 条及换版回执。原页、其余历史来源、旧索引、真实模型、外置 `/usr` 打包闭包和完整业务仍未通过，票据开放；准备估计72%不是验收率。

## 2026-10-30 13:42 外置源码 r2

按旧版固定 471 项 allowlist 新建独立外置源码 r2，共 7,561,169 字节；新清单 SHA-256 `9184ef4fcb73b8142dcf1c252a524a75db4fc37306f1ba2a807889da883c6d31`，UID10001 逐项大小／SHA 读回无差异，旧版保留。资料启动候选已固定新镜像和 r2 release，`verify_release()` 返回471。尚未有真实 GPU 配置／profile manifest，当前启动候选还须接到共享 pending GPU 准入；未启动第4容器，资料业务未验。

## 2026-10-30 12:37 固定远程配置与启动前沿

现有完整资料入口在启动时只校固定 GPU profile manifest、凭据和模型配方，连接 UDS 发生在模型请求时；因此有真实 worker 配置与独立 pin 后，即使 GPU socket 尚未监听，资料容器可先提供非模型来源读取。隔离的 `serial03-source-start-candidate-20261030/start_source.py` 已按既有外置 release 471 项哈希、专用 daemon、镜像、UID、私有目录和端口编写只读预检及显式启动路径；WSL 静态测试 1 项和 release 哈希核验通过。当前正式 `gpu.json`、由其派生的 `gpu-profiles.json` 和 source remote env 不存在，真实预检按设计 exit1 拒绝启动；不能用 REPLACE 或假 pin 凑第四容器。03 仍开放，非模型 API、历史索引和真实来源业务尚未实启读回。

**历史勾选（代表性范围和限制以本文顶部关票记录为准）：**

- [x] 本机 Docker 容器从登记的外置项目文件以具名非 root 账户启动资料服务，核验账户名、UID/GID、实际进程和文件权限；启动定义、模块导入、动态资源和子进程使用同一版本，不只修改镜像定义。
- [x] 按冻结基线实际数据流，核验后端、资料服务及实际参与的任务程序通过登记路径或现有接口读取／接收所需原件、页图或来源输入；核对实际输入字节身份及其与正确项目、角色、原件哈希、适用时的物理页、来源版本和采用状态的对应。对确需文件路径读取的角色验证映射、权限与失败行为，不要求所有角色直接打开同一原始文件。
- [x] 对隔离真实项目副本，通过现有页面和接口读取原文、原页、规范来源及已有索引；统一入口中基线已有能力全部保留。
- [x] 历史绝对路径采用经验证的挂载或安全映射；缺失、错误映射、跨出允许目录及权限不足均明确失败，不悄悄读取替代文件。
- [x] 只读发布资产、可写资料状态和临时文件位置符合实际权限；容器重启后历史资料、来源身份和既有任务记录仍可读取。
- [x] 项目资料、起草空模板及不同项目的权限和索引范围保持隔离，不通过复制或重建新资料模型绕开现有来源约定。
- [x] 保存实际文件对应、接口结果、浏览器回查和重启重读证据；列出资料打不开时可由人手依次检查的路径、权限、版本和服务状态。

## 2026-10-30 11:53 常驻入口首次实际失败

已构建镜像、外置 Python／源码和双 state 一次真实 Docker 启动；Vetting、Advisory 子进程均因 `CUDA explicitly requested but unavailable` 在入口初始化退出。容器 exited1/OOM=false，28681／28685 未监听，现场仍只有 App/H2/Qdrant 三个运行容器。原对象与日志保留，回执见 `.local/container-rehearsal-20261029/serial03-source-image-20261030/startup-attempt1.json`。需要真实固定 GPU 远程接缝后再启动，不以占位模型或导入探针关票。

## 2026-10-30 11:35 +08 主控串行资料镜像准备

在专用发行版现有82个离线wheel SHA锁下安装的独立Python环境已完成 `pip check`；从当前源码外置发布的471项／7,541,675字节均有逐项源/目标SHA一致回执（见本地 `serial03-source-env-20261030/` 与 `serial03-source-release-20261030/`）。本次以本地 `consense-rehearsal/account-test:20261029` 用户空间为底座，从仅含Dockerfile和账户文件的4,608字节构建上下文生成资料镜像 `sha256:cb2a2e626f0bb0462df51f6de32897debdb8dddf0da9b10cddf516d233cdca13`，镜像配置为具名 `consense:consense`；业务源码没有COPY进镜像。短命Docker探针使用只读外置Python/source挂载，实际UID/GID10001:10001、Python3.12.3，导入 `oer_unified_server` 及资料入口成功；另一次配置预检以固定SL scope SHA `bb1512de4b16bfa2c10f03a8197b0c798c105e3359fc649d35391f6fcb66e3d6` 和Vetting/Advisory独立可写state、28681/28685通过。两探针均exit0并自动移除，常驻容器未启动；前后App/H2/Qdrant仍3个运行。证据 `serial03-source-image-20261030/build-readback.json`。真实固定GPU profile、凭据、模型资产闭包和GPU释放见证仍不具备，资料入口不得降级假装模型可用；真实来源API、原页、索引迁移、重启持久和业务均未验，03不关票。
