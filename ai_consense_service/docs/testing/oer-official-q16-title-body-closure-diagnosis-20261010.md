# 官方 Q1.6 标题命中与正文闭包的只读诊断

日期：2026-10-10，Asia/Shanghai。本页记录首次实际 retrieve 的 source-only 诊断，不读取 MiniMax 最终答复、2d／2e 答案或评分 gold。继承 [范围规则](../oer-scope-and-rules.md)、[实际索引核验](./oer-actual-two-source-java-index-real-bge-validation-20261010.md) 和 [首次实际 retrieve 核验](./oer-official-q16-actual-retrieval-readonly-validation-20261010.md)。本页的 coverage 分母是当前 adopted 源内已登记的结构子单元，不是人工答案规定的必要法律证据。

## 结论与责任层

本轮 GCC 17／17.1 标题排在前面，但正文未返回，存在两个独立因素：probe 使用较小的 24 candidate／3 unique SourceUnits 输出预算；SourceUnits 与 structure context 只闭合相同 clauseId 或相同 observed block，没有消费跨 whole-unit 的 typed parent 层级。提高 K 可以取回已有候选中的部分正文，但无法补齐未进候选的正文。完整原件 payload 没有在 HTTP 或 Java business 接缝丢失。

| 层 | 本轮实际事实 | 责任边界 |
| --- | --- | --- |
| 原件／canonical 公共投影 | GCC 已 adopted 的物理 65–70 页有 50 whole units／92 parts；17.1 正文是 30 whole units／64 parts；正文到 17.1 标题有 30 条已登记跨 whole-unit parent 边 | 现有来源具有这些正文与层级证据，不是这 30 单元在 transport 途中消失。source review、humanConfirmed=false 及语义 unknown 继续保留 |
| `SourceUnits.resolve` | 相同 source scope 的 clauseId **完全相等**才合并；GCC 两标题与各正文 clauseId 不同，每个单独形成 1 member unit | observed unit 保真通过，不等于整个标题下的正文闭包 |
| `structural_retrieval._catalog` | 只重建 `resolve(...).members` 的 blocks；GCC17 标题 structure text 40 UTF-16 code units，17.1 标题 42，均 1 required member | 保存 relations 不会自动把边指向的子单元纳入用于 embedding／rerank 的 parts |
| candidate／rerank | 实际候选 24 parents；GCC17.1 正文只有 13 个候选，另 17 个不在池中。实际标题排 1／2，SCC17 排 3 | 排名是这次真实模型相关性输出；标题命中后不携带现有正文是结构消费边界，不能全部归为模型能力 |
| limit | probe 为 top K=3，实际返回两个 GCC 标题与 SCC17 | 小预算进一步截断；不等于正式服务默认就是 24／3 |
| Java `OerCanonicalSourceUnits` | 严格镜像 Python 的 exact-clause／observed-block 规则 | 若直接改变 Python members 而不更新 Java 规则，canonical 校验会拒绝，不能只改一端 |
| Java `OerBusinessEvidence.businessHit` | 从返回 sourceUnits 的 members 按 session 源顺序拼接全文，并保留 complete canonical payload 与 pointers | 这里没有额外丢掉本应已返回的正文 |
| Advisory | canonical 路径直接使用 canonical retrieval；`withCanonicalIncorporationContext` 只补 standard 的项目采用／版本／override 依据 | 旧 vector 路径的 `retrieveTopKFloor=20` 不作用于本轮；采用上下文不能替代 GCC 章节正文结构闭包 |

probe 配置来源为 `.local/state/oer-official-source-business-bridge-20261010/probe/start-prepared-runtime.ps1:24`，明确设置 `candidate-limit=24`、`top-k=3`。frozen Java `ConsenseProperties.java:149`–150 的正式默认是 candidateLimit=50、topK=10；canonical retrieval 从 `properties.getVetting()` 取这两个值并用于 Advisory。**本次 top10 重放仍使用同一 captured 24 候选池，未测试 candidate50 的新池和新神经结果。**

## 同一完整 reranked sequence 的纯重放

以下结果使用 actual 24 个分数与完整排序，经原版 `select_source_units` 重算。K 指 unique SourceUnits，不是直接截 raw rank。SCC17.301 的 6 个 parents 解析为同一 source unit，故不能用 raw 前 K 条推算数量。

| 输出 K | 实际 unique units | 返回 GCC17.1 正文 whole units | 已登记正文分母 | 缺少正文 |
| ---: | ---: | ---: | ---: | ---: |
| 3 | 3 | 0 | 30 | 30 |
| 10 | 10 | 5 | 30 | 25 |
| 20 | 19 | 13 | 30 | 17 |
| 24 | 19 | 13 | 30 | 17 |

