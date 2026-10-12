# 按实际任务建立 LLM 选型与评估基线

日期：2026-10-12，Asia/Shanghai。状态：研究方案；没有调用模型、修改 Drafting harness、替换服务或部署目标环境。最新范围与设备规则见 [OER 当前入口](./oer-scope-and-rules.md)，单卡策略见 [部署研究](./oer-llm-deployment-strategy-research-20261012.md)。

用户最新要求：**按 LLM 实际需要参与的任务类型评估目前候选，不限定约 120B MoE；满血 MiniMax M3 官方 API 已取得不错的 Drafting 效果，应作为评估基线调查。** 因此规模、架构、公开榜单与“单卡能启动”都不作为最终主选结论。

**同日后续修正已覆盖原容量优先序：至少256K、优选512K；先调查任务分类的公开评测与实际测试经验。** 本机Bonsai是Qwen3.8-27B底座的三进制PTQ1_0，已测端到端配方不足；用户最新补充显存和上下文因素，保存第五轮为64K，完整长窗／H800能力未验。保留FAIL并区分输入分片、窗口耗尽、精度与输出协议，不作底座能力否定，也不以FP8公开分数替该配方追认通过。当前证据表与候选角色以 [质量与长上下文选型](./llm-task-quality-selection-256k-512k-20261012.md) 及 [更大模型与归因补充](./llm-h800-larger-model-and-context-strategy-20261012.md) 为准；本文件保留任务契约、评价分账和历史容量算术，冻结harness不改。

**最新联合评价以前置RAG为准：用户再次明确Drafting、Vetting、Advisory必须一起比较，后两模块都先通过RAG初筛。** 因此充分相关证据后的审查／回答能力、真实RAG证据完整性及端到端结果分别评价；Drafting高分不能抵消Vetting关键漏报／误报，M3 Drafting正面体验不继承为Advisory有据回答成绩。当前纸面分数、方法适用性和角色调整见 [三模块联合研究](./llm-three-module-rag-selection-20261012.md)，没有新增业务阈值、运行或harness变更。

详细材料：

- [实际任务、调用边界与已保存效果证据](./research/llm-task-baseline-current-evidence-20261012.md)。
- [Vetting的RAG后任务证据](./research/llm-vetting-rag-task-evidence-20261012.md)、[Advisory的RAG后任务证据](./research/llm-advisory-rag-task-evidence-20261012.md)。
- [按任务扩展的模型候选调查](./research/llm-task-model-candidates-20261012.md)。
- [MiniMax M3 官方身份及质量参照规则](./research/minimax-m3-evaluation-reference-20261012.md)。
- [准确权重／KV 容量计算](./research/llm-h800-selection-20261012.md)。
- [更大模型运行机制](./research/llm-h800-larger-model-runtime-options-20261012.md)、[具体候选](./research/llm-h800-larger-model-candidates-20261012.md)与 [本机上下文归因](./research/bonsai-local-context-capacity-attribution-20261012.md)。
- [本机Bonsai失败与固定回答重放](./research/bonsai-local-task-evidence-20261012.md)、[独立／作者任务评测](./research/llm-independent-task-evaluations-20261012.md)、[Qwen新旧同表](./research/llm-task-benchmark-qwen-20261012.md)、[256K／512K证据](./research/llm-context-256k-512k-evidence-20261012.md)。

## 1. 先建立三层基线

**质量参照基线**：保留用户认可的 MiniMax M3 官方 API Drafting 输入、原始输出、实际采用与保存成果。用户的认可是一条重要证据，但没有真实逐项评分时，不虚构字段准确率或全部任务通过率。后续对其覆盖范围与失败项逐项核验；用户认可的完整体验不能全归为某一次 LLM 调用的成绩。

**模型能力基线**：对各候选提供同一份准确、完整且已核的任务输入，在独立评价中比较需要模型承担的识别、关系理解、冲突判断和依据回答。程序执行、文档保存、OCR 质量、检索漏召回与前端状态分别记账，避免把它们算成模型能力。

