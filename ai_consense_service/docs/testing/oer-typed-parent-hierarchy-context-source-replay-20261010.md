# Typed parent 层级上下文的复制实现与源重放

日期：2026-10-10，Asia/Shanghai。继承 [范围规则](../oer-scope-and-rules.md) 和 [首次标题／正文缺口诊断](./oer-official-q16-title-body-closure-diagnosis-20261010.md)。root 审查局部门控、完整 diff 与真实 tokenizer 证据后，已授权并完成 frozen `fixed-local` 的三份产品文件与两份新契约测试精确落主，**当前状态为已落主但未激活**。复制代码的纯契约、实际来源重放和本地 tokenizer 预算分别保留原收据；本代理未切运行服务、未重新 index／retrieve／调用 LLM，未读取官方答案或首轮模型答复。

## 冻结对象与实际接缝

复制工作根：`.local/state/oer-typed-parent-context-closure-20261010/run-235240-sourceonly/`。`baseline/`保留原 132 frozen 文件；`fixed/`是首版 source-wide gate 的历史绿色复制实现；**批准并落主的冻结来源是 `fixed-local/`**，由 root review 发现范围过宽后只收窄错误依赖传播。两版 source-only 收据、失败与 fixture 均保留，未追改旧状态。

| 冻结来源与落主 bytes 相等的文件 | SHA-256 |
| --- | --- |
| `fixed-local/retrieval_source_units.py` | `a1fd7096a108bfaa71c51ac6b79ead7a255f0de940986e681fede25e4ccac3a9` |
| `fixed-local/structural_retrieval.py` | `24ecff9ab4f1327a0178f08dce919ffa766cb279d2aabfd7862ac11cc8e619e6` |
| `fixed-local/window_runtime.py` | `0db2f52775f06dedaf1d6d002e674c851d651479376b35aeb20151aea6117892` |
| `fixed-local/test_typed_parent_hierarchy_context.py` | `1e3c4ae986c6070c117e5270afd2bc1623c4d9e4a08fc736c6d83a687849c770` |
| `fixed-local/test_typed_parent_runtime.py` | `4029f4b785b3c4928d2f350b1cf000b1d98787626c1729d2ac2a84154f08e2b0` |

相对 baseline 的完整 diff 为 `local-freeze/copied-source-full.diff`，SHA `644861a32066f5b8bd02bc41361b7d3ea0dfeb7010cf332ca4853cfb40b13075`；首版到当前局部门控的差分为 `local-freeze/copied-source-locality-delta.diff`，SHA `ac33a812af72a4322edbae502732da72ccd46be52c89166e12df57e91920b5d9`。新增两份 authored pure tests 为 `test_typed_parent_hierarchy_context.py` 与 `test_typed_parent_runtime.py`，不是用官方题目／答案制作的检索特判。

## 五文件精确落主收据

应用于主目录 `ai_consense_service/tools/vetting_eval/`。应用前逐文件确认三份主产品与 `baseline/` bytes 完全相等，两份新测试在主目录不存在；保存旧字节和五文件 diff 后，再次核对全部目标才写入。应用后三产品／两测试的 bytes 与上述冻结來源逐一完全相等，baseline 与冻结收据／两份历史 diff 的 SHA 均未改变。

本机收据目录为工作根下 `main-apply-local-20261010T001049Z-172a8178/`：

| 本次应用证据 | SHA-256 |
| --- | --- |
| `apply-receipt.json` | `dde4da8aeacbf8737b99c104d664674315a11d751e74623add8aad386ef9ef14` |
| `preflight-receipt.json` | `cd385f77173b3d6210a27270cbc3659520a302caa41223e00d99c7b5ca14f94c` |
| `application-five-file.diff` | `05c5f7e6f15d2b90d0711f29f18b01c31902c7c1421bc961ac51fa61dbc71b20` |

`before/`保存三份旧主产品，以及本文应用前 bytes（文档 SHA `2b9ebdcbf7ee8776df7bf8deaadfbfb9f80a0a38239cf9e1ce494ae330782dd2`）；两份测试原不存在，状态明确记在收据中。应用前后分支仍 `main`，HEAD仍 `285fc2e3bdf97792aeae00a31dbde2c69ebcde60`。未提交／推送、未改分支或 ref、未改运行 copy／服务、模型或索引；没有因为落主重新执行模型或业务请求。root 后续另行决定服务激活、新身份 index 和真实业务验收，本文不追认这些步骤已经通过。

