# 单 H800 的更大模型路线与本机上下文归因

日期：2026-10-12，Asia/Shanghai。承接用户关于更大模型进入 H800 的问题及最新补充：本机 Bonsai 效果不足可能受到本机显存与上下文限制影响。范围是读取已有证据与官方运行机制，未运行新模型、下载权重、修改冻结 harness 或切换服务。

当前规则见 [入口](./oer-scope-and-rules.md)。质量筛选仍优先于容量；主模型至少256K、优选512K，OCR与LLM不同时使用，允许阶段卸载。

用户后续明确三个模块一起评价，Vetting／Advisory先经RAG初筛；因此更大模型是否值得混合部署，应看相关证据包后的审查、回答、引用与缺证收益，不能只凭Drafting抽取或专业总分。最新 [三模块联合研究](./llm-three-module-rag-selection-20261012.md)补充该口径，不改变本文容量与Bonsai归因边界。

## 1. 最新结论

**单张H800可以承担比显存容量更大的模型推理。** 需要区分整模型GPU常驻、GPU计算时读取CPU权重、GPU与CPU分工计算专家、CPU执行巨大查表组件，以及活跃KV置CPU。它们的容量与等待代价不同；“未能全GPU驻留”不等于任何CPU／GPU混合路径都不可研究。

Qwen3.8-27B官方FP8继续是现有调查中最有证据的本地第一顺位候选，尚未本项目实测。更大模型应作为质量挑战者，其中Flash-Next的组件offload路线值得优先核：它有同代专业任务提升信号及正式CPU查表配方，但长文抽取并未改善，主干还需要量化或offload。不能因可塞入就假定更适合所有任务。

**本机Bonsai的FAIL只否定当时端到端配方已经达标，不能否定底座能力或H800足窗口下的潜力。** 同底座官方FP8和Bonsai的完整上下文控制均保持未验；不是只用较小显存、较小窗口跑过，就能判断模型上限。与此同时，具体失败是否由上下文耗尽造成，需要实际请求证据，不能由显存较小自动归因。

## 2. 几种真正不同的方法

| 方法 | 主要保存在何处、谁计算 | 能解决什么 | 对本项目的判断 |
| --- | --- | --- | --- |
| 更低比特权重量化 | packed权重主要在GPU；GPU按支持的内核计算 | 减少常驻权重、为长KV留空间 | 优先官方可复现制品；4bit／FP8质量分别验，不由Bonsai负面经验否定所有量化 |
| GPU计算＋权重CPU offload | 部分权重在RAM，GPU直接读取或异步迁入 | 允许整模型权重总量超过80GB，GPU常驻权重仍受单卡预算限制 | 有正式运行机制；每步访问的字节、带宽与预取决定速度，不能借HBM带宽估PCIe时间 |
| CPU专家计算 | attention、部分专家等由GPU承担，其余专家在CPU计算 | MoE可利用RAM容量，避免每步搬完整专家权重 | 与通用vLLM权重offload不同；需要准确模型支持及CPU指令／内存带宽，27vCPU本身不足以预测吞吐 |
| 特定组件CPU查表 | 巨大embedding／N-gram lookup在CPU；异步回传查表结果 | 将大而稀疏访问的组件移出GPU | Flash-Next有具体官方配方，不能泛化成所有模型都有同类组件 |
| KV精度与存放优化 | KV可量化；部分引擎支持活跃KV在CPU | 缓解256K／512K的状态容量 | KV量化与权重量化分开；prefix-cache offload不能自动减少单条活跃长序列所需KV |
| 磁盘offload／mmap | 超出RAM的权重页留在SSD，按需取回 | 提供更宽的容量研究上界 | 真正的运行工作集与随机读可能主导等待，不作为当前正常业务基线；mmap也不等于权重免费 |

正式参数、版本语义和官方源码见 [运行机制调查](./research/llm-h800-larger-model-runtime-options-20261012.md)。当前版本的vLLM可以选择UVA／prefetch权重offload；KTransformers的CPU专家路线则改变计算分工。引擎支持某机制，不等于支持所有checkpoint和量化格式。

vLLM `kv_offloading` 与 SGLang HiCache 的已核主要用途是保存／恢复prefix cache：命中后数据需要回GPU，不能将其宣传为单条512K active attention不占GPU。llama.cpp的`--no-kv-offload`确有将活跃KV放CPU的语义，但backend计算／搬运代价以及准确模型的长窗质量仍需实测。

阶段sleep／RAM热缓存只在OCR与LLM切换时让出GPU；推理中offload每个请求都可能付代价。用户已经允许阶段切换，所以先把OCR／E/R与LLM的阶段预算处理正确，再决定是否需要推理中offload。

## 3. 更大候选的价值与实际缺口

