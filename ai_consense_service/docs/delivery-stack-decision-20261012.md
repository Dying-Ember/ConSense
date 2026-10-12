# ConSense 超算交付技术栈评估与今晚锁定建议

日期：2026-10-12，Asia/Shanghai。用途：给正在准备超算环境的同事确定依赖与部署边界，减少后续安全扫描时的技术栈变动。

状态：**整体评估及今晚锁定建议已经形成；仍为研究建议，未安装、构建、扫描或部署新的运行环境。** 本文覆盖无框表隔离实测、新原型、Vetting／Advisory、现有软件版本与单 H800／K8s 交付。用户说明同事环境尚未搭好，本次以项目自己的交付需要给出方案，不等待对方环境信息。软件家族、版本候选与实际扫描采用分别记录；没有生成最终镜像 digest 或扫描通过结论。

规则入口：[OER 已确认范围与执行规则](./oer-scope-and-rules.md)、[实施交接](./HANDOFF-ocr-embedding-rerank-20261008.md)。21:00 开发基准的最新规则优先；05 已冻结及入库是开发交付，不追认为全部来源质量通过。Drafting 的 harness、取值、正文与排版冻结保持。

## 0. 今晚可交给部署同事的整体结论

**整体评估范围明确包含OER、Vetting和Advisory，以及它们共用的检索／证据／LLM／存储／K8s部署。** 第2.3节的“仅原件解析”只限定该子调查的质量归因，不排除第3–4节的业务与推理技术栈。用户再次明确要求两业务模块都纳入，模块依赖、真实已有能力及仍需实现的部分汇总于第3.4节。Drafting继续冻结，图纸文件只做身份识别后排除。

主体技术栈保留。建议新增 Camelot 传统无框表候选能力，并把服务式 Qdrant 作为超算首版目标；主要能力缺口由现有 Java／Python 服务的数据契约和算法实现补齐。新原型的交互不要求增加一套 Agent／RAG 框架。以下是明确的研究推荐，尚未变更运行环境或当前05库。

| 层次 | 今晚建议确定的方向 | 采用／验证边界 |
| --- | --- | --- |
| Web | Vue 3＋TypeScript；沿用现有 npm lock，生产交付静态产物 | 锁 Node/npm 构建 patch；不把 Vite dev server 作为发布入口 |
| 业务服务 | Spring Boot＋JPA＋JDK17，POI／PDFBox；保留现有三模块实现 | Boot2.7.18 已结束官方 OSS 支持，应优先核扫描及受支持版本整改；不把现版本静态盘点称为可发布通过 |
| 关系数据 | 保持 MySQL 家族及现有 SQL 业务模型 | 新部署以受支持的 MySQL LTS 为目标，建议8.4系列；patch／驱动／Flyway须一起验证，现5.7兼容注释不是新部署承诺；本轮未迁移 |
| 原生 DOCX／PDF | POI原生DOCX；已有native PDF文字／几何／有线表路线 | 保文稿格式、原文字、条款和来源身份，不用OCR重造原生DOCX |
| 无框表增强 | 独立CPU native-table worker：Camelot2.0.0基础包＋pdfplumber0.11.9 | 纳入扫描候选；Network／Stream独立产物，完整表恢复及条件组装仍待源评，不替换05 |
| 扫描／复杂页面 | PaddleOCR3.6.0＋PaddleX3.6.0；MinerU full4.0.10 | 两路完整解析，独立依赖环境与顺序GPU使用；Linux锁／模型／精度各自保持身份 |
| 检索模型 | BGE-M3＋BGE-reranker-v2-m3；BM25＋向量检索＋结构扩展＋rerank | 保当前固定revision；完整父条／表组回取，raw和审计字段留来源层 |
| 向量数据库 | **超算目标建议单实例Qdrant Server＋PVC，候选1.19.2；现client1.19.1** | 这是部署方式调整，不换数据库家族。当前Local05保持；迁移到新collection后核点／向量／payload／来源，不直接复制Local目录成Server存储，不要求重做OCR |
| 业务LLM | 独立vLLM服务；Qwen3.8-27B官方FP8为首轮候选，M3仅开发质量参照 | 至少256K、优选512K；准确engine／制品／模板／FP8和H800峰值未验。初始可先测已成功Linux底座的vLLM0.19.1＋Torch2.10.0/cu128＋Transformers5.10.1，不能把MinerU成功当业务模型成功 |
| 文稿／原件资产 | PVC文件资产＋版本／SHA清单；LibreOffice及已用PDFium/必要Poppler、真实字体 | 不新增MinIO或Chromium导出服务；版本、字体及所有本地二进制仍纳入扫描 |
| 编排 | K8s；CPU业务／存储持续运行，GPU阶段显式交接 | 不新增Ray、KServe、Temporal或GPU共享平台。不同环境隔离不等于同时向同一张GPU申请多个整卡Pod |

选择服务式Qdrant的理由是让向量存储生命周期独立于GPU模型的加载／卸载与检索worker重启；Vetting和Advisory共用一个索引API，更容易保持同一冻结身份。它不是两模块功能的硬前置：若首版时间不足，可继续一个明确owner的Local检索服务作为有界后备，replica=1、uvicorn workers=1、发布时旧owner完全退出。Local后备不允许多个Pod共写；PVC RWO本身也不能证明同节点只有一个打开者。

本报告建议**新增组件家族候选两项**：Camelot基础包、Qdrant Server制品，连同各自传递包、原生库及OS一起纳入扫描，不能理解为仅增加两个包。通用逻辑表恢复、完整证据组装、审查执行账本、引用绑定和版本失效属于待实现能力，不是新增第三方平台。native-table增强默认关闭／仅离线候选生成，在完整逻辑表和业务组源评前不自动替换解析或05入库采用。精确Linux制品、业务LLM正常加载及扫描仍有待办，见第5节；今晚不能只凭组件名宣称所有制品已冻结。

**首版GPU拓扑推荐终止式阶段Pod，暂不采用sleep共享。** CPU业务、关系库、Qdrant Server和资产服务持续运行；Paddle→MinerU→文档embedding按完整批次分别启动独占GPU工作负载，前一阶段正常结束、输出持久化、确认释放后再进入下一阶段。在线Vetting／Advisory先完成query embedding／rerank及证据包准备，再结束该GPU阶段、加载业务LLM；LLM结束并释放后才回到下一取证阶段。连续问答可能有较高加载等待，先接受这个正确且可恢复的基线；之后仅在实测峰值准入后增加小E/R共驻或验证sleep优化。阶段Pod删除／Job完成后实际资源释放、接口ready、失败恢复仍需目标验证；本次没有创建K8s控制器。单GPUowner管理隔离子进程作为后续减少加载等待的优化候选，不作为首版必须新建的调度平台。

## 1. 运行栈盘点与部署边界

### 1.1 已有业务端与文稿工具

