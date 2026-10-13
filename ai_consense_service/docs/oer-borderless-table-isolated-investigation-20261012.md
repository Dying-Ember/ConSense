# 无框表库隔离实测与 OER／RAG 设计评估交接

日期：2026-10-12，Asia/Shanghai。读者：ConSense 主 agent／架构与解析设计负责人。

状态：**调查与本机实测已完成；接入方案为研究建议，尚未采用。** 本文仅交接既有实测、证据及设计约束，没有发布、修改业务解析器、切换模型或向量库。原件、完整运行证据与环境仍保留在忽略的本地目录。

先读 [OER 已确认范围与执行规则](./oer-scope-and-rules.md) 和 [实施交接](./HANDOFF-ocr-embedding-rerank-20261008.md)。现有05是已冻结开发基准，其质量与适用边界见 [固定资料开发基准](./fixed-oer-development-baseline-20261012.md)。本调查不替换它；主 agent 后续设计与运行仍以中央规则及最新用户指示为准。

## 1. 评估结论与主 agent 需要决定的事

**Camelot 值得进入无框表的候选生成层。当前stock输出不能直接作为完整逻辑表／完整业务证据采用。** 这轮已自动找到五张目标表的六列主体，Stream的数据区文字匹配达到641/642，真实空栏125/125；但多行表头、折行字段和表外条件仍有缺口，六条路线完整表均0/5。[E10][S01][S02][S03][S04][S05][S06][D01][D02][D03][D04][D05][D06]

这些事实支持先验证“有线原路线＋无框候选＋通用逻辑行恢复＋完整条件组装”的分层方案。Network可作为边界较清楚的六列主表候选，Stream可作为文字和候选边界的第二份对照。两者各自有缺陷，不能根据参考答案逐格择优、拼成一张被追认为正确的表。Hybrid在五页的rawGrid与Network逐页完全一致，当前目标页没有额外完整表收益。[E12][E13]

本轮只完成六个固定native配方，不代表Camelot全部可用配置的极限。参数搜索、专门表头解析、ML路线、扫描来源及视觉LLM都没有在本轮取得源质量成绩。主 agent 应决定下一轮的有限验证方案，先取得正常完整输出与独立源评，再讨论生产采用。

| 事项 | 当前调查判断 | 状态／设计边界 |
| --- | --- | --- |
| 保留有线表解析 | lines/lines仍有明确回归优势 | 研究建议；新provider不能以局部格收益覆盖旧完整正确表 |
| 无框表候选生成 | 先验证Network，保留Stream独立对照 | 未采用；不能把高字面诊断当整表通过 |
| 逻辑行恢复 | 多行表头和同编号字段续文需要通用处理 | 尚未实现；禁止固定页／固定bbox／固定列数修补 |
| 表外条件组装 | Notes、星号条件、缩写子表需与主表共同回取 | 尚未通过；全页chars保留不等于关系闭包完成 |
| 图片进入RAG证据包 | 必须保来源身份、原物理页、区域和完整图表组 | 设计建议；本轮未调用业务多模态LLM |
| ML路线 | 保留下一阶段对照资格 | 未安装／加载／推理，官方下载预检失败 [E18] |
| 自动采用及索引替换 | 当前不满足 | 完整表0/5；正式质量门未过 |

## 2. 本轮授权、隔离与实际覆盖

用户先要求讨论，不改业务代码，随后明确授权“用一个隔离环境真的找到这些表格测试一下看看”。本轮授权落实为独立环境和离线实验，不扩展为业务部署。

实验根目录：`D:/R&D/ConSense/.local/state/oer-camelot-isolated-20261012/run-2112-01`。解析输出为其`actual-01/`，每路线独立JSONL，另有共用原生glyph/graphics观察流。使用Python3.12.14独立venv、Camelot2.0.0、pdfplumber0.11.9；环境版本与代码身份见E03/E19/E20。

执行时间为北京时间21:26:39起，整次实际解析终态completed，墙钟211.733774秒。六路线每条访问226页，失败页均0，源PDF解析前后SHA与字节数一致。GPU调用0，解析器读gold文件0。墙钟包含共用来源观察、I/O等，不等于各方法计时简单相加。[E02]

| 完整输入 | 原物理页映射 | 页数 | 本轮SHA256 |
| --- | --- | ---: | --- |
| architectural:COM11 | 1298–1352 | 55 | `ce01d9b017107df8f8d70229de1067d5d8d0faf2b0b31995c1e867adb1649f3d` |
| foundation:PIL1 | 2671–2757 | 87 | `f7f67b7d5e3ff8ceea13e176739d77b2ee61320bda0f8c2fbcd750dbf9697930` |
| geotechnical:GIN | 4140–4223 | 84 | `13f3f24097f119dbe9939c23d7a0ac2d08f5ed58cd5d785c1836343fac3ec5de` |

上述55＋87＋84＝226，是完整解析输入页数，不是226页已逐字准确验收。完整SL母本身份SHA为`6331c283da806846a06644814fa2271182314dbc2235c711a16e8492faaa0492`，本轮只继承身份元数据，没有重读整个4300页母本。[E01]

目标五表原页／局部页分别为1312/15、1316/19、1321/24、1326/29、1330/33。五页已144DPI完整渲染并实际看图，没有用精确表区裁图帮助解析。渲染收据保留Symbol／ArialUnicode字体警告；主要表和已说明条件可读，不能据此认证全部符号。[E12][E14][P1312][P1316][P1321][P1326][P1330]

