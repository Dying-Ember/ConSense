# MiniMax 完整输入预算与原生 provenance 开销的只读核查

日期：2026-10-10，Asia/Shanghai。依据 [当前范围规则](../oer-scope-and-rules.md)、[handoff](../HANDOFF-ocr-embedding-rerank-20261008.md) 和 [实际 G305 Vetting 收集](./oer-g305-real-vetting-report-collection-20261010.md)。只读取当前源码、同轮冻结源码与已保存实际输入；没有修改生产代码、切换 profile、调用 tokenizer、HTTP counter／generation、index 或 job。技术结论与合法接入前提单独记录，不把提案写成已完成。

## 最新源码接入：显式 MiniMax Vetting 可选择同 wire Responses

本节优先于下文只读调查阶段的“当前未接入”表述。final-v3 已经独立审阅并精确应用到主源码，默认 `consense.vetting.minimax-cn-wire=chat-completions` 保持；仅显式 `X-ConSense-Llm-Profile: minimax-cn` 且配置 `responses` 时选官方 native wire。Drafting／Advisory 原路径保持。新 `VettingLlmOperation` 在队列入口捕获实际 wire／profile SHA，queued、stored、progress、result 记录 scope=configured_vetting_wire；配置身份不当供应商执行证明。thinking disabled→reasoning none，adaptive→high，unknown 拒绝；输出预留 16,384，context／margin 没有为通过预算而改小或改大。

同一 bound Input 的 preview／final count／generation 共享 immutable PreparedRequest；每次预算检查仍 fresh count，缓存只存 wire artifacts，不缓存 verdict。schema 只装一次，count／generation 的 model／instructions／input／reasoning 相等；unknown／over／drift／incomplete 保持既有门控。Responses 仅用独立配置的官方 HTTPS root／服务端 key，不借 Chat endpoint／中继访问 token；目前中继仍不提供 Responses route。semantic disabled 不提前校验不会用到的 native 配置，实际 enabled incomplete output 仍持久 FAILED。

候选 `.local/state/vetting-explicit-responses-candidate-20261010/run-142600/`：manifest SHA `2abab7fdeb6ac959e9301c5d2e5ab51dccec4036c954218967c152119ba68abd`，validation SHA `e8da83d1eb6d1b1abe95af9e32a792375f12964b39ae2d5fda4e9fae815102a7`。作者实际 11 suites／98 tests 全绿，包括 4 个实际 MVC→filter→async queue→POI→isolated H2 的 fake-wire 工作流，0 provider call。root 新 copied checkout 独立实际 5 suites／58 tests 全绿，墙钟 31.7383423 秒，review SHA `cbfb84227f163738a3fb85eae03b0ff525b9b793f50e8c05dd5e69d368faf647`；runner 额外请求的 VettingTokenAwareHarnessTest 不存在，执行 0，不称 6 suites。这两分母有重叠，不相加。

精确应用 9 文件（6 产品＋2 新 tests＋1 更新旧 integration test），source/resource 全闭包 568→571 与已测试 candidate 逐 hash 相等，其他源码不改；备份及 apply receipt `.local/state/vetting-explicit-responses-independent-20261010/root-source-apply-01/`，SHA `ff2a0135aaaf8703200621510dbad960ddb03814c3221930f323790466acc376`。主 target／Jar／在线配置未写入，尚未激活真实合同调用。旧 SemanticReview 20 tests 中两项在准确 baseline 也失败；只读归因指向旧 decoder 将新版 metadata envelope 当 array，后续迁移 fixture 不得放松资料隔离／来源门控。

源码应用后的 `git diff --check` 指出 transport 一行空白含尾空格，随后只去掉这一个空行的 8 个空格，Java tokens 不变，未重复模型／测试。该局部规范化保存在同 apply 目录 `whitespace-only-receipt.json`，当前 `VettingResponsesTransport.java` SHA `0d7b6a926c3d05b76a486420cd1caf21f618357c52519d984cdbbf015f4c5314`；原精确应用收据继续代表规范化前的 final-v3 字节，不声称当前这一文件仍为原 candidate SHA。主 target／服务不受影响。

