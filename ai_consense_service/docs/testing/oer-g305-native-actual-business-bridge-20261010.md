# G305 原件的持久 Java／DB／native collection／真实 BGE 接线

日期：2026-10-10，Asia/Shanghai。本文记录独立开发验证，使用实际赛方 2b 的项目 G305 原件；没有读取 2d／2e 答案，没有执行官方八题，没有调用 LLM，没有改在线 8080／5173／8092 或它们的文件。浏览器与业务回答由后续独立验收记录负责。

## 原件与采用前审计

源文件为 `16 - Appendix G305 - declaration of confidentiality, etc.docx`，原字节 SHA-256 为 `16b4630df29b7aa76e8511b42f12359c8d16514932e60046e25f1c49e167c57e`。完整原生观察有 174 nodes、64 paragraphs、1 张 5 行／15 cell 的完整表。原生位置为 DOCX part／Clark path／channel／UTF16，未制造 PDF 页码或 bbox。

独立审计先核验原件 XML／全部实际 CAS／literal 文本／整表，164 项通过；随后对候选 source reference／adoption 做 305 项核验。引用参考保留全部 64 paragraphs 及完整表；16 个可检索采用单元为 15 个非空独立段落和 1 个完整表单。表内 19 paragraphs 包含 8 个空段落，全部保留。独立空段落的源证据仍保留在 reference／ledger／CAS，不创建空文本检索单元。

原件含 `APPENDIX G305`、保密及伦理声明标题、`CONTRACT NO. 20250101` 和实际项目标题。外部 GCC 4.4／5.30／14.1(4) 引用没有改成 G305 自身的 clause IDs。Word 的生效文字、法律适用和 rendition mapping 仍为 unknown。既有 LibreOffice 派生页的目视核验不建立原生节点到图像的映射。

独立证据位于根目录忽略目录 `.local/state/oer-g305-independent-review-20261010/run-033745-b84ef1/`：

| 收据 | SHA-256 |
| --- | --- |
| `source-audit-receipt.json` | `957993a54c6f82953af731aaf8e0a84d0276e825a568e10285bdf7cf3f40497a` |
| `source-reference-adoption-audit-receipt.json` | `29048323e306eef04f82a00dc9db83de422f908adef88967b9bcfb5f9572fda2` |

## 实际持久来源与 root 绑定

Java 原生 parser 在全新复制 checkout 中实际解析原字节。真实 Spring transaction 在自己的 file-backed H2／JPA 提交项目与来源后，使用数据库生成 ID 再补全 reviewed adoption；没有使用 fixture 的 `42` 或其他人工 ID。

默认 source router 对 G305 仍为 `UNKNOWN`，已保存 `default-routing-before-review.json`。完成原件审计后，仅在这个独立项目使用既有 `OerSourceMetadata.capture(..., router)` 的 trusted exact-SHA registry 重载，把实际 SHA 绑定到 `G305／project_document／tender` 和独立 review version。这不表示默认上传已自动识别 G305，不把文件名识别当作采用，不改生产路由代码。

| 身份 | 实际值 |
| --- | --- |
| 项目 | `g305-native-9c5e9309-1cc1-46b5-9a2b-172a7e16b4c7` |
| 实际 JPA documentId／bindingId | `1／1`，属于本独立 DB |
| Java parsed sourceHash | `4161cf77eac6b290ec0acff864b5d43132321f1c7d671a550c84d9c0238d0399` |
| Native observation source revision | `c062685d7ab1fb58d0a125914a011c066815f6fe18e425c905ff5e0adfff280b` |
| 采用 manifest 内容 SHA | `2848a7f093718b43ac1ddd8d0d3e43e8862c7b8fc8988f49e77bf41c519b3616` |
| 实际采用文件字节 SHA | `23015a593d5eb6112b3089186ce1db95d429c972eb12f75bea79e15e3f62f57b` |
| 唯一 document root seal | `9982e13c3d66b21b42af544fececd21ec069e9e4b4d012ca0a62121668010dbb` |
| 旧完整 public seal | `a40cff11fdc51bffb8189fa08a034509eea1b9619afd091e90303a787bc39808` |
| Projection revision | `859ee7a6a552138f89b36c19a0340d075612aba3491a8d40eaf47976dd0dedf8` |
| Canonical sourceHash | `26a60baf183722ed07035593fd0e5592288452d04802c57b4a61acab58cf6162` |

候选 adoption 的既有字段全部逐值保留，只填 `missingActualFields` 指定的 actual DB／role／module／eligibleModules／purpose／class／scope policy。实际 `sourceClass=project_appendix`，module 为 Advisory，eligibleModules 为 Vetting＋Advisory；policy SHA 取自实际 8871 health，而不是写一个假 scope。

通过 owned 8871 的实际 CAS 把原 artifacts 逐字节验证后捕获，调用 `/project/native-collection`，再从实际 HTTP 取 root。Root 18,375 bytes；1 个完整 batch 316,053 bytes，内含全部 16 units；都小于 16MiB。`registerReviewed` 持久化的是唯一 root，数据库没有为 batch 创建来源或绑定。该小源的容量通过不能外推到 PRE 最大完整表。

