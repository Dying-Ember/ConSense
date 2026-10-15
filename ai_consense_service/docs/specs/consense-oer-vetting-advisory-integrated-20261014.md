---
title: ConSense OER／Vetting／Advisory 统一实施与验收规格
status: ready-for-agent
labels: [ready-for-agent]
date: 2026-10-14
timezone: Asia/Shanghai
authority: 用户要求整合 OER 修复与完整产品实施要求，并保证 OER 修复不被覆盖
execution: spec-only; no-new-code-model-training-index-or-deployment-started
tracker: local-markdown
---

<!-- implementation-progress-20261015:start -->
> **2026-10-15 当前实施状态：** 用户已授权的统一代码实现、分支整合及适用本机自动化已收口，具体通过/跳过/历史排除和 retired 范围见[统一实施记录](../testing/consense-integrated-implementation-20261015.md)。原 08:30 目标未达；按用户最新要求在代码阶段停手，不启动效果验证。真实模型、赛方原件前后质量、SL、端到端及目标环境门未验收，完整目标未 complete。下方 `spec-only` 及原状态保留规格编制时语境，受保护 OER 要求不缩减。
<!-- implementation-progress-20261015:end -->

## Problem Statement

使用者需要上传实际合约材料，得到可核查的完整审查报告或有依据的咨询答案，并从每个引用打开正确版本的完整原件。当前 OER 的正常解析整合、检索到模型的完整证据消费、模型生成、应用接收和前端交付仍有各自的缺口；局部代码、契约或单题进步不能证明整条链已经可用。

原 OER 修复规格已经明确观察先行、身份判断、布局传输、混合区域补读、完整结构组合及来源质量门。产品问题交接则列出 PRD01–PRD08 的真实失败、未验证项和归因工作，但它本身不是完整实现规格。若用产品问题摘要替代 OER 细则，或仅拼接两篇文档，会遗漏引擎质量条件及原总规格中的持久恢复、人工记录、报告、迁移和目标交付要求。

本规格统一责任、接口、执行依赖和完成判据，保留已实现起点和未过门，不重新实现历史修复，也不把尚未接受的候选写成既定修法。

## Solution

建立一个总体实施入口，采用现有模块和公开业务接缝，组织以下工作链：

**原件登记 → 初步 native／OCR／layout 观察 → Java 身份／版本／范围 → 阅读规则与按区域补读 → 完整来源结构 → 索引候选与结构闭包 → 实际模型 wire 与预算准入 → Vetting／Advisory → 应用校验与原子保存 → 前端、原件阅读及报告。**

OER 保留独立受保护的 D1–D10、EAC01–EAC22 和 ENG-00–ENG-10。产品层在这个契约上补齐实际检索、模型调用、业务判断和交付；各层在首次发生内容丢失或错误的地方修复，并分别验收。

业务开发可以使用身份明确、已知缺陷可见的 fixed05。新 OER 候选的采用和最终整链接受必须通过引擎来源门；业务单题成功不能关闭该门。未确定采用、身份或局部依据时按实际影响范围保留可靠部分及 unknown。

本次交付为规格和本地任务图。既有 Advisory 停止状态保持；不因文档整合恢复模型／浏览器测试、训练、自动 Goal、索引重建或部署。

## User Stories

1. As a document user, I want my original and its actual byte identity preserved, so that every result can be traced to the file I supplied.
2. As a document user, I want content evidence used before detailed routing, so that filenames and metadata cannot discard legitimate material.
3. As a document user, I want pending identities visible in the catalog, so that uncertain files do not disappear or enter trusted business evidence silently.
4. As a document user, I want confirmed drawings excluded after identity routing, so that the agreed parsing scope is respected.
5. As a document user, I want project documents distinguished from templates and evaluation answers, so that unauthorized material does not become contract evidence.
6. As a document user, I want native PDF and Word structure preserved, so that numbering, controls, revisions and original wording remain recoverable.
7. As a document user, I want scanned and mixed regions read with complete context, so that native text does not conceal an unread form or table.
8. As a document user, I want original geometry and coordinate transforms retained, so that a citation points to the correct occurrence in the correct source.
9. As a document user, I want form type, writing state, value and owner distinguished, so that missing recognition is not treated as an unfilled or negative answer.
10. As a document user, I want complete tables, conditions and cross-page relationships preserved, so that qualifiers cannot be lost during assembly or transport.
11. As a project owner, I want dual-parser evidence and adoption decisions retained, so that complete regions are selected without inventing per-cell combinations.
12. As a project owner, I want complete-source quality and independent review maintained, so that local gains do not conceal errors, unknowns or regressions.
13. As a developer, I want downstream consumers to receive complete selected source units, so that short retrieval windows do not replace necessary contract context.
14. As a developer, I want raw audit material separated from model evidence, so that compact prompts retain actual wording, conditions and source references.
15. As a project user, I want source editions and project adoption distinguished, so that the latest standard is not assumed to govern my project.
16. As a reviewer, I want actual model submissions and budget failures recorded, so that planned material cannot be mistaken for completed review.
17. As a reviewer, I want execution coverage separate from findings, so that zero findings do not imply that all checks were performed.
18. As a reviewer, I want interrupted work to recover only from compatible checkpoints, so that saved results are not duplicated or falsely completed.
19. As a reviewer, I want manual checks, decisions and comments bound to their exact versions, so that changed evidence makes affected records require review.
20. As a reviewer, I want PDF, DOCX and JSON reports generated from one saved snapshot, so that coverage, limitations and findings agree across formats.
21. As an advisory user, I want an ordinary question to trigger the required expert method, so that I do not need to write a detailed generation prompt.
22. As an advisory user, I want a complete answer and explanatory flowchart when requested, so that I can use the result after generation finishes.
23. As an advisory user, I want claims, conditions, actors and deadlines supported by actual contract text, so that valid citation IDs do not conceal unsupported conclusions.
24. As an advisory user, I want reliable limited analysis and necessary follow-up questions retained, so that insufficient evidence does not erase useful supported content.
25. As an advisory user, I want verified units streamed with truthful stages, so that progress does not publish unaccepted drafts.
26. As an advisory user, I want stopping, disconnection and late events handled accurately, so that a failed request cannot become successful history.
27. As an advisory user, I want each historical answer to retain its own citations, so that a later answer cannot change the evidence I open.
28. As an advisory user, I want citations to load the complete PDF or Word source and nearby content, so that I can inspect the original independently.
29. As a reviewer, I want a finding to create an editable advisory question, so that I can investigate it without automatically changing my review decision.
30. As a project user, I want source images and complete table groups returned accurately, so that generated explanations do not replace original evidence.
31. As a project owner, I want parser, retrieval, harness and model errors investigated separately, so that remediation reaches the first failing layer.
32. As a project owner, I want development examples and independent source families distinguished, so that exposed questions are not reported as blind tests.
33. As a project owner, I want training considered only for demonstrated residuals, so that missing input and integration defects are repaired first.
34. As a project owner, I want storage migration verified with a reversible new collection, so that fixed05 and historical evidence remain available.
35. As an operator, I want frozen Linux artifacts and offline startup verified, so that the target does not depend on implicit downloads.
36. As an operator, I want the single H800 handed over between complete stages, so that resource waits and recovery remain observable and bounded.
37. As a project owner, I want code, model, source, browser and deployment evidence reported independently, so that a merge is not presented as product acceptance.
38. As a project owner, I want SL version expansion deferred until the engine is repaired, so that dataset growth cannot conceal unresolved integration defects.

