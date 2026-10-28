# Drafting 分组、并发与前端反馈验证（2026-10-28）

## 最新部署检查点（2026-10-29 03:34上海时间）

build30已实际启动：Java50084／18204、exec52195，03:33:53本地日志确认启动完成；JAR SHA256 `954F46586C91A324B13F96ED58EFEAAFCB55180F0450CFEF8AA03658C38D54B6`。从冻结build29只覆盖TemplateGraphService、AutomaticDiscovery、DiscoveryReferences、BusinessRules四个生产文件，定义路由仍保留旧版。核对旧Java8336命令与build29 JAR后经原exec19891中断退出，原DB、uploads、home/temp、8GiB及模型配置保持；前端frontend17 Node39228／5185不变。启动/进程/JAR回执在本机`build30-replacement.json`，未操作其他模块服务。内置浏览器已刷新，当前待重新读回并验证旧run续跑；尚不能报告续跑兼容、修复后模型效果或新版合并页面验收通过。

与build30生产范围一致、不含新domestic路由的独立树`5358623e5973292452175133c8ac6734a32b70c1`从空target编译，51套件560项中558通过、2实际源skip，无失败/错误；日志在`.local/source-sync-build30-20261029-033030/backend-regression.log`。该门与下方52套件564项高度重叠，不相加；其ContinuationIsolation 3、SourceSelectionIsolation 12、GroupScopeDuty 12、BusinessRules 16、VO/fixed 9均实际执行。打包跳过测试，直接Java Maven launcher成功24.824秒；初次mvn.cmd因D路径中的&转义失败，保留build.log，重试成功在build-retry.log。软件门、构建、运行启动与业务验收分别记录。当前仍没有通信、项目值采用或三份Word，goal为ACTIVE；下文各检查点保留其历史时间。

## 运行与软件门更新（2026-10-29 03:26上海时间）

前端已受控替换为frontend17，Node39228／5185、exec14918；核验旧frontend16 Node46056身份后经原exec29273中断退出，其余服务未操作。四个生产前端文件及四个测试来自冻结清单，实际回执在本机`frontend17-replacement.json`。内置浏览器热重载后重新打开图谱，已保存13项定义仍可回读；新版合并行为尚未在页面重新验收，不能把此前旧界面的人工合并当成新修复通过。后端仍是Java8336/build29，0模型在途。

最终统一源码树`644f376109fe0b6d8ea2f908f22d63bbc15a364b`从不存在的全新target开始，实际编译331个生产源及644个测试源，通过52套件：564项中562通过、2项因缺真实比赛sourceDir跳过，无失败/错误。前端20个定向测试文件、typecheck和build通过。日志位于本机`.local/source-sync-final-empty-20261029-032707/`。此前overlay门因mtime导致新Auto源配旧class的两失败记录保留，不算新生产代码失败或模型失败；最终class身份日志确认新Auto/References包含scopeRepairProtocol。软件门不能代替真实原件、模型或业务验收，两项Bill/edition原件跳过仍未通过。

为继续旧任务20个未派发组，下一运行候选build30仅覆盖VO、Auto/References及SCT4父前提修复，暂保留build29的定义路由，避免新增domestic owner和重分组破坏旧计划或重发17次调用。新增domestic路由已在上述源码门验证，尚不纳入当前旧run运行包；计划另批部署与前端实际模型验证，不将其源码测试称作自动准确率改善。全部失败、回执及未完成状态保留，goal继续ACTIVE。下方frontend16及首次早停记录按各段操作时的历史状态读取。

## 最新检查点：消防人工纠正及增量续跑通过页面执行；SCT 第二次同协议错误早停（2026-10-29）

运行仍为 build29/frontend16。本轮通过内置浏览器完成消防选择的准确画词、两个互斥 Replace、重复输入归并、保存、刷新回读及仅该定义的组确认。两个模型节点的当前来源、observation、discoverySourceKey及稳定值均相同，但local ID及标签不同；人工保留fire_scope为主，将旧n1归入历史。PDF实际选区为`*SCC20.2/ *SCC20.3.`，两条件分别替换为`SCC20.2.`或`SCC20.3.`，保留句点和后续正文。旧界面因标签差异初次拒绝合并，统一等价标签后才成功。已确认定义从12增至13，其余确认保留；没有采用项目答案。步骤172—180及截图135—144在本机图文操作记录，不能把人工纠正写成自动正确率。

UTC `2026-10-28T19:07:32.142Z` 实际点击原run `448e2ab2-28ac-4b7a-96d1-abb50c67cfd9` 的“继续尚未派发的分组”，不是全量重跑。任务从30/88、7/30推进至37/88、10/30后再次PARTIAL，0在途，20组未派发。新增三组各2次实际模型调用，累计10组17调用；89要求、54编译节点、35待核均不代表正确率。lastContinuation从`2026-10-28T19:07:39.873748400Z`至`19:09:16.118025600Z`，新resultRevision为`7baec3ba-325c-46ed-abc5-81c4fdf24081`。新增停止组为`group-2ab7e0f4a80fb57b8bf83837`，探针时间`19:08:30.349038Z`；明确不是旧firstProbeFailure被误重放。新前端下载诊断封存`downloads/build29-sct-continued-stopped-diagnostics.json`，SHA256 `B59FB03C885590AB4B433F5F50CFDBD3392FAC998FAB206A485544BD37960F5B`，截图145—147。

重复首因已归为两个窄harness接缝：SCT4当前原生指导明确询问domestic blocks，但固定定义路由只登记SCT6的同业务指导，因此该组没有收到已存在domesticBlocks定义；模型又建本地输入。随后scope_disposition_repair对known与local两条合法输出路径缺少针对性指引，模型重复把local输入放进只允许supplied known root的scopeRules，编译器正确拒绝。定义路由和补修提示正在修复并准备定向软件回归，尚未部署或真实模型重验。SCT的预制墙付款许可与NTT的预制墙投标基础是不同事实，不因主题相近强合并。未再派发剩余20组。

并行小修包括合并保留主项未变的固定对应、清除过期定义确认、服务端只读当前性/准备资格、两变量适用条件不一致时阻挡，以及外部适用条件引用迁移后明确回到待核。准备资格只表达定义前提，完整来源/依赖仍在显式确认时校验；它不是完整标准树或原件证明通过。合成测试、联合软件门和真实页面验收分开报告，部署前不以这些源码替代业务通过。本轮仍没有上传通信、提取/采用项目值或生成三份Word，goal保持ACTIVE。下方build29首次早停及更早记录均按其历史时间读取。

## 当前检查点：build29 已前端实跑并在新原子变量的范围协议错误处早停（2026-10-29）

build29 已替换本任务的 build28 后端，前端仍为此前 frontend16/5185。替换回执为 `.local/drafting-harness-main-20261028-v1/template-variation-probe/build29-replacement.json`：UTC `2026-10-28T18:24:42.0697014Z`，核验旧 Java45820/build28 身份后替换，沿用原 DB/uploads/home/temp 和 8GiB 堆；新 JAR SHA256 为 `611B1D31ABD56D909E7CA8A951F4A99DB7297CDF370D85EFF019FE47DCFE03B2`。回执没有新 PID、exec ID 或 package 耗时，本文不补写这些信息。生产覆盖仅为 `DraftReviewDiscovery.java`、`DraftAutomaticDiscovery.java`、`DraftDiscoveryGroups.java`、`DraftDiscoveryEvidence.java` 四文件，最终 SHA 清单在 `.local/drafting-harness-main-20261028-v1/build29/source-overlay-final.json`。

本批软件门实际执行395项，393通过、2跳过，日志 `.local/source-sync-anchor-regate-20261029-022001/combined-regression.log`。两项跳过需要真实 sourceDir 的原件用例，因此不能写成真实 SCT 的14个插入点已通过。旧编译／测试失败及各轮门均保留，重叠门不相加；这些是软件验证，不是模型正确率或端到端业务通过。合并后的定义确认资格及固定对应恢复前端补丁仍待测试部署，当前旧界面上的人工绕行成功不能作为新补丁验收。

实际业务操作继续走原 v23 项目的内置浏览器。页面重新仅勾选 SCT24，NTT23、SCC25 不选，点击新识别按钮后产生 run `448e2ab2-28ac-4b7a-96d1-abb50c67cfd9`；没有继续旧 run。诊断冻结 selectedSourceIds 为 `[24]`、protectedSourceIds 为 `[23,24,25]`，模型为 `qwen3.8-27b`，profile `qwen`，provider `openai`（兼容配置），配置 SHA256 `41e37dfb2f09f795d7d9e0fea30ab9110e20b973023800f706c533bb2bd26340`，matchesCurrentConfiguration=true。createdAt `2026-10-28T18:26:06.418758Z`、updatedAt `2026-10-28T18:30:11.538097Z` 是服务运行记录跨度，不是点击时刻或纯模型耗时。

终态是 PARTIAL／READY_FOR_REVIEW：30/88来源事项、7/30组，真实模型调用合计11次，剩余23组未派发，未点击“继续尚未派发的分组”。failedItems=0、unresolvedItems=28、requirementsCount=80、nodesCount=51；这些数量不代表正确率。首停组 `group-9928bd91ca7e970e23468bde` 在 `2026-10-28T18:28:54.689885600Z` 触发 `SCOPE_RULE_KNOWN_VALUE_REFERENCE_REQUIRED`。本次 resultRevision 为 `a66e0cd3-1997-4474-b9a3-9c471e65fd3e`，输入底稿 expectedDraftRevision 为 `906ac757-a2e6-4754-9bff-aa984a12316c`。

