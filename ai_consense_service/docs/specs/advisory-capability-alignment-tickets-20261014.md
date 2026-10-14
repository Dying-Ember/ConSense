# Advisory 能力对齐实施任务图

规格：[已确认 Advisory 规格](advisory-capability-alignment-20261014.md)。用户于 2026-10-14 确认并启动 implement-spec。单一私仓 PR 分支 `codex/advisory-alignment-20261014`，固定源码基线 `3d582f10233d744509effc927813981c3d467e69`（已合并此前 PR #8）；活动 dirty 主目录及旧分支保持。Ponytail 5.2.0 enabled，三个 hooks trusted，运行模式 full 已核。

| Ticket | 交付 | 依赖 | 状态 |
| --- | --- | --- | --- |
| A0 | 固定基线、批准提示词、任务图及草稿 PR | 无 | 进行中 |
| A1 | 运行规范、结构化有限分析／内部检查、T6 图计划、历史与共享发布门 | A0 | 待实现 |
| A2 | 完整源文件接口／阅读／原生定位、正文引用与表格展示 | A0 | 待实现 |
| A3 | 实际 provider streaming、完整单元提取、取消与两条模型传输兼容 | A0 | 待实现 |
| A4 | 服务端业务事件流、Codex 风格交互、请求停止及最终提交事件 | A1、A3 | 待联调 |
| A5 | 来源／历史／范围／竞态回归、真实 Mermaid、官方 question-only 与自然问法验收 | A1、A2、A3、A4 | 待验收 |
| A6 | Standards＋Spec 审查、修复、PR ready 与交接 | A5 | 待审查 |

A1、A2、A3 并行，各用独立 worktree 和分支；A4 的前端及 controller 可按下面固定接缝先行开发，集成等待依赖。合并每个已测提交后重算 frontier，审查不把 mock／结构验证称为业务通过。

## 最小共享接缝

- A1 提供 `AdviceService.ask(projectId, request, AdviceProgress)`；原 ask 保留。AdviceProgress 只含真实 `phase(String)`、已核 `section(String content, List<String> citations, List<CitationVO> sources)`、`checkCancelled()` 和取消回调登记 `onCancel(Runnable)`；无前端自检字段。
- A3 在 LlmClient／AiGateway 追加 `supportsStreaming()` 与 `chatStructuredStream(turns, schema, Consumer<String> onDelta, Consumer<Runnable> onCancel)`。不支持的 client 显式返回能力 false，不伪造 delta；完整响应方法保留供其他模块使用。Responses Operation 用同等 delta／取消接缝，保留计数、身份、预算及一次修复总预算。
- A1 负责结构化答案单元协议与发布门。增量 JSON 字符串必须按完整对象边界提取，支持 escape／Unicode／任意网络分段，闭合单元校验后才调用 section；后置 envelope／全局失败不得显示最终完成。A3 可提供独立纯解析 helper，单元字段定义由 A1 与既有 AnswerRecord 最小兼容。
- A4 通过 `POST /api/advice/{projectId}/ask/stream` 的 fetch 事件流发送 phase、section、complete、error，完成事件带既有 AskResponseVO，只能在 ask 原子保存返回后发送。请求选择 profile 在入队前捕获并在 worker bind／释放；端点支持终止当前 request，不停止共享服务。
- A2 的项目原件端点使用 `GET /api/advice/{projectId}/sources/{documentId}/original`，保留当前 source identity／revision／routing／scope／SHA 核验；固定标准原件继续复用既有固定接口。独立阅读组件输入 CitationVO，emit 只传该引用身份，不从文件名猜来源。

实际方法细节可按周边模式调整，但不得改变已确认行为或弱化信任边界。实施子代理按各自 ownership 避免修改同一个 AdviceService／AdviceView；共享接缝调整通过上下文指针记录。
