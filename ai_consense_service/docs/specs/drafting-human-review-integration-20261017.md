## 2026-10-23 沉浸核对与多处关联

原件核对区提供显式沉浸式入口；原版页面与问题编辑栏一起占满可用窗口，桌面左右约2:1。页面和编辑栏各自滚动，不再叠加外层工作区滚动。退出按钮与Esc均可退出，同一预览实例保留当前页、选词及未保存问题调整；关闭、切换项目／离开步骤、组件卸载恢复背景滚动。嵌套对话框先处理Esc，加载失败仍能退出。

同一变量可关联多段原文和多处修改范围。版面文字出现多次本身不是错误：在当前绑定原件中显示同页全部匹配，跨页列出匹配页供切换；右侧各条已关联原文可分别定位。文字匹配仅辅助对照，不自动确认来源归属、创建编辑范围或采用答案。原件／分析身份无法核实时继续拒绝猜测绑定；文字层未找到与文字层无法检索须分别提示，不混成“多处所以失败”。

自动定位失败只在原件阅读区给出非阻断提示，明确仍可浏览、翻页和选词，不作为整块红色业务错误显示；来源版本不可用、读取／保存失败等实际错误仍独立处理。空白段落或无文字位置明确标注，不提供点击后必然失败的定位操作。阅读辅助定位不生成编辑授权，旧分析／多重原生身份继续拒绝猜测。

同一guidance note可拆出不同事实及其组合条件；同一事实应复用变量并关联多处来源／修改范围，不因处理多个片段而重复建输入。完全相同引文、答案类型与角色却没有独立事实含义的节点须作为重复候选核查；不能仅凭同文就合并不同事实、不同条件或不同输出类型。现存电子发行／BQ示例的重复与联动缺陷仍在验收报告中待修，预览修复不代表该语义问题完成。

## 2026-10-23 用户要求：恢复原版页面核对交互

以2026-10-16“在原件上核对”原型的原版页面交互替换重排文字主视图：保留原件排版、旁注和物理分页，用户在页面框选文字、逐词增减选择，就地补充／关联问题、指定影响范围；右侧一次核对一个问题，复用已有条件、拆分合并、多处范围、草稿保存及后续明确采用流程。“另开只读PDF窗口＋重排文字选词”不能作为本要求已经完成的证据。

切换文件只改变查看对象，设为本次起草版次是独立明确操作；项目、来源SHA、分析身份及图谱修订守卫保持。页面坐标不能直接当作Word偏移：仅在当前原件及完整选词／上下文能唯一准确对应原生文字时，构造包含准确引文、UTF-16偏移、段落身份及SHA的native选区，服务端保存时仍独立核验。重复／无法唯一对应的位置不得猜测、借用另一版Word或扩大范围。PDF来源可供阅读和来源关联，不获得另一版Word的编辑权限。

当前文件问题采用搜索和分页，选择问题后在原版页面定位及高亮来源，通过显式操作进入变量和影响范围调整。历史任务记录保留，要求／关系建议／需补核使用有界标签页、搜索及分页；不再把整屏原子记录和反复折叠作为主操作。历史任务数量不冒充当前草稿数量，未确认草稿不能声称已确认。

当前实现和真实浏览器证据见[结果验收报告](../testing/drafting-result-usability-20261023.md)。本交互改造不证明全部模板语义正确或导出文件逐页通过。

## 2026-10-23 用户确认：允许有明确标记的部分草稿交付

允许在部分修改尚未核对完成时预览和导出未完成草稿。只应用用户明确核对并采用、来源及编辑校验通过的修改；未决部分保留原文，不自动采用模型答案、不绕过来源版本或准确编辑范围校验。依赖未决条件或冲突的修改同样保留未处理，不能仅因局部答案已采用就执行。

部分交付的预览及导出文件须带醒目的“未完成草稿”标记，并附可读的未处理清单，列明问题、涉及文件／原文位置、未处理原因和下一步操作。标记必须进入实际导出文件，不能仅显示在网页或文件名。全部需要核对的内容确认完成、必要答案明确采用且修改方案无未决时，自动省略未完成标记及未处理清单；不得仅按识别任务完成、位置全部绑定或用户点击导出来消除标记。

此项是用户最新确认的交付规则，替代此前“整图未确认时一律不能输出”的产品限制。实施需复用现有图谱、事实采用、计划、原件编辑和LibreOffice导出能力；原整图确认流程及历史证据保留，部分交付不能将未完成图谱伪装为已确认版本。当前规则已确认，生产接线和文件验收尚待完成。

第三步同一方案内的修改影响按来源文件归组，文件名只显示一次；纯填空符号汇总目标数，正常原文引文逐条保留。坐标绑定数量与修改待核对数量分开统计，展示归组不改变目标、候选序号、原文或采用权限，不增加折叠和后台请求。待核对数量须包括既有图谱投影中整条方案的规则重绑、未决、缺失引用、版本不兼容及仅参考状态，不能因各目标坐标bound而显示无待核对；计数为0也不授予确认、采用或执行权限。

修改影响展示须区分来源位置绑定与处理方式核对：原生范围bound但动作仍check时，明确位置已绑定、修改仍待核对且不执行，不将坐标核验显示成业务规则已通过。

第三步原文预览须复用第二步保存的原生选区：方案携带nativeBinding时，仅在绑定状态、原件SHA、原生段落身份、editableText通道、偏移及准确引文全部一致后导航；多段绑定须完整核验，重复身份拒绝。失效绑定不得回退至相似文本或旧目录猜测。无该绑定的既有目录路径保持原边界。原文选词可能只是一个词或填空，展示称“原文位置”，不把它宣称为已确认的正式条款编号；导航不授予编辑、确认或采用权限。

