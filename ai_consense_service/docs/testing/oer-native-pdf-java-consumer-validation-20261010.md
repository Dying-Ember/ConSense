# 原生 PDF Java 业务消费者接入与回归

2026-10-10，Asia/Shanghai。遵循 [OER 范围规则](../oer-scope-and-rules.md)。本记录是 [独立声明契约](./oer-native-pdf-java-declaration-contract-20261010.md) 之后的消费者接入阶段；前一记录的源码 SHA 和“尚未接入”状态保持当时事实。

本轮完成 canonical registry 注册／逐读、pointer、Vetting／Advisory model view 和 retrieval 的原生 PDF 专用分支。最终复制 checkout 的 **13 suites／151 tests 全部通过，0 failure／0 error／0 skipped**。这证明测试覆盖的业务接线和既有分支回归；没有执行真实 Python HTTP、BGE／MiniMax、比赛问题、正式报告评分或前端业务验收。

## 实际接线与不降级边界

| 消费位置 | 本轮行为 |
| --- | --- |
| `NativePdfCanonicalContract` | 独立 PDF capability／algorithm／display declarations；剩余 PDF version／typed ref／block／chunk marker 仍触发专用门控，不能删除部分标记后降级。保留 literal／UTF16／原物理页／坐标声明及 unknown；Java 不因此证明实际 glyph membership 或像素正确。 |
| `CanonicalEvidenceService` | 原 UTF8 snapshot 限制 16,777,216 bytes；先在原 wire 上检查 `projectionSha256`，再走 PDF envelope／chunk contract。专用 health 与 projector／transport／observation 源码 SHA 绑定。注册记录真实原件 SHA、Java parsed sourceHash、routing、reference 与 adoption 身份。 |
| Adoption identity | PDF 的 public manifest 把私有 region review 换成 typed ref，其 public manifest field hash 与原 adoption manifest SHA 不相等。PDF 注册使用 `adoptionManifestRef.sha256` 与 `projectionIdentity.adoptionManifestSha256`；DOCX／scanned／collection 原分支保持。误用 public field hash 的负例明确拒绝。 |
| Persisted reads | PDF `readBinding`、pointer／snapshot／pointers 与 direct `chunks(binding,module)` 都冷读 registry，核对 live raw seal／存储 raw bytes SHA／parsed snapshot 一致，复验当前原件与路由。registry 失败或来源变化不能返回旧存储内容。实际 CAS 读取及重建仍由 Python 每读门控执行。 |
| Pointer／DTO | 来源名读取实际 `source.filename`，保留真实 PDF 物理页、parts、typed refs、source spans、专用 bboxConvention／geometryStatus、capture completeness／未采用 glyph 与 unknown 声明。`nativePdfEvidence` 是可选字段，`@JsonSetter(nulls=Nulls.SKIP)` 保证旧 branch 的 Java null 在序列化往返后仍为 null。 |
| `OerBusinessEvidence`／`VettingSourceMaterial` | pointer display 必须与当前 snapshot 相等；PDF schema／producer／storage／geometry／bbox 与真实 page label、parts 一致。专用结构与 unknown 原样进入材料；不得升级 `humanConfirmed`、effective rendering 或语义完整性。既有 scanned／DOCX／whole table gate 保持。 |
| `OerCanonicalRetrievalClient` | PDF corpus 额外调用 `backend.snapshot` 冷验，不只相信存储声明；index／status／retrieve 核对应 capability 与 algorithms，live proof 或 recipe 变化使 receipt 失效。payload 与完整 Python source unit 仍须等于 actual published parents，canonical 失败无 legacy index 回退。 |
| `AdviceService` | 原文摘录携带 PDF position／textChannel／refs 与 display declarations，明确 declared geometry、有效可见性与 whole-source semantics unknown，以及 visual unavailable。DOCX 摘录分支保持。 |

本版 PDF transport 不发布图像／表格／appearance text；内部 source raster descriptor 不能自动打开未发布 asset 路由。bbox 是 source-review region 与声明的视觉变换，不是已经验证的 exact glyph extent。没有把代码契约通过升级为 OCR／结构／法律质量通过。

## Actual producer fixture 与 Python／Java source unit 互验

