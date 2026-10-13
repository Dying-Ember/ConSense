# 七类解析缺陷：官方能力与最小依赖增量核查

日期：2026-10-12，Asia/Shanghai。本文是独立官方来源研究，配合本轮解析缺陷优先级评估；不是已实施、已训练或已部署方案。先读当前 `oer-scope-and-rules.md` 与 handoff 顶部，未安装软件、下载模型、修改业务／解析源码、启动服务或操作向量库。

**本轮最新用户边界：图纸文件完全不进入解析；只需路由层识别其为图纸并排除。** 不为 CAD、工程图、尺寸、图例精读或图纸关系抽取增加组件。规范／合约正文中本来就在范围内的插图或流程示意，仍需保全完整原图及有关文字，不扩成工程图语义解释。下表第三项已按该边界修正。

## 1. 今晚可确定的有限建议

1. 在既定 Java／POI／PDFBox、Paddle、MinerU full、pdfplumber／pypdf、PDFium 和 Camelot 原生表格候选基础上，新增**原生表单提取、可见控件／空栏解析、原生文字与布局关系恢复、完整图文保全**这些代码模块；没有当前来源实验足以证明必须再引入第三个完整 OCR／文档理解框架。
2. 原生 AcroForm 与打印／扫描勾选是不同输入。原生使用现有 PDFBox 或 pypdf 字段／Widget 通道，保 `/V`、`/DV`、`/AS`、`/AP`、字段层级及位置；视觉控制使用当前原生图形／PDFium 渲染、OpenCV 与 OCR 路线。不能用“未找到 Widget”推断“没有控件”，也不能用 OCR 无文本推断“没有空栏”。
3. 现有模型对视觉勾选确实仍分不清时，可以考虑一个轻量、受标注监督的控件／状态子模型；它解决的应是指定失败区域的选中状态，而不是重复整页解析。优先复用既有 Paddle/PyTorch 训练推理环境，无需首先新增另一个深度学习框架。是否需要新增权重由正常输入覆盖后真实失败决定，尚未训练或验证。
4. 复杂正文的文字可能全部保留但读序／源注释归属错。先利用原生文字坐标、字体、编号与已有版面结果建立结构关系，保候选与未知；引入一个替代解析器并不能证明它会正确恢复条款和旁注。
5. 图纸只需身份路由与排除。范围内普通插图完整性应使用现有 PDFium 整页渲染与原文／caption／note 定位；不要只提取 PDF 的 image 对象来代表整图，因为文字、箭头与线条可以分布在独立矢量对象中。
6. Docling 是可保留的隔离对照候选，PyMuPDF 是可保留的原生 PDF 引擎候选；两者目前都不是本轮前三项修复的已证必要依赖，不能为了“也许有用”整体放进交付环境。

## 2. 官方能力：原生 PDF 表单不是一般文字层

### 2.1 pypdf：字段值与可见 Widget 双通道

官方 6.19.0 文档说明：根对象 `/AcroForm` 存放字段，页面 `/Annots` 中 `/Widget` 表达显示；字段可以通过 `/Parent`／`/Kids` 组织；同一字段可在多页有多个 Widget，隐藏字段又可能没有 Widget。`/V` 为实际值，`/DV` 为默认值。XFA 可以覆盖普通页面内容。[S01]

所以业务结构需分开保“字段存在／原始值／视觉观察”，不能只提 `get_form_text_fields()` 的字符串。原始未填、未识别、字段缺失是三个状态；默认值也不能等同于实际已选择值。

### 2.2 PDFBox：当前版本已有 checkbox／radio 支持

PDFBox 2.0.31 `PDCheckBox` 已有 `isChecked()`、`getOnValue()`，通过实际字段值与 appearance 定义关联。源码明确 On value 不一定是 `Yes`；没有 appearance 定义时可返回空字符串，故不能硬编码字符串或把缺 appearance 当可靠视觉结论。[S02]