历史冻结收据中的 `mainSourceEditedByThisAgent=false` 是本次应用前状态，原收据不回写；本次 `apply-receipt.json` 的 `status=source_applied_not_activated` 记录之后的事实。冻结的228项纯契约与真实 tokenizer 证据仍归属原复制 snapshot，落主用逐文件 bytes 等价核验，没有把历史测试改称在线验收。

原 exact observed unit 的 `unitId`、`kind`、`members`、`requiredMemberIds`、`blockIds`、`observedRanges` 等字段仍按原规则计算和保留。只有 native PDF 有明确跨 whole-unit parent 声明时才加 `hierarchyContext`，其 context identity 与旧 unit identity 分开。`structural_retrieval._catalog` **实际用 context members 的全部 parts 重建 structure embedding／rerank 输入**；不是仅附加元数据而仍只喂标题。已保存的完整来源 payload／页位／图片、表格定位／unknown 不重写。

SourceUnits episode policy 为 `unique-source-structural-unit-seeds-typed-parent-context-v2`；hierarchy policy 为 `same-source-declared-typed-parent-descendant-context-v1`；structural recipe version 为 `observed-structure-dual-granularity-typed-parent-context-v2`。selection policy、recipe、源顺序和导入源码 SHA 一起绑定 signature 与 cache identity。新实现改变真实 embedding 输入，不能沿用旧 embedding compatibility receipt 宣称等价，也不能别名复用旧 fixed-cache／index；真实验收需要新 index 身份。

## Python／Java 冻结的字段契约

`hierarchyContext`包含：policy、contextId、status／reason、sourceIdentity、seedObservedUnitId、rootWholeUnitIds、requiredMemberIds、members、memberPayloadSha256、edgeDeclarations、blockIds、observedRanges、memberOrder，以及明确保留的 derivedMembersCanBecomeOrigins=false、memberScores=null、qualifiersComplete=unknown、semanticScopeVerified=false、legalClosureVerified=false。

sourceIdentity 是旧 8 个 scope 字段，再加 parent 原有的 originalSourceSha256、projectionRevisionSha256、sourceRevisionSha256、scopeSha256。端点按这份完整 scope 中的 exact canonical `unitId` 定位到 parent `id`；不能把旧 observed `unitId` digest 与原件 whole-unit ID 混用。后四个 SHA 要求 64 位小写十六进制。

members／requiredMemberIds 按 canonical 输入的实际源顺序，含原 exact members 和可信 parent descendants；完整原 JSON 不改变。memberPayloadSha256 是每个原 parent 的稳定 sorted JSON UTF-8 SHA。edgeDeclarations 按 edgeId lexical 排序，edgeId 是原 relation 完整 JSON 的同类 SHA，relation 原 JSON 完整保留。重复边原 payload 不同则 unknown。blockIds lexical；observedRanges 使用旧 block-range 校验。contextId 对以下 address 作同类 SHA：policy、status、reason、sourceIdentity、seedObservedUnitId、rootWholeUnitIds、requiredMemberIds、memberPayloadSha256、edgeIds。

稳定 JSON 定义为 Python `json.dumps(...,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False)` 的 UTF-8 bytes。当前全部 authored／actual fixtures 可在 Java 递归 sorted JSON 下对等；新数字表示兼容性另按具体 fixture 验证，不改变或放宽既有 hash。

只采用 type=parent、scope=between_whole_units、status=source_reviewed_declared、sourceReview／basis 非空且 humanConfirmed 是 boolean 的声明。true／false 都如实保留，不升级语义。from／toRegionId 必须位于对应原件 endpoint 的 adoption.regionIds。内部 parent 不入跨 whole-unit 图，合法内部 self-edge不当作跨 unit 环；无此类边与非 native 来源保持旧 exact 行为。不得按条号前缀／关键词猜层级，不造缺失的中间节点，不遍历 sidebar 或合同交叉引用作为法律闭包。

Java 对等实现单独在 ignored copy进行，须用同源图重新计算上述 fields／edgeIDs／member顺序／完整 payload／unknown，并实际由 OerBusinessEvidence 的正文、canonicalMembers 与引用 pointers 消费。不能只接收未知附加字段绕过 canonical 校验。此文不追认 Java、Advisory、Vetting 或浏览器已经部署；对应代理／真实运行另交付收据。

