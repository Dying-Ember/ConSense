# 官方 Advisory 八题与 Vetting 结构化报告：执行及评分计划

日期：2026-10-10，Asia/Shanghai。依据 [已确认范围](../oer-scope-and-rules.md)、[当前交接](../HANDOFF-ocr-embedding-rerank-20261008.md) 及本轮只读官方原件盘点。本文新增计划，没有启动模型、重建索引、修改业务代码或修改在线服务。实际问答／报告结果必须另建 frozen run，不能把计划、源码测试或本轮盘点写成业务验收完成。

2026-10-10后续状态：独立真实G305单源开发checkpoint已完成一次实际Advisory及一次Vetting，不属于下列官方八题／完整多来源评分。Vetting17次global返回空数组且17主题partial、102额外request因完整预算未知未提交，零发现不作正确率；实际JSON／DOCX／PDF收集和全页视觉检查完成。前端合法JSON报告下载故障已修复并通过实际隔离浏览器下载，旧报告三处英文／scope缺陷已做最小源码修复／59项复制checkout回归，尚未生成真实新版报告。见 [真实收集](./oer-g305-real-vetting-report-collection-20261010.md)、[全页与浏览器验证](./oer-g305-report-visual-and-json-browser-validation-20261010.md)、[说明回归](./vetting-canonical-report-coverage-20261010.md)。官方八题实际执行0、正式Vetting语义评分0、权重训练0。GCC65–70原PDF双解析／完整原件参考已经准备，native_pdf联合采用transport尚未接通，见 [GCC17准备](./oer-gcc17-original-pdf-source-preparation-20261010.md)。下文“本轮未执行”保留计划创建阶段事实；真实后续执行以独立run／receipt为准。

## 已核材料、暴露状态与当前断点

2026-10-10 08:37增量：最终兼容review已GO，Java16／Python3文件精确落主且旧bytes备份，apply receipt SHA `c55ab3eb59fb81f0d12159792f173d3fb37ee145fb864a1b346b081dccb71909`，HEAD未移动；代码应用不等于运行或答题通过。新隔离runtime／Jar准备中，不替换旧Vetting、不写在线target。Python258相关单轮全绿；Java129相关单轮＋受影响18项形成135唯一绿色阶段组合。08:37:49旧同job已到语义3／17，尚无终态报告评分。修复后新索引／Q1.6及其余完整目标仍未验，详见 [最新交接](../HANDOFF-ocr-embedding-rerank-20261008.md)。

2026-10-10 08:32:36增量：第二轮同一Vetting job仍RUNNING／semantic，2／19、语义审查2／17、sourceSetStale=false；见collector `117-vetting-poll-114-response.bin`，尚无终态报告或可评分结果。层级上下文修复的旧candidate经独立review发现window-only与普通chunks policy兼容缺口，当前新副本最小修正及Python／Java四份实际模式fixture联验进行中，不激活旧candidate。Java兼容相关11 suites／129 distinct单轮全绿（receipt SHA `56204b4770a586d059962e42d40e7a638cd5c62ac00b71a976d9f329984d655e`）；该结果仅代码回归，不代表Q1.6新实答或正式Vetting质量。完整collection global envelope的HTTP枚举／有界回取缺口也已复现，隔离修正进行中。当前来源仍局部，官方八题、全量来源、前端及H800验收未完成；最新状态见 [实施交接](../HANDOFF-ocr-embedding-rerank-20261008.md)。

2026-10-10 08:17当前增量：Q1.6首轮失败结果保留；跨whole-unit typed parent上下文已完成Python局部门控与实际原件重放，228／228纯回归通过，五文件落主但未激活。Java对等复制版本200项通过，实际policy诊断的最小聚焦回归正在完成；新recipe必须新索引，尚无修复后官方实答成绩。首次两源Vetting在原件目录配置门控失败，未进入语义模型；仅修隔离runtime storage root后明确发起第二轮job `97ca407a-1fa3-48b0-a1d2-bc884e7574ab`。08:16:18collector最新raw状态为RUNNING／semantic、1／19，准备17检索主题，尚未收到最终报告或正式评分。该任务进度不是质量分数，当前来源仍只含GCC65–70和SCC17相关已采用局部；不能泛化为整件合同。见 [最新交接](../HANDOFF-ocr-embedding-rerank-20261008.md)、[Python层级重放](./oer-typed-parent-hierarchy-context-source-replay-20261010.md) 与 [首次Vetting失败](./oer-official-two-source-vetting-first-failure-20261010.md)。下方时间戳更早的状态均保留其历史语境。

2026-10-10 07:57当前增量：Q1.6首轮已实际收完并独立评分，HTTP200不作题目通过；answer为空、grounded=false、basisScope=insufficient、流程图unsupported，30正文要求未回答。source／index前后身份相等，两源代表pointer实际回取材料差异0。定位到准备runner显式24／3的预算及跨whole-unit typed parent未被消费的问题，尚无正式默认50／10或新闭包真实答复成绩。重复复验的最小性能Jar受控切换执行中，标题正文上下文在隔离Python／Java复制checkout验证；首次本两源Vetting job仍未发起。后续以各轮实际收据为准，详见 [首轮业务结果](./oer-official-q16-first-business-result-20261010.md)。下方07:32及更早“pending／实际执行0”等均为当时状态，不代表当前尚未执行首题。

