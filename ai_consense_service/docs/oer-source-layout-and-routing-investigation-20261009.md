# OER 原件布局与上传路由调查

更新：2026-10-09，Asia/Shanghai。当前阶段：原件与现有链路调查完成，整体方案待沟通。本文汇总 SL 技术库、2b 全部文件及现有上传链路的调查结果，作为下一次整体 OER 讨论的依据。已确定范围与执行规则继续以 [中央规则](./oer-scope-and-rules.md) 为准，实现、实验和部署进度见 [handoff](./HANDOFF-ocr-embedding-rerank-20261008.md)。

本轮没有修改赛方原件，也没有根据这些调查结论切换在线 Java 后端、前端或 MiniMax 中继。现有 OER 实现草稿及此前实验保留。下面的布局调查完成情况与全文语义、解析模型、检索和应用验收分别记录。

## 调查覆盖与可复算来源

| 对象／检查层次 | 实际覆盖 | 证据及限制 |
| --- | --- | --- |
| 原资料包身份清点 | 48 原件：30 DOCX、18 PDF；6 固定来源、38 项目输入、4 隔离评价 | [S1](#证据索引) 的 `summary`／`files`；文件生命周期不是有效条款数量 |
| PDF 容器清点 | 合计 4,684 原物理页 | S1 `pdfPhysicalPagesTotal`；含排除章节、封面及评价文件，不是活动索引分母 |
| SL 技术范围 | 原容器 4,300 页；纳入 1127–4300，合计 3,174 页 | S2 顶层范围连续性及 25 个边界原页视觉检查；没有逐字验收全部技术页 |
| SL 条款／定位调查 | 独立调查检查 18 个高清原页；根代理补看 6 个原页 | S3 两份视觉收据；两组可能重叠，不相加为唯一页数 |
| 2b 原件 | 35 文件：23 DOCX、12 PDF | S1 的 `2b_Tender documents`；下文逐件列出 |
| 2b PDF 布局 | 258／258 原物理页对象普查及缩略图目视；28 张 contact sheets；24 个高清全页样本 | S4 逐页 ledger；完整全文／所有 cell 语义接受页数为 0 |
| 2b DOCX 原生结构 | 23／23 原件；23／23 只读派生 PDF 成功 | S5 native summary、差异矩阵及 render receipt；派生分页不代表 Word 权威分页 |
| 2b DOCX 代表页视觉 | 39 个派生代表页已实际目视，来自 17 份原件；其余 6 份仅 native 调查 | S5 最终视觉／核验收据；不自动推广为全文、每个表格或每项格式语义已接受 |
| 上传与身份路由 | 31 份源码／证据 SHA 快照 | S6；只读源码调查，没有本轮数据库、在线上传或浏览器验收 |
| 真实双解析 | AoA 原物理页 1、APB 原物理页 3，各执行 Paddle／MinerU 完整页面路线 | S7；实测错误保留，尚无已接受的全资料 canonical 选择 |

比赛排除 BQ／SOR／General Summary、PRE 以外的项目工程技术 PSS、SL 非技术章节。项目 PRE、适用 GS／SL 技术参考、正文 Bill 身份引用及 GCC／SCC 付款和价格调整机制仍保留。范围外资料缺席不列为缺件或验收阻碍。2d／2e 评价答案隔离；范围内合约附件的 Sample／Specimen 文件名或内部示例不等于评价答案。

## SL：clause no 可作为主结构，但需要正文边界校验

S3 的原始书签候选调查得到 11,218 个技术书签，其中 9,182 个符合编号样式。它们包含标题和层级节点，不能称为已确认的 9,182 条正文条款。原写法存在大小写、字母尾码和附录变体，应保存原编号，另建查找键和明确的版次命名空间。

| 原件位置／现象 | 调查结论 | 对后续实现的要求 |
| --- | --- | --- |
| 物理页 1133，CLA1.M010.9–M050.9 同页 | 一页可以有多个条款起点；书签有 `/XYZ`、Left、Top、Zoom | 保留 raw destination，转换后与正文编号 bbox 核对；书签 anchor 不当作正文 bbox |
| 物理页 1258，Fig. 4 与 COM5 附录 | outline depth 与正文页眉所示归属不一致 | 书签树是先验；规范父关系须结合正文标题、编号及实际位置 |
| COM2.M005.A 从 1179 延续，1187 顶部仍有前条 `(g)`，之后才是 COM2.M010.9 | 按“下一书签页之前”整页截断会漏费用／不延长期内容 | 按页内块起止和读序聚合跨页条款，允许同页分别属于前后两条 |
| 1201，COM2.APPEND1.9 | 水试验内容主要在图像中 | native text 不足以表示全文；图像、标题、归属和位置进入同一结构证据 |
| 1327–1330，COM11.APPEND5.9 | setup、PLANT／SITE 流程图、Data Fields 表及 notes 延续到无独立编号页 | 无编号续页继续归于实际父条；保留原图／文字／父条定位与完整表格组关系。首版按 Q10=A 返回正确图表，不以该观察声明自动理解箭头关系 |
| 3437–3438，ELE36.6 checklist | row 7 跨页，空 checkbox 与 Remarks 有语义；完整外组边界仍未知 | 局部观察不当作整组已完成；续行、空框和备注关联需要独立验证 |

具体层级、原页坐标和模型调整规则已写入 [SL 专项文档](./sl-clause-hierarchy-and-model-adaptation.md)。技术范围中的 General、定义、附录、条件和例外仍是内容；没有独立 clause no 的子项、图例和脚注不删除，也不由模型发明编号。

1179–1187 的整页 native 候选文本经实际 BGE tokenizer 计数为 5,384 tokens，不含 special tokens；含 special tokens 为 5,386（S3 token receipt）。它包含页眉页脚和末页后续条文，中间部分原页未做完整语义验收。这只是候选跨度计数，不能当作已接受 clause 长度，也不能仅据此证明某个 clause 必然超过 2,048。后续参数对照须在正文边界确认后测实际输入和覆盖。

## 2b PDF：逐文件规律和身份依据

以下 12 份均已完成逐页布局调查；原物理页数、高清样本和详细身份／版本线索见 S4 `files`、review ledger 和 census。文件名用于对应原件，身份结论还依赖实际标题、正文、页眉页脚及资料包关系。

| 原文件 | 原物理页 | 已观察的布局／身份特点与处理影响 |
| --- | ---: | --- |
| 04 - GCT.pdf | 2 | 原生条件正文叠 AcroForm／Widget 填充值。项目身份和左侧引用值不能仅从 page text 读取；固定表单和实际项目填值分别绑定 |
| 05.1(3) - Annex 1 to 6.pdf | 47 | 多个 Annex／表单版次共存，边框和无边框表、合并格及横向材料混合；27–29 页有 `/Rotate=270` |
| 05.1(4) - Appendix H - Info to be supplied by Tenderer.pdf | 55 | Part／section 分组和 offered material 填表；28–31 页连续表含共享／合并表头及空 offer cells，不能每页独立断表 |
| 05.1(5) - Appendix I - Restriction to award tender.pdf | 2 | 封面后是条件正文和影响条件范围的长脚注；脚注与实际父条关联 |
| 06 - FoT (scan).pdf | 9 | 扫描正文、选择支路、签署区；8–9 页有红色新增值和删除线，原值与适用修改关系保留 |
| 08 - AoA (scan).pdf | 4 | articles、不同签署方式及 notes 分区；分支条件须与相应执行内容绑定 |
| APA_Site Boundary Plan.pdf | 8 | 封面／目录／清单、body 空白但仍有框架的页及朝向变化的图纸；不能把空白正文自动当漏解析 |
| APB_List of Contract Dwg.pdf | 61 | drawing ID／revision／title 的无边框清单、分组行和折行；31→32 页 architectural 尾组转 structural 新组 |
| APC_Sketches and Details.pdf | 41 | 图纸、titleblock、小表、照片／uniform 等混合；17 页局部 DELETE／strike／cloud；19 页图与外框方向不同 |
| APL_List of BS Subcon.pdf | 3 | trade 分组和印好的空编号槽；空槽不补成承包商、不当 OCR 漏字 |
| APU_List of Ref Dwg, Doc.pdf | 20 | 全页扫描加不可见旧 OCR 搜索层；reference only 声明、修订、memo 和 correspondence 分组应共同保存 |
| APZ_Commissioning Management Plan.pdf | 6 | 外目录、说明、内 Annex cover／目录；目录引用超出实发物理页，记录 supplied extent，不据此制造范围外缺件 |

S4 底层清点为 106 页 native content／form 家族、132 页无 native chars、20 页扫描叠隐藏 OCR 层，合计 258。它们是对象与布局分类，不是文字准确率。image occupancy 是 200×200 网格 bbox 估算，也不代表像素级覆盖或实际模型接受。

已证实的四类跨文件风险：

1. **隐藏搜索层。** APU 全部 20 页都有全页扫描图和 `Tr=3` 不可见文字；native 字符数为 49–1,471，部分含错词／U+FFFD。可抽文字不足以判定可靠原生正文。旧层与扫描原图分别保留，解析路线需要识别其来源。
2. **表单表示。** GCT 有 10 个 canonical fields、11 个 Widgets，分别在原物理页 1／2 分布 8／3 个；8 个 normal appearance streams 已保存。`Contract Number` 的空值与 `Contract No=20250101` 并存；两个 Widget 从 Parent 继承值。普通 page text 遗漏可见项目名和合同号，空 alias 不能被当作资料缺失。field tree、Widget、appearance 和显示位置分别留证，不 flatten 原件。
3. **局部修改与方向。** APC 17 的 DELETE 只作用于具体局部；APC 19 的图纸侧向而 appendix 外框正向，PDF `/Rotate=0`。文件／整页级旋转、删除状态都不足，需区域坐标与修改关系。
4. **相似外形不决定角色。** APB 31 的 `REF/A/CPK021` 仍处于 contract drawings 清单；APU 3 原文明确 reference only、不是 Drawings／Contract 的组成部分。drawing 前缀、列数或标题相似不能代替采用声明和实际上下文。

Appendix H 的弯引号显示曾在 PowerShell 控制台出现“��”，但直接读取 census 字符码确认原值是 U+2019，该文件 native text 中 U+FFFD 为 0（S4 verification）。这是控制台显示问题，不列为原件或 PDF 抽取错误。

## 2b DOCX：逐文件结构规律

S5 的 23 份原生结构普查记录了段落、section、header／footer、编号定义、表格、合并格、tabs、格式、media 及各原件 SHA。全部已只读渲染为派生 PDF；下表记录原件家族及已观察的结构，不以缓存或派生页数建立原件引用。

代表页目视来自 17 份文件。G304、G305、S301、S304、S305、S306 六份仅完成原生结构与内容身份调查，没有视觉样本；不把同族代表页观察推广为这些原件已目视通过。

| 原文件 | 家族／结构特点与处理影响 |
| --- | --- |
| 02 Cover.docx | 四个 volume 的封面与项目身份元数据；不能由封面证明内含条款或采用关系 |
| 03 - NTT.docx | 条款编号／正文可能位于无可见边框的 layout table；Bill／文件清单另含 spans；20 XML tables、2 sections |
| 05 - SCT.docx | 正文混合 C–G 附录和填表，44 XML tables、13 sections；各附录页码重启，G 有工期表 |
| 05.1(1) - SCT.A_Marking Scheme_DCMBI P04_v4_r1.docx | marking summary／annex 与多 section 表单；与 assessment 文件互相关联但不按相似标题去重 |
| 05.1(2) - Marking Scheme (assessment std).docx | A3 横向四列评分矩阵，合并格、nested table；C.1.1 跨派生页，评分组与父条件相连 |
| 07 - Schedule I to FoT.docx | 价格调整说明、比例表及待填位置；保留字段对齐及空值，不扩大为范围外价格表核算 |
| 09 - CoC.docx | 合约组成／采用目录，原生 tables=0；CoC 与固定 GCC 正文不是同一语义角色 |
| 10 - SCC.docx | Roman 目录与 Arabic 正文页码，TOC／PAGEREF 和手写条款号；3 XML tables、3 sections；目录引用不另建正文条款 |
| 11 - Appendices to SCC FLYSHEET.docx | 附录组成 flysheet，元数据节点 |
| 12 - Appendix G301 - Bond.docx | 保函义务正文后接多方签署表；待填／括号说明与实际签署值分开 |
| 13 - Appendix G302 - insurance.docx | 原生 tables=0，但保险 schedule 由段落、tabs 和字段对齐形成，不能作为单列散文处理 |
| 14 - Appendix G303 - confirmation letters for insurance.docx | 确认函、签署区、italic guidance 和 placeholders；guidance 不是已填项目事实 |
| 15 - Appendix G304 - short form arbitration rules.docx | Articles、子项和脚注；保留编号／义务／脚注关系 |
| 16 - Appendix G305 - declaration of confidentiality, etc.docx | 声明与执行表，印刷身份和字面页脚与动态 PAGE 区分 |
| 17 - Appendix S301 - Notice of consent for XP.docx | consent 信函、指引和空签署位；header 继承与空 first-page header 保留 |
| 18 - Appendix S302 - specimen personal accident insurance.docx | 七 sections、tabs／下划线字段、少量小表及多列 blank labels；Specimen 不代表评价答案 |
| 19 - Appendix S303 - warranty for play equipment.docx | warranty 正文及多方执行表；与 S304 共用 SCC7.307／EXT10，按自身 subject 与附录号区分 |
| 20 - Appendix S304 - warranty for IASM.docx | 相似 warranty 家族及六执行表；共享引用不证明与 S303 相同 |
| 21 - Appendix S305 - warranty for roofing.docx | warranty 家族、六执行表、待填和 guidance；与 S306 共用 SCC7.304／WAT6 |
| 22 - Appendix S306 - warranty for waterproofing.docx | 按自身 waterproofing subject／附录身份与 S305 区分，不按相似 boilerplate 覆盖 |
| 23 - Appendix S307 to S309 - Not used Flysheet.docx | 明确 Not used 的范围节点，标题与状态共同保留 |
| 24 - The Specification.docx | GS／SL 采用与 PSS 组成关系；PRE 保留，工程技术 PSS 直接排除；版本声明不由最新标准替代 |
| 25 - Specification - PRE (base tender).docx | 项目 PRE 正文和复杂附录：174 XML tables、49 sections、33 media；`.P`／`.9`／`.N`、图表、校准和示例报告共同存在 |

这些规律可用于结构解析和身份判断，但不能硬编码样板项目值、某一页码或某个标题外形。不同版次、修改值和新项目重新读取实际原件。

原生／渲染核查的关键差别：

- **XML table 不是数据表的同义词。** NTT 的条款排版表、SCT 表单、评分矩阵、目录与实际数据表的语义不同；不能普遍把第一行当表头。大多数表缺 `repeat-header` 标记，也不能据此切断跨页表。
- **格式仍有合约意义。** 23 份原件 tracked markup 计数为 0，但 PRE 有直接 strike，G303／warranty 有明确 italic guidance。PRE 的 EPS-F4 代表派生页中 RSE 被划掉；保留实际格式及适用说明。没有 tracked change 不代表没有修改语义。
- **缓存页不是原件分页。** NTT 的 cached Pages=12、本轮 LO 派生 13；SCT 38→49；PRE 139→671（S5 render receipt）。这些差异只说明缓存与本轮 renderer 不同。LO 页绑定 renderer、字体观察和配置，不称为原 PRE 权威 671 页。原件引用以稳定段落／表格／cell 身份为主。
- **内部示例与项目事实分开。** PRE 的代表派生页 524–525 明写 `for illustration only`／`Sample summary table`／`Sample chart`，含日期、35%、26,504 等完整样例值。保留这些内容和图表，但不能写入本项目事实或因此排除整份 PRE。
- **跨页父子关系。** PRE 代表派生页 669 的 B12.920.P 标题与 670 的 capacity／location 表同属关联结构；标题留在上一页不能使表变成无归属片段。

首轮长路径 LO 转换失败的日志和收据仍保留；随后短派生路径转换成功。字体观察不等于已经证明所有字体无替代，派生渲染也不证明模型结构输出正确。

## 上传链路：身份路由缺口有源码依据，尚无在线复现结论

现有链路已有角色／范围区分、原生块／bbox、质量未验证状态和 Drafting 来源绑定可复用能力。现有 PDF parser 计算 bbox 时使用 direction／pageRotation，但普通 DocumentBlock 没有持久化通用 direction／rotation／transform；optional probe 的页级 rotation／cropBox 不能当作全链坐标变换契约。S6 的逐文件 SHA 快照保存本轮读取的代码版本；下列源码行号在该快照中定位，后续代码变化应重新核查。

| 当前行为与位置 | 业务影响／待讨论的接口要求 |
| --- | --- |
| [VettingService](../src/main/java/com/consense/service/vetting/VettingService.java)，114、603–611 行：解析前按原文件名分配 fileKey | 文件名不能充分证明文件家族、版次及采用关系；原字节、标题、编号结构和资料包证据需要进入身份记录 |
| [VettingCorpus](../src/main/java/com/consense/service/vetting/VettingCorpus.java)，83–95 行：未知 auto 角色默认 tender；CoC key 为 GCC 但 role 有 tender 特例 | kind、role、lifecycle 和 scope 是不同轴；明确 unresolved 身份，避免默认冒充已确认 |
| [VettingController](../src/main/java/com/consense/web/VettingController.java)，27–37 行；[VettingView](../../ai_consense_web/src/views/VettingView.vue)，482–496 行：同批共用 role／scope | 混合上传缺逐文件身份复核／依据展示；一个批次默认不能替代每件分类 |
| [SourceDocument](../src/main/java/com/consense/domain/SourceDocument.java)，27–88 行 | 现有持久字段缺通用原字节 SHA、edition／adoption、分类依据及区域 route acceptance；optional probe SHA 没有贯通来源目录 |
| [ProjectDocumentScope](../src/main/java/com/consense/service/sources/ProjectDocumentScope.java)，27–34 行：规范化 filename 取最新 | 同名异版与改名同源不能靠这一策略解决；最新失败项不回退旧文本的已有语义须保留 |
| 同文件 48–50 行的 filename 排除规则 | 真正技术／合约 checklist 可能被当 example；这是源码级失败模式，本轮没有实测错误上传或修改规则 |
| [DocumentParser](../src/main/java/com/consense/document/DocumentParser.java)，227–257 行：文字量／乱码／image 尺寸启发式 | `visible>=200` 且乱码未超阈值时不查 image，也不查 `Tr=3`；APU 类型存在跳过扫描恢复的可能，尚未统计 Java 实际 bypass 页数 |
| 同文件 156–194 行；[OcrClient](../src/main/java/com/consense/ocr/OcrClient.java)，19–24 行：整页 OCR，text＋confidence＋line bbox | 页级接口不足以表示混合区域、完整 cell/span、表组和跨页拓扑；不能只替换模型名称就宣称完整 parser 已接入 |
| [VettingDtos](../src/main/java/com/consense/web/dto/VettingDtos.java)，21–36 行；[前端 types](../../ai_consense_web/src/api/types.ts)，459–483 行；VettingView 1113–1123 行 | 展示操作状态／warnings，未展示字节身份、版次、分类依据、scope 决策及区域规范接受状态；parsed 不等于业务质量通过 |

[VettingSourceQuality](../src/main/java/com/consense/service/vetting/VettingSourceQuality.java) 的 `textAccuracy` 当前仍为 `unverified`，没有把 native 声称为已验真。缺口是来源识别与恢复路线，而不是已实证的“代码把原生文字标成正确”。canonical `sourceHash` 为文本＋结构身份，不能替代原件 `sourceSha256`。

upload result 的 `parsed` 计数包含 PARTIAL；成功 OCR 的待复核状态也可能令结果为 PARTIAL。因此 accepted／parsed／failed 是操作统计，不是质量通过统计。表中展示缺口限于上传和文件列表；其他 finding evidence 已有 documentId／canonical sourceHash，不能说整个系统没有任何来源映射。

以上结论来自源码与原件各自核查。调查没有运行 APU Java 上传、访问业务数据库或对当前 UI 做在线验收，因此不能把潜在失败模式写成已复现的当前页面错误。

## 真实模型结果：两路证据都需门控

S7 保存原始输出实际字节／SHA、assets、原 PDF 页映射和最新 projection2 包；初版包及失败记录继续保留。输入 PNG provider 的页 1 分别映射到 AoA 原物理页 1、APB 原物理页 3，不能丢失原页身份。

| 实测 | 结果及保留限制 |
| --- | --- |
| MinerU Standard AoA／APB | 完整页路线已运行；阶段推理约 137.646s／340.021s（CPU FP32 配方），23 次 VLM 请求返回 200，未观察 generation 预算截断；不代表质量接受或 H800 性能 |
| MinerU APB | 漏 GENERAL NOTES 的 GN-01／GN-02 两行与左下 contract footer；drawing 主表能出结果不代表全页完整 |
| Paddle AoA | 完整 layout／recognition 已输出；后续 crop exporter 失败，首次绘图触发字体下载；该 run 不计严格离线成功 |
| Paddle APB | 固定字体后的 CPU FP32 推理约 881.967s，networkAttempts=[]；drawing 身份覆盖 25／25，但原件 `COMPLTION` 被识为 `COMPLIION`，不记全文正确 |
| 两路当前规范状态 | `routeAcceptance=unknown`、`canonicalSelection=null`、`humanConfirmed=false`；原文拼错保留，不用语法修复抹掉来源 |

AoA 的 Paddle 早期 token trace、MinerU 部分 small ONNX tensor 未捕获，以及部分文本投影不可用均记在 S7。HTTP 200、产物齐备或高 confidence 各自只证明运行事实。

真实 BGE 神经 encode／index／rerank 尚未运行。双粒度结构核心 107 项测试、Java 聚焦 30 项、wrapper 63 项、model helper 5 项等是各自契约／隔离测试记录，不能合并当作解析、检索或业务成绩；旧 fixture 缺失导致的 24 个扩展 errors 继续保留（S8）。模型已下载不代表全链路部署。

## 整体方案讨论的输入

已经确定的范围、clause no 路线、双完整 parser 证据保留、长结构＋短窗口、同池排序评价、Codex 原件标注、文件家族留出及局部 unknown 规则不再重复询问。本次调查支持讨论下面的具体接口和实施取舍；这些仍是设计输入，不写成已定或已部署架构。调查后的 Q9 已明确标准模板仅供 Drafting、Vetting／Advisory 共用项目原件与适用参考；Q10=A 先用文字关联回取正确原图／表组。上文关于箭头、连接及局部删除指向的原件发现保留为事实，不转化为首版自动理解目标。

| 设计问题 | 原件／现有链路支持的要求 | 仍需设计或实验解决 |
| --- | --- | --- |
| 上传身份路由 | 固定字节可精确识别；kind、role、edition、adoption、scope、内部 section role 独立；保存实际依据 | 新文件如何组合 native／视觉／包内证据，何时 unresolved、如何逐件复核及更新来源版本 |
| 原生与扫描恢复 | DOCX 原生结构优先；PDF 需考虑 hidden layer、form、混合区域及朝向；扫描保留两套完整输出 | 快速身份探测与完整解析如何分阶段；区域范围、fallback 与未知传播接口 |
| 结构与定位 | SL 条款主层级、无编号续文、表组和图像；NTT layout table、SCT 附录、PRE 内部示例；原坐标可追溯 | 统一来源单元能表达哪些关系，native 与视觉输出如何对齐，完整表组采用边界 |
| embedding／rerank | 窗口用于定位，结构用于条件／例外联合判断；编号和引用图可补候选 | 在确认边界的参考样本上选择实际长度、融合、候选预算和模型调整；不预设 2048 最优 |
| 质量门控与模型调整 | 两路都可能错误；原文拼错不是识别纠错目标；分层归因 | 原件标注集／家族留出、必要证据集与分级同池标签，何种残留错误值得目标微调 |
| 预部署与比赛替换 | 固定六来源按用途提前精修：三份标准模板只供 Drafting，GCT／GCC／SL 按采用范围供 Vetting／Advisory 共用；项目实际 NTT／SCT／SCC 继续审查／咨询；新上传读取实际内容，范围外不补齐 | 模块准入、缓存失效／revision／index identity、赛场调度与真实 H800 共驻资源验收 |
| 前端可验收 | 用户须能看到身份依据、未知影响及回到条款／表组／原页 | 哪些默认自动采用、哪些只提示局部复核，应用审阅与咨询引用如何回跳 |

调查阶段的产物是范围、原件规律、失败证据和现有接口差距。下一步与用户沟通整体方案，再按确定的方案实施和验收；尚未进行的真实检索、全资料精修、目标微调与在线应用验收继续明确为待完成。

## 证据索引

下列本机机器证据在忽略的 `.local`，不随 Markdown 提交。每份 JSON 保存来源身份／位置或 artifact SHA；异机接手不能把本机链接当作证据已迁移。

| 标识 | 证据 |
| --- | --- |
| S1 | [source inventory](../../.local/state/oer-implementation-20261009/source-inventory.json)；[部署清单](../../docs/oer-source-deployment-manifest-20261009.md) |
| S2 | [SL scope manifest](../../.local/state/sl-scope-bookmarks-20261009/sl-technical-scope-manifest.json)；[25 页边界 receipt](../../.local/state/sl-scope-bookmarks-20261009/sl-technical-scope-receipt.json) |
| S3 | [SL 独立条款调查](../../.local/state/sl-scope-bookmarks-20261009/independent-clause-hierarchy-audit.json)；[root 6 页观察](../../.local/state/sl-scope-bookmarks-20261009/clause-structure-visual-review-20261009.json)；[候选跨度 token receipt](../../.local/state/sl-scope-bookmarks-20261009/com2-m005-page-span-token-count.json) |
| S4 | [PDF 逐件调查](../../.local/state/oer-implementation-20261009/2b-layout-investigation/pdf-layout-investigation.json)；[逐页视觉 ledger](../../.local/state/oer-implementation-20261009/2b-layout-investigation/pdf-layout-review-ledger.json)；[GCT field／Widget](../../.local/state/oer-implementation-20261009/2b-layout-investigation/gct-acroform-widget-evidence.json)；[核验 receipt](../../.local/state/oer-implementation-20261009/2b-layout-investigation/report-verification-receipt.json) |
| S5 | [DOCX native summary](../../.local/state/oer-implementation-20261009/2b-layout-investigation/docx-native-summary.json)；[逐件差异矩阵](../../.local/state/oer-implementation-20261009/2b-layout-investigation/docx-file-difference-matrix.json)；[只读 render receipt](../../.local/state/oer-implementation-20261009/2b-layout-investigation/docx-render-receipt.json)；[39 页最终视觉观察](../../.local/state/oer-implementation-20261009/2b-layout-investigation/docx-visual-observations.json)；[最终核验 receipt](../../.local/state/oer-implementation-20261009/2b-layout-investigation/docx-investigation-receipt.json)；[字体观察](../../.local/state/oer-implementation-20261009/2b-layout-investigation/docx-render-font-observations.json) |
| S6 | [现有上传路由源码调查](../../.local/state/oer-implementation-20261009/document-routing-current-state.json) |
| S7 | [projection2 双解析核验](../../.local/state/oer-implementation-20261009/dual-parser-evidence-projection2/verification-receipt.json)；[AoA bundle](../../.local/state/oer-implementation-20261009/dual-parser-evidence-projection2/aoa-page1.json)；[APB bundle](../../.local/state/oer-implementation-20261009/dual-parser-evidence-projection2/apb-table-page3.json)；[MinerU 真实运行 receipt](../../.local/state/oer-implementation-20261009/parser-runs/mineru-standard-http-fp32-cpu-attempt2/receipt.json)；[Paddle APB 真实运行 receipt](../../.local/state/oer-implementation-20261009/parser-runs/paddle-native-fp32-cpu-offline-apb/receipt.json)；[APB 原件／漏项核读](../../.local/state/oer-implementation-20261009/mineru-real-pages-quality-review.json) |
| S8 | [handoff 实施状态](./HANDOFF-ocr-embedding-rerank-20261008.md)；[parser 环境状态](../../.local/state/oer-implementation-20261009/parser-environments-status.json) |

DOCX 的早期 [代表页映射](../../.local/state/oer-implementation-20261009/2b-layout-investigation/docx-sample-page-receipt.json) 保留当时 pending 状态；最终视觉结论以 S5 的新观察及新收据为准。S6 快照中的中央规则／PDF census manifest 后来有更新，业务源码 SHA 经独立复核未变；原 JSON 保留其历史绑定，不追认成最新文档快照。

本次汇总的原件／范围／文档链接核验见 [v2 receipt](../../.local/state/oer-implementation-20261009/source-inventory-scope-validation-20261009-v2.json)；[独立审计记录](../../.local/state/oer-implementation-20261009/investigation-independent-audit.md) 记录原件与源码核对及其限制。早期收据及旧文档 SHA 继续保留。
