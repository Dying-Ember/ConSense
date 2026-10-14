# Vetting 能力核查历史稿（本轮范围已纠正为 Advisory）

日期：2026-10-14，Asia/Shanghai。状态：**历史核查，本轮不实施；原待决问题已作废**。

用户已两次明确纠正：本轮补齐的是 **Advisory**，产品经理确认的合同咨询提示词也属于 **Advisory**。当前访谈与能力对齐入口为 [Advisory 能力对齐](advisory-capability-alignment-20261014.md)。本稿保留纠正前的 Vetting 源码核查事实；下文的本轮范围描述、转化建议和 Q1／Q2 不构成用户采纳或实施要求，不再等待这些问题的回答。已确认提示词不能因本稿而被写入 Vetting。

本稿最初按 Vetting 核查能力差异；用户随后明确纠正目标及已确认提示词均属于 Advisory。本稿核查过程没有修改业务代码、运行模型、操作数据库或切换服务，后续工作以 Advisory 入口为准。

## 依据与版本

- 产品经理确认的新 [合同咨询提示词 v1.3](inputs/contract-advisory-system-prompt-confirmed-20261014.md) 已原字节保存为需求输入。SHA256：`ec23c7a665f803598a82d8aaf41ace65df1e431ac5e4ab24610e61d11ccf0470`。用户已确认其专家方法与边界，本轮不重新提问是否采纳。
- 审查源码事实以已公开交付的后端 `9f1e785c4ecfcb816513fe45486fd38fb5d8e49e` 为固定基准；对应独立 clone 已核 clean。相关前端交付基准为 `d4939eca9ec68ec44e0c1302e4c4f3f287dc1d0c`。
- 活动主目录 HEAD 为 `285fc2e3bdf97792aeae00a31dbde2c69ebcde60`，有大量保留中的未提交内容，并与上述交付版有实质差异。不能仅凭主目录文件缺席把已交付能力判为未实现，不覆盖或清理活动目录。
- 范围以 [已确认范围规则](../oer-scope-and-rules.md) 为准；复用 [当前开发规格 D8–D11](oer-vetting-advisory-next-development-20261012.md)、[不确定性局部化 ADR](../adr/0001-localize-uncertain-review-evidence.md)、[模板隔离 ADR](../adr/0003-separate-drafting-templates-from-vetting-advisory.md)。
- 术语记录于 [ConSense CONTEXT](../../../CONTEXT.md)，与已有 GLOSSARY 的审查检查项、发现、依据及咨询入口含义保持一致。

## 纠正前的边界核对草稿（本轮不据此实施）

1. 本轮主体是 Vetting；不将合同咨询的 T1–T8 问答模板或答案 JSON 直接套到审查流程。
2. 专家知识组织审查、发现遗漏和核对条件，不补造项目事实、采用版本或角色权限；机器不取得批准、指令、认证或裁决权。
3. 按正式标题、简称、实际内容、项目、角色、状态及版本识别文件，不硬编码样板目录，不因同名就混合标准模板与项目稿。
4. 未填 NTT／SCT／SCC 模板只供 Drafting；实际项目同名稿仍可审。图纸、计价文件、PRE 以外项目工程技术 PSS、SL 非技术部分保持排除；合法正文中的相关机制及引用不因关键词误删。项目 PRE 与 SL PRE 分开。
5. 官方评价答案、countercheck、样板问答和流程图保持同题隔离，不能成为生产合同证据。
6. 固定参考、目录、项目事实与待审条款分别处理；缓存和哈希不证明项目采用，版本变化不得把旧结果重绑为新结果。
7. 未知可界定时只阻断依赖该证据的判断，继续可靠部分；无法界定影响才整体标不完整。缺证不等于无问题或不适用。
8. 自动检查至少有 checked-pass、issue、insufficient-evidence、not-applicable、not-completed；执行完成、零 finding、空数组和 HTTP 成功均不等于通过。
9. 人工 checklist 与自动检查独立，默认未核对；自动无问题不能写人工 fulfilled。报告绑定同一来源及结果快照。
10. Vetting→Advisory 只是可编辑问题入口；明确提交后独立取证，不自动调用、裁决或回写。
11. 已撤回的风险数量上限不恢复；按完整证据与实际预算分包，不能静默截取问题数量。

## 现有能力，应复用而非重建

以下代码行号均基于已交付后端固定提交，仅为源码与测试阅读，未作为本轮执行通过的声明。

| 已有能力 | 证据 |
| --- | --- |
| 确定性检查＋检索语义审查 | `VettingRuleEngine.java:53–70`；`VettingSemanticReview.java:713–749`。已有主体、触发、范围、措辞及采用／例外分析。 |
| 多方原文引用及适用性门控 | `VettingSemanticReview.java:459–537`；要求实际提交且可定位的实体依据，固定标准不自动证明采用。 |
| 项目事实空白、请求与回复分开 | `VettingProjectFactTable.java:44–101`；`VettingProjectFactTableTest.java:9–20,43–61,85–90`。空白不默认否。 |
| 局部缺证及空结果不算通过 | `VettingSemanticOutcomeTest.java:14–27,46–58,117–125`；`VettingPacketCoverage.java:30–36`。 |
| checkpoint、来源变化阻断与历史保留 | `VettingCheckpointService.java:50–101`；`VettingService.java:355–390,612–627`。 |
| 人工核对独立及冻结报告 | `VettingChecklistService.java:36–76`；`VettingReportPresentation.java:24–69`；`VettingReportWriter.java:223–272`。 |

