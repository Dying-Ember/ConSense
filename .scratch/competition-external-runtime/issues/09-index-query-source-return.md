# 09: 让新资料完成建索引、查询和原文回查

<!-- competition-sl-single-and-models-20261101 -->
**最新决定（2026-11-01）：**每个项目仅选一个 SL 版本，全部已确认版本供不同项目选择；逐一核查 OER 模型，若有调整过的权重，按最后一次实际调整作为比赛资产携带，并在票 14 使用对应资产验证。旧多选行为不再作为目标，训练产物身份、加载结果和模型质量据实报告。详见[权威实施说明](../../../ai_consense_service/docs/specs/competition-container-external-runtime-20261029.md)。

**项目向量隔离（2026-11-02）：**Vetting／Advisory 的项目资料解析、投影和向量按项目及来源版本隔离；复用前核对项目、原件／版本和投影身份。SL 各版本是共享标准资料，每个项目仅绑定所选一版；详见[权威实施说明](../../../ai_consense_service/docs/specs/competition-container-external-runtime-20261029.md)。


**2026-11-01 demo 对齐：**沿冻结基准的 `/index-canonical`、`/retrieve` 和原文回查数据流，运行已采用的 embedding、检索及 rerank，不新建平行检索框架。相同资料、模型和版本的有效索引可以复用；不能证明适用时才重新处理，仍须核对项目、来源及版本。

**What to build:** 代表性新资料经外置检索链建索引并查询，使用者从现有页面打开对应项目与版本的原文原页。

**原关联（按实际需要）:** 08 — 稳定 OCR。新资料查询需有该资料实际处理结果；可复用已有适用结果，不额外等待无关整票。

**Status:** done — 按本机代表性 demo 范围关票；识别与召回质量及赛方环境未验

**验收（demo 范围）：**

- [x] 对代表性新资料及其实际处理结果，使用真实容器和已采用权重完成索引与检索；相同来源版本的有效结果可复用，不能用预填结果冒充运行。
- [x] 在现有浏览器入口查询，并打开正确项目、角色、版本和物理页；保留来源身份、项目范围与索引／模型版本检查，历史与新资料不混用。
- [x] 模型或版本不匹配、缺文件及处理失败要明确提示，不将失败或不完整检索伪报成功；保留既有数据与结果。
- [x] 记录代表性真实运行和未覆盖范围；召回质量、模型理解及赛方环境另行说明，不为 demo 重复全量重建或为每项单独重启容器。

**2026-11-01 真实业务关票证据：**在隔离项目 `proj_mvxmgte2cplp`，对 08 的新资料文档 50（原件 SHA `544defab851a2e44a4e6ab13d1bf2bea66c24ca651789a65e41d0b11a14ca4f6`、业务内容 SHA `a4b407245dfae8333675e04ca9708745dc5ee58f7c8f593d9fa328b70e553fe3`）通过 Vetting 页面选择“所有选定项目文件”，查询 `TUNG CHUNG AREA 98`。Source `/index-canonical` 实际 completed、cached=false、43 parent / 86 windows / 86 vector；使用真实 BAAI/bge-m3 和 BAAI/bge-reranker-v2-m3。索引 operation `4f79b3a7b90a4ed6a203e77b996b5f85`、查询 embedding `132bcb006c9f4f8fa24a3b7926c3c089`、rerank `e78e2a185f2d4e14bb94c16cc8168431` 均 completed／released，最终 owner 不存在。检索首条 `canonical:6fca638d…` 为 doc50 物理第1页标题，页面从该命中打开同项目、来源版本绑定的 PDF 第1/4页；request `9388d989-d1f1-41a9-b787-b24811b9a522`。Source 原始请求、响应及回执在 `/srv/consense-rehearsal-20261029/instances/local01/source-serial03-20261030/state/vetting/run-evidence/20261101T101145.968345Z-ffc23e815a8a4c01a1e2f65a5bdb06d5/`，建索引回执同目录树 `20261101T101038.807816Z-1f2b80a909374d569189079696756cf2`。来源投影、sourceScope 与模型 operation 输入对应，document 50 为项目事实资料、PARTIAL、sourceQualityVerified=false；不将查询成功解释为 OCR 全文质量或召回质量已验。

**负例与未验边界：**实际 App 原件错误 SHA 和跨项目索引有拒绝记录，历史真实检索超时返回业务错误；缺文件为容器内合成存储夹具，旧索引签名与模型路径不匹配为软件测试或历史配置故障。本次未对 doc50 故意制造缺文件／模型换版负例，不把这些记录称为本次完整端到端负例。赛方环境、模型理解及更广召回质量未验。

**已有结果与缺口：**03 已有真实历史索引搜索返回 10 条命中和来源回查记录；合成来源的真实 embedding／rerank 查询也有记录，但不能代替新资料从 OCR 到页面回查的演示。
