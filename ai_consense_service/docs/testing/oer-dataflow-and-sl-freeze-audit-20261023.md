# OER 数据流、重复处理与 SL 多版本比赛冻结审计

后续：[第二轮复审](oer-dataflow-and-sl-freeze-second-review-20261023.md)补充语义完整组的重复展开、排队期限、普通 OCR 传输／工作文件及索引恢复问题，并扩展 15、16 的 Java 多版消费及模型路径身份接缝；本报告保留首轮观察时点和证据。

日期：2026-10-23，Asia/Shanghai。审计源码固定为 `3b014db5175044e60341e1c99857fc6e553b3941`，开始时工作区干净。本轮只读产品代码、规则和已有证据；只新增本报告及忽略目录中的小型复现记录，没有修改产品、上传资料、数据库或索引，没有运行真实 OCR／LLM／文档解析或切换服务。

结论：现有原件身份、CAS（按内容哈希保存的资产）、完整单元及失败保护应保留，但公共链还没有做到“一次准备、按需消费”。本轮确认 18 项需要处理的问题：既有会丢内容／关系的正确性缺陷，也有重复整图读取、重复编码和大对象复制；SL 的多版准入、可移植路径以及完整质量／比赛包仍有明确缺口。不能仅靠增加内存、减少响应字段、更多 OCR 或再打一个子包解决。

## 审计依据与边界

主要依据是[范围规则](../oer-scope-and-rules.md)、[当前实施交接](../HANDOFF-ocr-embedding-rerank-20261008.md)、[引擎修复规范 D1–D8／EAC](../specs/oer-engine-integration-repair-20261014.md)、[公共按需读取规范](../specs/oer-demand-driven-business-access-20261022.md)、[七版离线包要求](../specs/sl-technical-offline-package-20261020.md)及[SL 来源与检索质量记录](sl-technical-source-and-retrieval-quality-20261021.md)。当前用户要求扩大本轮审计至解析、嵌入、消费和冻结链，分别报告各层证据。

负载按当前本机单个业务后端、两个 OER 服务、用户可同时操作多个模块，以及七个 SL 来源的完整技术范围评估；不是假设互联网级并发。大型单文件、一个文件多个意见和一次新增来源已经足以触发多数问题。

审计起点的进程核查为：后端 `18086 / PID 6292 / consense-review101.jar`，H2 `18082 / PID 32040`，前端 `5177 / PID 24568`，两 OER `8874 / PID 13776`、`8875 / PID 3032`；OCR `18780/18771/18772` 无监听。另一个已获用户授权的任务正在独立协调 review102 联合切换。运行状态会变化，本文的源码结论绑定上述 commit，进程状态只代表观察时点，不能用源码已合入推定运行已更新。

严重度：P1 表示来源正确性、明确的功能／交付阻断，或当前大资料路径上的主要开销；P2 表示应修的重复处理、资源放大和接线缺口。“源码确认”“合成反例”“保存的真实证据”“验收未完成”不是同一个证据等级。

## 优先结论

| 类别 | 编号 | 主要问题 |
| --- | --- | --- |
| 内容与关系正确性 | 01–03 | 拆批丢跨页候选；混合内联公式丢文字；项目资料上传绕前置身份 |
| 公共来源准备 | 04、07 | DOCX 视觉核对未接通；normal 观察按模块重复构造与往返 |
| 读取与嵌入成本 | 05–06、08–09 | 列表／状态读正文大字段；意见逐单元全量回读整图；新增一个来源重嵌入旧窗口；冻结库每次查询扫全库 |
| 解析执行与传输成本 | 10–14 | 每页模型会话；每批完整 PDF 传输；每页五次原件读回；CAS 后半段重复物化／写入；准入锁前的大对象积累 |
| SL 比赛交付 | 15–18 | 单来源 policy；恢复映射未接 reader；全版本质量／检索未验；完整数据库和依赖闭包未封存 |

## 已确认发现

### 01．P1：传输拆批会丢失批次边界上的跨页关系

