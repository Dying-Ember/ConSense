# Drafting 人工全流程验证（2026-10-26）

状态：本轮前端人工路线到三份实际 Word 下载与目录验证已完成。F 分支经正式 batch108、实际“更新目录及预览”、第二次幂等点击、PDF预览、硬刷新重入、原生保存稿读回及独立目录/渲染核验后封存。本文保留 A–E 的原始失败、旧最终文件及53项旧页码不符；2项业务未知、2个可选override保持模板默认、4个不完整TOC标题书签UNKNOWN、heading-flow UNKNOWN及元数据缺口、原有兼容性红项分别保留。该结果不代表模型语义准确率、法律/来源完整性或原生Word分页认证。

历史浏览器环境阻断：`F-EGO-SPACE-LOST`（2026-10-26 04:27:13 +08:00）记录原Ego TaskSpace4返回 `task space not found: 4`，枚举仅有用户space1。用户随后明确允许切换IAB；`cua.getState` 和按URL `getTab` 各发生约300秒工具连接超时，回执转录值分别300.0103/300.0194秒，未独立计时。`js_reset`成功后选中专用tab2恢复，用户tab1保持不动。首次实际目录请求期间另有3.077秒的selector等待工具失败（请求timeout45000ms、产品仍RUNNING、未重复点击），随后同一请求成功。两次连接超时、短等待失败及环境恢复分别保留，不计为Drafting产品失败，也不覆盖原13项FAILED。

## 范围与输入

- 测试项目：`proj_mvo4rwx372wr`；浏览器入口：`http://127.0.0.1:5177/#/drafting`。
- 通过前端上传 18 份 DOCX：`Ref/simulated_2b_correspondence/01_upload_documents` 的 15 份模拟沟通文件，以及 `Ref/2026-09-04_Documents to Shortlisted Candidates (Contract Advisory)/2a_Standard documents` 的 NTT、SCT、SCC 原件。答案 JSON、TXT、核对清单未作为沟通资料上传。
- 采用固定 79 变量目录的纯人工填写路线；上传、定义核对、填写、保存、采用、生成、正文编辑及导出分别留证。该路线不用于宣称模型识别或语义准确率。
- 独立重载对比记录确认初始 63 个已提供值均 `mismatch=[]`、`adopted=true`，`dirty=[]`。后续 Bill、用途、专业及发放位置等补充操作另见原始日志，不能倒推为每次历史点击都成功。
- `foundationIncluded`、`contractorLegalForm` 保留未知；不得以生成成功代替来源确认。模拟通信及 SIM14 测试决策均保留测试材料身份。

## 分支与原始失败

|分支|起点与用途|保留的失败及处置|
|---|---|---|
|A|首次上传及人工填写|上传截图超时后核对实际效果，未重复上传；身份确认弹窗遮挡操作属于正常业务流程。|
|B|`FORK-B` 从 `012` 之后继续，确认身份后恢复人工填写|`B-RELOAD` 是重载回第 1 步后脚本误等第 3 步；由实际按钮重新进入。`B-GENERATE` 是真实后端生成失败，见下文静态页脚冲突。|
|C|`FORK-C` 从 `B-GENERATE` 继续，保留值和未知项|重开人工页时脚本仅等可见、未等启用，保留该失败后等候就绪。机械修复后继续生成及补充 Bill 信息。|
|D|阅读失败期间使用独立保存正文编辑入口|保存 SCC 24 处及 SCT 3 处人工修订。SCT 原文前导空白在编辑器中被隐藏，首次严格文字比对失败保留；随后记录实际原生块和可见原文再操作。|
|E|batch107 切换后从阅读失败处继续|`E-RETRY-READING` 截图 CDP 超时保留；独立只读记录显示 SCT 阅读正文长度 32233、`readingErrors=[]`。SCC11.305 编校为 `Not used.` 后保存成功；刷新后首次点击尚 disabled 的第 4 步失败，等控件启用后重新进入并实际下载三份 Word。目录及渲染核验另记。|
|F|batch108 及显式“更新目录及预览”控制|实际首次更新产生新SCC修订，第二次updated=false且同文稿；PDF预览59页、硬刷新重入阅读及三Word实际下载通过，目录92/92独立核验。新输出位于 `deliverables/final-verified/`，旧 `deliverables/final/` 保留；最后动作 `F-FINAL-VERIFICATION` 附独立证据。|

