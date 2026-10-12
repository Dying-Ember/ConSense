# OER 来源部署及比赛替换清单

核对日期：2026-10-09。本清单生成步骤只清点本机赛方参考包 48 个原件的来源身份、分类和部署边界，不修改原件、不加载模型。OER 实现与真实模型实验的当前进度见 [handoff](../ai_consense_service/docs/HANDOFF-ocr-embedding-rerank-20261008.md)。

实施前先读 [OER 已确认范围与执行规则](../ai_consense_service/docs/oer-scope-and-rules.md)；该文档统一维护范围、资料生命周期、SL 章节及模型调整规则。本清单展示当前原件事实，不以容器数量扩大比赛范围。

2026-10-10 最新实施顺序：用户明确当前尚不到冻结打包阶段。先完成本地算力资源／统一入口、架构修正、OER 全链代码与模型调优及必要训练，再验收并制作最终发布包。本文完整链随包／换版清单是后续交付约束；不将资源准备收据或私有测试副本当作最终包，不以发布工作替代当前链路实现和质量调优。

同日后续设备修正：主模型 GPU 优先，MinerU 辅助层可按核实的官方建议和端到端实测采用 CPU；包中须保存实际逐模块设备配方，不强求所有辅助 CUDA，也不隐瞒异常回退。中央规则“GPU 优先与辅助层设备分工”优先于历史全 CUDA 实验约束。

当前可以预先处理并按身份冻结的固定集合是 NTT、SCT、SCC 标准模板、GCC、SL2022 April 2024，以及本项目选择固定的 GCT。这六个来源是跨模块预处理的并集：三份标准模板仅供 Drafting；GCT／GCC／SL 按实际角色、版次采用和技术范围用于 Vetting／Advisory。每次新项目必须重新核对实际合同、邮件、资料目录、采用版次、排除范围及补充文件。2d 和 2e 为隔离评价资料。已读原件没有保证最终比赛只替换变量值。

2026-10-10 用户再次明确部署要求：这六类文件先在本地调好代码／模型参数并按原件验收，合格后将原始产物、规范结构、条款／图表定位、范围／角色、文档切块及向量持久化冻结；同版业务请求和服务重启复用成果。**完整 OCR／布局／表格双路及原生解析、索引构建链也必须随发布包带到超算**，用预先验证并冻结的代码应对黑客松新原件或版本变化。新字节生成新成果并核验后发布，不按同名文件套旧定位；SL 换版重新识别技术范围。每次查询的 query embedding／rerank 不属于固定文档预计算。stage 失效、现场运行与本地开发边界以中央规则的“固定版本验收后持久化，完整处理链随包发布”小节为准；该要求尚不表示全文件冻结或 H800 打包／部署完成。

来源身份和用途已核对；48 个文件的完整逐页解析、语义理解与质量验收尚未完成。本轮全文核读了 00 清单与 Q&A 六页，对全部文件做了哈希、大小、DOCX 正文标题／结构和 PDF 页树计数；PDF 文本抽取不能代替扫描、流程图或表格视觉核验。

后续 [SL／2b 布局与上传路由调查](../ai_consense_service/docs/oer-source-layout-and-routing-investigation-20261009.md) 已完成：2b 35 文件逐件调查，全部 258 原 PDF 页对象普查／缩略图目视，24 个高清原页；23 DOCX 原生普查与只读渲染，39 个代表派生页来自 17 文件。该布局调查不改变上述全文质量限制；本段数字是调查快照。整体方案已获用户认可，当前已经进入OER实施与局部真实检索／业务验证；最新成功、失败、partial及发布门槛见handoff，不能再按此前“待沟通方案”停留。固定六来源完整质量验收／冻结、目标离线包和H800部署仍未完成。

## 完整链随包的验收清单

固定成果和换版能力分别核验，当前不把调查清单当成最终release manifest。冻结包必须能在目标无网络环境恢复以下能力：

