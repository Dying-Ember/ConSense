# 单 H800 80GB 的 LLM 选型研究

研究日期：2026-10-12（Asia/Shanghai）。本轮只读项目及官方资料，保存研究结论；未下载模型权重、未调用审查模型、未构建镜像、未修改运行配置或部署 H800。

## 结论与前提

用户已明确 **OCR 与 LLM 不会同时使用，可以分别卸载**。因此先按 LLM 阶段拥有整卡判断主模型能力与容量，再单独核算在线 query embedding／rerank 的共驻或阶段切换。没有必要为了保留 OCR 常驻而强迫采用较小 LLM；也不能把暂时卸载 OCR 后的瞬时 free memory 当成长请求、并发或辅助模型安全共驻的证明。

**最新选型依据是实际任务，约120B MoE不是预设最终主模型。** Drafting 内容、Vetting、Advisory 和语义识别分别评价，小型 dense、27B dense／hybrid、35B 小 MoE、Gemma 独立 dense 家族与较大 dense 量化模型均可进入对照；完整 MiniMax M3 官方 API 保留为用户当前认可的 Drafting 质量参考。具体候选、官方规格与评价方案见 [按任务的模型候选研究](./llm-task-model-candidates-20261012.md)。本文保留 GPT-OSS-120B 原生 MXFP4 的约120B容量案例，以及 Qwen3.5-27B BF16 和 Qwen3.5／3.6-35B-A3B FP8 的资源对照，不据容量直接排名业务质量。Qwen3.5-122B-A10B 官方 GPTQ-Int4 的完整 checkpoint payload 约 **73.429 GiB**，128K 全注意力 KV 再加 **3 GiB**，尚未计线性状态、运行时重排、prefill 工作区及设备余量，仍属容量边界研究项。最终选择由同源、分任务业务评价与目标资源实测决定。[GPT-OSS 官方规格](https://developers.openai.com/api/docs/models/gpt-oss-120b)、[Qwen3.5-27B 官方卡](https://huggingface.co/Qwen/Qwen3.5-27B)、[35B-A3B 官方 FP8](https://huggingface.co/Qwen/Qwen3.5-35B-A3B-FP8)、[122B 官方 GPTQ-Int4 配置与索引](https://huggingface.co/Qwen/Qwen3.5-122B-A10B-GPTQ-Int4/tree/30cd92cba9707a9aba09d1e490ed4b66b78e9606)。

这不是已选定赛方模型：当前 [OER 规则](../oer-scope-and-rules.md)、[H800 既有设计](../vetting-h800-moe-plan-20261004.md) 都明确实际约 120B MoE 的名称、量化和推理引擎尚未确认。`application-vetting-h800.yml` 的 GPT-OSS／Qwen 注释只是候选观察，`responses.enabled=false` 也没有启用模型供应商。本次对已检索到的仓库文档未找到赛方强制 checkpoint 或明确允许替换模型的原始条文；需继续从赛方原件、平台模型目录／deployment metadata 查证，不把开发者候选写成赛方要求，也不要求用户回答本可查的技术事实。若指定 checkpoint 不得换，先验证指定模型，其他候选仅作离线对照。

当前执行顺序仍为正常完整解析质量 → 固定资料向量库质量 → 项目接入与业务 → K8s／H800。本研究不把部署准备提到当前解析质量之前；[handoff 顶部](../HANDOFF-ocr-embedding-rerank-20261008.md) 的未过质量门保持。

## 1. 少量候选及其实际权重身份

下表的 `total_size` 从官方固定 revision 的 `model.safetensors.index.json` 实际读取，是 tensor payload 的索引声明，不是模型已加载后的 VRAM。以 GiB=2^30 bytes 换算；下载文件还有 header，运行时可能忽略 vision／MTP，也可能为重排、解包或 kernel 产生其他张量，必须另测。总参数／激活参数采用官方模型卡，不使用 HF 包装 dtype 元素统计反推 MoE 参数。

| 候选配方 | 官方模型卡身份 | 索引 payload bytes／GiB | 部署含义 |
| --- | --- | ---: | --- |
| GPT-OSS-120B 原生 MXFP4 | 117B 总／5.1B 激活，36 层，文本输入；原生 131,072 | 65,248,815,744／60.768 | 官方单 H100 可运行案例最直接；H800 上的内核、128K 峰值和业务质量未测 |
| Qwen3.5-122B-A10B BF16 | 122B 总／10B 激活，48 层，vision＋text；原生 262,144 | 250,173,007,840／232.992 | 全部权重纯 GPU 常驻不符合单卡容量 |
| 同模型官方 FP8 | 8bit 浮点量化，仍有 BF16 组分 | 127,152,313,312／118.420 | 全部 checkpoint 仍大于单卡；FP8 不是 4bit |
| 同模型官方 GPTQ-Int4 | 4bit GPTQ，group size 128，部分模块保留高精度 | 78,844,078,048／73.429 | 单卡边界候选，不能把普通 Q4 算术当已装下长上下文 |
| Qwen3.5-27B BF16 | dense 27B，64 层，vision＋text；原生 262,144 | 55,562,872,800／51.747 | 以较少量化为质量对照；dense 每 token 的主要权重都参与计算 |
| Qwen3.5-35B-A3B BF16 | 35B 总／3B 激活，40 层，vision＋text；原生 262,144 | 71,903,655,008／66.965 | 可讨论单请求 text-only 容量；官方通用 BF16 配方用更大或多卡，不等于单 H800 已过 |
| 同模型官方 FP8 | block FP8，dynamic activation，block 128×128 | 37,454,799,072／34.882 | 为在线 GPU embedding／rerank 留出较多容量，亦降低阶段 H2D 权重字节数 |

模型卡与配置依据：[GPT-OSS 官方部署说明](https://developers.openai.com/cookbook/articles/gpt-oss/run-transformers)、[GPT-OSS config](https://huggingface.co/openai/gpt-oss-120b/blob/b5c939de8f754692c1647ca79fbf85e8c1e70f8a/config.json)、[Qwen122 卡](https://huggingface.co/Qwen/Qwen3.5-122B-A10B)、[Qwen27 卡](https://huggingface.co/Qwen/Qwen3.5-27B)、[Qwen35 卡](https://huggingface.co/Qwen/Qwen3.5-35B-A3B)、[Qwen35 FP8 config](https://huggingface.co/Qwen/Qwen3.5-35B-A3B-FP8/blob/9d1823d2dee688a6b25e77009dc727688c44936e/config.json)。索引复算入口与 revisions 见第 7 节。

### 量化并不是把所有模块统一乘 0.5／1 byte

GPT-OSS 的 MXFP4 主要用于 MoE 专家权重；其 config 明确排除 attention、router、embedding、lm_head。它的实际发布表示、block scale 和其他张量已经体现在官方索引中，不能写成“全部 117B 都是纯4bit”。其 MXFP4 配方与模型一起完成 post-training，并不意味着任意重新量化的 GGUF／AWQ 自动保有同样质量。[官方 GPT-OSS config](https://huggingface.co/openai/gpt-oss-120b/blob/b5c939de8f754692c1647ca79fbf85e8c1e70f8a/config.json)、[官方模型卡](https://huggingface.co/openai/gpt-oss-120b)。

Qwen122 GPTQ config 的动态排除包含 `attn`、shared expert、MTP、visual 等模块；索引与 API metadata 显示仍有约 9.122B BF16 组分。此 dtype 元素数是辅助包装统计，不拿它替代模型卡参数数；它说明名为 Int4 的 checkpoint 也有大量非 Int4 内容。**当前正式候选可以采用官方 GPTQ-Int4，不再只有第三方 Q4；现有 10-04 文档中“第三方 Q4”是当时研究状态。** [固定 GPTQ config](https://huggingface.co/Qwen/Qwen3.5-122B-A10B-GPTQ-Int4/blob/30cd92cba9707a9aba09d1e490ed4b66b78e9606/config.json)。

Qwen35 官方 FP8 config 采用 block weight quantization 和 dynamic activation；embedding、lm_head、部分 linear-attention 小投影／conv、router、visual、MTP 等保留例外。官方卡的“接近原模型指标”只是官方报告，不是本项目中文合约、数字、例外及来源引用无退化的成绩。[官方 FP8 卡与 config](https://huggingface.co/Qwen/Qwen3.5-35B-A3B-FP8/tree/9d1823d2dee688a6b25e77009dc727688c44936e)。

### 为什么当前小 MoE 对照采用 35B，而非只比较旧 30B

Qwen3-30B-A3B-Instruct-2507 官方是 30.5B 总／3.3B 激活、48 层普通 GQA、4 KV heads×128、原生 262,144，且专门是 non-thinking instruct。它可作较旧文本-only 对照，但 128K BF16 全层 KV 约 12 GiB，256K 约 24 GiB。Qwen3.5-35B-A3B 只有10个 full-attention层，长窗口 KV 斜率低得多，同时已经有官方 FP8 与 hybrid serving 路线，更能代表本项目单卡长文部署的资源取舍。这个判断来自架构和量化包，不证明 35B 的合约审查比旧30B更准确。[30B 官方卡](https://huggingface.co/Qwen/Qwen3-30B-A3B-Instruct-2507)、[30B config](https://huggingface.co/Qwen/Qwen3-30B-A3B-Instruct-2507/blob/0d7cf23991f47feeb3a57ecb4c9cee8ea4a17bfe/config.json)。

## 2. Qwen3.6 的轻量核查

**Qwen3.5 不是本研究声称的最新模型。** 已实际核验官方 `Qwen/Qwen3.6-35B-A3B` 与其 FP8 包存在；3.6 保持35B总／3B激活、40层（10 full＋30 linear）、2 KV heads×256、原生262,144，继续使用 `qwen3_5_moe` 模型架构。它增加 coding 及历史 thinking preservation 的训练／模板能力，不能据版本号或 coding 榜就宣告更适合法律合约。[3.6 官方卡](https://huggingface.co/Qwen/Qwen3.6-35B-A3B)、[3.6 config](https://huggingface.co/Qwen/Qwen3.6-35B-A3B/blob/995ad96eacd98c81ed38be0c5b274b04031597b0/config.json)。

3.6 BF16 的索引 payload 为71,903,645,408 bytes，约66.965 GiB。3.6 FP8 revision `95a723d08a9490559dae23d0cff1d9466213d989` 的 index `metadata` 是空对象，**total_size未知，不能把null换算成0**；本轮改读官方 HF `api/models/...?...blobs=true`，42个 safetensors 的文件长度合计37,463,662,160 bytes，约34.891 GiB。后者含文件header，口径不同于上表payload，亦非VRAM。正式冻结前应核该 revision 的实际文件清单、量化规则、模板、loader以及载入后的tensor清单。[3.6 FP8 文件页](https://huggingface.co/Qwen/Qwen3.6-35B-A3B-FP8/tree/95a723d08a9490559dae23d0cff1d9466213d989)、[3.6 FP8 config](https://huggingface.co/Qwen/Qwen3.6-35B-A3B-FP8/blob/95a723d08a9490559dae23d0cff1d9466213d989/config.json)。

推荐把3.5固定revision作为可复现容量与质量基线，3.6作为同架构替换对照；若业务留出、模板兼容、目标 kernel 与恢复试验有更好且稳定的结果，再采用3.6。3.6的 `preserve_thinking` 改变历史消息实际输入，必须另计tokens、来源资格和输出预算；不能默认开启后仍声称沿用相同上下文实验。[官方3.6 thinking说明](https://huggingface.co/Qwen/Qwen3.6-35B-A3B)。

## 3. 显存怎么算，哪些数字还没有测

LLM阶段总预算为：

`驻留权重 + KV／GDN线性状态 + prefill／decode工作区 + CUDA graphs／allocator／运行时副本 + 本阶段辅助模型峰值 + 设备安全余量`

OCR 已释放时不加入 OCR 常驻项；若在线 E/R 仍在卡上，它们按真实峰值单列。`allocated`、`reserved`、整卡NVML memory是不同观察口径，不能相加。设备产品标示80GB也不直接转换为可准入字节；先读取目标 `device.total_memory`／驱动free、其他进程和容器限制。

### 单序列 BF16 KV 的可复算理论值

普通 full attention KV 算术为 `2 × full_layers × token_count × kv_heads × head_dim × dtype_bytes`；2表示K和V，BF16每元素2 bytes。这里的token_count包含输入与已生成tokens，131,072=128K、262,144=256K，亦不是128,000／256,000。并发序列按各自实际长度求和；prefix sharing、block alignment、GDN checkpoints需按引擎另算。

| 架构 | 已核配置 | 总序列128K KV理论值 | 总序列256K KV理论值 |
| --- | --- | ---: | ---: |
| GPT-OSS-120B | 18 full＋18 sliding，8 KV heads×64，sliding window128 | 4.504 GiB（滑窗正确保留）；全部层保留全长则9 GiB | 超过原生窗口，本研究不作为可运行档 |
| Qwen3.5-122B-A10B | 12 full＋36 linear，2 KV heads×256 | 3 GiB＋线性状态 | 6 GiB＋线性状态 |
| Qwen3.5-27B | 16 full＋48 linear，4 KV heads×256 | 8 GiB＋线性状态 | 16 GiB＋线性状态 |
| Qwen3.5／3.6-35B-A3B | 10 full＋30 linear，2 KV heads×256 | 2.5 GiB＋线性状态 | 5 GiB＋线性状态 |
| Qwen3-30B-A3B-Instruct-2507 | 48 full，4 KV heads×128 | 12 GiB | 24 GiB |

上述计算只来源于已读取官方 configs（第7节），不是官方性能保证或本项目实测。GPT-OSS滑窗公式为 `2×8×64×2×[18×131072 +18×128]` bytes＝4.50439453125 GiB；全长上界是忽略滑窗减量的保守存储场景，不表示目标引擎默认如此。

Qwen3.5／3.6 hybrid config 有 `mamba_ssm_dtype=float32`，GDN／conv状态不能按BF16全文KV表继续估成0，也不能将全部linear层错误套用全长GQA KV。理论上循环state不随每个历史token储存普通全长KV，但prefix缓存／chunk checkpoint／并发池及MTP会有额外状态，实际引擎配置与profile须显示。[Qwen122 config](https://huggingface.co/Qwen/Qwen3.5-122B-A10B/blob/dc4d348443bc740c68e2d77492492c11606384d5/config.json)、[Qwen27 config](https://huggingface.co/Qwen/Qwen3.5-27B/blob/fc05daec18b0a78c049392ed2e771dde82bdf654/config.json)、[Qwen35 config](https://huggingface.co/Qwen/Qwen3.5-35B-A3B/blob/59d61f3ce65a6d9863b86d2e96597125219dc754/config.json)。

### 先看最小权重＋KV，再讨论工作区和辅助模型

| 配方 | 128K：payload＋full／滑窗KV | 256K：payload＋fullKV | 判读 |
| --- | ---: | ---: | --- |
| GPT-OSS-120B MXFP4 | 65.272 GiB；全层全长场景69.768 GiB | 不支持该原生档 | 尚有理论空间给工作区与余量，不能承诺OER共驻 |
| Qwen122官方GPTQ4 | 76.429 GiB＋线性状态 | 79.429 GiB＋线性状态 | 单80GB很紧，全部阶段互斥也不能省略LLM自身工作区 |
| Qwen27 BF16 | 59.747 GiB＋线性状态 | 67.747 GiB＋线性状态 | 128K较可行的高精度对照；256K仍须测prefill与输出 |
| Qwen35 FP8 | 37.382 GiB＋线性状态 | 39.882 GiB＋线性状态 | 对长窗口／在线CUDA E/R有更大的规划空间 |
| Qwen35 BF16 | 69.465 GiB＋线性状态 | 71.965 GiB＋线性状态 | 不能只因官方示例多卡排除，但默认batch/graphs可能撑破单卡 |

表中全部是理论初筛，payload不一定等于runtime weights。若沿用旧设计的6 GiB工作区＋4 GiB设备余量这两个**规划假设**，GPT-OSS滑窗128K约75.272 GiB；Qwen122 GPTQ4已约86.429 GiB，尚未加线性状态。由此可以说同一保守假设下后者不满足预算，不能说已证明任何内核／text-only／短窗口都不可运行。`--language-model-only` 可跳过vision encoder和multimodal profiling，但必须观测实际节省；关闭MTP也只能减去loader实际不载入的部分，不在完整索引上凭空扣bytes。[Qwen官方text-only说明](https://huggingface.co/Qwen/Qwen3.5-122B-A10B-GPTQ-Int4)、[vLLM Qwen hybrid指南](https://docs.vllm.ai/projects/recipes/en/latest/Qwen/Qwen3.5.html)。

## 4. H800 内核、引擎与上下文策略

OpenAI Docs 提供原生MXFP4在单H100运行的官方案例；本轮打开的Transformers与vLLM Cookbook页面均带 **archived／可能过时** 提示，因此只用它们支持模型表示、Harmony与接口机制，不直接复制其中旧nightly包命令作2026冻结配方。最终镜像须锁版本／digest、CUDA／PyTorch／Triton／FlashInfer等组合，并在目标H800记录实际compute capability与kernel选择。[OpenAI Transformers说明](https://developers.openai.com/cookbook/articles/gpt-oss/run-transformers)、[OpenAI vLLM说明](https://developers.openai.com/cookbook/articles/gpt-oss/run-vllm)。

vLLM自己的GPT-OSS配方区分Hopper与Blackwell；支持MXFP4运行不等于H800有Blackwell原生FP4 tensor-core路径。不能把Blackwell／DGX Spark或AMD吞吐抄成H800成绩。vLLM硬件量化表支持Hopper的GPTQ／Marlin／FP8，但“支持量化格式”仍不足以证明某个hybrid MoE checkpoint、attention、GDN状态、JSON grammar及sleep／wake的组合全部通过。[vLLM GPT-OSS当前配方](https://github.com/vllm-project/recipes/blob/main/OpenAI/GPT-OSS.md)、[量化硬件表](https://docs.vllm.ai/en/stable/features/quantization/index.html)。

本轮查到vLLM维护者的模型配方列出Qwen122 Int4可用单80GB、35B FP8可用单H100；这是**官方引擎示例**，不是本项目H800最大128K／256K输入、长输出、embedding／rerank及OOM恢复已经通过。Qwen自己122B GPTQ模型卡的示例仍使用多卡TP，模型卡还存在文字“8 GPUs”而命令TP4的差异，因此不据示例GPU数反推精确最低硬件。[122B vLLM配方](https://recipes.vllm.ai/Qwen/Qwen3.5-122B-A10B)、[35B vLLM配方](https://recipes.vllm.ai/Qwen/Qwen3.5-35B-A3B)、[122B GPTQ官方卡](https://huggingface.co/Qwen/Qwen3.5-122B-A10B-GPTQ-Int4)。

上下文要区分四个量：模型原生支持、引擎最大总序列、当前请求真实token数、已验证资源profile。项目现有131,072总／16,384输出／16,384安全余量的完整输入门限98,304只是保守实验政策。安全余量不是显存；MiniMax的token估计也不是目标tokenizer精确计数。用目标tokenizer＋模板对完整instructions／materials／schema／历史消息计数，Qwen原生256K应建立独立档位，不能继承GPT-OSS128K为硬件上限。

首轮按1序列、原生128K档、关闭未经证明的speculative/MTP，保留完整输入。chunked prefill或降低已验证的`max-num-batched-tokens`可降低单次prefill峰值，不能据此减少证据或偷改`max-model-len`的业务预算。待容量与业务通过后再加256K（仅模型原生支持者）、较短实际窗口、并发及prefix cache。Qwen的1,010,000 YaRN扩展是模型卡支持路线，远非本项目单H800已经通过，也不应当作首轮默认。[Qwen官方长上下文说明](https://huggingface.co/Qwen/Qwen3.5-35B-A3B)、[vLLM总序列与batch说明](https://github.com/vllm-project/recipes/blob/main/OpenAI/GPT-OSS.md)。

BF16 KV先作为质量／容量基线；FP8 KV可作为第二阶段节省候选，需单独锁dtype与scales、同源数字／条款／长上下文退化评价，不能认为权重FP8自动令KVFP8。关闭prefix caching作冷／暖公平对照，再单独测实际复用率；sleep释放KV之后需重新prefill，不把对话保存在数据库等同于GPU prefix仍存。

## 5. 业务选择不能由参数量或榜单代替

ConSense涉及繁体／简体中文界面、英文及双语合约、数字／否定／例外、来源修订与适用版次、完整表组及引用。官方多语言、C-Eval、IFEval、LongBench分数只帮助选候选；目前没有官方资料证明某候选在香港房署此套合约上的漏报率、条件完整性或引用正确率达标。Qwen3.5的多语言覆盖与官方instruction／long-context评价有相关性，不能等同法律领域保证；GPT-OSS较强通用推理也不能修复未进入输入的条款或被OCR错挂的条件。

模型比较必须用同一冻结的canonical证据与业务问法，先plain基线，再既有最小结构流程；保持固定与项目文件资格、参考版次和2d／2e只供评分边界。先冻结盲输出，后向独立evaluator开放gold。指标至少分开报告：必要证据覆盖、条件／例外忠实、数值／单位／否定、发现漏报与误报、引用原文／原图／完整表组、unknown处理、JSON schema合法、有效终止与完整输出、端到端成本。开发已暴露题标明身份，另保留新文件／新题留出。

**JSON能解析不等于业务正确。** vLLM当前支持xgrammar／guidance、JSON schema与reasoning输出，但版本、模型parser及实际endpoint必须组合验证。GPT-OSS输入输出使用Harmony，analysis／final／tool边界必须由兼容模板／parser处理；不能把CoT粘到JSON正文或按普通text completion忽略结束tokens。项目还须验证Chat Completions／Responses的字段、max-output budget、reasoning token计费／占位、拒答／空结果／length finish与streaming增量行为。[OpenAI Harmony](https://developers.openai.com/cookbook/articles/openai-harmony)、[vLLM structured outputs](https://docs.vllm.ai/en/stable/features/structured_outputs/)、[GPT-OSS API兼容表](https://github.com/vllm-project/recipes/blob/main/OpenAI/GPT-OSS.md)。

Qwen3.5／3.6默认thinking，官方关闭方式是`chat_template_kwargs.enable_thinking=false`，不支持把`/nothink`软标志沿用为官方3.5／3.6开关。可先在相同证据上比较thinking与non-thinking，让条件推理与有效JSON分别评分；不因non-thinking更快就直接替换，也不因high thinking就认定准确。长reasoning和final共享实际生成／总序列预算，16K输出应按真实分布校准。[Qwen3.5 thinking说明](https://huggingface.co/Qwen/Qwen3.5-35B-A3B)、[Qwen3.6 thinking说明](https://huggingface.co/Qwen/Qwen3.6-35B-A3B)。

这些候选官方发布均标Apache-2.0；GPT-OSS还应核其模型使用政策，所有包／引擎与第三方量化的license／NOTICE都随固定release保留。许可允许使用不等于比赛允许换checkpoint、联网或外部推理。比赛不预设外部API算力；本机MiniMax成绩只作为开发对照。[GPT-OSS官方卡许可](https://huggingface.co/openai/gpt-oss-120b)、[Qwen122 GPTQ许可](https://huggingface.co/Qwen/Qwen3.5-122B-A10B-GPTQ-Int4)、[Qwen3.6许可](https://huggingface.co/Qwen/Qwen3.6-35B-A3B)。

## 6. 权重策略与验证次序

阶段之间卸载／恢复大模型，与**每个forward把部分权重从CPU搬到GPU**是两种策略。前者在一批OCR／embedding／rerank完成后恢复一次LLM，成本主要是一轮加载＋warmup＋prefill；后者把host/device搬运反复放进推理路径，可能降低驻留量但放大每token延迟。OCR／LLM互斥已明确时，能整卡容纳的大模型优先用阶段切换，没有必要为了OCR引入每token CPU offload。RAM兼容量化副本可减少磁盘冷读，但250GB RAM仍须包含loader临时副本、pinned buffers、page cache与其他服务；不能预设所有量化引擎支持`.to('cpu')`。

| 验证阶段 | 具体比较 | 应保留证据 |
| --- | --- | --- |
| 0 身份与比赛适用 | 查赛方指定模型、可替换规则、现有checkpoint／量化／引擎／管理权限；无模型生成 | 原始条文定位／deployment metadata；unknown保持 |
| 1 配方与接口 | 固定revision、文件SHA／tensor index、tokenizer／chat template、Linux image digest、kernel；完整JSON／Harmony／thinking接口验证 | 模型与引擎manifest、实际生成raw、终止／拒答／schema行为 |
| 2 LLM独占容量 | 单序列128K；逐档到真实最大输入＋长输出；Qwen另测256K；BF16 KV先行 | GPU total/free、weights载入／prefill／decode峰值、allocated／reserved／NVML、host峰值；OOM终态 |
| 3 同源业务质量 | GPT-OSS120 MXFP4、Qwen27 BF16、35B FP8；35B3.6替换对照；122GPTQ仅容量可行后加入 | 冻结输出、独立源gold、逐题分母／失败／引用与条件、质量差异 |
| 4 阶段恢复 | 磁盘冷加载、RAM缓存warm恢复、kernel ready、首次prefill／TTFT；整批OCR后恢复 | 实际VRAM释放、host缓存表示、加载字节数、分段与全链时长；失败恢复 |
| 5 在线E/R | 先LLM独占通过，再验证CUDA query embedding／rerank共驻；不足时阶段排队 | 最大真实query／候选／batch峰值，与LLM最大请求组合；切换次数及用户等待 |
| 6 性能优化 | 质量保持后才试FP8 KV、MTP／speculation、prefix／并发、引擎对照 | 同源质量回归、资源差异、TTFT／decode／端到端中位与尾延迟 |

“warm ready”必须包含loader／kernel／健康检查真实终态；H2D纯带宽下界不能当用户等待时间。以权重实际bytes除以host→device有效带宽，而非H800 HBM内部带宽。运行中的CPU offload、阶段RAM缓存和OS page cache分别标识；缓存已热的单次启动不得宣称冷加载已过。

不同LLM可改变weights／KV预算，却不能让不完整OCR、来源错配、完整条件包拆散或引用失真变正确。OER原件质量与模型审查质量仍分别验收。当前本项目上述H800模型调用、容量、恢复、性能及业务试验均未执行，本轮没有用理论表追认其中任何门通过。

## 7. 来源与复算身份

以下均于2026-10-12访问。HF API只读取公开metadata／config／index，没有下载safetensors本体；固定revision链接优先于可变main。

| 包 | 固定revision | 官方config／index入口 |
| --- | --- | --- |
| openai/gpt-oss-120b | b5c939de8f754692c1647ca79fbf85e8c1e70f8a | [config](https://huggingface.co/openai/gpt-oss-120b/blob/b5c939de8f754692c1647ca79fbf85e8c1e70f8a/config.json)／[index](https://huggingface.co/openai/gpt-oss-120b/blob/b5c939de8f754692c1647ca79fbf85e8c1e70f8a/model.safetensors.index.json) |
| Qwen3.5-122B-A10B BF16 | dc4d348443bc740c68e2d77492492c11606384d5 | [config](https://huggingface.co/Qwen/Qwen3.5-122B-A10B/blob/dc4d348443bc740c68e2d77492492c11606384d5/config.json)／[index](https://huggingface.co/Qwen/Qwen3.5-122B-A10B/blob/dc4d348443bc740c68e2d77492492c11606384d5/model.safetensors.index.json) |
| Qwen3.5-122B-A10B FP8 | a099dee70ccfcd8d5dda56aaa0b60cb8ecadabc9 | [index](https://huggingface.co/Qwen/Qwen3.5-122B-A10B-FP8/blob/a099dee70ccfcd8d5dda56aaa0b60cb8ecadabc9/model.safetensors.index.json) |
| Qwen3.5-122B-A10B GPTQ4 | 30cd92cba9707a9aba09d1e490ed4b66b78e9606 | [config](https://huggingface.co/Qwen/Qwen3.5-122B-A10B-GPTQ-Int4/blob/30cd92cba9707a9aba09d1e490ed4b66b78e9606/config.json)／[index](https://huggingface.co/Qwen/Qwen3.5-122B-A10B-GPTQ-Int4/blob/30cd92cba9707a9aba09d1e490ed4b66b78e9606/model.safetensors.index.json) |
| Qwen3.5-27B BF16 | fc05daec18b0a78c049392ed2e771dde82bdf654 | [config](https://huggingface.co/Qwen/Qwen3.5-27B/blob/fc05daec18b0a78c049392ed2e771dde82bdf654/config.json)／[index](https://huggingface.co/Qwen/Qwen3.5-27B/blob/fc05daec18b0a78c049392ed2e771dde82bdf654/model.safetensors.index.json) |
| Qwen3.5-35B-A3B BF16 | 59d61f3ce65a6d9863b86d2e96597125219dc754 | [config](https://huggingface.co/Qwen/Qwen3.5-35B-A3B/blob/59d61f3ce65a6d9863b86d2e96597125219dc754/config.json)／[index](https://huggingface.co/Qwen/Qwen3.5-35B-A3B/blob/59d61f3ce65a6d9863b86d2e96597125219dc754/model.safetensors.index.json) |
| Qwen3.5-35B-A3B FP8 | 9d1823d2dee688a6b25e77009dc727688c44936e | [config](https://huggingface.co/Qwen/Qwen3.5-35B-A3B-FP8/blob/9d1823d2dee688a6b25e77009dc727688c44936e/config.json)／[index](https://huggingface.co/Qwen/Qwen3.5-35B-A3B-FP8/blob/9d1823d2dee688a6b25e77009dc727688c44936e/model.safetensors.index.json) |
| Qwen3.6-35B-A3B BF16 | 995ad96eacd98c81ed38be0c5b274b04031597b0 | [config](https://huggingface.co/Qwen/Qwen3.6-35B-A3B/blob/995ad96eacd98c81ed38be0c5b274b04031597b0/config.json)／[index](https://huggingface.co/Qwen/Qwen3.6-35B-A3B/blob/995ad96eacd98c81ed38be0c5b274b04031597b0/model.safetensors.index.json) |
| Qwen3.6-35B-A3B FP8 | 95a723d08a9490559dae23d0cff1d9466213d989 | [config](https://huggingface.co/Qwen/Qwen3.6-35B-A3B-FP8/blob/95a723d08a9490559dae23d0cff1d9466213d989/config.json)／[index metadata空](https://huggingface.co/Qwen/Qwen3.6-35B-A3B-FP8/blob/95a723d08a9490559dae23d0cff1d9466213d989/model.safetensors.index.json)／[公开file-size metadata](https://huggingface.co/api/models/Qwen/Qwen3.6-35B-A3B-FP8?blobs=true) |

可复算：索引`metadata.total_size / 1073741824`；KV按第3节公式；3.6FP8对42个`.safetensors`的公开文件长度求和，未知索引payload继续未知。模型revision不是集群已安装版本；冻结与部署后仍须核实际manifest。