两条旧 fixture 随后完成独立只读归因和最小迁移：完整 metadata envelope 改用 MetadataWire decoder，第二条移除与独立 project-reference 比较目的无关的未填 SCC 模板及其专属断言／ID；target＋完整 fact／native header 和第一条合法 GCC standard-only 拒绝保留，没有产品门控变化。新 copied checkout 的原 20 cases 全通过，suite 1.497 秒、Maven 3.621 秒；准确单文件 5 additions／9 deletions。validation SHA `7cd7417f716d1bf4914f2407cd98c7853b4f32ec3704286e9a0d3ea1e9db37ad`。root 已精确应用这一 test 文件，当前 source/pom 571 文件闭包除该项均相等；apply receipt `.local/state/vetting-semantic-fixture-migration-20261010/root-source-apply-01/apply-receipt.json` SHA `908bf0aae6153e70271c08b976f7cf82181045d66ed0a99c0d47f977c88e5c28`，test SHA `3ad5ec7fc359e8eefa4831dc78b01ab29ab5f6c9b511e187d1df9a77cdbda86d`。两次 runner 在测试前失败（各 0 cases）保留，未加外置 agent／额外观测；未输出的 exact packet status／budget／aggregate verdict 保持 unknown，不将 mock 通过称真实业务验收。

另一次先前真实小输入 Responses probe 位于 `.local/state/minimax-m3-same-wire-probe-20261010/run-130600/`：counter 179，generation usage 179＋6，输出 `{"result":5}`，两次 HTTP200，reasoning none，0 retry／fallback。它只证明本轮账号和小 wire 输入能调用，不证明合同、长输入、完整 Vetting 或前端验收；本次源码应用新增真实调用为 0。旧 partial 和 source-request 未提交结果不追认通过。

## 历史调查：当时 UI MiniMax 路径不能通过配置获得合法 token 计数

真实 G305 job 使用显式 `X-ConSense-Llm-Profile: minimax-cn`，走 MiniMax Chat Completions，中继 `8092`，结构输出 16,384，`thinking=disabled`。模型／配置身份和完整提示原字节见上述收集记录；17 个 global packet 真实调用并返回空数组，102 条额外 source-request episode 因 `budget_unknown` 保留未提交。

[VettingSemanticReview](../../src/main/java/com/consense/service/vetting/VettingSemanticReview.java) 的选择规则为：

- `responsesEnabled()` 要求 `!ai.explicitProfileSelected()`，同时 `vetting.responses.enabled=true`。
- `selectedObserver()` 对显式 `minimax-cn` 返回 `null`；不会把本地 tokenizer observer 套到官方模型。
- 显式 profile 的完整 Input 绑定当前 `provider/model/configurationSha256`、system/user/schema、输出预留和 source snapshot。不能只打开 `token-budget-observer-enabled` 或 `responses.enabled`，声称当前前端 M3 请求已经被计数。

这个 null 与实际 `no_bound_token_observer` 一致。保留未知是正确的证据边界，不能通过放松字段校验、填一个常数或改写状态消除它；需为拟用 wire 建立真实 observer。

## CLI observer 的适用前提

[VettingTokenBudgetCliObserver](../../src/main/java/com/consense/service/vetting/VettingTokenBudgetCliObserver.java) 仅处理 opt-in 的本地 sealed recipe。它要求当前 provider/config SHA、真正 tokenizer identity、chat template identity、effective context identity、输出预留一致，实际 executable／software artifacts 和 recipe 的 SHA 逐读复验；完整序列化 Input 经 UTF8 stdin 送入计数器。返回必须说明观察了完整 chat template 和 schema envelope，并绑定相同 `serializedInputSha256`。

该工程接口适合本地自管模型且有真实 tokenizer/template／实际部署配置的情形。M3 官方服务的隐藏 tokenizer/chat template 没有因接口存在而可得；本轮没有取得它们。不能使用 Bonsai、BGE、其他模型 tokenizer，不能把按字符估算或仅 JSON tokenizer 结果标为 exact M3 观测。比赛模型确定后如提供真实 tokenizer/template 与部署上下文，可为该模型制作新 sealed recipe；本轮 H800 模型／部署方式仍未确认。

## 既有 Responses transport 可作为新的供应商估算路线

[VettingResponsesTransport](../../src/main/java/com/consense/service/vetting/VettingResponsesTransport.java) 已有独立 default-off、Vetting-only 路线。`PreparedRequest` 冻结 generation body 及 counter projection；计数端点为 `/v1/responses/input_tokens`，生成端点为 `/v1/responses`。两者共享 `model/instructions/input/reasoning`，schema 明确写入 system instructions；在 count 和 dispatch 前后重新核完整 Input、wire/profile/context policy 与 schema。输入/输出预算失败、未知或漂移不能自动回退本地生成。

其预算结果为 **`provider_reported_estimate`／`provider_estimated_fit`**，`completeChatTemplateAndSchemaObserved=false`。输入 token 是供应商估算，配置 context cap／安全余量是显式操作政策，均不是精确隐藏模板、账号实际容量、模型测得最大上下文或 H800 硬件限制。源码 `checkEstimate` 区分了这两种口径；不能为通过 CLI 门控而伪造精确 tokenizer/template SHA。

