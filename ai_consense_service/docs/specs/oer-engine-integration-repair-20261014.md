---
title: OER 解析引擎整合与来源保真修复
status: ready-for-agent
labels: [ready-for-agent]
date: 2026-10-14
timezone: Asia/Shanghai
authority: 本轮用户已确认的整套引擎修复方案、质量优先及通用性约束
execution: spec-only; first-identity-fix-recorded; remaining-work-not-started-by-this-spec
tracker: local-markdown
---

<!-- implementation-progress-20261015:start -->
> **2026-10-15 当前实施状态：** 统一实现与适用代码/本机环境自动化已收口，实际通过、123 条件 skipped、历史排除及九项 retired 见[统一实施记录](../testing/consense-integrated-implementation-20261015.md)。原 D1–D10／EAC01–22／ENG-00–10 完整保留；正常原件前后效果和独立来源质量未验收，按用户最新要求不开始，ENG-10 后置。原 08:30 目标未达，完整目标未 complete，下方 `spec-only` 和旧进度按历史语境读取。
<!-- implementation-progress-20261015:end -->

<!-- unified-spec-entry-20261014:start -->
> **2026-10-14 当前总体实施入口：** [统一实施与验收 spec](consense-oer-vetting-advisory-integrated-20261014.md)及[统一任务图](consense-oer-vetting-advisory-integrated-tickets-20261014.md)。OER 原 D1–D10／EAC01–22／ENG-00–10 按[受保护引擎子规格](oer-engine-integration-repair-20261014.md)完整保留；产品问题 PRD01–08 与旧总 AC01–20 在统一入口逐项承接，业务成功不关闭 OER 来源质量门。下方原正文与状态保留原语境，已实现起点不重列未修，未验收／失败不追认通过。本次只调整规格与导航，Advisory 已停止测试保持，不启动实现、模型、训练、索引、自动 Goal 或部署。
<!-- unified-spec-entry-20261014:end -->

## Problem Statement

使用者需要把实际 PDF、扫描文件及 Word 交给一套协同工作的解析引擎，取得完整、可定位、可追溯的原文和结构。现有原生工具、两套视觉解析器、布局检测及表格工具已有能力，但正常入口中的执行顺序、观察数据传递和结果组合尚未接通。缺失输入、裁图上下文、字段归属、跨页关系或导出接缝的问题，曾被混同为需要继续训练的模型残差。

当前源码已确认：Java 上传流程先完成解析再登记身份；旧扫描接口传递文字及行框，未承载完整布局区域、原始多边形与模型来源；混合页可能因已有较多原生文字而漏读局部扫描区域。已复核 canonical 投影可以使用来源参考框、读序和关系，因此这类采用结果不能证明新原件的自动恢复能力。

文件名直接排除来源、由候选 fileKey 推定角色和 PDF metadata 直接确认为图纸的第一批缺陷已完成局部修复。剩余目标是把正常解析链修好，并对完整来源质量验收；增加实验轮数或使 JSON 合法不能代替该目标。

## Solution

复用现有 Java／Python 引擎、原生文档工具、双解析器规范化、完整结构组选取及 canonical 来源单元，形成以下正常工作流：

**原件登记 → 初步 native／OCR／layout 观察 → Java 确认文件身份与版本 → 阅读规则安排任务 → 按缺口补读 → 同源坐标与完整结构整合 → 最终来源单元、原文回取及必要的下游投影。**

原生 PDF 优先取得实际文字、几何、表格及控件；Word 优先使用原生包结构；扫描和混合页按实际区域使用 OCR、布局及专用识别。身份识别复用首轮观察，阅读规则定义读取方法和关系，不预置当前原件答案。无法确认的部分保留原文、候选和真实缺口，按影响范围暂停依赖它的判断。

沿用已确认的公开业务验收流程，主接缝在本规格中聚焦为：**真实文件上传 → 正常解析作业 → 持久化身份／目录状态／完整来源结构 → 回取对应原件与定位 → 核验下游接口实际消费。** 检索效果、Vetting／Advisory 的合同判断及部署分别验收，不纳入本规格的解析质量分数。

## User Stories

