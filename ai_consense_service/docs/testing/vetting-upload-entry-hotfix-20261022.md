# Vetting 上传入口用途选择热修

**最新部署：** 用户停止原Drafting任务后，公共APB／APU路由已随hotfix86部署到18086／PID24292／64GiB。下方“等待运行任务结束、尚未部署”按本轮较早语境读取；旧pending未迁移，完整35报告未新跑。[联合修复与运行验证](drafting-extraction-payload-hotfix-20261022.md)。

用户确认：Vetting用于审查已经拟好的文件，不应在主入口要求用户分配内部语料角色。“项目资料”的宽泛命名及整批用途下拉框，把Drafting式资料准备负担暴露给了审查用户。

本次仅修改前端：

- 移除上传主入口的“自动识别／招标文件／标准依据／项目资料／文件目录”用途下拉框及其批次状态。
- 上传复用现有API，sourceRole保持undefined，由既有OER身份路由自动判断；不把所有上传强制标为tender，不改变排除规则。
- 保留现有逐文件身份确认及纠错、来源SHA／路由守卫、上传顺序和未确认请求不重发行为。
- 后继同时移除“文件范围”批次控件；上传调用不传sourceRole或documentScope，由公共身份路由决定。后端无需部署或重启，未触发模型／OCR或上传测试文件。

同时只读核实`VettingService.sourceSet→ProjectDocumentScope.selected/currentCatalog`：当前审查来源只选择VETTING_PACKAGE／VETTING_SUPPLEMENT，不直接把STANDARD_TEMPLATE／PROJECT_INPUT加入本次包；上述范围未做后端改动。

验证：

- `vetting-upload-queue-controls`、`vetting-source-purpose-controls`、`vetting-readable-summary-controls`串行33项通过，0失败／跳过。新增断言确认主入口无角色选择，真实组件上传调用不携带强制sourceRole，同时保原逐文件确认回归。
- vue-tsc通过。
- ego-browser独立任务空间7打开5177 Vetting页面，DOM和截图确认主入口用途下拉已移除，上传／运行审查／导出及文件范围控件仍在；截图已亲看，空间已finish关闭，未接管用户个人页。
- 证据`.local/vetting-upload-purpose82/regression.log`、`typecheck.log`、`browser.log`、`upload-entry.png`。

以上是入口热修的软件及显示验证，不是新一轮真实文件上传、自动身份准确率或整包Vetting业务／报告验收。Drafting正在执行的任务未停止、重发或改配置；未知的四份本地untracked未动。

## 后继范围控件与真实接缝核查

- 第二控件移除后，同三组33项回归通过，0失败／跳过；vue-tsc重新执行exit0。
- ego-browser独立空间8实际打开5177，主入口DOM仅剩导出格式select；截图`upload-entry-scope.png`已目视确认，空间已finish。未操作用户正在手测的页。
- 2b目录实际35文件（23 DOCX、12 PDF）；实际SHA仅5份命中固定registry，其余走动态内容识别，这不是5/35准确率。
- 从当前18086只读GET真实项目`proj_mvi0kncwu0as`的files返回35条：17 PARSED、14 PARTIAL、2 EXCLUDED、2 IDENTITY_PENDING。APA／APC为drawing_outside_scope；APU／APB为source_identity_unresolved，尚未详细解析。证据`.local/vetting-upload-purpose82/2b-stored-files.json`。
- 此存量项目含历史人工确认，不能把31份已解析说成31份自动识别成功。该快照也不能继承重启前APB／APU解析终态结论。
- 代码链为VettingService.uploadPackage→固定SHA身份→DocumentParser.observe→OerSourceMetadata.captureObservation→准入后详细parse。PDF身份观察当前只看第一页（原生不足时调用身份OCR）；无法确认则保原件和观察、等待逐文件确认。不能据此宣称35件均可自动纳入。
- sourceSet复用公共ProjectDocumentScope且限定VETTING_PACKAGE／VETTING_SUPPLEMENT；不会直接把Drafting模板／项目输入作为待审包。此处验证了选择逻辑，不代表后续报告已验收。

接缝验收缺口：DOCX渲染布局生产链仍未接通；APB／APU文字清单自动身份未收口；逐文件准入历史须区分自动与人工；真实完整审查、可读报告及导出本轮没有执行。按用户先热修、准确率后置的顺序记录，不启动全量解析或中断现有Drafting任务。此前将接口及局部回归描述为整体seams通过的结论不成立。

## 2026-10-22 Vetting准入接缝后继热修

用户明确要求先对齐Vetting接缝并热修。改动只落公共`OerSourceRouting`，不新增Vetting分类器、解析器或技术栈：补齐正文开头声明的合同图纸清单及参考图纸／文件清单标题，识别为APB／APU、project_document、tender。标题必须来自实际观察；文件名只作候选。引用句、普通DRAWINGS标题及Sample answer下的同名标题不能获得此准入。

### 复现及实际结果

- 新回归旧逻辑RED：`LIST OF CONTRACT DRAWINGS`期望project_document，实际unknown（`route-red.log`）。
- 初版软件35项通过，但真实首页观察发现APU原件实际为`LIST OF REFERENCE DRAWINGS/`续行`DOCUMENTS`，初版仍unknown。失败保留`real-list-observations02.log`，随后补跨行标题；没有把软件绿票当真实原件通过。
- 最终真实探针复用生产`DocumentParser.observe→PaddleOcrClient.observeIdentity→OerSourceMetadata.captureObservation`，对原APB、APU各只观察首页；两份均content_supported、对应APB／APU、readable=true。原件SHA与既有审计保持；未详细解析61＋20页、未写在线来源或调用LLM。证据`real-list-observations.json`及`real-list-observations03.log`。首次探针因默认系统临时目录AccessDenied失败，已用与生产同类可写任务临时目录修正探针启动参数，不改系统目录权限。
- PDF身份观察→详细解析→元数据保存→待审源集软件接缝已覆盖；HTTP multipart不传role／scope→真实服务／隔离JPA保存→业务查询及listFiles已覆盖。最终组合`OerSourceRoutingTest,OerSourceMetadataTest,VettingUploadRoleTest,VettingSourcePurposeJpaHttpTest`同窗65项通过、0失败／跳过，`seam-green04.log`。
- 首轮HTTP组合30项中3条旧断言失败：旧断言要求PARSING／FAILED从selected覆盖分母消失，与现行ProjectDocumentScope保留最新不可用来源的规则冲突。只更新测试为保留该来源ID且parseUsable=false；IDENTITY_PENDING仍不得准入，生产覆盖／质量守卫未放宽。原失败`http-seam.log`保留。

### 部署与范围边界

后端切换尚未执行：Sean原Drafting任务`4be87bf6-2a68-452d-b18c-867a0c68b018`仍RUNNING。不能启动第二后端共用当前库，因为自动任务启动恢复会把现有任务标成中断；也不能为本热修停止原任务。运行中的18086仍是automation80，以上新路由为已测试候选，不能称在线生效。

旧APB／APU在线IDENTITY_PENDING记录尚未迁移或重新详细解析；新路由不会靠读GET静默改变旧状态，也不伪造人工确认。已有逐文件人工身份确认入口保持。DOCX视觉生产链、完整35件报告／导出质量及OER准确率调查不在本次小补丁内，继续保留为未完成，不能宣称全部Vetting seams已通过。
