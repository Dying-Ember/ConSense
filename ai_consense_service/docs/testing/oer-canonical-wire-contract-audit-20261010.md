# Canonical Java wire 独立契约审计

日期：2026-10-10，Asia/Shanghai。先读取 [OER 已确认范围与规则](../oer-scope-and-rules.md) 及 [实施交接](../HANDOFF-ocr-embedding-rerank-20261008.md)。本轮独立审查新 `OerCanonicalRetrievalClient`，使用匿名 wire、已保存的真实 Python HTTP responses 和纯 selector 制作的补充契约用例。生产代码由主线程维护，审计线程未修改它；没有新 HTTP、模型、数据库、浏览器操作、服务重启、原件／索引变更。

这是下述固定基线的审计与离线重放，不将历史 [Java 接通缺口审计](./oer-canonical-java-integration-audit-20261010.md) 的旧状态覆盖为当前状态，也不把本次源码进展表述为在线部署。

## 基线、方法与证据

| 项目 | 固定身份／说明 |
| --- | --- |
| 初始 Java client | `src/main/java/com/consense/service/vetting/OerCanonicalRetrievalClient.java`，SHA-256 `1c286c446060000a434248c6f94623638df1fb7718de7804543c5ac4783c6b9f` |
| 模块 URL 配置类 | `src/main/java/com/consense/config/CanonicalEvidenceProperties.java`，SHA-256 `8d334037fb028c5252b7fd1d9531ee121d29950ece7d8ea995a0d3a885e15b86` |
| 匿名 wire | `src/test/resources/vetting/authored_canonical_wire.json`，SHA-256 `0ab054fac6078fb985688e950deabacacce537b13e9857a107f683dce482d46a`；声明 authored、没有真实原件／模型质量意义 |
| 已保存 HTTP 集合 | 根目录 `.local/state/oer-implementation-20261009/canonical-integration/http-development-20261009T162533Z/` 和 `http-development-20261009T163014Z/` |
| 本轮独立证据 | 根目录 `.local/state/oer-canonical-wire-audit-20261010/`，原始基线源码另存 `frozen/` 和 `baseline/frozen/` |
| 完整证据清单 | `python-wire-receipt.json` 保存每份读入原件投影／response 的路径及 SHA；`cases.json` 保存全部契约输入和预期行为；`baseline/run-receipt.json` 保存实际编译／执行状态和源码 SHA |

测试编译完整、未改写的 client 和配置类到独立目录，用既有隔离 Java 验证目录 `.local/state/oer-canonical-java-20261010/run-003612-9e8a1b/target/classes/` 及本机 Maven jars 提供依赖。当前 `VettingRetrievalHealth.java` 与该依赖目录的源码 SHA 均为 `0b91c6537b883165cce1f64b1983f9221615b4a4de3b3e91b5280598bc3eca76`；`JsonUtils.java` 两处也一致，均为 `c165bce6c926a0a25307844e574d6ea0247c4c2838e27560f53a5c52e2a3f6d3`。不是把旧 client class 当成本轮源码验证。

`WireAudit.java` 通过反射调用实际 client 的 `mapped`／`validateUnits`，保持输入 JSON；`LifecycleAudit.java` 调用实际 client 的 index/retrieve，用覆写 `get/postJson` 的纯内存 HTTP double。该 double 只返回固定 JSON、记录模拟调用，不连接任何端口。生命周期 health 以现有 v4 fixture 补入所需 OER 声明，是合成契约输入，不冒称本轮 live health。

Python 仅加载 `retrieval_source_units.py`／`token_windows.py` 的纯函数，无神经模型或 tokenizer 推理。记录两者实际代码 SHA 于 `python-wire-receipt.json`；未改 Python selector。

## 已保存真实 wire 的复算

两个历史 HTTP 目录中共读取 **13 份**含 `sourceUnits` 的 response，分别是第一轮 10 份、第二轮 3 份；共 **39 个**返回单元，每份 3 个。数量来自 `python-wire-receipt.json.recomputedSavedUnits` 的文件和 unit 行，所有状态均为 `complete_observed_unit`。

对每个 seed，用同一次 HTTP 保存的 GCT／SL projection chunks 重新调用 Python `SourceUnits.resolve`，除附加的 `originalRerankOrdinal/seedScore` 外逐字段对照：39/39 完全一致，包括 unitId、source identity、全部 members、observedRanges、位置、unknown／quality 声明及原 payload。基线 Java 对这 13 份 response 的 mapping／unit 校验全部接受。匿名两页表格 wire 也接受，astral emoji `😀` 的 UTF-16 span 没有误按一个 code unit 计算。

这些 response 当时执行过真实模型及 HTTP；**本轮只是保存数据的离线重放**。本轮没有重新访问当时端点，也没有验证那些 development document IDs 已绑定到当前 Java 数字数据库 ID、当前项目或在线 adopted snapshots。因此不能据 13/13 宣称 Java `/index-canonical` → 实际业务 current binding → 前端已端到端接受。

