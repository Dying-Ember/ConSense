# GCC／GCT／SL 技术范围与 2b 原始 PDF 双解析实测

日期：2026-10-09，Asia/Shanghai。执行与独立参考审阅由 Codex 完成；`humanConfirmed` 不据此设为 true。本文记录真实原始 PDF 实验，不以 DOCX 派生页实验决定 PDF 选型。两路 23 页 BF16 与每路预先固定四页的 FP32 对照均已结束，原件／输入身份和运行产物已核验；全文 gold、自动跨页关系和在线业务验收尚未完成。

本轮支持 **优先用 BF16 配方建设 Paddle 骨干，并保留 MinerU 完整第二路及表组采用空间**。这批 PDF 的两家优势不同，FoT 删除状态、APL 空槽、APU 编号前缀等共同缺陷仍需恢复／门控。BF16 在固定四页上没有观察到关键内容或表格关系的新恶化；这不是 FP16 通过，也不是全部 PDF 精度等价。

## 范围、输入与证据边界

本轮使用赛方实际原件中的 GCC、GCT、SL 技术部分和 2b 示例 PDF。SL 的活动范围仍是物理页 1127–4300；已排除 SL 非技术库、BQ／General Summary 和非 PRE 的项目工程技术 PSS。NTT／SCT／SCC 未填标准模板只服务 Drafting，不加入 Vetting／Advisory。这些边界见 [当前规则](../oer-scope-and-rules.md)。

正式输入为 **23 个完整原物理页，来自 14 份原始 PDF**：2b 全部 12 份范围内 PDF 的 17 页，加 GCC 的 1 页和 SL 的 5 页。它是有意选取的布局风险样本，不是全部 GCC、全部 2b 的 258 页或全部 SL 技术库的逐页验收。2b 的原生 DOCX 保持 POI／OOXML 主来源，另轮派生验证见 [DOCX 实测](./docx-poi-dual-visual-validation-20261009.md)。

| 样本组 | 原物理页 | 本轮检查重点 |
| --- | --- | --- |
| 2b GCT | 1 | 原生正文、AcroForm 可见项目／合同值、编号与侧边注 |
| 2b Annex 1–6 | 28 | 旋转、九列空表、局部文字方向 |
| 2b Appendix H Part C | 28–31，完整四页组 | 二层表头、Description 与四种 Offered Material、items 1–20、空栏和单位 |
| 2b Appendix I | 2 | 否定、例外、三条 Notes 与正文的归属 |
| 2b FoT | 8–9 | 红色文字、删除线、替换后的有效数字、字段和读序 |
| 2b AoA | 1 | 正文、articles 和原页身份 |
| 2b APA／APC | APA 7；APC 17、19 | 侧向／局部旋转图纸、完整图组、标题和来源定位 |
| 2b APB／APL／APU／APZ | 3／3／3／4 | 清单、空槽、隐藏搜索层、编号前缀和指引 |
| GCC | 16，印刷页 4 | 正文层级、条款编号、否定和例外 |
| SL 技术正文／流程图 | 1133／1329 | clause no、数值单位、限定语、正确完整图与相关性文字 |
| SL ELE checklist | 3436–3438，完整三页组 | Part I 空字段、Part II 9 项与 18 空状态框、row 3／7 续行及 Remarks |

SL3439 只作原件节边界核查，不进入模型；它已转入 ELE37。历史 ID `sl-architectural-table-p1329` 保留用于证据关联，原页实际是 SITE RFID 流程图，不能按该 ID 将其当表格。

两套 parser 接收同一份 300 DPI、CropBox、完整页 PNG。PDF 原件只读；不向视觉模型提供原生抽文、参考标注或另一路答案。Poppler 26.07.0 的字体警告保留，渲染可看不等于每个 glyph 已全文核验。坐标 affine 已记录，所有候选区域的原页映射仍需单独验证。

本机证据根目录：`D:\R&D\ConSense\.local\state\original-pdf-dual-validation-20261009`，位于忽略目录。正式输入 `parser-inputs-v2.json` SHA-256 为 `5a7cef3402da8adea9efe3cf54faa57f9115af7ded42fcacc001e8c8c857192f`。样本、37 个关键内容／布局检查及冻结收据在新模型执行前于 UTC 14:00:18.710940 保存；样本具有历史暴露，不能称新来源盲测。37 个检查不是全文 gold，不计算整体准确率。

