# G305 真实 Vetting 任务、输入与结构化报告收集

日期：2026-10-10，Asia/Shanghai。本记录是单个独立开发项目的真实链路证据，不是正式赛方多来源合同审查或法律质量评分。前置来源审计、持久 DB、native collection、实际 BGE 和 HTTP bridge 见 [G305 接线记录](./oer-g305-native-actual-business-bridge-20261010.md)。主任务负责内置浏览器及导出文档的视觉验收；本文仅记录只读收集与离线逐值核验。

## 实际运行身份与边界

主任务在独立 5175 前端发起一次 G305 Vetting 任务。本收集器没有 POST、index、Ask、模型调用、启动任务或重试；仅轮询既有任务，并在终态后 GET 三种报告。既有 8080、5173、8092、WSL 及其他受保护服务没有为本收集变更。Bonsai 保持抑制。

| 身份 | 实际值 |
| --- | --- |
| 项目 | `g305-native-9c5e9309-1cc1-46b5-9a2b-172a7e16b4c7` |
| 原件 | `16 - Appendix G305 - declaration of confidentiality, etc.docx` |
| 原字节 SHA-256 | `16b4630df29b7aa76e8511b42f12359c8d16514932e60046e25f1c49e167c57e` |
| 独立 Java | `127.0.0.1:18082`，PID 50408，冻结 Jar SHA `bbe2bdd520c301f9631584d3aa906b3ae17ce11699ee37b85020af1728654d7f` |
| 模块 URL | Advisory `127.0.0.1:8871`；独立 Vetting `127.0.0.1:8872` |
| 运行配置 | `h2,oer,minimax-relay`；5175 CORS；AiGateway INFO |
| 模型身份 | profile `minimax-cn`，`MiniMax-M3`，identityScope `configured_profile` |
| 模型配置 SHA | `eeefbf7cdffe77dcf194698a9654ac9ceeaebb4a5132bcc0bcd029cc43c68688` |
| jobId | `d7e27142-4fea-4876-b221-3578bfb4c26e` |
| job source snapshot SHA | `1dc4bfa8faf2be2ee07860c980c117b16c8a0f7d5e7f4356c048686357ed9767` |

Vetting URL 与 INFO 是同一 Jar 的显式忽略启动配置，未重新编译在线 target。切换前后持久来源、唯一 root binding、Advisory 历史身份保持一致；切换收据位于 `.local/state/oer-g305-business-bridge-20261010/run-034106-69f0a2/module-switch-040104-259c7a/validation-receipt.json`，SHA `576fb6353caa107cf2a3a9c33b94fb7bc3db1a428755d6f81cedc3fbf919fb23`。重启后的 index receipt 未自动重建，由主任务此次实际 UI 任务执行自己的 Vetting index。

默认 router 对 G305 仍是 `UNKNOWN`；此次使用既有 exact-SHA registry 的独立来源审计结果。真实 native chain 通过不追认为任意 G305 上传已自动路由。

## 完成状态与实际模型输入

任务终态是 `COMPLETED`、`phase=completed`、`19/19`，`sourceSetStale=false`。实际 UTC 开始 `2026-10-09T20:02:42.391023Z`，结束 `2026-10-09T20:07:11.141237Z`；总墙钟 268.750214 秒。该进度是任务处理进度，不是 19 个法律问题正确。

17 个主题各有一个实际 global packet；AiGateway 保存 17 个请求和 17 个模型 content 返回，**全部返回 `[]`**，每个返回 2 个 UTF16 字符，SHA `4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945`。没有 issue、consistent 或 insufficient_context assessment，没有 rejected record；0 findings 来自空输出，不能解释成合同无问题。

| 输入／覆盖项目 | 实际结果及分母 |
| --- | --- |
| 系统提示长度 | 每次 6,722 UTF16 字符，包括结构 schema |
| 用户提示长度 | 17 次分别为 274,574–274,645 UTF16 字符 |
| source wire | 每次 265,383 UTF16 字符，绝大部分为原生结构和证据 metadata |
| `submittedChars` | 每次 1,117，仅为所选正文汇集，不能冒称完整模型输入大小 |
| coverage `textChars/reviewedChars` | 1,099/1,099，以已采用观察的 UTF16 范围并集为分母；不等于原件生效全文 |
| 被送入 global 的来源观察 | 每次 16 个唯一 records = 15 个非空独立段落＋1 个完整表单 |
| 实际完整表 | 每次保留 5 rows、15 cells、19 paragraphs，其中 8 blanks；content、parts、sourceSpans、tableGroup、relations、adoption、assets、顺序、quality 和 unknown 逐值匹配既有实际 source payload |
| 原生定位 | part/path/channel/UTF16；`physicalPages=[]`，无假页／bbox，图像返回仍待可靠 rendition mapping |