2026-10-10 07:32当前增量优先于下方准备阶段：实际项目GCC id1／项目SCC id2已持久登记、fresh Jar重开、两源cold pointer回取和真实BGE索引；仅采用GCC65–70六页50 units＋SCC17相关22 units，非完整合同。第一道Q1.6 POST已发起，retrieve身份／结构保真通过但实际只返回两个GCC标题及一个SCC单元，GCC17.1正文子条未闭包。M3日志已返回缺证`grounded=false`，HTTP答复仍等回答后门控，尚无冻结业务结果或评分。Vetting仅完成运行前来源manifest，job尚未启动。实际证据与限制见 [当前交接](../HANDOFF-ocr-embedding-rerank-20261008.md) 和 [Q1.6实际检索审计](./oer-official-q16-actual-retrieval-readonly-validation-20261010.md)；不把历史“实际执行0”解释为尚未发起本次题目。

2026-10-10实施增量：native PDF observation seam已冻结，Java14 tests／独立Python27 focused tests均通过，另11隔离CAS篡改拒绝；实际GCC65–70／authored正常与损坏AP分别冷读两次。当前完整原件／glyph／AP字节保全不是正文采用或质量评分，effective raster与glyph/form operators独立重放仍未验。joint projection、bounded PDF public wire和独立registry／fakeHTTP已冻结，最终authored public43,123bytes；root23项transport／registry／fakeHTTP独立复验通过，Java独立声明contract25/25通过。实际GCC joint adoption与正式Java消费者接线正在进行，尚未在实际业务项目登记／检索；其余必要SCC／PRE／SCT及多来源闭包仍要完成。因而0道官方实答／0份正式多来源评分保持，不能用契约测试追认样题成绩。依据见 [native capture记录](./oer-native-pdf-observation-capture-20261010.md)、[public transport](./oer-native-pdf-public-transport-20261010.md)及handoff最新增量。

独立只读coverage核查确认：G305是当前已采用／持久登记／真实BGE及M3的单源；SCC raw39,918,459bytes、SCT raw44,840,258bytes、PRE raw418,855,750bytes只是原生观察保全，不能当完整语义采用。最接近的官方首批为Q1.6，仍需whole SCC17.301有效编辑状态及实际采用／依赖、GCC17实际采用、同registry真实DB ID与M3链路；新flowchart交付仍单独未实现。其他题按下表补必要完整来源，不能把六页GCC17泛化为全部GCC。

先前Python PDF与DOCX collection registries并列。现已新增共同registry／CAS与统一入口源码，scan、DOCX single／collection及native PDF的105次离线执行全绿，88个唯一tests，其中17项mixed；见 [统一入口验证](./oer-unified-native-source-registry-20261010.md)。8874未启动，fake ASGI／向量通过不代表真实mixed项目／模型验收。Java snapshot／batch／asset仍需同一准确backend base，按module的retrieval process仍须matching targetModule；改URL不会迁移来源或建立采用。Q1.6先用同PDF-capable registry的GCC＋小SCC single snapshot验证，完整八题／正式多来源Vetting前再完成实际mixed runtime及全部必要采用。

真实GCC8873 BGE index和两次source-only query已完成，均CPU FP32；试验性必要集全部进入top8，尚无官方问题／M3调用。实际GCC＋SCC Java上传在fresh隔离H2暴露大字符串持久化溢出；保持全原文，单因素显式CLOB对照已成功上传并checkpoint，正常重开读回及产品修复回归待完成。GCC的第1页无OCR仍使整源PARTIAL，65–70实际采用范围必须独立核，不把PARTIAL改为PARSED。正式输入预算与metadata开销继续按 [完整输入核查](./oer-minimax-input-budget-and-provenance-review-20261010.md)独立解决，不把字符优化当exact M3 token observer；v3请求级无损metadata共享已做63项回归，但尚未在真实M3新请求验收。

本机盘点位于根目录忽略目录 `.local/state/oer-official-acceptance-inventory-20261010/`：

H2最小产品修复及9 suites／58 tests回归已完成，旧源码red保留；H2实际CLOB／完整Unicode落盘重开／MySQL57 LONGTEXT生成分别验证。实际同原件fresh修复Jar／fresh DB未预ALTER的上传／重开仍在准备中；见 [LOB故障与验证](./oer-native-evidence-lob-persistence-20261010.md)。这些测试不增加官方题执行数或正式报告评分数。

| 文件 | 内容 | SHA-256 |
| --- | --- | --- |
| `acceptance-inventory.json` | 原件路径／SHA／页数、角色、来源隔离与评价入口 | `2576445ee5232af9a297b26d4221f28b69eb0d1d30826be4dbe59c13e1db9c03` |
| `questions-only.json` | 八个实际 Q: 段的 raw native text 与单独视觉转录问题；不含 A: 段 | `4c4c8b53c235d320b881009c982796a78d5edd2e72aaa712eade95fd53372009` |
| `vetting-report-requirements.json` | 格式／续页／状态／范围／来源评价检查项 | `275f3f9d8d6317206e60f01fe5aa240ddfd1d04d78a3269acfffb5188a59867d` |
| `acceptance-inventory.md` | 可读盘点、逐题入口及未完成项 | `6e81f4021927e5d979a5da9aa788ebcc1abeea942f989de528bccbba566c9137` |

