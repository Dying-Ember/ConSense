# 原生 DOCX／PDF canonical producer 设计审计

日期：2026-10-10，Asia/Shanghai。本次只读当前源码及已保存运行证据，新增本文与忽略目录的来源清单；没有启动模型、调用 HTTP／数据库、重新解析原件、改变在线 profile 或修改生产代码。本文提出尚未实现的最小 native producer 接口，不把源码能力或历史样本当成整源质量验收。

## 结论与当前缺口

可靠原生 DOCX／PDF 可以复用现有来源身份、不可变 registry、双粒度索引、结构单元解析及资产字节门控；但**当前 sealed canonical v1 是扫描双解析专用，尚无可以诚实投影原生 DOCX 的 producer**。它要求实际双 parser、原 PDF 物理页和已经核验的原页 bbox，Java 两个消费者也硬编码 v1。不能为通过这些门控给 Word 编造物理页／bbox，不能将 POI 重复封装为 Paddle／MinerU 两次运行。

建议增加一个独立的 native observation → 显式采用 → sealed projection 模块，分别提供 POI／OOXML 与 PDFBox native adapter。建议使用明确的 **native canonical v2 分支**，保留现有 scanned v1 的双解析准入条件不变；registry／Java 按 schema 分派验证，再复用共同的来源、版本、scope、完整单元和资产门控。这个版本设计仍是建议，本文没有激活新架构。

现有 `DocumentBlock.originalText` 也不能直接叫“原始逐字文本”：当前 `normalize()` 会压缩 tabs／空格、将 NBSP 变为空格、压缩换行并 trim。最小实现首先需要独立的无损观察／规范化映射；修改 legacy 展示文本不属于本设计的必需动作。

## 范围与证据

本文服从 [当前范围与规则](../oer-scope-and-rules.md) 和 [handoff](../HANDOFF-ocr-embedding-rerank-20261008.md)。SL 仅原物理页 1127–4300；SL 非技术库、BQ／General Summary 和非 PRE 项目工程技术 PSS 排除。2a NTT／SCT／SCC 标准模板只供 Drafting；实际项目版本独立路由。2d／2e 答案保持隔离。表格完整组采用、原件不改、unknown 保留以及 Codex 不设置 `humanConfirmed=true` 继续有效。

本次读取六份历史 POI export，实际统计对象为 `parsedDocument.blocks`。六份字节 SHA 与原 `poi-export-receipt.json` 一致；没有将其重跑或升级成当前 parser 输出。可复算清单在本机忽略目录：

`D:\R&D\ConSense\.local\state\oer-native-design-audit-20261010\native-design-source-ledger.json`

清单 SHA-256：`dc0f7f3808dd4eb09c99fa1593d2bd49ee8c3c23ff5241b52f422c8b29a82d0b`。同目录 `source_ledger.py` 仅读源码字节和既有 JSON，记录当前 13 个源码文件 SHA、原 export receipt SHA 与逐件计数。它不读取模型或评价 gold、不判断文字是否正确。

| 已保存 POI 来源 | Blocks | kind 组成 | 编号记录 | 符号记录 | 历史 parser 状态 |
| --- | ---: | --- | ---: | ---: | --- |
| NTT `source-0cae753f94bac27b` | 73 | 1 paragraph、70 table_row、2 footer | 12 | 0 | PARSED |
| SCT `source-6d7802ea7b094124` | 355 | 140 paragraph、197 table_row、5 header、13 footer | 46 | 1 | PARTIAL |
| SCC `source-8ba80d183f6adf7a` | 793 | 772 paragraph、16 table_row、2 header、3 footer | 14 | 0 | PARSED |
| Marking `source-56dcdea23a87dd49` | 78 | 13 paragraph、63 table_row、1 header、1 footer | 239 | 0 | PARTIAL |
| G302 `source-5047630bf82cbf56` | 272 | 269 paragraph、1 header、2 footer | 0 | 2 | PARTIAL |
| 项目 PRE `source-a4e61064c33ba2b6` | 10,531 | 8,045 paragraph、2,434 table_row、49 header、1 footer、2 footnote | 487 | 26 | PARTIAL |

