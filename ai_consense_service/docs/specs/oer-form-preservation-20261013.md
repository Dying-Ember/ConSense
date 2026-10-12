# D4 原生 PDF 控件保全补丁（2026-10-13）

对应 `oer-vetting-advisory-next-development-20261012.md` D4／AC02 的三个既有软件漏失点。复用 `DocumentBlock.pdfFormField`、`PdfFormField` 和 `VettingCorpus.Part`，没有新增业务 DTO 或原件结构 schema。

- `DocumentParser` 在 native 抽取失败及 OCR 成功后仍保留已观察到的原生控件。OCR 文本保持原返回内容；控件是独立的原件观察，追加顺序不声明视觉读序。同一物理页的待核对页码不重复。
- `VettingCorpus` 不再过滤空白控件，也不把长控件外观拆成丢失字段元数据的普通文本切片。空正文仍为空；不以 `/V` 生成可见文字，不将 blank 改成 No／NA。
- `VettingSourceMaterial` 的已有 `selectedPartExtractionObservations` 携带选定、完整且身份对应的 `PdfFormField` 副本。正文／完整 offsets、原生来源、block／anchor、物理页、bbox、visibility 和唯一身份必须一致；非空正文只能来自既有、无冲突的文字外观。失败保留 `unknown_part_binding`，不把不对应的字段作为已定位来源。未选字段不进入模型材料。

投影版本为 `selected-native-source-material-form-observations-v5`。正文、空白、`/V`、选定 `/AP` 字节与字形、`/AS`、空／非文字外观和冲突均沿用已有字段声明。`foundation_scope_evaluation.py` 同步检查 v5 完整字段及 unknown 解释，拒绝丢字段、修改值／外观／原流、混用 v4 或把 blank 宣称为 NA。其余调用者从同一 Java 投影构建材料；实际序列化输入 SHA 会随投影和原流改变，不复用旧请求身份。历史 v4 无表单证据仍可读取，但不赋予本补丁的保全结论。新实际运行必须重新构建并保存当前 Jar／源码 SHA 与 recipe，旧运行证据保持其原软件身份。

本补丁影响共享 PDF 解析输出，包括 Drafting 保存的结构。Drafting 的变量提取仍只使用保存正文并要求来源为 PARSED；生成 PDF 的公开上传测试确认 `/V=No`、既有 `/AP=Yes` 的冲突来源为 PARTIAL，控件保留且提取返回既有 4005，模型未调用、变量未采纳。PDF OCR／DOCX／ZIP 解析与模板源文兼容测试同时通过。匹配的文字外观遵循原有规则；本补丁不改变其法律适用状态。

验证证据保存在忽略的外部目录 `C:/Users/Administrator/Documents/Codex/consense-implementation-20261013/`：

| 验证域 | 实际结果 | 证据 |
|---|---|---|
| 初始窄契约回归 | 62/62，失败／错误／跳过均 0 | `form-preservation-targeted-tests.log` |
| 最终共享 parser／Drafting／model packet 回归 | 144/144，失败／错误／跳过均 0 | `form-preservation-parser-drafting-regression.log` |
| 既有 whole-unit／request／metadata wire 回归 | 65/65，失败／错误／跳过均 0 | `form-preservation-existing-wire-regression.log` |
| 材料来源 evaluator | 23/23 | `form-preservation-material-evaluator-tests.log` |
| 首轮测试失败 | 完整 JSON 字节相同但 lossless decimal wire 的数字节点类型不同；改为比较完整序列化内容，未改生产 wire | `form-preservation-first-test-failure.log` |

生成原件包括冲突／匹配文字外观、缺失外观、空白外观，以及实际 PDFBox checkbox 的非文字 `/AP` 和已有 `/V`／`/AS`。长原流完整进入实际 user excerpt 和输入 SHA；合成完整 token 观察超预算时拒绝整包，输入与原流均未截断。此 token 用例是契约验证，未调用真实 tokenizer／模型。

AC02 整体仍未通过：真实原件表单显著成员分母、扁平／扫描表单、像素外观、父子选择组与法律 OR 关系、独立留出、实际模型及浏览器业务验收未完成。原件、生产服务、活动索引、Local05 均未改动。
