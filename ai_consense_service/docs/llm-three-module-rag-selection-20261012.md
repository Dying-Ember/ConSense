# Drafting、Vetting 与 Advisory 的联合 LLM 选型：RAG 初筛后的纸面证据

用户后续要求的单份交付见 [LLM选型与OER部署完整分析报告](./consense-llm-oer-architecture-analysis-report-20261012.md)：整合本文与部署、长窗、本机经验，提供架构agent的具体实施交付物；本文继续保留三模块任务证据与纸面分数的专项说明。

日期：2026-10-12，Asia/Shanghai。状态：公开证据研究与选型判断，未运行新的模型／API 推理、下载权重、修改代码／冻结 harness 或变更服务。本文件承接用户最新两项明确要求：三个模块一起评估；Vetting 和 Advisory 都先通过 RAG 初筛。当前实施顺序仍为解析质量→固定库→业务→H800，规则见 [入口](./oer-scope-and-rules.md)，既有任务契约见 [任务基线](./llm-task-evaluation-baseline-20261012.md)。

## 1. 联合选择的结论

**Qwen3.8-27B 官方 FP8 继续是三模块共用主模型的第一顺位验证建议，但理由不能只依赖 Drafting。** 它具有完整原生256K、官方512K路线、实际FP8抽取实验与独立长文推理成绩；在 RAG 已提供相关材料的前提下，知识记忆与独立翻遍全库的压力下降，给定证据的条件推理、语义审查与依据回答更重要。这是由业务结构与公开证据作出的研究判断，三个模块的本项目成绩仍未闭合。

**Vetting 是当前联合选择中风险较高的一项。** 查询回答正确与审查发现完整分别评价；一次能找出正确问题，不能证明全部关键问题都找齐。Flash-Next 的同代专业任务提升信号使它仍是有价值的挑战者，不能因27B的抽取分数略高就排除更大路线。当前没有公开同输入 RAG 合同审查表证明 Flash-Next 必然优于27B，也没有证明二者已达到本项目全面审查门。

M3 官方 API 的已保存正面证据主要属于 Drafting。该经验不能直接填入 Vetting／Advisory 的质量格；同样，本机64K Bonsai的失败不能给官方FP8或H800足窗口的三个模块补填失败。一个共用模型需分别支持三类任务，不能由其中一类高分平均掉另一类的关键错误；本文不新增百分比采用线或业务权重。

## 2. 三个模块的实际任务与应看的证据

| 模块 | 输入与 LLM 责任 | 主要评价内容 | 较相关的公开代理证据 |
| --- | --- | --- | --- |
| Drafting | 项目资料与候选原始证据；提取值、完整清单并建议候选关系，最终合同仍由确认值及程序编译 | 值与对象、false／unknown、清单完整、required字段、连续原文引用 | ExtractBench／SOB、IFBench；M3与Bonsai已保存任务经验 |
| Vetting | RAG初筛及完整结构扩展后的tender／standard／project等角色化证据；判断真正冲突、遗漏、适用性并形成有据发现 | AND／OR、例外、最低量与精确量、同对象同范围、修订／Not used、漏报／误报、发现与引用完整 | AA-LCR、专业文档推理；CoWork／Job作为较宽的辅助信号，法律工作流与合同审查另核 |
| Advisory | RAG初筛、必要引用与纳入依据补充、问题及非合同背景；作针对性有据回答、缺证澄清 | 回答正确、依据支持、条件完整、来源采用、缺证unknown、误拒答、追问中的范围／版本一致 | AA-LCR、给定来源的grounded QA／faithfulness／answerability；工具／多轮成绩仅作辅助 |

Vetting与Advisory以用户最新确认的RAG初筛为设计前提，不由旧实验端点或未接通状态推翻。当前源码参与边界见 [源码与已有证据](./research/llm-task-baseline-current-evidence-20261012.md)；源码存在、schema通过或传输可达不等于真实业务质量。

## 3. 本轮重新读回的纸面分数

下面每行仅比较相同指标；独立机构执行、模型作者自报和抽取产品作者实验分别标明，不加成总分、不继承精度或推理模式。来源均于2026-10-12本轮重新读取。

