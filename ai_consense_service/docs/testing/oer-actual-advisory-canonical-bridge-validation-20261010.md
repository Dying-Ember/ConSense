# 真实 Advisory 业务桥接：GCT 原件、BGE 与 MiniMax

日期：2026-10-10，Asia/Shanghai。本报告接续 [准备路径](./oer-advisory-real-bridge-readiness-20261010.md) 和 [38 项业务接线测试](./oer-advisory-canonical-implementation-validation-20261010.md)。本轮已执行隔离的实际 Java HTTP → Advisory Python → BGE 检索 → MiniMax-M3 → 历史与原图回取，不把隔离业务成功写成在线切换、全资料质量或前端验收完成。

## 实际运行身份与边界

成功 run 位于根目录下忽略的 `.local/state/oer-advisory-real-bridge-20261010/run-20261009T175749Z-09c619/`。使用复制 checkout、已提交 H2/JPA、实际 Spring 注入代理、真实 DocumentParser、独立 storage／CAS／Qdrant 和 `@SpringBootTest(RANDOM_PORT)`；test 没有宽泛 `@Transactional`。记录器仅转发真实 HTTP 并保存不含 Authorization 的安全收据，不是 mock。

- 本轮 project：`advice-real-cb78df20-88aa-43e2-aa52-6490554be36a`；实际数据库 document ID／binding ID 均为 `1`。
- 测试的实际 Java HTTP port：`53244`；测试结束后该实例退出。其业务项目、storage 和 H2 提交保留，供下述独立持久实例继续验证。
- 原件为 `Ref/2026-09-04_Documents to Shortlisted Candidates (Contract Advisory)/2b_Tender documents/04 - GCT.pdf`，SHA `ca3944c16890cb5ea7f8b24e2fab613c7ffd621809f7f850529901649e6f15f8`，两物理页。Java 实际解析两页，canonical 采用仍仅为已核原页 1 的 24 个区域。
- 既有 reviewed request SHA：`4c02a8a9f83be6521932c435550a389fe7b5cc7a1a96ab08dbdfc29548d09bb9`。仅按实际数据库 ID 重绑定后投影，未更改原件、原始 reviewed request、reference 或质量声明。独立 CAS 中实际 staging 的 167 个 descriptor／blob 均核 SHA 与 byteCount。
- 本轮 Java parsed revision：`3353126c37949b8882fd2b8617cd20d9dd0745e82ff312a44e8e6d6633876c0c`；projection SHA：`d0747f0a311dcc3165453abd1662a134db98242a301f23fc5c668d5e72564722`。
- split 为 `development_historically_exposed`。维持 CPU FP32、short 512／overlap 64、structure 2048 的冻结配方；没有新跑 Paddle／MinerU、启用 Bonsai 或改变 GPU／WSL 服务。
- `humanConfirmed=false`、`wholeSourceAccepted=false`、`qualifiersComplete=unknown`、`semanticScopeVerified=false` 保留。实际完整观测单元不表示法律适用性或整源语义质量已确认。

## 实際业务结果

复制 checkout 编译／打包 exit 0；实际桥接 Maven **1 test，0 failures／errors／skipped，exit 0**。测试报告 SHA 为 `bf8b3cc5d61dbc28119d2a0f73cbd3e79ff2b1ffced271ceff01dac9f4b1e433`。此前统一源码 215 项测试和本轮真实桥接分别报告，不合并为题目准确率。

实际完整流为 sources → 未就绪 status → fullset rebuild → ready status → ask → messages → pointer／asset。24 chunks 生成 29 个实际 embedding windows，fixed cache hits 为 0。真实检索返回 GCT3、GCT1、GCT2 完整单元，分别有 12、5、5 个 members；所有成员原文均进入实际 M3 prompt，引用及来源结构保留到实际 HTTP response 和历史记录。

本轮只有 **1 次真实 MiniMax 回答**，经已有本机中继；model 为 `MiniMax-M3`、profile 为 `minimax-cn`、configured identity SHA 为 `eeefbf7cdffe77dcf194698a9654ac9ceeaebb4a5132bcc0bcd029cc43c68688`。这是配置身份，不扩为供应商后端权重证明。API key 只经子进程环境传递，没有写入参数或收据；独立 loader 的合成安全验证见 `.local/state/oer-advisory-real-bridge-20261010/probe/relay-config-safety.md`。

实际开发问题为：

> What happens if the tender booklet is a photocopy rather than the original version?

实际回答引用 `[1]`，对应 GCT3，`grounded=true`：

> Under General Condition of Tender No. 3, the 'booklet' (referred to in condition 1(a)) shall, unless otherwise provided, be the originals issued to the tenderer. Tender submission with the 'booklet' not using the originals shall, among other inappropriate deeds as specified in the Housing Authority's Guide to Registration of Works Contractors and Property Management Services Providers (the 'Guide'), be treated as a tendering irregularity according to the Guide. Any non-compliance with condition 3 may cause the tender to be disqualified.

