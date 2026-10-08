# Bonsai 本地 Drafting：已完成实测结果

2026-10-08。本轮按用户要求固定现有 Drafting harness，比较本机 Bonsai 的上下文、生成预算及 thinking。模型始终为同一 `Ternary-Bonsai-2-27B-PTQ1_0.gguf`，引擎不变；模型输入只有考试区的 15 份 DOCX 和赛方 2a 的三个标准模板。参考答案只用于离线评分，没有上传给模型。

## 最好已测成绩与代价

| 参数 | 正确且有真实出处的候选 | 应留空 | 严格 PASS / REVIEW / FAIL | 时间 | 加权生成速度 | 总生成 tokens |
|---|---:|---:|---|---:|---:|---:|
| 32K F16 / off / 2K 总生成 | 58/61 | 18/18 | 55 / 21 / 3 | 10.99 分钟 | 30.87/s | 17,862 |
| 64K F16 / medium / 4K 每思考块 / 16K 总生成 | **60/61** | 18/18 | 55 / 23 / 1 | 65.18 分钟 | 29.22/s | 111,753 |
| 64K F16 / off / 16K 总生成 | 58/61 | 18/18 | 55 / 21 / 3 | 10.09 分钟 | 30.94/s | 16,259 |

61 项是需要业务值的输入，另外 18 项应保持留空，包含四项刻意缺失。`false`、`0`、`[]` 是有含义的答案，不计作空值。REVIEW 保留为独立结论，人工证据检查不改写自动分数。正确候选覆盖也不代表已人工采用、每个引用都单独充分，或最终合同已经验收。

medium4k 是本轮安全约束通过后的最好已测配置，但仍是 FAIL。它修复了完整 13 个 Bill 父项与 `sections=[]`；`domesticBlocks` 和 `foundationDesignSubmittedWithTender` 的引用支持诊断从 PASS 降为 REVIEW，业务值仍正确。`designResponsibilities` 的三项值正确，引用只有矩阵导言，仍需要完整来源上下文。`nscAlternativeText` 的弯引号变为直引号被单独记录为文字保真差异。

off16k 的 `advancePaymentAdopted` 同时保留正确 true 与错误 false 候选。false 来自“没有改动标准还款参数”的真实句子，不支持“不采用预付款”。该运行被安全约束排除于 best；它有 58 项正确候选，但因冲突只显示 57 项非空建议。

唯一仍未检出的 required 项是防水范围长字符串。实际两个 sourceText 分片分别覆盖前 16 项与后 7 项，没有单次请求同时覆盖全部 18 项；primary/repair 又发生标点改写、引用只覆盖第一项、尾段说明引用与填值不配对，以及应为字符串却返回数组。这些事实不能简化成“27B 太小”或“4K 思考一定不够”。本轮不改变 harness，也没有复跑 MiniMax 官方 API 来核对其成功 trace。

## 时间为何变慢

medium 总生成量增加到基线约 6.26 倍，每 token 解码速度只降低约 5.34%，调用次数从 41 降至 39。两轮 prompt eval 都约 71 秒；medium 约 3,824 秒用于纯生成，约占整轮 97.8%。最终答案独立重分词为基线 17,821、medium 23,349 tokens；medium 的总生成与最终文本相差约 88,404，主要为思考及边界开销估算，不是 API 精确 reasoning usage。

本机实测短输入下约 30 tokens/s，硬件决定绝对等待时间；本次六倍耗时主要来自更多生成工作。没有证据证明显存不足或大量 CPU 回退造成这一倍数差。历史启动日志没有实际 offload 层数，不能仅凭 `--gpu-layers 99` 宣称所有算子都在 GPU。完整分解、采样空档及证据 hash 见 [性能报告](drafting-performance-assessment.md)。

## 超过 32K 的容量验证

64K/F16/off 下，未缓存输入 **54,997 tokens** 的合成合同测试通过。目标原合同、生效补充协议和变更记录分别置于开头、中间、末尾；合同号、雇主、金额相加、控制性完工日期、检查日期、未知邮箱、冲突和三个来源均正确。HTTP 响应用时 112.751 秒，prompt eval 101.452 秒（542.10 tokens/s），生成 265 tokens 用时 11.253 秒（23.46 tokens/s），`finish_reason=stop`。

该测试证明确实完成了超过 32K 的一次推理；8K 是总生成上限，实际仅生成 265，不等于 8K 长输出测试。一次合成案例不覆盖满 64K、128K、真实长合同质量或整份合同生成。回执在 `.local/state/capacity/run-55k-off8k-20261008/`，容量推算与实测边界见 [容量报告](bonsai-capacity-assessment.md)。

## 重跑与快速重新评分

完整固定参数对照会创建独立验收项目，并在结束后恢复原配置：

```powershell
& '.local/tools/powershell/pwsh.exe' -NoProfile -File scripts/benchmark-bonsai-drafting.ps1 -Profiles medium4k -OutputRoot .local/state/drafting-exam/new-campaign
```

只检查已保存结果，不再次调用模型：

```powershell
python scripts/validate-drafting-fixture.py --fixture Ref/simulated_2b_correspondence/03_missing_evidence_test --actual .local/state/drafting-exam/campaign-medium4k-20261008/medium4k/variables.json --trace .local/state/drafting-exam/campaign-medium4k-20261008/medium4k/trace.json --plan .local/state/drafting-exam/campaign-medium4k-20261008/medium4k/plan.json --out-dir .local/state/drafting-exam/recheck-medium4k
python scripts/compare-drafting-runs.py --runs .local/state/drafting-exam/baseline-32k-off .local/state/drafting-exam/campaign-medium4k-20261008/medium4k .local/state/drafting-exam/campaign-off16k-20261008/off16k --out-dir .local/state/drafting-exam/recheck-comparison
python scripts/analyze-drafting-performance.py
```

评分器退出码 0=PASS、1=FAIL、2=输入错误、3=REVIEW。比较脚本退出 0 只表示报告写入成功，不是考试通过。79 项评分之外的 Bill 分发/用途/子项关联、候选采用和完整合同生成明确没有验收。

## 服务恢复与证据

当前运行恢复为原 32K/off/2K 配置。原 runtime SHA256 为 `A612679931DD93A4F92AD4493BD2F6A12AA4440D81008935936435D141167091`，backend JAR 为 `FC007462DFC4ED8B60D166A9606243E0838802E50210248E859D7D0B432A76FC`。medium 首次恢复遇端口检查失败，已保留原始失败 campaign 与后续恢复回执；benchmark 增加了最多 20 秒的端口释放等待，不会通过端口发现来杀其他进程。off16k 及容量试验自动恢复成功，容量试验还核验 backend/frontend 原进程 receipt 不变、三服务 ready、watcher Running。

逐字段与数字来源台账：

- `.local/state/drafting-exam/comparison-all-20261008/drafting-comparison.{json,md}`：三轮评分/参数身份、每字段改善与退步、排名安全约束、source hashes。
- `.local/state/drafting-exam/comparison-all-20261008/off16k-conflict-analysis.{json,md}`：预付款角色混淆的两个原生 DOCX 段。
- `.local/state/drafting-exam/campaign-medium4k-20261008/medium4k/manual-evidence-review-delta.{json,md}`：变化引用、设计矩阵及防水分片/repair 判定。
- `.local/state/drafting-exam/performance-comparison.json`：每调用时间/tokens、日志行、总体公式、来源 hashes。
- `.local/state/capacity/run-55k-off8k-20261008/{capacity-run,assessment,request-summary,artifact-hashes}.json`：55K 单请求实际 usage、终止、21 项断言及服务恢复。
