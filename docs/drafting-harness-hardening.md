# Drafting harness：完整逻辑单元与来源约束恢复

## 改动目的和边界

用户的本地前端验收指出两处「识别留痕中有值，表单却未填」：Bill 已返回完整清单，但引用只有介绍句；防水区域返回了被切开的清单片段，同时改写了编号标点。此次加固处理供给上下文和候选接收两个边界，不把考题参考答案放入模型输入，也不对缺失项直接补答案。

业务 harness 当前部署版本为 `draft-extraction-20261008.20-formal-bill-identities`，继承 `.19-complete-source-lists` 的上下文与恢复检查，进一步修复正式 Bill 名称及候选合并。完整实测 trace 身份为 `draft-extraction-20261008.20-formal-bill-identities+joint-evidence-20261007.2`。模型配置仍使用本地已部署的 64K 上下文、medium thinking、4K reasoning budget、16K 总生成预算；16K 同时包含 thinking 和最终回答。此文记录 harness 修复，不把模型容量或训练效果改善计入成果。

修复保留用户已有项目、人工采用和文稿。真实考题必须在新的明确标注项目里运行，识别阶段不自动确认候选、不生成合同。最终 PDF 排版、Bill 分发及用途图、Safety 12.2/12.3/12.4 关联等属于后续业务验收，不能由变量识别分数代替。

当前 `.20` 后端部署收据为 `.local/state/harness-hardening-20261008/deployment-20/receipt.json`：active JAR SHA-256 为 `187D0475CCA6221B0035F5E7BA405DBE343916CCEA310553EB3B89125028A4E8`，runtime SHA-256 为 `AC9C02A2396AA61056C55DA179873DB09A9E9F36A7EA494B1BFB24D0D1A35E5A`。只换后端 PID 至 97500，Bonsai 和前端 PID 保持 65476、97260；watcher Running。受保护旧项目的 variables/documents/templates/inputs 四份 HTTP 读回内容分别与切换前字节一致，用户此前采用和文稿保留。

历史 `.19` 部署收据 `.local/state/harness-hardening-20261008/deployment-receipt.json` 的 JAR SHA-256 为 `172D867DDE2FF7C6F31FA01F16306E2E8C1966E3A3F00CC41F8E70DF4A91F4F3`。以下保留首次真实考试及其 Bill FAIL，不能用 `.20` 的离线修复追改 `.19` 实测。

## 实现

`DraftOrderedSourceLists` 从原文偏移识别连续编号逻辑单元。清单的条目、相邻字段标题、父级控制标题和紧邻限定句一起保留；编号断裂或重复不会拆成可接受的短清单。`DraftSourceContext.primary` 和 `recall` 在单元可容纳时提供完整原文，不拼接彼此不连续的片段，也不改变稳定的核心切片。超过现有 6,000 字符上下文预算时保持有界，并留下 `context_insufficient`，不能把截断结果当成完整清单。

`DraftCandidateEvidenceRecovery` 在原有 shape、confidence、字面出处和业务接收校验之间，增加有限的证据恢复及完整性检查：

- Bill：完整候选与唯一原文表的所有身份行一致，且原模型引用可以锚定该表的介绍范围，才把引用重定位到实际连续表单元。缺行、改正式名称、重复身份、无关引用、同值的不同表范围，以及 pending/否定控制，不能触发恢复。可选 type、purpose、trade、placement 继续受原有逐行来源校验约束，不根据名字补分类。
- 编号文本：按原文单元的实际数字序列、条目数量、每个条目正文和顺序判定完整性。没有硬编码「防水一定有 18 项」。即使完整引用可见，模型只给 1–16、12–18、漏中间项或只有无编号正文片段，也不得接收为完整字段。
- 标点恢复：全部条目正文和编号身份已与唯一原文对应，只在编号 marker 存在字面差异时恢复原文表示，如 `7Internal tankings;` 恢复为 `7.Internal tankings;`。不改变正文词语、数字身份、否定或作用范围，不补模型未返回的条目。
- 恢复始终受当前实际 supplied window 限制；正确内容只存在于原始整文、未提供给该次模型请求时，不得越界借用。低 confidence、错误 shape、空值、未知值不会由恢复逻辑变成有值候选。