`B-SAVE-2` 原始日志误标 `branch=A`；报告按执行者确认归 B，仍保留原始 A 和更正说明。任何后续恢复均不覆盖原始 `FAILED`。日志 `effect` 是执行者的现象声明，独立核验另外列示。

## 实际操作阻断及修复

### 1. SCT 结构分支删除与静态页脚迁移冲突

正式 baseline104 在 `2026-10-26 02:28:39.638 +08:00` 生成失败：`STORY_STATIC_BUSINESS_OVERLAP: sct-issue-alternative-remove-scope`。L10Pro 所删除的 SCT 替代段落范围 P229–387 包含静态正文页脚 P253/254、P302/303、P368/369；同一页脚迁移计划和结构删除被按业务正文重叠拒绝。

修复只让 `DraftSourceEditCompiler` 接收同一 `NativeLayoutPlan`：在原件 SHA、原生段落 ID、哈希、文字及同节页脚载体全部匹配时，结构分支删除跳过已计划迁移的确切静态页脚节点。`DraftFormattedRules` 将同一计划交给 compiler 与 editor；原有编辑器重叠及祖先防护未放宽，旧双参数构造器保持兼容。

正式 footer105 的候选 SHA-256：`7e7baa514e31b245c0f1226018bbbefa7da9ddde46f1cc0f91c6d17106064b85`。切换前后选定 21 张业务表、2947 行的快照 TSV 逐字节相同，文件 SHA-256 为 `785883b71b1ea49af6d9e393462a92bb0d654ec233853770cfb1683d5b9ffaea`。这只支持选定窗口和快照字段的保全结论。

### 2. 运行包缺少原生 documentSource 路由，阅读失败又挡住独立正文编辑

同一 SCC 修订的 template/source、template/bindings 和 document/bindings 均返回 200，`documents/SCC/source?revisionId=...` 返回 404。旧 baseline104/105 运行类缺少 `documentSource`，已提交 HEAD 则有正确 Controller/Service 方法。

前端 `DraftingDocumentReview.vue` 保留阅读错误和重试提示，同时显示单独绑定已保存修订的正文编辑 slot。运行修复从已提交 HEAD 提取正确方法；未把工作区中无关的脏 `DraftingService` 整体编译进包，也未用翻译导出接口替代原生来源。

source106 方法级覆盖只增加 Service 五个方法及 Controller 一个路由；原 Service 185 个、Controller 37 个既有 `method_info` 字节不变，既有字段、常量池前缀和类元数据保留，`BootstrapMethods` 仅追加。`-Xverify:all` 的 7 项反射检查通过。source106 候选未单独部署，而是并入 batch107。

### 3. 已保存正文标题更新后，现有目录标题缓存仍为旧文字

SCC 正文已人工修订，受保护目录的标题缓存未跟随。修复在分页前，以同文档内完整且唯一的原生标题书签、相同编号及受支持的目录结构为条件，仅同步既有标题文字缓存；保留域、书签、页码缓存、段落及文字格式，不推断标题语义。

真实离线输入 `SCC-before-toc.docx` SHA-256 为 `f2226638845944e57663a12029ef67cf07b09ad55f0debd9c237232cbe528ad2`。92 个目录目标中，2 个标题缓存修改、86 个核验不变、4 个书签范围不完整而原样保留，整体 `titleCacheStatus=UNKNOWN`，不能写成 92/92 验收通过。

