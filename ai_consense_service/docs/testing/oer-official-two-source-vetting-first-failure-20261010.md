# 官方 GCC／SCC 两源首次 Vetting：运行准备失败

2026-10-10，Asia/Shanghai。本轮只使用已采用的 GCC65–70 六页和项目 SCC17 相关正文，非完整合同；2d／2e 未进入生成输入，humanConfirmed=false。首次失败完整保留，下一次明确发起的任务另存，不覆盖或自动重试。

## 实际任务及失败

隔离 Java PID57464／127.0.0.1:18083，新性能 Jar SHA `762b671197f04015554f79adc4bcb0d90b1d275b86cbe958fafd8de601faa76e`。collector run 为 `.local/state/oer-official-q16-first-run-20261010/vetting-080202-ac8bed/`，session71771 退出1；capture receipt SHA `4dec094a7bcf12159136318f2fd0fff269031c801ff7f93d5b0cc0d614b081f6`。

实际 job `7fe80981-0d90-4eea-ae27-eb5df1c522b5`，08:02:12.426615 开始，08:02:23.968378 终态 FAILED，错误 `SOURCE_ORIGINAL_UNAVAILABLE`。completedUnits=0／19、reviewedDocuments=0／2、semanticTopics为空；这些是任务及材料覆盖计数，不是模型正确率。失败发生在 `VettingService` 初始化 canonical session、核验原件的阶段，尚未进入语义审查调用。首次 poll raw SHA `2f8908b2f75b71b782485a53b927d784364db6b2792747055f18c4dadcdb9595`。收集器在失败终态停止，没有导出报告或发起第二个任务；不能把本轮称为报告格式／法律质量验收。

运行前独立来源 manifest SHA `8bd8dc8679c4c339f1778908d47faa791febedad06b7fedf9145ce7046a662c3`，sourceSnapshot `05880fd4bf694b2655bd0b904f9544299382352fc0ffacb0fac8322321477727`。独立评价期望在读报告前已冻结，从运行前清单及实际启动 POST 绑定 jobId，expected SHA `1ac203122ec8d75a46d565c46213ad6a859bdb6e26cf881d0aa9ef7833e05356`；它不是报告评分结果。

## 已核原因与修正边界

受控切换保留了数据库的既有绝对 `storage_path`，仍指向旧 owned run 的 `isolated-storage`，并保留该目录和原件。新启动 runner 却将 `CONSENSE_STORAGE_ROOT` 指向新 run 的备份目录。`CanonicalCurrentSourceReader` 第74行要求原件真实路径位于配置的 storage root 内；两者不一致，所以存在且 SHA 一致的原件仍被拒绝。这是测试实例准备配置错误，不以放宽来源门控解决。

JDBC 已验证两源六个完整raw字段、bindings与两条问答历史在正常 checkpoint／shutdown／重开后保持；GCC仍PARTIAL、SCC仍PARSED。恢复 receipt SHA `17f85ee4c8d5363aab229796c2085ef773d94bd109d76390e64397a45e47d8b0`，闭库／store复制 receipt SHA `28308cd2181e621c4d481cb72c4a90abaaa39224af96e2aa950134d38a73c853`。这些证明原件与历史保留，不证明配置正确或Vetting通过。

修正仅将新 owned runtime 的 storage root 对应实际权威原件目录，保持产品门控、Jar、模型、24／3、8874／8875、数据库来源路径及原件字节不变；先保存含本次失败任务的持久库，再启动另一隔离实例和明确的新一轮。准备与执行收据分别保存。8080／5173及其他业务服务不切换，Bonsai不恢复。

保护服务的首次post-check把先前CIM时间与Get-Process高精度时间混用，出现1–9 ticks差异，失败原收据保留。root随后用与baseline相同的CIM字段逐项重读：五个端口的地址／PID／executable及实际已记录的创建时间表示全部精确相等，容差0；不声称拥有切换前完整Get-Process ticks证据。见 `.local/state/oer-canonical-recheck-design-audit-20261010/copied-trial-v1/prepared-package-v1/guarded-activation-v3/root-protected-listeners-same-provider-after.json`。