诊断码 `evidence_quote_reanchored` 和 `source_value_typography_restored` 是允许的恢复留痕；`source_list_incomplete`、`source_list_invalid_sequence`、`source_list_value_mismatch`、`source_list_ambiguous`、`source_list_unresolved`、`context_incomplete_source` 仍阻止接收。原模型 `rawValue` 和 attempt 的 `rawResponse` 保留，接收后的 `normalizedValue` 和实际 `sourceQuote` 单独可查。因此报告应区分「模型原回答正确」和「harness 在受约束的来源内恢复后正确」。

`.20` 增加两层 Bill 保护，均不从其他来源补正式描述：

- **正式名称的位置**：`DraftCandidateGrounding.billDescriptionSlot` 要求候选 description 占据 Bill 编号后的完整名称位置，支持实际表格 cell、带引号名称和 explicitly named/titled/description 等正式命名语句。仅出现 `being labelled`、classification、reference 或正式名称后面的限定语，不建立新的身份。不能把 `Other Works (External Areas)` 截成 `Other Works`，也不能丢掉分号、and 或冒号后的正式副标题。实际名字可以包含这些标点。原始 `All Provisional` 误识别因此在 intake 阶段拒绝；未知定价分类仍由独立逐行 metadata 检查处理。
- **显式定价 namespace**：`DraftBillCandidates` 用 source 明确的 `BQ:number`、`SOR:number` 分开身份，同一个 number 在两种明确 namespace 中可以分别存在。type 缺失只是缺 metadata，不自动创造一个新父 Bill；只有该 number 在全部候选中对应唯一明确 namespace，且完整 description 一致，才补齐兼容信息。存在多个 namespace、名称不一致或非空 metadata 冲突时保持 conflict，由用户选择，不隐式采用最新上传或某个较高 confidence 候选。
- **先看全部候选**：正式保存路径一次性收集所有 Bill 候选的 namespace，再解析无 type 行，避免逐对合并时被首批候选提前决定归属。原候选 value、quote、source identity 和顺序不被合并改写；生成 id 不是新的业务身份。
- **三态**：没有可用候选保持空字符串/missing；明确有出处的空列表保持 `[]`；空列表与实际 Bill 行同时出现或身份/metadata 不兼容，返回 conflict，不能把未知当无 Bill，也不能自动丢弃冲突。已有人工采用值照原规则保留，来源变化时提示复核。
- **保序**：优先用已经完整列出最终身份的来源候选作顺序骨架。部分候选补 type 或其他可选信息，不把中间的 Bill 移到头尾；混合 typed/untyped 表也保持原定义顺序。

## 离线回归

checked-in fixture 位于 `ai_consense_service/src/test/resources/drafting/harness/captured-bill-waterproofing/`，约 27.5KB。它只包含 SIM04、SIM11 原文与 6 个真实模型 item、请求偏移、run/part/attempt/item 身份。源文本 SHA-256 与 DOCX 字节 SHA-256 分别记录，已与当前 Ref 数据核对；二者不能与应用的 parsed `sourceHash` 混为一谈。

所有回归走生产 `DraftExtractionHarness.decide` 或 `DraftSourceContext` 入口，不复制另一套接收算法，不调用模型、HTTP 或用户数据库。

| 测试类 | 当前用例数 | 主要覆盖 |
|---|---:|---|
| `DraftingCapturedOutputRegressionTest` | 12 | 真实 Bill 引用恢复、真实防水失败输出、完整 18 项正例、只差编号标点正例、3 项动态正例、旧切片与完整新窗口 |
| `DraftingEvidenceRecoveryBoundaryTest` | 15 | pending/否定父标题、尾部 pending、无编号和缺项、跳号/重复号、歧义 Bill 表、null/unknown/[]、越界恢复 |
| `DraftSourceContextOrderedListTest` | 10 | primary/recall 完整原文偏移、编号单元、父级控制、无关段落、超预算边界、损坏序列 |
| `DraftEvidenceQuotesTest` | 2 | 支持引号样式差异，拒绝改词与拼接引用 |
| `DraftingCapturedBillQualificationTest` | 2 | 首次真实浏览器考试的「All Provisional 误作正式 Bill 描述」负例与原完整 13 表正例 |
| `DraftBillDescriptionSlotTest` | 6 | 正式名称与 classification/reference/限定语区别、括号/and/分号/副标题保留、pending 命名拒绝 |
| `DraftingBillCandidateMergeTest` | 10 | 真实错误合并重放、定价 namespace、全部候选收集、未知/空/冲突三态、兼容 metadata 和保序 |

