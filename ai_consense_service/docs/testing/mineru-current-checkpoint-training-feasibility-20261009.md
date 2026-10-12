# MinerU 当前 2605 Pro checkpoint 的训练可行性与完整解析回接

日期：2026-10-09。执行者：Codex。状态：**官方资料／固定源码／本地小配置核查完成；训练、LoRA 合并、导出重载与训练后业务质量均未执行。**

本轮仅获取公共文本与小源码、只读核对现有配置，并计算资源估计。未安装依赖、下载权重、加载模型、训练、修改 vendor 或切换在线配置。原件、密钥及运行服务未操作。证据保留在 ConSense 根目录忽略的 `.local/state/oer-training-research-20261009/mineru/`；中央规范仍以 [OER 已确认范围与规则](../oer-scope-and-rules.md) 为准。

## 当前决策

用户最新明确的实施流程是：**在本机 RTX A4000 16 GB 调试模型参数、代码和配置，完成必要的 LoRA 兼容／资源 pilot；选定后冻结并打包，再部署到单 H800 80 GB 超算。**超算平台的代码改动需额外扫描，不作为迭代训练或现场修改代码的场所；平台承担部署兼容、真实显存、吞吐、模型恢复与应用验收。若部署验收发现需改代码或模型，回本地修正并生成新冻结包。

当前 1.2B checkpoint 存在合理的定向 SFT／LoRA 工程路线。**难点主要是标注、实际输入／输出语言、16 GB 本机完整峰值和完整 parser 回接，不能只凭模型参数小或旧教程断言训练可直接用。**第一轮应先在本地修正布局／裁图／资产与结构接口，并调好推理配方；只有完整、清晰 crop 内仍持续识别错误，才进行有界的识别／表格 pilot。本次没有承诺本机一定训得动；后文 80 GB 训练算术仅保留作历史资源参考，不属于本次部署流程。

官方 2509 教程明确支持旧模型的表格／公式／文字／布局任务，并要求加载自有 `mineru_ext` 扩展；当前 MS-SWIFT 源码明确登记的是 **2604 Pro**，不是本机 **2605 Pro**。本机 2605 的架构、processor 与 Qwen2-VL 路线一致，因而可以设计兼容验证；本次没有证明“当前 checkpoint 的官方现成脚本＋完整 Standard 训练导出链已通过”。[S1–S5]

更关键的是，**MinerU 4.0.10 Standard 的布局由小模型完成，2605 VLM 负责已有框的识别**。只微调 VLM 的 `Layout Detection` 任务，当前 Standard 不会因此改用它；漏框、错误类别和被裁掉的编号不能据此承诺修复。[S6–S8]

## 固定对象与支持等级

