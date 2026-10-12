# 复杂正文与规范内插图／流程示意：解析技术栈增量评估

日期：2026-10-12，Asia/Shanghai。研究范围为用户优先级中的复杂正文：多栏读序、源条款层级、旁注、跨页连接，以及范围内规范插图／流程示意的完整保全。先读当前[范围规则](./oer-scope-and-rules.md)和[handoff](./HANDOFF-ocr-embedding-rerank-20261008.md)，再读[源解析质量审计](../../.local/state/oer-beyond-borderless-review-20261012/source-quality.md)及其指向的小型真实收据。只评估原件解析，不把RAG、法律采用判断或LLM排包作为解析修复。

**用户本轮最新排除：图纸文件完全不在解析之列，知道它是图纸即可。** 图纸文件、PDF／扫描图纸只做文件身份／路由识别后排除，不精读、不做图纸OCR／矢量／图例关系／尺寸／CAD研究。规范文档内已有插图与流程示意只做准确完整保全，不借此扩大为图纸解析。APU下文实例是参考图纸清单正文的公共图号与标题，不要求打开、解析它所引用的图纸。最新指示覆盖此前优先级清单中“图纸／流程图”含混范围。

本轮未安装、启动解析／模型、修改代码／服务、重评分或切换05开发基准。以下是研究建议，尚未实施／采用／源质量验收。Drafting冻结保持；首版Q10=A仍为准确完整原图／表组和相关文字展示，限范围内规范材料，不扩大为箭头语义推理或图纸工程关系推断。

## 1. 判断：需要补解析引擎，不需要据这些记录再增加大框架或模型家族

现有Paddle／MinerU、PDFBox／PDFium原生观察及整页RGB已经具备产生文字、位置、候选读序、布局类别和图片资产的能力。实际主要缺口发生在：区域进入识别前的覆盖、混合表格／正文的读序、原件层级与旁注关系、图像范围与外部说明的组装，以及有意义源内容在导出时被过滤。

建议在现有解析代码中增加统一的**源布局与完整成员组装层**，由三个可分别验证的部分组成：完整页与任务覆盖；正文读序／条款与续接结构；图像主体及caption／legend／notes完整组。这些部分共享来源版本、原页、坐标与组件身份，不需要图数据库或新的RAG框架。

对复杂正文及规范内插图保全，目前没有充分证据要求引入LayoutLM、Donut、Docling或单独的流程图理解模型。CAD及图纸文件解析被用户明确排除，不列作候选任务。若修输入／组装后仍在范围内完整原图内稳定出现模型检测／读序错误，再做当前布局模型的有界调整或监督训练；记录中的crop外缺失与导出过滤不能靠识别权重训练补回来。现有路线不能因此被称为已经足够准确，仍须真实源验收。

## 2. 目前记录中有证据的主要缺口

| 实例与保存来源域 | 已确认现象 | 对技术栈的归因 |
|---|---|---|
| APU正常完整20页、179识别任务 | 原页3公共图号prefix在crop上边界之外；原页6owner标题及prefix也未进入正常请求／raw／middle／FULL | 完整输入不等于所有区域都进了识别任务；先加覆盖与标题／公共prefix组路由，不能称仅OCR认错 |
| APU原页10正常输出 | 原件标题顺序8、9、10、11，输出为9、8、10、11；Hoarding表先于所属11)标题 | 表格与文字混合读序／parent组装；标题组件存在不代表完整关系正确 |
| AoA正常完整4页的OR | raw／middle／structured保留3个OR，默认Markdown只有2个；跨页OR被标为page_number过滤 | 表示／导出损失；应保source-significant字样，不按布局标签一刀切删除 |
| SL正常完整50页、独核29case／25原页／126窗口 | 额外21资产旧provider可辨主体20/21、完整主体crop4/21、完整图文关联组1/21；三个分母不能混成总体OCR分 | 主体与外caption／legend／notes断组是共享问题；图像主体能辨认不能证明完整返回 |
| 同一SL范围的新focused窗口 | 额外资产1/21、COM5七图3/7、EXT17公式及六释义1/1；仅评单窗口源像素完整范围 | 盲扩bbox未普遍恢复完整组，不能将窗口完整度当语义／owner正确 |
| 同一SL新relation sidecar | 2082保全检查0错，29/29原页指针保持，旧7/7正控关系仍表达；新增完整源关系组0 | metadata／关系存储不是自动发现关系；仍缺通用组装和源验证 |
| SL151worksection／2836原页native对照 | 两通道2726页同字码顺序、110页同字码集合但顺序不同；mixed-lane风险550页，formula／raised269页，encoding156页，风险可重叠 | 这些是审查线索，不是110／550页都错；原生字码保全不能证multi-column／可见原图读序通过 |

逐项证据：

