# Canonical 来源绑定与 Java 资产代理实施记录

日期：2026-10-10，Asia/Shanghai。按 [中央规则](../oer-scope-and-rules.md) 与 [接通审计](./oer-canonical-java-integration-audit-20261010.md) 实施最小接入点。新增持久化、内部来源复核注册、受控 OER 读取、业务资产代理和 pointer DTO；本轮未修改旧 Vetting／Advisory 检索流程、Python server、前端或在线配置，未启动新的在线业务服务。

## 当前可用契约

| 入口 | 行为与限制 |
| --- | --- |
| `CanonicalEvidenceService.registerReviewed(projectId, documentId, ReviewedRegistration)` | **仅内部 service 方法**，没有公共注册／上传／采用 HTTP 端点。显式提供 projection、原件 SHA、Java parsed revision、source revision、实际 scope policy、adoption manifest、来源复核 reference 的 SHA，以及业务 module；从配置的 OER registry GET 已发布 snapshot 后逐项核验并保存。不能提交 parser bundle 或任意 JSON／URL／storagePath。 |
| `snapshot(projectId, documentId, projectionSha256, module)` | 内部读取完整原始 JSON String，复核 stored raw bytes SHA、projection seal 和当前原件／parsed revision／路由。完整 unknown／扩展字段、表格、parts、关系及资产 descriptor 均保留，不转换成旧 `VettingCorpus.Chunk`。 |
| `currentBindings(projectId, module, selectedDocumentIds)` | 按明确选中文档 ID 的入参顺序返回完整 `CanonicalSourceBinding`；每件必须有最新明确注册的 snapshot 并通过当前来源校验。缺失、最新绑定失效或文档重复时失败，不返回部分 corpus、不回退旧采用。供独立 canonical retrieval client 后续使用，本轮没有接入业务服务。 |
| `GET /api/evidence/{projectId}/documents/{documentId}/canonical/{projectionSha256}/units/{unitId}?module=vetting\|advisory\|drafting` | 返回 `{code,message,data}` 的 EvidencePointer，包含实际 document ID、原字节／parsed／source／projection 版本、完整物理页组、parts／sourceSpans／tableGroup／assetRefs／relations／adoption／sourceQuality／unknowns；`sourceFilename/sourceOrder` 从 snapshot／chunk 原字段以 JsonNode 保留，不推断或重编号。显式 `normalized_xyxy_original_page`，不升级未知质量或 `humanConfirmed`。 |
| `GET /api/evidence/{projectId}/documents/{documentId}/canonical/{projectionSha256}/assets/{assetId}?module=...` | 从注册 snapshot 找唯一资产引用，经固定 OER asset 路径取回字节；核验实际 source／projection headers、严格带引号 ETag、mediaType、SHA、byteCount 与 media magic，返回实际字节，附 private no-cache／nosniff。不是用 caption 找相似图。 |

`projectionIdentity.documentId` 和 `manifest.documentId` 必须均等于实际数据库 `SourceDocument.id` 的十进制字符串。不能用“同一原件 SHA”把两个不同上传记录糊成同一个 document，也未实现隐式文档别名；producer 制作 adoption 时应采用实际 ID。

pointer 表示已采用 snapshot 中的资产映射，不声明资产字节永久可用。三页表组如果声明了资产，snapshot 必须包含所有三页及全组 block 的引用；缺任一页的 metadata 会拒绝注册。某页实际资产之后丢失或损坏时，该引用回取失败，不能用另页代替。没有资产的已采用文字单元保持文字单元，不假装图表已可回取。

## 来源与模块门控

当前来源需属于实际 project，保留明确 OER 路由和已解析的原字节 SHA。普通未知 eligibility 不从 snapshot 的声明继承许可。标准模板 category 及已确认标准模板身份只能用于 Drafting；Vetting／Advisory 继续执行 `ProjectDocumentScope` 的排除规则，BQ／SOR／General Summary／非 PRE 工程 PSS／评价答案等不会因新增入口被放行。身份已识别而 quality 未知的状态与权限未知分别处理。

