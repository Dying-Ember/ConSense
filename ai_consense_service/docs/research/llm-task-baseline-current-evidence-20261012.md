# LLM 按任务评估：当前源码与既有证据

核对日期：2026-10-12，Asia/Shanghai。本次只读检查当前源码和已保存运行证据；只新增本研究文件。没有调用 API、加载模型、检查或变更 GPU／进程／服务、运行测试、读取凭据或改变 Drafting 冻结 harness。总体规则以 [OER 当前入口](../oer-scope-and-rules.md) 为准，研究建议见 [任务评估基线](../llm-task-evaluation-baseline-20261012.md)。

OER 实施状态采用本轮已读的 09:08 handoff 冻结视图；并行原件任务后续写入的 09:37 等状态不在本文重新核验范围。本文后续读取的是任务源码和已有 M3／Drafting 证据，不跟随另一个任务反复读取实施 journal。

用户已确认 OCR 与业务 LLM 不同时使用，可按阶段加载／卸载；业务流程不重新访谈。用户要求按 LLM 实际任务选型，不把约 120B MoE 作为最终模型规模限制，并报告满血 MiniMax M3 官方 API 在 Drafting 中效果不错。该直接经验保留为开发质量参照；不能用仓库 relay 默认值推翻它，也不能据此宣布 M3 在 Vetting、Advice 或比赛部署已通过。

## 1. 先确定模型真正执行的任务

| 任务 | 实际模型输入和输出 | 模型需要处理的难点 | 程序／人工负责的边界 | 当前调用与源码证据 |
| --- | --- | --- | --- | --- |
| Drafting 项目信息提取 | 按原件逻辑单元形成的 correspondence part、允许字段目录、精确输出规则；返回 `[{key,value,sourceQuote,reason,confidence}]` | 完整清单与表格列归属；`false`／`[]`／`null`；问题与已提供回复；条件适用性；正式英文名称；数值角色；项目事实与模板／文件名的区分 | 程序校验 key、原生 JSON 形状、实际上下文内引用、词义支持和清单完整性，执行有界 repair／recall、候选合并；用户决定采用。模型建议不能自动成为有效值 | `DraftExtractionHarness.java:16–37,81–151,302–385`；`DraftingService.java:581–685`；普通 `ai.complete` |
| Drafting 原始双证据关系建议 | 两个候选的原始证据包、来源身份和完整上下文；返回一个 relation 对象 | 区分 supplement、explicit_replacement、contradiction、undetermined；共同对象／作用范围及明确修订语言；不能由日期或上传次序推定优先级 | 程序逐项校验来源身份、区间、引用和关系；既有候选合并与人工采用仍生效。每运行最多 4 次、每字段最多一个候选对的模型复核 | `DraftJointEvidenceReview.java:14–48,89–108`；普通 `ai.complete` |
| Vetting 语义审查 | 被提交的 tender／standard／project／manifest 角色化证据窗口及 JSON schema；返回 assessment、type、severity、title、comment、impact、suggestion 和 `{chunkId,side,quote}` evidence | 同主体／触发／范围／语气的真正冲突；最低量与精确量；例外、Not used、修订和引用；采用版本；跨文档协调；具体风险与证据不足的区分；标准原文与项目新增文字的语言审查 | 程序校验 schema、提交 chunk 身份、可定位原文、tender 角色、两侧实质引用与当前来源；只有 `issue` 进入候选，再排序、去重和做覆盖检查。机械通过仍不证明语义判断正确 | `VettingSemanticReview.java:73–86,295–329,342–425,577–614`；`VettingService.java:151–167,220–265`；`completeStructuredJsonList` 或单独 Responses transport |
| Advice 合同问答 | 检索及 incorporation 补充证据、问题、作用范围、明确标为非合同证据的用户背景；返回 grounded、answer、citations、missingReason、clarifications、basisScope | 仅用已给证据回答；跨条款解释、条件／例外和修订；引用足以支持答案；项目合同依据与标准参考依据；缺证据时澄清／未知 | 程序负责当前来源 session、检索、引用 ID 校验、采用／纳入依据和 unknown 判定；无 hits 时直接返回 unknown，不调用 LLM；显式模型失败不改派其他模型 | `AdviceService.java:215–290,672–680,744–813`；普通 `completeJson` 或单独 Responses transport |

