---
title: ConSense 下一轮开发 — OER、Vetting、Advisory 与超算交付
status: ready-for-agent
labels: [ready-for-agent]
date: 2026-10-12
authority: 用户已讨论的范围与技术栈结论、communications V2.0、新一轮规格编制要求及已确认的业务验收流程
execution: implementation-in-progress; authorized-2026-10-13
---

## Problem Statement

**执行状态增量（2026-10-13）：** 用户已调用 implement-spec 授权本规格实现，要求先安装 Ponytail / 激活 hooks，再以最小完整代码推进；硬截止为今天北京时间08:30本地提交准备，09:00用户提交GitHub。[实施状态和实际证据](./oer-next-implementation-status-20261013.md)是当前执行入口。以下“本轮仅编制 / 不启动实现”描述2026-10-12编制阶段的历史授权，不再限制本次已授权实现；范围、候选采用与各真实验收门仍适用。

QS 需要用真实项目合约和适用标准完成合同审查与咨询，看到实际检查范围、问题、裁决及原文依据。当前已有解析、固定资料开发基准、检索、审查和咨询代码，但固定参考与项目证据的引用接缝未完全接通，长任务恢复和咨询会话仍有缺口。新 demo 展示了较完整的底稿、导航、裁决与咨询流程，其固定答案、无问题默认通过和本地示例状态不能成为正式系统的业务结果。

原件解析仍有表单空栏与分支漏失、复杂正文层级与读序、无框表逻辑结构及必要注释不完整等问题。需要把这些缺口归因到输入、解析、结构恢复、检索或模型判断，保留已正确的正文与完整表组，避免靠逐格融合或换一套大框架掩盖失败。

同事正在准备单 H800 的 K8s 环境。需要在下一轮开发前确定组件家族和隔离边界，并形成可扫描、离线启动、可恢复的具体制品；技术栈建议、代码能力、本机质量与目标部署验收必须分别记录。

## Solution

以冻结的 05 固定资料开发基准为起点，在现有 Vue／Java／SQL／Python 服务上补齐共同证据契约、Vetting 执行与人工状态、Advisory 会话和逐答案引用，以及 demo 所表达的真实业务交互。解析改进与两业务模块可以并行；已知缺陷和 unknown 限定受影响判断，不将全部原件最终通过重新设为开始业务开发的前置。

保留 Paddle＋MinerU full、原生 POI／PDF 解析、BGE 检索及既有文稿工具。新增独立 Camelot 原生文字层表格候选分支，交由代码恢复逻辑表及完整条件组；超算向量存储以单实例 Qdrant Server＋PVC 为交付目标，现有 Local05 保留。候选版本需经兼容、源质量和实际制品验证后采用，不能自动覆盖开发基准。

用户已确认主验收流程：**上传真实文件 → 固定／项目证据取证 → Vetting 报告或 Advisory 答案 → 点击回取对应原文**。离线解析质量、真实模型结果、浏览器交互和 K8s／H800 制品分别验收。本轮仅编制规格和本地任务图；不启动实现、安装、模型调用、训练、索引迁移或部署。

## User Stories

