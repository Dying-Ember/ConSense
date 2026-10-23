# 公共按需读取接线与手动测试交接

状态：2026-10-22 本批代码已构建并部署到本机手测环境。用户要求先收口当前修复，之后手动测试并按反馈热修。本文件不授予三模块完整业务验收、全部架构迁移或最终文件质量通过。

**19:25后续手测热修已部署：** [NTT上传保存失败的原因、修复及真实浏览器证据](drafting-template-upload-hotfix-20261022.md)。共享磁盘兼容层补到后端JDBC客户端临时LOB路径，40项组合回归通过；相同原件在隔离项目实际上传、刷新回读通过。当前后端PID38868／64GiB，新包SHA5980b5a2…；H2／OER未重启、原62来源及6草稿身份／状态／修订保持。下文PID6112及服务端脚本独有兼容为热修前状态。Goal仍paused，完整业务／最终文件未新授通过。

## 本批实际改动

- 既有 unified OER 安装 `/sources/read`，协议 `source-bound-demand-read-v1`，提供 catalog、ids、scan。请求传来源地址、module、明确 ID／游标与预算，完整单元不能被截断；ids／scan 直接复用生产 `SourceUnits.resolve`。
- Java 公共 client 接入有界传输，核实际来源／软件／policy／顺序及 scope，核完整 members、unknown 和 coverage；scan 不能首屏跳过来源，续游标必须从请求 offset 连续推进。未知不能被改成已验证语义，context 不能混入其他文档的合法原文。
- `OerBusinessEvidence.sourceHits` 删除 Java 本地闭包 fallback，最多 256 origins 一批调用公共读取，保持顺序、unit 去重、零检索分数及 unknown。已有 normal Word 条件／指导语／续段公共适配保留。
- Advisory 业务代码不再自行作 incorporation／table／small-corpus 三次全量扫描；最终 model material 也交给公共层，不再逐引用直接查业务 repository。公共内部继续复用现有 Word 完整表格与来源守卫，没有新造表格算法。
- 公共范围选择不再仅因 FAILED／PARSING 静默移除已识别的最新来源；保留范围分母、禁止回退旧版，准备／索引的 parseUsable 准入保持，图纸／身份未知／定价／模板／评价排除保持。

## 实际验证与保留失败

证据均在忽略的 `.local/oer-demand76/` 和 `.local/source-compact-hotfix75/`，不是线上模型质量评分。

| 检查 | 实际结果 |
| --- | --- |
| Python source read／既有 unified／SourceUnits | `python-source-read03.log`，40/40，通过，93.759 秒 |
| Java 首轮 green11 | 155：129 通过、16 失败、10 跳过。失败为显式测试 profile 被启动参数 disabled；测试已隔离配置，生产准入未放宽 |
| Java green12 | 182：169 通过、1 失败、2 错误、10 跳过。发现来源分母缺陷；两错误为真实夹具环境未指定 |
| Java green13 | 211：208 通过、1 失败、1 错误、1 跳过。补齐真实输入后，余项为旧目录 SQL 次数断言与输出目录缺失 |
| Java green14 | 211：210 通过、1 失败、0 错误、0 跳过。唯一失败为历史 Q1.6 模型材料基线 |
| Q1.6 定向 green17 | 全部正文、顺序、unknown、57 材料／109 引用及来源关系元数据等价断言已过；最后的 270,000 B 体积门仍失败，实际 426,200 B。**此方法仍记 FAIL，不称全绿** |
| 构建 package18 | BUILD SUCCESS，未再次运行测试；实际运行 JAR SHA `9fb71c60f345207bf3243295999d2147022d3a65ea6f537ee306f2624cb3c3b7` |

历史 Q1.6 baseline 缺少既有 a5f6a299 契约的两字段。测试只从 SHA 锁定的原 GCC／SCC 补入 expected，并独立核 176 个 referenceRelationIndex、132 个 reviewActor 原值；未从 actual 制造 expected，未改固定 fixture 或放宽完整内容断言。后继体积门保留在待办，不为赶交付删来源或提高门槛。

## 当前手测环境与恢复范围

前端：`http://127.0.0.1:5177/`；后端 18086／64 GiB，H2 18082／32 GiB，Vetting OER 8874、Advisory OER 8875。新后端 PID6112，前端24568，H2 32040；PID 只代表本次现场，重启后必须按实际 receipt 核验。两 OER 的真实 health 均宣告新 source-read contract，软件 SHA 与已测 Python 源相同。

