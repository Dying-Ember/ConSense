# Bonsai 2 本机上下文、输出与推理预算评估

日期：2026-10-08（Asia/Shanghai）。研究固定模型 revision `b072e1d3b35a0a630cece372c2127528e0994386`、PrismML runtime `prism-b10743-adfffbe`。本部分只读模型元数据、历史部署收据及第一手源码，没有操作服务、GPU、配置或业务材料。主流程的本机容量实测应追加在文末。**理论分配容量、模型加载成功、长输入可执行、任务正确率是四个不同结论。**

## 结论与候选配置

32K 不是模型的能力上限；本机 GGUF 直接声明训练上下文 `262144` tokens，官方模型卡也声明 262K。当前 `32768` 是服务启动参数。输出 `2048` 和关闭 thinking 同样是部署/应用选择，不是模型不可改变的硬限制。[官方模型卡](https://huggingface.co/prism-ml/Ternary-Bonsai-2-27B-gguf/blob/b072e1d3b35a0a630cece372c2127528e0994386/README.md#model-overview)、[runtime 参数定义](https://github.com/PrismML-Eng/llama.cpp/blob/prism-b10743-adfffbe/common/arg.cpp)

建议依次验证：

1. **64K + F16 K/V + medium thinking + 16K 总生成预算**。不再压缩激活缓存，按当前背景占用估算整卡约 11,509 MiB，余量约 4,867 MiB；这是最保守的升级候选。
2. **128K + q8_0 K/V + medium thinking + 16K 或 32K 总生成预算**。按常驻 KV 估算整卡约 11,765 MiB，余量约 4,611 MiB；同型 Q8 在固定源码的默认 CUDA FA 构建中可用，值得本机加载、长 prompt、严格 JSON 和证据追踪验证。
3. 128K F16 估算只余 771 MiB，256K Q8 只余 259 MiB；计算缓冲、桌面和其他服务变化足以耗尽这些余量，不应作为常态全 GPU 配置。256K F16 的 KV 本身已是 16 GiB，整卡容不下模型。256K Q4 理论可以节省空间，但引入更强的激活量化，应另做质量、性能和稳定性验收。官方还列出约 200K 长上下文量化 KV 连接重置/崩溃的未复现报告。[已知问题](https://huggingface.co/prism-ml/Ternary-Bonsai-2-27B-gguf/blob/b072e1d3b35a0a630cece372c2127528e0994386/KNOWN_ISSUES.md)

上述卡总量使用主流程提供的当前基线：RTX A4000 `16376 MiB`，32K/F16 单槽部署整卡 `9461 MiB`。包含桌面及其他进程，不能把整卡占用归为模型；只用相同背景下增加 KV 的差值预估。研究没有当前采集 GPU 数据。

## 可复算的 KV 内存依据

2026-10-08 直接用只读 BinaryReader 解读本机 `.local/models/Ternary-Bonsai-2-27B-PTQ1_0.gguf` 的 GGUF v3 元数据，得到：

| 元数据键 | 实际值 | 用途 |
| --- | ---: | --- |
| `general.architecture` | `qwen35` | 固定运行器使用 Qwen3.5 hybrid 图 |
| `qwen35.block_count` | 64 | 总主干层数 |
| `qwen35.full_attention_interval` | 4 | 每 4 层一层 full attention |
| `qwen35.context_length` | 262144 | 模型声明的上下文 |
| `qwen35.attention.head_count` | 24 | 查询 head 数，不直接乘到 KV 公式 |
| `qwen35.attention.head_count_kv` | 4 | K/V head 数 |
| `qwen35.attention.key_length` | 256 | 每个 K head 的维度 |
| `qwen35.attention.value_length` | 256 | 每个 V head 的维度 |
| `qwen35.ssm.conv_kernel` | 4 | recurrent convolution state |
| `qwen35.ssm.state_size` | 128 | recurrent state 维度 |
| `qwen35.ssm.group_count` | 16 | recurrent key group |
| `qwen35.ssm.time_step_rank` | 48 | recurrent value head |
| `qwen35.ssm.inner_size` | 6144 | recurrent inner 维度 |

官方底座 config 与这些值相符，但本评估以部署 GGUF 为准，避免 main 底座日后变动。固定 runtime `qwen35.cpp` 以 `(i+1) % full_attn_interval != 0` 标记 recurrent 层，故主干是 **16 层 full attention + 48 层 recurrent**。`llama-memory-hybrid.cpp` 只为 full 层分配增长式 KV；recurrent 路径保存状态，其大小与 sequence/rollback 数有关，不能把 64 层全部按普通 KV 累乘。[模型图](https://github.com/PrismML-Eng/llama.cpp/blob/prism-b10743-adfffbe/src/models/qwen35.cpp#L17-L29)、[hybrid memory](https://github.com/PrismML-Eng/llama.cpp/blob/prism-b10743-adfffbe/src/llama-memory-hybrid.cpp)、[recurrent memory](https://github.com/PrismML-Eng/llama.cpp/blob/prism-b10743-adfffbe/src/llama-memory-recurrent.cpp)、[底座 config](https://huggingface.co/Qwen/Qwen3.8-27B/blob/main/config.json)

单槽、无投机/MTP 时，full-attention 常驻 KV 公式为：

```text
KV_bytes(C) = C × 16 层 × 4 KV_heads × (256 × bytes_per_K + 256 × bytes_per_V)
F16：2 bytes/value => 65536 bytes/token = 64 KiB/token
q8_0：每32值为32个int8+2-byte缩放 => 34/32 bytes/value => 34 KiB/token
q4_0：每32值为16个packed bytes+2-byte缩放 => 18/32 bytes/value => 18 KiB/token
```

源码为每层分别创建 `[n_embd_k_gqa, kv_size, n_stream]` 和 `[n_embd_v_gqa, kv_size, n_stream]` 张量；`block_q8_0`/`block_q4_0` 的 `static_assert` 明确了缩放开销，不能简单把 Q8 写成“F16 的恰好一半”。[KV 张量分配](https://github.com/PrismML-Eng/llama.cpp/blob/prism-b10743-adfffbe/src/llama-kv-cache.cpp)、[量化 block 定义](https://github.com/PrismML-Eng/llama.cpp/blob/prism-b10743-adfffbe/ggml/src/ggml-common.h)

| 配置 C | F16 KV（MiB） | Q8 KV（MiB） | Q4 KV（MiB） |
| --- | ---: | ---: | ---: |
| 32768（32K） | 2048 | 1088 | 576 |
| 65536（64K） | 4096 | 2176 | 1152 |
| 131072（128K） | 8192 | 4352 | 2304 |
| 262144（256K） | 16384 | 8704 | 4608 |

用 `9461 - 2048 = 7413 MiB` 作为当前非增长式 KV 的整卡基线，仅添加表中 KV 得到：

| 候选 | 估算整卡（MiB） | 估算余量（MiB） | 容量判断 |
| --- | ---: | ---: | --- |
| 64K F16 | 11509 | 4867 | 保守第一候选 |
| 128K F16 | 15605 | 771 | 余量过低 |
| 128K Q8 | 11765 | 4611 | 有实际测试价值 |
| 256K Q8 | 16117 | 259 | 余量过低 |
| 256K Q4 | 12021 | 4355 | 理论可分配，但质量/长上下文稳定性待单独验证 |

**此表不是峰值显存承诺。** 固定 CUDA `fattn.cu` 的 TILE/MMA_F16 分支可能对量化 KV 申请临时 F16 K/V 工作区；更长 prefill、batch/ubatch 和模型执行图还改变计算缓冲。加载成功并不证明接近满上下文的 prompt 可完成。[F16 scratch 分配依据](https://github.com/PrismML-Eng/llama.cpp/blob/prism-b10743-adfffbe/ggml/src/ggml-cuda/fattn.cu#L536-L567)

## Q8 KV 是否需要重编译、是否影响质量

PTQ1_0 是固定模型权重包装；`q8_0` K/V 缓存压缩的是请求过程中的激活，两者独立。Q8 KV 是有损表示，不能把此前“PTQ1 对已三元化权重无损”的结论延伸到缓存。更大上下文并不改变权重，但缓存量化可能改变数值和结果。官方固定模型资料没有给出中文合同任务上 F16/Q8/Q4 缓存的成对准确率，因此任何“绝对不降智”或确定百分比都无依据。

已知问题页宽泛提示量化 FA 可能需要 `-DGGML_CUDA_FA_ALL_QUANTS=ON`。进一步检查本机固定 runtime 的 CUDA CMake 可见，默认分支明确包含 `fattn-vec-instance-q8_0-q8_0.cu`、`q4_0-q4_0.cu`；`fattn.cu` 默认也接受这两种同型组合。`FA_ALL_QUANTS` 扩展混合 K/V 与更多格式，不应先假定同型 Q8 必须重编译。实际二进制仍须加载/推理验证，且不要未经验证改成混合 Q8/F16。官方特别列出 `q5_0` 慢数倍，推荐 F16/Q8/Q4。[固定 CMake](https://github.com/PrismML-Eng/llama.cpp/blob/prism-b10743-adfffbe/ggml/src/ggml-cuda/CMakeLists.txt#L115-L125)、[固定同型组合](https://github.com/PrismML-Eng/llama.cpp/blob/prism-b10743-adfffbe/ggml/src/ggml-cuda/fattn.cu#L320-L325)、[默认混合组合限制](https://github.com/PrismML-Eng/llama.cpp/blob/prism-b10743-adfffbe/ggml/src/ggml-cuda/fattn.cu#L442-L450)、[官方已知问题](https://huggingface.co/prism-ml/Ternary-Bonsai-2-27B-gguf/blob/b072e1d3b35a0a630cece372c2127528e0994386/KNOWN_ISSUES.md#performance)

Q8 是优先于 Q4 的扩容候选，因为其表示精度更高；业务质量仍要用相同输入、同采样条件、F16 基线配对测试，至少检查事实、证据编号/引用、矛盾条款、缺失信息、JSON 完整性及 finish_reason。PTQ1/PQ2 权重切换本身不会扩大上下文，更大的 PQ2 文件还会减少 KV 余量。

## 输出、thinking 和上下文共享

上下文 C 是本次渲染后的输入、已有对话以及新增输出共同占用的窗口。新增输出包含 reasoning 和最终 answer；`max_tokens=16384` 不是“允许先思考若干，再独立输出16384”。固定 `server-context.cpp` 用所有生成 token 的 `stats.n_gen` 减去 n_predict；请求的预算优先于服务端默认值，因此只修改服务端 `-n` 不能覆盖应用仍发送的 `max_tokens=2048`。[生成计数与预算](https://github.com/PrismML-Eng/llama.cpp/blob/prism-b10743-adfffbe/tools/server/server-context.cpp#L413-L419)、[请求覆盖服务端预算](https://github.com/PrismML-Eng/llama.cpp/blob/prism-b10743-adfffbe/tools/server/server-context.cpp#L1747-L1748)

| 配置 | 输入理论预算 | 含义 |
| --- | ---: | --- |
| 64K context / 16K generation | 49152 | 还要扣除 system/template、既有对话及安全余量 |
| 128K context / 16K generation | 114688 | 同上 |
| 128K context / 32K generation | 98304 | 同上；总输出含 reasoning |

这些是减法预算，不是建议把每份合同都塞到最大。官方建议给默认 reasoning 模型 `-n 16384` 或更高，并配 `-c 65536`；medium 在约16K预算下比默认 xhigh 更少因思考耗尽而无答案，官方称此预算区间两者大致同准确度，但没有本项目业务数据。`high` 会触发 template 错误，`low` 不可靠地缩短 reasoning，不应用来追求低延迟。[官方推理参数与预算问题](https://huggingface.co/prism-ml/Ternary-Bonsai-2-27B-gguf/blob/b072e1d3b35a0a630cece372c2127528e0994386/KNOWN_ISSUES.md#empty-cut-off-or-runaway-output)

建议服务默认 `--reasoning auto --reasoning-effort medium --reasoning-format deepseek`。auto 才允许客户端通过顶层 `reasoning_effort:"none"` 关闭简单提取的推理；固定 `--reasoning on` 会覆盖客户端 none。deepseek 将 thinking 放在 `message.reasoning_content`，保留最终 `message.content` 给 JSON 解析。需要明确思考上限时使用顶层 `reasoning_budget_tokens`（或别名 `thinking_budget_tokens`），不要放到 chat_template_kwargs；固定 server-common.cpp 在1355行读取这两个字段，优先 reasoning_budget_tokens。思考上限必须低于总生成上限并给最终答案留余量，例如总16K/思考4K作为工程候选，不能宣称为已验证的最佳质量预算。[字段读取源码](https://github.com/PrismML-Eng/llama.cpp/blob/prism-b10743-adfffbe/tools/server/server-common.cpp#L1313-L1366)、[参数说明](https://github.com/PrismML-Eng/llama.cpp/blob/prism-b10743-adfffbe/common/arg.cpp)

作为接口候选（不是已执行配置）：

```text
-c 131072 -np 1 -fa on -ctk q8_0 -ctv q8_0 -n 32768
--jinja --reasoning auto --reasoning-effort medium --reasoning-format deepseek
```

```json
{
  "model": "ternary-bonsai-2-27b",
  "max_tokens": 16384,
  "reasoning_effort": "medium",
  "reasoning_budget_tokens": 4096,
  "temperature": 1.0,
  "top_p": 0.95,
  "top_k": 20,
  "min_p": 0.05,
  "messages": [
    {"role": "system", "content": "You are a helpful assistant."},
    {"role": "user", "content": "待验收的合成合同问题"}
  ]
}
```

context shift 在窗口满时会移除既有 token；对证据任务，静默丢弃早期合同内容会改变答案依据。容量测试宜禁止 context shift 并预先计算预算，以明确错误取代默默缺失证据。固定 server-context.cpp 在禁用 shift 时按容量停止，否则会执行 seq_rm/seq_add。应用还需检查 finish_reason=length，把不完整 JSON/报告当作失败，不能作为完整答案提交。[满上下文行为](https://github.com/PrismML-Eng/llama.cpp/blob/prism-b10743-adfffbe/tools/server/server-context.cpp#L1815-L1821)、[shift 删除内容](https://github.com/PrismML-Eng/llama.cpp/blob/prism-b10743-adfffbe/tools/server/server-context.cpp#L2848-L2866)

## 延迟与应用联动

输出上限并不会令每次请求都生成到上限，但真实大输出成本高。仅作短样例速度的算术示意：若持续34 tokens/s，生成16384需约482秒、32768需约964秒，分别约8和16分钟；长上下文下真实速度还可能更慢。34 tokens/s 来自先前短合成样例收据，不是长上下文实测。还要加 prefill 时间，所以接口 HTTP timeout、网页等待和任务状态处理必须跟着调整。运行服务单独扩大 -n，并不能证明 ConSense 端到端的大报告流程可执行。

扩大窗口可以让模型读更多材料，但不能替代检索、按任务筛选证据、分章节生成及完整性校验。原部署文档明确独立 BGE-M3 embedding 服务/检索链尚未配置；其补齐影响长合同功能，即使 Bonsai 可分配128K也不能单凭模型 API 宣称所有项目功能已验收。[已有部署说明](../LOCAL-DEPLOYMENT.md)

## 本机实测追加区

本区记录主流程于 2026-10-08 完成的实际测试配置、模型读回、显存采样、长输入 token 数、输出 token 数、耗时、finish_reason 与 JSON/证据结果。研究部分的推算仍是估算，不作为实测值。

2026-10-08 第一候选曾实际加载：`--ctx-size 65536 --cache-type-k f16 --cache-type-v f16 --n-predict 16384 --reasoning on --reasoning-effort medium --reasoning-budget 4096 --reasoning-format deepseek`，同一 PTQ1_0 权重和引擎。`/v1/models` 读回 `n_ctx=65536 / n_ctx_train=262144`。加载时整卡 `11656 MiB`，空余 `4514 MiB`；这些是当时的加载采样，收据在 `.local/state/drafting-exam/campaign-medium4k-20261008/medium4k-loaded-gpu.csv` 与 `medium4k-model-info.json`。`/apply-template` 验证启用 `<think>`，没有 xhigh 的模板指令，与显式 medium 一致，收据为 `medium4k-template-check.json`。

`medium4k` 和 `off16k` 两轮相同 Drafting harness 的 15 份缺失证据考题已完成，未将参考答案作为模型输入；两轮的 `campaign.json` 均记录 `status=completed / restored=true`。`medium4k` 首次恢复因启动入口检测端口 8091 仍有监听而失败，稍后查询已无监听，重试原启动入口成功；初次未留监听者 PID，因此端口释放延迟只是符合观察的解释，不能断言占用者身份或确切原因。历史 `initialRestoreError` 和恢复收据已保留；`off16k` 自动恢复完成。考试单次输入约 4K，这些业务运行本身**不构成 64K 满窗口或长输入质量验收**。变量成绩分别见两轮评分收据和 [对照说明](drafting-run-comparison.md)，不能用下面合成测试的 PASS 替代 Drafting 评分。

独立合成合同长输入测试于 **2026-10-08 11:41–11:43（Asia/Shanghai）实际执行完成，PASS 并自动恢复**。本轮直接调用本机 Bonsai API，配置为 **64K context / F16 K/V / thinking off / 8,192 总生成上限**，沿用同一 PTQ1_0 权重和引擎，没有修改 Drafting harness。`/v1/models` 读回 `n_ctx=65536 / n_ctx_train=262144`，真实响应的 `usage.prompt_tokens=54997` 与执行前 `/apply-template` 加 `/tokenize` 的计数一致。

目标原合同、控制性生效补充协议、检查/批准变更记录分别位于输入 token 117、27,403、54,593（约 0.21%、49.83%、99.27%），其间有 306 个其他项目的干扰记录。实际答案正确返回原价 23,847,000 元、唯一批准变更 486,000 元及相加后的 24,333,000 元；采用生效补充协议的完工日 2027-07-02，区分检查日 2027-06-29 与被替代完工日 2027-06-18；未知邮箱保持 `null`，三个来源条款均正确。

| 实测项 | 结果 | 证据 JSON 路径 |
| --- | ---: | --- |
| 输入 tokens | 54,997 | `request-summary.json:usage.prompt_tokens` |
| 实际输出 tokens | 265 | `request-summary.json:usage.completion_tokens` |
| 实际总 tokens | 55,262 | `request-summary.json:usage.total_tokens` |
| 缓存命中 tokens | 0 | `request-summary.json:usage.prompt_tokens_details.cached_tokens`、`timings.cache_n` |
| API 请求耗时 | 112.750833 秒 | `request-summary.json:generation_elapsed_seconds` |
| 输入处理时间 / 速度 | 101.452248 秒 / 542.097 tokens/s | `request-summary.json:timings.prompt_ms`、`prompt_per_second` |
| 输出生成时间 / 速度 | 11.253181 秒 / 23.460 tokens/s | `request-summary.json:timings.predicted_ms`、`predicted_per_second` |
| 正常结束 | `finish_reason=stop` | `assessment.json:finish_reason`、`response.raw.json:choices[0].finish_reason` |
| 完整性及事实断言 | 21 / 21 为 `true`，PASS | `assessment.json:assertions`、`verdict` |

**8,192 是允许生成的总上限，实际仅输出 265 tokens**，没有验证连续生成 8K 文本。输入加完整输出预留为 `54,997 + 8,192 = 63,189`，小于 65,536，余量 2,347；这是预算检查，不能与实际使用的 55,262 混淆。`assessment.json` 同时要求 provider 无 error、恰好一个 choice、正常 stop、非空完整 JSON、无 tool calls/refusal，以及所有字段、类型、值和来源正确；仅有正确 JSON 而 `finish_reason=length` 不会获得 PASS。

显存为整卡采样：试验模型加载后使用 `11596 MiB`、空余 `4574 MiB`；请求结束后使用 `11591 MiB`、空余 `4579 MiB`，分别见 `trial-loaded-gpu.stdout.csv` 和 `after-request-gpu.stdout.csv`。没有连续记录推理峰值，因此不把两次采样当作峰值承诺。

`capacity-run.json` 记录 `status=completed / restored=true / restoreErrors=[]`。本轮 finally 恢复原始 runtime 字节（SHA256 `A612679931DD93A4F92AD4493BD2F6A12AA4440D81008935936435D141167091`），backend JAR 不变（SHA256 `FC007462DFC4ED8B60D166A9606243E0838802E50210248E859D7D0B432A76FC`），backend/frontend 进程 receipt 不变、原 watcher 恢复 `Running`、stopped marker 恢复原状态、三个 owned 服务均 ready。恢复时整卡采样为使用 `9601 MiB`、空余 `6569 MiB`。结果目录的 `artifact-hashes.json` 中 **52 份工件的大小和 SHA256 已逐一只读复核，全部一致**。

本次证明这台机器在 **64K/F16 配置下，可以完成约 55K 输入的一次合成、多位置事实检索及短 JSON 输出**。它没有验证满 64K、128K/Q8、更长输出、55K thinking medium、持续并发或真实合同质量，也不代表本项目 Drafting 变量和合同任务全部通过。准备好的 medium 请求仍未执行；原 24K 样例及本轮输入、预期答案均保留。

复现需在两轮 Drafting campaign 完成并恢复后执行，输出目录须是新目录：

```powershell
& '.local/tools/powershell/pwsh.exe' -NoProfile `
  -File '.local/state/capacity/run-long-context-capacity.ps1' `
  -OutputRoot '.local/state/capacity/run-55k-off8k-NEW'
```

本轮完整收据：`.local/state/capacity/run-55k-off8k-20261008/`。
主要核对 `capacity-run.json`、`request-summary.json`、`response.raw.json`、`assessment.json`、`restored-status.json` 与 `artifact-hashes.json`。