首次对未修复代码的真实输出重放得到 **12 tests / 4 failures / 0 errors**，四处红测分别是 Bill 介绍句无法恢复、完整防水单漏 `7.` 无法恢复、只有 1–16 的值被完整引用误放行、切片窗口没有携带完整清单。独立证据位于 `.local/state/diagnostics/harness-regression-red-20261008.{log,xml,json}`；历史红测没有被新结果覆盖。

历史 `.19` 最终快速回归收据 `.local/state/harness-hardening-20261008/captured-final/receipt.json` 已绑定表中前四个类和 fixture SHA-256 `FB77C36710AA766D73F53048B19A32E6C840ED1936D27FAFFD1E506324B5B727`，结果为 **39 tests / 0 failures / 0 errors / 0 skipped**，exit code 0。这是生产接收/context seam 的离线验证，不能证明服务已使用新 JAR，也不能替代真实模型考试。

`.20` 的最终隔离源码 package gate 收据 `.local/state/harness-hardening-20261008/related-gate-20-receipt.json` 已将 active JAR SHA-256 绑定到 **164 tests / 0 failures / 0 errors / 1 skipped**，覆盖 15 个相关 suite；唯一 skip 是 Workflow 的既有项。当前快速脚本中的七类合计 **57 项**，均已包含在这次 164 项 gate 中且无 skip：这两个数字是包含关系，不能相加成新的测试总数。另有 Python recovery auditor 的 7 个 integrity tests 全部通过；Python 的 7 项不计入 Java 164。

**更宽的 `Draft*` 测试没有全绿**：已保存的首次全量结果为 280 tests / 6 failures / 9 errors / 56 skipped，日志和报告在 `.local/state/harness-hardening-20261008/drafting-suite.log` 与 `drafting-suite-reports/`。目前按 root 的对照排查，`DraftingExtractionHotfixIntegrationTest` 的两处 errors、`DraftingTradeConflictIntegrationTest` 的一处 failure 在保存的旧 baseline service 中也可重现；它们仍是现存问题，不能隐藏，但不能直接算为本次新回归。`DraftingMultilineBodyIntegrationTest` 五处首次失败缺少明确真实 2a sourceDir，后续相关 gate 已带真实路径通过；`DraftingNativeLayoutIntegrationTest` 七处依赖桌面 x2t/sourceDir 的问题与此次 harness 变更没有已证实的因果关系，仍未通过该环境下验收。baseline 对照证据在 `.local/state/harness-hardening-20261008/baseline-known-failures.log`。历史 `.19` 的 12 类相关 gate 为 **146 tests / 0 failures / 0 errors / 1 skipped**，日志 `related-gate.log`；39 项 package 日志为 `package-final.log`。当前 `.20` 的相关 gate 为上述 164 项。不能把这些相关 gate 通过改写成全部服务测试通过。

快速复验命令（项目根目录 PowerShell）：

```powershell
& .local/tools/powershell/pwsh.exe -NoProfile -File scripts/test-drafting-harness-regression.ps1
```

runner 的 `testClassNames` 数组统一控制 Maven test 选择、报告收集、期望套数和收据。缺报告、陈旧报告、0 用例、failure/error 或 skipped 不会被当作成功。它只执行 `test`，没有 package、服务重启或应用写入。

## 真实考试与评分口径

### 当前 `.20` 全资料前端考试：61/61 覆盖，65 PASS / 14 REVIEW / 0 FAIL

当前部署的 `.20` 已由内置浏览器前端在全新项目 `drafting-exam-20261008-175206-5f89290f` 点击「识别输入」触发并完成，资料仍为相同 15 份 DOCX 加 NTT/SCT/SCC 三份 2a 模板，运行目录 `.local/state/drafting-exam/harness-formal-bill-20261008`。模型、runtime、参考答案和上传范围不变。run 为 `f41b5531-f0d0-4565-9191-ee2466df802c`，trace 为 completed、完整版本与部署一致；北京时间 2026-10-08 17:56:20 开始，18:52:19 完成，耗时 **3,359.490011 秒（约 56 分钟）**。此分数来自本轮保存结果，不移用 `.19` 历史成绩。

