# 比赛外置运行：冻结行为对照

**状态：票据 01 的部分对照，未完成，不能据此关闭票据或解除后续门控。**冻结依据为提交 `8a40dfcca04730ba7b40cdefb36b596a37bbcb53`。本表区分冻结源码中的入口、历史观察以及本次预演状态；源码或接口存在不等于功能在本次容器中可用。历史记录描述各自时间、版本和输入，不自动成为冻结源码或本轮运行的验收结果。

**最新决定：**使用 Docker 并准备具名非 root 运行账户，登记并实测各角色进程及权限；先前 Podman 观察为探索，不能代替 Docker 检查。本机 Qwen 不放真实权重，使用明确标记的假文件占位及协议替身验证接线；真实 Qwen／vLLM 推理及赛方验收未验证，其他模型真实推理要求不变。

逐项复查冻结源码时，可以用 `git show 8a40dfcca04730ba7b40cdefb36b596a37bbcb53:<仓库相对路径>`；下表所列源码路径均相对于该提交。当前规格与门控见[实施说明](../specs/competition-container-external-runtime-20261029.md)及[本地票据索引](../../../.scratch/competition-external-runtime/README.md)。

| 受影响范围 | 冻结源码或既有接口事实 | 历史观察和限制 | 本次预演状态 |
| --- | --- | --- | --- |
| 后端、网页与版本 | `ai_consense_service/tools/offline_delivery/README.md` 说明 `externalApp` 挂载后端 JAR 和完整网页产物，并以清单检查启动；协调器和 GPU 工作程序仍在镜像内。默认模式保留独立网页容器。 | [外置部署检查点](external-app-deployment-20261029.md)记录过清单测试与宿主进程 smoke；后者使用内存数据库并关闭模型／OCR，只证明限定的启动与静态资源路径。 | 实际业务容器的挂载、权限、读写和完整外置版本未验。 |
| 资料身份、原件及原页 | `ai_consense_service/src/main/java/com/consense/web/VettingController.java` 有来源角色、原件、解析内容等入口；同目录 `CanonicalEvidenceController.java` 有规范单元、资产和原页入口。 | 历史咨询及来源读取观察按[英文记录检查点](oer-workflow-english-records-checkpoint-20261028.md)所述范围成立，不能扩大成所有来源或所有业务通过。 | 本次外置资料服务、同一文件跨容器身份、历史路径和重启回读未验。 |
| 新资料、OCR、索引和检索 | `ai_consense_service/tools/offline_delivery/README.md` 记录本地 OCR、协调器和 GPU 任务入口；资料接口包含上传和检索入口。 | 模型目录、既有测试或索引存在不证明本次容器完成真实识别与检索；原始双路证据和来源质量必须按现行规则分别核查。 | 稳定 OCR、embedding、rerank 真实权重在容器中加载，新资料处理、持久索引及原文原页回查均未验。 |
| 起草 | `ai_consense_service/src/main/java/com/consense/web/DraftingController.java` 有模板、输入、变量、进度、生成、预览及导出等接口。 | [源码同步检查点](source-sync-checkpoint-20261028.md)记录过浏览器中的不完整草稿及限定的目录更新观察；未证明三份 Word 内容、版式或完整业务验收。 | 本次容器中的模板隔离、变量和人工记录保存、预览与导出未验。 |
| 审核 | `VettingController.java` 有资料上传、来源选择、运行、进度、人工复核及报告导出等接口。 | [英文记录检查点](oer-workflow-english-records-checkpoint-20261028.md)记录的 JSON/PDF 是对先前不完整审查的导出；文件检查不能证明完整实质审查。 | 本次容器中上传、审查、人工记录、报告及持久读回未验。 |
| 咨询、逐段显示和停止 | `ai_consense_service/src/main/java/com/consense/web/AdviceController.java` 包含会话、索引与问答入口；同目录 `AdviceStreamController.java` 定义 SSE 和对应停止入口。冻结的 `ai_consense_service/tools/offline_delivery/coordinator.py` 和同目录 `chat_operation_worker.py` 拒绝有限 GPU Job 的 `stream=true`，其 README 要求关闭该路径的正文流式输出；不能据此声称离线链路已有逐段输出。 | [英文记录检查点](oer-workflow-english-records-checkpoint-20261028.md)记录过有边界的真实咨询及保存恢复观察；这不是本次本地模型流式或停止验收。 | 本次本地 Qwen 真实逐段输出、页面显示、取消所属任务、部分结果与引用持久化未验。 |
| 本地模型和配置 | `ai_consense_service/tools/offline_delivery/README.md` 记录现有协调器／有限任务模型接口及配置约束；[实施说明](../specs/competition-container-external-runtime-20261029.md)明确继续使用现有 Qwen 兼容 API，赛方通过外置配置接入 vLLM，本机以占位文件及标记的协议替身检查，只有实际不兼容才修复。 | 既有模型目录或 catalog 不证明实际权重完整、模型 ready、真实计数和推理通过；历史在线 Qwen 结果也不证明本机权重推理。 | vLLM 真实权重身份、实际资源、配置生效、聊天、结构化输出及输入计数均未验。 |
| 数据、换版和恢复 | 冻结的外置部署说明区分关系库、向量库与硬盘数据，并明确 H2 文件不能直接作为 MySQL 数据目录。 | 既有在线实例及其原件、索引、任务和人工记录需保护；单独复制在线数据库文件不能证明跨资产的一致恢复点。 | 数据库支持的一致副本、关联资产恢复点、离线构建、受控换版、失败恢复及容器重建未验。 |
| 日志和受控执行 | `ai_consense_service/tools/offline_delivery` 包含已有任务执行和取消相关代码；接口或代码存在不证明所有实际入口共享可靠准入或资源已释放。 | 固定执行位置是待评估方案；渲染出的容器数量不是平台或运行全程计数证据。普通日志不能代替原始证据。 | 所有启动入口的互斥、真实进程树及显存释放、跨进程持久日志、脱敏与故障恢复未验。 |

