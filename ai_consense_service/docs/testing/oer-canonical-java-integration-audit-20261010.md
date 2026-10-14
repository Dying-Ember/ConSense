# Java → OER canonical 检索与图表回取接通审计

日期：2026-10-10，Asia/Shanghai。审计基准为工作区当前源码；Git HEAD `285fc2e3bdf97792aeae00a31dbde2c69ebcde60` 之外有未提交实现，不能把 HEAD 当作全部被审计内容。先读取 [中央范围规则](../oer-scope-and-rules.md) 和 [实施交接](../HANDOFF-ocr-embedding-rerank-20261008.md)。本轮仅源码只读核查和本文件记录，未修改 Java／Python／Vue 生产源码，未启动服务、切换 profile、调用模型、进行新性能实验或浏览器验收。

## 当前结论

**已存在来源身份、原生证据持久化、结构检索 health 和 Python sealed canonical／资产接口，但 Java 与前端尚未接通这条完整链路。仅把 retrieval URL 指向 OER 端口、或启用 `application-oer.yml`，不能完成 canonical 采用及 Q10 图表回取。**

主要接通缺口是：

1. Java 仍以 `VettingCorpus.chunks()` 的本地 snapshot 向 `/index` 索引；没有 `/project`、`/index-canonical`、canonical snapshot 注册／持久化调用。新 canonical chunk 的 ID、版本、质量形状、parts、资产关系与旧 Java DTO 不同，现有严格校验会拒绝这种返回。
2. Python 已能按 reviewed whole unit 产生 sealed projection 和正确来源资产，Java 尚未保存这些 immutable projection／adoption／assetRefs，也没有图表资产的业务代理端点。
3. Advisory 仍使用 `AiGateway.embed`、`EvidenceChunk` 与 `VectorStore`／stuffing 的独立链路，没有采用 Vetting 的 Python OER 检索。资料权限已区分 `advisory`，不等于两模块已经共用 OER 检索。
4. Vetting 当前前端显示文字、位置和“打开原件”；Advisory 显示文字引用。两者均没有 canonical `assetRefs` 或完整跨页表组的展示契约。

本结论来自下列源码。已有单测／原件探针另按历史 receipt 报告，本轮没有复跑它们，也不把源码审计记为真实 BGE／原件质量／前端业务接受。

## 已有入口及其真实边界

