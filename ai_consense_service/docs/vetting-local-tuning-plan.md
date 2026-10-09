# 本机 vetting 参数调优计划与结果日志约定

核对日期：2026-10-02。范围：文档解析、OCR、分块、embedding、混合检索、rerank、上下文组装及本地 LLM。本文件记录初始只读核对、工具参数入口及后续实验计划；初始参数核对没有执行模型调用、OCR 或建索引。后续真实提示词/runtime 实验与 probe 独立留档，汇总见 [讨论与决策记录](vetting-discussion-and-goal-20261002.md)。每次执行须产生独立日志及与基线的逐项比较，失败和未完成结果也要保留。

**当前结论：4060 已在历史配置和固定输入上运行现有链路，源语义质量尚未通过验收。** 技术执行、引文字面定位、人工审核功能和合同审查质量分别记录。较大模型、返回 `[]`、HTTP 200、合法 schema 或 `located=true` 均不能替代源语义判断。历史实验、不可变报告及保存的 run 不覆盖；自动实验登记及历史导入由统一日志工具负责，本文件不冒充已执行的新实验。后续执行目标见 [当前 goal 修订](vetting-current-goal.md)。

本机3B/4B/7B仅作为机制验证、低成本回归和受限基线；用户明确比赛H800主模型不会采用4B/7B。比赛主模型候选以至少27/32B级dense或更大MoE作为规划方向，仍需在比赛环境验证，并公平比较大模型plain prompt与最小runtime。本机参数与小模型失败不能直接外推H800容量、合同质量或复杂agent的必要性。

## 1. 机器与版本基线

本轮通过 Windows CIM、`nvidia-smi`、Python `importlib.metadata` 和本地配置/权重文件只读核对，未初始化推理引擎。

| 项目 | 本机已核对值 | 比较时需要保留的边界 |
| --- | --- | --- |
| GPU | NVIDIA GeForce RTX 4060 **Laptop GPU**；8188 MiB；driver 572.83 | 这是笔记本 GPU；采样显存不等于理论峰值，功耗/温度也可能影响延迟 |
| CPU | Intel Core i7-14650HX；16 核、24 逻辑线程 | OCR thread 数不应直接等于全部逻辑线程；记录同时存在的进程 |
| RAM | 34,084,429,824 bytes，约 31.74 GiB；机器规格 32 GB | 全机 used RAM 与某进程 RSS 分开记录 |
| OS | Windows 11 专业版，10.0.26200，64 位 | Windows DLL、CPU OCR 路径不能直接视为 Linux/H800 已验证 |
| Python / Torch | 3.12.14 / 2.7.1+cu118 | `cu118` 是本机 wheel 标签，不能写成比赛驱动版本 |
| OCR | rapidocr 3.9.2；onnxruntime 1.22.1 | 三份 ONNX 的身份见下节，不能仅凭包版本描述模型 |
| Embedding / rerank 依赖 | sentence-transformers 5.2.0；transformers 4.57.6 | 默认 tokenizer/activation 行为依赖这些版本 |
| 检索依赖 | qdrant-client 1.19.1；rank-bm25 0.2.2 | Qdrant Python local mode，不是远程 Qdrant 吞吐测试 |
| Pilot 解析依赖 | pypdf 6.19.0；pypdfium2 5.13.0；python-docx 1.2.0 | 真实服务解析主要是 Java PDFBox/POI，不能混称同一解析实现 |
| 比赛机器 | 用户确认 27 vCPU、250 GB RAM、1×H800 80 GB、650 GB high-performance + 1250 GB general-purpose storage | 尚无 H800 实测；比赛 OS、driver、实际可用磁盘、预带权重/索引条件仍需确认 |

本机环境路径为工作区 `tmp/vetting_eval_env`、`tmp/vetting_models` 等。日志需写实际绝对路径、运行时身份及源/类/JAR 哈希，不能只写“latest”。实验机器空闲状态、冷热模型、缓存命中及并发活动是比较条件。

## 2. 当前参数与实现位置

表中的“应用值”是当前源码/`vetting-local` profile 或实际冻结请求中的显式值；“库默认”是本机已安装版本的配置/源码值；“计划”尚未执行。源码值不自动证明某个旧 immutable JAR 已部署了该变更，具体运行以其冻结清单为准。

### 2.0 本轮新增工具能力及验证版本

初始核对发现embedding/reranker共用token/batch以及索引身份不完整，随后只修改调参工具，保持默认512/4：

- `server.py:31` 独立读取 `CONSENSE_EMBED_MAX_TOKENS` / `CONSENSE_EMBED_BATCH_SIZE` 与 `CONSENSE_RERANK_MAX_TOKENS` / `CONSENSE_RERANK_BATCH_SIZE`。正整数校验，token上限8192；`device()`只解析一次。`health():250`返回两模型独立resolved device/dtype/token/batch/name/revision、实际BM25库默认及检索默认设置。
- `server.py:207` signatureVersion2补入embedding有效device/dtype/token/batch，保留revision/normalized/corpus；旧disk或cached metadata读取在模型/数据库使用前返回409重建提示。只改reranker不改变embedding索引身份。
- `eval.py:518` 新增 `--embed-max-tokens` / `--rerank-max-tokens`；`--max-tokens`仍对未显式覆盖的模型提供fallback；embed/rerank batch有各自别名。`embedding_signature():336`及`require_index_signature():342`按完整embedding身份检查，输出独立runtime配置。
- 独立离线测试覆盖默认/拆分/非法值、实际加载器及encode/pair绑定、旧索引拒绝、embedding身份变化与reranker-only不失效、health/selected device一致。推理路径均使用替身，不运行HTTP、GPU或真实索引。

实际离线结果：2026-10-02，12 tests、0 failure/error/skip、exit0；日志及冻结测试身份在 `tmp/local_model_parameter_tests/20261002T041643Z`，`test_manifest.json` SHA256 `823d1b0cbe77d458215039ae83e537e8d6d3c42d258b9cafd2a3ffb09c4fee6a`。`artifact_binding.json`提供与运行清单hash相同的源码副本；后来追加结果说明的MD与测试时的MD版本明确区分。它不是Java155+12的合并全量运行，也不是任何模型参数组合的质量验收。

后续补入 CLI 五阶段自动日志后，最新冻结离线结果为 16 tests、0 failure/error/skip、exit0，见 `tmp/local_model_parameter_tests/20261002T042903376420Z/test_manifest.json`，SHA256 `9a5d43bac6f492bc0ce8581affebe49c88b86b53ae709f9ac84fd0c90ff5830e`。初次失败、旧 12 项证明和新 16 项证明分别保留，不能合并成一次运行。真实 CLI probe 已独立登记，但没有执行 OCR、embedding、rerank 或 LLM。

HTTP服务的新环境变量和health行为仍未实际验证。2026-10-02随后完成独立CLI小语料实验：在fresh目录创建一次embedding512/batch4的V2索引，对比rerank512与1024、batch4固定；不是驻留HTTP服务升级或全44-source实验。原索引、历史proof均保留。1024/batch2组合仍planned；本轮实际结果及来源边界见下文4.1。库/预处理完整身份仍须随日志额外冻结。

### 2.1 PDF/DOCX 解析及 OCR

| 参数 / 行为 | 核对值与来源 | 当前含义及边界 |
| --- | --- | --- |
| OCR 路由 | `DocumentParser.java:196`：去空白后字符数 `<40`；或 U+FFFD/U+0000 数量 `>max(3, visible/10)`；或字符数 `<200` 且存在较大图像 | 40 是 `ConsenseProperties.java:57` / `application.yml:81` 应用值；并非只依据“有没有文本层” |
| 较大图像判断 | 图像宽≥400、高≥200；嵌套 Form 递归最多检查 depth 4，见 `DocumentParser.java:208` | 图表/矢量页仍可能需要视觉复核；此 heuristic 不证明文字准确 |
| OCR raster DPI | **200 DPI、RGB**，`DocumentParser.java:143`；CLI `eval.py:528` 默认同为 200，可用 `--dpi` | Java 当前为硬编码；提高 DPI 还会受到 OCR 内部 resize 限制 |
| 空白物理页 | 空文本且渲染图全部像素 RGB=白色才记 `blank`，`DocumentParser.java:146,188` | 不是空文本就忽略扫描页；近白噪声页可能进入 OCR |
| OCR page 上限 | 配置 `max-ocr-pages=60` / `maxOcrPages=60` | **当前仅声明，解析器无消费引用，未生效。** 不得据此声称只处理 60 页或已限制长扫描文件 |
| OCR endpoint/timeout | `POST 8868/ocr`；Java local profile timeout 180,000 ms | `server.py:267` 单图片≤25 MiB、≤40,000,000 pixels；这些是生效的输入限制 |
| OCR runtime | RapidOCR ONNX Runtime；配置 `use_cuda/use_dml/use_cann/use_coreml=false`，CPU provider 路径 | 应用没有启用 GPU OCR；本轮未新建 session，所述为已安装配置及运行实现 |
| ONNX threads | **intra-op=4，inter-op=1**，`ocr_runtime.py:24` | 应用显式覆盖；不是库配置中的 -1 自动线程数 |
| OCR 步骤 | det/cls/rec=true；`Global.use_preprocess_img=true`；min/max side=30/2000 | RapidOCR 3.9.2 `config.yaml:2–22` 库默认，应用未覆盖；需把默认展开到运行清单 |
| OCR 检测 | det limit_side_len=736、limit_type=min；thresh=.3、box_thresh=.5、max_candidates=1000、unclip_ratio=1.6、dilation=true、score_mode=fast | `config.yaml:131–151` 库默认；与最终文字保留阈值不是同一个参数 |
| OCR 文字阈值 | `Global.text_score=.5` | `config.yaml:2` 库默认；HTTP 返回引擎输出，没有另外加业务置信度阈值。低置信不能自动证明合同文字错误或无效 |
| OCR 方向分类 | Cls shape 3×48×192，batch=6、threshold=.9、labels 0/180 | `config.yaml:154–167` 库默认 |
| OCR 识别 | Rec shape 3×48×320，batch=6；return_word_box=false、return_single_char_box=false | `config.yaml:170–182`、Global 库默认；返回 line box，不是表格单元格语义 |
| DOCX 有效文字 | 原 block/style/table/footnote/endnote 与原文范围保留；Word 删除/删除线作为 metadata，effective text 排除 | OCR 不具备相同删除状态能力。源页的删除线、表格对应关系、图形含义必须单独视觉核对 |

**OCR 权重已经核实，非版本猜测。** 本机 `tmp/vetting_eval_env/Lib/site-packages/rapidocr/config.yaml` 指定 Det/Rec 的 PP-OCRv6 small，Cls 配置 namespace 为 PP-OCRv4/mobile。默认 resolver 选到以下文件；本轮文件 SHA256 与 rapidocr 3.9.2 `default_models.yaml` registry 相同。应用目前只覆盖 threads 和可选 `Global.model_root_dir`，没有把这三份模型路径写进服务配置，因此后续运行仍须冻结默认配置和权重哈希。

| 用途 | 实际本地文件 | SHA256 |
| --- | --- | --- |
| Detection | `rapidocr/models/PP-OCRv6_det_small.onnx` | `090f04abcd9d9a7498bc4ebf677e4cb9bdce1fe4197ddb7e529f1ef44e1ff94f` |
| Recognition | `rapidocr/models/PP-OCRv6_rec_small.onnx` | `6f327246b50388f3c176ae304bd95767ea6dc0c9ae92153ef8cbe210b3c14884` |
| Orientation classification | `rapidocr/models/ch_ppocr_mobile_v2.0_cls_mobile.onnx` | `e47acedf663230f8863ff1ab0e64dd2d82b838fceb5957146dab185a89d6215c` |

权重目录的存在和 SHA 匹配不替代某次 session provider/实际 loaded model 记录。本轮未调用 OCR，也未验证其它 OCR 模型。PaddleOCR-VL 等仅作为后续独立候选，不称已经安装或测试。

### 2.2 Corpus、embedding 与 rerank

| 参数 | 当前值与源码 | 比较时必须控制 |
| --- | --- | --- |
| 生产分块 | `VettingCorpus.java:110` 组块约 **1400 Java UTF-16 units**，换物理页或 heading 也 flush；字符累加包含换行 | 不是 1400 tokens；短组块不能跨明确来源/heading 来提高召回 |
| 生产长 block 切片 | `VettingCorpus.java:116–127` 长块 slice≤1400，step=1200，名义 overlap=200；边界避开 surrogate pair | 保留原 blockId、UTF-16 start/end、页/anchor。语料改动需新 corpus/IDs/索引，不能重用旧排名 |
| chunk 正文 | `VettingCorpus.java:221` 是原 block text 以换行连接；fileKey/role/anchor 在 metadata 中 | Java 生产 content 没有 CLI 的 key/clause 前缀；两种 corpus 不得假装完全相同 |
| CLI pilot 分块 | `eval.py:529–530` 默认 size=1400/overlap=200；Python按 code point 长度处理，pilot 有 key/clause content 前缀 | CLI smoke 不是完整 Java parser/corpus 的精度证明；真实参数比较优先冻结生产 snapshot |
| Embedding 模型 | `samples.json:3` BAAI/bge-m3，revision `5617a9f61b028005a4858fdac845db406aefb181` | 固定 tokenizer/config/revision；本地模型 config 支持 8192，但服务当前只输入 512 |
| Embedding 维度 / pooling | 本地 config hidden size 1024，CLS pooling；L2 normalize=true；Qdrant COSINE | 当前是 dense embedding 使用，不是 BGE-M3 多向量或 sparse 输出实验 |
| Embedding token 上限 | **512**，`server.py:31–53,170`；doc 与 query 共用该模型的 max_seq_length | 被截断的文本仍作为完整 payload 返回；payload/quote 可定位不证明模型读过引文所在 token |
| Embedding batch | **4**，`server.py:184–186,326,362`；Qdrant写入 group=128 | group128是处理/写入批次，不是 GPU inference batch128 |
| Device / dtype | `server.py:117–135` env auto/cpu/cuda；auto可用时CUDA；CUDA float16、CPU float32；torch CPU线程4 | CUDA FP16是本机实际链路基线；改device/dtype需新签名和对照，不能静默复用cache |
| Reranker 模型 | `samples.json:4` BAAI/bge-reranker-v2-m3，revision `953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e` | 固定 CrossEncoder/tokenizer/activation 实际配置；分数不是缺陷概率 |
| Reranker token 上限 | **512**，`server.py:180` 的 CrossEncoder max_length | 是 query+candidate+special tokens 的**组合**限制，不是候选单独512；库 `CrossEncoder.py:710` 使用 truncation=true |
| Reranker batch / dtype | batch=4；CUDA float16 / CPU float32 | `server.py:180,189–191`；不应因改 rerank batch 而重复 embedding/index |
| 截断方向 | 本地 tokenizer config 未覆盖 truncation_side；transformers 4.57.6 默认 right | pair默认截断还受 tokenizer双序列策略控制，需按实际 query/candidate记录各自保留 token，不只计算 chunk token |
| 模型缓存 | HF_HOME默认工作区 `tmp/vetting_models`，两模型 pinned snapshot；`CONSENSE_MODEL_OFFLINE` 默认1 | HF snapshot local-only不自动保证RapidOCR或Ollama全流程断网；离线验收应单独实测 |
| 服务并发 | 一个 Uvicorn worker；共享 RLock；模型lazy load且常驻；Qdrant local持久化 | CLI将embedding/rerank分阶段加载/释放；CLI与常驻服务的显存不能直接同比 |

