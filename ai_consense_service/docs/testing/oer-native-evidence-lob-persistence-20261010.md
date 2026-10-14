# 大原生 PDF 证据的 H2 存储故障与最小修复

2026-10-10，Asia/Shanghai。遵循 [OER范围](../oer-scope-and-rules.md)。此故障发生在为官方问题准备实际GCC＋项目SCC的独立持久项目时，不是模型推理错误。原件、失败DB、完整证据和对照均保留；在线8080／5173及数据库未切换。

## 已证实的故障与对照

隔离真实GCC上传在 `run-054947-f26855` 的H2 2.1.214 trace出现 `BufferOverflowException`，调用链为 `ValueDataType.writeString` → `WriteBuffer.putStringData`，后续checkpoint也失败。根目录为 `.local/state/oer-official-source-business-bridge-20261010/`。该轮不能作为可信登记身份或业务通过。

`run-055254-2201a1` 同原件fresh隔离DB的单因素对照表明：虽然实体有 `@Lob`，强制 `columnDefinition="LONGTEXT"` 使H2 MySQL mode实际建成 `character varying`；对照仅显式改为 `character large object` 后，两份实际上传及 `CHECKPOINT SYNC` 成功。对照schema见该run的 `actual-isolated-h2-column-types-before.json`／`after.json`。初probe随后错误要求GCC整源必须PARSED，实际第1页OCR禁用使整源PARTIAL，故该probe仍为失败；它不撤回成功上传与checkpoint的单独事实，也不把PARTIAL改为PARSED。

独立最小H2 writer重现使用715,827,883个合成ASCII字符，是 `3 * length` 在signed int上溢出的第一个长度；small control通过，实际 `WriteBuffer.putStringData` 抛同类BufferOverflow。证据为该run的 `h2-longtext-min-repro.json`，输入明确synthetic，未修改真实原件或数据库。它证明H2该字符串写入分支的边界，不是原件恰好有这个长度的声明。原件完整字段长度／流式UTF8 SHA及正常重开核验另由实际上传收据记录。

对照DB已正常SHUTDOWN并fresh reopen。实际GCC `native_pdf_evidence_json` 为993,216,139 UTF16 chars／993,219,983 UTF8 bytes，流式SHA `157465dac4f43dd0923a745202bc7766b07b452f6f1a31003010c8e9dba00592`；`structured_content_json` 为497,580,778 chars／497,583,661 bytes，SHA `ecbd883563296d14091c62a1ad6537ba2b346895e9a687ccd0b13edf51408184`；`text_content` 318,081 chars。两份大JSON共1,490,803,644 UTF8 bytes。初collector以单次 `getBytes(UTF_8)` 分配中间数组出现 `NegativeArraySizeException`，失败输出保留；改为遍历完整字符串流式UTF8 SHA后成功，DB字段不改、不截断。

这些原件规模说明CLOB只解决此次内联VARCHAR写入溢出，不证明全文SL的单String／ORM读取／Java内存容量通过。完整原始观察在CAS保全、分批public transport和关系完整性仍分别受门控；不会以截短source字段或提高public cap绕过容量。字形／位置在页与行结构中的重复量正在只读测量，具体后续存储改进须保持身份、完整证据和当前源核验。

## 产品最小修复

只去掉 `SourceDocument` 的五个既有LOB字段及 `CanonicalSourceBinding` 的两个LOB字段上的MySQL专用forced DDL，保留 `@Lob`、字段内容及业务身份，由实际dialect选择CLOB／LONGTEXT。没有截断原文、删除glyph／表格／unknown、升级H2依赖、放宽采用或16MiB public上限。

新增 [NativeEvidenceLobPersistenceTest](../../src/test/java/com/consense/repository/NativeEvidenceLobPersistenceTest.java) 验证实际H2表类型、完整多MB Unicode证据的commit／checkpoint／SHUTDOWN／新factory读回，以及MySQL57离线生成DDL仍为LONGTEXT。文件DB用独立临时路径；测试大字符串是合成格式压力输入，不作法律／OCR正确性证明。

| 阶段 | 复制checkout run | 结果 | receipt SHA |
| --- | --- | --- | --- |
| 修复前 | `.local/state/oer-native-evidence-lob-20261010/red-055705-459d4d/` | 1 test／1 failure／0 error／0 skip；expected CLOB，actual VARCHAR；33.230秒 | `ffecf75a0268f8245c268d5ac608bf2975dc99eb1efa182db0b340fbb76ba613` |
| 修复后 | `.local/state/oer-native-evidence-lob-20261010/fixed-055750-b76fd0/` | 9 suites／58 tests，0 failure／error／skip；41.594秒 | `4df0234746420fd88f641d304ba42e45239ca83d7a08f2e8a7cd82ded0ac72fc` |

58的分母来自9份surefire XML：新增LOB3、既有migration1、collection backend3、fresh transaction9、native DOCX8、native PDF10、Vetting business6、report coverage4、Advice integration14。它们不能与历史151／63项相加作为独立新测试或业务正确率。所有Java编译测试在完整复制checkout执行，在线 `ai_consense_service/target` 未写入。

## 已有H2库与正式验收边界

新建库可由修正实体生成正确类型；既有H2表不会仅靠代码更新就获得本记录证明。供停止服务、离线备份、核schema后使用的 [H2专用SQL](../../tools/h2-native-evidence-clob.sql) 未自动执行；MySQL仍保持LONGTEXT，不运行此SQL。既有损坏MVStore不能假定可以通过ALTER修复，应保留并从原件在新隔离DB重建验证。

原场景重跑已经完成：`.local/state/oer-official-source-business-bridge-20261010/run-060145-06ba7b/` 使用修正实体的fresh复制checkout／fresh持久DB，`isolatedH2NativeFieldsExplicitClob=false`，不预ALTER。原始GCC＋项目SCC真实HTTP上传、checkpoint、正常关闭、再开existing DB读回都成功；upload命令59.546秒。完整GCC五个字段及parse message流式SHA／长度与显式CLOB对照相同，原件未改；GCC仍PARTIAL，SCC为PARSED，保留各自覆盖。

实际project=`official-q16-source-90602e8f-85b8-4e1a-ad61-2625c654115e`，GCC documentId=`1`、SCC=`2`，只有这轮fresh成功身份用于后续重投影／注册。原失败DB、显式CLOB对照和probe失败均保留。

| 真实重跑证据 | SHA-256 |
| --- | --- |
| `upload-receipt.json`，completed／无手工CLOB／checkpoint成功 | `19386c8a78d112f06b834282a142e3f043abcde9618b84ec4f685a44f0bd3c04` |
| `export-receipt.json`，reopenedActualSources=2／不重复上传／checkpoint成功 | `81fe4a94f1c2a7de282ca1059aa7f785b05d9b90d74b8e2e099131c9ca58fd7b` |
| `actual-source-id-handoff.json`，实际IDs／原件／Java parsed／routing | `8e8b9d138d4e657fe02780b3be8d19b19d8e713b8638448852a7cd69530f84e2` |

这验证了本轮实际故障修复和完整源的落盘重开，不追认为canonical采用／检索／M3已接通。成功轮IDs重新producer／register仍在进行。官方八题实际执行0、正式多来源Vetting语义评分0、权重训练0，Q1.6图交付及其余必要来源采用仍待完成。