| 实际结束组 | 模型调用 | 补证轮 |
|---|---:|---:|
| group-42027cbeec2ade43a9656e70 | 2 | 1 |
| group-0327020e86f2ef86a2a424aa | 1 | 0 |
| group-45526a19c0e07e60f7b38d26 | 1 | 0 |
| group-3df8e3d8489d41bd69e9ca67 | 1 | 0 |
| group-4f9e13abc180ad3a5886ec52 | 2 | 1 |
| group-9928bd91ca7e970e23468bde | 2 | 1 |
| group-7e32b2bde4716dac42289ef5 | 2 | 1 |

同源固定定义路径有局部实证：BQ `/items/88` 的输入实际收到 `electronicTendering` 及其完整值域，原始回复没有另建 BQ 业务变量，四条 L10Pro/Hardcopy 的 keep/delete 条件仍保留；该组2次调用、1轮补证。这个结果支持减少早期无关同源根污染和重复业务定义，不能把四条条件误写成本轮相较 build28 新增的成果，也不代表当前范围已完成原生绑定或可执行。

Bill `/items/89` 两处指导均引用 `billNos`，14个空字段仍返回 check；实际1次调用、0补证，没有请求宿主正文。build29 已交付合法空插入点的 `fieldLabel/insertionPoint/memberId` 导航及按需 scope/member 取证路径：scope 请求仍可保留空 text、scopeComplete=false 与合法零长 ranges，宿主全文须显式请求 member；插入接缝共用 singleInsertionAnchor guard，拒绝混合 memberRanges 或错误单位。但本轮模型没有使用新增按需取证链，不能声称真实模型已验证14点插入或该工具链已通过。

本轮首停已定位为新的模型协议错误，未放宽编译器：`/items/93` 的当前指导为“Select either SCC20.2 for fire services installation, or SCC20.3 for fire services and water pump installation”，输入 knownRoots、fixedContext.fields、fixedRootRouteCandidates、restoredDefinitionReferences 均空；fixedDefinitionCandidates 仅提供词汇导航。模型首答新建合法待核原子输入 `fire_scope`，但没有范围处理；scope_disposition_repair 的第二次调用又生成 `sr_t1_s1_check`、`sr_t2_s1_check`、`sr_t3_s1_check`，三条均在 scopeRules.when.clauses 中引用本地 `fire_scope`，operator=unknown，未提供 valueSourceId。实际提示词明确 scopeRules 的 inputId/valueSourceId 只能使用 supplied knownRoots aliases，新 local facts 应走原子 branches/effects。因此 `ScopeRuleDraft.requireKnownValue` 拒绝这些规则是正确守卫，不能归因于 owner-filter 丢根或通过放行任意 local ID 解决。合法原子输入及其 requirement 仍在 `/items/13/receipt/nodes/0`，internal ID `discovery-group-1d6bec798ea964e1-fire_scope`，pending、editTargets为空，两个 marker 子项52/53及未决状态保留。根线程正在通过前端人工校正，后续从该分叉单独记录，本检查点不提前宣告通过。

原始诊断通过页面“准备本次运行诊断→下载诊断 JSON”实际下载并封存于 `.local/drafting-harness-main-20261028-v1/template-variation-probe/downloads/build29-sct-stopped-diagnostics.json`，SHA256 `4271BA11839C44D38004B70A4B9BF2FF8AD1D1AB003DA5608FA4A9EEE9E7B9F2`。操作记录166—171及截图131—134保留选择、运行、自动早停、下载；没有产品 API／数据库旁路。本次尚未上传项目通信、提取或采用项目值、生成三份 Word，goal 继续 ACTIVE。以下 build28 及更早检查点均按历史记录读取。

## 当前检查点：build28 软件门通过，SCT 前端新探针因重复业务定义再次早停（2026-10-29）

build28 新任务 `b242fe17-4f3b-4559-a293-9ae285067212` 已通过内置浏览器实际运行至 23/88 来源事项、6/30 组并自动 PARTIAL，处理中 0，未继续派发剩余 24 组。停止原因为 `supplied_business_definition_duplicated` 和 `node_without_supported_requirement_mapping`；6 个已返回组各实际调用 1 次，无补证轮。原始诊断已通过页面“准备本次运行诊断→下载诊断 JSON”实际下载，封存 `.local/drafting-harness-main-20261028-v1/template-variation-probe/downloads/build28-sct-stopped-diagnostics.json`。结果 revision 为 `d2a4076c-2c20-47a4-8b6e-dff1f1475515`，createdAt/updatedAt 为 `2026-10-28T17:41:22.067485Z` / `2026-10-28T17:43:06.868753Z`；这是服务任务记录，不等于纯模型耗时。48 requirements、27 compiled nodes 不是正确率。Bill 原始回复已明确引用 `billNos`，但其具体范围仍为 check；BQ 条件及首停重复节点正在按原始输入/回复调查，尚不能声称整体质量提高。截图119—121保留运行、早停及下载；未上传通信、未采用项目值或生成 Word。

build28 的最终定向软件门在全新 target 强制编译生产及 637 个测试源后，通过 32 套件、279 项，0 失败／错误／跳过，日志 `.local/source-sync-drafting-final-20261029-0138/full-targeted-regression.log`。首次编译泛型错误、272 项中的 10 个失败以及原基线复跑的 3 个旧 Mockito 入口失败均保留：新 fixture 补齐真实 guidance memberRanges，不放宽原生 guard；旧续跑 fixture 改为实际五参入口，预算、未知不重放及保存断言保持；同源只读引用改为显式当前 guidance/scope 关联，旧非空、只读、主 owner 权限及 foreign 隔离断言保留，新六例同时证明无关联、陈旧和伪造引用不获权。279 与此前定向门重叠，不相加，不代表全仓或业务通过。

只读关联仍要求 reader 自身完整原生 guidance anchor，且其本次供应的 relatedGuidance 三元组与实际根证明一致，owners、taskQuotes 和 patch 权限不增加。非 guidance 的 marker/field reader 本轮没有新增支持；现有只比文字的引用 helper 不足以代替原生范围验证，未用它绕过 guard。独立只读审查未发现阻断性权限或 NTT 保留问题；所有 guidance 提前 anchor 扫描存在未测量的额外本地成本，尚未证明是实际瓶颈。

build28 仅覆盖冻结 build27 的 7 个 Drafting 实现，最终 package 成功 28.341 秒，打包跳过测试；JAR SHA256 `1405DEF3817CC3932B5C142BF97F0229DE39FF25A5A8944A0D579BF77A84BBDC`。核验旧 Java24584/build27 身份后通过原 exec 中断，新 Java45820/18204、exec1493 启动；前端 frontend16/5185 及原 DB/uploads/home/temp/8GiB 堆保持，其他服务未操作。原 v23 页面在 `2026-10-28T17:41:08.131Z` 仅选 SCT24，实际点击新识别按钮；截图118只证明启动过渡，后续真实结果以119—121及新诊断为准。未续发 build27 的剩余 79 组。

原 v23 项目通过内置浏览器取消 NTT23，仅选择 SCT24／SCC25，于 `2026-10-28T16:48:45.671Z` 启动 Qwen3.8 27B 新任务 `68913833-698a-4a6b-ad42-8c2573d3395d`。冻结 selected 为 24/25、protected 为 23/24/25，计划为 SCT 88 事项/32 组、SCC 144 事项/51 组，NTT 没有本轮任务。任务在 11/232 事项、4/83 组自动 PARTIAL 早停，剩余 79 组未派发，未点击继续。实际 5 次模型调用全部属于 SCT；第 4 个已结束组是派发前阻断的零调用回执，SCC 尚无实际模型调用。

诊断 JSON 由前端实际下载并封存，SHA256 `95026E81994C563527F0449E409D2DBA6A53577F814F6259F12541A0EC4CF535`，本机路径 `.local/drafting-harness-main-20261028-v1/template-variation-probe/downloads/build27-selected-sct-scc-stopped-diagnostics.json`。四组回执及来源核对见同目录 `build27-selected-source-readonly-audit.json`。原始 5 个模型 reply 的 `nodes` 全部为 0；界面的 16 个原子节点来自 harness 编译 9+7 条 check，不能称为模型正确识别 16 个业务变量。26 requirements 同样不是正确率。

本次三个修复接缝如下，软件门已过，真实模型及业务效果仍待验证：

- 两处真实 Bill 任务拥有不同原件位置和 group ID，但短别名归一后 prompt 相同，公共 ledger phase `discover-group` 把第二组误判重发。Service 改为 `discover-<真实groupTaskId>`，同组 no-replay 和未知发送不重试仍保留。Auto 对未收到任何 raw reply 的异常只保存真实首因和未决覆盖，不再用空对象生成假的 `group_task_outcome_missing_or_invalid`。
- Bill 将 scope 的 s1 当 member，DiscD 将 guidance 的 g1 当 scope；原拒绝正确，但空 items 无助于显式纠错。References 提供最多 4 条、每条最多 4 个本 owner 已收到的 `{kind,id短别名}` 选项，Auto 放入下一轮 supplement；不自动改参数、不增加来源权限或调用预算。完整 wire encode 路径只遍历具名来源字段，反馈标签不得二次转换。
- SCT BQ／Bill 指令缺少相关固定定义，反而拿到同源无关根。新增当前原文核对的 definition-only 路由、Bill 轻量列定义及 owner/依赖闭包；有明确当前关联的 guidance 另授只读引用，不回到全源广播。generic a/b 只有本任务当前证据包含 L10Pro 和 hardcopy BQ 语境才给 BQ 根。旧 NTT 已确认根的证明和值保持，当前 SCT usage 只放请求副本。Auto 仅在本组所有业务定义齐备时采用 scope prompt，避免“任意 knownRoots 非空”锁入过窄路径。定义参考不授予历史编辑范围。

