# 真实 GCC／项目 SCC：隔离业务来源准备及大字段测量

更新：2026-10-10，Asia/Shanghai。本文记录两份实际原件进入独立持久 Java 项目的来源准备；官方题、MiniMax 回答、Vetting 报告与质量评分由后续独立验收完成。原件、CAS、数据库、运行日志及本机凭据保留在忽略的 `.local/`，不随代码提交。

## 原件与最终身份

最终 run：ConSense 根目录下 `.local/state/oer-official-source-business-bridge-20261010/run-060145-06ba7b/`。项目 ID `official-q16-source-90602e8f-85b8-4e1a-ad61-2625c654115e`，隔离 Java 端口 `127.0.0.1:18083`。这是全新 H2／storage；原有在线项目、数据库、模型、8080／5173／8092 及旧实验服务未替换。

| 来源 | 实际 Java documentId | 原件 SHA-256 | fresh JVM Java parsed hash | 覆盖状态 |
| --- | --- | --- | --- | --- |
| `2a_Standard documents/09_General Conditions of Contract.pdf` | 1 | `d7d74db6d9aee390093b9e22bb30dccdbf3c0316d47635922109422278d161e7` | `f7d1c7ac63b2468a6438a87d64250f91c7de509da8f8293ddfb398bf33cf01d9` | PARTIAL；74 物理页，解析 73 页，OCR 0，确认空白 1；未完成原物理页 `[1]`。不改称整源已解析。 |
| `2b_Tender documents/10 - SCC.docx` | 2 | `8ba80d183f6adf7a11eeb08f75e32c2bffdc3960f969202fcbed357e477f7af3` | `fb862f63c3a0611452748f638ef68b3cf5635fc066abd4bb3a77857b879d7f62` | PARSED；原生 799 个段落／表格／页眉页脚／脚注尾注位置，无推算 Word 物理页。 |

两份文件均通过真实 HTTP `/api/projects`／`/api/vetting/{projectId}/package/upload` 上传。GCC 角色 `standard`／`reference_standard`。SCC 根据已核实际 2b 来源和准确原件 SHA 追加本项目 reviewed identity，角色 `tender`／`project_document`／`project_special_conditions_of_contract`；准确版本 `actual-2b-SCC-local-source-review-20261010-v1`。这不证明普通上传的通用自动分类已经修复，不把实际 2b SCC 当作 2a 标准模板。

`actual-source-id-handoff.json` SHA `8e8b9d138d4e657fe02780b3be8d19b19d8e713b8638448852a7cd69530f84e2` 是最终可信身份交接。不得使用早期 explicit-CLOB 对照 run 的项目 ID，或把 byte receipt SHA 代替 Java parsed hash。

## H2 原场景复验

旧产品同时使用 `@Lob` 和 forced `LONGTEXT`，H2 MySQL mode 实际生成 VARCHAR；巨型原生证据触发 H2 `WriteBuffer.putStringData`／`BufferOverflowException`。最小产品修复、red、58 项回归及 MySQL57 离线 DDL 见 [LOB 故障与验证](./oer-native-evidence-lob-persistence-20261010.md)。本文只增加完整真实原件的场景证据。

最终 fresh copied checkout 已包含修复后的两个实体。上传阶段不执行预先 `ALTER ... CLOB`，使用产品自动生成的 DDL；`upload-receipt.json` 明确 `isolatedH2NativeFieldsExplicitClob=false`。完整上传 1 项 opt-in integration helper 通过，Maven 59.546 秒；`CHECKPOINT SYNC` 成功，测试 JVM／Hikari 正常关闭。随后独立新 JVM 打开同一持久 H2，不重传，完整 export 1 项 helper 通过，Maven 26.896 秒，checkpoint／正常关闭成功。

`actual-persisted-fields-GCC.json` 和 `actual-persisted-fields-SCC.json` 与旧 explicit-CLOB 对照完整字段统计 JSON 逐字相同：六字段保留完整 UTF16 chars、完整流式 UTF8 bytes SHA 及 SQL LENGTH。未截断原生数据。旧失败、旧显式 CLOB 对照、首次 export 的 `String.getBytes` 巨型数组失败均保留；后者仅把 probe 的 byte 计数／SHA 改为 8192 chars 分块写入 UTF8 digest/count sink，没有改变持久字段。

