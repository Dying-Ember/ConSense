# Vetting Mini06 与完整修复输入回归

日期：2026-10-20，Asia/Shanghai。状态：Mini06 真实业务失败保留；后继 07 已完成正式隔离 Maven package 与逐身份验票，运行部署及后继真实模型结果分别以实际运行收据为准。

本记录属于[三模块端到端台账](three-module-e2e-20261020.md)。测试范围沿用当前 OER 规则：标准空模板不作审查对象，图纸与计价资料保持排除；未改变解析、来源采用或证据完整性门。

## 同源真实 Mini06

第五轮测试包 `20f89cdc7641c3d106f461cc7b642fc6c5b3c4181d0a9eb499cb742467261fd0` 下，只启动一次 Mini06；没有上传、重解析或人工 provider 重试。项目 `e2e-vetting-mini-r02-20261020`，run `46b98ae7-4215-4935-86fb-cdf8ee533a14`，来源快照与 Mini05 均为 `730661c6431068f7c73a9c3e2cdbd74a7779b3148e4d3dccbf2726afb98a44fc`。原 DOCX 字节 SHA 为 `121fe9d141e684decffbbff870a62203146047f2b1be223d317483ae96821b9f`。

17 个主题的实际输入都保留原顺序 0–8，所选记录集合与 Mini05 逐值相同。run 最终 `COMPLETED`，17 包均为 `completed_empty`，0 finding，`reviewCompleteness=incomplete`。18 次实际 generation 包括第 9 主题的一次既有自动重试。逐 attempt 提供商声明的 token 合计为输入 604,420、输出 1,401、总计 605,821；这不是独立账单证据或提供商最大上下文实测。证据及逐项分母见 `.local/e2e-20261020/vetting/mini06/acceptance.json`。

第 9 主题首答并非没有识别冲突：record 0 正确引用 Clause 1／2 的 two／exactly three signed paper copies 两条完整原句。record 2 的标题为 189 个 Unicode 码点，超过当前 schema 的 160 上限；生产 `StructuredJsonValidator` 对原始完整数组重放仍得 `SCHEMA_MISMATCH`，单独检查原 record 0／1 的结构通过、record 2 失败。单项结构通过不代表来源或业务采纳。原答及实际 schema 均未修改，收据为 `mini06/actual-schema-replay.json`。

首答与自动重试的实际 `attempt.input` 完全相同：重试没有取得首答或校验原因，最终返回 `[]`，正确候选随整数组结构失败而丢失。该结果仍为业务 **FAIL**。另一个 99.8 候选把 `3 Clarifications` 当第二条实质引文；当前裸文档标题门并不等于所有编号标题都已排除。它没有正式发布，不能追认其判断正确。

## 报告稳定性真实通过的范围

首次完成、首次 JSON 导出、再次 JSON 导出及导出后重载四处报告快照都为 `d9039980992d4bcd6cc105191a0bd743c0c52d755792ba7fc7aee0e41014cb22`；两次 JSON 的完整字节相等。DOCX 与 39 页 PDF 的内部快照也相同，未进行人工复核记录写入。原件、来源集合与模型结果未改。

这证明第五轮存储时间精度修复在该真实运行上的快照稳定性。它不关闭上方漏检、空包或来源质量问题。HTTP 原票及三格式文件见 `mini06/terminal-collection.json` 和 `mini06/acceptance.json`。

## 后继 07 修复契约

修改限于 `VettingSemanticReview`、新 `VettingRepairContextTest` 与既有 `VettingExplicitResponsesWorkflowTest`。不截断标题、不改模型动作、不分拆发布结构失败数组、不放宽 schema／引用／来源／业务门、不增加两次 generation 的上限。

- 初始 packet ID、`promptEnvelopeSha256` 和来源快照继续作为 checkpoint 锚。修复 attempt 独立保存真实的新 `input`、绑定 hash 与 `inputBudget`，不能冒用初答的 token 准入。
- 保留原 full input 的 fresh recount 与 `sameFinalObservation` 拒绝门。前一实际 attempt 为可修复的 `structured_output_invalid` 且保有 raw 时，把固定校验原因与原始 raw 的 JSON 字符串作为明确不可信的修复上下文；原完整 user／来源 wire 保持为逐字相同的后缀。
- 对改变后的完整请求额外计数，所有模型／tokenizer／chat template／上下文／输出预留／估算政策等观察身份必须保持。比较仅排除合法变化的输入 hash、输入 token 数和三个实体／计数响应 hash。
- 新请求若超预算、不可观测、来源失效或 profile／政策改变，在第二次 reserve／generation 之前拒绝。generation 上限不变，但发生结构修复时增加一次真实 token-counter 请求，不能声称调用成本完全不变。
- `policyContractSha256` 包含 `rejected-candidate-repair-context-v1`，避免把旧修复政策的 checkpoint 当新政策结果。

## 回归与保留失败

