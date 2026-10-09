# Vetting 逐阶段探针与瓶颈定位

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


2026-10-02 用户要求在每一步布下探针，定位效果的瓶颈。观察应同时回答两件事：材料在哪一步丢失或改变，以及时间、显存和内存花在哪一步。材料进入模型、引文定位成功、返回合法 JSON 都不能直接计为正确审查。

## 证据链与记录规则

记录沿用本机实验日志，冻结本轮原件、来源角色、解析版本、chunk/Part、模型、代码、参数及提示词。每个探针包含 experimentId 或真实 runId、projectId、topic/query/call、sourceHash、chunkId、原段落/表格位置、时间和输入输出 artifact SHA256。跨阶段通过真实身份连接，不能仅靠文件名或片段数量。

GPU 预计算的 experimentId 与 Java 审查 runId 是不同身份。两者通过全语料 SHA、index signature、明确的 query/role/topK/candidates 和外部封存 seal 连接。Native 与 HTTP recorder 没有共享 request ID；顺序、时间、模型和 token usage 只能形成候选对应关系。Java 完整 messages 与 proxy 原始请求正文的精确匹配另行检查，不杜撰 native body hash。

输入和输出保留原对象、顺序及原文。探针不替换模型 token features、向量、分数、提示词或门禁决定。辅助完整 tokenization 只测截断，绝不作为模型输入。写盘/序列化/额外诊断开销单列；开启探针的耗时不是无探针基准。嵌套阶段不能简单相加。

## 逐阶段内容和当前证据

| 阶段 | 探针内容 | 当前状态与边界 |
| --- | --- | --- |
| 原件和来源 | 原始 bytes SHA、文件/项目/角色、SourceHash、解析状态、原件位置 | 本轮 44 原件身份已绑定；来源角色是 tender、standard、project_fact、package_manifest，与基础变量分类无关 |
| 解析 / OCR | 原物理页、native extraction、OCR 选择判据、图像 DPI/bytes、实际 OCR 请求、lines/bbox/confidence、失败与覆盖 | 本轮复用既有解析/OCR，新增调用为 0，历史 elapsed 为未知；逐页新调用 recorder 在独立源码版本补齐，不能冒称本轮重跑 OCR |
| Block / chunk | 原 paragraph/table row/cell、删除状态、Part UTF16 半开 offset、重叠、内容 SHA、原 block 联集覆盖 | 实际 13,293 chunks 对 44 文档的联集检查没有发现全局缺句；表头/表格关系及删除状态在下游的可见性仍有局限 |
| Embedding | 原输入顺序、内部批次实际顺序、input_ids/attention_mask、完整/保留 tokens、原向量数组、finite/norm/dtype/shape、批次耗时 | 实际 13,293 index 输入；138 行有 512-token 截断；向量机械对账通过，不表示语义召回正确 |
| 索引 | 全语料 fingerprint、签名、模型和依赖身份、upsert 数量、index receipt、耗时 | 一次 fresh GPU index 完成 13,293；receipt 239.17 秒，含观察开销 |
| Dense / BM25 | 精确 query 与 role scope、eligible 原 ID、各路原始 score 和候选 rank | 实际 64 query；BM25 scope 全分数和候选均保留，能区分未进候选与来源不存在 |
| RRF | 原两路候选、融合分数、全排序、截取候选理由 | 保留融合全序与 candidates=50 的边界 |
| Rerank | 实际 query-document pairs、原 features/tokens、原始激活 score、重排全序、topK | 实际 1,840 pairs，36 行截断；本轮重点六个源目标均未发生 index 截断，四个实际 target pairs 均未发生 pair 截断 |
| Java 检索门禁 | 完整当前语料 handshake、index receipt、每次 query/response、原 payload/role/ID 校验、异常原因和耗时 | 新实际案例启用 requireHybridRetrieval；失配/失败中止，不静默改用词项检索；完整语料 fingerprint 的 CPU 成本独立计时 |
| 上下文 | 实际 seeds、补邻段/交叉引用、完整 selected Chunk/Part、组预算、dropped/partial/unresolved、实际提交原文 | 新 run 保存每个 topic 的完整 selection；检索有命中仍需检查是否实际进入提示词 |
| Model / HTTP | 原始 request/response entity bytes、SHA、状态、完整 content/reasoning/usage/finish、真实 sampler/seed、异常前缀 | 新 run 使用串行透明 proxy；availability GET 与生成 POST 分开；不称 TCP packet/framing 捕获 |
| 解码 / schema | 原 gateway content、parseList、typed records、topic schema 校验、原错误和独立耗时 | Java 179 项完整测试通过；实际 schema 成功仍不等于合同判断正确 |
| 证据与适用性 | 每条 record/quote 的真实接受/拒绝原因、source role、范围、引用目标、prefix-break 后未检查引文 | 引文位置匹配与语义成立分开；原 gate 有漏判时探针记录漏判，不替其背书 |
| 结果 / 导出 | 同 run 的 findings、原 JSON/Word/PDF bytes SHA、请求状态与耗时、人工处理字段 | 新 run 终态后保存实际导出；client export wall 包含服务端渲染和传输，渲染内部 CPU 暂无独立 timer；逐页 QA 单独执行 |
| 资源 | 每秒实际采样间隔、整机 GPU/RAM、各 owned process 及 descendants RSS、阶段与异常 | 全部计为有限采样，不能称绝对峰值；桌面及其他程序占用包含在整机值中 |

## 本轮已定位的材料瓶颈

以下只代表冻结的 64-query GPU 检索配方。它与旧 `5355…` 的 CPU timeout 后词项回退不同；不能将新检索 rank 当作旧 run 的 rank，也不能据此声称新模型语义质量通过。

| 原源目标 | 实际 query ordinal / role | 观察到的最早断点 | 实际 token 边界 |
| --- | --- | --- | --- |
| VT-012：A3.1 override 与 premium 条件 | 41 / tender | dense1 → BM25候选3 → RRF1 → rerank1 → returned1；旧模型实际已收到 override 却误判，后续重点是新 selection、模型及 gate | index302；pair321；均完整 |
| VT-013：AQCC whole contract + last Section后六个月 | 5 / tender | dense3 → BM25候选20 → RRF10 → rerank17；超过 topK10，未返回。不能归因于 512 截断 | index271；pair290；均完整 |
| VT-014：Site Staff / Building Services Inspector | 9 / tender | 两定义分别返回第1/2；接下来核新 selection 和模型是否仍错判互斥 | index117/52；pair132/67；均完整 |
| VT-015：Note 1 首块 | 17 / tender | dense41，BM25全 scope competition rank59，RRF全序68；超过融合候选50，未进入 rerank | index315；无实际 pair |
| VT-015：Note 1 续块 | 17 / tender | 已索引且 role eligible；未进 dense50，BM25全 scope competition rank979，未进入 RRF | index90；无实际 pair |