## Implementation Decisions

### U0. 规格权威、受保护条款与状态

- 最新明确用户指示优先；当前范围规则和已采纳 ADR 约束各模块。本规格统一产品编排，OER 子规格决定其引擎细则；本规格不能默示放宽子规格。旧日期方案、状态和问题证据保留历史语境。
- OER 原 D1–D10、EAC01–EAC22、ENG-00–ENG-10 全部保留，验收编号不重写、不删除、不用综合业务指标替代。PRD07 负责跨层归因和消费接缝，不新建平行 parser 或第二套引擎任务。
- 原产品交接 PRD01–PRD08 的问题、验收与 E1–E8 证据继续有效；原总规格 AC01–AC20 的完整产品范围按本规格映射承接。旧 task 的关闭状态不因新图生成而改变。
- 冲突以当前明确规则及相应专业子规格为准。例如来源有效性 verdict 不能缓存，actual-CAS-every-read 和 fresh-source 守卫保持；旧总规格的复用措辞不能放宽此规则。纯计算准备、未变化的解析及文档向量可在完整身份核验后复用。
- 首批 OER 身份／scope 修复及 Advisory A1–A4 等已有实现是受保护起点；当前 checkout 是否包含、当前构建是否采用、真实业务是否通过分别核对。不能把旧 NEXT 的“未开工”快照套到当前全部能力。
- ready-for-agent 仅表示规格可执行，不启动实际工作。此文不解除 Advisory 测试已停止状态，也不操作或改写其他 OER 工作的运行状态。

### U1. 共同身份、来源角色与资料权限

- 区分文件类别、原件字节身份、业务角色、内部内容角色、适用文件版本、项目采用及处理 generation；原件 SHA 与 canonical sourceHash 分别保存，前者不证明法律适用。
- 固定引用绑定 baseline／generation／manifest，项目引用绑定 project／document／source snapshot／revision；原件、条款／native 位置、group、member、asset 使用现有来源身份语义。展示 fileKey 和文件名不充当这些身份。
- pending、失败和范围外文件可在目录查看原件及真实状态，不能进入可信业务源集；同名较新失败或排除上传不能复活旧成功版本。用户合法角色保留，但 tender／package 不能绕过身份待确认或模块隔离。
- BQ／SOR／General Summary、PRE 以外 works-related PSS、SL 非技术章节维持范围外，缺席不作为必需输入失败。项目 PRE、适用 GS／SL 技术范围、范围内 Bill／drawing 引用及合同付款机制保留。
- NTT／SCT／SCC 标准空模板只供 Drafting；实际项目三稿或明确待审起草成果按实际身份处理。2d／2e、countercheck、答案及配套评价流程图隔离，不进入生产候选、同题 prompt 或解析训练监督。
- 文档内指令不能改变应用范围、模型权限或发布门。固定标准解释、项目事实、用户陈述、假设和采用证明分别表达；未知采用不能用最新版、同类标题或 SHA 一致补造。

### U2. OER 的完整修复链，按原 ENG 单线实施