**触发与原因。** 原生 PDF 候选按 64 个选页拆批，上下文超过 32 MiB 时还会二分。Python 每次请求重置 `previous_methods`，续接只检查本批前页；Java 汇总各批 pages／bundle，没有补跨批关系。因此表格、续注或条件组横跨两个批次时，传输成功但续接候选消失。

**证据。** [PaddleOcrClient.java:115](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/ocr/PaddleOcrClient.java:115)、同文件 157–175；[native_pdf_candidates.py:159](/D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/native_pdf_candidates.py:159)、203–212。本轮替身控制流用同一合成来源的 `[64,65]`：合为一批时三个方法均调用一次续接；拆为两批时均没有调用，响应仍是 `candidate_observed`。见[复现记录](/D:/R&D/ConSense/.local/oer-readonly-audit-20261023/transport-evidence.json)。

**影响与最小修复。** 后续完整来源组可能缺续页关系。由公共层传递同源同配方的前驱观察引用，或汇总后补做边界关系；不要重新解析全部前页。补 64/65 与按字节二分两种边界测试，未续接明确标 unknown。已有运输批次测试和同批 COM11 续注测试不能替代此检查；尚未证明某个真实来源已受此缺陷影响。

### 02．P1：原始识别有条件文字，混合内联公式规范化后整段 text 为 null

**触发与原因。** `_text` 只接受全为 `type=text` 的 content 列表。文字中出现 `equation_inline` 且没有可用 lines/spans 回退时，整段文字返回空。见[structured_parser_evidence.py:406](/D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/structured_parser_evidence.py:406)。

**证据。** 本轮纯函数反例：普通 `50 mm` 返回文字，`text + equation_inline + text` 返回 `[null,null]`，见[SL 证据](/D:/R&D/ConSense/.local/oer-readonly-audit-20261023/sl-evidence.json)。保存的真实比赛版原页 3556／3557，F03／F12 的“至并包括 50 mm／大于 50 mm”分界在 MinerU raw 中存在，规范化文字项仍 FAIL；[原有逐项记录](/D:/R&D/ConSense/.local/sl-fwp-seven-semantic-review01/actual-source-quality-firstlook01/actual-two-page32-per-method-review.json:2136)及[质量报告](sl-technical-source-and-retrieval-quality-20261021.md:16)保留了证据。

**影响与最小修复。** 口径分界条件不能作为正常文字进入切块与业务依据，重复 OCR 不能修复这个后处理丢失。沿现有函数支持已知 typed inline 的原顺序、原字面投影，保留原结构和 LaTeX／解释 unknown，并保持 lines/spans 优先级。已有私有修复的回放不等于当前产品采用。

### 03．P1：Drafting 项目资料上传绕过前置身份判断，且正常 PDF 漏掉原生表格候选

**触发与原因。** 沟通／项目资料入口直接调用通用 `parse(name, bytes)`，解析之后才 `captureParsed` 识别身份。该调用没有 observation／identity，reading plan 是 `legacy_unbound_generic`；详细 PDF 文字／OCR 仍执行，但原生表格候选在 `confirmedNonDrawing` 门处直接跳过。

**证据。** [DraftingService.java:372](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/service/drafting/DraftingService.java:372)，395 与 403 的先后关系；[DocumentParser.java:212](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/document/DocumentParser.java:212)、304–388、471；[OerReadingPlan.java:19](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/document/OerReadingPlan.java:19)。另一审计分工独立核对 controller 到 service，没有前置隐藏守卫。模板上传有独立身份检查，不能覆盖此入口。

**影响与最小修复。** 正常项目 PDF 有表格缺口也不执行 CPU 候选；误传图纸 PDF 则可能先进入全页细读／OCR，违反图纸只识别后排除的 D3。项目资料应复用已有 Vetting 上传的观察、身份、排除／pending 和观察复用路径，再调用同一 parser。需新增正常表格 PDF 和图纸／未决身份两类入口反例。本轮是源码调用链确认，未真实上传或解析图纸。

### 04．P1：普通 DOCX 上传仍只有原生读取，未接通规定的同源视觉准备和核对

