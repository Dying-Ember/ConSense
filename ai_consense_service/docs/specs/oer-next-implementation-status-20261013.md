# OER / Vetting / Advisory 实施状态 — 2026-10-13

用户于北京时间 2026-10-13 凌晨调用 `implement-spec`，授权实现 [主规格](./oer-vetting-advisory-next-development-20261012.md)，要求最小完整代码，并纠正截止为 **今天 08:30 准备好本地代码，09:00 由用户提交 GitHub**。这覆盖此前的 spec-only 授权状态；原件质量、真实模型、浏览器及 H800 门仍分别验收。

## 工作入口和保护范围

- 集成工作区：`C:/Users/Administrator/.codex/worktrees/oer-next-delivery/ConSense`。
- 集成分支：`codex/oer-vetting-advisory-20261013`。
- 开始前源码快照：`46206d441ee7e16b8cff7cbc76fc1632529ffca1`；原主分支 HEAD：`285fc2e3bdf97792aeae00a31dbde2c69ebcde60`。既有未提交成果已保存为独立本地基线，主工作区保持原状。本轮增量以快照为比较基点。
- 配置、初始文件 SHA、patch 和测试日志留在 `C:/Users/Administrator/Documents/Codex/consense-implementation-20261013/`，不提交配置备份或凭据。
- Drafting 冻结；图纸只识别身份并排除；冻结 Local05、原件、模型及既有服务保护。测试只用隔离数据，不迁移或切换活动数据库。
- 不提前替用户 push、创建远端 issue / PR 或部署。现有本地任务图作为实施 tracker。

## Ponytail 和续跑

先安装官方 `DietrichGebert/ponytail` 5.1.0，再通过 `hooks/list → config/batchWrite → hooks/list` 信任三个准确哈希的 hooks；随后才开始开发。三个 hooks 均 enabled / trusted。独立只读 Codex 生命周期实际完成 SessionStart、UserPromptSubmit 并注入 full 规则；SubagentStart 配置已确认，其单独运行验收尚未执行。当前桌面回合未重启，执行者已读取安装的 SKILL 并采用 full 规则。

本聊天续跑 heartbeat `consense-08-30` 每 30 分钟检查并推进，至 2026-10-13 08:30 Asia/Shanghai 截止。无可行动变化保持安静，完成、失败或需用户处理时通知。它承担续跑；Ponytail 承担最小实现规则。

## 时间预算

| 北京时间 | 工作和退出条件 |
| --- | --- |
| 00:30–02:00 | 共同证据、文件身份、来源回取；保存各模块契约 |
| 02:00–05:00 | 并行 Advisory 会话和 Vetting checkpoint / 状态 / 报告；持续集成 |
| 05:00–07:00 | 前端联动、公开工作流验证、导出和回归；处理实际失败 |
| 07:00–08:30 | 停止非必要扩展，独立 Standards / Spec 审查、修复、构建、冻结及提交说明 |

时间是预算，不是已完成事实。解析候选、Qdrant Server 迁移、离线制品可以提前准备；真实源质量、模型、扫描和 H800 未过门不会被文档或本地契约测试替代。

## 当前任务与验收

