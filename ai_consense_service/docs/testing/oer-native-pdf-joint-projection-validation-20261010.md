# 原生 PDF 字形与双解析版面联合投影验证

2026-10-10，Asia/Shanghai。执行范围先按 [OER 已确认范围与规则](../oer-scope-and-rules.md)；本文记录独立 native PDF canonical recipe 的代码契约，不代表原件全文质量、模型准确率、赛题回答或前端验收。

## 实现与固定接口

实现：`tools/vetting_eval/native_pdf_canonical_projection.py`。入口为 `produce_native_pdf(native_bundle, dual_bundle, explicit_adoption, *, artifact_store)` 和 `validate_native_pdf_projection(projection, *, artifact_store)`。二者均需真实 `ArtifactStore`，错误统一为 `CanonicalProjectionError`。输入、snapshot 和原始文件都不以外部路径自动读取。

固定常量：

| 字段 | 值 |
| --- | --- |
| schema | `consense.native-pdf-canonical-projection.v1` |
| producerKind | `native_pdf` |
| storageShape | `full-native-pdf-joint-internal-v1` |
| adoption schema | `consense.native-pdf-canonical-adoption.v1` |
| regionReview schema | `consense.native-pdf-region-review.v1` |
| projectionVersion | `source-reviewed-native-pdf-prose-and-visual-layout-v1` |

这是独立内部 snapshot。既有 native DOCX 与扫描 producer、source reference 格式、Java observer、registry/server 未因本次投影改变。公共传输必须使用独立 compact recipe，继续限制整个公共请求／响应不超过 16MiB；内部完整证据大于该上限不能通过删除观测字形、未知字段、样式或表格绕过。

## 原件与文本约束

native bundle 的实际源 PDF、原始 Java export、三份实际 observer source 和已有 appearance artifacts 每次都冷读 CAS，并调用独立 `native_pdf_observation` 门控。实际 PDF 页框还由声明版本的 pypdf 重读。投影不独立重放 PDF operators，不证明每个字形确实在原页可见或法律语义正确。

两路 parser 的实际 raw outputs／assets 也冷读；重新 `normalize_parser_run`，独立单页 capture batch 重新 `combine_parser_captures`，重建整个 dual bundle 后逐字段核对。调用者重新签字后的 normalized text 不能替代 actual raw。双路的模型、运行、配置与聚合算法身份继续保留。

文本只拼接实际 page glyph 的完整 Unicode 值。null 保留在原观测和零长度 UTF16 span，不造 replacement character；一个 glyph 的 surrogate pair、结合序列或多字符值不会按字符串片段截断。OCR candidate text 和 `source_reference.text` 保持独立，不能补写 native literal。

每个采用区域有完整、唯一、同页并按 operator capture 顺序列出的 `capturedGlyphIds`，独立声明其 `glyphIdsInReadingOrder` 排列。读序和空白分隔属于 source-review 声明，不伪称 native observer 已识别文本行。分隔符只允许空格／换行，必须绑定连续的实际 glyph ID，并声明 `reviewMethod=original_page_raster_boundary`；任意词语、tab、重复分隔或非连续位置拒绝。

已知 hidden rendering mode、zero alpha、outside crop 字形不能进入采用正文，仍在原始 CAS 与显式 `unadopted_unknown` inventory 中。`operator_visible_unverified` 可以参与明确 source-reviewed 区域，但 effective visibility 继续为 unknown。每个已观测物理页均有一条严格整数页号、capture 顺序的 unadopted inventory；采用与未采用字形完全分割全部 page glyph。AP glyph 不进入 page glyph membership。

采用区域所在页必须有完整 native operator capture。其他未采用页可保持 partial／unknown，不能因局部未知删除整源或已经可靠采用的其他页。scope 的 required pages 是 capture 必要范围，不能解读为该范围全部已接受。

## 位置、版面及闭合

source reference 必须为实际原 PDF 的 v2，绑定实际原件字节、物理页和源页 PNG CAS。区域有 measured normalized xyxy，native glyph 的 normalized rendered-crop xywh 必须与采用区域相交；页框 hash、rendered-crop 与参考图的宽高比例均核验。这个保守关联不证明 exact glyph outline、局部裁剪交集、后绘制遮挡或 pixel visibility。

每个区域同时保存两路完整 layout block 的 ID、实际 block／candidate-text hash、raw provenance 和 original-page transform。采用的是区域对应的整 block layout，不从不同 parser 逐词拼接。`layout.rasterMappingReview[runId]` 必須逐路绑定：