- 复用 DocumentParser、ParsedDocument、DocumentBlock、POI／OOXML、PDF 原生观察、PaddleOCR-VL／PP-DocLayout、MinerU、已有 Camelot／pdfplumber 候选和 canonical units／parts／relations，不新增平行来源树或全局 provider 替换。
- 正常顺序必须是初步观察先行，再 Java 身份／版本，再阅读规则和详细任务。局部观察保存真实 coverage，不能写成完整 PARSED；图纸只做必要身份观察后排除，身份不足保持 pending，不能通过详细 parser 猜身份。
- 真正 OCR／layout 上游、HTTP、Java 和持久化共同承载 region、原文／规范化文字、原 bbox／polygon、frame、变换、来源 channel、模型／配置及 raw 引用；兼容旧 text／confidence／lines。零文字但有布局或控件观察也必须保留。
- 对混合页按实际未覆盖区域补读；较多 native 文字和隐藏 OCR 层不证明完整可靠。标题、共享前缀、写区、表头、单位、续行、Notes、条件及例外真实进入输入；正确 native 内容不能被整页 OCR 覆盖。
- 同源组合核实际原件、页／occurrence、CropBox／MediaBox、rotation、UserUnit、crop／缩放和输入图像身份。尺寸一致、clip 合法和字符串相同都不证明同源；有效观察复用不能绕过身份失效。
- 完整保留表单类型／子写区状态／原文字面值／owner／位置／关系；checkbox 分域，空 OCR、placeholder 或 mask 不证明未填。表格保 glyph 成员、逻辑行列、合并、空栏、多行表头、Notes／OR／跨页条件组；Word 保 story、编号、字段、修订、符号及原始／有效文字，不编造原件物理页。
- 公式没有可靠结构时保完整原公式图、可核释义及 unknown，扁平文字不冒称公式结构通过。范围内规范插图保主体、caption／legend／notes 和所属条款；区域完整性或关系不明时返回完整原页并标限制，不扩为图纸细读。
- 扫描区域继续按已采纳规则保存两套完整解析、assets、分歧和完整组采用依据。按缺口触发的追加补读或避免重复执行，不能解释为撤销双完整解析。采用按完整区域／表格组，不逐格拼最佳答案，不凭 confidence 或一致性授分。
- 阅读规则定义通用角色、锚点、编号语法、读序和关系，不硬编码特定页、附录答案、原件值、预期空白或固定 crop。SHA 绑定的历史范围及 source-reviewed 采用只适用于对应版本，不能当新来源自动能力。
- ENG-05 验证正常 parser→canonical→持久化→consumer→原件回取保全，后续产品工作消费此输出。引擎质量仍按完整原适用范围、普通 CER≤0.1%、关键零错漏、完整结构与定位、旧正确、不同作者及独源门验收，分母不缩。

### U3. 从完整来源到实际业务包的责任分界

| 层／现有模块 | 提供的外部行为 | 不得由后一层补造的缺口 |
| --- | --- | --- |
| OER 正常 parser | 原文、完整结构、来源位置、coverage 和采用／自动恢复状态 | 未读区域、错误字段状态、缺列、错误坐标、丢跨页关系 |
| canonical 与来源服务 | 持久化完整单元、parts／groups／relations、真实原件和资产回取 | 原件版本、项目采用、人工参考是否属于自动产物 |
| 检索／结构扩展 | 合法候选及其完整必要正文、父条、表组、条件和相反约定 | 仅标题命中不能当正文已送；没有召回不能当不存在 |
| 模型材料／预算适配器 | 最终 wire、模型 profile、正文／限定／unknown、准确或明确未知的计数 | 截断、未提交、估算不能当已执行；审计字段不能当合约正文 |
| Vetting／Advisory 与发布层 | 实际模型结果、机械接受、独立判断、原子保存及真实终态 | schema 合法不能证明命题有据、完整审查或成功交付 |

- 完整证据组沿用 required／present／missing／unknown 及成员角色，保父条、条件、AND／OR、例外、相反条文、修订和跨页资产。下游逐跳对照同一 source／unit／group，找首次丢失位置。
- 保留 BGE-M3、BGE-reranker-v2-m3、BM25、向量召回、结构扩展和同池 rerank。embedding 消费原文窗口，命中后取完整业务单元；不以短窗替代完整父条或表组。
- glyph、operators、raw 大响应、CAS、软件收据留证据层。紧凑模型材料保存实际正文、必要结构／限定、采用状态、相关 unknown 和简洁定位，生成标签与原引用正文分开；精简 metadata 不能删除语义证据或守卫。
- 缺证影响可界定时只暂停依赖事项，互不依赖的可靠事项继续；身份、同源或缺失影响不能界定时阻断相关整体消费。范围外文件缺席不制造范围内 coverage 缺口。
- 不增加新的有效性缓存；保 actual-CAS-every-read、原件实际字节及业务调用前后 fresh-source。collection root／batch 和完整结构单元沿用既有限额与完整性契约，不拆表组或删 blank 规避容量门。

### U4. 实际模型、窗口与输出契约

- 绑定实际模型 revision、引擎、tokenizer／processor／chat template、运行 profile、完整序列化请求及 hash。模型选择在异步线程与同步／流式路线保留，不静默换模型、换库或落回默认 profile。
- 沿已确认至少 256K、优选 512K 的目标规划，逐 profile 实测总窗、输出预留、reasoning 配方、有效注意力范围及 KV／host／device 峰值。字节数、估算 tokens、实测 usage 和最大窗口分别记录；不以扩到 1M 代替归因。
- 准入必须观察实际 wire，并含 schema、历史、系统规范、思考及最终输出预算。unknown／over-budget 保持可见，按完整单元分包或减并行，不能截条件、把 planner 请求当已提交或使用另一模型 tokenizer 冒认。
- Qwen 的 reasoning_effort 须证明被所选引擎消费；M3 stable adaptive、Flash effort 及其他模型模式分别记录，不混用。模型现有候选集合与精确部署版本仍须验证，不凭其他模型的失败或 Drafting 成绩填入 Qwen 的 Vetting／Advisory 能力格。
- 真实调用仅按后来明确授权的有限方案进行：固定问题、来源／prompt／模型／模式、调用及总修复上限和资源预算；记录首次返回、stop、length／timeout／取消、准确或 unknown usage、修复与最终接受。一次只改变一个因素。
- Advisory 图与 JSON 修复共用最多一次总修复预算，不叠加；无法形成完整合法可接受结果时保存失败与已验证有限内容，不制造完成。graph-first、协议精简或改变思考额度均是待对照候选，不能预称有效。

