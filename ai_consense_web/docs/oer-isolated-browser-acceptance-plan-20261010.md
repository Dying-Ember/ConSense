# OER 真实隔离浏览器业务验收路径

2026-10-10。本文仅记录只读核查和后续操作方案；本轮没有启动、停止或替换服务，没有修改已冻结前端源码或共享 HANDOFF，没有发起模型、上传、索引或业务写入。当前规范仍是服务端 `docs/oer-scope-and-rules.md`。

## 当前可复用证据与实际缺口

| 证据 | 已核对内容 | 对浏览器验收的限制 |
| --- | --- | --- |
| GCT reviewed projection | `04 - GCT.pdf` 原字节 SHA `ca3944c16890cb5ea7f8b24e2fab613c7ffd621809f7f850529901649e6f15f8`；仅 page 1 的24个采用区域；开发 projection `ba9fa8d8f5ab6be94abe9634a431f5bab6ee104bf9b2cf3472064a35b4dc9674`。 | `documentId=development-gct-ca3944c16890cb5e`，不是真实 Java ID；必须以隔离库的实际 ID 重投影。第二页未采用。 |
| 真实 Java→Python GCT bridge | `.local/state/oer-java-python-bridge-20261010/run-20261009T170318Z-99aa3c/bridge-receipt.json`；GCT3 rank 1、12 members、544,413 bytes 原页图；projection `94a4a72534da8665302c73faf14ef8d63f58b8a6e05e97e5f0da7bf745027045`。 | UUID 内存 H2、事务回滚；`documentId=565527031` 只在该次数据库内有效。Controller 是 MockMvc，没有持久 Java HTTP 项目。保存的 `actual-evidence-pointer.json` 还缺当前必需 `addressKind`，不能拿保存 JSON 注入前端。 |
| SL reviewed projection | 原件 SHA `6331c283da806846a06644814fa2271182314dbc2235c711a16e8492faaa0492`；`a153a4c69c44d5c761fb9ac4381fc07c45f0b2d744331daf2a9a2fb82da4f8e5`，15 chunks／17资产声明。`sl-amr-three-page-table` 属于 `ELE36.6.010.9`，53 cells，物理页3436、3437、3438，tableGroup `sl-amr-part-ii-documents`，该表3张完整原页图。 | 原 projection 的 ID 是 `development-sl-amr-6331c283da806846`。目前有真实 Python 检索／原图证据，还没有这组三页对应的持久实际 Java来源及业务引用。仅采用这些 reviewed units，不代表整个4300页原件被接受。 |
| 前端 scope v4 | `.local/state/oer-canonical-frontend-20261010/scope-v4-20261010012935536/validation-receipt.json` SHA `548c9215440ca813f75c82e3ea0dd7b0f6365dd5df84a79c1680caa64af188c5`；43 Node tests、9 profile场景、typecheck、隔离bundle通过。 | 是源码／契约／隔离打包；不是实际业务HTTP或浏览器验收。后续应使用该独立bundle或相同冻结源码，不覆盖在线dist。 |

只读监听快照：5173前端、8080 Java及8868 OER在监听；5174、18081、8869未监听。8868 `/health` 返回 targetModule=vetting、indexSignatureVersion=4、CPU／FP32配方。此快照不是新检索或模型质量验证。现有 `scripts/oer-local.ps1` 只有 retrieval/raw-vlm入口，未提供 Advisory 独立进程选项；不能假设直接执行该脚本就已配置 Advisory。

## 最小隔离准备

下面是待执行方案，没有在本轮运行。建议新验收目录保存复制 checkout、file H2、上传副本、Python状态、所有原始HTTP回包、截图和收据。端口在实际启动前重新确认。

1. **Java实际HTTP实例**使用冻结的复制checkout及独立target，建议绑定 `127.0.0.1:18081`。仓库已有 runtime H2和 `application-h2.yml`；可用 file H2隔离库，显式设置 `CONSENSE_H2_JDBC_URL` 与 `CONSENSE_STORAGE_ROOT` 到本次目录。H2 profile的Flyway关闭、Hibernate update只支持此隔离验收，不追认为MySQL V14迁移通过。配置LLM使用真实MiniMax中继／已有profile，凭据仍从本机配置读取，不放验收文档或HAR。
2. **Canonical opt-in配置**须另行显式提供，`application-oer.yml`只规定Vetting配方，不能单靠该profile启用业务canonical。`consense.oer-evidence.base-url`非空才使 `OerBusinessEvidence.enabled()` 成立。例：

   ```yaml
   consense:
     oer-evidence:
       base-url: http://127.0.0.1:8868
       retrieval-base-urls:
         vetting: http://127.0.0.1:8868
         advisory: http://127.0.0.1:8869
   ```

   base-url负责snapshot／assets；检索按module选URL。8869必须是真实 `oer_server.py`、`CONSENSE_OER_TARGET_MODULE=advisory` 的独立进程、独立 `CONSENSE_RETRIEVAL_DATA`；不能把vetting进程URL写成advisory后期待自动兼容。Advisory健康配方／scope与canonical client一致。两个registry均需存在新实际document ID的sealed projections及全部immutable资产；只共享／复制核SHA的canonical／artifacts，不共享Qdrant可写库。CPU FP32及现有512/64/2048配方先保持一致，此次不同时做GPU／精度消融。