冻结文件为 `sample-selection-v2.json`、`reference-layout-checks-v2.json`、`reference-freeze-receipt-v2.json`；原生 PDF 字符／线条／矩形／图片／widget／Tr 证据独立保存。原生搜索层自身可能陈旧或隐藏，不能把直接抽出的字串称为验真正文。

## 实际配方与精度含义

两套 VLM 固定 checkpoint 均以 BF16 存储权重。FP32 对照把这些权重扩为 FP32 计算，不恢复 checkpoint 没有保存的额外权重精度。BF16 与 FP16 不同，本轮没有执行 FP16；支持研究及官方出处见 [精度核查](./oer-fp16-bf16-support-20261009.md)。

| 对象 | 本轮实际配方 |
| --- | --- |
| PaddleOCR-VL-1.6＋PP-DocLayoutV3 | Paddle native GPU，官方模块混合精度：`visual`／`mlp_AR` 保持 FP32，其余语言参数 BF16；layout 配置保持固定 |
| MinerU 4.0.10 Standard | 固定 MinerU2.5-Pro-2605-1.2B raw VLM，全 VLM CUDA BF16；eager attention；辅助 ONNX 小模型 CPU；TF32 关闭 |
| 输入与并发 | 同源完整 PNG，300 DPI，单页顺序、并发 1，不降分辨率、不换 attention、不换小模型 |
| FP32 对照 | 冻结四页：GCC16、APB3、Annex28、SL3437；只改变指定 VLM dtype，其他条件保持 |

Paddle 第一次加载因我们错误地要求所有参数均 BF16，被 runner 门控阻止；0 页识别、23 页 not_started，失败完整保留为 `paddle-bf16-run1`。它是工程门控错误，不能用于比较识别质量。修订 v2 runner 精确核查 vendor 保留名单，生成新的 `paddle-bf16-run2`，不强制将视觉编码器转成 BF16。

Paddle 实际有 491,934,528 个 FP32 参数和 466,654,208 个 BF16 参数，总计 958,588,736；对应参数字节为 2,901,046,528，相比全 FP32 的 3,834,354,944 减少约 24.34%。MinerU 全 VLM 1,156,026,624 个 BF16 参数，参数字节为 2,312,053,248。两者均须将参数字节、allocator allocated／reserved、整卡采样和主机 RSS 分开记录，不相加、不把权重大小当整条链路峰值。

## 完整 BF16 运行与质量结果

两路均已 23／23 页 completed、exit 0 并释放实验 GPU 模型。Paddle 135 个逐页索引的 artifact、MinerU 258 个真实请求／响应／终态与产物 hash 已分别核验。执行 completed 不表示文字或布局正确。每路原始输出、逐页收据、源码和失败记录保留；候选差异以原页判断，不能多数表决或按 confidence 选择真值。

| 同一 23 页原始 PDF | Paddle 官方 mixed BF16 | MinerU 全 VLM BF16 |
| --- | ---: | ---: |
| 整轮含加载／导出时长 | 268.218 秒 | 708.576 秒 |
| 整轮时长／23 页，描述性均值 | 11.66 秒／页 | 30.81 秒／页 |
| 模型加载 | 4.169 秒 | 1.673 秒 |
| allocator allocated high-water | 6,576,616,192 bytes | 12,915,709,952 bytes |
| allocator reserved high-water | 16,083,767,552 bytes | 42,517,659,648 bytes |
| 整卡共享采样最高已用 | 15,963 MiB | 16,004 MiB |
| parser RSS high-water | 4,359,827,456 bytes | 30,965,235,712 bytes |
| 真实 VLM 终态观测 | 未插桩逐请求 token／EOS／finish reason | 258／258 stop；max 输出含 EOS 369 tokens |

上述是本机各自完整实现的实测，Paddle 这轮耗时约为 MinerU 的 0.379 倍；它不是相同 attention／框架的模型算力对照，也不是 H800 吞吐或全文质量排名。Paddle 每页 receipt 的 allocator 数值未 reset，是整轮累计 high-water，不能称各页独立峰值。MinerU eager 大图／大表的显存及主机内存压力明显；本轮没有独立 profiler，不能把具体延迟唯一归因 WDDM、视觉 attention 或小模型。reserved、allocated、整卡值、RSS 不相加。

