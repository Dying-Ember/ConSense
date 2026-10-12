# MinerU 原始 PDF 的 BF16／FP32 实测

2026-10-09；本机 RTX A4000 16 GB、Windows。实验为历史暴露的开发样本，不是盲测；没有切换在线 OER 配方，没有修改原件。

## 实际覆盖与控制

本轮 BF16 完整解析 14 份真实原始 PDF 的 23 张 300 DPI 完整页，包括 GCC、GCT、2b 的 12 份 PDF 及 SL 技术范围。这里的“完整”指每张选定页输入完整、完整 parser 执行并保存产物，不表示这些 PDF 的全部页或全文已验收。

在新模型输出前，固定 GCC 物理页 16、APB 页 3、Annex 页 28、SL 页 3437 四页作为 FP32 控制。FP32 实际按原 manifest 顺序执行：Annex 28 → APB 3 → GCC 16 → SL 3437；BF16 四页来自完整 23 页 run，页调度、首次模型加载位置和 allocator 缓存不同。没有重复或随机化性能基准，不将四页结果称作全 23 页精度等价。

两次实际执行的 runner 与 adapter 字节完全相同。唯一精度配置差异为模型参数和浮点输入的 `bfloat16`／`float32`；整数 token／grid 保持整数。固定如下：

- MinerU 4.0.10 Standard；辅助模型为 ONNX CPU。
- Raw VLM：`opendatalab/MinerU2.5-Pro-2605-1.2B@08aaea840498d49ce16247b6263196cf02814885`。
- Qwen2-VL 主模型：CUDA eager，TF32 两项关闭，最高上下文／输出预算 8192，实际 cap 受输入长度约束。
- 同一 processor、同一完整 PNG、同一 parser、生成参数、penalty 与保留特殊 token 的解码方法。
- 前台单进程 LocalClient，无 HTTP 服务和实际 HTTP 请求；禁止应用 socket 连接，Python asyncio 私有 wakeup socketpair 单独记录为本机 IPC。
- 原始 PDF、PNG、raw／small 模型均校验 SHA；参考标注未进入模型输入。

RTX A4000 的原生 BF16 支持已实际确认。两次主模型各 1,156,026,624 个参数分别全部位于 `cuda:0/torch.bfloat16`、`cuda:0/torch.float32`；每次调用核验参数与浮点输入精度。

## 执行与结果一致性

| Run | 页数 | 实际 VLM 请求／响应 | 终态 | 整轮秒数 |
| --- | ---: | ---: | --- | ---: |
| MinerU BF16 | 23 | 258／258 | completed，exit 0 | 708.576 |
| MinerU FP32 控制 | 4 | 49／49 | completed，exit 0 | 182.729 |

307 个请求均有完成终态，全部 `finish_reason=stop`。adapter 与 vendor response 逐个相等，没有请求／产物缺口；本轮输入与记录产物复验没有 hash 变化，应用网络尝试为零。

四页控制的 49 个 vendor request 正文连同图像字节完全一致，generation／penalty 参数完全一致。**49 个 response 文本和四页完整 Markdown 字节完全一致。** 这支持在这四种版式上使用 BF16，没有发现降精度引入的新结果变化；相同输出不是正确性证明，也不能据此保证其他文件没有浮点差异。

## 时间与显存

以下每页时间包含该页 parser、识别与产物保存；输出 token 包含 EOS。

| 原始页 | BF16 秒 | FP32 秒 | BF16／FP32 输出 token |
| --- | ---: | ---: | ---: |
| GCC 16 | 25.452 | 27.906 | 760／760 |
| APB 3 | 39.620 | 29.911 | 660／660 |
| Annex 28 | 27.800 | 27.216 | 300／300 |
| SL 3437 | 64.458 | 95.055 | 410／410 |
| 四页合计 | 157.331 | 180.088 | 2,130／2,130 |

BF16 四页合计耗时较少，但 APB 反而更慢，Annex 接近。页序、首次加载和缓存影响没有消除，不能据这些数字宣称固定提速比例。

