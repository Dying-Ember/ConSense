# Advisory 固定开发基准接缝（实现候选）

2026-10-12。本记录描述已保存代码和作者静审，不代表 Maven、真实 HTTP、模型、数据库、前端或业务验收；Maven 由 Root 统一执行。本轮没有切换在线配置或部署服务。

## 显式标准解释与默认项目问题

`POST /api/advice/{projectId}/ask` 在原 `question`、`scope` 和 `replyToMessageId` 之外接受可空 `basisScope`：

```json
{
  "question": "Explain the cited GCC standard provision and its exceptions.",
  "scope": "fullset",
  "basisScope": "reference_only"
}
```

只有明确 `basisScope=reference_only` 才直接使用已经配置并启用的 `FixedBaselineRetrievalClient`，不创建项目 canonical Session，不检索旧 flat 项目索引。未填 `basisScope` 保留原 `project_contract` 默认路径；`scope` 仍只接受 `fullset`、`tender` 或 `contract`，不把 `reference_only` 混作项目文件范围。共用 fixed client 默认关闭，本代码不改其配置。

显式标准解释中模型不能把回答升级成 `project_contract`。若模型输出该值或其他未知非空 basisScope，回答保留为 `insufficient`，提示标准原文不能证明项目采用、版次或修订。引用不能证明完整原件、法律归属和库全部质量已经验收。

默认项目路径若使用 canonical，必须先加载当前项目来源、核验索引并成功取得项目证据，然后才独立添加 fixed standard lane；项目 canonical 失效时继续报原来源错误，不能静默 fallback 到标准解释。未启用 canonical 的既有 legacy 项目路径保持原行为，不借本候选改写它。

## 项目采用门与独立引用

fixed hits 不伪造 `canonicalEvidence`、canonical Session 或项目 SourceDocument。新的 `CitationVO.fixedBaselineEvidence` 保存固定父 ID、冻结 manifest SHA、版本、source family、原件 SHA/物理页及明确的开发质量/适用性 unknown；`documentId` 为 null。原件/父/表回取链接使用独立 `/api/oer/fixed-baseline/...` 入口；入口实现及前端适配由 Root 管理，不由本记录冒称已部署。

现有 `project_contract` guardrail 保持要求实质 tender 引用，并核标准采用上下文。新固定标准 `applicabilityContextComplete=false`；它不会因项目文本提及标准缩写、目录或邮件而变成适用已证。真实版本/修订采用模型和项目证据门尚待专门实施；当前候选会拒绝仅靠 fixed 引用形成项目合约结论，而不假称这些门已经完成。

fixed 模型材料按共用 client 的完整父/完整表组一次性生成，几何、原始 audit 和大 raw payload 不直接放入 prompt。200,000 字符只作为完整组准入上限；超出时抛明确不足错误，不截断正文、表格行或条件。Responses 的实际 token 预算仍由既有 transport 核验，这个字符上限不是 token 数或模型窗口声明。

Responses 可以使用明确的 fixed 身份，或项目 Session 与 fixed 冻结身份的联合摘要；计数/生成前后 callback 分别核项目来源和 fixed `assertCurrent`。Chat 路线生成后及历史原子写入前也核 fixed 来源。显式澄清回复必须保留原请求的 reference-only/project-contract basis，不能跨模式继承未回答问题。

## 窄验证候选

`AdviceFixedBaselineTest` 包含 7 个作者构造、内存保存、Mockito adapter 的控制：显式标准解释无 Session；模型不能升级成项目结论；固定标准独自不能过项目采用门；canonical 失败不 fallback；fixed 默认关闭时拒绝；模型期间冻结来源变化不得保存历史；完整模型材料一次且不序列化 raw audit。没有真实 HTTP、模型、数据库或向量服务调用。

同时应由 Root 统一回归 `AdviceCanonicalIntegrationTest`、`AdviceDocumentScopeIntegrationTest` 和既有 Advice Responses tests，以核冻结 canonical 来源与 transport 行为。新增源码及测试目前尚未由本文作者编译/执行；测试结果以 Root 的真实 Maven 收据为准。
