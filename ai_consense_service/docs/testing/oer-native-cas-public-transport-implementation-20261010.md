# 原生 DOCX compact CAS public transport 与容量验证

日期：2026-10-10，Asia/Shanghai。本文服从 [OER 范围与规则](../oer-scope-and-rules.md)，衔接 [native producer 实施](./oer-native-canonical-producer-implementation-20261010.md)。本报告只记录独立 transport 源码、人工编写的契约 fixture 及真实原件的容量测量；原件语义接受、真实模型检索、业务报告、在线部署和 H800 验收另行记录。

## 已实现的入口和版本

`tools/vetting_eval/native_projection_transport.py` 在已验证的 full-bundle 内部视图之上提供公开传输形状：

```python
public = publish_native_transport(internal_projection, artifact_store=store)
internal = hydrate_native_transport(public, artifact_store=store)
validate_native_transport(public, artifact_store=store)
```

完整内部视图仍由 `produce_native(bundle, explicit_adoption, artifact_store=store)` 产生。只接受真实 `ArtifactStore`；公开传输不接受 hash-only 的观察证据、不默认采用原件全文、不猜测来源或创建参考真值。

| 字段 | 本版本固定值 |
| --- | --- |
| `schema` | `consense.canonical-projection.v2` |
| `producerKind` | `native_docx` |
| `storageShape` | `cas-native-observation-adopted-public-v1` |
| `projectionVersion` / `projectionIdentity.version` | `source-reviewed-native-cas-public-projection-v1` |
| `canonical.version` | `native-public-whole-unit-commitments-v1` |
| 公共 snapshot 上限 | 16,777,216 bytes；可降低、不可提高 |
| 每次读取 | `cold_full_cas_and_internal_reconstruction` |
| 派生验证缓存 | `not_implemented` |

本分支不修改扫描 v1 的完整双解析／实际原页几何门槛。调用方按 schema、producerKind 和 storageShape 明确 dispatch，未知组合拒绝。公开 native snapshot 与 full-bundle 内部视图虽然同属 schema v2，仍是不同 storageShape，不能把内部视图直接放进 16MiB HTTP／Java入口。

## 完整证据与公开形状

全文源字节、完整 ZIP parts、adapter 源码、raw observation、完整 native nodes、tokens、编辑通道、表 inventory、参考标注以及 full-bundle 内部投影继续作为准确 CAS 字节保存。公开 JSON 保留如下内容：

| 公开字段 | 实际保留／复验方法 |
| --- | --- |
| `internalProjectionRef` | 准确内部 snapshot CAS descriptor；同时记录其原 seal、sourceHash 和 revision。 |
| `nativeObservationRef` | 完整原生 bundle 的准确 CAS descriptor 和 bundle seal。 |
| `nativeCaptureRefs` | sourcePackage、adapterSource、rawObservation 与全部 rawParts 的实际 descriptor 目录。 |
| `transportCodeRef` | transport 源码的实际 CAS 字节；读取时既核 SHA，也核字节等于当前运行的 transport 源码。 |
| `sourceReferenceRef` | 原有 reference 的 descriptor 和来源／actor／method／version 声明；不把 reference 转录写回候选内容。 |
| `parts[]` | 原文本、原生位置、UTF-16 范围、anchor、channel 选择和 candidate provenance 原样保留；全部 `nativeTextChannels`、editingState、limitations、semanticQuality 直接可见。 |
| `nativeNodeRef` / `nativeTokensRef` / `nativePropertiesRef` | raw observation 的准确 descriptor＋JSON pointer＋payload SHA，绑定原件／source revision／observer identity。完整 node 或 tokens 无需再次在每个公开 Part 内嵌。 |
| `nativeTablesRef` / `nativeTableNodesRef` | 完整采用表组 inventory 和所有原生表节点分别存准确 CAS 字节，公开 descriptor＋pointer＋payload SHA。 |
| `nativeTableTopology` | 全部实际 table／row／cell／blank／nested 关系、完整成员 ID、准确 sourcePosition、unknown、原始 merge/grid 属性 presence 与 literal val；属性 rawNode 和 gridColumns 用准确 CAS pointer 回取。 |