Note 1 原表格 row 有 1,548 UTF16 units。首块为 `[0,1400)`，续块为 `[1200,1548)`；`minimum requirement … PRE.B8.210` 位于原 `[1401,1483)`，在续块中完整保留。当前问题是该 query 对条件和续段的覆盖，不能把切片结束当成原文件缺句。

全部 15,197 行实际 input_ids/attention_mask 独立核对均不超过 512。stderr 的 `529 > 512` 等提示来自额外完整 tokenization；不能据该 warning 声称实际模型越界。六处重点源目标未被截断，不代表其余 138 embedding / 36 rerank 截断没有风险；其他条款仍需按原源范围评价。

## 已执行与下一检查点

GPU 阶段已实际完成：44 文件、13,293 chunks、1 次 index、64 queries、8,731 probe events；producer 正常退出，sampler 已停止。整张 RTX 4060 的有限采样峰为 3,512 MiB。Index encode wall 累计约 132.17 秒、upsert约101.07秒、probe诊断及写入约20.27秒；它们有嵌套，不能相加作净性能基准。

Java 完整 Maven package 179 tests / 20 suites、Python staged cache/probes 27 tests、HTTP proxy 15 fixtures 均通过。业务仓库已复制相同测试源码并核对 Java 99 个源/测试文件与 full-package source 完全相同。原 `5355…` 的不可变报告、JAR、评价和 source evidence 保留。

新整包 run `1661b036-c465-477d-9d96-7d548d9c7ca6` 已在 13:49:10Z 用唯一 POST 启动；19 次比较为计划数，完成数以实际记录为准。本轮启动前已登记 recipe；Bonsai 配方仍为 32K / rk4v4-e8 / graph off / no-thinking / prefill128 / concurrency1 / structured output8192 / transport retry0。生成阶段只回放本次实际 GPU 检索的 sealed results，embedding/reranker 不同时驻留 GPU。随机有效 seed 由 native 保存；不声明与旧 run 同 seed 的单因素比较。

隔离 DB 克隆的初始 descriptor 比较错误、2GB heap OOM 和 native 前两次 DLL loader 启动失败均有原始证明；克隆用全新恢复副本及6GB heap成功，native补齐任务 DLL目录后实际 ready。失败过程均无模型生成。它们作为环境/准备失败单列，不计入模型误报率，不删除失败库、原 stderr 或原 recipe。

以上是启动时快照。实际终态、逐条评价和新增软件版本见下文；固定 34 项 / 13 字段只在独立评估端使用，accuracy/precision/recall 仍为未知。

## 1661 实际终态与独立评价

该 run 在 `2026-10-02T14:01:44.296561Z` 完成 21/21；唯一 POST 后共 19 native starts / 19 dones，与 19 个完整 HTTP entity captures 一一形成串行、时间和 usage 候选链。Java messages 与 HTTP 原始请求精确相同，native 仍无共享 body hash / request ID 证明。应用端 sampler 已停止，共 753 次采样；整机 GPU 有限采样峰 7,734 MiB，RAM 峰 21,864,898,560 B。应用阶段新增 parse/OCR/embedding/rerank 模型调用为 0，前面的实际 GPU 索引/64 queries 单独计量。

| 实际阶段 | 次数 | 累计实测 wall | 边界 |
| --- | ---: | ---: | --- |
| 全语料 GPU fresh index | 1 | 239.17 s | 独立预计算；包含该阶段观察开销 |
| Java 当前语料 fingerprint | 64 | 43.66 s | 每次查询复核全语料身份的 CPU 成本；median 0.698 s |
| Java sealed retrieval HTTP | 64 | 0.558 s | 读取本轮已测检索响应，不能称实时 GPU inference 耗时 |
| Context selection | 16 | 15.69 s | 原上下文组选择；未包含独立事实 pack 构造 |
| AiGateway | 19 | 669.82 s | gateway/client/availability/body读取与解析，内部阶段及native prefill/decode另外保留 |
| Schema gate | 19 | 0.000866 s | 机械 schema/accounting 检查 |
| Record evidence/applicability gates | 47 | 0.009437 s | 位置、角色与当前程序接受条件，不能证明语义成立 |
| Java probe observer | 1,539 events | 1.504 s | 已测序列化、hash/encoding、写盘；含1.248s artifact写入，最终summary self flush及部分caller构造未计 |

上表有嵌套，不能求和作总耗时或净吞吐。应用 started→finished 为 754.05 s，19-call 配方固定32K/rk4，probe开启，不是无探针性能对照。

独立审核检查全部47 raw assessments、87引文以及最终4条发现：28记录机械接受、19拒绝；80引文逐字定位、5失败、2由于原gate提前break而未检查。最终3条规则的保守协调/核对措辞有源支持；唯一模型保险发现把未提交的 Schedule 条目当作未知，并标为 issue。当前原Word source83段617实际已列 `A1, A2, A3, A4, A5, A6 and A8`，因此并非已确认的保险缺陷。本轮模型已正确读出 A3.1 notwithstanding override，但 context 尚缺 Schedule，再由 issue分类及risk门禁漏判放行。这与旧run误读override是不同错误，探针把两者区分开。

实际158个唯一chunks、26份文档进入模型，不代表完整13,293片段均完成语义审查。固定34项为6局部源支持、4部分、1来源反例、23未评；13字段均有局部值/用途支持，条件和采用关系仍未全闭合。定义topic3的BSI/SiteStaff局部记录改善；payment主题仍忽略已提交的14.2(4)删除/Not used，HOMES主题仍把已提交的星号说成缺失。门禁拒绝这些判断不等于模型正确，`[]` 也不能当作该主题完整覆盖。

实际导出 Word14页、原PDF13页共27页已逐页视觉检查，原4导出bytes/SHA与HTTP metadata一致；922段落/20证据label开头核对通过。报告API与JSON有44项 omitted-null / explicit-null序列化差异，实质值差异0。版式可读，保留coverage留白、Word人审栏目跨页及PDF短warning尾行记录。4发现均Open，人审字段全null；本轮没有以非空人审证明替代实际空状态。完整审查质量仍未通过，H800尚未实测。

## 后续解析和导出内部探针：197 项软件证明

逐页PDF native extraction/OCR判据、200DPI实际渲染、原OCR输入byte[]/typed result/lines/bbox/confidence/异常、DOCX block/删除与strike metadata、sourceID→原件SHA→parsed sourceHash、原chunk构造/Part union以及report内部outline/OOXML/PDF generation/JSON serialization/实际returned byte[]探针已补齐。新源码经targeted75、完整Maven package197 tests /23 suites验证，默认关闭；12文件精确复制到业务仓库并核对全部120个manifest源码/测试条目相同。179实际run JAR/原输出不替换。