共同原件根目录为 `Ref/2026-09-04_Documents to Shortlisted Candidates (Contract Advisory)/`。四份参考文件身份如下；不得据页数推算题数或发现数量。

| 原件相对路径 | 物理页数 | 实际原件 SHA-256 |
| --- | ---: | --- |
| `2e_questions and answers/01_Contractual advice Q&A.pdf` | 6 | `6df367235fa35d43d5febc5e36e99ba32519b68802881124a7028626e30efb91` |
| `2e_questions and answers/02_Appendix-Flowchart.pdf` | 3 | `c26aae05c033faff74bcdcda162cadd9e100f2471c50f5ea2f7a8f8a4be00d71` |
| `2d_Tender doc vetting comments/01_Vetting Comments_20250101.pdf` | 29 | `f6044aeb2e4d6b572155b315708615f3f7a711880052d18c1b61e7b85c181f2d` |
| `2d_Tender doc vetting comments/02_App I - Checklist_20250101.pdf` | 14 | `dfdef3f92a810e9c88ebf20f260d2d1a292d778689421c714b0d05d68e60c86c` |

实际正文唯一题号为 **1.1–1.6、2.1–2.2，共 8 题**，其中 Simple 6、Situational 2。目录重复不重计；2.2 的两个子请求仍是一题；三页 flowchart 属于 1.6 的参考输出，不是三道新题。本轮全部六个 Q&A 原页和三页 flowchart 已目视，2d 意见原页1／2／29、countercheck原页1／13／14已目视，其余报告页只作 native text 提取。尚未逐断言标注整份意见的正确性和范围分母。

执行者在盘点中已经读取题干、参考答案和参考图；源材料此前也用于开发讨论。登记为 **development_historically_exposed**，不能称 blind／unseen。视觉转录只修复读取题干所需的字体映射表达，raw native text 另存；不改原件或 canonical 证据文本。

2d／2e 的答案文字、解释、图和由答案制作的必要证据标签只供独立评分，不进入同题 OCR 监督、producer 输入、生产索引、检索、模型 prompt、历史示例或问题背景。生成侧只接收 question-only、范围内实际项目原件及用户实际提供的澄清。下文必要来源与证据条件是**评价侧检查表**，不得追加到模型题干中以提示答案。

当前独立 18081／8869 的既有项目只登记 GCT page1 的局部 reviewed canonical；一次 booklet photocopy 问答见 [真实 Advisory bridge](./oer-actual-advisory-canonical-bridge-validation-20261010.md)。它不属于官方八题，亦不具备八题所需的项目资料覆盖。官方题实际运行数仍为0，官方 Vetting 报告未执行。下面的命令是准备满足后可执行的入口，本轮没有执行其中 POST。

## 先建立实际项目来源，再冻结官方批次

按实际原件准备独立持久项目，不使用演示占位记录或上一项目的采用值。原件上传、原生／扫描解析、来源身份与采用、必要范围的 reference／canonical 采用、实际 DB ID 重绑定／registerReviewed 分别记录。当前 canonical controller 仅提供只读 snapshot／pointer／asset，不存在通用 HTTP 写入注册入口；采用登记继续使用已核验的内部接缝，不为验收开放伪造登记 API。

以下文件是本样板中需要准备的主要范围内来源，已在本轮重新核实际原字节 SHA；列出文件存在不代表其全文解析、条款适用或语义质量已通过：

| 角色 | 实际样板文件 | SHA-256 |
| --- | --- | --- |
| 适用标准 GCC | `2a_Standard documents/09_General Conditions of Contract.pdf` | `d7d74db6d9aee390093b9e22bb30dccdbf3c0316d47635922109422278d161e7` |
| 项目 SCC | `2b_Tender documents/10 - SCC.docx` | `8ba80d183f6adf7a11eeb08f75e32c2bffdc3960f969202fcbed357e477f7af3` |
| 项目 PRE | `2b_Tender documents/25 - Specification - PRE (base tender).docx` | `a4e61064c33ba2b67c5192bfa7756c1b792dbb74ffda32aea84f2b96509dc6ea` |
| 项目 SCT | `2b_Tender documents/05 - SCT.docx` | `6d7802ea7b094124c0ddebb0b448c7e6bfe72828ffbc91ad1c30133902a6f64c` |
| 项目声明附录 | `2b_Tender documents/16 - Appendix G305 - declaration of confidentiality, etc.docx` | `16b4630df29b7aa76e8511b42f12359c8d16514932e60046e25f1c49e167c57e` |

按题目及实际条款依赖继续纳入本项目 FoT、AoA、实际附录、Specification、2c 邮件／纪要或合法相关图纸／采用资料。它们需要自己的 source SHA、actual DB ID、role、source revision 和完整必要单元，不能只因同目录即假定合同采用。技术参考只纳入实际适用 GS／SL 技术库；SL 当前原版物理页1127–4300，换版重建范围清单。2a 的 NTT／SCT／SCC **标准模板始终 Drafting-only**；上述2b实际项目版本另行路由。

