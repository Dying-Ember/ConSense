# 全部赛方文档重解析与历轮来源问题对照

日期：2026-10-15，Asia/Shanghai。用户本轮要求按目前代码重新解析全部文档，并与最后一次训练后的结果比较。该要求恢复本地来源解析效果检查；不恢复训练、调参、候选权重采用、检索／重排或业务评分。

**结论：有局部工程与原生结构保全收益，尚未证实整体解析质量提高。** GCC已避开整棵context物化OOM；项目SCC的三个公式改为保留原件一致的OMML树；可比较Word的旧来源覆盖保持。扫描表单、COM11完整表格及其字段／关系仍无可评分的新结果。全量原生／POI执行检查已收口，完整双模型OCR全量对照尚未完成。

## 范围和运行身份

已逐件重新核对赛方资料包全部 **48** 原件的实际字节、大小和 SHA：**39** 件进入解析（27 DOCX、12 PDF），另有3件组成元数据、2件图纸和4件隔离评价答案。图纸仅识别身份后排除；APB／APU是文字清单，保留解析。原件、gold、fixed05／Local05、活动数据库及索引均不覆盖。

来源及可重算证据：

- [全量原件账本](../../../.local/oer-all-document-reparse-20261015/corpus-preparation/corpus-inventory.json)，SHA `20d7e0be79dd7c0ff8c2582d4976a0c25f51eee532330833dba0e311e0d35a7c`。
- [库存守恒自检](../../../.local/oer-all-document-reparse-20261015/corpus-preparation/inventory-selfcheck.json)：48件身份／路径／SHA无重复、48件当前字节与冻结值匹配、39+3+2+4=48；旧179个SL输入完整覆盖该版物理1127–4300共3174页，无缺口或重叠。
- [额外SL版次可达状态](../../../.local/oer-all-document-reparse-20261015/corpus-preparation/additional-sl-versions.json)：用户提供的2025 Batch3原路径返回WinError1326（凭据错误），不是已经证明文件不存在；官网六项仍是目录候选，没有本次下载或可核的完整PDF字节。不套用2024版页范围。

今天已闭合的 `actual-native-02` 是提交 `4bec277` 的真实正常parser隔离运行：186个完整输入实际对应8原件，SL按179个完整技术分册输入；3257个PDF原页位置、3件DOCX。该轮耗时1916.812秒、输出12525252557字节、0训练／DB／原件写入；它是原生文字与CPU表格相位，视觉OCR关闭。186次正常返回的实际parseStatus为 **2 PARSED、182 PARTIAL、2 FAILED**，不能称186件解析成功。首轮OOM／multipart失败不删除。

本轮对终态重新运行冻结比较器，输出到全新 [final比较报告](../../../.local/oer-fixed-six-reparse-20261015/comparison-native-02-final-current-01/comparison-report.json)，SHA `0cd8594d1ab63e0b5acc94847893b33c3d9f956bb01614821bfcc96ca82c564c`。比较器7项自检、原网格评分器12项控制及20个历史参考pin通过。前轮partial报告不当最终结果。

当前main为 `10c3eaf3057a3a45cbf8747468cd48ddf78287aa`。相对4bec277，PDF／OCR／HttpSupport／native表格生产路径未改；DocumentParser新增显式Drafting legacy-math重建旁路，普通parseDocx仍传false。旧轮因此可作同普通路径的既有证据，但运行提交仍标4bec277。本轮另用当前已冻结JAR重新执行全部27件范围内DOCX和此前未覆盖的7件完整PDF（200页），新清单及输出在 `.local/oer-all-document-reparse-20261015/`；其终态单独结算，不混改旧运行身份。

## 全量执行终态

最终[执行账本](../../../.local/oer-all-document-reparse-20261015/all-document-execution-ledger-final.json)已闭合，SHA `26741f72195a74061b54aea11b34b30e346fa1f67b454c387c74f8c01423a8ce`。它逐项关联原件／子件SHA、原页映射、实际提交、driver／完整输出／终态、复用和人工身份说明。39原件按完整SL分册展开为217逻辑输入：本轮34个当前构建输入＋183个今日既有PDF输入；旧三模板已由本轮27 DOCX替代，不重复计数。PDF原页位置3457＝旧3257＋新增200。

| 层次与完整分母 | PARSED | PARTIAL | IDENTITY_PENDING | FAILED |
| --- | ---: | ---: | ---: | ---: |
| 主执行口径（含旧explicit-source身份），39原件 | 19 | 12 | 6 | 2 |
| 主执行口径（含旧explicit-source身份），217完整逻辑输入 | 19 | 190 | 6 | 2 |
| 另补六份人工身份parser-only诊断后，39原件 | 19 | 15 | 0 | 5 |
| 另补六份人工身份parser-only诊断后，217完整逻辑输入 | 19 | 193 | 0 | 5 |

