# 三模块统一集成与 SL 全版本交付检查点

日期：2026-10-20。统一 Goal 继续 active。本轮可交付代码已采用主树并完成组合检查；按用户明确要求先提交推送本轮检查点，不等待整个 Goal 完成。Git 最终分支、commit 与远端相等核对保存在忽略的本机回执，不能把准备状态写成已经推送。

## 本轮实际通过的范围

| 验证 | 实际结果 | 证据（相对 `.local/e2e-20261020/`） |
| --- | --- | --- |
| 新统一后端组合及同源 JAR | 单轮127/127，0 failure/error/skip，20 suites，87.547秒，2949输入pin保持，自然Job0；16GiB堆，复用纯JVM runner，无新增Job cap | `checkpoint-integration127/root-acceptance01.json`，SHA `0b7f40f04332abed56877325360f13324324116d808841ea77f9e9cb5da1535c` |
| 前端回归 | 210/210，0 failure/skip，33492.9161ms；另 Vue类型检查通过、Vite build17.91秒通过 | `checkpoint-frontend02/root-acceptance01.json`，SHA `f201a345e715d1afbd852ff0aebe98bec8f0393422cb18b041324fe55bf167cf` |
| Drafting真实2a原件生成／预览／正文编辑 | 1/1，102.969秒，24856输入pin保持，自然Job0；三DOCX、NTT11页预览与PDF导出同字节，正文改动只涉及document.xml，其余部件保持 | `drafting/native-story-service-integration11/preparation03/previewbody-high16-terminal05/root-acceptance01.json`，SHA `3a32a27a5965c4f8688e753d87ecffdd5f8f800f02f49289dc7213690907becf` |
| Vetting reader04定向回归 | 单轮42/42，41.063秒，3903输入pin保持；不与下行或旧37重复相加 | `vetting/duplicate-publication01/reader-report-presentation04/bounded42-highheap01/root-acceptance01.json`，SHA `14d2a0d876466bf6162b6e4a5c8584354c6429c508dfe1e4a7cec977c63232b7` |
| 同一归档内容的reader04生成 | 另一次1/1，36.531秒，3899输入pin保持；16个finding原字段、69人工记录、47引文保全 | 同根 `archived-reader04-highheap01/root-acceptance01.json`，SHA `55c6cd5d8124a59872c8a3031184fa6049f74fa02e3ad8af8c400bdd3cde9c6b` |
| 新产物逐页视觉 | Drafting NTT11页、Vetting Word40页／native PDF42页全部亲看，未见阻断几何版式问题；共93张页面观察，不能当93份原件或业务通过 | `checkpoint-visual-acceptance01.json`，SHA `2488b5cec09f5b0b0c12981a834f358c8645e4405ded5f535455fe09552436b2` |

127项是新的统一组合：native／canonical／Artifact39、reader42、candidate-container6、Qwen观察31、纯合成报告format4、语言边界5。当前全部主源已编译，JAR SHA `a9f4ff6061db6bbf303dc0f39dfa196804c2b498b7b9cb209ed6972cdc8b45d9`，80,361,920字节。这不是默认发现全量套件、真实provider、共享HTTP或浏览器验收；此前各轮红票与环境补证保持独立。测试使用隔离H2，0共享SQL、OCR或真实LLM。

## 修复与采用

精确采用30路径：Drafting7产品／8公测／Artifact语义适配1、Qwen4产品／1公测、Vetting4产品／5公测。每个目标先匹配原主树pin、保全原字节，再采用对应实际通过快照；没有整树覆盖。采用票 `checkpoint-main-adoption01/receipt.json` SHA `33ed5b1d2e7d48307c1296d64684835b60115fc7aded5acffab5533afc97e313`。

Drafting修复已进入正常生成链：采用的顶级合约身份写入原生story，保留源部件、样式、字段、分节关系及正文编辑后的story溯源；canonical大树哈希使用与旧持久JSON语义等价的流式入口。旧terminal04因验收白名单拒绝LibreOffice本包opencltest而中止；terminal05沿已接受soffice.com入口，一次固定48个精确PE路径及字节哈希，保留Java上限1及原终态门，实际转换成功。旧失败仍保留。11页NTT逐页项目名、编号及NTT/1–11可见序号正确，OOXML PAGE字段也另由Root核对。

Vetting读者修复保留原finding／人工Checklist／47引文，不据候选容器自动合并法律问题；来源标题、label与首引文成组，17主题逐项分段，保持原条件和未完成状态。新Word渲染31.813秒／40页、native raster6.094秒／42页，各自然Job0、975输入pin保持、24GiB Job；新页面票独立于旧reader03。续页所属标识与尾页留白仍可改进，当前非阻断。

Qwen新增可选sealed HTTP JSON实体观察和实际credential一致性校验；默认关闭，真实dispatch仍须关联durable ledger，不宣称TLS抓包。默认27B和Flash-Next语义保持；本轮0新真实模型请求，上一真实27B76/100、业务FAIL不因代码回归通过而改变。

提交前frontend01原为209/210，第三张图尚在WebCrypto校验时固定12次event-loop flush已经结束。仅在原测试添加5秒有界的实际资源完成等待，保留原三图、顺序、escaping、未知状态和零业务调用断言；没有改产品校验。首次Vite遇系统TEMP文件移除Access denied，改为忽略目录内独立TEMP后成功，无ACL修改、全局配置变更或强删临时文件。两次原失败日志均保留。

两份可选私有归档回放方法完整源码先留在忽略目录，再与可提交的4个format／5个language合成回归分开。TCP诊断两文件继续留本地、不纳本轮提交。原件、模型、数据库、密钥、大量运行证据均不进入Git；Unicode字表及许可证、native edition短标签与哈希元数据可版本化。

## 明确未收口

1. Drafting的SCT／SCC最终PDF、2b项目样例、长项目名称／编号、多文件最终包与最新浏览器全链，不能外推NTT11页结果。
2. Vetting项目身份一致性VT02第一阶段仅候选及独审，新组合未带入；后半lifecycle／DTO／Writer未接入。当前catalogue中APP-I-002是Generally/1、APP-I-007才是第15页Generally/2；旧将该页直接对应002的初步定位须更正。copies理由与适用条件的相关性分别追实际packet，不能归为单纯渲染错误，也不能改历史raw或人工记录。
3. Advisory新观察尚未统一升级或真实ask；最新提示词实际发送、来源／条件与引用、浏览器业务质量仍需继续有限复验。
4. OER复杂原生PDF、跨页结构与分阶段扫描质量继续按原分母推进，图纸排除，不用代码绿票替代来源质量。
5. [SL全版本清单](../specs/sl-technical-offline-package-20261020.md)覆盖官网六版＋独立比赛Batch2共7个已取得来源，30134容器页／22214技术页位置。所有版本的高质量解析结果、全部成果修订和数据库均须带入比赛，不能只取最新。六官网新版仍未正式OER验收；完整DB/CAS/索引／原件依赖闭包、可迁移路径解析、流式归档及同引擎新根恢复尚未完成。Local05局部失败／PARTIAL与有效结果都保留，不能因打包标成PARSED。

本轮没有启停正式16／前端或受保护H2 owner，没有生产/H800部署。统一升级前仍须保全SQL16之后的消息35/36、DF01项目等新增写入及外部文件。下一步先完成本轮Git检查点，再继续VT02行为执行、最新统一运行与剩余质量范围；统一Goal不提前complete。