- 修改：`_Toc183592395`（SCC7.304 waterproofing 标题）、`_Toc183592419`（SCC11.305 → `Not used`）。
- 保留 UNKNOWN：`_Toc183592382`、`_Toc183592393`、`_Toc183592403`、`_Toc183592446`，原因均为 `TOC_TITLE_HEADING_RANGE_INCOMPLETE`。
- 离线核对确认其他包部件逐字节相同、24 处正文修订保留、92 个 PAGEREF 页码缓存保留。该结论只适用于此输入/输出对；后续 `Not used.` 编校另做正式保存及导出核对。

### 4. 目录数字缓存仍沿用原件页码，页脚混合数字导致最终化保留 UNKNOWN

E 分支旧最终 SCC 为59物理页、正文末页56；独立按完整“编号＋标题”定位92个目录目标，39项页码正确、53项仍旧、0项未定位，旧目录末端可到76。`layout/SCC/toc-page-verification.*` 保留该失败，旧 `deliverables/final/SCC.docx` 不覆盖。

从正式当前修订 `e3609dea-d3ed-47be-aae9-050ec491e95c` 只读提取的实际已保存 DOCX SHA-256 为 `8e8aeefa41b13b012c899d8c9401b657fa2aad362617c0162fbf809e723c38b7`，与E分支旧最终下载及离线输入逐字节相同；该修订引用的原模板 `sourceSha256` 另为 `9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208`，不能混作已保存稿hash。完整 bindings1097、当前ledger5。真实 renderer 的首遍结果为 `TOC_DISPLAYED_FOOTER_MISSING_OR_AMBIGUOUS: 4`：旧算法假设页底区域只有一个数字，但实际页脚同时有 PAGE 域1及合同编号20250101；第59物理页也同时有56和20250101。

修复从同一 DOCX 的目标 section、显式 default footer 和唯一完整 PAGE 域出发；页脚原生 literal 逐项消费实际渲染文字后，只允许一个剩余正整数作为打印页码。未使用数字长度、最大值或特定合同编号猜测；重复、缺失、局部匹配、额外literal及不支持的变体继续 UNKNOWN。

### 5. 已清空原生段落仍带书签，移除元数据触发标题流防护

仅修复页脚后仍保留 `HEADING_FLOW_REMOVAL_STILL_PRESENT: CS3129cc1e8e99fa85c90eca23cb7d`。该段原文为 `For the purpose of Clause SCC11.301 of the Special Conditions of Contract:-`；授权 `CLEAR_PARAGRAPH_AND_NUMBERING` 后原生段落及完整空书签仍存在，bindings却标记removed。当前284条类似removed binding保留空marker，其中265条有初始CLEAR记录、19条为后续人工清空；现有 `bodyLedger/withSourceRemovals` 只保留REMOVE_NODES而丢失CLEAR历史。这个元数据/沿袭缺口本轮仍未修复。

为避免把独立目录数字核验与标题修复混在一起，采用很窄的TOC-only处理：只捕获上述仍存在错误，并在已注册原件及endpoint、完整244分母/profile、唯一保存binding、原件/段落文字hash和确切结果段落全部匹配时，验证原生paragraph真正为空且书签唯一完整，无编号、样式、field、math或drawing等。所有其他无效binding及未核验原件保持原先首遍失败。该路径不修复段落、不宣称heading PASS，保留原始reason、heading UNKNOWN及repair0/mutation0。

冻结实际输入经两遍真实 renderer 得到TOC PASS：92目标＝53数字缓存更新＋39不变；2114原生测量点两遍相同，最终观测页码与最终目录数字相同。独立包级差异核验仅 `word/document.xml` 内53个PAGEREF数字文字缓存改变，其他原生文字、属性、拓扑及其他ZIP部件逐字节不变。独立完整编号/标题/PDF复核为92/92正确、0不符/未定位。该结果属于冻结正式输入的离线 renderer，输出 DOCX SHA-256 `506baa7624333866c1200eaef47b158565cfc12fb4f163086ee799d5b5b59c51`、PDF SHA-256 `c93f2c0e7cf42d970084b05348ed9e36a15d4ae75440e87ffac48e6b0f5bab9b`；不冒充正式按钮或浏览器导出。

