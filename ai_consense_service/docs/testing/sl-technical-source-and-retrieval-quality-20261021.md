# SL 技术规范解析、嵌入与检索质量现状

日期：2026-10-21。结论：已有单版开发基线、七版特定完整条款的有界原图源评和真实局部验证；七版本全库质量未验收。归档、恢复、哈希、软件测试不计作来源准确率或检索召回率。历史运行、保存来源回放、原图核对和当前运行采用分别记录；后继检查不改写历史收据。

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

候选成果与当前正常链的集成另列：[当前首个损失层审票](../../../.local/sl-com11-note9-current-chain-candidate01/first-loss01.json)（SHA256 `408af9eb7b0264c6f522c0e79f23876e1829f8e3553ffc3a7a1edabe91dfb20f`）绑定了已存 normal、原件／章节 SHA、页映射、实际 block／文字／bbox 与当时五处代码身份。现存 `sl-018/complete-normal.json.gz` 中，章节局部页 19（原页 1316）的主表及 Note 1–9 已保留，Note 9 下一页的 LM／CO／LL、RS 和 “For other areas” 续文也已被 ordinary native 读到，但未连回上一页表格的完整证据组；局部页 20 本身没有 logical table。

后继已沿既有 Python capture → Java method group → 普通模型材料及完整 metadata wire 修复，Root 精确采用四个产品文件和两份既有测试文件，未部署。真实保存 provider 回放的 Python RED 精确复现缺关系，GREEN 27/27 通过、54 份旧 actual 单元摘要保持；Java RED 精确复现组内缺后缀，GREEN 首窗 85 pass／1 夹具断言失败，修正该项对数值节点类型的比较后仅补验原失败方法 1/1，跨两窗覆盖原 86 个唯一身份。原失败保留，不称单窗 86 全绿；两份完整 wire JSON 各 2,011,545 字节且原已逐字节一致。新后缀保持原表锚点、源向、三段真实 native 文字、unknown 法律语义，不伪造下一页逻辑表；可选续接分析异常不会清除已成功的同页主表。见 [六文件采用](../../../.local/sl-com11-note9-current-chain-candidate01/root-adoption-actual01.json) 与 [完整软件审计](../../../.local/sl-com11-note9-current-chain-candidate01/software-acceptance01.json)。这些是当前源码的软件／保存来源消费证据，没有新的 PDF 提取、实际引擎或真实检索调用；跨 HTTP batch 的续接、canonical 准入及真实检索仍未通过。本轮同分母源评分已实际执行，准确范围如下。

### 当前修复输出的同分母源评分

Root 实际运行一次保存来源评分，52.093 秒、exit 0、输入 pins 全部保持并自然 Job 退出。保持 R116 的封定 scorer、gold、226 页输入门和 32 个 fragments；仅将五表及必要续页所在的 7 页替换为当前实际 capture，其他 219 页原始 JSONL 行逐字节保留。三个方法独立评分，adapter 只做可逆的已证来源／页命名空间转换和展平已有 continuation 组，没有生成新文字、表格或关系；gold 仅在 scorer 读取。

| 当前 capture 方法 | 正确主格字面 | 完整主表 | 完整表外成员 | 正确 typed 关系 | 完整已标来源组 | 旧正确项回退 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Camelot Stream | 672/672 | 5/5 | 12/12 | 87/87 | 5/5 | 0/1,443 |
| Camelot Network | 650/672 | 4/5 | 12/12 | 75/87 | 4/5 | 0/1,387 |
| pdfplumber lines/lines | 0/672 | 0/5 | 0/12 | 0/87 | 0/5 | 0/0，无旧正确项 |

旧正确项是主格 topology 与 literal 两项分别计数，再加表外成员与 typed 关系；不是 1,443 个独立原件或业务问题。Network 的 56 个失败项、pdfplumber 的 1,443 个失败项全部保留；每个方法的另外 27 个历史 fragment 评分逐字段相同。五个已标目标组的所有评分依赖均在当前 7 页内，不使用其余历史页替当前关系授通过。这证明当前修复在已暴露的局部开发域中恢复了 R116 已证能力，没有超越其旧分数；不是 fresh 226 页解析、七版全库、盲测、运行部署、canonical 发布或真实检索验收。

逐 ID 的当前结果见 [实际评分结果](../../../.local/sl-com11-note9-current-chain-candidate01/source-score-window01/results/green/result.json)，SHA256 `2bdd728561671d843a4ea5cfc8c9092397f2ad346ccf567a8edcfa35aaaf1606`；[实际执行收据](../../../.local/sl-com11-note9-current-chain-candidate01/source-score-window01/green/receipt.json) SHA256 `763c73d37509fc1b3d96782ae2c837dde538d2a0ddbea15dd8a30ab58f59207f`。结果明确 `currentActualPagesPerMethod=7`、`retainedHistoricalPagesPerMethod=219`、`fresh226Claim=false`、`sourceQualityFinalAccepted=false`；本次无 PDF 提取、引擎、模型、HTTP、SQL、渲染、索引、服务或比赛包操作。

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

