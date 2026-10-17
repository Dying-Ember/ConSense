<!-- drafting-template-acceptance-priority-confirmed-20261017 -->
**2026-10-17 用户已确认验收顺序：先收口 Word 与原生 PDF 的解析质量，扫描 PDF 分阶段验证。** 当前验收不要求同步跑完 NTT/SCT 共52页扫描；已完成的NTT12页真实结果及缺陷保留，SCT40页尚未启动。每阶段分别记录实际覆盖、源质量、耗时及未完成，不以Word通过替代扫描版通过，也不由本次确认自动新增OCR、Goal或定时任务。

<!-- drafting-template-router-source-adopted-20261017:start -->
**2026-10-17 当前状态：NTT/SCT/SCC 模板专用解析 r15 已精确采纳到主源码。** Word 优先、实际字节分格式和 family/版次隔离；复用已有 OER 原生遍历，保 Guidance、完整范围、条件原文及命名通道位置。三 Word 冻结97组指导、157标记、3999原生段对应；全部63个已声明完整语义范围源核通过，新增27无局部标记填写区及1个Mr/Ms候选也逐源核验。14个Bill描述只给插入锚点，未来长度/列表基数和项目值未知；7个Word marker继续unknown，14处较广指导的直接条件依赖边未完成，原文上下文保留，不授自动编辑或完整项目条件图。

最终521项＝487通过＋34既有/条件跳过，另真实三Word隔离upload/reading/evidence方法1项通过；27普通Word全parsedDocument与I29保持，三模板去仅两可选coverage域后整值保持。SCC独立PDF18指导正文及边注分开保留，但normal仍PARTIAL。NTT12页真实OCR已完成并揭示主候选漏星/公式/补括号缺陷；r15对完整保存结果仅复算证据，0新OCR且不选未验证候选，不授模型质量提高；SCT40页未跑。活动API/浏览器/真实Wordpreview/业务验收未授通过。

13Java＋1Vue精确采纳，完整810后端文件等实测shadow；其他797、Python293、main target811（活动453）保持。无commit/push/部署、服务/DB/索引操作，不创建Goal/提醒；无模型部署或写操作，未执行权重逐文件before/after核验。当前说明见[模板专用路由实施文档](specs/drafting-template-source-routing-20261016.md)，本地完整凭据在`.local/drafting-template-routing-20261016`，继续一本HTML的I30。旧“实施中/未采纳”段落按历史状态阅读。
<!-- drafting-template-router-source-adopted-20261017:end -->

---
title: Drafting 模板证据解析实施交接
status: authorized-implementation-in-isolated-shadow; source-acceptance-in-progress
date: 2026-10-16
timezone: Asia/Shanghai
baseline: 10c3eaf3057a3a45cbf8747468cd48ddf78287aa
scope: NTT/SCT/SCC DOCX and separately identified PDFs; Word preferred; project completed sources isolated
---

# Drafting 模板证据解析实施交接

**最新执行状态：用户已直接授权专用路由实现，要求根代理亲看源 XML 与页面后逐模板加固。** 本轮 `.local/drafting-template-routing-20261016` 已固定九份独立来源并建立源参考，产品修改仅在 shadow；代码、完整原件解析及全部范围独审仍分别验收。NTT／SCT 原 PDF 为12／40页扫描件，SCC 原 PDF 为66页原生文字，均与 Word 分版保存。NTT Word/PDF 即使同标 March 2020，技术支持文字也不同。Word 优先只决定主来源，不证明同版。源标记可能控制跨表选项、完整行、非连续子条或句内短语；页尾、表尾及 Cont’d 不自动构成终止边界。继续同一本 OER HTML，不新建 Goal／timer，不改活动系统或原件。

## 接手目标与执行边界

在变量／规则模型工作前，把当前模板里的条款层级、原文、原生位置、布局线索、全部编制说明及其完整作用范围交成可核查证据。输入必须足以回答：**这条修改要求从哪里来，涉及哪些独立事实，作用于哪些完整成员，哪些关联仍无证据。** 本交接重点是解析精度及图谱输入，不能用模型补写原件缺漏，也不能由静态 catalog 反推“原文一定有这些变量”。

本文件只交接开发与验收工作，不记录这些能力已完成。编写时只读核对文档、代码接口、已有本地证据与四份原件 SHA；没有运行 parser、OCR、业务模型、服务、索引或真实编辑。实施者先核最新用户授权及现有工作区／并行 owner；保当前其他修改，不重启或替换活动前后端、DB、GPU／WSL 服务，不发布原件、权重、密钥或运行证据，不自动创建 Goal／timer／push。

**唯一解析链路继续使用现有 OER。** 原始内容／结构／位置在哪一层首次丢失，就由该层原 ENG owner 修；Drafting 只补消费投影与证据组装缺口。不要复制本地提取脚本成为第二套 parser，不先重写 UI、业务字段目录或生成器。

先按以下触发读取权威资料，历史状态以各自冻结身份理解：

