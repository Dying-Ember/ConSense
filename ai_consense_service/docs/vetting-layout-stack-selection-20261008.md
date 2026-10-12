# Vetting 扫描文档布局与表格解析选型

文献核实日期：2026-10-08；状态入口更新：2026-10-09（Asia/Shanghai）。本文的 0 模型实测及未安装状态是当日文献调查记录。用户后来已授权 OER 实现与本地部署，真实模型实验已开始；当前阶段与未完成验收见 [handoff](./HANDOFF-ocr-embedding-rerank-20261008.md)。官方能力、实际接入和本项目效果继续分别记录。

**当前规则入口是 [OER 已确认范围与执行规则](./oer-scope-and-rules.md)。** 比赛排除计价文件、非 PRE 项目工程技术 PSS 与 SL 非技术章节；完整模型、固定资料与新增文件产线按范围实施。本文技术选型不自动授予修改其他模块业务规则的权限；已确认的取舍和用户后续实施授权无需重复询问。

用户已采纳全面双解析和经验证的区域/完整表格组级路由择优，两套原始输出与分歧都保留；Codex 负责参考标注与原件复核。这决定了后续对照和组合方式，不预设某候选更优，也不表示该路线已实测。已定取舍见 [设计访谈](./vetting-ocr-embedding-rerank-grill-20261008.md) 与 [架构决策](./adr/0002-preserve-dual-parser-evidence-and-route-by-structure.md)。

## 判断边界

任务侧优先级已按用户进一步说明收敛为固定资料精修冻结与样板驱动的通用解析适配；本报告的模型小样选型服务于这两个交付。完整执行与验证边界见 [固定资料与样板适配方案](./vetting-competition-corpus-and-ocr-adaptation-20261008.md)。

Paddle 有布局、阅读顺序与完整文档解析能力；不能从本项目目前的纯 OCR 输出推断 Paddle 布局不可用。真正需要比较的是 **PaddleOCR-VL-1.6 完整解析产线** 与 **MinerU 4 Standard**。PP-StructureV3 作为传统模块化参照，不增加第三个同级主选方案。布局检测得到一个 `table` 区域，并不等于已经得到单元格、跨行跨列、表头关系或合同字段归属。

**实验执行顺序：先在 A4000 16GB 独立节点验证 PaddleOCR-VL-1.6 完整 pipeline + PP-DocLayoutV3，再以同源页执行 MinerU 4.0.10 Standard 对照。** 该顺序服务于补齐当前缺失的布局／读序／表格产线，不是合同质量胜负结论。两套均需保存完整输出与分歧；规范输出采用用户已确认的区域／完整表格组级择优策略，具体哪套在哪类更优由同一参考标注和文件级评价支持。某一路无法稳定运行时记录该路线失败，不将只跑通另一套称为双解析通过。当前 RapidOCR 保留为基线；原生 DOCX 保持 POI。

本项目当前状态由同轮仓库审计提供：正式 OCR 是 RapidOCR 3.9.2，PP-OCRv6 small det/rec 与 mobile cls；Java 接口仅提供 text/confidence/lines bbox。扫描几何表格候选未接入生产。因此目前缺少的是完整 parser 与结构证据契约。原生 DOCX 继续使用现有 POI 结构解析，保留编号、删除／划线、符号与单元格来源；本轮不改写 Drafting。已有 DOCX 保真记录见 [vetting-docx-fidelity-20261003.md](./vetting-docx-fidelity-20261003.md)。MinerU 4 支持 Office 输入，不构成其已经保留本项目这些法律语义的证明。

2026-10-08 的资源讨论条件为 Windows／RTX 4060 8GB，以及另一台 Win11／A4000 16GB 独立解析节点；两卡显存不相加。2026-10-09 当前工作机已是 A4000 16GB，使用独立 CPU FP32 环境验证并保持现有 GPU 业务。比赛节点为单 H800 80GB、27 vCPU、250GB RAM。GPU 透传、共驻峰值、吞吐和并发需分别实测，当前设备与运行证据见 handoff。

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

