# Advisory 接入 current canonical evidence 的最小完整方案审计

日期：2026-10-10，Asia/Shanghai。本文只读源码、接口和现有测试，新增设计记录与忽略目录的源码冻结收据；没有修改生产代码、启动服务／模型、调用 HTTP／数据库或运行业务验收。共享 seam 正由其他工作并行加固，本文以保存的源码 SHA 为基线，后续修改必须重新核对差异。

## 结论

不能仅把 Advisory 的 base URL 指向 OER。当前 `AdviceService` 的 rebuild、status、retrieve、标准采用依据补充和 citation 转换依然是 legacy 路径；直接调用新 `OerBusinessEvidence.adviceHits` 后，`withIncorporationContext` 会从旧 `EvidenceChunkRepository` 混入另一套文字，`toCitation` 又会丢掉已经存在于 hit 的 canonical pointers。

最小完整改动是：在显式 OER 配置下，按**本次用户选择的 scope**建立 `OerBusinessEvidence.Session`，用 current bindings 和相同 selected corpus 完成 index／retrieve／采用依据补充；完整 source units、unknown 和来源指针进入模型、回答 citation 及历史冻结。legacy 分支只在未配置 OER 时使用，canonical 缺 binding／失败不能回退。当前引用和对话存储框架可以保留，不需要另建 Advisory 子系统。

业务范围继续服从 [规则入口](../oer-scope-and-rules.md)：NTT／SCT／SCC 标准模板只供 Drafting，实际项目版本独立处理；SL 非技术部分及已排除计价／技术 PSS 不能进入候选池；2d／2e 隔离；Q11 只是审查发现→现有 Advisory 的轻量问题草稿入口，引用线索须独立核对当前原件。

## 源码基线与现状

本机收据：`D:\R&D\ConSense\.local\state\oer-advisory-design-audit-20261010\source-ledger.json`，SHA-256：`03c5c599b3f6e66b8eeecba405e51451862701c1aaf1a25dd4463fb5ba3994e8`。收据绑定 14 个源码／测试／前端文件的实际字节，并在同目录 `source/` 保存只读分析基线。`AdviceService.java` SHA 为 `b2b9d3d3e53f0fd9af7dc29325e08303cad7b3a437eb8f77bde47e5df628b1ca`，`OerBusinessEvidence.java` 为 `fea89d391a3a7c0ad8475da76a1c9f5c23eaba6bdd5b0240886c3f15779c4b20`。

| 当前位置，行号相对冻结源码 | 现有行为 | canonical 接入必须覆盖的变化 |
| --- | --- | --- |
| Advice `rebuildIndex` 69 | 固定 fullset，`VettingCorpus.chunks` → `ai.embed` → EvidenceChunk／VectorStore，删除 project 旧索引 | 新分支接收当前 scope，load module=advisory 的 current Session，索引 sealed JSON；不删除／覆盖其他模块和 scope 的索引。 |
| Advice `status` 140 | EvidenceChunk count、fullset freshness、VectorStore count 和 `ai.embeddingAvailable` | canonical receipt／health／绑定和候选池身份；不能用旧 Qdrant 数量和 legacy embedding health 宣称 canonical ready。 |
| Advice `ask` 178、`retrieve` 266 | fullset 旧 freshness，再选定 documentIds 过滤；小库 stuffing，大库 vector search | 本次 scope 在 index 前构成实际候选池，完整双粒度／结构复评检索；不要绕过 OER 走 legacy stuffing。 |
| Advice `withIncorporationContext` 536 | standard hit 后，从旧 EvidenceChunk 追加 tender composition／mention 文本，最多 12,000 chars | 追加内容来自同一 Session 的 canonical snapshot，完整单元采用，保留未提交／unknown 状态，禁止旧表混入。 |
| Advice `incorporationSupported` 554 | reference_only 无条件 true；project_contract 至少一条 tender；standard 只在 complete=false 或无 mention 时拒绝 | reference_only 至少有真实标准条文引用；project conclusion 使用标准时要有本次所供有效项目采用／修订／引入证据，null／unknown 不能等同 true。 |
| Advice `renderExcerpts` 371、`toCitation` 391 | 渲染平面 text 和 legacy sourceHash；不拷贝 DTO 已有 canonicalEvidence | 渲染完整单元的来源、成员、未知边界；pointer 列表与单元 trace 进入 citation，hash 意义不得混写。 |
| Advice `persistAssistant` 474、`history` 405 | sourcesJson 冻结并按 DTO反序列化，历史不重检索 | 可沿用 JSON 存储，但先确保 DTO 保存全部 canonical pointers／sourceUnit 和 unknown，历史不重链当前版本。 |
| Advice `sources` 515 | 当前 catalog 的 inclusion／exclusion＋legacy parse/sourceHash | catalog 继续保留排除和失败／新上传状态，另表达 canonical注册／revision状态，不能把 parseUsable 当 canonical ready。 |
| AdviceController／web API | rebuild／status 无 scope；ask 和 sources有 scope | rebuild／status 增兼容可选 scope（默认 fullset），前端传当前 scope并在切换时刷新状态。 |

