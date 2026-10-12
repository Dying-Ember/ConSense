# Java native canonical v2 consumer 接入与验证

日期：2026-10-10，Asia/Shanghai。服从 [OER 已确认范围](../oer-scope-and-rules.md) 与 [实施交接](../HANDOFF-ocr-embedding-rerank-20261008.md)。本报告记录最小 Java consumer 版本接入与复制 checkout 的代码契约验证，未部署线上服务，不代表原件、真实模型检索、前端或 H800 验收。

## 实现行为

`NativeCanonicalContract` 集中约束准确版本与原生定位：schema 为 `consense.canonical-projection.v2`，producer 为 `native_docx`，projectionVersion 为 `source-reviewed-native-cas-public-projection-v1`，storageShape 为 `cas-native-observation-adopted-public-v1`。只接受当前 CAS compact public shape，内部完整原件观察仍由 registry 根据真实 CAS bytes 逐读复验。未知 producer/version、full-bundle 旧形状、伪 PDF aliases、伪文字 channel 或 quality 升级会被拒绝。

`CanonicalEvidenceService` 的 native 分支保留 seal、source、manifest/reference、route/module、当前 DB parsed hash、原件实际 SHA 以及最新来源绑定检查。对已存 native binding，每次公开读取重新调用固定 registry snapshot endpoint，以触发 private proof 的实际 CAS 复验；返回必须与已存 canonical snapshot 完整相等，再检查来源当前状态。registry 不能读取真实 proof 时，不会退回旧 DB snapshot。

原扫描 v1 分支没有放宽非空正物理页、原 PDF normalized bbox、跨页 table group 全组资产或实际图像 SHA/MIME/长度校验。没有把 `pages()` 改成可接受空页，也没有更换 SourceUnits closure。

EvidencePointer 增加 `projectionSchema`（旧 pointer 默认 v1）、`producerKind`、`storageShape`、`originalGeometryStatus`、`visualReturnStatus`。native 的 `bboxConvention` 为 Java null、`physicalPages=[]`；真实位置、native 原通道／editing unknown／完整 topology 和 CAS references 继续留在原 Part/tableGroup JSON。线上 NON_NULL 序列化可以省略 pointer 的 null bboxConvention，但原 Part 禁止任何 Word→PDF page/bbox alias，含 null alias 也拒绝。

`OerCanonicalRetrievalClient` 接受当前 native snapshot，同时要求独立 native health 能力。扫描 base capability 保持原样；只有 native corpus 强制检查 `oer.nativeCanonical`：

```json
{
  "schema": "consense.canonical-projection.v2",
  "producerKind": "native_docx",
  "projectionVersion": "source-reviewed-native-cas-public-projection-v1",
  "storageShape": "cas-native-observation-adopted-public-v1",
  "projectionEndpoint": "/project/native",
  "artifactReadValidation": "actual-cas-every-read"
}
```

`sourceWindows.algorithms` 必须含 `native_source_registry.py`、`oer_native_server.py`、`native_canonical_projection.py`、`native_projection_transport.py`、`native_observation.py`、`source_reference.py` 的有效 SHA；algorithm 身份改变使已存 index receipt 失效。旧 v1-only health 不能采用或索引 native。索引只发完整当前 projection addresses，ranked payload 与 source-unit members 保持原 JSON 相等，仍不通过 legacy Chunk 重建原输入。

`OerBusinessEvidence` 在 native 分支把 legacy Chunk/Part pageNo 与 bbox 留空，避免 P0、空页数组 `get(0)` 和规则页码 substring 错误。`canonicalViewStatus` 验证 native metadata、准确 channel/UTF16/anchor 与完整 raw pointer；相互同步修改 model view 或 pointer 也不能得到合格证据。`ruleSources` 的 DocumentBlock 使用真实 native anchor，页码可以为空。

`VettingSourceMaterial` 将 producer/geometry/visual/unknown 状态和完整 Part/topology/ref 送入模型材料，native 不生成 bboxConvention 字段。Advice 的摘录说明原 DOCX 没有固有 PDF 页／框，并逐 Part 输出真实 partName/nodePath/textChannel。`visualReturnStatus=unavailable_pending_verified_rendition` 仍明确保留，不能称 Q10 完成。

## 实际 fixture 与冻结来源

测试资源 `src/test/resources/oer/canonical/authored-native-public-v2.json` 直接复制真实 Python compact producer 输出，没有手工改 sourceHash/chunk 或重贴 seal。它来自真正 Java observer 对**人工编写 OOXML 契约文件**的捕获，documentId=`42`、module=`vetting`、eligibleModules=`vetting/advisory`、role=`tender`、purpose=`project_document`、class=`project_pre`。

