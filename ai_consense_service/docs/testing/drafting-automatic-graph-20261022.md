# Drafting 文件识别与变量图谱自动化接线

**后继纠偏：** 原隔离QA无沟通资料，不能证明真实候选提取通过。用户手测发现巨型字段重复传输及连续调用异常；原任务已按要求取消，hotfix86已部署。[根因、修复、132项结果和仍未完成的公共按需迁移](drafting-extraction-payload-hotfix-20261022.md)。

2026-10-22。接续用户手测热修，Goal工具保持paused。

用户最新明确：第一步识别文件；第二步识别变量、原子拆解并拼成图谱。此前仅把内部按钮搬到来源页，没有实现整套自动推进，不能代表用户所要求的自动化。现将第一步保留文件动作，第二步建立一次启动、持续推进、可恢复读取的后台任务；旧“下一步：识别模板要求”、草稿／重建／1—10预算按钮不再向产品用户暴露，也不移入高级操作。

## 实现

- 初版`DraftAutomaticDiscovery`把完整sourceMembers装包交LLM，在真实NTT只读检查中暴露错误：正文22,698字符，两个请求被重复原生XML／段落属性／范围元数据展开为约261万／123万字符，均被大小守卫阻断，0模型调用。此实现未部署，不能视作正确复用。
- 用户再次明确Drafting模板是专用OER路由，指导说明、修改标记、修改范围在`parseTemplate→OerTemplateEvidence`阶段产出；现已改为唯一复用旧发现器的已存OER观察选择、范围／条件闭包、类型／引文和原生span校验。完整sourceMembers只作来源覆盖与未定审计，不作为无差别全文LLM主任务。LLM消费结构结果做业务要求、变量、原子及关系，不能重判结构或把scopeComplete=false补真。
- `DraftAutomationService`使用单Spring worker和JPA run/item；持久化紧凑计划和每项增量，不逐packet生成完整图谱修订。最后合成并保存一次DRAFT，与resultRevisionId同事务提交。采用既有LlmProfiles异步绑定和QwenGenerationLedger，不另写provider client。
- POST `/api/drafting/{projectId}/graph/automation`启动；GET `latest`及`/{id}`只读状态；POST `/{id}/cancel`停止；`/{id}/resume`仅允许尚未发布结果的INTERRUPTED任务继续未发送事项。已发送未知、失败项不重发；终态重新识别是新任务和新调用。
- 前端第1步“文件识别”、第2步“变量识别与图谱”，2秒节流读取真实phase/message/currentFile/counts。切项目和卸载停止poll，旧响应不得回写新项目，刷新接回原job。执行中锁定可能改变来源／图谱的动作；脏值不被自动覆盖。
- 图谱保存后复用原`extract`提取项目资料候选，不自动采用。取消或达到预算时保已完成候选，来源和head冲突停止发布。无项目沟通资料时图谱仍保留，取值阶段为部分完成。

## 验证记录

核心代理使用已有classpath和独立256MiB JVM实际跑新自动core8项与旧发现29项，37/37通过，证据`.local/automatic-discovery-core-check/results02.log`。此为mock契约验证，不是模型／来源／业务验收。

Root首次Maven编译通过。首轮测试因Windows的`&`路径被Surefire fork命令拆分，实际0项执行，保留`service-regression01.log`；随后同JVM执行组合80项，74通过、6个新任务测试因测试夹具试图mock final LlmOperation而出错，保留`service-regression02.log`。夹具改为真实配置生成operation，后继验证进行中。

专用路由纠偏后：核心小回归39/39通过；Root组合`service-regression06.log`为84项中83通过、1新集成夹具失败（原本仅有普通正文，没有OER指导说明，不能再期待全文fallback）；改为真实原生DOCX含指导说明后，`service-regression07.log`该项通过。同一修复的84个唯一方法均有实际通过证据，无skip；不冒称单窗84全绿。Maven `package01.log`构建成功。

前端代理16个相关脚本、typecheck和build通过，记录`.local/drafting-automation-ui81/verification.json`；root后继3个真实组件脚本及typecheck再次通过，`ui-final.log`／`ui-typecheck-final.log`。本地TEMP权限导致的初次build失败保留，不算产品测试失败。

20:47后端更新为`.local/oer-demand76/runtime/consense-automation80.jar`，SHA`4263d498499a2228ff70298dbd7b11bbd3f0f74b151b8e4b289464a8082f5dc0`，PID20700／18086／64GiB。仅替换核对过身份的业务后端，H2 PID32040／18082和前端PID24568／5177保持；升级前后25项目、Sean的3模板与15资料列表精确一致。原件、已采用值和用户项目不因本轮测试改写。

