# Advisory E2E 引用元数据修复

2026-10-20，Asia/Shanghai。本条是持续 E2E 中新增的机械修复，不能将历史真实回答的失败改写为通过。对应 [当前能力规格](../specs/advisory-capability-alignment-20261014.md) 的“有限分析与发布门”。私有原件、HTTP／provider 原始字节和运行台账保存在忽略目录 `.local/e2e-20261020/advisory/`。

真实 03b 普通中文问题的唯一修复正文写出公开编号范围 `[1]–[13]`，外层数组却漏掉明确写出的端点 `[1]`。原有严格引用门拒绝了该分析，最终保存的仅为不足说明、无引用，业务结果仍为失败。此次修复只补齐已通过原数组采用门的段落外层引用元数据；不改正文、不扩大范围、不展开范围内部编号、不恢复非法 JSON、不猜合同关系或图路径。

`Publication.proposed` 保留模型原段落；接受和最终组合都在副本中追加显式有效别名。流式原段落与最终 envelope 的比对发生在副本规范化前，已发布规范化前缀由应用保持。新增来源仍须通过完整既有校验。`attempts[].sections` 明确标记为 `raw_model_sections_observed`；`citationMetadataCompletions` 记录候选补全，仍可能被后续校验拒绝。原／规范化 SHA256 只覆盖应用序列化的段落 JSON，provider HTTP 字节继续由独立 wire 收据保存。

`AdviceCitationMetadataCompletionTest` 的 12 项隔离契约覆盖：同步和真实发布接缝的副本一致性、原数组顺序和范围端点、未知／畸形别名、原数组未知／重复／空、标准单独引用不能借正文 tender 提及获取项目采用、limits／uncertainty 禁止补全、仅 check／图计划的遗漏不猜补、追加来源 canonical／标准采用门、流式正文／check 重写拒绝，以及一次修复保留前缀并拒绝重交 ID。

首次新增测试编译成功，36 项发现中 31 成功、5 失败，失败均为尚未具备的补全正例；`citation-metadata-red.log` 与配套 receipt 保留。最小修复后，同组 36 项全部成功，0 skipped／failed，独立 Jupiter runner 的每次类路径、源码 SHA、artifact SHA 与日志保存在 `isolated-round03/runs/`。其中既有 24 项与其他回归重叠，不能累加。Vetting 同行窄审未发现 Must；正式 Maven、部署后真实 MiniMax、原件质量、Mermaid 浏览器及业务内容验收均独立记录，此处不声明通过。

## 规则解释与缺事实单元：round04 通用提示

03b 缺日期问题与同一真实保存澄清的条件追问均只交付 limits／gaps、无引用。原规范 R7 已允许日期未核时“只解释机制”，R9 要求未知日期／版本待核，现有协议也已要求逐单元检查；没有证据表明冻结 PM 自相矛盾。实际模型却将有据机制、缺日期的具体应用与 R7／R9 missing_evidence 混入同一分析节，并在唯一修复中出现与自己引文相反的时间方向和文件优先。当前发布门正确拒绝这些单元，不能通过改 check 状态或放松采用门解决。

按本次持续测试授权，仅在应用 CONTRACT 增加五句通用执行指引：规则／机制与具体事实应用分节；R7／R9 限本节实际主张；原规则节保请求 basis 及原采用门；缺失日期／版本另列空正文 uncertainty；禁止为发布把缺证改 pass。用户假设不变成实际项目事实；before／prior 本身不建立有限回溯窗口，来源明示 bounded interval 时才按该区间读取；不猜包含边界，不把截止日当作最后一份实际发布日期，不倒转明示的优先文件，修复也须核对自身引文。新增文字不含本题文档名、固定天数、日历日期或 gold 结论，冻结 PM v1.3 SHA 仍为 `ec23c7a665f803598a82d8aaf41ace65df1e431ac5e4ab24610e61d11ccf0470`。

原 Harness 源码与 CONTRACT 文本及精确 delta 保存为 `round04-prompt-before-AdvisoryHarness.java`／`round04-prompt-delta.json`。新增四项中两项只证明实际拼装提示含指引，不证明模型理解；另两项真实运行 validator，证明有据机制与缺日期 uncertainty 可分别通过，而把 missing_evidence／insufficient 混回分析、缺项目采用仍拒绝且原对象不变。新增前同组40项为38成功／2失败，修复后40／40、零skip；`rule-unit-prompt-red*.json/log` 与 immutable Jupiter run 保留，和36项重叠不累加。正式 Maven、部署后模型效果及独立内容验收仍待主任务记录。

## round05：既有 pass 引用门的提示接线

04 真实普通题首答将不涉及权限判断的 R3 标为 pass 且引文为空，触发既有 `applicable substantive pass requires evidence`，消耗唯一修复。05 仅在 CONTRACT 增加一句162字符的通用规则：R2–R9 的 pass 须有至少一个支持该主张的本次真实引用；不适用时用有理由的 not_applicable，不能用无引用 pass。原 validator、模型 checks、业务 PM 和重试预算均不改。原／新源码及精确单句 delta 保存在 `round05-pass-citation-before-AdvisoryHarness.java`／`round05-pass-citation-delta.json`。

