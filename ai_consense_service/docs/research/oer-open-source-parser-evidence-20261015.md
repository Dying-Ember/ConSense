# OER 开源解析方案：长文档、表单与提示词的一手证据

日期：2026-10-15。状态：只读研究／架构建议，未实施、未换型、未调参、未调用新模型，未上传原件或改变服务。环境约束按单张 H800 80 GB、250 GB 主机内存、最多五容器考虑。

本研究遵循 [OER 范围与执行规则](../oer-scope-and-rules.md)及[实施交接](../HANDOFF-ocr-embedding-rerank-20261008.md)。PRE、SL 技术章节在本文仅代表数百／数千页的长文档形态；不因研究重新纳入已排除的具体 PRE 资料，也不恢复其他 SL 版本的实际执行。图纸识别并路由排除。检索、embedding、rerank、Vetting、Advisory 不属于本文的解析效果结论。

## 1. 先给结论

1. **成熟方案的可取方向是分工解析及统一保真结构。** Docling 的 PDF 后端、OCR、layout、table、reading order 和文档组装各有职责；VLM 是可选路径。官方实现不支持“每页必须让两个 VLM 都完整重读，才可靠”的必要性结论。[Docling 架构](https://github.com/docling-project/docling/blob/6fadbae317b09c4dd4051c0f1fc1746c6baf04bc/docs/concepts/architecture.md)、[标准 PDF pipeline](https://github.com/docling-project/docling/blob/6fadbae317b09c4dd4051c0f1fc1746c6baf04bc/docling/pipeline/standard_pdf_pipeline.py)。
2. **PRE／SL 类大文件应先建立全文件低成本观察，再按页／区域使用视觉细读，最后单独装配跨页结构。** Docling 有原生 PDF 路径和页范围接口；MinerU2.5 的论文采用全页布局概览与原分辨率区域识别。两者都不能据此宣称千页文档的全部条款、跨页表格、脚注关系已经可靠恢复。[Docling 原生路径](https://docling-project.github.io/docling/usage/advanced_options/#extract-the-native-content-of-a-pdf)、[转换 API](https://docling-project.github.io/docling/reference/document_converter/)、[MinerU2.5 论文](https://arxiv.org/abs/2509.22186)。
3. **现成输出中的文字几何、表格单元格、父子关系和来源引用应保留。** 只保留 Markdown 或大块 text，会丢掉后续核对和局部回读所需的证据。DoclingDocument、PP-StructureV3 和 MinerU 的详细中间结果都比纯文本丰富；各引擎实际提供的字段应按真实版本消费。[DoclingDocument](https://docling-project.github.io/docling/concepts/docling_document/)、[PP-StructureV3 输出契约](https://www.paddleocr.ai/main/en/version3.x/pipeline_usage/PP-StructureV3.html)、[MinerU 2.5.4 输出说明](https://github.com/opendatalab/MinerU/blob/61cc6886fe3edda8aa1c5b8bd2b6eaedddb8af99/docs/en/reference/output_files.md)。
4. **表单有成熟组件，但没有查到能直接保证所有空白／签署／见证人归属正确的通用组件。** Docling 当前 Heron 已检测 Form、Key-Value Region 和选中／未选中 checkbox；KIE 提供实体分类与关系抽取。区域检测、OCR 文字、字段身份、是否留空、是否已填写及其 owner 是不同判断，不能互相代替。[Heron 官方模型卡](https://huggingface.co/docling-project/docling-layout-heron)、[Paddle KIE 工作流](https://www.paddleocr.ai/main/en/version2.x/ppstructure/overview.html)。
5. **修改提示词是有效的工程选项，尤其适合局部语义抽取，但不是已证明的质量修复。** 官方底层接口确实可以传入自由提示；正常 parser 输出仍多为 block 内容，未必自动进入字段／关系契约。需要保留原始观察，单独记录候选、证据及 unknown，并在完整来源分母上比较。[MinerU 自定义 prompts 接口](https://github.com/opendatalab/mineru-vl-utils/blob/2843b62a1c68feafce42fa52f6e21080eb094fc2/mineru_vl_utils/mineru_client.py#L530)、[Paddle DocVLM 底层 query](https://github.com/PaddlePaddle/PaddleX/blob/ffb64904d23708863ff5b8da312a5cbd52a7f462/paddlex/inference/models/doc_vlm/predictor.py#L648)。

## 2. 各类成熟组件的实际分工

| 组件 | 一手资料确认的能力 | 对 ConSense 有用的地方 | 仍需独立解决／验收 |
| --- | --- | --- | --- |
| Docling 标准 PDF 链 | 程序化 PDF 内容与坐标、OCR、布局、表格、阅读顺序及文档组装；可以另选 VLM pipeline | 长正文统一结构、表格与文字对齐、保留来源和章节容器 | 复杂表单完整状态、特殊跨页关系、具体来源泛化 |
| DoclingDocument | body／furniture 树、parent／child JSON pointer、文本／表格／图片／key-value 容器、bbox 和 provenance | 建立可回取的文档表示；让段落、表格和来源坐标不被纯文本序列化抹去 | 数据类型能表示关系，不证明每个 backend 已正确产出关系 |
| PP-StructureV3／PP-DocLayout | 版面区域和顺序，OCR，表格及可选公式／印章等专用模块 | 保存行级文字与坐标、表格 cell 及 HTML；小编号和标点可保留另一套观察 | 类别检测不等于字段身份／owner／空值语义；置信度不等于来源正确率 |
| PaddleOCR-VL | 按布局区域做文字、表格、公式、图表等识别；当前文档还有专门的跨页重整接口 | 复杂区域细读与表格结构候选，保原始 provider 声明 | 不把专用识别模型直接当作通用字段关系推理器 |
| MinerU2.5／MinerU | 全页低成本布局与原分辨率局部内容识别；引擎另有文档后处理 | 避免整个高分辨率页面一次生成的冗余；复杂表格及细字区域候选 | 跨页合并仍有规则和版本边界；清理型 Markdown 可能弱化非正文证据 |
| LayoutLM 系列／Paddle KIE | OCR 文字、二维坐标及视觉特征上的实体分类与关系抽取；通常配合任务数据训练 | 有固定字段集合的表单、问答字段关联，可参考其实体／关系分层 | 非即插即用的任意表单语义解析；无文字写区不是 OCR token 分类天然覆盖的对象 |

本表能力依据：[Docling 2025 技术论文](https://arxiv.org/abs/2501.17887)、[PP-StructureV3 产线说明](https://www.paddleocr.ai/main/en/version3.x/algorithm/PP-StructureV3/PP-StructureV3.html)、[PP-DocLayout 文档](https://www.paddleocr.ai/main/en/version3.x/module_usage/layout_detection.html)、[PaddleOCR-VL 模型卡](https://huggingface.co/PaddlePaddle/PaddleOCR-VL)、[MinerU2.5 模型卡](https://huggingface.co/opendatalab/MinerU2.5-2509-1.2B)、[LayoutLMv3 官方项目](https://github.com/microsoft/unilm/blob/31c5b904ca1bf2afb4c234a6675c683a4e5fc7cd/layoutlmv3/README.md)。公开论文或模型卡的 benchmark 只支持其声明的测试任务，不构成 ConSense 原件通过。

## 3. PRE／SL 类长文档的核心设计

### 3.1 先看完整文件，分阶段支出计算

以下为基于资料的工程建议，尚未实施或测量收益。

1. **完整建账。** 一开始读取文件身份、实际页数、每页尺寸／旋转、程序化文字与坐标、可用书签、嵌入图片及文字层异常，形成所有页均有处置状态的账本。可提取文字的页也必须记录可疑点，不能把“有 text layer”直接当作来源正确。
2. **原生优先。** 文字可用的正文保留 PDF 原生字符／文字 cell；DOCX 保留格式原生结构。需要理解多栏、表格和阅读顺序时使用相应结构阶段。扫描页、混合扫描区域、编码损坏或异常文字层进入 OCR／视觉路径。
3. **先全页布局，再局部细读。** 对需要视觉的页面保完整布局观察，将表格、细字、脚注、编号列表和表单区域作为有来源坐标的输入。不能先裁掉未知区域再宣称完整解析；布局漏检亦是独立失败类型。
4. **有根据地调用第二引擎。** 用于第一引擎缺项、结构冲突、局部难读或预先定义的抽查；多引擎结果并列保留。不能挑每个字段的最高分结果拼成一个虚构的单分支准确率。两模型一致也可能共同漏掉编号或空白区。
5. **文档级结构装配。** 在所有页的结构观察完成后，重建章节／条款层级、跨页段落和表格续接，保留原页引用。边界冲突进入局部回查，而非把整本原图和文本一并塞入一次 VLM 上下文。

依据边界：Docling `NativePdfPipeline` 只使用原生解析，不含 layout／OCR／table 模型，故适合低成本观察但不单独承担完整结构质量；MinerU2.5 的解耦策略支持布局引导局部识别，并不证明不经核查的裁剪输出完整。[Docling native 文档](https://docling-project.github.io/docling/usage/advanced_options/#extract-the-native-content-of-a-pdf)、[MinerU2.5 §3.2](https://arxiv.org/html/2509.22186v1#S3.SS2)。

### 3.2 分页、缓存和恢复有哪些现成支持

Docling API 明确提供 `page_range`、`max_num_pages`、`max_file_size`。`max_num_pages` 是超限拒绝条件，不能描述为“自动按该页数分批”。`convert_all()` 返回逐**文档**的结果迭代器，也不等于逐页流式交付；`DocumentStream`／`BytesIO` 是输入形态，不能据名称声称整次解析内存恒定。[官方 API 定义](https://docling-project.github.io/docling/reference/document_converter/#docling.document_converter.DocumentConverter.convert)、[大小与输入流说明](https://docling-project.github.io/docling/usage/advanced_options/#impose-limits-on-the-document-size)。

当前 Docling 标准 PDF 代码有阶段有界队列和 back-pressure，支持处理中的资源控制；它仍组装文档结果，不能直接推导出千页原件的磁盘 checkpoint 和跨进程恢复已具备。MinerU 2.5.4 CLI 也有 `--start`／`--end` 页范围，文档标明它们从零开始；与 Docling 的范围约定应分别绑定，禁止只换版本号复用旧页号。[Docling 队列实现](https://github.com/docling-project/docling/blob/6fadbae317b09c4dd4051c0f1fc1746c6baf04bc/docling/pipeline/standard_pdf_pipeline.py#L170)、[MinerU 2.5.4 CLI](https://github.com/opendatalab/MinerU/blob/61cc6886fe3edda8aa1c5b8bd2b6eaedddb8af99/docs/en/usage/cli_tools.md)。

建议由 ConSense 的现有编排职责管理页／章结果缓存和任务恢复：缓存身份包含原件 digest、源页、坐标变换、producer／模型／配方及契约版本；失败页不覆盖已成功页，不把未执行页称为成功。页面窗口的结果与跨窗口结构分开保存，边界页保留续接上下文。窗口大小、并发数和内存上限应通过实际文件测量确定，本文不提供未经测量的最优数字。

### 3.3 跨页不只是拼接 Markdown

| 关系 | 官方现状与证据 | 建议保留的核对依据 |
| --- | --- | --- |
| 跨页表格 | Paddle 当前 `restructure_pages(..., merge_tables=True)` 是单独步骤；MinerU 2.5.4 发布记录已声明支持跨页表格合并。能力存在，但不能据此推导任意嵌套表格均正确 | 每个页段和 cell 的原页坐标、重复表头、列兼容、行续接前后文本及原始单元格身份 |
| 章节／条款层级 | Docling 当前有独立 heading hierarchy 阶段，读取书签、编号和视觉样式；默认关闭，且有信号不足和扫描字体元数据缺失等边界 | 原编号和标题字面文本、书签来源、页序、父级候选及依据；同编号不自动证明同一条款 |
| 脚注／正文归属 | Docling 有 furniture 与 provenance；MinerU 历史 README 的清理目标含去掉页眉／页脚／脚注。清理正文和来源归档的目标不同 | 脚注文字、标记、原页位置与所属段落候选，不能仅看清理后的 Markdown |

证据：[Paddle 页面重整接口](https://www.paddleocr.ai/main/en/version3.x/pipeline_usage/PaddleOCR-VL.html)、[MinerU 2.5.4 发布记录及功能](https://github.com/opendatalab/MinerU/blob/61cc6886fe3edda8aa1c5b8bd2b6eaedddb8af99/README.md)、[Docling 标题层级](https://docling-project.github.io/docling/usage/heading_levels/)、[DoclingDocument](https://docling-project.github.io/docling/concepts/docling_document/)。当前 Docling 的 `mineru2_pro` preset 还明确说明未启用跨页表格合并；不能把上游 MinerU 产品的全部能力算到每个集成 preset 上。[官方模型目录](https://github.com/docling-project/docling/blob/6fadbae317b09c4dd4051c0f1fc1746c6baf04bc/docs/usage/model_catalog.md#vision-language-models-vlm)。

## 4. 表单、空白区和签署关系需要单独处理

**现成组件已能做部分表单观察。** Heron 官方类别包含 Form、Key-Value Region、Checkbox-Selected 和 Checkbox-Unselected。Paddle KIE 的职责则先 OCR，再 SER 实体识别，再 RE 关系抽取；LayoutLMv3 官方例程也区分 form understanding 与 layout detection。[Heron 类别定义](https://huggingface.co/docling-project/docling-layout-heron)、[KIE 说明](https://www.paddleocr.ai/main/en/version2.x/ppstructure/overview.html)、[LayoutLMv3 例程](https://github.com/microsoft/unilm/blob/31c5b904ca1bf2afb4c234a6675c683a4e5fc7cd/layoutlmv3/README.md)。

**这些证据不覆盖所有无文字写区。** OCR token 分类以已检测文字为主要对象；没有文字的签署线、留空值区或仅由段落留白隐含的区域，需要额外视觉／原生几何观察和明确任务定义。检测到 Form 或某一标题，并不能自行证明下方空白区的边界、法律角色、值状态。本文没有找到对 ConSense 的签署／见证／编号写区完整质量作出保证的官方评测。

建议将表单判断分开记录：印刷标签及编号、写入区域、字段身份、可见内容、空白／有值／不适用／unknown、字段归属和关系。保留整页上下文及局部原图，允许回查跨区／跨页关系。局部观察没有覆盖完整写区时，不得用 OCR 未检出文字推断“确认空白”。这是一项对来源真实性的验收要求，不是通过换一个 KIE 名称即可完成的功能。

## 5. 修改提示词：接口允许什么，效果仍需证明什么

### 5.1 正常 parser 与底层自由提示要分清

| 入口 | 官方源码确认的接口 | 可以作出的结论 | 不能直接作出的结论 |
| --- | --- | --- | --- |
| PaddleOCRVL 正常 page pipeline | 按区域类型构建 `OCR:`、`Table Recognition:`、`Formula Recognition:`、`Chart Recognition:` 等任务提示；`prompt_label` 选择任务标签 | 可以选择识别任务；正常入口并非单一任意业务提示字符串 | 改 prompt_label 就能输出字段 type／state／owner |
| PaddleX DocVLM 底层模型 | 输入对象包含 `image` 和 `query`；底层把 query 送入模型 chat template／生成 | 技术上能够发送自定义自然语言或 JSON 抽取指令 | 该专用模型对未公开验证的字段语义任务已有可靠能力 |
| MinerU 官方 `MinerUClient` | 构造函数有 `prompts: dict[str,str]`、`system_prompt`；按 block type 选择 prompt | 可以覆盖布局或内容任务提示；底层 `predict(image, prompt)` 接收字符串 | 所有自由提示都能维持布局解析格式、表格输出或完整字段关系质量 |

证据：[Paddle 任务提示及 prompt_label](https://github.com/PaddlePaddle/PaddleX/blob/ffb64904d23708863ff5b8da312a5cbd52a7f462/paddlex/inference/pipelines/paddleocr_vl/pipeline.py#L308)、[Paddle query 生成路径](https://github.com/PaddlePaddle/PaddleX/blob/ffb64904d23708863ff5b8da312a5cbd52a7f462/paddlex/inference/models/doc_vlm/predictor.py#L648)、[MinerU prompts 参数](https://github.com/opendatalab/mineru-vl-utils/blob/2843b62a1c68feafce42fa52f6e21080eb094fc2/mineru_vl_utils/mineru_client.py#L530)、[MinerU 底层 predict](https://github.com/opendatalab/mineru-vl-utils/blob/2843b62a1c68feafce42fa52f6e21080eb094fc2/mineru_vl_utils/vlm_client/base_client.py#L157)。这些是研究时官方上游版本，不代表本地冻结 adapter 已暴露相同参数。

**本地接线已只读核对。** 当前 [complete_ocr.py](../../tools/vetting_eval/complete_ocr.py) 的 Paddle 请求体（417行起）只传原图与固定解析开关，MinerU `/parse/jobs` 请求（452行起）只传原件身份、OCR mode 及输出格式，均未透传业务 prompt／字段 schema；[paddle_native_server.py](../../tools/vetting_eval/paddle_native_server.py) 接受原图后执行 `runtime.recognize(raw)`，[mineru_native_server.py](../../tools/vetting_eval/mineru_native_server.py) 明确保留官方 parsing／prompts／sampling。因此“官方底层能改提示词”和“当前正常入口已经用该提示词提取 typed fields”是两件事；本轮未修改这条接线。

### 5.2 对当前问题最有价值的使用位置

建议把提示词用于**保留识别观察之后的结构语义候选提取**，或有界区域回读：输入原页／局部图、原始 OCR／布局与稳定 source ID，要求分开输出印刷文字、字段候选、写区、状态、owner 候选及证据；不足则 unknown。短表单可保整页上下文，长文档按章节／相邻边界页处理。提示词能澄清任务和输出对象，也可能改善模型把写区当正文省略的问题；实际收益尚未在本轮运行验证。

不能仅在现有 OCR recognition prompt 后加“输出 type/state/owner JSON”便称语义接线完成。MinerU 内容结果直接赋给 `block.content`，Paddle 也把生成结果赋给 block 内容；模型返回 JSON 可能只成为一段 text，并不会自动填充上层 typed field／relationship。布局输出还有专门解析逻辑，改变其格式可能直接破坏解析。[MinerU 内容赋值与布局解析](https://github.com/opendatalab/mineru-vl-utils/blob/2843b62a1c68feafce42fa52f6e21080eb094fc2/mineru_vl_utils/mineru_client.py#L884)、[Paddle block 内容生成](https://github.com/PaddlePaddle/PaddleX/blob/ffb64904d23708863ff5b8da312a5cbd52a7f462/paddlex/inference/pipelines/paddleocr_vl/pipeline.py#L542)。

提示词候选应与原始识别结果分开保存，实际采用仍要满足：源位置相符、字面证据可回取、写区覆盖完整、关系对象明确、旧正确项保持及完整分母评分。JSON schema 只能限制结构和类型，不能证明模型没有遗漏、臆造、误归属或误报空白。对源图中本来不存在的内容，提示词也不能赋予真实性。

## 6. 许可与可部署证据，按版本分别看

研究时核对的官方代码参考点：Docling main `6fadbae317b09c4dd4051c0f1fc1746c6baf04bc`；PaddleOCR main `dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf`；MinerU master `b7f3dd0c5f6e6d66a298239ffd02b328b873918e`；MinerU 2.5.4 tag `61cc6886fe3edda8aa1c5b8bd2b6eaedddb8af99`。以下不把当前 master 的新功能或新许可追溯到旧包／旧模型。

| 对象 | 已核许可 | 采用时的实际边界 |
| --- | --- | --- |
| Docling／docling-core／docling-serve 代码 | MIT | 权重和其他依赖另核。官方主项目也明确要求查 individual model license |
| Docling Heron 权重 | 模型卡标 Apache-2.0 | docling-models bundle 同时列 CDLA-Permissive-2.0 与 Apache-2.0，不能给全部模型一概贴 MIT |
| PaddleOCR 代码、PaddleOCR-VL 官方权重 | Apache-2.0 | 仍应冻结具体模型 revision 和随包许可；不同组件分开核对 |
| MinerU 2.5.4 代码、MinerU2.5-2509-1.2B 权重 | AGPL-3.0 | 不因新版本改许可而自动解除旧权重／旧依赖义务 |
| MinerU 当前 master | Apache-2.0 为基础，附加商业门槛及对外在线服务标识义务 | 不能简称为无限制纯 Apache-2.0，也不能据此替换冻结版本 |
| Microsoft LayoutLMv3／LayoutXLM 子项目 | README 明确 CC-BY-NC-SA-4.0 | 不因 unilm 根目录或部分源码头的其他许可而假定子项目可无条件商业采用；Paddle 再实现和上游权重分别核查 |

许可来源：[Docling](https://github.com/docling-project/docling/blob/6fadbae317b09c4dd4051c0f1fc1746c6baf04bc/README.md#license)、[Heron](https://huggingface.co/docling-project/docling-layout-heron)、[Docling models](https://huggingface.co/docling-project/docling-models)、[PaddleOCR LICENSE](https://github.com/PaddlePaddle/PaddleOCR/blob/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/LICENSE)、[PaddleOCR-VL 模型卡](https://huggingface.co/PaddlePaddle/PaddleOCR-VL)、[MinerU 2.5.4 LICENSE](https://github.com/opendatalab/MinerU/blob/61cc6886fe3edda8aa1c5b8bd2b6eaedddb8af99/LICENSE.md)、[MinerU2.5 权重卡](https://huggingface.co/opendatalab/MinerU2.5-2509-1.2B)、[MinerU 当前 LICENSE](https://github.com/opendatalab/MinerU/blob/b7f3dd0c5f6e6d66a298239ffd02b328b873918e/LICENSE.md)、[LayoutLMv3](https://github.com/microsoft/unilm/blob/31c5b904ca1bf2afb4c234a6675c683a4e5fc7cd/layoutlmv3/README.md#license)、[LayoutXLM](https://github.com/microsoft/unilm/blob/31c5b904ca1bf2afb4c234a6675c683a4e5fc7cd/layoutxlm/README.md#license)。此为文件许可核对，不是具体商业使用的法律裁定。

**生产落地证据的强度有限。** Docling 提供独立 serve 项目、稳定 v1 API、CPU／CUDA 容器和部署示例；Paddle 文档提供本地与服务化部署；MinerU 模型卡指向官方 Open API。这些能证明存在可用工程入口及官方服务，不能证明 ConSense 的原件准确率、千页最坏内存、断点恢复、目标 H800 性能或五容器验收已经通过。[Docling Serve](https://github.com/docling-project/docling-serve)、[Paddle 部署文档](https://www.paddleocr.ai/main/en/version3.x/pipeline_usage/PP-StructureV3.html#3-development-integration-deployment)、[MinerU 模型卡部署入口](https://huggingface.co/opendatalab/MinerU2.5-2509-1.2B)。不以 stars、下载量、演示或营销用语授予生产可靠性。

## 7. 对 ConSense 的推荐顺序

**先复用分工和结构契约，再决定是否替换引擎。** 优先核查现有 Paddle／MinerU 输出是否有行级 OCR、表格 cell、来源几何和原生关系被 adapter 或消费层丢失；当前已冻结模型能提供的证据先完整接住。Docling 是最值得评估的统一文档表示和长文档结构组件参考，但本文不建议未经同源验证立即将它换成新的主 parser，也不建议再加一套独立并行的万能解析服务。

针对长正文，优先原生文字与版面结构，建立全文件处置账和页／章缓存，视觉调用只承担需要它的页／区域；跨页续接及条款层级作为明确的文档级职责。针对表单，保完整页上下文和写区观察，再由有证据的语义阶段输出状态与归属。提示词修改可在该语义阶段作为优先研究变量，候选与原识别结果独立。

五容器下的建议角色仍可为 App（含前端静态资源）、MySQL、Qdrant、CPU coordinator、单 GPU 执行槽；Docling 若后续获准采用，优先作为既有解析角色中的库／阶段评估，而不是自然增加第六个常驻容器。单 GPU 任务按有界队列和实际释放协议串行／受控运行，不能假设把所有模型同时常驻就更可靠。此为部署设计推论；本文没有安装依赖、构建镜像或验证其相容性。

后续若进入实施与实测，需分别给出：长正文的逐页／章节覆盖、文字保真、阅读顺序、跨页表格／行续接、脚注及条款父级；表单的字段／写区／值状态／owner；工程的时间、峰值 RAM／VRAM、失败恢复和五容器实际计数。完整保留未知与旧正确项，使用从未参与提示设计的同类新资料复核泛化。**七页训练或提示词样本的改善，不能代替整本 PRE／SL 类资料的泛化结论。**
