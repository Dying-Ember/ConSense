# Drafting 保留指导文字时的 SCC 目录更新修复

日期：2026-10-26，时区 Asia/Shanghai。本文只记录本轮实际前端目录卡点的根因、窄修、软件回归、真实首版产物的离线渲染与运行交接；不披露模拟通信正文，不评价模型识别／原子拆解／匹配的准确率。

**登记状态：指定 SCC 保存修订的真实前端目录验收 PASS，整体业务 E2E 未完成，heading-flow 仍 UNKNOWN。** F356 重新生成后，F360 实际点击“更新目录及预览”，F362 终态页面显示“目录页码已核对，无需修改。条款标题与正文的分页仍需人工核对。”F363 对应前端 PASS／`updated=false` 分支与身份保护，F364 实际下载与保存稿一致。此结论仅绑定下文 revision／SHA；原首版离线 PASS 另列，后续范围、条件或事实调整后的新稿须重新验证。

## 实际卡点与最小修复

实际前端第 276→277 步点击“更新目录及预览”返回 `HEADING_FLOW_CHAIN_CHANGED: CSc1bcfdc59caea7749d8bf08fa41c`。同一首版下载稿通过 `DraftNativeLayout.activeHeadingFlows → DocxTemplateEditor.updateNativeHeadingBoundaries` 离线重放，在生产调用点复现相同错误；只保留首条关系重放仍复现，原始失败均保留。

注册的排版规则面向已清空指导段的文稿，要求标题与最终正文之间的 gap 为空。本轮图谱生成保留了尚未核定的原文指导段。正式保存的 1319 个 binding 均为 `exact`，244 条关系和 411 个 profile control 通过原有身份校验；不存在需要伪造移除记录的端点。93 个非空 gap 去掉唯一新增 CS 来源 bookmark 后，完整原生段 XML 的 SHA-256 逐一等于固定来源 registry 的对应槽位哈希。

修复保留原 `updateNativeHeadingBoundaries` 的严格空 gap 行为，另外提供只读来源核验。只有原错误为 `HEADING_FLOW_CHAIN_CHANGED`、正式 profile control 已核实、全部活动链的端点／段数／顺序／控件继续有效，且每个非空 gap 的指定来源 bookmark 与原生 XML 哈希均精确相同时，才允许进入既有的独立目录缓存闭环。不删除指导文字，不放行未知插段、改字、调序或不明 bookmark，不做标题修改。

这种文稿的目录可以单独达到 `status=PASS`，但 `headingFlowStatus` 保持 **`UNKNOWN`**，原因记录为 `RETAINED_VERIFIED_SOURCE_GUIDANCE`，`headingMutationCount=0`。不得把目录 PASS 改述为标题与正文同页、整体排版、Word 原生分页或业务内容均已验收。

越过上述卡点后，真实首版又触发 `TOC_CACHE_XML_NOT_REVERSIBLE`。原文稿的声明后换行、223 处字体属性顺序和备注文字换行与 Java Transformer 的序列化结果不同，旧逻辑因此拒绝整 part 重写。目录页码修复现在仅对 DOM 已确认的纯数字 PAGEREF cache 文本槽，在原始 UTF-8 XML 中进行等宽替换。目标集合、数字格式、宽度、槽位对应数量、DOM 结果一致及其他 package part 哈希检查继续执行；其他原始 XML 字节不因目录更新被重新排列或改写。

## 产品变更范围

- [DocxTemplateEditor.java](../../src/main/java/com/consense/document/DocxTemplateEditor.java)：来源 gap 契约、共用严格链检查、独立只读精确来源核验，以及只改指定数字 cache 槽的写入。
- [DraftNativeLayout.java](../../src/main/java/com/consense/service/drafting/DraftNativeLayout.java)：从现有固定来源 registry 向每个 gap 传递按原位置绑定的来源 bookmark 和原生段哈希；原 registry 与历史 profile 内容不变。
- [DraftTocFinalizer.java](../../src/main/java/com/consense/service/drafting/DraftTocFinalizer.java)：复用既有 TOC-only 模式，在精确来源核验后允许目录独立验证，保留 heading-flow UNKNOWN。
- [DraftTocRetainedGuidanceTest.java](../../src/test/java/com/consense/service/drafting/DraftTocRetainedGuidanceTest.java) 与 [DraftTocCacheLexicalPreservationTest.java](../../src/test/java/com/consense/service/drafting/DraftTocCacheLexicalPreservationTest.java)：精确保留、篡改拒绝、顺序和 bookmark、契约边界、声明／属性／换行保全及注释／CDATA 不能误导目标槽位的回归。

