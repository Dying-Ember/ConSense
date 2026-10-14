# Drafting 三稿审阅修改：现场排查与热修验证

## 最新工作顺序与当前基线

### 当前已部署内容验收（capture04，覆盖下方历史cf87快照）

主8080 PID89996，Jar SHA `79cfc1c4dc78e8cdb10b26cfc158b06772cb7b7f7df51bb399623227949a6384`，规则`drafting-20261010.1-docx`。准确7文件候选已应用，13个冻结识别harness SHA不变；组合39通过／0failure/error/skip。三稿29.283725秒重生成，无新模型／PDF，确认值与trace字节不变。当前NTT/SCT/SCC revision为`eb6cfc4f-2732-4f91-9dc7-74815991a33a`／`6c28ee91-0855-452e-9037-4cc0cd8a283e`／`69b37f83-3c59-4e22-b42d-5eb82ceaad2e`。

24实际scope检查和独审2,672项通过；完整1,745 binding库存与1,555存活保存文字定位子集分别核验。仅SCT P849／P851改文及P854／P856所在子项行移除，NTT／SCC包字节完全不变。当前site=false使完整SCT6(1)(c)为`Not used.`，domestic=true／facade=true下SCT6(2)及SCT6(3)(c)原正文正确保留，三个父action状态不借指导清理计为business applied。

真实前端函数回放终态文件是`content-live-replay-04/frontend-replay.json`（没有同目录receipt.json），实际内置浏览器验证保存模式c为Not used、i/ii/iii不在保存正文、整2/a/b及3c保留无ins/del；review中删除／新增和指导清理可见。当前准确浏览器proof为`live-sct-scope-browser-proof-04b.json`、11断言全通过；3c现场JPG为`live-sct-domestic-facade-scope-review-04b.jpg`，位于`.local/state/drafting-review-fix-20261010/root-backend-combined-01/`。旧04补项目字段时页面已切到最早项目，保留mismatch收据，不用于当前身份验收；04b重新选择正确project／revision完整复核。右侧条号显示残差：site c的P849/851/853/854/856显示SCT5(3)(f)，domestic 2相关位置也有旧SCT5标签；实际binding定位和正文正确，正在owned诊断，不宣称展示完全通过。

当前111动作65applied／33retained／11pending／2unresolved，plan14待核；业务确认包`packet-final-01`由当前保存DOCX原样复制，用户自行发送。完整业务验收仍待，版式修复暂停。独审`.local/state/drafting-content-independent-20261010/capture04/incremental-semantic-native-review-01.json` SHA `e1efdf37e225853f0a0a0bd4cb4f389727f96028d641c0d44739d4bfe909efa6`。下方所有“未部署”“capture03当前”均为历史，不能覆盖本节。

导航显示随后已窄修热更新：准确2前端文件，27构建HTTP资产SHA一致，harness/catalog／saved revisions不变。直接Node typecheck／Vite build、14审阅断言、6布局断言、30真实callback核验通过。实际浏览器P849/851/854/856显示SCT6(1)(c)；P853没有business operation，显示两个注册关联SCT5(3)(f) / SCT6(1)(c)，不给唯一actionId。选中binding、段落、status及次序不变；不唯一关联保持待OER的精确执行scope，不能称全部来源语义自动解决。部署及浏览器收据为组合根`location-label-install-04/receipt.json`和`browser-proof.json`，截图`live-sct-site-labels-review-04.jpg`、`live-sct-domestic-facade-final-04.jpg`。新前端加载期间disabled导航等待结束后验收，不重新生成／调用模型／渲染。

### 最新作用范围核准：三处 SCT 指导说明属于内容核验

用户连续三张截图指出 SCT6(1)(c)、SCT6(2) 和 SCT6(3)(c) 的指导范围。独立对照2a原件、2b同条款及capture03，12项源／捕获检查通过；receipt `.local/state/drafting-content-independent-20261010/capture03/conditional-scope-review.json` SHA `39a004502144b6a90508c99e93f8f252265807e9b27c3e9f46c2a48847df64f2`。这12项是作用范围证据，不是补丁部署或最终业务验收。

