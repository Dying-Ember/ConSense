# MiniMax M3：开发质量参考、任务评估与单 H800 边界

日期：2026-10-12，Asia/Shanghai。状态：**只读研究与 proposed 评估方案；未调用 API、未读取密钥、未改变服务、未下载或部署模型**。本轮仅新增本文；官方资料为当日公开页面快照，其 API 名称／服务规格仍可能更新。

入口遵循 [OER 已确认范围与执行规则](../oer-scope-and-rules.md) 与 [实施交接](../HANDOFF-ocr-embedding-rerank-20261008.md)。本文配合 [LLM 候选研究](./llm-h800-selection-20261012.md) 和 [单 H800 显存与生命周期研究](./oer-h800-memory-scheduling-20261012.md) 阅读，不把研究建议写成已采用规则。原件质量、固定库、完整业务及 H800 门仍分别验收。

## 1. 结论与用户已确认观察

用户本轮明确：**官方完整 MiniMax M3 API 在 Drafting 上效果很好，应作为质量参考；LLM 应按任务选择，不限定约 120B；OCR 与 LLM 不同时运行，可以分阶段加载／卸载。** 本研究接受这些业务前提，不重新询问，也不因为其他任务旧实验失败否定 Drafting 的实际使用体验。

建议把官方 `MiniMax-M3` 保留为开发阶段的主要质量参考。它的价值是给出目前认可的 Drafting 表现，并帮助识别本地候选缺失了哪些事实、条件、引用或结构；本地选型仍由 Drafting、Vetting、Advisory 的分别评分以及单 H800 实际容量／时延决定。大模型参考结果不等于原文 gold，也不能替代独立核验。

**官方 API 是开发比较渠道；它不是已确认可用于比赛阶段的额外算力。** 本文没有新增比赛可调用云 API 的判断。比赛实际运行仍以已确认资源和规则为准；质量参考、可部署候选、比赛采纳三个决定分开记录。

## 2. “完整 M3”应落实到准确身份，避免换成另一模型