对照本轮实际 GCT3 原文，回答保留 `unless otherwise provided`、按 Guide 处理的 tendering irregularity 和 `may cause ... disqualified`，没有将“可能”改成自动取消资格。该核对只覆盖这个已暴露开发问题；没有使用官方 2e 答案来生成此回答，也没有证明所有正式题正确。当前 Advice 既有行为将该英文内容原样放入 zhHans／zhHant／en 三栏，本轮没有增加翻译能力。

实际 controller 返回的 page-1 PNG 为 **544,413 bytes**，SHA `15b47e88ca835936456c1ff304004b1735edf1171258dec611705df7b1db1f38`，与 reviewed 原 raster 逐字节一致。实际历史为 user／assistant 两条，最后一条的 sources、citations、answer 与本次 response 逐对象相等。

在本轮隔离数据库变更 parsed revision 后，旧 pointer 和 asset 实际 HTTP 均返回 **409**，新 ask 返回业务 **4204**，历史仍为两条，M3 调用仍只有一次；没有静默重链旧引用或保存失败问答。随后仅在该隔离 DB 恢复测试前 parsed revision，以供前端继续使用原本有效的 binding；真实原件、reviewed request 与在线 DB 均未改动。

上述恢复最初只在 test 进程中验证；后续浏览器发现持久库仍为负例，不能将 `isolatedNegativeRevisionRestored=true` 解读为最初已经落盘。实际诊断与受控持久修复见下节。本轮真实 test 成功事实保留，最初持久准备失败事实也保留。

## 耗时与日志独立复核

以下为本轮实际 forwarding receipt 的始末时间差，包含网络／服务／冷启动开销，不以 Python 内部某一阶段时间替代用户等待时间：

| 实际步骤 | 秒 | 本轮证据 |
| --- | ---: | --- |
| `/index-canonical` | 11.652 | `transport-006-receipt.json` |
| `/retrieve` | 8.324 | `transport-014-receipt.json` |
| `/v1/chat/completions` | 5.904 | `transport-017-receipt.json` |

Python request journals 的 index 为 **20 events**、retrieve 为 **27 events**；此次与旧 bridge 的 18／25 events 分开记录。离线脚本只读已存 bytes，复核 request／response／events 的 SHA、byteCount、事件数量与 ordinal 1…N，并检查真实 test、历史、prompt、单元、pointer 和原图共 **49 项，全部通过**；该数字是一致性检查项数，不是 49 道业务题。

离线审计收据为本 run 的 `saved-evidence-audit.json`，SHA `64ac2e5d6ca46e5b2186a5869e14daf908cb7e0be5564e2875bcf79a34986431`；脚本在相邻 `probe/audit-saved-run.py`。审计没有 HTTP 或新模型调用。

| 保存证据 | SHA-256 |
| --- | --- |
| `bridge-receipt.json` | `d080801daa06b1ea8b4c12a41429a3fb279ecec2bc6955cc3a99911540636a5f` |
| `launcher-receipt.json` | `2fd885a0b849704cd804a03130d4ca4de21497b45d78a63eedd5baa6169eee89` |
| `actual-advice-response.json` | `9724c156380cc0e496de58b13d019273ec476133d9d4ae432da620498b999467` |
| `actual-history-response.json` | `00cec5157ded85761827fe93deb1ccc4b239c2c73337637704a9ec3044b8818b` |
| index journal `receipt.json` | `227097443fbcdd7a1830f0d53e71e53e1e231d2d934ed6c3f9d4cb2a36096657` |
| retrieve journal `receipt.json` | `4361aeba8686b82e1b6ce2136fc97f0e734d6326e3dfdd79c3da0d3dfc9861ef` |

## 保存的失败与持久前端准备

首次实际 run `run-20261009T175519Z-1e7828` 在 cold BGE index 阶段超时，尚未调用 MiniMax。原因是 ignored probe 的 OkHttp 虽设置 32 分钟 call timeout，仍继承 10 秒 read timeout；实际 cold index 超过该值。只修改 probe 的 read／write timeout 后创建上述新 run，失败日志和精确进程 cleanup 收据保留，没有覆盖失败或放宽业务门控。

首次持久 Java 启动在 Windows JDK17／Tomcat selector 出现 `UnixDomainSockets ... Invalid argument: connect`。仅改 ignored launcher 的持久 JVM 参数为 `-Djava.net.preferIPv4Stack=true` 和本 run 独立 `-Djdk.net.unixdomain.tmpdir=.../socket-temp`，与成功 test 条件一致。初次 launcher／manifest 字节另存为 `*.pre-serve-jvm-fix`／`launcher-pre-serve-jvm-fix.ps1`，原失败 logs 保留，`serve-jvm-amendment.json` 明记实际 bridge 未因该启动参数修订重跑；应用源码、实际 test、runtime Python 没有改变。

