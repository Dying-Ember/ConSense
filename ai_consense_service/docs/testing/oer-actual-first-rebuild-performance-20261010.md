# 首轮真实 GCC／项目 SCC 索引耗时与门控路径

2026-10-10，Asia/Shanghai。仅分析已保存的首轮真实请求、线程快照及同一冻结源码；分析自身没有 HTTP、模型、数据库操作，没有读取 2d／2e 答案，没有修改产品或运行参数。本文补充 [真实来源准备](./oer-actual-official-source-business-preparation-20261010.md)，不代替 [官方业务验收计划](./oer-official-business-acceptance-plan-20261010.md)。

## 常规 RAG 与当前实现的分层复核

2026-10-10 用户连续询问向量存储、RAM 复用，以及当前实现是否偏离常规 RAG。本节区分选型、业务要求和实现开销；不是缓存已部署或性能已改善的收据。

当前 Python 模型服务＋BGE-M3／BM25／RRF＋reranker＋Qdrant、Java 业务服务与本地 H2，属于可用的常规组件组合。Qdrant 当前是 Python local persistent mode，不是独立 Qdrant server。按 clause 层级定位后回取父条、条件／例外和完整表组，是本项目已有结构要求；无需因此把原始 OCR／glyph 归档放入每次检索的处理路径。

当前实现把完整来源校验放得过于频繁：`CanonicalCurrentSourceReader.checkSource` 重新加载被核验来源、计算 `VettingCorpus.sourceHash` 并核对原件；collection root／batch／持久 metadata 读取和 query 后又逐次冷核完整 CAS。`sourceHash` 在包含 `paragraphStyleName` 时还会解析和重新序列化结构。Python 已缓存部分项目 metadata／数据库实例，不能称整个实现没有内存复用；但来源冷核和派生结构复验仍会重复执行。这些是源码可确认的路径，尚不能给出各路径占总耗时的独占比例。

更合适的工程分层是：上传／解析／采用／建索引时完整核验并发布不可变修订；在线检索读取轻量当前版本、范围和采用关系，用修订身份定位已校验的索引与结构对象；返回的原文／原图／完整表组通过 artifact ID 回取对应完整证据。RAM 适合保存有容量上限的解码结构、父子映射、词法索引及索引身份，持久原件和原始 parser 产物仍保留。query 相关的 rerank 分数不缓存成通用事实。

该方向需要先建立受控写入／不可变 artifact 与可靠失效机制，再决定哪些完整校验从在线链路移到发布／恢复／审计阶段。仅比较 mtime／size 或把“上次校验通过”当作永远有效，不是可接受的替代。当前 scope 文档的逐次完整 CAS 冷核条款仍描述实际基线；缓存改造尚未实现，不据本节追认其已撤除。新的实现应继续通过来源替换、采用变化、删除、模块／范围变化、坏 artifact 和重启恢复反例，并分别测冷启动、暖查询与源更新的时间／内存峰值。

前述目录投影和新 verification-read 候选属于局部减载；它们不等于以上分层已经完成。内存缓存也不能替代 glyph 重复序列化和 LLM prompt 中机械 metadata 开销的治理。后续保留现有组件，先修订存储／检索边界，不为使用框架而整体重写，也不先增加模型训练来掩盖工程等待。

### 用户提供的 Qdrant 建议核对

用户提供的外部 agent 建议为附件 `已粘贴的文本.txt`，10,975bytes，SHA `1f77aa04c9d9b6c5e337aa0260981152f144eb4138b5a003a17cdf5730d37968`。其中引用的远端 master 源码不直接当当前运行事实；本轮核对本机安装的 `qdrant-client==1.19.1`，没有打开活动 store。

当前 `local/persistence.py` 保存序列化 point 到 `storage.sqlite`；`local/local_collection.py:326` 的 `load_vectors` 从持久记录构建内存 NumPy 向量、payload 和 ID 映射，稠密搜索对内存 vectors 计算距离。因此“SQLite持久化”与“Local内存检索”需同时说明，不能让人误以为 SQLite 执行向量相似度搜索或每次 query 重读全部向量。`local/qdrant_local.py` 明确对同一目录持有独占锁；payload index 在 Local 中发出无效警告。当前源码的20,000point值是建议服务端的大集合warning，不是功能硬上限，也不能作为低于该数一定足够的性能承诺。

采纳的工程方向是：来源身份／版本／项目采用关系及完整原件为权威，向量与父条款结构为带修订身份的可重建检索副本；加载后按父ID回取相关完整条款／条件／例外／表组，准备完整新索引后统一发布。三种职责不要求增加三套物理数据库。当前先治理大档案普通读取、版本失效和RAM对象复用；完整SL／多worker／恢复验收出现实际需要时，再依据测量选择Qdrant服务端。单个129points开发索引不代表完整规模已验收。

## 08:44 增量：运行中 Vetting 的局部模型日志观察

### 后续独立核对：实际提交的记录与来源JSON

同一保存prefix（618,503B，SHA `e2700aa8f37ac29de0d750732bff22ac5e18cff2aac54cdf4201a98e83a3f365`）中的两次调用均已经走canonical/OER检索，且已经使用`lossless-source-metadata-dictionary-v3`；不能将其描述为没有RAG或尚未使用v3共享。独立解v2 records及v3 metadata后，两次最终提示均为25条来源记录＝22条项目SCC＋3条GCC，请求内record id无重复。第1次top-level content 8,928 UTF16字符，source JSON302,265字符／302,375 UTF8 bytes；第2次7,474字符，source JSON266,940字符／267,018 UTF8 bytes。这些字节是保存来源JSON按UTF8编码的实际大小，不包括provider HTTP body的其余字段／schema，不是token计数。

独立展开后source tree的minified重建分别1,024,119／919,272 UTF16字符；这是解码后的重建量，未额外发送，更不等于1GB。实际wire的可加分项第1次为canonicalStructure值体163,706＋全请求共享metadataValues123,766＋content JSON值9,064＋其余键／字段／框架5,729＝302,265；第2次133,229＋120,357＋7,584＋5,770＝266,940。共享字典不可全部归给某一来源或某一结构字段。25是最终提示记录数，不能仅凭此数把每条都称为reranker直接返回的独立命中。收据`.local/state/oer-official-q16-first-run-20261010/vetting-log-observation-0844/independent-route-review/actual-prompt-counts-receipt.json` SHA `df5fd15cc6e380364ffc5b30de4098d895797db75a558034650c9f5db60bb701`（基础counts快照）；新增分项单独由完整审计保存，不覆写该counts快照。

