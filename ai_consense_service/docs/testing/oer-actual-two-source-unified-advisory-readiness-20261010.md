# 实际 GCC／SCC：统一 Advisory 服务与只读契约核验

2026-10-10，Asia/Shanghai。本记录续接 [统一 Vetting 来源准备](./oer-actual-two-source-unified-vetting-readiness-20261010.md)。原 owned 8873 是独立 native PDF 入口，health 只有 `nativePdfCanonical`，不同时具有 SCC DOCX 消费者需要的 `nativeCanonical`。因此另建独立 owned `127.0.0.1:8875` 统一 Advisory 服务，供 Java 的 canonical snapshot／asset backend 和 Advisory retrieval；Vetting 保持 owned 8874。旧 8873／8874 及在线 8080／5173／8092／8871／8872 未切换或停止。

## 新 8875 身份与实际 GET 验收

绝对目录：

`D:\R&D\ConSense\.local\state\oer-unified-actual-preparation-20261010\run-222702-e68e99`

从工作区再独立冻结 132 个 Python／samples 文件，复制前＝复制后＝冻结副本，GET 后 SHA 再次相等。新 state 复制同一 actual CAS 的788对象／694,530,617 bytes，逐字核对相等；没有复制旧 registry、projects、index、Qdrant 或请求 journal。fresh registry 对实际 ID1 GCC public 与 ID2 SCC public 分别重新 actual-CAS cold validate／publish，72 parents＝50＋22。源 public address／bytes、原件及 source revision、质量未知、whole-unit 内容和 scope 均保留，与 Vetting 8874 使用的两份实际来源相同。

两份 source declared modules 仍为 GCC=vetting／SCC=advisory，eligible modules 都为 Vetting、Advisory。既有 shared-module gate 以 `module=advisory` 和 scope policy 实际通过，未从 producer 重新派生 module 身份，未手改 sealed public 或扩大模板准入。scope policy SHA 为 `bb1512de4b16bfa2c10f03a8197b0c798c105e3359fc649d35391f6fcb66e3d6`。

配置继续为 CPU FP32、offline、batch2、短512／overlap64、长 embedding2048／结构 rerank2048 strict、无 fixed cache，`targetModule=advisory`。本 run 未加载模型或 tokenizer，没有执行 index、retrieve、LLM、官方题或结构化报告。health 只确认配置及 loaded 状态，不是模型质量或实际神经吞吐。

端口空闲时 Hidden 启动：wrapper PID83508，CreationDate UTC `2026-10-09T22:28:05.0769290Z`；listener child PID8312，CreationDate UTC `2026-10-09T22:28:05.3260110Z`。final process receipt 核 PID／script／精确 UTC ticks 相等；旧 5173／8080／8092／8871／8872／8873／8874 的地址、端口和 PID 与启动前相同。

`health-probe-222810-678ecd/` 仅执行4个 GET：health before、GCC canonical、SCC canonical、health after。两份 actual public 对象、exact bytes 和 projection header 都相等；scope、target module 和全部 algorithms SHA 与本次 freeze 相等。health 同时声明 scanned PDF、native DOCX、native document collection、native PDF、unified 五层能力，本次实际来源读取只覆盖 native PDF／native DOCX 两种。embedding／reranker／OCR 前后均 `loaded=false`，projects／run-evidence 为空、Qdrant 不存在。此 8875 probe 没有失败或重跑。

| 文件 | SHA-256／bytes |
| --- | --- |
| `runtime-manifest.json` | `f88b1c538344abbf6522faf924306eadf95706d68c9e85caaf108e379cbbf9e8`／45,222 |
| `health-probe-222810-678ecd/health-canonical-receipt.json` | `c74a5c1eea35a70ce70eed373a834e7d76f1ad5fbc03aa1e914deb1e5a6a0570`／4,846 |
| 同目录 `capabilities-receipt.json` | `89a89e22c4bda614872e04943169ed4762c8b27158f604043a6c14a1ff53c1d4`／7,108 |
| 同目录 `health-after-response.json` | `766ccea50075991c48fc4ac9281c6e3cc09eb5de7baa6f7c4daf0d294cf02c18`／11,150 |
| `process-final-receipt.json` | `d59161ae508c9d36978875d9d30618255ba0cab8eaac5363821be826d00150a9` |
| `final-artifact-ledger.json` | `4430a7d80b9828b1c981de7461c655ce7113d62605891082b8940fd5100f380b`／6,916 |

已向 business bridge 交付的隔离 Java 配置方向为：

```properties
consense.oer-evidence.base-url=http://127.0.0.1:8875
consense.oer-evidence.retrieval-base-urls.advisory=http://127.0.0.1:8875
consense.oer-evidence.retrieval-base-urls.vetting=http://127.0.0.1:8874
```

`CanonicalEvidenceProperties` 明确 snapshot／asset 读取使用 `baseUrl`，retrieval 则按 module 选 URL。此处是已验 OER 服务及明确交接的配置，不追认 Java 启动、内部登记或业务模型调用已经通过；相关事实由 bridge 的后续独立收据确认。后续本代理只读跟踪 owned runtime，M3 由 Java 执行，不能重复直接触发模型调用。

## GCC 原图 SHA 字段的实际只读核验