没有手改 public JSON 后重新 seal。将最终 authored PDF 的实际 CAS 复制到独立 store，只把实际 adoption 的 documentId 改为测试身份 `42`，调用冻结 Python `produce_native_pdf`／`publish_native_pdf_transport`，再完整 cold hydrate 并核对实际 internal。原 CAS、前一声明 fixture 和比赛原件保持只读。

该 PDF 是人工构造的两页测试文件，经真实 Java PDFBox observation 生成；双路 layout 输入明确是 synthetic parser outputs，没有 neural OCR 实跑。`42` 只是隔离 fixture 身份，不是线上 DB ID。资源来源说明见 `src/test/resources/oer/native-pdf-contract/business-fixture-provenance.json`；negative cases 是明确变异，不能当 actual CAS producer acceptance。

| 工件 | bytes／SHA-256 |
| --- | --- |
| `authored-business-public-v1.json` | 43,078；`f2e7455b6cd84b08a11fccc85e66dc9ea2f0335444f382c57b2c4c2de41dc068` |
| 原 authored PDF | 2,744；`da26cdde7e7e53bf7ad25891e699f2d31e57bfa2252a3e1a3b05a1915a6764ab` |
| 实际 internal JSON，留在 ignored CAS run | 217,833；`27568303e0ab3be474e0d4d246dda2dd7cdb8685a1f91b16c2562031edd2ee1d` |
| Python `SourceUnits.resolve` 输出 | 24,373；`b8fee1bde1a45e720f8bbeeafbd61cb9825313c24f423d3e8752d538b957efdb`；Java resolver 完全相等，保持 `complete_observed_unit`／`semanticScopeVerified=false`／`qualifiersComplete=unknown` |
| Public projection SHA | `20c154be8b8d37731c455fecf37e2572bb0ca19495182e23f6cac12b9f278782` |
| 实际 adoption manifest SHA | `b2b4b835454adae65c714acdaac0f83855497dfa55150d61aa192dc1b48e0fef` |
| Python projector／transport | `bf75da59df2c696cd56b7628b104d89ea12f57c783bfd5e9a4cc06f4de991416`／`60c969f2e11b490b7618f973c592c584a62b971046298daa403910c048c05d23` |
| Python source unit selector | `a528457827bc655e4980bbf9187251a16b94c481c98971e87f99db6157443104` |

独立导出目录 `.local/state/oer-native-pdf-java-consumer-20261010/business-fixture-052637-396076/`，`export-receipt.json` SHA 为 `a6303930d042687d98ab968186d475ab1e191ae771af895b69da42d209a87f3e`。源码、原件、实际 CAS 与 wire 导出层次分开；Java 正例使用实际 public bytes 和 PDF，而 registry、neural scoring／LLM 仍为明确测试替身。

## 最终复制 checkout 与逐项分母

最终 run：`.local/state/oer-native-pdf-java-consumer-20261010/run-053955-5edb3c/`。完整 `pom.xml`／`src` 复制后运行 JDK 17.0.20.1+1、Maven 3.9.11、`forkCount=0`；项目编译 target 保持 Java 8。该 run 的 14 个本任务源码／test／资源工作区与复制 SHA 全部相同。新增 provenance 为测试完成后的说明文件，单独核验资源 bytes／SHA；不冒称它在原测试 run 中被读取。

| Suite | tests | failure／error／skip |
| --- | ---: | --- |
| `CanonicalNativePdfEvidenceServiceTest` | 10 | 0／0／0 |
| `OerNativePdfBusinessEvidenceTest` | 8 | 0／0／0 |
| `OerNativePdfRetrievalClientTest` | 5 | 0／0／0 |
| `NativePdfCanonicalContractTest` | 25 | 0／0／0 |
| `CanonicalEvidenceServiceTest` | 19 | 0／0／0 |
| `CanonicalNativeEvidenceServiceTest` | 8 | 0／0／0 |
| `NativeDocumentCollectionContractTest` | 6 | 0／0／0 |
| `CanonicalCollectionBackendTest` | 3 | 0／0／0 |
| `OerBusinessEvidenceTest` | 11 | 0／0／0 |
| `OerCanonicalRetrievalClientTest` | 17 | 0／0／0 |
| `OerCanonicalSourceUnitsTest` | 16 | 0／0／0 |
| `CanonicalFreshTransactionTest` | 9 | 0／0／0 |
| `AdviceCanonicalIntegrationTest` | 14 | 0／0／0 |
| **合计** | **151** | **0／0／0** |