- SCT6(1)(c)：P849 引导、P851／854／856 的 (i)／(ii)／(iii) 和 P853 注同属连续两行。2b同条款明确 `(c) Not used.`，原三个子项均无。当前 siteFormationWorks=false 已使 AND 条件 false；此前“负形式仍待业务决定”分类已被这一证据覆盖，是已知 false 漏执行完整 (c) 的内容缺陷。采用 Not used 形态，保全前 (b) 与后 (2)，不复制2b项目事实。
- SCT6(2)：P859 注管 P862／864／871 的完整 (2)(a)(b)，下一父级 P886 为 (3)。P898 的 (c) 属 (3)，受另一注 P895 支配，不能并入 (2)。当前 domesticBlocks=true，整组保留正确。
- SCT6(3)(c)：P895 管完整 P898；P899 只管 P898 内 `and the submission for special terms of payment`。domestic=false 整项 Not used；domestic=true、precastFacadePermission=false 仅删括号内付款短语；两个 true 保留正文。capture03 两值均 true，当前主文保留正确。残余编辑字符属暂停中的纯格式处理，不用截图推断正文应全部删除。

当前还核到三项父状态把指导清理计为业务 applied：SCT4 `sct-domestic-entire-clause`、SCT6(2) `sct-domestic-proposals`、SCT6(3)(c) `sct-domestic-submission-liability`。编辑注的清理与业务正文保留／改写必须分别归因；相关候选在owned目录红绿回归，未在主服务部署。父范围及付款子范围按条款树窄核，不能把未来插入锚或相邻项算作当前业务改写。

这些确认已持久化到 [OER 范围规则](../oer-scope-and-rules.md)；变量识别harness、用户确认值不变，纯版式仍暂停。

本轮最终owned内容候选为`drafting-20261010.1-docx`，5产品＋2测试文件，36方法通过、0failure/error/skip，manifest SHA `65f8c227d5c81448f353084febe212f3f4ef027174b1b92b90118d9c4f8e2def`。Root组合copied staging成功；首`content-combined-04`在19.67秒于testCompile停止，0测试执行，原因是copied中保留的暂停版式测试引用未采纳的格式API。该测试原样移到`paused-content-test-isolation-04`（SHA `cfda6756e1453f7b053612be15fcad5f36e4db4c77c6a378fff3245c403101e6`），不删除、不恢复版式实现、不修改main；`content-combined-04b`加入既有Bill身份回归继续。作者真源差分：NTT／SCC整包字节不变，SCT仅word/document.xml的P849／851／854／856及授权子项行变化，其余87 ZIP entries相同。主部署／新保存生成／前端验收尚未完成。

用户最新确定先完成NTT／SCT／SCC对应变量值的正文修改，整理供业务确认，同时全力推进OER；新的页眉页脚、空白压缩／分页、重编号、目录更新、重新排版及纯格式星号修复暂停，等OER来源结构及修改范围接缝确认后再接。变量识别harness继续冻结。现存候选／失败／未执行guards不删，已部署历史修复保持。

当前主8080为Jar SHA `cf87eca5efdec0a4f82efa9c5dbb8d533db6f4f7290fc11a9db304e745f327ab`，监听PID82812；四标题prefix已部署，实际三稿生成27.82736秒，65确认值及完整variables／trace字节保持。最新revision为NTT `4caf34a7-510e-45b6-b1af-4b1a5a6da059`、SCT `31ade0d3-346b-4641-9a43-323e1b46e781`、SCC `a1e6cefb-04e8-4408-b922-3eadb3134403`。精确证据在组合根的 `incremental-prefix-deployment-03`、`incremental-prefix-live-generate-03`、`incremental-prefix-after-capture-03`、`incremental-prefix-live-proof-03b`、`incremental-prefix-live-coverage-03`、`incremental-prefix-live-replay-03`。下方406ead及更早revisions为历史。

当前capture03内容独立第一层核验：65确认值逐项与plan输入／effective一致并有业务action覆盖；NTT178、SCT470、SCC1097条全部源／保存bindings（含清空、物理移除及父绑定插入）均核原文、文本hash和唯一CS标记，没有API文字与实际DOCX不一致、无operation改字。111actions为67applied／30retained／12pending／2unresolved。这是工程一致性，不等于业务语义全部通过；逐语义核验继续。证据 `.local/state/drafting-content-independent-20261010/capture03/receipt.json`。

