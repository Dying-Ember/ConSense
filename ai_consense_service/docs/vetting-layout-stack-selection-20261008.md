# Vetting 扫描文档布局与表格解析选型

核实日期：2026-10-08（Asia/Shanghai）。状态：**文献核实，0 模型实测**。本轮仅阅读官方发布、源码、模型卡、论文与产品文档，没有安装依赖、下载模型、启动推理或修改服务配置。官方能力、已接入能力、本项目效果三者分别记录。

**当前工作边界（2026-10-08 用户最新指示）：只讨论 OCR、embedding、rerank，允许改文档，禁止改代码。** 本报告中的模型、参数、组件契约和实验顺序是讨论内容；不因此启动安装、配置修改、下载、模型实验或接入。完整边界与三阶段质量讨论提纲见 [当前讨论范围](./vetting-competition-corpus-and-ocr-adaptation-20261008.md)。其他模块暂不展开。

用户已采纳全面双解析和经验证的区域/完整表格组级路由择优，两套原始输出与分歧都保留；Codex 负责参考标注与原件复核。这决定了后续对照和组合方式，不预设某候选更优，也不表示该路线已实测。已定取舍见 [设计访谈](./vetting-ocr-embedding-rerank-grill-20261008.md) 与 [架构决策](./adr/0002-preserve-dual-parser-evidence-and-route-by-structure.md)。

## 判断边界

任务侧优先级已按用户进一步说明收敛为固定资料精修冻结与样板驱动的通用解析适配；本报告的模型小样选型服务于这两个交付。完整执行与验证边界见 [固定资料与样板适配方案](./vetting-competition-corpus-and-ocr-adaptation-20261008.md)。

Paddle 有布局、阅读顺序与完整文档解析能力；不能从本项目目前的纯 OCR 输出推断 Paddle 布局不可用。真正需要比较的是 **PaddleOCR-VL-1.6 完整解析产线** 与 **MinerU 4 Standard**。PP-StructureV3 作为传统模块化参照，不增加第三个同级主选方案。布局检测得到一个 `table` 区域，并不等于已经得到单元格、跨行跨列、表头关系或合同字段归属。

**实验执行顺序：先在 A4000 16GB 独立节点验证 PaddleOCR-VL-1.6 完整 pipeline + PP-DocLayoutV3，再以同源页执行 MinerU 4.0.10 Standard 对照。** 该顺序服务于补齐当前缺失的布局／读序／表格产线，不是合同质量胜负结论。两套均需保存完整输出与分歧；规范输出采用用户已确认的区域／完整表格组级择优策略，具体哪套在哪类更优由同一参考标注和文件级评价支持。某一路无法稳定运行时记录该路线失败，不将只跑通另一套称为双解析通过。当前 RapidOCR 保留为基线；原生 DOCX 保持 POI。

本项目当前状态由同轮仓库审计提供：正式 OCR 是 RapidOCR 3.9.2，PP-OCRv6 small det/rec 与 mobile cls；Java 接口仅提供 text/confidence/lines bbox。扫描几何表格候选未接入生产。因此目前缺少的是完整 parser 与结构证据契约。原生 DOCX 继续使用现有 POI 结构解析，保留编号、删除／划线、符号与单元格来源；本轮不改写 Drafting。已有 DOCX 保真记录见 [vetting-docx-fidelity-20261003.md](./vetting-docx-fidelity-20261003.md)。MinerU 4 支持 Office 输入，不构成其已经保留本项目这些法律语义的证明。

资源边界采用用户本轮确认：开发主机 Windows、RTX 4060 8GB；另一台 Win11 主机有 A4000 16GB，可运行容器，作为独立解析 HTTP 节点；比赛节点为单 H800 80GB、27 vCPU、250GB RAM。8GB 与 16GB 不相加。A4000 容器 GPU 透传、峰值显存、吞吐和并发均未验证；OS 本身不作为主选方案的决胜因素。

用户进一步确认本地验证优先质量、可忽略速度差异。第一轮将耗时作为日志指标，不作为主候选淘汰条件；在运行时支持、同一模型和精度、同一图像与输出长度配置下，GPU 计算速度主要影响完成时间。容量检查覆盖完整推理峰值，而非仅模型权重。先通过降低并发／batch、串行阶段、必要时卸载来控制内存；任何降分辨率、量化、输出截短或解析模块关闭都作为单独实验记录，不能混入质量基准。全流程实际稳定性和合同解析质量仍需验证。