每份 export 均有 **0 个非空 pageNo、0 个非空 bbox、0 个带 wordCells 字段的 block**，quality 均为 unverified。这个零值来自逐件保存 JSON，不是对当前源码的断言；当前源码已经新增 `wordCells`，六份旧输出不能追认为包含该结构证据。`PARSED` 和 `PARTIAL` 是操作／覆盖状态，不是语义质量标签。

## 可复用能力与需要新增的证明

| 当前接口 | 可复用事实 | 不能据此宣称的事实／最小缺口 |
| --- | --- | --- |
| `DocumentBlock` | 原生路径、kind、样式 ID／name、编号和符号状态、有效／original／deleted／strike channels | 当前 channels 已 normalize；需要原始 OOXML／glyph 观察和变换映射。路径未统一携带准确 package part 与 run anchor。 |
| `DocumentBlock.WordCell` | tableLocation、row／cell／gridColumn、span、merge、原生 cell channels、topologyStatus／limitations | 不等于完整 table inventory；要记录整表行／格子覆盖、真实 nested tree、空格子和 unresolved span。不得把内表 cell 拼进外表 grid。 |
| `PdfPageEvidence`／`PdfNativeSupport` | 原物理页、CropBox／rotation／UserUnit、glyph code／Unicode／font／matrix／alpha／Tr、nativeBlocks、widget 与 existing AP | `operator_visible_unverified` 未核验后画遮挡、颜色对比、optional content、完整 clipping；没有可靠 native table grid／clause tree。 |
| `OerSourceMetadata` | 原文件字节 SHA 与 parsed／canonical hash 分开；身份、版次、role／purpose／module；即使采用 OCR 仍保存 native PDF evidence | 路由正确不证明文本和布局正确；显式 reviewRole 不能被 classifier 偷换，示例／答案／模板权限不能由 producer 放宽。 |
| `source_reference.py` v2 | DOCX native anchor 与 rendition anchor 类型分离；实际证据 SHA-to-bytes 验证；unknown 声明 | 只验证完整性和声明关系，不独立解析原件或验真。现 native part 仅 `word/document.xml`；页眉／页脚／注释等不能猜 partName。 |
| `canonical_projection.py` v1 | seal／revision／chunk／asset identity、显式采用与扫描完整表组规则 | `_project_canonical` 强制 `assert_complete_dual`，Part 固定 `native=False` 与原 PDF geometry；需要 native 分支，不能静默放松 v1。 |
| `oer_source_registry.py` | 不可变快照、当前 module 可见性、资产字节 SHA／长度／magic 与 source／unit 绑定 | 默认 validator 是扫描 v1；需要明确版本分派。EMF／chart XML 原始材料不能冒充支持展示的 PNG／JPEG／WEBP／PDF。 |
| Java `CanonicalEvidenceService`／`OerCanonicalRetrievalClient` | current binding、snapshot seal、asset group、完整 JSON equality、source／revision／scope 和 UTF-16 闭包门控 | 硬编码 v1；Part 及 sourceRegions 必须原页 xyxy，Word null geometry 无法通过。DTO／前端必须清楚显示原生定位与派生定位。 |
| window／structural retrieval | text、blockId、anchor、UTF-16 offsets、来源身份、quality 与完整 closure；算法不依赖 PDF bbox | envelope 必须支持明确 native position／unknown。未审核 heading／caption／续表关系不能当可靠边参与扩展。 |

坐标必须显式转换：`DocumentBlock.bbox` 是 top-left normalized **x,y,width,height**，canonical v1 是 normalized **x1,y1,x2,y2**。同样四个数不代表同一约定。PDF frame 还须携带实际原页、CropBox／rotation／UserUnit 与渲染身份；appearance stream-local glyph 不直接当原页 bbox。

## 最小 producer 接口建议

建议入口为纯投影模块，而不是把读取、模型调用、索引和业务审查塞入同一层：