| 候选 | 已核部署事实 | 任务质量信号 | 当前角色 |
| --- | --- | --- | --- |
| Qwen3.8 Flash-Next FP8 | 完整索引172.7624GiB；官方CPU查表offload约47.7GiB PLE后，GPU剩余量级仍约125.1GiB，需要主干量化或进一步offload | 同代JobBench55.7对27B33.4；ExtractBench长文37.74对27B38.45，没有全面提升 | 组件offload＋主干量化／专家混合的优先质量挑战者，未验单卡256K／512K |
| Qwen3.5-122B-A10B GPTQ Int4 | 现成官方量化制品73.429GiB；256K／512K理论BF16 full KV再6／12GiB，未计其它状态 | 同表27B与122B在指令／长文指标有胜有负，参数不提供胜出保证 | 现成4bit＋小部分offload的工程对照，不是因122B就优于当前3.8 |
| Nemotron3 Super | 官方FP8约119.5212GiB，参考至少2×H100；NVFP4正式制品面向Blackwell，H8004bit路线未闭 | 有512K合成检索成绩与长文推理信号，尚无本项目中文合同成绩 | Hopper量化／CPU专家路线的补充候选 |
| MiniMax M3 | 官方MXFP8参考约444GB；4bit裸参数算术约214GB，不等于存在相应可运行且保质制品 | 本项目已有Drafting正面经验，是重要质量上界 | 强质量参照；混合单卡需要另核准确量化、架构支持与RAM／加载峰值 |

每项精确身份、来源及正式配置限制见 [更大候选调查](./research/llm-h800-larger-model-candidates-20261012.md)、[M3专项](./research/minimax-m3-evaluation-reference-20261012.md)。Flash-Next的活跃6B不是全部驻留量，不能把静态PLE移出后剩余FP8权重直接叫已装下。

如果仅把Flash-Next剩余FP8体积理想折半，主干4bit会落入单H800的研究量级；未量化层、scales、KV、MTP、工作区与运行时重排仍要加回，没有实际Hopper制品／内核时不报准确可用余量。这个算术支持继续研究，不能当部署通过。

M3官方FP8总量大于80GB GPU＋250GB RAM名义合计，无法将该配方全部权重保存在两者中；更低比特或磁盘工作集是另外的路线。约214GB的4bit裸参数量可供RAM／GPU分工研究，不保证加载时无副本、支持官方MSA或保有API效果。

## 4. 本机Bonsai的上下文需要重新解释

本机模型声明262,144，而服务启动窗口和业务排包是不同层次。既有正常配置有32K，后续调参及第五轮有64K，不将所有历史结果统称32K。[原容量研究](../../docs/bonsai-capacity-assessment.md)、[原调参](../../docs/drafting-model-benchmark-results.md)。

根据固定Bonsai元数据，单槽F16 full-attention KV在256K约16GiB，已经超过本机16GB显卡给模型、KV与工作区一起留下的容量；H800提供的资源条件明显不同。这是配置／容量限制的证据，不能据此认定某次短请求确实丢了上下文。

旧61有值题中，防水范围18项确实被sourceText分片，两个片段分别包含前16和后7项，没有单请求同时包含完整18项。这是输入闭包不足的具体混淆因素。把服务窗口调大但继续沿用原分片，并不会自动把完整材料送给模型；最终分片、补查和合并结果也不能全部归给权重能力。

第五轮的保存runtime为64K、F16 K/V、16K默认总生成、medium、4K每思考块。其短source在primary中完整入包，现有28条engine counters没有标记truncated或输出cap命中；因此当前证据不支持把Bills／Sections失败直接归因为64K耗尽。adapter没有保留完整逐请求usage／finish_reason，仍有审计缺口。精确请求长度、source跨度、slot counters与限制见 [本机上下文归因](./research/bonsai-local-context-capacity-attribution-20261012.md)。

这两条结论同时成立：本机未验证完整256K／512K，不能用它否定H800能力；已保存某些短请求的失败也不能由“本机显存小”解释掉。剩余格式、required键与引用问题继续保留。

## 5. 研究后的选择

先保留一条相对简单的主候选：Qwen3.8-27B官方FP8，另用Bonsai在准确完整输入／上下文条件下作归因控制。完整主模型评价还未执行，不把这项建议记录为采用。

更大模型优先研究Flash-Next的CPU查表＋Hopper兼容主干量化／offload，它具有同代专业任务收益信号；122B现成GPTQ4用来回答较少量offload的工程可行性，Super／M3用于有针对性的质量上界。按任务残差决定投入，不展开所有量化的笛卡尔积。

最终比较必须覆盖完整必要资料、256K／512K总窗口及输出预算、条件／例外、全部清单、连续引文与unknown；再比较长prefill、decode、整任务等待和恢复。静态“权重放进内存”、checkpoint能加载、长输入可运行和业务正确分开报告。

本轮全部是研究与文档修正，新增模型推理0、下载权重0、运行配置变更0。当前解析质量→固定库→业务→H800顺序及冻结harness保持。