## 复现的两类问题

### 1. 非 clause closure 把成员额外 block 变为新的范围

`nonclause-member-extra-block` 纯 Python 用例：seed 只包含 block A 的 0–3 UTF-16；另一个 canonical parent 包含 A 的 3–6 及独立 block B。Python 保留这个完整成员的 payload，但 `blockIds/observedRanges` 只以最初 seed 的 A 为范围，不跟随派生成员的 B 作新 origin。这符合 `derivedMembersCanBecomeOrigins=false`。

初始 Java client 193–201 行遍历 expected members 时把它们所有 Parts 的 blockId 加入 `blocks`，因而要求 B 也出现在 `blockIds/observedRanges`，拒绝 Python 合法返回：

```text
Canonical retrieval rejected: canonical full source closure differs
```

最小修复是保持原 seed block 集合；成员完整 JSON 继续运输，但不扩展起点。clause 分支则使用实际 clause group 的 blocks。不能把 Python 返回扩成递归 closure 来迁就 Java，这会改变已确认的检索语义。

### 2. 仅校验摘要格式和 unknown 形状，未重建可靠性／单元身份

初始 Java 165–176 行检查 unitId 为 SHA 格式、唯一，以及 unknown 分支没有 members、有 reason；没有依据当前 canonical corpus 重建真实单元。复现了四个应拒而被接受的 mutation：

| 用例 | 基线错误行为及影响 |
| --- | --- |
| `forged-unknown-downgrade` | 将完整可靠 clause 改成 unknown、清 members、伪造 reason，仍接受；完整结构证据可被无依据地隐藏 |
| `forged-unit-id` | 将 Python digest 换为任意有效格式的 64 位 SHA，仍接受；身份不绑定实际 origin／structure |
| `duplicate-reliable-unit-forged-id` | 两个已 rerank seed 来自同一可靠 clause，给第二个任意不同 unitId，仍接受；突破可靠结构单元去重，占用额外 topK |
| `forged-cross-clause-complete` | 同一个 source block 横跨不同 clause 时，Python 返回 `source_block_crosses_clause_unit_boundary` unknown；伪造 complete、只运输一个 clause，Java 仍接受 |

最小修复是纯 JSON 实现 Python `SourceUnits.resolve` 的同一语义，并重算 unitId／未知原因，按字段核验 producer 返回的 entire unit：source identity、kind、status、reason、origin、成员／ranges、clause heading、blocks、quality 声明和 digest。unknown 的真实来源状态继续保留，不能强迫 unknown 变成已知。可靠单元按重算身份去重，不相信远端任意 SHA。完整成员仍逐个与当前 canonical JSON 对照，不使用旧 Chunk/Part DTO 损失字段。

## 基线用例统计与正确拒绝

`baseline/java-wire-receipt.json` 共有 **23 个**逐行 probe：13 个保存真实 HTTP response、1 个匿名 fixture、3 个纯 Python 正常／unknown wire、5 个 mutation、1 个非法 UTF-16 mapping。`expectedJavaAcceptance` 与 `asExpected` 可复算预期。

其中 **18/23**与预期一致，5 个偏差对应上述 1 个合法误拒和 4 个伪造误收。不是 18/23 模型准确率。其余明确正确的防护包括：修改 closure member 的原文会以 `canonical closure payload differs` 拒绝；将包含 emoji 的 Parts endOffset 少算一个 UTF-16 code unit 会以 `canonical UTF16 part mapping differs` 拒绝。Python 原本因非零 block 起点或跨 clause boundary 返回的真实 unknown 能正常保留。

## 模块、配方、失败回执及 current binding

`baseline/lifecycle-receipt.json` 包含 **10 个**完整 client 模拟生命周期，每项保存 accepted/error、index receipt 是否仍在、模拟调用增量和 URLs：

| Probe | 结果 |
| --- | --- |
| 没有当前 index receipt | 拒绝，模拟 HTTP 调用 0 |
| 合法 index＋retrieve | 成功；health、index-canonical、health、retrieve 四次均使用 `vetting` 专属 URL，未误用默认资产 baseUrl |
| URL 包含凭据 | 在 HTTP 前拒绝，调用 0 |
| health 声明 advisory、当前调用 vetting | 只读一次模拟 health，index POST 未发出 |
| 成功 index 后重新 index 失败 | 旧 receipt 已清除，不复用过去成功索引收据 |
| Java parsed source hash 变化 | 在新 query HTTP 前拒绝、receipt 清除 |
| snapshot JSON 字节变化而 bytes SHA 不变 | 在新 query HTTP 前拒绝、receipt 清除 |
| index 后 OER algorithm hash 变化 | health 后拒绝，新 query POST 未发出、receipt 清除 |
| query response indexSignature 不符 | 当前调用抛异常，已成功 index receipt 保留 |
| query transport double 抛异常 | 当前调用抛异常，已成功 index receipt 保留 |

