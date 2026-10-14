# OER 对 Vetting／Advisory 的质量归因：只读证据核对

日期：2026-10-14，Asia/Shanghai。范围是已有来源、输入供应、调用与答案证据的离线核对。没有新模型／GPU／API 调用，没有打开活动数据库、启动或切换服务，也没有修改 OER 训练、固定05、Advisory 升级或冻结 harness。新写入仅为本研究记录。

## 当前判断

目前不能用“Vetting／Advisory 效果不好”直接选择 OER 训练或 27B 微调。已保存证据至少包含三种不同根因：来源已存在而输入供应遗漏；完整来源已给但生成误解条件；原件结构／字段语义仍有独立未通过项。应先固定某次实际版本，再逐项寻找错误首次出现的位置。

归因核对的优先顺序：

1. **实际来源是否到达模型**：原件有内容但 canonical 缺失是解析候选；canonical 有内容但实际 wire 缺失是检索、结构扩展或打包；正文到达后答案才错误是生成／提示／运行配方残差。
2. **本轮是否实际审查这些内容**：Vetting 的 pending、not_submitted、partial 不能记作模型审查错或漏；进度 completed 不证明法律覆盖。
3. **给足已核必要来源后能否完成**：首先核生成预算与正常终态，然后比较同源的提示组织／模型。未经同输入受控对照，不把生成残差升级为基座能力上限。
4. **来源本身的语义质量和独立泛化**：字符、合法 JSON、完整传输不证明表单状态、条款归属、例外与跨页关系正确。当前 OER 开发候选未通过的门继续保留。

## 正在进行的 OER 工作和下游身份

读取时中央规则最新入口为 R157。当前语义表单分支完整七页包含 57 字段、74 写区、11 文档关系。已核写区定位 31 correct／43 unknown；写区 state 与 visibleValue 各 1 correct／73 unknown；57 完整语义字段仍 unknown；11 关系 2 correct／9 unknown。不同观察不能合成一个准确率。R157 是 CPU 规则／来源复核，0 模型调用、0 新训练更新；此前训练拟合和协议问题仍按历史保留。

来源：

- [中央规则](../oer-scope-and-rules.md:1)，读取时 R157 当前状态。
- [独立来源计数](../../../.local/state/oer-quality-persistent-20261013/semantic-field-alignment-01/label-led-writing-regions-source-review-03/source-summary.json:97)：`wholeSourceQualityAccepted=false`、`normalFinalParserAccepted=false`；116–117 行为 `candidateAdopted=false`、`sourceFamilyGeneralizationAccepted=false`。
- [Root 结果](../../../.local/state/oer-quality-persistent-20261013/semantic-field-alignment-01/label-led-writing-regions-root-result03.json)：实际闭合状态、同域计数、候选未采用及下一步。

这支训练／规则工作正在补字段类型、填写状态、值、写区与归属关系；它不等于在训练 Advisory 的事件／条件推理。它尚未整体采用，不能把旧 Vetting／Advisory 失败归为这支新候选的效果，也不能期待该分支天然修复检索预算或 MiniMax token observer。

固定05发布与开发候选分开：中央规则的 `public-split-delivery-20261013` 块记录冻结包发布／字节恢复，活动 Local05、H2、模型服务、Drafting 和主 dirty 工作区没有据发布而切换。包可恢复不是下游足证或业务验收。

## 已定位的输入供应失败：旧 Q1.6

2026-10-10 首次实际 retrieve 返回 3 个单元：GCC17.1 标题、GCC17 总标题、SCC17。GCC 必要程序正文没有随标题返回；`complete_observed_unit` 只表达标题单元组成，`qualifiersComplete=unknown` 和 `semanticScopeVerified=false` 没有被提升。

来源：

- [实际 retrieve 核验](../testing/oer-official-q16-actual-retrieval-readonly-validation-20261010.md:35)，35–45 行记录返回身份与语义覆盖边界。
- [标题／正文闭包诊断](../testing/oer-official-q16-title-body-closure-diagnosis-20261010.md:7)，7–20 行定位消费责任；26–41 行给出固定池重放。

当时 canonical 已有 30 个 GCC17.1 正文 whole units，并登记 30 条正文到标题的 typed parent 边。actual probe 为 candidate24／top3；`SourceUnits` 只按相同 clauseId 或 observed block 闭合，结构消费没有自动沿跨单元父子边带入正文。完整 payload 没有在 HTTP／Java 接缝丢失。

固定实际 24 候选重放：K3 覆盖正文 0／30；K10 为 5／30；K20／24 为 13／30，其余 17 不在池中。沿已登记可信 parent 边的离线 context prototype 可覆盖 30／30，但只是观察正文供应；没有给法律闭包、答案或全源质量授分。

**归因**：该次正文缺失有候选预算和结构消费的直接证据，不能全部算 parser 或 LLM 能力。也不能把 probe24／3 误说成所有正式服务默认配置。

## 给足正文后的生成残差：2026-10-11 Q1.6

独立 review-04 核对实际 generation wire、保存答案与前端。53 个冻结 unique source 单元的完整 ordered parts.text 已进入 prompt；17.1(12)、17.1(16) 两页正文和限制都在实际输入。厂商报告 input83,868／output2,647；configured cap16,384，正常 completed，未见输出预算打满证据。

来源：