1. As a QS, I want to upload real project files, so that review and advice use actual evidence rather than demo answers.
2. As a QS, I want each file's identity, business role and version to be visible, so that similarly named documents are not confused.
3. As a QS, I want drawing files identified and excluded, so that the system stays within the agreed competition scope.
4. As a QS, I want excluded materials omitted from required-input checks, so that their absence does not create false failures.
5. As a QS, I want unfilled drafting templates kept separate from project contracts, so that optional template wording is not treated as adopted terms.
6. As a QS, I want actual project NTT, SCT and SCC documents recognized independently of templates, so that valid project evidence remains usable.
7. As a QS, I want fixed GCC, GCT and applicable SL technical references reused by version, so that every question does not repeat parsing.
8. As a QS, I want changed source versions distinguished from old ones, so that historical results are not presented as current evidence.
9. As a QS, I want form fields, empty slots and selection branches preserved, so that an unanswered field is not mistaken for No or not applicable.
10. As a QS, I want clauses read in their correct hierarchy and order, so that conditions, side notes and continuation text stay attached.
11. As a QS, I want complete tables with headers, notes and continuation members, so that a value is not interpreted without its qualifications.
12. As a QS, I want original formulas, revision states and meaningful Word content retained, so that flattened text does not change contractual meaning.
13. As a QS, I want uncertain parsing localized to affected issues, so that reliable parts can still be reviewed.
14. As a QS, I want fixed standards distinguished from project-adopted terms, so that reference wording is not asserted to govern my project automatically.
15. As a QS, I want complete evidence units retrieved, so that a relevant snippet does not omit exceptions or contrary provisions.
16. As a QS, I want every citation to identify its source, version, clause and page, so that I can verify the conclusion.
17. As a QS, I want fixed and project citations to open their correct originals, so that identifiers cannot route to another source.
18. As a QS, I want complete original images and table groups displayed with their relevant text, so that I can inspect the evidence directly.
19. As a QS, I want missing or stale evidence clearly reported, so that I do not mistake an incomplete retrieval for a supported answer.
20. As a QS, I want review progress to show executed work and waiting stages, so that a long job is understandable.
21. As a QS, I want interrupted reviews to retain completed work and expose recovery status, so that failures do not erase progress.
22. As a QS, I want duplicate submissions handled safely, so that the same check does not produce duplicate results.
23. As a QS, I want checked-pass, issue, insufficient-evidence, not-completed and not-applicable separated, so that zero findings do not imply success.
24. As a QS, I want coverage displayed separately from finding counts, so that I can see which checks were actually performed.
25. As a QS, I want manual checklist decisions separated from automated findings, so that the system cannot certify my review for me.
26. As a QS, I want dispositions and team notes saved with the exact review version, so that refresh and rerun preserve their proper context.
27. As a QS, I want changed evidence to require renewed review of affected dispositions, so that stale decisions are not silently inherited.
28. As a QS, I want file, clause and issue navigation to stay linked, so that selecting a finding opens its relevant original and evidence.
29. As a QS, I want reports to include scope, coverage, uncertainty, findings and manual responses, so that exported results explain their practical limits.
30. As a QS, I want PDF, DOCX and JSON exports from the same report snapshot, so that formats do not disagree.
31. As a QS, I want the current advisory project, session and evidence scope visible, so that separate consultations do not mix.
32. As a QS, I want bounded follow-up context within my session, so that the model understands relevant history without importing unrelated conversations.
33. As a QS, I want fixed-reference interpretation offered as a light advisory entry, so that I can ask about a standard without pretending a project adopted it.
34. As a QS, I want quick questions to trigger real retrieval and model execution, so that convenience does not substitute fixed responses for analysis.
35. As a QS, I want each historical answer to open its own references, so that later answers do not change earlier evidence.
36. As a QS, I want clarifications marked as user assumptions, so that my supplied background is not turned into a contract citation.
37. As a QS, I want a selected review finding to create an editable advisory draft, so that I can ask a follow-up deliberately.
38. As a QS, I want advisory answers kept separate from review dispositions, so that consultation does not silently decide an issue.
39. As a QS, I want generated clause flowcharts tied to supported answer text, so that the visualization does not invent contractual steps.
40. As a QS, I want project switches and late responses isolated, so that an old request cannot overwrite a different workspace.
41. As a developer, I want raw source evidence separated from compact model input, so that audit data does not dominate prompts or repeated processing.
42. As a developer, I want actual model token admission and output reserves, so that long evidence is not silently truncated or sent over budget.
43. As a developer, I want parser and model candidates evaluated on complete source groups and new examples, so that exposed fixtures do not become hardcoded solutions.
44. As a developer, I want training applied only to identified model residuals, so that missing input and export defects are not treated as weight problems.
45. As a deployment engineer, I want independently locked Linux environments, so that incompatible model dependencies do not share one environment.
46. As a deployment engineer, I want persistent storage and explicit single-GPU handoff, so that model restarts do not corrupt evidence or strand tasks.
47. As a deployment engineer, I want offline model loading and digest-bound scan artifacts, so that competition deployment does not depend on live downloads.
48. As a project owner, I want failures, untested gates and round results preserved in the existing HTML and handoff, so that progress remains auditable.
49. As a project owner, I want Drafting behavior frozen during this work, so that improvements to other modules do not change accepted drafting results.

## Implementation Decisions

### D1. 起点、授权及阶段边界

- 使用现有冻结 05 作为开发基准，保留旧库、原件、模型、失败与未完成收据。开发基准不追认为最终解析、业务或 H800 通过。
- 本规格为下一次开发的输入，标签 `ready-for-agent` 表示规格可交给执行者；用户本轮明确只写 spec，不授权由本轮启动实现。
- 源解析、检索与模型判断分别归因。局部不确定时暂停依赖它的判断，继续可靠事项；缺失范围无法界定时整份业务结果标为不完整。
- 在固定资料准确 SHA／版本／配方不变时复用持久成果；新来源或版本建立新 generation，失败不得覆盖旧完整结果。固定 SL 新版重新识别技术章节边界，不套旧页码。

