# 依据任务质量与 256K／512K 证据选择 LLM

日期：2026-10-12，Asia/Shanghai。状态：已完成公开评测及保存运行调查，尚未进行新候选模型实验或 H800 验收。最新要求见 [规则入口](./oer-scope-and-rules.md)。本文覆盖先前基于容量提出的候选优先序；原模型、原评分及失败继续保留。

## 1. 研究结论与已经改变的选择

**最新三模块联合口径见 [RAG初筛后的选型补充](./llm-three-module-rag-selection-20261012.md)。** 用户明确Vetting与Advisory先经RAG初筛；本页抽取分数主要支持Drafting，AA-LCR等才提供给定证据推理的代理信号，专业agent分数仅作辅助，不能把一项成绩当三模块采用结论。当前27B FP8第一顺位建议针对三模块共用验证，Vetting完整审查、Advisory引用／缺证和真实检索质量分别保留未验门。

**本机 Bonsai 的已测端到端配方尚未达标，完整256K／512K与H800条件下的能力未验。** 用户最新指出显存和上下文可能影响效果；保存第五轮实际配置为64K，历史输入分片也构成混淆因素。保留该配方FAIL，不将它升级为Bonsai底座能力否定，也不由官方FP8公开高分追认本机通过。新小模型能胜旧大模型，有具体任务表支持；当前选择必须同时看值与对象、完整清单、条件／例外、原文引用、unknown和输出协议，最后才核窗口与单卡部署。具体归因和更大模型路线见 [最新补充研究](./llm-h800-larger-model-and-context-strategy-20261012.md)。

至少 256K 是主模型准入要求，512K 为优选。GPT-OSS120、GLM-4.7-Flash、Qwen2.5-72B 退出完整主模型候选；较大的参数数目不能补回不足的窗口。M3 官方 API 保留为实际 Drafting 质量参照；不由单卡容量否定这条效果证据，也不把 API 参考自动变成比赛部署路线。

当前建议保留以下有限选择，而不是展开所有模型与精度组合：

- **质量参照：M3 官方 API 的已保存运行。** 其同题第五轮有值 58/58、留空 21/21，0 FAIL 且 REVIEW 已源核；较晚另一次仍有四项候选残差，不能宣称所有任务恒定满分。
- **本地第一顺位验证：Qwen3.8-27B 官方 FP8。** 已有独立长文推理和benchmark作者FP8抽取证据；以完整必要输入和准确长窗配方核任务质量，再与Bonsai受控比较精度、模板／final协议及上下文因素。未通过前不叫已采用替代方案，不能只给Bonsai加思考来磨题。
- **更大质量挑战者：Qwen3.8 Flash-Next。** 有同代专业任务提升信号及官方CPU lookup offload；剩余FP8主干仍超过单卡，需Hopper兼容量化／混合驻留，且公开长文抽取没有全面提升。详见 [具体候选与缺口](./research/llm-h800-larger-model-candidates-20261012.md)。
- **本地 MoE 对照：Qwen3.6-35B-A3B 官方 FP8。** 具备原生 256K、官方 512K 配方及实际 FP8 抽取成绩；长文推理与完整抽取并不领先当前 3.8-27B，不由显存或 MoE 标签提升为质量首选。
- **独立家族对照：Gemma4-31B。** 在 OCR 转写文本的结构抽取上有不同优势，具备原生 256K；目前没有核得官方 512K 路线，512K 优先时不能取代前两条。26B 的 ExtractBench 成绩不能冒名给 31B。
- **长窗专项：Nemotron 3 Nano／Super。** 有 512K／1M 合成长窗分数，Nano 的真实长文推理明显较弱，Super 的 H800 量化路线未闭；保留针对性对照，不作为默认业务主模型。
- **小模型能力对照：Qwen3.5-9B。** 用已有同表的指令／长文成绩检验有界关系分类、typed extraction 和缺证处理；不因 9B 自动排除难任务，也没有当前 ConSense 成绩支持它接管全任务。本轮没有查证公开 Qwen3.8-9B／35B 包，不编造后缀或继承分数。

