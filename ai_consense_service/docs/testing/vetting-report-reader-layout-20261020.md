<!-- unified-progress23:start -->
**2026-10-20：30路径候选已精确采用主树；新统一后端127/127及同源JAR通过，前端210/210、类型检查和生产构建通过。Drafting正常三DOCX＋NTT预览／正文编辑通过，11页身份／页码可见结果逐页正确；Vetting reader04新Word40页／native42页全部亲看，来源标题及17主题分页缺陷已修。**

127是本次统一组合，不替代全量套件或真实provider／浏览器／业务验收；原42回归和归档1另有独立票。Drafting预览102.969秒、24856输入pin保持、1转换、自然Job0；preview/export同字节、编辑仅改document.xml，SCT/SCC／2b／长身份等尚未通过。Vetting保16原finding／69人工记录／47引文，最新页面中copies自动理由与Checklist要求的相关性仍需追实际packet；当前APP-I-002=Generally/1、APP-I-007=该第15页Generally/2，旧初步定位不能混用。

SL用户要求覆盖所有版本高质量解析成果及数据库，七已取得来源及22214技术页位置逐版登记，完整解析／DB闭包／比赛离线恢复尚未完成。Qwen本轮0新真实请求，上一27B业务76/100 FAIL保持；VT02首阶段仍候选、后半未接。原件／DB／密钥保持本地忽略目录，受保护H2及正式服务未启停。按用户授权先做本轮commit/push，统一Goal继续active。逐项证据和本轮前端失败修复见[统一检查点](three-module-checkpoint-20261020.md)；以下22及更早块保留历史时点。
<!-- unified-progress23:end -->

# Vetting 报告读者版式修正

2026-10-20，用户指出新测试报告原生 PDF 第 46 页外观像调试输出，尤其格式不适合作为正式报告。该页属于现有导出器追加的 Appendix II 逐发现明细。整体呈现验收判为 FAIL；此前字体、裁字、续表及内容保留的局部检查不能替代读者版式验收。历史产物、测试失败及局部检查结果保留，不追改为其他结果。

## 已确认原因

- `VettingReportWriter` 先生成正式业务表和 Checklist，再将旧 `outline` 整体追加为 Appendix II。Word、原生 PDF 共用这份字段列表，所以两种格式都有问题。
- `outline` 在每个 finding 前直接输出 packet ID 和 source snapshot SHA，并显示内部 `body/4/paragraph` 等路径、分类码及运输／调用／解析状态。这些字段对审计有用，但现有呈现让它们压过审查意见与来源原文。
- 问题标题、Comment、Fact basis、Reason and impact、Recommendation、Suggested action 等字段重复铺陈，人工未填字段逐行打印 Not recorded，缺少明确的信息优先级。
- 既有来源位置的人类显示没有覆盖全部 Appendix II 文本；页内标题、定位、业务内容、人工处置及证据引文没有形成清晰的阅读层次。
- 当前样本是 E2E 测试项目，不是正式项目输出。原始分析把未确认正式归属的两个段落称为 Clause 1／Clause 2 的业务缺陷继续保留，不能凭版式修正宣称法律内容已通过。

## 赛方结构依据

实际参考文件为 `Ref/2026-09-04_Documents to Shortlisted Candidates (Contract Advisory)/2d_Tender doc vetting comments/01_Vetting Comments_20250101.pdf`，共 29 页。其第一页为 Comments on Tender Documentation，展示项目身份、文件分组，以及序号、Page、Clause / Item、Comments、Remarks by Project Team 五栏。另一份 `02_App I - Checklist_20250101.pdf` 为 Checklist 参考。

这些文件用于核对报告结构与排版；其项目名称、合约编号、发现和法律建议不作为当前项目事实或模型答案。

## 修正边界

