# ConSense 工作入口

涉及比赛资料范围、上传文件身份／路由、OCR、embedding、rerank、模型部署、来源修订、切块或索引时，先读取 [OER 已确认范围与执行规则](ai_consense_service/docs/oer-scope-and-rules.md)，再读取 [实施交接](ai_consense_service/docs/HANDOFF-ocr-embedding-rerank-20261008.md) 的当前状态。

已确认规则以该入口维护；旧日期的讨论和实验记录按历史语境读取。用户最新明确指示优先，发生变更时同步更新规则和相关文档入口，避免再次把已排除材料当必需输入或重复询问已采纳决定。

原件、模型、密钥和运行证据保留在忽略的本地目录。代码、契约测试、真实模型调用、原件质量和前端业务验收分别报告；保留失败和未完成的实际状态。
