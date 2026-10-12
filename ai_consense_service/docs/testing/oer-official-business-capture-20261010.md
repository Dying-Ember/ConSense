# 官方业务首次输出收集器

日期：2026-10-10，Asia/Shanghai。入口为 `tools/vetting_eval/official_business_capture.py`，只收集当前真实业务输出；法律答案、图和报告的评分继续由隔离 offline grader 在生成结束后执行。依据 [范围规则](../oer-scope-and-rules.md)、[官方计划](./oer-official-business-acceptance-plan-20261010.md) 和 [就绪核查](./oer-official-output-readiness-audit-20261010.md)。本次只新增脚本、契约测试和本说明，没有发起 HTTP／模型、改变线上服务／数据库或实现流程图产品能力。

## 先准备精确冻结清单

收集器不会上传、登记采用、重建索引、清空历史或写审阅状态。须先由实际来源准备流程建立隔离持久项目，并按真实 DB IDs 完成 canonical 绑定和对应模块索引。最小 `expected-sources.json` 形状如下；示例值必须换成 actual ID、文件名、Java parsed hash 和 actual index status，不能拿示例值运行。

```json
{
  "projectId": "official-q16-source-90602e8f-85b8-4e1a-ad61-2625c654115e",
  "sourceScope": "fullset",
  "documents": [
    {"documentId": "1", "fileName": "ACTUAL_GCC_FILENAME.pdf", "javaParsedSourceHash": "ACTUAL_64_HEX_PARSED_HASH"},
    {"documentId": "2", "fileName": "ACTUAL_SCC_FILENAME.docx", "javaParsedSourceHash": "ACTUAL_64_HEX_PARSED_HASH"}
  ],
  "adviceIndex": {
    "indexKey": "ACTUAL_STATUS_INDEX_KEY",
    "indexSignature": "ACTUAL_STATUS_INDEX_SIGNATURE"
  }
}
```

至少两个不同的正整数 documentId；与 live included source catalogue 的完整集合、文件名和 Java parsed sourceHash 完全一致，不只检查“至少找到了这两份”。可附 `sourceRole` 并逐项核 live 返回值。原始字节 SHA、canonical sourceHash、projection、adoption、producer、reviewed manifest 等离线 provenance 可一并放入 document；当前 `/advice/sources` 未返回的字段逐项列为 `offlineProvenanceNotLiveVerified`，不能报告已现场核验。`adviceIndex` 是准备完成后的实际 `/api/advice/{projectId}/index/status?scope=fullset` 的 `indexKey/indexSignature`。收集前和每题后核 canonical、当前 fullset、embedding/vectorReady、非 stale、实际文档计数以及两项 index identity；这个门控代表当前来源绑定／索引身份，不代表采用的法律判断已正确。

Vetting 入口还需相同清单中的实际身份：

```json
{
  "vetting": {
    "sourceSnapshotSha256": "ACTUAL_JOB_SOURCE_SNAPSHOT_SHA256",
    "canonicalCorpusIdentitySha256": "ACTUAL_VETTING_CURRENT_CORPUS_SHA256"
  }
}
```

这两项是现有真实 job／coverage 字段，来自本批实际准备和冻结；不虚构新服务器字段。Vetting 共用 `/advice/sources?scope=fullset` 做当前 ID／parsed hash 目录核查，再核自身 job source snapshot 与 completed coverage canonical identity；不把 Advisory catalogue 声称为 Vetting 私有注册 API。Vetting 不要求 adviceIndex readiness，不借用 Advisory 的索引去运行审查。

## 先运行现有 Q1.6

下列 PowerShell 使用显式 `--execute`、选定 profile/model、frozen JSON SHA 和全新忽略目录。参数默认超时为 2100 秒、Vetting 总等待 7200 秒；超时会保留失败回执，不自动重复发送。实际 root 校准范围和请求预算须在来源准备阶段确认，收集器不伪造 token fit。

```powershell
$capturePython = 'D:\R&D\ConSense\.local\oer\venvs\retrieval\Scripts\python.exe'
$captureScript = 'D:\R&D\ConSense\ai_consense_service\tools\vetting_eval\official_business_capture.py'
$expectedSources = 'D:\R&D\ConSense\.local\state\ACTUAL_READY_RUN\expected-sources.json'
$expectedSha = (Get-FileHash -LiteralPath $expectedSources -Algorithm SHA256).Hash.ToLowerInvariant()
& $capturePython $captureScript advice --execute `
  --base-url http://127.0.0.1:18083 `
  --project-id official-q16-source-90602e8f-85b8-4e1a-ad61-2625c654115e `
  --profile minimax-cn --model MiniMax-M3 `
  --sources-manifest $expectedSources --sources-sha256 $expectedSha `
  --questions 'D:\R&D\ConSense\.local\state\oer-official-acceptance-inventory-20261010\questions-only.json' `
  --questions-sha256 4c4c8b53c235d320b881009c982796a78d5edd2e72aaa712eade95fd53372009 `
  --question-id 1.6 `
  --out 'D:\R&D\ConSense\.local\state\ACTUAL_NEW_Q16_RUN'
