# OCR 与嵌入质量结论：2026-10-05

当前已确认的错误能够定位到具体来源和物理页。最新 source44 仍有六份文件存在已确认的未修复 OCR 文字或符号遗漏：SL、AA、APA、APB、APC、APU。FT 与 APL 曾确认出错，已将本次核实的整行修订应用到新快照；这不代表整份文件已通过 OCR 验收。PRE 另有 Word 结构解析问题，不能归因于 OCR。

## 文件与具体问题

| 文件 / documentId | 物理页 | 已确认的问题 | 最新修复状态 |
| --- | --- | --- | --- |
| SL — 26_Specification Library_SL2022_042024_Batch02.pdf / 66 | 1006；另含 1008 等风险页 | 1006 的 Part C(iii) 提供澄清/信息首行未进入保存的 OCR 块；横向表格、checkbox 与流程关系未完成结构核验 | 先前 39 个整块修订及 987 页单位行已应用；上述漏行、部分符号和结构仍待修 |
| AA — 08 - AoA (scan).pdf / 78 | 4 | 注释中的 caret 与实心三角标记遗漏；正文基本保留 | 本轮新发现，未修订 |
| APA — APA_Site Boundary Plan.pdf / 96 | 7 | 旋转标题框 13 个块出错，例如 CHECKED 被识别成 `0-5038D`，SCALE 成 `SOALE`；16 个微小/旋转文本观察仍不确定 | 本轮新发现，未修订 |
| APB — APB_List of Contract Dwg.pdf / 97 | 47 | 四个完整 drawing title 严重乱码；另四块 AND 变成 AÑD | 本轮新发现，未修订 |
| APC — APC_Sketches and Details.pdf / 98 | 19（印刷 APC/18） | 10 行 gondola 尺寸/重量表遗漏；材料及安全操作注释不完整或乱码；三个高置信度 `009` 块实际为 `600` | 本轮新发现，未修订；最高优先级 |
| APU — APU_List of Ref Dwg, Doc.pdf / 100 | 2 | 两条目录项的目标页码 2 遗漏 | 本轮新发现，未修订；页 3–20 的 PDF 原生文本不在这次 OCR 抽查内 |
| FT / 76 | 2 | 原脚注首行的 partnership / unincorporated joint venture / all partners 条件乱码 | 已核实的整行修订已应用到 source44；其他 OCR 页未全面验收 |
| APL / 99 | 3 | 空名录中的 Roman labels 合并成乱码，可能被误当已填写内容 | 三个整块修订已应用到 source42，并保留于 source44；其他页未全面验收 |
| PRE — 25 - Specification - PRE (base tender).docx / 95 | Word 物理页未生成 | 13 项未解析编号、4 项字体符号告警、2 项结构告警 | 属于 Word 解析质量；保留 PARTIAL，不能算 OCR 错误 |

GCC 的封面页和 APZ 的物理第 6 页所看文字/数字没有发现相应错误。APZ 第 6 页逐一核对 63 个保存块，但其目录结构和其他页没有由此通过验收。所有来源的未核实精度、图表关系及人工确认状态继续保留 unknown/unverified；本轮代理视觉核对不是用户人工确认。

## 抽查覆盖和原始证据

此前检查了 15 个风险页，本轮对六份尚未视觉核对的 OCR 文件各追加一个页面，累计 21 个不同物理页。新增六页有 398 个保存块逐行记录，结果是五页确认存在文字/符号错误、一页所看文字未发现错误。选页刻意包含低置信度、旋转、小字及表格，不能用这组数字计算全 147 个声明 OCR 页、全 44 来源的准确率、CER 或 WER。

- [此前 15 页核对记录](C:/Coding/ConSense/tmp/ocr-native-v3-manual-review-20261004/manual_audit.json)属于修订前历史观察。
- [新增六页逐块、原图与实际 Part 定位](C:/Coding/ConSense/tmp/vetting_foundation_hardening/ocr-six-source-spotcheck-20261004/audit_manifest.json)绑定当前 source44 快照、原 PDF、PNG、block SHA/bbox、UTF-16 范围及 chunk IDs。
- [新增六页可读说明](C:/Coding/ConSense/tmp/vetting_foundation_hardening/ocr-six-source-spotcheck-20261004/README.md)。
- [APC 原图局部](C:/Coding/ConSense/tmp/vetting_foundation_hardening/ocr-six-source-spotcheck-20261004/pages/APC_P19_notes_table_upright.png)与[保存 OCR 行](C:/Coding/ConSense/tmp/vetting_foundation_hardening/ocr-six-source-spotcheck-20261004/pages/APC_P19_lines.txt)可直接对照。