1. As a document user, I want native PDFs read with their actual text and geometry, so that clear digital text is preserved accurately.
2. As a document user, I want Word files read through their native structure, so that tables, numbering and revisions retain their meaning.
3. As a document user, I want scanned files observed before Java identity routing, so that routing can use actual text and layout evidence.
4. As a document user, I want mixed pages checked for uncovered visual regions, so that a native paragraph does not hide a scanned table or form.
5. As a document user, I want document family, source role and edition distinguished, so that a familiar title does not imply project adoption.
6. As a document user, I want full titles, form identifiers and version evidence used for identity, so that routing can generalize beyond sample filenames.
7. As a document user, I want filenames and PDF metadata retained as candidates, so that weak hints cannot discard legitimate material.
8. As a document user, I want uncertain identities visible with their originals, so that pending files do not disappear from the catalog.
9. As a document user, I want confirmed drawing files excluded before detail parsing, so that drawing content stays outside the agreed scope.
10. As a document user, I want uncertain drawing candidates paused without a false classification, so that neither exclusion nor detail reading is guessed.
11. As a document user, I want drawing lists and textual references preserved, so that mentioning a drawing does not exclude a text document.
12. As a document user, I want explicit scope and confirmed answer isolation respected, so that package labels cannot bypass protected boundaries.
13. As a document user, I want reading rules to describe roles and reading methods, so that they do not substitute stored answers for my file.
14. As a document user, I want first-pass observations reused, so that identity routing does not repeat the same recognition task.
15. As a document user, I want supplemental inputs to include necessary titles and shared prefixes, so that models are not asked to infer missing context.
16. As a document user, I want each field occurrence kept distinct, so that repeated Name or Address labels do not merge different parties.
17. As a document user, I want field type, writing state, value, owner and location evaluated separately, so that text recognition does not imply a complete form.
18. As a document user, I want unknown writing states preserved, so that empty OCR is not interpreted as unfilled, No or not applicable.
19. As a document user, I want checkbox states distinguished from written values, so that different control types are not conflated.
20. As a document user, I want complete tables and their notes preserved, so that headers, units, exceptions and blank cells remain attached.
21. As a document user, I want continuation lines and cross-page groups retained, so that an OR branch or qualification is not lost at a page boundary.
22. As a document user, I want reliable native observations retained during OCR supplementation, so that a second channel does not erase correct text.
23. As a document user, I want complete dual-parser outputs and disagreements retained, so that adoption can be audited against the original.
24. As a document user, I want complete regions or table groups selected coherently, so that per-cell combinations do not invent a new structure.
25. As a document user, I want locations mapped to the correct source frame, so that crops, rotation and scaling do not highlight the wrong region.
26. As a document user, I want Word body, headers, notes and revision states distinguished, so that unrelated stories are not flattened into one reading order.
27. As a document user, I want source text separated from normalized text and generated labels, so that original wording remains recoverable.
28. As a document user, I want every final unit tied to its actual original and observations, so that matching dimensions or filenames cannot substitute for identity.
29. As a document user, I want changed versions to invalidate incompatible cached results, so that an old page range or box is not applied to new bytes.
30. As a document user, I want incomplete parsing identified by affected regions and relationships, so that missing coverage is not presented as success.
31. As a document user, I want complete source units passed downstream, so that transport does not drop fields, relations or qualifications.
32. As a developer, I want raw glyphs, operators and audit receipts kept in the evidence layer, so that compact model input remains useful and traceable.
33. As a developer, I want source-reviewed adoption distinguished from automatic parsing, so that assisted results do not inflate generalization claims.
34. As a project owner, I want complete-source comparisons with unchanged denominators, so that local gains do not conceal remaining errors or unknowns.
35. As a project owner, I want previously correct results preserved, so that a candidate cannot improve one field by regressing another.
36. As a project owner, I want reference annotation and independent review kept separate, so that producer output is not used to grade itself.
37. As a project owner, I want training limited to demonstrated model residuals, so that missing input and orchestration defects are fixed at their source.
38. As a project owner, I want ineffective iterations stopped and reviewed against history, so that loss reduction alone does not justify repeated training.
39. As a project owner, I want software tests, actual model execution, source quality and browser acceptance reported separately, so that progress is understandable.
40. As a project owner, I want originals, fixed05, prior failures and Drafting behavior preserved, so that engine repairs remain reviewable and reversible.
41. As a project owner, I want SL version collection deferred until the engine is repaired, so that expanding the dataset does not distract from integration.
42. As a project owner, I want future SL versions isolated by actual bytes and lineage, so that near-duplicate editions do not leak across training and evaluation.

## Implementation Decisions

### D1. 复用现有引擎，区分观察、身份和最终产物