```

去掉 `--question-id` 则逐题运行八个唯一题号，每次 `replyToMessageId=null`，各题独立提问；不会把前题答案或参考答案追加到下一题。生成端只读取明确提供、SHA 一致的 expected source JSON 与 question-only JSON，不打开问题清单中的 `sourcePath`，也不加载 2d／2e 原答案或评价标签。题目 flags 必须明确 answers 禁止且八题 ID 唯一；直接传 2d／2e 目录文件会拒绝。

每题仅从已有返回 DTO 核 modelIdentity.profileId／model 和 sourceScope。Q1.6 当前没有 typed flowchart 字段时记 `graphOutputStatus=unsupported`，文字为 `textAssessment=not_scored`；未来有该字段也只记 `present_unvalidated`，不自动认定合法图或正确图。首次 current harness 输出应先保留、分析，再依据实际差距决定后续实现，不能预置参考节点／期限来过题。

## 独立收集 Vetting

用相同显式参数把 mode 改为 `vetting`，提供已补齐 `vetting` 身份的 frozen source manifest、其新 SHA 和新的 `--out`；不需 questions 参数。入口先确认不存在正在进行的 job，调用 `/api/vetting/{projectId}/runs?lang=en`，保存并轮询该 ID。FAILED／CANCELLED／ERROR／timeout 会停止并保留实际状态，不改任务、finding status、review、人工 checklist，也不再次发起 run。

该入口保存 findings（包括空数组）、metrics 和 JSON／DOCX／PDF bytes。顶层 COMPLETED、零 findings 和 semanticTopics partial 都保留，结果统一是 `collected_unscored`；没有“零发现=No comment=质量通过”的推断。当前 `/report.*` 只指最新 job，没有 runId 参数，因此每个导出前后都读取 latest ID、source identity、stale 和 completed corpus identity，并校验 JSON report.job。latest ID 变化则整轮 failed，已收集 bytes 保留。这个前后栅栏不是原子的按 run 地址协议，receipt 明写该限制，不能冒称服务器拥有专属旧 run 导出路由。

## 产物与安全边界

每个请求保存 response 原始 bytes 为 `NNN-label-response.bin`，以及 status、start/end、wall time、byte count、SHA；模型问题请求的 UTF-8 原始 body 另存 `.bin`。这里只保存从实际 Java HTTP 收到的 bytes，不能将其追认为 MiniMax provider 原 wire／token usage。安全响应 headers 仅 `Content-Type/Length/Disposition` 和明确的 request-ID 类；不保存 Authorization、Set-Cookie、API key 或所有 headers。stdout 只输出状态与 receipt 路径，不打印失败 body 或外部异常文本。

`capture-receipt.json` 记录模型身份、来源／index gate、每题 output kind、job／coverage、全部 raw responses 和未现场核验的 provenance。旧输出目录直接拒绝，失败路径也不覆盖旧 receipt。HTTP 529 或其他失败保留第一次返回，不自动重试；服务端内部重试及实际 model wire 另从该冻结服务日志核实。

仅接受显式 localhost／127.0.0.1 的 HTTP Java 端口，拒绝默认 80／8080、带凭据／query／fragment 的 base URL；HTTP redirect 不跟随。源码可以迁移到冻结部署，但本工具本身不是 Linux／H800 部署验收，也不是最终发布包。

## 本轮离线验证

`test_official_business_capture.py` 的最终 **20 个 in-memory transport 契约测试全部通过，0.272 秒，0 失败／错误／跳过**。覆盖首次 Q1.6 unsupported、已有图字段未评分、八题独立、至少两源和完整 ID／hash／index 门控、source 变化、错误模型身份、529 raw bytes／安全 headers／零重试、frozen SHA／答案 flags、旧 run 不覆盖、显式 execute、零发现／partial、active job 拒绝、latest job 在导出前／中变化以及 FAILED 终态保留。Fake DOCX／PDF bytes 只用于 collector 行为测试，不宣称实际文档有效或视觉通过。

收据位于根目录 `.local/state/oer-official-business-capture-20261010/run-073100-contract/validation-receipt.json`，相邻 `unittest.log` SHA `84eef9162ba23acbbb79c5f70376443c5dc89f9ac61288d0d9dfcd335783b4d9`。collector SHA `f86a8e7e4450af80dd7a7f9e6462d51f8db0dbe71a0e7291fc4663dc82339191`，测试 SHA `e42524eb57ada2a3458f1454d76c15d79144c4c1ad4531fcc5061c8309bcc806`。这些验证没有 sockets、真实 HTTP、神经模型、服务或 DB；实际来源准备、首次 Q1.6 和正式多来源报告仍由主任务另行执行并保留 run。
