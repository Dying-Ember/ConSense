# 06: 核验 Qwen 接口业务请求与持久结果

**现行口径（2026-11-02）：**本机三模块真实业务通过外置配置调用官方 `qwen3.8-27b` API；比赛时配置切回自部署 vLLM。下方替身请求和本票关票保持当时的代表性协议范围，不代表真实模型或本轮三模块完整结果；实际纯前端业务和失败状态见[票 14](14-local-full-stack-rehearsal.md)及看板。

## 2026-10-30 定向协议修复与验证（历史时点）

本机协议替身的 HTTP/1.0 响应显式声明 Connection: close，且拒绝 Content-Length 短读；原先连接中止和截断请求被接受的失败保留。Python 合成及本机回环测试 12/12 通过；当时替身 SHA256 f09578d9cd1be0402a71cb27614d807a334b6e1fd27415b5ee4a6eba9568ec95 与 Java 隔离快照一致，票06 Java 测试 2/2 通过。Java 测试 stderr 继承父进程以避免 Windows 临时文件锁，较早组合检查 35/35 针对短读修复前字节。证据在 .local/ticket0607-isolated-20261030-193840-4d4e90e2/。随后为票07处理 Windows 断连异常，当前替身 SHA256 d7d973b202ba8d01c1481d5c0cef51f2948e0a3bbcb3b190eae369f23738c974；Python 13/13 和 Java 增量／取消 3/3 的独立证据见票07，票06两项已针对最新字节隔离重跑通过（日志 SHA256 151cd300eb32ea9179a1c7046451b5131b4dd1f1b6a60c4b87c68129968cba94）。仅软件协议检查；真实容器、持久数据库、页面和关票依赖仍未验。


**2026-11-01 demo 对齐（历史时点）：**本票验证原业务接口与容器化 Qwen 接线能运行，沿用已有结果保存方式；不新增持久化框架或为了取证重复执行业务。赛方沿用原 Qwen 客户端，由外置配置指向 vLLM；当时本机使用明确标记的协议替身，不能冒充真实推理、质量或输入计数。

**2026-11-01 结构化业务运行中点查：**隔离合成项目 `proj_ticket06_3b097c7d2c184a61a38c1333f87c86fe` 的 run `e881383a-fa37-40aa-b632-437387fdc2ec` 已通过原 Vetting 入口执行结构化协议替身请求；只读 run 接口返回首个 `reliablePackets`，状态 `completed`，`checkpointStatus=saved_verified_packet`，保存时间 07:37:13 UTC，结果 SHA256 `18ee2c83bdb0e71895430e48f42fa935706e71ed58cf3b5b6b8042f00e188b41`。替身返回 1 条 `insufficient_context` assessment，0 条 finding；并非真实 Qwen 推理或质量结论。记录的响应内容 SHA256 为 `58950fd75d448c480b36171f4aa74622cbe698a5ecdc6820208f800c3bd479d1`，请求输入 SHA256 为 `40f3d745bc91e56ec6394bb9d8fae823accccb19c10cc6b07fa9d74cc81cb074`。

首 packet 的 R1 绑定 canonical chunk `canonical:10f34d5653a83197bed832d38f9913b9c70c801ae400d0206210fd1914400363`；返回证据为资料 49、`body/0`、精确引文 `AUTHORED obligation 0`，`located=true`。证据原件 SHA256 `bb1d56aa44662c5133463109295c3e57de117fd26cf066ab114e12ffe1830c9a` 与提交前资料 49 读回一致，来源 revision SHA256 `bc8a7ee09dcf45600c7961969236a7ba8d15d96d3970a8dbc73953503e356327`；run 的来源快照为 `1fe119bab9a57e18363aded0319744416d758f7fe7ecb2aa4f4c0b9266ee18d0`，`sourceSetStale=false`。packet 自身快照 `66b13a00b7f54b7590ee51a7895bfb1d0da9958a968d89ba33375f22675cc4c9` 与 run 快照属于不同层级。提交前资料身份与 run 提交回执在 `.local/container-rehearsal-20261029/ticket06-dual-synthetic-fixture-20261101-a1/vetting-run-receipt-20261101-cache-refresh-a1/`；本段 packet 和状态来自运行中的 run 接口读回。点查时 run 仍为 `RUNNING / semantic`，不代表整次审查完成或全部覆盖通过。

**2026-11-01 代表性 demo 关票：**Drafting 普通替身结果和上述 Vetting 正式结构化替身 packet 已从业务接口保存并读回，项目、原件、引用和版本有对应记录。下方错误、超时、格式和配置证据按其真实运行／软件测试范围分别使用；此前真实连接失败未冒充成功。当前 run 仍在执行，已保存的代表性 packet 不代表整次 29 方向审查完成；本机真实 Qwen／vLLM、赛方环境、模型质量及本次 Vetting 业务级超时未直接验证。

