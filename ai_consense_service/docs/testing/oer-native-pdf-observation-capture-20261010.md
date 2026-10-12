# 实际原 PDF 的 PDFBox 原生 observation capture

更新：2026-10-10，Asia/Shanghai。本记录仅为 native PDF seam 1 的 Java capture、复制 checkout 测试和实际原件封存；遵循 [OER 范围规则](../oer-scope-and-rules.md) 与 [实施交接](../HANDOFF-ocr-embedding-rerank-20261008.md)。原件、完整 raw JSON、工件、编译日志和 CAS 留在忽略的本地目录。它不表示已完成联合 canonical producer、业务采用、真实检索、官方题目、报告评分或前端验收。

## 当前实现与接口

新增 `src/main/java/com/consense/document/OerNativePdfObservation.java`，通过实际 PDFBox `PDFTextStripper.processTextPosition` 观察指定原件物理页的 operators，不调用 `DocumentParser.parsePdf` 的规范化／渲染／OCR 路线，也不从 `DocumentBlock` 反造 glyph。独立 schema 为 `consense.native-pdf-observation.v1`，`producerKind=native_pdf`、observer version 为 `pdfbox-native-observation-1`；不借用 `native_docx` 及其位置能力。

输入必须提供实际原 PDF bytes、明确递增且无重复的 1-based physical pages，以及三个实际 adapter 源码 bytes：`OerNativePdfObservation.java`、`PdfNativeSupport.java`、`PdfPageEvidence.java`。null、零、越界、重复、倒序和空页选择受控拒绝。standalone 参数为：

```text
com.consense.document.OerNativePdfObservation original.pdf fresh-output.json 65,66,67,68,69,70 actual-observer-java-source-directory
```

输出文件用 `CREATE_NEW`。该入口不 fetch 网络资源、不渲染、不运行 OCR／LLM、不建立规范化 blocks、lines、clauses、table topology 或 canonical adoption。PDFBox 对非嵌入字体的本机 font mapper／替代 metrics 可能依赖当前环境；保存实际 font mapper class、FontBox package version（缺少时为 unknown），并保留此限制，不能把原 font name 等同已验证的实际字体形状。

主要字段：

| 字段 | 实际含义 |
| --- | --- |
| `source`、`sourceArtifact` | 原 PDF 的文件名、SHA、byteCount、pageCount、source revision；artifact 保存原文件完整实际 bytesBase64，未裁页、改写或 flatten。 |
| `observerIdentity` | 三个 actual source SHA map、实际 PDFBox 2.0.31／Jackson／Java／FontBox 及 font mapper、配置与 config SHA。编译版及源码一致性另由 frozen run receipt 和 compiled class SHA 绑定。 |
| `selectedPhysicalPages`、`invocation` | native page text 只观察选定物理页；forms 是全文件 existing fields／widgets／selected normal AP inventory。未选页的 form 放 `documentFormFieldsOutsideSelection`；未确定页的 form 放 `unassignedFormFields`，不制造 page 0 的业务物理页。 |
| `pageObservations[].frame` | PDFBox effective MediaBox／CropBox `[x,y,width,height]`、Rotate、UserUnit，原 dictionary 保留在原 bytes；raw default user space 未提前应用 UserUnit。两个派生 affine mapping 分别到 rotated crop normalized top-left 及 72-point physical crop。它们标为 derived、not raster verified，没有实际 raster／DPI provenance。 |
| `nativePdfEvidence.glyphs` | 实际 Unicode／characterCodes、font name／subtype／embedded、PDFBox position／font size／text state、text matrix、CTM、fill／stroke alpha/color、rendering mode、clip bounding rectangle及已有 normalized bbox。glyph 原始 Unicode 包括 hidden／zero-alpha／outside-crop，没有按 native eligible 候选删掉。Jackson 实际字段名称包含 `xscale`／`yscale`。 |
| `glyphOrderChannel` | `observedGlyphUnicodeAll` 字面 Unicode，`UTF16` start-inclusive/end-exclusive spans逐 glyph对应；cluster、空 Unicode／null Unicode的 zero span保留。operator capture order不冒充 reading order，也不插入猜测的空格或换行。 |
| `formFields` | stored `/V` 与 selected existing normal `/AP` 值、bytes、glyph channel各自保留。raw／decoded AP stream bytes与Bbox／Matrix不等同实际widget page mapping；AP glyph `bbox=null`、stream-local coordinate未知。其它 AP state、rollover/down在原 bytes保留但未解释。 |
| `lineRelations`／`quality` | line relation明确 `not_observed`；effective visibility、rendering、semantic/legal scope、applicability未验证；`humanConfirmed=false`、`semanticScopeVerified=false`、`canonicalAdoption=not_performed`。 |