| 对象 | 本轮核实结果 | 能支持的结论与限制 |
| --- | --- | --- |
| 完整 parser | MinerU `4.0.10`；release tag `mineru-4.0.10-released` 指向 commit `c221cc41bc911ad0df3eaeda97acf6a1bfe9bf93` | 本机当前 Standard 源码与固定 release 的窗口编排文件字节相同；不是旧 MinerU 2.5 的单 VLM e2e 脚本。[S6–S8] |
| 当前 raw checkpoint | `opendatalab/MinerU2.5-Pro-2605-1.2B@08aaea840498d49ce16247b6263196cf02814885`；HF API 与本地配置一致 | `architectures=[Qwen2VLForConditionalGeneration]`、`model_type=qwen2_vl`、BF16；实际参数 1,156,026,624。官方卡给 Transformers／vLLM 推理示例，未提供这个 revision 的完整 SFT／LoRA 合并验收。[S1–S2] |
| 当前 processor／词表 | `Qwen2VLProcessor`／`Qwen2VLImageProcessor`；自有 OTSL、方向、续段等 token | 必须使用该 snapshot 的 processor、tokenizer、chat template；不能用通用 Qwen2-VL 词表替代。[S2] |
| 旧官方训练营 | 教程指定 `MinerU2.5-2509-1.2B`、四项任务、PubTabNet demo 1,000 train／200 val | 证明旧任务有官方训练实践；demo 数量不是我们合同场景最低数据量或泛化保证。文档明确“不要直接使用 swift sft”，要求 `python run_mineru.py sft` 加载 `mineru_ext`。本轮未取得／核对教程提及的完整 ZIP 扩展代码，不声称能复用它训练 2605。[S3] |
| MS-SWIFT 当前固定源码 | commit `410a5d18930725d1d3cfe3acdd611eb9795c403b` 的支持表与 Qwen 注册代码列出 `MinerU2.5-Pro-2604-1.2B`，未列 2605 | 有 Pro 家族／Qwen2-VL 的框架支持依据；没有当前 2605 专属注册／实例运行证据。配置路径、`model_type` 与 `template` 可显式指定，但必须做零步兼容检查。[S4–S5] |
| 通用训练机制 | MS-SWIFT 支持 `peft` backend、LoRA、全参、vision／aligner／LLM 冻结控制和 gradient checkpointing | 这是框架能力。先验证实际可训练模块、label、image grid 与 loss，再决定使用通用 Qwen2-VL 注册或独立 HF Transformers＋PEFT collator；后者是工程候选，不是本次已实现路线。[S5、S9–S10] |

本地 `config.json` 顶层最大位置为 32,768，嵌套 `text_config.max_position_embeddings` 为 8,192。两值不一致本身不授权增加训练长度；第一轮继续核实实际加载配置与实际总 token，超长完整目标保留为失败／另行处理，不静默截断。processor 的 `max_pixels=1,605,632`、`min_pixels=50,176` 与原生 chat template 都须绑定数据配方。[S2]

五份现有小配置的 SHA 与旧下载身份收据复验一致；本轮没有重新下载或读取模型 tensor。HF API 的 parameter count 与此前 [真实精度实验](./mineru-original-pdf-precision-validation-20261009.md) 的运行参数计数相符。证据见 `config-and-vendor-inventory.json`。

## 当前实际任务语言：训练不能只喂最终 Markdown

当前 `mineru-vl-utils==2.0.5` 的实装 client 与本轮固定上游 commit `405520b8fe6be61725537984eb4a00d404dc272a` 的 client／OTSL 转换文件字节一致。[S7]

| 子任务 | 当前实际输入／target 约束 | 训练优先级 |
| --- | --- | --- |
| Text Recognition | 完整实际 crop＋准确 `\nText Recognition:`；target 为可见原文及该任务已有的表示 | 有完整 crop 仍错读关键字符、编号、单位时可试。不得把原件错拼自动写成“纠正后的原文”。 |
| Table Recognition | 完整实际表格 crop＋准确 `\nTable Recognition:`；raw target 使用 OTSL | 合同表的 cell／span、空格槽、表头与数值归属可作为第一项定向试验；必须保存完整表组 gold，不能只训练最终 HTML／Markdown。 |
| Formula Recognition | 完整 crop＋准确 `\nFormula Recognition:` | 本轮尚无证据证明它是主瓶颈，先不增加训练任务。 |
| Layout Detection | 页面缩放后＋准确 `\nLayout Detection:`；box／ref／direction token 序列 | 该 VLM 任务存在，但 Standard/high 不调用它。调整此任务只有在独立确认 Advanced 或新布局路线时才有意义。 |
| Image／Chart Analysis | `\nImage Analysis:`；当前源代码存在这一任务 | Standard/high 在窗口编排中强制 `image_analysis=False`。图内检索文字缺失先处理功能接入和原图映射；只训练不能使未执行任务自动执行。 |

Prompt 来自当前 client `DEFAULT_PROMPTS`；`DEFAULT_SYSTEM_PROMPT` 为 `You are a helpful assistant.`。旧官方教程也要求训练／推理 prompt 一致，不能更换成通用“总结该文档”后仍称同一 OCR 任务。[S3、S7]