第一层仅发现的 `applied_without_matching_binding_operation` 已归因至 `sct-domestic-entire-clause`：domesticBlocks=true时正文保留正确，P547指导被准确editorial catalog清理，父action状态却把其guidance-only投影计为applied。后续作用范围核验又识别 SCT6(2)／SCT6(3)(c) 的指导清理归因问题，不能用这个第一层计数宣称全部父状态已正确。SCT6(1)(c)的旧pending分类已由上方最新2b同条款证据纠正，内容候选尚未交付或部署。

P862/P898候选18方法通过但未应用主源码，已完整备份到 `paused-adopted-prefix-checkpoint-04/files`，Root copied产品恢复主基线、src/main差异0。NativeFooter自有渲染安全收尾、56.20秒exit0，尚未目视／portable guards／独立最终ZIP与前端验收；冻结在作者 `formatting-03/stopped-layout-checkpoint-01`，不部署。Root前端跨story候选只在忽略copied，无主前端改动。

## 第二轮历史实测：分页与计划状态已部署

当前主8080 Jar SHA `406ead07bc5e9cf8c4e2e2993b6f7431a9b1ed74116d7aa91abcc3112a2866a1`／PID114072。7个增量文件精确应用，236产品文件无allowlist外差异，13个冻结识别文件SHA保持；120方法通过、0失败／error、23显式skip，真实原件计划状态包含在本轮分母。组合根 `.local/state/drafting-review-fix-20261010/root-backend-combined-01/` 下 `incremental-source-apply-02/receipt.json`、`incremental-combined-02/receipt.json`、`incremental-package-02/receipt.json` 记录准确源码及Jar身份。前端5173、中继8092、隔离OER8880保全。

本轮用户三稿实际生成41.7469秒，无新M3；完整variables／trace字节前后相同，65确认值保持。NTT／SCT／SCC revision分别 `31835149-c108-47eb-9404-4de4f0749c33`／`d3076e25-270a-49e5-bf05-93a023da0c23`／`1f89be9d-5fc8-481e-80e5-ad89a6ecdf0f`。plan `applicationStateSource.kind=saved_generation` 与三份未人工编辑保存artifact精确绑定，SCC状态33 applied／19 retained／3 pending。三稿全部w:t、field instructions和非document.xml ZIP parts与上一轮一致，只有source-bound布局变化。

1557保存binding的路径／文字／hash全部匹配；changedWithoutOperation、scopeGaps、confirmedWithoutBusinessAction均0。前端真实函数回放NTT12／SCT31／SCC52关联输入均至少一处实际修改标记，原件／保存模式无ins/del。SCT有1条历史共同动作 `applied_without_matching_binding_operation`，它保留独立残差，不与其他零值合并解释。

最新实际SCC PDF独立解析、63页缩略图全部目视，SCC5.308物理17页和SCC7.308物理31页高清目视通过：标题与首正文同页，无只有页眉页脚的空页。PDF SHA `8606058640784318d1e93da0c7b4b1aa8945a178c08f661aaf0e19db68df2221`；证据在 `incremental-live-pdf-proof-02b/`。部分非采用条款留白、目录页码缓存、星号与手写页脚仍未通过最终格式验收，不称完整PDF已修完。下方69页／plan未修按历史读取。

用户新增格式排查已对照2b三原件：NTT／SCT四处纯编辑prefix缺准确registry，窄候选真实compile4与classification5通过，两稿真实PDF中相应四星号消失，Root验证中，尚未上线。NTT／SCT正文手写页码随删除上浮，拟用source-bound家具锚迁移native动态footer并保留布局／审阅归因；SCT源页码起始17须单独规范。SCCnative页脚坐标稳定，三处Bill／Schedule空值与语义星号须独立处理。2b仅提供格式模式，不复制其业务取值；变量识别harness仍冻结。

SCC三处语义残余已完成独立只读核验：源P1428／P1548／P1777在当前保存稿及bindings中原文精确保持，三个 `safety-pricing` action均pending／null、BusinessValue unresolved、operationIds为空。当前13个Bill行仅有id／number／description／type，无purpose；缺的是专业工种安全费用的scope／purpose映射，并非已知Drainage值漏执行或placement门控。现有compiler可按明确BQ／SOR映射填写，当前不能推断Bill12或SOR9／10用途，保留pending及对应marker，不改harness或compiler。证据 `.local/state/drafting-scc-residual-independent-20261010/receipt-01.json` SHA `8851dd4eb87e8842d7d2b060279cf7b8fc11476f3ed66ca651d829462350ba76`，绑定本轮SCC revision。

