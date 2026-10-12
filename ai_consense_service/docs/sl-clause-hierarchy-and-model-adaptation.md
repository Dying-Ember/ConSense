# SL 条款层级、定位与模型调整

更新：2026-10-09。依据：用户明确要求以 SL 技术条款的 clause no 切块、构建层级和表格／图像定位，并将模型调整纳入实施。本文细化 [OER 已确认范围与规则](./oer-scope-and-rules.md) 中的 SL 路线；实际完成状态记在 [handoff](./HANDOFF-ocr-embedding-rerank-20261008.md)。它是实施要求，不是已完成全部条款索引或微调的声明。

## 原件编号与结构依据

当前源为 SL2022 April 2024，SHA-256 `6331c283da806846a06644814fa2271182314dbc2235c711a16e8492faaa0492`。活动范围为九技术库物理页 1127–4300，共 3,174 页。SL PRE 整库及非技术前置页的处理保持中央规则；项目 PRE 单独保留。

原始书签显示技术库具有明确层级，例如 `Architectural Works → CLA CLADDING → CLA1 PROFILED SHEET ROOFING AND CLADDING → MATERIALS → PROFILED SHEETS → CLA1.M010.9 GENERAL`。这是可靠的结构先验，正文块和原页位置仍须核对。同页往往有多个条款，单凭书签页号不能得到每条正文起止坐标。

2026-10-09 独立原件调查发现 outline destination 还含 `/XYZ`、`/Left`、`/Top` 和 `/Zoom`。例如物理页 1133 的 `CLA1.M010.9` 与 `CLA1.M020.9` 分别有 Top 624／577、Left 54，可保留原始 destination 并转换到正文 bbox 的同一坐标系，核对编号／标题后辅助切块。现有 `original-bookmarks.json` 只保留页码和 depth，不能当作已保留这些原始坐标的完整产物。

书签本身也有错误层级：物理页 1258 的 `Fig. 4 - Aiming patterns for hard body inpact test` 书签层级与页眉所示 `COM5 > APPENDIX COM5/I` 不一致。因此 outline 树、编号候选和正文定位交叉核验后才能成为规范层级；编号样式候选总数不作为已解析正文条款总数。

条款编号有大小写、数字和字母尾码、附录形式等变化，例如 `CLA1.M010.9`、`COM2.M005.A`、`com2.w490.9`、`COM2.APPEND1.9`。保存原始写法，另存经规则核验的查找键；不能只接受大写或 `.9` 结尾，更不能把所有点分段数字当父子关系。尾码的业务意义须以原件说明为准。

“每条正文条款有编号”是待逐条验证的结构假设。库／分项／组标题、条内 `(a)/(i)` 子项、续页正文、表格行、图例、脚注或技术附录可能没有独立 clause no。它们仍是内容，按实际父节点归属保留；编号缺失、重复或 OCR 不确定进入异常清单，不由模型补出编号。

## 切块与定位契约

| 层级或对象 | 必须保留的关系与定位 |
| --- | --- |
| 原件／版次／技术库 | 原字节 SHA、edition、scope manifest identity、library ID；条款主键在该命名空间中唯一 |
| worksection／章节／组标题 | 原目录／书签路径和正文标题证据，observed parent ID；标题顺序与原件一致 |
| 完整 clause | 原 clause no、标题、父路径、全文与读序；跨页内容属于同一条款，保留每页来源 spans |
| 子款、条件、例外、注释 | 保留原子项标签与 observed parent；编号相似不代替从原件确认的隶属关系 |
| 表格／跨页表组 | 所属条款、table/group ID、表标题、完整表头、单位、row/cell/span、续表关系；保存原页图和表组边界，不逐格混用两套 parser |
| 图像／流程图／图例 | 所属条款或独立附件节点、asset ID／字节 SHA、caption、原页 bbox；首版按 Q10=A 用文字关联返回正确原图，不以未验证箭头／邻框关系支持自动结论；已有关系观察单列状态 |
| 原页／区域 | 1-based 原 PDF physicalPage、printedPageLabel、source block ID、坐标单位／原点／画布、bbox、裁图至原页变换及 input asset identity |

正文块先按阅读顺序和经核对的条款起止聚合，再生成 clause 单元。页眉页脚中的重复编号是定位证据，不是新条款起点。目录、交叉引用、表格内的引用编号也不自动开启新条款。跨页续文、表头、否定和例外保持来源关系；不能在下一书签页之前简单按页截断。