| 层次 | 当前源码／锁定事实 | 今晚建议 | 验证边界与出处 |
| --- | --- | --- | --- |
| Java 业务服务 | Spring Boot 2.7.18；编译 source/target 1.8；Spring Web／JPA／Validation；OkHttp 4.12.0 | 保持现有业务服务架构，运行时明确选 JDK 17；不因原型重写后端 | `pom.xml:8–32,35–103`；`README.md:14` 明确需要 Java 9+ ProcessHandle，已检查 JDK17。Java8 字节码不表示格式化 PDF 功能可在 Java8 运行 |
| Java 构建 | Maven，Boot parent 管理传递版本；上游记录 JDK17.0.20.1／Maven3.9.16，root 本地另有 Maven3.9.11 工具 | 构建环境选定一个确切 JDK17 patch／Maven patch，并保存构建镜像 digest 与最终 resolved dependency tree | `README.md:21` 是上游验证快照；本轮未构建，不能把不同 Maven patch 写成同一配方 |
| 业务数据库 | JPA；mysql-connector-j 及 H2 runtime；Flyway7.15.0，POM 注释说明目标 MySQL5.7 | 先核超算已有关系库与扫描策略，确定一个生产数据库版本／驱动／迁移配方；H2 继续仅开发使用 | `pom.xml:26–28,53–67`。没有本轮真实 MySQL 迁移验证；MySQL5.7／Flyway7 是必须进入安全审查的旧版本约束，不凭静态清单宣称已安全或直接升级 |
| 原生 DOCX | Apache POI／OOXML 5.2.5；正文与保真修改由现有 Java 文档链承担 | 继续保留；不把 OCR 文字重建 DOCX 当生成方案 | `pom.xml:30,69–89`；不因新原型引入新的 Office 编辑套件 |
| Java PDF／报告 | PDFBox2.0.31；VettingReportWriter 已原生生成 PDF／DOCX／JSON | 保留现有导出路径，锁字体资产和版本；先真实生成后核字体、可检索文本及页码 | `pom.xml:29,70–73`；`VettingReportWriter.java:8–16,104–147,351–366`。报告生成不需要新增 Playwright／Chromium PDF 服务 |
| 格式化 DOCX→PDF | 默认 LibreOffice；另有显式 desktop-x2t 可选 adapter；进程管理使用 ProcessHandle | 首版交付锁定 LibreOffice 路线及确切 Linux 包／字体，保留 adapter 源码但不同时新增 ONLYOFFICE 产品到生产清单 | `docs/specs/drafting-document-engine-decision.md:5–19`；`DraftPdfProcess.java:13–20,37–39`。本机 LibreOffice26.2.6 是已有本机 QA 配方，不是 Linux 镜像已验版本；页眉、页脚、目录／分页门仍独立 |
| 字体／PDF 栅格化 | 本机已用 Poppler26.07.0；renderer 与 PDFBox 需要真实 Unicode 字体 | 字体本体、许可证、SHA 与 fontconfig 缓存纳入镜像／制品清单；区分原件字体映射与报告字体 | `docs/testing/original-pdf-dual-parser-bf16-validation-20261009.md:29`；`docs/testing/oer-g305-report-visual-and-json-browser-validation-20261010.md:19`；Windows 字体和 portable 可执行文件不能原样作为 Linux 包 |
| 前端 | Vue3／TypeScript／Vite，checked-in package-lock v3，有精确 version／integrity；已有 PDF.js、Mammoth、DOMPurify、Mermaid | 使用 `npm ci` 与同一个 lock 构建静态产物；Node/npm 属构建环境，生产前端用团队已有静态文件服务／Ingress | `ai_consense_web/package.json:7–34`。不用 Vite dev server 当生产发布；本轮不另定未经核验的 Nginx tag |

POM 的明确版本只是输入的一部分：BOM 管理的 Servlet／Jackson／Hibernate／连接驱动等传递依赖仍需最终 resolved 清单和镜像扫描。Boot2.7、MySQL5.7、PDFBox2.0.31、POI5.2.5 等旧版本应早交扫描方核定；没有扫描结果时，不声称特定 CVE 已受影响／已修复，也不为赶交付做未经回归的大版本迁移。

前端关键锁定版本如下，来自本轮直接解析 `package-lock.json`，不是 package.json 的 `^` 范围：

| 依赖 | 精确版本 | lock 起始行 |
| --- | --- | ---: |
| Vue | 3.5.43 | 4304 |
| Vite | 5.4.21 | 4237 |
| TypeScript | 5.9.3 | 4191 |
| vue-tsc | 2.2.12 | 4387 |
| Axios | 1.20.0 | 1659 |
| Pinia | 2.3.1 | 3714 |
| Vue Router | 4.6.4 | 4372 |
| vue-i18n | 10.0.8 | 4351 |
| PDF.js | 4.0.379 | 3641 |
| pdf-parse | 1.1.1 | 3619 |
| tesseract.js | 5.1.1 | 4109 |
| DOMPurify | 3.4.16 | 2435 |
| Mammoth | 1.13.0 | 3253 |
| JSZip | 3.10.2 | 3107 |
| Mermaid | 11.15.0 | 3296 |
| @noble/hashes | 1.8.0 | 739 |

上述每个 entry 均有 integrity。它证明分发包身份，不证明已通过安全扫描。README 根入口记录使用 Node24 检查，而 web package 没有 engines／Node patch 锁；最终构建 Node patch、npm patch 与基础镜像仍需确定。

### 1.2 OER 环境必须保持独立

| 工作负载 | 已有准确版本／制品 | 交付建议 | 当前证据与缺口 |
| --- | --- | --- | --- |
| Embedding／rerank 服务 | requirements：transformers4.57.6、sentence-transformers5.2.0、qdrant-client1.19.1、rank-bm25 0.2.2、FastAPI0.142.2、Uvicorn0.54.0；本机 retrieval-gpu dist-info 是 Torch2.11.0+cu128，Python3.12.10 | 独立 CUDA retrieval 环境，保留 BGE-M3 与 BGE reranker-v2-m3 固定 revision；复制身份和语义，不复制 Windows venv | `tools/vetting_eval/requirements.txt:1–15`；本轮只读 `.local/oer/venvs/retrieval-gpu/Lib/site-packages/*.dist-info`／pyvenv.cfg；需要独立 Linux hash lock 与 image digest |
| 固定开发库05 | 真 CUDA FP16 编码，FP32 归一化／存储；512 token 窗、overlap64、batch16 | 搬运不可变 baseline 和原件／父单元／版本，保留真实 contract；避免把 development v1/signature3 假作 project canonical v2/signature4 | 最新 handoff 顶部及 `fixed-oer-development-baseline-20261012.md`。已建向量不要求重新 OCR；在线业务接缝和检索业务质量仍需验 |
| Paddle 主解析 | PaddleOCR3.6.0、PaddleX3.6.0；本机 paddle-gpu 为 paddlepaddle-gpu3.3.1；PaddleOCR-VL1.6＋PP-DocLayoutV3 | 独立 Paddle CUDA 环境；沿用 native 混合精度，不强制所有组件16bit | 旧 freeze `.local/state/oer-implementation-20261009/paddle-requirements.freeze.txt:58–60` 是当时 CPU 环境的 `paddlepaddle`；本轮 GPU dist-info 明确为 `paddlepaddle_gpu`。Windows CPU freeze 不能当 Linux GPU lock |
| MinerU 辅助解析 | `mineru[full]==4.0.10`；已有 Linux full 成功配方 Torch2.10.0+cu128／vLLM0.19.1／Transformers5.10.1／accelerate1.15.0／CUDA12.8；主 VLM CUDA FP16 | 复用已有 Linux full 精确候选，不重新在业务 retrieval 环境 `pip install mineru[full]` | `requirements-mineru-linux-full.in:1–5` 只有直接输入。真实 full 锁、安装与两页完整运行另有保存证据，见下节；正常 CPU 辅助及未运行分支如实保留 |
| native PDF／无框表候选 | 隔离实验 Camelot2.0.0＋pdfplumber0.11.9，Python3.12.14；Network／Stream／Hybrid 实跑 | 可先列入独立 native/table 候选环境扫描清单，功能采用由完整表组源评决定；不在现有 GPU 环境就地安装 | `oer-borderless-table-isolated-investigation-20261012.md:22–27,57–65`。隔离版本锁存在但没有 wheel hashes；不是目标 Linux 镜像。stock 完整表0/5，不自动替换05 |
| 旧轻 OCR | RapidOCR3.9.2／ONNXRuntime1.22.1 CPU，代码与 requirements 仍在 retrieval 工具中 | 保留兼容代码身份，声明其适用边界；不把 line OCR 当完整结构解析或主解析器 | `requirements.txt:4–5`；`ocr_runtime.py` 明确以 CPU thread 配方为旧路径。默认 full 不意味着 every auxiliary 都应 CUDA |
| MiniMax 中继 | Python client 直接要求 `openai>=1.55.0,<3`，未锁精确版本 | 开发参照入口保留；交付若确需该 client，要指定一个实际测试过的精确 SDK 及 hash；比赛离线模型不自动依赖外部 API | `tools/minimax_relay/requirements.txt:1`、`client.py:105`。不能把本机密钥或 relay-client.env 打入镜像 |