正式 score 为 `scoring-final-20/drafting-score.json`，结果为 **61/61 正确且 grounded 候选、18/18 正确留空、65 PASS / 14 REVIEW / 0 FAIL**；四项刻意缺失 **4/4 PASS**，`globalFailures=[]`。semantic 字面诊断支持 **47/61**；错误 authoritative 候选、历史/其他源冲突候选、无出处候选均为 **0**。总 verdict 为 **REVIEW**：61/61 是正确候选覆盖率，不能写成 79 PASS 或完整合同验收通过。

Bill 本轮只有一个完整候选，显示建议值为全部 **13 行**，number/type/正式 description 全部正确，`correctFullCandidate=true`、`exactTextFidelity=true`、groundingChecks 全部通过，Bill outcome 为 **PASS**，没有 optional metadata warning。Bill 12 保留 `Site Safety; Environmental Management and Other Sundry Requirements (All Provisional)`；不再另造一行 `All Provisional`。防水是一个完整 **18 项** text 候选，PASS，17、18 与全部编号正文都保留。恢复 audit `.local/state/harness-hardening-20261008/recovery-audit-final-20/recovery-audit.json` 为 **1 PASS / 0 FAIL / 0 REVIEW**，唯一恢复为防水，rawValue 未改写，normalizedValue 为实际 supplied context 内的原文连续子串。

只读收集脚本是 `scripts/observe-drafting-browser-exam.py`，接收 `--run-dir` 和 `--expected-harness-version`，只用 GET 检查 fresh 项目、等待持久化 completed/failed trace、读回 variables/trace/plan/progress 并保存 runtime/GPU/日志区间。它不触发识别、不 POST、不向模型发送第二轮请求；实际识别触发来自浏览器。expected-harness-version 需要与 trace 的完整身份一致，包括 joint-evidence 标记，避免旧版本或历史运行冒充当前验收。旧 `.local/state/harness-hardening-20261008/observe-browser-exam.py` 是 `.19` 历史脚本，不复用到 `.20`。

本轮收集器使用完整 expected version；运行过程中恢复只读观察所用命令如下。`--resume-observation` 保留首次 `runtime-before`、engine 日志 offset 和 `observationStartedAt`，不会重启识别，也不会改写起始证据。本轮已经完成，无需再启动收集器。

```powershell
python scripts/observe-drafting-browser-exam.py `
  --run-dir .local/state/drafting-exam/harness-formal-bill-20261008 `
  --expected-harness-version draft-extraction-20261008.20-formal-bill-identities+joint-evidence-20261007.2 `
  --resume-observation