该路线可为**同一个新 Responses wire 的完整输入**提供可绑定估算，不能把 Responses counter 的值套给当前 `/v1/chat/completions` wire，或追认为已经结束的 G305 job 被预算验证。`inputFromGateway` 是文件回放接缝：只接严格相同的保存提示/schema，重新构建 Responses PreparedRequest；即使 system/user 文字一样，wire、reasoning 和 provider configuration 身份仍改变。

当前实际接入限制为：

| 项目 | 当前实际源码要求／差异 |
| --- | --- |
| Endpoint | `normalizeBase` 只接受 `https://api.minimax.cn`，无代理、redirect 或隐式 retry；不接受 `http://127.0.0.1:8092` |
| 凭据 | Responses 专用 server-side key；中继访问 token 不能当官方 API key |
| 现有中继 | 根目录 `scripts/minimax-relay.py` 仅允许 `/health`、`/v1/models`、`/v1/chat/completions`，无 Responses／counter 固定路由 |
| profile | 现有 UI 显式 M3 profile 会绕过 default-off Responses；必须显式设计使用哪个 transport 并保留真实 operation identity |
| 推理模式 | 当前 Chat 是 `thinking=disabled`；Responses Profile 校验固定 `reasoning.effort=high`，这是新的模型配置，不能当只加计数器的单因素对照 |
| context policy | 默认 configured cap=0、policy 未知；必须绑定实际部署的显式政策与输出预留、安全余量，不能把旧 H800 规划或历史 1M 操作值默认为本轮已测容量 |
| 真实运行 | 此次只读核查无新计数／生成；现有 fake-HTTP tests 的源码存在不替代当前真实端点、配额或业务质量验收 |

