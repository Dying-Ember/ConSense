# 来源约束的 vetting runtime 原型计划

2026-10-02。第一版设计已实现为轻量 Python 串行状态机，并完成 24 项离线测试和三个冻结 scope 的六次真实 native 调用；独立语义结果在实验日志另行追加，尚未部署到 Java 生产 vetting。原型复用已冻结的 source pack、native gateway、资源采样和实验日志，不把参考案例答案写入提示词。程序先核验字段来源，模型解释和比较对象；自动检索扩展仍是后续工作。讨论与状态见 [决策记录](vetting-discussion-and-goal-20261002.md)，执行目标见 [当前 goal](vetting-current-goal.md)。

现有两阶段实验已表明：要求模型同时输出原文、chunkId、来源角色和用途标签，会产生跨邮件绑定、跨行拼接和请求/回复混淆；只有字面定位仍不能阻止错误的对象比较及“所有来源一致”断言。runtime 应让程序承担可机械验证的来源工作，把模型调用保留给选择和解释。最终状态仍是待独立源审，不能用合法 JSON、`[]`、零候选或同值检查代替质量验收。

### 第二版实际结果与下一步

2026-10-02 已完成 `span_comparison_runtime.py` 的 29 项离线测试，以及第二版三个 Stage2-only 真实调用。首轮 Stage1 的 11 条原始选择、8 条来源绑定、3 条拒绝严格按旧 call ledger 复用；新调用数为 3、retry 为 0。来源目录扩大为原请求、Clause、header、真实空 Reply 与原文上下文的 214 units，139 个原 Part、120 个 native cells、23 chunks 的字节重建经独立源核验。模型输出 `{unitId, rawValue}`；程序定位原范围并生成引文。相同子串只作为警告，不认证完整属性、对象或用途。

本轮同时改变 schema、system/user、来源呈现、ctx 16384→32768 和输出上限 2048→4096，不能作单因素归因。3B/.2 的三条输出全部为 unknown、左右值为空。61 次 selector 中 40 次定位、21 次错误；唯一机械 gate pass 是标准模板复述。独立审核判定 34 项中 33 项未评、13 个字段全未评，质量未达标。实际 tender 日期及七天条件被正确引用，只能计作局部源识别，不能计作日期比较成功。

证明：`tmp/span_comparison_runtime_runs/span-stage2-only-3b-ctx32768-20261002T052731136669Z/technical_span_comparison_manifest.json`；独立源审：`tmp/independent_fact_response_audits/span-stage2-only-3b-20261002T053529469329Z/evaluation.json`。全部原始结果及失败继续保留，第二版没有生产部署。

第三版已按真实 native `Required input` 文字和原来源引用归组，保留各版本 populated/blank/no-column 上下文；每个冻结请求任务独立调用。任务数量由原文决定，不能读取独立 34 项或 13 字段矩阵生成任务。模型采用紧凑 ID 指针，程序据真实原单元生成引用；whole-unit 绑定仍不等于完成原子属性比较。多目的请求保持未确认的原子性，未选择和 null 不代表缺失，标准模板仍需采用依据。

### 第三版实际运行与受控对照

`request_task_runtime.py` 的 21 项离线测试、139 Parts/214 units/23 chunks 独立原文核验通过。真实 source-derived taskPlan 有四条任务，右侧枚举只能选本任务实际 populated Reply，不能用标题、Required input、blank、no-column 或 standard 充填项目答复。每个任务至多三条 observation、三条 support 只是输出 cap，不能表示多目的请求已经完整覆盖。

`request-task-id-only-3b-20261002T0624105353167Z` 真实新增四次串行比较（retry0/new selection0），3B/.2/ctx32768/output4096，四次均 stop，合计调用 26.368 秒，采样整机 GPU 峰值 5094 MiB。原请求与准备文件 byte-identical。独立源审为两条错误、两条部分支持、完整支持的局部比较零条，qualityAccepted=false。四条左证据均为标题；存在用途错配、明确否定误读和 match/value_difference 自相矛盾。20 个程序引文全部 literal-bound 仍不能算语义通过。34 项评价为错误7/部分8/未评价19，13 字段为错误1/部分5/未评价7；不接入生产自动发现。