表格保留整个外表及全部内表、blank cells 和原生 paragraph，未把表逐格拼接或只保留计数／哈希。无法解释的 merge／grid 值继续保留原值；没有推断 rendered grid、rowSpan、表头语义或有效编辑态。原观察无 Word 权威物理页，因此每 chunk 的 `physicalPages=[]`、`originalGeometryStatus=not_defined_for_native_docx`，不添加 page/bbox alias。

`humanConfirmed=false`、`semanticScopeVerified=false` 和 unknown 继续沿用。没有核验 Word rendition 映射，仍为 `assetRefs=[]`、`visualReturnStatus=unavailable_pending_verified_rendition`；本版本不证明 Q10 原图／表格返回已支持。

## 身份公式与每次冷读取

使用项目 `_bytes` / `_hash` 的规范 UTF-8 JSON。public identity 保留原来源／observer／role／module／quality／scope 身份，再绑定 public version、storageShape、实际 transport code SHA、internal seal／code SHA、internal snapshot bytes SHA 和 native bundle bytes SHA。

每单元承诺包含 `unitId`、`scope`、`partsSha256`、`adoptionSha256`、`chunkPreIdentitySha256`，表组另含 `tableGroupSha256`。`chunkPreIdentitySha256` 覆盖公开 chunk 去掉 `id`、`sourceHash`、`projectionRevisionSha256` 后的整个 body。公共 canonical 保留这些承诺、完整 relations 和 assetRefs。

```text
sourceHash = SHA256({projectionIdentity, canonical})
projectionRevisionSha256 = SHA256({sourceHash, projectionIdentity})
chunk.id = "canonical:" + SHA256({revision: projectionRevisionSha256, unitId})
projectionSha256 = SHA256(public snapshot except projectionSha256)
```

读取先核格式、硬上限、seal 和实际 transport code 字节，再从 CAS 读取内部 snapshot，经 `validate_native_projection(..., artifact_store=store)` 重新核 source／adapter／raw observation／全部 parts／reference 和原 XML。随后根据这个完整内部视图重建公开形状，并与提交的**整份 JSON 完全相等**。所有内部／bundle／表数组的期望 CAS 字节也实际读取核对；不会在读取时补写缺失 CAS。

只重贴外层 seal、改位置／offset／pointer／payload SHA／表成员／角色／quality 无法代替完整重建。首次成功后修改 CAS 也会使第二次读取失败；没有依据 mtime、存在性或前次成功绕过验证的缓存。transport 源码更新会让旧 public snapshot 的实际 code 检查拒绝，需要重新形成当前版本产物；不能仅修改 snapshot 的 codeSHA 和 seal。

## 冻结契约验证

实际目录为 `.local/state/oer-native-transport-20261010/authored-023826-b0513d/`。其中保留 `authored.docx`、`authored-native-public-v2.json`、完整内部 JSON、explicit adoption、`artifacts/` 实际 CAS、`unittest.log` 和 validation receipt。

收据 SHA-256：`984b7809ef7a4cd1bf34d18d66f41f0c456693022aa754974071a4a8a4c15d44`。本次 **86 tests passed，0 failure/error/skip**：transport **14**、native producer **28**、source reference **15**、扫描 v1／CAS **29**；不与此前或 root 的重叠运行相加。测试前后所列源码 SHA 未变。

| 实际冻结产物／操作 | 结果 |
| --- | --- |
| public snapshot | 90,153 bytes；3 chunks，documentId=`42`、role=`tender`、module=`vetting`，eligibleModules=`vetting,advisory` |
| internal snapshot | 333,924 bytes；完整 bundle／观察／reference 保留 |
| 实际 CAS 总字节 | 638,128 bytes；不含外围日志和额外导出 JSON |
| 单次显式 cold hydrate | 0.04584 seconds；完整恢复的 internal 与原 producer 产物完全相等 |
| transport code SHA | `8f0b2986c69e9475bebf9cd4be094085115c4d1b81cf767f34f10de1645dbf51` |