**2026-11-01 15:44 +08 后台 run 只读点查：**`e881383a-fa37-40aa-b632-437387fdc2ec` 仍为 `RUNNING / semantic`，6/29，当前正在准备 PRE.B4 检索；详情返回 5 个可靠 packet，均为 completed，来源 `sourceSetStale=false`，整体覆盖仍为 incomplete、0/1 文档。点查时 progress 的 error 为空、result messages 为空；按提交前 operation 清单比对，新增 operation 中未见 failed／cancelled／expired。当前 owner 是 `914e44b4d3874f95a8c081c6d9069a82`，阶段 `query-embedding-rerank`、状态 running、release pending；已完成的新增 operation 包含 17 个检索／重排和 10 个 CPU 替身调用，分别记录 released 和 released_cpu_only。这是该时点接口和 operation 状态，不能证明之后不会失败，也不能推断整轮完成；未启动新任务或重试。

**后台 run 最终状态：**`e881383a-fa37-40aa-b632-437387fdc2ec` 终态 `COMPLETED`（50/50），业务接口读回 48 个可靠 packet、`sourceSetStale=false`，但 coverage 仍为 incomplete，审查完成文档 0/1；未解决的标准资料／评估项继续待核，不能把进度满格称作完整审查通过。终态点查 GPU owner 不存在；真实 Qwen／vLLM 与模型质量仍未验。

**四条验收当前对照：**

1. 普通请求已有 Drafting 合成结果；结构化请求已有上述首个 packet 并通过原业务接口读回，代表性请求已有证据。不以完成全部 29 个方向作为新增关票条件。
2. 首 packet 项目及资料 49 原件身份、引用和来源 revision 已记录。来源变更守卫有旧 run 实际失效记录及软件测试；`e5bf58a4…` 的 Qwen 连接失败被记录为 FAILED、0 可靠 packet。结构化 schema 错误在 Java 到本机替身的定向测试被拒；超时在合成 HTTP 网关测试中按 TIMEOUT／HTTP504 传播。competition profile 缺地址在软件测试中于传输前失败且无云端回退。以上是代表性证据：超时尚无本次 Vetting 容器业务终态的直接复验，赛方最终 profile 和地址也尚未核验；不将软件检查说成赛方环境验证。
3. Drafting 草稿、首结构化 packet 和运行中任务状态已经通过业务接口读回；run 最终状态尚未知，不将整个 run 结束设为新增关票前提，也不要求为验收重建数据库或逐路径重启。
4. 已标明使用合成协议替身、首 packet 的结果和当前覆盖边界；真实 Qwen／vLLM、业务输出质量和完整审查覆盖仍未验。run 结束后应如实补记终态与实际覆盖，不能仅凭 `COMPLETED` 宣称审查通过。

第二条证据位置：来源失效现场见本票历史记录；来源变更软件守卫见 `ai_consense_service/src/test/java/com/consense/web/VettingProgressJpaHttpTest.java` 和 `VettingRecoveryWorkflowTest.java`，后者历史测试结果见 `ai_consense_service/docs/vetting-recovery-verification-20261013.md`。schema 负例及 2/2 本地测试日志分别见 `ai_consense_service/src/test/java/com/consense/ai/Ticket06QwenStubIntegrationTest.java` 和 `.local/ticket0607-isolated-20261030-193840-4d4e90e2/focused-close.log`。超时 3/3 的合成网关测试见 `ai_consense_service/src/test/java/com/consense/ai/Ticket07GatewayTimeoutTest.java` 与 `.local/container-rehearsal-20261029/ticket07-java-check-20261101/maven.log`。competition 配置 4/4 见 `ai_consense_service/src/test/java/com/consense/ai/CompetitionQwenConfigurationTest.java` 与 `.local/container-rehearsal-20261029/competition-config-check-20261101-a1/maven-final.log`；默认 profile 与 competition profile 不同，不将此检查泛化到其他 profile。

**What to build:** 在本机真实容器中通过现有业务入口跑通代表性的普通和结构化请求，正确处理结果、错误并沿原有方式保存和读回。

**原关联（按实际需要）:** 02 — 外置后端和网页；05 — Qwen 占位及兼容接口。实际请求需对应后端和模型接口可用，不额外等待无关整票。

