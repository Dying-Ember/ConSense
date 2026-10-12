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

## 尚未过门与回滚

`semanticModelQueryAccepted=false`、`targetAdopted=false`、`businessAccepted=false`、`H800Accepted=false` 保持真实状态。实际语义问题编码、现有检索／rerank／完整父证据输出兼容、业务原文回取路由及目标 PVC 冷启持久性未由此测试证明，**AC08 尚未整体关闭**。

WSL `/mnt/c` 启动明确警告文件系统不可确认数据安全；这是本机存储兼容试验，不能充当 PVC／生产耐久性验收。正式部署保持一个副本、一个 storage owner，Pod 更新应确保旧 owner 退出后新 owner 再启动；RWO 本身不保证进程唯一。

当前仍选择 Local05 的 `127.0.0.1:8876` 入口。回滚配置保留原冻结 metadata、collection 和状态目录；通过原有服务访问，不另开一个 Local client。候选未切换，因此没有执行线上切换／回滚演练。既有 Drafting、活动服务、原件、模型及冻结索引保留。

失败证据保留：首个实际尝试因原 owner 锁文件不可读在复制／目标 collection 创建前失败，收据位于 `actual-01/`。首个 Server 启动使用 `.json` 配置扩展，配置 loader 未加载而采用默认配置；精确确认并停止该自有 PID 后，将新建的空存储移到 `unconfigured-first-start-storage/`，改用已实际加载的 `.yaml`。正确启动禁用 telemetry 与 gRPC，只监听 loopback；没有打开或停止 Local05。
