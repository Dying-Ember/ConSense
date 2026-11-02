# ConSense 前端 — 当前源码快照

此前端最初从源码提交 `629ef98d197cd83e4e7128e468b292492e4443f2` 导入新仓库，未带入原仓库的旧 Git 历史。初始源码随后合入远端 Drafting PR 和 10 月 9 日的本地 Vetting／Advisory 改动。npm 锁文件中 jsdom／canvas 的可选对等依赖已修正，并曾通过全新 `npm ci` 测试。当前源码来源和历史验证记录见仓库根目录的 `SOURCE-INTEGRATION.json`。历史范围检查的基线查找已做可移植处理，精确的旧基线作为测试样例保留。

## 安装、启动和构建

使用与仓库现有 Vite／Vue 工具链兼容的 Node.js，在本目录执行：

```powershell
npm ci
$env:CONSENSE_API_TARGET = 'http://localhost:8080'
npm run dev -- --host 127.0.0.1 --port 5173
```

将 API 目标地址设为配套服务实际监听的端口。前端通过 `/api` 调用服务；模型服务密钥和数据存储属于后端配置，不包含在前端源码中。前端能够启动本身不能证明后端或模型已就绪。

使用共享 MiniMax-M3 relay 时，参见 [MiniMax relay 配置、测试和开发交接](docs/minimax-relay-handoff.md)。配套后端使用 `h2,minimax-relay`，将 `CONSENSE_API_TARGET` 指向该本地后端，并在后端／客户端配置中单独保存 relay 令牌；前端不保存令牌。交接文档还说明了使用 MiniMax 进行真实模型回归、代码分析与修改，以及随后独立验证的方式。

```powershell
npm run typecheck
npm run build
```

比赛五容器的镜像打包、配置文件、相对路径、端口和部署限制，见仓库根目录的[比赛五容器部署说明](../docs/COMPETITION-DEPLOYMENT.md)。

## 可移植检查

仓库包含人工编写的控件／模型测试样例和测试辅助代码。在本目录执行前端 CommonJS 检查：

```powershell
Get-ChildItem -LiteralPath ./tests -Filter '*.test.cjs' | ForEach-Object {
  node $_.FullName
  if ($LASTEXITCODE -ne 0) { throw "Failed: $($_.Name)" }
}
node scripts/test-drafting-step3-scope.mjs
node scripts/test-drafting-step3-workspace.mjs
node scripts/test-vetting-view.mjs
node scripts/test-advice-scope-view.mjs
```

`tests/fixtures/template-reading.synthetic.docx` 是人工编写的合成测试数据。Drafting 范围脚本直接检查当前工作流程。

## 上游整合检查（历史记录）

2026 年 10 月 9 日上游整合时，全新 `npm ci`、类型检查和构建通过。CommonJS 检查记录为 36 项通过、零失败、1 项真实模板导航测试跳过；四个视图／工作区脚本均通过。可选的真实文档读取分支和 OCR 诊断重放需要未纳入该快照的外部证据，当时没有运行。这些是上游整合回执，不是当前工作树的新测试结果。

## 外部诊断样例和历史记录

`scripts/test-inspection-ocr-diagnostics.mjs` 已保留，但它需要 `tests/fixtures/inspection-ocr-diagnostics.actual.json`。该本地 OCR 运行输出原始样例未随此前端快照发布，因此**仅凭此快照无法运行**诊断脚本。如需重放历史测试，应另行提供经授权的精确外部样例；也可建立并明确标注新的合成／脱敏样例及对应测试，但不能将替代样例称为原始观察。随仓库保留的样例 README 说明了这一边界。

`public/reports` 下可选生成的 H800 规划报告 PDF／HTML 未随此前端快照提供，应用代码不依赖它们。私人通信、模型密钥、运行数据库、用户上传文件、已安装依赖和生成的运行输出不包含在此前端快照中。比赛默认模板和其他交付资产以仓库根目录的比赛部署说明和实际文件为准。

`docs/specs/verification` 下的文档和旧追踪条目均为历史记录；其中的本机绝对证据路径与原仓库提交 ID 在此快照中可能无法解析。它们不是新克隆仓库的验证结果。请运行上述可移植检查，并使用另行提供且经授权的项目资料独立验证真实业务输出。