七个不同字节来源合计 22,214 个技术页位；范围和版本身份见[七版调查](../research/oer-sl-technical-version-investigation-20261019.md)。本轮实际 typed native 捕获仅每版两页，共 14 页；原收据为 [七版14页捕获](../../../.local/sl-oer-next-bounded-window01/actual-native01/root-acceptance01.json)，当时各版 `quality=unverified` 保留不改。后继针对这 14 页的具体语义源评见下节，不能把剩余 22,200 页写成已经检查，也不能把 14 页全部质量提升为通过。

局部实际像素复核覆盖 331 个分隔候选（52 个空格／279 个换行），未观察到与上下文矛盾的候选；其中 206 个换行左端原 space glyph 的精确身份仍 unknown。这不是对全部字符、表格、条件关系的正确性评分。[原始复核记录](../../../.local/sl-real-seven-source-review04/review04-summary.json)保留所有分母与限制；[candidate03 保存产物核对](../../../.local/sl-real-seven-source-candidate03-postread01/execution/receipt.json)仍明确 `sourceQualityAccepted=false`、`affineAccepted=false`、`canonicalAdoption=false`。

跨版内容确有实质差异，例如比赛 Batch 2 和官网 rev1 的 FWP3.1.5.030.B 材质要求；同编号、相似标题和高向量相似度不能合并版本。七版本的完整嵌入／检索召回、选定版本隔离、完整跨页证据组及原件质量，目前没有通过记录。

## 后继：七版完整 FWP 条款的 224 项有界原图源评

独立代理逐张以原始分辨率查看上述 14 张已存原 PDF 页图，对七版各自完整 `FWP3.1.5.030` 候选单元进行固定 32 项对照，总计 224 项：219 PASS、0 FAIL、5 UNKNOWN。这是原图与保存候选的实际对照；评估前已看过候选文件，因此不是盲测，也不是对全部技术页质量的统计估计。

| 所评对象 | 结果 | 可证明的范围 |
| --- | ---: | --- |
| 原图到保存文字、标量、版本身份及单元边界 | 203 PASS | 此七个完整条款的被检查内容保持 |
| 显式序列化的私有候选父子、条件和跨页关系 | 14 PASS | 27 节点的父子和完整后代字形集合、七组条件控制及页码映射保持；不证明当前正常链已消费 |
| item 4 证书句的证明对象宽度 | 2 PASS、5 UNKNOWN | 原版／Batch 1 没有该句；另五版文字、认可机构／实验室、CM 与 Approval 已保留，但证明对象宽度不猜补 |

原图确认比赛 Batch 2 与官网 rev1 同为 `.030.B`，小口径 fresh/fire 与 flush 的 body/cover 材料要求确实不同，保存候选没有串版。50 mm 属于 item 2，item 3 为严格大于 50 mm；Batch 1 的 item 2 从第二页开始，其余六版的 item 2 由第一页延续至第二页 2(b)。2026 的 `.030.10` 与打印页 9／10 分别保持，旧版条号／页脚没有套用；已印的 EN 10283 Grade 1.4408 替代项没有扩大到 screen 或原版／Batch 1。

[逐项源评](../../../.local/sl-fwp-seven-semantic-review01/semantic-source-review.json)（402,755 字节，SHA256 `c4788c164c7bef28acba6aab902258a43f7bf49c025e9ce3fa3a0dc554e578ef`）保留 224 个唯一版本／字段身份、源见值、候选值、目标产物、原页和全部 50 份输入的哈希保持；[评估说明](../../../.local/sl-fwp-seven-semantic-review01/review.md)列每版分母与未知项。每项 `candidateAdopted=false`，当前正常链消费明确为 `UNKNOWN_NOT_EXECUTED_OR_PROVEN_BY_THIS_REVIEW`。203 项文字／标量证据与 14 项私有候选关系不得合并宣称运行质量；canonical、实际检索、版本隔离、affine、精确像素／glyph 身份及七版全库验收均未提升为通过。本次 0 新捕获、OCR、渲染、模型、HTTP、SQL、索引、服务或比赛包动作。

<!-- sl-normal-component-progress44:start -->
## 当前Note9正常链的跨语言组件验证（progress44）

本轮实际补上保存来源在Java和Python之间的组件消费证据，没有发起新的PDF提取、OCR或神经检索；不能由组件成功把完整SL来源准入、运行发布或质量改成通过。三个窗口分别执行和验收：