### U5. Vetting 完整覆盖、持久执行和恢复

- 复用 Java／SQL、现有 packet、coverage 和有界 worker，保存来源／模型／输入身份、checkpoint、唯一 owner／lease 及幂等结果。旧 owner 和重复 dispatch 不得重复发布；checkpoint 不兼容或调用结果不明时显示需重跑／中断，不假称续跑。
- 记录 planned、submitted、model-returned、mechanically-accepted、independently-reviewed。任务执行结束、完整范围审查完成、零 finding 和独立判断无问题分别表达，coverage 与 finding 数／人工 checklist 分开。
- checked-pass、issue、insufficient-evidence、not-completed、not-applicable 均有实际检查和必要证据依据。未提交／缺证／超预算／length／失败不能默认为 pass，NA 须有范围依据；不恢复风险数量上限。
- 轻量 progress 展示排队、模型调用、等待 GPU、保存、恢复和真实终态，按 run／epoch 抵御晚到请求，不通过轮询读取巨型报告或整源。
- 完整材料必须有实际提交与执行账本。评测含独立应发现集合及合理无问题反例，核关键漏报和误报；仅审模型返回 finding 不能证明完整质量。

### U6. 人工记录、报告与正式导出

- 人工 checklist 默认未核对；裁决和团队备注使用现有 SQL，绑定 project／run／source snapshot／finding revision。精确版本可复用，依据或范围改变时仅受影响项待复核，保历史与未涉及记录，不跨项目继承。
- PDF／DOCX／JSON 由同一已保存 report snapshot 生成，包含来源版次、适用范围、覆盖、缺证／未完成／NA、finding、人工响应及真实引用，不能在三个导出器中重新判断业务。
- 正式导出限执行 COMPLETED、来源版本相符且 snapshot 已保存。有界业务缺证可导出明确保留限制的报告；FAILED／interrupted／未完成／过期当前 run 拒绝正式导出，可靠已持久包和失败账本仍可查看。历史报告继续绑定原版本。
- 沿用 POI／PDFBox 和真实字体生成原生文档，核分页、整表、长引用和尾文；业务错误不能作为下载成功文件，HTML-as-Word 或 demo 截图不是正式报告路线。

### U7. Advisory 专家方法、有限分析和自然流程图

- PM v1.3 确认稿仅属于 Advisory。应用固定必需规范、来源权限及输出契约，保 DB 自定义文字作为补充；实际默认／自定义配置都核有效 prompt 身份，不将本轮问答规范套到 Vetting 或 Drafting。
- 复用显式 project／session／scope、有限历史和澄清，区分 project_contract 与 reference_only、事实／用户陈述／假设／未知。专家意见不取得 CM、Surveyor、批准、认证或裁决权限；项目采用／修订／时点不明时保限制，不自动换参考解释冒充项目答案。
- 保留内部 R1–R10 等适用自检及本地可执行门，记录状态、短理由、实际证据和受影响单元；自报 pass 不授法律正确。旧历史缺记录仍为未记录，首版不增自检面板。
- 有据有限分析、不能定论事项、补问和真实引用贯穿响应、历史及重开；整体 insufficient／grounded=false 不因局部有据改变。缺证原因不作为合同引用，来源／范围／采用／语言与提交守卫保持。
- 自然问法自动选既有题型与 T6 图计划，用户原问句保留，不靠题号特判、手工强 prompt、官方答案或私有纠正图。普通 Q1.6、自然短问法、另一类图题及缺日期／制度变体分别验收，不继承成绩。
- 图节点／边／条件／actor／接收人／动作／期限与起算／终点来自实际正文及限定。复用图计划、身份引用、结构校验和受限 Mermaid 渲染；图结构、可达性、语法／显示与合同语义分项验收，标题或合法 ID 不证明结论有据。
- 每个权利、条件、例外、较早者／失权或 binding 效果有逐项来源支持；未确认部分保 unknown 或有限文字，不猜起算日期、制度或分支方向。图是解释成果，不充当原合同图或图纸解析。
- Vetting→Advisory 沿现有可编辑问题与未验证引文草稿，带 finding／来源身份、有效期及一次性消费；跳转不调用模型，提交后独立核当前材料，不回写审查裁决。

### U8. 实时发布、历史与完整原件阅读

- 复用既有 streaming、业务事件、取消、图组件及应用持有前缀。反馈来自真实阶段，正文和图按可独立校验的完整单元发布；禁止假百分比、完成后打字动画或半幅未验图冒充真实流。
- 已发布单元不被后续修复改写。最终组合仍核来源、session、scope、模型、采用、语言和引用；complete 仅在最终原子保存成功后发送。后置失败／取消保真实终态，未接受草稿、空正文和 length 不写成功历史。
- 分别验证停止、断线、迟到事件、重复提交、project／session／source 切换及稳定滚动。仅取消本请求可取消工作，远端取消未知如实记录，不停止共享模型服务。某 profile 只有状态流／最终结果时明确正文流未完成。
- 每条答案保自身来源 snapshot／引用；点击历史 A 不使用后问 B 的来源。固定和项目回取路径按来源类型分开，晚到阅读响应不污染当前答案。
- 引用单击加载完整实际源文件。PDF 核 hash／版本、真实物理页及 frame 变换，可验证时高亮；无几何仅跳真页并提示，允许邻页及整表阅读。DOCX 使用同一 part／namespace／native path 唯一映射节点，保整表和前后文，不按近似文本选第一个命中，不造 Word 页码。
- 各业务阅读接口按本模块／范围准入；Advisory 不借另一模块 URL 绕权限。历史失效显示原因，不重绑同名新版本；原始 .doc 等 reader 支持限度单列，验收材料涉及的类型未闭合不能以后台解析成功关闭阅读门。
- 正式 Vue 交互复用文件／条款／finding 导航、原文旁问题卡、底稿、裁决／备注、快速问答和证据面板，消费真实 API。Q10=A 返回正确完整原图／整表及必要成员，未知图形关系不猜；安全 Markdown／Mermaid 渲染失败保已接受文字与明确状态，不新增展示框架。