| 何时读 | 参考 | 本交接采用的边界 |
|---|---|---|
| 开始及执行范围变化时 | [OER 范围与执行规则](oer-scope-and-rules.md)、[OER 实施交接](HANDOFF-ocr-embedding-rerank-20261008.md)文首当前状态 | 模板／评价答案／图纸隔离；代码、原件、模型及业务结果分开；原 ENG／EAC 门保持 |
| 排定 owner、依赖及产品接缝时 | [DYN 前置能力与任务图](specs/drafting-template-adaptation-prerequisites-20261015.md) | 本交接落实 DYN-00–06 的解析证据前置，向 DYN-07–09 提供输入；不跳过 G0/G1 |
| 修改正常解析入口或分组时 | [OER 修后流程图](diagrams/oer-recognition-after-repair-flow.md)、[当前演进路径](specs/oer-recognition-evolution-path-20261015.md) | 按当前正常入口、完整文档组装及跨文件回退门执行，复用原修复，不围绕单页过拟合 |
| 定图谱输入或语义状态时 | [typed graph 工作流](specs/drafting-typed-graph-workflow-20261015.md)，尤其六轴契约、目标编辑、独立上下文与第15节逐环节标注 | 解析只提供证据、候选及范围；变量／关系／动作候选和最终采用分别保存 |
| 处理另版 SCC 或核两版对应时 | [PDF SCC 提取与隔离记录](research/scc-pdf-variable-extraction-isolation-20261015.md) | 两版 SCC 的条号、对象、默认、条件、成员及目标不互相继承 |

## 1. 固定来源、已有材料和不可冒认的结果

下表原件在 2026-10-16 交接编写时再次按实际文件字节核 SHA，相同于已有 receipt。异机接手先查可达性并重新核，不从相同文件名认定同版。

| 来源 | 本机原件绝对路径 | sourceId | SHA-256 |
|---|---|---|---|
| NTT DOCX | `D:/R&D/ConSense/.local/drafting-regenerate-hotfix-20261015/NTT-original.private.docx` | `ntt-docx-60bd8796aa82` | `60bd8796aa828863c7edfe8617b7a5c56ee383afe8ba87a089e820f0fd9a982c` |
| SCT DOCX | `D:/R&D/ConSense/.local/drafting-regenerate-hotfix-20261015/SCT-original.private.docx` | `sct-docx-7def01d62e07` | `7def01d62e07b35dd86beeff995e0e37d73ce18adca2746e2a6f6d9bafb5aaa4` |
| 现用 SCC DOCX | `D:/R&D/ConSense/.local/drafting-regenerate-hotfix-20261015/SCC-original.private.docx` | `scc-docx-9aa2fa066836` | `9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208` |
| 另版 SCC PDF | `C:/Users/Administrator/Downloads/email from Smart QS Hackathon Team/07_Special Conditions of Contract (SCC).pdf` | `scc-pdf-1bebab1afbc0` | `1bebab1afbc0faaf8aa9c0b9e6c51b023c24e53a094d4987d746fdeb65b30741` |

已有证据入口均在忽略目录；路径是取证索引，不随 Markdown 发布：

- `D:/R&D/ConSense/.local/drafting-guidance-stability-20261015/source-map.json`、`extraction-receipt.json`、`corpus.json`、`corpus.md`：三份 Word 的原件／document.xml 身份、main `w:p` 逐项原文、guidance、其余标记候选、其他 story 检查及缺口。
- 同目录 `extract-guidance.py`：历史离线分析方法，**不是产品 parser**。它主要拼 `w:t`，按现模板末列表格布局找括号说明，并与旧 catalog 文本核对；不能据此证明 tabs／breaks／sym／OMML、继承样式、修订有效态、完整布局或任意新版均已支持。它的段落合成 ID 与简单 XML 路径不直接等于正常 OER native ID 或编辑器锚点。
- `D:/R&D/ConSense/.local/scc-pdf-variable-extraction-20261015/source-manifest.json`、`source-verification.json`、`inventory.json`、`validation.json`、`part-01-22.json`、`part-23-44.json`、`part-45-66.json`、`rendered/`：另版 PDF 身份、66页文字／页图、人工处理点及证据。PDF 为 2013 Edition、合订至 2023-01-01；本版物理1–3为封面／目录，物理4对应纸面1。这个映射只适用于本版，不写成通用页偏移规则。
- `D:/R&D/ConSense/.local/drafting-variable-graph-audit-20261015/atomic.json`、`atom-contract.json`、`atomic-receipt.json` 及 [图谱还原审计](research/drafting-variable-graph-reconstruction-audit-20261015.md)：人工还原的类型／链／证据参考，可做开发挑战集；不是自动解析输出、完整业务 gold 或新引擎通过证明。
- [两路模型试验记录](testing/drafting-guidance-followup-20261016.md)：当前模型接口／语义失败边界。合成 MOCK 段号只核语义意图，不证明真实编辑范围；模型输出不能倒灌为原始证据。

