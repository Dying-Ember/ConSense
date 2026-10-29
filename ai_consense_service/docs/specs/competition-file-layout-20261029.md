# 比赛部署文件清单与相对路径

**用途：**帮助准备本次代码外置、镜像构建和硬盘搬运；本文件只在本地及远端代码仓库维护，不搬到比赛运行环境。

**冻结依据：**完整提交 `8a40dfcca04730ba7b40cdefb36b596a37bbcb53`。此前未完成的工作已按用户指示作废；此清单只服务于该基线的现有功能外置及必要配套。重构允许移动、修改代码，但应与冻结基线对照验证。

**重要状态：**仓库位置和“当前”挂载位置来自冻结基线代码；下文“建议”硬盘位置只是统一摆放方案，尚未创建、接线或在比赛平台验证，不能直接照抄后宣称已可运行。真实运行依赖、各文件读取位置和数据身份须在实施时逐项核对。比赛硬盘可来自多个挂载卷，树形示例只表示逻辑相对路径，并不要求赛方提供同一个物理目录。

## 冻结基线中，现在的镜像是怎么分的

这里回答“**目前代码怎么定义镜像**”，不是新的目标方案。冻结提交中的离线部署代码是候选准备程序：负责核查清单、生成部署资源和任务模板；仓库代码本身不能证明这些镜像已经构建、扫描、上传或在比赛环境运行。仓库中跟踪的镜像构建文件共有 **5 份**，都在 `ai_consense_service/tools/offline_delivery/`。MySQL 和 Qdrant 使用清单提供的现成镜像，仓库没有它们的 Dockerfile。

### 1. 五份项目镜像构建文件

| 镜像构建文件及角色 | 当前明确放进镜像的内容 | 当前怎样启动／硬盘放什么 | 当前与外置目标的差距 |
| --- | --- | --- | --- |
| [Dockerfile.backend](../../tools/offline_delivery/Dockerfile.backend)：默认后端 | Java 运行环境基础镜像；`consense-service.jar` 复制到 `/app/`；`fonts/` 复制进镜像。 | 容器执行 `java -jar /app/consense-service.jar`，端口 8080。业务运行包在镜像里。 | 改 Java 业务后要重新生成 JAR；此方案还要重做镜像才能放入新版 JAR。字体也在镜像里。 |
| [Dockerfile.backend-external](../../tools/offline_delivery/Dockerfile.backend-external)：外置 App 后端 | Java 运行环境基础镜像；项目 `app-entrypoint.sh` 和 `fonts/` 仍复制进镜像。 | 启动镜像内 `/app/app-entrypoint.sh`，从硬盘挂载的 `/artifacts/` 读取 JAR 和完整网页目录；Java 同时提供网页和后端。 | 已外置 JAR 与网页产物；启动脚本和字体仍在镜像内。Java 源码修改仍需重建 JAR，网页源码修改仍需重建完整前端。 |
| [Dockerfile.web](../../tools/offline_delivery/Dockerfile.web)：默认独立网页 | Nginx 基础镜像；前端 `dist/` 和项目 `nginx.conf` 复制进镜像。 | 使用基础镜像的网页服务，端口 80；默认方案有单独的网页容器。 | 前端产物和网页服务配置在镜像里，更新网页要重新准备镜像；外置 App 方案不使用该网页角色。 |
| [Dockerfile.coordinator](../../tools/offline_delivery/Dockerfile.coordinator)：可选资料／任务协调 | Python 运行环境基础镜像；项目 `vetting_eval/`、`offline_delivery/` 两个目录和 `kubectl` 复制进镜像。 | 以 `/app/vetting_eval/server.py` 启动；读取挂载的 `/artifacts/` 和 `/workspace/`。只在部署清单配置协调器时创建。 | 两个项目 Python 目录仍在镜像内；启动路径也被部署生成程序写死。 |
| [Dockerfile.gpu-worker](../../tools/offline_delivery/Dockerfile.gpu-worker)：显卡任务通用构建文件 | 指定的显卡本机运行环境基础镜像；项目 `vetting_eval/` 和 `offline_delivery/` 两个目录复制进镜像。 | 启动 `/app/offline_delivery/stage_worker.py`，再执行任务清单指定的实际程序；按需创建任务容器。 | 项目 Python 代码仍在镜像内。当前不是已经做好的一个固定显卡容器；不同任务可指定不同基础环境。 |