Paddle `max_new_tokens=8192` 只是配置，未保存其 native generate 的逐请求终态，不能由 completed 推断全无截断。MinerU 记录了真实请求输入和终态，258 次都 stop，其本轮慢不能解释为大输出或触发输出上限。两路网络尝试均为零，原生抽文／参考均未进入视觉模型。

独立审核逐条覆盖冻结的 37 个检查，提供原件、PNG、candidate JSON pointer 和 SHA；不合并成总准确率。对应本机报告是 `paddle-original-pdf-independent-review.json/md` 和 `mineru-original-pdf-independent-review.json/md`。

| 原页／完整组 | Paddle mixed BF16 | MinerU BF16 | 对业务的影响与采用边界 |
| --- | --- | --- | --- |
| GCT p1 | 1、2、3 和 3(a)i–iv 编号保留，侧注分类／读序及身份字段仍需规范化 | condition 1 编号丢、condition 3 编号进入左侧 See Special 侧注 | 本组优先核验 Paddle；可见 Widget 项目／合同值两家都恢复，但不能拿普通 page text 的空值判缺资料 |
| GCC p16 | 1.2–1.5、子项、否定和汇率例外的关键内容保留 | 同类关键内容保留 | 两路均可提供候选；clause ancestry 需要工程规范化，不称完整 GCC 通过 |
| SL CLA p1133 | 五条编号／标题、0.6／0.7mm 与限定保留 | 相同关键值保留，编号／标题拆成块 | clause no 主层级规范化要保留真实父子项和原拼词，不能只按文本换行切块 |
| Annex p28 | 九列与空行保留，但原 `HK$M` 成 `HKM` | 九列与该单位关键点保留 | 金额单位是实际字符缺陷；角度与 cell 坐标验收仍待完成 |
| Appendix H Part C p28–31 | 保持五列、四种系统空栏、items 1–20，pressure／unit 在 Description 内 | pressure／unit 被放入本来空的 Fresh Water 列，末页伪六列 | 本完整组优先核验 Paddle；四页自动合并／item 子字段关系仍未实现，不逐格拼两家答案 |
| APB p3 | 两条 GENERAL NOTES 找回；原 `COMPLTION` 成 `COMPLIEN`；notes 仍是扁平 text | GN-01／GN-02 整体漏块，主要清单和原拼词保留 | 漏块与拼词分开；本页覆盖优先 Paddle，不能称全文正确或自动纠正原件拼写 |
| SL ELE checklist p3436–3438 | 九项、18 空框文字在，但首末页状态列网格错／合并 | 三页四列关键关系、九项、18 空框及 row 3／7 续行内容保留 | 本完整组偏好 MinerU；两路都没有实际自动跨页 group／续行边。18 个字形命中不能接受错误状态列 |
| FoT p8–9 | 字段行列较好；删除／有效数字仍被当普通备选，PRE.B4.070 尾部错误 | 同样丢删除线／颜色状态；条款尾部错、多栏顺序风险 | 两路都不能直接产出可靠 live／deleted 解释；需原页编辑状态恢复与局部门控，不能交给拼词润色补义务 |
| APL p3 | 三 trade 标题在，全部 19 Roman 空槽漏读 | 同样漏空槽 | 原件空槽须保存字段拓扑；漏读不能当已填或无须处理 |
| APU p3 | SITE/A 和 BL/A 两组前缀都漏；否定／reference only 声明保留 | SITE/A 漏、BL/A 保留；同样保留限制声明 | 重复 ICU-01 缺完整前缀时不能合成确定 drawing ID；隐藏旧 OCR 层不能直接接管原文 |
| APA／APC／SL RFID 图 | 大体是正确图，部分支撑条／边框裁紧或轻微切断；内部相关性文字不足 | 正确图资产存在；APC19 错判 table，只能采用原图；RFID 图边框有轻微裁断、内文未索引 | Q10=A 仍需完整相关边界、实际标题／图内可读文字及父条定位；不要求自动解释箭头，也不能只保存 crop 就宣称可检索 |

由此支持的工程取舍是：**Paddle 作为优先建设的 PDF 解析骨干，MinerU 保留为完整第二路；按区域／完整表组的原件核验采用候选。** 本批中 Paddle 的漏块／编号／空表表现及整体耗时更适合先落地；SL AMR 表组明确保留 MinerU 的优势。该取舍不将扫描双解析改为单解析，不按文件名或本题固定页码选择真值，不把两家共同缺陷放进 canonical 结果。

