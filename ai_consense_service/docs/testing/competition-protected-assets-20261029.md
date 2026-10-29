# 比赛预演：实际资产与受保护范围登记

2026-10-29；票据 01 的资产登记分项。本次仅观察进程／端口与路径、读取既存验证收据，对已有代码及完整网页计算身份；没有启停服务、发出模型请求、解析原文、连接活动数据库、复制活动状态或修改源数据。此登记不关闭 01，也不授予 02–04 运行许可。

遵循[实施 spec](../specs/competition-container-external-runtime-20261029.md)、[运行方案](../specs/competition-runtime-plan-20261029.md)与[冻结对照](competition-runtime-baseline-20261029.md)。后续 02／04 的发布产物尚未构建，不以缺少未来构建产物阻碍现有字节和目标位置登记；业务历史路径映射与恢复验证留给 03／13。

## 本次固定目标及证据

部署 ID 为 `r1-20261029`，实例为 `local01`。Linux 持久根固定为 `/srv/consense-rehearsal-20261029`；Windows 专用外置根固定为仓库忽略目录 `.local/competition-external-runtime-20261029`。本次没有声称这些目标已创建、发布、挂载或验证权限。目标下所有原件、模型、数据、日志及秘密继续排除在 Git 之外。

完整私有清单位于 `.local/container-rehearsal-20261029/asset-register.private.json`。它逐项列出现有路径、复用验证收据或字节清单、明确目标宿主路径和容器路径、产生者、读取者、权限、临时目录、生效方式与 unknown；动态 PID、实际源目录及文件名只保存在该清单，不在公开文档复述。

清单共有 **141 条映射**，包括下表范围及重叠状态视图；同一 H2 内的人工／任务／历史／生成 BLOB 各列一条范围记录，不能把映射行数当成独立物理文件数。私有清单 SHA-256：`e3947e23ff30c58d61eaf75df5d68d575298506b3b940c8ab31f23e31531e98d`。该 hash 只用于复核这份清单，不证明目标资产已经复制或运行。

| 范围 | 实际登记／身份来源 | 本次明确目标（相对 Linux 根） → 容器路径 | 状态及后续产生者 |
| --- | --- | --- | --- |
| 当前 Java JAR | 当前 18186 启动类路径定位，stat 与本次 SHA-256 | `baseline/current-runtime/app.jar` → `/baseline/current-runtime/app.jar`；未来 `releases/r1-20261029/app.jar` → `/runtime/release/app.jar` | 现有字节是比较输入；02 新产物 `to-be-built`，未冒充冻结提交的构建。 |
| 完整网页 | 已有 `dist` 全部 84 个文件逐文件路径／大小／SHA-256 与清单摘要 | `baseline/current-runtime/web/` → `/baseline/current-runtime/web/`；未来 `releases/r1-20261029/web/` → `/runtime/release/web/` | 已有 dist 是比较输入，未认定为当前 Vite 所服务源码；02 完整发布 `to-be-built`。 |
| 两组 Python | `vetting_eval` 776 文件、`offline_delivery` 29 文件逐文件身份；排除 Python 缓存等非源码缓存 | `source/ai_consense_service/tools/{vetting_eval,offline_delivery}/`；未来 `releases/r1-20261029/tools/` → `/runtime/tools/` 下同级两目录 | 当前源码已登记；03／04 固定运行快照 `to-be-built`，相邻导入与子进程由后票实读验证。 |
| 外置配置／秘密 | 当前 Java 的外置 properties 路径、stat、Windows ACL | `config/r1-20261029/app.private.properties` → `/runtime/config/app.private.properties` | 可能含秘密，未读取内容、未计算内容 hash；02 产生私有有效配置。秘密的读取授权和目标权限后验。 |
| 四组已核 OCR 资产 | Paddle 18、布局 4、MinerU 小模型 15、主 checkpoint 11，共 48 项既有 SHA／大小；完成标记另 1 项补证 | `models/` 下保留历史 runtime 的模型相对结构 → `/runtime/models/` | 复用主票与补票，不重读大权重。主票缺标记的 FAIL 保留，不能改称单窗 49 项通过。 |
| embedding／rerank | 两个 immutable publication manifest，分别 10／6 项已登记身份 | `models/{embedding,reranker}/` 下含固定 publication 身份及 snapshot → `/runtime/models/` 对应位置 | 复用 manifest，未重新加载或读权重；Linux ABI、加载和真实推理后验。 |
| 封闭 H2 切点 | 10:02:10 上海时间切点，源／保护副本现成 SHA 一致 | `baseline/cut-20261029T020210Z/h2/acceptance.mv.db` → `/baseline/h2/acceptance.mv.db`；未来可写 `instances/local01/h2/` → `/runtime/data/h2/` | 只登记已有封闭副本；没有打开活动 H2；服务数据须另取工作副本，不把只读基线作为活动库。 |
| 44 原件 | 每件旧路径、数据库预期 SHA、已核 source/copy SHA、大小及现有保护副本；总 100,563,992 字节 | `baseline/cut-20261029T020210Z/files/` → `/baseline/files/`；未来 `instances/local01/files/` → `/runtime/data/files/`，每件后缀为现有保护副本的明确文件名 | 复用 source-hash-check，不读原件内容或重算原件 hash；新位置与旧 DB 绝对路径映射由 03／13 验证。 |
| 5 份索引 metadata、5 份 SQLite、2 份 Qdrant meta | 每个现有真实路径、collection、metadata／SQLite SHA、原有副本目录及冷读回执均具体登记 | `baseline/cut-20261029T020210Z/indexes/` 下按模块和实际身份命名 → `/baseline/indexes/`；导入暂存固定在 `instances/local01/index-import/` | 不是 server storage 格式通过；绑定 73/74，1 项历史 unknown 保留；后续验证 local→server 读取／迁移。 |
| 人工记录、任务、历史、生成 BLOB | 按 `evidence/summary.json` 对同一 H2 切点分别登记覆盖范围 | 同一 H2 基线及未来 `instances/local01/h2/` | SQL GROUP BY 计数是返回行数，不能当成业务记录总数；18 PDF artifact 行与 draft artifact 不匹配的既有状态保留，不修旧缺项。 |
| 已保存 trace／日志 | preservation receipt 的旧路径→封闭副本和 SHA 共 4 项 | `baseline/cut-20261029T020210Z/preserved/` → `/baseline/preserved/` | 历史证据只读，不能作为本次服务日志。 |
| 新任务／生成／日志／证据 | 未来产生者及角色读写登记；旧外部任务、生成与所有日志位置不假称齐全 | `instances/local01/tasks/`、`results/` → `/runtime/data/tasks/`、`results/`；`logs/local01/` → `/runtime/logs/`；`evidence/local01/` → `/runtime/evidence/` | `to-be-created`，03／04、业务生产者及12负责；未知旧范围仍记录 unknown。 |

