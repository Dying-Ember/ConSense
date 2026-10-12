# OER 部署研究：当前代码、模型与运行证据核查

核查日期：2026-10-12，Asia/Shanghai。范围是只读检查仓库代码、当前规则、handoff 和已有冻结运行收据；仅新增本文，没有启动或停止服务、读取 GPU、加载模型、调用 API、运行测试或修改运行配置。仓库 HEAD 为 `285fc2e3bdf97792aeae00a31dbde2c69ebcde60`；工作区包含实施增量，HEAD 本身不代表本文检查到的全部源文件已发布。

**用户最新前提：OCR 与 LLM 不同时使用，可以分阶段卸载。** 因此 OCR＋LLM 同时常驻不是本轮需要重新讨论的业务问题。工程研究重点是阶段之间真正释放／恢复、连续问答时小检索模型的策略、RAM 缓存与冷／暖加载成本，以及最后的 K8s/H800 验收。下文“尚未实现／未验证”是工程状态，不要求用户重答已定业务规则。

## 结论

1. 单张 H800 80GB＋K8s 是已确认部署目标，尚无目标镜像、K8s manifest 或集群部署。独立 Linux MinerU full 已取得真实两页完成证据，但它不等于最终发布镜像或 H800 验收。
2. 比赛约 120B MoE 的实际模型、量化、引擎仍未在现有规则中确认；GPT-OSS-120B MXFP4 与 Qwen3.5-122B-A10B 是已有规划候选。现有 Java LLM profile 只有 `local`／`minimax-cn`；`vetting-h800` 配置设置输入预算，没有配置或启用 120B 推理后端。
3. 当前正式 OER 检索入口是 **CUDA FP32、short 512／overlap64、structure 2048、batch 默认 2、embedding/reranker 串行释放**。不能把未来 FP16／BF16 节约预期当成当前实际配方。
4. 已实现的 `serial offload` 只处理同一 Python 进程内的 BGE embedding 与 reranker。它删除另一模型对象，后续从本地 snapshot 重新构造；没有保留显式 CPU 模型对象或实现大 LLM 的 RAM 热缓存，也没有跨进程／跨 Pod 的 GPU 所有权协议。
5. 实际两次 query 的 journal 证明反复释放和重建确实发生。这个行为在 16GB 开发机上有明确用途，但未来 H800 若小检索模型能够通过峰值准入，可以研究保留驻留，以减少每次问答重新加载；这需要新配方、质量／资源验收，不能直接关闭当前守卫。
6. 解析基线、来源质量、固定库、业务验收、K8s/H800 均须分别报告。最新 handoff 已有 18 家族正常完整基线，但固定全所需来源最终解析门仍未过，当前执行仍以原件质量为先。

## 证据入口及最新状态

| 事实 | 已读证据 | 可支持的结论与边界 |
| --- | --- | --- |
| 已确认优先顺序 | `docs/oer-scope-and-rules.md:9–17` | 各类正常基线→渐进提高解析→固定资料库→项目／业务→H800；部署研究不等于开始部署 |
| H800、K8s、存储约束 | `docs/oer-scope-and-rules.md:15–17` | 目标已定；镜像／manifest／集群版本、runtime、PVC、device plugin 尚未实核 |
| 正式设备与质量规则 | `docs/oer-scope-and-rules.md:59–75` | 主 VLM／embedding／rerank CUDA；辅助按官方与实际证据分工；不能静默转 CPU、截断或改分辨率 |
| 当前解析进度 | `docs/HANDOFF-ocr-embedding-rerank-20261008.md:5–21` | GCC 结构仍有残差，FWP 新训练权重未采用，SL 整图表主体优先；固定库／业务／H800门未过 |
| 大模型资源与实际身份 | `docs/oer-scope-and-rules.md:267–269` | 用户报告 1×H800 80GB、27vCPU、250GB RAM、650GB 高性能＋1250GB 通用存储；实际约120B身份未确认 |
| 本地调试、冻结、扫描发布 | `docs/oer-scope-and-rules.md:71–75` | Windows/A4000通过不追认Linux/H800；平台不作为现场补码或默认训练环境 |

