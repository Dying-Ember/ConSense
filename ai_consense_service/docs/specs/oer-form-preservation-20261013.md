# D4 原生 PDF 控件保全补丁（2026-10-13）

对应 `oer-vetting-advisory-next-development-20261012.md` D4／AC02 的三个既有软件漏失点。复用 `DocumentBlock.pdfFormField`、`PdfFormField` 和 `VettingCorpus.Part`，没有新增业务 DTO 或原件结构 schema。

- `DocumentParser` 在 native 抽取失败及 OCR 成功后仍保留已观察到的原生控件。OCR 文本保持原返回内容；控件是独立的原件观察，追加顺序不声明视觉读序。同一物理页的待核对页码不重复。
- `VettingCorpus` 不再过滤空白控件，也不把长控件外观拆成丢失字段元数据的普通文本切片。空正文仍为空；不以 `/V` 生成可见文字，不将 blank 改成 No／NA。
- `VettingSourceMaterial` 的已有 `selectedPartExtractionObservations` 携带选定、完整且身份对应的表单观察。正文／完整 offsets、原生来源、block／anchor、物理页、bbox、visibility 和唯一身份必须一致；非空正文只能来自既有、无冲突的文字外观。失败保留 `unknown_part_binding`，不把不对应的字段作为已定位来源。未选字段不进入模型材料。

当前投影版本为 `selected-native-source-material-form-observations-v6`。模型只接收 15 项观察字段：控件身份／名称／类型、完整值与完整可读外观、外观状态、可见态／值状态、物理页、needAppearances、外观是否存在、冲突、stream／glyph 观察状态及 limitations。原始／解码 AP 字节、glyph 坐标、bbox／rectangle／matrix 和 annotation flags 保留在完整来源对象；`pdfFormFieldSourceSha256` 绑定该对象。此指针用于核验已保存的观察完整性，不声明另有历史 CAS 下载 API；原件回取仍按 project／document／parsed hash／原字节 SHA 守卫。

字段摘要有独立的跨语言规范：排序对象键，仅在摘要材料中将每个有限 binary64 浮点数映射为 `{"$binary64":"16位大端IEEE754十六进制"}`，保留整数、字符串、布尔、null、数组顺序和负零，再对紧凑 UTF-8 JSON 计算 SHA256。此编码不修改原件、存储字段或模型观察；非有限浮点拒绝。`foundation_scope_evaluation.py` 采用同一规范并检查 v6 允许字段及 unknown 解释，拒绝丢字段、修改值／外观、raw／坐标注入、摘要不符、旧版本或把 blank 宣称为 NA。其余调用者从同一 Java 投影构建材料；实际序列化输入 SHA 会随投影或完整原流摘要改变，不复用旧请求身份。新实际运行必须重新构建并保存当前 Jar／源码 SHA 与 recipe，旧运行证据保持其原软件身份。

v5 是保留的历史补丁结果：完整 PdfFormField 曾进入模型材料，违反主规格 D6。独立 415,064 字符 AP 反例的旧实际 user 长 420,565 字符；v6 复放为 5,477 字符，raw／decoded stream 均未搬运；另一个 27,020 字符可读外观及正文完整保留。随后独立发现科学计数法使 Java／Python 十进制 JSON 摘要不一致，按上述专用 binary64 规范修复；不修改既有全局 canonical digest。旧失败、旧 v5 收据和源码身份均保留。

本补丁影响共享 PDF 解析输出，包括 Drafting 保存的结构。Drafting 的变量提取仍只使用保存正文并要求来源为 PARSED；生成 PDF 的公开上传测试确认 `/V=No`、既有 `/AP=Yes` 的冲突来源为 PARTIAL，控件保留且提取返回既有 4005，模型未调用、变量未采纳。PDF OCR／DOCX／ZIP 解析与模板源文兼容测试同时通过。匹配的文字外观遵循原有规则；本补丁不改变其法律适用状态。

验证证据保存在忽略的外部目录 `C:/Users/Administrator/Documents/Codex/consense-implementation-20261013/`：

| 验证域 | 实际结果 | 证据 |
|---|---|---|
| 初始窄契约回归 | 62/62，失败／错误／跳过均 0 | `form-preservation-targeted-tests.log` |
| 最终共享 parser／Drafting／model packet 回归 | 144/144，失败／错误／跳过均 0 | `form-preservation-parser-drafting-regression.log` |
| 既有 whole-unit／request／metadata wire 回归 | 65/65，失败／错误／跳过均 0 | `form-preservation-existing-wire-regression.log` |
| 材料来源 evaluator | 23/23 | `form-preservation-material-evaluator-tests.log` |
| 首轮测试失败 | 完整 JSON 字节相同但 lossless decimal wire 的数字节点类型不同；改为比较完整序列化内容，未改生产 wire | `form-preservation-first-test-failure.log` |
| v6 及专用字段摘要核心回归 | 六套件 58/58，失败／错误／跳过均 0；另 metadata wire 16、PDF native 9 均通过 | `vetting-final-independent-01/form-source-sha-final-receipt.json` |
| v6 evaluator 与科学计数规范 | 25/25；Java／Python 固定嵌套科学数样例共同 SHA `714f894e3f33c75c769a04d969b14e005f8e6fc221a588af5984a00f3b30f565` | 同 receipt 及 `form-source-sha-scientific-fixture-normalized.json` |
| 独立 compact 反例及旧摘要 RED | 空 AP 大原流未进模型、可读文字完整；旧十进制摘要差异已复现并保留 | `reviewer-d4-compact-projection-green-02.log`、`reviewer-d4-field-digest-probe.log` |

上述测试集合部分重叠，不相加为总体准确率。v6 核心重放初次因隔离输出目录未创建有一项 error；创建目录后只重跑该六项 source replay，全部通过，原日志／XML 保留。最终 receipt SHA256 为 `602639648e63fb5ac0db0268f22c84ba2643edb542cbe84f410e2f2fb7cfae47`；根集成提交为 `a0a60b2`，全 Maven／当前真实业务验证另见实施状态，不由本表替代。

生成原件包括冲突／匹配文字外观、缺失外观、空白外观，以及实际 PDFBox checkbox 的非文字 `/AP` 和已有 `/V`／`/AS`。v6 中完整可读外观仍进入实际 user excerpt，长原流只经来源摘要绑定；来源原流保持完整且未修改。合成完整 token 观察超预算时拒绝整包，正文与来源原流均未截断。此 token 用例是契约验证，未调用真实 tokenizer／模型。

AC02 整体仍未通过：真实原件表单显著成员分母、扁平／扫描表单、像素外观、父子选择组与法律 OR 关系、独立留出、实际模型及浏览器业务验收未完成。原件、生产服务、活动索引、Local05 均未改动。