新增错误尚未写进任何原件或源快照。缺失表格/页码/标记不能追加到不对应的旧块 bbox，也不能伪装为已经恢复 native cells。后续需要对图纸方向、分辨率、文字区域检测及表格区域做受控 OCR 对照，并分别记录原文忠实度和结构关系；不能只看 OCR confidence。

## APC 第 19 页实际 OCR 配方对照

在上述原图核对之后，使用同一套本地 RapidOCR 3.9.2 / ONNXRuntime CPU 模型实际识别两次，每个配置一次，无识别重试或审查 LLM。A 使用 200 dpi 整页及 max_side=2000；B 使用 400 dpi、记录范围的局部裁剪、顺时针旋转 90 度及 max_side=4000，检测尺寸也由 736 调至 1280。B 是多个参数组合，不能把改善单独归因于 dpi；裁剪范围以外未处理，不能代替整页解析。

| 原图绑定的检查项 | A：200 dpi 整页 | B：400 dpi 旋转局部 |
| --- | --- | --- |
| 10 行 gondola 尺寸/重量配对 | 0 / 10 | 10 / 10，数值及对应行与原图一致 |
| 安全说明第 6 条四行正文 | 后两行缺失或乱码 | 四行完整识别 |
| 三处原图 `600` | 0 / 3；均为 `009` | 2 / 3；另一处仍误识别为 `60.0` |

仍有 `FLQOR`、`DRAWNG` 等文字错误，OCR 阅读顺序交叉混合注释和表格，两次 OCR 当时只输出平面文字行。此次结果没有应用到 source44 或任何 native cells；APC 继续为 needs_review。两次原始 OCR、原图逆映坐标、实际检测/识别输入形状及失败记录已保存；采样工具只跟踪到虚拟环境启动器，真实 OCR 子进程峰值 RSS 仍未知，不能把其约 4 MiB 启动器 RSS 当成 OCR 内存占用。

[两配置真实执行记录](C:/Coding/ConSense/tmp/vetting_foundation_hardening/ocr-apc-bounded-control-20261005/v2/prepared_v1/actual/run_result.json)和[识别结束后的独立原图范围评价](C:/Coding/ConSense/tmp/vetting_foundation_hardening/ocr-apc-bounded-control-20261005/evaluation_only/evaluation.json)支持上述有限结论。可以确认方向及输入预处理在此页是可修复的实质瓶颈，当前结果仍不足以宣布 APC 整页或其他图纸通过。

随后对已有 B 原图和 OCR 行进行了格线结构解析，没有新增 OCR 或神经调用。最新 v4 得到三个扫描表格候选、47 个 cell；吊篮表为 11 行 × 2 列，十组尺寸／重量按原 OCR 行 ID、原页逆变换坐标及同一几何行独立核对，10/10 对应关系得到支持。47 个 cell 的文本与原 OCR 行一致，54 条来源行的 polygon、置信度及主／竞争格覆盖率独立重算一致。[v4 候选](C:/Coding/ConSense/tmp/vetting_foundation_hardening/apc-ruled-table-structure-20261005/v4/candidate_tables.json)与[独立核对结论](C:/Coding/ConSense/tmp/vetting_foundation_hardening/apc-ruled-table-structure-20261005/evaluation_only/independent-v4/README.md)均已保存。

这项改善只恢复候选几何关系。NO／DESCRIPTION 的 OCR 合并行仍未能安全拆开，四个无已分配文字的 cell 保持 unknown；29/54 个文字 polygon 有 padding 或跨边界，原坐标和竞争关系保留。所有候选保持 needs_review、native=false、humanConfirmed=false；本轮已接入检查接口和界面，尚未应用到 source44 或索引，不代表上述 `60.0` 等文字错误已修复，也不代表整页表格恢复完整。检查页实际核对了三表选择、吊篮表 20+2 格分页、末行 `6.5 M` / `901`，以及真实 400 dpi 输入图加载；源数据与候选数据分别展示。

