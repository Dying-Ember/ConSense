# SL 技术规范解析、嵌入与检索质量现状

日期：2026-10-21。结论：已有单版开发基线和真实局部验证；七版本全库质量未验收。归档、恢复、哈希、软件测试不计作来源准确率或检索召回率。本次只读核对现存收据，没有新解析、OCR、嵌入、索引或模型调用。

## 解析实际测过什么

历史比赛 Batch 2 单版的技术范围是 3,174 页，拆成 151 个 worksections／2,836 页、7 个附录／164 页及 21 个必要 shared 单元／174 页。shared 包含技术封面和目录，不能全当技术条款。

151 个 worksections 的独立原生文字诊断已真实完成：2,726 页的非空白字符序列一致，另 110 页字码数量和组成相同但顺序不同。这证明有限的文字保全，不能换算为 OCR 准确率、读序准确率或完整条款正确率。原 normal 中的 failed／OCR-required 标记保留。实际终态为 `complete_full151_independent_native_diagnostic_not_visual_quality`，`CERScored=false`、`wholeSLQualityAccepted=false`。

完整保存的 754 个物理表父是资产库存，不能当 754 张正确源表。历史选用的 COM11 五张无框表 HTML 候选有限源评为结构 654/672、格文字面 418/672、完整字面表 2/5；这是旧候选的结果，不能代表后续候选始终只有 2/5。另一个独立 native 表格库研究的六条路线，严格完整单表当时均为 0/5；不同 provider／评分域及后继轮次不能拼总分。

后续 R116 已对三种方法各自保存的完整 226 页产物做关系回放，在同一组已标注 COM11 五表的 672 格、12 个表外成员、87 个关系上重新源评。本次直接重读其实际收据：

| R116 方法 | 正确主格字面 | 完整主表 | 完整表外成员 | 正确 typed 关系 | 完整已标来源组 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Camelot Stream | 672/672 | 5/5 | 12/12 | 87/87 | 5/5 |
| Camelot Network | 650/672 | 4/5 | 12/12 | 75/87 | 4/5 |
| pdfplumber lines/lines | 0/672 | 0/5 | 0/12 | 0/87 | 0/5 |

Stream 的完整来源组由 4/5 改善到 5/5，新增项为原物理页 1316→1317 的 Note 9 续接；1321→1322 的已闭合列表反例仍拒绝。旧 32 个已评 fragments、原来源对应、分母与旧正确项保持；33 个作者控制及独立错来源、错 bbox、错 glyph、逆向页负控已执行。这证明该局部开发域的来源结构确有改善，不能称整库、盲测、法律归属或当前运行产品通过。收据仍明确 `sourceQualityFinalAccepted=false`、`candidateAdopted=false`、`humanReviewed=false`、`blind=false`，本轮没有重新抽取 PDF 或调用模型。

实际证据为 [R116 完整源评分收据](../../../.local/state/oer-quality-adjustment-20261013/scorer-03/candidate226-score-receipt.json)、[完整评分明细](../../../.local/state/oer-quality-adjustment-20261013/scorer-03/actual-candidate226-score-01/complete-condition-group-score.json)（SHA256 `cf6686f6852e49c37c67ab315657919e86a6e56fc296a84904a875e66275177f`）与 [producer 独立复核](../../../.local/state/oer-quality-adjustment-20261013/scorer-03/producer-independent-review-receipt.json)。

候选成果与当前正常链的集成另列：[当前首个损失层审票](../../../.local/sl-com11-note9-current-chain-candidate01/first-loss01.json)（SHA256 `408af9eb7b0264c6f522c0e79f23876e1829f8e3553ffc3a7a1edabe91dfb20f`）绑定了已存 normal、原件／章节 SHA、页映射、实际 block／文字／bbox 与当前五处代码身份。现存 `sl-018/complete-normal.json.gz` 中，章节局部页 19（原页 1316）的主表及 Note 1–9 已保留，Note 9 下一页的 LM／CO／LL、RS 和 “For other areas” 续文也已被 ordinary native 读到，但未连回上一页表格的完整证据组；局部页 20 本身没有 logical table。当前沿既有 Python capture → Java method group → model material 接缝准备最小修复，尚未通过新的实际回归或产品验收。跨页表头、折行字段、条件范围、cell 几何与条款归属的全库质量仍未验收。

