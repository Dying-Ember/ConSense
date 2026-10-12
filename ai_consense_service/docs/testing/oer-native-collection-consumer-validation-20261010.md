# 原生大文件分批消费者与真实 BGE 验证

日期：2026-10-10。执行者：Codex。此记录区分完整来源传输、软件契约、实际神经调用和赛方业务质量；不将人工契约样本升级为比赛原件 gold。

## 本轮具体变化

单个原生 DOCX 继续只有一个 document root 登记和全局 source/revision。新增 `native_collection_registry.py` 与独立 `oer_native_collection_server.py`；完整采用单元按原序分批，root 与每批都保持 16,777,216 bytes 上限。单个完整表组过大则拒绝，不切开、删空格／嵌套表或提高上限。完整 producer contract 见 [document collection 实施](./oer-native-document-collection-20261010.md)。

Python 内部 `load` 重建完整旧 public 供索引使用；HTTP root 仅返回 bounded root，批次通过固定 `/canonical/{projectionSha256}/batches/{ordinal}` 返回。实际 UTF-8 bytes 与根 descriptor 的 SHA/byteCount 相同。`/index-canonical` 只传一个 root SHA，不能把批次分别登记成几个文件。`/project/native-collection` 只接收实际 CAS internal descriptor，不接受 caller URL／任意本机路径。

每次读取 root、任何一批、已持久化索引及 query 后都冷读完整来源 CAS、reference/adoption 和所有批次，精确重建再比对。缺失 CAS 不补写，删除 convenience native markers 不变成 legacy。原件、编辑态、UTF16、blank/nested topology、跨批 parent 关系和 unknowns 保留。这个实现没有 validation cache；真实 PRE 的重复冷读成本仍需测量。

Java root 登记、完整 chunks、pointer 定位和 retrieval 接入见 [Java collection 实施](./oer-native-collection-java-20261010.md)。DB 保存唯一 root snapshot，`OerBusinessEvidence.load` 对 collection 通过 service 读取完整批次，避免把无 chunks 的 root 当空 corpus。旧 scan/native 服务代码、target、状态和在线前端不因本轮 Python 新入口改变。

## 契约与独立审查

root 最终统一 Python 验证：**148 tests，0 failure/error/skip，exit 0，所列源码无前后漂移**。receipt：

`.local/state/oer-native-collection-consumer-20261010/run-031745-655301/validation-receipt.json`

SHA：`e428b4433a2c5cf08946561f58c4616248e92e9bec38d60aa776dc245d948f1c`。

该轮包含旧 scan/native/CAS/来源门控回归、新 collection producer 和 registry/HTTP 检查。模型是 doubles，数字不与独立 producer 的 91 项、较早 115 项或 Java 重叠运行相加。

独立审查第一次实测发现：私有 root 文件是合法 JSON `[]` 时，GET 返回 500。改成严格 JSON object／重复 key／nonfinite 拒绝后，同一输入返回受控 404。publication 的完整 receipt wrapper 也计入响应 bytes 上限，保持旧 request journal 接收普通 JSON value。

修后独立 **16 probes** 全通过，receipt：

`.local/state/oer-native-collection-independent-audit-20261010/run-031709-a4966c/audit-receipt.json`

SHA：`647d43a80950585d5afdabfe380c04ffba221ec5c619c193829cd86a09802f3b`。正常 POST journal/HTTP JSON 一致，另有 `post-journal-and-drift-receipt.json`。初版 finding 和 root 自己的短 TEMP 路径比较、Response/journal 不兼容失败输出均保留，不能重写为第一轮全过。

Java另有独立只读17个探针，未发现真实业务闭包失败；收据`.local/state/oer-native-collection-java-readonly-audit-20261010/run-033022-696774/audit-receipt.json`，SHA `7f970921bf0b5c5f101a86694bc1d0a7d6ccb9d39db9e01d6fc9115d176f19fd`。实际root/parents/targetbatch/unknown一致；foreign document、掉批/换批、cap提升、人类确认提升、root冒充v2拒绝；实际私人CAS缺末批后读取首批拒绝且不repair。源码与118-test复制checkout一致，前后无drift。

验证职责仍明确：compact metadata不是完整manifest，Java本地wire检查不自行重建完整manifest/canonical commitment。两个人为重seal的wire（metadata.notes或sourceOrder999变化）可过局部结构检查，但同一root在实际Python全CAS重建中均因derived payload mismatch拒绝。生产service/client/chunks都重新访问这个authoritative registry，没有发现只靠局部seal跳过实际CAS的路径；不能在未来以本地validator替换该来源证明，亦不据此本轮追加无实际缺口支持的重构。

## 独立进程与真实 BGE

独立地址：`http://127.0.0.1:8871`；target module=advisory；CPU FP32；独立 frozen Python 副本及 state：

`.local/state/oer-native-collection-runtime-20261010/run-031923-628757/`

`runtime-manifest.json` 保存源码、配方、scope、实际 CAS 及人工 fixture 身份，`process-receipt.json` 保存 wrapper/child process 创建时间与脚本路径。后台以 Hidden window 启动；8080/5173/8868/8092、既有 18081/8869/5174 未重启或替换。本服务不是正式比赛项目，也未成为线上前端默认 backend。

首个 fixture 的 scope 是人工契约使用的 `a*64`，真实 `/index-canonical` 正确返回 422 `PROJECTION_SCOPE_POLICY_MISMATCH`，未启动模型。随后显式重新投影人工采用的 scope，原 source/reference、units、quality 均不变，保留首轮失败与旧 root。修订记录：

`run-031923-628757/scope-retest-032059-148486/runtime-manifest.json`