14 项 transport tests 覆盖实际 CAS 每个家族篡改、每次重验与读取禁止补捕获、丢失字节、完整内表／blank拓扑、原文字／UTF-16／editing channels、pointer错位、hash-only替代、角色／module身份变化、重贴seal篡改、未知shape／version、实际但不识别的transport code，以及固定cap／无伪Word页码。fixture来自实际Java observer对**人工编写的OOXML契约文件**的捕获，不是比赛原件gold、比赛成绩或模型调用。

同一冻结产物追加实际 `server.IndexRequest` Pydantic往返、显式cold transport validator的registry publish/load与advisory共享，以及SourceUnits／结构planner probe，记录于上述目录的 `readonly-transport-probes/readonly-probe-receipt.json`，SHA：`69c8c7e2a5b16377875b44757f52eec301d27e15efd44e76d1bdef149f8f4137`。全部chunk往返完全相等，3个SourceUnits为complete_observed_unit，fake character tokenizer产生5points／2contexts，native positions保留；server模型及DB在前后始终未加载，没有HTTP调用。这是实际schema／纯结构兼容证据，不是默认registry版本dispatch、Java／HTTP、真实BGE或业务报告验收。

## 真实原件容量测量与剩余硬限制

容量测量复用 root 已验证的真实 bundle，逐个重新对实际 CAS 与原 XML 冷验证，没有再次 capture 原件、没有生成原件 source reference／adoption，也没有调用 producer 对这些材料形成“接受”的 snapshot。最终独立目录为 `.local/state/oer-native-transport-20261010/capacity-only-024621-ba36c6/`；收据 SHA：`66087b33f5cfe0739f29b17f29c66a75780ebca8282cc76de51ce6e3f7e758da`。首次 `capacity-only-024045-f2f802` 汇总时重复计入 nested 表之间已有的 JSON 分隔符，Marking多8bytes、PRE多1byte；保留旧收据作为历史，准确字节数采用已复核的最终收据，容量结论不变。

测量选择所有非空表外 paragraph，以及全部完整外表的所有 nested／blank paragraph，使用准确 `observedRaw` public Part 和完整拓扑实现计算。下表是**必须字段的序列化下界**，尚未包含 snapshot header、manifest、commitments、chunk header／content／sourceSpans、adoption、tableGroup成员、unknowns和relations。不是可接收producer snapshot，也不是整体大小上界；低于16MiB只能说明此下界没有排除可行性。

| 原件 | 保留paragraph数 | native tables / outer tables | public Parts＋topology 下界 | 既有 bundle 单次实际冷验证 |
| --- | ---: | ---: | ---: | ---: |
| G302 | 272 | 0 / 0 | 1,570,094 bytes | 0.451 s |
| Marking Scheme | 1,104 | 34 / 26 | 7,354,875 bytes | 5.667 s |
| 项目 PRE | 20,845 | 224 / 223 | 139,376,543 bytes | 17.614 s |

PRE完整公开视图必超16MiB，因此当前版本不能直接发布其整源全部单元。其最大完整外表组有1,186个paragraph，Parts＋topology已达8,326,637bytes；这些是真实完整原表成员，还需加其余字段。没有表组在**此下界**上超cap，但不能追认为完整单表snapshot都已过cap。

完整证据已从公开wire移至CAS，只解决整bundle内嵌导致的必然超限。未来整源接入还需要单独验证范围分批／分页读取：完整原表组不得拆格或截文本，分批也不能改用新页码／隐藏未检索范围来假装全文覆盖。当前没有实现sharding；不提高16MiB门槛。表中时间仅是bundle验证，不包含大internalJSON反序列化、重建／序列化和HTTP传输，因此不能称真实业务冷hydrate总耗时或warm cache效果。

根仓库owner负责Python registry／server版本dispatch和frontend；Java同伴使用本冻结fixture做最小consumer接入。其各自接通／测试证据由对应报告补充，不在本文通过契约fixture或容量测量代认。真实原件独立reference、整组选择、Q10 rendition、原生PDF adapter、模型检索和最终vetting结构化报告仍是独立未验收项。