历史分母仅用于接手复算，**不是固定代码常量或最终完整性结论**：

| 口径 | NTT | SCT | Word SCC | 来源及限制 |
|---|---:|---:|---:|---|
| main document 内 descendant `w:p`，含空段／表内段 | 665 | 1238 | 2074 | `source-map.json`／`extraction-receipt.json`；不是 Word 物理页数，也不代表所有 story |
| 已提取 guidance blocks | 11 | 44 | 42 | 共97；分别对应18／73／103个非空guidance段，共194 |
| guidance之外的额外 marker／keyword候选 | 13 | 37 | 50 | 共100；未必是变量或真实编辑点，仍需完整处置 |

NTT-GN011 原文括号未闭合，来源段 NTT-P0614／0617／0620 内容照录；保 `sourceSyntaxComplete=false`，不要补括号后宣称原件如此。PDF 既有人工清单为74处理点候选、144逐字引文、155标记／编辑词候选行；这些均不是独立用户变量数。接手者对新参考发现增漏时增加参考修订与差异账，不为守住旧总数删项目。

**2026-10-16 全文扩展前的原生特征盘点补充：** 当前模型实验的 `w:t` 文本包与原生特征必须分别核对。直接重读上述原件 `word/document.xml` 后，NTT／SCT／SCC 分别观察到 `numPr` 8／21／22、`tab` 541／2209／2018、`br` 4／2／26、`u` 99／188／41；SCC 另有 `fldChar` 279、`instrText` 97。这些是 XML 元素数，不是变量数、漏项数或正常 OER 已丢失的证明。逐特征所在原段 ID、原件与 XML SHA 见忽略目录 `D:/R&D/ConSense/.local/drafting-graph-stability-campaign-20261016/evaluation/full-source-native-readiness-census.json`（SHA `b2cc15182a75ad8c8a917025a71de187f8425548216d21823902d62d2389884d`）。先核已有正常保存证据及消费投影是否保全这些信息，再决定首个缺失层；不得为模型试验复制第二套编号／字段解析器，或因提供了全部 `w:p` 就授完整可见文本／可填字段／布局通过。当前实验的简短直接表坐标编码能精确表示 NTT 全665段及 SCC 全2074段，SCT另有19段须保其他原生路径形式，不能静默跳过。

**同源正常保存证据已定位（2026-10-16）：** NTT 原件 SHA `60bd8796aa82…` 的历史正常产物在 `.local/oer-all-document-reparse-20261015/actual-native-01/corpus-02/complete-normal.json.gz`（SHA `6182abb393f2f79f7e3f46558966bcd9b85d595ba53255358029ff4f01436842`），I15 分节修订的同源保存产物在 `.local/oer-recognition-evolution-20261015/I15/shadow-native-docx-sections01/originals02/material/corpus-02/normal/complete-normal.json.gz`（SHA `88ac6951f9a674f09ab4ba371d78e05ede5e34a21dc4e759423cfdee60886826`）。两者各有128个正常聚合块；其中6块的8个 `wordNumbering` 已保存，按原生路径与 `numId` 可唯一对应8原段。它们是可复用的 producer 观测，不能由 `resolved` 直接授渲染编号或语义范围正确。现查保存投影未见精确 tab／显式 break 事件位置及 underline run 属性／跨度；普通空格／换行不能回推这些原生事件。先在现有正常观察和消费接缝补证据，不为图谱实验重写 parser。128聚合块与665原段不能相减作为丢失数；这些缺口也不直接等于变量遗漏。逐项证据、JSON pointers、源路径和边界见本地活动 `rounds/I08/normal-source-reuse-audit.json`、`normal-numbering.proposed.json` 与 `root-native-reuse-readback.json`；本轮仅据可读全文及独立编号层准备 D1 语义发现，完整版式、可填位置和图谱来源门仍未通过。

**SCT／Word SCC 同源正常产物复用补充（2026-10-16）：** 本地活动的 `evaluation/full-project-source-native-readiness.json`（SHA `8ab72e39b6476f11bbbbcacf8e7aac2b732e1c268759ff53c191b3cca988fcab`）已按原件身份定位 SCT=`corpus-03`、Word SCC=`corpus-04`，并核正常保存结果、candidate、ordinary 及 SourceDocument 接缝。历史正常结果分别在 `.local/oer-all-document-reparse-20261015/actual-native-01/corpus-03/complete-normal.json.gz` 与 `corpus-04/complete-normal.json.gz`；I15 同源结果沿上段 `originals02/material/` 下相同 corpus 路径取回。SCT 为349聚合块／PARSED，SCC为1100聚合块／PARTIAL，后者有4条结构warning；这些是工程观察，均不授来源或业务通过。已有21／22个编号对象在所查 candidate 与 ordinary 中逐对象保持；复用这些观察，不另写编号器。SCC 的4个数学原生对象保原XML／摘要身份，公式语义仍未核。

