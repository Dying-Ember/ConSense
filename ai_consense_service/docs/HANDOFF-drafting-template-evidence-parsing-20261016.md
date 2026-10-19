<!-- oer-guidance-source-scopes-I47-20261019:start -->
**I47：OER 指导语证据按需接入 Vetting 与 Advisory 消费链；不新增解析真源、不改 candidateGraph／canonical 契约、不重生成，活动服务未部署。**

本轮修的是 I44／I45 之后确认的消费落差：Vetting／Advisory 只拿到指导语原文块，拿不到"指导→目标条款／条件／标记"关系。该结构只在 Drafting 模板路径生成（`DocumentParser.parseTemplate` → `OerTemplateEvidence.analyse`）；标准模板与指导语按既定范围不进审查／咨询语料，因此没有现成通道。

做法是不扩范围、不改契约：在请求内用同一份已持久化解析（`structuredContentJson` ＋ `parseCoverageJson`）重建 `ParsedDocument` 并运行 `OerTemplateEvidence.analyse`，只投影与本次选中成员相交的部分。
- Vetting：`OerModelMaterial.projectOrdinary` 在 `observedSourceUnits` 中新增 `observedGuidanceScopes`（`guidanceNotes`／`sourceConditions`／`markerCandidates`／`guidance_capture` 组），字段版本 `ordinary-word-guidance-scopes-v1`。
- Advisory：`OerModelMaterial.project` 在 normal 生产者下按 `documentId`／`originalSourceSha256`／`javaParsedSourceHash` 复核请求内来源身份（`AdviceService` 按 pointer 的 documentId 取当前文档；repository 缺失时不取），命中才挂同一字段。
- 证据不可解析、家族不支持或身份不符时字段保持缺省；不写库、不改 `parseCoverageJson`、不改持久化代次、0 OCR／模型调用。

验证：定向 133 项＝128 通过＋5 项原有条件跳过，0 失败；全量后端 3635 项＝3477 通过＋14 failures＋9 errors＋135 跳过。23 项失败与改动前基线（stash 两个产品文件后同批重跑）逐类完全一致，属既有环境问题（测试用 Python 解释器、进程／端口、SQL／临时目录），原样保留、不计通过。首轮全量另有 5 个 errors 是本轮引入的（部分测试构造 AdviceService 时 repository 为空），已加空值保护修复，原失败保留记录。

未覆盖：真实原件上的触发与耗时、前端与浏览器业务验收；`native_pdf`／`family` 生产者快照不含 candidateGraph，本字段对其不适用；标准模板指导语仍只在 Drafting 侧结构化。本轮只提交并推送源码／测试／文档，不部署。证据 `.local/oer-guidance-seams-20261019-I47/verification.json`；台账 I47。
<!-- oer-guidance-source-scopes-I47-20261019:end -->
<!-- oer-native-pdf-source-scopes-I45-20261019:start -->
**I45：修复原生PDF标题／修订边栏、保修表所属条款和跨页共同条件；精确采纳2产品＋2测试，活动服务未部署。** 模板规则仍为 `native-template-rules-20261019-r21`，本轮修现有普通PDF来源分组和消费者，未新增解析真源。

亲看SCC原图并逐原生字形核验。第23页SCC7.6现在按完整标题字体证据建根；修订旁栏不再作日期根。保修表9条原生行和跨到第24页的6条共同起算条件归入SCC7.6(4)(d)，精确保留19行有序范围，截止(e)前；(e)及其i/ii恢复原父子关系。原8格、字形、坐标、来源及全部8个原生表方法组完整保持。单独选择跨格行亦沿明确方法组ID带回原完整表，缺失／空白／重复来源ID和外源链接不能证明完整性。

全66页保存观察首轮拦下第65页同类日期根，并发现第14／18／32／62页的普通正文／Guidance混合旁栏；第18页日期含空格，不能靠日期正则过滤。按已观察编号／正文列和独立相连旁栏窗口补齐，不按日期、颜色、文件名、页码特判。真实小字号条款、相隔旁注之间的正文和缺源／缺页／错坐标反例均保留检查。最终Java定向共215项，211通过、4项因未提供本地原件回放目录而条件跳过，0失败；保存全66页来源复算通过35/35门，实际普通来源选择／模型材料／严格JSON传输复验通过，canonical公开消费者另有32项契约回归通过。本轮没有新PDF解析、CPU provider、OCR或模型调用，不把回放耗时视为OCR速度。

原保存parse字节保持；3380总块（3182原生行＋198方法观察）、227798字形在分组前后保持。raw反序列化差异与build变更分开报告，routingObservation由验收helper单独保留原始admission字段，不冒称helper装入了该字段。I44的六个定位实际共享1个完整普通chunk，本轮按完整chunk哈希去重复算，原六条定位仍分别核验。新增材料回归中的本地原件回放用例若缺明确目录则保留条件跳过，不能记通过。

SCC第32页Guidance中的1–5数字清单仍被普通来源分组误作根，真实SCC8.7范围因此提前结束；已亲看原页并核完整输出，列入下一步，不称整份章节归属通过。最终同ID差异复核另确认第57页跨行Clause 7.4引用被旧规则误作标题，本轮跨页续接使第58页38行续文继续归到假7.4下，scopeComplete仍false；原文保持，父级错误未解决。下一步需同时修指导编号和跨行引用／标题区分，不能把这类传播记为整体结构改善。NTT剩余10枚真实修改标记的正式范围、1枚说明符号分类及原件NTT17缺括号保持；SCT40页扫描和SL跨分册长表仍分阶段。AoA/APL按已确认实际用途，Word暂收口。本轮不声称整份SCC或所有PDF语义质量通过，不授项目适用性或自动编辑。六入口旧文完整保留为后缀，单本HTML追加I45，历史I00–I44原字节保持。原件／模型／活动服务／DB／索引不写，892个main build/web产物保持，不提交／推送／部署。完整证据 `.local/oer-pdf-native-scopes-20261019-I45/verification.json`，同一本HTML `logs/index.html#I45`。
<!-- oer-native-pdf-source-scopes-I45-20261019:end -->

<!-- oer-pdf-scope-and-native-table-I44-20261019:start -->
**I44：NTT 单段删除目标与两套 BQ 发放候选边界已修；SCC 原生表格的完整单元和正式消费接缝已修。源码已采纳，当前模板规则 `native-template-rules-20261019-r21`；活动服务未部署。**

三子代理并行实施、原件审核和独立边界检查，root 整合。NTT 第3页的删除旁注准确连到完整(e)段208字符，并在下一条*2前结束；第3/4页两套BQ发放候选准确保留6/4个正文成员，不再把第5页候选吞到第12页。原13个标记字符/位置、11条指导、168块、24原始候选及8非目标页保持。4/11指导取得精确原件审核语法证明，只有(e)新增完整目标；另10枚真实修改标记及1枚说明符号仍缺正式范围/分类连接，不能归为只差项目选择。NTT17原件未闭括号保持。

SCC 原生PDF第23页4×2保修表复用原物理格进入现有logicalTable，再由完整原生单行/唯一完整单元片段连到普通材料；原文字、8格、字形和坐标不重写，不按几何或相似文字单独采用。新格型必须完整占位、无缺/重字形且恰覆盖原字形集合；完整门只在建组前执行一次，整行匹配也不能绕过。真实原件66页按当前身份链运行33个原生CPU批次，全部candidate_observed；3182原生块、正式全文及66页完整原生观察逐值保持。没有新OCR或模型调用。

最终Java定向333/333通过，I43原251用例身份/重数/通过保持；Python逐模块独立进程50/50通过，组合运行原有4个Camelot生命周期失败原票保留。最终冻结runtime的NTT12页原件/保存OCR回放及普通、Drafting读取通过；六Word和SCC原生PDF的保存证据复算，除三个sourceBinding重算字段外全值/全序保持。SCC最初同JVM在wire断言失败；已证明是DoubleNode与保留小数字面量节点的Java类型比较误报。使用同一保存parse单独复验，六份完整有序JSON的UTF-8字节严格相同，8格/字形/坐标/原引用均保；没有重跑provider，原失败未覆盖，不称为原JVM整轮通过。

**继续修的真实缺口：**SCC第23页修订边栏日期01.12.2022被现有原生条款分组误当根，真正SCC7.6没有成根，跨到第24页的六行共同起算条件虽原文完整，却没有形成截止(e)前的所属范围。这是编号/边栏与跨页续接的引擎缺陷，已进入I45并行修复，不是要求用户决定项目适用性。SCT40页扫描、SL跨分册长表及其他未验源页继续分阶段，AoA/APL维持已确认的比对用途。原件/gold/模型及活动服务/DB/索引无写操作，892个main build/web产物保持；不提交、推送或部署。完整证据 `.local/oer-ntt-scan-scope-20261019-I44/verification.json`，同一本HTML `logs/index.html#I44`。
<!-- oer-pdf-scope-and-native-table-I44-20261019:end -->

