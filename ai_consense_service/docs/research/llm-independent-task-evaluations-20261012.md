# ConSense LLM 任务评测：独立评测、作者实验与实际经验

研究日期：2026-10-12。本文只研究公开证据，不调用模型、不下载权重、不检查或改变服务/GPU，也不修改评测 harness。它补充本项目的任务基线，不代表已完成 ConSense 原件上的候选模型验收。

## 1. 先固定决策边界

- 主模型上下文硬门槛是 **至少 256K，优先 512K**。原生长度、扩展配置、实际可靠长度分别记录；“支持 1M”不能替代长文任务质量证据。
- 用户已经试过本地 27B 并判定不适用。项目记录中的 Bonsai 2 是 **Qwen3.8-27B ternary**；下面的公开 Qwen3.8 FP8/供应商/API成绩不能推翻这一实际结果，也不能把旧 Qwen3.6-27B重新默认为首选。
- 用户反馈完整 MiniMax M3 官方 API 的 Drafting 效果好，保留为本任务质量参照。其他法律基准中的“合同起草”可能涉及另写条款、文书交付、谈判建议，不能直接等同于 ConSense 的来源抽取。
- 按任务选择模型：完整列举、严格 JSON、逐项原文证据、细微差异判断、缺项时弃答分别有自己的失败条件。参数量和综合智能榜不承担这些验收条件。
- OCR 与 LLM 不需要同时运行；按阶段卸载已经接受，本文不重新讨论该业务决定。

## 2. 证据层级及适用性

| 证据 | 实验执行者 / 利益关系 | 能证明什么 | 不能证明什么 |
| --- | --- | --- | --- |
| Artificial Analysis（AA）的 AA-LCR、GDP.pdf、Omniscience | 独立模型评测机构自行执行 | 同一评测版本、推理配置下的任务相对表现 | ConSense 的严格 JSON、原文逐字引用、256K/512K实际可靠性 |
| SOB 论文 | Interfaze / JigsawStack 作者，表中包括自家 Interfaze-Beta | 固定样本和输出协议下的结构与值质量 | 中立实验室结论、所有结构化解码方式的统一排名 |
| ExtractBench 论文与仓库 | runllama.ai 作者，表中包括自家产品 | 企业文档抽取、长列表、跨页表格及 grounding 的明确切片 | Bonsai ternary质量、256K/512Ktoken质量、完整任务通过率 |
| Qwen / DeepSeek 官方模型卡 | 模型供应商 | 权重/架构/上下文声明、部署配置和自报分数 | 外部独立复核、目标机器上的服务验收 |
| Glassmkr 生产任务实验 | 应用公司自行比较，样本很小 | JSON合格仍可能内容失真、推理预算与延迟的实际关系 | 法律任务或长上下文普遍排名 |
| ConSense 本地历史与用户反馈 | 本项目实际使用 | 当前任务和当前推理配方的实际成败 | 不同量化、不同引擎或另一版本权重必然同样成败 |

这里的“作者实验”是可检查的第一手证据，但带产品利益关系；不把它包装成中立独立实验室测评。

## 3. ExtractBench：当前高总分仍掩盖长文严重漏项

### 3.1 论文 v1 的规模、指标与解码方式