| 身份／选项 | 当日官方证据 | 对本项目的处理 |
| --- | --- | --- |
| `MiniMax-M3` | [模型目录](https://platform.minimax.io/docs/guides/models-intro) 与 [Chat API](https://platform.minimax.io/docs/api-reference/text-chat-openai) 列出的正式名称，模型窗口为 1M | 用户认可的官方完整 M3 参考使用此 ID；保存响应实际 model 字段 |
| `MiniMax-M3.1-Flash-Preview` | 官方另列名称，当前注明仅通过 M Plan／MiniMax Code 提供 | 作为另一候选与配方，不替换 stable M3 基线，不移植其默认推理深度 |
| “full”／完整 | 当日 Chat 模型枚举未列 `MiniMax-M3-full` | 这里指用户使用的正式 M3 API；不能据此声称已核 API 后端权重精度、checkpoint 或多卡配置 |
| standard／priority | [官方 Chat API](https://platform.minimax.io/docs/api-reference/text-chat-openai) 中是请求准入档位 | 作为成本／延迟变量保存，不视为另一套模型权重或自动质量提升 |

模型名称不是不可变 revision。API 实验应保存请求时间、请求／响应 model、response ID、实际 endpoint 与配方；若厂商不公开 revision，就明确记录 `backend_revision=unpublished`，不把 Hugging Face 的某个 hash 冒称 API 后端版本。

## 3. 当前 API 语义与本仓库配方不是同一层

当日 [Chat API](https://platform.minimax.io/docs/api-reference/text-chat-openai) 的关键语义：stable M3 默认 adaptive，可 disabled；`reasoning_split` 只控制输出位置；`reasoning_effort` 的真正深度调节当前只适用于 Flash Preview。Chat 已建议 `max_completion_tokens`，旧 `max_tokens` 标记 deprecated。[Responses API](https://platform.minimax.io/docs/api-reference/responses-create) 对 stable M3 的默认推理为关闭，非 none 的 effort 才打开；其 output 预算明确包含 reasoning。**切换协议而不显式固定模式，会改变实验条件。**

因此评估清单应至少区分以下三个对象：

- **现有项目配方**：`MiniMax-M3`，源码结构化请求配置为 thinking disabled／reasoning split true／输出 16,384，通用 temperature 0.2。来源：[application.yml](../../src/main/resources/application.yml)；这是默认源码，不是读取现场环境后的完整 live 配方。Drafting普通`complete`与Advice普通`completeJson`走plain chat，不发送上述structured专用参数，不能推定旧Drafting已有16,384预算或disabled模式；准确调用路径见 [任务证据第1节](./llm-task-baseline-current-evidence-20261012.md)。
- **M3 adaptive 参考配方**：在相同资料／任务上明确打开 adaptive，给足 reasoning＋final 的总预算；输出解析仅消费最终内容，原始 response 完整保留。它是另一个实验条件，不能把结果归因于“同预算同模式”。
- **候选本地引擎配方**：固定模型／量化 revision、tokenizer、chat template、reasoning parser、采样与输出预算；再分别与上述已固定的 API 条件比较。

当日 Chat 页面未提供可据此确认的 strict JSON Schema 保证；Responses 页面虽然列出 `text` 输出格式对象，当前公开展开结果仍不足以证明所需 schema 子集及 strict 行为。本研究不把“OpenAI compatible”当作完整 schema 支持，也不把提示词要求 JSON 当约束解码验收。无论 API／本地，都对完整业务 schema、额外字段、必需字段、类型、引用成员、原文跨度和失败恢复执行实际验收；HTTP 200 与能解析 JSON 只是中间门。

官方 [OpenAI SDK 指引](https://platform.minimax.io/docs/api-reference/text-openai-api) 要求工具多轮保全完整 assistant／tool-call 和相应 thinking 内容。评估应保存原协议字段，并按各协议的官方格式续接，不能只拿上一轮最终 prose 填回，再把质量下降归因于模型。单轮与完整多轮工作流分别评分。

1M 是模型／API 的标称窗口，**不是本项目已验收的证据预算**。资料内容、系统指令、工具定义、历史、隐藏模板以及预留 reasoning／final 都占预算；每个目标 tokenizer 的完整 wire 输入必须独立计数。达到窗口也不保证长距离条件和例外均能被使用。项目 16,384 输出上限与厂商 API 的更大允许值属于不同层，不直接同步修改。

## 4. 开放权重已存在，但不能按 23B 激活量估算整卡容量

官方 [MiniMaxAI/MiniMax-M3 模型卡](https://huggingface.co/MiniMaxAI/MiniMax-M3) 已提供开放权重，约 **428B 总参数／23B 每 token 激活**。这排除了“M3 只有 API、没有权重”的判断；激活量衡量本步使用的专家计算，不代表只需保存这部分权重。[产品页](https://www.minimax.io/models/text/m3) 仍有即将开源的旧文，与已存在的官方权重仓和专门 self-host 指引不一致；当前可获取性以实际权重仓及专门部署资料为依据，保留页面状态冲突，不混用发布前文案。

以下仅是参数 payload 的推导，未包含 scales、未量化层、KV、CUDA context、图和运行 workspace：

| 表示 | 约数推导，decimal GB | 约 GiB | 单 H800 含义 |
| --- | ---: | ---: | --- |
| BF16／FP16，2B／参数 | 428×2≈856GB | ≈797GiB | 超出 80GB，整模型 GPU 常驻不成立 |
| 理想 4bit，0.5B／参数 | 428×0.5≈214GB | ≈199GiB | 即使量化也明显超出 80GB；实际还增加元数据与非量化层 |

官方 [self-host 指引](https://platform.minimax.io/docs/guides/local-deploy-m3) 当前标 **Experimental**，页面最后审阅日 2026-08-26：参考为 8×B200／TP8，`MiniMaxAI/MiniMax-M3-MXFP8` 固定 revision `c5454eb03678d8710e54a4e0fc681b9f3b4a3dba`，权重仓约 444GB；另列 8×H200 的 BF16 路径约 854GB。官方未给该配方最小 host RAM、peak GPU 或完整生产磁盘余量。它不是单 H800 验证。

CPU／expert offload 并非数学上完全不可研究：250GB RAM 与 4bit 理论约 214GB 是同一数量级。但还要放 scales／非量化层、实际可用 host RAM、解析与索引服务、反序列化／转换峰值及缓存；27 vCPU、host bandwidth 和 CPU↔GPU transfer 又影响逐 token 的速度。需准确 checkpoint／后端支持、实际 RAM 峰值和整任务时延后才有结论。**目前不适合作为单 H800 主部署基线，也不能假设它保有官方 API 的性能。** 用户允许 OCR／LLM 分阶段解决两阶段重叠占用，仍不能把单个 M3 权重缩小到卡内。

## 5. 本仓库真实覆盖：Drafting 正面体验保留，任务覆盖分别评价

| 已有观察 | 可以支持什么 | 尚不能推断什么 |
| --- | --- | --- |
| 用户认可官方 M3 的 Drafting 效果；Drafting 识别／确认值／生成当前冻结 | M3 有明确业务参考价值；保留已验证交互和输出作为回归样本 | 不从用户认可直接推断所有模板、未见项目及 OER 问答都已达标 |
| 保存的后续 Drafting 生成／版式回合复用确认值，部分没有新 M3 调用 | 文档生成、版式、确认值保持有独立证据 | 不把每次 DOCX 生成都算新的模型识别成功 |
| 2026-10-11 Advisory Q1.6 新 M3 调用，53 完整 source 单元已入 prompt，HTTP200／正常 completed | 当前完整证据 wire 及 API 生成路径已实际跑通 | 不代表官方八题、Vetting 和 H800 已完成 |
| 同次 Q1.6 source-only 独审为 25pass／14partial／6fail，ledger 32.0／45 | 指出及时 Notice 分支、前提／报告／失效出口、保密与引用残差，指导单因素改进 | 不是准确率／比赛分数，不能外推 Drafting 或全任务效果 |

Q1.6 的可复核数字来源均在 [handoff 的“Advice观测与展示组合后端…”与“新Q1.6不同作者逐项评分…”增量](../HANDOFF-ocr-embedding-rerank-20261008.md)：请求 290,272 UTF8B、厂商 input 83,868／output 2,647／total 86,515 tokens、generation 32.1365675 秒；response receipt SHA `a403634ce8e021f7eeef16056d8f17ab04280bc9b7e1e9488f1fc40faac14c04`，独审 receipt SHA `082366681b6371b63b7b447ba82b597d3c0402d059509f34f264a9023b84375a`。这是保存证据引用，本研究未重放或读取原件／raw。输出低于 16,384 且正常 completed，不支持把该次所有遗漏归因于输出截断。端到端与 provider 时间不同，差值未完整归因，不都记为 GPU 时间。

历史 [Vetting 实验日志](../vetting-experiment-log.md) 保留 stable M3 adaptive 的真实调用及 schema／证据问题；它服务于相应任务的原因研究，不作为当前 Drafting 效果的否定证据。现有 [MiniMax relay handoff](../minimax-relay-handoff.md) 仅承诺特定非流式文本 Chat 范围，16,384 上限等中继约束不能冒称厂商官方 API 的全部能力。

## 6. 两条比较轨道，避免让大窗口或额外证据掩盖模型差异

**轨道 A：相同完整证据的质量比较。** 给 M3 和本地候选相同 source bytes／hash、完整必要条款和条件、同系统／任务／schema、固定工具返回及一致对话历史。逐模型计算完整模板的 token；必要证据超出某候选有效窗口，就记容量失败，不截尾仍称完整输入。每次输出都保存原始请求与响应，不修 raw 后记模型成功。

**轨道 B：各自可部署配方的业务比较。** 各候选使用自己的已验收窗口、引擎、量化、检索预算和 reasoning 模式，在同一源库／题集／source-only gold 上跑正常流水线；检索变化与 generation 变化分开归因。这条轨道允许专长模型与不同部署方案，回答“业务效果、切换成本、等待时间能否接受”。不能把轨道 B 的多证据／多重试结果放到轨道 A 排名。

| 控制维度 | 必须保存／固定的内容 | 防止的误判 |
| --- | --- | --- |
| 来源与证据 | revision、SHA、完整页／条款、原空白／删除线／条件；未知保留 | M3 多拿证据、本地缺条件，却归因为模型更弱 |
| Token／预算 | 各自 tokenizer＋chat template；input、reasoning、final、max、实际 stop；不能精确拆分则记未知 | 用 M3 的 vendor 计数宣称另一个模型能放下；只给小模型相同数字却不给完整内容 |
| Thinking | stable disabled 与 adaptive 分开；本地明确模板开关、解析和上限 | 把关闭 thinking 叫“不完整 M3”，或把 Preview 的 max effort 当 stable 更高精度 |
| 多轮／工具 | 原始 assistant／tool-call／工具结果及全部必要历史；固定轮数／工具结果的比较另列 | 只喂最好一轮、裁剪不对称、人工修复后当自然完成 |
| 输出评分 | schema、原文引用、完整条件、例外、时钟／事件、默认出口、未知处理逐项证据 | 单看语言流畅度或 JSON 可解析而漏法律逻辑 |
| 重试／修复 | 单次结果与全部重试、总调用／时间／tokens／修复次数分开 | 最佳样本代替一次可靠性，低估正常用户等待 |
| 部署与费用 | API tier／cache／计费单位／endpoint；本地冷暖加载、KV、峰值、切换、CPU offload | 将 remote wall time 当本地 tok/s，把初始 cache 优势当模型质量 |

API 成本使用当日 [官方 API 价格页](https://platform.minimax.io/subscribe/token-plan?tab=api-enterprise) 与实际 usage 的 input／cache／output 分项复算，并区分窗口档位和 service tier；不同地区／计划不能照搬价格。本报告未读取账单或计费账户，也未建立消耗预算。对已有 response 如没有全部 usage breakdown，则不补造 cache／reasoning 成本。

## 7. 按任务的 scorecard，而不是按参数量做总排名

| 任务 | M3 作为参考的主要价值 | 决定本地候选是否可替代的验收 |
| --- | --- | --- |
| Drafting | 已有正面使用体验；长表单／条款的识别、字段关系与文本草拟 | source-only 完整字段／关系／未知；确认回路；输出正文与最终 DOCX／页面业务分别核验。既有确认值生成不算新识别 |
| Vetting | 对条件、例外、标准和项目覆盖作结构判断 | 高风险遗漏／误判、适用性与引用成员、schema、完整证据覆盖；normal batch 完成及拒绝／失败路径 |
| Advisory | 复杂事件、时钟、逻辑链及多轮解释的能力参考 | 单轮／多轮；正确事件与条件、完整默认出口、GCC／SCC overlay 明示、来源引用、图可读性；query E/R 切换计入等待 |

通过条件先固定再看候选输出。尤其高风险假合规、条件漏失、来源误引不能被流畅度均分抵消。每项判定给原页／条款证据、分母与 unknown；跨任务异构指标不拼成“总准确率”。同一完整来源的开发调优与未见 blind holdout 分开，禁止把官方答案、评分 ledger 或 M3 参考答案回灌题集、检索标签／提示或训练后再称 blind。

M3 输出可以辅助提出需要检查的差异，并做可见的参考答案草稿；最终 gold 只能来自允许的原件／法规和独立核验。M3 自评不单独作为裁判；小模型与 M3 都要接受同一事实检查。若某任务较小模型质量达标且明显降低显存／冷加载／等待，按该任务采用较小模型符合目标；也保留多模型阶段轮换，不人为预设统一 120B。

## 8. 建议下一步与尚未完成项

1. 当前先完成已授权的全来源解析质量工作；不因本文提前推进索引／性能／H800 或改变冻结 Drafting。
2. 以后进入 LLM 验收时先整理已认可 Drafting 的准确 source→request→response→确认→生成链，标记新识别与复用生成；保存成开发回归，另建独立未见完整样本。
3. 先据保存wire识别现有M3的真实协议与thinking条件；缺失者保留unknown，不把structured源码默认倒填到Drafting历史运行。未来对照把显式disabled与adaptive分别作为固定条件，按届时实际实验范围与调用预算执行。本文只定评估清单，没有发起任何新调用。
4. 用相同完整证据比较本地候选，逐任务筛质量，再在单 H800 上验证最大请求、KV、冷暖恢复／E/R query 切换、OOM 回收及下一任务恢复；通过后才选择比赛配方。
5. 如仍要研究 M3 CPU／expert offload，将其列独立高成本实验：准确量化与 backend 兼容、RAM 峰值、27 vCPU／传输瓶颈、整业务等待缺一不可；目前不把它列为已可用本地替代。

本研究完成的是官方身份／规格核对和比较设计。实际新模型调用 0、权重下载 0、配置／运行态变更 0；M3 全任务质量、strict schema、单 H800 offload、比赛云 API 适用性均未因此验收。