**已发现明确版本冲突。** Retrieval 当前锁定 Transformers4.57.6；MinerU4.0.10 Linux full 官方要求 Transformers≥5.10.1,<6，Linux vLLM≥0.19.1,<0.29。两者不能作为同一个 Python 环境直接合并。Paddle、MinerU、检索及 CPU native/table 环境有不同 numpy／pypdf／ONNX／CUDA 依赖，按进程或镜像隔离可以降低破坏现有成绩和二次扫描的风险。新增无框候选不是重新选择全套 OER 框架。

这里的独立镜像是**依赖隔离**，不要求同时驻留多个 GPU Pod。单张 H800 的 GPU workload 必须由一个明确阶段所有者使用；默认 `nvidia.com/gpu:1` 下两个常驻 GPU Pod都申请整卡，第二个不能凭 sleep 自动获得 scheduler 资源。要么一个持卡执行单元管理隔离子进程，要么严格结束上阶段专属 Pod 再交卡，具体方案在实际集群核验。

### 1.3 有现成 Linux full hash lock，不应从零再解一次

本轮直接核对已有目录：

`.local/state/oer-mineru-full-linux-20261010/candidate-01/worker-vllm0191-pypdf6190/`

| 制品 | 当前证据 |
| --- | --- |
| `requirements.in` | MinerU4.0.10／vLLM0.19.1／Torch2.10.0／Torchvision0.25.0／Torchaudio2.10.0／Transformers5.10.1／nvidia-cutlass-dsl4.4.0／pypdf6.19.0 |
| `constraints.txt`／`linux-pip-freeze.txt` | 精确安装版；含 accelerate1.15.0、numpy2.2.6、ONNXRuntime1.31.0、OpenAI2.54.0、pypdfium2 5.14.0 |
| `uv-resolved-lock.txt` | uv 生成，具有 `--hash=sha256`；实际 SHA `f37a6e916dfc1ec7e7a77e2c90506112f3625b43bd160d2ed55140a7bfac49c1` |
| `linux-installed-metadata.json` | 238 个 locked distributions；pip check exit0；resolverInstallVersionMismatch=[]；metadata SHA `bf98c722e6ee29f7c9b14f5acd8a0f60315a8d4bd49be4200eaac41b5fb7f86e` |
| 已有真实运行 | `official-full-native-async-vllm0191-fp16-8k-forms-session-v3-safe-rpc` 完成 GCT 两页；独立 runtime／worker 证据在 `docs/research/oer-deployment-current-evidence-20261012.md:108–123`；其质量残差未获最终采用 |

这是已有 Linux 候选环境的可复用起点。它仍未包含最终 OCI 基础镜像 digest、目标 OS 包清单、H800 驱动／容器 runtime 组合、最终 source／wheelhouse 制品集合及安全扫描收据。它不是“所有 H800 部署项已经锁完”。将其纳入最终交付清单前，需校核同事所选 OS／Python ABI 与该 lock 的兼容性；不能只复制一个冻结文本并在不同架构重新从网络解析。

Camelot 隔离环境 `requirements.lock.txt` 只有22条精确 versions，不带 hashes；当前 SHA `a1849b56dfa274d9d1307f2299312ded46fd0e3ced650d9dc5526c0926972aeb`。其中 `cryptography50.0.2`、`numpy2.5.4`、`pypdf6.11.0`、`pypdfium2 5.14.0` 与其他环境不同，必须冻结目标Linux完整依赖后再扫描。不能只扫描 Camelot 名字、忽略传递包。

### 1.4 数据库与状态不需要全部换型

Python 实际检索服务使用 `QdrantClient(path=STATE/qdrant)`，Uvicorn `workers=1`（`server.py:291,410`；固定 baseline wrapper 同样 `workers=1`）。这是一进程持有的持久 Local DB。Java 另保留 memory／HTTP Qdrant 两个 vector provider 分支（`AiConfig.java:49–56`），不能把分支存在当实际05库已经通过 HTTP Qdrant server 运行。

Local确实可以由一个独立retrieval服务持有，供Vetting／Advisory共享调用。主负责人综合单GPU阶段交接后，超算目标优先建议单实例Qdrant Server＋PVC，让存储不依附某GPU进程；Local作为不额外引入新制品的首版后备。两者不能混称已部署。manifest、raw、parsed、父单元／原表组及source artifacts放持久卷，真实query/material在内存按版本缓存。活动Local库仍保持现有owner，迁移另建generation／collection并回归，不在本次评估访问、改写或复制数据库。

项目持久化与报告状态继续使用已有关系数据库。现有 Java 有任务队列和持久实体；需要业务状态／重启恢复，不自动意味着引入 Redis、Kafka、Celery、图数据库或新的 agent 框架。版本、覆盖、证据闭包和引用绑定优先在现有 Java／Python模块内完成。

### 1.5 今晚交付锁定仍缺的具体项目

本轮 repo 文件盘点及 `git ls-files` 未找到 Dockerfile／compose／K8s deployment／Helm／SBOM 文件；当前本地 Windows launch scripts不能当集群配置。查找范围为当前 repo 源码，未检查同事另一仓库或实际超算集群。

