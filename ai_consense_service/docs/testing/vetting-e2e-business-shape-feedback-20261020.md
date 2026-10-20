# Vetting Mini07 业务字段失败与有界反馈候选

状态：Mini07 真实运行 **FAILED**，原始输出保持；后继业务字段反馈修复完成离线 RED、13类217/217 和正式09后继54 suites870/870。Root安装09后，Mini08真实触发一次业务形状修复，完整 raw／理由／请求／来源／预算绑定核验通过，但模型修复返回 `[]`、最终0发现，已知两份／三份副本冲突未保住，**业务验收仍失败**。候选首版、正式首编译失败及后继成功、实际 Mini07／Mini08 分别保留，不能用测试或 COMPLETED 抹去失败。

## 同源实际 Mini07

Round07 JAR `72d818409d1bcded40a5e44cb9166af24d513636cd3bb02c46d8a803142214b3` 下，仅启动一次 run `05f6ccc0-bfe5-4281-a0cd-661fef576d9f`。项目、原件与来源快照均与 Mini06 相同；source snapshot 为 `730661c6431068f7c73a9c3e2cdbd74a7779b3148e4d3dccbf2726afb98a44fc`，原9段受控 E2E DOCX 为 `121fe9d141e684decffbbff870a62203146047f2b1be223d317483ae96821b9f`。

2026-10-20 04:07:30–04:11:33（Asia/Shanghai）执行17个主题、17个 packet、17次 generation，无 attempt2。15个主题返回 `[]`，topic8与topic17分别返回3条结构合法记录，但整 topic 未通过原有业务字段校验，run 最终 FAILED、reviewCompleteness=incomplete。终态 findings GET 为0；没有报告 snapshot，三格式导出与快照稳定性本次均未执行。没有追加 POST、上传、重解析、人工 provider retry、人工 finding 写入或在途重启。

逐 actual attempt 的 input/hash、own input budget 和 source pin 离线复算全部通过；17包的完整 selected source records 与 Mini06 相同，tender sourceOrder 均为0–8。306个 source requests 全为 `already_global`。未启用独立 Vetting planner probe，因此只能按冻结代码、完整9-of-9源集合、已准入 global input 和上述请求状态推导 P=17、I=17、R=0、K=0，总 counter=34，不能声称独立测得 provider TCP／账单调用数。提供商逐 attempt 声明的 usage 为输入570,836、输出2,744、合计573,580 tokens；它与 counter 数量、最大上下文实测分别报告。

原票与分母见[Mini07 acceptance](../../../.local/e2e-20261020/vetting/mini07/acceptance.json)、[完整终态响应](../../../.local/e2e-20261020/vetting/mini07/009-terminal-run-before-any-export-response.json)和[逐实际业务形状复算](../../../.local/e2e-20261020/vetting/mini07/actual-business-shape-replay.json)。原终态 UTF-8 字节 SHA 为 `9ac2607fcc2d13c13ccc87608b0fdd6d000752df5f40b4d04a49bcee0a493a01`。

## 逐记录结果及首个失败接缝

| 位置 | 实际内容 | 原校验结果／边界 |
| --- | --- | --- |
| topic8 record0 | 正确逐字引用 two／exactly three signed paper copies 两句，同一 tender、日期和时间 | pendingFacts 非空却 suggestedAction=amend；既有业务字段门拒绝 |
| topic8 record1 | 99.8“不存在”的 reference 候选，以两行编号标签作附加引文，同时承认可能有窗口外目标 | 原数组因 record0 失败，未执行本条来源证据门；不能追认有效或已发布误报 |
| topic8 record2 | 依据未确认的99.8程序内容缺席形成 requirement 候选 | 原数组被拒；没有来源／业务采纳 |
| topic17 record0 | 再次正确引用完整 two／exactly three 两句 | pendingFacts＋amend；issue 搭配 checklist insufficient_evidence，又违反既有配对门 |
| topic17 record1 | 99.8缺失改称 requirement，窗口外目标仍在 pendingFacts | pendingFacts＋amend，原业务字段门拒绝 |
| topic17 record2 | 两句日期时间相同，模型推测“of the same tender”会产生另一套 deadline／submission regime | pendingFacts＋amend；来源语义仍无支持，未发布，不能因原始引文可定位追认第二个冲突 |

全部17份实际 raw array 通过原 production `StructuredJsonValidator`。旧 `executePacket` 只在 bounded attempt loop 捕获 `INVALID_JSON`／`SCHEMA_MISMATCH`；`validBusinessDetails`、pendingFacts动作约束及 checklist assessment 配对在循环之后，失败会抛出 `IllegalArgumentException`，无法使用已有第二次尝试。因此实际 Mini07 没有执行07结构修复分支；不能用217项离线通过填补这个真实分支。

## 设计依据与最小候选

[比赛运行规范正文](../../src/main/resources/vetting/contract-system-competition-20261015.md)第七部分要求“适用且失败先修正”，同时禁止无限再生成换绿；[对应 spec](../specs/contract-vetting-system-prompt-competition-20261015.md)的“持久化与实现约束”保持同一要求。[统一实施规格 U4–U6](../specs/consense-oer-vetting-advisory-integrated-20261014.md)要求明确有限调用／总修复上限、完整实际请求准入、保真实失败及拒绝失败报告。未找到要求业务字段失败必须永远绕过已有第二次修复的条文。

