# ConSense 本机部署记录

部署日期：2026-10-08，Asia/Shanghai。目录：`D:\R&D\ConSense`。远端：<https://github.com/Dying-Ember/ConSense>。拉取基线为 `main` 的 `b4c7339e1871fc7eba92b32edd2b76cd52d734e8`。本记录随兼容修复、部署脚本和研究文档一同纳入版本控制；`.local/` 的运行配置、工具、权重、数据库及验收产物和 `Ref/` 的外部原始资料仅保留在本机，不随代码提交。文中 `.local/` 链接是本机证据路径，其他 checkout 需要自行准备对应依赖和参考资料。

当前后端为 `draft-extraction-20261008.20-formal-bill-identities`，完整前端考试得到 **61/61 正确且有出处的取值候选、18/18 正确留空、65 PASS / 14 REVIEW / 0 FAIL**。Bill 显示完整 13 行，其他防水区域显示完整 18 项。旧 60/61 调参成绩及以下各阶段部署记录保留作为历史；最终加固、测试和语义复核边界以 [Drafting harness 加固记录](docs/drafting-harness-hardening.md) 为准。人工采用、文稿生成和完整 PDF 版式验收仍须分别核对。

## 访问与模型配置

| 内容 | 地址 / 配置 |
| --- | --- |
| ConSense 起草页面 | <http://127.0.0.1:5173/#/drafting> |
| 提示词页面 | <http://127.0.0.1:5173/#/prompts> |
| 后端 API | `http://127.0.0.1:8080/api` |
| 后端 OpenAPI | <http://127.0.0.1:8080/swagger-ui.html> |
| 模型 API 根地址 | `http://127.0.0.1:8091` |
| OpenAI 客户端 base_url | `http://127.0.0.1:8091/v1` |
| 模型目录 | <http://127.0.0.1:8091/v1/models> |
| 聊天 | `POST http://127.0.0.1:8091/v1/chat/completions` |
| 模型名称 / alias | `ternary-bonsai-2-27b` |

三个监听地址均绑定 `127.0.0.1`。ConSense 的 `CONSENSE_LLM_BASE_URL` 配置是根地址 **不带 `/v1`**，因为其 Java 适配器自行追加 `/v1/chat/completions`；一般 OpenAI SDK 的 `base_url` 则带 `/v1`。

本部署启用 local / OpenAI profile，禁用 MiniMax 和 OCR；数据库使用本机 H2，向量存储选用 memory。模型运行器使用官方 PrismML fork `prism-b10743-adfffbe` 的 Windows CUDA 12.4 预编译包，权重为官方 `Ternary-Bonsai-2-27B-PTQ1_0.gguf`。

用户已确认将变量识别考题中 **60/61** 的配置推广为本机默认：**65,536 tokens 上下文、F16 K/V 缓存、medium thinking、每思考块 4,096 tokens、总生成预算 16,384 tokens、seed 42**。部署切换和有效进程验收见下方“64K medium 默认配置推广”。初次部署使用的 32K / thinking 关闭 / 2K 配置及其测试保留在历史验证节。

**16,384 tokens 是 thinking 和最终答案共同消耗的生成预算，不是独立的 16K 最终答案额度。**每思考块 4,096 tokens 也不等于整次请求只允许 4,096 个思考 tokens。单槽、`--gpu-layers 99`、Flash Attention、batch 512 / ubatch 128、`--jinja --reasoning-format deepseek` 沿用获胜测试配置；服务器 `--n-predict` 与 ConSense `structured-max-tokens` 均为 16,384。如模型返回 `finish_reason=length`，仍须核对完整响应，不能把截断输出当成功。

