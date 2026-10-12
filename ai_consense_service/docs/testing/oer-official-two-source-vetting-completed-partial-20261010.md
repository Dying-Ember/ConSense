# GCC／SCC 两源 Vetting：完成终态与局部未审查事实

日期：2026-10-10，Asia/Shanghai。本文记录旧任务的持久化终态和只读离线诊断，不是新一轮执行、全合同质量验收或报告评分。此前 [首次准备失败](./oer-official-two-source-vetting-first-failure-20261010.md) 的另一个 job 保留原失败；本次不覆盖它。

## 从数据库副本确认终态

精确 job 为 `97ca407a-1fa3-48b0-a1d2-bc884e7574ab`，project 为 `official-q16-source-90602e8f-85b8-4e1a-ad61-2625c654115e`。旧观察程序两小时等待上限退出，不等于 worker 失败；10:46 的 RUNNING 和 11:02 单次 GET 超时也不能判终态。11:33 核验原 PID118580 已不存在、18083 无 listener，因此停止对旧 owner 发 API 请求，未重启或重投任务。

原 H2 文件完整流式哈希、稳定性核验和新私有副本一致，1,541,472,256 bytes，SHA `a085f92eba7786bce0d94d6502461de32e61547356cf8aa45a32b6751c20fd86`。仅打开副本，`ACCESS_MODE_DATA=r;IFEXISTS=TRUE`，driver metadata／JDBC 均 readOnly=true；只执行 SELECT。原数据库未打开，复制／查询前后原件 SHA 不变。只读查询保留 result／coverage／model identity 原字段私有字节，没有启动 Spring、报告导出或模型调用。

| 保存字段／复算结果 | 实际值 | 证据 |
| --- | --- | --- |
| status／phase | COMPLETED／completed | readonly-query-receipt.json |
| completedUnits／totalUnits | 19／19 | 同上；是流水线进度 |
| started_at UTC | 2026-10-10T00:12:04.151367Z | 同上 |
| finished_at UTC | 2026-10-10T02:47:45.880744Z | 同上 |
| 完整任务墙钟 | 9,341.729377 秒，即 2:35:41.729377 | completed-business-summary.json，UTC microseconds 复算 |
| findingCount／activeFindingCount | 0／0 | 精确 project＋run SQL count |
| sourceSnapshotSha256 | 05880fd4bf694b2655bd0b904f9544299382352fc0ffacb0fac8322321477727 | 原 run；冻结 source manifest 精确匹配 |

模型 identity 与 canonical corpus identity 也与运行前冻结身份精确匹配。COMPLETED 只表示任务走到终态；0 finding 不能当作没有合同问题或法律判断正确。

## 覆盖仍是 partial

17 个 semantic topic 的 status／globalCallStatus 都为 `completed_empty`；17 条 `aggregateReviewStatus=partial`、`semanticScopeVerified=false`，coverage scope 都是 `observed_source_requests_only_qualifiers_unknown`。returned／issue／consistent／insufficientContext assessments 均为 0；sourceRequestCount=119，pendingSourceRequestCount=119，没有 acceptedFinding。字段未知和局部未审查状态不提升。

唯一 submitted chunks 共 46：本次局部 SCC 的22／22、局部 GCC 的24／50；GCC submitted/reviewed 字符15,942／24,015。范围只是之前采用的 GCC65–70 六页和 SCC17 相关正文，不是两份全合同。“reviewedDocuments=2”只说明各有被观察／提交的片段，不能解释成全文完成。原 coverage 的3条全局 warning 原文私有保留。

JSON／DOCX／PDF 正式报告未由本次查询导出，结构与法律质量评分均未运行。保存的 result／coverage 不能追认为前端验收或导出布局验收。

## 119 个 pending 的直接原因

这些记录是 harness planner 生成的 observed source requests：34 条 `ranked_source_observation`，85 条 `retrieved_structural_unit`，不是模型回复中的119次工具请求。119 条均为：

- `transportStatus=budget_unknown`；
- `unknownReason=complete_input_budget_observation_unknown`；
- `reviewExecutionStatus=not_submitted_budget_unknown`；
- `packetId=null`。

17 个 global packet 的预算 metadata 均为 `reason=no_bound_token_observer`、`observation=null`、`extraDispatchPermitted=false`。冻结及当前四个相关源码 SHA 一致：`VettingSemanticReview.selectedObserver` 对 explicit profile `minimax-cn` 返回 null；`VettingInputBudget.check` 对 null observer 保持 unknown；`VettingTokenAwareRequestPacks` 将这些完整组保留 pending、不额外 dispatch；`VettingPacketCoverage` 正确保留 not_submitted 状态。全局 legacy 调用仍可在预算 unknown 时执行，所以存在 completed_empty 全局调用和 partial 来源补充覆盖。

该证据排除了“119次模型反复索要”“119次 lookup 失败”和“完成后忘记结清”为这些 pending 的直接原因。它不证明 M3 的实际 token 预算耗尽。下一次业务质量测试前需解决 MiniMax 模型／实际完整 wire 输入的预算绑定，不能拿 Bonsai tokenizer、字符数估计或别的模型 token 数伪装实测，也不通过删除 pending 或抬高 coverage 规避。

## 私有证据入口

全部位于 ConSense 根 `.local/state/oer-official-q16-first-run-20261010/vetting-original-h2-readonly-20261010-113733-700/`：

- `readonly-query/readonly-query-receipt.json`：精确 SQL、终态、字段非空和原 DB 未打开事实；
- `readonly-query/completed-business-summary.json`：完整运行时长、17 topic／119 pending／46唯一片段及评价未执行状态；
- `readonly-query/budget-unknown-reason-summary.json`：所有 packet 的 unknown 原因与补充 dispatch gate；
- `readonly-query/private-original-request-packet-examples.json`：3 条原 request 和首 packet 原字段，只供私有核验；
- 同目录冻结来源／模型／corpus身份核验与原字段文件，原件和失败保留。

此诊断0新 LLM调用、0训练、0新POST、0原库写入。原worker退出原因尚未确定；任务完成事实来自持久库，不能从worker消失推断失败。

终态query receipt SHA `e93fba207c01e6c03a045b3e27b21b8eb0ac2c8c3b39c4de9ec47b3d133a80c4`；business summary SHA `69c46544c4b7f2111b9cd0662abffbe42c846f1ecb340be9ee0fc3ffa9d668e4`；budget原因聚合 SHA `5df00c37d86d39386a97798419dbe7f37876d31c672dce77054b7cad0f187b35`。全闭包 `frozen-readonly-closure-receipt.json` SHA `fa394535ccb74b1e2271b4ef6149f120b765c99e08a391d0362531c747fcb587`绑定查询源码、驱动、原CLOB、复制／原件零漂移及核验脚本；原result CLOB是RunResultVO，不冒称完整report.json。
