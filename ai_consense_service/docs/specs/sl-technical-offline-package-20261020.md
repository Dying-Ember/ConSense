# SL 全版本技术解析与数据库离线包现状

日期：2026-10-20。状态：**只读盘点完成；全版本高质量解析、完整离线包和目标还原尚未通过。**

用户本轮明确要求：SL 技术部分的所有版本均取得高质量解析结果，连同数据库完整带入比赛；不能只带最新版本。本轮完成后先 commit／push。此要求扩充交付分母，不把历史“尚未冻结打包”的记录当作停止准备的理由；原件质量、制品完整性、实际还原与比赛运行分别验收。本文只盘点和提出最小后继步骤，不连接数据库、调用 HTTP／OCR／模型、启停服务或实际打包。Git 只收代码、文档和无密钥清单，原件、模型、数据库、密钥及运行证据留忽略的本地目录。

## 1. 当前版本分母与身份

**已取得并核对的是 7 个不同字节来源：官网当前列出的六个完整库，加比赛 Batch 2 原件。** 不是声称找全官网曾撤下的所有历史版本。官网目录证据为 [10-19 调查](../research/oer-sl-technical-version-investigation-20261019.md)及本机 `.local/oer-sl-version-research-20261019/catalog-live.json`；本轮未重新联网。目录快照为 2026-10-19 观察，官网最后修订标注 21 July 2026。

本轮对下表七份实际 PDF 重新计算 SHA256 和字节数，全部与 `verification.json.sourcePins` 相等。这里只核了七原件和必要的小型清单，没有扫描模型权重或重跑解析。文件名、封面版次、上传业务身份、媒体格式、原件 SHA 和解析修订是不同字段：七件均为 PDF；研究用 PDFium 原生文本不是另一原件格式，也不是正式 OER 修订。未知的 DOCX 同版来源不补造为已存在。

六个官网原件根为 `.local/oer-sl-version-research-20261019/<versionId>/`。比赛文件根为 `Ref/2026-09-04_Documents to Shortlisted Candidates (Contract Advisory)/2a_Standard documents/`。

| 来源 ID／封面或版次事实 | 实际 PDF 文件名 | 字节 | SHA256 |
| --- | --- | ---: | --- |
| `sl2022-original`；Original version，封面未印年月 | `SL2022_052022_31052023_Orginal_OGCIO_V3.pdf` | 45,194,143 | `8f384759bda31e0734d07bf5bcf5899d5a581cff826d4c3685ace51053ebbec7` |
| `sl2022-batch01`；April 2023 version | `SL2022_042023_19062023_Batch01_OGCIO_V1.pdf` | 46,843,050 | `8479e1b7ce6885cb89c126271a31134ce2978140336ceb98a78c0252319b35cb` |
| `competition-batch02`；比赛 April 2024 来源，独立保留 | `26_Specification Library_SL2022_042024_Batch02.pdf` | 47,975,184 | `6331c283da806846a06644814fa2271182314dbc2235c711a16e8492faaa0492` |
| `sl2022-batch02-rev1`；官网 April 2024 rev 1 | `SL2022_042024_Batch02_OGCIO (rev 1).pdf` | 48,127,395 | `969e2a986d1a247b08fc18c39d7bed592b76fe6480a525973310a4daf46b549f` |
| `sl2022-batch03`；封面 April 2025，文件名带 052025 | `SL2022_052025_Batch03_OGCIO_final.pdf` | 50,767,072 | `f57cdb0d70ff72b385d0a563509542eec027b30f73131112a693dc13b06ef515` |
| `sl2022-batch04`；封面 April 2026，文件名带 052026 | `SL2022_052026_Batch04_OGCIO_final.pdf` | 79,864,310 | `dd73415b110db47793be45ed1c97009ba01aa14acfb66faeccbe1df47341f89f` |
| `sl2026-original`；Original version；官网另给发布／适用月份 | `SL2026_072026_Original_OGCIO_final.pdf` | 50,293,363 | `949b55e160b04c7f00965f0ebce51fa20db958bd6d0f394205e6e8b74d7c643d` |

每件均保留九技术库及其本版物理页边界。下表来自 `summary-evidence.json.versions`，本轮重新求和为 **30,134 个容器页、22,214 个技术页位置**。这是跨七件的来源分母，包含跨版本复用内容；不是独立训练样本数、正确页数或已完成解析页数。