| 交付项 | 发布时必须绑定 | 换版时的运行职责 |
| --- | --- | --- |
| 已验收固定成果 | 原件SHA、版本／采用、两路raw及CAS、规范结构、条款／表组定位、scope／模块权限、切块／向量／词法索引、quality及generation | 完全匹配时直接恢复复用 |
| 原生解析及页面呈现 | POI／OOXML、PDF文字层／表单处理、renderer及字体、坐标变换源码和依赖 | 读新实际原件并保留全部原生／视觉表示 |
| 完整双视觉链 | Paddle OCR／布局／表格、MinerU完整第二路及layout／OCR／table／formula等实际所需辅助模型、processor／tokenizer、已验证权重／参数、CUDA／Linux依赖 | 对新数据运行完整已冻结配方，保存完整raw及资产，不临时从网络找缺失模型 |
| 规范采用及索引构建 | 身份路由、版次／范围识别、完整组质量门控、clause层级、切块、BGE-M3、BM25、rerank、持久发布流程 | 经核验生成新generation；SL重识别章节，不套旧页码／项目值 |
| 运维及回归入口 | 离线启动／模型阶段调度、设备及字节检查、验收样本／脚本、完整SHA manifest、回滚版本 | 单H800调度；失败保留原件／raw／unknown和旧完整成果 |

发布门槛包含：无网络首次启动、同版冷启动复用且无文档OCR／embedding重做、真实变版新处理、scope／定位及完整跨页表组核验、失败不覆盖旧完整成果、单卡模型卸载／恢复，以及目标H800兼容／显存／时间实测。当前Windows私有环境或元素级VLM探针不替代这些门槛。超算现场执行已发布代码处理新数据属于正常运行；代码／模型配方改变仍回本地验证和重新冻结。

2026-10-10 实施增量：仓库已应用六模型角色的准备入口扩展及独立文件导出工具，新增三OCR角色的必需仓库资产18/4/15项；36契约测试和10独立合成probe通过，实际37文件2,920,695,603bytes已核LFS/Git身份并导出常规文件，断开本轮源view后独立校验通过。使用说明、逐角色数字证据、准确SHA及剩余门槛见[模型准备与离线文件导出](../ai_consense_service/docs/testing/oer-offline-model-assets-validation-20261010.md)。当前实际导出只含新增三OCR角色，不是完整模型/运行发布包；Linux GPU依赖、package字典/字体/renderer、完整双parser入口、六来源质量、Windows→Linux索引身份迁移及完整generation发布/失败回滚继续单独验收。准备机下载与目标离线执行分开，不能依赖比赛现场临时下载缺失组件。

## 比赛功能范围先于文件清点

2026-10-09 用户明确确认：比赛不提供 BQ／General Summary 及 PRE 以外的 works-related PSS，直接排除；SL 的非技术章节也排除。这些内容不进入部署、生产检索或验收缺口，不要求补齐，不将其缺席标为整套资料不完整。计价文件范围同时包括 SOR。

范围依据为 [比赛需求核对记录](../ai_consense_service/docs/archive/team-update-20261004/communications-alignment.md) 中 Challenge Brief 第 4(b)/(c) 与 2(d) 节，以及 [三项功能文件范围表](../ai_consense_service/docs/archive/team-update-20261004/business-question-scope.md)；本轮用户进一步明确排除 SL 非技术章节。Brief 原始 PDF 当前不在本工作区，此处区分既有原件核读记录、本轮原始 00 清单与用户最新指示，不冒充本轮重新读取了 Brief PDF。

| 内容 | 部署及检索处理 |
| --- | --- |
| BQ／SOR／General Summary 计价文件 | 范围外；不部署、不索引为合约证据、不核算工程量或价格、不要求补齐 |
| PRE 以外的项目工程技术 PSS | 范围外；不部署、不进行技术 PSS 审查、不要求补齐 |
| SL 标准库非技术章节 | 范围外；按目录／实际章节边界排除于活动参考索引，原始文件不修改 |
| 项目 PRE | 保留；属于合约要求及适用条件的审查／咨询输入 |
| 适用的 GS／SL 技术章节 | 保留为参考依据，绑定采用版次及实际章节；不把项目技术 PSS 混入 |
| 范围内正文的 Bill 编号／名称／引用，以及 GCC／SCC 付款和价格调整机制 | 保留；处理正文参数、引用及合约机制不扩大为计价文件审查 |