OTSL 当前转换器识别 `<fcel>`、`<ecel>`、`<lcel>`、`<ucel>`、`<xcel>`、`<nl>`：有内容起始格、空格、横向合并续格、纵向合并续格、二维合并续格、行结束；实际含义以当前转换器和完整 cell gold 为准。`<ched>` 也在 checkpoint 词表中，但当前转换器主分词模式不包含它，不能自行把它作为已支持 header 编码。[S2、S7]

这些 token 均为 `special=true`。训练 collator 必须验证 assistant target 中结构 token 的 loss label 未被错误清成 `-100`，decode／serving 不能用 `skip_special_tokens=True` 抹掉它们；EOS、用户图像占位和 assistant 结构 token 分别处理。当前 adapter 保留这些 token 的真实输出已在原始精度实验保存。[S2、S7；精度报告]

以下只是符合 MS-SWIFT 多模态数据形状的**待验证示意**，不是已制作标签，也不是可直接开始训练的数据集：[S9]

```json
{"messages":[{"role":"system","content":"You are a helpful assistant."},{"role":"user","content":"<image>\nText Recognition:"},{"role":"assistant","content":"经原页核对的原文"}],"images":["/data/crops/text_001.png"]}
{"messages":[{"role":"system","content":"You are a helpful assistant."},{"role":"user","content":"<image>\nTable Recognition:"},{"role":"assistant","content":"<fcel>Header A<fcel>Header B<nl><fcel>Value<ecel><nl>"}],"images":["/data/crops/table_001.png"]}
```

每条训练记录另保存来源 SHA、物理页、crop bbox／转换、文件家族、split、历史暴露和 gold revision；这些溯源字段不作为识别模型答案提示。先按原文件／近重复／派生家族划分，再裁图和增强。现有 23 页是已暴露开发样本；2d／2e 同题答案不进入任何训练 target。[中央规则]

MS-SWIFT `Qwen2VLTemplate` 会经过 `qwen_vl_utils.fetch_image`，随后调用 processor 的 `do_resize=False`；这与本机现有 adapter 直接调用固定 processor 的细节并非天然等价。零步检查须比较像素 resize、grid、图像 token、system／prompt、assistant label 与 EOS，不能只看模板名称相同。[S5]

## 真实错误分别由哪一阶段负责

以下例子来自 [原始 PDF 双路实测](./original-pdf-dual-parser-bf16-validation-20261009.md) 与 [MinerU 原页精度报告](./mineru-original-pdf-precision-validation-20261009.md)，不是新训练成绩。图像、文本、结构和关系的接受状态分别报告。

| 已观察问题 | 最小修复／调查对象 | VLM 微调能否直接解决 |
| --- | --- | --- |
| GCT1 的左侧条款 1 未进正文 crop；条款 3 被边注框包含 | Standard 的 PP-DocLayoutV2 检测、块归属、normalization 与裁图；检查原始框→实际模型 crop 的链路 | **不能直接解决被裁掉的编号**。认字 VLM 没收到这些像素。若采用 VLM layout 则为另一条待验证路线。 |
| APB3 漏 GENERAL NOTES 两行及左下标识 | 先定位原始检测／过滤／crop 中是否有该区域 | 只有完整区域确实进入识别、仍漏读时，才作为 recognition SFT。不能把漏块先归为语言模型能力。 |
| APC19 技术图资产存在但误分类为 table；PROJECT 错重复为 DRAWING TITLE | 类型和区域角色先看 detector／接口；之后才看完整 titleblock crop 内文字、键值归属 | 类型修复与文字 SFT 分开。原图存在不计“图丢失”，改文字不自动证明资产关联正确。 |
| AppH Part C30 pressure unit `kN/m2` 进入 Offered cell；31 虚拟多一列 | 比较 actual crop、raw OTSL 与转换后 HTML，制作完整物理列／span／表头 gold | raw OTSL 本身错误时可定向表格 SFT；raw 正确但转换错时修 parser。不能逐格从两路拼结果。 |
| APL 待填空槽、FoT 删除／划线状态缺失 | 当前表示能否表达 blank／strike，原生层与视觉状态是否保留；接口先做明确状态契约 | 未定义输出和下游消费方式前，增加 strike 训练文字目标无意义。状态差异不能用拼词修正处理。 |
| PRE.B4.070 等完整前缀错读 | 首先确认前缀所有字符在实际 crop 中；随后按关键编号字符单独评价 | 完整 crop 内仍截短／错读的残留可试 recognition SFT；不硬编码这一个编号。 |
| SL3436–3438 AMR 的跨页 row3／row7 续行未自动归父行 | 邻页表组、完整表头与 cell/span 的组关联和后处理 | 单页裁图训练没有邻页上下文，不能自动建立跨页父行。保留完整表组采用 gate；已有较好页内结果不先改坏。 |
| SL1329 流程图文字未进检索，crop 边缘略裁 | Standard 图内任务禁用、资产边界／caption／条款归属、Q10=A 返回接口 | 未启用任务和裁掉的边界先修接口；图中文字／图表 SFT 只有在正确完整输入＋任务实际执行之后才评估。首版不要求自动理解箭头关系。 |