精确 tab／underline／显式 break 来源跨度在上述已查保存层仍未观察到；SCC 的97个 `instrText` 原始事件（92个字面PAGEREF前缀、1个TOC前缀、4个未组装片段）及279个 `fldChar` 也尚无专门字段指令来源投影，不能由缓存文字补成有效引用。SCT 的 `SCT-P0797–0804`、`SCT-P0806–0816` 共19段属于合法嵌套表路径，含空成员；保持完整原路径，不能强套NTT坐标形式或跳过。先由源核判断这些特征是否影响当前所需语义，关键缺证保unknown；元素数不等于变量数或遗漏数，聚合块数不能与原段数相减。以上是复用与缺口交接，0新解析／模型调用，不扩成第二套字段或布局parser；PDF SCC继续独立。

**编号对应已直接复核，来源事实可独立复用（2026-10-16）：** SCT 的21项保存编号现已逐项与原件 `numPr`／原路径唯一匹配，见本地活动 `evaluation/I11-SCT-saved-numbering-crosswalk.json`（SHA `a2d29656609ed92d4e2821f717563dff7df048d544965ea6ecf95fc1278a5548`）。Word SCC 的22项编号及4个数学对象另导出为不含私有评分的 `evaluation/I12-WordSCC-native-source-only.json`（SHA `bdc70c50c172d0b0f6c9a1a2d0a0b4484d07895b8e57984abb3b39df69c8230f`）：保存的编号对象与原节点对应；数学 rawXML 的字节身份和原始子树逐项对应，原 producer 的语义 unknown 保持。编译模型输入时可复用这两份 source-only 文件，不要把私有语义验收报告喂给模型或提示词作者。对应关系准确不授编号渲染、公式求值、法律范围或全图通过；精确事件跨度及字段指令缺口仍按上段处理。

**指导语物理容器不能跨模板套用（2026-10-16）：** 按冻结原 `node_path` 逐项盘点，NTT／SCT 的指导语分别涉及12／54个最近 `w:tr` 容器，另有跨多个 row 的指导语；Word SCC 的全部42条指导语／103个指导原段均无 `tr` 祖先，是 body 原段。该事实只描述这些指导语的原生位置，不能据此断言 SCC 整份没有表格。见本地活动 `rounds/I12-capacity/physical-container-capacity.json`（SHA `caa683801f48e2a2aa168c341efcc74613227b8002d5aa63c30db5ed871a38b1`）。不要把 NTT／SCT 的表行截取方式套给 Word SCC，或将 guidance 自段当成其完整法律作用范围；SCC 仍须核父条、选项、续文与引用。盘点未删除容器外任何正文，也未授法律闭包、布局或语义通过。

## 2. 先体检现有接口，确定唯一修改点

所有代码路径相对 `D:/R&D/ConSense/`。此处“起点”仅指已读源码的接口存在，实施者仍须沿实际版本复核调用者、持久化及消费者是否保全字段。

| 位置 | 可复用起点 | 首先核什么 |
|---|---|---|
| `ai_consense_service/src/main/java/com/consense/document/DocumentParser.java` | `observe(...)`、`parse(...)` 与 `ParsedDocument` 的 text／blocks／coverage／sourceSha／nativePdfEvidence | observe明确仅路由观察；正常parse入口是否把完整 native／结构证据带到Drafting，而非只传normalized text |
| 同目录 `DocumentBlock.java`、`ParseCoverage.java` | sourcePosition、Word编号／symbol／cell／nativeObjects、PDF glyph／form、visual/frame/provenance、whole page groups | 物理complete不证明文字／关系正确；candidate不能自动成为effective text，空白结构不能被文本过滤丢掉 |
| 同目录 `OerNativeDocxObservation.java`、`DocxNumberingResolver.java`、`WordSymbolDecoder.java` | 原ZIP／part、namespace路径、native node、原生表拓扑、四个重叠text channels及编号观察 | 原生通道与有效文本分别核；paragraph/run不能重复相加；继承隐藏／删除／strike／story适用未证时保unknown |
| 同目录 `OerNativePdfObservation.java`、`PdfNativeSupport.java`、`PdfPageEvidence.java` | 原件物理页、glyph/frame、crop/rotation坐标、表单与可见性观察 | 只选页观察不等于66页完成；印刷页标、原始物理页、bbox坐标框架分开 |
| `ai_consense_service/tools/vetting_eval/complete_ocr.py`、`structured_parser_evidence.py` 及当前 OER 正常结构生产接缝 | 双解析原始产物、完整采集／page map、完整组准入与冲突保留 | 未采集／失败／截断／争议组在哪层丢失；复用修复owner，不能在Drafting静默拼文本填洞 |
| `ai_consense_service/src/main/java/com/consense/service/sources/NativeCanonicalContract.java`、`NativePdfCanonicalContract.java`、`NativeDocumentCollectionContract.java`、`CanonicalEvidenceService.java` | 身份、完整来源单元／集合与当前来源验证 | 定位、完整组和未决能否正常存取；不创建第二套canonical真相 |
| `ai_consense_service/src/main/java/com/consense/service/drafting/DraftTemplateSourceText.java`、同目录 `DraftTemplateReadingSources.java`、`DraftSourceContext.java` | saved/fresh匹配、既有冻结来源身份、上下文窗口及context_insufficient | legacy归一化匹配不授原字保真；6000字符窗口及pipe表启发式不证明完整成员；不能只扩窗口代替结构组装 |
| `ai_consense_service/src/main/java/com/consense/service/drafting/DraftSourceAnchors.java`、同目录 `DraftSourceEditCompiler.java`、`DraftBusinessRules.java`；资源 `ai_consense_service/src/main/resources/drafting/input-catalog.json`、同目录 `guidance-compiler-bindings.json` | 当前版固定规则、段号与编辑绑定 | 只作兼容和旧正确回归；新版本重新发现范围，不能迁用旧段号／固定catalog来证明新解析正确 |
| `ai_consense_service/src/main/java/com/consense/document/DocxTemplateEditor.java` | `inspect(byte[])`→TemplateIndex、source hash、part/path/node hash、原生表／段落／run及受限apply | inspect不决定适用性；原生节点的真实绑定与编辑能力单列，故事区编辑不支持等限制保持 |