- [APU/APL major-defects-and-attribution](../../.local/state/oer-apu-apl-normal-complete-parser-20261011/score-01/major-defects-and-attribution.json)，`/items/0`–`/items/2`。
- [AoA residual-attribution](../../.local/state/oer-aoa-complete-baseline-source-score-20261011/review-score-01/residual-attribution.json)，`/defaultMarkdown`。
- [SL源范围compact](../../.local/state/oer-coverage-baseline-report-20261011/update-45-sl-extent-preparation/sl-extent29-independent-source-compact.json)，`/closedPayload/metrics`、`/closedPayload/sourceReviewCoverage`。focused范围与旧provider组分数分开保留。
- [SL关系独审compact](../../.local/state/oer-sl-provider-relation-independent-source-20261012/source-review-01/compact-report-fragment.json)，`/oldSourceScoresUnchangedNotRescored`、`/sourcePositivePriorRelationsStillExpressed`、`/newCompleteSourceRelationGroupsProved`。
- [SL native完整复算](../../.local/state/oer-sl-body-source-native-diagnostic-independent-static-20261012/closed-actual01-independent-audit-01/independent-closed-actual-audit.json)，`/denominator`、`/independentlyRecomputedCategories`、`/independentlyRecomputedRisks`。

上述是各自保存的正常来源与评分域，不能全部追认为05采用输出的缺陷。SL2836页对照也不是当前固定required3250页／SL3174页开发基准的完整视觉验收。

已有正确项必须保留：GCT有限原2页范围的11/11完整源条款组、16个parent与4个margin-note owner已保存正确；GCC根尾19/19结构行、2/2完整根尾范围、1/1跨页根尾范围与旧23/23单元、6/6跨页控制均保持。源上已存在正确文字／parent的GCC增量不能写成正文漏失被修复。证据分别为[GCT facts](../../.local/state/oer-coverage-baseline-report-20261011/gct-fresh-complete-quality-adapter-prepare-01/fresh-complete-quality-facts.json)及[GCC compact](../../.local/state/oer-coverage-baseline-report-20261011/update-57-gcc10-and-sl-source-preparation/gcc10-score-compact.json)。全库、更深重复编号、定义及源语义关系仍有unknown。

## 3. 现有能力与实际代码接缝

### 3.1 布局／OCR模型已经存在，先核输出怎么被使用

[prepare_oer_models.py](../tools/vetting_eval/prepare_oer_models.py:18)已有明确revision的PaddleOCR-VL1.6、PP-DocLayoutV3、MinerU2.5-Pro-2605-1.2B以及MinerU小模型库；这里证明制品准备清单，不冒称所有模型已在每一正常路线发挥该能力或已验收。

