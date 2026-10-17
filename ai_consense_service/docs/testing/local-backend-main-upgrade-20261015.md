# 本地后端 main 构建替换与数据库升级

日期：2026-10-15，Asia/Shanghai。用户明确要求“后端重启替换一下”，本次仅升级本机 8080 后端及其必需的兼容数据库结构；保持已有运行配置、5173 前端、MiniMax relay 和独立原件解析任务。

## 已安装构建与启动

安装的是已提交 main 的 `4bec277a22cdfe7ee46fe329a9f4d52740341b6b` 构建，包含 `1796536` 的 Vetting 提示词保全与业务补齐。JAR 为 79,672,518 字节，SHA-256 为 `22926e3dc74abb9b38009e054f56eb60997c5f93590f487bdcc5add78afa3c6f`，与原隔离自动化、打包和冻结收据逐项绑定。当前活动路径为 `ai_consense_service/target/consense-service-1.0.0.jar`。

新后端 PID 为 `31816`，日志于北京时间 14:48:00 确认 Tomcat 8080 和 `ConSenseApplication` 启动完成。JVM 参数保持 `-Xms256m -Xmx16384m`，即后端最大堆 16 GiB；Drafting 的 LibreOffice、存储目录、运行配置及 schema-safe 配置保持。旧包 SHA 为 `eb419a4eb7a90e9fabd1e1c5948f28692ed57c596ca3378a5c88e34514e07077`，已独立备份。

## 数据库兼容与保全

旧包包含 V1–V15，新包增加 V16–V22，原 15 个迁移文件逐字节一致。当前本地配置为 `ddl-auto=none`、`flyway=false`，因此没有开启 Hibernate 自动更新或全量 Flyway。切换在现有 lifecycle mutex 内，仅停止收据确认的后端进程，待其退出后备份完整 H2 文件，再在独立副本执行七个版本的 20 条原始增量 DDL。

真实副本识别为 V15，排队和运行中的 Vetting 审查均为 0；旧 evidence_id 最大 12 字符，允许执行至 256 字符的变更。成功迁移后，15 个原表的行数逐表保持，新增的 advice_session、vetting_packet_checkpoint、vetting_report_snapshot 三表为空，新增和变更列类型通过核验。项目数仍为 15，来源文档仍为 348。只有副本完整通过后才安装副本和新 JAR，原库完整备份及旧包保留。

首轮 1 GiB 迁移进程在第 19 条 SOURCE_DOCUMENT 增列的重建阶段遇到 H2 `database has been closed`，没有安装失败副本，旧后端已自动恢复。日志未保留下可直接确认 OOM 的首因，不能将其定性为已证实内存故障。第二轮从最新停服数据库重新生成副本，保持全部原 DDL 和断言，迁移进程采用 8 GiB 堆、`ExitOnOutOfMemoryError` 和 300 秒截止，20 条全部完成。8 GiB 仅用于这次离线迁移，不改变后端的 16 GiB 配置。

## 实际只读检查与限制

10 个 GET 检查均返回 HTTP 200 / code 0：项目列表、依赖健康、提示词、Vetting findings 和 latest run、Advisory sessions 和 messages、已有 Drafting 项目的变量和文档，以及 5173 前端代理的项目列表。升级前后 15 个项目 ID 完全一致。Vetting 运行提示词和默认提示词已可从活动后端读取。

健康检查仍显示默认 Ollama 不可用、OCR 关闭，与升级前一致，`ready=false` 如实保留。本次没有模型生成、上传、来源重解析、索引重建或浏览器端到端验收；不能据接口可用关闭模型效果或业务质量门。原件比较仍沿既有独立执行账本推进，未随本次后端重启重复启动。

运行证据保留在忽略目录 `.local/state/backend-main-restart-20261015-1445/`：`preflight.json`、`receipt.json`、`schema-migration.json`、`readonly-smoke.json`、完整原库备份、前后 JAR 和日志。首轮失败及回退证据保留在 `backend-main-restart-20261015-1434/`。只读检查的首次聚合表达式误用了 PowerShell 裸 `false`；10 次请求及原始捕获均已完成，收据仅据这些捕获和实际工具输出重新生成，错误保留，未重复请求。
