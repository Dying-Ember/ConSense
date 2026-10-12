# 表单与 Word 特殊内容的解析技术栈研究

日期：2026-10-12，Asia/Shanghai。只读研究；没有重跑原件、模型、训练、服务或数据库，没有改代码或环境。本报告按用户本次优先级评估原件解析，不把 embedding、RAG、检索或业务模型的问题算作解析漏失。状态以当前源码及保存的有限源核记录为准，不以旧记录推断冻结 05 全库缺陷。

**用户本轮最新边界：图纸文件全部排除，只做身份识别；此次排序第 3 项中的图纸文件已被撤回。** 本报告不研究 CAD／工程图细节，也不为附件图纸保留解析例外。图纸清单和合同 DOCX 内的 OOXML `drawing` 节点不自动等同图纸文件，其范围按已确认身份；下文 Word 图形分支与条件流程仅指仍在范围内的合同／表单内容。

## 结论

**优先增加表单结构与输入覆盖代码引擎，目前没有充分依据再引入独立表单识别模型或新的大型解析框架。** PDFBox 已具备真实 PDF 表单的原生观察，pypdf 能读原生字段，pdfplumber 能提供文字／几何／注释，已有 OCR 和 OpenCV 路线可用于打印／扫描控件。缺口主要是完整区域路由、typed blank/control、选择分支及导出保全，而不是缺另一套读 PDF 包。

“表单”需要至少区分四种来源，不能统一调用 `get_fields()` 后就声称完整：

| 来源形态 | 可复用能力 | 必须补的代码和验证 | 新依赖判断 |
| --- | --- | --- | --- |
| PDF 真正的 AcroForm／Widget | PDFBox 现有 `/V`、所选 `/AP`、`/AS`、位置、可见标记、冲突观察；pypdf fields 与 radio group 路由 | 更完整字段 flags/options/父子组表示，控件外观有效状态与可见源核；不把非文本 AP 当已未勾选 | 现有依赖可做，不需要新表单框架 |
| 已扁平／打印的原生 PDF 表单 | PDFBox／pdfplumber 的文字和矢量位置，Camelot 网格候选 | 字段 label＋空槽／框／下划线＋选择连接的结构恢复；完整页覆盖检查 | Camelot 和既有几何／OpenCV 足以开展验证；无需新增表单模型作为首步 |
| 扫描表单或扫描＋隐藏旧 OCR | 当前 Paddle／MinerU OCR/layout 与原图 | 完整区域入任务、孤立符号／印刷空编号覆盖、checked/unchecked/blank/unknown 分别表示；完整输入内残差再判断识别训练 | 暂无证据要求新增识别家族；需要必要的源监督与现有模型调优 |
| 原生 DOCX 表单、字段、图形和修订 | POI／OOXML 原件与 raw parts、文本各通道、编号、cell topology、已有 LibreOffice 渲染 | 内容控件状态与语义、字段缓存／显示、AlternateContent 分支选择、继承样式／隐藏／修订可见态 | 优先 OOXML 代码和现有渲染核验；目前不需要另加商业 Word 引擎 |

## 已证漏失的原因并不相同

| 保存的源证据 | 已证现象 | 归因与可行处理 |
| --- | --- | --- |
| APL 第 3 页，19 个印刷 Roman 空槽 | 正常任务只覆盖三个 trade heading，19 个槽未进入请求与正常产物 | 输入／布局覆盖缺口。需要完整表单区域、孤立编号与真空槽路由；不能发明承包商姓名，也不能靠新 OCR 模型识别未收到的区域 |
| AoA 第 2 页闭合 `]*`、第 3 页姓名 `#` 与 blank-end comma | 源符号位于实际识别 crop 外或未完整覆盖 | 输入范围先修；源符号是字段／分支结构的一部分，不按噪声裁掉 |
| AoA 跨页 OR | raw/middle/structured 保留 3 个，default Markdown 只有 2 个；第 2 页 OR 被标 `page_number` 后过滤 | 已捕获而导出丢失。代码必须保显著分支连接，不能把所有 footer/page_number 类内容全删 |
| AoA 第 4 页 ▲ | 完整实际输入内可见，识别器未转录 | 这是完整输入内识别残差；可用源监督检验任务／参数及必要训练，不与输入漏失混算 |
| GCT 有效显示名值及空控件 | 有限完整源核记录为 8/8 可见 control 名值、3/3 空控件保留 | 保留已有正确能力；不能由此推广为所有 checkbox/radio/PDF 表单状态已通过 |

