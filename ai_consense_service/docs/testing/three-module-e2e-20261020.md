<!-- user-sl-package-and-push-20261020:start -->
**2026-10-20用户最新确认：SL技术部分的比赛目标，是把所有版本的高质量解析结果及数据库完整打包带入比赛；不能只带最新版本或部分开发样本。**

各版次、原件格式与源修订保持独立身份，逐版本盘点原件SHA、解析／canonical修订、质量／unknown边界、数据库与CAS／索引恢复关系。已接受的结果与尚有PARTIAL／未完成区域分别标明，不能把有价值的局部结果丢弃或把未验结果包装成高质量通过。沿既有处理链和数据库／离线包机制补齐，图纸排除、扫描分阶段与不猜合版的既有范围保持。版本分母及具体缺口见[SL技术全部版本离线包清单](../specs/sl-technical-offline-package-20261020.md)；该清单先盘点当前实际证据，未实际打包／恢复的项目不得称已可携入比赛。

用户同时明确授权“这轮完成后先提交并推送到远端”。本轮可交付修改验证、统一集成后先做一次Git checkpoint，不等待统一Goal全部完成。先核分支／远端／变更归属／必要检查及密钥排除，只提交代码、测试、文档和可版本化打包清单；原件、数据库、模型、密钥与大体积运行证据保留忽略的本地目录。先前“No commit/push”记录属于旧授权时点，不再阻止此次明确请求；仍不授权force push、reset、生产部署或中断受保护H2 owner。
<!-- user-sl-package-and-push-20261020:end -->

<!-- unified-progress23:start -->
**2026-10-20：30路径候选已精确采用主树；新统一后端127/127及同源JAR通过，前端210/210、类型检查和生产构建通过。Drafting正常三DOCX＋NTT预览／正文编辑通过，11页身份／页码可见结果逐页正确；Vetting reader04新Word40页／native42页全部亲看，来源标题及17主题分页缺陷已修。**

127是本次统一组合，不替代全量套件或真实provider／浏览器／业务验收；原42回归和归档1另有独立票。Drafting预览102.969秒、24856输入pin保持、1转换、自然Job0；preview/export同字节、编辑仅改document.xml，SCT/SCC／2b／长身份等尚未通过。Vetting保16原finding／69人工记录／47引文，最新页面中copies自动理由与Checklist要求的相关性仍需追实际packet；当前APP-I-002=Generally/1、APP-I-007=该第15页Generally/2，旧初步定位不能混用。

SL用户要求覆盖所有版本高质量解析成果及数据库，七已取得来源及22214技术页位置逐版登记，完整解析／DB闭包／比赛离线恢复尚未完成。Qwen本轮0新真实请求，上一27B业务76/100 FAIL保持；VT02首阶段仍候选、后半未接。原件／DB／密钥保持本地忽略目录，受保护H2及正式服务未启停。按用户授权先做本轮commit/push，统一Goal继续active。逐项证据和本轮前端失败修复见[统一检查点](three-module-checkpoint-20261020.md)；以下22及更早块保留历史时点。
<!-- unified-progress23:end -->

<!-- unified-progress22:start -->
**2026-10-20：Vetting reader03 Word40页／原生PDF41页已实际渲染并逐页亲看，内容保全保持，但发现孤立来源标题、审查主题跨页长段落及一项Checklist理由相关性待核，整体读者／业务尚未通过。Drafting预览03实际1failure，定位为验收配置误用已知不支持版本探测的Windows soffice.exe；正式配置原本已正确使用soffice.com。**

Word渲染34.016秒／40页、native raster6.093秒／41页，分别自然Job0、979pins保持、Job24GiB；原16finding／69人工记录／47必要引文保持。独立逐页票为 `advisory/reader03-native41-visual-review01/native41-page-review.json`（41页）与 `vetting/duplicate-publication01/reader-report-presentation03/reader-artifacts03-highcap01/drafting-word40-visual-review01.json`（40页），均在 `.local/e2e-20261020/` 下。native34/35/36的来源标题与首引文分页，40–41的17项主题仍为长段落，reader04在独有候选中修呈现；Word15 Generally/2自动not-fulfilled的two/three-copy理由不足支持对应条件指令检查，原因待源／绑定复核，不能据图片判定模型或adapter层。原16候选法律等价关系仍unknown，禁止自动合并或改人工记录。

Drafting terminal02因conhost预算1过窄而0JUnit中止，5held已退出且原失败保留；依据完整调用链把总预算改为1Java+4版本+1转换=6后，terminal03已实际186.563秒／单方法1failure／0error/skip／自然Job0／24834pins同。接口返回4013 CONVERSION_TIMEOUT，只有空profile-probe且无render目录，确认未进入PDF转换。既有document-engine-decision已明确Windows接受入口为soffice.com、exe版本识别会超时；此次属于测试配置准备缺陷，正在复用既有.com接受凭据作最小纠正，不再用整轮试验猜入口。三份正常DOCX生成通过保持，预览／正文编辑／逐页仍未通过。

VT02身份审查第一阶段已在私有候选实施共享只读resolver、绑定元数据adapter及纯协调器，尚未编译／执行／采纳；Writer与reader04按明确先后串接。Qwen观察31及采用清单已备，未新部署／真实ask；原业务FAIL保持。后继JVM继续16GiB堆／组合Job24GiB，不重跑已绿分母；正式16与受保护H2 owner均未启停，全部Root已启动窗口已接回终态。统一Goal继续active；2b／长身份／最终整包逐页／三模块最新浏览器／复杂PDF及分阶段扫描全部保留。
<!-- unified-progress22:end -->

<!-- unified-progress21:start -->
**2026-10-20：后继JVM统一-Xms1024m/-Xmx16384m；有组合Job内存限制的JVM／renderer窗口提高到24GiB。Vetting reader03已实际完成单轮37项回归全通过及另一次归档生成1项通过，原16发现／69人工记录／47必要引文保全。**

37项实际40.156秒、3884pins保持；归档1项实际35.641秒、3880pins保持，均exit0／自然Job0／0failure/error/skip，两个分母分列不累计为同一轮。Root独立重算归档原finding全字段（仅剥新增container）、job／coverage、人工Checklist，Word47引文逐字逐序、nativePDF47次真实出现逐序保持。归档nativePDF实际41页，尚无Word渲染或逐页视觉／法律业务通过；16候选是否独立法律问题仍unknown，不自动合并。原技术状态／裸body定位显示已修，121处正式归属未知限定仍保留；1处句间缺空格登记为编辑残余。接受票分别为 `.local/e2e-20261020/vetting/duplicate-publication01/reader-report-presentation03/bounded37-highheap01/root-acceptance01.json` 与 `archived-reader03-highheap01/root-acceptance01.json`。

全部Root已启动窗口已接回终态，旧7822在飞登记已清。Drafting正常三原件DOCX高堆通过保持，预览／正文编辑待terminal02同handle终态观察修正完成独审后单独释放；新Vetting Word render／native raster亦准备独立窗口。当前正式后端本已16GiB堆，不为资源改动重启；受保护H2 owner保持。Qwen31代码通过但尚未采纳／部署／新真实ask，原业务FAIL保留。统一Goal保持active，2b／长身份／最终整包逐页／VT02身份一致性／浏览器／复杂PDF与分阶段扫描等未完范围不缩减，旧低堆失败不改、不重跑已绿测试、不做人为低堆探针。
<!-- unified-progress21:end -->

<!-- unified-progress20:start -->
**2026-10-20：已按用户要求实际运行16GiB后继JVM。OER流式canonical哈希6项精确等价回归全通过；随后同三份NTT/SCT/SCC真实原件的正常生成链实际通过1项，三份DOCX、身份快照与账本均已导出并独立核对。**

canonical6实际32.781秒/6pass/0failure/error/skip/4799pins同；正常三文件实际74.219秒/1pass/0failure/error/skip/4794pins同，两者均自然Job0，Xms1024m/Xmx16384m。Root复算三份导出SHA、所有ZIP部件、story current/compiled/source哈希与三份共同输入快照，确认采用的测试身份贯穿三文件，源styles/fonts/numbering保持，69份实际产物保留。该证据限隔离MVC/JPA、真实原件及DOCX，不代表网络HTTP、浏览器、PDF逐页或法律业务通过；来源文件仍2a三模板，2b和其他身份长度等范围未完。

当前唯一Root在飞为Vetting reader03的37项16GiB回归，session7430，未得终态、不得重跑或先报通过；Drafting预览/正文编辑高堆包已备，须另行Root释放。新的JVM资源策略保持Xms1GiB/Xmx16GiB、组合renderer有cap时24GiB，取消人为低堆探针。Qwen31代码通过但尚未采主树/部署或真实ask，原业务FAIL保持。三模块/OER全部未验范围、历史失败和人工决定保留，Goal继续active。
<!-- unified-progress20:end -->

<!-- unified-progress19:start -->
**2026-10-20用户更新：后继本任务测试JVM统一-Xms1024m/-Xmx16384m（16GiB堆），不再用小堆反复试验；包含JVM与renderer并有Job内存上限的窗口使用24GiB总额。旧2GiB执行记录和失败原样保留；取消人为128MiB OOM探针，不改受保护H2 owner。**

Root实际读取主机总127.7GiB/当时可用78.67GiB，当前正式后端本已-Xmx16384m，无需为资源重启。600秒/fork0/单Root JVM、原件与来源/内容/自然退出校验仍保留；后继计划和runner预算需同时改为16384，不能只换Java参数或改历史票。Drafting单文件流式canonical哈希修复继续以原指纹等价为门，用充足内存推进同三原件正常链，不将旧2GiB失败直接归为部署失败。

Qwen GREEN03已实际31.719秒、31唯一方法全通过、0failure/error/skip、自然Job0，1014新pins及1925旧REDpins独立重核保持；仅一行全限定类型名修正，旧RED02双精确failure和GREEN02编译失败保留。候选尚未采主树/部署，真实27B业务FAIL不追认。Drafting身份40已过但正常三文件仍失败待后继；Vetting reader03两项内容保全Must已静态修正，37回归/归档新输出及逐页视觉未完成。Goal继续active，三模块/OER全部未验范围保持。以下18及更早块按当时状态读取。
<!-- unified-progress19:end -->

<!-- unified-progress18:start -->
**2026-10-20：统一Goal保持active。本轮Drafting身份资格单轮40全部通过；但随后正常三文件generate在隔离2GiB JVM实际堆溢出，三文件/预览仍未通过。Qwen观察双目标RED02精确复现且自然Job0；GREEN02编译缺LlmProfiles名称，0JUnit，不计31通过。**

Drafting身份40实际56.922秒/0failure/error/skip/4788pins同，主产品与64相同；normal3files实际65.328秒/1failure/自然Job0/4789pins同，POST generate返回5000，首个OOM在OerTemplateEvidence.inputSha完整canonical JSON字符串物化。已转同一OER链内最小流式哈希候选调查，不靠提高堆或跳过原件通过；所有部分产物和失败保留，previewbody未释放。

Qwen RED01产品目标已命中但runner终态观察失败，原票保留；后继RED02实际30.109秒/2精确failure/0error/skip/自然Job0/1925pins同，确实观察1次held handle pending到自然0。GREEN02实际16.969秒，缺import编译失败，准备仅名称解析修正的GREEN03，复用RED02证据不重跑RED。Vetting reader03两份未执行候选又查出渲染后去重误吞不同位置字段、删除具体未审主题两项Must，正在保全原字段和短主题范围；整体报告仍FAIL。三模块/OER/最终整包、真实模型、浏览器、复杂PDF及分阶段扫描范围全部保留；本轮0新模型/共享HTTP/SQL/服务启停，H2 owner未停。以下17及更早块按历史时点读取。
<!-- unified-progress18:end -->

<!-- unified-progress17:start -->
**2026-10-20：统一Goal继续active。Drafting正常生成链的页眉页脚集成候选已实际单轮64方法全部通过，0failure/error/skip，含5个真实原件守卫；尚未采主树或部署。Vetting新版Word/PDF已实际生成并保全47份必要引文关系，但读者整体仍FAIL。**

Drafting本轮46.640秒、自然Job0、5711输入pins独立重核保持；64包含原60与新4consumer，不与旧60重复相加。Vetting32唯一方法由原28通过与显示断言修正后补跑4通过组成，不能称单轮32绿；另归档生成1通过，16原发现字段/人工Checklist保持，Word47引文逐字逐序、nativePDF47均可提取。新nativePDF46页、Word未渲染。只读查读仍发现12处Unknown status: out_of_scope、1处未绑定body/3和重复限定语，先最小修呈现，再逐页视觉验收，不把页数减少当质量通过。

Advisory新增Qwen实际请求实体观察仍是两文件候选及未执行测试包，未新增真实模型调用；此前73回归及源码采用不代表真实模型业务通过。所有Root已启动执行均终态；本轮未启停活动服务或受保护H2。下一步继续身份40、同有效项目身份的三文件生成/预览/编辑、报告读者修复及Qwen有界回归。SQL16后新增写入须在统一升级前保全；浏览器、跨文件身份、复杂PDF/分阶段扫描等范围保留。以下16及更早块按历史时点读取。
<!-- unified-progress17:end -->

<!-- unified-progress16:start -->
**2026-10-20：统一Goal保持active。Vetting读者候选已实际完成缺陷复现和首轮32项回归：28通过、4断言失败、0error/skip，不能称报告已验收。原技术字段直出已被新测试稳定复现；后继仅修正已查实的目录前缀及窄列换行显示断言，产品候选不改。**

RED01在JVM前因config缺四预算字段失败，0JUnit，原票保留；RED02实际32.109秒、1精确产品failure，GREEN01实际39.640秒、32=28pass+4failure，两次均自然Job0且2929/2947输入pins保持。新版归档47引文Word/PDF及逐页版式仍未执行/通过，读者整体仍FAIL。原Drafting11页定向空白修复、Advisory73采源码的范围保持；新增Drafting正常生成集成及Qwen实际请求观察均仅候选，未部署或通过业务。

完整证据见统一Goal本次增量与读者版式记录。全部Root启动已接回终态，0在飞Root模型/测试；活动服务和受保护H2未启停。SQL16后写入须在统一升级前保全。下列15及更早块按历史时点读取，原三模块/OER/复杂PDF/扫描范围不缩减。
<!-- unified-progress16:end -->

<!-- unified-progress15:start -->
**2026-10-20：统一Goal继续active。Vetting截图所示整体读者版式仍FAIL；已查明技术审计字段、重复意见和空人工字段直出，正在修主体及附录，尚无新版Word/PDF验收或部署。**

Drafting10真实NTT生成1项已通过，单次渲染18.875秒、11页；Root和独立代理均看全11页，确认09新增的空白第5页消除，页眉页脚及NTT/1–11保持。归一化正文与09一致不等于精确原文或全版式验收；原blank2、其他五原件、业务身份长度和前端集成仍未验。Advisory正式归属有限校验已实际RED73（65通过/8精确失败）→GREEN73全通过，并精确采入源码，未部署、未重新调用真实模型，原业务FAIL保持。正式16及受保护H2 owner保持，后继升级仍须保全SQL16后的写入。

详细证据见统一Goal本次增量；读者要求见 `testing/vetting-report-reader-layout-20261020.md`。以下14及更早块按历史时点保留，不缩减原三模块/OER/浏览器/复杂PDF和分阶段扫描范围。
<!-- unified-progress15:end -->

<!-- unified-progress14:start -->
**2026-10-20：统一Goal保持active，正式16仍运行，三模块业务尚未整体验收。用户指出新Vetting报告像调试输出，整体读者版式明确FAIL，不能用0裁字等局部检查覆盖；正式五栏和附录均正在做最小读者投影修正，原始审计与人工决定保留。**

Drafting10已用同字节09原件输出复现目标RED；后继55项实际通过＋5项原件guard补跑通过，构成同336源码的60唯一方法通过凭据，原5skip及此前配置/编译失败完整保留，不能称单轮60绿。真实fixed NTT及空白页消除仍待生成/渲染。Advisory逐alias归属显示已实际63项通过并采入源码，未部署；明确正式条号的发布门仍候选，token及真实27B业务未通过。Vetting03已生成完整Word/PDF、JUnit1pass；原runner退出观察错误保持RUNNER_ERROR，独立产物审查不追认自然Job0。新Word61页/nativePDF60页已渲染，但整体呈现未接受，旧版进一步验收暂停，转用户最新读者版式修正。

详细状态及证据见统一计划本次增量；读者版式要求见 `testing/vetting-report-reader-layout-20261020.md`（testing目录内同名文件）。下面13及更早块按当时状态保留。后继升级必须重新保全SQL16之后写入，受保护H2 owner未停；最新整包身份/浏览器/复杂PDF和分阶段扫描等范围全部保留。
<!-- unified-progress14:end -->

<!-- unified-progress13:start -->
**当前增量（2026-10-20）：统一Goal继续active；正式16仍运行，三模块业务尚未整体验收。09页眉页脚候选已实际58项回归、1份原件生成和12页渲染；全部12页已看，但第5页无正文，仍FAIL且未采主树。Vetting report02实际因额外cmd子进程触发原有界监控而中止，未取得JUnit终态和完整PDF。下面12及更早块按记录时点保留。**

Drafting09实际GREEN58（含原54与新4，不能相加为112）、actual1均0failure/error/skip；renderer自然exit0、12页。源styles/fonts/pgSz/body margins保持，旧27entries中仅main/rels/types三项变化，新增header/footer，其余24项字节精确。Root逐页查看后另对同SHA PDF做12页geometry核对：项目页眉及合同号、完整HD控制码、March2020和NTT/1–12位置/字号一致；只有page5正文非空字符为0。票`drafting/native-format-candidate09/root-layout-review01.json`，明确layoutAccepted=false/mainAdopted=false。压力身份不是业务采用值；其余五原件、长名称/编号及MS Word尚未验。

Drafting空页定位到原静态页脚group4的source p202/p203（body直系p41/p42）。captureLayout→captureStories→applyLayout185→applyStories清空22的顺序，使原非空页脚段未进入既有empty-spacing处理，清空后仍占正文高度；相邻空段已压到1twip，紧后表格保留既有page_break_before。后继仅在经过安全门且成功迁移/清空的段落复用compactSpacing，不删段、不改边界或全局边距；仍候选准备，未执行。

Vetting report01实际1failure保留；其2074运行pins因collector跳过project目录没有覆盖914个pom/src，准备时曾比对不等于运行期pin证明。report02已改为2043pins显式覆盖全部914源码，唯一测试适配独立来自已批准VT01 DOCX证据索引16行/48cells，生产相对B03无变化。Root唯一执行02后，actual Job发现java63728/FT134369639827721209、系统conhost53396和系统cmd62664/FT134369640153361963，超过原exact Java+conhost上限而RUNNER_ERROR。2043pins同，cleanup三held均wait0退出且0error；无JUnit XML、无自然Job0通过票。已产生annotated-findings、cell-preservation和report.docx三份部分产物，没有report.pdf/replay-receipt，均不算报告验收。票`vetting/duplicate-publication01/b-report02/generation/execution/owned-receipt.json`。正查PDFBox字体扫描的首个cmd来源，优先用标准配置消除子shell，不放宽为任意cmd、不改旧失败或自动重跑。

Advisory精确引用反馈40绿/采源码/未部署保持。逐alias既有ownerStatus/semanticStatus显式显示候选已完成静态审查，生产限AdviceService三显示hunk；实际21alias离线绑定为14unknown、4物理container、3物理story，全部semantic unverified，正式clause/heading保持null。新增约10765UTF8B不能视为实际token准入；公开/私有Java replay和有界RED/GREEN执行配置正准备，0新模型/HTTP/SQL。实际27B消息36仍业务FAIL，不能以离线候选追认。

运行环境仍后端18084/PID69780、受保护H2 owner18082/PID38980、前端5175/PID16852。本次仅新增上述单次owned JVM，0模型/共享SQL/HTTP/renderer/服务启停/主树产品写入；执行session27721已终态，0在飞Root执行。上一进度问答仅核状态属no-progress，本轮新增实际失败与空页geometry证据，下一步据此修复；Goal不缩范围。跨文件身份、最终整包/浏览器、复杂PDF和分阶段扫描未完，SQL16早于新增写入，后继升级仍须重新保全。旧五入口及execution-state原字节保存在progress-snapshot13。
<!-- unified-progress13:end -->