同版 `PDRadioButton` 已支持 widgets 的 selected index 与 export values，并区分 radios-in-unison。这里能获得原生状态，不代表打印在 PDF 上的方框或扫描笔迹会自动变成这种对象。[S03]

`PDAcroForm` 可以识别 XFA，源码明确 dynamic XFA 不支持 flatten。[S04] pypdfium2 也注明 XFA 依赖具体 PDFium 构建是否含 XFA 支持。[S05] 当前未发现范围内真实来源一定使用动态 XFA，不能提前增加 V8/XFA 构建；若路由发现 XFA，应明确报告实际支持与渲染失败，不能将空渲染当空表单通过。

### 2.3 PDFium：控件渲染必须实际启用

官方 helper 要求在获取页之前 `PdfDocument.init_forms()`；`page.render(may_draw_forms=True)` 只有已初始化 form environment 才会画表单。annotations 也有 `draw_annots` 开关。[S05][S06] 因此“整页图片已生成”不证明表单显示态被保全，代码应核该配置及实际渲染。

建议存原件绑定的整页／控件区域图，并把字段值与可见状态冲突保成不确定。此处可以用现有渲染库落实，不等于需要新增一个渲染器。

## 3. 官方能力：可见空栏、勾选与分支

pdfplumber 提供字符、矩形、线、曲线和 annotation 位置，也有原生表单值的文档示例；适合机器生成 PDF。它不提供 OCR，且官方明确不负责重建 image 对象的像素内容。[S07] 打印框／线空栏可先保原生几何，状态需要结合可见图；无文字不应被文本导出过滤。

OpenCV 官方轮廓及形态学教程提供二值、轮廓、形状层级、开闭等基础操作。[S08][S09] 这些可用于候选控件定位和无文字区域观察，但官方教程没有证明某一阈值能识别所有打勾、擦除、圈选、扫描噪声或选择分支。原始 RGB 必须保全，二值图只是解析视图，无法判定的状态保 unknown。

Paddle PP-StructureV3 官方说明其子模块可独立训练／推理，已有版面、OCR、表格、公式及可选 chart 模块。[S10] 本轮读取的通用布局类列表不是专用 checkbox 状态保证；不能把“已安装 PaddleOCR”写成“所有视觉控件已实现”。必要新增的是控件解析分支和真实状态监督，而非必然新增框架。

## 4. 官方能力：复杂正文与图文保全

pdfplumber 官方提供原始字符、矩阵、字体及位置，也支持坐标排序／`use_text_flow`，同时提醒 PDF 底层流顺序不总是逻辑顺序。[S07] Native 字码 coverage、程序成功或直接从上到下排序均不能证明多栏／旁注与源层级正确。

Paddle PP-StructureV3 输出 `parsing_res_list`、`block_order` 等版面读序结果。[S10] 它们可作为已存在的候选结构，但条款编号／标题层级、跨页续接、旁注作用域仍需用来源具体核验，官方结果字段不是法律结构验收。

PDFium 整页渲染会画页面实际内容，避免把“嵌入 image 的 bbox”误当含独立文字、线条和 caption 的全部插图。[S06] 范围内插图优先保完整页回看，再保经完整性验证的 crop 和关联文字；图纸文件则在路由退出，不进此通道。

**不用新增 CAD／图纸模型／流程语义图数据库。** 首版仍遵守准确返回原图与相关文字的目标，不假定需要解释箭头或隐含工程关系。

## 5. Docling 与 PyMuPDF：真正补的缺口和代价

