# 原生 DOCX canonical v2 producer 实施与契约验证

日期：2026-10-10，Asia/Shanghai。本文服从 [OER 当前范围](../oer-scope-and-rules.md) 和 [实施交接](../HANDOFF-ocr-embedding-rerank-20261008.md)，落实 [native producer 设计审计](./oer-native-canonical-producer-design-audit-20261010.md) 中独立原生分支。它记录源码及人工编写的契约 fixture 验证，不代表原件全文接受、真实模型检索、在线应用或 H800 部署已经通过。

`tools/vetting_eval/native_canonical_projection.py` 已提供独立的纯投影入口：

```python
produce_native(native_observation_bundle, explicit_adoption, *, artifact_store)
validate_native_projection(projection, *, artifact_store=None)
```

当前输出为 `consense.canonical-projection.v2`、`producerKind=native_docx`、`projectionVersion=source-reviewed-native-whole-unit-projection-v2`，是 **full-bundle 内部验证视图，不能直接作为大文档的公开 wire**。同日追加的独立 CAS public transport 已实现，当前版本、冻结fixture及真实原件容量限制见 [compact transport实施](./oer-native-cas-public-transport-implementation-20261010.md)；其public shape与本内部视图分别识别。原生 PDF 尚无本模块 adapter。扫描 `canonical_projection.py` 的 v1 仍独立保留，其完整双 parser／真实原页几何要求未被放宽；本文原producer验证阶段未修改 registry、server、索引、Java consumer 或前端以激活 v2，后续接入由root另记实际证据。

## 输入、定位和接受边界

原生观察来自 opt-in Java POI／OOXML observer，包含实际 DOCX 字节、全部 ZIP part、实际 adapter 源码、dependency／config、完整 story／node／table inventory，以及原生 token／编辑通道。先通过 `native_observation.validate_native_bundle` 核验实际 CAS 字节与原 XML，producer 再核验明确的来源、采用和 scope 身份。observer v2 的节点身份包含准确 package part 名称，重复页眉 part 即使字节相同也不会混成同一节点。

采用 manifest 使用 `consense.native-canonical-adoption.v1`，只有显式选择，不运行模型猜测来源或补合同值。其结构为：

| 项目 | 契约 |
| --- | --- |
| 来源／路由 | `bundleSha256`、`sourceSha256`、`sourceRevisionSha256`、`documentId`、`role`、`module`、`eligibleModules`、`sourcePurpose`、`sourceClass`；来源变化拒绝旧采用，改变角色、模块、质量或 scope 生成新身份。 |
| scope | `requiredPartNames` 是本轮局部采用允许涉及的准确原件 part 名；`scopePolicySha256` 绑定范围规则。此清单不表示全部 part 的文字已接受或整份 Word 已验收，全部原件 part 仍在观察 bundle 保全。 |
| reference | 只接受原始 UTF-8 `source-review-reference.v2` 的实际 CAS descriptor 和 `evidenceSha256`；actor 必须 Codex、method 必须 original_source_review、humanConfirmed 必须 false。原件及 part 字节按实际 SHA 核验，不自动跟随参考文件内路径。 |
| region | 只选择表外 paragraph 的实际 `nodeId`／`textChannel`，并与 reference 的准确 native anchor 对照；run、cell 或表内 paragraph 不得分别作为普通区域接受。 |
| table group | 显式外表 root、完整 native table ID／paragraph ID 清单，reference.nativeTables 的完整原表组声明及 source anchor；内表属于父 cell，不能再作为另一独立 root 重复接受。 |
| relations／clause | 仅采用明确 reference 支持的关系和 clause no；父条循环、歧义、未选成员以及没有参考支持的 clause 拒绝。 |
| quality | semanticQuality 和未确认属性继续 unknown；局部选择不会升级 humanConfirmed、有效编辑态或整源接受。 |

每 Part 的 `sourcePosition` 是 `docx_native`＋原件 SHA＋准确 `partName`／`partSha256`＋Clark 名称和同名兄弟序号的 `nodePath`。原生 Word 无权威物理页或 PDF bbox，因此不添加 `pageNo:null`、`physicalPageNo` 或 `bbox` alias；所有 `physicalPages` 为 `[]`，明确标记 `originalGeometryStatus=not_defined_for_native_docx`。

索引文本直接选择观察里的原生 channel，保留 tabs、NBSP、空白、原拼词和 astral 字符。Part 同时保留全部 channels、token、nativeObservation 及准确 rawObservation descriptor／JSON pointer；变换明确为该 channel 到索引文本的 identity UTF-16 映射。reference 的转录文字不写回 parser candidate，即使两者不同也不会自动“修字”。`nonDeletedNonDirectStrike` 只是明确的直接编辑通道，不声称已解决 inherited style、隐藏态或最终显示文本。

## 完整表组与原始资源

全表 inventory 覆盖全部实际行、格子、blank cells、嵌套表、原始 gridSpan／vMerge／hMerge／gridBefore 属性。捕获 validator 对照实际 XML 完整验证 node／token／table membership；producer 在这个证明之上按一次建立的原生 ancestry 索引选择完整组并复验清单，避免逐表重复解析整个大 part。缺失、重复内表 root、表外 scope 越界或重复 paragraph 拒绝。无法解释的属性保存原值和 unknown，不补造 gridColumn、rowSpan、表头或语义 cell 关系。性能调整未取消每次 registry 读取所需的实际 CAS 完整性复验。

所有 paragraph 按原生 source order 进入索引一次，重叠的 run 通道和结构 table／row／cell 文本不会再拼进内容。完整 nativeTables 和原生节点继续保留在采用单元及 tableGroup。表内 blank paragraph 可保留；完全没有非空观察文字的表暂不能成为检索 chunk，原始 inventory 仍完整保全，不能用 gold／猜测标题制造内容。