## root review 后的局部依赖门控

首版先验证整份同源图，将任一错误登记为 source-level unknown；故原 `disconnected_source_cycle` fixture 预期良好分支也 unknown。该方案能拒绝坏结构，但范围过宽：同源独立章节的 cycle 不应暂停完整合法章节。历史 225 项绿色表示通过当时的规则，不表示这个过宽规则已被接受。

新 locality replay 对同源两条断开分支建红／绿信号。旧 `fixed/` 的合法分支为 unknown／0 member，预期 red；`fixed-local/` 的合法分支 complete／3 members，坏 cycle 分支 unknown／0 member，green。当前算法对可定位的端点候选及明确端点／region owner记录 nodeErrors；多父标 child 与相关 parents，cycle 只标 DFS active path 中的 cycle nodes。context 沿已接受的 parent children 取 descendants，遇所依赖 nodeErrors 就 unknown。因此坏 descendant 会暂停其祖先，完全独立合法分支继续；错误声明无法由任何 endpoint／owner确定影响节点时才 scope-level unknown，不能用局部门控静默忽略不可定位依赖。

| locality 收据 | SHA-256 | 结果 |
| --- | --- | --- |
| `source-wide-locality-red-receipt.json` | `246bb7c396776289f3546d1993687f137a90a3f4c72962fa08f291b295f0264d` | 原 source-wide gate 红；good branch 被过宽暂停 |
| `local-dependency-green-receipt.json` | `9fde23c19d326186e9904877c1806a4cf9d19133f618e1e8276f6560e7a01fd0` | 独立良好 branch 继续；坏 cycle branch 仍 unknown |
| `local-freeze/java-context-contract-cases.json` | `3ed36127542fb1e5cf8f4cdc725d7f673d16340768644a1c64e22e1d873491f6` | 25 authored＋3 actual-source 的逐对象预期契约 |

schema／policy保持当前尚未发布的 v2 候选，源码 SHA 变化绑定新 signature。实际 GCC 无这些故障，因此当前 actual title context fixture与首版字节完全相同，均 SHA `f3f682f5ef42cd28be289984ac68b61616bb088c9dd5b3782ff3fcdc18558bfb`；没有通过 locality 调整改变现有原件关系或以答案补正文。

## 实际来源重放与模型输入预算

原实际 metadata SHA 固定为 `7e67db58cc193df3a616cb3fddcad91e84019467f09a4408772048d98908b9cc`；GCC sealed public SHA仍 `f0f42df3b3d96ed7a77620b9e5161fbbf5bbd9f1ec59b04122f75e34c09b7b85`。仍只采用原 PDF物理65–70页，GCC整体PARTIAL。实际2b SCC17的 partial／wholeSccAccepted=false／effective edit unknown不改变。

使用首次 captured candidate24 与全部真实分数／排序，original top3 seed selection逐对象相同；旧 exact unitId与 members不变。baseline标题结构输入是42 UTF-16 code units／1 part；当前 17.1 hierarchy context 是31 whole members（标题＋30正文）／65原 parts／21,621 UTF-16 code units。`local-freeze/fixed-green-receipt.json` SHA `32e59528e52e4bb475e9f38ead1cf1ea44843fa01ba72e03de8c349c0c242299`。这是实际原件观察上下文覆盖，不是最终答案或必要法律证据评分。

真实 tokenizer replay只加载已下载的 pinned本地 tokenizer，强制 offline、local_files_only=true、trust_remote_code=false，并拒绝 HTTP/connection请求；未加载神经模型或调用 forward。问题来自第一次 actual retrieve `request.json.query`，仅 question-only（两个 tokenizer下35 tokens，不含special tokens），未读最终答复／gold。完整 windows、原件跨度、各实际 input 的 text/query/IDs/mask SHA已保存。

| 本地真实 tokenizer | 完整未分窗输入 tokens | 实际窗数 | 最大完整窗输入 tokens | 当前 cap | 覆盖与截断 |
| --- | ---: | ---: | ---: | ---: | --- |
| BGE-M3 `5617a9f61b028005a4858fdac845db406aefb181`，context embedding含special tokens | 4,993 | 3 | 1,993 | 2,048 | 全部原件ranges；truncation=false |
| BGE reranker v2 m3 `953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e`，实际question＋context完整pair含special tokens | 5,030 | 3 | 2,030 | 2,048 | 全部原件ranges；truncation=false |