**签名修复与剩余缺口：** 初始HTTP签名缺device/dtype/batch，CLI stale检查也不完整；本轮source-only v2已补有效embedding device/dtype/token/batch、revision/normalized/corpus并完整比较旧签名。依赖、tokenizer配置、pooling/preprocess完整身份仍未自动全部展开，继续冻结到实验runtime manifest。batch不一定改变语义身份，但当前签名也纳入它来防止实验误复用。Reranker变动不要求重新生成同一embedding，其独立runtime写入health/CLI retrieval report。

### 2.3 混合检索、上下文与 LLM

| 参数 | 当前应用值 / 依赖默认 | 当前边界 |
| --- | --- | --- |
| Dense 与 BM25 candidates | **各最多50**，RRF合并后再次最多50；`server.py:93–99,364–377` | Java `ConsenseProperties.java:106` candidateLimit=50；不等于送50段给 LLM |
| Rerank 输出 top-k | **10**，`ConsenseProperties.java:107` / request.limit | 每role分别检索；角色过滤在 Qdrant 与 Java返回ID核对两侧生效 |
| RRF | 常数 **60**，等权 `1/(60+rank)`；`server.py:374` | 不是 rerank threshold；同chunk可能获得两路贡献 |
| BM25 | rank-bm25 0.2.2：**k1=1.5、b=.75、epsilon=.25** | `BM25Okapi(terms)` 未显式覆盖；值来自已安装 `rank_bm25.py:79` 库默认，不能称自调配置 |
| BM25 tokenizer | `[a-z0-9]+(?:[./()-][a-z0-9]+)*`，casefold；score>0才入候选，`server.py:113,369–370` | 对英文/条款编号有用；未配中文分词。检索角色与目录噪声分开统计 |
| 检索超时 | query=180,000 ms；首次index=1,800,000 ms，`application-vetting-local.yml:32–33` | HTTP Java默认无应用重试；超时/词项fallback必须记录，不可混称完整hybrid已运行 |
| 普通主题 | semanticTopics=16；每题按tender/standard/project_fact/package_manifest各检索，`VettingSemanticReview.java:66,94–106` | 同名entity或top-k命中不证明对象、阶段、scope及例外完整 |
| 普通上下文 | **10,000 Java UTF-16 units**，`application-vetting-local.yml:29` | 字符预算≠model tokens；trace记录selected/dropped/partial/unresolved，限制必须显示 |
| 项目资料比较 | **最多16组**，每组正文预算 **12,000 UTF-16 units**，`application-vetting-local.yml:30–31` | 新 `buildFactComparisons` 已接入源码，actual planned=ordinary count+实际admitted packs，不固定19；尚不等于旧126部署已做这些比较 |
| 来源角色 | tender义务；standard依据；project_fact事实；package_manifest目录 | 事实/模板不能自动成为合同义务；basis gate只限制比较资格，不证明项目采用版本、table字段含义或模型结论正确 |
| 本机默认 LLM | local profile **qwen2.5:3b**，`application-vetting-local.yml:13` | 最近冻结recipe：GGUF 3.1B Q4_K_M，digest `357c53fb659c5076de1d65ccb0b397446227b71a42be9d1603d46168015c9e4b`；tag不是永久身份 |
| LLM temperature / context | **.2 / 8192 tokens**；`OllamaLlmClient.java:55–56` | 普通与project prompt含system/user/JSON/schema/metadata开销，不能把字符budget当剩余input budget |
| Structured output预算 | **num_predict=2048**、stream=false、JSON schema；`OllamaLlmClient.java:47–62` | done_reason=length显式失败；stop/schema合法不证明无遗漏。上限改变可改变截断或重复风险 |
| LLM timeout / retry | **180,000 ms / 0重试**，local profile lines16–17 | global application.yml、Java字段默认有其它值；不可用全局max-retry1/2描述local实际值 |
| 保存的主观风险数量上限 | maxRiskFindings=10，`VettingService.java:150–153` | 合并候选后按严重程度排序限制risk类并记录跳过数量；不是模型生成上限，也不限制全部reference/conflict类 |
| think / keep_alive | local structuredThinking=null，生产请求两字段均未显式发送 | 不把遗漏当false或0；Ollama继承参数、版本、完整modelfile/recipe需要另外冻结 |
| 未显式覆盖的采样项 | top_k、top_p、min_p、repeat_penalty、presence_penalty、seed、num_thread、num_gpu等 | 本轮不猜服务默认或模型继承值。最近3B/7B manifest 的 inheritedParameters=null；未拿到值时日志必须写unknown/inherited，不假定同模型权重以外完全一致 |
| 两阶段 prototype | `fact_stages.py:211` extraction `.2/12288/3072`；line232 comparison `.2/12288/2048` | 独立实验，不是生产8192/2048参数。同时变system/schema/两阶段流程，不能把结果只归因ctx或输出长度 |

`consense.vector.chunkSize=800/chunkOverlap=120/topK=6/scoreThreshold=.35` 属于旧 advice/drafting向量适配器；当前 vetting使用上表 Java corpus+Python Qdrant，不能用这些旧值调本轮链路。`maxRiskFindings` 是risk类保存上限，不能描述为模型生成或全部finding数量上限。

## 3. 每次对比的输入与验收层次

先冻结输入，再开始调用。冻结对象包括原文件SHA、source角色和版本、parser/metadata版本、全部chunk/Part原文与offset、query、每role raw ranked IDs、实际submitted IDs、system/user/schema、最终native request bytes和模型/依赖身份。标准目录和项目邮件保留来源角色；赛方2d参考comments、2e问答只能用于独立evaluation，不能进入生产corpus、索引或prompt。

固定输入集分三层，扩范围必须另记新实验：

1. **小样本输入保真层：** `samples.json` 中 FT物理2/7、AA物理1/2、APL物理3五页，加GCC物理2真正空白页、FT物理9删除线、APB物理21短图纸代码作为视觉负例。FT/AA/APL只读原扫描，不按文件名默认全部OCR准确。
2. **整包源检索层：** `full_case_sources.json` 的44原件、四角色；重放使用某一冻结生产corpus及source-only queries/spans。原v1的13,270chunks/138段>512/max861 token记录只属于当轮；后v2实际13,293chunks必须重新做token统计，不能照搬138。标注需涵盖主体、trigger、time/minimum、exception、xref、table绑定，并加入目录/标准漂移负例。
3. **语义诊断层：** 复用已冻结source-first-five包及项目资料SCT6/7/8 inputs，题目编号仅是评价定位，不写入通用生产规则。独立源审计给出能支持的最小命题与unknown；没有依据的“应有gold issue”不能强迫模型报冲突。

| 层次 | 记录的指标 | PASS 能说明什么 |
| --- | --- | --- |
| 执行 / 资源 | 返回状态、exception/OOM、cold/warm、cache、elapsed、RSS、GPU sampled peak、placement、并发 | 只说明该配置在该输入上可执行；未采到OOM/瞬时peak不等于最坏情况可用 |
| 解析 / OCR | 页路由混淆、确认blank、failed页、CER/WER及关键数字/条款exact率、bbox落页、table/del视觉判断 | 文本质量和源有效性；needle命中不能替代整页文字与版面复核 |
| Embedding / 检索 / rerank | doc/query/pair token保留率、span recall@candidate/k、both-side+必要限定覆盖、MRR/nDCG有相应人工标签时才计算；角色/目录误命中 | 只说明证据检索能力；高相关分数不等于冲突判断正确 |
| 上下文提交 | 完整源对象绑定、UTF-16 union、selected/omitted/partial/unknown IDs、角色占比、xref/parent闭合 | known内容是否送入；unknown不能升级为complete |
| Structured / 引文 | schema/stop、record数量及cap、quote side exact/source/anchor、frame/subject/predicate/value/condition绑定、gate rejection reason | 字面与资格门禁；引用可以字面真但关系错、依据不足 |
| 源语义 | 逐record主语/阶段/最低或精确/范围/例外/删除父子关系；false absence、unsupported issue、object association错误、bounded observation、unassessed | 独立人工源判断。precision/recall只有在完整范围和明确分母人工标注后才计算 |
| 人审 / 导出 | reviewRemarks/actionTaken原文、null/false/true决策、status独立、保存时间、JSON/Word/PDF一致及全页视觉 | 软件流程验收，独立于LLM审查质量 |

所有结果分别保存 `technicalStatus`、`sourceBindingStatus`、`semanticStatus`、`coverageStatus`。空数组标记为“无输出，相关命题未评估”，不能自动填semantic PASS；insufficient_context也须核对缺口确实存在，不能允许否认已经给出的内容。

## 4. 单因素实验顺序（全部 planned，除另有真实日志）

先小输入、后整包；每轮固定上一层已确认的输出，避免同时重解析、换模型、改prompt和改ranking。未注明已执行者仍为实验设计，不是当前推荐生产值；4.1和4.2只记录已完成的小样实验。

| 顺序 | 单独改变的因素 | 固定项 / 需要的执行条件 | 优先比较指标 |
| --- | --- | --- | --- |
| P0 | 无：冻结基线和参数展开 | 当前版本、native request、机器空闲、cold/warm分别留档；补运行身份缺口 | 字节/对象/offset一致；日志是否足够重放 |
| P1 | OCR DPI：200→250→300；已执行固定五页小样（4.2） | 同一原页、同一ONNX/config/threads；实际render尺寸改变，但三arm内部det输入都为1408×1984 | 本次只比较关键数字/限定小样、框/空名单视觉及latency/RSS；未准备完整CER或表格语义准确率 |
| P2 | OCR最终text_score：.5→.4或.6 | 固定DPI/检测阈值；低分行及bbox保留sidecar供人工检查 | 漏字/噪声、原条款数字保真；不得因filter改变自动裁掉有效合同文字 |
| P3 | OCR det box_thresh：.5→.4或.6 | 其它det参数不动；thresh/.3、unclip1.6另起实验，不同时改变 | 检测漏框/合框、邻列串接、CER和运行时间 |
| P4 | OCR CPU intra线程：4→2或6 | inter始终1、相同冷/热条件；方向cls开关、rec batch再各单独实验 | 延迟、CPU/RSS、全机争用；文字一致性 |
| P5 | PDF OCR路由textLayerMinChars：40→20或80 | 复用原页标签与同一OCR，不换DPI | native/OCR/blank路由precision/recall、被跳过扫描页、成本；实现生效后才测page cap |
| P6 | chunk size或overlap（二者分别测） | 固定parser/原blocks/metadata；例如size1400→1000，overlap保持200；改变derived stride须日志显式写出 | token截断、语义限定分离、xref/页owner保持；新chunkIDs与新索引，禁复用旧cache |
| P7 | embedding batch：4→2 | 先保持maxTokens512、相同snapshot/FP16、完整输入及query；这是1024/batch2方案的对照起点 | vectors差异、冷index时间、GPU/RSS、cache身份 |
| P8 | embedding maxTokens：512→768或1024 | **固定batch2**，rerank仍512/batch4，独立新索引；小probe先行，显存/质量尚待测 | token保留、span recall、index时间与OOM；与512/batch2比较，不能直接声称512/batch4→1024/batch2是单因素 |
| P9 | rerank batch：4→2；随后pair max512→768/1024 | 先固定同一query+candidate原始集合，embedding/index不变；每个因素独立 | pair各侧token保留、both-side/qualifier recall、顺序变化与latency/GPU |
| P10 | candidate50→30或80 | top-k10、embedding/rerank/prompt固定；分别保留dense、lexical、fusion中间排名 | candidate recall、TOC噪声、rerank成本；只增加候选不能证明范围更完整 |
| P11 | top-k10→5或15；随后RRF60→30/90 | 每项单独；RRF可先离线复用相同两路raw ranks，再验证真实运行 | 必要两侧/限定提交率、budget drops、role占比、latency |
| P12 | BM25 k1、b、epsilon或tokenizer | 当前库默认先展开；每项单独，中文tokenizer是另一策略实验 | clause/英文/中文分层检索，词项噪声、relevant-span recall |
| P13 | context字符预算10000或project12000 | 同一冻结rank/源对象/selector，其它参数不动；普通与项目包分别测 | full/partial/unknown、xref补齐、模板开销、最终token保留；字段正确性仍独立审核 |
| P14 | LLM temperature .2→0 | 相同model digest、prompt/schema/chunks、ctx8192/output2048/recipe；如可固定seed则写明，否则标nondeterministic | 同一错误是否稳定复现、source semantic audit；重复小调用估计波动，不挑最好一次 |
| P15 | LLM context8192→12288 | output仍2048、其它字段完全固定；先小包，不同时加入两阶段prompt | native prompt counters及完整输入保留可证明程度、数字/对象/限定错误、VRAM；尚无相同输入/system/output固定的本机单因素验收 |
| P16 | output2048→3072 | ctx固定为已测值，prompt/schema不变 | length/repetition、遗漏/cap、语义质量；更多输出不自动提升召回 |
| P17 | 模型/量化；system；原生table cells；两阶段extract→compare | **分别**冻结实验，模型试验要展开继承recipe；如必须多项变更，列出all deltas并称recipe/流程比较 | 逐record同源语义+引用+覆盖+资源；不把历史7B错误候选当进步 |
| P18 | 完整新44-source应用run与离线验收 | 小probe有源支持且配置被明确选择后；新project/run/snapshot；分别冷index和热query；模型/OCR/build/render不并发 | 所有阶段账本、漏检/误报/unknown、完整报告/人审流程；H800再按同协议复验 |

**执行工具边界：** legacy `eval.py --max-tokens`仍会同时作用于未独立覆盖的embedding和reranker；P8/P9应明确传入新的 `--embed-max-tokens` / `--rerank-max-tokens`，batch也独立固定。最初离线测试阶段及单独环境probe未验证新参数；随后4.1执行了rerank512→1024、batch4固定的pilot。embedding1024/batch2和完整生产语料效果仍未验证。生产Java DPI与chunk阈值仍未提供运行时调参接口；修改需另冻结源码/编译产物。调参时保持source文字与offset不变，截断只发生在模型输入视图，不能为通过引文门禁改原文。

### 4.1 已实际执行：reranker 512→1024，batch4固定

2026-10-02，在`tmp/rerank_token_experiments/rerank512-1024-20261002T050558263488Z-f914dc11`完成预注册的小规模单因素实验。复用原`tmp/vetting_eval/chunks.jsonl`的2,739个pilot chunks和`samples.json`的8 queries/11 contains-needle标注，冻结原bytes和源码，未重新解析/OCR，也未建全44-source索引。旧V1向量和metadata没有手补签名或复用；fresh目录实际建一次V2 index。模型revision固定，embedding512/batch4、CUDA FP16、rerank batch4、candidate50/top10、RRF60及BM25默认均不变，仅reranker实际`tokenizer.model_max_length`为512/1024。

源码新增schemaVersion2检索报告：记录全部50候选的顺序、query/content SHA、query-vector SHA及全排序分数。诊断使用已加载tokenizer，按pinned ST5.2 `predict`相同batch调用`padding=True,truncation=True,return_tensors='pt'`；生效上限读取`tokenizer.model_max_length`，保留batch tensor指纹与每pair实际保留token指纹。诊断只做CPU tokenization，无额外model forward，耗时与reranker评分分开。