**2026-11-01 15:14 同一 run 终态：**隔离样本 run `e5bf58a4-eb94-4ab3-84c7-8b8cb8409bc7` 最终为 `FAILED`、28/29，错误 `Qwen chat connection failed`；coverage 为 `incomplete`、0/1 文档，可靠 packet 为 0。App 日志中 27 个已完成方向均记 `failed/review_processing_failure`。按本次提交前 operation 清单比对，真实 document embedding 1 个、query/rerank 64 个均为 `completed/released`；business-llm 27 个均为 `completed/released_cpu_only`、请求 `max_tokens=1` 的替身预检，未见正式结构化生成 operation。终态点查 owner 文件不存在；未声称整个任务业务成功。run 实际由 14:42:33 运行至 15:14:20 +08；历史失败与原始记录保留。

**2026-11-01 运行中点查（历史；终态见上）：**同一隔离项目 run `e5bf58a4-eb94-4ab3-84c7-8b8cb8409bc7` 最新点查为 RUNNING、semantic 19/29，当前 GPU operation `2aa2d75fb341477db3fc17cf72fb7fbf`；运行中不启动其他 GPU 请求。应用日志已记录至少前 16 个方向的 packet 均为 `failed/review_processing_failure`；只读 H2 核对其中前 6 条已保存失败，均为 `Qwen chat connection failed`，诊断字段 `CONNECTION_FAILED`、阶段 `generation`，输入预算预检为 `provider_estimated_fit`。实际正式结构化结果未出，不能把任务进度或替身预检当作业务成功。来源、任务和失败证据保留。

**连接故障定位与修复（历史时点）：**首个方向的预检 worker 在 06:45:13.356 UTC 记录完成／释放；Source 在 13.370 发送最终响应 body 时记录 `CancelledError`，App 在 13.448 开始正式 gateway 调用、13.471 结束并在 13.496 记录失败。其他方向也有相邻时序的 Source 取消与 App 连接失败，但日志不含可证明跨进程同一请求身份的完整链，不能断言其为现场唯一根因。工作区 `ai_consense_service/tools/vetting_eval/server.py` 已调整成功送达终帧与断连同时出现时的处理；`test_server_business_adapter.py` 增补该竞态测试，`test_business_response_keepalive.py` 覆盖 HTTP 连接复用。Source Python 环境相关 unittest 34 项通过；真实 Uvicorn 回环、合成 business 下，旧版第二请求 BrokenPipe、修复版连续两次 HTTP 200，隔离测试目录 `.local/container-rehearsal-20261029/ticket06-asgi-response-race-20261101-a1/`。修复在当时尚未部署；随后已换入 Source，同连接预检及正式结构化替身请求均 HTTP 200，见本票后续复验。不能据此证明真实 Qwen 或现场全部断连成因。

**运行交接（2026-11-01 14:48:49 +08，历史点查；终态见上）：**隔离项目 `proj_ticket06_3b097c7d2c184a61a38c1333f87c86fe` 的 run `e5bf58a4-eb94-4ab3-84c7-8b8cb8409bc7` 正在 `RUNNING / semantic`，3/29；从提交前 operation 清单起已有 13 个 operation。真实 `document-embedding` operation `c670565241f7474a8104657257a8c66c` 已完成并记录 released；当前 `query-embedding-rerank` operation `080454f689ac4b8c8a547055d70552aa` 为 ready、release pending。两个已完成的 `business-llm` operation `9ff075fb51a64b8aa366cb12a6ca0a4d`、`bf8f3e4acd1e4a63a419a6f641971b43` 都是 `max_tokens=1` 结构化替身预检并记录 `released_cpu_only`，尚未观察到正式生成；正在运行不能等同通过。当前未见 operation 失败，但仍须等待该 run 终态并核资料 49、实际 packet R1 和结构化响应。

只读追踪：在专用 WSL 从 `http://127.0.0.1:28680/api/vetting/proj_ticket06_3b097c7d2c184a61a38c1333f87c86fe/runs/e5bf58a4-eb94-4ab3-84c7-8b8cb8409bc7/progress` 获取状态；终态后去掉 `/progress` 读 coverage、reliablePackets 与来源。提交及 `operations-before.json` 在 `.local/container-rehearsal-20261029/ticket06-dual-synthetic-fixture-20261101-a1/vetting-run-receipt-20261101-fresh-a1/`；operation 的 `status.json`、`request.json`、`response.json` 位于 `/srv/consense-rehearsal-20261029/instances/local01/gpu-serial04-r7-20261030/workspace/gpu-operations/<operation-id>/`。该目录是专用 WSL 的私有证据；不要重复提交或停止在途任务。

**Status:** done — 已按本票当时的代表性替身范围关票；该替身审查 run 已终态但覆盖不完整（见上），不代表真实 Qwen 或本轮业务完成。真实业务按票 14 实测，赛方 vLLM 与业务质量另记。