真实两个 tokenizer的 maxWindows=1都会抛 `WINDOW_COUNT_GUARD`，不返回静默遗漏tail的部分结果；真实reranker的超长query抛 `QUERY_TOKEN_BUDGET_EXHAUSTED`。这些是实际 tokenizer feature lists／纯窗口位置校验，**不是神经 forward、实际 tensor设备／dtype、模型排序质量或吞吐测量**。

real tokenizer收据：`local-freeze/real-tokenizer-budget/real-tokenizer-budget-receipt.json`，SHA `2c776723e575d8bb5e83de5e340bcd558a8dc3438c67f4c498cb8313d2d08055`；`actual-tokenizer-input-digests.json` SHA `72046b4f085bc124bb349aebfff5f70e0ba0fe18937bfeaeabebf4f4b76df409`。CharacterTokenizer旧收据 `authored-character-budget-receipt.json` SHA `2f90970b0c109082b4fe66fa43d666f5cb155727be2ac184dd83b8e72f74989e`保留为 authored budget契约（12／12窗），不追认为真实 BGE token数。

结构 long lane仍按sourceSpans的真实重叠映射原 canonical parent。它可命中本来就有short lane的正文 original parent；禁止伪造title overlap。`resolve`、`select`与same-pool `compare`不会因 context members自动增加 rerank候选／origins、不赋制造分数、不递归检索。若 Advisory同时选17与17.1祖先，其上下文正文可能重叠，需按来源身份／member pointer核最终 prompt去重与预算；不能据单个5,030 pair预算宣称整体prompt已验收。

## 纯回归、保留失败与未完成边界

原 native PDF／DOCX／collection／scanned wire和结构测试共175项在项目既有 retrieval venv通过，收据 `pure-contract-regression-retrieval-venv-receipt.json` SHA `848eb1341c270aabc6dd0cc0269dfd7e1a6dd966f762bf4e9fee3b7d7a257bd0`。加入真实 copied-source SHA／policy／recipe／cache／context-order 的 authored runtime检查后，首版225项通过，SHA `15cd5b1671eaa0fe076c3d20dc7983324768ad4647afc2add195c609f8b6a8d0`。当前局部门控含新独立分支／不可定位／祖先依赖案例 **228／228**通过，无failure/error/skip；`pure-contract-regression-local-receipt.json` SHA `fdc3e6d4b5e3449fe76be9f63f1dcf196ad0657d67e12c404ebc7b977c285d91`，完整log SHA `def7405ca9747fd946dd0cec4945b4711736528a80376bfa89a6021d81f6d399`。

初次扩大回归使用系统默认 Python缺pypdf，175项中19个nativePDF setup errors，原log/receipt保留，receipt SHA `6917c059b2da0a63471387744218d7f7a41aece69cd05f05d0237fd5d79cdbe6`。只改用既有 retrieval venv（pypdf6.19.0），未安装包／改产品来掩盖失败。扩展 fake-runtime首次要求真实算法SHA时误用了测试库原有mock算法map，独立断言失败；随后该测试明确调用真实 copied `WindowRuntime.algorithms`，其他 tokenizer/model/vector adapter仍 authored，未据mock map证明实际SHA。隔离runtime必须显式配置CONSENSE_WORKSPACE_ROOT；缺配置的首次测试导入失败也不归为产品结构错误。

最终 local复制冻结收据 `local-freeze/copy-source-freeze-receipt.json` SHA `fb566c6ec4798f27f4c4020f7c8d1982f05b89a606fa2a42ffab2040818a14b9`；首版原 132 frozen源 bytes／sealed public／metadata保留核验的收据 SHA `933f501541f88fa34a5e1c06a5ac8be1551ea9ab52421a65e5dd1c6ad9a1de85`。

本 Python 接缝的五文件已精确落主但未激活；当前未进行权重训练、真实新recipe的index／神经rank性能验收、Java上线、首轮／后续答复评分、Vetting真实结构化报告质量或前端业务验收。source-only结构覆盖、纯契约、真实tokenizer预算、主源码落地、模型forward、最终业务答复与部署继续分别报告。

## Window／dual 模式兼容的第二次候选冻结

