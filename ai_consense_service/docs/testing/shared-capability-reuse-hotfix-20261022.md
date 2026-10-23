# 公共能力复用热修与剩余缺口（2026-10-22）

**后继校正：** [第二轮排查](shared-capability-reuse-audit-20261022.md)已补修下文的LO总量／probe清理、PDF metadata LOB、重复原件SHA、历史binding无界读取、registry重复root及normal关系遍历，取得70 PASS／1 skip、161 PASS与Python92 PASS的独立证据。用户随后要求按[按需 OER spec](../specs/oer-demand-driven-business-access-20261022.md)从业务接口根本收拢；业务全量快照与独立切块尚未改完，不称架构完成。下文“未修”表是本记录初版状态，以后继为准。

本轮接续用户“类似不合理的问题一起排查修掉，包括重复造轮子、功能不复用”的授权。只在既有 Java／Jackson／Python／JPA／LibreOffice 中修公共路径，没有新增依赖或另一套来源管线。当前 Goal 工具仍为 paused，前后端、OER 和业务 H2 未恢复；本记录不代表三模块已部署或人类验收通过。

## 这轮实际修了什么

| 公共位置 | 原问题 | 当前实现与边界 |
| --- | --- | --- |
| `JsonUtils.serializedSha256` → `NormalSourceCandidateGraph` | 只为取得 JSON 指纹，也先生成完整图的 JSON String | 复用现有 Jackson 序列化配置，以有缓冲 Writer／UTF-8 digest 直接计算。字段顺序、数字、时间、Unicode、未配对 surrogate 与旧 `Utf8.sha256(JsonUtils.write(value))` 相同；不能替代保留原数字 token 的 `CanonicalJson` |
| `OerBusinessEvidence.Session` | 为防选中视图被改动，额外保留每个视图的整份 JSON；后继比较又构造整串 | 私有 mutation seal 只保留 64 字符 SHA，构造、完整核对与模型选中成员核对三个位置共用上述 helper；选中来源及字节校验仍在 |
| `OerBusinessEvidence.loadSession` | 同阶段已验证 normal snapshot，业务适配再解析同一 binding | 复用既有 frame 的 normal snapshot；非 normal／collection 保留原 envelope 路径。frame 的线程／项目／文档／projection／module 与退出 fresh 校验不删，不引入跨请求缓存 |
| `OerBusinessEvidence.ruleSources`／`ordinaryRuleSources` | 每个 part 重新哈希整文、解析全部 blocks／coverage，并重复寻找标题 | 一次同步调用内按文档懒读取和复用 source hash、blocks／ID 索引、coverage、精确位置＋文字的 heading 索引。普通和 canonical 适配共用 `RuleSourceRead`；重复 block、重复 captured source、失配原文／范围／位置继续 unknown；下一次调用重新读取 |
| `NormalObservedContract` | 提取 `candidateGraph` 为完整 String，再调用 canonical hash | 直接复用已有 `CanonicalJson.fieldHash`，省掉这一份完整 graph String；严格 JSON、重复键、原始数字 token、排序和身份校验保持 |
| `token_windows.windows`／`verify_window_coverage` | 每个窗口重新哈希完整父正文、父 payload、query | 单次生成内复用父／query 指纹；独立校验重新算一次后复用。每个窗口的身份、内容、范围、metadata、spans、预算仍逐一校验；没有跨调用 cache |
| `DraftingService.currentPdfArtifact`／repository | 仅问是否有缓存，也加载包含 PDF 字节与 manifest 的整份实体 | 前置检查改用 JPA `existsByDocxSha256`；真实读取与定位仍使用原完整接口，渲染身份／锁／校验保持 |

这是同一公共实现的调用内复用。业务权限、module indexKey、模板适用策略及人工采用状态各自保留，不能仅为去重而合并。

## 实际执行的验证

所有 Java 与历史 PRE 验证由 Root 串行执行。使用既有隔离构建目录、32 GiB heap；启动至少 48 GiB 可用，运行时可用低于 32 GiB 或提交余量低于 24 GiB 停止验证进程。未连业务数据库、外部模型或 OER 服务。