BQ／SOR／General Summary、非 PRE works-related PSS、SL 非技术库仍排除。题干或范围内正文提及 BQ、Bill 或付款／错误调整机制，不扩大输入集合。不存在的范围外资料不算 parser 缺口或全局不完整；存在具体范围内依赖但缺据时，局部 unknown／澄清。

每批冻结资料／问题／评价标签和模型配方；runtime manifest至少记录：

- 有序 selected catalogue，actual documentId、originalSourceSha256、javaParsedSourceHash、projectionSha256／revision、reference／adoption／scope policy SHA、role／purpose／eligible module；不靠文件名或 fileKey 合并两个上传身份。
- 独立 Java／对应 module Python 的 URL、源码／依赖／模型 revision、设备／dtype、窗口与完整结构预算、BGE／BM25／RRF／rerank 配方和 index signature。
- 当前 modelIdentity、配置 SHA、任务 prompt 版本／SHA、实际请求／响应／journal。凭配置名称或服务健康不能算实际 M3调用。
- question-only SHA、独立 evaluator 标签 SHA、暴露登记。评价标签只能在生成结束后由单独离线阶段读取。
- 新建 run directory，不覆盖旧成功、失败或中断证据。密钥只在受控服务环境，不记录 Authorization 值。

## 八题的必要来源、输出及证据条件

此表先定义文档家族和证据闭包；精确 required unit IDs／必要证据集必须由范围内原件标注完成后绑定 frozen source revision，不能把样板答案列出的条款直接当作已验证 gold，亦不能只看命中相同 clause token。被引正文的父条、实际修改、条件／例外、附录和采用状态均属于评价范围。

| 题号／原物理页 | 必要范围内来源 | 输出类型 | 评价侧必须核的证据条件 |
| --- | --- | --- | --- |
| 1.1／2 | 本项目采用 GCC；项目 SCC 的付款修改；项目 PRE 的材料／申请要求；实际 G305及与付款资格相关的已采用附录／条件 | 可定位的分项申请资料清单，并区分不同收件角色、时间与资格条件 | 对照实际采用的 standard＋project override，完整核角色、时间、声明／附录、条件及例外；缺条件不得列为全合同完整清单。声明中的其他示例取值不自动成为事实。 |
| 1.2／3 | 采用 GCC 与项目 SCC 中文件义务、错误修正、计量／估值／调整机制；本项目合同类型／数量性质的实际声明或用户澄清 | 合同机制说明；背景不足时先提出具体澄清，再按实际背景分情况回答 | 先核发生阶段、合同类型、错误性质和谁填入相关项目等必要事实；不得偷偷采用2e答案为说明而假设的背景。区分机制条件、适用分支与风险承担，不开展BQ算量、报价或计价文件审计。 |
| 1.3／3 | 采用 GCC、项目 SCC 的优先顺序／技术提案例外；实际纳入的图纸／Specification或合同组成声明 | 对冲突、优先顺序与更严格要求的条件性分析 | 同时检出一般顺序与实际例外，不把某个技术提案例外扩为所有drawings／specifications都自动适用；区别一般规则咨询和对未提供的具体冲突作事实判断。 |
| 1.4／4 | 采用 GCC、项目 SCC 中变更权力、工期／通知／EOT；实际完工安排仅在需要具体应用时纳入 | 分别答“能否发Variation”和“能否／如何主张EOT”，含必要条件 | 覆盖变更的必要性／权力、通知程序、资格条件和决定角色；主张权、通知权、获得EOT不合并为同一个无条件结论。题干情境是用户背景，不冒充原件事实。 |
| 1.5／4 | 采用 GCC、项目 SCC 的组成／技术提案／优先与歧义处理；项目 SCT 的提案纳入；PREprogramme要求及实际纳入／接受证据 | 合同要求、风险及处理建议分开；缺实际纳入背景时明确条件 | 条款完整检索与programme实际身份分别验证。建议不得改变既有合同权利或冒称有单方删除权；未提供的programme不要求模型编造内容。完整评价覆盖相关条件，不只复述一条优先规则。 |
| 1.6／5 | 实际采用 GCC17.1全文、相关项目 SCC修改／采用的争议机制；实际版本及其所有条件／期间 | **生成新的flowchart**，并保留其节点／分支与原条文证据映射 | 不能返回2e答案图代替生成。依据实际采用机制核所有节点、条件、分支、期间、终点和跨页完整性；拓扑关系与图渲染另验，见下节。 |
| 2.1／6 | 采用 GCC／项目 SCC 中运输责任、成本／工期机制；项目 PRE有关运输、交付或方法约束；存在时本项目运输安排／指令／事实 | 对额外运输成本与可能的其他请求分别作合约分析 | 区分题干实际给定情境与合同是否规定运输序列／方法／时间。搜索未命中不能证明合同没有相关规定；没有完成相关原件覆盖时标不足。情境条件、不同请求和程序各有证据，不挪用参考答案的额外事实。 |
| 2.2／6 | 项目 PRE 的具体Tree Risk Assessment采用文字；项目合约成立／招标时点与版次；采用 GCC／项目 SCC 的指令／变更机制；存在时实际更新通知 | 同一题内分别答SI是否必要及费用归属，含时点／版次条件 | 准确保留“latest”的合同语境；题干的9th／10th是e.g.情境，按背景／条件记录，不能从字面创建真实版次事实。原条、实际采用和更新后要求分别核；未提供外部指南全文时不编造新增工作内容。 |