root 复核发现上面的初版 `fixed-local` 无条件向 health／sourceUnits 声明 v2；window-only 的旧 baseline Java handshake 因此被拒绝。按实际检索模式区分声明和消费，在新 ignored copy `fixed-mode-compatible/` 完成局部修复，未改已经冻结的 `fixed-local/` 或主源码。此候选当前是 **已冻结复制版本、未落主、未激活**，等待 root 独立 review；上面的五文件落主事实不代表本节候选已应用。

`select_source_units(..., *, hierarchy_context=True)` 默认保留 v2，新增参数必须是实际 boolean，0／1、字符串、None、list、dict 不能按 truthy 自动转换。window runtime 显式传 false，直接使用旧 `_resolve_observed`，episode／health 声明 `unique-source-structural-unit-seeds-v1`，不附 `hierarchyContext`／`hierarchyContextPolicy`；dual 显式 true，保持 v2 和完整 context。正常 retrieve 和 emptyScope 两处都传实际 mode，ranking event 使用返回 episode 的实际 policy，signature／embedding cache identity 显式绑定该 mode。分数、origins、source payload、源顺序、旧 exact unit identity、原件 quality／unknown 不变；未修改 `structural_retrieval.py` 或 typed graph 算法。

新候选共135份顶层 Python／samples 源码，旧 `fixed-local/` 的134份中132份逐字节不变；只改两份产品，新增一份模式测试。完整三文件差分 `mode-freeze-final/fixed-local-to-mode-compatible-three-file.diff` SHA `25d7083d1578c0f68ad5004efa4a5ee756e1650dec89ffe238339a712fe68d72`；最终收据 `mode-freeze-final/mode-compatible-freeze-receipt.json` SHA `cdcd90eab2302cd4491dec2b8ffeb3e2574523597112b5a3bf9a23785445121a`，同目录保存完整 candidate manifest 及旧 copy 的再次复验 manifest。冻结时再次核对主目录五文件仍等于上面的 `fixed-local` SHA、新模式测试尚不存在，main／HEAD未移动，旧收据／tokenizer evidence／fixture／diff保留。

| 新候选文件 | SHA-256 |
| --- | --- |
| `fixed-mode-compatible/retrieval_source_units.py` | `4f2c5ddabd2026227a272dd6e6209a0c2f144e7e1f7bbdad175783248aa3f59f` |
| `fixed-mode-compatible/window_runtime.py` | `d855bb8b61ca308b6c1cc8a23a46d422c7e5d6f13c4740b5ad6bde16f1cda825` |
| `fixed-mode-compatible/test_typed_parent_mode_compatibility.py` | `ae5a9c815576c8659f876eb1b230fbe4a0585390602e72445a369699ebdad551` |

先在旧产品上执行11个 distinct 聚焦案例，得到3 failures／11 errors（errors包含7个非bool subtest），red收据 SHA `c50dede1029f3c5f221d1d4e81727d2b576fe2d65191bd884cc1c89a7359125a`；保留失败，缺少新参数／signature字段是预期信号。修复后同11／11通过，收据 SHA `a75dc0c36cd8d5bfb1196665121151995e9588bba2559d8757f39867b5ce06df`。随后单轮相关回归 **258／258 distinct**、0failure／error／skip，`mode-compatibility-related-green-receipt.json` SHA `6d6c5306c21f8fb241de7ba5ff11e0ff11a720ff05a4c30e4f2fbbe869bba71e`，完整log SHA `7adecf1c688148562c7f07717622ec557fba90ceeefe47647e56de42256d6e23`。这些分母互相重叠，不相加为正确率；仅 CharacterTokenizer／FakeModel／FakeDb，真实tokenizer／神经forward／网络／liveDB均0。

Python／Java共享模式文件为 `mode-freeze-v2/python-java-mode-fixtures.json`，4,256,545 bytes，SHA `7beec767e60af8626d67e8e8f757553a4c9f1e6cb9d9398583ef27b9daa22aed`；receipt SHA `dad35f999ae1838709fe6d56b1d36cc3b23b94e1f160d6a5fb9f3dcff2cc9f04`。四组分别是 ordinary SCC 的 windows／dual，以及 typed GCC 的 windows／dual，包含实际 Python health、实际 selection episode 与原50／22个 canonical parent。signature实际重算，但 indexResponse 是明确 authored envelope，vectorPoints 不是实际 indexing 点数，.75分数不是模型排序。Java对等代理已报告4 health＋4 canonical episode独立重算、原72parent逐对象相等，以及2 actualhealth＋明确 authored普通legacychunks 的 Java mock HTTP client 接缝通过；实际 SCC native payload不能追认为legacy normalized chunk接纳，Java最终收据另行维护。