证据定位：

- [源解析质量审计](../../.local/state/oer-beyond-borderless-review-20261012/source-quality.md)，行 26–45、79–87。
- [APL 正常输入漏槽归因](../../.local/state/oer-apu-apl-normal-complete-parser-20261011/score-01/major-defects-and-attribution.json)，行 154–182，`/items/3`。
- [AoA 归因](../../.local/state/oer-aoa-complete-baseline-source-score-20261011/review-score-01/residual-attribution.json)，`/sourceInputScopeChecks`、`/trianglePage4`、`/defaultMarkdown`；行 237–243 是 OR 导出差异。
- [GCT 最新保存完整质量事实](../../.local/state/oer-coverage-baseline-report-20261011/gct-fresh-complete-quality-adapter-prepare-01/fresh-complete-quality-facts.json)，`/formalMetrics/sourceVisibleControlNameAndLogicalValueCorrect`、`/formalMetrics/emptyControlsKept`。该文同时明确 humanConfirmed=false、blindHoldoutExamples=0、wholeFixedCorpusGatePassed=false；不是新源泛化或全表单状态验收。

## 当前源码能力与明确缺口

### PDF 原生表单

[PdfNativeSupport.java](../src/main/java/com/consense/document/PdfNativeSupport.java:78) 现有观察直接读取原件 AcroForm 字典，避开 viewer fixup；遍历 field tree/widgets，记录 field type/name/value、NeedAppearances、widget flags/rectangle、`/AS` 和所选现有 normal AP。AP 原始／解码 bytes、glyph 与 text 分别保留，`/V` 与可见 AP 不一致时标 conflict。行 137–146 的值状态不把 missing AP 或非文本 AP 当已验可见值；只观察所选 normal AP，其他状态仍保在原件。

[DocumentParser.java](../src/main/java/com/consense/document/DocumentParser.java:140) 渲染时不根据 `/V` 合成缺失 AP；行 157–172 仅在既有 AP 文本与字段值不冲突时将其加入文字，其他情况保 native metadata 与 review。此能力已经在源码；本轮没有重新运行测试或核在线版本。

当前 [PdfPageEvidence.java](../src/main/java/com/consense/document/PdfPageEvidence.java:41) 的 typed field 只有 value/appearance/type/位置与状态，还没有完整 `/Ff`、`/Opt`、父字段及 button-group members 的专门输出。因此可通过现有 PDFBox 原生对象补这些观察与来源绑定，但不能宣称当前已完整解析 radio/choice。非文本 AP 中的框／勾记需要 typed 状态解释与可见原图对照；`empty_or_non_text_appearance` 不是 `unchecked`。

[OerNativePdfObservation.java](../src/main/java/com/consense/document/OerNativePdfObservation.java:59) 明确 whole-document existing fields/widgets/selected normal AP scope；行 120–128 保 effectiveRenderingStatus=unknown、operator order 非 reading order、AP local coordinates 未转换到 widget page，且未做 raster／OCR。该观察保护原证据，不能代替最终表单显示正确率。

现有 [PdfNativeEvidenceTest.java](../src/test/java/com/consense/document/PdfNativeEvidenceTest.java:106) 的源码覆盖 stored/AP conflict、matching/hidden/missing appearance 和不合成 AP；这是测试定义，本轮未执行。当前有限 fixtures 不能证明所有 checkbox/radio、XFA 或变体显示状态。

### pypdf／pdfplumber 的边界

现场安装 pypdf `_doc_common.py` 的 `get_fields`（行 572–619）明确从 AcroForm tree 提取 interactive fields；没有 AcroForm 返回 None。行 703–726 有 checkbox/radio 的 AP states；行 826–875 将字段定位至 widgets/physical pages。现有项目 [pdf_selected_source.py](../tools/vetting_eval/pdf_selected_source.py:22) 保留完整 page、annotations、继承字段状态且不 flatten；它是来源保全，不是表单理解。