1.2 等澄清题按同一 questionId 记录 initial turn及必要follow-up，不新增题数。第一次正确澄清可通过“背景处理”维度；没有足够背景的最终合同结论不能算已经完成。同题后续事实应来自用户实际回复或另行明示的评价场景，不从2e答案复制进prompt。第一轮所有题的 `replyToMessageId=null`，当前 AdviceService 在这个分支使用独立 QuestionContext，不自动把其他历史回答加入本题；澄清回复才显式绑定同题 assistant message ID。

## Q1.6 新图生成与 Q10 原图回取分别验收

Q10=A验证检索到正确原件图／表、标题、所属父条、原页与完整表组，并回取真实asset。它不验证模型读懂箭头、不生成新图，也不能让隔离答案图变成生产原件。

官方1.6要求由实际合同条款**生成flowchart**，至少增加以下可观察结果：模型生成的结构化节点／边或等价图描述；每个节点／条件／期间对应实际证据；实际采用机制及备选路径正确；完整渲染和可检视／导出的新图。render成功只证明语法／布局，不证明法律拓扑正确；文字总结通过也不代表图输出通过。

当前读取的 `AdviceService.AnswerRecord`没有flowchart专用结构，`AdviceView.vue`以文本段落展示 `message.content`，未发现该入口的Mermaid／图渲染。模型即使把Mermaid放进answer字符串，也不能直接报告前端flowchart验收通过。以本计划记录**能力缺口／未实现**，不能把该题静默从8题总数删除、冒称正确、或把2e参考图作为产物。后续实现该能力时须记录最小接口／渲染设计和真实生成图验证，保持Q10原件回取的既有边界；本文件不修改代码或承诺当前已有支持。

## 实际执行入口及现有脚本边界

后续已实现显式官方业务收集器 [official_business_capture.py](../../tools/vetting_eval/official_business_capture.py)，使用 [冻结来源清单及说明](./oer-official-business-capture-20261010.md)。20项in-memory transport契约通过，collector SHA `f86a8e7e4450af80dd7a7f9e6462d51f8db0dbe71a0e7291fc4663dc82339191`；这不计真实HTTP或官方题执行。收集器要求至少两个actual来源的完整live ID集合／文件名／Java parsed hash及canonical indexKey／signature，未知离线provenance不冒称live核过；仅question-only用于生成，原bytes／失败／modelIdentity及Q1.6当前graph unsupported分别保存。Vetting保留零finding／partial，latest-only导出前后核同job／source／corpus；服务器没有原子run-specific导出协议。它不上传／采用／rebuild、不写人审状态、不自动重试、不读取答案标签，不把collected_unscored称质量通过。

实际fixed源码／freshH2无手工ALTER的GCC＋SCC上传、正常关闭、重开完整字段一致已完成，真实ids1／2正在内部重新采用登记。详见 [实际LOB故障重跑](./oer-native-evidence-lob-persistence-20261010.md)。首次Q1.6仍须等这两个实际binding及Java→BGE index current可用再执行。

已读当前入口与相关源码：`AdviceController`／`VettingController`、`AdviceDtos`／`VettingDtos`、`AdviceService.clarificationContext`、`VettingReportPresentation`／`VettingReportWriter`；以及 `tools/vetting_eval/{integration.py,eval.py,foundation_scope_evaluation.py,foundation_retrieval_audit.py,fresh_client_readback.py}`。

- `eval.py`是解析／检索pilot，没有生成正式咨询答案或审查裁定；旧README的3B、8GB、端口与老预算按历史语境读取，不能用作当前执行配置。
- `integration.py`可真实上传、启动Vetting、读取job／findings／exports，再离线prepare review matrix；其 `load_documents`拒绝2d／2e，absence of finding不当负例通过。当前脚本不发送`X-ConSense-Llm-Profile`，且不做canonical内部采用登记，所以不能直接声称它已是八题M3或canonical官方验收runner。`--review-existing`仍会启动模型，`--resume-completed`只核已有job；`--skip-status-check`避免它的合成Handled状态写入。不能拿它绕过本计划source绑定和actual profile门槛。
- `foundation_scope_evaluation.py`、`foundation_retrieval_audit.py`和`fresh_client_readback.py`可复核绑定SHA的保存结果，但不是八题法律评分器／真实新LLM调用。`direct_api_minimax_material_control.py`调用直接API，不能替代实际 AdviceService／Vetting harness的业务验收。
- 实际执行优先直接调用明确选中的隔离Java业务HTTP，M3由该服务的既有私有中继配置提供。collector只能读question-only与actual runtime manifest，offline grader生成结束后才读2d／2e及标签；二者不共用读取答案的函数或进程环境路径。

