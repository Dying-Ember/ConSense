# 单 H800 推理更大 LLM：RAM、CPU 专家与 KV 的有效路线

更新：2026-10-12，Asia/Shanghai。本轮为有限只读补充研究，查官方文档／源码，不下载权重、不运行 GPU／模型／服务、不改运行配置。遵循 [当前规则](../oer-scope-and-rules.md)、[当前交接](../HANDOFF-ocr-embedding-rerank-20261008.md)；实际任务质量和至少256K、优选512K总上下文先筛选，目标环境资源可行性随后验证。OCR与LLM已确认分时，不重开业务访谈。

## 1. 结论：可以使用超过80GB的权重，但算力与存储实际跨CPU／GPU

有三条真实机制：部分权重放RAM由GPU读取／预取；MoE专家在CPU计算；部分KV及其计算放CPU。它们都能降低GPU常驻量，但不把CPU RAM变成H800 HBM的带宽，也不保证任何新架构／量化制品能直接运行。候选模型仍须有经过支持的格式、模板、算子与完整长窗质量。

必须分清以下身份：

| 机制 | 推理期间哪些内容离开GPU | 谁执行主要计算 | 能解决什么 | 本轮不可宣称 |
|---|---|---|---|---|
| 阶段卸载／sleep | 整个闲置模型权重／缓存 | 此模型暂停推理 | 让OCR、query E/R和LLM先后使用同一卡 | 让超过单卡容量的LLM在醒来后自动完成推理 |
| GPU权重offload | 部分权重保留RAM，按层／访问读取 | GPU | 降低常驻权重、留出KV空间 | 等同CPU专家计算或没有传输代价 |
| CPU专家计算 | 指定MoE专家权重常驻CPU | CPU执行这些专家，GPU处理其余模块 | 不必每token把全部CPU专家权重搬回GPU | 凭active参数算RAM，或对任何MoE通用 |
| CPU活跃KV／attention路径 | 当前序列KV置CPU，按backend执行／搬运 | 依实际计算图 | 真正减少一条活跃序列GPU KV的候选路线 | 仅开prefix connector便能无限扩active context |
| prefix KV分层缓存 | 已完成KV块存RAM／磁盘，命中恢复GPU | 常规attention仍在GPU | 减少重算，提高多轮／跨请求缓存命中 | 储存池大小等于当前单请求可执行窗口 |
| CUDA UVM | managed memory可按系统能力迁移／远程访问 | 通常GPU，驱动参与内存管理 | 由采用该机制的程序实现超额存储 | 框架默认malloc已使用UVM；H800等同Grace-Hopper一致内存系统 |

sleep的既有官方依据、K8s整卡所有权及阶段恢复见 [显存调度调查](./oer-h800-memory-scheduling-20261012.md)。本报告新增的是推理正在执行时的机制。

## 2. vLLM：UVA权重与异步prefetch是真的，CPU专家计算不是同一开关