路径除明确标注外均相对 `ai_consense_service/`。handoff 的历史记录保留当时身份，顶部最新进度优先。

## LLM 配置：预算档与推理后端是两件事

| 检查项 | 源码／配置证据 | 当前结果 |
| --- | --- | --- |
| Profile 列表 | `src/main/java/com/consense/ai/LlmProfiles.java:19–24,43–46` | 只有 `local` 与 `minimax-cn`，默认 local |
| 本地默认 | `src/main/resources/application.yml:58–68` | 默认 Ollama，`qwen2.5:7b`；这只是默认配置，不证明当前运行实例正在使用它 |
| Java 类默认差异 | `src/main/java/com/consense/config/ConsenseProperties.java:53–55` | 类默认 `qwen2.5:14b`；正常绑定 application.yml 时由资源配置覆盖，不能混作当前模型身份 |
| MiniMax 开发对照 | `src/main/resources/application.yml:69–79` | `MiniMax-M3`，OpenAI 兼容供应商接口；未输出密钥或当前环境值 |
| H800 budget profile | `src/main/resources/application-vetting-h800.yml:1–17` | total 131072、output 16384、safety 16384，故政策输入边界98304；`responses.enabled=false` |
| 120B候选是规划身份 | `docs/vetting-h800-moe-plan-20261004.md:9,17–23,164–166` | GPT-OSS/Qwen候选不表示赛方实际选择或本项目已部署；MiniMax不作为额外比赛算力假设 |
| 本地 Bonsai | `docs/HANDOFF-ocr-embedding-rerank-20261008.md:1588`、`docs/oer-scope-and-rules.md:67` | 用户要求保持抑制，历史停止证据存在；本轮未查询实时进程或重新加载 |

`LlmProfiles.java:31–32` 将身份标为 `configured_profile`，`metadata()` 表达的是可配置性，不能当推理成功或真实权重证明。冻结时需要保存实际 checkpoint、量化格式、tokenizer、engine、完整上下文预算及加载证据。

现有 H800 设计的 98304 是含额外安全余量的工程政策，不是 H800 硬件容量上限。`docs/vetting-h800-moe-plan-20261004.md:62` 已记录108855输入＋16384输出仍低于131072的例子；供应商计数没有目标 tokenizer 精确性和显存证明。研究新的 LLM 候选时，不应把这个旧预算自动套成所有模型通用上限。

赛方模型能否替换：已读档案 `docs/archive/team-update-20261004/competition-and-authority.md:8–9,27–33` 只支持用户给出的硬件、不同项目输入以及初赛proposal技术选型不是最终承诺。该档案未给出当前赛方模型替换政策。现有中央规则仍将赛方模型身份记为未知；这不是已经证明不能替换。后续优先查实际平台资料与已授权的赛方文档，不让用户猜可查事实。

## 当前 BGE 真实身份、大小与精度

模型 pin 在 `tools/vetting_eval/samples.json:3–4`：

| 模型 | 固定 revision | 已有本地资产与参数事实 |
| --- | --- | --- |
| `BAAI/bge-m3` | `5617a9f61b028005a4858fdac845db406aefb181` | 本地 `config.json`：XLMRobertaModel，24层、hidden1024、16heads、FFN4096、vocab250002、position8194、`torch_dtype=float32`；`pytorch_model.bin` 2,271,145,830 bytes |
| `BAAI/bge-reranker-v2-m3` | `953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e` | 本地配置为同尺度 XLMRobertaForSequenceClassification；只读取 safetensors JSON header，393 tensors全部F32，567,755,777参数元素，payload 2,271,023,108 bytes；文件2,271,071,852 bytes |

这些资产来自根目录 `.local/state/oer-implementation-20261009/bge-model-files.json` 的固定模型清单；本轮读取配置及 safetensors header，没有反序列化模型对象或读取 tensor payload。下载清单的 `downloaded_and_byte_verified_no_inference` 是当时资产状态，不冒充推理。实际加载已另有 journal。