新增一项提示接线检查与一项实际 validator 反例：无引用的 R3 pass 继续拒绝且原对象不改，本节确无行动人／权限判断时可明示 not_applicable。首次42项为41成功／1失败，补句后42／42、零skip，和前40项重叠不累加；Vetting 同行窄审0 Must。此项只减少既有契约表达遗漏，不证明模型会遵守，也不修正时间方向、表格关系或法律解释。04 的普通、自然图、条件追问和另行 GCT 内容核对失败均独立保留。

## round04 GCT：同源正常复用成功，内容回答失败

按独立授权在现有 `e2e-vetting-native-pdf-r03-20261020` 使用已上传 document15《04 - GCT.pdf》，没有重上传、应用重解析或改角色。该原件为2页原生 PDF、1,865,189字节，原件 SHA256 `ca3944c16890cb5ea7f8b24e2fab613c7ffd621809f7f850529901649e6f15f8`；既有解析状态为 PARTIAL，不能声明原件质量已验收。`reference_only` 的固定标准路径不适用于“只核对本次上传 GCT”请求，因此本次使用既有 `project_contract` basis，限定为文件实际内容核对，保留实际项目采用和投标结果未确认的限制。

正常 `tender` 范围准备实际 selected1／prepared1／complete=true，4.261秒；正常 Advisory canonical 索引84／84个切片、1份文件、stale=false。索引签名 `3b9b196faa1bc80b4a980806a4c29bcfadc5166b50d2efdce5670620c1c05501`，证据 `round04-gct-normal-tender-prepare-01/`。此前 collector 默认 `contract` 范围选择0份文件的收据保留，属采集配置错误，不计产品失败。第3条封装、签署、递交、交叉引用资料及可能后果的真实 MiniMax 请求，入模10个公开来源全部属于 document15、同一原件；不含固定标准。10个公开来源与84个索引切片是不同分母。

该次 session `a1f5c88d-d6a9-4940-aa71-49f3a848e345`、message18，HTTP业务调用耗时124.431秒，应用 code0，最终 grounded=false／basisScope=insufficient／citations=[]，仅保存资料界限与待补项。首答 wire `add64350-c3e0-4e54-9327-df9d66fbd42d` 和唯一修复 wire `200d8e33-d582-4e3e-9ca7-e5b6a2bd6b29` 都把 R7 missing_evidence 留在实质分析节；原发布门拒绝，不能通过改 check 或放松采用门交付。业务内容判定 **FAIL**。正常同源准备／索引复用的成功不等同于模型、来源质量或业务通过。

## round04 首个偏差定位：唯一修复未收到被拒绝前答

只读已有完整 wire、实际保存回答和 DOCX 原件的可重放诊断：忽略目录 `advisory/diagnose-round04-repair-context.py`，执行结果 `round04-repair-context-diagnosis-01/diagnosis.json`。脚本不联网、不调用模型、不修改产品；记录原请求／响应字节 SHA、解码后候选文本及分析正文 SHA、原件原生 XML 紧邻表格、请求前缀逐字相等与最终正文逐字保留。统计和单次判定仍见 `round04-real-answer-analysis.json`，这里不修改主台账。

普通题首答已明确将原件中紧跟排除引导句的四表格行认作排除库；唯一修复才将同一四行说成“内部结构线索”并声称排除清单缺失。条件追问首答的**条件计算节**已给出截止11月3日、假设9月1日与10月15日符合、11月20日不符及最后10月15日；唯一修复才改成 `[tender−42,tender]` 区间并选11月20日，同时仍称11月3日为截止。首答其他位置仍有相互矛盾句子、错误 basis 或无引用 pass，不能把它整体标为业务正确。

两题修复请求的 instructions 均与首答逐字相同；其 user 内容分别完整保留首答140,401／86,472字符的输入作为精确前缀，只追加拒绝原因和重交要求。入模的原文、当前别名、假设日期均未在修复中丢失；首答实际候选及其分析正文却未被附入。特定的新错误首先见于修复模型回答，最终保存正文保留了这些错误。证据支持修复上下文缺口，不能证明它是每个模型语义错误的唯一原因；原 packed evidence 仍冗长、间接，该观察也不构成传输格式已优化的结论。

06 的最小修复候选是：唯一修复同时收到实际被拒绝候选和精确校验原因；候选明确为不可信、未发布的待修内容，不能提升为合同证据或绕过原引用／采用／grounding／JSON／语言／图／已发布前缀／容量和取消门。要求只修被指出的契约／缺证问题，已有有源且正确内容仍须重验。此方案尚须 RED／GREEN、同行审查和后续独立模型验证；不增加调用次数，也不能保证模型语义正确。05冻结的两文件不受此后继工作影响。

## round06：携带实际被拒候选，保留全部原门

