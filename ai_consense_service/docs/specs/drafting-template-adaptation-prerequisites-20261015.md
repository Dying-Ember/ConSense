---
title: Drafting 动态模板识别与 SCC 版本适配：前置能力与任务图
status: proposed
date: 2026-10-15
timezone: Asia/Shanghai
tracker: local-markdown
execution: planning-only; no-code-model-parser-effect-index-or-deployment-started
baseline: 10c3eaf3057a3a45cbf8747468cd48ddf78287aa
---

# Drafting 动态模板识别与 SCC 版本适配

本轮交付是能力盘点、实施任务和阶段门。用户要求先拆清前置能力，不能把任务清单视为立即开始开发、真实模型调用、原件质量测试或部署的授权。业务默认值和标记的具体解释尚待按模板说明及 PM 例子核实，不能补造成已确认规则。

## Problem Statement

比赛可能更换 NTT／SCT／SCC 模板，增加、减少变量或改动条款编号、位置及适用条件。当前字段目录、条件动作和部分生成位置以既有模板编制；仅改表单字段，不能保证新版模板的识别、选择、删留、交叉引用和生成正确。

实际 SCC 对照已出现同主题重编号、本文件未找到旧条款，以及主题相近但支付对象／计价归属不同的情况。识别对象还包括没有星号／井号的下划线空白、选项及编制说明。因此，条款编号不能作为跨版本业务身份，符号数量也不能作为业务变量分母。

首先需要用现有引擎证明：原件内容和位置是否完整保留、所有模板处理点是否有下落、新旧对应与语义变化是否可核查。只有区分解析缺陷、识别缺陷和产品能力缺口，才能冻结本轮修改范围。

## Solution

复用既有正常上传／解析／持久化来源／原件回取，以及项目 catalog → 输入采用 → plan → 原件锚定编辑 → 保存修订的接缝。新增行为集中在“本次模板修订的分析结果”：输出条款与引用视图、处理点清单、新旧对应、字段定义、默认依据、条件关系、实际目标及未决项，供原有产品流程消费。不要并行另建 parser、全局业务词典或全文自由改写链。

顺序是：**范围与样本账本 → 来源与结构前置能力 → 处理点和版本差异识别 → 变量／默认／关系 → 项目级 catalog／plan → 当前原件生成 → 分层验收。** 身份契约、样本准备与只读盘点可并行；业务层改造范围在范围门后定稿，不要求先完成所有 OER 最终质量门才能做代码设计。

## User Stories

1. As a drafting user, I want the uploaded template edition identified, so that old clause numbers cannot control a different original.
2. As a drafting user, I want every relevant editing location accounted for, so that missing recognition does not silently omit required work.
3. As a drafting user, I want fill-in blanks, choices, conditions and preparer guidance distinguished, so that each symbol does not become a separate question.
4. As a drafting user, I want unmarked blanks and content controls recognized, so that discovery does not depend only on stars and hashes.
5. As a drafting user, I want the same project fact shared across its actual occurrences, so that repeated wording does not produce conflicting answers.
6. As a drafting user, I want renumbered clauses distinguished from changed clauses, so that old rules are reused only where justified.
7. As a drafting user, I want unmatched, split, merged and ambiguous clauses shown, so that an approximate match cannot silently authorize edits.
8. As a drafting user, I want new fields introduced and unused fields retired for this template, so that the form follows the current template.
9. As a drafting user, I want defaults and derived values to retain their basis, so that missing facts cannot be presented as confirmed values.
10. As a drafting user, I want conditions and cross-document effects explained, so that changing a fact updates every affected target.
11. As a drafting user, I want only affected decisions to require review after a change, so that reliable unrelated work remains usable.
12. As a drafting user, I want failed or superseded analysis kept distinct from the current result, so that late work cannot overwrite newer input.
13. As a drafting user, I want edits applied to the current original, so that its wording, numbering and formatting remain traceable.
14. As a drafting user, I want unresolved or unsupported edits disclosed, so that partial analysis is not advertised as a complete draft.
15. As a developer, I want existing projects and frozen revisions preserved, so that dynamic templates do not break the current workflow.
16. As a project owner, I want code, source quality, browser and export results reported separately, so that a passing fixture cannot replace product acceptance.

## Implementation Decisions

### 现有能力与新增缺口

“已有”仅指当前代码或相应专项证据，不能推广为未知模板已支持。后续 DYN-00 必须核实际目标版本，已有能力通过对应反例后复用，不能把全部旧任务重新写成未实现。