glyph 的 `operator_visible_unverified` 或 `eligibleForNativeText=true` 只是该 operator／alpha／crop 下的候选属性。clip rectangle不等同 exact glyph/clip intersection；hidden text、后画遮挡、optional content、对比度、font mapping及有效 displayed text不能靠 native capture认定。

原件、config／observer revision公式分别为：

```text
sourceSha256 = SHA256(actual original PDF bytes)
configSha256 = SHA256(sorted compact UTF8 JSON config)
observerIdentitySha256 = SHA256(sorted compact UTF8 JSON observerIdentity)
sourceRevisionSha256 = SHA256("consense.native-pdf-source-revision.v1\n" + sourceSha256 + "\n" + observerIdentitySha256)
```

选页也属于 observer config，选页／源码／实际依赖改变产生新的 observation revision；相同文字不自动转移旧 mapping/adoption。

## 最终代码验证与失败保留

最终冻结 checkout：`.local/state/oer-native-pdf-observer-20261010/run-044811-0303f8/`。整个 `src/main` 与 pom复制后在新目录编译；在线 `ai_consense_service/target`、Java、前端、DB、中继和 WSL未修改。

本次 Maven **14 tests，0 failure／error／skip**：新 `OerNativePdfObservationTest` 6项与旧 `PdfNativeEvidenceTest` 8项。新 focused cases覆盖原 literal／hidden／zero alpha／outcrop glyph与style/hash/unknown、crop/0/90/180/270 rotation/UserUnit派生坐标、stored V/AP conflict/缺 AP/未确定 page、严格选页和完整源码绑定、UTF16 cluster/non-BMP/空及null glyph，以及实际 malformed AP先捕文字后失败的保留／拒采用。

malformed AP最后一项使用 authored原PDF，合法 `Retained` text operators之后出现真实坏 `ID` token。保存已捕 raw glyph／AP stream／stored V，并设 `appearanceGlyphObservationStatus=partial_existing_normal_AP_operator_failure`、`valueStatus=appearance_observation_partial_unknown`、top `partial_capture_preserved`。同一case实际运行既有 `DocumentParser`：保留正常 body，拒绝把 partial AP文本加入 effective output，stored V/AP值独立保留、needsReviewPages含该页、无 OCR调用。正常 existing AP及旧 form behavior的8项回归仍通过。

保存的失败与被取代快照：

- `run-043842-ce3c5b`：最初13项通过及原实际导出，尚缺本机font边界精确化；其旧 observation identity继续是旧mapping的准确历史身份。
- `run-044238-2ccaa9`：compile失败；错误调用 `FontMappers.get()`，实际jar `javap`核为 `instance()` 后修复。日志和receipt未覆盖。
- `run-044337-8dbb76`：font边界精确化后13项通过及实际export；**尚无partial AP effective gate收紧**，不能当当前源码14项结果。
- `run-044622-f235e3`：14项中1项失败；authored未闭合string/array被PDFBox宽容接受，没有触发预期异常。随后以实际parser可抛错的坏ID token验证；并非把异常数据升级为完整观察。
- `run-044811-0303f8`：当前最终14项通过。之后本轮capture代码冻结；下一接缝的 projection/CAS/gate/consumer验收独立记录。

最终 three-source SHA：

| 源码 | SHA256 |
| --- | --- |
| `OerNativePdfObservation.java` | `6aacbeb537de21f5a2a502db2f089527c5ff197368aed57911fadb20d9642a61` |
| `PdfNativeSupport.java` | `237128a82981de1e9433956c54627a753b30e921cefad44d6b0a1c933f93ecb5` |
| `PdfPageEvidence.java` | `98133e6b7768a7361e732e6b544c6eae2d20b2ca4bf39f2c9984a024312e5353` |

最终 `validation-receipt.json` SHA为 `cd53d62866297925e6933ebc23b991203cf16be4c1b3ef585fc175468f9facc4`；其中两个XML report、log及三个adapter／两个test SHA均可冷读复算，`workspaceEqualsCopy=true`。它不声称全仓测试或上线。

## 实际 GCC 65–70页封存

最终导出：`.local/state/oer-native-pdf-observer-20261010/run-044811-0303f8/actual-exports-044845-898584/`。`export-receipt.json` SHA为 `81ac6d7576366d95855c6a35095d0f207e53b1492706e9c3c574efa5caabb56c`，绑定 frozen源码、实际 compiled class bytes、输入／输出／日志 SHA、页范围和before/after source hash。

