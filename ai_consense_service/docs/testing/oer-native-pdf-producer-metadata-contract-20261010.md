# 真实 Native PDF producer 元数据契约修复

2026-10-10，Asia/Shanghai。此记录属于真实 GCC／SCC 样题链路的工程准备，不是官方回答或合同判断成绩。未修改冻结 producer public、reference、adoption 或 CAS；没有删除元数据来绕过验证。

## 原场景与最小反馈

来源项目为 `official-q16-source-90602e8f-85b8-4e1a-ad61-2625c654115e`，实际 GCC documentId=1、SCC documentId=2。真实来源上传及持久重开见 [LOB 验证](./oer-native-evidence-lob-persistence-20261010.md)。修复 readTimeout 后，第二次登记仍在 GCC 返回 `CANONICAL_NATIVE_PDF_LAYOUT_INVALID`；失败保存在 `.local/state/oer-official-source-business-bridge-20261010/run-060145-06ba7b/register-061644.log`。

同一原封 GCC public（1,503,684 bytes，SHA `f0f42df3b3d96ed7a77620b9e5161fbbf5bbd9f1ec59b04122f75e34c09b7b85`）直接离线进入 Java 声明契约失败。仅在内存诊断副本移除 211 处 `targetFrame.originalPageRasterSha256` 后，错误移到 `RELATIONS`；再仅在内存移除关系附加元数据，声明校验通过。这些诊断副本没有保存、出版、重新 seal 或登记。完整诊断 SHA `75946507b948428c62c9643800812d749d44576ac04a5c3ab179ffd1f576e8de`。

独立只读 cold CAS 审计确认 211 个 raster 字段均与实际参考 PNG、part anchor、review reference 和原 transform target 一致；六页 PNG 的实际 bytes、SHA 和尺寸均一致。110 个 relation 均等于原 reference relation 加由 producer 计算的 owner/index/status/scope：2 continuation、78 parent、6 qualifier、24 sidebar。附加字段为 110 个 `sourceReview`，108 个 `basis`／`humanConfirmed=false`／`reviewActor=codex`，2 个 `sameParentClause`。计数是本次 GCC public 的结构事实，不是语义正确率。

## 最小产品修复

`NativePdfCanonicalContract` 接受一个可选的 `targetFrame.originalPageRasterSha256`。出现时必须是合法 SHA，且等于 `rasterMappingReview.referenceRasterSha256`；后者继续与 part 的 `sourceReviewAnchor.renditionSha256` 相等。缺省时保持旧 producer 契约。未知 target 字段、错 SHA、null、错误页／源／几何／变换、不同原图身份仍拒绝。

关系允许五个已知的可选审阅元数据字段：`sameParentClause`、`sourceReview`、`basis` 是非空文本；`humanConfirmed` 出现时只能是布尔 false；`reviewActor` 出现时只能为 codex。它们保持描述性来源说明，不新增语义、人审或像素质量证明。原 relation type、scope、owner、完整闭包、region membership、reference index 及 envelope/chunk 逐值相等检查保留，未知字段仍拒绝。Java 只校验公开声明；实际 reference／CAS 原字节绑定仍由 Python 冷读取校验。

## 回归与真实原字节重放

全部 Java 编译／测试在复制 checkout 中执行，在线 `ai_consense_service/target` 未写入。

| 阶段 | 结果 | 收据 SHA |
| --- | --- | --- |
| 旧源码最小 red，`red-062456-f8ab28` | 2 tests／2 failures，实际 LAYOUT 与 RELATIONS 模式 | `452e7ae5d44af2bfbdd9b3a537e0f184a1e438565d4471c32bd06c8b639158f4` |
| 修复后，`fixed-062542-20a3da` | 10 suites／116 tests，0 failure/error/skip，44.358 秒 | `afd597f71a0ecd751ac5d854b6e6e3178987aae5c0f72788d32bcea3cb1f74f6` |
| 独立审计后加强负例，`fixed-063003-39c26f` | 相同 main，29 focused tests 全绿，21.476 秒 | `af58eb5fca91b9fc0b6869fd8ffbcfbde68c24fcd91cbe2592f4b18d5110bede` |
| 未改 GCC／SCC 原封 public 重放 | 两份完整 `CanonicalEvidenceService.validateSnapshot`（raw seal＋声明）通过、返回树逐值一致 | `dc220c5724f7d94be404c1108fe53bb343e764b022b79758315552a3d3e3e46e` |

测试目录为 `.local/state/oer-native-pdf-producer-contract-20261010/`；原字节重放收据为真实 source run 中 `actual-two-source-wire-fixed-contract1.json`。固定 main 源码 SHA `bb1cd93294d6b0b44ea39534c7e71c6686c95d235397f17f63288eda39f91c2e`。116 与此前 58／87／151 的回归有重叠，不能相加。

独立审计发现最初关系负例只修改 envelope，可能由副本漂移门控代为拒绝。最终测试将非法字段同步到对应 chunk，分别验证 typed／false／actor 门控；另外独立保留只修改 chunk 的副本漂移负例。29 项 focused 重跑包含同一组 116 中的29项，不增加覆盖分母。`sameParentClause` 在 reference v2 尚无可依赖的语义定义，当前保留为原文字段；本次实际两例与17.1(12)／(16)相符的审计事实不扩为新的全局协议规则。

登记、持久服务读回、实际 Java→BGE→M3 问答、Vetting 报告、引用 URL 回取分别是后续门槛；上述通过不升级它们的状态。GCC 仍只采用物理页65–70，原生上传仍 PARTIAL；未知视觉／法律采用及 `humanConfirmed=false` 保留。其余官方问题必要来源、原图 serving、Linux 冻结和 H800 实测仍待完成。