| 能力 | 当前可复用起点 | 本轮必须补齐／证明的部分 |
| --- | --- | --- |
| 来源身份与范围 | 原件 SHA、来源角色、generation、覆盖信息、模块隔离 | 模板集合与分析修订身份；内容相似、同文件名和同编号均不能复用旧身份 |
| 原生解析 | DOCX 包结构、Word 编号／修订／native objects；PDF native／layout 观察及既有补读链 | 模板处理点需要的结构、完整上下文和 occurrence 是否实际可消费；缺失归原 ENG 修复 |
| 原件定位与编辑 | native 节点、原文 hash、局部编辑、绑定及三稿原子发布 | 新版目标必须重新定位；不能用旧段号或放宽错版守卫冒充适配 |
| 输入、候选与采用 | 候选／已采用分离、来源绑定、人工目标、冲突及 reviewRequired | 本模板字段定义、匹配候选、规则采用及默认依据的统一生命周期 |
| catalog 与计划 | 字段控件、condition／affects、计划预览、既有规则 | 由项目和模板修订返回字段／动作；未知新字段不再只因不在全局静态目录而被拒 |
| 版本与恢复 | 快照、冻结 artifact、stale／只读保护 | 分析修订、schema／规则修订、依赖闭包、取消／晚到结果和缓存失效 |
| 模型与验证 | 既有模型选择、结构化输出、预算／调用／失败契约 | 如需语义模型，完整相关材料进入实际请求，输出绑定实际来源；先离线验证，不新增训练或无限修复 |
| 生成与版式 | 当前 DOCX 精确改写、Word／PDF 交付及未验证提示 | 动态目标接入；实际支持的格式和操作矩阵；保留字体／域／编号等真实限制 |

### 支持范围须先冻结

- 首轮业务族保持 NTT／SCT／SCC，不扩为任意法律文书平台。现有 DOCX 保格式生成能力保留；所提供的另一版 SCC PDF 先作为解析／版本差异样本。
- DYN-00 必须分别列清“可阅读分析、可作为可编辑生成输入、可交付的输出”。若拟支持仅有 PDF 的模板生成可编辑 Word，需明确新增重建能力、格式承诺及单独验收，不能从 PDF 文字可提取直接推导；也不能未作决定便以 DOCX 限制悄悄排除用户提供的样本。
- 标记含义依据本模板的说明和上下文。默认字段清单及自动采用条件需有明确业务依据；未知默认只影响依赖它的任务，不阻塞可靠部分的工程准备。
- 普通项目合约与标准空模板分别保存角色。模板只服务 Drafting；不得因共用解析或条款对应而进入 Vetting／Advice 证据。未来实际待审生成稿仍依实际身份准入。

### 分析契约和信任范围

- 区分业务概念、项目事实、当前版条款、模板处理点、规则、字段取值和编辑目标。一个事实可以影响多个处理点；一个处理点可能依赖多个事实及 AND／OR／例外。
- 分析结果绑定模板集合的每个原件身份、实际解析 generation、分析／规则修订和完整覆盖账。条款号是本版地址；跨版对应是带来源依据的关系，不是永久相等声明。
- 持久化先沿现有来源、采用和快照能力扩展最小必要状态。DYN-02 决定契约和迁移差异，不提前指定新表数量、通用规则语言或第二套 canonical 模型。
- 模型提出的字段、对应和关系属于候选，结构化 schema 合法、相似度高或模型自信均不构成已确认业务规则。自动接受条件须明确；不满足时保持待复核，保留原文和局部可靠结果。
- 条件计算为数据驱动的受限操作，不能执行来自模板／模型的任意代码。未知、冲突、循环和悬空引用均明确记录，未知不当作 No。
- 缩小失效范围需要完整依赖证据。依赖不完整或影响范围不明时，先保留现有保守的整稿 stale／只读保护，不为减少提示而假定其他目标安全。新字段的接入只改变校验所依据的定义；仍只接受当前项目／catalog修订明确声明的字段，不能删除未知key、类型或来源校验。

## 阶段门

