# 私仓源码整合记录与后续同步

日期：2026-10-09。私仓：Dying-Ember/ConSense。

## 当前整合

以私仓 main 的 2e75126 为基准，迁入本地后端 67ceb708..82ea3d9 的完整 45 路径增量，以及前端 629ef98d..4e8ec5e4 的完整 7 路径增量。保留远端 drafting PR #3 的原生表格、字段关系和候选/建议 snapshot 契约，加入已有的 vetting/advice 材料范围、embedding preflight、旧材料快照导出保护、69 项人工清单、比赛报告结构及引用校验。

本地原工作区和其他隔离分支也已核查：与集成源不同的旧生产路径对应已知祖先版本，非祖先特性分支的生产改动已被后续整合或等效重写吸收。两套明确的交互原型保持实验历史身份，没有覆盖产品代码。两个脏 x2t 测试分别是当前版本的重复内容和缺少后来新增断言的旧版本，保留当前更强版本。

补齐 51 份缺失/更新 Markdown，并归档 team-update 的 6 份独有文档。保留远端最新 GLOSSARY 和 7 份 Oct 8 OCR/embedding/rerank 讨论文档。旧 goal/tracker 标记为历史，以最新 handoff 的决策和后续明确授权为准。

## 本轮检查

- 后端 clean package：162 个 suites，1,618 项测试，1,550 通过、68 跳过，0 failures/errors；生成可执行 JAR。
- 前端：fresh npm ci、typecheck、production build 通过；37 个 CommonJS 文件中 36 通过、1 跳过、0 失败；4 个 view/workspace 脚本通过。
- 真正启动 Python loopback HTTP 协议进程，但其 guard 和最终计数证明 neural/tokenizer/vector/OCR 操作均为零。冻结 health 元数据不是当前机器的健康测量。
- 5 个依赖原件/真实捕获/renderer 的 Java 测试保存在 external-fixtures，不属于默认测试结果。实际模板导航、实际文档阅读分支和 OCR 诊断重放缺外部证据，未计为通过。
- 独立兼容性审查核对了远端 drafting 保留、API/types 配套、V12/V13/entity、embedding preflight 顺序、scope 和 stale 导出保护；未发现阻断。

首轮全套暴露的 mock 端点配置、真实原件缺失、系统 Python 依赖缺失和 Windows SHA-bound TXT 换行问题均保留了失败证据，并分别修复测试自足配置、外部验收边界、运行环境选择和精确 LF 规则；没有放宽证据断言。

这些结果证明本轮源码整合和可执行默认回归。真实模型质量、OCR/embedding/rerank 精度、MySQL/Flyway 部署、Qdrant 和原件完整 PDF 版式仍需独立验收。

## 后续保持一致

1. 以私仓远端 main 的实际 SHA 为整合基准；在干净分支迁移差分，不把旧工作目录整仓覆盖到新远端。
2. 配套同步后端、前端、迁移、资源、测试和文档；按完整功能增量审查，而不是只复制新增类。
3. 把来源 base/head、检查及跳过原因写入 SOURCE-INTEGRATION.json；SOURCE-SNAPSHOT.json 永久作为初次导入记录。
4. 通过检查后 push/合入私仓 main，再 fetch 回读核验提交和受审文件身份。干净的 ConSense-github 工作副本只做 fast-forward 更新，原工作区未提交内容保持原样。
5. 将真实资料、权重、运行数据库、密钥和绝对路径历史输出留在独立外部依赖中；测试/历史文档不得被误读成真实质量已通过。

两套实验原型、原始文件和运行数据仍有明确未发布边界。本记录不声称每个本机文件均已备份到私仓。
