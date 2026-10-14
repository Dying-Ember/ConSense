# OER 的 FP32／BF16／FP16 支持核查

核查日期：2026-10-09，Asia/Shanghai。状态：只读官方资料、固定 checkpoint 配置与 safetensors header、本机已安装源码；本核查没有加载模型、执行推理、停止或切换服务。真实 BF16 质量／耗时／峰值验收另存运行收据，不从本报告的“支持”追认为“实测通过”。

规范入口是 [OER 已确认范围与执行规则](../oer-scope-and-rules.md) 与 [handoff](../HANDOFF-ocr-embedding-rerank-20261008.md)。用户明确要求选型重点回到**原始 PDF：GCC、GCT、SL 技术范围及 2b 实际提供的原文件**。此前 DOCX 派生页实验只服务原生与视觉交叉验证，不能据其 11 页质量或耗时宣布 PDF 主候选胜负。标准模板仍仅供 Drafting，范围排除项不因本精度实验改变。

## 可直接支持的结论

**建议首先验证 BF16，而不是将 FP16 当作所有“16 位精度”的统称。** 两个固定 VLM checkpoint 的配置都声明 BF16；直接读取 safetensors header 还确认两者的全部浮点权重实际以 BF16 存储。官方 Paddle 示例明确采用 BF16，MinerU 示例采用 `dtype="auto"`，固定模型配置对应 BF16。上轮 FP32 主要是把已有 BF16 权重扩成 FP32 进行计算，并不会恢复 checkpoint 原本没有保存的额外权重精度。[S1–S4，L1–L4]

BF16 与 FP32 的计算舍入仍不同，不能由上述事实推断所有输出相同。生成时很接近的候选 token、区域框、读序或后处理阈值可能因数值差异改变；本报告没有量化该变化的大小。关键数字、单位、否定、`or`／`and`、条款编号、星号注释、公式、表格行列关系仍需同输入逐项比较。FP32 也不修复漏检、漏裁块、输出拓扑压平等结构问题。

**硬件前提成立：** `nvidia-smi --query-gpu=name,compute_cap` 返回 `NVIDIA RTX A4000, 8.6`。Paddle 本机源码的 GPU BF16 判据为 compute capability ≥ 8 且 CUDA 主版本 ≥ 11；本机 Paddle 为 3.3.1、既有 GPU 配方 CUDA 12.9。Torch 本机 `is_bf16_supported` 对 GPU major ≥ 8 判定原生 BF16 支持。该判据和 NVIDIA Ampere 文档支持安排实验，不能替代 Windows 上整条 parser 的真实成功收据。[S5，L5–L7]

## 两套 VLM 的明确支持与当前实现