| 指标 | 512 baseline | 1024 candidate | 可支持的判断 |
| --- | ---: | ---: | --- |
| 固定输入绑定 | 两arm query vectors、全部400个ordered pairs/content/candidate IDs、embedding/index身份逐项相同 | 相同 | 本轮对照绑定通过，不证明合同语义 |
| 实际截断pair | 2/400 | 0/400 | 参数确实触发，2个保留token SHA改变；最大原pair为736tokens |
| 标注片段top10命中 | 11/11，8/8查询 | 11/11，8/8查询 | 本组needle召回未改善；不是全面检索或语义准确率 |
| 评分耗时（不含诊断） | 5.616462s | 5.898354s | 单次观测，约+5.0%；不作统计性能归因 |
| CPU诊断耗时 | 0.473356s | 0.198199s | 分开记录，不混入评分时间 |
| 整阶段wall | 19.998017s | 18.253100s | 含加载及I/O；单次冷热/缓存差异，不能称1024更快 |
| 全机采样GPU peak | 2529/8188MiB，20 samples | 2579/8188MiB，18 samples | 非模型独占/绝对峰值；无采样错误及OOM |

新索引阶段wall62.437361s、62 samples、GPU sampled peak2563MiB。两arm实际模型device为cuda:0、dtype为torch.float16。每个CLI是新进程，HF文件缓存冷热未知；采样时task-local Ollama及retrieval服务缺席，由进程和TCP LISTEN表确认。PS response是unknown，不能记成实际`[]`；这些显存数不能与历史常驻服务条件直接同比。本轮没有LLM、应用review、上传、旧应用数据库操作或H800运行。

两条真正改变的pair均来自PRE.B8的中英Daily Record Summary/载运表格，在BSI查询中分别由rank39→37、20→19，均未进入top10，也不匹配本查询两条BSI定义needle。其它7 queries的全排序不变。故本轮不能声称补齐了BSI定义/限定或改善合同审查；继续保留512基线，1024作为已验证小规模可执行候选，`qualityAccepted=null/semanticAcceptance=not_evaluated`。完整相关性、例外和冲突gold尚缺，不能造precision/recall。

证明：`comparison_sidecar.json` SHA256 `ea720ec70da77c8b8959bc7e412f3657ea85fa5f5815860bccd217477c2b7785`；独立`source_activation_followup.json` SHA256 `3a6621f3a976a806969256ccdc60823b796c7494a8d3eb4ecfc46f8dbb9828d8`。两轮报告位于各自`out/experiment_runs/<id>/after/retrieval_report.json`，原报告未覆盖。baseline=`pilot-evaluate-20261002T050711414202Z-6e06971b`，candidate=`pilot-evaluate-20261002T050732385616Z-b9288bfb`；candidate日志显式绑定baseline ID。

后续纯CPU读取pinned `tokenizer.json`，重建两pair的实际512/1024保留token IDs并逐字SHA匹配真实报告，再以offset定位被截尾部并读原DOCX XML对应段/row。候选0-based18仅丢失空白表格分隔；候选27丢失Half/One quarter的载量、Vehicle registration、CHIT/DDF字段及`APPENDIX PRE.B8/III`。这些局部尾文不是BSI/Site Staff定义或其权限条件。fresh `source_context_truncation_audit.json` SHA256 `ab214a0a9b04dc40922ba8077ca8f7668749305eb1c908e62dc192067a73a829`另存，原sidecar未改；结论只覆盖这两组，不是400pair语义gold。统一日志已追加`independent_audit`关联三份sidecar，调用观测不变，质量仍unknown。

此前两次driver preflight分别因11434连接拒绝和8868主动探测超时失败，均是stage0、零模型/index/评价调用，已另登记统一registry为`driver_preflight`。旧失败目录、错误栈和固定输入hash保留；未自动retry/OOM调参。本轮环境改为明确服务缺席后，另新group预注册并手动执行。

### 4.2 已实际执行：OCR DPI200→250→300，内部resize不变

2026-10-02在`tmp/ocr_dpi_experiments/dpi200-250-300-20261002T053456227500Z-b7c70e67`完成预注册的三个串行CPU OCR arm，05:38:38–05:39:40Z，共15个fresh page calls。固定原FT物理页2/7、AA物理页1/2、APL物理页3；三份原PDF/原样samples、三ONNX、RapidOCR3.9.2源码/config和本工具冻结，三个out各有独立空OCR cache并预放已核实的同三本地权重。未重新下载模型，child socket网络尝试为0。实际三阶段均为`CPUExecutionProvider`、intra4/inter1；每arm前只GET nativePS，实际返回`models=[]`，没有生成、unload、索引、应用上传或DB改动。原`tmp/vetting_eval/ocr`和旧report hash均未变。

| 观测 | DPI200 | DPI250 | DPI300 |
| --- | ---: | ---: | ---: |
| 实际PDF渲染W×H | 1654×2339 | 2067×2924 | 2481×3508 |
| Global预处理后W×H | 1408×1984 | 1408×1984 | 1408×1984 |
| det session实际tensor | [1,3,1984,1408] | [1,3,1984,1408] | [1,3,1984,1408] |
| 原22个anchor smoke | 21/22 | 21/22 | 21/22 |
| 14视觉区域的31个literal小样 | 30/31 | 30/31 | 30/31 |
| 五页OCR时长合计 | 13.303055s | 13.431753s | 14.824862s |
| engine load | 2.277107s | 2.254597s | 2.147377s |
| 整CLI wall | 18.130961s | 18.773093s | 21.727142s |
| 全机GPU sampled peak/8188MiB | 1589MiB，18 samples | 1633MiB，19 samples | 1575MiB，22 samples |

**提高DPI改变了渲染像素和插值，没有提高本轮det网络实际输入分辨率。** 当前Global`max_side_len=2000`及32倍数取整使全部15页进入同一1408×1984尺寸；不表示像素tensor相同，cls/rec crop数量和宽度仍会改变。observer只包原preprocess和session调用，参数/数组/原return/异常均转发；3项fake对象离线透明性test通过，实际shape/配置/provider/线程和全部batch另存。Python metadata开销约0.0021–0.0022s/arm，包含在原OCR timer中，不作扣除或零开销宣称。单次latency含加载/OS缓存/后台进程影响；全机显存不是CPU OCR模型显存，也不能与服务缺席的rerank实验直接同比。

五份原图及三arm全部15份box overlay已实际查看，14个区域标注仅用于独立evaluation，不进模型。三arm都保留FT2的180天投标有效期及`from and including ... Closing Date fixed or extended`、AA1工期延长与付款时间/方式的合同条件、FT7脚注4/5/6，以及AA2两层OR。裸数字/词出现不证明关系理解。APL三名单的姓名槽位全空，罗马编号却被OCR合成`目目`/`日目日`/`313`/`三三三`等或漏检（250的Lift(v)/(vi)无box），不得当人数或姓名。实际页脚三arm都是`APL /2`；冻结`APL/2`needle因内部空格失败，原21/22保留，不事后修改匹配规则。未准备整页字符转写/CER，也没有自动表格关系/删除线/合同语义验收。

保留DPI200基线，本组没有证成250/300提高所标注的数字/限定；`qualityAccepted=null/semanticAcceptance=not_evaluated`。下一步若调整`max_side_len`须另预注册单因素并留原DPI不变，不能把DPI结果当该参数已测试。证明：主pre-registration SHA256 `294dbe2b3393bb9cf43f00d5f0aac1ddb8ccea8bb0813a1cdf1d1808358f2ff9`；实际`comparison_sidecar.json` SHA256 `4b2cf5a0f5e20406eb8b366afe51674cdb00b031bae451aa7288e6050caca909`，绑定全部sources/config/weights/原输出/资源/shape；三个实际CLI事件另追加独立源审，不改原观测。

实际ID分别为`pilot-ocr-20261002T053838557779Z-66251ec6`、`pilot-ocr-20261002T053857774937Z-2ff81410`、`pilot-ocr-20261002T053917673682Z-6f7e0dfa`，250/300均关联200的baseline ID。各arm的JSON/PNG/overlay和不可变`experiment_runs/<id>/after`报告均保留；三sampler已停止，无采样错误或OOM。此项不是Java整包parser/新44-source应用run或H800测试。

### 4.3 OCR 单因素入口与完整缓存身份：已实现，已完成一轮有限对照

在4.2原冻结结果之后，只修改Python工具源码，新增`--ocr-max-side-len`、`--ocr-text-score`、`--ocr-box-thresh`、`--ocr-intra-threads`、`--ocr-inter-threads`。默认仍为2000、0.5、0.5、4、1；`--dpi`仍为200。其余det thresh=.3、limit736/min、cls/rec batch6和CPU provider配置不变。max-side必须为整数≥32，两个threshold须为有限[0,1]数，两个thread须在1…实际CPU逻辑数以内，否则引擎初始化前拒绝，避免ORT静默忽略越界thread。入口实现阶段仅做离线验证，没有真实调用或把参数接进Java/HTTP endpoint；随后完成的独立max-side两arm实际对照见4.4，不代表应用整包升级。

`ocr_runtime.py:119`开始定义cache recipe，`eval.py:112`补入raster DPI/scale及工具身份，`eval.py:120/271`的OCR生成与PDF解析消费都检查签名。新JSON带`ocrCacheSchemaVersion=2/ocrCacheRecipe/ocrCacheSignature`：绑定完整已安装RapidOCR Python/config资源原bytes、实际选定ONNX/显式dictionary权重hash、有效参数、raster DPI/scale、Python/OS/CPU及工具/runtime源码hash、选定VC runtime DLL hash，以及依赖版本/METADATA/RECORD。依赖wheel的manifest身份不等于所有外部系统DLL已逐字hash；该边界在recipe中明确保留。output/model-root目录本身不影响身份；原PDF hash、key和物理页另逐项校验。初始化若修复/替换权重会在推理前拒绝，不能把初始化前的旧hash签到新输出上。

相同recipe的OCR cache可复用且不创建engine/渲染；digital PDF没有OCR缓存时不要求OCR模型。旧unsigned cache、参数/config/model/thread/package/DPI或source/page改变均拒绝，并明确要求`ocr --refresh`后用同参数parse，禁止手工补签名。`--offline`不仅检查存在，还在factory前比对默认权重official registry SHA，并显式传入三个local model_path绕过RapidOCR默认下载/repair分支；缺失/损坏默认权重立即拒绝。显式custom模型不强套官方SHA，offline Rec另需local Rec.rec_keys_path，或精确hash为本机已观察embedded-character metadata的PP-OCRv6 small权重；preflight不创建session检查其它custom内嵌字典。正常非offline保留原首次加载下载/custom路径，但在推理前记录完整身份。pre-run日志只hash权重，不逐实验复制模型。4.2三arm的旧cache和raw结果保持原样，不能冒充新签名版本的cache命中。

非法CLI参数由`eval.py:798`的独立preflight路径记录started/failed、raw argv/error/exit code/工具identity和0调用，resource未知；拒绝的raw out/config不读写，log-root仅接受workspace/tmp内合法目录，否则回到trusted default，`--help`正常exit0不记失败。这是driver参数拒绝，不是有效OCR/RAG/LLM实验已执行。

最新完整离线证明：`tmp/local_model_parameter_tests/20261002T062150260246Z/test_manifest.json`，SHA256 `eb97cbf23ab2750b26fae320046a5f65f06718ca9abbe56e6ddc1839f1d2670a`，29 tests/0failure/error/skip、exit0及`py_compile`通过，0真实OCR/model/index/API/sampler、未加载heavy inference模块。测试验证独立参数转发、config/model/thread/package单独变化失效、same-recipe复用、unsigned拒绝、parse门禁、初始化修复权重提前停止，以及offline坏权重factory前拒绝、显式local/custom路径和非法参数日志安全边界。首轮24test有1项mock可变字典共享导致失败，已单独冻结并登记`offline-ocr-cache-initial-20261002T060517549896Z-eb91c029`，各次24/26证明独立保留；不是OCR质量失败/改善计数。独立review保存旧26-source坏权重/非法参数两个反例`tmp/independent_ocr_cache_reviews/20261002T061435220956Z/review_counterexamples.json`，SHA256 `192a3cbd5d38f89593ee189ddf1d5148fc1134898d946c8e83634cce4cc497ce`；29新mock回归关闭这些软件路径，没有实际OCR或联网执行。

另只读installed metadata/weight audit：`tmp/local_model_parameter_tests/installed-ocr-recipe-20261002T062249279271Z/installed_recipe_audit.json`，SHA256 `4018b06acf4d8494fbd9c61b26d40d56e1840766b377696a11e02267b5eba4b4`；PP-OCRv6的配置`ch`实际通过`multi_PP-OCRv6_det_small/rec_small`路由，Cls为`ch_ppocr_mobile_v2.0_cls_mobile`，三本地hash均与official registry及2.1/4.2已核实值一致。此audit没有创建session，不能新称实际provider/thread已测。后续用fresh group、同DPI200/同5页/同3权重与14独立视觉labels，串行仅改max-side2000→3000，仍须观察actual resize/session尺寸、数字/限定/空表槽位及资源，不从API成功或字词出现推导关系正确。

首个零调用准备冻结在`tmp/ocr_max_side_experiments/maxside2000-3000-dpi200-20261002T064137935506Z-e5e82c0d`。两fresh out各复制同三official ONNX，沿用同三原PDF/五物理页、22原needle和14原视觉标注bytes；使用同29版工具和此前审核的observer，recipe逐字段仅`/parameters/Global.max_side_len`由2000改3000。pre-registration SHA256 `01b6fec54091d9891fcc123359c852659d068f3a3b734dc03692f1b60d10bf1a`，driver pre-registration SHA256 `9eb224ce0f17e70b12bbac4256499d69ceec534ac7d2b7f25ca9e0f81a9901a6`。6项observer fake透明性/固定输入/recipe唯一差异/命令显式执行门禁测试及AST检查通过，proof SHA256 `15dfba621575d0879f623d756252fa529581028adcc129e14bf13932d9ce7af3`。准备阶段没有OCR/native/API/index，未加载heavy inference模块。

随后一次已授权driver启动在stage0失败：旧logger错误地拒绝`planned → started`，exit1发生在source核对、native PS、child创建和sampler前。原`execution_manifest.json` SHA256 `12d000e87fe05ee0faca0308f3ed1499d57ecd2c00d692b021e4989a8cdf51da`及工具捕获的合并console输出保留；该输出不冒充独立OS stdout/stderr原bytes。原参数和input fingerprints对应failed registry event SHA256 `b4b2718709b1354607de01cea5d818b6991373cb866438386997a1dba76e8d85`。真实child/OCR/native/API/index attempts为0，sampler没有启动，旧组不重跑。这是软件preflight失败，不能计为OCR质量失败。

新版零调用组`tmp/ocr_max_side_experiments/maxside2000-3000-dpi200-v2-20261002T070656398717Z-59c14349`已冻结：preregistration SHA256 `242086feb062adbdccc3dab29a1e35080674c45aad0a154f43455e09c367deca`，driver SHA256 `7718d6dc3633ef0807a7a32538c7e55c13d3cf1bd6c5072ee001c60ba7f2d445`。仍用同29版eval/OCR源码和上述固定原件/权重/标注，两arm recipe唯一差异仍为max-side2000→3000；新logger `d0fff2f4…`的14项真实registry测试通过。新observer在原full engine/preprocess调用前写attempt，成功才写completion，异常保存后原样透传；preprocess完成不冒充整页OCR完成。11项轻量准备测试及AST检查通过，proof SHA256 `a7607ceebb0528597afe75edcaaaba44a190b5d5ee8ee853bf8e9ac22778d9a9`，包含实际临时registry planned→started/旧事件bytes不变、假对象成功及失败透明性验证。新registry只登记planned (`d1b8d53b…`)，actual OCR/native/HTTP/index/sampler仍0，actual det尺寸/nativePS未知。logger/observer跨组变更使新版不能与旧DPI组做单因素归因；单因素结论只能比较这两个新版arm。待其它模型采样停止、收到执行授权后再串行各一次，不从参数名或offline测试推断效果。