启用配置分别为 `consense.document.probe-directory`、`consense.vetting.probe-directory`、`consense.vetting.export-probe-directory`，应设置每轮独立绝对目录。旧 `parse(fileName, bytes)` 保持原无探针入口，即使配置document root也不改变drafting调用。vetting显式入口要求真实source ID、原件SHA和fresh job；OCR接口只暴露typed OcrResult，不能冒称捕获provider原始HTTP response。原业务失败时，probe写盘/flush失败保留为suppressed原异常；正常业务遇probe写盘失败则明确报告观察失败。

这是软件fixture/真实in-memory workflow证明，新增实际OCR识别和扫描件精度验证为0。后续真实上传/重解析才产生该阶段实际数据；现有解析复用时历史耗时仍是unknown。导出内部计时版本未用于1661原报告，原client GET wall与27页QA保留自身版本身份。

## 当前账号 Codex 同材料能力对照

用户提出用强API排除模型因素，并明确“就用本账号的codex”。采用独立、无父历史的Codex审查任务先做能力对照：冻结本次19份实际messages/schema/source context，审查者先只读单个call输入，不看Bonsai答案、原件外补材料或gold。初步选call1/2/3/6/11，分别冻结输出后再作源审与原应用gate核对；未回放的调用保持未评。

该路由是当前账号的Codex继承模型/推理设置，harness instructions、采样、context/output限制及tokenizer不与Bonsai参数匹配，不能称模型单因素API benchmark。Codex返回空array只代表该次没有报告具体issue，不能证明理解了所有字段或完整合同无问题。强模型也会错，只有同材料的错误差异和首次失证阶段共同支持瓶颈归因。比赛本地部署和H800复测继续保留。

五份独立输出现已实际冻结。call1/6返回空array，没有重复原Bonsai的payment错误及HOMES符号缺失误报，但不作完整理解证明。call2把AQCC数量差异明确写成有范围未知的协调风险，避免确认互斥义务。call3提出`curtail checking`措辞核对，当前原Word body1441确有该表达。call11不重复Schedule缺A3的结论，提出有条件的专业设计责任/保险协调风险及重复b编号核对；原Word body1847/1852均有b，直接缩进属性不同，具体改成c仍需确认层级。原文包含词语或编号支持核对建议，不自动支持全部修订建议、保险缺陷或无保单结论。

原179 JAR的SemanticReview、ContextBuilder、RetrievalClient和AiGateway直接执行两轮离线机械回放，仅替换HTTP边界为已封存响应、LlmClient为已保存content，0新provider/HTTP/GPU/OCR/DB调用。先原baseline精确复现19完整prompt/context断言、47raw/28机械接受/19拒/1candidate，所有原gate reasons/quoteChecks恒同。再唯一mixed swap只替换5份content，其余14份Bonsai不变，19输入仍精确相同：36raw/22机械接受/14拒/4candidate。四条Codex新raw均被原gate接受为candidate，但未写入应用DB，不是19次新Codex整包审查。

四新candidate均是risk/language，原179 `comparisonRequired=false`，所以对应已标unresolved的chunk不会通过该分支拒绝；这是原门禁的实际边界，没有用Python重新实现或增加guard。它能拒绝非法结构/错引文/部分角色误用，尚不充分区分条件未闭合的advisory与已确认文档缺陷。五份能力对照因此支持模型解读与工作流缺口并存，不能据此宣布纯提示词、agent runtime或某个比赛模型已经胜出。

实际对照结果：`C:/Coding/ConSense/tmp/codex_material_control/final-1661-five-20261002T1446Z/comparison.md`；源审+gate登记evaluation SHA `00d425ea49827e0798ebc135131dcd638fc1f9f84b97d39341438f1a12aa675a`，不可变registry audit event SHA `6db7b47288c5c0a0860190fa586014022befe5013df8fa905e378ec5b4b4c151`。输入准备发生在首份盲审前，统一control registry首次登记晚于1/2/3输出，已明确记录，不能补称pre-inference登记。五个reviewer task outputs不等于五次provider inference requests，后者未暴露，保持unknown。

## 复查入口

- [Java探针实现](../src/main/java/com/consense/service/vetting/VettingReviewProbe.java)、[严格检索与阶段计时](../src/main/java/com/consense/service/vetting/VettingRetrievalClient.java)、[上下文与模型门禁观察](../src/main/java/com/consense/service/vetting/VettingSemanticReview.java)。
- [Embedding/rerank实际输入观察](../tools/vetting_eval/staged/pipeline_probe.py)、[实际GPU执行入口](../tools/vetting_eval/staged/warm_staged_cache.py)、[封存cache消费](../tools/vetting_eval/staged/staged_cache.py)、[HTTP entity记录器](../tools/vetting_eval/openai_body_capture_proxy.py)。
- GPU原始数据：`C:/Coding/ConSense/tmp/staged_retrieval_cache/actual-probed-gpu-20261002T1309097405198Z`。
- 独立审计：`C:/Coding/ConSense/tmp/independent_stage_probe_reviews/actual-probed-gpu-20261002T1309097405198Z/final-20261002T133657671600Z/final_independent_warm_audit.json`。
- 新 run 原始数据：`C:/Coding/ConSense/tmp/bonsai_vetting_application/full-case-probed-20261002T1330Z`。
- [当前目标](vetting-current-goal.md)、[本机调优计划](vetting-local-tuning-plan.md)、[实验日志](vetting-experiment-log.md)。交付目标尚未验收完成；探针对账完成不能代替 vetting质量及 H800 实测验收。


## 2026-10-02 同材料对照结论：模型解读与工作流缺口并存

用户问“瓶颈是模型还是harness有问题”。已执行结果支持两者并存，但位置不同，不能用一个总误报率或主观百分比归因。

| 环节 | 实际证据 | 当前结论 |
| --- | --- | --- |
| 检索及上下文 | AQCC任期说明dense3/RRF10后rerank17，被top10排除；Note1首块RRF68超候选50，含限定的续块未进候选；保险当前原Word Schedule段617列A3，却未提交 | 关键条件未送达，强模型不能凭空补证；这些重点目标不是512-token截断造成 |
| 模型解读/分类 | 相同输入的5份Codex盲对照没有重复Bonsai的Not used/amendment和HOMES星号缺失误报；AQCC数量差异改为范围未知的协调建议 | 同材料下更强审查端有改善，支持当前Bonsai部署配方存在理解/分类瓶颈；空array不证明完整理解 |
| 问题接受门禁 | 4条新risk/language候选经原179 Java gate接受；实际comparisonRequired=false，不因quotedUnresolved拒绝。新保险建议仍有专业设计/独立保单/采用关系未知 | 位置匹配与结构合法不足以确认合同缺陷，需分开已证实问题、协调建议、信息不足及覆盖缺口 |
| 核心编排/传输/解码 | 原179 JAR离线复现全部19提示词和selection、47raw/28接受/19拒/1candidate；原gate reasons/quoteChecks恒同，原HTTP messages与Java提交相同 | 本轮结果可复现；未发现提示词传输或JSON解析损坏足以解释质量问题，不对其他案例作绝对无bug证明 |
| 速度 | 实际应用754.05s，19次AiGateway累计669.82s；GPU索引239.17s另行预计算 | 当前应用时间主要在生成链路；gateway含客户端处理，嵌套阶段不相加，sealed检索耗时不当作实时GPU inference |

