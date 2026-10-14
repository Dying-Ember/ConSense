# 按业务任务评价 LLM 的候选与部署研究

日期：2026-10-12，Asia/Shanghai。继承 [OER 已确认范围与执行规则](../oer-scope-and-rules.md) 与 [当前交接](../HANDOFF-ocr-embedding-rerank-20261008.md)。本轮读取仓库已有证据、官方模型卡及公开配置／索引／文件元数据，保存研究；没有下载权重、调用模型或 API、运行或修改 Drafting harness、修改运行配置或执行部署。

## 1. 当前判断

**后续用户要求已覆盖本文初版推荐顺序。** 主模型至少256K、优选512K；本机Qwen3.8-27B底座的Bonsai三进制已测不足。当前任务表、失败经验、最新模型与长窗角色见 [质量与长上下文选型](../llm-task-quality-selection-256k-512k-20261012.md)。本文保留此前准确包／metadata调查；短窗模型只能作历史或短任务控制，不再以本文旧容量候选段落决定主选。

**不以120B、MoE或某个供应商为先决条件。** 最终模型可以按任务不同，也可以是通过全部业务门的一套模型。先并列评价小型 dense、27B dense／hybrid、35B 小 MoE、Gemma 独立 dense 家族，再由实际质量和 H800 资源证据决定是否增加较大 dense 量化模型或 GPT-OSS-120B。参数数目、激活参数、公开 coding 榜和标称窗口均不等同于本项目的条款判断能力。

