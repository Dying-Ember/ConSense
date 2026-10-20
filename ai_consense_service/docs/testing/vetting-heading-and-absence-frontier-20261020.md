# Vetting：Mini06 编号行及缺失目标结论的只读边界

本次调查保持 Round07 冻结实现不变。Mini06 的实际输出曾引用 `3 Clarifications` 作为第二处依据；原件及实际 wire 均未声明该段为标题，不能仅凭编号、短度或词语把它改判为纯标题。当前调查没有证明原生观察、canonical 或模型投影丢失标题信号。

本文件是后续问题的证据边界，不属于 Round07 修复白名单，不代表新增规则已经实现或业务验收通过。

## 实际记录及其原有状态

- 项目：`e2e-vetting-mini-r02-20261020`。
- Mini06 run：`46b98ae7-4215-4935-86fb-cdf8ee533a14`。
- source snapshot：`730661c6431068f7c73a9c3e2cdbd74a7779b3148e4d3dccbf2726afb98a44fc`。
- 原件是此前实际上传的受控 E2E fixture `e2e-project-SCT.docx`，SHA256 `121fe9d141e684decffbbff870a62203146047f2b1be223d317483ae96821b9f`。它用于验证真实应用链路，不能代替正式比赛原件的质量验收。
- 记录来自 topic9、attempt1、原始数组 record1，标题为 `Cross-reference to non-existent Clause 99.8 within the Special Conditions of Tender`。
- 原始完整数组因另一条记录的标题超过 schema 上限而 `structured_output_invalid`。本记录单独的 schema replay 通过，仅证明结构合规；原始数组没有按条拆分采纳。本记录从未发布为 finding，原 packet 最终 `completed_empty`。

保留的实际 candidate 没有改写。两处引文分别是：

1. `The Tenderer shall submit clarifications in accordance with Clause 99.8 of these Special Conditions of Tender.`，实际 anchor `body/8/paragraph`。
2. `3 Clarifications`，实际 anchor `body/7/paragraph`。

candidate 的 `comment` 同时说目标“cannot be located in the supplied text”及“unverified and unverifiable from this packet”；`pendingFacts` 明确保留 `Whether a Clause 99.8 exists outside the supplied excerpt window (e.g., in a continuation page not provided in this packet).`。因此模型把“窗口中没有找到”与标题中的“non-existent”混在了一起。该语义问题仍需后续实际有效输出与完整 gate 复现；本次没有把结构失败的原始记录当作已采纳误报。

## 原件与实际模型输入对齐

原件 `word/document.xml` 的第8个段落原文为 `3 Clarifications`，其实际 XML 只有 `w:p/w:r/w:t`。该段没有 `w:pPr`、`w:pStyle` 或 `w:outlineLvl`，整个 DOCX ZIP 也没有 `word/styles.xml`。

实际解码后的 selected record 完整保留了这些状态：

| 位置 | 实际值 | 含义 |
| --- | --- | --- |
| `canonicalStructure.sourceOrder` | `7` | 原件第8个段落的观察次序 |
| `canonicalStructure.kind` | `source_block` | 没有已确认的标题语义 |
| `parts[0].readingContext.nativeDocxParagraph.status` | `outline_not_declared` | 原件没有声明 outline |
| `paragraphStyleId` / `styleChain` | `null` / `[]` | 没有可解析的已声明段落样式 |
| `nativeDocxOutline.ancestorOrdinals` | `[]` | 没有已声明的标题祖先 |
| `readingContext.role` | `numbered_member_candidate` | 未确认的观察候选，不能提升为纯标题事实 |
| `readingContext.semanticStatus` | `unverified` | 语义仍待确认 |
| `clauseId` / `clauseHeadingLocation` | `null` / `null` | 没有按相邻位置杜撰条款归属 |

实际 source position 精确指向原件 `word/document.xml` 的 `p[8]`，source SHA 与上述原件一致，part SHA 为 `da543acff347624cd7d9e250f85d65d89ef8c8a053029ea9bcdeb21c38464b95`。

调查还把原件的9个 body 段落与实际 topic9 选中的 tender rows 逐一对齐：`sourceOrder=0..8`、完整文本、source SHA、part SHA、原生 `p[1]..p[9]` 路径全部一致。因此，这份受控 fixture 的9个 body 段落全部进入了该实际 packet。这个事实只覆盖该 fixture，不能推导任意产品检索 packet 已覆盖全源或完整条款。