### D2. 资料身份和范围

- 固定业务参考范围为 GCC、GCT、全部适用 SL 技术范围，维持中央规则中的既有排除。SL 技术范围内的 General、定义、必要附录和条件不按单词误删。
- 排除 BQ／SOR／General Summary、已排除的 PSS、SL 非技术部分及答案材料；其缺席不产生必需输入失败。合同正文的 Bill 编号、名称和引用文字仍保留。
- 未填 2a NTT／SCT／SCC 模板只服务 Drafting；实际项目三稿或明确待审生成稿按项目文件身份处理，不能按文件名排除。
- 图纸文件仅识别身份并标记范围外，PDF／扫描／附件格式均无例外。不得触发图纸细节 OCR、CAD、尺寸、轴线、符号或工程关系解析／训练。
- 范围内合同／规范的图纸清单文字仍按文件身份解析，不打开其引用图纸。既有范围内规范插图保全不扩为图纸精读。
- 同名文件不证明同版本；来源至少绑定项目或固定基准身份、原字节 SHA、文件类别、业务角色、版本和采用状态。身份不确定保持 unknown，不猜项目采用。

### D3. 解析引擎与 Camelot 分支

- 原生 DOCX 优先 POI／OOXML，保留字段、内容控件、公式、修订与可见态及原文件；原生 PDF 同版本文字层和几何与可见页核对。隐藏旧 OCR 层未经资格核验不能当可靠原生文字。
- 独立 native-table CPU 环境读取原生 PDF 文字层，运行 Camelot Network／Stream 候选并保存原网格和来源。Camelot 是新增候选分支，不是全局 provider 替换。
- 代码恢复多行表头、同编号续文、逻辑行列、空栏、合并关系、主表边界，并组装 Notes、星号、缩写子表、父条与跨页成员；逻辑成员可追溯到同版本原格／glyph 及变换。
- 候选缺整列或原文成员时回查完整原生观察；不能只把已返回 grid 变整齐后宣称完整。不硬编码目标页、固定 bbox、列数、项目值或行号偏移，不使用答案补格。
- 显式补齐原生 PDF 的表格投影、来源与资产契约；不能假定现有仅正文投影已支持完整表格。
- 保留现有正确有线表路线。不同 provider 以完整区域／整表组和可证明的通用资格选择，保存完整双输出与分歧，不逐格融合，不凭置信度或一致性证明正确。
- 候选默认不发布到活动库；完整表和必要条件组、正常完整文件输出及旧正确项回归分别通过后，发布新 generation。失败保留 unknown 和此前完整版本的明确身份。

### D4. 其他解析残差与必要训练

- 优先表单：区分原生控件、打印扁平表单及扫描表单，保完整区域、空槽、勾选／未勾选、blank／NA／unknown、父子选择组及 OR 分支；核控件值与可见外观差异。
- 其次复杂正文：恢复多栏读序、条款编号主层级、旁注归属、父条件及跨页续文。SL 按 clause no 建层级，无独立编号的成员归真实父节点；不发明编号。
- 后续处理有框复杂表、公式、扫描／隐藏 OCR 和 Word 特殊内容。无法恢复公式结构时保完整原公式图、释义及 unknown，扁平文字不冒称结构通过。
- 范围内规范插图保可见主体、caption、legend、notes 和所属段；裁图完整性不确定时返回完整原页并标明未知关系。
- 先核完整输入、区域覆盖、正常输出及导出保全；模型收到正确输入后仍存在可复现识别残差，才在现有模型框架内开展针对性标注与参数／必要权重训练。
- 训练保独立来源划分、基线、模型与数据身份、训练和留出结果、旧正确项回归。已暴露源页仅为开发回归；没有收益的训练不替换基准。每轮有新训练／质量结果时更新已有 HTML 与 handoff，不另造综合准确率。

### D5. 共同证据与持久数据契约

