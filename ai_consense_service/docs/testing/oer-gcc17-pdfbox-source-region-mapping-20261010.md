# GCC17 原件区域与实际 PDFBox 字形映射准备

日期：2026-10-10，Asia/Shanghai。接续 [GCC原始PDF准备](./oer-gcc17-original-pdf-source-preparation-20261010.md)。本轮将实际Java PDFBox exporter的字形ID与此前独立原件侧region标注对应；没有运行OCR、BGE或LLM，也没有采用／注册业务来源。

当前capture是 `.local/state/oer-native-pdf-observer-20261010/run-044811-0303f8/actual-exports-044845-898584/gcc-physical65-70-native-pdf-observation.json`。实际raw JSON40,626,503字节，SHA `b7a52b13b6764b0e3df2ac0be996595679c08930d9636e8d891d6da3c845d007`；源PDF字节SHA保持 `d7d74db6d9aee390093b9e22bb30dccdbf3c0316d47635922109422278d161e7`。完整raw style／glyph／frame／unknown保存，不因raw体积大而裁字段。

原件侧输入是此前107region独立标注及六页pdfplumber native evidence。其glyphIndices没有直接作为PDFBox indices套用：先核每页实际glyph数量，再逐index比较Unicode、fontName及原PDF baseline x/y；只有全部一致才绑定 `pdf-page/{physical}/glyph/{index}`。每region声明的nativeObservedRawGlyphTexts也逐值一致；全部原字形恰好一次属于某个region，空白区域、家具和边注不删除。

| 原物理页 | 实际字形数 | 数据来源 |
| --- | ---: | --- |
| 65 | 4053 | 最终mapping `pages[0]` 与实际export `pageObservations[0]` |
| 66 | 4138 | 同对象ordinal1 |
| 67 | 4666 | 同对象ordinal2 |
| 68 | 4527 | 同对象ordinal3 |
| 69 | 4046 | 同对象ordinal4 |
| 70 | 2832 | 同对象ordinal5 |

总计24262字形、107regions；各页数量、regionCount和最大位置差均保存在receipt。坐标比较阈值0.005pt；实际最大baseline差0.0004747895950458769pt，属于两引擎float表达的观测差。该数不是glyph outline／raster框精度，不代表两引擎独立证明法律语义或有效可见性。

当前证据目录 `.local/state/oer-gcc17-pdfbox-mapping-20261010/run-204922-284ddd/`：`glyph-region-mapping-preparation.json` 1,106,203字节，SHA `597ed14490144c9f602d40536b9ca71599f82ee75b52e87c17348d6c8bb468f7`。receipt绑定实际export、旧reference和旧native每页文件的bytes/SHA、实际observer／source revision，以及helper源码SHA。它给出每region准确PDFBox glyph IDs、literal Unicode、先前声明顺序、原页图引用与旧reference JSON pointer。`humanConfirmed=false`，无真人确认声明。

本轮没有新推导阅读顺序；顺序来自先前原件review声明。source reference中的带gap显示文本／物理换行与实际glyph literal仍分开，没有用reference文字修补glyph，也没有把Paddle／MinerU文字覆盖原生观察。真正projection还需将这些映射封为新的typed reference/adoption，核完整unit／跨页关系及实际visual provenance；不能把这个自定义准备JSON当作source_reference v2或submit-ready manifest。

早期两个成功mapping仍分别绑定较早observer，保存在run-204259-e12f3b及run-204600-3bdcf5。字体观察边界和partial AP处理改变observer源码／revision后，当前run重新绑定并复核，不把旧SHA重贴新capture。helper初版误认为所有region都有可选sourceSideLabel，发生KeyError；初始脚本／失败receipt保留在run-204241-04f765，随后仅修正helper的可选字段处理，原件／候选／模型均未改。

有效栅格显示、精确glyph外形、全GCC另外68页、完整合同适用关系及业务采用仍未通过。原件encrypted／extractionPermission等actual访问flags也保留在receipt，不能与语义、可见性或采用状态合并成一个verified标志。
