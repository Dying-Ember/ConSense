# OER / Vetting / Advisory 实施状态 — 2026-10-13

用户于北京时间 2026-10-13 凌晨调用 `implement-spec`，授权实现 [主规格](./oer-vetting-advisory-next-development-20261012.md)，要求最小完整代码，并纠正截止为 **今天 08:30 准备好本地代码，09:00 由用户提交 GitHub**。这覆盖此前的 spec-only 授权状态；原件质量、真实模型、浏览器及 H800 门仍分别验收。

## 工作入口和保护范围

- 集成工作区：`C:/Users/Administrator/.codex/worktrees/oer-next-delivery/ConSense`。
- 集成分支：`codex/oer-vetting-advisory-20261013`。
- 开始前源码快照：`46206d441ee7e16b8cff7cbc76fc1632529ffca1`；原主分支 HEAD：`285fc2e3bdf97792aeae00a31dbde2c69ebcde60`。既有未提交成果已保存为独立本地基线，主工作区保持原状。本轮增量以快照为比较基点。
- 配置、初始文件 SHA、patch 和测试日志留在 `C:/Users/Administrator/Documents/Codex/consense-implementation-20261013/`，不提交配置备份或凭据。
- Drafting 冻结；图纸只识别身份并排除；冻结 Local05、原件、模型及既有服务保护。测试只用隔离数据，不迁移或切换活动数据库。
- 不提前替用户 push、创建远端 issue / PR 或部署。现有本地任务图作为实施 tracker。

## Ponytail 和续跑

先安装官方 `DietrichGebert/ponytail` 5.1.0，再通过 `hooks/list → config/batchWrite → hooks/list` 信任三个准确哈希的 hooks；随后才开始开发。三个 hooks 均 enabled / trusted。独立只读 Codex 生命周期实际完成 SessionStart、UserPromptSubmit 并注入 full 规则；SubagentStart 配置已确认，其单独运行验收尚未执行。当前桌面回合未重启，执行者已读取安装的 SKILL 并采用 full 规则。

本聊天续跑 heartbeat `consense-08-30` 每 30 分钟检查并推进，至 2026-10-13 08:30 Asia/Shanghai 截止。无可行动变化保持安静，完成、失败或需用户处理时通知。它承担续跑；Ponytail 承担最小实现规则。

## 时间预算

| 北京时间 | 工作和退出条件 |
| --- | --- |
| 00:30–02:00 | 共同证据、文件身份、来源回取；保存各模块契约 |
| 02:00–05:00 | 并行 Advisory 会话和 Vetting checkpoint / 状态 / 报告；持续集成 |
| 05:00–07:00 | 前端联动、公开工作流验证、导出和回归；处理实际失败 |
| 07:00–08:30 | 停止非必要扩展，独立 Standards / Spec 审查、修复、构建、冻结及提交说明 |

时间是预算，不是已完成事实。解析候选、Qdrant Server 迁移、离线制品可以提前准备；真实源质量、模型、扫描和 H800 未过门不会被文档或本地契约测试替代。

## 当前任务与验收

| 工作 | 所有者 | 当前证据 |
| --- | --- | --- |
| 范围路由、共同契约与集成 | root | 开始实施；完成证据待追加 |
| Advisory SQL session / 相容有界历史 | advisory_impl | 独立工作区实施中；迁移 V16 |
| Vetting checkpoint / owner / coverage / snapshot | vetting_impl | 独立工作区实施中；迁移 V17 |
| 固定 / 项目引用和 Vue session / 导航 | frontend_impl | 独立工作区实施中 |
| NEXT-02 解析候选和 NEXT-04 迁移 | 待排入剩余时间 | 未执行，不自动发布活动库 |
| 真实业务 / 浏览器 / 导出、H800、独立复核 | 集成后 | 待执行；已知失败保持 |

每次集成追加具体提交、命令 / 结果和未通过门。任务关闭遵循 [任务图](./oer-vetting-advisory-next-development-tickets-20261012.md) 的真实证据要求。