真实浏览器任务空间6首次复现新UI对旧后端GET automation/latest返回404，启动按钮被禁用；这是本轮先HMR前端而未同步后端造成的实际阻断。新后端部署后，同页刷新启动按钮恢复，真实点击一次在隔离QA项目启动Qwen27B任务`1d2a0872-63f6-4cd1-9f13-94a57ac12bed`。23个已存OER任务持续执行，页面显示真实阶段、文件和计数；实际刷新后GET恢复同runId，未重新POST，截图`automation-running.png`已亲看。最终图谱与可读结果尚待原任务终态，不能据此先报整条链通过。

**20:54原任务已终态并保存待核对结果。** 23个OER来源任务＋1个关系任务＝24/24执行，0个failed、15个未决／部分完成事项；保存14条要求、18个原子节点及5条非执行关系候选。结果DRAFT修订`48818355-0510-4e94-833f-3322179b3de6`，currentRevisionId仍null，未确认图谱或采用项目事实。真实浏览器已展开要求和关系候选，看到了对应名称及完整引文，无页面错误；`browser-result.log`和已亲看的`automation-result.png`保留。源扫描状态为PARTIAL，不报业务质量全过；本QA无沟通资料，取值候选阶段未完整，无自动采用。

用户要求先热修、准确率后置。后继只记录：部分原子项与关系仍未决；同一NSC适用性在不同观察中各有节点，尚待同一事实核对；中文页面部分模型理由为英文。此批不因此扩大模型质量调查或重跑整套材料，不将24/24执行冒充24/24语义正确。

终态刷新检查保留一次脚本失败：`browser-refresh-result.log`错误地假定刷新会自动打开终态结果页，等待30秒超时。实查没有页面错误或数据丢失，刷新按既有产品行为回到第1步；点击“查看变量识别任务”进入第2步后，原runId及resultRevisionId均保持，结果可见且无错误，见`browser-refresh-result-success.log`及`automation-refreshed-result.png`。原文按钮已实际点击，无页面错误；本轮不把打开来源入口等同逐条来源质量通过。测试空间6已正常finish关闭，未接管用户个人页。

## 保留限制

### 当前真实NTT的有限核查

隔离QA项目`proj_mvjgfph94gd3`的来源63，原件SHA `60bd8796aa828863c7edfe8617b7a5c56ee383afe8ba87a089e820f0fd9a982c`，`analysisRevision=native-template-rules-20261019-r21`。从当前后端读取669个已存原生成员、11条指导说明、12个修改标记候选、160个来源组及15条条件，读取证据为`.local/automatic-discovery-core-check/qa-reading63.json`。

- 11条指导说明中10条captureComplete=true，且各自通过guidanceNoteId／guidanceNoteIds关联到一组scopeComplete=true的范围；不能只看指导说明对象自身scopeComplete=false就宣称全部范围失败。那些范围的业务边界仍需逐项原件核对，程序声明完整不是质量裁定。
- 末条铁路保护区说明captureComplete=false，无完整关联范围，保`GUIDANCE_SOURCE_SYNTAX_INCOMPLETE`。下载同SHA原始DOCX，直接检查`word/document.xml`第23表第3列：其三行文字为`(“Not used” if`、`no part of the`、`Works will be executed within the Railway Protection Area`，后继空行并进入第18条；对应原生文字中没有结束括号，不能未经进一步版面核验就归为解析漏字。
- 12个星号记录中11个为conditional_source_target，另1个位于`(*Amend if NSC is not applicable)`，被标negative_instruction_symbol，没有当成独立编辑目标。
- 完整组112、不完整组48，含物理来源范围、候选条款、guidance capture、marker行等不同种类，**不得把112/160当准确率**。本次未完成全NTT的漏识别检查、逐目标范围质量、SCT/SCC及视觉对照，不对专用路由给总体准确率或全过结论。

检查明细`.local/automatic-discovery-core-check/ntt-route-quality-inspection.json`，下载原件`.local/automatic-discovery-core-check/ntt-source63.docx`，0模型／OCR调用。原件、运行证据均在忽略目录。

- 源扫描完成不等于模型已理解所有业务语义；跨项关系是pending候选，尚需人工核对及采用。
- 当前派发标量守卫识别文件集合／SHA／路由／解析状态变化，同原件解析修订变化由最终发布的fresh来源绑定拦截；不声称每次派发已重读完整分析。
- 普通DOCX上传仍未自动转PDF、双视觉核对或页码映射。本次不补跑整批OCR，也不把文件解析状态和变量图谱状态混为一谈。
- 未完成的真实模型／前端／原件验证、部署和远端交付需按后续实证更新，不能以mock通过替代。