- 原生 PDF 继续使用 PDFBox 文字、glyph、页面 frame、widgets 及已有原生表格观察；Word 继续使用 POI／OOXML。视觉路线保留 PaddleOCR-VL、PP-DocLayout 与 MinerU 完整解析能力；Camelot／pdfplumber 作为原生文字层表格候选，不替换全局 provider。
- 优先在现有 DocumentParser 内组织初步观察和按身份补读，复用 ParsedDocument、DocumentBlock、native evidence、规范化及 canonical 单元，不新增平行文档树、路由平台或缓存服务。
- 初步对象保留真实原件 SHA、总物理页数及已观察范围；首轮只观察部分时 coverage 不完整。初步观察不能作为最终 PARSED 结果持久化或授予完整来源质量。
- 已确认非图纸但文档子类型未定时，可以使用受范围约束的通用文本读取规则；未知类别、用途、版本及适用关系继续分别保留，不能互相补造。

### D2. 身份先行及候选／确认边界

- 将文件类别、文件身份、来源角色、内部内容角色、适用文件版本和项目采用关系分开。确切 SHA 证明原件字节身份，不单独证明项目法律采用。
- 正式标题、表单编号、文号、版本、粗布局与实际内容可共同支持身份。文件名、展示 fileKey、PDF Title／Subject、孤立的 TITLE 字段或正文关键词保留为线索；不能单独决定最终排除、来源角色或附录身份。
- 扫描来源先取得身份所需 OCR 文字和布局，再交 Java 统一判断；原生 PDF／Word 复用原生观察。身份观察与后续识别共享同一作业和输入身份，不先做完整细读再把事后登记称为前置路由。
- 身份证据不足时只补必要身份观察或进入待确认。待确认文件在目录可见，原件、SHA、候选、原始提示及已有观察可回取；未观察的字段、页数和质量保持未知。
- 已确认答案／模板／模块排除优先于业务准入。显式 tender／package 不能绕过 IDENTITY_PENDING；来源用途的明确选择不等于证明内容不是图纸。既有合法用户角色仍保留，不因候选状态覆盖用户输入。
- 2d／2e 官方评价答案、countercheck、样板问答及流程图维持已确认隔离，不进入业务依据、模型上下文或解析训练目标；仅评价用途的参考答案与定位信息不得暴露给 producer 或训练准备方。
- 旧 canonical 角色或来源绑定不匹配时保持 stale／拒绝，不通过文件名恢复旧默认角色。目录展示与业务准入分别使用既有 catalog／selection 语义。

### D3. 图纸只做身份判断，待确认不等于已识别

- 对已确认图纸仅做身份标记并排除详细解析；不得进入图纸文字细读、尺寸、轴线、图例、符号、CAD 几何或工程关系识别／训练。身份级 OCR／粗布局用于必要的路由观察，不扩成细节解析。
- 显式 drawing 范围和已支持的原生 CAD 格式证据继续有效。弱 PDF metadata 只形成 UNKNOWN 身份下的 drawing 候选并保留原值；可信确切字节的非图纸身份可以覆盖弱提示。
- 必须覆盖无 metadata 图纸候选的正常入口反例。无法确证为范围内非图纸时保留 pending，不用普通详细 parser 代替身份判断；pending 不能计为自动图纸识别成功。
- 文本合同、图纸清单及提到 drawing ID 的规范仍按实际文件身份读取；不能按附录代号、清单标题或引用文字将其整份判为图纸。混合文档／附件按实际来源对象和范围判断，不能扩大整份排除。
- 主工程和独立交付的实际能力分别记录。交付版已有 metadata pending 分支，不表示主工程已具备同一自动入口；实施应在目标集成版本中补齐并验证。

### D4. 扫描观察契约与兼容性

- OcrResult／client／ocrBlocks 现有 text、confidence、lines 及文字 bbox 保持兼容；增加必要的可选布局区域与 frame／provenance 观察，保留旧构造和旧 wire 调用的可用性。
- 同步验证真正上游输出、HTTP 传输和 Java 消费。只给 Java 增加字段、继续调用仅返回文字行的旧上游，不构成完整布局接通。
- 每个 region 至少保留实际 ID、kind、物理页、provider 原文、规范化文字、原 bbox／polygon、输入 frame、标准化位置、来源 channel 及原始响应引用。可用的读序、父子、表格观察保留为候选，不自动授正确语义。
- 每次观察绑定原件 SHA、输入图像身份与尺寸、crop／旋转／缩放到原页的变换、engine／model revision／config、任务及真实终态、覆盖和缺口。PDF 保留实际页面 frame；Word 保留原生部件与位置。
- 原始返回、normalize 后文字和生成的结构标签分开保存。没有识别文字但有实际 layout／表单控件观察时不能丢弃，也不能凭此认定原件空白。

