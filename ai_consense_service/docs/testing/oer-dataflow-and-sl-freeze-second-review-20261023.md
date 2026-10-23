# OER 第二轮复审：重复展开、任务期限与 SL 冻结身份

日期：2026-10-23，Asia/Shanghai。复审起点为 `74bd18e648e7d9525d2db24061a89af9a44bab6b`，工作区干净；相对产品基线 `3b014db5175044e60341e1c99857fc6e553b3941` 只有文档变化。本报告接续[首轮 18 项审计](oer-dataflow-and-sl-freeze-audit-20261023.md)，重点查反例、状态边界和遗漏的调用链，不重新计算首轮验收分母，也不宣称已修复产品。

**复审结论：问题比“传了太多 JSON”更深。** 当前几处实现把“从多个起点读取同一个完整来源组”变成了“为每个起点重新保存、搬运并提交整个组”；任务期限也没有贯通排队和实际执行。SL 则同时缺少消费者的多版本选择，以及可移植身份与物理路径的分离。仅缩小 HTTP 响应、增加内存、启用更多缓存或再打一个离线子包，不能关闭这些问题。

沿用 Ponytail 审计方法及当前[范围规则](../oer-scope-and-rules.md)、[实施交接](../HANDOFF-ocr-embedding-rerank-20261008.md)、[引擎修复规范](../specs/oer-engine-integration-repair-20261014.md)、[公共按需读取规范](../specs/oer-demand-driven-business-access-20261022.md)和[SL 离线规格](../specs/sl-technical-offline-package-20261020.md)。负载仍按本机多个业务模块、真实大来源和七版 SL 技术资料评估。只读产品与保存证据；新执行均为纯函数或替身控制流，没有实际 OCR、LLM、在线数据库、向量库、原件解析、归档恢复、服务切换或历史数据改写。

## 复审发现与首轮修正

以下 R01–R04、R07 为首轮遗漏的五类问题；R05、R06 是首轮 15、16 的必要扩展，不重复累计为七项全新缺陷。

### R01．P1：公共语义准备按起点复制完整组，存储、读取和模型输入一起放大

**具体触发。** 一个完整条款／来源组由多个 canonical origins（可检索起点）构成。多个起点可解析到同一完整 unit，但 `prepare` 仍为每个起点保存 `{origin, unit: 完整闭包}`。这里“闭包”指判断本条所需、经现有关系解析取得的全部成员及声明，不是另建法律关系图。

**当前代码。** [source_read_server.py:232](/D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/source_read_server.py:232)逐起点序列化、写 CAS，322–330 行扫描再逐条回读；[OerBusinessEvidence.java:760](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/service/vetting/OerBusinessEvidence.java:760)把各自完整 `semanticUnit` 复制到 Chunk，117 行后由 session 持有。模型投影 [VettingSourceMaterial.java:203](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/service/vetting/VettingSourceMaterial.java:203)仍携带完整 `semanticUnit`；30–47 行只删除机械字段、替换引用，不合并重复成员。后续 [VettingSemanticReview.java:838](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/service/vetting/VettingSemanticReview.java:838)继续提交该投影。同包多个起点因此可以重复带入同一组原文。

**可达性与复现。** 这不是只存在于自造 JSON 的形状：[native_canonical_projection.py:354](/D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/native_canonical_projection.py:354)允许多个 unit 共享 `clauseId/headingUnitId`，402–403 行保留到各 chunk；既有 [test_native_canonical_projection.py:76](/D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/test_native_canonical_projection.py:76)就是同 A1 的标题与表格。实际 `SourceUnits`、semantic 投影及生产 prepare 循环 AST 的合成反例为：

| 同一完整组中的起点 | 不同 unitId | 不同 CAS 记录哈希 | 记录内成员出现次数 | 序列化记录合计字节 |
| --- | --- | --- | --- | --- |
| 16 | 1 | 16 | 256 | 1,957,641 |
| 32 | 1 | 32 | 1,024 | 7,590,969 |

