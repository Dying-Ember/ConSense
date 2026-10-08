# Drafting 本机推理耗时评估

2026-10-08。针对相同 15 份缺失证据考题、相同 NTT/SCT/SCC 模板和相同 harness 的三轮已完成考试做只读分解。没有改 runner、评分器、harness、模型配置或服务进程。off16k 运行时的 CUDA 模块读回仅用于补充观察，各轮生成统计按对应退休日志隔离。

**本机的当前速度确实限制绝对等待时间；本次从约 11 分钟变为约 65 分钟，主要原因是生成工作量增加，而不是调用次数增加或每 token 速度突然下降数倍。** medium4k 总生成量增加到基线的约 6.26 倍，调用少了 2 次，加权解码吞吐只降低约 5.34%。最终 JSON 的独立重编码只增加约 31%，额外生成主要属于思考及生成边界差异。重编码差值不是 API 的精确 reasoning usage。

| 指标 | 32K / off / 2K 基线 | 64K F16 / medium4K / 16K | 64K F16 / off / 16K | 证据与计算 |
| --- | ---: | ---: | ---: | --- |
| runner elapsed（秒） | 659.422 | 3910.937 | 605.156 | baseline/medium4k/off16k.manifest $.elapsedSeconds |
| harness trace wall（秒） | 657.449 | 3906.281 | 600.753 | baseline/medium4k/off16k.trace $.data.finishedAt - startedAt |
| 实际生成调用次数 | 41 | 39 | 40 | baseline/medium4k/off16k.trace rawResponses / .metrics calls；逐项对齐 |
| engine 总生成 tokens | 17862 | 111753 | 16259 | baseline/medium4k/off16k.metrics $.calls[*].generatedTokens 的和 |
| 纯解码总时间（秒） | 578.609 | 3824.269 | 525.494 | baseline/medium4k/off16k.metrics $.calls[*].generationMilliseconds / 1000 的和 |
| prompt eval 总时间（秒） | 71.029 | 71.743 | 68.993 | baseline/medium4k/off16k.log 按已匹配 task ID 取 prompt eval time；baseline 不以缺字段当 0 |
| 加权解码 tokens/s | 30.871 | 29.222 | 30.940 | baseline/medium4k/off16k总 generatedTokens / 纯解码秒数 |
| wall 摊销 tokens/s | 27.087 | 28.574 | 26.867 | baseline/medium4k/off16k总 generatedTokens / runner elapsed；不是 kernel 解码速度 |
| 平均每调用 generated tokens | 435.659 | 2865.462 | 406.475 | baseline/medium4k/off16k总 generatedTokens / callsCount |
| runner wall 减解码与 prefill（秒） | 9.784 | 14.925 | 10.670 | baseline/medium4k/off16kelapsed - generation - prompt |
| 最终 content 独立重编码 tokens | 17821 | 23349 | 未重编码 | baseline/medium4k/off16kretokenization $.runs[*].calls；add_special=false |
| generated 减最终重编码 tokens | 41 | 88404 | 未重编码 | baseline/medium4k/off16k上两项相减；含思考和 EOS/template/parser 边界误差 |

加权吞吐采用 `sum(tokens) / sum(seconds)`，没有平均各次 tokens/s；基线 prompt eval 已按 engine-metrics 的 41 个 task ID 回查旧日志，前后烟测不计入。medium/off16k 的完整 39/40 个日志生成记录按顺序与 metrics 匹配并逐条验证时间和 token 数，未混入其他引擎前后烟测。trace 的 `rawResponses` 按实际执行顺序保存，而 `parts[*].attempts` 按来源分组；分析已验证“先全部非 recall，再全部 recall，最后关系审核”的序列与 rawResponses 逐项完全一致，再与 engine 记录对齐。每次调用的 task ID、类型、文档或 relation key、tokens、时间和日志行号保存在 JSON。

`elapsedSeconds` 是 runner 的 monotonic 总时间：旧基线使用已准备项目，medium 从项目准备开始；其少量准备及读取成本范围不同。共同的 trace startedAt 到 finishedAt 区间也分别列出。wall 剩余项只能称未归因的非 engine 解码/prefill 时间，可能包含 HTTP、Java 处理、校验、数据存取、runner 准备/readback、调度及记录舍入，不能全部归给 CPU 或网络。evaluatedPromptTokens 只含实际重算的 prompt，缓存前缀不在内；本报告没有把它当完整输入长度。

