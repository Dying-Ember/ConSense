<!-- oer-guidance-pdf-producers-I48-20261019:start -->
**I48：指导语证据覆盖 PDF 来源与全部 canonical 生产者；Word 成员 id 经 retainedBlockIds 映射到 block id，成员命名空间不可比时显式声明，不按页或相似度推断关联，不改契约、不重生成，活动服务未部署。**

I47 只在 `normal_observed` 生产者的 Word 来源上命中。本轮：
- Word 证据成员是 coverage 段落 id（`docx-p:…`），chunk／pointer 用 `DocumentBlock` id；新增按 `retainedBlockIds` 的映射，命中后同时保留段落 id 供证据选择。合成夹具已改为真实 id 形态（`docx-p:g1` ↔ `g1`）。
- `analyseGuidance` 允许无 `nativeDocxParagraphs` 的 PDF 覆盖度（blocks 非空即可），并只在 fileKey 为 NTT／SCT／SCC 时运行；分析结果（含失败）按 source 缓存，避免逐 chunk 重算。
- `project()` 的 guidance 钩子移到 `normal_observed` 守卫之外：`native_pdf`／`family` 也会运行，身份仍按 `documentId`／`originalSourceSha256`／`javaParsedSourceHash` 复核，`parts` 空值与空白 blockId 均跳过。
- 显式状态：普通来源无命中不输出字段；canonical 无命中输出紧凑 `status=unavailable`；`native_pdf`／`family` 因成员命名空间不可比（`native-pdf-region:<sha>` 对 `pdf-page/<n>/line/<k>`）→ `reason=producer_member_identity_not_comparable`。
- 未覆盖：让这两类生产者真正命中需生产器暴露 Java 成员 id（契约变更＋重生成）；标准模板仍不进审查／咨询语料；相交仍是成员级、未做 offset 级裁剪；身份 pin 未含 coverage 代次。

验证：定向 88 项＝84 通过＋4 项原有条件跳过，0 失败；全量后端 3636 项＝3478 通过＋14 failures＋9 errors＋135 跳过，23 项环境失败与基线逐类一致。两个独立子代理分别核实了 PDF／生产者可行性与对 diff 的对抗审查；其 high／med 项（Word id 映射、失败缓存、逐 chunk 膨胀、空白 id）已修，offset 级与 pin 宽度作为已知边界保留。本轮只提交并推送源码／测试／文档，不部署。证据 `.local/oer-guidance-pdf-producers-20261019-I48/verification.json`；台账 I48。
<!-- oer-guidance-pdf-producers-I48-20261019:end -->
!-- oer-guidance-source-scopes-I47-20261019:start -->
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


---
title: NTT、SCT、SCC 模板专用解析与来源范围
date: 2026-10-16
status: source-adopted-r17; scan-source-quality-partial
scope: drafting template source parsing only
---

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
**2026-10-18 I35 最新状态：** 样板Word优先路由现保全独立PDF候选及完整已有解析；自动模板图排除未采用辅助源，同时保留显式来源阅读。SCC原生PDF补齐指导首项和两行换行的具名词组，8个逻辑出现／10个精确范围；三Word除r16代次及两个摘要外整值保持。旧r15作为历史保留，当前使用需显式重分析，活动服务未部署。SCT共同指导关联、7个未知marker、SCC p54及扫描验证边界保持。详细状态以[已确认规则](../oer-scope-and-rules.md)及`.local/drafting-template-stability-20261018/verification.json`为准；单本HTML为I35。
<!-- drafting-template-stability-r16-20261018:end -->


# NTT、SCT、SCC 模板专用解析与来源范围

本文件记录用户本轮要求的专用解析接缝、实际模板差异和验收方法。r15 已完成真实原件与独立源审，并精确采纳到主源码；活动服务和 JAR 未更新。证据与未完成项继续保在同一本 OER 台账 I30。依据为 [OER 范围与规则](../oer-scope-and-rules.md) 和 [模板证据交接](../HANDOFF-drafting-template-evidence-parsing-20261016.md)。

## 2026-10-17 收口状态

<!-- drafting-template-acceptance-priority-confirmed-20261017 -->
**2026-10-17 用户已确认验收顺序：先收口 Word 与原生 PDF 的解析质量，扫描 PDF 分阶段验证。** 当前验收不要求同步跑完 NTT/SCT 共52页扫描；已完成的NTT12页真实结果及缺陷保留，SCT40页尚未启动。每阶段分别记录实际覆盖、源质量、耗时及未完成，不以Word通过替代扫描版通过，也不由本次确认自动新增OCR、Goal或定时任务。

最终规则为 `native-template-rules-20261016-r15`。13 个 Java 文件与 1 个 Vue 文件精确采纳，完整 810 个后端源码/资源/pom 与实测隔离树相同；其余 797 个旧后端文件、293 个产品 Python 文件和 811 个 main target 文件保持，后者包含 453 个活动 classes/JAR。原有脏工作区按开始时的逐文件基线保留，不做 commit/push 或部署。