- [不同作者核验04](../../../.local/state/oer-q16-answer-independent-audit-20261011/review-04/Q16-INDEPENDENT-ANSWER-REVIEW-04.md:9)，9–14 行为实际输入与来源边界；18–23 行为关键残差；87–90 行为可对照改进因素。
- [terminal receipt](../../../.local/state/oer-q16-answer-independent-audit-20261011/review-04/terminal-receipt-04.json:6)：`input53CompleteAndProviderSavedFrontendExact=true`；64–72 行保留缺文排除、生成错误、独源／业务未通过。
- [wire 来源比较](../../../.local/state/oer-q16-answer-independent-audit-20261011/review-04/actual-wire-source-comparison-04.json:3)：冻结53／dictionary57；49–50 行核 GCC 正文成员；77–78 行核 SCC 正文成员。

独审固定45项为25 pass／14 partial／6 fail，coverage ledger32.0／45；这不是准确率或比赛分数。关键残差包括 timely Notice 路线反接至 finality、报告／证据／appoint-failure 等条件遗漏，以及 SCC 正文在[3]却引用仅有标题的[1]。

**归因**：上述具体错误不能再解释为正文没有送入或输出 cap。它们支持“生成层残差”，但还没有隔离提示组织与基座能力。该次输入约83.9K reported tokens，大部分为结构／身份字典；简化 model-facing 表示和结构化流程检查仍是未验证因果因素。source-only 独审不认证整份原件法律采用，`humanConfirmed=false`、独立来源家族泛化 false、业务验收 false。

## Vetting 的旧零发现没有可用模型成绩

旧两源 job `97ca407a-1fa3-48b0-a1d2-bc884e7574ab` 保存终态 COMPLETED，进度19／19，finding0；17 semantic topics 均 completed_empty 和 partial。119 个 observed source requests 全 pending；唯一 submitted chunks46，SCC22／22、GCC24／50，只覆盖已采用的局部来源。

来源：[两源终态诊断](../testing/oer-official-two-source-vetting-completed-partial-20261010.md:25)，25–42 行。

119 是 planner observed source requests，34 ranked observations＋85 structural units；它们不是模型119次工具请求。17 个 packet 均 `no_bound_token_observer`；`minimax-cn` 的 observer 为 null，完整输入预算保持 unknown，补充组不额外 dispatch。全局 legacy 调用仍可以在预算 unknown 时完成为空。

**归因**：补充包未提交的直接原因是 harness 预算绑定。该 run 未建立缺陷 gold 分母和法律语义评分，不能算 model accuracy／precision／recall，也不能据 finding0 宣布无问题。它同样不证明 M3 token 已耗尽。

另有原件结构与模型共同失败的真实例子：HOMES 下一附录 Subtitle 曾继承上一 clause owner，模型据此把下一附录标题当上一表页脚；机械引文门接受1个 issue，独立原件审查 confirmed0／false1。来源：[Vetting 任务证据](llm-vetting-rag-task-evidence-20261012.md:87)，87–101 行；完整 source 源核位置见该文93行。此例证明“引用存在”不能替代角色／章节scope正确，也不能把这个已审单例外推全库 precision。

## 刚升级的 Advisory：使用新版本入口

用户已明确新版正在测试。本次归因不把旧版清空 insufficient 正文、旧提示或旧测试分数当作新版行为。

当前升级与比较的证据入口：

- 实施 worktree：`C:\Users\Administrator\.codex\worktrees\oer-advisory-next\ConSense`。
- 测试根：`C:\Users\Administrator\Documents\Codex\advisory-alignment-20261014`。
- 固定输入：`model-comparison-01\tooling\snapshot\generation-request.utf8.json`。
- 来源独审：`model-comparison-01\evaluation-source-only-01\source-identity-receipt.json` 和 `private-source-analysis\literal-packet-availability-review.json`。

本次只读核到 availability review 的38个 sourceAvailabilityRows 全为 available，10个 exact literal source spans；`sourceDerivedCorrectionSentToGenerator=false`、`officialAnswersRead=false`、`modelCalls=0`、`serviceCalls=0`。这是本轮选定来源义务在实际包中的可得性，不能推成全合同或完整 OER 质量通过。identity receipt 仍保留 `sourceQualityAccepted=false`、`qualifiersComplete=unknown`、`projectApplicability=unconfirmed`。

Root 另行核对新版16K模型比较终态；32K补充比较仍在进行时保持 pending，不在本研究写成通过。即便正文可得，新版最终 schema、flowchart、语义支持、界面交付和独立泛化仍需各自核验。

## 最小归因方法

按每个业务主张留五个可核对点：原件位置与含义、canonical内容与结构、实际wire内容、原始回答、接收／评分结果。对每一项记录 first_failure_layer 和 unknown，避免只留一个总 FAIL。

优先复用正在测试的新版本和已封 wire，不启动第二套活动训练或模型。先从已核足证的条件／例外案例及含缺证的配对案例检验新 harness；再以同一正确来源包比较模型。若 canonical 从原件漏字或归属错误，单独 source repair 对照；若 canonical 正确而 wire缺失，修供应；若 wire正确但 raw错，用提示／配方／模型对照；若 raw正确但产品错，做固定raw离线重放。

本次没有重新跑 parser、读取活动数据库、调用教师或本地大模型，未改 scope／handoff／旧日志。
未知仍包括新版完整业务验收、各层错误比例、独立来源泛化，以及正在进行的 OER 候选最终采用效果。