| 工作 | 所有者 | 当前证据 |
| --- | --- | --- |
| 范围路由、共同契约与集成 | root | 图纸早期身份排除；固定原件流式 SHA、选中 corpus row 冷读；共同软件身份包含候选 helper，49 项 Python 回归通过 |
| Advisory SQL session / 相容有界历史 | advisory_impl | V16 session、范围与消息 snapshot 已集成；所有新生成统一最终预算准入和至少256K，V18完整85字引用ID升级；相关119项回归通过，真实页面仍在复验 |
| Vetting checkpoint / owner / coverage / snapshot | vetting_impl | V17 owner / lease / checksum packet / report snapshot 已集成；5个独立child JVM恢复边界通过；审查发现的过期人工写入和局部缺证问题修复中 |
| 固定 / 项目引用和 Vue session / 导航 | frontend_impl | 固定和项目回取分路由、原件SHA/PDF.js、session epoch、历史完整组和草稿已集成；版本写入与reference_only状态显示修复中 |
| NEXT-02 原生表格候选 | native_table_impl | 三章226页的Network / Stream / lines分别完整执行；49个旧lines输出全等。候选未评分采用，原五目标整表0/5保持，见[候选收据](./oer-native-table-candidate-20261013.md) |
| NEXT-04 Server迁移候选 | qdrant_server_impl | 19,371 parents、20,700 vectors和payload全量读回、冷启seal相等；3次self-vector查询、3 PDF / 2表JSON回取通过。语义查询 / PVC / H800未过，见[迁移收据](../testing/qdrant-server-migration-candidate-20261013.md) |
| 真实业务 / 浏览器 / 导出、H800、独立复核 | root / review | 第一轮真实M3已经调用，但SQL保存失败；界面保问稿。预算仅provider estimate加margin；检索包未含口头指示条款，答案明确缺证。V18修复后复验；H800 / 扫描未执行 |

每次集成追加具体提交、命令 / 结果和未通过门。任务关闭遵循 [任务图](./oer-vetting-advisory-next-development-tickets-20261012.md) 的真实证据要求。

## 01:43 集成与实际失败增量

- 当前交付 HEAD `4516a96`；已包含图纸路由、Advisory、Vetting、前端、native-table、Qdrant Server候选及独立审查修补。未push。
- 固定来源原件守卫 `9fbb03e`，software闭合 `a0168a4`，Vetting恢复配方 `b4ba1f4`，Advice原件守卫 `8659239`，全生成准入及V18 `4516a96`。两个已完成Advice收据：[字节守卫](./verification/oer-advice-source-bytes-20261013.md)、[全生成准入](./verification/oer-advice-all-generation-admission-20261013.md)。
- 历史producer fixture的6个CAS text文件在Windows checkout曾转CRLF；逐项仅在恢复LF后匹配原receipt的SHA和bytes时还原，21行全部原封存identity匹配。没有重seal、改期待值或跳过历史回归，`.gitattributes -text`保准确checkout字节。49项回归收据为外部 `software-closure-final-tests-04.log`。
- 跨模块9类集成首跑86项：83通过，3个真实loopback HTTP case因JDK17 `Invalid argument: connect`失败；同3项在准确外部JVM参数令JDK使用TCP pipe后通过。保留首失败 `integration-byte-checkpoint-8659239.log` 和复验 `native-http-windows-tcp-fallback.log`，未改生产HTTP框架或测试断言。后续源码集成仍需最终回归。
- 首轮真实M3计数返回12,820 input tokens，配置262,144窗口、16,384输出预留和32,768margin，总61,972；状态为`provider_estimated_fit`，`exactTemplateObserved=false`。生成usage 12,820 / 229 / 13,049，模型答复缺证；保存遇`evidence_id VARCHAR(64)`长度85失败。V18将该列扩256并通过真实SQL升级 / HTTP原样回读，旧独立H2复验必须实际执行迁移。
- 首页面错误、问稿保留、counter / generation全wire和SQL失败日志在外部 `isolated-browser-01` 留存。此次失败不算已完成Advisory业务验收；不把counter连通、HTTP200或fixture报告当源质量 / 真实答案质量通过。

上述测试集合有重叠，不相加成一个总测试数。未过任务继续保留其实际质量与运行门，解析候选和Server候选不切活动Local05。

## 02:35 实施与验证增量