### 6. 用显式已保存修订动作更新目录与预览

新增“更新目录及预览”和 `POST /api/drafting/{projectId}/documents/{fileKey}/refresh-toc`，请求只接受 `{revisionId,docxSha256}`，响应为 `{document,tocFinalization,updated}`。只处理SCC当前已保存修订，检查原件、完整bindings/ledger和输入快照；READ_COMMITTED事务中先加锁，render后clear持久化上下文，再重新加锁及复核来源/值/修订/hash。TOC PASS且不带标题修复/变更的结果才追加纯目录缓存子修订，父修订及完整旧ledger保留；相同字节或UNKNOWN保持原修订且零写入。该动作不调用模型、不修改变量或正文，也不通过虚构正文编辑触发目录更新。

前端在dirty、busy、自动任务、过期或缺少修订/hash时禁用，精确捕获已保存身份，响应后再次核对项目、head和dirty状态；UNKNOWN/失败保留当前文稿和本地编辑。PASS更新阅读器和两种导出绑定，heading UNKNOWN仍可见。

## 回归、运行与验收分别记录

|证据层|本次结果|结论边界|
|---|---|---|
|页脚/结构删除定向回归|27 tests，0 failure/error/skip；含 63 已提供值及派生 false 的 NTT/SCT/SCC 原件编译|本地编译与代码回归，不等于浏览器或业务验收。|
|TOC 定向回归|38 tests，0 failure/error/skip|与前一组重叠 18 个测试，禁止相加为 65。|
|batch108 TOC冻结回归|50 tests＝旧38＋新7＋授权5，0 failure/error/skip|冻结源见 `toc-page-regression/receipt.json`，不与重叠套件重复累计。|
|显式目录更新Service/Controller|7 tests，0 failure/error/skip；候选实际方法字节测试|Repository/EntityManager doubles及自建finalizer/PDF pair，尚非真实SQL并发或浏览器验收。|
|显式目录更新前端|10 mounted/API tests绿；reader19/workspace6/locations6/arrival4、typecheck及production build绿；独立HEAD精确发布树typecheck/build/reader19/toc10也绿|独立导出HEAD加本轮5项前端发布候选，296源码hash精确，依赖为原node_modules显式junction；HTTP/重型Workspace为声明测试边界，尚非正式浏览器验收。|
|真实已保存SCC离线renderer|2遍、92＝53改＋39不变、2114点稳定；独立完整标题/PDF定位92/92|heading仍UNKNOWN/244注册/repair0，元数据缺口保持；正式F分支另记。|
|前端阅读/编辑回归|reader 19、workspace 6、locations 6；typecheck 通过|分组独立列示；不把检查数量换算为业务场景数量。|
|保留的原有红项|`DraftNativeStaticInlineCompatibilityTest`：`pPr at 0 expected true but false`|在固定 baseline104 独立复现，未作为本轮修复回归通过项。|
|TOC 历史尝试|toc-01 未执行；toc-02 错误预期 UNKNOWN 数；toc-03 fixture 触发既有缓存宽度保护；toc-04 修正 fixture 后通过|所有历史日志保留，不重写为绿。|
|batch107 正式运行切换|47780 → 27772；新进程 creationFiletime `134374298946404662`|部署记录的 ready 阶段只表明只读 API 就绪；后续浏览器及输出验证另记。|
|batch107 切换保全|前后各 21 张选定业务表、2957 行；TSV 完全一致，SHA-256 `b187a8bc5b6ff68f8ab5774be29d7090160ce4f89be01445c3942ee96ee79392`|不覆盖未纳入快照的图正文、来源/解析/canonical 正文等字段。|
|浏览器阅读恢复|SCT 完整阅读正文长度 32233、无阅读错误|支持该时点的阅读恢复；最终编辑/保存/导出仍按后续证据核对。|
|最终 Word 下载|E 分支经前端实际下载 NTT/SCT/SCC；清单及本地 SHA 见下表|支持实际下载及文件身份；页数、逐项内容与版式另行核对。|
|旧最终渲染与内容|NTT12/SCT23/SCC59，共94物理页已复核；旧SCC数字目录53项不符|旧文件保留，不以新离线结果改写历史；全分页/Word/招标或法律验收不在该结果内。|
|F正式按钮及幂等|首次updated=true，新修订96a9235a；第二次updated=false、92项0改/92不变、文稿DTO完全相同|实际IAB操作/正式API证据，不从updated=false单独推导隐藏数据库零写入。|
|F硬刷新/阅读/原生读回|SCC阅读长度167621，无loading/readingErrors；三稿native GET均200且metadata/实际下载/GET字节同|API可见12业务字段不变，变量与任务表的保全另看21表快照；不替代来源确认。|
|F最终目录及视觉证据|SCC59物理页、目录92/92正确、0不符/未定位；总55481原生节点仅53个w:t数字缓存不同|SCC目录1–3新作原尺寸复核，4–59共56页精确pixel相同复用；NTT12/SCT23同字节复用，94物理页有视觉证据覆盖，并非本次全部重开。|

