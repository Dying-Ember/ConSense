# Drafting 变量识别：容量、接口及验收边界

核对日期：2026-10-08。此文件记录只读源码核对、当前编译类构造提示词，以及用户指定合成考题的本地解析和 tokenizer 计数。没有通过本次分析自动采用变量，也没有用参考答案构造模型提示词。真实 API 基线识别由另一个独立测试流程执行，其准确率必须依据该流程的逐项结果报告。

后续任务约束：用户补充相同 harness 使用 MiniMax-M3 官方 API 可以正确完成，因此本轮 **保持 harness 和业务代码不变**，先做模型 context/output/thinking 参数对照，具体见 `docs/bonsai-model-tuning-matrix.md`。本文末尾 grounding 建议仅记录潜在风险，未获此次实施授权，也不作为当前调参依据；不能仅因潜在风险扩大修复范围。

## 结论

对于指定的 `Ref/simulated_2b_correspondence/03_missing_evidence_test` 变量识别考题，**32K 上下文足够容纳当前应用实际提交的单次输入**。15 份通信 DOCX 经当前 `DocumentParser` 解析成 49,196 个 UTF-16 字符，由现有分片逻辑分成 23 个 primary 调用。包含完整 79 项目录、协议和当前服务器 chat template 的单次有效提示词为 **3,413–4,088 tokens**，不是将所有通信或完整标准合同一次送入 32K 窗口。

**2,048 输出上限与 thinking 关闭仍可能造成截断、遗漏和判断质量问题。**扩大上下文不会自动改变现有业务分片、引用校验、漏项补查次数或合并规则。应先保留基线逐项结果，确认失败发生在模型输出、证据校验、分片边界还是候选合并，再进行对应修复。

完成实测后追加：本批 32K/off/2K 基线单次最大生成 1,592 tokens，没有观测到 2K 上限命中；不能把三项失败直接归因于输出截断。64K/16K/off 控制组仍为 58/61，并新增一个真实引用的语义冲突；64K/16K/medium4K 为 60/61，补齐 Bill 清单和明确空 Sections，但仍有一项防水长文本 FAIL，耗时增加约 5.93 倍。防水两个实际请求分别不含完整 18 项，同时模型存在字面改写、引用配对及值结构错误。server ctx-size 不等于 harness 实际 sourceText 长度。对照没有改动本页下方的业务 grounding 建议，也没有用参考答案调整 harness。

完整 NTT/SCT/SCC 的当前生成入口是规则驱动的原始 DOCX 编辑和编译；它并非要求 LLM 在一次 2,048-token 回答中写完整合同。参见 `ai_consense_service/src/main/java/com/consense/service/drafting/DraftingService.java:848` 和其中 `DraftFormattedRules.compile` 调用。容量问题仍需要处理，但不能用整份合同长度直接推导当前变量识别单次所需上下文。

## 实际考题输入计数

本地 helper 使用当前 `target/classes` 中真实 `DocumentParser`、`DraftClauseRules.chunks`、`DraftSourceContext.primary` 和 `DraftExtractionHarness`。只读取指定 15 份上传 DOCX；参考 JSON、TXT、参考答案表和完整主包没有进入提示词。

| 指标 | 观察值 | 证据 |
|---|---:|---|
| 通信 DOCX 数量 | 15 | `.local/state/capacity/drafting-exam-prompt-fixtures.json` 的 `documents` |
| 成功解析数量 | 15 | 每份 `parseStatus=PARSED` |
| 解析后总字符数 | 49,196 UTF-16 字符 | `documents[].parsedUtf16Chars` 之和 |
| primary 分片数量 | 23 | `cases` 数量，尚不含修复、recall 或联合证据调用 |
| 单次实际输入 token 最小值 | 3,413 | SIM12 part 1，`.local/state/capacity/drafting-exam-token-counts.json` |
| 单次实际输入 token 最大值 | 4,088 | SIM09 part 0，同一计数收据 |
| 23 次输入 token 合计 | 87,779 | `observations[].tokens` 之和；是多次调用工作量，不是单次上下文占用 |
| 最大原文上下文 | 3,478 UTF-16 字符 | SIM14 part 1，原文窗口收据 |
| `context_insufficient` 分片数 | 0 | 所有实际考题 primary 原文窗口 |

