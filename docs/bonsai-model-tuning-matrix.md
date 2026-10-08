# Bonsai 2 本地变量识别：固定 harness 的模型调参矩阵

核对日期：2026-10-08。用户指出相同 harness 使用 MiniMax-M3 官方 API 可以正确完成，因此本轮实验保持 harness、业务规则、证据校验和参考答案口径不变，优先考察本地 Bonsai 的输出限额、推理模式及缓存配置。本文是候选矩阵和已确认参数契约；服务切换、真实识别及恢复由独立主流程执行。

## 最值得先跑的候选

| 顺序 | Context / KV | 总生成上限 | thinking | 每思考块预算 | 用途 |
|---|---|---:|---|---:|---|
| Baseline | 32,768 / F16 | 2,048 | off | 不使用 | 已完成：58/61 正确且有出处候选、18/18 应留空、55 PASS / 21 REVIEW / 3 FAIL |
| A | 65,536 / F16 | 16,384 | off | 不使用 | 已完成：58/61、605.156 秒；新增一项来源角色混淆产生的冲突候选，不能入 best |
| B | 65,536 / F16 | 16,384 | medium | 4,096 | 已完成：60/61、3,910.937 秒；最高已测覆盖率，严格仍 55 PASS / 23 REVIEW / 1 FAIL |
| C | 65,536 / F16 | 16,384 | medium | 8,192 | 尚未执行；目前剩余失败涉及实际分片、引用配对及值类型，没有证明 4K 思考预算不足 |
| D | 131,072 / Q8_0 | 与胜出候选相同 | 与胜出候选相同 | 与胜出候选相同 | 有实际长窗口需求或部署容量需要时评估；不作为当前 4K 输入考题的首选准确率干预 |

当前指定考题经真实应用解析/构造的有效单次输入只有 3,413–4,088 tokens。即便预留全部 16,384 生成 tokens，合计最多约 20,472，小于 32,768。因此 **64K 是对齐官方推荐并为其他业务留余量，不能把其相对 32K 的收益直接解释为“这批考试看到了更多资料”**。若严格隔离输出因素，可先保留 32K/F16/off，只将 `--n-predict` 改为 16,384，再进入 A；并非所有矩阵行都必须执行。

当前三组实测已经完成并恢复原配置，详见 `docs/drafting-exam-validation.md` 与 `.local/state/drafting-exam/comparison-all-20261008/`。所有已测调用均未达到各自总输出上限。medium 的加权生成速度为 29.22 tokens/s，off16k 为 30.94 tokens/s；medium 的总生成量是基线约 6.26 倍，耗时约 5.93 倍，增加的思考及边界开销是主要因素。最高覆盖率并不等于全部变量通过，当前不将它描述为已验收的全自动起草配置。

128K/Q8 同时改变窗口和 KV 精度，会引入第二个质量变量。当前考试既没有超窗，也没有上下文不足分片，应先找到 F16 下的最佳推理/输出配置，再决定是否需要这个容量候选。显存加载成功与识别准确率分别验收。

## 本机可执行文件确认的参数契约

本机官方运行器 build `b10743-adfffbe41` 的 `--help` 真实确认：

```text
--reasoning [on|off|auto]
--reasoning-effort LEVEL
--reasoning-budget N
--reasoning-budget-message MESSAGE
--reasoning-format FORMAT
--cache-type-k TYPE
--cache-type-v TYPE
```

`--reasoning` 只接受 **on、off、auto**。正确的 medium 候选参数是：

```text
--ctx-size 65536
--cache-type-k f16 --cache-type-v f16
--n-predict 16384
--jinja
--reasoning on --reasoning-effort medium
--reasoning-format deepseek
--reasoning-budget 4096
```

将最后一项改为 `8192` 就是候选 C。不要写 `--reasoning medium`。保持现有 `--parallel 1`、GPU 层、flash attention、batch、ubatch、模型/引擎文件不变。`on` 对此固定本地实验强制 thinking 开启；若以后需要客户端 `reasoning_effort:none` 动态关闭，官方说明应使用 `auto`，因为 `on` 会覆盖客户端关闭请求。

关闭思考的候选保留 `--reasoning off --reasoning-format deepseek`，不以 `--reasoning-budget 0` 替代关闭。

### 防止 xhigh 参数漂移

模型自带模板默认 reasoning effort 是 **xhigh**，只支持 **xhigh、medium、low**。普通 `--reasoning on` 而没有显式 `--reasoning-effort medium` 会落到 xhigh。通用 CLI help 虽列出 high/minimal/max，模型模板不接受它们，官方确认 high 会导致 HTTP 500。

本地 ConSense 普通 chat 不发送每请求 `reasoning_effort`、`reasoning_budget_tokens`，所以本轮必须靠 CLI default 固定。配置、进程命令行和 rendered chat template 都应留收据；不能只凭 UI“thinking已启用”推断实际 effort。不要把 xhigh 的生成回执归到 medium。

### 思考预算不是独立的最终输出额度

