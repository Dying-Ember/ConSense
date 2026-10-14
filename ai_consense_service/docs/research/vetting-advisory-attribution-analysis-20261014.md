# Vetting / Advisory：OER、harness 与模型的只读归因

观察截止：2026-10-14 20:45（Asia/Shanghai）。本轮只读源码、冻结运行记录及已完成测试产物；只新增研究文档。没有调用模型/API/GPU、打开活动数据库、重跑业务、修改配置或干预 OER 训练。后续测试不自动继承到本文。

## 结论

当前证据支持分阶段判断，不能给三层各自一个错误百分比。

1. **Vetting 的旧完整审查能力尚未得到有效评价。** 已证实的主要障碍是完整输入预算无法绑定，119 个补充材料请求未提交；0 finding 和 COMPLETED 不能当全面无问题，也不能用于否定模型。
2. **升级后 Advisory 的本题来源供应已有较强证据。** 实际发送包包含本题独立选定的38项条文要点。M2.7 的对应引用、分支和输出契约错误已在原始草稿中出现，不能统一归为 OCR/检索没有给资料。
3. **M3 的16K对照是运行预算失败。** 以 length 结束且尚未交付完整最终回答，不能据此判合同推理错误。32K对照在观察时仍进行中。
4. **harness 负担与模型能力仍需受控拆分。** 新协议要求每个答复单元提供十项自检、结构化图计划和多层引用；这是可见的协议负担，尚无删改单因素对照证明它是预算耗尽或内容错误的原因。
5. **OER 训练继续有必要，但不会自动解决已经进入模型之后的错误。** 当前来源质量候选尚未整体采用；需绑定每道题实际使用的解析、索引与证据包身份，不能将训练中的新候选成绩填入旧业务运行。

## 版本与证据边界

| 对象 | 本轮核验身份 | 可陈述范围 |
| --- | --- | --- |
| OER规则入口 | 本轮读到的R157快照；训练/方法迭代持续进行 | 表单语义、写区、来源关系仍有未知，整体候选未采用；不据局部进步宣布整库通过 |
| 根工作区 | HEAD `285fc2e3bdf97792aeae00a31dbde2c69ebcde60`，已有大量dirty增量 | 只作源码能力读回，不能当在线Jar或公开交付版本 |
| 升级Advisory | `C:\Users\Administrator\.codex\worktrees\oer-advisory-next\ConSense`，code07 / `363096b76f1921ac5f0e43c9f470f3e94c45ba6d` | 绑定本轮已完成16K模型对照；不能沿用根目录旧AdviceService判断升级版未修 |
| 新模型对照 | frozen-source replay；sourceFreshnessAccepted、sourceQualityAccepted、applicationSessionAcceptance均false | 可核固定材料上的生成/协议表现，不是fresh retrieval、完整OER或前端业务验收 |
| 旧业务 | 2026-10-10 / 10-11的固定运行 | 仅用于历史机制归因，不追认为新版仍有相同缺陷 |

范围仍遵循 [scope](../oer-scope-and-rules.md) 与 [handoff](../HANDOFF-ocr-embedding-rerank-20261008.md)。Vetting/Advisory不使用Drafting标准模板作为业务依据；图纸仅身份路由后排除；2d/2e答案与生成端隔离。本轮不修改这些规则。

## Advisory：当前升级版已观察到什么

### A1. 选定条文已经进入实际请求

独立来源核对绑定 code07、M2.7第一轮actual Chat请求与generation snapshot，system/user完全相同；10/10个选定字面来源片段匹配，38/38项选定来源要点为available，partial/absent/unknown均0。

这排除了“这些选定要点根本没送到模型”对相应错误的解释。它不证明整个GCC/SCC解析通过、法律适用版本齐全、项目采用已确认、所有潜在必要资料齐全，或全合同审查覆盖通过。来源审核没有把纠正答案送回生成器，也未读取官方答案或图。

证据：[公开安全availability收据](</C:/Users/Administrator/Documents/Codex/advisory-alignment-20261014/model-comparison-01/evaluation-source-only-01/packet-availability-01/published-safe-availability-receipt.json>)，SHA-256 `25aaf2eded06cbfa4a877ddbbe3431eed8cf66c4fe028df2eb4f5e919fac5187`。

### A2. M2.7同时有协议与内容残差

`m27-official-02`：第一次正常stop，JSON可解析，但流程图section缺必需checks；唯一一次repair仍正常stop，返回JSON不合法。没有应用发布。

独立评审只评价第一轮原始草稿，没有补checks或改图。图包含19节点/28边，3个节点不可达，3条动作没有actor。45条具有合同含义的引用行均仅指向标题，正文支持行0。38项来源覆盖评审为3pass/22partial/5fail/8missing。**这些是评审行与要点计数，不是准确率或独立缺陷数。**