- 固定参考与项目证据有显式不同的来源类型和回取路径；固定 sourceId 不拼进项目文档入口，标准不能伪装成项目已采用条款。
- 固定基准引用绑定 baseline／generation／manifest 身份；项目引用绑定 project／document／source snapshot／revision。完整结构单元绑定 clause、原页、group 和 member 身份。
- 完整证据组保存 required、present、missing、unknown、成员角色、必要条件和资产身份；相关父条、AND／OR、例外、相反条文、Notes、修订与完整表组可回取。
- 审查 run／packet 保存来源、模型 profile、输入身份、执行状态与结果；人工核对、finding 裁决和备注保存精确审查／生成版本。
- Advisory 保存独立 session、项目、证据范围及逐消息的来源 snapshot、模型 profile、证据和引用；显示历史不能把旧消息重新绑定到新来源。
- 来源查询、资产回取、前端引用与导出共用以上身份语义。具体数据库 migration 和 DTO 由实现者按现有公共业务接口落实，避免另建平行证据体系。

### D6. 检索、模型输入与版本守卫

- 保留 BGE-M3、BGE-reranker-v2-m3、BM25、向量召回、结构扩展和 rerank。命中后取完整业务单元，不能以短向量窗代替完整父条或表组。
- raw／CAS、完整坐标、审计字段和原资产留来源存储，按身份核验和回取；模型投影保实际正文、必要限定、采用角色、相关 unknown 和简洁来源指针。
- 同一来源身份和不可变校验结果可在正确失效边界内复用；原文不因性能优化删减。轮询和每个提示不重复搬运整个来源 JSON。
- 按实际模型、tokenizer、chat template、最终 wire 输入及 schema 计算准入和输出预留。总窗口至少 256K、优选 512K；RAG 不豁免该要求，也不预设 H800 已验证。
- 必要证据不能容纳时显示预算状态，按完整单元分包或减少并行；不静默截断条件、换模型／库或把未执行包记为通过。
- 取证、调用、保存和导出核来源版本；发现变化拒绝旧结果冒作当前，保留历史并提供重跑／复核入口。旧版仍被项目明确采用时以旧版身份使用。

### D7. Qdrant 交付目标与基准保护

- 目标为单实例 Qdrant Server＋PVC，由 Vetting 和 Advisory 共用检索入口；现 Local05 及其冻结成果完整保留。
- 从冻结的结构化来源与向量建立新 collection，不直接复制 Local 存储目录成 Server 存储，不要求无变化原件重新 OCR。
- 迁移清单绑定 embedding revision、维数、归一化、距离度量、点／向量／payload／父子映射及原资产。按冻结 manifest 复算与读回，不把两种记录数混用。
- 切换前核全量预期记录／向量和来源身份、实际查询及原件回取，并保存回滚配置。未通过保持目标未采用，不覆盖旧库。
- 若目标制品尚未闭合，Local 仅由一个明确 owner 提供有界后备；单实例、单 worker，旧 owner 退出后发布。PVC 的 RWO 不是防多进程写锁。

### D8. Vetting 执行、覆盖与恢复

- 用现有 Java／SQL 和有界 worker 持久化 packet checkpoint、输入／来源／模型身份与幂等结果；唯一执行 owner／lease 防止旧执行者和重复 dispatch 发布。
- 排队、执行、等待 GPU、重试、失败与完成可查询；进度轮询读取轻字段，不读取整份证据／报告。完成是执行终态，不是业务无风险判定。
- 重启后从已验证 checkpoint 处理未完成包；调用结果不明的包显式重试或标中断，已保存结果不重复发布。没有兼容 checkpoint 时显示需重跑，不能声称续跑成功。
- 自动检查状态至少区分 checked-pass、issue、insufficient-evidence、not-completed、not-applicable。通过必须有实际已执行检查及完整必要依据；无 finding、空数组、HTTP 200 或模型 ready 均不构成通过。
- 覆盖按任务范围和执行账本计算，与 finding 数量、人工 checklist 分开。缺口可界定时仅暂停受影响结论；不可界定时标整份不完整。
- 不恢复已撤回的风险数量上限。因输出预算分包时保持完整必要检查和结果状态，不暗中截取发现数量。

### D9. 人工核对、裁决与报告