### 4.4 OCR max-side2000 /3000、固定DPI200：真实执行与来源限定比较

4.3新版预注册组随后获授权串行执行，driver session20457实际exit0，于2026-10-02 07:18:19.369524Z完成，两个sampler均停止。两个arm分别只有一次尝试、各5页full OCR与preprocess attempted/completed均为5/5，无重试、错误或child network请求；每arm开始前native PS实际返回`models=[]`，没有generation/unload。实际三session均`CPUExecutionProvider`、intra4/inter1，同原PDF/渲染DPI200/三官方权重/冻结工具与observer/logger。配置除已核实本地路径外仅Global.max_side_len变化，旧DPI cache、report和旧stage0失败组均保留。实际ID为`pilot-ocr-20261002T071737004087Z-a2248354`和`pilot-ocr-20261002T071757489856Z-3c500ced`，后者关联前者为baseline。

| max_side_len | 同render W×H | 实际preprocess W×H | 实际det tensor NCHW | 原needle smoke | 14区域31字面unit | 五页OCR合计s | CLI wall s |
| --- | --- | --- | --- | --- | --- | ---: | ---: |
| 2000 | 1654×2339 | 1408×1984 | [1,3,1984,1408] | 21/22 | 30/31 | 13.668669 | 18.993472 |
| 3000 | 1654×2339 | 1654×2339 | [1,3,2336,1664] | 21/22 | 29/31 | 15.820364 | 20.609542 |

参数这次实际触发det输入尺寸变化：3000保留原raster进入preprocess，det再按库32倍数处理；不是仅参数名变化。每arm19/21个约1秒样本，全机GPU sampled peak1503/1506MiB、全机RAM used peak19,103,236,096/19,308,421,120 bytes；engine load3.201597/2.080555s，观测bookkeeping合计约0.002050/0.002155s已包含在原时间中而未扣减。OCR实际为CPU，各进程RSS及全机显存含已有服务/桌面背景；单次时间和采样峰值不能当稳定性能或OCR专属GPU占用，也不能与旧组更改observer/logger后的时间做单因素归因。

独立实际查看5张原reference和10张新boxes overlay，对照原14视觉区域及原OCR JSON。3000的FT2将源文字`days from and including the Tender Closing Date`识别成`days frdom and including the Tenderer`，下一行才出现`Closing Date`；180及fixed or extended保留，但期限起点原句失真，原字面检查减少1项，不能修字后报通过。AA1延长/付款条件、FT7脚注条件和AA2 OR相关字面unit两arm均保留；这只证明所选文字存在，OCR并未输出选择逻辑/主体条件关系。APL Fire(ii)-(iv)在3000拆成正确独立数字，而Electrical/Lift(ii)-(iii)变为合并错字；2000则Electrical(iii)-(iv)及Fire(ii)-(iv)合并。槽位全空仍不代表已填分包商/实际人数，两arm均输出`APL /2`而原`APL/2`严格needle失败，维持21/22，不临时改评价规则。

最终来源限定sidecar为同组`comparison_sidecar_revision1.json`，SHA256 `573b7503a86313b89f56aa935cfd61df85ae4ed505600098c869f6c5e70fd98d`，全部12项机械身份/配置/shape/provider/计数/network/sampler核对通过；原始raw与CLI不可变报告均绑定。初`comparison_sidecar.json` SHA256 `24b72d157a008a688d82e06ddde4d1f0fa08f9bce19a4e185b0e8680fd95c5b7`已正确记29/31，但MD一句条件均保留过宽，保留初版及原审计事件，revision1另写精确更正并追加registry独立审计，不覆盖历史。结论是保持2000默认：较大det尺寸没有改善此固定字面指标，局部槽位有取舍且期限条件退化。`qualityAccepted=null/semanticAcceptance=not_evaluated`；未建立全页CER、表格关系准确率、合同发现precision/recall、44-source应用效果或H800效果，没有改默认、应用DB或索引。

### 4.5 Embedding512 /1024、batch4固定：仅准备，尚未执行

唯一待执行冻结组为`tmp/embedding_token_experiments/embedding512-1024-20261002T080828206680Z-c545b7f8`；preregistration SHA256 `ced9ef45b28cfe57ea2cc721e61a351d183859935ec010b3d8c96a040a1fc8e8`。旧`080535949274Z-6eb46562`准备组保留，不能执行：终审新增执行前核对实际installed ST/Transformer/CrossEncoder/BM25源码、Python exe及包版本，防止冻结副本相同而真正运行库已漂移。两新arm采用同一新driver/observer revision；没有修改生产参数、eval工具、原chunks/queries/gold、标准索引或旧实测报告。

| 项目 | 512基线 | 1024候选 | 当前证明范围 |
| --- | --- | --- | --- |
| embedding实际请求max_seq_length | 512 | 1024 | 静态parsed args、V2签名；actual tokenizer未知 |
| embedding batch/device/dtype | 4 / CUDA / FP16 | 同左 | 运行时需核对，未加载encoder |
| rerank tokens/batch | 512 /4 | 同左 | 当前固定请求 |
| candidates/topK/RRF | 50 /10 /60 | 同左 | 当前固定请求 |
| BM25 | k1=1.5、b=.75、epsilon=.25 | 同左 | rank-bm250.2.2库默认，未覆盖 |
| index/cache | 独立fresh out，重建一次V2 | 另一fresh out，重建一次V2 | 两out目前只有同bytes chunks，无vectors/Qdrant/cache |

固定原`tmp/vetting_eval/chunks.jsonl`2,739片段（SHA256 `9a906fc82b3c410f6090b1c93ff88b7ff1c931995cfb7e095edbe5c2eace0096`）及原8query/11needle `samples.json`（SHA256 `3fcc3644fca50ceeed86782db6cff3937cb066d9f741e6ec37748f37b106ef94`）。BGE-M3 revision`5617a9f61b028005a4858fdac845db406aefb181`与reranker revision`953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e`不变；完整本地HF snapshot文件逐个hash，只绑定不复制权重。HF_HOME固定工作区`tmp/vetting_models`，HF/Transformers offline，child socket连接阻断；driver未来只做localhost PS读取，不生成或卸载模型。原V1或历史V2 vectors/Qdrant均不复用或补签名。

准备核对现有18项证明对应的10个检索函数AST与当前源码完全相同，没有重跑未变部分；当前eval身份另与29项工具证明绑定，新logger与14项planned→started证明绑定。新observer/driver的12项独立fake/registry测试通过（0failure/error/skip、exit0，未加载heavy推理模块）。这些验证原tokenizer/encode参数、return对象与异常身份透明，真实临时registry planned→started及terminal保护、input/runtime漂移拒绝、V2跨arm/旧签名拒绝和显式执行门禁；不计为真实模型调用或质量改善。

12项新证明为同组`offline_validation/test_manifest.json`，SHA256 `fce02bdb571e047eddf4bf6658045603fbf4e21b34d9aee997cbe97f133834c0`；file-only终审`final_static_preflight_revision1.json` SHA256 `d0f6fda8f61e4c1f41e87db8dd1bc4f7f9467d5869a251b2eff897cc1f6dbd86`。独立预检保存于`tmp/independent_embedding_preflight_audits/embedding512-1024-20261002T081559058046Z/independent_embedding_preflight.json`，SHA256 `1f0a5bbfcdec71a07f5de34ad4e2bf510bb3384875e85fac0012ac00cc156821`，无机械blocker且没有新增tests/模型/API。CPU终审辅助脚本曾将registry返回descriptor误当event而`KeyError:'record'`，失败helper、partial proof、捕获console及0调用software失败事件保留在同组`post_prepare_cpu_audit_attempt1`（event SHA256 `420e402bf85af7319df978fc40c5600c9663c2a13a9b91ab7c32eeacff2af9aa`）。随后仅修读取descriptor.path、另写revision1终审；旧080535追加cancelled/superseded事件（SHA256 `120f098aa1eb059b6bf25e0ca8a1541778f05c96082cd939f25cc2dae9b1a55e`），旧原件未覆盖，未重跑任何模型。

Encoder观察器包裹ST5.2真实first-module tokenizer调用，记录原encode输入顺序hash、实际strip/lowercase后按encode排序的batch输入hash、原返回的input_ids/attention_mask等完整张量hash及padding/retained长度。embedding生效上限取调用中显式`max_length=self.max_seq_length`，同时记录`tokenizer.model_max_length`而不混用。额外不截断tokenizer诊断读取同实际normalized输入，得到真实untruncated token数量与截断变化；不按字符数估算。该CPU诊断包含在encode/CLI时间内且另记时间，不扣成纯模型耗时；若诊断失败，保留原结果并标unknown，driver停止且不自动重试。

未来执行顺序固定为index512→evaluate512→index1024→evaluate1024，每stage一次、0retry；实际运行前各stage须PS`models=[]`且8868/8870没有listener。准备阶段未查询PS，actual状态、tensor、显存和速度均unknown。增加embedding token上限可能改变query/document vectors、dense候选、RRF候选和400个rerank pair的具体内容；400仅为8×50的预期规模，不能强制candidate/pair/query-vector相同。比较须保留每query全部candidateIds、content/pair/token SHA、全部ranked scores、top10变化、11needle命中与未评估范围、index/query/rerank/诊断时间和各sampler资源。新11needle不是完整relevance或合同semantic gold，未知precision/recall、限定完整性和H800效果继续null。

## 5. 已有反例与历史比较边界

下列是已有真实日志和独立源审计记录，不是本轮新运行。详细路径/数字保留在 [运行与验证README](../tools/vetting_eval/README.md) 及其不可变证明中。新实验须保留这些反例，不能仅用正向needle重新计“全部通过”。

| 已有现象 | 对调优的约束 |
| --- | --- |
| FT物理9删除线被OCR识别为普通文字；APB物理21短图纸AL代码曾误作缩写定义 | OCR quote真不能证明源文字有效；不能全局禁OCR，也不能以提高DPI代替删除/图表解释 |
| T3包有SiteStaff职责却被模型称无职责；BSI代表authority定义又不在同一core | “已给内容被否认”和“真正需要的范围缺失”分开；不能按旧comments强制BSI conflict |
| T13出现45与60minimum，模型混淆first/revised/对象或声称无xref | 不把兼容最低时限直接当矛盾；minimum/精确值、trigger、xref分开审计 |
| T14 sole employment 与 full attendance、SO/SS例外和合同适用限定 | 不把不同scope/不同义务词直接视为冲突，缺少例外时unknown |
| 单chunk20条fact引文字面同源，但bf8a8条把后PM/QCM资格串给前AS/TD名单 | subject/predicate/value/condition必须属于同一显式命题；frame gate是必要条件，不能替代kind/关系审核 |
| 全44 immutable126实际16主题schema合法、0LLM issue，但19records有false absence/unsupported scope等 | 完成全调用≠质量验收；模型actual提交144unique chunks/23文件不等于审完44全文 |
| 项目资料三轮9 native calls均未源语义验收；4B旧模板2018→猜2022的字面candidate被新basis guard拒绝 | guard修复拒绝资格≠模型理解提升；原输出/旧gate结果不覆盖 |
| cell3B三calls5 insufficient均源语义失败；same-cell7B三issue均无源支持，SCT6返回[] | 7B把真实相同日期判冲突且gate曾通过；大模型/候选数量不能单独作为质量进步 |

当前7B三issue独立审计为34 item checks：3反驳、3对象unsupported、28未评估；SCT6的12项均未评估，不能计算全面准确率。参考证明：`tmp/independent_fact_response_audits/native-cell7b-20261002T034705308429Z/independent_native_cell7b_three_issue_source_semantic_audit.json`，SHA256 `850b1a7760be963a0d12f6584714b1bf7574c9e1ce1fc2c9e20792a9fa7d734e`。

已有3B→4B对照同时换model并加think:false，且有继承sampling recipe差异；cell3B→7B只改native model字段，但继承recipe未完整展开，均不称weights-only因果实验。原cold full-index与warm review、CLI独立加载与常驻服务、不同metadata corpus、旧system与generic/cell/two-stage prompt是不同条件。未控制的变更必须列入confounders，不能只对比总秒数/显存或issue数量。

## 6. 每次执行和比较日志

每轮使用新的 `experimentId`、目录和UTC/local时间，先写planned输入绑定，执行后补真实结果。原始request/response、native计数、模型show/tag/digest、完整参数及source/类/JAR/dependency哈希与resource sampler都保留。人工审核或自动门禁改变只能新增sidecar，不能改历史raw output或已保存run。

当前 `eval.py.main` 已在源码接入统一 `append_record`：五个stage自动started→finished/failed，支持 `--log-root` / `--baseline-id`，固定config/tool/package身份并把已知输入及报告原bytes保存到fresh `out/experiment_runs/<id>`。ResourceSampler对非probe阶段观测，probe资源unknown。原cache可复用，vector array只hash，Qdrant/HF不递归复制。最初16项离线测试使用stage/sampler替身与临时registry；后续真实环境probe已单独登记为 `pilot-probe-20261002T043440667386Z-94303902`。该probe未执行真实OCR、建索引、检索或LLM，不能计作新模型参数的质量验证。

自动日志版本16tests全通过（0failure/error/skip、exit0），`py_compile`通过；证明在 `tmp/local_model_parameter_tests/20261002T042903376420Z/test_manifest.json`，SHA256 `9a5d43bac6f492bc0ce8581affebe49c88b86b53ae709f9ac84fd0c90ff5830e`。随后增加ordered UTF-8 pair身份、有效tokenizer上限与ST5.2相同batch输入指纹测试，最新18tests/0failure/error/skip、exit0及`py_compile`通过，证明为`tmp/local_model_parameter_tests/20261002T045705258953Z/test_manifest.json`，SHA256 `35ac025123fa5734e9db1e441d10baf6701f1657e12b8c3a2b1ad693c63562ae`。之前失败、12/16项证明均保留；所有unit proofs是offline tooling，不计模型调用或准确率。后续真实index/两evaluate的started/finished、stdio、报告rawbytes及资源已分别自动登记，见4.1。

统一schemaVersion1必需字段如下；细分质量指标置于evaluation，参数/输入fingerprints在同一experimentId下固定，后续audit追加新event：

```json
{
  "schemaVersion": 1,
  "experimentId": "unique-id",
  "scope": "pilot_evaluate: Python pilot, not Java full44 or contractual acceptance",
  "status": "planned|started|completed|failed|cancelled",
  "baselineId": "immutable-baseline-id",
  "changedFactors": [{"name": "rerankMaxTokens", "from": 512, "to": 1024}],
  "parameters": {"explicit": {}, "libraryDefaults": {}, "inheritedOrUnknown": {}, "modelDigestsAndRevisions": {}},
  "inputFingerprints": {"fixedInputManifestSha256": "sha256", "runtimeManifestSha256": "sha256"},
  "measurements": {"actualCalls": null, "coldWarmAndCache": {}, "concurrentActivity": [], "wholeMachineSampledResources": null},
  "evaluation": {"technicalStatus": "not_run", "sourceBindingStatus": "not_evaluated", "semanticAcceptance": "not_evaluated", "qualityAccepted": null, "coverageStatus": "unknown"},
  "artifacts": [],
  "confounders": [],
  "decision": "retain_baseline|candidate|reject|inconclusive"
}
```

日志要同时列分子/分母及未评估项；无标签时不造accuracy。引用side定位与record证据资格分开。记录estimated token与真实native prompt/output counters的来源，保留done_reason、schema失败、timeout、fallback、OOM、缺失采样；带unknown值而非填0。延迟可分别比较coldload、index、queryembedding、BM25/dense/RRF、rerank、LLM、导出；只有重复次数足够且相同条件时才报告p50/p95。全GPU used/进程RSS/全机RAM与PyTorch allocator peak分别标注，采样peak不称绝对peak。