后两行只替换六个待确认文件的parser诊断视角，不修改前两行入口能力结果或活动身份注册。Appendix H／Appendix I／APU经explicit exact-source身份详细解析后为PARTIAL，FoT／APB／APZ为FAILED；原六项自动pending均保留。这不是人工替生产链授来源身份通过。

复用的179个SL子件及AoA／APL沿用前轮已授权的exact-source身份与完整页映射，共181逻辑输入／3原件属于旧显式身份诊断；上述主执行统计不能称全39件自动身份识别成功。仅未注入人工来源身份的实测36原件／36输入为19 PARSED／11 PARTIAL／6 IDENTITY_PENDING／0 FAILED。两种身份来源分开，不授新来源自动识别或跨版本泛化通过。

本轮34输入和另6次首次详细解析共实际native wire4次（跨轮共享上限200），另4次readiness HTTP；从全局首轮时钟到CPU最终退出396.893秒／3600秒上限、四拥有输出根239331995字节／32GiB上限。0视觉模型／0训练／0DB／0原件写。两个CPU服务均依owner STOP收口，真实exitCode为1且root与descendant释放已观察；18770无监听，活动8080及预览进程未操作。不能把owner-stop exit1重写exit0来称测试绿。

当前JAR SHA为 `3fe51ad2dd8a6f41e468fb612fbf44e907ce017545a541b04358551c1649d971`，源码和674个classes/resources已与已绿package03逐项核对。本轮没有重新全量代码测试；复用的是原full03基线及准确Drafting定向增量，解析结果独立结算。

## Word同源旧正确保持及公式原生保全

[Word比较报告](../../../.local/oer-all-document-reparse-20261015/docx-comparison-02/docx-comparison-report.json)实际消费27份完整normal gzip，SHA `9317a8359bec5b61ea91c38034f53ae6fd60e71763d2db1bc6d66ebebeb75a3c`。旧22份完整输出库存与本轮交集为20，另外7份没有可比的同SHA完整normal（3份2a模板、3份2c邮件、G305）；不借其他同名文档分数。

- 20份同源文件已核的正文／物理cell／编号／非空header-footer来源覆盖旧正确全部保持；这是这些具体域的守恒，不是整件CER或全部语义质量通过。
- 旧13955块中13952块五字面通道payload完全一致；19份文件的全部旧字面块保持。唯一变化是项目SCC的3个公式段，旧plain text带替换乱码，当前plain text不再把公式强行展平，并另存完整math原生对象。
- 项目SCC当前完整输出中，3个公式原树、3个分数、18个下标、57个数学文字叶及父节点序号、2条公式顺序边均与同sourcePart／paragraph锚的原件参考一致；42 typed角色、684个显式producer nodeID仍未知，数学解释和渲染显示未验收。不能把保留XML授为R129候选侧车所有typed能力已进入main。
- 项目PRE旧10898块字面保持，新增30个空text原生对象块标为 `native_ooxml_unrendered`，不记新增正文／已解析资产／准确率收益。独审纠正了header来源通道：11994格本来均已存在，新增正确为0；初版比较报告保留，纠正后的fresh02为本节依据。

Word对照代码自检和SCC缺失／分子分母顺序／文字／sourcePart SHA／重复五个负控已执行；保真、typed语义、视觉显示和全源CER继续分开。

进一步[原生对象与原ZIP资产核验](../../../.local/oer-all-document-reparse-20261015/docx-native-object-integrity-02/word-object-integrity.json)覆盖93个对象／62个对象块：43块XML逐属性与文字完全相同，另外19块仅VML `o:gfxdata` base64属性换行经XML标准空白归一，解码后的二进制SHA相同；按62块结构与二进制payload等价保留，不能把19也记strict XML exact。45个原package资产引用的实际bytes和SHA全部核合。该项只证明保留完整性，仍标unrendered。最后[终封收据](../../../.local/oer-all-document-reparse-20261015/docx-final-03/terminal-receipt.json)封存实际比较／对象核验源码、基线副本和最终两个报告pin；早期被纠正输出保留为历史。

## 最后训练和最近有效质量基线

最后一次真实训练是 **R153**；R157／R158不是新的权重训练。R153最后一段16次更新／112次完整训练计算，teacher loss从2.9686845115降至2.4632216096；训练后七页合法协议为0/7，fresh只绑定2/7且两项都未合法、其余5项未知。该候选未采用、没有执行正常parser、没有通过来源质量。历史九段累计496次训练计算／124次更新，不能换算为准确率或epoch数。

R157的最新来源判定由R158保留：74写区定位为31正确／0错误／43未知；写区状态与可见值分别1正确／73未知；57完整语义字段全未知；11文档关系2正确／9未知。比较保留每个旧正确项的身份和当前验证状态，不把旧正确直接复制到新结果。