- 人工 checklist 默认未核对，不能由自动无问题勾选。裁决及团队备注进入既有 SQL，按项目／run／source snapshot／finding revision 保存。
- 精确相同证据与生成版本的重跑可复用对应人工记录；证据或范围改变时受影响记录待复核，保旧历史，不跨项目继承。
- 三格式报告从同一冻结 report snapshot 生成，包含来源版本、适用范围、执行覆盖、缺证／未完成／不适用、问题、人工响应和引用。
- 当前正式导出仅接受执行 COMPLETED、来源版本相符且已保存的 report snapshot；QUEUED／RUNNING、FAILED／interrupted 或来源过期的当前 run 拒绝正式导出。失败／中断仍可查看已持久化的可靠包、失败原因和未完成账本，不将 checkpoint 冒充已完成报告。
- 执行正常完成但业务有界缺证时，允许导出明确标有完整性限制的报告，不能把限制删掉；历史报告保持原版本身份。本轮不增加失败／中断 run 的部分报告导出，避免将运行失败伪装为 COMPLETED。
- 用 POI／PDFBox 输出可读原生 DOCX／PDF，实际核字体、分页、长引用、表格和尾文；demo 截图 PDF／HTML-as-Word 不作正式路线。

### D10. Advisory 会话、范围与交接

- 显式项目／session／证据范围；项目咨询与轻量 reference_only 标准解释入口分开。固定参考解释不宣称项目采用，项目咨询缺采用证据时明确限定。
- 受控历史只来自同 session、项目、范围和允许的版本，按最终预算保留；次数、消息和 token 上限在运行 profile 中显式配置，沿用已有有限澄清为起点，不无界注入项目历史。
- 快速问题调用真实取证与模型。用户补充背景标为用户假设，不伪造文件引用；澄清 parent 必须来自相容会话和范围。
- 点击历史答案／其中某条引用，展示该答案／该引用自己的依据，不沿用最后回答或之前的全局选择。旧请求晚到不得污染新项目／session。
- Vetting→Advisory 生成可编辑问题／未验证引文草稿，携带 finding 和来源身份；沿用项目绑定、有限有效期及一次性消费。跳转不自动调用模型，明确提交后独立核当前来源；不自动回写审查裁决。
- 失败、缺证、stale、预算不足显示明确原因。历史保留，不静默用旧库或其他模型伪装当前成功。

### D11. demo 对应的正式前端交互

- 采用 demo 的选包、文件／条款／问题导航、原文旁问题卡、核对底稿、裁决／备注、快速问题与证据抽屉组织；消费真实 API 状态和来源身份。
- 点击 finding 打开其原文和完整证据；在切文件／项目／run／答案时同步正确上下文，展示缺成员、缺资产或过期原因。
- Q10=A 首版用相关文字取证，向用户展示准确完整原图／整表及必要注释、跨页成员和来源定位。现 demo 主要是文字证据，完整原图／整表为待实现增强。
- 现有 Mermaid／DOMPurify 可展示回答中的条款流程图；渲染失败保原回答与图表文本，流程图不是原始依据，不能增添无来源步骤。
- 普通 Markdown 全语法和可复制 LaTeX 排版为可选增量，未指定必装包；公式展示库不修复源公式语义。保已有界面语言能力，不把显示翻译写回原文证据。
- 不采用 demo 的固定 QA、无 finding 默认 pass、零计数即检查完成、localStorage 正式持久化、同名替换即解析成功、已排除材料缺失失败或风险数量上限。

### D12. 组件与版本矩阵

| 层次 | 下一轮方向 | 版本／采用边界 |
| --- | --- | --- |
| Web | Vue 3／TypeScript、PDF.js、既有 Mermaid／DOMPurify | 复用 npm lock；锁 Node／npm 构建版本，交静态产物 |
| 业务／关系数据 | Spring／JPA／JDK17、MySQL | 保现架构；Boot 2.7 等旧版本交扫描核查并按结果整改；新 MySQL 以受支持 LTS 为目标，8.4 系列是待验证候选 |
| 原件／导出 | POI、PDFBox、已有 PDFium／pypdf／pdfplumber、LibreOffice 和真实字体 | 锁原生库和字体，保原件与可见态 |
| native-table | 独立 CPU Camelot 基础包＋pdfplumber | 候选 Camelot 2.0.0／pdfplumber 0.11.9；完整 Linux 传递锁含所需 OpenCV／PDFium；不加 ML／OCR extras |
| OCR／复杂布局 | PaddleOCR／PaddleX＋MinerU full 独立环境 | 当前路线 PaddleOCR／PaddleX 3.6.0、MinerU full 4.0.10；复用已验证 Linux hash lock，不混合环境 |
| retrieval | BGE-M3／BGE-reranker-v2-m3、BM25、Qdrant | 固定模型 revision；隔离 retrieval 与 MinerU 的 Transformers 版本 |
| 向量库目标 | Qdrant Server 单实例＋PVC | 候选 Server 1.19.2／client 1.19.1；兼容、迁移与实际回取后采用 |
| 业务 LLM | 独立 vLLM profile，Qwen3.8-27B 官方 FP8 首测 | 既有架构报告锁定的模型 revision；至少 256K／优选 512K，业务与 H800 未验；M3 为可选开发参照 |
| 编排／资产 | K8s、SQL、PVC 文件资产与身份清单 | CPU 服务持续，单 GPU 阶段交接；不增重型调度或对象存储平台 |