<!-- unified-progress12:start -->
**当前增量（2026-10-20）：统一Goal继续active；本机活动仍正式16／后端69780、H2 owner38980、前端16852，无新增真实模型、共享SQL或服务启停。Advisory精确引用反馈已实际40项通过并采入源码，尚未部署；三模块业务仍未整体验收。下面11及更早块为其记录时点的历史状态，不覆盖本段。**

Advisory tests03实际XML已准确出现4项=3个唯一marker的目标failure+unknown-alias1pass，0error/skip；Root逐项核类型、true→false及115/141/111源行。原runner在JVM已exit1后遇终态image查询WinError5，仍保存RUNNER_ERROR、自然Job0未观察，不能补造；两个此前held PID/FILETIME均cleanup wait0确认退出、无错，5593pins及共享owner保持。独立产品RED采纳票`advisory/formal16-nested-citation-feedback-tests03/root-product-red-acceptance01.json`明确区分产品断言与runner终态错误；没有重跑RED或改旧票。

后继GREEN04只执行原40方法：31.891秒／exit0，40pass、0failure/error/skip，6519pins同，最多1JVM+1held系统conhost，实际raw Job自然归零、cleanup无错、共享两owner仍原身份。仅终态parent已exit、同held PID/FILETIME且fresh handle wait0时可用此前已验证image补分类，原快照保留、不修改active计数；活跃或新身份仍拒绝。票`advisory/formal16-nested-citation-feedback-green04/green/execution/test-verification.json`。

Root已把两处最小生产hunk及两份对应公共测试精确采入主树，生产原CRLF保持，标准引用验证／metadata completion／accepted-prefix／最终一致性门和base system不变。现能准确向repair报告缺少哪些alias，且明确必须回原来源核实，不为过检补alias、删check或把fail/missing改pass。采纳票`advisory/formal16-nested-citation-feedback-candidate01/root-main-adoption01/adoption.json`。正式16不含此修复；实际27B消息36仍业务FAIL，raw76/100及条件／引用／正式归属缺陷保持，不能把40绿说成模型业务通过。

来源首损调查确认wherever applicable和独立同编号替代句完整送达实际[6]，已存system也已有条件／限定与repair重验要求；最早可证损失在模型概括未遵从。既有ownerStatus unknown／semanticStatus unverified在parts.readingContext及lossless wire保留，最早正式措辞升格在raw第1/2/4条。后继仅在原逐alias模型视图消费已有归属状态，不新建真源、不猜条号、不灌oracle，也不靠重复泛指令宣称修复。调查票`advisory/formal16-source-cause-review01/cause-review.json`。

Drafting body-only08后核补充：第5页只有旧手工控制码／日期，没有正文，12页也不是合格版式。09的第二份独立normalization诊断修正旧手工footer位置过滤，07和08的归一化正文流一致；该诊断忽略空格／标点，不能当exact保真或source等价验收。`drafting/native-format-candidate09/existing-render-body-comparison02.json`。source-bound a3/a5及页眉页脚距离候选、公测／实际原件测试仍离线准备，未采主树／运行。Vetting B03已通过的24项保持，下一步复用旧完整report生成新的Word/PDF；原16findings与69checklist、Comments17=16成员+末尾Review summary行，summary精确授权变化另列，不能冒称只有16条前缀变更。B仍未采主树／部署。

DF01人工确认34HTTP／7检查通过及其他原件／代码证据见11；最终整包身份、逐页版式、跨文件身份审查、最新浏览器、复杂PDF及分阶段扫描均仍未完成。SQL16早于新增写入，后继升级前必须重新保全。全部Root已启动执行已接回终态，无在飞模型请求。旧11头及六文件原字节保存在progress-snapshot12。
<!-- unified-progress12:end -->

<!-- unified-progress11:start -->
**当前进度（2026-10-20）：统一Goal继续active；活动后端仍正式16／18084／PID69780，受保护H2 owner38980及前端16852均保持。正式16的1315项／97类、升级后44项完整API比较与隔离恢复F成功为既有实际凭据，不等于三模块业务验收通过。**

Drafting固定身份的人工兜底链已在正式16唯一执行34HTTP（4POST／3PUT），7项检查通过且0unknown：固定变量可见、临时项目元数据不自动effective、手工采用不立即改项目、错误类型与两类stale CAS保持原值、显式confirm才同步项目并推进snapshot、最终重读稳定。票`drafting/formal16-identity-http-plan01/execution/result.json`；不是原件识别、Topbar像素、图谱或最终文件验收。新测试项目必须纳入下次升级保全。

Drafting native-static-inline04保持54项及六实际原件全pass／0error/skip；六原件为2a和2b各NTT/SCT/SCC。六候选273页程序检查不等于逐页视觉通过，旧1pass+5error与renderer终检错误均保留。combined07一次Editor合并185正文动作及28story动作，test1pass、渲染16页；Root已见短条款独占页面及大空白，版式未接受。新增body-only08与07同原件／同bodyPlan／同Editor、0业务与0story动作，真实test1pass21.75秒、185全APPLIED、672段、原非空正文保持；与07main仅两story refs及22精确static清空不同。另行单次渲染18.625秒、12页，输入保持、自然Job0。原件同renderer13页且第2页本空白，08不保留该空白页；页数不作为合格标准。控制排除bodyPlan单独导致16页，新story及static迁移仍需来源样式／距离修复。票`drafting/native-layout-body-only08/root-render-observations01.json`；只详看08第4/7/11页，非全12页视觉接受。a3/a5及header/footer距离候选仍离线未采。

Vetting A已采源码、13项通过，防不同数量／时间主张互吞，正式16未包含。B02旧RED为夹具error保留；B03仅修真实存储的合成原件夹具，原件守卫不绕过。B03真实RED1在两原始成员发布后命中SOURCE_CANDIDATE_CONTAINER_MISSING，34.156秒；经Root核完整XML后另行GREEN24全pass／0failure/error/skip，42.172秒，两阶段2930pins保持、自然Job0。GREEN包括A13+B8+报告3，实际16条旧输出全字段不变、1个精确来源容器、legalProblemCount=null，各成员与人工决定保留。票`vetting/duplicate-publication01/b-candidate03/isolated01/root-*-acceptance01.json`。B未采主树／部署，旧真实重复业务FAIL不追改；新版Comments增加来源上下文后必须重新生成及逐页检查Word/PDF，旧VT01的44绿／56页不覆盖B。

Advisory真实27B消息36仍业务FAIL：21来源送达但只发布limits，未回答核心优先次序；本次实际付费2preflight+2answer=4，完整保存／历史及账本核对完成。完整39105B base system重建命中实际保存hash2d617cb3f3da0c63fdcc73652301b40b55c875ef46e15f79ff118d71211762b2；一次repair另加既有repair contract，不冒称逐次wire独立观察。两次1497字分析相同，raw冻结评分76/100，仍遗漏wherever applicable、给同编号替代增加不一致前提，且部分引用不足；published必需解释FAIL，其他未执行条件UNKNOWN，不因沉默给PASS。canonical正式条款owner仍unknown，literal1/2/4不能升级为正式归属。来源/system/ledger票`advisory/formal16-post-offline-review01/source-system-ledger-review.json`。

Advisory精确missing-alias反馈仍候选。tests01在JUnit前runner误判conhost，原票保留；tests02修精确parent+至多1个held系统conhost后已真实运行，27.016秒、JVM exit1、自然Job0、3747pins保持、共享两owner原身份。实际4项全assertion failure，不能算预期3failure+1pass：unknown-alias已被更早JSON enum正确拒绝，但新增测试错期待后一道validator文案。Root票`advisory/formal16-nested-citation-feedback-tests02/root-red-reconciliation01.json`，GREEN未运行；独有tests03只修该测试及失败目标区分，生产两hunk／引用门不放宽，尚未执行。模型业务FAIL与source未知保持。

OER03分节、VT01旧报告版式、DF01严格身份来源及Vetting A为源码后继修复，均不在正式16。最终身份／逐页版式、Vetting跨文件身份、最新浏览器业务、复杂原生PDF及分阶段扫描仍未完成。SQL16早于新增问答与身份项目，升级前必须重新保全写入；本轮新增真实模型／共享SQL／服务启停均0，所有Root已启动任务已接回终态。旧10头及六文件完整字节保存在progress-snapshot11；以下旧状态按历史读取。
<!-- unified-progress11:end -->

<!-- native-red-and-provider-e08:start -->
**本轮实际执行：页眉页脚候选03 RED因夹具不合法失败；隔离数据库E已真实建立连接，但历史API来源与数据库来源比较失败。活动仍正式15，正式16未安装，Goal继续active。**

03仅释放RED一轮，实际20.141秒／exit1／自然Job0，21项为9pass、0failure、12error、0skip，全部冻结输入保持。12项均在POI装载合成Word时因`/_rels/.rels`缺content type提前失败，未到两个产品Must断言；不能算预期产品RED，GREEN／六原件阶段均未启动。原03源码、XML、日志及release保留；Root独立票`drafting/native-story-editor03/root-red-reconciliation01.json` SHA `3adc01b08407ce22736f320a766a90c2a67a5d746b5c04dba6fae2ca569e89ca`。独有`fixture-correction01`修合法OPC夹具，不改原失败；后继必须Root逐轮核原始断言及终态，再决定下一轮。

E只使用既有D mutable clone的独立H2公开SPI，实际10.297秒／exit1／自然Job0，1229项immutable pins前后相同；12条非法路径与8条派生操作边界实际通过，JDBC为1attempt／1established，证明该路径已越过readonly初始化问题。JUnit实际1failure、0error、0skip，在`inspectAdvice:191`的完整历史`sources`比较处停止；仅已生成session、messages33/34和有效属性导出，完整23项业务导出未完成，不生成成功coverage或放行升级。原A/B/C/D四次error继续保留；合计五个inspection tests为1failure、4error、0pass，不把文件观察／失败attach诊断混入测试分母。E原票`.local/runtime-preparation16/provider-inspection06/execution/owned-receipt.json`。

E允许H2仅在D工作副本内部初始化／关闭写入，初始hash `e579ef565e9384df6c0f5f8d1bd548d7c4a87be536ae743f53e0df0fface2556`，末hash `f2c02928c7539fe000ea766868ca63b8bc86990c553e7a9e421718357f927671`，大小均6645993472字节；immutable原A及所有历史票未变。现直接用E已导出记录离线定位API投影与存储JSON差异，不重跑E、不复制或恢复数据库、不改ACL、不连接共享库。活动后端44596／18084、H2 owner38980／18082、前端16852／5175已重核仍监听；本轮0新增HTTP／模型调用／服务启停。其余三模块业务、最终生成版式、浏览器、复杂PDF／扫描及整包身份审查仍未验收。
<!-- native-red-and-provider-e08:end -->

<!-- filesystem-and-native-review07:start -->
**最新状态：D工作副本检查仍1error，正式16尚未安装；已定位本机Java文件可写检查差异，正在准备仅隔离检查使用的后继E，未释放新数据库连接。** D实际8.906秒／exit1／自然Job0，1186个immutable pins保持，工作clone初末完整hash也相同；同样在H2初始化提交处报readonly store，完整23业务导出未开始。A/B/C/D四次失败均保留，不能出成功coverage或升级。运行后端仍正式15，H2 owner未停。

Root以单独128MiB/30秒owned JDK观察实际0.688秒exit0/Job0，0数据库连接／0数据字节写入；A与D均Files.isWritable=false、File.canWrite=true、DOS/volume readonly=false，D现有clone的FileChannel WRITE句柄实际打开成功。两文件前后完整hash均保持。公开H2 2.1.214源码FilePathDisk.canWrite使用Files.isWritable，FileStore.open155-156据此强制readOnly，直接解释当前rw URL也降级只读的触发。源码来自Maven Central只读sources归档，未替换运行库。观察票`.local/runtime-preparation16/filesystem-observation05/execution/receipt.json`。后继E拟复用H2公开FilePathWrapper SPI，仅对独有D工作目录按实际可写能力判断，限定路径并做原A／外路径／相对路径拒绝自检；不替换默认provider、不改ACL／生产H2、不再复制／恢复，全部23比较与导出门保留。E尚仅离线候选／计划准备，需Root审pins后单次释放；不得把准备当修复成功。

DF02/03候选02独审固定manifest a660c354…、Editor4095643a…，发现2项Must：跨段complex field的cached文字可被static pair清空；NO_TARGET/PRESERVE的page flag可授权清空／重置而不建立替代footer/PAGE。报告`vetting/native-story-editor02-review02/review.json` SHA `a41e50fb1654a3101267aaf18135bcc80aa02312a96b4a72f48c67a8cf52ca7d`，补查SHA `f746449bcea401ccda42f5ca34176e98d5295302a8dce8f42a00943a7626eb3f`。02快照和初始manifest漂移均保留；后继03新增两个针对性回归（预期21核心），准备同测试RED/GREEN及六实际原件第三轮的新有界方案，尚0JVM/renderer/生成物/主树写。最终artifact集成和逐页版式仍未完成。

Advisory离线ledger/full-system工具已交付：18项明确synthetic自测通过，旧消息34完整effective system重建精确命中实际hash40258ecd…；冻结16同题目／预期aliases的39105B候选hash2d617cb3…仅为候选，未作为新真实观察。交付`advisory/formal16-27b-application-plan01/ledger-preparation02/handoff-verification01.json` SHA `c2c0d528e138297d0fd58871ba191006ef9b6515fd79a51e72ddfaf39c3bf212`。实际before ledger与新27B ask均未创建；原D成功门方案保留，D失败后需另立E成功输入计划。本轮0新增模型／共享SQL／服务启停，Goal继续active，原三模块及OER全部未验范围保持。
<!-- filesystem-and-native-review07:end -->

<!-- adoption-and-restore06:start -->
**最新实施增量：VT01报告版式5文件及DF01身份来源资格2文件已条件采入主树，均未构建为后继包或部署；活动仍正式15，正式16冻结JAR不变。** VT01生产源码与green05精确一致，112张原有逐页图片及41个引用pins在采入前重核；采纳票`vetting/vt01-format01/root-main-adoption01/adoption.json` SHA `74e69ab20026ebaa306ebc54cc281285feadd2e7a1043653ac2dbafb72081acc`。新增私有replay guard已实际双入口编译：A给明确输入／输出时5pass/0skip，B不给属性时4pass/仅私有replay1skip，两JVM自然exit0/Job0；A收据误要求fork0 XML属性而报FAILED_PRESERVED，该原票保留、不补XML、不重跑。Root已执行独立只读重算，纠正票`guard-entry-replay01/independent-reconciliation01.json` SHA `cdbf6454a88a0ffc1738faf9cd3290c6d5a38e3db681f9b4c255702879d9bd88`全部字段／断言吻合。这仍不是新后端HTTP导出或业务通过。

DF01 provenance同48项实际RED21pass/27failure→GREEN48pass，均0error/skip；原9项身份／adoption回归保持。损坏／非数组／null成员／来源型空数组不会再降为空手工来源，身份显示needs_review并退出effective，Project确认受控拒绝，其他值及历史产物原始内容保留。仅身份入口收紧，普通宽容helper不变；明确manual true加合法[]仍可首次确认。采入产品源码与GREEN完全相同；公共新测试仅删除私有heap环境断言与两个imports，39业务case／断言保持，此测试适配尚未编译，列后继正式17验证。采纳票`drafting/strict-identity-provenance01/root-main-adoption01/adoption.json` SHA `e9dc219998f4e19d5a7f75c0a5e82ff43d0e964c3232e5ca23760ff81c83410b`。

SQL16隔离检查A/B/C实际均1error，完整23业务导出未开始，不能出成功coverage或切后端。A在恢复后只读重开失败；B最小URL7.906秒在初始化报`Schema public not found`；C保留创建时identifier设置仍9.719秒报readonly store，其完整原始栈明确是`Database.<init>(358) → SessionLocal.commit → TransactionStore.endTransaction → MVStore.commit`。三个失败、原库与所有pins保留，H2 owner38980未停。已改为准备额外独有可写工作副本，让H2只在该副本内部初始化／关闭；工具SQL仍限既有固定SELECT，不能称连接只读。Root唯一逐字节复制已完成51.062秒，副本6,645,993,472字节，初始hash及原A前后完整hash均`e579ef565e9384df6c0f5f8d1bd548d7c4a87be536ae743f53e0df0fface2556`，未打开数据库。复制票`.local/runtime-preparation16/clone-inspection04/copy-receipt.json`；D执行代码／pins仍待审查与另行释放，不重跑Script或复制，不改A、不连接共享库。

DF02/03 native story Editor同一capture/apply/write链候选与Advisory typed ledger/full effective prompt decoder正独立准备；均0新增模型／HTTP／共享SQL。本Goal仍active，原有三模块业务、浏览器、身份整包审查、复杂PDF／分阶段扫描等未验项完整保留。
<!-- adoption-and-restore06:end -->

<!-- runtime16-restore-and-report05:start -->
**最新进度：正式16代码／契约回归1315项全通过，但尚未安装，活动后端仍为正式15。以下旧日期状态保留作历史，不覆盖本段。** 升级前44条完整GET、SQL16及30份外部原件备份均已完成；SQL16隔离恢复A实际exit1，JUnit为1项／0failure／1error／0skip。RunScript步骤执行后，在只读重新打开现成库时出现`MVStoreException: This store is read-only [2.1.214/2]`，尚未执行完整23项业务导出，因此数据恢复验收未通过，不能称已恢复可用。

A副本6,645,993,472字节已保留，SHA `e579ef565e9384df6c0f5f8d1bd548d7c4a87be536ae743f53e0df0fface2556`；后继B仅准备复用该文件、最小只读URL及运行前后全文件hash，不重跑Script、不修改原A失败、不连接共享库。最小URL能否解决仍是待验证假设。恢复成功门已补齐23项导出、typed/null/hash、有效配置及XML终态，并以独立好／坏夹具复核；这是工具修复，不能替代实际恢复通过。活动H2 owner38980未停止，后端44596／18084与前端16852／5175仍监听。

VT01报告版式候选已到green05：44项／7类、0failure/error/skip；冻结英文报告的DOCX渲染与原生PDF各56页均逐页视觉通过，17条Comments与69条Checklist每格式逐cell／顺序无差异。修复涵盖11pt字体、双层合并重复表头、续页裁字、序号裂行、表格孤词、label与引句／段尾分页及来源位置人类显示；原finding/evidence/JSON/审计raw路径及原业务FAIL保持。交付票`vetting/vt01-format01/main-ready02.json`，SHA `6e16ff043614a1d80a7e3d6a77bbd85fe470fb6efde1e84126d9f604d27b1108`。候选尚未采主树／部署，私有replay方法新增可选输入guard尚未编译；后继正式17须分别验证有／无输入入口，不能把green05追认成guard后的测试。其他样本、语言、MS Word及新后端HTTP导出尚未验。

OER03分节原始声明补齐已采主树，六实际原件31项通过；尚未构建部署，也不在正式16中。DF02/03真实生成页眉页脚及逐页版式继续实施。DF01身份来源严格资格修复正做隔离RED/GREEN；当前只确认代码风险，不追认活动数据受污染。DF01真实HTTP计划已纠正record的kind约定，driver SHA `732f040e817da44112d2fc3f6a1060732327935d012bcf8f118f9362ddc4ae36`，plan SHA `4455e44d051460c1afccf0f55a97ee7e42c77b59dbc2228c6e65096fe988cd81`，未运行。本轮未新增真实模型请求；27B正常Advisory／Vetting等待正式16安装与实际提示词核对。Goal保持active；浏览器、复杂PDF／扫描分阶段及整包身份审查仍未验收。
<!-- runtime16-restore-and-report05:end -->

<!-- quality-baseline-and-report04:start -->
**后继质量状态：OER03同一真源的分节声明补齐已精确采入主树2文件；尚未构建／部署，不在正式16包中。** 采纳票 `drafting/oer-native-section-layout-main-adoption01/adoption.json`，SHA `079033fd7ce2b9df156f7c7a86b114119e7ea0e8d6614d0ac62ebb6ff8c65843`，原dirty字节另备份。采用的是已独审、同测试23项及六实际原件共31项通过的精确候选；当前活动仍正式15，正式16A+B及其JAR不变。

