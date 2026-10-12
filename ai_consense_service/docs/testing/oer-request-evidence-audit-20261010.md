# OER RequestEvidence 与已有运行证据审计

更新：2026-10-10，Asia/Shanghai。只读审计新请求日志及已有产物；没有启动神经模型、浏览器或 HTTP 验收，没有改生产代码、原件、canonical 快照和检索状态。规则依据 [OER 已确认范围与执行规则](../oer-scope-and-rules.md) 和 [实施交接](../HANDOFF-ocr-embedding-rerank-20261008.md)。本文不改变 SL 技术范围、比赛排除项、标准模板隔离、完整表组采用和 Codex 标注边界。

结论：现有同步端点的请求隔离、成功运行的候选／分数／输入 token 记录以及来源资产绑定均可核对。审计发现日志失败时的计数／部分写入描述符缺陷，主实施者已修复，随后独立复核通过。当前日志完整性不等于检索质量、法律判断或应用端验收通过。

## 审阅对象与冻结证据

代码：`tools/vetting_eval/oer_request_evidence.py`、`oer_server.py`，并追踪 `window_runtime.py`、`token_windows.py`、`structural_retrieval.py`、`oer_source_registry.py` 和 `artifact_store.py` 的实际调用关系。

原观察版 RequestEvidence SHA-256 为 `489c9f681a283bc8083e7790931f18d423c5e72575faf128d4d307d214deb118`，已复制到本机忽略目录保存；修后独立复核版为 `b0b34f5b26143271561f9a1b2b05d740e52e58a796363689994ab64982ab40fc`。代码可能继续迭代，审阅结论绑定这些字节；其他代码 SHA 见审计收据。

审计脚本固定了当时已有 terminal receipt 的 **25 份完成运行：4 份 `/index-canonical`、21 份 `/retrieve`**。分母、目录、各 receipt SHA、request／response／events 实际 SHA 和字节均记录在 [audit-existing-receipt.json](../../../.local/state/oer-request-evidence-audit-20261010/audit-existing-receipt.json)。原运行目录仍可能继续增长，不把此快照冒称全服务永久总数。25 份均为 completed；本轮失败验证来自临时目录的故障注入，不能写成这批真实模型已发生失败。

主核查样本是 `20261009T162008.963466Z-6cb32f72b4624794a3d37887f1247f96`，问题为 “What happens if the tender booklet is a photocopy rather than the original version?”，项目为 `oer-gct1-sl-amr-development`。关联索引运行 `20261009T161948.759666Z-d583cd07b4f5453884586f2cce4c852f` 保存了准确派生 `/index` 输入及完整窗口计划；其中 39 个 canonical parent 和 47 个 embedding point 提供复算来源。此次是已有 GCT／SL AMR 开发产物核查，不是新文件留出或比赛完整语料评价。

## 请求隔离与终态

`RequestEvidence.active` 是每个 journal 实例独立的 ContextVar。FastAPI 当前三个记录端点均为同步调用，backend 的 RLock 序列化实际索引／检索；外层请求在等待和执行期间拥有自己的目录、事件 ordinal 与计时。stageOrdinal 是共享 runtime 的阶段序号，不是请求目录内 ordinal，不能用其起点是否为 1 判断串日志。

已有并发测试在 4 个线程执行 8 个请求，每个请求产生 12 条带自身编号的事件；独立执行通过，目录内没有他请求数据，ordinal 连续，事件 SHA 一致，请求外 observe 不落盘。嵌套 `/index-canonical` → `/index` 保持一个外层目录并记录 `nested-operation-input`，未把准确派生 chunks 丢掉或另造一份独立终态。临时目录验证同时确认 wrapper 返回原 result 对象、未改 request 的拼写／空白／null、未改 unknown 和 humanConfirmed。

