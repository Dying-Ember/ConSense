# MiniMax-M3 Responses：原生结构化输出的官方依据

2026-10-20，Asia/Shanghai。持续 E2E 中只读调查；未发送付费兼容性探针、未增加 API 字段、未切换模型或传输路线。研究由当前 Advisory 后台子代理执行；其余并行代理继续各模块测试。本机没有 ego 浏览器命令，按其 Windows 安装说明确认后使用现有后台浏览器阅读公开官方文档，没有安装新软件或要求用户完成 onboarding。

当前足以采纳的结论是：**官方 Responses 请求定义目前仅给出 `text.format.type=text`，没有本次能据以实施的原生 JSON／JSON Schema 最终回答约束。** 不能从“OpenAI 兼容”推导支持 `json_object`、`json_schema`、`strict`，也不能把函数参数的 JSON Schema 当成最终回答的强约束。这是对本次可见官方契约的判断，不是“已探针证明所有未公开字段都不可用”。

| 核对点 | 官方页面观察 | 结论及边界 |
| --- | --- | --- |
| 当前接口与模型范围 | [Responses 对话生成](https://platform.minimax.cn/docs/api-reference/responses-create) 定义 `POST https://api.minimax.cn/v1/responses`，同页推理部分明确列出 MiniMax-M3 的行为 | 本次核查对应目前应用实际使用的路线；页面示例主模型已为 M3.1-Flash，不能因此静默换型号 |
| 文本格式控制 | 展开 [`text.format.type`](https://platform.minimax.cn/docs/api-reference/responses-create#body-text-format-type)，类型为 enum，默认值 `text`，可用选项仅 `text` | 没有找到官方对该字段的 `json_object`／`json_schema` 枚举、schema／name／strict 子项或相应约束承诺；不加猜测字段 |
| 函数工具 | 展开 [`tools.parameters`](https://platform.minimax.cn/docs/api-reference/responses-create#body-tools-items-parameters)，原文为“函数参数定义，使用 JSON Schema 格式”；工具字段为 type=function、name、description、parameters | 这是函数参数定义，不能推为最终 message JSON Schema；本次未观察到此页承诺 `strict` 受约束解码 |
| 工具选择 | [`tool_choice`](https://platform.minimax.cn/docs/api-reference/responses-create#body-tool-choice) 显示 enum，选项仅 `auto`，描述为模型自行判断调用工具 | 不把强制函数调用或 forced structured answer 当成已支持，不把当前只接收 message 的 transport 偷改为 tool 结果读取 |
| OpenAI SDK 兼容 | [官方 OpenAI SDK 说明](https://platform.minimax.cn/docs/api-reference/text-openai-api) 支持模型表包括 MiniMax-M3，并列出兼容请求参数；同时说明部分 OpenAI 参数会被忽略 | SDK／协议形状兼容不证明所有 OpenAI 功能可用；本次页面没有提供原生最终 JSON Schema 模式的可采纳保证 |
| 推理开关差异 | [Responses reasoning](https://platform.minimax.cn/docs/api-reference/responses-create#body-reasoning) 明确 M3 默认关闭推理，非 none 可开启但不调深度；M3.1-Flash 始终推理且 none 返回400 | 当前 M3 的 none 与新 Flash 的行为不同；本次研究不授权以换型号／开推理来替代当前失败验收 |

调查先尝试的旧直达路径 `https://platform.minimaxi.com/docs/api-reference/text-responses` 重定向到新官方域名后返回404；随后通过该页可见的“对话生成”链接进入上表真实页面。工具连接器的初次搜索没有返回可用内容，不作为事实依据。官网检索一次 `json_schema structured output` 未形成可引用的支持页面；不以该检索结果证明全站不存在相关能力。所有表内判断均来自展开后的真实官方页面字段。

本次 E2E 捕获的应用请求 `generation-request.utf8.json` 只有 model／instructions／input／reasoning／temperature／stream／max_output_tokens／truncation，没有 text.format 或 response_format。答复 schema 放在应用提示词中。真实出现多余 graph edge.id、无引文 pass、字符串 grounded 和法律方向自相矛盾，均不应表述为“provider 严格 JSON Schema 已通过”；应用本地结构门与法律内容审查保持独立。

当前最小处置是维持现有传输和本地严格校验、一次修复上限，保留失败与未交付图。原生格式新行为暂不实施。若后续取得明确覆盖 **MiniMax-M3 + Responses + 本次区域 API** 的官方字段与约束保证，再准备最小候选：只在现有 request builder 中加入被证实的格式字段，counter／source snapshot／观察收据纳入完全相同的序列化输入，分别验证非流式和流式及 ignored／unsupported／invalid output 反例。届时仍须另定有限实际调用预算；不采用静默 fallback、不把类型强转／删未知字段视为来源或业务正确，也不扩大现有模型修复次数。

JSON Schema 即使将来可用，通常只能约束结构；它不会证明引用原文支持解释，也不会自动纠正 prior-to 方向、截止日与最后发布日期、表格排除关系或文件优先。当前这些真实业务失败继续单独保留。