**触发与证据。** 普通 DOCX dispatch 直接进入 POI；返回 `pages=[] / pageCount=0 / ocrUsed=false`。[DocumentParser.java:256](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/document/DocumentParser.java:256)、856；[OerNativeDocxObservation.java:177](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/document/OerNativeDocxObservation.java:177)仍明示 `rendition_assets_and_native_to_visual_mapping_not_produced`。现行[上传缺口记录](docx-upload-production-gap-20261022.md:11)与当前代码一致。

**影响与最小修复。** PARSED 不能被解释为派生 PDF、双视觉、页码映射与核对均完成；模板原件预览已有渲染，不代表任意上传的视觉来源准备完成。沿公共来源准备接现有 renderer、两引擎、registry／CAS，按原件及渲染配方复用；阶段分别报告未执行／运行／失败／完成。保留已可用原生内容及人工兜底，派生页码不能冒充 Word 固有分页。

### 05．P1：来源列表、索引状态仍先读全项目正文大字段，再在 Java 筛范围

**触发与证据。** Advisory 页面同时刷新列表与状态。[AdviceService.java:1328](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/service/advice/AdviceService.java:1328)的列表读取 text／structure／coverage 后才选 current；1351–1397 的 selectedDocuments／guard 读取完整业务来源后筛选；[OerBusinessEvidence.java:219](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/service/vetting/OerBusinessEvidence.java:219)的状态仍 stage→loadSession。Vetting 文件列表在 [VettingService.java:238](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/service/vetting/VettingService.java:238)、297–303、921–931 也读全项目 text／coverage，部分行再读 text／structure。

**影响与最小修复。** 只查状态也可能把范围外 PRE、历史版本及大结构从 DB 搬入 Java；并发列表／状态和多次守卫叠加。这里确认的是数据库大字段搬运，不是宣称浏览器收到了整篇正文。复用现成目录／摘要和 selected-ID loader，列表取长度与必要摘要，精确全文校验留给具体操作。round380 的 Vetting 审查 sourceSet 已先筛后读，不重复报为未修。旧 Advice 列表测试仍允许读这三项大字段，需要按现行零正文 LOB 目录要求补回归。

### 06．P1：新语义审查的候选取证，仍按不同 unit 全量回读同一完整来源

**触发与证据。** [OerBusinessEvidence.java:120](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/service/vetting/OerBusinessEvidence.java:120)只按 `documentId|projection|unitId` 去重；每个不同 unit 调 `evidence.pointer()`。[CanonicalEvidenceService.java:318](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/service/sources/CanonicalEvidenceService.java:318)先 readBindingView，再找单元；579–598 读取／解析 binding，并从 Python 重取完整 snapshot／collection 批次。

**影响与最小修复。** 同一大文件的 N 个不同意见单元可走 N 次完整回读和重新校验，属于新流程保存前的成本，并非只剩旧报告路径；不据此推定操作系统缓存命中或实际耗时。按 document/projection 分组，用同一请求私有核验 frame 批量取指定 units，并统一退出守卫；以后再接真正证据 IDs 读取。现有测试只覆盖同一 unit 重复 quote，mock 了 pointer，需补“同来源不同 unit 的后端完整读取次数”约束。fixed 引用绕过与同对象去重已存在，应保留。

### 07．P2：同源 normal 观察在上传后，首次准备各模块时又完整重建、重写和往返

**证据。** 上传在 [VettingService.java:413](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/service/vetting/VettingService.java:413)已 capture。首次模块准备在 [NormalObservedPreparationService.java:25](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/service/sources/NormalObservedPreparationService.java:25)再次 capture，并多次 normalDocument、把正文／结构／coverage 作为 compare-and-set SQL 参数传回 DB；[CanonicalEvidenceService.java:90](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/service/sources/CanonicalEvidenceService.java:90)再向 Python 发送完整 candidateGraph、收回完整 projection 并保存 snapshot。module 还进入 normal projection identity（normal_observed_projection.py:281–296）。