首次共享fixture冻结曾直接比较 pretty JSON 原bytes而失败，旧 metadata parent保持 Pydantic key插入顺序，新输入来自 sorted public快照，故新pretty SHA `bef9e055b3e3323e582c6e784aedd4b7209f75fad32665ca2a9abf1ae2f512da` 与旧 `f3f682f5ef42cd28be289984ac68b61616bb088c9dd5b3782ff3fcdc18558bfb` 不同。两个对象及每个数组顺序完全相等，stable sorted JSON SHA均为 `a5ae1850f3bcfcd790b329849ab880ff8d809605fb57e2ff8098a9837233114d`；没有改产品来掩盖序列化失败。首个工件保留在 `mode-freeze/`，解释在 `mode-freeze-v2/first-raw-key-order-comparison-failure.json`。v2只在逐对象完全相等后复制旧 frozen bytes为 `actual-first-title-context-preserved.json`，不声称新pretty序列化bytes与旧相同；contextId、原分数、origins和完整source payload保持不变。

本节不代表服务激活、新模型输入索引、真实召回、权重微调或官方业务报告通过。新runtime准备必须在 root review／实际源码应用之后重建 code freeze，使用新隔离状态与新index／cache身份；不能沿用旧embedding compatibility。仍不改旧8874／8875、实际Vetting job、8080／5173或Bonsai抑制状态，不读官方答案／gold，不重复业务请求。

### 第二候选随后精确落主，运行仍未激活

root 随后完成独立 review，verdict SHA `647d15cef1d2db4d6eff0f6ebedb5a51cae382ed236dc17818945eaaab83a9ed`，并将本节 Python三文件与对应Java16文件逐字节应用到主目录。应用收据 `.local/state/oer-java-policy-compatibility-20261010/run-101510-sourceonly/root-apply-20261010T003735085746Z/apply-receipt.json` SHA `c55ab3eb59fb81f0d12159792f173d3fb37ee145fb864a1b346b081dccb71909`，状态 `source_applied_not_built_not_activated`，HEAD仍 `285fc2e3bdf97792aeae00a31dbde2c69ebcde60`。Python最终源码等于本节三候选SHA。旧freeze中main尚未应用的状态按当时事实保留，不回写收据。

按 root 后续指示，开始只准备8876／Advisory及8877／Vetting的全新运行目录，明确复制本节冻结 `fixed-mode-compatible/` 全135源码而非从未验证的main盲拷贝，并绑定 `mode-freeze-final/candidate-source-manifest.json` SHA `ee53c7dce5d45dd74d76bbee67633f79891f516b37afcb08aaad08c9478396c4`。此准备未启动服务、未执行模型／index，运行准备结果和启动门控另交root复核；源码落主与前端／真实题报告验收继续分开。

### 新8876／8877的实际 readiness，仅GET

上述准备随后完成于 `.local/state/oer-typed-context-v2-actual-preparation-20261010/run-003755-eb79bf/`，`prepare-receipt.json` SHA `ce36ec04d3756307eed70ad9f6f34cf0e7c94c873d1a5ff5a6b905a70d8a71c8`。每个运行目录复制原788个CAS对象、694,530,617bytes及4份immutable登记，两份来源每个runtime仍是GCC50＋SCC22，源顺序、完整raw parents、roles、quality和unknown保持；fresh projects／run-evidence为空、未复制旧qdrant／index／fixed cache／embedding compatibility。135源码manifest `code-source-manifest.json` SHA `c2022d0245417adff1f21c8af82b623d33ec25215dddf62e564d2e3aaff7096a`，全31实际algorithm hashes绑定新代码，不追认旧index等价。