| 验证与证据（均在忽略的 `.local/source-compact-hotfix75/`） | 实际结果 |
| --- | --- |
| `reuse-red02/` | 原 mutation seal 保留整份 JSON 的问题已实际 RED；`reuse-red01` 未产生完成结果，不算一次测试结果 |
| `reuse-rule-red04/` | 修复前新增 3 方法为 3 failure／0 error，实际复现逐 part 重复读取；原记录保留 |
| `reuse-green03/` | 公共 JSON 指纹、candidate graph、canonical lexical 相关 57 项通过。含 1 MiB scalar 指纹分配低于 256 KiB，及 surrogate／字段顺序／数值等价检查；不代表当时尚未集成的业务适配已过 |
| `reuse-business-green05/` | 集成 8 套共 161 项：160 通过、0 failure／error、1 skip。其中真实 Spring／临时 H2／模型 double seam 93 项通过，来源业务 15＋4、项目上下文 18、身份生命周期 9、表格 metadata 10、Drafting 状态 10、JPA 查询 1 项通过 |
| 上述唯一 skip | `DraftingPlanGenerationStateTest.actualThreeOriginalGenerationAndImmediatePlanHaveTheSameSourceApplicationStates` 要求显式开启保存的真实三原件夹具；本轮未启用，不报为通过，不代表实际 LO 或三份文件验收 |
| JPA 实际 SQL（同一 `reuse-business-green05/maven.stdout.log`） | 已存与不存在两个 `exists` 均只 `select id … where docx_sha256=? limit ?`，没有 `pdf_bytes`／`manifest_json`。夹具保存时的完整 entity 查询不计作存在性查询 |
| `reuse-window-red01.log`／`reuse-window-green03.log` | 原计数断言 RED；复用既有 retrieval venv 后 7 套 132 项全过。首次系统 Python 缺 numpy 的导入错误保留于 `reuse-window-green01.log`，没有安装新依赖 |
| `reuse-window-runtime01.log`／`reuse-window-equivalence01.json` | 直接 WindowRuntime／dual／typed 42 项通过；16 组旧新完整输出字典相等。尾窗口 7 类篡改、跨调用源变化和错误先后顺序均有负控；不同批次不相加冒充唯一用例总数 |
| `reuse-real-pre01/summary.json`／`result.json` | 同一 I39 历史保存解析完整回放通过，10,930 units／parts 展开长度／SHA 逐项相等，其他非 unitId 字段、正文／结构／coverage／原件 pin 与输入 gzip 保持 |

父段落 104 字符、query=Q、34 窗口的实际 helper 调用计数：

| 操作 | 原 HEAD | 当前 |
| --- | ---: | ---: |
| `windows` 含内部 verify：父正文 SHA | 69 | 2 |
| 同上：完整父 payload SHA | 68 | 2 |
| 同上：query SHA | 35 | 1 |
| 独立 `verify_window_coverage`：父正文 SHA | 34 | 1 |
| 同上：完整父 payload SHA | 34 | 1 |

两次分别属于生成准备与独立验证。Java 多 part 夹具还验证每文档正文 getter 1 次、结构 getter 固定 3 次（hash 一次、现成 parser 两次）、coverage 1 次，并比较所有输出 block／上下文字节，未把省略校验当作复用。

历史 PRE 本次仍为 480,537,861 B → 238,538,721 B，减少 241,999,140 B（50.3601%）。新图 wire SHA 为 `ddfd1fd242f49f0f7cb578d5599b5ecf9470cf5538c03b5528b09dde4ba76fa7`，与上一轮完全相同；10,930 单元的 ordered expanded parts SHA 为 `b88fa20716b1f71ab978369f949719a2feb1a3b50e17eff47e4e14e2bfc3f555`。unitId 仍按 v2／v3 版本区分，不能冒充同一修订。

`reuse-business-green05` 1 秒采样峰值工作集 2,433,351,680 B／private 2,511,589,376 B，整机最低可用 107,026 MiB。历史 PRE 回放进程 16.987 秒、采样峰值工作集 9,552,068,608 B／private 17,421,021,184 B，整机最低可用 100,108 MiB，均未触资源停止界限。这不是旧新独立进程的内存降幅对照，也不能证明桌面卡死根因。

