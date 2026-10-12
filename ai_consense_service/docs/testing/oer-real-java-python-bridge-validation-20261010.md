# 真实原件 Java → Python canonical bridge 隔离验证

日期：2026-10-10，Asia/Shanghai。按 [当前范围与规则](../oer-scope-and-rules.md)、[实施交接](../HANDOFF-ocr-embedding-rerank-20261008.md) 和修后 [canonical client 验证](./oer-canonical-client-validation-20261010.md) 执行。结果为独立 H2/JPA、真实 GCT 原件、当前 Java native parser、实际 Python 服务及 BGE 模型之间的小样本接通；不表示在线业务、前端、全文质量或平台部署通过。

## 执行范围与身份

所有 probe、复制 checkout、storage 和运行证据位于根目录忽略的 `.local/state/oer-java-python-bridge-20261010/`。生产源码只读，在线 `ai_consense_service/target` 没有作为编译或写入目标；在线 Java、前端、MiniMax 中继、其他 GPU／WSL 服务及 Bonsai 抑制状态没有切换。

本轮复制 checkout：`run-20261009T170318Z-99aa3c`。目录名使用 UTC；实际执行为 10 月 10 日 01:03–01:04（Asia/Shanghai）。生产 main 的 219 个文件复制前后逐文件核 SHA，launcher 最终 `productionSourceChanged=[]`，exit code 为 0。client／closure helper 使用独立审计后的冻结版本：

| 身份 | SHA-256 |
| --- | --- |
| `OerCanonicalRetrievalClient.java` | `d056a67af34ecc97344c8aa4f50bcb69527f31448fb95b707fae2cb62a588df6` |
| `OerCanonicalSourceUnits.java` | `8a06e595e079e09402509c1c21e66fe8772e15433beaa3b918ce151db21df2c9` |
| 原始 `04 - GCT.pdf` 字节 | `ca3944c16890cb5ea7f8b24e2fab613c7ffd621809f7f850529901649e6f15f8` |
| 已 reviewed 的 GCT page-1 reference v2 | `9fe521114c974a071e01d1fc74408c4e860865798f5e62f8f007fc315ee3163a` |
| Source-reviewed page-1 raster | `15b47e88ca835936456c1ff304004b1735edf1171258dec611705df7b1db1f38` |
| 本轮 Java parsed sourceHash | `3353126c37949b8882fd2b8617cd20d9dd0745e82ff312a44e8e6d6633876c0c` |
| 新 projection | `94a4a72534da8665302c73faf14ef8d63f58b8a6e05e97e5f0da7bf745027045` |
| 新 index signature | `c6b1191248b40ab6e29d89102f4fc1a0d87851af75d263134adf5df428b878b4` |

H2 数据库为 UUID 隔离的 `real_java_oer_bridge_137e922b-85b1-40d8-9b3b-a7732a39c4ff`，使用 Hibernate 建表、Flyway disabled。`SourceDocument.id=565527031` 为本轮实际数据库 identity 生成值，binding ID 为 1；它们仅在该数据库 scope 内有效，不能当在线项目 ID。独立 project 为 `java-oer-bridge-137e922b-85b1-40d8-9b3b-a7732a39c4ff`，index key 在其后追加 `-gct1-vetting`。测试事务已回滚，H2 随进程退出；Python 中的新 projection／项目索引是本轮隔离产物。

## 实际链路与结果

1. 从 bundle 的真实 sourcePath 读取完整 2 页 PDF，核原件／reference／原页 raster SHA，逐字节复制到本轮独立 storage。当前 `DocumentParser` 实际解析完整原件；OCR 显式禁用，若调用 OCR recognize 则 probe 直接失败，不伪造第二路。
2. 使用实际 `SourceDocumentRepository` 保存真实 parse text／blocks／coverage／native evidence，调用 `OerSourceMetadata.capture` 保存原件 SHA 与实际 GCT／tender／project_document 路由，再清 EntityManager 从实际 JPA 重新读取。
3. 读取既有完整 reviewed bundle＋adoptionManifest 的原始请求，唯一替换其明确 `documentId` 为实际 H2 ID，再调用真实 `/project`。原 bundle 未改，原 reviewed 请求未改，旧 projection 未覆盖；没有将 reference 的答案文字写入 candidates 或伪造 native dual parser。
4. `CanonicalEvidenceService.registerReviewed` 按原件 SHA、Java parsed revision、source revision、scope policy、manifest 与 reference SHA 注册，再从 `currentBindings` 读取完整 current snapshot。24 个 adopted page-1 chunks／24 个 asset refs 和完整 JSON bytes 保持不变。
5. `OerCanonicalRetrievalClient.index/retrieve` 使用完整 current binding 调用实际 `127.0.0.1:8868`。health 复核为 vetting、signature v4、CPU FP32、短窗口 512／overlap 64／结构 2048；模型为冻结的 `BAAI/bge-m3` 与 `BAAI/bge-reranker-v2-m3`，不是 HTTP fixture。
6. 核完整 ranked payload／sourceUnits closure，再调用 pointer／真实 backend asset，比较完整 bytes。实际 controller 使用 standalone MockMvc 验证路由、序列化和响应 headers；没有启动或切换新的 Java HTTP 服务。