上表列出的是当前程序的参与边界。Advice 的 `AnswerRecord` 目前没有单独的 typed flowchart／流程节点字段；流程说明若出现在 answer 文本中，应按其中每个步骤的证据支持评分，不能称当前已实现结构化流程图契约。

传输参数也必须按调用路径核对。`OpenAiLlmClient.java:39–82` 中普通 `chat` 调用 `chat(turns,false)`，只有 structured 路径才添加 `n=1`、`max_tokens`、`thinking` 和 `reasoning_split`；两者都发 model、temperature、stream=false。Drafting 的普通 `complete` 与 Advice 的普通 `completeJson` 不能由 Vetting structured 默认预算推定实际 M3 限额／思考模式；Vetting 的严格 schema 目前由 `AiGateway.java:180–229` 将 schema 加入提示词并在返回后验证，OpenAI client 没有由此保证 native constrained decoding。单独 Responses transport 的输入预算／schema另有契约，应在各自请求中确认。

Drafting 最后生成 NTT／SCT／SCC 的当前入口是源生 DOCX 规则编译：读取原模板、重新解析、核对来源文本、建立 OOXML binding，再调用 `DraftFormattedRules.compile`。此阶段没有要求 LLM 写完整合同；见 `DraftingService.java:899–950`，实际 compile 在第 916 行。因此“提取建议正确”“采用值正确”“条件操作正确”“DOCX 正文正确”“PDF 排版正确”有不同责任和验收，不能用最后文稿问题直接给模型扣分，也不能用修复后的文稿给旧模型提取成绩加分。

## 2. 输入证据完整性先与模型能力分开

需要两层评估，并保留同一题两层输入的对应关系：

1. **给定充分证据的模型基线**：独立冻结原件／源生文本、角色、版本、完整逻辑单元、作用范围和参考答案；每个候选模型拿到相同 system／user 消息及 schema。此层比较提取／判断／回答是否正确，尤其是条件、例外、清单与不确定性。
2. **OER＋模型整链评估**：对冻结原件按实际 parser、chunk、embedding、rerank 和 evidence expansion 路由执行，再送入真实业务请求，评估最后候选／报告／答案。额外记录 OCR 丢字、表格错列、页组缺失、父子条款断裂、检索缺证据、截断和旧版本混入。

模型基线已经给出完整条件但判断错误，才是直接模型语义失败；完整原件含条件而实际模型请求未包含它，应先计为证据供应缺口。若模型在缺失上下文时断言有缺陷或不存在内容，仍是模型的无依据断言，不能用上游缺证据完全免责。这两种失败可以同时出现。

原件质量当前仍受 [OER 解析验收规则](../oer-scope-and-rules.md) 约束。本研究没有把当前探索产物认定为通过固定库／业务／H800 验收，也没有启动新 Drafting 识别。

## 3. MiniMax M3：用户质量参照与可核既有运行

### 3.1 直接官方 API 经验与路由身份

`docs/bonsai-model-tuning-matrix.md:3` 和 `docs/drafting-budget-assessment.md:5` 已记录：用户报告相同 harness 使用 MiniMax-M3 官方 API 可以正确完成，因此先保持 harness／业务规则／证据校验不变，再对本地模型做参数对照。此次用户又明确这是满血 M3 官方 API 的良好 Drafting 经验。它是已经提供的质量参照，不要求用户重新证明才继续研究。

`docs/local-network-and-minimax.md:18–24,95` 的当时部署说明明确区分应用直接官方调用与给开发同事的独立 LAN relay；relay 的两路并发、独立 token 和请求限额仅属于那条路由。当前代码／profile 名称、历史路由描述和用户直接官方测试，应各自保留时间及配置身份。本次未读运行环境、未调用接口，不能确认当前 live 路由或云端权重修订。下面冻结 trace 的 `identityScope=configured_profile` 也只证明应用所配置的身份，不证明远端权重 hash。

### 3.2 冻结 M3 候选审计的准确口径

既有本地证据位于 `.local/state/drafting-m3-audit-20261010/run-8f01b7df/`，运行 ID `8f01b7df-6234-4ffb-b204-2b009f5cadba`，项目 `drafting-exam-20261008-175206-5f89290f`，阶段 SIM15。所有计数来自冻结文件而非本次重新调用。