### 可执行的 Advisory 收集段

下面保存为本机ignored run中的脚本后，参数全部显式传入。**本轮未执行**。先准备实际项目和完整source freeze，再单独完成当前scope的rebuild；本段不上传、不登记、不重建索引、不清空历史。`ExpectedSourcesPath`是本批准备时的JSON清单，含 `documents:[{documentId,fileName,javaParsedSourceHash}]`，对应实际selected fullset；其SHA也要冻结。更详细binding／raw SHA等保存在同批runtime manifest。

```powershell
param(
  [Parameter(Mandatory=$true)][string]$BaseUrl,
  [Parameter(Mandatory=$true)][string]$ProjectId,
  [Parameter(Mandatory=$true)][string]$QuestionsPath,
  [Parameter(Mandatory=$true)][string]$QuestionsSha256,
  [Parameter(Mandatory=$true)][string]$ExpectedSourcesPath,
  [Parameter(Mandatory=$true)][string]$ExpectedSourcesSha256,
  [Parameter(Mandatory=$true)][string]$RunOut,
  [switch]$RunActualLlm
)
$ErrorActionPreference='Stop'
if(-not $RunActualLlm){throw 'This collection performs actual LLM calls; pass the explicit execution switch only after the frozen corpus is ready.'}
$uri=[uri]$BaseUrl
if($uri.Host -notin @('127.0.0.1','localhost') -or $uri.Port -eq 8080){throw 'Use the explicit isolated localhost Java service.'}
if($ProjectId -notmatch '^[A-Za-z0-9_-]+$' -or $ProjectId -match '^demo'){throw 'Use the actual isolated acceptance project.'}
function Read-FrozenJson($path,$expected){
  if((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected.ToLowerInvariant()){throw 'Frozen file SHA mismatch'}
  Get-Content -LiteralPath $path -Raw -Encoding utf8 | ConvertFrom-Json
}
$questions=Read-FrozenJson $QuestionsPath $QuestionsSha256
$expectedSources=Read-FrozenJson $ExpectedSourcesPath $ExpectedSourcesSha256
if($questions.answerTextIncluded -ne $false -or $questions.uniqueTopLevelQuestions -ne 8 -or $questions.questions.Count -ne 8){throw 'Question-only manifest invalid'}
if(@($questions.questions.questionId | Sort-Object -Unique).Count -ne 8){throw 'Question IDs are not unique'}
$headers=@{'X-ConSense-Llm-Profile'='minimax-cn'}
$api=$BaseUrl.TrimEnd('/')+'/api/advice/'+$ProjectId
$sources=Invoke-RestMethod -Uri ($api+'/sources?scope=fullset') -Headers $headers
$status=Invoke-RestMethod -Uri ($api+'/index/status?scope=fullset') -Headers $headers
if($sources.code -ne 0 -or $status.code -ne 0 -or -not $status.data.vectorReady -or $status.data.stale -or $status.data.evidenceBackend -ne 'canonical'){throw 'Current canonical scope is not ready; do not call the official batch.'}
$included=@($sources.data | Where-Object included)
if($included.Count -ne $expectedSources.documents.Count -or $included.Count -lt 2){throw 'Selected source catalogue differs or is still a one-file pilot'}
foreach($doc in $expectedSources.documents){
  $live=@($included | Where-Object {$_.documentId -eq [string]$doc.documentId})
  if($live.Count -ne 1 -or $live[0].fileName -ne $doc.fileName -or $live[0].sourceHash -ne $doc.javaParsedSourceHash){throw 'Current selected source identity differs'}
}
if(Test-Path -LiteralPath $RunOut){throw 'Use a new output directory; preserve earlier runs'}
New-Item -ItemType Directory -Path $RunOut | Out-Null
$sources | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath (Join-Path $RunOut 'sources-before.json') -Encoding utf8
$status | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath (Join-Path $RunOut 'status-before.json') -Encoding utf8
foreach($q in $questions.questions){
  $body=@{question=$q.queryTextVisualTranscription;scope='fullset';replyToMessageId=$null} | ConvertTo-Json -Compress
  $prefix=Join-Path $RunOut ('q-'+$q.questionId)
  $body | Set-Content -LiteralPath ($prefix+'-request.json') -Encoding utf8
  try{
    # Byte[] preserves UTF-8 question bytes; response Content and identity headers remain raw.
    $response=Invoke-WebRequest -Uri ($api+'/ask') -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 2100
    $response.Content | Set-Content -LiteralPath ($prefix+'-response.json') -Encoding utf8
    $envelope=$response.Content | ConvertFrom-Json
    if($envelope.code -ne 0 -or $envelope.data.modelIdentity.profileId -ne 'minimax-cn' -or $envelope.data.modelIdentity.model -ne 'MiniMax-M3'){throw 'Actual business/model identity mismatch; inspect saved response'}
    # Semantic correctness, clarifications, references and Q1.6 graph remain offline scoring gates.
  }catch{
    # Save a safe typed failure; preserve actual HTTP failure body in the collector's private evidence log.
    @{questionId=$q.questionId;terminalStatus='failed';failureClass=$_.Exception.GetType().FullName} | ConvertTo-Json | Set-Content -LiteralPath ($prefix+'-failure.json') -Encoding utf8
    throw
  }
}
Invoke-RestMethod -Uri ($api+'/messages') -Headers $headers | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath (Join-Path $RunOut 'history-after.json') -Encoding utf8
```

