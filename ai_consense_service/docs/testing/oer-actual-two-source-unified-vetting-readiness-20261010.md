# GCC／SCC 实际来源绑定及统一 Vetting 服务准备

2026-10-10，Asia/Shanghai。此记录完成两项准备：将 GCC 六页的明确采用投影绑定到修复后 fresh Java 上传的实际 documentId；随后在新 owned `127.0.0.1:8874` 建立独立、冻结、以 Vetting 为目标的统一来源服务。没有执行 embedding、rerank、index、retrieve、LLM、官方题或结构化报告。本次来源仍是部分采用，不能据此确认完整合同语义或业务效果。

## Fresh Java 来源与 GCC 追加绑定

可信来源交接为 `D:\R&D\ConSense\.local\state\oer-official-source-business-bridge-20261010\run-060145-06ba7b\actual-source-id-handoff.json`，SHA `8e8b9d138d4e657fe02780b3be8d19b19d8e713b8638448852a7cd69530f84e2`。projectId 为 `official-q16-source-90602e8f-85b8-4e1a-ad61-2625c654115e`，GCC／实际 2b SCC 的 documentId 分别为 1／2。

此交接来自修复后产品字段定义产生的 fresh H2；upload／export receipt 均为 completed、正常 checkpoint，新 JVM 重开后完整字段 streaming SHA／SQL 字符长度相等，`isolatedH2NativeFieldsExplicitClob=false`。旧 `run-055254-2201a1` 是显式 CLOB 对照，不作为本轮实际身份。GCC stored copy／实际原件 SHA 重新逐字核对相等；未读取约 993MB 的 native 字段正文或约 498MB 的结构字段正文来产生采用答案。

GCC Java 全件状态继续为 **PARTIAL**：74 页中 73 页已解析、OCR 0 页、确认空白 1 页，未完成页 `[1]`。这六页 65–70 的独立采用不升级全件状态。SCC Java 为 PARSED，只表示实际 799 个原生位置被解析；不追认为有效编辑接受或完整法律语义已验。

GCC 追加绑定目录：

`D:\R&D\ConSense\.local\state\oer-official-source-business-bridge-20261010\run-060145-06ba7b\canonical-gcc-binding-220705-103443`

使用 [实际 GCC 真实 BGE 运行](./oer-actual-gcc-native-pdf-real-bge-runtime-20261010.md) 的冻结 producer／transport、同一原件 native／dual／source-only review CAS，唯一采用清单变动为 `/documentId`：preparation ID 改为实际 Long ID 的字符串 `"1"`。scope、role、purpose、source class、eligible modules、全部 native glyph／typed separators、whole units、关系和质量保持原值。经 produce、内部冷验、public publish、公开冷验、独立 registry.load 重算，全 50 单元的正文／顺序／页码／anchor 和关系与旧投影相等。旧 registry bytes、旧 index metadata 和冻结代码 SHA 未变；没有 patch sealed public JSON 或覆盖原快照。

| GCC 绑定证据 | SHA-256／bytes |
| --- | --- |
| `validation-binding-receipt.json` | `e4594dd64fe3fd7f72e47dd4262f01a1afc6841b1c23c8f89d2a20a8632e33da`／5,300 |
| `registration-fields.json` | `e3faf39d1b943eabd42db1feda8845f60fd9280c1dac3f529138f9cd4cdf9fea`／647 |
| `actual-java-public-projection.json` 的文件 bytes | `f0f42df3b3d96ed7a77620b9e5161fbbf5bbd9f1ec59b04122f75e34c09b7b85`／1,503,684 |
| public registry address | `94e151f2d3e4ebfd4442305181c974c52f1bf5d901f23b9df166dda2585176c6` |
| public projection revision | `ee29c411c7496672eca9ba53d857a75abb693e220327fd2525d664363024a6f0` |
| canonical sourceHash | `e8104f25f928b1fe075df0a2002d783328b2657c55c8c4988582b0888324f2cc` |