| 可报告事实 | 数字／身份 | 数字来源和限制 |
| --- | --- | --- |
| 明确非 null 预期字段的正确有引用候选覆盖 | **57／61**；残差 **4** | `model-candidate-audit.json.summary.expectedNonNullInputs/nonNullInputsWithCorrectGroundedCandidate/expectedNonNullResidualInputs`；`strict-original-grade/drafting-score.json.summary` 一致。属于这个已有项目的候选覆盖，不能称盲测准确率或最终文稿通过率 |
| 预期 null 字段出现本轮新候选 | **0／18** | `model-candidate-audit.json.summary.expectedNullInputs/expectedNullInputsWithAnyNewCandidate` 和 79 项 ledger。证明本轮未新填这些字段，不证明页面当前值都为空 |
| 刻意缺证据字段出现本轮新候选 | **0／4** | `summary.deliberatelyWithheldFieldsWithAnyNewCandidate` 与四项对应 ledger；4 项属于 18 项 null 状态中的子集，不能再加作独立样本 |
| 当前已有人工确认／编辑字段 | **65** | `summary.currentlyHumanConfirmedOrEditedFields`。页面既有值和本轮候选分别保存，65 不能作模型正确数 |
| 原严格评分 | **FAIL**；PASS **14**、REVIEW **0**、FAIL **65**；应空状态当前正确留空 **14／18**，刻意缺失 **0／4** | `strict-original-grade/drafting-score.json.summary/boundaryChecks/globalFailures`。评分要求独立、未采用的考试项目，当前 `no_auto_adoption` 与 `no_unadopted_effective_values` 均 false，已有采用／人工值污染了该严格页面状态口径；保留原 FAIL，不能删采用标记改绿 |
| 模型／harness／来源版本 | profile `minimax-cn`；provider `openai`；model `MiniMax-M3`；configured identity SHA `0ea2e85cc5111ada5a893df84920da7c07c9b24ddccdad604486c84cdc0bb947` | `trace.json.modelIdentity`；harness 为 `draft-extraction-20261009.27-media-scope-and-floor-normalization+joint-evidence-20261007.2`；evidenceRevision 为 `b7d3e38060d6d679f35841b9463cd311b87ed5e97f88372897a6d818da9c68a4` |
| 运行记录完成与时长 | completed、stale=false；开始 `2026-10-10T09:29:32.680512400Z`，结束 `2026-10-10T09:36:13.692259500Z`；相减 **401.0117471 秒** | `trace.json.status/stale/startedAt/finishedAt`。仅这个整次识别运行的时间，不是纯模型 decode、首 token、稳定吞吐、并发或价格测量 |
| 输入身份核验 | **15** 份 fixture DOCX 身份与 bytes hash、manifest 和实际 inputs 对应检查通过 | `manifest.json.inputFiles` 与 `strict-original-grade/drafting-score.json.boundaryChecks`。原件和逐字段引用保留；参考答案未作为模型输入 |

18 项 null 的“没有本轮候选”与严格评分的 14 项“当前正确留空”并不冲突：前者只看本轮新候选，后者看已有人工采用项目的当前页面状态。没有新增猜值是有用证据，但不能据此把已有人工填入的四个缺证据字段报成盲测通过。

严格评分另有 `valueInputsWithLiteralSemanticSupport=44` 的字面诊断；该诊断明确不证明完整合同语义蕴含。57 的候选覆盖也需要保留逐项来源、可能冲突和语义核验，不把字面找到引用直接当作正确推理。`nscAlternativeText` 的弯引号变直引号，以及 `otherWaterproofingSpecificationAreas` 的三个空格投影差异分别留账，不把全部 18 项保序清单丢尾误归为这次 M3 失败。

### 3.3 已核四项残差与责任划分

以下归因直接来自 `AUDIT.md:9–12` 和 `model-candidate-audit.json.residuals`，不据此改冻结 harness。