**体检交付**：一张能力账，每行写“已具备且有证据／字段存在但未贯通／已复现缺陷／未知”，附实际调用链、输入输出样本与首个丢失层。完成标准是每个本交接必需字段都有owner与证据或明确缺口，不以“已有一个parser类”完成盘点。

## 3. 解析必须保留的事实与判断边界

### 3.1 原文、层级与布局

保三层：原件／原始观察、分析投影、已确认采用。原字、空白／制表符／换行／控制符、数字／比较符／and-or-not、脚注和编辑标记保可回取；搜索归一化另存映射，不能拿normalize后的字符偏移直接切原件。

条款树至少区分文件／story、章／条／子条／列表、选项组及选项、guidance、表／行／单元格与页眉页脚／目录。显示标签、原生numPr/域、父节点、顺序、正文成员集合分别记录；同号多处保occurrence，目录同名不是第二份正文，段落样式名／正则命中只是层级证据。原文已有编号冲突、不闭合或缺父节点，保候选及原因。

DOCX 位置用实际source SHA、part、namespace node path、native ID／hash、story与**指定文本通道内的UTF-16 start-inclusive/end-exclusive偏移**。观察器的 `observedRaw/deleted/directStrike/nonDeletedNonDirectStrike` 是重叠观测通道，不自动等于渲染有效文本；编辑器part#path及hash需显式映射复验。段落ordinal可作本修订导航，不能跨版充当永久身份。没有经独立renderer映射时DOCX物理页／bbox保持unknown，不能拿分页符数、段号或catalog页号补假坐标。若增加渲染映射，另存原DOCX与派生PDF的SHA、renderer／字体／配置身份及节点到渲染页的对应证据，不能反把派生页定位当原生编辑锚点。

PDF保原始1-based物理页、page frame／crop／rotation／尺寸、glyph或区域来源及原生顺序。纸面页标另存。行文本／页文本提取不证明真实读序，边注、多栏、跨页及跨表结构要独立核。原生字体缺映射或Symbol／ArialUnicode渲染警告保留；不能只凭native有字就授字形正确。图纸按OER身份路由后排除；提及图纸编号的文字说明仍属于本模板证据。

### 3.2 全部修改要求入口

枚举显式Guidance Notes、括号侧栏／脚注、星号／井号及其说明、下划线／点线／空单元格、content control／表单字段、Option／Alternative选择、Not used／delete／insert／amend、无标记编制建议、外部核查及引用更正。跨run的同一标记保完整span，保普通星号／井号／目录引导线／单位符号的负例，不把每个符号变成变量。

每个候选须有处置：真实修改要求／固定正文／非编辑标记／仅外部核查／重复出现但独立位置／歧义／未观察／失败，并保理由与证据。无标记不等于无要求；空文本不等于没有字段；未解析出来不等于业务false。所有要求先形成来源账，再提候选变量，保持要求与变量多对多。

### 3.3 完整作用范围：以成员证明完成

一个target/group不能只存“标题＋首段＋末段”。交付**有序成员ID集合、各成员原生定位、父条／选项／表关系、边界证据、连续／非连续范围、缺失成员和完整性状态**。