五份文件中的基础镜像都通过构建参数传入：Java 的 `JAVA_RUNTIME_IMAGE`、网页的 `NGINX_RUNTIME_IMAGE`、协调器的 `PYTHON_RUNTIME_IMAGE` 和显卡任务的 `NATIVE_RUNTIME_IMAGE`。从这些文件不能确定实际发行版、版本、具体镜像名、内部所有依赖或最终 SHA；需以实际清单及构建记录核对。当前 Dockerfile 没有明确复制模型权重，候选清单要求从硬盘挂载模型等资产；但基础镜像内部实际有什么，不能仅凭 Dockerfile 断言。

### 2. 部署清单里有哪些镜像角色

除了后端、网页、协调器和两个数据库，现有清单要求为 **5 种显卡任务分别填写镜像**。五种任务可以使用同一份 `Dockerfile.gpu-worker`，但各自可指定不同的运行环境和最终镜像：

| 清单中的角色 | 做什么 | 目前如何提供 |
| --- | --- | --- |
| `mysql` | 关系数据库 | 清单指定现成镜像；数据挂载到 `/var/lib/mysql`。 |
| `qdrant` | 向量数据库 | 清单指定现成镜像；数据挂载到 `/qdrant/storage`。 |
| `backend` | Java 业务；外置 App 模式也提供网页 | 清单指定后端镜像；默认和外置 App 使用不同构建文件。 |
| `web` | 独立网页 | 只有默认模式要求；外置 App 模式由后端提供网页。 |
| `coordinator` | 资料服务及显卡任务协调 | 可选；只有启用协调器时，清单才要求此镜像并启动容器。 |
| `paddle` | Paddle OCR 阶段 | 清单单独指定此阶段镜像，任务按配置启动。 |
| `mineru` | MinerU OCR 阶段 | 清单单独指定此阶段镜像，任务按配置启动。 |
| `document-embedding` | 资料向量生成阶段 | 清单单独指定此阶段镜像，任务按配置启动。 |
| `query-embedding-rerank` | 查询向量与重排阶段 | 清单单独指定此阶段镜像，任务按配置启动。 |
| `business-llm` | 业务大模型阶段 | 清单单独指定此阶段镜像；比赛目标是自行部署 Qwen3.8 27B，实际接入尚待实现与验证。 |

清单要求每个角色填写带 SHA256 的镜像标识，但**没有要求所有角色必须指向不同镜像**。因此这里的 5 个显卡角色，不等于已证明有 5 份不同镜像文件；具体镜像内部是否具备对应模型和依赖也尚未验证。任务真正执行的程序还由清单中的命令和运行资产决定，不是有一个角色名就已经能运行。

### 3. 镜像角色数量和运行容器数量

镜像是启动容器所用的文件；一个镜像可用于不同角色，五种显卡任务的镜像也不代表五个任务容器同时运行。现有代码生成五个显卡任务模板，按需创建任务；网页、数据库、后端及可选协调器是另外的常驻容器。

| 现有生成方式 | 镜像清单角色数 | 常驻容器 | 正常启动一个显卡任务时的计划数量 |
| --- | ---: | --- | ---: |
| 默认方案，不启用协调器 | 9：MySQL、Qdrant、后端、网页及 5 种显卡角色 | MySQL、Qdrant、后端、网页，共 4 个 | 5 个 |
| 默认方案，启用协调器 | 10：上面 9 个角色加协调器 | 上面 4 个加协调器，共 5 个 | **6 个，超过用户要求** |
| 外置 App，不启用协调器 | 8：MySQL、Qdrant、后端及 5 种显卡角色 | MySQL、Qdrant、后端，共 3 个 | 4 个 |
| 外置 App，启用协调器 | 9：上面 8 个角色加协调器 | 上面 3 个加协调器，共 4 个 | **5 个，刚好达到上限** |

表中镜像角色数是清单字段数，角色可指向同一实际镜像；容器数量是代码正常生成、一次只启动一个显卡任务时的计划数，**不是现场实测，也不是对任何时刻不超 5 个的证明**。替换旧实例、新任务并发、初始化及平台额外加入的容器都需要另行计数。网络 Service 只是访问入口，不是另一个容器。没有启用协调器的行只说明数量，不能据此断言完整业务功能可用。

### 4. 当前代码怎样使用这些镜像

`delivery.py` 根据清单生成 MySQL、Qdrant、后端、可选网页及协调器的部署定义；5 个显卡任务分别生成任务模板，不随常驻部署自动全部启动。需要执行阶段时，再按清单指定该阶段镜像和程序创建任务。协调器和显卡任务的代码分别明确指向镜像内 `/app/vetting_eval/server.py` 和 `/app/offline_delivery/stage_worker.py`；请求执行阶段还会再次覆盖任务启动命令。未来外置不能只改 Dockerfile，必须一并处理实际启动位置。