| 门 | 何时判定 | 退出条件 |
| --- | --- | --- |
| G0 范围与分母 | 大范围业务实现前 | DYN-00／01 完成：支持矩阵、完整来源族／处理点盘点方法、失败边界、现有能力证据和待补清单已冻结；实际处理点数尚未核完时不填虚构总数 |
| G1 可核查识别 | 定动态业务改造范围前 | DYN-02–06 有真实来源结构和覆盖证据，能区分重编号／语义变化／未匹配；首次丢失层和唯一 owner 清楚；可形成业务改动清单。真实来源验证须沿当前执行授权，未执行保持开放 |
| G2 变量与规则准入 | 新版自动生成前 | DYN-07–10 的字段、默认、关系、采用和失效契约通过代码反例；新／歧义规则无未经授权的自动采用 |
| G3 软件集成 | 代码交付前 | DYN-11–13 的正常产品接缝、迁移、竞态和适用自动化通过；旧模板正确行为保留，失败／skip 集合如实记录 |
| G4 来源与业务接受 | 声称新版模板可靠可用前 | DYN-14 分别完成来源、独审、正常页面及导出门；OER 原 ENG-07／09 全部适用条件独立保留，不由 G1／G3 代替 |

G0／G1 用来逐步收敛范围。契约和测试设计可并行；不能要求先写完整动态 UI 才发现底层不能定位，也不能无限等待所有 OER 残差后才准备产品契约。

## 实施任务图

状态含义：`Ready` 只表示可做规划／离线准备，未开工实现；`Waiting` 为依赖待满足；`Conditional` 需相应输入或执行准入。当前没有任何 DYN 实施任务被本文件记为完成。

| ID | 任务与必须交付的结果 | 依赖 | 核心退出条件 | 初始状态 |
| --- | --- | --- | --- | --- |
| DYN-00 | 冻结基线、现有能力／证据、支持格式与可改操作；明确业务未知项和修改责任 | 无 | 能逐项判断复用／补缺／未证，输入分析与生成格式分开；已有失败和工作区保留 | Ready |
| DYN-01 | 建立来源谱系、完整处理点盘点账、开发／独立评价隔离和挑战集计划 | DYN-00 | 新旧版本、PDF／DOCX同版与人工参考明确；盘点／参考分母中的每个处理点有下落，未执行不填零；运行自动发现另由DYN-05验证 | Ready；先做清单设计 |
| DYN-02 | 定义最小模板分析与修订持久契约、候选／采用状态、兼容与任务终态 | DYN-00 | 重启可恢复；相同身份可复用纯派生结果，源变更不能复用来源有效 verdict；迁移先验证副本 | Ready；契约先行 |
| DYN-03 | 复用 ENG 正常解析并只补本场景首次暴露的内容／结构／位置／覆盖缺口 | DYN-00／01／02；沿原 ENG 依赖 | 标记、上下文、空写区、表／跨页／公式／修订及位置完整到消费者；修复只有一个 ENG owner | Waiting；不是另建 parser |
| DYN-04 | 从现有完整来源建立本版条款层级、边界、story 与引用视图 | DYN-02／03 | 目录／正文／页眉不混；编号、正文范围和 occurrence 可回取；SCC内部与GCC／SCT等外部引用区分，歧义可见 | Waiting |
| DYN-05 | 发现并分类全部模板处理点及其说明、分支、作用域 | DYN-01／04 | 覆盖星号、井号、下划线、点线、空单元格、content-control、选项和无符号说明；区分普通符号与真实编辑意图，不按符号数造变量 | Waiting |
| DYN-06 | 建立新旧条款及处理点对应，给出可核查差异与首次缺陷归因 | DYN-04／05 | 区分仅重编号、措辞变、语义变、新增、未匹配、拆合和歧义；相似标题不证明等价，旧规则不自动迁移 | Waiting；G1 核心 |
| DYN-07 | 把处理点汇聚为本模板字段定义，并与已知业务概念匹配 | DYN-05／06 | 保类型、基数、允许值、适用条件、必填性、来源和所有目标；重复位置共用事实，新字段可表达，未使用旧字段不被误删历史 | Waiting |
| DYN-08 | 明确默认／派生／项目事实／未知的来源优先级与覆盖规则 | DYN-02／07；业务依据逐项取得 | 有据默认可见、可改且不伪装人工采用；派生保输入及规则；冲突不静默覆盖，缺事实不变默认No；无依据项保持待定 | Waiting；未定业务项单列 |
| DYN-09 | 重建事实→条件／规则→多目标的依赖及跨文档引用关系 | DYN-04／07；与08并行 | AND／OR／例外／互斥完整，循环和悬空引用可见；改一值仅重算真实闭包；内部SCC改号不篡改GCC等引用 | Waiting |
| DYN-10 | 落实采用、局部失效、分析任务／缓存和旧项目兼容 | DYN-02／06–09 | 采用／批量采用绑定读取时的项目、catalog／分析修订、候选及来源，过期／跨项目拒绝并保原采用值；变更按闭包待审，晚到／取消／失败不发布，旧修订可读，新模板不静默回退固定catalog | Waiting |
| DYN-11 | 将项目级字段及关系接入既有 catalog／输入／plan／差异预览 | DYN-07–10 | 抽取提示词、查找／分组、解码／校验、保存、预览和生成使用同一catalog修订；未知key仍拒绝；默认与派生可核查，旧DTO／项目显式兼容，刷新／切项目无串数据 | Waiting |
| DYN-12 | 让动态编辑目标接入现有原件编译与三稿原子发布 | DYN-04／09–11；G2 | 锁定同一模板集合、catalog／规则及采用快照，发布前条件改变整批不更新当前指针且保旧稿；当前原件与目标精确局部编辑，重复／缺失锚点拒绝；不放宽hash／原文保护或全文重写 | Waiting |
| DYN-13 | 验生成结果、引用闭合、编制说明清理及结构／格式保持 | DYN-12 | 已采用处理点逐项有结果；未经授权正文及native对象保持；无应清理残留／误删，编号目录域字体等实际能力分列，unsupported可见 | Waiting |
| DYN-14 | 收口正常接缝自动化、不同作者复核与独立来源／真实产品验收 | DYN-01／03–13；原 ENG／UNI 相关门 | 软件与来源／模型／页面／导出分别有真实证据；完整分母、旧正确、失败／unknown／未执行保留，未过门不宣称整体完成 | Waiting；真实执行依准入 |