`ProjectDocumentScope.currentCatalog` 留最新同名上传，`selected(...,"advisory")` 执行模块和比赛排除；这套选择可以复用。必须保留未解析／失败最新版本的事实，不能先用 parseUsable 过滤让旧版或不完整候选池看起来 ready。当前 canonical `currentBindings` 已要求每个 selected ID 有最新合法 binding，并核对原件字节、Java hash、routing／module、同名 supersession；缺失就明确拒绝，不静默补 legacy。

## selected scope／indexKey 与状态

推荐每次询问使用以下可审计调用链：

```text
normalize scope
→ ProjectDocumentScope.selected(project, current catalog, scope, advisory)
→ OerBusinessEvidence.load(project, advisory, selected)
→ verify exact current scope index receipt (or explicitly rebuild that scope)
→ adviceHits(session, question + bounded user clarification)
→ current canonical incorporation units from same session
→ complete evidence prompt + exact [n] citations
→ revalidate current selected scope/bindings after chat
→ frozen sourcesJson/history
```

当前 Session 的 indexKey 是 `project::canonical::module::<hash of ordered projection SHAs>`，因此 module 和真正 projection 集合已隔离。两个 scope 如果恰好选到相同集合，可复用同一 key；集合不同必须有不同 key。可以在状态 receipt另记用户 scope供 UI说明；无需为了不同标签而重复同一索引。绝不能先用 fullset 做 topK，再丢掉不在 contract／tender 的 hits；这会让范围外来源占掉召回名额，也不符合选定候选池的结构复评。

冻结的 `OerBusinessEvidence.current` 在 index／retrieve 前后重新验证固定 documentIds、binding fingerprint 和 views，并在失败后 invalidates Session。这能防止已有来源变更；它**不重读本次 scope的完整 selected集合**。若问题进行期间不同文件名的新范围内来源加入，旧 IDs仍可通过。Advice在提交 prompt前和 LLM响应后须重取选定 scope并比较集合／绑定身份；变化应重新检索或明确告知重新提问，不把旧 snapshot称为当前 package。不能在新旧 Session之间复用已经编号的 [n] sources。

当前 seam／client 没有公开 receipt-ready／index-status查询；client receipt 保存在 Java内存，Java重启后没有 receipt 即拒绝retrieve。这是诚实状态，不应改成根据 Python磁盘索引存在或旧 DBcount便自动 ready。最小可以增加只读状态接口，报告当前 source／projection集合、模块、索引signature、固定recipe／health及receipt是否存在；rebuild成功后登记，绑定／scope／health失效即 stale／unready。若选用 ask 时自动确保同一scope索引，也要记录实际 rebuild和signature，status不能虚报无成本ready。两种 UX实现方式需保持同一候选池门控，本文推荐沿用已有“刷新索引”入口并增加scope参数。