2026-10-02 已完成两个独立的 **prepared-only、零生成调用** 对照。`request_task_runtime.py` 增加 model、expected digest、system prompt 参数，默认仍为原 3B 和原 SYSTEM。新增六项默认字节兼容、单因素请求差异、身份校验和零调用阻断测试，与原 21 项合计 **27 项离线测试全部通过**；这不是模型质量验收。新冻结 runner SHA 为 `04e434c6b22ee93bf33fe1ede84c1020a84bc8b7f706922d54d1172548200ab5`，原 `0f4db48` runner、062410 实际运行及所有历史文件不变。

| 对照 | 准备实验 ID | 相对实际 062410 请求的唯一允许 body 变化 | 固定生成参数 | 当前状态 |
|---|---|---|---|---|
| A：system-only 3B | `A-system-only-3b-20261002T064058158379Z` | `messages[0].content`，在原 SYSTEM 后追加泛化规则 | ctx 32768 / temperature .2 / output 4096 / retry 0 | prepared，actual attempts 0，未生成结果 |
| B：model-only 7B | `B-model-only-7b-20261002T064058158379Z` | `model` 改为 `qwen2.5:7b-instruct-q4_K_M`；manifest 绑定对应 digest | ctx 32768 / temperature .2 / output 4096 / retry 0 | prepared，actual attempts 0，未生成结果 |

A 的规则要求选择实际有规定的正文、比较本任务 tender 与已填 Reply、区分请求标题和答复、限定对象和用途、用实际支持条件的单元、维持 relation/differenceKind 一致，以及不按日期或文件顺序推定采用。没有加入条号、人物、地址、日期或评价答案。B 保持原 messages/schema/options/stream/user 全部字节，只换 native body 中的 model。一次只读 `/api/tags` 确认缓存中 7B digest 为 `845dbda0ea48ed749caafd9e6037047aa19acfcfd82e704d7ca97d631a0b697e`，3B digest 为 `357c53fb659c5076de1d65ccb0b397446227b71a42be9d1603d46168015c9e4b`；没有拉权重或加载模型。

两组 taskPlan 均从同一实际来源推导出四条任务，每个任务最多一次、顺序执行、重试零次。139 Parts、214 units、23 chunks、各版本 Reply 状态、候选枚举、完整原文及 source hashes 相对实际 062410 不变。A 四份 request 为 40655/28754/28783/22165 bytes，B 为 38739/26838/26867/20249 bytes；输入和输出 token 预算仍为 unverified。每任务三条 observation 的 cap 可能不足以覆盖多目的请求，不能据 cap 或合法 JSON 作完整字段结论。7B 的实际内存和自动 CPU offload 尚未测量，不能从 3B 数字推算，也不能把模型配置对照解释为参数规模的单独因果效应。

不可变准备目录为 `tmp/request_task_profile_prepares/system-and-model-pair-20261002T064058158379Z`，其中 `profile_pair_preflight.json` SHA 为 `ad32ba5c893f17dd222bb715c70e4a4c11bdeaa95ac50b40afcdc8d210f2608c`；两个子目录分别保存请求、schema、来源、runner 与 `single_factor_source_proof.json`。默认只读的 `tmp/run_authorized_request_profile_20261002.py` 已对 A/B 各作一次文件预检，均为零 HTTP、零生成、零采样；实际执行还需明确授权和全新输出目录，不能覆盖准备目录或原实际结果。原四条 match 的标题和用途问题仍未通过质量验收。

### 日志状态衔接的独立软件修复

OCR max-side 对照首次执行在 stage 0 暴露 `experiment_log.py` 的状态门禁问题：旧 `planned` 被当成已结束状态，导致预登记记录不能追加 `started`。该失败发生在模型、PS、OCR 和采样之前，原失败组与所有历史事件保留，不能把软件失败写成参数效果。

修复仅允许 **old status 为 planned 且 eventType 明确为 started** 的衔接；completed、failed、prepared、unknown 等状态仍不能回到 started。parameters/inputFingerprints 不可变、calls 只能追加且保留原前缀、独立审计不能改观测值等约束未放宽。临时 registry 的真实 `append_record` 回归覆盖 planned→started→completed、参数/指纹篡改拒绝、调用前缀保护、终态重启拒绝、旧 planned 文件字节不变，以及非 started 事件不能启动计划。**14 项 logger 离线测试全部通过**，并逐字 hash 确认 184 份已有 registry 事件未变；零模型、OCR、API、数据库或资源采样。