每起点使用 2,048 个 ASCII 字符。成员出现次数是复制计数，不是不同原件单元数量；字节数来自[执行收据](/D:/R&D/ConSense/.local/oer-readonly-audit-20261023-round2/semantic-closure-evidence-final.json)。由于 origin 和 unit.originIds 随起点改变，记录不是相同字节，CAS 最终去重也不能消除这层复制。没有将上述数字外推为真实 SL 占用、网络流量或模型 token 数。

还用既有合成 OOXML observer 导出实际调用 `produce_native()`：标题、表格两个起点属于同一 A1／heading，真实 resolver 返回一个完整 unitId，生成两个不同 SHA 记录、四次成员出现、共 63,782 字节。该项证明 producer 到 prepare 的形状可达；没有调用文档 parser，也不是对某份真实 Word 原件的质量评价。

**失败影响。** 这也会把可处理资料推向预算上限。当前 server 对单个超限完整 unit 返回 `unit_too_large`，Java [OerBusinessEvidence.java:110](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/service/vetting/OerBusinessEvidence.java:110)直接抛错，117 行只在全部扫描结束后发布；[VettingService.java:577](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/service/vetting/VettingService.java:577)在规则和语义审查前调用它。因此一个超限组会阻断本轮其他已可读资料的审查。现有系统拒绝截断是正确的；问题是缺少保留可用部分并明确列出阻断范围的路径。

另一个合成形状含 8 个各 1 MiB 的成员，其 text、parts.text、tableSlice 中保留同一文字，首记录为 28,321,210 字节，大于 16,777,216 字节读取上限。该探针未经过 registry／HTTP 准入，只证明这种序列化形状与消费者预算的冲突；不声称任意 8 MiB 上传都能或都会触发。

**最小修复。** 沿现有 manifest、CAS、unitId 和短引用，让完整组只保存一次、起点只保留指向组及成员的地址；扫描、Java session 和同次模型组包使用同一成员字典。现有 [OerCanonicalRetrievalClient.java:265](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/service/vetting/OerCanonicalRetrievalClient.java:265)要求 origins 与 units 一一对应，不能直接对 units 去重；应明确升能力版本、保留每起点分母与 cursor 校验和旧冻结 manifest 读取，不原地重写历史。完整组自身仍超限时，按已有规范有界传输后完整重组，或标明未完成及受影响判断，保留其他可靠范围；不得硬切条件、丢表头或减去原文来满足预算。验收必须同时检查 CAS 写入、回读字节、Java 持有及模型实际提交四层，不能只看响应变小。

### R02．P1：排队不计入处理期限，客户端结束后服务器仍可开始新 OCR 工作

**具体触发。** 多个页请求等待全局计算锁；其中某请求在等待时已超过调用方期限。`/ocr` 的同步 handler 没有传入客户端任务 ID、剩余期限或取消状态，拿到锁后才调用 `recognize_complete_page`；后者重新开始一整份配置时限。

**代码与反例。** [server.py:429](/D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/server.py:429)、454–465 行与 [complete_ocr.py:496](/D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/complete_ocr.py:496)形成该顺序。Java [HttpSupport.java:235](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/ai/HttpSupport.java:235)有本地 call timeout，但没有因此取消服务器的任务；[DocumentParser.java:419](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/document/DocumentParser.java:419)保存页失败后继续后续页。

实际 route AST 和实际 complete-page 函数配替身锁／时钟／provider：t=0 入队，设调用方预算于 t=3 结束，t=60 得锁，服务器新期限仍为 t=63，随后发生 7 次替身 provider 协议请求并返回 `completed`。七次是该夹具的协议调用数，不是七次模型推理。见[生命周期收据](/D:/R&D/ConSense/.local/oer-readonly-audit-20261023-round2/lifecycle-evidence.json)；未模拟真实网络断连或操作系统调度，也未声称当前停止的 OCR 服务仍在运行。