加载模型后，CUDA allocated 为 BF16 2,320,355,328 bytes、FP32 4,692,473,856 bytes，主模型驻留量明显减少。但完整运行峰值没有同比减半：SL3437 单调用 peak allocated 为 BF16 12,915,709,952、FP32 13,373,454,336 bytes。

BF16 全 23 页整卡采样峰值 16,004 MiB，allocator peak reserved 42,517,659,648 bytes、RSS 30,965,235,712 bytes；FP32 四页整卡采样峰值 15,930 MiB，reserved 21,783,117,824 bytes、RSS 10,162,069,504 bytes。两次运行长度和之前处理的大页不同，reserved 包含已有缓存；整卡、allocated、reserved、RSS 记账不同，不相加，也不能拿这两组峰值证明 BF16 更费显存。

BF16 本轮最高单请求输入 2,041 tokens、输出 369 tokens，全部正常结束。大表慢并非输出预算截断或长输出导致；eager 视觉计算、临时张量、缓存及 Windows 内存压力还需要独立 profiling。BF16 能减少模型驻留量，不能代替 attention 配方及完整页共驻资源验证。

## 原始页质量的关键观察

对照原页与本轮产物，以下缺陷仍需处理，不因精度对照一致而接受：

- APB3 的两条 GENERAL NOTES 和左下合同标识漏读，BF16 与 FP32 均如此；其他三组、`#` Revision 及 HO-04 换行标题得到保留。
- GCC16 的定义续段、1.2–1.5 层级、付款币种例外、汇率调整否定及 1.5(2)(a)/(b) 在两个精度中保持一致。
- SL3437 保留四列、4–7 条各两枚空复选框、1.5m／350m 和否定限制；跨页行 3／7 的内容虽保留为无编号续行，自动关联到前页父行尚未实现。
- GCT1 项目／合同可见值读出，但条款 1 数字漏掉，条款 3 数字被串入左侧边注。只读出正文不足以建立可靠 clause 层级。
- FOT8 保留红色 1,000.00／1,000,000.00 数值，但将有效 20M 与划掉的 25M 并列为普通文字，并把 PRE.B4.070 输出成 PRE.B4；局部编辑状态与引用尚未通过。
- H Part C 28–31 保留编号、重复表头及空 offer 区域，但 p30 的压力单位被错放进首个 Offered Material 列；p31 将 Description 拆成虚拟两列。整表拓扑与字段归属尚未接受。
- SL1329 的流程图图像包含主流程和左侧上传／数据库／导出分支，图内文字未进入最终可检索文本；右侧框线裁切也需要完整区域校验。
- APC19 保存了完整技术图区域图像，但归类为 table，识别文本主要是标题栏，且把 PROJECT 值重复成 DRAWING TITLE。不能据文字输出推断已解析整张图纸。

以上是关键结构观察，不是全文或全部 cell/bbox 准确率；两路选型仍需与同源 Paddle 实测一起判断。

## 可复算证据

本机实验根目录：`.local/state/original-pdf-dual-validation-20261009`，原始证据不随仓库发布：

- [BF16 运行收据](../../../.local/state/original-pdf-dual-validation-20261009/mineru-bf16-run1/receipt.json)
- [BF16 产物／运行审计](../../../.local/state/original-pdf-dual-validation-20261009/mineru-bf16-run1-artifact-audit.json)
- [FP32 控制运行收据](../../../.local/state/original-pdf-dual-validation-20261009/mineru-fp32-control-run1/receipt.json)
- [FP32 产物／运行审计](../../../.local/state/original-pdf-dual-validation-20261009/mineru-fp32-control-run1-artifact-audit.json)
- [逐页精度对照 JSON](../../../.local/state/original-pdf-dual-validation-20261009/mineru-precision-control-comparison.json)
- [逐页对照及差异目录入口](../../../.local/state/original-pdf-dual-validation-20261009/mineru-precision-control-comparison.md)

run 下 `executed-sources` 保存实际源码；`vendor-http`、`vlm-evidence` 与 `vendor-output` 保存请求图、实际 token／收据、完整解析结果和资产。初期 BF16-only 准备稿、被撤回的 case 排序准备稿、原收据分别保留；实际 FP32 使用 BF16 全页 run 对应的封存参数化源码。