SCT剩余星号的“原生Word编号”假设经最终package raw XML核验已排除：numbering.xml、w:sym／field／delText均无对应星号；四heading清理后document.xml的w:t仍有5个星号。源P849／P862／P898分别为 `*(c)`／`*(2)`／`*(c)`，P436为两处 `*SCC20.2`／`*SCC20.3` 业务选择引用。ErraticPricing低清缩略中的开引号未证实为star。此前census误混SCC统计，保留纠正；后续以逐offset原XML和最终PDF核验，不改numbering.xml。

## 最新实测：组合后端已部署，用户三稿已重生成

2026-10-10 后续实测覆盖下面较早的 staged 状态；历史失败、原件基线和未完成门保留。主后端 8080 已安装 SHA `a8c8f591610f51de7ffd6ec7f1af7bfd8358b2aa0f6a5f96d6a0494ea9f17d16`，本轮 source apply 仅允许25个准确文件，13个受保护识别文件SHA不变。组合目录 `.local/state/drafting-review-fix-20261010/root-backend-combined-01/` 的 `root-final-source-apply-01/application-receipt.json`、`final-package-01/receipt.json` 保存应用与构建证据；中继、前端、旧隔离OER服务保全，Bonsai继续抑制。

真实 `live-generate-01/receipt.json` 记录22.768秒生成三稿，无新M3调用。完整 variables API 与 trace API 字节前后不变，65确认值及run `8f01b7df-6234-4ffb-b204-2b009f5cadba` 保持。新revision：NTT `2e1b5da5-f110-40b3-b76f-4fda8af02851`、SCT `c11edf2f-20f4-4b93-bca4-f2a1cf6968db`、SCC `d127f1b5-c5bb-4751-af1c-326e6868196d`。`after-deployment-capture-01/receipt.json` 已冻结实际三DOCX、PDF、bindings和API。

`live-coverage-01/full-current-action-coverage.json`：1557条保存段落路径／文字／textHash全部精确一致，文字变化无operation的数量为0，实际操作越出登记范围的数量为0。变化binding分别NTT33、SCT192、SCC404；这是工程覆盖，不是变量或业务正确率。真实前端函数回放 `live-replay-01/frontend-replay.json` 中，12／31／52个关联输入分别均至少有一处实际修改标记；原件和保存模式无ins/del。浏览器实见SCT Bill7–Drainage已写入，P163已删除指引灰色删除线可见，原“有文字差异缺少已应用修改记录”提示消失。截图 `live-sct-review-01.png` 保留。

最终focused测试95通过／0失败或error／23显式skip；实际三原件综合生成及PDF方法1通过／0skip。收据分别 `final-focused-01/receipt.json` 与 `final-three-render-01/receipt.json`，不可相加为模型成绩。跨段字段保护及准确TOC标题模式已入本轮Jar；普通REF缓存、未知或不完整字段仍拒绝。六处安全BQ3–8身份已写，五项无安全落位及purpose／placement分发继续局部pending。

实际渲染NTT11页、SCT22页、SCC69页。SCC物理33／62／69仅页眉页码、无正文，属于真实空页；物理17／30存在SCC5.308／SCC7.308孤标题。已定位到业务清空正文保留分页符和段落高度，正在source-bound窄修，不能称0空页或完整PDF验收通过。NTT／SCT全页目视及SCT P730长段／Envelope行通过本轮版式核验。

另有实际状态接缝：`GET /plan` 从平面模板重算，不读取生成时真实源码编译状态；因此保存稿有完整operation而部分SCC又显示unresolved。当前API账本13applied／11retained／28unresolved／3pending与生成snapshot预期不一致，正在核对及修复。状态复用必须绑定当前输入、来源、规则和未人工编辑的保存artifact；不能把旧成功状态套到新输入或新原件。该项和SCC分页仍未部署，不宣称整体完成。

## 当前实施状态（2026-10-10 后续增量）

用户授权直接热修生成、正文应用、审阅与版式链路，并明确要求变量识别 harness 保持现状。以下原排查是冻结基线；本节记录后续实施，不能把基线缺口与已安装前端混为同一状态。