早期 `run-002122-425426/failure-receipt.json` SHA `c7688cb21b3734d5e6a39681645a25efc66b239ff8645baae5e86a0e1d5b0868`及当时三份draft源码均保留。它失败于inventory JSON key用`/`、旧Windows清单用`\`导致清单SHA不同；新run对每个actual对象SHA和size逐值核对旧 `actual-cas-copy-manifest.json`，均相等。旧Windows清单SHA `8fa020d98a10694b5cbf93e369704727f0f0371f9191dbe3265e372ad5ef9283`，正斜杠规范化表示SHA `8cb1d68760bf818b0be15ca425e3f68da88021cfb34667fcb6c0e568d7af3031`，两者可逆转换且对象值完全一致。说明收据 `historical-cas-key-serialization-reconciliation.json` SHA `a814748f0f88c8476276c955a6a63e43be3aa80500d80666960c85e8e85df7e6`；没有放宽原件字节门控或追改旧失败。

root 独立读取prepare收据、launch脚本及manifest后给出限定GO `root-typed-context-v2-runtime-ready-20261010`，仅启动两个新服务、health与两源完整GET，未授权index／retrieve／模型或Java切换。以下manifest逐字绑定此次启动：

| 新运行身份 | Runtime manifest SHA-256 | 本次owned进程 |
| --- | --- | --- |
| `127.0.0.1:8876`／Advisory | `6e5410335c252346ef92dc0c4589a6026234aac9b7adcd1ca7316230e8b55180` | wrapper91992／listener115768 |
| `127.0.0.1:8877`／Vetting | `809a599bafaa1b4e615d0d273d984e4b1539e509eba590691561daa4b1994b46` | wrapper92016／listener111084 |

启动使用冻结的 `launch-prepared.ps1`（SHA `d05ee6758f98ad0636b2745b17a9a533f038127e704eb87b55271edde8a50bc4`），先核无占用新port及无旧启动handle，顺序启动、不重发启动；child仅安全system allowlist＋显式非secret runtime变量，其他继承变量均在child移除，MiniMax／API key名称拒绝进入child。`activation-ready-004302-5b8f9a/activation-receipt.json` SHA `85d062b0fc20f2de497a352ac9eb5ba28da8357cf664e8508de184e94c80870d`，保存新listener的script／executable／SHA／PID／parentPID／CreationUtcTicks，绑定对应process receipt。各次前后保护5173／8080／8092／8868／8871／8873／8874／8875／18083的已存在listeners，统一使用CIM `Win32_Process.CreationDate` UTC ticks，未混用Get-Process时间精度，也未stop旧进程；这些PID是本次捕获身份，后续操作必须重新核验，不能只凭旧PID停进程。

最终 `readiness-004625-4467a2/readiness-receipt.json` SHA `f656c03fc0cbcd22c426ad4655885961aa2512589a248f57e996672310ff9904`，状态 `ready_get_only_no_index_query_model`。4份canonical GET原bytes／headers／SHA均保存，两runtime中两源wire分别与原冻结public bytes完全一致，positive模块门控保持。另4次health GET（各runtime前／后）显示embedding／reranker／OCR loaded=false，CPU／FP32／batch2，short512／overlap64、dual long2048，v2selection／hierarchy policy、recipe及全31algorithm SHA相等。全部源／模型权重／index状态均按实际捕获核验：projects／run-evidence为空、qdrant不存在，index／query／LLM／模型weight load／forward均0，旧与新owned listener身份前后相同。GCC GET各18.651／17.793秒、SCC各10.208／11.110秒只是本次完整cold registry回取，不作为神经推理／最终业务延迟。

原draft `replay-source-only.py`保留；它使用flat retrieval health字段，与实际 `retrieval.sourceWindows`不符。后继 `replay-source-only-v2.py`只修这个接缝，加入sealed readiness SHA／对应runtime身份及新GO门控，默认仍dry-run，并明确拒绝把上述readiness-only GO用于neural index／retrieve。两module dry-run已完成、无HTTP；默认50 candidates／10 units、只用同原 `request.query`（query SHA `ad21c77cdca0c26bae2872b385441414224c14d40f4f668fa4f1913ca4dba0ad`，source request SHA `6cf6690b2f1c2dfa91dd0170573fb89b548fff259389aec7bfaa09245dd13a9c`），不读gold／官方答案或首轮LLM正文。后继脚本尚未实际执行，不作为新index、神经rank或模型答复证明；未使用的`launch-reviewed.ps1`只是额外身份wrapper草稿，本次没有用它代替root已review的启动脚本。

本次readiness只能证明新服务与完整原件wire／声明接缝 ready。GCC仍只采用物理65–70页，整件PARTIAL；实际SCC17仍partial、wholeSccAccepted=false／effective edit unknown。尚未新index／BGE query、Java切换、官方答案评分、Vetting报告质量或前端验收。后续操作必须由root另行review／GO并使用新的index身份，不把此限定GET-only授权扩成模型测试。