**验收（当时的替身 demo 范围）：**

- [x] 使用现有容器和业务入口，以标记的本机替身完成代表性的普通及结构化请求；复用已完成的 Drafting 结果，不为凑验收重复运行。
- [x] 保持项目、来源及版本与请求一致；结构化校验及来源守卫有效，错误、超时和格式失败不冒充成功，也不静默切换外网。
- [x] 按原业务方式保存或读回代表性结果及任务状态；不增加数据库重建或每条路径重启验证作为前置。
- [x] 如实记录替身结果与实际覆盖；真实 Qwen／vLLM 和输出质量另记未验。

**2026-11-01 本次实际复验：**隔离项目 run `989f72ab-bfdb-4956-b060-46a338ded2d5` 终态显示 `COMPLETED`，但实际覆盖仅 0/2 文档、0/4 片段，0 findings；提交前后没有新增 GPU 或 Qwen operation，不是结构化业务成功。首个错误为 Source `/index-canonical/availability` HTTP 503，`INDEX_PUBLICATION_DURABILITY_UNKNOWN`；后续方向报告 `canonical_index_receipt_invalidated`。未重发。

**归档后的再次运行：**run `87fe29a3-3b5f-414a-bd9c-1cd5825fda88` 显示 COMPLETED，但覆盖仍为 0/2 文档、0/4 片段、0 可靠 packet，未新增 GPU 或 Qwen operation；首个错误已从 Source 503 变为 App 内 `canonical_index_receipt_invalidated`。源码核对：此前状态失败使该索引 key 的 App 进程内 receipt 标为失效；后续 compact recovery 在访问 Source 前即拒绝，不能自行发现 block 已归档。Vetting 无公开的显式重建入口；受控重启同一 App 可清除该进程内状态，但不等于重建成功或业务通过。此前 989 与本次失败均保留。

**后续结果与新隔离样本：**同一 App 重启后 run `a147a130-0ead-410f-948c-148f12fd3269` 仍为 0/2 文档、0/4 片段，未新增 GPU 或 Qwen operation；首个错误为 Source HTTP 422 `INDEX_INITIALIZATION_UNAVAILABLE`。旧失败 generation 的 collection 仍存在，而 Source 初始化要求该索引 key 的 collection 前缀为空；不删除旧点。为避免反复更改旧状态，已新建隔离合成项目 `proj_ticket06_3b097c7d2c184a61a38c1333f87c86fe`，仅上传既有 21 字节 tender 样本，文档 49 为 PARSED，按原件、routing、business 版本读回并确认含 fixture 引文；人工采用状态未核。本次创建、上传和解析未提交 Vetting/GPU，后续实际 packet 的 R1、模型调用及结果仍需验证。回执在 `.local/container-rehearsal-20261029/ticket06-dual-synthetic-fixture-20261101-a1/fresh-project-receipt-20261101-a1/`。

**阻断来源及处理方向：**精确索引 key 的 `publication-27d63336a9250e9ba20a5c0f676371b4a2ac7b7ee8692dc3612c46527072d956.block` 指向前次 run 的 journal `20261101T033050.474496Z-668d040b5c3045f9a3b23f208895b6dd`；其 receipt 为 failed、`MODEL_SESSION_INVALID`，当时 embedding `4cbc5803…` 保留 `release_unknown`。旧 journal 仅有预写记录、无最终 intent 和 response；该 key 的 metadata 文件不存在，预写记录 `priorMetadataSha256=null`。Qdrant 内旧 generation 有 8 个部分点，不能作为已发布索引使用。只读检查时 owner 不存在；现有完成索引恢复方法不能直接恢复这个失败预写。可由主控在确认无在途写入后，保留原始证据、只将该隔离项目的精确 block 原子归档，保留旧 generation，然后允许原流程按需创建新 generation；不直接改写为成功，不清理原项目、资料或旧点。主控随后已将该 block 按上述方式归档并核同原字节；独立只读复查确认原 block 不存在、归档 SHA 为 `33be4785eafaf04e1498f0cfc87c67fa4bff48cc19a4b5814a3b8e0d5b2ce8c3`。后续实际运行仍需验证。

**已有结果与缺口：**隔离合成项目的 Drafting 发现请求已保存草稿 `5005ee98-aed9-465e-9751-9d3cf375c8cb` 和一条待审核候选。Vetting run `903d307a-46ab-47ec-a7d0-0c4da997f4ac` 只有 0/2 资料完成、未调用结构化 Qwen，不能按成功计算；后续优先沿原入口解决实际阻断。历史定向测试和失败记录保留。