- 当前集成 HEAD `de4a603`。规则 finding 原件身份修复 `34c1975`：从捕获的 source snapshot 核对 parsed revision 后绑定 original SHA；真实 HTTP DOCX 上传→规则 finding→严格原件 GET 及同大小／同 mtime 字节替换拒绝，54 项契约通过。
- 固定 PDF 引用初始物理页修复 `f5ee146`，相关 9 项契约通过。真实浏览器已看到 GCC 第 17 物理页 canvas；印刷页脚为 2，保留两种页码的区别。第一轮引用在跟问与会话往返后仍绑定自身身份，证据位于外部 `isolated-browser-01/advice-clause21-page17-pixels.png` 和 `advice-session-roundtrip-dom.txt`。
- Advisory 有限真实 M3 样本已在 V18 实际迁移后完成 SQL 保存和 HTTP 回读：GCC 2.1 首问两引用、同 session 跟问一引用。跟问最终 wire 的两条历史 question／answer 与 SQL 内容逐字相等；provider input 16,866、output 339、total 17,205；262,144 窗口＋16,384 output reserve＋32,768 margin 总预算 66,018，状态 `provider_estimated_fit`。`exactTemplateObserved=false`，不宣称精确模板或实测最大窗口。首轮 85 字 evidenceId 的 SQL 失败保留。
- 前端公开 `.test.cjs` 126/126、vue-tsc／Vite build 通过（`integrated-web-f5ee146-*`）；三份公开 `.mjs` 旧 harness 在真实失败后补相应 session／显示版本 binding，`69693b9` 最终 12/12 通过。历史 OCR “actual” fixture 未提供，按 README 保留未复验状态，没有补造或跳过断言；既有 Drafting 脚本保持。
- 全 Maven 首跑绑定业务源码 `34c1975`：2,631 tests、25 failures、103 errors、123 skips，失败收据保留。已核一份 authored original fixture 在 Windows checkout 多出 3 个 CRLF；准确 Git blob 与既有 SHA 一致后恢复，并以 `.gitattributes -text` 保字节。`e7d4434` 仅补此属性和相应当前标量校验／模型投影／只读 index status 的旧测试，61/61 focused tests 通过。第一次全跑结束时收据读取到前端-only HEAD `69693b9`，该字段不表示 Java 编译了后续代码。
- Vetting 全部生成最低 256K、未知预算零准入和 checkpoint recovery-v3 已集成 `de4a603`。原实现者广回归 903 tests、0 failures／errors、5 个明确外部环境 skips；没有修改 Drafting 或共享计数器定义。Root 正用明确历史 replay root、Python interpreter 与原封 fixture 做第二次全 Maven，终态待写入。recheck CAS 的 Windows 换行和旧模拟 status fixture另修，保所有失败。
- Linux retrieval 候选 `18e05e3`：82 个官方来源 wheels、4,066,465,995 bytes；实际新 WSL venv `--no-index --require-hashes` 安装、pip check、CPU import／张量／BM25 和 server 导入通过，模型／DB构造／GPU context／OCR均0。初版94包包含 RapidOCR 内嵌3个 ONNX 权重，已下载但未加载，候选明确拒绝，完整失败和偏差保持；不能报告本轮0权重下载。独立审查发现通用校验器未将 wheel hash 绑定到具体包，修复中，实际 pip hash 安装证据单独保留。
- Native-table 已集成主网格修复 `38c6c21`，正常完整226页 source-scoring 为 Stream 672/672、主表5/5；第一轮外成员正确分母为12（此前人工加总13已纠正）。新的同页 Notes／星号完整源组候选收据达到4/5、11/12外成员及86/87 typed关系；Window续注仍缺归属，候选未集成／未正式采用，父条与法律作用保持 unknown。完整原件／blind／AC02–05门未关闭。
- 同一真实 SCC 已通过浏览器以 tender 上传到隔离项目：原生799位置、186,855 textChars、PARSED、OCR=false；Word物理页及继承样式删除态未确认。为后续真实 Vetting 新开自有 CPU FP32 project retrieval（18868、独立数据目录、准确原模型 publication、offline）；未打开第二个 Local05 client，8876原owner与数据保持。最终 backend 重启与实际运行／人工记录／三格式导出待执行。

所有当前结果继续区分代码契约、有限原件源评、真实模型、浏览器、制品和H800；独立复核正在收口，未push，08:30冻结安排保持。