DF02/03原件基线已实际渲染：2a NTT13页／SCT29页，2个独立2GiB/600秒owned renderer均自然Job0，原件字节不变。仅此renderer基线的静态页码标签分别11／27枚；NTT空白第2页，NTT第5/6/7页及SCT第3/4/5/12/13/14/15/16/17/18页有静态页码处于页面上半部。Root亲看NTT1/3与SCT1/3，SCT第3页顶部确有上页静态SCT/2及来源控制文字；这是未修改原件的渲染表现，不归为本轮产品回归。完整逐行坐标／字体观察 `drafting/native-identity-baseline-render01/geometry-observations.json`，SHA `f10022625261dba799a9b50202e93b8a14a18947a7c51e454badde7b1e1dedd7`。尚未据此选定新边距、创建页眉页脚或验收生成物。

VT01 green03真实42/42全绿，Word/PDF各56页，各自56页全视觉已做，所有17 Comments行及69 Checklist行逐cell字符／顺序相同；表头裁切、序号裂行及表格孤项已修。仍有少量Appendix引句／末词孤页：nativePDF37/38及47，DOCX40，因此视觉结论为 `FAIL_MINOR_PAGINATION_REMAINS`。原票／输入全保，后继green04沿既有段落keep/续段与精确来源路径人类显示做最小修复，未采主树、未混正式16，原真实审查业务FAIL保持。
<!-- quality-baseline-and-report04:end -->

<!-- formal16-preservation03:start -->
**当前状态：正式16已通过 A1314/96类＋B1/1类，共1315/97，0failure/error/skip；尚未安装，活动服务仍为正式15。** 同一冻结1517文件、A33/B39实际属性、四份原件副本和九份完整历史输出均有终态票。Root接回A、B自然Job0，合并票 `build/round16 integration01/combined-verification.json`，SHA `6e1e737ee0288e436f3c49c30673d895a16b44f378c111d0754b350e80393419`。新JAR SHA `4626bb141f311fa1eb28cd3c9d2ac5191a98b03a4e3543479eef674815b4f170`。本轮未新增真实模型请求。

升级保全已完成44条完整GET及唯一新SQL16：1,286,084,208字节，SHA `cb95962d2d62aff32e4230d41139bc15e200c4950f7410b42608c0e968c54e09`，SQL Job自然0、共享H2 owner38980未停。30份上传／隐藏原件共4,233,776字节已另行逐字节备份；SQL只含原件路径，不含原件字节。全新隔离库恢复与历史artifact BLOB、问答33/34、17packet、计数action及文件关联检查仍待执行，通过前不切后端。完整API比较40条严格字节相等，另4条仅允许已审定的身份功能精确转换；严格JSON比较已补重复成员、非有限数及bool/number边界，独审通过。

OER原生分节声明候选已在六份实际2a/2b原件复验31/31全通过，22sections、132slots、2010精确检查无差异；三份2a保留六项真实sourceBinding派生hash变化，18次独立重算吻合，不删hash后宣称旧字段零变化。该候选仍隔离，未证明生成或逐页版式。Vetting报告候选42项与两格式逐cell核对通过，但逐页视觉已发现Word续页表头裁字、序号换行及表格跨页问题；继续最小修复，未采主树或混入正式16。用户再次确认质量优先。
<!-- formal16-preservation03:end -->

<!-- unified-e2e-oer-goal-20261020:start -->
**2026-10-20 用户停止旧Goal后已创建新统一Goal，状态active：三模块端到端＋OER能力补齐。** 范围包括正式归属、条件／指导语／续段／编辑目标、页眉页脚身份、复杂PDF／跨页／扫描分阶段验证；沿同一native observation／canonical真源修首个损失层，不另建parser或旁路。文件owner、依赖顺序、当前原件与运行证据见[统一编排与验收计划](unified-e2e-oer-goal-20261020.md)。质量优先，后继正常真实调用优先27B；Flash保留显式选项及质量对照，不因速度自动优先，不缩来源／条件／输出。旧失败、dirty源码、原件与历史证据保留；正式15仍在运行，后继修复未部署不追认生效。
<!-- unified-e2e-oer-goal-20261020:end -->

<!-- three-module-new-requirements-20261020:start -->
**2026-10-20 用户追加要求，已纳入本Goal且尚未验收：** DF01固定顶级项目名称／合约编号，识别后经产品用户确认替换项目显示并贯穿整个包；DF02按项目2b NTT／SCT／SCC原件核对并更新预览／生成页眉页脚身份；DF03逐页检查版式与页码；PR01核对Vetting／Advisory实际使用最新system提示词；VT01结构化报告与赛方对应文件核对栏目及版式；VT02审查整包项目名称／合约编号一致性，区分自身身份与合法外部引用。需求、验收案例、状态及证据按ID记录在[新增能力验收记录](three-module-e2e-followups-20261020.md)。临时项目名不能直接作身份真值，识别不得静默改项目；原件与当前run保持，不追认已覆盖。
<!-- three-module-new-requirements-20261020:end -->

<!-- flash-vetting-live-progress-20261020:start -->
**当前增量（2026-10-20T03:48:41.173334+00:00）：真实 Flash Vetting 新 run 已成功提交并运行中，尚未业务验收。** run `a5681c88-5b6c-44e1-804a-0f5012192969`，实际4个来源/旧run预检GET通过、唯一POST返回HTTP200，绑定`qwen3.8-flash`及当前source snapshot；driver每60秒观察既有run，不重发。首次01预检失败仍保留为1GET/0POST。本次新增数据库写在SQL15之后，后继升级前需新备份。真实付费ledger/终态findings/99.8及来源断言待验，不能把17topic计划或51预计generation当实际次数。

Drafting 新null回归已真实RED：29项=28通过+1失败，失败用例覆盖六个可空关系字段；另外两条字符串`null`/foreign隔离及26旧测试均通过。最小修复GREEN进行中，主树parent提示词与本修复均未进入活动正式15。Advisory正常Flash ask及保存重读在本次Vetting付费动作终态后继续；三轮27次受控模型比较不代替应用闭环。Goal保持active。
<!-- flash-vetting-live-progress-20261020:end -->

<!-- formal15-installed-controlled-models-20261020:start -->
**当前核验状态（2026-10-20T03:35:39.928936+00:00）：正式15已实际安装，后端18084/PID44596，精确FILETIME字符串134369401181141747。** 同冻结1506文件的A1235/82与同target/JAR B1/1均全绿，合计1236/83、0failure/error/skip；A32/B38实际属性与自然Job0均有票。旧15首轮1219pass+8failure+8error由两测试旧反射签名引起，9处显式null/45bytes修复后复验，旧红轮不改判。升级前后39完整API body逐字节一致，SQL15为1272078624字节/SHA`3b75228304bfd549705818ceefba15be2f5a2eff397702a65968208ec11c2cd5`，仅逻辑备份未新恢复。H2owner38980未停；安装票`.local/e2e-20261020/runtime/round15-installed-verification.json`。

三模块同冻输入模型对照已实际完成3轮/27POST，0retry/repair/cap1预检；两个Qwen各9/9返回exactmodel/stop/严格JSON，MiniMax2/9返回、7次HTTP529未评分。原文、schema、应用契约与可用局部成果分别核验，不能把此对照当应用端到端或统计稳定性通过。详细逐项证据见[三模型受控对照](qwen-controlled-model-comparison-20261020.md)。新的真实Flash Vetting应用run尚未提交；Qwen持久计数链真实运行仍待验。

Drafting按用户规则保留有来源的独立问句，坏关系另列待人工补齐。三轮Qwen回答暴露parent包含与标量依赖混用；一条parent契约提示已采主树（3158→3377字符），尚未构建/部署，不在上述正式15包内，也未改动原比较输入。新版实际浏览器切换仍因owned TaskSpace丢失未验；记账后继续其他测试。日常真实LLM继续Qwen，MiniMax只作本次对照；Goal保持active。
<!-- formal15-installed-controlled-models-20261020:end -->

<!-- round14-installed-and-controlled-models-20261020:start -->
**当前状态（2026-10-20T10:38:43.551853+08:00）：正式14已安装，后端PID61472／18084。** A1190/76与独立同target/JAR B1/38属性均实际通过，合计1191/77、0failure/error/skip；九份原件完整输出保持。升级前后26完整API响应逐字节一致，新SQL14为1266093696字节、SHA`0dcddc6afb02cc4b23eb15b19de9d038b9682cbcfba4a40d1d4b143ef4a5a568`，仅逻辑备份未新恢复。H2 owner38980未重启。安装票`runtime/round14-installed-verification.json`。活动目录已返回local、MiniMax和两个Qwen profile，27B/Flash均configured=true，两种`X-ConSense-Llm-Profile`头实际接受；这还不是模型推理或浏览器验收。原326error轮及B输出目录遗漏失败保留；B脚本已在发前创建新目录，仅复验1项，A未重跑。

Qwen usage-preflight后继19路径已采主树，隔离106项全绿，25为新policy、81为旧回归。与A1190的精确交集61，后继最小A应1235/82，加B1为1236/83；尚未构建／安装，活动14的Vetting/Advisory不能因此追认预算链通过。fresh Specification正常上传重解析、首次变量HTTP耗时及真实Qwen业务仍待验。外置浏览器旧space2已丢失，按ego技能记录未执行并继续其他任务。

**用户最新要求模型对照控制变量，期望两个Qwen型号改善MiniMax的质量与可靠性。** 三模型须用相同冻结来源／范围、题目、提示词、schema、共同温度及输出上限、同一runner版本；必要provider字段单列。首答比较0自动repair／retry／history，独立评价来源完整性、漏检/误报、引用/条件、错补事实及可用局部成果；官方答案／评价oracle只在模型外保存。首批拟3场景×3模型＝9次回答，尚未释放或执行，不以一次对照授予稳定性。MiniMax仅作同条件对照，日常测试继续Qwen。Goal保持active。
<!-- round14-installed-and-controlled-models-20261020:end -->

<!-- formal14-a-red-20261020:start -->
**当前进度（2026-10-20T09:56:11.241788+08:00）：正式14 A首轮已结束，尚未升级后端。** 实际76类／1190项＝864通过＋326 errors，0 failure／skip；首个已证根因是新增测试helper `ValueTextTcpJdbcProbe` 在未启用TCP测试时注册空server，正在隔离验证整helper仅在 `consense.probe.tcp.enabled=true` 时装配。原失败轮、76原XML及完整身份／错误台账保留，九份完整原件输出逐字节保持，实际32属性逐类齐全，测试Job自然active0且close前active0。独立终态审票：`drafting/formal14-a-independent-audit/terminal-independent-review.json`。原runner还有未走到的69类常量遗漏，后继独立runner将按精确expected身份集合推导类数；不修改原轮追认通过。B未执行、SQL14及26API保全未执行；应用仍为正式13 PID47748，H2 owner38980及前端／OER／OCR监听保持。Qwen预算预检与持久调用预留仅在后继候选实现，业务未通过结论保持。Goal继续。
<!-- formal14-a-red-20261020:end -->

<!-- qwen-main-adopted-20261020:start -->
**最新增量（2026-10-20 09:39:17 +08:00）：Qwen前后端已精确采主树，尚未升级运行后端。** 最终隔离JUnit29/29、独立二审0Must；17路径采纳票`build/round14-qwen-adoption/receipt.json`。Root补的实际前端Qwen持久化／标签／切换请求头场景从旧UI的local≠qwen RED转GREEN；项目typecheck与独立输出生产build均过，173个受检前端源文件前后SHA保持，三个自有Job均自然active0。票`qwen-provider-discovery/web-green01/receipt.json`。两个model的小JSON、max1真实generation可行性及SSE共6次generation均协议通过，0retry；不冒充模块业务或原生counter。正式14精确并集计划A1190/76＋B1/1＝1191/77，尚未执行。当前backend仍47748/18084正式13，H2owner38980保持；Vetting/Advisory可信Qwen预算policy待完成，历史FAIL保留。[Qwen接入记录](qwen-api-integration-20261020.md)。Goal继续。
<!-- qwen-main-adopted-20261020:end -->

<!-- qwen-progress-20261020-0910:start -->
**增量状态（2026-10-20 09:16:12 +08:00）：后续模型切换为Qwen，当前活动服务仍为正式13。** 用户确认API的`qwen3.8-flash`即Flash-Next；前端需显式选择27B／Flash-Next。已真实GET模型目录HTTP200，并对`qwen3.8-27b`／`qwen3.8-flash`各执行一次小JSON调用，两次均HTTP200、精确model、单choice、stop和JSON内容通过，0retry；不代表三模块业务验收。独立profile／adapter／前端候选仍在隔离区，HTTP隐式replay与JSON转义密钥脱敏两Must正在修；Vetting／Advisory可信无生成计数契约尚未证明，保留unknown。详见[Qwen接入记录](qwen-api-integration-20261020.md)。

来源条件＋续段source-region修复已精确采主树；DraftVariable单字段去`@Lob`及两fixture也已精确采纳。后者green02真实JUnit1/1、exit0、完整响应及历史全字段保持，并有独立0Must审票；二者均尚未正式集成／安装，不改变旧answer32业务FAIL或活动首次变量延迟结论。green02清理close前active1，不称自然全空，正式runner须等实际Job active0。现有后台与H2 owner仍在，未重启；未来进程FILETIME使用字符串，避免大整数JSON精度丢失。旧数值收据保留，已记录当前精确值及binary64舍入复现，未把舍入复现当原历史转换链证明。SQL13之后写入仍需新备份。下方08:44及更早段落按历史快照读取，Goal继续。
<!-- qwen-progress-20261020-0910:end -->

# 三模块端到端测试与修复台账

日期：2026-10-20，Asia/Shanghai。状态：执行中。08:00 为用户进度验收节点，不是全量完成截止时间。

<!-- current-progress-20261020:start -->
**当前进度（08:44检查点）：Goal active，08:00只验收进度，未完成工作继续。** 正式13已安装至后端`18084`／PID`47748`（creation FILETIME`134369286706911920`），前端`5175`，H2 owner`18082`／PID`38980`保持。实际运行JAR SHA`596ffe85efce13106e3a2fc296d92d983e330b4610f36cbe636525e91556dd4c`。64类／1071独立用例全GREEN、1490冻结文件未漂移、9整件来源输出逐字节保持；20个升级前后完整API响应全字节相同。定向通过不覆盖Full12原失败或三模块业务FAIL。

| 模块／层次 | 已验证结果 | 当前失败与未完成 |
| --- | --- | --- |
| Drafting | 有来源的局部结果经人工补规则／原文目标、核对保存、采用测试值及三DOCX／PDF生成导出的限定链路已通过。同255节点保存527.469→30.219秒。NSC／BSSSC两路浏览器类别显隐／保存已验，双项目真实抽取均保留Fire services和Lift | 新两字段不是旧79评分整体重验。正式13后16/16真实HTTP完成，四份80变量响应均38564B、与旧before全字节同；NSC首次28.906s／重复0.031s，BSSSC首次22.156s／重复0.032s，旧首次34.360／26.765s。首次仍慢，性能未收口；并行负载和缓存未控，不能把全部观察差归因N+1。继续隔离SQL／JDBC／事务观察；4HQL不是总SQL |
| Vetting | 真实审查、来源定位、人工记录及三格式导出已执行；报告快照同步及非空拒绝后repair空数组的人工提示已装。正式13后浏览器可见Mini08执行结束、0发现及非完整覆盖提示 | Mini07 FAILED、Mini08已知two/three copies冲突仍业务FAIL。原两句literal可达、首答引用正确，但首答record0业务形状无效、repair原答首次变[]；不得自动amend→review或恢复为finding。新UI为latest-only，Mini07历史FAILED界面重验skip，API原失败保持 |
| Advisory | 正式13同题新会话实际8应用HTTP、1primary；125.083秒返回code0，1counter＋1generation完整响应、0repair／手工重试，历史逐值同、错原件SHA拒409。answer32补出Site Boundary条件并改正旧SL排除库错引 | **answer32仍业务FAIL**：把(1)(c)及其引入的GSCEW拆为两份资料，误称额外Specification内容／版本／优先未知，grounded=false／insufficient。原DOCX body38的include-the-following及body40完整GSCEW至body42才起2；新prompt虽完整包含两段，但分为独立unit且无明确续段关系。最早已证业务错在provider raw，原样进入发布；原生关系缺口仍调查。旧answer26／28／30失败不覆盖 |
| Full12广回归 | 07:23:46终态exit1，55分26.890秒，无超时／重跑。365类／3956项＝3939pass＋2failure＋6error＋9skip；旧TemplateReading10/10、大UTF16输入四方法均实际通过。正式12的965身份全部pass；终审v2及旧参数身份映射已完成 | 原两failure保留：来源guard回归已最小修复并原93/93 GREEN；旧raw按当前schema正确早拒的language6/6 GREEN是显式新契约，非旧同义通过。六个VettingProcessRecovery文件H2仍实际只读error；完整原因与环境决策见下方环境表，不改记恢复通过 |
| 正式13验包／安装 | 原fork0轮1071全GREEN；同一1490源的freshfork02实际64/1071全GREEN、零skip，完整保留965及920身份，64个新class JVM初始化32属性均匹配。新包已安装，20/20完整API响应全字节保持，H2 owner创建身份保持 | 参数观测仅为每类provider初始化快照，不是运行中采样，不追认原fork0空properties。freshfork01启动失败48.109秒、0测试／0XML，原票保留；02仅修启动路径引用机制，非严格A/B。安装不等于模型／浏览器／原件质量整体通过 |

补验02输出位于`build/round13 freshfork02/`；正式票`build/round13-build-verification.json`（SHA`f5a39038fd528e93dde1971a6e44a45a3ba986c2111ffc332d3a2cc0d83e55f4`），安装票`runtime/round13-installed-verification.json`（SHA`c628706cd0b321d270d59dcf5e98c41779c2750235f7ae8e732ffaee7c4fa5fb`）。原Surefire空属性和01的cmd无引号`&`故障票／日志保留；没有回写原绿轮或失败轮。升级after收据SHA`03adb9dcb9b684a275031b5ce5685aba59ce630f3bd5738f367e58ef900e2fe9`。

新BS两场景原22HTTP／2应用primary／4候选来源绑定通过，变量before／after完整同；未确认／采用／生成，provider POST实际unknown、上界6。真实性能16HTTP总票`drafting/variables-perf-round13-preparation/execution/summary.json`（SHA`43ad39cb10e1ad34d12ffc5d21df228d97c64a8791c586b7ace131fb46e6decd`），不是性能已解决。新Advisory会话`ecce95d4-a4c7-472b-88d9-eab561c04477`／answer32的原请求、答案和wire在`advisory/round13-small-minimax-empty-specification-priority-positive-01/`；原answer30保留。

新浏览器surface已实际创建agent-owned tab1981782471，5175三入口可见；已读Drafting人工采用值false／理由、来源核对及三文稿入口，Vetting latest incomplete／0，Advisory旧历史／引用／原件定位。本次有限只读复验已封存，27次控制动作（预算45内），自有tab已关闭；收据`browser/round13-readonly-retest/receipt.json`，SHA`54d4bc19dd803782ce20eb11a8c0752a2155c33e06efa75d1e8eebc325154484`；按钮可见不等于新导出，仍loading的正文不称全文通过，全页截图超时保留skip。旧六Word共7980段对照与原三阅读模式证明保持；隐藏未核转换列表标号并提示核对PDF仍只是安全兜底，原编号尚未恢复。额外Advice整包1/6完成、5未执行。

SQL13（1265134528B，SHA`565b8d4eeca8324584d7a251464437f7504f6b46e2dfd197371e722df096eb5d`）在旧answer30／BS候选之后、升级13之前抓取，仅验文件完整性、未新恢复。20对完整API已核同；**其后两个性能项目与answer32是新写入，SQL13不覆盖这些新写入，下一次升级前需新冻结／备份。** 新OCR推理／扫描质量／H800／生产未执行。

首次载入JFR诊断已终态：实际1POST／0GET／1jcmd／0recording，JDK缺Attach原生库，冷GET跳过、0重试／运行时修改，原票`drafting/input-init-runtime-jfr02/verification.json`保留。随后**单个自有256MiB进程、随机loopback TCP／新mem库的4场景机制诊断全部符合预声明**：remote空stream在INSERT前OOME，实际buffer=1,073,745,920 chars；mem空stream、remote正长度1的stream及remote空setString均完整读回。票`drafting/input-lob-tcp-owned01/verification.json`，SHA`6159efcb1b366451e345ccdfcc8f7e77f8b484f48a7d7e9d04b37e955e980d9b`。只证明客户端空CLOB超大缓冲机制，不量化22–29秒延迟归因；正用真实Hibernate80变量做隔离单字段绑定修复验证，主代码／活动服务未改。

