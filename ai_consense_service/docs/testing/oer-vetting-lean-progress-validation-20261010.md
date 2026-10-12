# Vetting 轻量进度：去除轮询中的重型来源读取

日期：2026-10-10，Asia/Shanghai。代码已通过隔离回归、跨端独立审阅并精确应用主源码；没有通过本次操作启动／重启服务或写在线 target。真实前端／业务性能验收仍需部署匹配的新版本后另做。

## 改动及来源核验边界

旧轮询 `GET /runs/{id}` 经过 `jobVO→sourceSetStale→freshSourceSet→sourceSetHash`，读取完整来源实体和大字段、解析结构、计算来源身份。已保存656次GET合计98分钟客户端等待；它不是已测定worker净开销，也不能声称移除轮询会节省98分钟。

新增两条接口：

- `GET /api/vetting/{projectId}/runs/{id}/progress`；
- `GET /api/vetting/{projectId}/runs/latest/progress`。

Repository明确投影11列：id、status、phase、completedUnits、totalUnits、message、error、startedAt、finishedAt、sourceSnapshotSha256、modelIdentityJson。无source document查询／实体化，不选择result／coverage，不实体化VettingRun。必要modelIdentityJson仍是域模型的LOB字段，只解析为现有五字符串安全身份；projectService.require仍核验项目元数据。准确结论是移除重型source/result/coverage读取，不能写成“零任何LOB”。

DTO只提供这11个状态字段对应表示及恒定 `sourceVerification=NOT_CHECKED`，所有任务状态都不带result、coverage或sourceSetStale。进度读取没有本次来源验证结论。旧完整get/latest详情、报告／证据和业务查询的fresh／实际原件及CAS核验保持。

前端活动恢复与普通轮询改读progress，首次终态才对同一run发一次完整getRun；single-flight避免重复读取，只有明确重连可重试失败详情。详情失败保留原handle、暂停、不再POST，不显示确认完成或启用报告／checklist。进度白名单移除旧coverage/result及旧fresh=false，保留同任务已有stale=true警告；项目／epoch／run id／job sequence／read sequence／unmount围栏保持。startRun原有完整来源检查不变。

## 真实回归和独立审阅

| 层次 | 执行与结果 | 边界 |
| --- | --- | --- |
| 后端red | 真实MVC→service→H2/JPA，同已存run旧GET实际读source并触发hash JSON解析，新端点缺失失败 | 不是浅层mock代理计数 |
| 后端final green | copied checkout fresh target一次16 suites／126唯一cases，0fail/error/skip | 原有规则、LLM profile、export／fresh来源门控在同一执行中复核 |
| 新HTTP/JPA用例 | 8cases覆盖4状态、poison重型字段、latest／null、项目边界、同来源新旧路径、变源report4104 | projection真实SQL、两实体load=0、0parser/OER/LLM调用 |
| 前端行为red | 真实SFC编译＋Vue/jsdom，活动恢复1full GET vs期望0 | 初版缺类型依赖的harness错误另存，不当业务红测 |
| 前端final green | 新交互10/10；既有view／report download脚本通过；直接vue-tsc／Vite隔离build通过 | API边界控制；没有真实LLM或浏览器调用 |
| 独立审阅 | Standards／Spec均0可操作问题；独立重计XML及jsdom，逐hash核567→568与180→181全闭包 | 只确认精确源码应用，不批准或追认业务质量／部署 |

合法小H2同run请求对照：旧GET11.6001ms，新progress17.4703ms。该单次小数据结果不能宣称墙钟更快；真实SQL显示去除了大来源路径。真实大来源任务吞吐与worker总时长的改进尚未测量。

Windows `npm run build` 包装命令因路径R&D中的&解析在进入typecheck前失败，原日志保留；直接Node调用相同vue-tsc.js／vite.js通过，没有改依赖或跳过typecheck。已有DraftingPdfPreview混合import warning保留。

## 精确主源码应用

先核完整baseline、每个候选实际字节和独立receipt，再备份原字节，只复制后端4产品＋1新增测试和前端3产品＋2测试，共10文件。应用后主service568、web181项与测试冻结闭包逐hash一致。其他既有overlay保留，未编译主target、启动服务、写DB、调用LLM或切换运行版本。

证据位于ConSense根忽略目录：

- 后端 `.local/state/oer-vetting-lean-progress-20261010/run-20261010-134900/validation-receipt.json`，SHA `2a06c6726119dbbc53626925d2d924a7297852e02f54b25a0563319b623e7e80`；真实SQL／XML／red及完整源码freeze同目录；
- 前端 `.local/state/oer-vetting-progress-web-20261010/run-033300-4ea167/validation-receipt.json`，SHA `c54b74e7a1d541d6ee974bc2502dd845d6e54a936d7a6ccd334a0cdcfcf7c679`；before／after／五文件ledger及原测试日志同目录；
- 独立 `.local/state/oer-vetting-progress-independent-review-20261010/run-20261010-115143-150/independent-review-receipt.json`，SHA `052685268ebfc4c772cf1f9eb9f05a411e03c58242234278b13b936e741a5565`；
- 主源码 `.local/state/oer-vetting-progress-root-apply-20261010/apply-receipt.json`，SHA `fec06640c58305488b9b165b4b255dbe7114177e8de87a00eda8c3eda1c78bd9`；actual执行脚本及原字节backup同目录。

旧M3任务completed_empty／partial、119个来源计划因token observer缺失未提交，仍是独立残差，见[保存终态与预算诊断](./oer-official-two-source-vetting-completed-partial-20261010.md)。本修复不提高它的coverage，也不证明官方题、完整报告、原件质量、前端验收或H800完成。
