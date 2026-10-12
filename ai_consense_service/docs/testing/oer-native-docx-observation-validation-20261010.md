# 原生 DOCX 无损观察 adapter：实现与真实原件验证

日期：2026-10-10，Asia/Shanghai。本文记录独立观察层，不是 canonical 采用、原件质量 gold、在线业务验收或 H800 部署完成证明。当前范围服从 [OER 规则](../oer-scope-and-rules.md) 和 [handoff](../HANDOFF-ocr-embedding-rerank-20261008.md)；项目 PRE 保留，SL 非技术库、BQ／General Summary、非 PRE 项目技术 PSS 排除，2a 标准模板仅 Drafting，2d／2e 答案隔离。

## 已实现的边界

新增 `src/main/java/com/consense/document/OerNativeDocxObservation.java`，版本 `ooxml-observation-2`。它读取实际源 DOCX ZIP 字节，用 POI OPC 识别真实部件的 content type 和 relationship，再用禁用 DTD／外部实体的 namespace DOM 观察实际 Word stories。原 package 和每个非目录 ZIP entry 的**实际字节**、长度与 SHA 全部保留，不用重序列化 XML 代替 raw bytes，不修改原件。

本次仅新增上述 adapter、聚焦 test 和本文；运行脚本及全部原件／导出／失败证据在忽略的 `.local`。没有修改 `DocumentParser`、共享消费者、在线 `target`、数据库或前端，没有加载模型，也没有改变 Bonsai 或其他在线／WSL 服务。生产业务尚未默认调用此 adapter。

公开入口：

```java
ObjectNode OerNativeDocxObservation.observe(
    String sourceFilename, byte[] actualSourcePackageBytes,
    String actualAdapterSourceSha256) throws Exception;
```

该方法要求显式 adapter source SHA。调用方对所提供的 actual source/code bytes 负责；独立 CLI 的三个参数为 `source.docx fresh-output.json actual-adapter-source.java`，直接读取这两个文件的实际字节，输出使用 `CREATE_NEW`，不覆盖旧证据。下游 capture 应重新核对源 ZIP、adapter 文件和所有 part／node／token，再保存 CAS 和 seal；不能仅相信此处声明的 SHA。

## 实际 wire 与定位规则

根 schema 为 `consense.native-observation.v1`，`producerKind=native_docx`；schema 表达观察 shape，observer version／actual code／deps／config 表达具体实现身份。

| 字段 | 实际规则 |
| --- | --- |
| `source` | 原始 filename、package SHA、mediaType 与 `sourceRevisionSha256`。revision 为 sorted-key compact UTF-8 JSON `{sourceSha256, observerIdentity}` 的 SHA；它不是单个原件 SHA 的别名。 |
| `observerIdentity` | `ooxml-observation-2`、actual adapter source SHA、实际 POI／Jackson／Java／XML factory 身份、解析配置和 sorted-key compact JSON `configSha256`。 |
| `sourcePackage` | actual 原 ZIP 的 `name/mediaType/sha256/byteCount/bytesBase64`。 |
| `rawParts` | 全部非目录 original ZIP entries，以 actual ZIP name 保存，不加 leading slash；实际 uncompressed entry bytes、长度、SHA 与媒体类型均保留。关系／样式／numbering／二进制 media 也不丢弃。 |
| `storyInventory` | 实际 `partName/partSha256/mediaType/rootExpandedName/storyKind`。main、header、footer、footnotes、endnotes 观察；comments／glossary 等未支持 story 保存 raw，明确 unsupported，不映射到 main。 |
| `nativeNodes` | 仅 `paragraph/run/table/row/cell`，按实际 XML 观察顺序给全 bundle 唯一、严格递增的 `sourceOrder`。保留实际 XML namespace／local name、raw attributes／properties、parent native node／table／row／cell。 |
| `sourcePosition` | `kind=docx_native`、source SHA、actual part name／SHA、精确 namespace Clark path。同 expanded name 的 sibling index 从 1 开始，不依赖文档的任意 prefix。 |
| `tableInventory` | 每表使用对应 table nodeId；完整 rowId／rowIndex、cellId／rowIndex／cellIndex、grid columns、raw gridBefore／gridAfter／gridSpan／vMerge／hMerge、owned paragraph IDs、nested table IDs 和准确 parentCellId／parentTableId。没有推断 rowSpan、gridColumn、视觉跨页组。 |