| 来源 ID | 容器页 | 本版九技术库总范围（含端点） | 技术页 | 范围前的元数据／PRE 页合计 | 原生书签 |
| --- | ---: | --- | ---: | ---: | ---: |
| `sl2022-original` | 4,093 | 1042–4093 | 3,052 | 1,041 | 77 |
| `sl2022-batch01` | 4,299 | 1102–4299 | 3,198 | 1,101 | 77 |
| `competition-batch02` | 4,300 | 1127–4300 | 3,174 | 1,126 | 12,753 |
| `sl2022-batch02-rev1` | 4,300 | 1127–4300 | 3,174 | 1,126 | 12,753 |
| `sl2022-batch03` | 4,368 | 1162–4368 | 3,207 | 1,161 | 124 |
| `sl2022-batch04` | 4,389 | 1181–4389 | 3,209 | 1,180 | 126 |
| `sl2026-original` | 4,385 | 1186–4385 | 3,200 | 1,185 | 126 |
| 可复算合计 | **30,134** | 每版单独定位 | **22,214** | **7,920** | 不跨版合树 |

九专业逐版起止完整表在调查文档“九个技术库的实际分界”和每件 `cross-version/<versionId>-inventory.json`。不能把比赛件的 1127–4300 或固定书签 depth 套给其他版。SL PRE 仍排除于业务技术索引，完整 PDF 作为身份／溯源容器保留；技术正文内部的 General、定义、条件、注释、附录及对 SL PRE 的引用不按关键词删掉。图纸文件先识别后排除，不因打包扩展为图纸解析。

比赛件与官网 rev 1 的 4,300 页原生文本比较为 4,298 页相同、3556／3557 两页不同；技术页为 3,172／3,174 相同。两者在 `FWP3.1.5.030.B` 的两处材质要求有实质差异。这只证明该组原生文本比较，不能据此共享两件的图像／坐标／解析修订、自动采用最新版或合并为一条来源。

## 2. 逐版本实际产物、修订与质量状态

| 来源 ID | 已定位产物及接受边界 | 当前 quality／unknown | canonical／数据库／索引定位结论 |
| --- | --- | --- | --- |
| `sl2022-original` | 独立 PDF、来源 receipt、原生书签／逐页文本、FIN／ELE Appendix 原页调查 | `SOURCE_INVESTIGATION_COMPLETE_OER_ACCEPTANCE_NOT_RUN`；完整 OER、全部技术页来源质量和全版还原未验 | 本轮限定检查的两份 unified registry、Local05 及 SQL16 source 行均未找到该 SHA 的正式成果；其他历史目录未做全盘否定 |
| `sl2022-batch01` | 独立 PDF／receipt／书签／逐页文本；FIN 续表正反例与条件变化 | 同上；原生文本／目验样本不能授整版解析通过 | 同上 |
| `competition-batch02` | 既有技术 scope；179 完整输入的 Local05 发展基线；三页 AMR reviewed 历史结果及当前 registry 三页 root；后续 native 重解析另存 | Local05 `finalParsingAccepted=false`、`businessAccepted=false`；失败／unknown 保留，不能用后期局部产物追认整版 | 实际位置见第 3 节；存在旧版成果，但并非全部 SL 高质量数据库包 |
| `sl2022-batch02-rev1` | 独立 PDF／receipt／书签／逐页文本；与比赛件差异原页／diff | 来源调查完成，正式 OER 质量未运行；不得沿用比赛件旧修订 | 本轮限定检查未找到该 SHA 的正式 canonical／Local05 成果；SQL16 对应行 0 |
| `sl2022-batch03` | 独立官网 PDF；封面 April 2025、ELE36 图文表单与逐页原生文本 | 正式 OER 质量未运行；旧 P: 自存副本未取得同字节证明，不与官网件合并 | 本轮限定检查未找到该 SHA 的正式 canonical／Local05 成果；SQL16 对应行 0 |
| `sl2022-batch04` | 独立 PDF；截尾书签、PLU 原件残缺 Guidance、逐页原生文本 | 正式 OER 质量未运行；原件残缺不补造为解析器恢复成功 | 本轮限定检查未找到该 SHA 的正式 canonical／Local05 成果；SQL16 对应行 0 |
| `sl2026-original` | 独立 PDF；`.10`／TOL／Building Services 编号及 ELE36 图文表单调查 | 正式 OER 质量未运行；不得复用 `.9` 的编号假设或技术范围 | 本轮限定检查未找到该 SHA 的正式 canonical／Local05 成果；SQL16 对应行 0 |

六件官网新来源的逐页研究文本位于 `.local/oer-sl-version-research-20261019/cross-version/*-pages.jsonl`，比赛件也有独立同类文件。研究 receipt 的 0 OCR／0 model 不表示这些图文混合技术页已经完整读出。ELE36 局部图片正文、跨页同一行、FIN 跨页／单页表及版次编号差异仍需进入既有同源 observation → canonical 链。该轮没有把新六版写进活动 DB／索引，不能只把研究文本打包后称“全部高质量解析数据库”。

