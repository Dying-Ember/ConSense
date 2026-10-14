# 更大模型放入单H800：三个具体候选与还缺的证据

日期：2026-10-12，Asia/Shanghai。有限只读研究，保留用户**至少256K总上下文、优选512K**及OCR／LLM可按阶段卸载的约束；没有下载权重、调用模型／API、修改harness或运行环境。这里的80GiB用于规划比较，最终以目标设备实际可用字节为准。

## 1. 可采取的方法和本轮结论

更大模型可以通过**权重量化、静态lookup表CPU驻留、部分专家／层CPU offload、减少不需要的vision／MTP组件、量化KV、降低并发并做chunked prefill**进入单卡研究范围。前三项减少GPU权重需求，KV及调度措施主要压低cache／峰值；OCR卸载只释放OCR阶段的占用，不会把LLM自身权重、KV或prefill工作区变为0。offload是用主机RAM和传输时间换GPU容量；必须保持完整来源及总窗口，不能靠截断满足256K要求。

| 候选／公开配方 | 已核容量事实 | 单H800结论 | 为什么继续或暂停 |
| --- | --- | --- | --- |
| Qwen3.8-Flash-Next FP8＋N-gram CPU offload | 完整FP8 **172.7624GiB**；51B lookup table约**47.7GiB**可官方offload | 只扣lookup仍约**125.1GiB**；单80GiB仍不足，还须主干低bit／专家offload | 当前3.8专业任务有公开收益，但长文抽取没有优于27B；应作为有条件的大模型研究路线 |
| Nemotron3 Super120B-A12B官方FP8 | **119.5212GiB**；官方minimum **2×H100-80GB** | FP8全驻留不足；本轮未核到NVIDIA官方Hopper4bit包 | 官方256K／512K RULER强、含中文支持；任务得分并非普遍胜Qwen122，4bit质量和引擎需独立证据 |
| Qwen3.5-122B-A10B官方GPTQ-Int4 | 完整payload **73.429GiB**；256K BF16 full KV **6GiB**，512K **12GiB** | payload＋KV已约79.429／85.429GiB，尚未加线性state及runtime | 有现成官方Hopper可研究的4bit制品；256K必须省组件／KV或offload，512K更不能凭权重刚好装下认定通过 |

以上既不是“不可能研究更大模型”，也不是单卡正式准入。真正的方案应选**具体checkpoint＋量化方法＋引擎内核＋组件驻留＋context／output档位**，再看业务收益与资源峰值。参数量不能先提供质量排序。

## 2. Flash-Next：51B静态lookup确有CPU路线，但它不是全部问题