3. **实际数据**在隔离Java建立项目A，并另建空项目B用于切换检查。使用真实GCT与完整SL容器的受控副本，执行实际Java native parser，保存真实 `SourceDocument`、原字节SHA、Java parsed sourceHash、scope／role／OER身份。SL原件现为47,975,184 bytes，在120MB上传上限内；4300页解析耗时仍需单独记录。不能用虚构parse或拷贝旧H2 ID绕过来源核验。
4. **重投影与内部登记**沿用GCT／SL既有raw bundle及reviewed adoption，仅替换明确document ID，产生新projection身份。通过真实Python `/project`发布，再由Java内部 `CanonicalEvidenceService.registerReviewed`核验并持久登记。该方法没有公共HTTP注册端点，不能让浏览器提交任意snapshot。登记期待包含新projection SHA、原字节SHA、Java parsed hash、sourceRevision、scopePolicy、adoptionManifest、reviewReference SHA及module；明确vetting/advisory eligibility。保留旧projection。
5. **真实业务数据**通过实际Vetting／Advice任务生成，不能写fixture Findings／ChatMessage或拦截浏览器网络替代。没有实际finding时Q11需记为该数据未触发入口；没有可点击citation时原图UI链路未通过，不能注入假citation宣称通过。SL仅参考库时允许reference_only／条件不明，不能为方便验收虚构项目采用关系。
6. **独立前端origin**建议 `http://127.0.0.1:5174`，使用v4隔离bundle；Vite preview支持现有 `/api` proxy，须令该新进程的 `CONSENSE_API_TARGET=http://127.0.0.1:18081`，不沿用默认8080。必须核对请求实际落在隔离Java，而非在线接口。最短页面是 `/#/vetting` 和 `/#/advice`；路由名是advice，不是advisory。

## 准确业务接口与组件必需数据

所有浏览器API以下列独立origin同源 `/api` 请求；Python端点是Java内部依赖，浏览器不直连、不需要模型密钥。

| 用途 | 实际路由／必需返回 |
| --- | --- |
| 项目 | `GET /api/projects`，`POST /api/projects`；新建最少提供id及nameZhHans。当前选择在 `store.activeProjectId`，不由hash path携带。 |
| 实际上传 | `POST /api/vetting/{projectId}/package/upload` multipart `files`，可显式 `sourceRole`、`documentScope`。GCT=tender/project_document；SL=standard/reference_standard。明确身份还要符合 `OerSourceMetadata`；不以文件名覆盖注册校验。 |
| 实际审查 | `POST /api/vetting/{projectId}/runs?lang=zh-Hans`，`GET .../runs/{id}`或`runs/latest`，`GET .../findings`、`metrics`、`files`。保存真实run／模型身份／source snapshot，不追认MockMvc为这些接口实际调用。 |
| 发现证据 | `GET /api/vetting/{projectId}/findings/{code}/evidence`；真实 `items[]` 的documentId、sourceHash、fileKey／fileName、pageNo／anchor、literal quote／text、located以及可选单个canonicalEvidence。`VettingView.evidenceItems`透传pointer，在原文后用 `CanonicalEvidencePanel`展示。 |
| 完整原件回跳 | `GET /api/vetting/{projectId}/sources/{documentId}/original?sourceHash={javaParsedSourceHash}`。Vetting仅在pageNo匹配 `^P(\d+)$` 且文件为PDF时追加 `#page={该物理页}`。跨页 `P3436–P3438` 不匹配，当前按钮打开原件但不自动定位首表页；需在验收中记录该边界。hash仍是Java parsed revision，不能换canonicalSourceHash。 |
| Advisory候选范围 | `GET /api/advice/{projectId}/sources?scope=fullset|tender|contract` 返回documentId、sourceHash、role、parseStatus、included及exclusionReason。当前实现GCT属于tender，contract排除；SL不属于tender，contract包含。fullset同时包含二者。未登记selected document会使整个selected canonical corpus失败，不静默选另一来源。 |
| 范围索引 | `GET .../index/status?scope=...`，`POST .../index/rebuild?scope=...`；evidenceBackend=canonical、sourceScope、indexKey／signature／reason及ready字段由当前binding＋health＋receipt决定。不是旧vector计数。 |
| 真正咨询 | `POST /api/advice/{projectId}/ask` JSON `{question,scope,replyToMessageId?}`；浏览器发送点击时LLM profile header。真实AskResponse／历史 `GET .../messages`返回grounded／basisScope／sourceScope／modelIdentity、citations及sources。每个Citation需literal content、fileLabel／anchor／pageNo、scoreMeaning、canonicalSourceUnit及全部canonicalEvidence成员列表。 |
| Canonical pointer | `GET /api/evidence/{projectId}/documents/{documentId}/canonical/{projectionSha256}/units/{unitId}?module=vetting|advisory`；地址含 `addressKind=canonical_adoption_unit`、project／actual document／module、unitId／canonicalChunkId、六个SHA、原sourceFilename、sourceOrder整数或null。返回还须包含physicalPages、parts、tableGroup、assetRefs、sourceSpans／relations／adoption／quality／unknowns及humanConfirmed=false。 |
| 图像资产 | `GET /api/evidence/{projectId}/documents/{documentId}/canonical/{projectionSha256}/assets/{assetId}?module=...`；header source／projection SHA、严格ETag、MIME以及实际bytes／magic／SHA均匹配；浏览器核完bytes才创建Blob URL。完整跨页组不只取seed图。 |
| Java内部真实Python | `/health`，`POST /project`，`POST /index-canonical`，`POST /retrieve`，`GET /canonical/{projection}?module=...`及`GET /canonical/{projection}/sources/{sourceSha}/assets/{assetId}?module=...`。保存实际request journal、sourceUnits closure、pair tokens及原图SHA；浏览器网络mock不构成该链路。 |