未修改模型提示、原子能力拆解、固定变量定义、图谱及其采用值或生成决策。修复没有通过业务写 API 或数据库写入代替前端操作。

## 分层验证结果

| 检查层 | 实际结果与边界 |
| --- | --- |
| 既有 heading／TOC 及新增契约断言 | 最终候选 `focused-full05` 为 42 通过、2 明确跳过、0 失败。使用隔离 ABI 类路径，直接执行现有纯 JUnit `@Test` 断言方法；没有冒称完整 Maven／Spring 服务回归。 |
| SCT 与 native 布局补充断言 | `native-extra05b` 为 18 通过、0 跳过、0 失败，覆盖 SCT 真页眉、实际 2a 来源 anchors、保存布局状态及失效保护。与前项测试类不重叠。 |
| 最终软件计数 | **60 项通过、2 项跳过、0 失败**。不叠加先前候选重复运行的数量。两项跳过分别需要旧 runtime33 restore-context 和旧人工 24 处正文编辑产物，未提供外部夹具，未记为通过。 |
| 真实首版 SCC 离线渲染 | 正式 LibreOffice renderer profile，2 次真实渲染、92 项目录目标；目录缓存与目标位置稳定，目录 PASS。heading-flow UNKNOWN，93 个精确来源 gap、零标题修改。没有把离线结果写回项目或作为前端下载交付。 |
| 独立 package 字节比对 | 仅 `word/document.xml` 内 32 个目录 cache 的 35 个 ASCII 数字字节改变。该 part 仍为 2,309,170 字节，其他 2,309,135 字节与所有其他 package parts 完全相同。 |
| 指定新稿的真实前端目录 | F356 重新生成后，经 F360 实际按钮触发、F362 终态页面、F363 身份保护分支及 F364 实际下载确认目录 **PASS**、`updated=false`、heading-flow **UNKNOWN**。F366、F368 另完成本轮 NTT、SCT 下载，三份均与保存身份一致；整体业务内容校核和完整 E2E 仍未完成。 |

真实离线首版输入 DOCX SHA-256 为 `b16a73140a965fb11da80df2b4d45b5fab32ba00bbf5bd07782cfd3bfd08bc75`；最终离线输出为 `a3bb35c0973531e79a1fd70642171aa28d34b96b3fdb2416251e5a57733d488f`。固定 renderer profile 为 `fe7e495bdf797a53216feb670e073a77a558318a26d53bad98d46feb360a65c8`。输入原件与原下载稿均未覆盖。

## 真实 UI 与当前保存 proof 的只读取证

本次实际 UI PASS 绑定 SCC revision `2316aac1-9568-4ee5-95ab-9aabe3c4e83f`，实际下载 303,560 字节，DOCX SHA-256 为 `ac618d80243e655d71d9dea37e5d438846ba5c1b4cc312279b2ebfede73b5ba5`。已保存的终态截图、前端操作记录、只读 GET `documents-after-ui-toc.json`、定域 SELECT 和真实下载逐项对齐；读取时 `stale=false`。该 DOCX 不是上述旧首版离线输出。

前端 `DraftingView.vue::refreshDocumentToc` 只有在 `tocFinalization.status=PASS`、项目／token、原保存 revision／SHA、无未保存或 stale 状态、返回文件身份，以及最终活动文稿身份均通过后才显示该提示。`updated=false` 还要求 revision／SHA 保持。实际 F362 页面已留截图；未用代码检查或离线结果替代点击。

一次 PreparedStatement `executeQuery` 精确限定该项目当前三份保存修订，只对本 SCC SHA 与正式 renderer profile 读取 PDF manifest；完成后 rollback，零 DML／DDL／commit，零业务 API、渲染或模型调用。H2 的 `Connection.isReadOnly` 报告 false 已原样保留，采集器通过唯一 SELECT、明确参数及 rollback 约束只读行为。

实际落盘 proof 位于数据库的两个字段，现已分别导出到忽略证据目录：

