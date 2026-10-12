# Q11：审查发现进入现有咨询的轻量入口

更新：2026-10-10，Asia/Shanghai。当前状态是前端代码与本地契约／组件验证完成，另完成隔离当前源码的 Chrome 页面补充检查；内置浏览器、真实发现带入、真实来源版本核对、原件检索与模型答复的业务验收尚未完成。Chrome 检查发现在线前后端均仍是旧部署，当前源码咨询所需的 sources 接口在在线 Java 返回 404。规则依据 [OER 已确认范围与执行规则](../oer-scope-and-rules.md) 的 Q11=B 与 [实施交接](../HANDOFF-ocr-embedding-rerank-20261008.md)。此文只记录本入口，不改变比赛范围或模板隔离。

## 实现行为

在审查发现的原文证据区域增加“带入合约咨询”。按钮把当前项目、发现编号、三语标题／问题描述，以及逐条原文、documentId、sourceHash、文件、页和锚点送入已有 Advisory，生成可编辑问题草稿。正文保留原拼写、空白和换行；附带引用明确作为待核查线索。团队审阅修改、建议答案、影响文字和整份报告不复制。

打开入口只导航和读取现有咨询资料，不自动提问、不重建索引。用户编辑并提交后仍走已有 `adviceApi.ask`、当前模型来源和当前项目／范围的检索链路。默认带入 `fullset`，用户可自行调整咨询范围。入口不改原件、报告、审查状态或其他工作流。

同一浏览器页签的 `sessionStorage` 仅保存一个待认领草稿；URL 只含随机 token，认领后删除 token 与存储记录。草稿绑定项目、30 分钟有效期、只能认领一次；错误项目、过期、格式错误或重放明确提示，切项目时清除已带入草稿。生成 token 使用 `crypto.getRandomValues`，适用于普通 HTTP 局域网访问，不依赖仅限安全上下文的 `randomUUID`。浏览器禁用存储或导航失败时仍留在审查并提示重试，超大内容拒绝带入而非静默截断。此传递记录是用户问题上下文，不是新的来源权威证明。

咨询载入当前 `sources` 后，以 documentId 和 sourceHash 对照所附引用；审查已标 `sourceSetStale` 或 hash 不同则显示来源变化。缺 hash、仍在加载／读取失败均保持“版本未核验”；来源不存在或被当前范围排除则明确提示不可用。这里对照的是进入咨询及范围切换时读取的来源快照，不声称持续监控全部来源；提交后的当前原件检索仍由已有后端执行。后端 `AdviceSourceVO` 已有 sourceHash，本次只补前端可选字段声明。

证据加载中、运行／上传／保存中和存在未保存的审阅／清单修改时，入口不导航；未保存状态同时提示先保存或放弃，避免因离开页面丢失本地编辑。新增按钮、提示、默认问题支持简体、繁体和英文，通过已有 `pick` 选择文案；切换界面语言不会重写用户正在编辑的草稿。

## 代码和验证

- [独立传递与来源核对 helper](../../../ai_consense_web/src/advice/reviewHandoff.ts)：项目／有效期／一次性认领、原文保留、随机 token、三语文案与当前来源核对。
- [VettingView](../../../ai_consense_web/src/views/VettingView.vue) 和 [AdviceView](../../../ai_consense_web/src/views/AdviceView.vue)：现有页面上的入口、草稿、来源警告与手动提交。
- [前端 API 类型](../../../ai_consense_web/src/api/types.ts)：`AdviceSourceDocument.sourceHash?: string | null`，无新增后端接口。
- [8 项 helper 契约测试](../../../ai_consense_web/tests/review-advice-handoff.test.cjs) 与 [6 项真实 Vue 控件测试](../../../ai_consense_web/tests/review-advice-controls.test.cjs)：合计 14 项通过。控件测试使用实际 Vue、Pinia、i18n、router 与客户端模块，替换 HTTP 边界；验证入口、原文／身份、不自动调用、可编辑后手动提交、三语、未保存门控、加载／过期／范围警告、切项目／重放和存储拒绝。
- 既有 `llm-profile-controls.test.cjs` 回归 exit 0，九组 PASS；包含 Advice／Vetting 操作入口模型选择，以及其他模块的历史模型身份。自定义渲染器仍有已有 Drafting Teleport 两条 warning，保留而不称浏览器验证。
- `vue-tsc --noEmit` exit 0；CRLF-aware 的定向 `git diff --check` 无新增行尾空格。原 Vue 文件使用 CRLF，默认 Git 空白规则会把新增行的 CR 报成行尾空白，未为消除该提示重写整文件。

本机 `npm run typecheck` 首次因 Windows npm 命令包装器对工作区 `D:\R&D\...` 的 `&` 转义问题失败；使用下列直接 Node 调用执行同一 vue-tsc 检查成功。此区别是启动器问题，不隐去失败记录，也不修改全局 npm 配置。

```powershell
cd 'D:\R&D\ConSense\ai_consense_web'
& 'C:\Program Files\nodejs\node.exe' --test 'tests/review-advice-handoff.test.cjs' 'tests/review-advice-controls.test.cjs'
& 'C:\Program Files\nodejs\node.exe' 'tests/llm-profile-controls.test.cjs'
& 'C:\Program Files\nodejs\node.exe' 'node_modules/vue-tsc/bin/vue-tsc.js' --noEmit
```

以上代码验证阶段未加载模型、改 GPU 状态、启动／重启服务或向远端推送。Bonsai 的临时抑制不变。浏览器实际展示、真实来源版本变化，以及用户手动提交后的模型／检索质量，需主实施者分别验收；本地测试不能追认为这些关卡已通过。随后隔离前端的运行检查单独记录在下文。