共享panel目前展示原页bbox数值、完整原页图和literal结构文字；没有crop overlay或点击文字自动滚到图像区域的实现。Advisory有citation→完整成员panel，当前没有Vetting式打开原件按钮。跨页canonical view由Java形成 `P首–P末`，Vetting的单页fragment规则不会自动跳到该跨页首表页。验收应分别记录已支持的citation/pointer回跳、Vetting单页完整原件页跳和这些UI边界，不能声称两模块都有精准bbox overlay或跨页PDF自动定位。

## 最短真实操作与通过条件

1. 打开独立 `/#/advice`，选项目A，scope=fullset，核sources含真实GCT／SL、无Drafting标准模板／计价／SL非技术内容；刷新证据索引。浏览器Network必须是5174→18081，后端必须产生实际advisory index receipt。
2. 问真实GCT开发问题：`What happens if the tender booklet is a photocopy rather than the original version?`。点击回答的实际引用；核当前module=advisory、GCT3完整12-member closure、原page1文字与544,413-byte图。法律答案／限定条件另记LLM结果，不能以rank1代替。
3. 问真实SL问题：`AMR checklist 的 Meter room/box layout 要展示哪些内容？如果只取附件 P.1 上的两条说明会漏掉什么？`。点击实际引用，核 `sl-amr-three-page-table` 的3436／3437／3438三页图按物理页顺序返回，表53 cells、row3／7续行、Remarks a–c与独立表外instruction可查；sourceUnits必要成员完整。保留selection／applicability unknown，不能从空框推断采用。没有实际Citation就如实记录未完成。
4. 打开独立 `/#/vetting`，实际运行审查，打开具有canonical证据的真实finding。核literal quote、全部原图／表组；点“打开原件”核原文件，单页P1需跳页，跨页表组当前只有原件打开及panel三图，自动首表页定位记为缺口。点“带入合约咨询”：同origin sessionStorage存问题、literal quotes及source identities，进入 `/#/advice?review={opaque token}`后一次性消费并移除query；无自动ask／rebuild。问题可编辑，明确新点击提交后才独立检索。未保存审阅／checklist修改、证据未加载等既有门控按真实UI核对。
5. 在scope=fullset发起真实索引／ask请求后立即切contract→fullset；再从项目A切到空项目B。捕获确实未完成的请求，旧完成不得恢复旧引用／图像／ready状态，不得清新busy或通知旧成功；B不能展示A的图。若本次真实请求过快，没有产生迟到条件，race UI项记为未观察到，源码测试结果仍独立保留，不能人工mock延迟后称真实模型验收。
6. **只对隔离受控来源副本**执行一次可恢复的来源变化，保存新旧SHA／revision／routing。原件不改；通过正常替换上传或受控独立副本变化触发当前reader。历史消息仍保存旧pointer；重新打开应返回对应409 stale或404binding unavailable，原地址不换新projection，零新资产重链。当前索引/ask失败或要求新登记；真实长任务期间更改必须不保存旧证据为新成功回答。未执行真实变化时该项不能借mock门控追认。

每个case保留业务request／response、Java↔Python journal、所用project／document／projection／original／parsed身份、浏览器截图和全部asset SHA／bytes。截图展示实际文件名／物理页／unknown状态，HAR须排除Authorization和中继token。最终分开报告源码43 tests、H2实际HTTP、真实模型检索／LLM回答、原件／表组质量及浏览器可见结果。全库质量、MySQL迁移和H800部署仍独立待验。

## 本轮只读依据

已查源代码：router／Vite config、AdviceController、VettingController、CanonicalEvidenceController、ProjectDocumentScope、CanonicalEvidenceService／Properties、OerBusinessEvidence／CanonicalRetrievalClient、VettingView／AdviceView、reviewHandoff及oer_server。已查上述原始收据、registry sealed JSON和SL原件文件大小；没有以authored fixture替代真实GCT／SL。

相关实施记录：服务端 `docs/testing/oer-real-java-python-bridge-validation-20261010.md`、`oer-canonical-java-binding-and-assets-20261010.md`，前端 `docs/canonical-evidence-panel.md`。本文是验收路径设计，当前所有“后续操作”的实际执行状态均为未运行。