后两项保留的是成功 **index** receipt，不是失败 query 的 response；client 没有返回旧结果。本轮记录该实际状态，不将它直接定性为缓存失败回答或新缺陷。每次后续 retrieve 仍重新检查 corpus 和 health，也校验返回 signature。

`CanonicalEvidenceService.currentBindings` 源码使用指定 current selected documents，按每文档最新 adoption 读取并调用 `readBinding/assertCurrent`，不会回退到较旧 stale adoption；原件字节、Java hash、路由和模块仍在该 service 门控。client 本身要求调用方提供这个 current bindings 集合，本轮 synthetic 生命周期只验证所提供对象发生 hash／bytes 变化后的拒绝。没有据此证明真实数据库 current catalog 在远端调用期间的并发变化已端到端验证。

## 修后重放入口与状态

主线程已接收上述精确复现，修复由主线程维护。离线重放命令：

```powershell
& 'C:/Users/Administrator/AppData/Local/Programs/Python/Python312/python.exe' `
  '.local/state/oer-canonical-wire-audit-20261010/run_java_audit.py' 'repaired'
```

该命令保留 `baseline/`，把当前 client、URL 配置及存在时的 `OerCanonicalSourceUnits.java` 复制到独立 `repaired/frozen/` 后编译；使用已封存的 `cases.json` 重放，不需要启动服务或加载模型。`repaired/run-receipt.json` 记录真实 SHA 和三个 exit codes。

2026-10-10 00:56:37（Asia/Shanghai）修后独立重放实际完成：编译、wire runner、生命周期 runner 的 exit codes 均为 **0**。client SHA 为 `d056a67af34ecc97344c8aa4f50bcb69527f31448fb95b707fae2cb62a588df6`，新 pure JSON `OerCanonicalSourceUnits.java` SHA 为 `8a06e595e079e09402509c1c21e66fe8772e15433beaa3b918ce151db21df2c9`；配置类 SHA 与基线相同。

**23/23 wire probes 与明确预期一致**：17 个真实／匿名／纯 selector 合法或真实 unknown 输入接受，5 个 mutation 加 1 个非法 UTF-16 mapping 输入拒绝。基线的五个偏差均已修复，13 份保存真实 HTTP response 继续全部接受。10 个 lifecycle 的结果 JSON 与基线逐字节一致，未改变模块 URL、配方／来源变更拒绝或失败 index 收据处理。这是该独立 frozen 输入集的契约结果，不是模型准确率或 live Java／前端验收。

| 独立证据文件（相对根目录 `.local/state/oer-canonical-wire-audit-20261010/`） | SHA-256 |
| --- | --- |
| `cases.json` | `50b275ab8e82344f27aa54520306ea55a4b1aa0b3def92b1f74c6ad642372b72` |
| `python-wire-receipt.json` | `2b09a254b27771456afae8c51b8ba463d10cb5f539d69c83eafe4ef7844b0f3e` |
| `baseline/java-wire-receipt.json` | `b78826c976358663a318433c9b04489792081225ea540ed7c50abae51ce37203` |
| `baseline/lifecycle-receipt.json` | `c1b263ba79659fc3b513cbf1e300a19936ac036a769a9b74435e59b424a4ccc8` |
| `repaired/java-wire-receipt.json` | `67f927b3a80ac40a6cb3600b031340bca7e8c5221746bd9a833a2d524d2751c6` |
| `repaired/lifecycle-receipt.json` | `c1b263ba79659fc3b513cbf1e300a19936ac036a769a9b74435e59b424a4ccc8` |
| `repaired/run-receipt.json` | `b0c7d617228236255188db24a3daf59873f9e1ddc5c2aef3259089abca8a849a` |

## 验证限制

本次证明的是保存 wire 的数据兼容与特定门控行为，不证明 OCR 逐字正确、parser 来源采用已接受、候选召回／排序质量、整库 clause 完整、当前 live profile、模型服务资源与延迟、前端图表回取或 H800 发布通过。真实保存 39 单元均是 complete 状态；unknown 覆盖来自明确的纯函数补充用例。完整原件图表资产字节及 Java proxy 本轮没有重新 HTTP 读取。

早期重放辅助脚本曾在 root 修改 client 的过程中捕获新 delegate 而尚未包含新类，编译失败；其 stderr 在 GBK 控制台打印还触发了 UnicodeEncodeError。原始失败日志保留于本机独立目录；随后用固定初始源码和 UTF-8 输出完成 `baseline/`，不是绕过生产代码编译失败。本机 dependency classes 的复用及 synthetic HTTP double 是明确的测试限制，不能包装为新完整 Maven／真实网络验收。
