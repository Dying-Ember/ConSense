# 单 H800 的 OER 显存、模型生命周期与 Kubernetes 调度研究

<!-- oer-qwen-colocation-20261015:start -->
## 2026-10-15 当前共驻评估：Qwen3.8-27B、完整OER与思考预算

本增量回应用户最新要求：依据[修复后识别流程](../diagrams/oer-recognition-after-repair-flow.md)，将当前OER、Qwen3.8-27B、最高思考档及参考图／原证据共同纳入单H800设计。**状态仍是proposed／离线评估，未部署或变更运行配置。** 下方2026-10-12的“OCR与LLM不会同时使用”保留历史语境；本次评估新增Qwen与当前OER阶段模型共驻，不追认为旧实现已有此能力。

**推荐主案：Qwen FP8权重＋原生256K、BF16 KV常驻；Paddle、MinerU、embedding、reranker顺序复用一个辅助槽。** CPU身份OCR、POI／OOXML、pdfplumber／Camelot及证据编排在CPU侧。双完整视觉仍按同原页Paddle→保存原始CAS并释放→MinerU→保存并释放，不省第二路、不降低原件覆盖或改变BGE精度。

| 规划部分 | GiB | 依据与限制 |
| --- | ---: | --- |
| Qwen FP8权重 | 28.744 | 固定revision元数据tensor字节；实际加载可不同 |
| 原生256K full-attention BF16 K/V | 16.000 | 16层、4 KV heads、head_dim256的数学量；不是混合引擎池保证 |
| Qwen混合状态、工作区、图与分配器预留 | 7.256 | 工程预算，须实测 |
| **Qwen总槽** | **52.000** | 冻结引擎须证明总槽内可容纳完整262,144总token |
| **OER共享辅助槽** | **16.000** | max(当前单路完整OCR预算16，当前FP32串行E/R预算8)，阶段互斥；不是所有辅助全常驻 |
| 全局驱动、残留与安全余量 | 6.000 | 工程预算，不能另把同一安全余量重复算成可用空间 |
| **合计** | **74.000** | 80GB十进制为74.506GiB；实际准入用设备可见byte与外部所有者占用 |

此表提供待测容量目标；当前Linux BF16＋ONNX完整双视觉没有可复用的H800整链峰值。历史Windows Paddle allocated约6.125GiB、MinerU raw约12.029GiB仅作压力参考，不能替代当前Linux整卡峰值；allocated、reserved、NVML不相加。OER16GiB不足时应拒绝当前共驻profile并退到Qwen完整释放后的串行路线，保失败与完整内容，不能靠裁证据、降分辨率或漏掉辅助分支换容量。

16GiB full-KV不能直接转写为vLLM的`kv_cache_memory_bytes=16GiB`并保证256K：linear state、hybrid cache groups、page对齐与prefix snapshots仍需冻结引擎核验。显式KV、chunked prefill、batch及总活跃token须在52GiB总槽内校准；Qwen和MinerU不能各按整卡默认utilization自动扩池。主案一条活跃Qwen生成、一个重GPU阶段；独立逻辑session可顺序复用同一模型进程。生成或OCR事务到可持久边界后再交GPU计算租约，不承诺任意token中途抢占。

**思考档与输出长度分别控制。** 固定Qwen卡最高为`reasoning_effort=xhigh`，还支持medium/low；`preserve_thinking`默认true，历史思考计入后续输入。常规原子语义／类型建议medium；多级跨文件影响、完整分支、引用传播、默认／例外冲突、疑点调查与最终覆盖审查建议xhigh。升级依据用程序结构信号和来源缺口，不能只用模型自信；所有不确定项保留。当前尚无两档同题效果／成本对照。

当前全图参考输入133,996 token已经包括27,683 token的精简旧图投影；投影中的参与关系不自动成为已核因果。按用户要求，每条参考影响链还须附逐关键关系的原证据包，保原文、出处、条件／例外、版本与核验状态；一段不足支持整链时附多份。局部任务带相关子图＋全局索引，集中复核可带完整投影和必要证据。已有全图计数不是全部新增原证据补齐后的最终请求大小。

当前输入133,996＋65,536思考与最终输出预留＝199,532，原生总窗尚余62,612给指令／历史／补查。若按官方超长建议给262,144 reasoning＋131,072 final，则合计527,212，已比512K多2,924且未加新增上下文，须另用1M profile。最高思考档不要求每次耗尽这些输出额度；结束、length耗尽、失败和未决须分别记账。