### U9. 分层归因、独立评价与条件训练

- 同题固定四类输入：来源审核的完整材料＋最小清晰契约；同材料＋生产 harness；正常 parser 产物＋同 harness；实际 RAG 发送包＋同 harness。业务义务、来源评分和分母保持，固定 raw 重放另评接收层。
- 每个错误记录首次出现层、可回取证据和未知因素；交互影响不能相减为 OER／harness／model 三个错误百分比。旧 canonical 正文存在但 wire 仅标题应修消费接缝；raw 已有语义错误不能归为前端改箭头。
- 同原适用完整范围保正确／错误／未知／未执行，引用支持、命题、完整条件、应发现集合、协议、发布、原件回取分别记。source-reviewed 投影、参考框／关系和 Codex 标注不计自动新来源预测；humanConfirmed 仅指真人确认。
- 开发／校准／评价按项目、来源家族、原件／派生与近重复版本分组，答案和评价框不暴露给 producer／训练准备。暴露旧题只作开发回归，合成压力样本不称新来源泛化。
- 工具／规则／接口修好后仍存在、且充分输入下可复现的模型残差，才进入 OER 条件 ENG-08 或业务模型训练候选。教师原答先经来源审核，完整监督、独立校准／评价、精确有限预算和 fresh reload／正常消费须可验证，不凭模型大或 loss 降低采用。
- OER 默认 BF16 和必要 FP32 状态、原质量门及连续三次可比实际既无完整来源收益也无关键缺陷减少即停止同方向复盘的规则保持；prepare／数据调查不重置计数。未采用候选不替换活动基准、gold 或旧正确，训练、发布权重、索引采用及业务复测分别有身份与状态。
- 新实际结果更新既有 HTML／质量日志／handoff，保旧失败、分母和轮次，不另造综合准确率或无需求仪表盘。

### U10. 存储迁移、制品和目标资源

- Qdrant Server 单实例＋PVC 是既定目标，使用冻结结构与向量建立新 collection，保 Local05。绑定 embedding revision、维度、归一化、距离、点／向量／payload／父子映射及资产 manifest，全量读回、真实查询及原件回取通过后采用；不复制 Local 目录冒充 Server 迁移。
- 未采用目标时可用明确 owner 的现有有界入口。单 writer／worker 和旧 owner 退出须真实验证，PVC RWO 不等于进程写锁；失败保存回滚配置和旧成果，不静默重绑基准。
- 沿原有限组件集合准备独立 Linux 锁、MySQL 支持版本候选、schema／数据迁移、备份恢复、字体与原生库、CPU table、OCR、retrieval 和业务 LLM 环境；精确 patch、模型／依赖 SHA 及 OCI digest 在制品验证中冻结。旧版本风险按真实扫描整改，安装不等于采用。
- 完整离线交付含已验收固定成果及应对新版本的完整 OCR／原生／布局／表格／双解析／切块／embedding／索引与回滚链，不以预计算 JSON 或单索引代替现场处理能力。原件／权重只读，工作与数据 PVC 明确；断网启动不得隐式下载。
- 目标按单 H800 80GB、27 vCPU、250GB RAM、650GB 高性能＋1250GB 通用存储规划。CPU 业务、SQL、向量与资产持续；Paddle、MinerU、文档 embedding、query embedding／rerank 与业务 LLM 按完整阶段交卡。前 GPU owner 真退出释放整卡后，后一阶段加载／ready，先持久化结果；sleep 不等于归还 K8s GPU 申请。
- 主 VLM／embedding／rerank 默认 CUDA，辅助层按固定版本官方策略可用 CPU，不恢复已撤回的全层 CUDA 探针，也不静默降主模型 CPU。记录冷／暖加载、ready、等待、峰值、超时与恢复；不能靠降质、截表或截上下文解决资源缺口。
- 制品绑定源码、模型／tokenizer／processor／template／自定义代码、OS／Maven／npm／pip 锁、字体、许可证、扫描与恢复证据。本机、CI、PR merge、模型 ready、安装及合成长窗测试不替代 MySQL／Linux／K8s／H800／生产接受。
- 活动 DB／服务／索引切换、公开发布及部署另按会话明确授权与实际计划执行。本次不触发这些动作；其他 GPU／WSL 工作及已抑制 Bonsai 状态保留。

### U11. 执行图与关闭判据

- 统一 UNI 任务只负责基线、跨层产品接缝及最终交付，引擎沿原 ENG 图实施。契约明确后可并行设计／开发，下游集成与关闭才受依赖限制，不恢复“全解析通过才开始业务开发”的旧约束。
- OER 的观察／布局／混合覆盖／完整消费依赖保持，canonical 环境修复在隔离环境并行，不改活动系统网络、删断言或改 skip 换全绿。必要训练依据 ENG-07 实际诊断决定，不能要求已经通过最终质量才允许研究残差，也不能绕过数据准入。
- 新 generation 采用及最终新引擎整链接受等待 ENG-09；fixed05 业务开发结果明示旧基准身份与局限。SL 下载／多版本全页比较仍由 ENG-10 后置，版本扩展不自动进训练，也不阻塞不依赖它的产品工作。
- 任务输出至少包括：编号、实际 checkout／commit＋dirty 身份、来源／模型／prompt／配置、问题层、最小改动、实际输入、期望／结果、旧正确回归、错误／unknown／skip／未执行、证据地址、规定门及当前状态。未达到全部规定验收不得标 Closed。

