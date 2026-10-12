# GCC17 原始 PDF 六页：双解析、原件参考与接入缺口

日期：2026-10-10，Asia/Shanghai。原件 `2a_Standard documents/09_General Conditions of Contract.pdf`，74物理页，SHA `d7d74db6d9aee390093b9e22bb30dccdbf3c0316d47635922109422278d161e7`。本轮为65–70六个完整原页的开发准备，不是GCC全文或官方1.6答题验收。

证据目录为根目录忽略路径 `.local/state/oer-gcc17-dual-prep-20261010/run-195152-da8629/`。六页300DPI、2481×3508，两套完整页推理串行运行：Paddle mixed BF16 6/6，180.279秒；MinerU4.0.10／1.2B BF16 6/6，205.231秒，124次实际VLM调用均stop。没有OOM；整卡峰值分别15983／15255MiB，结束free15293MiB。峰值是整卡观察，不能作为模型独占内存或H800吞吐。原件和受保护服务未修改。

## 原件标注与实际残差

Codex原件侧参考将24262个native glyph恰好一次分到107regions（3个空白保留）；104个非空为81正文、11边注、12页家具。原文、原页图、字形／geometry、region关系和完整两路raw输出均保留。`humanConfirmed=false`，不是法律适用已确认。

| 仅除空白的对照项 | Paddle | MinerU | 分母含义 |
| --- | ---: | ---: | --- |
| 正文region literal完全一致 | 68/81 | 65/81 | 81个非空main regions，非全文准确率 |
| 初始marker保留 | 74/75 | 71/75 | 75个带初始marker的main regions |
| 时间表达 | 27/27 | 27/27 | 本轮具体表达，不代表程序条件全部正确 |
| 六类guard关键字 | 39/39 | 39/39 | region中的实际次数，不是法律题得分 |
| 边注literal完全一致 | 10/11 | 10/11 | 同一弯引号转直引号残差 |
| raw顺序逆source的region pair | 1/620 | 73/799 | 两路实际可形成的pair各自分母 |

Paddle将17.1(2)的 `Sub-contract` 写成 `Sub-contractor`，丢17.1(13)前缀，并倒置14(c)/(d)raw块顺序。MinerU把13个实际正文marker标为aside_text，另遗漏4(b)、8(a)的(a)、11前缀，5(a)括号残缺，7(b)重复／残缺。这些是对照原件得出的具体识别／布局残差，不能用关键词全对或两路一致抵消。

原件边界：p65前文是16.2 Frustration；17.1(1)–(18)到p70结束后才是18 NOTICES／18.1。65→66的5(a)/(b)同父；67→68的(12)、68→69的(16)保留同句continuation。奇数页右边注、偶数页左边注与正文分开；Article26.1／26.2是引用，不发明GCC26条。当前模型六次独立page input没有自动形成这些跨页关系。

## 真实接入状态

已有Java PDFBox `PdfPageEvidence`实际保存glyph／Unicode／characterCodes／font／textMatrix以及Widget／AP信息。operator可见性不保证raster显示；AP glyph是stream-local、没有原页bbox。页级needsOcr仍可能替换effective text／blocks。

既有Python／Java原生transport是native_docx专用，明确拒绝PDF页／bbox别名。六页reference和raw候选还未构成能登记业务的native PDF joint projection。必须补齐四个接缝：实际PDFBox观察CAS封存；原生文字与实际视觉布局分别绑定的source-reviewed whole-unit projection；native_pdf专用typed transport／registry／capability；既有Vetting／Advisory consumer和实际API／前端验收。不能把native文字伪装成OCR输出，也不能减弱扫描双解析或完整表组门控。

本轮开始新增独立PDF observation seam，尚未完成；后续状态另附日期／receipt，不能因准备材料齐全追認登记／采用成功。当前不是官方1.6首轮答题；正式八题实际运行仍0，正式多来源报告评分仍0。

后续实施增量（2026-10-10）：上段“observation尚未完成”为准备阶段历史状态。当前native PDF Java capture与独立CAS冷验已经冻结，通过Java14 tests及Python27 focused tests／11篡改拒绝，完整actual GCC raw 40,626,503 bytes／24,262 glyph保留，三组actual工件各两次cold read通过。见 [capture记录](./oer-native-pdf-observation-capture-20261010.md)。PDFBox glyph与此前source-review的107region也已逐literal／font／基线坐标复核，见 [mapping准备](./oer-gcc17-pdfbox-source-region-mapping-20261010.md)；这仍未产生source_reference v2／adoption。后续joint projection／public wire现只有actual authored契约checkpoint，真实GCC登记、业务消费者、官方1.6与flowchart仍未验收。

可复算证据：`source-preparation-completion-ledger.json` SHA `7912594b6902718419b1e89159c897eefac59e94ff62816f42d381eac83a350b`；`original-source-reference.json` SHA `c24a51a9f4994f07a187da74cb6c03edb3e2e5f77ec4dd2942ea59f261b2ab7c`；全分母／逐region差异为 `source-candidate-comparison.json`；当前源码接缝与精确行号为 `native-pdf-minimum-seam-audit.json`。原件、模型输出和评价参考各自保留，不把参考答案输入同题模型。