### 执行分批与并行

1. **第一批：DYN-00／01／02。** 先给能力账、格式／操作矩阵、样本与挑战分母、分析契约和状态图。此批不要先重写字段页面；能靠既有证据确认的复用，缺证保留。
2. **第二批：DYN-03／04／05／06。** 根据首个缺陷归因补引擎或模板消费层；交付识别清单和旧新差异，过 G1 后冻结业务改造范围。
3. **第三批：DYN-07／08／09／10。** 07后08与09可并行；10先定兼容／失效契约，再随各项集成。默认业务清单未定不授权自动填值。
4. **第四批：DYN-11／12／13。** 原有输入、plan和编辑器逐段接入；前端可基于已冻结契约并行开发，不能用mock结果当真实识别完成。
5. **第五批：DYN-14。** 先收代码／环境自动化，再按实际授权执行来源及产品门。暂停的模型效果、浏览器、部署不因任务依赖满足自动恢复。

每批一个集成 owner；共享契约先冻结，共享文件只由一个 owner 改。同一 parser 缺陷只挂原 ENG 实现任务，DYN 负责场景消费及相应反例，避免两套修复。

## Testing Decisions

沿正常上传 → 最终解析／持久化 → 模板分析 → catalog／plan → 原件编辑／保存 → 原件回取验证外部行为。先复现具体反例，再修首次丢失层；单元测试用于受限条件计算等纯逻辑，不能仅对实现结构写镜像断言。复用已有 Drafting 服务集成、原件精确编辑、版本冲突和真实挂载前端测试接缝。

| 挑战族 | 必须观察的行为 |
| --- | --- |
| 内容相同、条号／位置变 | 识别同主题候选并重新绑定当前版；内部引用随实际关联核对，不套固定数字偏移 |
| 编号相同、主体／条件／金额／期限变 | 记录语义变化，相关规则及采用失效；不得因同编号或高相似自动复用 |
| 新增／缺失／拆分／合并／重复标题 | 完整关系及未匹配保留；搜索未命中不推导业务“不适用”，不能靠旧位置强配 |
| 跨run标记、表内标记、仅下划线／空单元格／控件 | 处理点及owner完整，普通标点不造字段；空观察不冒称确定未填 |
| 条件说明、共享前缀、AND／OR／例外、跨页／跨文档 | 完整上下文进入分析，影响闭包可核；GCC／SCC／SCT等引用作用域不混 |
| 模板默认、PM约定、可派生值、缺事实及冲突 | 默认有据、派生可重算；原采用值和冲突保留，未知不当No，规则变更局部失效 |
| 同一事实出现多处 | 一次取值覆盖真实目标，修改不漏处、不重复提问，不把两个同名但不同owner的值错误合并 |
| 重新上传、并发分析、取消／超时、进程恢复 | 旧代晚到不能覆盖新代；失败／部分结果及原稿保留，不把旧catalog伪装为本次成功 |
| 生成与未涉及内容 | 精确改动范围、删除边界和引用可核；Word格式、表格拓扑、公式、编号、字段及未修改内容保持或明确unsupported |
| PDF／DOCX同版、近重复模板及独立来源 | 转换文件和邻近版本属于同一谱系，不当独立holdout；格式分析成功不当作可编辑生成或版式验收 |