补充澄清：下文接近 1GB 的 `native_pdf_evidence_json` 是后台保存的原始 PDF 解析档案，不能称为一次 RAG 检索后提交给 M3 的上下文。保存的同一 stdout 前缀已独立重开并核 SHA，两次日志的 system prompt 均为7,469 Java UTF16字符，user prompt分别为316,178与280,841字符；两段合计分别323,647与288,310字符。这里只提取日志长度头，不输出完整提示。收据为 `.local/state/oer-official-q16-first-run-20261010/vetting-log-observation-0844/prompt-size-clarification.json`，SHA `0b2a473738d316d564d1c2367d9ac082aefc5b6afdfb9ad62a99e310912c81f9`。

这些字符数仍包含业务提示及来源结构信息，不是纯合同文字；它们不等于token数、完整provider JSON字节或神经输入预算，schema及transport开销也不在这两个prompt长度计数内。原始档案的存储／重复读取开销，与模型实际提示结构开销分别调查，不能把1GB归给RAG或把497.074秒全部归给大字段。

后续已保存档案分项核对：两个字段合计1,490,803,644B，三条glyph对象路径的字节跨度合计1,485,522,714B，占99.6458%；路径分别是页面`glyphs`、页面`nativeBlocks[].pdfGlyphs`和结构`pdfGlyphs`。后两条路径合计990,234,164B。源码确认同一glyph对象随页面与line block分别序列化；聚合计数本身不是三组所有值逐一相等的证明。另一次六原页独立观察确认每glyph有36字段，重复保存相同limitations，glyph没有raw stream或Base64字段；该六页观察的nativeBlocks为空，不能用它冒称重现了完整档案的三路径。原始完整测量SHA `745e6255ccb19c8e8b02e068a176e1968a65881edc38854380a1b5aa2d51dbe1`，汇总收据`.local/state/oer-current-source-metadata-catalog-20261010/run-005733-15ab4d/gcc-archive-growth-readonly-evidence.json`，SHA `84cef16d42946d3ff7da60ec13a4d387ee155c0bfbf233263705a0327ded4bea`。本次复核读取已保存测量和小收据，没有重读活动H2的大字段。

目录读取的最小修复已在独立副本冻结：supersession catalog由全21列（含5个LOB）改为显式5列`id/projectId/category/fileKey/fileName`；实际JPA测试只加载请求文档，其他文档不再被目录查询hydrate。最终同一轮137项测试通过，冻结ledger SHA `2e477e4dd79dbff5b5a583a40312e1fe40ab948f955b253485d4506b229824f6`。此候选仍完整读取被核验文档并重算原sourceHash，没有解决其自身约993MB native字段和约498MB structure字段的全部成本。09:24 root已在独立review后精确落主2产品＋5测试，apply receipt SHA `406d6a93c622f41893ee36ff6a8eec24d01565df07c9773f4d75b4121ee69ff9`；尚未部署或获得真实业务提速证据，不能将137项通过称为497秒等待已消除。

同一旧job `97ca407a-1fa3-48b0-a1d2-bc884e7574ab` 的保存stdout前缀观察到两对AiGateway事件：08:27:54.422请求至08:27:59.815原始返回，5.393秒；08:36:16.889请求至08:36:23.092原始返回，6.203秒。两份完整返回均为 `[]`，对应UTF-8 SHA `4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945`。这只是日志请求／原始返回间隔，包含客户端调用工作；没有token usage，不能计算tokens/s、纯forward或完整HTTP调用次数。空数组也不作为质量通过证据，任务仍未终态。

两次日志请求之间相隔502.467秒，首次返回至下一请求相隔497.074秒，说明此段主要墙钟花在模型调用以外；具体来源fresh DB／hash／CAS／检索的独占成本尚未录制，不能把497.074秒全部指定给某一层。新层级recipe尚未用于该旧run。本次只读取并保存正在写入的log前缀，未重发job／请求、未改产品或部署；Windows console labels按cp936解码。证据为 `.local/state/oer-official-q16-first-run-20261010/vetting-log-observation-0844/observation-receipt.json`，SHA `86022a4b47b121c8817f33269e8c19e17d54d29e822f7c64876973797da9782a`；`stdout-prefix.bin`绑定观察范围，事件时间可直接复算。首次ReadAllBytes独占读因文件正在写入而失败，后用共享读取Get-Content成功，未干预写入进程。

## 实际请求结果

项目 `official-q16-source-90602e8f-85b8-4e1a-ad61-2625c654115e`，独立 Java `18083`，canonical／Advisory `8875`。来源为原件 GCC 与 **实际项目** SCC，局部采用 50／22 whole units；不是 2a 的起草模板。冻结 Jar SHA `2321add597a844995b35a8a0f9b3fd041c171aae2a5b1e2d36e90228ecd56426`。

| 测量 | 实测值 | 证据／含义 |
| --- | ---: | --- |
| Java `POST /index/rebuild?scope=fullset` | **738.5420868 秒** | `preparation-receipt.json` 的 `java-index-rebuild.elapsedSeconds`；UTC 22:51:59.995166 至 23:04:18.547671。HTTP 200、API code 0、vectorReady=true、stale=false、indexed=72。 |
| Python `POST /index-canonical` | **116.1425100 秒** | 独立 journal receipt；终态 completed，68 events 完整字节／SHA 复验。这个时间含实际 CAS、分窗、tokenizer、模型与索引操作。 |
| Java 请求墙钟减去上述 Python index 墙钟 | **622.3995768 秒** | 从两份实际时间相减，约占 84.27%。包括外层数据库／来源哈希、snapshot HTTP 的 Python 实际 CAS 校验、JSON／指针等工作；不能报告为 Java 纯 CPU 时间。 |
| Python index 占 Java 请求墙钟 | **约 15.73%** | `116.1425100 / 738.5420868`，不是 GPU 利用率。 |
| 业务 parent units | **72** | 两原件采用的 22 SCC＋50 GCC units，以及 Java HTTP 和 Python response 的 indexed。 |
| 实际嵌入 features | **125** | 73 正文窗口（72 个不同正文 parent）＋52 结构 context features；upsert 保存 125 个 1024 维向量，fixedCacheHits=0。结构 carrier 不是新的原文条款。 |
| 实际嵌入批次 | **63** | 63 个 `inference-full-input-tokenizer` events，对应 125 rows；实际 CPU／torch.float32，batchSize=2。 |
| 实际输入长度 | **最大 1,115 tokens** | 完整 `fullTokenCounts` 复算，所有 effectiveTruncation=false。长结构与短窗口配方共同执行，不能把 runtime 的短窗口 512 当所有输入的上限。 |
| 本次索引的 LLM 生成 | **0** | 准备收据 `llmRequestsMade=0`；索引已完成不表示官方题回答已完成。 |

