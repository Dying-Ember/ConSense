# SL 多版本技术部分调查

日期：2026-10-19。状态：已找回官网来源、取得当前六个公开版本，完成各版原生目录／全文调查及指定难页的原图复核；不是六版 OER 解析质量验收。

用户本次要求恢复 SL 多版本技术调查。2026-10-14 的“延后到引擎修好后”记录保留历史，不再阻止本次调查。新资料独立保存，不覆盖比赛 Batch 2 原件，不加入训练或活动索引。范围仍为 SL 九个技术库，SL 内的 PRE 不因本次调查恢复为业务材料。图纸继续按已确认规则排除。

## 找回的来源与实际版本

之前登记的入口是房委会 [Specification Library 官网目录](https://www.housingauthority.gov.hk/en/business-partnerships/resources/specification-library/index.html)。本轮用浏览器重新打开，页面仍列下述六个完整库的下载入口，页面标注最后修订为 21 July 2026。目录声明 SL2026 原版于 April 2026 发布，供 August 2026 起招标的新发展合约采用；这不等于任意现有项目已采用新版。

你之前所说的 April 2025 Batch 3，对应 [SL2022 Batch 3 PDF](https://www.housingauthority.gov.hk/common/pdf/business-partnerships/resources/specification-library/SL2022_052025_Batch03_OGCIO_final.pdf)。文件名带 `052025`，实际封面物理第 1 页明确印 `April 2025 version`。已找回当时登记来源，未用文件名反推封面日期。

| 版本／官方入口 | 实际总页数 | 九个技术库的物理页范围 | 技术页数 | 原生书签数 | 封面或日期证据 |
| --- | ---: | --- | ---: | ---: | --- |
| [2022 原版](https://www.housingauthority.gov.hk/common/pdf/business-partnerships/resources/specification-library/SL2022_052022_31052023_Orginal_OGCIO_V3.pdf) | 4093 | 1042–4093 | 3052 | 77 | 封面写 Original version，未印年月；不把文件名的日期当封面日期 |
| [2022 Batch 1](https://www.housingauthority.gov.hk/common/pdf/business-partnerships/resources/specification-library/SL2022_042023_19062023_Batch01_OGCIO_V1.pdf) | 4299 | 1102–4299 | 3198 | 77 | 封面 April 2023 version |
| [2022 Batch 2，官网 rev 1](https://www.housingauthority.gov.hk/common/pdf/business-partnerships/resources/specification-library/SL2022_042024_Batch02_OGCIO%20%28rev%201%29.pdf) | 4300 | 1127–4300 | 3174 | 12753 | 封面 April 2024 version；正文确有与比赛原件不同之处 |
| [2022 Batch 3](https://www.housingauthority.gov.hk/common/pdf/business-partnerships/resources/specification-library/SL2022_052025_Batch03_OGCIO_final.pdf) | 4368 | 1162–4368 | 3207 | 124 | 封面 April 2025 version |
| [2022 Batch 4](https://www.housingauthority.gov.hk/common/pdf/business-partnerships/resources/specification-library/SL2022_052026_Batch04_OGCIO_final.pdf) | 4389 | 1181–4389 | 3209 | 126 | 封面 April 2026 version；文件名带 052026，不能取代封面日期 |
| [2026 原版](https://www.housingauthority.gov.hk/common/pdf/business-partnerships/resources/specification-library/SL2026_072026_Original_OGCIO_final.pdf) | 4385 | 1186–4385 | 3200 | 126 | 封面写 Original version；官网给出发布与适用招标月份，文件名带 072026 |

以上数值来自每份实际 PDF 的页树、完整原生书签和封面／边界原页；汇总见本地 `summary-evidence.json`，逐页原文见 `cross-version/*-pages.jsonl`。这组物理页只定位这些 SHA 绑定的来源，不是解析器可以复制给其他版本的路由常量。六个技术范围分别计数，不把同条文跨版本重复计为独立训练样本。

## 技术内容有哪些真实差别

### 同一 Batch 2，也可能存在实质修订

比赛文件 `26_Specification Library_SL2022_042024_Batch02.pdf` 与官网 rev 1 均为 4300 页，封面／README／总目录的原生文本相同，但字节和部分条文不同。

本轮对两份的全部 4300 页逐页比较 PDFium 原生文本：4298 页严格相同，差异只在物理第 3556、3557 页，即打印页 `FWP3/10–11`。在 3174 个技术页中，3172 页严格相同。这个结论只针对原生文本，不能推出全部图片、字形、坐标或渲染也相同。

root 和调查代理均亲看了两份原件的四张完整页面，确认差异属于 `FWP3.1.5.030.B CONSTRUCTION`，不是同页上方的 `FWP3.1.5.010.9 TYPE AND APERTURE`：

| 条款条件 | 比赛原件 | 官网 rev 1 | 实际影响 |
| --- | --- | --- | --- |
| 2(a)(ii)，公称尺寸 ≤50 mm，fresh water／fire services，body and cover | ductile iron 或 bronze | 只列 bronze | 可采用材质范围变窄 |
| 2(b)(ii)，同尺寸，flush water，body and cover | stainless steel，BS EN 10088-1 Grade 1.4401 | ductile iron，BS EN 1563 EN-GJS-400-15，或 bronze，BS EN 1982 对应牌号 | 材质要求实质改变 |

两处 screen 的 stainless steel 要求保持。原文、完整 diff、原图及条款核对见 `sl2022-batch02-rev1/rev1-strainers-source-comparison.json`、`summary-evidence.json`。不能用最新版静默替换比赛原件，也不能只凭“2024 Batch 2”认定同一修订。

### 瓷砖拉拔试验的豁免条件变了

2022 原版的 `FIN5.T150.9` 与 Batch 1 的 `FIN5.T150.A`，在 2(a)(iii)／2(b)(iii)的例外描述不同：

- 原版：`tiles installed over waterproofing layers`。
- Batch 1：`tiles installed over cold liquid applied waterproofing layers as WAT6.M010`。

原版物理 1372–1373，打印 `FIN/104–105`；Batch 1 物理 1445–1446，打印 `FIN/108–109`。两版四原页由调查代理核对，root 复看了续页 1373／1446。这里需要同时保留材料条件、条款编号和对 WAT6.M010 的引用，不能只做相似文字合并。

### 同一性能表，有时跨页，有时一页装完

`FIN5.M1050.9` 的 latex mortar 性能表，在原版物理 1355–1356（`FIN/87–88`）跨两页：下一页继续表头、freeze-thaw 与 open-time 项，再带两条表下注。Batch 1 对应表位于物理 1428（`FIN/91`），五项集中在同一页。root 已亲看这三张完整页面。

这说明跨页续表规则要识别表头、列、所属条款和脚注，也要能在另一版中接受“不需要续接”。不能把历史某几页的页框或页数写成固定答案。调查代理进一步保留完整五行三列和两条脚注，去空白／重复表头后的 451 个字符精确相同；该规则只证明这组表的相应内容相同。

另一个完整长表样本是 Electrical Appendix 1 的技术标准清单：原版物理 3299–3326（AP1/3–30）；Batch 1 为 3478–3506（AP1/3–31）。调查代理亲看首三页与末页，并对完整原生文本核对，Batch 1 末页出现与 ELE21 关联的 ISO 9227/2017、ISO 10289/1999 两行，原版完整清单无相同条目。这是表格内容及长度变化，不能仅靠表名判相同。

## 对结构识别最重要的发现

### 书签可辅助定位，不能统一当章节树

2024 Batch 2 有 12753 项书签，深度到 6；2025 Batch 3 仅 124 项，最深到 2；2022 原版及 Batch 1 各 77 项。即使技术内容有对应关系，书签树也不是同一细度。

2024 中 Architectural 等专业下另包了一层同名节点；2025 中 FIN 等 worksection 可直接位于专业下一层。同一 `depth=1` 在各版代表不同层级。2024 Structural 的中间节点还实际带有 `SEW_22_4_202Structural EngineeringWorks Specification4_B1` 之类异常标题。

Batch 4 的顶层书签 `Soft Landscape Works Specificatio` 被截尾；2026 的 `DemolitionWorks Specificatio` 亦有类似元数据问题。原件封面须作为复核依据，不能因字符串不完全等于 Specification 就漏掉整部。已亲看 Batch 4 的 Soft Landscape 封面物理 2826 及后续边界，正文专业名称完整。

本轮初次尝试“按技术库下一层书签直接比较完整单元”，结果被这些层级／包裹／空格问题干扰，不能据其 added／removed 数字报告新增删除条款。该诊断保留于 `cross-version/technical-unit-comparison.json`，在 `summary-evidence.json.discardedDiagnostic` 明确标为不适用；没有把失败方法包装成版本变化结论。

### 编号至少有三套格式，后缀有明确含义

2026 的 README 物理第 4 页（RM/3）原图明确给出：

| 类型 | 示例 | 要识别的差别 |
| --- | --- | --- |
| 一般技术条款 | `COM5.M010.10` | worksection、G/D/M/W/T 分类码、serial number、edition suffix |
| Building Services | `ELE12.1.010.10` | 多层数字格式，不套一般条款分类码结构 |
| Structural Appendix H tolerances | `TOL.COM5.010.10` | TOL 前缀及对应 worksection，不能按普通小数编号拆 |

同位置的 2022 系列示例为 `.9`，2026 的 `.10` 明确表示 2026 Edition，不能限制为单个数字。2022 页面的部分编号示意是图像，原生文本没有示意中的全部内容；root 亲看了 2026 对照图，调查代理核了 2022 示意图。

README 物理第 6 页（RM/5）对 PSS 后缀定义：`P` 是项目合约特定修改，`N` 是一般新增，`X` 是一般删除，`A, B, etc` 是替代／修改；本次正文也实际出现 `.A`、`.B`。同页还明确指出：其他条款引用时不带后缀。因此应分别保存完整源编号、引用干号及版本身份；不能去后缀后直接覆盖、混并或自动认定同义。

### 原生 PDF 仍含局部图像正文和跨页表单

2025 Batch 3 的物理 3492–3494，对应 `ELE36/19–21`、`ELE36.6.010.9`；2026 对应物理 3508–3510、`ELE36/18–20`、`ELE36.6.010.10`。正文中插入 AMR Outstation Proposal checklist，带地址／姓名填写线、两列选项框及跨页续行。

root 亲看两版各前两页，确认表单第 (3) 行的正文跨到下一页，而下一页续文前并没有重新写 `(3)`。2025 这三页原生文本分别只有 398／193／191 字符，主要是外层标题与页眉页脚，里面的清单文字不能用“整页已有原生文字”判为读取完整。2026 同样分别仅有 385／181／181 字符的原生文本。

2024 官网 rev 1 的同表位于物理 3436–3438；调查代理核了这版与 2025 的三个对应 Image XObject，encoded／after-filter stream SHA 三组均相同。这仅证明该组三张嵌入图像相同；不能由此判断两本全书或引擎表格解析相同。

这一组的处理应是：保外层原生标题／条款／页号，在实际图片区域补读并建立完整三页表单关系；不必因此让整本数千页 PDF 全页 OCR。图中写的 August 2017 是被引用附件自身日期，也不能用它改判整本 SL 的版本。

### 原件自身也可能有残缺指导，不能自动补齐

Batch 4 物理 1716（PLU/16）原图中确实有未闭合的 `(Guidance Note: The revised clause shall apply for all new projects and projects under construction with Part I 1. This clause provides ...`；2026 对应物理 1720（同为 PLU/16）从完整编号 1 开始，该残缺 Guidance Note 消失。root 和调查代理都亲看两页。这是原件呈现变化，不能归咎于 native 抽取，也不能自动补造右括号或据此宣布技术义务已改变。

## 九个技术库的实际分界

以下是调查定位表，不是产品硬编码规则。专业名称由官网、各版目录和专业封面交叉核对；各范围包含该版技术库内部的附录和封面，不能按总页数差直接判断漏页。

| 技术库 | 2022 原版 | Batch 1 | Batch 2 rev 1 | Batch 3 | Batch 4 | 2026 原版 |
| --- | --- | --- | --- | --- | --- | --- |
| Architectural | 1042–1750 | 1102–1842 | 1127–1855 | 1162–1889 | 1181–1919 | 1186–1922 |
| Drainage / External | 1751–2032 | 1843–2147 | 1856–2151 | 1890–2185 | 1920–2219 | 1923–2222 |
| Structural | 2033–2470 | 2148–2609 | 2152–2602 | 2186–2639 | 2220–2671 | 2223–2674 |
| Demolition | 2471–2525 | 2610–2666 | 2603–2658 | 2640–2695 | 2672–2726 | 2675–2729 |
| Foundation | 2526–2624 | 2667–2768 | 2659–2757 | 2696–2794 | 2727–2825 | 2730–2827 |
| Soft Landscape | 2625–2698 | 2769–2845 | 2758–2830 | 2795–2867 | 2826–2897 | 2828–2899 |
| Building Services | 2699–3703 | 2846–3899 | 2831–3911 | 2868–3977 | 2898–3998 | 2900–3994 |
| Civil | 3704–3931 | 3900–4132 | 3912–4138 | 3978–4206 | 3999–4227 | 3995–4223 |
| Geotechnical | 3932–4093 | 4133–4299 | 4139–4300 | 4207–4368 | 4228–4389 | 4224–4385 |

## 如何把这次发现用于 OER，避免只修一个版本

以下是由原件直接导出的验收要求，不表示本轮已实现或证明当前引擎存在全部缺陷。

1. **版本身份先分清。** 保来源 URL、SHA、封面版本与 actual edition，比赛 Batch 2 和官网 rev 1 分开。业务选用哪个修订不能由爬到的最新版本替用户决定。
2. **先建本版层级，再取完整技术单元。** 用目录、原生标题／编号／字体与布局交叉校验；书签不完整、深度变更或错字时仍能保完整专业和条款。
3. **编号解析保持原文和多套语法。** 覆盖字母后缀、多位 edition、Building Services 和 TOL；引用干号只用于候选定位，在版本／来源内消歧。
4. **续表有正反例。** 同一 FIN 性能表跨页／不跨页同时验；Appendix 1 用整表首尾和新增行验；ELE36 用跨页同一行与两个选项列验，不能只比 OCR 字符数。
5. **原生优先、局部补读。** 外层 native 文字不能掩盖内嵌图像；图像／表格补读结果回到既有同源 observation／canonical 链，不建第二套真源。
6. **跨版测试按完整来源隔离。** 相近版本有大量复用内容，不能随机把相同条款拆进训练和评测。先用本次来源作为独立版本对照，具体训练用途另行登记。

适合先做的验收组合是：Batch 2 旧版／rev 1 的相同干号不同材料要求，原版／Batch 1 的 FIN 跨页与条件变化，Batch 3／2026 的 ELE36 混合图文表单，以及 2026 的 `.10`／TOL 编号。组合覆盖版本、结构、原生／图像与语义差异，不围着某一页反复调规则。

## 本轮证据与未做的事

本地证据根：`.local/oer-sl-version-research-20261019/`。六个官网原件各在独立版本目录，下载 receipt 含 requested／final URL、HTTP 状态、大小、SHA 及耗时；六次完整下载均 HTTP 200，未自动重试或绕过 TLS／权限。当前目录清单是“官网当前列出的六个版本”，不代表已找到全部历史撤下版本。

root 用已有 bundled Python、pypdf 和 PDFium 对六版加比赛旧版共 30134 页保存完整原生文本／书签，单次主读取 114.656 秒；这不是 OCR 速度、OER 稳定性或来源准确率。全量页码／九库范围／Batch 2 差异有 assert 检查。原生文本顺序也不是可直接发布的最终阅读顺序。

| 来源 | SHA256 |
| --- | --- |
| 2022 original | `8f384759bda31e0734d07bf5bcf5899d5a581cff826d4c3685ace51053ebbec7` |
| Batch 1 | `8479e1b7ce6885cb89c126271a31134ce2978140336ceb98a78c0252319b35cb` |
| Batch 2 rev 1 | `969e2a986d1a247b08fc18c39d7bed592b76fe6480a525973310a4daf46b549f` |
| Batch 3 | `f57cdb0d70ff72b385d0a563509542eec027b30f73131112a693dc13b06ef515` |
| Batch 4 | `dd73415b110db47793be45ed1c97009ba01aa14acfb66faeccbe1df47341f89f` |
| 2026 original | `949b55e160b04c7f00965f0ebce51fa20db958bd6d0f394205e6e8b74d7c643d` |
| 比赛 Batch 2 旧原件 | `6331c283da806846a06644814fa2271182314dbc2235c711a16e8492faaa0492` |

旧记录里 P: 的用户自存 Batch 3 路径本轮不可达，未重新映射或改认证；已使用可正常访问的官网原件，未声称与该 P: 副本同字节。本轮只有原件调查与研究／台账更新，没有新 OCR／LLM／训练、产品代码修改、构建、部署、活动 DB／索引写入或自动提交推送。

没有逐页目验六版全部技术页，也没有重跑六版正式 OER 输出；目前报告的是可复核的来源差异、结构差异与后续验收要求，不能据此声称“所有 SL 版本解析已通过”。
