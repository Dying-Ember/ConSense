# 原生 DOCX 的完整 document collection 分批契约

本轮增加一个独立 Python 传输模块，使一份实际采用的原生 DOCX 继续拥有一个 `documentId`、`sourceHash` 和 `projectionRevisionSha256`，用有序批次传递完整结构单元。新增文件为 [`native_projection_collection.py`](../../tools/vetting_eval/native_projection_collection.py) 与 [`test_native_projection_collection.py`](../../tools/vetting_eval/test_native_projection_collection.py)。旧 producer、单 snapshot transport、扫描入口、registry 和运行服务没有在本子任务中修改。

这解决的是有界传输契约。当前真实 Java authored DOCX／独立 reference／明确 adoption 已验证三批往返；完整项目 PRE 还没有建立本轮真实 reference／adoption、出版 collection、接入神经模型或接受原件质量验收。Java／Python registry／业务消费和浏览器的接线状态需要读取其各自最新验证记录，不能从本模块测试推定。

## 分批边界与身份

- root schema 为 `consense.native-document.v1`，batch schema 为 `consense.native-document-batch.v1`，版本为 `whole-native-units-ordered-cas-document-v1`。
- root 的 `projectionSha256` 是 collection root 的 seal；`publicProjectionSha256` 是完整旧 public 对象的 seal。两者不是同一个 SHA。
- 全部批次保存同一份旧 public 的 `documentId`、`sourceSha256`、`sourceHash`、`projectionRevisionSha256` 与 `publicProjectionSha256`。改变装箱上限可能改变 root seal，但不能改变这些全局来源身份。
- 依据旧 public 的实际 source order，按完整 adopted unit 贪心装箱。`table_group` 的全部 Parts、blank／nested membership、原生表拓扑、文本通道、编辑状态、UTF16 位置和关系均保持原样。单元内不拆块、不删空格／空段、不逐 cell 合并两路候选。
- 每个 batch 带该批次所有 unit 的完整 `unknowns`，与 chunk 源序一一对应。当前 native producer 每 unit 一条 unknown record；缺少、重复、次序不符或额外无 unit 的 unknown 直接拒绝。全局原生限制仍通过 root 的 `limitations` CAS 引用保留。
- batch 上限可以调低用于协议测试，必须是正整数且不超过 **16,777,216 bytes**；root 自身也不能超过这一硬上限。完整单 unit 超限明确报 `native_collection_unit_too_large`，不提高 cap 或把单表拆成多个伪 document。
- 此接口不注册来源。调用方必须把 root 作为同一份 document 的一次完整 revision；不能逐批注册、只取最新一批或者把传输批次当作独立业务文件。

JSON 使用现有 canonical UTF8 编码：排序 object keys、无多余空白、保留 Unicode、禁止 NaN。seal 等于去除 `projectionSha256` 后 canonical bytes 的 SHA256，actual artifact descriptor 的 SHA 等于含 seal 的实际 canonical bytes 的 SHA256。

## root 与 batch 的内容

root 包含完整 `source`、`projectionIdentity`、`observerIdentity`、`nativeObservationQuality` 和明确的未验证质量／视觉状态。`manifestMetadataShape=manifest-without-units-tableGroups-relations-assets-v1` 明确声明 `manifestMetadata` 不含 `units`／`tableGroups`／`relations`／`assets`；其余 source、scope、quality、role、module、eligibleModules、purpose、class、reference 均原样保留。完整 manifest 的 `manifestSha256` 另行绑定，不能把 compact metadata 冒称完整采用清单。

`headerRef` 绑定实际 CAS 中的完整旧 public header（旧 public 除 `chunks` 外的全部字段，包含原 seal）。header 可以大于 16 MiB，因此不提供“直接 HTTP 获取整个 header 就算有界读取”的承诺。`envelopeRefs` 使用该 descriptor、精确 JSON pointer 和 payload hash 保存 full manifest、canonical、全部 unknowns、limitations、nativeObservationRef、nativeCaptureRefs、sourceReferenceRef、captureCompleteness 及 nativeObservationQuality。

`canonicalClosure` 包含全源 unit 数、完整有序 unitId list hash、完整 canonical commitments hash、关系数／hash 和 canonical 整体 hash。所有跨 unit／跨 batch 关系留在原 header 和各原 chunk 中；分批没有重写或裁剪关系。

