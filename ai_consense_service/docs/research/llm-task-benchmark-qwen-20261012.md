# Qwen 当前模型的任务评测、实际失败与长上下文门槛

日期：2026-10-12，Asia/Shanghai。依据用户最新要求：选型先看任务相关榜单及实际业务经验，**至少256K总上下文，优选512K**；新小模型可以胜旧大模型，不按显存或参数先排胜负。本轮仅读取公开官方模型卡、benchmark作者资料与仓库已保存结果，新增本文；未调用模型/API、读取密钥、修改harness／其它文件或部署。

## 1. 对本项目有用的结论

**用户已试过并认为不足的27B，是Qwen3.8-27B底座的Bonsai 2三元版本。它在本机完整Drafting任务仍有实际失败；不能把27B当作尚未尝试的默认下一步。** 用户最新补充指出本机VRAM与上下文限制也可能影响结果；现有失败约束的是当时完整配方，不能当模型能力上限或否决H800完整上下文中的Bonsai。当前筛选必须同时尊重这个实际证据和最新3.8公开任务成绩。高精度3.8-27B及完整长窗Bonsai保留为上下文／精度／协议受控对照，均未证明足够；Flash-Next则先过部署资源准入，不能由名字中的Flash或6B active推成小模型。身份、评分和原始失败见[本机Bonsai任务证据](bonsai-local-task-evidence-20261012.md)。

最新3.8官方卡公布了IFBench和办公／专业任务指标；LlamaIndex提供者公开提交的FP8提取／解析成绩又显示：27B ExtractBench均分89.75，但长文子集仅38.45；Flash-Next均分89.88，长文反而37.74。**总分和大参数均不足以解决长清单与跨来源任务。** 这支持先按遗漏、来源连续性、schema和条件闭包选型，不能用宣传平均保留率覆盖本机残差。

第2至4节保留3.5／3.6同表作为“新小模型可以胜旧大模型”的历史证据案例，**不以它们代替当前3.8筛选，也不把3.5分数继承给新版本**。已核官方公开3.8清单没有9B或35B；3.5-9B、3.6-35B-A3B只可作有既有任务证据的其它候选，不虚构3.8小checkpoint。

当前3.8-27B和Flash-Next原生窗口都是262,144 tokens，官方YaRN factor2路线为524,288，factor4命令cap为1,000,000。**本轮没有原生512K或本项目256K／512K长文正确性的已验结论。** 总上下文包括system、来源、历史、保留的思考及生成，预算须先给完整输出留位；扩窗参数、成功不OOM和业务正确分开。

### 1.1 当前3.8公开身份与资源准入

