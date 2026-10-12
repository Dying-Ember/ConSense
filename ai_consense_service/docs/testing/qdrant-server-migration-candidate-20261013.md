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

## 尚未过门与回滚

`semanticModelQueryExecuted=true`仅指上述三个实际CPU自然问题；`semanticModelQueryAccepted=false`、`runtimeCompatibilityVerified=false`、`targetAdopted=false`、`businessAccepted=false`、`H800Accepted=false` 保持真实状态。dense召回已观察到偏题；现有完整BM25／rerank检索链、运行配方兼容、业务原文回取路由、完整语义质量及目标 PVC 冷启持久性未由此测试证明，**AC08 尚未整体关闭**。

WSL `/mnt/c` 启动明确警告文件系统不可确认数据安全；这是本机存储兼容试验，不能充当 PVC／生产耐久性验收。正式部署保持一个副本、一个 storage owner，Pod 更新应确保旧 owner 退出后新 owner 再启动；RWO 本身不保证进程唯一。

当前仍选择 Local05 的 `127.0.0.1:8876` 入口。回滚配置保留原冻结 metadata、collection 和状态目录；通过原有服务访问，不另开一个 Local client。候选未切换，因此没有执行线上切换／回滚演练。既有 Drafting、活动服务、原件、模型及冻结索引保留。

失败证据保留：首个实际尝试因原 owner 锁文件不可读在复制／目标 collection 创建前失败，收据位于 `actual-01/`。首个 Server 启动使用 `.json` 配置扩展，配置 loader 未加载而采用默认配置；精确确认并停止该自有 PID 后，将新建的空存储移到 `unconfigured-first-start-storage/`，改用已实际加载的 `.yaml`。正确启动禁用 telemetry 与 gRPC，只监听 loopback；没有打开或停止 Local05。