同源冻结分母与限制见[既有比较器说明](../../../.local/oer-fixed-six-reparse-20261015/comparison-preparation/README.md)及[原未决来源账本](../../../.local/oer-fixed-six-reparse-20261015/preparation/outstanding-source-coverage.json)。训练loss、参考侧车、候选产物存在、源XML保留和正常产品入口分别报告。

## 已闭合固定来源对照

| 来源问题 | 本次可核结果 | 判断及边界 |
| --- | --- | --- |
| GCC传输与169条定义成员文字 | 新轮72个选页经39实际批次返回，避开首轮完整context物化OOM；169/169同原生ID成员文字保持 | 工程传输改善；66个定义框／owner／顺序等完整结构没有因此通过，全74页CER未验收 |
| GCT全部43可见单元 | 42字面一致／1未知；原页2页脚原生两行与旧侧车合成一单元保持区别 | 未测得字面改善；11组／parent／条件／空控件不能凭字面一致判通过 |
| SL COM11五组672格 | 新轮首批临时PDF清理WinError32后，相关后续页未执行；三方法均保持未知 | 不能比较网格质量改善；12外部成员／87关系／5完整组继续未验 |
| AoA／APL七页的57字段／74写区／11关系 | 两原件本轮parseStatus FAILED、0 blocks；视觉OCR调用0；新语义域全部未知 | 本轮没有可评分新结果，不能关闭R153及R157遗留问题，也不能据不同运行相位判模型回退 |
| 2a三份固定模板 | 没找到相同SHA的旧正常质量基线 | 新完整输出只形成基线，不能借用2b同名项目模板或项目SCC的R129结果声称改善 |

SL完整性另经[独立逐页账本](../../../.local/oer-all-document-reparse-20261015/corpus-preparation/native02-completeness-audit.json)重算：3174页＝441页返回native候选观察＋455页capture失败无观察＋2278页因前批失败未执行。三种方法各保相同完整分母，候选存在不等于表结构正确。普通coverage的2447 parsedPages／727 failedPages／3174 needsReviewPages也不当来源正确率。

COM11的失败raw CAS也重新核过：只保原1298／1299／1300页；R116五表实际要求1312／1316／1321／1326／1330页，交集0，三种方法各672 origin全部未知。[raw可用性收据](../../../.local/oer-all-document-reparse-20261015/corpus-preparation/com11-raw-candidate-availability.json)保留实际bundle SHA及完整缺口，没有从其他输出或gold拼输入。

GCC／GCT的Camelot两方法报 `PDFTextExtractionNotAllowed`，须与pdfplumber观察分别保存。SL的WinError32已在自产三页PDF上复现为PLAYA mmap生命周期缺口；本轮没有修生产依赖或规避PDF权限。诊断见[既有生命周期证据](../../../.local/oer-fixed-six-reparse-20261015/native-lifecycle-diagnosis/DIAGNOSIS.md)。

## 视觉运行的实际边界

本轮另在自有Windows Job中完成45秒上限／4GiB内存上限的依赖与设备检查，实际8.922秒exit0：Paddle3.3.1、CUDA编译支持及官方 `is_bfloat16_available('gpu:0')` 均为真。该检查 **0模型加载、0识别推理**，不能称模型ready。证据见[实际设备检查](../../../.local/oer-all-document-reparse-20261015/readiness-preparation-01/dependency-device.json)及[拥有与退出收据](../../../.local/oer-all-document-reparse-20261015/readiness-preparation-01/receipt.json)。

完整双provider仍没有可直接运行的冻结配置：Linux Paddle环境缺件下载失败；MinerU官方完整BF16运行环境在WSL；同OS双provider的真实拥有／释放准入、完整runtime资产pin、实际模型BF16参数核验及总8192次SDK内部尝试计数接缝尚未完成。旧FP32／FP16或未采用LoRA不得冒充本次同配方BF16。现成[provider review](../../../.local/oer-fixed-six-reparse-20261015/execution-preparation/provider-review-01/runtime-review-result.json)保留这些缺口，本轮设备检查只更新设备可用性事实。

**当前结论：传输和原生文字保全已有实证收益；尚不能确认架构调整提高了最后训练后仍未决的复杂表单／完整表格来源质量。** 需要先消除native文件生命周期阻塞、接通可审计的完整双provider，再按相同原件、配方、完整分母和旧正确项复测。现有证据优先指向工程接缝，不支持现在追加训练。

本地全量原生／POI检查、同源可比域重算和自有进程收口已结束；本报告不宣称全部来源质量完成、完整双OCR重跑完成或业务／浏览器／H800验收通过。当前未决优先顺序为native文件生命周期、自动身份与完整视觉运行接缝、再按原57／74／11及COM11完整域重测；不以新一轮训练代替这些接缝。