Standard→high，Advanced→xhigh；前者小模型 `layout_model.batch_predict` 后 `batch_extract_with_layout`，后者 `batch_two_step_extract`。不能将整个 parser 的所有错误都作为 raw VLM 一次训练的目标。[S6–S8]

## 本地试验、导出回接与冻结打包 gate

1. **先在本机做零更新兼容检查。**在独立环境固定当前 snapshot、MS-SWIFT／Transformers／PEFT 版本。先检查文本／表格代表 crop 的输入与完整 target token、loss mask、实际 trainable modules、有限 loss；不跑任何优化 step 也先做保存→新进程重载与完整 parser 回接。部署所用 Linux 环境、依赖与 engine 尽量在本地独立兼容环境预验证，Windows 推理成功不追认 Linux/H800 成功。保留失败；当前全部未执行。
2. **只选一种残留问题。**建议首项为完整合同表格的物理列、span、空槽和单位归属，或完整 crop 的关键编号。先用少量已正确标注 crop 验证能学会格式，再用家族隔离内部验证判断退化与改善；小集过拟合成功只能证明训练链能工作，不算准确率／泛化。
3. **本机资源 pilot 先采用非量化 BF16 LoRA。**候选 rank 8、microbatch 1、gradient accumulation、gradient checkpointing／recompute；冻结 ViT／aligner，仅给 LLM 投影加 LoRA，核验冻结层实际没有 gradient／optimizer state，训练禁用推理 KV cache。完整输入＋完整 target 先做 forward、backward、optimizer step 峰值检查，有限 loss／grad 和来源映射通过后才增加 steps。参数和学习率在本地选定；vision／aligner 解冻、全参、量化及 attention 改动分别需要新 pilot，不自动扩展到平台训练。[S10]
4. **导出完整 HF 模型目录。**MS-SWIFT `merge_lora` 源码执行 `Swift.merge_and_unload`，随后保存模型与 `template.processor`；这提供通用导出机制。adapter-only 目录不等于完整 MinerU 模型。合并后核验 config、权重 shapes、tokenizer 所有结构 token 与 ID、processor、chat template、EOS；随后在全新进程加载，不能用原内存对象充当重载通过。[S11]
5. **先在本地通过官方 remote VLM seam 回到 Standard。**以选定的 Transformers 或 vLLM engine 服务合并后的完整 HF 模型，再给独立 MinerU 配置 `model.vlm.server_url` 与 `model.vlm.model`；随后准备相同任务契约的 Linux/H800 部署包。官方 4.0.10 入口会优先使用 remote VLM，但仍需要 Standard 的小模型、页处理、assets 和完整后处理。最小路线无需修改固定权重 registry 或冒造模型完成 marker。[S8、S12]
6. **确认任务语言穿过 serving。**服务必须保留 raw OTSL／所有必要特殊 token、固定 prompt、图像 bytes、实际输入／输出 token 和终态。`/v1/models`、一次聊天成功或训练 loss 下降，均不是 Standard 页级通过。vLLM 版本及 MinerU logits processor 另行固定验证；不能用普通聊天服务过滤后的输出替代 OCR raw target。[S1、S7]
7. **完整页／表组回归与文件级留出。**比较训练前／后相同完整原页和全部受影响表组，分别评价关键字符、漏块、读序、cell/span／blank／状态、资产完整性和来源映射；保留 MinerU 第二路原始证据及 Paddle 对照。漏块、跨页与图内检索接口尚未通过时不被 recognition 改善覆盖。
8. **在本地冻结部署包。**固定代码 commit／本地补丁 SHA、parser 与 engine 版本、完整模型目录 SHA 清单（如采用 LoRA，则带合并后的完整 HF checkpoint）、小模型、processor／tokenizer／chat template／OTSL token ID、task prompt、精度／attention／DPI／batch／预算、scope／source／gold／配方 revision。备齐 Linux Python／CUDA／依赖锁定、匹配 wheel 或镜像、启动／健康／卸载恢复脚本、离线 assets 与代表回归输入；密钥留运行环境。Windows venv 不作为可直接搬到 Linux 的交付物，不能留“上平台后补代码／下载遗漏依赖”的步骤。
9. **H800 使用冻结包验收。**平台确认启动及完整任务兼容、实际模型 dtype、整卡／allocator／host 峰值、完整目标覆盖、冷／暖加载与权重读入 VRAM、ready／首个请求、完整页与表组吞吐，以及故障／卸载／恢复后的引用和资产一致性。部署配置中可预先设计平台 profile，但质量敏感配方不在平台现场试改；需要变更则回本地复现、修正、回归、重新冻结并提交新包。平台容量与性能验收不代替本地原件质量对照。