1. 以本版结构与说明定位owner，收集正文、子项、共享前缀、续文、嵌套表、相关Guidance及条件／例外。解释为“删除／Not used／选A/B”的不同粒度由原文支持，不由相邻位置猜。
2. 遇页尾／行尾／表尾仍开着的guidance、列表或选项，沿本版逻辑顺序寻找续接。跨页重复表头／页脚作为furniture单独保留，不截断业务成员；另一张物理表是否延续，须有表头、列拓扑、标签及上下文共同支持，不因相邻就合并。
3. 完整性至少需可核的开始、同层结束／下一peer边界及内部全部成员。A/B组选项与各子项分别列成员；共享heading／common尾段明确归属。编号缺口、跨表读序冲突、成员含未知对象时输出候选组与missing／conflict，不标complete。
4. 长度／传输／上下文预算不足时保整个组的身份和分批manifest、游标、expected member集合（若已知）与digest；各批实际取回齐并核对后才可供完整组消费。不能裁尾、只给第一页、只给相邻N段，或把“所有已发现成员”改名“完整成员”。expected未知时保持unknown。
5. 编辑可行性另审。解析到PDF A/B全组不证明能原PDF保格式删改；DOCX分析quote准确也不证明原生span安全。只有本版原件hash、目标hash、expectedOriginal及能力白名单都满足，才向现有编译器交可执行绑定。

### 3.4 引用与版本隔离

每条引用保“引用出现位置／原文标签→文件命名空间／版次→候选被引条款／粒度”，并记录明示、候选、歧义、未提供来源、无匹配或冲突。SCC8.2、GCC8.2、SCT8各有独立命名空间。跨文件取证带实际source ID／SHA；仅给条号字符串不算已解析引用。

默认存在两个分析profile：`NTT+SCT+Word-SCC` 与 `NTT+SCT+PDF-SCC`。比较profile可建立带证据的跨版候选对应，不能把两个SCC同时当同一活动模板或共享其条号／默认值。PDF4.2 A/B与5.2引用、PDF8.2采用与22.8全组选项须读本版；Word7.304质保安装前提、Word标准预付款6/7等不得移植。PDF的Nominated Sub-contractor与Word Specialist/BSSSC对象分别保留，不凭相同工种合并。

## 4. 图谱输入的最小可审投影

下列是**待DYN-02评审的字段语义示意**，不是已存在API，也不要求创建新数据库或平行canonical模型。优先映射现有来源／coverage／native结构；ID、hash、反向来源及引用回取由程序维护。类型AST沿typed graph规格复用，不在解析层另造V/P/R词表。

```json
{
  "schemaRevision": "candidate-template-evidence-v1",
  "templateSet": {"id": "set:word", "revision": "r1", "profile": "NTT+SCT+Word-SCC", "sourceRefs": ["src:ntt", "src:sct", "src:scc-word"]},
  "sources": [{"id": "src:scc-word", "sha256": "<actual-sha256>", "mediaType": "docx", "parseGeneration": "<actual-generation>", "observerRevision": "<actual-revision>"}],
  "units": [{
    "id": "unit:1", "sourceRef": "src:scc-word", "kind": "guidance",
    "parentRef": "clause:1", "orderedMemberRefs": ["native:p1", "native:p2"],
    "rawTextRef": "artifact:exact-text", "originalTextSha256": "<actual-hash>",
    "locationRefs": ["loc:1", "loc:2"],
    "captureStatus": "complete", "sourceSyntaxStatus": "unclosed", "semanticScopeStatus": "unknown",
    "missingMembers": [], "limitations": ["original_unclosed_bracket"]
  }],
  "locations": [{
    "id": "loc:1", "sourceRef": "src:scc-word", "kind": "docx_native",
    "nativeLocatorRef": "<existing-part-path-node-hash-channel-span>", "renderedLocationRef": null
  }],
  "processingPoints": [{
    "id": "point:1", "requirementUnitRefs": ["unit:1"], "markerLocationRefs": ["loc:1"],
    "candidateKind": "conditional_edit", "disposition": "needs_review",
    "ownerRefs": ["clause:1"], "targetGroupRefs": ["group:A"], "evidenceRefs": ["unit:1"], "issues": ["scope_not_yet_verified"]
  }],
  "groups": [{
    "id": "group:A", "sourceRef": "src:scc-word", "kind": "alternative",
    "orderedMemberRefs": ["native:p3", "native:table1", "native:p4"],
    "boundaryEvidenceRefs": ["unit:begin", "unit:next-peer"],
    "completeness": "unknown", "missingMembers": [], "editCapability": "analysis_only"
  }],
  "references": [{"id": "ref:1", "fromUnitRef": "unit:1", "originalLabel": "SCT clause ...", "targetNamespace": "SCT", "targetSourceRef": "src:sct", "candidateTargetRefs": [], "status": "unresolved", "evidenceRefs": ["unit:1"]}],
  "coverage": {"referenceRevision": "ref:r1", "inventoryRef": "artifact:all-input-members", "dispositionsRef": "artifact:all-candidate-dispositions", "complete": false},
  "issues": [{"id": "issue:1", "scopeRefs": ["group:A"], "code": "GROUP_COMPLETENESS_UNVERIFIED", "status": "open", "evidenceRefs": ["unit:1"]}]
}
```

DOCX/PDF locator在实际契约采用互斥shape：DOCX保part/path/node/hash/channel/UTF16；PDF保physicalPage/frame/bbox及glyph／region原始引用。上例不以虚构页号补DOCX，也不以原PDF页号冒充Word编辑锚点。

