# Drafting 运行结果只读对照

`scripts/compare-drafting-runs.py` 读取已有真实 Drafting 考题运行的保存文件，并生成中文 Markdown 与 JSON 对照。它不发模型请求、不改答案、不改评分规则、不确认候选、不生成合同。先运行现有 `validate-drafting-fixture.py` 对已保存答案重新评分，再运行本脚本比较成绩。

在项目根目录的 PowerShell 中：

```powershell
python scripts/compare-drafting-runs.py --runs .local/state/drafting-exam/baseline-32k-off .local/state/drafting-exam/campaign-medium4k-20261008/medium4k --out-dir .local/state/drafting-exam/comparison-medium4k
```

`--runs` 第一项作为基线；可追加多个运行目录。每个目录读取 `manifest.json`、`scoring/drafting-score.json`，以及存在时的 `runtime-before.json` 与 `engine-metrics.json`。完整评分应与 manifest 的完成状态、项目身份及逐项账目一致。运行中没有完整评分时显示 `PENDING`；运行已完成但尚未评分显示 `AWAITING_SCORE`。它们不进入 best 排序，也不会因待完成状态导致脚本报错。目录不存在或无法生成报告时退出码为 2；退出码 0 仅表示报告已写入，并不表示考题 PASS。

脚本输出 `drafting-comparison.json` 与 `drafting-comparison.md`。只有考题阶段、参考答案身份、输入与模板字节身份和期望字段账目与基线一致的完整结果可参与排序。先要求四个刻意缺失字段全留空、全部应留空字段无误填、无错误/冲突/来源核验失败候选，且全部记录的边界检查通过；再按正确且有出处的确定业务值候选覆盖、严格 PASS 数、FAIL 数、REVIEW 数、耗时比较。安全约束失败的运行不会成为 best。best 仅指这些保存运行中观察到的最佳成绩；它可能仍然是 FAIL 或 REVIEW。

`REVIEW` 始终保留为待复核，不合并进 PASS。候选检出、引用字面支持诊断、当前显示值和人工采用分别呈现：非空显示建议不代表其值正确，也不代表已被人工采用。每个统计包含来源文件 SHA-256 与 JSON 路径；逐字段改善/退步保留两个原始评分文件身份与 ledger key。mixed 变化会同时列入改善和退步。

上下文、输出、thinking、思考预算和 seed 只从记录的 `model.Arguments` / `Model.Arguments` 读取；缺失参数显示 `unknown`，不从目录名猜配置。缺少 `runtime-before.json` 时，优先读取运行内明确后采的 `runtime-linked-after-baseline.json`，标为 `post_run_linked_readback` 并保留其原配置来源与 hash。若第一项没有这两份证据，而后续 campaign 的父目录存在 `runtime-original.json`，脚本会将其标注为 `post_run_original_readback`。两者都是后取的原配置读回，不是预采集，不能独立证明基线当时实际使用的参数。读回配置也不等于本比较脚本核验了进程命令行。

模型日志汇总可能包含同一时间段内其他客户端请求；缓存使 evaluated prompt token 数小于完整输入 token 数。输出上限命中计数与未知 `finish_reason` 原样报告，不能据此伪装为 API 正常 stop 或完整 reasoning token 分拆。即使 79 个输入全部 PASS，Bill 9/10/11 分发、安全子项关系、标准还款条款保持、检查条文去重及最终 NTT/SCT/SCC 合同生成仍须另外验收。