这是一份可以据证据执行的候选筛选决定，没有足够证据将任一未测本地候选宣布为已达到 M3 质量的最终赢家。实际采用门仍在原件解析与固定库质量之后。

## 2. 本机经验比泛化平均分更直接

本机作者卡明确 `base_model=Qwen/Qwen3.8-27B`，制品是 `Ternary-Bonsai-2-27B-PTQ1_0.gguf`，不是旧 Qwen3.6。身份、固定 revision、小证据 SHA、逐类失败详见 [Bonsai 本机证据](./research/bonsai-local-task-evidence-20261012.md)。

| 同题第五轮记录 | 有值项正确候选 | 正确留空 | PASS／REVIEW／FAIL | 对选型的实际意义 |
| --- | ---: | ---: | --- | --- |
| Bonsai 原始实际完整任务 | 51/58 | 21/21 | 31／41／7 | 当前配方不足；未重新评分为通过 |
| 固定 Bonsai 原回答，经 `.27` 规则离线重放 | 56/58 | 21/21 | 33／44／2 | 修接收／来源规则能恢复部分；不是新模型运行 |
| M3 保存完整任务 | 58/58 | 21/21 | 42／37／0 | 37 REVIEW 已独立源核，支持质量参照 |

来源：[原始完整考试](../../docs/drafting-harness-rounds-4-5-20261009.md)、[M3 同题审计](./testing/m3-round5-20261009/README.md)。58 与 21 为两个不同分母，不能把 REVIEW 当错值或自动 PASS。Bonsai 原运行另有 53/58 的语义人工口径，不替换 strict 的 51/58。固定题已参与开发，M3 与 Bonsai 的消息／harness 调度并非完全相同；这是完整工作流经验，不是受控的纯权重实验。

两个残差明确：Bills 泄漏思考及畸形前缀导致 decoder 选到较早嵌套数组，之后补查仍遗漏；Sections 初次漏 required `designation`，修复后引文不连续。这些同时涉及模型输出和系统接收，不能统统归因于压缩，也不能靠提高窗口就认为会消失。

最新 [逐请求上下文核查](./research/bonsai-local-context-capacity-attribution-20261012.md)确认第五轮primary均包含完整短part，28次引擎生成的truncated均为0，slot停止计数最高9375；完整API usage／finish_reason未保存。这支持“未观察到这两项短请求耗尽64K”，不补造完整输入token数或所有历史硬件正常。另一组18项防水清单确实分入前16／后7项两个输入片段；单纯扩服务窗口不会自动改变冻结harness的排包。

另一组本机调参中，32K/off/2K 得 58/61；64K/medium/16K 得 60/61，但时长从 10.99 分钟增至 65.18 分钟且仍 FAIL；64K/off/16K 仍 58/61。来源：[既有调参](../../docs/drafting-model-benchmark-results.md)。分母与第五轮不同，不合计。这些短窗实验未验证 256K／512K，却已说明继续加思考不是已经证实的解决办法。

M3 的较晚已采用项目运行仍有 57/61 本轮正确有引用候选，残差包含无主语空列表引用、正确值但对象／数组协议错误、常规要求误当额外提交及 Sections 补查预算耗尽。65 人工确认／编辑字段不能计为模型命中。详见 [两次 M3 当前证据](./research/llm-task-baseline-current-evidence-20261012.md)。用户认可的 Drafting 效果与这些具体边界同时成立。

## 3. 四类任务对应哪些真实评测

| ConSense 任务 | 优先公开证据 | 必须单独判断的项目责任 |
| --- | --- | --- |
| Drafting 值提取与清单 | ExtractBench 长列表／跨页表格／法律切片、SOB 值与完整响应、IFBench | 原值和对象配对、否定／unknown、全部项找齐、连续引文；最终 DOCX 仍由确认值和程序编译 |
| Drafting 候选关系分类 | 指令遵从、typed 输出、长文推理；目前无完全对应的公开项目榜 | supplement／explicit_replacement／contradiction／undetermined，不能用日期猜采用关系 |
| Vetting 跨文件审查 | AA-LCR v1.1、GDP.pdf、LongBench v2；Harvey LAB-AA 方法作法律工作流参考 | AND／OR、例外、优先级、不同对象、真冲突与缺资料、漏报／误报、完整 finding 与证据 |
| Advisory 有据回答 | AA-LCR、缺证与错答校准、多轮指令／工具评测 | 当前来源与项目采用、回答针对性、引用支持、缺证澄清；闭卷知识分不能当 grounded 正确率 |