## 两个主候选与传统参照

| 项目 | PaddleOCR-VL-1.6 完整解析 | MinerU 4 Standard | PP-StructureV3 参照 |
| --- | --- | --- | --- |
| 布局与读序 | PP-DocLayoutV3；单次 forward 预测多点区域与逻辑读序 | 小模型栈包含 PP-DocLayoutV2 与其读序后处理；Standard 结合小模型与 VLM | 默认 PP-DocLayout_plus-L，加独立 PP-DocBlockLayout 等模块 |
| 识别阶段 | VL-1.6-0.9B，识别文字、表格、公式、图表、印章等 | MinerU2.5-Pro-2605-1.2B；小模型承担 OCR／公式／表格等职责 | PP-OCRv5 与 table_recognition_v2 等专用模块 |
| 主要结构输出 | 逐页 parsing_res_list、块标签／顺序／内容／bbox，原 JSON | ModelJson、MiddleJson、Structured Content、Content List V1/V2、资源包 | table_res_list、cell_box_list、pred_html、table OCR 与置信度等 |
| 主要验证问题 | 完整产线能否在合同样本上保持块归属、表头和跨页来源 | 自动后处理与渲染能否保留审核需要的原块和页号；Standard 的具体质量 | 可作为专用单元格结构与轻量传统方案的对照 |
| 本项目实测 | 0 | 0 | 0 |