人工把已配置修改切换为“需要核查”时，应保存原拟议动作以及答案配置和替换文字落点，继续等待核对。重新选择同一拟议动作不能提前删除其范围核对依据；明确切换成其它处理则按该动作清理不适用配置。动作切换与保存均不自动确认图谱或采用答案。

来源未完成而暂存为check的目标，如proposedAction明确为fill／replace／insert，保存问题或条件调整时须保留拟议答案引用、拼接配置及固定改文，不因当前待核对状态删除方案。保留配置不授编辑权限，原check、needsRebind及原件绑定守卫保持。

待确认图谱的第三步候选列表应直接展示同版图谱记录的关联修改影响，复用既有图谱关系投影，不新增模型请求或来源读取。展示问题自身及直接引用此答案的方案、原文目标与待核状态；没有直接方案须明确说明，不把候选值作为采用值试填。旧版或来源失效时不展示影响，退出节点的方案不进入当前影响。没有适用条件的记录不得描述为已确认适用；纯占位符目标以可读说明呈现，并保留原文核对入口。

修改范围未绑定时，应向用户说明缺少准确原文选区及如何继续核对，不直接显示内部错误码。重复填入同一答案须分别建立准确目标；现有多选区替换仍只在指定选区写入，其余选区清空。来源已识别不等于答案格式、条件或编辑范围已获确认。

<!-- drafting-result-usability-20261023:start -->
候选列表须支持按问题名称、文件名和原文筛选并显示可见／全部数量；筛选只改变展示，保留原候选序号、证据及定义绑定。未保存校正时禁止改变筛选，切换项目／修订清空筛选，避免隐藏恢复的草稿；清空搜索恢复全部结果，不额外请求识别或模型。

已存指导说明的成员范围允许经同版原件与观察核实的空文本0–0区间，保留空段分隔及完整引文；非空文本零长区间、越界、错版或改动范围继续拒绝。此规则仅授来源观察核对能力，不生成原生编辑坐标，不改变范围完整性或采用权限。

保存核对草稿时，顶部与底部入口须显示实际保存中状态，服务器成功返回前不得提示已保存；失败保留调整并恢复入口。切换项目或重新读取后，旧保存请求的成功或失败均不得覆盖当前项目状态。保存仍不等于模板确认或答案采用。

共用来源上下文展示使用可读的触发与停止说明，不向业务用户展示内部字段key、字符偏移或重复机器代码；原文直接展示，不再增加一层折叠。记录窗口与实际派发上下文的区别保持，未知原因明确缺少说明；完整定位和诊断仍保留在原记录中，不因显示简化修改证据。

有待确认图谱的第三步同样提供已有识别报告入口，复用历史只读报告，不要求先确认模板或回到旧目录。按字段分批的primary调用使用“主识别调用”标签，调用摘要不重复显示内部kind／status代码；未知调用仍标记为记录的调用类型，不猜测性质，原始提示及返回保留。

原件版面读取期间同一来源不重复发起转换请求；等待与失败状态分开，失败后才提供重试。切换来源或项目后旧响应不得覆盖当前版面，渲染失败不影响准确原文核对。

第三步已归属候选须能直接查看对应模板问题及修改范围，复用第二步问题选择与原文定位。导航携带候选所属图谱修订；当前编辑版不一致、问题已退出或不存在时拒绝跳转并说明需重新核对。未归属候选先明确归属，不猜测目标；未保存候选或问题表单继续受现有守卫保护。导航不保存、不确认、不采用，也不将展示范围自动转为编辑选区。

“定位关联原文”不能仅依赖可编辑native span：已绑定来源观察可在同原件／分析代次下，以观察成员唯一对应的原生part／nodePath定位段落。此路径与范围展示复用身份解析，不按相似引文搜索，不把观察偏移转成编辑偏移，不创建选区；旧版本或多重对应仍须明确报不可准确定位。

修改标记的来源观察可通过同版已存memberId／channel及localSentenceObservation范围绑定为理解上下文，不能要求其伪造指导说明的memberRanges。发现与核对使用同一适配；历史缺范围锚点只有在完整观察对象、原件及分析身份一致时才能恢复。该绑定不产生原生编辑选区或编辑权限，错误显式范围仍须拒绝。

来源范围展示同时接收指导说明的明确关联与同版修改标记的targetGroupIds，复用原件身份／分析代次校验。一个标记关联多项目共享范围时，展示该范围不等于授权当前变量修改所有项目；标记位置、理解上下文和可执行目标须保持区分。

长问题表单末尾须就近提供保存草稿入口，复用整版保存及冲突／锁定／未记录表单校验。界面须区分“记录问题调整”和“持久化保存草稿”；保存不等于确认模板或采用答案，不要求用户回到远处顶部才能完成保存。

**2026-10-23 用户要求：基于已完成结果修复可供人使用的流程，不重发识别或追加付费模型调用。** 有未确认图谱时，第三步必须显示该版候选答案和确认前提，不能静默展示旧固定变量目录冒充第二步的结果。模板规则确认与项目事实采用继续分开，不自动确认、合并不确定语义或采用项目值。没有草稿／确认图谱的旧项目继续保留既有人工填写路径；已确认版本仍是生成权威。原文查看、准确选词及修改位置绑定复用现有组件；业务界面不展示哈希及内部节点标识，不依赖反复折叠才能完成主要操作。历史占位项、历次自动节点及本次结果须核清，保留人工调整和来源证据，不按同名直接合并。进展、未定事项及验收边界见[本次结果验收报告](../testing/drafting-result-usability-20261023.md)。

