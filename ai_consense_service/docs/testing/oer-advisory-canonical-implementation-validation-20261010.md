# Advisory canonical 接线与隔离验证

日期：2026-10-10，Asia/Shanghai。范围服从 [OER 规则入口](../oer-scope-and-rules.md)。本文记录显式启用 canonical 配置后的 Advisory 业务消费实现、复制 checkout 的实际验证及未完成验收；前序只读设计见 [接入审计](./oer-advisory-canonical-integration-design-audit-20261010.md)。没有在在线 `target` 编译、启动／重启服务或模型、修改在线数据库、触发真实 LLM／OER 神经模型调用或执行浏览器业务验收。

## 实际行为

`AdviceService` 在 `OerBusinessEvidence.enabled()` 为 true 时，按本次用户所选 scope 读取当前允许的文件全集，再构建 module=`advisory` 的 Session。最新解析失败／未解析版本保留在选定集合里，不能被 `parseUsable` 过滤后冒称其余资料索引完整。标准 NTT／SCT／SCC 模板及已确定范围外资料继续由 `ProjectDocumentScope` 排除，实际项目版本分别识别。

canonical rebuild、status 和 ask 使用相同选定集合；scope 在索引与召回之前生效。canonical 失败或缺当前 binding 时返回明确业务错误，不读取 legacy EvidenceChunk、Qdrant 或旧 embedding。未启用 canonical 时保留既有 legacy 路径及模型 profile 选择。

| 接口／数据 | 实现 |
| --- | --- |
| `POST /api/advice/{projectId}/index/rebuild?scope=...` | 归一化 scope，默认 `fullset`；以选定 Session 构建／验证 canonical 索引。 |
| `GET /api/advice/{projectId}/index/status?scope=...` | 读取选定 Session 的实际 receipt／health 状态，返回 backend、scope、indexKey、signature。不能用 legacy 向量库数量宣称 canonical ready。 |
| `POST /api/advice/{projectId}/ask` | 沿用请求中的 scope 和可选 clarification；同一 Session 检索、补充项目采用依据、提交完整观测单元并保存引用。 |
| `CitationVO` | 保留全部 `canonicalEvidence` pointers、完整 `canonicalSourceUnit` 和 `scoreMeaning`，深复制后进入回答及历史 JSON。 |
| `IndexStatusVO` | 新增可选 `evidenceBackend`、`sourceScope`、`indexKey`、`indexSignature`、`indexReason`；旧构造方法继续兼容。 |

对外 `indexReason` 只输出 `canonical_index_receipt_missing`、`canonical_index_unavailable` 或 `current_sources_unavailable`，ready 时为 null。检索／来源／模型失败均包装为安全业务错误；不把外部异常的 credential canary 或原始 exception message 透传给前端。

## 完整上下文、unknown 与采用边界

标准命中后的项目采用依据补充仅扫描该 Session 中 tender 观测，用 composition 文件名或 literal reference mention 作为线索，再调用共享 `sourceHits(session, origins)` 解析完整 source units。不读取旧 EvidenceChunk。追加单元保留 unranked context 意义；真正检索命中保留 actual reranked seed 意义，不给 derived members 编造 rerank 分数。

12,000 字符预算以完整单元为界。超预算单元整体不提交，任何 unknown／缺失／部分返回或未覆盖 origin 都不能标为 `applicabilityContextComplete=true`。覆盖由已提交完整单元的 `requiredMemberIds` 对照本次 origins，而非空集合 `allMatch` 或仅凭 `sourceHits` 调用成功。已在 ranked hits 中完整出现的同一单元不重复追加。

`complete_observed_unit` 仍只表示协议中的观测结构闭合。`qualifiersComplete=unknown`、`semanticScopeVerified=false`、source unknowns 和位置字段进入模型和引用；不提升为标准适用、完整原件或语义核验。项目合约结论引用标准时需要明确的完整观测采用上下文与被引用项目依据，null 不当 true。`reference_only` 至少必须实际引用标准。上述门控不能代替模型对版次、条件、例外和项目修订的语义判断，也不证明真实答案忠实。

历史消息沿用已有 sourcesJson 冻结；更新当前来源后不重新检索／重链旧引用。旧 pointer 的原图回取仍受共享 current-binding 校验约束；本测试仅核验引用 JSON 的保存和回读，不证明旧 asset 在线可回取。

## 事务与当前来源复验

rebuild 和 ask 不再在一个长数据库事务内包住 embedding、检索或 LLM 调用。canonical catalogue 使用独立只读 `REQUIRES_NEW` 事务并清除 EntityManager 请求缓存，防止 OpenEntityManagerInView 把旧 source entity 复用于后续读。检索后、LLM 响应后及最终历史保存前重取完整 selected scope，并核对当前 Session／binding；期间新增来源或直接数据库更改时拒绝保存旧证据答案。

远程操作成功后，用户消息和助手消息在一个短写事务中原子保存；模型／canonical 错误保存零条，避免孤立用户消息。检索无依据的诚实 unknown 回答仍可保存完整一问一答。legacy index 删除使用短 TransactionTemplate，补足 repository `@Modifying` 原来依赖上层事务的条件。

此复验不是对并发来源更新的线性化证明；本次测试证明调用期间及保存前观测到的变更会拒绝旧回答，没有建立持久来源锁或跨检索／模型／落库的分布式事务。

## 复制 checkout 的实际验证

最后验证目录：`D:\R&D\ConSense\.local\state\oer-advisory-implementation-20261010\run-013652-b588a8`。仅复制 `pom.xml` 与 `src` 到该新目录，以 bundled Java 17／Maven 3.9.11 执行五套测试；`forkCount=0`，测试资料及 H2 文件／输出位于复制目录的 target，未覆盖在线 service target。