## SL 第 1006 页：尺寸对照恢复漏行，但不能统一调大参数

同一张已有的 200 dpi 整页原图实际进行了两次 CPU OCR：A 使用 max_side=2000 / Det min=736；B 使用 max_side=4000 / Det min=1280。没有重渲染、裁剪或旋转，B 也不是 400 dpi。A 的实际检测张量为 `[1,3,1984,1408]`，B 为 `[1,3,2336,1664]`；两组短边均已超过各自最小尺寸，Det min 没有触发放大。

| 检查项 | A | B |
| --- | --- | --- |
| 原先完全遗漏的 Part C(iii) 第一行正文 | 找回 | 找回 |
| 100 字符已确认正文逐字匹配 | 否，编辑距离 2 | 否，编辑距离 3 |
| `information` / `*NSC` | `infomation` / `"NSC` | `infomation` / `"NSC` |
| 另一条原有 Part B(iii) 长正文 | 保留 | 输出缺失 |
| 最终输出行数 | 51 | 49 |
| 实际识别时长 | 2.583 秒 | 3.092 秒 |
| 实际 worker RSS 采样最大值 | 774.47 MiB | 883.43 MiB |

两组都执行了 51 次分类和 51 次识别。当时的尺寸控制探针没有保存过滤前每行的文字、置信度和框映射，因此该历史试验本身不能确定 B 的漏行阶段，不能只凭调用次数归因；后续逐行诊断的实际结论见下段。微小上标仍未确认。两组均未应用，SL 继续 needs_review，不采用全局 B 参数，也不覆盖现有已核实修订。

历史页面到当前 Source44 的 49 个块身份、顺序与非文本字段相同；block 11、27 是已有明确修订，另行绑定前驱链。两项发生在识别启动前的准备／helper 失败已保存。该尺寸控制系列只有两次实际识别，没有复跑已完成的识别；其后新增的逐行诊断次数单列如下。[逐行结果与资源报告](C:/Coding/ConSense/tmp/vetting_foundation_hardening/ocr-sl1006-bounded-control-20261005/README.md)、[运行终态](C:/Coding/ConSense/tmp/vetting_foundation_hardening/ocr-sl1006-bounded-control-20261005/final_receipt.json)和[独立效果评价](C:/Coding/ConSense/tmp/vetting_foundation_hardening/ocr-sl1006-bounded-control-20261005/v3/evaluation_receipt.json)均已冻结。

新增逐行诊断保持同一 PNG、原 A/B 配方和 text_score=0.5，实际又识别两次：A 已完成识别，但诊断 JSON 的 NumPy 布尔值序列化失败，原始结果未捕获，不能用它证明 A 的逐行阶段；没有重跑 A。修复纯序列化和原始结果先保存后，唯一剩余 B 执行成功。B 实际为 51 个检测框 → 51 次分类 → 51 次识别 → 51 个非空文字 → 49 个最终块，两个行在低于0.5的分数过滤中删除，分类／识别输入及筛选映射核对无异常。

其中 `page-0:det-13` 在原页归一化 y≈0.297–0.312，实际触发180°分类（分数0.9151526，分类阈值0.9），之后识别为乱码（分数0.49389），在0.5文字阈值删除。独立原页 polygon 定位确认它唯一对应当前 Part B(iii) 的 `pdf-page:1006:ocr-line:13`，归一化矩形 IoU=0.9586443955，次名重叠小于0.1；新 B 的全部49条 rawLines 与历史 B 逐字段相同，稳定复现。这证明该正文的直接丢行层是分数过滤，识别输入之前发生了方向变化；未进行禁止旋转的反事实识别，不能宣称方向分类是唯一根因。低分原文只保留为诊断候选，不自动并入可信正文。[独立来源定位](C:/Coding/ConSense/tmp/vetting_foundation_hardening/ocr-sl1006-line-stage-diagnosis-20261005/independent_source_polygon_evaluation_v1.json)、[新增诊断与失败记录](C:/Coding/ConSense/tmp/vetting_foundation_hardening/ocr-sl1006-line-stage-diagnosis-20261005/README.md)及[实际 B 终态](C:/Coding/ConSense/tmp/vetting_foundation_hardening/ocr-sl1006-line-stage-diagnosis-20261005/repair_v3/prepared_v1/actual/run_result.json)保留；诊断新增2次、历史尺寸控制2次分别计算，识别结果未写回来源或索引；本轮将已有A/B与B行级诊断加入只读检查界面。