证明冻结在 `tmp/test_snapshots/experiment-log-planned-started-20261002T065606598638Z`，保存实际 stdout、stderr、源码和 `test_summary.json`（SHA `d251c8b1b85ccb5783220bf6cfc34c0118e8d695005d5d321ea3ac178ca5ddd1`）。新 logger SHA 为 `d0fff2f4eaeb9521c62d1810d6771d5d397c6564280e0bc91fcc58a9f247900f`，实验 ID 同冻结目录名，最终 `software_validation` 事件 SHA 为 `fd3651e0bd2170afa9923284857d6626d1e7bc202dc17823b366fd1455cdc5bd`。该证明不替代旧 OCR 29 项测试或原 A/B 冻结依赖；新 OCR 对照须另建组并明确绑定修复版 logger。有效 CLI 在 started event 前的目录/文件初始化 IO 失败仍可能仅留下 stderr，这个可观测性边界保持独立记录，不称所有初始化错误已覆盖。

## 1. 原型范围和对照边界

- 输入只来自授权的冻结 packs；本轮起步最多三个 scope，每 scope 一次选择、一次比较，**最多六次 native 调用，顺序执行，重试为零**。
- 不调用上传、OCR、索引、检索或应用全项目 review，不自行扩大材料范围。原文和原 owner、scope、role、offset、hash 不改。
- 模型选择原文单元和 value；程序决定 chunk、表格列、原文引用及源条款绑定。模型不能提供替代的 source ID、role、reference 或自行重建的引文。
- Stage 2 仍接收全部冻结原文、原请求和回复状态。被拒绝的选择不会删除对应原文，空选择不会被解释为源中没有值。
- 参考案例、独立 evaluation fixture 和人工判定仅由离线验收使用，runner 及 prompt-builder 不读取它们。
- 与已有实验比较时，明确这是来源约束工作流实验。unit schema、输入组织和程序验证改变，不能称 prompt-only 或单因素改善。另设真正只改 system 的对照，不能混为一组。

建议先自研这一小段串行 runtime。它需要确定性文件输入、两次调用、明确失败状态和逐步骤回放；现阶段不需要多 agent 调度或自动工具循环。引入通用框架也不会自行解决来源角色、对象和适用条件错误。后续若需要复杂检索分支或跨任务恢复，可在相同节点接口和事件协议上替换调度层。

## 2. 构建不可变原文单元

单元由程序从实际 `Part` 和 native table metadata 生成。表格单元保留 row、列、原 Clause 和 Required input；段落单元保留相邻标题与限定。不能用人工正确答案生成候选字段。

| 字段 | 来源及要求 |
| --- | --- |
| `unitId` | 根据 corpus metadata version、sourceHash、documentId、原 blockId、原范围和 cell index 生成确定性 ID；绑定 unit manifest hash |
| `documentId` / `sourceHash` / `fileKey` / `role` | 直接复制冻结 corpus；保留 `OTHER` 和未知身份，不根据标题猜角色 |
| `submittedChunkIds` / `partIdentity` | 记录本轮实际提交并覆盖该单元的 chunk/Part；不得引用仅存在于完整库但未提交的文字 |
| `blockId` / `startUtf16` / `endUtf16` | 原 effective block 范围；逐单元检查原 `substring` 等于单元文字，保护 surrogate 边界 |
| `tableRowId` / `cellIndex` / `cellHeader` | 来自 native cells/headers；检查 header/cell 数、原行重组和 offset；缺元数据保留 unknown |
| `rawText` / `rawTextSha256` | 原文原样保存，包含大小写、标点和换行；不使用模型重写值 |
| `rowContext` / `requiredInput` / `rawClauseCell` | 原生单元格及其位置，不把请求中的 actor 转成工程对象，不把 requested input 转成答复 |
| `replyState` | `populated`、`blank`、`no_reply_column`、`unknown_structure` 四种分开保存 |
| `sourceReferences` | 仅解析原 Clause cell / owner heading 中确实存在的引用；有限的逗号缩写展开有来源 span；多目标不能伪装成已知单一用途 |
| `qualifierContextIds` | 同一已确定 scope 的原条件、例外、续段、交叉引用单元；记录哪些缺失或未能装入 |

同一原 row 被重叠 Part 或多个 chunk 包含时，先按原 block/cell 身份去重，再保留所有实际提交的覆盖证据；不把重复抽取当成独立来源。若一个 native cell 横跨切片且本轮未完整覆盖，单元标为 `partial_source`，不能向模型展示为完整回复。