**完整系统基线**：用实际 OER→query 检索→材料排包→LLM→执行／引用／报告链检验最终任务。此时报告完整质量、GPU／RAM峰值、切换次数、TTFT与总时延。模型独占能力较强而系统丢证据，仍不通过；模型比 M3 更小而通过真实任务与部署门，可以成为主模型。

三层都保存冻结身份，评价集与生产资料隔离。MiniMax 输出是对照结果，**原件、项目真实采用关系、用户确认值和独立来源核验才是判断依据**。不能将 M3 的所有回答直接当 gold，也不能把其答案作为同题其他模型的输入。

## 2. 哪些任务需要比较不同模型

下表描述本项目任务的评估职责，不新增实现或改变已冻结的 Drafting 规则。实际调用接口、operation 与保存证据在独立当前证据报告定位。

| 任务族 | LLM 要解决什么 | 必须留给程序／原件的部分 | 模型评价重点 | 对模型资源的影响 |
| --- | --- | --- | --- | --- |
| Drafting 资料识别与候选值 | 从沟通／项目资料识别取值、候选、采用线索和待确认条件 | 来源身份、原文定位、真人确认、最终取值与确定性执行 | 值与字段对象配对、请求／回复区分、未知不当No、引用不编造 | 资料可能长，需多来源理解；小模型只能用实测说明适用边界 |
| Drafting 双证据关系建议 | 对两个原始候选及完整上下文判断 supplement、explicit_replacement、contradiction、undetermined | 来源身份与跨度校验、合并规则、人工采用、最终取值 | 同一对象／范围、明确修订语言、引用支持关系；不得由日期或上传次序猜优先级 | 属于有界语义判断；不是让 LLM 重写完整合同或代替确定性编辑 |
| Vetting 结构化审查 | 跨文件比较义务、条件、版本、引用和项目事实，提出发现与疑问 | source ID、原文引用、完整证据包、schema／范围校验和报告导出 | 漏报与误报、对象／列角色、适用条件、相反条文、完整报告 | 最适合比较dense与MoE推理差异；長上下文不等于必须送整库 |
| Advisory 依据回答 | 按用户问题选择并解释适用依据，缺背景时澄清，证据不足保留未知 | 实际检索与版本、原页／原图／完整表组回取、引用资格 | 回答是否直接、条件例外是否完整、引用是否支持、不过度推断 | 连续query，在线E/R与LLM切换成本及对话输入预算重要 |
| 上传资料语义身份／关系辅助 | 在确定字节、结构线索之外识别可能类别、内部角色或引用关系 | 准确SHA、路由资格、已核范围、可靠native事实 | 候选置信与unknown、误分为固定模板／评价答案、角色不串用 | 可研究较小模型承担有界任务；当前不假定已有这类LLM调用 |
| 原文／结构分歧修复候选 | 对已定位分歧提出可由同一原件验证的字符／关系候选 | 原件真值、独立源核、完整表组采用及失败账本 | 不语言润色合同、数字否定不猜、提议须可定位核验 | 不是把文本LLM当OCR或多数表决；没有视觉能力不能读裁图 |
| 图表返回与结构资产 | 首版仅用文字／结构定位并返回准确原图和完整表组 | 来源文件／版次、完整成员、资产SHA与关联 | 找到正确对象、标题／父条／跨页组正确 | 现Q10范围不需要引入大多模态LLM语义理解箭头 |

最后三项是可能的语义辅助职责及能力边界，不因“可以由 LLM 做”而改成当前必须调用模型的环节。原生解析、条款定位、索引、完整性与确定性编辑能由程序保证的，应继续由程序保证。

当前 Drafting 最终 NTT／SCT／SCC 由原生 DOCX 规则编译、OOXML binding 和已确认值生成，没有要求 LLM 写整份合同。条款条件树、作用范围和编辑计划仍是重要业务验收，但不能将后续程序修复计为模型新识别成功。当前实际调用契约与源码位置见 [任务证据第1节](./research/llm-task-baseline-current-evidence-20261012.md)。

## 3. MiniMax M3 是有用的参照，但要冻结实际配方