| 扩展模式，同一工作区／安全预留口径 | 规划GiB | 结论 |
| --- | ---: | --- |
| 256K BF16 KV＋共享OER | 74.000 | 主案待测，不新增KV低精度变量 |
| 256K FP8 KV＋共享OER | 66.000 | 余量后备，数值与内核另验 |
| 512K BF16 KV＋共享OER | 90.000 | 单卡不准入 |
| 512K FP8 KV＋共享OER | 74.000 | 条件后备，仍放不下上述527,212场景 |
| 1,000,000 FP8 KV＋共享OER | 88.518 | 不作为同驻留方案 |
| 1,000,000 FP8 KV、OER CUDA进程完整退出 | 72.518 | 独占后备；YaRN、FP8 KV、完整长请求和实际峰值另验 |

**反复加卸载的时间取决于频次，H800算力不能代替I/O与进程初始化。** 当前`complete_ocr.py`以一页为事务分别启动两路owned provider。P页的启停成本约为P×(Paddle启动＋释放＋MinerU启动＋释放)，之外还要加实际解析。现本地BGE相邻事件约1.758／1.571秒仅是当时小模型重构区间，不能外推27B／H800或完整OCR冷启动。27B常驻可避免日常反复大模型装载；首次cold、warm page cache、RAM热副本、sleep/wake和重新prefill仍分别测量。

为控制导入成本，后续可把OCR进程寿命改成原件或有界批次，理论启停次数从2P变为2×ceil(P/B)。这是待实现的调度改造，当前代码不是该批次复用实现。必须保每页CAS、原页对应、完整双候选、取消／超时、未执行页和精确进程树释放；候选协调等待同页两路齐备。当前E/R正式入口强制FP32 serial offload，双小模型热驻留属于另一待验证profile，不直接放宽原窗口／索引身份。

最多五容器的候选拓扑为App(Java＋Vue)、MySQL、Qdrant、CPU coordinator、单GPU owner。现实现仍是五个CPU容器加一个活动GPU Job，峰值六；固定worker还需真实operation／epoch／进程所有权与持久回执，不能伪造Job／Pod UID。不同原生环境分别冻结，不能混装同一Python环境。当前Java也没有完整Qwen xhigh／preserved-thinking请求与消耗接线，模型卡支持不代表产品已经支持。

本地可重算依据在`.local/h800-oer-qwen-colocation-20261015/`的`inventory.json`、`runtime-fit.json`、`memory-review.json`；精确Qwen配置／权重／KV依据在`.local/drafting-typed-contract-design-20261015/context-capacity-qwen.json`，输入包见`context-budget-root.json`、`context-budget-ntt-sct.json`、`context-budget-scc.json`。模型身份、H800实际容量、每阶段整卡与host峰值、load／warm／release／prefill／decode及全请求时间须独立实测。没有运行GPU／模型／WSL、下载权重、改服务／DB／实际配置；本增量不关闭既有来源或部署验收门。
<!-- oer-qwen-colocation-20261015:end -->

日期：2026-10-12，Asia/Shanghai。状态：**研究与 proposed 配方，未部署、未采用为新规则，未测 H800**。本轮只读取本地文档／源码及公开官方资料，新增本文；没有安装、下载权重、调用模型、修改配置、停止服务或接触集群。

## 1. 当前边界与结论

入口以 [OER 已确认范围与执行规则](../oer-scope-and-rules.md) 及 [handoff](../HANDOFF-ocr-embedding-rerank-20261008.md) 为准。当前仍是各类正常完整解析基线与渐进提高质量，固定库、业务、发布和 H800 后置。用户本轮明确 **OCR 与 LLM 不会同时使用，分开加载／卸载可行**；本研究采用这一业务生命周期，不把 OCR 与大 LLM 的并发常驻当必要目标，也不重新问已明确的业务规则。

建议的成熟基线是：**一个 GPU owner Pod 获得整卡，内部用独立 Python 环境／子进程执行不同阶段；OCR 整批串行，两路完整输出保存后释放；检索取证完成再恢复 LLM。** 初期以进程真实退出／新进程重载保证边界；验证引擎和量化表示后，加入 RAM 热缓存／vLLM level-1 sleep，减少正常批次的冷加载。连续 Advisory 另有 query embedding／rerank 的 CUDA 工作，须优先评估它们与 LLM 的有限 KV 共驻；不能共驻时，查询级切换属于明确退路，其每问一次大模型恢复成本必须计入。以上是工程建议，未完成 H800 容量或时延验收。

单卡容量要分别考虑三个条件：模型能启动、最大有效请求能完成、阶段切换后下一模型能恢复。任一条件不满足，都不能称部署完成。只配置 K8s Pod 数量、`nvidia.com/gpu` 份额或 vLLM 百分比，均不足以证明这些条件。