## 3. 配方与评分：哪些数字可以比较

### 3.1 解析配方

pdfplumber仅改变`vertical_strategy/horizontal_strategy`：lines/lines、text/text、text/lines；Camelot逐页调用完整PDF路径、`pages`及network/stream/hybrid flavor，其他参数保持stock默认。没有传gold、目标页特例、tableAreas或columns，没有逐格融合。所有候选都保存，不根据文字与答案一致度选择provider。[E03][E20]

Camelot保存原始`cell.text`、`rawBbox`、边标记及原生网格；库执行strip后的`table.data`另存为providerGrid。pdfplumber保留真实空字符串、None槽位与原生cell是否存在。坐标声明为`PDF_points_crop_top_left`；合并owner无法确定的格保留spanKnown=false，不能假造unit span。[E20]

### 3.2 固定参考与三个独立检查

参考与scorer在新候选产生前封存，strict封存时间UTC13:20:13，独立文字诊断UTC13:24:03。两seal均记录新候选读取0；strict12个控制和诊断7个控制通过。它们是评分机制控制，不计原件正确率。本轮沿用已暴露开发来源，非blind。[E06][E07][E08][E09]

| 检查 | 判定方法 | 可以说明 | 不能说明 |
| --- | --- | --- | --- |
| 严格origin结构／格文 | 来源anchor中点落入唯一候选格；不可复用；row/col/rowSpan/colSpan精确一致；span可证明。格文再要求去空白后的原文相同 | 声明逻辑格是否与已核来源对应 | 精确物理边界、法律owner、表外条件完整性 |
| 独立anchor文字诊断 | 唯一、不复用bbox member上比较原文；暂不要求声明行列/span通过 | 文字是否已观察到，以及结构与字面问题是否可区分 | 完整表通过、整体OCR准确率、业务采用 |
| 完整单表 | 同一个候选覆盖全部参考origin，尺寸正确、没有多余origin、没有拼片段 | 有限参考单表是否完整 | 完整业务表组与Notes/跨页/修订闭包 |

文字比较仅去空白，不改大小写、标点、Unicode或拼字。参考空栏是必须存在的来源格，None不等于真空栏。匹配不能用文字择优、最近位置修补或调行号后追认通过。[E04][E05][E21][E22]

| 固定参考域 | 表片段 | 来源origin | 说明 |
| --- | ---: | ---: | --- |
| COM11五无框表 | 5 | 672 | 主目标 |
| COM11有线表 | 8 | 167 | 回归域 |
| PIL1普通有线表 | 10 | 81 | 回归域 |
| GIN逻辑表征 | 1 | 12 | 与同页physical表征分别计分 |
| GIN有线表 | 8 | 123 | 回归域 |
| 参考记录合计 | 32 | 1055 | 可含同一来源的不同表征；不能称1055个不重复物理格或全库准确率 |

每项分母来自E04的`reliableSourceDenominators`，源参考见R01–R03。物理边界、legal owner、Notes/表头作用域、跨页续接与完整业务组仍unknown；未评分项不能写成0或通过。

## 4. 五张无框表的实测成绩

672来源格＝表头30格＋数据区642格；其中真空栏125格、非空格547格（含30表头）。独立raw复算无issues，所有保存分数和逐格指针与原始origin字段一致。[E10]

| 路线 | 严格结构 | 严格格文 | 整表结构／字面 | anchor文字诊断（含表头） | 数据区文字诊断 | 真空栏诊断 | 证据 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `pdfplumber_lines_lines` | 0/672 | 0/672 | 0/5／0/5 | 0/672 | 0/642 | 0/125 | [S01][D01][E10] |
| `pdfplumber_text_text` | 0/672 | 0/672 | 0/5／0/5 | 322/672 | 322/642 | 116/125 | [S02][D02][E10] |
| `pdfplumber_text_lines` | 0/672 | 0/672 | 0/5／0/5 | 0/672 | 0/642 | 0/125 | [S03][D03][E10] |
| `camelot_network` | 0/672 | 0/672 | 0/5／0/5 | 620/672 | 620/642 | 116/125 | [S04][D04][E10] |
| `camelot_stream` | 0/672 | 0/672 | 0/5／0/5 | 641/672 | 641/642 | 125/125 | [S05][D05][E10] |
| `camelot_hybrid` | 0/672 | 0/672 | 0/5／0/5 | 620/672 | 620/642 | 116/125 | [S06][D06][E10] |

Stream的数据区641/642包含真空栏125/125；数据区非空项为516/517。四张21项数据表分别126/126；1330为137/138。五表表头诊断均0/30，是完整来源表头格没有在同一个对应逻辑格中恢复；不表示表头文字全部消失。[E10][E11]

### 4.1 逐页成绩与候选形状

下列文字列都是独立anchor诊断；严格结构／完整表仍全部未通过。形状为parser保存的rawGrid，包含同页额外候选，不是正确源网格。

| 原物理页 | 来源逻辑尺寸 | Network形状／文字诊断 | Stream形状／文字诊断 | Hybrid形状／文字诊断 | pdfplumber text/text形状／文字诊断 |
| ---: | --- | --- | --- | --- | --- |
| 1312 | 22×6 | 25×6／126/132 | 40×6／126/132 | 25×6／126/132 | 74×10／78/132 |
| 1316 | 22×6 | 25×6；6×3／126/132 | 45×6／126/132 | 25×6；6×3／126/132 | 88×12／65/132 |
| 1321 | 22×6 | 25×6；5×3／126/132 | 40×6／126/132 | 25×6；5×3／126/132 | 87×11／21/132 |
| 1326 | 22×6 | 25×6／105/132 | 37×6／126/132 | 25×6／105/132 | 70×8／72/132 |
| 1330 | 24×6 | 27×6／137/144 | 31×6／137/144 | 27×6／137/144 | 82×8／86/144 |

