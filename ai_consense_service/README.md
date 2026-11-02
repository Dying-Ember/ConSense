# 服务端：配置、集成和验证

准备比赛资料或开发 OER 功能前，请先阅读 [OER 已确认范围与执行规则](docs/oer-scope-and-rules.md)，再阅读 [实施交接](docs/HANDOFF-ocr-embedding-rerank-20261008.md)，了解当前模型、服务验证与未完成的验收。下载资料、修改解析／索引方案或启用 OER 配置前，均应以这两份文档和用户最新决定为准。

比赛五容器的打包、外置配置、相对路径、端口、模型与数据交付说明见[比赛五容器部署说明](../docs/COMPETITION-DEPLOYMENT.md)。五个镜像是启动壳，不能仅凭镜像归档认定完整系统可部署；实际资产和三个模块的验收状态以该说明和最新记录为准。

最初导入的服务端源码版本为 `67ceb7081d42204aa17b12b149f52081e348da71`。此前集成使用远端 Drafting 基线 `2e75126` 及本地 Vetting／Advisory `67ceb708..82ea3d9`，详见仓库根目录的 `SOURCE-INTEGRATION.json`。模块名称为 `ai_consense_service`。

初次源码快照没有导入既有项目、上传文件、数据库记录、真实模型／运行记录、旧 Git 历史、模型缓存和密钥。当前比赛分支另行整理的模板、模型及数据应以仓库的比赛资产清单为准，不代表历史项目数据已恢复或业务验收完成。

三个运行时 Drafting 登记文件和 13 个 Flyway 迁移文件（包括 V12／V13）均予保留；skills 种子文件按通用定义保留原始字节。`projects.json`、`vetting-files.json` 和 `findings.json` 均为空数组，因此新安装不会预置演示项目、来源记录或审查发现。`guidance-compiler-bindings.json` 仅调整了三处 `originalSourceDocuments.*.sourcePath` 字符串，其余绑定规则、来源／文本哈希、清单哈希和其他字节保持不变。

## 构建与本地检查

使用 Maven 和支持 `ProcessHandle` 的已审阅 JDK。格式化 PDF 的进程管理要求 Java 9 或更高版本；该快照曾在 JDK 17 上检查。POM 的编译 source／target 仍为 Java 8。

```sh
mvn -Dtest=DraftBusinessRulesTest,DocxNativeLayoutGuardTest test
mvn -DskipTests package
```

10 月 9 日的上游集成使用 JDK 17.0.20.1、Maven 3.9.16 和 `mvn -B -Dconsense.test.python=<prepared-interpreter> clean package` 检查。默认测试记录为 1,618 项，其中 1,550 项通过、68 项跳过，无失败或错误；当时生成了可执行 JAR。协议测试样例没有调用真实模型服务，也没有执行神经网络推理、加载 tokenizer、访问向量数据库或运行 OCR。这些数字是历史上游集成记录，不是当前工作树的新测试结果。

另有五个测试文件依赖私有原件、真实运行记录或实际模板／渲染器，保存在 `tests/external-fixtures/java`，不属于默认测试源码目录。五个 Vetting 协议／辅助测试文件已放回默认测试目录，并使用冻结的非密钥元数据样例；它们仍需要已安装文档所列依赖的 Python 环境。可设置 `-Dconsense.test.python=<interpreter>`、`CONSENSE_TEST_PYTHON`，或使用服务目录的 `.venv`。详见 [外部测试样例说明](tests/external-fixtures/README.md)。跳过的测试和外部测试不计为已通过。

13 项通过的规则测试和 4 项跳过的原生布局检查属于初次导入时的记录，并非当前集成的测试总数。`target/` 下的编译产物由 Git 忽略，不应提交；构建和测试回执保存在发布目录之外。

## 配置

通过已认证的 MiniMax-M3 中继进行协作开发时，参见 [MiniMax 中继交接](docs/minimax-relay-handoff.md)。`minimax-relay` Spring profile 使用独立的中继令牌，保留非流式结构化输出，并由中继处理 529 重试。[.env.example](.env.example) 只包含占位符：可复制为被 Git 忽略的 `.env.local`，再将配置加载到后端进程环境中。该交接还包含前端变量测试和用于审阅代码建议的纯文本 SDK 客户端。

应用通过 `SPRING_PROFILES_ACTIVE` 选择 profile。H2 是开发配置，应提供新建的空数据库或独立的部署数据库；不要将当前本地项目数据库当成新部署的初始数据。

源码支持以下环境变量；默认值会随 Spring profile 变化，具体以实际加载的 `application*.yml` 为准：