[ExtractBench 论文 v1](https://arxiv.org/html/2607.29677v1)，2026-07-31，作者 Boyang Zhang、Adrian Lyjak、Eli Stewart、Zhaoqi Li、Simon Suo（runllama.ai）。数据有 **370份文档、4,869页、67种文档类型、8个业务领域**。

主要指标为 **value F1**：规范化值后计算每文档 precision/recall/F1，再对文档取非加权平均。“Long”是 **超过50页**，不是256K/512Ktoken。数组通过 Hungarian 匹配当作无序记录；省略标量键按 null处理。因此 overall不是逐字段准确率，也不是整份输出完全正确率，不直接覆盖 ConSense 的顺序约束。正确值与页/框的 grounding另算。

论文 v1 Table 2，单位为 value F1（%）：

| 系统名称（按论文） | Overall | Short | Medium | Long >50页 | T1长列表 | 跨页表格 | D7法律 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Qwen3.6 35B-A3B | 87.3 | 93.1 | 84.8 | 26.8 | 79.0 | 73.8 | 58.8 |
| Gemma4 26B | 66.2 | 80.5 | 40.5 | 12.2 | 51.1 | 40.5 | 17.9 |
| Lift9B | 77.3 | 87.2 | 62.6 | 25.3 | 68.6 | 64.5 | 42.2 |
| NuExtract3 | 47.9 | 54.4 | 39.3 | 8.9 | 31.8 | 37.6 | 56.0 |

Appendix C的协议并不一致：Qwen3.6用 vLLM `json_schema` / xgrammar，Gemma4用提示词 schema加 `json_object`，Lift用官方 SDK guided JSON，NuExtract3用原生抽取模板。不能把四行解释成统一约束下的纯模型能力排名。**Lift9B不是Qwen9B。**

### 3.2 2026-10-12可读到的仓库版本与论文不同

当前仓库固定在提交 **`c972087fe7a5132132f7230d1bdb841d3fbf07ed`**。来源：[leaderboard.csv](https://github.com/run-llama/ExtractBench/blob/c972087fe7a5132132f7230d1bdb841d3fbf07ed/leaderboard.csv)；[原始CSV](https://raw.githubusercontent.com/run-llama/ExtractBench/c972087fe7a5132132f7230d1bdb841d3fbf07ed/leaderboard.csv)。以下为该版本的相应行，不能与论文v1数值拼接：

| CSV系统名称 | Overall F1 | Short F1 | Medium F1 | Long F1 | Long precision | Long recall |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Qwen3.8 27B | 89.75 | 94.68 | 87.54 | 38.45 | 92.29 | 33.00 |
| Qwen3.8 Flash Next | 89.88 | 94.82 | 87.81 | 37.74 | 88.68 | 33.69 |
| Qwen3.6 35B | 88.11 | 92.86 | 86.37 | 36.90 | 90.57 | 31.73 |
| Qwen3.5 35B | 88.00 | 93.52 | 85.27 | 31.73 | 81.07 | 26.83 |
| Qwen3.5 9B | 86.10 | 92.31 | 81.12 | 32.22 | 85.52 | 27.22 |
| Gemma4 26B | 70.27 | 77.70 | 59.61 | 28.88 | 50.10 | 25.56 |
| Gemma4 E4B | 69.64 | 81.10 | 48.93 | 26.82 | 71.15 | 25.45 |
| Gemma4 E2B | 51.85 | 63.71 | 28.96 | 14.58 | 67.85 | 11.84 |
| Gemma4 12B | 45.39 | 53.62 | 30.82 | 13.16 | 44.89 | 14.44 |

这些行的 Word_Grounding / Page_Grounding均为0，表示相应 pipeline没有交付该类定位证据，不能解释成所有值均为幻觉。

[同一提交的 pipeline说明](https://github.com/run-llama/ExtractBench/blob/c972087fe7a5132132f7230d1bdb841d3fbf07ed/docs/pipelines.md)显示：

- Qwen3.8 27B使用 `Qwen/Qwen3.8-27B-FP8`，自托管 vLLM，one-shot，`json_object`。
- Qwen3.6 35B使用 `Qwen3.6-35B-A3B-FP8`，vLLM，`json_object`；这与论文v1的 `json_schema`不同。
- Gemma4 26B使用 Gemma4-26B-A4B，vLLM，`json_object`。
- 根作者收尾复核同一固定CSV与pipeline后补充：Qwen3.5 35B为官方FP8，Qwen3.5 9B pipeline只声明`Qwen3.5-9B`，二者vLLM／`json_object`。9B长文F1 32.22与35B31.73差异小，未见区间，不能称显著提升；不能给9B虚构FP8标签。

[Qwen3.8-27B官方模型卡](https://huggingface.co/Qwen/Qwen3.8-27B)也展示了上述89.75/94.68/87.54结果，标明FP8 pipeline。供应商页面转载不能改变原实验的性质，也不能变成本地 ternary配方的质量证据。

**对 ConSense最有价值的信号是漏项：**Qwen3.8 FP8 overall 89.75，与长文档F1 38.45、recall 33.00同时成立；Qwen3.6 35B的长文recall也只有31.73。这支持将“找得到的项目引用正确”和“把应有项目全部找齐”分开验收。它不支持直接宣布任一模型满足本项目完整列举任务。

## 4. SOB：小而新可能胜过大而旧，且源类型会改变排名

[Structured Output Benchmark论文v1](https://arxiv.org/html/2604.25359v1)，2026-04-28。作者 Abhinav Kumar Singh、Harsha Vardhan Khurdula、Yoeven D Khemlani、Vineet Agarwal，Interfaze / JigsawStack。包含 **5,000条文本、209条图像/OCR、115条音频转写**；主要比较21个模型，音频20个。

各模态先转成文本上下文；OCR行不是模型直接看原始图像。主协议temperature 0、输出上限2,048、可关时关闭reasoning，schema放提示词；不是统一原生 constrained decoding。JSON Pass表示parse与schema验证通过；Value Accuracy逐gold叶路径精确比对，数组索引与顺序有意义；Perfect Response才是完整输出精确正确。

Table 4文本任务；模型别名照论文，不推定未披露的checkpoint后缀：

| 模型别名 | JSON Pass | Value Accuracy | Perfect Response |
| --- | ---: | ---: | ---: |
| GLM-4.7 | 97.2% | 83.0% | 52.6% |
| Qwen3.5-35B | 97.4% | 82.8% | 51.9% |
| Qwen3-235B | 98.2% | 81.1% | 48.1% |
| Gemma-4-31B | 94.5% | 79.8% | 47.7% |
| Gemma-3-27B | 97.5% | 80.3% | 47.1% |
| Qwen3-30B | 98.8% | 77.8% | 41.4% |
| GPT-OSS-20B | 85.8% | 69.3% | 37.6% |

Table 5 OCR文本任务中的对应结果：

| 模型别名 | JSON Pass | Value Accuracy | Perfect Response |
| --- | ---: | ---: | ---: |
| Gemma-4-31B | 99.5% | 67.2% | 11.3% |
| Qwen3-235B | 90.7% | 55.2% | 7.8% |
| Qwen3.5-35B | 85.4% | 55.0% | 9.8% |

Qwen3.5-35B在文本值和完整响应上超过旧Qwen3-235B，是“新小模型可胜旧大模型”的具体任务证据；OCR文本则换了排名。表中没有Qwen3.8、M3或Qwen9B，不据此为它们填分。原生schema消融只覆盖3模型/115音频样本，不能推广成所有场景的统一结论。

## 5. Artificial Analysis：使用任务分数，不拿综合榜代替

来源为评测执行方自身的[方法说明](https://artificialanalysis.ai/methodology/intelligence-benchmarking)，当前页面标示 Intelligence Index v4.3.2。采用 **AA-LCR v1.1**，不与v1.0、旧模型卡或旧综合指数小数混排。

| 任务 | 样本与协议 | 对 ConSense 的用途 / 限制 |
| --- | --- | --- |
| AA-LCR v1.1 | 100题、每题3次，约100k输入token（cl100k_base），7类文档包括法律；更新16个答案键及judge | 长文推理参考；长度不足以验证256K/512K；v1.0不可直接比较 |
| GDP.pdf | 100任务、10领域、4,592 PDF页、1,275标准，每任务5次，即每模型500次尝试 | 多文档交付完整性；headline是All-pass，Mean Pass另算。AA用LiteParse/OCR文本并给支持图像的模型图像，与Surge原始PDF流程不同 |
| AA-Omniscience | 6,000道闭卷知识题；指数-100到100，奖励正确、惩罚错误、弃答不罚 | 错答/弃答取舍参考；不是给定合同来源的grounded QA |
| IFBench | 294题×5次；AllenAI官方verifier，loose模式；v4.1起单列 | 指令遵从参考；loose模式不能代替本项目bare-JSON严格协议 |
| Harvey LAB-AA v1.1 | 120个私有任务、24法律领域，sandbox交付；Hallucination-Gated All-Pass | 法律工作流参考；已读页面未显示可核对的当前小模型数值，因此不填分 |

以下各值取同一当前AA比较页面所显示的四舍五入值。方括号来源对应下一张链接表；不把不同reasoning配置合为一行。

| 模型 / 配置 | AA-LCR v1.1 | GDP.pdf All-pass | Omniscience指数 | 来源 |
| --- | ---: | ---: | ---: | --- |
| Qwen3.8-27B Xhigh | 82% | 17% | -10 | A、B |
| Qwen3.8-27B non-reasoning | 69% | 5% | -8 | B |
| Qwen3.8-27B Low | 77% | 10% | -27 | C |
| Qwen3.8-27B Medium | 80% | 13% | -36 | C |
| Qwen3.6-27B reasoning | 77% | 11% | -20 | D、E |
| Qwen3.6-35B-A3B reasoning | 72% | 7% | -22 | A、F |
| Gemma4-31B reasoning | 70% | 6% | -48 | E、F |
| GPT-OSS-120B High | 52% | 4% | -49 | D |
| DeepSeek-V4-Flash 0731 Max | 80% | 11% | -14 | G |
| Nemotron3-Nano-30B-A3B reasoning | 38% | 1% | -52 | H |
| Nemotron3-Nano-30B-A3B non-reasoning | 11% | 页面未显示 | -69 | H |

| 来源 | AA原始比较页 |
| --- | --- |
| A | [Qwen3.8-27B与Qwen3.6-35B-A3B](https://artificialanalysis.ai/models/comparisons/qwen3-8-27b-vs-qwen3-6-35b-a3b) |
| B | [Qwen3.8 non-reasoning与Xhigh](https://artificialanalysis.ai/models/comparisons/qwen3-8-27b-non-reasoning-vs-qwen3-8-27b) |
| C | [Qwen3.8 Low与Medium](https://artificialanalysis.ai/models/comparisons/qwen3-8-27b-low-vs-qwen3-8-27b-medium) |
| D | [Qwen3.6-27B与GPT-OSS-120B](https://artificialanalysis.ai/models/comparisons/qwen3-6-27b-vs-gpt-oss-120b) |
| E | [Qwen3.6-27B与Gemma4-31B](https://artificialanalysis.ai/models/comparisons/qwen3-6-27b-vs-gemma-4-31b) |
| F | [Qwen3.6-35B-A3B与Gemma4-31B](https://artificialanalysis.ai/models/comparisons/qwen3-6-35b-a3b-vs-gemma-4-31b) |
| G | [DeepSeek-V4-Flash与Qwen3.6-27B](https://artificialanalysis.ai/models/comparisons/deepseek-v4-flash-vs-qwen3-6-27b) |
| H | [Nemotron3-Nano non-reasoning与reasoning](https://artificialanalysis.ai/models/comparisons/nvidia-nemotron-3-nano-30b-a3b-vs-nvidia-nemotron-3-nano-30b-a3b-reasoning) |

这些结果再次反驳“参数越大越适合本任务”：27B可以在这些任务上高于旧GPT-OSS-120B。GPT-OSS的131K上下文又低于当前主模型硬门槛，排名不能消除门槛。Qwen3.8增加reasoning改善LCR/GDP，却没有单调改善Omniscience；推理强、少漏项、少错答是不同维度。上述任何公开Qwen成绩均不重置本地Bonsai FAIL。

## 6. 上下文声明与长文质量证据必须分开

| 候选 / 官方来源 | 声明或配置 | 当前任务证据的实际覆盖 |
| --- | --- | --- |
| [Qwen3.8-27B](https://huggingface.co/Qwen/Qwen3.8-27B) | 原生262,144，YaRN可扩至1,000,000；524,288的示例建议factor 2 | AA-LCR约100k；ExtractBench按页切片。均没有证明512K原件的完整抽取 |
| [Qwen3.6-27B](https://huggingface.co/Qwen/Qwen3.6-27B)、[35B-A3B](https://huggingface.co/Qwen/Qwen3.6-35B-A3B) | 原生262,144，并给出扩展配置 | 符合声明层面的256K门槛；不因扩展配置就视为512K业务已验收 |
| [DeepSeek-V4-Flash](https://huggingface.co/deepseek-ai/DeepSeek-V4-Flash) | 官方声明1M；Flash总284B、active 13B，专家FP4/其余FP8 | AA上表是0731 Max版本，不能悄悄与0424模型卡自评分合并；也不声称单H800可部署 |

Qwen3.8官方自报IFBench 79.5，Qwen3.6-27B为69.1。它们是供应商表格分数，不能当作AA loose模式的同源独立分数。当前主模型审查要同时有：确切checkpoint/量化/引擎、服务接受的长度、输入加输出预算、长原件上的引用与完整性证据。

## 7. 合同任务与grounding：哪些基准真的接近

| 第一手来源 | 与本项目相近的任务 | 使用边界 |
| --- | --- | --- |
| [Stanford ContractNLI](https://github.com/stanfordnlp/contract-nli) | 对合同断言给entailment / contradiction / not mentioned，并找证据 | 适合Vetting的矛盾/未提及区分；不是完整条款清单抽取，也没有本轮新候选统一实测 |
| [CUAD / Atticus](https://www.atticusprojectai.org/cuad/) | 510合同、13k+标注、41类条款，律师参与监督 | 适合条款定位/抽取；不能代表跨来源冲突判断与本项目“应有但缺失”的业务规则 |
| [Better Call CLAUSE（EACL Findings 2026）](https://aclanthology.org/2026.findings-eacl.305/)、[论文](https://arxiv.org/html/2511.00340v1) | 7,500+扰动合同、10类异常，细微合同差异检测 | 接近Vetting；论文模型世代较旧，未给Qwen3.8/M3当前对照。完整性和误报需独立看 |
| [IFBench / AllenAI](https://github.com/allenai/IFBench) | 58类分布外约束的可验证遵从 | 可补充协议压力；不能替代契约证据与抽取完整性 |
| [FACTS Grounding原论文](https://arxiv.org/abs/2501.03200) | 要求回答忠实于给定来源 | 比闭卷Omniscience更贴近grounding；本轮没有核对到新候选统一分数，不引用第三方Gemma分数 |

本轮完成的是公开任务证据整理。并没有把上述数据集跑在ConSense候选上；公开“法律高分”也不能自动证明香港工程投标材料的同类判断。Vetting应继续区分：真实差异命中、普通条款误报、来源不存在时补造、引用位置正确但主体缺失、长列表漏项。

## 8. 实际使用报告：有效JSON和内容正确仍是两回事

[Glassmkr公司自身的比较](https://www.glassmkr.com/blog/qwen3-6-vs-gemma-4-infrastructure-narration)用真实约6,500token的基础设施叙述任务，每配置3次，L4 / llama-server，Gemma4-26B-A4B Q4与Qwen3.6-35B Q4对照：

| 配置 | JSON成功 | 风险判断正确 | 延迟 |
| --- | ---: | ---: | ---: |
| Gemma4-26B-A4B | 3/3 | 3/3 | 16.2秒 |
| Qwen3.6-35B，non-thinking | 3/3 | 2/3 | 11.2秒 |
| Qwen3.6-35B，thinking | 3/3 | 3/3 | 79.9秒 |

初始thinking被4,096输出上限截断，后扩大至16,384；两家都出现编造缩写展开。作者将一次风险误判归为可用prompt修补，但没有展示修补后的完整复验，不能把归因当已证实。这个小样本不决定法律模型排名，却支持将JSON、语义、截断和延迟分别记录。

## 9. 对当前选型的可执行结论

1. **退出已经失败的本地Bonsai 27B推理配方作为默认主模型。**公开Qwen3.8 FP8值得研究，但只有更换明确checkpoint/量化/引擎后才能构成新候选；当前实际FAIL仍然保留。
2. **不按大小排序，不自动退回旧27B。**SOB的新35B胜旧235B、AA的27B胜旧120B提供任务级反例；同时ExtractBench与SOB说明模型家族/源类型/结构化协议会改变排序。
3. **M3官方API继续作为Drafting质量参照。**API参照与本地部署是两项选择；跨任务法律综合榜不否定用户已观察到的抽取效果。
4. **长文完整性是公开证据中最明显的未过门槛。**较高整体抽取分数和高precision可以与约三成的长文recall并存。候选必须证明整份应有列表、跨节来源、缺失输入时弃答，不能只展示几个正确引用。
5. **256K声明是筛选条件，512K质量是另外的证据。**AA的约100k测试不足；页数基准也不能替换token压力。Gemma或其他家族若尚未核对主模型上下文和对应checkpoint，仍是待核候选。
6. **当前可以缩小候选和指明失败维度，不能宣布最终主模型已验收。**本文提供已完成的公共模型成绩与真实经验，不把下一步评测计划冒充现有质量结果。最终部署容量、分阶段加载和显存策略由部署研究主文结合目标硬件处理。

## 10. 可复核来源清单

- ExtractBench论文：arXiv `2607.29677v1`，2026-07-31；当前仓库证据固定SHA `c972087fe7a5132132f7230d1bdb841d3fbf07ed`，CSV和pipeline链接见第3节。
- SOB论文：arXiv `2604.25359v1`，2026-04-28；规模、Table 4/5、原生schema消融见第4节。
- AA方法页与8个比较页：2026-10-12公开页面，Index v4.3.2 / AA-LCR v1.1；数值取同一当前比较页面显示值。动态页面可能继续更新，本文保留研究日版本口径，不跨版本混排。
- 官方模型卡仅用于声明/自报，不与独立任务表混为同一证据层。Bonsai本地FAIL和M3使用反馈采用当前用户约束，不宣称本轮重新运行过模型。