| 来源 | 指导捕获 | 全量源审通过的完整修改范围组 | 新增无局部标记字段 |
|---|---:|---:|---|
| NTT Word | 11/11 冻结组 | 9 | 本版新增字段分母为 0；跨表 A/B、同行分支及第二句范围另有完整组 |
| SCT Word | 44/44 冻结组 | 21 | 22 处，另有 1 个 Mr/Ms 文字选择候选 |
| SCC Word | 42/42 冻结组 | 33 | 5 处，包含 3 次同一 Tree 编号 |
| 独立 SCC PDF | 18/18 显式指导正文 | 不授整份 PDF 语义通过 | 修订边注另存为 21 个 context range；normal 仍 PARTIAL |

三 Word 合计 3,999 原生段和 157 个 ASCII 标记均与冻结源参考对应；当前所有 `scopeComplete=true` 的 63 个语义组及 337 个命名通道范围均逐源审核。63 只代表全部当前完整声明，不是所有业务决定的准确率。27 个新字段中，14 个 Bill 描述只授零长度插入锚点，13 个现成空白范围精确；所有值仍待项目来源。Bill 终号没有专属填写指令，保留未关联状态。

仍有 7 个 Word 标记为 unknown：SCT 的 Mr/Ms 选用、Bill/Bills 原文引述不一致，以及 5 处需要 PQS 判断的 footing 局部改动。另有 14 处较广/共同指导尚无直接条件依赖边，完整原文和上下文仍保留；不能把“没有错联”写成项目条件依赖图完整。项目适用性与自动编辑均未接受。

NTT 的 12 页独立扫描已实际 OCR 一次，正常调用 2,361.43 秒，24 个 fresh provider tasks（两提供者各 12，cache hit 0；不是内部推理次数统计）。旧主候选漏 2 个星号、公式漏 8% 项并误补源未闭括号；另一完整候选较好但未选用。r15 只对保存的完整结果复算证据，去掉 1 条普通引文误指导，保原件语法未知及 12 页双候选；除 `templateEvidence` 外整份 JSON 逐值相同，0 新 OCR/原件 parse。没有模型质量提升结论。SCT 40 页扫描尚未执行。

最终实际回归为 521 项＝487 通过＋34 既有/条件跳过，0 失败/错误；另一次真实三 Word 的隔离上传、读取、evidence 接口检查为 1 项通过、0 跳过，不重复加入唯一测试总数。27 普通 Word 的完整 `parsedDocument` 与 I29 逐值相同；3 模板只去两类可选新增 coverage 字段后也与普通结果整值相同。Vue 的隔离全项目类型检查通过；没有活动 API、浏览器、真实 Word preview 或业务验收。

本地完整证据入口为 `.local/drafting-template-routing-20261016/source-audit01/r15-final-independent-source-adoption-gate01.json`、`adoption-preparation02/adoption-plan02.json`、`adoption-preparation02/root-gates02.json` 和 `adoption-preparation02/execution02/terminal.json`。原 r13 测试失败、r14 独审拦截及其他历史失败保留，不用最终通过覆盖。采纳脚本只写上述 14 条源码路径；不进行模型部署/写操作，本轮没有模型权重逐文件 before/after 核验，不把“无写操作”当作权重逐字节证明。

## 输入身份和路由

同一次上传先保实际原件字节和 SHA，再按文件签名判断 DOCX 或 PDF。文件名和扩展名只是提示；实际内容决定 NTT、SCT、SCC family，用户选择的 family 与观察冲突时拒绝替换。已知评价答案、已填项目成稿、图纸和其他明确排除身份不能作标准模板主源。

同批先处理 Word。通过实际来源绑定的 Word 为首选；同 family 的不同 Word SHA 不按上传顺序选一个。不同 SHA 的主源替换必须走显式 replace；解析失败保留既有主源。写入前在短事务中重读当前来源 SHA 并锁定项目，避免两个上传同时覆盖。

PDF 没有已确认身份时先沿现有 OER observe 路径识别。身份仍未知的原件保留为待识别材料，不给它编造 family、解析证据或主模板地位。同批已有可用 Word 时，只有单一文件名 family 提示的 PDF 可先存为未选择的辅助候选，避免无需要地调用整份 OCR。这个状态不证明它与 Word 同版。

项目其他输入仍用普通 OER parse。专用入口是现有 `DocumentParser.parseTemplate`；正文、编号、字段、公式、表格和原生对象复用同一次正常解析。`OerTemplateEvidence` 只消费已有 blocks 和 coverage，不重新读 ZIP/PDF，不调用渲染器或模型。

## 每类模板的源结构和判断依据