每个 global packet 的实际 `inputBudgetStatus=budget_unknown`，reason 为 `no_bound_token_observer`，`observation=null`，`extraDispatchPermitted=false`。实际 complete input 的 provider tokens 未观测，不能用正文字符、近似 tokenizer 或显存推断已确认 fit。

每个主题另有 6 条 sourceRequest，共 **102 条 request episode**，全部 `reviewExecutionStatus=not_submitted_budget_unknown`，pending=102。这些是重复主题下的独立请求记录，不是 102 个文件或独立条款。它们记录额外 source-request packet 未获预算门控准入；**global 的 17 次调用真实发生且完整 16 records 已送入**，不能把 blocked 记录误读成正文完全未发送。17 个主题 `aggregateReviewStatus=partial`、`semanticScopeVerified=false`，没有补调模型来消除该状态。

9 类比赛 criterion 都确实写入每次 system prompt：跨文件冲突、guidance/optional、references、版次、递交媒体、Works/site、项目要求、付款/LD/completion、项目语言。单独 G305 无法覆盖这些跨来源任务；prompt 包含 9 类不等于 9 项业务验收通过。

## 真实 BGE 与时间边界

8872 实际 journal 记录 1 次 `/index-canonical` 和 17 次 `/retrieve`。全部 request、response、events 文件的实际字节数／SHA、事件数／ordinal、终态逐件核验；不是 fixture 或保存响应重放代替模型调用。

| 阶段 | 实际时间及边界 |
| --- | --- |
| canonical index | 11.882661999901757 秒 |
| 17 次 retrieve | 合计 80.56673590000719 秒；单次 4.157314900076017–7.199974599992856 秒 |
| 以上 Python 请求合计 | 92.44939789990894 秒 |
| 17 组 Gateway request/response logger 间隔 | 合计 105.375 秒；单次 5.049–8.866 秒，包含 HTTP／日志等墙钟开销 |
| 任务总墙钟 | 268.750214 秒 |

这些值来自 CPU FP32 开发服务与 MiniMax 中继的本次小源运行，不是 H800 性能、GPU 单算子时间或 tokens/sec。logger 间隔不是 provider GPU 时间；剩余任务墙钟不直接归为本地算力不足。完整 tokens、provider wire usage 与内部阶段时钟未取到，暂无输出吞吐结论。小源约 1.1K 正文字符形成约 281K 完整提示字符，后续需评估保持完整表与来源保真的输入开销，不能删 blanks／topology 或以静默截断提高成绩。

## 结构化报告真实导出

三个 GET 均实际 HTTP 200，响应字节原样保存；JSON 可严格解析，DOCX ZIP 有 `word/document.xml`，PDF 起始为 `%PDF-1.4`。视觉分页、字体与浏览器表现由主任务独立记录，本文不据 magic／ZIP 通过宣称布局已验收。

| 格式 | 实际字节 | 原响应 SHA-256 |
| --- | ---: | --- |
| JSON | 502,787 | `b2088dc7cf8e88bc2a4980b1c0da209b2c485c204c1946c0b3f8c059891a8a4b` |
| DOCX | 17,885 | `ca267d9044dc1909129f2c715ebb15e5615be0de1588d89863f79e1cf209c79e` |
| PDF | 66,812 | `a1b4afdeb3c82e8eee38950ae9583d72cd1cedae1646bcf1adab35f9afe254b0` |

JSON `reportStructureVersion=competition-comments-and-checklist-v1`、`reportLanguage=en`、`language=en`。报告保留实际 job、source snapshot 与 model identity；`findings=[]`、`evidenceIndex.rows=[]`，没有为填满结构发明 citation。

Comments 的实际空输出提示为：