batch107 候选 `consense-drafting-source-toc107.jar` SHA-256 为 `ebaa8ddce511adb0a85cc43bc3290959ae559b74ac5c31b426bbff8d2f0d8faa`；保留 source106 已核对的两类方法级覆盖，仅增加冻结的 Editor/Finalizer class family。隔离编译 target 8/class major 52，未编译其他脏源码。

第一次 batch107 preflight 在停止旧进程前因 PowerShell 将 ISO 时间自动转换为 `DateTime`、随后文化相关字符串导致 `DateTimeOffset.Parse` 失败；修正类型处理后重新执行。`runtime-batch107/first-preflight-failure.json` 为执行者后补转录，未取得原始独立失败日志。该脚本失败与产品生成/阅读缺陷分开，不作为一次产品失败，也不冒充一次已完成切换。

batch108 候选 `consense-drafting-toc108.jar` SHA-256 为 `a3510ab3658cd65b74573e4199cc7c930f91f22ab10aaa992d708c2504a9774e`。固定107基包只覆盖两TOC类族和各追加一个refreshToc的Service/Controller，原190/38个方法、字段、CP前缀和class元数据字节完全保留。两TOC类族等于真实renderer所用class；route等于7项契约测试所用class。实际候选 `-Xverify:all` reflection通过，未初始化类或调用业务。正式切换由独立deployment回执确认：27772→16212，新creationFiletime `134374426219933485`；切换前后21张选定表均2959行，TSV逐字节相同，SHA-256 `03756979eda60b03871e881828d508409620d5e76098480b2b5a14a43fec1838`。

## E 分支旧最终 Word 下载身份

`deliverables/final/download-manifest.json` 记录了通过前端“导出 Word”取得的实际下载。SCC 最后编校由 `E-BODY-SAVE-SCC` 保存，刷新后 `E-SAVE-PERSISTENCE-REENTER` 重新进入已保存文稿；此前点击 disabled 控件的 `E-SAVE-PERSISTENCE-RELOAD` 失败仍保留。

|文稿|字节|SHA-256|上海时间|
|---|---:|---|---|
|NTT.docx|65521|`beffcd65c1e8d955f8442659073a946f1901a858c6f63971f92f3e0d7028349b`|2026-10-26 03:29:25.769|
|SCT.docx|127661|`485d5736600ffb253842902bfd00e56c2e9e64b9fd13babb9d291033fb1c40d7`|2026-10-26 03:29:29.867|
|SCC.docx|258767|`8e8aeefa41b13b012c899d8c9401b657fa2aad362617c0162fbf809e723c38b7`|2026-10-26 03:29:34.595|

