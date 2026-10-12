# 原生 DOCX 完整单元分批：Java 消费与真实窄桥验证

2026-10-10，Asia/Shanghai。本记录覆盖新的 document-root 协议、Java 接入、复制 checkout 契约测试，以及 Java 对隔离 Python/CAS/BGE 的真实 HTTP 调用。当前样本是明确标识的 authored OOXML 协议样本，不是比赛原件，也不构成 PRE 全文采用、官方八题、正式 Vetting 报告或前端验收。

## 采用原因和身份边界

完整 PRE 原生 public 的 Parts＋topology 必需字段下界为 139,376,543 bytes，已超过单 snapshot 的 16,777,216-byte 上限。完整原表组不能为了传输被拆开，blank／nested 也不能删去。新的 Python `native_projection_collection.py` 保存一个有序 document root 与若干完整采用单元 batches；整份原生 public 只在内部内存还原，实际证明及 header 保留在 CAS。

Java 保持一个 document 对应一份最新 binding。`CanonicalSourceBinding.snapshotJson` 保存有封印的 root，未塞入完整 chunks、未伪装成旧 v2 snapshot，也没有将 batch 当作另外的文件。

- `schema=consense.native-document.v1`，`version=whole-native-units-ordered-cas-document-v1`。
- `projectionSha256` 是 root 的注册／业务地址；`publicProjectionSha256` 是原完整 public 的封印，二者分别保留。
- `documentId`、原件 SHA、`sourceHash`、`projectionRevisionSha256` 在所有 batches 中保持该完整来源的同一身份。
- root 的 `manifestSha256` 仍绑定完整 adoption manifest。`manifestMetadata` 保留源、角色、模块、范围、quality 及 reference；它不是完整 manifest，因此注册不对它计算一个假 manifest hash。
- `batchPolicy` 规定完整采用单元边界、原顺序及整单元超限拒绝。root、每个 batch 的实际字节上限均为 16 MiB，配置或调用不能抬高这个上限。

完整 PRE 的上述下界是历史实际原件容量证据；本次三个单元的 authored 分批通过不证明完整 PRE 已采用或全表最终封包均可容纳。

## Java 接缝

新增 `NativeDocumentCollectionContract` 验 root seal、全局身份、metadata/ref、范围及 unknown 状态、固定分批 policy、连续 ordinal／firstUnitOrdinal、每批完整 `unitIds`、全源 unit 覆盖及顺序 hash。读取 batch 后另外核实际 byteCount／SHA、batch seal、同一来源／revision、原有 native chunk 契约、完整表 topology／blank／nested 以及每 unit 原样 unknown 记录。

`CanonicalEvidenceBackend` 只访问固定的 `/canonical/{root}/batches/{ordinal}?module=...`。不接受 CAS 任意路径；root 和 batch 的 HTTP 读取均执行硬 16 MiB 上限并拒绝 redirect。普通 snapshot／扫描消费仍走原入口。

`CanonicalEvidenceService`：

1. `registerReviewed` 在旧 native capability 之外检查独立 `nativeDocumentCollection` capability 及三项新算法 SHA，逐批验证后保存一份 root binding。
2. 持久来源读取重新请求实际 registry root；该 Python 入口每次重验完整实际 CAS。source/catalog/adoption freshness 继续用既有 fresh reader。
3. `chunks(binding,module)` 在 DB 原 root 保持不变的情况下，返回完整、原序的内存父 chunks，并在读取前后检查当前来源。
4. `pointer(unitId)` 用 root 的 `unitIds` 找到实际所在完整 batch，检查它后只返回该完整 unit。返回地址继续是 root SHA，显示契约继续声明原生 `consense.canonical-projection.v2`，保留真实 part/path/channel/UTF16/topology 和该 unit 的实际 unknown，未制造 PDF 页／bbox／原图。
5. `pointers` 逐批返回所有完整 unit，未将 root 或 batch 当 chunk。

`OerBusinessEvidence.load` 消费 collection 的完整父集合，再按 pointer 数量及实际 payload 校验建立 Session。Session 的根 binding fingerprint、当前 selected scope 复验和改版失效规则继续有效。