[pypdf 官方表单说明](https://pypdf.readthedocs.io/en/stable/user/forms.html) 解释 root fields 与 page widgets 的双重结构，radio 的父组与各子 widget 不同；flatten 后内容转成普通 PDF content。它也说明 XFA 是独立结构，可影响可见内容。当前样本没有足够 XFA 质量证据，首步应检测并标 unsupported/unknown，不据此增加 XFA 引擎或执行脚本。

[pdfplumber 官方 README](https://github.com/jsvine/pdfplumber/blob/stable/README.md#extracting-form-values) 给出从 pdfminer 的 AcroForm tree 读取字段示例；[本地 page.py](../../.local/state/oer-camelot-isolated-20261012/run-2112-01/.venv/Lib/site-packages/pdfplumber/page.py:273) 的 annots 保 `Rect` 和底层 data。这不是空槽／勾选语义引擎。该库官方也说明更适合 machine-generated PDFs，不自带 OCR，不能用它独立解决扫描表单漏区。本轮已读取这两官方来源，没有下载原件或模型。

### Word 特殊内容

[DocumentParser.java](../src/main/java/com/consense/document/DocumentParser.java:594) 已区别 `del`/`moveFrom`、直接 strike/dstrike 与有效候选文本，并保原文／删文／划线文本。行 627–640 对 AlternateContent 遍历并明确 unresolved，不选择最终显示分支。仅有 OOXML 字面通道不等于继承样式、显示字段和有效修订态正确。

[OerNativeDocxObservation.java](../src/main/java/com/consense/document/OerNativeDocxObservation.java:132) 保所有 raw parts；行 212 专门标 `content_control_semantics_unresolved`。行 244–269 保 direct edit channels 且 effectiveCandidateStatus=unknown；drawings/fields/symbol 等显示解释也保 unknown。这意味着原 XML 留着，typed controls／可见分支仍需新增代码解释和渲染对照，而非再 OCR 整份原生 DOCX。

[当前特殊边界源审](../../.local/state/oer-beyond-borderless-review-20261012/source-quality.md:65) 说明 SCC 三处公式叶文字保留，但分式／下标结构不完整；SCT 11 图形分支等仍 PARTIAL。十个剩余 DOCX 没有 insert/delete/move 实例，不能把不存在的修订算通过；FoT scan 的 strike/delete 仍 unknown。本次不动冻结 Drafting。

## 建议实现顺序与准入

1. **完整输入覆盖。** 表单 route 保完整页／完整字段组；对未入 recognition 任务的文字、框、下划线、孤立编号／选择词形成 missing-region ledger。native 几何与已锁 OpenCV 可做候选 coverage；不能把任意像素 connected component 等同语义字段。
2. **Typed 原件结构。** 明确 field/control ID、label、空槽、checked/unchecked/blank/NA/unknown、branch separator、原页/bbox、原 token/AP、条件关系。native/printed/scanned route 分别记录来源，不用一个空字符串混所有状态。
3. **导出保全。** 每个显著选择词与字段结构能从正常 raw→结构化→可供使用输出逐级回取；过滤前保风险与来源，不一刀切删页脚／噪声类标记。
4. **完整输入内残差再训练。** 用真实源监督测试 ▲、勾记、划线等已有识别链路；不为从未入 crop 的空槽训练，不把训练页 fit 当跨文件泛化。
5. **保旧正确项。** GCT 有界 control、已有 SL checklist18控件训练页成果分别回归；再加入独立表单、真实勾选／未勾选、radio groups、widget `/V`/AP 冲突、打印空槽、扫描选择分支和有修订 DOCX。样例需来源和 route 分母，当前未测项诚实保 unknown。

这套工作增加的是源解析代码与有限必要监督，不需要增加 Agent 框架、向量库或业务 VLM。若真实独立样本证明现有 OCR 对完整输入内 checked/strike 持续失败，再对比分离小型控件分类器／现有模型任务或权重训练；不能先把新模型和 Linux 重依赖纳入今晚必选栈。商业 Word／XFA 处理也只在确实遇到当前 LibreOffice／OOXML 无法忠实显示的必要输入后另立验证。
