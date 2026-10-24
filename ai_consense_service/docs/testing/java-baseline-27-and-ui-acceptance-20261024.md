# Java 既有 27 项失败／错误修复与前端验收记录

2026-10-24，Asia/Shanghai。本记录前半部分只覆盖已保存的隔离 Java 基线复现、产品修复和定向回归。原始失败保留；新的通过记录不改写旧票，也不作为全仓、线上服务、原件质量、真实模型或浏览器验收通过。

用户本轮优先清理 Java 既有失败／错误，再通过内嵌浏览器完成纯网页交互验收。此前首轮审计 08／16 的未完成工作已另行保全，本轮不恢复该 stash，不把这批 Java 修复算作相应性能／可移植性事项完成。

## 1. 证据范围和执行隔离

证据根为仓库忽略目录 [`.local/java-baseline-failures-repair-20261024`](../../../.local/java-baseline-failures-repair-20261024/)。本节核对两个独立快照：

- `java-baseline01`：原五个失败 suite 的完整基线；原 27 项由 **26 项 assertion failure＋1 项 error** 组成。
- `java-focused02`：修复后的 15 个相关 suite；其中原五个 suite 保留全部原测试身份，并新增 3 个预算边界参数实例。

启动器为 [run-java-checks.ps1](../../../.local/java-baseline-failures-repair-20261024/run-java-checks.ps1)。它复制 `src`、`tools`、`pom.xml` 到新的任务目录，使用该目录自己的 Java 临时目录和 replay 输出目录，单 JVM `forkCount=0`，离线 Maven、8 GiB 堆及 2 个 active processor。复核没有在产品目录运行 Maven，没有启动真实模型、生产数据库、OCR 或产品服务。

本文件以下数字取自各快照的 `ai_consense_service/target/surefire-reports/TEST-*.xml`，并核对其实际 `testcase` 数量。范围由 suite 和测试身份确定，不用日志中的汇总替代逐项 XML。

## 2. 原五个 suite：62 项到 65 项

| Suite | 原基线：总数／通过／failure／error／skip | 修复后：总数／通过／failure／error／skip | 逐 suite 原始证据 |
| --- | --- | --- | --- |
| `OerSharedNoteAuthorDifferentialTest` | 36／15／20／1／0 | 36／36／0／0／0 | [baseline XML](../../../.local/java-baseline-failures-repair-20261024/java-baseline01/ai_consense_service/target/surefire-reports/TEST-com.consense.service.vetting.OerSharedNoteAuthorDifferentialTest.xml)、[focused XML](../../../.local/java-baseline-failures-repair-20261024/java-focused02/ai_consense_service/target/surefire-reports/TEST-com.consense.service.vetting.OerSharedNoteAuthorDifferentialTest.xml) |
| `VettingCanonicalReportCoverageTest` | 4／1／3／0／0 | 4／4／0／0／0 | [baseline XML](../../../.local/java-baseline-failures-repair-20261024/java-baseline01/ai_consense_service/target/surefire-reports/TEST-com.consense.service.vetting.VettingCanonicalReportCoverageTest.xml)、[focused XML](../../../.local/java-baseline-failures-repair-20261024/java-focused02/ai_consense_service/target/surefire-reports/TEST-com.consense.service.vetting.VettingCanonicalReportCoverageTest.xml) |
| `VettingPacketReportTest` | 1／0／1／0／0 | 4／4／0／0／0 | [baseline XML](../../../.local/java-baseline-failures-repair-20261024/java-baseline01/ai_consense_service/target/surefire-reports/TEST-com.consense.service.vetting.VettingPacketReportTest.xml)、[focused XML](../../../.local/java-baseline-failures-repair-20261024/java-focused02/ai_consense_service/target/surefire-reports/TEST-com.consense.service.vetting.VettingPacketReportTest.xml) |
| `VettingReportWriterTest` | 12／11／1／0／0 | 12／12／0／0／0 | [baseline XML](../../../.local/java-baseline-failures-repair-20261024/java-baseline01/ai_consense_service/target/surefire-reports/TEST-com.consense.service.vetting.VettingReportWriterTest.xml)、[focused XML](../../../.local/java-baseline-failures-repair-20261024/java-focused02/ai_consense_service/target/surefire-reports/TEST-com.consense.service.vetting.VettingReportWriterTest.xml) |
| `VettingSampleReportAlignmentTest` | 9／8／1／0／0 | 9／9／0／0／0 | [baseline XML](../../../.local/java-baseline-failures-repair-20261024/java-baseline01/ai_consense_service/target/surefire-reports/TEST-com.consense.service.vetting.VettingSampleReportAlignmentTest.xml)、[focused XML](../../../.local/java-baseline-failures-repair-20261024/java-focused02/ai_consense_service/target/surefire-reports/TEST-com.consense.service.vetting.VettingSampleReportAlignmentTest.xml) |
| 合计 | **62／35／26／1／0** | **65／65／0／0／0** | 上述五份 XML 分别求和 |

可复算关系：原分母 `36+4+1+12+9=62`；原失败／错误 `20+1+3+1+1+1=27`；修复后分母 `36+4+4+12+9=65`。按 `classname#testcase.name` 比较两个快照，原 62 个测试身份全部保留，无删除或改名。唯一增加的是 `VettingPacketReportTest.inputBudgetLimitationsRemainIndependentOfTheRecordedExecutionResult{String}[1]`、`[2]`、`[3]`，分别对应完成但输入预算未知、完成但预算仅为提供者估计、输出预算耗尽同时输入预算未知。

这些 suite 的相同测试身份不表示断言内容完全未改。第 4 节逐案说明了旧展示预期的适用性变化，保留来源、计数、失败状态和人工记录的正负验证。

以下只读 PowerShell 可重算上述每行，不会重跑测试：

```powershell
$auditRoot = 'D:\R&D\ConSense\.local\java-baseline-failures-repair-20261024'
$suites = @('OerSharedNoteAuthorDifferentialTest','VettingCanonicalReportCoverageTest',
  'VettingPacketReportTest','VettingReportWriterTest','VettingSampleReportAlignmentTest')
foreach ($run in @('java-baseline01','java-focused02')) {
  foreach ($suite in $suites) {
    $file = Join-Path $auditRoot "$run/ai_consense_service/target/surefire-reports/TEST-com.consense.service.vetting.$suite.xml"
    [xml]$report = Get-Content -LiteralPath $file -Raw
    $s = $report.testsuite
    [pscustomobject]@{ run=$run; suite=$suite; tests=[int]$s.tests;
      passed=[int]$s.tests-[int]$s.failures-[int]$s.errors-[int]$s.skipped;
      failures=[int]$s.failures; errors=[int]$s.errors; skipped=[int]$s.skipped;
      actualCases=@($s.testcase).Count }
  }
}
```

## 3. 产品根因与修复

### 3.1 原生来源身份键与范围计算不一致

`OerBusinessEvidence.transportUnit` 在合并已观察层级和直接旁注上下文时，把公开来源身份 `identity(...)` 传给 `blockRange`；而 `blockRange` 按包含原件／投影／修订等字段的 `scopeIdentity(...)` 筛选父项。两种键不相等时没有找到任何原文 part，旧实现仍返回 `endUtf16=0` 并标为零间隙观察。

基线差分 `[2]` 明确记录原期待范围终点为 `211`、`21`、`791`，实际三个终点均为 `0`；证据是第 2 节的 SharedNote baseline XML。修复让调用双方使用同一 `scopeIdentity`，并在没有任何匹配 part 时拒绝 `observed_block_mapping_missing`，不再把缺失来源包装成完整空观察。

新增 `NativePdfMixedSourceUnitsTest.businessUnionUsesTheSameNativeRootKeyAsObservedBlockRanges` 和 `aRangeWithoutAnySourcePartsCannotBecomeACompleteEmptyObservation` 验证完整范围与拒绝空映射。相关 suite 在 focused 中为 8／8 通过，见 [NativePdfMixedSourceUnitsTest XML](../../../.local/java-baseline-failures-repair-20261024/java-focused02/ai_consense_service/target/surefire-reports/TEST-com.consense.service.vetting.NativePdfMixedSourceUnitsTest.xml)。这两项不计入原五个 suite 的 62→65 分母。

### 3.2 保存的旧负例与现行严格来源身份门

SharedNote 第 33 个差分实例 `actual-bad-target-scope` 的原错误为 `Canonical retrieval rejected: source-unit field differs: kind`。其旧冻结 response 早于当前更严格的原生根修订身份门；不应为让旧正向预期通过而放宽来源范围、改写冻结原件或接受含歧义的组。

原 fixture 仍为本地 `actual-shared-source-unit-fixtures.json`，SHA-256 保持 `cda3a7a07579fb7bd0a2e3cd39e20b7daac9f3c21b1c36ac95c018f17dfb4633`；原 33 个 episode 及另外 3 个测试均保留。仅对精确 dataset＋seed 的当前行为单独验证：旧 response 必须被拒绝；当前结果必须为 `kind/status=unknown`、`reason=ambiguous_document_source_identity`，成员、必需成员和范围为空，附属上下文也保持 unknown，不授语义或法律闭包。