回接配置的形状如下，仅作为独立实验配置示意，**本次未写入在线配置、未启动服务**：[S12]

```yaml
model:
  source: local
  small_backend: onnx
  vlm:
    server_url: http://127.0.0.1:18091/
    model: consense-mineru-2605-table-lora-v1
    max_concurrency: 1
```

当前 `get_vlm_predictor` 的默认本地调用把 `model_path=None` 交给 registry；不能只把磁盘中某个文件换成 adapter 或给未使用的路径，就认为完整 Standard 已加载新权重。remote seam 可明确隔离新旧模型身份，HTTP 开启前还需按本机服务边界独立验证。[S8]

## 本机 16 GB 的峰值约束与完整目标 gate

此前 [真实精度实验](./mineru-original-pdf-precision-validation-20261009.md) 的 A4000 BF16 推理只加载模型时 allocated 为 2,320,355,328 bytes，但代表页最高 VLM allocated 为 12,915,709,952 bytes，整卡采样峰值 16,004 MiB。它们分别是加载、计算与整卡指标，不能相加；更不能据约 2.153 GiB 参数大小或约 67 MiB LoRA 状态就承诺本机训练能装下。推理峰值也不是训练峰值，当前没有本机 forward／backward／optimizer step 的资源收据。

本地 pilot 从单任务、batch1、冻结 vision／aligner、LLM-only LoRA、recompute 开始，按 forward、backward、optimizer step 分阶段保存 allocated／reserved／整卡／host peak 和实际 token。gradient accumulation 可保持有效 batch，但不减少一个完整样本的峰值；recompute 会增加计算时间，收益须实测。模型退出并释放显存后再进行下一路，Bonsai 保持抑制，其他运行服务遵循中央边界。

