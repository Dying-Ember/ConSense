# Drafting 变量图谱逐项还原与两版 SCC 审计

## 逐环节原子标注修订

用户明确要求的是给每条变量影响链的每个部分逐项打原子类型标签。初版 `index.html` 仅汇总整链的关系、影响和编辑类型，不能满足这一要求；其源码及原始审计证据保留为历史摘要。当前以 [逐环节原子标注页](../../../.local/drafting-variable-graph-audit-20261015/atomic.html)、[原子图 JSON](../../../.local/drafting-variable-graph-audit-20261015/atomic.json)及[原子标注检查回执](../../../.local/drafting-variable-graph-audit-20261015/atomic-receipt.json)为入口。

当前覆盖仍为 Word 的 79 个业务定义、1 个系统容器，以及独立 PDF SCC 的 74 个处理点。每个环节分别声明以下字段，不再让一份整链输入集合与全部目标做笛卡尔对应：

| 环节内容 | 固定标签及明确参数 |
| --- | --- |
| 读取的值 | V1–V8，变量／子字段、raw／effective／derived／final_adopted 阶段，以及本环节的主输入、前置、共同作用或来源角色 |
| 进入条件及取值分支 | 逐叶 P1–P8 与 all／any／not AST；guard、case、未命中和多命中政策分别记录 |
| 相互影响和共同作用 | 每项具体 R1–R10、C1–C7，适用的 F1–F10 纯配方及参数；R2 的 D1–D3 域配方另记 |
| 中间结果 | 逐项 I1–I7、结果 V 型、值来源／固定配方、已知／未决／不适用／冲突状态，以及上下游引用 |
| 作用范围 | 每个准确位置单列文档、条款、原段号／页号、用途、物理范围、原文预条件及绑定状态 |
| 作用方法 | 每个分支、每个位置分别标 A1–A7 基础动作或 E1–E4 组合配方，并声明替换文字、值引用、成员集合或编号映射 |
| 失败与缺证 | false、unknown、invalid／conflict 出口、来源与能力缺口；已有行为、观察到的问题及拟补约束分别保状态 |

例如 `projectInTinShuiWai` 是 V1。它的住宅适用前置由 R1／I1 单独表达，随后由地点取值形成十／二十年的选择，再读取最终采用决定，对 P570 的准确文字作 A3，P554／P572 的已决指导分别作 A7。住宅条件不成立时，不把“不采用”擅自编译为删除。另一个例子是 `electronicTendering`：NTT2 单独读取 V5 格式值；NTT3(c) 另有双信封前置；SCT1、SCT3、SCT5 行结构、标签与引用分别有自己的节点和位置动作。

原子还原补出 F10 有界标识解析，用于 Section 引用文字到标识 token 的转换；固定分隔符、trim、大小写、重复、空项与越域政策明确，后续允许域／基数／角色仍分别核验。Bill1／2 的两类固定描述则由 P1 已注册文本比较政策和 F2 决策表表示，不再虚构通用归一化键，也不硬贴 F9。

最终采用是显式证据输入边界。没有本项目采用回执，只表示对应输入尚未知，不能自动采用分析候选；规则本身仍需类型明确地描述验证和投影。PDF 的核查回执也先经过独立 R9 核来源、版次、要求 ID 和证据完整性，再读取核查状态，不仅凭 `verified` 字符串判通过。标签映射、标签位置和叶引用已经分别标注；Section 的身份、子集、资格、域、选择、成员核验和多槽扇出分别保环节。

下方初版观察继续保留；逐环节结果收紧了实际修改范围和负向形式。已有精确负向 scope 的 SCC 条目按正文保号 Not used 与目录对应修改还原，不能沿后置 switch 的 fail 误判未支持；SCC11.302 的 A／B 范围也按实际编译器收窄，避免将邻接条款计入目标。未决来源和真实能力缺口没有因此关闭。原子类型、条件 AST、目标范围和报告展示的检查不代表新解释器或原件生成验收。

