# Vetting 显式引用范围修复与上下文预算对照

时间按 Asia/Shanghai 为 2026-10-03；证据文件及 API 时间使用 UTC。本文接续 [DOCX 保真修复结果](vetting-docx-fidelity-20261003.md)，旧报告、旧模型回答和旧检索结果保持原身份。本轮目标是定位并修复条件进入模型输入之前的 harness 缺口，再独立评价模型拿到材料后的判断。

## 已确认的输入缺口

先前 HOMES 窗口保留了 SCC22.304 的部分段落，却遗漏第 (6) 和 (8) 段。原件及新解析语料中这些正文均存在，不能将窗口缺失写成原合同缺失。旧 builder 的同父条分组与局部邻段策略没有确保完整引用目标进入窗口；其 unresolved 判断也不能证明完整父条或精确子条款已经提交。

本轮保持两份原件、1,859 个新解析 chunks、历史实际 CPU 索引及两次查询/rerank 完全相同，仅重新运行生产上下文构建。当前步骤没有新的 parse、OCR、embedding、rerank、index、live query、应用 DB 或模型调用。原件、派生 sourceHash、index signature 和完整检索 payload 分别绑定，不能把回放描述成新检索。

## 代码行为与边界

`VettingContextBuilder` 的策略更新为 `paired-local-window-reference-closure-v2`。只有最初已入选的 ranked core 中的原文显式引用可以触发一跳扩展；查询关键词不会制造引用，后来带入的片段不能继续扩展第二跳。显式引用目标优先于弱邻段，目标按完整来源范围提交，预算不足不截断正文。

引用目标绑定 document、sourceHash、role 和唯一 heading。同一 clauseId 有重复 heading，或 heading 缺失，均保持 unknown/ambiguous。精确子条款 metadata 缺失时，可以回退到该来源的完整父条及拥有明确 ancestry 的子范围，但 `exactSubclauseVerified=false`，相应 unresolved 保留。完整父条提交只证明材料传输范围，不能证明模型已理解全部限定条件、项目采用关系或全文 absence。

每条引用新增探针记录：起点及其 anchor、初始资格与最终是否提交、引用目标身份、解析方式、required/submitted/missing chunk IDs、首次请求增量字符、当时剩余预算、提交状态和精确子条款验证状态。现有 `SemanticReview.context_selection` 序列化完整 selection，会保留这些字段。原来的字符串引文和比较来源门禁仍保留。

初版 v1 的 23 项针对测试通过后，独审仍发现重复 heading 合并、null heading 精确化以及动态起点引入第二跳的问题。原 v1 源码、测试和失败 probe 已冻结。v2 补充相应回归后完整验证通过；这些首轮缺陷未隐藏或改写成成功结果。

## 软件验证与真实材料对照

| 检查 | 结果 | 适用范围 |
| --- | --- | --- |
| 针对测试 | 26 项通过，0 failure/error/skip | 原 ContextBuilder 15 项与新增 11 项 |
| 完整离线 Maven package | 257 项、29 suites，通过；原 246 项 identities 和现存测试源码均保留 | 包含隔离 in-memory H2 软件 fixtures，不是实际应用 DB 审查 |
| 冻结生产类与打包 JAR | 214 个逐字节一致 | 冻结 v2 构建；不等于当前运行服务已部署 |
| 独立边界 probe | 相同 4 个 fixtures 在 v2 中 observedBugCount=0 | 重复/null heading、一跳链和唯一 heading continuation 对照 |
| 保存检索输入 guards | 17 项通过 | 外部 SHA、payload、part offset、参数及拒绝覆写/身份变化 |

| HOMES 材料 | 正文字符（UTF-16） | 提交 chunks | SCC22.304 父条 chunks | 第 (6)/(8) 段 | 比较含义 |
| --- | ---: | ---: | ---: | --- | --- |
| 旧 builder，10,000 预算 | 9,671 | 9 | 5 / 10 | 缺失 | 旧生产材料基线 |
| 新 builder，10,000 预算 | 9,925 | 11 | 6 / 10 | 仍缺失 | 隔离 builder 改动；明确记录预算阻塞及 4 个 missing IDs |
| 新 builder，20,000 预算 | 17,203 | 18 | 10 / 10 | 原文均进入输入 | 与新 10,000 隔离预算因素；不自动算精确子条款或语义验收 |

付款主题在两种新预算下均保持 12 chunks、3,306 字符。HOMES 新 20,000 的 14 条引用为 12 个 parent fallback 与 2 个 exact clause，required 与 submitted 全部相同，missing 为空；仍有 4 个来源起点在 unresolved 集合中，精确子条款尚未知。保守比较门禁可能继续阻止候选，不能为提高候选数而解除未知范围。