当前期望另存为 [shared-note-current-scope-negative.json](../../src/test/resources/oer/canonical/shared-note-current-scope-negative.json)，schema 为 `consense.shared-note-current-strict-root-negative.v1`，字节 SHA-256 为 `fd9dffea2bf809c256c894a1d971a606cbce5b74bd2590fffc760c729610c84c`。测试核该文件身份、原 fixture 身份、精确 dataset／seed、完整当前 resolve 结果与 transport binding；`.gitattributes` 对这份固定哈希 JSON 使用 `-text`，避免换行转换使校验失真。该适配不对任意旧 fixture 推断等价，也不把 unknown 改成完整。

### 3.3 读者报告遗漏覆盖计数与具体未完成原因

报告的读者投影应使用可理解的来源范围与业务状态，但原共享 outline 删除了已保存的片段／字符分母，并把失败和未提交合并成一个数量。普通读者无法从报告看出 `7/19`、`123/777`、3 个待处理请求所对应的 7 个总请求，或具体是输出预算耗尽还是输入预算尚未确认。

`VettingReportWriter` 现在仅在 Appendix II 来源范围附录恢复原计数，明示只描述已记录来源观察，不代表原件完整或实质通过；请求显示待处理数及已知总数，失败和未提交分列。输入预算与执行结果分别投影：已完成结果仍为已完成，输入预算 unknown 仍未知；提供者估计仍只作为估计；失败原因和预算未知可以同时出现。失败原因没有已知人类映射时，只提示查已保存审查记录，不直接拼接 raw enum、error、packet ID 或哈希。

主五栏业务表、引文、人工回复、实际处理说明、补遗决定和报告身份权威逻辑没有因此改动。Word 和 PDF 继续使用同一个 outline；旧二进制报告、历史 job 和数据库记录不在本轮改写。

## 4. 原六个报告失败的旧预期与替代证据

读者展示契约沿用 [读者版式修正范围](vetting-report-reader-layout-20261020.md)；原 canonical 警告来源及字符分母含义见 [覆盖说明历史记录](vetting-canonical-report-coverage-20261010.md)。旧记录按各自阶段读取。

| 原 case | 原断言为何失败／过时 | 本轮保留的正向与负向验证 |
| --- | --- | --- |
| `exportsLocalizeCanonicalLimitsWithoutChangingTheObservedDenominator[en]` | 数字丢失是真实产品问题；要求直出 `canonical`、`UTF16` 的技术措辞与后来的读者映射不一致 | Word 全正文＋表格、PDF 实际提字均保 `123/777`、`7/19`、来源文件、仅已采用观察的范围、字符范围并集、原件完整／未采用区域未知、局部窗口及未入模型未审查；数字只能位于 Appendix II；禁技术术语直出；原 job JSON、警告及计数保持。 |
| 同方法 `[zh-Hant]` | 同上；旧逐字繁中技术说明不再是读者报告原样要求 | 同原分母和边界，验证繁中读者说明及无来源改写。 |
| 同方法 `[zh-Hans]` | 同上；不能用删除或恢复内部术语代替语义保真 | 同原分母和边界，验证简体读者说明及原始记录保持。 |
| `actualWordAndPdfShowAuthorityPendingFailureBudgetAndLegacyUnknown` | raw `partial/completed_empty/scope_unknown_legacy/budget_unknown/output_budget_exhausted` 和 `Global call status` 不应作为读者文字原样直出；具体失败原因和总请求分母仍应保留 | 三语 Word／PDF 均明确来源支持不完整、待处理 3 of 7、失败 1／未提交 1、输出预算耗尽、完整输入预算未确认、历史来源范围未知、空响应不代表合同无问题；禁内部码与技术标题，完整 job JSON 及原枚举逐值保留。 |
| `wordRetainsBothSidesAnchorsReviewStatusAndCoverageWarnings` | 原 `20250101` 只来自 Project 显示字段，run 没有采用身份；恢复该回退会违反身份权威规则 | 两格式均要求身份待确认且不得显示该镜像号；保双方引文、来源位置、baseline 文件、Handled、人审及来源警告；Word 首 Title 样式仍验。 |
| `actualPdfAndWordHaveBusinessTablesBeforeAuditAppendixAndWriteQaFixtures` | `job=null` 时要求 Project 镜像号 `Current-Contract-9` 成为报告身份已过时 | 两格式均要求待确认且禁镜像号和旧样例号；五栏／建议在 Appendix II 前、Appendix I 顺序、不打印 Run ID 继续验证。 |

追加预算独审的三个 case 逐一保留执行与预算的独立含义；每个 case 都验证英语、简体、繁体的 Word 全正文＋表格和 PDF 提字，及整个 job JSON 前后相等。已完成 case 明确不得被显示为“未提交”或“未完成”。这三个参数实例是新增回归，不计作原 27 项中的新增失败。

正向已采用身份的显示没有通过恢复 Project fallback 来验证。既有 `VettingPackageIdentityReportTest` 的 3 个方法继续验证冻结已采用身份正常出现、临时显示身份不成为权威、缺失采用字段保持未确认；其 focused XML 为 [3／3 通过](../../../.local/java-baseline-failures-repair-20261024/java-focused02/ai_consense_service/target/surefire-reports/TEST-com.consense.service.vetting.VettingPackageIdentityReportTest.xml)。

## 5. 定向组合：192 项为 191 通过＋1 条件跳过

`java-focused02` 的全部 15 份 XML 求和为 **192 项＝191 通过＋1 skip，0 failure／0 error**；[maven.log](../../../.local/java-baseline-failures-repair-20261024/java-focused02/maven.log) 的终态为 `BUILD SUCCESS`，总时长 45.993 秒，结束时间 `2026-10-24T08:11:40+08:00`。不能写成 192 项全部通过。

| Suite | Tests | Passed | Skipped |
| --- | ---: | ---: | ---: |
| `NativePdfMixedSourceUnitsTest` | 8 | 8 | 0 |
| `OerAttachmentPythonDifferentialTest` | 37 | 37 | 0 |
| `OerAttachmentTransportTest` | 4 | 4 | 0 |
| `OerCanonicalSourceUnitsTest` | 16 | 16 | 0 |
| `OerSharedNoteAuthorDifferentialTest` | 36 | 36 | 0 |
| `OerTypedHierarchyContextTest` | 27 | 27 | 0 |
| `VettingCanonicalReportCoverageTest` | 4 | 4 | 0 |
| `VettingPackageIdentityReportTest` | 3 | 3 | 0 |
| `VettingPacketReportTest` | 4 | 4 | 0 |
| `VettingReaderReportPaginationTest` | 6 | 5 | 1 |
| `VettingReaderReportPresentationTest` | 16 | 16 | 0 |
| `VettingReportContinuationTest` | 7 | 7 | 0 |
| `VettingReportPageFurnitureTest` | 3 | 3 | 0 |
| `VettingReportWriterTest` | 12 | 12 | 0 |
| `VettingSampleReportAlignmentTest` | 9 | 9 | 0 |
| 合计 | **192** | **191** | **1** |

每行均对应 [focused Surefire 目录](../../../.local/java-baseline-failures-repair-20261024/java-focused02/ai_consense_service/target/surefire-reports/) 内同名 `TEST-com.consense.service.vetting.<Suite>.xml`；通过数为 `tests-failures-errors-skipped`。

唯一 skip 是 `VettingReaderReportPaginationTest.saveTheExisting180UnitQuotationAsACompleteWordControlForActualRendererReview`，原因原文为 `System property [consense.vetting.reportArtifactDirectory] does not exist`，见 [该 suite XML](../../../.local/java-baseline-failures-repair-20261024/java-focused02/ai_consense_service/target/surefire-reports/TEST-com.consense.service.vetting.VettingReaderReportPaginationTest.xml)。这不影响原五 suite 的 65 项均实际通过，也不能把该输出／renderer control 记为已执行。

最后独立只读核对确认：当前工作树的 `VettingReportWriter.java` 和四个报告测试文件均与 `java-focused02` 快照逐字节相同，`git diff --check` 通过。只核这一精确版本；后继如再修改须重新绑定适用证据。

## 6. 兼容性与尚未授予的验收

- 保持严格来源身份门，不让旧 response 的宽松预期覆盖当前 unknown；冻结原 fixture 不替换，当前负例另列精确身份。
- 读者报告文字和附录段落有变化，新生成 Word／PDF 的字节、换行、页数可能变化。历史文件没有改写；不能从提字完整或页数变化推断逐页版式通过。
- `VettingPdfWriter` 的旧入口仍委托同一报告实现，缺少 run／snapshot 时继续显示未确认身份；不从 Project 显示字段补造采用身份。
- 保存的来源计数、失败／未提交／预算限定和人工决定保留原意，不把任务完成、模型返回或空数组升级为来源完整、实质一致、人工确认或法律通过。
- 当前证据是隔离软件契约、保存 fixture 差分及实际生成字节的内容检查；不授真实 OCR／LLM／检索质量、全 SL 来源质量、比赛离线包、产品部署或真人业务通过。
- focused 通过不等于全仓通过；本节不预判仍在执行的宽回归，也不覆盖浏览器纯交互结果。

## 7. 待 Root 补充：宽回归、基线残余与内嵌浏览器实测

本节由 Root 在取得对应终态后追加。当前保留待核状态，不把静态控件检查、API／CLI 调用或后台函数当作用户网页步骤。

