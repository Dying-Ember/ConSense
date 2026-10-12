# 原生 PDF 表格候选与投影接缝（2026-10-13）

本轮是 NEXT-02 的有界实现。独立 CPU 候选可以正常处理完整原件、保存双 Camelot 输出和既有有线表输出、暴露源成员残差；现有 native PDF 投影新增显式来源审阅的完整表格组和完整原页资产。**不关闭 NEXT-02／AC02–AC05，不宣称原件整表、所有 Notes／父条件或最终解析质量已通过。** Local05、活动索引、原件、模型、Drafting 和运行服务保持原有身份。

最新共享逻辑修复与实际完整源组评分见末节；下方首轮记录及未评分状态保留当时含义。

## 入口与依赖

- 候选命令：`tools/vetting_eval/native_pdf_table_candidate.py --sources <完整来源manifest> --output <新目录>`。输出目录必须尚不存在，源文件 SHA／字节数／实际页数须与 manifest 一致，运行前后重新核 SHA。`originalPages` 只保存已封来源页映射，不用来选择实验页或调参数。
- 使用独立 `requirements-native-table-linux.lock.txt` 的 Camelot 2.0.0／pdfplumber 0.11.9 CPU 环境；无 ML／OCR extras、模型下载或新 GPU 依赖。复用此前隔离观察器的原格、原 bbox、旋转／MediaBox／CropBox 转换、文字行、glyph、graphics、provider diagnostics 和错误保全实现；原始页属性由已安装 pdfplumber 的 PDFPage 读取。
- 三条路线：Camelot Network、Camelot Stream、pdfplumber lines/lines。各自输出完整 JSONL，保留 provider 原格与来源观察，独立记录逻辑提案；没有 provider 选择、逐格融合或答案补格。

来源 manifest 的 `units` 含 `sectionId`、`pageCount`、完整 `originalPages`、`originalCompleteUnitPdf: {path, bytes, sha256}`，总页数为 `completePageCount`。传入完整来源单元，命令不接收固定页／bbox／列参数。

## 候选的实际边界

`native_pdf_table_logic.py` 保留全部物理格和文字，以完整原生 glyph 观察核查 provider 表边界内遗漏、重复分配、跨格 glyph 和文字分歧。NA、No 保留原字面，返回空格只记 `unknown_source_blank`；merge-owner 未暴露时保留 unknown。

唯一明确编号列的连续编号行／空编号续行可形成可逆逻辑行提案；首个编号前的行形成表头提案。全部保留物理行／格身份，不能凭该提案证明表头或续行正确。完整表外 glyph 和可能 Notes／星号／缩写／条件行保留，owner 为 unknown，不自动挂到邻近表。缺列可能落在 provider bbox 外，因此保存完整页的未分配 glyph；该观察不能单独证明完整源边界或全部必要成员。

## 来源审阅投影与资产契约

`native_pdf_canonical_projection.py` 保留旧 v1／v2 正文重放，非空 tableGroups／assets 使用 v3。`native_pdf_table_projection.py` 要求：

1. `tableGroups` 声明 `tableGroupId`、`referenceTableId`、完整 `regionIds`、`fragments`、`logicalRows` 和 `tableInventory/cellMembership/blankCells/logicalRows/conditions` 审阅属性；同版本 source-reference table 必须声明 `completeSourceGroup` 并列出相同完整 region inventory。
2. 每个 fragment 保存来源 `regionId`、`rowCount/columnCount`、`headerRows` 与格的 `cellId/row/col/rowSpan/colSpan/glyphIds/bboxNormalized/valueState`。格位无重叠且覆盖全部行列；全部原生 region glyph 必须各属一格，字面由原 glyph 派生，不能从 reference／provider／答案替换。无 glyph 的空格须显式 `source_reviewed_blank`，其几何仍须在原 region 内。
3. `logicalRows.physicalRows` 完整分配非表头行；同页续行须连续，跨 region 续行须有同版本来源的 `continuation` 关系。完整表及 Notes／限定成员须进入同一个 `whole_table_group` unit，不能以正文单元绕过整组门；既有 parent／qualifier／continuation 关系闭合和源 glyph 全盘清单仍执行。
4. `assets` 仅接受 `original_page`，包含 `assetId/physicalPageNo/artifactDescriptor`；原页必须属于 scope 并匹配已验证 source-reference 原页 PNG／JPEG。没有不确定裁图。CAS 冷读 SHA 与字节数、来源／页／修订身份严格绑定，现有 registry 可返回对应原始资产 bytes。

公共 transport v2 把完整表格拓扑放在同一冷验证 snapshot 的 `nativeTableGroupsRef`，保留有界摘要、完整文字、来源指针及原页资产；源 family 保留子来源资产并拒绝资产 ID 冲突。旧正文 public v1 保持派生字段和原限制文案，重放不重新密封。所有 `semanticScopeVerified/humanConfirmed/wholeOriginalSourceCoverage` 仍为 false／unknown。

声明资源门限制每个 fragment 最多 100000 个格位，超过即明确拒绝；这不是目标表的固定列数。既有 `sl_library/sl_technical_library/specification_library` 专用 canonical scope 拒绝仍保留，未凭通用表格接缝放宽 SL 条款层级／来源角色门；本次 SL 候选原件运行不能自动进入 canonical publication。