**影响与最小修复。** 用户／Java 已认为失败的请求仍可能占 GPU、占锁并拖延后页；仅提高客户端 timeout 会延长问题。沿现有 owned-job 生命周期绑定任务和剩余预算，在准入、得锁及每次 provider 派发前检查同一期限；排队必须计时。取消只作用于拥有的任务，保留已完成输出、失败和 unknown，先查询已有任务再决定是否重试。与首轮 14 的“分配前准入”一起修，但分别验内存上界与过期后零新派发。

现有 [native_provider_lifecycle.py:339](/D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/native_provider_lifecycle.py:339)已对执行中的 owned provider 定时终止并在 finally 释放，complete_ocr.py:745–755 也有 MinerU 取消。此项只指出排队及调用方状态没有贯通，不能扩大成“OCR 整体没有期限或取消”。

### R03．P2：普通页 `/ocr` 没有继承原生 PDF 已有的防重放和响应字节上限

**具体触发与代码。** [PaddleOcrClient.java:403](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/ocr/PaddleOcrClient.java:403)用普通可重发 byte[] multipart；identity 与 parse 两种 `/ocr` 调用都没有选 one-shot，也没有传响应 cap。[HttpSupport.java:194](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/ai/HttpSupport.java:194)将默认 cap 设为 0，236–243 行只有 one-shot 才关闭 OkHttp 连接恢复重试／重定向；否则通过 `body.string()` 全量读取。

**边界。** wrapper 的 `maxRetry=0` 只限制自有外层循环，不能证明底层 OkHttp 不重试。具体是否重发取决于异常及连接恢复条件，本轮没有发真实重定向、断线或巨型响应，也不声称某次生产推理已重复。原生 PDF 的 one-shot／8 MiB 上限已经存在，不能把它重新报为未修。输入端的 25 MiB 图像上限也不约束结构化输出大小。

**影响与最小修复。** 页级昂贵处理存在隐式再发入口，大结构响应也没有 Java 侧明确内存边界。复用已有 one-shot、无自动重放和有界解码流读取；若完整结果超过合理上限，使用已有 CAS／manifest 引用，不截断完整观察。返回未知时不自动补发，与 R02 的任务结果查询接通。该项是源码确认的控制缺口，不是已测到的重发事故或 OOM。

### R04．P2：Paddle 输入既进 CAS，又以命名 PNG/JPEG 等文件长期保存一份

**具体触发与证据。** [paddle_native_server.py:129](/D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/paddle_native_server.py:129)已把完整输入存入 CAS；132–142 行又在同根目录 `inputs/<sha>.<ext>` 写一份以供 pipeline 读取。成功、capture 失败及后处理失败路径均没有这份命名输入的释放步骤。

实际 `NativePaddleRuntime` 类 AST 配假 pipeline，对 2 个成功输入、1 个失败输入执行后，留下 3 个命名文件共 224 字节，CAS 同样保存 224 字节，三对内容逐项完全相同，见[生命周期收据](/D:/R&D/ConSense/.local/oer-readonly-audit-20261023-round2/lifecycle-evidence.json)。这是三个极小合成图，不是生产磁盘占用；同 SHA 文件通过 exclusive-create 复用，因此不是每重试一次就再永久多一份。

**影响与最小修复。** 新页增多会累积第二份输入；释放模型进程不释放该持久目录。以 CAS 为输入留证的权威副本，将 provider 所需扩展名路径改为任务拥有的临时工作文件，完成／失败证据持久化且确认 provider 不再持有后清理，或提供明确有界的可回收工作区。不能删除唯一原件／CAS、失败收据或仍被活跃任务引用的文件，也不能使用可能让 provider 改坏 CAS 的可写硬链接。本轮没有执行任何清理。

### R05．扩展首轮 15：SL 多版本选择必须贯通 Java 固定库消费者