<!-- oer-ntt-scan-adoption-I43-20261019:start -->
**I43：NTT 扫描 PDF 的两枚星号、完整评分公式和旁注采用接缝已修复；本轮是原件复核后的真实保存观察回放，0 新 OCR／模型，活动服务未部署。** Word I41／I42 范围及模板规则 `native-template-rules-20261019-r20` 保持。用户最新要求三子代理并行，本轮分别进行原件与全文件回放、局部采用加固及独立边界验收，由 root 整合；下方旧轮次的 root-only 记录保留历史语境。

亲看原页并审核完整候选。NTT 物理第3／4页的 `*2` 各恢复一枚，第3页的 summations 亦按原件保留。第7页仅以完整公式替换整个误分表格，Corporate 8%、PASS 17%、10%技术评分分式和65%价格分式均进入正式结果；其余14个原主候选块及页码两侧横线保持。第12页只替换完整旁注，保另14个原块及空资产上下文；原件NTT17本来缺右括号，保缺失并明确不完整，不采用备用整页把装订孔误读成 C. 的错误。

复用既有 dual observation → source review → canonical 完整单元 → 正式 regions → ordinary／Drafting 链，没有第二套解析模型。整页审核必须覆盖该候选全部块；局部采用必须逐项覆盖整页原块的保留／替换，异类旁注不能吞正文，细小脚注不能替整表，单个误分表格转整公式须明确重分类。相邻框交叠只接受绑定实际输入、同变换、精确块对与交集的源审证据，不改框或加容差。未审核／歧义输入保原行为。全文件配置新增 `sourceReviews`，按双run完整身份精确选取该页审核记录，防止单页规则误套其他页；重复、非法或混用配置在provider前拒绝。

同一当前冻结Java runtime、同一原件完整12页执行修前／修后对照：8个非目标页完整blocks、文字与页组精确相等；24个完整双候选对象保持，第7／12页28个旧正确主块的文字、原框和provider观察保持。168→168来源块、11→11指导，标记11→13仅是补回漏读，不是准确率。实际Paddle解码、原件解析、普通材料及Drafting完整读取的正文、blocks和七类证据均保全，canEdit=false。成功对照两次共24页保存观察回放，墙钟含编译25.891秒／17.016秒，不作为OCR速度对比；另一次helper内存MissingNode与持久化null误比的失败保留，实际总执行三次／36页。

最终Python七模块156项全部通过，旧117身份与通过保持；Java定向251项全部通过，未重跑本轮全后端宽回归。保存了旧review未采用、局部替换吞表、bool坐标／异变换、单页规则误套、helper及环境失败的原票。只采纳4产品＋6测试路径，无新依赖；旧未审核页输出另以原main出口逐字段核对保持。原件、模型、活动服务／DB／索引及892个main build／web产物保持，本轮不提交、推送或部署。

**仍需下一步：**扫描NTT的13个标记中12个精确作用范围仍unknown，另1个是说明性符号；11条OCR派生指导的来源语法验证不能因文字恢复自动升级，NTT17原文缺闭括号继续保留。SCT40页扫描版继续分阶段；SCC正常上传会传来源身份，旧native-only helper的identity_unbound不代表产品整体断路，物理第23页真实保修表及第24页共同起算条件待正常路由验收；SL长表仍需跨分册原件核验。AoA／APL沿I42实际比对用途，不重开完整表单结构要求。完整证据 `.local/oer-ntt-pdf-quality-20261019-I43/verification.json`；单本HTML继续 `logs/index.html#I43`。
<!-- oer-ntt-scan-adoption-I43-20261019:end -->

<!-- oer-pdf-restart-I42-20261019:start -->
**I42：按用户最终用途收口 AoA／APL 文本，修复 SCC 原生 PDF 第13页两处精确删除词组关联；源码已采纳，活动服务未部署。** 当前模板规则 `native-template-rules-20261019-r20`，旧代次须重新分析，不能只改版本号。root 独自完成本轮，没有重启子代理。

AoA／APL 只要求保页眉页脚、正文和可定位的大致分组，支持项目名称／合约编号及 APL 的 BS 分包名称与 Drafting 对应变量比对。已重验两份原件 SHA、逐看 APL 三页及 AoA 身份页；以保存的真实识别结果经当前 ordinary 消费复算，AoA 43、APL 16 个非空文本块全部保留，各9段材料的文字、anchor、条款与来源身份保持。AoA 合约编号20250101和完整项目名称、APL 同一编号及三类专业标题均有来源定位。APL 这版未写项目名称、公司名称栏未填，保留原件未提供；本轮未读取项目实际 Drafting 值，不冒称业务一致性已通过。旧57／74／11及19个Roman空位保历史，不再阻塞当前用途。

亲看独立版SCC PDF物理第13页，按原指导把 Option A/B 的条件与当前正文中待删的引用文字区分。补齐 `delete the word(s)` 语法、引号内条款号与条件引用不作删除条款定位、前置标记的双行原生字形范围；显式 in/from Clause 目标仍按指定条款找，保同源／同页／同坐标系／行距／缩进／字形检查及重复匹配拒绝。两组精确引用分别恢复，项目Option选择与自动编辑权限不作推断。

相同保存的66页观察完整复算并经实际 Drafting reading／p54 observation bind；3,182来源成员、18指导、31条件、208旧组、其余59标记整值保持，只新增2组、更新2个标记，unknown11→9。余9个仍是6个正文局部边界未明＋3个目录副本。六Word保存完整观察的证据除重算sourceBinding外逐值逐序相等；这是规则／消费复算，未重新OCR、未新跑原件解析或模型。66页视觉缺口、nativeTable identity_unbound以及NTT/SCT扫描问题保持原状态。

先复现3个RED，单独语法修正剩2失败、目标修正剩1失败，双行修正后通过；最终定向237全过，另含11种来源／布局／歧义反例。完整后端3636项＝3521通过＋115原条件跳过，0失败／错误；I41原3631用例身份重数、3516旧通过及115完整skip签名保持。源审汇总器首次误把无ID的issue当ID对象导致KeyError，修正汇总方式后全量核对通过，该工具失败单独留证。

仅采纳1产品＋1测试，沿用既有观察→模板证据→普通／Drafting消费链，无新依赖。原件、模型、活动服务／DB／索引、892个main build/web产物及I00–I41历史保持；未提交、推送或部署。本轮完整证据 `.local/oer-pdf-restart-20261019-I42/verification.json`；单本HTML `logs/index.html#I42`。
<!-- oer-pdf-restart-I42-20261019:end -->

<!-- oer-word-followup-I41-20261019:start -->
**I41：已修复标准 NTT 未闭指导误伤后续条款，并补齐标准 SCT／SCC 七个未知标记的原生指导／字段上下文；源码已采纳，活动服务未部署。** 当前规则为 `native-template-rules-20261019-r19`。下方 I40／r18 及更早内容保留为历史，旧证据须沿既有重分析流程生成新代次，不手改版本或迁移旧观察。

直接复查原 DOCX ZIP/XML 及既有同源页图。标准 NTT 17 的未闭边注原来沿旁栏继续收进第18条及后续的9个纯空段，导致18、18(a)、18(a)(i)、18(a)(ii)、18(b)五个独立范围被误降级。现在仅在同来源、同原生表、不同明确编号边界，且旁栏后缀全部有原生纯空段事实时收窄；非空续文、未知payload、同号续表、异表或不同part修订均不裁。指导保前4成员，原文缺闭括号、capture/sourceSyntax=false及NTT17 partial均保留。标准NTT由62完整+6 partial变为67完整+1 partial；六Word总计1842完整+8 partial，范围总数1850不变。

七个未知marker只补上下文，不授修改范围。标准SCT5的指导确实存在，但指导中的Bills与正文Bill有原文字面冲突；保同原生行指令关联并明确未对齐精确目标，不模糊删除。SCT7(1)的Mr/Ms连回已有双选项字段与Project Architect角色旁注，项目选择仍未知。标准SCC7.302、8.304(xiii)、22.305五枚星号连回同条款的Only adopt/PQS指导与条件，具体局部删改起点仍未知。159 marker中152个其他对象整值保持，原7个仍unknown；99指导、7216来源成员、1181组、191条件的身份顺序保持，只有NTT同一指导/capture/condition三对象裁掉已证空尾。

