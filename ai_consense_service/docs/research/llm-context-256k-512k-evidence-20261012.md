# 主 LLM 的 256K／512K 上下文证据调查

更新：2026-10-12，Asia/Shanghai。调查问题：按 ConSense 实际任务质量选择主 LLM 时，哪些候选真正满足至少 256K、优选 512K 的上下文要求；官方窗口、扩展配方、引擎支持及该长度下的质量分别有什么证据。

本报告服从 [当前规则](../oer-scope-and-rules.md) 与 [当前交接](../HANDOFF-ocr-embedding-rerank-20261008.md)。用户已明确 OCR 与业务 LLM 不同时使用，允许阶段加载／卸载；不重新询问业务生命周期。选型顺序是实际任务与经验 → 长上下文质量 → 单 H800 的部署准入，不按参数量、dense／MoE或权重体积先排主次。当前仅研究公开资料与只读模型 metadata，没有下载权重、调用模型／API、修改冻结 harness、切换服务或部署 K8s。

## 1. 可以据此做出的决定

1. **当前原生 256K 入围**：Qwen3.8-27B／官方 FP8、Qwen3.8-Flash-Next／官方 FP8，以及 Gemma4-31B-it。Qwen3.8 有明确的官方 512K YaRN 配方；Gemma4 尚未核得官方 512K 配方。Qwen3.6-27B／35B-A3B 保留为历史配方与质量对照，不因旧版本入围而优先于新版本。这是长度入围，不是本项目任务质量已通过。
2. **有具体 512K／1M 公布质量证据**：Nemotron 3 Nano／Super 的 RULER 与 DeepSeek V4 的 1M MRCR／CorpusQA。但检索合成题成绩、长文推理成绩和中文法律任务分别判断，不能只凭最大窗口主选 Nano。
3. **MiniMax M3 保留完整质量参照**：官方支持 1M，用户已有完整 M3 API 的 Drafting 正面经验，开放权重与 self-host 路线确实存在。本次未核得完整 M3 在 256K／512K 的公开 RULER／MRCR 分数，不能用另一尺寸的研究实验代填。
4. **Granite4.1／4.2 暂列长上下文研究备选**：官方称可扩展 512K，但已核配置与原生长度是 128K，公开部署样例也仍是 128K，未核得完整 ≥256K 配方与该长度质量证据。因此暂不作为已经满足主模型硬门的候选。
5. **旧短窗候选退出主方案**：GPT-OSS-120B、GLM-4.7-Flash、Qwen2.5-72B-Instruct 的已核官方上限低于 256K。可以留作短任务能力对照，不能用未授权的截断／预摘要来绕过主方案窗口门。

入口最新记录的本机 Bonsai 为 Qwen3.8-27B 底座的 Ternary-Bonsai-2-27B-PTQ1_0，用户报告效果不足；这一实际失败必须保留。它既不能由旧版本 27B 的公开成绩抵消，也不能直接泛化为同底座 BF16／FP8 的失败。Qwen3.8 官方原生窗口、扩窗配方与固定制品身份已独立核查，不能借用 Qwen3.6 的窗口或成绩替它背书。

最新 [本机归因核查](./bonsai-local-context-capacity-attribution-20261012.md)确认第五轮服务为64K；本地受限配方未验证完整256K／512K，FAIL不代表H800足窗口下的模型上限，但该轮完整短源残差没有窗口耗尽证据。[更大候选补充](./llm-h800-larger-model-candidates-20261012.md)现已核到Flash-Next专用recipe的vLLM0.29.0+／SGLang0.5.20+要求；这是该模型该recipe的后续事实，不补作下表27B所有配置的最低版本。

## 2. 总窗口必须给推理和输出留位置

服务端上下文预算至少覆盖：

`系统与任务模板 + 历史／工具消息 + 当前业务输入／完整证据 + 本轮推理与最终输出 ≤ 引擎实际上下文上限`