## 当前代码证明的范围

`VettingSubstantiveEvidence.substantive` 排除独立 appendix/annex/schedule 标签和特定完整文档标题，不会因编号或短度排除 `3 Clarifications`。对保留的两处实际引文进行该 helper 的独立离线 replay，结果均为 `true`。这表明“被 helper 计数”与“语义上已经证明两条实质规范”存在距离。

当前 `VettingSemanticReview` 对 reference issue 要求至少两处不同的 located substantive anchors，再检查项目/标准的比较依据；同为 tender 的两处位置可以满足 `hasComparisonBasis` 的位置数量条件。代码注释明确禁止把本地未检索到目标直接推成 reference issue；system prompt 也明确写有 `an omitted target is unknown`、`Missing context is not proof of a defect`。本次未运行完整 admission pipeline 来证明这条结构失败记录能够成为 finding，不能把 helper replay 写成完整 gate 或生产误报验收。

“窗口中未找到99.8”是否足以支持全源缺失结论，仍受材料范围与完整性证明约束。本 fixture 可以独立复算全部9段覆盖，但实际 generic 模型输入没有由这两处引文自动建立全源负向证明。不得将源序齐全、两个 anchor、`candidate_unverified` 或 `semanticStatus=unverified` 解释为法定适用性或全源穷尽确认。

## 后续可重放 RED 的前提

如果继续修复，需要先找到明确的首个失真层，再做独立于07的最小变更：

- 对标题分类，优先使用原件确有的 paragraph style、outline 或明确的原生结构声明。当前实际行没有该信号，不能以本例单独建立“所有编号短句都是标题”的 RED，也不能改掉已有“编号规范正文仍可作依据”的回归保护。
- 对缺失目标结论，保留本次完整 candidate、实际 source packet 和范围元数据；后续可以通过完整真实 gate 的离线 fixture provider 重放一个结构有效的原样候选，分别检查只包含引用窗口与确有完整源范围证明的情形。必须明确此类 replay 使用保留候选的独立调用，不是改写原运行结果。
- 若无法从现有结构证明纯标题或全源缺失，应保留 `unknown` / `insufficient_context` 边界，继续其他已具备证据的修复，不采用长度、编号、关键词白名单或源特定规则来制造通过。

本次只保存了证据与后续验证前提，没有新增生产规则、没有修改 canonical 语义、没有调用模型、数据库或应用 API，也没有重新解析原件。

## 本地证据及复算入口

原件、完整模型输入和运行证据均保留在忽略的 `.local` 目录：

- [Mini06 原始终态响应](../../../.local/e2e-20261020/vetting/mini06/008-terminal-run-before-any-export-response.json)。
- [原始完整数组的 production schema replay](../../../.local/e2e-20261020/vetting/mini06/actual-schema-replay.json)。
- [实际 candidate、两处完整 selected records 和 helper replay](../../../.local/e2e-20261020/vetting/round08-heading-source-investigation/mini06-heading-complete-metadata-replay.json)。
- [实际 topic9 完整解码 source packet](../../../.local/e2e-20261020/vetting/round08-heading-source-investigation/mini06-topic09-complete-decoded-source.json)。
- [原件 native XML、样式缺失事实及9段逐项对齐](../../../.local/e2e-20261020/vetting/round08-heading-source-investigation/original-native-paragraphs-and-styles.json)。
- [实际原件 document.xml](../../../.local/e2e-20261020/vetting/round08-heading-source-investigation/original-document.xml)。
- 离线 helper 为 `.local/e2e-20261020/vetting/Mini06HeadingEvidenceProbe.java` 和 `extract_mini06_heading_native.py`。首轮 native extraction 假定存在 styles.xml 而发生 `KeyError`，此实际缺失及续跑过程已在 native receipt 中明示；完整 wire replay 在该异常前已完成，未覆盖原始证据。
- [Round07 正式构建收据](../../../.local/e2e-20261020/build/round07-build-verification.json)单独证明51 suites、808/808及冻结源/JAR身份，不关闭本文件记录的语义边界。