| 对象 | 直接证据 | 能够得出的结论 | 尚不能得出的结论 |
| --- | --- | --- | --- |
| PaddleOCR-VL-1.6，revision `c5630abae1d940eafe0697512a0325494b02ab42` | 官方卡 Transformers 示例 `torch_dtype=torch.bfloat16`；固定 `config.json` 第 39／68 行；全部 620 个权重 tensor 为 BF16 | BF16 是上游直接给出的推理路径，不需要更换或低比特量化 checkpoint | 本机完整 BF16 pipeline 已通过；全部输出与 FP32 等价；所有模块自动变 BF16 |
| 本机 PaddleX 3.6.0 动态 VLM loader | `doc_vlm/predictor.py` 第 63–68 行：支持 BF16 时选 `bfloat16`，否则 `float32`；`misc.py` 第 27–35 行调用框架支持检测 | 上轮实验主动禁用 BF16 helper 才建立 FP32 基线；恢复 vendor auto 是可考察的 BF16 配方 | 原生 loader 明确将 FP16 作为自动首选；关闭 FP32 实验控制就足以证明实际运行 dtype |
| PP-DocLayoutV3，revision `241f8bdfc77a7c7bee915a5057aaee58c235a8d3` | 独立静态 runner 第 388–394 行：只有 `run_mode=paddle_fp16` 才选择 `PrecisionType.Half`，否则 FP32 | layout 精度控制与 VLM 分开；首轮 BF16 对照可以保持 layout 配置不变 | VLM BF16 会连带把 layout 检测器变 BF16；layout FP16 的召回／读序质量已经相同 |
| MinerU2.5-Pro-2605-1.2B，revision `08aaea840498d49ce16247b6263196cf02814885` | 官方卡示例 `dtype="auto"`；固定 `config.json` 第 8／38／101 行为 BF16；全部 681 个权重 tensor 为 BF16 | BF16 是固定 raw VLM 的模型原生存储及 auto 推理方向 | 其他 GGUF 量化产线、Advanced tier 或新 checkpoint 也等价；完整 Standard 已验证 BF16 |
| 本机 MinerU 4.0.10／mineru-vl-utils 2.0.5 | `transformers_loading.py` 第 26 行 `dtype="auto"`；实际实验 adapter 第 47–48 行强制 `torch.float32`＋`eager` | 当前实验从官方 auto 路径显式改为 FP32；BF16 可先只更改 VLM dtype，保持 Standard、小模型和输入不变 | 把 `dtype=auto` 当作已执行；把不同 Transformers／attention engine 的收益全部归因 BF16 |

本机两套 VLM 的 safetensors header 无模型张量加载核验见 `D:\R&D\ConSense\.local\state\oer-precision-support-20261009\local-precision-support-receipt.json`。该收据保存配置 SHA、header SHA、dtype／参数／payload 计数及当前已装相关源码 SHA。[L1]

## 显存与性能可以预期什么

16 位 tensor 的存储字节为同形状 FP32 的一半。以下仅按固定 checkpoint header 的张量形状计算**参数 payload**，不含 allocator、KV、activation、attention、图像预处理、小模型、框架及其他进程。[L1]

| 固定 VLM | 参数数 | BF16 参数 payload | 同形状 FP32 参数 payload | 理论参数差额 |
| --- | ---: | ---: | ---: | ---: |
| PaddleOCR-VL-1.6 | 958,588,736 | 1,917,177,472 bytes | 3,834,354,944 bytes | 1,917,177,472 bytes，约 1.79 GiB |
| MinerU2.5-Pro-2605-1.2B | 1,156,026,624 | 2,312,053,248 bytes | 4,624,106,496 bytes | 2,312,053,248 bytes，约 2.15 GiB |

BF16 还可能减少部分激活、KV 和临时 tensor，并利用 Ampere Tensor Core；但数值敏感操作可能保持 FP32，layout／ONNX 不会随 VLM dtype 全部改变，整卡峰值也含其他进程。因此不能把上述参数差额换成“整条 pipeline 显存必减半”，也不能保证速度翻倍。[S5，L8–L9；工程推论]

BF16 保留与 FP32 相同的 8 位指数范围，尾数精度较低；FP16 的动态范围更窄。Torch 官方本机源码明确提醒 BF16 预训练模型可能因动态范围差异与 AMP／FP16 不兼容；这一段原文属于训练说明，不用它宣称当前两个模型 FP16 推理必失败，但足以支持**先用 checkpoint 原生 BF16，FP16 单列为后续兼容性实验**。[S5，L8]

MinerU 官方卡的 **2.12 fps** 是单 A100、`vllm-async-engine` 并发 VLM 配方。它不是本机 A4000、Standard＋ONNX CPU、Windows Transformers FP32／eager 的整页吞吐。Paddle 的通用 OmniDocBench 排名也不是我们合同字段或 PDF 结构准确率。官方资源／benchmark 只作为模型能力与优化方向的证据，不能做异配方主选比较。[S1–S2]