| 字段 | 预期与本轮实际 | 可确定归因／选型考点 |
| --- | --- | --- |
| `oldValuableTrees` | 预期 `[]`；模型提出正确空列表但只引用 “Explicitly none”，没有主语；`unsupported_empty_list` 拒绝，未恢复正确候选 | 值与完整证据必须同时满足；不能把裸 none 作为所有列表不存在的依据。包含被门控挡住的证据质量失败 |
| `allBqQuantitiesProvisional` | 预期 `false`；初次只引 “No”，`quote_value_mismatch` 拒绝；定向 repair 得到完整主语与正确 false，却输出对象而非数组，`invalid_json`；后来 recall 返回 null | 内容判断、引用主体与精确输出协议是不同门；JSON格式错误不能静默变为空结果，扩大 context 也不直接解决此格式错误 |
| `additionalSubmissions` | 预期 `[]`；初次空列表因无主语引用被拒；后来将普通 two-envelope technical-proposal 要求误当额外项目要求，并被接受 | **模型语义错误＋gate miss**。引用存在仍可引错业务类别；题库需要“常规要求与额外要求”的负例，不能只测引用是否原文 |
| `sections` | 预期 `[]`；原文有明确空 Sections schedule，primary 返回 null；有界 8-call recall 预算在较晚来源上下文之前耗尽 | 本轮未恢复的遗漏，涉及 primary 读取和实际有界补查覆盖；冻结基线分别记录原始输出、补查调度及总产物，不能只报最后 UI 值 |

此审计记录的是已有项目上 M3＋当时 harness 的真实边界，足以说明应将 M3 保留为 Drafting 参照，并给候选模型设置上述难例；不足以比较未在同一输入上运行的候选模型，更不足以宣布 M3 的当前版本在新项目上稳定通过。

### 3.4 文稿修复与模型成绩分开

[Drafting 当前现场审计](../testing/drafting-review-live-audit-20261010.md) 顶部 capture04 记录三稿重生成 **29.283725 秒**，无新模型调用，确认值与 trace bytes 不变；其正文／来源作用范围变化属于编译、binding 和用户确认后的执行。该文件当前记录的 111 个动作、65 applied／33 retained／11 pending／2 unresolved、14 个业务待核是文稿计划口径，不能并入 79 字段的模型统计。完整业务与版式验收也分别保留。

## 4. 不同任务的比较指标与失败账本

候选先按任务与供应方式列出，M3 的开发参照、本地候选、赛方供应模型分别有身份。模型参数规模、dense／MoE 或通用榜单只能筛候选，不能替代以下业务结果。

| 任务 | 必报分母／主要质量指标 | 应独立保留的失败类别 | 性能指标与必要条件 |
| --- | --- | --- | --- |
| Drafting | 预期有值字段；预期 null 的 missing／unknown／inactive／optional 分层；完整清单条目与顺序；有正确候选的字段数；错误额外候选；确定值、精确引用、条件适用和来源关系正确数 | 原始模型漏／错；证据供应不全；原文主体丢失；列表丢尾；形状／JSON；gate 拒绝或漏放；repair／recall预算；不同源冲突；人工采用与最终编译另列 | 单请求与整运行时长、输入／最终输出／reasoning tokens、finish reason、请求数、重试数；同 harness、相同 input part、相同预算下比较 |
| Vetting | 按官方／专家审核标签的真实问题数和负例数；issue precision／recall；严重度与类型；定位与两侧证据完整性；一致条款误报；必要 unknown；报告去重与覆盖 | 误把 minimum／exception 当冲突；将 reference-only 当合同；错误版本；标准措辞无依据改写；断章取义；未知当缺失；重复；schema／预算／provider失败 | 按题／主题记录端到端、模型调用和检索成本；输入证据组／schema相同，单项 verdict 与最终聚合分别评分 |
| Advice | 可回答／不可回答题分别统计；答案要点或流程步骤的语义正确；引用支撑率；适用条件、例外、版本和 incorporation；应澄清／应未知的正确率 | 无依据推断；引用 ID 有效但不支持答案；遗漏条件／例外；用户背景当合同事实；参考标准当项目合同；partial unit被当完整条款；模型失败被隐藏 | 完整输入 token预算、检索与模型时长、首 token／总响应、重试、上下文长度、失败率；无 hits 的无模型路径单独记录 |

各任务报告每个分子对应哪些 label／字段／条款／问题、每个分母如何选取，连到冻结原件及实际提交上下文。最终排序应先过质量门，再比较部署内存、时长与恢复；不要把三任务合成一个总百分比，让更多简单题掩盖 Vetting 的高风险误报。