| 必须形成的交付项目 | 当前已有 | 当前缺口／应给同事的具体内容 |
| --- | --- | --- |
| 软件家族及职责 | Java／Vue／POI／PDFBox／Paddle／MinerUfull／BGE／QdrantLocal 已在源码或实验中 | 统一的最终采用矩阵；每个候选 enabled/disabled 状态，不让候选安装代表自动采用 |
| Linux 基础运行 | MinerUfull 独立 Linux 环境成功 | OS、glibc、Python patch／ABI、JDK17 patch、Node/npm构建版、CUDA用户态及 OCI 基础镜像 digest |
| 依赖锁 | web完整 package-lock；MinerUfull本地hash lock；其他多为direct pins／Windows freeze | 将可复用 lock 与 wheel/apt制品 SHA整理为可交付清单；Paddle、retrieval、native/table 各自Linux锁；最终Maven resolved tree |
| 模型与执行配方 | 模型资产 manifests、revision、固定baseline pins及各轮 recipe | 最终模型集合／tokenizer／processor／chat template／remote custom code身份；精度及fallback；模型SBOM/许可证；业务LLM最终引擎不能由MinerU内置vLLM版本顺带指定 |
| 持久与恢复 | 冻结05原件、corpus、manifest、真实点数及来源回取 | Linux PVC挂载规划、单写者、backup/restore、UID/GID、只读模型／原件与可写工作目录分离；切换或恢复保持同版本来源身份 |
| K8s资源 | 已确认单H80080GB和分阶段执行 | device plugin／runtime实测；唯一GPU owner、阶段释放、异常恢复、CPU／RAM／PVC／探针/权限的实际manifest；不为每Pod凭空切显存 |
| 出口与离线 | 开发使用本地模型，若干脚本设 HF_HUB_OFFLINE／TRANSFORMERS_OFFLINE | 镜像不能运行时pip/npm/apt下载；模型配置不得隐式网络fetch；目标模型代码、wheels、字体均提前打包；外部MiniMax只作明确可选开发参照 |
| 安全扫描 | 未见本轮真实扫描收据 | 按最终镜像digest＋模型制品SHA＋源码commit绑定 SBOM、许可证、漏洞结果、团队要求的安全审查；不得以静态依赖清单替代扫描通过 |
| 目标验证 | 多个本机或独立Linux有限验证 | 新镜像启动、断网模型加载、完整页解析、固定库读回、GPU转交及Vetting/Advisory真实引用／报告回归；不能用HTTP200/0 findings当通过 |

安全扫描要锁的是**具体交付制品**。今晚可以确定软件家族、隔离边界及候选开关，然后在该边界内完成精确锁、扫描和必要兼容补丁。依赖名字确定后仍要固定 hashes/digests；算法规则改进不必引入新框架，但如果平台扫描包含源码，源码改动仍需按平台要求重新审查，不保证“同一镜像名以后任意改代码免扫描”。

### 1.6 本轮静态身份

| 文件 | 本轮 SHA256 |
| --- | --- |
| `pom.xml` | `91ba5b577d8a60424e76757ee5616b62c2f540d4546bcff5531f251661c42897` |
| `ai_consense_web/package-lock.json` | `e157783e1fa48b2215928789e30aea68004a0087dc09b1446bdcc2c756c38702` |
| `tools/vetting_eval/requirements.txt` | `c1fe3525541d4bfca2b9cd3eac3cc28eb3bc5a3803c6dc6862110a3946cf80a3` |
| `tools/vetting_eval/requirements-mineru-linux-full.in` | `7cd9fd047c3269ccbc389e513146e7b92af38a369f827c25269e7f6696f483a3` |

本轮没有读取环境变量／密钥／业务 properties 内容，没有打开活动 Qdrant DB，没有调用模型、安装、构建、重启、写索引或切换运行配置。只新增本文；上表是文件身份／版本盘点，不是软件控制、来源质量、业务或H800验收。

## 2. 无框表调查实际改变了什么

来源：[无框表库隔离实测交接](./oer-borderless-table-isolated-investigation-20261012.md)，尤其第4–6、9节；版本来自实验`actual-01/run-recipe.json`、`requirements.lock.txt`及安装源码。原始实测保持，没有本轮重跑、重评分或替换05。

| 来源事实 | 技术栈含义 | 接下来应实现的能力 |
| --- | --- | --- |
| Stream数据区anchor文字641/642，真空栏125/125；六路线完整单表均0/5 | Camelot增加了可用的候选观察能力，不能直接宣称无框表已解决 | 多行表头、字段续行、主表边界、空栏与合并关系的通用逻辑恢复；每个结果回指原provider格／glyph，记录变换 |
| 表头拆成多行；Waterproofing Installation Date拆为两行；Network在1326漏整列 | 问题不只是OCR拼字，也不能只按统一行号偏移修补 | 不按固定页、bbox、六列或gold恢复；新来源完整正常输出及独立源评 |
| Note(7)/(8)改变预制件日期含义；Casting Date2*有两天浇筑条件；缩写子表与Notes未闭合 | 再增加一个读文字模型不能自动建立适用关系 | 主表＋表头＋Notes＋星号＋缩写子表＋父条／续页的完整证据组，缺失和歧义保留unknown |
| Hybrid在COM格计数136/167高于lines/lines129/167，但完整表仅2/8对7/8 | 不能全局替换当前有线表解析器 | 有线原路线保持；候选按完整组、源中立资格选择，不按答案逐格混provider |

这些统计分别来自原调查的第4节表、第5节原页观察、第6节回归表。641/642是数据区字面诊断，不能称99.84%整表准确率；0/5来自严格完整表域，不表示所有文字均未读取。05保存HTML逻辑表的声明格域与本轮几何成员域不同，不拼成一条质量曲线。

### 2.1 具体新增包与可以避免的额外包

- 建议纳入扫描：`camelot-py==2.0.0`基础包，`pdfplumber==0.11.9`，以及目标Linux解析出的完整传递依赖。独立Windows实验锁包含OpenCV headless5.0.0.93、PDFium5.14.0、playa-pdf1.1.0、numpy2.5.4、pandas3.0.6、openpyxl3.1.5等；它们不是已验证的Linux二进制锁。
- **基础Camelot也需要OpenCV。** 安装`camelot_py-2.0.0.dist-info/METADATA:21–30`有基础依赖；`camelot/parsers/__init__.py:4`导入Lattice，`lattice.py:8`顶层导入cv2。即使只运行Network／Stream，也不能漏报OpenCV及其原生库。
- 默认PDFium路线不要求额外安装Ghostscript或Tabula。metadata:41–42才是Ghostscript extra；`backends/image_conversion.py:24`默认PDFium并允许fallback。交付若启用Lattice／Hybrid，需锁定渲染后端，关闭未打包后端的隐式fallback，保留实际失败。
- 不加入`camelot-py[ml]`／Table Transformer、`camelot-py[ocr]`或新的视觉业务模型。ML预检权重下载、模型加载、来源推理均0，TLS EOF是下载失败，不是模型质量FAIL；尚无完整来源收益。ML还可能隐式下载timm backbone，不能只锁两个模型文件就称离线可用。
- Camelot native路径不增加Torch或新的Java PDF服务。已有Java服务、Paddle和MinerU保持各自职责。

版本／原生依赖事实由[当前实验recipe](../../.local/state/oer-camelot-isolated-20261012/run-2112-01/actual-01/run-recipe.json)、[实验版本锁](../../.local/state/oer-camelot-isolated-20261012/run-2112-01/requirements.lock.txt)和安装metadata／源码核实。OpenCV／PDFium的Windows wheel不得搬作Linux制品。当前各环境的pypdf、cryptography、numpy版本不同，不整份合并锁文件。

### 2.2 用户澄清：Camelot文字层恢复＋代码解析引擎的接入价值

用户进一步澄清，评估对象是直接让Camelot读取原生PDF文字层、推断表格候选结构，再由我们的代码恢复逻辑表和业务关系；不是讨论用一个provider全局替换其他provider。**该方案有明确接入价值，建议作为原生PDF表格的一条解析分支。** 以下仍为方案评估，不表示已授权实施、修改活动索引或完成质量采用。

实测支持的收益是：五目标无框表已有大量可回指的数据文字和列位置，后续代码可处理多行表头、同编号字段续文、标题／主体分界，并从同版本全页文字与坐标中绑定Notes、星号、缩写子表、父条及续页。641/642字面诊断是候选基础，0/5完整表则说明上述逻辑层尚未完成，不能以该分数否定代码增强方案，也不能提前声称增强后通过。Network在1326漏整列，因而解析引擎也必须发现缺列／缺成员；只处理已返回grid无法恢复没有进入候选的原文，应回到完整原生文字观察或保留失败／unknown。