Python event 是**累计里程碑**，不能直接当独立计时器：进入 nested index 的 event 在 49.1789974 秒，offline-tokenizer-identity 在 80.6577410 秒，window-model-loaded 在 82.3753977 秒，首／末 tokenizer event 在 82.4340734／115.1804567 秒，window-index-upsert event 在 116.0590135 秒。它们支持识别先后阶段，不能据相邻差值声称纯模型 forward、纯磁盘或纯 Qdrant 时间。

Java POST 返回之后，准备程序另发 `GET /index/status`，该请求重新加载来源与复验，拥有自己的 cold gate 路径和墙钟。**不将 status-after 的时间算入已完成的 738.5420868 秒 rebuild。**完整准备程序的终态另由原准备收据记录。

## 源码路径计数，区别于逐调用实测

以下按当前来源类型及成功分支逐层核算。GCC 为 `consense.native-pdf-canonical-projection.v1`，SCC 为 `consense.canonical-projection.v2`；后者也是 `NativeCanonicalContract.SCHEMA`，两份都走 native 重新校验，均不是 collection root。被分析的八个源码文件与冻结 copied checkout 字节相同，SHA ledger 见性能收据。没有新增计时探针，因此下列数字是**源码确定路径数，不是已录制的每次 SQL／HTTP 计数**。

| 外层调用 | `currentBindings` 轮数 | 依据 |
| --- | ---: | --- |
| `canonicalSession → load` 初次 binding 集 | 1 | `OerBusinessEvidence.load:60` |
| `load` 返回前 `current(session)` | 1 | `OerBusinessEvidence:79` |
| `index` 的检索调用前／后 | 2 | `OerBusinessEvidence:81` |
| `rebuildIndex` 的 `assertCurrentSelection` | 2 | `AdviceService:90,634–637`；assertSelected 内部 current，再显式 assertCurrent |
| `canonicalStatus` 的 `assertCurrentSelection` | 2 | `AdviceService:641`，同上述路径 |
| `indexStatus` 的前／后 current | 2 | `OerBusinessEvidence:82–83` |
| 合计 | **10** | 每轮遍历两源，不因 selected IDs 相同而略过。 |

此外 `load:69` 对**每份来源**调用 `evidence.pointers`，其 `readBinding` 再执行一次。每个 native `readBinding` 的路径为：

1. `CanonicalEvidenceService:229` 首次 `assertCurrent`；
2. `recheckNativeSnapshot:237` 的 `assertCurrent`，随后实际 backend snapshot GET；
3. `recheckNativeSnapshot:238` 核完整 snapshot 身份后再 `assertCurrent`。

因此成功 rebuild 对**每源**为 11 次 readBinding／backend snapshot GET、33 次 fresh assertCurrent；`CanonicalEvidenceService` 分支的两源合计为 **22 次 snapshot GET、66 次 fresh 来源校验**。另有 `OerCanonicalRetrievalClient.corpus` 的 native PDF 分支：index 和 indexStatus 各为 GCC 增加1次 snapshot GET，SCC普通native DOCX v2不走此分支。完整成功rebuild源码路径合计因此为 **24次 snapshot GET**，fresh来源校验仍为66次。上述均为静态路径计数，未声称已逐调用观测24次；不包含backend服务内部的二次registry／CAS读取，不把health或index POST算成snapshot GET。另有 `freshSelectedDocuments` 的3次fresh全目录读取（load、rebuild selection、canonicalStatus selection）。独立核对见源码 `OerCanonicalRetrievalClient.corpus`；原离线收据的22保留原分支口径，不改写旧证据。

## 大字段影响的确定部分

保存的真实 GCC 字段完整字节量为：

| 字段 | UTF-8 bytes | 来源 |
| --- | ---: | --- |
| native_pdf_evidence_json | **993,219,983** | `actual-persisted-fields-GCC.json`，H2 实际字符长度亦核对 |
| structured_content_json | **497,583,661** | 同文件 |
| 两字段合计 | **1,490,803,644** | 前两项求和，约 1.49 GB（十进制） |
| 实际普通文本 | **319,042** | 同文件的 text_content；普通合同文字不是这一容量增长的主体。 |

`SourceDocument` 将大字段映射为 `@Lob String`，没有声明 lazy basic。`CanonicalCurrentSourceReader.assertCurrent` 通过 Spring 代理使用 `REQUIRES_NEW / READ_COMMITTED`，`checkSource → readDocument` 每次调用实体 findById，再读取同项目完整 catalog，核对 supersession／routing／原件字节；随后 `VettingCorpus.sourceHash` 对 `textContent + structuredContentJson` 重新计算。GCC 每次该 hash 都涉及约 498 MB 的完整结构串，String 合并与 UTF-8 编码也有成本。检查 SCC 时完整 catalog 仍包括 GCC，大字段并不因本次检查对象是 SCC 自动避开。

`@Lob` 解决此前列容量不足，**不等于已经消除了 ORM 字段 materialization、完整 JSON 或重复 hash 成本**。不同事务中的逻辑字段处理量不能直接乘成物理磁盘读取量：H2／文件系统／RAM 缓存、JVM 内存与 GC 会改变成本，本轮未录制 SQL／IO／GC 分阶段计时。Python snapshot 还会按照实际 CAS bytes 验证，Java 线程等待该 HTTP 的时间属于真实门控工作。

两份已保存线程快照提供推进证据：

- `java-thread-0655.txt`：`AdviceService.rebuildIndex → OerBusinessEvidence.index → current → readBinding → recheckNativeSnapshot → CanonicalEvidenceBackend.snapshot`；
- `java-thread-0701.txt`：已推进至 `AdviceService.canonicalStatus → assertCurrentSelection → assertSelected → current → readBinding → recheckNativeSnapshot`。

两次都观察到 HTTP 读等待，但外层调用栈不同；后续成功 POST 终态也已保存。不能将它们解释为在一个调用内卡死、统一解释为 MiniMax 过载，或全部归因于显卡不足。本轮 embedding 的实际运行设备为 CPU，尚无同配方 GPU 对照；提高算力不能自动消除完整来源／CAS／ORM 的重复处理。