待追加内容包括：宽回归的完整 tests／failure／error／skip 分母及每项残余的基线比较；候选前端精确版本；内嵌浏览器实际完成的项目选择、报告入口、备注保存、报告下载及可读性步骤；遇到的卡点、修复和原始失败证据；部署与服务保全的实际边界。关键业务按钮是否直接可见、默认页面是否被哈希或内部码挤占，须以真实页面交互记录判断。

### 7.1 宽回归与固定基线残余复验终态

本补充只记录已经结束的 Java 执行窗，不填入网页步骤或业务验收结论。`java-wide03` 对修复后候选执行全仓 `-Dtest=*`；终态为 **4,655 项＝4,460 通过＋9 failure＋10 error＋176 skip**，`BUILD FAILURE`，Maven 时长 `20:33 min`，结束时间 `2026-10-24T08:33:36+08:00`。原 27 项对应的修复没有因扩大分母而被撤销；全仓也没有因此授予通过。

随后从未应用本轮修复的固定 `12d68729d7c07581bb8b64dc9d62b417d7635086` 源码副本 `baseline-source`，仅选择宽跑终态仍失败的全部 12 个 suite，执行 `java-wide-residual-baseline04`。该窗为 **98 项＝79 通过＋9 failure＋10 error＋0 skip**，`BUILD FAILURE`，Maven 时长 `01:25 min`，结束时间 `2026-10-24T08:35:43+08:00`。这 19 个残余没有删除、跳过、改写断言或重跑至通过。

| 执行窗 | Tests | Passed | Failure | Error | Skipped | 原始证据 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 修复候选全仓 `java-wide03` | 4,655 | 4,460 | 9 | 10 | 176 | [Surefire XML 目录](../../../.local/java-baseline-failures-repair-20261024/java-wide03/ai_consense_service/target/surefire-reports/)、[Maven 终态](../../../.local/java-baseline-failures-repair-20261024/java-wide03/maven.log) |
| 固定基线残余 `java-wide-residual-baseline04` | 98 | 79 | 9 | 10 | 0 | [Surefire XML 目录](../../../.local/java-baseline-failures-repair-20261024/java-wide-residual-baseline04/ai_consense_service/target/surefire-reports/)、[Maven 终态](../../../.local/java-baseline-failures-repair-20261024/java-wide-residual-baseline04/maven.log) |

可复算关系为 `4460+9+10+176=4655`、`79+9+10=98`。宽跑与残余复验的通过数来自各自全部 `TEST-*.xml` 的 `tests-failures-errors-skipped`，两个窗的分母有重叠，不相加。宽跑的 176 个 skip 保留原 XML 理由，不计通过，也不把缺少原件／renderer 的 failure 或 error 改记为 skip。

### 7.2 全部 12 个残余 suite 与 19 个 case 对照

以下 12 个 suite 在两窗的总数及 failure／error 数相同，均无 skip。每行原始证据为上述两目录中 `TEST-<完整 suite 名>.xml`；[逐案对照收据](../../../.local/java-baseline-failures-repair-20261024/residual-baseline-comparison04.json) 同时保存了 XML 路径／SHA、原始 message、完整 Throwable、精确测试 ID、异常类型和出现次数。

| 编号 | 完整 suite 名 | Tests／Passed／F／E | 已确认的残余类别 |
| --- | --- | --- | --- |
| S01 | `com.consense.ai.StructuredJsonSourceReferenceSchemaTest` | 14／13／0／1 | 样本仍提交旧 numeric reference 字段，当前 schema 已要求 text fact，并拒绝额外属性。 |
| S02 | `com.consense.document.DocxNativeOutlineTest` | 17／15／2／0 | 测试直接把 compact graph 的 `$nativeParagraph` 引用与原始 inline inventory／properties 比较。 |
| S03 | `com.consense.document.DocxStructureIntegrationTest` | 23／22／1／0 | 测试在未展开的 `$nativeSection` 引用上读取 `sectionId`。 |
| S04 | `com.consense.service.drafting.DraftNativeIdentityFontTest` | 8／7／1／0 | 本执行窗未提供实际 2a 原件目录 `consense.acceptance.sourceDir`。 |
| S05 | `com.consense.service.drafting.DraftNativeNormalFlowIntegrationTest` | 1／0／1／0 | 本执行窗未提供显式有限 renderer 配置 `consense.acceptance.rendererExecutable`。 |
| S06 | `com.consense.service.drafting.DraftNativeStaticInlineCompatibilityTest` | 1／0／1／0 | 既有 `pPr` 保全断言失败；只做固定基线复现，本轮不修这一原生版式范围。 |
| S07 | `com.consense.service.drafting.DraftReviewConsumerSeamTest` | 4／3／1／0 | 旧 catalog 精确对象断言与当前补充 identity group 的结果不同。 |
| S08 | `com.consense.service.drafting.DraftSctCellFlowCandidateTest` | 8／7／0／1 | `Objects.requireNonNull(consense.acceptance.sourceDir)` 先产生空指针，尚未读取 SCT 原件。 |
| S09 | `com.consense.service.sources.SourceContentDigestPreparationJpaTest` | 6／0／0／6 | H2 inline Java trigger 编译找不到 `org.h2.api`、`org.h2.jdbc`、`org.h2.value` 等包，未进入各 case 的业务断言。 |
| S10 | `com.consense.service.sources.SourceContentDigestTriggerTest` | 1／0／0／1 | 同一 H2 动态 trigger 编译／classpath 错误。 |
| S11 | `com.consense.service.vetting.OerActualQ16ModelMaterialTest` | 1／0／0／1 | 当前 owned replay 输出目录缺少 `fixtures/GCC.json`。 |
| S12 | `com.consense.web.BusinessSourceJpaBoundaryTest` | 14／12／2／0 | 既有来源 SQL 查询边界断言失败；两窗返回相同 SQL 断言消息，本轮不改查询或旧测试。 |
| 合计 | 12 个 suite | **98／79／9／10** | **19 个残余 case 均在固定基线复现。** |

下面的 case ID 按 `suite#testcase.name` 解释；suite 使用上表完整名称。类型代号精确对应：`F-A`＝failure／`org.opentest4j.AssertionFailedError`；`E-S`＝error／`com.consense.ai.StructuredOutputValidationException`；`E-N`＝error／`java.lang.NullPointerException`；`E-J`＝error／`org.springframework.jdbc.UncategorizedSQLException`；`E-H`＝error／`org.h2.jdbc.JdbcSQLSyntaxErrorException`；`E-P`＝error／`java.nio.file.NoSuchFileException`。所有 19 项的 ID、类型和多重数均一致；下表仅摘录消息信号，完整原文保留在逐案收据和 XML。

| 序号／suite | 精确 testcase.name | 两窗类型 | 消息／失败机制对照 |
| --- | --- | --- | --- |
| 01／S01 | `productionSchemaMapsStrictTypedReferenceBoundariesWithoutSourceCertification` | E-S | `Structured output does not conform to the supplied schema and flat-array contract.`，原文一致。 |
| 02／S02 | `customStyleInheritanceDefinesTableScopeUntilTheNextPeerWithoutOcr` | F-A | inline inventory 与 compact graph 比较失败；原始消息含两轮不同的生成来源身份，独立对应关系见 7.3，不声称原文一致。 |
| 03／S02 | `eachTableParagraphKeepsItsOwnLayoutPropertiesThroughGraphAndOrdinaryMaterial` | F-A | `expected: <true> but was: <false>`，原文一致；首个失败位于未展开 graph 的 properties 查找。 |
| 04／S03 | `nativeHeaderFooterReferencesKeepTypedInheritanceAndRawConfigurationThroughSlices` | F-A | 期望 `docx-section:0b8f585ad227ac1dfded4ea633ebad1fddd1fa7cd7def59cd1c3576385cba712`，实际为空，原文一致。 |
| 05／S04 | `theThreeActualSourcePoliciesCoverHanIdentityAndKeepTheirStylesAndFontTables` | F-A | `Actual 2a source directory is required for this specific regression ==> expected: not <null>`，原文一致。 |
| 06／S05 | `qualifiedStandardPackageThreePreviewsRetainFullNativeGenerationEvidence` | F-A | `Explicit finite renderer window required ==> expected: not <null>`，原文一致。 |
| 07／S06 | `staticMigrationPreservesNativeTabRunPropertiesAndRenderMarkerWithoutLooseningLocalLabelEdits` | F-A | `pPr at 0 ==> expected: <true> but was: <false>`，原文一致。 |
| 08／S07 | `oneConfirmedDefinitionOwnsCatalogValuesExtractionAdoptionAndPlan` | F-A | 期望仅 `{ruleVersion=graph-2}`，实际还含 identity group／contractTitle 字段，完整原文一致。 |
| 09／S08 | `actualSctRegistryCoversAllConfirmedHeadingsLeadInsAndBodyParagraphs` | E-N | message 为空，但两窗完整 Throwable 均定位测试第 69 行读取必需 `sourceDir`，没有以空消息相等代替 cause 核对。 |
| 10／S09 | `cold_binding_changed_before_stamp_preparation_rolls_back_both_binding_and_source_stamps` | E-J | 初始化 `source_document_content_digests` trigger 时 Java 编译缺 H2 包；完整 message／Throwable 一致。 |
| 11／S09 | `changed_source_or_routing_or_actual_original_is_never_given_the_captured_stamp` | E-J | 同一 trigger 初始化错误；完整 message／Throwable 一致。 |
| 12／S09 | `legacy_binding_gets_raw_snapshot_stamp_and_direct_sql_snapshot_mutation_is_rejected` | E-J | 同一 trigger 初始化错误；完整 message／Throwable 一致。 |
| 13／S09 | `missing_write_trigger_is_explicit_recoverable_and_does_not_change_content` | E-J | 同一 trigger 初始化错误；完整 message／Throwable 一致。 |
| 14／S09 | `selected_legacy_source_is_stamped_without_loading_body_again_and_warm_guard_is_metadata_only` | E-J | 同一 trigger 初始化错误；完整 message／Throwable 一致。 |
| 15／S09 | `cold_source_changed_before_stamp_preparation_is_rejected_and_the_new_stamp_is_rolled_back` | E-J | 同一 trigger 初始化错误；完整 message／Throwable 一致。 |
| 16／S10 | `directSqlContentChangesRefreshDigestsWhileProgressAndNullEmptyRemainDistinct` | E-H | 初始化 `SOURCE_DOCUMENT_CONTENT_DIGESTS` 时同样缺少 H2 包；完整 message／Throwable 一致。 |
| 17／S11 | `actualQ16ResponseReplaysNormalBusinessHitAndActualAdviceWriterWithoutWholeSourceProof` | E-P | 两窗各自的 `replay-output/fixtures/GCC.json` 不存在；仅替换 owned run 绝对目录前缀后，完整 message／Throwable 一致。 |
| 18／S12 | `vettingSourceSetPreservesCompleteVerificationInputsAndStoredArchive` | F-A | 完整 SQL 选择列／查询断言消息一致，未删除或忽略列来消除失败。 |
| 19／S12 | `businessSelectionKeepsFailedSupersessionAndModuleExclusions` | F-A | 完整 SQL 选择列／查询断言消息一致，未放宽筛选或改写失败状态。 |