上述是实际旧下载身份。独立核对随后确认旧SCC目录页码39正确/53不符/0未定位，保留其失败；批量修订和来源范围按证据分别核验。旧内容receipt严格期望 `Not used`，实际为E分支人工补句点的 `Not used.`，导致同一标题及目录的两个严格比对差异；原红项保留，另有标点等价审查，不修改旧期望来抹除。SCC物理41页的SCC16.301标题与后页正文分离仍属未验收项；稀疏空白、原件#six/#twelve和旧模板引用保留专业/来源复核边界。

## F 分支正式新修订、实际下载及独立核验

首次实际更新产生SCC修订 `96a9235a-eb7e-4cee-8c9d-ed2fccfa9a78`，本次输入的已保存稿为 `e3609dea-d3ed-47be-aae9-050ec491e95c`。实际响应TOC PASS：92目标＝53数字缓存更新＋39不变，2114原生点两遍稳定；heading仍UNKNOWN/244注册/repair0/mutation0，原reason保留。正式DOCX与离线真实renderer结果同SHA；正式采用PDF hash `408a2fb7cf20f571015d33084f6de7d6729cfb65f1b2cfbd33c6f30fb95e6b08`与离线PDF hash分别记录。

第二次真实点击返回updated=false、同revision/hash、整个document DTO相同，TOC92目标0改/92不变。正式newrevision PDF请求返回200 `application/pdf`，前端显示59页；随后实际 `tab.reload`、等待第4步启用并重新进入SCC，阅读正文167621字符、无loading或readingErrors，仍显示已保存文稿。

|新Word|字节|SHA-256|实际IAB下载上海时间|
|---|---:|---|---|
|NTT.docx|65521|`beffcd65c1e8d955f8442659073a946f1901a858c6f63971f92f3e0d7028349b`|2026-10-26 07:04:51.743|
|SCT.docx|127661|`485d5736600ffb253842902bfd00e56c2e9e64b9fd13babb9d291033fb1c40d7`|2026-10-26 07:04:57.321|
|SCC.docx|258722|`506baa7624333866c1200eaef47b158565cfc12fb4f163086ee799d5b5b59c51`|2026-10-26 07:05:02.413|

下载位于 `deliverables/final-verified/`，三份浏览器Downloads原文件与证据副本逐字节相同。按after-reload对象的确切revisionId对三稿执行native source GET，均200且stale=false，metadata hash、实际下载及原生保存稿字节三者完全一致。NTT/SCT与旧final同字节；新SCC只改53个PAGEREF数字缓存。

独立currentGET比较确认NTT/SCT整份DTO不变；SCC仅revisionId/content/docxSha256/pdfSha256/blocks/nativeLayout改变。其余12字段完全相同：fileKey、title、generated、snapshotId、ruleVersion、stale、contentEdited、sourceSha256、fieldStatus、fieldImpacts、unresolved、renderProfileHash。1927blocks中1874个完全相同，53个只变行末页码及正确textHash；nativeLayout只改两处artifact hash镜像。API未暴露的字段不由该DTO比较推导。

正式操作后的21表COMPLETE快照为2961行，TSV SHA-256 `8471be8eaace2f61ee6d8417c5c616f62f74088c89f299ca22606256343abf85`。相对切换前2959行，仅draft_document摘要更新（12行不变）、draft_artifact31→32、draft_pdf_artifact24→25；净增两行分别为一个DOCX artifact及一个PDF artifact。其余18张选定表摘要完全相同，包括draft_variable、graph输入/自动/提取表、qwen_generation_action、Vetting及Advice记录；全部活动guard保持0。快照PLAN排除的source/parse/canonical与graph大正文不在保全声明内。读库采集为已审SELECT-only并rollback，H2虽请求readOnly但连接报告false，不能宣称服务器强制只读。