| 接缝 | 实际执行及保存产物 | 仍不证明 |
| --- | --- | --- |
| Stage1 Java导出 | 1方法PASS、43.968秒、输入保持／自然Job0；实际18,211,812字节request有1,743单元、30关系、254PDF组。两个Note9方法均保原页20的三段ordinary-native续文 | 首窗使用前继私有fixture投影哈希，不能单独证明真实Python投影回到Java |
| Python02 producer／CAS／冷registry | 后继1方法PASS、49.718秒含清理及pins，485输入／7,861依赖保持。实际36,817,060字节完整快照、18,211,936字节manifest CAS及私有registry root／revision alias逐字节一致；1,578可检索文字chunk＋165不可检索单元＝1,743。篡改manifest关系、截短派生关系、真实私有CAS损坏均拒绝，blob已恢复 | 私有registry存储和冷重建不是正常HTTP准入，不是fixed05发布或真实检索。前窗缺`samples.json`、0测试的导入失败保持 |
| Stage3实际Python投影→Java pointer／模型／wire | 1方法PASS、40.266秒、2,069pins保持／自然Job0，最大1JVM、16GiB堆／24GiB Job。消费Python02完整快照及真实source／projection revisions；模型材料942,231 UTF-8字节，两个方法组完整、有序JSON相等，异修订指针拒绝 | `normalHttpAdmissionTested`、`canonicalScopeAdmissionPassed`、`sourceQualityAccepted`、`retrievalOrIndexingTested`仍全部false |

实际输入图谱及binding、完整manifest和各修订封印已独立复算。Python02快照SHA256为`219a78f05e4a08ad40d94cd47d0dfd1b23c4f1ab912aec75d114fdc6cc8a4062`；projection为`60e69914b22999b2d49f7102bc4a49b34d5f284b9fe29653de87f3e06fdefd14`，projection revision为`30a90abfe7151ad690ce21d2adba38bd0b2e08239b7e1ecffdcae06dc8b42ba5`，source revision为`acf77b4bf732cede643a5f920791e0b7695be9646b18011230dde102e5faf4ca`。这些是实际组件身份，不用作者fixture哈希代替。

证据分别为[Stage1导出审计](../../../.local/sl-com11-note9-current-chain-candidate01/canonical-java-export-audit01.json)、[Python02实际结果](../../../.local/sl-com11-note9-current-chain-candidate01/canonical-python-window02/results/green/result.json)、[Python02独立全hash审计](../../../.local/sl-com11-note9-current-chain-candidate01/canonical-python02-actual-audit.json)、[Stage3实际执行票](../../../.local/sl-com11-note9-current-chain-candidate01/canonical-java-window02/green/execution/owned-receipt.json)和[Stage3完整消费证据](../../../.local/sl-com11-note9-current-chain-candidate01/canonical-java-window02/green/execution/real-consumer-output/java-real-projection-consumer-evidence.json)。[Python前窗失败](../../../.local/sl-com11-note9-current-chain-candidate01/canonical-python-window01-failure-evidence.json)保留，不改为来源质量失败或后继通过。

现有完整父原件scope policy要求SHA `6331…`，实际COM11章节派生件为`ce01…`，仍按`sl_source_hash_does_not_match_manifest`拒绝；没有重标full-parent SHA、改catalog role或翻quality布尔。法律归属／条件闭包继续unknown。当前7页＋历史219页同分母源评的局部Stream全通过、Network和pdfplumber失败，以及七版14页219PASS／5UNKNOWN，均保原分母与已暴露非盲测边界；组件链成功不外推至全22,214技术页、七版嵌入／检索或运行部署。

Root[当前18780实际回执](../../../.local/sl-fwp-seven-semantic-review01/coordinator-before01/receipt.json)记录健康检查500／`software changed since import`，正常运行接缝需先恢复；本轮新的七版真实dual尚未发出，0新引擎调用。此前七个两页版本／28个method-page槽入口仍是准备，不能由历史原图或软件测试授dual完成。先补正常准入、真实同源双路／版本隔离检索和来源质量，SL质量未通过期间不执行比赛包绑定、压缩、归档或恢复。

<!-- sl-normal-component-progress44:end -->

## 当前判断与后续验收

普通原生正文已有可用实例和文字保全基础；复杂技术表格、图片内清单、条件与续页关系存在实际缺陷／未知，不能将整库称为高质量。嵌入成功只证明材料被编码和保存，不能补回缺失文字、错列数字或未建立的条件关系。

后续沿用同一 OER／canonical 真源链：按七版身份固定章节覆盖账，保留历史旧正确与失败；先补复杂表格／条件组和原生局部图像材料，再按相同版本做来源核验及真实检索回归。数字、单位、否定词、例外和跨页表头必须单独验收，不能只看总文字量或向量点数。扫描按阶段执行，图纸仍只识别后排除。