| 指标及口径 | Qwen3.8-27B | 对照与分数 | 对三个模块的含义与限制 | 一手来源 |
| --- | --- | --- | --- | --- |
| AA-LCR v1.1 | Xhigh 82% | 3.6-35B reasoning 72%；GPT-OSS120 High 52% | 给定长资料多步推理信号，关联Vetting与Advisory；不是冲突召回、逐字引文或256K／512K验收 | [AA当前比较](https://artificialanalysis.ai/models/comparisons/qwen3-8-27b-vs-qwen3-6-35b-a3b)、[GPT-OSS同指标](https://artificialanalysis.ai/models/comparisons/qwen3-6-27b-vs-gpt-oss-120b) |
| GDP.pdf All-pass | Xhigh 17% | 3.6-35B reasoning 7%；GPT-OSS120 High 4% | 完整专业文档任务信号；不是字段准确率，也不是RAG初筛后合同审查得分 | 同上两页，指标标为Professional document reasoning / All-pass |
| ExtractBench overall／long value F1 | FP8 pipeline 89.75／38.45 | Flash-Next FP8 89.88／37.74 | 主要支持Drafting抽取筛选；long为>50页，不代表256K token | [固定CSV第44／43行](https://raw.githubusercontent.com/run-llama/ExtractBench/c972087fe7a5132132f7230d1bdb841d3fbf07ed/leaderboard.csv)、[pipeline身份](https://github.com/run-llama/ExtractBench/blob/c972087fe7a5132132f7230d1bdb841d3fbf07ed/docs/pipelines.md) |
| ExtractBench long precision／recall | 92.29／33.00 | Flash-Next 88.68／33.69 | 已返回项较准与大量漏项可以同时发生；不能拿引用已返回项证明完整审查或完整清单通过 | 同一固定CSV第44／43行，列P_Long／R_Long |
| IFBench | 79.5 | Flash-Next 81.3 | 输出指令／约束的辅助筛选，三模块均相关；不证明引用或判断事实正确 | [Flash-Next固定官卡Language表](https://huggingface.co/Qwen/Qwen3.8-Flash-Next/blob/de4b8e4d43b917e7706784d8bb445c9af86a3540/README.md) |
| CoWorkBench | 70.7 | Flash-Next 73.9 | 厂商内部长程办公任务，含法律等领域；不是专门的RAG后合同题，作较宽专业信号 | 同一官卡Language表及注5 |
| JobBench | 33.4 | Flash-Next 55.7 | 较大专业任务差距支持继续调查Flash-Next；不能将22.3分差称作Vetting提升或统计显著 | 同一官卡Language表Professional job tasks行 |

AA的当前页面说明AA-LCR v1.1、GDP.pdf对应任务，分数身份是Xhigh或High，未由这些网页证明本地官方FP8精度／引擎与该服务一致。更详细的方法版本、推理预算与不可比字段见 [独立／作者任务评测](./research/llm-independent-task-evaluations-20261012.md)、[Qwen任务表](./research/llm-task-benchmark-qwen-20261012.md)。不能把旧Qwen3.5卡的未注明版本AA-LCR 66.9与这里v1.1的82直接相减排名。

更贴近目标的任务表已经定向调查，缺口须保持明确：[Vetting专项](./research/llm-vetting-rag-task-evidence-20261012.md)核了ContractNLI、CLAUSE与Legal RAG Bench，分别涉及合同判定及证据、缺陷发现与解释、检索后有源回答，但没有当前候选同源的完整审查成绩；LegalBench-RAG只测IR，不能充当生成模型榜。[Advisory专项](./research/llm-advisory-rag-task-evidence-20261012.md)核了FACTS Grounding、RAGTruth、ALCE、RGB与FinanceBench，对当前准确模型的grounding／引用／缺证可比行仍未取得。FACTS当前Kaggle正文未能读回；unknown不等于0分或作者未发布。现有代理分数可以缩小候选，不能补写这些直接任务成绩。

## 4. RAG初筛怎样改变选型，又怎样保留风险

本项目保存运行还提供了与纸面分数不同的直接经验：

| 已保存任务 | 实际结果与来源 | 能说明什么 |
| --- | --- | --- |
| 2026-10-10首次Advisory Q1.6，configured MiniMax-M3 | `grounded=false / insufficient`、answer空；必要GCC正文未入实际返回集。[首次结果](./testing/oer-official-q16-first-business-result-20261010.md)、[检索读回](./testing/oer-official-q16-actual-retrieval-readonly-validation-20261010.md) | 首先是证据供应缺口，不能由未回答推定模型本身能力不足 |
| 2026-10-11后续Advisory Q1.6新M3调用 | 已保存53完整source单元输入，正常completed；source-only独审25pass／14partial／6fail，ledger32.0／45。[M3专项第5节](./research/minimax-m3-evaluation-reference-20261012.md)引用确切运行及独审收据 | 完整输入路径跑通后仍有条件／出口／引用残差；分项状态和加权ledger不是准确率或官方比赛分数，也不是当前整库RAG验收 |
| 2026-10-02 HOMES引用扩展后的直连M3 Vetting | 单次新20k材料请求返回1issue，机械accepted1，独立源审confirmed0／false1；实际实验`minimax-m3-homes-reference-closure-20k-20261002T1830Z`及收据身份在 [原报告](./vetting-reference-closure-20261003.md)保存 | 下一附录标题混入上一owner，模型进一步误读为页脚；是输入角色与语义误报的混合问题。只有一个已审候选，不报整库precision／recall，不用来否定所有M3能力 |
| 2026-10-10两源GCC／SCC Vetting | 17主题均partial，119来源补充请求未提交，0finding；运行终态completed。[保存终态](./testing/oer-official-two-source-vetting-completed-partial-20261010.md) | 局部机械完成和零发现不证明全面审查正确；这些库存数不构成模型precision／recall分母 |

上述旧运行保持日期、准确来源与既有状态，没有重新调用或改分。它们说明三个模块须分别评价，同时也不能把证据供应限制全部算成LLM语义失败。

RAG把大库初筛、候选排序交给检索链，LLM集中处理相关证据。与闭卷问答或从全库自行寻找事实相比，这种结构支持把当前能力较强的27B作为共用候选；实际改善幅度未测，不给出虚构成功率。

初筛只是候选筛选。进入模型的材料仍须保留完整主条款、定义、条件／例外、相反规定、引用链、跨页表组、source角色和已核采用状态，不能用若干孤立高分句子替代。Advisory需要题目相关依据；Vetting还需要覆盖各审查责任的检索与反证，不能把一次相关top-k当全面检查。

| 情况 | 检索／排包评价 | LLM评价 |
| --- | --- | --- |
| 原件中有关键例外，证据包未带入 | 漏召回或结构扩展缺口 | 若断言不存在例外，另计无依据断言；不能完全免责 |
| 主条款与例外均已带入，结论仍错误 | 该题的必要证据供应充分 | 条件／优先级推理失败 |
| 只找到一侧条文或当前采用未知 | 配对／纳入依据不足，coverage有限 | 应说明缺口；Vetting不得把差异直接报成确定冲突 |
| 没找到支持问题的依据 | 检索是否真的覆盖所需范围另评 | Advisory保留unknown／澄清；Vetting不把零发现当全面无问题 |
| 条款完整、结论正确，但引文不支持或不连续 | 来源包和定位按实际核查 | 生成／引用失败，schema合法也不通过 |

因此RAG检索质量、证据包后的模型能力和端到端结果分别记账。公开检索基准也不能充当LLM审查／回答榜单。两个专项证据入口为 [Vetting调查](./research/llm-vetting-rag-task-evidence-20261012.md) 与 [Advisory调查](./research/llm-advisory-rag-task-evidence-20261012.md)。

## 5. 当前候选的联合角色

| 候选 | Drafting | Vetting（RAG后） | Advisory（RAG后） | 当前建议 |
| --- | --- | --- | --- | --- |
| Qwen3.8-27B官方FP8 | 有实际公开FP8抽取成绩；本项目完整长窗未验 | 独立长文推理支持，发现完整性／反证与引文仍缺专门成绩 | 长文推理支持；有证回答、缺证校准、多轮与逐字引文未闭 | 三模块共用第一顺位验证，不记为已采用或已达到M3 |
| Qwen3.8 Flash-Next | 长文抽取未显示胜过27B | 同代专业任务更强的研究信号，尚非合同审查证明 | 指令与专业任务信号；grounded QA与缺证同口径表未闭 | 优先挑战复杂Vetting／Advisory，混合单卡另准入 |
| Qwen3.5-122B／Nemotron3 Super | 各自已有任务表，不能按大小优先 | 老表长文／多约束及Super长窗可作补充，不与v1.1硬拼 | 作为准确架构、量化与grounded任务的补充对照 | 只有针对当前残差的收益依据才提高优先级 |
| MiniMax M3官方API | 用户正面经验与本项目保存审计支持质量参照 | 有历史调用和局部审查限制，未建立完整通过成绩 | 有Q1.6实际调用与独立来源分项残差，未建立完整通过成绩 | 开发质量参照身份保留；Drafting体验不继承为另外两模块通过 |
| 本机Bonsai PTQ1_0 | 受限配方FAIL与短请求格式／引用残差保留 | 不由Drafting失败自动填失败 | 不由Drafting失败自动填失败 | H800完整窗口／精度／输入归因对照，未验 |

联合选型不预先要求三套模型：先判断一个候选是否能分别支持三个模块；某一模块出现明确关键缺陷，再按其任务证据比较更强模型或明确模块路由。RAG已由现有OER／embedding／rerank链承担，不新增LLM猜任务或改冻结Drafting流程。

## 6. 上下文与当前完成边界

用户至少256K、优选512K的要求保持。RAG减少送入模型的无关内容，不能据此悄悄降为短窗方案；必要完整条款／表组、多来源与历史、工具／模板、推理及最终输出共同计入总窗口。多数问题不必塞满512K，满窗有效性与正常请求质量仍分别评价。

原生256K、官方512K扩窗、推理模式与权重／KV精度分别固定；不把AA约100K输入的成绩或按页分桶的ExtractBench追认为目标长窗通过。保存对照至少区分充分证据、真实RAG包、故意缺证／反证／旧版干扰；这是后续评价契约建议，本轮没有执行实验、修改harness或新增业务阈值。

本轮完成三模块评价口径、公开分数读回、RAG责任分账与文档同步。最终采用、Vetting真实漏报／误报、Advisory真实grounded／unknown、单H800长窗和前端验收均未由本文完成；不干预并行原件质量任务。