目前唯一已实现的外置 App 模式是从硬盘读 JAR 和网页产物；它没有把协调器及显卡任务 Python 代码外置，也没有实现固定显卡执行容器。当前有限显卡任务的业务模型路径不支持逐段正文输出；接入方案要保护冻结基线已有能力。依据：[镜像与阶段清单校验](../../tools/offline_delivery/delivery.py)、[离线部署说明](../../tools/offline_delivery/README.md)、[请求阶段启动](../../tools/offline_delivery/request_stage.py)及上表的五份 Dockerfile。

## 一眼看懂

```text
代码仓库（本机及远端保留完整源码、spec、部署说明和历史文档）
├─ ai_consense_service/       后端源码、业务资源、OER 与部署脚本
├─ ai_consense_web/           前端源码和构建配置
├─ scripts/                   本机启动和维护脚本，逐项确认是否需要现场版本
└─ docs/、各项目 docs/        本地和远端维护；不作为比赛环境搬运对象

比赛硬盘逻辑根目录（下列为建议，相对路径尚未接线）
├─ source/                    需要现场修改、构建的代码及必要构建输入
│  ├─ ai_consense_service/
│  │  ├─ pom.xml
│  │  ├─ src/main/java/
│  │  ├─ src/main/resources/
│  │  └─ tools/{offline_delivery,vetting_eval}/
│  └─ ai_consense_web/
│     ├─ src/
│     ├─ index.html
│     ├─ package.json、package-lock.json、tsconfig.json、vite.config.ts
│     └─ 其他构建确实依赖的文件
├─ releases/<版本>/           经构建、校验并固定的运行版本
│  ├─ app.jar                 Java 可执行包
│  ├─ web/                    完整前端 dist，含分包和 PDF worker
│  └─ 必要的脚本／资源版本快照及清单
├─ config/                    外部配置；秘密单独限制访问
├─ models/                    Qwen、已采用 OCR、检索及各自配套文件
├─ fonts/                     文档输出确实需要且许可允许外置的字体
├─ data/                      MySQL、Qdrant、原件、任务、结果及证据
└─ logs/                      必要的可关联排障日志；原始证据分开保护
```

`<版本>` 是示意占位符；建议保留工作源码和独立运行版本：修改源码后构建并形成完整版本，再受控切换，不原地覆盖运行中使用的文件。建议目录名不是已实现的固定接口。任一确实需要不同放置方式的文件，以实施后的实际清单与配置为准。

## 仓库文件到比赛硬盘

下表中的“仓库相对位置”相对仓库根目录；“硬盘相对位置”相对上面的比赛硬盘逻辑根目录。目标位置目前均为建议。