命令由本机 `.local/state/oer-advisory-implementation-20261010/validate.ps1` 执行，最终 Maven exit=0，`BUILD SUCCESS`。各项数字来自该目录 `target/surefire-reports/TEST-*.xml`，XML SHA 逐项保存在 `validation-receipt.json` 和 `final-source-ledger.json`：

| 测试套件 | tests | failures | errors | skipped | 实际边界 |
| --- | ---: | ---: | ---: | ---: | --- |
| AdviceCanonicalIntegrationTest | 13 | 0 | 0 | 0 | 实际 Spring MockMvc／H2 catalog 和历史；共享 canonical seam 与 LLM 用 authored doubles。 |
| AdviceDocumentScopeIntegrationTest | 15 | 0 | 0 | 0 | 既有 source scopes、clarification、引用及 legacy 行为回归。 |
| AdviceLlmSelectionIntegrationTest | 8 | 0 | 0 | 0 | 既有 profile 选择、模型身份与不回退契约；不等于真实 MiniMax 调用。 |
| AdviceUnconfiguredProfileIntegrationTest | 1 | 0 | 0 | 0 | 未配置的显式 profile 保持拒绝。 |
| AdviceModelIdentityMigrationIntegrationTest | 1 | 0 | 0 | 0 | 既有 V10 身份迁移断言继续保留，fixture 补执行实际 V14。 |
| 合计 | 38 | 0 | 0 | 0 | 13 + 15 + 8 + 1 + 1，由五份 XML 可复算。 |

新增 canonical 测试覆盖：scope 在 index 前生效、模板／计价排除、contract 与 tender 集合不同产生不同 Session key、legacy adapter 零调用、缺 binding 不回退、最新失败解析版本不丢弃、完整多 pointer 和 unknown／scoreMeaning 穿透回答及历史、同一 Session 的 unranked 采用上下文、无 origin／空或部分返回／unknown／超预算整体遗漏不通过采用门控、reference_only 需要真实标准引用、模型调用期间新增来源和直接 JdbcTemplate 修改旧 source 均拒绝落库、远程错误保存零消息，以及远程调用没有实际事务。

private Session／IndexState 构造器仅反射用于 authored carriers；测试没有 seal／parser／原件字节的真实性。完整单元和 pointers 是明确构造的测试输入，不能把上述 13 项当作实际完整表组解析、检索质量、模型忠实性或浏览器验收。真实共享 binding／parser 的验证引用各自独立报告。

## 保留的失败与修复

| 运行目录 | 实际结果 | 原因与处理 |
| --- | --- | --- |
| run-012829-b7fd51 | 25 tests，1 failure，0 errors | 24 个 legacy 回归通过；migration fixture 只到 V13，entity 读取已由实际 V14 添加的 native 字段时失败。仅补 fixture 执行已有 `V14__canonical_source_bindings.sql`；未改生产 DDL、删除断言或重写失败日志。 |
| run-013203-412e6d | 35 tests，1 failure，1 error | authored mock 捕获了初始化空 context list，以及 `when` restub 执行旧 matcher-null answer 造成 NPE。改动态 thenAnswer 和 doReturn；原日志／XML／receipt 保留。此轮未包含最终 direct-DB test 与 V14 fixture，不能宣称最终通过。 |
| run-013652-b588a8 | 38 tests，0 failures／errors／skips | 两个 fixture 问题修复后，加入 origin coverage 与直接 DB 变更测试，五套全部通过。 |

## 来源与收据身份

最终 `validation-receipt.json` SHA-256：`17ee21b25d7bc98bc5eecab2c811a1904cc3ae4444ebf40d3c37f6100f49dbbc`。

最终 `final-source-ledger.json` SHA-256：`879a1bfd6d482dc13c06a60283873c3a5a3800d361d5128dd209382d7c88bd64`。该 ledger 保存五个负责文件、实际共享 seam／reader／V14 共十份复制源与当前源 SHA；落收据时十项全部相同。之后代码变更必须根据新 SHA 重新判断验证有效性，不能继承本轮通过。

| 负责文件 | 最终测试源 SHA-256 |
| --- | --- |
| AdviceService.java | `8eea042cf3f9130d80294f1aa1347aa2361443e59ef14fb6c1f4a1fd3f41fd82` |
| AdviceController.java | `602bbe8b7e3b36ecad173c8271f7d2e0bdc83c80f149276f4da206cb3b46e694` |
| AdviceDtos.java | `c23dc2af423c0228837b8f9946c963575fa9806bc03f871cc96f4354f66f56fa` |
| AdviceCanonicalIntegrationTest.java | `ba26a4e25b29a985f979b2d06cb3fc0192d7a0cafa7908bf277af79e22e7b21c` |
| AdviceModelIdentityMigrationIntegrationTest.java | `5f081c9afa65f83b2ad9b12cffd289181abc6c8ed0b1e664580a2cee2a21efb7` |

上述四个已跟踪负责文件的 `git diff --check` 通过；新增测试源在复制 checkout 全量编译和实际运行通过。

## 后续验收

已完成的是 Advice 业务接线源码、MockMvc／H2 契约和既有 profile 回归。尚未完成显式 OER 运行配置、Advisory 独立 retrieval 进程、真实当前原件注册／完整表组、实际 Java→Python→embedding／rerank→MiniMax 问答、浏览器范围切换／原图展示／历史失效行为验收及 H800 性能。前端分工的隔离 bundle／契约收据另记于前端文档；不能用它或本报告宣称在线部署已完成。