48 原件、6 固定来源与 4,684 PDF 物理页是资料包及容器清点数，不代表每个文件／每个章节均为比赛审查对象。尤其 SL 原件保留完整版本作为溯源容器，活动索引仅纳入范围内技术章节；技术／非技术边界由真实目录及原文确定，不按关键词或页号猜测。

本版 SL 仅纳入物理页 1127–4300 的九技术库，共 3,174 页；1–21 作为组成／版次元数据，22–1126 的 SL PRE 整库排除。历史风险页只有 1329、3437、3438 仍在当前范围。章节区间和 SHA 见中央规则及本机 [SL scope manifest](../.local/state/sl-scope-bookmarks-20261009/sl-technical-scope-manifest.json)，其当前文件 SHA-256 为 `bb1512de4b16bfa2c10f03a8197b0c798c105e3359fc649d35391f6fcb66e3d6`；该清单是范围身份，不是全页 OCR 质量验收。换版后重新核验目录，不套用旧页码。

## 清单和数字证据

- 总计 48 个文件：2a 5、2b 35、2c 3、2d 2、2e 2、根清单 1。
- 生命周期清点：固定预处理来源 6、项目输入 38、隔离评价 4。机器清单中的历史名称为 fixed_reference；它表示准备生命周期，不表示六者均为审查／咨询参考依据，也不是赛方对最终比赛文件的保证。
- 格式：DOCX 30、PDF 18。PDF 物理页合计 4,684，其中 GCC 74、SL 4,300、GCT 2。物理页含封面／插页，不应直接与印刷条款页号相减判为缺页。DOCX 物理页未从 XML 推断。
- 完整逐文件 SHA-256、字节大小、角色依据、分组和阅读边界在 [source-inventory.json](../.local/state/oer-implementation-20261009/source-inventory.json)，可由同目录 inventory-sources.py 重新计算。
- 原件清单段落和 Q&A 页面抽取证据在 [original-evidence-extracts.json](../.local/state/oer-implementation-20261009/original-evidence-extracts.json)，只供审计／隔离评价，不是生产检索输入。

上述 source-inventory.json 的统一角色及 productionRetrievalAllowed 是调查时的粗粒度清点记录，原字节与收据保留；它们不能代替 Q9 确认后的模块准入。下表给出当前用途，NTT／SCT／SCC 标准模板不能凭 standard 标签进入 Vetting／Advisory。

## 哪些可以预部署

| 文件 | 原件业务角色 | 当前模块用途 | 处理与缓存条件 |
| --- | --- | --- | --- |
| 2a_Standard documents/01_Notes to Tenderers (NTT).docx | standard | Drafting 专用模板 | 对当前 SHA、版次、文本、结构、quality、scope 和角色绑定后预先解析／嵌入；更换任一身份则新版本。 |
| 2a_Standard documents/02_Special Conditions of Tender (SCT).docx | standard | Drafting 专用模板 | 对当前 SHA、版次、文本、结构、quality、scope 和角色绑定后预先解析／嵌入；更换任一身份则新版本。 |
| 2a_Standard documents/06_Special Conditions of Contract (SCC).docx | standard | Drafting 专用模板 | 对当前 SHA、版次、文本、结构、quality、scope 和角色绑定后预先解析／嵌入；更换任一身份则新版本。 |
| 2a_Standard documents/09_General Conditions of Contract.pdf | standard | Vetting／Advisory 按适用版次共用 | 对当前 SHA、版次、文本、结构、quality、scope 和角色绑定后预先解析／嵌入；更换任一身份则新版本。 |
| 2a_Standard documents/26_Specification Library_SL2022_042024_Batch02.pdf | standard | Vetting／Advisory 按采用范围共用技术参考 | 仅九技术库 3,174 页；完整容器不全部索引。对当前 SHA、版次、文本、结构、quality、scope 和角色绑定后预先解析／嵌入；更换任一身份则新版本。 |
| 2b_Tender documents/04 - GCT.pdf | tender | Vetting／Advisory 按本项目采用共用 | 对当前 SHA、版次、文本、结构、quality、scope 和角色绑定后预先解析／嵌入；更换任一身份则新版本。 |