旧父条计数已从原冻结 selection 逐 ID 核对。当前报告仍在追加本轮模型对照与终态证明；原始证据不随报告修订。

## 模型对照实际结果

实际材料、软件身份和准备请求独审通过后，对新 20,000 的 HOMES 材料执行了一次直连 MiniMax-M3。保留既有参数：temperature 0.2、max_tokens 16384、thinking adaptive、reasoning_split true、stream false、n 1、串行 1、retry 0、180 秒 socket timeout。socket timeout 不是绝对 wall 上限。

沿用此前 445-byte 格式提示包，生产 systemSource、user、schema 投影由实际新材料产生；不向 prompt 加入正确答案、预期 SCC 条件、独审标注或此前模型回答。比较因素是新 builder 与预算；它不能被单独归因模型变化。保留真实 request、HTTP body、content、reasoning、usage 及 finish_reason；不修 JSON 或引文。

唯一 POST 返回 HTTP 200、MiniMax-M3、finish_reason=stop，wall 158.656 秒；provider 报 prompt 10,625、completion 10,907、total 21,532 tokens，cached_tokens=128。reasoning_tokens 原值为 0，同时原始 `reasoning_content` 单独保存；不据这个 usage 数值推断没有思考。没有额外重试、JSON 修补或引文改写。本次没有重复此前的 PRE→SCC7(a) 误归和缺少 (6)/(8) 的判断，只是一轮观察，不证明稳定性或所有条件正确。

原 content 是合法数组，唯一 issue 声称 HOMES 附表“页脚”标 IV，与标题 APPEND3 及正文引用 III 冲突。生产 gateway 实际 parsed=1；复制的机械编排调用了 3 次真实 `VettingCorpus.evidence` 和 1 次真实 `hasComparisonBasis`，三处引文均定位，机械 accepted=1、candidateEligible=1。gate 自身 HTTP=0，savedProvider stub 返回=1，导入其他模型 call=0。它是保存内容解码/机械回放，没有执行完整 SemanticReview 或应用审查。

原件独审反证了这个 issue：HOMES 原生表在 body2326，不含 `APPENDIX PRE.B6/IV`；该 section 在 body2328 结束，其 rId20/header10.xml 明确为 `APPENDIX PRE.B6/III`。body2329 是下一附录的独立 `Subtitle`，body2330 是 `PRE.B6.APPEND4.9` 水龙头冲洗标题，body2332 是新的冲洗表；它对应下一 section 的 rId21/header11.xml `APPENDIX PRE.B6/IV`。实际 footer1.xml 内容是 `20250101PRE.B/8`。因此，模型将下一附录标题误认为上一附表页脚，不能把“修正页脚”当有效意见。

对应 harness 缺口是 `VettingCorpus` 未将带点/斜杠的独立附录标题识别为边界；它继承了上一 clause 的 owner。Parser 已正确保留 Subtitle 样式和 body 位置，文本及 offsets 也未损坏，但上一 APPEND3 chunk 的末尾混入下一附录标题。此处说明逐字引文和两来源比较检查不足以验证版面角色及所属章节。本轮唯一候选经源审 confirmedIssues=0、falseIssues=1、qualityAccepted=false；整体 precision/recall/accuracy 仍未知。

下一项工作已转入新的隔离修复阶段：补通用的独立附录/章节边界，保持所有字符、Part offsets 与原件不变，并以新 corpus/material/index 身份继续验证。新源码与新材料不能替换本轮冻结输入冒称同一实验。完整 SemanticReview 编排、实际应用人审/导出、44 来源整体质量、OCR 精度及 H800 尚需独立验证。

## 原始证明入口

- [v2 软件冻结 manifest](../../tmp/docx_reference_closure/service-20261002T175906633958Z/frozen_build_v2/manifest.json)：SHA `b70e6c2a093c907cd58519becce33e58f5addda8f81491ca054d926a827e028e`。
- [独立 v2 边界执行 receipt](../../tmp/docx_reference_closure/independent-review-v2-20261002T1820Z/run_receipt.json)：SHA `d2619cb75826de10fc2af43c36382a1b8258f64ea0c1595193e2fbaf6d556353`。
- [新 10,000 材料 manifest](../../tmp/docx_reference_closure/root-context-run-20261002T1820475051068Z/context10000/material_manifest.json)：SHA `a43a0c93a7a778d6a770a5bb253ef92c6205ff4e086e3816fdcd28380ea16745`。
- [新 20,000 材料 manifest](../../tmp/docx_reference_closure/root-context-run-20261002T1820475051068Z/context20000/material_manifest.json)：SHA `bf8520c246515e602a71ba6eef523be74f83ec6938987ded0d72fef8487fe6fb`。
- [本次准确 launch classpath 与依赖身份](../../tmp/docx_reference_closure/root-context-run-20261002T1820475051068Z/root_launch_receipt.json)：SHA `2acb56f707f3647343fc5718b9c165b8e1d49bfac75cf788c3c267b8806d8d2a`；运行后补记，依赖 classpath 文本已在运行前冻结，各 `.m2` JAR 字节 fingerprint 为运行后观察。
- [17 项文件输入 guards](../../tmp/docx_reference_closure/root-context-run-20261002T1820475051068Z/guard_checks/guard_result.json)。