```text
produce_native(
    native_observation_bundle,
    explicit_adoption,
    source_reference,
    optional_rendition_bundle
) -> sealed_native_projection_v2
```

模块输入只接受明确原件 SHA、实际 parser／源码／依赖／配置身份与完整观察产物；不从固定题目值、文件名或另一路结果猜事实。输出统一来源、单元、资产、quality、scope 和 identity，但定位使用类型化 native／rendition 字段。当前扫描 projector 保持独立的实际双 parser 证据要求；共同 seal／身份组装可以抽出小 helper，扫描验收规则不随 native 入口被改成可选。

1. **观察 adapter。** POI／OOXML adapter 留存原 package parts、原生段／表／cell／run 位置、原始文本 channel、编号／符号与编辑态的证据和局限；PDFBox adapter 留存完整 glyph／widget／AP 操作证据及真实原页 frame。不得仅从当前归一化块倒推原字符。
2. **显式采用和结构。** 制作来源 reference 并声明采用哪些原生区域或完整表组、哪些属性已有证据、哪些仍 unknown。没有可信读序／表头／父条时保留 candidate 或 unknown，不制造 clause no。
3. **派生定位。** 可选 rendition bundle 绑定原 DOCX、实际派生 PDF、renderer／字体／配置、actual PNG／尺寸／页／变换；原生 anchor ↔ rendition 区域关系独立验证。缺少映射不阻止保留原生文字，但不能宣称 Q10 图表返回已完成。
4. **共同投影和门控。** 生成带原始观察 identity 与采纳 identity 的 sealed native v2；统一 registry／index 通过版本化 validator 读入。资产绑定当前 source／revision／unit，SHA／长度／magic、模块隔离及完整组检查延续。

建议 v2 在根部声明 `producerKind=native_docx` 或 `native_pdf`，每 Part 包含 `sourcePosition`；Word 的原定位不用 legacy `pageNo`／`physicalPageNo` alias。原 PDF 则保留真正物理页。概念字段如下，**它不是当前可提交的 wire schema**：

```json
{
  "sourcePosition": {
    "kind": "docx_native",
    "sourceSha256": "<actual original DOCX SHA>",
    "partName": "word/document.xml",
    "bodyElementIndex": 53,
    "nodeKind": "cell",
    "tablePath": [{"tableIndex": 0, "rowIndex": 2, "cellIndex": 0}]
  },
  "originalGeometryStatus": "not_defined_for_native_docx",
  "renditionRelations": [{
    "kind": "docx_rendition_region",
    "renditionSha256": "<actual PDF byte SHA>",
    "renditionPhysicalPageNo": 3,
    "sourceImageSha256": "<actual PNG byte SHA>",
    "mappingStatus": "unknown_pending_reference_alignment"
  }],
  "semanticQuality": "unknown",
  "humanConfirmed": false
}
```

真实实现必须补足经确认的 frame／bbox 与 renderer 字节身份；上例的具体几何保持未定义。header／footer／footnote 不从列表 index 假设 `header1.xml` 等名称。首版未支持的 story 可以保存完整原件观察并局部 unknown，不以错误 main-document anchor 宣称已支持。

同一原生单元的 normalized 索引文本应保留到 raw channel 的变换或明确标记未映射。Java 现有闭包按 UTF-16 offset 验证；raw OOXML run、Unicode code point、PDF glyph cluster 和 normalized UTF-16 offset 必须分开，不能共享一个数字字段冒称位置一致。包含 emoji／astral 字符和 ligature 的关系尤其要独立验证。

## 完整表组与资产规则

DOCX 表组按准确 part＋原生 table path 建 inventory，覆盖全部行、原生 cell、合并槽及嵌套表；原生 XML table 可能用于法律条款排版，不自动解释为业务数据表。nested table 是父 cell 下的子表，outer cell text 已包含 nested 文本时不能又把 nested cells 展平成另一份相同正文索引；保留原始 text 观察、明确索引 projection 的节点范围与去重依据。