额外只读 SQL metadata 核验覆盖产品全部七个 LOB 列：SourceDocument 五列及 CanonicalSourceBinding 两列均由产品 DDL 生成 `character large object`。`actual-seven-lob-product-generated-schema.json` SHA `d7f6a0410c9038812de70606389a9c6335568b06525ccddf373108e49bee35c3`；该 probe 不执行 ALTER／checkpoint／业务写入。

来源 manifest `source-copy-manifest.json` SHA `f66b6e9d9fb3d3e60b9860693c116a7588b76527551464430098171a4a1818d8`。同一冻结副本打包成功，Maven 4.973 秒；Jar `target/consense-service-1.0.0.jar` SHA `cd7ecf609a14553bf7ff329e4067ee5c237cc7a58165153d43fe3d79f594b7ce`。上传／export 是真实 HTTP 的 Spring 测试 JVM，Jar 常驻读回单独记录，不能把构建成功直接当作运行成功。

## GCC 约 1.49 GB 数据的具体来源

最终 fixed DB 的 `actual-GCC-structure-growth-utf8.json` SHA `745e6255ccb19c8e8b02e068a176e1968a65881edc38854380a1b5aa2d51dbe1`。只读 JDBC CLOB Reader → Jackson token traversal；每个 JSON root 解析结束 offset、SQL LENGTH、完整 UTF8 字节计数相等。没有把 0.993 GB JSON 构建为 JsonNode 树。UTF8 raw substring bytes 使用 65,536 项 offset ring 记录，不把原文或字形数据改写。测量脚本在忽略的 `probe/MeasureActualPdfStructure.java`。

| 持久字段 | UTF16 chars | 完整 UTF8 bytes | 完整 UTF8 SHA-256 |
| --- | ---: | ---: | --- |
| `native_pdf_evidence_json` | 993,216,139 | 993,219,983 | `157465dac4f43dd0923a745202bc7766b07b452f6f1a31003010c8e9dba00592` |
| `structured_content_json` | 497,580,778 | 497,583,661 | `ecbd883563296d14091c62a1ad6537ba2b346895e9a687ccd0b13edf51408184` |
| `text_content` | 318,081 | 319,042 | `ddde867f9b15f2a87270a39c0087dfd283a2d4feada6139d2c6c228e609e0482` |

前两个 JSON 字段共 **1,490,803,644 bytes**，并非文本本身有相同体积。其来源为同一字形元数据在三个位置重复序列化：

| JSON 路径／位置 | 字形对象数 | unique glyph IDs | 字形对象 raw UTF8 bytes，不含数组逗号 | 平均 bytes／glyph |
| --- | ---: | ---: | ---: | ---: |
| Native evidence `page.glyphs[]` | 314,109 | 314,109 | 495,288,550 | 1,576.804708 |
| Native evidence `page.nativeBlocks[].pdfGlyphs[]` | 314,000 | 314,000 | 495,117,082 | 1,576.805994 |
| 单独 structured content `block.pdfGlyphs[]` | 314,000 | 314,000 | 495,117,082 | 1,576.805994 |

74 页下有 3,790 个 native blocks；单独 structured content 也有 3,790 blocks。314,000 个 block glyph IDs 在三个位置均存在；block 两处的 ID 集相等、对象总 bytes 相等。此测量证明 IDs／计数／总 spans 的重复，未单独将每个重复对象的 payload 作逐字段 equality 声明。page glyph 另外 109 个 IDs 未出现在 native block 引用中，仍保留原始证据；不因未参与块正文而删除。

Native evidence 的 `nativeBlocks` 数组共 497,583,735 bytes，独立 structured content 共 497,583,661 bytes；后一份基本重复 native evidence 中的 blocks。每字形的 transformation／PDFBox position／clipping／颜色／字体／visibility unknown／limitations 等完整元数据约 1.58 KB。重复 `limitations` 数组每份约 69.71 MB（page glyph 69,732,198 bytes，两个 block glyph 副本各 69,708,000 bytes），这是 object bytes 的子集，不能再加到总 bytes。

GCC 的 74 个 `formFields` 数组全空，总数组 bytes 148，没有 appearance glyph、AP raw／decoded byte streams。此次膨胀不来自 AP／byteStreams；不要把其他原件可能有 AP bytes 的情况与 GCC 混同。

**容量限制继续存在。** CLOB 修复解决了当前 H2 inline VARCHAR／整数扩容溢出，Java 实体、解析产物和 ORM 仍会把巨大字段载为单个 String；每次来源读取的内存／传输／重复序列化成本尚未解决。不能将这 74 页的真实保存成功追认为 3,174 页 SL 技术库或任意整源容量通过。本轮没有实施 CAS 迁移、去重字段、删减 metadata、放宽 cold-read gate 或修改 parser。