这是能力对照及具体失证阶段定位，不是严格模型单因素实验：Codex继承当前账号模型/推理设置，其harness、采样、tokenizer与context/output预算不与Bonsai匹配；精确provider模型ID和provider请求总数未暴露。仅5份独立审查输出，其他14份没有做Codex审查。mixed swap产生4个候选只说明原门禁接受，没有新增整包Codex结果、应用DB发现或质量验收。accuracy/precision/recall保持未知，H800尚未实测。

后续修复顺序：先按完整条款/表格行/限定条件及明确交叉引用补检索，记录未覆盖目标；再把条件未闭合的风险建议与已确认缺陷分开，补证失败保留unknown；最后用同冻结材料和整包案例复测提示词/强模型。必要时加入调用受限、过程可回放的补检索runtime。现有证据不支持直接通过更换框架名称解决问题，也不支持认为H800强模型会自动修复证据遗漏与接受规则。

新service lifecycle源码探针已通过210项软件测试，独立复查另发现REQUIRED事务参与外层事务时返回不等于提交，正在做probe-only修正及真实H2 rollback fixture；不改变原业务事务、DAO或人审字段。210软件证明及归档辅助失败保留，新版本完成前不称该源码已推广或用于1661实际运行。解析/OCR重跑、H800质量及下一轮整包审查仍未完成。

对照证据：

- [五份实际输出与源审](../../tmp/codex_material_control/final-1661-five-20261002T1446Z/comparison.md)，evaluation SHA `00d425ea49827e0798ebc135131dcd638fc1f9f84b97d39341438f1a12aa675a`。
- [原179门禁回放最终证明](../../tmp/offline_179_application_replays/final-proof-20261002T144357123155Z/summary.json)，SHA `1723bf8d25da40bcb072da9f067e0ef32da50d686c4c448a4f5c2fb10c4d9b8c`；5目标gate明细SHA `283773bcb0bd7e870fe168a147fcc51839ca6086acaf4ec375007f9714574686`。
- [逐阶段观察与完整边界](vetting-pipeline-observability.md)。本轮观察完成不作为vetting交付目标已验收的证明。


## 2026-10-02 lifecycle探针收尾：211项软件证明与源码接回

规则源构造→原RuleEngine输出→原semantic candidates→severity排序→source规范化→fingerprint去重/risk cap→coverage→保存前后原记录及人审字段→事务返回→失败/重启恢复的探针已补齐。targeted实际33 tests/4 suites，完整offline Maven package实际211 tests/25 suites，failure/error/skip均0。经root和独立只读复查，5文件精确复制到仓库，全部123个manifest源/测试条目与full-package源码相同。默认off，配置`consense.vetting.service-probe-directory`使用每轮独立绝对目录；新service-UUID操作绑定真实run/project，与semantic/parse/export目录隔离。

原排序、first-wins去重、risk++计数、业务REQUIRED事务、DAO次数和人审更新逻辑不变。probe只记录原业务状态：保存/flush后的snapshot属于pending；事务返回时若当前线程仍参与外层事务，`committed=null`及`participatingOuterTransaction=true`，外层结果unknown；无外层时才记录已正常完成自己的事务。真实in-memory H2 outer recovery fixture显示内部暂变FAILED、caller rollback后仍RUNNING，探针未伪报最终提交。Observer写盘/序列化失败隔离于业务成功，原业务异常cause/suppressed保留；观察失败不能当作完整证据。其它旧semantic/corpus/export探针失败策略没有改变。

此前210项软件测试通过后，辅助freeze先有Windows206长路径失败，再有普通Python rglob只见3个无效目录1-byte fixture、漏列实际lifecycle事件的归档范围问题。原partial/v210与完整extended-path v210a归档均保留；最终fresh版本采用extended-length路径读取及平铺文件、源→copy bytes/SHA映射。辅助归档缺口不当作模型/业务失败，也不回改210原manifest。新事务声明修正由fresh targeted/full-package及独立源审证明。

这次是软件fixture/唯一内存H2的实际流程证明，未产生新的模型/OCR识别、真实检索API、原应用DB或GPU调用。运行中的1661实际案例仍是原179 JAR，原输出/报告/独审及27页QA不替换；新源码未被称为重新部署或真实整包质量改善。下一轮真实运行才能获得新service/解析/OCR/导出内部阶段的实际耗时和结果。完整vetting质量及H800验收仍未通过。

证明：`C:/Coding/ConSense/tmp/staged_retrieval_cache/service-lifecycle-tx-20261002T150517046509Z/frozen_proof/v211/manifest.json`，manifest SHA `bd8ef34a4f356486ea74290e40ec121bc2533c6b54e59e6885f9a4bb49b95e42`；精确源码推广：`C:/Coding/ConSense/tmp/probe-source-promotion-lifecycle-20261002T151508272323Z/promotion_manifest.json`，SHA `65ceff36f5e8f634d4d7fb8195ae76d218e89848b6d097aecaad6b1f865d68fb`。当前实际模型/检索/门禁诊断继续见[逐阶段探针文档](vetting-pipeline-observability.md)。


## 2026-10-02 OCR与检索精度抽查：已有真实小样，当前保存结果仍有损伤

用户进一步询问“向量数据库和ocr的精度有抽查吗”。此前确有真实OCR与检索小样；最新1661应用复用解析、新增OCR0只限定该run，不能解释成项目没有做过OCR人工核对。

OCR已执行固定FT物理2/7、AA物理1/2、APL物理3共五页的DPI200/250/300三个arm，15次真实CPU识别；五原图与15overlay人工看过，每arm原needle21/22、14区域31literal小样30/31。另一组固定DPI200、max-side2000/3000两个arm共10次真实CPU识别，needle均21/22，局部literal30/31→29/31。全部25份历史raw OCR JSON及冻结sidecar/SHA已独立复核，未修改原匹配规则。提高DPI没有改变该组det实际1408×1984输入；max-side3000确实改变det输入，但FT期限文字退化且编号有合并/漏检取舍。维持已测200/2000基线不等于识别精度合格。