官方资源页只将 8GB GPU 作为规划起点，并明确 Standard／Advanced 的速度和内存取决于 engine、输入规模、并发与可用显存。2026-10-08 文献核查未找到针对 A4000 16GB 或历史 4060 8GB、我们合同样本、所选 engine 的完整解析峰值验证，因此当日“可安排实测”不能写成“已保证跑得动”。当前 A4000 主机的实际 CPU 实验与质量限制更新在 handoff；比赛 H800 还要与 reviewer、embedding、reranker、KV cache 和 workspace 共享 80GB，解析资源应实测后纳入共同预算。[MinerU 资源边界](https://opendatalab.github.io/MinerU/usage/tiers/)。

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

## 2026-10-08 拟定的有界验证顺序

1. A4000 节点先测 GPU 透传与完整 parser HTTP 健康；固定 `MinerU==4.0.10/Standard` 和 `PaddleOCRVL(pipeline_version="v1.6")` 及各自依赖／权重；从单文档并发 1、单页、低区域 batch 开始，测峰值显存。Paddle release/3.7 配置的 pipeline batch_size=64、layout batch_size=8 不应直接成为 16GB 节点的初始实验值；低 batch 也只是测试起点，不是容量保证。4060 不与 A4000 的显存相加。[VL-1.6 配置](https://github.com/PaddlePaddle/PaddleX/blob/release/3.7/paddlex/configs/pipelines/PaddleOCR-VL-1.6.yaml)。
2. 第一轮限定约 12–20 页真实合同样本，含相邻跨页组；冻结同一份页面清单，覆盖干净扫描、倾斜／复印件、多栏页、合并单元格、复杂多级表头、跨页续行、页眉含条款标识、脚注、勾选、删除／划线、印章。先交付原页叠框、读序、表格与错误对照，再决定是否扩大样本。原 DOCX 保持 POI 路径；转出的 PDF 仅作为另一个明确标识的扫描测试来源。
3. Codex 对照原件完成原页读序、关键块遗漏、跨行跨列、表头→值归属、续表／续行关系、页眉脚注遗漏和来源定位的参考标注与复核。把 OCR 字符错误、布局／读序错误、表格结构错误、字段归属错误分别统计；不要用 OCR 平均 confidence 代替正确率。
4. 两个主候选均保存早期模型输出、逐页块／bbox／表格 HTML／可用 cell 框和原页图；检查 HTTP 页映射与合并视图能否追溯。PP-StructureV3 只选少量表格做传统参照，不扩展选型范围。
5. 再测冷启动、长文档、单页 p50/p95、失败页、峰值 VRAM/RAM 与离线重启；耗时仅记录，第一轮以质量与能否完整运行决定取舍。出现 OOM 时先调 batch／VLM 并发或串行阶段，保持图像信息量、输出完整性和识别模块一致；需要修改这些质量相关参数时另开对照并记录所有差异。解析后做相同 chunk／检索输入，由 Codex 对照原件复核关键条款和表格值是否进入正确证据片段。
6. 根据同一冻结样本和文件级评价，验证区域／完整表格组级路由；保留两套原始输出、分歧、原件和采用理由，明确失败与未知。表格以完整结构组选择，不逐格拼接。服务启动、schema 合法或测试通过不计作质量验收。按 2026-10-09 Q9，固定来源分用途预解析、切块、嵌入并打包：NTT／SCT／SCC 标准模板仅供 Drafting；GCT／GCC／SL 按实际采用与技术范围供 Vetting／Advisory 共用。记录模块准入、源文件与解析／切块／嵌入配置哈希；比赛新增文件沿用经验证的版本与契约。Q10=A 首版检查文字关联到正确原图／表组及来源位置，图形关系自动理解不作为首版选型目标，表格结构要求继续保留。H800 的优先部署方案是先解析新增文件与建索引、完成后卸载解析进程再加载大 reviewer，实际预算允许时才考虑常驻。

2026-10-08 当日仅更新文档，结论为“设计方向已确认、尚待实测”。2026-10-09 已授权并开始扫描 PDF／图片完整 parser、结构证据保存及 OER 必要接入。完整 parser API 是解析接入目标，单 VLM recognition 服务只是其中一个子阶段。Paddle 与 MinerU 两套执行同源对照并保存结果，再验证结构组级择优；原生 DOCX 保持原生结构路径。实际运行、未解决问题和业务验收以 handoff 为准，运行成功与准确率改善分别提供证据。

## 2026-10-09 技术栈汇报与当前工程缺口

用户已认可整体方案。本节补充实际安装、运行和待验收状态，不覆盖上面的 2026-10-08 文献调查，也不将安装或契约测试写成业务效果。

| 层 | 当前选型与职责 | 当前状态 |
| --- | --- | --- |
| 主业务应用 | Java 8／Spring Boot 2.7.18 后端；Vue 3／TypeScript／Vite 前端 | 保留现有应用框架，OER 必要调用通过接口接入，不重写业务服务 |
| 原生文件 | POI 保留 DOCX 段落、编号、表格／cell 等原生结构；PDFBox 配合表单、显示内容与页图来源检查 | DOCX 排版表、表单和数据表分别识别；PDF 隐藏 OCR 层或仅普通文本抽取不能作为保真证明 |
| 扫描完整解析 | PaddleOCR-VL-1.6＋PP-DocLayoutV3；MinerU 4.0.10 Standard＋MinerU2.5-Pro-2605-1.2B | 两套保留为主候选，复用部分 Paddle 组件，不能将一致输出视为独立正确性证明；RapidOCR 为基线 |
| 召回与重排 | BGE-M3 dense＋BM25 融合，bge-reranker-v2-m3；长结构／短窗口双粒度 | 实现与 tokenizer／契约已检查；新 OER 真实 encode、建索引、召回及同池排序质量仍未执行。未接入全部 sparse／ColBERT 模式 |
| 模型服务与存储 | 三套 Python 3.12 环境分别隔离 retrieval／Paddle／MinerU；FastAPI／Uvicorn；Qdrant local 持久化、单 worker | retrieval 服务消费 canonical chunks，不直接执行完整 OCR；MinerU raw HTTP endpoint 仅为 VLM 识别子阶段，完整文档服务接入尚未完成 |

真实实验目前只覆盖 AoA 原页 1 和 APB 原页 3。MinerU CPU FP32 两页阶段耗时分别为 137.646／340.021 秒，见根目录 `.local/state/oer-implementation-20261009/parser-runs/mineru-standard-http-fp32-cpu-attempt2/receipt.json`。Paddle APB CPU FP32 页阶段耗时为 881.967 秒，见 `.local/state/oer-implementation-20261009/parser-runs/paddle-native-fp32-cpu-offline-apb/receipt.json`。这些是各 run 收据的阶段耗时，不是吞吐 benchmark；样本量、实现配方与 CPU 条件不足以宣布质量胜负或外推 H800 性能。

MinerU APB 原始布局漏 GENERAL NOTES 的 GN-01／GN-02 及左下合同标识；Paddle APB 保留这些内容，但原件的 `COMPLTION` 被识成 `COMPLIION`。原件拼词与模型改写分别保存，不能将纠正后的文字当原文。Paddle AoA 识别后 crop 导出失败；首次绘图还触发字体惰性下载，后续 APB 固定字体后外网尝试为零，仍不能据此宣布完整离线冷启动通过。质量证据见 `.local/state/oer-implementation-20261009/mineru-real-pages-quality-review.json`、`dual-parser-evidence-projection2/verification-receipt.json` 及各 run 的原始 artifacts。

当前 GPU 迁移未完成：`oer_server.py` 和 `scripts/oer-local.ps1` 明确约束 CPU／FP32；`mineru_raw_vlm_server.py` 使用 CPU FP32／eager；retrieval 的 Torch 为 CPU wheel，MinerU 小模型当前为 ONNX CPU。不能仅改一个 device 环境变量就写成已迁移。GPU 配方须分别锁定 CUDA、框架／engine、目标 checkpoint、精度和完整解析模块，并验证同源输出、冷启动、峰值显存与失败状态。CPU FP32 保留作基准；GPU FP32（所选引擎支持时）、FP16／BF16 的不同配置分别记录。

用户要求 Bonsai 临时抑制、代码／权重保留、其他服务不动，已执行。2026-10-09 20:26:56 +08:00 只读复查 `nvidia-smi` 返回 A4000 总计 16,376 MiB、已用 3,043 MiB、空闲 13,127 MiB；本地 Model.Enabled=false，准确路径的 Bonsai 进程及 8091 监听均为零。停止和其他服务保全证据见 `.local/state/gpu-bonsai-unload-20261009/unload-receipt.json` 与 `post-check-v2.json`。空闲量为瞬时快照，不能证明双 parser／BGE 共驻容量；本次汇报未启动新 GPU 模型或切换在线 OER profile。

输出保真继续作为接入要求：保存早期模型输出、逐页结构、原页与 assets、block／cell／表组来源关系，再生成 Markdown 或合并视图。MinerU 实装 DocVortex 0.5.13 满足依赖，但上面对最低版本 0.5.4 的源码分析不能直接代替实装版本的续表、辅助块和来源映射验收。raw adapter 失败请求的终态记录、完整离线启动及新依赖身份复验仍是待完成事项。

模型调整按错误阶段归因：先校准 clause 层级、切块、布局／读序、表组、推理和检索参数，再针对残留系统问题进行兼容性核实后的训练。漏检区域不等同于识别拼词，识别 VLM 微调不能自动修复布局未提供的内容。Paddle 原版 0.9B ERNIEKit 教程和 MinerU 旧 2509 模型 MS-SWIFT 教程没有证明对当前 VL-1.6／Pro checkpoint 的完整训练、导出与重载兼容；不能照搬教程后承诺训练已可用。短 512／overlap64、结构 embedding／rerank 各 2048 是独立初始实验预算，与 LLM 上下文及生成输出长度不同，最终配方由真实召回与排序对照确定。

后续实施顺序为：独立 GPU 环境与单路峰值验证 → 同源完整页／跨页组标注和双解析保真 → 真实 BGE 编码／必要证据召回／同池结构重排 → 扩大范围内资料覆盖及应用验收。比赛 H800 优先安排解析／建索引与大 reviewer 分阶段使用显存；是否常驻由完整共驻预算实测决定。本机 GPU 迁移和比赛 Linux 配方分别验证，不把其中一套成功直接当另一套通过。

## 原生 DOCX 路线的技术判断

2026-10-09 用户进一步说明考题与手头 DOCX 差别很小，并询问 POI 是否足够。技术判断：POI／OOXML 足以作为这批同类原生 DOCX 的主体解析基础，继续该路线；完整性验收仍针对当前实现与实际文件进行。该判断不等于当前 `DocumentParser` 已完整保留所有合约结构，也不把原生文件格式视为质量通过。

依据是 [2b DOCX 逐件调查](./oer-source-layout-and-routing-investigation-20261009.md)：NTT 条款位于无边框排版表，SCT 混合附录和表单，评分矩阵存在合并格及嵌套表，G302 用段落／tabs 对齐 schedule 字段，PRE 有复杂表格、图片、勾选、直接删除线和内部示例。这些已观察结构应按原生信息明确处理。已实现自动编号解析、字体符号解码及显式删除／划线文本分离；历史保真修复及尚未接受状态见 [DOCX 保真记录](./vetting-docx-fidelity-20261003.md)。

本次源码核查确认当前表格输出以 row／`List<String> cells` 为主，未持久化完整 `gridSpan`／`vMerge`、嵌套表层级和单元格关系；嵌套内容能读到文字不等于结构已保留。`tab` 转为空格，未保存 G302 等对齐字段的关系。继承样式划线、AlternateContent 分支、脚注引用关联和编号仍有明确验证边界。图片资产及条款关联需要从原生 relationships／锚点保存，图片中的扫描内容按扫描区域规则双解析。POI 不提供 Word 排版引擎，段落／表格／cell 作为原生来源位置，视觉核对使用另行绑定 renderer／字体／配置的派生页。

后续以已调查的文档家族补齐确定性结构提取、来源关系和质量门控；用变值、增删行、条款插入及合并格变化的材料做回归，不能硬编码样板正文、固定行号或项目值。模型负责身份／内容角色判断及后续语义任务时仍需依据实际来源；纯原生文字／表格的恢复无需先训练 OCR。复杂视觉区域局部处理，不将整个原生 DOCX 默认转成扫描 OCR。本次仅记录技术判断，未修改解析代码或切换在线服务。

## 原生及混合 PDF 的文字层与视觉结构联合解析

2026-10-09 用户询问非扫描 PDF 是否计划直接抓取文字层，再结合 OCR 和布局检测共同解析。该路线是既定原生／扫描／混合区域路由的实施细化：可靠可见文字、页面视觉结构和局部识别共同形成有来源的结构单元。当前只记录实现方案，联合解析尚未部署。

1. **原生证据抽取。** 使用 PDFBox 保留实际字符／字框、原物理页、字体与方向等可取得的来源属性；同步检查 page content、AcroForm fields、Widgets／appearance 和 annotations。抽取字段值与页面显示值不一致时保留两种表示及其位置；普通 page text 不能覆盖本包 GCT 的全部可见填值。
2. **页面视觉结构。** 在绑定原件、渲染器和配置的页图上检测正文、标题、多栏、表格、图像及读序。PDF 字符绘制顺序不是条款阅读顺序；有原生文字也不能省掉结构恢复。矢量图／线和嵌入图片保存为原件证据或绑定原页的派生资产，首版按 Q10 返回正确图像／表组，不因此承诺箭头或局部 DELETE 指向已自动理解。
3. **区域识别与校验。** 可映射且经核对的可见原生文字优先；图中小字、局部扫描、异常编码、未覆盖的表单显示内容等采用 OCR／完整 parser 恢复或对照。扫描区域和隐藏 OCR 层覆盖的扫描内容按 Q1 执行两套完整解析，保存原层和两套输出；真正非扫描页面不默认要求所有正文全文 OCR。不能仅凭文字数量判定原生文字可靠。
4. **来源对齐与结构恢复。** 将原生字框、布局框、OCR 框及图像映射到同一原页坐标，记录 cropBox、页面／局部方向和变换。恢复父条、条件、例外、表头、cell/span 和跨页表组。对照以区域或完整结构组为单位；表格采用完整组并保留原始候选，不从多个结果逐格挑值拼接。原生文字、OCR 文字和显示内容不一致时保留来源、分歧与接受依据，关键关系不明时标局部 unknown。
5. **切块与检索。** 输出规范结构及来源映射后再做长结构／短窗口索引；不重复嵌入同一文字的 native／OCR 表示，也不把两套表示当成两个独立条款。原始表示继续留存，重复判断须有位置和内容依据，不能仅按相似措辞合并不同条款。

当前 `DocumentParser.parsePdf` 是 PDFBox 原生抽取＋页级启发式 OCR fallback：`needsOcr` 检查文字量、乱码和部分图片尺寸；触发 OCR 后用 OCR 输出替换当前页的文字／块，尚未实现上述区域联合恢复。当前将 `TextPosition` 聚合成行 bbox，未输出逐字框，也未专门解析 field／widget／appearance。现有 bbox 计算可复用，但逐字来源、通用变换、表单显示、native／视觉冲突和完整表格关系仍需接入；这些是源码状态，不是已完成的在线验收。真实联合解析优先验证本包 GCT 表单和 Annex 原生／混合表格，再覆盖可靠原生正文、异常编码及局部扫描等类型，分别评价字符保真、结构、来源定位、遗漏与重复。