## 保全与未决门控

本机已有业务、资料服务和独立项目实例，其归属、端口、数据和模型资源需在操作前重新确认并保护；详细清单与运行证据留在受限的本机目录，不在本文件列出私有路径、进程身份、原件或凭据。本次只读清点观察到有在线 H2 数据库和关联资料，但尚未证明可在不干扰现有服务时获取数据库支持的一致备份，也未证明原件、索引和人工记录具有同一可恢复点。不要直接复制活动数据库文件或把 H2 文件当作 MySQL 数据目录。

本机最小环境的人工步骤及其未验证边界见[Docker 操作说明](competition-local-docker-setup-20261029.md)。

专用 Docker 测试镜像以具名账户 consense（UID/GID 10001）运行禁网容器，验证 D 盘专用测试文件的只读与可写挂载、不同容器路径的文件身份及容器内 GPU 可见；未运行实际模型。四个测试容器已留证并清理；其中一次禁用全部 Linux capabilities 并启用 no-new-privileges 后仍可读取测试文件和查询 GPU，另有两个非 root Docker 容器经本机 WSL host 网络及专用回环端口交换数据，结束后端口释放；该检查不证明赛方网络隔离。任务专属 daemon 已在核对身份后受控停止，后续需显式以禁用桥接和防火墙规则修改的参数启动。

专用 Podman 已完成有限的真实容器检查：禁网 Linux 容器以 UID 1000 运行，验证了 D 盘测试挂载及只读权限，容器内 `nvidia-smi` 可见本机 A4000；先前两次因驱动不完整导致的失败已保留。另外，两个串行禁网容器以不同容器路径只读同一 D 盘专用测试文件，标记及 SHA-256 与宿主一致；测试容器的身份与结果留证后清理。DrvFS 显示的属主和模式不代表 Windows ACL。这些观察不证明业务容器或模型运行、实际业务路径与权限、五容器全过程或一致恢复已通过。

本次观察到的 18190 CPU OCR 支线不能代表完整 OER 身份。完整双路历史配置及正常 Java 接入证据已定位：18780 协调入口采用 PaddleOCR-VL-1.6 完整 pipeline 与 MinerU 4.0.10 Standard，并有实际双路推理回执；详见[双路 OCR 证据更正](competition-dual-ocr-evidence-20261029.md)。此前由旧健康回执推断“仅 RapidOCR／双路身份未定位”的结论不成立。历史推理不证明当前权重映射完整、来源质量通过或本次 Docker 推理通过；这些状态仍分别核对。

当前仍需确定实际执行方式、五容器全过程计数、各端业务路径及版本身份，再取得可恢复的一致副本和必要的人手操作说明。资源不足、缺资产或协议不通须保留真实失败状态。01 的检查全部完成前，02、03、04 不能进入执行；后续各票继续依其阻塞关系和真实验收关闭。

后继已定位当前 Java 启动前、2026-10-29 10:02:10 上海时间的封闭 H2 副本，并在任务目录使用同版 H2 只读打开；源与副本前后 SHA-256 保持一致。该切点的 44 份来源文件按数据库内预期哈希逐项核同，任务自有文件副本也全部核同；计数与逐项复制依据在本机 `.local/offline-ticket01-20261029/evidence/source-copy-summary.json` 及私有明细，SQL 依据在同目录 `summary.json`。它支持确定切点的数据库与原件预演，不覆盖切点后新增数据；Qdrant／索引 generation 绑定、完整生成文件及业务恢复仍未证明。没有打开或复制当前活动 H2／Qdrant，也没有停止原服务。

此前取消的未完成工作不作为本次前置条件；历史失败与资料保留。该对照不是要求重启历史质量整改，也不是新功能清单。容器内外路径及模型接线引起的新退化仍须处理；测试、真实模型、原件质量和业务验收分开报告。