本段只收集初始turn，不能把返回200／modelIdentity正确当答对。实际collector还需为每次请求记录UTC始末、实际status／安全headers／原response bytes及SHA，保留非200／业务code失败、transport journal、当次index signature、提交完整单元和token预算；每个citation实际回取pointer／asset并核SHA／bytes／当前来源。上面的PowerShell JSON深度100只是保存值的最小入口，不能替代raw transport log或完整payload校验。中断保留已完成题及失败题，不换budget/profile后接着拼成“一轮”。需要重试时创建新的 run／attempt。

### 可执行的 Vetting 入口

在另一个已登记当前完整scope的隔离测试project，使用 `X-ConSense-Llm-Profile: minimax-cn` 执行：

1. `GET /api/vetting/{projectId}/files`，核actual来源、scope／parse及runtime manifest；确认对应Python target=vetting，而非将8869 advisory进程改名复用。
2. `POST /api/vetting/{projectId}/runs?lang=en`，保存actual request／response和returned job ID。该操作会真实调用模型，本轮未执行；collector须有显式execution switch和新run output。
3. 按returned ID轮询 `GET /api/vetting/{projectId}/runs/{id}`，每次状态变化保存原response及UTC；FAILED／CANCELLED／ERROR终止并保留。COMPLETED也要核modelIdentity、sourceSnapshotSha256、canonicalCorpusIdentitySha256、sourceSetStale与packet ledger。
4. `GET .../findings`、`GET .../metrics`，逐finding `GET .../findings/{code}/evidence`；按实际canonical pointer调用 `/api/evidence/{projectId}/documents/{documentId}/canonical/{projectionSha256}/units/{unitId}?module=vetting`及对应asset。实际scope、原件／parsed／projection身份及完整表组必须一致。
5. 分别下载 `GET .../report.json?lang=en`、`.../report.docx?lang=en`、`.../report.pdf?lang=en` 的原bytes／safe headers／SHA。不得在baseline写人审status／review／checklist确认或触发合成Handled更新。report必须绑定刚完成的job；期间若有新job／scope变更，不把latest结果混进旧run。
6. 生成结束后，离线读取隔离2d及范围过滤标签，做下述评分。native text／render检查不调用LLM；语义评价要有逐项原页证据及unknown，不能只做string needle matching。

这是当前可调用业务入口。计划不会以旧integration直接API、model stub、mock或Qdrant命中冒充已跑完整Vetting。源catalog／canonical注册和module配置尚不足时，记录precondition_failed，先完成准备，不造source、test label或pointer以过检。

## Advisory 分层评分与每题收据

先通过来源／范围／真实调用门槛，再逐维度保存 **pass／fail／unknown／not_executed**，不要把未执行计成正确，也不以单个总分遮蔽引用或能力失败。

| 维度 | 可观察结果 | 失败／未知处理 |
| --- | --- | --- |
| E：必要证据 | frozen required evidence set的必要单元／父条／修改／条件／例外完整；source pointer可回取实际原件和完整跨页表组 | 未标注required set时unknown；漏召回和重排损失分别归因；扩大closure不增加rerank origins |
| A：答案内容 | 回答各subrequest，合同条件／可能性／角色／期间忠实；没有样板假设或外部事实偷渡 | 逐claim对原文核证，正反／例外遗漏明确fail；source不足且诚实unknown另记，不算错误硬答 |
| C：背景处理 | 需要时澄清；user背景与合同证据分开；samecase follow-up保留源身份 | 正确澄清可通过本维度，最终答案完成度继续pending；不能以2e背景补齐 |
| P：引用与历史 | answer引用ID指向同次sources；current pointer／asset／原bytes有效；history完整对象未丢失 | 错来源、错版、旧地址重链为fail；正确返回stale拒绝属于版本门控通过，不是引用可用通过 |
| O：交付输出 | 内容可读且当前前端支持；1.6新flowchart语法、实际图、拓扑／证据映射分别通过 | 普通text答复不算1.6flowchart完成；能力未实现为not_implemented，仍保留在8题状态表 |
| T：实际调用与资源 | 实际BGE／rerank／M3、端到端耗时、token／finish reason／重试／截断、设备与dtype记录 | HTTP529／timeout／OOM／truncation单列运行失败，不归结为推理结论错误或悄悄剔除 |

每题 `questionId` 收据建议包含 `split, questionSha256, sourceCatalogueSha256, projection/index identities, modelIdentity, configurationSha256, promptSha256, request/response descriptors, actualCallLedger, timings, submitted/returned sourceUnit IDs, citations/pointer/asset checks, claimAssessments, clarificationState, outputKindChecks, perAxisStatus, terminalStatus`。原bytes与claim评价分开保存；`humanConfirmed=false`，评分者角色与已暴露状态写明。总表报告8题每题状态、各维度已评／pass／fail／unknown数，以及真实请求次数；不得把同题follow-up或重试加入题数分母。