来源待核对占位（source_seed）与业务变量分开呈现。自动化收尾只将完整成功或明确无业务要求的来源任务对应、仍为pending且manual=false、没有人工规则／修改目标且未被其他有效节点引用的占位退出业务待确认列表；原文、观察、处理状态和替代节点引用留在原图历史。部分、失败、未执行以及人工调整／确认／有引用的项保留核对，不自动授予业务变量确认。该规则不构成同名业务变量合并授权。已有结果修复须保存新草稿、保持旧版本和候选绑定，不重发模型。

修改目标的“文字来自哪个答案”只列当前有效业务问题，复用既有业务／核对分类，不把来源待核对占位或verification事项当作可采用答案。历史目标若已引用不适用或缺失项，保留原引用并明确提示重新核对，不自动清空为固定文字；从核对事项创建新目标时，不默认以该事项作为答案来源。此入口筛选不改变既有条件语义或来源／生成校验。

角色为verification的原文／编辑核对事项同样与项目问题分组显示，无论来自占位还是原子识别；统计、下拉列表和关系图标签口径一致。将明确的符号清理指令改归核对事项，不等于确认执行或补全编辑范围，仍保留来源和未确认状态。不得把“是否删除星号／下划线”这类已由原文规定的处理误作任意项目事实供采用。

问题合并或退出后，已提取候选必须保留原文、旧归属及候选快照，并显示为待明确归属；只可通过既有人工校正接口选定当前问题后迁移，不自动采用，不调用模型。候选校正只更新目标问题定义绑定，不将无关问题追认为新版。

人工合并也不能静默改变必填性：可选与必填定义不一致时拒绝直接合并，先核对原要求。历史未声明 optional 的问题继续使用现有必填默认；不得因为保留方向不同而改变回答义务。

两个问题均只含完全空的 rule 占位（branches 与 inputIds 为空、needsRebind=true、无其他规则字段）时，不因各自携带占位而拒绝明确的同义合并；保留问题仍须保持 needsRebind，原失败诊断留在历史。含实际规则或额外作用域信息的双规则合并继续拒绝，不以占位整理代替条件或修改范围核对。

修改问题名称、角色或其他非条件字段不能重建／清空原规则。条件分支未改变时保留完整 correction 及 needsRebind；移除空隔离规则须使用现有明确操作，不能由普通表单保存隐式完成。

反向同样成立：仅修改条件、修改位置或处理说明，不能重写未编辑的问题原句、类型／角色说明、选项扩展字段、所属关系或缺省固定值。表单按实际变更提交定义字段；共享reducer不得将局部EDIT未携带type解释为类型变化。真实答案类型或选项变化仍保留原有依赖重绑和候选定义核对要求，不能靠忽略定义哈希掩盖误改。

人工核对须展示当前问题的指导说明在同版OER中明确关联的来源范围和涉及段落，保留完整／未完整状态。导航可用原生part／nodePath及原件身份定位唯一段落，但导航不是编辑选区：规范化来源偏移不能直接作Word修改偏移；仍复用准确选词及目标绑定。缺失或重复位置明确保留不可准确定位状态，不按文本相似度猜测，也不自动选择／确认修改。

段内词语范围须单独展示同版OER已存的准确词语，整段另作上下文展示。括号等包装字符若标记为需要独立清理决定，界面必须明示其不在当前词语范围内；不得因为整段被展开就授予整段修改权限。缺少准确词语时显示未提供，不凭段落文字推算。此展示不自动创建原生选区或保存修改。

准确原生Word选区中的普通直接run/tab属于可明确选择的正文控制字符，共用编辑器应支持其随选区替换，保留选区外tab和原run属性。此支持不扩展到catalog规范化坐标跨控制、插入点、字段或分页符；不得用放宽这些边界修复正文tab误拦。实际生成仍必须验证完整原件及最终版式。

问题仍有效但定义已改变（包括清单子字段调整）时同样保留旧候选为待核对，不因定义哈希不匹配而在界面消失。旧候选的结构化展示／编辑使用与提取记录定义匹配的历史字段目录；当前目录继续用于选择归属和校正校验。不能把旧列按同名或位置静默映射到新列，也不能把历史候选展示当作新版定义已获确认。

同一问题的候选可来自不同的已保存定义；按每条候选的修订和定义绑定选择展示字段，同一修订只读取一次、同一定义只返回一次。校正一条不能将其他未校正候选追认为当前定义。只要该问题仍混有未适配定义的候选，整组保留待核对，不能写入可采用目录；全部明确校正一致后才恢复候选采用入口，仍不自动采用。

候选展示及校正复用既有取值展示／字段输入组件，并由同版图谱目录提供标签、选项及子字段；布尔值使用可读答案，结构化清单按有名称的列呈现和编辑，不要求正常用户编写JSON。缺少字段定义或原值格式不合法时保留原内容并明确提示，不能猜测字段或丢失候选。展示、校正不改变事实采用权限。

<!-- drafting-result-usability-20261023:end -->

<!-- drafting-automation80-20261022:start -->
**2026-10-22 用户纠偏：第一步“文件识别”，第二步“变量识别与图谱”。** 第一阶段识别文件身份、内容、结构与实际布局状态；普通 DOCX 上传尚未接通的转 PDF／视觉核对／页码映射仍按[生产缺口](../testing/docx-upload-production-gap-20261022.md)保留，不得把原生解析完成说成布局确认。第二阶段一次启动整套模板的要求识别、变量归纳与原子候选拆解、跨项关系检查，保存待人工核对的图谱。不得用“每次识别1项”、创建草稿、重建版本等内部控制替代自动推进，也不收进高级操作。