候选变量session取证时，从此投影装入完整相关guidance／父条／选项／续文、各要求来源、已有变量／类型参考的只读候选和具体unknown；输入跨文件材料保持同一项目profile。历史图谱只作比对参考，不能替代本版原文。证据和task封套可缩包装，原文条件、缺证状态和成员完整性不能裁掉；生成budget耗尽与输入不足分别报告。

## 5. 可执行任务次序与完成条件

建议每批一个owner，先完成当前批可核证据再推进后续。任务ID为本交接局部标签，关联原DYN／ENG，不替换它们。

| 次序 | 工作与交付物 | 依赖／完成条件 |
|---|---|---|
| TP-00 身份与体检 | 固定代码HEAD／dirty快照、四源manifest、现有接口／消费者能力账及唯一owner；区分read/analysis/edit能力 | 对应DYN-00／01／02；四SHA核对，缺资产明确列出；每个必需字段都有证据或缺口 |
| TP-01 参考分母 | 建全source-member inventory与独立参考：所有story／正文／表／空结构／66页、guidance／markers／空写区／指令／非编辑负例 | 分母从原件独立建立；旧97／100／74／155仅对照；双作者复核原文及布局关键处，未核项不填零 |
| TP-02 正常链路基线 | 经当前正常入口保存未修结果、原始provider/native输出与canonical消费投影，逐字段比参考找首个丢失层 | 真实parser/OCR只在本次实际授权和有限预算下执行；缺运行条件不阻塞离线契约，但不得称来源已测 |
| TP-03 内容与结构补缺 | 只修首个丢失层的原字／编号／表拓扑／空写区／native锚点和字段贯通；交最小代码、调用者与契约测试 | 对应DYN-03／原ENG；保旧正确及错误历史，native/raw/candidate分层无退化 |
| TP-04 条款与范围组装 | 建本版层级／引用视图、全部处理点处置、完整选项／跨页／跨表成员与边界证据 | 对应DYN-04／05；每个参考处理点与组都有下落；未知组不可作为完整自动编辑输入 |
| TP-05 版本与图谱接缝 | 完成两个SCCprofile隔离、新旧对应候选及最小图谱证据契约；验证session实际收到完整证据而非旧catalog摘要 | 对应DYN-02／06→07–09；来源变更／错版／旧generation／未闭引用均有拒绝或局部unknown；不自动采用 |
| TP-06 自动化与全源复验 | 聚焦反例、适用Java/Python代码检查，继而同输入完整原件前后比较、负例及旧正确保持 | 完整分母／失败／unknown／未执行一致保留，关键字段零错漏；来源质量、模型语义、业务／导出分别判门 |
| TP-07 独立交付 | 不同作者按冻结原件与实现输出复核；交能力账、diff、运行身份／测试、全源逐项账和剩余门 | 软件green不覆盖来源缺口；未完成真实效果／页面／编辑项明确列出，不擅自complete或部署 |

准备阶段可只读并行：Word native内容／布局、PDF版本／跨页组、契约／测试参考。共享DocumentParser／canonical模型由单owner集成。实际代码修改前搜索全部调用者和现有测试，运行方式读取仓库当前构建配置；不在活动服务使用的target目录覆盖运行文件。

## 6. 测试矩阵和独立验收

使用自写小模板构造变体完成代码反例；真实原件与人工参考留在忽略目录。开发暴露样本如实标development，另有未用于规则选择的模板／文档族验证泛化；无法取得独立来源时明确不授独立泛化门，不把重排／改名的同源文件称holdout。

优先沿现有测试接缝补行为反例，不另建演示parser来通过：

- `ai_consense_service/src/test/java/com/consense/document/`：`DocxStructureIntegrationTest`、`DocxCellTopologyTest`、`DocxNumberingResolverTest`、`WordNativeObjectsTest`、`WordSymbolDecoderTest`、`OerNativeDocxObservationTest`、`OerNativePdfObservationTest`、`OerEngineIntegrationTest`、`DocxTemplateEditorSafetyTest`。
- `ai_consense_service/src/test/java/com/consense/service/sources/`：`NativeDocumentCollectionContractTest`、`NativeCompleteCollectionContractTest`、`NativePdfCanonicalContractTest`、`NativePdfSourceFamilyContractTest`及相应normal capability／service测试；覆盖完整成员、丢批、错版、逐读当前身份。
- `ai_consense_service/src/test/java/com/consense/service/drafting/`：`DraftingTemplateReadingIntegrationTest`、`DraftingTemplateSourceCompatibilityTest`、`DraftSourceContextOrderedListTest`、`DraftRoundThreeNativeTableColumnRolesTest`、`DraftSourceBillIdentityTest`；旧来源acceptance类按本次环境和资产可达性分开报告，不用删除／skip换绿。
- `ai_consense_service/tools/vetting_eval/`：`test_structured_parser_evidence.py`、`test_complete_ocr.py`、`test_native_collection_complete_wire.py`、`test_native_pdf_source_family.py`、`test_native_pdf_producer_replay.py`及正常入口相关反例。纯fixture／回放通过不等于真实provider或原页质量通过。