运行开始后的普通异常及 BaseException 由外层 finally 写 failed receipt，并恢复 ContextVar。HTTPException 的 status_code／detail 和 WindowError 的 kind／metadata 会保留。这层记录的 completed 表示 Python 操作返回并保存结果，不证明响应已送达客户端。Pydantic 校验发生在 wrapper 前、journal 初始目录／request 保存失败、进程强制终止和终态文件自身无法写入均不由此 finally 保证完整收据；缺收据仍为缺收据，不归入 completed。当前只包装 `/index`、`/retrieve`、`/index-canonical`；`/project`、health、canonical 读取和 asset GET 没有同种请求日志，不冒称所有 HTTP 端点都有失败终态。

原缺陷定位为观察版 `oer_request_evidence.py:39–46`：先增加事件 count，再编码和 append，导致失败时描述符与真实文件不一致。独立故障复现与修后结果如下：

| 故障 | 原观察版 | 修后独立复核 |
| --- | --- | --- |
| 非有限值触发 JSON 编码失败 | failed、声明 1 条事件、实际 0 行；context 已重置 | failed、声明 0 条、实际 0 行，SHA／byteCount 一致，context 已重置 |
| append 写入 12 字节后抛 OSError | failed、声明 1 条／0 字节、SHA 不匹配真实 12 字节 | failed、0 条完整记录／12 字节、SHA 匹配，integrityStatus=`malformed_or_interrupted`，context 已重置 |

主实施者把 ordinal 推进移到成功 append 后，终态再读取真实 events.jsonl，流式重算 SHA、字节及完整记录数，保留损坏状态。审计没有修改该实现。修后现有 `test_oer_request_evidence` 共 4 项独立执行通过，含并发、模型操作失败、编码失败和部分 append；临时故障没有触碰真实 run-evidence。

## 候选、分数与 token 的复算

25 份已完成收据的 request／response／events 描述符、实际事件条数和请求内 ordinal 均一致。21 份 retrieve 分别以准确 canonical 输入和实际 events 重算，结果如下；所有逐运行布尔检查在上述审计收据中保存，不能仅据本表总数验收。

| 层次 | 重算依据 | 结果／边界 |
| --- | --- | --- |
| 候选输入 | query SHA、candidatePool SHA、每个 parent 的 sourceHash／documentId／稳定 payload SHA | 21 份均匹配；pool 与 RRF 提交顺序一致 |
| Dense 聚合 | 实际 window score、完整 point → seed 映射、各 lane 最后 fetch | 各 lane 的 max score 和 winning window 均匹配 |
| RRF | 两条 dense 有序 parent 池、BM25 有序池、实际 constant | 全部融合分数及截取 candidate pool 可复算一致；未重新估计 BM25／神经分数 |
| 同池两阶段 rerank | baseline／structure 实际 pair 文本、各自 manifest／pair SHA、实际 score 数组 | max score、winning window、每个 candidate 的最终 context score 均一致；两阶段没有偷偷换候选池 |
| 返回范围 | 全量 rerank 真实 score order、source-unit closure、response hits | 最终顺序／返回 ID 一致；21 份返回的 parent payload 与索引输入完全相同 |
| 真实 tokenizer 边界 | `inference-full-input-tokenizer` 的实际 fullTokenCounts、batch 顺序、cap、effectiveTruncation | 21 份逐顺序匹配完整 pair manifest；全部 effectiveTruncation=false 且不超各自 guard cap |

首轮样本另用该 receipt 指定的 **离线 reranker tokenizer** 重算，未加载神经权重：39 个候选产生 baseline 41 对、structure 6 对，共 47 对。47 个重算 token 数与 pair manifest、真实 inference guard 日志三路逐项完全一致，最大 1,325 tokens。fast tokenizer backend SHA 及 tokenizer.json／tokenizer_config.json／special_tokens_map.json／sentencepiece.bpe.model 四项文件 SHA 一致。这里的 47 是实际 pair 数，不能称 47 个独立候选或完整法律证据集。