每个 root batch descriptor 保存 `ordinal`、`firstUnitOrdinal`、`unitCount`、有序 `unitIds`、`unitOrderSha256`、batch seal 与实际 artifact descriptor。这样调用方可精确找到一个 unit 所属 batch，根闭包仍覆盖全部采用单元。root 数量或 unitIds 太多而导致自身超限时也明确拒绝；本轮未实现再一层 root 页化。

batch 保存相同全源身份、ordinal、firstUnitOrdinal、unitCount、unitOrderSha256、完整 `chunks`、完整对应 `unknowns` 和 seal。批次仅承担传输和精确路由，条款／整表单位仍是业务结构边界。

## 发布与每读复验

接口为：

```python
publish_native_collection(internal_projection, *, artifact_store,
                          max_batch_bytes=16777216)  # -> bounded root
validate_native_collection(collection, *, artifact_store)  # -> same root
hydrate_native_collection(collection, *, artifact_store)  # -> complete old public, internal memory only
read_native_collection_batch(collection, ordinal, *, artifact_store)  # -> bounded batch
read_native_collection_root(descriptor, *, artifact_store)  # -> bounded root
native_collection_descriptor(collection)  # exact root-byte descriptor only, no admission claim
```

发布先校验实际内源投影及其原 ZIP、全部 raw parts、observer export、token／native inventory、独立 reference 和完整 adoption。使用未改动的旧 `native_projection_transport._assemble` 生成完整 public，不调用旧单 snapshot 的 `_size`；随后先计算整个分批 layout，确保所有 root／batch 大小门控通过，再捕获 header／全部 batches／root。失败可留下已有原生 capture／internal bytes／代码 bytes 的证据，不会出版一个半有效 collection root。

每次读取 root、hydrate 或任一 batch，都重新读取并校验：

1. collection 实际 code bytes 与当前模块，以及精确 descriptor 形状；transport 实际 code bytes 与当前未修改实现，以及精确 descriptor 形状。
2. actual CAS 的完整 internal view，重新 `validate_native_projection`；由此核对实际原 ZIP、observer、所有 parts／nodes／tokens／表 inventory、reference 和 adoption。
3. 通过旧 `_assemble(..., capture=False)` 精确重建完整 public，再重建 header／全部 batch／root。
4. 与调用方 root 逐字段一致，并对实际存储的 header、每一个 batch 和 root 做 bytes／SHA／byteCount 比对。

因此即使只读取第 0 批，第 2 批丢失或原 ZIP 被修改也会拒绝。该路径没有 validation cache；`capture=False` 永不创建缺失 CAS。`hydrate` 返回的完整旧 public 仅供进程内消费，可能超过 16 MiB，不能直接交给旧单 snapshot validator、旧 HTTP wire 或宣称它也满足有界传输。

## 实际 authored 验证

当前冻结 fixture 位于本机忽略目录 `.local/state/oer-native-document-collection-20261010/authored-031512-33c6d4/`。它使用既有 Java observer 对实际 authored OOXML 包生成的 capture，并使用与候选文本分离的 reference 和明确 adoption。documentId 为 `42`，不是比赛 PRE 的原件接受声明。

| 材料 | 实际字节数／结果 | 直接来源 |
| --- | --- | --- |
| root | 18,024 bytes | `fixture-receipt.json.rootBytes`、`native-document-root.json` |
| 完整旧 public | 90,153 bytes；hydrate 全字段一致 | `fixture-receipt.json.fullPublicBytes`、`full-public-internal-memory-only.json` |
| 完整 header CAS | 15,747 bytes | `fixture-receipt.json.headerBytes`、root.headerRef |
| 批 0 / heading | 10,405 bytes | `fixture-receipt.json.batchBytes[0]`、`native-document-batch-0.json` |
| 批 1 / whole table | 57,603 bytes | `fixture-receipt.json.batchBytes[1]`、`native-document-batch-1.json` |
| 批 2 / header | 9,114 bytes | `fixture-receipt.json.batchBytes[2]`、`native-document-batch-2.json` |
| 表组 | 2 native tables、5 paragraph Parts，包含 3 blank Parts | 同 receipt 的 wholeNativeTableCount／wholeTableParagraphCount／blankPartsRetained |
| 跨 batch parent | table 指向 heading；原完整关系 SHA 不变 | root.canonicalClosure、完整 header、table chunk.relations |

root actual bytes SHA 为 `7b4f58c8539b0dc1e8ba5cead57943cca62cd9d2d533f967e7782c6625c153ef`；root seal 为 `f04f569b311552dfd2b89dee01be0f487ae6b99764fb57327e7d97a97279a990`；完整旧 public seal 为 `bb405b6a824257a732f8f7fbf0f6cb2285c1ea26dc104f60e721f4c366e80452`。后者与既有 Java public fixture 的投影身份一致。

