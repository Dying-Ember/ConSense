# Drafting 文稿晚到与换版误判未保存的界面修复

2026-10-20。真实浏览器在人工确认模板并重新采用 false 后，停留在变量页：五个步骤和关系图被禁用，生成按钮可点却由函数直接返回。后端已经存在通过 API 生成的文稿，前端原本为空的 `bodyDraft` 与新到文稿不一致，被错误当成人工未保存改文，变量页又没有正文放弃按钮。

根因是 `watch(activeDocument)` 收到新文稿后调用默认 `loadDocument()`；后者的保护判断使用新文稿，而不是使用原文稿判断用户是否改动。生产修复只有该 watcher 一行：先对 `previous` 文稿和当前本地 draft 判断是否存在真实编辑；原本干净时才调用 `loadDocument(false)` 加载新文稿或已缓存 draft。`loadDocument` 自身保护、`bodyDirty`、已保存版本和项目／文件缓存均不改，真实旧版人工编辑不会被新服务端版本覆盖。

新 mounted 测试 `ai_consense_web/tests/drafting-document-arrival.test.cjs` 使用真实 `DraftingView` watcher、刷新控制流和 `DraftingBodyEditor`。只模拟 HTTP、来源核对完成事件和重型文稿承载组件；没有实际 HTTP 写入、生成或 LLM 请求。

| 场景 | 改前 | 改后 |
|---|---|---|
| 初次加载结束后，后端第一份文稿晚到 | 步骤错误禁用，失败 | 初始化正文，五步骤／关系图可用，通过 |
| 干净旧文稿由后端换成新 revision／新段落文本 | 步骤错误禁用，失败 | 新 revision 正文加载且仍干净，通过 |
| 用户实际 textarea 改文并插入段落后收到新 revision，再切项目返回 | 通过 | 旧 revision、人工文本和插入段落均保留，仍待保存，通过 |
| SCT 人工改文切项目返回，再明确放弃后切 NTT | 通过 | SCT 缓存保留，不泄漏到 NTT；放弃后 NTT 仍干净，通过 |

正式 RED 为 4 项 2 pass／2 fail；GREEN 为 4/4、0 skip。另有 regenerate feedback、body edit、business state、human review flow 四个相关文件通过；TypeScript typecheck 通过。早期测试夹具的按钮文案和未声明 workspace `input` emit 导致额外失败，已先修正夹具再记录干净 RED，没有据此改生产。原始证据在 `.local/e2e-20261020/drafting/ui-body-watch-{red,green,related,typecheck}.log`；改前 View 和最终测试也保存在 `ui-body-watch-red/`。

已形成的错误缓存没有自动删除，真实未保存内容也没有清理。当前浏览器保留现场由 root 另行留证并通过正常 UI／完整重载复验；mounted 通过不替代该浏览器复验。