固定27原Word重新解析，墙钟27.062秒；34943原生成员、15611内容块、5042来源范围身份与顺序全部保持，26份完整普通JSON精确不变。六模板真实解析、Drafting全文读取及4份有指导文件的具名观察绑定完成，解析/阅读71.911秒；2份无指导项目源按原件缺少处理。新NTT18家族五正例实际完成ordinary/canonical→Python本地CAS/registry→Java材料/compact全链，0失败/未执行；NTT17旧负例只作为I40历史证据，不计本轮新跑。标准模板的隔离contract_appendix回放不构成Vetting/Advisory业务采用。

最终定向217全过。后端组合回归3631项=3516通过+115条件跳过，0失败/错误；I40全部3613旧用例身份/重数、3498旧通过及115完整skip签名保持。首轮宽回归447.203秒因root启动配置漏离线Python/graph fixture与largeUtf8开关，保留3 errors/额外skip原票；仅原5套件用I40原配置重跑122/122通过，97.813秒，按整套件替换构成组合验收，非第二轮全量实跑，重叠不相加。

剩余来源问题具体保留：NTT17原指导缺闭括号1个partial；项目SCT证书E手排标题无原生heading/outline/编号或正面布局角色，7个partial。已查看原件/styles/numbering/派生页，27原件反例证明不能只靠Normal字体和缩进推断标题。SCT的Bills/Bill冲突、Mr/Ms项目选择及SCC的PQS局部边界仍各自待核。只读查看SCC PDF第16页核对删改指导，本轮无新PDF解析/扫描OCR/模型调用；PDF视觉缺口及NTT/SCT扫描分阶段范围保持。

仅采纳1产品+4测试路径，无新依赖；六份入口文档追加本前缀，同一本HTML追加I41，I00-I40历史原字节保持。原件/gold/模型/活动服务/DB/索引和892个main build/web产物保持；不commit/push/部署。完整证据 `.local/oer-word-followup-20261019-I41/verification.json`，单本台账 `.local/oer-recognition-evolution-20261015/logs/index.html#I41`。
<!-- oer-word-followup-I41-20261019:end -->

<!-- oer-template-closure-I40-20261019:start -->
**I40：标准／项目版 NTT、SCT、SCC 的原生来源范围及 Drafting 读取接缝已修复，最终八个代码／测试路径已精确采纳；活动服务未部署。** 下方 I39／r17 及更早段落按历史时点阅读。当前规则为 `native-template-rules-20261019-r18`，继续沿既有 OOXML／PDF native observation → sourceClauseScopes／OerTemplateEvidence → ordinary／canonical → Drafting 来源读取，不增加第二套解析器或技术栈。

六份 Word 分别按自己的原件、版本和原生 XML 核验。已修复续表与重复条款混淆、Alternative／Option 分支末界、嵌套表格误作旁栏、大小写 Roman／字母及插入后缀、显式清单和共同尾句、项目 SCT 两条填写指导的漏捕。最终消费还查出原生纯空段在条款和分支里的角色不一致，以及标准 SCC 的 source-list 越过下一 Option B；只补原 XML 正面证明的纯空段，并在共同尾句推导前应用已证分支末界，列表自身与下级范围同时裁到真实排他终点，严格包含校验保持。13 个分支共补 124 个已证空段，56 处原包含冲突归零；被原件审计拦下的 runtime11 及定向反例失败均保留。

最终固定 27 份 Word 真实重新解析，墙钟 27.360 秒。I39→最终直接比较证明 34,943 个原生成员旧字段及 15,611 个内容块、原文通道和顺序保持；21 份非目标文件仅新增精确的原生空段事实，其余完整旧 JSON 保持。六模板来源范围由旧 1,788 个（1,263 complete／337 ambiguous／188 partial）重组为 1,850 个（1,837 complete／13 partial）；全部身份重绑、续章合并和范围变化有原件账本，不能把数量差当准确率。最终标准 NTT／SCT／SCC 为 62+6／218+0／700+0，项目 NTT／SCT／SCC 为 66+0／232+7／559+0，前数 complete、后数 partial。文件级状态仍为 22 PARSED＋5 PARTIAL。

最终六 Word 真实模板解析与 Drafting 完整读取耗时 71.968 秒；4 份有相应指导的文件完成 named observation bind，2 份按原件不存在保留 source-absent。相同完整保存观察的 r17→r18 对照为 97→99 条指导、159 个 marker 保持、1,177→1,181 个 group、unknown 9→7；7,216 个来源成员保持，19 个完整变化对象逐项留证。该对照隔离规则变化，没有重跑旧 parser 架构。标准 SCC 的 ten、项目 SCT 的法例空位及五个原始方框字符共 7 次实际 native bind 成功；五个方框使用独立原件复核坐标，不授自动选项集合识别或项目勾选结果。

独立版 SCC PDF 真实读取 66 页 native 层，40.047 秒；SCC7.3(3)、(6)、(7) 与 SCC22.1 四个重点跨页范围有完整来源成员和排他终点。PDF 的 18 条指导、61 个 marker、208 个 group 独立保存，不能借用 Word 版同号替代。实际 Drafting 阅读及 p54 observation bind 完成，完整六类来源字段相等，原始 reading／request／result 三份 gzip 均已落盘，canEdit=false。PDF 仍 PARTIAL，66 页保留 ocr_failed：本轮显式关闭 OCR，逐页 short／corrupt／form 触发为 0，但 vector gap 为 66 页、image gap 为 1 页；nativeTable 的 identity_unbound 是独立未执行原因。两种 synthetic DOCX 验证该 PDF 版内容未来以段落或表格边注 Word 提供时的既有路由兼容；当前没有该版真实 DOCX，不以合成样本冒称原件验收。

固定 41 用例保持同一 caseId／rangeId／selected seed／六文档，40 正例＋1 精确 partial 拒扩。仅 4 项期望按已证原件末界更正，19 次移出的旧 witness 原对象保留；独审从六个 ZIP part 核对 1,005 次 witness／990 个唯一段。最终普通／canonical → Python 实际 CAS／registry → Java 最终材料及 compact 全链为 40 个完整范围正常保留、1 个不完整范围按预期拒绝扩大，0 失败、0 未执行。Java 前段 63.485 秒、Python 120.625 秒、Java 最终读回 40.250 秒；保 2,944 个来源单元、2,835 个可检索指针和 109 个非检索单元。这是明确隔离路由下的来源保真／消费验收，不授模板进入 Vetting／Advisory 业务权限。

最终定向 250 项＝246 通过＋4 条件跳过，38.985 秒；完整后端 3,613 项＝3,498 通过＋115 条件跳过，0 失败／错误，461.484 秒。旧 I39 的 3,535 个用例身份和重数、3,420 个旧通过及 115 个完整跳过签名保持；定向原 4 个跳过的完全相同用例在完整回归中均实际通过；两轮重叠不相加。原 helper 身份差异、消费失败、定向失败和来源门拒绝均保留，不把它们改记通过。

保留项具体为：标准 NTT 原指导缺闭括号影响 6 个来源范围；项目 SCT 证书 E 的手排标题缺绑定布局角色影响父项及六个子项，共 7 个来源范围。人已查看同源派生页，但没有把人工阅读冒充自动布局角色。剩余 7 个 Word marker 的精确指令／目标范围证据不足，完整原文、位置和上下文保留。项目 SCT 整份 PARTIAL 另来自 11 个签署栏原生 pict 警告，与 7 个范围问题分开。NTT／SCT 扫描 PDF 继续分阶段验证；完整来源语法不授法律适用性、项目值、自动编辑或语义评分。

原件、gold、模型、活动服务／DB／索引及 892 个 main build／web 文件保持。没有 main 编译、真实 OCR／模型调用、commit／push／部署或 H800 操作。只给这六份入口文档加 I40 前缀，并更新同一本 HTML 的 I40，I00–I39 历史原字节保持。最终总门 `.local/oer-template-closure-20261019-I40/verification.json`，采纳凭据 `adoption-result.json`，来源汇总 `source-audit/summary12.json`，完整语料链 `final-execution/final-corpus-map12-02.json`；同一本 HTML 入口 `.local/oer-recognition-evolution-20261015/logs/index.html#I40`。
<!-- oer-template-closure-I40-20261019:end -->


<!-- drafting-template-seams-r17-20261018:start -->
**2026-10-18 I36 最新状态：模板解析与 Drafting 消费接缝已修复并精确采纳源码，规则为 `native-template-rules-20261018-r17`。** 下方 r16/I35 及更早状态保留历史语境；活动服务尚未部署此轮源码。