一个条款未超预算时，结构向量覆盖完整条款及必要标题路径；较短局部窗口用于精确定位。超预算时按子款或完整表组有序分组，保存 parent ID、成员、实际 token 数及覆盖；不能将相邻条款为了凑满 tokens 混成新义务。现有 2048／512 配方是初始对照，不是最终最优参数。大表不得静默截断或丢表头；图像无法可靠转写时保留图像证据及未知状态。

编号查找、cross-reference graph 和 BM25／dense 检索可以共同提供候选。引用边保存原始引用文字、定位与目标解析状态；未解析或跨版次引用保留 unknown。仅“属于同一分项”或“模板高度相似”不足以证明条款相同。应用引用应可从命中窗口回到完整 clause、表组及原页。

## 模型调整与评价

本资料的高结构化、重复模式及规模决定了要建立针对 SL 的适配与评价流程。调整对象由误差层级决定：

| 误差 | 调整对象及验证 |
| --- | --- |
| 漏区域、错读序、表头／cell／图像定位与文字关联错误 | 完整 parser 的 layout／读序／表格阶段及其配置，以及原图／caption／父条映射。识别模型的语言补全不能恢复未被检测的区域；图形关系自动理解属于后续扩展，按 Q10=A 不作为首版模型调整目标 |
| 字符、单位、编号、勾选等系统性识别错误 | recognition 配方；残留重复问题适合时建立原件标注并微调对应识别模型，重新验证完整 pipeline |
| 证据完整但未进入候选 | clause／窗口表达、标题上下文、dense／BM25 融合与 embedding；合适的 query—必要证据集用于领域适配 |
| 已入候选但排序丢掉条件、例外或相反条款 | 同池 baseline／结构 rerank；构建分级相关性和真实难负例，必要时微调 reranker |
| 原文拼写或语法本身有问题 | 保留原文证据与文字问题标注；可另作展示修订建议，不改原始证据、编号或条款含义 |

先锁定完整精度基线和冻结参考标注，再单独调整各因素；结构化源本身可以解决的切块和定位关系先实现。权重微调需要明确模型兼容性、训练目标和新 checkpoint identity。不能用学习条款编号、固定项目值或评价答案代替泛化检验，也不预设所有模型都要微调。

按原文件／近重复／派生家族分组，并考虑相似 worksection 与跨章节重复模板，再决定留出；切块后随机拆相似条款会造成泄漏。当前样板已有开发暴露，不能改称未见盲测。合成变值和扫描压力样本单列，2d／2e 答案仍隔离。

验收分别记录：条款编号与正文对齐、完整页／表组覆盖、原页定位、必要证据召回、同池排序和应用引用回跳。字符正确不代表表格关系正确；clause no 唯一不代表每条正文完整；索引成功不代表检索任务正确。质量结果与资源／时间日志分开，并在 handoff 更新未解决问题。

## 2026-10-09：原始 PDF 误差驱动的 OCR 改进与训练评估

用户进一步要求评估模型训练难度及如何提高 OCR。以下是根据[原始 PDF 实测](./testing/original-pdf-dual-parser-bf16-validation-20261009.md)形成的实施建议；官方训练支持调查不等于已训练或已接通生产链路。详细研究见 [Paddle 当前 checkpoint](./testing/paddle-current-checkpoint-training-feasibility-20261009.md)、[MinerU 当前 checkpoint](./testing/mineru-current-checkpoint-training-feasibility-20261009.md)及[误差分层改进计划](./testing/oer-error-to-training-plan-20261009.md)。

| 当前观察 | 先处理的层与改进目标 |
| --- | --- |
| MinerU GCT 编号被 crop 裁掉或归入 aside；APB notes／合同标识漏块 | 优先检查检测覆盖、分类、读序及裁块边界；识别模型未看到完整区域时，不用语言微调补造编号或 notes。布局微调需覆盖正文、边注、编号及图纸周围文字。 |
| Paddle AMR 表的状态列／checkbox 归属错误；MinerU AppH 将内容放入原件空白栏 | 对照原页建立完整表组 gold，包含空 cell、row／column、span、表头→值及状态；用各引擎实际结构输出格式生成训练目标。MinerU 当前表格目标需核对 OTSL 方言，不能照搬旧 HTML 教程。单项文字或勾选计数不代替结构验收。 |
| APL Roman 编号空槽、APU 编号前缀两家共同漏掉 | 先追踪原页→检测→实际 crop→raw response，确定缺失发生阶段；未定责前不把全部问题归入 recognition，也不从模板补出固定内容。 |
| FoT 删除状态／颜色／有效选择丢失 | 首先保存原生文字、图形或编辑状态与可见外观；扫描状态另行标注、恢复或保留 unknown。不能把删除线、checkbox 或空槽当普通拼词自动修复。 |
| 条款父子、跨页续文／表头／续行、图表定位 | 利用核验后的原编号、书签、位置和表组关系完成规范化与完整候选采用。它们是独立工程目标，不默认通过 VLM SFT 自动解决。 |