修订 root seal：`3c27743c54d34d52f8c6a318a1d7301106b66c0d4fafbe04305ab0f041a0a489`。真实 scope SHA：`bb1512de4b16bfa2c10f03a8197b0c798c105e3359fc649d35391f6fcb66e3d6`。

实际 HTTP 结果：

| 操作 | 结果与证据 |
| --- | --- |
| GET root/3 batches | 全部 200；root 18,024 bytes，批次 10,405/57,603/9,114 bytes，实际 SHA 与 descriptor 相同 |
| index-canonical | 200；3 parents、5 vector points、5 encoded windows；scope 保留全部输入，没有过滤；短粒度覆盖所有原文本范围 |
| retrieve | 200；2 ranked hits、2 observed source units；每 hit payload 和每返回 member 与保存的完整批次逐字段相同 |
| 模型 | BGE-M3 revision `5617a9f61b028005a4858fdac845db406aefb181`、reranker revision `953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e` 实际 CPU FP32 loaded；OCR 未加载 |

client wall time：索引 8.705 秒，检索 3.350 秒。前者包括首次 tokenizer/model ready 和 HTTP 工作，后者含 reranker 首次加载，不能用于估算完整 PRE／生产吞吐或 H800 性能。实际 source/window/model/pool 事件留在同进程 `retrieval-state/run-evidence/`。

收集脚本在两次成功 HTTP 后把 index 的 `signature` 错写成 `indexSignature`，保存了失败 receipt。没有因此重跑推理；独立离线验证保存响应并再读 health，按正确键确认相同 signature 与完整 payload。最终验证：

`run-031923-628757/scope-retest-032059-148486/post-call-validation-032225-abd39f/validation-receipt.json`

SHA：`fa9989c516c1564417859e40b1e017b35f05e5c7755534fe03208be139fe7ad0`。该验证 `newInferenceCallsByValidator=0`。原先失败的 collector 状态保持原样。

随后实际 Java 窄桥通过：最终复制 classes 的 `CanonicalEvidenceBackend` 经真实 HTTP 读取 root＋三批，`NativeDocumentCollectionContract` 核 SHA/bytes/seal/完整原序与 unknown；两个保存 BGE hits 及 source-unit closure 与 Java 完整 parents 一致。新的 authored index key 使用实际 `OerCanonicalRetrievalClient.index/retrieve`，返回两 hits，warm index/retrieve 分别约0.718/0.784秒。receipt为`.local/state/oer-native-collection-java-20261010/java-http-live-032410/receipt.json`，SHA `fc0b873ce3a453c04c5a6e6354ece260404a1738534d02241a6db25eafe7e20e`。这仍是无业务DB、无LLM、无正式来源采用的协议窄桥；不将它算正式Vetting/Advisory业务验收。

Java最终统一复制checkout为12 suites/118 tests，0fail/error/skip/source drift；receipt为`.local/state/oer-native-collection-java-20261010/run-032236-b4f88e/validation-receipt-attempt1.json`，SHA `d4d94f72d04f5fd118a5d63dbe1689d7b4349583e45ab76a3be711d22bcffeed`。包括root-only binding、业务Session完整载入与revision失效；HTTP stub和LLM doubles的边界见Java文档。

又对**本轮自行创建的8871**执行真实进程重启：先逐PID核executable/创建时间/脚本，保存旧process receipt，再仅停止匹配进程并以相同frozen代码/state/config重新Hidden启动。新child PID为116616（本轮结束快照），wrapper8364。重启后health显示模型未加载，直接查询重启前既有磁盘index，未POST重建index；返回200，index signature、两个hit身份/完整payload以及sourceUnits与重启前完全相同，实际BGE重新冷加载。证据：

`run-031923-628757/restart-032720-713071/validation-receipt.json`

SHA：`46fbb6c7cc7d6165e02f366aaf0de471652216f766a8e58abbcb6a84021e5316`。该收据保存前后process身份及输入SHA，`indexRebuilt=false`。这是独立Windows进程／Qdrant／metadata恢复验证，不是整机开机、正式业务DB、Linux发布或H800验收。既有七个在线监听的PID仍与本轮开始一致。

## 官方来源准备与下一门槛

实际 2b SCT、SCC、G305 已独立导出/capture，fresh CAS 和完整 ZIP/XML/token/table 核对通过，原件哈希未改。准备目录：

`.local/state/oer-official-native-prep-20261010/run-030851-3a569c/`

`preparation-receipt.json` SHA `a105826c6c56e2ca0fa52f51f303c44e53d19084903f8bf30dca70cb0dab5a07`。SCT/SCC/G305 分别保全 6,495/6,185/174 nodes、44/3/1 tables；计数来自独立 `source-xml-audit-receipt.json`，不是采用单元／条款正确数量。导航记录准确 part/path、四通道、空段及 literal clause hints；不推断自动编号、effective editing、法律适用或全文语义接受，也没读取2d/2e答案。

完整 PRE 仍没有实际 reviewed reference/adoption、正式 documentId 或全文 collection 注册。既有 139,376,543 bytes 只为 Parts/topology 下界；最大完整表组 8,326,637 bytes 也不证明最终 chunk 能装下。原件采用、完整 source coverage、真实 Java/DB/前端、SL 完整表组/Q11、原生 PDF 联合恢复、参数残差/必要训练、Linux冻结包与H800演练继续处理。

赛方八题 Advisory 实际执行数仍 **0**；正式 Vetting 结构化报告未执行/评分。执行顺序和独立评价口径保持 [官方业务验收计划](./oer-official-business-acceptance-plan-20261010.md)：先准备并冻结完整来源及配方，再真实生成、最后答案隔离离线评分。当前没有权重训练，不以通路验证宣称“微调已完成”。