SCT1/SCT3 的四个完整选择分支现携原生共同指导，12 个标记取得缺失的指导/条件关联；SCT6(3)(c) 的整项条件与局部 `#` 条件保为两层独立关系，不压成一个布尔值或扩大修改范围。SCC 原生 PDF p54 完整修订边注组中仅日期行与指导重叠的情况已补齐，恢复指导 capture/syntax 和精确 glyph 来源，保留三条边注；目标 scope 仍不因 capture 恢复而自动变完整，PDF 总体 PARTIAL 保持。

Drafting 沿同一 OER 原观察读取条件、来源关系和关联指导；完整指导以 `sourceObservationBinding` 绑定原观察、全文、范围、原 SHA 及三个分析身份，明确 `editableAuthority=false`，编辑目标另要求精确 `nativeBinding`。Bill 插入使用显式 `insert` 和一个零长度 UTF-16 原生点，多段替换要求明确 `replacementPlacement.spanIndex`；保存、确认、计划、编译和人工 override 统一校验。修复共享 Word 文本遍历误读属性 tabs 为可见空格、真实 run tab 被压成空格的根因，真实 SCT 的14个插入点均可准确绑定；未推断未来 Bill 条数/内容或采用项目值。

界面显示真实证据代次与状态；缺失/旧证据进入既有替换重传再审核步骤。生成只校验当前 confirmed graph 实际 selectedDocuments 版次，未选辅助 PDF 不阻挡 Word，PDF-only 提前说明需要对应 DOCX。多段候选仅在原范围完整、native spans/身份未改且用户明确选择动作/落点时解除待重绑。

验收：27份原Word修前/修后均实际正常解析、原件SHA保持；34,943原生成员及15,611 block身份/顺序/来源保持，变化按原XML与全部19,694 carrier ranges逐项核验，无宽泛trim或忽略正文。三模板完整保存观察复算保3,999成员、97指导、122条件、157标记、717组原范围和7个未知标记；SCT只补指定关联。SCC PDF完整保存观察复算保3,182成员、其他17指导/203组及全部61标记，p54来源进入Drafting阅读与named observation绑定且canEdit=false。四来源复算没有新PDF解析、OCR或模型调用。

最终较宽130套件 1407 项＝1303通过＋104条件跳过，0失败/错误；当前最终源码的真实三Word阅读和SCT14点保存确认/人工测试值/生成另行2项通过，与定向重叠不相加。前端198项全过、typecheck通过。原RED、早期消费失败、完整原件扩展回归超时和PDF helper绕过JSON wire造成的失败均保留；最终报告明确每个条件跳过与后续同用例实跑状态，不用超时或跳过计成功。

本轮精确采纳27代码/测试路径，随后仅同步这六份文档；其余baseline及535受保护main build/web产物保持。不编译main、不commit/push/部署，不操作活动服务/DB/索引。7个未知标记、未来Bill基数/项目事实及PQS适用性、扫描NTT旧缺陷/SCT40未执行页继续保留。完整凭据 `.local/drafting-template-seams-20261018/verification.json`、`source-preservation-review.json`、`word27-source-review/independent-source-gate01.json`、`adoption-result.json`；单本HTML追加 **I36**。
<!-- drafting-template-seams-r17-20261018:end -->

<!-- drafting-template-stability-r16-20261018:start -->
**2026-10-18 I35 最新状态：** 样板Word优先路由现保全独立PDF候选及完整已有解析；自动模板图排除未采用辅助源，同时保留显式来源阅读。SCC原生PDF补齐指导首项和两行换行的具名词组，8个逻辑出现／10个精确范围；三Word除r16代次及两个摘要外整值保持。旧r15作为历史保留，当前使用需显式重分析，活动服务未部署。SCT共同指导关联、7个未知marker、SCC p54及扫描验证边界保持。详细状态以[已确认规则](oer-scope-and-rules.md)及`.local/drafting-template-stability-20261018/verification.json`为准；单本HTML为I35。
<!-- drafting-template-stability-r16-20261018:end -->

<!-- drafting-template-acceptance-priority-confirmed-20261017 -->
**2026-10-17 用户已确认验收顺序：先收口 Word 与原生 PDF 的解析质量，扫描 PDF 分阶段验证。** 当前验收不要求同步跑完 NTT/SCT 共52页扫描；已完成的NTT12页真实结果及缺陷保留，SCT40页尚未启动。每阶段分别记录实际覆盖、源质量、耗时及未完成，不以Word通过替代扫描版通过，也不由本次确认自动新增OCR、Goal或定时任务。

<!-- drafting-template-router-source-adopted-20261017:start -->
**2026-10-17 当前状态：NTT/SCT/SCC 模板专用解析 r15 已精确采纳到主源码。** Word 优先、实际字节分格式和 family/版次隔离；复用已有 OER 原生遍历，保 Guidance、完整范围、条件原文及命名通道位置。三 Word 冻结97组指导、157标记、3999原生段对应；全部63个已声明完整语义范围源核通过，新增27无局部标记填写区及1个Mr/Ms候选也逐源核验。14个Bill描述只给插入锚点，未来长度/列表基数和项目值未知；7个Word marker继续unknown，14处较广指导的直接条件依赖边未完成，原文上下文保留，不授自动编辑或完整项目条件图。

最终521项＝487通过＋34既有/条件跳过，另真实三Word隔离upload/reading/evidence方法1项通过；27普通Word全parsedDocument与I29保持，三模板去仅两可选coverage域后整值保持。SCC独立PDF18指导正文及边注分开保留，但normal仍PARTIAL。NTT12页真实OCR已完成并揭示主候选漏星/公式/补括号缺陷；r15对完整保存结果仅复算证据，0新OCR且不选未验证候选，不授模型质量提高；SCT40页未跑。活动API/浏览器/真实Wordpreview/业务验收未授通过。

13Java＋1Vue精确采纳，完整810后端文件等实测shadow；其他797、Python293、main target811（活动453）保持。无commit/push/部署、服务/DB/索引操作，不创建Goal/提醒；无模型部署或写操作，未执行权重逐文件before/after核验。当前说明见[模板专用路由实施文档](specs/drafting-template-source-routing-20261016.md)，本地完整凭据在`.local/drafting-template-routing-20261016`，继续一本HTML的I30。旧“实施中/未采纳”段落按历史状态阅读。
<!-- drafting-template-router-source-adopted-20261017:end -->

---
title: Drafting 模板证据解析实施交接
status: authorized-implementation-in-isolated-shadow; source-acceptance-in-progress
date: 2026-10-16
timezone: Asia/Shanghai
baseline: 10c3eaf3057a3a45cbf8747468cd48ddf78287aa
scope: NTT/SCT/SCC DOCX and separately identified PDFs; Word preferred; project completed sources isolated
---

# Drafting 模板证据解析实施交接

**最新执行状态：用户已直接授权专用路由实现，要求根代理亲看源 XML 与页面后逐模板加固。** 本轮 `.local/drafting-template-routing-20261016` 已固定九份独立来源并建立源参考，产品修改仅在 shadow；代码、完整原件解析及全部范围独审仍分别验收。NTT／SCT 原 PDF 为12／40页扫描件，SCC 原 PDF 为66页原生文字，均与 Word 分版保存。NTT Word/PDF 即使同标 March 2020，技术支持文字也不同。Word 优先只决定主来源，不证明同版。源标记可能控制跨表选项、完整行、非连续子条或句内短语；页尾、表尾及 Cont’d 不自动构成终止边界。继续同一本 OER HTML，不新建 Goal／timer，不改活动系统或原件。

## 接手目标与执行边界

在变量／规则模型工作前，把当前模板里的条款层级、原文、原生位置、布局线索、全部编制说明及其完整作用范围交成可核查证据。输入必须足以回答：**这条修改要求从哪里来，涉及哪些独立事实，作用于哪些完整成员，哪些关联仍无证据。** 本交接重点是解析精度及图谱输入，不能用模型补写原件缺漏，也不能由静态 catalog 反推“原文一定有这些变量”。

本文件只交接开发与验收工作，不记录这些能力已完成。编写时只读核对文档、代码接口、已有本地证据与四份原件 SHA；没有运行 parser、OCR、业务模型、服务、索引或真实编辑。实施者先核最新用户授权及现有工作区／并行 owner；保当前其他修改，不重启或替换活动前后端、DB、GPU／WSL 服务，不发布原件、权重、密钥或运行证据，不自动创建 Goal／timer／push。

**唯一解析链路继续使用现有 OER。** 原始内容／结构／位置在哪一层首次丢失，就由该层原 ENG owner 修；Drafting 只补消费投影与证据组装缺口。不要复制本地提取脚本成为第二套 parser，不先重写 UI、业务字段目录或生成器。

