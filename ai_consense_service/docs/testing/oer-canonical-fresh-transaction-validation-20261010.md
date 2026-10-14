# Canonical 当前来源的独立事务复验

日期：2026-10-10，Asia/Shanghai。依据 [当前 OER 范围](../oer-scope-and-rules.md)、[handoff](../HANDOFF-ocr-embedding-rerank-20261008.md) 和 [真实 Java→Python bridge](./oer-real-java-python-bridge-validation-20261010.md)。本轮仅修复来源／采用复验的数据库事务边界，没有修改 OerBusinessEvidence、canonical retrieval client、Vetting 或 Advice，也没有操作在线数据库、服务或模型。

## 具体问题与处理

在长事务中，调用方的 EntityManager 可以保留已经读取的 SourceDocument；REPEATABLE READ 还可能保持较早的数据库读取视图。仅再次调用 repository 不足以证明读到最新来源或新采用。资产 HTTP 返回后的检查也可能重用 HTTP 前已经读过的实体，漏掉请求期间提交的变更。

`CanonicalEvidenceService` 的公开只读 API `currentBindings`、`snapshot`、`pointer`、`pointers`、`asset` 通过 Spring 代理执行 `REQUIRES_NEW / READ_COMMITTED / readOnly=true`，挂起调用方事务，使用独立 PersistenceContext 读取 binding。新 `CanonicalCurrentSourceReader` 另以相同独立事务执行 source、catalog、角色／模块、parsed revision、routing 与实际原件 bytes SHA 的复验；尤其 asset HTTP 完成后的 `assertCurrent` 不再复用 HTTP 前的来源实体。

reader 同时核对最新明确 adopted projection 和 snapshot bytes SHA。发生新采用后，`currentBindings` 返回新采用；仍请求旧 projection 的 snapshot／pointer／asset 拒绝为 `CANONICAL_ADOPTION_SUPERSEDED`，不会把历史采用当当前，也不会回退。已有 raw／snapshot 的历史证据不因此删除。来源变更、同名新上传（即使解析失败）、模块排除或原件变动继续按各自理由拒绝。

`registerReviewed` 在新 binding 尚未保存时只复验当前已提交 source，不要求尚不存在的新 adoption。生产 bean 使用显式注入的 Spring reader proxy。旧四参数直接构造入口继续保留，供已有 authored fixture／独立 probe 使用；直接 `new` 本来不执行 Spring 事务 advice，不将此前 direct-construction 真实 bridge 追认为独立事务验证。生产采用需要已提交的 SourceDocument；未提交上传不从调用方缓存继承来源身份。

独立事务在嵌套调用时使用额外数据库连接。本轮没有并发生产负载或连接池容量验收，不能用两个测试 writer threads 推断大量并行请求不会等待连接；远程 LLM／检索应继续按业务接缝单独处理事务与当前 selected scope。

## 真实 Spring／H2 并发验证

测试为 `CanonicalFreshTransactionTest`，使用实际 Spring service／reader 代理、实际 H2/JPA、外层 REPEATABLE READ 事务、已缓存实体和独立 writer thread 的已提交变更。正向设置中明确证明外层 EntityManager 仍返回旧实体和旧正文，再由生产 API 识别新状态。不是 Mockito 的 repository 答案模拟。

HTTP 为自有 ephemeral localhost fixture，提供 authored sealed projection 和实际 fixture 原件／PNG bytes；它只用于事务与来源门控，不调用 BGE、OCR 或 LLM，不作真实原件质量声明。原件 bytes SHA 使用实际 fixture 文件核算，并未伪填原件 SHA。