**候选不能直接成为来源审阅声明。** 需独立核完整源表／必要条件组和旧正确项，再由显式 adoption 调用产生新 immutable generation；本次没有执行生产发布或活动库替换。

## 实际验证与失败保全

本地证据根目录：`C:/Users/Administrator/Documents/Codex/consense-implementation-20261013/`。原件／大 JSONL／运行日志不入 Git。

- 原生投影、transport、registry、family、HTTP、历史 producer 重放与新增契约控制：`native-table-final-tests.log`，75 tests 全通过；随后新增多行合并表头、跨页来源关系、格内显式分隔符及声明资源门控制，最终增量结果见 `native-table-delta-final-tests-02.log`。
- 首轮 `native-table-verified-tests.log` 为 69 tests／68 pass／1 error，保留。`native-table-baseline-confirmed.log` 独立加载 fb5160c 旧实现确认已有失败：旧 scanned fixture 是 `%PDF` 字样的非合法文件，真实冷读拒绝 `startxref not found`。只将该 native registry 兼容用例改为合法五页 authored PDF；未删／skip 用例或放松生产 validator。
- Linux CPU 实际 authored 两页表运行：`native-table-authored-actual-01/terminal.json`，三路线均完整两页，sourceQualityAccepted／baselineAdopted 为 false。它是运行／契约控制，不是比赛原件成绩。
- 真正完整 COM11 55＋PIL1 87＋GIN 84＝226 页 owned copies：`native-table-actual-full226-01/terminal.json`，actual exit 0，87.226 秒；三路线各 226/226 页，无源观察／路线执行错误，源副本三 SHA 前后不变。该耗时包含本运行观察／JSONL 写盘，不能与旧不含共有观察的库耗时比较排名。
- 独立保存输出审计：`native-table-actual-audit.json`。有线全路线 49 个完整 table 输出与原隔离实验在 226 页逐项相同；这是旧保存输出回归保全，不增加原件源分。

| 路线 | 完整页 | 表候选 | provider 边界内有未分配 glyph 的候选 | 文字诊断分歧格 |
|---|---:|---:|---:|---:|
| lines/lines | 226 | 49 | 0 | 8 |
| Network | 226 | 114 | 25 | 637 |
| Stream | 226 | 291 | 65 | 2303 |

上述为 `native-table-actual-audit.json` 从完整 JSONL 复算的残差库存，不是正确率；库间表候选／格分母不同，不能直接比较质量。三路线完整源组接受数都为 0（未做新的独立原件整组评分）。历史严格完整五目标表 0/5 保留，不用 641/642 字面 anchor 或候选数量将它覆盖。

未做新权重训练、模型／GPU调用、blind、新来源留出、完整 form／math／特殊 Word 内容、实际业务引用浏览器或 H800 验收；尚需独立完整源审阅、Notes／父条件／跨页归属判断，以及所需全范围源质量门。

## 02:05 增量：主表逻辑恢复已源评，完整业务组保持失败

新增同一 provider 的可逆逻辑 grid 提案：唯一编号列定位数据行，取其之前最近的完整多列表头并合并同列表头续行；连续编号续行保留完整原格 ID／glyph，caption、尾正文和 Notes 等被移出 grid 的原格仍在全 provider 输出。编号语法允许数字、单字母及罗马序号，不将多字母括号缩写当序号；没有 `(NA)` 特化、固定页／bbox／列数／行号偏移、字面修补或 provider 融合。该提案仍不自称完整来源组。

原候选全 226 页均重放新版源中立逻辑，三个原始 provider 输出及来源观察的实际 SHA 先核验。独立 scorer 复用 2026-10-12 密封参考、分母、原页／源 SHA、source-anchor midpoint、格位／跨度／字面和完整单表规则；开发已暴露，非 blind。首轮无此逻辑恢复的原始五表 0/5 记录保留。

| 原件目标 | 单表格数 | Network 新逻辑结构／完整字面 | Stream 新逻辑结构／完整字面 | 组外必需成员 | 必需 typed 关系 | 完整业务组 |
|---|---:|---:|---:|---:|---:|---:|
| Façade 1312 | 132 | 132/132 | 132/132 | 2 | 17 | 0/1 |
| Window 1316＋1317 | 132 | 132/132 | 132/132 | 3 | 19 | 0/1 |
| Timber 1321 | 132 | 132/132 | 132/132 | 2 | 16 | 0/1 |
| Gateset 1326 | 132 | 110/132 | 132/132 | 2 | 15 | 0/1 |
| Volumetric 1330 | 144 | 144/144 | 144/144 | 3 | 20 | 0/1 |

每项格数、关系、成员身份与逐格／逐关系原因可在 `native-table-whole-group-01/full-group-source-score.json` 和对应 `scoring/*.logical-02-same-domain-source-score.json` 复算。Stream 五主表为 672/672 格和 5/5 完整主表；Network 650/672 和 4/5，Gateset 最后列的 22 个源 anchor 落在 Network 右边界之外，未用参考扩 bbox 补过门。这是主表独立域，不等于带 Notes 的完整业务组。