## 初版链级审计及证据

2026-10-15，按 `typed-graph-v0.2-candidate` 的值型、业务角色、影响、关系与编辑意图，还原当前 NTT／SCT／Word SCC 变量图谱，并独立重分类另一版 PDF SCC 的全部既有处理点。两版可以共用类型与组合方法；采用条件、项目事实、条号、完整目标组及原证据必须分别绑定来源。

目前没有证据支持直接删除任何现有业务字段。主要问题是最终人工采用后缺成组核验、适用性未统一、外部核查未结构化、部分负向编辑出口未闭合，以及参与关系被误画为因果。冗余主要存在于条件必答、重复自由输入和共享物理编辑的表达，不等于对应业务事实无用。

本轮是有原证据的分析和有限纯规则验证；没有修改产品实现、删除字段、编辑原件、调用模型／OCR，或操作活动数据库／服务。独立本地报告预览位于 `http://127.0.0.1:5190/index.html`，不影响产品前后端。

## 完整还原结果与复算入口

原文及分析 JSON 仅存忽略的本地目录 `.local/drafting-variable-graph-audit-20261015/`，不复制赛方原文到 Git。

- [交互审计页](../../../.local/drafting-variable-graph-audit-20261015/index.html)：Word 变量、PDF 处理点、33 条观察及两版对照；每项可查看值分支、共同输入、关系／影响类型、派生、目标、编辑意图及原证据。
- [完整结构化结果](../../../.local/drafting-variable-graph-audit-20261015/audit.json)：保全部原字段视角、候选、未决与来源。
- [结构与证据检查回执](../../../.local/drafting-variable-graph-audit-20261015/receipt.json)：分母、输入 SHA、检查范围。
- [浏览器报告检查](../../../.local/drafting-variable-graph-audit-20261015/browser-checks.json)：报告自身的逐项渲染及筛选检查，不是产品端到端验收。
- [合并与自检脚本](../../../.local/drafting-variable-graph-audit-20261015/combine-audit.py)，以及同目录 `prepare-audit.py`、`build-core.py`、`build-inputs.py`、`build-special.py`、`reconstruct-pdf.py`。

`core.json`、`inputs.json`、`special.json` 分别还原 28／25／27 个 Word 顶层定义。合并先检查完整 key 集合，再按链 ID 和完整内容保留不同字段的焦点视角，禁止同 ID 后写覆盖。

| 分母 | 已核结果 | 可复算来源与解释 |
| --- | ---: | --- |
| Word 顶层定义 | 79 业务＋1 系统 | `baseline.json.word`、当前 `input-catalog.json`，集合精确一致 |
| Word 嵌套字段定义 | 28 | `baseline.json.word.nested`；含业务技术 ID 与系统 6 列，不另算 28 个提问 |
| Word 影响链 | 45 个 ID、59 个焦点版本 | `audit.json.chainIndex`；不同视角全部保留，不是原子边数 |
| 旧目录关系投影 | 111 动作、336 关系 | `baseline.json.word`；229 action_input＋81 catalog_affects＋26 field_condition，不能全称值因果 |
| 旧有限合成记录 | 89 记录、36 顶层字段显式给值 | `baseline.json.word`；剩余 44 业务字段未在这些记录显式给值，不代表当前实现没有这些字段 |
| 有限单输入对照 | 302 个记录对 | `finite-contrasts.json`；两个记录仅改变一个顶层输入且动作语义结果有变化，不是 302 个独立业务事实或穷尽验证 |
| PDF 处理点 | 74 | `pdf.json.points` 与原 inventory 的 ID 集合一致；不是 74 个用户变量 |
| PDF 逐字引文 | 144 | 每条在本版对应缓存页中精确匹配 |
| PDF 原证据状态 | 62 explicit、2 inferred、10 unknown | 原 inventory 状态逐项保持，未因重分类升级 |
| PDF 分析角色 | 6 事实、15 决定、1 内容、12 派生、40 核查 | `pdf.json.roleCounts`；40 核查点的正式事实输入仍待定义 |
| PDF 上下文 | 42 分析概念、45 Word 候选参考上下文 | `pdf.json.contextConcepts/candidateContexts`；不是已采用产品字段，候选上下文不充当本版已证依赖 |