文件 bytes 读取使用配置 storage root 与 `toRealPath`，原件需为其下实际普通文件；每次注册／读取／回取复核原字节 SHA。Java parsed revision 使用既有 `VettingCorpus.sourceHash`，独立保存。路由指纹保存 category、fileKey、显式 role、document scope、parseStatus 与完整 OER identity；变化使旧绑定失败。同名较新上传即使 parse FAILED，也不能让旧 snapshot 继续冒充当前文件。

来源复核 request 必须绑定实际 OER health 的 scope policy 和 canonical／asset contract，以及 snapshot 的 reviewed manifest／reference 身份。该内部 API 保存调用者明确的来源复核决定，不声称代码验证能证明人工真实性、完整语义质量或重新完成原件审核。Python registry 仍负责完整双路／采用／关系验证；Java 独立检查 seal、业务来源绑定和显示资产完整性。可靠原生 producer 尚缺时，不伪造双解析来通过注册。

## 受控配置与持久化

[CanonicalEvidenceProperties](../../src/main/java/com/consense/config/CanonicalEvidenceProperties.java) 使用前缀 `consense.oer-evidence`，`base-url` 默认空；未配置时实际调用返回 `OER_EVIDENCE_CONFIG_INVALID`，不自动回退到旧 retrieval URL。配置属于 operator 的服务配置，HTTP 请求不能指定后端地址。

```yaml
consense:
  oer-evidence:
    base-url: http://127.0.0.1:8868
    timeout-ms: 30000
    max-snapshot-bytes: 16777216
    max-asset-bytes: 33554432
    # 后续独立 retrieval client 可按 module 指向匹配进程；本资产代理只用 base-url。
    retrieval-base-urls: {}
```

上述是配置说明，未加入在线配置。base URL 必须是 HTTP(S) origin，根路径，无 userinfo、query 或 fragment；backend 仅拼接 `/health`、`/canonical/{projection}` 和其固定 `/sources/{source}/assets/{asset}`，禁用 redirect，限制响应长度及调用时间。错误资产不会向浏览器透传为成功。

[V14 migration](../../src/main/resources/db/migration/V14__canonical_source_bindings.sql) 为标准 V13 后的 MySQL schema 增加此前仅在 entity 中存在的 `source_sha256/oer_identity_json/native_pdf_evidence_json`，并新增 `canonical_source_binding`。旧 row 的新来源字段保持 NULL，不自动重算、回填或采用。新表保存完整 snapshot LONGTEXT、raw bytes SHA、原件／parsed／路由／projection／scope／采用／reference 身份及 eligibility，project＋document＋projection 唯一；显式 InnoDB／utf8mb4，避免数据库默认字符集破坏中文或 non-BMP 字符。旧 sourceHash 定义不变。

兼容策略：标准 MySQL V13 路径由 Flyway 执行 V14；H2 本地 profile 维持原有 Flyway disabled／Hibernate update 行为。若既有 MySQL 被手工增加过同名列，应先核对实际 schema 与 Flyway history，再对该实例采用明确升级方案；本实现不自动 repair history、不 silently 忽略形状不同的重复列。尚未在实际 MySQL 5.7 运行 V14。验证中使用 populated H2 MySQL-mode 执行**完全相同的 V14 SQL**，再通过真实 JPA 保存、clear persistence context 和读回；该结果不冒称已验证 MySQL 运行升级。

## 本轮验证与原始失败

复制 checkout 验证 **19/19 通过，failures/errors/skipped 均为 0**，来源为 [validation receipt](../../../.local/state/oer-canonical-java-20261010/run-003612-9e8a1b/validation-receipt.json)。18 项 [service／实际 localhost HTTP 测试](../../src/test/java/com/consense/service/sources/CanonicalEvidenceServiceTest.java) 和 1 项 [exact SQL／JPA 持久化测试](../../src/test/java/com/consense/repository/CanonicalSourceBindingPersistenceTest.java)。没有写入在线 target。

