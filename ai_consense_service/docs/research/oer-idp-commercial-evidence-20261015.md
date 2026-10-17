# 商业 IDP 对合同、空白表单与大型技术文档的处理证据

核查日期：2026-10-15。本文是官方资料研究，供 ConSense 后续架构选择参考；未上传原件、调用云端或本地模型、修改提示词实现、训练、构建或变更服务。执行范围仍以[OER 已确认范围与执行规则](../oer-scope-and-rules.md)及[当前交接](../HANDOFF-ocr-embedding-rerank-20261008.md)为准。PRE 仅作为大型技术书册的文档形态参照，不恢复已排除的 PRE 原件；图纸仍只识别路由后排除。

## 结论与证据等级

商业资料支持的方向是：先保留原件文字及版面观察，再做结构化业务抽取，之后执行规则校验与人工异常复核。OCR、字段语义、空白状态、法律关系和人工确认是不同的结果，不能由一段自由生成的 JSON 同时代替。提示词能够改善已有证据的解释与关联，是值得独立验证的方法；官方材料没有证明它可以恢复未识别的写区、证明所有空白字段、消除长输出截断，或保证从未见过的合同版式。

本文区分三种证据：**官方能力**指产品文档明示的输入、结果和限制；**案例证据**指厂商及客户公开叙述的具体方案，不能视为独立基准；**ConSense 推断**是结合现有缺口提出的设计判断，尚未实现或通过来源验收。下列云产品仅作为架构参照，不建议据此把比赛原件交给云 API。

## 1. 文字、版面、字段与复核如何分工

