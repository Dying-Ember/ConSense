> Historical snapshot retained during the 2026-10-09 private-repository sync. This file records the earlier task state; current OCR/embedding/rerank decisions are in `HANDOFF-ocr-embedding-rerank-20261008.md` (under `docs/`), and the current integration record is `SOURCE-INTEGRATION.json` at repository root.

# ConSense vetting 当前目标

## 当前结论：基础链路五项完成条件已通过；最终审查质量仍待验收（2026-10-05）

当前基础Goal按[原完成条件](vetting-foundation-findings-20261003.md)完成本机冻结范围验收，未新增reviewer调用或本地reviewer部署。这里的“完成”指数据/材料运输、非静默截断、索引身份、必要范围恢复及保存导出状态；不代表44份合同总体正确率、全量OCR精度或H800性能已经通过。[五项逐项依据](../../tmp/vetting_foundation_hardening/foundation-final-checkpoint-20261005/criteria_acceptance.json)和[本轮日志/文档交付回执](../../tmp/vetting_foundation_hardening/foundation-final-checkpoint-20261005/delivery_receipt.json)保留。

当前44份材料已真正运行一次Service/H2/MVC软件闭环：使用当前872构建、Source44及本轮16条真实检索，提交44来源/13,464完整canonical parents，实际保存后执行Open→Handled及备注/行动更新，再导出两组JSON、Word和PDF。[独评说明](../../tmp/vetting_foundation_hardening/source44-service-save-export-20261005-v1/independent/README.md)核对4个实际受控generation Input、357唯一父块、808次native row/10次slice/5次重建行；主题2 global同包完整保留AQCC＋Note1，主题6 global同包完整保留SCC(6)九段＋SCC(8)。人工字段、引用、packet/sourceSnapshot在保存和导出中一致。62 counter和4 generation为受控软件fixture，0真实provider/审查模型调用，生成候选不当作真实合同缺陷，verified仅为“证据已定位”。

原整包进程业务断言完成后因临时driver先关闭Context而exit1，该失败与所有原输出保留，不能写成整包进程exit0。随后最小实际生命周期回归复现旧错误，并验证afterTestClass→close的正确顺序，两个新H2 Context正常关闭、exit0，0 Service重跑。[清理修正证明](../../tmp/vetting_foundation_hardening/source44-service-save-export-20261005-v1/cleanup_lifecycle_final_receipt.json)。[可复现入口](../../tmp/vetting_foundation_hardening/source44-service-save-export-20261005-v1/REPLAY.md)已实际ValidateOnly exit0；完整重放命令已提供而未重复执行，旧629工件不覆盖。

两组原生PDF各12页已渲染并目视核对24页，没有观察到裁切或重叠。Word内容/引用/人工字段身份核对通过，视觉分页仍unknown：内置renderer缺LibreOffice，独立只读Word转换未产出，失败与超时保留，仅停止本任务新建实例，原用户Word进程保留。[视觉观察和Word失败](../../tmp/vetting_foundation_hardening/foundation-final-checkpoint-20261005/visual_readback_post_observation.json)。16个pending中主题2的8个为条款标题不唯一，主题6的8个为起始来源关系未能确定；主题1/3/4/5缺本轮查询分别保留not_submitted。它们均未被旧20,000字符门控阻挡，不把unknown当一致或原件没有。

切块/嵌入预算已使用真实tokenizer，512-token窗口及64 overlap保留来源映射，推理guard明确关闭静默truncation，超预算明确失败。当前Source44 13,464父片段/13,602向量点完成fresh-client全点读回，payload/Part/cells/范围一致，float32逐元素0差异。新16查询的四必要范围×两配方：dense3/8、BM25/RRF8/8、实际rerank全候选7/8；同分数旧top-K0/8，完整结构附件8/8。SCC为tender document80，不能误称固定standard document64损坏。283完整Input独评也核对两个预算分支的global范围各8/8，0真实送审。该局部改善不证明全库语义检索或真实provider容量。

OCR六份仍有明确局部问题：SL P1006、AA P4、APA P7、APB P47、APC P19、APU P2；FT P2/APL P3仅已确认局部修订，PRE另有Word编号/字体/结构告警。[文件级质量报告](vetting-ocr-embedding-quality-20261005.md)保留具体错误。SL最新单变量仅关闭方向分类：51框和裁切像素不变，未旋转47行不变，目标正文0.49389→0.97343，在相同0.5门槛恢复，最终49→50行；错误旋转导致该处漏行已证实，编号/星号仍错，实验未应用。APC尺寸重量配对0/10→10/10，三处600从0/3→2/3，仍有60.0及阅读顺序问题。21风险页不是147声明OCR页或44整份精度验收；质量继续needs_review/unverified/PARTIAL，不全局关闭分类。最新SL反事实仍未注册到检查UI。