原件是 `Ref/2026-09-04_Documents to Shortlisted Candidates (Contract Advisory)/2a_Standard documents/09_General Conditions of Contract.pdf`，共74物理页；完整原件 SHA `d7d74db6d9aee390093b9e22bb30dccdbf3c0316d47635922109422278d161e7` 导出前后相同。选65–70，未修改、重存、裁原件；其它68页text未观察。原 PDF的实际 invocation为 `encrypted=true`、`extractionPermission=false`；该访问事实原值保留，空密码下PDFBox实际观察出的operators不能等同可见文字、语义正确或采用许可。

`gcc-physical65-70-native-pdf-observation.json` 是实际 Java输出，**40,626,503 bytes**、SHA `b7a52b13b6764b0e3df2ac0be996595679c08930d9636e8d891d6da3c845d007`、source revision `4fc695edd06c98062cb556dca830b9c2d417a50a796878f195fae0a41a2d0cd8`。保留全source／glyph/style/unknown造成raw JSON较大；不套16MiB public-wire cap删字段，也不提高其它接口cap。下游由实际CAS身份保存／引用这些bytes，public transport另验。

| 原物理页 | glyph count | 全literal UTF16长度 | existing form count |
| --- | ---: | ---: | ---: |
| 65 | 4,053 | 4,053 | 0 |
| 66 | 4,138 | 4,138 | 0 |
| 67 | 4,666 | 4,666 | 0 |
| 68 | 4,527 | 4,527 | 0 |
| 69 | 4,046 | 4,046 | 0 |
| 70 | 2,832 | 2,832 | 0 |
| 合计 | 24,262 | 24,262 | 0 |

全部24,262项目前只是 `operator_visible_unverified`，effective visibility仍 unknown。数量来自最终raw JSON及 `offline-export-audit.json`，不表示24,262个字都正确可见。实际native export wall time为1.3320094秒，只含这次Java capture，不能当OCR、检索、LLM、整源或H800吞吐。

同目录 `authored-wire/` 含匿名自行生成2页PDF、三个实际源码bytes及实际Java JSON；48个body glyph包括visible26／hidden mode6／zero alpha9／outcrop7，另有两个定位forms和一个page未知form。它是契约fixture，不能冒充赛方原件。该JSON 105,373 bytes、SHA `e19809480580239e4bb07e3ff5b91114a16785a058d3117d6fb8b64821728816`。原件不进入仓库fixture。

最终 `offline-export-audit.json` SHA `638ffd3214a9761a6a599c3aa20d39da66f1d8c39de8cf0167b0037710b65259`。自写审计按每glyph核字面／span／ID／style和实际source/artifact/code/config/identity/revision；121,707个布尔检查通过、0失败。该计数有大量机械性逐glyph检查，**不是独立PDFBox重放、视觉质量、legal scope或题目分数**。独立auditor另冷读实际原PDF／raw Java export／源码及AP bytes，通过CAS/gate核页框与不升级unknown；其最终结果由自己的记录报告，不能用本自审代替。

独立auditor需要可冷读的实际partial AP工件，因此在最终export目录新增 `partial-ap-wire/`。只用忽略目录的 `NativePdfPartialApFixtureExporter.java` 与最终frozen compiled capture/test helpers生成同一malformed AP case的**新 authored PDF**，不是此前测试进程内的同一byte identity；未改capture源码、在线target或最终编译版。`authored-partial-AP.pdf` SHA `2075db4dad7cb64989f402abdd31632144ab835f44ef1adaae7ce4d1fc93de8e`；实际Java JSON SHA `4797529ef2522f33b3c3584d36f24d1d9c9b694795628f7f9359b2aad5a5e27c`，三个实际adapter bytes一起保存。`partial-AP-export-receipt.json` 绑定generator source/class、PDF、Java JSON、observer/source revision及8个已捕AP glyph，明确partial／unknown；没有手拼JSON或模型调用。

## 后续接缝与未完成项

本轮没有OCR／LLM／真实model调用，不接canonical source registry／consumer，不改在线服务或数据库。GCC原自定义 source-reference也未冒充 `source_reference v2`。将native字面与actual full双OCR visual layout连成单位、全区域/列表intro-tail/cross-page closure、actual source-reviewed adoption、typed PDF transport和真实Vetting／Advisory引用／图片仍须分别完成；一致性不提升 humanConfirmed、法律适用或语义质量。并非74页GCC整体接受、官方八题或Vetting结构化报告通过。