节点 identity 的最终公式：

```text
native- + SHA256(UTF8(
  "consense.native-node.v1\n" + kind + "\n" + sourceSha256 + "\n"
  + partName + "\n" + partSha256 + "\n" + nodePath
))
```

`partName` 是必要身份维度：两个完全相同字节的 header parts，part SHA 和 Clark path 可以相同，但原生位置不同。初稿曾遗漏它；尚未发布的旧 `ooxml-observation-1` 输出保留为历史，最终升级到 version 2，新增相同 header bytes／不同 part name 的回归。当前三份真实原件未出现旧 identity 碰撞，不能据此忽略通用缺口。

Word 原生节点及 position **不出现** `pageNo/physicalPageNo/bbox`，包括 null alias。`originalGeometryStatus=not_defined_for_native_docx`。打印 PDF／原图／bbox 映射须另外绑定实际 renderer、rendition bytes、页图和原生 node，本文没有产生这些映射或宣称完整可视表组已返回。

## 文本、编辑态和表格保真

每个 paragraph／run 保存四个独立 channel：

- `observedRaw`：实际 DOM 解码后的文字加显式 OOXML control 编码，不做 trim、NBSP 替换或空白压缩。
- `deleted`：直接 `w:del`／`w:moveFrom`／delText 等观察。
- `directStrike`：实际 run 的直接 strike／dstrike true 属性观察。
- `nonDeletedNonDirectStrike`：仅排除上述直接信号的候选；**不称有效显示文字**。

tokens 保存实际 node path、`rawDomText`、raw attributes、直接编辑信号、各 channel 的 included 标记及 `[start,end)` **UTF-16 code unit** offsets。`w:tab/ptab` 编码 `\t`，`w:br` 编码 `\n`，`w:cr` 编码 `\r`，同时保留原 control 和属性；这些编码不是 Word 原始物理分页定位。XML 自身的字符实体／行结束符解码遵循 DOM 标准，原 XML bytes 仍在 rawParts 可复核。

field instructions 单独保留，不发明它的显示结果。symbol 保存实际 font／code，display unknown，不把 Wingdings code 自动当 Unicode 字形；auto numbering 也不补造。rawProperties 保存段／run／表／行／cell 属性的 decoded DOM tree 和准确 path；未计算 style inheritance、hidden／strike 有效状态、fields、content controls、AlternateContent branch 选择及 story applicability。因此 `effectiveCandidate` 不输出，`effectiveCandidateStatus=unknown_style_inheritance_and_visibility_not_resolved`，semantic quality 保持 unknown。

paragraph 与 run 是重叠的观察粒度，**不能把两者同时拼入正文索引**。table／row／cell 的 channels 和 tokens 为空，仅保存结构关系；内表作为独立 table 绑定 parentCell，不拼平到外表格子。空段落／格子、无文字图／符号／field token 都保留，不能为满足检索 content 的非空约束发明字符。下游需按明确 node/channel 和完整 native 表进行显式采用。

`inventoryComplete=true` 只指准确 native table 的所有原生 row／cell 均已观察，不证明合并后的 grid 正确、表头适用性、Word 渲染、条款层级或跨页视觉关系。raw merge 属性的 absent 和 val 缺失分别保存为 `present=false`／`present=true,val=null`；非法值也完整保留。没有推测 grid 位移或 rowspan。

## 隔离契约验证

最终复制 checkout：根目录 `.local/state/oer-native-docx-observer-20261010/run-021313-f25c92`。

直接使用 bundled JDK／Maven Launcher，`forkCount=0`，专用已存在 Unix-domain socket tmpdir。只在该 checkout 编译／运行；当前在线 service `target` 未编译或覆盖。

`OerNativeDocxObservationTest` **10 tests，0 failures、0 errors、0 skipped**：exact tabs／NBSP／emoji／换行、直接删除／strike 与 inherited unknown、field／symbol raw、完整 blank／merge／nested topology、sdt／customXml 实际路径、准确 header／footer／notes part、unsupported comments raw、source/code revision、repeatable IDs／order／无 PDF aliases、DTD 拒绝、高度压缩 EMF 不改变全局 POI ratio，以及字节相同的两个 header parts 仍有不同 ID。

