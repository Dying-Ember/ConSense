# Advisory 经 RAG 初筛后的 LLM 任务与选型证据

更新：2026-10-12，Asia/Shanghai。用户最新明确：LLM选型须同时评价Drafting、Vetting、Advisory；Vetting及Advisory均先经RAG初筛。本报告只补充Advisory的给定相关证据之后的回答任务，不能把Drafting成绩直接搬成Advisory成绩。

遵循 [范围规则](../oer-scope-and-rules.md) 与 [当前交接](../HANDOFF-ocr-embedding-rerank-20261008.md)。只读既有源码调查／保存证据和公开第一方benchmark；只新增本文，没有模型／API推理、下载权重、服务／测试／代码／harness变更，也未改scope或handoff。主方案至少256K、优选512K的总窗口要求保持；RAG使每题处理相关证据包，不要求每题无差别装入整个来源库。主机容量不作为本文的质量排序依据。

## 1. RAG相关性初筛之后，模型仍必须做什么

已核 [当前任务源码基线](./llm-task-baseline-current-evidence-20261012.md)第16–20行：Advice接收检索及incorporation补充证据、问题、scope和明确标记的非合同用户背景，输出`grounded`、`answer`、`citations`、`missingReason`、`clarifications`、`basisScope`。程序负责当前source session、检索、引用ID和采用／纳入关系门；无hits时直接unknown而不调用LLM；显式模型失败不能隐藏改派。本轮没有重验在线调用状态。

| RAG之后的责任 | 模型必须完成的判断 | 不能由初筛替代的检查 |
|---|---|---|
| 有证合成与直接回答 | 用相关条款回答实际问题，将事实、条件、结论与建议联系起来 | 有hits／高rerank分不等于证据足够，也不保证答到问题 |
| 条件、例外、完整流程 | 同主体／触发／时间／前提／出口，跨条款及完整表组共同解释 | 找到主规则却遗漏例外，仍可产生方向相反的答案 |
| 引用支持与覆盖 | 每个实质结论有足够证据，引用对应实际正文而非只对应标题 | citation ID合法不等于引文足以支持责任、期限或采用结论 |
| answerability | 有证时回答；缺少必要前提时unknown／请求具体澄清；能部分回答时划清边界 | 以空答案换低幻觉率、有证却拒答，或无证时用常识补项目事实 |
| 来源采用与scope | 分开当前项目已纳入依据、标准参考与非合同用户背景 | 上传顺序、日期或语义相关性不能自行确立合同采用／优先级 |
| 多轮澄清与修订 | 后续事实／source revision改变时重新判断，修正受影响结论与引用，不扩散未改部分 | 单轮正确、工具调用榜分、拥有`clarifications`字段均不证明多轮修订通过 |

业务判断中的“完整”是**完整必要相关证据及其条件闭包**，不是将全库逐字塞入每个问题。给模型的包仍须保留来源角色、采用状态、原文范围和必要例外；不足时应由程序补证或模型明确缺项，而非把部分unit冒称整条规定。

## 2. 五组第一方基准：各自能回答不同的问题

以下为2026-10-12只读调查。它们对本项目的映射是研究判断，不是benchmark作者对ConSense的验收。