**模板专用路由必须复用：** `DraftingService.parseTemplateUpload → DocumentParser.parseTemplate → OerTemplateEvidence.analyse` 在文件识别阶段产出 guidanceNotes、markerCandidates、groups、conditions 与原生字段候选，绑定原件及分析修订；指导说明捕获、修改标记定位及其控制范围是 OER 的职责。第二阶段遍历这些已存观察及其范围／条件闭包，LLM仅解释业务要求、归纳变量、拆解原子候选及检查跨项关系，不再发现指导说明、重判标记或猜测编辑范围。范围完整性由OER及原生编辑校验决定，模型不能补真。所需上下文按既有公共读取与来源范围能力取得；完整原生XML、段落属性及重复正文不进入模型请求。

第二阶段采用既有 Spring／JPA 后台任务、现有模型入口与 OER 已存模板观察。完整sourceMembers仅用于来源覆盖与未定项审计，不能把每段原文无差别送LLM冒充全面识别。每项保存有界增量receipt，最终仅发布一份DRAFT图谱，不逐项保存全图历史；复用现有观察选择及闭包装配，不另造parser或截字chunker。一次来源准备只读一套来源投影；派发守卫查标量身份，最终发布重新核实原件和分析绑定。

阶段提示必须来自实际后台状态：准备来源、建立计划、识别要求与变量并拆解原子候选、检查跨项关系、保存图谱、提取项目资料取值候选、待人工核对。原子识别与要求归集若在同一请求内执行，不虚构两个先后完成事件。显示当前文件、实际完成／总项数、失败和未决数量；刷新只读取并恢复原任务，不再启动调用。

自动识别保留精确引文及要求到节点映射。跨项的同一事实、依赖、冲突、未定关系先保存为关系候选，不按同名或相似条号自动合并，不自动成为执行边。生成结果、源扫描覆盖、语义质量、视觉布局核验分别报告；人工核对、修改、采用及项目身份确认守卫保持。局部 JSON、模型、关系或预算问题保留完整可用结果；来源或 head 冲突不得覆盖新人工结果。

任务绑定项目、模板清单、原分析修订、基础 head、点击时模型配置。单 worker、有限队列、每阶段最多512项、累计最多768次模型调用、6小时期限；达到边界留未执行项。每次派发前写入 IN_FLIGHT；重启标 UNKNOWN，并允许显式继续未发送事项，不重放已发送未知或失败调用。已有图谱保存后重启仅标部分完成，不自动重做候选提取。取消在调用边界生效；已完成的图谱与安全候选可供核对。

本批是自动候选识别及持久编排接线，不宣称全部模板语义完整、逐主变量独立语义调查、关系自动采用或 DOCX 视觉生产链已完成。实现与实际验证见[自动化接线记录](../testing/drafting-automatic-graph-20261022.md)。Goal工具保持 paused，按用户后续热修授权执行。
<!-- drafting-automation80-20261022:end -->

<!-- three-module-new-requirements-20261020:start -->
**2026-10-20 用户追加要求，已纳入本Goal且尚未验收：** DF01固定顶级项目名称／合约编号，识别后经产品用户确认替换项目显示并贯穿整个包；DF02按项目2b NTT／SCT／SCC原件核对并更新预览／生成页眉页脚身份；DF03逐页检查版式与页码；PR01核对Vetting／Advisory实际使用最新system提示词；VT01结构化报告与赛方对应文件核对栏目及版式；VT02审查整包项目名称／合约编号一致性，区分自身身份与合法外部引用。需求、验收案例、状态及证据按ID记录在[新增能力验收记录](../testing/three-module-e2e-followups-20261020.md)。临时项目名不能直接作身份真值，识别不得静默改项目；原件与当前run保持，不追认已覆盖。
<!-- three-module-new-requirements-20261020:end -->

<!-- oer-scc-p32-p57-scope-I49-20261020:start -->
**I49：修复 SCC 原生 PDF 第32页括号指导语编号清单被误作条款根、跨行 `Clause 7.4` 引用被误作标题；源码已采纳，活动服务未部署。**

第32页 `[Guidance Note:` 内的 1.–5. 粗体编号原被 `NormalPdfSourceGroups.marker()` 当根，真实 SCC8.7 在第一条指导语处提前结束。现按未闭合方括号捕获：括号内编号行保持为当前条款成员，直到 `]` 关闭；不按页码、字体或日期特判。第57页跨行引用（上一行以 `Clause` 结尾、下一行以 `7.4` 开头且同正文列）不再建根；标题仍按粗体／字号与原列证据判定。

真实 66 页保存观察复算（源 SHA `1bebab1a…`，365 组／480 issues）：SCC8.7 `body=29`、`end=pdf-page:32:line:34`（即 SCC9 标题），裸 `1..5` 引用列表为空；无裸 `7.4` 根，真实 SCC7.4 仍为完整组（`body=44`，止于 page21 line10）。SCC8.7 仍为 `partial_unknown_scope`，不把边界修正当整份完整；p57 只在该保存观察上验证。