| 调用类型 | 基线次数 | medium 次数 | off16k 次数 | 基线生成 tokens / 秒 | medium 生成 tokens / 秒 | off16k 生成 tokens / 秒 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| coverage | 2 | 1 | 2 | 182 / 5.503 | 1040 / 32.798 | 188 / 5.719 |
| joint_review | 0 | 0 | 1 | 0 / 0.000 | 0 / 0.000 | 486 / 15.299 |
| primary | 23 | 23 | 23 | 11418 / 372.524 | 76566 / 2612.654 | 10665 / 348.334 |
| recall | 8 | 8 | 8 | 2190 / 69.583 | 15522 / 492.676 | 2175 / 68.269 |
| repair | 8 | 7 | 6 | 4072 / 131.000 | 18625 / 686.141 | 2745 / 87.872 |

23 次 primary 对应 23 个来源 part；repair 为格式/候选修复，coverage 为遗漏覆盖检查，recall 是后续来源回查；joint_review 为后置联合证据关系审核。medium 对原基线新增耗时没有来自更多轮调用：primary 和 recall 数相同，repair 少 1 次，coverage 少 1 次。off16k 的 40 次调用包括 39 个 part attempt 和 1 个 advancePaymentAdopted 联合关系审核；该最后调用也已与 rawResponses 和 engine 记录准确配对。类型统计来源为各轮 trace 的 attempt.kind / relations；相应时间和 tokens 来自逐项配对后的日志/engine metrics。

用基线加权速度作可复算的顺序记账，可以把增加的等待分为：

- 更多生成 tokens，保持基线速度：**3041.438 秒（50.69 分钟）**；计算 `(111753 - 17862) / baseline weighted tok/s`。
- 新 token 工作量上的较低加权吞吐：**204.222 秒（3.40 分钟）**；计算 `medium generation - 111753 / baseline weighted tok/s`。
- prefill 差值：**0.714 秒**。
- 其余 wall 差值：**5.141 秒**。

合计 **3251.515 秒**，与两轮 elapsed 差值一致。这个记账说明工作量是主要因素；32K→64K、output、thinking 和 seed 同时变化，不能据此把吞吐差归因为上下文单一因素，也不能把记录差值直接当因果效应。

medium 的三次最慢调用为 task **45422 / 49966 / 52926**，位于文档 10、11，分别约 **18.16 / 22.79 / 19.09 tokens/s**。日志时间见 `medium4k.log` 行 687–689、736–738、825–827；大部分其他 medium 调用约 30–32 tokens/s。上述低速段落在人工采样结束到独立采样恢复的空档内，现有记录无法判定是共享 GPU 竞争、温度/功耗、运行器路径、上下文处理或其他暂态原因。没有把单个 GPUutil 百分比当根因。

**GPU 运行位置的核对结果有明确证据缺口。** 三轮启动 stderr 仅含 server/common 层的加载与槽位日志，stdout 均为空；没有保存实际 offloaded 层数、CPU model buffer、CUDA model buffer、GPU KV/compute buffer 的诊断。`--gpu-layers 99` 是请求值，不能证明实际所有层和全部算子都在 GPU，也不能据此排除局部 CPU fallback。分析 JSON 的 `startupEvidence` 将这些未知值保留为 null。

off16k 运行时（UTC 03:33:27）的 PID 110924 已按 receipt 的 executable、marker、creation UTC 验证归属，加载了 ggml-cuda.dll、cudart64_12.dll、nvcuda.dll，且 nvidia-smi 的 compute-app 列表中存在该 PID；这证明该考试运行进程已使用 CUDA GPU。ggml-cpu-alderlake.dll 同时加载是正常可用后端，单凭加载不能推断 CPU 回退。Windows WDDM 将每进程 used_gpu_memory 返回 [N/A]，没有模型单独显存数字。这一运行时快照仍不能补成各层/缓冲位置验收，也不能回推 baseline/medium 的逐层位置。证据为 `current.off16k.moduleReadback`。后续若要定性硬件瓶颈，需要在受控启动中保存核心层/缓冲日志，再配对 profiler、功耗/温度/时钟、CPU/GPU 时间；本次未干预服务。

medium 有 **164 条**位于实际 trace 区间的整卡采样，观测最高 used **11743 MiB**，最小 free **4427 MiB**；两项均来源于 resumed JSONL 第 **37 行**，UTC `2026-10-08T02:57:40.674384+00:00`。采样 GPUutil 范围 **86–100%**，中位数 **97%**。初始约 **451.0 秒**没有观察，最大中间空档 **976.9 秒**。这些数字包含桌面和其他 GPU 客户端，属于间歇观测，不是模型单独显存、连续峰值、SM occupancy、显存带宽饱和率或硬件上限。末尾模型卸载后的样本已排除。

