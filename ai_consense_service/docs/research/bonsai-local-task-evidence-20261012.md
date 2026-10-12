# 本机 Bonsai 27B 的任务证据与选型含义

日期：2026-10-12，Asia/Shanghai。本轮只读已保存模型卡、评分和审计；未加载模型、修改冻结 harness 或调用 API。当前约束见 [规则入口](../oer-scope-and-rules.md)。

**最新上下文归因修正：本文FAIL评价当时本机端到端配方，不判定底座或H800完整窗口的能力上限。** 用户补充本机显存不足、上下文可能影响效果；保存第五轮实际配置为64K，未验证完整256K／512K。历史18项清单输入确有分片，但第五轮Bills／Sections完整短源已入包，28次引擎生成无truncated标记，现有记录不支持直接归因为窗口耗尽。API完整usage／finish_reason及硬件因果保持未知。详见 [保存上下文核查](./bonsai-local-context-capacity-attribution-20261012.md)。

## 1. 身份核验：用户所指确为 Qwen3.8-27B 三进制版本

本机保存的作者卡 `.local/state/bonsai-official-model-card.md` 的 `base_model` 是 `Qwen/Qwen3.8-27B`，正文同样明确底座。实际制品为 `.local/models/Ternary-Bonsai-2-27B-PTQ1_0.gguf`，文件长度 5,946,648,928 bytes；模型 revision `b072e1d3b35a0a630cece372c2127528e0994386`，Prism 运行器 `prism-b10743-adfffbe`。这不是旧 Bonsai 1／Qwen3.6，也不是未尝试的普通 27B。[既有制品核验](../../../docs/bonsai-local-feasibility.md)、[作者固定权重地址](https://huggingface.co/prism-ml/Ternary-Bonsai-2-27B-gguf/resolve/b072e1d3b35a0a630cece372c2127528e0994386/Ternary-Bonsai-2-27B-PTQ1_0.gguf)。

原始第五轮评分配置为 profile `local`、model `ternary-bonsai-2-27b`，run `80d0396f-1a3c-4ed0-8232-5c21e966a209`。配置身份不能冒充网络响应内已逐请求返回的 checkpoint 身份；现有证据保留其记录口径。

## 2. 真实业务结果：原始运行、固定回答重放和 M3 分开

| 运行／评价 | 有值项正确候选 | 正确留空 | 原严格评分 PASS／REVIEW／FAIL | 结论 |
| --- | ---: | ---: | --- | --- |
| Bonsai 第五轮原始实际完整任务 | 51/58 | 21/21 | 31／41／7 | FAIL |
| 同一批 Bonsai 原回答，经 `.27` 接收／来源规则离线重放 | 56/58 | 21/21 | 33／44／2 | 仍 FAIL；没有新模型推理 |
| M3 第五轮保存完整任务，run `c4bb7f20-cde1-4bae-8205-1da79f2a2375` | 58/58 | 21/21 | 42／37／0 | 37 REVIEW 已有独立原件语义复核支持 |

分母分别为同题 58 个有值项、21 个应空项；PASS／REVIEW／FAIL 合计为 79 个正式输入。51 与 56 是严格评分器的 grounded value 口径。另有原件人工语义审计 53/58，不改写严格评分器的 51/58。REVIEW 不算自动 PASS，M3 不叫全部 79 自动通过。

来源：[第五轮完整考试与归因](../../../docs/drafting-harness-rounds-4-5-20261009.md)、原始 `.local/state/harness-iterations-20261009/round5/strict-score/drafting-score.json.summary`、重放 `round5/offline-replay27-score-v3/drafting-score.json.summary`、[M3 独立语义复核](../testing/m3-round5-20261009/README.md)。重放评分中的默认配置标签是隔离测试配置，不能将它的 `qwen2.5:7b` 误认为实际生成这些回答的新模型。

原始 Bonsai 任务耗时 1749.474028 秒；M3 既有同题约 269.933 秒。前者为本机硬件和当时完整补查流程，后者为 API 及其 harness 流程，未统一硬件、消息与调度，不用两数推算 H800 速度或纯模型倍速。固定题已参与开发，M3 这次成绩不是新的盲测。

## 3. 留下来的失败是什么

- **Bills 输出协议**：回答含泄漏的思考及畸形前缀；正确终端数组出现之前还有嵌套 Bill 行数组，现有 decoder 先选了前面的数组，出现六个空 key／`unknown_key`。随后 recall 仍漏 Bill register。该失败同时涉及输出协议和 decoder 兼容，不能说模型完全没识别 Bill，也不能说最终系统已完成。
- **Sections 的值、schema 与引文**：初次把 designation 放在可选 `id`，漏了必需 `designation`；repair 给了正确行却从引文删掉中间一句，违反连续来源要求，仍被拒。格式有效、值正确和连续引文必须同时通过。

分片、来源角色、表示规范与接收规则的修复能解释 51→56 的部分提升；剩余失败以及用户总体体验均支持当前 Bonsai 配方不足以成为完整业务主模型。没有同消息／同引擎的三进制对 FP8／BF16 实验，不能把全部差异归因于量化，也不能由此断言所有 27B 均不足。

## 4. 加预算已有测试，不是默认救法

| 已保存 Bonsai 配方 | 有值正确 | 留空正确 | PASS／REVIEW／FAIL | 总时长 |
| --- | ---: | ---: | --- | ---: |
| 32K context／thinking off／2K 总生成 | 58/61 | 18/18 | 55／21／3 | 10.99 分钟 |
| 64K／medium／4K 每思考块／16K 总生成 | 60/61 | 18/18 | 55／23／1 | 65.18 分钟 |
| 64K／off／16K 总生成 | 58/61 | 18/18 | 55／21／3 | 10.09 分钟 |

来源：[本机模型调参结果](../../../docs/drafting-model-benchmark-results.md)。这是另一组 61 有值／18 空值输入，不与第五轮合并。最佳已测 medium 仍 FAIL；单纯把 off 的输出预算扩大没有提高有值命中。它不证明更大预算永远无效，但足以否定“继续给 Bonsai 加思考就能达标”的当前默认假设。这些运行并未使用 256K／512K，不能称为长窗口验收。

## 5. 作者平均保留率不能覆盖具体业务失败

本机保存作者卡给出 14 项 thinking benchmark 平均 FP16 86.32、Bonsai 84.78、约 98.2% 保留率；同表 MuSR 为 79.63→70.63，IFEval 91.50→91.31，IFBench prompt-loose 71.00→74.00，BFCL v3 76.74→74.92。各维度方向不同。平均保留率不保证合同条件、多行清单、引文与 required schema 的可靠性。这是量化作者自报；其 IFBench 口径不能和其它供应商未声明模式的 IFBench 直接拼榜。

作者卡本轮 SHA256：`B02967A1A469EF60F21F26C7D8B316C66EB2388A90A96218BD4A85D67C907E07`。固定 HF README 在线读取未成功，本文上述 card 数据据本机保存版本，不称为在线卡已重新验证。

原始第五轮评分 SHA256：`8882550D402A290142FC98401F5CB7AE2D40531A068E34F2CD1E22E620F9EF65`；固定回答重放评分 SHA256：`65338ED791B88EECC3C132744CED1896F88FE0F534A714AA835D1D984F80A5EA`。三份小证据本轮已重算 SHA，一致；未重新扫描大权重或运行考试。

## 6. 由这些证据改变选型

当前本机Bonsai PTQ1_0配方尚不能推荐为已达标完整业务主模型，保留部署状态和失败；H800足上下文Bonsai继续作为未验归因对照，不作模型级排除。Qwen3.8官方FP8是本地第一顺位验证建议，也承担同底座精度／协议对照；它必须用完整来源和具体任务残差说明改善，不能凭公开平均榜单宣布替代已足够。其它新小模型与更大混合驻留模型照样进入任务评价；参数量不提供否决权，任务成绩才决定角色。

完整公开评测、至少 256K／优选 512K 的资格与部署取舍见 [质量与长上下文选型](../llm-task-quality-selection-256k-512k-20261012.md)。本轮没有改变既有在线模型、用户确认值或冻结识别 harness。