本候选只把**完全相同的 record-shape 校验**复用于已解码候选及既有后置门。它覆盖固定字段、pendingFacts动作和 checklist状态配对，未把来源证据、条款归属、采用、法律或语义判断搬进自动修复。

- 每包 generation 仍 `MAX_MODEL_ATTEMPTS=2`。已有 JSON／结构失败和新增业务形状失败共用两次额度，不新增第三次尝试，不单独拆分／发布原数组中的合法条目。
- 业务形状失败记录固定 `BUSINESS_SHAPE_MISMATCH` 标签及原有固定 reason list，保留整个失败 raw/hash。原始 model action 不改写，模型自行生成完整新数组；raw 继续作为明确不可信 JSON 字符串进入原完整请求之前。
- 保留初始 packet ID、promptEnvelopeSha 和 source snapshot 锚。attempt2 使用自己的完整新 input/hash/budget，原 whole user/source wire 是逐字相同后缀。先重计原请求并核政策，再对完整修复请求计数；未知、超预算或身份变化在 reserve／generation2 前拒绝。
- 新业务形状分支没有保留 raw 时直接失败，不盲发相同原请求；第二次仍无效就保持失败。来源引文拒绝仍不触发该修复，修复后的虚构引文也不能被采纳。
- 采用显式 `business-shape-feedback-max2-v1` policy identity；输出 schema 和原 system 文字保持。原07 checkpoint policy SHA 为 `2f67ea0f6b9c0b4cd969884130a0963cba8b18c26f1477052cfc24c6cfee1e10`，候选09为 `f4dbc07677cc2c462b9a8c290270a66e40e105c2d32d82f66d55b8ac4f0edd78`，不能复用旧政策结果。

## 离线验证及未关闭范围

初始3项候选在冻结07上实际 RED：2项因仅调用1次而失败，来源引文失败不重试的反例通过，0skip。原生产 SHA、测试 SHA 与完整日志见 [RED receipt](../../../.local/e2e-20261020/vetting/business-shape-red/receipt.json)。

最终13类217/217、0failure/skip/abort、进程exit0，其中包括原209项及新增8项，不把先前 RED/GREEN 重复相加。新增项覆盖 Responses 和 native chat、整个 raw/理由与完整来源后缀、新 hash/budget、未知／超预算修复阻断、源 guard 改变、两次无效无第三次、来源引文失败及修复后虚构引文拒绝。真实 MVC／异步／隔离 H2 用例另外从持久化 run 读回两次 attempt、完整理由/raw/hash/own budget，再读取实际 findings 数组确认未发布失败 raw；使用 authored WireHttp，没有连接真实模型或活动应用。

[GREEN03 217 receipt](../../../.local/e2e-20261020/vetting/business-shape-green03/receipt.json)和[实际17份旧 raw 的完整门输出及 policy 对照](../../../.local/e2e-20261020/vetting/business-shape-green03/actual17-gate-and-policy-equivalence.json)分别记录测试与纯门等价：保留的17数组、6记录在新 helper 产生的 reason list 与冻结07复算逐值一致。它们不能证明下一次真实模型会正确修复、保住已识别冲突或消除99.8／标签误判。

首次正式09 Maven 在 `testCompile` 失败，尚未执行测试：新增 `VettingBusinessShapeRepairTest` 的 `var` 循环类型不兼容项目 Java8 source 设置。先前独立 javac17 编译允许该语法，因此隔离217通过未覆盖此编译要求。[首次正式失败收据](../../../.local/e2e-20261020/build/round09-attempt1-compilation-failure.json)保留 exit1、0测试及全部1485源哈希未变；原项目目录、日志和快照均保留。只将新增测试改为明确 `SemanticAttemptVO` 类型并补 import、移除过时 RED-only 注释，生产和 workflow 源哈希保持。[GREEN04](../../../.local/e2e-20261020/vetting/business-shape-green04/receipt.json)重新执行同13类217/217，0failure/skip/abort、exit0；[v2冻结清单](../../../.local/e2e-20261020/vetting/business-shape-ready-v2.json)绑定该修正与首正式失败，不覆盖首版 peer 或失败证据。正式后继使用独立 `round09-attempt2-project` 和输出目录。

04:42正式后继完成：[round09 attempt2 verification](../../../.local/e2e-20261020/build/round09-attempt2-build-verification.json)确认 Maven source8 编译及 package exit0／BUILD SUCCESS，54 suites、870/870，0failure/error/skip，精确保留08全部862个测试身份并新增8项。全部1485冻结源文件构建后大小／SHA保持，测试没有新增 non-target 文件；NTT／SCT／SCC 的完整 parse、lexical、index 共9份文件与冻结基线逐字节相同。JAR 为80,157,903 bytes，SHA `c443f624f6f7e57ee1181b9bf4b4a17cea4ec4469b980e4e87c6916394251e42`，正式源树摘要 `fbd2beacdac2bd304113beaded7c04dc5fe318ee5d943b23fb0032a50c59d51b`。stderr137 bytes是预期DOCTYPE拒绝，不能称stderr为空；Drafting另行完成的109项wire环境回放不并入870分母。本构建未调用活动应用／真实provider／OER模型，也未部署；已交Root统一运行环境管理。