本轮已核 [MiniMax 官方模型目录](https://platform.minimax.io/docs/guides/models-intro)、[Chat API](https://platform.minimax.io/docs/api-reference/text-chat-openai) 与 [Responses API](https://platform.minimax.io/docs/api-reference/responses-create)。正式 model ID 为 `MiniMax-M3`，官方给出1M窗口；“满血”沿用用户所指的正式 M3 官方 API，不能据此反推厂商内部精度或 checkpoint。实际 endpoint／账户／模型快照可用范围与已发送输入仍需记录，不能把产品上限当某次调用已利用的预算。

“满血”保留用户意思，但评估时用可复核字段表达：准确model ID、直连官方或relay、endpoint类别、请求／响应model字段、输入模板、system/developer prompt、工具与schema、thinking/reasoning模式、max_tokens、采样、完整输入与实际生成token、重试、缓存和失败状态。开发中已有relay的事实不能否定用户本轮说的官方API，也不能把两条传输路径的结果混计。

M3效果可能来自多种共同作用：模型自身推理、较大的完整上下文、正确来源与提示、harness候选约束、用户确认，以及确定性正文执行。评估要分别保留这些条件，而不能只把“大模型输出的文稿看起来不错”变成某个参数规模的要求。

[M3 官方开放权重](https://huggingface.co/MiniMaxAI/MiniMax-M3) 已存在，约428B总参数／23B激活；产品页“即将开源”的旧文案与实际权重仓、专门部署页存在状态冲突，不能继续据旧文案说权重未发布。[官方自部署页](https://platform.minimax.io/docs/guides/local-deploy-m3) 当前为 Experimental：参考8×B200／TP8、MXFP8约444GB权重，另有8×H200 BF16。总权重决定驻留空间，23B激活决定每步部分计算；单H800整模型GPU常驻不成立。CPU／expert offload可独立研究，但不是已验收的单卡替代。详见 [M3 专文第4节](./research/minimax-m3-evaluation-reference-20261012.md)。

M3官方API可作开发质量参照，比赛最终算力仍按已确认赛方资源和实际平台规则；若平台提供该类endpoint，则单独核其资源／额度／适用性，不能默认为比赛可以依赖外部API。

### 已保存两次 Drafting 运行，分开报告

| 保存运行 | 可复核结果 | 对评估的意义与限制 |
| --- | --- | --- |
| 2026-10-09，`c4bb7f20-cde1-4bae-8205-1da79f2a2375` | 有值58/58正确；应留空21/21正确，刻意缺证4/4属其子集；自动原报告42 PASS／37 REVIEW／0 FAIL；37 REVIEW、38候选引文独立核原件均支持 | 支撑M3作为Drafting回归质量参照；固定题已参与开发，不能称新的盲测。来源：[工具说明](../tools/drafting_eval/README.md)、[独立台账](./testing/m3-round5-20261009/README.md) |
| 2026-10-10，`8f01b7df-6234-4ffb-b204-2b009f5cadba` | 非null字段57/61有正确有引用候选；预期null本轮新增候选0/18；刻意缺证0/4属子集；原strict scorer仍FAIL | 属已有人工采用项目的本轮候选覆盖；65项人工确认／编辑字段不能计为模型命中。四项残差与严格页面状态口径见 [当前证据第3节](./research/llm-task-baseline-current-evidence-20261012.md) |

两次运行不合并分子／分母，也不以较早全对结果抹去较晚残差。后者已核难例包括：空列表引文无主语、正确repair值输出对象而非数组、普通two-envelope要求误归额外提交、Sections初次遗漏及补查预算耗尽。它们分别检验完整引用、输出协议、业务类别与遗漏恢复，不只是测试长上下文。

### API协议和实际调用路径须冻结

stable M3的Chat默认adaptive，可显式disabled；`reasoning_effort`对stable不调节深度。Responses默认off，非none打开推理但同样不调深度。Flash Preview是不同模型和语义，不混入stable基线。协议切换而未显式固定thinking会改变对照条件。

当前Drafting普通`complete`与Advice普通`completeJson`走plain chat；源码的16,384输出／thinking disabled等structured默认只属于相应结构化调用路径，不能推定旧Drafting已经使用该预算或模式。现有Drafting trace缺每请求usage／finish_reason，不能倒填token成本、截断分布或reasoning预算。新对照保存完整provider收据，历史成绩继续保留其证据限制。

## 4. 候选池应按任务和配方组织

下列为**当前候选角色，未测者没有本项目成绩**。正式选型按具体checkpoint、精度、引擎与能力组合。公开分任务成绩及其模式、版本、利益关系见 [当前质量调查](./llm-task-quality-selection-256k-512k-20261012.md)，不把同家族所有版本视为相同模型。

| 候选组 | 应进入比较的理由 | 优先任务 | 必须验证的风险 |
| --- | --- | --- | --- |
| 本机Bonsai2-27B PTQ1_0 | 已保存第五轮原始FAIL及固定回答重放仍FAIL | 历史失败与协议归因 | 从完整主模型默认推荐移除，保留在线状态；不是新实验 |
| Qwen3.8-27B 官方FP8 | 独立AA-LCR及作者FP8抽取成绩较强；原生256K／官方512K扩窗 | 同底座精度／协议隔离对照；全任务能力待验 | 不能用公开成绩覆盖三进制本机失败，长抽取recall仍低；旧3.5／3.6分数不继承 |
| Qwen3.6-35B-A3B 官方FP8 | 原生256K、官方512K与FP8抽取pipeline有据 | 本地MoE对照，Drafting／Vetting／Advisory | 真实长文推理与完整抽取并未领先3.8-27B，不由容量提升为质量主选 |
| Gemma4-31B | 独立家族；OCR转写文本抽取有不同优势，原生256K | 源类型差异与完整业务对照 | 无已核官方512K；26B的ExtractBench不能给31B；H800峰值另验 |
| Qwen3.5-9B | 新小模型同表长文／指令／工具成绩可超旧80B，原生256K／官方512K | 有界关系、typed extraction、缺证及难例能力对照 | 不按9B排除难任务，也没有成绩证明可以接管全任务 |
| Nemotron3 Nano／Super | 有512K／1M分长度合成测试 | 长窗专项对照 | Nano真实长文推理弱；Super官方NVFP4为Blackwell，H800路线未闭 |
| M3官方API／DeepSeekV4长窗家族 | 已有业务质量参照／更强长窗质量上界信号 | 困难任务参照与后续容量专项 | 单H800全驻留不成立，不把API或未验offload默认当比赛方案 |
| GPT-OSS120／GLM-4.7-Flash／Qwen2.5-72B | 历史能力或资源对照 | 短任务对照 | 已核窗口均低于256K，退出完整主模型池 |

官方入口：[Qwen3.6-27B](https://huggingface.co/Qwen/Qwen3.6-27B)、[Qwen35 FP8](https://huggingface.co/Qwen/Qwen3.5-35B-A3B-FP8)、[Google Gemma4 模型卡](https://ai.google.dev/gemma/docs/core/model_card_4)、[GLM-4.7-Flash](https://huggingface.co/zai-org/GLM-4.7-Flash)、[GPT-OSS120](https://developers.openai.com/api/docs/models/gpt-oss-120b)。更多具体包、精度／长度／许可与来源核查见 [任务候选调查](./research/llm-task-model-candidates-20261012.md)。

不建议第一轮将所有模型×所有精度×所有任务笛卡尔积展开。先选择一项dense高精度、一项低驻留MoE、一项独立家族，并用小模型做有界任务对照，使用同源完整材料测试；明显失效者保留原因，表现足够者再进入完整系统与部署循环。大MoE和较大dense量化根据困难题残差加入挑战，避免先占用大量下载／推理时间。

**当前研究建议**：M3保留质量参照；3.8官方FP8仅作有明确归因目的的同底座控制，3.6-35B官方FP8作本地MoE对照，Gemma31与9B分别回答家族／源类型和小模型能力问题，Nemotron作长窗专项。没有足够证据宣布其中任一未测本地方案已经达到M3效果。参数、容量与公开平均分均不抵消关键错漏；到达既定业务评价阶段后再执行隔离对照，不修改当前冻结harness。

下表保留初版已经核算的静态权重／128K容量事实，**不是当前≥256K主模型优先序**；新的256K／512K资格与长文质量见最新研究。短窗控制项不能由这里重新进入主方案。

| 精确候选配方 | 已核静态权重 | 128K总序列的BF16 KV算术 | 研究意义 |
| --- | ---: | ---: | --- |
| Qwen3.5-9B BF16 | payload17.980GiB | full attention4GiB，另加linear状态 | 测小模型任务边界，长窗口与可靠理解分开 |
| Qwen3.6-27B BF16／官方FP8 | payload51.747GiB／file28.747GiB | full attention8GiB，另加linear状态 | 同版本精度对照；FP8明显改善容量，但业务损失须实测 |
| Qwen3.6-35B-A3B FP8 | file34.891GiB | full attention2.5GiB，另加linear状态 | 给在线E/R、workspace和长输入留较多空间的候选 |
| Gemma4-31B-it BF16 | payload58.251GiB | 普通K/V分存约10.781GiB，依实现验证 | 独立家族，但权重＋KV已约69.032GiB，不能只看31B标签 |
| GLM-4.7-Flash BF16 | file58.156GiB | MLA由具体engine表示决定 | 短窗历史控制；index的total_size异常不能当约29GiB，按48个真实文件长度核 |
| Qwen2.5-72B-Instruct-AWQ | payload38.739GiB | 约40GiB；原生32K／YaRN128K分开 | 短窗历史控制；退出完整主模型，容量算术保留 |
| GPT-OSS120 MXFP4 | payload60.768GiB | 约4.504GiB理想分层／9GiB全长情景 | 短窗历史控制；退出完整主模型，Harmony／E/R研究保留 |

表中GiB=2^30bytes；payload是张量索引字节，file是权重文件长度含header，两种口径不能精确相减。KV还未计block分组、workspace、graphs、线性状态与其他驻留。逐数官方revision、config／index、GLM异常核算和Gemma不自动减半的依据见 [候选调查第3、4、7节](./research/llm-task-model-candidates-20261012.md)；表中没有H800实测峰值。

在128K这一共同档，27B官方FP8与35B-A3B官方FP8的静态权重＋普通KV分别约36.747／37.391GiB，量级接近；到256K时各自KV斜率不同，不能由参数标签判断谁更省显存。dense与MoE的计算量、prefill／decode内核和CPU回迁也不同，最终等待由真实任务决定，不能用激活参数的比例推算tokens/s。

## 5. 公平比较：相同材料与各模型最佳部署是两轮实验

**第一轮：相同任务材料。** 使用相同原件版本、原文／完整条件包、项目采用事实、用户确认值、schema与最小必要instructions。各模型用自己的正确tokenizer／chat template，记录实际token数；不将MiniMax计数冒充本地模型计数，不把错误template造成的失败当模型弱。

若完整共同材料超过某模型已验证预算，记为该profile容量不准入，或用预先定义并保留完整依赖关系的通用分组方案另做实验。不能为让小模型通过而裁掉难例、相反条文或条件，也不能让M3看1M整库、另一模型看几段RAG后把差异全算模型能力。

**第二轮：各自可部署最佳配方。** 允许各模型用本机可运行、质量通过的上下文、精度、分块prefill、thinking、KV与阶段调度，然后在同一业务题和真实来源上比较最终完整质量与运行成本。它回答“哪个方案最适合这个系统”，与第一轮纯能力对照分开报告。

公开榜单只用于候选筛选，不能作为最终本项目质量分数。重试和多候选投票会消耗额外调用／token和用户等待，必须一起计入；不得用一个模型多次取最优结果与另一个模型单次比较而隐去采样。

## 6. 评价指标和失败账本

| 轴 | 逐项核验内容 | 为什么不能只看总分 |
| --- | --- | --- |
| 值／对象正确 | 每个字段、实体、用途、请求／回复、数字、单位、否定与unknown | 一个关键否定或对象错配可以改变整项义务 |
| 完整语义单元 | 主条款、局部条件、AND/OR、例外、相反规定、跨页表组 | 单句正确不能证明完整判断正确 |
| 来源与采用 | 当前版次、真实项目范围、source定位、引用字面、图表成员 | 流畅回答引用另一版本仍错误 |
| Vetting发现 | 已有独立gold对应的漏报、误报、类别、严重性、依据与建议 | 发现越多不代表审查越好；没有问题不代表有覆盖 |
| Drafting链 | 模型候选／语义判断、确认值、修改计划、实际保存正文分别检查 | 用户确认或程序修正后的成功不能全部归给模型 |
| 输出协议 | JSON/schema、完整final、length/refusal/empty、streaming终态 | schema合法不能抵消业务错误，截断报告也不能算完成 |
| 交互质量 | 回答针对性、必要澄清、证据不足处理、正文可复核 | 写作风格好不是来源保真或法律正确性 |
| 运行代价 | peak VRAM/RAM、cold/warm、每问load次数、TTFT、全任务时延 | 参数小或独占tokens/s高不能代表全系统更快 |

通过门应优先保持关键内容与来源正确，再比较普通项、覆盖与性能。不能让风格分数平均掉数字／否定／版本／条件的已知关键错误。具体业务阈值沿用当前已确认规则；尚未给出相应任务独立gold分母时，不虚构一个百分比门或通过率。

每项数量与比例附原件／题目、源位置、候选output位置及分母清单。区分自动确定、独立Codex核验、真人确认、unknown与未评，零失败也须给核验范围；开发暴露题与真实留出分开。2d／2e只进入隔离evaluator，不进入同题解析、索引或提示。

## 7. 一套主模型与按任务多模型的取舍

| 组合 | 收益 | 代价 | 建议 |
| --- | --- | --- | --- |
| 一个通过各任务门的主LLM | 状态、模板、依赖、缓存与调度简单，最容易审核和恢复 | 同一模型未必在每类任务最优 | 先建立此基线；模型规模可以是27/31/35，也可以120，由成绩决定 |
| Drafting／Advisory与Vetting分别使用已验模型 | 简单任务节约资源，难任务可用更合适模型 | 多份packed缓存、加载、schema/template、revision和跨模型上下文需要管理 | 仅各任务实测差异明显时采用，不预先复杂化路由 |
| 小模型做有界候选、大模型做高风险判断 | 可减少大模型承担的确定性分类与候选工作 | 小模型漏项可能成为大模型输入瓶颈，增加链路失败点 | 保留完整原文和完整候选范围，大模型不能只看小模型摘要 |
| M3 API用于开发参照、本地模型用于比赛 | 直接衡量用户已认可效果与目标部署之间的差距 | endpoint条件、上下文和费用不同，正式环境权限独立 | 明确两条成绩轨，不把API结果写成本地模型验收 |

任务类型已经由模块和operation明确时，路由可以由显式任务类型选择，不必增加一个LLM“猜哪个模型”的环节。无论采用哪种组合，模型切换均保存实际model/profile身份，不能静默备用或让用户在同一任务中收到不同模型却没有记录。

## 8. 当前完成程度与下一轮研究／验收顺序

本轮完成的是官方候选与资源研究、任务责任拆分、MiniMax基线定义和文档入口更新；用户已观察到的M3 Drafting良好效果已明确记录。新的真实M3或本地候选对照、完整任务独立gold评分、单H800峰值与切换均未执行。

目前继续已确认OER原件质量主线，Drafting harness不动。后续按实际工程阶段整理准确M3保存输入／输出和已确认业务成果，建立逐任务冻结清单与失败账本；先进行完整材料的模型能力对照，再进行完整系统与单卡部署循环，最后选择通过任务质量门、可在目标资源运行且恢复可靠的模型／组合。研究允许更广的候选，不代表全部候选都必须下载或上线。