分母来自每个 `target/surefire-reports/TEST-*.xml`；receipt 保存逐 suite XML SHA 与 JVM arguments，Maven log 总时间 **34.360 秒**。receipt SHA `f23c00f3e69441a49b3371f708735934a3ea3a295e99fc15d4ee24e0378dfeff`，log SHA `47b8743ad1dda60c2d5e14e97e61d1b64d44723db1f5184a05ab370b349ebb92`。这些 tests 与前一 25 项／root 63 项有重叠，不累加成绩。

本任务最终产品源码 SHA：

| 文件 | SHA-256 |
| --- | --- |
| `NativePdfCanonicalContract.java` | `cae03d772782ff6e5fbad45fc389b1d06d3793911d743ac26c39f4f896cc250b` |
| `CanonicalEvidenceService.java` | `8820cfc8edc4dfd27bd66c0080bd84a62af8f4000c42c58cddadf69761ad64b1` |
| `OerBusinessEvidence.java` | `ed37c1ab519f9855fea26b7e6356de392d1b09cac74af9924df78d30fb8092a5` |
| `OerCanonicalRetrievalClient.java` | `ac0c7558b85d1eddb4f2f61006e60f9052b2eda44cf982241e686312420fad34` |
| `VettingSourceMaterial.java` | `5d5a74a98bc69fc7c2d7c43784c1dad4836e4add60b39c25ce1f5d5548ddce69` |
| `AdviceService.java` | `66122cd17ef2416a75a7c4358ea3d7e5a96ea73e66d902c4a5cf1a8bf64f158b` |
| `CanonicalEvidenceDtos.java` | `cd81dc5abb76bca04613cbfd6d0ab86308eaecc5d112e9987bedf2d33d2bb287` |

## 保留的失败与最小修复

首次复制 run `.local/state/oer-native-pdf-java-consumer-20261010/run-053609-150c46/` 为 151 tests／0 failure／49 errors／0 skipped。49 errors 都是本地 HttpServer setup 抛出 `Unable to establish loopback connection`，cause 为 `SocketException: Invalid argument: connect`；新 PDF suite 10 项和既有 HTTP suites 均尚未进入业务断言。非 HTTP suites 的 102 项通过，不称整轮通过。失败 receipt SHA `4d5a7e3bcf68784edb691dd47f25ddaa77a4ceef2d0dae854dda747b77254ce5`，log SHA `0673248e5776bf2e5f1fe48a84c5668b064baebc1319b22242e91672811142c2`。

对照已通过的 root runner，失败 runner 没有设置 `jdk.net.unixdomain.tmpdir`。只在 ignored launcher 创建独立 `jdk-sockets` 目录并传对应绝对 JVM property，fresh copied run 的同一 151 项全部通过；没有改业务源码／HTTP断言／SocketImpl，没有重启其他服务。已证实这项 runner 配置能消除本轮环境症状；没有额外追认默认目录失败的内核原因或路径长度原因。

更早 root 的 63 项接线测试出现 4 个旧 Chunk equality 失败，是新增可选 `nativePdfEvidence` 把 null 反序列化为 Jackson NullNode。最小修复只添加 null-skip，不放宽 equality；本轮 8 项新 PDF business suite 中包含 scanned／DOCX 往返回归。root 的旧失败与修复收据见其 [metadata wire 记录](./oer-vetting-source-metadata-sharing-20261010.md) 及 `.local/state/oer-source-metadata-wire-20261010/run-final-053116-ce4ac3/` 实际 evidence。

## 仍待真实验收

本轮未部署、未使用在线 `ai_consense_service/target`、未修改在线数据库／8080／5173／8092、未使用 GPU／其他 WSL，也未恢复 Bonsai。实际原件业务闭包须在独立持久项目中绑定真实 DB ID、module routing／policy 和 reference adoption，登记真实 producer 输出，核对 Java HTTP→对应 Python registry→真实 BGE／MiniMax→回答／报告与原件位置。

实际 PDF 与原生 DOCX collection 目前由不同 registry 入口负责；共同项目使用多个类别来源的 registry 配置／整源身份仍须明确，不能把任一 fixture 分支通过写成完整混合源已上线。官方八题、正式 Vetting 结构化报告、更多原页／完整表组、参数消融／训练导出重载、发布包及 H800 均按各自证据验收。