逐页数字：D02/D04/D05/D06的`fragments`按section、originalPage、category定位；形状见E13。lines/lines五页均无目标表候选；text/lines只得到标题／下划线附近碎片，1330无候选。[E12][E13]

### 4.2 为什么字面高、结构仍为零

Stream前四页数据行比参考逻辑行整体+6，1330为+5或+6；五目标页唯一成员的spanKnown均true、列偏移0。严格结构0主要来自声明逻辑行体系与来源不一致，不能把它解释为unknown span统一否决。[E10]

1312保存的Stream表为40×6：页眉／下划线／标题占据前部行，数据库列标题拆在raw rows4–6（Stored in/the/database）；来源表头anchor落在row5，其他列多为空；正文从candidate row7开始而source row1开始。[E11]

1330的来源逻辑r13c1为`Waterproofing Installation Date`。Stream candidate row18只有`Waterproofing Installation`，row19只有`Date`；Network/Hybrid为row15/16。Date字符没有消失，是同一来源格被拆成两行，当前冻结评分不能拼回后计通过。[E11]

Network/Hybrid在1326有22个来源anchor缺匹配，覆盖一整列（含表头），即使rawGrid看似六列也不构成位置覆盖证明；其他四页member有唯一对应。其数据区620/642和空栏116/125因此不能只归因于表头。[E10][D04][D06]

## 5. 表外条件与业务风险

独立作者实际查看五张完整原页，未读gold后挑provider；形态说明和30条rawGrid快照分别见E12/E13。五页Network与Hybrid rawGrid逐项相同；六列主表均止于最后编号项，optional/Notes/星号条件没有进入主表。保存全页native chars仍未建立引用关系。

| 已确认现象 | 来源位置 | 对Vetting／Advisory的实际影响 | 证据 |
| --- | --- | --- | --- |
| 数据库列标题分三行，候选拆多条grid行 | 五目标页 | 伪数据行、row整体偏移；需要通用逻辑表头恢复 | E10/E11/E12/E13 |
| 同一字段Date另起候选行 | 1330，第13项 | 字段名不完整，可能错误切块或误判存在另一项 | E11/E12/P1330 |
| Site Arrival Date的Note(2,7)规定预制厂到场特殊定义；Installation Date的Note(2,8)规定相应预制构件casting日期 | 1316，主表及Note(7)/(8) | 只看字段名或Note编号会遗漏条件，不能直接套普通施工到场／安装含义 | E12/P1316 |
| Casting Date2*仅在VPB/VPK分两天浇筑时适用 | 1330，最下方星号句 | 三个Camelot主表没有这句；不能当作无条件必填日期 | E12/P1330 |
| 三列缩写区域与主表Notes绑定未完成 | 1316 Note(9)、1321 Note(7) | 子表不能脱离引入条件单独索引；1316混页脚，1321漏下部缩写及agreement句 | E12/E13/P1316/P1321 |
| Stream吞入页眉／标题／Notes但仍漏完整附加依据 | 前四页及1330 | 更多字不是更完整证据；部分Notes续文进入第二列，1330漏后续Notes/星号限定 | E12/E13 |
| text/text整页变成8至12列，边界切入单词 | 五页 | 词被拆格，不能靠全页文字存在认定六列表完整 | E12/E13 |

这些缺陷分成两个设计问题：**主表的逻辑格恢复**与**主表关联的完整条件组装**。修好其中一个，不自动证明另一个完成。完整业务组当前unknown，没有逐项法律适用验收；本调查不把它写成新增业务通过数。

## 6. 旧正确项回归与方法选择风险

以下同一固定来源参考上的分数均按冻结strict规则，不含anchor文字诊断；每项保独立分母。COM表格数8、PIL10、GIN8；更多部分格正确不能抵销完整表回退。[S01][S02][S03][S04][S05][S06]

| 路线 | COM11结构／格文；完整结构／字面 | PIL1结构／格文；完整结构／字面 | GIN有线结构／格文；完整结构／字面 | 证据 |
| --- | --- | --- | --- | --- |
| `pdfplumber_lines_lines` | 129/167／129/167；7/8／7/8 | 81/81／79/81；10/10／9/10 | 123/123／123/123；8/8／8/8 | [S01] |
| `pdfplumber_text_text` | 1/167／1/167；0/8／0/8 | 0/81／0/81；0/10／0/10 | 0/123／0/123；0/8／0/8 | [S02] |
| `pdfplumber_text_lines` | 31/167／31/167；0/8／0/8 | 16/81／12/81；0/10／0/10 | 11/123／8/123；0/8／0/8 | [S03] |
| `camelot_network` | 14/167／14/167；0/8／0/8 | 0/81／0/81；0/10／0/10 | 0/123／0/123；0/8／0/8 | [S04] |
| `camelot_stream` | 0/167／0/167；0/8／0/8 | 0/81／0/81；0/10／0/10 | 0/123／0/123；0/8／0/8 | [S05] |
| `camelot_hybrid` | 136/167／136/167；2/8／2/8 | 46/81／46/81；5/10／5/10 | 49/123／49/123；2/8／2/8 | [S06] |

