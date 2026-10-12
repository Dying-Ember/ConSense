# Java canonical 检索客户端实施与隔离验证

日期：2026-10-10，Asia/Shanghai。按 [当前范围与规则](../oer-scope-and-rules.md) 和 [实施交接](../HANDOFF-ocr-embedding-rerank-20261008.md) 实施。验证在复制 checkout 中运行，没有覆盖在线 Java 的 target、配置或数据库。

`OerCanonicalRetrievalClient` 使用 `CanonicalEvidenceService.currentBindings` 提供的完整 immutable snapshots，调用 `/index-canonical`，接收完整 ranked payload／sourceUnits。原件、Java parsed revision、路由、projection 和 snapshot bytes 身份分别绑定；不是把 canonical JSON 转成旧 Chunk DTO。模块特定 retrieval URL 明确配置在 `consense.oer-evidence.retrieval-base-urls`，资产 registry 使用独立 `base-url`。不同模块要求后端实际 health 的 `targetModule` 一致，不能仅对查询返回做事后过滤。

索引前清理旧 receipt；来源／配方变化在查询 POST 前拒绝并清 receipt。HTTP 查询失败或返回校验失败不返回部分结果，不将失败响应变成有效证据。既有成功 index receipt 是否保留与查询响应是否可接受分别处理；transport 失败不自动表示实际索引已变化。

返回的 ranked payload 必须逐字段等于 current canonical chunk。表格、关系、原图引用、新字段及 unknown 状态完整保存，Parts 保留原始 UTF16 范围与 newline 拼接内容。sourceUnits 中只有原 seed 有 rerank 分数；附带成员不取得新 origin 身份或推断适用关系。

独立只读审计发现初版源单元门控有两类问题：非 clause seed 的成员可能同时持有另一个块，Java 错误把这个额外块扩为新来源；另一个问题是未重算结构单元 ID，并容许可靠单元被任意改为 unknown 或跨 clause 拼成 complete。新增纯 JSON `OerCanonicalSourceUnits` 对照 Python `SourceUnits.resolve` 的观察语义，按完整当前 corpus 重建预期 kind／status／reason／blocks／members／ranges 和 unitId。unknown 原因与已观察结构均比较，不凭格式正确的 SHA 接受单元，可靠同条款重复 seed 不能以不同伪造 ID 逃过去重。

## 验证范围与证据

最终复制 checkout 为本机 `.local/state/oer-canonical-client-20261010/run-005424-8bb919`。Maven 编译当前全量生产／测试源码，只执行以下聚焦 suites，未称全库测试通过：

| Suite | 通过／执行 | 验证内容 |
| --- | ---: | --- |
| `OerCanonicalRetrievalClientTest` | 12／12 | Python-authored canonical wire；完整 JSON／UTF16／两页表组／unknown；错误 payload、缺成员、范围、index receipt、source／recipe变化、角色与模块 URL。HTTP 是 Mockito fixture。 |
| `OerCanonicalSourceUnitsTest` | 16／16 | 12 个纯 Python 生成正／unknown cases 的逐字段等价，以及非递归、伪造 unknown／unitId、跨 clause 和重复可靠单元回归。无模型或原件。 |
| `VettingDualStructureHealthTest` | 8／8 | 已有实际 health／selector fixture，显式 v4／dual profile、长短预算、算法身份与 recipe 变化门控。 |

总计 36 项，0 failures、0 errors、0 skipped。证据入口：[validation receipt](../../../.local/state/oer-canonical-client-20261010/run-005424-8bb919/validation-receipt-attempt1.json) 与 [Maven log](../../../.local/state/oer-canonical-client-20261010/run-005424-8bb919/maven-test-attempt1.log)。收据绑定复制代码、测试、两个匿名 fixture 及 Surefire XML 的 SHA。代码：[客户端](../../src/main/java/com/consense/service/vetting/OerCanonicalRetrievalClient.java)、[源单元门控](../../src/main/java/com/consense/service/vetting/OerCanonicalSourceUnits.java)。

保留早期失败：`run-004605-34b5b7` 未指定 classworlds 配置，Maven Launcher 初始化失败；`run-004615-c01fbc` 编译成功但 Surefire fork 对含 `&` 的参数路径展开失败，0 tests，不计通过。后续直接 Java Launcher 加配置、专用 socket tmpdir，并在测试进程采用 `forkCount=0`，避免 batch 重解析。初版 `run-004656-6075ea` 的 20 项通过保留，但其缺陷由独立审计揭示，不能用初版成绩追认最终门控。

修后另外完成独立只读重放：23 个 wire probes 和 10 个生命周期 probes 全部符合预期，编译及两个 runner exit code 均为 0。包含 13 份已保存真实 HTTP responses、anonymous wire、合法非递归 closure、known unknown 与非法 UTF16／伪造 unknown／unitId／跨 clause／重复单元。证据目录为本机 `.local/state/oer-canonical-wire-audit-20261010/repaired`；这些是离线保存响应与行为审计，没有新 HTTP、模型或数据库调用，不能与上面的 36 个 Maven test 叠加成准确率分母。独立审计复核 client SHA `d056a67af34ecc97344c8aa4f50bcb69527f31448fb95b707fae2cb62a588df6`、SourceUnits SHA `8a06e595e079e09402509c1c21e66fe8772e15433beaa3b918ce151db21df2c9`。

## 当前未完成的业务门槛

Java→实际 Python／真实 BGE／原件资产 bridge 已完成一次独立 opt-in 验证，见 [真实 bridge 记录](./oer-real-java-python-bridge-validation-20261010.md)：真实 2 页 GCT 原件经当前 Java native parse、实际 H2/JPA 生成 document ID，再为既有 reviewed page-1 24 chunks 重投影，经 registerReviewed／currentBindings／index／retrieve／pointer／真实原图 bytes 返回。Maven 1 项通过，GCT3 排名第一且完整 12 members，controller standalone MockMvc pointer／asset 均 200。该增量晚于上述 wire／重放记录，原 fixture 结果仍只支持自身契约／兼容结论。

当前业务消费接缝正在单独开发／验收；上述 probe 未调用 `VettingSemanticReview`／`AdviceService` 的真实任务。前端共享证据组件也在单独实现，在线旧版本未切换。H2 document ID 只在本轮 UUID 数据库 scope 内有效；Java controller 通过 MockMvc，未启动新的 Java HTTP 服务。仍须验证实际业务 selected scope、Advisory 自身模块进程／URL、在线数据库迁移和业务页面；不能据此宣布线上接通或全文质量通过。

可靠 native producer、范围内整源精修、全库召回与排名、LLM 答案忠实性和 H800 发布包仍未完成。canonical 质量保持 unknown，`humanConfirmed=false`；不用这些契约测试宣称 OCR 全文准确率或法律判断通过。