来源：[固定资料开发基准](../fixed-oer-development-baseline-20261012.md)、[无框表实测](../oer-borderless-table-isolated-investigation-20261012.md)及[实施交接的全151原生文字诊断](../HANDOFF-ocr-embedding-rerank-20261008.md)。本次实际重读终态：`C:/Users/Administrator/.codex/local-state/consense-oer/sl-body-source-native-diagnostic-20261012/prepare-01/root-actual-full151-01/terminal-receipt.json`。

## 嵌入与检索实际测过什么

历史 fixed05 已真实使用 BGE-M3 在 CUDA FP16 编码、FP32 归一化和持久化：19,371 个观察父、20,700 个向量窗口。该分母包含 GCC／GCT／单版 SL，不是 SL 单独向量数，更不是七版本已全部完成。实际索引收据为 `completed_persisted_development_baseline`，同时明确 `finalQualityAccepted=false`。

已封已知内容的八题回归中，SL 自身有四题：

| 实际问题 | 原物理页 | 目标排名 | 实际验证 |
| --- | --- | ---: | --- |
| fixing holes | 1137 | 1 | 完整观察父、parts、来源指针回取 |
| pile NDT | 2744–2746 | 1 | 完整观察父、parts、来源指针回取 |
| RFID precast facade 表 | 1312 | 5 | 完整表父、HTML 引用、132 origins 回取 |
| RFID volumetric VPB/VPK 表 | 1330 | 2 | 完整表父、HTML 引用、144 origins 回取 |

这些是真实神经检索请求，没有按目标 ID 回查替代失败，也没有重试；但属于已知来源回归，`blind=false`，`sourceAccuracy=null`、`businessCorrectness=null`。两表不是 top-1，不能称八题全部 top-1；检索到完整保存父也不证明原件表格和条件本身正确。

本次直接重读的实际证据：

- `C:/Users/Administrator/.codex/local-state/consense-oer/fixed-best-baseline-20261012/freeze-2100-05/actual-index-root-01/actual-index-receipt.json`
- `C:/Users/Administrator/.codex/local-state/consense-oer/fixed-baseline-real-source-query-evaluation-20261012/actual-eight-query-05-01/compact-eight-query-source-regression.json`，SHA256 `476887abbd1dea3de60f6def2193707fe6f1e0d6dc550ae6be24bfc584f5725c`

此次没有重新调用历史 8876 服务，不能由文件记录声称该历史服务现在仍在线。历史运行和当前业务采用分别验收。

## 七版本本轮的实际边界

七个不同字节来源合计 22,214 个技术页位；范围和版本身份见[七版调查](../research/oer-sl-technical-version-investigation-20261019.md)。本轮实际 typed native 捕获仅每版两页，共 14 页；收据为 [七版14页捕获](../../../.local/sl-oer-next-bounded-window01/actual-native01/root-acceptance01.json)，各版 `quality=unverified`。不能把剩余 22,200 页写成这轮已经检查，也不能把 14 页称作完整质量通过。

局部实际像素复核覆盖 331 个分隔候选（52 个空格／279 个换行），未观察到与上下文矛盾的候选；其中 206 个换行左端原 space glyph 的精确身份仍 unknown。这不是对全部字符、表格、条件关系的正确性评分。[原始复核记录](../../../.local/sl-real-seven-source-review04/review04-summary.json)保留所有分母与限制；[candidate03 保存产物核对](../../../.local/sl-real-seven-source-candidate03-postread01/execution/receipt.json)仍明确 `sourceQualityAccepted=false`、`affineAccepted=false`、`canonicalAdoption=false`。

跨版内容确有实质差异，例如比赛 Batch 2 和官网 rev1 的 FWP3.1.5.030.B 材质要求；同编号、相似标题和高向量相似度不能合并版本。七版本的完整嵌入／检索召回、选定版本隔离、完整跨页证据组及原件质量，目前没有通过记录。

## 当前判断与后续验收

普通原生正文已有可用实例和文字保全基础；复杂技术表格、图片内清单、条件与续页关系存在实际缺陷／未知，不能将整库称为高质量。嵌入成功只证明材料被编码和保存，不能补回缺失文字、错列数字或未建立的条件关系。

后续沿用同一 OER／canonical 真源链：按七版身份固定章节覆盖账，保留历史旧正确与失败；先补复杂表格／条件组和原生局部图像材料，再按相同版本做来源核验及真实检索回归。数字、单位、否定词、例外和跨页表头必须单独验收，不能只看总文字量或向量点数。扫描按阶段执行，图纸仍只识别后排除。