每轮结束给出三项具体结论：相对哪个基线改了什么；真实资源与源语义哪些改善/退化/未知；保留基线还是选择候选及下一轮唯一变化。技术成功但语义失败的配置可以保留为诊断，不进入“已验收”表。人工review状态和意见不写入模型prompt或RAG。

## 7. 本轮明确未闭合事项

- `maxOcrPages` 未生效；Java的200DPI及生产chunk参数尚无运行时可配置接口。CLI已独立提供OCR五参数/DPI/chunk入口及严格cache recipe，见4.3；不表示Java/HTTP同样有这些入口。其它OCR库默认值仍须在实际新session清单中冻结。
- HTTP/CLI v2已补embedding device/dtype/token/batch签名并拒绝旧签名；HTTP行为尚未实际验证，CLI独立新V2 index及两evaluate已真实完成。tokenizer/pooling/依赖完整recipe仍需manifest绑定，后续controlled restart选择新state目录，不能覆盖旧proof。
- 512截断的整包统计需按当前corpus及query+candidate pair重新计算，尤其必要限定是否在模型可见范围；旧138/861数字不能跨版本沿用。1024/batch2、ctx12288及更大模型整包质量均为planned/未验收。
- Ollama版本、完整inherited parameters、默认sampling/keepalive、实际input retention尚未完整绑定到每个旧实验。本轮未调用服务补查；历史未知值继续unknown。
- 标准目录噪声、跨source owner/xref、项目fact字段和request/reply关联仍需源优先审计。zero omitted/unresolved只针对已识别范围，不证明所有上位例外和跨引用完整。
- 软件回归、实际隔离API/UI以及报告排版各有独立证明版本；不能从155 tests或人工字段导出PASS推出LLM合同语义PASS。H800容量充足也不能自动解决这些质量问题。

后续按[当前goal的模型与流程对照原则](vetting-current-goal.md)并行推进两类工作：本机解析/OCR/embedding/rerank继续做单因素、逐轮留档的管线验证；比赛主模型先建立27–35B级及更大挑战者的plain-prompt基线，再在同一模型内比较最小runtime，并在同一流程内比较模型配方。未建立比赛大模型基线前，不以3B/4B/7B的失败判定复杂agent必要。实际GPU实验按资源空闲统一排程，当前4.5 embedding组仍prepare-only；源语义、资源和程序机械门禁分别验收。

## 8. Bonsai2 27B / NInfer 大上下文本机候选

用户后续指定试用这一路线，并要求上下文尽量大。现已实际构建24SM/sm89版本、通过三个GPU数值测试，并完成短输入、容量和原四任务plain pilot。完整原始证据、失败和比较见[讨论记录](vetting-discussion-and-goal-20261002.md)及[实验日志](vetting-experiment-log.md)。该低比特配方用于本机诊断，H800配置仍待实际验证。

| 参数 | 实际候选值 | 已验证边界 |
| --- | --- | --- |
| 模型 | Bonsai2 27B、v3 `bonsai2_27b_vl_mtp_q4q5.ninfer`；SHA256 `96f1f241753d8e6e01c3f742907523a5b78a2c6dd0eee1f81114a61d29b4d671` | 三进制/混合格式转换，不等同27B BF16 |
| 引擎 | NInfer public Windows fork e7d309e31260052b10e0500433661b03dcdc6e0e，加24SM及CUDA12.8兼容补丁 | 仅本机native实测，未证明H800编译/数值/性能 |
| 大窗口质量pilot | 初轮`--max-context 61440 --kv-capacity 61440`；当前日常候选`49152/49152` | 初轮输入10774/7512/7524/5596，完整保留旧四任务；后续60K启动失败表明显存预算波动，48K改作更多余量候选 |
| 最大一次成功配置 | max-context/kv-capacity64512 | 实际62386输入+65输出；不是完整边界或稳定容量证明 |
| KV | `rk2v4-e8` | 低比特KV，质量须另核对；单序列main KV全GPU驻留 |
| CUDA graph | 大窗口关闭 `--no-cuda-graph`；32K容量组开启 | 64K两者均启动失败；graph关闭释放12MiB预算，不提供CPU卸载 |
| Prefill | 128 | 原生正整数且128倍数，不能用64/32；最小workspace预算 |
| 并发/排队 | 1/1 | 本机仅串行，未测试并发容量 |
| Prefix reuse/anchors | `--no-prefix-reuse --auto-long-anchors 0` | 三容量组cache0/完整prefill，没有用缓存代替长输入 |
| Vision/MTP/speculation | 未启用 | OCR/RAG仍由独立组件处理，本次只测text |
| Plain pilot生成 | temperature0.2、max_tokens8192、no-thinking、每task1次/retry0 | native text-only format，system追加通用schema说明，JSON不受约束 |
| Plain pilot实际继承采样 | top_p0.8、top_k20、min_p0、presence_penalty1.5、frequency_penalty0 | 来自四条原生request_start.sampling，不由库通用默认推断；seed省略时随机，实际uint64逐任务记录 |
| 端口 | loopback18082 | 临时owned服务，结束后停止；不是应用模型默认更新 |

下一轮如比较KV精度、graph或thinking，必须同模型同源同prompt、使用新id和日志；当前32K→60K→63K同时改变长度与graph，耗时不能只归因graph。初轮技术选择60K是为相对63K留出更多余量，尚未做重复稳定性验证；后续根据实际启动失败改为48K候选。64K/128K的startup OOM与生成阶段语义错误分别报告，256K未执行；真实token预算检查超界就拒绝，不截断合同输入。确认本机源审增益后，再决定是否接入应用。

随后同60K控制组0生成启动失败，当时available_after_weights/runtime预算可用987,680,768B、runtime要求999,123,456B；可用量下降不能归因presence参数，因为尚未接受生成请求。改48K后实际合成输入47,415/输出65、3值正确/cache0，HTTP66.668s、整机GPU采样7697MiB，启动planned slack211,200,256B属于本次环境。该档只作余量更大候选，仍非重复稳定性认证。48K的presence1.5基线及presence0组必须两臂同context、same4effective seeds，不能将一次context变化后的回答归为纯presence收益。

2026-10-02 实际采样补充：plain pilot仅显式覆盖temperature，原生日志显示模型non-thinking预设仍生效，包括presence_penalty1.5。四任务实际seed按原JSON整数记录为2119091403160762891、2751084604308387624、14276431447698355580、7481791582233387498，第三个超过signed64但原生支持uint64；不能经JavaScript/PowerShell浮点而丢精度。下一组仅将presence_penalty设0，并将同一组原实际seed显式绑定，保持其它effective采样、请求全文、输出预算及引擎配置；wire的seed从省略到显式这一变化单独记为复现绑定。原non-thinking默认、client overrides和实际resolved值分别留档，不用temperature一项代表完整模型配方。

### 8.1 同48K采样对照结束后的选择

上述“下一组”已实际完成。先以原四个resolved seed在48K建立presence1.5基线，再仅改变presence为0；独立核验四条wire body、native engine及start/done采样。完整messages、temperature约0.2、top_p约0.8、top_k20、min_p0、frequency_penalty0、max_tokens8192和其余参数相同。原60K→48K的context变化另作控制记录，四条输出content的UTF8字节相同；不把context变化混入presence收益。

| 单次同48K结果 | presence1.5 | presence0 |
| --- | --- | --- |
| 整record错误/部分/完整支持 | 1/3/0 | 2/2/0 |
| JSON可解析/任务schema通过 | 4/4、3/4 | 4/4、2/4 |
| 34项支持/错误/部分/未评 | 1/4/6/23 | 1/5/7/21 |
| 13原子字段支持/部分/未评 | 2/5/6 | 4/5/4 |
| 四调用HTTP合计 | 51.933518s | 54.613078s |
| 整机采样GPU峰值 | 7714MiB | 7714MiB |
| qualityAccepted | false | false |

0臂的VPC否定触发、同电话识别是局部支持，不能抵消错误用途比较、虚假来源归因或absence推断。单次四任务对照不提供总体accuracy/precision/recall；保持null。选择保留presence1.5诊断基线，不采用0为改进配置；48K仍只是较多余量的候选，最大一次容量成功仍为64,512配置/62,386真实输入。

上述32K KV计划已完成：同32K两臂完整messages、8192输出预算、same实际uint64 seed及resolved sampling保持一致，engine唯一变化rk2v4-e8→rk4v4-e8；48K→32K另作context因素记录。每token KV源码预算rk2为13,312B、rk4为17,408B，推算没有代替实测。最终技术和独立源审结果见8.2，容量/KV实验到此收尾。

新的多行编号原型已通过28项离线测试并冻结469个原文片段，仍未执行27B流程，语义/额外调用预算未知。用户已明确要求“确定bonsai稳定可以运行就去跑vetting，别把目标搞偏了”，因此不追加单独框架或参数ladder，先完成真实44份应用vetting；只有实际问题需要时才恢复必要对照。embedding512/1024组保持prepare-only；H800比赛主模型仍须先建立大模型plain基线，本机低比特结果不直接外推。最终技术汇总与独立源审入口见[实验日志](vetting-experiment-log.md)。

### 8.2 最终32K两臂与当前应用参数

参数阶段最终为14实际runtime组/25次生成，4startup失败组0生成；所有原容量、采样和KVproof保留。最后32K的rk2/rk4分别4/4stop/cache0，prompt tokens均10774/7512/7524/5596，completion分别144/139/142/220及136/132/144/142。实际请求对象、四个uint64seed和所有sampling恒同，只有KV dtype改变；控制proof SHA256 `d3fb2153f7161c4ab7beee50f353c9b31a137a9a2143c110538d5c70da290a11`，final技术manifest SHA256 `d544b123f7322b86e98dc01e7726d04fdddc3d189d39371984f875687525af85`。两组单次HTTP合计51.491631/47.875629s，整机GPU7557/7627MiB、58/53samples；Maven晚于rk4停止22.404651s，未重叠，但其余背景不完全控制，不声明性能因果。

源审rk2错误1/部分3/完整支持0、schema3/4；rk4错误1/部分2/完整支持的局部图纸地址比较1、schema4/4、18/18选定引文定位。两臂qualityAccepted=false；HD officer/地址局部改善同时存在SCT6标题/采用关系不足和SCT8无日期标题归因退化，未评项不计正确，precision/recall未知。具体34项/13字段计数和evaluation SHA见实验日志。本机配方没有得到完整合同质量验收。

| 新44-source应用配方 | 本轮配置 | 验证边界 |
| --- | --- | --- |
| 原件及解析 | fresh DB/存储复制，44 PARSED：35 tender+5standard+3fact+1manifest | 原解析/表格/OCR复用，不是fresh OCR；2d/2e不进模型 |
| Java | 独立163-test JAR185 production classes；18083 | test和package身份绑定；不是已验收应用结果 |
| 模型/引擎 | Bonsai2 27B/NInfer、context32768、KVrk4v4-e8、graphOff、noThinking、prefill128、并发1 | 原payload/exe全hash引用；本轮不重hash大文件，应用每call实际采样后审 |
| 结构化调用 | temperature0.2/max_tokens8192/单choice/非stop拒绝/retry0 | native JSON grammar未启用；应用解析及证据门禁须分别检查 |
| 检索 | 独立8869/state，BGE-M3和reranker maxTokens512/batch4，CPU float32/offline | 为给Bonsai留VRAM，GPU→CPU为完整应用配方差异；fresh index实际Java触发 |
| 任务预算 | 16主题+3project-reference比较，计划19calls/21progress units | 此次起跑只确认计划；actual数量、失败/coverage/quality终态再记录 |

新目录`tmp/bonsai_vetting_application/full-case-20261002T1133137425760Z`实际在11:45:32Z唯一POST运行`5355ca9c-093e-4f9c-b879-06d15011a7c3`，只poll同ID；本补记时CPUindex仍运行。统一身份事件ID `bonsai-full-application-20261002T114532-5355ca9c`，首次身份登记11:52:48Z晚于POST，不冒称运行前preregister。44原件SHA/角色/文件名及source/block身份全部绑定，mutable native/API日志只作实际观察；actualcalls/resources/语义保持pending，后续追加而不改旧proof。


### 8.3 实际应用终态：本机参数不等于质量通过

旧8.2的“CPUindex仍运行/19计划”是起跑快照。实际run `5355ca9c-093e-4f9c-b879-06d15011a7c3`已经COMPLETED、21/21、19原生starts/dones；源与软件仍为44 PARSED复用解析/OCR、163测试/185classes/JAR固定身份。Bonsai context32768、rk4v4-e8、graphOff、noThinking、prefill128、concurrency1、输出8192/retry0保持；实际每call native sampling记录temperature约0.2/top_p约0.8/top_k20/min_p0/presence1.5/frequency0，seed使用当次精确uint64，未套此前四task seeds。

CPU float32 embedding/rerank512/batch4的新冷索引等待30分钟Read timed out，Java采用词项回退。这是检索降级baseline，不是BGE/rerank整包成功；原partial state及失败日志保留。成功调用涉及22/44来源、147/13,293唯一片段，未提交切片没有完成语义审查。2,475实际采样、整机GPU峰7,763/8,188MiB、RAM峰24,513,654,784B、samplerStopped=true；这些包含其他进程，不能与历史不同环境单次峰值作模型效率因果。索引在应用终态后由root停止owned父/子进程，不更改原sample或称后来索引成功。

最终7条=3规则有限观察+4模型。独评模型2false/2partial未充分成立、0完整支持的整model缺陷，qualityAccepted=false；34项5局部支持/8部分/21未评，13字段只是局部值与用途支持，整体accuracy/precision/recall=null。证据字面定位与程序gate不解决例外优先关系或chunk切断导致的falseabsence。Word/PDF实际逐页QA已做（16+16页），正文/证据保全，但nativePDF尾1行和EN系统prefix混语仍需显示修复；不称报告交付完全验收。

下一步仅针对本次实际问题改进：使真实整包索引/检索可完成、保留完整引用与必要限定、修发现判断和报告显示，再复核同run人审/导出链路。容量/KV阶段14组25生成已收尾，不另扩参数矩阵。4B/7B对Bonsai的已测局部改善见[实验日志](vetting-experiment-log.md)：同四user/source任务有1bounded local full记录，但system/预算/grammar/引擎不同，非only-model效果；4B范围又不同。4060配方不能直接外推H800比赛主模型，H800仍须独立离线与源审验证。

### 8.4 用逐阶段probe定位实际瓶颈

用户要求每一步probe，链路为source → parse/OCR → chunk → index → hybrid candidates → rerank → context → model → evidence/applicability gate → human review/export。每阶段记录固定输入/实际输出hash、执行或复用、elapsed、截断/遗漏范围、异常和资源；API200、quote定位或空数组不替代语义PASS。新的运行时细分探针尚待实际执行，不能把5355历史19次原生调用及词项回退当新阶段验收。

源侧0-call基线已完成，入口[probe README](../../tmp/bonsai_vetting_application/full-case-20261002T1133137425760Z/source_pipeline_probe_20261002T125441Z/README.md)。对44来源及13,293 chunks/207,800 Parts逐字和UTF16offset核对，207,746存储非空块13,266,431字符区间全局完整、无orphan/mismatch；49长块103partial Parts。源table rows3,686、实际序列化cells仅15fact Parts；230table chunks没有row0或serializedheader，只是机械可见性观察，不能称全表格正确或230错误。strike/deleted元数据18块仍存储，Chunk.Part不直接携flag；原始转录、继承删除线和效力仍需其他证据。manifest SHA256 `0f3fc3b1cdad7a2fe67ba61bb541a73dc3ce888bf33f138d1a4d5aea2baeca6b`，日志scope为offline_source_parse_chunk_observation，0真实parser/OCR/embedding/rerank/API/DB。