SIM08、09、11、12、13、15 各两片；SIM14 三片；其他八份通信各一片。核心分片上限 3,000 字符，邻域补充窗口上限 6,000 字符。当前实际考题不存在因窗口超过 6,000 字符而被标记为不足的 primary 分片。

计数方式是当前本地服务器 `/apply-template` 渲染完整二消息提示词，再用 `/tokenize` 精确计数；没有发起生成。服务器当时的模板为 thinking 关闭模式。输入计数证明窗口容量，不证明实际输出准确率或长上下文推理质量。上述 helper 默认提示词与当前编译协议一致；若数据库自定义提示词发生变化，真实 API 请求还须重新比对。

复算文件：

- `.local/state/capacity/DraftingFixturePromptStats.java`
- `.local/state/capacity/measure-application-fixtures.ps1`
- `.local/state/capacity/drafting-exam-prompt-fixtures.json`
- `.local/state/capacity/drafting-exam-token-counts.json`

## 必须区分的限制

| 层次 | 当前行为 | 源码位置 |
|---|---|---|
| 服务器上下文 | `--ctx-size` 才决定 llama-server 的实际窗口 | `.local/runtime.json` 的 `Model.Arguments` |
| Java `numCtx` | Ollama 适配器会发送；OpenAI 适配器不发送上下文尺寸，只参与本地配置身份/预算信息 | `OpenAiLlmClient.java:47`、`OllamaLlmClient.java:59`、`LlmProfiles.java:37` |
| 普通输出 | Drafting 抽取、联合证据和 Advice 走普通 `chat`，没有显式 `max_tokens`，受服务器默认 `--n-predict` 控制 | `DraftExtractionHarness.java:281`、`DraftJointEvidenceReview.java:44`、`AdviceService.java:193`、`OpenAiLlmClient.java:53` |
| 严格结构化输出 | Vetting 的 `chatStructured` 显式发送 `structuredMaxTokens`，当前本地启动脚本由 `Model.MaxOutputTokens` 绑定 | `OpenAiLlmClient.java:55`、`scripts/local.ps1:160` |
| 原文窗口 | Drafting 核心 3,000 字符、邻域 6,000 字符；ctx-size 增大不会自动扩大 | `DraftingService.java:69`、`DraftSourceContext.java:13` |
| 漏项回查 | 一轮最多 8 次 bounded recall，且需要目录路由词命中原文 | `DraftExtractionHarness.java:163`、`:205` |
| 联合证据 | 一轮最多 4 次，关系建议不自动改写或采用候选 | `DraftJointEvidenceReview.java:14`、`:27` |
| 超时 | 前端默认 600,000 ms；extract 没有单独延长；后端 HTTP 采用配置超时；Vite 代理 3,600,000 ms | `ai_consense_web/src/api/client.ts:23`、`src/api/index.ts:90`、`vite.config.ts:23`、`HttpSupport.java:77` |

`collectTemplateText` 等旧 helper 的 8,000/10,000 字符常量没有当前调用点，不能据此宣称当前合同生成截断到 10,000 字符。

thinking 启用后必须继续让思考文本与 `message.content` 分离；Java OpenAI 适配器只读取 `message.content`，不读取 `reasoning_content`。严格 Vetting 若将 `<think>` 和思考正文放进 `content` 会破坏纯 JSON 契约。适配器目前没有发送 llama.cpp 的每请求 `reasoning_effort`；现有 `thinking.type=adaptive|disabled` 扩展主要为别的兼容服务准备，不能未经验证当作 Bonsai 的 `medium` 控制。