```

#### 14 项 REVIEW 的逐项边界

14 项均正确取值且有上传来源，全部 `failures=[]`，唯一 warning 为 `correct_value_and_literal_source_found_but_semantic_evidence_requires_human_review`。类型为 9 个 boolean、1 个 number、2 个 choice、2 个明确空列表。它们都已显示为 `suggested`，`reviewRequired=false`、`confirmed=false`、`manuallyEdited=false`；这里的离线 REVIEW 是 scorer 的语义证据诊断，不能解释成前端缺值、冲突、pending 数量或模型未回答。

| 输入 | 本轮建议值 | 引用与复核重点 |
|---|---|---|
| `foundationIncluded` | false | SIM14 明确「For this test scenario only…not a combined contract with foundation works」；仅本测试场景的采用 |
| `periodAtLeast39Months` | false | SIM03/07/08/15 四个一致候选；31-month ceiling 与 >=39 threshold 分开，不把 ceiling 当已授予合同的精确工期 |
| `contractPeriodMonths` | 31 | SIM14 明确「For this test draft only, use a period of 31 months」，SIM15 再确认；测试草稿采用，不是已授予合同事实 |
| `subcontractArrangement` | BSSSC | SIM05 的明确 select 决定，另五个一致候选；应用标签映射不能授权整条 NTT13 删除 |
| `twoEnvelopeTendering` | true | SIM06 明确两封套及「Record…Yes」，SIM08/14 一致；Envelope 1a/1b/1c 是 subdivisions |
| `electronicTendering` | L10Pro | SIM06 明确 preparation/pricing 选 L10Pro，SIM08/15 一致；不得推成 portal-only tender return |
| `contractorDesignedFoundations` | false | SIM14 明确 Contractor does not design the foundations；不同于专项组件设计义务 |
| `foundationDesignSubmittedWithTender` | false | **实际候选 quote 只有 `No`**；SIM14 第 36 native 段确有完整 no-foundation-design-with-Tender 句子，但该短引用本身缺字段绑定，需要前端打开上下文确认 |
| `railwayProtectionAreaWorks` | false | SIM14 明确 no part of Works is within MTRCL Railway Protection Area；场景采用，不能由 Not used 推断 |
| `oldValuableTrees` | [] | SIM14 明确 adopted list is explicitly empty；明确空与未知树表分开 |
| `allBqQuantitiesProvisional` | false | SIM14 明确 not all BQ quantities are provisional；Bill 12/13 名称不能替代全局判断 |
| `additionalSubmissions` | [] | SIM14 明确额外 project-specific general submission list 为空；标准保留要求和 Appendix H 不随之删除 |
| `refugeFloorInstallationIncluded` | false | SIM14 明确 no refuge-floor installation is included；从属 warranty 问题 inactive |
| `buildingDemolitionSitesSeparated` | false | SIM14 明确不形成 SCC8.303 所需 separate/detached arrangement；不新增 demolition-only Section |

除上述 `No` 的真实弱引用外，其余 13 项都有明确业务句子或字段表格出处。scorer 要求引用含完整 curated reference statement，boolean/短 choice/空列表也不能只凭值字符串证明语义；contractPeriodMonths 又特意排除只见 31/ceiling 就判定已采用精确工期。因此 13 项仍保守 REVIEW，并不等于它们的业务值错误。这不免除人工核对场景范围、否定和条款作用；也不把 `foundationDesignSubmittedWithTender` 短引用的不足伪装成纯机器误报。

#### 两项精确字面差异

`businessValueEqualButLiteralTextDiffers` 只有 `nscAlternativeText` 和 `otherWaterproofingSpecificationAreas`。二者 outcome 都是 PASS，但 `exactTextFidelity=false` 应保留，不可声称逐字完全一致。

- `nscAlternativeText`：参考和实际均 687 个字符；唯一差异在零基 offset 12，参考/DOCX native 第 60 段/trace 原文的 `tenderer’s` 使用 **U+2019**，模型 value 与 quote 的 `tenderer's` 使用 **U+0027**。单独折叠这一个撇号后严格相等；去空白则仍不等。没有缺失 GCC/SCC、CIC registration 或 works-contractor 义务，但不能把规范化 grounding 通过写成精确原文子串通过。
- `otherWaterproofingSpecificationAreas`：参考 1,855 字符，normalizedValue 1,869 字符。实际换行从 17 增至 34，空格从 248 减至 245；逐字符 diff 全为 whitespace，移除 whitespace 后严格相等，**normalizedValue 与参考之间没有编号标点或正文差异**。原模型 rawValue 中 `7Internal tankings;` 缺 `7.` 的点，受来源约束恢复成原文 `7.Internal tankings;`；此 marker 恢复另有 audit 留痕，不能与 normalizedValue 对参考的空白差异混为一谈。

#### 本轮时间与 token 速率

`engine-metrics.json` 的观察区间记录 **35 次 engine call、101,657 个 generated tokens、0 次 output cap reached**；trace 同时记录 23 个 core parts、35 次 attempt。generation 合计 **3,291.73067 秒**，prompt evaluation 合计 **57.47779 秒**，由合计 token/合计 generation 得到 **30.88254 token/s**；按整个 3,359.490011 秒识别墙钟时间计为 **30.25965 token/s**。generated tokens 包括 thinking 与最终回答，不能解释成 101,657 个可见结构化答案 token。