| 挑战族 | 必须保留／拒绝的结果 |
|---|---|
| 原字及多通道 | 星／井跨run、tab/break、nonbreaking字符、symbol字体、OMML、插入／删除／strike、隐藏／继承状态；原字回取／offset一致，未解析字形或有效态保unknown，不拼重叠通道 |
| 层级及编号 | 手写号／numPr／样式继承／重启编号、重复标签、目录同名、条号变但正文同、同号正文变；父子与正文边界正确，歧义不强配 |
| 表及空写区 | NTT三列/SCT二列既有样本，加列数变化、合并／嵌套／无边框表、空行空cell、下划线／点线／控件；不把邻列指导接到错误正文或压掉空字段 |
| 无标记指令／假阳性 | 全部guidance、仅Notes外编制建议、普通星号／目录引导线／固定期限／履约正文；候选都有来源和处置，不为凑字段数增问题 |
| 原文未闭合／缺证 | NTT-GN011保所有已存原文和sourceSyntax缺陷；capture完整与scope未知区分，不补字或静默裁尾 |
| 完整组跨页／跨表 | 指导／选项跨分页，重复表头页脚、续表／独立邻表、共用前缀尾段；所有成员／边界与缺口逐项可核，只删标题为负例 |
| Word质保 | 安装存在、质保要求、Specification指定区域、三类成员与重复引用分别可达；保and/or与局部范围，不能合成一个boolean证据 |
| NTT/SCT联动 | 电子发放与BQ=L10Pro条件独立；SCT5双信封、SCT6删留、重新字母及SCT引用保各自粒度；bill完整集合与各专业引用不合为同一个值 |
| PDF4.2→5.2 | 本版A(1)–(8)／B(1)–(9)完整跨页成员与引用；数字是人工核此版的参考，生产发现靠结构；双信封不能代答分包资料／经验是否要求 |
| PDF8.2→22.8 | 本版8.2实际采用、铁路／NSC不同成员及反向引用；局部footings星号不扩大到piles／pilecaps／整条，禁止借Word8.304／22.307套编号 |
| 来源错版／失效 | Word/PDF SCC同标题同号／不同对象、同名文件换字节、parse generation变更、缺源／重复hash引用；拒错版绑定，旧结果stale保留，其他已确认来源不被抹去 |
| 容量和失败恢复 | 完整组超过传输／上下文边界、缺批／重复批、OCR/provider失败、取消／晚到；whole-group保持unknown／失败，页数／字数增多不冒充质量通过 |
| 旧正确／下游隔离 | 既有模板正确文本／拓扑／编号／位置保持；模板证据不混入Vetting/Advisory索引，原件／旧gold／fixed05与其他运行不变 |

关键验收规则：

1. **逐项可重算**：冻结原件、参考修订、代码／parser／模型／配置身份、全部源成员和候选分母；每项给before/after、定位、原文、期望、观察、处置。源语法缺陷、missing、unknown、not_run、failed单列，不能移出分母换绿。
2. **关键字段零错漏**：经独立原件核定的条款号／层级、否定与限定词、数值／单位／日期阈值、指导所属、marker/span、目标成员、跨文件命名空间和当前来源身份，要求零误读、零遗漏、零错误归属。未知来源不能强造正确；仍有关键可核缺口时来源门保持未过。
3. **完整范围零误扩**：所有已知目标成员／整组选项完整，旧正确成员保留，负例非成员零误吸入；不允许“覆盖了所有已找到成员”替代完整参考分母。每个自动可用group都须有完整性证据；unknown group保持analysis-only。
4. **同分母比较**：输出整体及NTT/SCT/Word-SCC/PDF-SCC分别的正确／遗漏／改字／层级错／错归属／误增／unknown／失败／未执行，以及旧正确→保持／回退。人工修正和原始正常输出分开，局部正例不能替代全源。
5. **分层报告**：代码单元／契约／集成通过；正常parser/OCR实际执行；原件保真；变量／链语义；真实页面／原件编辑及导出分别记录。模型JSON合法、coverage.complete、原文长度或程序exit0不能关闭来源／业务门；源解析目标完成也不授整张图谱或27B能力通过。

## 7. 最终交付清单

提交最小实现与适用测试；同时保留私有本地的manifest、能力账、完整reference／实际输出、分母及旧正确差异、原页／原生锚点抽查、失败／unknown账和运行receipt。代码库只放不含原件的契约、合成fixture、验收方法与摘要。交付摘要必须明确：哪些接口复用、实际改了哪一层、哪些源／组已达到关键字段零错漏、哪些未过、是否具有真实编辑能力，以及下一owner与依赖。

若某项能力已有且证据足够，直接复用并只做正常链路回归；若底层观察完整而Drafting投影丢失，修消费者；若原生／OCR原始输出已丢失，返回原ENG修复。任何情况下都保本版原文、范围和失败，完整图谱只能建立在这些可核证据上。
