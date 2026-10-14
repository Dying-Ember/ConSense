# 原生 PDF public 声明契约与 Java 聚焦验证

2026-10-10，Asia/Shanghai。范围依据 [OER 规则](../oer-scope-and-rules.md)，实施状态由 [handoff](../HANDOFF-ocr-embedding-rerank-20261008.md) 维护。本记录仅涉及新增的独立 Java 契约、测试和 fixture；没有接入业务消费者、没有部署服务或调用真实模型。

## 本次完成与接口

新增 `src/main/java/com/consense/service/sources/NativePdfCanonicalContract.java`，对应真实 Python `native_pdf_projection_transport.py` 的 public wire，而非复用属于 DOCX 的 `NativeCanonicalContract`。

- `isNativePdf(JsonNode)`：任一原生 PDF schema、producer、版本、storage、typed ref 或原生 PDF position marker 都触发专用准入。部分 marker 被删除后，剩余 marker 仍不能允许它降级到 DOCX／扫描路线；完整合法性由下列 validation 判断。
- `validateEnvelope(JsonNode)`：验证 public 身份、manifest、typed CAS 描述、整体采用范围／完整单元、声明关系及 unknown 保留。
- `validateChunk(JsonNode, String sourceSha256)`：验证独立 chunk 的 literal assembly、UTF16、source spans、位置、typed pointers、原页 frame 与两路 layout 声明。
- `validateChunk(JsonNode, JsonNode envelope)`：再绑定 source／revision／internal descriptor／raw observation／projection identity 与当前 envelope。

任何不合法声明抛出 `CanonicalEvidenceException`，状态 `BAD_GATEWAY`，错误码为 `CANONICAL_NATIVE_PDF_<类别>_INVALID`。类无文件、网络、registry、数据库或服务状态写入。

## 验证内容与实际边界

| 检查 | 当前能力与边界 |
| --- | --- |
| 原文件身份 | PDF media type、source bytes SHA／revision、原物理页总数和选页声明、observer 源码及 projection dependency SHA 声明一致。Java 本类不读取原 PDF 或实际 CAS。 |
| Literal／UTF16 | public `content` 必须逐字等于有序完整 `parts.text` 加明确换行；起止使用 Java UTF16 长度；`sourceSpans` 与 part 的位置和 typed span ref 完全一致。不进行拼词、规范化或 OCR 替换。 |
| Typed CAS refs | kind、内层 snapshot descriptor、source／revision、payload SHA 格式、固定 chunk／part pointer、exact bytes 或 canonical JSON identity scope 明确；CAS relative path 必须为由 SHA 推导的固定路径。不会把 descriptor 存在当作实际 bytes 已读验。零长度 AP stream 描述允许合法零 byteCount。 |
| 原物理页与 bbox | 保留真实 PDF 物理页、source-review anchor、原 raster SHA、规范化 bbox、original pixel bbox 与 polygon 的声明关系；原 PDF MediaBox／CropBox／rotation／UserUnit 的派生矩阵重新计算核对。派生矩阵继续标为未作 raster 验证，不声称 exact glyph extent。 |
| 双路 layout | 保留 MinerU 和 Paddle 两个 family、一个选中 layout run、whole block／候选文字 SHA 与 raw provenance；layout target frame、bbox、polygon、声明 overlap、原 raster／input／transform SHA 绑定。`same_original_raster` 要求实际声明 SHA 相同，不能用相同尺寸代替；affine 仍为 source-reviewed 声明。 |
| 整体采用与关系 | commitment、manifest 与 chunk 单元数／顺序一致；完整 region 与 clause heading 保留；relation 的 region 必须归于声明 unit；continuation／qualifier 必须闭合在同一 whole unit；合法 parent／sidebar 等跨单元关系保留。 |
| 质量与发布能力 | 保持 `semanticQuality=unknown`、`semanticScopeVerified=false`、`humanConfirmed=false`，effective rendering／reading order 等不升级。public 不内联 glyph membership／native span inventory／AP 文本；图像发布仍 unavailable。`nativeTableMetadataVersion` 精确要求 `native-pdf-no-table-adoption-v1`，不表示支持表格采用。 |
| 比赛与模块范围 | 排除 BQ／SOR／General Summary、工程技术 PSS、SL 非技术内容和答案；标准模板仅 Drafting；项目实际合约保持项目资料身份。本版 prose seam 对整库 SL 身份明确拒绝，不能把暂未实现的 SL scope 适配当作比赛范围删除。 |

本类验证的是 **public 声明**。它无法从不内联的 glyph membership ref 中独立发现隐藏／透明／outcrop glyph，也不复现 PDF operator、字体或像素。已实现的 Python cold CAS producer／transport gate 必须读取完整原件、observation、参考标注、adoption、双路 capture 与源码 bytes，并重建实际内部／public 工件；Java 声明通过不能取代该门控。

协调修改 text、spans 和 SHA 声明可能通过声明层；其实际 CAS 和 seal 仍须由 Python 拒绝。当前测试中的同步 UTF16、零 byteCount 和 RFC6901 `~0`／`~1` 变异仅确认声明语法兼容，不代表变异工件能通过 Python 实际 bytes 验证。

未来调用者仍须在原始 response bytes 上维持 **16,777,216 bytes** 上限及适用 raw seal；本类不通过 `JsonNode` 重序列化假定重现 Python 浮点 JSON bytes。本次没有更改 `CanonicalEvidenceService`、`OerBusinessEvidence`、DTO、现有 `NativeCanonicalContract` 或该 cap。