Paddle 官方元素识别示例还展示 BF16＋`flash_attention_2` 作为减内存／加速方向；它不代表本机 Windows 动态 Paddle 路线已安装 FlashAttention 或已能复现。若同时换 BF16、attention、batch、分辨率，必须另记配方，不能称纯精度对照。[S1，L9]

## 原始 PDF 的验证控制与主候选选择

第一轮只改变 VLM FP32→BF16，其余 checkpoint、完整 parser、tier、输入 PNG、布局／小模型、DPI、解码、输出预算和并发保持固定，保存真实 parameter dtype 与请求／终态／资源日志。两套 parser 继续保留完整原始输出和分歧；不能用模型自评或置信度判正确。

验证以实际 GCC、GCT、SL 已纳入技术范围及 2b 原始 PDF 为准：可靠可见原生文字、AcroForm／widget／appearance、隐藏搜索层与真实扫描分别记录；不是统一把所有 PDF 当纯扫描。固定来源和新增项目稿分角色，标准模板不混进 Vetting／Advisory。对比须覆盖正文及条件例外、父子 clause、编号、脚注、多栏、页眉、表格 cell/span、完整图表组及跨页关系，字串正确与结构正确分开验收。

先问每一路在这些 PDF 上是否完整、可定位、是否漏关键内容；再评同样质量条件下的耗时和资源。DOCX 派生页能提示风险类型，但不能替代原始 PDF 的选型证据。**本支持核查不指定 Paddle 或 MinerU 为全局主选，也不修改全面双解析、完整表组接受和 POI／可靠原生文字的职责。**

## 来源与本地证据定位

公开页面的固定版本文本／HTML、字节 hash 和获取失败均保存在 `D:\R&D\ConSense\.local\state\oer-precision-support-20261009\official-source-receipt.json`。公开抓取没有读取密钥。部分一般文档 URL 返回跳转或 404，未作为有效技术正文引用；不得把 HTTP 可获取本身算成资料核实。