[Qwen固定card](https://huggingface.co/Qwen/Qwen3.8-Flash-Next/blob/de4b8e4d43b917e7706784d8bb445c9af86a3540/README.md)说明125B主模型／6B active，另51B N-gram embedding＋4B MTP；lookup提供计算较小的参数扩展，较MoE适合offload。完整官方FP8 revision为`236dfdf285828023ca3bcd3f37366c58a3469b13`，[索引metadata.total_size](https://huggingface.co/Qwen/Qwen3.8-Flash-Next-FP8/resolve/236dfdf285828023ca3bcd3f37366c58a3469b13/model.safetensors.index.json)＝185,502,232,570 bytes／2^30＝172.7624168GiB。没有下载tensor内容。

[vLLM模型维护者recipe](https://recipes.vllm.ai/Qwen/Qwen3.8-Flash-Next)已明确提供`VLLM_PLE_CPU_OFFLOAD=1`，把lookup放主机RAM，异步prefetch所需rows；该路线要求至少51GB主机RAM另加runtime余量，初始实现限NVIDIA。页面标vLLM0.29.0+且要求专用镜像`vllm/vllm-openai:qwen38-flash-next`，其PyPI路线不支持。4×H100 FP8＋PLE CPU已做启动／短输入运行验证，**recipe明确说没有测单个262K请求**。这证明offload已实现，不证明单H800完整256K／512K通过。

[SGLang官方cookbook](https://docs.sglang.io/cookbook/autoregressive/Qwen/Qwen3.8-Flash-Next)要求v0.5.20+，提供`--ple-offload-embedding`及file-backed NVMe路线；它给出NVFP4 Blackwell包中约47.7GiB FP8 lookup、78.3GiB其余权重的案例。CPU／NVMe lookup不是仅概念，也不是减少来源或重新训练。**但这些NVFP4／GB10／RTXPRO6000案例不能移作H800量化内核及长窗口证明。**

即使按47.7GiB lookup全扣，FP8剩余约125.1GiB仍高于80GiB；不把6B active当6B驻留。要单H800须再研究主干int4／其它Hopper低bit包、专家offload或更多组件驻留安排。即使主干约78GiB，也要实算长窗state／cache与prefill；Blackwell容量演示不能直接沿用。CPU lookup会产生host memory／pinning／prefetch需求；专家offload则有不同访问频率与PCIe负担，不能宣称同等成本。

质量收益按[当前任务对照](llm-task-benchmark-qwen-20261012.md)分别看：官方同表Flash-Next对3.8-27B的IFBench81.3对79.5，CoWork73.9对70.7，Job55.7对33.4；LlamaIndex FP8 ExtractBench均分89.88对89.75，但long37.74对38.45。**专业任务信号支持研究，长清单不支持仅靠升级它。** 两者native262,144、官方YaRN factor2至524,288；Flash recipe扩窗cap1,000,000。当前256／512长窗质量及单H800offload延迟仍缺本项目证据。

## 3. Nemotron Super：Hopper有官方FP8，官方NVFP4另属Blackwell

本轮从[NVIDIA官方FP8 model API](https://huggingface.co/api/models/nvidia/NVIDIA-Nemotron-3-Super-120B-A12B-FP8)核revision`744b1880a37996c5d56bf454ae164dfd74d77c4e`，[固定index](https://huggingface.co/nvidia/NVIDIA-Nemotron-3-Super-120B-A12B-FP8/resolve/744b1880a37996c5d56bf454ae164dfd74d77c4e/model.safetensors.index.json)的128,334,890,312 bytes＝119.5211805GiB。卡列minimum2×H100-80GB、支持Hopper，vLLM0.18.1示例默认256K，可override至1,048,576。**“Hopper支持FP8”与“单80GiB足够”分开。**

[NVIDIA NVFP4卡](https://huggingface.co/nvidia/NVIDIA-Nemotron-3-Super-120B-A12B-NVFP4)列Blackwell及单B200／DGXSpark路线；不能把它当已核的H8004bit制品。按BF16模型metadata的123,611,012,096参数乘0.5byte只是约57.56GiB理想算术，没有scale、未量化层、Mamba state、Attention KV、MTP及workspace。本轮官方模型清单可核BF16／FP8／NVFP4，**未核到NVIDIA官方AWQ／GPTQ4 Hopper制品、内核与相应量化任务报告**；公开第三方包存在的搜索条目不提升为官方准入。

潜在单H800路线是Hopper可执行的weight-only4bit＋实际hybrid／LatentMoE内核，或FP8的部分offload。必须绑定量化粒度、未量化模块、校准数据、scales、loader及MTP设置；重新量化也是质量变化，不能把BF16榜单继承为4bit结论。

以下所有值来自同一[NVIDIA固定BF16卡](https://huggingface.co/nvidia/NVIDIA-Nemotron-3-Super-120B-A12B-BF16/blob/2dc98e2afe4face0e4ce40972a915c45368bd34a/README.md)。它有512K／1M合成检索质量实证，但较弱的AA-LCR／多约束结果限制“长窗即整体胜出”的推断。

| 指标 | Nemotron3 Super | Qwen3.5-122B-A10B（该卡对照） | GPT-OSS120B（该卡对照） |
| --- | ---: | ---: | ---: |
| IFBench prompt | 72.56 | 73.77 | 68.32 |
| ScaleAI MultiChallenge | 55.23 | 61.50 | 58.29 |
| AA-LCR | 58.31 | 66.90 | 51.00 |
| RULER256K | 96.30 | 96.74 | 52.30 |
| RULER512K | 95.67 | 95.95 | 46.70 |
| RULER1M | 91.75 | 91.33 | 22.30 |

Nemotron卡声明中文支持，比Nano已核语言清单更适合进入中文候选；仍需中文合同／长表／引文题验证。这个表的Qwen是**3.5-122B**，不是3.8-27B；IFBench prompt、AA-LCR及harness口径也不和其它card硬拼排名。当前没有同源留出题证明它相对3.8-27B改善具体业务残差，只有研究价值。

## 4. Qwen122 GPTQ4：现成制品不等于256K／512K装下

已核[Qwen官方GPTQ-Int4固定包](https://huggingface.co/Qwen/Qwen3.5-122B-A10B-GPTQ-Int4/tree/30cd92cba9707a9aba09d1e490ed4b66b78e9606)payload78,844,078,048bytes＝73.429GiB；group128，attention、shared experts、MTP、visual等有量化排除，不能用122B×0.5byte取代真实index。已保存证据及公式见[H800容量研究](llm-h800-selection-20261012.md)。

其12full＋36linear层、2KV heads×256的单序列BF16 full KV，在262,144／524,288总tokens分别为6／12GiB；FP8 KV理想存储分别约3／6GiB，但须Hopper对应attention／cache内核及质量验证，linear recurrent state和prefill缓冲另计。完整payload＋BF16KV为79.429／85.429GiB，连余量都未加入；即便FP8KV也仍约76.429／79.429GiB另加state／workspace，不承诺512K。

可研究的实际减量为text-only不加载visual、关闭并确证不加载MTP、FP8 KV、单请求、bounded chunked prefill，以及必要的少量权重offload；每项都按loader实际节省bytes记账。[vLLM122B recipe](https://recipes.vllm.ai/Qwen/Qwen3.5-122B-A10B)虽列INT4单80GB形态，但不是本项目长输出及512K验收。native262,144／YaRN524,288规格路线成立；引擎、任务质量、最大窗口仍未完成。

该3.5版本在自己的同表AA-LCR66.9、BFCL72.2较27B66.1／68.5高，但IFEval93.4低于27B95.0、IFBench76.1低于76.5、LongBench60.2低于60.6；仍不是一律胜小模型，更不能借旧表替代当前3.8比较。完整结果与版本口径见[任务评测历史表](llm-task-benchmark-qwen-20261012.md)。

## 5. 下一阶段应证明的具体结论

先用完整来源、同任务schema与固定输出预算，看候选是否改善跨源采用、连续引文、例外闭包、表组和长清单漏项；M3的已保存任务结果只作参考，Bonsai本机受限配方的失败也不是H800完整上下文能力上限。再固定262,144与优选524,288**总**窗口、单请求、目标tokenizer、量化／state策略，记录weights／KV／state／prefill／graph与整卡峰值、hostRAM、offload流量、首token与完整任务延迟。保完整来源及所需输出，不能用短随机prompt的tok/s或启动成功完成业务验收。

本轮结论止于公开身份、方法已实现与算术准入；三候选的单H800256K／512K模型调用、业务质量、hostRAM传输及前端验收均未执行。