| 候选 | 官方可确认能力 | 相对现有体系真正可增加什么 | 代价／边界 | 本轮建议 |
| --- | --- | --- | --- | --- |
| Docling | PDF layout／reading order／table／formula、统一文档结构及本地执行；代码 MIT，模型单独许可证 [S11]；Core 含 `checkbox_selected`／`checkbox_unselected`／`form`／`empty_value` 等标签 [S12] | 可作为独立正文布局／表单结果对照，并提供较丰富的类型表达 | 类型标签存在不证明指定 parser／模型已可靠产生这些状态；标准 PDF 模型路线还含 native docling-parse、PDFium、Torch、docling-ibm-models、accelerate、HF 等 [S13][S14]；需新的完整锁、模型缓存和扫描 | 未采用；没有来源收益证据前不纳入首版完整worker，若当前路线余下确有模型缺陷，先独立对照 |
| PyMuPDF | 原生 widgets、文字、图像／渲染和 PDF 操作，MuPDF C 引擎；无强制额外 Python 依赖；AGPL／商业授权 [S15][S16] | 整合较多 PDF API、作为不同原生引擎观察对照，可能简化 adapter | 已有 PDFBox／pypdf／pdfplumber／PDFium 覆盖主要底层能力；仍不能自动给出正确空栏／跨页注释关系；MuPDF 原生二进制和许可纳入交付核查 | 不为本轮缺陷额外安装；若已有被模型环境使用，保留实际依赖，不误删；技术收益与许可适用需分别确认 |
| 新视觉／表单模型 | 特定控件识别模型可能补视觉状态；未在本轮加载来源测试 | 解决正常输入都已进入后残留的符号状态误识别 | 新权重／数据／训练／GPU峰值／模型许可证；不宜以通用榜单替代本项目 source review | 有明确来源失败再选轻量子模型，优先复用现有框架 |
| 新公式OCR框架 | Paddle 本身已有公式模块，官方输出 LaTeX／MathML [S17] | PDF图像公式可使用现有家族的专用模型；Word OMML 可先直接保结构 | 公式树仍须核分式、上下标、符号和定义，不把叶文字当公式正确；启用模型涉及权重制品但不必新框架 | 后置于前两类优先项；不重复新增公式框架 |

Docling官方发布metadata核到 `2.138.0`，主包为 `docling-slim[standard]==2.138.0`。[S14] 该信息是本次官方能力／依赖调查，不是推荐将 mutable `main` 或当前最新包锁进项目。本轮没有 resolver、安装或来源推理，完整依赖数量／镜像大小／峰值未测。

## 6. 七类与技术增量对应

| 用户类别 | 当前应优先补什么 | 是否已证明需要新组件家族 |
| --- | --- | --- |
| 表单 | 全输入／空栏覆盖；原生字段、视觉控件、选择分支的独立状态与所属标签 | 需要专用代码分支；暂未证明第三OCR框架必要，可能需要现有框架的新控件子模型 |
| 复杂正文 | Native字符与模型版面组合，读序／层级／注释／跨页结构及独立源核 | 暂无；Docling只作未采用对照候选 |
| 图纸文件 | 身份路由后排除 | 不需要，且不新增解析 |
| 范围内插图／流程示意 | 完整原图、caption／legend／notes原文与所属位置保全，不精读工程语义 | 已有渲染与布局组件；不能只抽 embedded image |
| 有框复杂表格 | 合并／嵌套／续表的源逻辑结构与完整组 | 当前已有表格家族；不能把换引擎当结构正确 |
| 数学公式 | PDF公式专用通道，DOCX OMML结构，分式／上下标／定义关系 | 先复用现有 Paddle／MinerU 家族，新增模型制品需实际验证 |
| 扫描与隐藏OCR | 文字层可靠性观察＋可见页OCR；不要见text非空就停止视觉解析 | 已有家族；可能配方／模型调整，不必新增OCR框架 |
| Word特殊内容 | OOXML字段／OMML／drawing／修订story与可见态，双轨验证 | 既有 POI／XML与LibreOffice；不新增Word转换供应商 |

本表只判断解析技术增量，不评价 embedding／召回／rerank、法律采用、LLM最终入包或引用UI。所有候选仍是研究建议；当前 frozen05 与 Drafting 保持。