| 结果 | 本轮实测 | 证据 |
| --- | --- | --- |
| Native parse | `PARSED`；2 页、87 blocks、5,033 glyphs、11 form widgets；`ocrUsed=false`；0.804 秒 | `actual-java-native-parse.json` 与 bridge receipt |
| H2/JPA 注册 | 实际生成 document ID；实际固定来源路由；完整 sealed snapshot JSON bytes roundtrip | `actual-jpa-source-document.json`、`actual-java-source-routing.json`、`actual-canonical-snapshot.json` |
| `/project` | 新 document-bound projection；24 chunks／24 assets；0.381 秒 | `http-02-request/response.json`、`actual-project-response.json` |
| 实际 embedding 索引 | 24 chunks → 29 encoded windows／vector points；`fixedCacheHits=0`、`cached=false`；Java 调用耗时 4.782 秒 | `http-05-response.json` 与 runtime index journal |
| 实际检索／结构复评 | query 为 `What happens if the tender booklet is a photocopy rather than the original version?`；候选上限 24、topK 3、role=tender；Java 调用耗时 6.741 秒 | `actual-retrieval-response.json` 与 runtime retrieval journal |
| 排名／closure | GCT3、GCT1、GCT2；GCT3 完整 12 members，GCT1／GCT2 各 5 members | 实际 retrieval response；扩展 members 不当新 rerank origins |
| Pointer／asset | parts／sourceSpans／relations／adoption／quality／unknowns 保留；PNG 544,413 bytes，与原 page raster 逐字节相等；source/projection/ETag 一致 | `actual-evidence-pointer.json`、`actual-returned-original-page.png` |
| 实际 controller harness | pointer 与 asset 均 200，响应字节和 headers 符合原图／来源身份 | `actual-controller-pointer-response.json` 与 probe assertions |
| 最终原件复验 | 原件、独立 storage copy 与 reviewed 输入请求 bytes 未改变 | bridge receipt 与 probe assertions |

这是一个明确 development query 的接通观察；GCT3 rank 1 不构成盲测准确率、法律答案质量或整源接受结论。CPU 单次耗时不推算为 GPU／H800 性能。

## 可复算证据与运行入口

同一复制 checkout 的 Maven 编译生产／测试源码，仅执行 `.local` opt-in `RealCanonicalJavaPythonBridgeTest`：**1 test，0 failures、0 errors、0 skipped，BUILD SUCCESS**。该项不可与匿名 wire／旧响应重放的计数相加为准确率分母。

- [Bridge receipt](../../../.local/state/oer-java-python-bridge-20261010/run-20261009T170318Z-99aa3c/bridge-receipt.json)，SHA `87cd916b9c955268e61d91720137af5a1f7360eb6dca1493af2aef91c13a395b`。
- [Launcher receipt](../../../.local/state/oer-java-python-bridge-20261010/run-20261009T170318Z-99aa3c/launcher-receipt.json)、[Maven 原日志](../../../.local/state/oer-java-python-bridge-20261010/run-20261009T170318Z-99aa3c/real-bridge-maven.log)、[源码复制清单](../../../.local/state/oer-java-python-bridge-20261010/run-20261009T170318Z-99aa3c/source-copy-manifest.json)。
- [Runtime journal 核验](../../../.local/state/oer-java-python-bridge-20261010/run-20261009T170318Z-99aa3c/runtime-evidence-verification.json)，SHA `5543f1f7f9b862d35a32631e94e6de65c03ea6010469ae4641741bb37220f36f`。只读复制并核 request／response／events 的 SHA、byteCount、连续 ordinal；index-canonical 18 events、retrieve 25 events，保留实际 tokenizer、向量 upsert、dense／BM25／RRF、同池结构 rerank 与 closure。
- [Opt-in 入口](../../../.local/state/oer-java-python-bridge-20261010/run-reviewed-bridge.ps1) 要求明确 `-RunRealModels`，使用独立复制 checkout、storage、H2、project 和 index key；不会成为生产默认测试。Maven 使用 JDK 17 直接 launcher、独立 socket tmpdir、`forkCount=0`，避免含 `&` 路径被 batch 重解析。入口及原件／运行证据不随仓库提交。

首轮日志尾部保留 H2 `90121 Database is already closed` 的 shutdown hook 警告；它发生在 assertions 通过、事务回滚及 BUILD SUCCESS 之后。没有为该退出阶段警告重跑模型，也没有覆盖日志为无警告状态。

## 尚未验证的门槛

本轮没有验证在线 MySQL 5.7 的 V14 升级，也没有修改在线数据库。H2 实际 JPA 成功不能代替 MySQL migration；此前 exact V14 SQL／H2 契约结果按自己的证据读取。

仅采用既有 reviewed GCT page-1 的 24 个区域。Java native parse 完整 2 页不使 GCT 第二页被 canonical 采用或语义接受；旧 reference、bundle、候选文本和采用范围保持不变。`semanticQuality=unknown`、`humanConfirmed=false`、`wholeSourceAccepted=false` 保留。SL 三页表组、native canonical producer、整源精修、全库召回／排名和独立留出评价没有在此项验证。

业务消费接缝仍在单独开发／验收；此 probe 未经 `VettingSemanticReview`／`AdviceService` 的实际任务调用，也没有 LLM 生成、引用忠实性或前端真实任务验收。Advisory 需要自身 actual targetModule URL／进程及其 current selected scope，不能把本轮 vetting URL 改名作为通过。当前在线 Java／5173 旧前端没有切换，最终 Linux／H800 包、共驻与推理资源验收仍未完成。