- **主前端已热更新。** 审阅展示完整保存稿的实际变化，输入选择只负责定位；共同动作展示整体差异并列出关联输入，编辑指引清理由后端明确的 `editorialOperationIds` 单列。混合业务／清理段落保留共同归因，未知记录仍显示待核；原件与保存模式继续各展示自身正文。自动编号提示增加原始模板／保存稿来源标签，没有冒称已验证原生续号。候选在 `.local/state/drafting-review-fix-20261010/root-candidate-01/`；最新安装收据 `mixed-provenance-install-02/receipt.json` 核验 27 个构建文件 HTTP SHA，13 个受保护识别文件 SHA 不变。审阅 14 个场景、layout 6 个场景、直接 Node 调用 vue-tsc 与 Vite build 已通过。Windows CMD 包装的旧 `npm run typecheck` 失败记录保留，不能称该原命令通过。
- **主后端仍为原验收 Jar。** SHA `6b23d15f1506bbc87955622b6e09998c71b3a97241174e8f279be36ff9e1d74e`。SOURCE_TEXT_DIVERGENCE 兼容、明确清理分类、真实插入关联及 P596 影响范围已在组合 copied checkout 中；Bill 身份填写、SCC 非采用输出与 PDF 分页候选继续核验。它们尚未安装 8080，不能宣称用户保存稿已修好。组合入口为 `.local/state/drafting-review-fix-20261010/root-backend-combined-01/`。
- **Bill 身份与分发分开验证。** 候选已在原件安全位置填写六条 BQ3–8 编号／描述；原 P182 Bill9 与采用的 SOR9 不匹配，继续保留。BQ12/13、SOR9–11 没有已核源码位置，五项记录为局部待处理；purpose／placement 及分发仍按证据门控，未把 SOR 塞进 BQ。候选与通过收据在 `.local/state/drafting-pdf-pagination-independent-20261010/backend-candidate-01/segment-bill-identities-01/`，尚未主部署。
- **PDF 是真实 LibreOffice 回放。** 首两轮 33 页／6 空白页及 25 页失败候选保留；第三轮为 23 页／0 空白页，原页码跨页、续页标题、SCT14/15 孤标题与 Envelope1c 小行断页已修。P730 长段尾行仍在窄修，最终候选尚未安装。原件手写页码仍是正文，不宣称已改成原生页脚。
- **生成性能与 SCC 负向输出尚未通过。** 23 个当前已确认非采用父动作的真实源码回放已建立，但前几轮为 Maven 启动失败或 CPU 长等待后中止，不能当作 23 个断言失败／通过。正在分别检验源码匹配与 XML feature 扫描开销，所有 debug 与修复只在 owned copied 目录；未知输出形式继续局部 pending，不全局放宽 `not_adopted`。

尚未用组合后端重新生成用户三稿；65 个确认值、识别 run 和原保存 revision 保留。最终验收仍须核实际三稿 DOCX、后端实际 bindings、审阅／保存视图及真实渲染 PDF，不能用前端离线回放代替。

## 冻结只读排查基线

2026-10-10，Asia/Shanghai。对象为用户在主前端 `5173/#/drafting` 验收的项目 `drafting-exam-20261008-175206-5f89290f`。本次核对当前 API、原始及保存 DOCX、完整 bindings、当前 plan，并用当前前端 `convertDocumentReading`／`prepareDocumentReview` 离线回放。没有重新调用 M3、修改采用值、重新生成、修改产品源码或重启服务。此记录不替代此前的模型识别成绩，也不宣称最终三稿业务正确。

## 证据入口与版本