具体推理 token 是否保留在后续历史、如何计入 API usage 与 KV，按模型模板及引擎实际记账核验；不能假定 thinking 免费或位于窗口之外。Qwen 官方长上下文配方明确区分包含输入及输出的总长度（[Qwen3.8-27B 固定卡](https://huggingface.co/Qwen/Qwen3.8-27B/blob/1d4bf0f2ff6012fd82039f2fa52739d0dd7c60c0/README.md)、[Qwen3.6-27B 卡](https://huggingface.co/Qwen/Qwen3.6-27B#processing-ultra-long-texts)）。

| 总上限示例 | 为推理＋正文预留 | 为模板／历史／工具预留 | 当前证据剩余量 | 数字身份 |
|---|---:|---:|---:|---|
| 262,144 | 32,768 | 4,096 | 225,280 | `262144−32768−4096` 的算术示例 |
| 524,288 | 32,768 | 4,096 | 487,424 | `524288−32768−4096` 的算术示例 |

32,768 与 4,096 是解释预算的假设，未设为业务批准限额。若一份业务输入本身需要完整 256K token，原生 256K **总**窗口不能在保留输出后满足它；应选择更大的官方窗口或配方。多轮历史、工具结果和超长推理会进一步减少当前证据容量。每个候选必须用自己的 tokenizer 计数，不能拿某供应商的 token 数或文件页数直接换算另一模型。

“256K／512K／1M”先按官方标签记录，再绑定准确 token 上限。已核 Qwen／Gemma 的 256K 为 262,144；Qwen 官方建议的 512K 配方为 524,288；MiniMax M3 配置和 Nemotron Super 的 1M 引擎配方为 1,048,576。不同来源的测试长度标签不自动统一成同一个精确整数。

## 3. 候选模型：长度、引擎与质量分开记录

| 候选与一手依据 | 原生／默认配置 | 官方较长窗口路线 | 已核引擎支持 | 针对长文质量的证据与缺口 | 本轮定位 |
|---|---|---|---|---|---|
| [Qwen3.8-27B](https://huggingface.co/Qwen/Qwen3.8-27B)／[官方 FP8](https://huggingface.co/Qwen/Qwen3.8-27B-FP8) | 原生及默认 262,144 | 官方命令上限 **1,000,000**；512K 明确 `factor=2.0`、524,288；最大配方用 `factor=4.0` | 官方卡建议 latest framework并链接 vLLM／SGLang recipe；本轮未核得数值最低版本，不套用旧卡版本 | 本项目三进制 Bonsai 失败已知；不能据此宣布 BF16／FP8 失败，也不能用 hosted／旧版成绩追认本地通过。README 未核得 RULER／MRCR／分长度 LongBench 结果 | 当前 256K／512K 长窗主候选，质量按任务筛选 |
| [Qwen3.8-Flash-Next](https://huggingface.co/Qwen/Qwen3.8-Flash-Next)／[官方 FP8](https://huggingface.co/Qwen/Qwen3.8-Flash-Next-FP8) | 原生 262,144；配置 `qwen4_exp_text` | 同版官方扩至 **1,000,000**，512K 为 factor2 配方 | 官方建议 latest framework；新架构／词表组件按本版部署说明验收，不把27B或旧MoE的支持自动移植，未核数值最低版本 | 参数身份约125B／6B active，另有51B n-gram embedding＋4B MTP；README未核得RULER／MRCR／分长度LongBench结果；活动参数不代表完整权重／质量 | 新版 MoE 质量候选；不受约120B历史锚约束 |
| [Qwen3.6-27B](https://huggingface.co/Qwen/Qwen3.6-27B)／[35B-A3B](https://huggingface.co/Qwen/Qwen3.6-35B-A3B) | 原生及默认 262,144 | 官方最大 **1,010,000**；常用 512K 时建议 `factor=2.0`、524,288；最大配方用 `factor=4.0` | 官方建议 vLLM≥0.19.0／SGLang≥0.5.10；需官方 RoPE overrides 与允许更长窗口的开关 | 卡中 agent／coding 的 200K／256K 设置不足以证明 512K 完整证据推理；本轮未发现这两个 checkpoint 的官方 RULER／MRCR／LongBench v2 分长度成绩 | 历史配方／能力对照，不列为默认主选 |
| [Gemma4-31B-it](https://huggingface.co/google/gemma-4-31B-it)／[Google 卡](https://ai.google.dev/gemma/docs/core/model_card_4) | 原生 256K；配置 262,144 | 本轮未核得官方 512K／1M 配方 | [vLLM 官方 Gemma4 配方](https://docs.vllm.ai/projects/recipes/en/latest/Google/Gemma4.html)已支持模型；31B 示例 TP2 的 `max-model-len=32768` 只证实支持，不能当作 256K 容量验收 | MRCR v2，8 needles，128K average＝66.4%；不是 256K／512K 成绩 | 原生 256K 候选；不宣称已满足 512K |
| [MiniMax-M3](https://huggingface.co/MiniMaxAI/MiniMax-M3) | 官方 1M；配置 1,048,576 | checkpoint 本身支持，非本项目临时 RoPE 扩窗 | [官方 self-host](https://platform.minimax.io/docs/guides/local-deploy-m3)为 Experimental；已核多卡参考，不证明单 H800；[官方 API](https://platform.minimax.io/docs/api-reference/text-openai-api)与本机已有正面经验另记 | 用户 Drafting 经验成立；未核得完整 M3 的 256K／512K／1M 分长度 RULER／MRCR 质量表 | 任务质量参照与长窗候选；资源准入后置 |
| [Nemotron 3 Nano 30B-A3B](https://huggingface.co/nvidia/NVIDIA-Nemotron-3-Nano-30B-A3B-BF16) | 官方支持 1M；默认配置 262,144，为显存考虑保守设置 | 官方明确 `VLLM_ALLOW_LONG_MAX_MODEL_LEN=1` 与 `--max-model-len 1M` 配方 | vLLM≥0.12.0；自定义 `nano_v3` reasoning parser；Transformers≥5.3.0 | RULER-100@256K／512K／1M＝92.9／91.3／86.3；AA-LCR＝35.9。官方支持语言列表未列中文，须做中文业务验证 | 长窗证据充分的研究候选，不能仅按 RULER 主选 |
| [Nemotron 3 Super 120B-A12B](https://huggingface.co/nvidia/NVIDIA-Nemotron-3-Super-120B-A12B-BF16) | 官方 1M；默认 262,144 | 官方允许 override 至 1,048,576 | 卡有 vLLM 0.18.1 与 SGLang 路线；BF16 基线列 8×H100-80GB | RULER@256K／512K／1M＝96.30／95.67／91.75；AA-LCR＝58.31 | 补充质量候选；120B 不是规模要求，单卡能否部署后验 |
| [Granite4.1 官方说明](https://github.com/ibm-granite/granite-4.1-language-models)；[Granite4.2-8B](https://huggingface.co/ibm-granite/granite-4.2-8b)／[30B](https://huggingface.co/ibm-granite/granite-4.2-30b) | 已核 4.1-3B／8B／30B、4.2-8B／30B 默认 131,072，`rope_scaling=null`；4.2 明写原生 128K | 4.1 训练说明提及扩至 512K；4.2 声明 long-context extension to 512K；本輪未核得完整 ≥256K 部署参数 | 4.2 卡：vLLM≥0.20＋`granite_thinking_parser`，SGLang≥0.5.18；样例仍设 131,072 | 4.2 公布 RULER 64K／128K，尚无已核 256K／512K 成绩；新版本发布不能替代该缺口 | 512K 研究备选，暂不认定满足主模型硬门 |
| [DeepSeek-V4-Flash／Pro](https://huggingface.co/deepseek-ai/DeepSeek-V4-Flash) | 官方 Preview 1M；Flash 配置 1,048,576，包含官方训练后的 YaRN（origin 65,536、factor16） | checkpoint 官方训练／支持的 1M，与用户任意改 RoPE 有别 | 官方 inference／encoding 配方需单独适配；本轮不从 HF 自动生成示例推断通用引擎最低版本 | instruct 模式公布 1M MRCR／CorpusQA，且对 reasoning budget 敏感；Think Max 建议窗口至少 384K | 有较直接长文推理证据的质量候选，资源可行性后置 |
| [GLM-5.2](https://huggingface.co/zai-org/GLM-5.2)／[5.3](https://huggingface.co/zai-org/GLM-5.3) | 5.2 官方 1M；两者配置 1,048,576，5.3 说明基于 5.2 底座 | 官方 checkpoint 路线 | 5.2 卡：SGLang≥0.5.13.post1／vLLM≥0.23.0；不能据此追认 5.3 的所有路径已验证 | 长程 coding agent 的 1M／400K 配置不是法律长文成绩；本轮未取得这两个版本的 RULER／MRCR／LongBench 分长度结果 | 长窗补充候选，任务质量证据仍需完善 |

Qwen3.8 与旧 Qwen3.6 的官方 YaRN 均包括 `original_max_position_embeddings=262144`、`rope_theta=10000000`、`partial_rotary_factor=0.25`、`mrope_interleaved=true` 与 `mrope_section=[11,11,10]`，不能只改 `max_model_len`；**3.8-27B／Flash-Next 的官方 cap 是 1,000,000，旧3.6是1,010,000**。static YaRN 可能影响短输入，因此原生 256K 与扩展 512K 必须作为两个运行身份比较。引擎能启动、metadata 上限较大、RoPE 数值可修改，都不等于跨页例外、优先级和完整表组在该长度下判断正确。

## 4. 已核公开长上下文成绩的准确含义

下表为供应商公开结果，未经本项目复跑。跨供应商的提示、采样、reasoning预算、任务子集与计数方式不保证一致，因此不能拼成一个无条件总排名。每行链接指向发布该数字的一手来源。

| 模型／制品 | 测试与实际长度标签 | 公布结果 | 可以支持的判断 | 尚不能支持的判断 |
|---|---|---:|---|---|
| [Gemma4-31B-it](https://ai.google.dev/gemma/docs/core/model_card_4) | MRCR v2，8 needles，128K average | 66.4% | 存在 128K 多目标检索证据 | 256K／512K 完整法律文书推理通过 |
| [Nemotron Nano](https://huggingface.co/nvidia/NVIDIA-Nemotron-3-Nano-30B-A3B-BF16) | RULER-100@256K／512K／1M | 92.9／91.3／86.3 | 对官方合成长窗测试较强，随长度下降 | 本项目中文审查、冲突处理及 Drafting 达标 |
| 同上 | AA-LCR | 35.9 | 实际长文多步推理较合成检索更值得警惕 | 用高 RULER 抵消推理短板 |
| [Nemotron Super](https://huggingface.co/nvidia/NVIDIA-Nemotron-3-Super-120B-A12B-BF16) | RULER@256K／512K／1M | 96.30／95.67／91.75 | 有这三个长度的公开合成题结果 | 单 H800 峰值／吞吐或业务质量验收 |
| 同上 | AA-LCR | 58.31 | 具备可对照的长文推理结果 | 与未统一设置的其他模型直接判胜负 |
| [Granite4.2，3B／8B／30B](https://huggingface.co/ibm-granite/granite-4.2-30b) | RULER 64K | 67.52／80.99／89.96 | 能比较这次公布设置内的尺寸差异 | 外推至 256K／512K |
| 同上 | RULER 128K | 55.30／71.41／81.38 | 128K 结果低于同卡 64K 结果，长度影响须保留 | 把“512K扩展”文字当作同质量承诺 |
| [DeepSeek V4 Flash instruct](https://huggingface.co/deepseek-ai/DeepSeek-V4-Flash) | MRCR 1M（MMR），Non-Thinking／Think High／Think Max | 37.5／76.9／78.7 | 官方 1M 长上下文结果且明显受推理模式影响 | 默认模式等于 Max 模式成绩 |
| 同上 | CorpusQA 1M（ACC），同三模式 | 15.5／59.3／60.5 | 有 1M 语料问答证据 | 中文施工合同题与准确引用已通过 |
| [DeepSeek V4 Pro instruct](https://huggingface.co/deepseek-ai/DeepSeek-V4-Flash) | MRCR 1M（MMR），同三模式 | 44.7／83.3／83.5 | 强长窗对照；不依据总参数先淘汰 | 各任务都比 Flash 强或单卡可部署 |
| 同上 | CorpusQA 1M（ACC），同三模式 | 35.6／56.5／62.0 | High／Max 与 Flash 的相对结果不完全相同 | 用单一综合分数代替任务选择 |

Nano 卡中的 Qwen 比较对象是 **Qwen3-30B-A3B-Thinking-2507**：AA-LCR＝59.0，RULER＝89.4／84.0／77.5。这不能作为 Qwen3.6 或 Qwen3.8 的分数；它也说明合成检索更强和实际推理更强可以出现在不同模型上。Nano 多语言 MMLU-ProX＝59.5、该旧 Qwen 对照＝77.6，只用于提示语言能力风险，不能直接代替中文业务得分（[同一卡](https://huggingface.co/nvidia/NVIDIA-Nemotron-3-Nano-30B-A3B-BF16)）。

MiniMax 的 [MSA 论文](https://arxiv.org/html/2606.13392v1)中 MSA-CPT 的 RULER128K＝72.12、HELMET128K＝45.93，来自 **109B total／6B active** 实验模型，而完整 M3 是 **428B total／23B active**；不能把论文表 3 结果标成完整 M3 或 1M 质量。完整 M3 的本机经验、官方窗口和未取得的分长度分数分别保留，详见 [M3 专项调查](./minimax-m3-evaluation-reference-20261012.md)。

Nemotron Super 的 [官方 NVFP4 卡](https://huggingface.co/nvidia/NVIDIA-Nemotron-3-Super-120B-A12B-NVFP4)明确兼容硬件为 **Blackwell**，最低参考为 1×B200 或 1×DGX Spark；不能将该制品认定为 H800／Hopper 可用配方。[BF16 仓库只读 metadata](https://huggingface.co/api/models/nvidia/NVIDIA-Nemotron-3-Super-120B-A12B-BF16)在本轮固定 SHA `2dc98e2afe4face0e4ce40972a915c45368bd34a` 返回 `safetensors.total=123611012096`。按每参数 2／1／0.5 byte 的理想算术，分别约 247.22／123.61／61.81GB，最后一个约 57.56GiB；这没有计入量化 scale、未量化层、引擎工作区与状态，也不是已经存在的 H800 4bit 制品验收。Super 的 Mamba recurrent state、Attention KV 与 MTP 状态不能仅按 12B active 或纯 Transformer 公式估计；256K／512K 单卡准入仍待真实目标配方。

同样，Qwen3.8-Flash-Next 的 [官方 FP8 固定索引](https://huggingface.co/Qwen/Qwen3.8-Flash-Next-FP8/resolve/236dfdf285828023ca3bcd3f37366c58a3469b13/model.safetensors.index.json)给出 `metadata.total_size=185502232570` byte，约172.7624GiB、131shards；这已经高于单 H800 的全驻留容量，不能因“6B active”宣称容易放进80GB。该事实属于质量候选筛选后的部署约束：可以保留质量参照及后续经过验证的量化／offload研究，但不能把未核路线当作已满足正式单卡主方案。

## 5. 三类 benchmark 不能互相冒名

- [RULER 官方](https://github.com/NVIDIA/RULER)包含合成检索、多跳、聚合和 QA；普通 needle-in-a-haystack 可以很高，复杂长上下文表现仍会下降。报告需保留版本、题型组合、样本数、目标长度与截断策略；Nano 的 RULER-100 标签与其他卡的 RULER 标签不擅自视为完全同一配方。
- [AA-LCR 官方说明](https://artificialanalysis.ai/evaluations/artificial-analysis-long-context-reasoning)：100 道多步问题，包含法律／政府／报告，内容长度约 10K–100K，以 `cl100k_base` 计数。它对本项目推理责任较相关，但不是 512K 长度验收。本轮没有把搜索镜像里的 M3／Qwen 分数升级为已核一手成绩。
- [LongBench v2 官方](https://github.com/THUDM/LongBench)：503 道多选题、六类真实任务，长度写为 **8K–2M words**，并非各模型 token 数。整体得分不自动证明 512K／1M 上限可用；需查看 split、原文覆盖、模型 tokenizer 和截断规则。

候选之间可以比较同一次官方表的结果，同时必须把每个模型放回本项目真实任务：事实／条款定位、作用范围与例外、跨文件优先级冲突、Vetting 发现、Advisory 意见及 Drafting。代码 agent、竞赛数学和单 needle 高分不补足这些证据责任。

## 6. 已核旧候选的退出依据

| 原候选 | 已核官方长度 | 依据 | 主方案处理 |
|---|---:|---|---|
| GPT-OSS-120B | 131,072 | [OpenAI 模型文档](https://developers.openai.com/api/docs/models/gpt-oss-120b) | 低于 256K，退出长窗主方案 |
| GLM-4.7-Flash | 202,752 | [固定 revision 配置](https://huggingface.co/zai-org/GLM-4.7-Flash/blob/7dd20894a642a0aa287e9827cb1a1f7f91386b67/config.json) | 同上 |
| Qwen2.5-72B-Instruct | 默认 32,768；官方 YaRN 完整窗口 131,072 | [官方卡](https://huggingface.co/Qwen/Qwen2.5-72B-Instruct)、[固定配置](https://huggingface.co/Qwen/Qwen2.5-72B-Instruct/blob/495f39366efef23836d0cfae4fbe635880d2be31/config.json) | 同上；不能把旧 YaRN 支持称作 256K |

它们的旧任务经验与部署算术可作为历史／对照证据保留。未经同长度业务验收的任意扩窗，不能让它们重新满足主方案门。

## 7. 本轮只读冻结的配置身份

这些公开配置与仓库 revision 是本次调查时的 metadata，不是本地制品安装／部署证明。`max_position_embeddings` 是默认配置字段；Nano／Super 与 Granite 的官方扩展声明必须结合上文阅读，不能只看该列下结论。

| 模型 | 不可变 revision／配置链接 | 默认 `max_position_embeddings` |
|---|---|---:|
| Qwen3.8-27B | [1d4bf0f2ff6012fd82039f2fa52739d0dd7c60c0](https://huggingface.co/Qwen/Qwen3.8-27B/blob/1d4bf0f2ff6012fd82039f2fa52739d0dd7c60c0/config.json) | 262,144 |
| Qwen3.8-27B-FP8 | [017b9c7af6b5689d5dd426a76e0bc077eb5ca20a](https://huggingface.co/Qwen/Qwen3.8-27B-FP8/blob/017b9c7af6b5689d5dd426a76e0bc077eb5ca20a/config.json) | 262,144 |
| Qwen3.8-Flash-Next | [de4b8e4d43b917e7706784d8bb445c9af86a3540](https://huggingface.co/Qwen/Qwen3.8-Flash-Next/blob/de4b8e4d43b917e7706784d8bb445c9af86a3540/config.json) | 262,144 |
| Qwen3.8-Flash-Next-FP8 | [236dfdf285828023ca3bcd3f37366c58a3469b13](https://huggingface.co/Qwen/Qwen3.8-Flash-Next-FP8/blob/236dfdf285828023ca3bcd3f37366c58a3469b13/config.json) | 262,144 |
| Qwen3.6-27B | [6a9e13bd6fc8f0983b9b99948120bc37f49c13e9](https://huggingface.co/Qwen/Qwen3.6-27B/blob/6a9e13bd6fc8f0983b9b99948120bc37f49c13e9/config.json) | 262,144 |
| Qwen3.6-35B-A3B | [995ad96eacd98c81ed38be0c5b274b04031597b0](https://huggingface.co/Qwen/Qwen3.6-35B-A3B/blob/995ad96eacd98c81ed38be0c5b274b04031597b0/config.json) | 262,144 |
| Gemma4-31B-it | [842da3794eaa0b77d5f08bae87a17459d91ff475](https://huggingface.co/google/gemma-4-31B-it/blob/842da3794eaa0b77d5f08bae87a17459d91ff475/config.json) | 262,144 |
| MiniMax-M3 | [f0e1c1e04d40177e4673a22097036854f536e9c0](https://huggingface.co/MiniMaxAI/MiniMax-M3/blob/f0e1c1e04d40177e4673a22097036854f536e9c0/config.json) | 1,048,576 |
| Nemotron 3 Nano BF16 | [bf77c3174f68ad409e1c2aa60daeb46e32d1c606](https://huggingface.co/nvidia/NVIDIA-Nemotron-3-Nano-30B-A3B-BF16/blob/bf77c3174f68ad409e1c2aa60daeb46e32d1c606/config.json) | 262,144 |
| Nemotron 3 Super BF16 | [2dc98e2afe4face0e4ce40972a915c45368bd34a](https://huggingface.co/nvidia/NVIDIA-Nemotron-3-Super-120B-A12B-BF16/blob/2dc98e2afe4face0e4ce40972a915c45368bd34a/config.json) | 262,144 |
| Granite4.1-3B | [c0650403e44e78ec0262dab1c90914c65b196c4e](https://huggingface.co/ibm-granite/granite-4.1-3b/blob/c0650403e44e78ec0262dab1c90914c65b196c4e/config.json) | 131,072 |
| Granite4.1-8B | [1504002f650e656a0a3789d99574df12e3e94ed0](https://huggingface.co/ibm-granite/granite-4.1-8b/blob/1504002f650e656a0a3789d99574df12e3e94ed0/config.json) | 131,072 |
| Granite4.1-30B | [4fae6278f7132abf5e971f9de49ebbad09c54cce](https://huggingface.co/ibm-granite/granite-4.1-30b/blob/4fae6278f7132abf5e971f9de49ebbad09c54cce/config.json) | 131,072 |
| Granite4.2-8B | [f8de16cdcdbc6c779ca517604e050d82cc119e44](https://huggingface.co/ibm-granite/granite-4.2-8b/blob/f8de16cdcdbc6c779ca517604e050d82cc119e44/config.json) | 131,072 |
| Granite4.2-30B | [9e668ce1c538387ef24d3644e9b0606647762636](https://huggingface.co/ibm-granite/granite-4.2-30b/blob/9e668ce1c538387ef24d3644e9b0606647762636/config.json) | 131,072 |
| DeepSeek-V4-Flash | [60d8d70770c6776ff598c94bb586a859a38244f1](https://huggingface.co/deepseek-ai/DeepSeek-V4-Flash/blob/60d8d70770c6776ff598c94bb586a859a38244f1/config.json) | 1,048,576 |
| GLM-5.2 | [cf457fa734ab149ffef225f80893eb38c6ff5cdc](https://huggingface.co/zai-org/GLM-5.2/blob/cf457fa734ab149ffef225f80893eb38c6ff5cdc/config.json) | 1,048,576 |
| GLM-5.3 | [aca966e4e02791568aa6a4ced368624b3d897f42](https://huggingface.co/zai-org/GLM-5.3/blob/aca966e4e02791568aa6a4ced368624b3d897f42/config.json) | 1,048,576 |

## 8. 如何把这些证据变成项目选择

公开证据已能排除旧短窗主模型、区分原生与扩窗，并识别 Nano 的合成检索／实际推理差异及 DeepSeek 的推理预算敏感性；还不能直接宣布某个模型已通过本项目。后续评价在既定实施顺序到达 LLM 阶段后进行，不因此现在启动新运行。

评价应使用已经清楚的任务分类与相同完整来源，至少记录以下内容：

1. **身份**：模型与量化制品／revision、tokenizer／template、engine版本、原生或官方扩窗配方、推理模式与 output budget。Qwen 原生 256K 与 YaRN 512K 分开；MiniMax API 与开放权重分开；Bonsai 三进制与 BF16／FP8 分开。
2. **实际长度**：每模型真实 token 数，完整证据覆盖及留给 reasoning／正文的余量；长度分组覆盖正常任务、接近 256K 和接近 512K。不能只靠无语义填充或把短题人为复制成长题；关注结论的证据位置、密集例外、跨页／跨文档依赖及历史增长。
3. **正确性**：关键条款与完整表组召回、条件／例外／优先级正确、引文定位与原文忠实、发现／建议的可执行性、来源不足时 unknown、Drafting 与确认值一致。以任务家族分别记错漏与严重缺陷，不用一个综合分抵消法律关键错误。
4. **公平设置**：本机 M3 正面运行作为经验基线，统一来源与输出责任；推理预算、采样与工具协议保留差异并报告。优先比较已有公开证据较强的新模型，旧参数规模不设为入场限制。公开榜单与本项目实测单独记录。
5. **质量通过后的 H800 门**：在目标 Linux／K8s、单 H800 上核权重＋KV＋工作区峰值、256K／512K prefill、decode、单请求耗时与恢复。OCR 与 LLM 分时减少共驻权重，但不消除长窗口 KV；query embedding／rerank 的阶段也要计入完整业务延迟。显存不能装下时明确保留失败，不能删关键上下文来追认成功。资源策略详见 [显存调度调查](./oer-h800-memory-scheduling-20261012.md)。

最终主方案要同时满足：真实任务质量、足够的总窗口及目标环境部署。此次研究对前两项提供公开证据与准确缺口，没有把公开窗口、示例命令或 metadata 检查记为本地／H800验收。