自动化阶段可先用自写模板构造上述变体。真实来源盘点与质量评分采用实际完整原件和不同作者核对，人工参考隔离；不能把已暴露样本当盲测，也不能将手工修正结果当新引擎自动输出。

### 原 OER 保护映射

本任务图是统一规格的 Drafting 扩展，不覆盖或改号原 D1–D10／EAC01–EAC22／ENG-00–ENG-10。ENG旧快照中的“未开工／19项受阻”等须依当前证据逐项刷新，不能直接套用为本轮事实。

| 原要求 | 本图消费／验证 | 唯一引擎责任与保持项 |
| --- | --- | --- |
| EAC01–08、EAC20：身份、范围、观察、版本失效 | DYN-00／02／03／10 | ENG-00–02／06；保已有修复及模板／答案／图纸隔离 |
| EAC09–11：覆盖、同源坐标、完整上下文 | DYN-03–06 | ENG-03；不靠尺寸一致／clip或关键词补造同源 |
| EAC12–15：空写区、整表、Word、双解析整组 | DYN-03–05／12／13 | ENG-04；已有正确native及完整双解析采用规则保持 |
| EAC16–17：完整消费、回取、局部缺证 | DYN-02–06／10／14 | ENG-05；持久化／consumer不丢完整结构，unknown不删 |
| EAC18–19、EAC21–22：人工隔离、完整质量、日志和真实页面 | DYN-01／14 | ENG-07／09；普通CER≤0.1%、关键零错漏、完整结构／定位、旧正确、不同作者及独立来源门不缩减 |

ENG-08 按最新“不再调模型”决定保持不启动，不借动态模板恢复训练／换型／调参；ENG-10 的 SL 版本扩展继续后置，不加入本图关键路径。短模板变体、代码绿灯、PDF文字抽取成功均不能关闭原来源质量门。

## Out of Scope

- 本轮任务拆解不启动实现、模型／OCR调用、原件质量评分、索引、服务切换、数据库迁移、浏览器验收、目标环境部署、Goal或定时任务。
- 不重做已有原件存储、采用、快照或DOCX编辑器，不新建平行 parser，不改原PM业务规则，不把未确定默认值实现为既定值。
- 不发布原件、模型、密钥或私有运行证据；原件、fixed05／gold、既有训练产物、活动DB及其他GPU／WSL工作保持。
- 不扩到图纸解析、SL全版本工作、任意法律文书、全文LLM改写或无依据PDF→Word格式承诺。

## Further Notes

已有新旧SCC对照是公开给本次设计的开发样本，全文等价与完整处理点分母尚未验收。新版PDF SHA为 `1bebab1afbc0faaf8aa9c0b9e6c51b023c24e53a094d4987d746fdeb65b30741`，现用SCC DOCX SHA为 `9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208`；这些SHA只作身份／证据定位，禁止编码成答案或自动规则。目录与抽取文本的存在不能证明完整结构通过。

本地只读对照在忽略目录 `.local/scc-format-design-review-20261015/`，原件不复制进Git；已有数学投影兼容热修保留正常parser不变及精确校验，不等于通用模板适配已完成。实施起点及既有代码／真实操作证据见 [Drafting热修记录](../testing/drafting-regenerate-hotfix-20261015.md)。

2026-10-15 后续统一设计见 [修改要求、固定类型、原子语义能力与图谱拼接](drafting-typed-graph-workflow-20261015.md)：NTT／SCT／SCC 先归集全部修改建议／编制要求，再综合反推变量并保来源对应；独立上下文形成原子结果，程序组链合图，集中复核与有界重点回查。该稿为 proposed／design-only，四轴候选类型和流程图不追认为产品协议、模型效果或原件生成已实现。

关联入口：[统一规格](consense-oer-vetting-advisory-integrated-20261014.md)、[统一任务图](consense-oer-vetting-advisory-integrated-tickets-20261014.md)、[受保护OER规格](oer-engine-integration-repair-20261014.md)、[ENG任务图](oer-engine-integration-repair-tickets-20261014.md)、[局部不确定性ADR](../adr/0001-localize-uncertain-review-evidence.md)、[双解析采用ADR](../adr/0002-preserve-dual-parser-evidence-and-route-by-structure.md)、[模板隔离ADR](../adr/0003-separate-drafting-templates-from-vetting-advisory.md)。本图沿用本地Markdown tracker，不创建远端issue或新聊天。