同一 authored fixture 的 full-CAS 冷读取时序：root **0.056859 s**，三个 batch 分别 **0.053079 / 0.056525 / 0.051765 s**，hydrate **0.053060 s**。这是很小的本地协议 fixture 单次工程计时，包含每次全源复验；不能外推完整 PRE、吞吐、质量或 H800 性能。直接来源为该目录 `fixture-receipt.json.timingsSeconds`；receipt bytes SHA 为 `1db7cbcc0e92d2af09e8c011475de2d733f45ddd5d742587edb2a98f4f423aa5`。

最终无模型的 Python 契约统一运行 **91 tests，0 failure／error／skip**，22.814314 s，源 SHA 前后一致；这是新 collection 21 tests 与既有 native 70 tests 的单轮计数，不与较早重叠的 90／115 项轮次相加。直接 receipt 为 `.local/state/oer-native-document-collection-20261010/run-031525-0fa4d8/validation-receipt.json`，SHA `5d26d5f9b9018de9b34475635c67adc7204bbbb403027988269b57dbd24207f9`，全部 test 名及输出在同目录 `test.log`。

新 21 tests 覆盖真实 CAS roundtrip、默认单批／三批、完整 blank／nested 表和跨批父条、compact manifest 明确声明、完整 unknowns、单 unit 精确上限及下一 byte 拒绝、禁止提高 cap、失败不出版 root、actual root descriptor、掉批／重复／错序／错 unit hash、不同 document revision 批混入、实际修改 batch／header、全 families 实际 CAS 改字节、读时不修复、缺最后批使首批读取也失败、原 ZIP 缺失、质量升级／未知字段拒绝、code descriptor aliases，以及独立纯字节容量探针。

初轮 18 项有一处纯 sizing fixture 未同步新 `unknowns` 闭包而报错；fixture 已修正，生产门控没有放宽。随后 20 项 focused run 与 90 项统一 run 都通过；新增 code descriptor alias 拒绝后形成上述最终 91 项。本机 validation receipt 保留该失败的命令、测试名、异常与归因；较早 run 和 authored fixture 不覆盖、不当当前代码。

当前 collection 源码 SHA 为 `379b57f558f12a3efc6ca35c8b3aa5bc92ee426e26d078ce4161a7978d75e4a7`；旧 transport 源码仍为 `8f0b2986c69e9475bebf9cd4be094085115c4d1b81cf767f34f10de1645dbf51`。

## PRE 容量与尚未通过的门槛

已核对旧容量 receipt 的当前文件 SHA 为 `66087b33f5cfe0739f29b17f29c66a75780ebca8282cc76de51ce6e3f7e758da`，位置为 `.local/state/oer-native-transport-20261010/capacity-only-024621-ba36c6/capacity-receipt.json`。其中实际 PRE 的 mandatory Parts＋完整 topology 下界为 **139,376,543 bytes**，最大完整原表组的同类下界为 **8,326,637 bytes**，完整原生 bundle 当时 cold validation 为 **17.614 s**。这些是旧 receipt 中实际 bytes 的容量／时序记录，本轮没有重新建立 PRE reference／adoption或运行 PRE collection。

139 MB 下界说明单 snapshot 无法传输；分批可以保持完整 adopted unit 和单一全源身份。最大表组 8.3 MB 只是下界，未包含 content、sourceSpans、adoption、relations、unknowns 和封装字段，不能据此保证该表组最终完整 chunk 一定小于 16 MiB。若实际完整单元超限，当前实现会拒绝，仍需另设计保留完整组的有界资产读取，不能切碎或删字段冒称已通过。

当前每 batch 冷读会再次验证完整来源。这使少量高完整性查验可复算，但对数百 MB 来源重复读取的成本必须实际测量并单独记录。没有缓存的安全基线不等于性能最终方案；本轮没有用缓存跳过原件／采用复验，也没有把较小 fixture 的毫秒级时序替代 PRE 成本。

完整 PRE 原件采用、registry／Java 单 document collection 接线、实际 BGE／MiniMax／浏览器、完整来源质量与 official Advisory 8 题／Vetting 结构化报告仍需各自实际执行。当前无权重训练、无神经模型调用、无服务启动／切换、无在线 dist 修改、无 H800 发布包或性能证明。官方答案继续评价隔离，`humanConfirmed=false` 与未核验的视觉状态保持。