- 忽略目录：`.local/state/drafting-reading-audit-20261010/capture-175514-837030/`，完整 HTTP 字节／六份 DOCX／SHA 在 `receipt.json`。
- 复现命令：`node .local/state/drafting-reading-audit-20261010/replay.cjs .local/state/drafting-reading-audit-20261010/capture-175514-837030`。读取当前 catalog 并与冻结 catalog 精确比较，实际运行产品前端转换及审阅函数；不连接数据库、不生成文稿。输出 `frontend-replay.json` SHA `130d09c674e0b69930d754781c4e34c94ee01ff1ea2685040f7b845211f0d89e`；逐文件入口为 `frontend-replay-handles.json`。
- NTT revision `1e5d983a-7a02-4dc8-a6c2-35325e7b0978`；SCT `5776fcc2-e5c0-4fa7-bf8f-b6c7e22c740c`；SCC `5eb38a62-b58b-409f-9f54-29f44bb6b44a`。三稿均 `generated=true/stale=false/contentEdited=false`，plan／文稿均 `drafting-20261006.8-docx`。检查末尾再 GET documents 时三 revision 未变。
- 当前主 Jar SHA `6b23d15f1506bbc87955622b6e09998c71b3a97241174e8f279be36ff9e1d74e` 与安装收据相同；本机 renderer 配置为 `libreoffice`。`DraftNativeLayout.savedInfo` 对非 `desktop-x2t` 返回 null，因此本次 `nativeLayout` 不返回对象符合设备配方，不能据缺字段判定 Jar 漂移。NTT／SCT 的 desktop-x2t 专属续页处理未由这一配置启用。

## 全三稿回放结果

下表“变化行”按保存 binding 的 sourceText／text 差异或 removed 状态统计，包括编辑指引清理，不是变量数、独立业务动作数或模型准确率。输入回放只统计本文件有登记关联的 key，包含内部隐藏字段；跨文件的同一 key 分别核对，分母不累计为独立变量总数。“有新增删除标记”统计逐字 ins/del、整段 removed/guidance 或 added，关联绿线和 retained 边线不算修改标记。

| 文件 | 保存 binding 变化行 | 变化行无 operationIds | 关联输入回放数 | 至少一处新增／删除标记的输入数 | 有 diffUnverified 的输入数 | 有 jointRangeAttribution 的输入数 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| NTT | 33 | 0 | 12 | 5 | 4 | 8 |
| SCT | 186 | 0 | 31 | 4 | 13 | 22 |
| SCC | 136 | 0 | 52 | 0 | 42 | 19 |

两个 notice 分母可能重叠。三文件均没有 mappingUnavailable／selectedMappingUnavailable。六份 DOCX 的正文覆盖检查均 complete=true、missingParagraphs=[]、textMismatches=[]、正文顺序通过；这只覆盖转换器所声明的段落、规范化文字、公式及图片范围，不包括 Word 自动编号标号保真或完整合约业务验收。

## 已确认问题及原因

### 1. 同一文稿不是分批展示

`DraftingDocumentWorkspace.vue:34–51` 等待原件与保存稿的完整 DOCX／bindings 两路加载、转换及 SHA／revision 核验，再统一赋值。`DraftingDocumentReview.vue:25–26,163` 同步准备完整 HTML 并一次呈现。重新识别只刷新候选和变量；`DraftingView.vue:287–290` 的生成才产生新文稿。当前 revision 与 plan 均非 stale，不能将本次缺口归为等待下一批或自动重新生成未结束。

### 2. 自动编号提示缺来源标签，阅读层未核验原生编号

`document-reading.ts:172–179,268,367` 统计直接或样式继承的 Word numPr，只要存在就告知编号／续号未验证。review 在 `DraftingDocumentReview.vue:63–66` 合并两路 warning，数字不同则保留两句，但没有“原始模板／保存稿”标签。

| 文件 | 原始模板带自动编号段落 | 保存稿带自动编号段落 |
| --- | ---: | ---: |
| NTT | 8 | 8 |
| SCT | 21 | 16 |
| SCC | 22 | 15 |

这些不是遗漏变量数或确认错误数；不同数量也不能单独说明删除正确。当前 HTML 转换没有展开并验证 numbering.xml 的真实标号、重启与续号，不能直接删提示后宣称核验完成。后台 nativeLayout 也不会自动消除该阅读层提示。

### 3. 右侧确知删除，正文却因业务归因失败保留原文

右侧按钮及状态仅使用 `locationStatus=removed`；正文在 `document-review.ts:38–47,207–209` 还要求 operation 匹配业务 action，且 appliedActionIds 中有该 action。编辑指引／星号清理有独立 operation 命名域，与业务 action 不匹配。`DraftDocumentBindings.java:67–68` 仅按 operation ID 前缀计算 appliedActionIds，没有表达独立清理的修改类别和归因。因此“缺少已应用修改记录”不准确：主要是记录存在，业务归因无法建立。