```json
{
  "status": "source_reviewed_declared",
  "method": "original_page_raster_geometry_review",
  "referenceRasterSha256": "实际参考原页 PNG SHA",
  "inputRasterSha256": "该路实际输入 asset SHA",
  "originalPageTransformSha256": "该路实际 transform 的 canonical JSON SHA",
  "relationship": "same_original_raster"
}
```

identity transform 只有输入与参考 raster SHA 相同且 relationship 为 `same_original_raster` 才接受。affine transform 需明确 `source_reviewed_affine_to_reference_raster` 声明，保留真实输入／参考图／变换身份和 unknown；相同尺寸本身不证明是同一图。原图不是 OCR／文本的替代品。

units 为 whole text units，按实际 source-reference 页／区域读序排序，region 只能属于一 unit。所有关联到采用区域的 reference relation 均须列入 `sourceRelationIndices` 且两端完整采用；boolean 不能伪装 relation index。continuation／qualifier 必须位于同一原子 unit，continuation 按源顺序且不可循环／分支／重复。parent 关系不可循环／重复父节点。parent、sidebar、caption、header、margin note 等合法跨 unit 关系保留明确 typed endpoints／`between_whole_units` scope，后续 consumer 仍须完整 closure，不能只取局部正文推断全条。

当前仅接受 prose。native table／cell、tableGroups、自定义 assets 和 AP-to-page text 是明确拒绝，原始 native evidence 不删除。SL 独立技术范围 policy 尚未接到此 recipe，因此 SL library 暂拒绝，不代表技术库被排除。BQ／SOR／General Summary、works-related PSS／SL nontechnical、评价答案仍按已有范围 policy 拒绝；Drafting 标准模板不得进入 Vetting／Advisory。

## 冷重构与未知状态

native bundle、dual bundle、adoption 都有 canonical JSON CAS descriptor，原件／observer／raw parser／reference／PNG 字节有各自 descriptor。校验从三个 CAS 输入重建所有 unit、chunk、原始 UTF16 span、layout、来源／算法身份、quality 与未知清单，再比较完整 snapshot，重新签 payload 不绕过核验。

identity 同时绑定 projector source SHA 与八个实际依赖模块 source SHA。`sourceHash` 是 canonical 结构／配方身份，不能替代原件 `sourceSha256`。snapshot 的 semanticScopeVerified／humanConfirmed 为 false、wholeOriginalSourceCoverage 与语义质量为 unknown。

消费者接入发现统一 sourceIdentity 要求显式 `nativeTableMetadataVersion`。因此 producer chunk 增补 `native-pdf-no-table-adoption-v1`，只绑定当前表格未采用配方，不宣称支持表格；runtime 不补字段。旧冻结 snapshot／receipt 保留，新源码与同一 19／139 契约复验见 [独立 registry／HTTP 验证](./oer-native-pdf-registry-http-validation-20261010.md) 和原 run 下 `schema-identity-v2-*` 目录。

## 验证与保留证据

测试 `test_native_pdf_canonical_projection` 最终 19 项，0 跳过。原生 PDF、原生 DOCX、扫描 projection 与 source-reference 相关契约使用同一次冻结源码继续回归；最终数量和日志 SHA 以本机 validation receipt 为准，不与旧 snapshot 测试相加。

fixture 使用可携带 authored 2 页 PDF、最终实际 PDFBox 2.0.31 Java export／三份 source bytes、真实 CPU-rendered 原页 PNG。两路 raw parser JSON 是明确 authored、未执行神经模型；它们和独立 reference text 故意不同于 native 字面值。正例精确输出 `Visible fi cafe\nSecond page`。UTF16 null／surrogate／结合序列与 partial page 是明确 synthetic raw-export contract mutations，不冒称 unchanged PDF operators 产生这些字形，也不计 OCR 质量。

本轮 red 包括 partial adopted page、unadopted 反序／bool、字形与区域分离、known hidden／alpha／outcrop、boolean relation index、循环 continuation。历史 fixture `filename` 字段错误和不匹配 pypdf 环境失败保留；它们不作为产品行为 red。cold roundtrip 首次暴露输入 dict 排列与 CAS sorted JSON 导致 visual block 数组不稳定，已统一实际 run ID 排序。

执行 Python：`.local/oer/venvs/retrieval/Scripts/python.exe`，pypdf 6.19.0。忽略的 run 为 `.local/state/native-pdf-projection-20261010/run-044305-c59746/`，保留所有失败、final focused／related regression 日志、最终 authored snapshot／CAS 及 `validation-receipt.json`。原件和权重未改；未启动 GPU、HTTP 服务、LLM／BGE，也未部署在线服务或完成官方问题／Vetting 报告评分。