`DraftPrompts` 中的旧助手、数量上限及“未知不输出”文字，不能替代实际 `VettingSemanticReview` 调用链的事实核对；当前能力判断基于真实使用的链路。

## 六类能力差距

| 差距 | 固定基准事实与最小复用位置 |
| --- | --- |
| 身份 unknown 未完全隔离 | `OerSourceRouting.java:67–92,106–152` 主要看前四个非空行标题；NTT 仅识别 Notice 而未覆盖 Notes。`VettingCorpus.java:109–123` 未确认角色可能默认 tender。复用既有 identity、routing candidate 和 module exclusion，区分已确认与候选／unknown。 |
| 专家审查要求未形成可核对结果合同 | `VettingSemanticReview.java:61–85,713–749` 有固定主题和通用 comment／impact／suggestion，但没有按相关检查表达权限—程序—实体权利、多轨时限、费用／EOT等分析是否完成。扩展现有 assessment，不能只替换角色首句。 |
| 修订、采用与时点缺追踪关系 | `VettingReviewPackBuilder.java:322–343,390–398`、`VettingSemanticReview.java:663–692` 有版本与文字匹配，但不证明完整法律采用／修订链。复用原文引用、source identity、link trace和 unresolved references，保留真正未知。 |
| 覆盖仍主要为 topic／packet | `VettingPacketCoverage.java:30–36,59–63` 与 `VettingService.java:478–494` 记录输入／执行覆盖，没有逐应审项回应完备性。需要区分“材料已提交”与“适用检查已执行”，保留人审独立。 |
| 缺证缺少可操作的待确认对象 | 已有 insufficient_context，但未明确每个必要事实／权限／版本缺口影响哪个检查、下一步需要什么。复用 existing assessment／rejection／coverage，避免未知被笼统 warning 或省略吞掉。 |
| 项目语言检查缺正向准入 | `VettingSemanticReview.java:477–485` 对语言 issue 保守拒绝，因为项目新增／改写来源未确认；`VettingLanguageOriginGateTest.java:28–54,74–78` 只证明阻断。需来源证明的放行路径，不能凭模型声称项目改写就解除。 |

## 新提示词转化为审查方法

| 咨询模板 | 审查维度；不要求每条款输出八份问答 |
| --- | --- |
| T1 | 提交物、行动人、接收人、形式、条件、期限与起算是否清楚且一致。 |
| T2 | 错误类型、编制／行为主体、纠正机制与后果是否混用，不扩成价格核算。 |
| T3 | 项目采用、修订、版本、文件优先、引用与例外是否闭合。 |
| T4 | 行动权限、申请程序、实体权利、评定／认证及争议主体是否区分。 |
| T5 | 风险触发、转移与缓释建议是否有据，建议是否被当义务。 |
| T6 | 已核文字流程的分支、动作、接收人、期限及真实失权条件是否完整；不强制画图。 |
| T7 | 费用、EOT、估值、付款及程序渠道是否互相误推。 |
| T8 | 动态引用的相关时点、采用版本与变化后果是否被清楚约定。 |

R1／R10 体现为每项判断的事实与依据状态、假设、影响范围及报告级界限；R2–R9体现为上述相关分析。不机械要求每个 finding 采用咨询答案开头或完整十项模板。

## 纠正前的设计树与已作废问题

已定：产品经理确认的专家原则与文件边界 → Vetting 实际能力对照 → 现有可靠能力复用。

第一轮 frontier：

- **Q1 · 职责边界**：本轮是否将真实履约事件的费用／EOT／失权实体判断纳入 Vetting 自动输出？
  - 推荐 A：仍审合同文件、条款机制和项目适用关系；已签文件也可作为实际审查对象，但具体事件权利判断经 Advisory 分析。
  - B：同时扩成真实履约／争议个案审查；须另定义事件事实、通知、因果及争议判断的验收。
  - 状态：待用户回答；未将推荐写为采纳。
- **Q2 · 结果呈现**：相关专家检查是否成为可见的逐事项底稿？
  - 推荐 A：按实际适用检查项展示结果、理由、依据和缺证，问题另成 finding，复用现有问题卡／覆盖／报告，不造每条款×十规则×八题型矩阵。
  - B：保持发现为主和覆盖摘要；后台仍保留完整检查与来源记录，前端／报告暂不展开逐事项底稿。
  - 状态：待用户回答；人工 checklist 独立已经确认，不因该选择改变。

下一轮分支（依赖本轮回答，暂不询问）：应审项基线与证据触发补充、逐项结果最小字段、待确认信息闭环、范围身份 unresolved 的处理展示、验收案例与真实业务门。

## 文档与实施纪律

- 已确认术语写入 CONTEXT；未决产品选择只记为待决，不提前写成实施结论。
- 只有形成真实权衡且具有边界／长期维护价值的决策时才新增 ADR，不为每个字段或常规修补创建 ADR。
- `grilling` 要求在用户确认已达成共同理解后再实施。访谈文档和原字节需求输入不构成代码、部署、运行提示词或模型变更授权。
- 不重建或暂停当前 OER 质量目标，不操作活动模型、fixed05、数据库、Drafting或其他服务。本轮只对齐 Vetting 产品与实现输入。