比赛旧版 Local05 的 `sourceCoverage` 本轮只读重算：179 SL 输入覆盖 3,174 原页位置，声明 parsedPages 为 3,171、ocrPages 为 0；不完整输入为 `building_services-ELE36` local20／21（原页3437／3438）和 `architectural-COM11` local32（原页1329）。这些是既有产物的覆盖声明，**不是本轮质量分数，也不是对 SourceDocument 状态的重写**。后继成果若修复其中一页，也必须记录新修订及其采用依据，保留旧失败；不把 PARTIAL 强转 PARSED。

Local05 的已采用发展基线增量还包括原页1312／1330的两个 whole HTML table parent；其 `normalWorkerQualified=false`、geometry／legal ownership／final quality 均未接受。清单保留 754 个完整物理表产物和 19,371 个 GCC／GCT／SL 联合 parent、20,700 个向量窗口；754／19,371／20,700 均不是 SL 正确表／正确条款数。COM55 recovery、672 格的已知缺口、辅助 parent 噪声及深层条款／跨页语义 unknown 仍在原 `knownGaps`。

后继 `.local/oer-fixed-six-reparse-20261015/actual-native-02/execution-terminal.json` 确认全部 186 输入已作 complete-input attempts、`qualityAccepted=false`。它是 GCC／GCT／179 SL／三 DOCX／AoA／APL 的联合执行分母，不能当七版 SL 分母。其 `comparison-native-02-partial-01/comparison-report.json` 明示 `runInProgressOrIncomplete=true`，只含25个返回未评分、160未执行、1无闭合 driver 的当时快照；最终 terminal 不会自动补全该旧比较表。原 sl-018 的文件生命周期失败、方法权限失败和 source-unscored 状态需原样保留。

## 3. 实际存储位置与“数据库全部”的覆盖边界

本轮只读文件和已有导出，没有打开 H2／SQLite、请求 Qdrant 或调用 registry 服务。以下是可定位的不同持久层，不能用其中一层替代全部。

