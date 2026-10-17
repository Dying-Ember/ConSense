# MiniMax M2.7／M3 的结构输出参数核查

核查：2026-10-16 04:14:58–04:15，Asia/Shanghai。范围仅限官方托管 `/v1/chat/completions` 的 JSON／函数输出约束及其思考参数；0 次模型 API、未读取密钥、未改当前冻结请求或 transport。本文作者参与了本轮 D1 提示词编写，本调查不替提示词独立审核或实际模型语义验收。

**目前不能从官方公开契约确认 M2.7 或 stable M3 支持 `response_format` 的 JSON object／JSON Schema 模式。** 官方中国区 Chat Completions 完整 OpenAPI 没有声明 `response_format`，也没有 `json_object`、`json_schema` 或 `strict` 参数。两模型均出现在该共用 API 的模型枚举中，但没有另外的结构输出模型支持表。这里的结论是“未找到公开支持承诺”，不是通过试探证明服务端必定拒绝这些字段；不得从 OpenAI 兼容性推定约束解码可用。[中国区 Chat Completions](https://platform.minimax.cn/docs/api-reference/text-chat-openai)、[完整官方 Markdown／OpenAPI](https://platform.minimax.cn/docs/api-reference/text-chat-openai.md)

## 可核结论

| 问题 | 官方证据与结论 | 对当前实验的含义 |
|---|---|---|
| `response_format: {type: json_object}` | 中国区完整 Chat 请求定义与国际参数页面均未列出；完整 Markdown 也未出现该模式。 | 没有可据此直接采用的 JSON mode；不发送未知字段碰运气。 |
| `response_format: {type: json_schema}`／`strict` | 同一 Chat 定义未声明 schema 输出参数或 strict 保证。 | 不能声称 API 会强制满足 D1 JSON/schema。 |
| `tools[].function.parameters` | 官方把它定义为“函数参数 JSON Schema”；返回的 `ToolCall.function.arguments` 为“函数入参，JSON 格式字符串”。 | 有函数参数定义接口，但这两句不等于保证生成语法及 schema 严格正确；仍需原样保存、严格解析和验证。 |
| 强制指定某个 function／必须调用 | Chat 完整请求定义未声明 `tool_choice`；因此未证实 OpenAI 的强制选择写法在该端点受支持。 | 不把注册唯一函数当作必定调用，也不把未调用算已返回结构对象。 |
| 改用 Responses 是否解决 | 官方 `/v1/responses` 定义里的 `text.format.type` 只有 `text`；`tool_choice` 仅 `none`／`auto`。 | 没有已证实的 JSON/schema 替代路径；换协议还会改变思考默认，不能作为透明修复。 |

Chat 请求及函数字段证据见保存快照 `chat-cn.md:931–1085`、`1301–1328`；Responses 格式与工具选择见 `responses.md:289–319`、`489–508`。[Chat 官方定义](https://platform.minimax.cn/docs/api-reference/text-chat-openai.md)、[Responses 官方定义](https://platform.minimax.io/docs/api-reference/responses-create.md)

## Thinking 与输出位置

中国区 Chat 定义明确：`MiniMax-M3` 可用 `thinking: {"type":"disabled"}` 跳过思考；M2.x 的 disabled 会被接受但不起作用。真正的 `reasoning_effort` 深度档位只对 `MiniMax-M3.1-Flash-Preview` 有效，不能把 stable M3／M2.7 的 low／xhigh 请求解释为已经降低／提高了思考强度。`reasoning_split=true` 将思考另放 `reasoning_content`；false 将其放进 content 的 think 标签，二者都不控制思考开关。与 JSON mode 的兼容限制没有公开说明，因为该定义根本没有声明 JSON mode。[中国区 Chat](https://platform.minimax.cn/docs/api-reference/text-chat-openai)、[官方 OpenAI SDK](https://platform.minimax.io/docs/api-reference/text-openai-api)

函数工具与思考可共同使用，官方 M3 工具示例在同一请求中设置 `tools` 和 `reasoning_split=True`。但工具指南称分离字段为 `reasoning_details`，当前 Chat reference／SDK 参数表则写 `reasoning_content`；应保留这个文档差异，未来若另行试验以实际原始响应为准，不能静默丢字段。多轮工具调用要求保留完整 assistant／工具调用及思考字段；本轮单次 D1 不能据此推定具备新的多轮能力。[官方工具与交错思考指南](https://platform.minimax.io/docs/guides/text-m3-function-call)

`max_tokens` 是已弃用的生成预算字段，包含思考；新集成推荐 `max_completion_tokens`。提高总上限可以针对 length，但不是 JSON 语法约束，也不能据此修正 stop 后的逗号错误。新旧上限字段、思考模式、协议和输出结构应分别登记变更，不能一次全改后宣称单因素收益。[官方 OpenAI SDK](https://platform.minimax.io/docs/api-reference/text-openai-api)

## 下一轮可考虑的有限变化，均未准入

1. 对 stable M3，保留完整同源输入、D1 契约、总生成 32768、`reasoning_split=true` 等条件，只把 thinking 改为 disabled，是文档明确支持的独立试验因素。它可能减少思考占用，但是否改善完整性、格式和语义必须实测，不能预授通过；当前 length 失败保持原状。
2. 对 M2.7，不把 disabled 或未知 JSON mode 当修复。优先沿已有流程减小单次语义闭包／输出体量，保完整分母和原证据；普通 JSON 仍严格验原始输出。提示词中的转义／分隔纪律只能视作提示策略，不能称约束解码。
3. Function 输出可以作为独立的候选实验：用 schema 描述一个仅返回 D1 对象的函数，分别记录是否实际调用、arguments 原文、JSON 语法、schema、覆盖与独立语义。当前官方证据不足以保证它严格遵循参数，也不足以保证强制调用。若将来获准，需新冻结请求及收集契约，支持 `finish_reason=tool_calls` 和 arguments；不得让现有只收 stop/content 的 runner 冒称已测此模式，亦不执行任何实际业务动作。

这些建议没有新增调用授权，不改 64 次／2,097,152 声明生成上限，不给当前失败输出修 JSON，不删断言／字段／来源以换绿。

## 来源快照与时间边界

优先回读了既有 `m3-controls-research.md` 和 2026-10-12 M3 研究；随后重新打开当前官方中国区／国际 Chat 与 SDK 页面，并读取完整官方 Markdown。网页没有公开可区分的内容修订日期；抓取响应的 HTTP `Date` 为 `2026-10-15 20:14:59 GMT`，`Last-Modified` 接近本次抓取时刻，不能当作功能发布日期。完整 OpenAPI 的 `info.version=1.0.0` 也不是模型后端版本或发布日期。

已保存的公开文档位于忽略目录：[抓取清单](D:/R&D/ConSense/.local/drafting-graph-stability-campaign-20261016/evaluation/minimax-structured-output-docs-20261016/retrieval-manifest.json)。五件均为公开文档 GET，不是模型 API：

| 官方快照 | SHA256 |
|---|---|
| [中国区 Chat 完整定义](D:/R&D/ConSense/.local/drafting-graph-stability-campaign-20261016/evaluation/minimax-structured-output-docs-20261016/chat-cn.md) | `9bf4eaa77f66d9f597cb65e33ced8abb440e29362f2e73e5061f6ca026797738` |
| [OpenAI SDK 指南](D:/R&D/ConSense/.local/drafting-graph-stability-campaign-20261016/evaluation/minimax-structured-output-docs-20261016/chat-sdk.md) | `ee59b46734b303cce80dc60fd7729abce43cb205ad5914356c16beac95934412` |
| [Responses 完整定义](D:/R&D/ConSense/.local/drafting-graph-stability-campaign-20261016/evaluation/minimax-structured-output-docs-20261016/responses.md) | `b8ed98eb11ce6639e48801e0e8efd2e1dc6730ba4265e5c28698fbaef24c07d6` |
| [M3 工具指南](D:/R&D/ConSense/.local/drafting-graph-stability-campaign-20261016/evaluation/minimax-structured-output-docs-20261016/tool-use.md) | `40ab52a616a5321ff841dc6250b0b62a7cdc0384eb0218365f27beef648f25bb` |
| [AI SDK 指南](D:/R&D/ConSense/.local/drafting-graph-stability-campaign-20261016/evaluation/minimax-structured-output-docs-20261016/ai-sdk.md) | `ec76ecc9b8a325354547f5eeaf2b13c004dfeb215a8603748a8617a32c886485` |

工具文档没有公开 strict arguments 保证；AI SDK 指南也未新增 response_format／JSON mode 承诺。文档支持与真实端点行为、JSON/schema 合格、来源语义正确及最终图谱稳定性分开报告。
