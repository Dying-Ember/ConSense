# GCC 原 PDF 65–70 页：实际 native 采用、投影及独立 registry

日期：2026-10-10，Asia/Shanghai。本文记录六个物理页的实际离线来源采用；不替代全 GCC 解析、模型质量、真实检索、法律答案、官方题目或前端业务验收。范围以 [OER 已确认范围与规则](../oer-scope-and-rules.md) 为准。

本次直接使用已经保存的最终 Java PDFBox export、source-only v2 reference／独立 glyph mapping，以及两套完整实际 parser CAS。没有重跑 Java／OCR／embedding／rerank／LLM，没有读取 2d／2e，没有启动服务、修改产品代码或触碰在线数据库。原件、既有双路输出及冻结代码保持不变。

## 冻结身份与证据入口

运行目录：`.local/state/oer-gcc17-native-adoption-20261010/run-213435-4a6db0/`。

- [compact validation receipt](../../../.local/state/oer-gcc17-native-adoption-20261010/run-213435-4a6db0/validation-receipt-compact.json)：8,501 bytes，SHA `5608fc3be4e5f730aa7ad21d589c20dd7b75866eceeb7c1e90f107610af9de99`。
- [final artifact ledger](../../../.local/state/oer-gcc17-native-adoption-20261010/run-213435-4a6db0/final-artifact-ledger.json)：SHA `5dce6fbf78ef285ae7698fd3375efeec7ffa46eba3d0cd24504016c8a2f6370c`；记录原始收据、实际执行 helper 与各输出文件的 bytes／SHA。
- [显式采用清单](../../../.local/state/oer-gcc17-native-adoption-20261010/run-213435-4a6db0/actual-explicit-adoption.json)、[来源复核 v2](../../../.local/state/oer-gcc17-native-adoption-20261010/run-213435-4a6db0/source-only-adoption-reference-v2.json)、[整单元边界账本](../../../.local/state/oer-gcc17-native-adoption-20261010/run-213435-4a6db0/whole-unit-source-boundary-ledger.json)、[typed line boundary 账本](../../../.local/state/oer-gcc17-native-adoption-20261010/run-213435-4a6db0/typed-line-boundary-source-review-ledger.json)。
- [internal projection](../../../.local/state/oer-gcc17-native-adoption-20261010/run-213435-4a6db0/actual-internal-projection.json)：76,595,333 bytes，实际完整 native／dual／reference／采用记录保留。
- [public projection](../../../.local/state/oer-gcc17-native-adoption-20261010/run-213435-4a6db0/actual-public-projection.json)：1,505,920 bytes，SHA `5041b479b6fdb59f669bac22caea14052019aa012b5a62820ef11c54d18d45c3`，小于 16MiB 固定 cap。
- 独立 `cas/`：742 个对象，412,516,758 bytes；独立 `registry/`：实际 public snapshot 与 native PDF revision hardlink。未与在线／既有 8872 registry 合并。

原件为 `2a_Standard documents/09_General Conditions of Contract.pdf`，658,169 bytes，74 物理页，SHA `d7d74db6d9aee390093b9e22bb30dccdbf3c0316d47635922109422278d161e7`。本次 export 只观察 65–70，原始 `encrypted=true`、`extractionPermission=false` 继续作为真实观察保留。

| 身份 | SHA-256 |
| --- | --- |
| 原始 native export，40,626,503 bytes | `b7a52b13b6764b0e3df2ac0be996595679c08930d9636e8d891d6da3c845d007` |
| native source revision | `4fc695edd06c98062cb556dca830b9c2d417a50a796878f195fae0a41a2d0cd8` |
| source-only reference predecessor | `0364edaff19ce8e7dbfbea06f029b69d817b494cd7d5b733b44b05aea57f68f5` |
| glyph mapping predecessor | `597ed14490144c9f602d40536b9ca71599f82ee75b52e87c17348d6c8bb468f7` |
| 本次 source-only reference | `2fa6e44feb1eef4f2af0cb61f266ccaef849ebc4277d3aa2533a3c25a2a626d0` |
| public sourceHash | `1ea94c62175162a3375a87ffd5eedde6b29a4e09bec6f696293429da778ea763` |
| public projectionRevisionSha256 | `21f109811e9cd532d06b1826c229728884d839071230935a682f61b55759d082` |
| public registry address／projectionSha256 | `391d0c4601ec3b7aa99aa03b26deb33038a16a31e4ef3f054567c25f8324dda3` |

采用的本地 documentId 为 `actual-gcc2013-standard-native-physical65-70`，role=`standard`，purpose=`reference_standard`，eligibleModules=`vetting,advisory`。这是隔离来源 preparation 身份，尚未在持久 Java 项目创建真实 DB documentId；接入时应以真实 ID 重新 produce／cold validate／register，不能原地替换已封印 public JSON 的 ID。

本次开始／结束时四个代码 pin 相同：projector `bf75da59…416`、transport `60c969f2…d23`、PDF registry `612c16b7…b2`、PDF server `b3fd27c0…5c3`，完整值见 compact receipt。server 未运行；随后 runtime coordinator 的新代码不能追认成本次 server 运行验证。

## 原页复核与采用方法

Codex 本轮目视复核六张实际 300DPI 原页，使用已有独立 mapping 将每个 region 的 glyph membership 绑定到最终 PDFBox glyph ID。mapping 已逐字／字体／坐标核对 earlier source-only 观察与最终 export；本次仍把 capture order 和 source-reviewed reading order 分开保存。