当前44源inventory按真实project过滤实算10源ocrUsed=true、147个已记录OCR页；全部PARSED/complete只是保存的解析覆盖。新增两页当前原件的纯CPU渲染和人工复查显示：FT source76物理2脚注1首行在已保存OCR中为严重乱码，pdf-page:2:ocr-line:55/confidence0.56112；APL source99物理3原罗马编号清楚且姓名槽位空，已保存结果却合成BEEE/日目日/3333（line11/14/19）。max-side3000还把期限起点文字识别为frdom/Tenderer。旧pilot局部30/31不代表当前147页质量；低分/高分、返回文本、complete都不能代替原页核对。整体accuracy、整页CER、表格关系/删除线精度仍unknown/null，未准备完整逐字转写。

向量库的ID/原文字段/role/向量顺序、入库数和查询身份已机械核对；检索精度要评价embedding→候选融合→rerank→实际context。历史2,739 chunks/8 queries/11 contains-needle的512/1024对照均11/11进top10；只证明指定片段被召回，不作总体Recall@K/Precision@K或条件闭合证明。最新全44源13,293 chunks、64 queries的源审已确认AQCC任期rerank17被top10排除，Note1首块RRF68被候选50排除、关键续段未进候选。未被截断的源目标仍可漏召回，条件和交叉引用要另外标注。

下一轮精度工作按原件页/条款范围分层：OCR逐字转写并单列条款号、金额、天数、否定/删除/OR、空槽位与表格列；检索以独立相关条款及必要限定为标签，分别计算candidate/topK/最终context覆盖。人工labels只用于评价，不能回填本轮请求、原gold或预先指定应有发现。当前两页抽查支持明确损伤定位，不支持总体百分比。

完整独立证据：[来源与OCR抽查审计](../../tmp/independent_ocr_spot_checks/source-two-page-20261002T151751193921Z/independent_ocr_precision_spot_audit.json)，SHA `253f8046cddf2513aa210a38cdc33d16be686e5b5d51460e4af9f38709ca0572`；[说明及两原页图入口](../../tmp/independent_ocr_spot_checks/source-two-page-20261002T151751193921Z/README.md)。新增操作仅文件读取、两页CPU渲染及独立评价，新增OCR/LLM/API/DB/索引均0。保存的原件、旧OCR及既有证明SHA保持不变。

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

新证据链已经实际执行：同 SHA 原 DOCX → 新 DocumentParser 输出/provenance → 当前 VettingCorpus 的 1,859 chunks/11,342 个 Part 范围 → 全新 CPU embedding/Qdrant index → 两次 BM25/dense/RRF/rerank → 当前生产 context → 两次直连 MiniMax-M3 → 原 AiGateway 解码及明确的机械门禁投影。246 完整/35 针对软件测试通过的 7 个文件已精确接回仓库，旧应用、索引、DB、历史实验和前端实例没有替换。

独立源审恢复 SCC `body/908/paragraph` 的 `(e)` 与 PRE `body/2326/table` 的 22 勾选；1,859 chunk IDs 唯一，11,342 Part UTF16 切片和全部 chunk 的 Part join 逐字核对。新索引 1,859 行、1,024 维均已独立检查 finite/normalized；1,084 probe artifacts、1,067 events 身份核对完成。此检查证明存储和来源连接，不是检索语义 precision/recall。

| 目标或阶段 | 实际最早断点/恢复点 | 仍需保留的判断 |
| --- | --- | --- |
| SCC14.302 `(e)` | 新原生编号 → source block/provenance → embedding301 tokens、rerank317 tokens均无截断；dense12/RRF15/rerank16，未进top10，但生产 context 通过邻段自然补入付款 call1 | target 被补入且 user 原文精确；不代表所有付款条件或完整 clause 已齐 |
| HOMES 22 个勾选 | 新 symbols/cells → embedding448、rerank463 tokens均无截断；rerank1/top10rank1，进入 call2 的 explicit-reference partial window | `qualifiersComplete=unknown`；fullySubmitted 只说明该选组已提交，不等于合同范围完整 |
| HOMES 采用/传输条件 | call2 共9 chunks；完整源存在 SCC22.304(6)，但它未进入本次 user 窗口 | 后续修条件伴随召回和 absence/scope-hint 规则；未提交不能推断不存在 |
| API/解码 | 两POST HTTP200/stop、body完整；call1正式解码为合法[]；call2原`suggestion`缺冒号，真实 parser失败 | call2有效records=null，schema及record证据 gate 未到达；可读三段不作有效记录 |

Gate helper 真实执行 AiGateway 的 structured-list/parseList/extractJson/unwrapList 解码，并精确断言 saved API turns/schema/content；VettingOutputSchema 对 submitted IDs 作真实 schema 绑定。record shape、prefix-break、tender/distinct/unresolved 等编排是逐行对照源码的机械投影，helper 可调用真实 evidence/私有 comparison-basis 方法；实际本轮 call1 无 records、call2 parse failed，因此没有记录级引文或 basis 校验。它不是完整 SemanticReview/application run，也不增加 repair 或 API retry。`savedApiResultCallsBound=2` 是已绑定保存记录，gate 自身新增网络/模型/DB/索引调用为0。

原件及材料 [源审](../../tmp/docx_fidelity/independent-source-audit-20261002T1710Z/source_parse_chunk_audit.json)、[检索/context 审计](../../tmp/docx_fidelity/independent-source-audit-20261002T1710Z/retrieval_stage_audit.json)、[实际新材料](../../tmp/docx_fidelity/actual-two-source-20261002T1658Z/material/material_manifest.json)、[API 结果](../../tmp/docx_fidelity/actual-two-source-20261002T1658Z/minimax_actual/run_result.json)、[gate 结果](../../tmp/docx_fidelity/actual-two-source-20261002T1658Z/gate_actual/gate_result.json) 分开保存。统一模型独审/比较见 [本轮 DOCX 保真结果](vetting-docx-fidelity-20261003.md)。本轮 source95 仍 PARTIAL，unknown 字体、编号与 AlternateContent 可见；共享 parser 可能改变 drafting 模板状态计数，DraftingService 未修改。没有新 OCR、Word 渲染、44-source 应用 run、UI 或 H800 验证。


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


## 2026-10-03 新基础链路 Goal：停止新增审查模型实验

用户最新要求优先修数据质量、切块、嵌入、重排序和harness。实际App create_goal已返回active，未设置token预算；最新目标与完整发现见 [基础链路汇总](vetting-foundation-findings-20261003.md)。历史模型/提示词计划保留为当时记录，当前不继续发送新的审查LLM请求。