12 个表外成员（2＋3＋2＋2＋3）的完整文字可由同版本 native 来源观察在审阅 source bbox 内回取；继承参考的 `sourceCharIndices` 是 PDFium character 身份（含 CR/LF），**不能冒称 pdfplumber glyph ID**。新评分使用同页已审来源 bbox 的 glyph center 映射再独立比较已封视觉字面，没有向 generator 回传监督。候选尚未产出完整 owned 表外成员与 typed 关系，因此 owned 成员 0/12、typed 关系 0/87、Window Note9 续页关系 0/1；页输出被捕获不能代替页组归属。继承参考未标父条完整分母，父条门为 unknown，不能写成通过或零漏失。初版总数13为加总错误，本表逐目标分母与来源记录未改。

旧有线路线保存输出与旧正确源项逐项保持：结构旧正确 333/333、字面旧正确 331/331；主有线完整结构 25/26、字面 24/26，与原独立库源评一致。原未知／失败不合并成新综合准确率。

收据根为外部 `native-table-whole-group-01/`：`logical-replay-02/terminal.json`（代码 SHA／原 capture SHA／3×226 页）、`scoring/`（密封同域 raw／新逻辑结果）、`full-group-source-score.json`（完整五目标及失败）。代码控制见 `native-table-logical-grid-final-tests.log`；没有新 parser／模型／训练调用，未发布新 generation，NEXT-02／AC04 保持未完成。

## 02:30 增量：单页明确 Notes／星号恢复；续页和法律范围仍未知

新增 `native_pdf_table_conditions.py`，仅在同页唯一逻辑表时形成可逆观察组：保原 glyph／完整原文行，提取相邻 caption／明确 Notes 标题的完整上下文，按显式编号／逗号列表／唯一星号定义保存同源关联。原标记表达式保留，不推断 AND／OR 的法律意义；不同字号后的内容留在完整未分配清单。多表、重复定义、缺定义、无明确 Notes 标题和跨页不猜 owner。所有 group 的 self acceptance 仍 false，`cross_page_members/legal_applicability/source_pixel_visibility` 保持 unknown。

再次正常读取原三章全部 226 页，Network／Stream／lines 各 226/226 实际完成，零路线错误；本次 cold start／缓存状态未经控制，82.525 秒不是速度排名。三来源 SHA 前后不变，完整 raw／逻辑／观察组共同保存。输出目录 `native-table-actual-full226-conditions-02/` 新增 `source-code/`，实际运行配方代码三份按字节保全并列 SHA，后续 worktree 清理不丢失 recipe。

| 完整监督源组 | Network 外成员／关系 | Network 完整已标源组 | Stream 外成员／关系 | Stream 完整已标源组 |
|---|---:|---:|---:|---:|
| Façade | 2/2；17/17 | 1/1 | 2/2；17/17 | 1/1 |
| Window 含 1317 | 2/3；18/19 | 0/1 | 2/3；18/19 | 0/1 |
| Timber | 2/2；16/16 | 1/1 | 2/2；16/16 | 1/1 |
| Gateset | 2/2；3/15 | 0/1 | 2/2；15/15 | 1/1 |
| Volumetric 含星号 | 3/3；20/20 | 1/1 | 3/3；20/20 | 1/1 |

Stream 完整已标源组 4/5，必需外成员 11/12、typed 关系 86/87；主表 672/672 和 5/5 保持。Network 完整已标源组 3/5；Gateset 的最后列 source anchor 问题保持，不能只因文字存在就改源格匹配。Window 的 Note9 缩写成员续至 1317，未有可证明的单页显式回链，下一页全部原生 glyph 仍保存为 unassigned；不因页邻近或只有一个 table 就造 continuation。

该完整组分数限定为继承的已封监督定义：主表、12 个必要 caption／Notes／条件成员、87 条已标关系及已标页组。每个候选成员须以同页源 bbox 的**非空白原 glyph 身份完整相等**匹配，再独立比较原视觉字面；字段与表的关系还须匹配 source origin／标号／类型，不凭文字相似选结果。原字面、空栏与未分配原 glyph 均保全，不逐格融合。源中的空白行 glyph 不被当作内容缺失，原始观察中仍完整保留。

`native-table-whole-group-conditions-02/complete-condition-group-score.json` 保存逐目标、逐成员、逐关系的匹配／失败，以及独立主表评分路径；parser 没有读取监督或答案。首轮条件观察 1/5（逗号列表尚缺）、初始完整组 0/5、旧原始主表 0/5 均保留在各自带配方的收据目录，不覆盖成最终质量。

完整已标源组 4/5 **不是 AC04 或最终来源质量已通过**：Window 续页仍缺，父条完整分母未在继承参考标注、法律适用／像素 visibility／独立留出仍 unknown，未采用／发布或改变 Local05。正常实际完整运行与软件控制分开：最终 `native-table-final-condition-tests.log` 为 85/85 tests，通过重复定义、多表归属、无标题、列表原表达式、源码重放／CAS／registry／HTTP 等契约检查，不计源正确率。