用户当前认可完整 MiniMax M3 官方 API 在 Drafting 上的效果，因此保留它作为质量参考。这个参考不预先输给本地模型，也不自动要求未来继续依赖外部 API；本地部署可行性与 API 质量是两条分别验证的结论。[官方开放权重](https://huggingface.co/MiniMaxAI/MiniMax-M3)已存在，约428B总参数／23B激活；[专门自部署页](https://platform.minimax.io/docs/guides/local-deploy-m3)为Experimental，参考8×B200 TP8／MXFP8约444GB权重。产品页“后续开源”的旧文案与实际权重仓、专门部署页存在状态冲突。单H800整模型GPU常驻不成立，CPU／expert offload也尚未作为单卡业务方案验证；完整身份、API语义与边界见 [M3专文](./minimax-m3-evaluation-reference-20261012.md)。

硬件前提是单 H800 80GB、250GB RAM、27 vCPU、650GB 快盘及1250GB普通存储。用户已明确 OCR 与 LLM 不同时使用，允许卸载，因此先评价 LLM 独占整卡的能力，再加入在线 embedding／rerank 真实峰值。RAM 中保留已加载权重以加快阶段切换，不等同于每次 forward／每 token 跨 PCIe 回读 CPU 权重。[显存与卸载分析](./llm-h800-selection-20261012.md)。

这份研究不改变当前执行顺序：完整解析质量 → 固定资料向量库质量 → 项目业务 → K8s／H800。当前原件质量与业务门的实际未完成状态保留；本次新增真实模型运行数为0。

## 2. 四类任务的评价对象

| 任务 | 必须评价的实际行为 | 主要失败代价 | 选型含义 |
| --- | --- | --- | --- |
| Drafting 内容与变量识别 | 从当前采用资料抽取 typed 值、保留否定与条件、识别 pending／冲突、正确留空、逐候选原文引用；后续文字填入模板保持对象、单位、责任和适用范围 | `false`误当空值、未知误填、样例被当采用源、Bill／Sections缺项、引文不连续、正文润色改变责任 | 小模型可先测 bounded extraction；长字符串、跨表格责任和条件组合另分难度。最终文稿质量与变量识别分开验收 |
| Vetting | 比较条款、上下级与跨文件采用／改写关系，覆盖必要条件／例外、完整表组，区分冲突、差异和缺证，给可核验 finding／coverage | 错把缺资料当冲突、删掉例外、漏检查未覆盖单元、无依据宣告合同无风险 | 27B dense与35B MoE平行；较大dense／120B只在困难留出题上能提高关键错误表现时升级 |
| Advisory | 回答具体问题、辨明项目采用与标准来源，保留条件、优先级及未知，需要时澄清，引用完整当前来源 | 有信心但缺证、用标准推翻项目实际改写、答非所问、把局部检索当完整法律闭包 | 固定资料与检索结果下比较答案；检索缺证与生成错误分别归因，不能只看回答流畅度 |
| 语义识别／路由 | typed 文档身份、条款／表格角色、候选相似与采用语义、ambiguity／unknown和对应证据 | 误识别身份后进入错误知识库、建议自动成为已采用、来源失效仍返回旧值 | 小型dense有资源优势；只在可核验的有限分类／提取边界中使用。身份哈希、revision、权限、来源资格由确定性系统负责 |

任务表是本次评价设计，不声称任何候选已完成业务验收。既有 [Drafting工具说明](../../tools/drafting_eval/README.md)、[冻结考试记录](../../../docs/drafting-harness-rounds-4-5-20261009.md)、[Vetting／Advisory验收计划](../testing/oer-official-business-acceptance-plan-20261010.md) 是对应业务证据入口。LLM能力不能替代 native证据保存、精确数字运算、哈希绑定、来源修订和模板编译。

## 3. 候选矩阵：质量假设与可部署包分开

下表是有限候选集，不是排他名单。不同精度或版本必须记录为不同 recipe；BF16与FP8结果不能合并成一个模型成绩。GiB为2^30 bytes，全部数字来自公开固定revision元数据，**不是加载后显存**。`payload`指索引的字节声明；`file`指官方权重文件长度合计、包含header。多模态checkpoint还包含vision／MTP等，实际text-only loader可能不同。

| 候选 | 官方身份／已核窗口 | 权重口径与规模 | 本轮进入评价的理由／限制 |
| --- | --- | ---: | --- |
| Qwen3-8B BF16 | 小型text dense；config 40,960 tokens；Apache2.0 | payload 16,381,470,720 bytes＝15.256GiB | 已有常规GQA服务路线的小模型基线；重点测bounded语义、变量及短Advisory。不能把扩窗配置或8B速度外推到复杂Vetting质量 |
| Qwen3.5-9B BF16 | dense hybrid，32层＝8 full＋24 linear；原生262,144；Apache2.0 | payload 19,306,216,416＝17.980GiB | 同时比较新hybrid小模型的长输入容量；9B与8B并非只差1B，架构、模板及多模态包不同 |
| Qwen3.5-27B BF16 | dense hybrid，64层＝16 full＋48 linear；原生262,144；Apache2.0 | payload 55,562,872,800＝51.747GiB | 较少量化的中文、条件／表格与推理质量对照；dense每token计算负担不能用MoE激活参数来估 |
| Qwen3.6-27B BF16／官方FP8 | 仍为27B dense hybrid、16 full＋48 linear、原生262,144；Apache2.0 | BF16 payload 55,562,855,904＝51.747GiB；FP8 file 30,866,866,928＝28.747GiB | 同版本精度对照与3.5→3.6版本对照；官方FP8包存在。FP8 index metadata为空，用66个safetensors文件长度；空字段不当0 |
| Qwen3.5／3.6-35B-A3B 官方FP8 | 35B总／3B激活，40层＝10 full＋30 linear；原生262,144；Apache2.0 | 3.5 payload 37,454,799,072＝34.882GiB；3.6 file 37,463,662,160＝34.891GiB | 单卡留出较多长输入／在线E/R容量的小MoE主对照；不能因35B总参数较大就宣告优于27B dense；3.6是版本对照，不凭coding榜定胜负 |
| Gemma4-31B-it BF16 | Google独立家族，官方30.7B dense，60层＝10 full＋50 sliding；原生256K，sliding1024；Apache2.0 | payload 62,546,177,752＝58.251GiB | 独立dense对照，检验中文、条款、缺证和引用；本轮确认Gemma4许可，未沿用Gemma3许可或窗口。单卡长请求内核和重排未测 |
| GLM-4.7-Flash BF16 | 官方30B-A3B MoE，47层MLA，config 202,752；MIT | 48个权重file 62,444,175,504＝58.156GiB | 独立中文MoE／MLA对照；官方卡公开配方采用TP4且含较新引擎要求，不能当单H800成功。索引字节字段异常见下文 |
| Qwen2.5-72B-Instruct-AWQ | 较大dense，AWQ4bit group128；config32,768，卡给YaRN扩至131,072；Qwen自定义许可 | payload 41,595,518,976＝38.739GiB | 成熟larger dense量化对照；官方强调structured／JSON，但任务须实测。原生32K与扩展128K分开；长KV可能比hybrid模型高很多 |
| GPT-OSS-120B 原生MXFP4 | 117B总／5.1B激活；18 full＋18 sliding；原生131,072；Apache2.0 | payload 65,248,815,744＝60.768GiB | 保留约120B大MoE对照；原生MXFP4与Harmony协议形成独立recipe。官方单H100案例不等于H800长输入与业务验收 |
| MiniMax M3 官方API | 完整API质量参考；官方ID `MiniMax-M3`，官方发布说明给超长窗口 | 不适用本地权重／显存口径 | 用户认可当前Drafting效果；分别记录实际profile、endpoint、model字段、完整wire／usage。不用API名称反推可下载checkpoint或单卡可行性 |

逐模型官方来源：[Qwen3-8B](https://huggingface.co/Qwen/Qwen3-8B)、[Qwen3.5-9B](https://huggingface.co/Qwen/Qwen3.5-9B)、[Qwen3.5-27B](https://huggingface.co/Qwen/Qwen3.5-27B)、[Qwen3.6-27B](https://huggingface.co/Qwen/Qwen3.6-27B)、[3.6-27B FP8](https://huggingface.co/Qwen/Qwen3.6-27B-FP8)、[Qwen3.5-35B-A3B FP8](https://huggingface.co/Qwen/Qwen3.5-35B-A3B-FP8)、[Qwen3.6-35B-A3B FP8](https://huggingface.co/Qwen/Qwen3.6-35B-A3B-FP8)、[Google Gemma4 model card](https://ai.google.dev/gemma/docs/core/model_card_4)、[Gemma31 checkpoint](https://huggingface.co/google/gemma-4-31B-it)、[GLM Flash](https://huggingface.co/zai-org/GLM-4.7-Flash)、[Qwen72 AWQ](https://huggingface.co/Qwen/Qwen2.5-72B-Instruct-AWQ)、[GPT-OSS规格](https://developers.openai.com/api/docs/models/gpt-oss-120b)、[M3官方页](https://www.minimax.cn/models/text/m3)。revision和复算入口见第7节。

建议第一批构成是：**小模型8B／9B、27B dense、35B FP8 MoE、Gemma31与M3 API参考**；同家族先冻结一个版本形成主对照，再用3.6／3.5版本及BF16／FP8做受控差异试验。GLM、72B AWQ和GPT120作为第二批，增加独立家族或困难题质量的信息。如果一个第一批模型已在必要任务和容量上达到门槛，不必为了参数更大而增加在线复杂度；如果高风险任务仍有系统性错误，再由错误类型选择扩展模型。

## 4. 会改变单卡判断的几个容量事实

容量统一用 `实际驻留权重＋KV／线性状态＋prefill工作区＋graphs／allocator／副本＋本阶段辅助模型峰值＋余量`。按config计算的理论KV不包含后五项；厂商80GB标签不是容器当前可准入字节。[完整公式及GPT／Qwen容量研究](./llm-h800-selection-20261012.md)。

| 配方 | BF16普通KV理论口径 | 单卡含义 |
| --- | --- | --- |
| Qwen3-8B | 36 full、8KVheads×128；总序列32K约4.5GiB | 较小权重并不自动得到低长窗口KV；config原生40,960，本次不把128K当原生 |
| Qwen3.5-9B | 8 full、4KVheads×256；128K约4GiB／256K约8GiB，另有linear状态 | 小模型长输入容量候选；“262K”只声明窗口，不证明失散来源／长距例外的质量 |
| Qwen3.5／3.6-27B | 16 full、4KVheads×256；128K约8GiB／256K约16GiB，另有linear状态 | BF16权重51.747＋8≈59.747GiB再加状态与工作区；FP8包更轻，但质量与kernel另验 |
| Qwen3.5／3.6-35B-A3B | 10 full、2KVheads×256；128K约2.5GiB／256K约5GiB，另有linear状态 | FP8权重约34.9GiB具有容量余地；35B小MoE的低KV斜率是架构结果，不是所有MoE共有性质 |
| Gemma4-31B | config：10 full的4 global KVheads×512；50 sliding的16KVheads×256、window1024；普通K/V分别存储时128K约10.781GiB／256K约20.781GiB | 128K时payload58.251＋KV10.781≈69.032GiB，未含工作区／重排；256K合计约79.032GiB已很紧，不按卡的256K直接设置单卡最大窗口 |
| Qwen72 AWQ | 80 full、8KVheads×128；32K约10GiB／64K约20GiB／128K约40GiB | 128K时权重＋KV≈78.739GiB、尚无工作区；32K／64K更值得先测。YaRN扩窗和KV量化是不同recipe |

Gemma4官方说明global层统一K/V，但配置中的 `attention_k_eq_v=true` **不能直接当实际cache减半证明**。本轮读取的vLLM `gemma4.py` 将K权重放入K和V投影槽，再调用普通 `Attention(q,k,v)`；其KV表示、重排权重与引擎优化都须在锁定版本上验证。上表采用分别存储K/V的保守算术，不把模型结构宣传当cache实现。[固定Gemma配置](https://huggingface.co/google/gemma-4-31B-it/blob/842da3794eaa0b77d5f08bae87a17459d91ff475/config.json)、[vLLM当前实现](https://github.com/vllm-project/vllm/blob/main/vllm/model_executor/models/gemma4.py)（读取日期2026-10-12；此main链接不是已锁定部署版本）。

GLM官方索引 `metadata.total_size=31,221,488,576` 恰好等于API所列BF16 31,221,485,568与F32 3,008的**元素数量之和**，与48个权重文件合计62,444,175,504 bytes不一致。按dtype元素数乘2／4为62,442,983,168 bytes，接近文件总量，支持索引字段口径有误的判断；本轮未读权重张量，不将该29.077GiB字段当已证实BF16显存。GLM采用MLA，不能套普通GQA表，实际engine cache representation与单卡峰值待验证。[固定GLM索引](https://huggingface.co/zai-org/GLM-4.7-Flash/blob/7dd20894a642a0aa287e9827cb1a1f7f91386b67/model.safetensors.index.json)、[固定GLM文件目录](https://huggingface.co/zai-org/GLM-4.7-Flash/tree/7dd20894a642a0aa287e9827cb1a1f7f91386b67)。

## 5. M3现有成绩怎样作为参考

仓库保存了一次独立MiniMax-M3第五轮变量识别运行 `c4bb7f20-cde1-4bae-8205-1da79f2a2375`，耗时约269.933秒；有值58/58正确、留空21/21正确，自动结果仍为42 PASS／37 REVIEW／0 FAIL；37个REVIEW、38个候选引文另经独立原文语义复核均得到支持。来源是 [工具说明第65行](../../tools/drafting_eval/README.md)、[已保存运行说明](../testing/m3-round5-20261009/README.md)、[逐候选语义复核](../testing/m3-round5-20261009/semantic-audit.md)。这些是一次保存运行的结果，本轮没有重跑。

该历史工具使用“MiniMax 国内 Token Plan”前端入口；用户现在所说完整官方M3 API是当前质量判断。两者必须在新实测时分别绑定endpoint／profile和完整wire，不能据相同model字段断言供应商后端权重或资源相同。上述固定题目已公开并参与harness开发，不是新未见题；变量成绩不证明最终文稿、采用、完整Vetting或Advisory。

历史Bonsai27最高60/61与后续第五轮51/58、离线replay56/58均保留原事实，且Bonsai ternary27B不是Qwen dense27B。旧失败包含输入分片、JSON协议、required字段和引文连续性；从这些记录不能推出所有27B不足，也不能把M3另一次真实运行成绩覆盖到旧Bonsai运行。[Bonsai已测结果](../../../docs/drafting-model-benchmark-results.md)、[第四／第五轮冻结记录](../../../docs/drafting-harness-rounds-4-5-20261009.md)。

Advisory已有一次真实M3 bridge验证，但配置身份不证明provider权重；官方Q16首轮只采用GCC65–70及SCC17相关whole units，不是完整合同，且未保存provider原始usage。已有Vetting完成终态亦有token observer缺失等pending残差。选型时须保留这些系统侧归因，不能把未提交资料后的拒答、coverage残差或partial一律记为模型推理错。[实际Advisory](../testing/oer-actual-advisory-canonical-bridge-validation-20261010.md)、[Q16首轮结果](../testing/oer-official-q16-first-business-result-20261010.md)、[Vetting partial与预算诊断](../testing/oer-official-two-source-vetting-completed-partial-20261010.md)。

## 6. 下一步的评价设计，不是本轮实施

1. **冻结题目、资料与recipe。** Drafting沿用现有harness契约，保持其源码和评分器；既有公开题做回归，再由独立作者准备未见留出题。Vetting／Advisory使用当前采用来源与完整结构闭包；语义识别用带身份、角色、unknown和独立标签的有限样本。gold不进入模型上下文，也不靠另一个LLM判断自己得分。
2. **固定同源输入，再记录每模型实际tokens。** 相同原文、来源字典、conditions、表格与任务schema按各模型官方tokenizer及chat template计数。一个较小共同档可先设总序列32K；任何recipe超过档位须记录不适用／overflow，不能静默裁掉条件。长资料另作64K／128K及模型原生档；M3超长API能力作为单独系统实验，不把它与已裁输入的本地模型合成一个分数。
3. **分别比较思考和结构输出。** 先确保final channel、finish reason、schema和引用可观测；non-thinking与有界thinking各是recipe，思考也计生成预算、时间与序列长度。Qwen的正式开关为template的 `enable_thinking`；Gemma4用官方thinking模板且历史通常只保留final；GPT-OSS使用Harmony。不能将某模型的模板开关或parser套给另一家。[Qwen3.6模板](https://huggingface.co/Qwen/Qwen3.6-27B)、[Gemma4模板规范](https://ai.google.dev/gemma/docs/core/model_card_4)、[Harmony](https://developers.openai.com/cookbook/articles/openai-harmony)。
4. **按任务与失败类型出表。** 字段正确、正确留空、冲突／未知、引文支持、条件／例外、表格完整、来源采用、关键错误、JSON／protocol失败、truncation、timeout／OOM各有自己的分母和出处。部分成功、不适用和未执行不能填成0错误。最终文稿、完整报告和前端流程另评，不能由variable score代替。
5. **再验证H800 recipe。** 锁checkpoint SHA、engine commit／image digest、CUDA／driver、attention／MoE kernel、dtype、weight／KV quant、window、生成预算、并发、E/R设备及unload方式。观察加载峰值、未缓存prefill、长decode、CPU／PCIe流量、冷／热切换、失败恢复与真实业务链；只对实际请求报告性能。把两套9B／35B同时常驻以避免切换只是待测方案，不能先从文件体积相加宣布可用。

目标是获得分任务的质量／资源Pareto边界：对高风险Vetting，关键错误和来源完整性优先；对有限语义识别，在同样质量门下再比较吞吐；Drafting用M3既有表现对照新本地recipe；Advisory分别判断检索和回答。在未测前不宣布单一胜者，也不人为要求每任务一定用不同模型。按任务切换模型的额外加载、RAM缓存、准入排队和可恢复性也进入最终部署取舍。

## 7. 固定revision与复算来源

所有公开规格／metadata读取日期为2026-10-12；未下载safetensors内容。HF公开入口是 `https://huggingface.co/{model}/resolve/{revision}/config.json`、同目录 `model.safetensors.index.json`，以及 `https://huggingface.co/api/models/{model}/revision/{revision}?blobs=true` 的文件长度。先确认total_size含义／存在性，再采用payload或file口径。

| 模型ID | 固定revision | 本轮数字来源 |
| --- | --- | --- |
| `Qwen/Qwen3-8B` | `b968826d9c46dd6066d109eabc6255188de91218` | config＋index total_size |
| `Qwen/Qwen3.5-9B` | `c202236235762e1c871ad0ccb60c8ee5ba337b9a` | config＋index total_size |
| `Qwen/Qwen3.5-27B` | `fc05daec18b0a78c049392ed2e771dde82bdf654` | config＋index total_size |
| `Qwen/Qwen3.6-27B` | `6a9e13bd6fc8f0983b9b99948120bc37f49c13e9` | config＋index total_size |
| `Qwen/Qwen3.6-27B-FP8` | `e89b16ebf1988b3d6befa7de50abc2d76f26eb09` | config；index为空；66权重file bytes之和 |
| `Qwen/Qwen3.5-35B-A3B-FP8` | `9d1823d2dee688a6b25e77009dc727688c44936e` | config＋index total_size |
| `Qwen/Qwen3.6-35B-A3B-FP8` | `95a723d08a9490559dae23d0cff1d9466213d989` | config；index为空；42权重file bytes之和 |
| `google/gemma-4-31B-it` | `842da3794eaa0b77d5f08bae87a17459d91ff475` | config＋index；2权重file长度作交叉核验 |
| `zai-org/GLM-4.7-Flash` | `7dd20894a642a0aa287e9827cb1a1f7f91386b67` | config；index字段异常；48权重file bytes＋dtype metadata交叉核验 |
| `Qwen/Qwen2.5-72B-Instruct-AWQ` | `698703eae6604af048a3d2f509995dc302088217` | config＋index total_size；模型卡YaRN与license |
| `openai/gpt-oss-120b` | `b5c939de8f754692c1647ca79fbf85e8c1e70f8a` | config＋index total_size；官方model／Harmony文档 |

权重／KV算术是研究推导；没有runtime显存、CUDA内核、latency、并发、卸载恢复或新业务成绩。模型许可、公开config和当前main实现会变化，正式冻结时以精确revision／commit和实际目标环境重新核验。
