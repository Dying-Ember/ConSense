# DeepSeek V4 Flash 在单 H800 上的可行性调查

核查日期：2026-10-16（北京时间）。证据性质：官方模型元数据、配置、源码和部署文档的只读调查；没有下载权重、加载模型、调用推理接口或改变 ConSense 活动配置。本文件只新增调查记录，现有脏工作区及并行活动保持。

## 结论及模型身份

有两条具体可试路线：**约 2bit EXL3 全 GPU**，以及**保留原生专家精度的 CPU／GPU 混合执行**。前者优先解决 prefill／生成速度，但必须重验量化业务质量与 H800 峰值；后者有更接近目标 CPU 配额的运行证据，但长输入可能非常慢。**1×H800 80 GB 不能将官方 V4 Flash 原发完整权重全部常驻显存**，不代表所有重新量化表示都不能装下。此处没有目标 H800 实测，不把“文件放得下”认作 ConSense 已可用。

用户仅称“DeepSeek v4 flash”，不能静默将早期 preview 当成正式版。后续验证优先冻结正式 **DeepSeek-V4-Flash-0731** 的具体文件与推理实现；下文同时单列 preview，便于审核已有社区转换包是否仍停留在旧模型。也不能因为官方 API 接受旧名字，就把当前云端响应当作所要求的 V4 Flash。

| 官方仓库／版本 | 本次固定 revision | 身份及结论 |
| --- | --- | --- |
| `deepseek-ai/DeepSeek-V4-Flash` | `60d8d70770c6776ff598c94bb586a859a38244f1` | 早期 preview；官方说明为 284B 总参数、13B 激活参数，支持约百万 token；43 主层，专家使用 FP4、其他多数参数 FP8。 |
| `deepseek-ai/DeepSeek-V4-Flash-0731` | `7872f01b1d1fe23eabc4c98b48bffcef5a386062` | 官方正式版本，取代 preview；配置保留 43 主层，另带 DSpark 推测解码模块。不能复用 preview 文件 hash 或文件大小充当它的验收。 |
| `deepseek-ai/DeepSeek-V4.1-Flash` | `5254448103b6254fc646dacbca1cbbf20e9216c1` | 另一架构／型号：官方称 552B backbone，另有 Engram 条件记忆等；不可继承 V4 Flash 的内存预算。 |