上述职责来自 [PP-DocLayoutV3 官方模型卡](https://huggingface.co/PaddlePaddle/PP-DocLayoutV3)、[VL-1.6 论文 §2](https://arxiv.org/html/2606.03264v1)、[MinerU 4.0.10 模型 registry](https://github.com/opendatalab/MinerU/blob/c221cc41bc911ad0df3eaeda97acf6a1bfe9bf93/mineru/model/registry.py)、[PP-StructureV3 release/3.7 配置](https://github.com/PaddlePaddle/PaddleX/blob/release/3.7/paddlex/configs/pipelines/PP-StructureV3.yaml) 与 [PP-StructureV3 输出教程](https://www.paddleocr.ai/latest/version3.x/pipeline_usage/PP-StructureV3.html)。VL-1.6 沿用 VL-1.5 的 V3 布局模块，主要升级第二阶段识别器；PP-StructureV3 的名字包含 V3，但其默认布局不是 PP-DocLayoutV3。

## MinerU 4 版本与档位

GitHub 4.0.0 发布于 2026-09-16；核实时 GitHub 最新稳定发布与 PyPI 当前稳定包均为 **4.0.10**，发布时间为 2026-09-29，早于本报告日期。4.0.10 源码固定为 `c221cc41bc911ad0df3eaeda97acf6a1bfe9bf93`。后续实验应固定补丁版本和依赖锁，不能只记录“MinerU 4”。[4.0.0 发布](https://github.com/opendatalab/MinerU/releases/tag/mineru-4.0.0-released)、[4.0.10 发布](https://github.com/opendatalab/MinerU/releases/tag/mineru-4.0.10-released)、[PyPI](https://pypi.org/project/mineru/)。

4.0 将原先用户层面的 `pipeline/vlm/hybrid` 选择改为四个质量档位，模型 backend 与 VLM engine 独立配置。内部仍可出现 `medium/high/xhigh` effort，不能继续复制 3.x 命令作为 4.x 配置。[4.0 迁移说明](https://opendatalab.github.io/MinerU/reference/migration_4/)。

| 档位 | 官方定位 | 本次选型中的用途 |
| --- | --- | --- |
| Flash | 原生文本／电子文档快速解析与索引；实时 tiers 文档还区分扫描／图片 Flash OCR | 原生文本预览；不承担复杂扫描表格审核主能力 |
| Basic | OCR、公式、表格小模型，无需本地 VLM，可 CPU | 轻量消融／故障降级对照 |
| Standard | 小模型 + VLM，PDF 的默认本地解析质量 | 主候选；先测试此档 |
| Advanced | 与 Standard 使用同套部署模型，增加推理计算 | 仅对 Standard 已暴露的困难页做后续显式对照 |

PDF 与图片支持四档；Office／OpenDocument／RTF／EPUB／OFD／HTML／CSV 等本地使用 Flash。Advanced 没有单独模型下载档位。实时文档的 Flash OCR 描述比 4.0.0 发布概述更细，实验以固定 4.0.10 的实际路径与产物为准。[官方 Tiers and Runtimes](https://opendatalab.github.io/MinerU/usage/tiers/)。

## MinerU 模型、运行引擎与 Paddle 复用

4.0.10 registry 指定：小模型可选 `MinerU-4_models_onnx` 或 `MinerU-4_models_torch`；VLM 原始权重为 `opendatalab/MinerU2.5-Pro-2605-1.2B`。vLLM／LMDeploy 使用原始权重，llama.cpp 使用 registry 指定的 `jinzhenj/MinerU2.5-Pro-2605-1.2B-GGUF` 中 Q8_0 主模型和 mmproj。这是软件版本 4 与 VLM 型号 2.5-Pro 的不同命名层级。[固定版本 registry](https://github.com/opendatalab/MinerU/blob/c221cc41bc911ad0df3eaeda97acf6a1bfe9bf93/mineru/model/registry.py)。

ONNX 官方模型卡列出 PP-DocLayoutV2、PP-OCRv6 Tiny Det + Small Rec、PP-OCRv4 seal detector、PP-FormulaNet plus-M。表格文件与 Torch bundle 相同；其 manifest 记录上游来源、revision、SHA-256、导出签名与验证。固定源码加载有线表格 UNet、无线表格 SLANet-plus 和 PP-LCNet 表格分类器。Paddle 与 MinerU 有明确组件复用关系，不能将二者写成“Paddle 没有布局，所以改用完全不同技术”。[ONNX bundle 模型卡](https://huggingface.co/opendatalab/MinerU-4_models_onnx)、[Torch bundle](https://huggingface.co/opendatalab/MinerU-4_models_torch)、[固定 runtime/hybrid.py](https://github.com/opendatalab/MinerU/blob/c221cc41bc911ad0df3eaeda97acf6a1bfe9bf93/mineru/model/runtime/hybrid.py)。

PP-DocLayoutV2 后处理会解码读序、按 index 排序，并包含页眉／页脚／脚注等重分类规则。检测、读序、关系恢复与识别属于多段链路，保留模型阶段产物有助于判断问题发生在哪一段；其存在不证明本项目的复杂表格已恢复正确。[固定布局后处理源码](https://github.com/opendatalab/MinerU/blob/c221cc41bc911ad0df3eaeda97acf6a1bfe9bf93/mineru/model/layout/pp_doclayout_v2_base.py)。

基础包含 ONNX、llama.cpp、WebUI、SDK/API；`torch` extra 增加 Torch 运行栈；`full` 在 Linux 加 vLLM，在 Windows 加 LMDeploy。Python 要求 `>=3.10,<3.15`，各 engine 的 wheel、驱动条件仍须分别满足。小模型与 VLM 可以部署在不同运行方式下，独立节点也可以提供 VLM 或完整 parser API，但两种 endpoint 不可混用。[Extension Modules](https://opendatalab.github.io/MinerU/quick_start/extension_modules/)、[Model Source](https://opendatalab.github.io/MinerU/usage/model_source/)。

官方资源页只将 8GB GPU 作为规划起点，并明确 Standard／Advanced 的速度和内存取决于 engine、输入规模、并发与可用显存。未找到官方对 A4000 16GB 或本机 4060 8GB、我们合同样本、所选 engine 的完整解析峰值验证，因此“可安排实测”不能写成“已保证跑得动”。本轮认可 A4000 作为主实测节点，4060 作为布局／小批对照；比赛 H800 还要与 reviewer、embedding、reranker、KV cache 和 workspace 共享 80GB，解析资源应实测后纳入共同预算。[MinerU 资源边界](https://opendatalab.github.io/MinerU/usage/tiers/)。

Paddle 也有原生 Paddle／Transformers 与 vLLM／SGLang／FastDeploy 等路线；加速 server 不支持原生 Windows 的部分可使用官方 Docker 路径。Win11 A4000 容器 HTTP 节点在文档层面成立，GPU 透传与完整产线仍须测。VL-1.6 官方模型卡要求 PaddleOCR doc-parser `>=3.6.0`、Paddle `>=3.2.1`，明确只调用 element-level Transformers 示例不能代替完整页解析。[VL-1.6 模型卡](https://huggingface.co/PaddlePaddle/PaddleOCR-VL-1.6)、[PaddleOCR-VL 使用教程](https://www.paddleocr.ai/latest/version3.x/pipeline_usage/PaddleOCR-VL.html)。

## 离线准备与校验边界

MinerU 官方支持 Hugging Face／ModelScope／local。Standard 需要选定小模型 bundle 和选定 engine 对应 VLM；可在联网构建机显式指定目标 backend 下载，再按相同组合 verify，离线推理设 source=local。local 缺资源会失败而不是自动下载；显式 `models download` 本身仍会访问远程来源。Advanced 复用 Standard 部署。[Model Source 离线说明](https://opendatalab.github.io/MinerU/usage/model_source/)。

下列命令仅为官方工作流示意，本轮未执行：

```text
mineru-kit models download --tier standard --small-backend torch --vlm-engine vllm --source huggingface
mineru-kit models verify --tier standard --small-backend torch --vlm-engine vllm
mineru-kit models show
```

`verify` 不能当推理验收，也不能当每个本地权重已做 SHA-256 验证。4.0.10 下载源码检查远程匹配文件清单、路径是否存在和完成标记；registry 未固定所有远程模型 revision。部署时应另保存下载 revision、manifest 与每个模型／配置／字典的本地 SHA-256，固定容器 image digest 和依赖 lock；然后做完全断网启动与小样解析。这些是本项目建议的可复现要求，尚未执行。[固定 download.py](https://github.com/opendatalab/MinerU/blob/c221cc41bc911ad0df3eaeda97acf6a1bfe9bf93/mineru/model/download.py)。

Paddle 官方支持联网 pull/save、离线 load/run 的镜像流程；离线镜像与模型、完整 layout-parsing 服务要同时准备，不能只有 VLM recognition endpoint。首次验证应证明无外网情况下完整文件请求可完成，并记录准确模型版本、镜像 digest、显存与输出，不用“镜像已下载”代替这一证据。[PaddleOCR-VL 离线部署教程](https://www.paddleocr.ai/latest/version3.x/pipeline_usage/PaddleOCR-VL.html)。

## 页／块／表格输出与法律证据保留

MinerU 4 对分析和后处理结果采用不同对象：ModelJson 包含 analysis pages 与 page_index_map；MiddleJson 包含有顺序的 pages／semantic blocks，序列化带 `schema=docvortex.model/docvortex.middle` 与 `schema_version=2.0`。来源页 page_idx 从 0 开始，CLI 页选择从 1 开始。Structured Content 是消费视图，没有这两个 schema identity 字段。V1 API 可返回 markdown/middle_json/structured_content/zip，具体以服务 capabilities 为准；`mineru parse --json` 只是命令响应，不是 MiddleJson。[Output Formats and Result Contract](https://opendatalab.github.io/MinerU/reference/output_files/)。

Content List V2 按页分组，能表示 page_header/page_footer/page_number/page_aside_text/page_footnote；table item 包含 HTML、裁图资源、caption／footnote 与表格类别。bbox 是可选：存在时将 MiddleJson 的 0–1 坐标转为 0–1000 整数框。它是消费格式，不能保证每个原模型块／cell 的全部 provenance 留存。适配器必须自己记录来源 PDF、原页、块与 cell 的身份，不能将整数 bbox 误认作原图像像素。[固定 V2 renderer](https://github.com/opendatalab/MinerU/blob/c221cc41bc911ad0df3eaeda97acf6a1bfe9bf93/mineru/render/_internal/content_list/v2.py)、[bbox 转换源码](https://github.com/opendatalab/MinerU/blob/c221cc41bc911ad0df3eaeda97acf6a1bfe9bf93/mineru/render/_internal/content_list/common.py)。

重要区别：结构 JSON 比 Markdown 更丰富，但仍不是未处理的全部识别结果。MinerU 4.0.10 依赖 `docvortex>=0.5.4,<1`；核查最低版本 0.5.4 的处理代码显示，页内处理会做列表与视觉关系归组，跨页处理标注续段／续表；MiddleJson 转换还删除计算临时字段 lines/_lines/angle/score/label。分析阶段本身也归一化、过滤无效块。只保存最终 MiddleJson 就无法恢复所有中间判断。[固定依赖声明](https://github.com/opendatalab/MinerU/blob/c221cc41bc911ad0df3eaeda97acf6a1bfe9bf93/pyproject.toml)、[DocVortex 0.5.4 pages 后处理](https://github.com/myhloli/DocVortex/blob/6d6e1829f819135ae17974ce2dfc23b9ea2e0524/src/docvortex/postprocess/pages.py)、[MinerU analysis normalization 调用](https://github.com/opendatalab/MinerU/blob/c221cc41bc911ad0df3eaeda97acf6a1bfe9bf93/mineru/backend/analysis/pdf/pipeline.py)。

DocVortex 0.5.4 默认 Markdown 会跳过页眉／页脚／页码／旁注辅助块；page_footnote 不在该 skip 集合，不能笼统称全部脚注被删除。默认 render plan 将续段／续表合到前块，后块不单独展示；FULL 模式不跨页合并续表。该渲染计划以文档副本为基础，原逐页 MiddleJson 可分别保留，但合并后的 Markdown 位置不能当原页／cell 来源。[默认 Markdown renderer](https://github.com/myhloli/DocVortex/blob/6d6e1829f819135ae17974ce2dfc23b9ea2e0524/src/docvortex/render/_internal/markdown/renderer.py)、[render planner](https://github.com/myhloli/DocVortex/blob/6d6e1829f819135ae17974ce2dfc23b9ea2e0524/src/docvortex/render/_internal/common/planner.py)、[辅助块定义](https://github.com/myhloli/DocVortex/blob/6d6e1829f819135ae17974ce2dfc23b9ea2e0524/src/docvortex/schema.py)。

Structured Content 保留页与辅助文本／脚注以及可用 bbox，但转换删除 index 等内部字段，不能直接充当我方原 block 身份。MinerU 的额外 LLM 辅助 title_leveling 与 cross_page_table_cell_merge 默认 false；这些关闭，并不等于确定性跨页后处理与 Markdown 合并也全部关闭。应记录实际 resolved 配置，并用含跨页表头／续行／脚注的样本验证来源保留。[Structured Content renderer](https://github.com/myhloli/DocVortex/blob/6d6e1829f819135ae17974ce2dfc23b9ea2e0524/src/docvortex/render/_internal/structured_content/renderer.py)、[MinerU 配置默认值](https://github.com/opendatalab/MinerU/blob/c221cc41bc911ad0df3eaeda97acf6a1bfe9bf93/mineru/config.py)。

Paddle 同样有消费视图的丢失风险：VL-1.6 默认 markdown_ignore_labels 包含 number/footnote/header/footer/aside_text 等；完整 JSON 的 parsing_res_list 以读序排序，块有 block_bbox/block_label/block_content/block_id/block_order 和 input_path/page_index。HTTP prunedResult 会省去 input_path/page_index，适配器需用请求身份与原页映射补回。跨页 merge／标题 relevel 是单独后处理，HTTP restructurePages 默认 false，应先保存逐页结果再生成聚合视图。[VL-1.6 固定配置](https://github.com/PaddlePaddle/PaddleX/blob/release/3.7/paddlex/configs/pipelines/PaddleOCR-VL-1.6.yaml)、[VL 输出与 HTTP 契约](https://www.paddleocr.ai/latest/version3.x/pipeline_usage/PaddleOCR-VL.html)。

本项目统一证据包建议包括：原始文件 SHA-256；解析器／模型／配置／engine 版本；页图与坐标空间；ModelJson 或尽可能早的模型输出；逐页结构 JSON 与图片资产；我方 sourceId／原页／blockId／cellId 映射；后处理规则与聚合关系；错误／未知状态。Markdown、合并 HTML、检索文本均是派生阅读材料。`ParseResult.save` 可写 markdown、middle_json、structured_content、images，并在存在 raw model output 时写 model_output；普通结构 JSON 不包含全部图片字节，不能把一个 JSON 文件当完整证据包。[结果保存契约](https://opendatalab.github.io/MinerU/reference/output_files/)。

## Benchmark 只能辅助设定假设

VL-1.6 论文 §5.1 使用 OmniDocBench v1.6，含新的 MGAM 评价方式与困难页，不与 v1.5 分数直接横比。同一论文表内的 VL-1.6 与 MinerU2.5／Pro 对照属于该版本与该实验配置，未直接测试 MinerU 4 Standard，不能据此宣布本次两个完整 parser 的胜负。模型卡说明 VL-1.6 自身指标由作者评估，其余多数引用榜单；这不是我们对两套产线进行的独立合同实测。overall 也不能转译为合同字段准确率。[VL-1.6 论文](https://arxiv.org/html/2606.03264v1)、[模型卡评估说明](https://huggingface.co/PaddlePaddle/PaddleOCR-VL-1.6)。

PP-DocLayoutV3 对应 RT-DocLayout 论文的固定识别器实验，能够帮助区分布局收益与识别器升级，但其中 A100 batch32 的布局 FPS 不能当 A4000 的完整文档页吞吐。MinerU2.5-Pro 模型卡所报 A100 并发 VLM 速度也不包含本项目全链路验收。[RT-DocLayout 论文](https://arxiv.org/html/2606.23344v1)、[MinerU2.5-Pro 模型卡](https://huggingface.co/opendatalab/MinerU2.5-Pro-2605-1.2B)。本报告不制作跨 benchmark 排名。

## 下一轮的有界验证（尚未执行）

1. A4000 节点先测 GPU 透传与完整 parser HTTP 健康；固定 `MinerU==4.0.10/Standard` 和 `PaddleOCRVL(pipeline_version="v1.6")` 及各自依赖／权重；从单文档并发 1、单页、低区域 batch 开始，测峰值显存。Paddle release/3.7 配置的 pipeline batch_size=64、layout batch_size=8 不应直接成为 16GB 节点的初始实验值；低 batch 也只是测试起点，不是容量保证。4060 不与 A4000 的显存相加。[VL-1.6 配置](https://github.com/PaddlePaddle/PaddleX/blob/release/3.7/paddlex/configs/pipelines/PaddleOCR-VL-1.6.yaml)。
2. 第一轮限定约 12–20 页真实合同样本，含相邻跨页组；冻结同一份页面清单，覆盖干净扫描、倾斜／复印件、多栏页、合并单元格、复杂多级表头、跨页续行、页眉含条款标识、脚注、勾选、删除／划线、印章。先交付原页叠框、读序、表格与错误对照，再决定是否扩大样本。原 DOCX 保持 POI 路径；转出的 PDF 仅作为另一个明确标识的扫描测试来源。
3. Codex 对照原件完成原页读序、关键块遗漏、跨行跨列、表头→值归属、续表／续行关系、页眉脚注遗漏和来源定位的参考标注与复核。把 OCR 字符错误、布局／读序错误、表格结构错误、字段归属错误分别统计；不要用 OCR 平均 confidence 代替正确率。
4. 两个主候选均保存早期模型输出、逐页块／bbox／表格 HTML／可用 cell 框和原页图；检查 HTTP 页映射与合并视图能否追溯。PP-StructureV3 只选少量表格做传统参照，不扩展选型范围。
5. 再测冷启动、长文档、单页 p50/p95、失败页、峰值 VRAM/RAM 与离线重启；耗时仅记录，第一轮以质量与能否完整运行决定取舍。出现 OOM 时先调 batch／VLM 并发或串行阶段，保持图像信息量、输出完整性和识别模块一致；需要修改这些质量相关参数时另开对照并记录所有差异。解析后做相同 chunk／检索输入，由 Codex 对照原件复核关键条款和表格值是否进入正确证据片段。
6. 根据同一冻结样本和文件级评价，验证区域／完整表格组级路由；保留两套原始输出、分歧、原件和采用理由，明确失败与未知。表格以完整结构组选择，不逐格拼接。服务启动、schema 合法或测试通过不计作质量验收。固定的 NTT／SCT／SCC 模板与 GCT／GCC／SL 可在验证后预解析、切块、嵌入并打包，记录源文件与解析／切块／嵌入配置哈希；比赛新增文件沿用经验证的版本与契约。H800 的优先部署方案是先解析新增文件与建索引、完成后卸载解析进程再加载大 reviewer，实际预算允许时才考虑常驻。

后续实现参考范围为扫描 PDF／图片 parser 及结构证据保存；当前阶段仅更新文档，页面／块／表格的展示和其他模块另行讨论。完整 parser API 才是解析接入目标，单 VLM recognition 服务只是其中一个子阶段。Paddle 与 MinerU 两套都执行同源对照并保存结果，再验证结构组级择优；原生 DOCX 保持原生结构路径。当前结论是“设计方向已确认、尚待实测”，没有宣布已经切换、已经跑通或已经改善业务准确率。
