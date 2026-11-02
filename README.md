# ConSense

ConSense 提供合约起草（Drafting）、审查（Vetting）、来源证据查看和咨询（Advisory）功能。Vue 前端和 Spring Boot 服务保存在同一个源码快照中，方便对应前端、文件识别、业务规则和文稿生成接口。

处理比赛资料范围、OCR、向量化、重排、模型部署或索引前，请先阅读 [OER 已确认范围与执行规则](ai_consense_service/docs/oer-scope-and-rules.md)，再阅读 [当前实施交接](ai_consense_service/docs/HANDOFF-ocr-embedding-rerank-20261008.md)。其中会区分当前决定和历史实验记录。

## 比赛容器交付

五容器的打包、导入、部署、外置资产、相对路径、端口、配置项和已知缺口，见 [比赛五容器部署说明](docs/COMPETITION-DEPLOYMENT.md)。

以下路径相对于仓库根目录：

| 路径 | 内容 |
| --- | --- |
| `competition-assets/images/` | 五个冻结镜像：`app.tar`、`source.tar`、`gpu.tar`、`h2.tar`、`qdrant.tar`，及 `SHA256SUMS` 校验文件 |
| `competition-assets/templates/` | NTT、SCT、Word SCC 和 PDF SCC 四份默认模板 |
| `competition-assets/drafting-pdf-reference/` | PDF SCC 的历史图谱候选，尚非完整人工确认的正确图谱 |
| `competition-assets/models/mineru-r153-adapter/` | 使用 Git LFS 保存的重训 MinerU R153 适配权重；需配套基础模型使用 |

五个镜像是启动壳，完整应用发布物、外置运行环境、模型和数据库需要另行提供。其余模型权重、运行依赖和纯 SL 数据库仍在整理，不能因为已有 tar 就认定可以独立部署。Drafting 图谱及三个模块的端到端验收也尚未完成。

部署说明中的目录以部署根目录为基准；宿主机的相对路径需要先解析成绝对路径，再用于 Docker 挂载。目标容器内路径、挂载模式、配置文件、端口及需现场填写的变量均在部署说明中列出。密钥和口令由现场单独注入，不放在仓库里。

## 仓库目录

| 目录或文件 | 内容 |
| --- | --- |
| `ai_consense_web/` | Vue 3 / TypeScript / Vite 前端、三语言界面、起草核对、文件对照、业务关系图和前端检查 |
| `ai_consense_service/` | Spring Boot 服务、识别与证据工具、业务登记、DOCX 生成、文件渲染适配、审查检索工具和服务测试 |
| `SOURCE-SNAPSHOT.json` | 10 月 7 日首次导入的历史版本和打包检查 |
| `SOURCE-INTEGRATION.json` | 10 月 9 日整合来源、当时的检查和未完成验收 |
| `docs/PRIVATE-REPOSITORY-SYNC.md` | 源码同步流程与资料边界 |

本源码快照使用独立 Git 历史。凭据、正在使用的项目数据库和私人通信不纳入仓库；人工编写的合成样例仅供测试。既有运行记录不能代替新部署的运行和业务验收。

## 本地开发启动

历史发布使用 JDK 17、Maven 3.9 编译，前端使用 Node.js 24 和已有锁定依赖检查。本地开发需安装兼容的 Node.js、npm、JDK 17 和 Maven；这些运行环境和依赖未随源码自动安装。

若本地使用 H2，在 `ai_consense_service/` 目录运行：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'h2'
$env:CONSENSE_ALLOWED_ORIGINS = 'http://localhost:5173,http://127.0.0.1:5173'
# 启用模型或 OCR 前，先配置并启动相应服务。
$env:CONSENSE_LLM_ENABLED = 'false'
$env:CONSENSE_MINIMAX_ENABLED = 'false'
$env:CONSENSE_OCR_ENABLED = 'false'
mvn spring-boot:run
```

默认 HTTP 端口为 `8080`。H2 数据和上传文件保存在服务的本地 `data/` 目录，由 Git 忽略。如果未设置 `SPRING_PROFILES_ACTIVE`，应用默认使用 MySQL；MySQL 和完整运行配置见服务目录的 README。新项目资料需要在本地创建或上传。

另开终端，在 `ai_consense_web/` 目录运行：

```powershell
npm ci
$env:CONSENSE_API_TARGET = 'http://localhost:8080'
npm run dev -- --host 127.0.0.1 --port 5173
```

打开 `http://127.0.0.1:5173/#/drafting`。根据实际端口设置前端 API 目标和服务端允许的来源；前端通过 `/api` 代理访问服务。