Java 实际 register 曾报 `CANONICAL_NATIVE_PDF_LAYOUT_INVALID`：strict targetFrame 六键不接受真实 public 保留的第七键 `originalPageRasterSha256`。root 独占产品修复；本代理不改 Java、不重投影、不改 public seal，只读检查 Python producer／原始 geometry 和来源绑定。

实际 GCC public 仍为 address `94e151f2d3e4ebfd4442305181c974c52f1bf5d901f23b9df166dda2585176c6`、文件 SHA `f0f42df3b3d96ed7a77620b9e5161fbbf5bbd9f1ec59b04122f75e34c09b7b85`。完整 actual-CAS cold hydrate 后逐一检查211个 layout associations：每个 targetFrame 都等于原 `originalPageTransform.targetFrame` 的完整深复制；从实际 run／page／whole block 重算 `project_bbox_to_original` 对象完全相等；第七键逐项等于 source-reference PNG SHA、part.sourceReviewAnchor.renditionSha256、rasterMappingReview.referenceRasterSha256。源 SHA、物理页、pixel width／height、transform SHA 和输入 frame 也相等，6张原 PNG 的 CAS exact bytes／PNG header dimensions 重新核验通过。

211条实际 raster relationship 全为 `same_original_raster`，本次每条 inputRasterSha256 也等于原图 SHA。这是实际这批关系，不是放宽 affine／未知原图规则。public、registry 和旧 index metadata bytes 未变，HTTP／模型／index／LLM调用为0。

只读证据目录：fresh bridge 的 `canonical-gcc-binding-220705-103443/layout-readonly-audit-222319-00c08a/`，receipt `layout-raster-binding-audit-receipt.json`，4,067 bytes，SHA `b7d1213dcb6db771e9972c0ff6d73d8db22ac517742567995b036cda1e723726`；逐211条 pointer ledger 同目录保留。首次 helper 在 cold hydrate 后误把 source-reference page 当作直接 `physicalPageNo` 字段而发生 KeyError，实际页号位于 `sourceImage.sourceRelation.originalPhysicalPageNo`；首轮 helper／Java observed snapshot／failure receipt 保留在 `layout-readonly-audit-222218-09365b/`，新轮仅修 helper 的 schema 路径。

Python geometry producer保留全部 targetFrame，已有标准 source/page/input/transform 和 source-reviewed raster relationship 门控；并不单独解释这个 optional 原图 SHA 为有效视觉证据。Java 的修复可以只允许该命名可选 SHA，出现时验证 SHA 和 anchor／review 等式，其余未知键仍拒。原图绑定通过也不确认 glyph 实际可见、精确墨迹范围或法律意义。

## GCC 关系审核 metadata 的实际只读核验

Java 后续 strict relation 字段清单还漏认源审核 metadata。110个 actual public relations 对 actual source-only review CAS 原 relation 精确重放，并重算追加的 fromUnitId／toUnitId／referenceRelationIndex／status／scope 相等；完整 index 清单、owner 和 whole-unit 关系保留。

| 元数据 shape／类型 | 实际数量及范围 |
| --- | --- |
| `sourceReview`＋`sameParentClause` | 2条 continuation，分别17.1(12)、17.1(16)跨页；sameParentClause与两端 source region clauseId相等，成员属于同一 whole unit |
| `sourceReview`＋`basis`＋`humanConfirmed`＋`reviewActor` | 108条：parent78、qualifier6、sidebar24；humanConfirmed全为实际 boolean false，reviewActor全为codex |

`sourceReview` 为非空文字两种，`basis` 为非空解释五种；未包含模型评分或 gold。来源 boundary／层级、共同尾句、17.1(17)(d) 内原印 Article26 引用／替换规则、侧栏视觉关联分别保留原解释。侧栏 basis 明确“不是 operative rule”，不产生合同义务或新 GCC26。

证据目录：fresh bridge 的 `canonical-gcc-binding-220705-103443/relation-readonly-audit-222556-4a3187/`，receipt `relation-metadata-audit-receipt.json`，3,997 bytes，SHA `68a1aebe149818f4ef72cedbfb1b44eab044c44835715c301664a193c58d8165`；完整 relation source replay ledger 同目录保留，引用前述 whole-public cold hydrate receipt。无产品／public／DB修改或模型调用。

root 已确认采用 typed 原文描述保全：只承认命名 metadata 的类型，不把 optional sourceReview／basis／sameParentClause 文字升级为新的法律语义门控，也不对未来参考文字 hardcode 本轮日期／措辞；`humanConfirmed` 不允许 true／字符串冒充 boolean，unknown metadata 与已有索引、owner、whole-unit闭包、循环、scope、status和type门控继续严格。关系 metadata 是审核描述，Python source-reference core只验 type／endpoints，native producer精确复制并核图结构；不能由 metadata存在推断完整法律引用闭包或完整合同采用。

## 尚未完成的验收

8875当前只完成能力及 exact source wire 准备。GCC只采用65–70六页，SCC只采用17的部分单元，源质量／有效编辑／完整法律依赖仍未知；GCC未发布 native visual assets，SCC仍无 verified visual rendition。此记录不宣布 Java登记完成、任何官方题答对、Vetting报告评分通过、权重微调或H800部署完成。后续必须以新的实际 Java run、真实 OER journal、M3 output、引用回读及明确分母的评分分别报告。