验证：`NormalPdfSourceGroupsTest` 16 项全过（含两个新反例）；相关定向 99 项全过；全量后端 3640 项＝3482 通过＋14 failures＋9 errors＋135 跳过，23 项环境失败与基线逐类一致。0 OCR／模型调用；不部署。证据 `.local/oer-scc-p32-p57-20261020-I49/verification.json`（含真实数据 harness）；台账 I49。
<!-- oer-scc-p32-p57-scope-I49-20261020:end -->
<!-- oer-guidance-pdf-producers-I48-20261019:start -->
**I48：指导语证据覆盖 PDF 来源与全部 canonical 生产者；Word 成员经 retainedBlockIds 映射、按 offset 相交，`native_pdf`／`family` 按字面条款引用关联，仍不可比时显式声明，不改契约、不重生成，活动服务未部署。**

I47 只在 `normal_observed` 生产者的 Word 来源上命中。本轮：
- Word 证据成员是 coverage 段落 id（`docx-p:…`），chunk／pointer 用 `DocumentBlock` id；新增按 `retainedBlockIds` 的映射。夹具已改为真实 id 形态（`docx-p:g1` ↔ `g1`）并覆盖拆分块切片。
- 指导语、条件与 capture 组的 `memberRanges` 必须与所选 part 的 `[startOffset,endOffset)` 相交；>1400 字拆分块不再继承同一块别处的指导语（marker 仍按成员级）。
- `analyseGuidance` 允许无 `nativeDocxParagraphs` 的 PDF 覆盖度，只在 fileKey 为 NTT／SCT／SCC 时运行；分析结果（含失败）按 source 缓存。
- `native_pdf`／`family` 的 pointer part 用 `native-pdf-region:<sha>`，与 Java 成员 id 不可比；此时改用 **字面条款引用**：取 `adoption.clause.clauseId`，选择自身 `referenceCandidates` 或原文中标明 `Clause／SCC／NTT／SCT <编号>` 的指导语，`selection=referenced_clause_literal`。不按物理页或文字相似度推断。
- 两者都不可得时输出紧凑 `status=unavailable`；普通来源无命中不输出字段；`parts` 空值与空白 blockId 跳过。

验证：定向 90 项＝86 通过＋4 项原有条件跳过，0 失败；全量后端 3638 项＝3480 通过＋14 failures＋9 errors＋135 跳过，23 项环境失败与基线逐类一致。两个独立子代理分别核实 PDF／生产者可行性并做对抗审查；其 high／med 项（Word id 映射、失败缓存、逐 chunk 膨胀、空白 id、offset 级）已修。剩余边界：无条款引用的指导语对这两类生产者仍不可比；标准模板按既定范围不进审查／咨询语料；身份 pin 为文本＋结构化，未单独比较 coverage 代次。本轮只提交并推送源码／测试／文档，不部署。证据 `.local/oer-guidance-pdf-producers-20261019-I48/verification.json`；台账 I48。
<!-- oer-guidance-pdf-producers-I48-20261019:end -->
- oer-guidance-source-scopes-I47-20261019:start -->
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

# Drafting source review integration

Status: implementation and isolated code/browser verification complete on 2026-10-17. The user accepted the five-step workflow. Source-quality, model-effectiveness, production database migration and deployment acceptance remain separate.

## Required workflow

Sources → identify candidates → review original sources → adopt project values and inspect actions → preview/export. Preserve template upload/replacement, correspondence upload/removal, failed/partial parse states, extraction history, explicit candidate adoption, three-document generation, body edits/save/discard, regeneration, revision-bound preview and Word/PDF export.

Templates establish questions and rules. Correspondence establishes candidate project facts. Correcting a candidate is not adoption. Saving a rule draft is not publishing a confirmed rule. The old combined-foundation Boolean is not silently copied into two independent facts. Unknown, false, zero and an explicitly empty collection remain distinct.

## One rule authority

The saved review state remains the authoritative graph. Its formal envelope is `{kind:consense-template-graph-v1,graph:{variables,sourceReviews,events,nextId,blank,guidanceMode},sources,selectedDocuments}`. `selectedDocuments` maps NTT/SCT/SCC to the server source ID. Source IDs, original SHA, semantic/source hash and analysis revision are separate identities. Both SCC editions may be reviewed, but their definitions, dependencies and edit targets must stay isolated; generation uses the selected edition.

Each saved revision is immutable. Saving a draft does not change the confirmed head or invalidate the current finished documents. Confirming compares the expected head and draft under the existing project lock, validates sources and definitions, and creates a confirmed revision. Input meanings/types/options/membership have fingerprints: a changed input preserves its historical value but requires re-adoption. Changing only target instructions does not erase unrelated project facts.

No confirmed graph retains the existing catalogue and rules. Once a graph is confirmed, catalogue, allowed field IDs, types, extraction schema, candidates, adoption, plan, target overrides and generation all use that definition. Snapshots include the graph revision, source identities and adopted input state. Ordinary body edits, artifacts and exports retain their existing revision guards.

## Source and edit seam

Reuse OER/native observations and the existing `DocxTemplateEditor`; do not create a parallel parser/canonical model. Source review reads stored evidence without lazy reparse or DB refill. Word reading exposes native paragraph IDs, text including controls, original SHA and paragraph SHA. Its offsets are UTF-16 `editableText` positions, separate from OER named-channel ranges and PDF word boxes.

A source selection may contain multiple exact spans. The server independently binds every span against the chosen original. `nativeBinding` records status, original SHA and spans with paragraph ID/SHA, channel, start/end and exact expected text. Repeated text, malformed or overlapping spans, unsupported fields, missing source or uncertain scope remain explicit. A sparse selection is never expanded to its first/last word. PDF observation does not supply an editable Word original.

Source-ID plus expected-original-SHA asset endpoints prevent a non-primary edition preview from silently opening the primary template. Source originals are content-addressed and immutable for historical revisions. Browser source paths are never accepted.