[当前检查页](http://127.0.0.1:5177/#/vetting-inspection?dataset=corrected-source44-native-v3-full44&run=corrected-source44-current16)展示实际Source44向量窗口/来源、16新查询与rerank、7 OCR页/447保存块、4既有未应用实验及SL过滤阶段/APC候选表格。v4 8089使用隔离H2、模型关闭，实际浏览器和API核对已完成；此页未借旧42分数，也没有把受控保存导出登记为真实MiniMax审查运行。固定六来源[portable v7](../../tmp/vetting_foundation_hardening/fixed-portable-source44-v7-20261005/DELIVERY_README_zh.md)两随机目录各10,724严格命中/0miss及float32字节一致，仅证明本机迁移，Linux/H800及portable实际Java调用仍unknown。

当前基础完成后，下一阶段应使用已授权MiniMax API检验固定材料的真实语义审查，先明确完整请求预算，再分别评价误报、漏报、引用与缺失前提；忽略本地reviewer部署。全量OCR、扫描图表、未标注检索范围、真实provider容量、最终审查准确率、H800和Word视觉仍未知。既有源修订、Drafting、原数据库和用户进程保持；下文旧检查点按当时状态完整保留。

---


## 当前材料运输验证：corrected42/current16的4个受控生成Input必要范围同包（2026-10-04）

冻结Root774软件的新三场景Service/H2/MVC保存导出已终态。外部聚焦独评核对 **229次受控counter＋4次受控generation** 的233个完整Input：四个实际generation Input分别完整同包Q2的AQCC＋Note1最小要求范围、Q6的SCC6九段＋SCC8，包含当前正文/Part/native cells及slices，generation sourceSnapshot从同包生产Selection原始JSON重算。[可读独评说明](../../tmp/vetting_foundation_hardening/corrected-query16-controlled-input-independent-review-20261004/README.md)与[实际Input范围收据](../../tmp/vetting_foundation_hardening/corrected-query16-controlled-input-independent-review-20261004/actual_final_v3_v2/audit.json)已冻结。这证明本次来源材料能到达完整受控Input，不是模型质量结论。

coverage场景14条counter捕获包含完整必要对，但仍为受控over/unknown，**0generation、347pending**；14是捕获数，不是成功送审包。没有保存查询的主题1/3/4/5保持0requests、notSubmitted及0gateway，不能当真实空检索或consistent。原始16查询returned仍是聚焦0/8完整；真实provider预算和合同语义未验收，不能用受控fixture数冒充真实MiniMax计数。非global候选完整sourceSnapshot独立重算仍pending，本focused独评没有重新全量核验1002工件/283实际类。

16查询尚未接入前端run；另发现“已记录但为空的BM25阶段被展示为unavailable”的检查接口缺口，最小修订尚未在本checkpoint部署。旧数据/旧失败/历史证明保持。Goal仍started/active，原完整目标与参数指纹保留，foundationAccepted与retrievalQualityAccepted均null；本次只追加日志/文档，0新模型、API、检索、索引、OCR或AppDB调用。

---


## 16条实际查询必要范围独评终态：最终返回0/8完整（2026-10-04）

本段更新下方历史检查点中“必要范围比较正在进行”的状态。已冻结的16条查询（主题2/6、四角色、primary50/10与coverage100/20）独评完成：AQCC、Note1的原文最小要求范围、SCC6九段与SCC8在当前语料及全部索引窗口中均完整，但两种配方的8项聚焦最终返回范围完整数为 **0/8**，与旧native-v3实际128查询对应范围相同，不能据OCR文本修订声称检索质量提高。primary SCC6在RRF完整后被candidate50漏掉一个父块；其余完整候选主要在最终top10/top20被排除。dense与BM25是并行分支，dense缺失本身不等于后续不可恢复的损失。

[独评中文说明](../../tmp/vetting_foundation_hardening/corrected-query16-necessary-range-review-20261004/README.md)、[终态收据](../../tmp/vetting_foundation_hardening/corrected-query16-necessary-range-review-20261004/actual_evaluation_v1/receipt.json)、[逐阶段对比](../../tmp/vetting_foundation_hardening/corrected-query16-necessary-range-review-20261004/actual_evaluation_v1/comparison.json)分别绑定实际SHA。新corrected42/query16生产上下文与完整候选Input尚未终态，**currentContext为unknown**；不得把旧764受控保存导出、新774只读UI、v6可移植缓存或测试通过当作本轮材料已完整送审或语义质量验收。统一registry只追加本次observation，necessaryRangeComparisonPending=false、focused8ReturnedComplete=0、retrievalQualityAccepted=null；Goal原完整要求与started状态保持，foundationAccepted=null。本次追加只操作日志及文档，0新模型、查询、索引、OCR、HTTP或AppDB调用。

---


## 当前基础链路检查点：Root774检查运行、真实16查询与隔离保存导出（2026-10-04）

Root统一构建已实际完成774 tests／72 suites全0，283生产classes与冻结JAR逐字一致，独立审计通过。新检查后端8089／前端5177使用独立H2内存、审查模型关闭；18次真实只读GET核验全部42修订及其来源身份。实际浏览器已核对APL第3物理页3处原OCR/修订文字、真实1024维Point与窗口、dataset/run切换：旧128运行自动切回旧native-v3快照，修订快照不会借用旧排序。[构建独审](../../tmp/vetting_foundation_hardening/independent-root-build774-review-20261004/receipt.json)、[API核验](../../tmp/vetting_inspection_runtime_corrected42_20261004/actual_api_verification_v1/receipt.json)和[浏览器证据](../../tmp/vetting_foundation_hardening/corrected-inspection-ui-verification-20261004/browser_verification.json)分开保存。

修订索引的新query-only回归已实际完成16请求：主题2/6×四角色×primary50/10及coverage100/20，保留旧query逐字内容，0index／0OCR／0审查生成；embedding16／reranker175 forward全部完成，Qdrant关闭及原producer状态字节保持。仅该16请求范围，不能称作重跑全部128。[实际终态](../../tmp/vetting_foundation_hardening/corrected-source42-query-regression-20261004/actual_v1/result.json)已冻结；必要条件逐层独立评价正在进行。这16结果尚未注册到前端，当前检查页仍将其显示为无已接入的检索运行；后续需接入同身份实际新排名。资源样本有长时间观察间隔，wall不作吞吐或H800性能结论。

同一planner764软件的真实Service/H2/MVC/人工复核/JSON Word PDF五场景连续集成终态通过，10targeted tests全0，283class与764冻结JAR一致。混合预算证明完整A+共享condition成功同包，Bunknown保留pending且不抹除成功membership；同generated revision保留人工状态，改变revision后不继承。counter/generation为明确in-process WireHttp夹具、保存排名属于旧native-v3；这份集成不冒充本次corrected42新端到端。新source/corpus/query16到Root774 Service的最小隔离集成正在准备。[764交付](../../tmp/vetting_foundation_hardening/service-native-input-integration764-20261004T1024Z/delivery_receipt.json)保留实际Word/PDF导出文本身份，未宣称视觉排版验收。

固定六来源v6离线包已实际生成、归档、随机短目录解包并重绑新project/docIds，10,724个严格缓存lookup命中、0miss、0新神经调用，SL新修订身份与其他五来源旧严格缓存各自绑定；原生311行/cells保留，旧v5不变。[v6交付](../../tmp/vetting_foundation_hardening/fixed-portable-package-v6-20261004/delivery_receipt.json)。OCR质量/人工确认未提升，未标注及legacy页范围仍unknown，Linux/H800和合同语义质量未验收。App Goal保持active，原完整完成条件保留，foundationAccepted仍null；下文旧检查点按当时状态保留。

---

## 当前基础链路检查点：修订索引终态与通用条件小组（2026-10-04）

42个整块OCR修订的新44来源快照已经实际完成新语料和索引：13,464 parents／13,602 windows，675个五固定来源严格旧缓存命中，12,927个窗口本次真实编码，3,271次embedding forward全部完成；0新查询／0审查生成。独立fresh client共54次scroll核对全部points，完整payload、来源范围及实际upsert float32逐元素0不一致，所有producer状态及旧索引/缓存字节保持。[索引终态](../../tmp/vetting_foundation_hardening/corrected-source-native-v3-index-20261004/dispatch_receipt.json)和[独立读回](../../tmp/vetting_foundation_hardening/corrected-source-index-readback-20261004/final_receipt.json)已冻结。修订不提升OCR整体精度或人工确认状态；未确认候选不应用。

通用一跳条件小组修复在隔离764 tests／71 suites全0后已仅接回planner与新增测试两文件。保存的真实Q2排名、原116,644 token over Observation逐字回放，59个完整候选中的新小组保全AQCC+Note1：16,200字符／13完整chunks／7完整原请求；新小组budget_unknown／0extra／未送审，不使用旧大组计数冒充。独立59候选及965 chunk访问、lossless source/wire与whole requests完整性均通过。[接回计划与隔离证据](../../tmp/vetting_foundation_hardening/service-direct-condition-bundles-20261004T014327571205Z/delivery_receipt.json)和[独立审计](../../tmp/vetting_foundation_hardening/direct-condition-independent-review-20261004/candidate_audit.json)可复核。

修订索引已有独立检查binding和真实全点快照，前端修改前/后OCR展示及dataset/run身份切换已实际typecheck、Vite构建通过；**新检查后端尚未完成Root构建或部署**。旧128查询仍只属于旧native-v3索引，新修订索引没有检索运行。[新检查binding](../../tmp/vetting_foundation_hardening/inspection-corrected-source42-20261004/publication_receipt.json)不能当作新UI运行验收。下一步为新软件完整构建、实际只读检查及同版本Service/H2保存/人工复核/JSON/Word/PDF连续集成。App Goal保持active；foundationAccepted、语义质量及H800验收仍未知。下文所有旧检查点按当时状态保留。

---

## 当前实际检查点：新128检索、完整材料回放与OCR修订（2026-10-04）

新 native-v3 真实查询已完成：**128 query／0 index／0审查生成**，13,464 parents／13,602 windows，embedding及rerank forward全部完成，原索引状态字节保持。独立四必要范围评价确认：corpus及indexed窗口完整，但相关Q2/Q6的primary、coverage检索最终返回仍均未覆盖AQCC、Note1、SCC(6)/(8)。这是排序及返回取舍瓶颈，不宣称检索精度提升。[真实128结果](../../tmp/vetting_foundation_hardening/native-v3-query-dispatch-20261004/actual/result.json)和[独立前后比较](../../tmp/vetting_foundation_hardening/native-v3-necessary-range-evaluation-20261004/README.md)分别留存；共享GPU及暂停使wall不可作为吞吐基准。

当前755生产配置已对上述新排名完成**32组／410完整Input候选／2597来源请求**的0网络回放。通用补全使Q2的AQCC+Note1、Q6的SCC(6)+SCC(8)在相应完整候选中同包；未知计数保留budget_unknown，不发布extra、不冒称sent。Q6全局和关联组实际保存计数分别108,855与13,728；新输入与已计数输入逐字一致，独立终态范围审计继续。Q2关联组已有唯一真实MiniMax计数116,644（HTTP200／0生成／0重试），按当前政策为over_budget。只读保存Observation回放证明：61个完整请求才在真实over后拆分，65个候选／73个unknown待处理／0extra；但没有更小的AQCC+Note1完整共同包，一跳直接相关条件分组正在通用修复。[Q2真实计数](../../tmp/vetting_foundation_hardening/native-v3-source-request-755-replay-20261004T010408Z/actual-primary2-related-counter-45s/receipt.json)与[拆包独立评价](../../tmp/vetting_foundation_hardening/native-v3-source-request-755-replay-20261004T010408Z/Q2_SAVED_OVER_FALLBACK.md)保留。98,304是131,072减两项16K预留的临时保守策略；128K来自GPT-OSS原生规格，**不是H800硬件最大容量**，不能据策略over_budget声称装不下。

原图复核已产生**42个整块文字修订的新44来源快照**（SL39、APL3），原OCR/原件/旧native-v3索引保留。四个未确认候选及漏行仍待核；重复短行用整页66块逐字符顺序证明定位，未靠bbox猜测。33个工具guard全过，needs_review／textAccuracy=unverified／humanConfirmed=false保持。[修订工具和来源身份](../../tmp/source-text-override-tooling-20261004/README.md)可复现；新生产语料已实际生成：13,464 parents／207,800 Parts，42个未改来源3,429 parents整树逐字保持，SL/APL的10,035旧parent IDs全部失效。[语料与身份独审](../../tmp/source-text-override-tooling-20261004/corpus_rebuild/final_manifest.json)已冻结。唯一新index-only已实际启动，旧固定缓存仅按严格原身份读取，新SL旧缓存不得冒充新向量；实际窗口、命中、forward和fresh-client读回待终态。检查页仍使用旧native-v3数据，不冒称已经部署修订。

检查后端已实际切换到新单文件inspection构建：**755 tests／70 suites全0，282生产class与实际JAR逐字相等**，H2内存隔离且模型关闭。真实281只读GET核验全部128 rerank及返回列表、19个历史实际请求/schema、旧新缓存隔离；新检索sent仍unavailable。前端类型及Vite构建通过，128任务均可选择。[当前检索检查页](http://127.0.0.1:5176/#/vetting-inspection?dataset=native-v3-full44&run=native-v3-current128)保留旧排序与历史送审的独立身份。未改Drafting源码或打开原应用数据库。

App Goal与日志目标保持进行中。下一步为修订快照的真实新语料/索引身份与必要条件预算验证；foundationAccepted、完整审查语义质量和H800验收仍未知。下面所有旧检查点按当时状态保留，当前状态以本节为准。

---

**当前检查点（2026-10-04）：** Root 已实际完成 **755 tests /70 suites，失败、错误、跳过均 0**。比赛按 120B 级 MoE 规划，共同 H800/MiniMax profile 为 **131,072 总 tokens／16,384 输出预留／16,384 安全余量，即 98,304 完整输入 tokens**；相关 whole requests 已按原 eligible origins 关联成组并移除旧 20K 字符门控，但原 37 个受阻请求的真实 MiniMax 计数与语义效果仍待复测。当前有 8088 隔离只读检查运行时及 [5176 检查页面](http://127.0.0.1:5176/#/vetting-inspection)。native-v3 full44 为 **13,464 父片段／13,602 真实 Qdrant points**，可检查的是北京时间 **2026-10-04 01:57:04** 的实际数据库快照，原库字节保持；不是实时应用库。排序检查保留旧 metadata-v2 primary64 真实检索，新 native-v3 的 128 查询及精度尚待验证。原生 DOCX 段落、表格正文及结构已有完整保留，尚未排版渲染，物理页未知，完整性不等于文字或法律正确性。旧 retrieval run 的 sent 材料不可用；历史 Bonsai 的 sent 展示新增仍在开发，不能写成已接通。详见 [H800 MoE 方案](vetting-h800-moe-plan-20261004.md)及 [方案 PDF](../../output/pdf/ConSense-Vetting-H800-MoE-Plan-20261004.pdf)。**后文原有内容逐字保留为历史检查点；软件与数据可检查不等于最终审查准确率已通过。**


## 最新执行检查点：Root710 与部分 MiniMax 计数已核实（2026-10-04）

当前仓库已接回 MiniMax 专用 Responses 适配，并实际完成 Root 离线 clean package：**710 tests / 65 suites，失败、错误、跳过均 0；275 个 Java 8 生产 class 与冻结版及 JAR 逐字一致，原 668 测试身份保留。** [真实 Root710 冻结](../../tmp/vetting_foundation_hardening/promotion-responses-710-20261003T155516966381Z-892f68badf0844a9b8960631576144af/frozen_root_build/manifest.json)已独立核对实际 XML、完整 source/target ZIP、class/JAR 和当前 Root 字节。旧“当前仍为668、710仅隔离未接回”的段落是历史快照。该适配限定 vetting、默认关闭，不改变共享 LLM/Drafting；软件 H2/loopback 回归不等于真实合同审查质量。

MiniMax 的 188 份完整 PreparedRequest 已冻结为新供应商/profile/16384 输出预留身份，原正文、schema、所选来源树和 SourceWire 保留。一次真实 counter-only 执行提交到第 25 份后停止：**前 24 份 HTTP200，供应商估计 4037–25031 input_tokens，按配置策略为 provider_estimated_fit；第 25 份耗时 10.0201889s，budget_unknown，没有状态、正文或 token 数；其余 163 份未提交。零生成、零重试。** [实际部分结果](../../tmp/vetting_foundation_hardening/actual-responses188-counter-20261003T1610Z-bff354d792e741d6a52e538d56587b11/counter_results/run_result.json)及[独立审计](../../tmp/vetting_foundation_hardening/independent-actual-responses-counter-review-20261004-v1/audit_receipt.json)已核对 25 份请求逐字身份、24 份原始 UTF-8 与 canonical JSON 响应 SHA，原 105 个安全工件前后不变。未知不能当空输出、超预算或精确网络原因；guarded transport attempt 不等于独立抓包证明。历史一次 193-token smoke 另保留，不能拿它代替本批或把部分结果写成 188 全部完成。

这些值是 **provider_reported_estimate**，不是精确 tokenizer/chat template、账号有效容量或生成验收。1000000 operation cap、16384 输出预留和 16384 余量属于配置策略；本轮未产生语义审查记录，准确率和召回率未知。凭据只由 Root child 执行者加载；独审未读密钥、环境或 .dpapi。Root terminal 清理标志与公共脚本边界已绑定，但未通过读取真实环境再次证明清理。

前端供应商估算状态 v1、拒绝原因/列布局 v2 均已接回；Root 的 vue-tsc 和 Vite 两轮实际构建 exit0。[v2 实际三语只读渲染](../../tmp/vetting_foundation_hardening/web-provider-estimate-rejections-20261003T1612Z/root_rendering/render_receipt.json)使用冻结 synthetic fixture，14 个 GET、0 mutation/forward/provider/AppDB，估算与未知限制、拒绝原因和 pending2of4/partial 已显示；owned tab、两个验证终端和 18900/18901 服务已关闭。[v2 Root 构建](../../tmp/vetting_foundation_hardening/promotion-provider-rejections-ui-20261003T163706064105Z-f825bd57219948eabbd459cac8627c78/root_web_build_receipt.json)绑定当前源码。**旧后端实例仍未切换；已有 Vite5173/5174 可能通过 HMR 使用新源码，因此不能说整个 UI 没变化，也不能声称同版本端到端运行已验收。**

报告另有[三语 synthetic overbudget 显示核查](../../tmp/vetting_foundation_hardening/packet-report-overbudget-synthetic-20261003T1600Z-v2/final_manifest.json)：旧 668 冻结 Writer 实际生成 PDF/DOCX，三份 PDF 每份 1 页、144dpi，已有视觉核查；DOCX 仅检查 OOXML 字体/文本/footer，缺 bundled LibreOffice，未完成 Word 视觉验收。这属于旧软件和明确 synthetic fixture 的报告显示证据，不是当前 710、真实供应商候选或真实合同报告验收。

基础链路的既有提升保留：44 源新索引 13464 parents / 13602 windows；10724 固定严格 cache hit、2878 变量真实 GPU embedding；独立全部点读回 payload/sourceSpans/float32 一致。原生行/cell/slices、来源质量身份、无损材料运输与固定六类 v5 搬移/cache 验证均有冻结证据。但 **128 次新索引混合检索/rerank 仍未实跑**；AQCC + SCC(6) + SCC(8) 的必要条件共同同包尚未证明，OCR 13 页仍 needs_review，Linux/H800 未实测。测试数量、索引字节一致与供应商 estimate 不能替代这些缺口。

下一步保留已观测结果，另按受限计划处理第 25 份未知及 163 份未提交；资源满足后执行既有 32 组 × 4 角色的 128 次真实检索/rerank，不扩 query 矩阵。继续核对必要条件同包、完整请求及预算、同版本保存/人工复核/Word/PDF 导出，再按用户方向用 MiniMax 做真实审查质量对比，忽略本地审查模型部署。本轮未等待或重开 GPU、未发生成请求；尚在隔离修复的诊断/回放不计作完成。**App Goal 保持 active，foundationAccepted 和最终语义质量仍为 unknown。**

---

以下原文逐字保留，涉及 Root668、710未接回、仅193-token计数等内容均是其当时快照；当前状态以上述检查点为准。



## 最新执行检查点：基础修复已接回仓库（2026-10-03）

本检查点更新下面历史 Root556 与“668尚未接回”的状态。当前仓库已接回36个后端文件及3个前端文件，另外两项测试工具可移植性修正单列；Drafting与其它保留文件逐字核实。实际Root执行离线clean package，**668 tests / 63 suites，失败、错误、跳过均0**；264生产class与旧668冻结版及当前JAR逐字一致，原668测试身份全部保留。前端实际vue-tsc与Vite构建均exit0。旧运行实例仍未切换，新源码与构建通过不代表当前浏览器已使用此版本。

实际完整target、source、测试XML、集成输出和JAR已冻结：[Root构建清单](../../tmp/vetting_foundation_hardening/promotion-coherent-668-20261003T150921549978Z-fa59277104154a3b92bb9a785debfd70/frozen_root_build/manifest.json)。保存及Word/PDF导出回归在隔离H2和明确替身上执行；两次loopback fixture合计60个HTTP请求。此证据验证软件接线、状态和产物，不能用作真实合同准确率或供应商调用验收。接回前target全量备份、首次guard拒绝、临时测试路径失败均保留。独立审计已核实接回来源、前端日志及MiniMax计数smoke；新Root整包冻结另行复核。

MiniMax专用Responses适配已在隔离版本完成：[710项回归交付](../../tmp/vetting_foundation_hardening/service-minimax-responses-20261003T1456090675944Z/final_receipt.json)，710 tests / 65 suites全0，原668身份保留；仅vetting专用、默认关闭，不改共享LLM/Drafting。新42项验证完整PreparedRequest、计数与派发投影、身份和配置漂移、失败拒绝；实际复现并修复了provider关闭后错误回落本地client的路线。计数明确标为provider_reported_estimate，预算为provider_estimated_fit，aggregate仍partial；不伪造tokenizer/template或容量证明。该适配尚未接回或发送真实API请求。供应商估算状态的前端3文件补丁亦已隔离通过24项guard及类型/构建检查，尚待浏览器核对和接回。

已验证的材料进展保留：44源新索引13464 parents / 13602 windows，固定10724严格cache命中、变量2878真实GPU编码，变量此次完整token features最大512且lost0；独立54次scroll核对全部13602 payload/sourceSpans/float32一致。原生结构3661完整行、13983 cells和55 slices逐范围核对。188个完整输入可逆运输均保留来源JSON树与范围身份，19844589→12520259 bytes（减少36.91%），只是字节减少。固定六类v5可搬移离线包已完成本机打包/搬移/cache核验，OCR13页needs_review；Linux/H800仍未实测。

下一步依次完成710适配与估算UI接回、188完整输入的真实MiniMax预算及排包回放、128次新索引实际召回/rerank与必要条件同包覆盖、同版本运行/保存/人工审核/导出核对，再按用户Goal用MiniMax做审查效果对比。128次query尚未运行，GPU空闲不足时不并行神经推理或终止用户程序。当前真实供应商只完成一次非生成计数smoke（HTTP200，193 input_tokens），未把它推广为188预算或1M有效上下文证明。最终语义准确率/召回率及完整基础验收仍未知，App Goal保持active。

---

以下为此前记录，涉及Root556或“尚未接回”的说法均是当时快照。



2026-10-03 最新用户Goal补充：**LLM验证先使用已提供的MiniMax API，忽略本地算例部署。** 当前先完成输入及证据门禁；后续API验证按实际接口、模型、完整请求、输出预留及返回计数绑定配置，不把Bonsai/NInfer离线预算当MiniMax容量证明。OCR、embedding、reranker本机基础验证继续；不继续本地审查模型部署或扩大选型/提示词实验。下面历史本机部署与暂停模型段落保留为当时快照，以此补充及最新App Goal为执行依据。

修订日期：2026-10-03。最新依据：用户要求先停止模型工作，总结全部发现，修复数据质量、切块、嵌入、重排序和 harness，并创建持续应用 Goal。当前执行正文以本文件最新修订与 [基础链路发现及验收](vetting-foundation-findings-20261003.md) 为准。历史实验和旧 Goal 状态保留为当时快照；最终 vetting 交付仍未验收。

## 修订后的目标正文

> 聚焦 vetting 基础链路，drafting 由其他队员负责。以赛方原件和冻结真实案例修复数据质量及 OCR 质量状态、原生表格结构传递、token-aware 切块、嵌入及索引身份、混合召回与重排序的必要条件覆盖，以及 harness 的失败、上下文、候选和人工复核状态。暂停新的审查 LLM 调用、模型选型和提示词实验；OCR、embedding、reranker 验证继续。每轮冻结代码、输入与参数，保存真实输出、失败、资源及修复前后比较；独立标注仅作评价，不回填实现。完成隔离回归、实际基础链路验证和可复现脚本/日志，明确未知范围。后续继续最终审查质量、前后端人工审核、同版本 Word/PDF 报告及 H800 离线交付验收。

2026-10-03 用户追加比赛约束与预建授权：NTT/SCT/SCC模板及GCT/GCC/SL固定，其他材料现场变化。基础修复须面向未知新材料，固定层与项目变化层独立管理，不按文件名或当前案例写特例。提前构建固定文件的可校验离线解析/切块/embedding资源包，保存原件、来源映射、质量状态、向量和模型/tokenizer/算法身份；现场校验复用，变化材料新建索引。补新增/删除/修改文件、长短文本/表格、项目隔离和失效验证。详细边界见[基础链路比赛约束](vetting-foundation-findings-20261003.md)。该补充作为进行中App Goal所引用的验收正文继续执行，无需停止现有Goal。

2026-10-03 用户再次确认固定六类材料可提前解析、切片、嵌入并带入超算：固定层使用已经完成的v4离线包；变化材料现场按实际原件/项目/角色处理。固定模板不能替代实际填写后的待审合同，预计算向量也不能替代当次rerank和审查输入覆盖。详细职责与缓存失效边界已记入[固定与变化资源验收](vetting-foundation-findings-20261003.md)。549隔离冻结版已修初始派生origin反例，仍待保存排名回放与接回；超预算必要条件分包、未知新材料端到端及Linux/H800验证继续，Goal保持active。

## 保留的原目标

原 goal：按照我们的计划，用案例文件做测试，生成结构化的审查文档（类似参考文件那样），完成 vetting 模块，直到效果达到预期，然后把项目代码更新一下，前后端都要（可以在前端审核审查结果，并支持导出结构化的审查报告）。

此次修订保留这个交付目标，补充模型实验、日志、runtime 和比赛环境验证要求。当前执行顺序先解决审查质量与证据关系，再扩大案例覆盖和部署验证。

## 当前优先级

第五阶段进行中：Root源码仍为556 tests/49 suites/234 classes，本轮逐文件重核与原冻结字节相同，旧服务未替换。44源native-v3新index已实际完成：13464 parents/13602窗口，固定10724严格cache hit，变量2878真实GPU embedding、758/758 forward；真实检索/rerank/审查LLM均0。独立新客户端54 scroll逐点核13602 payload/sourceSpans/float32向量全部一致，生产4状态文件前后不变；原生3661 rows/13983 cells和55 slices/115文本范围另有准确侧车核对。无损wire/完整token-aware排包已形成668 tests/63 suites全0的隔离修复；659独审发现literal数字精度/scale失真，668局部decoder已补9case，独审及188新完整Input/API预算验证继续，不声称已通过真实MiniMax容量或覆盖。前端实际只读38GET验证32主题9未提交/23部分、unknown及逐item完整包身份；另修顶部任务COMPLETED文案，三语言明确任务执行结束不等于完整审查，类型检查/Vite及实际浏览器通过，尚未接回或部署。固定六源v5搬移/cache验收保留，OCR13页needs_review。按用户最新Goal，后续LLM验证优先MiniMax API，不以本地审查模型部署/容量为前置；本轮0新审查LLM，Goal active，基础与最终语义质量均未宣告验收。

### 最新检查点：真实44源索引读回、668修复与页面执行语义

[全44新索引终盘](../../tmp/vetting_foundation_hardening/full44-native-v3-index-20261003T132419Z/final_receipt.json)：实际1 index、0 query/LLM，13464 parents/13602窗口，10724固定命中、2878新变量GPU编码、758 embedding forward完成，wall272.422s。新完整token features逐数组核对，max512/lost0；当前encoded到upsert逐元素一致。与旧实际向量按显式window映射比较，固定全部byteexact、变量1291/2878 byteexact，其余maxabs6.01261854e-6、mincos0.9999999982448565；这是数值回归，不能代替检索准确率或语义效果。

[独立fresh-client全部13602点读回](../../tmp/vetting_foundation_hardening/native-v3-fresh-client-audit-20261003T1357Z/actual_v1/audit_receipt.json)：关闭状态副本新client/54scroll/close，全部payload/sourceSpans/实际upsert float32一致，4生产状态文件前后不变。13464 canonical→normalized parents整树相等，无范围缺口。[原生结构侧车](../../tmp/vetting_foundation_hardening/native-v3-fresh-client-audit-20261003T1357Z/native_structure_sidecar.json)准确核3661完整Part.table行/13983cells、55Part.tableSlice/115selected文本，完整行重组和slice UTF16范围/hash一致。旧主receipt nativeRows0是Part.cells统计字段错误，原receipt保留；不是结构丢失。

[668隔离后端](../../tmp/vetting_foundation_hardening/service-lossless-literal-numbers-20261003T1415Z/final_receipt.json)：668 tests/63 suites全0、264 Java8 classes与JAR/resources一致。659为完整Input观察、可逆wire和whole-request token排包版本；独审6次实际wire roundtrip发现5种literal subtree的BigDecimal精度/scale损失，失败保留。668只改局部decoder保留JSON数字字面量及9个回归（修前5fail），不改全局JsonUtils/正文/ranks/SourceMaterial/其他provider配置。尚无新实际Input计数/LLM；独审、188单因素新Input导出、所选MiniMax API实际预算与排包回放继续。659/668均不冒称已接回或部署。

[前端实际浏览器检查](../../tmp/vetting_foundation_hardening/ui-render-validation-20261003T135424515645Z-b75bed2bf2894773bfa74b94a6a93e55/final_receipt.json)：冻结saved628的32覆盖对象显示9not_submitted/23partial、31全局超预算，旧无aggregate和预算unknown明确显示未知/未提交；两条人工UI展示引文逐项保留不同完整Input包ID与来源快照，真实formalCandidates仍0。38只读GET、0forward/LLM/AppDB。实测另发现顶部“审查完成32/32”误导；新两文件隔离补丁改为“任务执行结束/执行进度”及覆盖限制，简繁英实际渲染、vue-tsc/Vite全部通过。只证明UI显示，不是有效模型候选、原件/报告下载、人工写入或最终语义审查证明。selector的summary role反例、第一次繁体替换失败和owned会话Ctrl-C退出均保留；server finally close、端口关闭，旧实例不改。

### 上一检查点快照：完整预算反例、统一640版本与固定v5

[188实际完整输入原生计数](../../tmp/vetting_foundation_hardening/native-complete-input-budget-20261003T125514259180Z-e3e84051e5524f25ac5d35f6237d537f/receipt.json)，SHA `63775972698484d97c5e2c698764fbc53171e454be0113b3a0803e0272363249`。完整messages/schema/template实际计数，无截断；离线32K context、8192输出预留下30 fit/158 over，31全局和127额外超预算，范围4086–47339 input tokens。189次原生CPU调用包括1 CLI smoke及188逐包过程，0权重加载/生成/GPU/HTTP。188逐包完整native input/output/IDs/prompt保存，CLI smoke仅保存Observation，不称189份IDs均有独立归档；计数是offline_plan，不是当前在线容量验证。原Input、配置、软件及来源字节不变。来源正文之外，反复出现的Part字段、质量状态和限定说明贡献大量输入体积，之前20k字符正文上限不能保证完整输入预算。

[完整预算独审](../../tmp/vetting_foundation_hardening/native188-budget-independent-review-20261003T1306Z/final_receipt.json)，SHA `cb1e3eeb377190d96d40465f2294ef347b22a2f2cde5443c418a8ae4800314e1`：188 Input/messages/schema与native输入、完整prompt、UTF8 literal spans、IDs/count/Observation全部匹配，29 guards全过；两对重复完整输入的IDs和prompt相同，计数分别22711/23659。21自有及1614外部当前字节复核，253生产class/JAR与原生编译/helper/resources/DLL均匹配。独审为纯文件，不重跑tokenizer；保存的是运行前描述及运行后校验声明，另存当前字节复核，不冒称已保存历史结束时的独立snapshot。首次软件补充状态字段误读反例保留，后续只按真实schema适配。

[实际628 harness读取原生Observation回放](../../tmp/vetting_foundation_hardening/actual-offline-observation-harness-replay-20261003T1305Z/summary_receipt.json) SHA `ec768cb446280cc3280d7550fcbc331b3e129bd8ee2314927879b40fc2ef443b`：188个当前生产方法重新组装Input均与保存计数输入相等；158已知over全部0 Gateway，30 fit走实际Gateway/明确空替身，messages/schema相等；候选0。32 topic aggregates为9 not_submitted与23 partial，3911全部request ledger保留，3749 pending；没有verified/complete。真实模型/HTTP/神经/检索0，production CLI关闭且在线预算未验证。

[当前源数据紧凑运输prototype](../../tmp/vetting_foundation_hardening/lossless-source-columns-prototype-20261003T130337859031Z-06bfc277294d4b0695110b94c10515ec/receipt.json)，SHA `fb1d75dae5e99cc604ecce4ae2fa9eabd403e6038b8167b457415b13e390dddd`：全部188原SourceMaterial JSON树逐项/键序还原一致，来源快照、正文/cells/质量/unknown不变；188实际原生CPU计数得到135 fit/53 over，最大32131。此为输入运输大小实验，0模型生成，不是提示词语义效果对照。53项仍含Note1必要范围所在包，不能完成预算验收。独立[generic反例](../../tmp/vetting_foundation_hardening/actual-offline-observation-harness-replay-20261003T1305Z/lossless_prototype_peer_probe.json) SHA `916c77efd14c345155f62a647f35c47f5ccab94e341eb2eaec8019e1fb83d3e5`发现4项：保留词碰撞两种、True/1及1/1.0共享丢类型。原反例保留；严格Java可逆wire、reserved literal escape及完整token-aware原子whole request排包正在另起隔离版。full audit projection/corpus/ranks不改，新完整Input hash和候选scope须更新。

[188新版材料独审](../../tmp/vetting_foundation_hardening/harness624-material-audit-20261003T1242155213902Z/README.md)已冻结，13464 canonical/44 SourceDocs、3259 selected chunks/29933 Parts、3750 nativeRows/105 slices/47完整重建rows来源一致，21 guards通过。8个target×profile分别有完整单包；[共同范围补充](../../tmp/vetting_foundation_hardening/harness624-material-audit-20261003T1242155213902Z/target_request_colocation_supplement.json)明确Q6 AQCC/SCC6/SCC8全部共同同包在两profile均false。32 global经显式旧新ID映射后正文及顺序与Root556一致，新增native元数据另声明，不能说全部JSON byteexact或跨包union支持结论。

[统一后端640冻结](../../tmp/vetting_foundation_hardening/service-coherent-backend-20261003T1240Z/final_receipt.json) SHA `f08b4c6f183c1e1fddb117ffd11dd80cf0cc12d9d4e96ac6ca1d4e4284767679`：实际640 tests/60 suites全0，255生产class/Java8/JAR逐字核对。仅4production/resource和7tests/fixtures合并strict568与628；Drafting及628 observer原文保留。loopback wire/H2软件fixture单独计数，不称新真实索引或模型。未接回Root556/部署。新版wire/排包修复继续以640为底另起stage。

[固定六源native-v3 v5交付](../../tmp/vetting_foundation_hardening/fixed-portable-package-v5-20261003T1235Z/final_receipt.json) SHA `283736e649ad6d9bea4e1c738111b2166328214643dcf472a916cc8b7ab45a6a`：[ZIP](../../tmp/vetting_foundation_hardening/fixed-portable-package-v5-20261003T1235Z/ConSense_fixed_6_native_portable_v5.zip) 5271143890B、SHA `887d413ef946c7190c98940b274f46849dee43de1c40158d75360e00f805629a`，24259 files。10724旧实际window/完整tokenizer features/原始NPY向量经显式等价迁移到新身份/cache；实际打包、随机短根搬移、严格rebind与生产567 SourceMaterial的10705父/311 native rows核对、10724 strict cache hit通过，0新神经/模型/索引。深Windows路径首次解压失败及后续proof URI spell失败保留，修正仅短新路径及canonical文件来源核对。旧v4/旧cache不改；Linux/H800未实测。当前继续全部44源native-v3的显式向量迁移、新index与独立持久读回。OCR13页仍needs_review。

### 上一检查点快照：628隔离回归、65向量独立读回与188实际输入

最新[隔离harness冻结版本](../../tmp/vetting_foundation_hardening/service-source-request-harness-v2-20261003T1234Z/frozen_build/manifest.json) SHA `0e61f533612cd988715b422424d2488fc8bc1d320aefb9d81352405382dcff44`，628 tests/57 suites全0失败，253生产class逐字匹配JAR。新增反例在旧624版实际25 tests中4失败；修复后42 targeted tests通过：局部严格JSON解析拒绝重复计数/身份/嵌套字段，已知over_budget对全局和额外包均停止派发；未知全局兼容行为仍标partial，未知额外包拒绝。该回归使用隔离H2、mock provider和合成CPU CLI，真实模型/HTTP/神经/索引/应用DB调用为0。尚未接回Root556，不能用628测试通过声称当前浏览器已运行新版。

[独立三活动collection读回](../../tmp/vetting_foundation_hardening/native65-readback-v4-20261003T1224Z/audit_receipt.json) SHA `695c0ae407b107792f76c590361fe8ecf7c37aa89738d742e5742c12aa714faa`：17+24+24共65 points的完整payload/sourceSpans与最后一次实际写入的float32向量逐元素一致。保存的50 embedding及100 rerank forwards的完整输入/特征、窗口覆盖另行独立核对；审核不重跑推理。旧注册collection仍保留但不进入当前三份metadata的路由，本轮不证明历史存储回收。实际GPU生命周期覆盖增加、删除、修改、缓存及项目隔离；它使用当时564 native slice版本，后续567可搬移身份修订是隔离回归证据，两者不可合称新567 GPU运行。

[44来源native-v3实际产出](../../tmp/vetting_foundation_hardening/full44-native-v3-20261003T122032033906Z-946b6fe8c3b8449f9e7eb639dbce2e79/receipt.json) SHA `36e184a9ead7e6cadf8893971b5a282f54fae27f464715e42265e710e2228bb7`，从44份保存SourceDocument实际重建13464 parents，3661完整native row Parts、55 split slice Parts，全选投影重建25完整row；原正文/来源/角色/质量/owner/UTF16不变，IDs与native metadata显式升级。此为真实Corpus/材料投影和逐值比较，0新parse/OCR/神经/query/index。32组保存排名通过[显式ID映射绑定](../../tmp/vetting_foundation_hardening/full44-native-v3-rank-binding-20261003T122422186127Z-bd6f1402f34246a394d9ef65a93489d9/receipt.json)重绑，不冒称新模型排序结果。

[188份完整实际Input清单](../../tmp/vetting_foundation_hardening/service-source-request-harness-20261003T113523Z/actual_full44_complete_inputs/complete_input_batch_manifest.json) SHA `10b64ba35921a8afcd39a277947154f3e7bad9c6a713056e8d422ff3b1fb3cb2`：32组包含32全局和156额外Input，保存实际messages/schema/来源scope/输入哈希与3911请求ledger。此时实际token预算观察尚未执行，新native-v3完整来源/四个必要范围的独立审核进行中；旧582版四范围同包运输证据仍保留，不能直接推断新版已通过。cap、oversized及unknown请求仍须显式保留，不能以发完已选包声称主题审查完整。

[真实NInfer CPU tokenizer比对](../../tmp/vetting_foundation_hardening/native-chat-token-audit-20261003T121312670062Z-aea04ee6e85e4b9badff1c1df683a928/audit_receipt.json) SHA `c9a29c8bb7fc4ef22a3e5a1781e1717d6eef694061079700dcfc14268458a4af`：采用该引擎的完整chat template、schema envelope和literal spans，四份历史完整请求实测10774/7512/7524/5596 tokens，与当时原生接口计数一致；第五份unicode/特殊tag来源测试证明literal保护不同于整串直接编码。共5次实际CPU tokenizer、0权重加载/生成/GPU/HTTP。新CLI严格JSON4 guards通过；CJK外部fixture路径修复后原8项回归通过。以上不证明188份新输入预算或当前在线context。下一步按离线32K/8192输出预留计划完整计数，并保留offline_plan身份，生产CLI仍拒绝把offline_plan当在线部署证明。

[固定六源native-v3产出](../../tmp/vetting_foundation_hardening/fixed-native-slice-migration-20261003T1156Z/fixed_six_emit_receipt.json) SHA `6444dae249d68ff1ee0a846cc623fa0e4b2320624b6e40092bd30725f7c9f173`：6来源/10705 parents、311完整native row Parts，正文与来源关系不变。10724原实际向量window/features/cache的显式迁移及v5离线包/rebind验证尚未完成；v4原包保留。新strict568冻结已通过自身回归，最终需与628合成再构建；前端覆盖状态仅隔离编译通过，未部署。固定OCR13页仍needs_review，未将解析完整或索引一致性当OCR准确率。H800与最终审查语义质量未实测；Goal继续active。

上一检查点快照（现已由上方最新检查点更新，2026-10-03）：未知原生长cell的六种材料/身份组合已完成实际GPU生命周期运行，8次index、24次query、50次embedding与100次rerank forward全部完成，wall112.699s；其中包含缓存复用、增加、删除、修改及项目隔离。停止producer后，Root用新Qdrant客户端打开字节一致的状态副本，主索引17窗口的全部payload及原始float32向量逐元素一致，原状态字节未变。该小型运行不证明合同语义质量，另外两个项目collection尚待独立fresh-client审核。长cell564版已完成来源切片重建，strict568版已冻结；web覆盖状态隔离编译通过。上述最新修订均未接回Root556或部署。固定v5迁移发现slice内容身份含数据库documentId，旧portable rebind不能直接承载新版长cells，正按通用来源/布局身份修复；v4原包保留，v5未交付。SemanticReview分包/token预算/持久化来源scope与最终合并继续进行；固定OCR13页仍needs_review，H800未实测，0新真实审查LLM，Goal active。

2026-10-03 第二阶段已接回461 tests/39 suites通过的14份源码（146全src），selected-only native cells/原始块续段/OCR needs_review传播已修。旧430测试身份保留；测试夹具及质量断言的明确修正、原生33行材料和Note1源行运输对照见[基础链路第二阶段](vetting-foundation-findings-20261003.md)。旧实例和索引仍未替换；下一步补SourceDocument质量到Chunk/材料的通用元数据，完成token窗口实际embedding/rerank及fresh-index验证，再预建固定六源离线包。以下430版段落是本阶段之前的基线，不代表当前Root源码。

最新源码为430 tests/36 suites通过的基础版本，已接回但未部署Java/UI或替换索引。原编号、勾选、独立附录边界已修；本Goal第一阶段又修复全部角色的原生cells传递及报告“已核验”文案。二源2445个完整原生行Part已恢复结构，44原件哈希重核。旧1895向量机械核对通过，但新chunk IDs已变，不能复用旧索引。embedding46项、rerank3 pair截断及必要条件软排序漏召回仍待修复。历史全部发现、当前验收与证据统一见 [基础链路汇总](vetting-foundation-findings-20261003.md)。

1. 继续原生结构的下游材料传递，核对PARTIAL及固定OCR原页，保留未知项和原文范围。Corpus原生cells与44原件哈希基线已完成，不以此声称整体解析精度通过。
2. 再修token预算、块/条款/表格续段及嵌入/索引版本；新旧数据明确隔离。
3. 用来源标注评价dense/BM25/RRF/rerank/实际context，补通用必要条件与明确引用，保存逐层漏召回原因。
4. 核对harness真实预算、错误/空数组/门禁未到达、候选与人工复核状态，以及保存/导出身份；不新增LLM生成或扩展模型实验。
5. 以隔离测试、实际基础运行与可复现日志交付当前Goal。完整语义质量、44来源最终vetting应用、人审界面与H800验收仍为后续交付内容。

### 历史优先级快照（2026-10-02）

以下按当时状态保留；其中 active Goal 和旧实例状态不代表最新实时状态。

2026-10-02 新增执行要求：按用户“每一步布探针”的指示，用真实阶段输入/输出和身份链定位材料、理解与资源瓶颈。GPU全语料与64查询探针已实际完成，179-test Java严格检索及模型/门禁计时新整包run `1661b036-c465-477d-9d96-7d548d9c7ca6` 已完成21/21、19实际调用、4发现，独立47条源审显示质量仍未通过。AQCC在rerank17/top10之外、Note1在RRF68/cap50之外；部分已提交原文仍被模型误读，保险Schedule局部缺口又被unknown-as-issue及risk gate放行。197-test逐页解析/OCR/corpus/导出内部探针源码已精确接回仓库，原解析复用时新增OCR0、历史耗时unknown。用户进一步明确用本账号Codex作强模型同材料对照，先盲回放冻结输入，再揭示旧输出/原件评价；harness和采样差异独立记录。详细状态和证据见[逐阶段探针与瓶颈定位](vetting-pipeline-observability.md)。

2026-10-02 后续用户明确要求：“确定bonsai稳定可以运行就去跑vetting，别把目标搞偏了”。Bonsai已完成实际加载和多次完整输入生成，容量与KV的独立实验到此收尾；当前第一执行任务改为现有真实案例的应用整包vetting。使用Bonsai、实际检索和当前证据/审核/导出链路，独立核对误报、遗漏与覆盖。新的模型或工作流实验仅在实际vetting暴露具体问题后开展，不继续扩展参数ladder或只准备小范围原型。H800比赛验证仍保留在原交付目标中。

1. 完成本次真实44文件案例的应用vetting。当前run `5355ca9c-093e-4f9c-b879-06d15011a7c3` 已终态COMPLETED并完成同run报告/独立源审，质量未通过；固定Bonsai32K/rk4的本轮索引超时后词项回退，优先修实际检索和发现缺口、复核原始输出、覆盖及同run人审报告。模型能运行这一阶段已经结束，不在整包结果之前继续容量、采样或框架探索。
2. 用真实提交原文独立核对审查结果：有源支持、误报、局部观察、未知限定与未覆盖分别记录。实际完整生产corpus为13,293片段，其中标准参考10,615、招标合同2,663、项目事实3、目录12；全局来源数量不等于语义审查覆盖。CPU冷索引、超时及检索回退按实际日志记录，不由计划19次比较推断完成。原解析和OCR在本次复用，另行验证新的解析或扫描件。
3. 只针对本次vetting暴露的具体问题修复解析、检索、来源/表格关系、输出协议或提示词，并以相同范围复测。参数比较继续遵守 [本机调优计划](vetting-local-tuning-plan.md) 的可复测约束，但不独立扩展实验矩阵。保持最小runtime可回放、调用受限、错误可见；复杂agent和框架改造须有实际案例必要性及增益证明。低比特27B与此前3B/7B的结果不能替代比赛模型，也不能单独证明复杂agent必要。
4. 核对同一结果版本的人工审核、处理意见与结构化Word/PDF导出，逐页验证实际报告。现有历史结果和不可变证明继续保留；历史界面验证不替代本次Bonsai结果的前端复测。
5. 准备比赛机器的离线包并在实际 H800 环境复测。记录模型权重、索引身份、依赖、有效参数、吞吐、显存、运行时间和审查质量；本机结果不能代替 H800 结果。

## 本机与比赛模型的职责

2026-10-02 后续用户授权试用 Bonsai2 27B + NInfer，并要求上下文窗口尽量大。本轮已固定公开转换权重、引擎 revision 和 24 SM 适配，完成构建、短输入及32K/48K/60K/63K/64K/128K实际容量探索；256K因资源预算不足未执行。仅配置 `max-context` 不等于该窗口可用；每档记录 KV 类型、真实输入 tokens、峰值显存、延迟、失败与质量。完整 vetting 输入不得为适配窗口而静默截断。低比特权重与 KV 的结果只代表该部署配方，不能当作 27B BF16 或 H800 的效果证明。随后在固定本机模型配方内调整实际采样参数，并准备最小结构流程对照。进展和工具链故障见 [讨论记录](vetting-discussion-and-goal-20261002.md) 及 [实验日志](vetting-experiment-log.md)。

2026-10-02 用户提醒：比赛 H800 80GB 不会以 4B/7B 作为核心审查模型，当前错误可能主要来自模型能力不足。该提醒调整后续实验顺序，应用 Goal 的交付正文保持不变。

4060 8GB 继续验证解析、OCR、embedding、混合检索、rerank、来源绑定、输出协议和低成本回归；小模型的语义质量只代表该配方。H800 的核心信息整合先准备 27–35B 级候选，并加入 72B 量化模型作挑战者。候选以实际合同源审结果选择，参数规模、公开通用榜单或框架名称不决定最终主模型。

截至本次修订，已核对的候选为 Qwen3.6-27B、Qwen3-32B、Qwen3.6-35B-A3B 以及 Qwen2.5-72B-Instruct-AWQ，均尚未在本项目实测。模型代际、量化、chat template 和 thinking 配方不同，跨候选实验只能比较完整部署配方，不能只归因于参数量。35B-A3B 的 3B 是每 token 的激活量，权重和显存预算按总模型计算。权重容量推算不能替代 H800 实际加载、上下文缓存、吞吐及质量验证。

完整候选来源、资源预算与对照边界见 [讨论与技术决策记录](vetting-discussion-and-goal-20261002.md)。当前保持既有来源绑定和独立 34 项/13 字段评价；独立评价答案、标注及案例目标值不得进入实现或提示词。已有 Bonsai2 27B 低比特配方的 4060 实测，尚无 H800 实测，本机原型不会被描述为比赛质量已验证。

2026-10-02 容量阶段已完成：32K/CUDA graph 开启实际处理31,443输入tokens；60K/graph关闭处理59,394；63K（max-context64,512）/graph关闭处理62,386，均无缓存复用、三个远距测试值正确、输出65tokens。64K（65,536）开启和关闭graph均因运行时显存预算不足在启动阶段失败，128K亦失败；256K未执行。当前最大成功配置为64,512，但尚未测试完整边界或长输出，不能写成稳定63K合同理解能力。原四任务plain pilot采用60K，temperature0.2、输出预算8192、no-thinking、rk2v4-e8、prefill128、并发1；输入原文完整保留。其后同60K的presence0对照却在启动时缺11,442,688B、0生成，说明运行时可用显存预算波动仍影响该档；波动原因未单独测量。当前改为48K作为余量更大的日常候选，已验证容量及同48K/同实际seed的presence1.5与0成对比较。技术候选配置不自动成为应用默认，重复稳定性、源审结果与后续同模型流程对照决定是否接入。

2026-10-02 此前12组阶段快照：12组实际runtime中4组启动失败、0生成，另8组共17次预训练生成。48K容量实际输入47,415/输出65，原文三处测试值正确，cache0；同48K的四任务两臂输入、模型、引擎、输出预算、完整prompt及四个uint64 seed相同，有效采样唯一变化为presence_penalty1.5→0。1.5臂独立源审为错误1/部分3/完整支持0、schema3/4；0臂为错误2/部分2/完整支持0、schema2/4。0臂VPC否定触发和电话相同的局部观察有改善，但绘图地址的错误来源归因更明确，整条结果未改善；两臂qualityAccepted=false。保留1.5作为诊断基线、48K作为日常技术候选，均不作为已验收的应用模型配置。此处所述KV计划已完成；最新14组技术与源审收尾状态见下段。最小结构流程保持已准备状态，按用户纠偏先执行真实应用整包vetting，只有实际案例暴露问题后再做必要对照；本轮不以低比特27B的失败判定复杂agent必要。原始证明和最终sidecar入口见实验日志。

## 验收要求

| 层次 | 验收内容 | 不能替代该层验收的结果 |
| --- | --- | --- |
| 文档解析和 OCR | 物理页、段落、表格、删除状态和来源范围可追溯；固定扫描样本核对识别错误；解析失败及遗漏明确可见 | 请求成功、返回非空文本 |
| 检索与 rerank | 用独立标注的查询和相关条款检查 Recall@K、排序及最终上下文覆盖；保留条件、例外和交叉引用 | 相似度高、检索到某份文件 |
| 信息整合 | 逐条验证同一对象、用途、条件及项目采用关系；正确区分请求、已填回复、空回复、无 Reply 列、明确否定和模板占位符 | 合法 JSON、引文能定位、模型更大 |
| 审查质量 | 有支持的问题、误报、遗漏、正确的局部观察和 unknown 分别评价；独立标注与实际提交范围相对应 | 候选数、consistent 数、空数组或零问题 |
| 前后端与报告 | 实际案例可审核，处理意见和 addendum 决定可保存；Word/PDF 保留结构、证据、人工记录和可读布局；保存与导出使用同一结果版本 | 离线替身测试、报告文件生成成功 |
| 比赛运行 | 离线依赖、权重、索引和配置可复现；在实际比赛环境核对资源、速度及同口径质量 | 4060 跑通、推算 H800 性能 |

质量评价集、范围和门槛应在比较前写入实验计划。尚无完整标注范围时，accuracy、precision、recall 保持未知；不得临时选取最好输出或将未评价项计作正确。尚未确认的数值门槛不冒充用户已批准的验收标准。

## 当前应用 Goal 状态

2026-10-03 用户明确要求“生成一个目标任务挂着跑”后，已调用应用 `create_goal` 创建本文件最新基础链路目标，工具实际返回 **active**，未设置token预算。实际创建结果保存在 [Goal创建收据](../../tmp/vetting_foundation_hardening/goal_created-20261003.json)。本段为最新状态；下面历史实验中的null/active记录只表示当时快照。Goal正在推进，不代表基础或最终审查质量已经验收。

讨论依据与本轮状态见 [讨论与技术决策记录](vetting-discussion-and-goal-20261002.md)。每次实验的原始记录、失败与独立审核见 [实验日志约定](vetting-experiment-log.md) 和工作区 tmp/vetting_experiment_logs。

## 历史应用执行补记：44份真实来源（2026-10-02快照）

2026-10-02，容量、采样及KV阶段最终收尾为14组实际runtime、25次预训练生成，4组startup失败且0生成；旧12组、17次快照保留。最后同32K两臂的完整请求、输出8192预算、四个实际uint64 seed及resolved sampling相同，仅KV dtype从rk2v4-e8改为rk4v4-e8。rk2独审为错误1/部分3/完整支持0；rk4为错误1/部分2/完整支持1，后者只是一条有源支持的局部图纸地址比较，两臂qualityAccepted仍false。没有全面accuracy/precision/recall，也不把四任务当整包验收。当前不再扩展容量、KV或单独框架实验。

实际应用目录为`tmp/bonsai_vetting_application/full-case-20261002T1133137425760Z`。最新Java完整163 tests、18 suites、0failure/error/skip和独立JAR185 production classes已绑定；44份项目原件及既有解析结果从一致性备份复制到fresh DB与新存储目录，全部PARSED，角色为35 tender、5 standard、3 project_fact、1 package_manifest。复用既有解析/表格/OCR，不称本轮重新解析或OCR。2d/2e参考答案仍只用于独立评价。

实际唯一POST在11:45:32Z启动run `5355ca9c-093e-4f9c-b879-06d15011a7c3`，后端18083，只轮询同一run，不重复启动。应用配方为Bonsai2 27B、context/KV32768、rk4v4-e8、graph off、no-thinking、prefill128、并发1；结构化输出预算8192、transport retry0。为给Bonsai留显存，本轮BGE-M3和reranker改为CPU float32、maxTokens512、batch4，使用8869独立state并新建索引；不能把整包差异归因KV或模型规模。记录时CPU index仍在运行，16主题+3项目资料比较是19次计划模型比较、21个计划progress units，均不是完成计数；实际终态、引用、误报/遗漏、人工审核与导出验证随后从原始证据追加。

统一应用事件ID为`bonsai-full-application-20261002T114532-5355ca9c`。首次完整身份日志在11:52:48Z补建，明确晚于POST；不冒称运行前已冻结完整计划。模型与运行源码已在启动前有固定身份，44原件SHA/角色/文件名及SourceHash另作绑定。动态runId、调用、覆盖和资源在measurements/evaluation中追加，参数与来源身份固定。当前Goal仍active，质量目标尚未达成。


## 本轮应用终态与下一执行点

上述启动时的pending观察保留。2026-10-02 12:26:59Z，同一run `5355ca9c-093e-4f9c-b879-06d15011a7c3` 实际COMPLETED、21/21 progress，原生日志19 request_start与19 request_done对应，7条最终发现（3规则、4模型）。这是应用终态，不是质量验收。CPU冷索引等待30分钟后Read timed out，实际采用词项检索；此次没有完成BGE/rerank整包检索验证。13,293生产片段中，147个唯一片段、22/44份文件进入成功模型调用，未提交范围没有完成语义审查；原解析/OCR复用。资源sampler已停止，2,475实际样本、整机GPU采样峰7,763/8,188MiB、整机RAM峰24,513,654,784B，均不是单进程峰值。

独立源审qualityAccepted=false：3规则仅有限源观察成立，不能给Bonsai记为模型发现；4模型结果中VT-012/VT-015明确误报，VT-013/VT-014有局部原文但问题未充分建立。VT-012忽略自己的“notwithstanding Exception 3.4.1”引文；VT-015把模型切片结束当成原Word缺句。固定34项为5局部支持/8部分/21未评；13字段的值与用途仅局部支持，不代表13项完整合同义务、条件或采用关系通过。整体accuracy/precision/recall仍未知。独审和原输出不改，入口见[实验日志](vetting-experiment-log.md)。

同run JSON/DOCX/PDF实际导出并完成Word16页+原生PDF16页逐页查看。511个正文段落及15条证据全文保留，15处标签与引文开头两格式同页；API与report的72处对象差异全部是省略null与显式null，实值一致。当前原生PDF末页只剩一行warning尾句，EN系统warning第8页仍有中文fallback前缀，排版和系统语言未验收。7条状态仍Open、人审四字段均null。当前前端已用本轮existing dist的hash一致副本启动loopback5175并实际显示21/21/7和词项回退；人审保存及导出同版本的互动验收仍需独立证据，不借用历史UI结果。

当前工作收敛到这些实际缺口：恢复可完成的整包检索及其覆盖证明、避免把局部切片/例外关系误判为合同缺陷、修报告显示和尾页布局，再用同一结果版本完成用户审核与导出。不继续容量/KV或框架ladder。此前4B/7B与Bonsai对同四任务有有限局部改善，但system、输出预算、native grammar及部署不同，不能单独归因模型规模，也不能代替本轮整包质量。H800仍未实测，Goal保持active。

## 2026-10-03 MiniMax-M3直连同材料对照：首五未通过，余14未发送

时间按Asia/Shanghai为2026-10-03，实际API与证明时间为2026-10-02 UTC。用户提供OpenCode Go后，首次请求因缺x-opencode-session返回400；补官方稳定UUID会话头后返回403，响应称需要active Go subscription。用户确认已启用Go，因此这只证明该请求的entitlement校验失败，未证明用户账号未订阅；workspace/key归属尚未定位。用户随后明确改用大陆MiniMax Token Plan，Go诊断停止。两个Go POST均未得到模型回答，分别保留失败recipe，不自动重发、不混入质量统计。新订阅凭据以Windows DPAPI保存在repo外，通过瞬时环境变量注入；key不进入模型消息、源码或实验日志。

MiniMax官方只读Token Plan用量GET返回HTTP200/base_resp0；实际随后五个POST均HTTP200/MiniMax-M3/finish_reason stop。选择稳定M3，当前官方Token Plan支持M3；M3.1-Flash-Preview标注只通过M Plan/MiniMax Code，未假设预览权限。大陆直连https://api.minimax.cn/v1/chat/completions，仅stdlib HTTPS，无SDK/CLI/代理/重试/额外Codex system。M3参数为thinking adaptive、reasoning_split true、temperature0.2、max_tokens16384（含思考）、stream false、n1，reasoning_effort不发送（当前文档仅preview适用）。保留原Bonsai的system projection、user、schema和消息结构；19准备输入逐字验证，五个实际wire body再次绑定。模型/provider、thinking、输出预算、tokenizer与服务默认项有差异，非严格单模型因果实验。模型1M窗口是官方规格，没有执行1M输入容量试验。

冻结19输入后，先按1/2/3/6/11跑首5；运行前登记，串行、retry0、180s socket timeout并非绝对wall上限。五次wall分别108.079/172.797/172.031/134.828/73.360秒，合计661.095秒；provider报prompt21143、completion45875 tokens。usage中reasoning_tokens口径不一致，保留原值，不据零值推断无思考；remote GPU/prefill/decode未知。原HTTP实体/headers/usage/model/id/finish和content/reasoning分别存档，未剥fence、拼reasoning或修JSON。

| 原ordinal | 实际结果与独立源审 | 原179门禁 |
| --- | --- | --- |
| 1 付款/删除 | 正确认出14.2(4)已删除；唯一新issue是缺(e)，被原Word自动编号反证 | 1 raw通过并成为候选，但仍是false finding |
| 2 人员数量 | 合法[]，未重复QCM two≥one的互斥minimum错误；空数组不代表AQCC岗位/时长/适用全部审查 | 0 raw |
| 3 定义 | 1 parent-role输入未知、1 CTA/Site Staff条件建议；未证明合同排除或冲突 | 2 raw均因引文定位失败拒绝 |
| 6 HOMES | 正确区分asterisk remarks与两列，但把解析丢失的22个勾选误认为原合同缺填 | 1 raw因改写引文拒绝 |
| 11 保险 | 原JSON字段名损坏，未修复；可读文字的广义A3/专业责任概括也过宽 | 原生产解析失败，schema及record gate未到达，raw=null |

自动编号证明：SCC native body child936对应Java body/908/paragraph，numId14首次且唯一使用、无override/style引用，abstractNum10/ilvl0/start5/lowerLetter/(%1)确定显示(e)。HOMES证明：PRE native table47对应Java body2326，rows1..11 cells2/3共22个Wingdings:F0FC w:sym；当前collector均丢失。编号和符号是原件OOXML，不是OCR；本轮没有重写原解析缓存。DocumentParser的DOM collector只处理w:t/delText/tab/br/cr/p，缺编号上下文及w:sym，VettingCorpus再拼getText无法恢复。二者分别定位到parseDocx/wordBlock和collectWordText，后续先修解析保真与源provenance，再用新材料身份重建索引复测。

实际原179类的单次CPU mixed5回放exit0，19 prompt/selection断言等同，65检索边界为保存文件替身，不是真实index/query。首5四条有效raw=1机械接受/3拒/1候选；第11原JSON的Unexpected9在line9 col19导致模型解析阶段失败，不能记schema rejected或有效保险finding。唯一候选(e)被原件反证。原生产只查6quotes=3located/3failed，另4因prefix-break未查；独审遍历10quotes有6逐字定位，口径分别保留。混合19为36raw/19accept/17reject/1candidate，其中未替换14仍是原Bonsai32raw，与旧结果逐项相同；不是19次MiniMax生成、新整包run或应用DB结果。

独审四条raw分类：2原件反证false defects、1适当输入范围unknown、1条件协调建议，confirmed contract defects0；第11可读文字另列，不加入有效raw统计。原Bonsai首5有15raw、原Codex首5有4raw，数量不能作为准确率。M3在部分删除/计时/minimum/remark解读上未重复旧错，但原件编号/符号缺失、RAG条件漏召回、未知推广成缺陷及不合格结构输出仍是独立瓶颈。此前AQCC duration rerank17/top10、Note1 RRF68/candidate50和Schedule未提交的缺口没有因此被补齐。

按照“首5输出合格后再扩展”的计划，当前暂停余14的发送。全19准备完成不等于实际全部生成；实验terminal为failed_initial_review，5个transport成功、4个schema数组、1个JSON解析失败。accuracy/precision/recall为null，qualityAccepted=false，完整vetting Goal未验收；H800未实测。当前不凭这组结果引入agent框架，优先修源材料保真、必要条件召回、unknown/absence门禁，再单列格式提示或结构约束对照。新source/parser/index身份不得沿用旧冻结输入冒称同材料。

软件仅mock测试最终9项通过（10.646s）。适配时两个旧Go host/path fixture失败日志保留；corrected fixtures与provider base_resp失败停止检查通过，没有新增测试模型调用。登记helper曾把in_progress ordinal3放进不可改calls前缀，终态追加被拒；保留旧快照，后续actualCallMetrics绑定完整实际终态。该日志观察器修正未改API请求/回答或生产gate，不能归作模型质量问题。

统一结果：[同材料逐次对比与汇总](../../tmp/minimax_material_control/final-first5-20261002T162339182102Z/evaluation.json)，SHA 237cd32d4b6c9638356fc1289a26e7d86f66eff422d92c986f1807e3e76c6e89；[源审说明](../../tmp/minimax_material_control/independent-source-review-20261002T160511Z/README.md)；[原Word编号及符号证据](../../tmp/minimax_material_control/independent-source-review-20261002T160511Z/native_numbering_and_homes_symbol_proof.json)；[原179回放证明](../../tmp/offline_179_direct_api_replays/minimax-first-five-20261002T161149167103Z/final_proof_manifest.json)。统一registry与comparison已追加terminal，247个artifact身份由root再次实算核对，key不在日志。

官方依据：[MiniMax OpenAI兼容参数](https://platform.minimax.cn/docs/api-reference/text-openai-api)、[Token Plan Key与用量接口](https://platform.minimax.cn/docs/token-plan/faq)。每次源审仅评价实际提交范围，gold不回填模型输入。

## 2026-10-03 Word 保真修复与二来源真实复测

已完成已确认的两个源信息丢失修复，并把冻结测试通过的 7 个解析及测试文件精确接回仓库；完整 246 项、针对 35 项测试均为 0 failure/error/skip。真实 source80/SCC 与 source95/PRE 用新解析器重解析，原件字节保持不变，派生 sourceHash 和新索引另作身份。SCC14.302 的自动编号 `(e)`、HOMES 表格的 22 个 Wingdings 勾选及 provenance 已恢复；这两处还经过原 OOXML、chunk/Part 和实际 submitted user 原文核对。

本轮只有两份来源、1,859 chunks、一次真实 CPU 新索引和两次真实混合检索。两个新的 MiniMax-M3 POST 都完整返回 HTTP 200/stop，但付款 call1 是合法 `[]`，HOMES call2 的原始 `suggestion` 字段少冒号，正式 AiGateway parser 失败，有效 records 为 null；不能把可读的三段文字记为三条通过记录。零候选也不表示质量通过。当前没有完整 precision/recall、44 文件新应用 run、前端复测、H800 或新 OCR 质量结果。

当前优先解决必要条件召回及 unknown/absence 规则：HOMES call2 实际只提交 9 chunks，完整来源中的 SCC22.304(6) 未进入这个窗口；不能把未提交当作合同缺失。再以独立实验验证格式控制，不依据 HTTP 成功、schema 提示或 framework 名称作质量判断。共享 DocumentParser 的新 PARTIAL 状态可能影响 drafting 上传与模板状态计数，DraftingService 没有修改；后续部署需核对这个共享边界。生产服务和前端未重启，旧 UI 仍使用旧实例，仓库源码接回不等于已部署。

App Goal 的最新实时结果为 `null`；过去创建 active Goal 的记录保留为历史，不据此声称当前仍有 active Goal，也没有擅自重建。工作继续按本文件的交付目标推进，目标质量尚未验收。完整结果、模型逐段独审和后续安排统一见 [本轮 DOCX 保真结果](vetting-docx-fidelity-20261003.md)。


## 2026-10-03 显式引用范围与 20k 单次 MiniMax 对照终态

通用 ContextBuilder 引用扩展已通过 26 针对 / 257 完整测试并接回两个文件，214 个生产类与冻结 JAR 逐字对应，原 246 项测试身份保持。独审在初版发现重复/null heading 及动态起点第二跳，v2 同四个 fixtures 全通过；初版失败证明保留。起点固定为初始已入选 ranked core，目标绑定 doc/hash/role/heading，完整父条预算不足保留 missing IDs，不推断原合同缺失。

保持原件、新解析及历史真实 CPU ranking：旧 HOMES 10k 为 9 chunks/9671 chars、SCC22.304 5/10；新10k为11/9925、6/10，(6)/(8)仍缺；新20k为18/17203、10/10，正文与续段均完整进入。新增解析/索引/查询/OCR均0，只有生产 context 回放；exactSubclauseVerified仍false，4个origin unresolved，qualifiers unknown。Root launch classpath 已记录，125依赖JAR为运行后字节观察，不宣称launch前immutable。

唯一新 API（同M3/.2/max16384/adaptive/reasoning_split/n1/serial1/retry0/socket180、同445-byte格式包）HTTP200/stop、158.656s，prompt10625/completion10907；合法raw1，机械accepted1/candidate1，三quote定位、真实basis一次。它没有重复旧PRE→SCC误归和缺(6)/(8)判断，仅是一轮观察。唯一issue却把下一APPENDIX IV独立Subtitle当HOMES表页脚；原Wordsection/header关系反证：HOMES属于III，IV下一章是APPEND4.9水龙头冲洗。当前Corpus把带点/斜杠的下一附录marker继承上一owner，逐字quote gate无法验证版面角色。独审confirmedIssues0/falseIssues1/qualityAcceptedfalse，整体precision/recall/accuracy未知。

当前执行点是新的通用章节边界修复及新segmentation/corpus/index/material验证，避免改源编号迎合误报；随后继续完整来源、人工审核与同版本导出。Source、语义claim归属和门禁问题仍需分别验证，暂不以这条错误决定复杂agent框架必要。默认预算/旧运行实例尚未替换；H800与新OCR验证未做。App Goal本轮查询仍为null，没有擅自新建，也未将完整vetting目标标记完成。

完整参数、范围、原始失败和阶段对照见 [显式引用范围修复与上下文预算对照](vetting-reference-closure-20261003.md)；[最终比较](../../tmp/docx_reference_closure/final-comparison-20261002T185937009135Z/evaluation.json) SHA `1c4d49ffd0e9c822dc28dc0ede0c21966c80260fce1e2a4dcfedde5d752fc655`。统一registry追加4个独立事件及后续source评价，原观察与旧报告保持原身份。


## 2026-10-03 独立章节边界、新 CPU 索引与 thinking 对照已完成

本轮继续 vetting，不扩展 Drafting。已修复独立 APPENDIX/ANNEX/SCHEDULE 与 Word Title/Subtitle 继承上一条款 owner 的失真，表格内值与普通正文不误切分，无法确定 owner 时保留 unknown。新增 segmentationVersion=standalone-section-boundary-v1 并重算所有 chunk IDs。隔离 package 268 tests/30 suites、针对28项、0失败/错误/跳过，旧257项身份保留；14独立反例修复后全通过。源码基线129份中Corpus1份改变、其余128份字节保持，新增测试文件1份，共130份核对；两文件精确接回工作区，214个生产class与JAR对应。初版误切分及freeze脚本旧报告路径错误的证明保留。

复用SCC80/PRE95真实保存parse，新chunks1859→1895，11342 Part occurrences及全部保存的非空blocks coverage核对。PRE parseStatus仍PARTIAL，不能把保存parse到chunk传输完整性解释为原件所有结构已完整解析。新本地CPU/float32 BGE-M3+reranker索引1895条、cached=false，固定maxTokens512/batch4/threads4/interop1/topK10/candidates50/RRF60/串行1/retry0，索引743.476s、两查询28.545/29.240s、工具整体810.421s。17次encode wrapper与2次rerank成功；内部forward调用数未知。落盘1895向量与实际编码输出逐元素一致、1024维finite/unit norm；不是检索语义准确率。62次采样最大进程RSS约4.516GiB、机器可用RAM最低约10.218GiB，真实峰值与GPU未知。完成后的Qdrant析构警告单列，不当作索引失败。

token裁剪index46/1895、rerank3/100，但焦点(e)、HOMES、SCC22.304(6)/(8)未裁剪。(6)/(8)rerank为21/14，纯top10仍漏必要条件；生产引用闭包将完整父条10/10及续段加入20k实验材料。两主题12chunks/3306 UTF-16字符、18/17166；30个选中chunk occurrences/102 Parts与corpus对应，IV marker已不进入材料。14 literal traces（2 exact clause、12 full parent）required/submitted完整、missing0。4个unresolvedComparisonIds是origin比较提示，并非4个literal targets缺失；精确子条款metadata、限定条件完整性和全文absence仍未知。

实际新API POST共2次，均直连大陆MiniMax-M3，.2/max_tokens16384/reasoning_split=true/n1/streamfalse/串行1/retry0、同445-byte格式指令。新分块adaptive HTTP200/length、133.235s，completion16384、reasoning_tokens16383，message缺少正式content；状态failed/output budget exhausted/semanticNotAssessable/gateNotReached。不能转换为[]、不能称JSON语法失败，也未评价旧IV误报是否复现。新单参数对照只改/thinking/type为disabled，完整messages和20k素材相同；HTTP200/stop、1.484s、原content为实际[]、completion2。正式gateway解码1/schema1，parsed0/parseFailed0/shapeRejected0；无record故evidence0/basis0。这一次没有旧IV误报，没有正例可测召回，accuracy/precision/recall/整体质量/重复稳定性仍null，不据此改变生产thinking默认参数。

日志同时保留API v1 query包装适配错误（synthetic mocks通过但真实prepare安全拒绝，HTTP0）、修正后的v2与真实input guards、adaptive失败、disabled空结果和独审更正。统一registry向CPU/adaptive/disabled三个实验各追加independent_audit事件，原parameters/inputFingerprints/status/measurements与调用前缀逐字保留。新分块与旧轮之间多因素改变；只有本轮两请求是thinking单因素对照，耗时不宣称性能优化。

后续执行点：先用固定、原件核实的真问题正例与无问题反例，锁定修正后material，做Bonsai/M3、thinking与output budget对照；分别计漏报、误报、引文定位、来源角色、条件覆盖、输出预算和资源成本。如果纯prompt仍不能稳定完成字段/条件/来源核验，再加入有步数和token预算的检索扩展、source核查和claim复核，自研状态机或开源runtime改造依据实测选择。输出缺失、解码拒绝、依据检查未到达与合法空结果必须分开记录。

完整报告见 [章节边界修复与同参数复测](vetting-appendix-boundary-20261003.md)；[最终机器对照](../../tmp/docx_appendix_boundary/final-comparison-20261002T2000Z/evaluation.json) SHA `f89ee3490a5e9749ac1704525a3891e5ff3201e684bb6ca30c72762b78073a4a`。真实调用范围：parse/OCR0、新chunk构建1、本地向量索引1/query2/encode17/rerank2、外部API2、saved正式gateway解码1；完整application/H8000。运行服务、UI、旧数据库和旧索引尚未替换，应用默认context预算仍保持原配置。本轮未验收44来源、正例召回、全量OCR、H800性能、人审及同版本Word/PDF导出。

最新get_goal仍为null。此处更新工程目标和执行点，不代表新建app Goal或整体验收完成。

### 新检查点：MiniMax 真实计数访问、668 独审与 188 无损输入

MiniMax-M3 的 Responses 输入估算已实际调用一次：HTTP200、object=response.input_tokens、input_tokens193、0生成、0原件/gold读取；直连TLS、无proxy/redirect/retry，冻结请求字节与实际发送保存体相同，凭据仅DPAPI瞬时注入不入工件。此例是benign协议访问检查，不证明188包预算、Chat Completions模板、隐藏tokenizer/template、1M容量或语义质量。[实际收据](../../tmp/vetting_foundation_hardening/minimax-responses-counter-smoke-20261003T144941357263Z-51b74cb5c22d4b9a94608c19f1d0b567/actual_attempt/result.json)。6项可复现fake-HTTP协议守卫通过，包括重复派发、generation路径/输出、duplicate/bool计数与凭据回显拒绝。

[官方Responses输入估算](https://platform.minimax.cn/docs/api-reference/responses-input-tokens)使用专用请求协议。当前Chat Completions不能直接拿这一估算冒充自身完整模板计数。[只读设计](../../tmp/vetting_foundation_hardening/minimax-wire-budget-independent-design-20261003T1442128797160Z/README.md)已冻结；default-off vetting专用Responses适配开始隔离实现，counter与未来dispatch共用PreparedRequest/input投影，显式provider_reported_estimate和context policy，unknown对global/extras均阻断，Drafting默认client保持。还未通过该新适配验收。

[668与页面独审](../../tmp/vetting_foundation_hardening/ui-and-wire668-independent-review-20261003T1423327131936Z/final_freeze_manifest.json)已完成：同一原6反例全部通过，旧5失败清除；UI只读fixture/lifecycle修复证据逐文件核对。[188实际输入交付](../../tmp/vetting_foundation_hardening/lossless-input-tooling-20261003T1320Z/delivery_receipt.json)全部source有序JSON逐包还原，32global/156extra，完整Input19844589→12520259 bytes（减少36.91%）；sourceSnapshot、system/schema/provider/Selection不变，21工具守卫通过，0实际外部counter。文件字节减少不是token或效果证明。

新索引32组×4角色=128真实retrieve的plan与16工具守卫准备就绪，当前这轮actual retrieve仍0；GPU被本Goal之外运行程序占用时不启动并行NN、不终止用户进程。已有独立13602点读回结论保留。Root仍556基线、最新668和最终前端3文件尚未接回；接回清单/同版本无需生成集成继续准备，旧运行实例不改。Goal active，基础与最终语义质量均未宣告通过。


2026-10-03 最新独立复核补充：Root新整包的63份真实XML已独立计算为668 tests/0fail/0error/0skip，测试ID多重集合与旧668一致；264个class、JAR entry及1191个完整归档entry逐字核对通过，当前源码/target和正式测试工具补充一致。复核只读取已有文件，未重跑测试或启动服务、API、GPU、模型、OCR或DB；不代表语义、MiniMax预算/生成、运行部署或Linux/H800验收。[独立收据](../../tmp/vetting_foundation_hardening/independent-promotion-smoke-audit-20261003T1520Z/root_full668_build_readonly_receipt.json)。
