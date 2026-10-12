# Bonsai 本地上下文与显存限制：保存证据的归因边界

日期：2026-10-12。按用户最新补充，重新区分本地运行系统FAIL与模型本身的能力。只读取已保存配置、部署收据、第五轮trace/引擎日志及历史报告；不读取密钥、不查询live进程/GPU、不调用模型、不运行新测试、不修改harness或服务。范围入口及handoff已先读取。

## 1. 结论分为两层

**本机实际部署只有64K上下文，不能据此否定H800上给予足够上下文后的Bonsai。** 已保存服务元数据为 `n_ctx=65536`、`n_ctx_train=262144`；更早基线仅32K。32K/64K是部署选择，不能说Bonsai原生只能32K/64K。显存、KV配方、harness输入策略、输出协议/解码、底座及权重量化都可能影响最终体验；当前没有足上下文或同精度对照，模型层面的归因仍pending。

**第五轮已保存的两类关键残差，不能直接归因于64K耗尽。** 该轮每个primary都带本part完整短源，28次生成均 `truncated=0`，生成量最高5643，低于保存的16384总生成上限；slot停止计数最高9375。Bills协议/decoder选择、Sections required字段和不连续引文的失败仍成立，但证据没有显示它们是窗口满或显存不足造成。

因此选型口径应是“当前本机Bonsai配方未通过完整业务门”，不写成“Bonsai模型能力已证实不行”或“更大显存也必然无用”。资源假说也不能覆盖现有失败：只有同输入的足上下文、受控精度/运行配方对照才能完成该归因，本轮不执行。

## 2. 配置与实际读回

以下路径均相对 `D:\R&D\ConSense`。

| 保存证据 | 精确位置 | 值及含义 |
| --- | --- | --- |
| 第五轮运行前配置 | `.local/state/harness-iterations-20261009/round5/real-run/runtime-before.json`，`$.Model.ContextSize` / `$.Model.Arguments` | 65536；单槽parallel=1；K/V均f16；n-predict=16384；reasoning on / medium / reasoning-budget4096；batch512 / ubatch128 |
| 部署收据实际models读回 | `.local/state/deployments/20261008-122202-b420c762/receipt.json`，`$.Models.data[0].meta`，保存文本行101–102 | n_ctx65536 / n_ctx_train262144；仅证明该历史读回，不冒称本轮live状态 |
| 同一收据保存启动命令 | 上述 `receipt.json`，`$.ModelCommandLine`，行111 | ctx65536、F16 K/V、16K总生成，与第五轮配置一致；没有显式 `--no-context-shift` |
| 当前磁盘配置文件 | `.local/runtime.json`，`$.Model.ContextSize` / `$.Model.Arguments` | 仍记录同一64K/F16/medium/16K配方；配置文件不证明当前进程实际参数 |
| 历史容量报告 | `docs/bonsai-capacity-assessment.md:7,127,131` | 训练上下文262144；历史32K及后续64K实际读回；55K合成容量测试。该报告的历史GPU数字本轮未重采 |

**ctx_shift保持unknown：**没有显式禁止shift的参数，不能推断shift被禁用，也不能把运行器默认值当作该次已读取的实际配置。在保存的第五轮引擎区间中没有找到context shift事件；这仅是该日志范围的观察，不证明所有历史请求从未shift。`--gpu-layers 99`同样是请求值，不证明全部算子实际GPU执行。

## 3. 第五轮输入身份与分片

trace是 `{code,message,data}` envelope，内容位于 `$.data`；不能直接读根级 `parts`而得到假零。来源为 `.local/state/harness-iterations-20261009/round5/real-run/trace.json`。

- `$.data.parts`有15个part，partId为220:0至234:0，全部partIndex=0；`$.data.rawResponses`有28个字符串。原始HTTP响应对象/usage/finish_reason没有存进这些字符串。
- 全部15个primary满足 `attempt.context.sourceStart=0`，`sourceEnd=part.sourceText.Length`，`context.sourceText.Length=part.sourceText.Length`。长度为789–1417个字符，未将字符数称为token数。
- Bills的part `222:0`（`parts[2]`）primary source范围0–1110，sourceText长度1110；其recall是290–1083的793字符子范围。
- Sections的part `231:0`（`parts[11]`）primary与repair范围均0–1299，sourceText/context长度均1299。初次designation落在id、repair删掉引文中间句，不能归为该短源没有进入请求。
- repair/recall按目标字段缩小范围，是harness的输入选取策略；`context.stopReason=budget_exhausted`等标记是来源补查预算状态，不能当作GPU OOM、token窗口溢出或API `finish_reason=length`。

这些是保存part及attempt上下文的完整性，未重新拿原DOCX逐字校验，也不证明整个项目/所有跨来源上下文已经一次性给模型。联合信息排包仍可能影响更复杂任务。

## 4. 第五轮逐请求token记录

来源为该轮 `engine-metrics.json` 的 `$.calls[index]`；index为零基。prompt-eval只计实际重算token，不含复用缓存前缀，**不是完整输入token数**。generated计引擎全部生成，不能拆成精确reasoning/最终文本。