| 产品及官方能力 | 能借鉴的分工 | 不能据此授予的能力 |
| --- | --- | --- |
| AWS Textract 分别返回文字、表单键值、表格、查询答案、签名检测和 Layout；Layout 包括标题、段落、列表等阅读顺序元素。[分析能力](https://docs.aws.amazon.com/textract/latest/dg/how-it-works-analyzing.html) | 同页文字、结构、选择状态和字段关系各保结果及定位，再组合。 | 检出签名的几何位置不等于确认签署人角色、条款适用或法律有效性。 |
| Azure 把内容、版面、样式与语义字段分开；文字有 span，区域带物理页码和 polygon；原始表格与语义字段并非同一层。[响应契约](https://learn.microsoft.com/en-us/azure/ai-services/document-intelligence/concept/analyze-document-response?view=doc-intel-4.0.0) | 文本观察保原文，字段值及规范化值另存；表格结构不能直接当作业务字段映射。 | 结果可描述跨页区域，但官方明确目前没有跨页阅读顺序；不能由坐标集合推定整份合同已连通。 |
| Google Form Parser 抽取 OCR、键值、简单表格和复选框；Custom Extractor 定义业务实体。[Form Parser](https://docs.cloud.google.com/document-ai/docs/form-parser)、[抽取类型](https://docs.cloud.google.com/document-ai/docs/extracting-overview) | 通用识别与任务专用字段 schema 分开，复杂表格需要父子实体等明确建模。 | Form Parser 不能上训，简单表格不支持跨行／跨列单元格；不能将其能力当成所有处理器的共同上限或保证。 |
| ABBYY Vantage 区分 OCR JSON、Fields JSON 与错误输出；Fields JSON 包含字段结构及规则错误。[输出契约](https://help.abbyy.com/en-us/vantage/1/developer/developer_jsonexport/) | 观察、抽取、规则校验及运行失败分别落盘。 | 只有成功状态或字段值不能证明页面、字段清单和所有关系完整。 |
| ABBYY 人工复核接口把原页、字段、规则错误及复核状态放进事务流程。[人工复核集成](https://help.abbyy.com/en-us/vantage/1/developer/developer_integrate_manual_review/) | 需复核的证据与机器输出同行，保留复核前后差异。 | 人工修正后的交付值不能倒算成自动解析正确率。 |

AWS 建议根据任务敏感度采用置信度及人工审查，并提示不规则、合并单元格会影响表格结果；Azure 也将置信度用于关键／低置信度结果的复核。厂商示例阈值不是 ConSense 的验收阈值。[AWS 最佳实践](https://docs.aws.amazon.com/textract/latest/dg/textract-best-practices.html)、[Azure 精度与置信度](https://learn.microsoft.com/azure/ai-services/document-intelligence/concept-accuracy-confidence)

## 2. AoA、APL 的空白、填写与混合状态

| 来源问题 | 官方证据 | 对 ConSense 的含义（推断） |
| --- | --- | --- |
| 空白字段是否一定存在于结果中 | Google 明示 Form Parser 对值未填写的键值对不能可靠解析。Azure 通用键值输出允许未填写字段有空值，但前提是先检出该键。[Google 限制](https://docs.cloud.google.com/document-ai/docs/form-parser)、[Azure 键值说明](https://learn.microsoft.com/en-us/azure/ai-services/document-intelligence/concept/add-on-capabilities?view=doc-intel-4.0.0#key-value-pairs) | `value=""`、未返回实体、没找到答案，分别可能是空白、漏识别、低置信度或不适用，必须区分。APL 的条目标记和空条目骨架不能依附于非空 OCR 值才存在。 |
| 复选框／选择标记 | Textract 的选择元素可以处于键值或表格单元格中；Google 的复选框有填／未填状态，但不支持单选按钮，且部分框无关联键。[AWS 选择元素](https://docs.aws.amazon.com/textract/latest/dg/how-it-works-selectables.html)、[Google Form Parser](https://docs.cloud.google.com/document-ai/docs/form-parser) | 印刷的 `OR` 是分支关系，不能当成已选／未选的复选框；文字填写区与 OMR 状态需分别观察。 |
| 签名存在与签署语义 | AWS 能检出签名区域；Azure custom neural 支持标注签名字段。Google 签名为视觉存在的派生判断，未检出时不返回该实体，且没有 text/page anchor。[AWS 分析](https://docs.aws.amazon.com/textract/latest/dg/how-it-works-analyzing.html)、[Azure custom neural](https://learn.microsoft.com/en-us/azure/ai-services/document-intelligence/train/custom-neural?view=doc-intel-4.0.0)、[Google 派生与签名](https://docs.cloud.google.com/document-ai/docs/ce-derived-signature) | 检出笔迹不证明签署身份、所属执行方式或法律完成。未检出签名也不独立证明原件签署区为空。 |
| 一个字段含多个写区／子字段 | 产品有对象、数组、父子实体或表格建模，但没有本次 AoA `mixed` 状态的现成质量契约。 | `mixed` 必须由完整子字段清单及各自证据计算；缺一个子区时保留 unknown，不能用“该段有文字”赋整组 filled。 |
| ABBYY 的勾选与空值 | FlexiCapture 12 有专门 checkmark 数据类型，可将未选映射为空值；这是该产品的 OMR 字段契约。[checkmark 类型](https://help.abbyy.com/en-us/flexicapture/12/distributed_administrator/properties_data_type_checkmark/) | 不把 FlexiCapture 的接口自动移植为 Vantage 能力，也不据此推断普通空白横线已被发现。本次未核到可直接保证 AoA 签署状态的 Vantage 契约。 |

ConSense 应使每个字段保有稳定 ID、条目／选项归属、物理页与写区证据、原文值、显式状态和未知原因；owner 与跨页关系保留端点证据。扫描件的空白判断需要视觉观察或专门结构检测，不能仅由 OCR 空文本推断。该建议是设计推断，不等于当前解析器已具备这些输出。

## 3. 大型 PRE／SL 形态必须按任务分段

数百至数千页技术书册是核心场景。商业 API 的大页数限额解决接收和作业组织，不自动解决章节、跨页表格、条款引用或整书理解。官方现行限制如下；版本、模式和处理器不同不能混用。

| 官方处理器／模式 | 明示上限或运行方式 | 能说明什么 |
| --- | --- | --- |
| AWS Textract 同步／异步 PDF、TIFF | 同步 10 MB、1 页；异步 500 MB、3,000 页。[限额](https://docs.aws.amazon.com/textract/latest/dg/limits-document.html) | 多页应通过异步作业组织，不能将 3,000 页误读成同一语义上下文。 |
| AWS 异步结果 | Start 返回 JobId，完成通知后 Get；结果使用 NextToken 分页。ClientRequestToken 支持重复请求取得同一 JobId。[异步机制](https://docs.aws.amazon.com/textract/latest/dg/api-async.html)、[Start](https://docs.aws.amazon.com/textract/latest/APIReference/API_StartDocumentAnalysis.html)、[Get](https://docs.aws.amazon.com/textract/latest/APIReference/API_GetDocumentAnalysis.html) | 借鉴可恢复作业、幂等身份及结果分页持久化，页级遗漏和失败仍需另查。 |
| Azure S0 分析 | 500 MB、2,000 页；可用 `pages` 指定范围；OCR JSON 响应也有 500 MB 限制。[服务限额](https://learn.microsoft.com/en-us/azure/ai-services/document-intelligence/service-limits?view=doc-intel-4.0.0) | 原件输入、页范围及输出体积需同时控制；接收 2,000 页不建立跨页阅读顺序。 |
| Google 批处理 | 输入最高 1 GB；Custom Extractor 200 页、Form Parser 100 页、Layout Parser 与 Enterprise OCR 各 500 页；带复选框的 Custom Extractor 为 10 页。通常在线模式上限 15 页，另有特定例外。[现行限额](https://docs.cloud.google.com/document-ai/limits) | OCR、Layout 与字段抽取限额不同，不能拿 Layout 的 500 页替代字段处理器的 200／10 页上限。 |

跨页语义也需要专门契约：Azure custom neural 对跨页表格要求将各页行标为同一表，并包含单页、多页变化；Google 的特定 foundation 版本支持跨页三层嵌套实体。相对地，Google derived 字段是逐页生成，跨页信息未完整支持。可见“某类跨页结构受支持”不等于“所有跨页条款关系都正确”。[Azure 跨页表格](https://learn.microsoft.com/en-us/azure/ai-services/document-intelligence/train/custom-neural?view=doc-intel-4.0.0)、[Google 跨页嵌套](https://docs.cloud.google.com/document-ai/docs/ce-with-genai#label_cross-page_nested_entities)、[Google derived 限制](https://docs.cloud.google.com/document-ai/docs/ce-derived-signature)

**ConSense 的大型文档路线（推断）：**

1. 先冻结来源 SHA、版本和完整物理页映射。原生文字／结构按页或有限批次流式保存；需要视觉识别的页面另排队，每页保成功、失败或待执行状态。
2. 从目录、标题和编号建立章节树，以章节、完整表格、完整执行选项组作为有界抽取单元，携带文档类别、版本和父章节路径。不能仅按固定 token 截断后丢掉父上下文，也不能整本送入 VLM。
3. 跨页表格、续条款、`OR`／Notes 及引用建立候选边；拼接必须引用两端来源，并保留待续状态。批次已完成与语义关系已闭合分别计算。
4. 作业清单保来源／页／渲染身份、模型及配置、输入和输出摘要与实际回执；按依赖摘要重算受影响章节，失败仅续跑未完成部分，保留旧结果和失败。
5. 逐项检验全页覆盖、章节覆盖、表格连续性及旧正确保持。原件能被读取、任务退出成功和生成了大量文字，都不能替代保真证据。

这套分层不要求每层新增容器。单 H800、250 GB 快速主机内存与最多五容器是部署约束，250 GB 主机内存不能当成显存或整书模型上下文。现有 App＋静态页面、MySQL、Qdrant、CPU coordinator、GPU 执行槽的五角色建议可承载这些作业；GPU 任务串行、CPU 流式处理、队列及回执持久化仍须独立验证。本研究不证明 AIOP 容器计数、权限、镜像或 H800 运行已通过。

## 4. 修改提示词是不是另一种办法

是，但应作用于明确任务和完整来源单元。Google Custom Extractor 明示使用字段名称与描述组成底层提示；描述可以提供位置、格式及消歧背景。其文档级提示只是整体提示的一部分，限 500 字符，官方特别提醒不要依靠它校验合计等规则。直接抽取 `Extract` 与推断 `Derive` 也分开定义；零样本仍要用测试集评估，少样本示例不构成未见版式泛化证明。[字段与文档提示机制](https://docs.cloud.google.com/document-ai/docs/ce-mechanisms)、[生成式抽取](https://docs.cloud.google.com/document-ai/docs/ce-with-genai)

Azure `queryFields` 则是提供字段名列表，扩展抽取 schema，单请求最多 20 字段；它不能被描述为无限制的自然语言问答提示。AWS Textract Queries 才有面向问题的查询接口，找不到答案可能返回空结果；空答案仍不是来源空白的正面证明。[Azure Query Fields](https://learn.microsoft.com/en-us/azure/ai-services/document-intelligence/concept/add-on-capabilities?view=doc-intel-4.0.0#query-fields)、[AWS 查询答案](https://docs.aws.amazon.com/textract/latest/dg/queryresponse.html)

对 ConSense，可把提示改写作为后续独立实验：输入完整章节或字段组，要求固定 schema、来源引用、字段归属、直接值与推断值分开，并允许 unknown／abstain。重复的 Name／Address／Occupation 可利用当事方及执行选项上下文消歧；大型书册则携带父章节背景。程序检查字段清单、来源存在、数值关系及输出完整性，模型不自行宣布校验通过。

提示优化不能关闭四类缺口：漏掉的 OCR／写区无法凭文本提示补成真实观察；空 OCR 不能证明 unfilled；上下文或生成预算不足造成的截断需工程处理；固定七页的改进不能证明陌生版式泛化。本次未写或执行新提示，也不恢复模型训练、换型或参数消融。

## 5. 官方客户案例实际证明到哪一步

| 官方案例 | 实际方案与可借鉴点 | 证据边界 |
| --- | --- | --- |
| [AWS／Rocket Close，2026-04-02](https://aws.amazon.com/blogs/machine-learning/rocket-close-transforms-mortgage-document-processing-with-amazon-bedrock-and-amazon-textract/) | 约 50–100 页产权／法律文档包：Textract 保 OCR 和结构，再分类、分段、建目录，按类型使用字段字典及提示抽取；按字段类型评估。 | 客户与 AWS 联合叙述。正文明确是成功 POC、下一阶段进入生产实施，不能按标题宣称已完成生产部署。 |
| [ABBYY／全球快餐加盟商租约](https://www.abbyy.com/customer-stories/generative-ai-idp-extracts-data-from-lease-agreements-with-82-accuracy/) | 数百页租约先分类并分成 Preamble、Premises、Renewal Options 等条款段，再把特定段交给 GPT-4 Turbo 抽字段；置信度不足送人工复核站。 | 客户未具名，属厂商／伙伴案例。标题准确率不能搬成 ConSense 预期，更不能当空白执行表单或整本技术书的成绩。 |
| [Microsoft／EY，2026-01-14](https://www.microsoft.com/en/customers/story/25721-ey-global-services-limited-azure-document-intelligence-in-foundry-tools/) | 税务资料包通常超过 50 页、部分约 300 页；以可重复标注流程和不同自定义模型处理变化表单，案例报告当年新增 340 个生产模型。 | 具名客户的生产叙述，支持专门模型及反馈流程；没有证明一个通用提示覆盖所有文件，也未提供本项目可复算的测试分母。 |
| [ABBYY／Caisse d’Epargne Bretagne Pays de Loire](https://www.abbyy.com/customer-stories/leading-french-bank-goes-digital-with-a-document-processing-solution-based-on-abbyy-flexicapture/) | 信贷档案系统结合扫描、分类、索引、客户档案关联和关键字段抽取，公开年处理量超过 500 万页。 | 是具名已落地流程的厂商客户叙述；500 万是年度汇总吞吐，不是一份 PDF 页数，也不证明本项目逐字段准确率。 |

这些案例共同支持文档身份、分段、任务专用抽取与复核工作流。案例没有给 ConSense 的 57 字段、74 写区和 11 关系提供通过证据，也未提供可直接复用的 H800 配置。Google 旧 managed human-review API 已标记 deprecated，不能把旧托管 HITL 接口列为当前推荐实施路径；本地异常复核是独立设计选择。[Google 旧复核接口状态](https://docs.cloud.google.com/document-ai/docs/reference/rest/v1/projects.locations.processors.humanReviewConfig/reviewDocument)

## 6. 对后续选择的建议与不建议

**建议优先做的，是把来源观察与语义抽取的接口补全，并用完整分母评估。** 合同正文／原生 Word、AoA 扫描执行表、APL 空白列表、大型技术章节共享来源证据模型，但走适合各自形态的任务路线。正文先保原文及层级；空表先保条目与写区库存；语义层再给出字段类型、owner、状态及关系。提示词改写作为语义层候选，与原生结构、视觉观察和程序校验分别评分。

后续若获准实施，应先准备最小可评估契约及独立测试：同时覆盖原正确项、完整未知项、空白／填写／混合区、重复标签、跨页关系、长书失败续跑及陌生版式。按固定来源、同分母、同匹配规则比较；直接原文、规范化值、推断、人工修正各保身份。置信度不能发现所有“整个字段没出现”的漏项，因此还需库存、覆盖及结构不变量检查。

**不建议**只追加“请完整识别”的通用提示、整本 PRE／SL 送入 VLM、从空返回推定空白、把供应商百分比换成本项目门槛、用固定页坐标／gold 补结果，或为每个概念阶段增加容器。当前没有证据支持据此重训或改变已冻结模型配方。本文给出研究方向，不宣称代码、真实模型、原件质量、前端业务或目标 H800 的新增验收结果。