新反馈用例在旧实现上实际 RED：6 项均失败。最终独立 javac／Jupiter 执行 12 类、209 项，209 通过、0 failure／skip／abort，专用 runner 正常退出码 0。包括真实 H2／MockHTTP 的 durable attempt 流程、两个 provider transport、预算／profile 变化拒绝、完整输入记录、原始候选保留、未知／超预算拒绝、无第三次 generation 和原证据门。该批不是 Maven 整树、真实模型或浏览器业务验收。

既有 `malformedFirstResponseRetriesIdenticalWireWithDurableAttempts` 两个参数化身份改为语义准确的 `malformedFirstResponseRepairsWithBoundContextAndDurableAttempts`，明确验证新修复实体、完整旧 user 后缀、schema／source pin、原 packet 锚和 4 counter／2 generation。正式集成已列出 548 个原身份精确保留及这两个显式旧→新映射，不虚报全部 550 个名称相同。先前两个旧 identical-wire 断言失败日志保留，没有删除安全门来追认通过。

初次隔离工具缺 Spring 测试依赖，未进入测试；原 compile 文件在重试时被覆盖，仅工具原票保有错误尾部，该证据缺口明确保留。之后通用 runner 已打印 209 通过但成功路径未退出；只清理核明的自有测试 helper PID，保留其身份和日志，再用任务专用 runner 取得退出码 0。未停止前后端、OER 或 H2 owner。

冻结清单及四份日志 SHA：`.local/e2e-20261020/vetting/round07-repair-context-ready.json`。独立 Advisory 窄审为 0 Must，三源码及日志 SHA 绑定见 `.local/e2e-20261020/advisory/vetting07-peer-review.json`。

## 正式 Round07 整合构建

从已验票的 Round06 冻结树出发，只叠加上述3个 Vetting 文件及独立 peer 通过的2个 Drafting 长段落识别文件，共3处 changed、2处 added。1484个源文件聚合 SHA 为 `aecec4f19ec662391cf74cc55541b0ae9d68c55d723bab905a6417a83d4296ee`；所有文件构建后逐项大小／SHA 不变，没有测试创建的额外非 target 文件。

正式51 selectors 在独立 TEMP、16GiB heap 运行 Maven package：51 suites、808/808 passed、0 failure/error/skip，`BUILD SUCCESS`，退出码0。808个唯一身份与“旧06身份减2个旧名加2个明确映射的新名、独立 regex green02 的270项、6个新 repair cases”的集合精确相同。原550项不变量全部保留，其中548个名称完全相同，2个使用上方明确映射；新增258项不包含两处更名。270项与原550有18项重叠，不能把两批数量直接相加。

三份实际 NTT/SCT/SCC 标准 DOCX 的完整 parsed-document JSON、逐段 lexical decisions、完整 private template index 共9文件在正式整合中再次与冻结基线完整字节相同。该项是来源输出不变与性能修复回归证据，不是新模型/OCR或前端业务验收。

正式 JAR 为 `.local/e2e-20261020/build/round07-project/target/consense-service-1.0.0.jar`，80,156,448字节，SHA256 `72d818409d1bcded40a5e44cb9166af24d513636cd3bb02c46d8a803142214b3`。[构建收据](../../../.local/e2e-20261020/build/round07-build-verification.json)包含逐 suite、XML hash、case identity 文件、来源清单与9项完整文件比较。构建前一次相对 baseline 路径校验拒绝发生在 Maven 启动前，另存 `round07-integration-preflight-rejection.json`，随后使用准确绝对路径；未覆盖实际失败记录。

正式 stdout/stderr 均保留。stderr 包含一条未归属的命令路径诊断及测试主动拒绝 DOCTYPE 的解析消息；正式进程退出、全部 XML、产物和源 hash 的验票均成功，不能把它表述为 stderr 完全无诊断。整合构建没有调用应用 API、真实模型或本机 OER，没有修改共享 target 或自行部署。

编号行及99.8缺失结论的后续边界另见[只读调查](vetting-heading-and-absence-frontier-20261020.md)。原件没有标题 style/outline 声明、实际 canonical/wire 也明确 unverified，本轮没有凭编号或短度新增标题分类规则，该未知没有被808项通过关闭。

## 原广回归失败身份

原 Vetting 22 条失败／错误已逐 identity 映射：16 条在既有完整选中类复验中关闭，6 条仍为本机 H2 文件只读 `90097`。关闭的 16 条包含 15 个 test invocation 和 1 个 `BeforeAll` 类设置错误，不能称为 16 个独立方法。复验分母是 115 started = 109 successful + 6 failed，0 skip／abort；与原 22 分母重叠，不相加。

原 runner 只打印成功总数与穷尽失败清单，正向逐项状态由完整类运行及零 skip／abort 推导，不伪造逐例 PASS XML。每条原 XML／hash、编译方法存在证据及对应日志行见 `.local/e2e-20261020/vetting/broad-round02-identity-closure/receipt.json` 与 `mapping.md`。独立 SQL owner 恢复成功不替代这六条文件数据库测试。