GCT 虽然属于固定六来源，实际位于项目 tender 文件夹并保留 tender 角色；fixed_reference 只表示部署生命周期。2a 的 standard 也不等于本次项目自动采用全部条款。三份起草模板的解析／缓存与审查／咨询来源分开准入；2b 的实际项目 NTT／SCT／SCC 仍按项目稿处理，不按同名类别排除。固定文档向量可以赛前计算，rerank 得分仍随实际查询计算。

## 哪些随项目重新准备

2b 的项目 NTT／SCT／SCC、FoT、AoA、条件目录、附录／保函／保险／保修表式、Specification、PRE、图纸清单及图示都须使用新项目实际原件。表式中出现 Sample 或 Specimen 不使它自动成为评价答案；它们是该 tender 包中的条款或采用附件。封面和附件总标题是 composition／identity 证据，不能代替合约义务正文。

2c 三封邮件是该样板项目的事实输入，不能作为未来项目事实。00 清单也要随新资料包核对，不能用旧目录证明新项目缺了某文件。项目事实、采用版本、正文、结构、quality、来源角色或上传资料发生变化时，应重新产生相应 source/index 身份；不能将旧向量重贴为新原件。

## 答案隔离和原件支持的规则

00 清单把 2d 标为 Sample tender documents vetting comments，把 2e 标为 Questions seeking contractual advice and the answers。2e Q&A 物理第 3 页 Q1.2 明确要求 solution 通过 enquiry 获取更多背景；其中 post-contract、lump sum BQ firm quantity 和 errors in firm quantity 是为说明而采用的背景，不是对未来问题的默认事实。

2d 的意见与 countercheck、2e 的答案及 Appendix flowchart 只进入隔离评价。它们不进入生产 vector corpus、查询证据或 OCR 训练目标。该隔离是本项目已采纳的质量设计；清单本身没有写成赛方禁止检索条款。

00 清单明确写 A set of tender documents excluding Bills of Quantities and General Summary。目录中 COM、FIN、MET、PLU、TIM、WAT、DRA、EXT、SEW、PIL、ELE、FWP、LTE、GIN 列为 Not provided；这是目录项清点，不是缺失的必需比赛资料，不以 13／14 的计数差异增加任务。PRE.B 已提供并保留。APZ 印刷页 6–30 的 Not provided 只作该原件的覆盖事实记录；是否影响某条范围内判断，须有具体条款及任务依据，不据此自动判整包不完整。

版本关系需要保留：00 的 enrollment 区列出 SL2026 下载链接，而 shortlisted 实发清单是 SL2022 Batch 2 April 2024；项目 The Specification 原生 P33 采用 SL2022 updated as at Aug 2024，实际标准 PDF 第 1 页写 April 2024。三个声明不能合并成“最新标准已准备”。P46 的交叉引用有限纳入和 P50 的 PSS 替换规则仍须随本项目实际条款核对。

## 近重复与派生分组

原件 SHA 完全相同的组为 0。DOCX 五词 shingle 检查记录 8 对共享文字候选，包括 NTT／SCC 标准和项目稿，以及 S303–S306 保修附件。它证明文本相关性，不证明两个文件业务等效或已确认谁由谁派生。另将 SCT 标准／项目稿及 marking scheme 附件按明确家族分组，即使未达到相似度阈值。

新渲染页、裁图、降质增强和修订结果须继承原件 SHA 与派生链；同原件、近重复版本和同版式家族应在同一 split 中，不能随机拆页造成开发／留出泄漏。这批样板已有历史开发阅读，不能重新宣称为独立盲测。

## 与既有适配文档对照