共享Word源结构真实原件RED已完成：88原生成员／ID／位置／文本通道逐值保留，headings0／ranges0；实际selectedWordScopes分别选条件段与GSCEW段均拿不到共同范围。自有2GiB JVM、2.093s，预期结构失败exit3；票`drafting/advice32-source-structure-readonly/red01/owned-receipt.json`。隔离最小source-region GREEN及歧义／旧正确回归进行中，不扩整份编号树、不授法义／编辑／采用。

证据总入口`.local/e2e-20261020/execution-state.json`；Full12终审`drafting/full12-independent-audit-v2.json`；guard93票`vetting/canonical-guard-fix/final-ready.json`；language6票`drafting/isolated-round13-language-contract/summary.json`。数字按轮次／层次读取，不把重叠测试、保存原件回放、真实模型或浏览器结果相加。
<!-- current-progress-20261020:end -->

以当前代码与 [已确认规则](../oer-scope-and-rules.md)、[当前交接](../HANDOFF-ocr-embedding-rerank-20261008.md) 为依据，测试 Drafting、Vetting、Advisory 的真实输入、来源身份、OER 消费、MiniMax 调用、浏览器操作、保存恢复及支持的生成／导出。发现可复现且在当前设计范围内的问题就做最小完整修复。需要用户决定或外部条件的问题记录并跳过，继续其他工作。代码／契约、真实模型／引擎、原件质量、浏览器与业务结果分别报告，失败、unknown、跳过和未执行保留。

原始基线：`8d0900716b044621486c0889898b9d9b57a00ab3`，启动时工作区 clean。完整路径与 SHA 清单、HTTP 原票、模型记录、运行日志及冻结原件身份保留在被忽略的 `.local/e2e-20261020/`，不提交原件或凭据。首轮开发前端 `http://127.0.0.1:5174` 代理到测试后端 `http://127.0.0.1:18081`；后继 `http://127.0.0.1:5175` 代理到 `18084`，为后续修复与重启回归使用。已有 5173 前端与原项目资料保留；两个测试实例的结果分别留证。

## 本轮明确的产品验收要求

- Drafting 已有人手兜底。优先把已找到、来源可验证的完整变量和原子关系作为可用待核结果交给用户，允许查看、补充、修改、保存和继续起草。变量不齐、局部 JSON 截断或拼装失败不能使全部已完成内容反复报错。损坏对象和依赖仍须明确待补，不能猜事实或自动确认。
- 无论采用 NSC 还是 BSSSC，消防、电梯等 BS 分判类别都需要选择。NSC／BSSSC 只决定所选分判的指定方式，不能控制类别是否出现。
- 官方答案与评价资料不得进入同题解析、索引、上下文或模型提示；标准空模板不得成为 Vetting／Advisory 的业务依据，图纸识别路由后排除。

## 当前环境与问题

| 项目 | 已观察事实 | 状态与下一步 |
| --- | --- | --- |
| 原 8080 后端 | 现有 watchdog 反复启动，Tomcat 因默认 TEMP AccessDenied 失败 | 原票保留，测试实例显式使用项目内临时目录 |
| 隔离文件 H2 | 自建普通／H2文件的实际 `FileChannel` 写入成功，但 `Files.isWritable=false`，H2依此只读。新增只读诊断：实际Java `55448`与活动后端 `13736`主令牌均为Low IL；新Java的provider在权限分支抛 `AccessDeniedException`，两进程主令牌对同两文件SD的 `AccessCheck(0x2)`也拒绝。精确 `java.exe`及父目录的Low／NW标签从工作区根继承链已实读；**未证仅IL导致拒绝** | 六个原文件H2恢复errors仍未重验／未通过，未改ACL或运行时；独立TCP内存owner退出后SQL恢复的旧证明不等于文件模式修复。环境决策问题记录后跳过：保留现有隔离策略下，文件H2恢复应采用何种可信运行环境；继续Goal。证据目录 `drafting/h2-writable-probe-20261020-01/readonly-java-diagnostic-01/` 下 `conclusion.json`、`label-and-java-registry.json`及 `label-registry-conclusion.json`，保留真实写成功／NIO拒绝差异与因果未知 |
| 后继测试数据库 | 独立 H2 owner 仅监听 `127.0.0.1:18082`，承载 `18084` 的测试数据库；当前 PID `38980` | 539,218,592-byte SQL 在旧 owner 实际退出后恢复，21 表／270 行逐值摘要相等，四个业务 API 完整字节一致；详见 02:12 证据。此后新增数据仍需新备份，未改 H2／ACL |
| OER launcher | 正常来源契约已为 1 GiB，启动脚本却仍按原生来源 16 MiB 校验，健康服务被误拒绝 | 最小校正 normal 专属常量；native/family 16 MiB 保持；新增旧值／越界反例，37 项检查通过，两个真实服务复核通过 |
| Advisory 来源准备 | 原完整 SCC normal 发布遭 30 秒超时，原票保留 | 完整发布独立 120 秒、ordinary GET 30 秒保持；已实际读回 complete=true／already_registered，没有重发投影或索引，不将来源完整等同答案正确 |
| Vetting 新上传 | 首轮未登记原件在准备前失败，0 LLM 调用 | 已修正常来源准备，mini／G305／2 页 GCT 均已进入真实模型；模型语义和审查完整性分别保留失败／不足 |
| 跨模块来源登记 | 首轮同项目误选另一模块 normal binding 失败 | 已按模块选择 normal 绑定，真实跨模块显式准备通过，reviewed 共享来源及 stale 拒绝保持；不是放宽角色或采用门 |
| OCR 当前运行身份 | 原常驻服务健康为 500，日志明确 `SOFTWARE_CHANGED_SINCE_IMPORT`；旧启动清单对应历史源码 | 已对 1,051 个唯一运行资产作实际核验，只为源码变化生成新的本地启动清单；原模型／历史清单保持。仅在确认原 unit 中只有协调器、无 provider 作业后重启该精确 unit，`18780/health` 恢复 200；本轮尚未执行新 OCR 推理或宣称质量改善 |

## 01:15 阶段进度（不作为整体验收）

Drafting 已完成真实三 Word 模板上传、逐份 reading／evidence／bindings 和原件字节 SHA 回读。局部结果交付修复的首轮 Java 定向回归 77／77 通过，涵盖 discovery 19、完整 JSON 前缀 3、legacy extraction 26、graph service 29；浏览器已实际复现 NSC 隐藏消防／升降机类别，修复后的后端包与浏览器回归待执行。完整三模板 graph 保存耗时超过预期，正在以真实请求和最小复现定位重复原件读取；15 份项目沟通 fixture 的真实 MiniMax 抽取另项目执行，尚未记为通过。

Vetting 的空资料／排除资料门已实际 HTTP 核验；标准 NTT、drawing、pricing 均排除。mini 合成与真实 G305 首轮都在来源准备前失败，0 LLM 调用，不作为模型质量结果。修复定向首轮 34／34 通过；另一批 133 个测试因默认 C TEMP 权限错误未进入测试，原 errors 保留，待在明确可写的测试临时目录重跑原用例身份。

Advisory 已完成会话／scope／项目隔离、SSE 错请求与停止／释放边界、排除材料及原件绑定拒绝；真实小源完成准备和 BGE-M3 索引 21／21，实际 canonical pointer 对应原始 DOCX 字节回读通过。首个 MiniMax 请求实际 input_tokens 估算为 34,276，采用已记录的 1,000,000 声明操作窗、16,384 输出上限和 32,768 安全余量；`exactTemplateObserved=false`，不把估算当提供商实测最大窗口。当前生成进行中，尚未记录答案／引用业务通过。

## 01:22 增量（已打入第一轮测试后端，下一轮仍在修复）

第一轮修复包 SHA `c403b7277c5546decf36a16416c037cb15fbb24dca71973c74ec14f155d3bc3f` 已启动在 `18084`，来源树清单为 `build/round01-source-hashes.json`。原 `18081` 中完整 graph 保存请求在 900 秒后客户端超时，结果未取得；15 份沟通资料抽取已终态。收取进程身份和全部请求原票后只停止该自有首轮测试进程，保留 `5174` 及所有磁盘证据，未触及原项目数据库。后继 H2 独立 owner 保持，重启前真实业务 SQL 快照 `runtime/db-snapshot-round01.sql` 为 6,565,920 bytes。

- BS 类别真实浏览器回归通过：专用项目 `e2e-ui-20261020`，NSC／BSSSC 两路消防和升降机均可见可选；先以 NSC 保存，再切 BSSSC 保存，真实 GET 均保留 `["Fire services","Lift"]`，取值只来自测试人工输入。证据 `browser/bs-designation-browser-round01.json`、`bs-nsc-saved-api.json`、`bs-bsssc-saved-api.json` 及实际截图 `bs-bsssc-fire-lift-saved.png`。规则版本为 `drafting-20261020.1-bs-scope`。
- Drafting 15 份 fixture 真实抽取 116.359 秒、24 次模型调用：79 业务字段中 58 应有值（57 provided＋1 derived）与 21 应留空（17 not_applicable＋4 unknown）分别匹配；实际 API 回读 80 行为 79 业务＋1 system，confirmed／manuallyEdited 均为 0。85 条决策是另一分母：58 accepted＋6 rejected＋21 unanswered。评分器保留 44 pass／35 review／0 field fail，整体 FAIL 原因是历史目录版本不匹配，不改旧版本门来追认通过；35 review 保留人工语义边界，完整列表的换行归一差异继续记录。逐字段证据在 `drafting/sim15-evaluation01/grade/drafting-score.json`；原始回读、trace 和 events 在 `drafting/e2e-drafting-extract-20261020-0110/`。
- Drafting 本轮扩展定向 Java 最终 91／91（Business14＋Extraction26＋JSON3＋Discovery19＋Graph29）、前端 53／53 通过；与首轮 77 重叠，不相加。下一轮补 adapter 不完整响应的局部保留及完整 graph 保存性能；未声称当前 900 秒失败已修。
- Vetting／共享来源 34 项＋原环境错误批次 133 项均已实际通过，133 的身份为 Service19＋JPA21＋real seam93，0 failure／error／skip；原默认 TEMP 的 133 errors 保留原票，修正 JVM 临时目录后重跑，未改测试绕过。第一轮真实 mini／G305 均已跨过来源准备并完成 CUDA 检索，但在 ranked chunk 对象一致性门失败，0 LLM；独立复现证明唯一变化为 transport copy 的 `MissingNode`→`NullNode`，实际 JSON 相同，正在修原 pointer 工厂而非放松上下文比较。跨模块显式 normal 准备已实际通过，Vetting sourceSetStale 保持 false。
- Advisory 首个真实问答实际 175.252 秒，2 次 generation＋2 次 counter，历史逐字段恢复通过；最终 grounded=false、0 有据分析／引用，中文题输出英文，业务答复未通过。真实原件及解析保留完整四行排除表，但模型材料只取得选中一行；正在复用既有完整表投影修消费层并保持修复前已核语言。失败 wire 与浏览器截图 `browser/advisory-first-answer-language-failure.png` 保留，不因 HTTP 200 或历史恢复通过改记答题正确。

## 01:45 增量（第二轮真实运行及浏览器，仍在执行）

第二轮测试包 SHA `c177893ef2472596751712353c1bf47b616763db76585031da013eaf12c511b8` 运行于 `18084`，前端 `5175`。全后端广回归于 01:37 开始，尚未完成，不能给全量通过数；已发现旧 `DraftApplicabilityEvidenceTest` 仍将 BS 工种作为指定方式条件字段拒绝，与用户新规则冲突，原失败保留并维护现行规则断言。后续开发中的源码未冒称已进入该运行包或此轮已编译测试树。

- Drafting 局部 transport／来源复用定向结果为 159／159、0 skip，和先前批次重叠，不相加。第二轮完整 255 节点 seed 保存实际成功，墙钟 527.469 秒；首轮超过 900 秒无回执的失败保持。两轮项目和 source IDs 不同，同三份原件 SHA，不宣称完全相同 payload 的性能对照。确认未复核 seed 被 4007 拒绝。已定位逐节点 no-op 校验重复 load→write→load 原 Word，正在复用编辑器原有安全验证做请求内只读验证；该新性能修复尚未部署。
- Vetting mini03（run `2e85b80a-00ca-4cf2-b118-fa9eb0d449a6`）已完成 17 主题并产生 1 条 two／three paper copies 冲突。原文确实存在，模型却把真实 Clause 1／2 说成 2／3，仍为业务质量缺陷；不存在 Clause 99.8 的主题保持 `not_completed`。任务 `COMPLETED` 不代表完整审查通过。真实 G305 real03（run `99830d48-5f64-4be6-a59e-0631836d1463`）亦已终态，0 finding、`reviewCompleteness=incomplete`，待逐主题核验。
- Root 在实际浏览器完成 mini03 发现详情、两条原文和 canonical 节点阅读，核到 `word/document.xml`、原件 SHA 和 UTF16 范围。绑定原件从浏览器下载的 SHA `121fe9d141e684decffbbff870a62203146047f2b1be223d317483ae96821b9f` 与实际来源一致。“带入合约咨询”生成可编辑问题／两条引用草稿，未自动提交。JSON、DOCX、PDF 三格式从 UI 导出并实际落盘，字节和 SHA 清单为 `browser/vetting-mini03-browser-downloads.json`。初次页面仍显示运行结束时快照 `470444…`，实际下载 JSON 已是人工复核后的 `7199007a1e01dc6225b36a37fc5fb1a5aeda5fe1d8aec30bb004dfdf97253665`；二者同 run／同源但不是同一报告快照。下载成功不代替内容／版式验收。对应截图为 `browser/vetting-mini03-finding-detail.png`、`vetting-mini03-canonical-source.png`、`vetting-mini03-to-advice-draft.png`。
- Advisory round02 同题真实复验 245.816 秒，session `8daf2cfb-c199-4539-b17f-ae6be6427677`。实际模型输入已包含四行库名称，但最终仍为英文、`grounded=false`，只保留一段有限分析及 7 个引用，业务失败保留。完整表上下文仍只持 Soft Landscape 行的公开引用，正在补齐同源真实表成员的独立引用，不能把整表上下文冒充单行出处。证据 `advisory/round02-small-minimax-ordinary-01/answer.json`。
- 大 SCC 已有正常绑定的最终验证实际通过：selected 1／prepared 1、`already_registered`、`complete=true`，163.198 秒；本次未重发 projection、未做 GPU index 或 LLM。普通 GET 时限仍为 30 秒，未凭大响应猜测扩大。来源质量和人工确认保持 false。证据 `advisory/round02-scc-prepare-existing-01/`。

## 01:52 增量（报告快照界面修复及其他未完成项）

Vetting 导出使用当前人工记录新建报告快照，但界面没有刷新 `job.reportSnapshotId`，造成初次 UI `470444…` 与实际下载 `719900…` 不一致。已补真实 SFC 回归得到 RED（仍为旧 ID），再在导出成功后复用 `restoreJob()`，两项新回归覆盖最新快照及旧项目异步下载不污染新项目，脚本与 Typecheck 通过。实际 Chrome 再次核验：明确测试备注保存后，UI 和真实 JSON 下载都更新为 `ab4a31bef52e46856f617c471d21990bb84936e9a2e955280f8af13f177df633`。随后恢复原备注，历史 `reviewUpdatedAt` 按真实操作变化，未抹除旧快照。证据 `browser/vetting-snapshot-sync-verified.json`、`vetting-snapshot-export-after-note.png` 及 `build/vetting-view-export-snapshot-{red01,green01}.log`。

Advisory 的 `[5]` 在浏览器准确高亮 `body/42/paragraph` 对应的原生 `w:p[42]`，`[10]` 只高亮 `tbl[1]/tr[3]` 的 Soft Landscape 行，原件阅读同时完整显示四行及排除引导句。阅读定位本身通过，不能据此追认答复内容。自然图真实问答另用 177.184 秒、两次 generation，图节点保留 ID 和边 actor／action 约束失败，最终只保留有限中文文字，无可交付图；记为业务失败。代理正修静态关系图提示、初答语言和真实表成员引用，不放松图与引用校验。

Drafting 已离线以当前 PreparedTemplate 路径对真实三 Word 的 255 个 sourceAnchor 重放，nativeBinding 与本轮实际 API 保存结果逐项一致（94 bound／161 missing、0 差异），native 绑定部分用时 7.290 秒；这是局部验证，不是修复后的完整 API 性能结果。原模板同一引擎预览对照也确认 NTT 第5页和 SCT 第4页的页脚近空页原已存在，生成未引入；整份 Word→PDF 版式仍未收口。证据 `drafting/actual-prepared-source-replay03.json` 与 `drafting/e2e-drafting-source-preview-20261020-round02/comparison/near-empty-comparison.json`。

## 证据入口（持续追加）

## 02:12 增量（实际 owner 恢复及第三轮独立构建）

原真实原件广回归仍使用 01:37 编译的第二轮树继续，当前真实原件 `DraftingArtifactIntegrationTest` 已完成：28 项中 20 pass／5 failure／3 条件 skip，耗时 1,324.043 秒。五项失败分别涉及 NTT 实际 run 内容断言、SCC 独立 guidance 行删减数、两个方向的 measurement 原生分页符数量，以及 SCT 第二种 alternative 模式生成返回 4012。逐项原票在 `build/artifact-integration-failures-round02.json` 和本轮 surefire XML；正在核原件身份、旧位置断言与真实生成行为，未改期待追认通过。加上两个已明确过时的 BS 测试，本轮广回归在该时点有 7 failures、0 errors；这不是最终总数。

第三轮整树已独立复制到忽略的构建目录，未触及广回归正在使用的主 `target`。首轮正式 Maven 实际执行 22 个类／283 项，0 failure／error／skip，构建成功；两个建议类名在仓库不存在，未计入实际执行，随后使用实际 `VettingPacketPersistenceWorkflowTest`、`VettingSemanticPacketHarnessTest`、`VettingPacketReportTest` 补跑 31／31、0 skip。其间独立窄审又复现 Drafting 核心保留受模型父子节点顺序影响：child-first 只保父问题，parent-first 保两项。已修为用临时 question core 验证局部关系、保真实 parent／type／source，再对实际保留图作完整校验。新增四项顺序／互指／父循环反例，独立 26／26 及同一原失败 probe 通过，正式第三轮 b 整树包正在构建，未冒称已安装。

测试数据库 owner 的实际退出恢复已完成。旧 owner PID `11292` 和应用 PID 的路径、命令、端口归属及 SQL SHA 均先核验，随后只停止这两个测试进程。SQL `runtime/db-snapshot-round03.sql` 为 539,218,592 bytes，SHA `b1b032e3b923a225007ad232fc76f36150c9a8d8dd2626e64c271c04398c6624`；新 loopback owner PID `38980` 恢复后，21 张应用表、270 行的逐列类型／长度分帧行摘要及排序后多重集 SHA 与恢复前逐项相等，空表也纳入比较。证据 `runtime/restore-round03/receipt.json` 与 `db-digest-round03-{before,after}.json`。

暂用同一第二轮 JAR 启动后，实际 HTTP 回读项目列表 9 项、Advisory 指定会话 2 条消息、Vetting 1 条发现、Drafting 3 份文档，四个响应的完整字节 SHA 和内容均与 owner 退出前一致；证据 `runtime/recovery-api-{before,after}-receipt.json`。此门证明本次测试数据的 SQL 恢复及三模块回读，未改变原 8080 的文件数据库／TEMP 故障，也不等于可移植运行环境验收。第三轮 b 装包前各代理暂停本测试数据库写请求。

Advisory 第三个缺日期基线仍为业务失败：真实 132.18 秒、两次 generation，最终 HTTP200／业务 code4205；provider envelope 合法 `completed`，内部业务 JSON 因未转义引号无效，并出现无原文依据的“当日 24:00”门槛。该会话历史 0 条，未伪造可追问消息。第三轮提示补了必要 JSON 字符串转义、初答语言、引用成员和静态关系图机械说明，仍须真实复验；截至本条三组基线累计 8 次 generation、0 个完整业务接受。证据 `advisory/round02-small-minimax-missing-date-01/`。

恢复原人工备注后的 UI 三格式下载已经逐一核到同一真实报告快照 `c7cbc40e98bf57664dc41b66dadb1eaca55222c0331a0675cf081f7530078999`，JSON／DOCX／PDF 内部快照一致，PDF48页；完整文件 SHA 与边界见 `browser/vetting-restored-downloads-verified.json`。这次核验不消除原第二轮的条款编号和英文 summary 混中文缺陷。