- `draft_artifact.ledger_json`：上述 revision 的 `kind=toc_finalization` 条目。
- `draft_pdf_artifact.manifest_json.tocFinalization`：按当前 DOCX SHA 与正式 profile 绑定的 PDF artifact `0ca7d97b2264d63ca188dd5dc0d152644697c70d470959b6cf9348a026b9cfbe`。

两处已有 proof 语义相同：目录 PASS、92 目标、2 次真实转换、32 项 cache 修改、93 个精确来源 gap、heading-flow UNKNOWN、零标题修改；final DOCX SHA 与本次 UI 下载相同。PDF SHA `34824d69dcef94a7ccc8fd8f0c42cc95ba0f2cb901462c01e1b73ba8b4b68dfa` 与 GET 及既有磁盘 PDF 逐哈希相同，没有为取证另触发渲染。

这些是该保存修订已有的生成／finalization proof。F360 的 `updated=false` 分支只返回核验 proof，不追加 revision 或保存该次 response proof；本轮没有封存完整 F360 HTTP 响应，不把已有 proof 的 32 项修改冒称为 F360 的修改量。F360 的实际页面结论是“无需修改”。

## 构建、失败保留与运行交接

最终候选是 `candidate05/consense-drafting-toc-retained109.jar`，SHA-256：

`3a6953d37f9c33e822811172d58fe7b38453283f080471ce3a1d0c6a279f2767`

它以原运行 batch108 的 `a3510ab3658cd65b74573e4199cc7c930f91f22ab10aaa992d708c2504a9774e` 为基线，只替换上述三个类家族的 46 个 class，另外 1033 个 jar 条目逐哈希不变；未混入并行的 `DraftingService` 或 OER 修改。

**`candidate04` 已 rejected，禁止部署。** 源码 diff 自检发现一次默认编码读取污染了原有 SCT 文字；在最终 candidate05 中已按冻结 batch108 源码恢复，并补跑前述 18 项 SCT/native 断言。该失败候选、发现过程与收据保留，没有以最终成功覆盖它。

主任务已按确切监听、CIM 命令行和进程创建身份，将独立 Drafting 18086 后端从 PID 35552 更新到 PID 42880，绑定上述 candidate05 SHA。原 H2 PID 32040 与前端 PID 24568 保留，其他服务不动；不依赖旧 `manual-runtime.json` 中失真的 jar 字段判断实际版本。启动前后证据分别保留。随后指定 SCC 保存修订实际完成上述前端目录验收；该运行更新与 UI PASS 均不构成整体业务 E2E 通过。

本机忽略证据根为 `.local/drafting-auto-e2e-novel01-20261026/frontend-toc-blocker-fix/`，不将私人输入正文、实际 DOCX／PDF 或运行证据提交 Git。主要证据包括：

- `baseline-red.log`、`minimized-first-flow-red.log`、`actual-context-red.log`：原始同错与实际正式上下文重放。
- `cache-same-values-red.log`、`candidate01-native/`：保留的后续序列化失败。
- `focused-full05/assertions.log`、`native-extra05b/assertions.log`、`final-readiness.json`：最终断言及来源冻结复核。
- `candidate05-native/summary.json`、`proof.json`、`independent-cache-only-diff.json`：真实首版两次渲染和独立字节核验。
- `candidate05/candidate-jar-receipt.json`：三个类家族及其他 jar 条目保全。
- `deployment05/before.json`、`after.json`：主任务执行的确切后端更新，后者明确为 `RUNNING_UI_VALIDATION_IN_PROGRESS`。
- `deployment05/toc-ui-acceptance-summary.json`、`.md`：F360–F364 的真实 UI、身份保护、实际下载与旧离线证据区分。
- `deployment05/read-only-toc-proof-01/`：唯一 SELECT 的原始行与 rollback 收据、当前 SCC ledger／PDF manifest 的已有 proof，以及既有 PDF 文件身份。

该目录卡点已经通过指定保存修订的真实前端操作验证，三份本轮下载齐备。主任务继续人工范围与条件校核、图文步骤和正文检查；文稿再次变化后需要绑定新身份重新验证，不能沿用本次 PASS。本文不宣称完整目视审查、来源／业务完整性、整体业务 E2E 或 Word 原生分页通过。