以下数字用于本版独立审计分母，不能成为产品代码中的常量或新版的预期条数。独立原件、段落参考和页面只存忽略的本地证据目录。

| 模板 | 本版原生结构 | 指导语与标记 | 必须区别处理的范围 |
|---|---|---|---|
| NTT Word | main document 内 665 个原生段；主条号、正文和右侧说明常分处不同单元格；同一个条款可延续到下一物理表 | 11 组指导语；12 个正文 ASCII 星号/井号 | 整子条删除、跨表 Alternative、同一行中的独立 A/B 段、句内 G1/G1a 选项、仅第二句的条件修改 |
| SCT Word | main document 内 1,238 个原生段；主款、数字子款、字母列表、业务 Reference 列及嵌套表同时存在；缺少可直接授完整业务树的原生纲要 | 44 组指导语；43 个正文标记 | 整主款与子款选项分层；`#` 项完整行含 Reference；跨页 (4) 的 a–f 及子项；括号内几个词；外部费率核查；待填期间 |
| SCC Word | main document 内 2,074 个原生段；42 组指导语位于 body 段，不在 `w:tr` 中；含目录字段、自动编号及公式 | 42 组指导语；102 个正文标记；另有页脚字段指令中的星号负例 | 多个非连续显式引用、共享尾句、单/双星局部选择、安装名称后缀、专业与采用条款列表、重复 Section 空白、定义末句；目录副本须排除独立编辑意义 |
| NTT / SCT 原 PDF | 分别 12 / 40 页扫描件，原生文字为 0；这些本版页面旋转为 270 度 | 需对各自扫描原件实际 OCR 和逐页核验，不能从 Word 移植条数或范围 | 保物理页、原件 SHA、原图和页坐标变换；按现有视觉路由取得文字及布局；未运行的页和失败不能记作已解析 |
| SCC 原 PDF | 独立的 66 页文字层版本；与 Word 的版次、对象和条号不同 | 本版 18 处显式 Guidance 标签、61 个 ASCII 标记，均不等于全部业务修改点 | 同页边栏修订文字与指导正文可能混在一个文字 block 中；必须按精确字形来源切片分开，不能按 block 或字符串整行删除 |

Word 的 3,977 个 main 段加上其他 story 的 22 个段，共 3,999 个原生段均在独立来源参考内。空段、注释/脚注分隔符、页眉页脚和已删除观察通道仍保身份，不当成正文有效文字重复合并。Word 物理分页未知时保持未知；绑定原 Word SHA 的派生页面只是布局佐证。

独立原 XML 和页面续查另外冻结了 27 个没有局部星号/井号的字段：SCT 的两套 Bill 清单共有 14 个描述插入点，Bill 末号 1 个，Project Architect 所在行有职位后缀、姓名、电话 3 个，两处地址各有 floor/Block 共 4 个；SCC 的 Technical Proposals 定义有 Envelope/SCT 条号 2 个，Old and Valuable Tree 编号有 3 个重复空白。这个新增分母是字段范围，不是新发现 27 条指导语；三处短旁注本来已在冻结 97 组指导内。

Bill 描述在破折号后没有现成文字，输出应是零长度插入锚点，不能假造完整替换范围或假定未来只有七行。Tree No. 指令明确指两个子条，其中第二子条的 Provided that 续文还有第三个空白；下一条 SCC22.302 不属于该字段。每个字段继续保指导原文及关联依据，填写值和项目条件另待项目来源。

NTT Word 与 PDF 即使都写 March 2020，技术支持联系方式仍不同。SCC Word 与 PDF 的年份、专业分包对象及条号也不同。来源不能因文件名、family 或同名条款而合版。

## 指导与目标如何关联

先独立枚举指导原文和字面 marker，再建立关系。每个 marker 只有在完整源证据足够时才绑定目标；出现在指导文字、目录或域指令中的星号不能直接变成业务变量。项目条件真假与源范围是否可确定分别保存。

| 源指令类型 | 范围证据 | 不能作为完成依据 |
|---|---|---|
| 整条/整子条删除或 Not used | 明确主款/子款、指导所在容器、同层下一个边界、全部内部成员及续文 | 只取含标记的一段，遇表尾或页尾就停止 |
| Alternative / Option | 本版 owner、选项说明、正文成员、续页重复标签、带说明的行拓扑；同行多分支需额外一一对应证据 | 整行或整表一锅收；仅按相邻顺序或某个重复关键词配对 |
| 显式非连续引用 | 指导逐个列出的原文引用、各自完整源单元、父上下文和共享尾文 | 取最小最大段号之间所有内容；把引用单元范围等同于可删除整单元 |
| 句内单词/句子 | 指导明确引用的词、标记相对位置、指定通道内精确 UTF-16 切片 | 把整段、括号或前句一起变成删除目标 |
| 斜线与单/双星选择 | 完整源选项组、标记组、指导条件；分隔符和标记清理候选单独保留 | 直接替项目选一个值；截取未支持的词串前缀后声明 complete |
| 待填空白/期间 | 原空白位置、明确 fill-in 指令、指令限定的子条或行、同源重复出现关系 | 扫整个主条，把没有被指令指定的其他空白也纳入目标 |
| 专业/名称/采用引用列表 | 指导限定的定义、专业或命名空间与精确列表切片；其他正文是上下文 | 只因一整段含有多个 SCC 引用就把它们全部纳入可修改范围 |
| 外部核查 | 原费率/金额、同源 check latest 指导及查询要求 | 把现有金额当作已经核验的最新值，或当整段删除指令 |