## 证据入口（持续追加）

各模块证据分别在 `.local/e2e-20261020/drafting/`、`vetting/`、`advisory/`；环境在 `runtime/`，初始构建在 `build/`。每个结果应引用对应原始请求／响应及检查收据，实际统计随后追加；本文件的进行中事项不作为完成证明。

## 02:40 增量（第三轮 b 已安装、广回归终态与人工兜底操作）

第三轮 b 独立正式 Maven 为 25 个类／318 项，0 failure／error／skip，JAR SHA `273e14ccae9b4703afd491ede3f091c7f43f50c1b6e93608cbc3129609b58907` 已运行在 `18084`，PID `55012`；前端 `5175`。依据为 `build/round03b-build-verification.json`，不将先前 283＋31 与此批重叠计数相加。前端生产构建成功，输出 `build/web-round03b/`；随后新增的人工核对按钮尚未进入该静态构建，已由当前 Vite 开发服务加载。

01:37 启动的第二轮整树广回归于 02:24 结束，Maven **BUILD FAILURE**：353 个类／3,680 个独立用例＝3,565 pass＋22 failure＋14 error＋79 条件 skip，0 重复 classname／name 身份。已按本轮起始时间筛选并冻结全部 XML、逐例失败与跳过原因到 `build/backend-full-round02-final/summary.json`。旧 BS 规则断言、原件布局账本漂移、原生布局保护拦下的生成错误、Python 依赖／fixture 配置及 H2 只读分别调查，不能将环境错误静默删去或把后续定向通过覆盖此轮结果。

Drafting 同一测试项目、同三份原件 SHA、同 255 节点的完整 HTTP 保存由 527.469 秒降至 30.219 秒；6 项实际 HTTP 核验通过，未核模板仍拒绝确认，原已确认图及文档 revision／hash 保持。证据 `drafting/e2e-drafting-255-prepared-20261020-round03b/summary.json`。正文人工修改另完成 14／14 实际 HTTP 检查：native 原段保存、乐观版本／hash 拒绝、DOCX／PDF 重新导出、重载保持和重新生成；变量前后完整响应 SHA 相同，证据 `drafting/e2e-drafting-body-save-20261020-round03/summary.json`。

真实来源问题核心在 SourceReview 被正确标为 pending／needsRebind，但空规则没有显式解除动作，按钮需修改无关文字才可保存。已新增“移除隔离规则，保留问题”，仅对 `discoveryCoreRetained` 且无条件、输入依赖及修改目标的隔离规则开放，复用已有 RULE reducer、撤销与脏表单保护；不会增加编辑目标、确认问题或采用事实。挂载 Vue 测试先 RED 后 GREEN，并保持原 REVIEW 的来源／关系安全门。额外复现撤销将图变为 Vue Proxy、再次操作触发 `DataCloneError`，以和主图一致的 shallowRef 保存撤销栈修复。类型检查与来源状态／BS 类别定向通过。实际浏览器已完成解除、撤销、再次解除、逐项核对和保存；模板确认及后续人工采用／生成仍继续核验，原失败截图 `browser/drafting-partial-core-review-blocked-before-ui-fix.png` 保留。

Vetting mini04 的 17 个主题均执行，最终因模型将未评估清单混入发现记录而业务 schema 拒绝，保留 FAILED；不是 HTTP 或预算错误。新增 2 页原生 GCT 实际解析 5,381 字符、OCR 0，parseStatus=PARTIAL 保留质量未知；run `dd0c7435-e81b-46d2-8d8f-a8d3b1cf8c28` 已 COMPLETED，但 reviewCompleteness=incomplete、0 finding，17 packet 为 16 completed_empty＋1 completed_with_rejections。canonical 分母 84 segments／5,191 UTF16，本次成功调用覆盖 55 segments／3,666 UTF16；不能把零发现称完整审查或业务正确。新报告 DOCX／PDF 同 snapshot `0570ada…af8f`，英文摘要修复已实际目视，但它仍来自旧 G305 模型运行，不能替代新模型语义验收。

Advisory 第三轮 b 同题已证明初答中文和四行完整表的独立同源引用进入真实请求，但普通答复因正文编号遗漏外层 citation 数组被严格门拒绝，交付 0 接受分析。自然图 125.15 秒，内部 JSON 和重交已发布前缀失败，0 complete／history 0；缺日期 169.586 秒后保存了真实澄清 message 10，grounded=false、citations=[]、业务仍不足，后续条件追问独立保存。正在开发的外层引用元数据补齐只对原有合法引用已通过采用门的 analysis／flowchart，从正文明确出现且 allowed 的编号补数组；raw、叙述、check、graph、未知 alias、采用门与发布前缀保护均保持，须经独立构建及真实复验后再报告。

测试工具事件也保留：root 新增 Vue 断言曾尝试检查带父引用的完整测试 DOM 对象，自有 Node PID `21628` 内存异常增长至约 68 GB；已核路径／父进程后只停止该测试进程，改成布尔断言并以 512 MiB 堆上限复跑通过。02:34 Vetting 租约警告发生在上述 native run 已于 02:33:56 COMPLETED 之后，未倒推为模型失败。该环境事件及初始断言／脚本错误不计业务通过。

## 02:55 增量（第四轮安装与实际兜底生成）

统一第四轮冻结 1,480 个非忽略服务文件、43,467,449 bytes；清单 SHA `ae8b26652834b25d5799085581a2b7a1289cc8833ad327c558148fb0326e45cf`。正式 Maven **33 suites／386 tests，0 failure／error／skip，BUILD SUCCESS**，同一用例身份无重复，JAR SHA `5c6ad9473775b9429b374172257c9ba5c831192e42f8d2d4ff759f8d35e8b8d2`。已实际启动于 `18084`、PID `55732`，不是仅源码或待安装包。证据 `build/round04-build-verification.json`，386 项原 XML 另冻结在 `build/round04-focused-reports/`；与第三轮和其他定向批次不相加。

前端 9 个既有检查入口、Typecheck 通过，生产构建重跑成功且所核 223 个前端文件 SHA 保持（逐文件摘要见 `build/web-round04-verification.json`）。首次 Vite 因默认 C TEMP 的 esbuild 临时文件删除 AccessDenied 失败；仅显式改为已验证的任务 TEMP／TMP 后成功，没有改代码／依赖绕过。原失败和重跑分别保留；不因最终 bundle 成功冒称浏览器业务全通过。

Drafting 人工兜底已完成真实 Chrome 操作和后续 **18／18 实际 HTTP**：隔离问题核心单独核对、保存、确认后，在采用步骤人工输入 false，并保存完整“仅用于端到端测试、不代表真实项目事实”理由。服务回读 confirmed／manuallyEdited、所选值与理由均相符；生成三 DOCX／三 PDF，三份上传原件下载字节不变，三份生成 PDF 与各自同一 renderer 的原模板预览逐字节相等，页数 12／30／84。非空原段的顺序／文字、opaque 原件 parts 和采用值前后完整 JSON SHA 均保持。该问题 editTargets=0，证明有据局部结果可继续人工起草和导出，**不声称已自动删除预制外墙法律条款**。证据 `drafting/e2e-drafting-partial-core-export-20261020-round04/summary.json`，实际截图 `browser/drafting-partial-core-adopted-with-test-reason.png`。

升级前明确停止新增应用／模型写请求，最新 SQL 备份 **916,089,408 bytes**、SHA `b531e1325f277c4aa24cb4ea5e9c2ce033c51909102e5c45fb3dfb4c3d1a776f`，文件 `runtime/db-snapshot-round04.sql`。只重启自有测试应用，H2 owner PID `38980` 保持。升级前后实际回读五个 API：11 个项目、1 个人工采用问题、3 份草稿文档、1 条原 Vetting 发现、4 条指定 Advisory 会话消息，五个完整响应的字节和 SHA 全部相等，证据 `runtime/round04-upgrade-api-{before,after}-receipt.json`。此后已恢复新测试，备份不冒称包含未来写入。

第四轮开始同源、预先声明预算的真实复测：Vetting mini05 run `e540efea-f618-4b28-b324-d9d5072d4874` 只 POST 一次、沿用 mini04 的同一 sourceSnapshot，未重传或重解析；Advisory 按普通题、图及合法历史条件追问逐项执行；Drafting 旧 DOCX04 的 confidence="high" 拒绝保留，以明确 JSON 数值类型的提示在同输入上单次复测。上述结果尚未取得，不能计通过。

下一轮发现并保持隔离的两项问题：Drafting 每个图谱字段都重新校全部模板来源，导致纯 GET 随字段数反复读取同源；Vetting 首完成快照保留 100ns Instant，而实际 H2 持久化微秒，重载导出后仅 finishedAt 差异就改变报告 hash。后者已只读精确比较两条既存快照 payload，来源、模型发现、清单及覆盖逐字段相等；正在用已 flush 的存储时间修边界，避免假定所有数据库精度。两项在开发中的后继源码均未热更新当前第四轮服务。

## 03:25 增量（第五轮正式包、模型业务失败与人工重绑）

第五轮从第四轮冻结树派生，1,480 文件基线全部核 SHA 后只覆盖 10 个已完成文件，未带入第六轮 DOCX preparation 改动。正式 Maven **38 suites／503 独立 tests＝503 pass，0 failure／error／skip，BUILD SUCCESS**；第四轮 386 个用例身份全保、另 117 个新增身份，不与其他定向批次相加。JAR SHA `20f89cdc7641c3d106f461cc7b642fc6c5b3c4181d0a9eb499cb742467261fd0`，80,154,837 bytes。证据 `build/round05-build-verification.json`、冻结 XML `build/round05-integration-reports/`；本条写入时尚待备份／升级，不能称已部署。首轮 runner 将预期 DOCTYPE stderr 误作 PowerShell NativeCommandError，外层状态不完整；原日志／XML／JAR 保存在 `build/round05-first-runner-failure/`。仅修 runner 收取方式后同树重跑，完整取得退出 0／BUILD SUCCESS，两个 JAR SHA 相同。

第五轮补丁：Drafting 仅在同一请求方法内复用完整来源核验，不跨请求缓存；Vetting 首快照使用已 flush 数据库 scalar 时间、缺失／外项目／未完成状态拒绝，并明确 pendingFacts 业务拒绝原因，裸文档标题不能凑成第二条实质比较证据；Advisory 进一步明确 R2–R9 的 pass 必须有实际非空引用，不适用须明确 not_applicable。校验门、原件身份和调用次数上限未放松。实际运行后的同源性能／快照验收仍待执行。

第四轮真实 Vetting mini05 最终 **FAILED**，run `e540efea-f618-4b28-b324-d9d5072d4874`。17 个主题来源显示均为 0..8，同源 snapshot／原 JSON 保持；条款 1／2 归属改善，但 topic4 存在 pendingFacts 却建议 amend，严格业务 schema 正确拒绝。topic5 曾候选假称 99.8 不存在，另一条只是裸 SCT 标题，失败运行没有正式发布该候选；第五轮已用真实 wire 离线回放锁住裸标题不得凑证据，不冒称模型复测通过。下一次 Mini06 预声明仅一次启动 POST／17 topics，沿用同一来源、不重传／重解析。原六个 H2-file recovery `90097` 环境失败保留，不再 ACL 试探。

Advisory 第四轮普通题实际 169.578 秒，message14／session `112fa79d-8226-4d33-a5f8-d21d74807774`，发布中文 analysis 和 13 个真实引用；13/13 完整原件 GET、13/13 错 SHA 拒绝及 native pointer／quote 精确检查通过，但仍把已提供的四行排除清单误称为未提供，**业务 FAIL**。自然图 186.116 秒，首答 26 个关系含额外字段／语义形状错误，repair grounded 为字符串，code4205、history0，FAIL。合法 replyTo12 条件追问 200.427 秒，发布结果把 prior-to 截止误改成时间窗口，并在同答给出矛盾日期，业务 FAIL。原票分别在 `advisory/round04-small-ordinary-originals-01/`、对应普通题／图／conditional 目录，不通过重复重采追认。

同一原生 GCT 文档 15 的跨模块 Advisory 正常 prepare 为 selected1／prepared1、complete=true，4.261 秒；其独立 index 为 84/84、1 doc、stale=false，未重传／重解析。实际 scope=tender、basis=project_contract；原 scope=contract 的 selected0 脚本错误单独保留。session `a1f5c88d-d6a9-4940-aa71-49f3a848e345` 唯一 ask 124.431 秒，10 条入模 sources 均来自 doc15、无 fixed baseline，两个 provider attempts 均未满足完整业务门，最终 grounded=false／insufficient／citations=[]，**业务 FAIL**。不将 prepare／索引及资料复用通过写成回答质量通过。官方 MiniMax primary docs 仅明确 Responses `text.format.type=text`，未给最终消息 strict JSON Schema 契约；没有猜加字段或发送付费探针，见 [官方契约调查](../research/minimax-m3-responses-structured-output-20261020.md)。

Root 已用真实 GUI 为保留的预制外墙问题补回 false/delete、true/keep 两个互斥分支。整段跨 native tab 的初次目标因 `UNSUPPORTED_INLINE_FEATURE` 被拒绝，原失败 draft `686f323f-0f2e-42c0-9610-79e75416eed2` 保留。分别选择 P75 的 `[0,4)`、`[5,208)` 普通文字，保留 `[4,5)` tab 后，两目标均 bound，逐项审核／保存／确认成功，新 confirmed revision `1949922a-d463-4b45-bf9b-2ff42f64426f`；旧 confirmed 版本保留。旧人工 false 被标为 reviewRequired，effectiveValues 为空且方案 pending；随后真实点击“认可原值并完成复核”，明确沿用仅用于测试的理由。证据 `browser/manual-rebind-safe-{confirmed-graph,needs-review-variables,pre-adoption-plan}.json` 和截图。本次 API 采集曾因控制台 GBK 编码与误取不存在 rule 键失败，原图谱响应已保存；修采集后继续，不将采集错误算产品失败。

重新采用后的 GUI 又出现独立前端死锁：新文档到达时，watch 用新 document 与旧 bodyDraft 比较，将未初始化文档误判人工改文，步骤导航 disabled，生成函数静默 early return。此时没有真实生成 POST；已保留页面证据并委派真实组件 RED/GREEN，必须同时保留真正的人工改文与跨项目缓存，未通过清空状态绕过。安全 spans 的真实生成／导出比较尚未完成。

真实原件专项第四轮 b 最终 **6 suites／88＝87 pass＋1 failure，0 error／skip**，38 分 09.538 秒。唯一分数页原生 XML 断言漏排除新增的来源绑定书签；先核 16 个书签精确 source SHA／paragraph ID，再复用严格完整配对归一化 helper，全部其他 native XML 仍逐节点相等。同一生产源码的独立第四轮 c 单项正式 1/1 pass，生成 DOCX 与原失败产物逐字节相同，实际 LibreOffice PDF 的分子／分母均在物理第13页，已亲看页面。原 88 红票不改绿；4GiB OOM aborted 不计通过。证据见 [真实原件专项](drafting-actual-source-specialty-20261020.md)。

第六轮 DOCX part-section lookup 优化单独完成 28/28 定向及 peer 0 Must；三份原件完整私有 TemplateIndex（约19.5MB，含 snapshot XML／path／section／SHA／features／anchors）前后逐值／逐字节相等。prepare 观察值 NTT 1.420799→0.330008s、SCT 1.649350→0.510258s、SCC 6.776566→1.257918s，非稳定性能承诺，未部署。见 [等价与计时](drafting-part-section-lookup-20261020.md)。另正在只读定位 Advisory repair 全文重生的接缝：特定正确表格／日期内容在修复回答中变错，修复输入未带被拒绝前答；首答本身仍有其他错误，不能称其全对，诊断 `advisory/round04-repair-context-diagnosis-01/diagnosis.json`。

## 03:47 增量（第五轮实际验证、完整人工补回链与第六轮可部署包）

第五轮已实际运行于 `18084`、PID `17640`，H2 owner `38980` 保持。升级前 SQL `runtime/db-snapshot-round05.sql` 为 **1,223,587,328 bytes**、SHA `b44616e633af2e34401f58c89e313924b3ba7e3e84c0fdf86fbb92f29a874c16`。升级前后五个完整 API 响应字节全部相同：12 projects、2 partial-core variables（1 问题＋1 system）、3 documents、1 Vetting finding、6 指定 Advisory history messages，见 `runtime/round05-upgrade-api-{before,after}-receipt.json`。此后新增测试另需新备份。

Drafting 第五轮同项目、同原件、同 current／draft revision 的只读性能验收 **5/5 实际 HTTP 通过**：8 字段完整变量响应由 75.516 秒降到 12.046 秒，图谱由 26.484 秒降到 14.641 秒；两个完整响应字节／SHA 相同，三份原件下载也逐字节相同。只报告本次观测，不承诺固定倍数。证据 `drafting/e2e-drafting-read-performance-20261020-round05-after/` 及 [请求内来源核验复用](drafting-request-read-performance-20261020.md)。

人工补回规则后的采用／生成已经在真实浏览器完成。前端死锁补丁仅把 activeDocument watcher 的脏状态判断改为比较 previous document：首次文档到达和无改文换版会初始化，真实人工改文／新增段落继续保留；挂载 RED 为 2 pass＋2 failure，GREEN 为 4/4，同行审查 0 Must。浏览器先保留修复前假 dirty 缓存截图，再正常整页重载、重选已保存项目，实际五步骤导航恢复、旧人工 false／理由保留，点击生成进入真实预览。没有通过隐藏状态或清空业务数据绕过。见 [前端文档载入回归](drafting-document-arrival-watch-20261020.md)、`browser/drafting-document-arrival-restored-step-enabled.png` 和 `browser/drafting-manual-rebind-generated-preview.png`。

后续 **13/13 实际 HTTP** 核验了同一 GUI 生成的三 DOCX／三 PDF 与三原件：NTT revision `d6c9d916-8ecf-462f-a0e9-c5ab44b37f13`，DOCX SHA `0794e61cf72d3ca7b2bc17968b69a31a0dcda2749ff2c771c5733bef34b2e81b`；精确 P75 书签 `CS06c6dc30800d321a6a0651802929` 的两个普通文字节点合计 **207 字符清空**，native run tab、pPr 制表位、完整段落骨架保持。相对之前无目标版本，除这两个 w:t 值外 **整个 document.xml 逐节点精确相同**，opaque ZIP parts 全部字节相同。SCT／SCC DOCX 和 PDF 均与前次无目标输出逐字节相同；三份原件字节不变，采用值完整 API 响应前后相同。PDF 页数 12／30／84。证据 `drafting/e2e-drafting-manual-rebind-export-20261020-round05b/summary.json`（引用此前同批 5 个已保存 HTTP 原票＋续采 8 个），未发 API generate 或新模型调用。本轮是显式测试值，不代表真实项目适用性；清空普通文字不等于删除整个段落 XML。

核验脚本原错误也保留：初次错把未命中分支状态期待为 not_used，实际为 not_adopted；第二次把 pPr 制表位声明与 native run tab 混数，精确 XML 证明两者都保留后改为仅检查 run/tab，整树比较仍严格。Python 3.12 缺 pypdf 的第一次启动未发 HTTP，随后复用已安装的 bundled Python。原采集目录与控制台失败不追认为产品通过，不重复生成文稿。

Vetting Mini06 实际 run `46b98ae7-4215-4935-86fb-cdf8ee533a14`，同来源 snapshot `730661c6431068f7c73a9c3e2cdbd74a7779b3148e4d3dccbf2726afb98a44fc`，单 POST、0 upload／reparse／人工修改。最终 COMPLETED，17 topics 全 completed_empty、0 finding，已知 two／three paper copies 冲突未发布，**业务 FAIL**。首完成／首 JSON 导出／二 JSON 导出／导出后四处 reportSnapshotId 均为 `d9039980992d4bcd6cc105191a0bd743c0c52d755792ba7fc7aee0e41014cb22`，首次两份 JSON 完整字节相同，三格式均落盘；第五轮时间精度接缝的实际门通过，不能替代业务质量。证据 `vetting/mini06/terminal-collection.json`。