实际前端回读保留了 NTT13 完整正文 Replace、BSSSC/NSC 分支及 nscAlternativeText，NTT10 完整 G1/G1a 目标与 all/any 条件，NTT17 标题及完整正文 keep/unused；截图 114—116。当前图谱界面为 28 业务变量、17 处理规则、20 核查、51 未确认；业务定义为 12 已确认、15 待核、1 未决关系。没有完整图谱导出，尚不能据此声称所有 NTT 字段、10 个排除记录及历史覆盖逐字段保持。未上传通信、未采用项目值、本轮三份 Word 尚未生成。

build28 的软件门、独立审查及新前端探针均已实际执行；新探针再次早停，先调查上述重复定义和有效要求映射，不直接续发旧计划。确认 BQ/Bill 的业务身份、条件、各自范围及按需取证后再扩大。旧运行、失败、原始回复和图文记录保留，goal 继续 ACTIVE。以下部署前及更早状态均为历史记录。

## 当前检查点：build27/frontend16 已部署待真实增量验证；12项定义整组确认及刷新通过

2026-10-29继续同一active目标，仍未完成三模板、通信取值及三份Word。本轮通过原v23页面实际完成保证金、铁路规则和重复事项的人工处理，随后暴露并修复新增模板导致的整组定义确认误报。最新图谱为12个业务变量全部定义确认、17项处理规则中2项已确认／15项待核、4项来源核查，故共19项未确认；本项目仍0项取值采用。不能把这些人工处理记作模型自动正确。

实际失败分叉：原NTT的12个业务根均未改定义，上传SCT/SCC后点整组确认，返回 `KNOWN_ROOT_REVIEW_DEFINITION_CHANGED`（twoEnvelopeTendering），保持0项确认。原因是新SCT给共享根增加证明，旧确认器要求NTT根预先保存所有当前来源的证明。build26仅窄修 `DraftKnownRootReview`：保留CORE和 `sourceProofsEqual` 原全部严格字段，对每根及已审前提按其自身保存的sourceReferences重建当前证明，按来源集合缓存；新增来源不能救回旧SHA、分析、观察、锚点或版次变化。没有把SCT的证明、取值或编辑权自动附给旧NTT根。

首次新测试因可变List fixture类型错误未编译，失败日志保留；修fixture后隔离7套件114项实际通过，0失败／错误／跳过，见 `.local/git-delivery-20261028/known-root-cohort-retry.log`。生产class SHA `255956D22ADFBA033B00BBB796E4D811ED11A67CC1AAEAD4A28C6352A2838139`，新测试最终SHA `8C1D604B73688941DA58F842689E27C66DAFE5F9647E4E2A4888F6DD40C35166`。本轮114与前一轮193是不同且有重叠的定向门，不相加，更不能声称全仓测试通过。

build26从build25冻结快照只覆盖该class，package成功27.648秒，跳过测试打包；JAR SHA `4787B52FFBF2228BD6F8F4CE41AC31434602F77FA32CBC1A20C8DE4863D18773`。确认旧Java2624/build25身份后经原exec中断，新Java48872/18204、exec86445启动；frontend15 Node8044/5185、exec25246不变，沿用原数据库、上传、home/temp及8GiB堆。没有操作其他模块服务，也没有把仍在开发的sourceIds混入这次修复运行。

原v23页面沿用同一12项选择、理由及确认勾选，于 `2026-10-28T16:33:10.657Z` 实际重试整组确认。前端回读12已确认／0待核业务定义／0未决前提关系，并显示已确认部分可用于项目候选提取；随后刷新并重进图谱仍保持。步骤135—141、截图103—108记录失败、实际修复分叉及刷新回读；未采用项目值，未确认整版模板，未执行Word改写。上述时间是点击时刻，之后回读区间不是精确服务耗时。

铁路规则另通过当前第11页画词和“确认改用这整段原文”选择标题、完整正文两个native spans，不含17编号和右侧note，true→keep、false→unused，保存并核对。保证金主规则已核对；两处NTT3(c) marker完整正文的重复核查也排除，四项非双信封真实未决保留。步骤131—137、截图100—106支持这些人工操作；最终Word是否正确保留编号及一次Not used仍待输出验证。

下一候选是显式sourceIds的增量识别：前端使用实际原件ID选择，仅在项目／打开／上传／明确刷新读取轻量来源列表；取消NTT后不因刷新或上传重新勾回，旧请求在途时串行补读且丢弃过时结果，轮询不重读来源。后端冻结所选及底稿依赖，仅读所选来源并保留未选NTT人工图谱和覆盖；本轮scanStatus与全图覆盖资格分开，SCC版次保持明确选择。独立审查补了续跑新head依赖必须属于原冻结保护集合的P2检查，不扩旧集合或重置预算。

增量软件定向组合已实际通过：9套件147项，0失败／错误／跳过，其中新增 `DraftSourceSelectionIsolationTest` 的12个外层用例确实执行，见 `.local/git-delivery-20261028/source-selection-final-retry.log`。原Nested版本未被当前provider发现、显式0项失败，以及两处Mockito覆盖stubbing阶段NPE均保留；最终修复没有删用例或放宽生产守卫。前端两个相关控件测试、typecheck及production build通过，日志 `source-selection-frontend-retry.log` 与 `source-selection-build.log`；早先上传刷新失败是测试window缺计时器，修fixture后仍保留原刷新次数、在途旧响应串行补读及轮询不重读断言。这些147项与此前定向门可能重叠，不能相加，也不代表全仓测试通过。

独立只读审查的唯一P2已复核闭合：resume在项目锁及head校验后、任何预算／manifest／状态／派发变更前，要求新head的来源依赖是原protectedSourceIds的子集；新增依赖明确要求新run，不扩冻结集合、不重发或重置预算。新测试同时保留拒绝后原7次调用回执和创建时间，以及原保护集合内人工编辑可继续的断言。其他来源、覆盖和SCC版次边界未发现阻断问题。

build27从build26冻结快照仅覆盖5个增量后端生产文件，package成功23.234秒，跳过测试打包，JAR SHA `E772501E95250A4A651F5B96588ED019F25026D27359F45D714D1FA14E124DDF`。frontend16从frontend15仅覆盖View、api、automation-types、state四文件，类型检查及build成功13.77秒。运行现为Java24584/18204（exec86773）和Node46056/5185（exec29273），原数据库、上传、home/temp和8GiB堆不变；此前frontend15命令使用相对路径，绝对路径匹配检查没有命中，未按未知PID杀进程，而是终止本任务已持有的exec25246并确认旧Node8044退出，再以绝对入口启动frontend16。其他模块未操作。

目前浏览器已刷新到新前端，准备只选择新增SCT/SCC；尚未派发这轮真实27B，故没有新的模型效果或NTT免重算实证。仍须通过页面实际启动、诊断下载、人工图谱及覆盖保存／刷新检查后，才可报告增量能力通过。最终16文件SHA清单保留D盘 `sourceids-final-manifest.json`；业务目标保持active，三模板准确性、通信提取／采用及三份Word未验收。下方为历史检查点。

## build25 历史检查点：修复取证协议、未决统计和重复修补；完整业务验收仍在进行

新目标已建立并保持 active：沿既有项目和失败证据持续短探针、定位、修复、前端重验，直到完成三模板、通信候选、人工纠正及采用、三份 Word 实际下载和完整图文记录。不得用同版零调用恢复、构建或单 NTT 派发完成代替准确率和完整验收。下方 build24 及更早状态均为历史检查点。

本轮通过原内置浏览器继续 v23 项目，排除两条重复 BQ 处理规则、四条重复来源核查；把保证金目标从两个散片重选为当前原件完整 `*G1/*G1a`，建立「含基础工程的联合合约 AND 工期不少于39个月→G1a」「任一前提明确为否→G1」两分支，未知不设默认分支；核对主规则并排除另两条重复 marker。保存后为12个业务变量、16项处理规则、7项核对事项、34项未确认，业务根仍0项已确认、项目值0项采用。这些是人工处理结果，不能计为模型自动识别正确。步骤116—126、截图085—095在D盘操作记录；086、094为保存过渡画面，087、095才是相应完成回读，091也不是有效选中证据，完整保证金选区见092。

SCT与DOCX条号版SCC已通过前端上传到原v23项目，未重传NTT或发起全量重识别。新增SCT已绑定，却因不在旧 `graph.sources` 而没有选版按钮、保存也不可用，已实测截图089。frontend15窄修显式选版路径：点击时重新校验当前head与reading身份、分析代次、来源哈希和bound状态，追加来源并设置该family的选择；旧图谱及其他选择保持。普通选词与编辑权限的 `ruleSource` 未放宽。新增独立控件测试覆盖拒绝状态、旧图保留与CAS。修复后在独立项目 `Drafting Incremental Edition Probe 20261028`（`proj_mvsapsboxszn`）通过页面先建立仅NTT的23项来源核查底稿，再上传SCT；入口实际可点，显式选择并保存后刷新，再次选SCT仍显示「本次起草采用此版」，原NTT选择和23项核查保留。步骤127—130、截图096—099支持这一局部前端通过；该探针没有模型调用，不属于自动识别准确率实验。上传后通过无关NTT保存触发服务端pinGraph不是该入口的验收依据。

后端定向回归进一步定位三项真实harness缺陷，build25仅覆盖 `DraftAutomaticDiscovery`：