来源是2026-10-12读取的[Qwen官方HF模型清单](https://huggingface.co/api/models?author=Qwen&search=Qwen3.8&limit=100)，返回以下三个架构各自原checkpoint与FP8包，共六项。清单不是所有未来／私有型号的证明；本轮未核到公开`Qwen3.8-9B`或`Qwen3.8-35B`。

| checkpoint | 固定revision | 官方身份／窗口 | 对单H800的决策含义 |
| --- | --- | --- | --- |
| [Qwen3.8-27B](https://huggingface.co/Qwen/Qwen3.8-27B/blob/1d4bf0f2ff6012fd82039f2fa52739d0dd7c60c0/README.md) | `1d4bf0f2ff6012fd82039f2fa52739d0dd7c60c0` | 27B dense hybrid；原生262,144；Apache-2.0 | 同Bonsai底座的精度受控候选，不是已通过主模型 |
| [Qwen3.8-27B-FP8](https://huggingface.co/Qwen/Qwen3.8-27B-FP8/tree/017b9c7af6b5689d5dd426a76e0bc077eb5ca20a) | `017b9c7af6b5689d5dd426a76e0bc077eb5ca20a` | 官方FP8包；下节提供者实际服务checkpoint | FP8公开成绩不能叫BF16成绩或Bonsai成绩；完整长窗口资源仍须另验 |
| [Qwen3.8-Flash-Next](https://huggingface.co/Qwen/Qwen3.8-Flash-Next/blob/de4b8e4d43b917e7706784d8bb445c9af86a3540/README.md) | `de4b8e4d43b917e7706784d8bb445c9af86a3540` | 125B／6B active，另51B n-gram embedding＋4B MTP；原生262,144；Qwen Community 1.0许可 | 不是35B小MoE；active数量描述计算，不能当驻留权重大小 |
| [Qwen3.8-Flash-Next-FP8](https://huggingface.co/Qwen/Qwen3.8-Flash-Next-FP8/tree/236dfdf285828023ca3bcd3f37366c58a3469b13) | `236dfdf285828023ca3bcd3f37366c58a3469b13` | 提供者ExtractBench服务包；官方索引131个shards | 权重索引total_size **185,502,232,570 bytes＝172.7624 GiB**，已超过80GiB；不能单H800全驻留 |
| [Qwen3.8-2.4T-A95B](https://huggingface.co/Qwen/Qwen3.8-2.4T-A95B/blob/207bd685a7e3696cfaff12ded7c6a7ea0f88c996/README.md) | `207bd685a7e3696cfaff12ded7c6a7ea0f88c996` | 2.4T／95B active；原生262,144、可扩1,010,000；官方表列hosted Qwen3.8-Max | 作为质量参考；不能把hosted Max成绩当本地checkpoint及单卡配方验收 |

Flash-Next权重数来源为[固定FP8索引的metadata.total_size](https://huggingface.co/Qwen/Qwen3.8-Flash-Next-FP8/resolve/236dfdf285828023ca3bcd3f37366c58a3469b13/model.safetensors.index.json)，换算分母为2^30。它是tensor存储总量，不是包含cache／workspace的运行显存；部分组件可offload是另一引擎／延迟方案，不能抹掉全驻留失败。读取了JSON索引，没有读取权重内容。

### 1.2 当前官方同表：指令、办公与专业任务

第一表所有值来自同一[3.8-27B固定card](https://huggingface.co/Qwen/Qwen3.8-27B/blob/1d4bf0f2ff6012fd82039f2fa52739d0dd7c60c0/README.md)；官方没有给这些指标的置信区间。

| 指标 | Qwen3.8-27B | Qwen3.6-27B | Qwen3.7-Plus | Muse Glimmer-30B | Opus4.6 Max |
| --- | ---: | ---: | ---: | ---: | ---: |
| IFBench | **79.5** | 69.1 | 79.1 | 77.0 | 62.5 |
| CoWorkBench | **70.7** | 61.0 | 65.1 | -- | 68.2 |
| JobBench | **33.4** | 21.8 | 27.6 | -- | -- |
| Agents' Last Exam Pass@1 | **20.4** | 10.6 | 13.2 | -- | -- |
| Agents' Last Exam Score | **42.9** | 27.3 | 33.6 | -- | -- |
| GPQA Diamond | 89.2 | 87.8 | 90.3 | 83.5 | 91.3 |
| HLE | 30.8 | 24.0 | 34.7 | 22.0 | 40.0 |

同表3.8-27B相较3.6-27B的IFBench高10.4、CoWork高9.7、Job高11.6分。但这不能解释同为3.8底座的本机Bonsai具体失败，更不能推出FP8／BF16已通过。CoWork是厂商内部长程办公任务，覆盖CS、金融、法律、医学等；Job与ALE更接近专业／持续任务，但仍不是项目来源采用或合同引文评价。

第二表来自同一[Flash-Next固定card](https://huggingface.co/Qwen/Qwen3.8-Flash-Next/blob/de4b8e4d43b917e7706784d8bb445c9af86a3540/README.md)，保留其DeepSeek列的确切`0731`版本；未将两表拼为统一榜单。

| 指标 | Flash-Next | 3.8-27B | 3.7-Plus | DeepSeek-V4-Flash-0731 | Opus4.6 Max |
| --- | ---: | ---: | ---: | ---: | ---: |
| IFBench | **81.3** | 79.5 | 79.1 | 79.2 | 62.5 |
| CoWorkBench | **73.9** | 70.7 | 65.1 | 45.1 | 68.2 |
| JobBench | **55.7** | 33.4 | 27.6 | 41.3 | 36.6 |
| Agents' Last Exam Pass@1 | 24.3 | 20.4 | 13.2 | **25.2** | -- |
| Agents' Last Exam Score | **51.2** | 42.9 | 33.6 | -- | -- |
| ClawEval-MM Pass@3 | **64.4** | 57.4 | 57.4 | -- | 52.5 |
| ClawEval-MM Average | **60.4** | 56.9 | 60.1 | -- | 54.7 |

Flash-Next的Job显著数值优势构成专业任务研究信号，但ExtractBench长文没有优势（见下节），资源也不合单卡全驻留。ClawEval-MM是多模态工具／agent指标，不叫文本抽取准确率；无需因为coding或多模态高分而给合同业务加权。

当前27B／Flash-Next文本表没有发布IFEval、AA-LCR、LongBench v2、SimpleQA、BFCL或TAU分数，不能用旧版本补空。2.4T card的**hosted Max**同表LongBench v2为66.3（Opus4.8 69.1、GPT5.6 Sol max 67.1、Qwen3.7-Max 65.3），IFBench82.8；这是另一个服务身份，且没有按256K／512K长度分桶。本轮在27B／Flash-Next两card未找到RULER／MRCR或对应长度桶成绩。

### 1.3 更贴近任务的ExtractBench／ParseBench：提供者FP8配方

以下是LlamaIndex benchmark提供者`boyang-runllama`提交到公开HF PR的评测YAML，**不是Qwen主README中已合并的BF16成绩，也不是Bonsai成绩**。只据公开元数据报告，不称独立复现。

| 任务／评测日期 | 实际服务checkpoint | pipeline | mean | short | medium | long |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| ExtractBench，2026-08-24 | Qwen/Qwen3.8-27B-FP8 | `qwen3_8_27b_fp8_vllm_extract_oneshot_structured_output_file` | **89.75** | 94.68 | 87.54 | **38.45** |
| ExtractBench，2026-08-29 | Qwen/Qwen3.8-Flash-Next-FP8 | `qwen3_8_flash_next_fp8_vllm_extract_oneshot_structured_output_file` | **89.88** | 94.82 | 87.81 | **37.74** |

逐项来源：[27B PR172](https://huggingface.co/Qwen/Qwen3.8-27B/discussions/172)、[27B Extract YAML](https://huggingface.co/Qwen/Qwen3.8-27B/blob/refs%2Fpr%2F172/.eval_results/extractbench.yaml)、[Flash-Next PR38](https://huggingface.co/Qwen/Qwen3.8-Flash-Next/discussions/38)、[Flash-Next Extract YAML](https://huggingface.co/Qwen/Qwen3.8-Flash-Next/blob/refs%2Fpr%2F38/.eval_results/extractbench.yaml)。PR可继续修订；固定checkpoint revision不能当这些动态PR评测文件的revision，引用时保留日期与PR身份。

| ParseBench，2026-08-28；同27B-FP8服务 | mean | text_content | text_formatting | layout | chart | table |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `qwen3_8_27b_thinking_parse_with_layout` | **70.79** | 88.28 | 59.77 | 69.90 | **69.17** | 66.82 |

来源：[27B Parse YAML](https://huggingface.co/Qwen/Qwen3.8-27B/blob/refs%2Fpr%2F172/.eval_results/parsebench.yaml)。**69.17是chart，不能误报为均分；正确mean为70.79。** pipeline名字显示thinking／layout路线，但YAML没有完整temperature、thinking token预算、max context、engine commit、数据revision或逐题usage，不补造recipe。ParseBench约2000个人工核验页面的五维评分，用来研究OCR／版面解析路线；不能把页面解析成绩当合同语义与引用验收，也不要求把既有OCR默认换成LLM。

[ExtractBench作者资料](https://huggingface.co/datasets/llamaindex/ExtractBench)定义370份英文文档／4,869页、8个业务领域／67类资料，含schema值、重复行、缺值null及页／box证据；short为252份≤10页、medium为98份11–50页、long为20份>50页。文档页数不是token窗口长度，长子集不是256K／512K验收。整体均分由短资料主导：按公开分桶数加权，27B约`(94.68×252＋87.54×98＋38.45×20)/370＝89.749`，Flash-Next约89.878；能复核报告均值。Flash-Next相较27B均分只高0.13分，long低0.71分，不能用总均分说明长清单更好。

ExtractBench区分value统一F1与word／page grounding指标；这些YAML值不自动证明新的来源定位质量。2026-08-19为v1.0，2026-09-29 v1.1增加103份真实资料box ground truth、收紧box并做少量值修正，文档和schema不变。上述8月分数早于v1.1且YAML未绑定dataset revision，不能假称已在当前v1.1重测。ExtractBench的长清单完整性、needle、密集表格／空值／checkbox任务，比通用coding排名更贴近typed extraction；仍需把英文资料抽取与中文合同、跨源采用、连续引文分别评价。[ParseBench作者资料](https://huggingface.co/datasets/llamaindex/ParseBench)。

## 2. 历史同表A：3.5的27B dense、35B小MoE、旧235B与122B

以下所有数字逐行来自同一个 [Qwen3.5-27B官方card，固定revision](https://huggingface.co/Qwen/Qwen3.5-27B/blob/fc05daec18b0a78c049392ed2e771dde82bdf654/README.md) 的Language表。数值是官方报告的分数；表内没有置信区间。GPT-OSS及旧大模型列作质量参照，不因此放宽用户256K门槛；`--`表示未提供／不适用，不能算0。

| 指标 | GPT-OSS-120B | Qwen3-235B-A22B | Qwen3.5-122B-A10B | Qwen3.5-27B | Qwen3.5-35B-A3B |
| --- | ---: | ---: | ---: | ---: | ---: |
| IFEval | 88.9 | 87.8 | 93.4 | **95.0** | 91.9 |
| IFBench | 69.0 | 51.7 | 76.1 | **76.5** | 70.2 |
| MultiChallenge | 45.3 | 50.2 | 61.5 | 60.8 | 60.0 |
| AA-LCR | 50.7 | 60.0 | 66.9 | 66.1 | 58.5 |
| LongBench v2 | 48.2 | 54.8 | 60.2 | **60.6** | 59.0 |
| BFCL-V4 | -- | 54.8 | **72.2** | 68.5 | 67.3 |
| TAU2-Bench | -- | 58.5 | 79.5 | 79.0 | **81.2** |
| VITA-Bench | -- | 31.6 | 33.6 | **41.9** | 31.9 |
| MMLU-Pro | 80.8 | 84.4 | **86.7** | 86.1 | 85.3 |
| C-Eval | 76.2 | **92.1** | 91.9 | 90.5 | 90.2 |

由同表得到的具体判断：

- 27B对旧235B，IFEval高7.2分、IFBench高24.8分、AA-LCR高6.1分、LongBench v2高5.8分；这直接反驳“大参数必胜”的筛选逻辑。
- 27B对新122B，IFEval高1.6分、IFBench高0.4分、LongBench v2高0.4分；AA-LCR低0.8分、BFCL低3.7分。它并非所有维度胜出；0.4分差异不能在没有区间与重复试验时称为显著提升。
- 35B小MoE的TAU2为81.2，高于27B的79.0及122B的79.5；但IFBench70.2、AA-LCR58.5低于27B。它应当作为需要工具／多轮交互的任务对照，不能凭MoE总参数35B把dense27B淘汰。
- 27B的C-Eval90.5低于旧235B92.1，说明新小模型并非每项胜旧大模型。C-Eval与MMLU只提供知识／中文考试的辅助信号，不直接证明来源引用或合约例外处理。

## 3. 历史同表B：3.5的9B与旧80B／30B、GPT-OSS120

数字来自 [Qwen3.5-9B官方card，固定revision](https://huggingface.co/Qwen/Qwen3.5-9B/blob/c202236235762e1c871ad0ccb60c8ee5ba337b9a/README.md) 的同一Language表。保持其明确写出的Thinking／2507列身份；没有将两张表拼成一个伪统一排名。

| 指标 | GPT-OSS-120B | Qwen3-Next-80B-A3B-Thinking | Qwen3-30B-A3B-Thinking-2507 | Qwen3.5-9B | Qwen3.5-4B |
| --- | ---: | ---: | ---: | ---: | ---: |
| IFEval | 88.9 | 88.9 | 88.9 | **91.5** | 89.8 |
| IFBench | **69.0** | 61.5 | 51.5 | 64.5 | 59.2 |
| MultiChallenge | 45.3 | 51.3 | 46.5 | **54.5** | 49.0 |
| AA-LCR | 50.7 | 51.7 | 49.0 | **63.0** | 57.0 |
| LongBench v2 | 48.2 | 48.0 | 44.8 | **55.2** | 50.0 |
| BFCL-V4 | -- | 49.7 | 42.4 | **66.1** | 50.3 |
| TAU2-Bench | -- | 57.4 | 41.9 | 79.1 | **79.9** |
| VITA-Bench | -- | 29.5 | 14.1 | **29.8** | 22.0 |
| MMLU-Pro | 80.8 | **82.7** | 80.9 | 82.5 | 79.1 |
| C-Eval | 76.2 | **89.7** | 87.4 | 88.2 | 85.1 |

9B对旧80B同表高：AA-LCR11.3分、LongBench v2 7.2分、BFCL16.4分、TAU2 21.7分；IFBench64.5低于GPT-OSS120的69.0。它值得实际测试关系分类、文档／条款角色、明确否定／unknown与typed变量，而不是因9B小就排除。4B的TAU2反而79.9高于9B79.1，也提醒一个指标不能代表整体质量。

9B AA-LCR63.0与27B66.1属于不同card表、没有完整逐项评测recipe／raw outputs，本轮只作探索性的候选信号，不将差3.1分当严格头对头试验。实际extractor的值与引文、条件闭包、unknown和多源采用需用同源留出题检验。

## 4. 历史Qwen3.6：2026 agent指标与版本缺口

以下来自 [Qwen3.6-35B-A3B固定card](https://huggingface.co/Qwen/Qwen3.6-35B-A3B/blob/995ad96eacd98c81ed38be0c5b274b04031597b0/README.md) 的同一Language表。TAU3与TAU2是不同版本，不跨版本减分；VITA的judger也已变更，不能与第2节31.9直接比较。

| 指标 | Qwen3.5-27B | Qwen3.5-35B-A3B | Qwen3.6-35B-A3B | 说明 |
| --- | ---: | ---: | ---: | --- |
| TAU3-Bench | 68.4 | 68.9 | **67.2** | 新版35B并未提高；官方user model为gpt-5.2 low＋默认BM25 |
| VITA-Bench | 41.8 | 29.1 | **35.6** | 3.6高于该表3.5-35B，但仍低于27B；使用claude-4-sonnet judger替代不可用的claude-3.7-sonnet |
| DeepPlanning | 22.6 | 22.8 | **25.9** | 更偏长程计划，不能代替条款语义验收 |
| MCPMark | 36.3 | 27.0 | **37.0** | GitHub MCP v0.30.3；Playwright responses截到32K |
| MCP-Atlas | 68.4 | 62.4 | **62.8** | public set，gemini-2.5-pro judge；高于同表旧35B但低于27B |
| Claw-Eval Avg | 64.3 | 65.4 | **68.7** | 平均提高 |
| Claw-Eval Pass^3 | 46.2 | 51.0 | **50.0** | 与Avg方向不同；不据平均分断言稳定性同步提升 |
| WideSearch | 66.4 | 59.1 | **60.1** | 搜索系统指标，低于同表27B |

[Sierra TAU3 Knowledge发布页](https://sierra.ai/resources/research/tau-3-bench-knowledge) 日期为2026-03-18，说明新版扩展到knowledge。它比纯coding更接近Advisory中的资料查找／交互，但仍不能替代项目采用、引用连续性和缺证拒答。

Qwen3.6-27B官方同表包含Qwen3.5-397B-A17B。其SWE-bench Verified为77.2对76.2、Terminal-Bench2.0为59.3对52.5，展示新dense小模型可在coding系统中超过较大MoE；同表MMLU-Pro86.2对87.8、C-Eval91.4对93.0，仍未全面胜出。**该表没有IFEval／IFBench／AA-LCR／LongBench v2的3.6成绩，所以这些coding差异不主导ConSense选型。** [3.6-27B固定card](https://huggingface.co/Qwen/Qwen3.6-27B/blob/6a9e13bd6fc8f0983b9b99948120bc37f49c13e9/README.md)。

## 5. 指标与四类业务的对应关系

| 指标 | 作者定义与本项目相关性 | 不能证明的部分 |
| --- | --- | --- |
| IFEval／IFBench／MultiChallenge | 格式、词项、长度、多约束／多轮指令遵循；对typed extraction字段、枚举、引用格式与不遗漏required键有直接筛选价值。IFBench作者给58种新OOD可验证约束，评测一般为prompt-level loose accuracy，thinking抽取final后检查 | 不证明某个false／数字／责任关系是事实正确，也不证明引文支持值。Qwen卡未列strict／loose具体选择，不能自行认作作者默认recipe |
| AA-LCR | 多份长资料之间的事实合成、条件／时间及法律资料推理；Vetting跨源关系和Advisory grounded回答的较强代理信号 | 原数据平均约100K input tokens、以cl100k_base计数，不证明256K／512K极限或原文逐字引用。模型card只写AA-LCR，未绑定v1.0／v1.1 |
| LongBench v2 | 多文档QA、长结构化数据、长对话／in-context任务；与资料闭包和表格理解有关 | 503道多选题，8K–2M **words**，多数在128K以下；不是全部512K tokens测试，不是JSON／引用／拒答考试 |
| BFCL-V4 | 函数选择、参数、工具调用及多轮agent；对关系分类后通过typed schema交接、检索工具参数有辅助意义 | 用工具参数成功不代表合同变量／来源角色正确；native FC与prompt模拟FC须区分，官方页面的code commit也不能自动当Qwen卡的评测commit |
| TAU2／TAU3／VITA | 规则约束下用户、agent和工具交互；可筛Advisory澄清／工具协作与长任务状态一致性 | 特定领域、user model、retriever、judge、scaffold会影响分数；不替代实际合约专业性及证据审计 |
| SimpleQA／事实性／hallucination | 简短事实问答、错误与拒答的分别统计；对Advisory缺证／不确定性有参考意义 | 当前所查Qwen卡未公布SimpleQA文本成绩，不能推导幻觉率。Vision表中的HallusionBench测视觉幻觉，不能当合同文本幻觉成绩 |
| C-Eval／MMLU／GPQA | 中文与知识／reasoning基本能力的辅助筛选 | 不测项目采用、当前来源、原文引文与责任边界；不能把考试正确率叫业务正确率 |
| SWE／Terminal／LiveCodeBench | 编码／终端agent能力，只有实现型工具或代码任务才提高权重 | 不能主导Drafting、关系分类、Vetting语义或Advisory引用回答 |

定义来源：[IFBench作者README](https://github.com/allenai/IFBench/blob/main/README.md)、[AA-LCR作者dataset card](https://huggingface.co/datasets/ArtificialAnalysis/AA-LCR)、[LongBench v2作者repo](https://github.com/THUDM/LongBench)、[BFCL作者方法页](https://gorilla.cs.berkeley.edu/leaderboard.html)、[SimpleQA作者说明](https://openai.com/index/introducing-simpleqa/)。上述业务对应是研究推断，不是作者对ConSense任务的验收。

**2026版本变化必须单列。** AA-LCR v1.1在2026年9月修正16个answer keys并增加judge system prompt，作者明确v1.0与v1.1不可直接比较。Qwen卡未注明dataset revision，因此同表可作为作者给出的相对信号，不能与2026年10月v1.1榜单合并排名。新评价应固定revision／judge、把格式／单位错误与事实错误分开；不是只读取旧“AA-LCR”名字。原v1.0仍有固定revision `bdae010bbce259820c0e34c1d7cce210d966fb75`。本轮未执行benchmark或下载数据文件。

## 6. Thinking、输出预算和可比限制

官方3.5／3.6卡都默认thinking，公开推荐一般任务最大输出32,768 tokens、困难数学／coding81,920；这些是**推荐值，不是每一行已证实使用的实际评测预算**。所查IFEval、IFBench、AA-LCR、LongBench v2各行没有逐行temperature、max output、thinking effort、precision、engine版本、样本级usage或置信区间；本轮不补造这些字段。

IFBench作者原README通常报告prompt-level loose accuracy，temperature0，thinking模型允许更多生成tokens并抽取final再评分；这解释了thinking budget和final解析为何影响排名，但不意味着Qwen每一行一定照此。未来实际ConSense对照固定同源输入、schema和final parser，分开bounded thinking／non-thinking，两者均记录思考tokens、final tokens、总时间、协议失败和关键业务错误。

3.6 coding脚注则给出明确的system recipe：SWE内部bash＋file-edit scaffold、temperature1.0、top_p0.95、200K context，SWE Pro做了问题修订且同表baselines重评；Terminal-Bench2.0使用Harbor／Terminus-2、3小时timeout、32CPU／48GB RAM、temperature1.0、top_p0.95、top_k20、max_tokens80K、256K context、5次平均。不能用该表分数声称仅权重升级的净收益，也不能将这些预算用作合约运行默认值。

3.5 TAU2脚注明确airline domain应用Claude Opus4.5 system card的修复；3.6 TAU3、VITA与MCP的recipe见第4节。3.5 VITA35B31.9与3.6表内同旧模型29.1不同，本轮保留两张表原值，不选择有利数字或跨表相减。官方FP8包另属部署recipe；BF16／原checkpoint评测不能自动成为FP8业务质量成绩。

3.8-27B默认thinking、`reasoning_effort=xhigh`，可选medium／low，`preserve_thinking`默认开启；保留历史思考会消耗总上下文，实际部署须明确它的策略。card在1M扩窗场景建议thinking上限262,144、final131,072，不可把两数相加套进原生256K预算。3.8的SWE Pro脚注为Claude Code scaffold、temperature1、top_p0.95、256K context，修订题目后重评baselines；QwenSWEBench内部avg@3、8小时timeout、max_tokens32,768、temperature1、256K context。这些系统分数不代表裸checkpoint合同质量。3.8两card仅写latest frameworks；随后核[Flash-Next vLLM recipe](https://recipes.vllm.ai/Qwen/Qwen3.8-Flash-Next)标0.29.0+及专用镜像、PyPI不支持，[SGLang cookbook](https://docs.sglang.io/cookbook/autoregressive/Qwen/Qwen3.8-Flash-Next)明确0.5.20+。不得把3.6引擎pin直接继承为3.8已验。该recipe确有N-gram CPU offload，但单H800容量还不足，详见[更大模型单卡研究](llm-h800-larger-model-candidates-20261012.md)。

## 7. 至少256K、优选512K的资格

| 模型 | 官方原生总窗口 | 官方512K／更长路线 | 当前可以声称的资格 |
| --- | ---: | --- | --- |
| Qwen3.8-27B／官方FP8包 | 262,144 | factor2路线524,288；factor4官方命令cap **1,000,000** | native规格过256K门；同底座Bonsai失败继续有效，512K质量／目标资源未验 |
| Qwen3.8-Flash-Next／官方FP8包 | 262,144 | 同上；hosted Qwen3.8-Flash 1M是独立service | 规格native256K；FP8权重172.7624GiB，单H800全驻留不合格 |
| Qwen3.8-2.4T-A95B | 262,144 | card声明可扩1,010,000 | 大型质量参考；hosted Max分数不等于本地配方验收 |
| Qwen3.5-9B | 262,144 | YaRN factor2示例524,288；factor4可至1,010,000 | 官方规格通过256K门；512K为扩窗recipe，质量与目标资源未验 |
| Qwen3.5-27B | 262,144 | 同上 | 同上 |
| Qwen3.5-35B-A3B | 262,144 | 同上；hosted Qwen3.5-Flash默认1M是独立服务配置 | 本地native256K与hosted1M不能混为同一个实际部署 |
| Qwen3.6-27B | 262,144 | 同上；官方建议只在需要长文时改rope_parameters | native256K；3.6的512K任务分数尚无本文证据 |
| Qwen3.6-35B-A3B | 262,144 | 同上 | 同上 |

来源为每个固定card的Model Overview与Processing Ultra-Long Texts节：[9B](https://huggingface.co/Qwen/Qwen3.5-9B/blob/c202236235762e1c871ad0ccb60c8ee5ba337b9a/README.md)、[3.5-27B](https://huggingface.co/Qwen/Qwen3.5-27B/blob/fc05daec18b0a78c049392ed2e771dde82bdf654/README.md)、[3.5-35B](https://huggingface.co/Qwen/Qwen3.5-35B-A3B/blob/59d61f3ce65a6d9863b86d2e96597125219dc754/README.md)、[3.6-27B](https://huggingface.co/Qwen/Qwen3.6-27B/blob/6a9e13bd6fc8f0983b9b99948120bc37f49c13e9/README.md)、[3.6-35B](https://huggingface.co/Qwen/Qwen3.6-35B-A3B/blob/995ad96eacd98c81ed38be0c5b274b04031597b0/README.md)。

factor4×262,144数学上为1,048,576，3.5／3.6 card命令为1,010,000，而当前3.8-27B／Flash-Next命令为1,000,000；保留各代实际cap，不将三数当同一事实。3.8官方YaRN为`mrope_interleaved=true`、`mrope_section=[11,11,10]`、`rope_theta=10000000`、`partial_rotary_factor=0.25`、`original_max_position_embeddings=262144`；factor2用于常见524,288，factor4用于更长。静态YaRN可能降低短文性能，官方建议仅在需要时启用；引擎兼容不由参数名保证。27B card称hosted1M版本coming soon，不能当本轮已可用。

原生262K、支持配置到512K、成功不OOM、长序列回答准确是四种不同证据。用户优选512K意味着最终候选还要用不裁来源的512K总预算实际验证前／中／后分布、条件／例外跨距、缺证拒答、表格／关系闭包、引用连续性与足够输出；不能用100K输入AA-LCR分数、>50页的ExtractBench或无长度桶的LongBench均分替代。

## 8. 如何据此安排业务对照

- **完整业务主模型：** 当前本机受限Bonsai PTQ1_0配方不足以直接担当完整业务主模型，保留部署状态与失败证据；H800完整上下文中的Bonsai／FP8均未验。将已知资源／上下文限制与精度、协议、模型能力分开后，才决定是否做受控复测；不默认仅加思考能解决。M3保留已有Drafting质量参考资格，但真实硬件、窗口和其它任务独立准入。Flash-Next不能推荐为单H800全驻留默认；研究offload／多卡时还须用实际专业任务收益覆盖内存、传输与延迟成本。
- **Drafting typed extraction／关系分类：** 用ExtractBench长清单、schema、null／checkbox等指标定位候选价值；高精度3.8-27B只有公开FP8提取信号支持其作为精度／协议受控比较，必须先说明它改善Bonsai哪一个关键残差，不能直接认作主模型通过。3.5-9B或3.6-35B-A3B可进入有限角色挑战，但所用成绩／版本及256K／512K实际质量保持未验。按原值、false／unknown、冲突、required字段、引用支持与连续性分项评分，不能只用JSON parse成功。
- **Vetting语义审查：** 由跨源责任、条件／例外、表格完整与coverage决定模型角色；当前3.8 Job／CoWork更有专业任务研究价值，仍没有合同留出题验收。对Bonsai已见关键错误保留外部强模型参考和升级路线，不按27／35／120B参数预先排胜负，也不拿3.5旧表当新底座保证。
- **Advisory引用回答：** 优先有证合成、缺证校准和长跨度证据，再看工具／多轮指标；SimpleQA、RULER／MRCR、256K／512K分桶与当前27B长文成绩空缺保留为未核项。必须单列有证回答、unknown／澄清、拒答和臆造，hosted Max长文成绩与本地Flash／27B身份分开。
- **M3实际经验继续保留：** 仓库第五轮MiniMax-M3真实运行`c4bb7f20-cde1-4bae-8205-1da79f2a2375`为58/58有值正确、21/21正确留空，42PASS／37REVIEW／0FAIL，37REVIEW有独立语义复核；这支持M3作为Drafting质量参考，但不是公开未见题或整篇文稿验收。[保存证据](../testing/m3-round5-20261009/README.md)。Bonsai原始51/58有值正确、7FAIL，离线重放56/58、2FAIL，未新增推理；不抹掉失败，也没有同消息／同引擎证据把全部差异归因于三元化。[本机证据及口径](bonsai-local-task-evidence-20261012.md)。

未测状态：上述Qwen候选本轮真实模型调用0；256K／512K的本项目实际输入、输出、引用质量、长思考、单H800运行与前端验收均未由本文完成。表格值是官方报告，推荐顺序是任务导向研究判断。