只有 `populated` Reply cells 可以成为项目回复选择候选；`blank` 与 `no_reply_column` 仍作为原始状态进入比较。Tender/standard 可选择的值来自原文单元；标准模板占位符仍是标准模板，不变成项目已填写值。若结构无法证明列含义，不将它冒充 `project_reply`。

## 3. Stage 1 只选择 ID 与原值

模型 schema 的每项仅含两字段，`additionalProperties=false`。`unitId` 使用本轮候选 ID 的 enum；`rawValue` 是该单元中的非空原文子串。不得输出 chunkId、来源角色、条款号、purpose 或重建引文。

```json
{
  "unitId": "<one of the submitted immutable unit IDs>",
  "rawValue": "<one contiguous verbatim substring of that unit>"
}
```

选择提示只规定通用行为：查看原请求、原单元及上下文，分别选择明确的值，不把不同用途合成一个字段，不推断 blank/no-column 中的答复。提示中不出现案例正确值。选择数量上限属于实际 schema/参数，先固定并登记；达到上限记录 `selection_cap_reached`，不能声称已抽全。

程序对每项执行以下步骤并保留全部原始返回：

1. 解析响应，检查 JSON、schema、字段数、ID、长度、非空白；超限或 `done_reason=length` 单独记失败/不完整，不截断后冒充有效数组。
2. 从 manifest 解析 unit，验证 hash、原 role、实际提交范围及 `rawValue` 的精确连续子串。项目答复必须落在 native populated Reply cell，不能来自 Required input 或另一行。
3. 若值在同一单元出现多次且无法确定位置，返回 `ambiguous_value_span`，不默认取第一处。不要通过大小写、标点或模糊匹配“修好”原值。
4. 由程序生成 `valueSpanId` 和原 UTF-16 范围。原 row 足够短时用完整原 row 构造 quote；否则选择包含 value 的连续源范围并保存范围规则，不拼接不相邻文字。短值可用周围原文满足最小引用长度，不能填充新文字。
5. 引用必须能在实际提交的 chunk/Part 和原 block 同时定位。若引用长度、单元范围或模型输入预算无法满足，返回 `quote_context_unavailable`，不得修改原文。
6. 将 reference、role、chunk、Reply state、quote、native cell spans 从 manifest 附加到字段；一个 row 的多个引用仍保留为多个源提名，**不声称用途与某个子条款已语义对应**。

成功字段的状态叫 `source_bound`。它只说明原文及来源关系通过验证，不叫 `semantically_correct`。例如一个 populated Reply cell 可能同时包含几个对象；程序不能仅凭 cell 正确就证明模型选的是同一个对象或同一种义务。

## 4. Stage 2 明确比较对象、用途和条件

比较输入包括全部原 source excerpts、请求/回复状态、source-bound fields、其可回放 span，以及缺失范围账本。模型看到的字段标记为“来源已绑定、对象/解释未验证”。未选择或拒绝的项仍在完整原文中，不能被理解为缺失。

首轮原型的 Stage 2 沿用旧 vetting 记录 schema；第二版使用 `{unitId, rawValue}`，第三版改为紧凑 whole-unit ID 与模型关系主张。下表仍是后续拟实施的原子属性及 span schema，尚未完整实现或实测；目标是先返回局部比较观察，再由适配器生成现有 vetting 记录：

| 字段 | 规则 |
| --- | --- |
| `leftSpanIds` / `rightSpanIds` | 使用实际 source-bound 或原文 span ID；每侧保留 role、原 reference 和对象上下文 |
| `objectAndPurpose` | 模型说明它认为比较的是何种对象、用途和属性，附支持该识别的原上下文 span；没有支持则 unknown |
| `relationship` | `match` / `difference` / `unknown`；不能把 different strings 自动当冲突 |
| `differenceKind` | 值差异、条件差异、义务差异、对象不同或未知；与结论一致 |
| `conditions` | 原触发条件、例外、时间类型、范围与交叉引用是否齐全；每项 `supported` / `missing` / `unresolved` |
| `adoptionBasis` | 区分 tender、project reply、template/standard；标准对应模板本身不是采用依据 |
| `scopeLimit` | 明确局部观察支持什么、哪些尚未判断；不从一项地址/时间匹配推导合同整体有效或无风险 |
| `evidenceSpanIds` | 原引用 ID，程序输出真实 verbatim quote；模型不手写完整引文 |