**影响与最小修复。** 同份解析内容的 Vetting／Advisory 初次准备重复观察重构与传输；不是已证明重做 OCR。同模块已有 binding 有复用分支，不能说每次都重构。先核验复用已存 candidateGraph，CAS 更新改用已有可信写边界摘要；内容 CAS 复用与模块授权 manifest 分离，历史 binding 不原地改写。

### 08．P1：只新增一个来源，也会重新嵌入未变的旧窗口

**触发与证据。** [WindowRuntime.index:231](/D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/window_runtime.py:231)只对同 project＋完整 corpus signature 命中直接复用；其他情况重建全部 rows，非 fixed 白名单全部 encode。本轮抽取实际方法 AST、用假模型／假向量库执行：首次 A+B 编码 2；相同范围编码 0；只加 C 后编码 A+B+C 共 3；换 project 的相同 A+B 再编码 2。见[下游证据](/D:/R&D/ConSense/.local/oer-readonly-audit-20261023/downstream-evidence.json)。

**影响与最小修复。** 大来源增量、跨项目／模块复用不能复用相同模型输入的编码；成本随旧库重复增长。沿现有内容寻址向量 cache 复用精确输入＋模型／tokenizer／预处理／窗口配方，重建独立权限视图时只编码缺失窗口。不得按 SL 条号或相似文字合并不同版本。上述数量是控制流计数，不是实际模型速度；现有测试甚至期待新 project 重新 encode。

### 09．P1：迁移后的冻结库每次查询仍回读全库向量及载荷

**证据。** `server.py:324–331` 每次 read_project 调 [MigratedIndex.read:72](/D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/migrated_qdrant_index.py:72)，重 hash 收据／冻结清单／candidate metadata，并 `verify_reuse`；[index_integrity.py:31](/D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/index_integrity.py:31)分页 scroll 整 collection，`with_payload=True, with_vectors=True`，逐点重算 seal。fixed05 的历史分母为 20,700 个窗口，来源见[SL 质量记录](sl-technical-source-and-retrieval-quality-20261021.md:74)。

**影响与最小修复。** 第二次、第三次相同库查询仍发生随全库增长的网络、对象构造和散列；不是只取 top-k。恢复／激活时完整核验，运行用不可变 generation、受控写权限和变更失效维持可信状态，再按命中读取必要数据。不能改成 size/mtime 缓存。本轮未压测线上 Qdrant，未给出实际流量或延迟。

### 10．P1：新页面 OCR 仍逐页启动／释放模型，冷页反复承担装载成本

**证据。** `DocumentParser.java:304,374–388` 每页调用；[complete_ocr.py:562](/D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/complete_ocr.py:562)每页依次处理两 provider，585–589 各进 owned 会话；[native_provider_lifecycle.py:339](/D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/native_provider_lifecycle.py:339)、372–393 建进程树并释放。Paddle 启动重新核资产，首次请求装载模型（paddle_native_server.py:64–73、95–124）。ownedJob 也按交易释放会话，没有跨页租约。

**影响与最小修复。** N 个未命中成功缓存的新页，正常两路路径各有 N 个装载会话；实际耗时及峰值本轮未测。沿既有生命周期增加有限页批／有期限租约，保留逐页结果、失败、取消和释放。成功页精确缓存已存在，不能说所有重复请求都会重新推理；单 GPU 不应为提速直接同时常驻双引擎。

### 11．P2：分批只限制上下文，每批仍重传、重存完整 PDF 原件

**证据。** [PaddleOcrClient.java:162](/D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/ocr/PaddleOcrClient.java:162)每次 multipart 附完整 original；`server.py:493–501` 整份读入；`native_pdf_candidates.py:107–109,148,155–158` 每批重新 SHA／put CAS／打开 PDF／写完整临时 original.pdf。

**影响与最小修复。** B 个批次至少传输 B×原件大小，未计 spool／CAS 临时写入。65 个选页至少两批，字节限制可进一步拆批。这是代码可计算的放大，不是本轮抓包流量。公共层一次注册完整原件为受校验 CAS，后续批次传同源引用与选页上下文；保留完整来源及边界验证。旧 1 MiB multipart 拒绝／上下文无界传输已修，不在本项重复报错。