当前 [官方 Engine Arguments](https://docs.vllm.ai/en/stable/configuration/engine_args/#offloadconfig)已列：

- `--offload-backend {auto,uva,prefetch}`；`auto`按配置选择。
- `--cpu-offload-gb`：每GPU可放CPU的权重额度，单位GiB；当前文档定义为UVA zero-copy，每次forward通过CPU–GPU互连访问，默认0。
- `--cpu-offload-params`：按参数名完整segments选择，例如`experts`；这控制权重位置，不把专家计算改成CPU。
- `--offload-group-size`、`--offload-num-in-group`：每组N层中offload最后若干层；后者不得大于组长。
- `--offload-prefetch-step`：提前预取层数，更大能隐藏部分延迟，也增加GPU staging占用；`--offload-params`可限定参数segments。

这比单纯睡眠更接近“更大模型在单GPU推理”，适合权重仅超出少量或层级搬运能被计算隐藏的候选。H800属于可研究的CUDA路径，但这些通用参数不是新checkpoint、MoE kernel、量化、CUDA graph、Mamba／MTP及512K组合的兼容证明。UVA与prefetch的配置不能混成两个可随意叠加的独立额度。

**256K／512K影响（机制推断）**：长prefill的每个chunk会继续访问各层，更多token增加总工作与搬运次数；较大chunk能增加权重重用，却增加activation／workspace峰值。decode通常逐tokenforward，小batch较难摊薄权重读取，互连容易成为每token瓶颈。异步prefetch不能超过可用带宽，也不能消除需要的staging层峰值。

## 3. KV offload：prefix保存／恢复与活跃序列容量要分别验

vLLM参数为 `--kv-offloading-size`（GiB；TP>1时总和）和 `--kv-offloading-backend {native,lmcache}`，前者未设则不启用，当前默认backend为native（[参数依据](https://docs.vllm.ai/en/stable/configuration/engine_args/#kv-offloading-size)）。

但 [vLLM官方用途说明](https://docs.vllm.ai/en/stable/features/kv_offloading_usage/)明确：completed KV blocks扩展prefix cache，命中后promote回GPU；native通过异步DMA复制到pinned RAM。因而**该功能本身不能证明512K活跃全attention KV无需驻GPU**。LMCache名字也不构成该证明；需核所选backend是否真正实现active KV streaming／CPU attention及模型支持。长prefill冷启动仍需计算，缓存命中只改变之后的重算与恢复；恢复本身受传输带宽及GPU可容纳块限制。

[SGLang HiCache官方](https://docs.sglang.io/docs/advanced_features/hicache_best_practices)同样以RadixAttention的三级缓存和命中率为目标：`--enable-hierarchical-cache`、`--hicache-ratio`或`--hicache-size`（GB）、`--hicache-io-backend`、`--hicache-write-policy`，可选`--hicache-storage-backend`。L1／L2属于单实例，不能把多个实例RAM池直接相加。官方PD例中的`--disaggregation-decode-enable-offload-kvcache`为decode结果供后续prefill复用，不等于单H800在CPU KV上持续执行完整512Kattention。

如果目标是降低单条当前序列KV，先考察模型本身的GQA／MLA／混合attention／recurrent state与经过质量验证的KV精度，再考察真正的CPU KV计算／流送路径。不能通过丢弃旧token、稀疏遗漏关键来源或预摘要来追认本项目完整证据通过。

## 4. llama.cpp：CPU层／专家与CPU KV有明确位置控制

[官方CLI](https://github.com/ggml-org/llama.cpp/blob/master/tools/cli/README.md)有 `--gpu-layers N`、`--cpu-moe`、`--n-cpu-moe N`（**前N层**MoE权重放CPU）、`--n-cpu-ffn N`（dense前N层）、`--moe-cache-mib N`（CPU专家的GPU缓存，默认0）、`--override-tensor`。这些路径依GGUF、模型架构与backend支持，不能直接塞入任意HF权重。

`--no-kv-offload`方向容易误读：[KV源码](https://github.com/ggml-org/llama.cpp/blob/master/src/llama-kv-cache.cpp)默认选择CPU buffer，只有`offload=true`才改为`model.dev_layer(il)`的device buffer。因此该开关确实可把活跃KV保留CPU，区别于vLLM的prefix存取。其attention计算与中间张量移动仍由backend计算图决定；本轮未验证任何候选256K／512K的可执行质量、吞吐或H800完整算子组合。

**代价（机制推断）**：CPU层／专家需要主存带宽与CPU计算；长prefill不能拿短prompt的decode成绩代估。CPU KV随有效序列长度增长，完整attention读取及CPU↔GPU中间数据移动可能显著拖慢decode。`--cache-type-k`／`--cache-type-v`提供不同精度，但降低KV精度需要业务长窗质量对照。文件`mmap`是加载／分页机制，不是让磁盘具有RAM带宽；缺少RAM导致系统swap时成本可能急剧恶化。

## 5. KTransformers／KT-Kernel：更大MoE的重要异构候选

[官方KT-Kernel](https://github.com/kvcache-ai/ktransformers/blob/main/kt-kernel/README.md)列Hopper 9.0／H100、H200支持；Linux x86-64、CUDA驱动与CPU指令集仍有要求，H800整机和具体新模型须实测。正式SGLang集成参数包括 `--kt-method`、`--kt-weight-path`、`--kt-cpuinfer`、`--kt-threadpool-count`、`--kt-num-gpu-experts`；未驻GPU的专家由CPU路径处理。CPU线程／NUMA配置和专用CPU权重不能省略。

这里传递的是激活／结果及所需GPU数据，**不必每token将全部CPU专家权重传回GPU**，所以与dense权重offload的带宽模型不同。不过CPU仍需读取被选专家权重，prefill的众多token可能触及广泛专家，MoE active参数不等于总RAM要求。

官方原生FP8／RAWINT4路径还提供`--kt-gpu-prefill-token-threshold`：较短chunk使用CPU＋GPU混合prefill；超过阈值改用逐层GPUprefill，另需容纳一个MoE层。`--kt-enable-dynamic-expert-update`可更新专家放置。故长prefill不是“所有专家永远留CPU且零GPU层峰值”。`--kt-max-deferred-experts-per-token`与AMXINT4的部分设置有官方质量风险提示，不能默认采用这些近似／低精度设置抵消已选模型质量。

## 6. UVA不是UVM，也没有通用的一键无限显存

[CUDA Unified Memory官方](https://docs.nvidia.com/cuda/cuda-programming-guide/04-special-topics/unified-memory.html)支持依系统能力进行managed memory超额存储、迁移与预取；关键API为`cudaMallocManaged`、`cudaMemPrefetchAsync`及`cudaMemAdvise`。不同系统的page fault、硬件一致性与直接访问能力不同。单H800挂x86主机不能直接套用Grace-Hopper／NVLink-C2C的CPU–GPU一致内存表现。

vLLM所称UVA是统一地址空间下的CPU权重zero-copy路径，并不证明框架使用UVM自动迁移。普通框架GPU分配失败时，增加RAM或改一个CUDA环境变量不会自动得到受支持的managed allocator。UVM若反复迁移超出HBM的工作集，可能thrash；预取能改变时序，却不消除互连和HBM容量限制。因此列作明确采用它的引擎／源码路线研究，不列成当前已验证主方案。

## 7. 对本项目的选择与下一门

建议按候选分别判断：权重只超少量时研究vLLM选择性／预取offload；较大MoE研究CPU专家计算；需真正降低活跃KV时研究CPU KV计算图，不能用prefix缓存替代。已有Qwen3.8-Flash-Next PLE等按组件CPU查表的专门路线与通用专家策略不同，具体候选身份、权重大小与Hopper量化边界以 [上下文调查](./llm-context-256k-512k-evidence-20261012.md)及根选型报告为准。

下一阶段只需核清：目标Pod获配RAM／CPU核与指令集、NUMA、CPU–GPU互连；所选checkpoint／量化／模板兼容；完整256K与512K输入的冷prefill、缓存命中恢复、decode、GPU／RAM峰值及业务正确性。单H800方案仍可使用获配CPU／RAM，比赛允许量必须以实际资源与规则核定，不能假定可任意扩大。

用于理解带宽的算术示例：若每forward实际需跨互连读取`D` byte、有效带宽`B` byte/s，未被计算重叠隐藏的成本受`D/B`约束；假设D=8GB、B=32GB/s，仅传输为0.25s。**这些是示例假设，不是H800测试值**，也不能把总CPU专家权重直接当成每token的D。后续分别测TTFT、prefill token/s、decode token/s与完整任务延迟，不能用启动成功或短prompt输出证明512K可用。

当前只有官方机制及未验条件，未取得新候选的单H800长窗运行结果。