先按以下触发读取权威资料，历史状态以各自冻结身份理解：

| 何时读 | 参考 | 本交接采用的边界 |
|---|---|---|
| 开始及执行范围变化时 | [OER 范围与执行规则](oer-scope-and-rules.md)、[OER 实施交接](HANDOFF-ocr-embedding-rerank-20261008.md)文首当前状态 | 模板／评价答案／图纸隔离；代码、原件、模型及业务结果分开；原 ENG／EAC 门保持 |
| 排定 owner、依赖及产品接缝时 | [DYN 前置能力与任务图](specs/drafting-template-adaptation-prerequisites-20261015.md) | 本交接落实 DYN-00–06 的解析证据前置，向 DYN-07–09 提供输入；不跳过 G0/G1 |
| 修改正常解析入口或分组时 | [OER 修后流程图](diagrams/oer-recognition-after-repair-flow.md)、[当前演进路径](specs/oer-recognition-evolution-path-20261015.md) | 按当前正常入口、完整文档组装及跨文件回退门执行，复用原修复，不围绕单页过拟合 |
| 定图谱输入或语义状态时 | [typed graph 工作流](specs/drafting-typed-graph-workflow-20261015.md)，尤其六轴契约、目标编辑、独立上下文与第15节逐环节标注 | 解析只提供证据、候选及范围；变量／关系／动作候选和最终采用分别保存 |
| 处理另版 SCC 或核两版对应时 | [PDF SCC 提取与隔离记录](research/scc-pdf-variable-extraction-isolation-20261015.md) | 两版 SCC 的条号、对象、默认、条件、成员及目标不互相继承 |

## 1. 固定来源、已有材料和不可冒认的结果

下表原件在 2026-10-16 交接编写时再次按实际文件字节核 SHA，相同于已有 receipt。异机接手先查可达性并重新核，不从相同文件名认定同版。

| 来源 | 本机原件绝对路径 | sourceId | SHA-256 |
|---|---|---|---|
| NTT DOCX | `D:/R&D/ConSense/.local/drafting-regenerate-hotfix-20261015/NTT-original.private.docx` | `ntt-docx-60bd8796aa82` | `60bd8796aa828863c7edfe8617b7a5c56ee383afe8ba87a089e820f0fd9a982c` |
| SCT DOCX | `D:/R&D/ConSense/.local/drafting-regenerate-hotfix-20261015/SCT-original.private.docx` | `sct-docx-7def01d62e07` | `7def01d62e07b35dd86beeff995e0e37d73ce18adca2746e2a6f6d9bafb5aaa4` |
| 现用 SCC DOCX | `D:/R&D/ConSense/.local/drafting-regenerate-hotfix-20261015/SCC-original.private.docx` | `scc-docx-9aa2fa066836` | `9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208` |
| 另版 SCC PDF | `C:/Users/Administrator/Downloads/email from Smart QS Hackathon Team/07_Special Conditions of Contract (SCC).pdf` | `scc-pdf-1bebab1afbc0` | `1bebab1afbc0faaf8aa9c0b9e6c51b023c24e53a094d4987d746fdeb65b30741` |

已有证据入口均在忽略目录；路径是取证索引，不随 Markdown 发布：

- `D:/R&D/ConSense/.local/drafting-guidance-stability-20261015/source-map.json`、`extraction-receipt.json`、`corpus.json`、`corpus.md`：三份 Word 的原件／document.xml 身份、main `w:p` 逐项原文、guidance、其余标记候选、其他 story 检查及缺口。
- 同目录 `extract-guidance.py`：历史离线分析方法，**不是产品 parser**。它主要拼 `w:t`，按现模板末列表格布局找括号说明，并与旧 catalog 文本核对；不能据此证明 tabs／breaks／sym／OMML、继承样式、修订有效态、完整布局或任意新版均已支持。它的段落合成 ID 与简单 XML 路径不直接等于正常 OER native ID 或编辑器锚点。
- `D:/R&D/ConSense/.local/scc-pdf-variable-extraction-20261015/source-manifest.json`、`source-verification.json`、`inventory.json`、`validation.json`、`part-01-22.json`、`part-23-44.json`、`part-45-66.json`、`rendered/`：另版 PDF 身份、66页文字／页图、人工处理点及证据。PDF 为 2013 Edition、合订至 2023-01-01；本版物理1–3为封面／目录，物理4对应纸面1。这个映射只适用于本版，不写成通用页偏移规则。
- `D:/R&D/ConSense/.local/drafting-variable-graph-audit-20261015/atomic.json`、`atom-contract.json`、`atomic-receipt.json` 及 [图谱还原审计](research/drafting-variable-graph-reconstruction-audit-20261015.md)：人工还原的类型／链／证据参考，可做开发挑战集；不是自动解析输出、完整业务 gold 或新引擎通过证明。
- [两路模型试验记录](testing/drafting-guidance-followup-20261016.md)：当前模型接口／语义失败边界。合成 MOCK 段号只核语义意图，不证明真实编辑范围；模型输出不能倒灌为原始证据。

历史分母仅用于接手复算，**不是固定代码常量或最终完整性结论**：

| 口径 | NTT | SCT | Word SCC | 来源及限制 |
|---|---:|---:|---:|---|
| main document 内 descendant `w:p`，含空段／表内段 | 665 | 1238 | 2074 | `source-map.json`／`extraction-receipt.json`；不是 Word 物理页数，也不代表所有 story |
| 已提取 guidance blocks | 11 | 44 | 42 | 共97；分别对应18／73／103个非空guidance段，共194 |
| guidance之外的额外 marker／keyword候选 | 13 | 37 | 50 | 共100；未必是变量或真实编辑点，仍需完整处置 |

NTT-GN011 原文括号未闭合，来源段 NTT-P0614／0617／0620 内容照录；保 `sourceSyntaxComplete=false`，不要补括号后宣称原件如此。PDF 既有人工清单为74处理点候选、144逐字引文、155标记／编辑词候选行；这些均不是独立用户变量数。接手者对新参考发现增漏时增加参考修订与差异账，不为守住旧总数删项目。

**2026-10-16 全文扩展前的原生特征盘点补充：** 当前模型实验的 `w:t` 文本包与原生特征必须分别核对。直接重读上述原件 `word/document.xml` 后，NTT／SCT／SCC 分别观察到 `numPr` 8／21／22、`tab` 541／2209／2018、`br` 4／2／26、`u` 99／188／41；SCC 另有 `fldChar` 279、`instrText` 97。这些是 XML 元素数，不是变量数、漏项数或正常 OER 已丢失的证明。逐特征所在原段 ID、原件与 XML SHA 见忽略目录 `D:/R&D/ConSense/.local/drafting-graph-stability-campaign-20261016/evaluation/full-source-native-readiness-census.json`（SHA `b2cc15182a75ad8c8a917025a71de187f8425548216d21823902d62d2389884d`）。先核已有正常保存证据及消费投影是否保全这些信息，再决定首个缺失层；不得为模型试验复制第二套编号／字段解析器，或因提供了全部 `w:p` 就授完整可见文本／可填字段／布局通过。当前实验的简短直接表坐标编码能精确表示 NTT 全665段及 SCC 全2074段，SCT另有19段须保其他原生路径形式，不能静默跳过。

**同源正常保存证据已定位（2026-10-16）：** NTT 原件 SHA `60bd8796aa82…` 的历史正常产物在 `.local/oer-all-document-reparse-20261015/actual-native-01/corpus-02/complete-normal.json.gz`（SHA `6182abb393f2f79f7e3f46558966bcd9b85d595ba53255358029ff4f01436842`），I15 分节修订的同源保存产物在 `.local/oer-recognition-evolution-20261015/I15/shadow-native-docx-sections01/originals02/material/corpus-02/normal/complete-normal.json.gz`（SHA `88ac6951f9a674f09ab4ba371d78e05ede5e34a21dc4e759423cfdee60886826`）。两者各有128个正常聚合块；其中6块的8个 `wordNumbering` 已保存，按原生路径与 `numId` 可唯一对应8原段。它们是可复用的 producer 观测，不能由 `resolved` 直接授渲染编号或语义范围正确。现查保存投影未见精确 tab／显式 break 事件位置及 underline run 属性／跨度；普通空格／换行不能回推这些原生事件。先在现有正常观察和消费接缝补证据，不为图谱实验重写 parser。128聚合块与665原段不能相减作为丢失数；这些缺口也不直接等于变量遗漏。逐项证据、JSON pointers、源路径和边界见本地活动 `rounds/I08/normal-source-reuse-audit.json`、`normal-numbering.proposed.json` 与 `root-native-reuse-readback.json`；本轮仅据可读全文及独立编号层准备 D1 语义发现，完整版式、可填位置和图谱来源门仍未通过。