来源评审记录的具体内容错误包括：final/binding的作用范围扩张；将14天ADR相关表达构造成不成立的决定性触发；other-matter路径绕过CM阶段；及时拒绝short-form arbitration的分支仍通向其终点。它们已存在于raw图计划，因此不是前端改变箭头造成。

这些事实支持“当前模型+prompt+packet+mode组合的生成端存在残差”。没有清晰简化prompt与同预算同模型的对照，不能独立断定权重能力上限或harness是唯一原因。M2.7是对照模型，不是Qwen3.8-27B；不得把这份失败直接填入Qwen能力格，也不得直接用它作蒸馏gold。

证据：

- [模型收据](</C:/Users/Administrator/Documents/Codex/advisory-alignment-20261014/model-comparison-01/m27-official-02/receipt.json>)，SHA `0decba222bf1d7d53205133b785e94ade673eeba42fd8368c4c7e91f90998be5`。
- [独立草稿评审](</C:/Users/Administrator/Documents/Codex/advisory-alignment-20261014/model-comparison-01/evaluation-source-only-01/draft-case-m27-02/published-safe-draft-evaluation-receipt.json>)，SHA `80639a54cca97444cf0a88c55e05ca6e9b1c0df2683090cd9cd85d4a5ffd07fa`。
- [原样graph-only诊断](</C:/Users/Administrator/Documents/Codex/advisory-alignment-20261014/model-comparison-01/m27-official-02-graph-only-01/receipt.json>)，SHA `107721808e190450c301013379b95117806cb4efdad4dc4f3eed150ad289fc97`。
- 私有逐行来源评审：`evaluation-source-only-01/private-source-analysis/m27-draft02-source-review.json`，不在本文复制原件正文或纠正图。

### A3. M3的16K结果尚不能作法律质量评分

`m3-official-02`使用同一冻结材料与16384输出额度，finishReason=length；运行捕获保持失败，不盲重试。冻结SSE重组显示thinking内容已经输出，但最终content为空。字符数不等于token数，provider usage缺失，不能推算精确thinking token分配。

归因应记为生成模式/输出预算门未过，而非“原回答合同判断错误”。harness已正确拒绝不完整响应。观察时`m3-official-03`的32768额度对照只有started、attempts=[]；不宣布新结果。

证据：[16K M3收据](</C:/Users/Administrator/Documents/Codex/advisory-alignment-20261014/model-comparison-01/m3-official-02/receipt.json>)，SHA `bf8d3f3b508fd94fa7d07fb25df9be7363afc44eb4f2c42fac3fe85671e3f428`。进行中产物不作为冻结质量结论。

### A4. 新harness有真实的协议负担，但因果待验

升级工作树的AdvisoryHarness要求每个limits/analysis/flowchart等section提供自己的R1–R10十条检查，含status/reason/citations；图边须提供全部规定字段，角色、时限起算、引用和可达性均有门控。其开销与遵从复杂度可由结构直接观察。

但目前没有保持必要角色/时限/引用要求的协议精简对照，不能断言十项自检导致M3思考耗尽，也不能通过删除这些业务核验把模型错误隐藏。新代码有compact输出指令，仍需测量具体契约与模式的交互。

读回源码前后SHA一致：AdvisoryHarness `d7c50df558ec641f38ba0b2bb23f37506912f42710f3236146892943a61bb3d9`；AdviceService `a7fa55287aeb69b27c4fcb2469f6d7ea5f00401be474a096a5db78b70d730d34`。

代码入口：[AdvisoryHarness](</C:/Users/Administrator/.codex/worktrees/oer-advisory-next/ConSense/ai_consense_service/src/main/java/com/consense/service/advice/AdvisoryHarness.java:56>)、[图校验](</C:/Users/Administrator/.codex/worktrees/oer-advisory-next/ConSense/ai_consense_service/src/main/java/com/consense/service/advice/AdvisoryHarness.java:315>)、[compact指令](</C:/Users/Administrator/.codex/worktrees/oer-advisory-next/ConSense/ai_consense_service/src/main/java/com/consense/service/advice/AdviceService.java:323>)。

## Vetting：已闭合的旧失败机制

### V1. 补充材料没有进入完整模型审查

旧job `97ca407a-1fa3-48b0-a1d2-bc884e7574ab` 已持久化COMPLETED，进度19/19，finding为0；17个topic全部completed_empty且aggregate partial。119个补充来源观察请求全部not_submitted_budget_unknown、packetId=null，17个packet原因为no_bound_token_observer。

119由34个ranked_source_observation与85个retrieved_structural_unit组成，是planner请求，不是模型反复索要119次。唯一已提交片段46个：SCC22/22、GCC24/50；范围仍仅GCC65–70与SCC17局部。此运行没有完成全面材料评价，不能用0 finding计算漏报率、准确率或宣称无问题。