## 首次内置浏览器检查的阻碍

2026-10-10 按要求通过 computer-use／cua_repl 尝试绑定现有内置浏览器页签，工具明确返回 `Browser is not available: iab`。当前可控 inventory 只有 Chrome extension，其中有已有 ConSense `http://127.0.0.1:5173/#/drafting` 页签；没有把该浏览器当内置浏览器或据本地组件测试宣称浏览器通过。尚未操作任何页面、读取实际 finding、创建临时草稿或提交咨询。由于请求的浏览器无法绑定，也没有可采集的内置浏览器截图。

准确阻碍及该阶段零变更状态保存于忽略目录 [browser-unavailable-receipt.json](../../../.local/state/q11-review-advice-browser-20261010/browser-unavailable-receipt.json)。该历史收据保留；内置浏览器控制接口尚未恢复。随后已授权使用现有 Chrome 补充检查，结果按下文独立记录，不能追认为内置浏览器通过。

## Chrome 隔离当前源码的补充检查

2026-10-10 通过 computer-use／cua_repl 检查已有 Chrome ConSense 页签。原 `5173` 页面加载 `/assets/index-PwZLw7Lw.js` 静态 bundle，没有 Vite client／源码入口；没有覆盖其 dist 或重启它。退出沉浸显示后正常切到 Vetting，原项目的 files／findings／metrics 实际 GET 均 HTTP 200 且为空。随后恢复原 Drafting 项目、MiniMax 来源、简体中文、SCT、`electronicTendering` 输入、审阅模式、SCT1 P236 关联位置及沉浸模式；原页没有可编辑字段或未保存修改。

使用工具托管的前台 Vite 会话 `2076`、自有 PID `120504` 启动当前源码，只监听 `127.0.0.1:5174`，固定 strict port，并代理既有 `127.0.0.1:8080`。独立 Chrome 页确认实际脚本为 `/@vite/client` 和 `/src/main.ts`，当前上传用途／文件范围控件可见。初次 `Start-Process Hidden` 后台启动被自动策略以 `blocked by policy` 拒绝，未执行；该失败保留在启动收据，未重复尝试后台启动。

通过项目下拉框正常读取全部 13 个已有项目，每个项目实际 files／findings／metrics 的 HTTP 200 响应均已保存，files 与 findings 长度为 0，metrics.total 为 0；对应页面均显示没有发现且运行按钮禁用。此分母来自当时实际下拉框及逐项目响应，不外推到其他环境。没有真实 finding 可打开 Q11 详情入口，所以真实按钮 → 可编辑问题草稿 → 来源提示的浏览器验收仍未完成；没有注入假 finding、创建报告或调用模型来填补此条件。

当前源码 Advice 页面正常呈现既有布局、空问题框和手动提问按钮，messages／quick-questions／index/status 实际 GET 为 HTTP 200。但 `GET /api/advice/{projectId}/sources?scope=fullset` 在在线 Java 返回 HTTP 404，控制台记录 `reloadScope` 的 mounted hook 未处理拒绝；源代码的 `AdviceController` 已声明该接口。该证据说明当前源码与在线后端部署版本不匹配，不能宣称 API 完全兼容或 Q11 在线可用。页面显示来源数量 0 也不能把此次读取失败当成来源已经核验。index/status 的 `ocrReady=false` 同样保留，不冒称 OER 生产部署通过。

网络观察从隔离页初次载入之后启用，保存的 73 条后续请求均为 GET；缓冲读取的 `truncated` 标记也原样记录，不能据此宣称覆盖初次启动前的全部网络。整个检查未提交咨询、重建索引、上传、保存、清空对话或新增审查报告。验收结束后关闭自有 Chrome 页、禁用自有网络观察，并以 Ctrl+C 停止准确 Vite 会话；PID 120504 与 5174 监听均消失。原 5173 PID 67984 与 Java PID 95412 的监听和启动时间一致，未重启。初次清理收据的时间字符串比较因 `ConvertFrom-Json` 自动转 DateTime 得到错误的 false，v1 原样保留，v2 使用相同 UTC ticks 验证一致。

本机忽略目录中的补充证据：

- [Chrome 验收收据](../../../.local/state/q11-review-advice-browser-20261010/chrome-browser-smoke-receipt.json)：逐项目覆盖、实际接口状态、未验收关卡及清理事实。
- [实际读取响应](../../../.local/state/q11-review-advice-browser-20261010/chrome-project-read-responses.json) 与 [GET 网络记录](../../../.local/state/q11-review-advice-browser-20261010/chrome-get-network-ledger.json)：只保留必要 method／URL／status／body，不保存请求凭据。
- [隔离服务启动](../../../.local/state/q11-review-advice-browser-20261010/vite-5174-launch.json) 与 [清理核验](../../../.local/state/q11-review-advice-browser-20261010/vite-5174-cleanup.json)：自有 session／PID、源码 SHA、原进程身份及准确停止结果。
- [原页面恢复截图](../../../.local/state/q11-review-advice-browser-20261010/chrome-restored-drafting.jpg)、[当前源码审查页](../../../.local/state/q11-review-advice-browser-20261010/chrome-current-source-vetting.jpg) 和 [当前源码咨询页](../../../.local/state/q11-review-advice-browser-20261010/chrome-current-source-advice.jpg)，各自另存 DOM。

下一步需要用匹配当前源码的后端及范围内真实 finding 完成入口验证；真实检索和用户手动提交后的模型答案仍单独验收。本轮没有自行重启在线服务、覆盖旧前端部署或加入兼容绕过。