**SCT／Word SCC 同源正常产物复用补充（2026-10-16）：** 本地活动的 `evaluation/full-project-source-native-readiness.json`（SHA `8ab72e39b6476f11bbbbcacf8e7aac2b732e1c268759ff53c191b3cca988fcab`）已按原件身份定位 SCT=`corpus-03`、Word SCC=`corpus-04`，并核正常保存结果、candidate、ordinary 及 SourceDocument 接缝。历史正常结果分别在 `.local/oer-all-document-reparse-20261015/actual-native-01/corpus-03/complete-normal.json.gz` 与 `corpus-04/complete-normal.json.gz`；I15 同源结果沿上段 `originals02/material/` 下相同 corpus 路径取回。SCT 为349聚合块／PARSED，SCC为1100聚合块／PARTIAL，后者有4条结构warning；这些是工程观察，均不授来源或业务通过。已有21／22个编号对象在所查 candidate 与 ordinary 中逐对象保持；复用这些观察，不另写编号器。SCC 的4个数学原生对象保原XML／摘要身份，公式语义仍未核。

精确 tab／underline／显式 break 来源跨度在上述已查保存层仍未观察到；SCC 的97个 `instrText` 原始事件（92个字面PAGEREF前缀、1个TOC前缀、4个未组装片段）及279个 `fldChar` 也尚无专门字段指令来源投影，不能由缓存文字补成有效引用。SCT 的 `SCT-P0797–0804`、`SCT-P0806–0816` 共19段属于合法嵌套表路径，含空成员；保持完整原路径，不能强套NTT坐标形式或跳过。先由源核判断这些特征是否影响当前所需语义，关键缺证保unknown；元素数不等于变量数或遗漏数，聚合块数不能与原段数相减。以上是复用与缺口交接，0新解析／模型调用，不扩成第二套字段或布局parser；PDF SCC继续独立。

**编号对应已直接复核，来源事实可独立复用（2026-10-16）：** SCT 的21项保存编号现已逐项与原件 `numPr`／原路径唯一匹配，见本地活动 `evaluation/I11-SCT-saved-numbering-crosswalk.json`（SHA `a2d29656609ed92d4e2821f717563dff7df048d544965ea6ecf95fc1278a5548`）。Word SCC 的22项编号及4个数学对象另导出为不含私有评分的 `evaluation/I12-WordSCC-native-source-only.json`（SHA `bdc70c50c172d0b0f6c9a1a2d0a0b4484d07895b8e57984abb3b39df69c8230f`）：保存的编号对象与原节点对应；数学 rawXML 的字节身份和原始子树逐项对应，原 producer 的语义 unknown 保持。编译模型输入时可复用这两份 source-only 文件，不要把私有语义验收报告喂给模型或提示词作者。对应关系准确不授编号渲染、公式求值、法律范围或全图通过；精确事件跨度及字段指令缺口仍按上段处理。

**指导语物理容器不能跨模板套用（2026-10-16）：** 按冻结原 `node_path` 逐项盘点，NTT／SCT 的指导语分别涉及12／54个最近 `w:tr` 容器，另有跨多个 row 的指导语；Word SCC 的全部42条指导语／103个指导原段均无 `tr` 祖先，是 body 原段。该事实只描述这些指导语的原生位置，不能据此断言 SCC 整份没有表格。见本地活动 `rounds/I12-capacity/physical-container-capacity.json`（SHA `caa683801f48e2a2aa168c341efcc74613227b8002d5aa63c30db5ed871a38b1`）。不要把 NTT／SCT 的表行截取方式套给 Word SCC，或将 guidance 自段当成其完整法律作用范围；SCC 仍须核父条、选项、续文与引用。盘点未删除容器外任何正文，也未授法律闭包、布局或语义通过。

## 2. 先体检现有接口，确定唯一修改点

所有代码路径相对 `D:/R&D/ConSense/`。此处“起点”仅指已读源码的接口存在，实施者仍须沿实际版本复核调用者、持久化及消费者是否保全字段。

| 位置 | 可复用起点 | 首先核什么 |
|---|---|---|
| `ai_consense_service/src/main/java/com/consense/document/DocumentParser.java` | `observe(...)`、`parse(...)` 与 `ParsedDocument` 的 text／blocks／coverage／sourceSha／nativePdfEvidence | observe明确仅路由观察；正常parse入口是否把完整 native／结构证据带到Drafting，而非只传normalized text |
| 同目录 `DocumentBlock.java`、`ParseCoverage.java` | sourcePosition、Word编号／symbol／cell／nativeObjects、PDF glyph／form、visual/frame/provenance、whole page groups | 物理complete不证明文字／关系正确；candidate不能自动成为effective text，空白结构不能被文本过滤丢掉 |
| 同目录 `OerNativeDocxObservation.java`、`DocxNumberingResolver.java`、`WordSymbolDecoder.java` | 原ZIP／part、namespace路径、native node、原生表拓扑、四个重叠text channels及编号观察 | 原生通道与有效文本分别核；paragraph/run不能重复相加；继承隐藏／删除／strike／story适用未证时保unknown |
| 同目录 `OerNativePdfObservation.java`、`PdfNativeSupport.java`、`PdfPageEvidence.java` | 原件物理页、glyph/frame、crop/rotation坐标、表单与可见性观察 | 只选页观察不等于66页完成；印刷页标、原始物理页、bbox坐标框架分开 |
| `ai_consense_service/tools/vetting_eval/complete_ocr.py`、`structured_parser_evidence.py` 及当前 OER 正常结构生产接缝 | 双解析原始产物、完整采集／page map、完整组准入与冲突保留 | 未采集／失败／截断／争议组在哪层丢失；复用修复owner，不能在Drafting静默拼文本填洞 |
| `ai_consense_service/src/main/java/com/consense/service/sources/NativeCanonicalContract.java`、`NativePdfCanonicalContract.java`、`NativeDocumentCollectionContract.java`、`CanonicalEvidenceService.java` | 身份、完整来源单元／集合与当前来源验证 | 定位、完整组和未决能否正常存取；不创建第二套canonical真相 |
| `ai_consense_service/src/main/java/com/consense/service/drafting/DraftTemplateSourceText.java`、同目录 `DraftTemplateReadingSources.java`、`DraftSourceContext.java` | saved/fresh匹配、既有冻结来源身份、上下文窗口及context_insufficient | legacy归一化匹配不授原字保真；6000字符窗口及pipe表启发式不证明完整成员；不能只扩窗口代替结构组装 |
| `ai_consense_service/src/main/java/com/consense/service/drafting/DraftSourceAnchors.java`、同目录 `DraftSourceEditCompiler.java`、`DraftBusinessRules.java`；资源 `ai_consense_service/src/main/resources/drafting/input-catalog.json`、同目录 `guidance-compiler-bindings.json` | 当前版固定规则、段号与编辑绑定 | 只作兼容和旧正确回归；新版本重新发现范围，不能迁用旧段号／固定catalog来证明新解析正确 |
| `ai_consense_service/src/main/java/com/consense/document/DocxTemplateEditor.java` | `inspect(byte[])`→TemplateIndex、source hash、part/path/node hash、原生表／段落／run及受限apply | inspect不决定适用性；原生节点的真实绑定与编辑能力单列，故事区编辑不支持等限制保持 |

**体检交付**：一张能力账，每行写“已具备且有证据／字段存在但未贯通／已复现缺陷／未知”，附实际调用链、输入输出样本与首个丢失层。完成标准是每个本交接必需字段都有owner与证据或明确缺口，不以“已有一个parser类”完成盘点。

## 3. 解析必须保留的事实与判断边界

### 3.1 原文、层级与布局

保三层：原件／原始观察、分析投影、已确认采用。原字、空白／制表符／换行／控制符、数字／比较符／and-or-not、脚注和编辑标记保可回取；搜索归一化另存映射，不能拿normalize后的字符偏移直接切原件。

条款树至少区分文件／story、章／条／子条／列表、选项组及选项、guidance、表／行／单元格与页眉页脚／目录。显示标签、原生numPr/域、父节点、顺序、正文成员集合分别记录；同号多处保occurrence，目录同名不是第二份正文，段落样式名／正则命中只是层级证据。原文已有编号冲突、不闭合或缺父节点，保候选及原因。