建议次序为：保持原件保真与完整组门控，固定输入和布局／裁块对照，再试同型号布局适配；对 crop 已完整但 raw response 仍系统性错误的识别／表格目标开展 VLM 微调。每步单独比较，避免同时修改输入、布局、权重和 selector 后无法归因。既定双解析继续保留，不因微调成功就撤去第二路。

训练必须对应完整 pipeline 的实际组件。当前 MinerU 4.0.10 Standard 映射到 `high`，页面 layout 来自独立 PP-DocLayoutV2，经 normalization／crop 后调用 VLM `batch_extract_with_layout`；对 VLM 的 Layout Detection 任务做微调并不会自动改变此 Standard 分支的检测结果。Advanced／`xhigh` 的 VLM two-step layout 是另一个配方，不静默替换本轮基线。Paddle 主路的 PP-DocLayoutV3 官方训练支持不能当作 MinerU Standard 的同型号支持证明。先核对实际检测器和裁块接口；某条路线未显示系统性布局错误时，不机械追加布局训练。

同一 Standard／`high` 分支还强制 `image_analysis=False`，不能将普通开关设为 true 后就称图内文字／相关性任务已执行。Q10=A 的图内文字与原件资产回取需要单独确认真实执行入口；模型训练不能替代未执行的任务。MinerU 当前 tokenizer 将 OTSL 结构 token 标为 special，训练 label 不得一律屏蔽这些目标，解码／导出不得一律丢掉 special token；需按当前 parser 方言核验完整表格结构。

训练前先做小型兼容试验：零步加载／导出／完整 parser 重载，随后少量标注样本的一步训练、LoRA 合并或完整模型导出，再重复端到端回归。训练 loss 下降、adapter 文件存在或独立裁块正确，不代替完整链路通过。PP-DocLayoutV3 的同型号官方训练／导出已查到；当前 Paddle VL1.6 的官方同 revision recipe、MinerU 2605-Pro 的专属训练登记及本项目导出回接仍需兼容实跑。Paddle 的 Transformers 官方逆映射是可用候选通路，仍需当前 checkpoint roundtrip；不能预判必须自写转换器，也不能写成已验证成功。

PP-DocLayoutV3 的布局 gold 需按官方 COCO 实例 segmentation／polygon＋bbox＋类别＋read_order 格式准备，只有 bbox 不足以覆盖其 mask／order 训练目标。当前部署25类与官方模块 demo 的11类不是相同 head；保持当前类别ID及顺序，或显式记录新类别模型与 parser 契约。官方 training `.pdparams` 与已下载静态推理模型分别绑定身份，不能未经证明视为同一训练起点。

当前 23 个完整风险页及 37 个关键检查用于已暴露的开发回归；训练须另补全文、区域、读序、cell／span、状态和跨页 gold，原件拼写如 `COMPLTION` 照实保留。SL 同来源内部章节留出只用于内部诊断，不能声称文件级独立泛化；真正泛化需不同来源／版次／项目，并控制相似 worksection、近重复和全部派生家族。合成变值／退化单列压力结果，2d／2e 答案继续隔离。

用户随后明确纠正执行地点：**本机 RTX A4000 16GB 调试模型参数及处理代码，再冻结打包部署到 H800；平台改代码需大量扫描，不是训练／开发迭代场所。** 先在本地做参数对照、必要 BF16 LoRA tiny pilot 与完整 parser 导出回接，按 microbatch1、冻结非目标模块及 gradient checkpointing 评估真实16GB训练峰值；完整关键输入／target不得为适配显存静默删减。80GB训练状态算术仅作参考，不自动转成超算训练计划。平台只验证已冻结包的运行兼容、推理资源／吞吐及LLM阶段切换；失败回本地修新版本。发布包需固定权重、代码／依赖、processor／tokenizer、task／配置、回归／回滚与身份manifest，模型／配置变更也不假定免扫描。当前未启动真实训练、未制作最终发布包、未部署新 checkpoint、未修改在线 OER profile。