例如Hybrid的COM11格结构136/167高于lines/lines的129/167，但完整结构表只有2/8，lines/lines为7/8；不能据格总数把Hybrid设为全局替代。PIL和GIN也有明显完整回归缺口。[S01][S06]

GIN逻辑表征是另一个独立12-origin域。本次lines/lines及Hybrid为2/12，Stream的文字诊断为12/12但严格结构0/12。这里通用candidate grid可被该logical view匹配；R109的显式logical-only通道0/12不与本次直接构成0→2提升。MinerU既有HTML declared-slot域与本轮geometry-member域也不能拼曲线、比较为同域胜负；05开发基准两张已存HTML表不能被本次stock候选自动替换。[E04][S01][S05][S06][D05]

## 7. 耗时与资源：本轮能推断到哪里

| 路线 | 访问页／失败页 | 保存候选表数 | 累计方法解析秒 | 证据 |
| --- | ---: | ---: | ---: | --- |
| `pdfplumber_lines_lines` | 226／0 | 49 | 0.65 | [E02] |
| `pdfplumber_text_text` | 226／0 | 223 | 74.90 | [E02] |
| `pdfplumber_text_lines` | 226／0 | 51 | 2.39 | [E02] |
| `camelot_network` | 226／0 | 114 | 14.71 | [E02] |
| `camelot_stream` | 226／0 | 291 | 19.81 | [E02] |
| `camelot_hybrid` | 226／0 | 175 | 51.82 | [E02] |

候选表数是检测库存，不是正确表数。方法阶段计时包括各provider解析及adapter序列化准备，**排除共有来源观察和JSONL写盘**。runner先打开pdfplumber页面并读取其chars/geometry，再进入各方法capture；pdfplumber复用已打开且已观察的页面，Camelot则在各自调用内逐页read_pdf。方法阶段计时不具有相同冷启动条件，不能把lines/lines的0.65秒当独立PDF端到端吞吐，也不能仅据这些数字得出库的普遍速度排名。[E20][E23]

六方法阶段累计164.270903秒，整次墙钟211.733774秒包含共有来源观察、写盘及其他开销。上述时延只针对本机CPU、三个完整章节和当前配方；没有OCR模型或视觉LLM推理，不能推算H800逐页视觉阅读、业务或端到端延迟。Hybrid这五目标页与Network同rawGrid，方法阶段累计51.82秒对14.71秒，本轮没有证实其目标页额外质量价值。[E02][E12][E13][E23]

## 8. 原始几何异常与证据完整性

对三个完整Camelot流的源中立一次审计检查rawBbox四值finite、x2>x1、y2>y1。Network原生6092 origins中4个负高度，Stream11598 origins未发现负尺寸／非finite，Hybrid13881 origins中4个负高度；8格均在原物理1320／局部23，Network table0及Hybrid table1、originIndex128–131。[E15]

adapter角点min/max会把负高度原格包络排序为正尺寸，但这不能使原格几何合法。原始数据与失败保持，8格不能证明源成员几何／拓扑。两个作者的异常集合逐key一致，与strict/diagnostic来源对应指针交集0；1320不在32个冻结来源参考片段页集合，因此没有贡献本报告有限分数。这个零影响事实不能认证完整stock输出。[E16]

Stream前四个目标页的表bbox top约−17.9pt，部分超出页域。表外包框与单格native正尺寸是两个检查；不clamp、不将虚拟表框当精确物理边界验收。[E12][E15]

本轮最终核验源PDF当前SHA／字节数、七完整JSONL身份、runner身份、两套seal pins、独立复算和监听地址／端口／PID，均通过。Git前后状态单独留档，其他chat并行工作没有清理、reset或覆盖；不能声称整个dirty工作区没有他人变化。[E17]

## 9. 给主 agent 的设计约束与下一轮验收建议

本节是**研究建议／未采用**，用于形成可评审方案，不表示代码或接口已实现。

### 9.1 建议的职责边界

| 层次 | 应处理的事 | 必须保留的来源／状态 | 本轮已完成程度 |
| --- | --- | --- | --- |
| 输入资格与路由 | 区分可信native文字、扫描及隐藏旧OCR；识别表格候选 | 原件版本／SHA／页位／文字层来源 | 本轮只覆盖三个native完整章节；其他来源未测 |
| 原始provider输出 | 独立运行、完整保存候选与错误 | 原网格、原文字、native/canonical坐标、None/空白/span未知 | 六固定配方已完成 |
| 通用逻辑表恢复 | 表头多行、编号数据行续文、标题/主体边界、空栏/合并关系 | 每个逻辑格回指原provider格／glyph／bbox，记录变换，不改原文 | 尚未实现／验收 |
| 完整证据组构建 | 绑定Notes、星号、缩写子表、续页、父条及适用条件 | 原引用号、全文限定、组成员与缺失/歧义状态 | 当前unknown |
| 检索与排包 | RAG初筛后扩展所需完整表组；召回附图时组装实际多模态输入 | 完整组身份、来源位置、图片资产引用、版本与可回取原件 | 本轮未验证真实业务接缝 |
| 业务LLM／人工核对 | 依据完整证据判断；对歧义请求回看来源 | 请求区域、返回图像身份、判断及引用；保留无法确定 | 本轮调用0 |