PTQ1 保存三元值 `{-1,0,+1}`，使用密集三进制包装；本文件约 5.95 GB、官方实际部署口径约 1.75 bits/weight。它不是全文件严格 1.58 bits/weight。模型 revision 固定为 `b072e1d3b35a0a630cece372c2127528e0994386`，SHA-256 为 `53107f530aa52eb00912263ab1ee29bd199261c87cd7b4ad4ca1318c1fe33ee3`。[官方模型](https://huggingface.co/prism-ml/Ternary-Bonsai-2-27B-gguf/tree/b072e1d3b35a0a630cece372c2127528e0994386)、[官方引擎 release](https://github.com/PrismML-Eng/llama.cpp/releases/tag/prism-b10743-adfffbe)

## 64K medium 默认配置推广

推广授权日期：2026-10-08。用户明确选择现有考题中 60/61 的 `medium4k` 运行作为本机默认。**配置切换于当日 12:22:27（Asia/Shanghai）完成**，部署收据为 [.local/state/deployments/20261008-122202-b420c762/receipt.json](.local/state/deployments/20261008-122202-b420c762/receipt.json)，状态 `deployed`。本次仅推广已经测过的模型参数，并延长前端长请求等待边界；业务 harness、提示词和后端 JAR 保持获胜测试版本。切换和回滚入口为 `scripts/deploy-local-runtime.ps1`，按原 runtime 字节备份恢复。

| 配置 | 推广值 / 核验方式 |
| --- | --- |
| 服务器上下文 | `--ctx-size 65536`；`Model.ContextSize=65536`，Java `--consense.llm.num-ctx=65536` |
| K/V 缓存 | `--cache-type-k f16 --cache-type-v f16` |
| Thinking | `--reasoning on --reasoning-effort medium --reasoning-budget 4096` |
| 总生成预算 | `--n-predict 16384`；`Model.MaxOutputTokens=16384`，Java `--consense.llm.structured-max-tokens=16384` |
| 思考与最终正文分离 | `--reasoning-format deepseek`；实际模型响应的 `message.content` 不应混入 `<think>` 正文 |
| 随机 seed | `--seed 42`；应用请求 temperature 仍为 `0.2` |
| 前端变量识别请求等待 | 2 小时（`7,200,000 ms`），覆盖已经测得的约 65.2 分钟整轮识别 |
| 前端 API 代理等待 | 125 分钟（`7,500,000 ms`），比变量识别客户端边界稍长 |
| 后端单次模型调用等待 | 原有 `1,200,000 ms`；每个模型调用独立计时，不是整轮识别的 20 分钟上限 |

该收据记录：Bonsai / backend / frontend 已分别从旧进程重新启动为 PID `65476 / 12144 / 97260`，三项 `Running=true / Ready=true`；模型 API 的 `data[0].meta.n_ctx=65536`，实际 Bonsai 命令行与获胜运行全部参数一致，Java 命令行包含 `num-ctx=65536 / structured-max-tokens=16384`，`ConSense-Local` 守护为 `Running`。**12:22 初次推广时** `.local/runtime.json` 的字节 SHA-256 与获胜 runtime 完全一致；随后增加下方 PDF renderer 配置，完整 runtime hash 已变化，`Model` 对象保持相同。后端 JAR hash 保持原值。PID 只表示本次切换时的进程，日后恢复应重新读取 `.local/state/{bonsai,backend,frontend}.json` 并校验身份。

获胜运行输入固定为考题区的 15 份 DOCX，以及赛方 2a 的 NTT、SCT、SCC 三个标准模板。参考答案未上传给模型。完整结果是：**61 个需要业务取值的变量中，60 个有正确且真实出处的候选；18 个应留空项全部正确。严格评分仍为 55 PASS / 23 REVIEW / 1 FAIL**，没有因为选择部署配置而改成“全部通过”。这一成绩只验收变量候选识别，不代表候选已被人工采用，也不代表完整合同、Bill 分发用法或 PDF 排版通过。已知剩余 FAIL 为 `otherWaterproofingSpecificationAreas` 的长文本与引用问题。

同题实测整轮耗时 **3,910.937 秒，约 65.2 分钟**；纯生成加权速度约 **29.22 tokens/秒**，包含 thinking 和最终答案，不能解释为用户可见答案独立生成速度。新增等待边界使浏览器可以等待这轮请求，不提高模型速度，也不保证所有更大资料包都能在两小时内完成。

获胜配置来源为 `.local/state/drafting-exam/campaign-medium4k-20261008/medium4k-runtime.json`，SHA-256：`18104338966DCF4C78878FB36AEC92C704ECB3F526718980E8B906BCEA2D6D73`。评分来源为该目录下 `medium4k/scoring/drafting-score.json`，SHA-256：`364C425917F6F57B790DA07C62B8324D3E82A0EA00CB0AB18A43F7F02A580B30`。后端 JAR 应保持 SHA-256：`FC007462DFC4ED8B60D166A9606243E0838802E50210248E859D7D0B432A76FC`。逐项台账、比较和性能边界见 [模型对照结果](docs/drafting-model-benchmark-results.md) 与 [性能评估](docs/drafting-performance-assessment.md)。

`local.ps1` 会复用已存在且 ready 的 owned 进程，不自动核对新参数；推广时必须先停止经 receipt 验证的本项目组件再启动。守护 `watch-local.ps1` 从 `.local/runtime.json` 读取配置，组件退出后的重启会沿用推广后的默认。前端 `/api/system/llm-profiles` 只显示模型与配置可用状态，没有实际 thinking/context/output 预算字段；部署生效应以进程参数、模型 API 和部署收据为准。

调参入口 `scripts/benchmark-bonsai-drafting.ps1` 在暂停守护和停止组件前核验 owned 服务、完整任务身份及模型空闲状态。模型 busy、slot 状态未知或查询失败时拒绝停止。首次 preflight 拒绝不会触发恢复重启；实验开始后若仍有请求运行，清理会记录 deferred restoration 并保留原配置字节备份，等待请求完成后再用已校验备份执行 `deploy-local-runtime.ps1` 恢复。`scripts/tests/test-benchmark-idle-guard.ps1` 的 12 个隔离 stub 用例覆盖这些边界，不操作实际模型或服务。

本次推广后，两个短合成用例通过同 JAR 字节的真实 `AiGateway → OpenAiLlmClient → HttpSupport/OkHttp → Bonsai` 执行，严格 JSON/schema 和四个中文字段精确值全部通过；两次 wire 的 `max_tokens=16384`，均以 `finish_reason=stop` 完成。Thinking 在非空 `reasoning_content` 中，最终 `content` 为纯 JSON。完整请求与检查见 [adapter-smoke.json](.local/state/deployments/20261008-122202-b420c762/adapter-smoke.json) 和 [adapter-smoke-check.json](.local/state/deployments/20261008-122202-b420c762/adapter-smoke-check.json)。该检查不重复整场考题。

内置浏览器已刷新并载入新 `/assets/index-DgQZe16V.js`，与磁盘构建 SHA 一致，确认模型配置就绪、三模板和 15 份资料存在、识别按钮可用。页面保留原选中项目 `drafting-exam-20261008-095654-9ecc884f`，停在“2. 核对起草输入”；已有候选是该项目先前的结果，点击“识别输入”才会按新配置重算。浏览器观察见 [browser-check.json](.local/state/deployments/20261008-122202-b420c762/browser-check.json) 与 [页面截图](.local/state/deployments/20261008-122202-b420c762/frontend-ready.jpg)，前端代理/API 读回见 [integrated-checks.json](.local/state/deployments/20261008-122202-b420c762/integrated-checks.json)。**用户本人前端业务验收尚未完成**。

当前重复部署应使用**同时保留 PDF renderer** 的 runtime 版本和真实 hash，命令如下；执行会先核对现有 owned 服务和模型空闲状态，并生成新的部署收据。12:22 初次推广使用的原始获胜 runtime 来源保留在上方收据中。完整考题在调参轮已跑完，本次推广没有重新触发约 65 分钟的整轮变量识别。

```powershell
& '.\.local\tools\powershell\pwsh.exe' -NoProfile `
  -File '.\scripts\deploy-local-runtime.ps1' `
  -RuntimePath '.\.local\state\renderer-acceptance-20261008\runtime-with-renderer.json' `
  -ExpectedSha256 'AC9C02A2396AA61056C55DA179873DB09A9E9F36A7EA494B1BFB24D0D1A35E5A'
```

如需恢复 12:22 模型推广前的 32K / thinking 关闭 / 2K runtime，使用原字节备份及其校验 hash。**这份旧备份没有 `DraftingRenderer`，会同时禁用格式化 PDF 渲染**，因此必须显式传入 `-AllowRendererChange`：

```powershell
& '.\.local\tools\powershell\pwsh.exe' -NoProfile `
  -File '.\scripts\deploy-local-runtime.ps1' `
  -RuntimePath '.\.local\state\deployments\20261008-122202-b420c762\runtime-before.json' `
  -ExpectedSha256 'A612679931DD93A4F92AD4493BD2F6A12AA4440D81008935936435D141167091' `
  -AllowRendererChange
```

这条回滚命令恢复模型和 Java 读取的 runtime 参数，并按旧备份移除 PDF renderer 配置；数据库、上传、考题记录及前端延长后的等待边界保留。默认情况下，部署脚本拒绝改变 renderer；`-AllowRendererChange` 只用于明确选择带来 renderer 变化的已审阅 runtime。该参数及顶层字段范围检查已通过 PowerShell parser（零错误）和真实 helper 的 20 个分支验证，见 [promotion-renderer-scope-check.json](.local/state/deployments/20261008-122202-b420c762/promotion-renderer-scope-check.json)；验证没有执行部署、重启或 active runtime 写入。脚本遇到模型正在处理请求、身份不匹配或端口不释放会停止操作；切换失败会尝试恢复原 runtime 字节并保存恢复结果。日常启停仍使用下方 lifecycle 入口。

## 格式化 PDF renderer

初次部署和 12:22 模型参数推广尚未设置 renderer；前端“PDF 版式核对”因而明确返回 `4013 / NOT_CONFIGURED: Set consense.drafting.renderer-executable and renderer-work-root`。原失败读回保留在 [sct-preview-before.json](.local/state/renderer-acceptance-20261008/sct-preview-before.json)。这属于独立的文档渲染依赖，已有保存 DOCX 可以直接渲染，无须重新识别输入或生成文稿。

现已准备官方 **LibreOffice 26.2.6.3** Windows CLI，采用项目内纯 MSI 解包：使用 lessmsi 读取 MSI 文件内容，没有执行 Windows Installer。下载来源为 [Document Foundation 官方 MSI](https://download.documentfoundation.org/libreoffice/stable/26.2.6/win/x86_64/LibreOffice_26.2.6_Win_x86-64.msi)，官方与实际 MSI SHA-256 均为 `F9877032FD908BEB9C0DDF06DF4AF5C2E85F419C42E14876C4CCE5AAE5FB2660`，签名状态 `Valid`。来源、解包方式、可执行文件和 hash 见 [renderer-source.json](.local/state/renderer-acceptance-20261008/renderer-source.json)。

| 项目 | 当前配置 |
| --- | --- |
| runtime 配置对象 | `.local/runtime.json` 的 `DraftingRenderer` |
| Kind | `libreoffice` |
| Executable | `D:\R&D\ConSense\.local\tools\libreoffice-26.2.6\SourceDir\LibreOffice\program\soffice.com` |
| Executable SHA-256 | `FF9414529BFDF970AE51D54C6E79CD6AA05B19409ADCDB8391D423BD0857A5F2` |
| WorkRoot | `D:\R&D\ConSense\.local\pdf-work` |
| TimeoutMs | `120000`，版本探测与本次转换共享 120 秒 deadline |
| 当前完整 runtime SHA-256 | `AC9C02A2396AA61056C55DA179873DB09A9E9F36A7EA494B1BFB24D0D1A35E5A` |

配置切换于 **14:37:43（Asia/Shanghai）** 完成，[deployment-receipt.json](.local/state/renderer-acceptance-20261008/deployment-receipt.json) 状态为 `deployed`。通过 `local.ps1 -Action restart-backend` 仅重启 owned backend，PID 从 `12144` 变为 `54208`；Bonsai `65476` 与 frontend `97260` 的进程 ID 和创建时间保留，`Model` 内容不变，backend JAR hash 未变，守护恢复为 `Running`。生效 Java 命令行包含四个 `consense.drafting.renderer-*` 参数。

启动脚本从 `DraftingRenderer` 显式传入 backend 参数，检查 executable 存在、timeout 为正整数、WorkRoot 位于本项目 `.local` 内且与 tools/data 分开，拒绝 linked ancestor。每次转换建立自己的 `profile`、`artifact.docx` 和 `out/artifact.pdf`，按 argv 直接调用 LibreOffice；私有 `UserInstallation` 避免复用用户已打开的 LibreOffice 会话。超时处理只终止本次 renderer 的 owned process tree。更新 renderer 配置后可执行：

```powershell
& '.\.local\tools\powershell\pwsh.exe' -NoProfile `
  -File '.\scripts\local.ps1' -Action restart-backend
```

格式化转换读取精确保存 DOCX，输出必须为可读、未加密、非空页 PDF；缓存按 DOCX hash 与实际 renderer/version/font profile hash 共同确定，读取时再核验 PDF hash。活动字体缺失或无法解析时明确报错；成功渲染仍只证明已有检查通过。**字形覆盖、逐文字字体选择、TOC/PAGEREF 等字段刷新，以及逐页视觉版式尚未认证**，需结合真实 PDF、源 DOCX 和人工核对。PDF 预览端点虽为 GET，会保存派生 PDF 缓存，不改变原 DOCX 或保存 revision。

实际 PDF/API 验收于 **14:45:54（Asia/Shanghai）** 完成，使用既有项目 `drafting-exam-20261008-095654-9ecc884f` 中的保存 NTT、SCT、SCC revision，结果 [pdf-api-acceptance.json](.local/state/renderer-acceptance-20261008/pdf-api-acceptance.json) 为 `passed`：

| 保存文稿 | 文稿 PDF 页数 | 标准模板预览页数 | 文稿 ready / 全部绑定 | 源模板 ready / 全部绑定 |
| --- | ---: | ---: | ---: | ---: |
| SCT | 23 | 29 | 304 / 470 | 469 / 469 |
| NTT | 13 | 13 | 152 / 178 | 178 / 178 |
| SCC | 82 | 84 | 994 / 1,097 | 1,097 / 1,097 |

三份文稿的 DOCX hash、revisionId、stale 状态均与切换前相同；没有重新生成文稿或改变采用状态。每份文稿与模板预览均返回真实 `application/pdf`、`%PDF-` 字节，通过 pypdf 解析、非空页和可提取文本检查；文稿预览与导出 PDF 字节完全相等，bindings 返回对应的 PDF hash 与 render profile hash。表中源模板预览使用带内部定位标记的工作副本。

bindings 的整体 `geometryStatus=ready` **不等于每个绑定都可精确定位**。表中逐项 ready 数量来自 [pdf-location-and-visual-review.json](.local/state/renderer-acceptance-20261008/pdf-location-and-visual-review.json)；其余文稿绑定分别为 SCT 166、NTT 26、SCC 103 个 `unavailable`，包括已删除目标或未能建立 exact 位置的绑定。所有 ready 点的坐标均有限、页号在对应物理 PDF 页边界内，源模板绑定全部 ready。页数和这些定位检查不构成合同内容、字体保真或全部页版式认证。

复验脚本 [verify_renderer.py](.local/state/renderer-acceptance-20261008/verify_renderer.py) 使用真实 API，依赖 pypdf 6.10.0。本次实际调用的 Python 命令如下；本机默认 Python 尚无 pypdf，重放应使用这个已带依赖的运行时：

```powershell
& 'C:\Users\Administrator\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' `
  '.\.local\state\renderer-acceptance-20261008\verify_renderer.py'
```

脚本复验既有保存 revision，不调用模型、不确认变量、不生成或编辑文稿；它会更新本验收目录下的 PDF、bindings 与验收收据，并通过预览接口读取或新增派生 PDF 缓存。

内置浏览器实测当前保存 SCT 的“PDF 版式核对”可显示第 1 / 23 页，跳转第 23 页成功，标准模板预览显示 29 页；收据 [browser-acceptance.json](.local/state/renderer-acceptance-20261008/browser-acceptance.json) 的 `status=passed` 只表示打开、显示和翻页功能通过，页面截图为 [sct-pdf-browser.jpg](.local/state/renderer-acceptance-20261008/sct-pdf-browser.jpg)。

**当前 `conversionFunctional=true / layoutCertified=false`，存在已观察到的分页忠实性问题。**在 [SCT-table-page-11.png](.local/state/renderer-acceptance-20261008/SCT-table-page-11.png) 中，保存文稿的 `SCT/14` 标签与下一段续页标题、续表出现在同一物理 PDF 页；同一现象也存在于原始模板的 [SCT-source.pdf](.local/state/renderer-acceptance-20261008/SCT-source.pdf) 物理第 16 页。因此不能把它说成本次接入 renderer 改动了原 DOCX 才引入，也不能仅凭 23 / 29 页的差异认定删页合理或内容丢失。

完整 XML、原始源文件、带定位标记工作副本和真实 PDF 对比见 [SCT-layout-diagnosis.json](.local/state/renderer-acceptance-20261008/SCT-layout-diagnosis.json)，结论 `PDF_CONVERSION_PASS_LAYOUT_FAIL`。已核对 `SCT/14` 是 `word/document.xml` 中正文段落的硬编码文本，非 footer 自动页码；源文件边界为正文 `p[163]` 与续表 `tbl[24]`，保存稿对应正文 `p[133]` 与续表 `tbl[19]`。两者该边界均未带强制 paragraph/run/section 分页，只有续表标题中的 `lastRenderedPageBreak` 缓存标记。源文件全篇为 1 个显式 page break、0 个 `pageBreakBefore`、25 个 `lastRenderedPageBreak`；保存稿分别为 1、0、20。缓存标记不等同于强制分页，源工作副本和保存稿保持了这一边界结构。

Word 版式与这些正文页码标签的忠实性仍需修复或认证。后续应从**当前冻结保存 DOCX** 生成保留正文、表格和用户编辑的 layout-only 候选，用父 revision/hash 与保留的书签核对目标，仅为已验证的完整续页标题添加显式分页，再经过新 revision 的 hash/内容保留检查与渲染视觉 QA；不能直接重新生成来覆盖用户文稿。本轮未执行该修复。已有抽查仅覆盖 SCT 的部分页，以及 NTT、SCC 首页面；不能将该问题归因为模型或宣称完整排版通过。字形与字体选择、字段刷新、全部页版式和用户本人业务验收仍未完成。

## 启动、停止与恢复

在项目根目录的 PowerShell 执行，或者双击同名 cmd 文件：

```powershell
cd 'D:\R&D\ConSense'
.\status-local.cmd
.\stop-local.cmd
.\start-local.cmd
```

这些入口调用项目专用 `.local\tools\powershell\pwsh.exe`，不依赖 Codex 的运行时路径。它们支持隐藏启动组件、端口冲突检测、重复启动复用与具体进程身份检查。停止前核对 PID、可执行文件、启动时间与该部署的命令行标记，拒绝对不属于本部署的 PID 操作。

Windows 任务名称为 `ConSense-Local`，当前用户登录后运行 `scripts/watch-local.ps1`。守护每 30 秒检查本部署组件，组件进程退出后重新启动；它不会因模型推理慢而任意杀死仍存活的进程。任务本身配置失败重试三次。已验证仅退出前端进程后的恢复与 API 代理正常；尚未实际重启整台 Windows 验证登录触发。

`stop-local.cmd` 停止守护和三个组件，并写入 `.local/state/stopped`，显式停止后不会被任务悄悄拉起。`start-local.cmd` 清除该标记、启动组件并恢复守护。组件启动与状态读取用规范化目录生成的全局互斥锁串行处理，可覆盖不同 Windows/RDP 登录会话。更新任务可执行：

```powershell
& '.\.local\tools\powershell\pwsh.exe' -NoProfile -File '.\scripts\install-local-autostart.ps1'
```

## 目录与依赖

| 路径 | 内容 |
| --- | --- |
| `.local/runtime.json` | 本机路径、端口、模型参数、DraftingRenderer 与 Java workaround 配置 |
| `.local/data/consense.mv.db` | H2 数据库 |
| `.local/data/uploads/` | 上传和生成文件 |
| `.local/models/` | 已校验的官方模型权重 |
| `.local/tools/` | Temurin JDK 17.0.20.1+1、Maven 3.9.11、PowerShell 7.4.13、PrismML 引擎、CUDA DLL 和项目内 LibreOffice |
| `.local/pdf-work/` | renderer 私有工作目录、版本探测与转换证据 |
| `.local/logs/` | 组件 stdout/stderr、构建、守护与诊断日志 |
| `.local/state/` | 进程身份、来源元数据、测试请求及验收收据 |
| `ai_consense_web/dist/` | 前端构建产物；Vite preview 提供页面及 `/api` 代理 |
| `ai_consense_service/target/` | 已打包 Spring Boot JAR |

Node.js 使用本机 `v24.19.0`。JDK、Maven、PowerShell、模型引擎及权重均从其官方发布下载并核对对应 SHA-256 / SHA-512。下载来源记录见 `.local/state/jdk-download.json`、`powershell-download.json`、`bonsai-download-plan.json`。

`.local/` 通过这个 clone 的 `.git/info/exclude` 忽略；模型、数据库、日志和本机配置不会成为 Git 提交。不要删除 `.local/data`，除非已完成备份并明确要清空本地业务数据。备份数据库应先运行 `stop-local.cmd`，然后复制整个 `.local/data`；恢复后再启动。

重新构建前先停止服务，避免 Windows 对正在运行的 JAR 锁定以及守护重新启动构建中的组件：

```powershell
.\stop-local.cmd
& '.\.local\tools\powershell\pwsh.exe' -NoProfile -File '.\scripts\build-local.ps1'
.\start-local.cmd
```

`build-local.ps1` 安装锁定 npm 依赖、执行包含 TypeScript 检查的前端 build，再执行选定后端测试和 package。这套脚本使用本机已准备的 `.local/runtime.json` 和工具；换到全新电脑需另行下载相同资产并生成对应本机配置。

## 本次修复

1. 前端 `DraftingDocumentWorkspace.vue` 的两处点击绑定改为 `closePdf()`，避免将 PointerEvent 传入布尔参数。
2. `DraftingPdfPreview.vue` 原先导入锁定 `pdfjs-dist@4.0.379` 不存在的 `TextLayer`；改用真实 `renderTextLayer` 与调用方输出数组，保留范围选择、缓存与取消功能。现有范围测试先复现失败，再验证修复。
3. `D:\R&D` 的 `&` 会触发 npm 生命周期默认 cmd 及旧 Surefire 的 Java 命令转义问题。npm 使用进程级 PowerShell script-shell；指定后端测试在 Maven 内运行（`-DforkCount=0`），未修改全局 npm 设置或 POM。
4. 当前 Windows 的 JDK AF_UNIX connect 返回 `Invalid argument`。默认和完整 Temp 路径的最小 Pipe probe 均失败。配置一个保证不存在的、部署专用 `jdk.net.unixdomain.tmpdir`，触发 JDK `PipeImpl` 自带的 loopback TCP 回退后 probe 成功，Tomcat 正常启动。该路径由启动脚本检查不可存在；这是一项局限于本 JVM 的 workaround，未修改系统网络、驱动、WSL 或防火墙。

## 初次 32K 部署验证与证据（历史）

以下表格及短样例记录的是初次 **32,768 context / thinking 关闭 / 2,048 总生成预算** 的部署测试，不是本次 64K medium 默认推广的验收结果。其访问链路和 lifecycle 证据保留供排查使用。

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 前端 npm ci / typecheck / Vite build | 成功 | `ai_consense_web/dist`，本轮命令退出 0 |
| 35 个 CommonJS 文件及 3 个 portable scripts | 均退出 0；实际上传模板回归因缺外部证据明确 SKIP | 前端现有 tests / scripts；无真实文档验收承诺 |
| 后端选定测试和 package | 28 条通过、4 条 native-layout guard 跳过 | `.local/logs/backend-build.log`，`target/surefire-reports` |
| 模型权重、引擎、CUDA DLL | SHA 和大小匹配固定官方资产 | `.local/state/bonsai-download-plan.json` |
| 直接模型 API | ordinary、strict JSON、中文合成字段均 `finish_reason=stop` | `.local/state/bonsai-probe-*.json` |
| 真实 Java adapter | `AiGateway → OpenAiLlmClient → 原 HttpSupport/OkHttp → Bonsai`，strict schema 与中文精确值断言通过 | `.local/state/consense-bonsai-adapter-acceptance.json` |
| Spring profile 和前端代理 | local/openai/ternary-bonsai-2-27b；`llm.available=true`；项目、技能、提示词接口 code=0 | `.local/state/integrated-api-acceptance.json` |
| 浏览器 | 起草首页显示模型已配置；提示词四项和技能三项正常加载，无应用 error 日志 | 本轮 Codex 浏览器观察 |
| 前端退出自动恢复 | 首轮 16.66 秒，代理恢复 code=0；进程 ID 已改变 | `.local/state/watcher-recovery.json` |
| 登录任务与独立运行时 | 任务 Running，执行项目专用 PowerShell | `.local/state/autostart-acceptance.json` |
| 完整停止/启动与可复现构建脚本 | 三个端口释放、数据库保留；实际构建脚本成功；重新启动后组件与模型均 ready | `.local/state/lifecycle-restart-acceptance.json`、`.local/logs/reproducible-build.log`、`.local/state/final-deployment-status.json` |

短中文合成样例为合同编号、雇主、合同金额、未提供完工日期四项，真实 adapter 返回正确四个值，经实际 JSON validator 验证，耗时约 2.413 秒、输出 62 tokens，服务端 decode 约 34.34 tokens/s。另一次直连样例为约 35.13 tokens/s。它们是小样本观察，不是长上下文吞吐或完整合同质量基准。

模型加载和短样例后 `nvidia-smi` 的整卡数值为 used 9455 MiB、free 6715 MiB；其中包含桌面与其他进程，不应当全部归给模型。启动前同机约 used 1704 MiB、free 14466 MiB，模型启用后的余量会随原有 GPU 服务变化。

重放真实适配器小样例：

```powershell
& '.\.local\state\run-model-probe.ps1'
```

重放脚本显式使用 UTF-8。第一次独立 Java source launcher 按系统 GBK 解码 probe 源码的失败记录另行保存；没有把该失败替换或隐藏为成功。ConSense 本身由 Maven 以 UTF-8 编译，启动脚本也显式配置 JVM 文件及 stdout/stderr 为 UTF-8。

## 仍需业务验收的部分

当前未配置 OCR、独立 BGE-M3 embeddings 和外部向量检索。格式化 PDF renderer 已按上方配置接入；字体选择、字形覆盖、字段刷新和实际版式仍按各自证据验收。`/api/system/health.ready=true` 仅说明聊天模型与 memory vector 的已有检查通过，不验证 embeddings、完整咨询索引或 PDF renderer。Bonsai 不能当作 BGE-M3；后续可以加真实 embedding 路由或独立配置 seam。

人工输入、项目/提示词/技能配置可使用；具备正确模板和已确认输入时，当前源码的 DOCX 生成是确定性的模板编译。自动资料抽取已有上文限定范围的真实考题结果；长合同、复杂证据判断、扫描材料、完整合同业务结果与 PDF 排版仍需各自验收，64K medium 的前端用户本人操作验收也尚未完成。

锁定前端依赖的 npm audit 当前报告 9 项（4 moderate、4 high、1 critical），本轮未进行依赖全集升级；依赖修补需另行验证 PDF 与文档解析兼容性。

NInfer 经验、公开 Ampere 移植和自行开发难度详见 [研究记录](docs/bonsai-local-feasibility.md)。当前已有可工作的官方三元 CUDA 路线，获得 LLM API 不需要自己重写内核；NInfer Windows 构建、专属模型转换与性能配对评估属于后续可独立验证的优化。