- `--reasoning-budget -1` 是不限制思考块；默认值就是 -1。
- `--reasoning-budget 0` 会立即强制结束思考块，**不等价于关闭思考模板**。
- 正数 N 从 `<think>` 起对该思考块计数，耗尽时 sampler 强制生成预算结束序列，然后允许继续最终回答。
- UTF-8 字符还未完整时可多生成少量 token 再结束，因此 N 不是对所有情形绝对精确的 whole-response token 截断点。
- 出现新的 `<think>` 块会重新取得 N 的预算；N 是每思考块上限，不是全请求所有思考的总和。
- `--n-predict 16384` / 请求 `max_tokens` 仍是**思考、强制结束序列、最终 JSON 合计**的生成限额。因此“4K 思考 + 16K 最终输出”不是候选 B 的实际契约；B 总额只有 16K。
- 不设置 `--reasoning-budget-message`，保持默认 none，避免实验多引入一句控制提示词。

预算必须通过 CLI 或请求**顶层** `reasoning_budget_tokens`（兼容别名 `thinking_budget_tokens`）传递。`chat_template_kwargs.thinking_budget_tokens` 没有效果，也不会报错。服务器先读请求顶层字段；读取结果为 -1 时继承 CLI。当前应用普通请求不会覆盖 CLI 的值。

`--reasoning-format deepseek` 将思考分离至 `message.reasoning_content`，当前应用仍读取 `message.content` 的最终 JSON。`none` 会把思考放进 content；`deepseek-legacy` 也会在 content 保留 `<think>` 标签；二者均不适合当前 JSON 输出协议。

## 官方建议与证据

固定模型版本的 [KNOWN_ISSUES.md](https://huggingface.co/prism-ml/Ternary-Bonsai-2-27B-gguf/blob/b072e1d3b35a0a630cece372c2127528e0994386/KNOWN_ISSUES.md) 明确指出：

- 第 18、24–25 行：空答案和截断最常见原因是思考消耗总输出预算，建议 65,536 context、16,384 或更高输出，以及 medium。
- 第 27–31 行：high 无效；low 实测思考长度与 xhigh 接近，不能把 low 当作可靠的低成本替代。
- 第 36–40 行：模板 kwargs 中的思考预算被忽略；顶层/CLI 预算才有效；on 会覆盖客户端 none。
- 第 45–46 行：16K 左右生成预算下，medium 比 xhigh 更少耗尽预算，准确程度约相近；在大得多的输出预算下，xhigh 才可能更准确。
- 第 50–59 行：模型采样推荐和部分元数据有差异，温度推荐 1.0、top_p 0.95、top_k 20、min_p 0.05。容量/思考矩阵先固定当前应用实际温度，后续若有必要再单独做官方采样参数一轮，避免同时改变多个因素。

固定官方引擎源码：

- [common/arg.cpp](https://github.com/PrismML-Eng/llama.cpp/blob/prism-b10743-adfffbe/common/arg.cpp#L3677)：CLI effort 和预算参数；预算允许 -1/0/正数。
- [tools/server/server-common.cpp](https://github.com/PrismML-Eng/llama.cpp/blob/prism-b10743-adfffbe/tools/server/server-common.cpp#L1353)：顶层预算字段读取及 CLI fallback。
- [common/reasoning-budget.cpp](https://github.com/PrismML-Eng/llama.cpp/blob/prism-b10743-adfffbe/common/reasoning-budget.cpp#L74)：思考预算状态机、UTF-8 完整性和新块重启。
- [common/sampling.cpp](https://github.com/PrismML-Eng/llama.cpp/blob/prism-b10743-adfffbe/common/sampling.cpp#L310)：初始化 sampler 并接受模板 prefill tokens。
- [tools/server/server-context.cpp](https://github.com/PrismML-Eng/llama.cpp/blob/prism-b10743-adfffbe/tools/server/server-context.cpp#L1747)：请求生成上限优先于 CLI；普通 ConSense 没有请求 max_tokens 时才用服务器默认。

原文已保存于 `.local/state/capacity/budget-source/`，均从以上固定版本获取。分析只读，没有更换引擎或权重。

## 可比验收和停止规则

每个候选用独立测试项目，保持相同 15 份考试 DOCX、原始 2a 三模板、相同 harness/version/prompt、温度和其余 sampling 参数；不上传参考答案，不确认变量。记录有效进程参数和模型/引擎 SHA，及每请求 input/completion/reasoning token、finish_reason、最终 content 是否为空/JSON完整、wall time。

仍按 provided 60、derived 1、不适用 11、optional_unchanged 2、intentionally_unknown 1、刻意缺失 4 的原有口径逐项评分。优先比较确切值与原文证据、缺失项误填、合法项遗漏、候选冲突、格式/预算失败；不把高 confidence、HTTP 200 或 trace completed 当作准确率。

CLI `--temp` 不能覆盖应用请求里的 `temperature=0.2`。如果后续决定比较官方温度，需要通过配置修改应用请求值并明确单列实验，不能误认为仅改启动参数已经做了温度对照。

若 A 已达到业务正确性，继续扩大窗口不是必需；若 A 无截断但遗漏/推断错误，B 比 D 更值得先跑；若 B 思考频繁被 4K 强制终止、复杂判断仍欠佳但最终输出预算宽裕，再跑 C。若 C 出现 final 剩余预算不足，优先提高总生成上限而不是把不完整输出解释为模型能力结论。最终恢复已确认可用配置并提供实际运行回执；本文不宣称候选已经验收通过。