| 内容 | 仓库相对位置／现有来源 | 建议硬盘相对位置 | 使用和生效 |
| --- | --- | --- | --- |
| Java 业务源码 | `ai_consense_service/src/main/java/` | `source/ai_consense_service/src/main/java/` | 现场修改后重新构建 JAR；重构前后按冻结基线保护功能。 |
| Java 构建文件 | `ai_consense_service/pom.xml` 及构建实际依赖的其他文件 | `source/ai_consense_service/pom.xml` 等保持原有相对结构 | JDK、Maven 和离线依赖如何准备需核实；不能假定现场能联网下载。 |
| Java 业务与配置资源 | `ai_consense_service/src/main/resources/`，包括 `application*.yml`、`db/`、`drafting/`、`vetting/`、`advice/`、`seed/` 和 `oer-fixed-sources.json` | `source/ai_consense_service/src/main/resources/`；允许单独外置并由程序读取的实际配置另放 `config/` | 当前部分资源随 JAR 加载，改硬盘源码副本后必须重打 JAR；不能假称已经支持单文件立即生效。若要单独外置读取，需实现并核对规则、数据库迁移及缓存。 |
| 后端发布文件 | 由 Java 工程构建生成；构建产物不等于冻结提交已有文件 | `releases/<版本>/app.jar` | 当前外置 App 候选已有该类相对路径，运行版本必须与构建源码和配置对应。 |
| 前端源码 | `ai_consense_web/src/`、`ai_consense_web/index.html` | `source/ai_consense_web/src/`、`source/ai_consense_web/index.html` | 页面修改后重新构建，保留完整前端功能。 |
| 前端构建文件 | `ai_consense_web/package.json`、`package-lock.json`、`tsconfig.json`、`vite.config.ts` 及构建实际依赖的其他文件 | `source/ai_consense_web/` 下保留原相对位置 | 准备离线 Node/npm 与依赖；已有本机 `node_modules/` 不等同 Linux 目标依赖。 |
| 前端发布文件 | 前端构建得到的 `dist/` | `releases/<版本>/web/` | 目录整体发布，包含 `index.html`、JS/CSS、懒加载分包和 PDF worker；旧页面切换期间所需旧版本须处理。 |
| OER、OCR 与检索代码 | `ai_consense_service/tools/vetting_eval/` | `source/ai_consense_service/tools/vetting_eval/`，实际运行时使用固定版本 | 包含统一资料入口、原页、检索、模型配置及相关程序；不能删去运行时必需的脚本或数据文件。 |
| 离线启动与任务代码 | `ai_consense_service/tools/offline_delivery/` | `source/ai_consense_service/tools/offline_delivery/`，实际运行时使用固定版本 | 启动、协调、任务、备份和配置脚本要按实际用途逐个核对；不是所有测试或历史工具都必须上云。 |
| 两组 Python 的相邻关系 | `ai_consense_service/tools/offline_delivery/` 和 `ai_consense_service/tools/vetting_eval/` 同属 `tools/` | 在运行快照中保持 `tools/` 下同级，或同步修改所有受影响读取方式 | 现有多处按相邻目录导入；只移动其中一个目录会找不到代码。 |
| 检索配置与离线依赖清单 | `ai_consense_service/tools/vetting_eval/samples.json`、该目录实际需要的 `requirements-*.lock.txt` | 分别位于对应 `source/.../tools/vetting_eval/`；离线依赖文件放实际确定的硬盘依赖目录 | 锁文件不是模型权重；各环境需按实际模型和 Linux 运行时核对。 |
| 镜像构建定义 | `ai_consense_service/tools/offline_delivery/Dockerfile.*`、实际需要的构建输入 | 构建准备文件可存 `source/.../offline_delivery/` 或离线构建区 | 这些定义用于构建镜像，不代表它们应被复制进镜像；最终只保留确实无法外置的运行时和依赖。 |
| 本机脚本与测试 | 仓库 `scripts/`、`ai_consense_service/src/test/`、各项目 `tests/`、工具目录中的测试及实验脚本 | 不默认整体搬运；需要现场构建、调试或验收时只选实际必需文件并登记 | 若现场 Maven 构建包含测试，须准备相应测试源码及依赖；不能仅凭精简清单假定标准构建成功。Windows 脚本不能假定适合 Linux；不得把测试或历史实验误认成生产入口。 |
| 说明文档 | 根目录和项目 `docs/`、README、GLOSSARY、spec 及历史交接 | **不搬运到比赛运行环境**；本机与远端仓库持续维护 | 不按 `.md` 扩展名一概删除：业务程序实际读取的提示策略是运行资源，必须保留。 |

例如，`ai_consense_service/src/main/resources/advice/contract-system-20261014.md` 和 `.../vetting/contract-system-competition-20261015.md` 虽是 Markdown 文件，却是现有程序读取的业务提示策略，不是需要搬运的项目说明文档。当前它们随 JAR 加载；保留源码和构建结果，或在实施后采用已验证的单独外置方式。

## 镜像、容器和硬盘如何分工

| 对象 | 应放哪里 | 说明 |
| --- | --- | --- |
| 自己维护、可能修改的业务源码、项目脚本、前端、配置、运行产物 | 硬盘 | 镜像打包后不可修改；当前仍写进镜像的项目脚本是待改造状态。 |
| 模型、分词器、处理器、模板、字典、权重分片及已采用适配器 | 硬盘 | 按实际模型配置和原有布局完整准备，不能只复制一个权重文件。 |
| 数据库数据、原件、证据、生成结果和日志 | 硬盘持久存储 | 关系库、向量库由对应数据库程序管理；日志与原始证据分开。 |
| 字体和可外置的第三方工具／依赖 | 优先硬盘 | 核对许可证、加载路径及目标系统可用性；文档转换及版式需验证。 |
| Java、Python、数据库程序、系统库、显卡运行所需依赖等 | 先评估外置；确实无法外置的才进镜像 | 镜像和容器尽量最小化，逐项记录留在镜像中的必要性及后果；平台驱动和内核由赛方环境提供。 |
| 代码仓库文档、历史说明 | 本地与远端仓库 | 不搬到比赛环境；业务运行时直接读取的资源文件不属于此类。 |

用户确认：镜像和容器需要扫描；硬盘文件可以直接上云。现有通知曾记录单文件严格小于 50 GB，具体适用范围及是否允许分卷仍须核实；不能把镜像／容器扫描说成每份硬盘文件均要扫描。