DOCX 位置用实际source SHA、part、namespace node path、native ID／hash、story与**指定文本通道内的UTF-16 start-inclusive/end-exclusive偏移**。观察器的 `observedRaw/deleted/directStrike/nonDeletedNonDirectStrike` 是重叠观测通道，不自动等于渲染有效文本；编辑器part#path及hash需显式映射复验。段落ordinal可作本修订导航，不能跨版充当永久身份。没有经独立renderer映射时DOCX物理页／bbox保持unknown，不能拿分页符数、段号或catalog页号补假坐标。若增加渲染映射，另存原DOCX与派生PDF的SHA、renderer／字体／配置身份及节点到渲染页的对应证据，不能反把派生页定位当原生编辑锚点。

PDF保原始1-based物理页、page frame／crop／rotation／尺寸、glyph或区域来源及原生顺序。纸面页标另存。行文本／页文本提取不证明真实读序，边注、多栏、跨页及跨表结构要独立核。原生字体缺映射或Symbol／ArialUnicode渲染警告保留；不能只凭native有字就授字形正确。图纸按OER身份路由后排除；提及图纸编号的文字说明仍属于本模板证据。

### 3.2 全部修改要求入口

枚举显式Guidance Notes、括号侧栏／脚注、星号／井号及其说明、下划线／点线／空单元格、content control／表单字段、Option／Alternative选择、Not used／delete／insert／amend、无标记编制建议、外部核查及引用更正。跨run的同一标记保完整span，保普通星号／井号／目录引导线／单位符号的负例，不把每个符号变成变量。

每个候选须有处置：真实修改要求／固定正文／非编辑标记／仅外部核查／重复出现但独立位置／歧义／未观察／失败，并保理由与证据。无标记不等于无要求；空文本不等于没有字段；未解析出来不等于业务false。所有要求先形成来源账，再提候选变量，保持要求与变量多对多。

### 3.3 完整作用范围：以成员证明完成

一个target/group不能只存“标题＋首段＋末段”。交付**有序成员ID集合、各成员原生定位、父条／选项／表关系、边界证据、连续／非连续范围、缺失成员和完整性状态**。

1. 以本版结构与说明定位owner，收集正文、子项、共享前缀、续文、嵌套表、相关Guidance及条件／例外。解释为“删除／Not used／选A/B”的不同粒度由原文支持，不由相邻位置猜。
2. 遇页尾／行尾／表尾仍开着的guidance、列表或选项，沿本版逻辑顺序寻找续接。跨页重复表头／页脚作为furniture单独保留，不截断业务成员；另一张物理表是否延续，须有表头、列拓扑、标签及上下文共同支持，不因相邻就合并。
3. 完整性至少需可核的开始、同层结束／下一peer边界及内部全部成员。A/B组选项与各子项分别列成员；共享heading／common尾段明确归属。编号缺口、跨表读序冲突、成员含未知对象时输出候选组与missing／conflict，不标complete。
4. 长度／传输／上下文预算不足时保整个组的身份和分批manifest、游标、expected member集合（若已知）与digest；各批实际取回齐并核对后才可供完整组消费。不能裁尾、只给第一页、只给相邻N段，或把“所有已发现成员”改名“完整成员”。expected未知时保持unknown。
5. 编辑可行性另审。解析到PDF A/B全组不证明能原PDF保格式删改；DOCX分析quote准确也不证明原生span安全。只有本版原件hash、目标hash、expectedOriginal及能力白名单都满足，才向现有编译器交可执行绑定。

### 3.4 引用与版本隔离

每条引用保“引用出现位置／原文标签→文件命名空间／版次→候选被引条款／粒度”，并记录明示、候选、歧义、未提供来源、无匹配或冲突。SCC8.2、GCC8.2、SCT8各有独立命名空间。跨文件取证带实际source ID／SHA；仅给条号字符串不算已解析引用。

默认存在两个分析profile：`NTT+SCT+Word-SCC` 与 `NTT+SCT+PDF-SCC`。比较profile可建立带证据的跨版候选对应，不能把两个SCC同时当同一活动模板或共享其条号／默认值。PDF4.2 A/B与5.2引用、PDF8.2采用与22.8全组选项须读本版；Word7.304质保安装前提、Word标准预付款6/7等不得移植。PDF的Nominated Sub-contractor与Word Specialist/BSSSC对象分别保留，不凭相同工种合并。

## 4. 图谱输入的最小可审投影

下列是**待DYN-02评审的字段语义示意**，不是已存在API，也不要求创建新数据库或平行canonical模型。优先映射现有来源／coverage／native结构；ID、hash、反向来源及引用回取由程序维护。类型AST沿typed graph规格复用，不在解析层另造V/P/R词表。

```json
{
  "schemaRevision": "candidate-template-evidence-v1",
  "templateSet": {"id": "set:word", "revision": "r1", "profile": "NTT+SCT+Word-SCC", "sourceRefs": ["src:ntt", "src:sct", "src:scc-word"]},
  "sources": [{"id": "src:scc-word", "sha256": "<actual-sha256>", "mediaType": "docx", "parseGeneration": "<actual-generation>", "observerRevision": "<actual-revision>"}],
  "units": [{
    "id": "unit:1", "sourceRef": "src:scc-word", "kind": "guidance",
    "parentRef": "clause:1", "orderedMemberRefs": ["native:p1", "native:p2"],
    "rawTextRef": "artifact:exact-text", "originalTextSha256": "<actual-hash>",
    "locationRefs": ["loc:1", "loc:2"],
    "captureStatus": "complete", "sourceSyntaxStatus": "unclosed", "semanticScopeStatus": "unknown",
    "missingMembers": [], "limitations": ["original_unclosed_bracket"]
  }],
  "locations": [{
    "id": "loc:1", "sourceRef": "src:scc-word", "kind": "docx_native",
    "nativeLocatorRef": "<existing-part-path-node-hash-channel-span>", "renderedLocationRef": null
  }],
  "processingPoints": [{
    "id": "point:1", "requirementUnitRefs": ["unit:1"], "markerLocationRefs": ["loc:1"],
    "candidateKind": "conditional_edit", "disposition": "needs_review",
    "ownerRefs": ["clause:1"], "targetGroupRefs": ["group:A"], "evidenceRefs": ["unit:1"], "issues": ["scope_not_yet_verified"]
  }],
  "groups": [{
    "id": "group:A", "sourceRef": "src:scc-word", "kind": "alternative",
    "orderedMemberRefs": ["native:p3", "native:table1", "native:p4"],
    "boundaryEvidenceRefs": ["unit:begin", "unit:next-peer"],
    "completeness": "unknown", "missingMembers": [], "editCapability": "analysis_only"
  }],
  "references": [{"id": "ref:1", "fromUnitRef": "unit:1", "originalLabel": "SCT clause ...", "targetNamespace": "SCT", "targetSourceRef": "src:sct", "candidateTargetRefs": [], "status": "unresolved", "evidenceRefs": ["unit:1"]}],
  "coverage": {"referenceRevision": "ref:r1", "inventoryRef": "artifact:all-input-members", "dispositionsRef": "artifact:all-candidate-dispositions", "complete": false},
  "issues": [{"id": "issue:1", "scopeRefs": ["group:A"], "code": "GROUP_COMPLETENESS_UNVERIFIED", "status": "open", "evidenceRefs": ["unit:1"]}]
}
```

DOCX/PDF locator在实际契约采用互斥shape：DOCX保part/path/node/hash/channel/UTF16；PDF保physicalPage/frame/bbox及glyph／region原始引用。上例不以虚构页号补DOCX，也不以原PDF页号冒充Word编辑锚点。

候选变量session取证时，从此投影装入完整相关guidance／父条／选项／续文、各要求来源、已有变量／类型参考的只读候选和具体unknown；输入跨文件材料保持同一项目profile。历史图谱只作比对参考，不能替代本版原文。证据和task封套可缩包装，原文条件、缺证状态和成员完整性不能裁掉；生成budget耗尽与输入不足分别报告。

## 5. 可执行任务次序与完成条件

建议每批一个owner，先完成当前批可核证据再推进后续。任务ID为本交接局部标签，关联原DYN／ENG，不替换它们。