- 纯搜索→上下文请求回合被合并器凭空补 `taskOutcomes=[]`，继而被误判坏schema，第三轮没有机会回答。现在仅合并实际提供的outcomes；两边都没有时repair也不补空数组。evidence-only原判定未放宽，显式空业务数组仍接受原schema检查。
- 不完整范围未形成ScopeDuty时，已有check/needsRebind目标可能被统计为completed。现在按目标owner保留非fatal未决事项和partial，不扩大范围、不额外补修，也不把正常的保存前nativeBinding=missing一概视为失败。
- 完全同源、同scopeKey、同directive的重复owner，虽已有错误目标待核仍再次触发修补。现在只共享「已有未决记录」抑制unrepresented重复派发；missing、错误目标和未决状态保留，不生成coverage proof或执行权限。异源、异范围、异指令不共享。

独立只读审查未发现上述三修放宽范围或来源守卫。另两项原生范围/SHA安全失败是测试输入错误：精修fixture误传新effect对象，关联证据fixture跨命名空间重号；修正输入后保持原安全断言。旧联合合约提示词、已隐藏变量、关系焦点/目录、真实搜索命中、预算边界和父子回执输入的测试分别按当前契约修正，不能据此声称模型能力提高。

软件验证由负责统一交付的协调会话在隔离目录实际强制重编译执行：相关14套件193项通过，0失败/错误/跳过，日志 `.local/git-delivery-20261028/backend-targeted-final.log`；两项安全套件20项通过见 `source-safety-retry.log`。三修72项是193项的重叠子集，不累加。前端typecheck及4个相关控件/版次套件通过，新增选版测试最终日志 `frontend-edition-retry.log`；这只证明软件回归。此前宽回归仍有未分类/未消除失败，不能宣称全仓测试通过。

冻结源码SHA256：Auto `7D566C291729A3B296CEA1208D708F661E41F3203827EDD51A63E905BF020C70`；新 `DraftGroupPendingProtocolTest` `E234F972D20F95EF8498A75DAA76D1F2694CCF233F34CF3012031C64B549D598`；`DraftingSourceReview.vue` `A35149669750AA8FF6EA0B5F32039B1516D2ADB1794932A43EEE4FEC7E7BA5A0`；新选版控件测试 `EB563EB16B0596051D0C2654BACF559FA2593C488E98C16EB66D3D0C614D0892`。

build25从build24冻结src/pom仅覆盖Auto，package成功30.724秒，`-Dmaven.test.skip=true`；本轮打包本身未执行测试，不能与协调会话的软件证据混称同次测试。JAR SHA256 `77AEE622B99790060442572FECBAEF4544F9525FB9AD3E0C7BF79A5E6321F9ED`。frontend15从frontend14仅覆盖上述Vue，typecheck及vite build成功9.74秒。旧Java35164经原exec会话中断后确认退出，新Java2624/18204启动；新前端仍5185（exec25246），后端exec24193；保留原group-parallel数据库、上传、8GiB堆、home/temp与配置，仅分开日志。其他模块服务未操作。

目前仍未实现「新增SCT/SCC仅识别所选来源、保留NTT旧覆盖」的完整前端入口；旧重新识别会规划全部来源，旧run续跑也不能容纳来源集合变化。该增量接缝已调查，未假称完成。铁路当前范围、非双信封未决、真正业务变量合并、三模板全量质量、两套SCC内容版次、阈值变化、通信提取/采用、三份Word仍未完成本轮验收。模型真实12次调用和23/23来源只属于之前NTT运行，不代表本轮修复已获得新的模型效果结果。所有新增证据留D盘。

## build24 历史检查点：续跑到23/23来源，人工范围修正保留，图谱仍为PARTIAL

build24/frontend14已通过内置浏览器在原v23项目点击“继续尚未派发的分组”。同一run `87dc8adf-a1fc-47b8-8134-68ef48510804` 从20/23来源、6/9组继续到23/23来源、9/9组，处理中0，状态仍为PARTIAL；终态49要求、31原子节点、失败0、结束时待核21。新的resultRevision为 `c8b33259-dd32-4944-b0c7-730199736a5b`。全部来源已有处理回执，不等于全部图谱语义正确、全部范围可执行或完整端到端通过。

本次已经取得两项实际前端证据：续跑没有重发旧六组；人工画词修改的完整正文范围、两个条件分支和改文取值源，在保存、页面刷新、续跑写入新候选以及再次刷新后仍保留。证据来自本地 `template-variation-probe/操作记录.md` 步骤103—115、截图077/078/080—084，以及页面实际下载的 `downloads/build24-continued-run-diagnostics.json`。

| 方面 | 本次实际结果 | 证据边界 |
| --- | --- | --- |
| 续跑与调用成本 | 原六组modelCalls前后均为1/2/1/2/2/1；各组rawResponse逐字及SHA256均一致。累计9→12次，新增3次仅属于原PENDING三组 | 对照 `build24-continuation-comparison.json`，证明此次没有重算旧组；不是全流程速度基准 |
| 人工范围反馈 | PDF画词选择完整NTT13正文，删除旧半句目标；唯一新目标为Replace，取值源为“BSSSC下NTT13本次采用替换文字” | 保存草稿并刷新回读通过；尚未采用真实项目改文或执行Word替换 |
| 条件与反馈持久化 | 分支BSSSC=是、NSC=否，分别用于替换和不替换；续跑后再次刷新，完整正文、分支与取值源仍保留 | 没有被旧NSC raw重新覆盖成半句check；只证明这一人工修改链路 |
| 未决问题保留 | 铁路组此次因 `scope_rule_scope_not_supplied_to_owner` 拒绝；旧BQ nodeIds变更冲突及后到引文拒绝仍可追溯 | 没有把继续处理当成消除旧失败；这些候选尚需核对或修复 |

“继续尚未派发的分组”的实际点击时间为 `2026-10-28T14:33:17.114Z`；诊断本周期 `lastContinuation.startedAt=14:33:18.337040Z`、`finishedAt=14:33:52.529151500Z`，按两者同为UTC重算为34.192111秒（截取六位小数）。这是本次新增三组的服务窗口；不能使用原run的创建到更新时间代替，也不等于用户操作全程耗时。旧六组的rawResponse和modelCalls与续跑前一致，原始失败继续保留。

本周期首先停止于铁路组 `group-1b17820f4b634e084baac6fe` 的scope owner拒绝，终态页面显示这一原因。原 `firstProbeFailure` 中BQ组的 `source_context_candidate_conflict` 是14:05:18的历史首停；续跑没有把它改写为本周期原因。另一个新组 `group-7a8a5e24eb49cb29da6b226a` 仍有 `unsupported_requirement_quote_kind_or_node_reference` 和 `response_parts_not_retained`，必须一并保留，不能因23/23覆盖完成而隐藏。

NSC人工修正的步骤为：打开“原文待核”中的Amend事项，对照指导说明关联的完整P546，通过“定位此段并选词”在PDF选中完整正文，点“作为修改范围”；把取值方式改为由其他变量值决定、动作为Replace，移除旧半句目标，选择BSSSC改文取值源，录入两个分支，再点“记录变量调整”“保存核对草稿”。截图080为保存后状态，081为更新前端后的刷新回读，084为续跑后的再次刷新回读。这是人工纠正及回写的实际成果，不能记成模型自动识别正确，也没有给未确认图谱或未采用的项目值授予文件修改权限。

build24主源码从build23冻结快照仅覆盖Auto、Service，package成功26.157秒，JAR SHA256 `2B42F7D488F2B8A4C121F5AB646EAF5A6E33087459B5CA8818195C1D7176103F`；测试源码未编译或执行。frontend14仅覆盖automation-types.ts、DraftingView.vue，typecheck及production build成功（vite16.06秒），前端测试未执行。软件构建、真实模型运行、原件质量及前端业务验收分别记录，不能互相替代。

当前仍只覆盖同一metadata-only NTT；原件、已核基线来源与编辑范围权限保持原有边界，原子识别能力继续保留。SCT/SCC、项目通信提取、真实项目值采用、三份Word生成／目录／下载及整图准确率仍未完成本轮验收。所有原件、截图和运行证据保留D盘，完整目标未完成。下方全部旧检查点保留，出现“运行中”“当前”等措辞时只指对应记录时刻。

## build23 历史启动检查点：build22 的仅核查候选不应触发全局停止

此节覆盖下方build21及更早运行状态。build23已通过内置浏览器5185新建独立项目 `Drafting Template Variation Metadata v23 20261028`，上传相同metadata-only NTT，在页面回读1/1份已解析后，于 `2026-10-28T14:03:25.887Z` 实际点击“开始识别变量并构建图谱”，由前端触发真实Qwen3.8 27B。记录截至这一启动阶段，运行结果尚待回读；不能预写NSC通过、准确率提升或完整验收通过。当前build23 Java8960/18204、exec51904，frontend13 Node38020/5185及原DB、上传保持。操作步骤99—100和截图072—075记录新建、保存完成、上传及启动。

build23只从build22冻结快照覆盖 `DraftAutomaticDiscovery` 的两处窄修：原始 `proposedAction=check` 的核查候选不因执行范围精修而重试或全局致停；明确的reference/verification候选与no_requirement矛盾保留为非fatal pending，仍是partial。原始delete等动作后来被降为check的情况不享受这一例外，其他条件、范围、来源和引用错误继续保持严格。源码package成功23.385秒，JAR SHA256 `08D610B5BDEB72752C0CF2D61EFFA28919DD66FF713DB22FECF4A7BD80DC158C`。测试源码未编译或执行；构建和启动不是修复效果证明。

此前build22在页面自动PARTIAL，run=`9c538772-409d-44ca-8ea1-b92a409dfa18`、revision=`d344189b-7832-4158-bc17-2c708c16b19e`：17/23来源、5/9组、处理中0、36要求、22原子节点、失败0、结束时待核12。实际6次模型调用由五组receipt的1＋1＋1＋2＋1相加；服务窗口 `2026-10-28T13:49:53.782930Z` 至 `13:51:24.373714Z` 为90.590784秒，只是短测服务时间。审核区截图071显示12业务变量、16处理规则／派生项、6核对事项、34未确认；这些计数不能视为已经正确或已经人工确认。