## Identification and adoption seam

Stored guidance and markers seed pending review items. Optional semantic discovery uses the configured model, independent bounded guidance contexts and deterministic local-ID assembly; it retains raw failures and never confirms or applies its own suggestions. It does not feed accumulated model proposals into subsequent contexts.

Correspondence extraction can name a saved draft revision, producing candidates only. Confirmed extraction fixes both definition and evidence revision at dispatch and checks them again before publishing. Candidate correction/reassignment appends history and stays separate from adoption. Adoption verifies the displayed candidate, graph revision, field fingerprint, original source and exact quote. Reruns retain manual values/reasons, marking actual changes for review. No internal or external model call is required for code tests.

## Verification seams

- Pure graph: scalar/collection typing; independent Boolean conditions; all/any and scoped cases; derived values, conflict/unknown handling, value-source joins; missing references, cycles and SCC edition isolation.
- Native binding/compiler: exact original and paragraph SHA, UTF-16 controls, sparse selections, unsupported fields, overlapping operations and source drift; untouched paragraphs preserved.
- Persistence/adoption: immutable revisions, optimistic head/draft conflict, source replacement, schema compatibility, candidate-only draft extraction/correction, manual retention and project deletion cleanup.
- Consumer integration: confirmed graph reaches catalogue/list/extract/update/plan/snapshot/selected template; legacy projects keep their existing implementation.
- Frontend: actual APIs and dynamic source identities, five-step state, unsaved edits, source/graph change invalidation, literal source interaction and adopted-value display; no localhost prototype iframe or private example data.
- Regression: isolated Java and frontend suites, type checking and build; retain failures and optional/environment-dependent skips. Report these separately from real model/OER/browser/business/deployment acceptance.

The active main workspace contains independent OER work. Implementation tests use its frozen current source in an isolated worktree. Only the reviewed implementation delta may be transferred back, with conflict checks that preserve unrelated changes. Do not deploy, restart active services, run new OCR/model calls, push or publish originals as part of this implementation.

## Implemented and verified

The formal Vue workflow now uses the project graph APIs and immutable database revisions. It includes source selection, exact original interaction, question split/merge and cross-file unification, typed conditions, per-item actions, graph overview, draft history, explicit confirmation, correspondence candidates and explicit project-value adoption. Unconfirmed projects retain the existing catalogue. Confirmed projects use one graph authority throughout extraction, adoption, planning, source selection, snapshots and generation.

The saved-document reader keeps existing identity and revision guards. When a complete edit projection cannot be verified, it displays the sanitized actual saved document, clears unsupported source-only change interactions, and labels the mapping unverified. Original-template mode remains a separate carrier. A missing mapping never substitutes old template text for the current saved body.

- Final backend `mvn verify`: 3,307 tests, 3,188 passed, 119 existing conditional skips, zero failures/errors; packaging succeeded. The 51 new review/migration seam tests all executed without skips, including the saved 291-record graph structure check.
- Final frontend: 189 tests passed, zero skips; `npm run build` passed with type checking.
- Actual isolated browser/API verification: upload synthetic NTT/SCT/SCC and correspondence; create and bind questions from the original; merge NTT/SCT duration into one input; save/confirm rules; manually adopt 42; generate the three documents; inspect actual DOCX and LibreOffice PDF; navigate the full graph and source; restore history as a new draft; edit and save a body paragraph as a new document revision. Changing a source invalidated old adoption; merely saving a rule draft preserved the current confirmed output.
- V23 was executed as written in isolated H2 MySQL mode and checked for old-row preservation and input-binding uniqueness. This is not a production MySQL/Flyway migration run.

## Standards

Independent review and the final reading-fallback supplement found no unresolved blocker. The implementation reuses the OER/native source chain, existing document editor, sanitization, project lock and artifact revision guards. Source-only adoption must preserve the dirty OER baseline and all active main build artifacts.

## Spec

Independent connected-code review and the final seam check found no unresolved blocker in draft/confirmed authority, graph consumers, SCC edition isolation, immutable originals, or saved-body fallback. Optional model discovery remains bounded and candidate-only. No new real model inference or OCR/effectiveness evaluation ran in this implementation.

Earlier failed checks are retained in the local evidence directory. Four existing Vetting fixtures were migrated to the already-established empty-source v2 hash; the frozen Q1.6 expected material gains only the existing ordinal-based public citation alias in memory. Original file hashes, complete ordered-material assertions and business negative gates remain intact; no failure was hidden by adding a skip.

Full logs, conditional-skip reasons, baseline comparisons, browser screenshots and source-adoption receipts are under the ignored local directory `D:/R&D/ConSense/.local/drafting-human-review-implementation-20261017/`. The existing graph experiment HTML ledger records this implementation separately from the historical 67 model generations and 11 input estimates; those budgets and model-effectiveness verdicts are not resumed or upgraded.

## 2026-10-17 OER consumer seam follow-up

The current Word sources support source-bound reading, human rule review, explicit project-value adoption and generation from the selected original. This does not grant complete automatic template interpretation. The existing path remains `DocumentParser.parseTemplate -> OerTemplateEvidence -> SourceDocument -> DraftReviewSources -> reviewed graph -> adopted project values -> DocxTemplateEditor -> revision-bound artifacts`.

A fresh audit found that the source evidence was present but the discovery projection dropped information: 16 of the 63 complete semantic scopes used ordered members or nested targets and became empty text; 3 SCT fields used a pre-normalized named channel; one SCT Bill endpoint had no guidance association. The projection now preserves these representations and the complete field observation, including field labels, insertion anchors and unresolved states. Only the unassociated endpoint gets a new pending source-review item. The other 27 field candidates remain reachable through their guidance associations.