## SCC17 的实际 producer／transport

最终 run 的 `scc-projection-attempt2/` 绑定真实 `documentId="2"`、tender／advisory、eligible vetting+advisory、project purpose/class、实际 SL scope policy SHA；采用已有完整 SCC17 reference／adoption 单元，经冻结 producer／transport 完整 CAS hydration 后 additive publish 到 owned 8873 registry。未手改 public seal。

22 个非空 whole units 保留：1 个 SCC17 标题及 21 个 SCC17.301 正文；18 个 blank／Tab paragraphs 仍保留在完整 raw／reference。原件修改接受状态、法律适用性及 `humanConfirmed=false` 保持未知；native DOCX 无原生物理页／bbox，`visualReturnStatus=unavailable_pending_verified_rendition` 保留。

Public 309,934 bytes，file SHA `2a5dae22b390e9c04648b9620e3977ad9ec7c9d3d01f624a7c6fc77fb0058325`，projection address `88a6396324a4d3f6b7f4ce4362bde5dee4e1e34fa12340657cb3f67a06005f4b`。真实 HTTP `/canonical/{SHA}?module=advisory` cold read 19.479663 秒，响应 byte SHA 与冻结 public 相同；无神经索引／检索或生成。

Registration fields SHA `ae3918ccf299b0d2f8010620718d7821ee16ae6960b03c9d5b6117a22780139a`；producer receipt SHA `4ad14facde68ac60f0f34929ca8eabe5c0631a6454fb2fb15ba5cf792f50ee4a`。第一次 probe 因 health JSON key 错用 `nativeCanonical` 导致 KeyError，失败响应和 receipt 保留；第二次改 probe 为实际 `nativePdfCanonical` key，新 attempt 目录保存成功输出，业务门控没有放宽。

## GCC 实际 ID 重投影与登记第一次失败

最终 run 的 `canonical-gcc-binding-220705-103443/` 用冻结 producer 对实际 fresh documentId 1 重投影，唯一 adoption 变更为 `/documentId`；50 个 whole source units 的原文／顺序／原物理页／anchor／关系保持。实际采用仅涵盖 GCC 原物理页 65–70，不代表整份 GCC 74 页采用完成；role standard、producer module vetting、eligible vetting+advisory 及原 scope/reference 保持。

Public 1,503,684 bytes，file SHA `f0f42df3b3d96ed7a77620b9e5161fbbf5bbd9f1ec59b04122f75e34c09b7b85`，projection address `94e151f2d3e4ebfd4442305181c974c52f1bf5d901f23b9df166dda2585176c6`。原 source revision `4fc695edd06c98062cb556dca830b9c2d417a50a796878f195fae0a41a2d0cd8` 保持；projection revision 为 `ee29c411c7496672eca9ba53d857a75abb693e220327fd2525d664363024a6f0`。Registration fields SHA `e3faf39d1b943eabd42db1feda8845f60fd9280c1dac3f529138f9cd4cdf9fea`；validation receipt SHA `e4594dd64fe3fd7f72e47dd4262f01a1afc6841b1c23c8f89d2a20a8632e33da`。本步骤是真实 producer／完整 cold CAS 验证及 additive registry，不是 Java 业务登记、索引或答案质量。

首次实际 Java 两源登记 helper 失败，Maven 42.394 秒，GCC `registerReviewed` 返回 `OER_EVIDENCE_UPSTREAM_UNAVAILABLE`；checkpoint 成功、JVM 正常关闭、原失败 receipt／Surefire 保留。准备配置已显式 `consense.oer-evidence.timeout-ms=120000`，但原 `CanonicalEvidenceBackend` 只设置 OkHttp `callTimeout`，未设置 `readTimeout`，后者仍使用默认 10 秒。实际同一 GCC endpoint 的对照见 `canonical-actual-read-timeout-comparison.json`；产品 transport 修复／回归和随后重新冻结 Jar 属于下一步，不能把配置了 120 秒称已有 120 秒 socket read。