## 下一次真实 Ask 的静态预算

已有当前 live index、非空命中、来源未改变且正常完整返回的源码路径中，M3 前至少有：load 初始／末尾 2 轮 currentBindings，ensureIndex 的 indexStatus 前后 2 轮，adviceHits 的 retrieve 前后 2 轮，检索后 selected/current 2 轮；另加 load 的 pointers.readBinding。合计**每源 9 次 readBinding／27 次 fresh 来源校验**。

命中 standard-role 依据且找到了项目 tender 的采用／改写来源时，`withCanonicalIncorporationContext → sourceHits` 额外前后复验 2 轮，M3 前升至**每源 33 次 fresh 校验**。M3 之后的来源核验与持久保存前核验各再执行 selected/current，共增加每源 12 次；正常全程为**每源 39 次**，有 sourceHits 时 **45 次**。如果 ensureIndex 必须 rebuild，另加 2 轮 currentBindings／每源 6 次 fresh 校验。

这些数字用于解释真实请求的时间预算，不是实际 Ask 时长预测。空命中、异常／拒绝、澄清／历史消息检查、网络重试和模型输出成本另有路径。sourceHits 建立当前已观测的采用上下文，不执行新的 rerank，也不会证明法律适用完整。首轮实际 Ask 的请求／终态／M3／引用证据须由独立 collector 保存；不能依据 static gates 宣布 2,100 秒超时必然发生或不会发生。

本轮不减门控、不改参数、不用缓存替代当前来源验证。后续若要改善速度，应分别测量字段读取、hash、snapshot CAS、模型及保存，保留当前来源／采用／内容身份与并发更改的校验语义，使用已保存失败和明确 fresh-source 反例回归；优化结果不能追认为原件覆盖、训练或 H800 部署通过。

## 可复算证据

源码／契约验证、模型 index 请求、整份原件质量、官方题回答、Vetting 报告和浏览器业务验收分别记录。本轮只完成已保存 index 性能复算。

- 原准备：`.local/state/oer-official-q16-first-run-20261010/prepare-225151-d858b0/`；POST response SHA `2dafb5ce92715bcdf12c14b89f817be44cdcdc24789954d75b1fc17a13606f70`。
- Python journal：`.local/state/oer-unified-actual-preparation-20261010/run-222702-e68e99/retrieval-state/run-evidence/20261009T225551.300529Z-d0f5435c94254b4b98139a3ec55671c0/`；events SHA `2c869d806885f00c71708124fca277e6fad60ce22d00f143000f005367b254c8`。
- 本轮离线复算：[performance receipt](../../../.local/state/oer-official-source-business-bridge-20261010/run-060145-06ba7b/rebuild-performance-saved-analysis-v2/performance-receipt.json)，SHA `fa8bbae766d91f74deecaecf6d07a5b3ad544a71841a9c4b1b69e89a80ff18e8`；保存了观测时原 preparation receipt 副本、artifact ledger 及 source SHA，后续原收据继续更新不会改写副本。
- 复算工具：忽略目录中的 `probe/analyze-rebuild-performance.py`，参数 `--out` 必须为全新 `.local/state` 子目录；没有网络／DB／模型调用。
- 首次分析 helper 将 125 feature carrier IDs 误按 72 业务 parents 去校验，离线断言失败；目录 `rebuild-performance-saved-analysis-0710-v1/` 保留。修后显式分开 73 正文窗口／72 正文 parents 与 52 结构 carriers；只修观察工具，未改变 index 或产品。

## 2026-10-10 10:26 增量：架构附件与当前源码核对、后续实施边界

本节记录用户提供的架构审查附件与当前本地工作区的只读核对。附件审查的是后端公开快照 `0861bfa` 和前端 `84c6ba1`，不能直接当本机最新实现或运行事实。附件原始 SHA 为 `ffa113e3fa6eafbd8bacedd09ead87d888e87f8e28c10355d59517b17a85d813`。本轮基线 HEAD 为 `285fc2e3bdf97792aeae00a31dbde2c69ebcde60`，逐文件固定 10:20:43 CST 工作区，并补充读取核对；包含 10:17 Java composite 主源码应用，不以 HEAD 替代未提交源码身份。10:26:42 CST 复核所读源码无哈希漂移；第 6–8 节另有独立审阅收据。

这里只将已确认事实与架构方向持久化，没有实施附件全部建议；未更改产品代码、运行参数、模型或服务，没有执行测试或模型／官方题请求。源码已有、候选契约验证、实际进程部署、原件质量、官方问题、Vetting 报告和前端验收分别报告。主任务的后续 CUDA 实际执行以其独立运行收据为准。

**本节校正上文“常规 RAG 与当前实现的分层复核”中在线仅检查轻量版本的设计讨论：已确认的 actual-CAS-every-read、实际原件字节、完整源单位和查询前后 fresh-source 门控继续保留，不能因不可变修订声明或 RAM 命中而移除。** 上文保留历史语境；涉及规则冲突时以 [OER 已确认范围与执行规则](../oer-scope-and-rules.md) 为入口。可以按完整身份缓存静态派生准备，不能把“已准备对象存在”当“本次原件／CAS 已验证”。

### 当前已修与实际残余

以下行号对应本轮保存源码；后续代码变动需重新固定身份，不把行号当永远有效的定位。