最终源码 SHA：

| 文件 | SHA-256 |
| --- | --- |
| `OerNativeDocxObservation.java` | `2a28cf5ce37d72407fe7d36e7162600e65a6d0bc0539a6ce929f22bfdb2fc265` |
| `OerNativeDocxObservationTest.java` | `ced41aa2cb2fed962c9bb8d44ebb4a94dec5476265362bd68e14f45095f65c87` |
| 最终 `validation-receipt.json` | `c6210e62fca0ed161fbea3043a214430f0fbd87b58e97956a23dfcb89baf63c7` |

receipt 带完整 test report／log SHA 和 frozen-versus-workspace equality，最终源码核对相同。10 项是软件契约，不是 10 道合同题或业务准确率。

## 真实原件导出与独立 source XML 全覆盖核对

最终原件／跨语言 fixture 导出目录：

`.local/state/oer-native-docx-observer-20261010/run-021313-f25c92/real-exports-021404-ae8675/`

每份源字节在导出前后 SHA 相同；最终 frozen adapter 与 workspace SHA 相同。`export-receipt.json` SHA：`ec8c12cf2aa9f57551275032378f9b8beb3d9034301900ec45dedf0936239295`。

独立 Python 标准库脚本 `audit-native-export.py` 从实际源 ZIP／XML 重新建立 Clark path／父关系和 token/channel，不从 adapter 输出构造期望文本。它逐件验证全部 ZIP parts、所有已支持 stories 的每个 native node／顺序／kind／identity、每个 token 的 raw DOM 文字与 controls／直接编辑信号／UTF-16 channel offsets、每张表的所有 row／cell／raw merge 属性和 nested 关系。该核对是源结构／解码保真，不代替 Word 视觉和法律语义参考标注。

最终 `source-xml-audit-receipt.json` SHA：`78781c2f6c2dfcbea9703011f98292500c6c347b35be244f76d5f10c888877b5`，包含审计脚本实际 SHA、上游导出 receipt SHA 和逐来源证据。所有四份来源的 `allZipPartsExact/allSupportedStoryNativeNodesExact/allTokensAndUtf16ChannelOffsetsExact/allTableRowsCellsAndNestedRelationsExact=true`；`displayedTextVerified=false`、semantic unknown、`humanConfirmed=false` 仍保留。

| 来源 | ZIP parts／stories | Native nodes（paragraph、run、table、row、cell） | 完整 native 表／行／cell | raw-channel-empty leaf cells | 最终实际导出耗时 |
| --- | --- | --- | --- | --- | --- |
| authored synthetic wire fixture | 11／6（1 unsupported comments） | 11、11、2、3、4，总 31 | 2／3／4 | 2 | 0.732 秒 |
| 2b Marking assessment std 原件 | 29／5 | 1,187、17,188、34、97、345，总 18,851 | 34／97／345 | 54 | 2.331 秒 |
| 2b G302 insurance 原件 | 30／6 | 637、1,267、0、0、0，总 1,904 | 0／0／0 | 0 | 0.992 秒 |
| 2b 项目 PRE 原件 | 125／53 | 21,836、35,312、224、2,899、11,994，总 72,265 | 224／2,899／11,994 | 6,121 | 5.975 秒 |

每行数量来自 `source-xml-audit-receipt.json.rows` 的逐来源计数，时间来自 `export-receipt.json.exports[].wallSeconds`。empty 定义为无 owned nested table 且所有 owned paragraph 的 `observedRaw` 都空；field／symbol 显示未知，不能把这些计数叫已核验视觉空格子。paragraph／run token observations 有重叠，不能相加当独立原文字符数或推理预算。

| 真实来源 | 原件 SHA-256 | 最终导出 SHA-256／bytes |
| --- | --- | --- |
| Marking `05.1(2) - Marking Scheme (assessment std).docx` | `56dcdea23a87dd49946053063a11c217348314751c2b9fde50caf4af581656a0` | `e0fad4f1486f28e8232ad35bcbbf6259c181836da83a58f2993bed19c7be89a1`／179,509,831 |
| G302 `13 - Appendix G302 - insurance.docx` | `5047630bf82cbf5613b0f280dcf116a1b67176e9fef8ff3f930ce270a26af0ae` | `432bb813a5295feedcadd05c7d310d4846f05ce0d4859af997f7e321f3526dba`／13,305,842 |
| PRE `25 - Specification - PRE (base tender).docx` | `a4e61064c33ba2b67c5192bfa7756c1b792dbb74ffda32aea84f2b96509dc6ea` | `07419ddfce4512a642085bd4b37c8f5e4c883b8fbfb5a05e96db261f7868ac79`／554,929,510 |

