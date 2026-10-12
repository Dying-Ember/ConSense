# GCC17 六页：typed source reference 准备与引用层级修正

日期：2026-10-10，Asia/Shanghai。原件仍是74页GCC、SHA `d7d74db6d9aee390093b9e22bb30dccdbf3c0316d47635922109422278d161e7`。本段只转换既有原件审阅标注的结构及定位，不运行OCR／LLM／BGE，不读取候选输出或2d／2e，不执行canonical采用／业务登记。范围与未知按 [规则](../oer-scope-and-rules.md)保持。

## 当前准备工件

原标注来自 `.local/state/oer-gcc17-dual-prep-20261010/run-195152-da8629/original-source-reference.json`，SHA `c24a51a9f4994f07a187da74cb6c03edb3e2e5f77ec4dd2942ea59f261b2ab7c`。实际PDFBox逐literal／font／基线坐标映射来自 `.local/state/oer-gcc17-pdfbox-mapping-20261010/run-204922-284ddd/glyph-region-mapping-preparation.json`，SHA `597ed14490144c9f602d40536b9ca71599f82ee75b52e87c17348d6c8bb468f7`；对应24,262 glyph／107regions。

新helper为 `.local/state/oer-gcc17-source-reference-v2-20261010/prepare-reviewed-reference-v2.py`。当前输出位于`run-211141-0dbb5b/gcc17-original-source-reference-v2-preparation.json`，schema `consense.source-review-reference.v2`、referenceVersion `gcc17-original-source-typed-preparation-20261010-v2`，151,227bytes，SHA `0364edaff19ce8e7dbfbea06f029b69d817b494cd7d5b733b44b05aea57f68f5`。

保留全部107 source regions：正文、三处native blank、边注及页家具没有因暂未采用而删除。逐region保留原source-review text／role／读序／原物理页／真实PNG身份／原native glyph envelope，不用OCR或答案修文字。两处原件已审的same-sentence跨页续接成为typed `continuation`；65→66的编号子项是same-parent next numbered subpart，仍保留在原sourceCrossPageInventory，没有错误改为同句续接。完整parent／qualifier／sidebar采用仍需后续显式结构审阅和whole units绑定。

validation用完整原PDF及六张真实PNG共7份actual bytes核SHA／尺寸／源页，再独立读取schema并复核对象。完整性核验不证明glyph operators已独立重放、像素边界／法律语义正确；`humanConfirmed=false`、semanticQuality和wholeOriginalSourceCoverage仍unknown。

## 独立检查发现与修正

初始typed输出`run-210639-3d74a9/`有107region完整性冷审计通过、0完整性失败；独立auditor另发现10个标签语义问题：4个标题结构键带`/title`，6个引述结构键带`17.1(17)(d)/quoted-article-26.*`。原helper将这些内部结构键叫`printedSourceLabel`不准确，它们不是纸面印刷标签。原件的Article26.1／26.2引述仍属于GCC17.1(17)(d)，不能新造GCC26.1／26.2。

已删除`printedSourceLabel`；原键保留为`sourceReviewStructureKey`。标题的实际containing clause不带`/title`；六处引述的`clauseId`明确为containing GCC17.1(17)(d)，另以`quotedRuleContext`保存引述层级与review identifier，明确不是新增GCC clause。只调整来源标签及上下文，没有改变原text／role／box／anchor／order／relations。

独立新修正窄复核34 checks／0 failures，核81个原source-review结构键、6个quoted contexts及4个title keys；7actual bytes再次冷核。source-reference validator的215unknown继续保留；308 unsupported preserved properties包含新增引述扩展，这些是未解释属性／语义限制，不能当质量分数或删除理由。

独立收据：

- 初始完整核验：`.local/state/oer-native-pdf-independent-20261010/run-043238-7a15b0/gcc17-v2-preparation-audit/run-211313-d6cb6a/independent-preparation-audit-receipt.json`，SHA `8fa48c91170156647798669f4c9852c0251f450a0da2e5b0da709f04c008d838`。
- 新修正窄复核：同根`label-fix-211425-8f02bd/independent-source-label-fix-receipt.json`，SHA `d5ba8aa989bf90ab055ead1d9e33775f64877c0e056d16ffe487b18fef2b72f1`。

首轮helper还错误地给`read_source_reference`传入不支持的`evidence_bytes`，实际TypeError与初版源码另存；已将actual-byte validation与read/schema replay按真实接口分开。旧typed输出和失败保持，不追认为新版本通过。

## 下一段边界

当前v2是准备工件，没有canonical adopted文本、selected layout、typed separators、完整单元、业务documentId或索引。后续要绑定实际完整native bundle、两路actual vendor bytes／assets与raster review，再建立显式region membership／reading order／separator／adoption，并使用独立PDF public transport／registry和Java消费者。真实GCC业务问答、官方1.6生成图及八题／正式Vetting评分仍未执行；原件与答案隔离规则保持。