> No findings were recorded by this task. This does not establish that the documents have no issues. See the review scope and limitations in Appendix II.

17 个 checklist tables 共 69 行，`checklistResults` 的 69 项全部 `unchecked`，英文表格提示 `Not checked - human confirmation required`，不是已做对 69 项。报告仍保留旧完整 countercheck 的 14 行计价范围外内容，但逐行显式标 `Outside scope: pricing-document countercheck.`。具体位置为 `/checklistTables/9/rows/2`、`/10/rows/2`、`/14/rows/0..4`、`/15/rows/0..4`、`/16/rows/0..1`。这是报告模板展示的实际状态，不重新要求 BQ，也不将其列为缺失输入；这些行的留存仍须按用户“直接排除”的最新范围要求单独处理。本文没有修改报告或生产代码。

PowerShell 显示曾出现中文／弯引号乱码；已对原 UTF8 JSON、terminal job 和 Jar／workspace checklist 实际字节核验，均无 U+FFFD，job message 逐值相同。控制台 codepage 表现不记为生产数据损坏。

## 收据、复算与失败保留

收集目录：ConSense 根目录忽略的 `.local/state/oer-g305-vetting-job-20261010/run-040348-91faa2/`。包含每个 GET 的原字节／headers/status/SHA、终态、three exports、actual Gateway stdout/stderr 原 bytes、GBK 派生文本、实际 BGE journal snapshot 和离线核验。

| 收据 | SHA-256 |
| --- | --- |
| `collector-receipt.json` | `f6632c5449c8802623bcce9dce2ff8c613f01e3204834318d55bc91d7a98d0df` |
| `evidence-audit-receipt.json` | `f38ab005dd7f8f655c12b4e7159dd860b319406860151a5811b8e75dffa044ff` |
| `deep-evidence-audit-receipt.json` | `c53c824440b13a5c1a57ba0b803d9dfd6bf69888b6ce9d185fc0ef5a02c02d3f` |
| `actual-gateway-capture-receipt.json` | `558ecfff848e2f717df44911d034339ba75d096e36d9a11f68a24ae4e61286ac` |
| `actual-bge-journal-snapshot-receipt.json` | `745fec6001a9da66530fe0d68d27ca8c855c5466de96ac24b7c45bb079760464` |

最终深核验 **347/347 evidence checks**，含实际输入整表、记录数、SHA、GET、journal 和报告身份；它们不是 347 个程序测试或法律题。第一次 ignored helper 把原生 `cells` 误取为表级字段，在读取现有证据时失败；第二次比较器没有考虑 Windows text-file newline 转换，仅 strict decoded-file equality 一项失败。两次失败分别保留 `deep-audit-helper-attempt1-failure.json` 与 `deep-evidence-audit-attempt2-newline-failure.json`；只修 ignored 离线 accounting，不改来源、生产、模型输出或 job，不重复调用模型。最终比较严格复算 raw GBK bytes→UTF8 派生文件实际 Windows newline 编码。原 logger bytes 始终保留。

复算脚本为同目录上一级的 `audit-collected.py`、`deep-audit-collected.py` 和 `audit-gateway-timing.py`，参数传实际 run 目录。所有 audit 都只读已有本机证据，不联网。Gateway 捕获是 AiGateway 的实际 content logger，**不是 provider HTTP wire**；GBK 原日志可表示性及文件 newline 派生边界单独保留，不能追认 provider 原响应 wire SHA 或 usage。

## 当前验收结论

本次补上真实 G305→持久 Java→Vetting native Python→真实 BGE→MiniMax-M3→任务终态→三格式结构导出，并证明每次 global 输入保留原表和 unknown。它没有得到有内容的审查 assessment，额外 source-request packet 因完整输入 token observer 缺失而 blocked，报告 checklist 全部未核；不能宣称 Vetting 已符合正式比赛预期。

官方八题执行仍未由本收集器执行；2d／2e 答案未读入模型，正式多来源审查评分未完成，权重训练仍为 0。后续由主任务按 [官方业务验收计划](./oer-official-business-acceptance-plan-20261010.md) 保持答案隔离、冻结运行身份、核对实际引用与报告业务准确性；视觉与前端验收、全库原件质量、真正留出泛化、完整 token 观测与 H800／Linux 发布仍分别报告。