## Vetting 报告分层评分

当前export JSON明确输出 `schemaVersion, language, project, job, findings, reportStructureVersion, reportLanguage, commentsTables, checklistTables, checklistResults, evidenceIndex`，结构版本为 `competition-comments-and-checklist-v1`，当前report presentation使用英语。请求lang与实际reportLanguage分别记录；不假定中文请求已经有中文报告。

| 层 | 评价字段／行为 | 门槛 |
| --- | --- | --- |
| V0 来源与运行 | project身份；job.id／status／modelIdentity；sourceSnapshotSha256；coverage.canonicalCorpusIdentitySha256；当前sources与stale；actual gateway／packet ledger | 完成／当前身份有效；模型未调用、task局部failed、coverage未知不能被COMPLETED掩盖。结构数据源、真实模型行为和质量分别报告。 |
| V1 可复算结构 | findings的code／fingerprint／runId／types／group／scope／severity／source／verification；title／body／impact／suggestion；refs／location／fileKey／pageNo／evidence | JSON类型、稳定finding身份及分组正确；统计metrics与实际active findings复算一致；unknown／缺定位不伪装verified。 |
| V2 编号与格式 | 合约identity→文件组→编号意见；commentsTables的Page／Clause-Item／Comments及可选Project Team remarks；evidenceIndex连接展示编号与finding code | 同一次run的JSON／DOCX／PDF字段和编号一致。样板1.3跨页续文应保留同一意见；实际长意见分页不截段、不重复计数，页眉／表头与继续标记正确。 |
| V3 状态与范围 | No comment／Not used／空白／未检查／缺证分别表达；checklist明确source及manual result；按当前范围过滤断言 | “零发现”不证明整源No comment；空备注不证明已countercheck，既有用户manual review不被baseline写入或重置。BQ／worksPSS／SL非技术断言不纳入必做项。 |
| V4 原文与地址 | FindingEvidence的side／documentId／sourceHash／quote／anchor／page／bbox／located；canonicalEvidence的完整source／projection／parts／spans／relations／资产／unknown | actual pointer／asset可用、原bytesSHA与headers正确；quote真来自相应原文位置，crossfile两端有证据。native DOCX位置不用伪物理页；PDF物理页与印刷页区别保留。 |
| V5 合同发现质量 | 九类审查任务逐项：问题、分类、冲突／例外、依据、影响与处理建议；false positive／miss／unknown分开 | 参考2d只是隔离比较；reference建议不自动权威，必须回实际2b／采用标准核证。没发现不当negative pass；引用正确也不证明合同裁定正确。 |
| V6 导出与浏览器 | JSON完整字段；DOCX原生表格／续页；PDF actual render；实际前端打开发现→证据→原图／完整表组；Q11入口仅editable草稿 | raw export成功、文字层完整、视觉可读、UI真实地址分别检查。下载HTTP200或组件tests不能代替实际浏览器。 |

意见样板的1／2／29页证明编号、续文、No comment与可选备注；No comment应由具体完成的检查范围支持，不是模型没有返回finding就写。`VettingReportPresentation`当前零发现会明写未记录发现不代表文件无问题，验收保留这一区别。

countercheck原页12–13属计价检查；原页14虽标题PRE.B12但动作含BQ计量／费率／Bill1 attendance，应按断言过滤。前11页也不能不经范围／来源核对整页作为production要求；2d本身始终不进入检索。部分countercheck页头为Contract No.20240539／26 February2025，与首页20250101分别保留参考身份，不能复制成测试项目事实。

尚未逐断言核准2d reference finding分母，不把末尾7.61当61个全报告缺陷、不把跨页重复行或子comment算新finding。先给参考断言绑定current scope、实际原文、类别和expected basis，再统计可评价分母、accepted／false positive／miss／unknown；格式层可先独立执行，不冒充语义层已通过。

## 必做的版本变化与退出验证

基线结果保存后，在**另一个受控隔离副本**做新增来源、parsed revision变化、原件字节变化、采用／projection变化等门控；记录旧history／report引用应保留原身份、旧地址应明确失效且不自动重链。失败问答不保存半条历史，changed-scope运行不保存当前有效报告。不要在正式评分项目边跑模型边改源，也不把这些负例混入8题正确率。

负例恢复放finally，保持旧bytes／DB备份，实际fresh读取后执行持久checkpoint／正常关闭并独立重开验证。旧bridge已暴露H2内存恢复通过但落盘仍负例，见 [真实bridge修复记录](./oer-actual-advisory-canonical-bridge-validation-20261010.md)；该经验不能用terminal test pass代替持久服务验收。随后真实HTTP、实际浏览器和必要的真正MySQL迁移／并发分别验收。

最终交付为：frozen runtime／corpus／问题与评价标签 manifest、8题逐题actual answers及分维度证据、真实Vetting job／完整三种报告与评分、实际pointer／assets／浏览器证据、运行耗时与资源、原始失败和剩余unknown。部署Linux／H800包及其真实推理资源／性能继续单列；本机模型可运行、源码215tests、桥接1test或当前计划都不表示全任务质量、权重微调或H800通过。