用户 SCT 截图的 P163 为 `adopting electronic dissemination of`；保存稿 text 为空，operation=`SCT-editorial-note-12-P163`，locationStatus=removed、documentEditStatus=applied、appliedActionIds=[]。侧栏显示删除，正文返回 diffUnverified 后 continue，未加删除线；真实删除没有失败。绿色竖线／定位边框为 review-associated／review-focused，不表示已修改。

独立复算未匹配业务 applied action 的变化行：NTT4（星号清理）；SCT43（38指引清理、4星号清理、1真实新增关联缺口）；SCC89（88指引清理、1质保处理范围缺口）。没有 field 关联的行不会触发选中输入的 notice，不能将这些行数作为 UI 警告条数。

### 4. 共用动作全部按逐输入文字隔离，SCC 因而没有新增／删除标记

`changeAttribution` 将动作涉及多个 inputKeys 的段落标为 jointRangeAttribution，保留原文，不显示共同动作的整体红线。这个保守规则避免将共同修改伪称某一输入独立修改，但缺少“共同修改”的可审阅呈现。SCC 当前52个关联输入逐一回放，均无新增／删除标记，虽保存稿存在136处变化。不能将这一事实解释为选错某一个输入或 SCC 变量完全没识别到。

修复应展示完整已保存共同动作并列出关联输入，仍不伪造逐输入字符范围；同时为有实际来源核验记录的编辑指引清理单列类别。不能仅凭字符串前缀放宽所有未知修改的门控。

### 5. 用户 SCT Bill 清单截图存在实际生成漏填

采用值 billNos 已 confirmed、无 validationIssue，包含13项；Bill7=Drainage、Bill3=Substructure、Bill8=External Works。保存 DOCX P170–182（原件段落号）的 Bill3–9 描述仍为空，operationIds=[]；不只是审阅颜色遗漏。当前 `sct-shared-bill-list` unresolved，`sct-bill-body-summary` 因 parent unresolved 被阻断。

准确原因：`DraftClauseRules.billDistribution` 在填写任何清单之前，要求每一行都有 purpose 和 placement。当前13行均有 type，但 purpose／placement 都省略。input-catalog、extraction harness 则把两项定义为有明确来源才提供的 optional 属性，基础验证只要求编号及描述。前端 bill-distribution 投影能显示 L10Pro 下的标准 BQ Disc A／B 安排及单独待核 SOR，保存编译器却整体停止。这是输入／投影／编译职责不一致，当前尚未到 mixed-BQ/SOR 的后续门控，不能归因于禁止混合类型或 M3 没识别出 Drainage。

要求：将可确认的编号／准确描述填写与跨 Disc、纸本、SOR 用途分发拆开；逐个已定位且确定类型的条目不应因其他行未知 placement 而全部不写。未知落位和用途局部保留 pending，绝不把9–11三项 SOR 硬塞到 BQ 或假定全部纸本；中文须报告缺哪行、哪个属性、阻拦哪类动作。此处正文需要的 Bill 引用保持 Drafting 范围内，不扩大为 BQ／SOR 文件审查或计价核算。

### 6. 两处实际修改未登记到完整影响范围

- SCT 新增 `Submit a PSE-endorsed design statement for the proposed CON8 volumetric precast concrete components together with the Tender.` 有 operation=`sct-additional-submissions-insert-P862`，却只继承相邻父段落的 twoEnvelopeTendering/domesticBlocks/precastFacadePermission 与 action。它没有真正的 sct-additional-submissions／pseSubmissions 等关联，`relateInsertions` 只继承 parent，真实输入的审阅无法呈现该新增。应绑定实际插入 action 和其 source-bound 影响信息，不按邻段猜归因。
- SCC 防水质保指引开头 P596 的 `[Guidance Notes:` 已被 `scc-waterproof-warranty-P596` 删除，但 action 登记从 P598 开始，而编译删除 P596–604；这一行没有 fieldKeys/actionIds。应使实际编辑完整范围与登记范围一致。

## 验收状态

本次只读排查及产品前端回放完成。已保存原件／文稿身份与正文转换覆盖通过；删除线、共同修改、影响范围和 Bill 编译存在明确缺口，不能将“审阅修改”当作已经完整可靠的三稿修改验收视图。相关修复尚未实施或部署，未修改用户现有输入／文稿，未重新发起模型识别。后续验证须分别覆盖原件实际改写、sidecar operation／action／field 归属、原始／审阅／保存三视图及真实浏览器呈现。