| 附件问题 | 当前源码状态 | 证据与未完成边界 |
| --- | --- | --- |
| Advice 范围只写入提示词 | 已修源码 | `AdviceService.java:227–235,306–315,339–353`：canonical session 按选定来源建立；legacy chunk 与 vector 查询均限制允许的 document IDs。未替代真实 scope 冲突题验收。 |
| 比例估算页码／默认 P1 | 已修源码 | `pageOf()` 已移除；`AdviceService.java:130,470–480` 保留原解析 pageNo／null；`OerBusinessEvidence.java:195–207` 取 pointers 的实际 physicalPages。未知不能补造。 |
| 模型自报 grounded 后自动补文件名 | 已加固，语义仍需验收 | `AdviceService.java:266–273,674–678,737–744` 还要求唯一可解析引用、有效 basis、无待澄清项；无 grounded 引用自动补齐。兼容唯一精确 fileLabel。程序拒绝假 ID／错来源，不宣称证明自由文本全部语义。 |
| 历史丢失 sources | 已修源码 | `AdviceService.java:499–516,578` 保存并读取当时 sources、模型、scope、basis、clarifications。顶层 `document:<id>` 仍是兼容身份，完整不可变引用应看每条 source/citation。 |
| Advice ask／rebuild 长事务等模型 | 已修源码 | 整方法事务已移除；`AdviceService.java:581–591` 在远程调用之后短事务成对保存消息。未作真实 DB 等待与失败事务验收。 |
| legacy Java 重建先删旧向量／切片 | 仍存在 | `AdviceService.java:104–106,111–154` 先删除后嵌入／upsert。canonical 启用时先走其分支、失败不降级（84–91）；旧路径不能据此宣布安全。 |
| 每次 query 重算 signature／结构／BM25 | 仍存在，Java 部分减载已落源码 | `server.py:303–314`、`window_runtime.py:149–164,248–250,289–291`。VerificationMetadata 排除无关 archive LOB；SourceHashNormalizer 每次 fingerprint supplied content，只 memo 确定性 JSON 规范化。Advice 的全实体 selection 仍存在。 |
| index／retrieve 广泛全局锁 | 仍存在，OCR 断言需纠正 | `window_runtime.py:173,248–304` 包含 neural／vector／BM25／rerank。legacy OCR 有锁，正式 unified 无 `/ocr` 路由（`oer_unified_server.py:63–70`），不能认定正式 OCR 与 OER 同进程同锁竞争。 |
| CUDA serial 卸载／重载 | recipe 相关机制存在 | `server.py:43–62,245–273` 释放另一模型后，下次 loader 重建。当前本地 strict CUDA FP32／serial 的显存约束不能擅改；实际加载次数／成本尚须当前配方测量。 |
| 原档案一路进入业务／LLM | 已部分分层；残余需测 | native transport 已把大 inventory／glyph／AP 作为实际 CAS refs；完整 adopted units、table topology 保留；业务 view 和模型选取证据另建。全实体加载、重复 view／CAS 校验仍有成本。不能把后台约 1GB 档案当单次 LLM 输入。 |
| Advice 跨项目晚响应 | 已修源码 | `AdviceView.vue:120–176,189–209,239–257` 有 project／scope／epoch／sequence 归属和失效；未做本轮浏览器延迟实验。 |
| 同项目清空与飞行响应 | 新记录的源码时序风险，未实测 | `AdviceView.vue:261–269` clearChat 未 bump ask/reload 序号，374 按钮无 asking 禁用；相同项目旧 ask（209）或旧 history reload（144）可能重新填入已清界面。独立审阅一致，但未观察实际前端复现，不称后台跨项目写入。 |
| 内部 seconds 被当总等待 | 内部边界有限，但正式路由已有外层计时 | `window_runtime.py:248–256,306` 不含 lock／metadata／前置校验；`RequestEvidence:65–108` 和正式 route wrapper 有 outer elapsedSeconds。outer 又不是浏览器端到端时间；仍需细分阶段。 |
| INFO 全 prompt／raw | 仍存在 | `AiGateway.java:76–95`，complete 与 structured 调用会使用全文 INFO 记录；不能泛化到所有直通 chat 路径。未读取当前 logger level，未更改日志。 |
| queue 完整 source 大对象与恢复 | 单实例工程已有，残余保留 | `VettingService.java:66–79,152–166,184` 是单 worker、queue8、DB run；lambda 捕获完整 SourceDocument 列表，尚未量内存；恢复扫描全部 active runs，没有 owner／lease，不可当多实例恢复。 |
| 生产读取 samples.json | 配置组织耦合仍在 | `server.py:73–75` 仅选择 embedding／reranker model configs。需要分离配置入口，但该代码不是读取金标准答案进入生产 query 的证据。 |

`VettingInputBudget` 并非空白：完整 messages/schema、source snapshot、provider/model/configuration、输出预留、模板与上下文观测身份均有检查（54–79）。CLI observer 默认关闭；explicit minimax-cn profile 不使用本地计数器；unknown 保留 unknown。兼容首个 legacy global packet 可在 unknown 时调用，额外 packet／Responses packet 要求已许可预算，已观察 over_budget 不调用（`VettingSemanticReview.java:34–42,295–318`）。因此“有预算组件”“provider estimate”“actual observed_tokens”是三个不同结论，不能相互追认。

### 四层表示职责

四层是职责边界，不要求增加四套数据库，也不表示当前实现已经完整收敛到该架构。

| 层 | 权威或派生职责 | 内容与边界 |
| --- | --- | --- |
| 1. 原件与原始观察档案 | 原始证据保全 | 实际原件、OCR／parser 原始观察、glyph／字体／坐标／operators／AP／完整表格等留 actual CAS／忽略本地目录；完整实际字节可回取。避免重复内嵌序列化；typed refs 必须可核验，缺失／损坏不可自动修补为有效。 |
| 2. 来源身份、采用与 generation 清单 | 当前适用来源的权威绑定 | project/document、原件 SHA、parsed sourceHash、projection/adoption/routing、module/scope、软件／tokenizer／model recipe、完整单位／窗口 manifest 与 generation 绑定。不可变 revision 不免除 fresh 数据库与原件／CAS 检查。评测 gold 不进入采用或生产 query。 |
| 3. 业务条款与已准备检索对象 | 可重建派生表示 | 完整 adopted clauses、条件／例外、表头／表组、父子关系、必要 pointers；向量、正文／结构窗口、按 role/scope 准备的词项与 BM25。RAM 复用应有容量上限，身份完整；不得把 scope 内 BM25 改为全库打分再过滤，也不把 query rerank 变成通用事实。 |
| 4. 当前问题的模型证据包 | 问题绑定输入 | 从第 3 层选取完整相关单位／条件／例外／表格，配简短 citation IDs、来源修订、必要结构或图像证据与 unknown。可去重复机械字段，不能截断表头、限定或完整单位；完整最终序列化输入与输出预留单独预算。 |

native DOCX／PDF public transport 已在第 1／3 层之间使用 refs；Java canonical business views 与 LLM pack 已有分离。后续优化主要是明确边界、减少重复表示／派生准备；不需要为了框架名重写技术栈，也不能用训练掩盖工程等待。

### 完整 generation 的绑定、发布与失败恢复

新 generation 应统一绑定原件／采用身份、适用来源集合及顺序、module/scope、父单位／完整表组、窗口／结构 manifest、tokenizer/model/algorithm recipe、向量集合与持久 metadata。先构建候选、完整核验点数／payload／coverage／身份，再发布当前 generation；失败记录、旧 generation 与可回滚证据保留，后续清理由明确的引用与保留策略决定。向量 alias 单独切换不能证明应用父条款、来源采用及 DB 指针一致。