不能用“向量记录有图片路径”证明LLM已收到图片；实际业务wire message需包含支持的图像内容及文字限定。不能默认每个有图候选都强制发送图片；触发策略须按表格解析缺口、任务需要、图像覆盖、模型能力及token/像素预算评估。即使强制附图，也需证明它属于同一来源版本和完整表组。

Native库在本轮文字数据区已有较高可用性，下一轮先验证逻辑恢复与完整条件组装，视觉模型可作为残差核对候选。该顺序是调查建议，不能推定视觉LLM质量已优于OCR。质量优先、Vetting/Advisory先RAG初筛、业务LLM至少256K优选512K及OCR/LLM分阶段使用仍按中央规则执行。

### 9.2 下一轮准入与接受项

1. 固定共享规则、版本与参数；选择完整正常来源输入，不拿精确gold裁图、已知目标坐标或列数帮助解析。参考与解析分离，新来源holdout未暴露后再评分。
2. 同时验证多行表头、字段续行、空栏、NA、合并格、额外grid行和主表边界。修正后的逻辑层应可逆追踪到原格，原文保持；不做全局行号减常数或见空格就向上填充。
3. 保完整Notes、星号、缩写子表与跨页关系；缺失或归属有歧义时保持unknown，不能仅有Note编号就让完整业务组通过。
4. 按完整单表／完整业务组接受，保每个旧正确origin及完整表的回归证据。禁止按来源答案逐格混provider；若设计整组provider选择，选择依据须源中立并单独验证。
5. 所有provider格先过native几何有效性检查，再转换坐标；保旋转/CropBox/UserUnit、表与格的不同资格。坐标非法的输出不得因min/max归一化获得质量资格。
6. 只在解析及完整证据组有足够证据后测试RAG召回、组扩展、实际LLM图文入包与Vetting/Advisory输出。分别评价召回缺口、排包缺口、模型拿到依据后的错误，不能以HTTP200、index-ready或零发现通过。
7. 需要ML或视觉对照时，另建隔离环境，锁完整模型revision/hash和实际render参数，证明下载、离线加载与正常整章推理成功。本轮ML权重下载预检为SSL EOF失败、下载/加载/推理页数均0；不将准备文件当ready或模型能力FAIL。[E18]

主 agent 的建议交付物：provider路由与回退方案、逻辑表／完整证据组接口、来源回指与图片入包契约、每项unknown的处理策略、完整章节及新来源评价计划。下一轮变更与索引采用应留独立版本，不覆盖当前05冻结基准或旧原始证据。

## 10. 可复算步骤与文档使用方法

1. 先按下方证据索引核文件身份，再读E04/E05明确分母与匹配规则。源参考R01–R03与三输入的路径在E01/E04，原件与参考都留在忽略目录。
2. 查看S01–S06的`aggregates`和目标`fragments`，逐格`sourceLiteral/candidateLiteral`、声明origin和匹配状态都在原报告；D01–D06增加不受结构门控的文字诊断。
3. E10给独立复算计数，E11给少量错误原格，E13保30条目标候选rawGrid的小快照；需要全部正常输出时从E02的`outputs`回取，不把小快照称完整226来源输出。
4. 看P1312等完整源页及E12确认表外条件。不要只根据scorer原文推断Notes作用域已通过。
5. E15/E16解释坐标异常以及零评分交集；E17保存本轮实际完整性检查，当前转写文档不替代或重写原终态。

可以在现有隔离venv使用E21/E22对封存candidate重新评分，输出必须用新的文件路径，不修改seal或原score。解析重跑则需新的不存在的输出目录，不能复用actual-01混合证据。主 agent 的初次评估只需要读文件，不必加载模型、打开Local DB或重跑解析。

本文可单独用于方案评估：核心数字、定义、缺陷和限制都在正文。要独立复核具体原格／图像，需同时访问证据目录；将Markdown转发到另一台机器不会自动携带忽略目录里的PDF、图片和JSONL。原件／模型／密钥／raw不应为了引用而加入Git。

## 11. 证据索引

方括号ID对应本节文件。路径是当前主机的真实保存位置；工作区内采用相对仓库路径，工作区外采用绝对路径。SHA256在本次转写时读取，文件身份和字节数另存本地`handoff-review/evidence-index-final.json`。文件里的绝对路径属于当次环境，不代表可在其他主机直接打开。另有18项关键claim的精确JSON Pointer和值交叉审计，见[E23]。

`aggregates`定位须同时匹配`sectionId`与`category`，`fragments`须同时匹配`sectionId/originalPage/category`，不得只按页号取值或合并物理／逻辑域。