旧parseCoverage显示10文件147 OCR页，历史parse/OCRelapsed未在本probe观测，保持null而非0。Note1源全文1548字符，在@0[0,1400)和@1200[1200,1548)两个片段全局保留，前一片段没有minimum requirement续句；全局正文不丢失与单次模型窗口完整是两个验收层。下一runtime probe需绑定具体选中哪些chunk、是否补齐必要限定，不能凭全局union推断context完整。原报告、Gold、parser源码与存储正文未改。

### 8.5 实际GPU stage与新整包探针

上述准备快照保留。GPU stage现已实际完成13293 index inputs、64 query inputs、1840 rerank pairs，8731 events，input_ids/attention_mask独审全15197行<=512。138 index/36 pairs有截断，但六重点源目标均无截断。AQCC目标q5 RRF10→rerank17，关键缺口在重排top10；Note1首块q17 RRF68/cap50之外，续段未入dense/BM25候选；不能先用扩大tokens解释这些目标。整张GPU有限采样峰3512MiB、index receipt239.17s，开启探针及嵌套计时不算净benchmark。

179-test Java严格语料身份/失败与stage timing、27-test Python原features/vector/ranks、15-fixture proxy真实entity bytes已接入新run `1661b036-c465-477d-9d96-7d548d9c7ca6`，13:49:10Z唯一POST，终态质量待actual审计。参数仍Bonsai32K/rk4、output8192、no-thinking、graphoff、concurrency1；检索改实际GPU预计算+CPU sealed回放，strict失败不词项回退，随机seed非paired。新逐页parser/OCR/corpus/export源码在独立版本补齐，现运行复用44解析来源，0freshOCR；未执行的软件或历史时长保持unknown。[逐阶段记录与断点](vetting-pipeline-observability.md)给出完整scope、artifact和下次可归因比较入口。


## 2026-10-02 全阶段探针终态与当前账号 Codex 对照

1661 run 在14:01:44Z完成21/21、19 native starts/dones、19完整proxy captures，唯一POST。GPU fresh13293-index/64queries预计算完成并独审；应用回放外部SHA封存响应，strictHybrid失败/失配中止，新增解析/OCR/embedding/rerank模型调用0。实际158唯一chunks/26docs提交；不能写成全13,293语义审查。全阶段identity、原输入/输出、实际token features、rank、context丢弃、模型sampling/seed/raw bytes、schema/quote/角色gate、report bytes和资源证据保留。

最终4发现=3保守规则观察+1模型保险unknown-as-issue。独立47 raw审计、87quotes、固定34项和13字段已完成：34为6局部支持/4部分/1反例/23未评，13字段只是局部值/用途支持，qualityAccepted=false，precision/recall/accuracy未知。保险原Word Schedule段617已列A3，但该段未进入prompt；override已正确理解，此轮缺口在context，再由issue分类/risk gate放行。payment忽略submitted Not used和HOMES忽略已提交星号显示模型解读也有瓶颈。AQCC重排17被top10截掉；Note1首块RRF68被candidates50截掉、续块未入候选，重点源目标没有512截断。

应用754.05s；gateway19累计669.82s，64全语料身份hash43.66s，sealed检索HTTP0.558s，16context selection15.69s，Java1539events已测observer1.504s。GPU阶段fresh index receipt239.17s单独计量。嵌套阶段不可相加，probe开启不是净性能基准。应用samplerStopped、753samples，整机GPU有限采样峰7734MiB。Word14+原PDF13共27页逐页实际查看、字段/原bytes核对完成；null省略差44/实质差0、人审字段仍全null，版式保留轻微分页记录。

解析/OCR/corpus/导出内部探针已完成targeted75和full197/23suites（0failure/error/skip），原legacy parse即使配置probe也保持无新增I/O，drafting调用不改变。12文件精确复制并核对120个manifest源码/测试条目；当前实际179 JAR/原输出不替换。配置consense.document.probe-directory、consense.vetting.probe-directory、consense.vetting.export-probe-directory默认off。Typed OCR result不冒称provider transport原body；新软件证明不代表新扫描件OCR精度通过。软件与原调用失败链、JSON artifact命名冲突、旧XML摘要纠正、GBK辅助输出失败均保留。

用户进一步提出强模型API排查，并明确“就用本账号的codex”。已冻结19份实际messages/schema，先选call1/2/3/6/11独立无父历史盲审；只读取该call输入，不读Bonsai输出/gold/额外原件，输出冻结后才评价。当前账号继承模型/推理设置与Bonsai的harness、采样、tokenizer、输出预算不同，不能称严格模型单因素API对照。空array不是理解/覆盖证明；强模型错误同样保留。比赛本地部署及H800复测目标继续active。

证明入口：

- `tmp/bonsai_vetting_application/full-case-probed-20261002T1330Z/planning_identity/actual_terminal_registry_event.json`，terminal event SHA1c448416fcb08b57167a5b6003a11b8e1020b54ab635f93e40609110c3394a3b；pre-POST parameters/inputFingerprints不变。
- `tmp/bonsai_vetting_application/full-case-probed-20261002T1330Z/probes/timing-final-20261002T1413Z/java_probe_timing_summary.json`。
- `tmp/bonsai_vetting_application/independent_quality/1661-final-source-audit-20261002T141853931992Z/evaluation.json`，SHA590bef8fba7afc3c9d16be942ddd3c38d4feb0234f0b685a25db0cb16d1d0ea0。
- `tmp/bonsai_vetting_application/full-case-probed-20261002T1330Z/qa-report-1661-20261002T140916Z/qa_manifest.json`，SHA2c7bc410b96cb1e9b7173918feacfe7aa26db0614b71291bae976f670e3db4e1。
- `tmp/staged_retrieval_cache/java-document-probes-20261002T1334/frozen_proof/final-legacy-isolated-197/manifest.json`，SHA70c31ed93de3fb81cdd6d0c28624d8753e51e4e66af66f822731f0d8bda48a2c；promotion SHAffebe2d0950d82ed5be3b3fc2d48b30b1ca843bf79597f8cfbcb374fb6f81d96。
- `tmp/codex_material_control/prepared-1661-20261002T1419Z/prepared_manifest.json`，SHAc247d315d5ee316bb7628c89f5bf74b7f88d8301407fe49e64c64cc5ce9ae8f8；准备19份不等于已运行19次。

完整证据边界与逐步表见[逐阶段探针文档](vetting-pipeline-observability.md)。此前运行中/待终态段落保留为对应时间快照，由本段实际终态更新当前状态。


## 2026-10-02 同材料对照结论：模型解读与工作流缺口并存

用户问“瓶颈是模型还是harness有问题”。已执行结果支持两者并存，但位置不同，不能用一个总误报率或主观百分比归因。

| 环节 | 实际证据 | 当前结论 |
| --- | --- | --- |
| 检索及上下文 | AQCC任期说明dense3/RRF10后rerank17，被top10排除；Note1首块RRF68超候选50，含限定的续块未进候选；保险当前原Word Schedule段617列A3，却未提交 | 关键条件未送达，强模型不能凭空补证；这些重点目标不是512-token截断造成 |
| 模型解读/分类 | 相同输入的5份Codex盲对照没有重复Bonsai的Not used/amendment和HOMES星号缺失误报；AQCC数量差异改为范围未知的协调建议 | 同材料下更强审查端有改善，支持当前Bonsai部署配方存在理解/分类瓶颈；空array不证明完整理解 |
| 问题接受门禁 | 4条新risk/language候选经原179 Java gate接受；实际comparisonRequired=false，不因quotedUnresolved拒绝。新保险建议仍有专业设计/独立保单/采用关系未知 | 位置匹配与结构合法不足以确认合同缺陷，需分开已证实问题、协调建议、信息不足及覆盖缺口 |
| 核心编排/传输/解码 | 原179 JAR离线复现全部19提示词和selection、47raw/28接受/19拒/1candidate；原gate reasons/quoteChecks恒同，原HTTP messages与Java提交相同 | 本轮结果可复现；未发现提示词传输或JSON解析损坏足以解释质量问题，不对其他案例作绝对无bug证明 |
| 速度 | 实际应用754.05s，19次AiGateway累计669.82s；GPU索引239.17s另行预计算 | 当前应用时间主要在生成链路；gateway含客户端处理，嵌套阶段不相加，sealed检索耗时不当作实时GPU inference |

这是能力对照及具体失证阶段定位，不是严格模型单因素实验：Codex继承当前账号模型/推理设置，其harness、采样、tokenizer与context/output预算不与Bonsai匹配；精确provider模型ID和provider请求总数未暴露。仅5份独立审查输出，其他14份没有做Codex审查。mixed swap产生4个候选只说明原门禁接受，没有新增整包Codex结果、应用DB发现或质量验收。accuracy/precision/recall保持未知，H800尚未实测。

后续修复顺序：先按完整条款/表格行/限定条件及明确交叉引用补检索，记录未覆盖目标；再把条件未闭合的风险建议与已确认缺陷分开，补证失败保留unknown；最后用同冻结材料和整包案例复测提示词/强模型。必要时加入调用受限、过程可回放的补检索runtime。现有证据不支持直接通过更换框架名称解决问题，也不支持认为H800强模型会自动修复证据遗漏与接受规则。

新service lifecycle源码探针已通过210项软件测试，独立复查另发现REQUIRED事务参与外层事务时返回不等于提交，正在做probe-only修正及真实H2 rollback fixture；不改变原业务事务、DAO或人审字段。210软件证明及归档辅助失败保留，新版本完成前不称该源码已推广或用于1661实际运行。解析/OCR重跑、H800质量及下一轮整包审查仍未完成。

对照证据：

- [五份实际输出与源审](../../tmp/codex_material_control/final-1661-five-20261002T1446Z/comparison.md)，evaluation SHA `00d425ea49827e0798ebc135131dcd638fc1f9f84b97d39341438f1a12aa675a`。
- [原179门禁回放最终证明](../../tmp/offline_179_application_replays/final-proof-20261002T144357123155Z/summary.json)，SHA `1723bf8d25da40bcb072da9f067e0ef32da50d686c4c448a4f5c2fb10c4d9b8c`；5目标gate明细SHA `283773bcb0bd7e870fe168a147fcc51839ca6086acaf4ec375007f9714574686`。
- [逐阶段观察与完整边界](vetting-pipeline-observability.md)。本轮观察完成不作为vetting交付目标已验收的证明。


## 2026-10-02 lifecycle探针收尾：211项软件证明与源码接回

规则源构造→原RuleEngine输出→原semantic candidates→severity排序→source规范化→fingerprint去重/risk cap→coverage→保存前后原记录及人审字段→事务返回→失败/重启恢复的探针已补齐。targeted实际33 tests/4 suites，完整offline Maven package实际211 tests/25 suites，failure/error/skip均0。经root和独立只读复查，5文件精确复制到仓库，全部123个manifest源/测试条目与full-package源码相同。默认off，配置`consense.vetting.service-probe-directory`使用每轮独立绝对目录；新service-UUID操作绑定真实run/project，与semantic/parse/export目录隔离。

原排序、first-wins去重、risk++计数、业务REQUIRED事务、DAO次数和人审更新逻辑不变。probe只记录原业务状态：保存/flush后的snapshot属于pending；事务返回时若当前线程仍参与外层事务，`committed=null`及`participatingOuterTransaction=true`，外层结果unknown；无外层时才记录已正常完成自己的事务。真实in-memory H2 outer recovery fixture显示内部暂变FAILED、caller rollback后仍RUNNING，探针未伪报最终提交。Observer写盘/序列化失败隔离于业务成功，原业务异常cause/suppressed保留；观察失败不能当作完整证据。其它旧semantic/corpus/export探针失败策略没有改变。

此前210项软件测试通过后，辅助freeze先有Windows206长路径失败，再有普通Python rglob只见3个无效目录1-byte fixture、漏列实际lifecycle事件的归档范围问题。原partial/v210与完整extended-path v210a归档均保留；最终fresh版本采用extended-length路径读取及平铺文件、源→copy bytes/SHA映射。辅助归档缺口不当作模型/业务失败，也不回改210原manifest。新事务声明修正由fresh targeted/full-package及独立源审证明。

这次是软件fixture/唯一内存H2的实际流程证明，未产生新的模型/OCR识别、真实检索API、原应用DB或GPU调用。运行中的1661实际案例仍是原179 JAR，原输出/报告/独审及27页QA不替换；新源码未被称为重新部署或真实整包质量改善。下一轮真实运行才能获得新service/解析/OCR/导出内部阶段的实际耗时和结果。完整vetting质量及H800验收仍未通过。

证明：`C:/Coding/ConSense/tmp/staged_retrieval_cache/service-lifecycle-tx-20261002T150517046509Z/frozen_proof/v211/manifest.json`，manifest SHA `bd8ef34a4f356486ea74290e40ec121bc2533c6b54e59e6885f9a4bb49b95e42`；精确源码推广：`C:/Coding/ConSense/tmp/probe-source-promotion-lifecycle-20261002T151508272323Z/promotion_manifest.json`，SHA `65ceff36f5e8f634d4d7fb8195ae76d218e89848b6d097aecaad6b1f865d68fb`。当前实际模型/检索/门禁诊断继续见[逐阶段探针文档](vetting-pipeline-observability.md)。


## 2026-10-02 OCR与检索精度抽查：已有真实小样，当前保存结果仍有损伤

用户进一步询问“向量数据库和ocr的精度有抽查吗”。此前确有真实OCR与检索小样；最新1661应用复用解析、新增OCR0只限定该run，不能解释成项目没有做过OCR人工核对。

OCR已执行固定FT物理2/7、AA物理1/2、APL物理3共五页的DPI200/250/300三个arm，15次真实CPU识别；五原图与15overlay人工看过，每arm原needle21/22、14区域31literal小样30/31。另一组固定DPI200、max-side2000/3000两个arm共10次真实CPU识别，needle均21/22，局部literal30/31→29/31。全部25份历史raw OCR JSON及冻结sidecar/SHA已独立复核，未修改原匹配规则。提高DPI没有改变该组det实际1408×1984输入；max-side3000确实改变det输入，但FT期限文字退化且编号有合并/漏检取舍。维持已测200/2000基线不等于识别精度合格。

当前44源inventory按真实project过滤实算10源ocrUsed=true、147个已记录OCR页；全部PARSED/complete只是保存的解析覆盖。新增两页当前原件的纯CPU渲染和人工复查显示：FT source76物理2脚注1首行在已保存OCR中为严重乱码，pdf-page:2:ocr-line:55/confidence0.56112；APL source99物理3原罗马编号清楚且姓名槽位空，已保存结果却合成BEEE/日目日/3333（line11/14/19）。max-side3000还把期限起点文字识别为frdom/Tenderer。旧pilot局部30/31不代表当前147页质量；低分/高分、返回文本、complete都不能代替原页核对。整体accuracy、整页CER、表格关系/删除线精度仍unknown/null，未准备完整逐字转写。

向量库的ID/原文字段/role/向量顺序、入库数和查询身份已机械核对；检索精度要评价embedding→候选融合→rerank→实际context。历史2,739 chunks/8 queries/11 contains-needle的512/1024对照均11/11进top10；只证明指定片段被召回，不作总体Recall@K/Precision@K或条件闭合证明。最新全44源13,293 chunks、64 queries的源审已确认AQCC任期rerank17被top10排除，Note1首块RRF68被候选50排除、关键续段未进候选。未被截断的源目标仍可漏召回，条件和交叉引用要另外标注。

