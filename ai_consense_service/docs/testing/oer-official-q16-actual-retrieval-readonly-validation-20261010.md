# 官方 Q1.6 实际 retrieve 的只读身份、结构与覆盖记录

日期：2026-10-10，Asia/Shanghai。本页只核实际 BGE retrieve 和返回证据，不读取 MiniMax 最终答复或 2d／2e 答案，也不替代官方答案评分。继承 [范围规则](../oer-scope-and-rules.md)、[官方业务验收计划](./oer-official-business-acceptance-plan-20261010.md) 和 [本轮实际索引核验](./oer-actual-two-source-java-index-real-bge-validation-20261010.md)。

## 实际运行和身份

root 是唯一主动 Java／模型调用者。本次只读 audit 的 network、DB open、model load、tokenizer load 和 LLM request 均 0，仅读取 frozen 文件并用纯 `SourceUnits` 规则重算。源选择仍为同一 fresh Java 项目的 GCC ID1＋实际 2b SCC ID2；未添加 Drafting 标准模板、BQ、General Summary、工程技术 PSS、SL 非技术部分或评价答案。

- Java collector：`.local/state/oer-official-q16-first-run-20261010/advice-071210-b73943/`。sources-before 首次 GET200，index-before 首次 GET200；Q1.6 是这轮首次实际 Ask，不重发题目或调整输入取得更好结果。
- OER runtime：`.local/state/oer-unified-actual-preparation-20261010/run-222702-e68e99/`，target=`advisory`。
- actual retrieve journal：上述 runtime 下 `retrieval-state/run-evidence/20261009T232514.466067Z-8b7926a89be448ca9d4876003407ceb6/`。
- [最终只读 audit receipt](../../../.local/state/oer-official-actual-index-readonly-audit-20261010/retrieve-audit-232820-bf97de/readonly-retrieve-audit-receipt.json)，SHA `a5cceafeeff2127cff366ad2881a5d8da29fe5af5de6847aec1b58351c995ba3`；上游 index audit SHA `715d6b38e7440e3340dfb345dc3c33030b6cdddd5bcc50686faa91d8ea6469ae`。

实际 request／response 的 projectId 与已核 Java indexKey 完全一致；indexSignature=`b7bf76d25534183567abe0ae1edaf4fd764ad73dee34b7b82e8bd2c93e3d12b9`。持久完整 metadata SHA 未变，132 个 frozen source／sample 文件仍与 manifest 相同。没有打开运行中的 Qdrant 或进行第二套神经调用。

## 模型输入、同池复评和结构保真

本轮 actual request `candidates=24`、`limit=3`，不设置 role 过滤。实际 candidate pool24，与 `parent-rrf-candidates.submittedParentIds` 顺序完全相同；每个 pool 行的 documentId／role／sourceHash／完整 payload SHA 与当前 metadata 原始 parent 相同。

同一 query／同一池执行 baseline25 pairs 和 structure19 pairs，actual pair SHA、完整 pair-window manifest SHA、实际分数数量／有限性均可重算。contextUnavailable=0；这是已观察结构范围内可构建 context，不代表必要法律证据齐全，也没有宣布排序质量改善。

actual `window-model-loaded` 显示新加载的 reranker 参数 `cpu`／`torch.float32`，revision `BAAI/bge-reranker-v2-m3@953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e`。embedding 复用本轮 index 已加载的真实 BGE-M3，actual query tensor1 row；reranker tensor44 rows，与25＋19实际 pairs相等。

| 实际 tensor 输入 | rows | 最大完整 tokens | actual cap | 截断 |
| --- | ---: | ---: | ---: | --- |
| Query embedding | 1 | 37 | 2,048 | `effectiveTruncation=false` |
| Reranker baseline＋structure | 44 | 1,152 | 2,048 | `effectiveTruncation=false` |

actual featureMode 都为 `inference_tensor_features`，不是预计划 token 数。guard 未记录原始输入 tensor 的 device／dtype；本页只陈述已观察到的参数 CPU FP32 和实际 mask token counts，不补写原始 `input_ids`／`attention_mask` 属性。

36 events 全部 ordinal 连续、newline 完整；terminal completed，request／response／events bytes 和 SHA 均符合 receipt。使用完整实际 rerank 结果重算排序和 `select_source_units(parents, reranked, limit)`，最终 hits、全部 sourceUnits、原成员、requiredMemberIds、seedScore、原排序序号逐对象一致，qualifiers／semantic unknown 没有提升为已确认。

## 不能由保真通过推出召回通过

实际返回3个独立 sourceUnits，每个只有1个 member：

| 返回顺序 | 实际单元内容身份 | source ID | 位置 | 闭包事实 |
| --- | --- | --- | --- | --- |
| 1 | `GCC/physical-65/17.1/title` | 1 | 原 PDF 物理65页 | 17.1 标题；未带入17.1正文子条 |
| 2 | `GCC/physical-65/17/title` | 1 | 原 PDF 物理65页 | 17 总标题；未带入下属正文 |
| 3 | 实际 `SCC17` native 单元 | 2 | DOCX 原生位置，无派生假页 | 原单元1 member；SCC17部分质量继续partial |

三者 `status=complete_observed_unit` 是当前 observed-block／exact-heading 选择规则的组成结论；`qualifiersComplete=unknown`、`semanticScopeVerified=false` 仍保留。标题完整不等于章节全部正文完整，parent／sidebar关系也没有通过 `SourceUnits` 自动递归。两条 GCC 标题没有携带争议解决程序的正文子条，是本轮实际业务证据覆盖风险；不能因为 identity／schema／结构重算通过，就把它写成 Q1.6 必要证据已召回、答案通过或完整法律闭包。

本页没有从官方答案补入缺失正文，没有修改候选池、limit、关系、索引或 harness；先保留首次模型答复并独立评分，再决定如何修复。最终答复是否如实表达证据不足、是否引用正文、图输出是否满足任务，均由单独业务评分记录核验。GCC整体PARTIAL、只采用65–70六页，SCC17 partial／wholeSccAccepted=false 等上游限制继续有效。

## 时间和保留失败

actual retrieve journal elapsed=80.093852s，response 内部 retrieve=32.018302s；后者包含在前者之内，不能相加。差值48.075550s包含来源重验、signature／metadata复核和其他wrapper工作；内部时间仍包含检索、计划、批处理和神经计算，不是纯forward，更不能作为GPU／H800或LLM tokens/s。

首个只读 helper 已通过 response 的完整SourceUnits重算，但误把 event envelope 的额外 `stageOrdinal` 当作 SourceUnits 业务字段，保留失败 `retrieve-audit-232803-ee3e3b/` 与原 helper `audit-retrieve-attempt1.py`。第二次仅在比较 event closure 时剥离既有 envelope 的 `stageOrdinal`；没有改产品、来源、模型、索引、实际 response 或 gate。新的独立审计 `retrieve-audit-232820-bf97de/` 通过，失败没有删除或追认为产品错误。

本页未进行答案／报告质量评分、浏览器验收、H800验收或权重训练。真实模型可运行、来源保真、召回／排序质量、最终回答及前端交付继续分别报告。
