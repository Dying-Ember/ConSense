# 官方输出就绪独立核查：统一 runtime、Q1.6 与 Vetting 报告

日期：2026-10-10，Asia/Shanghai。依据 [当前范围规则](../oer-scope-and-rules.md)、[当前交接](../HANDOFF-ocr-embedding-rerank-20261008.md) 和 [官方业务验收计划](./oer-official-business-acceptance-plan-20261010.md)。本轮仅读取当前源码、已保存回执和 question-only 清单，新增本记录与忽略目录中的独立审计回执；没有修改产品代码、中央交接、服务、数据库或来源采用，没有发起 HTTP、token counter、神经模型或官方问答。没有读取 2d／2e 原答案或参考流程图。

本记录的结论是：统一运行入口的已保存代码回归可独立复算；Q1.6 所需新流程图仍缺产品输出链；现有 Vetting 已有三格式报告结构，但正式多来源语义评分和完整模型输入预算仍须补齐。三者不能互相替代。

## 统一 runtime 回执的独立复核

已重新解析 `regression.log` 的完整 unittest ID，逐字计算当前消费者源文件及冻结副本 SHA，结果如下。

| 项目 | 独立复算结果 | 证据 |
| --- | --- | --- |
| 执行数 | 105 | 日志中 105 个 `… ok` 记录；末尾 `Ran 105 tests in 73.508s / OK` |
| 唯一 ID | 88 | 日志 full test ID 去重，与 receipt 的 ID 集合完全相同 |
| 重复执行 | 17 | 105−88；导入的既有 base 测试类重复执行，不增加唯一覆盖 |
| 新混合唯一测试 | 17 | `test_oer_unified_server.UnifiedHttpTests` 的新用例 |
| 失败／错误／跳过 | 0／0／0 | 已保存回执及日志 |
| 当前／冻结源文件 | 14／14 全部匹配 | 每个当前文件和 `source-snapshot` 副本均等于 receipt 的对应 SHA |

原回执：根目录 `.local/state/oer-unified-runtime-20261010/run-054600-regression/validation-receipt.json`，SHA `53582059d71cf682617aae02afa9a6da147dd8045cb35c6ae3dc12bb86203f6e`；日志 SHA `054dd62d2af88b49e5dc6396f19eddb020f7472db6388112ff0e1236825f3ad0`。完整 14 文件清单在该回执及本轮独立回执中，不另扩成发布包清单。

本轮独立回执：根目录 `.local/state/oer-official-output-readiness-20261010/unified-runtime-independent-audit.json`，SHA `988c2c18d04819b3f7dd8f9125975558f1904966fb658c5c45c76e7f0f987c32`。这是保存日志和文件字节的只读核查，没有重跑测试。原测试使用 ASGI、实际 authored 磁盘 CAS 与 fake vectors；并未启动 TCP、调用真实 BGE／M3、登记实际项目采用或完成前端业务验收。首轮三个测试假设失败仍保留在原 run，不回写成初次全绿。

可用于中央交接的准确表述：**统一 Python runtime 的保存回归为 105 次执行／88 个唯一测试，其中 17 个新增混合测试，0 失败／错误／跳过；本轮独立复算日志并确认 14 个当前源文件及冻结副本均匹配回执 SHA。证据覆盖 ASGI、authored CAS 与 fake vectors，真实来源采用／真实模型／业务验收另验。**

## Q1.6 新流程图的实际缺口

题目仅从根目录 `.local/state/oer-official-acceptance-inventory-20261010/questions-only.json` 读取，SHA `4c4c8b53c235d320b881009c982796a78d5edd2e72aaa712eade95fd53372009`。该文件 `answerTextIncluded=false`，共有八个唯一题号；Q1.6 的问题为：

> Please summarise the Housing Authority General Conditions of Contract Clause 17.1 on Avoidance and Resolution of Disputes by means of a flowchart.

清单注明该题指向原 PDF 物理第 5 页、`expectedOutputKind=flowchart`、historically exposed、`humanConfirmed=false`。本轮未打开被指向的 2e 原答案 PDF。Q10 原图回取不能覆盖该题的新图生成维度。

| 当前层 | 源码事实与地址 | 对 Q1.6 的影响 |
| --- | --- | --- |
| 模型解码 | `AdviceService.java:255–257,603–611` 的 `AnswerRecord` 只有 `grounded/answer/citations/missingReason/clarifications/basisScope`，没有图结构；unknown JSON 字段被忽略 | 模型输出新增图字段会被解码丢掉；把 Mermaid 写进 answer 仍只是字符串 |
| 回答／历史 DTO | `web/dto/AdviceDtos.java` 的 AskResponseVO、ChatMessageVO 仅有 LocalizedText content 与 sources／citations 等 | 前后端没有可验证的节点／边契约 |
| 持久化 | `domain/ChatMessage.java` 无图字段；`AdviceService.java:491–508,560–570` 保存／恢复字符串答案和冻结 sources | 即时图若只存在客户端，刷新／重开历史会丢失 |
| API | `web/AdviceController.java` 的 `/ask` 返回现有 AskResponseVO；无图或导出路由 | 不需要新独立模块，但现有 response 必须扩展 |
| 前端 | `ai_consense_web/src/api/types.ts:715–745` 无图类型；`AdviceView.vue:208–224,431–455` 仅 `<p>` 文本插值 | 现有 UI 不渲染新流程图 |
| 渲染依赖 | `ai_consense_web/package.json` 无 Mermaid／graph library | 不能报告当前可直接显示／导出流程图 |

