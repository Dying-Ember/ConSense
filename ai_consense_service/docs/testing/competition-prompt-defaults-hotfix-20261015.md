# Vetting / Advice 提示词默认值热修

2026-10-15 已完成代码、专项验证与本机部署。用户要求两模块配置页与已确认比赛规范一致，并指出“恢复出厂默认”会刷回旧内容。

原因：Vetting 已共用完整比赛规则；Advice 的配置默认只有 181 字符摘要，数据库还保留旧自定义 system/user。运行时另加完整必需规范没有更新配置默认。先只保存当前值后，恢复默认仍会取旧摘要，这是一次实际交付遗漏。

本次让 Advice 的配置默认和运行时共用既有完整规范资源；默认规范不再重复作为补充追加，真正的用户补充仍受必需规则约束。未修改两份已确认业务规范正文。Vetting 原完整默认保持，并与 Advice 一起保存、读回及执行实际恢复默认接口验证；Advice 的旧 answer/Mermaid 直接输出 user 模板替换为现有独立答复单元协议，三个占位符保持。

验证和部署：

- 专项镜像从 `4bec277a22cdfe7ee46fe329a9f4d52740341b6b` 加本次五文件构建；不包含尚在回归的来源桥。
- 默认资源一致性与重复 policy 两项先留 2 failures 的真实 RED；修后 AdvisoryPromptDefaultsTest、AdvisoryHarnessTest、VettingPromptPolicyTest 共 21 项通过，0 failures/errors/skipped，package 成功。
- 本机活动 JAR SHA `0d9e61ceebf243bca0b4a8eec2991fe8b19dab713d5c7298554de407e6a9498d`，后端 PID8892；配置和 16 GiB 堆保持，无新增数据库迁移，前端与 relay 保持。
- 两项真实 `POST /api/prompts/{key}/reset` 成功后，再独立 GET 核验：当前值与默认值逐字一致、customized=false。Vetting system 为 6654 字符，SHA `a14a3261203cc04b9e0db10d3dc230d047feb846c0d188f649433b928238c8c8`；Advice 为 9777 字符，SHA `ec23c7a665f803598a82d8aaf41ace65df1e431ac5e4ab24610e61d11ccf0470`。

证据位于忽略目录 `.local/state/advice-binding-hotfix-20261015/prompt-only-release-01/`：source-pins.json、build.log、deployment-receipt.json、reset-persistence-receipt.json 及两次恢复默认原始响应。旧配置备份在上级 prompts-before.private.json，未纳入 Git。上述数字分别来自构建日志、部署及 reset 收据，不是模型效果统计。

本次未调用生成模型、OCR、重新解析或训练；Advice 来源登记热修和完整回归另行记录，不以此专项通过替代。