ReviewedRegistration 的完整七 SHA 已写入 `registration-fields.json`，`module=advisory`。原件 SHA 为 `d7d74db6d9aee390093b9e22bb30dccdbf3c0316d47635922109422278d161e7`，fresh JavaParsedSourceHash 为 `f7d1c7ac63b2468a6438a87d64250f91c7de509da8f8293ddfb398bf33cf01d9`，native source revision 为 `4fc695edd06c98062cb556dca830b9c2d417a50a796878f195fae0a41a2d0cd8`，scope policy 为 `bb1512de4b16bfa2c10f03a8197b0c798c105e3359fc649d35391f6fcb66e3d6`，本次 exact adoption bytes 为 `eb117186abe779a1c720c073d8f305c1d20e64d3675014fe84f3d445ad417d54`，source-only review reference 为 `2fa6e44feb1eef4f2af0cb61f266ccaef849ebc4277d3aa2533a3c25a2a626d0`。

本记录只确认 Python additive publication 和字段交接；后续 Java 内部 register／实际项目绑定结果由 business bridge 的独立收据确认。

## 两份实际 public 来源及模块门控

| 来源 | declared module／eligible modules | actual public 与范围 |
| --- | --- | --- |
| GCC，ID1，role=standard，purpose=reference_standard | vetting／vetting、advisory | 上述 public address；采用物理页 65–70 的 50 个 source-reviewed whole units，原件其余页未由本窄采用分支观察 |
| 实际 2b SCC，ID2，role=tender，purpose=project_document | advisory／vetting、advisory | address `88a6396324a4d3f6b7f4ce4362bde5dee4e1e34fa12340657cb3f67a06005f4b`；SCC17 的 22 个 source-reviewed native 单元；未确认 wholeSccAccepted |

SCC public 原路径为 fresh bridge run 的 `scc-projection-attempt2/actual-SCC-public-projection.json`，309,934 bytes，SHA `2a5dae22b390e9c04648b9620e3977ad9ec7c9d3d01f624a7c6fc77fb0058325`；其 preparation receipt SHA `4ad14facde68ac60f0f34929ca8eabe5c0631a6454fb2fb15ba5cf792f50ee4a`。SCC full adopted-source／original byte replay 与 independently reviewed limitations 由该 preparation receipt 和独立审核记录承担；统一组成不改写这些未知。

既有 `NativeCollectionSourceRegistry._module` 允许 Vetting／Advisory 共用明确 eligible 的来源；`UnifiedCanonicalSourceRegistry.wire` 保留此 gate 并先按独立 PDF markers 分派 actual-CAS PDF 验证，再处理 DOCX／collection 分支。两份来源在新 registry 以 `module=vetting` 实际 cold read 和 `assert_canonical_scope` 通过，不需要改变 producer 的 declared module。模板隔离和范围排除规则未放宽。

## 新 owned 8874：冻结、独立状态与 GET 验证

绝对运行目录：

`D:\R&D\ConSense\.local\state\oer-unified-actual-preparation-20261010\run-221220-f9b662`

从当前工作区复制 132 个 Python／samples 文件，复制前、复制后和 frozen SHA 逐项相等，GET 后再次相等。脚本为 `code/oer_unified_server.py`。actual CAS 复制自 8873 的 shared artifacts，788 个对象／694,530,617 bytes，复制前、复制后和副本逐字相等；这是包括保留原始完整证据的 CAS 大小，不是新来源或正确单元的数量。没有复制旧 canonical registry、projects、索引、Qdrant 或请求 journal。新空 registry 仅从两份实际 public 重新 cold validate／publish，72 parents＝50＋22，全部完整 published parent 精确保留。

配置为 CPU FP32、offline、batch2、短窗口512／overlap64、长结构 embedding2048／结构 rerank2048 strict、无 fixed vector cache，scope SHA 与 8873 相同，`targetModule=vetting`。BGE-M3 与 reranker 的 configured revisions 分别为 `5617a9f61b028005a4858fdac845db406aefb181`／`953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e`；此新服务未实际加载权重、tokenizer 或执行神经推理。