## 实际 fixture 来源

测试资源 `src/test/resources/oer/native-pdf-contract/authored-native-pdf-public-v1.json` 从以下实际输出直接复制，未重序列化：

`.local/state/native-pdf-transport-20261010/run-045921-fa6df2/source-identity-compat-051410-9dcbec/authored-public.json`

它是 authored 两页 PDF 的实际 Java PDFBox observation 经实际 Python source-reviewed projector／transport 生成的 public JSON。双路 layout 输入为明确声明的 synthetic parser outputs，**没有运行 neural OCR 模型**；它不是比赛原件、赛方答案或真实法律任务成功证据。来源属性另存 `src/test/resources/oer/native-pdf-contract/fixture-provenance.json`。

| 工件 | 来源／字节证据 |
| --- | --- |
| Public fixture | 43,123 bytes；raw bytes SHA `18b927d8c32d2ba8bca38b47be00ddf8b3b4fe2b8def59000251e46febbeacd6`。测试首先核对 byteCount／SHA，然后完整 validation，并核对输入 JSON 未发生修改。 |
| 原 authored PDF | SHA `da26cdde7e7e53bf7ad25891e699f2d31e57bfa2252a3e1a3b05a1915a6764ab`。本次 Java 契约测试不重新读取或改动原件。 |
| Python projector | SHA `bf75da59df2c696cd56b7628b104d89ea12f57c783bfd5e9a4cc06f4de991416`，含明确 no-table metadata seam。 |
| Python transport | SHA `60c969f2e11b490b7618f973c592c584a62b971046298daa403910c048c05d23`。 |

所有 negative cases 都是对该 fixture 的明确局部变异，用于检查声明拒绝；没有手拼比赛工件冒称实际 producer 输出，也没有把 mutation fixture 重新发布到 CAS。

## 复制 checkout 验证与冻结身份

最终验证目录：

`.local/state/oer-native-pdf-contract-20261010/run-051747-5497b4/`

Maven 在完整复制的 `src/main` 和单独复制的 focused test／fixture 中运行，使用本地 JDK `17.0.20.1+1`、Maven `3.9.11`、`forkCount=0`，项目 source／target 继续为 Java 8。未使用在线 `ai_consense_service/target`，没有重启 8080／5173／8092、修改数据库、启动 GPU 或恢复 Bonsai。

**25 tests、0 failure、0 error、0 skip**。分母来自 `target/surefire-reports/TEST-com.consense.service.sources.NativePdfCanonicalContractTest.xml`，SHA `2b40224d25828c7e9b8d797f186448fa2679941f119a202e7fc6321c92423107`；测试实际覆盖真实 public pass、DOCX／扫描区分、partial marker、防降级、版本、literal／surrogate UTF16／spans、原页／refs／descriptor、unknown、不支持 AP／表／图发布、派生坐标、两路 layout／raster identity、整体采用／关系闭合、模块范围和源码身份。Maven 日志记录总耗时 15.545 秒，日志 SHA `c5b19c8cb42cb7664d87ca9e03432de3ba372a3a787659e66ab6fb27e2309ec9`。这 25 项不与重复运行或 Python 测试相加。

`validation-receipt.json` SHA：`17f18101b83efb269302ce33f1effd8bc49870b239097ec5becf71f0b8764f33`。收据记录以下四个工作区／复制工件 SHA 相同：

| 文件 | SHA-256 |
| --- | --- |
| `NativePdfCanonicalContract.java` | `28fbf7c2521350f8a49dc6df44e606a8d3ed17ebbccfdf823e20da43d70630ed` |
| `NativePdfCanonicalContractTest.java` | `de86acfe61b937aa2d70ee2f782018ac1a0c20e55d367ba5eb1fac2a95da7295` |
| `authored-native-pdf-public-v1.json` | `18b927d8c32d2ba8bca38b47be00ddf8b3b4fe2b8def59000251e46febbeacd6` |
| `fixture-provenance.json` | `fc106713d6d18abad0c0c222850eb2fdc2f24957123e9e06d018366cfb034a28` |

早期 `run-051309-41c146` 在旧 43,060-byte public SHA `8adabb1beaf02047101b9d213ebefede5e1dd779e0f6ddb8f17614142097e604` 上也通过当时的 25 tests；其收据 SHA `02f7eb8119998b8b98d46f1560c12ad526035bffc8f486bb26e6a01308b5e70a` 保留，但该旧 schema 缺 no-table metadata，不能追认为最终版本或 retrieve 兼容结果。

`run-051737-9857e4` 的首次最终准备在资源目录 Copy-Item 阶段失败，未启动 Maven；目录和 `preparation-failure.json` 保留。随后脚本按文件复制到已建目录，fresh `run-051747-5497b4` 才是最终通过运行。

## 尚未完成

本类尚未接入 Java canonical consumer、实际 HTTP、真实 GCC／GCT／SL 整源或真实 BGE／MiniMax 请求；没有前端验收、官方题目成绩、结构化 Vetting 报告结论、OCR 参数调整、训练或 H800 验收。Python registry／server 的独立 fake HTTP 结果由其专属记录说明，不能追认为本类业务接线通过。后续接入必须继续保留每次实际 CAS 读取和 unknown，并独立报告真实模型、原件质量和前端业务结果。