下一轮精度工作按原件页/条款范围分层：OCR逐字转写并单列条款号、金额、天数、否定/删除/OR、空槽位与表格列；检索以独立相关条款及必要限定为标签，分别计算candidate/topK/最终context覆盖。人工labels只用于评价，不能回填本轮请求、原gold或预先指定应有发现。当前两页抽查支持明确损伤定位，不支持总体百分比。

完整独立证据：[来源与OCR抽查审计](../../tmp/independent_ocr_spot_checks/source-two-page-20261002T151751193921Z/independent_ocr_precision_spot_audit.json)，SHA `253f8046cddf2513aa210a38cdc33d16be686e5b5d51460e4af9f38709ca0572`；[说明及两原页图入口](../../tmp/independent_ocr_spot_checks/source-two-page-20261002T151751193921Z/README.md)。新增操作仅文件读取、两页CPU渲染及独立评价，新增OCR/LLM/API/DB/索引均0。保存的原件、旧OCR及既有证明SHA保持不变。

## 2026-10-03 MiniMax-M3直连同材料对照：首五未通过，余14未发送

时间按Asia/Shanghai为2026-10-03，实际API与证明时间为2026-10-02 UTC。用户提供OpenCode Go后，首次请求因缺x-opencode-session返回400；补官方稳定UUID会话头后返回403，响应称需要active Go subscription。用户确认已启用Go，因此这只证明该请求的entitlement校验失败，未证明用户账号未订阅；workspace/key归属尚未定位。用户随后明确改用大陆MiniMax Token Plan，Go诊断停止。两个Go POST均未得到模型回答，分别保留失败recipe，不自动重发、不混入质量统计。新订阅凭据以Windows DPAPI保存在repo外，通过瞬时环境变量注入；key不进入模型消息、源码或实验日志。

MiniMax官方只读Token Plan用量GET返回HTTP200/base_resp0；实际随后五个POST均HTTP200/MiniMax-M3/finish_reason stop。选择稳定M3，当前官方Token Plan支持M3；M3.1-Flash-Preview标注只通过M Plan/MiniMax Code，未假设预览权限。大陆直连https://api.minimax.cn/v1/chat/completions，仅stdlib HTTPS，无SDK/CLI/代理/重试/额外Codex system。M3参数为thinking adaptive、reasoning_split true、temperature0.2、max_tokens16384（含思考）、stream false、n1，reasoning_effort不发送（当前文档仅preview适用）。保留原Bonsai的system projection、user、schema和消息结构；19准备输入逐字验证，五个实际wire body再次绑定。模型/provider、thinking、输出预算、tokenizer与服务默认项有差异，非严格单模型因果实验。模型1M窗口是官方规格，没有执行1M输入容量试验。

冻结19输入后，先按1/2/3/6/11跑首5；运行前登记，串行、retry0、180s socket timeout并非绝对wall上限。五次wall分别108.079/172.797/172.031/134.828/73.360秒，合计661.095秒；provider报prompt21143、completion45875 tokens。usage中reasoning_tokens口径不一致，保留原值，不据零值推断无思考；remote GPU/prefill/decode未知。原HTTP实体/headers/usage/model/id/finish和content/reasoning分别存档，未剥fence、拼reasoning或修JSON。

| 原ordinal | 实际结果与独立源审 | 原179门禁 |
| --- | --- | --- |
| 1 付款/删除 | 正确认出14.2(4)已删除；唯一新issue是缺(e)，被原Word自动编号反证 | 1 raw通过并成为候选，但仍是false finding |
| 2 人员数量 | 合法[]，未重复QCM two≥one的互斥minimum错误；空数组不代表AQCC岗位/时长/适用全部审查 | 0 raw |
| 3 定义 | 1 parent-role输入未知、1 CTA/Site Staff条件建议；未证明合同排除或冲突 | 2 raw均因引文定位失败拒绝 |
| 6 HOMES | 正确区分asterisk remarks与两列，但把解析丢失的22个勾选误认为原合同缺填 | 1 raw因改写引文拒绝 |
| 11 保险 | 原JSON字段名损坏，未修复；可读文字的广义A3/专业责任概括也过宽 | 原生产解析失败，schema及record gate未到达，raw=null |

自动编号证明：SCC native body child936对应Java body/908/paragraph，numId14首次且唯一使用、无override/style引用，abstractNum10/ilvl0/start5/lowerLetter/(%1)确定显示(e)。HOMES证明：PRE native table47对应Java body2326，rows1..11 cells2/3共22个Wingdings:F0FC w:sym；当前collector均丢失。编号和符号是原件OOXML，不是OCR；本轮没有重写原解析缓存。DocumentParser的DOM collector只处理w:t/delText/tab/br/cr/p，缺编号上下文及w:sym，VettingCorpus再拼getText无法恢复。二者分别定位到parseDocx/wordBlock和collectWordText，后续先修解析保真与源provenance，再用新材料身份重建索引复测。

实际原179类的单次CPU mixed5回放exit0，19 prompt/selection断言等同，65检索边界为保存文件替身，不是真实index/query。首5四条有效raw=1机械接受/3拒/1候选；第11原JSON的Unexpected9在line9 col19导致模型解析阶段失败，不能记schema rejected或有效保险finding。唯一候选(e)被原件反证。原生产只查6quotes=3located/3failed，另4因prefix-break未查；独审遍历10quotes有6逐字定位，口径分别保留。混合19为36raw/19accept/17reject/1candidate，其中未替换14仍是原Bonsai32raw，与旧结果逐项相同；不是19次MiniMax生成、新整包run或应用DB结果。

独审四条raw分类：2原件反证false defects、1适当输入范围unknown、1条件协调建议，confirmed contract defects0；第11可读文字另列，不加入有效raw统计。原Bonsai首5有15raw、原Codex首5有4raw，数量不能作为准确率。M3在部分删除/计时/minimum/remark解读上未重复旧错，但原件编号/符号缺失、RAG条件漏召回、未知推广成缺陷及不合格结构输出仍是独立瓶颈。此前AQCC duration rerank17/top10、Note1 RRF68/candidate50和Schedule未提交的缺口没有因此被补齐。

按照“首5输出合格后再扩展”的计划，当前暂停余14的发送。全19准备完成不等于实际全部生成；实验terminal为failed_initial_review，5个transport成功、4个schema数组、1个JSON解析失败。accuracy/precision/recall为null，qualityAccepted=false，完整vetting Goal未验收；H800未实测。当前不凭这组结果引入agent框架，优先修源材料保真、必要条件召回、unknown/absence门禁，再单列格式提示或结构约束对照。新source/parser/index身份不得沿用旧冻结输入冒称同材料。

软件仅mock测试最终9项通过（10.646s）。适配时两个旧Go host/path fixture失败日志保留；corrected fixtures与provider base_resp失败停止检查通过，没有新增测试模型调用。登记helper曾把in_progress ordinal3放进不可改calls前缀，终态追加被拒；保留旧快照，后续actualCallMetrics绑定完整实际终态。该日志观察器修正未改API请求/回答或生产gate，不能归作模型质量问题。

统一结果：[同材料逐次对比与汇总](../../tmp/minimax_material_control/final-first5-20261002T162339182102Z/evaluation.json)，SHA 237cd32d4b6c9638356fc1289a26e7d86f66eff422d92c986f1807e3e76c6e89；[源审说明](../../tmp/minimax_material_control/independent-source-review-20261002T160511Z/README.md)；[原Word编号及符号证据](../../tmp/minimax_material_control/independent-source-review-20261002T160511Z/native_numbering_and_homes_symbol_proof.json)；[原179回放证明](../../tmp/offline_179_direct_api_replays/minimax-first-five-20261002T161149167103Z/final_proof_manifest.json)。统一registry与comparison已追加terminal，247个artifact身份由root再次实算核对，key不在日志。