本次读回business summary及budget原因SHA与历史文档匹配；子审查还逐项复算了冻结coverage原字段。没有打开活动数据库。

证据：[终态与直接原因](../testing/oer-official-two-source-vetting-completed-partial-20261010.md)。私有根：`.local/state/oer-official-q16-first-run-20261010/vetting-original-h2-readonly-20261010-113733-700/readonly-query/`。

| 证据文件 | SHA-256 |
| --- | --- |
| completed-business-summary.json | `69c46544c4b7f2111b9cd0662abffbe42c846f1ecb340be9ee0fc3ffa9d668e4` |
| budget-unknown-reason-summary.json | `5df00c37d86d39386a97798419dbe7f37876d31c672dce77054b7cad0f187b35` |
| coverage_json.json | `b7ae5da6c085bd90d53fdc37f4e4b9ce41b08b35b41b5e29c63ca22b4a69a884` |

### V2. 源码修复能力与新业务结果要分开

根工作区当前SemanticReview已包含Responses PreparedRequest的同wire计数/生成绑定；explicit minimax-cn使用默认Chat时selectedObserver仍可能为空。该文件SHA `1cd28b430cd4460075dc9fce95d8676c14f7aec92ba1eec55dc4815dda092ea6`，读取前后稳定。

这说明源码存在可选修复路线。当前在线Vetting的Jar、wire配置、完整请求dispatch与新报告评分本轮没有核实，保持unknown。不能把源码/契约通过追认为119旧pending已完成。

证据：[selectedObserver](../../src/main/java/com/consense/service/vetting/VettingSemanticReview.java:45)、[同wire预算接入记录](../testing/oer-minimax-input-budget-and-provenance-review-20261010.md)。

## OER质量与下游应怎样分账

旧Q1.6首轮有明确反例：原canonical资料已包含GCC17.1正文及typed parent边，但实际retrieve只返回标题，候选24/top3和SourceUnits未消费跨单元结构造成正文缺席。该问题发生在检索/结构闭包消费，不能只称OCR没识别出来。[实际retrieve审计](../testing/oer-official-q16-actual-retrieval-readonly-validation-20261010.md)、[标题正文闭包诊断](../testing/oer-official-q16-title-body-closure-diagnosis-20261010.md)。这是历史机制，不宣称升级版仍然如此。

10-11的另一次M3回答，完整53个来源单元已进入wire，跨页条件也经独立核对；原回答仍有及时Notice分支、前提/出口及SCC正文引用残差。该结果属于当时packet/prompt/model组合的生成残差，未构成模型固有上限对照。[M3历史资料](./minimax-m3-evaluation-reference-20261012.md)。

OER仍需改善完整表单、表格、条件/例外、阅读顺序和跨页关系。其候选loss下降、局部来源进步，均不能自动更新已经冻结的fixed05、旧索引和V/A来源快照。也不能把表单字段候选的未知或进步直接当作GCC/SCC本题召回成绩。

## 不干扰训练的最小归因对照

当前可以继续做冻结产物的离线审查，无需等待整库OER最终验收。之后需要新模型调用时，先固定有限题组、模型/模式/预算及调用额度；本轮没有执行以下新试验。

| 对照 | 输入与harness | 主要判断 |
| --- | --- | --- |
| A | 来源核对后的完整必要材料 + 清晰最小任务契约 | 模型在证据充分情况下能否理解条件、引用和完整发现 |
| B | 同一材料 + 生产harness | 提示/协议/分段/自检负担的影响；保持业务义务不变 |
| C | 对应正常parser输出 + 同一生产harness | 原件转表示丢失或错挂造成的影响；必需材料范围对齐 |
| D | 实际RAG/结构扩展后发送包 + 同一生产harness | 候选、排序、闭包、预算裁切和来源权限造成的影响 |

每题分别核原件、parser、索引/候选、最终wire、raw回答、校验、前端交付。依赖性的交互不能简单相减为三层错误百分比。固定raw的离线接收重放另评harness后处理，不补答案或放宽正确的语义门控。

Advisory分别评：命题、条件/期限/出口、引用支持、缺证处理、图逻辑、输出协议。Vetting还需要独立的应发现集合、应无问题反例、实际审查范围和关键漏报；只评价模型返回的发现无法测全面召回。

只有证据充分、明确契约、运行正常之后仍重复出现的生成缺陷，才形成LLM蒸馏/SFT候选。检索未送入、预算未dispatch、parser漏失和前端交付缺陷各自先处理。三模块训练的收益仍分模块报告，不用Drafting已可用替代V/A质量验收。

## 本轮未完成项

没有新模型/API/GPU/服务操作；没有在线Vetting复测；没有追踪正在运行的32K测试到终态；没有执行上述A/B/C/D对照；没有评价Qwen3.8-27B的新版V/A成绩。本文是阶段归因与测试设计，非模型采用、OER验收、业务通过或部署批准。