## 2. 已核当前配方，避免拿历史候选代替实际模型

| 层／事实 | 当前证据 | 对部署研究的约束 |
| --- | --- | --- |
| 比赛资源 | 中央规则／handoff：1×H800 80GB、27 vCPU、250GB RAM、650GB 高性能存储＋1250GB 通用存储 | GPU byte 容量、host 可用量与通道吞吐后续读实际机器；容量不当速度 |
| Linux MinerU full | handoff 2026-10-11 部署增量：4.0.10 full／vLLM 0.19.1／Torch 2.10.0+cu128／torchvision 0.25.0／Transformers 5.10.1；本地完整 GCT 两页 exit0，主模型 CUDA FP16，qualityAccepted=false | 是本地 Linux／A4000 路线可运行证据，不是 H800、全源质量或最终 release lock |
| 当前 MinerU raw 模型 | `opendatalab/MinerU2.5-Pro-2605-1.2B@08aaea840498d49ce16247b6263196cf02814885`，原 PDF 报告实数 1,156,026,624 参数；BF16 参数 2,312,053,248 B | `MinerU2.5-2509-1.2B` 是另一模型；其官方推荐不能追认为当前 checkpoint 成绩 |
| 当前 BGE 身份 | BGE-M3 `5617a9f61b028005a4858fdac845db406aefb181`；reranker-v2-m3 `953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e` | 运行 dtype、输入长度、预处理、缓存及索引签名都要保持身份 |
| 当前 E/R 代码 | `tools/vetting_eval/server.py` 默认 `float32`，只接受 float32／float16；`SERIAL_OFFLOAD` 默认 true | 不能把两个当前 FP32 模型预算写成“共 2GB”；双常驻／FP16 均是新的 runtime 配方 |
| 当前 E/R 释放 | 同文件 `release_other_model` 清 Python 模型引用、GC、empty_cache；下次从 snapshot 重新构造 | 是销毁另一小模型后重新加载，**不是**保留完整 CPU 模型对象的热 offload，也不是 LLM 调度器 |

本地事实来源：[原 PDF 双解析报告](../testing/original-pdf-dual-parser-bf16-validation-20261009.md)、[精度核查](../testing/oer-fp16-bf16-support-20261009.md)、[设计访谈](../vetting-ocr-embedding-rerank-grill-20261008.md)、[当前 E/R 源码](../../tools/vetting_eval/server.py)。Linux full 收据为 `.local/state/oer-mineru-full-linux-20261010/candidate-01/worker-vllm0191-pypdf6190/async-full-worker-03/actual-gct-two-pages-01/actual-execution-summary.json`，handoff 已记 SHA `8af16619d9a4d48f78a83a7e8021dcce683f3705e0abfe514e0eeb1c6f978d98`；本文只核文档记录，没有独立重跑该作业或重审全部 raw。

### 官方配方支持什么