| 材料 | 实际 SHA-256／长度 |
| --- | --- |
| compact public snapshot | `9e50fe75904a6b92c8a082f036f6d5985adc6f02305e6a01d161d95791ab8182`；90,153 bytes |
| `authored-native-source.docx` | `a3cb856459e3027c81da7255595e3776913de30b2e934935fd64e5d921f848bd` |
| Python fixture run 收据 | `.local/state/oer-native-transport-20261010/authored-023826-b0513d/validation-receipt.json`，SHA `984b7809ef7a4cd1bf34d18d66f41f0c456693022aa754974071a4a8a4c15d44`；该独立 producer 运行记录不与 Java tests 相加 |
| transport producer 源码 | `native_projection_transport.py`，SHA `8f0b2986c69e9475bebf9cd4be094085115c4d1b81cf767f34f10de1645dbf51` |

fixture 保留 3 个 parent chunks、外表和内表的完整 public topology、5 个表内 paragraph Parts（其中 3 个 text/UTF16 范围为空）、准确原 NBSP/emoji/tab/newline、直接编辑通道、实际异常名称 header part 和 unknown 状态。`NativeCanonicalFixture` 固定测试资源 bytes SHA，不能静默替换为手写弱 pointer。

## 复制 checkout 运行结果

执行器 `.local/state/oer-native-consumer-audit-20261010/validate-java-native-consumer.ps1` 复制 `pom.xml` 与 `src` 到新目录，只在复制目录编译运行：

`.local/state/oer-native-java-consumer-20261010/run-024711-9e1903/`

实际 Maven **94 tests 通过，0 failures/errors/skips**；收据 `validation-receipt.json` SHA `d28b8296e2ff6d1dca94997b79d1aeebde8dbcf2e842f2fafa66e96d830f1d91`。收据含每个 XML 报告及 log SHA，14 个修改源码/资源的 SHA、测试前冻结副本与工作区相同。没有 build 工作区线上 target。

| Surefire suite | tests／结果 |
| --- | --- |
| CanonicalNativeEvidenceServiceTest | 5／通过：actual compact/source 注册及 pointer、逐读 registry 拒绝缺 proof、v1-only health/native algorithm 拒绝、重贴 seal 后 Word alias/版本/channel/quality 伪造拒绝、原件和上游变动失效 |
| CanonicalEvidenceServiceTest | 19／通过：扫描 v1 来源/资产/三页表组/route/module/未知完整字段等既有回归 |
| CanonicalFreshTransactionTest | 9／通过：最新采用/失败上传/原件变动/READ_COMMITTED 事务门控 |
| OerBusinessEvidenceTest | 10／通过：含 native null legacy geometry、blank/topology/原通道、ruleSources、完整模型材料、unranked context；其余当前 Session/payload/member 原回归 |
| OerCanonicalRetrievalClientTest | 14／通过：含 actual native 完整 parent 往返与能力/algorithm 变动拒绝；其余 payload equality/UTF16/closure/scope/index 身份门控 |
| OerCanonicalSourceUnitsTest | 16／通过：原 closure、unknown、derived-origin 和观察范围回归；实现没有更换 |
| VettingCanonicalBusinessIntegrationTest | 6／通过：整组材料/原 metadata/失败不 fallback/来源变动/覆盖范围门控 |
| AdviceCanonicalIntegrationTest | 14／通过：含 actual native pointer 的准确原生位置/channel/visual unavailable 摘录；原 catalog/history/scope/context/失败事务回归 |
| CanonicalSourceBindingPersistenceTest | 1／通过：绑定持久化回归 |

Java 来源/HTTP tests 使用 actual authored DOCX bytes、sealed Python snapshot 和本地 HTTP stub；HTTP stub 不替代真实 Python native registry/private CAS end-to-end。Vetting/Advice 集成 tests 的 LLM 为 doubles，本轮真实模型调用为零，没有新 MiniMax/M3/embedding/rerank 调用。Spring test 使用独立 H2 和复制目录 target，没有重启或迁移在线服务。

## 仍未通过的验收门槛

本轮没有运行新 native entrypoint 的实际 registry→Java→前端全链路、真实原件独立采用/质量验收或真实模型检索。前端 adapter 由另一实现分支负责。真实 Marking/G302/PRE 等资料不能用 authored fixture 契约成绩替代。

当前 storageShape 的完整单源 PRE 容量仍另有门槛；Java 16 MiB snapshot cap 未提高，也未切断原整表来通过上限。全空采用单元当前仍不能索引，原始空表观察保全；native rendition/Q10 视觉返回仍未实现。这些是下一阶段明确的实际状态，不由本次 94 tests 升级为已完成。