数字和原因来自前端实际下载的 `.local/drafting-harness-main-20261028-v1/template-variation-probe/downloads/build22-stopped-run-diagnostics.json`。首停回执明确记录工期／联合合约组 `group-85bedd453301bcbcfea9aa22` 于 `13:51:15.726333200Z` 触发 `group_no_requirement_conflicts_with_candidates`、`scope_rule_refinement_unresolved`。该组t1原始action就是check，但refinedQuote把guidance误当目标正文；t2/t3有reference requirements、空nodeIds、无新节点，却同时声明no_requirement。这些原始矛盾仍应待核，但其本身没有执行修改权限，旧探针却把它们升级为全局停止。NSC等其余四组仍PENDING，本轮未派发NSC，不能据build22证明NSC语义上下文或具名引文修补生效。

build22本身已加入：精确note但局部scope不匹配时，提供无编辑权限的 `fixedActionSemanticReferences`；未绑定范围的非check动作保持check并产生具名修补；合法且唯一的同owner/kind/nodeIds requirement可进行一次具名引文修补，其他错误及后续未修好仍致停。该build主源码package成功25.384秒，但仅源码存在、构建成功与对应路径实际运行须分别记录。build22失败原始输入、rawResponse、接受结果、诊断和未派发状态继续保留，不能用build23代码反写旧轮结果。

这两轮仍是同一NTT短探针：仅修改docProps元数据，正文XML字节未变，副本SHA仍为 `8eca847cb636682d902951836424baec4f22e17342d2f1a505d0f0b7e8ec5200`。原子识别能力保留，已有固定定义只按已供证据作为待审参考；未把固定目录或局部候选冒充完整、已核、可执行基线，也没有从历史范围取得当前原件编辑资格。本轮没有上传SCT/SCC或项目通信、采用项目值、生成和下载三份Word。完整目标继续有效。所有截图、上传和运行证据保留D盘；业务触发与诊断下载来自前端，源码构建、只读诊断分析、测试源码状态和业务验收分开报告。

图文记录继续在 `.local/drafting-harness-main-20261028-v1/template-variation-probe/操作记录.md`：步骤94—97对应截图068—071，步骤99—100对应截图072—075。没有补造未记录的点击时刻，下载后截图缺口也未用现存终态图替代。

## build21 历史检查点：已越过 WTO，NSC 动作语义和范围参考仍未接齐

此节覆盖下方build20及更早运行状态。build21 Java29268/18204、frontend13 Node38020/5185；前端run `a890f138-b81c-4fce-b12c-8ee4e2f288c2` 自动PARTIAL：20/23来源、6/9组、8次真实模型调用，服务窗口13:17:22.362386Z至13:18:51.593789Z为89.231403秒。数字来自前端实际下载的本地 `downloads/build21-stopped-run-diagnostics.json`，不是全流程耗时或准确率。当前没有模型在途，三份Word仍未生成，完整goal active。

WTO固定定义供给和实际一次定点修补已接通，最终issues为空且未触发停止。新的首个停止组为NSC：一条requirement引文把真实续文缩写为字面省略号，来源检查拒绝；kind、根ID与scopeObligationIds均已合法提供。另一个独立问题是当前s1仅覆盖P546带星号的后半句，而固定NTT13目标为整段，动作参考因此未供给；模型把Amend误推成delete。该结果只有待审权限，尚未执行。修复必须补仅供语义判断的固定动作上下文，并保留范围待核，不能只修引文后放行删除。

本轮已通过前端处理BQ的两条重复规则：打开第二套L10Pro保留/Hardcopy删除规则，核对同一s1及条件后点击“不再提问”，保存核对草稿，刷新后重新进入图谱，仍为12业务变量、18处理规则、5核对事项，证明两项排除已持久保存；原始诊断和旧版本保留。截图062–067，操作记录87–90；不是整图确认，也未采用项目值。直接合并非空处理规则目前会受MERGE_RULE_CONFLICT保护，此次使用排除重复规则这一现有前端路径。

build21仅覆盖编译器的unused标准文字容错与明确字段适用动作的提示，package成功27.678秒。所有新增／修改测试仍仅源码，未编译执行。下一阶段仍须完成NSC修复、未派发组和完整三模板业务验收。
## build20 历史检查点：WTO 修补已发出，冗余标准文字被解析器拒绝

本节覆盖此前所有“当前／最新”运行状态；下方 build17 及更早记录保留为历史。当前 build20 Java19040/18204、frontend13 Node38020/5185，原数据库、上传与人工保存版本保持；完整端到端目标仍 active，本轮三份 Word 尚未生成。

| 前端运行 | 已处理来源／分组 | 实际模型调用 | 服务记录窗口 | 首个实际停止点 |
| --- | --- | --- | --- | --- |
| build18 / 5d1faea1-672f-49c9-bf90-0b827d7f7a6d | 12/23；3/9 | 3 | 33.808466秒 | 业务引用用了baselineKey而不是当时的k编号 |
| build19 / 7a7f32a4-35ce-4d57-a4f5-825f6ee38396 | 14/23；4/9 | 4 | 48.164522秒 | WTO固定定义漏供；BQ修补准备后被停止，未实际发送 |
| build20 / 09575cfc-264b-411b-84c3-0bb88638bdd5 | 14/23；4/9 | 5 | 60.835004秒 | WTO实际修为unused，但冗余replacementEn被拒绝 |

数字依据为各轮通过前端“准备本次运行诊断→下载诊断JSON”保存的 `template-variation-probe/downloads/build18/19/20-stopped-run-diagnostics.json`，根目录为忽略的 `.local/drafting-harness-main-20261028-v1/`。modelCalls取各组receipt求和，时间仅为run.updatedAt减createdAt，不能外推全流程或准确率。build20服务窗口13:07:55.601690Z至13:08:56.436694Z；firstProbeFailure实际保留WTO、GROUP_DISCOVERY、完整原因及触发时间，页面本轮与首因一致。

build18已把同note的NTT2发放与NTT3(c)回标动作参考按完整正文消歧。build19进一步用稳定baselineKey替代k编号，并保留未知条件与原生范围。build20纠正有限案例三值判断，补供WTO固定定义；铁路原不闭合note只恢复有原文依据的待审定义，原capture告警及未知范围保留，未获编辑权限。build20的BQ首轮12个规则经只读诊断核对：10个非check条件分支保留，NTT3均为双信封AND格式；两条非双信封规则为check。原raw与accepted compiledScopeCondition逐条一致，不再重现build19错误删除和条件案例误报；12条规则仍为pending，不能计为人工确认或最终文件正确。

本轮WTO首轮为replace/Not used，实际一次定点修补改为unused并仍附replacementEn=Not used；该标准冗余文字遭SCOPE_RULE_ACTION_VALUE_UNEXPECTED拒绝。下一窄修仅规范化unused的标准声明，任意自定义文字和valueSource仍拒绝，同时明确提示字段适用动作。源码不等于前端验证，后续须从新分叉继续记录。

已验证的人工反馈边界：v17前端采用完整1(e)待核范围、条件=否、动作改Delete、记录并保存，版本及刷新回读保持；frontend13条件下拉由20项降为10个业务取值候选，既有选择保留。其他候选入口、整图合并、项目通信候选及三份文件交付尚未完整验证。截图040–057和详细点击说明保存在本地操作记录；build20续记单列后再并入。

所有这些隔离package都使用-Dmaven.test.skip=true；本轮新增／修改测试仅为源码，未编译或执行。前端typecheck/build与真实模型、原件质量、人工业务验收分别报告。

## build19 对照检查点：仍为 PARTIAL，错误删除已隔离，修补未发送

build19 通过内置浏览器5185新建独立项目 `proj_mvs3g1woeeig`，上传与此前相同的metadata-only NTT，并于 `2026-10-28T12:32:55.824Z` 点击“开始识别变量并构建图谱”，实际触发Qwen3.8 27B。run=`7a7f32a4-35ce-4d57-a4f5-825f6ee38396`，resultRevision=`0b8db82f-0b40-4f2b-9ceb-604ac20127e6`。本轮后端build19/Java8964/18204，沿用frontend13/Node38020/5185及原DB、上传。主源码package成功23.746秒；测试源码未编译、未执行。截图050—053及前端实际下载的 `downloads/build19-stopped-run-diagnostics.json` 保留在D盘本地证据目录。

前端自动PARTIAL：14/23来源任务、4/9组、处理中0、22要求、15原子节点、失败0、结束时待核10。服务记录 `12:32:56.316355Z` 至 `12:33:44.480877Z`，相差48.164522秒，仅为这一短测服务窗口。4个执行组各1次真实调用，共4次；另有5组、9条来源未处理。已处理来源receipt为4 completed＋10 partial；这10条来源待核不是10个错误业务变量。审核区显示10业务变量＋15处理规则／派生项、25未确认。

计数必须分别解释：10个业务根来自已有knownRoots，模型没有新增独立input；15个接受节点全部是scopeRules编译的处理节点，其中5 delete、4 keep、6 check，均待确认。22条requirements为15条编译记录＋7条模型记录（21 amendment、1 verification），不等于22个用户问题。实际派发输入77,569字符、输出16,759字符；另有39,263字符修补输入已经准备但没有派发，不能计为已发送模型输入，也不是token测量。