[vetting-competition-corpus-and-ocr-adaptation-20261008.md](../ai_consense_service/docs/vetting-competition-corpus-and-ocr-adaptation-20261008.md) 中六来源文件名、原件角色、GCC／SL／GCT 页数、答案隔离和最终变动未保证的边界与当前原件一致，当前模块用途按 Q9 分开。其历史 v7 缓存命中结果不作为本轮 OCR 或语义质量；原文中先前只讨论的阶段记录也不改写为已完成实施。

## 全文件索引

SHA 列为完整 SHA-256 的前 12 位，仅用于浏览；机器清单保存全部 64 位。DOCX 页数空白表示未建立物理页计数。

| 相对文件名 | 角色 | 生命周期 | 字节 | PDF物理页 | SHA前12位 |
| --- | --- | --- | ---: | ---: | --- |
| 00_List of info CAC (after shortlist)_r1.docx | package_manifest | project_input | 55165 |  | bf632d12d9e5 |
| 2a_Standard documents/01_Notes to Tenderers (NTT).docx | standard | fixed_reference | 65893 |  | 60bd8796aa82 |
| 2a_Standard documents/02_Special Conditions of Tender (SCT).docx | standard | fixed_reference | 113490 |  | 7def01d62e07 |
| 2a_Standard documents/06_Special Conditions of Contract (SCC).docx | standard | fixed_reference | 238674 |  | 9aa2fa066836 |
| 2a_Standard documents/09_General Conditions of Contract.pdf | standard | fixed_reference | 658169 | 74 | d7d74db6d9ae |
| 2a_Standard documents/26_Specification Library_SL2022_042024_Batch02.pdf | standard | fixed_reference | 47975184 | 4300 | 6331c283da80 |
| 2b_Tender documents/02 Cover.docx | package_manifest | project_input | 27257 |  | 5ed3b1b5a322 |
| 2b_Tender documents/03 - NTT.docx | tender | project_input | 63049 |  | 0cae753f94ba |
| 2b_Tender documents/04 - GCT.pdf | tender | fixed_reference | 1865189 | 2 | ca3944c16890 |
| 2b_Tender documents/05 - SCT.docx | tender | project_input | 162974 |  | 6d7802ea7b09 |
| 2b_Tender documents/05.1(1) - SCT.A_Marking Scheme_DCMBI P04_v4_r1.docx | tender | project_input | 165777 |  | 8030a1596e3a |
| 2b_Tender documents/05.1(2) - Marking Scheme (assessment std).docx | tender | project_input | 235869 |  | 56dcdea23a87 |
| 2b_Tender documents/05.1(3) - Annex 1 to 6.pdf | tender | project_input | 4616625 | 47 | 47c4ff967d7b |
| 2b_Tender documents/05.1(4) - Appendix H - Info to be supplied by Tenderer.pdf | tender | project_input | 1905298 | 55 | 3af9dac37c6b |
| 2b_Tender documents/05.1(5) - Appendix I - Restriction to award tender.pdf | tender | project_input | 644212 | 2 | 45d0f597246c |
| 2b_Tender documents/06 - FoT (scan).pdf | tender | project_input | 4569772 | 9 | ff7f6c95285e |
| 2b_Tender documents/07 - Schedule I to FoT.docx | tender | project_input | 36432 |  | ddd452e63e5e |
| 2b_Tender documents/08 - AoA (scan).pdf | tender | project_input | 2239282 | 4 | 544defab851a |
| 2b_Tender documents/09 - CoC.docx | tender | project_input | 38255 |  | 551675ca18be |
| 2b_Tender documents/10 - SCC.docx | tender | project_input | 214154 |  | 8ba80d183f6a |
| 2b_Tender documents/11 - Appendices to SCC FLYSHEET.docx | package_manifest | project_input | 22567 |  | 86a6d89bc5dd |
| 2b_Tender documents/12 - Appendix G301 - Bond.docx | tender | project_input | 44898 |  | 6b76b6266ffd |
| 2b_Tender documents/13 - Appendix G302 - insurance.docx | tender | project_input | 64647 |  | 5047630bf82c |
| 2b_Tender documents/14 - Appendix G303 - confirmation letters for insurance.docx | tender | project_input | 31691 |  | d2e21924e041 |
| 2b_Tender documents/15 - Appendix G304 - short form arbitration rules.docx | tender | project_input | 38931 |  | 104b8863701e |
| 2b_Tender documents/16 - Appendix G305 - declaration of confidentiality, etc.docx | tender | project_input | 29833 |  | 16b4630df29b |
| 2b_Tender documents/17 - Appendix S301 - Notice of consent for XP.docx | tender | project_input | 30896 |  | 728e1cb940c1 |
| 2b_Tender documents/18 - Appendix S302 - specimen personal accident insurance.docx | tender | project_input | 40304 |  | 96565bc6954c |
| 2b_Tender documents/19 - Appendix S303 - warranty for play equipment.docx | tender | project_input | 55134 |  | 8f02445b0a21 |
| 2b_Tender documents/20 - Appendix S304 - warranty for IASM.docx | tender | project_input | 55297 |  | 2adf1a94c6a6 |
| 2b_Tender documents/21 - Appendix S305 - warranty for roofing.docx | tender | project_input | 55311 |  | 4dd6308bf7c1 |
| 2b_Tender documents/22 - Appendix S306 - warranty for waterproofing.docx | tender | project_input | 54716 |  | 2e99974ccb2f |
| 2b_Tender documents/23 - Appendix S307 to S309 - Not used Flysheet.docx | tender | project_input | 32179 |  | d50b6100b5ca |
| 2b_Tender documents/24 - The Specification.docx | tender | project_input | 29528 |  | 3fa27b8e63e5 |
| 2b_Tender documents/25 - Specification - PRE (base tender).docx | tender | project_input | 7967566 |  | a4e61064c33b |
| 2b_Tender documents/APA_Site Boundary Plan.pdf | tender | project_input | 1545511 | 8 | c352de947dcc |
| 2b_Tender documents/APB_List of Contract Dwg.pdf | tender | project_input | 25419506 | 61 | 37097f68a953 |
| 2b_Tender documents/APC_Sketches and Details.pdf | tender | project_input | 21364205 | 41 | c3a49e36da43 |
| 2b_Tender documents/APL_List of BS Subcon.pdf | tender | project_input | 260306 | 3 | 9f46ba5c3e21 |
| 2b_Tender documents/APU_List of Ref Dwg, Doc.pdf | tender | project_input | 15626099 | 20 | db804281759d |
| 2b_Tender documents/APZ_Commissioning Management Plan.pdf | tender | project_input | 896052 | 6 | 16edf9ee361f |
| 2c_Emails among Project Team/2024-12-19_from QS to Architect and Structrual Engineer.docx | project_fact | project_input | 21911 |  | 8f71e3f7ebb5 |
| 2c_Emails among Project Team/2024-12-22_from Structural Engineer to QS.docx | project_fact | project_input | 22096 |  | 8d4e008883f9 |
| 2c_Emails among Project Team/2024-12-23_from Architect to QS.docx | project_fact | project_input | 22358 |  | 0593eccd5153 |
| 2d_Tender doc vetting comments/01_Vetting Comments_20250101.pdf | evaluation_answer | evaluation_only | 269009 | 29 | f6044aeb2e4d |
| 2d_Tender doc vetting comments/02_App I - Checklist_20250101.pdf | evaluation_answer | evaluation_only | 598917 | 14 | dfdef3f92a81 |
| 2e_questions and answers/01_Contractual advice Q&A.pdf | evaluation_answer | evaluation_only | 128606 | 6 | 6df367235fa3 |
| 2e_questions and answers/02_Appendix-Flowchart.pdf | evaluation_answer | evaluation_only | 1121196 | 3 | c26aae05c033 |

最终比赛在上述已确认范围内的变量、分页、版式、文件组成及采用版次可能变化。部署接受范围内的新原件并独立识别实际内容，不把当前模板值补进新项目，也不将已排除的计价文件、非 PRE 工程技术 PSS 或 SL 非技术章节重新作为必需输入。