进一步逐字复核不能简化成“模型完全没识别”：topic9 首答包含有两条精确原文的 Clause1／2 正确冲突，另一条 title 为 189 码点、超过 schema 160 上限，导致完整数组拒绝；既有第二次修复重发同输入，没有前答与具体原因，返回 `[]`。另外两条含裸章节标题的证据仍不足，缩标题也不能直接采纳。正在第七轮独立修复通用 repair 上下文与真实预算／checkpoint 记录，不自动截标题、不局部发布、不改动作或放松证据门。原 17 个主题实际 sourceOrder 与 Mini05 0..8 逐值相同。

第六轮从第五轮 1,480 文件冻结树只加入 6 个已同行审查文件，净增两个测试文件，共 **1,482 文件**；snapshot SHA `534f0925749971bec2fc6bb7325aa7a841da98537c1ef17dccce4fc5725abb52`。最终正式 Maven **43 suites／550 独立 tests＝550 pass，0 failure／error／skip，BUILD SUCCESS**，第五轮 503 身份全保＋47 新身份。JAR 80,155,278 bytes、SHA `66ad1bce4ac3287470af81304d89fadd4bc800a158a610d1c393d74f9fd1e86a`，冻结 XML `build/round06-integration-reports/`、收据 `build/round06-build-verification.json`。本条时点尚待 SQL／API 备份升级。

第六轮最初 550 项为 546 pass＋4 条件 skip（漏配真实 index 输出目录与 renderer 参数）；再跑为 549 pass＋1 renderer 路径失败（私有 profile 长度159，实际 PDF 返回 OUTPUT_MISSING）。两轮全 XML／日志／状态／产物身份分别在 `build/round06-first-conditional-skips/`、`build/round06-second-renderer-path-failure/`。只修本地 runner 参数为已验证的相对 executable、全新完整索引输出／旧基线及短 PDF work root 后，同一冻结代码最终 550 全过，未修改产品或降低期待。

第六轮前端 14 个实际检查入口、Typecheck、生产 bundle 均通过，224 个文件构建前后 SHA 保持；`build/web-round06-verification.json`。首个本地编排误写两个不存在的测试入口，其 MODULE_NOT_FOUND 原票保留；读实际文件清单后运行正确 regenerate-feedback／human-review-flow，两者通过，最终结果明确引用补跑日志，没有把未找到入口算执行通过。

原广回归 36 条 failure／error 记录已逐身份建立关闭映射：14 条有正式逐例 XML 对应，16 条通过完整所选类运行及穷尽失败清单推导关闭（15 个 test invocation＋1 个 BeforeAll 容器错误，原 runner 未输出逐例 PASS，不能冒造 XML），剩 6 条 H2 文件只读环境失败。来源／哈希与逐条原因见 `build/round02-failure-closure.json`，可重算脚本同目录。原 353／3,680 批次仍为原 BUILD FAILURE，79 skip 保留，不称 30 条都是代码缺陷。新的第六轮完整广回归在独立 `build/round06-full-project/`、16GiB／独立 TEMP／renderer 根下运行中，原所选失败不排除；尚未完成，不给最终总数。

## 03:55 增量（第六轮运行等价、PDF 目标页与后继修复）

第六轮已安装于 `18084`、PID `55880`，启动日志确认运行 JAR 为 `66ad1bce4ac3287470af81304d89fadd4bc800a158a610d1c393d74f9fd1e86a`；H2 owner `38980` 保持。升级前 SQL `runtime/db-snapshot-round06.sql` 为 **1,239,392,688 bytes**、SHA `71ca92ee2e1763c39bbac3c23b24f27cbfbf8ae7318df5bc5fedc9964abb426a`。五个完整 API 升级前后字节全等：12 projects、2 partial-core variables、3 documents、0 current-run findings、6 指定 Advisory history messages，见 `runtime/round06-upgrade-api-{before,after}-receipt.json`。此处 findings=0 是新 Mini06 运行的实际当前结果，不将它改写成旧运行也无发现。

在第六轮上再次通过真实 GUI 执行同一来源、同 confirmed graph `1949922a-d463-4b45-bf9b-2ff42f64426f`、同人工采用值的重新生成。随后 **9/9 实际 GET** 取得三个新的文稿 revision，**三 DOCX＋三 PDF 的完整字节均与第五轮相等**，采用值完整 JSON 前后也相等。NTT 新 revision `5d9d2d57-7350-4646-ae43-e1c90a4630fc`、SCT `a77b775b-11db-4eb4-804a-43be55d3634e`、SCC `4882ef5f-6f08-4393-965a-9d8028e41f7c`；证据 `drafting/e2e-drafting-native-equivalence-20261020-round06/summary.json`、`browser/drafting-round06-same-output-preview.png`。这证明本次 DOCX section preparation 优化在真实保存／生成／导出调用链的结果保持，不另作新事实或整份来源质量声明。

实际 NTT PDF 全12页文本搜索定位到原预制外墙条款在物理第2页，修改后全12页已无该正文。用 Poppler 110dpi 渲染并亲看前后物理第2页：第1(a)–(d)、第2条及后续段、页眉页脚可读，未观察到重叠／裁切；边栏原指导语不在两段人工目标内，继续保留。仅此页完成了前后视觉对照，不宣称整12页逐字视觉验收。证据 `drafting/manual-rebind-pdf-visual/{text-locator,visual-qa}.json`、对应两张 PNG；两个 PDF 原字节没有修改。

Advisory 第六轮两题队列已在上述升级校验后释放，锁定原件／parsed／projection／21 chunk 与真实 replyTo12。普通题唯一 ask 120.877 秒、message20，仅1次 generation，grounded=false／12 个真实引用；四库排除与触发规则本轮正确保留，12/12 引用原件／reader 位置逐字核验、错误 SHA→409 通过，但仍错误宣称入模来源[8]中的 GS/body40 不在摘录，并以无引用 metadata 扩写宽泛缺口，**整体业务 FAIL**。这次未触发 repair，不能称实际验证了06修复上下文。合法 replyTo12 条件题仍在途，不重启服务、不新增图或人工重试；最终完整语义及请求分母随后补入 Advisory 自有文档。

第七轮 Vetting repair 上下文已独立 RED 6/6、GREEN 12类209/209、0skip／abort，进程退出0，Advisory peer 0 Must。仅候选上下文／实际 attempt input与counter预算接缝；每次 repair 增加1次 counter，generation MAX2及完整 schema／来源／业务／checkpoint 门保持。三文件冻结 ready SHA `16e971120e09298f332b56582480e472ca69e06b1f37d38f98dfa258dec1e105`，尚未部署。原两条“相同 wire”的参数化身份将按语义新名显式映射，不能为保留数字恢复误导名称。首次缺 classpath 编译日志被覆盖的证据缺口已如实记录，不称全部工具原票都完整；后续成功但 helper 线程悬留的日志及精确进程终止／专用 runner 正常退出证据保留。详见 [Vetting 修复上下文](vetting-e2e-repair-context-20261020.md)。

另一个第七轮来源性能问题已取得确定性反馈：`OerTemplateEvidence.sourceListIntroduction` 的两个重叠贪婪匹配，在缺少尾冒号的长正文上字符读取随长度翻倍约增4倍；65,537 字符原谓词实际20.0348秒，1秒门失败。假设按平方回溯、固定编译开销、重复分析放大器排序，先用原表达式／真实 private 调用验证，再做语义保持的最小改动。原三份完整 parse／evidence 约293MB及完整私有 index 基线已冻结；首个 GREEN 验收 helper 因巨大 JsonNode 错误比较／JUnit 展开导致4GiB OOM而未完成，原票保留，改为完整文件逐字节比较并用独立16GiB重跑，尚不计通过。完整第六轮广回归的冻结源码和进程继续保持，未热替换此后继改动。


## 04:13 增量（第七轮已安装、真实模型终态与PDF版面概览）

第七轮正式冻结树 **1,484 文件**，从第六轮仅 3 changed＋2 added；snapshot SHA `aecec4f19ec662391cf74cc55541b0ae9d68c55d723bab905a6417a83d4296ee`。正式 Maven **51 suites／808 独立 tests，0 failure／error／skip，BUILD SUCCESS、exit 0**。第六轮 550 用例中 548 个身份精确保留，另 2 个相同参数调用按真实修复语义明确更名映射，新增 258 个身份；不能称 550 个名称全部相同。JAR **80,156,448 bytes**、SHA `72d818409d1bcded40a5e44cb9166af24d513636cd3bb02c46d8a803142214b3`；收据 `build/round07-build-verification.json`，冻结 XML `build/round07-integration-reports/`。基线相对路径 preflight 拒绝原票保留；最终 stderr 仍有未归属 cmd 路径诊断和预期 DOCTYPE 拒绝，正式退出／XML／源码及包身份均已核验，不称日志完全无诊断。

第七轮已安装 `18084`、PID `22892`，H2 owner `38980` 保持。升级前 SQL **1,244,947,200 bytes**、SHA `a898726bb4c5f64c992f238f7eab13410fcf46025f584d2196c01b34b4e5a786`，`runtime/db-snapshot-round07.sql`。升级前后 **6/6 完整 API 响应字节相等**：12 projects、2 partial-core variables、3 documents、0 current Vetting findings、8 conditional-session messages、2 latest ordinary-session messages，见 `runtime/round07-upgrade-api-{before,after}-receipt.json`。之后 Mini07 已写入，下一次升级须新备份。

本轮包含的 OER 清单引导正则性能修复已实际同测试 RED 11 项＝10 pass＋1 性能 fail；65,537 字符无尾冒号输入原 21.4637646 秒、GREEN 0.0024698 秒，相关 9 suites／270 全过。保留 Java trim、ASCII 空白、Unicode 字母、单连字符及结尾语义，拆成两段判定，不缓存或按来源特判。正式包再次核三份真实原件完整 parse／3,999 段 lexical 决策／完整私有 index，共 **9 文件逐字节等于旧基线**。早期巨大 JsonNode 断言展开导致 OOM 原票保留，最终使用完整文件流式比较。详见 [清单引导性能](drafting-source-list-introduction-performance-20261020.md)。

Vetting 修复上下文随第七轮安装，保完整原 user／source，并将被拒绝 raw 与固定拒绝原因作为不可信候选加入实际 repair 输入，绑定各自 hash／完整 counter／持久化与派发；generation 上限 2、整数组 schema／来源／业务门保持。每次 repair 比旧路径增加 1 counter，不猜通用总数。真实 **Mini07** 唯一 POST run `05f6ccc0-bfe5-4281-a0cd-661fef576d9f`、同 snapshot `730661c6431068f7c73a9c3e2cdbd74a7779b3148e4d3dccbf2726afb98a44fc`，最终 **FAILED**：17 topics 执行、17 generations、0 attempt2；15 completed_empty，另两主题触发 pending_facts_require_review／checklist 业务门。**真实 repair 分支 NOT_EXERCISED**，不能称本次证明修复有效；失败运行没有 report snapshot，不强行导出，不追加人工重试。逐 attempt／source 与 raw 离线复算仍在继续；原始终态在 `vetting/mini07/009-terminal-run-before-any-export-response.{bin,json}`、`terminal-collection.json`。本次未启用 VettingReviewProbe，planner provider 计数没有独立观察；只能将持久化 attempt 实证与冻结代码路径推导的 P 明确分开。

Drafting 第六轮已生成三 PDF 的 **126/126 页**全部 Poppler 100dpi 渲染，11/11 contact sheets 全部实际查看；12 页另看整页细图，17 个稀疏／表格／公式页面与现成源模板 preview 的相同 physical 页逐像素相等。当前 SCT 分数位于物理第21页（printed SCT18），分子、分母、分数线与定义可读；SCC28 保修表和 SCC56／58 公式无观察到的裁切／重叠。NTT5、SCT4／7／14、SCC3／41／57／74 的稀疏内容原模板已相同。范围内 0 新增布局缺陷、0 未关闭概览疑点；三输出 PDF 与三源 preview SHA 前后保持，0 API 写。边界为未逐 glyph、未做 Word golden，109 页没有逐源像素对照，114 页未另做整页细看；概览不等于逐字源质量通过。见 [PDF 版面概览](drafting-round06-pdf-contact-overview-20261020.md)，`drafting/round06-pdf-overview/review-ledger.json` SHA `dc31da35a823293a6c1123e7e780b060a5167d94fe2f3980a5b6923b9c97cf1a`。

Advisory 第六轮条件题 message22 已完成，replyTo12、grounded=false、1 个真实引用，仍把 prior-to 截止误写为 `[tender−42,tender]` 窗口并同时给出矛盾截止日期，**业务 FAIL**。本轮两题合计 **16/16 应用 HTTP、2 generations＋3 counters（含1 history-only）**，两题均未触发06 repair。完整旧 history 六条与 parent 保持，读原件／reader／错 SHA 拒绝通过；不能从存在错误历史直接断言它是唯一原因。第八轮仅把同一个不可信 history 块放在当前任务／完整来源之前，并修正固定标签方位词；不改 history membership／parent 语义，不写日期或题目白名单。目前同例有效 RED 1 fail、GREEN 1 pass，扩测与 peer 中，**未安装、未新模型调用**。

Root 已在真实 Chrome 打开 message20 原答和来源阅读器，依次点击 body/32 与 body/40，GS CEW、42 days prior 和 PSS／GS／SL 优先关系均在原件完整段落中实际高亮显示。原答仍声称 body/40 不在摘录，这个业务失败也能在前端复现，不能归为 reader 无法定位。截图 `browser/advisory-round06-message20-{source-reader,gs-body40-highlight}.png`、完整 AX `browser/advisory-round06-message20-source-reader-ax.txt`；本过程只读、不提新问、不刷新索引或修改会话。

完整第六轮广回归仍在独立冻结树执行，部分 XML 不是终态，不提前宣布原 H2 错误已关闭。4 个 document-binding 原件条件 skip 已发现独立 fixture 属性，另开同06源码专项复验，不改本轮 Full 的 skip，不排除旧失败类。08:00 仍只验收进度；Goal active，不作三模块整体业务通过声明。

## 04:20 增量（完整广回归终态、只读等价与第八轮准备）

第六轮完整默认 Maven 发现已正式结束，未排除旧失败：**359 suites／3,759 testcase＝3,660 pass＋9 failure＋14 error＋76 skip，BUILD FAILURE、exit 1**，42 分 07 秒。Maven 总数与完整 XML 节点一致，原1,482文件冻结源码结束逐项核验无变化，0真实模型／OCR／应用写。证据 `build/round06-full-summary.json`、冻结 `build/round06-full-final/`。旧3,565个正确项中3,564仍pass，1个转fail，不能写“0旧正确回归”。该项为 `AdviceDocumentScopeIntegrationTest.naturalFlowchartQuestionUsesApplicationHarnessAndGraphRepairSharesTheTotalBudget`：中文问题的原夹具声明全英文，触发语言门4205，属于夹具／新校验行为不匹配，不能写成环境错误。第八轮已另做还原07生产源码的原例失败对照，仅在 authored fixture 的问题明确追加英语请求；中文题拒绝英文回答的原安全测试仍保留。后续专项通过不改写 Full06 原红票。

其余8 failure为离线 replay 缺fixture4、共享note缺fixture3、Python wire fixture启动1；14 error为既有共享note空指针2、wire工作流上下文2、retrieval binding scope/size4、H2-file只读6。仍按逐身份调查和环境入口记录，不将它们整体追认为通过。旧22failure的12转pass、8仍fail、2身份变更待明确映射；原79skip的3转pass、76仍skip，不把新分母套回旧总数。独立真实原件 bindings4 专项正在收票，不混入本Full分母。

第七轮同一个八字段性能项目只读复验 **5/5 HTTP**：同current `5af2a8a1-c641-4384-b439-048011fcf393`、draft `244913b9-218f-412d-a1ce-ddd55ee0b576`。完整 variables 响应3,691字节、SHA `adb0882802a6bc03c21796d42fa992bafdcc44b640025fc7c0739ee39cf747ba`；graph41,112字节、SHA `9953311303cd5d918109c9648918f9d8e8b44821c1554eb5141f6923e4baff07`，均逐字节等于第五轮，三份原件亦逐字节同。变量11.109秒、图谱15.922秒，相比第五轮12.046／14.641有升有降，不声称整体提速或固定倍率。明确人工首值／理由保留，其余七值仍未采用，0上传／生成／模型。证据 `drafting/e2e-drafting-read-performance-20261020-round07-after/summary.json`。

Root 真实 Chrome 只读核验 Mini07 页面，最终显示运行失败、保留17/19执行进度及15可靠检查包，展开可见17个主题的实际状态、两处业务拒绝和未完成限制。失败报告导出disabled，运行按钮可用，未点击重新运行。最初恢复过程短暂显示处理中，随后正常变为FAILED，不作为卡死缺陷。证据 `browser/vetting-mini07-failed-ui{.png,-ax.txt,-receipt.json}`；未发新模型或写来源。

第八轮仅为 Advisory history 呈现顺序的2行生产变化及对应2测试文件；137项独立GREEN／peer0Must，正式集成从07冻树仅3文件变更派生，53个实际selector的身份并集预期 **862＝808旧＋54新增**，不是808＋137。此时仍在正式package，尚未安装。07 manifest 文件自身SHA `a2853180c11f511664350b6e2e2085baeedb368f708c36bb967f91ba9f073255` 与其内部树digest `aecec4f19ec662391cf74cc55541b0ae9d68c55d723bab905a6417a83d4296ee`是不同对象；1,484源逐项实读复核保持，不能误称摘要不同即源树变了。第八轮使用相同pathTABsha算法的树digest `d3f355b215a67cb840af616926644f9c048508fb61f37d2dd96bd5c92efd08ce`。

所有模型owner已明确释放后，Root冻结应用／模型写并取得第八轮升级前 SQL：**1,251,538,784 bytes**、SHA `765ff6715afdd121d96d33d0acef3ed705d625f55743ed3500facc5f5db09a20`，`runtime/db-snapshot-round08.sql`，含Mini07真实失败及历史。升级前 **7/7 API** 原票已保存：沿用六接口，另增加Mini07完整FAILED run响应5,457,720字节；还需装包后逐字节比对，未提前记为相等。备份期间H2 owner未重启。后续真实空会话条件题只作为预声明诊断计划，尚未释放／执行，不能预先计业务通过。

## 04:26 增量（第八轮实际升级、保留数据与后继独立验证）

第八轮正式 Maven **53 suites／862独立 tests 全pass，0failure／error／skip，exit0**。808个第七轮身份全保留；新增54（21个此前未选session旧例＋1新排序例＋32个scope例），137与旧集重合83，不能重复相加。1,484源构建前后逐项SHA保持，07→08仅 `AdviceService.java`、`AdviceSessionIntegrationTest.java`、`AdviceDocumentScopeIntegrationTest.java` 三条批准路径变化；九份完整原件 parse／lexical／index 字节同基线。JAR **80,156,454 bytes**，SHA `7ff876a7465879e50109ffee635ce295867979917492231aeba80876d73e7dd4`。正式收据 `build/round08-build-verification.json`、冻结 `build/round08-integration-reports/`；核票helper首次没对class#method selector规范化而拒绝，原票 `round08-verification-attempt-01-rejected.json` 保留，仅修checker后复核同一Maven产物，没有重跑或修改产品换绿。

Root实际重启精确自有后端后，第八轮 **PID35392／18084** 启动日志确认成功，H2 owner38980未重启，已安装文件SHA与正式包全等。**7/7完整API升级前后字节相同**：12项目、2人工采用变量、3文稿、0当前发现、Mini07完整FAILED运行、8条历史条件会话及2条普通题会话。Mini07完整运行5,457,720字节、SHA `9ac2607fcc2d13c13ccc87608b0fdd6d000752df5f40b4d04a49bcee0a493a01`；所有其他响应SHA见 `runtime/round08-upgrade-api-after-receipt.json`。这只证明已采集应用数据跨本次升级保持，不证明文件H2环境修复或业务答案正确。

在上述核验后，Root才释放Advisory第八轮单题诊断：新的空会话、逐字沿用原human message11完整假设问题，最多8个应用HTTP／1 ask／2 generations／2完整counter／0historycounter／0manualretry；同doc2原SHA／parsed／21chunk／contract及project_contract。该窗口在途不重启后端，不加入额外图、再问或上传。它测试没有旧错误assistant历史时的实际回答，因问题呈现与旧replyTo22不同，不能当严格一变量对照，也不用于证明08排序修复已改善模型。