LLM 不负责重新发明哈希身份、权限、精确来源资格或确定性模板执行。首版原图返回也不自动扩展为视觉解释。任务与源码边界见 [原任务基线](./llm-task-evaluation-baseline-20261012.md)。SWE、Terminal、数学考试不主导这些文字业务任务的选型。

## 4. 当前外部任务结果：按版本、模式与指标比较

### 4.1 独立长文推理与完整专业交付

下表是 Artificial Analysis 当前比较页的分任务分数，AA-LCR **v1.1** 与 GDP.pdf **All-pass** 分开，均不是本项目准确率。

| 模型／模式 | AA-LCR v1.1 | GDP.pdf All-pass | 来源 |
| --- | ---: | ---: | --- |
| Qwen3.8-27B Xhigh | 82% | 17% | [与 3.6-35B 同页](https://artificialanalysis.ai/models/comparisons/qwen3-8-27b-vs-qwen3-6-35b-a3b) |
| Qwen3.8-27B non-reasoning | 69% | 5% | [与 Xhigh 同页](https://artificialanalysis.ai/models/comparisons/qwen3-8-27b-non-reasoning-vs-qwen3-8-27b) |
| Qwen3.6-27B reasoning | 77% | 11% | [与 GPT-OSS120 同页](https://artificialanalysis.ai/models/comparisons/qwen3-6-27b-vs-gpt-oss-120b) |
| Qwen3.6-35B-A3B reasoning | 72% | 7% | [与 3.8-27B 同页](https://artificialanalysis.ai/models/comparisons/qwen3-8-27b-vs-qwen3-6-35b-a3b) |
| Gemma4-31B reasoning | 70% | 6% | [与 3.6-35B 同页](https://artificialanalysis.ai/models/comparisons/qwen3-6-35b-a3b-vs-gemma-4-31b) |
| GPT-OSS-120B High | 52% | 4% | [与 3.6-27B 同页](https://artificialanalysis.ai/models/comparisons/qwen3-6-27b-vs-gpt-oss-120b) |
| Nemotron3 Nano reasoning | 38% | 1% | [推理／非推理同页](https://artificialanalysis.ai/models/comparisons/nvidia-nemotron-3-nano-30b-a3b-vs-nvidia-nemotron-3-nano-30b-a3b-reasoning) |

由此可作的判断：较新 27B 在这些任务上超过旧 120B；同一个 3.8-27B 的推理配置也会明显改变结果。不能把 Xhigh 的分数给非推理配方，不能把 API 评测给本机三进制。AA-LCR 的输入约 100K `cl100k_base` tokens，不能验收 256K／512K；GDP.pdf 的 All-pass 比平均子标准通过更严格，17% 也不能叫 17% 字段准确率。方法、精确来源与利益关系见 [外部任务评测报告](./research/llm-independent-task-evaluations-20261012.md)。

Harvey LAB-AA v1.1 包含 120 个私有法律工作流任务与幻觉门；本轮未取得可核验的当前小模型逐行分数，留空，不虚构法律排名。它的完整交付与本项目候选识别也不完全同任务。

### 4.2 结构化抽取：高总分仍然漏长清单

以下来自 ExtractBench 作者仓库固定提交 `c972087fe7a5132132f7230d1bdb841d3fbf07ed` 的 [CSV](https://github.com/run-llama/ExtractBench/blob/c972087fe7a5132132f7230d1bdb841d3fbf07ed/leaderboard.csv)。是按文档平均的规范化 value F1／precision／recall，不是逐字段准确率。Long 指 **超过 50 页**，不是 256K tokens。

| 作者 pipeline | Overall F1 | Short F1 | Long F1 | Long precision | Long recall |
| --- | ---: | ---: | ---: | ---: | ---: |
| Qwen3.8-27B FP8 | 89.75 | 94.68 | 38.45 | 92.29 | 33.00 |
| Qwen3.8 Flash Next FP8 | 89.88 | 94.82 | 37.74 | 88.68 | 33.69 |
| Qwen3.6-35B FP8 | 88.11 | 92.86 | 36.90 | 90.57 | 31.73 |
| Qwen3.5-35B FP8 | 88.00 | 93.52 | 31.73 | 81.07 | 26.83 |
| Qwen3.5-9B | 86.10 | 92.31 | 32.22 | 85.52 | 27.22 |
| Gemma4-26B-A4B | 70.27 | 77.70 | 28.88 | 50.10 | 25.56 |

Qwen 使用自托管 vLLM／`json_object`，身份见 [同提交 pipeline](https://github.com/run-llama/ExtractBench/blob/c972087fe7a5132132f7230d1bdb841d3fbf07ed/docs/pipelines.md)。这是抽取产品作者执行并公开的实验，不能标作中立实验室或 ConSense 复跑。旧论文 v1 用不同 pipeline，Qwen 的旧 Long 26.8 不与当前 38.45 相减当版本提升。Flash-Next官方FP8固定索引为185,502,232,570bytes＝172.7624GiB、131shards，已超过单H800全驻留容量；保留质量参照与offload研究，不将6B active当权重预算。[固定官方索引](https://huggingface.co/Qwen/Qwen3.8-Flash-Next-FP8/resolve/236dfdf285828023ca3bcd3f37366c58a3469b13/model.safetensors.index.json)。

**对本项目最重要的信号是 precision 与 recall 分离。** 3.8 的长文 precision 92.29 与 recall 33.00 可以同时成立：找到的项相对准确，全部项可能严重不全。Flash Next 的更大容量也没在该切片上解决漏项。只检查已返回引用会漏验 Bills、Sections、责任矩阵和完整条款组的缺项。扩大窗口不是公开证实的完整抽取修复。

同一CSV中3.5-9B的长文F1为32.22、3.5-35B为31.73，较大模型在这个切片没有显示优势；差异小且无区间，不能叫显著胜出。9B的pipeline明确为`Qwen3.5-9B`／vLLM／`json_object`，未注明FP8，不借用35B精度标签。它提供真实抽取成绩，而不只是从知识／数学榜推测小模型值得测。公开文档分桶为252短、98中、20长，共370；整体均值受到短资料占比影响，不代表20份长资料同样可靠。[作者数据定义](https://huggingface.co/datasets/llamaindex/ExtractBench)。

### 4.3 新小模型胜旧大模型，源类型又会改变排序

[SOB v1 论文](https://arxiv.org/html/2604.25359v1)为 Interfaze／JigsawStack 作者实验。主设置 schema-in-prompt、temperature 0、max output 2048，可关时关 reasoning；不是统一 constrained decoding。

| 来源／任务切片 | 新小模型 | 值叶节点准确率／完整响应 | 旧大模型 | 值叶节点准确率／完整响应 |
| --- | --- | --- | --- | --- |
| 原生文本 | Qwen3.5-35B | 82.8%／51.9% | Qwen3-235B | 81.1%／48.1% |
| OCR 转写后的文本 | Gemma4-31B | 67.2%／11.3% | Qwen3-235B | 55.2%／7.8% |

OCR 行是同一转写文本上的抽取，不是 OCR 视觉准确率。JSON Pass 不等于值正确：例如原生文本 Qwen3.5-35B JSON Pass 为 97.4%，完整响应仅 51.9%。这与本机 required 键、类别和引文失败的分账方式一致。论文没有 3.8／M3／Qwen9B 分数，不为它们填分。

### 4.4 模型供应商同表可证明方向，不能跨版本拼榜

[Qwen3.5-27B 固定官方卡](https://huggingface.co/Qwen/Qwen3.5-27B/blob/fc05daec18b0a78c049392ed2e771dde82bdf654/README.md)中 27B／旧235B 的 IFEval 是 95.0／87.8，IFBench 76.5／51.7，LongBench v2 60.6／54.8。同卡 35B 的 TAU2 81.2 高于 27B 的79.0，但 IFBench70.2 低于27B，任务排序不同。新版本官方自报不自动是独立验证。

[Qwen3.5-9B 固定官方卡](https://huggingface.co/Qwen/Qwen3.5-9B/blob/c202236235762e1c871ad0ccb60c8ee5ba337b9a/README.md)中 9B／旧80B 的 AA-LCR 是63.0／51.7，BFCLv4 66.1／49.7，IFEval91.5／88.9。它使小模型成为有实质依据的挑战者；不保证本项目全部任务通过。上述 card 未绑定当前 AA-LCR v1.1，不能与 4.1 节合成一个统一榜。

当前 Qwen3.8 官卡 IFBench79.5、同表3.6-27B69.1，提供新版本信号；不借旧3.5数据声称3.8各项提升。完整新旧同表、指标定义、生成预算和当前3.8证据见 [Qwen 任务报告](./research/llm-task-benchmark-qwen-20261012.md)。

## 5. 256K 是准入，512K 要分清支持与有效质量

| 候选 | 已核官方窗口／扩窗 | 长文实际证据 | 选型处理 |
| --- | --- | --- | --- |
| Qwen3.8-27B 官方精度 | 原生262,144；官方YaRN factor2→524,288，最大1,000,000 | AA-LCR约100K；ExtractBench按页 | 256K规格入围，512K配方入围；有效长窗与本机任务未验 |
| Qwen3.6-35B／27B | 原生262,144；官方512K YaRN，最大1,010,000 | 前述独立任务成绩并不等于512K | 35B作MoE对照；旧27B不重新默认推荐 |
| Qwen3.5-9B | 原生262,144，官方512K YaRN | card同表长文分数，非512K逐长度验收 | 有界任务与小模型能力对照 |
| Gemma4-31B | 原生262,144；未核官方512K | MRCRv2八目标128K average66.4% | 达256K规格，512K优选有缺口 |
| Nemotron 3 Nano／Super | 官方支持1M；默认config256K、正式override路线 | RULER256K／512K／1M分长度成绩 | 有更直接长窗证据，任务与H800另准入 |
| MiniMax M3 | 官方1M，config1,048,576 | 项目Drafting正面经验；未核完整模型512K分长度表 | 质量参照，API与单卡自部署分开 |
| Granite4.2-8B／30B | 原生128K，作者声称扩512K；已核launch仍128K | RULER仅64K／128K，完整≥256K配方未核 | 暂不当已满足主模型硬门 |
| GPT-OSS120／GLM-4.7-Flash／Qwen2.5-72B | 131,072／202,752／官方扩至131,072 | 历史任务或容量对照 | 退出完整长窗主模型池 |

逐模型配置 revision、正式引擎参数、DeepSeek V4／GLM5.2／5.3 长窗补充资料见 [上下文证据](./research/llm-context-256k-512k-evidence-20261012.md)。Qwen3.8 与旧卡最大值不同，不能互换。static YaRN 对短输入可能有影响，原生 256K 与扩展 512K 是两套需要分别保存的运行配方。

长窗口的工程预算是输入＋模板／历史／工具＋推理＋最终输出。若示例预留32,768生成、4,096模板，262,144总窗口只剩225,280来源位置；524,288剩487,424。是减法示例，不是新增业务限额。若用户需要256K纯输入，再留输出，原生256K总窗已不够，应按更大配置准入。

NVIDIA 作者自报 Nano RULER-100@256K／512K／1M 为92.9／91.3／86.3，Super RULER为96.30／95.67／91.75；对应自报AA-LCR是35.9／58.31。Nano同卡旧Qwen3-30B的RULER较低，AA-LCR却59.0更高，说明检索与推理不同。不能把供应商不同RULER配方或旧AA-LCR与4.1节v1.1直接拼榜。来源：[Nano 卡](https://huggingface.co/nvidia/NVIDIA-Nemotron-3-Nano-30B-A3B-BF16)、[Super 卡](https://huggingface.co/nvidia/NVIDIA-Nemotron-3-Super-120B-A12B-BF16)。

DeepSeek V4 Flash／Pro 官方1M MRCR与CorpusQA提供较真实的超长质量参考，Think模式差异很大；Flash总284B的官方包不能因激活13B而假定单H800全驻留。GLM5.2／5.3的1M coding系统成绩也不能代替合同长文。它们是扩大质量上界的候选，不是已闭合的当前单卡方案。

## 6. 质量筛完，才使用 H800 的阶段资源

用户允许 OCR 与业务 LLM 互斥，因此保留 Paddle完整解析→释放→MinerU完整解析→释放→索引准备→业务LLM的阶段设计。不能因选了长窗模型削减双路解析范围，也不能把OCR权重继续加在每个LLM请求的常驻预算中。详细13类策略见 [部署研究](./oer-llm-deployment-strategy-research-20261012.md)。

业务阶段优先保留已选LLM和有界query E/R；若长窗口不足以共驻，先完成embedding／rerank与完整证据包，释放E/R后恢复LLM。sleep／RAM packed缓存是减少切换代价的优化，整进程退出／重载是恢复基线。分时释放不消除本次LLM长KV、临时prefill和工作区，512K必须在目标单卡实测。

只对已经核过架构的配方算缓存。已有Qwen3.6-27B全注意力KV在128K为8GiB，256K／512K为16／32GiB；35B对应2.5／5／10GiB，均未计线性状态、allocator、graph与工作区。旧3.6官方FP8静态文件28.747／34.891GiB；由此可见较小dense不一定更省长窗状态，但这些数不能直接贴到3.8或当实测峰值。出处：[固定权重与配置报告](./research/llm-task-model-candidates-20261012.md)。

Gemma31 BF16静态58.251GiB，128K普通K/V分存算术10.781GiB，256K约21.562GiB；仅两者已接近或超过标称80GB的可用范围，必须核实际KV实现／量化与质量，不能只按31B标签宣称足够。Super官方NVFP4硬件声明为Blackwell，不能给H800 Hopper借用其单B200配方；120B理想4bit算术也不是可运行权重或长窗预算。[Super NVFP4官方卡](https://huggingface.co/nvidia/NVIDIA-Nemotron-3-Super-120B-A12B-NVFP4)。

K8s需实际GPU所有者协调：sleep可能释放VRAM，却不释放原Pod的`nvidia.com/gpu`分配。一个独占GPU所有者协调隔离worker，或真正依次结束独占Pod／Job，才与平台资源模型一致。time-slicing不自动隔离显存。RAM阶段缓存与推理中每token专家CPU offload是不同策略，后者只在更强模型确有质量增益且容量不足时进入专项对照。

## 7. 评价应直接回答哪种失败得到改善

后续进入业务模型阶段时，保持既有识别harness和确认值冻结，另建隔离评价；本轮没有授权扩为新Drafting功能或启动实验。候选必须回答现有残差：

1. **值与来源**：保全否定、单位、责任对象、当前采用与unknown；引用须完整连续，不以JSON合法代替语义正确。
2. **完整性**：Bills／Sections／责任矩阵／跨页表组逐项有独立分母，分别报precision与recall，以及整份全部正确；缺项不能由平均正确率抵消。
3. **条件与冲突**：AND／OR、例外、显式替换、同名不同对象、相反证据，分别评误报与漏报。给同样完整原材料，不给另一模型M3答案。
4. **长窗有效性**：正常长度、接近256K及512K分别记录实际模型token数；关键证据分布在前／中／后，检查跨距条件、密集清单和输出预算。不仅复制填充或做单needle题。
5. **配方归因**：正确template、thinking／final parser、schema方式、精度与引擎单列；3.8 FP8首先是同底座控制，不能把系统修复后的分数全部归给权重。
6. **完整部署结果**：质量通过后核单H800 cold／warm、峰值、TTFT、prefill／decode、E/R切换、OCR后恢复和失败恢复；没有H800证据时明确保留未验。

具体业务通过门沿用现有规则，不新编总分权重或未经确认的百分比。参数量、公开平均榜和“512K能启动”均无权抵消关键已知错漏。当前研究完成了候选优先序修正与证据整理，新模型实际调用数为0，H800／K8s和前端业务门仍未通过。