### 12．P2：原生表格候选每页至少五次完整读回原件

**证据。** 当前三种方法中有两种 Camelot（native_pdf_table_candidate.py:36–42）。[native_pdf_candidates.py:187](/D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/native_pdf_candidates.py:187)在两个 Camelot 前各 read_bytes；218–229 的三个 finally 再各读一次。Camelot 每方法还重新打开文档。合成 spy 两页得到 10 次完整读取，见 transport-evidence。

**影响与最小修复。** 大 bytes 分配和反复文档打开随页数／方法数叠加。先改流式完整比对，再在严格不可变／受控来源会话中复用句柄与已核验结果。provider 篡改临时原件的负控现已存在，必须保留；不能简单删除 finally 校验或只信 mtime。此次未测实际磁盘读量，逻辑读次数不等于物理 I/O。

### 13．P2：Paddle CAS 引用省了 HTTP 内联数组，后半段仍整体物化并重复写 CAS

**证据。** [complete_ocr.py:279](/D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/complete_ocr.py:279)逐项 read_asset，把完整 bytes 留在 assets 并 put；规范化 `_raw_output` 再 put（structured_parser_evidence.py:121–125、615–620），任务缓存提交再 put（provider_task_cache.py:128–129）。[artifact_store.py:72](/D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/artifact_store.py:72)先完整写临时文件和 fsync，到 link 时才知道对象已存在。成功任务 cache hit 仍读取所有 assets bytes 后规范化。

**影响与最小修复。** CAS 最终去重正确，不代表临时写入、校验和内存复制为零；512 MiB 资产上限下尤其明显。沿已有 descriptor 接口传递核验后的引用，避免同一请求反复 bytes→CAS，按需读取必要数组数据后释放。引用与旧 inline 等价、同任务 0 新 HTTP 的已有测试是真实改进，不能否定；保留完整捕获和实际字节校验。

### 14．P2：串行计算锁位于大对象分配之后，不能限制排队内存

**证据。** [server.py:435](/D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/server.py:435)的 /ocr 在锁前读最多 25 MiB 并解码 RGB，允许 4,000 万像素，454 才进锁；493–499 的原生 PDF 先读取最多 64 MiB 原件＋32 MiB 上下文再锁。一个 FastAPI worker 仍可有多个同步 route 线程。

**影响与最小修复。** 多模块请求等计算锁时，已持有原 bytes／解码图像；执行串行不等于整机内存有界。本轮没有并发压测，不能据此断言生产 OOM。准入／容量令牌应在读取和解码前取得，按字节、像素和已有预算限制等待量，明确返回忙／排队状态。

### 15．P1：SL 运行入口仍是单来源 policy，合法第二版被拒绝

**证据。** [oer_unified_server.py:138](/D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/oer_unified_server.py:138)只载一份 manifest；[competition_scope.py:150](/D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/competition_scope.py:150)只存一个 source SHA，178–182 对其他 SHA 拒绝。原生发布入口要求 adoption 等于该进程 policy（oer_native_pdf_server.py:155–161），Java 注册也比对唯一 health manifest（CanonicalEvidenceService.java:156–159）。整个 policy 进入索引签名（oer_server.py:171–180）。

**具体案例与最小修复。** 比赛版 `6331…0492` policy 下，官网 rev1 `969e…549f` 即便携带本版正确范围仍被拒绝；当前是未实现多版使用，不是已经证明静默混版。沿现有 policy 增加按精确原件 SHA＋scope SHA 选择的目录，请求绑定实际采用的有序版本集合，贯通注册、查询和恢复；未知版不得回退默认版。不能伪装 SHA 或换 latest 来绕过。

### 16．P1：恢复器输出路径映射，但固定基线及迁移 reader 仍读取旧绝对路径

**证据。** [restore_frozen_artifacts.py:106](/D:/R&D/ConSense/ai_consense_service/tools/restore_frozen_artifacts.py:106)只输出 path-map，明示 runtime 未启动、旧绝对路径未重写；[fixed_development_baseline_runtime.py:24](/D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/fixed_development_baseline_runtime.py:24)、35–38、124–132 仍按原 C:/D: descriptor 路径读。migrated_qdrant_index.py:23–35 只有 WSL /mnt 特例；未找到 runtime 消费恢复 path-map 的接线。