## 优先处理的遗漏

以下编号定位 `audit.json.findings`，详情和原文／代码行在审计页可展开。33 条记录包含表达问题、简化候选和版本边界，不能称为 33 个已证产品缺陷。

| 观察 | 当前证据 | 应补的逻辑 |
| --- | --- | --- |
| SP-01 最终采用后的互斥核验 | 人工同时 retain Word 8.302 与 8.303，单项通过当前目标校验，未产生对应互斥问题；原编制要求明确互斥 | 保人工值，最终采用结果形成后执行 R9 成组约束，不能用最后写入胜出 |
| INPUTS-F01 inactive 旧参数重新参与 | `advancePaymentAdopted=false` 时还款子动作不采用；只增加隐藏旧值 `advanceRepaymentMonths=0`，子动作转 pending，且无对应 InputInvalid | 子规则统一读取适用的有效值，保原始旧值而不消费 inactive 值 |
| INPUTS-F03 人工父采用后的下游传播 | 人工 retain 专业 20.303 后，一般引用更新，安全计价仍 not_adopted | 核最终父采用后的子要求完整性，并保人工 retain 权威；仅证明 plan 传播缺口，未执行实际 DOCX 写入断言 |
| SP-03 Section 角色与基数 | relevant 与 specified 相同，或 relevant 填多个 Section，仍可形成 amend | 将单数 relevant Section、Specified Sections 的角色及范围约束结构化 |
| CORE-03／04、SP-04／05、INPUTS-F06 外部核查 | 图则不可公开查阅时另备图、PRE.B6.680／B6.612 配套、其他防水 Specification 及最新抄印费等要求未独立成节点 | 建 I7／R10 核查或采用证据，不能全部变成新增必填变量；图则只核存在／纳入事实，不解析图纸内容 |
| SP-02／08 负向出口 | 部分质保及 8.302 不采用决定缺已核原件退出范围；源码路径及纯文本 apply 探针显示 unresolved 并保原文，未做实际 DOCX 生成验收 | 在目标整组与负向范围得到来源确认后，再定义删除／Not used 等配方，不能把 not_adopted 自动翻成删除 |
| CORE-05 清单身份与约束 | 设计责任项缺稳定成员 ID、重复构件及相反责任检查 | 成员身份、scope 与关系冲突分别校验，同构件不同范围不能静默去重 |
| CORE-12 声明依赖缺口 | 消防映射进入 SourceCheck 后只留 subcontractors，丢掉分包制度参与；有限单输入对照与源码一致 | 保守失效读取集合与具体语义因果分开维护 |
| INPUTS-F02 目标出现位置 | 两个还款参数的 catalog affects 仅指 P1214 指导，实际参数位于 P1220 正文 | 分离“来源要求位置”和“实际修改出现位置” |

价格方案与 Bill 行类型、设计事实与逐构件责任、Lift 与 Lift and escalator 重叠等继续保待核：必须先确认完整清单及相同业务范围，不能仅凭有限输入称为业务冲突。

## 冗余如何处理

1. **条件冗余提问。** 无设计责任时两个设计时点及报价字段仍无条件必答；主体未知但两条法律采用决定已明确时，主体形式仍产生缺值问题。应调整适用／提问关系，而不是删字段。Section 地点全局必答也列为简化候选，地点仍可作为分离事实的依据。
2. **可派生结果。** 精确工期和 39 月判断存在补缺／人工优先关系，不能机械去掉一个权威；唯一 demolition-only 候选可派生，多候选仍需准确选择。数量、编号、引用优先按稳定身份派生，不另问自由文本。
3. **共享一次编辑。** general／PSE 多项要求合并后插一次，Bill 的汇总／分发视图可共用物理编辑；保每个贡献及来源，不把多视图理解为多次插入。
4. **不能合并的事实。** 安装存在与质保要求、规范与图纸查阅地址、两档抄印费率、树号三处必要扇出分别保留。partnership 与 joint venture 两采用开关不是互斥同义项；unincorporated JV 可同时涉及两条。
5. **表达上去假因果。** `contractTitle` 对包装指令只是参与／核验；`latePossessionAdopted` 影响训练子条编号，不影响训练资格。共同 `inputKeys` 不能直接变成每项值对每个目标的因果边。