官方依据：[MiniMax OpenAI兼容参数](https://platform.minimax.cn/docs/api-reference/text-openai-api)、[Token Plan Key与用量接口](https://platform.minimax.cn/docs/token-plan/faq)。每次源审仅评价实际提交范围，gold不回填模型输入。

## 2026-10-03 Word 保真修复与二来源真实复测

解析软件冻结并接回 7 个文件，完整 246 项/针对 35 项测试均通过。实际 source80/SCC 为 PARSED，793 blocks、228 chunks；source95/PRE 为 PARTIAL，10,531 blocks、1,631 chunks。PRE 中 22 个 Wingdings:F0FC 勾选已明确映射为 `✓`，另有 13 个 unresolved numbering、4 个不支持字体符号及 2 处 AlternateContent warnings，均保留 unknown/provenance，没有把无法解释的内容静默当作空白或可用条款。原 Word 物理页仍未知，本轮新增 OCR 为 0。

| 已测阶段 | 实际参数与结果 | 比较边界 |
| --- | --- | --- |
| 新原件解析/生产分块 | 2 次实际 DOCX parse；source80 约 1.507 s、source95 约 3.575 s；1,859 chunks | 原字节 SHA 与派生 sourceHash 分开；不是旧解析缓存重放 |
| CPU fresh index | BGE-M3，float32，maxTokens512/batch4，torch4 threads/inter-op1；cached=false；index client wall 953.990 s | 新项目及独立 state；实际 1,859 vectors，不复用旧向量；包含观察开销 |
| 两 query/rerank | BGE-reranker-v2-m3，float32，maxTokens512/batch4；BM25+dense/RRF60；candidates50/topK10；36.775/34.203 s | 新 dispatch1/2 对应原主题1/6；两源 CPU 而非旧44源 GPU 单因素比较 |
| 生产上下文 | semanticContextChars10000；付款 12 chunks/3306 chars，HOMES 9 chunks/9671 chars；无 reference-role hits | 原调用范围、引用材料与上下文预算均需单列；不是完整限定或全语料覆盖 |
| MiniMax-M3 | temperature0.2，max_tokens16384，thinking adaptive，reasoning_split true，stream false，n1，serial1/retry0；128.516/52.203 s | 新请求保持当前生产 system/user/schema 格式；远程算力、prefill/decode 未测；usage 保留原值 |

两次模型累计 wall 180.719 s，provider 报 prompt 6,953、completion 14,818 tokens。call1 返回合法 `[]`；call2 原始 JSON 的 `suggestion` 字段少冒号，正式 parser 失败，有效记录 null。可读三段文字仅作独立语义审核，不修响应、不计为三个有效 raw/候选。syntax 失败与语义、召回、完整采用关系分别计量；两处已恢复材料进入请求不证明所有条件召回或总体质量改善。

后续格式控制单列新实验，冻结同 provider/model、材料、预算、真实请求与原响应，比较提示词/输出表示/工具调用的影响。当前核对的官方 Chat Completions 文档列出 function tools，没有文档列出 `response_format`/`json_schema`；不能宣称已经验证 MiniMax 原生严格 schema。tools 参数支持也不自动证明严格解码或语义成立。必要条件召回、absence 与 scope hints 规则继续优先。

实际 [解析及分块 manifest](../../tmp/docx_fidelity/actual-two-source-20261002T1658Z/fresh_parse/corpus_manifest.json)、[CPU 索引/查询结果](../../tmp/docx_fidelity/actual-two-source-20261002T1658Z/cpu_actual/result.json)、[两次 API 原输出](../../tmp/docx_fidelity/actual-two-source-20261002T1658Z/minimax_actual/run_result.json)、[正式解码与门禁投影](../../tmp/docx_fidelity/actual-two-source-20261002T1658Z/gate_actual/gate_result.json) 保留独立身份。新 index signature 为 `fb1cc9a9e654cab93f9676f656ce81e97f41697f52ffb39784408ee6f53187cb`。集中说明与独审见 [本轮 DOCX 保真结果](vetting-docx-fidelity-20261003.md)。源码未部署到旧服务/UI；本轮未执行完整44来源、H800、新 OCR 或 UI 测试。共享 parser 的 PARTIAL 对 drafting 状态计数影响仍需集成核对，未修改其他队员 DraftingService。


## 2026-10-03 同HOMES输入格式提示包实测

新增一次M3对照，experiment `minimax-m3-homes-format-prompt-20261002T1740Z`，原topic6、user/schema/selection/index以及全部body参数保持相同。只在systemSource与schema envelope间增加445-byte协议包：裸JSON键/冒号、逐字引文、信息不足规则；评价整个包，不单归某句提醒。无新embedding/rerank/OCR/index/DB；driver11 mocks、gate adapter6 guards通过。

唯一POST HTTP200/stop、129.078s，prompt3953/completion8755；原baseline topic6为52.203s、3873/6267。provider耗时/生成变化只记录观察，不据一对值宣称性能增益。原始新content裸数组，production gateway parsed2、parseFailed0；机械accept1/reject1/candidate0（insufficient不作issue）。language quote弯引号改写失败、来源PRE→SCC误归、必要SCC6/8仍未在窗口；格式改善一次，repeat reliability/semanticQuality/whole44/H800不验收。source repair后的2次API与本次1次API分开保留。详见[参数、材料与逐次源审](vetting-docx-fidelity-20261003.md)。


## 2026-10-03 显式引用范围与 20k 单次 MiniMax 对照终态

通用 ContextBuilder 引用扩展已通过 26 针对 / 257 完整测试并接回两个文件，214 个生产类与冻结 JAR 逐字对应，原 246 项测试身份保持。独审在初版发现重复/null heading 及动态起点第二跳，v2 同四个 fixtures 全通过；初版失败证明保留。起点固定为初始已入选 ranked core，目标绑定 doc/hash/role/heading，完整父条预算不足保留 missing IDs，不推断原合同缺失。

保持原件、新解析及历史真实 CPU ranking：旧 HOMES 10k 为 9 chunks/9671 chars、SCC22.304 5/10；新10k为11/9925、6/10，(6)/(8)仍缺；新20k为18/17203、10/10，正文与续段均完整进入。新增解析/索引/查询/OCR均0，只有生产 context 回放；exactSubclauseVerified仍false，4个origin unresolved，qualifiers unknown。Root launch classpath 已记录，125依赖JAR为运行后字节观察，不宣称launch前immutable。

唯一新 API（同M3/.2/max16384/adaptive/reasoning_split/n1/serial1/retry0/socket180、同445-byte格式包）HTTP200/stop、158.656s，prompt10625/completion10907；合法raw1，机械accepted1/candidate1，三quote定位、真实basis一次。它没有重复旧PRE→SCC误归和缺(6)/(8)判断，仅是一轮观察。唯一issue却把下一APPENDIX IV独立Subtitle当HOMES表页脚；原Wordsection/header关系反证：HOMES属于III，IV下一章是APPEND4.9水龙头冲洗。当前Corpus把带点/斜杠的下一附录marker继承上一owner，逐字quote gate无法验证版面角色。独审confirmedIssues0/falseIssues1/qualityAcceptedfalse，整体precision/recall/accuracy未知。

当前执行点是新的通用章节边界修复及新segmentation/corpus/index/material验证，避免改源编号迎合误报；随后继续完整来源、人工审核与同版本导出。Source、语义claim归属和门禁问题仍需分别验证，暂不以这条错误决定复杂agent框架必要。默认预算/旧运行实例尚未替换；H800与新OCR验证未做。App Goal本轮查询仍为null，没有擅自新建，也未将完整vetting目标标记完成。

完整参数、范围、原始失败和阶段对照见 [显式引用范围修复与上下文预算对照](vetting-reference-closure-20261003.md)；[最终比较](../../tmp/docx_reference_closure/final-comparison-20261002T185937009135Z/evaluation.json) SHA `1c4d49ffd0e9c822dc28dc0ede0c21966c80260fce1e2a4dcfedde5d752fc655`。统一registry追加4个独立事件及后续source评价，原观察与旧报告保持原身份。


## 2026-10-03 独立章节边界、新 CPU 索引与 thinking 对照已完成

本轮继续 vetting，不扩展 Drafting。已修复独立 APPENDIX/ANNEX/SCHEDULE 与 Word Title/Subtitle 继承上一条款 owner 的失真，表格内值与普通正文不误切分，无法确定 owner 时保留 unknown。新增 segmentationVersion=standalone-section-boundary-v1 并重算所有 chunk IDs。隔离 package 268 tests/30 suites、针对28项、0失败/错误/跳过，旧257项身份保留；14独立反例修复后全通过。源码基线129份中Corpus1份改变、其余128份字节保持，新增测试文件1份，共130份核对；两文件精确接回工作区，214个生产class与JAR对应。初版误切分及freeze脚本旧报告路径错误的证明保留。

复用SCC80/PRE95真实保存parse，新chunks1859→1895，11342 Part occurrences及全部保存的非空blocks coverage核对。PRE parseStatus仍PARTIAL，不能把保存parse到chunk传输完整性解释为原件所有结构已完整解析。新本地CPU/float32 BGE-M3+reranker索引1895条、cached=false，固定maxTokens512/batch4/threads4/interop1/topK10/candidates50/RRF60/串行1/retry0，索引743.476s、两查询28.545/29.240s、工具整体810.421s。17次encode wrapper与2次rerank成功；内部forward调用数未知。落盘1895向量与实际编码输出逐元素一致、1024维finite/unit norm；不是检索语义准确率。62次采样最大进程RSS约4.516GiB、机器可用RAM最低约10.218GiB，真实峰值与GPU未知。完成后的Qdrant析构警告单列，不当作索引失败。

token裁剪index46/1895、rerank3/100，但焦点(e)、HOMES、SCC22.304(6)/(8)未裁剪。(6)/(8)rerank为21/14，纯top10仍漏必要条件；生产引用闭包将完整父条10/10及续段加入20k实验材料。两主题12chunks/3306 UTF-16字符、18/17166；30个选中chunk occurrences/102 Parts与corpus对应，IV marker已不进入材料。14 literal traces（2 exact clause、12 full parent）required/submitted完整、missing0。4个unresolvedComparisonIds是origin比较提示，并非4个literal targets缺失；精确子条款metadata、限定条件完整性和全文absence仍未知。

实际新API POST共2次，均直连大陆MiniMax-M3，.2/max_tokens16384/reasoning_split=true/n1/streamfalse/串行1/retry0、同445-byte格式指令。新分块adaptive HTTP200/length、133.235s，completion16384、reasoning_tokens16383，message缺少正式content；状态failed/output budget exhausted/semanticNotAssessable/gateNotReached。不能转换为[]、不能称JSON语法失败，也未评价旧IV误报是否复现。新单参数对照只改/thinking/type为disabled，完整messages和20k素材相同；HTTP200/stop、1.484s、原content为实际[]、completion2。正式gateway解码1/schema1，parsed0/parseFailed0/shapeRejected0；无record故evidence0/basis0。这一次没有旧IV误报，没有正例可测召回，accuracy/precision/recall/整体质量/重复稳定性仍null，不据此改变生产thinking默认参数。

日志同时保留API v1 query包装适配错误（synthetic mocks通过但真实prepare安全拒绝，HTTP0）、修正后的v2与真实input guards、adaptive失败、disabled空结果和独审更正。统一registry向CPU/adaptive/disabled三个实验各追加independent_audit事件，原parameters/inputFingerprints/status/measurements与调用前缀逐字保留。新分块与旧轮之间多因素改变；只有本轮两请求是thinking单因素对照，耗时不宣称性能优化。

后续执行点：先用固定、原件核实的真问题正例与无问题反例，锁定修正后material，做Bonsai/M3、thinking与output budget对照；分别计漏报、误报、引文定位、来源角色、条件覆盖、输出预算和资源成本。如果纯prompt仍不能稳定完成字段/条件/来源核验，再加入有步数和token预算的检索扩展、source核查和claim复核，自研状态机或开源runtime改造依据实测选择。输出缺失、解码拒绝、依据检查未到达与合法空结果必须分开记录。

完整报告见 [章节边界修复与同参数复测](vetting-appendix-boundary-20261003.md)；[最终机器对照](../../tmp/docx_appendix_boundary/final-comparison-20261002T2000Z/evaluation.json) SHA `f89ee3490a5e9749ac1704525a3891e5ff3201e684bb6ca30c72762b78073a4a`。真实调用范围：parse/OCR0、新chunk构建1、本地向量索引1/query2/encode17/rerank2、外部API2、saved正式gateway解码1；完整application/H8000。运行服务、UI、旧数据库和旧索引尚未替换，应用默认context预算仍保持原配置。本轮未验收44来源、正例召回、全量OCR、H800性能、人审及同版本Word/PDF导出。

最新get_goal仍为null。此处更新工程目标和执行点，不代表新建app Goal或整体验收完成。


## 2026-10-03 新基础链路 Goal：停止新增审查模型实验

用户最新要求优先修数据质量、切块、嵌入、重排序和harness。实际App create_goal已返回active，未设置token预算；最新目标与完整发现见 [基础链路汇总](vetting-foundation-findings-20261003.md)。历史模型/提示词计划保留为当时记录，当前不继续发送新的审查LLM请求。

最新源版本419 tests/35 suites通过并接回，尚未替换旧服务/数据库/索引。已修编号、勾选、附录边界、严格输出、typed failures和实际预算；第一待修复为tender原生cells在Corpus角色条件处丢失。embedding/rerank截断、候选cap/topK排除必要条件、OCR原页损伤及机械依据检查与语义结论的区别按独立层级验收。每轮继续记录实际输入/参数/源身份、失败和前后比较；独立标注只作评价，未标注范围与整体质量仍unknown。

新产物目录 `tmp/vetting_foundation_hardening`；应用Goal创建收据 `tmp/vetting_foundation_hardening/goal_created-20261003.json`。本轮scope记录没有模型、OCR、索引或检索调用。


## 基础Goal第一阶段已完成：原生cells及报告定位状态

合并后的430 tests/36 suites全部通过，原419测试身份保留；5份修改源码已精确接回，141份源码及224个生产class与冻结构建对应。真实保存二来源2445个完整原生行Part从0恢复至2445；HOMES17/flushing16焦点行cells逐字恢复，源正文/hash/owner/角色/UTF16不变，1895个chunk IDs全部更新。原生结构歧义保留unknown，普通首行不冒充表头。新索引和后续材料投影仍需下一阶段验证。

三语Word/PDF报告正文改为证据定位，说明定位不确认结论；保留工作状态及人工记录，未作布局/语义验收。44原件SHA重新实算通过，OCR147页仍只是历史处理范围。已确认本任务Bonsai23 start/23 done后停止PID8708并保留恢复配方，观测GPU7613→1147MiB。没有新增审查LLM、原件解析、OCR、embedding/index/query调用；软件回归使用隔离fixtures。Java/UI/旧DB/index未替换。App基础Goal保持active，下一阶段为selected-only材料结构、token预算/续段、索引失效及分层召回覆盖，全部发现与验收见 [基础链路汇总](vetting-foundation-findings-20261003.md)。


## 基础Goal第二阶段：结构材料、续段、OCR质量状态与固定库预建

合并461 tests/39 suites全部通过，原430测试身份保留+31；146份源码、228生产class与冻结JAR核对，14份tested源码已接回。selected-only结构材料已保留33原生焦点行，未入选header剥离；原source正文与UTF16保持。真实document72的Note1头段诊断补尾部最低要求范围，不算actualqueryrecall。OCR处理成功仍为needs_review/PARTIAL；FT2/APL3两页×两配方四次真实CPU识别，det tensor相同、raw结果相同，编号错误未改善。固定六源按显式原件SHA登记，GCC/SL旧OCR页派生待核状态；不会靠模板名改业务角色。用户确认NTT/SCT/SCC模板+GCT/GCC/SL固定，其他现场材料变化，并允许提前解析切片嵌入形成超算离线包。新增源增删改/混版/项目隔离与未知长段/表格验收，缓存身份绑定算法、模型/tokenizer、dtype和原文，parentIDs重绑定。

当前没有新审查LLM、embedding/rerank/index/query调用，Java/UI/旧DB/index尚未替换。App Goal保持active，接着将质量声明传到chunk/材料，冻结token窗口和实际向量配方，做fresh-client持久核对和层级召回，构建固定六源资源包。细节与不可变SHA见 [基础链路汇总](vetting-foundation-findings-20261003.md)。


## 基础Goal第三阶段：质量声明、人工revision与实际GPU窗口

487 tests/41 suites全通过，9份精确源码接回，149全src/230classes与冻结JAR对应；原461测试源码逐字保持。Chunk/材料保留OCR needs_review及scopeunknown，质量hash参与身份。44源13464父chunks已纯文件冻结，文字准确度全仍unverified。同引文内容变化跨轮生成新Open，原人工记录/history保留；37相关回归与33独立peer字段检查通过。

两源真实CUDA float32/TF32off主index1895parents→1941windows，69.492秒，4query为两原通用查询×50/10及100/20。主embedding486、query embedding4、rerank78 actualforward start/complete全部对应；额外小增删改/项目隔离fixture不混主分母。producer关闭后独立fresh client复制state并1constructor/8scroll/1close，1941完整payload/source spans/原encodedfloat32逐元素exact。这是运输和持久化证明，尚未验收整44的必要条件召回或最终合同语义。

CJK lexical旧中文=[]修复77guards通过，64英文queries tokens逐字同；固定6向量预建及整44将使用新算法身份。固定6 whitelist按6原件/sourceHash/role显式绑定，0新审查LLM/HTTP/AppDB/download；Java/UI/旧索引未替换。固定向量cache/整包实际ranking+生产context回放/跨目录离线资源包仍执行中，H800/Linux运行未知。不可变证据见 [基础链路第三阶段](vetting-foundation-findings-20261003.md)。


## 基础Goal第四阶段：固定六源资源包与全44真实回放

固定六源真实10705父chunks→10724窗口已预建；全44真实13464父chunks→13602窗口，10724固定缓存命中、2878新编码，一次主index、128实际query，两配方50/10与100/20、原64请求保持。独立新客户端逐元素核对全部缓存/新编码向量及payload，mismatch0。2281实际tokenizer-input探针丢失token为0；生产Budget/SourceMaterial纯文件回放128保存query→32材料组。

固定ZIP 5215966017 bytes已制作，第二随机根解压验证21575清单文件、fresh文档/父ID及10724保存向量全部命中、byte mismatch0。Linux wheelhouse/H800尚未验证，固定GCC/SL13个OCR页仍needs_review。当前全44旧预算材料在原Q2/Q6仍缺独立标注条件；两源预算反例已确认扩展补原缺口却被新增引用一票否决，正在通用修复。全44wall/index发生大时间gap，不能作为纯速度比较。health旧v2标签/default workspace两个metadata问题另行修复；当前Java/UI未重启，0新审查LLM/HTTP/AppDB，Goal active、foundationAccepted未知。不可变证据见[第四阶段](vetting-foundation-findings-20261003.md)。


## 基础Goal第五阶段：固定缓存身份、扩展判定与单因素材料回放

Root Java已接回500 tests/42 suites版本，保留原487测试，扩展按首轮实际缺口判断改善。metadata-v2 core/launcher经171隔离guard接回；新六源10724窗口真实重编码，新44源13464parents→13602windows、fixedcache10724/fresh2878、一主index/64原primary queries完成。fresh客户端全float32及源payload mismatch0，新旧六源10724向量exact，64primary各层排名完整payload一致。

三个独立Java实验保持未接回：实体限定504 tests、单调扩展515、incoming v2 517。各用相同真实保存排名做两源/44源回放，独立按原件block/UTF16审计；单调扩展令两源Q6条件完整送达，44源相关Q2/Q6范围仍缺，不能以missing总数下降代替覆盖。incoming构造后可变源反例已复现并拒绝修复，初始v1失败不删除。Root producer工厂最初因STRATEGY内联消费者class差异拒绝，随后独验源码相同且仅常量池UTF8版本替换，再重新冻结helper并回放；没有放宽完整JAR或来源校验。

当前三修订及incoming预算保护正在隔离合并，新版固定ZIP另建，原v3保留。PRE.B8.210大引用family是atomic拒绝且未消耗正文预算，原贪心partial假设已被只读审计否证，下一步以实际ranked-core/邻域/closure占用判断预算分配。0新审查LLM/HTTP/AppDB/OCR；旧实例未重启，H800/Linux未验收，Goal active、foundationAccepted未知。证据入口见[第五阶段](vetting-foundation-findings-20261003.md)。


## 固定六源离线交付 v4 完成

已生成5217042748-byte ZIP v4（SHA cb5f1f57d052c3d001e436dc7bb0502d5a027fb27860054613f5bf5be2abb0b5），包含最终metadata-v2与真实重编码fixed6缓存。第二随机根21576清单文件全部SHA一致，10705新父ID与旧不交、10724保存窗口缓存全部命中，原实际float32 NPY逐字节差异0；模型/算法内容身份不依赖目录，两绝对根一致。Root独立stream核ZIP SHA通过，v3原件/证明保留。

打包0新parser/OCR/tokenizer/neural/index/query/HTTP/AppDB，尚未上传平台或执行Linux/H800。Linux wheelhouse待补、固定13个OCR页needs_review。546-test三修订及incoming Budget仅隔离冻结，原失败保留，有限peer与实际保存材料评价仍在推进；Root当前500、Goal active。包与操作说明见[第五阶段固定离线交付](vetting-foundation-findings-20261003.md)。


## 546合并版独立反证与预算损失

546/46-suite源码及234-class JAR冻结，三套真实保存排名4+32+16材料组全部回放并独立审计；两源Q6两配方SCC6/8范围送达，44源Q2/Q6四必要范围仍缺。独立泛化反例证实：初始incoming-derived ranked tail原core未准入，扩窗却晋升outgoing origin并追第二跳。546不接回，修另版；此前有限静审无发现不等于验收。Q2 weak邻域填充先于observed qualifier造成可归因预算排除，大literal family 60801chars atomic拒绝/0 of49未消耗正文预算；Q6 preweak已近上限，优先级并非全解。

独立evaluator两个并行进程因Windows UTC时间字符串相同造成stage mkdir拒绝，失败单独保留；补UUID并在全新目录重做metadata-primary文件审计通过，未改首个已成功输出。本追加0新审查LLM/neural/query/index/HTTP/AppDB，Goal active，Root500与固定ZIPv4证据保持。

2026-10-03 固定与变化资源边界追加：用户确认固定六类原件可赛前解析/切片/嵌入带入超算；v4包搬移读回完成，现场变化材料须自身来源/项目重建、当次rerank。549隔离冻结修复派生origin，未promote；真实Q2 Note1预算阻塞证据已入基础发现文档。本追加0新review LLM/parser/OCR/tokenizer/neural/index/query/HTTP/AppDB；Goal active。

2026-10-03 最新检查点：Root556/49 suites实际package通过，234classes与隔离冻结/JAR逐字一致，12文件接回但旧实例未deploy；未知变化材料原件parse/tokenizer预检完成并发现长cell拆Part后关系unknown；574额外包原型仍隔离待saved-rank范围评价。0新真实审查LLM，Goal active，完整基础验收未通过。