12 个同名测试源码在两窗均存在；逐案收据保留原始文件 SHA，并另核仅 CRLF→LF 后全文相同，不把换行差异称逐字节相同。只读源码闭包核对还确认 `drafting` 58、`document` 20、`ai` 16 个生产 Java 文件与固定基线仅可能有换行差异、没有文本内容差异；这些核对只用于定位本轮改动的因果范围，不替代残余复验。

### 7.3 17 条原消息相同、1 条仅 owned 路径不同、1 条生成身份不同

[比较脚本](../../../.local/java-baseline-failures-repair-20261024/compare-java-residuals.py) 不改写原 XML：按 suite／test ID／failure-or-error／异常类型／多重数比较，并分别保存 message 和完整 Throwable。缺少报告立即失败，所有宽跑残余 suite 必须出现在基线窗。19 项中 **17 项原始 message 和完整 Throwable 相同**；S11 的缺文件错误仅有各自 owned run 绝对目录前缀差异，替换这一前缀后为 **18 项相同**。该映射没有删除错误、cause、source hash 或断言。

剩余 S02 的 `customStyleInheritanceDefinesTableScopeUntilTheNextPeerWithoutOcr` 保持原消息不相等：宽跑 message SHA 为 `fcfcdb1e422fff23b638bad062b409996c7b0cea140af5e8373db9316d984ada`，基线为 `c4a92cd515de6b01808cd4f6de3251d1d12d4923839b5d0cc8da3eaab6e202b1`。该测试分别在内存写入新 DOCX；两窗记录的 source SHA 为 `383b3fc75f61b2728913517799761c687162f2b2d3e8746b825dab3230dcb54d` 和 `07432ed9d478dbd491634d0830ea10e8ff3ec6cb9ccc0e8a008c4c57eaf30a70`。本 case 没有保存供逐字节比较的原始 package，原件字节相等未建立，也不据此推定来源身份相同。

[独立身份差异收据](../../../.local/java-baseline-failures-repair-20261024/docx-outline-residual-identity-delta04.json) 保留 expected 的 79 处及 actual 的 70 处完整字段差异。所有差异均为 source SHA 及其绑定 ID／引用：依据 `DocumentParser.java:1603` 的 `sourceSha|partName|nodePath` 公式，逐个重算两窗 9 个 paragraph ID；依据 `OerDocxSourceScopes.java:621` 的 `kind|firstMemberId` 公式，逐个重算两窗 3 个 range ID，全部吻合。只在附加分析中使用这一组经公式核验的 13 个身份对应关系后，两侧 expected 分别相等、actual 分别相等，part SHA、node path、文本及其他结构没有剩余差异。此对应关系不写回测试／原 XML，不是通用哈希忽略规则，也不把两份原件授予等价；它仅说明本次同一断言机制仍在固定基线失败。

可复核收据及 SHA-256：

- [逐案比较 `residual-baseline-comparison04.json`](../../../.local/java-baseline-failures-repair-20261024/residual-baseline-comparison04.json)：`59daf982bb182b18b029782325b080759828977f0f69a960cf3903bd720e0b99`。
- [生成身份差异 `docx-outline-residual-identity-delta04.json`](../../../.local/java-baseline-failures-repair-20261024/docx-outline-residual-identity-delta04.json)：`2efc346888bf47a8ac034257963b10b6897639b167c9d18d4dd3f625735b4176`。
- [执行环境 `baseline04-environment.json`](../../../.local/java-baseline-failures-repair-20261024/baseline04-environment.json)：`ddce1186c46d114e01a18e6bf2162ac0e9f7af8e9f2bc35c79ed4cb2c66650d9`。

### 7.4 Java 与 OS 临时目录的已证边界

`java-baseline01`、`java-focused02`、`java-wide03` 的 runner 明确设置各自 owned `-Djava.io.tmpdir`；现有票据不足以证明这三轮也显式设置了 OS `TEMP`／`TMP`，因此不作这一声明。

`java-wide-residual-baseline04` 在启动前显式将 OS `TEMP`、`TMP` 设为仓内 `.local/java-baseline-failures-repair-20261024/baseline04-os-temp`，JVM 仍独立使用 `java-wide-residual-baseline04/temp`，配置证据见上述环境收据。宽跑 Maven PID35956及其测试子进程全部退出后，才启动 baseline04 的唯一 Maven PID33388；baseline04 已以 exit 1 退出，关闭 hooks 完成，没有为补证同时启动第二个 Maven worker。19 个原始残余及其环境限制均保留，本节不把它们授为全仓通过、产品运行通过或网页验收通过。

## 8. 内嵌浏览器：真实交互、环境修正和保留失败

以下记录为真实网页交互，和 Java／控件测试分别计。本轮使用已有 ChatGPT in-app browser 的独立标签页，候选网址为 `http://127.0.0.1:5187/#/vetting`；后端仅监听 `127.0.0.1:18186`。原 18086／5177 业务进程、H2 owner 及停止的 OCR 未因候选环境重启。候选使用 owned H2 文件库和 uploads 目录，向量库明确选 memory，不写现有 Qdrant collection；OCR 明确关闭。

服务器标准启动、隔离构建／测试、日志读取和下载产物检查属于环境及验证工作。项目创建、模型选择、DOCX 上传、运行审查、来源查看和报告下载均通过可见网页控件完成；未通过 curl、直接业务 API、浏览器 fetch、WebMCP、数据库种业务数据或命令行调用 ConSense 业务函数代替用户步骤。

### 8.1 候选启动修正

- 首次启动因 Flyway 7.15 查询 H2 旧 `INFORMATION_SCHEMA.SETTINGS` 失败，保留 [startup01](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/backend-startup01-flyway-h2-failure.log)。候选 JDBC URL 增加 H2 现有 `OLD_INFORMATION_SCHEMA=TRUE;NON_KEYWORDS=VALUE` 兼容设置。
- 随后遇本机已知的 NIO 写权限预检误报，保留 [startup02](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/backend-startup02-local-nio-failure.log)。候选沿现有 `H2LocalFileAccess` 的显式 `-Dconsense.h2.local-file-compat=true` 和 `rwfile:` scheme；没有修改 ACL 或依赖版本。
- H2 动态 Java trigger 所需依赖由现有 H2 2.1.214 jar 显式加入启动 classpath，应用仍从标准 Spring Boot `JarLauncher` 启动。新空库通过标准启动实际执行 V1–V27 共 27 个 migration，见 [startup03](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/backend-startup03-schema-success.log)。这不表示业务数据库安装了 V27。
- MiniMax chat 选择本身没有绑定完整输入的 token observer。没有放宽预算门；改为启用已有 Qwen／Qwen Flash 配置及正式 cap1 usage preflight 路线，密钥从已有环境别名注入子进程，不写入报告／命令参数实际值。配置、owned PID、JAR SHA 见 [launch.ps1](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/launch.ps1) 和 [launch receipt](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/launch-receipt.json)。配置存在不等于推理通过。

### 8.2 网页创建、上传与三次原始运行

网页新建项目 `Java27 前端验收 20261024`，工作区显示编号 `QA20261024`。该显示编号不等于已采用的报告／文稿身份，未绕过 Drafting 中的明确采用步骤。

通过网页 file chooser 一次选择两份自造 DOCX：`Tender-conditions.docx`（1,167 bytes）与 `Notes-to-tenderers.docx`（1,225 bytes）。网页逐份返回 2/2、0 份接收／解析失败；源集分别列出 SCC／NTT、已解析，每份 7 个原生位置。物理 Word 页码仍未知，不按已解析推定整份质量／法律适用已确认。[输入及 SHA 清单](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/preparation.json)、[源集截图](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/sources-uploaded.jpg)。TXT 对照文件没有上传，也没有替代真实 DOCX。