## 已有候选的真实路径：不要和上面的建议混淆

以下是冻结基线中已有离线候选的**容器内路径**，不是已确认的赛方主机路径：

| 用途 | 当前候选中的路径／规则 | 搬迁影响 |
| --- | --- | --- |
| 外置资产卷 | `/artifacts` | JAR 与网页目录按此卷的相对路径配置；现有示例为 `releases/r1/app.jar` 和 `releases/r1/web/`。 |
| 候选清单 | `/artifacts/delivery-manifest.json` | 现有任务使用固定清单并校验登记文件；新布局需同步实际入口和清单。 |
| App 启动校验 | `/app/app-entrypoint.sh` | **当前仍在镜像内**；部署定义也再次指定它，外置时两处都要处理。 |
| OER 协调入口 | `/app/vetting_eval/server.py` | **当前仍在镜像内**；需与冻结基线实际使用的资料能力逐项对照。 |
| 显卡任务入口 | `/app/offline_delivery/stage_worker.py` | **当前仍在镜像内**；部署生成及请求阶段还会写入此路径，不能只改镜像定义。 |
| MySQL 数据 | `/var/lib/mysql` | 当前由 MySQL 容器的持久卷提供；不是仓库中现成可搬的文件夹。 |
| Qdrant 数据 | `/qdrant/storage` | 当前由 Qdrant 容器的持久卷提供；需与索引及模型身份一致。 |
| 工作卷，后端视角 | `/data/consense` | 现有数据库可能记录绝对文件路径，改变挂载路径前要核对历史原文访问。 |
| 同一工作卷，协调／任务视角 | `/workspace` | 与后端看到的路径不同；`CONSENSE_WORKSPACE_ROOT` 必须指向真实存在的目录。 |
| 外置 App 清单 | `/app-files` | 当前候选使用只读配置及清单；现场权限、文件更新与校验需重新验证。 |

当前默认上传目录 `./data/uploads/` 是相对 Java 进程工作目录，不是固定相对仓库根目录。当前检索默认模型缓存和运行数据分别位于工作根目录下的 `tmp/vetting_models/`、`tmp/vetting_server/`，也可通过配置改变；`tmp/` 名字不表示其中数据可随意删除。现有显卡任务会在工作目录下保存与任务身份相关的 `stdout.log`、`stderr.log` 和结果记录，不能据此假设其他进程日志已持久化或已脱敏。

## 模型、数据库和现场数据从哪里来

模型权重、数据库文件、原件、密钥以及大部分运行记录按项目规则不提交到 Git；**只复制仓库不能得到这些文件**。实际交付时应单独核验文件来源、版本、权限与完整性：

- `models/`：自部署 Qwen3.8 27B、当前已采用的 OCR、检索及重排模型，并保留各自实际需要的 tokenizer、processor、配置、字典、模板、底座、适配器和所有分片。当前检索代码的模型快照及缓存有自己的布局，不能把任意目录简单改名后认定可用。历史未采用候选不自动成为运行模型。
- `data/`：MySQL、Qdrant、原始上传、来源与 OCR 原始结果、任务状态、人工记录、历史及生成文件。应采用数据库支持的一致性方式准备；不能把本地 H2 文件直接放进 MySQL 数据目录。
- `config/`：比赛实际的地址、模型名称、可用上下文／输出预算、存储路径、超时和已采用 OCR 等配置；密钥仅放受限私有位置。当前有些配置在启动参数或打包资源中，统一外置及优先级仍需实施。
- `fonts/` 和离线依赖区：仅放实际需要、许可允许并已核验的文件；文档转换程序、字体加载和版式在目标 Linux 环境验证。

对历史文件采用迁移或不同挂载时，要一起核对数据库中保存的路径、原件和索引关联；不要凭目录名称认定可直接恢复。模型、文件、配置和日志是否完整，以实际运行链及验收记录为准。

## 实施后必须回填

1. 按实际运行链列出每个需要搬运的文件或目录、来源、目标相对路径、读取它的程序和生效方式；区分必需、现场调试才需要和不搬运。此文当前是目录级清单，不是已经验证完整的逐文件装箱单。
2. 列出最终进入每个镜像的运行时／依赖，以及确实不能外置的原因；核对没有因方便而打包可外置的业务脚本、前端或配置。
3. 记录实际硬盘卷与容器路径映射、权限、模型及配置身份；验证前后端构建、Python 同级导入、历史资料读取、数据库恢复和每次运行的版本。
4. 文档继续随代码在本地和远端仓库维护；上述赛场目录、配置和命令一经实施必须与此清单同步，未验证项保留为未验证。