当前 `collectWordCells` 的 metadata 可供 inventory 建立，但单个 `table_row` 和简化 `cells` 不证明整表齐全。gridSpan／vMerge／hMerge／gridBefore 无法解析时保留原值和 unknown，不制造 slot／rowSpan。全文字表组可以保存原生位置；只有完整映射的实际 rendition assets 覆盖整表时，才称“完整可视表组可返回”。几页 PDF 都有同名 caption 不足以自动绑定跨页续表。

PDF 有真实物理页，native glyph 不自带正确 cell／grid。可见文字可以作为候选，与视觉定位／表组证据并存；需要视觉替换时明确采用完整区域／完整表组，不能从 POI、原生 PDF、Paddle 和 MinerU 四处逐 cell 拼接。扫描区域继续两套完整解析。

EMF／chart XML／OLE 原资源留原字节和 relationship，不伪装为浏览器可读图。展示使用实际渲染 PNG／PDF，并声明派生及与原件／父条／caption 的关系。没有文字的图／空表也应保留资产和拓扑，不能为通过 `content min_length=1` 发明文字；它们可通过已知标题／相关文字单元关联返回。Q10=A 只要求正确完整来源图／表，不自动推导箭头含义。

## 当前 wire 的 native null／默认值风险

源码检查发现 `server.Chunk.pageNo` 默认为 `None`，`window_runtime.py` 和 `oer_server.py` 均用 `model_dump(exclude_none=True)` 导出，而 Java ranked payload 要与 sealed snapshot JSON **完全相等**。因此若未来 Word projection 显式包含顶层 `pageNo:null`，转换可能删掉该 alias，导致严格 equality 拒绝。

这个风险来自源码推导，本次没有新 HTTP 复现，不能记作已经发生的现场 bug。建议 native Word wire 从一开始省略未定义的 legacy PDF alias，并在明确 typed position／geometry status 字段表达未知／不适用；如果约定允许显式 null，则 wire 层必须精确保留，不可静默剔除。需要在 v2 实施时做实际 Pydantic → index → retrieve → Java roundtrip，再封存 canonical identity；不能为了通过而删掉 quality unknowns 或收窄 snapshot。

## 必需验证与完成条件

下面是实施后应有的契约与原件验证，不表示本次已执行。避免重复证明实现本身；每项均围绕来源保真或真实错误边界。

| 验证 | 最低需要的证据 |
| --- | --- |
| 版本及身份 | 原件／parser／配置／normalize／采用／scope／reference／rendition 任一相关变化产生新 revision／index；旧 v1 严格条件保持，unsupported v2 shapes 拒绝。 |
| 原生／派生定位 | 拒绝 Word 原物理页和伪 bbox；接受准确 native part／path；renderer 与 rendition bytes／PNG／frame 逐一绑定，换原件或重打印使旧映射过期。 |
| 无损文本和编辑态 | tabs、NBSP、空白行、原拼词、编号、符号、deleted／strike channel 与 UTF-16 映射；继承 strike、AlternateContent、控件未解决保持 unknown。 |
| 完整 native 表 | 嵌套表、blank cell、gridSpan／vMerge／hMerge、gridBefore、跨行／跨页视觉组；缺 row／cell 拒绝 complete，未解决 grid 不补造。 |
| 全 story 身份 | main／header／footer／footnote／endnote 的准确 package part；不支持 part 不假称已映射，重复文字不能替代准确 anchor。 |
| PDF native可靠性 | Tr3／zero alpha／crop／rotation／glyph cluster、existing /V 与 /AP 冲突、缺 AP 不再生；operator visible 与视觉原页核验分别统计。 |
| 资产与来源 | 实际 SHA／长度／magic，原件＋revision＋unit＋完整 group；错图、相似 caption、另一 source／旧 revision 均拒绝，原始 unsupported asset 不改媒体类型。 |
| Wire 和闭包 | 实际 Pydantic null／omission／extra fields roundtrip、Java完整 payload／UTF-16／完整 sourceUnit 对照；table/caption/parent/unknown 不被 legacy Chunk 丢失。 |
| 原件质量 | 对真实全文／全 cell／bbox／组标注先独立核对，再看候选；按源／派生家族分开开发和留出，不从 parser 输出构建 gold，不追认历史样板为盲测。 |
| 应用验收 | 真 document ID 的 Java current binding → registry → permitted scope 索引／检索 → 正确原图／表组 → 前端；与 mock、源码、离线契约、H800 发布分别记录。 |