当前 Python `window_runtime.py:172–179,186–217` 用签名 collection，exact count 通过后写 `.json.pending` 并 replace 元数据，再更新内存；该路径不删旧 collection。它是已有设计，不是本轮 fault injection／断电恢复通过。Java `OerCanonicalRetrievalClient.java:83–100` 重建前删除 receipt，严格验证成功才新发 receipt；`failedIndexClearsEarlierReceiptAndCannotUseOldIndex`（测试 277–286）明确要求 fail-closed。**receipt 失效不等于持久 generation 被物理删除。**

需分别验收两种失败：来源／采用／recipe 未变化且旧 generation 完整有效时，失败发布不得破坏持久旧数据；来源或配方已变／当前证据损坏时，旧 generation 即使保留也不可作为当前来源的“可用性 fallback”。若改变 Java receipt 发布策略，应重新验证两种边界；不能只以“仍返回旧答案”判成功，也不能追认本轮已实施。现有实际 CAS 每读和查询前后完整父单位／源变更验证继续保留。

### 资源、观测与实施优先级

| 顺序 | 实施目标／范围 | 具体验收 |
| --- | --- | --- |
| 1 | 先完成当前 strict CUDA-only FP32 的实际模型／来源链路；保留 Bonsai 抑制和其他 GPU 工作负载 | 冻结启动源码、venv、模型／tokenizer bytes、recipe、实际 load dtype/device、generation、源采纳；真实 index/query 与 source changed／bad CAS 拒绝；CPU/auto 不作为成功 fallback。代码测试、真实模型与业务质量分开。 |
| 2 | 当前已修 correctness 契约先真实回归，处理已证实残余 | 不同 scope 放矛盾来源，确认不越范围；实际／未知页码、假的或歧义 citation 拒绝；刷新还原原 evidence；延迟 A 响应后切 B、切 scope／unmount；同项目 ask/history 飞行时 clear 的可复现顺序单独测。 |
| 3 | 理顺 generation 发布与失败恢复，治理 legacy 先删后建 | 分别在 embedding、vector upsert、metadata write／replace、DB pointer／receipt 发布前后故障注入；验证完整旧数据保留与当前源失效拒绝；重启后 cold identity/CAS 验证，失败／partial 不被宣布 ready。 |
| 4 | 缓存静态派生准备并减载表示；保留 fresh 完整检查 | 连续相同 generation+role/scope query 记录 tokenization/BM25/structural preparation 次数、实际 bytes、墙钟／heap；源替换、采用变化、删除、坏 CAS、recipe/scope 变化和重启立即拒绝失效对象。不得以 RAM 命中跳过 actual CAS。 |
| 5 | 测量锁与模型驻留，再选择调度 | 分清 queue/lock、fresh source/CAS、准备、load/offload、embedding、vector、BM25、rerank、pack、gateway 与保存；记录连续查询实际模型加载次数。依据本地与 H800 实测显存/峰值/加载成本选择常驻或分阶段，未经测量不删锁、不多 worker、不直接更改 serial 配方。 |
| 6 | 收敛普通日志、queue 表示和单实例恢复边界 | 普通 INFO 用请求/来源身份、数量/大小/token/时间/错误摘要；完整 prompt/raw 进入受控私有 capture，需证明原始失败与审计完整仍保留。量 queue source 保留 heap，身份轻量化需保证执行时 fresh source 与 queued snapshot observation；多实例 owner/lease 尚未实现，保留单实例限制。 |
| 7 | 用实际发布身份关联业务验收 | 先完成真实当前 recipe，再用赛方问题与 Vetting 结构化报告收集全部请求、失败、unknown、引用／source units、报告和前端展示。不得用单个开发索引、源码测试通过或空数组回答宣布整份质量／H800已通过。 |

锁拆分、模型常驻、prepared BM25／结构缓存、普通日志改造、queue 轻量化、clearChat 修复和 generation 发布整理均为需要具体实现与验收的目标，本节不将这些架构建议自动认作已批准产品改动，也不宣称已部署或已提速。完整 original/CAS fresh 契约是执行前提，不是等待后续优化才生效的建议。

### 本轮审计身份与证据

- 保存目录：`.local/state/oer-architecture-critique-crosscheck-20261010/run-20261010-101638/`；报告 `ARCHITECTURE-CRITIQUE-CROSSCHECK.md` SHA `8d62e9e2e980ebddc9978f77826e745e3728ab70570ec911823928359a96754f`。
- 21 项 machine ledger：同目录 `findings.json` SHA `3d745522e4b1d5c5c1ed81896277705381b1f9daae037284a615c8a6a911399e`；每项包含 current status、exact file/lines 与未验收边界。
- `audit-receipt.json` SHA `d81bf9fed25e58734fa3c38a4a6bcf1172eae84bcbc7af1884b68a9cce5521df`；结束 source drift=`[]`，source/runtime changed by audit=false、tests executed=false、model calls=0。
- 初批 `source-baseline.json` SHA `6cb8f0184c60d6203d8898f8fa7c6ec2741196644f3352bf4f386a088e8f8072`；扩展 `source-baseline-extension.json` SHA `6bbd363d3d99428650be63fcb2eab5438ea14f3ccfdd64174be61f02ac6ad7e7`，保留副本。初批 reader 包路径 missing 已由扩展实际 `service/sources/CanonicalCurrentSourceReader.java` 补正，原 missing 记录保留。
- 独立第 6–8 节审阅：`.local/state/oer-architecture-attachment-current-20261010/sections6-8-readonly-review.json` SHA `69e434ff1773e8ec1e9d928d6fe20b4f543607a2a81a022ae74d64d8d985ea10`，固定十个文件；同意 clearChat 仅为源码时序风险、总计时与组件预算不等于实际业务验收。

## 2026-10-10 10:48 增量：旧 Vetting 状态轮询本身会重复加载大来源

本轮仅离线读取已保存的 collector、运行 manifest、Jar ZIP class 与冻结源码，未发 API、未读取活动数据库、未执行模型，也未更改／停止旧任务。结论明确：**旧 `GET /runs/{id}` 不是轻量进度读取；每次都会加载当前全来源实体并重新计算完整 parsed sourceHash，监控本身构成额外重复负载。** 暂无 worker／poll 竞争的独占时间测量，不能把总等待全部归给监控。

### 固定的是被轮询的旧运行身份