`OerCanonicalRetrievalClient` 消费实际 root＋完整 batches。`/index-canonical` 仍只发送有序 root 地址，因此请求不会携带整份 PRE JSON。index/retrieve fingerprint 绑定 root；返回 hit payload 和完整 source-unit closure 必须与内存原始父集合逐字段一致。保留既有三参数 direct-fixture constructor，生产使用注入 bounded backend 的四参数 constructor。

新 capability 独立声明 `schema`／`batchSchema`、固定 producer/batch endpoints、`one-document-root`、`maximumResponseBytes=16777216`、`whole-adopted-unit-source-order` 及 `actual-cas-every-read`。新增算法键为 `native_projection_collection.py`、`native_collection_registry.py`、`oer_native_collection_server.py`；旧 `NativeCanonicalContract` 常量和六项算法未改。

## 固定 fixture 和代码验证

实际 Python fixture 位于忽略目录 `.local/state/oer-native-document-collection-20261010/authored-031512-33c6d4/`，对应 Java resources：`authored-native-collection-root-v1.json` 与 batch 0／1／2。由实际 Java observer 对 authored DOCX 的输出及真实 CAS 发布，不是手写 Java snapshot。

| fixture 项 | 本次固定结果 | 证据 |
| --- | --- | --- |
| root bytes | 18,024 | `native-document-root.json`／同目录 `fixture-receipt.json` |
| root bytes SHA | `7b4f58c8539b0dc1e8ba5cead57943cca62cd9d2d533f967e7782c6625c153ef` | Java `NativeDocumentCollectionFixture.ROOT_BYTES_SHA` 逐读核对 |
| root projection seal | `f04f569b311552dfd2b89dee01be0f487ae6b99764fb57327e7d97a97279a990` | root 原字节与 Java seal 验证 |
| batches | 10,405／57,603／9,114 bytes | batch descriptors 及实际资源字节 |
| complete parents | 3；整表单元含 5 paragraphs、2 tables，保留 3 个 blank paragraphs | fixture chunks／Java contract 和 service tests |

最终统一复制 checkout：

`.local/state/oer-native-collection-java-20261010/run-032236-b4f88e/validation-receipt-attempt1.json`

SHA-256：`d4d94f72d04f5fd118a5d63dbe1689d7b4349583e45ab76a3be711d22bcffeed`。

12 suites／118 tests，0 failures／errors／skips，exit 0；receipt 的 `sourceManifest` 中 workspace／copy drift 为 0。既有 native/scanned、fresh transaction、binding persistence、Advisory/Vetting 集成 double 回归与新 collection 门控一并执行。相同 run 内 16 项新增 tests：contract 6、bounded backend 3、native service 3、retrieval client 3、business Session 1；其中不少场景含多项 hostile variants。这些测试数不能与先前重叠的 72／94／118 receipts 累加。

验证覆盖缺批／截断／byte tamper／换批、source 或 revision 换绑、重复／重排／漏 unit、表组／blank 保真、unknown 提升拒绝、单 unit 只读所在 batch、root-only DB snapshot、新 root 使 Session 失效、capability／算法缺失、固定 HTTP 路由、redirect 及配置抬高 cap 被拒。服务测试使用真实 loopback HTTP stub，神经模型、实际 Python CAS 及生产 DB 均不是这轮代码测试的运行对象。

所有 Maven 编译／测试位于上述复制 checkout；未在在线 `ai_consense_service/target` 编译，未启动或重启在线 Java 服务。

## Java 到真实隔离 Python／BGE

另用最终复制 `target/classes` 写 standalone ignored probe，访问父任务准备的实际隔离服务 `127.0.0.1:8871`。Python 的 code/state 均为其冻结副本，真实 CAS 已发布当前 authored scope root，真实 BGE-M3／BGE-reranker 使用 CPU FP32。本 probe 不改 frozen Python 或 CAS。

运行入口和证明：

- `.local/state/oer-native-collection-java-20261010/java-http-live-032410/NativeCollectionLiveProbe.java`；源 SHA `b78a3bd8a7b7b31e119b0e99f0e9e8300d69563b898acc20206130df9b85dcd5`。
- 同目录 `receipt.json`；SHA `fc0b873ce3a453c04c5a6e6354ece260404a1738534d02241a6db25eafe7e20e`。
- 对应 Python runtime manifest：`.local/state/oer-native-collection-runtime-20261010/run-031923-628757/scope-retest-032059-148486/runtime-manifest.json`。