首版没有已核验的 Word→rendition 资产关系，明确输出 `visualReturnStatus=unavailable_pending_verified_rendition`、`assetRefs=[]`。原始 media、EMF、chart 或 XML 仍在完整 CAS bundle 中，不能把它们伪装为原页 PNG，不能据本版本宣称 Q10 图表返回完成。

## seal 与读取复验

`projectionIdentity` 绑定 producer version／代码 SHA、完整 bundle／manifest、原字节及观察 revision、document／role／module、eligibility、sourcePurpose／sourceClass、scope、quality 和 observer identity。`sourceHash` 由整个 identity 与完整 canonical payload 重算；projection revision、chunk ID 和整份 seal 分别由固定公式生成。

`validate_native_projection` 根据 retained native bundle、manifest 和 sourceReference 重新生成完整期望 payload；只修改 chunk、内容、offset、路由、质量或来源后重贴外层 seal，不能通过校验。**registry／业务读取必须传实际 ArtifactStore**，此时会重新验证所有 source／adapter／raw observation／part／reference 字节和实际 XML；省略 store 的调用仅用于内部一致性，不能证明原件文件仍可读取。未来版本 dispatch 必须保持这个边界。

## 本轮实际验证

最终验证目录：`.local/state/oer-native-projection-20261010/final-022548-e1203f/`；收据 SHA-256：

`61c4c3c5e0b1eaacabfc22ac7684275392ca8a8d8a2799aea27369d11b801bf7`

本轮 **72 tests 通过，0 failure／error／skip**，包括新 producer 的 **28 项**，既有 source_reference 的 **15 项**，以及扫描 v1 和 CAS 回归。测试前后所列源码 SHA 相同。`unittest.log` 与每个源码 SHA 保存在上述收据中；不将这些数字与 root 的重叠验证相加。

最终同目录保留实际 `authored-native-full-v2-projection.json`（334,014 bytes）及 `artifacts/` CAS，可对 sealed JSON／typed native positions／UTF-16 再作真实字节兼容审计。早期 `validation-021807-43932c` 的72项收据和源码SHA保留原历史事实；后续 ancestry／SL class 门控调整按本最终收据报告。

测试内容包括 source／bundle／adapter／code／adoption／scope／role／module／quality 改变、原始 part 或 DOCX 字节被替换、完整内表和 blankcell、局部表拒绝、UTF-16／原字符、无伪页码、引用位置错配、gold 文本禁止写回、reference 关系支持、模板／答案／排除类别隔离，以及重贴 seal 后的 metadata mutation。

`test_native_fixture.py` 保留一个由真实 Java observer v2 对**人工构造 OOXML 契约文件**产生的精确 compressed capture，导出 `fixture_bytes()`；`test_native_canonical_projection.py` 在同一实际 CAS 上构造明确标识的采用 fixture。人工构造文件是契约测试材料，reference 文本刻意区别于候选以验证不发生 gold writeback，不能称原件 gold 或比赛题成绩。原始合成 DOCX、两版 observer export、copied Java source 身份和捕获收据保存在 `.local/state/oer-native-projection-20261010/`。早期旧 anchor 分支缺失及 Windows default decoding 造成的测试预期字符串错误保留工具输出记录；编码已改为显式 UTF-8／ASCII Unicode escape，观察字节未改写。

本轮没有 OCR／embedding／rerank／LLM 推理、服务启动、在线 profile 切换、原件修改或 Bonsai 重载。实际原件的 observer capture 完整性由 root 另行验证，不能据本报告升级为全文语义接受。

## 大文档公开 wire 的已确认阻断

只读接入 probe 使用 actual `server.IndexRequest` 已验证上述 authored 3 chunks 的 Pydantic往返完整相等，无 Word page alias；显式 native validator 的 registry publish／load及模块共享完整相等，默认 registry 仍按 v1拒绝v2。现有 SourceUnits／结构 planner可保存其准确native positions；probe使用fake character tokenizer，无BGE成绩。记录及源码SHA在 `.local/state/oer-native-projection-20261010/dispatch-readonly-probes/`，不能称 Java／HTTP／神经模型接通。

当前 Java `CanonicalEvidenceProperties.maxSnapshotBytes` 固定 **16,777,216 bytes（16MiB）**，backend 同时检查 Content-Length与实际流读取。root 实际原件 capture的完整bundle byteCount为G302 **12,424,016**、Marking **172,785,077**、项目PRE **418,855,750**，见 `.local/state/oer-native-capture-20261010/actual-v2-7bd3798a/receipt.json`。这些是捕获完整性，语义unknown；本模块没有再次capture或采用真实原件。当前内嵌整bundle的shape使Marking／PRE任何局部采用都必超snapshot门槛，不能通过增加gate、删token或截断来宣称支持。

上述阻断是在full-bundle内部producer验证时发现的历史状态。同日新增的独立CASstorageShape已完整保全原始证据并减少公开重复内嵌，详见 [compact transport实施](./oer-native-cas-public-transport-implementation-20261010.md)。首版每次读取仍完整CAS复验并重建，没有派生验证缓存；16MiB gate继续保留，真实PRE完整public Parts／topology下界依旧超限，尚不能发布整源。不能用mtime、路径存在或曾经验证成功替代准确字节核验。

剩余工作为版本dispatch和真实Java／索引往返、大public view范围分批与完整原表门槛、范围内原件的独立reference与整组选择、native rendition回跳／原图覆盖、原生PDF adapter，以及实际业务和H800发布验收。具体接入进度以handoff和对应运行证据为准，不从本契约报告升级为在线已部署。