最新源版本419 tests/35 suites通过并接回，尚未替换旧服务/数据库/索引。已修编号、勾选、附录边界、严格输出、typed failures和实际预算；第一待修复为tender原生cells在Corpus角色条件处丢失。embedding/rerank截断、候选cap/topK排除必要条件、OCR原页损伤及机械依据检查与语义结论的区别按独立层级验收。每轮继续记录实际输入/参数/源身份、失败和前后比较；独立标注只作评价，未标注范围与整体质量仍unknown。

新产物目录 `tmp/vetting_foundation_hardening`；应用Goal创建收据 `tmp/vetting_foundation_hardening/goal_created-20261003.json`。本轮scope记录没有模型、OCR、索引或检索调用。


## 基础Goal第一阶段已完成：原生cells及报告定位状态

合并后的430 tests/36 suites全部通过，原419测试身份保留；5份修改源码已精确接回，141份源码及224个生产class与冻结构建对应。真实保存二来源2445个完整原生行Part从0恢复至2445；HOMES17/flushing16焦点行cells逐字恢复，源正文/hash/owner/角色/UTF16不变，1895个chunk IDs全部更新。原生结构歧义保留unknown，普通首行不冒充表头。新索引和后续材料投影仍需下一阶段验证。

三语Word/PDF报告正文改为证据定位，说明定位不确认结论；保留工作状态及人工记录，未作布局/语义验收。44原件SHA重新实算通过，OCR147页仍只是历史处理范围。已确认本任务Bonsai23 start/23 done后停止PID8708并保留恢复配方，观测GPU7613→1147MiB。没有新增审查LLM、原件解析、OCR、embedding/index/query调用；软件回归使用隔离fixtures。Java/UI/旧DB/index未替换。App基础Goal保持active，下一阶段为selected-only材料结构、token预算/续段、索引失效及分层召回覆盖，全部发现与验收见 [基础链路汇总](vetting-foundation-findings-20261003.md)。


## 基础Goal第二阶段：结构材料、续段、OCR质量状态与固定库预建

合并461 tests/39 suites全部通过，原430测试身份保留+31；146份源码、228生产class与冻结JAR核对，14份tested源码已接回。selected-only结构材料已保留33原生焦点行，未入选header剥离；原source正文与UTF16保持。真实document72的Note1头段诊断补尾部最低要求范围，不算actualqueryrecall。OCR处理成功仍为needs_review/PARTIAL；FT2/APL3两页×两配方四次真实CPU识别，det tensor相同、raw结果相同，编号错误未改善。固定六源按显式原件SHA登记，GCC/SL旧OCR页派生待核状态；不会靠模板名改业务角色。用户确认NTT/SCT/SCC模板+GCT/GCC/SL固定，其他现场材料变化，并允许提前解析切片嵌入形成超算离线包。新增源增删改/混版/项目隔离与未知长段/表格验收，缓存身份绑定算法、模型/tokenizer、dtype和原文，parentIDs重绑定。

当前没有新审查LLM、embedding/rerank/index/query调用，Java/UI/旧DB/index尚未替换。App Goal保持active，接着将质量声明传到chunk/材料，冻结token窗口和实际向量配方，做fresh-client持久核对和层级召回，构建固定六源资源包。细节与不可变SHA见 [基础链路汇总](vetting-foundation-findings-20261003.md)。


## 基础Goal第三阶段：质量声明、人工revision与实际GPU窗口

487 tests/41 suites全通过，9份精确源码接回，149全src/230classes与冻结JAR对应；原461测试源码逐字保持。Chunk/材料保留OCR needs_review及scopeunknown，质量hash参与身份。44源13464父chunks已纯文件冻结，文字准确度全仍unverified。同引文内容变化跨轮生成新Open，原人工记录/history保留；37相关回归与33独立peer字段检查通过。

两源真实CUDA float32/TF32off主index1895parents→1941windows，69.492秒，4query为两原通用查询×50/10及100/20。主embedding486、query embedding4、rerank78 actualforward start/complete全部对应；额外小增删改/项目隔离fixture不混主分母。producer关闭后独立fresh client复制state并1constructor/8scroll/1close，1941完整payload/source spans/原encodedfloat32逐元素exact。这是运输和持久化证明，尚未验收整44的必要条件召回或最终合同语义。

CJK lexical旧中文=[]修复77guards通过，64英文queries tokens逐字同；固定6向量预建及整44将使用新算法身份。固定6 whitelist按6原件/sourceHash/role显式绑定，0新审查LLM/HTTP/AppDB/download；Java/UI/旧索引未替换。固定向量cache/整包实际ranking+生产context回放/跨目录离线资源包仍执行中，H800/Linux运行未知。不可变证据见 [基础链路第三阶段](vetting-foundation-findings-20261003.md)。


## 基础Goal第四阶段：固定六源资源包与全44真实回放

固定六源真实10705父chunks→10724窗口已预建；全44真实13464父chunks→13602窗口，10724固定缓存命中、2878新编码，一次主index、128实际query，两配方50/10与100/20、原64请求保持。独立新客户端逐元素核对全部缓存/新编码向量及payload，mismatch0。2281实际tokenizer-input探针丢失token为0；生产Budget/SourceMaterial纯文件回放128保存query→32材料组。

固定ZIP 5215966017 bytes已制作，第二随机根解压验证21575清单文件、fresh文档/父ID及10724保存向量全部命中、byte mismatch0。Linux wheelhouse/H800尚未验证，固定GCC/SL13个OCR页仍needs_review。当前全44旧预算材料在原Q2/Q6仍缺独立标注条件；两源预算反例已确认扩展补原缺口却被新增引用一票否决，正在通用修复。全44wall/index发生大时间gap，不能作为纯速度比较。health旧v2标签/default workspace两个metadata问题另行修复；当前Java/UI未重启，0新审查LLM/HTTP/AppDB，Goal active、foundationAccepted未知。不可变证据见[第四阶段](vetting-foundation-findings-20261003.md)。


## 基础Goal第五阶段：固定缓存身份、扩展判定与单因素材料回放

Root Java已接回500 tests/42 suites版本，保留原487测试，扩展按首轮实际缺口判断改善。metadata-v2 core/launcher经171隔离guard接回；新六源10724窗口真实重编码，新44源13464parents→13602windows、fixedcache10724/fresh2878、一主index/64原primary queries完成。fresh客户端全float32及源payload mismatch0，新旧六源10724向量exact，64primary各层排名完整payload一致。

三个独立Java实验保持未接回：实体限定504 tests、单调扩展515、incoming v2 517。各用相同真实保存排名做两源/44源回放，独立按原件block/UTF16审计；单调扩展令两源Q6条件完整送达，44源相关Q2/Q6范围仍缺，不能以missing总数下降代替覆盖。incoming构造后可变源反例已复现并拒绝修复，初始v1失败不删除。Root producer工厂最初因STRATEGY内联消费者class差异拒绝，随后独验源码相同且仅常量池UTF8版本替换，再重新冻结helper并回放；没有放宽完整JAR或来源校验。

