# OER 独立来源训练材料调查与小样本取得

更新：2026-10-11，Asia/Shanghai。作者：`model_material_independent_review`。仅属于 OER 当前任务的监督材料准备；Drafting、变量识别 harness、当前业务服务、DB、索引和模型权重均未由本作者变更。规则以 [已确认范围](../oer-scope-and-rules.md) 和 [实施交接](../HANDOFF-ocr-embedding-rerank-20261008.md) 为准，质量缺口读取 [QUALITY-PLAN](../../tools/oer_training/QUALITY-PLAN-20261010.md)。

## 结论与准确计数

已取得 **2 种不同来源／布局类型的候选材料**：GSA 官方空白施工采购表单，以及 IBM 官方 PubTabNet 的科学技术表格样本。它们补充来源多样性，尚未形成已验收监督池：本次训练步数为 0，正式 train／dev／blind 家族分配仍为 **0／0／0**，humanConfirmed=false，没有质量收益或基础模型未见过这些公开材料的结论。

| 项目 | 本次实际结果 | 证据与限制 |
| --- | --- | --- |
| 小文件取得 | 22 个 HTTP200 文件，共 2,042,883 bytes | `acquisition-01/02/03.json`；逐文件 URL、UTC、状态、bytes、SHA 均保留，全部重新核对相符。不是下载了 22 个训练原件。 |
| 取得的原始视觉资产 | 1 个官方 PDF／2 个物理页，加 2 个官方表格 PNG | PDF 原件未改；两 PNG 为上游发布的 table crop，不是完整论文页。 |
| 上游标注 | 1 个 JSONL，20 条 example records；取得图片并实际目视的只有其中 2 条 | 两图片分别对应 100／12 个 annotation cells；有 bbox 的分别为 97／12。缺 bbox 的空项不能凭整齐猜坐标。 |
| 布局／来源候选 | 2 种大类 | GSA 施工表单与 BMC 科学表格确有不同身份和版式；两 BMC 文章不据不同 PMCID 自动算互斥模板家族。 |
| 与已有库存 exact SHA 交集 | 3 个取得视觉资产中为 0 | 只与已有 `source-separated-inventory-01.json` 记录的 SHA 比较，未重新遍历全部原件，也没有完成全文／视觉 near-duplicate 检测。 |
| 质量与训练 | 0 新 GPU／OCR／模型调用，0 训练 steps | 只用现有 CPU PDF、图像和 XML 工具做来源检查；没有将 native 抽取文本或上游标注直接升级为已核 gold。 |

本次所有原件、license、metadata、取得脚本、CPU检查产物和失败记录保存在忽略目录：