职责建议为“同版本原生文字／坐标→Camelot候选→可追踪的逻辑表恢复→完整条件证据组→现有索引和来源回取”。候选原网格与原文保持；逻辑格记录来自哪些原格／glyph及变换。规则按布局、对齐、编号和文档层级推断，不硬编码目标页、固定六列或行号减常数。主表恢复与条件组装分别验证，保旧正确有线表回归。

源码接缝核对发现，`tools/vetting_eval/native_pdf_canonical_projection.py:127`当前要求tableGroups/assets为空，`:224–225`及`:296–297`排除table/cell分支。因此不能把接入描述为直接把Camelot DataFrame塞入已支持的native PDF通道；需显式新增表格投影／来源契约，并复用现有完整table-group回取、资产展示及检索组扩展能力。传统Camelot部分在CPU运行；这一新增分支不要求新增GPU模型。完整接入后的质量收益仍须用正常整章输出、整表／条件组和新来源回归验证，本轮未执行实现或重评分。

### 2.3 其他解析残差与用户最新优先级：先补引擎，图纸只识别身份

用户按漏失严重程度及影响范围提出表单、复杂正文、图纸／流程图、有线复杂表、公式、扫描／隐藏OCR、Word特殊内容七类排序，随后严肃明确：**图纸文件完全不参与解析，不在图纸细节上下功夫，知道它是图纸即可。** 本条以后续排除为准：身份路由识别／标记图纸后排除，PDF／扫描格式也不使其重新 eligible；不新增图纸OCR、CAD、尺寸／轴线／符号／工程关系模型或训练。范围内规范插图／流程示意的保全仍按原范围，不建立图纸精读任务。合同／附录中的图纸清单及图号文字不等于图纸正文。

此轮限定原件解析，不把embedding、召回、排序、项目采用或业务回答问题计作parser缺陷。依据[源解析质量审计](../../.local/state/oer-beyond-borderless-review-20261012/source-quality.md)、[表单／Word研究](./parse-stack-forms-research-20261012.md)、[复杂正文／规范插图研究](./parse-stack-layout-graphics-research-20261012.md)和[官方组件能力核对](./parse-stack-primary-sources-20261012.md)，**现有依赖家族＋已建议Camelot足以开展所需增强验证；当前没有新增第三套OCR／IDP框架或大视觉模型的必要性证据。** 这不是现有权重、复杂版式或控件已通过质量验收的声明。

| 优先能力／文件类型 | 应增加的解析代码 | 现有组件及外部依赖判断 |
| --- | --- | --- |
| 表单，第一优先 | 区分AcroForm／Widget、打印扁平表单与扫描表单；保完整字段区、空槽、勾选／未勾选／blank／NA／unknown、父子选择组及OR分支；核原值与可见AP差异 | 原生用PDFBox／pypdf，文字与几何用pdfplumber，视觉控件候选用已有OpenCV和Paddle／MinerU。先补路由、typed输出与导出保全，不必先加独立表单模型 |
| 复杂正文，第二优先 | 多栏lane与读序、编号层级、源旁注归属、父条与跨页续文；保有意义的页尾连接词 | 现有native glyph/font/坐标、PP-DocLayoutV3、PaddleOCR-VL／MinerU。需源结构引擎，不因关系错误就换布局框架 |
| 图纸文件 | 只识别文件身份并排除，不解析细节 | 不为图纸增加依赖、模型、训练或专用服务 |
| 范围内规范插图／流程示意 | 绑定可见主体、caption、legend、notes及所属源段，无法证明完整裁图时保整页；不扩为箭头／工程语义推理 | 复用PDFium／PDFBox渲染、现有布局与原件资产；暂无新增CAD／流程图模型理由 |
| 有框复杂表格 | 物理格、row/col/span与逻辑格、嵌套和续表关系；保护旧正确整表 | pdfplumber/native rulings、Camelot及既有Paddle/MinerU表格路线；另一个表格模型的必要性须由完整输入内残差证明 |
| 数学公式 | DOCX保OMML分子／分母／上下标；PDF保符号位置、公式结构及完整释义，扁平文本不得充当结构通过 | POI／OOXML及既有公式识别路线先核；不先引入新的公式服务或模型。结构未恢复时保原公式图和unknown |
| 扫描／隐藏OCR层 | 判可见层资格，保原图对照；隐藏旧OCR不作可靠native，完整输入内漏符号再调识别 | 现有渲染、native观察与Paddle/MinerU；此类是路由和质量门，不必新增另一OCR家族 |
| Word特殊内容 | 字段缓存／显示、内容控件、AlternateContent、修订／划线与继承样式可见态；保raw及原文件 | POI／OOXML＋已有LibreOffice核对；当前无增加商业Word引擎的必要证据，不修改冻结Drafting |

具体归因支持上述先后：APL原19印刷空槽未进入正常任务；AoA跨页OR在raw/middle中有3个、default Markdown只剩2个；APU清单图号prefix／owner被crop排除；这些要先修覆盖／导出。AoA完整输入内的▲遗漏则是识别残差，应另行用来源监督检验参数及必要训练。不能为识别器未收到的区域训练，也不能把所有失败都归为代码。SL插图主体、完整crop、完整源组的分母各异，保存metadata或盲目扩大crop不能认证完整图文关系。

解析引擎建议统一为四个接缝：①同版本原件观察（glyph、控件、OOXML、完整raster）；②区域／任务覆盖和现有provider路由；③表单／条款／表格／公式的可追踪结构恢复；④正常raw到结构化及导出的显著成员保全。OpenCV已在相关依赖链，但需要使用它的独立worker仍要明确完整Linux版本锁，不假定其他镜像已安装就可调用。当前候选原生路线不增加新的GPU模型；若完整输入和协议已正确仍有独立来源残差，可在已有Paddle/MinerU框架内做必要小模型／权重训练，记录新模型制品与扫描身份。

PyMuPDF可简化部分API，但无证据表明仅换库能修好上述源关系；其AGPL／商业许可需单独处理。Docling等会增加模型和运行依赖，也没有本批原件的增量质量成绩。本轮均不纳入首版必需清单。后续源码／模型变动仍按平台规则扫描，不承诺保留依赖家族即可免扫。

本节为源证据与官方能力研究／实现建议，本轮没有执行新解析、模型训练、安装或服务／索引切换；不把历史AoA/APU/APL等问题概括成05全库的现状。优先表单和复杂正文，再处理范围内规范插图保全及其他残差，质量结果分别由完整输入、正常输出、源结构和旧正确项回归验证。

## 3. Vetting／Advisory与原型对技术栈的影响

原型：[ConSense-CAC-Proposal-DemoV2.0.html](../../Ref/communications/ConSense-CAC-Proposal-DemoV2.0.html)，SHA `92ec31ed62ba746e7cb7de236ea80d7fb642ea2bb06af87fcaebf6e0dbda65fb`。原型是离线演示，本次仅静态源码评估；没有浏览器视觉／点击验收。其文件位置、静态问题与检查方式留在[静态调查收据](../../.local/state/communications-prototype-analysis-20261012/static-review-receipt.json)。

### 3.1 大部分交互可以复用现有实现