`llmReady` 和 chat profile仍由 AiGateway负责；canonical embedding／rerank能力由独立 advisory retrieval health提供。`embeddingReady` 不能继续指代 `ai.embeddingAvailable()`；`vectorProvider`／collection／indexed应明确表示canonical索引，不能显示legacy Qdrant信息。当前 Python按targetModule固定启动，advisory有自己的模块URL／进程；共用registry/assets不代表可以对vetting进程发送advisory请求。该调整不改变用户选择的MiniMax等chat来源。

## 完整 sourceUnit、incorporation与模型输入

当前 `adviceHits` 已携带 `canonicalEvidence`、`canonicalMembers` 与 `canonicalSourceUnit`：真正reranked seed持有其分数，complete_observed_unit回取全体members，unknown时保留seed及原reason。它提供了可用的接缝，不能在Advice又转换成legacy Chunk或从旧表按anchor查正文。

`complete_observed_unit` 只证明声明的canonical结构与观测ranges闭合；协议仍有 `qualifiersComplete=unknown`、`semanticScopeVerified=false`，sourceBlockComplete也可null。不能把这个状态改写为完整合约／标准已审核或自动采用正确。prompt应提供完整成员文字和原定位，局部unknown reason要可见并约束依赖该关系的结论；不能因为metadata未知就拒绝所有无关可靠文字，也不能把unknown seed当完整父条。

最小canonical采用依据补充可在共享seam提供helper：从同一 Session 的允许project clauses中选择实际可能涉及引用／采用的origins，用现有纯JSON `OerCanonicalSourceUnits.resolve` 构建完整observed units，并保留选择trace、未知和未提交unit。可沿用名称／literal mention作为**检索线索**，不能把它当已证明的adoption关系。补充单元不是reranked hit，不赋予虚构分数／排名，也不把它的derived members递归提升为新的origins。若模型输入预算不足，以完整unit为界记录遗漏并局部unknown，不截取12,000字符后假称完整。

现有实现的两个具体风险来自源码，不是本次新服务复现：

- `complete=compositionChunks.allMatch(present)` 在composition集合为空时得到true；这只说明没有匹配到命名composition，不能证明已提供项目采用依据。
- `Boolean.FALSE.equals(applicabilityContextComplete)` 只拒绝false，null会进入后面的mention检查；当前canonical adviceHits的该值就是null。非空“SL／GCC”字串不验证版本、例外、修订或引入语义。

建议保留既有两类basis，门控条件写清：

| basis／情形 | 最小可支持条件 |
| --- | --- |
| reference_only | 有本次真实标准条文citation；答案只是参考解释，项目适用性明确未知。原问题是否只问标准含义仍需语义判断，不能由模型回传该标签便跳过一切证据门控。 |
| project_contract，未援引standard | 有当前选定项目合约文字citation；事实／目录alone不能建立合约义务。不能因role=tender就把被引用的标题或空白表自动视为实质依据。 |
| project_contract，援引standard | 同时引用当前范围内标准及实际项目采用／修订／明文引入依据；完整提交相关observed单元、版本／例外／override上下文，无法确认关系时insufficient并澄清，不能把null完整性当true。 |
| 缺少采用／版本或结构关系 | 局部unknown，继续可支持的参考解释；需要项目适用性时grounded=false，询问具体所缺条文／事实。 |

这个门控仍不自动证明法律结论正确。既有ADVICE prompt第6／9／12条已经要求实际采用版本、排除／优先、明文例外；canonical接入要保留这些语义约束，而非通过coverage布尔将整库视为已纳入。本文没有添加what-if版本切换、审批或复杂adoption状态机。

## citation、历史和前端

现 `CitationVO.canonicalEvidence` 已存在且 `ChatMessage.sourcesJson` 为LONGTEXT，因此保存pointer列表不需要新建关系表。`toCitation`应深拷贝本次完整members对应pointers，保留module=advisory、原件SHA、projection／revision、unitId、parts／tableGroup／assets／relations／unknown。建议另保留sourceUnit coverage／trace供prompt和历史说明；只有pointer并不能表达retrieval unit的unknown reason。`sourceHash`的legacy字段目前指Java parsed hash，新hit为兼容它也填javaParsedSourceHash；不要把它显示成canonical revision，新的准确identity从pointer读取。