通用提示要求按 **同一对象、同一用途、同一属性及兼容条件**比较。联系人地址、资料位置、图纸查阅处等不同用途不能仅因都是地址而互相替代；请求 actor 与请求对象分开。已填回复、空回复、没有 Reply 列各自保留语义。标准版本日期、文件版本和待查阅对象的版本不能默认相同。

当前机械校验覆盖连续原文定位、来源角色、已填 Reply 或已选原值的完整范围、必要 tender evidence 与 distinct anchor 等条件；对象、用途、条件和采用关系仍需独立源审。同值仅记录 span-bound warning，因为旧 schema 不能确认比较的原子属性，不执行同值硬拒绝。

后续新 schema 拟检查以下必要条件，失败时保留原 claim 并记录原因。以下对象关系与属性门禁不能冒称本轮已实现或已验收：

- 引用 ID、定位范围和 role 必须真实；引用至少满足当前生产证据门禁。不得通过新 runtime 绕过 distinct anchor、unresolved 或 comparison basis 检查。
- `differenceKind=value_difference` 若两侧原值经已登记的最小空白规范化后相同，拒绝为 `equal_values_claimed_different`。不全局禁止同值的条件/义务差异，但它们需要各自的原条件证据。
- 若两侧对象/用途识别无支持或互不对应，状态为 `object_purpose_unresolved`，不生成冲突候选。
- 精确时间、最小/最大时限、触发条件、书面程序与 recipient 等不同属性各自比较；数量不同并非必然矛盾，兼容最低要求不能自动报冲突。
- 请求不能单独充当项目答复，template 的占位符/版本也不能单独充当 tender 采用事实；错误 role 推断记 `request_as_reply` 或 `template_as_adopted`。
- “某值缺失”必须限定实际检查范围；已经给出的 populated reply 不能仅因 Stage 1 未选到而称缺失。未检查来源不能列为一致支持者。
- 限定、完整续文或关键 xref 未给齐，保持 `qualifier_unresolved`，不能把 `consistent` 提升为整体通过。

已实现的机械检查以及后续属性检查都不能自动证明所有对象关系或结论蕴含成立。最终节点交付待独立源审的结果；人工/独立源审另追加 evaluation 事件，不能把机械检查状态复用为质量验收。

## 5. 节点、状态与失败账本

| 节点 | 成功状态 | 主要失败/未知类型 | 副作用 |
| --- | --- | --- | --- |
| 冻结输入 | `input_frozen` | `input_hash_mismatch`、`foreign_scope`、`missing_original_source` | 保存 manifest、源/参数/代码指纹；零模型调用 |
| 单元构建 | `unit_inventory_verified` | `table_structure_unknown`、`source_span_mismatch`、`partial_source`、`reference_ambiguous` | 保存 unit inventory、Reply 状态和范围账本 |
| 选择调用 | `extraction_response_saved` | `gateway_error`、`timeout`、`response_decode_error`、`schema_invalid`、`output_incomplete` | 原 request/response bytes 与 status/时间/token 立即保存；不重试 |
| 选择验证 | `extraction_source_checked` | `unknown_unit`、`blank_value`、`value_not_verbatim`、`value_not_reply`、`ambiguous_value_span`、`selection_cap_reached` | 分开 accepted/rejected；拒绝不删除原文 |
| 比较组装 | `comparison_input_frozen` | `budget_unverified`、`required_scope_dropped`、`qualifier_unresolved` | 记录全部提交/缺失 ID，不静默裁切必要原文 |
| 比较调用 | `comparison_response_saved` | 同 gateway/解析失败类型 | 一次调用，不 retry/repair/judge 循环 |
| 证据与关系检查 | `comparison_checks_complete` | 当前原文定位、Reply 范围及必要证据门禁；对象、用途、条件和采用关系保留待源审；同值只 warning | 保存所有原 claims、通过/拒绝原因及对照值范围；属性门禁另列为后续设计 |
| 源审交付 | `ready_for_independent_source_review` | `scope_not_evaluated`、`call_failed`、`evidence_rejected` | 输出待审候选、局部观察和 unknown；不自动标质量通过 |