## SL 第 1006 页最新单变量结果：错误旋转造成正文漏行

随后保持 B 的同一 200 dpi 原图、模型权重、检测参数及 0.5 文字阈值，新增一次实际 CPU OCR，仅传入 `use_cls=False`。51 个检测框、原页 polygon 和分类前裁切的像素 SHA 与上述 B 全部相同；未旋转的 47 行文字及置信度全部不变，变化只出现在之前触发旋转的四行。实际分类 forward 为 0，检测 1、识别 51，识别耗时 3.196 秒；没有重试、审查模型调用或来源/索引修改。

| 同一原页检测位置 | 原 B：方向分类开启 | 本次：仅关闭方向分类 |
| --- | --- | --- |
| Part B(iii) 长正文，det-13 | 乱码，0.49389，低于 0.5 而删除 | 可读条款，0.97343，通过相同门槛进入最终输出 |
| 下一行 `required)`，det-14 | `rias)`，0.54702 | `required)`，0.99167 |
| 另一低分小标签，det-23 | `a)`，0.43076，删除 | 相同文字和分数，仍删除 |
| 最终文字行数 | 49 | 50 |

原图视觉核对支持长正文和续行恢复；仍有 `(iij*` 对应 `(iii)*`、`"NSC` 对应 `*NSC`、`COWI` 对应 `COW/` 等编号、星号和缩写错误。可以确认：对这一页及冻结 B 配方，分类旋转是正文乱码、继而被门槛删除的原因；该局部结果不验证全局关闭分类，也不代表整页字准率合格。实验保持未应用、needs_review、humanConfirmed=false；当前检查页尚未注册这一次新结果。可复现入口为 [run_control.py](C:/Coding/ConSense/tmp/vetting_foundation_hardening/ocr-sl1006-no-classification-20261005/run_control.py)，[真实原始结果](C:/Coding/ConSense/tmp/vetting_foundation_hardening/ocr-sl1006-no-classification-20261005/actual/result.json)和[识别结束后逐行对照](C:/Coding/ConSense/tmp/vetting_foundation_hardening/ocr-sl1006-no-classification-20261005/comparison.json)分别保留。此前四次 SL 识别与本次一次分开计数，未复跑完成的识别。

## 嵌入质量：保存可靠性与语义召回必须分开

当前使用 BGE-M3 嵌入及 BGE-reranker-v2-m3 重排序。最新 source44 已实际完成一次索引：13,464 父片段、13,602 向量窗口，675 个未变化固定来源缓存严格命中，12,927 窗口新编码。全点 fresh-client 读回确认 payload、原生 Part/cells、来源范围与写入值一致，float32 向量逐元素不一致数为 0，已保存父块范围无间隙；旧索引与缓存字节保持。

这些检查证明索引没有再偷偷丢掉已经保存的输入，不能证明原 PDF 全文被 OCR 识别，也不能证明向量语义质量。APC 缺失重量表就是反例：保存的文本本身没有这些行，再准确的嵌入也无法检索出它们。

[source44 实际索引](C:/Coding/ConSense/tmp/vetting_foundation_hardening/source44-native-v3-index-20261004/actual/result.json)和[全点独立读回](C:/Coding/ConSense/tmp/vetting_foundation_hardening/source44-index-readback-20261004/actual_v1/audit_receipt.json)分别绑定真实结果。2026-10-05 已对该索引完成 16 次新的真实检索：embedding 16 次、reranker 175 次 forward 全部完成，缓存索引严格命中，未重新建索引，原索引状态字节保持，Qdrant 正常关闭。审查生成仍为 0；下面的新检索评价使用本轮实际排名，不借 source42 的分数。