| 组 | 真实接受结果 | 仍未通过的内容 |
| --- | --- | --- |
| Facade | 2条条件动作、2编译节点、2要求；使用稳定业务ID `precastFacadeTenderBasis`；marker按精确同源同范围共享 | 本轮没有附加check，不能据此声称“guidance动作与marker check共存”的owner隔离已实测 |
| Excision | `worksSubjectToExcision`在条件及nodeIds中合法引用；接受1条false→delete、1编译节点、2要求 | 仅这条观察到的分支通过，不能外推全部分支完整 |
| BQ | 原始12条规则；NTT2六个effect保留keep/delete和完整范围；NTT3(c)六个effect全部降check并保留原生spans | 模型又给出两条twoEnvelope=false→delete；修补准备后因全局停止未发送 |
| WTO | 非法 `wtoGpaApplies` 引用的两条规则均拒绝，接受节点/effect为0；仅marker的1条verification requirement保留 | “Not used”被模型误写delete；当前拒绝由非法引用触发，没有验证动作语义守卫；按需取证请求未执行 |

BQ本轮补出了build18缺少的三个反格式删除，但又产生两条其他投标程序下的错误删除。系统接受的NTT3(c)六个effect均为 `action=check`、`scopeComplete=false`、`needsRebind=true`，原动作只在 `proposedAction` 与原始条件中保留；`observedScopeComplete=true`、native source SHA和spans保留供人工核对。错误删除没有取得执行资格。该保护是实际结果，但case诊断仍不准确：`matches()`要求规则提到全部case输入，使只含twoEnvelope=false的规则连对two=true案例也返回unknown；四条正常case被列为unknown，未精确列出四条pending_case_treated。实际先由缺少electronicTendering依赖，再由同scope的case隔离保住范围；不能把它称为精确的pending案例识别。

最初停止来自WTO组。其 `SCOPE_RULE_KNOWN_VALUE_REFERENCE_REQUIRED`、`unsupported_requirement_quote_kind_or_node_reference`、`group_identified_without_supported_requirement` 和丢失计数均保留。WTO先设全局取消，随后BQ准备的 `scope_disposition_repair` 在gateway dispatch前被“任务已请求停止。”挡住；evidenceRounds记failed但没有rawResponse，该组modelCalls仍为1。因此本轮没有BQ第二轮模型回复，也不能将取消当成第二轮模型失败。页面显示BQ的 `scope_rule_condition_reference_unresolved` 是因为最终receipt按taskOrder排序后仅取首个probeReason，掩盖了实际首次触发顺序。

BQ修补请求的owner仍分别对应t4/s3与t5/s4；存储supplement用内部source ID，编码源码覆盖sourceTaskId、sourceTaskIds和scopeIds。因为未实际派发，没有第二轮wire接收实证。下一修复应保留首次停止的任务、全部原因和时刻，并在现有预算内让符合条件的局部修补确实得到机会；错误来源、条件和范围不能因此放宽。

本节之后，build20主源码已package成功24.247秒，测试源码未编译或执行。Java19040/18204沿用frontend13、原数据库和上传；内置浏览器新建v20并于 `2026-10-28T13:07:54.860Z` 点击识别，截图054—056。该启动事实先存本地 `template-variation-probe/build20-操作续记.md`，后续结果见本文前部build20及之后的检查点，不能把启动本身写为通过。

## build18 对照：引用协议卡点与不完整覆盖被误判完成

build18同样仅上传上述NTT副本。前端下载诊断的project=`proj_mvs2kza18ekg`、run=`5d1faea1-672f-49c9-bf90-0b827d7f7a6d`、revision=`12c86a02-4514-43a3-bbb8-6dec31d39c88`。服务记录 `2026-10-28T12:08:46.414186Z` 至 `12:09:20.222652Z`，33.808466秒，自动PARTIAL/READY_FOR_REVIEW；12/23来源、3/9组、3次真实调用，没有补证轮。5个known业务根与8个编译节点分开计算；后者是7处理派生＋1核查，不是8个独立变量。15条requirements由7模型＋8编译记录组成，类型为13 amendment＋2 verification。

本轮当前正文路由已纠正：NTT2纸本发放不再误拿NTT3(c)回标动作参考。BQ组却只有5条规则，漏了NTT2纸本在L10Pro下删除，以及NTT3(c)电子/纸本两个反格式删除，仍被标为completed。scopeDuties=8、missing=0只证明每段存在处置，未证明完整条件案例覆盖。Excision的引文及删留方向正确，但inputId和requirement.nodeIds用了稳定业务名worksSubjectToExcision，当轮协议仍只接受k5，故产生引用拒绝；不得记作引文或语义判断错误。Facade附加check还曾按全局scope压掉另一owner的合法完成度。上述失败原样保留，build19稳定ID和覆盖守卫是针对它们的修复分叉。

## 人工修改反馈及候选过滤的实际验证边界

v17原始结果先封存，再通过页面进入外墙删除规则的“调整变量与影响范围”，采用完整1(e)原段，将动作Check改为Delete，保持条件“投标以预制混凝土外墙为基础=否”，依次点“记录变量调整”“保存核对草稿”。图谱版本新增 `2026-10-28T11:57:17.096229Z` DRAFT，刷新后完整范围、条件和Delete均回读保留，页面无未保存调整。截图040—044与操作记录50—55支持这一局部反馈保存链；尚未据此执行最终Word修改，没有采用项目取值或确认整个模板。

frontend13随后实际打开同一规则的条件“选择变量”，候选从20项变为10个业务取值候选，编译出来的保留/删除处理规则不再进入该下拉，既有条件=否保留；截图045和步骤60。只有这个条件下拉已做前端实测，其他候选入口虽然源码共用过滤，也不能据此一并宣布通过。

完整图文操作记录位于 `.local/drafting-harness-main-20261028-v1/template-variation-probe/操作记录.md`，build19已续至76；截图050—053已嵌入文字步骤。下载动作存在但没有独立下载点击时间及下载后截图，文档明确保留此证据边界。此处全部都是单NTT短探针与局部人工反馈，本轮三模板全量图谱、项目通信提取、项目值采用、三份Word生成／更新目录／下载均未完成。

**以下各节为历史检查点，其中的“当前进程”“下一构建”“尚未修复”等措辞仅指对应运行当时，不代表以上最新状态；保留失败以供对照。**

## build17 历史检查点：恢复原生坐标，但动作参考路由仍有误

2026-10-28 19:40:19通过5185前端新建v17项目、上传相同metadata-only NTT并点击真实27B。运行 `2af86233-c71e-423d-a0c0-e9681397ae3f` 自动PARTIAL：14/23来源、4/8组、18要求/11原子节点；5次调用（1+1+2+1），服务记录11:40:19.852116Z至11:41:30.505108Z为70.652992秒，不能外推全流程。诊断经页面实际下载到D盘，截图036—039，操作记录续至49。build17主源码package成功，测试源码未编译或执行。

accepted中的11个目标均恢复native坐标，说明完整当前段落的归一化文字到Word字符范围投影已经进入真实运行；但nativeBinding仍为missing，尚未证明保存后严格原件绑定成功。BQ2各文字范围保留9个span及6个空布局成员，layoutCleanupIncluded=false。BQ3(c)L10Pro两条规则本轮已包含格式与双信封AND、scopeComplete=true；只是局部候选改善，未确认、未执行。

当前首要卡点为动作参考路由：NTT2纸本发放与NTT3(c)第二信封回标的guidance相同，CurrentTemplateAlignment按note把NTT-3C-PAPER误挂到NTT2完整正文，导致模型及修补轮把双信封前提加到发放条款；不能只让模型补AND。后续源码修复按当前完整目标正文核对动作参考，相同notes仅作定义导航，保留来源版次守卫。

其他本轮缺陷：BQ2继续把重复标题当字符精修；Facade完整scope回显被当作精修而额外待核；Excision可选refinedQuote:null被拒绝。后两项源码已修，新增独立测试源码，未构建或运行。Auto已加入无效精修的定点修补请求与最终仍未解决时的早停，尚未运行。当前运行仍Java4548/build17/18204、frontend12/5185；旧DB、上传及所有失败保留。完整goal active，尚未开始本轮通信及三份Word交付。

## build16 历史检查点：真实前端短测仍未通过

2026-10-28 19:10:48（Asia/Shanghai）通过内置浏览器5185，在独立项目 `Drafting Template Variation Metadata v16 20261028` 上传同一 metadata-only NTT后点击识别。build16仅覆盖六个本任务主文件到build15冻结快照；主源码package成功，测试源码未编译、测试未执行。frontend12的typecheck和production build成功，前端测试未执行。实际运行Java20464/18204与Node22472/5185；历史DB、上传及失败均保留。

运行 `1060e3d9-de4b-4b20-a5f8-61cf9bfc2940` 自动PARTIAL：14/23来源事项、4/8组、17要求、10原子节点、处理中0；四组各一次真实27B调用，共4次。服务created `11:10:50.070698Z` 至updated `11:11:27.986060Z` 为37.915362秒，仅为该短测服务窗口，不能外推三模板全流程或准确率。业务操作、诊断下载均通过前端，无产品API/数据库旁路。本地操作记录已续至41，截图031—035。

实际收益与残留问题分别记录：