Collector 在 08:11 后轮询的 `18083` 对应 `run-storage-root-fix-20261010-081500/java-process-receipt.json`：保存 PID `118580`、创建 UTC `00:09:50.066035`、Jar SHA `762b671197f04015554f79adc4bcb0d90b1d275b86cbe958fafd8de601faa76e`。它不是早前首轮 index 的 `2321add...` Jar。重哈希实际 Jar 匹配 receipt；七个相关 runtime source copies 匹配其 manifest；六个 Jar class entry 匹配 copied-trial build target。原静态 Jar 比较显示两 Jar 仅 AdviceService／CanonicalEvidenceService 两产品 class 改变，VettingService／VettingCorpus 未变。因此下列是旧运行冻结源码结论，不仅是当前代码推断。

旧运行 `VettingService.java` SHA `84c3bccd9748dc7e9a9697f5e5fa9a48b8e06304167c76187ea9bbf074cfd52e`，本轮当前 main 的该文件亦同 SHA。新 verification projection 只用于 CanonicalCurrentSourceReader；没有替换状态 GET 的 sourceSet 全实体读取。当前 main 新 SourceHashNormalizer 减少重复 JSON 规范化，但仍完整 fingerprint 当前 text/structure；不能称 status polling 已轻量化或旧任务已经使用新 hash。

### Getter 的确定调用链与 CAS 条件

`VettingController.java:57–59` GET → `VettingService.getRun:182` → `jobVO:582` → `sourceSetStale:589–593` → `freshSourceSet:617–620` → `sourceSet:450–453` → `SourceDocumentRepository.findByProjectIdOrderByIdAsc:16` → `sourceSetHash:583–587` → 每源 `VettingCorpus.sourceHash:139–154`。

- `jobVO` 对 RUNNING／COMPLETED 都调用 sourceSetStale；sourceSnapshot 非空时，状态 GET 不是只返回 run 表的 phase/completedUnits。
- freshSourceSet 开 `REQUIRES_NEW / READ_COMMITTED / readOnly` 事务，repository 返回全部 `SourceDocument` 实体，再执行 catalog/scope selection。
- 旧 `SourceDocument:65–99` 的五个 `@Lob String`（identity、native PDF、text、structure、coverage）没有 lazy basic 声明。全实体查询没有排除 native PDF archive；即使该字段不参加 sourceSetHash，仍属于实体 materialization 范围。物理 disk/cache 命中和实际 SQL 分阶段未测。
- 旧 hash 每次完整处理 text/structure；有 paragraphStyleName 时完整 parse／规范化，然后合并字符串、UTF8 编码、SHA256（`VettingCorpus:139–154,379–383`），没有新 memo。
- **CAS 分支不能扩大：** `sourceSetStale:594–597` 仅 coverage 中 `canonicalCorpusIdentitySha256` 非 null，才调用 `oer.currentIdentity → evidence.currentBindings/readBinding`。本轮全部 656 个保存 RUNNING response 都没有该 identity，所以确定重复 parsed sourceHash，但没有进入这个 CAS 分支。完成后含 identity 或其它访问链路须另核；不能说 656 次都读取原件／CAS，也不能因没有该分支就说它们轻量。

以上是 byte-bound 源码调用链，**不是现场采样线程栈**。已有 06:55／07:01 thread dump 是早前 Advice rebuild 路径，不能伪称 Vetting poll 栈；本轮没有新 JVM profiler、SQL／IO／GC 或 hash 计时探针。

### 已保存 656 次 GET 的复算

完整逐响应重哈希确认：656 次全部 HTTP 200、同 job `97ca407a-1fa3-48b0-a1d2-bc884e7574ab`、RUNNING、非空 sourceSnapshot、sourceSetStale=false、coverage 无 canonical identity；全部选中 SCC/GCC 两源。按固定成功分支 `656 × 2 = 1,312` 次 per-source parsed hash 是**源码路径推导**，不是实际 SQL/hash 探针计数。

| 保存观测 | 复算值 | 边界 |
| --- | ---: | --- |
| collector 总墙钟 | 7,237.396585 秒 | 08:11:46.302648 至 10:12:23.699233；collector 因 vetting_wait_timeout 失败，保存的 job 仍 RUNNING。 |
| 656 次 GET elapsed 合计 | 5,881.096 秒，约 98 分钟 | 原 request ledger 的 elapsedSeconds 求和；是客户端状态 HTTP 墙钟，不是 worker 独占 CPU/IO，也不是监控导致的净延迟。 |
| 每次 GET 平均／最短／最长 | 8.965085／6.781／14.813 秒 | 全部已保存 656 次，不仅末三次。 |
| 返回后 655 个间隔 | 合计 1,317.339750 秒，2.002435–2.075447 秒 | collector 等 GET 返回后再 sleep 约 2 秒；不是每 2 秒并发发请求。 |
| 最后 poll654／655／656 | 8.594／7.250／6.859 秒 | 按时间顺序；三份响应各 1,804 bytes、同 SHA `2346e4f3acc253fe952c34885373747240c42588063c1d5706cc7c0a20324e30`，仍 semantic 13/19。 |

不能把 5,881.096 秒从 worker 等待中直接扣除，更不能宣称停止 poll 会节省 98 分钟。可以确定的是：频繁 status GET 把全实体／完整结构 hash 再做一遍，与异步审查共享 JVM／DB／heap，有明显额外工作；其实际 CPU/IO／分配／GC竞争和关键路径延迟比例仍 unknown。也不能把上文大字段 bytes 乘以 656 当作实际物理磁盘读量。

保存 stdout 的两次 MiniMax 日志和 497.074 秒非模型间隔，与这条额外负载可同时存在；日志没有 status-get 阶段时间，不能单独将间隔归给某一层。10:46 的独立单次只读观察仍 RUNNING semantic17/19，表明 collector 超时后 worker 继续推进；不表示报告合并／保存终态完成。

### 后续验收与本轮证据

状态读取需作为独立性能路径治理：区分轻量 job progress、显式 fresh-source verification、完成保存、证据回取和导出。先避免读取不参与判定的 archive LOB、减少无意义频繁全核调度，再用同源／同 recipe 的低频与旧频率对照记录 worker 完成墙钟、poll source materialization/hash、JVM峰值与GC。任何轻量 progress 不能冒称 fresh/canonical 已验证；源替换、采用／模块／recipe变化、坏CAS、完成报告 stale 拒绝继续验收，业务证据读取仍保留 actual-CAS-every-read。本轮只调查并记录，未修改 API、poll 间隔或 fresh 检查。

