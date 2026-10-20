# Drafting 最终导出身份与版式验收 20261021

本轮在正式17后端与实际外置浏览器完成同一隔离项目的三Word／三PDF下载、135页PDF渲染和逐页视觉检查。项目身份贯穿生成物已得到证据；SCT分页与转换裁字、SCC目录准确性仍失败，不能以身份或代码检查通过授予整包版式通过。所有项目身份均为明确人工采用的虚构测试值。

| 范围 | 本轮实际结果 | 本地权威证据 |
| --- | --- | --- |
| 正常人工采用链 | 拒绝顶部替换、再次明确确认替换、三文件新生成、六个实际下载均完成 | `.local/e2e-20261020/browser-formal17/round19-state.json`、`identity-applied-terminal01.json`、`export-*-01.json`及`export-*.json`实际逐文件回执 |
| 缺身份引导 | 新前端真实点击只读1个plan、0个generate，中文提示明确补齐第4步，焦点回到身份输入；未采用临时顶部值 | 同根`preflight-browser-click01.json`、`round30-snapshot.txt` |
| 前端主树 | 新身份preflight已精确采用；211/211、0失败/跳过，类型检查及生产构建通过 | `.local/drafting-identity-preflight-candidate01/main-integration04/receipt.json`、各log；session41930已接回exit0 |
| 最终PDF身份与几何 | NTT11／SCT40／SCC84页，共135；每页完整采用项目名与编号，0字形越纸面；NTT/1–11、SCT/1–40、SCC目录i–iii及正文1–81连续 | `browser-formal17/exports01/identity-page-audit01.json`；几何检查不证明表格内部无裁切 |
| PDF全部逐页视觉 | NTT11与SCT40由独立代理亲看原PNG，SCC84由另一独立代理亲看；Root另亲看关键页，SCT/SCC整体FAIL | 同根`raster01/nt-sct-visual-review01.json`、`scc-visual-review01.json`与`receipt.json` |
| 同源原件对照 | 16pin保持，24GiB Job、两个串行转换均exit0且自然Job0；原SCT29页、SCC84页 | `.local/drafting-original-render-comparison01/execution/receipt.json`、`source-output-comparison01.json`；session6162已接回exit0 |

## 实际缺陷与首损失边界

**DF-LAYOUT-SCT-20261021-01：生成后新增空白与短尾续页，仍未修复。** 最终SCT空正文页2、11、14、26、31；明显短续文页4、6、9、17、19、29、33、37。源SCT经同引擎实际转换29页，生成物40页；原件物理2有1801字符正文，生成物物理2仅111字符身份/页脚。源1238段中，生成保留1233个同paraId段，另5个是登记emptyExclusions；保留段仅54个登记静态页脚发生清空，其余1179段全文保持。24个新增分页段与部署的native layout计划逐一绑定。已清空的页脚段未compact，且连续源空段/原硬分页仍占空间；不可把所有空白直接归因24分页，需按精确节点、named destination与原件对照最小修复。静态定位在`.local/drafting-sct-layout-diagnostic01/`，未采用主树。

**DF-PDF-SCT-20261021-02：末字在DOCX到PDF转换丢失，原件转换也复现。** 两处源及生成DOCX完整文字分别为`sum of each page of Bill No. 1`和`program to write on a DVD-ROM`，runs/pPr/表格属性保持；生成PDF16与同源原件PDF11均提取为`page o`、`write o`后换行，Root亲看生成PNG也缺相应末字。不能把它写成Editor文本删除。当前机上LibreOffice对源布局的兼容问题需要修，尚未证明具体裁切属性；SCT23其他疑点仍按逐页票保留，不凭英文推测补字。

**DF-TOC-SCC-20261021-03：34个目录行页码不符，仍未修复。** 最大偏差4页。原件与生成DOCX均完整保留1 TOC及92 PAGEREF，93字段指令/缓存序列完全同，92目标书签逐一精确闭合，0缺失/重复/未闭合；TOC缓存4384字符保持，均未设置updateFields，现有转换实现没有明确目录刷新。后继实际原件PDF对照已按92个原生书签完整目标、精确outline destination、TOC link及实际页底唯一页码完成：92/92目标物理页、XYZ坐标和显示页码与生成物完全同，34个错页全在同引擎原件转换中已存在，生成新增目标位移0，最大偏差均4页。仅证明当前LO转换已有此问题，不外推原Word渲染；产品错误仍须修，不按相似标题重写页号。字段全范围和转换调查位于`browser-formal17/exports01/scc-field-inspection01/`。只对复制DOCX试验dirty/updateFields尚未实际执行，不能据标记宣称修好。

## 独立代码结果与未完成范围

Vetting身份与报告metadata修复候选实际91/91、0failure/error/skip、8 suites、63.125秒、2076pin保持、16GiB堆、自然Job0；`.local/vetting-vt02-phase02-and-report-metadata-runtime01/phase02-repair02/successor-preparation01/root-acceptance01.json`。产品修复复用既有CONFLICT分类；Recovery fixture注入同权威resolver；Report三处内容断言仅按已实证PDF渲染空白折叠。原91的85pass/3failure/3error及诊断2例仍保留；本候选尚未主树统一采用或升级运行，不能等同真实Vetting业务通过。

前端完整回归先后出现fixed baseline初次载入、canonical reload两个异步断言时序失败，保留main-integration02/03；仅让测试等待实际hash/面板完成状态，保留原内容、安全和全部资源计数断言。最终main-integration04为本次有效211结果，不能删旧失败票。

本轮没有新真实Qwen请求；Advisory原76/100业务FAIL保持，formal17单次复验已冻结但未发送。SL新scope repair02及154方法软件计划、4页真实新capture/8方法完整链方案均未执行/采用，旧七来源14页代表性结果不替代全版本完整解析与离线包。2b、长身份边界、原DOCX在Word中的独立视觉、复杂PDF与分阶段扫描仍未收口。后端正式17和受保护H2 owner均未启停；下一次升级仍须保全SQL17之后所有新增写入。