## 实际 Session／真实模型／HTTP 与失败保留

`OerBusinessEvidence.load` 从真实已提交来源和 binding 加载 16 units，完整表仅一个原子 unit。实际 BGE index 为 5,841ms，实际 retrieval 为 7,297ms，query 为表单需要的签名、公司印章、姓名、职位、承包商名称和日期字段。实际 response 返回 3 ranked seeds／3 source units，包含完整表，sealed payload 与完整 source closure 均可按实际 CAS 复算。这是 CPU FP32 开发环境的小源工程计时，不是 H800 性能或法律答题正确率。

实际 Java HTTP 在独立 random port 56630 返回 table pointer 200。Pointer 保留全部表单 parts／topology、原源与实际 DB 身份、unknowns、`humanConfirmed=false`，`physicalPages=[]`，`visualReturnStatus=unavailable_pending_verified_rendition`。

第一次 opt-in Maven probe 为 **1 test／1 failure／0 error／0 skip**。失败仅来自 collector：本地 `valueToTree` 期望 `bboxConvention:null`，Spring HTTP serializer 省略该 null 属性。所有实际返回的共同字段精确一致；这是正确 native wire 的 null 省略，没有发现生产代码缺陷。原始 test XML／失败 receipt／实际响应／H2 均保留，没有把它重写成 Maven 通过。

修复仅发生在忽略的 probe 比较器。随后实际 CAS 冷复验、完整 table／hit／closure／adoption 对照和真实持久 H2 只读重开通过；保存 `postvalidation-receipt.json`。527 个检查由 **22 个业务／身份／证据检查＋505 个冻结源码文件检查**构成，不是 527 个测试或业务题。没有因这次 null 差异重跑 BGE。

在主任务提醒前曾准备一个第二复制 run，并启动编译；已核实 owned JVM 30296 身份并在 seed／projection／inference 之前停止，保存 `run-034406-beaf72/cancellation-receipt.json`。该取消 run 没有成为第二模型结果。只读 DB 审计 helper 的第一次编译因访问 package-private routingHash 失败，在连接数据库之前停止；修复忽略 helper 后按同一个生产表达式重新计算，错误记录单独保留。

原 seed JVM 退出后，真实原 file-backed H2 以 `ACCESS_MODE_DATA=r` 重开，source 原文／结构／routing 和所有已冻结 binding 字段逐项相等，只有 1 个 root binding。此证据证明当前 Windows JVM 退出后的持久性，不证明 OS 冷启动、MySQL 并发或 Linux／H800 恢复。

## 准备并启动的独立运行服务

基于同一冻结生产源码复制 checkout，使用 `-DskipTests package` 制作独立 fat jar；没有再次执行模型或覆盖在线 `target`。Jar SHA 为 `bbe2bdd520c301f9631584d3aa906b3ae17ce11699ee37b85020af1728654d7f`。复制源码与当前 workspace 比较没有漂移，runtime／jar／source manifest 身份均落盘。

主任务完成审计后，首次启动 owned Java `http://127.0.0.1:18082`，PID 34600、创建 UTC `2026-10-09T19:49:04.1536610Z`，保留 exact jar command marker、stdout／stderr。实际 `/api/advice/{projectId}/sources?scope=fullset` 返回一个来源且 sourceHash 与已提交来源精确相等。原 8080／5173／8868／8092／18081／8869／5174／8871 listener 的 PID 未改变。

Profiles 为 `h2,oer,minimax-relay`，canonical backend 为 owned 8871。MiniMax relay 地址为 8092，专用 relay token 只从已存在的 Machine／User Windows 环境变量读入 child environment，不写入命令、manifest 或日志；没有把官方 API key 发给前端。启动阶段没有重建 index 或 ask。后续浏览器的实际 native 展示／单题 MiniMax 回答由主任务执行并独立记录；本报告不替代它们。

本机实际证据目录：根目录 `.local/state/oer-g305-business-bridge-20261010/run-034106-69f0a2/`。

| 收据 | SHA-256 |
| --- | --- |
| 首次失败 `bridge-receipt.json` | `4f6ba14648fcbc0baf10fcd049eceea56dc5db4111f1fa2b70c4d338425f43c4` |
| `postvalidation-receipt.json` | `f44a898f62c6620faa76a7482b81f3097330db68058d9d3d8349ac2c679d8be6` |
| `actual-persistent-database-reopen-receipt.json` | `319fa250d354d151cc8d3115fd0e69a82877e7cef0d5d922620266c0ca78625c` |
| `runtime-package-receipt.json` | `30c4835d8a75d6e0a0f8b333e4487e606b44f1fad68d8468e8b221c451a92a21` |
| 首次启动 `runtime-preparation-before-cors.json` | `c8bffaf66858852dc33f5bbdb7a1840bcdbf7f9421dcae8354ba990a38043e9e` |
| 首次启动 `java-process-first-start-before-cors.json` | `29edfc2941db25ad4158644c3f1fe433d391bbba9a117a99092ed4de0fb65bdd` |