| 层 | 已定位位置／身份 | 已知覆盖与未验证点 |
| --- | --- | --- |
| 研究原件与来源证据 | `.local/oer-sl-version-research-20261019/`；比赛原件在 Ref，七 SHA 见第1节 | 七件原件及本版定位证据已存在；不等于正式 canonical 成果 |
| 比赛旧版 scope | `.local/state/sl-scope-bookmarks-20261009/sl-technical-scope-manifest.json`，SHA `bb1512de4b16bfa2c10f03a8197b0c798c105e3359fc649d35391f6fcb66e3d6` | 只绑定 `6331…0492`；新六版需各自采用版 scope manifest，不能复制该 policy hash |
| Local05 冻结说明 | `C:/Users/Administrator/.codex/local-state/consense-oer/fixed-best-baseline-20261012/immutable-freeze-05-01/` 内 `frozen-baseline-manifest.json`、`corpus-manifest.json`、`runtime-plan.json` | 显式 development baseline；canonicalRegistryPublished=false／finalParsingAccepted=false；原件集合为 GCC、GCT和单个比赛 SL |
| Local05 corpus／可复用源修订 | 同一根 `cpu-corpus-05-logical/corpus.jsonl`：154,427,626 B；corpus manifest：797,430 B；逐输入 normalBlocks／normalPages／structure 等 sourceArtifacts 路径保存在 sourceCoverage | 已确认文件存在与大小；本轮未重读完整 raw 或整库 hash。原 sourceArtifacts 依赖跨 `.local/state` 和 C: 证据根，离线包必须闭合这些依赖，不能只拷 corpus |
| Local05 向量及元数据 | `freeze-2100-05/state/qdrant/collection/project_c18c9b7514b1c830_b0a7bc46bb241f9a/storage.sqlite`：277,737,472 B；`state/projects/c18c9b7514b1c830e02cecd9bec3a11350ef947f5aa2aae625cbd4f862eb477e.json`：227,118,049 B | 本轮只 stat，不打开数据库；indexSignature `b0a7bc46bb241f9acbace0bab75a648c83db3ae339c79e68c4d689f1b635f64b`。已有清单的向量／恢复证据与本轮检查分列，未刷新其 runtime／完整闭包接受 |
| 当前 unified 模块 registry／CAS | `.local/oer/unified-vetting-state/` 与 `.local/oer/unified-advisory-state/` 的 `canonical/`、`artifacts/`、`projects/`、`qdrant/`、`run-evidence/` | process receipt 只用于定位，不代表本轮健康检查。源码 `oer_unified_server.py`80–81 使用同模块 `artifacts` CAS 与 `canonical` registry |
| 当前可见 SL root | vetting 的 `canonical/2f5d323129618d3c967268cc610e9045552adb38ce9fb8cd3d2428c54121e634.json`；文件SHA `d3aa6ec49c4fd40bca9906b53df442381fed7884217925f43dcdeabb614d2727`；projection revision `1f75916046ea0a25e31b94b739b3a0010e4762babf1697a5364dc097bcb14a5b` | 1 chunk、原页3436–3438、比赛 SHA；capture complete 只限这三页，humanConfirmed=false／semanticScopeVerified=false。递归检查 root JSON 观察到1个 CAS descriptor，文件存在且大小符合；未 hydrate该CAS、未全闭包 rehash，不能据此授完整raw／asset包通过 |
| 当前 registry 有限盘点 | 本轮解析 vetting 顶层4份 root JSON、advisory顶层8份 root JSON，单文件上限90MiB，前后size／mtime均不变；未运行registry方法 | vetting仅上行1份匹配七个SL SHA，advisory匹配0；新六版均0。这个12-root快照不是全盘文件缺失证明，也不是活动数据库／索引事务快照 |
| 本轮业务 H2 的既有 SQL16 | `.local/runtime-preparation16/db-snapshot-round16.sql`，2026-10-20 05:18:25Z完成，1,286,084,208 B，receipt SHA内SQL为 `cb95962d2d62aff32e4230d41139bc15e200c4950f7410b42608c0e968c54e09` | 是测试H2逻辑导出，不含外部原件字节；其后新增写入需最终保全。不能当全部历史／活动H2和OER库的完整当前备份 |
| SQL16已有离线行身份导出 | `.local/runtime-preparation16/projection-inspection07/execution/inspection/source-document-file-identities.jsonl`：24行，24个source_sha256均有值，SL file_key行0；对七个SHA逐一相等计数均0 | 只证明该SQL16 source_document导出没有这七件，不推断其他数据库／registry不存在SL。导出不含parse_status，不能凭此重贴PARSED/PARTIAL |
| 业务采用／审计链 | JPA SourceDocument与`canonical_source_binding`；V14迁移保original/source/projection revision、scope/module、adoption/reference及snapshot bytes身份，后续迁移保family pins | 最终包须连同源文件存储、绑定历史、实际采用、项目／人工记录／报告快照及对应修订保全；本轮未新查表，其他历史业务数据库的路径、所有行、当前采用状态仍待Root在既有备份计划中纳入 |

历史 `sl-amr-canonical-projection-receipt-v1.json` 的 projection `a153a4c6…`、15 chunks／17 assets／53 cells 是另一个已保存局部研究修订，不能与当前 `2f5d…` root 或 Local05 向量包混为一条“最新全SL成功”。应沿原 source／projection revision、adoption和CAS descriptor恢复历史，不能改地址或新建平行真源。

既有[来源部署清单](../../../docs/oer-source-deployment-manifest-20261009.md)要求原始产物／CAS／规范结构／scope／条款与表图定位／切块／向量／词法索引同包。已公开的 `oer-freeze-20261012` 是冻结模型与05向量库/provenance的独立包，中央规则明确其未附原始PDF、source descriptor闭包、corpus/runtime plans、活动H2、私有项目等；不能当本次七版本＋全部数据库包已完成。远端包manifest／恢复记录是已有证据，本轮没有下载或重新验收该包。

## 4. 最小后继实施与交付门