### 建议的最小实现，尚未实施

当前先在真实冻结两来源上运行现有业务的首次 question-only Q1.6，原样保存回答、未知和失败，文字／原文／条件分析可以独立评分，图输出明确 `unsupported`。本节仅为后续根据实际差距选择的方案，不作为首次运行的前置要求；本轮不新增 graph 产品能力，也不为过图维度预改提示或填参考答案。

在既有 Advisory JSON 结果中增加可选 typed `flowchart`，不另建业务流程。只需 `schemaVersion`、节点 `id/kind/label/citations`、边 `from/to/label/citations` 以及必要的入口信息；普通文字问答兼容旧字段。模型必须依据本次实际原件和既有引用 ID 生成，不能把 Q1.6 参考答案、固定法定节点或固定期限写入 producer 提示／代码。

先验证结构：唯一节点 ID、边两端存在、入口存在、所有有效节点可达、必要分支有明确条件、节点和条件／期限的引用只指向本次已验证 sources；限定图规模与 label 长度，避免超出可读画布。允许原条款本身合法的回环，不以“必须是 DAG”代替合同逻辑判断。结构门控通过仍不证明法律拓扑正确。无充分依据／结构无效时保留明确失败或澄清状态，不能静默降为文字后声称图维度通过。

持久化增加一个独立 JSON 字段和迁移，并在现有 ask/history DTO、save/history 恢复链中贯通；sources 继续使用同次冻结身份。前端增加一个确定布局的小组件，使用类型化标签生成 SVG 的节点、箭头和文字，不接受模型 raw HTML／任意 SVG。节点引用复用现有 source 打开方法，SVG 可下载；文本说明和图一起保留。首版足以覆盖该条款的可读流程图，无需交互图编辑器或新审批流程。

必要验收分开执行：

1. 契约与历史：正常图、缺节点、重复 ID、悬空边、不存在 citation、真实回环、unknown、scope/version 变化；刷新和重开后图与引用保持同一冻结身份。
2. 安全与渲染：标签按文本显示；可读的多分支／长文字／多语言图，实际浏览器渲染与下载 SVG；不以组件 test 代替浏览器。
3. Q1.6 原文与语义：生成结束后对照当前实际采用的完整 GCC Clause 17.1 及必要关联依据，逐节点核条件、时间、顺序、分支、终点和引用。再独立打开参考答案做离线比较；不先看参考图来反推节点。
4. 图维度与文本维度分别评分。模型能输出文字或合法 JSON，不代表已交付正确新流程图；需要保留实际生成结果和失败。

## Vetting 正式报告的已有能力与执行门槛

### 当前结构和业务入口

`VettingController.java:49–59` 提供异步 `/runs`、`/runs/latest`、`/runs/{id}`；`/findings`、`/metrics`、finding evidence 与 `/report.json|docx|pdf` 分别提供发现、统计、来源和导出。`VettingService.java:146–166` 在排队时冻结所选 LLM operation 与来源 snapshot，并在 worker 绑定；真实评分必须明确请求 `X-ConSense-Llm-Profile: minimax-cn` 并验证实际返回的 modelIdentity，不能由默认 profile 推断。

`VettingService.java:559–563` 的 JSON 为 `schemaVersion=1.0`，含 `language,project,job,findings,reportStructureVersion,reportLanguage,commentsTables,checklistTables,checklistResults,evidenceIndex`；结构版本 `competition-comments-and-checklist-v1`，实际报告语言 `en`。PDF／DOCX 也由同一英语展示层生成（`VettingService.java:554–555`）。`reportContext:571–576` 要求最新 job COMPLETED，拒绝来源 stale，导出只取该 run 的 active findings；这些是结构／身份门控，不保证审查正确。

FindingVO 明确保存 code／fingerprint／runId、类型与分组、正文／影响／建议、位置与 evidence、verification 和人工 review 字段；Coverage／SemanticTopic／SemanticPacket 保存 source snapshot、canonical corpus identity、实际 global call、aggregate review、submitted／unresolved、packet、inputBudgetStatus 等。不能只读取顶层 COMPLETED 后丢弃 partial／unknown。