使用 Windows 启动脚本 `scripts/local.ps1` 时，可在不纳入 Git 的 `.local/runtime.json` 中设置 `BackendMaxHeapMiB` 控制后端 Java 堆。未设置时默认最大堆为 32768 MiB（32 GiB）；显式值优先。例如 `"BackendMaxHeapMiB": 32768` 表示最大堆 32 GiB，初始堆仍为 256 MiB，并按需要增长。正在运行的后端只有在授权重启后才会使用新限制：

```powershell
pwsh -File scripts/local.ps1 -Action restart-backend
```

历史全文 OER 离线检查以 32 GiB 堆和 48 GiB 自有进程内存上限启动；如实测需求与宿主可用空间允许，可相应增加。还需考虑 Windows、OCR、模型等同时运行的负载；Java 堆、进程或容器内存以及显存是不同的限制。

相同的本地运行配置还可以为普通后端启用独立管理的 OCR 协调服务：

```json
"BackendOcr": {
  "Enabled": true,
  "Mode": "local",
  "BaseUrl": "http://127.0.0.1:18780",
  "TimeoutMs": 1800000,
  "TextLayerMinChars": 40
}
```

没有 `BackendOcr` 或设置 `"Enabled": false` 时，启动器保持 OCR 关闭。启用时须填写全部五项，使用带明确端口的 HTTP 回环地址、符合 OkHttp 调用限制的正整数超时和正数文本层阈值。`BaseUrl` 指向协调服务，后端会追加 `/health`、`/ocr` 和 `/native-pdf/table-candidates`。应用该设置并重启后端之前，要单独启动并验证完整识别协调服务；启动器不会启动 OCR 提供方，也不证明模型或原件识别质量。可运行 `pwsh -File scripts/tests/test-local-backend-ocr.ps1` 检查配置。

## 模型、OCR 与文件导出

服务提供本地模型和 MiniMax 中国 Token Plan 选项。分别使用 `CONSENSE_LLM_BASE_URL` 和 `CONSENSE_MINIMAX_BASE_URL` 配置实际服务地址，并启用对应配置。MiniMax 密钥通过服务进程环境变量 `MiniMaxCN_Token_Plan_API_Key` 提供；仓库不包含真实密钥。模型可用性和识别质量需要针对实际部署测试。

比赛环境使用的 Qwen、Source、GPU、H2、Qdrant 配置键及现场注入方式见[比赛五容器部署说明](docs/COMPETITION-DEPLOYMENT.md)。OCR、向量化、重排和向量检索各有独立运行依赖。DOCX 生成使用上传的可编辑模板和业务绑定；PDF 生成还需要单独配置渲染环境及字体，在线阅读对照与导出 PDF 排版是不同用途。支持的适配与外置资产见服务 README 和渲染配置。本快照未提供付费渲染器或商业许可。

## 历史检查及范围

2026 年 10 月 9 日的整合保留了 Drafting PR #3，并加入当时的 Vetting / Advisory 改动。该次前端重新执行 `npm ci`、类型检查和生产构建通过；后端 `clean package` 执行 1,618 项测试，1,550 项通过、68 项跳过，无失败或错误。前端 CommonJS 检查中 36 项通过、1 项因外部依赖跳过，另外四个视图或工作区脚本通过。五个依赖真实文件或运行时的测试文件仍在 Maven 默认测试源码目录之外，回环协议检查需要准备 Python 依赖。这些历史检查不证明真实模型、OCR、检索、MySQL 部署或原件版式的质量，详见 [SOURCE-INTEGRATION.json](SOURCE-INTEGRATION.json) 和 [同步记录](docs/PRIVATE-REPOSITORY-SYNC.md)。

以下数据仅对应 10 月 7 日首次导入：导出的前端通过类型检查、Vite 生产构建、35 项 CommonJS 检查和三个工作区、范围或视图检查；导出的服务通过 Maven 打包和 13 项业务规则测试，未调用真实模型。四个原生版式检查因未提供外部运行环境而跳过。上述检查使用了本地依赖缓存，不证明全新依赖安装、外部服务、原生 PDF 排版或新部署业务验收成功。

运行前后端各自 README 中的检查时，请区分测试范围。需要原件或真实运行记录的测试单独列于 `ai_consense_service/tests/external-fixtures/`；Java 源码已保留，但在对应样例恢复前不属于默认 Maven 测试。可选的前端 OCR 诊断重放同样需要外部样例。不得把未标明的合成结果当作缺失的原件证据。

## 初始快照来源

后续整合另见 `SOURCE-INTEGRATION.json`；下列版本仅描述首次导入：

| 组件 | 来源版本 |
| --- | --- |
| 前端 | `629ef98d197cd83e4e7128e468b292492e4443f2` |
| 服务 | `67ceb7081d42204aa17b12b149f52081e348da71` |

首次发布保留应用业务逻辑和变量回答行为，并为发布而处理了可移植范围、私有数据、配置占位、演示项目初始数据及外部运行依赖说明。该次导出没有修改当时的源工作树或正在运行的本地应用。