## Testing Decisions

主接缝沿用用户已确认的公开业务流程：**真实上传／正常解析 → 持久来源与业务作业 → 实际模型包／最终保存 → 重开结果 → 引用打开完整原件 → 前端图和正式报告**。从外部行为、持久终态和真实来源判定成功；只在既有适配器加必要兼容、坐标或竞态反例，不新建测试专用主入口。

契约替身可检查 wire 与业务拒绝行为；真实模型调用、原件完整质量、独立判断、浏览器、导出及目标资源另留证。优先复用现有上传／MVC／JPA、目录与范围、canonical 传输／回取、Vetting coverage／progress／report、Advisory scope／session、引用、图组件和前端竞态测试。对历史已修项核回归，不重复重建。

### T1. OER 全量保护映射

以下是原 EAC 的追踪映射，详细场景和原判据仍以 OER 子规格为准；每项都必须记录自身状态，不按表中摘要关闭。

| 原验收 | 本规格落点 | 必须保留的独立判据 |
| --- | --- | --- |
| EAC01 | U1／U2 | 改文件名／fileKey 不改终局身份；无证保 pending。 |
| EAC02 | U1 | 答案／模板／显式排除不可由 package／tender 绕过。 |
| EAC03 | U1／U8 | pending 目录可见且不准入，同名新失败不复活旧成功。 |
| EAC04 | U1／U2 | 弱 metadata 仅候选，0 详细 parser，exact-SHA 非图纸控制。 |
| EAC05 | U2 | 明确／CAD／无 metadata 图纸与文字清单反例，不把 pending 算识别成功。 |
| EAC06 | U2 | 正常观察→Java 身份→规则→补读→最终产物；局部不冒完整。 |
| EAC07 | U2 | 有效观察复用及 crop／版本／config 失效，真实调用计数。 |
| EAC08 | U2 | 真上游到 HTTP／Java／持久化的 layout／polygon／frame，旧兼容。 |
| EAC09 | U2 | 混合缺口完整补读，正确 native 内容保持。 |
| EAC10 | U2／U8 | 同源 frame／变换／相同尺寸不同页反例，真实回定位。 |
| EAC11 | U2 | 重复 occurrence、crop 外标题／共享前缀／续行完整。 |
| EAC12 | U2 | 完整表单 type／state／value／owner／位置／关系，空 OCR 不授未填。 |
| EAC13 | U2 | 全 glyph／整表／Notes／OR／条件例外／跨页，无缺列整齐假通过。 |
| EAC14 | U2／U8 | Word story／修订／native 定位及真实格式限度，不造页。 |
| EAC15 | U2 | 双完整结果与整组采用，保失败及旧正确，无逐格融合。 |
| EAC16 | U2／U3 | 正常 parser 到 consumer 完整结构及原件，正文与审计分层。 |
| EAC17 | U3／U5／U7 | 真实缺证终态、影响范围及依赖暂停，可靠部分保留。 |
| EAC18 | U2／U9 | 人工采用与自动恢复分开，评价答案／框隔离。 |
| EAC19 | U2／U9 | 完整原范围／分母、CER≤0.1%、关键零错漏、结构／旧正确／独审／独源。 |
| EAC20 | U1／U3 | 来源／角色／版本变化失效，不同名／旧页码／旧角色回退。 |
| EAC21 | U9 | 分层日志及三次无收益复盘，准备不 reset，中间信号不授源分。 |
| EAC22 | U8／U11 | 实际运行版本、pending／准入／回取及浏览器，离线组件不替代。 |

### T2. 产品问题与完整业务验收

每项包含离线最小正反例和规定真实验收。真实环节目前尚未授权重启，状态为待后续有限执行；本次不把该表写成通过。

| ID | 产品关联／场景 | 关闭所需可观察行为 |
| --- | --- | --- |
| UAC01 | PRD07；原件到正常 OER 最终消费 | 原 EAC01–22 分项满足、完整源质量独审；手工 parsed-state／projection 不能代替正常自动链。 |
| UAC02 | PRD07；canonical→候选→闭包→wire | 必要正文、父条、条件、例外、整表、角色与 unknown 实际到包；首个丢失处可定位，不只证明标题或 ID 可得。 |
| UAC03 | PRD04／PRD03；profile 与最终请求准入 | 精确同 wire 计数或明确 unknown；总窗／reasoning／reserve／stop／修复分列，未提交不写 submitted。 |
| UAC04 | PRD03；Vetting 完整材料和发现集合 | 完整账本与五类检查状态一致；独立应发现集与无问题反例评价漏报／误报，完成和零 finding 分开。 |
| UAC05 | 原 AC12／14；恢复和进度 | 隔离自有 worker 真实重启、幂等 owner／checkpoint，兼容恢复或明确需重跑；轻进度不读巨型源，晚到不串 run。 |
| UAC06 | 原 AC13／15；人工记录和三格式导出 | 精确版本／受影响待复核；同 snapshot 三格式内容一致；有界缺证保限制，失败／中断／过期正式导出拒绝；实际字体分页整表尾文核验。 |
| UAC07 | PRD01；自然问法和最终答案／图 | 普通 Q1.6、中文短问法、另一图题及缺条件变体分别交付；一次总修复；完整图实际渲染；空／length／未接受不假完成。 |
| UAC08 | PRD02；命题与合同路径 | 正文支持逐项命题、角色、期限起算、条件／例外、关键分支与终点；近似不冲突／及时逾期／采用未知反例；schema／结构／语义／覆盖分别报告。 |
| UAC09 | PRD06；有限分析／历史／流／停止 | insufficient 保有据内容与必要补问；已发布前缀不改；真实生成单元先核后发，原子保存后 complete；取消／断线／迟到／切 session 不伪成功。 |
| UAC10 | PRD05；引用到完整原件 | 真实接受的旧 PDF sample、新图引用、DOCX 三类分别打开准确字节／版本、页或唯一 part／node 及邻文／整表；未知位置提示，不造高亮或重绑新原件。 |
| UAC11 | 原 AC16–18；范围／会话／业务入口／资产 | 历史 A 与后问 B 分离；reference_only 不冒项目采用；finding 跳转只草稿；真实 Vue 联动与 Q10=A 原图／完整表组可读，失败安全保文字。 |
| UAC12 | PRD08；归因、质量与模型采用 | 四类同题包、raw 重放、充分输入与独立来源评分分开；每错误有首层证据；不同模型／模块成绩不混；未经准入训练不执行。 |
| UAC13 | 原 AC08；Qdrant 新 collection | 冻结 manifest 全量点／向量／payload／父子／资产读回及真实 query；Local05 保留，单 owner 与回滚可验证。 |
| UAC14 | 原 AC19／20；MySQL、Linux、K8s／H800 和交付 | 备份／迁移／恢复、digest 扫描、离线真加载、整卡交接／ready／峰值／等待及持久恢复；代码、来源、模型、浏览器和目标结果分列，Drafting／05 保全。 |