Reading now exposes the existing native SHA/part/expanded-name paragraph path from the shared OER path function. Discovery maps a named-channel range to an editable span only when source position is unique, the entire named-channel text equals editable text, the UTF-16 range and observed text agree, and the displayed quotation equals the ordered span text. Structured source ranges cannot fall back to a different same-text paragraph. Multiple spans remain separate; zero-length insertion anchors never become whole-paragraph replacements.

All explicit multi-span bindings now validate the displayed quotation using the existing newline-join convention. Multi-span replacement/fill requires one exact replacement placement: discovery keeps it under review, the shared plan returns an unresolved placement issue, new target overrides are rejected, and historical invalid overrides remain pending and require review. An unchanged historical entry may be retained while another entry is edited, but it cannot become executable. Retain/delete/not-used keep their existing treatment semantics.

Verification: 92-suite broad regression: 991 tests, 892 passed and 99 conditional/environment skips, zero failures/errors. After the final unchanged-history compatibility adjustment, the six affected/source suites were rerun: 61 tests, 60 passed and one pre-existing capture-dependent skip, zero failures/errors. These runs overlap and must not be added together. The opt-in real-source test uploaded all three actual Word files through the current parser and isolated H2/API methods, read the review projection, and asserted all 28 field candidates (22 SCT fields plus Mr/Ms, and 5 SCC fields), 3 pre-normalized ranges, 14 insertion anchors, the unassociated endpoint seed and complete NTT multi-paragraph evidence. Mock discovery tested transport only; no real model or OCR inference ran. Red reproductions, final reports, source/build hashes and source-only adoption checks are retained under `.local/oer-drafting-audit-20261017/`.

The source-level `scopeComplete`, project applicability and editing permission remain separate. Seven Word markers, fourteen broader conditional links, Bill description cardinality/placement and project values still require source or human decisions. SCC native PDF remains PARTIAL; NTT scan defects and the unexecuted SCT scan remain unchanged. The full 27-Word source-quality census, browser workflow, real-model quality, active services, production DB/index and deployment were not rerun or changed by this consumer repair.


## 2026-10-18 source-range and native-PDF closure

Word source intervals were adopted in I32. I33 adds bound native-PDF source groups and preserves complete group members, relations and unknowns through ordinary and offline canonical material. Actual SCC PDF evidence was recomposed from the complete saved observations and passed `DraftReviewSources.reading`: all six source evidence fields remain exact and `canEdit=false`. Nine explicit targets, shared tails, physical page context and the precise two-line last sentence passed the independent frozen source gate. Three Word template evidence objects remain value-identical.

The final 11-path source delta is adopted without main compilation or changes to active services, production DB or indexes. The 133-suite composite has 1373 passed and 116 conditional skips; the initial environment errors remain in the ledger. SCC p54 geometry, Roofing Installation secondary literal/conditional resolution, project applicability and automatic editing remain open. The complete SCC replay required a bounded 32 GiB offline JVM after preserving an 8 GiB OOM; current service capacity and whole-PDF source quality remain separate gates. Receipts: `.local/oer-word-pdf-closure-20261017/pdf-stage-verification.json` and `pdf-preparation/pdf-post-adoption-independent07.json`; the same HTML ledger continues at I33.


## 2026-10-18 cross-module variable seams

Source revision guards compare the existing original SHA, parsed source hash, algorithm revision, analysis input SHA, evidence generation SHA and evidence status. A changed or invalid OER generation cannot reuse an older reviewed graph even when the original and text are unchanged. Preview display, effective values, actions and target overrides consume one normalized proposal without persisting adoption. Declared fixed/system/derived literals use the same recursive typed schema before confirmation and during evaluation. A business detail named `id` is explicitly visible in the reviewed catalog and stays independent of `_itemId`; legacy hidden metadata remains hidden.

The same OER authority and explicit adoption boundaries remain. Strict published-parent JSON comparison now preserves Boolean/number types at index admission. Unicode whitespace-only quotations and required advisory fields fail their existing presence/location gates. Final scoped regression, exact source adoption, preserved failures and remaining live/source-quality/capacity boundaries are recorded in `.local/oer-module-seams-20261018/verification.json` and the same HTML ledger I34.

## 2026-10-20 usable partial results and BS trade scope

The user's latest requirement takes priority: finding every variable or completing the whole atomic graph is not a prerequisite for making the complete, source-supported results available. Preserve usable candidates as pending review items in the existing graph and correspondence workflow. A truncated JSON response may contribute only strictly decoded complete array members; never guess the unfinished member or close an incomplete value. Reject unsupported quotes, duplicated IDs, invalid types, foreign references and invalid dependency groups while retaining independent valid candidates. Preserve the exact response and the reasons for omitted material. Partial results must remain editable, saveable, correctable and available for explicit adoption after normal source/definition checks. They must be labelled partial, and must not be automatically confirmed or represented as a completed graph. An item with no complete supported result still retains its source review seed and manual completion path.

A complete input question with a valid source quote and type may remain available when its proposed branches or effects fail conversion or graph validation. Retain its original parent scope, quarantine those unusable relations in the exact raw response and explicit diagnostics, and set `correction.needsRebind=true`; graph confirmation remains blocked until the person reviews and rebinds it. Do not turn an invalid derived rule into an invented input. A unique bounded model-local identifier may be assigned a deterministic internal review identifier, with all explicitly local references mapped consistently and the original identifier recorded. Duplicates, missing parent scopes, unsupported types, invalid options and fabricated quotations remain rejected. The prompt distinguishes local list scopes from supplied source effect scopes and asks for separate typed project facts and derived treatments.

