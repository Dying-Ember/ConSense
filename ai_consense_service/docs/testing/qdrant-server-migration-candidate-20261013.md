# Qdrant Server 迁移候选：全量存储验证，尚未切换

2026-10-13，NEXT-04／主规格 D7、AC08 的迁移部分。目标仍为单实例 Qdrant Server＋PVC，Vetting 与 Advisory 复用既有检索入口。此次增加一个共享后端配置接缝及一个显式迁移命令，没有新增检索平台。

## 最小实现及使用边界

`tools/vetting_eval/server.py` 的单一 `database()` 入口使用 `qdrant_backend.connect()`：设置 `CONSENSE_QDRANT_URL` 时连接 Server，未设置时保留 Local 路径。Server API key 可由 `CONSENSE_QDRANT_API_KEY` 注入，不写入迁移收据。连接是 lazy，不在启动时装载模型。

`tools/vetting_eval/migrate_qdrant_server.py` 校验外部 SHA 绑定的冻结 manifest、来源元数据、embedding/window 签名、维数、Cosine 度量、完整父记录及逐窗来源覆盖，从新建自有副本导出向量，再向**不存在的新 collection** 显式 upsert 向量与 payload。拒绝原 collection 名、既有目标、相交目录、SQLite WAL／journal、变化副本、重复／缺失／不前进的点页及向量异常。失败保留候选和收据，不删除或覆盖 collection。

原件无需重新 OCR。Server 存储目录从空目录建立，Local SQLite 文件只出现在导出用的自有副本中，未作为 Server 存储文件复制。原 `qdrant/.lock` 是活动 owner 的瞬时进程锁；仅该文件明确排除复制和字节校验，原锁不打开、不移除。SQLite、Qdrant 元数据和完整父／窗元数据均在复制前、复制后和执行结束复算。

Windows 示例（使用已安装的 qdrant-client／NumPy 环境，不引入依赖）：

```powershell
python ai_consense_service/tools/vetting_eval/migrate_qdrant_server.py `
  --frozen-manifest 'C:/path/frozen-baseline-manifest.json' `
  --frozen-sha256 '<reviewed frozen manifest SHA256>' `
  --server-url 'http://127.0.0.1:16333' `
  --collection 'oer_server_baseline05_new_candidate' `
  --out 'C:/owned/fresh-migration-output'