`D:\R&D\ConSense\.local\state\oer-independent-training-sources-20261011\author-01\`

## A：GSA SF1442 施工合同表单

**第一方来源及版次。** [GSA 表单页面](https://www.gsa.gov/reference/forms/solicitation-offer-and-award-construction-alteration-or-repair) 将其登记为 SF1442、当前 revision 12/2022，适用依据 FAR 53.236-1(d)。本次从该页实际链接取得 [SF1442-22.pdf](https://www.gsa.gov/system/files/SF1442-22.pdf)，两页原件 SHA `e1219ff87107034e2138d16dab677dda39f1991f67b9c8165f2de0403720ad6e`，772,811 bytes。两张 150DPI 静态页图已目视；不是填入项目事实的合同，也不是完整 FAR 法规。

**使用依据和限制。** [GSA Reuse and copyright](https://www.gsa.gov/website-information/website-policies) 说明多数站点材料可复制分发，联邦雇员职务作品通常在美国属于 public domain，同时要求逐件检查受限图片／credit。当前两页是 GSA 官方 prescriptive 空白表单，目视和原生文字中未发现第三方图片 credit 或单独版权限制；可作为本地 source-only 标注准备候选，保留 GSA、表单号、版次及原 URL。该事实不是 CC0／全球专门训练许可证，也不推出所有 `.gov` 内容都无版权；后续跨境发布或混入第三方填报附件时重新核具体权限。不存在任何未核 license 被默认准入的材料。

**可以补的任务。** 这份表单有多尺寸线框、未填值槽、checkbox、标签与限定说明，以及同页的正文、签署和日期栏，可用于“原文字面／原生位置”“label–slot／option 归属”“空白是未填状态”“完整表组与页外说明”的 source-only gold 准备。它与现有 Appendix H 连续大空格、FoT 扫描侧注不同，提供原生交互表单这一额外失效类型。金额栏仅作为 OCR 几何和文字研究，不成为比赛 BQ／计价输入；美国表单条文也不进入香港项目合约的生产检索依据。

**已发现的兼容边界。** 原 PDF 的 AcroForm 包含 `/XFA`。本机 PDFium 明确告警没有 XFA support；静态页背景可以渲染和目视，不代表动态 XFA 行为已解析。pypdf field tree 计 86 项，page widgets 合计 82 项，二者不能未经核验当作相同分母。本次没有填写、flatten、迁移字段或修改原件，也没有升级 installed PDFium。native 文本为未核抽取，不能作为逐字 gold；需完整人工／独立来源核对静态页面、widget／field／XFA 与页内布局后才能形成监督 manifest。

**隔离和近重复。** 建议候选 key 为 `GSA:SF1442:REV12-2022`。同 SF1442 的新旧 revision、填值副本、打印／扫描／旋转、crop 和合成扰动先合并同一模板家族，不能拆两页来声称 train／blind。已目视这两页，后续默认只作开发或训练准备，不追认 blind。取得其他官方 form number 时仍核版型和共用正文，而不是每文件一个 family。

## B：PubTabNet 技术表格与逐项许可

**第一方来源。** 使用 [IBM 官方仓库](https://github.com/ibm-aur-nlp/PubTabNet) 的固定 commit `8ffde9024bd331f5a61c3c549fdf30dd3c3e43a5`，取得 README、LICENSE、`examples/PubTabNet_Examples.jsonl` 及两个小 PNG。[固定 README](https://github.com/ibm-aur-nlp/PubTabNet/blob/8ffde9024bd331f5a61c3c549fdf30dd3c3e43a5/README.md) 说明表格来自 PMC 的可商业使用 OA collection，annotation 有结构 token、cell 文字与非空 cell bbox。这个说明不代替逐图片 license。[固定 LICENSE](https://github.com/ibm-aur-nlp/PubTabNet/blob/8ffde9024bd331f5a61c3c549fdf30dd3c3e43a5/LICENSE.md) 明确分开 IBM 标注与原论文图片的权利。

**许可核对。** IBM annotation 采用 [CDLA-Permissive-1.0](https://cdla.dev/permissive-1-0/)；本地计算使用有授权，分发数据需保留 license、attribution，并声明修改。图片按每篇原论文许可处理。[PMC 官方要求](https://pmc.ncbi.nlm.nih.gov/tools/openftlist/) 是逐篇查许可、使用指定自动取得入口。本次由 [PMC OAI API](https://pmc.ncbi.nlm.nih.gov/tools/oai/) 的 `GetRecord` 保存两篇全文 XML 和 permissions；两者均明确 CC BY 2.0。该 [许可](https://creativecommons.org/licenses/by/2.0/legalcode.en) 允许复制与改作，须保留作者、题名、来源、license 和适用的修改声明。没有把它们错写成 CC BY 4.0，也没有把 IBM annotation 许可套到所有图片上。

| 实际样本 | 原论文及可回取许可 | 资产 SHA／已取得内容 |
| --- | --- | --- |
| `PMC1626454_002_00.png` | [PMC1626454](https://pmc.ncbi.nlm.nih.gov/articles/PMC1626454/)；Helbling 等，2006，BioMed Central；exact primary title 和完整 permissions 见保存 XML／`primary-source-details-01.json` | PNG 32,701 B，SHA `d34a3a312e57f9c2f3aba335729320ee3dd52ddf09b0c81b59d65746d910dac7`；100 annotation cells／97 bbox；两层分组表头、长行标签、数字、显著性星号。 |
| `PMC2753619_002_00.png` | [PMC2753619](https://pmc.ncbi.nlm.nih.gov/articles/PMC2753619/)；Brand 等，2009，BioMed Central；保存 XML明确同一 CC BY 2.0 | PNG 4,691 B，SHA `3edcd682c7817fe3aab1bf531a5eff24a66e5e489a389d3e210e5fe5eaf48d51`；12 annotation cells／12 bbox；简洁技术统计表、数值及长列标题。 |

两个 sample 对应各自 source `table-wrap id=T2`；原 XML 内未发现该表的独立 permissions／attrib 节点。两 PNG 均已实际目视，源 XML 的 table caption／note 与图片裁块分开保留；crop 外说明不自动成为 crop-only 训练 target，crop 也不能伪称完整来源图组。

**能补的任务和不能代替的任务。** 可用作完整 table-crop text／HTML结构／非空 cell bbox 的核对起点，重点检验列归属、多层 header、标点、数值和 span。112 是两个上游 annotation records 的 cell 总数，不是本地112项已核正确率；上游自动标注须逐图重审，HTML logical grid 与 PDF physical ruling 分开。它们是科学技术表格，不能代替合约条款层级、跨页法律条件、删线状态或 SL 原件的整组监督，也不加入生产合约索引。

**隔离。** 以 PMCID 保持同论文全部表／页／派生的原始亲缘，同时以 BMC／期刊样式／共享版型建立 nearDuplicateGroup；当前两个 table 不在不同 split 中承担泛化证明。完整 example JSONL 的20条只算取得的 metadata；只有2条配套图片取得，不把其余18条计成完整样本。后续优先取上游正式 train 部分并逐件核 license，再按论文、publisher版型、文本／布局近重复合并后分组；不取上游 test 答案来微调。外部公开 benchmark 可能已被 foundation checkpoint 看过，污染状态保持 unknown。

## C：OmniDocBench 已调查但不准入部署训练

已取得官方 README、code LICENSE 和 [官方 dataset card](https://huggingface.co/datasets/opendatalab/OmniDocBench/blob/main/README.md)，未下载它的 PDF、图片或 annotation corpus。[仓库代码](https://github.com/opendatalab/OmniDocBench/blob/main/LICENSE) 为 Apache-2.0，但 [数据版权声明](https://github.com/opendatalab/OmniDocBench#copyright-statement) 另限研究、排除商业使用。当前不将其当可随模型部署的质量训练数据，也不以代码许可证覆盖原文权利。以后如需独立非商业评价，另按准确数据版本和原件许可登记，不把 benchmark 评价资料混入对应训练 split。

## 失败、告警与未完成状态

| 实际观察 | 处理及保留状态 |
| --- | --- |
| 最初 CPU runner 命名 `inspect.py`，遮蔽标准库 inspect，pypdf import失败 | 首轮实际0个样本处理／0渲染；原脚本保留在 `retained-first-import-failure/`，仅将相同字节的执行副本改文件名为 `inspect_sources.py`，重新检查成功。未改依赖或源文件。 |
| Windows GBK stdout不能输出版权符号 | 保存的 UTF8 inspection 完整；失败摘要保留，后续只读控制台回读采用 Python `-X utf8`，没有归一文字。 |
| SF1442 XFA support告警 | 记录 static render 能力与 dynamic form 未验收，gold为0；没有按提示安装新 PDFium或宣称完整表单支持。 |
| web lookup打开原CDLA域名一次Internal Error | 后续第一方实际GET成功并保存 redirect后 `cdla.dev` 页面；不是 license消失，也不删除原lookup失败。 |
| 两个最初 PMC GetRecord 请求实际并发重叠 | 在同一次调用中读取官方 API 文档和执行下载，未在执行前落实“不并发”要求；HTTP200不代表取数流程合规。`retrieval-policy-observation.json` 保存准确状态；无后续PMC／bulk请求。下一次必须单独顺序队列、低于3rps并采用压缩，不沿用本次通用并发取得器访问PMC。 |
| 初版 XML检查列出所有 article-title，混入 bibliography | 原 `source-inspection-01.json` 保留不改；新增 `primary-source-details-01.json` 精确区分 front/article-meta 的 primary title、authors、IDs与T2，不把参考文献当来源题名。 |

本次 source acquisition HTTP失败为0，工具／CPU处理与流程缺陷按上表分别保留，不能概括成“全程零失败”。取得原视觉资产与所有上游metadata字节已重新哈希相符。

## 如何进入下一轮 OER 质量工作

1. 先将三个视觉资产记为新的 **source-only development candidates**，固定原件SHA、parent及版次、许可、author／caption／note和 renderer身份；仍不改正式 quality pool的0／0／0计数。
2. GSA需独立核两完整静态页的正文、label／空槽／option、physical grid、页外说明与XFA／widget歧义。PubTabNet需两完整crop逐cell核字面／structural tokens／bbox与原T2归属；空格、blank、number／unit／star的监督含义显式记录。未核项unknown，不从模型输出补gold。
3. 先做零训练的冻结base task路由与crop覆盖对照，再按源残差选择layout／crop／ownership／native结构修复或recognition SFT。新样本不能成为“为了训练而训练”的理由；本轮已消失的FoT星号与空槽残差不机械追加LoRA steps。
4. 至少补齐可核来源家族与group后，再按union-family设计 train／dev／blind。模板换版本／打印／扫描扰动不跨split；任何看过的targets默认development或train，公开数据不声称基础模型污染为0。是否形成足够互斥家族由独立审核决定。
5. 正式质量收益需要冻结模型／processor／官方task协议／完整预算、source-only评分、export／fresh reload／full-parser回接，以及原件与线上业务分别验收。当前没有运行训练、索引、模型API、GPU、service restart、打包或部署；未将2d／2e答案、标准Drafting模板、BQ／PSS或SL非技术章节加入材料。

## 本机可复核证据

所有下面文件在上述 ignored owned目录中；文档仅提交调查结论，不提交原件／许可原页或密钥。

| 文件 | bytes／SHA256 | 内容 |
| --- | --- | --- |
| `source-inspection-01.json` | 81,315／`889baa214030f24b98268b2d5b44bc50ef2d3be9a43b1cb7af13fe9a64b525d1` | 两样本annotation／license，PDF metadata、两静态页和native抽取、完整取得物核查。 |
| `primary-source-details-01.json` | 24,088／`26d116ed15eb388069d65f8c31e804a8a30911d466f55f73f8f31ddde4bcbbfe` | exact article identity与T2、22下载核查、旧库存exact overlap及XFA边界。 |
| `pubtabnet/PubTabNet_Examples-pinned.jsonl` | 146,162／`858ad438bdc1fd6af7538e7a0704c85d62696a601dadf0174a5b0b380685e9f2` | 官方20条example annotations；没有20份配套图。 |
| `pubtabnet/PMC1626454-source-license-oai.xml` | 53,495／`3e8648808c0da9c5857fd981f96e794935a3bf8af11d2c96864df0c038843171` | 原论文完整OAI permissions与原T2。 |
| `pubtabnet/PMC2753619-source-license-oai.xml` | 118,852／`eb04d48465885a86cde649193bbcaded442149184dcfbcb348b37e0fce02009b` | 原论文完整OAI permissions与原T2。 |

最终 `terminal-receipt.json` 将全部 acquired files、脚本、检查物、失败记录和本文完整bytes／SHA封存。该收据是本作者的机械保全终态，不代替另一作者的许可／原件gold复核、正式split、训练或业务验收。
