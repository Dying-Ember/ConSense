# Drafting 三份原件专项回归及真实 PDF 门

2026-10-20；独立 H2、固定原件、独立冻结源码和 target、本机 LibreOffice 26.2.6。这个结果属于代码／来源格式／本机渲染验证，不代表真实项目采用或 Microsoft Word 验收。

## 冻结 Round04b 完整批次

源码快照 SHA256 `ea41b4b1b515797a387fad7fa157073bca39ee4e76f7a5e9ba14698a8afeadd3`；16 GiB 测试 JVM；02:41:25 至 03:19:34，耗时 2,289.538 秒。

| 套件 | 执行 | pass | fail | error | skip |
|---|---:|---:|---:|---:|---:|
| DocxNativeLayoutGuardTest | 5 | 5 | 0 | 0 | 0 |
| DraftingArtifactIntegrationTest | 28 | 27 | 1 | 0 | 0 |
| DraftingReviewSourceIntegrationTest | 7 | 7 | 0 | 0 | 0 |
| DraftNttWholeClauseFurnitureTest | 3 | 3 | 0 | 0 | 0 |
| DraftOrderedListBoundaryRegressionTest | 13 | 13 | 0 | 0 | 0 |
| DraftTemplateGraphServiceTest | 32 | 32 | 0 | 0 | 0 |
| **合计** | **88** | **87** | **1** | **0** | **0** |

原批次状态为失败，保留不改。原始 JUnit XML 和日志均在 `.local/e2e-20261020/build/round04b-drafting-project/target/surefire-reports/`、`build/round04b-drafting-maven.log`。逐套、逐 case 状态、文件散列及原 Round02 的 11 个 Drafting 失败对应验证，见 `drafting/round04b-specialty-summary.json`。原 11 项现在均有正式通过的对应检查；BS 适用性旧合并测试按最新 NSC／BSSSC 两者均可保留 BS 工种的规则拆成两个测试，映射在汇总中明确记录。

## 新执行的 fraction 测试与独立修正

唯一失败为 `theSourcePreliminariesFractionStaysOnOnePdfPageAfterAdoptedBranchRemoval`：该 case 在 Round02 因未配置显式 renderer 而跳过，Round04b 才实际执行。失败发生在 PDF 门之前的原生 XML 断言。断言只去除了已允许的外层行 `cantSplit`，遗漏了生成文档为 16 个非空源段落加入的 CS 绑定书签。

独立调查保存了原行、输出行、首个差异和逐项归一化结果：只去除这 16 对新增绑定和已断言的单个 `w:trPr/w:cantSplit w:val=1` 后，整个 fraction／pricing 行原生 XML 完全相同。没有发现公式、文字、格式或其他原生节点损失。

测试修复先由固定原件 SHA 和每个原生段落 ID 计算、核对全部 16 个绑定名，随后调用既有严格比较 helper，仅去除完整、唯一、同段落且不碰原件既有标记的新增 CS 对，再保留原有的整个行逐节点精确比较。没有修改生产代码或放宽编辑安全门。

独立 Round04c 从同一 Round04b 冻结树派生，只覆盖这个测试文件，不包含 Round05／06。快照 SHA256 `903ae815825b53857d76da309c8fe6a7bc8126a1654115d3573a345e3d13eda2`；正式结果 **1/1 pass、0 fail、0 error、0 skip，BUILD SUCCESS**。这次独立通过不把之前的 88 批次改写为全绿。

实际产物及验证：

| 项目 | 证据 |
|---|---|
| SCT 原件 SHA256 | `7def01d62e07b35dd86beeff995e0e37d73ce18adca2746e2a6f6d9bafb5aaa4` |
| DOCX SHA256 | `acdddc0750e698c3b0f96d9f721280619cd19fcca7349d00e5bef7829b462320`；与原失败测试生成的 DOCX 字节相同 |
| 实际 LibreOffice PDF SHA256 | `8a99476774cbd69ad24ad5b5dc6c1ab19b5f5b678ec227eeb0ce188d07c53eb7` |
| PDF 门 | 原生分子 `Total of Bill No. 1` 和分母 `Amount of Builder’s Works` 均在物理第 13 页 |
| 视觉检查 | 渲染并查看第 13 页；Table 1、分数线、分子分母及后续定义清楚可读，未观察到重叠或裁切；源印刷页脚为 SCT/18，没有重编号验收声明 |

产物位于 `build/round04c-fraction-evidence/`；调查和页面 PNG、视觉核验位于 `drafting/fraction-row-round04b/`；各路径均相对 `.local/e2e-20261020/`。PDF 页面及原件内容属于忽略的本地运行证据。

更早的 4 GiB Round04 尝试实际发生 OOM 后中止，状态为 `aborted_after_observed_oom`，不计完成或通过。其日志、精确进程归属、终止证据以及 Windows 原生 wrapper 误报 exit 0 后的状态修正均保留。上述 16 GiB 完整批次和独立 PDF 复测有独立的真实结束状态，未覆盖早先失败。