## 最新 source44 真实检索中具体不过关的范围

本轮对四个必要范围、两种配方独立检查，共 8 项。SCC（document80）中 SCC22.304 的 (6)、(8) 在两配方的 dense 向量召回中都未完整出现；Marking Scheme（document72）的 Note1 在 primary 向量召回中不完整，coverage 配方完整；PRE（document95）的 AQCC 在两配方向量召回中完整，但在旧策略的同分数逐父片段 top-K 对照中被排除，当前完整结构单元已将其带回。三份文件的这些范围在语料和索引窗口中均完整，不能称作向量数据损坏。

| 本轮真实查询的检查层 | 完整必要范围 / 8 项 |
| --- | --- |
| Dense 向量候选 | 3 / 8 |
| BM25 候选 | 8 / 8 |
| RRF 全部融合候选 | 8 / 8 |
| 送入 reranker 的候选；全部实际 rerank 后候选 | 7 / 8；7 / 8 |
| 同一实际分数按旧逐父块 top-K 截取 | 0 / 8 |
| 新策略实际有分数的种子本身 | 2 / 8 |
| 本轮实际返回种子附带的完整结构单元 | 8 / 8 |

唯一进入 reranker 前已经不完整的项是 primary 配方的 SCC22.304(6)：RRF 全候选完整，但 candidate50 排除了包含前七段的父块。这说明向量召回、候选截取和最终选片各有具体缺口。完整结构单元附件恢复了本组范围，但没有给补充成员伪造排名或分数。上述 8 项是小样本必要范围完整性，不能计算全库语义准确率，也不能视为已通过真实 token 预算、实际送审或合同审查。

[新 source44 实际查询终态](C:/Coding/ConSense/tmp/vetting_foundation_hardening/source44-structural-unit-query16-20261005/final_receipt.json)与[逐阶段独立范围评价](C:/Coding/ConSense/tmp/vetting_foundation_hardening/source44-structural-unit-query16-20261005/evaluation_only/actual_v1/audit.json)保留每项来源哈希、原始段落范围及实际排名。评价结束后没有新增 NN 或查询，也没有向 producer 输入 gold。

随后已实际使用这 16 次新检索结果构造 283 个完整 Input，并完成独立文件核验。两个预算分支各四必要范围 × 两配方均为 8/8，完整必要条款对出现在对应 global Input 的同一个包内；44 来源身份、serializedInput/sourceSnapshot、原生 rows/cells/slices 与选中的原始 Part 保留一致。此次没有借 source42 的分数。[新 Source44 完整 Input 独立核验](C:/Coding/ConSense/tmp/vetting_foundation_hardening/source44-unit-input-independent-review-20261005/README.md)

这证明候选材料运输。本轮没有实际送审：未知预算分支不追加发送，controlled_fit 仅使用合成计数 100，真实 API 计数和生成均为 0；其他 269/283 个规划候选不保证分别包含完整必要条款对，不能把 global 同包结果推广到每个候选。

## 历史 source42 保存分数对照与完整输入核对

source42 的真实 16 条检索中，三个来源的四个必要范围存在明确丢失：document80 `10 - SCC.docx` 的 SCC(6)/(8)，document95 PRE 的 AQCC 范围，document72 `05.1(2) - Marking Scheme (assessment std).docx` 的 Note1。它们在语料和索引窗口中完整，但 dense/BM25/RRF/rerank 与最终 top-K 的取舍未能保留所有必要段落。不能据此断言这三份文件所有向量损坏，也不能只把责任归给 embedding 模型。

对完全相同的已保存真实分数，新的通用选择策略按可靠结构单元去重，再附带同来源、同版本的完整成员，独立评价结果如下：

| 评价层 | 四必要范围 × 两配方的完整项 |
| --- | --- |
| 旧原始 top-K 父片段 | 0 / 8 |
| 新策略实际有分数的种子本身 | 2 / 8 |
| 新种子附带的完整来源结构单元 | 8 / 8 |