[FixedBaselineRetrievalClient.java:193](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/service/vetting/FixedBaselineRetrievalClient.java:193)将所有 `family=SL` 映射为同一个 `manifest.originalSources.slParent.sha256`。57–60 行请求只携带一个配置 projectId、role、query 和条数，没有选定版本集合；[FixedBaselineProperties.java:10](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/config/FixedBaselineProperties.java:10)同样只有一份 manifest／index／project 配置。因此只把 Python scope policy 改为多版，第二个正确版本仍会被 Java 以 `frozen_parent_outside_source_scope` 拒绝。

这仍是功能缺口和正确的拒错保护，并非已证明静默混版。修复范围应包含：精确 source SHA／edition 集合、manifest、查询过滤、返回成员核验、项目采用与历史快照。不能为接收第二版而放宽成“SL 家族即可”，也不能把相同条号／相似文字当作同一法律来源。

### R06．扩展首轮 16：模型目录进入索引签名，找得到文件也可能被要求重嵌入

[window_runtime.py:127](/D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/window_runtime.py:127)把模型绝对目录写入 tokenizer identity 的 `resolvedSnapshot`，160–167 行整体纳入 signature；普通非 migrated 路径 [server.py:340](/D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/server.py:340)比较整个 signature。实际 `signature()` AST 的反例中，来源、模型文件身份、runtime 和代码身份均相同，唯一改变物理目录就得到不同签名；见[路径身份收据](/D:/R&D/ConSense/.local/oer-readonly-audit-20261023-round2/sl-portability-evidence.json)。没有加载 tokenizer／模型或执行向量操作。

**反证须保留。** [MigratedIndex:78](/D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/migrated_qdrant_index.py:78)有意不比较模型物理路径；Java [FixedBaselineRetrievalClient.java:268](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/service/vetting/FixedBaselineRetrievalClient.java:268)另有 query runtime 外部 pin、模型发布、health 和响应核对。所以不能泛称所有迁移路径都失败或所有配方均可漂移。

**最小修复。** 首轮 locator 解决“在哪里找到文件”，此处还需解决“搬目录是否改变逻辑内容身份”。冻结模型文件、tokenizer、预处理、窗口配方及实际索引身份，目标机路径交给受 manifest 约束的 locator；执行 runtime 的兼容边界保持独立明确。通过版本化适配／既有 migration 契约读取旧签名，不能直接删除历史字段、改写旧收据或跳过模型字节校验。

### R07．P2：索引损坏能被拒绝，但“重新构建”仍只重试失败的复用路径

**具体触发。** 正常动态索引仍存在同 signature／contract 的 metadata，原件、修订与请求配方都没有变化，但现有派生向量 collection 缺点或完整性不符。状态检查可以判断索引不可用，但用户点击重新构建仍发送同一 index 请求，无法绕出失败复用分支。此项不包含 `server.py:505–508` 按设计只读、明确拒绝写入的 migrated 固定索引。

**代码与反例。** [AdviceService.java:137](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/service/advice/AdviceService.java:137)显式 rebuild 调 `canonical.index(session)`，经 [OerCanonicalRetrievalClient.java:359](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/service/vetting/OerCanonicalRetrievalClient.java:359)进入 `/index-canonical`；[oer_server.py:44](/D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/oer_server.py:44)的请求没有 rebuild 意图。自动 `ensureIndex()` 也只在不 ready 时调用同一 index。[WindowRuntime.index:239](/D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/window_runtime.py:239)一旦 metadata signature／contract 相同就执行 `verify_reuse`，异常直接抛出，250 行以下重建代码不可达。

实际 index 方法 AST、临时 metadata 和假 DB／完整性核验：连续两次相同请求均因 `INDEX_POINT_COUNT` 失败，复用核验 2 次，encode／创建 collection／重建 window plan 均 0 次，metadata 字节不变；把核验结果改为健康的负控立即返回 `cached=true`。见[索引恢复收据](/D:/R&D/ConSense/.local/oer-readonly-audit-20261023-round2/index-recovery-evidence.json)。这证明恢复分支缺失，没有改动或破坏真实索引。