| ID | 文件 | 具体字段／定位 | SHA256 |
| --- | --- | --- | --- |
| E23 | [claim-evidence-audit.json](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/handoff-review/claim-evidence-audit.json>) | 18项claim的证据SHA、JSON Pointer、核实值与限制；`auditIssues` | `d3fea5bd309737d0c07009028af9558852e1836a6ce97418a91ad33a9233929d` |
| E01 | [sources.json](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/prepare/sources.json>) | `completePageCount; units[].pageCount/originalPages/originalCompleteUnitPdf; parentSourceIdentityIsInheritedMetadataOnly` | `f9e09b1d4dca41f81024c24624762e556bab1965f34165a3907897c3d533cf58` |
| E02 | [terminal.json](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/actual-01/terminal.json>) | `status; methods.*; elapsedSeconds; sourceChecks; outputs; gpuCalls; goldFilesRead` | `fb113a269e3441fa61cf5c83dbbfb4de3df88800e79d8a51d2f2656a37211817` |
| E03 | [run-recipe.json](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/actual-01/run-recipe.json>) | `versions; python; methods; runner; tableAreas; columns; referenceAnswersRead; candidateFusion; sourceScope` | `d18e401efab7d7d787a23e534d5e03c24d03234b2d9dde49b0aef37eeda233df` |
| E04 | [policy.json](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/policy.json>) | `reliableSourceDenominators; matchingPolicy; experimentInterpretation; sourceReferences` | `417b47ef6d650b11e6ba2e9ba77c8fb5edc3c0fede83a179184ddf70f2976af3` |
| E05 | [diagnostic-policy.json](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/diagnostic-policy.json>) | `domain; matching; literal; structureDiagnostics; claimsExcluded` | `acdf4edf8023a1f0e2241df1e709c322edc41acc1910ca8549c84415a82ba73a` |
| E06 | [scorer-seal.json](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/scorer-seal.json>) | `sealedAtUtc; newCandidateRecordsRead; referenceFragments; referenceOrigins; pins` | `3cbf344dd06a0e0cdd29876fa9a8978103093aacae6b93a840c3044fdd71ee72` |
| E07 | [diagnostic-seal.json](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/diagnostic-seal.json>) | `sealedAtUtc; newCandidateRecordsRead; controlsPassed; pins` | `b05bcb044b75d7456fc0371fb7f662b0f22f5771c75940e2fe64a18343e23024` |
| E08 | [controls.json](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/controls.json>) | `passed; failed; tests[].name/passed; parserCalls; candidateReads` | `3eacd8d76017ca84258103dc78ea7f5871637ff75f314e1d7f5b794b1785ce11` |
| E09 | [diagnostic-controls.json](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/diagnostic-controls.json>) | `passed; failed; tests[].name/passed; parserOrModelCalls; newCandidateReads` | `627066504a289ef3e239560fa16c8ffeaf04d446b60dc927226a7c4520703476` |
| S01 | [pdfplumber_lines_lines.source-score.json](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/pdfplumber_lines_lines.source-score.json>) | `method; completeSourcePagesValidated; aggregates[]; fragments[].originalPage/originCorrespondence/candidateCompleteness` | `4b19826ac210baf0f4cea9742fa30e2bd19693cdf3d19fde37fa2a973fd0fd0a` |
| D01 | [pdfplumber_lines_lines.anchor-literal-diagnostic.json](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/pdfplumber_lines_lines.anchor-literal-diagnostic.json>) | `sourcePagesValidated; aggregates[]; fragments[].memberCorrespondence/declaredOriginTupleMismatchAmongUniqueMembers` | `ede7d548b1154496b06fd7471bd141fb54d405f062afb4d66cd62e3df38b10ad` |
| S02 | [pdfplumber_text_text.source-score.json](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/pdfplumber_text_text.source-score.json>) | `method; completeSourcePagesValidated; aggregates[]; fragments[].originalPage/originCorrespondence/candidateCompleteness` | `3decfb7854dd3d5304bce4d4f6ded5457888ead8a2d5affadbf881ad0fd7aaca` |
| D02 | [pdfplumber_text_text.anchor-literal-diagnostic.json](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/pdfplumber_text_text.anchor-literal-diagnostic.json>) | `sourcePagesValidated; aggregates[]; fragments[].memberCorrespondence/declaredOriginTupleMismatchAmongUniqueMembers` | `c910dce3214d6937323893ab45dba930905577b4146caac208ba7b00342b0121` |
| S03 | [pdfplumber_text_lines.source-score.json](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/pdfplumber_text_lines.source-score.json>) | `method; completeSourcePagesValidated; aggregates[]; fragments[].originalPage/originCorrespondence/candidateCompleteness` | `6d8a28a0d3079537bde219d1e241f62c3e206db4450711bdc45e713eff6692e0` |
| D03 | [pdfplumber_text_lines.anchor-literal-diagnostic.json](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/pdfplumber_text_lines.anchor-literal-diagnostic.json>) | `sourcePagesValidated; aggregates[]; fragments[].memberCorrespondence/declaredOriginTupleMismatchAmongUniqueMembers` | `9803a34dc15450087e1210a9969104bec32e899be0bcb48044f67e91f62f9508` |
| S04 | [camelot_network.source-score.json](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/camelot_network.source-score.json>) | `method; completeSourcePagesValidated; aggregates[]; fragments[].originalPage/originCorrespondence/candidateCompleteness` | `6e20686a657678563978b6760a32cb5056204bdd30e5992008e9052d09cb7d0e` |
| D04 | [camelot_network.anchor-literal-diagnostic.json](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/camelot_network.anchor-literal-diagnostic.json>) | `sourcePagesValidated; aggregates[]; fragments[].memberCorrespondence/declaredOriginTupleMismatchAmongUniqueMembers` | `9069a57cf912770a99273be03c95cd8cb7809b44e7323b6dd89a81ca26e86584` |
| S05 | [camelot_stream.source-score.json](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/camelot_stream.source-score.json>) | `method; completeSourcePagesValidated; aggregates[]; fragments[].originalPage/originCorrespondence/candidateCompleteness` | `fc650abffc2f1af6f29127c0bd888b1f3c3aa9265aecb18955df4cf6a4536cae` |
| D05 | [camelot_stream.anchor-literal-diagnostic.json](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/camelot_stream.anchor-literal-diagnostic.json>) | `sourcePagesValidated; aggregates[]; fragments[].memberCorrespondence/declaredOriginTupleMismatchAmongUniqueMembers` | `e3240e0a08906ebef04f1a1e1f563969a6f597a7e2bd07a65240b8ae1155dcf6` |
| S06 | [camelot_hybrid.source-score.json](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/camelot_hybrid.source-score.json>) | `method; completeSourcePagesValidated; aggregates[]; fragments[].originalPage/originCorrespondence/candidateCompleteness` | `1b6ccdaaf4bbda676f2bab42c7f58bb0ce29ce7447e36e6847b57b9d6eb98fe6` |
| D06 | [camelot_hybrid.anchor-literal-diagnostic.json](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/camelot_hybrid.anchor-literal-diagnostic.json>) | `sourcePagesValidated; aggregates[]; fragments[].memberCorrespondence/declaredOriginTupleMismatchAmongUniqueMembers` | `4dabd5a0e6e6ad6e5cd8d3fa9f57923fc2d74c79701ab1600d906c143ea56bdb` |
| E10 | [independent-review.json](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/independent-review.json>) | `methods[].aggregatesIndependentlyRecounted[].counts; sourceCOMFiveTableBreakdown; issues; savedReportAndRawConsistencyPassed; nativeInvalidSourceCorrespondenceCount` | `b460c6cc39537af1e7776a62c1723ba368bc3efa138f48a091939f6d2499fac9` |
| E11 | [preserved-raw-example-evidence.json](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/preserved-raw-example-evidence.json>) | `examples[] selected by method/originalPage; headerRawOrigins; raw grids/continuation examples` | `0ca9f85c129507e08bbf934cadcad410dfbf092368b9d8b9330fd14b79d2426e` |
| E12 | [qualitative-five-page-review.md](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/independent-review/qualitative-five-page-review.md>) | `直接观察到的来源形态; Network/Hybrid; Stream; pdfplumber; 1316/1330限定条件` | `0a94702019f2a53e7cceb5ddcb19c5139852a5bb11d598910f0f50c6a71c3516` |
| E13 | [target-rawgrid-snapshot.json](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/independent-review/target-rawgrid-snapshot.json>) | `candidateRecords[].originalPage/tables[].rawGrid/bbox/candidatePageLineSha256; sourceImages[].path/sha256/viewedByReviewer` | `4d2af13c0b251981bf04496043f2a9131836e857eccda569a223caa98bad5a2e` |
| E14 | [source-render-receipt.json](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/source-review/source-render-receipt.json>) | 根为数组：`[i].source/localPage/originalPage/output/sha256/renderer/argv/stderr` | `ce47c1d2406035cd1a64ad0bda81957d97b9b39dc97c9f1f006886543384afff` |
| E15 | [native-geometry-audit.json](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/candidate/native-geometry-audit.json>) | `methodStatistics; invalidNativeOrigins; guard; finding; invalidNativeTables` | `cbd3af6b3df153bb7f0ec36768b74de1f0da481d5b35081c116f34c6f1091417` |
| E16 | [native-geometry-source-intersection.json](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/native-geometry-source-intersection.json>) | `invalidSignatureSetsMatch; invalidOriginCount; invalidOriginalPages; savedScorePointerIntersections; strictSavedScoresChanged` | `9fe6aab4a8cb648b3add99edb64dc73ca5b8fd18a4b5ec35bee23f503a0e1f9c` |
| E17 | [integrity-receipt.json](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/comparison/integrity-receipt.json>) | `checks; allChecksPassed; evidence[].path/bytes/sha256; qualityAccepted; baselineAdopted` | `63825a5d6e2d3d3b9a1b0f2ffc9842cdbbe77ca882e143664ec96db5d9c08292` |
| E18 | [static-precheck-receipt.json](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/ml-prepare/static-precheck-receipt.json>) | `officialHfLocalNetworkProbe; checkpointFilesDownloaded; modelsLoaded; sourcePagesInferred; mlRuntimeReady; pendingGates` | `900e393687ff4e891e0bb5d37d6cd503f14d7325d5247562760c3ea4bd48366a` |
| E19 | [requirements.lock.txt](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/requirements.lock.txt>) | `Exact installed package versions` | `a1849b56dfa274d9d1307f2299312ded46fd0e3ced650d9dc5526c0926972aeb` |
| E20 | [run_full_tables.py](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/candidate/run_full_tables.py>) | `METHODS; extract_pdfplumber; extract_camelot; spanKnown/rawBbox preservation; main` | `dc2f8a4e5614d1c8a9450a1e6dd39dd15d4cbdbbc1199957e0c13d7528643bcc` |
| E21 | [score_isolated_tables.py](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/score_isolated_tables.py>) | `load_policy_and_references; preliminary_matches; score_fragment; evaluate` | `fbb916861413eadaebcceb30c9b8864020019ea0de1dac17c040891bd9fba597` |
| E22 | [diagnose_anchor_literals.py](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/diagnose_anchor_literals.py>) | `diagnose_fragment; evaluate; controls` | `3612c1d7a5bc054c923972aa58d5cd12bcbbd1c023b5c81dbbc8346230133f10` |
| R01 | [sealed-source-reference-final-01.json](<C:/Users/Administrator/.codex/local-state/consense-oer/sl-next226-source-only-reference-20261012/prepare-01/source-reference-batch-01/sealed-source-reference-final-01.json>) | `groups[].fragments[].cells; literalText; visualLiteralSha256; verified member/source geometry` | `e8330d8125066ab6898d094f0e1149552c879f435adfdb7cc926ae716721df98` |
| R02 | [sealed-source-borderless-reference.json](<C:/Users/Administrator/.codex/local-state/consense-oer/sl-next226-source-only-reference-20261012/prepare-01/source-reference-borderless-batch-02/sealed-source-borderless-reference.json>) | `groups[].fragments[].cells; literalText; visualLiteralSha256; verified member/source geometry` | `864aa0075c7b3d6f2e2c0b188171caf8c9bdd35a37cfa9974d5dc15bc9227152` |
| R03 | [sealed-source-reference.json](<C:/Users/Administrator/.codex/local-state/consense-oer/sl-next226-source-only-reference-20261012/prepare-01/source-reference-ordinary-batch-03-v2/sealed-source-reference.json>) | `groups[].fragments[].cells; literalText; visualLiteralSha256; verified member/source geometry` | `b7f5ab131d5df1c5a12df5d38523008deb82a9c69590ce18a9218d935eca19cb` |
| P1312 | [source-p1312.png](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/source-review/source-p1312.png>) | `Complete original page rendered at144DPI; visual check per E12/E14` | `0a3ec16570caf6ba2f5a22a5f1e491fc62d7479b238950c83f78fb9e16e38b12` |
| P1316 | [source-p1316.png](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/source-review/source-p1316.png>) | `Complete original page rendered at144DPI; visual check per E12/E14` | `df15157cf03944ac9ed9edfa1a7a1332bbc0bc868b29c16a7d8588ff50f14292` |
| P1321 | [source-p1321.png](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/source-review/source-p1321.png>) | `Complete original page rendered at144DPI; visual check per E12/E14` | `f02279eda7817d1849e0f6cd3f5c54db9719038c9f05d56e33873ac6ca04239b` |
| P1326 | [source-p1326.png](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/source-review/source-p1326.png>) | `Complete original page rendered at144DPI; visual check per E12/E14` | `3dd688c4f621ae7d67ed85dcc2dca08b5e117962aee7e8d2b3f3e3619119d6bd` |
| P1330 | [source-p1330.png](<../../.local/state/oer-camelot-isolated-20261012/run-2112-01/source-review/source-p1330.png>) | `Complete original page rendered at144DPI; visual check per E12/E14` | `f6047e4b62af3a94d8ec6c5d89c757a772cfd51d75d5350663b23c4187879ffd` |