## 两版 SCC 的具体差异

Word SCC 来源 `scc-docx-9aa2fa066836`，SHA256 `9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208`；PDF 来源 `scc-pdf-1bebab1afbc0`，SHA256 `1bebab1afbc0faaf8aa9c0b9e6c51b023c24e53a094d4987d746fdeb65b30741`。本轮重核两原件 SHA；NTT／SCT 的当前分析沿用已绑定 catalog 与既有计划，未重新解析其原件。

| 范围 | 本版应保留的差异 |
| --- | --- |
| PDF 4.2 → 5.2 | 新增独立“投标时是否要求拟用分包商资料及经验”事实；不要求时 A(1)–(8)，要求时 B(1)–(9)，5.2 分别保 2／3 或 3／4 引用组。双信封不能代答此选择 |
| PDF 8.2 → 22.8 | 读取本版 8.2 最终采用；A 为原 xiii→xv、铁路 xiii＋NSC xiv，B 为原 xiii→xvi、铁路 xiv＋NSC xv；不套 Word 8.304／22.307 |
| footings 局部范围 | PDF 7.1／8.2／22.5 的邻近星号不授权扩大到 piles、pilecaps 或整条删除。8.2 仅正文有对应星号，另外两条的对应标题也需协调 |
| 质保及采用指导 | PDF 不能继承 Word 后增住宅门、安装 AND 门或未知主体 fallback；缺本版依据时保持 unknown |
| 分包与计量 | PDF Nominated 与 Word Specialist／BSSSC 不是同实体；PDF 11.2 无 Word 11.302 的 Option A/B，不能补入旧选项 |
| PDF 8.7 训练资格 | building contract、合资格独立楼宇拆卸、首月适宜与采用决定分别取证，不能用独立楼宇事实代答整体合同类型 |
| Word 独有能力 | 本轮 PDF 全文库存未识别到预付款对应点，保 Word 能力，不给 PDF 补标准 6／7 或其他默认 |

PDF 跨页归并后的 `PDF-SCC-4501` 有两组重复 Word 候选对应：subcontractArrangement 与 subcontractors 各重复两次。各归并为一条对应，所有依据及原始候选记录仍保留。`PDF-SCC-2328` 是该点合并别名，不另计分母。

## 检查结论与后续门

合并自检通过：80 字段与 28 子列完整、45 链保全 59 视角、类型枚举／真实 action ID／输入引用有效、Word 引文回到当前来源、12 项代码／目录绑定及两 SCC 原件 SHA 未漂移、PDF 74 点／144 引文及原状态保持、候选与依赖 namespace 分开。独立复核确认代理材料没有在合并中丢失或升级结论。

浏览器检查覆盖报告的 80 个 Word 条目和 74 个 PDF 条目逐项渲染，以及系统容器筛选、40 核查点筛选、问题全文搜索、原证据展开和版本对照；这只是报告本身的可用性检查。当前产品自动化、真实原件生成、模型语义识别和动态关系解释器不由本轮授绿。

后续实现先统一最终采用与约束复核、适用性和核查状态，再补准确编辑出口及定位元数据。继续沿[动态模板前置任务图](../specs/drafting-template-adaptation-prerequisites-20261015.md)与[类型契约](../specs/drafting-typed-graph-workflow-20261015.md)推进，不把本审计的手工还原视为自动引擎能力。