1. **先补一个版本清单，沿既有源修订。** 七件均有独立 source ID、原件SHA、PDF媒体类型、封面版次、URL／取得receipt、九专业map与scope SHA。每件列出全部已保存解析修订／raw与CAS／adoption／quality及失败、unknown、unexecuted，不只留最新。P: Batch3副本若以后可达，先核字节；同SHA可登记副本位置，不同SHA新增来源记录。当前未取得的历史撤下版记 discovery unknown，不凭文件名虚构数量。
2. **恢复按版、按完整技术单元的质量工作。** 已有比赛版继续保旧正确与失败；六新来源先用本版README／目录／source observations建立完整技术分母，再在同一OER链中处理。先覆盖FWP相同干号不同材质、FIN续表正反例、ELE36局部图像与跨页同一行、2026`.10`／TOL等实际差异，再推进各版完整技术范围。不能为凑全版本立即全书扫描OCR，也不能从native字数或代码通过授质量。训练、模型更换和全量执行另按统一Goal及Root有限窗口安排。
3. **按现存存储闭合私有包。** 复用 `ai_consense_service/tools/offline_delivery/` 的`assets`／阶段输出manifest以及既有Qdrant迁移入口；新增版本目录与依赖遍历即可，避免另写parser或第二套registry。闭包需包括七原件、本版scope、每个保留source/projection revision、完整raw/CAS/asset及坐标变换、采用与质量台账、corpus／chunk／BM25／vector recipe和持久状态。SQL导出需覆盖所有实际业务库及最后写入，配套上传原件、渲染资产、历史DOCX/PDF blobs、人工决定／advisory历史／报告snapshot；SQL16只作已知历史备份，不覆盖替换为新票。
4. **准备完整运行配方后，在隔离副本还原。** 私有发布manifest绑定最终code commit、OCI digest、OS／JDK／Maven／npm／Linux wheels、Paddle／MinerU／native／BGE／rerank／业务LLM的实际模型及processor/tokenizer/template、字体／renderer／许可证。旧Windows路径显式映射，不改冻结库内部身份；不把Windows venv复制成Linux环境。先验断网启动、七件可按版选择、旧版不被latest覆盖、同版复用不重做文档OCR/embedding、原件回取、失败状态保留、DB行／blob／revision及CAS闭包比对和向量实际读回；再验目标H800。制品存在／恢复退出0／native结果可读分别不是来源质量或比赛业务通过。
5. **本轮收口后由Root commit／push。** 先完成三模块及适用验证、确定采纳文件和清单，再提交本轮实际成果；当前dirty主树不能`git add -A`把无关修改归入本次。只提交审过的代码／文档／manifest，原件、数据库、模型、密钥、测试运行大产物留忽略私有存储。本文作者没有commit／push；用户最新要求已登记，统一Root负责最后的具体提交与远端验证。

当前明确缺口为：六新来源的正式高质量解析与各版scope发布；比赛版全技术页及历史残差的质量闭合；七版source→revision→CAS→DB→index完整对应；全部实际数据库／文件资产当前快照；本机与目标的完整离线还原及H800验收。缺口不能通过删除旧版本、改写PARTIAL／failed、拷贝一个SQLite或把研究文本标为canonical来关闭。

## 5. 本轮可复核的小型证据 pins

以下哈希均在本轮只读重算；七原件当前SHA见第1节。大数据库、向量、模型及完整raw未在本轮全扫哈希，相关历史hash仅按既有receipt解释。

| 本机证据（相对ConSense根，C:项除外） | 字节 | 本轮SHA256 |
| --- | ---: | --- |
| `.local/oer-sl-version-research-20261019/summary-evidence.json` | 34,798 | `025eecf998cb54ea7c6efe55a833fa5baaf211675c32b528b0f61d0c08f83928` |
| 同根`verification.json` | 6,701 | `96a6e58bb1c1dd69aba448256a2edec6f249dca999d97c243ff53b860de140b7` |
| `.local/state/sl-scope-bookmarks-20261009/sl-technical-scope-manifest.json` | 54,011 | `bb1512de4b16bfa2c10f03a8197b0c798c105e3359fc649d35391f6fcb66e3d6` |
| `C:/Users/Administrator/.codex/local-state/consense-oer/fixed-best-baseline-20261012/immutable-freeze-05-01/frozen-baseline-manifest.json` | 15,706 | `deb87c356c2a724f71d8bc316d86c4272fa9ebe014fb2bedf4c18de29a425aab` |
| 同目录`corpus-manifest.json` | 797,430 | `37825fb39be960ec50db838374b7fc4457ebe37c179bfaea55fcdd451e56b188` |
| `.local/runtime-preparation16/db-snapshot-round16-receipt.json` | 1,332 | `a6e8bf469a1558e32b0c2f58a720cfe55a49bfe981b542ed0fc2e29864564dac` |
| `.local/runtime-preparation16/projection-inspection07/execution/inspection/source-document-file-identities.jsonl` | 9,508 | `22f64e74c745f8493775fed8ae05bc254616ad596f40199659b37974e76cbd74` |

复算口径：`summary-evidence.versions`逐项`technicalEnd-technicalStart+1 == technicalPages`、九专业范围之和相等，再分别求和pageCount／technicalPages；SQL16用导出行`source_sha256`与每件原件SHA逐项相等计数。当前registry盘点仅读两模块顶层`canonical/*.json`的`source.sourceSha256`及revision元数据，保留检查范围；没有调用可写registry、数据库或模型。本文新增是本轮唯一文件写入，五个共享入口及VT02冻结候选未改。