Embedding 参数按标准 XLM-R model 的已读配置推导约567,754,752，计算为 embedding264,395,776＋24×encoder12,596,224＋pooler1,049,600；这是架构推导，未加载模型逐 tensor计数。FP32 payload约2.115GiB；reranker已实读header也约2.115GiB，两者仅参数payload约4.230GiB。运行还需要 activation、attention、临时张量、allocator、CUDA context和框架工作区；这些不能由参数量代替，也不能按磁盘文件大小直接推断峰值。

当前配方由 `tools/vetting_eval/oer_server.py:63–86,146–155` 强制：CUDA／FP32、serial offload开启、short512／overlap64、长2048、batch默认2、offline。`tools/vetting_eval/oer_unified_server.py:60–68` 复用该配置，`src/main/resources/application-oer.yml:5–15` 在Java消费端核同一窗口身份。基础 `server.py:30–32` 虽支持float16，但正式入口拒绝不同dtype；未来低精度是单独实验，不是当前状态。

### 真实 CUDA 检索收据

根目录 `.local/state/oer-gct-cuda-retrieval-20261010/root-runtime-prep-01/run-080342-ebb0af/actual-neural-01/` 的 `neural-final-handles.json` 本轮重新计算 SHA 为 `bd4639b5b3a748d6211c08d59918d512ed328781e07b25e468be91dbd7a2f8de`，与handoff一致。`execution-receipt.json:161–212` 保存实际recipe与整卡采样：

| 已观测数值 | 实际分母／口径 | 能支持什么 |
| --- | --- | --- |
| 9.971／4.844／4.580秒 | 一次GCT两页index与两次retrieve HTTP wall；handoff:1253 | 完整小作业外层时间，含非神经成本；没有H800或LLM调用 |
| CUDA／FP32、batch2、short512／long2048 | 实际模型加载journal及recipe | 当前真实精度，不能改称FP16 |
| 4641 MiB | 整卡约0.5秒采样最高值，含其他进程baseline；before2123／after4641 MiB | 共享整卡观测，非独占峰值；差值2518 MiB也不是BGE严格独占峰值 |
| 56 events／110输入行、max507tokens、无截断 | handoff:1253及绑定journal | 此两页开发输入；不能证明2048最大batch或完整SL规模峰值 |

本次读到的 BGE 证据没有明确 reset 的 owned `max_memory_allocated/max_memory_reserved` 峰值。已有offload事件提供释放前后allocator快照，快照不能称推理峰值；下表据原始events逐项列出。

### 每次 query 的重复加载已经发生

`tools/vetting_eval/server.py:43–62` 对另一模型执行：synchronize→清全局引用→del→gc→empty_cache。后续 `embedding_model():245–260`、`rerank_model():263–274` 调用本地 snapshot 的 SentenceTransformer/CrossEncoder构造器。这里没有 `.to('cpu')` 后保留模型对象，也没有显式RAM兼容模型副本；OS page cache的可能复用不等于已实现模型热缓存。

三份 journal 位于根目录 `.local/state/oer-gct-cuda-retrieval-20261010/root-runtime-prep-01/run-080342-ebb0af/retrieval-state/run-evidence/`：

| 请求目录／event ordinal | 真实事件 | allocator快照bytes或事件时点 |
| --- | --- | --- |
| `20261010T081459.600213Z-a90189cf03d44bc5bd9d6f1ab7a43176`／4 | index加载embedding | 请求elapsed9.080064秒；不是独立load timer |
| `20261010T081509.766984Z-05a33244b90446cbbfe0b29a87f0ced5`／9→10 | query1释放embedding→加载reranker | before allocated2,280,719,360／reserved2,355,101,696；after9,568,256／23,068,672；load事件elapsed4.294943秒 |
| `20261010T081514.809299Z-8aa0825ce9be4941918dd6e2b2391eb4`／1→2 | query2释放reranker→重新加载embedding | before2,280,863,232／2,371,878,912；after9,568,256／23,068,672；两事件elapsed0.283650→2.042053秒 |
| 同query2／10→11 | query2再释放embedding→重新加载reranker | before2,280,858,624／2,294,284,288；after9,568,256／23,068,672；两事件elapsed2.345255→3.915869秒 |