该实际对照只改变 client read timeout：call 120,000 ms＋默认 read 10,000 ms 于 10.067145 秒抛 `java.net.SocketTimeoutException: Read timed out`；call/read 均 120,000 ms 于 43.909924 秒取得 HTTP200，1,503,684 bytes／SHA 与冻结 GCC public 完全相同。对照 receipt SHA `ec8df078c7d78ffe333fd0ba2f9cfa0eb32212cb4b1a8087a9db2fa82aded515`。这是实际 cold-read transport 的根因隔离，不是改变源数据或模型参数。被客户端超时取消的首次请求仍可能在服务端完成原有冷读，后次耗时不应直接当成独立最优吞吐。

## 第二次登记暴露的实际声明契约差异

产品 read timeout 最小修复通过复制 checkout 9 suites／87 tests 后，复制该 Backend，并将独立 probe 的配置显式设 300,000 ms，再执行实际登记。第二轮越过真实 GCC cold GET 后，在 Java PDF declaration contract 拒绝 `CANONICAL_NATIVE_PDF_LAYOUT_INVALID`；测试 JVM 正常 shutdown，checkpoint 成功。`registration-attempt2-failed-layout/` 保留第二失败 receipt／command／当时 source manifest，对应日志／Surefire 仍保留。

最小只读诊断直接读取真正 producer public（1,503,684 bytes），不重传、不开 DB、不调用模型／索引；实际 public 文件 SHA 前后相同。`actual-GCC-layout-and-relations-contract-direct-diagnosis.json` SHA `75946507b948428c62c9643800812d749d44576ac04a5c3ab179ffd1f576e8de`：

1. 实际 211 个 `visualLayoutParts[].originalGeometry.targetFrame` 保留 `originalPageRasterSha256`；Java 旧 allowlist 只列六个其他 frame 键，拒绝这个原始 raster 身份字段。所有 211 处均等于该 row review 的 reference raster SHA 及 part 原页 reference rendition SHA。没有删除 public 中该键。
2. 仅在内存诊断 clone 中去该键，所有原几何／bbox／位置声明不改，完整 declaration 越过 layout，继而拒绝 `RELATIONS`。实际 110 个关系保留来源 review 附加声明：`sourceReview` 110 处；`basis`、`humanConfirmed=false`、`reviewActor=codex` 各 108 处；`sameParentClause` 2 处。旧 Java strict relation allowlist 也未接这五个已存在的来源元数据键。
3. 内存 clone 再仅去上述 relation 附加 metadata，保留全部 unit/region/type/scope/order/几何及 unknown 状态，完整 declaration 通过。这只用于定位，不是有效 resealed projection；clone 未保存／出版／登记。实际 producer／完整 CAS／public 仍保持原样，后续应接收并核验这些来源声明，不应靠删除它们过检。

产品契约兼容修复与实际原 public 重新直验、第三轮登记属于下一步；本文件不将该实际失败归为模型能力或 OCR 文字错误。

Java 两源登记、Jar 常驻读回及后续模型／题目／Vetting 验收状态分别追加真实收据。当前本文不声明官方题通过、整源采用完整、模型微调或报告质量通过。

## 原始条款 ID 的查询参数接口

GCC 实际第一条 `unitId=GCC/physical-65/16.2` 包含 `/`。原 `/units/{unitId}` 单段路径无法完整接收这个原始身份；不应重写 sealed unit ID，也不应全局放开 encoded slash。新增固定 GET `/units?unitId=...&module=...`，由正常 URL query decode 保留完整 literal，并调用相同 `CanonicalEvidenceService.pointer(projectId, documentId, projectionSha256, unitId, module)`。原单段路径继续保留。

独立复制 checkout 的旧 Controller 正例 red 为404，1 failure／0 error，Maven21.594秒；red receipt `.local/state/oer-canonical-pointer-query-20261010/red-063127-217296/validation-receipt.json` SHA `84681f66c88ca91c7a51bcc176189deed21f9460ee3a653dff9faf0c8ca8f09e`。最小 route 后6项HTTP routing／dispatch通过，另5 suites真实service／fresh transaction／backend gates通过，共55／55、0 failure／error／skip，30.258秒。fixed receipt `fixed-063210-ed6c2f/validation-receipt.json` SHA `b8059a83b59e7bc038ce3d7e9074b1b19e12b90d5019d57a27f409d9c2cbfd85`。六项路由测试明确使用mock service，验证原ID、wrong module403、wrong projection409、必填参数400和原route；不把该dispatch测试冒称真实CAS／业务模型验收。Controller SHA `0a31325384d2a5bfdf403fbd31b7c8dd5dc480582bbdd11e4c6c6626572ae596`。