### D5. 混合页覆盖、上下文与同源坐标

- 已有较多 native 文字不证明整页内容完整。对局部扫描表格、控件、图像文本及损坏／隐藏 OCR 层，按实际区域覆盖和来源资格决定补读。
- 补读先保证完整语义输入：所属标题、共享前缀、栏目标签、写区、父条、表头、单位、续行、Notes、条件及例外。裁图外的上下文不能交给模型猜；缺输入先补输入或保留缺口。
- 原生和视觉观察按同一来源、页、实际 occurrence 及坐标变换整合，正确 native 内容不能被整页 OCR 替换。不同位置的重复文字不因相同字符串合并。
- 校验 CropBox／MediaBox、rotation、UserUnit、像素尺寸及 crop 到原页变换。仅归一化、clip 或图像尺寸相同均不能证明同源与定位正确；保留原 frame 和可验证变换。
- 同一作业复用有效观察。跨作业复用须绑定实际原件、图像、任务、模型／配置及所需代码身份；缓存命中不授予来源有效性或质量通过。

### D6. 阅读规则与完整语义结构

- 阅读规则可按已确认的文档族／版本定义通用标签、编号语法、栏目角色、父子、读序、写区与分支关系；不得编码当前源值、预期空白、样本答案、特定 SHA 到答案、固定物理页或固定裁图坐标。
- 冻结且原件 SHA 绑定的历史页范围、来源参考标注和库存断言保留，只用于该版本的显式范围／采用／完整性验证。新版本须重新取得边界，不套用旧页号或旧框。
- 通用几何阈值、编号和版式语法可产生候选，须结合当前原件实际锚点并经来源验证；不能将其与特定样本页／值的查表规则混淆，也不能把阈值命中当正确证明。
- 表单分别保留类型、填写状态、原文字面值、定位、重复出现、所属角色、选项和文档关系；通用 Name／Address 标签本身不证明角色。子写区的已填、未填、未知状态不可被整字段一个标签吞并。
- 空 OCR、印刷 placeholder 或单一 mask 不能证明 unfilled；checkbox 与文字写区分域。未读、缺上下文、未关联、冲突及不支持分别保留，不变成 No／不适用或模型负样本。
- 原生表格候选恢复逻辑行列、多行表头、合并、空格、续文和完整条件组；缺整列或 glyph 成员时回查来源覆盖，不只整理已返回网格。跨页父条、OR、Notes 与例外保留完整关联。
- Word 保留正文、页眉页脚、脚注尾注、编号、表格 topology、符号、修订及有效／原始文字的语义。不同 story 不简单接到正文尾部；无可靠排版依据不编造 Word 物理页，不以旧 .doc 的较弱观察冒充 OOXML 完整支持。

### D7. 双解析器采用与补读

- 落实既有双解析 ADR：保存两路完整输出和分歧，以经来源验证的通用特征选择完整 region 或完整表格组。不得逐格／逐字段拼接分支最佳答案，confidence 较高或两路一致不构成正确证明。
- 第二路参与由可观察的缺覆盖、截断、缺关键格式、角色／结构冲突等触发。补读保留完整上下文、触发原因、输入、配置、结果、终态和采用依据，不盲目让两引擎对全文件反复重跑。
- 新候选失败、超时、协议非法或证据不足时保留真实状态和旧完整结果，不覆盖旧正确。覆盖可靠且结构一致的区域可继续，受影响且无法界定范围的结果保持不完整。

### D8. 完整来源单元和下游接口

- 最终 parser 输出沿用 canonical units／parts／relations、完整字段／表格组、source refs 及覆盖状态。验证观察、组装、传输、持久化和正常 consumer 各环节没有静默丢项。
- 原文、识别观察、结构推断及阅读规则元数据分别表示；不得把生成的角色名、预期值或参考答案写进原始引用正文。
- 下游模型接收实际选定的完整来源单元、必要 parts／tableGroups／relations、unknown 和原件定位。glyph、PDF operators、原始大响应、CAS 与软件审计留证据层，按需引用，不整树塞入 prompt。
- embedding 继续消费原文窗口，结构完整父单元与来源单独保存。本规格仅保证接口保真与完整消费，不调整召回、rerank、合同适用推理或业务答案规则。
- 局部缺证遵守已有不确定性 ADR：标出受影响对象及覆盖缺口，停止依赖判断并保留可靠部分；身份／同源或缺失影响无法界定时阻断相关整体消费。