**影响与最小修复。** 包还原到比赛主机新目录可以字节校验成功，reader 仍访问不存在的旧地址。沿既有 descriptor 读取边界接由外部 manifest 绑定的只读 locator；改变物理寻址，保留冻结逻辑路径、来源身份和签名。隔离还原时必须让原路径不可访问，再实际冷启动和查询，不能只看恢复退出码。

### 17．P1／验收未完成：七版 22,214 技术页尚无完整质量与版本隔离检索通过记录

**准确分母。** 七个不同字节来源＝六个取得的官网完整库＋比赛 Batch2，不声称搜全官网撤下的历史版本。七版技术页位置合计 22,214，逐版表见[离线包规格](../specs/sl-technical-offline-package-20261020.md:25)。最新七版真实双路覆盖各两页，共 14 页／14 POST／28 method-page；后继只有部分版本完成所存 raw 的正常导出／导入，见[当前记录](sl-technical-source-and-retrieval-quality-20261021.md:4)。

**不能混算。** 14 页私有候选的 224 检查项＝219 PASS＋5 UNKNOWN；这不是全库准确率，也不是所有双引擎字段均通过。历史 186 输入的联合 native 重解析、单比赛版 3,174 技术页、fixed05 向量数均不是七版质量分母。其余 22,200 页未获本轮局部验收覆盖，不等于断言全未解析或全错误。

**最小后继与门槛。** 按本版原件／章节／范围保全量覆盖账，保旧正确、FAIL／UNKNOWN／未执行；从混合公式、FWP材质差异、FIN跨页、ELE36图片内清单、2026编号等缺陷族扩展，再验完整单元、同版本嵌入、选择版本及拒混查询。优先复用已存 raw 修后处理，质量门未过不因赶比赛重复打包。

### 18．P1／交付未完成：已恢复七版子包不含全部数据库、索引和历史依赖闭包

**证据。** [已有子包收据](/D:/R&D/ConSense/.local/sl-seven-competition-next01/root-subpackage-acceptance01.json)为七版的 14 选页、48 文件、1,541,753,770 字节，fresh restore/cold validate 为真，但 `fullCompetitionPackageAccepted`、`sourceQualityAccepted`、`allDatabasesAndIndexesIncluded` 全为 false。本轮只重算小 manifest 的数量与大小，未重新扫描大包；48 文件 SHA 无重复组，所以不能仅凭 1.54 GB 指控同文件重复打包。

**能力边界与最小修复。** [offline_delivery/delivery.py:36](/D:/R&D/ConSense/ai_consense_service/tools/offline_delivery/delivery.py:36)、220–248 验用户声明的资产和阶段，没有核七版全部 source revision／原件／CAS／数据库最新切点／索引的业务闭包。通用工具没有谎报完成，缺的是最终交付门。沿既有 manifest／恢复器补版本与修订清单、可达依赖闭包、同一切点的数据库和索引身份及 missing/unknown；质量过后再隔离恢复、逐修订冷读及真实向量回取。不能只拷一个 SQLite 或以旧 SQL 覆盖后续写入。

## 已修内容、反证与文档漂移

- round380 已实现 Vetting 目录先筛、选定来源写边界摘要、semantic prepare／scan、包内短引用和同输入预检复用；不得继续笼统报“所有 Vetting 路径都读全项目”。05–07 指明剩下的真实入口。
- 原生传输已有 file part、64页／32MiB上下文限制、8MiB响应界限及 one-shot；旧 OOM／Starlette 1MiB 拒绝属于修复历史。限制并未解决01、11–12。
- 成功 OCR 任务的精确缓存与 Paddle CAS 引用都存在；10、13分别只针对冷页会话和引用之后的内存／写入。
- 旧离线包规格仍写“Java SL 消费契约未修”，已落后于当前 `NativePdfSlScopeContract`、两类 Java consumer 调用和[实际局部组件证据](sl-technical-source-and-retrieval-quality-20261021.md:117)。准确状态是 typed contract／局部组件已补，正常 HTTP 多版准入、全库质量和真实版本检索仍未闭合。本报告不回写历史记录。
- SL PRE 参考库仍排除；项目 PRE 不是同一类别，应按实际身份纳入或保留待确认。技术章节引用 PRE 不能按关键词整章删除。按 source SHA 严格拒绝错误版本是正确保护，不能为“跑通”删除。
- 未把 48 文件子包的大体积等同重复字节。结构冗余、临时复制和永久重复存储分别举证。