- **S1**：[PaddleOCR-VL-1.6 官方模型卡，固定 revision](https://huggingface.co/PaddlePaddle/PaddleOCR-VL-1.6/blob/c5630abae1d940eafe0697512a0325494b02ab42/README.md)，本地 `paddle-model-card.txt` 第 206／234–242 行为 BF16／FlashAttention 示例；第 53–57 行的榜单属于通用评测。
- **S2**：[MinerU2.5-Pro-2605-1.2B 官方模型卡，固定 revision](https://huggingface.co/opendatalab/MinerU2.5-Pro-2605-1.2B/blob/08aaea840498d49ce16247b6263196cf02814885/README.md)，本地 `mineru-model-card.txt` 第 115 行为 A100 并发速度，第 132–135 行为 dtype auto 示例。
- **S3**：[Paddle 固定配置](https://huggingface.co/PaddlePaddle/PaddleOCR-VL-1.6/blob/c5630abae1d940eafe0697512a0325494b02ab42/config.json)，声明 `torch_dtype=bfloat16`。
- **S4**：[MinerU 固定配置](https://huggingface.co/opendatalab/MinerU2.5-Pro-2605-1.2B/blob/08aaea840498d49ce16247b6263196cf02814885/config.json)，主／text／vision 均声明 `dtype=bfloat16`。
- **S5**：[NVIDIA Ampere Tuning Guide，Tensor Core](https://docs.nvidia.com/cuda/ampere-tuning-guide/index.html#improved-tensor-core-operations)，本地 `nvidia-ampere-tuning.html` 第 230／268–270 行说明 BF16 指数范围、支持的类型与累加器；该实时文档记录了抓取 hash，不是固定发布版本。
- **L1**：`D:\R&D\ConSense\.local\state\oer-precision-support-20261009\local-precision-support-receipt.json` 与同目录 `inspect-precision-support.py`，只读取 header／配置／源码及 nvidia-smi 描述；没有模型推理。
- **L2**：`D:\R&D\ConSense\.local\oer\venvs\paddle-gpu\Lib\site-packages\paddlex\inference\models\doc_vlm\predictor.py` 第 63–68 行；PaddleX 3.6.0；同环境 `inference\utils\misc.py` 第 27–35 行。
- **L3**：`D:\R&D\ConSense\.local\state\docx-triple-validation-20261009\paddle-gpu-fp32-run1\runner-executed-source.py` 第 230–233 行，显式禁用 BF16 检测建立 FP32 实验。
- **L4**：`D:\R&D\ConSense\.local\oer\venvs\mineru-gpu\Lib\site-packages\mineru_vl_utils\transformers_loading.py` 第 26 行；mineru-vl-utils 2.0.5；实际实验强制 FP32 见 `D:\R&D\ConSense\.local\state\docx-triple-validation-20261009\mineru-gpu-run2\executed-sources\mineru-gpu-raw-adapter.py` 第 41–49 行。
- **L5**：`D:\R&D\ConSense\.local\oer\venvs\paddle-gpu\Lib\site-packages\paddle\amp\auto_cast.py` 第 184–199 行；Paddle 3.3.1；框架公开支持查询 [paddle.amp.is_bfloat16_supported](https://www.paddlepaddle.org.cn/documentation/docs/en/api/paddle/amp/is_bfloat16_supported_en.html)。
- **L6**：`D:\R&D\ConSense\.local\oer\venvs\mineru-gpu\Lib\site-packages\torch\cuda\__init__.py` 第 183–202 行；Torch 2.11.0+cu128，native BF16 硬件判据。
- **L7**：L1 的 `gpuQuery` 为本轮 nvidia-smi 当前读取；CUDA／框架与此前实际执行身份见 `D:\R&D\ConSense\.local\state\docx-triple-validation-20261009\paddle-gpu-fp32-run1\receipt.json`、`mineru-gpu-run2\receipt.json`。
- **L8**：`D:\R&D\ConSense\.local\oer\venvs\mineru-gpu\Lib\site-packages\torch\amp\grad_scaler.py` 第 501–507 行，BF16 预训练模型与 FP16 动态范围提醒，属于训练说明；Paddle VLM 也显式将部分方差／attention／logits 计算保留 FP32，见 `paddlex\inference\models\doc_vlm\modeling\paddleocr_vl\_ernie.py` 第 304／870／1441 行。
- **L9**：`D:\R&D\ConSense\.local\oer\venvs\paddle-gpu\Lib\site-packages\paddlex\inference\models\runners\paddle_static\runner.py` 第 388–394 行，layout 独立 FP16 控制；`D:\R&D\ConSense\.local\oer\venvs\mineru-gpu\Lib\site-packages\transformers\modeling_utils.py` 第 2449–2453 行，Transformers 4.57.6 的 FlashAttention2 类型要求，第 4981–4989 行说明 BF16→FP16 需要保留部分 FP32 模块。

本核查仅新增研究文档和忽略目录下的采集／header 检查证据，未修改在线精度或 parser 业务代码。Bonsai 保持抑制，其他服务保持运行。

## 后续真实加载发现：Paddle native 的 BF16 是模块级混合精度

2026-10-09 22:02 +08，用户新要求的原始 PDF 实验首次真实加载了 Paddle native BF16。模型成功加载到 GPU，但实验 runner 的“所有 VLM 参数必须 BF16”门控过严而停止；23 个页面均 `not_started`，不能称 BF16 解析完成或质量通过。实际参数 tensor 为 **459 FP32＋111 BF16**，总参数仍为 **958,588,736**，全部在 `gpu:0`。失败和执行源码保留在 `D:\R&D\ConSense\.local\state\original-pdf-dual-validation-20261009\paddle-bf16-run1\`。

进一步只读核查发现这是 vendor 明确规定的正常混合策略，而非全模型静默退回 FP32：

- `D:\R&D\ConSense\.local\oer\venvs\paddle-gpu\Lib\site-packages\paddlex\inference\models\doc_vlm\modeling\paddleocr_vl\_paddleocr_vl.py` 第 68–70 行明确 `_keep_in_fp32_modules = ["visual", "mlp_AR"]`；注释说明视觉编码器保持 FP32 以改善视觉处理精度，并避免 ROCm／MIOpen BF16 conv 问题。
- 同环境 `paddlex\inference\models\common\transformers\transformers\model_utils.py` 第 381–390 行，在 BF16／FP16 模型加载时将名单内参数保留 FP32，其他参数转到请求 dtype。

因此，**Paddle native BF16 配方应解释为“语言部分 BF16、视觉编码器／projector FP32”**；不要强制把视觉模块 cast 为 BF16，也不要继续以“每个 VLM parameter 都是 BF16”作为是否发生 fallback 的判据。后续应逐个保存参数的稳定模块名、shape、dtype、device 和 vendor 保留名单，检查名单内 FP32、名单外 BF16，以及源配置身份；FP32 对照仍须所有参数 FP32。修订的门控和重新执行须生成独立 runner／run／工程收据，不能改写首次失败。

上文“checkpoint 的存储全部是 BF16”和参数 payload 的数学计算仍成立，但**native Paddle 运行时实际保留了大量 FP32 模块**，所以完整 native 推理不会达到表中“所有参数都 BF16”那一理论字节量。支持查询、权重存储和实际推理模块 dtype 三者须分别报告。该发现没有任何已完成页面的数值／识别质量对照，不用于证明 FP32 与 BF16 输出等价。

## 后续原始 PDF 实跑与固定四页精度对照

本节是前述支持核查后的真实执行结果。总体样本、两套 parser 的原件质量和选型见 [原始 PDF 主报告](./original-pdf-dual-parser-bf16-validation-20261009.md)，MinerU 的精度／请求／资源细节见 [MinerU 原始 PDF 精度实测](./mineru-original-pdf-precision-validation-20261009.md)。**本轮跑的是 BF16／FP32，未执行 FP16；不能将 BF16 结果换名成 FP16 质量或兼容性证明。**

Paddle 官方 native mixed BF16 的 run2 已完成 23 个原始 PDF 代表页，0 导出错误，`exit 0`，总 268.218 秒；FP32 控制完成预先选定的四页，0 导出错误，`exit 0`，总 72.780 秒。两轮均已退出释放模型，保留 run1 错误门控造成的 0 页失败。独立字节复验分别检查 37／8 个唯一原件＋PNG、135／22 个页内 indexed artifacts，以及实际 runner、vendor 源码和 named parameter 证据，均无 SHA／bytes 分歧。这是执行与证据完整性，不是全部原文正确或前端验收通过。

两轮使用相同 checkpoint、runner SHA、框架版本、merged pipeline 配置、固定 PNG 和 max_new_tokens=8192，layout detector 保持不变。官方 mixed BF16 实际为 **491,934,528 个 FP32 参数元素＋466,654,208 个 BF16 参数元素**；FP32 控制则全部 **958,588,736** 个参数元素为 FP32。根据完整 named shape／dtype 计算，参数 payload 为 **2,901,046,528／3,834,354,944 bytes**，实际差额 **933,308,416 bytes，约 0.87 GiB，占 FP32 参数 payload 的 24.34%**。native 路线视觉／projector 保留 FP32，所以没有获得“模型运行参数全部减半”的效果。以上仅为参数字节，不包含 activation、KV、workspace、allocator 或其他进程。[实跑 named evidence 与配对收据]

| 固定原物理页 | Paddle mixed BF16 秒 | Paddle FP32 秒 | 两轮公开产物差异 |
| --- | ---: | ---: | --- |
| Annex 28 | 8.822 | 9.440 | Public JSON 字节 SHA 完全相同；原有 HK$M→HKM 错误也相同，不能因一致就接受 |
| APB 3 | 20.691 | 21.300 | 一个 table 的末行字词 `COMPLIEN`→`COMPLIION`；原页是 `COMPLTION`，两者都未保真；表格拓扑相同 |
| GCC 16 | 20.728 | 19.985 | 一个定义段的外引号由直引号变弯引号；定义字词相同 |
| SL 3437 | 10.984 | 10.717 | Public JSON 字节 SHA 完全相同 |
| 四页页阶段合计 | 61.225 | 61.442 | 未显示明显稳定加速收益；四页 layout_det_res 完全一致 |

这四页没有观察到新增关键数值、否定或行列拓扑差异，但只能支持该固定四页的局部结论，不能证明 23 页、SL 全技术库或新的比赛原件都精度等价。BF16 四页处于完整 23 页的不同位置，FP32 仅跑四页，首次加载、warmup 与 allocator 历史不同；未重复／随机化，不宣称固定提速比例。Paddle 和 MinerU 使用不同 VLM、组件和执行链路，跨模型快慢或识别差异不能解释为 BF16 与 FP32 的精度收益。

MinerU 的同源实验表明 BF16 **加载后的模型 allocated 约减半**，但 SL3437 大表／长图调用峰值没有同比减半；完整页计算的 activation／eager attention／缓存与 Windows 内存压力仍需独立 profiling。具体 numel、token 与逐请求 allocator 数值由上述 MinerU 专项报告及其原始收据维护，不能把 Paddle native 的 24.34% 参数收益套给 MinerU，或反过来将 MinerU 的模型加载收益套给 Paddle。

Paddle 的 native generate 没有插桩逐请求输入／输出 token、EOS 或 finish_reason。因此本轮**未观测每个 VLM 调用是否因 EOS 结束，也未证明没有触及 8192-token 预算**；页状态 completed 和短最终文本不替代这些证据。Paddle allocator 未按页 reset，页收据中的 allocated／reserved 是整轮累积 high-water；整卡采样是与其他服务共享的快照，每两秒采样可能漏瞬时峰值，既不是 parser 唯一分配，也不能把 BF16 全 23 页峰值与 FP32 四页峰值当精度唯一因果比较。

本机可复算证据（忽略目录，不随仓库发布原件／模型／运行输出）：

- [Paddle mixed BF16 运行收据](../../../.local/state/original-pdf-dual-validation-20261009/paddle-bf16-run2/receipt.json) 与 [独立产物审计](../../../.local/state/original-pdf-dual-validation-20261009/paddle-bf16-run2-artifact-integrity-audit.json)。
- [Paddle FP32 四页运行收据](../../../.local/state/original-pdf-dual-validation-20261009/paddle-fp32-control-run1/receipt.json) 与 [独立产物审计](../../../.local/state/original-pdf-dual-validation-20261009/paddle-fp32-control-run1-artifact-integrity-audit.json)。
- [四页 public JSON／final blocks／table topology 与参数字节配对](../../../.local/state/original-pdf-dual-validation-20261009/paddle-bf16-fp32-paired-output-comparison.json)，这是输出差异和计时记录，不是 gold 全文准确率。
- [BF16 完整 named 参数](../../../.local/state/original-pdf-dual-validation-20261009/paddle-bf16-run2/actual-vlm-named-parameter-precision.json) 与 [FP32 完整 named 参数](../../../.local/state/original-pdf-dual-validation-20261009/paddle-fp32-control-run1/actual-vlm-named-parameter-precision.json)。
- [BF16 退出及显存后验](../../../.local/state/original-pdf-dual-validation-20261009/paddle-bf16-run2-post-exit-receipt.json) 与 [FP32 退出及显存后验](../../../.local/state/original-pdf-dual-validation-20261009/paddle-fp32-control-run1-post-exit-receipt.json)。