随后真实来源副本只显式刷新已验证的两实体、Backend、root批准的NativePdfCanonicalContract及上述Controller、对应tests／ignored准备helper。最终Contract main SHA `bb1cd93294d6b0b44ea39534c7e71c6686c95d235397f17f63288eda39f91c2e`；`sameParentClause`继续作为producer尚未定义语义的opaque来源文本，不以未经确认的解释增加全局约束，原闭包、owner、copies、顺序／scope严格校验保持。来源runtime manifest SHA `24599d8aa9fc1b0efd71e7975b880b31cb29c7a322bc39bb1c8b84f66501a860`，上一轮失败manifest保持在失败子目录。

统一owned Python方向改为 canonical＋Advisory `127.0.0.1:8875`，Vetting `127.0.0.1:8874`。8875 final health／two-source原字节cold GET receipt SHA `c74a5c1eea35a70ce70eed373a834e7d76f1ad5fbc03aa1e914deb1e5a6a0570`；native DOCX／native PDF／unified能力通过，实际50＋22完整parents与public bytes未变，models前后未加载，index／query／LLM调用为0。旧8873仍保留，不通过放宽mixed能力门控过检。

## 真实查询通过与 collector 两个比较错误

第三次attempt的实际GCC登记已成功。随后Tomcat GET query完整 `GCC/physical-65/16.2` 返回HTTP200／API code0，162,804bytes，SHA `8d8f930a15a2d73b637a7b94edc1fc4c31994fde822c2fee5645c995559e2426`。逐字段最小诊断仅一处：内部JsonUtils树含 `tableGroup:null`，Spring实际wire省略此可选null属性；全部非空原文、parts／arrays、身份及定位相同。诊断receipt SHA `5d67a05edb3eeac60034524f792fde981f65d329305910009a007b3e32781125`。这是ignored验收collector的比较错误，不是来源或业务pointer漂移。

`register-attempt-063427/`独立保留progress、失败receipt、raw query HTTP、当时source manifest／helper、日志和Surefire；preservation receipt SHA `d72f7445fd3a729f63b4a141d6e157f151e4ea4078a7fc6f277187fb43e2201b`。checkpoint／JVM正常close，实际GCCbinding保留；不重复原件上传或GCC登记。

第一resume仅把collector改为HTTP真实typed往返及非空字段比较；typed equality通过，但第二层直接比较Java `LongNode(1)`与JSON解析的 `IntNode(1)`再次误拒。JSON数值同为1，raw bytes／SHA与第三attempt相同。`resume-attempt-063934-b346d6/`独立保留该失败和checkpoint，不倒改为成功。

最终collector先将expected pointer序列化并按真实JSON重新readTree，再执行typed equality及递归所有非空字段／array逐值检查；只允许原本null字段在wire省略，不允许新增字段／缺失非空字段／数组成员或内容改变。调用已保存raw的cheap replay通过5／5：实际typed与non-null正例、改变documentId、删除unitId、增加未知字段、删除parts成员的四项反例；HTTP／DB／index／生成调用均0。`collector-saved-raw-replay-receipt.json` SHA `3c80938eadcd721e5687e5f85b18067db6ab914f10f2348cb2aaf995d18cfe00`。

后续resume仅对已持久GCC核fresh DB来源和binding各SHA，复用上述原raw／全部pointers证明，跳过重复GCC allPointers／HTTPcoldread；只补未登记SCC。最后仍要求fresh Jar对每个来源各一次真实HTTPpointer完整读回，保留原source／完整cold CAS及门控，没有在产品层缓存或放宽证明。

SCC真实query GET亦HTTP200／API code0，15,301bytes，SHA `438fe7f93d06a832bce4dc9ca45372d3ad18cd869023ccbe5428dc09f07fee2b`；最小完整JSONdiff仅 `bboxConvention`、`nativePdfEvidence`、`tableGroup` 三个原null字段在wire省略，所有非空原字段、引用及arrays相等。诊断receipt SHA `ad3996bc58fea64a7d8e7065bc2fe86e0b0a32a43c62e108d089a711b57f4ea0`。反序列化 `bboxConvention` 缺席时DTO默认PDFframe让POJO `equals`产生差异，因此collector最终不用POJO默认值充当原wire真值。原HTTP不会因此补写Word物理页或PDFbbox，产品及wire保持原样。