验证使用由现有 Python normalizer／projector 产生的 [AUTHORED 三页 wire fixture](../../src/test/resources/oer/canonical/authored-three-page-projection.json)，有实际匿名测试 PNG bytes 和独立源字节；[生成脚本](../../src/test/resources/oer/canonical/generate_fixture.py) 可复算。它不是原始合约、真实 OCR、gold 标注或留出质量成绩。Python seal 与 Java 验证通过；完整 raw snapshot 在服务与实际 JPA 两个边界分别无损读回，数字 token、中文、non-BMP、未知和扩展字段不会经过旧 DTO 丢失。

测试覆盖：来源 project／document 映射错配；未确认 eligibility／模板隔离；原件缺失或 bytes 变化；parsed revision／路由变化；较新失败上传；来源复核预期和 health contract 错配；parser bundle／坏 seal；三页表组第三页 metadata 缺失及第三页 bytes 不可取；错 source／projection／ETag／media／实际 bytes；未知 asset／路径注入、redirect、响应上限；selected corpus 缺文档／重复 ID／最新失效；stored bytes 被改；pointer 与实际 assets HTTP、409 stale 和不存在公共注册端点。

attempt1–3 保留原始失败：SQL／JPA 1 项通过，18 项 HTTP 测试在 `HttpServer.create` 前置阶段因本机 JDK17 `UnixDomainSockets.connect0` 报 `Invalid argument: connect`，尚未执行业务断言。最小探针普通 TCP 成功，指定已存在专用 `jdk.net.unixdomain.tmpdir` 后 Selector 成功；`preferIPv4Stack` 单独无效，mvn.cmd 的含 `&` 路径展开也未可靠带入参数。attempt4 直接用 `java.exe` 的独立参数启动 Maven Launcher 并设置专用 socket tmpdir，19 项完整执行通过；随后为前端补 `sourceFilename/sourceOrder` 及断言，attempt5 仍 19/19 通过；最终 DDL 核对补显式 utf8mb4 后 attempt6 的 exact SQL／JPA＋HTTP 仍 19/19 通过，先前 receipt／reports 已另存。没有改 OS、网络、JDK、在线服务或仓库 pom。

receipt 保存 18 项 owned 源码／SQL／测试／fixture 文件的 SHA 与复制 checkout 的 SHA 比较，全部一致。核心生产字节身份：

| 文件 | SHA-256 |
| --- | --- |
| `CanonicalEvidenceService.java` | `43c8f9b2090ccb5b6a8fd1498c5e3aeea38d17baaf699bd1c777e8c6ea7804b0` |
| `CanonicalEvidenceBackend.java` | `589bdee7d25a6559d933798eb5dc28530a93c391d7fbe67862fa72068cd58cf5` |
| `CanonicalSourceBinding.java` | `ea73c07b12d799b62f808bd247595cc3d86a4bab64275342079e4b999beb0fa8` |
| `CanonicalEvidenceController.java` | `7146aff830f35433b720331a25099857fcb62d53dfcea80e520bd6e8bb4d1df4` |
| `CanonicalEvidenceDtos.java` | `9692e37a2fc82573b7a6625e54cd5a6ad4ab204a3327eecf217476a81f1242e9` |
| `V14__canonical_source_bindings.sql` | `980f8c26d8ccdf7385520b0a86e07f55dca4ee7a2288b46640b5046f91a4895a` |

## 尚未接通的业务边界

本轮新增组件可以提供 source-bound 完整 envelope 和受控资产回取，但没有让 Vetting／Advisory 自动采用新检索 client、生成实际 source review、触发 parser／索引、激活 native producer 或部署新的业务 profile。前端也尚未增加资产 panel。真实原件＋真实 BGE／双路解析的跨语言业务接通，以及内置浏览器验收，仍需独立记录。

首版图表能力仍是 Q10=A：相关文字命中回到正确原件页及全表组／区域，unknown 保留；本组件没有流程箭头理解、未声称自动推断关系，也没有扩大 Q11 的轻量 Advisory 入口。