已有原件检查建议首先覆盖：[DOCX 三路实测](./docx-poi-dual-visual-validation-20261009.md) 的 Marking 嵌套表、G302 原生 tabs、EPS-F4 局部 RSE strike／独立 or／双列签名、HOMES 星号与 Wingdings、PRE illustration 和 EMF／chart；以及 [原 PDF 实测](./original-pdf-dual-parser-bf16-validation-20261009.md) 的 GCT widget、隐藏旧搜索层、FoT 删改态、APL 空槽、APU 前缀、Appendix H 和 SL checklist 全组。这是必要边界测试起点，不是接受固定题目取值的恢复器。

源码已有 `DocxCellTopologyTest`、`PdfNativeEvidenceTest` 和 `test_source_reference.py`，本次只读其行为与 source，不重新运行；不能将既有测试通过数写为本次 native producer 验收。可靠 native producer 未实现，整源映射、全量原件质量、真实 Java/索引/前端与 H800 均未由本文完成。

## 与模型／精度选型的关系

本设计不改变已采纳的完整双视觉候选和原生优先原则。原生 DOCX 用 POI／OOXML 主文字加实际 rendition 视觉核对；可靠原生 PDF 用真实可见文字候选加布局／OCR 证据；扫描区域保持 PaddleOCR-VL-1.6＋PP-DocLayoutV3 和 MinerU 4.0.10 Standard 两套完整输出。

当前原 PDF 对照支持优先建设 Paddle 骨干、保留 MinerU 第二路与完整表组优势；例如 Appendix H 更适合先核验 Paddle，SL ELE checklist 表组更适合核验 MinerU。这个取舍不能把原件语义、父条／caption／删改状态和映射缺口归咎于 dtype，也不能用“模型较准”跳过 producer 门控。BF16 与 FP16 有不同数值范围；BF16 已有同源局部对照，FP16 没有本地验收。精度选择详见上述原 PDF 报告和 [支持核查](./oer-fp16-bf16-support-20261009.md)，本文没有新精度实验。

## 可复核源码入口

- `src/main/java/com/consense/document/DocumentBlock.java`：channels、WordCell、PDF evidence 字段。
- `src/main/java/com/consense/document/DocumentParser.java`：DOCX body／story／table 输出与 normalize；PDF visible candidate 和 nativeEvidence 保留。
- `src/main/java/com/consense/document/PdfPageEvidence.java`、`PdfNativeSupport.java`：真实原页／glyph／AP 和明确局限。
- `src/main/java/com/consense/service/sources/OerSourceMetadata.java`：原字节身份、路由与 module metadata。
- `tools/vetting_eval/source_reference.py`：v2 native／rendition 声明、actual bytes 和未知传播。
- `tools/vetting_eval/canonical_projection.py`：当前 scanned v1 的强制双解析及真实几何。
- `tools/vetting_eval/oer_source_registry.py`：不可变 seal 和资产字节读取。
- `src/main/java/com/consense/service/sources/CanonicalEvidenceService.java`、`service/vetting/OerCanonicalRetrievalClient.java`：当前 v1 与 geometry gate／payload equality。
- `tools/vetting_eval/server.py`、`window_runtime.py`、`oer_server.py`：Chunk envelope 与 `exclude_none=True` 行为。

上述路径相对 `ai_consense_service`；逐文件 SHA 见本机 source ledger，避免日后源码更新后把本审计当成新版本现状。完整 JSON wire 的此前独立核查见 [canonical wire 审计](./oer-canonical-wire-contract-audit-20261010.md)，它处理现有 scanned v1 的 sourceUnit 闭包，不构成 native v2 通过。