实际前端代理回读 `/api/projects` 为 23 项、返回 code0；模型目录确认 Qwen27B 与 Flash-Next 已配置，local／MiniMax 关闭。浏览器已打开 Drafting、Vetting、Advice，并实际切换 Flash-Next 后恢复27B。页面能读不等于本次已重新生成文件／完成真实模型问答。当前浏览器 TaskSpace2，个人空间未动。

浏览器另已选中恢复的 2026-10-22 00:32:28 咨询会话，真实消息 API code0、2消息（user／assistant），回答正文与来源按钮可读；这是消息43／44的历史，不是新提问或上午消息52。新页面截图与回读位于 `.local/oer-demand76/runtime/manual-advice-history01.png`、`advice-sessions-readback.json`。

OCR18780已恢复既有 `consense-recognition-local.service`，CPU／offline健康为ok，使用既有准备函数在新目录重算当前246项运行资产pins，模型原字节保持。首次旧boot因canonical_projection.py更新被正确拒绝，旧boot／失败保留；新boot `01dcfc93…`，新准备未执行GPU推理。`ocr-health02.json`只是运行健康，不是扫描源质量通过。无新timer或自动重启。

数据库从现存最新 `runtime-integration30/checkpoint-before-runtime30.sql`（2026-10-22 02:56:31）恢复至新的**持久化 H2 文件库** `.local/oer-demand76/runtime/database/consense.mv.db`。仅导入一次，原备份未改。恢复进程峰值工作集 2,911,760,384 B，始终未触整机资源界限。独立重开核到：23 项目、62 来源、1052 变量、6 草稿记录、44 消息；这只是表与重点状态核验，未重新逐一核所有 BLOB。

- “合约填写专项验收（临时项目）”保留80条变量和三模板；**上午新生成的三份保存稿不在该库内**。浏览器显示的采用状态按恢复库实际值，不追认上午状态。
- “真实2b项目验收-恢复20261021”恢复35件：17 PARSED、14 PARTIAL、2 IDENTITY_PENDING、2 EXCLUDED。APU／APB是检查点时的待确认状态；上午的16 PARTIAL和新审查任务不能当已恢复。
- “Qwen controlled source verification”恢复至消息44及此前会话；上午消息52未进入该 SQL，不能假称当前历史含52。
- 上午已下载 NTT／SCT／SCC Word／PDF、较新的 API 证据仍在原目录；这些文件不是数据库全量恢复。现行下载入口见 `human-acceptance-delivery-20261022.md`。
- 恢复库无 QUEUED／RUNNING 审查，启动未自动重发新审查／模型问题。本轮未主动新调用付费 LLM。

H2 重开曾因当前 Windows/JDK 的 `Files.isWritable=false` 被自动降只读；真实 READ/WRITE 打开与 `File.canWrite` 成功。ACL试改没有解决，已恢复原 SDDL，未扩大其他目录权限。现由公共 `H2LocalFileAccess` 提供显式 `rwfile:` scheme，业务后端和 `scripts/H2LocalFileServer.java` 共用，只调整这项预检，实际文件读写仍由原 H2／OS 执行。原微型 MEMORY 表验证漏掉客户端大LOB临时文件，已由本次TCP CLOB／BLOB及真实NTT上传复验补齐，默认1MiB阈值与磁盘临时存储保持。**没有第二次巨型导入或改表型／升级 H2**。运行入口与日志在 `.local/oer-demand76/runtime/`及`.local/upload-write77/`。

## 手测后继续的明确事项

- `loadSession`、`corpus`、公共 frame 仍有完整来源 hydrate；公共内部 Advisory 范围选择仍遍历现有 session。不是所有目录／状态读取都已脱离 LOB。
- `/retrieve` 的 Java 闭包复核尚未移除；Drafting 的 template observations view、两处本地提取切块及完整 compact 迁移未完成。禁止称“所有重复实现已消除”。
- Q1.6 输入426,200 B尚未满足旧270,000 B门；保留完整内容，后继修重复呈现。
- 本轮浏览器只做页面／来源目录／模型切换准备检查；Advice初次读取出现过请求超时提示，来源就绪、索引升级与真实付费回答留给后续手测。没有新生成三份草稿或真实35件报告，也未重新验其最终版式。
- SL全量质量与质量通过后的比赛包继续后置。

## Git 交付

本批代码提交 `b6073711e10a185682def2d2f65e7d64c0669504` 已在本机部署。一次非交互推送失败：`could not read Username for https://github.com: terminal prompts disabled`，记录 `.local/oer-demand76/git-push-result01.json`；当前不能声称已推远端。未知的四份 untracked 文档／Drafting探针保持原样，原件、密钥、DB、模型和运行证据未入Git。