准确 MinerU 4.0.10 对应已核源码 commit `f232373fb319d63e82aefd30be40a2ccc6005d73`，Linux full 递归 Torch extra 并添加 `vllm>=0.19.1,<0.29.0`，Torch extra 要求 Transformers `>=5.10.1,<6`。官方 NVIDIA 推荐 full 提高吞吐；基础“8GB+ available VRAM”是最低条件，不能当复杂原件峰值或 OER 预留额度。[固定 pyproject](https://github.com/opendatalab/MinerU/blob/f232373fb319d63e82aefd30be40a2ccc6005d73/pyproject.toml)、[固定 README](https://github.com/opendatalab/MinerU/blob/f232373fb319d63e82aefd30be40a2ccc6005d73/README.md)、[PyPI 4.0.10](https://pypi.org/project/mineru/4.0.10/)。本轮不依赖猜测的 `v4.0.10` Git tag；固定 commit 由既有本地依赖同源核对记录提供，再在线核正文。

full 名称不代表每个辅助模块全 CUDA。当前准确源码和本地核对记录仍有 ONNX table 路径；模块 provider／forward、异常 fallback 与正常 CPU 分工必须分别表达。当前用户已允许官方适合的 CPU 辅助，主 VLM／embedding／rerank 仍按已定 CUDA。部署不为设备标签一致而改变已核原件质量。

旧 `MinerU2.5-2509-1.2B` 官方卡给出两阶段：降采样整页 layout，再 native-resolution crops 识别，并推荐 vLLM async；A100 的 2.12 fps 是该卡场景，不能预测本项目 full／2605 或 H800 的完整文件耗时。[2509 官方卡](https://huggingface.co/opendatalab/MinerU2.5-2509-1.2B)。本研究保留这项背景证据，但不建议为了其公开数字切回旧模型。

## 3. 业务生命周期决定加载次数

### 3.1 固定库与新文件导入

固定 GCC／GCT／SL 在解析验收后，离线完成 canonical、切块、向量及签名；部署复用与原件／范围／模型／dtype／算法一致的已验缓存。原件修订使身份变化时正常失效，不能拿旧缓存掩盖换版。比赛新增资料走正常真实流程。

proposed 单次导入：CPU 身份／原生结构准备 → 阻止新的 LLM 请求并等待已接受请求结束 → LLM 让出显存 → Paddle 完整批次 → 保存并退出 → MinerU 完整批次 → 保存并退出 → CPU 完整组采用／融合 → CUDA embedding → 当前任务已知 queries 的 CUDA rerank → 保存 query-bound 证据包 → 释放 E/R 按所选 profile → LLM 恢复及 prefill → 生成。

两路 parser 全批次串行加载，不按页来回换；两路都产生完整输出后再按完整组采用。用户已明确 OCR 与 LLM 不并行，此流程的 LLM 不需要为 OCR 保留足以共驻的活跃显存。正常批次“一次大模型恢复”是目标；失败恢复、追加资料和取消后重启各记独立事件。

### 3.2 Vetting 的已知 query 批取证

能够在生成前确定的一组审查 queries 可批处理 E/R，冻结每条 query 的候选与来源闭合，再一次恢复 LLM 做审查。query-bound rerank 分数不能变成适用于未来新问题的通用事实。若 LLM 生成中提出新的取证 query，仍须正常追加 GPU 检索阶段或走已验 E/R 共驻；不能在报告中隐藏这次切换。

### 3.3 Advisory 连续新 query

固定文档已嵌入不等于无需运行 query embedding；每个新问题仍有 CUDA embedding 与 query/passage rerank。最新已定规则不允许资源不足时默默把它们改 CPU。因此候选优先级是：

1. **LLM＋E/R 权重共驻，检索与生成计算按请求串行**。预留 E/R 最大已验峰值和受限 LLM KV；它们可共驻而无需同时计算。常驻 OCR 不在此 profile 内。
2. 共驻无法满足有效上下文／质量时，**LLM sleep → CUDA E/R → 小模型释放 → LLM wake → 重新 prefill → 生成**。这是每问都可能发生的切换，不可借导入批次的一次恢复估算连续问答体验。
3. 若引擎 sleep 不兼容当前量化模型，用进程退出／RAM或文件缓存重载完成同一流程；保存对话正文、引用和请求状态，GPU KV 只是可重建缓存。

对同一 query 的预算应完整记录：`T_queue + T_sleep/drain + T_E_load + T_embed + T_R_load + T_rerank + T_release + T_L_wake + T_prefill + T_decode`。共驻 profile 的加载项通常可摊销；查询级切换 profile 不能省略这些项。选择模型时应把可持续的 Advisory 生命周期列为系统指标，不能只比较独占卡 tokens/s。

## 4. 显存预算与 proposed 初始 profile

统一用 byte 实算，展示时 GiB=`bytes / 2^30`，GB=`bytes / 10^9`。产品名“80GB”不自动替换成公式中的 80GiB。设 `M` 为实际 `device.total_memory`，`D` 为驱动／固定其他所有者／不可释放残留，`S` 为工程余量。

```text
阶段 s 可准入 ⇔ D + Σ resident_i(s) + peak_increment_s + S ≤ M
resident_i = 实际 GPU 权重 + 持久 KV/混合状态 + 执行图/常驻buffer
peak_increment_s = 最大有效输入/并发下激活、工作区、临时重排和瞬时分配
RAM 准入 ⇔ CPU 权重缓存 + 反序列化峰值 + pinned buffers + 产物/索引 + 其他进程 + host余量 ≤ 实际可用RAM
```

weights-only 的算术仅提供下界。参数 dtype 收益不代表激活／图像峰值同幅下降；MoE 激活参数少不代表其他专家权重无需驻留。跨进程 NVML／整卡峰值是准入主证据，各进程 allocated、reserved 解释分配行为，**allocated 已包含在 reserved 中，二者不相加；reserved 与 NVML 也不相加**。[PyTorch CUDA memory management](https://docs.pytorch.org/docs/main/notes/cuda.html#memory-management)。

当前原 PDF 23页报告的 MinerU BF16 权重 2.312GB，但 allocated high-water 12,915,709,952B；Paddle mixed 权重 2,901,046,528B，allocated high-water 6,576,616,192B。Windows/A4000 的 reserved 分别达 42,517,659,648B／16,083,767,552B，整卡共享采样峰值分别16,004／15,963MiB；这些是不同度量和已见压力，不能把它们当 H800 物理 HBM配额或独立 parser 峰值。输入历史、框架和 full 路线亦不同，后续须直接测目标 Linux。[本地逐项来源](../testing/original-pdf-dual-parser-bf16-validation-20261009.md#完整-bf16-运行与质量结果)。

以下 **都是 proposed 测试起点**，不是实测占用或已获准的发布参数：

| profile | 起始策略 | 准入与调整条件 |
| --- | --- | --- |
| 离线导入 OCR | 大 LLM 释放后，每次只有一种 parser；从当前已验完整输入、dtype和单作业起，不先增异步并发 | 测完整最大片／最长表组、辅助层及 engine warm；再有界增 batch，不降低分辨率、漏页或截断换容量 |
| 串行 E/R | 保留当前 CUDA FP32、已验窗口；以 batch=1/2 作为独立目标机器 pilot起点 | 已有脚本batch4是另一配方；每个变更冻结身份，同池必要证据与排序回归通过后扩大 |
| Advisory 双小模型常驻 | **先为当前 FP32 E/R 提议预留8GiB总 envelope**；若两权重、最大2048-token pair/batch、框架上下文和峰值超过8GiB，扩大预留或保持串行小模型 | 8GiB是工程待测上限，不是官方需用量；当前默认serial=true需新profile，不改代码冒称已完成。不能按FP16两模型约2GB推当前FP32预算 |
| LLM 有限KV共驻 | 先设置并发1；`K_L ≤ M−D−S−E/R_envelope−W_L−LLM_workspace` | 由真实 engine、量化、max input/output测得 W和workspace，再定K；所需有效上下文不够时该模型/profile拒绝 |
| 工程余量 | `S=max(4GiB, 0.05×M)` 可作为初始 proposed 门；运行观察不足即增大 | 4GiB/5%是工程起点，非 NVIDIA/vLLM 保证；不得用它代替最大请求边界测试 |

8GiB E/R与4GiB余量相加只是拟定预算，不能直接推出“剩余68GiB可给LLM”，还要扣D、模型临时重排与实际 M 的单位差异。未来 FP16 E/R 若同池回归通过，可能降低常驻预算；改变 dtype 会影响当前向量／cache identity，不能用旧FP32索引成绩直接追认新精度。[BGE-M3 固定 config](https://huggingface.co/BAAI/bge-m3/blob/5617a9f61b028005a4858fdac845db406aefb181/config.json) 也声明float32；官方卡的8192是模型支持长度，不是本项目2048长结构或512短窗口需要随之放大。[BGE-M3 官方卡](https://huggingface.co/BAAI/bge-m3)。

reranker官方卡对正常v2-m3给FP16速度／精度取舍，示例pair长度512；其卡还包含Gemma和MiniCPM的其他示例，不应借那些输入协议替换v2-m3。长结构复评分别按当前tokenizer和完整pair计数核验。[reranker-v2-m3 官方卡](https://huggingface.co/BAAI/bge-reranker-v2-m3)。

### vLLM 参数不是跨模型的硬隔离

固定v0.19.1的 `gpu_memory_utilization` 默认0.9，是当前instance的executor预算；两个进程各设0.9会各自打算使用整卡的90%，不是自动分掉剩余10%。显式 `kv_cache_memory_bytes` 则控制每GPU的KV大小，非None时忽略前述百分比。它不能给权重／其他PyTorch进程建立K8s级隔离。[vLLM 0.19.1 engine args](https://docs.vllm.ai/en/v0.19.1/configuration/engine_args/#cacheconfig)。

因此不提供一个未知120B模型可直接采用的0.8／0.9数值。初次独占OCR可使用经测envelope换算出的utilization，或显式KV预算；Advisory则先计算上式中的K，再由冻结版本读取engine实际可容纳token数。显式K和百分比须选定一种主预算方式，不能误以为两者叠加更保险。模型原生max context、engine可准入KV tokens、业务每请求总input+output三个限制分别验证；128K模型标签不等于本机能运行128K并发。

## 5. 全部生命周期策略比较

| 策略 | 获得的能力 | 实际代价／条件 | 建议次序 |
| --- | --- | --- | --- |
| 子进程退出再重载 | CUDA上下文和子worker边界较清楚，故障可隔离，依赖env独立 | 冷读／反序列化／H2D／engine warm／prefill全部可能重复；闭合全部engine子进程后才能交GPU | **首个可验证基线**；作为后续优化失败回退 |
| OS page cache／mmap 权重文件 | 重载可能免真实磁盘读取，文件可校验 | 不是可直接wake的CPU engine权重对象，可能仍需反序列化／量化重排／H2D；cache可被回收 | 随基线一起测cold/warm；不能把warm当cold |
| 独立兼容量化RAM热副本 | 正常切换免冷磁盘加载；250GB主机有候选空间 | 必须保留同量化表示，预算临时副本与pinned；自定义loader越多回归越复杂 | engine确认后作为优先优化 |
| vLLM level-1 sleep/wake | 保留同一模型权重CPU备份，释放权重／KV GPU分配后可恢复 | KV忘记，重新prefill；足够RAM和准确allocator/backend；残留显存实际观测；不自动释放K8s GPU分配 | **首选引擎内优化**，与真实量化kernel＋H800完整循环验证 |
| vLLM level-2 sleep/reload | 不需要保存全部旧权重CPU备份，可换模型／更新权重 | 权重和KV都丢弃，wake后需reload_weights再恢复KV；不是同模型热恢复捷径 | 仅level1不适配／host不足等明确条件用 |
| E/R＋LLM有限KV常驻 | 连续Advisory免每问大模型wake和多数小模型load | 牺牲LLM可用KV；最大token/pair/并发必须共驻测；可只串行计算而不同时forward | **导入基线之后最有价值的问答优化** |
| 每问LLM睡眠、E/R后恢复 | 满足E/R都CUDA，释放最大GPU容量 | 每问恢复大量权重、清KV再prefill；完整TTFT可能显著增加 | 共驻不能准入时的明确退路 |
| KV CPU offload | 部分缓存放host，可能缓解长context GPU压力 | 传输／命中率／prefill与decode成本、host/pinned峰值；引擎／模型的KV和混合状态支持须验 | 后置优化，不能先假定补足共驻余量 |
| 权重CPU offload每forward搬运 | 模型GPU权重可小于全量，容量不足仍有候选 | 常态forward都受host-device通道影响；MoE routing不代表无需移动非驻专家，量化/backend适配独立核查 | 仅无更合适模型且完整业务延迟可接受时评估 |
| eager或减少CUDA graph捕获 | 可降低某些常驻graph／捕获峰值 | 可能牺牲速度或改变数值／shape路径；不修权重和有效KV不足 | 峰值归因明确后单因素试验 |

vLLM0.19.1 level1／level2、分步wake权重与KV及HTTP限制都有官方定义；online控制端点要求 `VLLM_SERVER_DEV_MODE=1`，应只交内部调度器访问，不能把sleep/reload管理口直接暴露给普通用户。[固定 sleep 文档](https://docs.vllm.ai/en/v0.19.1/features/sleep_mode/)。该版本文档没有保证所有量化模型、第三方allocator和CUDA graph资源都完全释放，因此以实际残留为准；`latest`文档新增的graph offload等参数不追认为0.19.1已支持。

KV CPU offload和权重CPU offload是不同机制。前者转移缓存，v0.19.1提供KV host buffer/backend参数；后者 `cpu_offload_gb` 的官方解释是forward时从CPU取部分参数，需要快host-device互连。两者都不等同于模型不用时进行的一次sleep。[固定 offload args](https://docs.vllm.ai/en/v0.19.1/configuration/engine_args/#offloadconfig)。不得拿H800内部HBM带宽估计RAM→GPU速度。

若实际搬运W=60×10^9 B、有效H2D带宽12×10^9 B/s，纯传输下界W/B=5秒；仅是量级算术，非H800测量。一次完整wake还含分配／重排／warm，下一请求含prefill；每问策略累积这一成本，整批策略摊销它。反方向D2H可能也在level1成本里：预留旧CPU热副本可避免不必要回拷，但不能假定vLLM内部具体实现已经采用此优化。60GB RAM副本也不能证明250GB host峰值足够。

## 6. Kubernetes 调度的关键接缝

### 默认独占与多Pod轮换

默认device plugin让容器请求一个GPU扩展资源；GPU limits和requests相等，只有limits时requests使用同值。[Kubernetes Schedule GPUs](https://kubernetes.io/docs/tasks/manage-gpus/scheduling-gpus/)。当LLM Pod仍占单卡resource时，另一个OCR Pod申请同一唯一resource不能因为LLM已sleep就自动准入：sleep释放的是CUDA内存，K8s分配仍属于LLM Pod。只写“LLM sleep后启动另一个整卡OCR Pod”会形成资源等待，而非完成轮换。这个结论是K8s资源分配语义与引擎sleep行为的工程推导。

可选两种部署形态：

| 形态 | 生命周期与依赖 | 适用判断 |
| --- | --- | --- |
| **一个GPU owner Pod，一个申请整卡的worker容器，内部子进程／多个不可变venv** | LLM／Paddle／MinerU／E/R以独立进程启动；各依赖lock独立；manager控制租约和真实进程闭合；CPU API／数据库可其他Pod | 首选候选。避免跨Pod整卡资源轮换死锁；不要求把互冲依赖装同一venv |
| **每阶段独占GPU Pod，前一个真实终止后下一个才创建／准入** | 每任务镜像独立最清楚；任务状态与产物持久；LLM Pod删除会丢其私有RAM热对象 | 基线可行，但每问这样滚Pod代价大。`Recreate`类发布过程和恢复时延需验；不宣称无中断 |
| shared GPU多个Pod＋统一application租约 | time-slicing让所有相关Pod获得设备访问；独立镜像可保留sleep的LLM host副本；只有拿到epoch令牌者才分配／计算 | 条件候选。所有入口／warmup／重启／后台worker均须服从同一manager；外部赛方LLM不受控则不能证明安全 |

“单Pod多模型”不等于单Python进程；统一owner只收敛GPU生命周期，各模型继续独立依赖、日志、模型身份和故障边界。多个containers各写`limits nvidia.com/gpu:1`也不能被假定共用唯一卡；建议先用一个GPU申请者管理子进程，具体镜像布局冻结后再按集群runtime验证，不通过hostPath裸设备绕过device plugin。

### time-slicing、MPS、MIG为何不能替代生命周期管理

| 模式 | 官方能力 | 对本项目的判断 |
| --- | --- | --- |
| time-slicing | GPU replicas共享设备，不提供显存／故障隔离；请求更多replicas也不保证更多算力 | 让多Pod可见卡不等于每Pod有VRAM额度；可以配合统一lease，但不能用它证明“LLM＋OCR可并驻”。OCR／LLM已有互斥业务，不需要为计算并发先引入它 |
| device-plugin MPS | 管理显存／算力份额；plugin按replicas的相等分数限制，每客户各一份；MIG启用设备不支持此sharing，官方文档仍标实验 | 两份时每份约半卡，无法装60GB量级LLM；不是自动给LLM70GB、E/R10GB的任意不对称划分。不同配置或裸MPS能力须另验，不能从plugin示例推导 |
| MIG | 预定义硬件实例提供显存／故障隔离 | 真隔离但会缩小单实例容量。H800官方列10／20／40／80GB类profile，80GB实例占完整GPU；没有一个保留约70GB大LLM、另切10GB的任意profile。若LLM实际需要>40GB，常见拆分会使其无法准入；不作为120B默认方案 |

来源：[GPU Operator time-slicing](https://docs.nvidia.com/datacenter/cloud-native/gpu-operator/latest/gpu-sharing.html)、[NVIDIA device-plugin共享/MPS](https://github.com/NVIDIA/k8s-device-plugin#shared-access-to-gpus)、[H800官方MIG对应profile](https://docs.nvidia.com/ai-enterprise/release-8/latest/infra-software/vgpu/reference/hopper-h800.html)。最后一个来源包含vGPU规格，不等于本集群已经配置MIG/vGPU；实际SKU、driver、权限和可用profile仍后续核查。本轮没有更改MIG模式或device-plugin配置。

### proposed 资源租约契约

只有已受统一控制的GPU owner能签发lease；每个lease保存阶段、owner进程／Pod UID、epoch、source/model/recipe digest、输入边界和GPU预算。worker加载模型之前复验令牌；新任务、自动重启、异步warmup不得绕过。lease超时只拒绝新工作，不能立即给第二worker：必须先确认旧owner已退出或已sleep、全部受控engine children状态和实际显存回落，再转让。控制器重启先reconcile当前PID／Pod／GPU UUID与journal，不把丢失内存锁当无人占卡。

K8s Lease只记录协调身份，没有独立显存fencing能力。要防split-brain须让GPU访问集中在单owner或所有worker强制核epoch，配合进程闭合及设备观察；不存在统一控制的外部进程不由本项目任意杀掉。此契约是proposed实现，当前没有新scheduler或manifest。

## 7. OOM、释放、恢复、发布验收

### 必须留下的证据

每次真实pilot绑定：GPU UUID／真实byte容量／驱动／CUDA capability、镜像digest／Linux依赖lock、模型revision／量化／tokenizer、实际module device/dtype、请求完整input/output token、image/crop与dpi、最大pair／batch／并发、stage owner/epoch、全worker PID与终态、整卡NVML时间序列、各进程allocator high-water、host RSS/cgroup峰值及RAM cache状态。

对进程退出方案，`empty_cache`只释放unused缓存，不能释放仍被tensor持有的模型；对sleep方案，HTTP200／is_sleeping不是足够容量证据。验收必须观察准确owner及children、真实free／residual，并运行下一模型的有效最大请求。不能要求全卡显存归零而误杀驱动或其他受保护owner；释放目标是所选profile声明的残留上限。[PyTorch empty_cache定义](https://docs.pytorch.org/docs/main/generated/torch.cuda.memory.empty_cache.html)。

### proposed 验收矩阵

| 门 | 实际动作 | 通过证据／失败处理 |
| --- | --- | --- |
| 最大完整OCR | 完整最大片／最长表组，两parser各保持当前质量配方，单作业后再测已提batch | 全输入、完整raw及EOS／异常状态；最大整卡／host峰值满足预算；不静默CPU、降dpi、截断 |
| E/R有限共驻 | 实际LLM常驻＋实际FP32双模型，最长已验2048pair及边界batch；再LLM最长有效请求 | 同池必要证据／排序／引用回归，整体峰值与余量；不靠两个小权重相加追认峰值 |
| 生命周期 | 退出重载、冷文件／暖文件／RAM热／level1分别测；覆盖import→Vetting与连续多query | release/wake/ready/first-prefill/TTFT分别计时；失败轮保留；缓存身份明确。level2不得假装level1 |
| 异常与OOM | 可复现受控失败、取消、worker异常、manager重启／lease失联／wake失败 | 不重复发布或进入自动OOM重启风暴；准确owned进程收尾，保存失败产物；不杀未知所有者 |
| 恢复资格 | OOM后先核原artifact、source/model/recipe和children；按当前可准入预算再运行下一阶段 | 完整状态、模型ready＋实际gold smoke＋下一最大请求；没有source/index失配；部分输出不升级ready |
| K8s更新／回滚 | 冻结镜像／manifest／依赖lock与PVC，停止旧GPU owner确认释放后启动新owner | 单owner无并行warmup，old/new digest清楚；rollback回已验镜像／模型／index版本，无混写 |
| 业务 | 真实模块查询、Vetting报告、Advisory连续问答及前端过程状态 | 分别验必要证据、引用、unknown和用户可见等待；资源或代码门通过不追认业务门 |

受控OOM后不先提高utilization或盲增输出cap。按原因区分：weights/load peak、KV、图像activation、E/R padded batch、graph capture、host offload RAM、其他owner。可恢复时只缩小**已验证且不改变完整内容覆盖**的batch／并发，或选择已验阶段切换profile；长请求仍不能准入则明确失败，不静默裁证据。非法访问／CUDA context损坏类失败应结束准确worker并新进程恢复，不在原失败上下文继续发布结果。

250GB host memory为候选RAM热副本提供空间，但Pod的memory limit还需容纳sleep备份、临时展开、pinned、解析与索引峰值；硬设较小cgroup limit会先host OOM。Qdrant Local继续单进程单写者，多个Pod共享PVC不授多writer资格；多副本需求到后续工程阶段改服务式方案并验，不在当前解析质量阶段迁移。

## 8. 推荐优化顺序与未完成状态

1. 保持当前解析质量主线；研究不启动H800、GPU运行或新依赖工作。
2. 后续冻结Linux模型与worker、独立lock；建立单GPU owner＋进程退出重载基线，完整import→两parser→E/R→LLM闭合。
3. 真测冷／暖文件cache及量化RAM表示；支持时把LLM整批切换优化为level1 sleep。保留退出重载作为恢复路径。
4. 在Advisory实测FP32 E/R＋LLM有限KV共驻；保持OCR互斥。若通过，共驻作为query常规profile；若失败，每问受控sleep/wake的实际等待透明展示。
5. 只有容量／时延数据表明收益，再单因素比较FP16 E/R、KV CPU offload、权重CPU offload、减少graph及受控共享Pod。所有变化分别做身份和质量回归，平台失败回本地新版本修复并重新扫描，不在H800现场开发。

当前只完成技术研究。没有证明H800有效上下文／共驻容量／冷暖加载时间，没有证明vLLM sleep兼容赛方真实120B量化模型，没有确定集群plugin/storage/runtime，没有目标镜像／manifest部署或业务验收。研究支持“按真实业务阶段分开加载卸载”是可行且合理的设计方向；实际可运行的完成门仍是上述完整循环与边界请求证据。