[E01]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/prepare/sources.json>

[E23]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/handoff-review/claim-evidence-audit.json>

[E02]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/actual-01/terminal.json>

[E03]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/actual-01/run-recipe.json>

[E04]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/policy.json>

[E05]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/diagnostic-policy.json>

[E06]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/scorer-seal.json>

[E07]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/diagnostic-seal.json>

[E08]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/controls.json>

[E09]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/diagnostic-controls.json>

[S01]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/pdfplumber_lines_lines.source-score.json>

[D01]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/pdfplumber_lines_lines.anchor-literal-diagnostic.json>

[S02]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/pdfplumber_text_text.source-score.json>

[D02]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/pdfplumber_text_text.anchor-literal-diagnostic.json>

[S03]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/pdfplumber_text_lines.source-score.json>

[D03]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/pdfplumber_text_lines.anchor-literal-diagnostic.json>

[S04]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/camelot_network.source-score.json>

[D04]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/camelot_network.anchor-literal-diagnostic.json>

[S05]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/camelot_stream.source-score.json>

[D05]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/camelot_stream.anchor-literal-diagnostic.json>

[S06]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/camelot_hybrid.source-score.json>

[D06]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/camelot_hybrid.anchor-literal-diagnostic.json>

[E10]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/independent-review.json>