问答中的 [1]／[2] 是每次提交的证据编号，`validatedCitations` 的严格已提交唯一匹配可以保留。完整单元包含多个canonical members时，一个引用必须能回到对应完整pointer组；citation只显示seed page／anchor会误导完整范围，UI应显示实际member位置／完整表组。去重可按真实unit identity做，不能只按相同文本把另一个source合并。

`persistAssistant` 已保存实际sources，`history`不重检索，适合保留历史证据。不要把历史消息重链到新projection；历史pointer获取旧原图若因current binding变更被资产服务拒绝，前端应明确“来源已变化，无法确认当前对应”，保存原历史文字而不换新图。澄清回复目前只带original question和最多5条／6,000字符用户background，不把上一答案当证据；这个限制与同project／scope校验应保留，新回答仍用当前Session重新检索。

前端TypeScript `Citation`冻结基线没有canonicalEvidence字段，AdviceView也未装入共享CanonicalEvidencePanel。需要增加pointer／sourceUnit类型并在当前citation和历史citation入口接共享图表展示；项目／scope切换应刷新scope状态，迟到响应不能覆盖新选择。Q11带入的审查引用只作可编辑问题线索，不用旧finding证明adoption或替代当前检索。

## 必需测试与验收层次

既有AdviceDocumentScopeIntegrationTest覆盖模板／答案／计价／技术PSS排除、三scope过滤、同名版本stale、伪citation、reference_only／标准缺adoption、有限composition、历史冻结和澄清。这些可以保留为legacy回归；本次仅阅读，没有重跑或追认为canonical通过。

| 新增契约／集成验证 | 核心断言 |
| --- | --- |
| scope候选池 | fullset／tender／contract的实际`/index-canonical` projections按selected集合构成；范围外高分chunk不能占用candidate／topK；相同集合可共key，不同模块／集合不可串用。 |
| canonical opt-in／失败 | 启用时legacy chunk／vector／embed方法没有调用；未注册、stale、superseded、错误module、原件变化、损坏seal均在chat前拒绝，不回退。 |
| rebuild／status | 完整receipt／signature／recipe／current绑定；scope切换、Java重启、Pythonrecipe变化、新增eligible source使相应状态unready；旧Qdrant正常但canonical失败不能ready。 |
| sourceUnit与输入 | 表组／父条／caption全members进prompt和pointer；unknown reason保留；预算超限以完整unit记录遗漏，不静默截断或赋member rerank分。 |
| 同Session adoption context | legacy表预置冲突文字也不能进入prompt；追加canonicalcontext在当前scope，使用相同revision／closure；不同项目、旧revision／out-of-scope拒绝。 |
| applicability | 无composition不因allMatch空集为true；null／unknown不放行project standard conclusion；标准真实reference解释可答，事实／目录alone、假citation和不相关tender不能造义务。 |
| citation／history | ask输出与sourcesJson／history全部canonicalpointers和unknown深度一致；新parse／adoption后历史不重链，新资产拒绝旧binding时UI显示真实失效。 |
| clarification／Q11 | 同project／scope、bounded用户background保留；旧消息不进条款索引，回复使用新current候选池；审查引用线索变化提示准确。 |
| 问答期间变更 | retrieval／LLM之间改变绑定或增删当前scope document，不持久化grounded旧回答；只变动排除scope来源不会使其他scope无故失效。 |
| 前端scope／assets | rebuild与status传当前scope，异步切换不串状态，当前和历史正确完整图／表；不存在pointer时显示诚实状态，不猜原图。 |

实现和隔离Java／Python契约通过之后，仍需真实当前project document ID → canonical binding → advisory模块BGE检索 →选定chat模型→正确citations／原图→前端独立验收。源码设计、mock、离线保存response重放、真实模型调用、原件语义及H800发布分别记录；本文没有完成其中任何新增在线验收。