这轮时间主要消耗在大量逐 token 生成，generation 约占识别墙钟时间的 98%，不是 64K 上下文或 16K 输出预算每轮都被用满。engine metrics 的原始 scope 是该时间区间内的所有 calls，可能包含其他客户端；它与 trace 的 35 attempts 数量一致，但日志没有建立每次 call 的独立业务请求身份。`outputCapReachedCalls=0` 表示日志未见输出预算触顶，不等同于已经获得 API `finish_reason`；普通 Drafting adapter 只保留 content，metrics 中对此也有边界说明。

### 保留 `.19` 首次实测

首次加固版本的真实考试已由内置浏览器前端「识别输入」按钮于北京时间 2026-10-08 16:20:23 触发，17:17:30 完成。独立新项目 `drafting-exam-20261008-161048-75a69374`，run `c5635b58-f36d-4dea-b4cb-4cd00bd5d763`，按默认 15 份 DOCX、3 份 2a 模板运行。`.local/state/harness-hardening-20261008/observe-browser-exam.py` 只发 GET 收集结果，不发起第二次识别或模型请求；正式目录是 `.local/state/drafting-exam/harness-hardened-20261008`。

首次真实结果为 **61/61 正确且 grounded 候选、18/18 正确留空、64 PASS / 14 REVIEW / 1 FAIL**，耗时 **3,426.838915 秒**。正式评分在 `scoring-harness-hardening-v1/drafting-score.json`，保持原始结果。semantic 字面支持为 47/61；错误 authoritative 候选 0、其他源冲突 1、无出处候选 0。这轮并非全通过：完整 13 行 Bill 候选存在，但 SIM10 的 `[{"number":"12","description":"All Provisional"}]` 被误当成另一正式 Bill identity 接收，显示清单因此含 14 行，Bill ledger 以 `displayed_suggestion_value_incorrect` 判 FAIL。`All Provisional` 是限定语，不能替换 SIM04 的正式长描述，也不能新增父行。新独立 captured 回归保存了这一真实错误和完整 13 表正例；`.20` 已修相应 intake/merge 边界并部署，新真实复验为上节 65 PASS / 14 REVIEW / 0 FAIL，不追改首次分数。

防水已由单个完整 18 项候选通过本轮业务和出处评分，`exactTextFidelity=false` 的差异经独立比较确认只在空白/换行，去空白后与参考字面完全相同。其实际 normalizedValue 连续且精确存在于原文中；此次恢复保留模型 rawValue，仅恢复编号 marker 与来源引用。专项恢复 audit 为 1 个 accepted 恢复决策 **PASS**，但它不证明所有合同语义，也不将 Bill 的独立 FAIL 抹掉。

旧 medium4k 是已保存的历史对照：

| 统计口径 | 历史结果 | 证据字段 |
|---|---|---|
| 需要取值的输入 | 61 项中 60 项正确且有出处 | `summary.correctGroundedValueInputsRecognized=60`，`requiredValueInputs=61` |
| 应留空输入 | 18/18 | `summary.correctlyUnfilledInputs=18`，`nullStateInputs=18` |
| 79 项自动逐项判定 | 55 PASS / 23 REVIEW / 1 FAIL | `summary.pass/review/fail` |
| 四项刻意缺失 | 4/4 PASS | `summary.withheldPass=4` |
| 字面 semantic 诊断支持 | 37/61 | `summary.valueInputsWithLiteralSemanticSupport=37` |
| 错误/其他源冲突/无出处候选 | 0 / 0 / 0 | `wrongLatestCandidates`、`historicalOrOtherSourceConflictCandidates`、`ungroundedCandidates` |
| 耗时 | 3,910.937 秒 | score 顶层 `elapsedSeconds` |

历史 score 位于 `.local/state/drafting-exam/campaign-medium4k-20261008/medium4k/scoring/drafting-score.json`，项目 `drafting-exam-20261008-101233-df46e254`，run `339ac1ae-eca6-48d1-b805-517fa4768aa5`。本轮不修改或覆盖该报告。

历史 Bill 行为 PASS，`correctFullCandidate=true`、`grounded=true`、`exactTextFidelity=true`，实际包含全部 13 个 number/type/formal-description 记录。历史防水为唯一 FAIL，`candidateCount=0`、`correct_grounded_candidate_not_recognized`；不是把 16 项算成 18 项后再判失败。

`scripts/validate-drafting-fixture.py` 的两个分数层次必须分别报告：