在端口空闲时 Hidden 启动，wrapper PID78944／listener child PID22332，CreationDate UTC 分别为 `2026-10-09T22:13:58.4831420Z`／`2026-10-09T22:13:58.7381310Z`；listener 为 `127.0.0.1:8874`。final process receipt 对 PID、精确 UTC ticks 和冻结 script 路径重核相等；旧 5173／8080／8092／8871／8872／8873 的地址、端口和 PID 与启动前一致。8873 的目标模块／已有模型和 index 未切换，Bonsai 未启用。

最终 successful probe 位于 `health-probe-221516-e33fa9/`。仅 GET：初始 health、GCC canonical、SCC canonical、最终 health。HTTP 返回两份 public 对象、exact bytes 和 projection header 相等；Vetting gate、scope 和 configured algorithms SHA 与 frozen manifest 相等。health 同时具有 scanned PDF、native DOCX、native document collection、native PDF 与 unified capability；本次实际读取的是 GCC native PDF 与 SCC native DOCX 两种来源，不能称其余来源类型已由此 run 实测。OCR、embedding、reranker 前后全部 `loaded=false`；projects／run-evidence 为空，Qdrant 不存在，index／retrieve／LLM 调用均为0。

| 最终证据 | SHA-256／bytes |
| --- | --- |
| `runtime-manifest.json` | `2e651ae8bce5d122c2bbab7c61ad239d20fb6199c37c9c1837951bd1a1d0f0a3`／45,218 |
| `health-probe-221516-e33fa9/health-canonical-receipt.json` | `e97dadbc52825a52dd931361db52752c1de50d167280ea3b5cce7136bbf96451`／4,843 |
| 同目录 `capabilities-receipt.json` | `5fbf0b222d0e8a1af178c403075b1672b8ec9ff3008ddd5b550617098cf532d8`／7,107 |
| 同目录 `health-after-response.json` | `95c1b424072490e8589a3d14359deb6d4b06fdfb9f439655654dd29c6ac3d8ba`／11,148 |
| `process-final-receipt.json` | `91689e249315dd3cd573e73ff108feea55df8c9ca895cbb185f787a03527532d` |
| `final-artifact-ledger.json` | `409b8dbf4708fd7aaba127fa513118d0e9c9e2621286937df5f6b7848fad0e0c`／8,293 |

首个 probe 在 health 和 GCC public 对象／bytes equality 已通过后，因为探针字典大小写敏感读取 HTTP 响应头发生 KeyError；原 helper、原响应和 `health-probe-failure-receipt.json` 保留。新 probe 仅将响应头名称转小写，写入新目录，不改产品源码／freeze／gate。两次 probe 合计6个 GET：失败轮2个，完成轮4个；无 POST。进程收据初次比较被 PowerShell JSON 的 ISO日期→DateTime 自动转换影响，精确 UTC ticks 重核通过，期间没有重启进程或换 PID。失败不能报告成服务或来源不完整。

## 接下来可做与未完成项

此服务已经具备在独立项目中进行下一步 partial multi-source 真实 index／retrieve／业务报告测试的准备身份。执行前固定本 run、两 source revisions、Java binding 与实际配置，不混入旧 source-only 101-window index。后续真实模型 journal、necessary-evidence 集、答案／结构报告评分及引用回读另做收据，不能用本次 health 替代。

GCC 尚非全件采用，SCC 尚非全件接受，跨文件法律引用／修改效果／采用适用性未知。GCC native transport 的 `assetRefs=[]`，SCC visual rendition 仍为 `unavailable_pending_verified_rendition`；此 run 没有前端原图／表格或业务验收。官方8题、正式 Vetting 报告、模型权重微调和 H800 发布均不由本准备记录追认为完成。