代码、网页的逐文件数来自本次私有清单；模型、数据库、原件及索引数分别来自 `.local/container-rehearsal-20261029/dual-ocr-identity/{receipt,marker-supplement}.json`、`.local/oer/model-publications/` manifest，以及 `.local/offline-ticket01-20261029/evidence/{summary,source-copy-summary,source-hash-check.private,source-preservation}.json`、`index-copy-candidate01/phase-a-receipt.private.json` 和 `phase-b/` 冷读回执。清单没有把 manifest 的登记等同于新一轮大资产核验。

## 受保护现有服务和范围

| 现有职责 | 已核保护范围 | 本轮允许取用／排除及原因 |
| --- | --- | --- |
| Java 18186 | 当前类路径 JAR、活动 H2、上传目录、外置配置、renderer 工作目录由当前命令行定位，具体路径和 PID 仅留私有清单 | JAR 比较身份、已封闭 H2 与44原件保护副本可供未来复制；活动库、工作目录及切点后变更全部排除，不能据旧切点覆盖现状。 |
| 独立 Java 18198／18199／18201 | 三个独立候选 JAR 根；18198／18199 数据库路径已核；18201 实际数据位置未由命令行证明 | 三者及其整个运行根排除，属于独立 Drafting；18201 数据归属未知不能编造或推断安全。 |
| OER 18185／18189 | 当前 Python 入口已核，历史索引收据明确 vetting／advisory state 根；共享 OCR、retrieval publication、环境与缓存根受保护 | 只允许采用已登记 immutable 模型来源及既有冷索引副本；当前服务、活动 state、venv 和共享缓存不触及。历史 state 与当前环境变量一致性未在本轮重新证明。 |
| SL 18183 | 当前 server.py 与 plan 路径已定位 | SL 来源、计划、固定基线、模型依赖及运行目录整体排除；独立业务，不迁移或重跑。 |
| Vite 5187 | 当前 Vite 脚本所在网页源码工程根已核 | 在线源码、node_modules、Vite 缓存与进程全部排除；另登记已有 dist 比较输入，不能替代其当前运行身份。 |
| BLZ 服务 | 私有清单记录匹配到的本机进程路径与独立项目根；WSL 内全部归属未全面核定 | 整个独立系统排除；没有授权本轮修改其状态、数据库、配置或生命周期。 |
| 网络采集／自动恢复 | 本机匹配的进程身份只存私有清单；受保护 network-monitor 输出根另登记 | 全部排除，不停止采集器；Windows 快照不足以证明 WSL／调度器中所有采集进程，遗漏范围继续保护。 |

模型源和44原件只复用既有核验身份；本轮实际没有把任何资产搬到目标根。全量旧日志／任务输出、18201 数据位置、继承 cwd、环境变量指定目录及 WSL 内 BLZ／采集细分归属仍为 unknown。unknown 的操作含义是继续排除，不是允许删除、搬动或替换。

## 仍须后票实证

固定路径解决登记可复核性，不能证明具名 non-root 账户具有所需权限、跨容器实际读到同一字节、业务绝对路径映射正确、历史生成文件完整或新版本已发布。03／13 核对旧路径、来源／项目／版本关联和历史恢复，02／04 构建运行产物、配置和实际入口，12核日志。原始模型推理、源质量与业务验收分别报告；没有为本登记重启旧质量整改。