1. **正确且 grounded 的候选覆盖率（61 项）**：候选业务值等于参考值，并通过上传来源身份、当前 parsed hash、trace 中原文引用、原始 DOCX 投影引用、上传字节 hash 校验。它可把同一结构列表的正确片段联合覆盖算作识别成功，但防水字段是 text，不能用 1–16 与 12–18 的文本候选自动拼成完整答案。Bill 所有 13 个 number/type/description、记录数量和重复性都要相符；只忽略生成 id、无害行顺序和另行报告的可选用途 metadata。
2. **79 项 PASS/REVIEW/FAIL**：在候选正确及来源核验外，还检查显示建议值、无自动采用、validation issue、额外错误候选和字面 semantic 诊断。有 warnings 而无 failures 的行是 REVIEW；例如值和出处正确，但引用仍需人为确认 semantic，或 Bill 可选 metadata 未经附加 oracle 验证。正确覆盖 61/61 不等于 79 PASS，也不代表正式合同已验收。

全局边界还要求资料恰好来自 15 份上传 DOCX、manifest 字节身份一致、trace completed/not stale、evidenceRevision 与计划一致、79 行齐全、catalogue ruleVersion 一致，以及所有候选保持 `confirmed=false`、`manuallyEdited=false`，不进入 `plan.effectiveValues`。任何全局失败都令总 verdict 为 FAIL，即使单行统计看起来良好。

评分器自身的 exit code：0 为 PASS，1 为 FAIL，2 为输入不完整/无效，3 为 REVIEW。已核读当前 `grade` 的映射和 `main` 的 `raise SystemExit(code)`，本轮 JSON verdict 为 REVIEW，所以 Python 进程本身应返回 3。本次外层 PowerShell/tool 显示 exit 1，是没有显式 `exit $LASTEXITCODE` 的包装层将非零 native exit 映成 1；它不能反推 score verdict 为 FAIL。应以持久化 JSON 的 verdict、globalFailures 和逐项 ledger 为业务判断，包装命令要用 `exit $LASTEXITCODE` 才能原样转交数值。不能把 exit 3 写成「全部通过」，也不能把缺少真实结果文件的 exit 2 解释成模型失败。没有为改包装退出码而重跑或覆盖正式 score。

### 可执行只读评分

本轮真实评分已经保存在 `$runDir/scoring-final-20/`。以下为另建复核目录的评分示例；不覆盖已保存正式或历史评分。评分器只读取参考答案，不做 HTTP、上传、模型请求、采用或生成。

```powershell
$runDir = '.local/state/drafting-exam/harness-formal-bill-20261008'
python scripts/validate-drafting-fixture.py `
  --fixture Ref/simulated_2b_correspondence/03_missing_evidence_test `
  --actual "$runDir/variables.json" `
  --trace "$runDir/trace.json" `
  --plan "$runDir/plan.json" `
  --stage SIM15 `
  --out-dir "$runDir/scoring-formal-bill-v20"
```

该命令还需要同一 `$runDir` 下由真实运行器保存的 `catalog.json`、`inputs.json`、`manifest.json`。不能只拿变量 JSON 单独构造一个更高的分数。

### 两处专项复核

Bill 的 ledger 必须同时核验 `recognizedCorrectGroundedAnswer`、`correctFullCandidate`、`exactTextFidelity`、全部 `groundingChecks` 和显示建议值。自动 core PASS 仍不能替代 Bill 分发/用途图验收；`billExtraMetadata` 一旦非空，会产生独立 REVIEW warning。

防水的 ledger 应是完整 text 候选，与 18 项实际来源在 canonical 和独立 `exactTextFidelity` 两层比较。再核对首项至末项的实际编号序列、17 和 18 的正文，不能只数字符串里有多少数字或见到 catch-all item 18 就宣称完整。

如果出现恢复 diagnostic，需要从 `trace.decisions` 的 accepted 行定位 `partId/attemptIndex/itemIndex`，同时读取对应 attempt 的原始 `rawResponse`。核验：原 `rawValue` 没有被改成参考答案；决策 `sourceQuote` 连续存在于**该 attempt 的 context.sourceText**；该 context 与原文偏移一致；恢复前后只发生批准的 marker/引用差异；候选的 normalizedValue 与实际来源相符。自动评分器验证了有效引用，但不单独审计恢复前的模型引用，这项比较需要本轮专项留痕。