来源：[preview 模型卡](https://huggingface.co/deepseek-ai/DeepSeek-V4-Flash/blob/60d8d70770c6776ff598c94bb586a859a38244f1/README.md)、[preview 配置](https://huggingface.co/deepseek-ai/DeepSeek-V4-Flash/blob/60d8d70770c6776ff598c94bb586a859a38244f1/config.json)、[0731 正式模型卡](https://huggingface.co/deepseek-ai/DeepSeek-V4-Flash-0731/blob/7872f01b1d1fe23eabc4c98b48bffcef5a386062/README.md)、[0731 配置](https://huggingface.co/deepseek-ai/DeepSeek-V4-Flash-0731/blob/7872f01b1d1fe23eabc4c98b48bffcef5a386062/config.json)、[V4.1 模型卡](https://huggingface.co/deepseek-ai/DeepSeek-V4.1-Flash/blob/5254448103b6254fc646dacbca1cbbf20e9216c1/README.md)。revision 分别取自本次读取的 [preview API](https://huggingface.co/api/models/deepseek-ai/DeepSeek-V4-Flash)、[0731 API](https://huggingface.co/api/models/deepseek-ai/DeepSeek-V4-Flash-0731)、[V4.1 API](https://huggingface.co/api/models/deepseek-ai/DeepSeek-V4.1-Flash)。

**官方 API 的版本漂移是已确认事实。** DeepSeek 2026-09-10 公告称 V4-Flash 已退役，兼容名字 `deepseek-v4-flash` 暂时路由到 V4.1-Flash；新名字为 `deepseek-flash`。因此云 API 返回正常，只能证明当前被路由模型的接口状态，不能证明 V4 Flash 本地权重或 H800 部署通过。来源：[官方 2026-09-10 公告](https://api-docs.deepseek.com/news/news260910/)。

## 权重大小：逐文件可重算

这里严格采用 `GB = 10^9 bytes`、`GiB = 2^30 bytes`。GPU／平台所标“80 GB／250 GB”是规格标签；实际可分配字节数仍需目标机器读回，不能把它们无条件视为 80 GiB／250 GiB。

本次只读取 Hugging Face 固定 revision 的 tree 元数据，筛选文件名以 `.safetensors` 结尾，逐个累加 `size`；没有读取任何权重正文。

| 版本 | safetensors 文件数 | 文件字节总和 | 十进制 GB | GiB | 数据来源 |
| --- | ---: | ---: | ---: | ---: | --- |
| V4 Flash preview | 46 | 159,617,149,040 | 159.617149040 | 148.655054 | [固定 tree API](https://huggingface.co/api/models/deepseek-ai/DeepSeek-V4-Flash/tree/60d8d70770c6776ff598c94bb586a859a38244f1?recursive=true&limit=1000) |
| V4 Flash 0731 | 48 | 166,886,535,336 | 166.886535336 | 155.425198 | [固定 tree API](https://huggingface.co/api/models/deepseek-ai/DeepSeek-V4-Flash-0731/tree/7872f01b1d1fe23eabc4c98b48bffcef5a386062?recursive=true&limit=1000) |
| V4.1 Flash | 48 | 510,296,708,312 | 510.296708312 | 475.250844 | [固定 tree API](https://huggingface.co/api/models/deepseek-ai/DeepSeek-V4.1-Flash/tree/5254448103b6254fc646dacbca1cbbf20e9216c1?recursive=true&limit=1000) |

交叉核验：preview 的 `model.safetensors.index.json` 给出 `metadata.total_size = 159,609,485,896 bytes = 148.647917 GiB`，它是张量数据口径，比含文件头的 46 个文件总和少 7,663,144 bytes。不要把 API 的仓库 `usedStorage`、文件总和、张量字节数混作一个统计值。来源：[固定权重索引](https://huggingface.co/deepseek-ai/DeepSeek-V4-Flash/raw/60d8d70770c6776ff598c94bb586a859a38244f1/model.safetensors.index.json)。

由此可直接排除：preview／0731 官方原发包的全显存方案。V4.1 原发包更超过单 H800 与平台标称 250 GB RAM 的合计容量，不能承诺同样的纯内存卸载方案。若把 V4.1 的特定稀疏参数放 NVMe 或另行重新量化，是另一组未验证的运行与性能问题。

## FP4 的含义与单卡路线

V4 Flash 已经不是 284B 全部按 BF16 存储。官方 preview 明确采用专家 FP4＋其他多数 FP8；直接说“再做 4bit，284B÷2 = 142 GB”既低估其他张量／scale，也忽略原发专家已经是 4bit。13B activated 描述每 token 参与计算的参数量，不是需要保留的全部专家权重。

只作数量级检查，284B 主干若所有数值都按纯 2bit 裸存储，为 `284e9×2/8 = 71e9 bytes = 66.123903 GiB`；纯 4bit 为 `142e9 bytes = 132.247806 GiB`。这是没有 scale、混合精度、词嵌入／输出头、DSpark、缓存和工作区的数学下限，**不能用来宣称某个实际 2bit 文件能在 H800 留足 ConSense 空间**。低 bit 重量化还增加来源保真与结构输出质量的验收门。

优先调查的完整路线是：保留正式 0731 的模型身份和精度，用专门支持该架构的 CPU 专家执行／GPU 主干或专家缓存，将全部或部分专家驻留系统 RAM；实测预填充、长生成和 OER 之间的切换后再定资源占额。把所有层经普通 device map 分给 CPU，仅能算候选通用卸载，不能推定具备高效专家调度、正确支持该模型或满足时延。

官方 reference 的 [inference README](https://huggingface.co/deepseek-ai/DeepSeek-V4-Flash/blob/60d8d70770c6776ff598c94bb586a859a38244f1/inference/README.md) 默认 `MP=4`，提供权重转换及多进程 GPU 推理，未给出单 H800 的 CPU 专家卸载运行配置或实测。该“4”是示例启动参数，不足以证明任何四卡型号都具备相同性能。

**H800 没有原生 FP4 矩阵乘法，不代表 FP4 存储权重一定不能使用。** 固定官方 [kernel.py](https://huggingface.co/deepseek-ai/DeepSeek-V4-Flash/blob/60d8d70770c6776ff598c94bb586a859a38244f1/inference/kernel.py#L441) 的 `fp4_gemm_kernel` 按小块把 FP4 解为 FP8 再作 FP8×FP8。NVIDIA 文档也区分 Hopper 的 FP8 与需 Blackwell 的原生 NVFP4 路径。因此必须核实际引擎的解码／计算内核，不能将原发 FP4、MXFP4、NVFP4 或任意 GGUF 名字当成可互换。来源：[NVIDIA Hopper](https://www.nvidia.com/en-us/data-center/technologies/hopper-architecture/)、[NVIDIA GEMM profiling 的架构约束](https://docs.nvidia.com/deeplearning/transformer-engine/examples/gemm_profiling/gemm_profiling.html)。

## 长上下文缓存：源码推导，不是显存峰值

V4 Flash preview 配置 `max_position_embeddings = 1,048,576`、43 主层、window 128、head_dim 512；43 主层中 21 个压缩比 4 层、20 个压缩比 128 层、2 个纯窗口层。压缩比 4 的层另有 128 维 indexer 缓存。正式 0731 前 43 层对应配置相同，但新增推测模块另计。

固定 [model.py](https://huggingface.co/deepseek-ai/DeepSeek-V4-Flash/blob/60d8d70770c6776ff598c94bb586a859a38244f1/inference/model.py#L384) 的 Indexer 及 [Attention 缓存分配](https://huggingface.co/deepseek-ai/DeepSeek-V4-Flash/blob/60d8d70770c6776ff598c94bb586a859a38244f1/inference/model.py#L436)，结合 [generate.py](https://huggingface.co/deepseek-ai/DeepSeek-V4-Flash/blob/60d8d70770c6776ff598c94bb586a859a38244f1/inference/generate.py#L90) 设置默认 BF16，可以推导 **batch=1、仅 43 主层持久 KV＋indexer**：

```text
bytes(N) = 2 × [43 × 128 × 512
               + 21 × (N/4) × (512+128)
               + 20 × (N/128) × 512]
         = 5,636,096 + 6,880 × N
```

| 配置 token 数 N | 主层 KV＋indexer bytes | GiB |
| --- | ---: | ---: |
| 32,768 | 231,079,936 | 0.215210 |
| 65,536 | 456,523,776 | 0.425171 |
| 131,072 | 907,411,456 | 0.845093 |
| 393,216 | 2,710,962,176 | 2.524780 |
| 1,048,576 | 7,219,838,976 | 6.723999 |

以上数字不是 vLLM／SGLang／llama.cpp 的实测，也没有包含 MTP／DSpark、压缩状态、RoPE 频率表、临时张量、预填充 workspace、CUDA 图、分配器及碎片。reference 的 indexer 还会建立随预填充长度快速增长的中间分数，不能据这个持久缓存表直接把 reference 扩为百万 token 生产服务。专业引擎的 FP8／FP4 缓存亦须按其实现另算和实测。

官方 preview 建议 Think Max 的 context 至少 384K；0731 建议 high／max 的 maximum output length 为 384K。这两条是不同版本的建议，不是本次硬件能力证明，也不能混成模型必须先分配 384K 输出才可启动。ConSense 初始验收应由实际输入及输出预算确定；缩小输出预算若导致 JSON／业务结果截断，必须保留失败。

## 仍须闭合的 ConSense 与目标硬件门

1. 固定正式 0731 或明确选定 preview 的实际文件 SHA、转换来源和引擎版本；不能只冻结一个可漂移的 API model alias。
2. 在目标平台读回 GPU 实际容量、CPU 型号／指令集、cgroup RAM／CPU 限额、PCIe 和本地存储；RAM 卸载的带宽常是瓶颈，不能从 250 GB 容量推 token/s。
3. 保留 Java、数据库、向量库、解析及现有模型的资源；单卡执行槽按真实进程树／显存释放交接。模型加载时的文件映射、反量化和 CPU／GPU 复制也计入峰值。
4. 验证 OpenAI-compatible chat／stream 的正确封装、think 与正式 JSON 分离、输出完整性、真实错误／length 截断；随后分别验证 Drafting／Vetting／Advisory。
5. 至少记录冷启动、长输入 TTFT、生成 tokens/s、完成任务 wall time、CPU／GPU 峰值和连续执行的资源释放。短问候或一次 /health 成功不能关闭这些门。

未执行：模型下载／转换／加载、目标 H800 推理、ConSense 业务请求及部署。当前能确认的是官方模型身份、精度／文件容量边界和可复算源码推导；不能保证卸载后的速度、生成质量或整套 ConSense 已可在该 H800 验收。

## 实际目标资源与推荐验证顺序

仓库已确认目标为 **1×H800 80GB、27 vCPU、250GB RAM、650GB 高性能存储＋1250GB 通用存储、Kubernetes**。这是资源标签，尚未读回目标 GPU 实际字节、CPU 型号／物理核、AMX／AVX、NUMA／内存通道、PCIe、cgroup 或盘吞吐。来源：[架构报告](../consense-llm-oer-architecture-analysis-report-20261012.md)、[最新共驻研究](oer-h800-memory-scheduling-20261012.md)。27 vCPU 不能照抄 60 workers 或两个 60 线程池。

最新 10 月 15 日方案曾为 Qwen 规划 52GiB、OER 串行辅助槽 16GiB、安全余量 6GiB；这是待测方案，不能给 DeepSeek 继承空余。以下 DeepSeek 首轮按 LLM 独占 GPU、OER／E/R 在完成可持久阶段后交接资源判断。它是研究建议，没有替换已确认运行规则或当前服务。

| 优先级 | 精确路线 | 适用目的及尚缺证据 |
| --- | --- | --- |
| 速度候选 | 正式 0731、EXL3 2.04bpw、ExLlamaV3／TabbyAPI、无 DSpark | 有全 GPU 容量候选和长输入外部实测；Hopper 支持有源码依据。约 2bit 的 ConSense 业务质量与 H800 完整峰值必须验证。 |
| 精度基线 | 正式 0731、原生专家 MXFP4 的 GGUF Q8_K_XL、llama.cpp CPU MoE | 较少改模型表示，已有相近 vCPU 的混合推理证据；CPU 专家 prefill 慢，不能仅凭 TG 合格采用。 |
| 混合执行优化 | 正式 0731、KT-Kernel＋其 SGLang 集成、CPU 专家＋layerwise GPU prefill | 可把大 chunk 的专家计算搬回 GPU，值得与前项对照；H800 兼容依赖、staging 峰值、CPU 配额与长请求实测尚缺。 |

普通 vLLM CPU 权重 UVA／prefetch 是 GPU 访问或搬运主存权重，并不等于 CPU 专家计算；sleep 只解决闲置阶段让卡，不让超大模型在醒来后自动装下。未找到它们在本资源＋该模型上的完整长窗组合验证，不写成现成启动承诺。

### EXL3 全 GPU 候选的精确边界

模型作者 [0731 EXL3 2.04bpw，固定 revision](https://huggingface.co/turboderp/DeepSeek-V4-Flash-0731-exl3/tree/b5526babe907141a0750f265a5a2a5cf414f5a64) 的 9 个 target safetensors 合计 **74,487,254,345 bytes＝69.371661GiB**。另有可选 `mtp.safetensors` **7,593,735,242 bytes＝7.072217GiB**，首轮排除，不把开启后的峰值或性能偷渡到基线。

[ExLlamaV3 v1.4.1 固定构建配置](https://github.com/turboderp-org/exllamav3/blob/4f8ad0121f483ba66a5336244a4c3b6d7210385e/.github/workflows/build.yml#L51) 包含 CUDA architecture 9.0；项目存在 [DeepSeekV4 架构](https://github.com/turboderp-org/exllamav3/blob/4f8ad0121f483ba66a5336244a4c3b6d7210385e/exllamav3/architecture/deepseek_v4.py)与[sparse-attention 实现](https://github.com/turboderp-org/exllamav3/blob/4f8ad0121f483ba66a5336244a4c3b6d7210385e/exllamav3/modules/attention_fn/dsa_triton.py)。这构成 Hopper 候选支持依据，仍非 H800 上该 checkpoint 的运行验收。

第一人称作者在 **RTX PRO 6000 Blackwell 96GB**、ExLlamaV3 1.4.1、TabbyAPI `3d2848d03184344664b9a8ed7685033e87744742`、256K FP16 cache、无 CPU MoE／MTP 下报告：250K 请求峰值 **74,604MiB＝72.855469GiB**。其短生成约95.33 token/s，250K 输入 PP约2265.18、之后 TG约79.25；这是该作者的外部测量，并非 H800 预测。[固定实验与日志入口](https://github.com/gisenberg/local-model-eval/blob/0ac6602be3477faaf82194a4b7599dc853d64a13/results/DEEPSEEK_V4_FLASH_RTXPRO6000.md)、[固定无MTP配置](https://github.com/gisenberg/local-model-eval/blob/0ac6602be3477faaf82194a4b7599dc853d64a13/experiments/deepseek_v4_flash_0731_exl3_2.04bpw/tabby_config.yml)。原250K JSON的usage为null，本文没有冒称已独立重跑复算该速度。

容量反算：若设备真提供80十进制GB，扣上述外部峰值只剩 **1.650337GiB**；若提供80GiB，剩 **7.144531GiB**。二者都不是已读回 H800 的 free memory。Hopper workspace、驱动、分配器和外部 owner 可能不同；只有在实际整卡峰值＋明确安全余量准入后才能说装下。该方案不能再默认共驻16GiB完整OER槽。

约2bit重新量化的质量不能从一次 needle、短编码题或 SWE-bench 结果继承。该外部作者开启MTP后虽变快，却观察到任务分数变化，因此本项目先冻结无推测解码；启用DSpark须独立验证采样／接受率、业务正确性、内存与速度。

### 近原精度 GGUF 与 KT 路线

[Unsloth 正式0731固定 revision](https://huggingface.co/unsloth/DeepSeek-V4-Flash-0731-GGUF/tree/fbbb5b93fb787c21338159b0af3318bb3f4d9768) 的 Q8_K_XL shards 合计 **161,869,615,520 bytes**，Q4_K_XL **155,095,241,120 bytes**。Q8这个名称不表示284B全展开为8bit：作者保留原生MXFP4专家，对其他部分保较高精度；Q4只再压少量非专家部分，两者仅差6.77GB。作者的bit-identical／短窗KLD观察不等于ConSense长窗业务通过。[表示和测试口径](https://unsloth.ai/docs/models/deepseek-v4.md)

[llama.cpp 固定源码及参数](https://github.com/ggml-org/llama.cpp/blob/620d2225cc436ab0b001e3200e590e3b286ef9ab/tools/server/README.md) 已支持 `deepseek4`、CPU MoE与OpenAI兼容服务；无需以早期私有fork作为前提。[KT官方教程](https://github.com/kvcache-ai/ktransformers/blob/7fbbc20d31265b266a16dd4a3929f2abf76681e6/doc/en/DeepSeek-V4-Flash.md) 明确单5090、≥200GB RAM、AVX2/FMA、专家混合执行，但16K示例和60workers不能充当本项目27vCPU／256K验收。

### 版本锁的实际风险

[FlashMLA官方当前README](https://github.com/deepseek-ai/FlashMLA/blob/main/README.md) 的2026-09-30增量删除Hopper与V4.0支持，要求旧模型／Hopper采用 `ba89a3466e9470ad08ab39738d4e7bb66989e1e7`。因此不能建议把所有依赖都升级到最新；必须冻结框架实际vendored kernel和整套相容依赖。该约束不表示所有引擎都直接使用外部FlashMLA。

llama.cpp历史CUDA大score buffer [#25468](https://github.com/ggml-org/llama.cpp/issues/25468) 与顺序请求KV释放 [#25566](https://github.com/ggml-org/llama.cpp/issues/25566) 已关闭且有修复；量化KV报告 [#26423](https://github.com/ggml-org/llama.cpp/issues/26423) 作者clean rebuild后撤回误报，不能当普遍现存缺陷。长窗缓存报告 [#25259](https://github.com/ggml-org/llama.cpp/issues/25259) 仍open；固定build仍需核fresh／prefix-hit／跨请求释放。首轮单slot、无prefix复用只是隔离变量，不是已验证的通用修复。

## 速度：prefill、生成、切换分别评价

以下速度均有具体来源；未找到恰好单H800＋27vCPU＋250GB、完整ConSense材料的公开验收。不同卡、CPU、量化、上下文和引擎的数字不能混成一次试验，也不能按GPU算力比例换算。

| 外部实验 | Prefill token/s | Generation token/s | 测量范围／限制 |
| --- | ---: | ---: | --- |
| 0731 Q8、A100 40GB、30vCPU EPYC7J13、216GB RAM、llama.cpp `876a432`、CPU专家37层 | 88.40（pp512） | 17.67（tg128）；32K depth约15.28 | 最接近CPU配额；只证明该机器短PP及到32K的decode。 |
| 同作者单H100 80GB、0731 Q8、CPU专家25层、`071327508` | 83.67（pp512） | 33.44（tg128） | CPU／RAM未完整公布；不能平移H800长PP。 |
| 同作者2×H100、全部GPU，同Q8 | 1445.94（pp512） | 56.97（tg128） | 仅供观察CPU卸载的代价，不能除2推单卡。 |
| 上节EXL3 2.04bpw、96GB Blackwell、单卡全部GPU | 2265.18（实际250K） | 短95.33；250K后79.25 | 不同表示／kernel／GPU，速度与业务质量须重验。 |
| KT单5090官方教程、16K配置 | 未提供可比PP | 20+ | 非27vCPU，不能据此承诺项目速度。 |

前三行：[原作者A100／H100记录](https://gist.github.com/bigsnarfdude/2b05be835f983fc46c643ea4d80ea634)；EXL3：[实验记录](https://github.com/gisenberg/local-model-eval/blob/main/results/DEEPSEEK_V4_FLASH_RTXPRO6000.md)；KT：[官方教程](https://github.com/kvcache-ai/ktransformers/blob/7fbbc20d31265b266a16dd4a3929f2abf76681e6/doc/en/DeepSeek-V4-Flash.md)。A100记录标题误称参数量，不采用其标题规格；模型大小依上文固定官方元数据。

KT另有[长窗教程](https://ktransformers.net/en/docs/inference/long-context-deployment)：4×5090、双路64-core AVX512、256GB DDR5、PCIe5的不同配方，600K prefill约20–25分钟，速率随深度由峰590降到320。它说明配置1M与实际跑完1M是两回事；不是单H800速度预测。教程中GPU prefill阈值须与实际chunk大小一起核验，不能只看总prompt长度。

**生成必须计入reasoning＋final全部tokens。** 首个reasoning token、首个可见final token、完整JSON完成分别计时；模型可能很早产生hidden reasoning，却让用户等很久。短TG、filled-context TG、完整任务wall和成功率分别报告。

### 133,996输入tokens的耗时情景

133,996来自项目最新共驻研究中的参考全图输入，**是Qwen tokenizer下既有计数，不是DeepSeek实际token数**；换V4模板后必须重计。以下仅拿同一数值演示成本，三组PP／TG都是明确假设，不是H800估计，也不表示每次任务必定耗尽输出上限。

`T_model_seconds = input_tokens / PP + (reasoning_tokens + final_tokens) / TG`

| 假设PP／TG（token/s） | 仅prefill | 实际生成8,192的总时间 | 实际生成32,768 | 实际生成65,536 |
| --- | ---: | ---: | ---: | ---: |
| 80／17 | 27.92分钟 | 35.95分钟 | 60.04分钟 | 92.17分钟 |
| 200／30 | 11.17分钟 | 15.72分钟 | 29.37分钟 | 47.58分钟 |
| 1000／50 | 2.23分钟 | 4.96分钟 | 13.16分钟 | 24.08分钟 |

这还没有排队、加载、取证、token计数、重查、校验或修复。约33 TG的CPU混合方案若PP只有84，面对新大输入仍可能等几十分钟；“GPU很强”或“生成33TG”不能掩盖prefill成本。反之所有权重留GPU的低bit候选有更好的速度动机，但质量未验不准采纳。

### 冷启动、热启动与阶段切换

可核外部启动观察：KT单5090教程约4–5分钟；其4卡长窗教程约80秒；A100作者mmap加载20秒但未区分冷／热；外部96GB Blackwell的另一0731 IQ2 GGUF配方明确冷53.8秒、热9.0秒。后两种都不是EXL3／H800的切换承诺。[IQ2冷／热记录](https://github.com/gisenberg/local-model-eval/blob/main/results/DEEPSEEK_V4_FLASH_RTXPRO6000.md#previous-deepseek-v4-flash-0731-ud-iq2_xxs-baseline)

161.87GB Q8权重若全部需读盘，假设有效盘吞吐1／3／6GB/s，仅字节读取的算术为约162／54／27秒；EXL3 target 74.49GB对应74.5／24.8／12.4秒。它们不是mmap ready的严格下限：mmap可以延迟缺页；要以首次完整请求完成计入未读页。还须计H2D、格式重排、kernel JIT／capture、首次workspace和GPU释放。RAM保留、OS page cache、CUDA sleep、退出重载是不同状态。

完整测量链：

```text
T_task = T_queue + T_parse/OCR/retrieval
       + Σ(drain + verified_release + load/read/H2D + engine_warmup)
       + Σ_request(encode/count + prefill + reasoning + final + HTTP)
       + validate/repair/persist
```

已有TTFT若包括load／prefill／reasoning，不再把同一时间重复相加。每次切换应记录：旧owner停止接新任务→当前事务完成→进程树和显存实际释放→新owner ready→首次真实工作完成。将“进程起来”与“首次完整请求无延迟缺页”分开。

现工作区完整OCR的fresh路径逐页Paddle→MinerU；两路都fresh时P页约2P次provider生命周期。并行工作的dirty代码新增成功任务复用后，准确次数是 `fresh_Paddle + fresh_MinerU`；不能无条件写每批只启两次，也不能假定活动服务已采用dirty代码。query E/R仍可能E→R→E反复切换；不要每个原子LLM调用重载约162GB。建议同阶段连续处理、LLM保一个生成slot，OER按可持久事务让卡，先测每种实际切换再决定缓存。[当前完整OCR](../../tools/vetting_eval/complete_ocr.py)、[所有者释放](../../tools/vetting_eval/native_provider_lifecycle.py)、[E/R服务](../../tools/vetting_eval/server.py)

## ConSense可运行的接线门

| 门 | 当前源码证据 | 最小必要工作 |
| --- | --- | --- |
| Chat与流 | [OpenAiLlmClient](../../src/main/java/com/consense/ai/OpenAiLlmClient.java) 已发 `{base}/v1/chat/completions`；SSE只消费content，要求正常stop和DONE | 复用已有客户端／local profile；base不要重复带/v1。实际确认reasoning与JSON分离、结束和取消。 |
| embedding独立 | [AiGateway](../../src/main/java/com/consense/ai/AiGateway.java) 的embed仍用部署级llmClient；OpenAI适配器对同base发/v1/embeddings | 不能只改base使旧检索入口失效。保BGE-M3／reranker的现有服务及索引身份，按实际路由作最小分离。 |
| 真实token观察 | [AdviceService](../../src/main/java/com/consense/service/advice/AdviceService.java) 要求完整绑定token observer和至少262144有效总窗；[既有counter](../../tools/vetting_eval/bound_native_token_budget.py) 绑定ninfer及enableThinking=false | 为冻结V4 tokenizer、chat template、thinking模式和runtime建立可复算recipe；不能用numCtx配置值或字符估计放行。 |
| 结构与来源 | schema经提示词发送，再由[本地校验器](../../src/main/java/com/consense/ai/StructuredJsonValidator.java)严格消费；tool／length／refusal拒绝 | 不必新增tool framework或native JSON Schema依赖；保裸JSON、类型、完整来源、引用与跨文件语义的真实失败。 |
| Drafting预算 | [DraftExtractionHarness](../../src/main/java/com/consense/service/drafting/DraftExtractionHarness.java) 调ai.complete普通chat；generic客户端只给structured发送max_tokens／thinking扩展 | structured-max-tokens不会自动约束Drafting。冻结server默认仍须验实际返回；必要时做最小请求预算及完整结束支持，研究本轮未修改。 |
| 模式和窗口 | generic客户端未发送numCtx，也未泛化支持V4 reasoning_effort；现有thinking.type仅adaptive／disabled | 不套用MiniMax参数；server模板、reasoning、总窗／输出上限须准确配置和回读。 |
| 弱健康检查 | /v1/models有data即可让available()为true | exact model＋真实短/长请求和完整业务证据才作为ready。 |

### 当前超时与速度的冲突

| 路径 | 现有时限 | 直接影响 |
| --- | --- | --- |
| local Chat | [application.yml](../../src/main/resources/application.yml) timeout1200000ms＝20分钟；[HttpSupport](../../src/main/java/com/consense/ai/HttpSupport.java) absolute callTimeout再加5秒 | prefill＋thinking＋final都算入；SSE持续有token不会取消该绝对上限。普通chat配置有1次transport retry，structured明确0。 |
| Advisory产品SSE | [AdviceStreamController](../../src/main/java/com/consense/web/AdviceStreamController.java) SseEmitter600000ms＝10分钟，到时取消 | 包含排队、RAG、计数、生成及修复；仅改provider超时不够。 |
| Advisory Responses | [默认配置类](../../src/main/java/com/consense/config/ConsenseProperties.java) generation5分钟；[transport](../../src/main/java/com/consense/service/advice/AdviceResponsesTransport.java) 最大允许10分钟 | 不继承Chat20分钟，需按具体被采用路线检查。 |
| Drafting前端 | [API](../../../ai_consense_web/src/api/index.ts) extract2小时、generate1小时 | 前端长时限不放宽后端每次模型调用。 |
| Vetting | 异步创建run后poll；[服务](../../src/main/java/com/consense/service/vetting/VettingService.java) 单worker／queue8 | 创建run30秒不是模型deadline；多证据包和允许的业务尝试须合计总任务wall。 |

纯decode下限：25TG生成32768需21.85分钟、65536需43.69分钟；50TG生成65536也需21.85分钟。以上均未加prefill，已经超过normal local Chat20分钟。25TG生成16384需10.92分钟，也超过Advisory产品SSE10分钟。因此“装下＋有token输出”不足保证ConSense可用。时限修改若必要，须同时处理取消、状态持久化与队列占用；不能只无限延长HTTP。

## 本轮本地契约检查及下一次真实验收

在忽略目录复制了隔离源码snapshot，未覆盖共享target／配置／源文件。六组既有测试 **154执行、154通过、0失败／错误／skip**：OpenAiStructuredVettingDecoding15、ProviderStreaming24、StructuredJsonValidator109、OpenAiAuthenticatedAvailability1、EmbeddingModelAvailability4、VettingH800BudgetProfile1。它们证明已有OpenAI／SSE／JSON／预算配置契约；没有真实DeepSeek或H800参与。

证据：[收据](../../../.local/deepseek-v4-flash-research-20261016/contract-receipt.json)、[最终日志](../../../.local/deepseek-v4-flash-research-20261016/contract-tests-04.log)、[容量和速度算术](../../../.local/deepseek-v4-flash-research-20261016/sizing-speed-calculations.json)。POM、OpenAiLlmClient、ProviderStreamReader、ConsenseProperties、AdviceService、application.yml六项snapshot与工作区SHA256核同。前三次环境失败原日志保留：Maven绝对路径中的&、Surefire fork路径，以及已知JDK AF_UNIX连接问题；最终仅对该测试JVM沿用仓库已存在的TCP fallback并禁fork，没有改系统网络／服务或产品代码。

下一次真实目标验证应先选EXL3速度候选与近原精度混合基线，冻结模型／量化／template／kernel／输出模式，每项先单发、无自动重试、无推测解码。只在target实际准入后做：

1. 读取真实GPU／CPU／RAM限额及盘拓扑，分别测cold、warm、首次完整请求和阶段释放，记录整卡及host峰值。
2. 32K、128K及最大准入实际输入，另用完整业务材料；若测试256K输入，必须使用更大的已核总窗，并满足输入＋reasoning＋final＋安全余量≤有效总窗口。需要512K或max模式时另测对应总窗，不把总窗全部当可用输入。
3. fresh与prefix命中分开；首reasoning、首final、完整JSON／stop／DONE分开；生成按实际8K／32K／64K及提前正常结束记录，不能把length截断当成功。
4. 执行OCR→取证／E-R→LLM→再OCR的完整切换，再分别跑Drafting跨文件变量、Vetting证据审查、Advisory可回读回答。量化／完整来源／语义／超时／旧正确逐项评分。
5. 根据真实延迟决定该候选只适合离线批处理，还是可用于交互；不在缺PP／长TG／切换证据时给出单一tokens/s承诺。前期冻结质量门与原件身份，不为速度裁top-k专家、砍来源或重试到绿。

当前决定是**优先验证EXL3是否在实际H800有足够余量且质量可接受；以原生专家精度混合作对照**。二者都未采用为生产模型，不能宣称ConSense已在目标环境运行或业务通过。

## 补充：上下文与生成长度的具体分配

2026-10-16 用户追问上下文和生成长度。本次重新GET正式0731固定config，`max_position_embeddings=1048576`；[官方0731说明](https://huggingface.co/deepseek-ai/DeepSeek-V4-Flash-0731/blob/7872f01b1d1fe23eabc4c98b48bffcef5a386062/README.md)对high/max建议maximum output length384K。前者是模型总窗声明，后者是建议的总生成上限，不是已测H800能力、硬性生成天花板或启动最低额度。

全部K按1024计。完整序列化输入包括system、schema、材料、历史及其他实际消息；thinking与final共用生成额度。准入关系是 `输入 + thinking + final + 安全余量 <= 已核有效总窗`。

以下是拟议验证档，不是已修改的运行配置；16K安全余量借用现有预算策略作规划值，最终须随真实计数器／引擎身份冻结。

| 拟议档 | 总窗 | thinking＋final总生成额度 | 安全余量 | 完整输入上限 |
| --- | ---: | ---: | ---: | ---: |
| 首轮完整输出目标 | 262144（256K） | 65536（64K） | 16384 | 180224（176K） |
| 扩展长文目标 | 524288（512K） | 131072（128K） | 16384 | 376832（368K） |
| 大预算边界研究 | 1048576（1M） | 393216（384K） | 16384 | 638976（624K） |

首档若thinking实际用48K，final只余16K；不能称64K最终正文。现有133996的Qwen参考输入若数值保持，首档共占215916，尚余46228；实际V4 tokenizer／模板必须重计，不为适配档位裁掉必需来源。输入超过首档准入时升级已核窗口或改可保持跨文件关系的任务编排。

现有EXL3外部证据仅配置256K总窗、约250K输入并返回六位校验码，[原始响应](https://github.com/gisenberg/local-model-eval/blob/0ac6602be3477faaf82194a4b7599dc853d64a13/experiments/deepseek_v4_flash_0731_exl3_2.04bpw/long_context_250k.json)的usage还为null。它不能证明64K持续生成，也不能证明512K/1M在单H800可用。首轮应从256K总窗出发验证64K总生成，完整业务若正常提前结束据实记；512K＋128K另作容量、速度和质量扩展门，1M不作首轮承诺。

当前产品预算也不是上表：generic local structured类默认2048；MiniMax profile为16384，H800 Responses候选为16384。generic OpenAI普通chat不发max_tokens，MiniMax适配器的普通chat则委托structured。不能将某一profile的16K说成所有调用统一上限，也不能把模型384K能力说成当前产品已接通。源码见[配置类](../../src/main/java/com/consense/config/ConsenseProperties.java)、[MiniMax适配器](../../src/main/java/com/consense/ai/MiniMaxChatClient.java)、[H800候选配置](../../src/main/resources/application-vetting-h800.yml)。

生成时间仍构成独立门：64K总生成在50TG也约21.85分钟，已超过普通Chat20分钟；128K在50TG约43.69分钟，384K约131.07分钟，均未计prefill。高预算档需要与产品时限及任务执行方式共同评估，不能仅扩大上下文数字。