- 当前BQ组使用已有k3直接输出scopeRules，没有再建同一BQ input；Facade依k7 true/false给出keep/delete。业务条件和动作仍由模型判断，harness只做已有图谱结构编译，不能把固定根计为模型识别成果。
- 前端实际切换三类列表，显示10业务变量、9处理规则/派生项、1原文待核；这只是分类交互通过，不是20项语义通过。
- 停止原因 `SCOPE_RULE_ID_INVALID_OR_DUPLICATED` 的实际规则名 `rule_t1_keep_yes_excision` 长25，未重复，却超过新helper的24上限，是harness额外格式负担。已修改源码允许1—64并区分invalid/duplicate；调用提示同步修改，测试源码补边界。该后续修复尚未构建或运行。
- BQ2的1662字符完整scope被模型用48字符、出现两次的标题作refinedQuote。accepted四条目标均为check/needsRebind，没有refinementCandidate/native执行位置；原完整scope保留，没有应用错误精修。
- BQ3(c)两条keep仅以k3格式为条件，漏掉k9双信封AND前提；prompt内fixedActionReferences及knownRoots已提供该前提，不能归为证据未提供。accepted同样保持check，未修改文件。模型还误将“纸本BQ＋其他PDF/Excel配套文件”当作同一格式冲突。
- 同scope的guidance和marker仍可产生重复规则；来源义务共享不能单凭覆盖归零证明免除人工重复工作。

原始诊断通过前端准备并实际下载，封存于 `.local/drafting-harness-main-20261028-v1/template-variation-probe/downloads/build16-stopped-run-diagnostics.json`。下一轮须先修复合法ID误拒，并针对当前已提供的必要前提、完整范围与重复义务补充明确反馈，继续短测；不能只修ID后跑完全轮。完整三模板、改阈值差异、通信提取、人工采用和本轮三份Word下载均未验收通过，goal保持active。

状态：实施和真实前端验证进行中。未完成全量图谱质量、通信取值、三份文稿及下载验收。

## 历史检查点：固定装载不能代替能力改进

build09 通过内置浏览器上传精确三原件，恢复96项固定定义候选（78根）、24项条件，模型调用0；一次整组操作确认78根定义。此结果只证明既有资产接入及组确认可用，不证明模型准确率、文件内差异处理或编辑范围已核。当前内置恢复要求三份DOCX原件SHA精确命中；历史人工基线仅能按完全相同文件恢复部分，尚未实现文件内部未变片段的差异复用。不得将0调用当作泛化提升。

本轮进一步发现：非同SHA模板的单项、分组及SCC关系阶段，固定语义参考被原件执行资格不必要地限制；分组/关系补证检索还会自动展开搜索命中的完整定义。build10拆开语义参考与旧范围权限，允许已绑定当前来源按需查固定定义，搜索仅返回导航。旧范围/固定动作的SHA守卫不变。主源码package成功，未编译或运行测试源码。

随后通过5185前端新建独立 `Drafting Template Variation Metadata 20261028` 项目，上传只改 `docProps/core.xml` 注释而正文XML字节不变的NTT副本。副本SHA为 `8eca847cb636682d902951836424baec4f22e17342d2f1a505d0f0b7e8ec5200`；15:53（Asia/Shanghai）从页面点击真实27B识别。任务在14/23来源事项、6/10组、14要求/6节点时自动PARTIAL停止，保留4组未派发，没有通过API或数据库代替业务操作。截图和步骤在本地 `template-variation-probe/操作记录.md`。

停止原因已对照实际输入与原始回复定位：hardcopy BQ组req-3引文是该任务已获授权scope内的DVD-ROM句子，却将 `sourceObservationId` 填成只含前一句的marker ID。严格核验正确拒绝了错引；`response_parts_not_retained`是同一次拒绝的派生计数，不能算第二种独立故障。下一修复仅针对本任务合法证据内唯一逐字命中、且当前原件范围可验证的出处补正，保留模型原ID和补正轨迹；歧义、跨任务或历史引文仍拒绝。修复及重跑尚未通过。

本轮真实模型输入已确认含相关固定目录导航，尚未证明模型正确利用完整定义。首组外墙的keep/delete范围符合当前原文，但问题措辞只说适用性、未明确投标基础，最终固定身份匹配仍未知。不得据此报告整轮准确率或人工成本改善。

以下早期运行记录保留为历史对照；当前目标继续有效，未达到完整端到端通过。

用户批准按[分组编排调查](../research/drafting-graph-batching-primary-sources-20261028.md)实施，业务验收只通过内置浏览器触发。源码修改、只读原件与日志分析及构建分别记录；测试源码尚未执行，构建不代表业务通过。所有原件、模型调用日志、截图、数据库和运行证据保留在 D 盘忽略目录 `.local/drafting-harness-main-20261028-v1/`。

## 已运行的对照

| 运行 | 页面 | 实际处理方式 | 当前证据边界 |
| --- | --- | --- | --- |
| build02，Baseline Tools Main | 5183 | 原始逐事项串行；三份同原件，Qwen3.8 27B | 已结束PARTIAL，273/273记录、269要求、416节点；前端回读401业务变量＋15核对事项，未人工改原始候选 |
| build05，Group Serial | 5184 | 255来源事项分为59组，最多1组推理，原事项逐项留回执 | 12:40:21由前端点击开始；最近248/255、58/59组，未人工修改候选 |

build05后端SHA256：`0FDB947A4DE3C0B9CFCD7AF766D2F7A892166EEFE1BB05FB0C9E93601FAFDBA5`。原build02及上传原件保持；构建快照采用本轮路径覆盖，未合入并行OER/Vetting/Advice改动。

分组串行与旧关系阶段有重叠，不能由这轮总耗时推算严格提速比例。已有旧运行的24事项观测中，24次请求往返占435.973秒观测区间中的432.728秒（99.26%）；包含提供端排队、推理、生成、网络和适配器，不是纯GPU计时。具体时间戳、逐调用数据和边界保存在本地速度瓶颈报告及CSV。

## 已通过页面完成的局部操作

以下均发生在独立的Manual Entry Check项目，不修改模型对照项目，也不构成整套模板已核验：

- 空图谱明确建立手工草稿，新增业务变量并保存；业务变量与23项来源核对分别显示。
- BQ是非提问改为具有稳定L10Pro/Hardcopy值的单选，人工核对后匹配固定定义；实际保存成功。
- PDF选词自动绑定不充分时，人工选择当前页完整Word原段，确认后关联现有变量并保存；引文和来源可回读。
- 只确认BQ变量子集，页面实际显示已确认变量1并启用项目候选提取；来源核对和未绑定范围继续待核。
- 将选词保存为check目标；保存后引文和待核动作保留，未据此授权编辑。
- 分别从NTT2两个Alternative建立局部是非项，人工关联到同一BQ选择，保存两组相反的条件和各自来源/目标；采用界面只出现一个BQ项目输入。
- 在该独立项目保存一次明确标注的人工Hardcopy交互测试值。没有将此值当成模拟通信提取答案，没有生成文件。

图文操作手册在本地 `group-serial/操作记录.md`，已记录每个点击、输入、保存效果和修复分叉。截图001–022与原始失败保留。

## 实际发现与修复状态

1. 新变量的`confirmed`标志被下拉误写为已确认固定匹配。修复区分new、matched、ambiguous及未知状态，页面已回读验证；只有人工匹配后才显示已确认对齐。
2. 派生条目被说明为来源核查事项。修复只改说明分支，页面已验证派生规则及来源仍保留；没有改变权限或计算。
3. 首批原始模型回复减少了BQ重复根，但将NTT3(c)不同范围以不新增输入为由标为no_requirement，漏了动作。另有主来源错配。图谱最终接受情况仍须回读，不能由raw认定已生效。
4. combined-with-foundation与39月样例保留了完整前提、AND/OR及前12月语义，但精修引文不在对应scope内，不能当作正确可执行范围。
5. 已确证目录`fields.affects`的宽落点导致excision混入BQ组。下一构建改用明确固定业务动作的paragraphs/inputKeys和原指令自身位置；宽父范围只作参考。只提供小量动作解释，不能取得编辑权限。
6. 下一构建增加scope义务覆盖检查及现有3次调用预算内的一次具体修补反馈。精确同源同scope的有效处置可以共享；仅重叠或不同scope不共享；未解决继续待核，未知发送不自动重发。

第5、6项尚未通过修复后真实前端验证；不得据测试源码或静态复核写成已通过。build06及后续双路运行待记录。

## 仍需完成

保留全量原始候选，按共同根、必要条件、分支动作、来源和范围核对准确性及人工成本；完成修复后两路并发的真实前端验证。随后通过页面确认图谱、上传模拟通信、提取并人工采用、必要纠正与合并、生成NTT/SCT/SCC、更新目录和预览、实际下载三份文件。真正完整的同版基线登记及跨项目复用另行验证，不能把固定目录或部分人工草稿升级为完整基线。


## build06 前端试跑与新分叉

build06 后端已构建，SHA256 `39C2C61909796AFD64EF67952DDA49A7B64BF0986465D59B5DDB377369A09073`；未编译或执行测试源码。独立后端18204（Java40496）、前端5185（47508），数据库及文件全部在D盘group-parallel目录。通过内置浏览器新建Group Parallel项目，上传同三份原版2a模板，于2026-10-28T05:27:18.000Z点击开始识别。实际计划255事项、54组、最多同时2组。

初批请求确认Excision不再进入BQ组，精确动作路由修复已在实际请求中生效。但Groups.payload仍把词法候选自动凑满8份固定定义全文；Excision等已有明确根的任务仍收到天气延期、combined foundation、39月等无关定义。这属于输入编排缺陷，与scope guard是否有效分别判断。通过页面“停止任务并保留已有结果”请求正常停止，不杀在途调用。修复分叉及截图001–006在本地group-parallel/操作记录.md；停止后的终态另行记录。

下一最小修复只取消不必要的自动全文填充，保留精确根的定义、必要依赖及模型按需baselineContextRequests/baselineSearchRequests，不放宽来源或scope权限。新的全轮验证从开始识别处重新记录。当前没有完整质量或端到端通过结论。

## build07 修复后完整重跑起点

