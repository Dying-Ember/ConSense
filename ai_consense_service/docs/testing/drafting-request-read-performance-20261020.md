# Drafting 同请求来源核验性能修复

2026-10-20；本轮只调整读取时重复核验的次数，不改变原件、来源身份、人工采用或写入门槛。

`DraftTemplateGraphService.variables()` 原来对每个字段调用 `stale(revision)`。同一份图谱的 24 个字段因此把相同原件重新读取、准备和核验 24 次。`current()` 分别检查当前版和草稿版时，也会重复准备它们共有的原件。真实 HTTP 基线中，三个固定 DOCX 原件的八字段读取耗时 75.516 秒；当前版与草稿版读取耗时 26.484 秒。

修复把一次 `variables()` 的图谱来源状态计算一次，再传给各字段响应。一次 `current()` 使用局部 `Map<Long, JsonNode>`，复用该次调用已经核验的当前来源元数据。每个 revision 仍逐项比较 `sourceSha256`、`sourceHash`、`analysisRevision`、`analysisInputSha256`、`analysisSha256`、`evidenceStatus`，并独立校验该 revision 的归档字节。写入、确认、发现和模型调用门禁各自重新核验，下一次请求也重新读取。没有全局缓存、线程缓存或跨请求缓存。

## 验证及原始失败

全部证据保存在忽略目录 `.local/e2e-20261020/`，汇总和文件散列为 `drafting/round05-read-verification-summary.json`。相对已冻结 Round04 的本轮完整差异为 `drafting/round05-request-read-diff.patch`。

| 运行 | 固定结果 | 解释及证据 |
|---|---|---|
| RED | 0 执行；测试编译失败 | 新测试最初尝试修改不可变 revision；原编译日志保留于 `build/round05-red-maven.log`。 |
| RED02 | 4 执行；2 fail、2 error | 两个预期性能失败，另外两个新夹具漏填人工采用理由；保留全部错误。 |
| RED03 | 4 执行；2 pass、2 fail、0 error | 干净性能红：24 字段实际 archive 24 次而非 1 次；同来源 current/draft 实际 2 次而非 1 次。原件／归档变更及模型中途变更保护均已通过。 |
| GREEN | 36 执行；35 pass、1 fail | 生产行为正确核验 3 份 revision manifest；新测试误写为 2。夹具包含 current 1 份和 draft 2 份，已据固定分母修正；生产代码没有为此改动。 |
| GREEN02 | 36 pass、0 fail、0 error、0 skip；BUILD SUCCESS | 冻结快照 SHA256 `4653aaae0a6839f5b0d33684ea4f9c4b8a562c11b7fb0b67eddcb1dad891cd06`。日志 `build/round05-green02-maven.log`，JUnit XML 在同名 project 的独立 target 中。 |

新增四项测试覆盖：两次独立读取各只核验一次共有来源；下一请求发现原件或归档字节变化并保留旧采用值、阻止写入和模型；current/draft 逐 revision 保留所有 manifest 字节核验、六身份字段差异及新增来源；模型执行途中原件变化后，已采用值保留但标记待审、有效值排除、重新采用被阻止。全类原有 32 项也通过。另由 Vetting agent 只读复核本轮差异，0 Must；该复核不替代测试或真实 HTTP 验收。

## 同源实际 HTTP 比较

专用项目 `e2e-drafting-read-performance-20261020-round05`，仅八个人工标明的测试字段、无编辑目标、无模型调用。首字段测试字面值带明确人工理由，其余七字段保持未采用。基线 14/14 HTTP 检查通过；三份存储原件下载后与以下固定原件逐字节相同：

| 原件 | SHA256 |
|---|---|
| NTT | `60bd8796aa828863c7edfe8617b7a5c56ee383afe8ba87a089e820f0fd9a982c` |
| SCT | `7def01d62e07b35dd86beeff995e0e37d73ce18adca2746e2a6f6d9bafb5aaa4` |
| SCC | `9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208` |

固定 current `5af2a8a1-c641-4384-b439-048011fcf393`、draft `244913b9-218f-412d-a1ce-ddd55ee0b576`。基线读取结果：

| 路径 | 基线耗时 | Round05 实际耗时 | 响应字节 | 前后相同的响应 SHA256 |
|---|---:|---:|---:|---|
| `/variables` | 75.516 s | 12.046 s | 3,691 | `adb0882802a6bc03c21796d42fa992bafdcc44b640025fc7c0739ee39cf747ba` |
| `/graph` | 26.484 s | 14.641 s | 41,112 | `9953311303cd5d918109c9648918f9d8e8b44821c1554eb5141f6923e4baff07` |

基线证据 `drafting/e2e-drafting-read-performance-20261020-round05-baseline/summary.json`。root 统一构建并安装 Round05 后，在同项目、同 graph revision、同原件上执行只读复验，5/5 HTTP 检查通过；两个完整响应字节及 SHA 全同，三份原件下载字节全同，首字段采用值和理由保留，其余七字段未采用。证据 `drafting/e2e-drafting-read-performance-20261020-round05-after/summary.json`。本次观察的变量读取倍率为 6.269，图谱读取倍率为 1.809；实际计时受本机并行测试影响，只能作为本次观察值。H2 契约检查、真实本机 HTTP 读取和真实项目业务验收分别报告。