两个来源的最终完整raw JSON、所有非空字段／数组与四项反例分别5／5通过；GCCv2 replay SHA `b2e31dd6b1ee11a8c5166e9b79edde6b56559fe42855e75391e30e04e96708a4`，SCCv2 replay SHA `98bc706c45bb895c1cf52ffb10d9bd87889bf1cf387cf98cfd9c1175792be6e3`。后一份在独立SCC失败attempt，保留原始失败，不将后续collector修正倒写为旧业务run通过。

## 已完成的冻结 Jar／持久重开与真实 pointer 门控

最终 `finalize-attempt-064721-e67aec/` 用fresh JVM读取同一DB两份实际来源及两个已存在binding，核对原件／parsed／source revision／scope／adoption／review SHA、完整sealed snapshot及保存的真实HTTP全部非空字段与arrays。没有再注册或上传，不重复之前准备步骤的HTTP／coldCAS。1项helper通过，38.870秒，0新binding、2复用binding，checkpoint／正常shutdown完成。completion receipt SHA `d5aa8b11056aef99cf016eb5df87e2e8e8cb4540c98d72f1465b85a68c4cae2a`；command receipt SHA `cec50c1a38aa364e1850078057a45849b641c24a01830a6b19c1d3d5956a3f0a`。最终source manifest SHA `7c37b5f6bc56024671f13ccef4a51ad72208918e1cd4219943012fdbb04fd613`，原失败目录仍保留。

随后package 5.441秒完成；最终Jar `target/consense-service-1.0.0.jar` SHA `2321add597a844995b35a8a0f9b3fd041c171aae2a5b1e2d36e90228ecd56426`。独立常驻Java PID76688，准确启动UTC `2026-10-09T22:48:25.0578700Z`，ownedJDK17工具路径及Jar command marker逐项核对。18083重启后的source catalog包含原1／2、原parsed hashes且included=true；真实运行 `/api/system/llm-profiles`确认MiniMax-M3／openai／configured=true，safe profile响应SHA `1401ddddf90952415e1785c937487cb2c940490785b9d650407f11cad3153faf`。

freshJar每源各一次真实query pointer GET执行原业务source／完整coldCAS门控，完整data与checkpoint前真实HTTP相等，rawbytes／SHA亦相同：

| 来源 | 状态 | 实际耗时 | raw bytes | raw SHA-256 |
| --- | --- | ---: | ---: | --- |
| GCC／`GCC/physical-65/16.2` | HTTP200、完整pointer相等 | 46.6861215秒 | 162,804 | `8d8f930a15a2d73b637a7b94edc1fc4c31994fde822c2fee5645c995559e2426` |
| SCC／`unit-native-3a160174855c102a2dc5c051b7cbc43f021d9055e2b724aee27b14f482d3ac52` | HTTP200、完整pointer相等 | 17.2664521秒 | 15,301 | `438fe7f93d06a832bce4dc9ca45372d3ad18cd869023ccbe5428dc09f07fee2b` |

约2026-10-10 06:50:23（Asia/Shanghai）形成 `actual-business-ready-receipt.json`，SHA `d0934faa11128987acbb115d7c16244f160bacf08f97ac50bc192c09252365fd`。其中附exact source、configuration、process、pointer及completion artifact ledger；此时间点actual model／index／retrieve／官方题／正式报告评分／答案读取仍为0，后续模型业务验收由独立collector记录。50个GCC／22个SCC whole units的局部采用范围不扩大为整份来源完整采用，799原生位置不变成Word物理页，前端原图／表格业务验收仍单独执行。

安全runtime config SHA `fc0939ab4a49d44152ad18bf6774999209d0c24b90ea1a454bb849c0acfd493f`：profiles h2／oer／minimax-relay，canonical及Advisory8875、Vetting8874，canonical read/call timeout300,000ms；M3经本机relay8092，结构化输出16,384 tokens、timeout1,900,000ms、thinking disabled。业务请求须显式 `X-ConSense-Llm-Profile: minimax-cn`，无header的legacy local已disabled；未将token或环境内容写入收据。OpenAI请求不发送 `num_ctx`；通用配置类的numCtx默认8192不能报告为M3上下文上限，上游context容量本轮未实测。现有Advisory邻接证据字符预算12,000保持，未改模型或业务预算。

最终只读readback helper第一次编译曾误引用package-private `CanonicalJson`，在main执行前编译失败，实际HTTP0；保留 `readback-helper-compile-failure-receipt.json`。仅将ignoredhelper改为标准MessageDigest SHA后执行上述真实读回，没有改变产品类可见性。