真实原件 `DraftingDocumentBindingsIntegrationTest` 独立复验 **4/4 pass，0skip／failure／error**，311.429秒；同第六轮源字节，只显式提供测试要求的bindingSourceDir及原件原字节重命名fixture、短rendererRoot。正式XML `build/round06-bindings4-final/`，汇总 `drafting/round06-bindings4-summary.json` SHA `19fb9571c6f65b01d7140e850793a153c8c819d6fc614175cd5cceb03e2831a6`；原Full四个skip继续保留。Full终态汇总自身SHA `f6c2d549aa806536c2781c91dc6f7d1b4f1eccc142079e303e72a3bab1043e2b`，独立失败台账 `drafting/round06-full-failure-ledger.json` SHA `c22c3e3d1c9541aa515b44a41d135406b352400c909242dc92bdd6231a7d955e` 已明确两处旧身份替换映射到三个新pass用例，不再留名称缺失未知，也不修改旧名字或旧红票。

Vetting后继新policy候选已经离线RED：3例中来源引用无效仍不retry一例通过，业务shape首答／两次shape无效的两例失败，因原07都只生成一次。正式比赛资产要求适用性失败先修正且不得无限生成，统一spec要求有限方案及总修复上限；未发现禁止在既有MAX2内修形状的要求。Root授权只做通用有限修复：同完整业务门与整数组、原raw／理由不可信上下文、fresh完整预算／身份guard／持久化与派发绑定，源引文无效不触发，不增第三次，显式新policy／checkpoint身份。仍是离线09，未装入08、不新付费调用，不能追认07已支持业务shape修复。

另一条环境专项严格从06的1,482源派生 `build/round09-wire-project/`：先核既有关闭收据只指向可变主target并非当前冻结逐例XML，再使用本机现成retrieval-gpu Python、4份SHA锁定的保存原fixture和原wireFixture，显式满足实际测试属性。六个类正式 **109/109 pass，0skip／failure／error，56.987秒**，0产品改动／0新模型／OCR；完整XML及server操作计数正在冻结。它用于逐身份补充Full06中8failure＋8非H2 error的独立证据，不改写原Full；6个H2错误不在这个专项选择内，仍为未关闭环境问题。
## 04:47 增量（第九轮部署、真实审查观测与跳过项补证）

第九轮首个正式 Maven 在 `testCompile` 失败：新增测试使用 `var`，与项目 source8 不兼容，**0 tests**。独立 javac17 GREEN 没覆盖这个语法兼容门，不能把它称为环境失败。原目录、源码及日志收据 `build/round09-attempt1-compilation-failure.json` 保留；仅将该测试改为显式类型，生产与workflow字节未因此改变，独立GREEN04 **217/217**，再由Drafting作逐文本delta-peer0Must，未改断言换绿。正式后继采用独立 `build/round09-attempt2-project/`、outputs／短r09a2p／TEMP，不覆盖首票。

正式第九轮第二次 **54 suites／870独立 tests 全pass，0failure／error／skip，BUILD SUCCESS、exit0**；原862身份全保＋8新身份，1,485源前后逐项SHA相同，相对08仅2changed＋1added，九份完整原件 parse／lexical／index全字节同旧基线。JAR **80,157,903 bytes**、SHA `c443f624f6f7e57ee1181b9bf4b4a17cea4ec4469b980e4e87c6916394251e42`；收据 `build/round09-attempt2-build-verification.json` SHA `cd9061de03b46045ea97e5e5d831bd02fba26433d10527b11609b5c377efb77a`。manifest文件SHA `bef6e95ce9bf0f6bfc9d9cc878aaa484b7db72d9e32f645474e7e4339f17bd38`，pathTABsha树digest `fbd2beacdac2bd304113beaded7c04dc5fe318ee5d943b23fb0032a50c59d51b`。详见 [业务形状有界修复](vetting-e2e-business-shape-feedback-20261020.md)，原Mini07 FAILED不因代码GREEN而关闭。

升级前新SQL **1,251,872,688 bytes**、SHA `5e671400b15ee4ede05ee12732dfbf30176b4d448582252538376c9867ceb681`，`runtime/db-snapshot-round09.sql`。确认无应用／模型在途后，Root精确自有重启为 **PID27040／18084**，H2 owner38980保持。升级前后 **8/8完整API响应字节相同**：七旧接口另含新empty session两条历史。新会话响应297,686字节、SHA `56785e150e05c387d6167b71332060968edbb7a7221db6310476dfd556a82ddd`；保全收据 `runtime/round09-upgrade-api-after-receipt.json` SHA `162672884028ad1cf2b83696b797e09ab78de7cefc29e9276326e27860b75a87`。之后Mini08新写发生，下一升级须更新备份。

本次只在自有Java子进程环境追加既有 `consense.vetting.probe-directory` 的 JVM -D选项，其他服务环境不改，不新建观测付费调用。JVM启动stderr确认准确选项；第一次 jcmd 只读属性检查因本机AttachProvider／native attach不可用失败，原工具输出及边界记录在 `runtime/round09-review-probe-startup-verification.json`，未为此安装／重启或声称附加成功。随后实际Mini08创建 `00001-run_started.json`，run／project／probeRoot精确匹配，确认应用绑定激活；`vetting/mini08/probe-activation.json`、run_started SHA `ad7dd6b1b573cedc92cb55085901ae1b6bf0b0e1b0f909e2960588dbc9f4cbdf`。P可按真实应用plan中actualPreviewObserverCalls累积；不是独立providerTCP／计费观测。

Root在保全核验后才释放 **Mini08** 唯一POST：run `ae8d3559-00f0-430a-962f-6749bdd53d58`，同source snapshot `730661c6431068f7c73a9c3e2cdbd74a7779b3148e4d3dccbf2726afb98a44fc`、9来源段、17主题，每包MAX2／generation≤34；counter为P＋I＋R＋K，≤P＋51，在同9of9全局准入路径条件上限68。预声明SHA `e327821fc3491786421eb1ba1c72e75b6e93b5e8679701f21f64643e77ec1a4f`，0上传／重解析／人工retry／duplicatePOST。此条时在途，不提前给业务结论；仍要求真实2vs3冲突保留、99.8假absence／裸标题不得发布、真实repair输入与各自完整预算／来源／checkpoint／派发绑定一致。FAILED不强行导出，COMPLETED才核首快照与两次导出。

Advisory08单题已正式完成 **8/8应用HTTP、1ask、2generations＋2完整counter、0historycounter／manualretry**，118.679秒，session `16f7967b-503e-49f1-bc2e-fda04bba51fe`／message24，code0但业务FAIL，最终只有界限／unknown、0citation；不声称该答引用reader通过。实际原件29,528字节／原SHA以及wrongSHA409通过，另用源文件reader做body40同源控制定位1/1，不冒充message24引用。实际repair逐字保留7,194字符首raw的JSON转义、两条真实reason，system和完整currentUser前缀保持，各轮完整counter输入等于派发字段；真实06修复上下文终于被触发验证，但不代表业务正确。两次raw均未计算日期，不能又记作“看见42days却算错”。独立累计为238 Probe＋2 SSE＝240已捕获应用交换、31prepared／31counter、28generation（27完整＋1旧abort）、3history-only；不混加Root／浏览器未捕获请求，不把provider响应当业务通过。

用真实OER run-evidence和08实际lossless codec解码确认首损层：新问题在两dense lanes均21/21，RRF提交21条且目标body40排1；rerank后目标 **15/21、score0.0548800**，topK10截掉，10个单独observed_source_blocks的closure没补回。旧06问题包含原human9的GS CEW母题与澄清，目标rerank **1/21、score0.981395**；实际问题／hit集合已变化，不能当严格A/B或只归历史影响。native outline无heading、sourceClauseRanges空且family unresolved，不能编造parent。逐ID、alias→modelUnit→parts.text及原请求SHA见 `advisory/round08-source-selection-diagnosis-01/diagnosis.json`，语义／repair独立审计 `advisory/round08-real-answer-analysis.json`。

后继Advisory10已获具体离线实施授权：对照原legacy.retrieve已有的小资料stuffingLimit行为，仅在原canonical非空命中且整个permitted corpus正文在原阈值内，保所有原ranked／fixed／table顺序、分数与公开编号，再经现有canonical.sourceHits补足真实单位并给各自真实alias；context_only不升级review／adoption。零原canonical命中、large、reference_only及排除来源保持原行为，完整wire仍fresh预算与全部来源／会话／模型／stream前缀门，不截断、不隐藏fallback、不在repair换alias，不加本题词／日期／文件名规则、不改rerank／index或另造truth。此时仅独立RED/GREEN，**未装入09／未新模型调用**。

跳过项独立补证继续严格用Full06的1,482冻结源及原保存capture／fixture哈希，不改代码或原Full分母。skip-replay01 **37/37**、02 **63/63**、03纯wire prepare **1/1**、04 standard/tender NTT差异 **1/1**，均0skip／failure／error；与原binding4合计106实际pass，映射 **59个原skip入口**，工厂／整类placeholder展开不能把它写成原106个skip。各票 `drafting/skip-replay0{1,2,3,4}-summary.json`、独立XML `build/skip-replay0{1,2,3,4}-final/`。0新OCR／模型／外部引擎／共享应用写，旧历史确认值未写当前项目。剩17入口正分别记录缺runtime／旧capture未定位／大输入内存预算及已有专项覆盖，不为凑数重复binding或实际原件专项；原Full06仍FAIL／76skip。

## 05:01 增量（Mini08 终态审计、小资料补全独立复核及跳过项最终分类）

Vetting Mini08 已于04:49:07终态并释放，实际耗时202.276394秒，`COMPLETED`／`incomplete`、18次generation（含1次业务形状修复）、0findings，**业务仍FAIL**。实际应用probe计数为P17＋I17＋R1＋K1＝36，planner cache0／observer failure0；provider声明输入606,177＋输出1,629＝607,806 tokens，不能作为独立TCP或账单观察。17份首次完整输入与Mini07逐JSON相同，18次完整来源／sourceOrder0–8保持；真实repair包含原raw、固定拒绝理由、完整原user后缀及各自fresh预算和来源／packet／派发绑定。修复请求本来已要求保留正确有源记录，不得为了空数组丢弃，实际第二答仍是`[]`；未找到提示接线或字段丢失的确定代码缺陷，因此本轮不追加同题微调或重采，不归一化action／checklist来绕过业务门。

完整验收 `vetting/mini08/acceptance.json` SHA `d637633df204d1fdfb976eb57aa2967723b1385f28c14036886f461f9dd42a64`，真实修复链 `repair-context-lineage.json` SHA `9bbcfa8248a5501613ae711503ffa278ddf1037a5747d84356b75b225a077dd2`，计数界限 `counter-observation-boundary.json` SHA `da22771464c2e31b08133b3c194676b3c555480e958a49475e3e016434c87222`。四处报告snapshot同为 `4936af81342469c646b226d175577560ccf340b88e07ea6f4dbf0fb8100f2784`，两份3,425,488字节完整JSON相同；DOCX18,768字节和PDF68,342字节身份同。报告保留`full_countercheck_unproven`、分母／unknown和“0发现不等于无问题”。PDF39页只针对1／37／38／39页视觉复核，未见新截断或重叠，其余35页未重复全看；99.8和裸标题本次没有被发布的原因是最终空发现，不能据此给通用误报精度通过。报告限制收据SHA `a46decd7b7df13595c9a759614425d9d4b5e0ad5eb90a66c599615b73005f2f2`。

Advisory10 小资料补全候选已经冻结：现有canonical原始命中非空，且当前允许的完整语料正文不超过原stuffingLimit时，经现有sourceHits追加未表示的真实单位；不改原ranked／fixed／table顺序分数，不重用别的正文alias，不升级unknown／review／adoption，原完整预算／来源／session／model／stream门保持。生产只动AdviceService，另两测试文件。最终11类 **176/176全过、0skip／failure／error**；完全相同的最终33个公开方法恢复08生产作对照，7个新增缺正文断言实际失败，旧31k大语料夹具通过。早期不一致fixture及测试辅助日志展开巨物的OOM原票保留，未把未完57条报告当整轮通过；最终使用直接反射设置共享字符串大逻辑输入，未为测试OOM修改生产阈值。ready `advisory/round10-small-corpus-ready.json` SHA `4714422d828877e89fa7a307b808314986e9b9fa5e9fc1dd558f17cab01a78ff`，当前是独立peer中，尚未正式集成、部署或执行下一真实模型题。正式集成须从已装09树只覆盖这三条批准路径，保留09的Vetting修复；不能直接用作者的08基底整树覆盖。Mini08写入后的新SQL及九接口保全脚本已备好，尚未执行备份／重启。

原76个skip已逐入口分类，skip-replay05大UTF8回归新增独立 **4/4 pass**，逻辑输入715,827,883 UTF16、16GiB上限、180秒超时界限，实际54.282秒，未超时，所有自有JVM已退出。至此六批合计 **60个原skip入口获补证，对应110个实际pass**；不加进原Full正式pass，不替换原76分母。最终账本 `drafting/round06-all76-skip-classification.json` SHA `9fe5697d0ba7d7f11e4a5869af21537dafabe41cc00d784f8af922dea2a54745`；[补跑说明](backend-full06-skipped-replays-20261020.md) SHA `7db97cf777ade80ed0cd6d17ec08e22d63b34142ff338113a19031187b3075a3`。仍有16原入口未执行：desktop-x2t专属9、特定旧Advisory失败capture3、旧语言capture1、291变量旧图1、已有相关原件专项覆盖而未重复的converter／binding2。随后只读定位发现旧worktree可能保留精确capture，身份核对和独立补证尚未完成，未先改记已通过。

## 05:07 增量（第十轮正式集成与升级保全）

Advisory10 独立同行复核0Must，`drafting/advisory10-final-peer-review.json` SHA `b277bc73a5a2017bb166e6631f135219d7178f2a8ab1cc680e96faf05de92bf5`。Root从已装09的冻结树逐文件派生，只覆盖批准的两改一增，合计1,486源；保留09全部Vetting修复。56个selector的实际测试身份并集为 **909＝870旧＋39新增覆盖**，其中本轮新写16项、另23项是此前未纳入正式selector的canonical旧方法；独立176项与870旧集重合137，不写成1,046。正式Java source8 Maven package **56 suites／909独立 tests 全pass，0failure／error／skip，exit0**，源构建前后逐项SHA保持，九份完整原件parse／lexical／index字节均等旧基线。新JAR80,158,600字节、SHA `6e6c8b24d96315f2da5bfb7fa96f191bb8cece1ebffece3e0faf61d03c6f89bb`；收据 `build/round10-build-verification.json` SHA `52cf837d6f0fb32c5b19a07708f6e668cf42ab049ed20792d941f7921076ca08`，manifest文件SHA `3d5db2658c202025620abf9dfac59a01c5934628999f22040f2e3f4735fedbea`，树digest `e16a7dd0ec940db123003214262f12fd14bce1188401681694a80b163cdf927a`。

Mini08后新SQL `runtime/db-snapshot-round10.sql` 为 **1,262,586,832字节**、SHA `edf6264d798f6772cc39dfcbc69e11ab8caeaf9f6592de36e474f055e0d9ca48`。Root确认owner均释放、应用／模型写冻结后保存九个完整API原票，再仅精确停止自有27040，升级为 **PID57040／18084**；05:06:30启动成功，H2 owner38980保持。升级前后 **9/9完整响应逐字节相同**，新增Mini08完整run6,071,952字节、SHA `88dc9ba62dfdb8469df33db31bce312b6a96e183e7695d87cede73e0c64a13fd`，包含真实repair和失败业务状态。保全收据 `runtime/round10-upgrade-api-after-receipt.json` SHA `3e286a96d5e8ae6ea39c6828828a31fa71baf9c85ee9a713230394ebc12e7342`；安装核验 `runtime/round10-installed-verification.json`。只证明采集数据跨此升级保持，不代表H2文件环境恢复、完整广回归或真实模型业务通过。下一Advisory单题草案尚未释放，若产生新写后再重启须更新备份。

Root实际Chrome刷新Mini08既有页面，短暂恢复态之后显示任务结束19/19、17个可靠检查包、0发现、相同报告snapshot；导出可用，明确警示任务结束不代表覆盖完整、497字符分母只指已采用canonical观察。未点击重跑或再次导出。证据 `browser/vetting-mini08-completed-ui{.png,-ax.txt,-receipt.json}`。Drafting主页面亦保持测试人工采用false、NTT已保存revision `5d9d2d57-7350-4646-ae43-e1c90a4630fc`和已删除P75；没有未保存改文。

## 05:11 增量（五个精确旧入口回放与第十轮真实单题释放）

精确旧capture／291图定位后，新增5个原skip入口实际执行 **5 tests＝4 pass＋1 failure，0error／skip**。三组Advisory原答／alias／source DTO和saved291原图4项通过；291原件5,698,309字节、SHA `5c002b7a1a01c7998b64287cfe7ff37ea1796ccb306f1c5bb9fe6680b25cd804`。第五项旧language raw缺少当前要求的subDimension／factBasis／pendingFacts／suggestedAction，冻结06原生产先在businessDetails门安全拒绝，未到旧测试期待的后续origin门，故原断言实际失败；不补填原raw、不放宽门、不改成skip。同类使用完整业务字段的既有5个authored来源门用例在Full06原XML已通过，新summary列精确身份，本次未重复运行。

合并此前补证的时间截面为 **65个原入口有执行证据（64通过、1失败），实际115条测试＝114pass＋1failure**；余11原入口未执行（x2t9＋精确converter／binding2）。仍是独立补证，原Full06的3,759／76skip／BUILD FAILURE及旧账本字节保持。汇总 `vetting/legacy-found5-combined-summary.json` SHA `6590e9b4140ecbab0a7a18c61053123bc3c4bc98aff8f7a9176ff39dc5ca6e98`；language失败收据SHA `d18747a4a9ff3d9bbb086d76cff54f66ae602ddcb98836572139a5390f07f5ed`；[补证说明](backend-full06-found-capture-replays-20261020.md)。两次独立项目各1,482冻结源、10份原fixture及复制件前后SHA保持，0共享应用／模型／OCR／活动DB／ACL更改。Full10计划会启用这些实际原fixture，安全拒绝导致的旧断言失败继续保留；原生PDF两个extra门明确使用DraftPdfRenderProfile.libreOffice，不以LibreOffice替代x2t专门验收。

Root在正式10、安装身份及9/9保全通过后封存并释放Advisory10唯一单问：预声明 `advisory/round10-empty-session-execution-plan.json` SHA `04acceb79bebb3073eb46ba674f02ba944e8c76a82a130d891532beeafad84d6`，原human11完整假设问句SHA保持 `843698c9868e3890375250d9f58045ece6ca253ff04ddbaea6cd00a8d9e070bf`。预算固定8应用HTTP／1新空会话／1ask／至多2counter＋2generation／0historycounter／0人工retry，不上传、改索引、换模型或添答案资料。executor首版准备目录计数已在任何请求前改为原始response receipt分别核实际counter／generation；准备请求不算调用，counter-only明确拒绝与dispatch未知分开。旧08双响应、authored counter-only、无回应unknown和空目录四种离线auditor检查通过，v1原字节保留。最终executor SHA `f99d24bba2c954b57df8d756a42c0aefd0cd62d8f9ef47bd10218d02b8b6bca8`；此时单题执行中，Root冻结PID57040及来源／配置，不提前给正文入模或业务通过结论。

## 05:18 增量（第十轮真实入模／条件计算改善，保留剩余问题；Full10已启动）

Advisory10唯一窗口已经终态并释放：session `cce26d85-3b44-4c9d-b80a-b73fe7d7e56e`／assistant26，**8HTTP、1ask、1counter＋1generation，0repair／historycounter／manualretry／dispatch unknown**，ask87.331秒、code0，grounded=false／basis insufficient保真实采用与版本来源未知。经正式10相同codec解码实际请求、逐alias→memberMaterialRefs→完整parts.text复算，21单位／21alias完整；目标body40自己的 **[18]** 对应完整524 UTF16文字、原canonicalChunkId／原件SHA／API parsed hash，发布DTO也同源，score0／unranked_context／actuallyCited=true。最终wire收据SHA `dc5b65eefd3cb54585d5a4ac8abdb4d536e9bfc4ad17062d0bce81fe8e0a6b23`，不把prepared阶段尚无DTO的false误记为最终缺陷。

