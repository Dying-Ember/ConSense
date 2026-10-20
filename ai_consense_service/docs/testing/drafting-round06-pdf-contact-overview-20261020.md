# Round06 三份 Drafting PDF 的只读全页视觉概览

2026-10-20 对主任务已经从真实 GUI 重新生成并导出的三份 Round06 PDF 做只读检查。没有调用 API、重新生成文档、修改原件或安装依赖。使用本机已安装 Poppler 以 100 dpi 渲染 NTT 12 页、SCT 30 页、SCC 84 页，共 126 页；11 张每张最多 12 页的 contact sheet 均已实际查看。三份页数与已有原模板 LibreOffice preview 分别一致，每页 MediaBox 均为 595.303937007874 × 841.889763779528 pt。全部渲染文件及清单在忽略目录 `.local/e2e-20261020/drafting/round06-pdf-overview/`。

该范围内未观察到新增的正文覆盖、明显裁切、破表或遗漏的渲染页；下列八个近空白／短续文页与原模板 preview 的同一 physical 页完全同像素，不能记为本轮新增漏页。选择的 17 页均独立渲染原 preview 同页并比较完整像素，全部相同；其中 12 页另外打开整页细图，余下 114 页仍只是 contact 概览。结论不等于逐 glyph、源内容／法律正确性或 Microsoft Word golden 分页通过。

| 原已导出的 PDF | 页数 | SHA256（检查前后相同） |
| --- | ---: | --- |
| `e2e-drafting-native-equivalence-20261020-round06/04-NTT-06-equivalent.pdf` | 12 | `fcc5b144c73f2ce6b2f942eb1e7ae0fb4c21049398a0a6f4c848ccf4e72aa368` |
| `e2e-drafting-native-equivalence-20261020-round06/06-SCT-06-equivalent.pdf` | 30 | `93a7f8f8a04d6ce618b303332b1a6e7248cae1d0198e4cd52e84b542c4e11bf7` |
| `e2e-drafting-native-equivalence-20261020-round06/08-SCC-06-equivalent.pdf` | 84 | `fdb4a9d65ab6133417aa95787905276e027c652502593f65da66c07005ef3f84` |

逐 contact sheet 的实际观察如下；页号均为 physical 页，未按原文打印页码推断。

| 文件／physical 范围 | 已执行的概览与追查 |
| --- | --- |
| NTT 1–12 | 正文与右侧指导语、页眉页脚、后续条款未见新增覆盖或裁边。physical 5 近空白页单独追查。 |
| SCT 1–12 | 两种投标文本、Bill 清单、右侧指导语未见新增叠印；physical 4、7 单独追查。 |
| SCT 13–24 | physical 16–19 提交表的可见行／边界保持；21 的表格／分数另看整页，14 的短续文另查。 |
| SCT 25–30 | 末段 SCT8–19、右栏指导语和最后页未见新增裁切；contact sheet 未用灰底位置不是 PDF 空页。 |
| SCC 1–12 | 目录及随后定义／条款版式连续，页眉页脚未见新增覆盖；目录 physical 3 另查。 |
| SCC 13–24 | 密集条款、层级编号、指导语块在页面内保持分隔，未见新增跨页覆盖或越界。 |
| SCC 25–36 | physical 28 保修表另看整页，边框／行和后续共同起算段落未见覆盖；其余编号未见新破版。 |
| SCC 37–48 | 条款、指导语与页眉页脚保持分隔；physical 41 短续文另查。 |
| SCC 49–60 | physical 56、58 公式另看整页，57 的短续文另查，未观察到新增公式叠字或拆跨页。 |
| SCC 61–72 | 61／62／65／66／70 公式在概览中未见明显覆盖，另完成同页全像素比较；未逐个打开整页细读下标。 |
| SCC 73–84 | physical 74 短续文另查；后续铁路／joint venture 末段及最后页页脚均落在页面内，未见新增裁切。 |

八个稀疏分页为 NTT 5、SCT 4／7／14、SCC 3／41／57／74。逐一打开原 preview 的整页 PNG，并与 Round06 对应完整页做全像素比较，全部相同：NTT 5、SCT 4 主要为原打印页尾续页；SCT 7、14 及 SCC 41、57、74 保留短续文和大块空白；SCC 3 是目录最后四行。没有基于留白重新分页、删除或补正文。

另外实际打开四张 Round06 整页细图，并与原 preview 同页全像素比较：

- SCT physical 21：表格、完整分子／分母、分数线、定义及 printed `SCT/18` 页尾都在同页，可读且未观察到裁切／覆盖。该 30 页未折叠版本的 physical 21，与旧“采纳分支移除”专项的 physical 13 是不同产物，不借旧图代替本次检查。
- SCC physical 28：三行保修表、边框以及后续共同起算段落可见，未观察到重叠。
- SCC physical 56：Contract Conversion Factor 的整体分数、上下标与相邻正文可见，未观察到裁切／覆盖。
- SCC physical 58：人工／材料两个分数及相邻定义可见，未观察到裁切／覆盖。

其余五个公式页 SCC physical 61／62／65／66／70 已看 contact 并完成源 preview 同页全像素比较，但未再逐个打开整页细读下标。完整 17 页比较之外的 109 页没有做源 preview 全像素比较；完整 12 页细图之外的 114 页没有做独立整页细读。逐字形、逐字义、所有原打印目录／页码的正确性、Word 原生 golden 对照均未执行，清单按每份文档列出全部未执行页号。

证据入口：

- `round06-pdf-overview/render-manifest.json`：三份 PDF 与原 preview 身份、126 张页面 PNG／11 张 contact sheet 的哈希、渲染命令和返回状态。
- `round06-pdf-overview/same-page-comparison.json`：17 个选定同 physical 页的全像素比较、源与生成 PNG 身份、稀疏页及公式追查原因。
- `round06-pdf-overview/review-ledger.json`：实际查看的 11 张 sheet、12 个整页细图、17 项追查、每份未执行页号、六份输入 PDF 前后 SHA 及范围限制。
- `render_round06_pdf_overview.py`、`render_round06_pdf_details.py`、`finalize_round06_pdf_overview.py`：只读渲染、比较及实际观察登记，均在同一忽略的 `drafting/` 目录。

三份导出 PDF 和三份既有原模板 preview 的字节均保持，所有产物写入独立忽略目录。本项没有必要的产品修复，未修改代码／原件，也不把这里的局部视觉结论提升为三模块完整业务验收。