### D9. 质量优先及必要训练

- 以原适用完整来源、完整语义输出、正常最终 parser 消费、旧正确保持、不同作者复核和独立来源泛化为质量依据。普通 CER≤0.1%、关键文字零错漏、表格／表单／定位及跨页关系分别验收；文本 CER 不能替代结构或状态完整性。
- 固定对照的范围与分母不得缩减；正确、错误、未知和未执行分开。未测保持 unknown／unscored，不写成零错误或零分；局部进步不关闭整体来源门。
- 自动恢复、Codex 参考标注、独立复核及 source-reviewed 采用分别计量。参考框、读序、glyph partition、table topology 和关系投影不得当同源自动预测或独立来源泛化分数；结构合法、原件 SHA／CAS 一致、进程完成均不授来源正确。
- 先把错误归因到原件获取、覆盖／裁图、识别、角色／组装、传输或 consumer。只有工具、规则和接口修好后仍存在、且有完整合格监督的模型残差才安排训练。
- 训练采用明确数据／校准／评测隔离、精确运行计划及有限预算；默认 BF16 与必要 FP32 状态，不盲升精度或扫参数。连续三次可比实际运行均未改善完整来源质量，且关键缺陷数也未减少时，停止同方向追加并复盘历史；prepare／数据调查不重置该计数，loss、首 token 和 JSON 合法仅作中间信号。
- 数据准入按完整来源、表单家族／版本及实际重叠内容分组，不能把同一出版集、近重复版本或相邻页拆成伪独立训练／校准／评价来源。评价专用答案、框和裁图隔离；来源标注完整性和不同作者复核通过后才准入，不把预测直接当监督真值。

### D10. 交付与阶段

- 本次只写 spec 和本地任务图，不启动后续代码、模型、训练、索引或部署。已有第一批修复作为受保护起点，不重复实现或把其测试追认为整链质量通过。
- 实施顺序为：观察和布局契约 → 身份先行及阅读规则 → 混合区域覆盖／同源组合 → 完整语义及正常消费 → 完整来源与独立复核；测试环境缺口可在隔离环境并行处理。
- 保留原件、模型、gold、失败、fixed05、既有 dirty 代码及 Drafting 行为。来源、模型、规则或配方变化建立可追溯版本，不能覆盖旧证据或静默重绑。
- SL 多版本收集／比较按用户最新要求延后至引擎修好。未来各版按原件 SHA、版本与来源谱系隔离；相同／近重复条文先分组，不把邻近版次随机拆成训练与评测或宣称独立家族。

## Testing Decisions

主测试接缝沿用用户此前已确认的公开上传与来源回取流程。本规格细化其解析段，不新建仅供测试的解析入口；从正常作业读取持久化原件身份、目录、覆盖、最终结构及下游实际消费。必要的坐标／frame、观察复用和兼容性反例可以在现有适配器补定位测试，但 helper、mock、单页 JSON 或手工采用结果均不能替代主链验收。

已有先例包括上传边界与真实 MVC／JPA 目录测试、原件登记及路由刷新、双解析规范化、native projection 与传输契约、完整源文件回取和实际 Vue／i18n 组件检查。契约替身验证外部行为；真实模型调用、完整原件质量、浏览器和目标运行资源分别留证。