[E11]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/preserved-raw-example-evidence.json>

[E12]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/independent-review/qualitative-five-page-review.md>

[E13]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/independent-review/target-rawgrid-snapshot.json>

[E14]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/source-review/source-render-receipt.json>

[E15]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/candidate/native-geometry-audit.json>

[E16]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/native-geometry-source-intersection.json>

[E17]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/comparison/integrity-receipt.json>

[E18]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/ml-prepare/static-precheck-receipt.json>

[E19]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/requirements.lock.txt>

[E20]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/candidate/run_full_tables.py>

[E21]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/score_isolated_tables.py>

[E22]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/scoring/diagnose_anchor_literals.py>

[R01]: <C:/Users/Administrator/.codex/local-state/consense-oer/sl-next226-source-only-reference-20261012/prepare-01/source-reference-batch-01/sealed-source-reference-final-01.json>

[R02]: <C:/Users/Administrator/.codex/local-state/consense-oer/sl-next226-source-only-reference-20261012/prepare-01/source-reference-borderless-batch-02/sealed-source-borderless-reference.json>

[R03]: <C:/Users/Administrator/.codex/local-state/consense-oer/sl-next226-source-only-reference-20261012/prepare-01/source-reference-ordinary-batch-03-v2/sealed-source-reference.json>

[P1312]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/source-review/source-p1312.png>

[P1316]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/source-review/source-p1316.png>

[P1321]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/source-review/source-p1321.png>

[P1326]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/source-review/source-p1326.png>

[P1330]: <../../.local/state/oer-camelot-isolated-20261012/run-2112-01/source-review/source-p1330.png>