**完整 target 覆盖是资源 pilot 的前置条件。**计数包含实际图像 token、system／task prompt、完整 assistant 文字／OTSL 和 EOS；逐条保存目标是否完整、OTSL 行／列／空槽／span 与输出终态。不能为塞进 16 GB 而截去后半表、空列、续行、关键编号或否定词，再把短样本成绩冒称原任务通过。超出容量时先改并发、batch、冻结策略、recompute 或已验证 attention；确需降低图像信息量或拆分样本时，单独记录消融和完整表组复原／质量 gate。仍 OOM 则保留为本机当前配方资源失败，继续可完成的参数与接口调试，不将失败训练自动转移到超算迭代。

## 80 GB 训练资源算术参考：不属于当前部署流程

赛方资源仍按用户已给定的单 H800 80 GB、250 GB RAM、27 vCPU、650 GB 高速盘、1,250 GB 普通盘规划。以下保留先前训练容量算术，便于判断参数与 activation 的区别，**不授权或计划在平台开展训练、现场改代码或调模型**；本次没有赛方性能、训练峰值或部署验收。

| 算术项 | 计算依据 | 结果 |
| --- | --- | --- |
| BF16 基础参数 | 1,156,026,624 × 2 bytes | 2,312,053,248 bytes，约 **2.153 GiB** |
| 全参 Adam 持久状态示例 | 每参数 BF16 weight 2＋BF16 grad 2＋FP32 master 4＋两份 FP32 moments 8，共 16 bytes | 18,496,425,984 bytes，约 **17.226 GiB**；实际 optimizer 策略可能不同 |
| LLM 七投影 rank8 LoRA | hidden896、KV128、intermediate4864、24 layers，Σ rank×(in＋out) | **4,399,104** 可训练参数；按相同 16 bytes 假设，额外约 **67.125 MiB**；不含 visual／aligner／embedding／lm_head |
| 未融合 loss 的 logits 示例 | batch1 × 8,192 positions × 151,936 vocabulary × FP32 4 bytes | 4,978,638,848 bytes，约 **4.637 GiB**；只是一个张量示例，是否实际物化取决于实现 |

可复算 JSON：`resource-arithmetic.json`。以上不含 activation、视觉反传、attention workspace、临时 logits 副本、allocator cache、框架、操作系统、数据加载和其他模型；因此**不能将 17.226 GiB 写成全参训练峰值**，也不能将 2.153 GiB 当完整 OCR 运行峰值。此前 A4000 的 eager 推理峰值已经明显大于参数驻留，这属于另一份推理实测，不能搬成 H800 训练峰值。

本次工程起点是本机有界的 LLM-only BF16 LoRA 兼容／资源 pilot，不因平台有 80 GB 就扩大为全参训练。超算使用已冻结模型与代码，按既定整批解析→索引→大 LLM 恢复阶段验证推理显存及切换；原生模型、适配权重、训练环境与部署环境各自有准确身份。没有依据报告本机必训得动或固定训练时长。

## 原始来源与收据

公共来源均保存原始文本、URL／resolved URL、UTC、字节数和 SHA。汇总为 `source-receipts-index.json`；本轮 31 项 fetched、7 项 failed。GitHub unauthenticated API rate-limit 的 403、猜测路径 404 保留，不能用它们证明训练不支持；关键结论来自成功取得的固定源码／模型卡。