- 软件家族和候选集合确定不等于最终二进制冻结；安装成功不等于采用。精确 patch、完整依赖、模型／字体 SHA、OS 和镜像 digest 在制品验证中锁定。
- 业务 vLLM 首测可复用已成功 Linux 底座的 0.19.1／Torch 2.10.0＋cu128／Transformers 5.10.1 配方方向，独立验证模型加载。官方同 tag 镜像可能是 CUDA 12.9.1，不能冒称同一配方；不盲升 latest／CUDA 13。
- MinerU full 和业务 LLM 不强制同一 venv。候选加载或兼容失败显式保留失败，按实际差额修独立 profile，不悄悄改成另一模型。

### D13. 单 H800／K8s 与离线制品

- 目标资源按已确认单 H800 80GB、27 vCPU、250GB RAM、650GB 高性能及 1250GB 通用存储规划，最终以实际集群核验。现本机成绩不等于目标峰值／速度。
- CPU 业务、SQL、向量库和资产存储持续运行；Paddle、MinerU、文档 embedding 以批次顺序使用 GPU。在线 query embedding／rerank 也纳入取证阶段，再交卡给业务 LLM。
- 首版独占阶段 Pod 前一工作负载真正退出并释放整卡资源，再启动后一阶段；结果先持久化。vLLM sleep 释放显存不等于归还 K8s GPU 申请。
- 准入核正常阶段与切换峰值、模型状态／workspace 和余量；未经实测不默认小模型共驻。官方设计的 MinerU CPU 辅助层、原生文字表和业务数据库可保持 CPU，适合 GPU 的模型入口采用 CUDA。
- GPU 加载等待、ready、超时和恢复状态对用户可见；不为等待增加 WebSocket／消息队列依赖。
- 模型与原件只读挂载，可写工作目录和数据库／资产 PVC 明确分开，记录权限、备份／恢复及单写者。目标镜像离线启动，不在现场隐式 pip／npm／apt／模型下载。
- 交付绑定源码 commit、OCI digest、OS 包、Maven resolved tree、npm lock、pip hashes、模型／tokenizer／processor／template／必要自定义代码、字体、许可证与真实扫描报告。扫描规则可能要求后续代码复扫，不承诺确定家族即可免扫。

### D14. 下一轮实施顺序

1. 先形成共同身份／状态契约和依赖制品清单，以本规格的有限组件集合准备扫描；保护冻结 05 与 Drafting。
2. 并行推进解析候选及结构恢复、固定／项目来源与模型投影接缝。解析候选未采用时，两模块仍使用明确缺陷边界的现有开发基准。
3. 在共同契约上并行实现 Vetting checkpoint／覆盖／人工状态，以及 Advisory session／引用／handoff，随后联动 demo 导航和资产展示。
4. Qdrant Server 使用新 collection 完成迁移、读回和回滚验证；未闭合时保单 owner 的现入口，不把迁移等待当推倒业务实现的理由。
5. 按已确认业务链跑真实文件／模型／浏览器／报告回归，修已归因缺陷并更新已有 HTML；最后以实际锁定制品验收 K8s／H800 和安全交付。

## Testing Decisions

用户已确认以上主业务接缝。优先通过公开业务 HTTP 工作流、持久结果重开、证据回取、前端点击和实际导出验收；不以私有方法调用、组件存在或数据库单条记录代替行为。现有适配器可用于契约替身，真实来源质量、检索、模型推理、浏览器和目标资源另行实测，尽量不增加新底层测试接口。