本次实际回答正确区分假设截标日2026-12-15减42天的截止日 **2026-11-03**，以及三份假设修订中最后实际符合条件者 **2026-10-15**；11-20晚于截止日而不纳入。独立Python从原问题四个日期及原条文42days复算，三个候选分别早于截标105／61／25天。保留道路／渠务且工地界线外的适用条件及所有真实项目事实未知。仅来源可用性和核心条件计算子项通过，**整体仍有残留问题，未记为Advisory整体验收通过**。语义核验 `advisory/round10-real-answer-analysis.json` SHA `e849a243c4d63d5bb4f719a2c43420c0cc38a4fb91deb02ae1717f1b0c4a1ba7`；累计已捕获应用交换为246Probe＋2startedSSE＝248、32prepared／32counter、29generation（28完整＋1旧abort）、3history-only，不混加Root浏览器及JDBC读取。

Root真实Chrome恢复该已保存会话，实际点击本答复[18]，原件reader准确橙框高亮body40完整GS CEW段，前一段工地界线外条件可见；中文回答的11-03／10-15计算与引用同时显示。未提新问、未刷新索引或改来源。证据 `browser/advisory10-answer26-source18-receipt.json` SHA `6c51a7df337e1f1e5af6c4edf6f82879b29fe1816eedadb7c9f0022f8d5b6aa6`、AX及两张实际截图。新答有四个实际citation，[18]为本轮真实reader验证目标，不冒称其余三引用均做了新浏览器点击。

保留三项残留：原模型analysis写“截标日后42天窗口之外”，其时间方向措辞错误，虽然计算和筛选正确；通用R7 missing_evidence被固定映射为deadline_tracks，扩大成无据双轨期限及不能确定最后期限；uncertainty已渲染dynamic_version，Service又按同一envelope.missingReason重复追加。正式10 compiled harness离线重放生成content逐字等实际API，首损层收据 `advisory/round10-publication-first-loss/receipt.json` SHA `4137294697e1ee038e45a8ba30e17e352bd526a7b2762e4fe4afeb84390c70fd`。Root只读JDBC精确读取assistant26的持久advice_checks_json，18,134 UTF8字节、SHA `8f696d3d7d5435a66e5745bd4172ba488e174a7c2b659aea8969e84fcc93579d`，id／project／session／role／单行均断言；raw三个section与ledger实际attempt完整相同，0rejection／1attempt。新第11轮只离线修后两项确定代码问题：R7通用缺证fallback用已有other_fact，显式deadline_tracks仍保；仅按gap身份去掉已在limits／uncertainty实际渲染的重复尾条，保未知、原checks、流式前缀和全部安全门，不改原模型错短语，不新模型重采。

独立Full10于05:13:10启动，JavaPID58372、`build/full10/project/`，从正式10全部1,486冻结源派生，树digest仍 `e16a7dd0ec940db123003214262f12fd14bce1188401681694a80b163cdf927a`。默认全部发现，无-Dtest／exclude／skip，16GiB、90分钟墙钟上限；使用已存在retrieval-gpu Python、绝对TEMP、65已定位原入口的原fixture／62pins／11copies，另启用两项明确支持LibreOffice的原生PDF方法；不设置任何x2t专门属性。子进程清理继承的模型凭据与JVM覆盖，仅允许本地fixture／MVC／过程内H2及自有renderer。完整计划SHA `93c4182d00d6856de25fa78f4bd4ce171661afedfeeecdf54be44283de344ed7`，副本manifest SHA `467c19da10c81197126b5fa28eb465e85ca7f6bbf702657a2e2580961bcea42b`，启动票SHA `f63d2d71743d938e664e308b63d2df01a94fe463b8d1614aeecc1d63e0ede850`。05:18已有103份完整XML但整轮仍在途，不提前给总数或通过。原Full06和所有独立红票保持。

**05:24独立业务复核补充：** `vetting/advisory10-business-peer.json`，42,711字节、SHA `d085cf3d565d2f340208bdd1ebda54c4e38ce84f307d42a17faf45ad2b22a2d4`。同行独立确认核心条件日期子项与body40实际可用性通过，不因grounded=false直接否定条件解释；同时确认完整业务仍有语义残留：工地界线外的适用条件来自[17]body38，却挂[18]body40（后者无该句且relations为空）；“SL仅由自身发布说明承担截止规则”和非该分支修订“一概没有直接合同效力”均超出供给依据。两类均是原模型主张，未静默补引用或改原答。21个对应sourcePosition的原DOCX正文计入tab／br后规范化空白均与canonical文字相等；body40完整524字精确相等，原生另有尾tab。此范围只是一份原件21个来源位置的文字核对，不是整体原件版式或解析质量验收。0新增API／模型／测试重跑／重启／源码修改。

11在任何产品修改前已取得有效RED：65测试中7个预期failure、0error／skip，覆盖通用R7两例、三语言同gap重复尾条、流式已发布前缀之后多余尾条、两个已接受受影响节之后再重复envelope。对照负例均保留：envelope-only仍显示、analysis字面提到同句不能吞真实待核尾条、被拒uncertainty不能替代已发布提示、显式deadline_tracks及真实双轨缺证仍受原门限制。后继GREEN与独立peer尚未完成，不能称这轮修复已部署或模型业务已改善。

## 05:43 增量（第十一轮正式安装与十接口完整保全）

正式11从10全部1,486文件只覆盖4个已批准Advisory路径，源树digest `d3e041b36b9418528aa7a218a19199c77a27cf4d9835999044d92b8d75c0fa95`。Maven实际exit0／BUILD SUCCESS，**56类／920独立tests，0failure／error／skip**，旧10全部909身份保留，新增11；独立187与旧176重合，不能相加。JAR 80,158,868字节、SHA `79c53df08b7b43f98b24c402a043ee34755ba649add3c054d676e67e5fbd0d6b`。源前后SHA及三原件parse／lexical／index九文件字节保持；收据 `build/round11-build-verification.json` SHA `d852fbef8556319d9634d6677d120ef285a393e22a86772b24a68e82b0f17fd0`。

通用R7缺证改为既有other_fact，显式deadline_tracks仍保留specific singleton；仅envelope同项已在接受的limits／uncertainty真实渲染时省重复尾条，不扫描analysis文字、不删跨节判断或不可变stream前缀。独立peer 0Must，票 `vetting/round11-gap-rendering-independent-peer.json` SHA `72829cac8e2c4aa43a893947efee4ccb7d5bbbbd43e4499926f49dc4a9fce356`。实际26 raw／ledger的离线renderer回放只证呈现，不声称模型重答或整体业务通过。

answer26之后新SQL 1,263,365,056字节、SHA `304c2a56ba260702fdc60d9b4089abdedeb00ca5a1c3dd5c4fbd5f2e73f19e99`。仅精确停止自有57040，05:41:52新PID46604启动成功，H2 owner38980保持；升级前后**10/10完整API字节全等**，含answer26两消息750,675字节和Mini08原终票6,071,952字节。API收据 `runtime/round11-upgrade-api-after-receipt.json` SHA `aa14e8340ba2d09c5587272e81ff476fcbb141a857949be6c2ffb17d564998fc`；安装核票 `runtime/round11-installed-verification.json` SHA `6167d1e2687f1f5739288fefafbf4214b2c5ba9b69e56e4daabb903568349e49`。旧answer26未重生成。

Full10继续以正式10冻结树运行，已见TemplateReading版本验证一项5000失败；保原XML并只读定位，不改运行树或删失败。新SCC4.1缺源负向场景只完成离线准备，使用真实UI原句、原唯一Specification、空新会话，固定8HTTP／1ask／最多2counter与2generation，0historycounter／手动重试；期待答案只离线，尚未释放。Vetting修复前非空业务拒绝→修复后[]的中性提示可用既有warnings保存／展示／导出，正在独立候选树最小RED/GREEN；不发布未过门finding、不改旧Mini08快照。

## 05:47 增量（Full10失败且未跑完全套，下一轮隔离计划）

Full10于05:44:48结束，exit1／BUILD FAILURE，31分38.454秒。最终冻结**197份完整XML／2,192testcase节点＝2,182pass＋1failure＋9skip，0XMLerror**，Maven已记录聚合一致；不是全部用例完成。首失败为DraftingTemplateReadingIntegrationTest.verifiedCatalogueEditionDependsOnCurrentBytesAndFileKeyInsteadOfFilename：原要求API code0，实际5000，栈定位OerTemplateEvidence.digest对整串getBytes时Java heap space。随后LargeUtf8EncodingRegressionTest的@BeforeAll在715,827,883 UTF16输入char[]构造时OOM，Surefire容器终止，该组没有完整caseXML。原3759身份精确缺1614、正式10的909身份精确缺365，另有历史factory／容器映射需独立审计，任何缺失都不补算通过。

首失败原XML与定点栈已保存 `drafting/full10-first-failure/receipt.json` SHA `a30ab58d1b128156537d73add3b1dac74baa53fa8fa5e301e8e564f1560ce7f7`；全轮原票 `drafting/full10-summary.json` SHA `d41d58627cddd0329dbc51a0156f0479448c95193b1bc9641423b238918470c0`，全部源／fixture前后SHA无变化，独立终态审计进行中。运行时160 XML只是上一采样值，最终分母以197份完整XML为准。

只授权隔离候选树复用现有Utf8.sha256，先验证旧SHA字节语义与受限堆RED/GREEN；不改本轮冻结树、不加堆重写红票。下一全发现方案准备将每测试类放独立JVM、最多同时一个16GiB测试进程，避免大输入组与已累积Spring上下文共堆；保全部fixture／原大输入组，不改生产测试门。该计划尚未执行，新运行条件不能声称为单变量A/B。Advisory11唯一SCC4.1缺源窗口已由Root封票释放，计划SHA `6b6f5541ac8da575934336cdfe2c4491399ef8479c3aa98bbf6fdae9ef247d84`，8HTTP／1ask／最多2counter与2generation，无其他模型窗口。

## 05:58 增量（真实SCC4.1负向场景、布局修复与后继保全）

正式11唯一问句“SCC4.1 下的文件优先次序是什么？”保持UTF8 43字节／SHA `6ce948b8354082dd5c56fc49c69240c35350ad173f82a5798459d27a01956849`，只供同一21切片Specification，没有把离线gold送入模型。新session `23419efd-c505-466c-9362-5e8177c0bf90`／assistant28，实际8HTTP、1ask143.479s、2counter＋2generation（1内部repair）、0historycounter／手动重试／unknown。code0、grounded=false／insufficient、21sources／0citations，history0→2逐字段保存，原件全字节保持、错SHA409。窗口已明确释放，票 `advisory/round11-small-minimax-empty-scc41-negative-01/window-release.json` SHA `4853202cc61ddadd320061431172a70d459af3b9e736655d504e984fe4d0abef`。

两原raw都明确SCC4.1未提供，并以真实aliases另述Specification；未冒称SCC的负向子项通过。两份analysis却重复使用basisScope=insufficient，违反早已明写的分节契约；原schema三枚举虽通过，按kind的门拒绝，实际adopted谓词被短路、调用0，不能说已证明采用通过或失败。最终只发布通用limits／uncertainty，具体缺SCC说明及有限分析未交付，整体业务FAIL；原分析另有语义／引用边界，不能因首门明确而说其余全对。first-loss全字段及原compiled Harness回放票 `advisory/round11-scc41-first-loss/receipt.json` SHA `62827c9904b5e2a1065a4f351b0a622a6b4172aa295d13f628c55c660345f98c`；Root一次精确只读SELECT导出actual28 ledger22,584字节、SHA `e36faac7cb12ced07b1f06f175918b936943e65298e4fdf9741ade91cbdbe421`，与两原attempt sections逐字段相等。sourcewire 21原parts／DTO两次均保持，票SHA `e6cc1dcbf2b276c52107402a8113ba436818eb5c19d6513c397bae3561bf6b54`。后继只把非法分节basis诊断与真实采用拒绝区分，不改schema／scope／安全门／原答，不重采该题。

真实Chrome已加载28并目视验证通用待核／0citation、没有发布分析；0引用目标的reader记未执行。会话控制区误占.chat-shell的第二个minmax轨道，实测280px且按钮被拉高；仅main.css增加一个auto轨道，控制区约110.958px、聊天区520px保持。截图 `browser/advisory11-answer28-negative{,-layout-fixed}.png`、完整AX另存`negative-full-ax.txt`，原diff-only AX也保留而不冒称完整树。3个相关前端入口、typecheck和生产bundle通过，224源前后SHA保持，只较上一冻结改这一CSS；票 `build/web-round12-layout-verification.json` SHA `7598b0ae89d64b16356175bd13ba685d1e16f58e892970fc235ef204b87a6e8b`。未验mobile。

Vetting中性警告独立候选RED18／4预期failure→GREEN18→19类284／284全过、0skip，正式11选中264旧身份全保＋20新。Root独立读代码、全1487源和真实XML核票0Must，票SHA `d01d8f0bf6ab9bc168c5c71a342c3bb1aa7b6f2ef6a3e8f1b66d731ea11a605f`。只识别有SHA的业务形状拒绝非空array→实际decoded空array，以既有warnings保存／恢复／报告显示，旧Mini08快照与业务FAIL保持，未部署。Drafting digest在90M UTF16／384MiB独立JVM已取得原实现OOM RED与委托既有Utf8.sha256 GREEN，同6项均通过；真实原件与独立fork入口仍在验证，Windows含&路径导致的0tests启动失败逐票保留，不改ACL。

answer28后的新SQL `runtime/db-snapshot-round12.sql` 1,264,140,816字节／SHA `d632a3cb23cb3e64296696a73efae2847f15a55362233b994920bd1335995cd1`，新11API before含28历史738,403字节／SHA `e6a208e92eb1a7388375b807271af3913323dff94d43f7ca80876f78eaaf67e0`。本条时仍11PID46604，未有正式12或升级，所有应用／模型写冻结。Full10独立终态票SHA `20b7395f990a0cd1694edbd8dce654fc9af75510a5230d36db6bf5340a471d5c`；显式旧factory／marker映射后尚1609旧节点未结算，含1564旧pass＋9旧fail＋14旧error＋22旧skip，不推通过或回退。


## 06:29 增量（正式12安装与Full12默认全量释放）

正式12已安装：JAR 80,159,905 bytes／SHA `0158104241bbcbbcd2f0412381946ba9c1d388469a952b8ee5da7475832cbf20`，60类／965独立用例，0failure／error／skip；正式11全部920身份保留＋45新增，不能与独立模块196／284／219相加。1,488源文件、树digest `9900b16daaabe6d7b702ae94a31118dcc22801fea28dadbb94571e98080f47bd`，九份完整原件 parse／lexical／index与旧基线逐字节相等。构建票 `build/round12-build-verification.json` SHA `7e01ffcc9ccaa967c9c06250fbeebed0042e63155512094f3bfc0bbd5c5b254a`；主源码九路径采纳票 `build/round12-main-adoption-receipt.json` SHA `aabfeee3ac2eadb4390a916cc3fff210140a14c8246c42a52455fa8bd596ddb2`，旧字节先备份。

06:18:31后端PID13736启动，H2 owner38980保持。SQL12原保存文件1,264,140,816 bytes／SHA `d632a3cb23cb3e64296696a73efae2847f15a55362233b994920bd1335995cd1`；升级前后11完整API响应逐字节相等，含Mini08与answer26／28。安装核票 `runtime/round12-installed-verification.json` SHA `10b457b1678f5194ba696f1b356b61412edd7828a3c108f5ed3bb58f59ae69e6`。仅证明指定安装／数据保全，不是全DB恢复或新业务通过。

Full12最终计划 `drafting/full12-executable-plan-v2.json` SHA `27c2072843a9bdccaca01715f39a189b347163a2be1f1a1528b689cb30fd7260`，runner SHA `358df1ac6256d5488ea14f820368e920268b34539710dc5f27af99bb2b3a9495`。Root在独立终审0Must（`vetting/full12-final-plan-independent-peer.json` SHA `0e7fd08856364995879531f3fbf5271449faa1c01d81a487840b010b79b9f68f`）后一次释放，见 `drafting/full12-root-release.json`。从正式12完整冻结源默认全发现，无selector／exclude／skip／failfast／retry／-T；Maven2GiB，每类独立16GiB JVM，forkCount1／reuseForks=false，90分钟上限，原715,827,883 UTF16输入组及6项H2恢复测试仍选择。62fixture pins、11原件copies、31属性保留；新prepared input仅改输出目录。子进程使用任务TEMP与精确JDK别名，进程在suspended时归入专有Windows Job后再恢复，超时／父先退／异常路径关闭该Job；构造identity异常及父先退的两自检实际退出handles通过。原PPID监控NOOP dump、启动路径／权限／OOM失败及初次自检错误均保留。60秒成员快照不能证明峰值并发；held-handle退出不冒充完整Job关闭后active0实测。此条是释放记录，实际PID／XML／终态随后补记，不能预记全量通过。

既有Drafting真实模型证据只读复算票 `advisory/round12-drafting-existing-real-evidence-index.json`，131,880 bytes／SHA `4ff2ea6d1e7175df677e539a0c1f89cd4917489ad2f9bfce629ee86d5725b163`：19保存JSON按原key序重建compact wire后19/19完整长度／SHA匹配原events，文件与wire哈希分列；15原DOCX bytes／SHA全匹配，原生表3个。SIM15实际24应用层非空返回attempt＝15primary＋8recall＋1repair，其中20completed＋4invalid_json，独立TCP／billing次数unknown。79字段＝44PASS＋35REVIEW＋0fieldFAIL；57provided＋1derived＋17not_applicable＋4unknown，35值仍待人工语义验收；目录版本不匹配的整体FAIL保留。Discovery只执行1/255，254未执行、2pending、4实际目标未绑定、0采用；Round04两字段1候选1空、0确认，模型自报confidence0.97不当人工正确率。0新增模型／SQL／API／产品修改，不重采追绿。

**Full12实际启动补记：** 06:28:19，Maven PID56764／creationFILETIME134369224992011757，独立Job在恢复前赋属。初始预检通过，263产品源／385测试源编译完成，正在进入默认测试发现；尚未取得首批XML属性或完整分母，不能称965或全量本轮已过。运行状态 `build/full12 fork/run-state.json`。

## 06:42 增量（新咨询题原始业务失败与网页编号RED）

Full12检查点105完整XML／1,212节点＝1,206pass＋6项desktop/x2t配置条件skip，0failure／error；整轮仍在途，未执行门不推通过。

Advisory12新Specification正向问题已完成8应用HTTP／1ask111.505s／1counter＋1generation，0repair／historycounter／manualretry／dispatchunknown。新会话4790f8f8-f55f-40f1-83d0-b6c8ad305d8f，assistant30；HTTP200/code0、历史0→2完整字段相等、错SHA409。21完整part与alias到DTO逐值保持；原分析4,158字在发布正文offset215起完整保留。业务仍FAIL：原答漏掉[16]/body38明确的场地界线外道路排水工程条件，并把[17]/body42排除库引用并入的原句引为[16]，四个明确引用对3正确＋1错。原system已有保留父条、条件、但书与例外的R6约束，不重复加同义prompt或重采追绿。最终首失票advisory/round12-specification-positive-terminal-audit/first-loss-receipt-v2.json，SHA1d48b2b3a1b169a6f95ec87e2911a8511cb540699007b044e796a65ef02043de；窗口已释放，旧28未改。

Mini08正式12类有界512MiB回放确认：原raw3结构PASS，原形状拒绝原因精确保持；copies两句与DOCX段4/6相等。精准pendingFacts→review已在两次实际system，repairUser与实际修复请求逐字节相等，[]首次来自provider第二答，无可证应用清空或缺同义提示原因。测试副本amend→review只过形状、不授语义或finding；无关checklist、局部缺失推全局不存在等仍拒绝。票vetting/mini08-field-diagnostic-audit/conclusion.json，SHA736e0ee81be4901f1d922c08a15289a5918183428c36583b2dbada9ebdb7362c，原业务FAIL及15证据保持。

网页真实RED截图browser/round12-ntt-list-numbering-red.png：NTT Type of Tender前三项显示1./1./1.，保存DOCX0794e61…的num51/abstract2/lvl0实为lowerLetter/(%1)。Mammoth已把中断列表转换为三个单元素ol；当前reading/bindings无同revision已核label。两个现有测试入口行为断言均RED；后继正在隐藏未核网页自动marker、保完整正文／锚点／literal编号／原DOCX与PDF并显式提示，不新建平行编号解析器。当前不是已恢复原编号或已核编号。