| calls index | evaluatedPromptTokens | generatedTokens | engineTruncatedFlag | outputCapReached |
| ---: | ---: | ---: | ---: | --- |
| 0 | 268 | 1152 | 0 | false |
| 1 | 253 | 1581 | 0 | false |
| 2 | 351 | 5643 | 0 | false |
| 3 | 283 | 2697 | 0 | false |
| 4 | 256 | 2585 | 0 | false |
| 5 | 1281 | 1206 | 0 | false |
| 6 | 285 | 871 | 0 | false |
| 7 | 1246 | 688 | 0 | false |
| 8 | 254 | 2382 | 0 | false |
| 9 | 276 | 1197 | 0 | false |
| 10 | 321 | 2025 | 0 | false |
| 11 | 1297 | 420 | 0 | false |
| 12 | 292 | 1743 | 0 | false |
| 13 | 1244 | 1599 | 0 | false |
| 14 | 383 | 1826 | 0 | false |
| 15 | 344 | 4304 | 0 | false |
| 16 | 1400 | 1230 | 0 | false |
| 17 | 390 | 2406 | 0 | false |
| 18 | 341 | 2569 | 0 | false |
| 19 | 322 | 3300 | 0 | false |
| 20 | 1288 | 4458 | 0 | false |
| 21 | 1137 | 692 | 0 | false |
| 22 | 1315 | 581 | 0 | false |
| 23 | 1207 | 1367 | 0 | false |
| 24 | 1192 | 320 | 0 | false |
| 25 | 1245 | 788 | 0 | false |
| 26 | 1196 | 507 | 0 | false |
| 27 | 1338 | 2952 | 0 | false |

28条中，prompt-eval范围253–1400，generated范围320–5643，truncated非零0条、cap true 0条。`$.outputCapReachedCalls=0`。

引擎区间日志 `engine-extraction-interval.log`另有28个slot release：停止时 `n_tokens`范围1511–9375，最大为task646365（`rg -n`行113），其次task671295为8029（行451）、task681818为7003（行580），均truncated0。日志含重复CR，PowerShell行号与按LF计数的rg行号不同；本报告采用rg，并保留task ID定位。slot计数是运行器内部计数，不能直接替换API完整prompt_tokens；没有通过减generated反推精确输入。

保存的hash-bound性能复核位于 `.local/state/harness-iterations-20261009/performance-summary/final-two-rounds/report.json` / `report.md`，第五轮trace/engine/backend调用数28/28/28、RAW对应一致。记录53,089生成token、37.880秒prompt-eval、1706.283秒generation、1749.474秒wall；generation占97.53%。这些是生成负载记账，不能推出显存不足的因果。

## 5. 不能补造的缺口

`engine-metrics.json`的 `$.finishReason=null`，其caveat明确普通Drafting adapter仅保留content，引擎日志不能建立API finish_reason。因此不能声称28次全部 `finish_reason=stop`，也不能把null当length。trace没有API usage，完整输入token、缓存token及精确reasoning usage保持unknown。

保存引擎区间没有找到OOM/窗口溢出/shift事件，不等于证明所有GPU路径无问题；未读取live GPU或实际offload层/CPU buffer。历史报告也保留了启动日志缺少这些诊断的状态。显存压力或缓存精度导致质量变化，目前没有本项目受控实验支持或排除。

## 6. 历史18项分片与第五轮分开

`docs/drafting-model-benchmark-results.md:19`记录另一组61有值/18应空的考试：防水范围两个sourceText分片分别覆盖前16项、后7项，没有单次请求同时覆盖全部18项；primary/repair还出现标点改写、引用/填值不配对、字符串返回数组等问题。这是明确的输入分片与输出/接收混合因素，不能简化为27B太小或4K思考不够。

该组32K/off/2K得到58/61，64K/medium/16K得到60/61，64K/off/16K仍58/61；变化同时包含thinking等参数，不是只改变上下文的因果实验。它既不能证明256K无帮助，也不能保证单纯扩窗能修复。与第五轮58有值/21空值不合并。

历史55K合成请求在64K/F16/off下有API `usage.prompt_tokens=54997`、生成265、`finish_reason=stop`；见容量报告及 `.local/state/capacity/run-55k-off8k-20261008/`。这是短输出合成容量成功，不能补上第五轮缺失usage，也不代表真实合同或256K验收。

## 7. 证据固定与后续口径

| 文件 | 本轮重算SHA256 |
| --- | --- |
| round5/real-run/trace.json | A4ED646E913DAAB861DB9B3D7FEEAC918F79DA811A6EDDAB947B5FBC52EC6560 |
| round5/real-run/runtime-before.json | 77136F042CBB844953776EF8669334E1180FA55A10B97095B324EBCCC132D267 |
| round5/real-run/engine-metrics.json | 5D066F286B45DE01981CE428D36DCF7BF14288C8F392E24EE971E508A4F508DA |
| round5/real-run/engine-extraction-interval.log | B2D3539DEF2CEE3F4E0FE1478572C394655E6FD34A2184DBE3B1160629D74785 |
| deployments/20261008-122202-b420c762/receipt.json | 09327B1137B2117D2FFC0519A2B528A8CACE5260F475400278CF5B1ED133012C |

表中round5前缀为 `.local/state/harness-iterations-20261009/`，deployments前缀为 `.local/state/`。保存证据保持原样；本报告不触发重新分词、模型加载、测试或服务切换。

当前推荐的调整是保留系统FAIL、撤回无受控支持的模型级否定；H800足上下文Bonsai为尚未验收的对照候选。同时明确第五轮已观察残差没有窗口耗尽证据，资源扩容不自动修复严格schema、连续引用或decoder选择错误。
