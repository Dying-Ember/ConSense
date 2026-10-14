# Vetting canonical 业务接线与统一隔离回归

日期：2026-10-10，Asia/Shanghai。依据 [范围规则](../oer-scope-and-rules.md) 和 [实施交接](../HANDOFF-ocr-embedding-rerank-20261008.md)。本文记录最新生产源码、业务调用接缝与隔离验证，不追认真实 LLM、在线 MySQL、浏览器或全库质量通过。

## 实现与实际行为

启用 `OerBusinessEvidence` 后，Vetting 以当前允许文件的完整有序集合建立 Session；不通过 `parseUsable` 丢弃最新失败或未绑定来源。缺少 binding、canonical index 或 retrieval 失败时拒绝，不因旧 `requireHybridRetrieval=false` 回退到 legacy 候选。

规则审查使用已采用的 tender 观察；证据按原锚、原 quote 和来源绑定，位置歧义不标 located。语义审查的实际 selector／planner／prompt 保留完整 canonical payload、跨页表组、parts、sourceSpans、relations、资产与 unknown；不把扫描表格伪装成 POI `nativeRows` 或旧 metadata/quality 版本。

原 reranked seed 的完整 observed closure 按整组加入既有字符预算，超预算整组省略；derived members 不成为新 rerank origins，也不再用它们扩 literal 关系。完整模型 Input 仍由既有 token 门控决定。`complete_observed_unit` 不表示条件完整、标准适用或语义已核验。

模型调用前后复验 Session。保存前重新读取当前 permitted selected scope，新增来源、版本或采用变化使旧 Session 失效。报告覆盖分母仅为已采用观察的 UTF16 区间并集，并绑定 `canonicalCorpusIdentitySha256`；不能称完整原件覆盖。报告的 stale 判定包含当前采用身份。

## 本轮失败及最窄修复

第一轮统一回归 `run-014007-2ffb11`：214 tests，1 failure、2 errors。三项均发生在新增 `VettingCanonicalBusinessIntegrationTest`；其余 211 项通过。新增最小往返断言后，`run-014236-a257c4` 的 6 项中 2 failures、2 errors，日志保留。

region chunk 的 raw payload 不含可选 `tableGroup`，原 pointer 使用 Java null。JSON 反序列化为 `JsonNode` 时将空值变为 `NullNode`，导致完整 `Chunk.equals` 与 `canonicalViewStatus` 都误判身份变化；planner 返回同一个 `canonical_view_identity_differs`。最小测试证明修复前完整序列化 JSON 相同、只有 `canonicalEvidence` 的 Java 值比较不同。

修复仅在 `EvidencePointer.tableGroup` 指定 `@JsonSetter(nulls=Nulls.SKIP)`，保持缺省表组的 Java null。完整 table object、raw payload、unknown、版本与来源门控保持原语义；未放宽 legacy 比较或删除安全检查。`run-014405-158870` 的 6 新业务＋8 Session＋19 service 共33项通过。

## 最新统一源码验证

最终复制 checkout：`.local/state/oer-business-evidence-20261010/run-014457-af0ff6`。只复制当前 `pom.xml` 与 `src`，使用 JDK17 的直接 Maven Launcher、独立 socket tmpdir、`forkCount=0`。全部 main/test 编译；在线 `ai_consense_service/target` 未写入。

| 测试分组 | 执行／通过 | 对应 suite |
| --- | ---: | --- |
| canonical Session／client／source units／health | 44／44 | OerBusinessEvidenceTest 8、OerCanonicalRetrievalClientTest 12、OerCanonicalSourceUnitsTest 16、VettingDualStructureHealthTest 8 |
| 新 Vetting 业务接线 | 6／6 | VettingCanonicalBusinessIntegrationTest |
| 既有相关业务回归 | 123／123 | ContextBudget 6、IncomingLiteralContext 17、SemanticReview 20、ServiceLifecycleWorkflow 8、Service 6、SourceMaterial 12、SourceQuality 18、SourceRequestConditionPacks 8、SourceRequestPacks 18、StrictHybrid 10 |
| 来源／真实 fresh H2 事务 | 29／29 | CanonicalEvidenceServiceTest 19、CanonicalFreshTransactionTest 9、CanonicalSourceBindingPersistenceTest 1 |
| Advisory canonical 接线 | 13／13 | AdviceCanonicalIntegrationTest |

总计 **19 suites／215 tests，0 failures、0 errors、0 skipped，BUILD SUCCESS，exit 0**。来源是各 `target/surefire-reports/TEST-*.xml`，逐文件 SHA 在收据中保存。复制 `src` 的全量 SHA manifest 在生成收据时全部与工作区一致，`changedAtReceipt=0`；相关 diff whitespace 检查通过。

- [最终收据](../../../.local/state/oer-business-evidence-20261010/run-014457-af0ff6/validation-receipt-attempt1.json)，SHA-256 `9aec1a3eead4bcdcc34841e0e4ac284d7cbca9d1bb44a205a2e7b3dbf17f7d07`。
- [原始 Maven 日志](../../../.local/state/oer-business-evidence-20261010/run-014457-af0ff6/maven-test-attempt1.log)。
- 本机可复跑脚本：`.local/state/oer-implementation-20261009/canonical-integration/validate-java-business-evidence.ps1`；支持 `-TestSuites` 收窄反馈，不覆盖旧 run。

这215项是测试执行数，不是合同题准确率。与独立 [Advisory 38项](./oer-advisory-canonical-implementation-validation-20261010.md)、[fresh事务29项](./oer-canonical-fresh-transaction-validation-20261010.md) 有重叠，不相加为新成绩。新6项使用 authored registry/client/model doubles，执行实际 selector/planner/prompt；fresh9项使用真实 Spring proxies、H2两线程事务和localhost HTTP。二者不能合称完成真实业务端到端并发验收。

## 下一步与未验收门槛

Vetting／Advisory 源码接线及相关回归已通过本轮验证；需在隔离持久项目中提交实际来源，按实际数据库ID重新生成和注册 projection，执行真实 Java HTTP→对应模块 Python→BGE→MiniMax→引用／原图，并进行浏览器验收。Advisory 必须用独立 `module=advisory` 进程、CAS／Qdrant状态与URL，不把 vetting 服务响应改名。

旧 GCT bridge 的内存H2事务已回滚，旧ID及保存pointer不当当前前端地址。当前前端需要由最新 controller 生成含 `addressKind` 的实际 pointer。真实 SL跨页表组、Q11轻量入口、scope变化后的迟到响应／历史引用失效仍须业务验收。

在线MySQL迁移、可靠 native canonical producer、范围内固定资料覆盖与精修、残差／留出／同池质量评价、本地参数和必要训练roundtrip、完整Linux/H800发布包与平台推理均未完成。goal保持active；当前线上旧服务未切换，Bonsai抑制维持。