严格 Vetting 要求 `finish_reason=stop`，`length` 会按 `OUTPUT_BUDGET_EXHAUSTED` 拒绝。普通 Drafting/Advice 目前没有相同的完整终止检查：输出预算耗尽后主要由 JSON 解析失败体现；如果截断输出恰好仍可解析，就存在未显式标记不完整的风险。参见 `OpenAiLlmClient.java:101` 与 `:128`。

## 其他模块的容量对照

以下数字来自自写合成文本和真实默认提示/源码包装，不是实际合同业务长度或质量结果。它们用于说明“2,048 输出”与“32K 输入加输出”需要分别配置。

| 合成场景 | 英文 tokens | 中文 tokens |
|---|---:|---:|
| Drafting 3,000 字符原文加完整 79 项目录 | 3,879 | 4,867 |
| Drafting 6,000 字符原文加完整目录 | 4,500 | 6,477 |
| Vetting 10,000 字符正文加真实 source-wire、系统与 schema | 6,444 | 10,083 |
| Vetting 20,000 字符正文加真实包装 | 11,068 | 18,461 |
| Advice 16,000 字符摘录加默认提示 | 3,604 | 8,880 |
| Advice 30,000 字符摘录加默认提示 | 6,500 | 16,392 |
| Vetting schema 允许的最大长度字段合成 JSON | 3,725 | 12,332 |

证据是 `.local/state/capacity/application-token-counts.json` 和对应 `application-prompt-fixtures.json`。Advice 表没有计每个切片的文件/页码/anchor 标签额外开销。Vetting 源码默认 initial context 10,000 字符、一次 expansion ceiling 20,000 字符、project-reference 12,000 字符（`ConsenseProperties.java:134`、`:136`、`:146`）。其 schema 每次最多三项，每项最多四段 quote；每段 quote 最多 1,200 字符，comment 最多 1,200、impact/suggestion 各 600、title 160（`VettingOutputSchema.java:15`）。这是 schema 允许的容量压力例子，不是日常一定产生如此长输出。

例如中文合成 20,000 字符 Vetting 输入 18,461 tokens，加 16,384 的输出预留合计 34,845，已经超过 32,768。若启用思考，生成预算中还需要给推理消耗留空间。因此全项目大余量配置可评估 64K/16K 或 128K/32K；显存加载成功、短推理成功和长合同质量是不同验收项目。对于当前变量识别考题，应优先按基线实际失败原因调整输出/协议/校验，不必将更大窗口作为准确率前提。

## 独立考题项目的真实 API 流程

所有业务响应包装为 `{code,message,data}`；成功的业务 `code=0`。测试必须用新的独立项目，不能以已有缓存、人工值或历史识别作为本轮成功证据。

1. `POST /api/projects`：JSON 至少 `{id,nameZhHans}`，其余名称可选；测试元数据避免带正确答案。
2. 标准模板分别 `POST /api/drafting/{id}/templates/{NTT|SCT|SCC}/replace`，multipart 单字段 `file`，使用 `Ref/2026-09-04_Documents to Shortlisted Candidates (Contract Advisory)/2a_Standard documents` 中原始 NTT、SCT、SCC。显式 key 比文件名自动匹配更容易审计。也可使用 `/templates/upload` 的重复 `files` 字段。
3. 仅将缺失证据变体的 `01_upload_documents` 15 份 DOCX 作为重复 `files` 字段上传 `POST /api/drafting/{id}/inputs/upload`。不得混入完整主包、2b 原件或 `02_reference_do_not_upload`。
4. 查看上传响应 `{accepted,parsed,failed,messages}`，并 `GET /inputs` 核对各文件 `status=PARSED`；`GET /templates` 核对三个模板已解析。`accepted` 是接收文件数。
5. `POST /api/drafting/{id}/variables/extract`，空请求体，`X-ConSense-Llm-Profile: local` 固定当前本地 profile。这是**同步请求，没有 Drafting 异步 job**。测试工具应使用足够长的单次超时；浏览器超时后不要立即重复启动相同识别。
6. `GET /variables/extract-trace` 与 `/variables/extract-traces` 读取回执；指定 run 可用 `/variables/extract-traces/{runId}`。失败时也保留 failed trace。运行中不保证最后 trace 已代表本次完成态。
7. `GET /variables` 按当前 79 项目录和原始证据逐项评估。不能调用 `confirm-all` 或自动 PUT 正确答案来制造识别成功。`GET /plan` 可核对派生/适用条件，但它只把已人工采用或确认的值当作 effective adopted values，未采用的建议不是最终合同决策。