`VettingReportPresentation.java:24–76` 按实际 documentId／fileName 分组，编号与 Page／Clause-Item／Comments／可选 Project Team remarks 及 evidenceIndex 相连。零发现明确写“未记录发现不代表文件没有问题”。`:78–107` 的 checklist 是保存的人工作业结果；unchecked 不转换为 No comment 或 fulfilled，样板结构中的计价行标注范围外。正式语义评价仍需逐断言按当前范围过滤，不把整张样板检查表当活动任务分母。

### 可复用脚本的实际边界

| 工具 | 可复用能力 | 不可代替的门槛 |
| --- | --- | --- |
| `tools/vetting_eval/integration.py` | 来源 manifest、上传／已有项目 run／poll／发现／原文定位、三格式导出、生成后 offline review matrix | 当前无显式 LLM profile 参数／header、无 canonical 内部采用注册；不能直接启动官方批次 |
| 同工具的 `--review-existing` | 使用现有上传记录，真正启动新审查 | 不是只读恢复；会调用模型 |
| 同工具的 `--resume-completed` | 读取录下且仍是 latest 的 completed run，不新发起审查 | 第 549 行仍要求 findings 非空；合法零发现不能完成其后结构导出，需单独 collector |
| 同工具的 `--skip-status-check` | 避免第 564–575 行 synthetic Handled／rerun 操作 | 正式基线应启用；不要把人工状态写入正式评分项目 |
| 同工具的 evaluation matrix | 第 296 行 `automaticVerdict=NOT_SCORED`，第 301 行明确 quote match 不评合同判断 | 不给正式正确率；内部 vetting_cases 也不自动等于官方 2d 分母 |
| `eval.py`、foundation_scope_evaluation／foundation_retrieval_audit、fresh_client_readback | 解析、候选池、召回排序、冻结结构／持久状态的证据核查 | 不生成／评分八题法律回答，也不评价正式多来源审查报告 |

真实 G305 单源 collector 已保留 JSON／DOCX／PDF、job／ledger 与独立复算脚本，见 [单源真实报告记录](./oer-g305-real-vetting-report-collection-20261010.md)。这些保存证据可以复用读取方法；单源 17 次 global 返回空数组、17 主题 partial、102 request episodes 因 budget_unknown 未提交，不证明正式报告通过，也不能说模型没收到原正文。

### 正式多来源执行前的具体门槛

1. 建立实际隔离持久项目与当前范围内 2b 合约／PRE、必要项目事实和实际采用的标准；2a 模板仅 Drafting，2d／2e 只在生成后的离线 grader 可见。每个文件保留 raw SHA、真实 DB documentId、parsed revision、routing、采用和完整 canonical source 身份；不能用样板 ID 或补丁声称采用完成。
2. 同一 frozen run 绑定实际 Java Jar、模块专属 Python／CAS／索引、模型 profile/configuration、prompt／schema 与来源 snapshot。采集完整实际模型输入和预算身份；对正式扩展 packet，unknown 继续 blocked，不能把 submittedChars 正文数当整包 tokens。
3. 当前 M3 `/chat/completions` profile 尚无完整 token observer；v3 metadata 共享的离线往返／字符节省不追认 token fit 或神经质量。若采用另一条 Responses+counter 线路，按新 wire/profile 独立绑定，不能把 counter 套到旧 chat wire。修复后需新运行，保留旧 G305 partial。
4. 用显式隔离 Java HTTP 发起一次真实多来源 job，保留首次输出、空数组、partial、失败、重试与墙钟；collector 接受零发现并继续收集三个报告和 coverage，而不将其判为质量通过。不在生成进程读参考答案。
5. 先做可复算结构评分：code／run／metrics、来源分组和稳定意见编号、JSON／DOCX／PDF 完整字段及续页、unknown／No comment／人工状态一致、原文 quote 与当前 pointer／asset／whole group。一律分开记录接口成功、文字层完整和全页视觉可读。
6. 生成结束后独立给 2d 每条参考断言绑定当前范围与实际 2b／采用依据，去除跨页重复和范围外断言后确定可评价分母；逐条区分 accepted／false positive／miss／unknown，尤其审查冲突两端、例外、影响和建议。既有“末尾 7.61”不能直接当 61 个独立缺陷。
7. 实际前端打开发现、冻结证据、原图／完整表组与 Q11 可编辑 Advisory 草稿；另一个受控副本再做 source／revision／adoption 变化负例，恢复后独立重开持久库，不边改基线来源边生成评分。

截至本轮核查，官方八题真实执行和正式多来源报告语义评分仍未由本任务进行；不把本记录、源码测试或单源连通表述为已达到比赛预期。

后续最小收集工具已新增并仅做 in-memory 契约测试，见 [收集器说明](./oer-official-business-capture-20261010.md)。它支持首次 Q1.6 与独立 Vetting 收集，不修改上述产品图能力、No comment 或评分规则。