`frontend-final-verified-review/final-qa-receipt.json` 独立核验新SCC59物理页、正文打印1–56、完整编号＋标题定位92/92正确、0不符/未定位。原生XML共55481节点，仅53个w:t数字缓存不同，其余文字、属性、拓扑及包部件保持。视觉证据为新看目录1–3页，正文4–59共56页精确pixel相同复用；NTT12/SCT23页按同字节既有原尺寸证据复用，三稿共94物理页有视觉证据覆盖。本次没有重开全部94页。

按 `oracle/manual-values-reviewed.json`，业务未知只有2项：`foundationIncluded` 为 `unresolved_current_independent_meaning`，`contractorLegalForm` 为 `intentionally_unknown`；`advanceRepaymentMonths`、`advanceRepaymentFirstCertificate` 为2个 `optional_unchanged`，保持six/seventh模板默认，不归为UNKNOWN。历史QA中“four root-recorded UNKNOWN”声明照留，此处按oracle明确纠正分类。另有4个TOC标题书签不完整UNKNOWN，以及heading-flow UNKNOWN与CLEAR/removed元数据/沿袭缺口，均未因数字目录PASS变成已解决。外部Specification及采用边界、三个NTT旧SCC引用和完整SIM14 NTT13保留，未强行插入18个Specification区域或3个设计scope。原 `DraftNativeStaticInlineCompatibilityTest` 红项及所有历史FAILED继续保留。

## 证据入口与交付边界

原件、运行包、进程身份、数据库快照、截图和完整逐次操作留在忽略目录 `.local/drafting-frontend-e2e-20261026/`，不进入 Git：

- `operations.jsonl`：追加式原始操作；`build_operation_report.py` 从单一快照生成详细 `report.md` 与 UTF-8 BOM `operations.csv`，保留原始 JSON 和分支更正。
- `oracle/`、`correspondence-manifest.json`、`browser/manual-reload-verification.json`：来源、人工处置与独立重载比对。
- `fix-regression/receipt.json`、`fix-regression/preview404-receipt.json`：页脚与阅读/编辑回归，以及原有红项。
- `runtime/`：footer105 构建、ABI 编译、切换及保全；`runtime-route106/`：已提交方法提取、字节保全及反射检查。
- `toc-title-regression/receipt.json`：92 个目标的 2/86/4 明细、输出约束及历史运行。
- `runtime-batch107/`：冻结候选、构建核验、正式切换、前后业务快照及阅读恢复。
- `toc-page-investigation/`、`toc-page-regression/`：正式当前artifact只读提取、首遍UNKNOWN、页脚定位、冻结50项及真实renderer两遍/独立cache-only/PDF目录核验。
- `toc-refresh-regression/`、`toc-refresh-ui-regression/`：新增显式已保存修订动作的源/方法graft、7项服务测试、10项前端测试及HEAD精确patch发布候选。Service/Controller/API完整dirty文件不直接stage。
- `runtime-batch108/`：固定107＋两TOC类族＋两个追加方法的候选、只读包验证及root控制的正式切换/保全。
- `runtime-batch108/current-get-business-preservation.json`、`formal-idempotent-and-exports-verification.json`、`native-final-readback.json`、`business-ui-preservation-comparison.json`：正式更新后的独立DTO、幂等/下载/网络、原生稿及21表差异核验。
- `frontend-final-verified-review/final-qa-receipt.json`、`layout/SCC-v2/`：实际新下载的92项目录、原生缓存差异、原尺寸3页新看及56页pixel复用证据。
- `owned-files.json`（历史9项）、`owned-files108.json`（历史11项）、`owned-files-final108.json`（最终17项：9产品/8测试）：保留原清单，最终清单区分发布候选与工作文件原始hash；本文为额外测试文档。

详细report.md及39列UTF-8 BOM operations.csv以最后 `F-FINAL-VERIFICATION` 为止的同一281动作日志快照封存，13项原始FAILED、环境阻断、两次约300秒连接超时、3.077秒工具等待失败及恢复均保留；最后动作附independentVerification PASS及独立证据，不由effect推导通过。模型、提示词、语义识别准确率及无关OER/Vetting/Advisory工作不在本轮修复与验收范围。