日志的 request 是 Pydantic 已接受对象的 `model_dump(exclude_none=True)`，response 是返回 Python result 的规范 JSON；它们不是原始 HTTP wire bytes／headers。日志按已执行路径提供准确文本、窗口、分数和身份，但 cached index 命中不会重新生成模型事件；复算此类读取须同时保留 immutable canonical、索引 metadata 及首次构建收据，不能拿空模型事件当再次推理。

日志代码没有选择 parser、改变 CPU／FP32、512／2048、overlap、候选阈值、结构门控或 source-unit closure，也没有用参考答案覆写分数。新增 RequestEvidence 的字节参与软件算法身份，因此代码修复会改变绑定身份；该事实不是日志私自调整模型配方。已有 21 轮的 sourceQualityChanged=false、semanticScopeVerified=false，comparison.qualityImprovementEstablished=false，原 unknown／质量状态保留；没有把 Codex 审阅冒充真人确认。

## 来源资产与质量限界

两份 immutable canonical 快照共有 **41 个资产引用**，其中多个引用共享原页字节；不是 41 张独立图片。文件级核对 descriptor 的相对路径／实际 SHA／byteCount、sourceSha256 与 projection identity 一致，humanConfirmed 均为 false。随后直接使用现有 `CanonicalSourceRegistry.asset` 只读回取全部 41 项，每项返回 descriptor、media type、准确字节 SHA 和长度均匹配；对每份快照各一次错误 source SHA 回取均以 `ASSET_NOT_BOUND` 拒绝。

HTTP asset 路由先核查指定 module 的 canonical chunks 和当前范围，再按 projection SHA＋source SHA＋assetId 绑定回取；ArtifactStore 读全文件验证 SHA／字节，registry 验证 PNG／JPEG／WebP／PDF magic。ETag 使用准确资产 SHA，响应带 source／projection 身份并 no-cache。此次复核证明已有 registry 文件与回取函数同源，没有发起新 HTTP GET，也没有完成 Java／前端 Q10=A 的图像表格展示验收。

主样本关于非原件招标 booklet 的命中包含 GCT 的条件／例外文字及其原始 parent，记录可以支持追查该问题；没有实际生成咨询答案。首轮 source-unit 返回与 clause 关系、SL 三页组来源能在原快照定位，但本审计没有把这些开发样本重新标成全文 gold。神经分数可复算并不证明必要证据召回、同池排名改善、原文全准确率或法律语义完整。参考标注、完整独立留出、真实检索相关性及应用端资产返回仍按主验收分别处理。

## 本机审计产物

原运行与 private receipt 留在忽略的 `.local/oer/retrieval-state/run-evidence`。审计辅助程序只写 `.local/state/oer-request-evidence-audit-20261010`，已确认 git ignore；不随代码发布模型、原件或运行证据。

- [已有运行审计收据](../../../.local/state/oer-request-evidence-audit-20261010/audit-existing-receipt.json)，SHA `e3a29683cd586e2d4dd4eda89385308284b99fa2aa8fd3c2a2832243b4d7174a`。
- [独立 tokenizer 与部分 append 故障复核](../../../.local/state/oer-request-evidence-audit-20261010/tokenizer-and-partial-receipt.json)，SHA `7571d78360ba7fc06a9d76285ce9a6c02cfd8d9f7d8f1a1d57c303cabcbec53a`。
- [返回 parent／unknown 与同源资产读取复核](../../../.local/state/oer-request-evidence-audit-20261010/source-return-receipt.json)，SHA `83cfc37776a9a6488f0449865e46292de56c44bebf0d1a7f9df88e907222e191`。

只读资产 helper 首次因 Windows 默认 GBK 读取含 Unicode 的 UTF-8 response 失败，未读取或修改资产；明确 UTF-8 后复核通过。该失败属于审计 helper，不是 OER 服务或真实模型失败。原始异常输出保留在当前工具记录；上述收据均来自成功复核。