query2两个相邻事件差约1.758和1.571秒，包含构造、文件／tokenizer与记录边界；没有更细分的load计时，不宣称纯H2D时间。连续问答因此存在每次重新构造两小模型的实证成本，不能把“串行”理解成免费切换。当前内部RLock在 `server.py:103` 只约束此进程；多个进程或Pod不能靠它获得整卡串行所有权。

## OCR：模型大小与整链峰值不同

### 真实原PDF BF16／FP32对照

`docs/testing/original-pdf-dual-parser-bf16-validation-20261009.md:37–65,87–113` 记录23原始风险页／14文件及每路固定4页FP32对照。两路都completed并退出，质量仍有共同缺陷，不能当全来源质量通过。

| 指标／当时配方 | Paddle native mixed BF16 | MinerU Windows raw VLM BF16 |
| --- | ---: | ---: |
| 实际参数payload | 2,901,046,528 bytes；491,934,528 FP32＋466,654,208 BF16元素 | 2,312,053,248 bytes；1,156,026,624 BF16元素 |
| allocator allocated high-water | 6,576,616,192 bytes | 12,915,709,952 bytes |
| allocator reserved high-water | 16,083,767,552 bytes | 42,517,659,648 bytes |
| 整卡共享采样最高 | 15,963 MiB | 16,004 MiB |
| parser RSS high-water | 4,359,827,456 bytes | 30,965,235,712 bytes |

这些是不同记账口径，不相加。Windows reserved超过物理卡容量不能解释为H800实际需要42.5GB，也不能据BF16参数约2.3GB宣称整parser只需2.3GB。MinerU固定SL大表的allocated峰值BF16／FP32为12,915,709,952／13,373,454,336 bytes，说明参数减半没有使大表完整峰值减半。Paddle视觉／projector保持vendor FP32，参数字节只比全FP32减少24.34%，不能强行改全BF16或把其收益套给其他模型。

本轮原Paddle23页receipt重新计算SHA为 `7ae78a142a2ab712e1b2f7011bb3940f17cdd51ed07f278ffb5efc67914554ab`，文件位于根 `.local/state/original-pdf-dual-validation-20261009/paddle-bf16-run2/receipt.json`。本轮没有重跑该实验。

### 已成功的 Linux MinerU full 是另一配方

`docs/HANDOFF-ocr-embedding-rerank-20261008.md:1199–1203` 与根 `.local/state/oer-mineru-full-linux-20261010/candidate-01/worker-vllm0191-pypdf6190/async-full-worker-03/actual-gct-two-pages-01/actual-execution-summary.json` 保存官方 `AsyncVlmPredictor→AsyncLLM→EngineCore` 真实完整GCT两页。后者本轮重新计算SHA为 `8af16619d9a4d48f78a83a7e8021dcce683f3705e0abfe514e0eeb1c6f978d98`。

| 项目 | 实际值／边界 |
| --- | --- |
| 配方 | `official-full-native-async-vllm0191-fp16-8k-forms-session-v3-safe-rpc`；Torch2.10.0+cu128；vLLM0.19.1；CUDA12.8 |
| 主模型 | 1,156,026,624参数；561项参数全部CUDA FP16，非BF16 |
| 预算 | context8192／maxTokens8192，maxConcurrency1，tensorParallel1，eager；KV显式536,870,912 bytes，gpuMemoryUtilization0.45 |
| EngineCore allocator峰值 | allocated3,350,478,848／reserved4,217,372,672 bytes；是该enginecore进程scope，不是整parser各进程或整卡峰值 |
| 调用／终态 | 38/38 stop，prompt5296／completion1135，无length截断；worker completed |
| 辅助 | Torch layout／detector实际CUDA FP32；tableDevice=cpu配置但ONNX表格资产此轮未执行，不能追认表格分支运行或质量 |
| 时间 | official infer108.415秒；launcher704.600／worker660.439秒含2.211GB profiler及保全，非正式吞吐benchmark |
| 验收 | `execution_completed_quality_residuals_unaccepted`；训练0，canonical adoption=false，production index write=false |