官方[PP-StructureV3说明](https://www.paddleocr.ai/main/en/version3.x/pipeline_usage/PP-StructureV3.html)包含多栏读序恢复、布局分析、图像／表格／公式及可选模块；官方[布局分析模块](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/module_usage/layout_analysis.en.md)说明bbox与类别进入读序网络，输出带位置、标签、候选顺序。公开功能证明能力家族已存在，不保证本项目多栏、旁注或换版读序正确。当前main文档V2说明与V3示例混合，锁定实现时应依精确制品／安装源码，不能照main名称升级。

[structured_parser_evidence.py](../tools/vetting_eval/structured_parser_evidence.py)保存provider正常产物／资产，但其文件开头明确不负责运行parser、取资产或分配合同事实。新组装不能把契约保全成功当来源关系质量。

### 3.2 原生字码与整页RGB要一起保留

[native_pdf_observation.py](../tools/vetting_eval/native_pdf_observation.py:138)保完整glyph literal／UTF16、字体、bbox、matrix、rendering mode及原capture order；`ORDERING=operator_capture_order_not_reading_order`，effective visibility仍为`unknown_not_raster_verified`。不可把内容流顺序直接当阅读顺序，也不可把operator-visible当最终原图可见。扫描隐藏层另路由，不以大量可抽文字直接采用native。

[selected_source_raster.py](../tools/vetting_eval/selected_source_raster.py:50)用PDFium实际渲染RGB，并保来源选页与frame验证。它可以提供完整原页fallback，但整页正确保存只证明有恢复手段，不证明目标图组／条款owner已恢复。

[native_pdf_canonical_projection.py](../tools/vetting_eval/native_pdf_canonical_projection.py:127)目前明确排除SL scope、tableGroups和custom assets；该独立prose通道不是所有SL／图形的通用成品入口。现有冻结来源使用的路线与本模块能力须分别报告。新增源组装应落到适用的正常产物，不能仅在审查sidecar放作者手工组，宣称自动parser通过。

官方[MinerU输出契约](https://opendatalab.github.io/MinerU/reference/output_files/)区分raw model、middle、structured、markdown与资产；`save(writer)`保实际图片、原页和rotation引用，而独立结构JSON可不含图片本体。这支持保留完整结果包并按实际引用取资产。它不会替我们证明组完整，不能只读Markdown或按自己构造文件名找图片。

## 4. 最小实现方向

### 4.1 完整页与识别区域覆盖

每页保完整来源观察，列出已路由区域、未归属文字／孤立符号／前缀／控件候选和实际crop边界。native页以保留glyph与图形观察核未覆盖区域；扫描页以同一可见原页与检测结果核遗漏，不把可靠性未验的隐藏层作为gold。孤立短号、图号公共prefix、边注、footer语义字样均独立保留；未覆盖区域进入补充任务或unknown，不编造值。

Paddle已有阈值、unclip及bbox merge参数可试，但必须基于实测任务边界，避免固定页号规则与全局盲扩框。存在两个候选owner时保歧义；crop覆盖验证与文字识别质量分开。native观察与RGB全页既存库足以实现首版，不为覆盖检验增加专用模型家族。

### 4.2 读序／条款层级／旁注／续页

保provider顺序与native顺序两个原始通道，另产出可回溯reading-order投影。优先检测column lanes和横跨栏标题，再按栏内位置／编号／结构约束排序；全页简单按y,x排序会交织双栏，不应作为默认重构。

条款树使用印刷编号、缩进／style、parent与边界；重复(i)/(a)必须在所属父域解析。续页保fragment而非把物理页文串无条件拼接。旁注、proviso、OR等按源明确引用、树范围和布局证据归属，不只挂最近paragraph；无足够证据的候选保unknown。以上只恢复原件关系，不作法律适用或项目事实判断。

这是通用代码结构层，可用现有JSON nodes/edges存储，不要求图数据库。已正确GCT/GCC有限组作为回归controls，新增深层／跨页／多栏source gold作为正常产物验收，防平均提升覆盖旧正确关系回退。

### 4.3 范围内规范插图／流程示意完整组

资产组同时保存范围内规范插图的完整主体、caption、legend、轴标、说明、编号与源局部／原页位置；这些成员只作为源图完整保全，不解释工程图含义。分离成员可以保持多个图片／文字对象，不要求盲目扩大成一个巨大crop。候选关系从原件显式图号、同一条款层级、原bbox与布局类别获得，不由PDF Form invocation、对象容器或最近距离单独推定语义owner。

目标asset的紧crop有疑问时，展示完整原页或更大已验证窗口；必要跨页成员一起返回，并明确哪些关系unknown。整页fallback是保全措施，不可因此称目标关联组通过。保留focused crop用于定位，不覆盖原页，也不丢外caption／legend。

范围内规范插图的相关文字若只存在raster，先由现有OCR覆盖并与完整原图保全分开评价；完整源输入内仍存在必要文字识别错误时，才评估DPI／分块／现有权重训练。图纸文件无论是否扫描，识别身份后就排除，不因可OCR而加入该分支。不把Q10=A扩成流程方向解释、工程图尺寸推理、CAD对象识别或多模态业务问答。

## 5. 今晚需要放进交付依赖范围的增量

对于本报告复杂正文及规范内插图保全：**暂不新增依赖家族。** 将已确定Paddle／MinerU布局与OCR制品、PDFBox、PDFium／Pillow、现有解析库及fonts的精确锁和原生依赖包含在交付。代码层需要覆盖、读序、源树与图文组装，当前已有库能承载。图纸文件仅身份路由并明确排除，不引入图纸专用依赖或训练。

Camelot／pdfplumber增量沿整体报告原生表格方向处理；它不能直接解决扫描图crop外文字或流程图关联。若需要OpenCV连通域／图形边界候选，应优先复用已入表格／OCR环境的OpenCV并明确锁，不能因为Camelot含该依赖便认为其他隔离worker也自动可导入。无需为这些观察先引入另一套文档框架。

只有在完整源输入、可回溯组装和旧正控保持之后，仍有跨文件稳定的模型bbox／类别／读序残差，才升级既有布局模型或做监督训练。到时把模型版本／新增推理依赖／Linux镜像分别列变更；不能由本轮研究预言训练收益或扫描通过。

## 6. 验收最小分母

逐类保留：完整页与必要region进入真实识别任务；有意义符号在正常structured与导出中保持；column／clause／旁注／续接关系源核；主图及每个外caption／legend／note实际成员完整；坐标与原页版本可回取；旧正确关键组不回退。组件存在、定位正确、已保存RGB和整页fallback分别评价，不合成一个总体准确率。

优先复用APU crop／读序、AoA OR与SL29有限实例证明具体缺口修复，同时加未用于调规则的同类原件。冻结05质量与各家族实验质量分域，source group人工确认、blind／換版与全库未核范围继续诚实记录。全量解析与监督训练本轮均未进行。