| 环节 | 现有实现 | 接通边界与源码依据 |
| --- | --- | --- |
| 上传来源 | `SourceDocument.sourceSha256/oerIdentityJson/nativePdfEvidenceJson`；`OerSourceMetadata` 保存路由和原生证据 | [SourceDocument](../../src/main/java/com/consense/domain/SourceDocument.java#L61-L73)，[OerSourceMetadata](../../src/main/java/com/consense/service/sources/OerSourceMetadata.java)。字段存在，不代表已有 canonical projection、adoption manifest 或索引 receipt 持久化。 |
| 文件范围 | `ProjectDocumentScope.selected(..., module)`，模块身份排除＋当前版本／tender／contract 选择 | [ProjectDocumentScope](../../src/main/java/com/consense/service/sources/ProjectDocumentScope.java#L26-L79)。Java 文件级范围可复用；未在此实现 SL 按原物理页的技术范围裁分。 |
| Java corpus | `VettingCorpus.blocks/chunks/emit` 从本地 `structuredContentJson` 分组、按页／字符数拆分，生成 Java UUID chunk | [VettingCorpus](../../src/main/java/com/consense/service/vetting/VettingCorpus.java#L120-L193)，[emit](../../src/main/java/com/consense/service/vetting/VettingCorpus.java#L312-L360)。原生字段已进入未切片 Part；不是 `/project` 生成的 whole-unit canonical chunk。 |
| Java semantic review | `VettingSemanticReview` 仍调用 `retrieval.index(projectId, chunks)`，再按主题／role 查询 | [VettingSemanticReview](../../src/main/java/com/consense/service/vetting/VettingSemanticReview.java#L115-L118)，查询包装见同文件 458 行。需先改变其 corpus 输入和索引 seam，不能只改 HTTP URL。 |
| Java index／retrieve | `/health`→`/index`→`/retrieve`，严格绑定 project/count/signature/current corpus/payload | [VettingRetrievalClient](../../src/main/java/com/consense/service/vetting/VettingRetrievalClient.java#L29-L82)，[current corpus 与命中门控](../../src/main/java/com/consense/service/vetting/VettingRetrievalClient.java#L83-L168)。没有 canonical 发布、加载或资产读接口。 |
| OER profile／health | 配置 v4／dual-structure／512/2048；health 校验算法、模型／配方及 source-unit contract | [application-oer.yml](../../src/main/resources/application-oer.yml)，[VettingRetrievalHealth](../../src/main/java/com/consense/service/vetting/VettingRetrievalHealth.java#L21-L101)。这是检索配方握手；未明确要求 `health.oer` 的 canonical／asset contract。 |
| Python projection | `POST /project` 接收 `{bundle, adoptionManifest}`，检查 actual scope policy，发布 immutable projection | [ProjectionRequest](../../tools/vetting_eval/oer_server.py#L36-L46)，[project](../../tools/vetting_eval/oer_server.py#L242-L257)。不能直接上传一个 Java Chunk 当作 bundle。 |
| Python canonical index | `POST /index-canonical` 接收 `{projectId, projectionSha256:[...], module}`，加载 sealed snapshots 后索引 | [canonical_index](../../tools/vetting_eval/oer_server.py#L259-L283)。列表要求唯一、至少一项；module 必须等于当前进程 `target_module`；policy 不同需重新投影。 |
| Python snapshot／asset | `GET /canonical/{projection}`；`GET /canonical/{projection}/sources/{source}/assets/{asset}`，可带 `module` | [端点实现和注册](../../tools/vetting_eval/oer_server.py#L285-L320)，[CanonicalSourceRegistry](../../tools/vetting_eval/oer_source_registry.py#L84-L126)。重复核验 seal、来源／模块、descriptor 和实际资产字节；不是按最新 filename 或相似 caption 回取。 |
| Advisory | `AiGateway.embed`＋`VectorStore`；小 corpus stuffing，大 corpus dense search | [AdviceService.rebuildIndex](../../src/main/java/com/consense/service/advice/AdviceService.java#L69-L135)，[retrieve](../../src/main/java/com/consense/service/advice/AdviceService.java#L266-L327)。没有 BGE dual／same-pool rerank 路径，也没有资产元数据。 |

## 精确断点

### A. 新 sealed payload 不能直接塞进旧 Java Chunk

Python projection 返回的 `chunks` 包含 `canonical:<sha>` ID、`sourceRevisionSha256`、`projectionRevisionSha256`、`physicalPages/sourceSpans`、`adoption`、`relations`、`tableGroup` 和 `assetRefs`。其 `sourceHash` 是 projection identity＋canonical 内容的哈希，包含模型、scope、quality 和采用决定，见 [canonical_projection 369–419](../../tools/vetting_eval/canonical_projection.py#L369-L419)。

旧 Java `Chunk` 只有有限来源／结构／quality 字段，`Part.table` 是 `TableRow`，`sourceQuality` 是 `VettingSourceQuality.Info`，见 [Chunk/Part](../../src/main/java/com/consense/service/vetting/VettingCorpus.java#L28-L86) 及 [Info](../../src/main/java/com/consense/service/vetting/VettingSourceQuality.java#L21-L31)。`JsonUtils` 全局关闭 `FAIL_ON_UNKNOWN_PROPERTIES`，见 [JsonUtils 16–19](../../src/main/java/com/consense/common/JsonUtils.java#L16-L19)。因此直接 `JsonUtils.read(..., Chunk.class)` 不是无损入口：它会忽略未声明字段，且 table／quality 同名字段的结构也不兼容。

`VettingRetrievalClient` 用本地 corpus `byId` 查找每个 hit，并将其 `normalizedPayload(c)` 与整个返回 payload 做 equality；closure members 也做同样门控，见 [Client 141–168、198–205](../../src/main/java/com/consense/service/vetting/VettingRetrievalClient.java#L141-L205) 和 [SourceUnits 25–55](../../src/main/java/com/consense/service/vetting/VettingRetrievalSourceUnits.java#L25-L55)。新 ID 不在旧 byId 中会拒绝；只把新 ID 套在旧 DTO 上，仍会因为字段丢失和 payload 差异拒绝。**接入时应改变“权威 snapshot”的来源，保留严格 payload 门控，不能放松 equality 来掩盖转换损失。**

最小 seam 是可持久化的 canonical snapshot/envelope：完整 sealed JSON 作为权威 payload，业务所需字段可有只读 view；检索传输、fingerprint、hit 与 source-unit member 比较均使用权威 payload。旧 legacy DTO 的序列化保持其分支。派生 view 不重新编号、拆 whole table、重组 parts、补 `humanConfirmed` 或升格未知质量。

### B. sourceHash 有两种已有定义，必须增加显式绑定

Java `VettingCorpus.sourceHash` 为 `textContent + structuredContentJson` 的 parsed revision，见 [134–148](../../src/main/java/com/consense/service/vetting/VettingCorpus.java#L134-L148)。Python canonical `sourceHash` 为 projection 内容身份，`projectionSha256` 则为完整 sealed snapshot 内容地址，`projectionRevisionSha256` 是另一明确定义的派生版本；它们与原字节 `sourceSha256` 不可互换。

当前 `/sources/{id}/original?sourceHash=...` 仅核对 Java parsed revision 与 storageRoot 内文件路径，见 [VettingService 507–524](../../src/main/java/com/consense/service/vetting/VettingService.java#L507-L524)。直接用 canonical `sourceHash` 构造该 URL 会被拒绝；该现有方法也未用 `sourceSha256` 复验即将返回的原件字节。应保留旧参数语义，为 canonical 证据新增明确 pointer／映射校验；不要全局替换现有 hash 算法。

建议持久化绑定至少包含 `projectId/documentId/originalSourceSha256/javaParsedSourceHash/sourceRevisionSha256/projectionRevisionSha256/projectionSha256/module/eligibleModules/scopePolicySha256`，保存完整 projection payload 与采用 manifest 身份。来源／quality／模型／范围／采用决定改变时形成新 binding/index receipt，不能将旧 projection 静默指向最新来源。

### C. 坐标和页码不同，图表展示必须显式转换

Python canonical Part 为 `physicalPageNo`＋`bboxConvention=normalized_xyxy_original_page`，bbox 是 `[x0,y0,x1,y1]`，并保留 `geometryEvidence`，见 [canonical_projection 150–178](../../tools/vetting_eval/canonical_projection.py#L150-L178)。Java Part 为 `pageNo="P12"`＋normalized `[x,y,width,height]`，见 [VettingCorpus.emit 337–357](../../src/main/java/com/consense/service/vetting/VettingCorpus.java#L337-L357)。

不能将两个四元数组直接互认，不能从 canonical `physicalPageNo` 缺失于旧 DTO 得出“无页码”。需要显式 `bboxConvention/coordinateSpace`、有验证的转换，以及整表 `physicalPages` 和页上 `sourceRegions`。Word 原生位置不能填成打印 PDF page；只有存在同源 derivative mapping 的视觉资产才使用其原物理页。

Python `_assets` 当前采用的是 parser 输入整页 artifact，校验 unit/run/page/block 集合和 `assetBoundary` 来源复核；**当前不是任意模型生成的 crop 或已保证每个表格为独立裁图**。整页 asset 的 sourceRegions 可定位相关区域。跨页 `table_group` 只要声明 assets，就必须覆盖全组 physicalPages 和 blockIds，见 [212–260](../../tools/vetting_eval/canonical_projection.py#L212-L260)。首版可返回完整原页及区域定位／完整页组，满足 Q10=A 的正确原件回取；后续裁图必须另绑定源页与裁切变换，不能改写原 descriptor。

### D. /project 当前仅支持完整双解析采用，可靠原生路径尚缺统一 producer

`project_canonical` 对当前 bundle 调用 `assert_complete_dual`，要求 actual reference、全部 required pages 和明确 reviewed attributes；table 必须整组采用，不能逐格拼接，见 [263–357](../../tools/vetting_eval/canonical_projection.py#L263-L357)。所以当前 `/project` 不是直接 POI／PDF native projection API。

可靠原生 DOCX／PDF 继续是主文字来源，这是已确认规则。最小实施需补独立 native snapshot producer/adapter（可复用同一 downstream envelope／scope／asset pointer 契约），或者在第一阶段明确保留 native `/index` 分支，并说明哪部分尚未有 canonical assets。**不能为通过双路 schema 伪造第二个 parser run，也不能强制 POI 原文改为视觉 OCR 文本。** 部分扫描＋原生混合文件还需要来源复核的 region／whole-group 组合 producer；当前 scanned projector 单 unit 采用某一 run，不是已经实现这种融合。

### E. OER health 握手还没有明确要求 canonical/资产能力

OER health 已公布 `oer.recipe/inputContract/targetModule/scopePolicy/canonicalProjectionEndpoint/assetReturnContract`，并把 wrapper／policy identity 加进 retrieval recipe/signature，见 [oer_server 138–156、200–211](../../tools/vetting_eval/oer_server.py#L138-L211)。Java 当前把整个 retrieval JSON 纳入 fingerprint，因而能观察这些 retrieval 字段的变化；但没有要求必须存在 `health.oer`、没有按预期 canonical input/asset return contract 固定其值，见 [Health 21–83](../../src/main/java/com/consense/service/vetting/VettingRetrievalHealth.java#L21-L83)。

因此已有 structural health 通过并不证明连接的是 canonical OER wrapper。最小新增明确的 OER 模式握手：校验目标模块、actual scope policy identity、input contract、asset contract／endpoint、wrapper 算法身份；旧 v3／普通 v4 握手保持原模式。`health` 不运行 tokenizer/model，也不接受 source quality，不能拿它取代 projection 或原件复核。

### F. Advisory 需要独立调用适配，scope 不能用 role 替代

Advisory 的 native chunk 文本被复制到 `EvidenceChunk`，payload 只有项目／文档／sourceHash／role／label／page／anchor／content，见 [AdviceService 89–127](../../src/main/java/com/consense/service/advice/AdviceService.java#L89-L127)。完整 parts、表组、资产、projection revision 不在持久化／引用输出中。

其 `tender/contract/fullset` 范围先按文档集合过滤，见 [ProjectDocumentScope 68–75](../../src/main/java/com/consense/service/sources/ProjectDocumentScope.java#L68-L75) 与 [AdviceService 271–275](../../src/main/java/com/consense/service/advice/AdviceService.java#L271-L275)；Python `RetrieveRequest` 仅支持 `projectId/query/role/limit/candidates`，见 [server 140–145](../../tools/vetting_eval/server.py#L140-L145)。多个合同／招标文档可能同为 `tender` role，因此 role 过滤不等于业务 scope。不能先从超范围池拿 topK、再仅事后丢掉不允许文档，并据此声称召回来自正确候选池。

建议按明确的“当前允许文档＋版本＋业务 scope”生成不可混淆的 index key／receipt，再由两模块检索适配器复用同一 sealed source registry 与 canonical chunks。当前 API 不增加 docId filter 的最小方案是用**按许可文档选择的 projection list** 建立该 scope 的 index，query 绑定该 index key。index key 要区分实际 `projectId`、模块、scope／版本指纹，在输出中保持业务 projectId，并遵守 Python key ≤200 字符；不是替换用户项目身份。

当前 `CanonicalSourceRegistry.chunks`／snapshot／asset GET 允许 `eligibleModules` 支持的 Vetting/Advisory 共享，见 [registry 95–108](../../tools/vetting_eval/oer_source_registry.py#L95-L108)。但 `/index-canonical` 仍要求 request.module 与当前进程 target_module 完全一致，见 [oer_server 259–278](../../tools/vetting_eval/oer_server.py#L259-L278)。**共享原件不代表当前单进程端点已是两个模块通用入口。** 实施需选定且验证 shared review index 的契约，或在原契约下使用明确模块进程／状态；不静默把 advisory 改名为 vetting 绕过权限检查。这里是工程契约缺口，不重新询问已采纳 Q9。

## 图表回取的最小业务接入点

### 已有页面的实际能力

Vetting 的 `FindingEvidence`／`EvidenceItemVO` 保留 sourceHash 和单 bbox，没有 sourceSha256、projection pointer、assetRefs、跨页表组；见 [VettingDtos 116–169](../../src/main/java/com/consense/web/dto/VettingDtos.java#L116-L169)。前端 `originalSourceUrl` 用 parsed hash 打开完整 PDF／下载 DOCX，页面仅列 quote，见 [VettingView 139–170](../../../ai_consense_web/src/views/VettingView.vue#L139-L170) 和 [1044–1065](../../../ai_consense_web/src/views/VettingView.vue#L1044-L1065)。现有 bbox 被转存但没有此处的区域绘制。

Advisory `CitationVO` 只有文字出处及 sourceHash，见 [AdviceDtos 43–56](../../src/main/java/com/consense/web/dto/AdviceDtos.java#L43-L56)；Vue 仅 `fileLabel/pageNo/content/score`，见 [AdviceView 462–484](../../../ai_consense_web/src/views/AdviceView.vue#L462-L484)。现有 [TS FindingEvidence](../../../ai_consense_web/src/api/types.ts#L485-L497) 和 EvidenceSnippet 669–684 行也没有 asset pointer。审查→咨询轻量入口携带文字／旧证据的实现仍可保留，不应扩为另一个 workflow。

### 建议新增一份共享 pointer 契约

以下字段是建议，不是当前已经支持的 API：

```text
EvidenceSourcePointer
  projectId, documentId, originalSourceSha256
  javaParsedSourceHash                 // 旧原件端点的 parsed revision，显式命名
  canonicalSourceHash, projectionSha256, projectionRevisionSha256
  unitId, canonicalChunkId, module, eligibleModules
  physicalPages, nativeLocation?       // unknown 保留；不能相互代填
  tableGroupId?, assetRefs[]

EvidenceAssetRef
  assetId, sha256, byteCount, mediaType
  sourceSha256, projectionSha256, unitId, physicalPages
  sourceRegions[{blockId,bbox,bboxConvention}]
  reviewStatus/unknowns                // 采用资产边界不等于全源语义接受
```

业务检索命中必须从已绑定 snapshot 回填 pointer，不能让 LLM 发明 assetId／URL。FindingEvidence 和 CitationVO 使用同一可选 pointer，旧文字引用继续可读，source stale 局部明确失败，不回取“最新相似图”。文字定位状态、资产边界复核和整表／语义质量分别显示。

在 Java 业务 controller 新增固定来源资产代理 seam，示意：

```text
GET /api/evidence/{projectId}/documents/{documentId}/canonical/{projectionSha256}/assets/{assetId}?module=advisory|vetting
```

该路径为建议。服务先核对项目实际 document、当前来源／revision binding 和模块 eligibility，再从配置的 OER 后端拼接既有 `/canonical/{projection}/sources/{source}/assets/{asset}`，不接收任意外部 URL／storage path。校验响应 `X-Source-Sha256/X-Projection-Sha256/ETag/mediaType` 和 descriptor bytes SHA／byteCount，匹配后返回实际资产字节。Python 已按 descriptor 和 magic 校验，Java 代理仍应绑定业务文件身份，避免把“Python 内有此资产”误当“属于当前项目可引用证据”。

前端可复用一个轻量 evidence asset panel：展示来源／版本／原页、相关文字，以及一个或多个完整页图片，按已声明 bboxConvention 绘制相关区域；table_group 按完整 physicalPages 显示，不只显示被检索命中的第一页。保留打开原件按钮。未知 bbox 不画猜测框；缺 asset／版本过期显示具体不可用状态。不要求流程箭头理解，不增加咨询审批／整改状态，遵守 Q10=A／Q11 的边界。

## 最小实施顺序与门控

| 顺序 | 最小接入点 | 必须保留的门控 |
| --- | --- | --- |
| 1 | 增加 source-bound canonical snapshot/binding/envelope 持久化；采用 manifest 留痕 | 原字节 SHA、parsed revision、projection seal／role／scope／quality／model 身份分别绑定。新增数据库字段／表需对应迁移；当前 `SourceDocument` OER 三字段仅有实体定义，本轮未见其专属 Flyway 迁移，不以 H2 create-drop 通过追认既有 MySQL 升级。 |
| 2 | 扫描资料由现有 `/project` 发布采用 snapshot；可靠 native 走独立 producer／明确 legacy 分支 | 实际 Codex reference bytes、required physical pages、whole table group、全双路 artifact 保留；native 不伪造 OCR 双路。全库／全包覆盖未接受保持未完成。 |
| 3 | Java 加 OER health 与 `index-canonical` 分支；取回 sealed chunks 建立 current corpus | index receipt 绑定完整 ordered projections／scope／module／chunk count／signature；failure 先清旧receipt。禁止把 unreviewed parser bundle直接/index。 |
| 4 | strict hit／source-unit gate 改用完整 canonical envelope，对业务模型提供只读 view | 保留 unknown-ID、payload equality、role／scope、finite score、完整成员／UTF16门控；不把 whole table 再按1400字符拆碎。任何 DTO转换需证明 roundtrip不丢字段。 |
| 5 | Advisory 检索适配到 shared permitted canonical sources；绑定其实际文档scope | 普通咨询独立检索、不是复用审查意见为权威依据；新 index key／source change stale 规则，防止使用另一scope／旧索引。小来源 stuffing 若保留也必须使用相同 accepted snapshot／完整单位并明确其检索模式。 |
| 6 | DTO pointer→Java asset代理→两页面共享panel | 来源／projection／module及实际bytes验证；跨页表组所有页／region映射；旧文本引用可读，资产失败不升级为已接受。 |

中央排除规则继续在 upstream file／page 选择和 Python policy 两处执行：标准 NTT/SCT/SCC 模板 Drafting-only；实际项目 NTT/SCT/SCC 和 PRE 保留；BQ/SOR/General Summary、非PRE works PSS、SL非技术库排除；未知普通来源保留unknown候选，不从正文关键字静默删段。Python `/index` 是scope-checked兼容入口，不因此变成已发布sealed projection；canonical 分支需要明确要求 registry 来源。

## 后续应新增的契约验证，当前均未执行

1. 同源 anonymous dual bundle／adoption → `/project` → snapshot GET → `/index-canonical` → `/retrieve` → Java evidence pointer 的完整 wire fixture；assetRefs、quality未知字段、table cells／relations、IDs／hashes全量 roundtrip。
2. 同页figure/caption和两页whole table group，各自文字命中回取准确完整原页资产；源SHA、projection、asset bytes／module／scope错配失败，旧snapshot不能指向新文件。
3. XYXY与XYWH的显式转换、物理页／Word原生位置、UTF16含non-BMP文本、空figure文本＋caption关联；不把空figure文本当OCR漏字，也不伪造paragraph页码。
4. canonical code／quality／adoption／scope／role／source/model改变，旧index receipt失效；index失败、source变更和health改变不得复用前一receipt。
5. Vetting与Advisory在相同allowed sources上的共有检索snapshot，以及Advisory tender/contract/fullset的不同候选池；standard template／held answers／SL排除页不能进入真实模型上下文。
6. UI mock契约分别验证新资产panel／stale／incomplete表组；随后使用真实原件、真实BGE和内置浏览器做独立业务验收。源码契约、真实模型与原件质量、前端接受分别记录。

现有 Python [test_oer_server](../../tools/vetting_eval/test_oer_server.py#L283-L410) 和 [test_oer_source_registry](../../tools/vetting_eval/test_oer_source_registry.py#L146-L218) 已有 isolated TestClient／资产身份门控样本；Java现有检索wire／health tests可作为契约骨架，不是本轮已执行的跨语言接通验收。

## 核心被审计文件字节身份

以下 SHA-256 在本轮只读核查后读取。后续修改后按新源码重新核对，引用行号也须更新；不要用本表追认后续版本。

| 文件 | SHA-256 |
| --- | --- |
| `service/vetting/VettingRetrievalClient.java` | `04fbe09178b863aca748cbdb9b3bbb6d9278e0b271c22bf23e1696467d93996a` |
| `service/vetting/VettingRetrievalHealth.java` | `0b91c6537b883165cce1f64b1983f9221615b4a4de3b3e91b5280598bc3eca76` |
| `service/vetting/VettingCorpus.java` | `0281c52075c19f1aebcaa89ef3fd4995dfb37ad33c07c291eef35678889423de` |
| `service/advice/AdviceService.java` | `b2b9d3d3e53f0fd9af7dc29325e08303cad7b3a437eb8f77bde47e5df628b1ca` |
| `tools/vetting_eval/oer_server.py` | `1dad42f977d5cba30bfaaa8314a05e258ebcf270a727ab2ec8d986e69df7fbff` |
| `tools/vetting_eval/canonical_projection.py` | `04ad81189bea16829be9f23353a81479b56a2ee945386bdd8b376fd37b724953` |
| `tools/vetting_eval/oer_source_registry.py` | `12397853b2c7cea36d8e5df28e82351a5fb49c834db418153d477dec9e20dc36` |
| `ai_consense_web/src/views/VettingView.vue` | `7b2a6c16e879669ceb7bf773609d8e2f338c4f932562bf7b7f8280323ddfc886` |
| `ai_consense_web/src/views/AdviceView.vue` | `d7f5a281450575854f8dc8354d1fc0cc02d972d23aa3b58234445505b4819940` |

Java 文件相对 `src/main/java/com/consense/`，Python 相对 `ai_consense_service/`，Vue 相对 ConSense 根目录。文中的新增接口／类形状是最小实现建议，当前未部署。

文档核对：47 个相对文件链接均存在，所引用行号范围均在当前文件内，无行末多余空白；9 个核心源码字节身份已复核。审计期间 `oer_server.py` 新增请求证据 journal，已据此更新该文件 SHA 和端点行号；canonical／模块／资产契约结论未改变。该核对仅验证本记录的引用与基准，不是接口或模型测试。