接口位置：`DraftingController.java:49`、`:67`、`:83`、`:88`、`:93`、`:98`；profile header 在 `LlmProfiles.java:14`。上传、解析、模型调用及最后短事务保存位于 `DraftingService.java:298`、`:543`。

`VariableVO` 包含 key、value（序列化字符串）、source、note、candidates、confirmed、manuallyEdited、reviewRequired、adoptionState、validationIssue。每个 candidate 还含 sourceDocumentId、fileName、sourceHash、sourceQuote、reason、confidence。`ExtractTraceVO` 包含 runId、modelIdentity、harnessVersion、evidenceRevision、status、failureCode/Message、parts/attempts、decisions、fields、relations；原文窗口提供 sourceStart/sourceEnd/sourceText，可检验 quote 是否来自实际上传文档。

## 评分口径与防止伪成功

参考答案 79 项的状态是 **provided 60、derived 1、not_applicable 11、optional_unchanged 2、intentionally_unknown 1、unknown 4**。不得把“其余 75 项”解读成 75 个都应非空的答案。来源和状态清单见指定变体参考 `expected_values.json` 与 `变量对照表.md`；这些文件只用于评分。

四个刻意缺失项为 `photocopyRateAboveA3`、`projectArchitectPhone`、`siteInspectionEndDate`、`quotationBeforeVariation`。参考 JSON 使用 null，当前 API 对没有取值的 editable input 正常返回空字符串；应要求无取值、无候选误填、`confirmed=false`、`manuallyEdited=false`，不应把 API 的空字符串直接误判为编造。不能自动填 0、false、其他纸张费率、开始日期或固定推测日期。

`periodAtLeast39Months` 是可从实际月份推导的阈值结果。`DraftBusinessRules.java:29` 会在采用值条件下写入 `effectiveValues`；变量识别准确率评分要区分源文取值和规则派生，不能强迫模型重复猜派生答案。

建议每项记录期望状态/值、实际变量值、候选值、原文 quote、sourceId/hash、decision status/codes，以及错误是否源于模型遗漏、输出截断、grounding 拒绝或候选冲突。格式成功、trace completed 和 confidence 高都不是业务准确率证明。

当前主要通用风险：79 项目录中单片缺省项依赖后续有限回查；对大部分 Boolean，现有 grounding 主要校验 quote 原文存在而不是该 quote 语义确实回答该 Boolean；对数字，字面同值可能属于另一个相邻字段。后续修复应建立按字段语义/原文回答片段的支持检查，并用“问句仍未知”和“相邻字段相同数值也不能借用”测试验证，不能硬编码这套考题的缺失键或正确数值。

## 待基线结果确认的通用 grounding 修复方案

此部分是设计建议，尚未修改业务代码或据此宣称准确率已经提高。

### 接缝

保留 `DraftExtractionHarness.decide` 作为 intake、原文存在、shape、confidence 和决策留痕的唯一入口。在 `DraftCandidateGrounding` 扩展纯函数支持判定，将“字面存在”和“该片段在回答该字段”分开。输入至少为 InputSpec、规范化候选值、exact quote，以及该 quote 在 original context 中的范围；输出采用 supported/unsupported/unknown 和稳定拒绝原因。unsupported/unknown 都不成为 accepted candidate，不把它们改造成 false、0 或空清单。