## 四页 FP32 同输入对照

FP32 只跑预先固定的四页，不重新挑选有利样本。实际执行顺序按原 manifest 为 Annex28→APB3→GCC16→SL3437；BF16 全 23 页中这些页的前序加载／缓存不同。因此逐页时长是描述性对照，不是随机化性能归因。

MinerU 已 4／4 completed、49／49 stop：同 runner／adapter、同 PNG、49 个实际 vendor request body（含图像 bytes）、generation／penalty 设置完全相同；49 个响应文本及四份完整 Markdown **字节完全相同**。这证明这四页的 BF16 没有引入所见文本输出差异，不能扩大为全 23 页／全部新来源精度等价。APB 的漏 GENERAL NOTES 在两精度下相同，不能归因 BF16。

| MinerU 固定原页 | BF16 秒 | FP32 秒 |
| --- | ---: | ---: |
| GCC16 | 25.452 | 27.906 |
| APB3 | 39.620 | 29.911 |
| Annex28 | 27.800 | 27.216 |
| SL3437 | 64.458 | 95.055 |

MinerU 四页识别累计 BF16 157.331／FP32 180.088 秒；FP32 整轮含加载／导出 182.729 秒。加载后 allocated BF16 2,320,355,328／FP32 4,692,473,856 bytes，但 SL 大表 peak allocated 12,915,709,952／13,373,454,336 bytes，完整峰值没有减半。全 23 页 BF16 与四页 FP32 的 reserved／RSS 累积值具有不同页历史，不能拿它们作 dtype 优劣结论。

Paddle FP32 已 4／4 completed、exit 0、0 artifactErrors；全部 958,588,736 个 VLM 参数实际在 gpu0 为 FP32，使用同 v2 runner、checkpoint／版本／merged config／PNG／8192 配方。四页 layout 检测输出完全相同；Annex28 和 SL3437 public JSON 字节完全相同。APB 仅一个表格末行由 BF16 `COMPLIEN` 变 FP32 `COMPLIION`，原件为 `COMPLTION`，两者均错；GCC 只改变 `Works Subject to Excision` 的外引号直／弯表示。该四页未见新增关键数值、否定或行列差异，不能据此推断全 23 页等价。

| Paddle 固定原页 | mixed BF16 秒 | FP32 秒 |
| --- | ---: | ---: |
| GCC16 | 20.728 | 19.985 |
| APB3 | 20.691 | 21.300 |
| Annex28 | 8.822 | 9.440 |
| SL3437 | 10.984 | 10.717 |

Paddle 四页识别累计 mixed BF16 61.225／FP32 61.442 秒，本单轮对照未显示明显整体加速。FP32 整轮含初始化／导出 72.780 秒；其 allocated 累积 high-water 为 8,646,053,632 bytes，而 mixed BF16 全轮 max 为 6,576,616,192 bytes。四页 FP32 与全 23 页 BF16 的输入历史不同，这些最大值不能当相同批次的精度因果实验。已知参数字节差额为约 0.87 GiB／24.34%，不能宣称整卡显存减半或性能翻倍。

本轮没有 FP16 实测，BF16 的结果不能转写成 FP16 等价。当前取舍是采用 checkpoint 支持的 BF16、Paddle 保留官方 FP32 视觉模块，继续对关键单位／编号／表组做门控。FoT／APL 没有同页 FP32 控制，不能从四页结果给它们做 dtype 因果归因。加大输出预算也不能自动恢复未被检测／裁入的条款号或 GENERAL NOTES；MinerU 的真实 stop 证据与检测区域支持将相应漏块作为布局路由问题调查。

## 对实际实现的要求