按当前运行器和这类串行负载的实测加权速度，**每额外 1,000 tokens 约需 34.2 秒纯生成**；一次 **4,096 思考 + 1,000 最终输出**约 **174.4 秒**，还要加 prefill 和应用成本。111,753 tokens 本身已经需要约 **63.74 分钟**纯生成。假设仍生成这些 tokens、保留当前其他成本，若要求 10 分钟结束，则解码约需 **217.7 tokens/s（当前 7.45 倍）**。这是条件规划算术，不是宣称 A4000 已达到不可优化的理论上限。

新增 **64K F16 / 16K output / thinking off** 对照已完成：**605.156 秒、40 次调用、16259 generated tokens、30.940 tokens/s 纯解码**。保持相同 64K/16K/seed42，medium 比 off16k 生成量为 **6.873 倍**、wall 为 **6.463 倍**；这比原始 32K 基线更直接支持思考工作量为主因。off16k 没有独立重编码，表格保留未知以避免在后续长 prompt 试验时额外调用 API。两轮不是随机化重复实验，环境变化和生成差异仍可能影响局部吞吐。

准确率对应的代价也要分开看：基线正确且有来源的 required-value candidate 为 **58/61**，medium 为 **60/61**，off16k 为 **58/61**；三轮 strict PASS 均为 **55**。medium 有 **23 REVIEW / 1 FAIL**；off16k 有 **21 REVIEW / 3 FAIL**，且历史/其他来源冲突候选为 **1**。candidate coverage 不是自动采用或完整合同验收；扩大上下文/输出但关闭 thinking 没有在这一次考试提高正确覆盖，因此不能把“更快”当业务通过。来源为三轮 `.score $.summary`。用户提供的 MiniMax M3 能做对是有用基线信息，本次没有 MiniMax 的同题 wall/token 记录，不能计算它相对本机的速度倍数。

另有单次真实长 prompt 容量对照（`long55k.assessment` / `long55k.requestSummary`）：服务报告 **65536 context**，输入 **54997 tokens**，缓存 **0**，prefill **101.452 秒 / 542.097 tokens/s**；生成 **265 tokens**，decode **11.253 秒 / 23.460 tokens/s**；HTTP 总时间 **112.751 秒**。`reasoning_chars=0`、`finish_reason=stop`，合成事实及证据断言 **PASS**，生成尝试 **1 次**。短分段 off16k 考试的加权 **30.940 tokens/s** 与此 **23.460 tokens/s**来自不同负载，不能当作只改变 context 参数的孤立因果。55K 合成检索成功证明该输入可执行及这些断言通过，不构成真实合同准确率或 128K/256K 能力验收。

复算命令：`python scripts/analyze-drafting-performance.py`。该脚本只读取已保存文件，无网络或模型生成；`final-content-retokenization.json` 是此前通过同模型 `/tokenize` 独立重编码最终文本的收据，不会在复算时再次调用模型。输出 JSON 包含每次调用、证据定位、来源 SHA256 和上述公式。

性能 JSON：`D:\R&D\ConSense\.local\state\drafting-exam\performance-comparison.json`，SHA256 `d8db4aaac63e6b54fff0a4d6f0dcf66c387127c82758870c780dd210ae63ef74`。