| 次序 | 工作与交付物 | 依赖／完成条件 |
|---|---|---|
| TP-00 身份与体检 | 固定代码HEAD／dirty快照、四源manifest、现有接口／消费者能力账及唯一owner；区分read/analysis/edit能力 | 对应DYN-00／01／02；四SHA核对，缺资产明确列出；每个必需字段都有证据或缺口 |
| TP-01 参考分母 | 建全source-member inventory与独立参考：所有story／正文／表／空结构／66页、guidance／markers／空写区／指令／非编辑负例 | 分母从原件独立建立；旧97／100／74／155仅对照；双作者复核原文及布局关键处，未核项不填零 |
| TP-02 正常链路基线 | 经当前正常入口保存未修结果、原始provider/native输出与canonical消费投影，逐字段比参考找首个丢失层 | 真实parser/OCR只在本次实际授权和有限预算下执行；缺运行条件不阻塞离线契约，但不得称来源已测 |
| TP-03 内容与结构补缺 | 只修首个丢失层的原字／编号／表拓扑／空写区／native锚点和字段贯通；交最小代码、调用者与契约测试 | 对应DYN-03／原ENG；保旧正确及错误历史，native/raw/candidate分层无退化 |
| TP-04 条款与范围组装 | 建本版层级／引用视图、全部处理点处置、完整选项／跨页／跨表成员与边界证据 | 对应DYN-04／05；每个参考处理点与组都有下落；未知组不可作为完整自动编辑输入 |
| TP-05 版本与图谱接缝 | 完成两个SCCprofile隔离、新旧对应候选及最小图谱证据契约；验证session实际收到完整证据而非旧catalog摘要 | 对应DYN-02／06→07–09；来源变更／错版／旧generation／未闭引用均有拒绝或局部unknown；不自动采用 |
| TP-06 自动化与全源复验 | 聚焦反例、适用Java/Python代码检查，继而同输入完整原件前后比较、负例及旧正确保持 | 完整分母／失败／unknown／未执行一致保留，关键字段零错漏；来源质量、模型语义、业务／导出分别判门 |
| TP-07 独立交付 | 不同作者按冻结原件与实现输出复核；交能力账、diff、运行身份／测试、全源逐项账和剩余门 | 软件green不覆盖来源缺口；未完成真实效果／页面／编辑项明确列出，不擅自complete或部署 |

准备阶段可只读并行：Word native内容／布局、PDF版本／跨页组、契约／测试参考。共享DocumentParser／canonical模型由单owner集成。实际代码修改前搜索全部调用者和现有测试，运行方式读取仓库当前构建配置；不在活动服务使用的target目录覆盖运行文件。

## 6. 测试矩阵和独立验收

使用自写小模板构造变体完成代码反例；真实原件与人工参考留在忽略目录。开发暴露样本如实标development，另有未用于规则选择的模板／文档族验证泛化；无法取得独立来源时明确不授独立泛化门，不把重排／改名的同源文件称holdout。

优先沿现有测试接缝补行为反例，不另建演示parser来通过：

- `ai_consense_service/src/test/java/com/consense/document/`：`DocxStructureIntegrationTest`、`DocxCellTopologyTest`、`DocxNumberingResolverTest`、`WordNativeObjectsTest`、`WordSymbolDecoderTest`、`OerNativeDocxObservationTest`、`OerNativePdfObservationTest`、`OerEngineIntegrationTest`、`DocxTemplateEditorSafetyTest`。
- `ai_consense_service/src/test/java/com/consense/service/sources/`：`NativeDocumentCollectionContractTest`、`NativeCompleteCollectionContractTest`、`NativePdfCanonicalContractTest`、`NativePdfSourceFamilyContractTest`及相应normal capability／service测试；覆盖完整成员、丢批、错版、逐读当前身份。
- `ai_consense_service/src/test/java/com/consense/service/drafting/`：`DraftingTemplateReadingIntegrationTest`、`DraftingTemplateSourceCompatibilityTest`、`DraftSourceContextOrderedListTest`、`DraftRoundThreeNativeTableColumnRolesTest`、`DraftSourceBillIdentityTest`；旧来源acceptance类按本次环境和资产可达性分开报告，不用删除／skip换绿。
- `ai_consense_service/tools/vetting_eval/`：`test_structured_parser_evidence.py`、`test_complete_ocr.py`、`test_native_collection_complete_wire.py`、`test_native_pdf_source_family.py`、`test_native_pdf_producer_replay.py`及正常入口相关反例。纯fixture／回放通过不等于真实provider或原页质量通过。

| 挑战族 | 必须保留／拒绝的结果 |
|---|---|
| 原字及多通道 | 星／井跨run、tab/break、nonbreaking字符、symbol字体、OMML、插入／删除／strike、隐藏／继承状态；原字回取／offset一致，未解析字形或有效态保unknown，不拼重叠通道 |
| 层级及编号 | 手写号／numPr／样式继承／重启编号、重复标签、目录同名、条号变但正文同、同号正文变；父子与正文边界正确，歧义不强配 |
| 表及空写区 | NTT三列/SCT二列既有样本，加列数变化、合并／嵌套／无边框表、空行空cell、下划线／点线／控件；不把邻列指导接到错误正文或压掉空字段 |
| 无标记指令／假阳性 | 全部guidance、仅Notes外编制建议、普通星号／目录引导线／固定期限／履约正文；候选都有来源和处置，不为凑字段数增问题 |
| 原文未闭合／缺证 | NTT-GN011保所有已存原文和sourceSyntax缺陷；capture完整与scope未知区分，不补字或静默裁尾 |
| 完整组跨页／跨表 | 指导／选项跨分页，重复表头页脚、续表／独立邻表、共用前缀尾段；所有成员／边界与缺口逐项可核，只删标题为负例 |
| Word质保 | 安装存在、质保要求、Specification指定区域、三类成员与重复引用分别可达；保and/or与局部范围，不能合成一个boolean证据 |
| NTT/SCT联动 | 电子发放与BQ=L10Pro条件独立；SCT5双信封、SCT6删留、重新字母及SCT引用保各自粒度；bill完整集合与各专业引用不合为同一个值 |
| PDF4.2→5.2 | 本版A(1)–(8)／B(1)–(9)完整跨页成员与引用；数字是人工核此版的参考，生产发现靠结构；双信封不能代答分包资料／经验是否要求 |
| PDF8.2→22.8 | 本版8.2实际采用、铁路／NSC不同成员及反向引用；局部footings星号不扩大到piles／pilecaps／整条，禁止借Word8.304／22.307套编号 |
| 来源错版／失效 | Word/PDF SCC同标题同号／不同对象、同名文件换字节、parse generation变更、缺源／重复hash引用；拒错版绑定，旧结果stale保留，其他已确认来源不被抹去 |
| 容量和失败恢复 | 完整组超过传输／上下文边界、缺批／重复批、OCR/provider失败、取消／晚到；whole-group保持unknown／失败，页数／字数增多不冒充质量通过 |
| 旧正确／下游隔离 | 既有模板正确文本／拓扑／编号／位置保持；模板证据不混入Vetting/Advisory索引，原件／旧gold／fixed05与其他运行不变 |

关键验收规则：

1. **逐项可重算**：冻结原件、参考修订、代码／parser／模型／配置身份、全部源成员和候选分母；每项给before/after、定位、原文、期望、观察、处置。源语法缺陷、missing、unknown、not_run、failed单列，不能移出分母换绿。
2. **关键字段零错漏**：经独立原件核定的条款号／层级、否定与限定词、数值／单位／日期阈值、指导所属、marker/span、目标成员、跨文件命名空间和当前来源身份，要求零误读、零遗漏、零错误归属。未知来源不能强造正确；仍有关键可核缺口时来源门保持未过。
3. **完整范围零误扩**：所有已知目标成员／整组选项完整，旧正确成员保留，负例非成员零误吸入；不允许“覆盖了所有已找到成员”替代完整参考分母。每个自动可用group都须有完整性证据；unknown group保持analysis-only。
4. **同分母比较**：输出整体及NTT/SCT/Word-SCC/PDF-SCC分别的正确／遗漏／改字／层级错／错归属／误增／unknown／失败／未执行，以及旧正确→保持／回退。人工修正和原始正常输出分开，局部正例不能替代全源。
5. **分层报告**：代码单元／契约／集成通过；正常parser/OCR实际执行；原件保真；变量／链语义；真实页面／原件编辑及导出分别记录。模型JSON合法、coverage.complete、原文长度或程序exit0不能关闭来源／业务门；源解析目标完成也不授整张图谱或27B能力通过。

## 7. 最终交付清单

提交最小实现与适用测试；同时保留私有本地的manifest、能力账、完整reference／实际输出、分母及旧正确差异、原页／原生锚点抽查、失败／unknown账和运行receipt。代码库只放不含原件的契约、合成fixture、验收方法与摘要。交付摘要必须明确：哪些接口复用、实际改了哪一层、哪些源／组已达到关键字段零错漏、哪些未过、是否具有真实编辑能力，以及下一owner与依赖。

若某项能力已有且证据足够，直接复用并只做正常链路回归；若底层观察完整而Drafting投影丢失，修消费者；若原生／OCR原始输出已丢失，返回原ENG修复。任何情况下都保本版原文、范围和失败，完整图谱只能建立在这些可核证据上。