authored fixture 位于该 run 的 `authored-wire/{authored-native-wire.docx,native-observation.json,fixture-provenance.json}`。实际 package SHA `b9e40483be6efbec570749ea1aa62384a984de42073c4375eb65f8282ace3c9d`，export SHA `6cefe315a264503b89104efbf61decc71d07249a5a064a91605653c16acd960e`，140,585 bytes。它覆盖 emoji、NBSP、tabs、直接删除／strike、field、symbol、nested blank cells、非默认名称 header／footer 和 notes。它明确为合成 wire 样本，不计赛方原件质量／留出准确率；ZIP 创建时间也进入实际 package 字节身份。

原件和大导出只保留在忽略目录。最终 JSON 较大源于保留每个 raw part、run／paragraph token、属性、SHA、path 及 channel offsets；可追溯观察不能因此静默丢弃。CAS／sealed projection／检索输入需要专用 capture 和选择，不能把整个观察导出直接塞入 LLM。

## 保留的失败和性能迭代

初轮 `.local/state/oer-native-docx-observer-20261010/run-015921-b0dff6` 的 8 tests 通过，Marking／G302 实际导出成功；PRE 在 POI `OPCPackage.open(InputStream)` 初始化失败，具体 `word/media/image16.emf` 初段展开 106,496 bytes／压缩 328 bytes，ratio 0.003080 小于 POI 默认 0.01。原失败 stack／log／receipt 和已导出文件保留。

修复为 exact bytes 的私有临时 package，用 POI `PackageAccess.READ` 仅读 OPC 元数据／关系；rawParts 仍从原 ZIP 获取。没有调用 `ZipSecureFile.setMinInflateRatio` 或改全局阈值。原件 PRE 本来含 38,074,712-byte 的 highly compressible EMF，完整展开容器 94,118,406 bytes；媒体保留，不冒充浏览器 PNG。观察配置显式限制 package 128 MiB、单 part 512 MiB、总展开 1 GiB、parts 100,000，超过时拒绝而非截断。临时文件只在本调用私有路径创建和清理。

第二轮 `.local/state/oer-native-docx-observer-20261010/run-020146-4ec6a6` 完整 PRE 导出成功，初实现耗时 209.614 秒。随后一次 DOM 遍历缓存准确 path、流式 compact 写 JSON，保留全部节点／tokens／raw 字节，使后续同机 PRE 导出为 4.512 秒，最终 version 2 为 5.975 秒。此为相邻本地进程工程测量，未控冷／暖缓存和全系统负载，不称严格吞吐基准或 H800 性能。大输出没有缩减成旧 normalized blocks。

独立审计首 attempt 因脚本每 token 重复计算同一个 4–12 MB part SHA 而主动取消：已先核对实际自有 Python process identity，保留原 log 和 cancellation receipt。修复仅缓存每个实际 part 的 SHA，全节点／token 验证范围保持完整；后续四份来源均完成。它不是 parser 质量失败，也不能把已取消审计算作通过。旧 observer version、修复前后源和失败证据均保留，不 reset／clean。

## 尚未完成的验收

本 adapter 解决实际 package／part／DOM 原生位置和文本／表拓扑的观察保真。它没有建立已审核 canonical、源参考 gold、有效编辑态／numbering／符号字形、完整 Word 可视表组与 rendition 定位，也没有做全范围文件质量接受、Java／Python registry／真实检索／前端业务回跳或 H800 推理验收。

下游 native producer 继续以明确采用的 node／channel、完整表组和原件参考门控，传播 semantic／applicability／editing unknown；不能复制 POI 输出假装两套 OCR，也不能用上述计数或字节核对把 unknown 提升为正确。scanned v1 的完整实际 Paddle＋MinerU 准入保持独立。后续集成与审核按新 producer 和完整业务证据另记。