节点还记录 `pending/running/completed/failed/skipped` 执行状态。执行完成与源语义正确是不同字段。某 scope Stage 1 失败时，先按冻结策略决定是否跳过 Stage 2；策略不能运行后挑选。选择数组为空而响应合法时，可依预先策略让 Stage 2 读取全部原文一次，但明确 `no_source_bound_selections`。任何分支都不增加调用上限。

不能把缓存重播称新模型调用。恢复运行只读取既有落盘响应并重跑纯验证节点；若真的要补调用，用新的 experiment ID，并明确旧轮失败。中断、预算不够或调用数未达计划都保存 `actualCalls` 和未评估 scopes，不自动补齐。

## 6. 日志、回放与本机资源约束

每个节点追加不可变事件；原始响应、被拒绝字段和错误结论仍保留。实验日志沿用 `experiment_log.py`：同 ID 的 parameters/input fingerprints 不变，后续 audit 只追加 evaluation/artifacts，pending 文件不作为完成证据。保存实际 frozen runner 与 validator 源 SHA，防止后续修 validator 后混入旧结果。

最小回放产物包括：

- `run_manifest.json`：实际模型名称/digest、schema/system/user/options 指纹、source/unit manifests、调用计划、上限、超时、retry=0、token 预算和实际采样环境；未知值为 null。
- `units.json` 与 `unit_source_checks.json`：原 cell/Part/block 范围、header、Reply state、source reference、sourceHash 和构造规则。
- 每次 `request.bin` / `response.bin`、HTTP status/headers、时间、实际 token/stop counters；先落盘后解析。timeout/错误响应照样保留，不声称 stop 成功。
- `selection_checks.json`：全部原选择、accepted/rejected、精确 value/quote spans、失败类型。
- `comparison_checks.json`：全部原观察/claims、quote/role/同值/对象/条件检查、候选与 unknown。
- `coverage.json`：本轮提交范围、必要组、遗漏、cap、未调用及未评价项；不存在用 `[]` 换算正确的规则。
- `evaluation.json`：独立 source-grounded item 结果及证明，不传给模型。没有完整标注范围时 accuracy/precision/recall 为 null。

4060 上一次只运行一个模型请求。原型读取冻结 packs，不同时加载 OCR/embedding/rerank 新任务。模型、上下文、输出预算先固定为明确基线；改其中任何项用新 experiment ID。采样日志记录实际并发进程，整机 GPU 观测不能称模型独占峰值，更不能外推 H800 性能。

预算检查需要实际 tokenizer 或服务可验证 token 信息；字符估算不能证明没有截断。若无法证明必要原文和 source-bound fields 全进入上下文，标 `budget_unverified`。不为满足预算删去合同条件或邮件空回复，不让源文本随模型窗口而改变。

## 7. 下一轮最小实施顺序和验收

以下是可重复的实施顺序。目前步骤1至4已完成首轮原型与限定调用；步骤5的独立语义审核另行留档，步骤6的生产接入、完整案例及H800验证仍待后续推进。

1. 先实现离线 `build_units` 与 selector validator。复用冻结 corpus/native cells，逐条验证所有生成单元的 UTF-16 span、role、reference、Reply state，不发模型请求。
2. 用合成的错误 selector records 检查 wrong ID、跨行 value、空白、重复位置、blank/no-column、header 歧义、超限和 Unicode 边界。这些是 runtime 可靠性验证，不是合同质量分数。
3. 冻结三个已有 source scope、完整 unit inventory、通用 prompts、schema 和固定调用参数。所有候选来自原文，fixture 不参与构造。运行前登记计划最多六次/重试零。
4. 在授权后串行真实运行。全部原选择与原比较都进入账本；失败或未提及项原样保存，不人工补答案、不挑最好响应。
5. 按独立源 fixture 比较：来源绑定、对象/用途、已填/空/无列回复、采用依据、同值误报、条件/xref、局部结论与未知各自记录。未提及原子项仍 `not_evaluated`。
6. 只有局部源审有支持、必要覆盖完整且误报边界可接受后，才评估接入 production。全 44-source、16-topic、OCR 扫描件和 H800 验收是后续独立任务，不能从三个局部 packs 的运行完成推导成功。

第一轮验收要回答的是：模型是否选到正确原单元，程序是否绑定真实值，Stage 2 是否比较同一对象并保留条件，反例是否被识别或合理留为 unknown。缺陷数量、候选数量和 `consistent` 数量不作为质量代理。