## 本轮复现、失败与覆盖

| 证据 | 实际执行及能证明的范围 |
| --- | --- |
| [root-evidence.json](/D:/R&D/ConSense/.local/oer-readonly-audit-20261023/root-evidence.json) | Git 基准、运行进程定位、03／04静态调用链；没有真实上传 |
| [transport-evidence.json](/D:/R&D/ConSense/.local/oer-readonly-audit-20261023/transport-evidence.json)／[脚本](/D:/R&D/ConSense/.local/oer-readonly-audit-20261023/transport-control-flow-check.py) | 实际候选控制流＋替身 PDF／方法，跨批续接丢失与两页10次整文件read_bytes；未真实解析 |
| [downstream-evidence.json](/D:/R&D/ConSense/.local/oer-readonly-audit-20261023/downstream-evidence.json)／[脚本](/D:/R&D/ConSense/.local/oer-readonly-audit-20261023/downstream-reembed-probe.py) | 实际 index 方法 AST＋假模型／DB，编码数2→0→3→2；不是模型性能 |
| [sl-evidence.json](/D:/R&D/ConSense/.local/oer-readonly-audit-20261023/sl-evidence.json)／[脚本](/D:/R&D/ConSense/.local/oer-readonly-audit-20261023/sl-pure-evidence.py) | 实际纯函数混合内联反例，子包小manifest计数／完整性标志；未重扫归档／CAS |

三个轻量脚本均保存来源 SHA／输入输出及边界。已有三个 window-server 测试尝试在导入时因缺少 fastapi 报错，测试主体执行数为0；随后纯方法反例不冒称这三项全套测试通过。自编向量夹具首次缺 parentId 的失败也已保留。没有新测真实延迟、传输抓包、峰值内存／显存、吞吐或全量质量。

已审正常上传／路由、原生与视觉候选、owned provider／CAS、normal 准备、列表／状态／guard、候选取证、动态与固定索引、SL scope、离线恢复和交付门。未逐一审全部原件页面、全部模型资产、在线数据库行、全 CAS 闭包，也未做浏览器业务、目标 Linux／H800 或完整端到端运行验收。

## 建议实施顺序和验收门

1. **先修内容和范围：01–03。** 验边界关系不随批次划分变化；真实 raw 的混合条件文字恢复且不改义；图纸／未决身份零细读，正常 PDF 表格候选能进入公共链。
2. **随后消除高频整图和重复编码：05–09。** 以真实大来源记录每个动作的 DB大字段读取、snapshot请求、实际编码窗口和返回字节；来源／权限／版本变更必须仍被拒绝。目录和状态应不读正文大字段，同源多unit只需一次请求私有核验帧，新增来源只编码缺失窗口。
3. **再处理冷执行与内存预算：10–14，并补04。** 有界会话复用、原件CAS批次引用、流式比对、描述符贯通、解码前准入；实际验证取消／失败／缓存失效和整机峰值。不能靠降分辨率、截断完整单元或删除证据使数字好看。
4. **SL按15–18串联收口。** 先多版policy和路径消费，再逐版来源质量／完整索引／版本隔离检索，最后完整数据库和依赖闭包的冻结及目标恢复。保留每层真实未过项，不重复制造局部子包来替代验收。

这是一份缺陷审计和后续验收清单，不是修复完成声明。源码正确性、真实吞吐、来源质量、业务效果和比赛运行分别验收。