当前三修订及incoming预算保护正在隔离合并，新版固定ZIP另建，原v3保留。PRE.B8.210大引用family是atomic拒绝且未消耗正文预算，原贪心partial假设已被只读审计否证，下一步以实际ranked-core/邻域/closure占用判断预算分配。0新审查LLM/HTTP/AppDB/OCR；旧实例未重启，H800/Linux未验收，Goal active、foundationAccepted未知。证据入口见[第五阶段](vetting-foundation-findings-20261003.md)。


## 固定六源离线交付 v4 完成

已生成5217042748-byte ZIP v4（SHA cb5f1f57d052c3d001e436dc7bb0502d5a027fb27860054613f5bf5be2abb0b5），包含最终metadata-v2与真实重编码fixed6缓存。第二随机根21576清单文件全部SHA一致，10705新父ID与旧不交、10724保存窗口缓存全部命中，原实际float32 NPY逐字节差异0；模型/算法内容身份不依赖目录，两绝对根一致。Root独立stream核ZIP SHA通过，v3原件/证明保留。

打包0新parser/OCR/tokenizer/neural/index/query/HTTP/AppDB，尚未上传平台或执行Linux/H800。Linux wheelhouse待补、固定13个OCR页needs_review。546-test三修订及incoming Budget仅隔离冻结，原失败保留，有限peer与实际保存材料评价仍在推进；Root当前500、Goal active。包与操作说明见[第五阶段固定离线交付](vetting-foundation-findings-20261003.md)。


## 546合并版独立反证与预算损失

546/46-suite源码及234-class JAR冻结，三套真实保存排名4+32+16材料组全部回放并独立审计；两源Q6两配方SCC6/8范围送达，44源Q2/Q6四必要范围仍缺。独立泛化反例证实：初始incoming-derived ranked tail原core未准入，扩窗却晋升outgoing origin并追第二跳。546不接回，修另版；此前有限静审无发现不等于验收。Q2 weak邻域填充先于observed qualifier造成可归因预算排除，大literal family 60801chars atomic拒绝/0 of49未消耗正文预算；Q6 preweak已近上限，优先级并非全解。

独立evaluator两个并行进程因Windows UTC时间字符串相同造成stage mkdir拒绝，失败单独保留；补UUID并在全新目录重做metadata-primary文件审计通过，未改首个已成功输出。本追加0新审查LLM/neural/query/index/HTTP/AppDB，Goal active，Root500与固定ZIPv4证据保持。

2026-10-03 固定与变化资源边界追加：用户确认固定六类原件可赛前解析/切片/嵌入带入超算；v4包搬移读回完成，现场变化材料须自身来源/项目重建、当次rerank。549隔离冻结修复派生origin，未promote；真实Q2 Note1预算阻塞证据已入基础发现文档。本追加0新review LLM/parser/OCR/tokenizer/neural/index/query/HTTP/AppDB；Goal active。

2026-10-03 最新检查点：Root556/49 suites实际package通过，234classes与隔离冻结/JAR逐字一致，12文件接回但旧实例未deploy；未知变化材料原件parse/tokenizer预检完成并发现长cell拆Part后关系unknown；574额外包原型仍隔离待saved-rank范围评价。0新真实审查LLM，Goal active，完整基础验收未通过。

2026-10-03 来源包独立范围检查：隔离574 actual32组/151包/1980同包请求，原Root556 global全文及顺序不变；Note1和SCC两范围完整入额外包，AQCC条件关系未建请求继续修。长cell/strict wire补丁仍隔离中，0新审查LLM、未部署、Goal active。入口：C:\Coding\ConSense\tmp\vetting_foundation_hardening\packet-native-progress-20261003T112206554150Z-c337c82f904c4e138a45210884a9d15b

2026-10-03 条件包实际独评：582隔离回放32/156包，四项必要原文范围两profile单包完整；原Root556 global不变、2067extra whole requests。仍37oversized/1054cap/94unknown，输入harness/长cell/strictwire未接回。0新审查LLM，Goal active。证据：C:\Coding\ConSense\tmp\vetting_foundation_hardening\condition-native-progress-20261003T113404450824Z-0b878877afe5429ab8d31de8e999f509

2026-10-03 最新628检查点：隔离628/57全0、重复JSON与全局over_budget反例已修；Root556未接回/未部署。三活动collection65向量/payload独立一致；44源native-v3及188完整Input产出，真实新输入token预算和独立范围审核未完成；固定v5迁移未完成，OCR13页needs_review，0新审查LLM，Goal active。checkpoint入口：C:\Coding\ConSense\tmp\vetting_foundation_hardening\progress-status-628-20261003T124949807573Z-d13198ec372548079c84ad7263326916

2026-10-03 完整预算检查点：188实际原生计数30 fit/158 over，真实harness空替身158over零派发、32组partial/not_submitted；紧凑prototype135 fit/53 over及4generic反例均保存，完整token-aware修复继续。统一640/60全0隔离未接回；固定六源native-v3 v5搬移/rebind/cache通过，44源新index继续。0新审查LLM，Goal active。入口：C:\Coding\ConSense\tmp\vetting_foundation_hardening\complete-budget-progress-20261003T132054922569Z-c08a33f92f014c6ba5cfbf714112b648


2026-10-03 最新用户Goal补充：**LLM验证先使用已提供的MiniMax API，忽略本地算例部署。** 当前先完成输入及证据门禁；后续API验证按实际接口、模型、完整请求、输出预留及返回计数绑定配置，不把Bonsai/NInfer离线预算当MiniMax容量证明。OCR、embedding、reranker本机基础验证继续；不继续本地审查模型部署或扩大选型/提示词实验。下面历史本机部署与暂停模型段落保留为当时快照，以此补充及最新App Goal为执行依据。

第五阶段进行中：Root源码仍为556 tests/49 suites/234 classes，本轮逐文件重核与原冻结字节相同，旧服务未替换。44源native-v3新index已实际完成：13464 parents/13602窗口，固定10724严格cache hit，变量2878真实GPU embedding、758/758 forward；真实检索/rerank/审查LLM均0。独立新客户端54 scroll逐点核13602 payload/sourceSpans/float32向量全部一致，生产4状态文件前后不变；原生3661 rows/13983 cells和55 slices/115文本范围另有准确侧车核对。无损wire/完整token-aware排包已形成668 tests/63 suites全0的隔离修复；659独审发现literal数字精度/scale失真，668局部decoder已补9case，独审及188新完整Input/API预算验证继续，不声称已通过真实MiniMax容量或覆盖。前端实际只读38GET验证32主题9未提交/23部分、unknown及逐item完整包身份；另修顶部任务COMPLETED文案，三语言明确任务执行结束不等于完整审查，类型检查/Vite及实际浏览器通过，尚未接回或部署。固定六源v5搬移/cache验收保留，OCR13页needs_review。按用户最新Goal，后续LLM验证优先MiniMax API，不以本地审查模型部署/容量为前置；本轮0新审查LLM，Goal active，基础与最终语义质量均未宣告验收。
### 最新检查点：真实44源索引读回、668修复与页面执行语义