证据目录 `.local/state/oer-vetting-status-poll-source-audit-20261010/run-20261010-024848-435/`：

- `STATUS-POLL-SOURCE-AUDIT.md` SHA `3133cce0dbc589c1eeabfd05a620c362d8b3c13f2f2d5142d22f3c81a95a4a0d`；
- `frozen-source-identity.json` SHA `ed8db89d4cebc173557132914acfe60f2222054c600f9d7ff0798dc144bd72b6`，保存全部 source/class/Jar 对应及零 API／模型／运行变更；
- `saved-poll-analysis-v2.json` SHA `21f33b46b716f3f0997f8935f7f63c7c3a494175dec6ca9b14a703097d70565c`，包含每个原 response SHA、request elapsed、job字段和汇总；
- 原 collector receipt SHA `a931f5da36b549d185112eebd9020637c86ee471a04715d0c807e1386294a902`；独立 10:34 audit receipt SHA `cfab8735e45fd47d562b14981a3c4616338d258e52558572345c6c312f3d3960`；
- Jar 静态比较 receipt SHA `1e11bb6c0938d46f1d3bbf38d72b4b95b56825e657c860a9f3ec318807a697d9`。初版 poll 间隔计算因 DateTime 转字符串损失小数秒，已由 v2 DateTimeOffset 直接减法修正，初版保留；GET elapsed 与 response哈希不变。

## 2026-10-10 后续：CUDA 真实请求计时与旧 Vetting 状态边界

隔离运行 `.local/state/oer-cuda-real-retrieval-20261010/run-024802-ccbe99/execution-v1/` 完成一次真实 `/index-canonical` 和一次原 Q1.6 question-only `/retrieve`，均 HTTP200。只复制原件 CAS／registry，不复制旧索引；792 文件／698,157,853 bytes 事后完整核验零漂移。实际来源顺序 SCC→GCC，72 原始 parents，129 新向量点（73 short／56 structure），cached=false、fixedCacheHits=0。

| 请求 | 客户端完整墙钟 | 外层运行收据 | HTTP 返回内部 seconds |
| --- | ---: | ---: | ---: |
| `/index-canonical` | 129.2383714 秒 | 约129.131秒 | 10.4031562 秒 |
| `/retrieve` | 69.5294469 秒 | 约69.483秒 | 8.1476496 秒 |

三层计时的覆盖边界不同，不能将内部秒数当整请求、纯GPU forward或加载耗时；也不能用时间差把剩余开销全部归为某一种解析、I/O或哈希。两模型实际CUDA／torch.float32，embedding65 tensor calls／129 rows、最大2006 tokens，rerank24 calls／47 rows、最大2043 tokens；effectiveTruncation全false，串行卸载有日志。返回3来源单元／3hits，GCC层级31members与完整原始parent匹配。模型／tokenizer16快照和145代码／配置文件独立核验。GPU环境依赖与旧CPU环境不同，此轮不构成纯设备消融或GPU加速倍数证明。

运行收据 `real-cuda-retrieval-receipt.json` SHA `bb63e8d6f6bd52d3b3dd8f35efa716ed736c201dd48197a36c71e08acfb98a81`；独立终审 `independent-review/actual-cuda-review.json` SHA `bf9035ad4657cb28bcd3a210ee9f4f2dd14ae2383be82864761bfcd8fcacf631`。自有服务退出／原10listeners身份保全收据 SHA `1c72be33f23d9de399132957e3fd365e875c727e755066da51a07f49ec645b49`。0LLM calls、未读gold、质量未评分；GCC PARTIAL／semanticScopeVerified=false与unknown继续保留。全链OCR、官方答案、报告、前端和H800仍未验收。

旧Vetting11:02:08–11:02:53唯一低频GET等待45.024秒后TimeoutError，HTTP=null、响应0bytes，owned身份通过；最新receipt `.local/state/oer-official-q16-first-run-20261010/vetting-final-low-frequency-audit-20261010-110203-303/audit-receipt.json` SHA `b0f199372912c13067aca2fb9b326c8e79b6e2efa537ac8169760fee30589a15`。最后成功观察仍是10:46 RUNNING／semantic17/19，耗时2小时34分；没有终态报告或评分。GET超时不证明job失败或完成，本轮没有重试／新POST／重启，停止进一步频繁轮询。

用户后续明确固定六来源质量验收后持久化，不在业务请求／状态读取／冷启动反复OCR、结构恢复、切块及文档embedding；完整处理链同时随发布包带到H800应对版本变化。执行规则已记入[中央规则](../oer-scope-and-rules.md)及[来源部署清单](../../../docs/oer-source-deployment-manifest-20261009.md)。这项规则不撤销actual-CAS-every-read，也不等于当前进度读取或来源核验性能已修复。

### 旧任务终态已经持久库确认

11:33核验原PID118580不存在、18083无listener后，未再对旧owner发GET。仅打开稳定完整原H2的只读副本，精确job `97ca407a-1fa3-48b0-a1d2-bc884e7574ab` 保存COMPLETED／completed／19/19，UTC起止00:12:04.151367–02:47:45.880744，复算9,341.729377秒＝2:35:41.729377。原库实际SHA复制及查询前后不变；未重启、POST、模型或报告导出。这更新此前API观察的终态未知；原超时及collector两小时退出事实保持。

findingCount=0不能当质量通过：17topic全completed_empty、aggregate partial、semanticScopeVerified=false；119条planner observed source requests全not_submitted_budget_unknown，因为explicit minimax-cn的selectedObserver返回null、完整input budget没有绑定，额外dispatch被门控。它们不是119次模型请求或实际超预算证据。此次只提交46唯一局部chunks，未完整审查合同。详细字段／数字证据见[终态及预算诊断](./oer-official-two-source-vetting-completed-partial-20261010.md)。轻量progress修复独立进行，不能据此宣称两小时性能瓶颈或此预算残差已全部解决。

后续轻量progress源码已按独立审阅通过的精确清单应用，见[回归及应用证据](./oer-vetting-lean-progress-validation-20261010.md)。16suites／126唯一Java cases与10个真实SFC交互cases通过，真实SQL去除重型source/result/coverage读取，旧详情及变源report4104仍验证。小H2同合法run旧GET11.6001ms、新progress17.4703ms，不当生产提速证明。运行服务尚未切换，没有重跑M3或提高旧任务coverage；完整业务性能与前端实测仍缺。