新规则仍用 `quote_value_mismatch` 作为兼容拒绝码，另加细分 diagnostic，例如 `source_is_request`、`field_scope_mismatch`、`numeric_unit_mismatch`、`boolean_answer_not_established`。这样现有一次 targeted repair 能从同一原文修正 quote/value，并且已接受的其他字段不被替换。直接改一个全局 prompt 无法补齐服务的证据约束。

### 规则范围

1. **先找可回答的原文片段。**问句、please confirm/provide、whether、pending、unknown、not supplied 和条件提议不能单独支持一个确定值。判断要限于候选实际支持片段，不能因后面一个无关问题拒绝前面已明确陈述的答案。原表格的 request 和 reply 仍分角色，不能把 `Architect to confirm... | 实际回复` 整行简单视为未回答。
2. **Boolean 需要自己的主题、肯定或否定关系。**不能继续对未列举的 Boolean 无条件 `return true`。明确 Yes/No、declared included/excluded、selected/not selected、required/not required 可支持相应结果；疑问、范围不明或其他事项的 No 保持 unknown。不要用日期先后、文件名、默认模板或 absence of answer 推导 false。
3. **数值需要字段语义与单位绑定。**数值 token 出现并不充分；纸张费率、月份、天数、数量、页码、条款编号或其他项目数字不能互换。绑定应来自版本化 catalogue 的业务语义/可审计 evidence policy，而不是某个文件名、考题来源、缺失键清单或正确数值。对于共享主题的相邻字段还要保留限定词，例如 up to/above、start/end；相邻字段即使数值相同也不构成本字段证据。
4. **日期和电话保留已有标准化能力，增加主题/端点限制。**电话不能借用同一 quote 中的合同号；结束日期不能借用唯一开始日期。保持 ISO 日期、共享月份范围、格式不同但同号电话的现有兼容。
5. **不声称靠一个通用正则完成 79 项语义验收。**确定的 supported 判定覆盖可解释规则；无规则或证据不充分时保留 editable unknown，并把不支持原因显示给人工。这可能降低自动填充数量，需要用 provided 项召回和缺失项误填率同时衡量，避免只追求不误填而把所有合法值都拒绝。

### 测试入口

最合适的现有 API seam 是 `ai_consense_service/src/test/java/com/consense/service/drafting/DraftingExtractionHotfixIntegrationTest.java`：使用真实上传、extract、variables 和 trace 接口、独立 H2，仅替换外部 `LlmClient`。可以扩展此类或创建同目录 `DraftingCandidateGroundingIntegrationTest`，直接验证错误模型输出不能进入 candidate 或 adopted value，并验证 bounded repair 的原始拒绝回执仍保留。

已有更广的 quote/date/phone/数字边界测试位于 `ai_consense_service/tests/external-fixtures/java/com/consense/service/drafting/DraftingQuoteGroundingIntegrationTest.java`。当前默认 Maven 构建未把这个目录作为 test source；仅修改该文件并运行普通 `mvn test` 不足以证明新测试实际执行。新回归测试应放在默认 `src/test/java` 或明确配置、核对真实执行报告。

建议的最小有意义用例：

- 同一个 Boolean 的问句分别被模型填 true 和 false，均拒绝；同主题明确 Yes 与明确 No 均接受且不自动采用。
- 一条无关问题出现在明确回答之后，合法字段仍接受；request/reply 同行时只让 reply 支持值。
- 邻接两个数值字段，用另一个字段的确切 quote 和相同数值回答当前字段仍拒绝；两个主题各有明确回复则分别接受；正常小数比例和货币格式规范化不退化。
- 仅开始日期时模型复制为结束日期必须拒绝；明确完整范围正常支持两个端点。
- 回查/修复未能建立支持时 value 保持空、无 accepted candidate、confirmed/manuallyEdited 为 false，并有原文和拒绝码；失败不被改造成默认值。

这些用例使用短小自写材料及其他业务字段和值，避免专为 03_missing_evidence_test 硬编码。待真实基线逐项结果明确后，应先修复已观察且可重现的最小一组规则，再运行 provided/unknown/不适用状态完整验收。