## Mini08：真实修复机制通过，业务召回失败

Root完成09安装与8份API原始字节保全后，预声明绑定PID27040、上列JAR及原9段／原source snapshot，只启动一次 POST。run `ae8d3559-00f0-430a-962f-6749bdd53d58` 于04:45:44.966–04:49:07.243运行，202.276394秒，终态 COMPLETED、reviewCompleteness=`incomplete`。17主题、17包、18次generation，只有topic1用了第二次业务形状修复，没有第三次或追加运行、上传、重解析、人工重试／finding写入。原件SHA及source snapshot与Mini07相同。

[Mini08 acceptance](../../../.local/e2e-20261020/vetting/mini08/acceptance.json)绑定完整原票。17份首次完整 Input 与 Mini07 逐JSON值相同；18份实际 Input 的完整来源集合均与Mini07相同，tender sourceOrder全部0–8，306个source request全为 `already_global`。[真实修复绑定复算](../../../.local/e2e-20261020/vetting/mini08/actual-repair-binding-replay.json)及[完整业务形状门复算](../../../.local/e2e-20261020/vetting/mini08/actual-business-shape-replay.json)在正式09类上通过；691个被用作离线依赖的生产class文件与对应JAR entries逐字节一致。失败raw、固定理由、JSON引号包裹的不可信反馈、原whole user精确后缀、独立新hash／预算、原packet／source锚均保持，唯一业务失败理由也与生产helper逐值相同。

topic1首答含3记录：record0逐字引用原p[4]／p[6]的 two／exactly three 两句，却带pendingFacts＋`amend`；record1为99.8缺失候选；record2为topic所需材料缺席的 `insufficient_context`。整数组被原业务形状门拒绝，没有拆分发布。实际 repair 前缀已明确要求 `retain source-supported correct records` 和不得 `drop supported records merely to return an empty array`，原raw与全部原请求也实际送达；第二次provider却返回 `[]`，其他16主题最终同为 `[]`，findings GET为0。因此已知正确冲突召回失败。99.8／裸标题没有被发布只说明本次最终空发现，不能宣称通用误报精度通过。[repair lineage](../../../.local/e2e-20261020/vetting/mini08/repair-context-lineage.json)将空数组首见层定位为真实第二次模型答复；未发现提示接线或输入字段丢失，不能把提供商选择直接归因为确定代码bug，也不通过改action、拆数组或同题重采制造通过。

既有ReviewProbe在本次真实run中实际激活，并保存367个事件、0观察失败；本次 `[P,I,R,K]=[17,17,1,1]`，实际应用observer计数36，规划cache hit0，低于本9-of-9路径预声明条件上限68。完整逐主题和逐预算事件/hash见[counter receipt](../../../.local/e2e-20261020/vetting/mini08/counter-observation-boundary.json)，不能称独立provider TCP／账单测量。提供商逐attempt声明输入606,177、输出1,629、总607,806 tokens；它们与应用计数分开。观察事件总耗时0.579314秒，仅计先前事件的序列化、文本/hash、文件写入；最终summary自身刷新不含，嵌套阶段耗时不相加。两个本地进度reader辅助失败（默认GBK与null字段）分别保留，再读只访问原文件；最终完整UTF8复算通过，没有额外模型重试。

报告机制检查通过，业务结论仍失败。首次completion、JSON1、JSON2、导出后四个snapshot均为 `4936af81342469c646b226d175577560ccf340b88e07ea6f4dbf0fb8100f2784`，两份完整JSON各3,425,488 bytes逐字节一致；DOCX18,768 bytes、PDF68,342 bytes，内部run/snapshot也一致。[报告identity](../../../.local/e2e-20261020/vetting/mini08/report-identity-verification.json)与[限制呈现复核](../../../.local/e2e-20261020/vetting/mini08/report-limitations-verification.json)确认JSON为incomplete，DOCX/PDF保留 `full_countercheck_unproven`、逐topic incomplete、完整原件与实质复核未确认、0finding不代表无问题、canonical观察分母与未审范围等限制。只渲染检查PDF39页中的p1／37／38／39，相关文字可见且未见新增截断／重叠；其余35页未重复视觉全测，DOCX未另作全版式重渲染。本次完整终态raw SHA为 `88dc9ba62dfdb8469df33db31bce312b6a96e183e7695d87cede73e0c64a13fd`。

Mini06原业务失败、Mini07运行失败、Mini08召回失败、未声明标题语义、窗口不等于全源穷尽、六项 H2 文件恢复失败、来源质量与整体业务质量仍分别保留。[编号行／缺失结论只读 frontier](vetting-heading-and-absence-frontier-20261020.md)未因本修复关闭。Mini08已终态并明确释放窗口，0模型／HTTP在途；后继部署及其他模块窗口由Root统一协调，本模块不为换绿再采样。
