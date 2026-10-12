# 独立原生 PDF registry 与 HTTP 接入契约

2026-10-10，Asia/Shanghai。先读 [范围规则](../oer-scope-and-rules.md) 与 [联合投影验证](./oer-native-pdf-joint-projection-validation-20261010.md)。本轮是 authored actual-CAS 与 fake 向量／重排模型的 FastAPI TestClient 验证，不是新 TCP 服务部署、真实 BGE／LLM 调用、原件精度或官方赛题验收。

## 新入口与隔离

新增 `tools/vetting_eval/native_pdf_source_registry.py`、`oer_native_pdf_server.py`。`create_native_pdf_app(policy, *, backend, artifact_store, target_module)` 复用既有检索窗口／HTTP 端点，使用独立 PDF registry 分支与 runtime；旧扫描和 native DOCX 服务入口、registry、collection、Java、在线 `target` 均未修改。

CLI 默认 `127.0.0.1:8873`、默认 state `.local/oer/native-pdf-retrieval-state`，拒绝扫描默认 `.local/oer/retrieval-state` 和 DOCX 默认 `.local/oer/native-retrieval-state`。明确 custom state 可用，现有兼容配方、offline 和旧 cache 禁用要求仍执行。**8872 是现有受保护的 native 服务，本入口不使用或覆盖它。**本轮没有启动 8873；调用函数创建测试 app 不等于部署成功。

`/health` 的 `oer.nativePdfCanonical` 声明独立 PDF schema／public storage shape／配方／16MiB cap、descriptor-only project 输入和局部能力限制。health 不加载 tokenizer 或执行模型，也不证明原件质量。signature 同时绑定新 server、registry、projector、transport 和 observer 的实际 source SHA，以及继承的窗口／scope／DOCX／扫描算法身份；运行代码变更后须启动匹配的冻结进程。

## `/project/native-pdf` 请求与回复

新端点只接收三个本地 actual-CAS descriptors：

```json
{
  "nativeBundleRef": {"schema": "consense.artifact-descriptor.v1", "storage": "content_addressed_disk", "sha256": "实际64位SHA", "byteCount": 123, "relativePath": "sha256/前两位/实际SHA.blob", "identityScope": "canonical_json_object"},
  "dualBundleRef": {"schema": "consense.artifact-descriptor.v1", "storage": "content_addressed_disk", "sha256": "实际64位SHA", "byteCount": 456, "relativePath": "sha256/前两位/实际SHA.blob", "identityScope": "canonical_json_object"},
  "adoptionManifestRef": {"schema": "consense.artifact-descriptor.v1", "storage": "content_addressed_disk", "sha256": "实际64位SHA", "byteCount": 789, "relativePath": "sha256/前两位/实际SHA.blob", "identityScope": "canonical_json_object"}
}
```

示例占位符不能直接调用。客户端先通过离线 preparation 把精确输入字节准备到该服务的 ArtifactStore，再使用其实际 descriptor；没有上传原件／任意远程 fetch 的新端点。inline bundles、额外字段、路径越界、URL 字段、缺项、重复原始 JSON key 均拒绝。三个 CAS JSON 的根必须为 object；实际 `[]` 字节产生受控 422，不能进入模型。adoption scope 也要求 object 并绑定当前 process 的 scope policy。

端点冷读 actual native、dual、adoption CAS，执行 producer 全量冷重构后生成独立 compact public wire。整个 public reply 包含 receipt 和 projection；在 registry publication 前检查完整回复预算。统一 ASGI limiter 限制全部请求／响应 body 不超过 16,777,216 bytes，超限请求 413、响应受控 422；响应在送出 headers 前完成预算核验，不能只限制 projection 后把 receipt 压到上限之外。

这不限制内部 raw native export 或完整 retained evidence 的大小；它们不内联进入公共请求。输入 descriptor 身份与其 retained JSON 字节继续逐读验证。`/project/native-pdf` journal 保留实际 descriptor 请求／公共回复及终态，仍在私有 service state。

## 精确 registry 与查询门控

PDF 分支只接受 `consense.native-pdf-canonical-projection.v1`、`producerKind=native_pdf`、`cas-native-pdf-joint-adopted-public-v1` 的严格组合，并执行独立 actual-CAS transport gate。PDF 专属 refs、source position kind、glyph／UTF16 spans、block／chunk ID 和配方版本是独立信号；只删除 producer／storage/version 不会降级成旧扫描或 DOCX。通用的 `nativeObservationRef` 同时存在于 DOCX，明确不能单独作为 PDF 信号。

非 PDF 内容继续调用旧扫描／DOCX validator。实际 authored DOCX 与 scanned public snapshots 的发布、读取和 native whole-parent 校验已在新 mixed registry 验证；不是根据 imports 推断兼容。加载的合法 JSON array 也须被新 validator 受控拒绝：canonical GET 404，index-canonical 422，无 `AttributeError` 500。

发布保存 immutable public bytes 及独立 `native-pdf-revisions` locator。locator 路径必须留在自身 root；它是地址，不能当 validation cache。直接 `/index` 的 PDF parents 必须已经登记、保持完整 published parents 和 source order；删父块、逆序、改文字、strip 部分类型字段或缺 revision locator 均拒绝。

每次 signature 前，包括从磁盘 metadata 恢复 index 的 read path，都冷验 actual published source／CAS。查询期间发生原件／raw evidence 变更时，query 后再冷验并扣留结果；不能把已跑 fake inference 的旧结果返回。此机制继承 existing `NativeOerWindowRuntime`，新 registry 在相同门控中处理 PDF 与既有 native DOCX。

## 必要 schema 修复与结果

首轮 project／register／index 通过，但 retrieval 因缺 `retrieval_source_units.SCOPE_FIELDS` 的 `nativeTableMetadataVersion` 返回 `STRUCTURAL_CONTEXT_UNAVAILABLE`。经 root 核对，在 **producer** chunk 加入 `native-pdf-no-table-adoption-v1`，公共 wire 自然保留并绑定 source identity；runtime 没有补字串／升级质量／绕过 strict rerank。旧 19／139／root55 冻结 snapshot 与失败日志保留，新 producer SHA 为 `bf75da59df2c696cd56b7628b104d89ea12f57c783bfd5e9a4cc06f4de991416`。

新边界审查另发现通用 `nativeObservationRef` 误判 DOCX 与 CAS／stored snapshot JSON array 的 object gate 缺失，两项均有实际 red 后局部修复，未改变旧 validator。源码冻结后的新 registry 4 项＋fake HTTP 10 项共 14 项全绿；继承消费者的独立回归数量以最终 receipt 为准。首轮 unittest 模块曾因导入 `OerHttpContractTest` 自动收集额外 17 项，后来改 module alias；这 17 项不算新 PDF 测试。

忽略 run 位于 `.local/state/native-pdf-projection-20261010/run-044305-c59746/schema-identity-v2-*/`，保存 projector 19／相关139、public transport9、registry／HTTP14及既有消费者回归日志、历史失败、新 final authored snapshot／CAS 与 `validation-receipt.json`。

原页 visibility、法律语义、完整原件覆盖继续 unknown；native tables/cells/AP adoption 与 PDF 原图 serving 尚不支持。真实 GCC adoption、Java 业务消费、浏览器展示、真实模型调用、赛方问题与 Vetting 报告评分、目标 H800 release 仍按 root 后续阶段独立验收。本轮没有更改在线 Java／前端／MiniMax／WSL 服务、原件或权重，没有启动 GPU 模型。