| 名称 | 用途 |
| --- | --- |
| `CONSENSE_STORAGE_ROOT` | 上传、项目及导出文件的可写目录；未设置时使用通用相对路径 |
| `CONSENSE_ALLOWED_ORIGINS` | 允许访问后端的前端来源；未设置时使用通用本机示例 |
| `CONSENSE_LLM_BASE_URL` | 通用 LLM 服务地址 |
| `CONSENSE_LLM_ENABLED` | 是否启用可选模型服务；沿用源码默认值 |
| `CONSENSE_MINIMAX_BASE_URL` | MiniMax 服务地址 |
| `CONSENSE_MINIMAX_ENABLED` | 是否启用 MiniMax；沿用源码默认值 |
| `CONSENSE_OCR_BASE_URL` | OCR 服务地址 |
| `CONSENSE_OCR_ENABLED` | 是否启用 OCR；沿用源码默认值 |
| `CONSENSE_VECTOR_BASE_URL` | 向量服务地址 |
| `CONSENSE_VECTOR_API_KEY` | 向量服务凭据；默认空值 |
| `CONSENSE_RETRIEVAL_URL` | 本地 Vetting／OER profile 的混合检索地址 |
| `CONSENSE_H2_JDBC_URL` | 开发用 H2 JDBC 连接 |
| `CONSENSE_VETTING_LOCAL_JDBC_URL` | 本地 Vetting profile 的 H2 JDBC 连接 |

还有以下既有配置项：

| 名称 | 用途 |
| --- | --- |
| `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_DATABASE` / `MYSQL_USER` | 部署数据库的连接信息 |
| `MYSQL_PASSWORD` | 数据库凭据，默认空值 |
| `MiniMaxCN_Token_Plan_API_Key` | MiniMax 凭据，默认空值 |
| `SPRING_PROFILES_ACTIVE` | 选择 Spring profile |

比赛环境的 Qwen、Source、GPU、H2 和 Qdrant 参数，以及可复制填写的脱敏配置文件，见[比赛部署说明](../docs/COMPETITION-DEPLOYMENT.md)。Qwen 服务的实际地址、模型和鉴权须按该说明配置，不能仅凭 URL 判定模型服务可用。

不要将真实凭据写入提交到仓库的示例文件。部署时应通过密钥存储或进程环境提供密钥，并通过对应的 `consense.*` Spring 属性配置服务提供方、模型 ID、超时、token 预算、检索契约字段和向量集合。

若只启动源码、不使用可选服务，可在进程中设置 `CONSENSE_LLM_ENABLED=false`、`CONSENSE_MINIMAX_ENABLED=false` 和 `CONSENSE_OCR_ENABLED=false`。这样不会使真实模型、OCR 或检索功能可用；使用相关功能前仍需配置并启动相应服务。

PDF 渲染需提供 `consense.drafting.renderer-kind`、`renderer-executable`、`renderer-asset-root`、`renderer-font-cache`、`renderer-work-root` 和 `renderer-timeout-ms`。x2t 路径需要只读且不可变的资源、预先生成的 `AllFonts.js`、非空的 `font_selection.bin` 及其引用字体，并需要独立的可写渲染工作目录。LibreOffice 路径需要无界面的 `soffice`、字体和独立可写的工作目录。报告字体通过 `consense.report.font-path` 配置。渲染程序和字体不随初次源码快照自动安装；比赛分支的外置资产及真实 PDF 预览状态以部署说明为准。

新项目的 NTT／SCT／SCC 和沟通资料需要通过应用选择或上传；源码快照本身不能恢复既有项目或此前已验证的排版环境。比赛默认模板及其替换规则见部署说明，SCC 每个项目只能选择一个版本。

## 可选 Python OCR／检索工具

`tools/vetting_eval` 包含实现和固定版本依赖；Torch／CUDA 以及模型权重和缓存需要另行准备。正式 OER 模型入口要求 CUDA，在 CUDA 不可用时会失败，不提供 CPU／自动设备选择或静默回退。`scripts/oer-local.ps1` 使用独立的 `.local/oer/venvs/retrieval-gpu` 环境；历史 CPU 评估环境和冻结运行仍作为基线保留。来源绑定的模型、设备、dtype 和索引身份必须匹配，因此 CUDA 方案应在独立状态下重建。当前确认状态见 [OER 范围与运行规则](docs/oer-scope-and-rules.md)及[实施交接](docs/HANDOFF-ocr-embedding-rerank-20261008.md)。

`samples.json` 保留历史样本评估配置：8 个来源条目、5 个 OCR 案例和 8 个查询。它只包含相对引用和样本检索词，不包含必需原件，也不是新的准确率结果。服务会读取这一模型配置结构；按需提供部署模型、运行配置和已授权的外部资料。

`tests/external-fixtures/manifests/` 保留来源身份清单，其中包含相对文件名、哈希及元数据，不含文档正文。带绝对路径的历史查询重放、真实来源摘录、模型／运行轨迹及输出保存在外部。旧提示词探针作为历史文档保存在 `docs/archive`。评估脚本仍需要已授权的外部原件和配置好的模型。

## 验证范围

初次打包检查保存在 `SOURCE-SNAPSHOT.json`；集成来源、测试及限制保存在仓库根目录的 `SOURCE-INTEGRATION.json`。当时集成了 V12／V13、范围／清单／报告相关源码、配套前端 API、测试样例和历史文档，并保留远端 Drafting PR #3 的内容。

上游集成曾记录源码兼容性、默认测试套件及构建通过。这些历史回执不能证明当前工作树或实际部署已通过。实际模型服务容量、真实模型输出质量、OCR／检索精度、MySQL／Flyway 部署及最终原件 PDF 排版仍需分别验收；历史结果或合成结果不能冒充新的业务验收。