**影响与最小修复。** 当前完整性门正确拒绝坏索引，但正常重新构建入口不能修复它，反复刷新只会重复失败。区分“来源／权限已经变化”与“同一可信来源的派生索引不可复用”；后者沿显式重建意图生成新 generation，全部校验通过后发布，并保留旧失败证据。不要先删除 metadata、放宽 hash 门或自动接收损坏结果。本轮不建议用户手工删线上索引。

## 反证及没有升级为缺陷的疑点

- **没有认定 PARTIAL 一律被洗成完整。** [NormalSourceCandidateGraph.java:724](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/service/vetting/NormalSourceCandidateGraph.java:724)保留未完成、失败页、待核页及限制，公共 semantic member 的 sourceContext 仍保留来源 unknown。normal 的 quality.status 固定写 PARSED 值得以后消除歧义，但不能仅凭这个字段就声称失败范围全部丢失。
- **没有认定索引在任何失败后都会发布半成品。** 当前动态窗口路径先校验点数／完整性，再写 pending 并 replace metadata；未复现半索引被正常读取。首次失败留下未引用对象的治理可另查，不能把它说成已有结果污染。
- **没有认定已有 cache 把不同配方或版本混用。** 精确成功 provider cache、source family 同原件约束／权限交集、native SL 的精确版及 source revision 校验、固定向量 cache 的输入／模型身份均存在；新发现针对缺失的入口和复用粒度。
- **没有认定检索命中等于项目已采用。** 固定参考仍明确 `reference_only`／项目采用未确认；项目 incorporation 另有检查。多版支持应保留这条边界。
- 首轮的 22,214 技术页、14 页局部真实双路、219 PASS＋5 UNKNOWN 及 48 文件子包数字保持原证据含义。新软件探针不增加真实来源质量或完整比赛包的验收进度。

## 根因归纳与实施顺序

不需要重写整套 OER，也不需要再建一套平行 graph。已有原件 SHA、观察修订、projection、manifest、CAS、向量 cache 和模块授权足够作为修复基础；需要把它们各自负责的身份与生命周期接通。

| 必须分清的对象 | 可复用的东西 | 不能由复用推导的结论 |
| --- | --- | --- |
| 原件 SHA＋本版章节范围 | 同一原件字节及已核验观察 | 文件名、条号相同即同一版本 |
| 观察／解析修订＋配方 | 精确 raw、规范化结果、完整组 | 新配方结果自动替换历史人工采用 |
| 完整语义组与多个起点 | 一份组内容、多条地址引用 | 每个起点都要复制全文；完整组就是原件质量已通过 |
| 精确 embedding 输入＋模型配方 | 相同输入的向量计算 | 向量可复用即来源归属、版本和权限可合并 |
| 项目／模块选定来源集合 | 独立授权与采用视图 | 参考库发布即项目适用；latest 即本项目冻结版 |
| 任务期限、取消与终态 | 同一任务已产生的可核验结果 | 客户端超时即服务器已停止；结果未知即可以重发 |
| 比赛冻结清单及目标机 locator | 完整可达资产、数据库切点、索引 | 哈希正确即质量通过；文件恢复即业务能运行 |

建议按以下四步推进，第一步同时覆盖正确性和放大最严重的接缝：