[全44新索引终盘](../../tmp/vetting_foundation_hardening/full44-native-v3-index-20261003T132419Z/final_receipt.json)：实际1 index、0 query/LLM，13464 parents/13602窗口，10724固定命中、2878新变量GPU编码、758 embedding forward完成，wall272.422s。新完整token features逐数组核对，max512/lost0；当前encoded到upsert逐元素一致。与旧实际向量按显式window映射比较，固定全部byteexact、变量1291/2878 byteexact，其余maxabs6.01261854e-6、mincos0.9999999982448565；这是数值回归，不能代替检索准确率或语义效果。

[独立fresh-client全部13602点读回](../../tmp/vetting_foundation_hardening/native-v3-fresh-client-audit-20261003T1357Z/actual_v1/audit_receipt.json)：关闭状态副本新client/54scroll/close，全部payload/sourceSpans/实际upsert float32一致，4生产状态文件前后不变。13464 canonical→normalized parents整树相等，无范围缺口。[原生结构侧车](../../tmp/vetting_foundation_hardening/native-v3-fresh-client-audit-20261003T1357Z/native_structure_sidecar.json)准确核3661完整Part.table行/13983cells、55Part.tableSlice/115selected文本，完整行重组和slice UTF16范围/hash一致。旧主receipt nativeRows0是Part.cells统计字段错误，原receipt保留；不是结构丢失。

[668隔离后端](../../tmp/vetting_foundation_hardening/service-lossless-literal-numbers-20261003T1415Z/final_receipt.json)：668 tests/63 suites全0、264 Java8 classes与JAR/resources一致。659为完整Input观察、可逆wire和whole-request token排包版本；独审6次实际wire roundtrip发现5种literal subtree的BigDecimal精度/scale损失，失败保留。668只改局部decoder保留JSON数字字面量及9个回归（修前5fail），不改全局JsonUtils/正文/ranks/SourceMaterial/其他provider配置。尚无新实际Input计数/LLM；独审、188单因素新Input导出、所选MiniMax API实际预算与排包回放继续。659/668均不冒称已接回或部署。

[前端实际浏览器检查](../../tmp/vetting_foundation_hardening/ui-render-validation-20261003T135424515645Z-b75bed2bf2894773bfa74b94a6a93e55/final_receipt.json)：冻结saved628的32覆盖对象显示9not_submitted/23partial、31全局超预算，旧无aggregate和预算unknown明确显示未知/未提交；两条人工UI展示引文逐项保留不同完整Input包ID与来源快照，真实formalCandidates仍0。38只读GET、0forward/LLM/AppDB。实测另发现顶部“审查完成32/32”误导；新两文件隔离补丁改为“任务执行结束/执行进度”及覆盖限制，简繁英实际渲染、vue-tsc/Vite全部通过。只证明UI显示，不是有效模型候选、原件/报告下载、人工写入或最终语义审查证明。selector的summary role反例、第一次繁体替换失败和owned会话Ctrl-C退出均保留；server finally close、端口关闭，旧实例不改。


### 新检查点：MiniMax 真实计数访问、668 独审与 188 无损输入

MiniMax-M3 的 Responses 输入估算已实际调用一次：HTTP200、object=response.input_tokens、input_tokens193、0生成、0原件/gold读取；直连TLS、无proxy/redirect/retry，冻结请求字节与实际发送保存体相同，凭据仅DPAPI瞬时注入不入工件。此例是benign协议访问检查，不证明188包预算、Chat Completions模板、隐藏tokenizer/template、1M容量或语义质量。[实际收据](../../tmp/vetting_foundation_hardening/minimax-responses-counter-smoke-20261003T144941357263Z-51b74cb5c22d4b9a94608c19f1d0b567/actual_attempt/result.json)。6项可复现fake-HTTP协议守卫通过，包括重复派发、generation路径/输出、duplicate/bool计数与凭据回显拒绝。

[官方Responses输入估算](https://platform.minimax.cn/docs/api-reference/responses-input-tokens)使用专用请求协议。当前Chat Completions不能直接拿这一估算冒充自身完整模板计数。[只读设计](../../tmp/vetting_foundation_hardening/minimax-wire-budget-independent-design-20261003T1442128797160Z/README.md)已冻结；default-off vetting专用Responses适配开始隔离实现，counter与未来dispatch共用PreparedRequest/input投影，显式provider_reported_estimate和context policy，unknown对global/extras均阻断，Drafting默认client保持。还未通过该新适配验收。

[668与页面独审](../../tmp/vetting_foundation_hardening/ui-and-wire668-independent-review-20261003T1423327131936Z/final_freeze_manifest.json)已完成：同一原6反例全部通过，旧5失败清除；UI只读fixture/lifecycle修复证据逐文件核对。[188实际输入交付](../../tmp/vetting_foundation_hardening/lossless-input-tooling-20261003T1320Z/delivery_receipt.json)全部source有序JSON逐包还原，32global/156extra，完整Input19844589→12520259 bytes（减少36.91%）；sourceSnapshot、system/schema/provider/Selection不变，21工具守卫通过，0实际外部counter。文件字节减少不是token或效果证明。

新索引32组×4角色=128真实retrieve的plan与16工具守卫准备就绪，当前这轮actual retrieve仍0；GPU被本Goal之外运行程序占用时不启动并行NN、不终止用户进程。已有独立13602点读回结论保留。Root仍556基线、最新668和最终前端3文件尚未接回；接回清单/同版本无需生成集成继续准备，旧运行实例不改。Goal active，基础与最终语义质量均未宣告通过。


2026-10-03 最新独立复核补充：Root新整包的63份真实XML已独立计算为668 tests/0fail/0error/0skip，测试ID多重集合与旧668一致；264个class、JAR entry及1191个完整归档entry逐字核对通过，当前源码/target和正式测试工具补充一致。复核只读取已有文件，未重跑测试或启动服务、API、GPU、模型、OCR或DB；不代表语义、MiniMax预算/生成、运行部署或Linux/H800验收。[独立收据](../../tmp/vetting_foundation_hardening/independent-promotion-smoke-audit-20261003T1520Z/root_full668_build_readonly_receipt.json)。