build07后端SHA256 `CC4243C69C6384FBA280D15175DAE4A9C905B5C614B50125F5DAF3DE1EB7AF98`，进程12576/18204；沿用前端5185、原数据库及旧已停止记录。新项目 `Drafting Group Focused 20261028` / `HARNESS-GROUP-FOCUSED-20261028` 通过页面新建并重新上传同三份原版2a。前端实际点击开始时间2026-10-28T05:40:25.763Z。截图及从分叉重记的步骤在本地group-focused/操作记录.md。

本次初始固定定义仅来自精确业务根及其明确condition.all前提；无精确根时只提供轻量导航，模型仍可按需查定义和原文。初始自动定义删除按affects附近关键词猜取的sourceContext，真实sharedEvidence和精确动作说明保留。角色提示澄清外部答案、派生值、核查项及固定文字的界限，不修改求值器或已有图谱兼容性；停止文案也不再将未执行完的图谱称为完整。

源文件快照与SHA在build07-source-manifest.json；Groups及角色提示经独立静态复核。测试源码未编译/执行；后端打包成功和实际前端启动分别报告。模型质量、全轮耗时、人工修改成本及最终三文件仍待该轮验证，不能报告通过。

## build05 全轮终态与当前效果边界

前端已确认PARTIAL保存：263/263（255来源＋8关系），158要求、172节点，59/59组，失败0、结束时待核90。审核区异步载入后显示143业务变量、29核对事项，172未确认；142待核变量、33未决关系。全程没有人工调整这轮候选；截图015保留刚终态时的0暂态，016为无需刷新即载入后的有效图谱。

最终日志固定13:44:49+08、3,193,266bytes、SHA256 `584a7194d02c5f78bbb79b5cfd7e8a6739c5c2cb30813ae60829f287215b6114`；原始文件见本地group-serial-final-timing-20261028T054449265Z捕获清单。76次完整调用=60次分组调用＋16次关系调用，无未配对调用；12:40:32.932至13:42:34.441首尾3721.509秒，往返累计3707.963秒，间隙13.546秒。旧逐事项全轮296次完整调用、首尾7469.995秒；调用数减少约74.3%。两轮存在共享提供端负载和提示差异，不能作为严格独占提速实验；节点或待核数量减少也不证明准确率提升。

build05的实际语义问题仍保留：固定候选污染、BQ与局部Alternative重复作答、scope错误owner或自造ID，以及屋面必要安装前提缺失。修复后build07已完整运行中，不以模型误判为理由提前结束；其输入编排已实证生效，输出质量另行抽核。

## 同主题初始输入缩减（build06 → build07）

只读封存请求确认原件SHA和instruction类型/文本相同：BQ初始user prompt从30479到20550 UTF-16单位，Excision从20229到7328；对应task、sharedEvidence、scope义务数量保留。这里不是token测量，也不是整体速度或准确率结论。具体行号、哈希和对照在本地group-fixed-context-build06-build07-handoff.md。

## build12短探针终态与build13修复分叉

build12由前端新建NTT metadata-only独立项目并启动，run `13a063e1-8480-4537-aa6f-b8c25babf853`，来源SHA `8eca847cb636682d902951836424baec4f22e17342d2f1a505d0f0b7e8ec5200`。发现BQ/双信封业务根被整组改为verification后，立即通过页面停止派发并保留在途结果。终态23/23来源、8/8组、27要求/4节点、15次真实模型调用，PARTIAL，未进入关系阶段。结束原因source_context_candidate_conflict是补证跨owner复用了requirement local id，原始候选和前端下载诊断保留。不能以全部事项抵达终态或节点数量减少宣布准确率改善。

build13只覆盖本轮五个主文件：DraftAutomaticDiscovery、DraftReviewDiscovery、DraftDiscoveryGroups、DraftDiscoveryEvidence、DraftDiscoveryProbe，其余沿build12冻结快照，未卷入其他模块并行修改。修复阶段指令，区分未来待答业务根与纯结构核对；仅在模型投影中省略内部literal_source_condition_not_project_boolean状态标签；跨不同owner且无语义引用的requirement local id重号安全重编号，单独记normalizations；新增exact-current、完整scope及固定定义/动作交集下的verification-only业务遗漏探针。节点/分支/值引用和同owner变义仍保持原拒绝。独立静态短审无Must，不等于运行通过。

build13主源码package成功，测试源码未编译、测试未执行；JAR SHA256 `BFFA66E946DF6F64BFDEAC8CB8E702581BEBC609D2F1D25DA60BCF16C2BE5282`。仅替换精确核验的build12 Java43724/18204，build13 Java2312于17:17:55就绪；前端5185及数据库/原件/历史运行保留。新metadata-v13项目已通过页面创建，后续真实模型短探针从此分叉继续，尚无修复后质量结论。

独立固定界面探针已通过页面采用BQ=L10Pro并保存。双信封未知时NTT2(a)保留/(b)删除，而NTT3(c)保持待核；再独立采用双信封=true并保存后，NTT3(c)(a)保留/(b)删除，NTT3(a)-(b)/4(b)/SCT5等标准程序保留。这是页面方案求值接线验证，测试值不是通信提取答案，未证明Word实际修改。图文记录和运行诊断全部在D盘本地证据目录。

build13真实短测run 2ed50d61-8942-43d1-a511-756b61c7e6b6由前端启动，2026-10-28T09:20:17.89149Z至09:20:41.841265Z自动终止（run时间23.950秒）。两组各一次真实模型调用，共2次；6/23来源、2/8组、7要求/1节点。Facade问题主体精确，但无branches/effects，且有requirement同时no_requirement，触发group_no_requirement_conflicts_with_candidates；BQ组仍以项目选项未知为由不建root，保留unresolved。原始下载JSON、截图020和完整操作分叉均保存；这轮没有证明准确率提升。后续修复已转向harness保留当前exact指令支撑的待审固定根，并提供每组合法knownRoots引用；仍不复制旧范围或自动确认。新代码尚未构建运行。

## build14 结构修复（运行验收待进行）

固定定义不再仅作提示词参考：Plan从完整、唯一、已登记且当前原件有完整来源锚的guidance建立待审业务根及明确前提/结构子字段；无项目答案，不导入历史动作或范围。manifest保留小引用，finish独立保留这些根，模型未输出不再使其消失。模型包只给小knownRoots表；同当前source+SHA的其他已证根可作只读引用，primary owner白名单仍仅本组exact任务。

parser允许按已知alias补当前规则，或让新derived动作引用它；既有核心定义不可改写。局部验证仅用既有根的定义核心，避免把本组之外旧规则的依赖带入。每组的branch和target均命名隔离，scope授权仍按各自sourceTask检查。跨组归约仅白名单同核心规则；已确认或人工核心不覆盖，未应用提案在原receipt/raw与issue保留。knownRootCount与真实模型candidateCount分别记录。

新current引用根沿现有前端候选/组确认入口处理，确认时用真实当前readings清变量重建权威定义、核对完整来源锚与核心，再按必要前提拓扑盖定义/条件指纹；未知修改范围仍check/needsRebind，不随定义确认授权。显式selectedDocuments只接收选中来源；多个SCC未选择时不恢复SCC根，不按Word/PDF或相似编号混用。

上述主源码已在本地main落地并经分工静态窄审；新增契约测试源码尚未编译/执行。build14隔离快照只覆盖本任务8个主文件，保留其他模块并行修改；正在package，未开始修复后前端验收。完整端到端goal仍active。
## 2026-10-28 build14/15 当前交接

build14主源码package成功（首次helper异常助手/泛型编译错误已修，原失败log保留），测试源码未编译或执行。当前known roots结构路径进入真实27B请求，10根为harness候选，不计模型成果。Facade模型从build13空规则改善为k7 true/false +当前scope keep/delete；BQ依旧丢义务，run5d6fc1d9-8117-4c57-b31c-709bf900a06d自动PARTIAL，两次真实调用。原始JSON由前端实际下载。随后前端勾选BQ与双信封两定义，一次理由+提交成功保存已确认2、待核8、未决关系2；未采用项目值/未知范围。截图025/026。

build15修只读sourceTaskIds空却rulePatchAllowed为真的编排缺陷，主源码package成功，测试仍未编译执行。JAR A0B842523573F310B432D6A7392807A7F330C98332BCD2E78B2A0E7304931AD5，当前Java40088/18204，exec66970；前端11/5185和原DB保留。前端新v15项目同metadata-only NTT实际运行run0a22ccf2-384a-432b-b2aa-997693d76c41；自动PARTIAL至16/23来源、5/8组、21要求/6节点。Facade规则再次正确，BQ首段补条件/范围但重复输入，BQ/双信封合组仅文字说明无结构化规则，Excision悬空分支；不报告质量/人工成本通过。诊断已前端下载封存，截图030，操作记录至32。

下一修复：输出协议应约束已知业务根仅引用合法alias补当前条件/动作/范围；对确属新事实另留原子识别扩展。增强早停探针抓唯一固定问题/值域的重复输入、已有修改义务却只有文字说明，以及悬空branch，避免跑到更多组才发现。不要再用更多长提示词单独修这一问题，不自动按主题强合并，也不抹掉未知和原始输出。可考虑已知根组直接输出scope rules、由harness编译为derived动作，保留通用原子路径用于差异；此方案尚未实现。

当前内置浏览器tab4停在v15终态，已markHandoff，无未保存采用值。原高级截图接口失效，已选tab的CDP Page.captureScreenshot只读备用成功；filechooser需启动click promise后先setFiles再await click避免死锁。实际业务上传/模型/确认/诊断均由页面完成；未产品API/数据库旁路。所有证据D盘。完整goal仍ACTIVE，当前轮三份Word尚未生成；不要标完成，不要把现有单NTT短测当完整三模板E2E。