## 8. 来源结构最小原型：仅离线准备，尚无模型结果

`tools/vetting_eval/structural_scope_runtime.py` 与其独立 synthetic suite 已完成第一次实现。2026-10-02 实际运行 `python -m unittest test_structural_scope_runtime -v`：22 tests、0 failures/errors，suite 0.060 s，统一工具观察 `5d6826`；未单独捕获原始 stdout/stderr 字节，证明中明确保留该边界。随后 `tmp/prepare_structural_scope_20261002.py` 实际退出 0，耗时 2.704 s，仅注册和写入新的离线产物，没有模型、HTTP、OCR、RAG/index、数据库或采样调用。

不可变准备目录为 `tmp/structural_scope_runtime_prototypes/source-structure-7b-20261002T080706656512Z`。`offline_preflight.json` SHA256 为 `c5056045e143a82b24b2ee27b2ca3858b22244083a45471ad55d82164116cacc`；`runtime_manifest.json` SHA256 为 `e778230d6e19d7fbfea27ae965bec1068ce6f95b95e2b7bb7831268a3832e207`。旧 `request_task_runtime.py`、所有 actual responses、catalog 与历史冻结产物均未修改。软件验证与 `prepared` 状态分别追加不可变 experiment-log 事件。

原型读取旧 B 组冻结的 source bindings/native metadata，不读取 Gold 矩阵或独立逐项答案。通过旧 source-only catalog 构造函数重建并逐对象比较，保留全部 214 units、139 Parts、120 cells 和 23 chunk occurrences；本轮新增 451 个连续片段。原 whole sources、空/无 Reply 列、多来源版本、未选片段与条件仍存在，不靠删除材料缩短上下文。

程序仅将 source owner、可见编号、原表格 Clause/Required input/Reply 列与显式 `For <reference>` 文字组织成来源范围。解析器给出的 heading location 加文字特征只是 classification hypothesis，不是独立语义证明；title/body 同一 row 仅限制标题行作 core，正文和整行保留。数字父项未出现时不由匿名字母子项补成 `(1)`。多 reference 回复只将显式标签所在原行路由给相应 branch，共享限定和无标签后文保留为 support context、关联未知，不按 caption 猜对象/用途。

schema 要求选择源内 fragment IDs，程序从原 UTF16 范围生成连续原文证据；标题、页眉、Clause、Required input、空 Reply 和无 Reply 列均不能充当 populated Reply value。记录分别保留属性主张、两侧 value、对象/用途证据、条件证据、adoption 证据和 relation/differenceKind。机械定位通过不认证完整属性、对象/用途或采用；同值只产生有界 warning，不删除其他条件/义务差异的原 claims。null 不表示缺失，全部拒绝或空数组不表示正确。

本轮原始 request/ref 结构动态推导出 5 个分支任务；实现没有写死任务数、属性名或评测字段数。准备中的 recipe 是已缓存 `qwen2.5:7b-instruct-q4_K_M`，digest `845dbda0ea48ed749caafd9e6037047aa19acfcfd82e704d7ca97d631a0b697e`，`num_ctx=32768`、temperature 0.2、`num_predict=4096`。model/digest 输入可替换；该 recipe 是准备配置，当前没有新 PS、placement、token 或响应结果。当前 revision 的 native executor 明确禁用，实际 attempts/finished calls 均为 0；未来若单独授权，计划每任务一次、串行、retry0。

五个 frozen request 分别为 81,362 / 50,451 / 50,531 / 50,495 / 34,636 B，messages 分别 58,658 / 36,280 / 36,338 / 36,322 / 23,624 UTF16 characters；schema 分别为 17,642 / 11,130 / 11,146 / 11,130 / 8,714 B。每任务最多 4 observations、每侧每个支持维度最多 3 fragment IDs。输入/输出预算均为 `unverified`；这些字符与 schema 大小不证明无服务截断或完整覆盖多目的请求。

该原型是 source routing、schema、prompt 与程序证据呈现的多因素变化，仍是可选对照方案。3B/7B 的失败不能证明复杂 agent 架构必需，也不能把该原型的 5 tasks/新 schema 与旧 4 tasks/旧 schema 冒称公平 workflow-only 对照。后续应以相同来源、动态任务及最终 schema 构造 plain 与 minimal 两种呈现，再与 H800 大模型交叉比较；实际语义质量继续由独立源审判定。
