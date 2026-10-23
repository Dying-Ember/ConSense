# Drafting 模板上传数据库写入热修

2026-10-22 用户手测上传 `01_Notes to Tenderers (NTT).docx` 时失败，页面显示 `could not insert [com.consense.domain.SourceDocument]`。本项是当前上传流程阻断，按既有授权立即做最小修复；不扩成新一轮全模块验收。

## 已确认原因

业务后端原 PID6112 的日志在19:11:34记录 H2 `50000 / HY000 / NonWritableChannelException`。隔离 TCP JDBC 探针以超过默认1MiB阈值的 CLOB 精确复现，异常链为 `JdbcPreparedStatement.setCharacterStream → SessionRemote → ValueClob.createTemporary → FileStore.init`。

此前的 `rwfile:` 兼容只在 H2 服务端注册。后端 JDBC 客户端在传输较大 LOB 前也会创建临时文件；本机 Windows/JDK 的 `Files.isWritable=false` 误报使 H2 将临时文件以只读模式打开，随后写入失败。此前短字符串／微型 MEMORY 表验证未覆盖此分支，不能作为真实上传写入通过的依据。

## 最小修复

- 把既有 `FilePathWrapper` 提取为 `H2LocalFileAccess`，业务后端及 `scripts/H2LocalFileServer.java` 共用，删除服务端脚本的重复实现。
- 后端在 Spring／H2 初始化前、仅显式开启 `-Dconsense.h2.local-file-compat=true` 时注册；默认临时文件前缀设为 `rwfile:h2.temp`。保留 H2 默认1,048,576客户端 LOB 阈值及磁盘分块写入，不使用 memFS、不提高阈值、不加依赖版本或技术栈。显式自定义 `h2.prefixTempFile` 的配置仍被保留，使用此兼容路径时应指定 `rwfile:` 磁盘前缀。
- `canWrite` 只改为当前机器上可用的 `File.canWrite` 预检；实际文件打开／写入仍由现有 H2 和 OS 执行，不绕过实际权限。
- Drafting 批量模板上传、替换及全局未知异常共用 `PublicErrorMessages`。内部 SQL／实体／路径留在服务端日志，用户收到可读的失败提示；显式业务校验及已有模板保留保持。
- H2 已有依赖改为编译可见，版本未变。服务端脚本编译时须同时包含共享 helper；本地启动脚本及已编译兼容目录已配套更新。

## 实际验证

证据位于忽略目录 `.local/upload-write77/` 和 `.local/upload-error-display/`：

| 层级 | 实际结果 |
| --- | --- |
| JDBC 原因复现 | `red01.log` 精确复现客户端临时 CLOB 的 NonWritableChannelException；同一探针接共享修复后 `green01.log` 完整写入／关闭／重开／逐字读回通过 |
| 错误展示复现 | 原返回路径2项红灯，确认数据库信息泄漏；后继39项回归通过 |
| 本次组合回归及构建 | `package04.log`：H2LocalFileAccessTest 1、DraftTemplateRoutingTest 38、GlobalExceptionHandlerTest 1，共40通过，0失败／错误／跳过，BUILD SUCCESS。H2探针在独立512MiB子JVM中执行超1MiB CLOB和BLOB完整回读，不污染其他测试的H2静态属性 |
| 保留失败 | package01／03 的 H2测试在默认短路径系统临时目录创建时 ERROR；后继改用既有 target 构建目录。package02 为本地 Maven 命令环境引用错误，未进入测试。原日志全部保留 |
| 真实浏览器上传 | TaskSpace3，隔离项目 `proj_mvjgfph94gd3`，从真实文件输入上传与用户失败文件逐字节相同的 NTT，出现“已上传”，无错误；页面刷新仍保持。browser03／04脚本、`upload-success.png`、`upload-reloaded.png`、`browser-reload.log` |
| 持久化来源 | 新来源63为PARSED，原件SHA `60bd8796aa828863c7edfe8617b7a5c56ee383afe8ba87a089e820f0fd9a982c`。正文23,308字符、structure6,124,018字符、coverage47,533,192字符；不是只靠上传toast判断成功 |
| 原有记录保全 | `db-before.log`／`db-after.log` 比较：原62来源的ID／项目／类别键／原件SHA／解析状态，以及原6草稿的ID／项目／文件键／修订号完全相同。未授予所有历史LOB逐字节核验通过 |

## 当前运行及范围

后端18086／PID38868／64GiB已使用新包 SHA `5980b5a2fcda9937eeb27e050012574965138d83d37cd7709368ca1b68da7b77`。前端5177、H2 PID32040／18082、两个OER服务保留，未重新导库、迁移旧binding或新建审查任务。浏览器测试空间已完成关闭；隔离测试项目留作已保存结果，用户原项目未替换模板。

本项只授予此上传保存路径恢复及错误展示回归通过；未重新验收变量识别、最终Word／PDF或35件审查报告，0新付费模型调用。Goal工具仍paused。

本次也观察到NTT的结构／coverage体积仍明显大于正文，记录入既有公共来源compact后继，不将数据库临时文件恢复当作体积优化完成。SL质量及比赛包继续后置；Vetting通用解析说明的中英技术文案记录待改，不阻塞当前手测。
