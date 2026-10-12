# Source-reference v2 读取与完整性验证交接

更新：2026-10-10，Asia/Shanghai。本文遵守 [OER 范围与执行规则](../oer-scope-and-rules.md)。本轮只实现原件参考标注的读取／适配与声明完整性检查；没有改解析采用逻辑、canonical projection 或 OER server，没有调用模型或改变在线服务。

## 当前交付与接口

新增 [source_reference.py](../../tools/vetting_eval/source_reference.py) 和 [契约测试](../../tools/vetting_eval/test_source_reference.py)。统一产物声明 `consense.source-review-reference.v2`，共同使用 `pages[]`、`regions` 和显式 `sourceAnchor`。

```python
from source_reference import read_source_reference, validate_source_reference

capture = read_source_reference(reference_path, expected_sha256=expected_input_sha)
document_v2 = capture.reference
original_reference_bytes = capture.raw_bytes

# 证据是调用者明确提供的实际 bytes；模块不跟随 JSON 中的路径读取文件。
report = validate_source_reference(document_v2, evidence_bytes={
    original_source_sha: original_source_bytes,
    original_page_image_sha: original_page_image_bytes,
})
```

`SourceReference` 同时保留确切 `raw_bytes`、`input_sha256`、原 `input_schema`、`input_reference_version`、适配器名称和初次完整性报告。原始空格、BOM、换行及完整字节不因规范化而变化。模块返回结果，不自动写文件；本轮证据捕获脚本分别保存原输入字节与规范化 JSON，并绑定双方 SHA。

`read_source_reference` 支持明确的路径或 bytes，可按调用者提供的 SHA 固定输入。v1 只允许两个已经核查的具体版本：

| 原参考版本 | 明确适配器 |
| --- | --- |
| `gct1-complete-visible-page-source-review-20261009-v2` | `gct_single_review_image_v1`，单 `reviewImage` → `pages[]` |
| `sl-amr-complete-original-3436-3438-source-review-20261010-v1` | `sl_pages_and_table_group_v1`，保留完整表组与页面读序 |

GCT 文件名中的 `v2` 是其参考修订号；该输入仍声明 schema v1。其他 v1 版本不能仅按类似文件名或字段形状自动猜测；新参考直接声明 schema v2。未来原件值或版本改变后，产生新参考身份，不把旧样板值自动迁移为项目事实。

## 校验内容与保留边界

实际输入 JSON 拒绝重复键和非有限数值。读取器核对 SHA 格式、唯一页／区域 ID、PDF 物理页别名、完整页面读序、原页文字与区域顺序的一致性、引用端点、归属及坐标范围。同时提供 raster 和 normalized 框时，核对两者在已声明图片尺寸中的一致性。明确提供实际 PNG bytes 后检查 SHA 和 PNG 头部尺寸；其他图片格式只验证 SHA，尺寸验证保留为未支持状态。

PDF 原页锚点携带原件 SHA、物理页与实际 page raster 身份；图片的原件关系继续是声明，字节 SHA 通过不表示已经证明该图片确实由该原页渲染。原件视觉采用、语义和精确 glyph 几何仍独立核验。

SL 的逻辑行／物理 row parts、列跨度、checkbox 所属、同项跨页关系、表头／表单／Remarks 引用均检查现有声明的完整性。续行不能添加额外 checkbox IDs。模块没有宣称支持任意表格 row grid、嵌套表或 vertical merge 的正确性；这类结构保留原字段，记录 `preserved_not_fully_validated` 或 `unknown`，不逐格合成解析真值。

没有提供实际证据 bytes 时，来源／图片字节状态是 `unverified`。未知角色不从 region ID 或文字猜测；空框、项目值、适用性和历史暴露均保留。未知字段保留在规范化对象和原始输入中，同时在报告标明不解释。报告恒保留 `semanticAcceptance=not_evaluated`、`humanConfirmationVerified=false`、`parserAdoptionPerformed=false` 和 `unknownsUpgraded=false`。

