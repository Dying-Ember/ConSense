# Canonical 审查报告覆盖说明回归

2026-10-10，Asia/Shanghai。实际 G305 审查报告暴露两处通用展示问题：全局 canonical 覆盖说明声明只处理已采用观察，但语义审查末尾仍追加“规则检查对全部可解析招标合同正文运行”；英语及繁中导出未翻译新的 canonical 总声明与每文件分母说明。

本轮只修这两处系统说明及新英译句间空格。范围遵循 [OER 规则](../oer-scope-and-rules.md)；此记录不扩大模板、计价文件或审查对象范围。

## 最终行为

- `VettingSemanticReview` 依据现有 canonical 模式，说明规则仅对已采用的 canonical 招标合同观察运行；legacy 路径保留原有正文扫描说明。局部窗口、其他限定未知、未进入模型的切片未完成语义审查等声明继续保留。
- `VettingReportWriter` 仅为三条已知系统说明补英语及繁中翻译：全局 canonical 范围、每文件已采用观察分母、规则扫描后缀。新英文后缀与前句之间有空格；源文件名称、原始引文及模型评论仍沿用原有处理。
- `VettingService.coverage` 已使用 canonical 分支，字符数为已采用观察的 UTF16 范围并集，既不计原件全文，也不把 unknown 改为确定；本轮未修改该文件。

## 测试接口与数据

父任务指定测试接口为现有 public `VettingSemanticReview.review` 返回的 warnings，以及 public `VettingReportWriter.docx/pdf` 返回的实际字节。新增 `VettingCanonicalReportCoverageTest`：一项使用已有 authored canonical wire 及声明的模型响应验证实际语义汇总；三项语言参数验证 DOCX／PDF 的覆盖限制、unknown、文件身份和 `7/19` 片段、`123/777` 字符数。模型与外部 registry／检索传输在本测试中为 fixture 边界，不是线上模型质量测试。

DOCX 以 POI 读取实际字节；PDF 以 PDFBox 读取实际字节。英语、繁中及简体 canonical 警告页另用 PDFBox 渲染为 PNG：英语物理第 18/18 页，繁中与简体第 21/21 页。英语／繁中页已目视检查，文字可读、无裁切。该 QA 只覆盖警告所在页，不声称整份报告或业务发现已验收；DOCX 未经 Word／LibreOffice 分页验收。

## 失败与最终通过

| 证据标签 | 实际执行结果 |
| --- | --- |
| `slice1-red-actual` | 1 项，1 失败；重现 canonical 汇总仍称扫描全部原件正文 |
| `slice1-green` | 1/1 通过 |
| `slice2-red` | 3 项，英语与繁中 2 失败；简体通过 |
| `slice2-green` | 新增用例合计 4/4 通过 |
| `related-suite` | 6 个相关 suite，59/59 通过；当时尚无英文句间空格断言 |
| `english-boundary-red` | 3 项，英语 1 失败；重现 `review.Rule` 紧连 |
| `final-related-suite` | 59 项中 1 失败；补空格已生效，断言错误地要求 PDF 换行后的 `Rule checks` 同行，实际导出保留正确空格；原失败保留 |
| `final-related-suite-v2` | 把断言改为允许正常 PDF 换行后，最终 59/59 通过，0 skipped |

最终 suite 分项：`OerBusinessEvidenceTest` 11、`VettingCanonicalBusinessIntegrationTest` 6、`VettingCanonicalReportCoverageTest` 4、`VettingReportWriterTest` 12、`VettingSemanticReviewTest` 20、`VettingServiceTest` 6。每项来源是最终 Surefire XML；合计 59。

测试启动另外保留两次环境失败：Maven Launcher 缺 `maven.multiModuleProjectDirectory`，以及本机 `R&D` 路径经 Surefire／cmd 子进程时无法正确启动 Java。二者尚未执行业务断言；采用直接 Java 参数启动 Launcher、专用 socket tmpdir、`forkCount=0` 后执行上述回归。未改仓库 pom、OS、网络或在线服务。

## 构建与证据身份

全部 Java 编译／测试位于 ConSense 根目录下忽略的 `.local/state/vetting-canonical-report-fix-20261010/run-042334-d5f370/checkout`，只复制当前 `pom.xml` 与 `src`；没有写在线 `ai_consense_service/target`，没有服务重启、真实模型调用或业务 POST。

`run-tests.ps1`、各阶段原始日志／Surefire XML、三个语言的 DOCX／PDF／抽取文本、警告页 PNG、任务开始前快照及两个产品 diff 均保留在同一 run。`final-build-source-manifest.json` 逐件记录实际测试 checkout 的 `src`／pom 身份；`final-product-manifest.json` 验证下述文件与复制版字节一致。

| 文件／证据 | SHA-256 |
| --- | --- |
| `VettingSemanticReview.java` | `0cd875a2a79610291863085c06c3ba92f200d2eccb1be2995be7633de5fb4ce4` |
| `VettingReportWriter.java` | `1d514c08a260a880a6fe9aacb81a2ff6a0a03c08b4ff918a2132c47dbb88f523` |
| `VettingCanonicalReportCoverageTest.java` | `de1917b0fe10e9a02d04ebce5f8acdba0cb69c879fd51acfdef112b22b4eed98` |
| `VettingService.java`（本轮未改） | `84c3bccd9748dc7e9a9697f5e5fa9a48b8e06304167c76187ea9bbf074cfd52e` |
| `final-build-source-manifest.json` | `f55b6a778884fe266468f68cab1f798bc9b17ae77d637b838735574eda7eac6a` |
| `validation-receipt.json` | `2df6344cd92b8e3b6fd9689e0a45fb7738a2cb9081c9488565b40f37283fe61c` |

独立 receipt 同时记录六个导出文件、抽取文本及 PNG 的实际字节数与 SHA。检查通过只表示该精确复制版本的契约／导出回归通过；在线服务重新部署、真实 G305 新版导出及前端下载验收仍由后续业务验收独立记录，不能由这里追认。