## 5. 冻结可比基线的最小证据包

研究阶段建议先准备证据包，再请求后续真实实验授权；当前不发请求或改现有 harness。

1. **数据与来源**：原件 bytes SHA、完整清单、版本／有效采用关系、sourceHash、source role、正文 projection及正常 parser产物、图表／跨页逻辑单元、sourceStart／sourceEnd、evidenceRevision与scope。固定材料、同项目相关资料和未纳入资料分别标明。
2. **实际请求**：保存真实发出的完整 system／user messages、schema、字段 catalogue、prompt模板及其 hash、每次 repair／recall／joint请求和真实 evidence packets。输入原件一样但分片、schema或额外上下文不一样，不能宣称只换了模型。
3. **模型与路由**：模型确切名称与可查版本，API官方／relay／本地engine、调用 transport、reasoning模式、context和output预算、sampling、retry、timeout；本地另留weights／tokenizer／chat-template／engine digest及quantization。云模型无公开权重hash时明确剩余不确定性。
4. **响应与资源**：原始 provider envelope、最终 content、reasoning字段、usage、finish_reason、失败状态与重试；每请求计时、整运行时间。本地阶段另留独占／共驻说明、模型加载／卸载时间、GPU分配／保留／整卡峰值、CPU RAM和磁盘读入；不把静态权重bytes当运行峰值。
5. **程序边界**：harness／business commit与冻结文件SHA、gate决策及拒绝码、候选合并、repair／recall调度、source-current核验。保留HTTP／JSON／gate／语义／来源／业务输出的独立终态，不能用HTTP200或completed替代正确性。
6. **标签与盲评**：参考答案只送评分器，记录每个label的原件证据与来源范围；至少将题目制定／模型调参接触过的开发集和新项目／未接触的heldout集分开。既有79题和本轮残差已用于开发讨论，应继续用作回归，不能宣称新项目泛化。

现有 M3 trace 能保留每个attempt的kind、systemPrompt、userPrompt、rawResponse、status、context及source identities，但本次检查的trace顶层和attempt没有usage／finish_reason字段，rawResponse是业务保存的模型content，不是完整provider响应统计。因此可以作为已冻结的候选／来源回归证据，尚不能直接给出M3每请求token成本、截断分布或统一reasoning预算。未来对照收据需要在不更改业务harness的授权范围内补齐传输侧证据，不能倒填旧运行未知数据。

## 6. 本次核验的证据身份

以下 SHA-256 本次对冻结文件重新计算，内容没有修改；忽略目录中的原件、trace与运行证据继续保留本地。

| 文件（相对仓库根目录） | Bytes | SHA-256 |
| --- | ---: | --- |
| `.local/state/drafting-m3-audit-20261010/run-8f01b7df/AUDIT.md` | 2,091 | `18f372dee23689c9cba354658446a06cfeee09abe6cf63aa62815595dd3c46ae` |
| `.local/state/drafting-m3-audit-20261010/run-8f01b7df/model-candidate-audit.json` | 178,381 | `c772f490dca4ed9ef56f1ed28e71343f943ae01c7611c1dce267636870eae928` |
| `.local/state/drafting-m3-audit-20261010/run-8f01b7df/strict-original-grade/drafting-score.json` | 267,359 | `c04b696f4eaf2feaa62442add5c6491254dc7fa6f195ac875ba61e9f1aed8134` |
| `.local/state/drafting-m3-audit-20261010/run-8f01b7df/manifest.json` | 7,341 | `9e122b36d425c991643889e92ddac0d00947d99334df8e43fc7f5fa3c72dbd28` |
| `.local/state/drafting-m3-audit-20261010/run-8f01b7df/capture-receipt.json` | 1,184 | `f5f18b70ae30f3ea30d705c3cdc15bb505d6e70b48f12103c62444f0478a82e1` |
| `.local/state/drafting-m3-audit-20261010/run-8f01b7df/trace.json` | 1,245,093 | `7a293469415c75c83c7693e246fe5c46c4e569fd778ebab1a7aab702db373303` |

代码引用指向当前工作区，存在此前工作区实现增量；本研究没有承诺它们已提交／部署。正式复现要把实际业务代码与prompt hash一并冻结。源生生成入口、门控和API客户端的代码证据不等于目标K8s／H800性能或最终业务验收。