| ID | 官方来源／固定版本 | 保存文件与 SHA-256 |
| --- | --- | --- |
| S1 | [2605 Pro 模型卡，固定 revision](https://huggingface.co/opendatalab/MinerU2.5-Pro-2605-1.2B/blob/08aaea840498d49ce16247b6263196cf02814885/README.md) | `mineru-2605-card.md`：`8f829b69be518375b02023f795b3898adec98f5ac37208239884ad88e9a21cb7` |
| S2 | [2605 固定 config](https://huggingface.co/opendatalab/MinerU2.5-Pro-2605-1.2B/blob/08aaea840498d49ce16247b6263196cf02814885/config.json)、[tokenizer_config](https://huggingface.co/opendatalab/MinerU2.5-Pro-2605-1.2B/blob/08aaea840498d49ce16247b6263196cf02814885/tokenizer_config.json)、[preprocessor](https://huggingface.co/opendatalab/MinerU2.5-Pro-2605-1.2B/blob/08aaea840498d49ce16247b6263196cf02814885/preprocessor_config.json)、[固定 API](https://huggingface.co/api/models/opendatalab/MinerU2.5-Pro-2605-1.2B/revision/08aaea840498d49ce16247b6263196cf02814885) | `local-2605-config.json`：`f91cad3c52a3d8f1d420b51459455b27d792db28bf5d1d08097ae96bf1c44730`；其他小配置逐项见 `config-and-vendor-inventory.json`；`mineru-2605-api.json`：`4e09e5d370d7f3631982f94754c3651e0c7345afbeca36b22b316303d2e3aacf` |
| S3 | [官方 2509 SFT 训练营，commit 761b4a98958c151bdb002f0790d9eb1ea87512e9](https://github.com/opendatalab/mineru-tutorials/blob/761b4a98958c151bdb002f0790d9eb1ea87512e9/04%E8%AF%BE%EF%BC%9AMinerU%201.2B%20%E6%A8%A1%E5%9E%8B%E5%BE%AE%E8%B0%83/04%E8%AF%BE%EF%BC%9AMinerU%201.2B%20%E6%A8%A1%E5%9E%8B%E5%BE%AE%E8%B0%83%20-%20%E6%96%87%E6%A1%A3.md) | `mineru-2509-official-sft-tutorial.md`：`4c2e5cdf8bb894c68463d27a743865b4ce60f724d05ba86fa178d29301363201` |
| S4 | [MS-SWIFT 支持表，commit 410a5d18930725d1d3cfe3acdd611eb9795c403b](https://github.com/modelscope/ms-swift/blob/410a5d18930725d1d3cfe3acdd611eb9795c403b/docs/source_en/Instruction/Supported-models-and-datasets.md#multimodal-large-models) | `swift-supported-models.md`：`c5cd3db646321fbf0280b45f36d1097ea1f5cfb2014b0a76e7873b47d13c41ab`；第 779 行明确 2604 |
| S5 | [Qwen2-VL loader／注册](https://github.com/modelscope/ms-swift/blob/410a5d18930725d1d3cfe3acdd611eb9795c403b/swift/model/models/qwen.py#L773)、[template](https://github.com/modelscope/ms-swift/blob/410a5d18930725d1d3cfe3acdd611eb9795c403b/swift/template/templates/qwen.py#L309)、[自定义模型入口](https://github.com/modelscope/ms-swift/blob/410a5d18930725d1d3cfe3acdd611eb9795c403b/docs/source_en/Customization/Custom-model.md) | `swift-qwen-register.py`：`1dbbc8e70b098a4713ad1b3f700afd44b4aae38731c1115e9ebfa309724cf7a6`；template `b84ada099b6e42bac2e693350ddf4a4e0e2184dffb29c1c9f2d2e890a79c0934`；custom-model `efa9b6223b63bab1fce05591798fec6e9c3a11e3804d90be10cbe1b90fa46692` |
| S6 | [MinerU 4.0.10 window 编排](https://github.com/opendatalab/MinerU/blob/c221cc41bc911ad0df3eaeda97acf6a1bfe9bf93/mineru/backend/analysis/pdf/window.py#L400)、[tier 映射](https://github.com/opendatalab/MinerU/blob/c221cc41bc911ad0df3eaeda97acf6a1bfe9bf93/mineru/parser/tier.py#L12)、[小模型 registry](https://github.com/opendatalab/MinerU/blob/c221cc41bc911ad0df3eaeda97acf6a1bfe9bf93/mineru/model/registry.py#L50) | `mineru-release-pdf-window.py`：`bdaaac5672515e92c33d1642d0ccb4ab056f00e14138adcaa2c1f60525754046`；本机 tier／registry 文件收据见 inventory |
| S7 | [mineru-vl-utils 固定 client](https://github.com/opendatalab/mineru-vl-utils/blob/405520b8fe6be61725537984eb4a00d404dc272a/mineru_vl_utils/mineru_client.py#L58)、[OTSL 转换](https://github.com/opendatalab/mineru-vl-utils/blob/405520b8fe6be61725537984eb4a00d404dc272a/mineru_vl_utils/post_process/otsl2html.py#L83) | `mineruvl-pinned-client.py`：`95384dd304dfb382c082e88d4613841d95e21dd7f85fae361876da9d8f76bb10`；OTSL `ea58226087d0a6f57c6c3f9e2c749d1c137ace01a0cb7e1253e6badb44968708` |
| S8 | [MinerU VLM 构造入口](https://github.com/opendatalab/MinerU/blob/c221cc41bc911ad0df3eaeda97acf6a1bfe9bf93/mineru/model/vlm/client.py#L16)、[runtime](https://github.com/opendatalab/MinerU/blob/c221cc41bc911ad0df3eaeda97acf6a1bfe9bf93/mineru/model/vlm/runtime.py) | `mineru-release-vlm-client.py`：`b172baaf6753dc6167acc9a4de8afcdb484ae1db4cdddd860225cf0c57765cd2`；runtime `b71817063bff4b1c51b9b57e236400ce4cbe759f2e299dd2806e7acbe9ef0227` |
| S9 | [MS-SWIFT 多模态／loss 数据契约](https://github.com/modelscope/ms-swift/blob/410a5d18930725d1d3cfe3acdd611eb9795c403b/docs/source_en/Customization/Custom-dataset.md) | `swift-custom-dataset.md`：`459d2a76dd9b78aa0f36f1bccc7458506e6b0eb5f2c1ff4ca7b86868ed750ad8` |
| S10 | [MS-SWIFT training／freeze／LoRA 参数](https://github.com/modelscope/ms-swift/blob/410a5d18930725d1d3cfe3acdd611eb9795c403b/docs/source_en/Instruction/Command-line-parameters.md#tuner-arguments)、[Qwen2-VL 模块前缀](https://github.com/modelscope/ms-swift/blob/410a5d18930725d1d3cfe3acdd611eb9795c403b/swift/model/model_arch.py#L590) | `swift-command-line.md`：`73e14b40910545b73417d9439f6daa0f5d98176be7bdfd56e2ec5720d6bf7d20`；arch `cb4c2e8ee2e2304218deafb9dedca9f3bc4622222e8ebaa67b774d4776ae0c1d` |
| S11 | [MS-SWIFT LoRA merge／保存模型和 processor](https://github.com/modelscope/ms-swift/blob/410a5d18930725d1d3cfe3acdd611eb9795c403b/swift/pipelines/export/merge_lora.py) | `swift-merge-lora.py`：`c96655d49d7d9eb61de8e07b1eff1d0f14b48a63b36287b4947bf5f49720bd97` |
| S12 | [MinerU 4.0.10 官方模型源与 remote VLM 配置](https://github.com/opendatalab/MinerU/blob/c221cc41bc911ad0df3eaeda97acf6a1bfe9bf93/docs/en/usage/model_source.md#remote-vlm-service)、[tiers](https://github.com/opendatalab/MinerU/blob/c221cc41bc911ad0df3eaeda97acf6a1bfe9bf93/docs/en/usage/tiers.md) | `mineru-release-model-source.md`：`55345b631d8e4e63771e6f2f15ad9ac5c84894e5930637c7e8b8271d7dbee366`；tiers `e916f1e9a3c18cc43655a242f0d77369520bf3b1367df23735e20ac52e2f963d` |

本轮结论不依赖泛用 leaderboard 胜负、旧教程设备成绩或当前样板 OCR 候选自评。未执行的兼容／训练／导出／完整页质量 gate 继续保留为未完成。