```

输出包括副本来源清单、float32 向量文件、完整逐点回读账本、自向量查询结果、候选元数据、候选配置和 Local05 回滚配置。候选配置的 `selectionStatus` 为 `candidate-not-active`，`runtimeCompatibilityVerified=false`。

**不能仅改 URL 就称 Local05 的旧运行配方兼容。** 原签名绑定 embedding／tokenizer／窗口／源／算法代码；本轮 `server.py` 接缝会改变代码身份。迁移元数据保留原 embedding 签名，并独立记录新 Server 后端身份。正式运行入口须另外验证这两层身份及实际语义查询，不绕过旧 signature guard。

## 本次实际结果及证据

外部忽略证据根目录为 `C:/Users/Administrator/Documents/Codex/consense-implementation-20261013/qdrant-server-migration-01/`，实际运行 `actual-02/`。原件、数据库、模型和 Server 二进制不进入 Git。

| 检查 | 实际结果 | 可复算依据 |
|---|---|---|
| 原冻结清单 | SHA `deb87c356c2a724f71d8bc316d86c4272fa9ebe014fb2bedf4c18de29a425aab` | `actual-02/migration-receipt.json.frozenManifest` |
| Server／client | 1.19.2／1.19.1，WSL Ubuntu 24.04 CPU，独立 loopback 16333 | 同收据 `serverVersion`／`clientVersion`／`target` |
| 完整父记录 | 19,371 | `parentCount`；冻结 metadata `chunks` 及逐窗覆盖复算 |
| 向量窗口／维数 | 20,700／1,024，unnamed Cosine | `vectorWindowCount`／`vectorDimension`／两侧 `VectorIntegrity` |
| payload／父子来源映射 | 全点精确相等，所有父均有完整窗覆盖 | `fullPointLedger`；`validate_metadata()` 与 `inspect_points()` |
| float32 向量字节 | 20,700／20,700 哈希相等 | `exactFloat32Vectors`；两侧 `pointsSha256=970c8e0e6ef36f57a4fbe5a26106e6225a3df5fd9b87d10afd9c001883c112a5` |
| JSON 数值比较 | 最大绝对差 `1.489219664252417e-08`；最大 cosine delta `6.661338147750939e-16` | `maximumVectorDifference`／`maximumCosineDelta`；逐点账本 |
| 实际 Server 查询 | GCC／GCT／SL 各一次自向量查询，源点均在 Top10 | `storage-query-probes.json`；这是存储查询，未重新编码业务问题 |
| 有界原件／表资产回取 | 3 份原始 PDF、2 份完整逻辑表 JSON，均与冻结 SHA 相等 | `originalAndTableReadback` 的 5 个 descriptor |
| 原 Local 持久字节 | 前后不变，活动配置未切换 | `allOriginalPersistentStateBytesUnchanged=true`／`activeConfigurationChanged=false`；`copy-provenance.json` |
| Server 正常停止后冷启 | 全量 20,700 点／payload／float32 seal 再通过；原 Local05 health 正常 | `cold-server-readback-receipt.json.fullColdVectorIntegrity`／`activeLocal05Health` |
| 实际迁移 wall | 89.779316 秒 | `wallSeconds`，仅本机本次存储迁移，不作集群性能预测 |
| 契约／持久完整性回归 | 85 tests 通过，0 failure／skip；其中 9 个新边界检查 | `storage-regression.log`；`test_migrate_qdrant_server` 与既有 `test_index_reuse_integrity` |

本次完整迁移收据 SHA 为 `453ae069936cd32bc0b8350ad24e70e691007d0140c13c0726d3141843d66443`。float32 编码哈希与 JSON 解码后数值容差是两项独立检查：此批实际 float32 字节全部相同，不把小数值差或容差通过改写成其他域的精确证明。Server 的 Cosine 再归一化允许产生微小 float32 差异时，也必须在逐点账本保留不相等的哈希与有界数值结果。

正常 SIGINT 停止自有 Server，再以相同新 storage/config 冷启动，调用既有 `verify_reuse()` 对候选 metadata 的完整点／payload／seal 再校验。冷启动收据 SHA 为 `a06b4eb3c1c181d51f0fe33220fea51397840c516e4297b83093157479ecff87`，驱动与日志保存在同一外部目录。本轮仅停止自有二进制／config 对应的精确 PID；Local05 未停止。正常停启不等同主机掉电、损坏恢复或 PVC 验收。

## 03:00 补充：真实自然语言 CPU 查询，保留召回限制

复用上述 Server 1.19.2、config、storage 和 `oer_server_baseline05_20261013_02`，只恢复自有候选；没有新建／修改 collection 或连接 Local05 数据库。独立分支源码基于 `429c6b7`，查询驱动和运行证据保存在外部 `C:/Users/Administrator/Documents/Codex/consense-implementation-20261013/qdrant-semantic-query-02/`，不新增业务代码或依赖。

实际运行使用已核82包的 Linux venv `/home/ocr/.local/share/consense-oer/retrieval-offline-strict-20261013/venv`，Python 3.12.3、Torch 2.11.0＋cu128、Transformers 4.57.6、Sentence Transformers 5.2.0、Qdrant client 1.19.1。相同冻结 BGE-M3 publication SHA `13c9d08ae6e1d3d73fb6d284d473f493446c4a276c864cf5a02338ded3552a15` 的完整实际文件、tokenizer backend 与原索引身份核验通过；调用现有 `server.encode_embeddings()`，CPU FP32、batch 1、512 token cap、无隐式截断、归一化1024维。原索引的 CUDA FP16／batch16 身份保留，不能把本次 CPU 查询当作原运行配方兼容证明。

三个问题在模型调用前写入 `query-plan.json`，没有 gold、目标父／页／结果提示。通过正常 `query_points()` 提交真实 query embedding；每题窗口50→100、最多50个父候选，复用现有父最大值聚合与 observed source-unit 选择。每题返回10个完整父记录及完整观察单元、Parts／来源／质量状态；dense score 不代表条款正确、适用性或缺陷概率。本轮没有 BM25、reranker 或 LLM 调用，也没有调用 Java 业务原文 GET。

| 实际自然问题 | 完整输入 tokens | 实际 dense 结果及限制 | 完整结果证据 |
| --- | --- | --- | --- |
| 意外现场条件延误后如何申请工期延长 | 21 | Top1 GCC 8.4，原件P38；返回GCC／SL。可观察到相关条款，未作gold评分 | `query-1-full-readback.json`，SHA `46232ca8379bf16994c3be8e8d0abed82cf17bf47c84315cfd3070a683b37365` |
| 接受承包商投标前检查哪些提交文件和资格 | 24 | GCT原件P1提交清单rank5；Top1为SL电气提交要求P2845，排序混入不同业务语境 | `query-2-full-readback.json`，SHA `8cba003bd8db54aeaf4384b2de97881a4417a0e72645bb7a1036180e55c9866e` |
| 批准真空污水管安装和测试前检查什么 | 17 | Top1为DEM2石棉处理定义P2624，偏题；排水测试P1898／1896／1938在rank3／5／7。不能称完整技术条件／整表语义召回通过 | `query-3-full-readback.json`，SHA `7a61abe80a2655205d191a63039ce31f582026af72bbb8b030e78df2769a496d` |

全量19,371父／20,700窗口身份与原冻结corpus／window manifest匹配；真实查询每次完整窗口payload与冻结记录精确相等，返回30个完整父／观察单元的原件路径与SHA匹配。查询前后再次全量读取实际20,700点／payload／float32，`pointsSha256`均为 `970c8e0e6ef36f57a4fbe5a26106e6225a3df5fd9b87d10afd9c001883c112a5`；不是仅count或sample核验。三份原始PDF和两份已采用逻辑表JSON的实际全文件SHA也与冻结声明相等；后两份是资产完整性回取，不宣称本次问题命中它们。来源完整性和 `complete_observed_unit` 不改变源质量、条件／Notes／法律适用的unknown。

实际完整收据 `natural-query-readback-receipt.json` SHA为 `aa3f1a273ba0356724da5809493c66487e3a188e34a9cc1af9bf48fb2c29fd57`；含query vectors、每题完整输出descriptor、模型publication／实际加载事件、软件／包／CPU与原CUDA配方、前后全seal及五项原件／表资产SHA。实际 wall 76.153123637秒，包含完整校验／模型装载／查询及文件读回，不作查询性能基准。loopback连接178，外联尝试0、GPU context0、模型下载0；此次允许加载既有BGE实际CPU权重。

自有启动／停止identity位于相邻 `qdrant-semantic-query-01/`：精确确认二进制／config的PID1683227后SIGINT正常停止，16333无监听，未向其他PID发信号。`protected-before.json`／`protected-after.json`记录8876 Local05 owner79196、18868 retrieval child40200、18083 backend owner110356的身份保持，Local05 health为ok；活动客户端配置SHA前后相同。首轮驱动错误地把Local seal直接用于目标metadata，在模型／查询前被拒绝；其失败收据与原驱动保留于 `qdrant-semantic-query-01/`。第二轮明确验证迁移收据中的目标seal，并按迁移仅变更collection与seal的事实检查原始来源身份，不修改冻结manifest。

## 04:21 补充：正式 HTTP 与 Java 只读迁移验证

`migrated_qdrant_index.py` 是一个显式读入口：必须同时配置 `CONSENSE_QDRANT_MIGRATION_RECEIPT`、`CONSENSE_QDRANT_MIGRATION_RECEIPT_SHA256`、精确 `CONSENSE_QDRANT_URL`，并提供原冻结两模型 publication。未配置保留原 Local 路径与原 signature guard；不自动切换目标。配置后拒绝 `/index`，只读取收据绑定的一个 project/collection；不连接原 Local 数据库，不下载或复制模型。receipt、冻结 manifest、候选 metadata 每次按完整字节重新核对 SHA，19,371 父／20,700 窗覆盖和每个实际点的完整 payload／float32 seal 通过后才进入现有检索链。

历史 indexSignature 保留为 `b0a7bc46bb241f9acbace0bab75a648c83db3ae339c79e68c4d689f1b635f64b`。当前 CPU FP32／batch1 的 queryIdentity、queryRecipe、queryRuntimeSignature、全部 helper 源码 hash、target URL/collection 和实际全点 seal 通过独立 `indexAccess` 返回；`runtimeCompatibilityVerified=false`。模型／tokenizer 的完整文件、backend、两 publication、窗口和完整 source 均与冻结声明核对；不同设备的查询不会改写旧 GPU 身份。已创建的 backend 绑定 STATE／URL／API key，后续漂移或无已知配置的迁移缓存被拒绝，不关闭或重新打开其他 owner。

Java `FixedBaselineRetrievalClient` 增加四个可选配置：`consense.fixed-baseline.migration-receipt-path`／`migration-receipt-sha256`、`query-runtime-path`／`query-runtime-sha256`，并把 `base-url` 明确设为自有 API。任一配置出现就必须完整绑定；默认仍要求原 Local／CUDA health。query manifest 的 schema 为 `consense.migrated-server-query-runtime.v1`，包含该 migration SHA、精确 query API URL、target URL/collection、原 indexSignature、独立 queryIdentity/queryRecipe/queryRuntimeSignature、实际 fullVectorIntegrity、实际 health、`readOnly=true`、`runtimeCompatibilityVerified=false`。它来自自有运行实际观察；源码或配方切换需要新 runtime、新 manifest 和外部 SHA。Java 核对两阶段 SHA、原 corpus／metadata／model publication、完整 helper 闭合和独立模型／tokenizer／配方，再保留现有完整父、完整 source-unit、原件 SHA、整表和质量 unknown 的所有检查。

原 Local seal 正常记录两份精确表示：`pointsSha256=2c98c363a7b695160595e08f5af9d7506bda8e3a2cd7034ff87281c6e132b227` 对应 warm Cosine 归一化暴露值，`persistedPointsSha256=970c8e0e6ef36f57a4fbe5a26106e6225a3df5fd9b87d10afd9c001883c112a5` 对应冷启动存储 float32。`index_integrity.verify_reuse()` 已明确分别验证这两份正常索引记录。此候选读模式强制 migration source 的完整六字段 seal 与原冻结 scalar＋persisted hash 相等，target seal 与 source 全字段精确相等；没有用 count/schema/容差代替向量身份，没有重写 warm seal。迁移工具允许有界 Cosine 再归一化差异，但此严格读模式不接受产生不同 float32 seal 的候选。

首次正式 Python HTTP 三问复用了原 `WindowRuntime.retrieve()` 的真实 BGE dense、全 19,371 父 BM25、RRF、真实 reranker、完整观察单元选择。外部证据 `qdrant-http-retrieval-01/formal-http-readback-receipt.json` SHA `ebfd602f4c4a0a110a53be617f3449d2614bb4fd2dd952962c851f8c2ca166f2`，当前 helper 源码快照与 tracked diff 保存在同目录 `initial-python-source/`／`initial-python-tracked.diff`。每题返回十个完整父／单元，实际 rerank 全序列与闭合重新计算相等，全 20,700 点前后 seal 不变。三原 PDF／二逻辑表 JSON 的完整 SHA 再通过；尚未进行 Java 业务原文 GET。

| 正式自然问题 | 实际 Top1 | 实际观察与限制 | 首轮完整证据 |
| --- | --- | --- | --- |
| 意外现场条件延误后申请工期延长 | GCC P38，score 0.9746729135513306 | 观察到工期条款，未作 gold 必要证据评分 | `formal-query-1-full-readback.json` SHA `7a706d0c3eb0f2d890faa359db22016e60d01373413c36b00070e01687d99441` |
| 接受承包商投标前的文件和资格 | GCT P1，score 0.3339497148990631 | 投标提交清单排首；不证明所有资格／例外完整 | `formal-query-2-full-readback.json` SHA `c2bfde33cba6c99b5608345a570c059f7f96972ae8c6f971435fba538f352e40` |
| 真空污水安装和测试审批 | SL ELE23 P3199，score 0.20684053003787994 | Top1 是电气 VISUAL INSPECTION，rank10 为 DRA1 P1898 W-trap 冲洗测试；跨专业语境混杂，不能验收真空污水条件或整表语义召回 | `formal-query-3-full-readback.json` SHA `ef948af6e889dd0c06fbe3a4df7efda108aa2d975d717523fbcf12a720026063` |

两次实际 Java 前门失败完整保留：`qdrant-http-retrieval-02/formal-http-readback-receipt.json` SHA `c3a31f7d765ca899ab2e46530101f9a9d9f7d62c3648bfdf839c5af01b86a19a`，错误地将 target cold seal 强制比较到原 warm hash；`qdrant-http-retrieval-03/formal-http-readback-receipt.json` SHA `2771af67f3d034496a946909037f1075446921af5dbbb83b1a587b4e4ba5a303`，原 Java normalized numeric digest 把整数转科学记数，与 Python 整数 JSON 不同。两者均在 `/retrieve`／权重加载前被拒绝，自有 API 正常停止。修复分别保原两 seal 表示、强制 source cold＋target exact，以及仅给 queryIdentity 采用保持整数的 SHA；不改原父 payload normalized digest。`token_windows.digest()` 使用 UTF-8、排序键、紧凑 JSON、保留整数编码；Java `queryDigest()`／`signatureJson()` 同样保留整数，拒绝该 query schema 外的浮点节点。原 `normalizedDigest()` 会把 2271145830 转为科学记数，因此不用于这一独立 query 签名。包含 10 和大整数 1000000000000000019884624838656 的实际 Python 金值 SHA `1f9b27100148c6e0e18d728e25673b67120895fb703d61ac59c390ed2b963b49` 已列入契约并由独立 Java 控制复核。

固定候选源码为独立分支 `486d63e`（基于 `fd80ecb`，前置 `2a5e203`）。四个相关 Java 测试类 30 tests（14＋5＋8＋3）通过，Python 116 tests 通过，均 0 failure／skip。作者之外的 stdlib 独立探针 43 项、15 个实际内存点／3 完整父通过；另 29 项独立 Java 真实 05／03 pin 只读控制通过，包括 Python 整数金值、完整两 publication／cold source seal、repin 错向量／target／URL端口／helper／模型／tokenizer／digest 的拒绝。两组真实 DB／HTTP／model 均为 0；独立收据分别为 `reviewer-qdrant-independent-01/independent-qdrant-review-receipt.json` SHA `753b32f1a5c5f58ec65838fe300b7c740b00cf9eeed1c50e19c8db626ba61266`、`independent-java-migration-receipt.json` SHA `461190fdf1c68b451a2af5f60470fa253341fe95b825a1c57c8ddc8112a1250a`。这些代码验证不等同真实候选查询验收。

最终实际 Java 验证位于 `C:/Users/Administrator/Documents/Codex/consense-implementation-20261013/qdrant-http-retrieval-04/`。外部 `MigratedJavaReadback` 调用现有 `HttpSupport` 和 `FixedBaselineRetrievalClient`，以 Vetting／Advisory／Vetting 三个模块调用发出这三条固定自然问题，实际 `/retrieve` 均 HTTP 200；每题十条完整 Java 证据，完整 model material、`assertCurrent()` 和每条父 `readOnlyHitById()` 原件校验通过。未调用 Java 业务原文 GET 路由。所有候选软件来源是该独立 worktree 的固定 `486d63e`，十个相关 compiled class 的调用前 SHA 与调用后精确相等，source root、class root、harness/source/class、classpath SHA 和全部 Python helper SHA 入收据；root 的 `target` 未改动。

| 最终实际项 | 精确结果 | 可复算证据 |
| --- | --- | --- |
| 完整收据 | SHA `392b160fbb0d28d7bb91495f5ffc254a3547bc07cd342a224243ebf7e91bb431` | `formal-http-readback-receipt.json` |
| 独立 query manifest | SHA `8c5af11a8f3a0d02c6eef27e4b45b464fb09b8fb1e2845f3b0ccd801ce5d0405`；queryRuntimeSignature `2450c3a2131f56ceb5357484640570f50e6711b82482f3b6e8b6df6d19208997` | `query-runtime-manifest.json` 与返回 `indexAccess` |
| 问题一／二／三完整输出 | SHA `70c3311c0fe6be3ea565c92770aabbfa14748b73ee57525a0a1a233203278120`／`601a64872f9588519251281d8f5c85c3d7abb989c4dd9aaa1584d23deeeadeb6`／`76d304d8793578e3ed8a93fd6e0d72d4dc0bdfd77f3e93b9a02779ea63ab52e3` | `formal-query-{1,2,3}-full-readback.json`；Top1／score 与首轮一致 |
| 每题 BM25／RRF 父池 | 每题全 19,371 父 BM25，50 个实际 rerank 父 | `parent-bm25-candidates.allScopeScores`／`parent-rrf-candidates.submittedParentIds`，完整 `formal-stage-events.jsonl` |
| 实际 embedding 输入 | 21／24／17 tokens；CPU FP32，batch1，1024 维 | 三个实际 tensor tokenizer 事件与模型加载事件 |
| 实际 reranker 窗口 | 108／62／109 完整 pair 窗，均最大 512 tokens；CPU FP32／batch1，无隐式截断 | `rerank-window-scores.pairsInActualInputOrder`、282 个完整 tokenizer 事件（含三个 query 输入） |
| 完整来源单元 | 每题 10，合计 30 个 `complete_observed_unit`，完整实际 rerank 序列闭合复算精确；质量／法律／语义 unknown 保留 | 三份 `response.sourceUnits`，Java `java-full-evidence-{1,2,3}.json` |
| 原 metadata／corpus | 227,118,049 bytes／SHA `0ce8c7cf6df5c196b5dc01fe9013412e7db5fe4692641da5375f176e85be927b`；154,427,626 bytes／SHA `e90e2db16d4cc45989cadbc0c16154810c473a2479e57e3c32bfd54aa70cf6c4` | 完整收据 `originalPersistedMetadataReadback`／`frozenCorpusReadback`；Java 初始 corpus 全文件扫描 |
| 全点前后 | 全 20,700 点／payload／float32，前后 seal 均 `970c8e0e6ef36f57a4fbe5a26106e6225a3df5fd9b87d10afd9c001883c112a5`；三 query 及初始化每次读入口均全点再验 | `fullVectorIntegrityBefore`／`fullVectorIntegrityAfter`，四个 `migrated-server-index-read` 事件 |
| 原件／表资产 | 3 原始 PDF＋2 全逻辑表 JSON 的实际全文件 SHA 再通过；未声明问题命中这些表或通过质量验收 | `originalAndTableReadback` 的五个 descriptor |
| 只读业务入口 | `/index` 409；`/delete`、`/upsert` 404；活动客户端配置 SHA 不变 | `indexWriteRejection`／`otherMutationRouteRejections`／`activeClientConfigurationUnchanged` |
| 模型／网络 | 两次真实模型加载，CPU FP32；worker 外联尝试 0、loopback 347、CUDA 未初始化，LLM 调用 0 | `worker-final-state.json`／实际模型事件；Java URL 由双 pin 精确绑定 |
| 自有停止 | API PID1691321 SIGINT、exit0；精确 binary/config/cmd 核对后仅 SIGINT Server PID1686661；16333／18878 无监听，Windows signal 0 | `ownedApiStop`／`owned-server-stop-receipt.json`／`protected-during.json`／`protected-after.json` |

最终 wall 365.874536731 秒包含全量校验、初始化、三次模型检索、Java 完整证据／原件读取与收据验证，不作性能基准。实际 HTTP `response.seconds` 分别为 79.84909894596785／28.97738278703764／77.1563968800474；每题外部输出 `wallSeconds` 是**事后结果核验耗时**，不能冒充检索耗时。完整 stage 的可重算摘录另存 `actual-stage-summary.json`。本证据绑定上述独立源码／class／模型／CPU配方；之后 root 的共享 Local helper 修复／源码集成需要新的 query 身份和 pin，不把旧 manifest 当新软件证明。其后单 Local owner 的恢复由 root 另行记录；03:00 的旧健康快照不证明 03:47 或本次后的 owner 连续存活。

## 尚未过门与回滚

`semanticModelQueryExecuted=true` 指上述限定三题的实际 CPU dense＋BM25＋RRF＋reranker 查询与 Java 只读桥；`semanticModelQueryAccepted=false`、`runtimeCompatibilityVerified=false`、`targetAdopted=false`、`businessAccepted=false`、`H800Accepted=false` 保持真实状态。完整现有检索链及 Java 客户端已实际执行，但第三题仍跨专业混杂；必要证据召回／整表质量／法律条件和适用性、跨设备语义采用、Java 业务原文回取路由、业务前端流程及目标 PVC 冷启持久性未由此测试证明，**AC08 尚未整体关闭**。

WSL `/mnt/c` 启动明确警告文件系统不可确认数据安全；这是本机存储兼容试验，不能充当 PVC／生产耐久性验收。正式部署保持一个副本、一个 storage owner，Pod 更新应确保旧 owner 退出后新 owner 再启动；RWO 本身不保证进程唯一。

当前仍选择 Local05 的 `127.0.0.1:8876` 入口。回滚配置保留原冻结 metadata、collection 和状态目录；通过原有服务访问，不另开一个 Local client。候选未切换，因此没有执行线上切换／回滚演练。既有 Drafting、活动服务、原件、模型及冻结索引保留。

失败证据保留：首个实际尝试因原 owner 锁文件不可读在复制／目标 collection 创建前失败，收据位于 `actual-01/`。首个 Server 启动使用 `.json` 配置扩展，配置 loader 未加载而采用默认配置；精确确认并停止该自有 PID 后，将新建的空存储移到 `unconfigured-first-start-storage/`，改用已实际加载的 `.yaml`。正确启动禁用 telemetry 与 gRPC，只监听 loopback；没有打开或停止 Local05。