### 隔离浏览器端口的配置修正

主任务的 5175 前端首次刷新证据索引快速返回失败。只读诊断证明 `OPTIONS` 携 `Origin:http://127.0.0.1:5175` 返回 403 `Invalid CORS request`；8871 的模型 journal 没有新的 index 请求。原因是独立 Java 启动只继承原 5173 的 CORS 配置，直接 test HTTP 没有 Origin 而通过。

修正仅在忽略的 startup 添加 `http://127.0.0.1:5175,http://localhost:5175` 两个 origin；同一 jar／DB／adoption 保持不变。确认 executable／创建时间 ticks／exact jar marker 后，仅重启 owned 18082；第一 PID／配置／403 保留。新 PID 98012、创建 UTC `2026-10-09T19:54:15.6185430Z`；实际 OPTIONS 变为 200 且 `Access-Control-Allow-Origin` 为准确请求 origin。这里只做 read-only preflight，没有代替主任务重复 index 或 ask。第一次 stop guard 因 JSON 时间字符串省略小数末尾零而安全拒绝，之后按同一 UTC ticks 复核再停止；没有凭 PID 停止其他进程。

| 修正收据 | SHA-256 |
| --- | --- |
| 当前 CORS `runtime-preparation.json` | `39eda5cbae3435850e1bb2e5199d65abb19bc915fe5a6b0166a9a79787182ae9` |
| 新 PID `java-process-receipt.json` | `993e73d656aed615b10584fa933a98a4934edd132581d6e88e46f9e5f40c7185` |
| `cors-preflight-after-explicit-config.json` | `fa1a07b74cc1d9da1b812623097ea0ba0da260d0efe876a41232d9085f69900f` |

### Vetting 的只读就绪与独立模块进程

实际 `/api/vetting/{projectId}/files`、`runs/latest`、`findings`、`metrics` 均返回 200；只有已提交的 G305 文件、没有审查任务／findings，所有 metrics 为 0。完整 native table pointer 携 `module=vetting` 也返回 200；binding eligibleModules 的 Vetting 权限真实存在。5 次只读调用保存在 `vetting-readonly-readiness-receipt.json`，SHA `76013dacc8835020076dddc536d184e922b6399f74c091bbcda506801da7d058`。没有调用 report 导出接口。

Report JSON／DOCX／PDF 的现有 GET 入口需 latest `COMPLETED` run、current sourceSetHash 和对应 findings；没有完成任务返回 4103，来源变化返回 4104。真正 run 可由 `POST /api/vetting/{projectId}/runs` 启动并轮询 `GET /runs/{id}`，本阶段未启动。

原 18082 只有 Advisory retrieval URL，fallback 的 8871 明确是 Advisory target；不能把它当 Vetting 使用。按主任务指示，新增独立 owned native collection `http://127.0.0.1:8872`，完整复制已冻结 Python 源码，并建立自己的 CAS／registry／Qdrant／request state。只捕获实际 reviewed root 的 descriptor closure 及 root 自身实际 CAS payload，没有生成新审核结论或改动 adoption。

本机独立运行目录为 `.local/state/oer-g305-vetting-runtime-20261010/run-035750-6d5b8f/`，wrapper PID 55708／listener PID 24612。实际 health 为 `targetModule=vetting`、同一个 policy SHA 与 native collection capability；实际 root HTTP 与已审 root 精确相等。`health-validation-receipt.json` SHA `9b451a6e27ebab92c954573dda6ed113dbee47d533acf1a2cf3a95c5f417d6c2`。没有 index／retrieve／LLM／Vetting job。第一次 staging 忽略 root 无法递归自引用的 payload blob，在 launch 之前被冷验证拒绝；失败 run `run-035719-72176c` 保留，随后只读取并捕获已存在的准确 root bytes，未修复或重写原 source。

忽略 startup 已准备 `--consense.oer-evidence.retrieval-base-urls.vetting=http://127.0.0.1:8872`，`vetting-java-startup-addendum.json` SHA `9d398e84c235c1b1d466bae3335359ab1c280bc150e04e5472ce7a27f91f7bfd`。**03:58 时该参数仍未应用到 PID 98012**，为保护正在进行的主任务 Advisory 验收，本 agent 没有再次重启 Java。无需修改 frozen jar；主任务在合适阶段显式接入后才能运行 G305 小范围结构化报告。它仍不是正式比赛多来源报告验收。

## 仍须完成

本轮没有执行官方八题或正式 Vetting 报告，权重训练仍为 0。它补上真实原生小文件的 DB／root／Session／BGE／HTTP 接缝；不支持把这一 source 视为完整合同法律上下文，不证明整源生效文字、rendered 图像、PRE 大源容量、全 SL 技术库、比赛留出泛化或最终 H800 部署。默认上传 G305 仍 unresolved，只有独立审计后的 exact-SHA 路由注册通过。