| ID | 场景／可观察行为 | 验收条件 |
| --- | --- | --- |
| EAC01 | 相同正文或确证身份，仅改文件名、候选 fileKey、附录名 | 最终身份／角色／范围不被弱线索改变；无身份者保持候选／待确认，原件和目录可回取。 |
| EAC02 | 已确认答案／模板，另标 package／tender；显式 example／pricing／works_pss | 已确认隔离仍生效，不进入可信业务源集；不把排除对象列为必需输入。 |
| EAC03 | pending 与 confirmed 混合列表、同名较新失败／排除上传 | pending／排除行可见且状态准确；business selection 不含 pending，不复活同名旧成功版本。 |
| EAC04 | 弱 drawing metadata，auto／package 和显式 tender／standard；可信 exact-SHA 非图纸控制 | 未确证者 UNKNOWN＋候选＋IDENTITY_PENDING，原提示保留，0 详细 parser 调用；可信非图纸可走正常路线。 |
| EAC05 | 显式图纸、原生 CAD、无 metadata 图纸候选、文字图纸清单及正文 drawing 引用 | 确认图纸不细读；身份不足不偷跑细读；文字清单／引用不误排；pending 不记为图纸正确识别。 |
| EAC06 | 原生 PDF、Word、扫描来源从正常入口执行 | 顺序确为初步观察→Java 身份→阅读规则→补读→最终输出；首轮局部 coverage 不冒充完整。 |
| EAC07 | 相同首轮观察被路由／补读复用，及不同 crop／版本／config 反例 | 相同有效观察不重复调用；输入或身份改变不能误用旧观察，计数与真实任务对应。 |
| EAC08 | 真正 OCR／layout 上游到 HTTP、Java、持久化；旧响应与零文字有控件响应 | 可选结构、原文、frame 和 provenance 完整到达；旧调用兼容；非文字观察不丢弃。 |
| EAC09 | 有较多 native 文字但含局部扫描表格／表单的混合页 | 缺口被补读且完整上下文进入；已有正确 native 字面和位置保持，覆盖账不漏该区域。 |
| EAC10 | CropBox／rotation／UserUnit／缩放／裁图和相同尺寸不同原页 | 原始与标准框可回实际原件；不同页不能混绑，不能靠 clip 令错误框看似合法。 |
| EAC11 | 相邻重复标签、共享前缀、标题在原 crop 外、字段续行 | 输入和输出覆盖完整对象并区分各 occurrence；缺上下文时补读或 unknown，不串项。 |
| EAC12 | 完整表单，包括已填／未填／未知子写区与 checkbox | 全部适用字段的类型、状态、原文值、owner、位置及关系分别对照原件；空 OCR 不证明未填。 |
| EAC13 | 完整原生／扫描表格、复杂正文、Notes／OR／跨页成员 | 原格／glyph 与逻辑结构、条件、例外完整对应；缺列／错归属／漏跨页不能以整齐网格通过。 |
| EAC14 | Word 表格、编号、字段、修订、不同 story 与不支持旧格式 | 有效与原始语义、原生定位和真实支持界限保留；不假造 Word 页码或将未读当空白。 |
| EAC15 | 双解析器分歧、截断、fallback 失败及采用 | 保存两完整结果和原因，按完整区域／组采用；无逐格最佳拼接、置信度授分或覆盖旧正确。 |
| EAC16 | 正常 parser→canonical→持久化→consumer→原件回取 | 完整单元／parts／组／关系／unknown 与来源不丢失；模型投影没有混入生成答案或整树审计。 |
| EAC17 | 缺证、冲突、unsupported、失败／超时和不能界定覆盖范围 | 真实终态及受影响范围可见；可靠部分保留，依赖判断受阻，不报告完整成功／零缺陷。 |
| EAC18 | source-reviewed canonical 与自动来源候选分别评价 | 人工提供的框／读序／关系及采用明示，不当自动预测；producer 不取得独立评价答案。 |
| EAC19 | 同完整范围前后对照、旧正确回归、不同作者与独立来源 | 普通 CER、关键零错漏及各结构维度分别达门，错误／未知不藏、分母不缩；fixture 不代替来源分。 |
| EAC20 | 来源／角色／版本改变、缓存和 canonical 旧绑定 | 旧结果明确失效或重新核验，不按同名、旧页码、旧角色或相同图像尺寸兼容回退。 |
| EAC21 | 质量日志与连续无收益实验 | 阶段缺陷、训练运行／forward／更新、模型结果和来源质量分列；连续三次可比实际既无来源质量收益也无关键缺陷减少时，触发方法复盘。 |
| EAC22 | 最终正常页面及原件阅读 | 主／交付版本身份明确，pending 文字／准入／原件回取真实可用；离线组件检查不充当浏览器业务验收。 |

完整来源质量须覆盖全部原适用范围，保持普通 CER≤0.1%、关键零错漏、完整表单／表格／跨页、旧正确及独源门。对适用范围中尚未确认的内容保持错误／未知或待验收，不因其未知而缩掉分母。

第一批历史契约通过数和未验证项见 Further Notes；本次没有重跑产品测试。既有 canonical 19 项／仓环境初始化错误必须在可用的隔离测试环境重验，不能删断言、改 skip 或修改活动系统网络以换取全绿。