## 已共用的能力，不再另造实现

- 生产 Vetting PDF 已是 `VettingService` 生成 DOCX → `DraftingService.renderPdf` → 公共 LibreOffice；Drafting 成品、模板预览、审阅原件也走该入口。
- 同一 Drafting 成品 preview／PDF download 已有事务内文稿行锁；不能笼统说两者必然并发。跨项目／模板／Vetting 的进程总量仍是另一问题。
- 普通来源图已由公共 `NormalSourceCandidateGraph` 构建；normal 来源准备、封存及窗口索引已有公共模块。同 indexKey 暖缓存已存在，尚未实测跨模块真实重复 embedding 次数。
- 原始数字 token 的 canonical hash 与 Java 对象序列化 hash 是不同契约，保留两者；不要用新 helper 替换前者。

## 已定位、尚未修的后继项

这些项保留在既有主线待办，不以“全部重复实现已清完”交付，也不把它们全部变成恢复人类验收流程之前的新门槛。

| 优先级／范围 | 确认的缺口与最小后继方向 | 必须保留的边界 |
| --- | --- | --- |
| P1／公共 LO | `DraftPdfProcess.run` 直接启动进程，缺 JVM 总并发上限；应在已有公共入口用 JDK 许可和有界等待收口 | 私有 LO profile、deadline、中断和只停止自己进程树；不能共用可变桌面 profile |
| P2／PDF 元数据 | `documentVO` 仅需 PDF hash／profile，却仍经 `currentPdfArtifact` 在有缓存时读 eager LOB；本轮只解决了前置存在性查询 | 后继用 repository projection；真实定位仍需完整字节与 PDF 书签验证 |
| P2／LO 探测 | `currentProfileHash` 每次创建 `profile-probe-*` 且无清理；列表／命中缓存仍探版本，miss 转换再探 | 调用内复用、`finally` 清理自己新建目录；禁止永不失效的版本／字体／程序静态缓存 |
| P2／LO 相同工作 | 同 DOCX＋profile 的 cache miss 未合并；跨模板／项目／Vetting 可能重复转换 | 有界合并进行中结果、失败释放；须核外层事务提交可见性，不能靠短锁假装后续一定读到缓存 |
| P2／来源准备 | `NormalObservedPreparationService.prepare` 仍无条件 capture＋CAS；current reader 的 fresh 阶段仍完整 parse／重建图 | 只复用同阶段已验证输入；DB／原件／CAS fresh guard 不删，不能撤销冻结前必要准备步骤 |
| P2／来源 hash 与窗口 | 原件流式 SHA 已有 `StorageService.matchesStoredBytes`，其他 reader 仍手写同类路径；manifest canonical＋identity 还有 rawObject→parse；规则 heading 的 retainedBlockIds 仍逐成员扫描 | 后继沿现有 helper 收拢，不复用过期原件 pin、不丢 unknown。当前未声称这些开销已量化清除 |
| P2／跨模块向量 | 冷 module indexKey 各自 encode；共享内容 cache 尚未实现 | 权限／module key 各自保留，复用身份必须包含实际模型输入、模型／tokenizer、预处理、配方与 dtype |
| P3／旧 PDF 实现 | `DraftDocPdfWriter` 无生产 caller；`VettingReportWriter.pdf` 仅 deprecated 兼容适配器和旧测试使用，无 Controller／Service 调用 | 有效断言迁回 DOCX＋公共 LO 后再删旧实现；不继续修旧自绘分页并当生产格式验收 |

旧约 958 MB binding 仍保留原字节；本轮未迁移、未重新封存当前 PRE。后继必须通过前驱版本校验追加新修订，不能原地改旧 bytes、删除来源或借 reviewed 权威。

GitHub 凭据失效的既有实际 push 失败见 `git-delivery-result.json`。本轮继续本地提交，远端推送待凭据恢复；不输出密钥、不反复触发登录或因此停止代码工作。三份未知 untracked 文档／测试保持不纳入提交。