首次 `.19` 运行、评分和恢复 audit 保留上述真实结果；`.20` 已部署并完成完整前端识别复验。识别覆盖与建议显示通过本轮核验，14 项语义复核、用户人工采用、文稿生成及附加业务验收仍须分别完成。

### 只读恢复 audit

`scripts/audit-drafting-harness-recovery.py` 仅读取保存的本地 trace，输出到新的目录；不读取参考答案，不做 HTTP、POST、模型调用或应用状态写入。它用原始 rawResponse 的协议解析定位 item，检查保留 rawValue、Java UTF-16 原始偏移对应的实际 context、有效连续引用、编号身份及逐项正文，Bill 恢复另外核验实际 pipe 表完整身份行与 retained type 所在行。未知 shape 返回 REVIEW；未发生恢复时报告 `NO_RECOVERY_OBSERVED`（exit 3），避免零用例被当成修复通过。

```powershell
python scripts/audit-drafting-harness-recovery.py `
  --trace .local/state/drafting-exam/harness-hardened-20261008/trace.json `
  --out-dir .local/state/harness-hardening-20261008/recovery-audit-new-review
python -m unittest discover -s scripts/tests -p test_audit_drafting_harness_recovery.py -v
```

首次真实 audit 的最终 JSON/Markdown 已保存于 `.local/state/harness-hardening-20261008/recovery-audit-first-live-final/`，绑定 trace 与 audit 脚本 SHA-256：1 个 accepted 防水恢复决策 PASS；旧 medium trace 的 smoke 结果为 `NO_RECOVERY_OBSERVED`，保存于 `recovery-audit-legacy-smoke/`。7 个 integrity 用例全部通过，涵盖改写 rawValue、越界引用、篡改 offsets、改词/缺项、无恢复、JSON 包装、Unicode UTF-16 偏移。audit 只检查含恢复 diagnostics 的 accepted 决策，**不审计未恢复候选、DOCX 字节身份、采用状态或完整 contractual semantic entailment**；这些边界在 JSON 中机器可见，不能用它替代正式考题 scorer。

## 合并前检查

合并审查基线为 `b4c7339e1871fc7eba92b32edd2b76cd52d734e8`。实现约定及需求符合性分别静态审查；当前 service source 与完成 164 项相关 gate 的 `.20` 源码镜像逐文件 SHA-256 相同，本机 active JAR 与该 gate 收据一致。额外前端检查覆盖 8 个相关 Node 测试文件，均通过；`npm run build` 的 TypeScript 与 Vite 检查通过。前端证据保存在 `.local/state/harness-hardening-20261008/premerge-frontend/`。仓库截至检查时没有配置 GitHub Actions，本地结果不代表远端 CI 执行过。

审查发现模型 benchmark 的停止流程缺少空闲 gate，已修复为：首次暂停 watcher 前核验完整任务身份、三个 owned 服务就绪及有效 boolean `/slots` 状态；每次停止和恢复前再次检查。busy、未知状态或请求失败不能作为空闲。首次 preflight 拒绝不触发清理；实验已经改变配置但模型仍忙时保存 deferred restoration 与原字节备份，保持正在运行的请求，不强行停止恢复。

`scripts/tests/test-benchmark-idle-guard.ps1` 用隔离副本与 stub 依赖执行实际 benchmark 控制流，12 个用例通过，覆盖 busy、缺失/非 boolean/空/不可用 slots、未就绪 backend、服务及任务身份不符、idle 成功并原字节恢复、runner 失败时 busy/未知导致延后恢复，以及下一 profile 的 busy 检查。测试没有调用真实 API、模型、Windows 任务或进程停止入口。该 12 项是脚本保护验证，独立于 Java 164 项和真实考题评分。

代码提交排除 `.local/` 的 runtime、权重、工具、数据库及运行证据，也排除 `Ref/` 的完整外部原始资料。仓库内只保留专项回放需要的已审阅小型 source/model-output fixtures 与测试代码。新的 checkout 需按 `LOCAL-DEPLOYMENT.md` 自行准备本机依赖和考试输入；文中的本机证据路径不会随 Git 提交。