1. **先修会丢依据／越界执行的链路。** 首轮 01–03 的跨批关系、混合公式文字和身份先行，与 R02／R03 的期限及单次派发一起收口。保留错误、unknown、成功部分和源级追踪。
2. **再修完整组重复展开这一公共根因。** R01 优先于各模块再做一层缓存；一并处理首轮 05–09 的目录大字段、逐单元整源回读、重复 normal 准备、全库重嵌入及冻结库每次全扫，并补 R07 的受控重建出口。每种优化分别证明存储、读取、序列化、模型输入和计算减少。
3. **收口有界执行和工作文件。** 首轮 10–14 与 R04，补有限页批／租约、原件引用、流式完整校验、解码前准入和任务工作区释放；普通 DOCX 的同源视觉链仍按首轮 04 单独补齐。安全校验不能拿掉。
4. **最后按版本冻结可运行的闭包。** R05／R06 贯通多版消费者和路径身份，再按同一数据库切点确定冻结 roots，列出每版原件→观察／采用修订→CAS→索引／query 配方→项目及历史引用的可达依赖。只有清单闭合且逐版来源门满足后，才做完整包及原路径不可访问的目标机恢复；禁止用局部子包或目标机临时全量重嵌入代替交付。

下轮修复验收应直接写成可失败的约束：改变传输批次不改变内容与关系；同组起点增加时不平方复制原文；相同输入／配方且已成功封存的请求复用结果、不新增推理，失败／未知先核任务终态；新增一个来源只编码缺失输入；排队耗尽期限后零新 provider 派发；换原件／修订／权限必须拒旧绑定；模型字节与已接受计算配方不变时，只搬模型目录不重算已冻结向量；损坏派生索引须可受控重建且不能被直接接受；错版查询必须拒绝；遗漏任一必需冻结依赖不能获完整接受。以上均为建议的后继验收门，尚未在真实系统执行。

## 本轮证据与实际检查范围

| 证据 | 实际执行 | 未覆盖 |
| --- | --- | --- |
| [semantic-closure-probe.py](/D:/R&D/ConSense/.local/oer-readonly-audit-20261023-round2/semantic-closure-probe.py)／[收据](/D:/R&D/ConSense/.local/oer-readonly-audit-20261023-round2/semantic-closure-evidence-final.json) | 实际 resolver／semantic 函数及 prepare 循环 AST，假 CAS；16／32 起点复制、超限形状及实际 producer 的两起点反例 | 真实来源分布、registry／HTTP 准入、生产流量、模型 token |
| [lifecycle-probe.py](/D:/R&D/ConSense/.local/oer-readonly-audit-20261023-round2/lifecycle-probe.py)／[收据](/D:/R&D/ConSense/.local/oer-readonly-audit-20261023-round2/lifecycle-evidence.json) | 实际 route／runtime AST、实际 complete-page 函数，假时钟／锁／provider；小图双副本；Java transport 静态断言 | 真实断连／取消、模型推理、并发峰值、线上磁盘量 |
| [sl-portability-probe.py](/D:/R&D/ConSense/.local/oer-readonly-audit-20261023-round2/sl-portability-probe.py)／[收据](/D:/R&D/ConSense/.local/oer-readonly-audit-20261023-round2/sl-portability-evidence.json) | 实际 signature AST，唯一改变模型目录的身份反例 | 模型载入、真实向量兼容性、比赛机冷启动 |
| [index-recovery-probe.py](/D:/R&D/ConSense/.local/oer-readonly-audit-20261023-round2/index-recovery-probe.py)／[收据](/D:/R&D/ConSense/.local/oer-readonly-audit-20261023-round2/index-recovery-evidence.json) | 实际 index 方法 AST，临时 metadata 与假 DB／完整性核验；两次失败、健康负控 | 真实索引故障或重建、模型调用、生产恢复 |

各收据保留所读源码 SHA 与边界；独立审稿核对了多起点形状的 producer 可达性、复制计数、超限反例及期限证据的限度。本轮没有以这四个小探针替代工程回归测试，也没有重跑首轮已完成的同类检查。

判断：优先修复“来源内容不丢失、同组不重复展开、过期任务不继续派发”，随后贯通 SL 的精确版本选择与可恢复冻结。未逐页复核七版原件、未做真实服务压测／浏览器业务／Linux 或 H800 恢复；前后两轮报告均仍是缺陷与验收边界，不是修复或比赛可用声明。