[独立范围评价](C:/Coding/ConSense/tmp/vetting_foundation_hardening/structural-unit-independent-range-review-20261004/actual_v1/audit.json)重新核对了真实种子原排名/分数、全部附带成员的原始完整 payload 及 source/hash/role。该结果是 source42 保存分数的确定性选片对照，新增 NN、检索、API、索引及审查生成均为 0；不代表新 source44 的实测召回率，也不代表这八项已进入真实 reviewer 输入、通过 token 预算或被模型正确审查。

随后对该 source42 对照实际构造的 283 个完整 Input 进行了独立文件审核：两个预算分支中，各四必要范围 × 两配方均为 8/8，且完整必要条款对出现在对应 global Input 的同一包内。所有 sourceSnapshot 与 serializedInput 身份、原生 rows/cells/slices 传递核对通过。其余规划候选不保证单包完整；未知预算保留 pending，controlled_fit 只使用合成计数 100，不代表真实 API 可容纳。该证明仍属于 source42 输入运输，不能替代新 source44 的完整 Input 验证。[完整输入独立审核](C:/Coding/ConSense/tmp/vetting_foundation_hardening/source-unit-input-independent-review-20261005/README.md)

当前可确认：OCR 对旋转图纸、小字号和表格区域存在实质失效；排序后逐父块 top-K 与条件材料运输也有实质缺口。嵌入模型的全量语义质量、完整审查准确率仍待评估。优先修原始输入，再验证结构单元选择及完整输入运输，能够避免把数据丢失误诊为 reviewer 模型能力不足。

## 历史 v2 检查接口的实际证明（2026-10-05，已由本轮诊断版本更新）

历史 v2 检查后端使用 8089、PID 34280，绑定 Source44 索引和本轮 16 条真实新检索。已保存的 [v2 实际 API 核验](C:/Coding/ConSense/tmp/vetting_inspection_runtime_source44_ocr_units_20261005_v2/actual_api_verification_v1/receipt.json) SHA 为 `f51a7e6cc2e38e2f6b4f73e638b914139543e41e1aa8b9a253bb4be8e2456a6e`。86 次只读 GET 检查通过：84 次正常资源返回 200，另两次错页资源／未知资源按预期返回 404；不是 86 次都返回了内容。

接口已逐项对照保存正本：7 个 OCR 页、447 个保存块、2 个尚未应用的 APC OCR 配置结果、8 个唯一 PNG；16 个检索任务、176 个真实有分数种子、1,245 个附带结构单元成员，以及 27 个未能确定结构单元的条目。补充成员保留完整来源材料，不伪造排名或 rerank 分数；未知条目保留零成员和 unknown。全部实际送审阶段仍为 `unavailable`，不能把可展示材料当成已送审。

这里证明的是 API 返回数据、原图资源与现有保存证据一致，不是 OCR、检索语义或合同审查质量通过，也不代替浏览器逐项核验。SL 第 1006 页的两组实际控制结果目前只写入本报告和独立试验记录，尚未加入检查界面；界面中的 SL 仍是保存 OCR、缺行风险与原图。


## 最新检查接口与浏览器核对（2026-10-05）

当前为 v4 检查后端 8089 / PID32312；前端仍为5177。七页447个保存块、四组既有真实实验、SL B 51/49/2行阶段记录及APC三表47个候选格与冻结正本一致。实验总数与SL页实验摘要的三处旧值已修正，新sidecar独评95/95通过，旧失败保留。原source44/索引没有写入；所有试验结果继续未应用、未人工确认、needs_review。

[最新API回执](C:/Coding/ConSense/tmp/vetting_foundation_hardening/ocr-inspection-diagnostics-20261005/actual_api_v5/receipt.json)明确区分当前v4页面GET与沿用的旧图片/越界响应。v4实际只读GET共11次，其中七个页面详情；没有把历史图片或404响应算作新请求。[终盘修复记录](C:/Coding/ConSense/tmp/vetting_foundation_hardening/ocr-inspection-diagnostics-20261005/summary_fix_final_receipt.json)保留了原始摘要错误及验证器错误复用旧SL响应的失败，修复后只补取剩余页面，没有重跑OCR或Maven。