当前独立持久 Java 为 `http://127.0.0.1:18081`，Advisory Python 为 `http://127.0.0.1:8869`。服务运行使用同一已提交项目／source／projection；Java 重启丢失内存 index receipt，因此浏览器须在当前 scope 重建一次 index，然后使用 `X-ConSense-Llm-Profile: minimax-cn` 实际提问。本 run 的 `persistent-service-receipt.json` SHA 为 `a7d4984023bc2134495e9f9a917755e378252eebea0dffa1bb527a44aa6ef840`，明确 `browserAccepted=false`。端口存活需读取当时实际 process receipts，不依赖本文 PID。

本轮复核受保护 8080／5173／8868／8092 的监听 PID 与起始基线相同，在线 `target` 未编译、在线数据库／dist／配置未覆盖。独立服务保持运行交给前端验收，不将健康检查当浏览器完成。

### 浏览器暴露的 H2 负例恢复落盘问题与修复

root 实际打开独立前端后，原历史回答可见，但 12 个 GCT3 原件地址均报 409。只读 actual `/sources` 的 parsed hash 为 `1adc9a9296ecd6893d229c82cba6ac0c0ae783405701b1659fc9d035ce37ca6b`，`/files` 显示 textChars=5405；原 saved source 为 5381 字符，加 `\nISOLATED PROBE REVISION` 正好为 5405。用实际持久 Jar 的相同 class bytes、嵌入 Jackson 2.13.5 重算，原 saved hash 正是成功 test 的 `3353126c…`，负例 hash 逐字匹配 live `1adc9a92…`。原件字节 SHA 仍正确，Jar／test 的 VettingCorpus、JsonUtils、fresh reader class SHA 相同，问题不是 parser 代码差异或 Python 检索 signature。

原因边界为：test 在 fresh JDBC／reader 中恢复原文后通过，但 launcher 很快终止进程，H2 delayed writes 和 `DB_CLOSE_ON_EXIT=FALSE` 的组合没有保证最终恢复已经落盘。最初 Windows 文件锁阻止只读 live MV file 复制；失败副本保留，没有据该副本声称读到了实际 DB。随后 root 明确授权仅停止精确 receipt 身份的独立 18081，保留 8869／在线其他服务，读取并修复隔离 H2。

修复先保留旧 DB／旧 text／HTTP 失败状态／process receipt，核验数据库当前 text 精确等于 saved 原文加负例 marker、structure 和 original SHA 精确相等，再仅更新 source_document.id1 的 text_content 为 saved 实际原文。执行 `CHECKPOINT SYNC`、正常 `SHUTDOWN`，独立重开 DB 再核原 hash 后正常关闭，随后重启同一 Jar。没有新增 binding、修改历史地址或模型调用。

- 修复前后全部 binding 行字段 hash 均为 `aa727dcd2703fcbc474b777ad997818019552ed6b7177043683c032db36da84d`。
- 修复前后全部 history 行字段 hash 均为 `9134c9e07859df0283f0578fd410e0477f38585e5f3c256a68cd7eb3e2b233f8`。
- 原件、saved request、历史 test 及其 manifest 均未改动；旧负例数据库和 text 保留在本 run 的 `persistent-source-diagnostic-v1/`。
- 修复后 **27 次实际只读 HTTP／40 项一致性检查全部通过**：current source hash 回到 `3353126c…`，currentBindings 可用，status chunks24／stale=false／index receipt missing，历史对象完全相同；12 个旧 GCT3 pointer 与 12 个旧资产均为 200，pointer 等于原历史地址，资产 SHA／bytes／identity headers 正确。没有执行 rebuild 或 ask。独立前端后续的 tableGroup 字段省略兼容问题由 root 单独处理，不将这些 HTTP 检查称为浏览器 UI 已通过。

对应本 run 的 `persistent-source-diagnostic-v1/diagnosis-receipt.json`、`durable-repair-receipt.json`、`restored-http-validation.json` 保存实际状态；修复版 Java process receipt 仍在本 run 的 `java-process.json`，旧版本另存。8869 未重启。

未来 ignored opt-in probe 已改为在 `finally` 中恢复负例，即使负例断言失败也恢复并 fresh 复验，再执行 `CHECKPOINT SYNC` 和正常 `SHUTDOWN`。旧 probe 字节及已执行 run 的 copied test／manifest 不变；新增 probe 用独立输出目录实际 javac 编译 exit0，仅验证编译，不重放模型 test。收据为 `future-probe-durability-amendment.json`。本次不把新 probe 追认为旧成功 run 已使用，也不放宽 frozen launcher 检查。

## 仍未通过的验收

本轮只证实 GCT page1 开发问题的一次真实 Advisory 接通、完整引用保留和当前版本拒绝逻辑。实际浏览器、SL 三页完整表组、更多原页／整源质量、官方 Advisory 问题逐题评价、Vetting 结构化报告、真实 MySQL 并发／升级、native canonical producer、精度参数／必要轻量训练和 H800 冻结包均仍须分别验收。2d／2e 继续只作为隔离评价资料，不能进入生产检索／同题输入；不会用本轮 1 test、1 answer 或 grounded 标签替代上述质量结论。