PRD01→UAC07；PRD02→UAC08；PRD03→UAC03／04；PRD04→UAC03／07；PRD05→UAC10；PRD06→UAC09／11；PRD07→UAC01／02；PRD08→UAC12／14。PRD07 与 UAC01 不承担其他 PRD 的合同业务评分。

### T3. 原完整总规格的连续性

旧 D1–D14 的有效要求分别由 U0／U11、U1／U2、U2、U2／U9、U1／U3、U3／U4、U10、U5、U6、U7／U8、U8、U10、U10、U11 承接；新 OER 细化优先，历史状态不复制成当前状态。

| 原验收 | 当前对应 | 保留的范围／关闭条件 |
| --- | --- | --- |
| AC01 | EAC01–06／20；UAC01 | 文件身份、范围、图纸／模板／SL 技术及真实项目稿。 |
| AC02 | EAC12／14；UAC01 | 原生／扁平／扫描表单与值／外观／状态。 |
| AC03 | EAC11／13／14；UAC01 | 复杂正文、父条、编号、旁注和跨页归属。 |
| AC04 | EAC13／15；UAC01 | 无框与旧正确有框整表、缺列、Notes、无 gold 拼格。 |
| AC05 | EAC18／19／21；UAC12 | 条件训练、独源留出、旧正确、历史 HTML。 |
| AC06 | UAC02／10／11 | 固定／项目类型、碰撞、版本及采用 unknown。 |
| AC07 | UAC02／04／09 | 完整组／条件、missing／unknown、可靠独立事项继续。 |
| AC08 | UAC13 | Qdrant Server manifest、读回、实际 query、回滚。 |
| AC09 | UAC03 | 最终模型 wire 准入、schema／reserve、完整分包。 |
| AC10 | EAC20；UAC02／06／10 | 调用、保存、导出来源变化与同大小改字节守卫。 |
| AC11 | UAC04 | 零 finding、NA、失败、未完成和覆盖独立语义。 |
| AC12 | UAC05 | 真实 worker 重启、checkpoint／owner／幂等。 |
| AC13 | UAC06／11 | 人工裁决／备注、刷新重跑跨项目的精确版本。 |
| AC14 | UAC05／09 | 轻进度、网络恢复、run／epoch 与迟到事件。 |
| AC15 | UAC06 | 同 snapshot 三格式，bounded 不足及失败／中断导出区别。 |
| AC16 | UAC10／11 | 每答案自己的引用、session／scope 与历史不重绑。 |
| AC17 | UAC09／11 | 参考解释、项目咨询、假设、澄清与 finding 草稿入口。 |
| AC18 | EAC13／16；UAC07／10／11 | 源图表／公式、完整原页回退、安全 Mermaid 与真实关系限制。 |
| AC19 | UAC14 | Linux 断网、K8s 单 H800 阶段交接及持久恢复。 |
| AC20 | UAC12／14 | 实际 digest／扫描／分层结果与 Drafting／fixed05 保护。 |

### T4. 验收层次和本次静态核对

| 证据层 | 可说明 | 不能替代 |
| --- | --- | --- |
| 代码／契约／内存数据库 | 指定版本的兼容、拒绝、持久状态等外部行为 | 真实原件、模型业务、浏览器与部署 |
| 真模型／真实 wire | 指定输入／模式实际执行与终态 | 原文准确、命题有据或完整范围质量 |
| 原件／完整来源及独审 | 字面、结构、位置、状态及已界定覆盖 | 检索效果、法律判断、前端及 H800 |
| 业务质量与浏览器／导出 | 实际发现／答案、引用、交互、图、原件及报告交付 | 其他模型／题型、自动来源泛化及目标部署 |
| Linux／MySQL／K8s／H800／扫描 | 绑定制品的迁移、离线运行、资源、恢复与扫描事实 | 其他版本、生产切换及合同正确 |

本次只检查规格覆盖、编号、任务 DAG、相对链接、原文保留及范围冲突，不重跑产品测试或核私有 runtime。canonical 每仓原 19 项环境初始化受阻仍未到业务断言，后续隔离重验，不删除断言或改活动系统网络换通过。

## Out of Scope

