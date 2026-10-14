# 2026-10-12 21:00 固定资料开发基准

05 开发基准已在北京时间 20:54 完成实际入库、来源回取与不可变冻结，提前满足用户的 21:00 硬截止。原 04 冻结基准保留供回退。这里记录当前可复现的开发起点；最终原件质量、全库检索质量、Vetting／Advisory 业务与 K8s／H800 验收均未完成。

## 范围与当前成果

| 固定来源 | 所需原件页位 | 当前范围说明 |
| --- | ---: | --- |
| GCC | 74 | 完整版本及已保存观察正文／条款 |
| GCT | 2 | 本地当前参考版本 |
| SL 技术部分 | 3,174 | 151 个技术 worksections、7 个技术附录、21 个必要技术 shared 单元 |
| 合计 | 3,250 | 范围保全计数，不是正确解析页数 |

181 是固定来源的解析输入单元数，其中 SL 为 179 个派生单元，不代表 181 份不同原始文件。18 个解析任务家族已经有完整输入基线，但动态项目资料不因此全部进入这个固定标准库。

05 保存 19,371 个父记录：18,615 个 native 观察父、754 个完整保存的物理表父，以及两张新增的完整 HTML 逻辑表父。实际 CUDA FP16 编码 20,700 个 token windows，FP32 归一化及向量存储。模型窗上限 512、重叠 64；检索返回完整观察父及其 parts，不以短窗代替完整来源正文。这些模型窗参数与业务 LLM 的总上下文大小不同。

新增表来自 SL 原物理页 1312、1330，共 46 个行 parts 和 276 个来源 origins。它们通过本次完整 HTML 声明格／字面有限源评，两页此前没有已保存 physical table 资产。05 逐字节保留 04 的 19,369 行前缀，只追加这两个完整表通道；没有逐格融合或替换 native 正文。

排除范围保持：SL 非技术章节、BQ／SOR／General Summary、已排除的非 PRE 技术 PSS、未填 2a NTT／SCT／SCC 模板及 2d／2e 答案材料。未填模板仅用于 Drafting；项目中实际上传的 NTT／SCT／SCC 按其真实来源身份处理。

## 实际回取与质量边界

在新 05 签名上执行了 1 次 GET health 和 8 次 POST retrieve，实际 exit 0，无重试、LLM 调用或数据库写入：

- 既有 GCC／GCT／SL 各两题，共六题：目标父仍排首位，完整正文／parts／原页／来源身份各 6/6 保持。
- 新 1312 表排第 5，新 1330 表排第 2，两者都在 raw top-10 中；完整表父、完整 HTML 资产引用及 132／144 origins 回取各 2/2。
- 两表不是 top-1，不能将八题表述为 8/8 top-1。题目基于已封来源内容，非 blind、非全库准确率或业务答案成绩。

GCC 第 1 页、SL 第 1329、3437、3438 页的选定 normal 解析失败标记保持。完整 COM11 provider 已保存 55 页输出，但正式 normal wrapper 在截止时失败；后续 CPU 回收只证明保存物料闭合，不修改 `normalWorkerQualified=false`。五张无框表的有限源评分为声明格结构 654/672、字面 418/672、完整字面表 2/5；其余三张残差保留。cell 几何、法律归属、表外附注及跨页关系仍未验收。

754 个 physical table 父是完整保存资产数，不是 754 张正确源表。所有原件范围未做完整独立 gold 评分，不提供异构合成的总体准确率。457 个辅助 native 父可能引入检索噪声；完整法律层级及条件闭包仍有未知。

## 当前检索入口

服务：`http://127.0.0.1:8876`，单 worker。固定 projectId：`fixed-best-baseline-20261012-2100`。

签名：`b0a7bc46bb241f9acbace0bab75a648c83db3ae339c79e68c4d689f1b635f64b`。

契约：`source-bound-window-points-full-parent-results-v1`，signatureVersion 3。这是独立开发基准入口，不能冒充当前 Java canonical 的 dual v2／signature4。Java／前端的 Vetting／Advisory 接缝仍待实现，单独改 canonical URL 不构成接入。

只读调用示例：

```powershell
$fixedQuery = @{
    projectId = 'fixed-best-baseline-20261012-2100'
    query = 'What happens to payment obligations when a special risk terminates the contract?'
    role = 'standard'
    candidates = 50
    limit = 10
} | ConvertTo-Json

Invoke-RestMethod -Uri 'http://127.0.0.1:8876/retrieve' `
    -Method Post -ContentType 'application/json' -Body $fixedQuery
```

返回的完整检索审计 JSON 不是 LLM 输入。后续共用业务 client 只传选定完整条款／表组、必要条件和来源标识；原图、坐标、raw、完整审计留在来源回取层。标准参考不能单独证明当前项目采用了某版本或存在缺陷，项目采用／修订依据必须另外取得。

## 冻结与证据位置

实际固定库 collection：`project_c18c9b7514b1c830_b0a7bc46bb241f9a`。

库状态：`C:/Users/Administrator/.codex/local-state/consense-oer/fixed-best-baseline-20261012/freeze-2100-05/state`。Qdrant Local 由当前检索服务单独持有，其他进程不能直接打开这个目录。

不可变冻结清单：`C:/Users/Administrator/.codex/local-state/consense-oer/fixed-best-baseline-20261012/immutable-freeze-05-01/frozen-baseline-manifest.json`，SHA256 `deb87c356c2a724f71d8bc316d86c4272fa9ebe014fb2bedf4c18de29a425aab`。它绑定三份原件、完整输入计划、选定解析快照、模型 publication、源码、语料、实际 index receipt、向量完整性及八题实际回取，并保存未知项。

实际索引收据：`freeze-2100-05/actual-index-root-01/actual-index-receipt.json`，SHA256 `ecefab5ffac98944d2bc11fa2b8c7317b54115792c6ec19a5c4bc822ccd7985f`，实际 20,700 点，wall 243.165 秒，writer 已关闭。

实际八题摘要：`C:/Users/Administrator/.codex/local-state/consense-oer/fixed-baseline-real-source-query-evaluation-20261012/actual-eight-query-05-01/compact-eight-query-source-regression.json`，SHA256 `476887abbd1dea3de60f6def2193707fe6f1e0d6dc550ae6be24bfc584f5725c`。

本地 client 身份配置位于 `.local/state/oer-deadline-baseline-20261012/selected-baseline-client.json`；这不是已被 Java 读取的在线配置。代码、规则及当前进展分别通过 [范围规则](./oer-scope-and-rules.md)、[实施交接](./HANDOFF-ocr-embedding-rerank-20261008.md) 与 [HTML 台账](http://127.0.0.1:5182/#document-scores) 维护。

原件、模型、密钥、数据库及真实运行证据保留在忽略的本地目录。接下来实现两个业务模块共用的固定标准参考入口、完整来源组预算和引用回取，再执行真实业务问题与结构化 Vetting 报告；不把本次冻结当作这些验收已经完成。