旧H800设计 `docs/vetting-h800-moe-plan-20261004.md:189–190` 的整批Paddle mixed BF16／MinerU BF16是规划表；真实Linux full成功路线是FP16，需要将目标环境的dtype、provider、内核及质量绑定到实际选定配方。不能仅从“full已经完成”追认旧BF16表已实现。这个差异不要求现在换精度或重跑GPU。

## 分阶段卸载与 K8s 实现的差距

| 已定／已有设计 | 已实现到哪里 | 还需工程验收 |
| --- | --- | --- |
| OCR和LLM分开使用 | 两parser已有串行作业与退出收据；中央规则采用阶段调度 | 实际LLM owner受控drain／sleep／wake、OOM恢复、释放后总device余量、下一阶段ready |
| LLM释放VRAM后RAM暖缓存 | `docs/vetting-h800-moe-plan-20261004.md:164–166,200–215` 记录目标 | 量化表示的sleep／wake或缓存实现、host峰值与重复切换；无现成部署通过证据 |
| 每正常导入批只恢复一次LLM | 设计表181–196；不是代码调度器 | 新导入批处理、已知query取证缓存身份、异常批次恢复、对话重新prefill |
| Advisory连续query使用CUDA | 当前BGE FP32串行释放已实跑 | 大LLM热驻留期间BGE最大query／pair／batch峰值准入，或严格阶段队列；OCR与LLM不并发已定 |
| K8s单卡 | 仅中央规则确认目标 | device plugin、runtime、资源owner与GPU释放不是Pod删建即可证明；本轮git tracked清单未发现Dockerfile／compose／k8s／deployment／helm文件 |
| Qdrant Local | `server.py:233,289–291` 声明单worker、`QdrantClient(path=STATE/qdrant)` | 单进程／单写者PVC、同卷副本保护；未来服务式Qdrant是单独迁移验收 |

常规 `nvidia.com/gpu:1` 不替每个Pod划出显存额度；默认整卡资源申请下若两个GPU Pod都请求1，第二个可能无法调度。即使启用共享插件也没有自动证明峰值或显存隔离。必须围绕唯一GPU阶段所有者设计，CPU编排／存储服务可持续运行，GPU模型阶段按已定先后交接。中央规则 `docs/oer-scope-and-rules.md:15–17` 已明确该边界，本轮没有指定未经验证的集群实现。

## 既有验收不能互相替代

| 验收轴 | 当前证据 | 仍未通过的门 |
| --- | --- | --- |
| 代码／契约 | formal入口、来源／窗口／serial-offload守卫可读；大量历史测试由各冻结收据承接 | 本轮没有执行测试；部署调度和目标镜像没有验收 |
| 真实模型调用 | 原PDF双parser；Linuxfull两页；CUDA FP32 BGE一index两query | 完整目标环境最大输入、并发、LLM与检索阶段峰值及切换 |
| 原件质量 | 最新18家族基线；GCC／FWP／SL仍具体残差 | 完整所需固定范围最终解析门、独立gold与旧正确项回归 |
| 检索／业务 | 两页开发检索实跑，显式margin关系闭合曾失败；历史官方Q1.6失败有记录 | 全固定库、真实官方题／Vetting／前端业务；不能用HTTP200或index-ready替代 |
| K8s／H800 | 已确认目标与独立Linuxfull成功路线 | 目标image／锁／manifest／扫描／发布／集群／cold-start／恢复／真实显存与推理 |

建议将部署研究后续工作锁在可复核的工程表：每个阶段记录owner、实际model／dtype／tokens／batch、参数bytes、allocated／reserved、整卡峰值、host峰值、退出后baseline、恢复时间／TTFT及质量门。参数payload与旧小样本数字只作为规划输入；当前不为了部署研究启动新服务、改精度或打断原件质量作业。