- 本次文档请求中的代码／DB／配置修复、真实模型／浏览器测试、训练、自动 Goal、索引重建、权重发布、服务切换、部署、commit／push／PR 或远端 issue 操作。
- 改动冻结 Drafting harness、变量／采用值、保存正文、生成行为及版式；用 Advisory PM prompt 修改 Vetting 方法。
- 图纸细节 OCR／CAD／向量／尺寸／轴线／图例／工程关系解析与训练，补齐已排除资料或提前进行 SL 全量版本下载／比较。
- gold／答案／固定页号／crop／项目值硬编码恢复，逐格拼最佳结果、缩原适用分母或删除失败以关闭门。
- 新 parser／agent／memory 平台、新监控大面板、第三套同级 OCR、重型队列／调度／对象存储、无需求依赖及首版业务 LLM 看图改造。
- 无来源归因与完整数据准入的 SFT／LoRA／蒸馏，扩大窗口或反复改预算代替定位，默认多模型 GPU 共驻。
- 用代码合并、HTTP 200、schema 合法、loss、人工投影、索引 ready、本机合成长窗或静态规格通过宣称完整产品接受。

## Further Notes

### 当前起点和开放门

下表转述原规格和产品交接已经保存的状态，本次没有重跑或核远端 PR／实际服务：

| 工作线 | 已有状态／来源 | 仍未完成／不能继承的结论 |
| --- | --- | --- |
| OER 第一批 | 原修复规格：主工程 87／独立交付 112 项局部契约与内存数据库通过；各自能力分开 | 无 metadata 图纸自动身份、观察先行、layout／补读／完整自动消费及完整源质量未过。 |
| canonical | 原修复规格：每仓另 19 项因 JDK 回环初始化受阻 | 未到业务断言，不全绿；保 ENG-06。 |
| 前端／来源登记 | 原修复规格：两仓类型及三语离线检查，实际原件＋人工 parsed-state fixture | 不证明浏览器／正常新来源 parser 通过。 |
| Advisory 既有实现 | 产品交接：A1–A4、前缀持有、同步修复和有界 MiniMax-only 流已提交；代码基准 363096b、历史 merge 85dc5ef、文档 55fa448 | A5 未接受且测试已停；自然图题、来源语义、DOCX／新图引用、完整成功历史／竞态未闭合；当前分支与部署重新核。 |
| 模型对照 | 产品交接 E1–E5：M3／M2.7 16K／32K 失败和 raw 独审保留；部分 usage unknown | 固定包能力比较不等 fresh RAG／项目 session／前端；不填 Qwen3.8-27B 新两模块能力。 |
| Vetting | 产品交接 E4：旧 COMPLETED 的 17 topic 全 partial，119 planner 请求未提交，实际局部片段 46 | 原字段及具体语境见 E4；不能把 119 当模型工具次数，也不能追认新版实际 wire／完整审查通过。 |
| 迁移／目标 | 原完整规格及 PRD08：Qdrant Server、MySQL／Linux／K8s／H800 有独立待验门 | 现有发布／本机模型可用不关闭目标接受。 |
| SL | 原 ENG-10：目录登记，官网大 PDF 与全页比较未启动 | 仍后置；未知独立家族／版本不自动入训。 |

主工作区本次只读 HEAD 为 `285fc2e3bdf97792aeae00a31dbde2c69ebcde60`，大量既有 dirty 增量保留。它不是上述 Advisory 新提交或独立交付构建；实施接手必须绑定实际 checkout、源码增量、运行构建与配置，不从一个目录推断另一目录已部署。

### 文档入口与原证据

- [统一本地任务图](consense-oer-vetting-advisory-integrated-tickets-20261014.md)。
- [OER 修复子规格：D1–D10／EAC01–22](oer-engine-integration-repair-20261014.md)及[原 ENG 任务图](oer-engine-integration-repair-tickets-20261014.md)，完整保留其原正文与条件。
- [产品问题交接：PRD01–08／E1–E8](consense-product-issues-agent-handoff-20261014.md)，保留真实失败和已停止边界，不作为已完成实现的证明。
- [原完整总规格：D1–D14／AC01–20](oer-vetting-advisory-next-development-20261012.md)及[历史 NEXT 图](oer-vetting-advisory-next-development-tickets-20261012.md)，其状态按历史，有效范围按本规格映射。
- [范围规则](../oer-scope-and-rules.md)、[当前交接](../HANDOFF-ocr-embedding-rerank-20261008.md)、[领域词汇](../../../CONTEXT.md)及[完整术语表](../../GLOSSARY.md)。
- [局部不确定 ADR](../adr/0001-localize-uncertain-review-evidence.md)、[双完整解析／整组采用 ADR](../adr/0002-preserve-dual-parser-evidence-and-route-by-structure.md)、[模板隔离 ADR](../adr/0003-separate-drafting-templates-from-vetting-advisory.md)及[有限分析 ADR](../adr/0004-preserve-validated-partial-advisory-under-insufficient-state.md)。
- [已确认 Advisory v1.3 原字节输入](inputs/contract-advisory-system-prompt-confirmed-20261014.md)与[能力对齐原记录](advisory-capability-alignment-20261014.md)，旧“待确认”及旧未实现表按其当时版本，不覆盖产品交接最新实现／停止状态。

原件全文、gold／评价答案、raw、密钥、模型及运行收据继续留受控私有／忽略目录。缺私有证据时标缺证据，不用空目录推断没有发生。文档整合的 before 快照和静态核对放在本地忽略证据根 `.local/state/consense-spec-unification-20261014/`，不等于公开发布或实际验收。

本规格按 to-spec 的七段结构及项目既有 local-markdown tracker 发布。主验收接缝沿用已确认公开流程；本次不重新访谈、不新建远端 tracker。