The same rule applies when the existing MiniMax/OpenAI adapter reports `finish_reason=length` before returning model text, or a later source call fails. Drafting records the adapter's existing failure kind, provider response, model content and safe finish/usage metadata. Only output-budget exhaustion permits strictly complete candidate members to enter the normal source/type/quote checks; other incomplete/provider failures supply no new candidate. Earlier complete candidates remain available, and incomplete sources remain pending. Public AI adapters retain their existing failure behavior for Vetting and Advisory. A graph save may reuse its verified original bytes, native index and bound OER evidence within that one save; it must not cache source authority across requests or omit the individual paragraph/span and editor safety checks. Text-only source review may validate a prepared immutable package through the same editor operation guards instead of rendering a no-op DOCX for every anchor; structural editing and output generation retain the full apply path.

BS trade scope is independent of the NSC/BSSSC designation method. Fire services, lift and other BS subcontract categories must remain visible, selectable and saveable under both methods, including while designation is unknown. Switching the designation must preserve the selected trade scope. Do not infer the actual selected categories from designation, and do not treat a list as company names. This rule does not authorize converting a BSSSC-specific source clause into an NSC clause or borrowing another edition: exact source binding, applicable edition and edit-scope checks remain necessary.

08:00 on 2026-10-20 is a progress acceptance checkpoint. Record missing user decisions, unavailable services, failed requests and unexecuted coverage, then continue independent tests and repairs. Code/contract, real MiniMax, original-source fidelity, browser workflow and document output checks remain separate. Current test evidence and actual requests are retained under the ignored `.local/e2e-20261020/drafting/` directory.


## 2026-10-23 original-page location authority

Original DOCX review must reuse the shared source paragraph bookmark and LibreOffice PDF named-destination pipeline. Only nonprinting markers may be inserted into a disposable original-review working copy; generated-document layout changes must not be applied. Original downloads preserve the exact uploaded bytes. Preview assets and location bindings must derive from the same deterministic working bytes and render profile. Source ID, original SHA and the actual displayed PDF SHA must agree before coordinates can be used.

Resolve all current native spans or source-observation ranges, excluding empty text members from navigable passages. Keep each distinct source paragraph as a separate location, including repeated identical wording; provide passage navigation and physical pages. A bookmark point authorizes navigation only. Shared PDF text-layer measurements may refine highlighting, while exact native UTF-16 span checks continue to govern editing. Missing, partial, stale or mismatched geometry must not prevent reading or authorize an edit. Asset endpoints must read narrow metadata rather than full source archives. This contract does not certify guidance semantics, existing graph correctness, headers/footers, native PDFs or scans. Current scoped verification and preserved failures are recorded in the 2026-10-23 usability report, round299—315.


## 2026-10-23 shared LibreOffice rendering correction

All DOCX-to-PDF consumers, including original/template/generated previews, Vetting reports and OER Word print-to-PDF visual parsing, must reuse DraftPdfConverter and its guarded DocxTemplateEditor Latin-hint compatibility policy. Do not create separate module-specific soffice commands or font replacement code. Preserve exact uploaded/saved DOCX bytes and OOXML evidence. Identify the temporary renderer copy separately in provenance; only eligible Latin formatting hints may change, never text, fonts, sizes, tables or business decisions. Version the render profile so stale PDFs cannot be reused. Current non-PDF Advisory text reading is not converted merely by this rule.

Original review also reuses exact-source-hash registered pagination controls from the existing native layout registry; unknown editions do not inherit them. This supersedes the earlier claim that every preview working copy adds only nonprinting markers. No generated project-identity stories or contract edits are authorized for originals. Validate actual source text, PDF pages and visible layout separately from bookmark existence. Unbound manual page selections remain reviewable as pending targets; an explicit choice of a source paragraph may bind its complete range, while automatic editing still requires exact native validation. Consecutive component updates must not delete dirty snapshots while a replacement instance is loading.

Empty source paragraphs can carry layout meaning through explicit or inherited line height, before/after spacing, cell position and page flow. Empty text is not permission to compress or remove a paragraph. Shared rendering must preserve these properties unless a compatibility correction is registered and verified against the exact original SHA and native anchors. Do not apply a universal empty-paragraph compaction policy, and do not universally remove existing compatibility controls to reverse one document's defect. Evaluate each proposed change with bounded same-source differential PDFs, checking the affected guidance/body relationship, physical page count and blank pages, source text and locations, tables and visible footer placement. Preserve original bytes, rejected variants and any remaining layout discrepancy; matching text or complete bookmark destinations alone do not establish layout fidelity.

The 2026-10-23 NTT correction removes only that exact source's 174 empty-spacing compactions while retaining its other registered controls; it remains 11 pages with all 178 nonblank paragraphs text-matched and located. The residual 16.05pt guidance/body start offset and varying body-footer baselines remain open. The adjacent SCT differential demonstrates why this cannot be generalized: removing all 313 SCT compactions changes 27 pages to 31 and creates four blank physical pages, despite retaining all 469 nonblank locations and the same text. Keep the SCT production registration unchanged until a narrower exact-source correction has passed its own evidence checks. Reuse this rule through the same DraftPdfConverter/native-layout path for every DOCX PDF consumer, including OER Word print-to-PDF; it does not add an alternative renderer or authorize document edits. Current evidence and deployment/browser boundaries are recorded in the 2026-10-23 usability report, round354—362.