| 产品要求 | 已有真实源码基础 | 调整范围 |
| --- | --- | --- |
| 文件／问题维度／条款导航、问题原文与理由建议 | `VettingView.vue`；现有finding与证据DTO | 借用原型导航组织，继续消费真实finding／范围和来源身份，无需换前端框架 |
| 核对底稿与独立核对状态 | `VettingChecklistService.java:35–67,97`按project/run/snapshot读写，默认unchecked，旧snapshot拒绝 | 人工核对状态与自动审查结果分开。不能用原型`checklistResult:8896`的无finding默认pass替换 |
| 实际审查覆盖与失败 | `VettingView.vue:808–850`显示topic/packet、gateway提交、budget/failure及required/missing成员 | 覆盖矩阵从执行账本计算，问题数量另列；零发现不构成通过 |
| 人工裁决与重跑／导出版本 | `VettingService.java:307–332,605`按fingerprint/revision复用及拒绝过期来源导出 | 项目＋报告revision＋finding身份；继续现JPA/SQL，不用新数据库保存团队备注 |
| 历史答案自己的引用 | `AdviceView.vue:289–295`按所点击message.sources切换 | 保留逐消息引用；原型`renderCitationPanel:10393`取最后回答的方式不应替换现有逻辑 |
| 原图／完整表组回取和展示 | `CanonicalEvidencePanel.vue:105–112`；`api/canonicalEvidence.ts:356,389` | 补固定来源资产映射及缺图／跨页unknown；无需新的图片前端或视觉embedding |
| PDF／DOCX／JSON报告 | `VettingReportWriter.java:104–147,351–366`已有PDFBox/POI导出 | 使用真实report/source版本；不复制原型HTML-as-Word与raster PDF导出实现 |

前端路径相对于`ai_consense_web/src`；Java路径相对于`src/main/java/com/consense`对应包。原型“无finding即通过”“0意味着已走查”“风险最多10条”及BQ缺失即不通过均需纠正；它们是业务语义问题，不构成增加第三方框架的理由。固定范围继续GCC/GCT/适用SL技术部分；未填2a模板不进入Vetting／Advisory，实际项目三稿按生成稿、来源版本与采用状态处理。合同正文的Bill编号／名称／引用变量保持。

### 3.2 两模块真正需要补齐的共同接口

1. **固定标准与项目证据双来源。** `FixedBaselineProperties.java:9–16`默认disabled；`FixedBaselineSourceController.java:54–90`和Advice的`reference_only`是候选接缝。固定05不能假作项目canonical源或项目已采用条款。
2. **显式固定来源回取。** 固定sourceId不能拼进项目`/sources/{documentId}`。当前前端Citation尚无`fixedBaselineEvidence`，`AdviceView.vue:539`和`VettingView.vue:152,1114`仍以canonicalEvidence为主；需要DTO／UI接入，单独index ready不代表业务已接通。
3. **完整来源组。** 检索命中扩展父条、条件、例外、Notes、完整表及相关修订，保存required／present／missing／unknown与版本。关系存于当前结构化索引／来源清单，不需要引入图数据库。
4. **模型投影与原始证据分离。** 模型得到完整业务正文和必要限定、角色／采用关系及简洁source pointer；坐标／raw／资产审计放来源服务和持久存储，按需回取。不能把全量原始JSON重复塞入每个prompt。
5. **结果状态及有效期。** 自动检查至少分checked-pass／issue／insufficient-evidence／not-completed／not-applicable；人工确认单独记录。未执行、预算略过、失败不能默认通过。
6. **会话与范围绑定。** Advisory区分项目合同咨询和显式标准解释reference_only，逐答案绑定query scope、source snapshot、模型profile、证据和引用；改变来源版本后旧答案保持历史身份。

本轮固定库候选隔离测试实际110/111，新共用client、Advice、Vetting与controller共21项均通过。唯一`CanonicalPageExcerptTest`失败有相关canonical方法及测试归一化内容相同的静态归因证据，指向旧inline断言与已采用字典导航行为不一致；没有称完整Advice文件字节相同或实际重跑旧版。它仍为失败，未因归因而改成通过。见[归因收据](../../.local/state/oer-advice-page-excerpt-regression-attribution-20261012/review-01/attribution-receipt.json)。这些是离线候选，不是新业务部署／真实模型验收。

### 3.3 展示原图与让LLM看图分开

首版沿用已确认Q10=A：通过相关文字找到依据，向用户返回准确原图／完整表组。这条路线需要PDF栅格化、资产存储与引用UI，已有工具可承担，不要求新增CLIP、视觉embedding或另一套业务VLM。

当前`LlmClient.ChatTurn`的content为String，OpenAI transport也序列化字符串。数据库存有图片路径、前端显示图片、返回JSON表资产均不证明LLM收到图像。若以后明确采用模型看图，应新增真实多模态消息内容、processor／像素和token预算、版本一致性及完整图表组绑定。Qwen3.8-27B官卡有native vision能力，因此可以先评估同一业务模型的视觉路径；不能在本轮未经测试就宣称已替代OCR，也不必先另引入一个大视觉模型。

### 3.4 完整交付范围：Vetting、Advisory及共用服务的技术栈判断

本节根据用户再次明确的整体评估范围补充当前源码快核。它不把业务缺口当parser错误，也不将上一节解析优先级解释为排除业务模块。以下仍为技术建议／源码能力，不是本轮真实模型、在线部署或H800验收。

| 模块／服务 | 实际依赖与职责建议 | 需要补齐的实现 | 首版新增外部栈判断 |
| --- | --- | --- | --- |
| Vetting检查编排 | Java/Spring/JPA/SQL；规则检查＋主题取证＋LLM结构化审查，保存任务／packet／finding／源snapshot | 完整执行覆盖、证据不足／未完成／不适用等状态；按packet断点、幂等、执行owner／lease，明确失败与重跑／恢复 | 先用已有数据库和有界worker实现；单实例不以Redis/Kafka/Celery/Temporal为必需 |
| Vetting人工核对与报告 | Vue检查视图、SQL人工状态与版本；POI/PDFBox导出PDF／DOCX／JSON | 自动结果与人工裁决分开，审查／来源／报告revision绑定；已过期结果不冒充当前报告 | 不新增浏览器截图／Chromium报告服务，也不引入新关系库保存评论 |
| Advisory范围与会话 | Java/API/JPA/SQL消息；项目咨询与显式reference_only标准解释 | 显式session与会话范围；受控历史上下文、逐答案源snapshot／profile／证据；版本变化后历史保持 | 不需要新的memory数据库或Agent框架。现有消息历史不等于完整多轮模型session |
| Advisory引用与展示 | Vue、PDF.js、现有CanonicalEvidencePanel、Mermaid/DOMPurify | 固定来源与项目来源分别回取；点击每条答案展示其依据；范围内完整原图／表组；生成条款流程图 | Mermaid和清洗依赖已有。通用Markdown／可复制LaTeX排版是可选轻量增量，当前不加入首版必需清单 |
| 两模块共用检索／证据 | BGE-M3、BGE-reranker-v2-m3、BM25、Qdrant、来源资产服务／PVC | 固定与项目双来源、结构扩展、采用／修订关系、完整条件和表组；raw保真与模型正文投影分开 | 沿用现框架；Qdrant Server是第0节交付目标候选，不引入图数据库或另一RAG平台 |
| 两模块共用业务LLM | 独立vLLM模型profile，Qwen3.8-27B官方FP8首测、M3开发参照 | 最终wire输入预算／输出预留、结构化结果验证、重试及错误状态、源绑定；长窗和两任务分别测 | 不必为每个模块加载另一模型；独立环境和任务协议，不能用M3 Drafting成绩代替两模块验收 |
| 线上任务及K8s | CPU业务/SQL/Qdrant/资产持续运行，GPU阶段依赖独立 | 在线query embedding/rerank同样需资源；GPUowner交接、ready/超时/失败恢复、持久任务状态 | 首版采用已有K8s和有界调度代码；未测共驻不默认安全，未新增Ray/KServe调度平台 |

