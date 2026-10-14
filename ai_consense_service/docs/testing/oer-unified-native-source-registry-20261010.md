# 同一来源 registry 中的扫描、原生 DOCX、DOCX collection 与原生 PDF

日期：2026-10-10，Asia/Shanghai。依据 [范围规则](../oer-scope-and-rules.md) 和当前原生消费者接口。本实现解决混合来源登记／读取／索引的实际接线断点，不执行来源标注或采用，不启动新 TCP 服务，不修改现有在线 Java、前端、DB、MiniMax 中继、模型或旧状态目录。实际原件和官方题仍需自己的采用及业务验收。

## 实现及原有门控

新增 `tools/vetting_eval/unified_source_registry.py` 和 `oer_unified_server.py`。旧扫描、原生 DOCX、collection、PDF 默认入口及其默认状态保留；只给 collection／PDF app factory 增加可选 registry、runtime 和已有 app 注入接缝，使新入口安装既有路由，不复制 producer、transport、source proof 或检索算法。

一个进程内使用同一 ArtifactStore、registry 根和 backend base。四类来源按严格现有 schema 接受：旧 scan v1、actual-CAS native DOCX v2 单 snapshot、DOCX document collection root、native PDF 独立 public schema。collection 的 root 是唯一登记身份；HTTP snapshot 返回有界 root，batch 路由返回完整 batch；仅内部检索视图 hydrate 全部完整 parents，不能通过 HTTP 将全量内部视图送出。

PDF 类型在宽泛 DOCX `_native_candidate` 前分流。后者会因 `source-reviewed-native-*`、`native-*` block 和 CAS 前缀识别 PDF；直接让两个旧 sibling registry 各自处理所有 parents 会误拒或错误寻址。新 coordinator 分离 PDF parents 与其余 parents，然后复用各自完整原件／CAS／revision locator／完整 parent 顺序门控。PDF 独立信号还在时，删除 producer／storage／version 不能降级到 scan、DOCX 或 collection。

所有公开 root 都先按严格 JSON object、唯一键和固定 16,777,216 bytes 上限读取。非法 collection root `[]`、`null`、字符串不会进入旧 `_wire.get` 导致无控制异常。PDF delegate 的 persisted-index proof 也使用同一严格有界 reader，不能以语义相同但超限的空白根绕过限制。引用、source order、whole-unit／table group、unknown 与既有 cold-CAS 要求保留，没有 validation cache、读时修复、拆表或提高 cap。

新入口复用原生 PDF whole-message middleware，限制请求及完整响应 body，发送 headers 前拒绝超限响应；相关 projection／index／retrieve JSON 路由使用严格唯一键读取。`maximum_message_bytes` 仅可降低到不小于 1024 bytes，不能超过固定上限。普通 scan 资产读取仍保留绑定 source/revision、descriptor、实际 SHA、媒体 magic 的旧路由，完整响应也受该上限约束。

## 模块与运行接线

Vetting／Advisory 可按来源实际 `eligibleModules` 共享读取，索引和检索继续严格匹配 process `targetModule`。标准模板仍保持 Drafting-only，答案和排除文件不能因 registry 统一而进入上下文。统一入口不是允许一个进程冒充两个 module 的捷径。

默认只准备新独立目录 `.local/oer/unified-retrieval-state`，拒绝四个旧默认 state 目录。CLI 默认 `127.0.0.1:8874`；本文没有执行启动。完成对应实际 source adoption／CAS 准备后，在冻结代码与自己的环境中可运行：

```powershell
$env:CONSENSE_OER_TARGET_MODULE = 'advisory'
$env:CONSENSE_RETRIEVAL_DATA = 'D:\R&D\ConSense\.local\oer\owned-unified-advisory-state'
& '.local/oer/venvs/retrieval/Scripts/python.exe' `
  'ai_consense_service/tools/vetting_eval/oer_unified_server.py' `
  --host 127.0.0.1 --port 8874 `
  --scope-manifest '<实际已核scope文件>' --scope-manifest-sha256 '<实际SHA>'
```

示例占位符不是当前部署配置。Java 的全局 `CanonicalEvidenceBackend.baseUrl` 可以指向这一个能够读取四类 source 的 base；`retrievalBaseUrls` 仍按 module 指向 matching process。各 module 自己的 process／state 必须具备相同被选来源的准确 root 和完整 CAS 闭包；不能靠改 URL 自动采用、搬运源或让一个 module 的 index 成为另一个的索引。

## 验证与实际限制

新 `test_oer_unified_server.py` 使用现有 actual authored OOXML／PDF／scan CAS fixture、FastAPI TestClient 与 fake embedding／rerank。一个 ASGI base共同返回四类准确 root、三个完整 collection batches 和旧 scan 原图；同池保留四个 document 的10个完整 parents，实际返回窗口／observed-unit归并后的 hits 和 closure 逐值对照完整 parent payload，不能把 parent 数直接当 hit 数。

测试还核严格 anti-downgrade、缺失非请求 batch、非法根／重复 JSON 键、完整 parent 缺失／倒序、source CAS 漂移前的 disk 恢复拒绝、查询期间 collection 漂移后的结果扣留、PDF persisted 根字节上限、matching Advisory／错误 target、完整请求／响应 cap、算法源码 SHA 和所有旧默认目录隔离。

首轮15项中12项通过，3项失败来自新比较器假设：不存在的 `observerOutput` 键、将 private internal parents 等同 public compact batch、要求10parents各返回一个独立hit。只修比较器，未放宽 admission；原失败保留于忽略目录 `run-053900-first`。随后15项全过，增补两项关键门后，九个消费者模块联合执行105次测试全部通过，0失败／错误／跳过，用时73.508秒；按完整 test ID 去重为88项，包含17项新 mixed tests。17次重复来自旧module暴露导入的base test class，不增加验收分母，前后轮也不相加。

最终日志、14份消费者源码冻结副本和身份清单位于忽略目录 `.local/state/oer-unified-runtime-20261010/run-054600-regression/`。`validation-receipt.json` SHA为 `53582059d71cf682617aae02afa9a6da147dd8045cb35c6ae3dc12bb86203f6e`，原日志SHA为 `054dd62d2af88b49e5dc6396f19eddb020f7472db6388112ff0e1236825f3ad0`。收据collector首轮因Windows CRLF未匹配状态行而拒绝，修其行尾读取后直接使用同一保存日志，未重跑测试或模型；该差异不改产品源码。源码快照为测试结束后逐字复制及复验，不冒称完整环境发布包。

本验证不是 TCP／真实 BGE／M3／Java DB／前端或比赛成绩。仍不支持 native PDF table／AP text采用及原图 serving，不增加 DOCX 原生到renderer的视觉映射，不把未观测页、unknown或准备好的raw source变为已采用。实际 PRE、项目 SCC/SCT、GCC的必要来源闭包、实际 DB ID、registry登记、真实模型调用及官方八题／正式报告评分分别验收。目标H800发布与模型训练也未由本工作执行。
