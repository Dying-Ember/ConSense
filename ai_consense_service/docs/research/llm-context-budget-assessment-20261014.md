# 三模块 LLM 上下文与思考预算评估

日期：2026-10-14，Asia/Shanghai。新测试产物读回截止21:05。只读公开官方资料、源码研究与已完成冻结记录；未调用模型/API/GPU、加载tokenizer、修改运行配置或干预并行OER/Advisory测试。

## 判断

主模型继续保留至少256K、优选512K总窗口是合理选择。256K适合先验证当前完整证据的单题与单审查包；512K为长历史、跨来源依赖和更大推理预算提供余量。当前证据没有证明必须提高到1M，也没有完整请求长度分布可以证明256K覆盖所有请求。

“足够”分三层：模型/引擎允许的总窗口、准确请求能容纳的token预算、该长度下仍保持条件/引用/完整发现的质量。三者不能互相代替。

## 已有实际长度与当前未知

1. 10-11 Advisory Q1.6：provider input83868、output2647、total86515，53完整source单元进入请求，正常completed但仍有合同判断/引用残差。它支持当时这个完整证据包的容量在256K数量级内，不支持将M3 token数直接复制给Qwen，也不证明新版相同。
2. code07新版对照只含system/user，没有多轮历史；M3初次请求约134499 UTF8bytes。system约19311 UTF16字符、user约96341字符；schema/专家规则/应用契约及来源身份均占空间。字节和字符不是token数。contextMeasured=false、providerUsage=null，204133的准入值是byte heuristic加输出/余量/封装，不是精确输入tokens。
3. 新M3在16384与32768输出额度对照均length、没有完整final；32K收据为failed_no_blind_retry。它是生成模式/预算未形成完整答复，不是输入用尽1M的证据。
4. 新M2.7在32K两次stop，终态contract_invalid_after_one_repair；更大输出预算没有自动解决JSON和契约问题。不能据此填Qwen3.8模型成绩。
5. 本机Strata合成请求有250083input+512output=250595的精确计数与实际执行；配置总窗262144。它只证明该配方的合成容量/速度，不证明真实Vetting/Advisory末端准确率、xhigh长推理或512K业务。

证据：

- [M3历史provider长度](./minimax-m3-evaluation-reference-20261012.md:66)。
- [新版M3 32K收据](</C:/Users/Administrator/Documents/Codex/advisory-alignment-20261014/model-comparison-01/m3-official-03/receipt.json>)，SHA `cb0300add1ba733d136320ced97a23b8594b2147ccb443e521c5657750dc0334`。
- [新版M2.7 32K收据](</C:/Users/Administrator/Documents/Codex/advisory-alignment-20261014/model-comparison-01/m27-official-03/receipt.json>)。
- [预算计算的明确身份](</C:/Users/Administrator/Documents/Codex/advisory-alignment-20261014/model-comparison-01/tooling-budget32-01/preparation-receipt.json>)。
- [Strata精确总窗口](./strata-iq3s-local-deployment-test-20261014.md:59)。

上述32K结果是对 [20:45归因快照](./vetting-advisory-attribution-analysis-20261014.md) 中“32K仍进行中”的后续补充；保留原文观察时点，不将后来的终态倒填到旧快照。

## 总窗口的算术候选

`完整输入（系统/模板/schema/证据/历史/工具） + 本轮thinking与final预留 + 安全余量 <= 实际总窗口`

以下按1K=1024计。16K安全余量为规划假设，尚未成为业务采用线或实测最大值。系统、历史、工具已包括在“完整输入”，不能再与安全余量混为同一个计数。

| 总窗口 | thinking+final合计预留 | 安全假设 | 完整输入余量 | 算术 |
| ---: | ---: | ---: | ---: | --- |
| 262144 | 32768 | 16384 | 212992（208K） | 262144-32768-16384 |
| 262144 | 65536 | 16384 | 180224（176K） | 262144-65536-16384 |
| 262144 | 131072 | 16384 | 114688（112K） | 262144-131072-16384 |
| 524288 | 131072 | 16384 | 376832（368K） | 524288-131072-16384 |

因此，当完整输入仅约80K数量级时，256K可以给推理与最终答案保留较大空间；当完整输入接近180K且还需64K以上生成，或接近112K且需128K生成，512K有清晰的容量理由。这里的阈值来自假设算术，不是三模块实际P95需求。

## 思考强度、生成额度和窗口分别固定

- Qwen3.8-27B官方支持reasoning_effort=xhigh/medium/low，xhigh默认。enable_thinking及preserve_thinking默认开启。需要核真实引擎是否消费该配置；不能只看到请求字段就宣称已提升。preserve_thinking可能让后续历史继续增长，历史预算应包含该模型模板实际保留内容。
- 官方原生总窗262144，超过总长度才使用官方RoPE/YaRN长窗路线；512K需按准确版本的官方配方固定，不能只改max_model_len。短输入与扩窗运行身份、业务质量分别核验。
- stable MiniMax-M3 Chat支持thinking adaptive/disabled；当前官方reasoning_effort深度参数只对M3.1-Flash-Preview生效。给stable M3填xhigh不能当已经提高；更换Flash是模型变更而非同模型参数对照。
- MiniMax官方max_tokens/max_completion_tokens预算包含thinking。新集成推荐max_completion_tokens；当前M3文档推荐131072、最大524288。这是API参数说明，不是ConSense必须使用的值，也不是本次已执行的64K/128K配方。

公开资料本轮在线读回：

- [Qwen3.8官方模型卡](https://huggingface.co/Qwen/Qwen3.8-27B#api-usage)
- [Qwen3.8官方长窗与输出建议](https://huggingface.co/Qwen/Qwen3.8-27B#best-practices)
- [MiniMax SDK参数](https://platform.minimax.io/docs/api-reference/text-openai-api)
- [MiniMax Chat参数](https://platform.minimax.io/docs/api-reference/text-chat-openai)

Qwen官方在1M长程agent场景给出的更大reasoning/final建议不能直接成为本项目每题强制预算。已有16K/32K触顶支持有界扩大生成额度；不能证明持续增大能解决思考不结束或输出契约错误。提高强度/预算的效果必须看完整最终答复、来源支持和关键错误，而非思考长度。

## 模块与部署判断

Drafting按完整候选来源/语义单元组织；Advisory按本题完整主条款、定义、例外、修订和必要事实组织；Vetting按审查责任组织完整配对材料，并另核全任务覆盖。整库可大于单请求窗口，完整结构分组不能静默裁条件或相反条款，也不能以一次top-k代表全面审查。

本机A4000 16GB与单H800的资源准入不同。模型支持512K不代表本机参数/KV/工作区可承载。KVMem相关KV检索子集、完整KV迁移和prefix cache也不同：逻辑窗口较大不证明每步读到完整必要条件。当前只核到本机Strata合成256K档实际运行；512K和完整三模块质量仍待独立验证。

下一次新增模型实验需先固定有限样本、完整请求计数、模型/模板/模式、thinking+final输出上限和调用预算。只改变思考档位或生成预算中的一个，保留raw/usage/finish与来源评分。当前尚未获得新packet的精确输入token，也未建立三模块常规/尾部长度分布。