正式仓库已接回两个文件，129 项源码全部与测试树对应；[promotion receipt](../../tmp/docx_reference_closure/promotion-20261002T182719137681Z/promotion_manifest.json) SHA 为 `9415bd9a52bbd408448c3f2a89d26ece9de87e18406b7223da668a7e49d1166b`。默认预算、旧服务/UI、旧索引/DB、其他队员 DraftingService 未因本轮离线试验修改。

[最终上下文独审](../../tmp/docx_reference_closure/independent-context-audit-20261002T182441447709Z/audit_receipt_final_v2.json) SHA 为 `4e1ccb086cb67d6f6c6336f3e42b5041a161ceb2720dee9e191193cbd389cf58`：全 1,859 chunks 与 11,342 Part offsets 精确，四个调用材料共 53 selected chunk occurrences、177 Parts 完整，28 条引用 traces 的来源、原文字面引用、预算及 required/submitted/missing 均逐项对应。125 个依赖 JAR 与运行后 receipt 匹配，其中 89 个与应用 JAR 库字节匹配；这不是运行前 immutable 依赖证明。

只有 ContextBuilder 生产源码发生变更。`VettingSemanticReview` 源码不变，但 javac 将新 `static final STRATEGY` 字符串内联，导致其 class 字节的一行常量变化，已通过 javap 逐项记录。systemSource 仍逐字不变；新 10,000 与 20,000 的生产类完全相同。不得把“其他源码不变”描述成“所有其他编译类字节都不变”。

单次 API [prepared_v2](../../tmp/docx_reference_closure/minimax20k-tooling-20261002T182344435894Z/prepared_v2/prepared_manifest.json) SHA `1073a7cf0633ae0651279af25957f46d4c0dc3a9776cee6440616cc02baec4ca`，请求 34,392 bytes；[driver 工具证明](../../tmp/docx_reference_closure/minimax20k-tooling-20261002T182344435894Z/tooling_manifest.json) 包含 20 项 mock tests 全部通过的原始记录。首次 partial-response fixture 字段名写错的测试失败保留，修正的是 fixture，未改真实响应。正式 prepared 目录会保存派发 reservation，同一 prepared 更换输出目录仍不能重发。

软件和两个预算上下文的三个新事件已经进入统一 registry。模型实验 `minimax-m3-homes-reference-closure-20k-20261002T1830Z` 单独登记，实际时刻来自 receipt，名称中的时间不是派发时刻；reservation 为 18:37:21Z。实际 [API run](../../tmp/docx_reference_closure/minimax20k-tooling-20261002T182344435894Z/actual/run_result.json) SHA `4e4ea4aef04001730c4f2df9bed7a6dd934a5dd936f1d93bf5392dfaf0a3f776`；[真实解码与机械投影](../../tmp/docx_reference_closure/minimax20k-tooling-20261002T182344435894Z/gate_actual/gate_result.json) SHA `957ae2be4b051ed4a33f4337b489110494a21d9f57d197bbb7b46523defa1cf6`。

[唯一 issue 原件源审](../../tmp/docx_reference_closure/independent-homes-appendix-audit-20261002T184824Z/proof_v2/audit_receipt.json) SHA `5c3efcb84315bae9e0eb6efa3fca01d147a25289fc5d88b8103b8c9efda36abf`，包含原生 section/header/footer 及 body/table 位置；本轮没有实际渲染 Word 物理页，不能添加页码或版面测量结论。首次 styles.xml 迭代误含 docDefaults 的仪表错误与旧 proof 保留，最终使用 proof_v2，未改模型回答或源材料。

[本阶段统一比较](../../tmp/docx_reference_closure/final-comparison-20261002T185937009135Z/evaluation.json) SHA `1c4d49ffd0e9c822dc28dc0ede0c21966c80260fce1e2a4dcfedde5d752fc655`，root 再次核对 539 个 bound artifact descriptors。模型原始 transport-completed 观察保持不变，独立源审以新事件追加到统一日志。新策略与两个预算、模型 transport、解码、引文、角色归属和最终质量分别记录；本次 source repair 接受、模型质量未接受，完整 vetting 目标未验收。
