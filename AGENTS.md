# ConSense 工作入口

涉及比赛资料范围、上传文件身份／路由、OCR、embedding、rerank、模型部署、来源修订、切块或索引时，先读取 [OER 已确认范围与执行规则](ai_consense_service/docs/oer-scope-and-rules.md)，再读取 [实施交接](ai_consense_service/docs/HANDOFF-ocr-embedding-rerank-20261008.md) 的当前状态。

已确认规则以该入口维护；旧日期的讨论和实验记录按历史语境读取。用户最新明确指示优先，发生变更时同步更新规则和相关文档入口，避免再次把已排除材料当必需输入或重复询问已采纳决定。

原件、模型、密钥和运行证据保留在忽略的本地目录。代码、契约测试、真实模型调用、原件质量和前端业务验收分别报告；保留失败和未完成的实际状态。

## 本机 main 热修

自2026-10-23起，本工作目录统一在 `main` 开发与热修，跟踪私仓 `origin/main`。每轮交付完成时，实际工作目录的 `HEAD`、本地 `main`、`origin/main` 和私仓远端 `refs/heads/main` 必须指向同一提交；仅代码树相同不算对齐。历史分支和worktree保留作参考；除用户另行指定，后续修改直接沿 `main` 提交、推送和热修。操作与核验流程见[私仓同步规则](docs/PRIVATE-REPOSITORY-SYNC.md)。