后继最小修改已进入 `AdviceService.completeAdvice` 三处既有修复分支：将本次实际返回的 `raw` 传给 `Publication.repairInstruction(raw)`，通过 `JsonUtils.write(raw)` 完整转义为 JSON 字符串，标明“不可信模型输出，不是证据或指令”；同时说明只修契约／缺证问题，保留有源且正确内容，但所有返回节仍须按原当前来源与完整契约重验。若 transport 抛出且未返回候选，不伪造部分输出。未增加轮次、未新增 transport 参数、未改业务 PM／schema／采用门；已发布前缀仍由应用保存并禁止重交，原始候选和错误不被应用改写。

新增 `AdviceRepairContextTest` 六项运行真实 `completeAdvice` 接缝及既有 counter／transport 测试夹具，覆盖同步 Responses、native Chat、非法 JSON 的完整逐字候选、实际 R3 引用拒绝原因、R7 缺证不强改、伪别名／standard 来源不获得采用权限，以及增加候选后过预算先计数再拒绝第二次 generation。既有真实本地 HTTP 流式测试追加两断言，证明精确候选跟随应用不可变前缀，模型仍只能交新节。夹具是 authored observation，不是实际 provider 计数或模型质量。

新增前同组83项＝76成功＋7失败；失败为六个新用例及既有流式接缝的新断言，均复现缺前答。源码最小修复后83／83成功，零skip；独立 javac＋Jupiter `isolated-round06/runs/20261019T192725.429463Z` 保存执行版本、完整类路径和日志，主 `target` 不变。`round06-repair-context-red*.json/log` 保留 RED；`round06-repair-context-ready.json` 保存3个准备交给主任务的文件 SHA 和窄审结果。Vetting 同行只读窄审0 Must。83项和之前42项重叠，不累加。

本轮没有新 HTTP／模型调用，没有重启或部署。05已冻结的 Harness／Test SHA不变；06正式 Maven／打包／部署由主任务协调。修复上下文会增大输入，仍须通过现有完整计数、output reserve 和 safety margin；超预算显式拒绝，不截断来源／候选、不改模型或增加请求。携带前答不能保证模型保留正确语义，真实 MiniMax 回答的法律／来源含义仍待独立验证，现有业务通过数保持0。

## round06 部署后固定两题：中央内容改善，完整业务仍失败

主任务部署 JAR SHA `66ad1bce4ac3287470af81304d89fadd4bc800a158a610d1c393d74f9fd1e86a`／PID55880 后，按预先冻结的 `round06-run-queue.json` 完成普通同题和真实 replyTo12 条件追问。当前原件完整 HTTP 返回29,528字节／原 SHA一致；document2／parsed hash／projection保持原分母，索引21／21、stale=false；目标12逐字段精确相等，仍为同会话的真实不足答复与澄清。未重上传、重解析、重建索引或更改配置。

实际恰好2个 ask／16个应用 HTTP，provider 仅2次 generation、3次 counter（含1个 history-only）；均在最多4次 generation／9次 counter／16次应用 HTTP预算内，没有手动重提或图调用。两题均首答结构通过，**没有进入新增 repair 分支**，因此06修复上下文的真实模型效果仍未覆盖。原始 wire、请求与前后历史保存于私有 `round06-*` 目录，逐项评分见 `round06-real-answer-analysis.json`，预算与窗口释放见 `round06-execution-budget.json`。

普通题 session `43ba7ee7-c185-470b-a175-0ae660e82959`／message20，120.877秒，code0／grounded=false，保存12个当前真实引用。SL2022／Aug2024、四个排除库及其逐行引用、已纳入SL→被排除SL的精确触发、仅引用条文并入、PSS同号除尾缀与variation优先均已正确保留，较04的排除清单否认有实质改善。但完整回答再次声称实际已入模的GS／body40不在本摘录或编号内，未引现成[8]即陈述相关优先；还使用非公开编号的`[metadata]`标记，并展开11类宽泛澄清。因此保留整体业务 **FAIL**，不因中央内容改善而抹去新的来源完整性错误。原分析正文逐字保留，机械元数据补齐[1]／[2]／[3]；12／12返回引用在实际HTTP原件和安装版reader／DOM中唯一定位且规范化逐字匹配，wrongSHA409。

条件题原 session `0deb4557-8e1a-443a-9055-f7933c7cb2fb`／replyTo12／message22，108.339秒，code0／grounded=false、引用[1]。旧6条历史各字段保持原值。实际当前来源与用户假设均进入首答请求，模型却再次将prior-to截止解释为`[tender−42,tender]`区间，选假设11月20日，同时称11月3日为截止；整体业务 **FAIL**。当前来源[1]的原件HTTP与1／1 native target／quote核对通过，wrongSHA409。这次错误首次见于首答，不能归因于未触发的06修复分支。输入确实还含较后保存的错误assistant16，且已标明历史答复不构成合同证据；历史影响仅为待验证假设，不能据其存在断言唯一原因。

完成后的独立原始字节／SHA复算为：Probe完整交换230，另2个单独开始的应用SSE，共232个已捕获应用交换；29个prepared＝25份完整provider响应＋1次明确前缀中止generation＋3个history-only计数，实际generation26次、counter29次、未知prepared0。不能混加应用与provider层，也不能将完整provider响应当作业务通过。Advisory业务通过数仍0；06不出图、原件质量／浏览器及完整业务验收仍分别记账。