candidate 未含的 17 个正文单位为：17.1(3)(a)、(4)(a)、(4)(b)、(5)(a)、(6)、(7)(a)、(7)(b)、(8)(a)、(8)(b)、(8)(c)、(10)、(11)(b)、(11)(c)、(11)(d)、(12)、(13)(b)、(15)。这里括号写法只是便于阅读的实际 unit 标签；重放算法用 exact canonical unitId 与 typed edge，不按数字前缀推断层级。完整 IDs、每 K selected seeds／covered／missing 清单保存在 receipts。

## 最小 source-only 红／绿信号

ignored helper：`.local/state/oer-official-actual-index-readonly-audit-20261010/replay-title-body-coverage.py`。固定 metadata SHA 为 `7e67db58cc193df3a616cb3fddcad91e84019467f09a4408772048d98908b9cc`，actual retrieve journal 为 `20261009T232514.466067Z-8b7926a89be448ca9d4876003407ceb6`。

baseline 使用原版 resolve／select，先逐对象验证 response hits 和完整 sourceUnits 等于 actual，然后对已命中标题是否携带现有已登记子单元作断言。baseline **预期 exit 1**，状态 `baseline_missing_declared_descendants_red`，失败与 receipt 保留；这不是 helper 误读 envelope 的旧失败，也不是主代码修改后故障。

prototype 只对 actual top3 seeds 沿同源、同 revision、已登记 `parent`／`between_whole_units` 关系取 descendants，并以 context-only 原 payload 并集作重放，不改变分数、池、origins 或原未知状态。prototype **exit 0**，状态 `prototype_observed_descendant_coverage_green`，覆盖 30／30；这证明可用已有结构证据补全观察上下文，**不是 Q1.6 答案、必要法律闭包、全件质量或业务验收通过**。

| 收据 | SHA-256 | 状态 |
| --- | --- | --- |
| `.local/state/oer-official-actual-index-readonly-audit-20261010/closure-replay-baseline-233924-1695f5/closure-replay-receipt.json` | `fa2aeed64b57c1894a279687c5c42033789940715d741e034ad18eefe5eed0da` | 原行为红；expected exit1 |
| `.local/state/oer-official-actual-index-readonly-audit-20261010/closure-replay-prototype-233928-5b8242/closure-replay-receipt.json` | `b76893c2560f74360c271a6efd8a0d08136d9a29505eca27c44f60f5f72e26ac` | context-only 观察覆盖绿；exit0 |

receipt 统计全 metadata 的 36 条跨 whole-unit parent 边；其中 31 条属于本次 GCC17 层级（30 正文→17.1 标题，17.1 标题→17 总标题），另 5 条属于 GCC18 层级。不存在独立 17.1(3)／(4)／(5)／(7)／(8)／(11)／(13) 的中间 canonical 节点；禁止从编号创造节点。内部 parent、continuation、qualifier 等关系继续保持原 payload，不转成跨 unit 图的自环。

两个 GCC 标题＋30 正文是 32 GCC units／66 parts；joined content 为 21,662 UTF-16 code units（含单元间换行）。actual top3 context 并集另保留 SCC17，合计 33 canonical parents／21,705 UTF-16 code units。**这些是字符成本，未测实际 tokenizer token 数、分窗、encoding 时长或最终 prompt 容量。**图像／页位／cells／pointers 保持 canonical 原对象；没有为 sidebar 视觉关联增加合同义务。

## 通用实现接缝建议

保留 existing exact observed unit 的意义，增加明确标记为 `observed hierarchy context` 的 typed-parent context 闭包，不能把它伪装成已核法律闭包。结构扩展只消费当前 selection／source／revision 内冷验、明确声明的 parent 边。端点缺失、跨来源或 revision、冲突、多父与跨 whole-unit 环必须 fail closed 或明确 unknown；无可信边与非 native 来源保持旧行为。内部 relation 合法自链接不按跨 unit 图误判。

闭包成员按 canonical 源顺序并集，保留 whole units／完整 parts／images／cells／位置与所有 unknown；原神经 seed 保留其 score、ordinal、candidate origin，衍生 context members 不领取新分数、不成为新 rerank seed。chapter 字符串、关键词、官方答案、评分结果或待审报告均不得参与结构扩展或补充原件索引。

接口需要 Python／Java 相同 pure 重算、不同版本的 policy／context identity、算法 SHA 与 recipe／signature／cache 失效规则；不能只放宽 Java 接受任意新增 JSON。若用于 structure embedding／rerank，则必须更新分窗／context recipes并用新 index 身份重建；若先在选择后用于 Advisory context，也必须单独门控返回完整性与 prompt budget。超预算应明确 incomplete／分组，不能静默截断而仍报告闭包 complete。

目前只完成 source-only 离线 prototype；主源码、运行服务、sealed public、实际索引与首轮答复均未改。GCC整体仍 PARTIAL／仅采用物理65–70六页，实际 SCC17仍 partial／wholeSccAccepted=false／effective edit unknown。首轮最终回答评分、真实新 recipe／索引／模型输入性能、Vetting结构化报告与前端交付分别验收。