实际 Java GET root＋三批，通过字节 SHA／size／seal／完整顺序／unknown 校验，并且 root 与当前 runtime manifest 逐字段一致。已有实际 BGE response 的两个 hit payload 与 Java 收集到的三个父单元完全一致，Java `OerCanonicalSourceUnits.validate` 对完整 observed closure 通过。

本 probe 随后用新 authored index key 执行实际 `OerCanonicalRetrievalClient.index`＋`retrieve`，真实返回两个 hits，原父 payload 和完整 source-unit 由 client 验证。该 key 是 `authored-java-native-collection-e805d11e-392b-4142-91a4-451308ddb6da`，只写独立测试 retrieval state。in-memory protocol binding 明确不是实际 DB/project adoption。

| 真实窄桥项 | 实际结果 | 证据字段 |
| --- | --- | --- |
| current root address | `3c27743c54d34d52f8c6a318a1d7301106b66c0d4fafbe04305ab0f041a0a489` | live receipt `rootProjectionSha256`／actual-java-root.json |
| 对应完整 public seal | `e6aae41bd1e971b2cc5d09c114b0599925fabdabb18947fbc613f0fc5386dc0c` | live receipt `publicProjectionSha256` |
| 完整父／批数 | 3／3 | `completeParentCount`／`batchCount`，actual-java-batch-0..2.json |
| Java 初次 GET／校验及旧 actual response 核对 | 0.6293828 s | `initialReadSeconds` |
| 实际 client index | 0.7184679 s | `actualJavaClientIndexSeconds`，真实 Python request journal |
| 实际 client retrieve | 0.7841215 s，2 hits | `actualJavaClientRetrieveSeconds`／`actualJavaClientHitCount`／actual-java-client-retrieve.json |

这些是模型已热加载、极小 authored 数据上的本机开发时间。它们不表示 PRE、整库或 H800 性能。新 scope root/public seal 与静态 fixture 不同，来自明确的实际 scope-policy 重投影；原件及 source reference、采用 units 和 quality 保持 authored 身份，没有改成比赛事实。

live receipt 明写 `actualSourceDbRegistration=false`、`actualLLMCall=false`、`officialQuestionsTested=false`、`formalVettingReportTested=false`、`frontendAccepted=false`、`qualityCertified=false`。

## 尚待完成的容量／业务边界

- 分批 root 解决 bounded snapshot 传输，不等于全部比赛来源已有完整参考及 adoption。PRE 全源仍须真实 source review／采用，最大完整表组若加 envelope 后超 cap 仍应拒绝，不能拆表。
- Java／Python 现阶段仍在内存收集完整父集合。Python 每次 root／batch 请求冷核完整 CAS；更多 batches 会重复验证大源，需真实 PRE 资源与时间测量后再决定有效且来源绑定的优化。当前没有验证缓存。
- `/retrieve` 仍一次返回 hits 的完整 payload 及 `sourceUnits.members`；它没有采用 root/batch response 协议，`HttpSupport` 也没有专门的这类响应 bytes cap。多个大单元可能造成大响应及重复成员，这是下一接缝，不能称已由 snapshot 分批解决。
- 前端只读完整所需 unit 的 pointer，保留真实 native 位置并明确视觉 rendition unavailable；尚未对 1,186-paragraph 等大完整单元的 HTTP/DOM 开销、真实 Java business API、数据库保存或 native 浏览器验收作证明。
- LLM 上下文、Vetting request-pack 预算和完整结构关联的业务准入仍需大原件验证。真实窄桥的 authored BGE 成功不证明官方答案或报告正确。
- DOCX 原生与 verified rendition 的映射、范围内全部 PDF/SL 质量、Q10/Q11 完整浏览器、官方八题及正式 Vetting 报告评分、必要训练及 Linux/H800 冻结部署继续为开放工作。

当前 governing rules 仍来自 `docs/oer-scope-and-rules.md` 和 `docs/HANDOFF-ocr-embedding-rerank-20261008.md`；本记录不扩大比赛范围，也不改变 standard-template Drafting-only 或评价答案隔离规则。
