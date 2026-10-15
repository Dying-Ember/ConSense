# main 统一开发入口与本地归档（2026-10-15）

用户已明确要求把统一实现合入 main，只保留 main 开发分支，其他分支归档。主目录为 `D:\R&D\ConSense`；main 已从 `5f0b6eb38c2a1a78e23e6779b301f146b54d15b5` 快进到通过既有适用自动化的 `4108497368b08484ca1fd3a9d3472547b175097e`，之后继续在 main 提交。

本次归档根目录为 `.local/archives/main-consolidation-20261015-121335/`，Git 恢复引用前缀为 `refs/archive/main-consolidation-20261015-121335/`。`protection.json` 记录原 41 个本地分支、远端跟踪引用、16 个工作区以及原始 SHA。每个分支在移出活动分支列表前都须保留恢复引用、进入可验证 bundle，并核对原 OID 与 main 祖先关系。远端仓库不推送、不删分支；本地非 main 的远端跟踪缓存可归档移除。

main 原有未提交内容，以及 `oer-form-preservation` 的 4 个源码/测试改动、`oer-next-delivery` 的 3 个文档改动，分别保存为 Git 工作树快照、原始字节 ZIP、文件 SHA 清单、二进制 diff 和原 index。旧工作区只在原 HEAD 脱离分支，保留目录、未提交内容及忽略资产。两个旧工作区的未提交改动另行归档，不追认为已验证主线代码。

`outputs/oer-progress/index.html` 与 `progress-ledger.json` 是较新的本地生成运行记录，原字节保留，移出源码跟踪并精确忽略。`.qdrant-initialized` 留在本地忽略规则。现有服务脚本、已运行 JAR、静态 dist、数据库、模型、原件和其他忽略资产不因分支整理切换或删除。main 中并行任务新写入的研究文档保留原状，不混入本次归档提交。

既有自动化和代码审查结论见[统一实施记录](consense-integrated-implementation-20261015.md)。其中历史 `.local/...` 测试证据路径仍以原统一工作区 `C:\Users\Administrator\.codex\worktrees\consense-integrated-20261015\ConSense` 为根；该目录继续保留。main 的源码/测试/资源按最终已测原始字节核对，换行缓存刷新不作为功能改动。旧失败、123 条件跳过、历史排除及九项 retired 均保持。

分支归档的实际终态以归档目录中追加的执行收据为准。恢复旧分支可用 `git branch <原分支名> <protection.json 中的 archiveRef>`；恢复未提交源码应在独立恢复目录使用 `snapshotArchiveRef` 或核 SHA 后的原始 ZIP，不能覆盖当前 main。bundle、ZIP 与源码快照均仅保存在忽略的本地目录。

本操作不启动真实模型、原件前后效果、SL、端到端或实际部署验收，不改变完整 OER D1–D10／EAC01–22／ENG-00–10 的未验收状态。