| Source ID | 文件 | SHA256 | JSON / 日志定位 |
| --- | --- | --- | --- |
| retokenization | `D:\R&D\ConSense\.local\state\drafting-exam\final-content-retokenization.json` | `295307f62e24a78a52f226cf9ad06befe87dd6e86479fbab70ccaf7aa353e9e4` | $.runs[*].calls; $.caveat |
| baseline.manifest | `D:\R&D\ConSense\.local\state\drafting-exam\baseline-32k-off\manifest.json` | `08ed215f91e97c2fff73cb6a99a4ff3801fc0a0ad7a6ab5da8fbebed2753fe2a` | $.elapsedSeconds; $.extractionStartedAt; $.finishedAt; $.inputFiles; $.templateFiles |
| baseline.trace | `D:\R&D\ConSense\.local\state\drafting-exam\baseline-32k-off\trace.json` | `7bcb952505b556b39a252bc2dd47ab70d33db38d2f70037cc2995e33decd0acc` | $.data.startedAt; $.data.finishedAt; $.data.parts[*].attempts; $.data.relations; $.data.rawResponses |
| baseline.metrics | `D:\R&D\ConSense\.local\state\drafting-exam\baseline-32k-off\engine-metrics.json` | `5c28f7b98df6b6af7330827ed9b04dec0c39a87a5fa8e4a0704cd809b32595af` | $.scope; $.calls[*] |
| baseline.log | `D:\R&D\ConSense\.local\logs\bonsai-20261008-093417-514.stderr.log` | `9f586d015b07e78a49d6ea61a9d49ceb87cf3096e811ad846063489992470a22` | Per-call prompt/eval/total lines specified in runs[*].calls[*].logLines |
| baseline.stdout | `D:\R&D\ConSense\.local\logs\bonsai-20261008-093417-514.stdout.log` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` | Entire file; saved length 0 bytes |
| baseline.gpu-before | `D:\R&D\ConSense\.local\state\drafting-exam\baseline-32k-off\gpu-before.json` | `e4963009a296d831228ca7e086fde229fe70fa22b6b77e957050ca328c8877d6` | $.capturedAt; $.csv |
| baseline.gpu-after | `D:\R&D\ConSense\.local\state\drafting-exam\baseline-32k-off\gpu-after.json` | `7b9635b772902fb096baacfa1f6a0783e063c4768505a325bee24c79b6a1ab05` | $.capturedAt; $.csv |
| baseline.score | `D:\R&D\ConSense\.local\state\drafting-exam\baseline-32k-off\scoring\drafting-score.json` | `e90df1f3642630f8751b1423820c262a52a5643654c3b1b60509446fa7669c63` | $.summary; $.globalFailures; $.boundaryChecks |
| baseline.runtime | `D:\R&D\ConSense\.local\state\drafting-exam\baseline-32k-off\runtime-linked-after-baseline.json` | `1fe1bab89541a2ee79788da8285baa0b0d02532283ff074edb4be9933225dada` | $.model.Arguments; $.captureTiming or $.capturedAt; $.modelReceipt |
| medium4k.manifest | `D:\R&D\ConSense\.local\state\drafting-exam\campaign-medium4k-20261008\medium4k\manifest.json` | `607887acc2f90e50011ca908598fc1fb10176a624c3526f98118788e1adcd70b` | $.elapsedSeconds; $.extractionStartedAt; $.finishedAt; $.inputFiles; $.templateFiles |
| medium4k.trace | `D:\R&D\ConSense\.local\state\drafting-exam\campaign-medium4k-20261008\medium4k\trace.json` | `6225ea789ff5b6fbc324114397888495c85787b313cb42353400b9d614acc2b4` | $.data.startedAt; $.data.finishedAt; $.data.parts[*].attempts; $.data.relations; $.data.rawResponses |
| medium4k.metrics | `D:\R&D\ConSense\.local\state\drafting-exam\campaign-medium4k-20261008\medium4k\engine-metrics.json` | `91d1b1da9360c8558c5080acf45413a69c9089103fcbd431fe676953e123ad0e` | $.scope; $.calls[*] |
| medium4k.log | `D:\R&D\ConSense\.local\logs\bonsai-20261008-101216-453.stderr.log` | `28b54741dd683dc4422da824e838919e3321d0d228839d2912959601af0e8c75` | Per-call prompt/eval/total lines specified in runs[*].calls[*].logLines |
| medium4k.stdout | `D:\R&D\ConSense\.local\logs\bonsai-20261008-101216-453.stdout.log` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` | Entire file; saved length 0 bytes |
| medium4k.gpu-before | `D:\R&D\ConSense\.local\state\drafting-exam\campaign-medium4k-20261008\medium4k\gpu-before.json` | `9cd7246dfb00cc80a32a3ed442ccf3e4b7fa82cda6a932eb036df00a2180726b` | $.capturedAt; $.csv |
| medium4k.gpu-after | `D:\R&D\ConSense\.local\state\drafting-exam\campaign-medium4k-20261008\medium4k\gpu-after.json` | `07a65a05fb5812cdd7407cf201173b5531c2287cf38e7ebc51af3e68c06cfd50` | $.capturedAt; $.csv |
| medium4k.score | `D:\R&D\ConSense\.local\state\drafting-exam\campaign-medium4k-20261008\medium4k\scoring\drafting-score.json` | `364c425917f6f57b790da07c62b8324d3e82a0ea00cb0ab18a43f7f02a580b30` | $.summary; $.globalFailures; $.boundaryChecks |
| medium4k.runtime | `D:\R&D\ConSense\.local\state\drafting-exam\campaign-medium4k-20261008\medium4k\runtime-before.json` | `7ac6f0c73b11a1c5360ac465ce0e075856b84634b5b435b60d4e0fc22bf93f3f` | $.model.Arguments; $.captureTiming or $.capturedAt; $.modelReceipt |
| off16k.manifest | `D:\R&D\ConSense\.local\state\drafting-exam\campaign-off16k-20261008\off16k\manifest.json` | `a5134219e4944776a6dd2053b4e68576cf6276fc9a9e95d553288bdfc01bb622` | $.elapsedSeconds; $.extractionStartedAt; $.finishedAt; $.inputFiles; $.templateFiles |
| off16k.trace | `D:\R&D\ConSense\.local\state\drafting-exam\campaign-off16k-20261008\off16k\trace.json` | `bafe5478aaef99457a47ea9a508727c49b8ad4224104b3a84bc96cdc41d48910` | $.data.startedAt; $.data.finishedAt; $.data.parts[*].attempts; $.data.relations; $.data.rawResponses |
| off16k.metrics | `D:\R&D\ConSense\.local\state\drafting-exam\campaign-off16k-20261008\off16k\engine-metrics.json` | `f1329471ee7837f4d5662b7f733c253079f14fb12805f6e306b12dc9e2043023` | $.scope; $.calls[*] |
| off16k.log | `D:\R&D\ConSense\.local\logs\bonsai-20261008-112932-704.stderr.log` | `01ab281306466c8bd48f809440b63e30a3535636869e5ae3a5777850adc97b48` | Per-call prompt/eval/total lines specified in runs[*].calls[*].logLines |
| off16k.stdout | `D:\R&D\ConSense\.local\logs\bonsai-20261008-112932-704.stdout.log` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` | Entire file; saved length 0 bytes |
| off16k.gpu-before | `D:\R&D\ConSense\.local\state\drafting-exam\campaign-off16k-20261008\off16k\gpu-before.json` | `ff8df088b924ee7bcf45ea9b89ed861b79fab7d2b52b5e2d7b3fa3d602edb942` | $.capturedAt; $.csv |
| off16k.gpu-after | `D:\R&D\ConSense\.local\state\drafting-exam\campaign-off16k-20261008\off16k\gpu-after.json` | `b1638ffa7844d620fdbb944e46cc8a81ad368cb5d124923c9cf028e7638dddd2` | $.capturedAt; $.csv |
| off16k.score | `D:\R&D\ConSense\.local\state\drafting-exam\campaign-off16k-20261008\off16k\scoring\drafting-score.json` | `c7e34b2298b7a53e1906ed0b6e34cc9e94a81c8b11b037b5e0b49e5b0c614a86` | $.summary; $.globalFailures; $.boundaryChecks |
| off16k.runtime | `D:\R&D\ConSense\.local\state\drafting-exam\campaign-off16k-20261008\off16k\runtime-before.json` | `530048c6f8835a4189b76dbaedd2f043d727a66276b06acf56d0acd9f10c8585` | $.model.Arguments; $.captureTiming or $.capturedAt; $.modelReceipt |
| medium4k-gpu-samples.jsonl | `D:\R&D\ConSense\.local\state\drafting-exam\campaign-medium4k-20261008\medium4k-gpu-samples.jsonl` | `2226c965bb3394ee7c5f399321dc27e5b23b1980a8ebb0650cc381ac51461ad4` | JSONL row index; active samples filtered to trace startedAt <= observedAtUtc <= finishedAt |
| medium4k-observer-resumed.jsonl | `D:\R&D\ConSense\.local\state\drafting-exam\campaign-medium4k-20261008\medium4k-observer-resumed.jsonl` | `ffdce8d4fda090b0720f3b1c6b9103fc67c7f137f1d515dc22179539213f9382` | JSONL row index; active samples filtered to trace startedAt <= observedAtUtc <= finishedAt |
| current.off16k.moduleReadback | `D:\R&D\ConSense\.local\state\drafting-exam\current-off16k-gpu-module-readback.json` | `f9f5327aab9dfc770daa4e8c0383c4607e23db8621e12481673d128f3c100f2c` | $.Scope; $.OwnershipVerified; $.Modules; $.ComputeAppCsv |
| long55k.assessment | `D:\R&D\ConSense\.local\state\capacity\run-55k-off8k-20261008\assessment.json` | `8af0785127222d02725eb3eddf2b8f8c692e30de2facc8362018c1b2ff9d668a` | $.scope; $.verdict; $.usage; $.timings; $.finish_reason; $.reasoning_chars |
| long55k.requestSummary | `D:\R&D\ConSense\.local\state\capacity\run-55k-off8k-20261008\request-summary.json` | `741fde50ee9c3ac17c02212d2ee103b32547e930f6cc250b1a67449ba2d0f7ad` | $.scope; $.generation_attempts; $.reported_model_context; $.generation_elapsed_seconds; $.timings; $.usage |