| 测试 | 实际结果 |
| --- | --- |
| Spring 代理／事务属性与资产正向调用 | 两个生产 bean 为代理；五个公开读 API 的 advice 均为新事务、READ_COMMITTED、只读；实际 PNG bytes 正向返回 |
| 外层缓存旧 source，第二线程提交新 parser text | `currentBindings`／snapshot／pointer／pointers／asset 均拒绝 `CANONICAL_SOURCE_STALE`，外层旧实体未被替换 |
| 外层已读旧 binding，第二线程提交新 projection | `currentBindings` 看到新采用；旧 pointer／snapshot 拒绝 `CANONICAL_ADOPTION_SUPERSEDED` |
| 外层已读 source/catalog，第二线程新增同名 failed upload | 当前 corpus 和 batch pointers 拒绝 `SOURCE_DOCUMENT_SUPERSEDED` |
| 第二线程提交 reviewRole 变化 | pointers 拒绝 `CANONICAL_SOURCE_STALE` |
| Asset localhost HTTP 等待期间提交 parser text 变化 | HTTP 后复验拒绝 `CANONICAL_SOURCE_STALE` |
| Asset localhost HTTP 等待期间提交新采用 | HTTP 后复验拒绝 `CANONICAL_ADOPTION_SUPERSEDED` |
| Asset localhost HTTP 等待期间提交同名 failed upload | HTTP 后复验拒绝 `SOURCE_DOCUMENT_SUPERSEDED` |
| Asset localhost HTTP 等待期间改变原件 bytes | HTTP 后重新读实际文件并拒绝 `SOURCE_ORIGINAL_BYTES_CHANGED` |

复制 checkout：根目录 `.local/state/oer-canonical-fresh-transaction-20261010/run-20261009T173037Z-4f2362`。Maven 编译该复制源码，仅执行以下聚焦 suites：

| Suite | 执行／通过 | 边界 |
| --- | ---: | --- |
| `CanonicalFreshTransactionTest` | 9／9 | 实际 Spring/H2 两线程与 asset HTTP 阶段复验 |
| `CanonicalEvidenceServiceTest` | 19／19 | 既有 authored wire、实际 localhost HTTP、完整 JSON／资产门控回归；repository 为 fixture |
| `CanonicalSourceBindingPersistenceTest` | 1／1 | exact V14 SQL／H2 MySQL-mode 与实际 JPA；不代替 MySQL 5.7 升级 |

总计 **29 项，0 failures、0 errors、0 skipped，BUILD SUCCESS，launcher exit=0**。新增测试和 source reader 与生产工作区的最终 bytes 均等于复制 checkout。本轮没有重新跑模型、在线 Java 或前端。

证据：

- [最终 validation receipt](../../../.local/state/oer-canonical-fresh-transaction-20261010/run-20261009T173037Z-4f2362/validation-receipt.json)，SHA-256 `775a3a3fce4474bff2d294c537003d9b852eaa623ef2e6ff72fba37ad73c9ba6`。
- [最终 Maven log](../../../.local/state/oer-canonical-fresh-transaction-20261010/run-20261009T173037Z-4f2362/focused-maven-attempt3.log) 与 [launcher receipt](../../../.local/state/oer-canonical-fresh-transaction-20261010/run-20261009T173037Z-4f2362/launcher-receipt-attempt3.json)。收据绑定 owned 源码、测试、三个 Surefire XML 和三轮日志 SHA。
- 生产 service SHA `dd24c0d989652f9f2707248f5921326ed4b05a68f7d0f062386f8469c3cdd7b0`；reader SHA `13b72f42dac9e3b142c09634627f1e303a76c34566ced4f61a10987c911ede07`；事务测试 SHA `2395ae265537e990196b09572e0b60bfe9ea6baf96505e5222b6379eda6aa904`。

保留初始化失败：attempt1 的新事务 suite 为 1 通过／8 setup errors，因 pending JPA deletes 尚未 flush 就重置 H2 identity，造成重复主键；改为隔离 H2 内即时 DELETE／clear 后修复。attempt2 为 8 通过／1 error，因单线程 fixture HTTP server 在 asset 等待时阻塞新采用的 health 请求；自有 server 改为四 worker 并严格结束后修复。两轮日志和原 Surefire XML 均保留，不记为全部通过，也没有为夹具失败修改生产复验规则。

## 尚未验证的门槛

H2 支持本轮实际 REPEATABLE READ／READ COMMITTED 和 EntityManager 行为，但不替代 MySQL 5.7 的真实隔离、升级、连接池负载或事务时序验收。此 API 门控在执行复验时拒绝已提交的来源／采用变更，不宣称对复验后发生的任意提交提供跨 HTTP／LLM 的全局串行化。

业务仍需在实际任务结束／保存前核对 current selected scope，尤其 fullset 新增不同文件、模块采用改变和 LLM 期间变更；本轮只处理选定 document IDs 的来源／当前 adoption API，不修改业务选择规则或 LLM 流程。Vetting／Advisory 的实际项目、前端、整源 OCR／检索质量、可靠 native producer 和 H800 发布包仍按各自未完成门槛验收。