1. GCC／GCT／SL 可靠可见文字层与版面共同解析，保存字符坐标、条款编号及父子关系。GCT 的 field tree／Widget／appearance 和可见值独立保留；文字层空 alias 不覆盖实际显示值。
2. 扫描区域继续两套完整解析；APU 的不可见旧搜索层留作原始证据，不冒充可靠可见正文。规范结果须带所用 parser、原件／revision、原物理页、区域框和采用依据。
3. 按真实表头、列线、空栏与编号构建完整表组和续行边，分别验证 Appendix H 的五列、SL AMR 的四列等不变量；不凭空框数量接受错位的列，不逐格拼接两路结果。现有输出的平面 blocks 不冒充 clause tree 或跨页 group。
4. FoT 删改态、APL Roman 空槽及 APU 分组前缀作为明确缺口，恢复原件可见证据并加局部门控；拼词润色不能改变有效／删除、金额单位、否定、编号或已知字段角色。未恢复的关系标 unknown，并限制依赖该关系的判断。
5. 先保证原图／表组完整边界、可读方向、标题／相关性文字、所属父条和原页映射，再进行图表检索／返回验收。原图资产已保存与文字可检索、返回资产正确是分开的关卡，Q10=A 不增加箭头语义推断。
6. 独立实验 attention／batch／资源优化时保存新配方，在 H800 再做真实共驻测试。固定来源可预解析、精修、建立版本缓存；当前样本均值不直接线性承诺 3,174 页的总耗时或竞赛吞吐。

本节是由实测支持的后续实现要求，未宣称这些恢复器、跨页边和图像检索接口已部署。

## 可复核证据索引

以下文件均在本机证据根目录，文件中保存准确原件／候选路径、字段、JSON pointer 与 SHA。原件及模型不进入仓库；实验文件位于忽略目录。主报告及规则文档可随代码交付，异机需自行准备资料与新运行证据。

| 证据 | 所支持的事实 |
| --- | --- |
| `sample-selection-v2.json`／`parser-inputs-v2.json`／`reference-freeze-receipt-v2.json` | 23 页／14 原 PDF、17 个 2b 页、SL 范围、源文件／PNG／采样和37个先行检查；非盲测／非全文gold |
| `mineru-bf16-run1/receipt.json`、`mineru-bf16-run1-artifact-audit.json` | 23 页终态、258 请求响应／stop／实际 dtype、708.576 秒、allocator／RSS／整卡采样和 source／artifact SHA |
| `paddle-bf16-run2/receipt.json`、`paddle-bf16-run2-artifact-integrity-audit.json` | 23 页终态、135 indexed artifacts、268.218 秒、实际 mixed 参数及 cumulative allocator／RSS／共享整卡采样；逐请求token未观测 |
| `two-engine-original-pdf-independent-comparison.json/md`、两家的 `*-original-pdf-independent-review.json/md` | 37 项质量对照及完整表组取舍，原件／PNG／candidate pointer和SHA；没有总体准确率 |
| `mineru-fp32-control-run1-artifact-audit.json`、`mineru-precision-control-comparison.json/md`、`mineru-same-engine-precision-independent-review.json/md` | 四页49实际请求、文本及Markdown一致、逐页计时及真实精度；布局缺陷两精度同样存在 |
| `paddle-fp32-control-run1/receipt.json`、`paddle-fp32-control-run1-artifact-integrity-audit.json`、`paddle-bf16-fp32-paired-output-comparison.json`、`paddle-same-engine-precision-independent-review.json/md` | 四页真实FP32、22 indexed artifacts、layout相同、两份public JSON相同、APB拼词／GCC引号差异、计时和参数字节；原页独立复核确认两种拼词均错误 |
| `final-run-comparison-receipt.json` | 四个terminal run 汇总及45项原件／PNG／冻结文件 identity复验一致；不把collector成功算质量通过 |
| `services-after-runs.json` | 前端／Java／中继健康200、原PID／启动时间一致，无Bonsai或实验模型进程；没有WSL单服务专项验收 |

汇总收据 SHA-256：`90ccd68f98a46b9a6f499978698f5e150e3832833e18812a54336a524a60843e`。输入 SHA 及冻结文件的前后核验支持同源对照，不意味着 immutable registry 或第三方人工验真。

## 运行与业务验收边界

本轮不切在线 OER profile，不改业务解析代码、不 push。GPU 模型串行运行、结束退出释放；Bonsai 保持用户要求的抑制，代码与权重保留。前端、Java 后端、MiniMax 中继和其他 WSL 服务保持运行，最终用正确端点 `/api/system/health` 复验 Java；猜测的 `/api/health`／`/actuator/health` 404 保留为探测路径错误，不是服务故障。

完整 PDF 原件结构、全部必要证据召回／同池 rerank、来源映射及前端最终返回的业务验收尚未由本实验完成。首版图表目标仍为 Q10=A：可检索相关文字并返回正确、完整、有来源的原图／表组，不要求自动解释流程箭头。