**Vetting任务当前不是自动断点续跑。** `VettingService.java:63–78`为内存locks/completions、有界单worker（1线程／队列8）和SQL run记录，启动恢复将QUEUED/RUNNING标为FAILED/interrupted供重跑；`:182–198`提供进度／任务查询。`VettingView.vue:261–264`已有轻进度轮询。需要提高可恢复性时，补SQL packet checkpoint、幂等和唯一执行者，不把当前实现称为重启继续。多副本必须另核跨实例owner／lease，内存锁不构成分布式锁；首版无需为显示进度引入WebSocket或消息队列。等待较长的Advisory请求若改任务式提交／查询，可共用此类持久任务机制，当前不宣称已经实现。

**Advisory消息历史与多轮模型上下文分开。** `AdviceService.java:610–627`逐消息保sources/model/sourceScope/basisScope；`:876–887`只支持同项目／范围缺证回答的有界澄清，`:271–286`模型输入为本次问题／澄清背景和证据，未自动注入整份项目历史。`AdviceView.vue:287–295`已按被点击消息自身引用切换；这是应保留的已有能力。固定库后端候选`:233–255,891`和前端`api/types.ts:717–725`／`AdviceView.vue:539`之间仍缺fixedBaselineEvidence契约及回取入口，不能由05 index-ready追认为业务已接通。

**展示的轻量增量须按输出要求决定。** `AdviceAnswer.vue:36`普通文本按字面显示，显式Mermaid由`answerFlowchart.ts:22–40`及既有Mermaid／DOMPurify严格处理；`useMarkdown.ts`只有极简标题／列表／引用等代码且未接入该回答组件，不能称完整CommonMark支持。package.json当前没有KaTeX／MathJax，若最终必须可复制LaTeX排版可单独选一个KaTeX类组件并锁定扫描；首版保原公式图及结构化内容不要求它。不得把显示公式的库当成修复源公式语义的工具，也不得把生成合同条款流程图误作重新启用图纸文件解析。

本次新增的是整体模块评估和源码状态说明，未修改业务代码、引入库、执行模型调用或切换服务。所有服务镜像／依赖／模型／字体仍须第5节的制品与扫描绑定；“无需新框架”不代表当前功能或安全发布已通过。

### 3.5 新demo HTML也是交付评估依据：交互要求到真实依赖的映射

用户进一步明确新的demo HTML也须纳入。Root本轮重新列举`Ref/communications`，当前只有`ConSense-CAC-Proposal-DemoV2.0.html`，1010428 bytes、SHA仍为第3节所列`92ec31ed62ba746e7cb7de236ea80d7fb642ea2bb06af87fcaebf6e0dbda65fb`。因此沿用同版本静态审查并补下表，不虚构更新版或以该确认代替浏览器交互验收。评估输入同时包括新demo产品流程、实际代码、OER源证据、已确认比赛范围与H800/K8s约束。

| 产品流程（区分demo与现有代码扩展） | 正式系统应保留／实现的行为 | 技术栈及状态判断 |
| --- | --- | --- |
| demo整包上传、源集与文件状态 | 上传真实原件，按内容身份、源SHA、版本和采用角色管理，排除材料显式标记 | demo`:6996–7016,11090–11105`保File对象并同名替换，`:7455–7465`模板只改名字；复用SourceDocument/API/现有解析，不新增上传框架，图纸识别后排除 |
| 文件／条款／问题维度导航、原文旁问题卡与核对底稿 | 在真实项目／run／snapshot下定位原文、问题、完整依据；切换不串项目或来源 | Vue现有Vetting视图／证据组件可承担；补统一ID和状态契约，无需换UI框架 |
| 核对勾叉、问题数／覆盖指标 | 自动结果至少区分通过、问题、证据不足、未完成、不适用；人工项独立 | demo`:8895–8904`二档逻辑与无finding默认pass不直接采纳；用实际执行账本＋SQL，不因零问题证明执行完整 |
| 问题裁决、团队备注、重跑复核 | 项目＋报告版本＋finding身份保存；注明源变化、旧裁决与当前证据差异 | demo`:10490–10506`localStorage示例不具完整项目／版本绑定；正式用已有JPA/SQL，不新增评论数据库 |
| Advisory快速问题、连续咨询、证据抽屉 | 真实取证／模型结果和显式会话范围；每个答案有自己的来源、限定和版本 | demo`:10538`按quickQuestions匹配仅是演示；`:10393`最后回答引用不能代替正式逐答案引用。现有API/SQL/检索/vLLM补接缝，不增加Agent平台 |
| 从审查问题进入咨询（现有代码扩展，demo没有独立引用草稿handoff） | 携带finding、未验证引文及source snapshot；来源过期提醒，答案不自动回写审查裁决 | 正式已有`advice/reviewHandoff.ts`、`VettingView.vue:1130`和`AdviceView.vue:466–481`入口，需完整身份／确认行为回归。内部模块交接不需消息队列 |
| demo原文证据展示；完整表／原图为Q10=A所需增强，条款流程图为现有代码扩展 | 固定与项目来源分路回取；准确显示本答案依据；流程图生成不恢复图纸解析 | 现有证据组件、PDF.js、Mermaid/DOMPurify可用；Q10=A保留，图纸文件只识别排除；不由图片展示推出新增VLM |
| 核对报告／导出 | 完整问题与覆盖、裁决、来源版本及字体／分页，从冻结报告snapshot输出 | 沿用POI/PDFBox/JSON。demo raster PDF/HTML-as-Word不是正式导出路线，不增加Chromium服务 |

demo中的排除材料缺失即不通过、已撤回风险数量上限、未填NTT/SCT/SCC模板参与Vetting／Advisory等与当前范围冲突的示例均不能转成需求；合同中的Bill编号／名称／引用仍按已确认规则。demo的Drafting展示仅作为背景，本轮不启动冻结模块改动，也不把硬编码文本／span追认为新版模板通用能力。

这份demo影响正式数据契约、状态和导航设计，并不迫使更换整体技术栈。若后续明确新增输出格式或交互要求，再按具体差额锁前端小依赖／模型制品。当前未执行demo脚本、浏览器验收、安装、业务实现或服务切换；本轮为整体技术栈／产品映射评估。

## 4. LLM、GPU与容器版本的具体判断

业务LLM仍以[既有架构报告](./consense-llm-oer-architecture-analysis-report-20261012.md)中的Qwen3.8-27B官方FP8为第一顺位验证候选，revision `017b9c7af6b5689d5dd426a76e0bc077eb5ca20a`；Flash-Next保留复杂任务挑战者，但今晚不纳入默认必装制品。M3作为开发质量参照，不默认为比赛外网依赖。至少256K、优选512K总窗口包含最终wire输入、历史、推理与输出，RAG不豁免这个要求。