| 第一方benchmark与方法来源 | 已核规模／评价对象 | 对Advisory的价值 | 必须保留的限制 |
|---|---|---|---|
| [Google FACTS Grounding原说明](https://deepmind.google/blog/facts-grounding-a-new-benchmark-for-evaluating-the-factuality-of-large-language-models/)；[FACTS v2说明](https://deepmind.google/blog/facts-benchmark-suite-systematically-evaluating-the-factuality-of-large-language-models/)及[作者论文](https://arxiv.org/abs/2512.10791) | 原版1,719题＝860public＋859private，文档最长32,000token；先判是否充分完成请求，再判是否完全有据；v2改进judge | 给定文档后的回答资格与grounding，能防止空答、泛答伪装忠实 | 原版排除数学／复杂推理；原版规模与长度不移给v2。FACTS总分混合Parametric／Search／Multimodal／Grounding，不能当Advisory grounding分 |
| [RAGTruth作者仓库](https://github.com/ParticleMedia/RAGTruth) | 2,965个source实例、17,790回答、7,664含幻觉回答、14,289标注span；QA部分989实例／5,934回答／1,724含幻觉回答；按回答及word span标注 | 分离unsupported、contradictory、null引发的问题；保留`incorrect_refusal`、`truncated`等状态 | 原回答来自六个旧模型。检测器span F1不是新回答模型的忠实率；`implicit_true`标注提醒“真实但源内未提及”仍需按本项目source-only规则判断 |
| [ALCE作者仓库](https://github.com/princeton-nlp/ALCE)与[引文评分源码](https://github.com/princeton-nlp/ALCE/blob/main/eval.py) | ASQA、QAMPARI、ELI5三类；分别评价fluency、correctness、citation质量；提供top100检索及oracle rerank资料 | 对句子引文支持、coverage与引文必要性做拆分，避免“引用很多”被当“引用正确” | 自动NLI／AIS有judge误差；通用QA不是合同采用。原文连续引用、来源revision和跨条款法律范围仍要另核 |
| [RGB作者仓库](https://github.com/chen700564/RGB)及[论文](https://arxiv.org/abs/2309.01431) | 中／英双语，四能力：噪声鲁棒、negative rejection、信息整合、反事实鲁棒；`all_rate`在noise<1时是accuracy、noise=1时是rejection，另有error detection | 初筛后仍有噪声、找不到答案、多片合成及冲突资料时，评价回答／拒答而非检索召回 | refined资料修正过原正负文档／答案，要绑定版本。反事实常识题的正确规则不能迁成“用常识改掉已采用特殊合同条款” |
| [FinanceBench作者仓库](https://github.com/patronus-ai/financebench) | 全体10,231问答／证据；开放150题；原论文16模型／上下文配置、2,400人工评审回答，含证据文档与页号 | 财务文档有证问答、数值／时间／单位、依据推导；区分retrieved context与有充分证据时的生成 | 属金融资料，不是合同资格／修订规则；旧GPT-4-Turbo＋retrieval的81%“错误或拒答”是联合失败，不能称幻觉率或给当前Qwen／M3作分数 |

RAGTruth数字来自README的task与model统计表；FinanceBench的150、16、2,400与81%来自作者摘要，且2,400＝150×16。引用以上规模只是说明评价分母与责任，没有用它们推算本项目成功率。

FACTS当前 [Kaggle Grounding v2页](https://www.kaggle.com/benchmarks/google/facts-grounding)在本轮只读抓取中未返回可核的正文／分数表。对目标模型的定向搜索也未取得可比一手行，因此保持unknown；这不证明它们没有公开成绩。v1、v2、FACTS Suite与Search v2均不能混名。

## 3. 当前候选的直接可比成绩：缺口不能用旧版或闭卷填满

本轮定向核查Qwen3.8-27B、Qwen3.8-Flash-Next、MiniMax-M3及Qwen3.5-122B-A10B在上述五组资料／现有任务研究中的身份。`unknown`表示未取得**该准确模型／制品／模式＋该版本指标**的可复核结果，不表示0分。

| 模型身份 | FACTS Grounding v2 | RAGTruth回答忠实性 | ALCE引文质量 | RGB四能力 | FinanceBench给定证据QA | 对当前选择的含义 |
|---|---|---|---|---|---|---|
| Qwen3.8-27B／官方FP8须另记制品 | unknown | unknown | unknown | unknown | unknown | 不可用AA-LCR或抽取F1替代Advisory引用／拒答通过 |
| Qwen3.8-Flash-Next／官方FP8 | unknown | unknown | unknown | unknown | unknown | 专业任务优势是研究信号，尚无当前同包grounded QA胜过27B的证据 |
| 完整MiniMax-M3官方API | unknown | unknown | unknown | unknown | unknown | 有下节本项目Advisory直接经验；不是已通过五榜或全部Advisory |
| Qwen3.5-122B-A10B；GPTQ4需另记 | unknown | unknown | unknown | unknown | unknown | 旧版任务成绩及原精度不能给新版本或GPTQ4继承；保有针对性对照价值 |

RAGTruth原六模型为GPT-3.5-turbo-0613、GPT-4-0613、Llama2三尺寸及Mistral7B；未把后来训练的hallucination detector成绩视为这些目标generator的回答质量。ALCE与FinanceBench的原旧基线也不能因模型家族相同而移给当前候选。

**仍有可用但不等价的公开信号**，沿用已核 [Qwen任务调查](./llm-task-benchmark-qwen-20261012.md)与[外部任务评价](./llm-independent-task-evaluations-20261012.md)，不新增模型运行：

| 公开信号与准确来源 | 已核数字 | 可以支持的纸面判断 | 不能改名成什么 |
|---|---|---|---|
| [AA同页：Qwen3.8-27B Xhigh／non-reasoning](https://artificialanalysis.ai/models/comparisons/qwen3-8-27b-non-reasoning-vs-qwen3-8-27b)；既有研究日口径AA-LCR v1.1 | 82%／69% | 新27B存在长文多步推理证据，推理配方需单列 | 有证引用准确率、Advisory通过率或256K／512K可靠性；v1.1约100K输入，不能填较长窗口 |
| [Flash-Next固定官方同表](https://huggingface.co/Qwen/Qwen3.8-Flash-Next/blob/de4b8e4d43b917e7706784d8bb445c9af86a3540/README.md)，Flash／27B | JobBench55.7／33.4；CoWork73.9／70.7；IFBench81.3／79.5 | 专业／办公／多约束任务的挑战者价值 | RAG生成grounding、来源采用或多轮澄清质量 |
| [旧Qwen3.5-122B官方卡](https://huggingface.co/Qwen/Qwen3.5-122B-A10B)；既有同表口径 | AA-LCR66.9 | 旧模型也有长文推理对照价值 | 当前3.8、GPTQ4、ALCE或FACTS成绩；不同AA版本／设置不硬拼排名 |
| [ExtractBench固定CSV](https://github.com/run-llama/ExtractBench/blob/c972087fe7a5132132f7230d1bdb841d3fbf07ed/leaderboard.csv)，27B FP8／Flash FP8 | Long F1 38.45／37.74，Long recall33.00／33.69 | 长资料抽取漏项值得警惕，Flash升级不能直接消除漏项 | 相关证据包QA正确率；Long为>50页，非256Ktoken |

闭卷MMLU／GPQA／SimpleQA、coding、综合智能指数均不补齐这个直接矩阵。多轮工具基准可以补交互信号，但任务scaffold、工具和用户模拟不同，本轮没有该准确候选在Advisory式澄清／来源修订上的可比成绩。

## 4. M3 已有一次真正的 Advisory 证据，且保留实际失败

[M3专项](./minimax-m3-evaluation-reference-20261012.md)第63–66行及 [handoff](../HANDOFF-ocr-embedding-rerank-20261008.md)当前“新Q1.6不同作者逐项评分”增量记录了2026-10-11真实生成，不是仅有Drafting体验：

- Q1.6的53个冻结完整source单元正文已进prompt；厂商input83,868、output2,647、total86,515 token，generation32.1365675秒，正常completed。这些是保存运行身份，不是本轮调用或当前服务状态。
- 独立source-only45项为 **25pass／14partial／6fail**，积分ledger **32.0／45**；不是32.5／45，不转成accuracy／比赛分数。独审SHA为`082366681b6371b63b7b447ba82b597d3c0402d059509f34f264a9023b84375a`，provider response SHA为`a403634ce8e021f7eeef16056d8f17ab04280bc9b7e1e9488f1fc40faac14c04`。
- 实际残差包括timely Notice分支接错、前提／报告／appoint-failure／inadmissibility／confidentiality缺漏，以及引用替换标题而非有全文的source；部分90days事件已正确，不能沿用更早失败。
- 输出2,647低于已记录16,384预算且正常completed，现有证据不支持把所有遗漏归因输出cap。前端完整可读性、剩余端到端耗时和官方八题均另有未验项。

这一观察支持把M3作为Advisory质量参考及失败归因样本，并说明RAG／完整证据已供应后仍会有生成层条件、流程与引用错误。它**不是**当前候选在同一冻结RAG包下的受控排名，也不把这一个问题外推全Advisory、Vetting或新项目。

## 5. 当前纸面选择：按Advisory责任暂定，未记为采用

**第一顺位验证建议仍可保留Qwen3.8-27B官方FP8，但只是一条有长文推理信号的候选路径。** 82% AA-LCR提供优先核查的理由，五类直接grounding／citation／answerability成绩及本项目Advisory均未取得。必须让它面对RAG初筛后的同包有证合成、例外闭包、unsupported及引文，而不能由Drafting表或JSON能parse宣布可接管Advisory；Bonsai三进制失败与FP8身份分开。

**M3继续作为Advisory强参考，保留Q1.6的实际未通过状态。** 用户认可的Drafting体验不代替此任务成绩；也不能把M3自己输出当gold。Flash-Next作为专业任务质量挑战者，重点看是否改善条件／流程及相关证据合成，而不是只追总分或规模；当前没有同包grounded QA胜出的证据。旧122B保留有针对性对照，新旧checkpoint和实际量化配方分别评价。

目前没有足够证据替三个模块确定同一个最终主模型，也没有证据宣布须为Advisory新增一套模型。先按各任务错漏决定是否需要分工，保持模型切换及服务采用尚未发生的实际状态。

## 6. RAG后评价应记录的证据，避免把上游和模型混成总分

同一题保留两条对应轨道：**冻结充分相关证据包**用于隔离生成能力；**实际OER＋RAG包**用于整链业务。RAG池／rerank／closure变化不是纯模型替换。按实际输入分母分列：

1. 充分可答：直接结论、条件／例外、完整流程和逐主张引用支持；同时记必须出现的关键项与遗漏，不能让高precision掩盖低coverage。
2. 不可答／部分可答：拒绝无据项目断言、准确`missingReason`、最少必要澄清；有证误拒和无证错答分别记，不能全部拒答取得高忠实分。
3. 初筛噪声：不相干高相似片、主规则缺例外、相互矛盾、标题代全文、未采用标准及旧revision；RAG供应缺口与模型越界可同时判失败。
4. 来源采用：当前已纳入条款、一般参考及用户背景保持角色；不是日期较新／排位较高就优先。引用合法、引用支持、当前来源可用分别报告。
5. 多轮修订：澄清后新增的事实、替换的证据、来源session变更和历史答案分别核验；旧引用不得继续充当新source依据。本轮只定义评价责任，没有完成该多轮流程验收。

这些是候选必须证明的行为及既有残差映射，不是新增实现、测试或已批准的执行配方。总token含模板、相关证据、历史、推理及最终输出；短包成功不证明256K／512K上限可靠，但不能为了凑窗口把无关资料当必需输入。当前解析质量→固定库→业务→H800顺序、Drafting冻结及新运行0保持。