旧 `vetting-experiment-log.md`／`vetting-pipeline-observability.md` 记载历史真实 Responses counter，但对应 `tmp/vetting_foundation_hardening` 工件不在当前工作区；本轮未复核它们的原 bytes，不把旧计数追认为当前 G305、当前 profile 或当前账户状态的证据。官方协议参考入口为 [MiniMax Responses input tokens](https://platform.minimax.cn/docs/api-reference/responses-input-tokens)；本轮未重新访问该页面，也未调用端点。

若后续采用既有路线，工作应先形成新完整 wire/profile 身份与有限静态中继路由（如确需复用中继），保证 key 留在服务端，再对冻结实际样本做 count-only／必要 dispatch。真实 profile、count、generation 绑定同 PreparedRequest，unknown 保持 unknown。不应隐藏移除 UI header 来绕过 profile 身份，或将未知计数请求自动重复发送。

## 实际 metadata 膨胀归因

已保存首个 G305 实际提示：system 6,722 UTF16 字符；user 274,586 UTF16 字符；source wire 265,383 UTF16 字符／265,384 UTF8 bytes，SHA `6ffb1b686b6374f3c0a78e7db2844a8b02c72e008fdf71a888cd5d30278f82d2`。16 records 的 `content` 合计只有 1,117 UTF16 字符。现有 `lossless-source-record-columns-v2` codec 确实生效，不是未开启压缩。

编码态 16 个 `canonicalStructure` 值合计 256,474 UTF16 字符，占 source wire 96.64296507312073%。34 个 native parts 为 15 个非空独立段落＋整表19段落；完整表8 blanks／15 cells保留。拓扑／位置／审计引用与来源身份大量重复，模型输入开销主要来自这里。

| 编码态可直接计算的字串 | 实际数量与字符成本 |
| --- | --- |
| SHA-256 纯字符串 | 916 occurrences、109 个 unique values，字串本身 58,624 字符 |
| 含 OOXML namespace 的 Clark path 字符串 | 188 occurrences，字串本身 58,739 UTF16 字符 |

下表是**展开后**独立字段值的 compact JSON 字符归因，不是编码态 wire 的可加成本或已实现节省量；不能与 265,383 相加，也不是 token 数：

| Canonical field | 展开值 UTF16 合计 |
| --- | ---: |
| `parts` | 192,909 |
| `sourceSpans` | 26,020 |
| `tableGroup` | 23,758 |
| `adoption` | 16,833 |
| `relations` | 9,032 |

34 parts 的较大展开字段为 `nativeNodeRef` 46,535、`nativePropertiesRef` 24,737、`nativeTokensRef` 24,499、`candidateProvenance` 19,433、`sourcePosition` 18,636、`anchor` 11,938 字符。原件字节 SHA、source revision、observer、artifact descriptor 与 Clark path 在 node/token/property refs、parts、sourceSpans、table topology 中重复出现。

[VettingSourcePromptWire](../../src/main/java/com/consense/service/vetting/VettingSourcePromptWire.java) 当前主要共享同字段顺序 records 的 common values 和列内 exact value tables。字典只在该列内部生效；不同 refs 的 `jsonPointer`／payload SHA 不同，跨结构的 sourceIdentity／sourcePosition 仍多处复制。含 `content/cells/text/quote/raw*` literal 后代的整列不进 valueTables，是保护原文的有意边界；不能简单删这个 guard，避免把原文字面对象／数字误作 transport 标记。

## 无损对策与不可混淆的边界

2026-10-10后续实施：下文的v3提案已有独立源码／离线checkpoint，见 [请求级metadata共享验证](./oer-vetting-source-metadata-sharing-20261010.md)。保存G305 source wire265,383→159,156 UTF16字符、全有序树往返相同；63项业务／编码／预算回归通过。字典值仍在同一模型输入，没有sidecar字段投影、没有删除原文／表格／unknown。尚无新M3调用／token观测／在线发布，因此下文“本次未实现”保留本篇只读调查阶段事实，不能追认新配方神经效果或已结束G305预算通过。

优先可评估 request 级共享的**非 literal metadata 对象／字串字典**：完整 SHA、descriptor、sourcePosition／namespace/path 字串只保存一次，各原字段以有类型、明确 legend 的索引引用。完整值继续存在于同一实际模型输入；解码严格还原原字段顺序、类型、数值字面量、null、quality、unknown、relations、全部19表段落／8 blanks／cell/topology／channel。本文没有实现该 v3 codec，未产生字节节省或 token／模型效果结论。

采用新编码必须用 actual G305 与完整 PRE／跨页组、literal 伪 transport 标记、unicode、blank／nested、编辑状态及数值字面量做独立 roundtrip；保留完整 CAS/source payload、实际输入 schema/profile、编码版本和新的 input SHA。只共享 metadata，literal 字段及后代继续原样保留；为模型可读性给出稳定短编号与语义标签，不能指望模型自行解压任意二进制。

HTTP gzip 只降低网络 bytes，服务解压后的模型输入不因此减少；base64／gzip字串不能直接作为模型可读原件，也不构成 token 观测。直接删除 provenance、把整表拆成部分 cell、丢 blanks、只传正文或截断较长字段，均不能冒称当前完整输入的无损优化。

另一个可讨论的工程路线是新的语义 prompt projection：完整审计 payload 保存在 sealed sidecar，模型只看到必要原文／完整相关表结构、层级、身份与 unknown，引用由 harness 绑定至全证据。它可能更适合业务模型，但**不是当前 prompt JSON 树的无损编码**，应独立声明输入配方变化、保留未发送字段清单和完整原始证据，并重新验证 quote/citation、条件覆盖、表单 blanks、法律结论与正式业务样本。不能把这种字段投影的结果当作仅换参数或沿用旧 input SHA 的同输入成绩。

## 当前收据与冻结源码

实际 provenance 归因收据为根目录忽略的 `.local/state/oer-g305-vetting-job-20261010/run-040348-91faa2/input-provenance-audit-receipt.json`，SHA `36bf58d9dc95dea9ed833dc3fb7e3ad19185c9f75380e84214df18fe5b355995`。脚本 `audit-input-provenance.py` 只解析已保存 actual prompt，`inputTokensObservedOrEstimated=false`、`modelOrCounterOrIndexCallsIssued=false`、`losslessOptimizationImplemented=false`。

本次实际读入的五个当前源码与 G305 Jar 的冻结 copy 逐字 SHA 一致：

| 文件 | SHA-256 |
| --- | --- |
| `VettingInputBudget.java` | `a5a8d62fbf4c34bdbb8bdde30bbf809f66b3f2b2fabab0e895a087a8d89390c6` |
| `VettingTokenBudgetCliObserver.java` | `eb0942a0911f4696e03ee379872fd3553d80dd93629f24b9c3347cd43229baf2` |
| `VettingResponsesTransport.java` | `afa4bd9a5755cb04d7fa0264e3c09e6cebe32b19498cb377997615750962a0d5` |
| `VettingSemanticReview.java` | `6c2365ddb9b743da082bfdf34cb85ea2a20c1d633e87e545612d940bed873daf` |
| `VettingSourcePromptWire.java` | `198aa56bdf47cc2fedf38cc10d9a619eeb4814fabbe1cc6eeeff1edb54bab517` |

完整来源、观察身份、日志和 actual inputs 留在忽略本机目录。读取历史协议文档／源码、证明无损字节归因、获得供应商估算、真实 generation 和比赛业务准确性分别记录；本报告仅完成源码／归因调查，没有新的 estimate 或 generation。