特别已亲看并核 XML 的例子包括：NTT *2 Alternative (a) 跨两张表；NTT3(c) 的两个 `*(c)` 同行不同段；NTT13 的星号在第二句前；SCT5(4) 的 A/B 跨页且含 a–f 和业务 Reference；SCC7.304 的九处非连续引用；SCC Railway Protection Area 定义的最后一句。页面及原段身份保在本地 source-audit，不在代码中写死这些段号。

## 输出与消费契约

专用 parse 的可选 coverage 新增原生段清单和 `templateEvidence`。普通 parse 不输出这两个新字段，原结果应整对象保持。

证据包含实际来源 family/SHA、分析 revision、所有源成员、完整指导原文、marker、条件原文、范围组、上下文、开始/结束依据、未知和冲突。Word 的原 part、Clark 路径、story、原生 XML/hash、原生行/单元格关系与四种既有文字观察通道均保留。格式化后文字偏移不能冒充原 XML 编辑偏移。

空格填写区额外使用同一次正常原生遍历中、压缩空白之前的四个解析文字通道。该观察保原连续空格，但仍包含解析器既有编号投影、控制转换和段落换行；不是直接拼接 XML 文字，也不是原件编辑坐标。所有范围必须声明所属通道，不能把独立 XML 审计里的偏移数值搬到解析通道。

至少分别报告：

- `captureComplete`：当前指导的来源文字成员是否完整观察。
- `sourceSyntaxComplete`：原文括号等语法是否真的闭合。原件未闭合不能替它补写。
- `scopeComplete`：声明的源目标是否有完整范围证据。
- `semanticApplicabilityVerified`：项目条件是否已确认。本轮保持 false。
- `automaticEditEligible`：能否直接执行原件修改。本轮保持 false。

`requireBound` 复核实际保存原件 SHA、family、当前完整 blocks/coverage 输入摘要、分析 revision 和整个 evidence payload 的 generation 摘要。旧 revision、自洽但不同输入的 payload、实际原件字节变化均不能授当前证据通过。

只读 evidence API 返回已经保存且绑定有效的证据；GET 不重新 parse、不调用模型、不改变项目。解析返回 PARTIAL 时可以原文阅读并显示待复核，生成前置门仍保持原有要求。

## 验证和未完成项

每轮分别保存适用代码测试、真实原件 producer 完整 JSON、冻结独立参考、来源范围独审、运行时间、内存峰值、失败和进程释放证据。合成测试通过不能替代原件范围验收。

回归必须包括 27 份普通 Word 与先前 I29 完整 `parsedDocument` 的逐值对照，以及三份标准 Word 去掉仅两个可选新增 coverage 字段后的整对象保持。真实三 Word 的上传/读取/evidence 接口在隔离 H2 中另外验证，不称为活动 API 或前端业务验收。

PDF 的原生字形投影保持 rawText/rawMemberRanges，正文和边栏各有精确来源切片。没有字形、坐标或同页映射证据时保持未评估或 unknown；OCR region 不能伪装原生 glyph。跨页几何和未持久化的 frame 不自行推定。

扫描件的两个完整 provider 候选分别保留，未有经独立来源评价的通用规则时不按某一文件、页码或一次较好结果选默认引擎。旧主文字可能只是 legacy 候选，必须显示待复核。OCR 自己输出了右括号只证明观察中有该字符，不能证明扫描原件语法闭合；观察语法、原件语法和原图源核分别记录。本轮 NTT 原扫描的一次真实 OCR 已返回，发现旧主候选漏两处星号、公式漏 8% 和补右括号，不能记为整体扫描质量通过；同次另一候选保留了更好观察，不把它自动升格为已采用结果。

源语义确实不足的情况单列：PQS footings 指令未指定精确修改起点、指导 Bills 与正文 Bill 不一致、原文指导未闭合。保完整原文、候选和原因，不能通过改 gold、近似匹配或把未知范围改名完整来消掉。

完整来源保全、指导捕获、精确修改范围、项目条件、自动编辑和端到端业务是六个不同层面的结果，分别接受，不互相替代。