正文文本只来自实际 `glyph.unicode` 加显式 typed separator。原生空格、智能引号及每个 literal glyph 不做替换或归一化。236 个跨行 newline 都绑定连续的两个实际 glyph ID 和 `original_page_raster_boundary` 声明；账本保留实际 baseline delta。没有把 reference transcription 或任一 OCR candidate text 倒写回 native text。

原页的编号正文、标题、共享引言／尾句、引用规则和页边注进入显式采用。15 个空白／页眉／印刷页号区域的 glyph 仍在完整 `unadopted_unknown` inventory 和 raw CAS，未静默删除。

以下八组保持一个 whole text unit：

- 17.1(7)(a)、17.1(7)(c)：引言、四个子项及共有尾句。
- 17.1(12)：物理页 67→68 的完整续句。
- 17.1(14)：引言、(a)–(e) 和共有尾句。
- 17.1(16)：物理页 68→69 的续句与 (a)–(c)。
- 17.1(17)：引言、(a)–(d) 及 (d) 内引用的 Article 26.1／26.2；引用 Article 26 没有变成 GCC 26 条款。
- 17.1(18)：引言／proviso、(a)／(b) 与共有尾句。
- 18.1(1)：送达引言及包含嵌套子项的完整 (a)–(d) 列表。

17.1(5)(a)→(b) 是下一编号子项，未伪装成同一句跨页 continuation。其他实际编号正文与页边注保持独立单元；标题、父结构及侧栏关联显式保存，不发明缺少的父编号区域。

完整两套 parser block 作为独立 visual evidence：每个 adopted region 保存两套全部重叠 whole block；Paddle 99、MinerU 112 个 region/block associations。这些是与相同原页 raster 的实际坐标关系，重复 association 不等于新的独立 block 或页。两路的 input PNG SHA 都与 reference 原页 SHA 相同，identity transform 绑定实际原页图；选中的 Paddle layout 不意味着其 OCR 文字被采用或正确性得到认证。

## 可复算数量与冷验结果

数量来自 compact receipt 的 `counts`／`perPage`，原始成员可在 adoption 中重算。

| 物理页 | observed glyphs | adopted regions | adopted glyphs | unadopted glyphs |
| --- | ---: | ---: | ---: | ---: |
| 65 | 4,053 | 14 | 3,972 | 81 |
| 66 | 4,138 | 16 | 4,058 | 80 |
| 67 | 4,666 | 13 | 4,586 | 80 |
| 68 | 4,527 | 15 | 4,447 | 80 |
| 69 | 4,046 | 15 | 3,966 | 80 |
| 70 | 2,832 | 19 | 2,750 | 82 |
| 合计 | 24,262 | 92 | 23,779 | 483 |

107 个 reference regions 中采用 92，生成 50 whole units。23,779＋483＝24,262，全部 observed glyph 恰好记入 adopted 或 unadopted。保存 2 continuation、78 parent、6 qualifier、24 sidebar 声明，完整 incident relation indices 进入采用清单；continuation／qualifier 属于同一个 whole unit，parent 无环且无多重父节点。

实际 helper 完成 `produce_native_pdf`，随后 cold internal validation、public publish／cold validation、独立 registry publish／load、Vetting／Advisory exact chunks 读取及 registered native parent validation 均通过。每次 cold validation 从实际 CAS 重建，未依靠 hash-only metadata 或派生缓存。离线 `SourceUnits` 对 public 的 50 个 chunk 均返回 `complete_observed_unit`、每个成员数 1；这是已采用 canonical 范围的无缺口结构检查，不是全原件、条件完整性或法律质量认证。

最大 whole text unit 为 17.1(17)，2,933 字符；其后为 17.1(16) 1,551、17.1(14) 1,324、17.1(7)(c) 1,112、17.1(8)(c) 997。字符数未替代实际 tokenizer／pair budget，真实 BGE 运行须另记 token 和覆盖。

原始 validation receipt 保留 validator 实际返回的完整 internal／public JSON，78,107,887 bytes，SHA `b1e8141de86791677ff2dace71cdbcc519c5bc83c8f5cfbbbd54f0a084b2d965`。compact receipt 只以固定 file descriptor／验证状态替代重复 serialization；两份原实际 projection 逐对象相等核对，原始收据未覆盖或删除。

## 未通过的业务与质量门槛

- glyph operator 未被另一引擎重放，effective pixel visibility 未认证；semanticQuality／semanticScope／humanConfirmed／wholeOriginalSourceCoverage 保留 unknown／false。
- 全原件其余 68 页未观察；本六页也不代表项目已经采用 GCC 的全部标准条件或 SCC 编辑后的法律状态。
- 当前 `SourceUnits` 不自动遍历 between-whole-unit parent／sidebar 关系。本次保留这些来源关系，并将续句／列表条件／引用规则做成原子单元；不能把关系已保存说成祖先自动闭包已经实现。
- 当前 native PDF public transport 的 `assetRefs=[]`／`visualReturnStatus=not_published_by_native_pdf_transport`。实际 page PNG／whole parser resources 已在 CAS，前端图像 serving 尚未由此投影提供。
- 没有真实 BGE、rerank、MiniMax、Java HTTP、前端、H800 或官方问题调用；官方八题与正式 Vetting 报告本次新增 scored runs 为 0。

后续可使用这份实际 public snapshot 做隔离真实检索／业务接入，但必须分别记录新 backend runtime、真实 DB ID 重新绑定、LLM 输入、引用忠实性、回答／报告评分及以上 unknown 的传播。完整输入不能用低层新功能或参考答案拼凑来绕过未完成状态。