## Out of Scope

- 本次 spec 编制期间的新实现、真实模型调用、训练、自动 Goal、权重发布、重建索引、活动数据库／服务切换或部署。
- 图纸细节 OCR、CAD／向量／尺寸／轴线／符号／图例或工程关系解析与训练；实际确认后的图纸全部范围外。
- embedding／检索／rerank 效果、Vetting／Advisory 合同适用推理、专家答案、报告／咨询功能扩展及其业务得分。
- 重建统一大框架、新缓存平台、独立 agent 平台、全局 provider 替换或无需求的状态／监控面板。
- 以 gold、评价答案、样本页／裁图位置或人工采用映射替代自动解析；修改已有 gold／fixed05 来让新结果通过。
- 提前开展 SL 官网全量下载、多版本全页比较或将新版本送入训练；此工作保留为后续阶段。
- 把代码／fixture、HTTP 成功、模型 ready、JSON 合法或本地测试追认为原件质量、浏览器或 H800 验收。

## Further Notes

本规格细化既有总规格的 D2–D4 及解析到完整来源接口，其他检索、Vetting、Advisory、Drafting 与部署任务仍由各自规格管理。使用项目既有本地 Markdown tracker，标记 ready-for-agent；本次没有配置、创建或关闭远端 issue。主验收接缝沿用已确认流程，不重新访谈已采纳决定。

已有修复是实施起点，以下为 2026-10-14 已保存证据的状态，未在本次 spec 编制中重新运行：

| 项目 | 已有证据状态 | 不能据此声称 |
| --- | --- | --- |
| 主工程身份／scope／目录／原件登记与刷新 | 87 项相关契约及内存数据库测试通过 | 整套正常 parser 来源质量通过。 |
| 独立交付对应修复及图纸 metadata pending 上传边界 | 112 项相关测试通过；主工程与交付能力仍分别标注 | 主工程已具备相同自动 drawing 入口，或任意无提示图纸均已自动识别。 |
| canonical 测试 | 每仓另 19 项因 JDK 回环连接初始化错误未到业务断言 | 全套契约全绿或 canonical 当前运行已验收。 |
| 前端 | 两仓类型检查及三语离线组件检查通过 | 当前浏览器或运行构建已采用并验收。 |
| SL | 已登记官网当前目录，未下载官网大 PDF，未启动全页版本比较 | 全部历史版本已获取、已划分独立数据或已加入训练。 |

原件字节登记／刷新测试包含实际原件与人工 parsed-state fixture，仍不是完整来源新解析质量。图纸无 metadata 的自动身份、观察先行、布局传输、区域补读整合及完整来源验收继续待完成。已有原件／数据隔离、训练失败及未完成状态保持。

关联入口：

- [本规格任务图](oer-engine-integration-repair-tickets-20261014.md)。
- [OER 总规格](oer-vetting-advisory-next-development-20261012.md)与[原总任务图](oer-vetting-advisory-next-development-tickets-20261012.md)。
- [已确认范围与执行规则](../oer-scope-and-rules.md)及[实施交接](../HANDOFF-ocr-embedding-rerank-20261008.md)。
- [领域术语](../../../CONTEXT.md)及[完整术语表](../../GLOSSARY.md)。
- [局部不确定性 ADR](../adr/0001-localize-uncertain-review-evidence.md)、[双解析器完整结构采用 ADR](../adr/0002-preserve-dual-parser-evidence-and-route-by-structure.md)、[模板资料隔离 ADR](../adr/0003-separate-drafting-templates-from-vetting-advisory.md)。

实现／审计原始证据保留在忽略的本地目录；本地相对引用仅用于当前工作区回取，不表示这些私有运行证据已发布：

- [架构与数据流核查](../../../.local/state/oer-engine-flow-review-20261014/architecture-dataflow-review.json)。
- [硬编码审计快照](../../../.local/state/oer-engine-hardcode-audit-20261014/root-audit-summary.json)。
- [第一批修复与实际测试汇总](../../../.local/state/oer-engine-integration-fix-20261014/root-result.json)。
- [图纸候选增量独立复核](../../../.local/state/oer-engine-integration-fix-20261014/backend-pending-phase02-review.json)。
- [SL 后置工作收据](../../../.local/state/oer-sl-official-versions-20261014/deferred-for-engine-repair01.json)。