| ID | 外部行为／场景 | 可判定的验收结果 |
| --- | --- | --- |
| AC01 | 真文件上传；同名／新版本；图纸与未填模板；SL 范围 | 文件身份与版本独立；排除材料不进入解析／检索／模型包；实际项目三稿仍可用；图纸无细节 parser 调用 |
| AC02 | 表单原生／扁平／扫描；空槽、勾选和 OR | 原件显著成员在正常完整输出中保留；blank、No、NA、unknown 不混淆；源值／外观冲突明确 |
| AC03 | 复杂正文、跨页父条、编号和旁注 | 读序、层级和限定归属与原件核对；无编号成员不被造号；输入／导出缺口不当作识别成绩 |
| AC04 | 无框表整表、Notes／星号／缩写及旧正确有线表 | 候选原格与逻辑恢复可回指；完整源组独立评分；缺列被发现；无 gold 补格或逐格融合；旧正确组保持 |
| AC05 | 训练候选及独立留出来源 | 正确输入下残差、标注、基线和新权重身份可复算；开发与留出分别报告；无收益不替换；已有 HTML 保旧失败并追加新轮 |
| AC06 | 固定与项目同名／ID 碰撞、未确认采用、未知版本 | 回取到正确类型／版本；标准不变成项目事实；缺采用证据保持限定或 unknown |
| AC07 | 整组取证、缺条件／续页／资产、预算不足 | 结果和引用保存 required／present／missing／unknown；必要限定不截断。同run中A缺必要成员时A暂停／缺证，互不依赖的B仍实际执行并产出有据结果，不能全量阻断 |
| AC08 | Qdrant 新 collection 全量读回和真实查询 | 点／向量／payload／父子映射符合冻结 manifest；原文／资产可回取；模型／度量身份一致；Local05 保留且可回滚 |
| AC09 | 最终 wire 模型准入、schema／输出 reserve | 以实际 profile 计数；over-budget／budget-unknown 不伪装已提交；完整分包与未完成状态可见；不使用其他模型计数冒认 |
| AC10 | 调用中／保存前／导出前来源变更；同大小改字节 | 旧结果不得冒作当前；历史仍绑定旧来源；错误复用及隐式换库失败被保留 |
| AC11 | 正常零 finding、未执行、输出耗尽、模型失败、缺证、NA | 五类检查语义与实际账本相符；finding 数、执行终态和人工核对分开；缺失范围无法界定时整体incomplete并保已完成可靠B；零finding／NA不掩盖覆盖缺口，无默认pass或风险数量上限 |
| AC12 | 排队、调用中、结果保存前后中断及重复 dispatch | 在隔离自有 worker 真实重启；checkpoint、owner／lease 和幂等结果持久；未完成不假完成；不可恢复时明确需重跑 |
| AC13 | 刷新、重跑、跨项目、旧 snapshot 的裁决／备注 | 精确版本复用；改变依据后待复核；非法／过期写入原子拒绝；历史和未涉及记录保持 |
| AC14 | API 轻进度、网络恢复、切项目及晚到请求 | 进度归正确 run／epoch；等待 GPU 可见；重开能读持久态；不因轮询读取巨大 report/source |
| AC15 | 同一 report snapshot 的 PDF／DOCX／JSON；完成但有界缺证与失败／中断对照 | 三格式范围、覆盖、unknown、finding、人工回应和引用一致；COMPLETED有界缺证报告保限制，FAILED／interrupted／未完成／过期正式导出拒绝且可靠记录可查看；实际核字体／表格／分页／尾文，业务错误不下载为文件 |
| AC16 | A 答案后问 B，再点击 A 引用；切 session／scope | A 打开自身版本与原文；固定／项目两条回取正确；旧请求不污染新会话；历史不换绑新来源 |
| AC17 | reference_only、项目咨询、缺证澄清、Vetting→Advisory | 标准解释与项目采用分开；用户假设不作引用；handoff 仅可编辑草稿，跳转无模型调用、无裁决回写 |
| AC18 | 范围内图表／公式、完整原页回退、Mermaid 成功／失败 | 原图／整表及必要成员真实显示；不完整关系明确；流程图不替代原件或引入无依据条件；安全渲染失败保回答 |
| AC19 | 最终 Linux 制品断网启动和 K8s／H800 阶段切换 | 无隐式下载；实际模型加载；前 owner 真正退出、整卡资源释放、后一阶段 ready；持久结果恢复；峰值与等待实际记录 |
| AC20 | 最终交付收据和受保护范围 | 扫描与实际 digest／源码／模型／字体一致；代码、契约、原件、模型、浏览器、H800 结果分列；Drafting 行为和冻结05原成果保持 |