## 原生 DOCX 与打印 PDF 的区分

schema v2 可声明 DOCX 原生锚点：`kind=docx_native`、原件 SHA、`partName=word/document.xml`、`bodyElementIndex`、`nodeKind` 和实际 `tablePath` 的 table／row／cell 索引。当前只支持主文档位置；header／footer 等其他 part 不静默映射。嵌套 table 的父级须定位至原生 cell。

原生 DOCX region 不能填写假造的 `pageNo`、`physicalPageNo` 或将打印框写成原生 bbox。打印 PDF 使用独立 `docx_rendition_page`／`docx_rendition_region`，明确原 DOCX SHA、不同的 rendition SHA、rendition 内物理页、source image 关系和 `mappingStatus`。声明索引／映射完整性通过仍不证明 native 内容或打印映射已由原件核验。

本轮 DOCX 只通过人工契约 fixture 验证上述接口，没有声称真实 DOCX 原件已经完成 v2 标注／映射验收。

## 本轮验证证据

CPU 契约测试命令，在 `ai_consense_service/tools/vetting_eval` 下执行：

```powershell
& 'D:\R&D\ConSense\.local\oer\venvs\retrieval\Scripts\python.exe' -m unittest test_source_reference -v
```

**15/15 通过，0 failures、0 errors、0 skipped。** 覆盖 byte 保留、SHA 不匹配、JSON 歧义、错误来源／页／坐标、缺引用、跨页 checkbox、原生 DOCX／rendition 分离及未知扩展。完整命令输出和测试代码快照绑定在下述本地收据。

两份真实参考均无损适配：除产物 schema 版本外，全部原输入已解析字段通过递归保留检查；原件、原页图片、旧参考字节在验证前后未变。实际证据 bytes 均由显式 source-input manifest 提供，没有读取模型候选或 canonical 文本。

| 真实参考 | 规范化范围 | 已验证实际字节身份 | 保留的未确认状态 |
| --- | --- | --- | --- |
| GCT 第 1 页，参考修订 v2 | 1 页、12 regions | 原 PDF＋1 原页 PNG，2 个 SHA | 25 条 scoped unknown；角色、边界精度及语义没有升级 |
| SL AMR 3436–3438，参考修订 v1 | 3 页、116 regions | 原 PDF＋3 原页 PNG，4 个 SHA | 166 条 scoped unknown；1 项未支持的 generic grid topology |

unknown 数量来自逐区域／几何／状态记录，**不是 OCR 错误数、正确率或候选失败数**。这两份材料仍是 `development_historically_exposed`、`humanConfirmed=false`；无损适配不等于语义通过、盲测通过或业务验收。

主证据：[validation-receipt.json](../../../.local/state/oer-implementation-20261009/source-reference-adapter-validation-v1/validation-receipt.json)，SHA-256：

`88a80092112133ada329e03bc25d21dae9bce364be4f188a5ee44e8bd170d5a8`

收据绑定代码／测试 SHA、原参考 schema／版本／SHA、exact raw input、规范化输出、完整性报告、原件／图片验证及测试日志。产物与失败／未完成状态留在忽略的 `.local/state/oer-implementation-20261009/source-reference-adapter-validation-v1/`，不会随源码发布；异机需自行提供原件和参考输入，不能把本地路径当成仓库已携带材料。

规范化输出 SHA：

| 产物 | SHA-256 |
| --- | --- |
| `gct1-normalized-reference-v2.json` | `dd4ebf95a543f6d9c75db7a5c163b1dbb353a5677bee25b47abe05b73d4fce99` |
| `sl-amr-normalized-reference-v2.json` | `59e9a539d41fa8f3dd0dc41f6a6d53268064248a38f3a36863a85aa8d5bd2851` |

后续接手可使用本接口读取更多直接 v2 的整页参考，先核对实际新结构、来源和身份，再独立进行原页／候选接受及检索评价。本轮没有修改 `canonical_projection.py`、`oer_server.py`，没有自动生成 gold 或采用 parser，没有推送或切换在线链路。