两份输入明确声明为软件验收自造资料，付款条件为同一认证事件后 14 天与 21 天；它们不代表真实项目或已采用合约。本节验证应用路径，不授予真实 2b 资料、七版 SL 或生产业务验收。

| 次序／网页选择 | 真实任务／结果 | 原始证据及边界 |
| --- | --- | --- |
| 1，MiniMax-M3 | `0a01637c-dd49-4b5e-b3a4-202bfb1a37f4`，执行结束 3/3，0/2 文件涉及实质审查，0 发现 | 14 来源单元／28 来源请求均未获成功提交，`no_bound_token_observer`。从网页下载 [首次 JSON](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/first-run-budget-unknown.json)；[截图](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/first-run-budget-unknown.jpg)。不计模型审查通过。 |
| 2，Qwen Flash | `ff246f13-8d53-44e3-b32c-f83a25f1cbec`，2/3 FAILED | 首个模型 content 是完整 JSON，识别出 14／21 天差异，但来源条款归属被拒绝；唯一修复回复混入解释文字，严格 JSON 门再次拒绝。[失败截图](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/second-run-invalid-repair.jpg)。 |
| 3，Qwen 27B | `09cb2ffc-aa1f-4e17-8d01-bac602660fa5`，2/3 FAILED | 首次回复非完整 JSON；唯一修复回复为完整 JSON，仍被 `unsupported_source_clause_attribution` 拒绝。[失败截图](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/third-run-source-attribution-failure.jpg)。换模型没有消除该归属断链，未将换模型视为修复。 |

两轮 Qwen 的 4 份实际 provider-content 已从 owned 运行日志逐字保全，见 [原始输出 manifest](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/preserved-model-failures/manifest.json)。每份保留长度、SHA、独立严格 JSON 语法检查；语法合格不证明来源归属或业务内容正确。没有提取 JSON 子串、删除解释后接收，也没有改写历史失败状态。

### 8.3 界面可读性及后续修复的验证范围

此前实际默认界面会展示完整 packet ID／来源 SHA，见 [原现场](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/before-hash-clutter.jpg)。这些标识、原始坐标和技术 JSON 已移入单层可选技术详情；文件名、引文、未确认／缺失限制和打开原件保持直接呈现。保存复核等业务按钮不放入技术详情。Fixed 的成员数量替代默认长 ID 列表，完整 ID／缺失列表仍可查；Canonical pointer／资产 SHA 校验保持。

网页还暴露两个展示问题并已修：导出后核对完整任务记录暂时被写成后台审查中；来源归属与 JSON 失败默认重复显示原始技术消息。现在前者显示明确的来源／报告记录核验状态，原操作阻止门保持；后者仅对两种已观察的技术 prefix 给三语可读说明并去重，原 message/error 留在既有技术详情，FAILED 状态及导出／保存门保持。

最终前端同树控件组合 **101/101 PASS、0 failure／skip**，生产 build（含 `vue-tsc --noEmit`）通过。证据为 [controls](../../../.local/java-baseline-failures-repair-20261024/ui-failure-reader-final/controls.log)、[build](../../../.local/java-baseline-failures-repair-20261024/ui-failure-reader-final/build.log)、[view regressions](../../../.local/java-baseline-failures-repair-20261024/ui-failure-reader-final/view-regressions.log) 和 [源码一致收据](../../../.local/java-baseline-failures-repair-20261024/ui-failure-reader-final/source-consistency.json)。109 个 src 与 5 份相关测试冻结前后字节一致，隔离 web 的 109 个 src 与这次受测源码差异 0。前序 99／100 项记录、首次全局 Temp build 失败均保留，未覆盖。


## 9. 网页实际暴露的来源条款位置断链与最终定向回归

本节记录第 8 节原始失败之后新增的最小后端修复，和第 7 节原 27 项修复分别核算。`java-wide03` 的 4,655 项是在本次网页归属断链修复前执行，不能称为本次最终后端源码的全仓回归。本节的 145 项仅为最终定向契约／软件回归；最终网页重跑、保存、下载和实际可读性终态仍由 Root 按真实交互另记。

### 9.1 首个已证断层在来源范围的业务消费者

Qwen Flash 原始首轮 JSON 已引用 NTT 的 14 天和 SCC 的 21 天自造原文。该包的两个 carrier 均为 `observed_source_blocks`，flat `clauseId` 为 null；已有 `wordSourceScopeContext.observedWordSourceScopes` 同时保留完整 `NTT1`／`SCC1` 原生范围，包含 `source-clause:<SHA>`、`source_syntax_complete`、完整 ordered members、显式来源标签起点及下一来源边界。`automaticEditEligible=false`、`semanticApplicabilityVerified=false` 保持不变。这是已观察的来源语法成员关系，不是法律采用或适用判定。

当时 `VettingSourceAttribution.reasons` 仅从证据 chunk 的 flat `clauseId` 与 `clauseHeadingLocation` 同时存在时授予条款位置；没有消费已经验证并保留的原生来源范围。因此模型可以看见 NTT1／SCC1，接收门却只能看见 flat null，正确的 substantive observation 与其来源条款标签发生断链。此处不能通过给全部 carrier 回填 flat `clauseId` 修复：一个 carrier 可能容纳多个条款。也不能从模型答复、同名文件、数组邻接标题或普通 cross-reference 反推归属。

首个修复点为上述消费者能力接线，原生 parser／normal graph 没有重写。严格单一完整 JSON 门保持；Qwen Flash repair 中解释文字混入 provider `message.content` 的原始失败也没有被剪切成内部 JSON 后接收。旧失败仍由第 8.2 节 manifest 保全，不因最终修复重跑而改记通过。

### 9.2 当前源码的可信接线与有限能力