- 复用现有固定检索／来源 controller、Vetting workflow／JPA HTTP progress／checklist／packet persistence／generated revision、Advice scope／model identity 和前端证据／下载测试的公共接缝。
- 当前生命周期先例只证明重启将 active run 标中断供重跑；AC12 为待新增真实进程恢复验收，不能以既有测试追认自动续跑。
- 既有 CanonicalPageExcerpt 失败及其归因收据保持失败状态；实现时复核最终契约，明确修复或保留说明，不通过删除／跳过来假造全绿。
- 真实业务集包含官方问题样本、结构化 Vetting 报告、新变量值／新来源例子、跨来源冲突、条件例外、缺证和版本变化。评估遗漏、误报、错误采用、引用及完整检查，Vetting 与 Advisory 分别计分；M3 的 Drafting 成绩不替代两模块验收。
- 解析评分区分完整输入、正文、结构、完整表／条件组、位置和旧正确项；沿用现有来源质量标准及各自分母。文字 anchor 命中率不叫整表准确率，资产数不叫正确表数，不合成无依据的总体准确率。
- 回归记录具体数据／来源／配方／模型身份、失败、skip 和未执行项。故障演练用隔离副本及自有 worker，不终止无关服务或打开旧 Local 库并写。
- 本轮只对规格、任务依赖和文档链接做静态核对；不执行上述实现验收，不将规格发布写成测试通过。

## Out of Scope

- Drafting harness、变量识别、确认值、保存正文、生成动作和版式改动；此前已完成的热修不扩展成新授权。
- 图纸细节解析、CAD／矢量／尺寸／符号／工程关系模型、图纸标注训练；已排除资料的解析或必需输入补齐。
- 全局替换 parser、按答案逐格拼表、已知页码／bbox／项目值硬编码恢复、无完整输入归因的盲目 SFT。
- 首版业务 LLM 看图、多模态 wire 改造、CLIP／新视觉 embedding 或新增大视觉模型；Q10=A 完整原图返回用户保留。
- 新增第三套 OCR／IDP、LangChain／LlamaIndex／Dify、Neo4j、Elasticsearch、Redis／Kafka／Celery／Temporal、MinIO、Chromium 导出及重型 GPU 调度平台。
- Camelot ML／OCR extras、Table Transformer／Tabula／Ghostscript 的新增安装；既有环境真正需要的同名依赖不因此被删除。
- Flash-Next 专用引擎／offload 和默认多模型共驻；未实测的 sleep 共享不能取代首版资源交接。
- 承诺最终原件质量／完整业务／安全扫描／H800 已通过；远端 issue、commit、push、PR 或实际运行切换不由本轮规格编制触发。

## Further Notes

- 本规格按用户指定的 to-spec 模板编写，验收主接缝已由用户明确确认。本轮后续纠正“先别开发，就写 spec”优先，执行状态保持 implementation-not-started。
- 采用仓库现有本地 Markdown 任务图惯例发布到[下一轮任务图](./oer-vetting-advisory-next-development-tickets-20261012.md)，规格标签为 ready-for-agent；未配置本轮远端 tracker，不创建或关闭远端 issue，既有 Drafting 任务及关闭状态保持。
- 决策依据：[范围规则](../oer-scope-and-rules.md)、[当前交接](../HANDOFF-ocr-embedding-rerank-20261008.md)、[整体技术栈评估](../delivery-stack-decision-20261012.md)、[无框表调查](../oer-borderless-table-isolated-investigation-20261012.md)、[固定05开发基准](../fixed-oer-development-baseline-20261012.md)、[LLM架构报告](../consense-llm-oer-architecture-analysis-report-20261012.md)。技术栈报告中的研究建议在本规格转为下一轮候选验证工作，不追认当时已部署。
- 产品输入：[communications V2.0 demo](../../../Ref/communications/ConSense-CAC-Proposal-DemoV2.0.html)，SHA256 `92ec31ed62ba746e7cb7de236ea80d7fb642ea2bb06af87fcaebf6e0dbda65fb`。原型未成为业务 gold；本轮不运行原型或浏览器验收。
- 沿用 ADR：[局部不确定事项处理](../adr/0001-localize-uncertain-review-evidence.md)、[保留双解析并按完整结构选择](../adr/0002-preserve-dual-parser-evidence-and-route-by-structure.md)、[模板与两业务模块隔离](../adr/0003-separate-drafting-templates-from-vetting-advisory.md)。用户最新图纸排除与21:00开发基准安排覆盖旧历史表述。
- 原件、密钥、模型、生成大资产和实际运行收据留忽略的本地／持久目录；可共享规格和任务图只引用身份与结果。实施后交接必须列待过门，不以静态文档检查代替业务证据。