## 7. 官方来源与本轮收据

官方网页若受403限制，使用同一维护者的官方源码／文档仓库；未把抓取到的README指导语作为运行指令。来源下载均小型文档／源码／metadata，不下载模型或执行远程代码。

收据：[第一批URL／SHA／片段](../../.local/state/parse-stack-primary-sources-20261012/official-source-receipt.json)、[第二批URL／SHA／片段](../../.local/state/parse-stack-primary-sources-20261012/official-source-receipt-02.json)。Mutable主分支文件由当次SHA固定为研究证据，不当交付版本锁。

- [S01 pypdf 6.19.0 forms官方文档](https://github.com/py-pdf/pypdf/blob/6.19.0/docs/user/forms.md)：原生fields／widgets、V／DV、层级、XFA。
- [S02 PDFBox 2.0.31 PDCheckBox源码](https://github.com/apache/pdfbox/blob/2.0.31/pdfbox/src/main/java/org/apache/pdfbox/pdmodel/interactive/form/PDCheckBox.java)：64–67、89–120行。
- [S03 PDFBox 2.0.31 PDRadioButton源码](https://github.com/apache/pdfbox/blob/2.0.31/pdfbox/src/main/java/org/apache/pdfbox/pdmodel/interactive/form/PDRadioButton.java)：84–145行。
- [S04 PDFBox 2.0.31 PDAcroForm源码](https://github.com/apache/pdfbox/blob/2.0.31/pdfbox/src/main/java/org/apache/pdfbox/pdmodel/interactive/form/PDAcroForm.java)：动态XFA限制及hasXFA／getXFA。
- [S05 pypdfium2 document helper](https://github.com/pypdfium2-team/pypdfium2/blob/main/src/pypdfium2/_helpers/document.py)：init_forms、XFA构建检查。
- [S06 pypdfium2 page helper](https://github.com/pypdfium2-team/pypdfium2/blob/main/src/pypdfium2/_helpers/page.py)：render crop／rotation、may_draw_forms、draw_annots。
- [S07 pdfplumber 官方README](https://github.com/jsvine/pdfplumber/blob/stable/README.md)：对象／矩阵、text flow、form、image重建与OCR边界。
- [S08 OpenCV官方轮廓教程](https://github.com/opencv/opencv/blob/4.x/doc/py_tutorials/py_imgproc/py_contours/py_contours_begin/py_contours_begin.markdown)。
- [S09 OpenCV官方形态学教程](https://github.com/opencv/opencv/blob/4.x/doc/py_tutorials/py_imgproc/py_morphological_ops/py_morphological_ops.markdown)。
- [S10 Paddle PP-StructureV3 官方文档](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/pipeline_usage/PP-StructureV3.en.md)：独立子模块、布局类型、parsing_res_list／block_order。
- [S11 Docling 官方README](https://github.com/docling-project/docling/blob/main/README.md)：PDF能力、本地运行、MIT／独立模型许可证。
- [S12 Docling Core 类型源码](https://github.com/docling-project/docling-core/blob/main/docling_core/types/doc/labels.py)：DocItemLabel checkbox／form／empty_value。
- [S13 Docling 官方pyproject](https://github.com/docling-project/docling/blob/main/pyproject.toml)：PDF、OCR、models-local等extras；没有在本次解析版本resolve完整lock。
- [S14 Docling 官方发布metadata](https://pypi.org/pypi/docling/json)：当次版本与standard主包关系。
- [S15 PyMuPDF 官方Widget文档](https://github.com/pymupdf/PyMuPDF/blob/main/docs/widget.rst)：原生form widgets与button states。
- [S16 PyMuPDF 官方README](https://github.com/pymupdf/PyMuPDF/blob/main/README.md)：MuPDF、API、许可；安装能力不作为本项目验收。
- [S17 Paddle官方公式模块](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/module_usage/formula_recognition.en.md)：专用公式模型及LaTeX／MathML。