[vLLM维护者27B recipe](https://recipes.vllm.ai/Qwen/Qwen3.8-27B)列出vLLM0.17.0+与Transformers≥5.8.0，并使用自身Qwen3_5配置／视觉processor。它支持先验证现有Linux0.19.1/5.10.1底座的兼容性，但其Blackwell／Ascend实测不证明单H800长窗通过。此处的首测版本是部署候选，业务加载成功之前不冻结为已接受profile。

本轮通过官方GitHub release API与tag源码再次核对了以下差异，保存[官方镜像默认参数](../../.local/state/delivery-stack-assessment-20261012/official-vllm-docker-defaults.json)及[官方release摘要](../../.local/state/delivery-stack-assessment-20261012/official-release-summary.json)：

| 制品／配方 | CUDA／OS／Torch事实 | 对今晚交付的影响 |
| --- | --- | --- |
| 已保存本地Linux MinerU full0.19.1 | Torch2.10.0+cu128，已有CUDA12.8成功证据 | 优先复用其hash lock，再建立目标镜像；不从零重新解一套版本 |
| 官方vLLM tag0.19.1 Dockerfile | 默认CUDA12.9.1、Ubuntu22.04、Python3.12，requirements Torch2.10.0 | 不能仅凭相同vLLM tag把官方镜像称为本地cu128同一配方 |
| 官方vLLM tag0.28.0／0.29.0／0.31.0 Dockerfile | 默认CUDA13.0.3、Ubuntu24.04、Python3.12，requirements Torch2.13.0 | 新版不是现12.8配方的无成本补丁；驱动、CUDA、原生内核与扫描范围须重新验证，不盲选latest |
| Qdrant发布候选 | 官方release API记录1.19.2为2026-10-05非prerelease，现Python client1.19.1 | 同家族单实例Server的候选版本；兼容、镜像digest、迁移／检索仍未测试 |

官方源包括[v0.19.1 Dockerfile](https://github.com/vllm-project/vllm/blob/v0.19.1/docker/Dockerfile)、[v0.31.0 Dockerfile](https://github.com/vllm-project/vllm/blob/v0.31.0/docker/Dockerfile)、[Qdrant1.19.2 release](https://github.com/qdrant/qdrant/releases/tag/v1.19.2)。上述核查没有拉取镜像或安装包。镜像默认值是tag源码事实，不是超算已运行事实，也不能由它们推定主机驱动兼容。

不要将MinerU的vLLM版本与业务LLM服务强制绑成同一个环境；若业务首测兼容失败，只修业务模型镜像。Flash-Next专用recipe要求vLLM0.29.0+及专用镜像，MinerU full4.0.10要求vLLM<0.29.0，且Flash完整FP8不能单H800全驻留；今晚不为未闭合的大模型配方增加SGLang、CPU专家引擎、lookup offload及另一套自编译内核作为必需项。

单卡部署必须同时安排在线query embedding／rerank。文档向量预计算不能取消它们；可先在LLM推理前批量取证，再交卡解码。是否允许LLM与小E/R共驻取决于实际峰值，未测时不预设能共驻。默认独占GPU Pod阶段要真正退出再交资源；[K8s官方GPU调度](https://kubernetes.io/docs/tasks/manage-gpus/scheduling-gpus/)使用扩展资源，不按显存百分比划卡。vLLM sleep释放显存不等于归还Pod的GPU申请，参见[官方sleep说明](https://docs.vllm.ai/en/v0.19.1/features/sleep_mode/)。

## 5. 今晚的有限实施顺序及扫描交付物

本节是下一步工作清单，本轮没有执行安装／扫描／目标部署。先确定依赖集合和职责，让后续逻辑恢复、证据绑定与产品界面大部分调整在已锁依赖内完成；平台若扫描源码或要求整镜像复扫，后续代码变动仍遵守其规则，不能承诺免扫描。

1. **先报两项组件家族增量及其完整传递依赖。** native-table CPU候选（含Camelot传递原生库）与Qdrant Server单实例制品。将Paddle、MinerU full、BGE、业务vLLM四种GPU工作负载隔离，不合并Python锁；这是依赖边界，不是四个GPU常驻Pod。首版按第0节终止式阶段Pod处理，不默认sleep归还资源或共驻。
2. **复用已有可靠锁。** web package-lock和MinerU full Linux hash lock已有证据；补Paddle／retrieval／native-table的目标Linux hash lock与离线wheel集合，确定OS、Python ABI、JDK17 patch、Node/npm patch、LibreOffice及字体。模型包括准确revision、tokenizer、processor、template和确需的自定义代码，不现场下载。
3. **优先核旧业务版本。** 官方明确[Boot2.7.18结束Spring Boot2.x OSS支持](https://spring.io/blog/2023/11/23/spring-boot-2-7-18-available-now/)。现MySQL5.7／Flyway7、PDFBox2.0.31、POI5.2.5、PDF.js4.0.379和前端构建依赖一起交SCA。已知EOL与实际CVE命中分开；如果扫描策略不接受当前版本，进行可兼容补丁或受支持Spring Boot版本迁移及回归，不把旧版本直接宣称发布通过。[MySQL8.4发布模型](https://dev.mysql.com/doc/refman/8.4/en/mysql-releases.html)可作为关系库LTS候选依据；本轮没有数据库迁移成绩。
4. **绑定最终制品。** 每个容器记录image digest、OS包清单、Maven实际resolved tree、npm lock、pip hashes、模型/字体/可执行文件SHA、软件与模型许可证，以及扫描报告身份。镜像tag不是digest，直接依赖pin不是传递lock；不把Windowsfreeze冒称Linux锁。
5. **做目标正常验证后再采用。** 新镜像离线启动、模型加载、完整文件解析、固定库点／payload／来源读回、阶段GPU释放与重新加载、Vetting真实结构化报告、Advisory逐消息引用和unknown。业务接缝候选默认关闭保持，不能用目录存在、HTTP200或零finding代替这些结果。

不纳入本次首版必需清单：LangChain／LlamaIndex／Dify、Neo4j等图数据库、Elasticsearch／OpenSearch、Redis、Kafka／RabbitMQ、Celery／Temporal、MinIO、CLIP／新视觉embedding、Camelot ML／OCR extras、新增Table Transformer、Tabula、Ghostscript、Chromium报告服务、SGLang／Flash-Next专用offload引擎。已有MinerU/Paddle真正需要的依赖继续保留，不因为某个同名包在此列表中就删除现有依赖。将来某项出现明确必要性和实测收益时，另建制品身份及扫描变更；本轮未添加或卸载这些组件。

## 6. 给部署同事的简短交接

> 沿用Vue/TypeScript、Spring/JPA/JDK17、MySQL、POI/PDFBox和LibreOffice。OER分Paddle、MinerU full4.0.10、BGE retrieval及native-table独立环境；新增Camelot2.0.0传统候选基础包，默认仅离线候选，保pdfplumber0.11.9有线正确路线。超算向量库目标候选单实例Qdrant Server1.19.2+PVC，client1.19.1，待兼容／迁移验证；Local05作为不可变开发基准保持，不直接拷目录冒作Server。业务LLM独立vLLM，先验证Qwen3.8-27B官方FP8至少256K／优选512K；现Linux0.19.1/cu128底座仅是首测候选，不把最新CUDA13镜像直接替换进去，也不默认带Flash-Next。GPU首版采用前一阶段结束后再启动下一阶段的独占Pod，CPU数据库／业务持续运行。首版原图／整表返回用户，暂不新增业务视觉模型。先锁完整Linux依赖、模型／字体和镜像digest并扫描，优先处理Boot2.7等旧版本；质量、兼容及安全状态分别记录。当前没有目标镜像构建、扫描通过或H800部署验收。