1. 正式业务报告继续对齐赛方文件的项目身份、文件分组、五栏意见表及 Checklist。每项优先显示位置、问题、理由／影响、建议和实际人工处置；有意义的内容不得因压缩排版而丢失。
2. 附录用于核对来源：清楚显示发现编号、文件名称、人类可读位置、完整必要引文、实际支持关系及未确认事项。正式条号未知时明确说明，不把段落顺序升为 Clause 编号，不猜物理页号。
3. packet ID、哈希、内部 XML 路径和调用运输明细继续保留在现有结构化审计记录、数据库及历史产物中。读者报告只显示确有核对用途的短标识和业务状态，不再逐项打印内部原始串。
4. 分类采用可理解名称；空的人工回复、实际处理及未决定状态合并呈现，或保留清晰的待填写栏，不能删除已经存在的人工决定，也不能把未填写变成已确认。
5. 复用现有报告映射、来源定位和 Word/PDF 写出链，不创建第二份审查真源。先制作独立候选，再同时检查正文表、附录、内容保留和逐页可读性。

## 验收与当前状态

必须同时证明：赛方栏目和阅读顺序对齐；业务意见及关键原文完整；原始审计和人工决定保留；预览／Word／PDF 的适用范围明确；逐页没有裁字、断序号、无意义留白或技术字段挤占正文。

旧回归对 Appendix II 的完整原样比较属于历史保真基线。新呈现修正应列明允许的显示变化，核对保留的来源、意见与人工字段，同时独立证明审计存储没有被删除；不能为了通过旧断言继续输出不合格的读者报告，也不能删掉断言后宣称所有内容已保留。

当前仅已确认缺陷和修正范围，候选尚未完成、未部署。Vetting 新报告的整体读者版式仍为 FAIL，三模块统一 Goal 保持 active。


2026-10-20 后续复核：Root已实际查看用户附件第46/60页并保持整体FAIL。两文件候选尚未冻结或执行；静态复审发现移除旧topic底稿会误删独立verifiedAssessment支持引文，已要求保留关键原文及实际未审范围。既有明确clauseId与每个来源位置须保留；历史模型的Clause1/2须逐项限定为正式条号未确认，不能只加通用脚注。当前运行版尚未替换。


2026-10-20 实际回归：RED01配置前置失败0JUnit保留；RED02单方法精确复现技术packet字段泄露；GREEN01实际32=28通过+4断言失败，0error/skip，仍未验收。目录前缀及窄列布局空白的显示断言正定向修正，原始字段和人工决定保真断言不放宽。归档47引文的新Word/PDF尚未执行；一个历史模型body/3位置没有对应证据，不猜映射，需在后续实际页面检查记录。


2026-10-20 progress17：32唯一方法组合通过（原28+补4，非单轮32绿），另归档生成1通过。16原发现/人工记录保持，47引文关系在Word逐字逐序、nativePDF可提取；只有3种引文文字，不合并法律问题。nativePDF46页，Word未渲染；整体仍FAIL。已实读12处Unknown status: out_of_scope、1处未绑定body/3及重复限定语，reader03先最小修显示映射和限定语层级，再执行逐页视觉；不凭减少页数、字段保全或0裁字宣称正式报告合格。


2026-10-20 progress18：reader03两份候选尚未运行；独立复审发现渲染后字符串去重会吞仅body位置不同的字段，及用展示序号覆盖具体未审主题。先在后继候选修正这两项内容保全Must；旧候选/历史47引文产物保持，整体读者版式FAIL不变。


2026-10-20 progress22：reader03单轮37全过、归档1全过及16原发现／69人工／47引文保全，不替代读者验收。新Word实际40页（34.016秒）、native实际41页（6.093秒），979运行pins保持、自然Job0、Job24GiB；两份独立票逐张打开全部81个实际PNG，Root另亲看10关键页。未见裁字／重叠／误空白／缺页码，但native34/35/36的Source quotations标题与首引文分到下页，native40–41的17主题仍挤在一个跨页长段落；reader04须无损修复两项P2。跨页引文／assessment所属记录续标、长row留白、CONF／APP-I／topic标签、未绑定定位句法及review.Rule缺空格继续登记。另Word15 Generally/2自动不满足的two/three-copy理由与该行条件指令检查相关性不足，作为独立P2业务复核，不从图片推定损失层、不改历史raw／人工状态。内容、渲染、视觉、法律／业务分别判定，整体仍FAIL；新候选由advisory代理独占Writer，VT02待reader04冻结后接同一Writer。票位于 `.local/e2e-20261020/advisory/reader03-native41-visual-review01/native41-page-review.json` 与 `.../vetting/duplicate-publication01/reader-report-presentation03/reader-artifacts03-highcap01/drafting-word40-visual-review01.json`。