- [CanonicalEvidenceService.wordSourceScopeContext](../../src/main/java/com/consense/service/sources/CanonicalEvidenceService.java#L438) 从当前 frame 的 verified binding／snapshot graph 取得上下文；[NormalSourceCandidateGraph.selectedWordScopes](../../src/main/java/com/consense/service/vetting/NormalSourceCandidateGraph.java#L180) 证明原件／part／native paragraph、选中 offsets／文本、范围成员和边界。原 identity、generation、revision、projection、canonical chunk 与 selected parts hash 继续保留。
- [ObservedSourceClauseLocators.requireBinding](../../src/main/java/com/consense/service/vetting/ObservedSourceClauseLocators.java#L16) 复用上述 pointer/context 精确绑定门；[OerModelMaterial](../../src/main/java/com/consense/service/vetting/OerModelMaterial.java#L304) 与 helper 共用这一校验，不接受压缩后丢失身份的模型 JSON 作为新 authority。
- [OerBusinessEvidence.loadSemantic](../../src/main/java/com/consense/service/vetting/OerBusinessEvidence.java#L93) 在 owned frame 冷构建真实 canonical pointer／raw context 时捕获私有构造的不可变 `Bound`。Session 新增的私有 capability map 仅保留该选中 paragraph 的身份、block、原文和 reference 能力，不把整章上下文复制到持久 chunk；既有 `Chunk` 字段、flat `clauseId`、serialized view 签名及 private Session constructor 签名保持。
- [OerBusinessEvidence.sourceClauseLocators](../../src/main/java/com/consense/service/vetting/OerBusinessEvidence.java#L354) 先核当前 session 和 exact view payload，再匹配当前 chunk/pointer 的完整身份、whole part 和文本。非 semantic 路径从同次 fresh raw word context 捕获；semantic 路径使用冷构建能力，禁止从 compact model context 重建。
- [VettingSemanticReview](../../src/main/java/com/consense/service/vetting/VettingSemanticReview.java#L475) 在实际提交中取得同一 `sourceLocators` map，同时交给 user/model pack 和 [接收归属门](../../src/main/java/com/consense/service/vetting/VettingSemanticReview.java#L589)。预算预览 `completeInput` 也使用同一投影逻辑；dispatch 前及 response 后的 current guard 保留。[VettingSourceMaterial](../../src/main/java/com/consense/service/vetting/VettingSourceMaterial.java#L97) 在共同外层加入 locator，覆盖 `semanticUnitRef` 与 `source-semantic-units-v1` 两个绕过 `OerModelMaterial.project` 的 compact 分支。
- [VettingSourceAttribution](../../src/main/java/com/consense/service/vetting/VettingSourceAttribution.java#L37) 只在当前引用通过同一 `Bound.references` 后增加该来源 reference。新增 descriptor 仅含版本、reference、anchor、选中 paragraph 的源语法范围说明和 unknown／false 权限；最终既有 semantic presentation 继续按原规则移除内部 anchor，不重复提交整段原文，不创建另一套身份 hash。归属 policy 版本已变化，旧失败运行的原冻结记录不被原地复用为新结果。

本轮新增能力严格限于：一个选中 whole native paragraph、单一完整 root `source-clause` 范围、唯一匹配的 `source_bound_native_member`、无 context issues、原文／完整 source position／retained block 精确对应；范围 ID 按既有 producer 的 `sourceSha|startMemberId|reference` 公式重算一致，完整／编辑／适用 flags 必须为真实 boolean。精确引用至少 12 个原始 UTF16 字符且在该 paragraph 中只出现一次；不折叠空白授予新归属，不做相似匹配。现有仅规范化空白的 evidence located 仍不足以授权来源条款位置。

nested／多范围、parent 链、table／cell carrier、partial／多 part、跨成员引用、重复引用、来源／解析／projection／revision／selected parts 漂移、context-only、普通字面 reference、无来源证明的相邻独立标签均不获得新增能力。旧 flat clause＋heading 门保持；source syntax 的支持不授予法律适用、采用、编辑或全来源质量通过。没有新增第三次模型尝试，也没有减少 JSON／来源校验。

### 9.3 最终 13 个 suite：145 项均实际通过

最终执行窗为 `java-locator-final07`，从当时实际工作树 fresh copy 源码执行，**145 项＝145 通过＋0 failure＋0 error＋0 skip**，`BUILD SUCCESS`，Maven 时长 `54.742 s`，结束时间 `2026-10-24T09:10:52+08:00`、exit 0。每行来自该窗同名 `TEST-com.consense.service.vetting.<Suite>.xml`，逐文件路径及 SHA 已写入 [test-receipt.json](../../../.local/java-baseline-failures-repair-20261024/java-locator-final07/test-receipt.json)。

| Suite | Tests | Passed | Failure | Error | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| `CanonicalWordSourceContextTest` | 5 | 5 | 0 | 0 | 0 |
| `NativePdfMixedSourceUnitsTest` | 8 | 8 | 0 | 0 | 0 |
| `ObservedSourceClauseLocatorsTest` | 8 | 8 | 0 | 0 | 0 |
| `OerSemanticBusinessEvidenceTest` | 8 | 8 | 0 | 0 | 0 |
| `OerSharedNoteAuthorDifferentialTest` | 36 | 36 | 0 | 0 | 0 |
| `VettingCanonicalReportCoverageTest` | 4 | 4 | 0 | 0 | 0 |
| `VettingPacketReportTest` | 4 | 4 | 0 | 0 | 0 |
| `VettingQwenUsagePreflightWorkflowTest` | 11 | 11 | 0 | 0 | 0 |
| `VettingReportWriterTest` | 12 | 12 | 0 | 0 | 0 |
| `VettingSampleReportAlignmentTest` | 9 | 9 | 0 | 0 | 0 |
| `VettingSemanticMaterialTest` | 6 | 6 | 0 | 0 | 0 |
| `VettingSourceAttributionRepairTest` | 12 | 12 | 0 | 0 | 0 |
| `VettingSourceMaterialTest` | 22 | 22 | 0 | 0 | 0 |
| 合计 | **145** | **145** | **0** | **0** | **0** |

可复算为 `5+8+8+8+36+4+4+11+12+9+6+12+22=145`；其中原五 suite 仍为 `36+4+4+12+9=65`，全部实际通过。新 helper 的 8 个 JUnit 方法包含实际 tiny DOCX→DocumentParser→normal graph→canonical pointer 的 NTT／SCC 正控、同一 preprojection proof 跨两个 compact caller、flat null／法律 unknown 不变，以及来源／parts／member／范围／literal／partial／table／引用多义等负控。fixture 不是为 helper 手工拼出的正例 context；边界突变只用于对真实 producer 结果做明确 negative mutation。

[新增测试源码](../../src/test/java/com/consense/service/vetting/ObservedSourceClauseLocatorsTest.java) 与最终快照 SHA 均为 `aa687a1ddfbeb7f51a1287b9317ec3bef409baf83c8fb815fc968efd2dc2b171`。[最终 receipt](../../../.local/java-baseline-failures-repair-20261024/java-locator-final07/test-receipt.json) SHA 为 `3b89879e5c6cfbfa537890846586ae0045a03dcaa521358e2010ee47c590bf0c`，含全部 13 份 XML hash 和 [Maven log](../../../.local/java-baseline-failures-repair-20261024/java-locator-final07/maven.log) hash，可从 XML 重新累计。

前置 `java-locator-positive05` 仅执行当时的 actual parser 正控 1 项，`java-locator-focused06` 为含当时 7 个 helper 方法的 144 项组合；两窗均原样保留，但不冒充后来 8 个方法的源码覆盖，也不与最终 145 项相加。最终 07 启动前显式设置 OS `TEMP`／`TMP` 为仓内 `locator07-os-temp`，JVM 独立使用 `java-locator-final07/temp`，全程唯一 Maven worker，结束后才由 Root 接手候选打包／服务及网页步骤。此处不重新授予第 7 节宽跑残余通过，不把 Java fixture／预算 workflow 通过当成真实 provider 或最终网页业务通过。


## 10. final07 网页后续原始结果、packet citation 边界与 final09 定向回归

本节仅追加第 4／5 轮真实模型记录、其有限解释和随后修复。第 1–9 节既有文字／原始失败未改写。第 9 节所说的 14／21 天原文差异与来源位置可核，不应据此授予“互斥义务成立”；本节对该 self-authored control 的逻辑边界明确补充。新的明确互斥资料网页、保存、导出与发布／runtime 终态由 Root 在第 11 节另记，本节不提前填写通过。

### 10.1 第 4／5 轮 request 与 provider content 原样保全

已按 owned stdout 的原始 byte offsets／line spans 保全完整的 3 次 request、3 次 response、整份日志副本和第 4 轮网页导出 JSON，见 [post-locator-run04-05-preserved/manifest.json](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/post-locator-run04-05-preserved/manifest.json)。Manifest SHA 为 `1844afd35200808a66a28b4d9de3c3bacb64d70a5bb91dc5ef5b7503b1628e19`；每份 request／response span 和独立 content 都列 bytes、SHA、原始行号及 byte 区间。content 只在必要时去除 logger 分隔行自己的末尾换行，以逐一匹配 logger 记录的 UTF16 字符数；未删模型解释、未提取内部 JSON、未重写原文。这里保存的是准确日志内容，不冒称 provider HTTP wire envelope 原始 bytes。

| 网页轮次／模型 | 已知任务身份 | 原始 request／response | 保留的真实结果 |
| --- | --- | --- | --- |
| 4，final07 Qwen Flash | `5cc5c38b-55c0-48cd-906f-102be2c6cf7a`，来自网页导出 | `request-01.log`／`response-01.log`，content **2 UTF16 chars**，SHA `4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945` | 严格 JSON `[]`，0 assessment／finding；不等于来源实质全通过。 |
| 5，final07 Qwen 27B 首次 | 总任务 ID 仍 unknown；未读 DB／API 补造 | `request-02.log`／`response-02.log`，content **6,722 UTF16 chars**，SHA `45b09486ef3380176172e32429ad7043e9aa0b566dce151c4f0e5f82960a14b5` | provider content 含推理解释及后续 JSON，严格单一 JSON 门拒绝；原失败保留。 |
| 5，同次唯一 repair | 同上，不是额外无限重跑 | `request-03.log`／`response-03.log`，content **1,524 UTF16 chars**，SHA `7ccb6ed9093a415f432b4a33a6c110db1aadab0555eebda5fef51e5c783df631` | 严格 JSON 1 record；`factBasis` 中 `NTT Clause 1 (R5)` 被旧接收语法当成子项 `NTT1(R5)`，归属门拒绝。 |

上述文件均在同一 manifest 目录，另有保全的 [第 4 轮网页 JSON](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/post-locator-run04-05-preserved/fourth-run-zero-findings.json)。第 5 轮原答及 repair 均把“within 14 days／within 21 days”称为互斥；该表述没有被修正后覆盖，既有拒绝状态也不因最终语法修复而改记通过。

### 10.2 空数组的已证边界及原始 control 的不足

第 4 轮 [只读分析收据](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/post-locator-run04-evidence/analysis.json) SHA 为 `46afa3142af287fa010e7033b24833383bb4313c020c9d61cec231ba8659c2a9`。该窗真实 source pack 为 14 rows／837 UTF16 原文字符；只去除新增 `observedSourceClauseLocators` 字段后，全部 rows 与原 Flash 首轮 source pack 完全相等，没有换源、删段或更改角色。R5／R12 均为 tender，实际输入分别保留 14 天／21 天全文和 NTT1／SCC1 locator；8 个可支持 row 的 locator 已到达真正模型输入。没有证据表明此次 `[]` 来自 compact 投影丢失这两条正文或来源能力。

第 4 轮网页 JSON 的 `attempt.rawResponse` 为 `[]`，answer receipt 的 model 为 `qwen3.8-flash`、usage 为 `prompt_tokens=81042, completion_tokens=1, total_tokens=81043`，provider envelope SHA 为 `c08c7b16be36d01912adaa3c312397ba1621d5b32a3932bdf2996460ee2ba258`。独立 cap1 preflight 的 response SHA 为 `7dab3f2a9ee9dc76d6394ddf5a2cefd0722c52539c2eb13a425eda79f0413abb`、completion 1、finish reason `length`；两份 envelope hash 不同。sealed input 的 generation 与 preflight entity hash 也不同，generation output reserve 为 16,384。

源码 [QwenChatUsagePreflight.observe](../../src/main/java/com/consense/service/llm/QwenChatUsagePreflight.java#L136) 只把 cap1 usage 转成 observation；[generate](../../src/main/java/com/consense/service/llm/QwenChatUsagePreflight.java#L173) 另行 dispatch answer，[QwenChatClient.generate](../../src/main/java/com/consense/ai/QwenChatClient.java#L62) 使用 `generationJson`，经 [sendPreparedQwen](../../src/main/java/com/consense/ai/OpenAiLlmClient.java#L185) 的 no-replay HTTP 路径接收实际业务 content。未见把 preflight content 缓存为业务答复或应用 mock 冒作该 explicit Qwen answer。0.865 秒的 generation 日志区间本身不足以证明 replay／mock，也不能独立证明远端基础设施身份；这里以应用绑定、provider-reported receipt 与代码路径为限，不扩成独立网络归因。

原 control 的 NTT 要求“within 14 days”，SCC 要求“within 21 days”：若第 14 天或之前付款，两者可以同时满足，单凭这两句不能证明严格互斥；NTT 2 还明确声明不是 real project／adopted contract。因此空结果可落在此资料的合理判断范围，不能通过放宽代码或强迫模型出问题把它修成非空。它也没有提供模型如何取舍的独立解释，不能据此证明所有检查通过。第 5 轮模型将两者称互斥的内容同样不授为正确业务判断；此次语法修复仅处理真实 citation 误读，不替模型背书。

### 10.3 首个来源拒绝为已绑定 packet citation 被吃成子项

第 5 轮 repair 原文的 `factBasis` 为：

```text
NTT Clause 1 (R5) specifies 'within 14 days'. SCC Clause 1 (R12) specifies 'within 21 days'. Both refer to 'each certified amount' for 'monthly interim payment'.
```

`1` 与 `(R5)`／`(R12)` 之间实际存在空格。原 `NUMBER` 的大小写不敏感子项部分 `(?:\s*\([a-z0-9]+\))*` 将这两个 packet alias 吃为 `1(R5)`／`1(R12)`，第一拒绝记录为 field=`factBasis`、clauseId=`NTT1(R5)`。这不是 root locator 未进入模型；同一输入已经有 NTT1／SCC1，引用原文也均为对应 source 中唯一的精确原文。错误发生在叙述语法把已提交的短来源引用视为来源子条款。

最小修复位于 [VettingSourceAttribution](../../src/main/java/com/consense/service/vetting/VettingSourceAttribution.java#L17)：不改原 `NUMBER`、`PREDICATE` 或 raw record，只在完整原子 clause 按原门验证失败后，检查末尾空白分隔且 case-sensitive 的 `(R[1-9][0-9]*)`。新 [四参入口](../../src/main/java/com/consense/service/vetting/VettingSourceAttribution.java#L32) 接收当前 packet 的真实 alias→source binding；[实际 caller](../../src/main/java/com/consense/service/vetting/VettingSemanticReview.java#L589) 传既有 `references.bindings()`，不从模型字符串补造 binding。

fallback 同时要求：alias 确属此 packet；同一 record 实际引用该 source；该引文为至少 12 字符的 raw exact 唯一原文；该 quoted source 已经由旧 flat＋heading 门或第 9 节私有 Bound 证明 base clause owner。仅存在目录条目、其他 record 引用、其他 source／namespace、base Clause 99、未知 alias、未 located／空白规范化后才 located 的 quote 均不足。完整原子来源子项优先，不盲删真实 `1(r5)`／`1(R5)`、`1(a)(r5)`，也不把 `1 (a) (R5)` 偷换成 root 1。旧二／三参入口默认不提供 packet bindings，仍保守拒绝含糊情况。policy 升为 `explicit-source-clause-attribution-v3:bound-packet-citation-v1`，参与既有 checkpoint policy hash。

raw 文本、显示内容和保存的模型原输出不改；source quality、法律适用、adoption／edit unknown 门及最多两次模型 attempt 保持。这里允许的仅是有当前 source／quote／owner 证明的 transport citation 解释，不授予未知子项或任意括号豁免。

### 10.4 final08 编译失败保留；fresh final09 共 152 项实际通过

`java-locator-final08` 在测试编译阶段因新增集成测试错误调用不存在的 `Candidate.getFactBasis()` 失败，exit 1，尚未执行任何 suite。原日志／快照保留，见 [final08 Maven log](../../../.local/java-baseline-failures-repair-20261024/java-locator-final08/maven.log)。修正仅删除这一错误测试断言；实际 attempt 的 raw 全文、SHA、原 `factBasis`、decoded 状态及一次 generation 断言保留，没有为测试增加产品 getter 或改业务输出。

随后从修正后的实际源码 fresh copy 执行 `java-locator-final09`：**152 项＝152 通过＋0 failure＋0 error＋0 skip**，`BUILD SUCCESS`，Maven `55.164 s`，结束时间 `2026-10-24T09:27:29+08:00`、exit 0。每行来自 [该窗 Surefire 目录](../../../.local/java-baseline-failures-repair-20261024/java-locator-final09/ai_consense_service/target/surefire-reports/) 同名 `TEST-com.consense.service.vetting.<Suite>.xml`，逐文件路径／SHA 在 [test-receipt.json](../../../.local/java-baseline-failures-repair-20261024/java-locator-final09/test-receipt.json)。

| Suite | Tests | Passed | Failure | Error | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| `CanonicalWordSourceContextTest` | 5 | 5 | 0 | 0 | 0 |
| `NativePdfMixedSourceUnitsTest` | 8 | 8 | 0 | 0 | 0 |
| `ObservedSourceClauseLocatorsTest` | 8 | 8 | 0 | 0 | 0 |
| `OerSemanticBusinessEvidenceTest` | 8 | 8 | 0 | 0 | 0 |
| `OerSharedNoteAuthorDifferentialTest` | 36 | 36 | 0 | 0 | 0 |
| `VettingCanonicalReportCoverageTest` | 4 | 4 | 0 | 0 | 0 |
| `VettingPacketReportTest` | 4 | 4 | 0 | 0 | 0 |
| `VettingQwenUsagePreflightWorkflowTest` | 11 | 11 | 0 | 0 | 0 |
| `VettingReportWriterTest` | 12 | 12 | 0 | 0 | 0 |
| `VettingSampleReportAlignmentTest` | 9 | 9 | 0 | 0 | 0 |
| `VettingSemanticMaterialTest` | 6 | 6 | 0 | 0 | 0 |
| `VettingSourceAttributionRepairTest` | 13 | 13 | 0 | 0 | 0 |
| `VettingSourceCitationBoundaryTest` | 6 | 6 | 0 | 0 | 0 |
| `VettingSourceMaterialTest` | 22 | 22 | 0 | 0 | 0 |
| 合计 | **152** | **152** | **0** | **0** | **0** |

可复算为 `5+8+8+8+36+4+4+11+12+9+6+13+6+22=152`。本窗包含 final07 的全部 13 suite，另加 citation boundary suite 的 6 个方法和原 attribution receiving harness 的 1 个集成方法，即 `145+6+1=152`；不把重叠执行窗相加为独立 case。原五 suite 仍为 `36+4+4+12+9=65`，均实际通过。

新增 [citation boundary suite](../../src/test/java/com/consense/service/vetting/VettingSourceCitationBoundaryTest.java) 覆盖原句正控、旧 overload 仍拒绝、错误／未知／其他 source alias、真实 child 优先、quote 非 exact／重复、跨 record、无来源 authority 和各发布字段 base assertion，原 raw narrative 不改。[实际 receiving harness 正控](../../src/test/java/com/consense/service/vetting/VettingSourceAttributionRepairTest.java#L58) 经真实 `review→executePacket→references.bindings()` 证明本次 packet 可一次 generation 接收且保存原始全文／hash／factBasis；不是只直接调用 helper 的 mock 断言。

最终 [test receipt](../../../.local/java-baseline-failures-repair-20261024/java-locator-final09/test-receipt.json) SHA 为 `9afda3b12b7b61bfbb983f93aafe7c069a36590797c5059a0449cf5652ace0da`。final08 worker exit 1 后才启动唯一 final09 Maven；启动前显式设置 OS `TEMP`／`TMP` 为仓内 `locator09-os-temp`，JVM 为 `java-locator-final09/temp`，最大 heap 8 GiB。终态后由 Root 接手精确源码核验／打包及 owned 候选服务。此处不将 final09 契约通过冒称 provider／新项目网页／生产／全仓通过，也不撤销第 7 节 19 个宽跑残余。

## 11. 明确互斥材料的纯网页端到端验收与最终候选

本节在第 10 节后继续实际网页交互。创建项目、上传、启动审查、查看证据、人工填写和保存、分派、刷新及所有下载均使用 Codex 内嵌浏览器 `http://127.0.0.1:5187/#/vetting` 的可见控件。没有通过命令行调用 ConSense 函数、直接 API、浏览器 fetch、数据库写入或手工植入 finding。终端仅用于隔离构建／启动、读日志、核对下载字节与渲染成品。此结果是自编材料的软件流程验收，不是实际 2b、七版 SL、原件质量、线上项目或 H800 验收。

### 11.1 新材料与独立项目

第 4 轮的 `within 14 days`／`within 21 days` 可以同时满足，原空结果和第 5 轮模型的互斥误判继续保留。另从网页建立 `Java27 明确互斥前端验收`，显示编号 `QA20261024B`。新自编材料明确限定同一 Employer、每笔认证月度付款、同一认证日期；NTT 要求 exactly 14 calendar days，SCC 要求 exactly 21 calendar days，双方均禁止提前或延后付款。两份文本没有提供优先顺序，合同采用及真实适用性仍未知。

| 实际上传文件 | 字节 | SHA-256 |
| --- | ---: | --- |
| `Exact-NTT.docx` | 1,170 | `6e767be7a517ff7f499e53ec1aeac2c1675f1e67f42ecbbdba740334f9dcd96d` |
| `Exact-SCC.docx` | 1,173 | `6fa60dd4814a99745ef88cfb66c5c5526ab63dec40e4d72a53fd59c35facb486` |

正文、包生成方式及边界见 [preparation.json](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/exact-payment-files/preparation.json)。两份均通过网页文件选择器上传，2/2 返回已解析、0 接收／解析失败，每份记录 7 个原生位置，未推算 Word 物理页；[上传完成截图](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/exact-sources-uploaded.jpg)。旧项目和五次运行未删除或覆盖。

### 11.2 实际模型结果与人工保存回读

Qwen 27B 本轮 job 为 `020d67c1-8618-482d-a586-a4cb316f6324`，执行结束 3/3，涉及 2/2 文件，返回 **1 条**跨文件付款期限冲突 `VT-001`。实际有 **2 次模型回复**：首次 `structured_output_invalid / INVALID_JSON`，既有唯一修复回复 `decoded`。原始内容和失败保持在网页导出的 JSON；没有剪取内部 JSON、加第三次尝试或改写历史结果。

双方完整原文已定位在各自 `body/4/paragraph`。来源语法位置不授法律条款采用或编辑权限，报告仍明示 formal clause ownership unconfirmed。语义调用面板为 14 个全局输入片段／906 UTF16 字符，问题／一致／上下文不足为 1/0/0，采纳／证据剔除为 1/0；未处理／总请求为 0/28，但各请求仍为仅全局来源已存在、供应商估算已解码、范围未知，`semanticScopeVerified=false`。按文件分母为 NTT 7/7、447/447；SCC 7/7、459/459。这些数值不证明原件完整性或逐项实质审完。

通过发现详情直接填写中文“项目团队回复”和“实际处理说明”，保持“是否需纳入招标补遗＝待决定”，点击保存并分派给 QS。随后关闭、整页刷新、重新打开发现，两段文字、保存时间及“已分派”状态均回读一致；之后隔离候选重启仍保持。证据为 [刷新后 AX](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/exact-review-refreshed.ax.txt)、[最终人工复核截图](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/exact-human-review-final.jpg) 及下载 JSON。没有修改合同、确认采用或把显示编号作为报告身份。

### 11.3 实际卡点及最小修复

- 原件入口：canonical DOCX 走身份绑定直链，但原 `<a target="_blank">` 在 IAB 点击后出现 crashed tab，本页 download 等待超时，当时 Downloads 未见文件。非 PDF 原件改用原文件名的 native `download`，不新开 tab，三语显示“下载原始文件”；PDF 仍保留新页和物理页定位。canonical／fixed／ordinary reader 的权限与路由边界不变。修复后从双方引文旁各点击一次，实际下载 NTT／SCC，字节与上传原件完全一致；见 [原件回读收据](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/original-download-readback.json)。
- PDF 环境：首次网页导出显示 `NOT_CONFIGURED`，[失败截图](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/exact-pdf-not-configured.jpg)。只在 owned 候选启动参数绑定本机既有 LibreOffice 26.2.6.3 和独立 `ui-acceptance/pdf-work`，未安装软件或修改产品运行配置。两次 CUA 调用超时原样保留为交互未完成；不能据渲染目录出现 PDF 就算网页下载。稳定页面之后再次从可见导出按钮发起，实际收到 PDF 下载事件、文件路径及“已生成并发起下载”提示。
- 可读性：来源请求列表展开后仍直接暴露完整输入包 ID 的漏口已补；28 条状态、未知限制、必需／缺少计数直接可读，完整标识仅在各自可选技术详情中。实际展开请求列表的 [AX](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/expanded-requests-readable.ax.txt) 中完整 64 位 SHA 出现次数为 **0**。业务按钮没有放进技术详情；原件下载直接位于引文旁，见 [最终证据页](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/exact-evidence-readable-final.jpg)。

最终前端组合 **122/122 PASS、0 failure／skip**，含原 102 项组合与 20 项 source-purpose 控件；owned TEMP 下 production build（含 `vue-tsc --noEmit`）通过。全部 109 个 src 和 97 个 tests 在冻结验证前后字节一致，隔离 web 的 109 个 src 与该受测源码差异 0。见 [controls.log](../../../.local/java-baseline-failures-repair-20261024/ui-original-download-final/controls.log)、[build.log](../../../.local/java-baseline-failures-repair-20261024/ui-original-download-final/build.log) 及 [一致收据](../../../.local/java-baseline-failures-repair-20261024/ui-original-download-final/source-consistency.json)。这些控件回归不替代上面的真实点击／下载证据。

### 11.4 三种网页导出与实际成品

| 从网页实际下载的成品 | 字节 | SHA-256 |
| --- | ---: | --- |
| `ConSense_Vetting_Report_QA20261024B.docx` | 19,050 | `7fe70a09c65602e5b9fbc419f35db86c7075afc5711903d277ba3ac8b73c9563` |
| `ConSense_Vetting_Report_QA20261024B.json` | 883,332 | `1df50b4c6a3c5729646ab0092dd7f4d19be675fbfc377bd32092e35e9b6442bf` |
| `ConSense_Vetting_Report_QA20261024B.pdf` | 219,657 | `ce4e53c4d6019ff372c245eed9a3b8690d24b11907b22d44e31f147161964f97` |

副本保存在 [downloads](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/downloads/)。Word native XML／JSON 的独立 [核验收据](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/artifact-checks/browser-downloads-QA20261024B-20261024T013553Z.json) 确认双边引文、中文人工意见、实际处理说明、`Assigned` 状态及来源分母保持。JSON 中 `addendumRequired=null` 正确保留待决定；Word 没有显式输出“待决定”标签，不能声称该显示字段已导出。此项显示限制没有被解释为已经决定或已经采用。

PDF 的 pdfinfo／pypdf 同为 **25 页 A4**，Producer 为 LibreOffice 26.2.6.3。对真实下载 PDF 渲染全部 25 页 PNG，原 PDF SHA 前后相同；[PDF 收据](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/artifact-checks/browser-pdf-QA20261024B-20261024T0138Z.json)、[25 页总览](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/artifact-checks/browser-pdf-QA20261024B-contact-sheet-25pages.png) 和 [逐页渲染](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/artifact-checks/browser-pdf-QA20261024B-pages-120dpi/) 保留。Root 实际查看总览及第 2／24／25 页，独立审阅者逐张查看第 1–25 页，[视觉收据](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/artifact-checks/browser-pdf-QA20261024B-visual-review-20261024.json) 逐页记录观察。中文人工意见、付款双引文、分母及 unknown 限制可读，未观察到重叠、裁切、表格越界、缺字或异常空白。第 2 页人工备注列较窄、时间戳换行较多，保持原五列表式，不授予所有内容长度的排版通过。

Word 首页及 PDF 仍显示 `Not confirmed (adopted title/number unavailable)`，JSON 身份 authority 为 `present=false / confirmed=false / eligible=false`，mirror audit 为 `none_project_display_is_not_package_authority`。显示编号与原文标签一致，没有自动提升为已采用合约身份。报告正文主要沿正式英文表式，中文人工记录与警告保留，不把 UI 语言切换解释为模型／报告全文翻译验收。

### 11.5 最终源码、候选与交付边界

最终后端为第 10 节 `java-locator-final09` 的 **152/152 PASS**。Root 按原字节核对实际服务全部 **1,045 个 src 文件**与该受测快照完全相同，外部 SharedNote 原 fixture SHA 继续为 `cda3a7a07579fb7bd0a2e3cd39e20b7daac9f3c21b1c36ac95c018f17dfb4633`；[源码收据](../../../.local/java-baseline-failures-repair-20261024/final-java-source-identity09.json)。随后仅对该快照 `-DskipTests package`，不是另一次测试通过；候选 JAR SHA 为 `c63cc69bfd4b768f08b4ae28ee10657011236a4c94a7dc5ddf1249ba58ac7d70`。

隔离候选使用同一 owned H2／uploads 保留两个项目和全部旧记录，没有重置数据库；内存 vector、OCR disabled。渲染器绑定后候选后端 18186／PID 24620、前端 5187／PID 25200；[launch receipt](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/launch-receipt.json) 和 [启动脚本](../../../.local/java-baseline-failures-repair-20261024/ui-acceptance/launch.ps1)。正式产品 Java 18086／47460、H2 18082／32040、前端 5177／24568、OER 8874／43560 与 8875／44812、relay 8092／20084 保持原进程；OCR 18780／18771／18772 无监听。没有执行正式后端部署、停止其他服务、恢复 OCR 或真实项目重审；进程保持不是全库内容逐值保全证明。

本轮 Java 原 27 项已清理，但第 7 节另有 **19 项宽跑残余**在固定基线同样复现，仍未修复；`java-wide03` 也不是最终新增来源／citation 修复后的全仓回归。首轮审计 01／02／03／05／06／08／16 的 22 路径待办保留在原 raw backup 与 stash `f977f1fd79fff1d50776fe516b6dc1e937443b91`，本轮未恢复或冒称完成。源码按当前 main 规则提交／发布，最终四引用、tree 和清洁状态回读见忽略目录 `publication`，不把源码发布当正式运行部署。