实际浏览器已核对Source44数据选择、SL1006两组实验与B过滤前两条低分行、APC19三表选择及22格的20+2分页。400dpi实验图实际加载3634×2560；SL目标行明确显示180°分类、识别0.49389、0.5分数门槛及未进入最终OCR。低分候选和扫描候选不被提升为可信源文或Word原生cells。[SL实际页面截图](C:/Coding/ConSense/tmp/vetting_foundation_hardening/source44-actual-ui-20261005/sl1006-stage-filter-v4-20261005.png)与[APC末行截图](C:/Coding/ConSense/tmp/vetting_foundation_hardening/source44-actual-ui-20261005/apc19-table22-tail-20261005.png)可直接查看。

可复用探针与严格OCR投影已加入vetting专用扩展接口，文字／分数／框基数不一致会明确失败，表格几何失败不丢掉已识别正文。29个无模型Python检查通过；最新Java完整构建872登记／871执行／0失败错误／1既有跳过。原server.py身份保留，扩展接口尚未用新的真实OCR权重调用验证；这些软件检查不作为OCR精度或审查质量通过证明。

固定NTT/SCT/SCC/GCC/GCT/SL的Source44 v7薄包已完成，两个随机目录各10,724次严格缓存命中／0miss、float32字节一致。[薄包交付说明](C:/Coding/ConSense/tmp/vetting_foundation_hardening/fixed-portable-source44-v7-20261005/DELIVERY_README_zh.md)保留本机验证范围；Linux/H800及实际Java材料集成尚未验收。

下一步验收仍是当前44份来源的实际Service/H2保存、复核、JSON/Word/PDF导出闭环；真实reviewer token预算和最终合同审查质量尚未通过。当前Goal保持active。不能把此轮检查界面、候选恢复和机械一致性当作整包vetting已完成。


## 最新44来源保存、人工状态与导出闭环终态（2026-10-05）

此节更新上节“下一步验收”的时间点。当前872软件、Source44和16新检索已实际完成一次Service/H2/MVC保存/人工状态更新/JSON-Word-PDF导出业务。4个受控generation Input独评核对通过，主题2 global完整同包AQCC＋Note1，主题6 global完整同包SCC(6)九段＋SCC(8)；357唯一父块与808 native-row、10 slice、5重建行观察均保持当前canonical身份。Open→Handled、备注、行动及addendumRequired=false在记录/报告中一致，不被当作人类语义确认。[实际闭环独评](C:/Coding/ConSense/tmp/vetting_foundation_hardening/source44-service-save-export-20261005-v1/independent/README.md)

剩余16 pending均明确保留：主题2为8个非唯一clause heading，主题6为8个未解起始来源关系；另外主题1/3/4/5缺本轮查询，分别not_submitted。不是旧20,000字符上限拦截。62计数和4响应仍为受控软件fixture，0真实MiniMax/审查模型调用，因此真实provider容量和审查准确率继续unknown。

原进程在业务断言完成后因临时driver清理顺序错误exit1，该失败保留；最小实际生命周期回归复现旧错误并证明afterTestClass→close修正，exit0且没有重跑Service。可复现入口已实际ValidateOnly通过，完整业务重放仅提供未重复执行。[入口及资源边界](C:/Coding/ConSense/tmp/vetting_foundation_hardening/source44-service-save-export-20261005-v1/REPLAY.md)

前后两份原生PDF各12页已渲染并目视核对24页，没有观察到裁切或重叠。Word文字/引用身份通过，视觉分页unknown：LibreOffice缺失，独立只读Word转换失败/超时均保留，原用户Word实例未改动。[视觉追加观察](C:/Coding/ConSense/tmp/vetting_foundation_hardening/foundation-final-checkpoint-20261005/visual_readback_post_observation.json)

按原五项条件，基础链路在本机冻结范围已完成；上述六份局部OCR错误、全量OCR/CER、未标注检索语义、真实模型准确率、Linux/H800及Word视觉不被改记为通过。后续MiniMax实测再单独评价最终vetting质量，本节的软件fixture不进入合同正确率。[逐项范围及统一交付](C:/Coding/ConSense/tmp/vetting_foundation_hardening/foundation-final-checkpoint-20261005/criteria_acceptance.json)
