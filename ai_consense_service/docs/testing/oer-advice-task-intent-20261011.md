# OER Advisory：原文任务与项目适用判断的提示对照

#### 2026-10-11 接续核验：有界来源发布、新M3问答及逐项评分完成；正式质量微调尚未开始

本节更新实际状态并覆盖 UTC `2026-10-11T00:23:46Z` 的旧观察。当前仍只有 OER 一个 active goal，Drafting 冻结。实际来源处理、运行部署、训练兼容与质量收益分别报告；本节不追认尚未完成的来源登记或业务验收。

Q1.3 saved actual 的不同作者核验已通过，终态 `.local/state/oer-q13-actual-normal-independent-20261011/review-01/terminal-receipt.json` SHA `defc4f16ff752b0cc2b96d7d85c23a5e5e5d2315940aa34d931c8f68ab89afee`。核验68实际框映射、21采用／14未采用区域、19闭合关系及4单元；983 distinct 可达 CAS descriptor 的349599150B完整流式哈希相符。全文／法律质量、human确认及vendor内部几何仍未接受。

**准确运行范围的 fresh 来源作业已实际 exit0。** 原 adoption 错绑规则文档 SHA，新 adoption 只改为正常 `CompetitionScopePolicy` manifest SHA `bb1512de4b16bfa2c10f03a8197b0c798c105e3359fc649d35391f6fcb66e3d6`，复用完整已有 native／dual／CAS，正常 produce＋cold validation完成，290.953秒为整份来源处理耗时，0新OCR／GPU／模型／API。session91747已结束，不重启。小终态 `.local/state/oer-q13-public-family-seam-20261011/prepare-01/execution/run-01/terminal-receipt.json`，3364B／SHA `a8c4ef533547cf7bc8834edfda40bb629747c214e20e704398b08fe858343c58`；fresh internal543247047B／SHA `e95a2fa97d7eb2b07f465b283ba7c1393b642939b4d1b99635124533f8a59b0e` 是离线证据，不是RAG或LLM输入。不同作者准确输入／完整990 CAS复制及新internal文件身份独审PASS，收据 `.local/state/oer-q13-fresh-active-policy-independent-20261011/review-01/terminal-receipt.json` SHA `1a393ce6599c227cc18e149a746d25a8fd18a1eff4e8c31c31ebfb09fd2e90eb`，独审不重复大JSON解析或cold validation。正常16MiB有界publisher现已exit0，session33864已完成，不重启；public289488B／SHA `8b1402abd61d8c1e70a525c53994793ee51e008af18c6701fc99a2e8ee1f0c55`，4units／21parts／19relations、完整typed引用及unknown保持，正常发布与saved-public冷验均通过。final-seal SHA `da2b07088db91041bb4e0deba09b7c9252702c49ada21a2cce8344f8953f479f`。same-original family仍在隔离候选，尚未live登记／DB／索引；新候选包含child root实际字节依赖与warm纯重构复用，不能直接追加冲突revision或用4unit覆盖GCC50＋SCC22。

**Advice观测与展示组合后端已正常受控部署，新M3实际问答已完成。** live JAR为79273839B／SHA `ef2f8c9e780d17fe0ed3b9f9cc684728788ee6e4964e626689ce57aa4dd22bf5`；实际部署终态 `.local/state/oer-advice-java-normal-deploy-20261011/execute-01/terminal-receipt.json` SHA `7bcd41e424fe64e65b4f9500fef089460dfa979bcd7629e649c9f222ade50b4c`。新backend PID47356，751静态保护项、四Drafting API完整字节、原索引metadata及六其他服务保持，仅增加已审wire观测配置。重启未ready已查明是Java进程内receipt丢失，正常前端刷新恢复72单元、Python cached=true、metadata身份未改；只读重启恢复候选尚未主采纳。新Q1.6前端提交UTC `2026-10-11T00:31:31.527Z`，generation request290272UTF8B，HTTP200／32.1365675秒，provider input83868／output2647／total86515tokens。原保留CDP requestTime至loadingFinished为110.064258秒，counter另5.9646518秒；其余71.9630387秒未全归因。保存history78961319B是多轮前端历史含sources，不是模型输入。消息12的wire、persisted answer与前端Mermaid／prose一致，截图有SVG但约410px宽文字很小，不能称完整可读。

**新Q1.6不同作者45项全新评为25pass／14partial／6fail，ledger积分32.0／45。** GCC-only为29.0／41，optional SCC为3.0／4，不是准确率或比赛分数，不沿用旧32.5。实际prompt含53冻结完整来源单元正文及跨页12／16限定；output2647低于16384且正常completed，不支持截断归因。及时Notice反接至final/binding仍是重大生成错误，部分前提／报告／appoint-failure／inadmissibility／confidentiality遗漏；本次90days三event已正确。SCC替换引用[1]只含42字符heading，全文在[3]。独审 `.local/state/oer-q16-answer-independent-audit-20261011/review-04/terminal-receipt-04.json` SHA `082366681b6371b63b7b447ba82b597d3c0402d059509f34f264a9023b84375a`。下一步以通用source-derived事件／条件／时钟／默认出口计划门与citation-member支持检查作同source／model／budget单因素对照，不把45项ledger或2d／2e作为提示，不以增OCR／输出预算掩盖生成残差。完整官方八题、Vetting报告与H800仍未完成。

**用户询问模型微调的准确状态：正式质量微调尚未开始，真实兼容性LoRA已做。** Root重新读取 `.local/state/oer-training-quality-20261010/cuda-lora-three-complete-crops-01/receipt.json`（SHA `4244e7726edc4e6ae8d4241ecb03578d6911742b264b0962baa15e208251ae02`）：MinerU `opendatalab/MinerU2.5-Pro-2605-1.2B` CUDA BF16、3实际optimizer steps、完整输入与target，导出／新进程reload成功，purpose=`compatibility_only`。后续 `parser-seam-02/run-receipt.json`（SHA `b5d021a1e47c5304c1b1beb4468fc96152c216590ad3b65bcf4363de1de7088a`）确认完整parser回接，onlineMutation=false。同输入控制 `base-bf16-controlled-03/actual-exact-77-01/source-only-quality-summary.json`（SHA `1f9e5a7c2c34c3a153a4cb8d8cdc72a5d8d576ce60e217cba404e25e337646ec`）为77／77完整token与文本相同、3／3 raw OTSL相同，未测到recognition收益，qualityAccepted=false。Paddle／embedding／rerank尚未正式权重微调，未替换线上权重。当前继续完整原件标注、实际裁图覆盖／结构／recognition残差分离和同来源开发诊断；独立train／dev／blind来源家族仍0／0／0，不据三步兼容或同源开发结果宣称泛化提升。

#### 2026-10-11 最新实际增量：只有 OER；Q1.3 正常来源冷作业完成，调用观测源码已采用

记录 UTC `2026-10-11T00:02:11.830973+00:00`。**当前只有 OER 一个 active goal；Drafting 保持冻结。** 解析、来源／索引接线、必要调参训练与 Advisory／Vetting 验收是这个目标内部工作。本节覆盖下方关于 Q1.3 正常 producer “未执行”和 observer “未独审／未主采用”的历史快照；运行服务部署、公开表示、来源登记和新索引仍分别验收。

**Q1.3 实际正常来源作业已完成。** Root 在主／复制正常接口17份准确源码全等、不同作者13契约及68实际框投影核验、fresh normal inspect ready／无缺输入后，执行正常 capture×3（两个完整Paddle PNG＋一个完整MinerU selected PDF）→combine→dual→native producer→完整冷校验。实际 exit0，完整作业340.3406489秒；这是本地源／证据处理耗时，0新OCR模型请求，不是GPU推理速度。原parser物理1／2经正常lineage映射原件19／20，完整输入、vendor资产和原source revision分工保留，原raw capture不追写ready。35区域中21采用、14未采用，4unit为父4 heading-only和完整4.1／4.2／4.3，19原关系闭合；4.4续21、3.2前页tail等不补造为完整采用。小终态 `.local/state/oer-q13-normal-producer-input-runner-20261011/root-reviewed-01/actual-normal-small-terminal.json` SHA `0ffb6f1184c1f698b3c89b95f5b2a4904da2cf8a28f29b0c36bc433ff80f7b45`，10448B；不同作者输入终态 SHA `1f8b3e6dad34e961282d94d11e9b93be1192ff9bc8bd6b8b53118c43808a816d`。saved actual的独审正在进行；GCC74页全文质量、legal／human确认及internal vendor renderer几何均未接受。

正常内部 projection 543247046B，cold execution receipt 543254460B，属于完整离线证据，**不是RAG结果或LLM输入**。Root只用有界小adoption／dual及完整文件stream SHA生成小终态，没有重新JSON解析这两份大文件。下一步必须走正常有界public transport和登记，不能把该内部全证据对象发给模型／前端。当前 `publicBoundedTransportProduced=false`、`liveRegistration=false`、`newIndexCreated=false`；现有GCC17的50unit＋项目SCC17的22unit正常索引保持，不能用4个新unit覆盖旧来源或声称全文已进入前端。Root小总结首次相对路径层数错误保留在 `summary-preparation-failure-01.json`，随后仅修总结器；实际producer exit0及全部原件／产品未受影响。

**Advice调用观测candidate01拒收，candidate02已独审并精确主源码采用。** 原`readTree`漏检重复字段早值／尾随JSON中的Unicode转义合成凭据，两例实际写出raw；原失败及NO-GO保持。新candidate02只改为完整token流检查每个解码FIELD_NAME／VALUE_STRING，默认关闭、普通非JSON原字节、usage unknown、完整请求及来源／预算守卫保持。不同作者最终fresh39方法全绿（原36＋额外3，有重叠的多轮不累计）；独审terminal SHA `43d9280915a3eb2e24e96ec7c70be4947306e3dfdf2f37ad42362560f6e57e51`。精确采用3个Advice产品和2个测试共5文件，维护性负例使用@Test TempDir，不写repo父目录；751其他src／frontendsrc／relay／POM文件bytes保持。采用receipt SHA `df17fca6d7b1e0fe6a988ad5e6c264df3b69d5b8b9dd56e920cb1a99de4e4591`。**仅源码已采用：尚未替live JAR／重启／启用观测／发新M3。** 组合JAR正在fresh ignored checkout离线构建；通过源码测试不等于真实provider body／usage已观测。

后续顺序是normal source的有界公开表示／完整来源family组合／登记和索引，已审组合Java运行接线及真实完整wire／token／usage观察，再推进官方八题与完整Vetting。已准备question-only八题及范围内输入队列，2c三封项目邮件与八题情景分离，2d／2e答案不进prompt／index／训练。Q1.6旧26pass／13partial／6fail及32.5/45仅ledger覆盖积分，完整业务、训练质量收益和H800仍未完成；不宣称本次源码或源作业通过等于比赛成绩。

#### 2026-10-11 最新实跑增量：仅 OER；MinerU 完整两页整作业成功，正常来源接线继续

记录 UTC `2026-10-10T23:33:21.011252+00:00`。**当前只有 OER 一个 active goal，Drafting 冻结。** 解析／必要调参训练、来源与索引、Advisory／Vetting 接入属于该目标内部工作。本节覆盖下方 MinerU prepare02 尚在窄修／未 GPU 实跑的快照，保留旧900秒失败及全部原始证据。

prepare02 的不同作者独审通过，receipt `.local/state/oer-mineru-profiler-owned-worker-independent-20261011/review-02/terminal-receipt.json` SHA `7956e9847d09dbfa57c5dd8cc7ac9c870052a8632b407038cb53094da69f5114`；核完整1057实际文件、408 pin角色／历史reader移址及非timeout准确owned-child清理。Root随后fresh核7个准确服务身份、317静态项、当前冻结源码、OER／M3空闲及GPUfree14515MiB，以准确plan SHA `c1e7e09bd6ad22ce6090361f12577ab7649976aa3ccab3737fa05dca8031db61` 创建短期unlock，仅运行一次完整选页输入。session74976已exit0，不能重发或继续poll。

**新整作业实际completed。** GCC物理19–20完整两页、原selected provider输入SHA `c3ce7a7744e78fca50fa0c86033bf65ba5b67859ec71150b0ca25b0545502d78`、MinerU4.0.10 full／vLLM0.19.1／CUDA FP16／8192／CPU表格辅助保持。launcher return0、networkAudit passed、worker两页completed、capture material complete。Root实际调用254.1343534秒；完整模型解析日志132.988763秒、34 native请求。两种耗时范围不同，不将差额都归于GPU／模型或视为controlled速度收益。调用收据 `.local/state/oer-mineru-profiler-root-execution-20261011/run-01/root-invocation-terminal-01.json`；launcher `.local/state/oer-mineru-profiler-owned-worker-20261011/prepare-02/actual-mineru-01/launcher-terminal.json` 420B／SHA `d992446b8239bb350e8609f2a71e1c985d35e1eabddc09aafda49d3a7a844d0f`。原worker-receipt仍是启动时running，不覆盖该历史收据，以新terminal为终态。

本次capture164 artifacts／7429386B，manifest canonical JSON内容digest `091a3e7eb3f0b6bc7590d715bd17e550319752671376c6e362e2553c051a905d`，准确文件字节SHA `6d3c4ac4733eefc256273ca4f7f1c1bf34fa783e4338de917c7b7e89636b858e`，runId `mineru:5650394eebb441f215c591860bf2befa0f51cc07e6f48e5ed483dc8413ddb6d2`。有界首forward原trace1114730B／SHA `e4ef7fe0078e9eced02c716f4ed103da590d0d84ebeaf0c70f8c1485b7b4f5d9`，sampledForwards=1、traceAdmitted=true；385 CUDA kernel仅属于该采样，不是整parser次数。完整1751 forward metadata与34请求仍保留，runtime receipt1814294B，trainingSteps=0。16MiB仅约束持久化／读取采样，不约束临时export、内部内存或forward时间；这次实际可正常收尾不放宽该限定。完整capture是本地调试证据，不能当RAG／LLM输入量。配方的CPU表格声明与实际执行分别表达：本次Torch OCR／layout有真实CUDA forward；三个惰性ONNX表格模块未执行，实际设备保持 `NOT_EXECUTED_UNKNOWN`，不凭配置项冒称已运行。

**新原始输出与已核页件逐字节一致。** Root核4个distinct完整vendor文件77732B（MD、middle_json、model_output、structured_content）的库存／bytes／SHA，全部与旧已做完整pixel／overlay及文字核验的输出全等；5raw roles含model_output alias，不能算5独立文件。身份收据 `run-01/raw-output-identity-01.json` SHA `4d934af04188f0099a48f7ef02096a04bab5d408f4ec0094f3d4b7d76311ec8f`，绑定prior quality SHA `7ae5087248bf07c3ac3416a4fc5d93b17435579d383c4077573235ab0238b924`。按该精确身份引用原34／34非空region定位与27／34空白折叠literal结论；未新render或伪造新人工确认，既有标点残差保持。旧wholejob失败不改写，新job执行成功与页件质量引用分别表达，不宣称GCC74全文或法律采用通过。

结束后7服务身份与317静态bytes保持，采样GPUfree14523MiB／util0；该采样不是整卡peak，也不能单独替代完整owned Linux子进程关闭审计。实际终态不同作者独审已通过，receipt `.local/state/oer-mineru-profiler-actual-independent-20261011/review-01/terminal-receipt.json` SHA `453cf073f3467e6b2b01e5013b2163617df221a19d6b9ae1bfe658c7a3a66a30`。独审核164完整产物、34请求及完整网络trace；236 trace均exit0，并fresh只查记录的236 PID＋tracer共237准确PID，全部不存在，EngineCore shutdown与form session closed相符。此为实际owned进程关闭证据，不是从Windows exit或GPU0推断。原件质量／正常lineage和业务／训练继续保留缺口。正常producer source-only runner的小authored契约已通过，计划保全35regions、21候选采用（父4 heading-only、4.1、4.2、4.3）、14unadopted和原19relations；实际完整native CAS冷读、selectedRaster正常replay、capture／projection登记和新索引未执行。原raw run的物理1／2、`nativeLineage=null`、`captureStatus=incomplete`及坐标未知／Markdown通用JSON投影issues保持；它与material complete是不同门，须由正常producer计算来源／transform，不能手写全局ready。4.4续21及3.2前页tail保持unadopted；原1／2 parser页须通过正常selected lineage映射物理19／20，不手改原raw或写虚构humanConfirmed。

正常OER部署／索引／重启缓存保存证据另已通过不同作者审计，receipt `.local/state/oer-combined-runtime-terminal-independent-20261011/review-01/terminal-receipt.json` SHA `3b021123a4306b98aaba3cb7b6a16ebf2ce4c5e2ee0df0d4769ab0072757d6d8`，不重跑这些已通过步骤。Advice-only完整wire／raw／usage观测候选作者33离线方法通过、已封存未独审／主采纳／部署；当前加载配置是官方MiniMax Responses、M3、16384输出cap，不能称经过仅支持Chat的本机relay。没有新M3／Q1.6调用，不盲发第四轮；Q1.6旧失败、完整官方题／Vetting报告、真实训练收益与H800仍未通过，goal active。


#### 2026-10-11 最新联合终态：仅 OER；正常部署／新索引／重启缓存完整性通过

记录 UTC `2026-10-10T23:10:33Z`。当前只有OER，Drafting冻结。本节覆盖下方new-index／restart-cache／point-readback pending的旧快照，保留旧失败及未完成质量。联合receipt `.local/state/oer-combined-runtime-switch-preparation-20261011/prepare-01/joint-terminal-receipt.json` SHA `015c754d7a276fe343acd26e31a14cd0f80e3c7c02a8932274c9d34aa9814437`，status=`normal_combined_two_OER_switch_fullset_index_owned129_points_and_restart_cache_verified`。两OER正常launcher及同codeAdvisory重启冻结source均 `e6c12faa92f6af56a9864f73e089b23e445a79039441be933ec12b0ea7a20bec`，组合SHA保持 `102e8682012a97faf8644e527bafeb1d47352928c9f42bdfd838da0d670459d5`。

正常Java新索引72parents／129windows／52contexts，signature `062d679a25059234a4dc9161cadd6efc30006693bfd3de8ad32efb6fceb08931`，cached=false；新Java71.5080016秒／Python36.35670799994841秒。正常重启后正常请求实际cached=true，Java44.6221553秒／Python24.835351299960166秒，metadata完整bytes／SHA／mtime与normal seal保持，0新upsert／0新model-load。未观测direct encode-function counter，不用历史encodedWindows替代本次counter；这些正常操作不是controlled速度对照，不把时间变化当模型／吞吐成绩。

关闭态私有副本真实129 IDs／完整payload／有限归一1024维FP32 points与normal seal一致，persisted digest `8b9efa24d6fafd85bae0f9e8bcf74933a6186f7ba00b53b45d84498eff5f10fa`，pointreceipt SHA `71393c2960933e55224860ea607f337ea9ebdb69a6ae5cfe9eece823e3240d8d`。snapshot捕于newbuild之后restart之前，独立copy读回在cached POST之前；cached路径自身核current points，没有第二次postcached独立DB读回。本次文档任务只读小receipt，未打开pointvalues或DB。

两OER各801完整来源CAS证据665869847B的bytes／SHA／mtime、317静态保护与Java／frontend／relay身份保持，Drafting0／新OCR0／新M3 0。已通过的是正常部署与索引／restart-cache完整性，未新增H800／controlled吞吐／法律全文／官方题／训练质量结论。Advice去重JAR仍已构建未部署，Q1.6旧26pass／13partial／6fail、32.5/45及具体内容残差和官方8题／Vetting／训练门保持。

MinerU profiler prepare01独审receipt `.local/state/oer-mineru-profiler-owned-worker-independent-20261011/review-01/terminal-receipt.json` SHA `fb3e84e9aabe759566b84ccfa51f88df70044a8b38a3b1ba3aeccaf5b4b10891`。active owned child的non-timeout异常清理缺口已CPU fake实复现，TimeoutExpired已有owned group清理、无child／已退出case分别保留；作者prepare02窄修中，prepare01封存、尚未新GPU／模型／worker实跑。旧MinerU整作业失败不追认通过；后续仍须窄修独审、真实parser完整收尾、原件质量／参数训练及官方业务验收。

#### 2026-10-11 最新运行增量：仅 OER；正常两服务已切换，新索引及重启复用仍待

记录 UTC `2026-10-10T23:06:08Z`。当前只有OER，Drafting冻结；本节覆盖下一节运行pending快照。正常launcher仅两OER真实switch已终态，receipt `.local/state/oer-combined-runtime-switch-preparation-20261011/prepare-01/actual-switch-01/switch-receipt.json` SHA `a02022551cbbf7a12b0db413cc235e8ca99f05c3a853a0fde2bff2fc355983a7`，status=`normal_two_OER_switched_exact_combination_new_index_pending`，绑定组合SHA `102e8682012a97faf8644e527bafeb1d47352928c9f42bdfd838da0d670459d5`。保护服务／静态bytes保持、Drafting changes＝0、providerRequests＝0；不是Java／frontend／relay或Drafting重启。

switch收据记录时indexRequests＝0；随后Root已正常发起新索引，尚无已核新signature／seal／ready终态。同code正常restart、cached路径和完整点读回仍待，不把source采用／runtime switched合称整个部署验收通过。fresh92、精确44与repository23有重叠，103宽选的1failure／19errors及准备失败继续按下节保留。Advice去重JAR仍已构建未部署；旧M3／Q1.6内容与32.5/45 ledger结果、实际训练及H800状态无新质量结论。MinerU有界profiler独审的非timeout异常child cleanup缺口仍在准备窄修，未新实跑GPU。此次只同步已存small receipts，两文档更新没有新模型／原件／DB／服务操作。

#### 2026-10-11 最新主采用：仅 OER；Root fresh 92／44 和资源接缝通过，运行终态待核

记录 UTC `2026-10-10T23:04:06Z`。本节覆盖下方“candidate03 fresh独审仍待／未主采纳”的历史快照；当前只有OER，Drafting冻结。Root fresh combined producer的75＋7＋10＝92方法绿，原byte probe实际以ParserEvidenceError拒绝；精确44 ASGI方法绿；23 repository-path／真实小authored v1资源接缝绿。三组有重叠，不加总为159项独立测试，不称全套回归或真实模型／原件／业务通过。路径 `.local/state/oer-combined-root-review-20261011/review-02/`，四份small receipt SHA依次为 `e245428226ddecaf8e43d50a97a9cd0f1fb10b1926ec203e893ec28288dd5d3a`（producer green）、`38b636ea6226dfd481e1cd64acc7aa724ff631a99ccbd15b3c6b8b208ca65946`（原probe refused）、`5930551a0a1f08bd85cc7d84c2268a82888e0b663fa8fd32e7084fa3d89ae9f0`（精确ASGI44）、`30303eb1f4c3d966a97aca7b08bac7d191588304e8d47f90626b396213ec4e49`（repository integration23）。

宽选103包含历史scanned假PDF fixture的1failure／19errors原收据／log保留，SHA `d6ce42f3e027f686fa2566601c58b0ac582b498254da3117f9d76489bfda87ce`；该fixture未满足source gate，后续只准确选原44类，不改假asset或弱化gate。首轮test目录／WSL distro／shell路径准备失败也保留；精确44的1次外部网络操作拒绝不当成provider成功请求。

已精确主采纳10 OER产品＋6契约tests＋22小authored retained-v1资源，160其他main Python／317静态保护保持。source-adoption receipt `.local/state/oer-combined-root-review-20261011/review-02/main-source-adoption-01/source-adoption-receipt.json` SHA `1e27407fa552a3fc05e6107a2afe3f47d00c70c7d5fe8fa67f94c616e0fe229a`，combination manifest SHA `102e8682012a97faf8644e527bafeb1d47352928c9f42bdfd838da0d670459d5`。仅RASTERS路径relocation的canonical-projection test不采纳，DEPENDS10 producer-replay采纳，真实小v1资源进入repo `src/test/resources/oer/native-pdf-producer-v1/`并核接缝。原件／模型／密钥／运行trace未入repo；历史CAS provenance不冒称历史producer实际执行认证。

采用收据只证明源码采用，serviceRestarts／liveFrozenJarReplacements及provider／DB／index writes均0。Root随后正常launcher仅切两OER，文档写入时switch终态尚未核到，不能追认部署或索引ready；Advice去重JAR仍为已构建未部署。下方GET性能PASS和Q1.3两页pixel／overlay／标点限定继续有效；MinerU整launcher失败、Q1.6既有32.5/45及内容残差、完整官方题／Vetting／实际训练收益／H800待验收均不变。

#### 2026-10-11 最新状态：仅 OER；GET 独审 PASS，展示去重 JAR 构建完成但未部署

记录 UTC `2026-10-10T22:58:34Z`。当前只有 OER 一个持续实施任务，Advisory／Vetting 是其业务接入与验收，Drafting 源码、识别 harness、采用值、测试和部署保持冻结。本节覆盖下方待独审快照，保留所有旧失败；当前详细状态见 [handoff 最新证据增量](../HANDOFF-ocr-embedding-rerank-20261008.md)。本轮仅同步两份文档，没有新模型／GPU／原件解析或服务切换。

canonical GET 的保存实测证据已获不同作者 file-only 独审 PASS：8 fresh child／24完整 GET，SCC22／GCC50当前登记单元的完整response／member arrays／headers／ETag及CAS有序闭包保持，13811检查0failed。warm pooled 的baseline→candidate为SCC18.9959945→0.52088015秒（-97.26%）、GCC41.8775001→3.7403130秒（-91.07%）；每来源／variant warm 4次，cold2次新应用状态、OS缓存未清，GCC cold42.7271518→46.0728250秒实际慢7.83%。独审只核已保存801文件665869847B完整hash＋stat证据，不重新打开payload或重放GET；baseline旧软件／root-frame request guard为0，candidate准确normal `algorithms`两次和成功root rehash两次，不能称软件守卫一致。仪器开销随工作量变化，每child外部网络拒绝事件细节未捕获、归因保持未知。本地CPU ASGI结果不是Java／TCP／H800／端到端业务成绩，候选尚未主采用或部署。独审 `.local/state/oer-canonical-get-performance-terminal-independent-20261011/review-02/terminal-receipt.json` SHA `8f5abc44241fdf6baea3a8e290d5093e0e96af7c7a8712b41f7222e40a29e569`。

normal producer02作者75及准确独立7项绿仍被额外实际负例拒收：去掉marker，在已知selection／raster schema资产制造duplicate schema key并绑定实际字节，normal capture错误接受无selectedSourceProvenance的PNG；strict JSON失败被跳过导致legacy分支绕过。拒收terminal SHA `e6d53411dc31b1e52e6804c8c099f0be9c63b6d61b2bf94df1b86cc7df997d05`。candidate03只增`parser_capture.py`的拒绝分类，保留全部top-level pairs，已知／未知version schema及top-level lineage不能由invalid JSON降格；普通资源及v1 replay保持。原probe逐字节保留并已正确以ParserEvidenceError拒绝；75＋7＋10＝92机械方法绿，11个red断言／子项及准备失败保留。terminal SHA `b43e02152f2bfd9ae8041c0cdc085c86bf817d788d20bcb22f64d5dcaf646c72`，路径 `.local/state/oer-q13-normal-producer-support-20261011/candidate-03/`。**Root fresh独审仍待，未主采纳／部署／登记／建新索引**，作者重跑旧独立方法不冒称新独审。

Root Q1.3 `layout-independent/review-02` 已核真实保存RGB pixel lineage（原／selected物理19–20页144dpi相等、Paddle完整输入与selected300dpi相等）并直接看两张完整原页overlay。35region＝34非空＋1空白control；两个parser均34/34唯一非空定位候选，均27/34仅折叠空白逐字一致，各7region标点残差。MinerU为9处曲／直apostrophe替换；Paddle为8处同类替换和1个连字符缺失，不静默修原输出。quality SHA `7ae5087248bf07c3ac3416a4fc5d93b17435579d383c4077573235ab0238b924`，路径 `.local/state/oer-q13-layout-independent-20261011/review-02/source-quality-results.json`。首次owned测量错误重复缩放MinerU ratio坐标产生全空，保留失败、仅修测量器。该两页结果不接受完整renderer geometry／跨页法律范围／GCC全文／normal registration／训练收益。MinerU模型两页142.290510秒和worker capture completed保持；whole launcher／dispatch仍在900秒TimeoutExpired后failed。GB级调试trace不是LLM输入，本轮未重读或新跑GPU；有界profiler仍仅fake CPU通过。

Advice展示去重的4文件主源码采用／726保护文件、fresh38机械测试及完整247157UTF8B模型wire保持继续有效；395224→262049UTF8B（33.696081%）是presentation摘录去重，不是新模型token或质量结果。`review-01/package-build-01` offline exit0并核准确class／resource，JAR79262318B／SHA `75689d1d3f17fd13460a7d0b6b9ba105baf35a67d5eb9c59669365bf86715a26`，artifact receipt SHA `cbea78def0b2cfd4aa2049267706b0914a3cf742bc4c599ed0c6555cde097c01`。本次package未重跑测试，38为先前fresh检查；liveJarReplaced=false、runtimeRestart=false、providerCalls=0，**已构建未部署，不能称当前前端已使用此去重**。

Q1.6第三轮业务结果没有新增成绩：仍26 pass／13 partial／6 fail、32.5/45 ledger覆盖积分（不是比赛分数或模型准确率）；期限／触发分支／modal／7天起算和SCC正文引用残差保持。图形与来源保全通过不追认条文正确。下一步先完成fresh独审、来源正常producer／登记／索引和精确运行接线，再在完整来源／unknown／关系保全下验收官方问题及Vetting结构化报告，评价ledger继续与prompt／索引／训练隔离；实际训练收益、完整官方8题与H800验收仍待。

#### 2026-10-11 新增终态：只有 OER；咨询展示去重已接入主源码，完整 GET 性能完成待独审

记录 UTC `2026-10-10T22:39:51.878386+00:00`。用户再次确认只有 OER 一条持续任务；解析、来源／索引性能和 Advisory／Vetting 业务测试是内部工作。Drafting 源码、测试及部署保持冻结。

Root 在 fresh ignored checkout 独立复跑实际 Advice writer：37 项作者测试加 1 项新 mixed legacy／canonical 导航检查，共 38 项、0 failure／error／skip。完整模型 wire 247157 UTF8B 与冻结基线逐字节相同；实际 Q1.6 摘录 395224→262049 UTF8B，减少 133175B（33.696081%），10 完整正文 SHA、57 字典身份／109 有序成员及完整字段保持。bytes 不是 token 数，机械保全不是新答题成绩。独审收据 `.local/state/oer-advice-display-dedup-independent-20261011/review-01/independent-review-terminal.json` SHA `c76120cdbe15032dd4c086149b45434e7873c33f3216fb6b0aacb7fbec2b2d74`。

准确采纳已审 `AdviceService.java` 与 3 份 OER 测试到主源码，其他 726 保护源码／资源／POM 保持；原文件有逐项 before 备份。采用收据 `review-01/main-source-adoption-01/source-adoption-receipt.json` SHA `53caa71508f7bc57fe90024343d1a2431380f204904dc0241f17f28fc5b2bcb2`。未换 live JAR、未重启、未调用新 M3 或更改 DB／索引，不能宣称当前前端已用这次去重。

prepare03 实际 composed canonical GET 已 exit0／`complete_actual_get_performance`，8 fresh child／24 GET，terminal SHA `590684cabbaa93908048c0279799abfc72f0f21c8457f218083cb1c41b06e699`。当前 Root 读回均值如下；每来源每 variant cold 2 次、warm 4 次。cold 是新应用状态，未清 OS 磁盘缓存。

| 来源 | baseline cold 秒 | candidate cold 秒 | baseline warm 秒 | candidate warm 秒 |
| --- | ---: | ---: | ---: | ---: |
| SCC | 19.2861195 | 18.8121117 | 18.9959945 | 0.52088015 |
| GCC | 42.7271518 | 46.0728250 | 41.8775001 | 3.7403130 |

完整 response／headers／ETag／members／CAS ordered closure 相同，801 关闭态证据文件 665869847B 前后 bytes／SHA／stat 保持；warm 收益来自复用派生重建，完整 CAS 检查仍执行。新 candidate 实际 normal `algorithms` 与 entry／exit source guard 已观测；baseline 与 candidate 的新增软件／root 守卫差异须分别说明，不追认相同 guard。profiling／audit／RSS 等测量开销存在。这是本地隔离 ASGI 实测，尚待不同作者终态独审，GET 候选未主采纳／部署，不是 live Java 或 H800／无仪器生产耗时。普通 GET 不读取 revision alias，不能用此成绩支持 alias 守卫。

normal producer candidate02 作者最终 75／75 机械检查通过，新增 v2 两项依赖及 normal runtime 三项 closure；旧 v1 原身份 replay 保持。不同作者 fresh 独审已开始，原件 pixel／35 region geometry、实际来源登记及新索引尚待。MinerU 两页 capture completed／launcher timeout failed 保持；有界 profiler 候选只通过 fake CPU，owned worker／source-lock 准确映射已记录但未实跑新 GPU。Q1.6 旧业务失败、完整官方题／Vetting 报告、真实训练收益和 H800 验收均未完成，goal 保持 active。

#### 2026-10-11 后续终态增量：仅 OER；两页原文核对完成，MinerU 捕获完成但整作业关闭失败

记录UTC `2026-10-10T22:27:08.124908+00:00`。当前只有 OER 一个 active goal；Drafting 没有新实现／测试／部署。本节覆盖下方关于 MinerU “无worker terminal／仍pending”的旧观察。

旧 MinerU session2609 已实际 exit1。两页native VLM阶段142.290510秒、34请求已完成；worker terminal实际completed且capture receipt两页material complete。与此同时整体launcher在900秒上限产生TimeoutExpired／failed，旧worker-receipt仍是running启动收据，保持原字节而不追写。不能把捕获complete升级为整链／网络审计／业务通过，也不能再说没有完整worker结果。Root只读确认六个准确owned PID已不存在、GPUfree14554MiB及7保护服务身份保持，未停止其他服务或重发模型。

收尾实际保存trace3,199,515,409B、enginecore-model-after317,935,702B、runtime-receipt326,356,806B。这些是性能／运行调试证据，不是RAG正文或LLM输入。全程CPU＋CUDA profiler导出、巨trace重读和完整kernel活动列表复制是已定位的工程开销；单次wchan=p9_client_rpc采样只说明当时等待WSL文件系统RPC，不单独解释整个900秒。新候选拟仅一个真实forward采样、原sample有界读取、保准确kernel类别／有限派生摘要，完整source／prompt／image／token／raw output照旧；尚未实跑新GPU候选，旧失败及全部原始证据保留。

Root直接查看原GCC physical19–20完整PNG、两页完整Paddle Markdown及raw native blocks后，逐一对照既有source-review35region：34非空、1原空白control；仅折叠排版空白逐字相等27/34，全部不折叠时0/34（原逐行换行／末尾空白与parser段落表示不同，不用此数宣称34段正文全错）。余7region实际差异为8个U+2019→U+0027及`Provided that:-`漏1个U+002D；没有额外词／数字／否定／正文段落差异。Q1.3相关父条／4.1／4.2／4.3共21region，同口径15/21；4.2(5)／(6)完整在下一页，4.4续21页边界保留。来源质量结果 `.local/state/oer-q13-source-stage-independent-20261011/review-01/paddle-source-quality-review-02/quality-results.json` SHA `7b7f9033d4aa72940d74978c80c968622ccc8b3e47c94870211f139551d783b5`。首测runner误假定vendor footer label导致AssertionError，实际为number，原失败及script保留，v2只纠正测量器；没有改parser、参考文本、分数、geometry或法律采用。不据这两页小文字残差贸然训练或宣称GCC全文通过。

实际大来源 composed GET 性能测试已由 Root session24412 开始，prepare03显式固定Windows子进程 executable并先核小stub的允许／拒绝；当前尚无整体terminal-summary，不报告最终平均数或线上收益。 prepare02第一次真实执行在spawn前触发Path(None)的owned runner错误，0GET，801输入完整bytes／stat前后保持；旧failure及authority保留，新03只修测量器，candidate01／02产品仍未主采纳。完整source／CAS／软件守卫保持，未开放原件／model／DB／主服务。

normal producer v2和selected-PDF→完整RGB PNG lineage候选完成作者59机械方法0fail/error，但主采纳、实际原件pixel／35regiongeometry及真实登记／新索引仍待；retained code provenance与已知recipe replay各自表达，不能把旧producer资产当当前源码或实际执行认证。Java完整正文单份presentation去重复候选也只在ignored副本，真实模型对照尚未发起。Q1.6旧26pass／13partial／6fail、完整官方题／Vetting报告／实际训练收益／H800仍未完成，goal继续active。


#### 2026-10-11 后续实测增量：仅 OER；缓存 frame 性能独审通过，Q1.3 两页 Paddle 完成，实际 GET 接线机械通过

当前只有 OER 一个持续目标，Drafting 冻结。记录时刻UTC `2026-10-10T22:12:56.521770+00:00`；本节覆盖下方“仍在准备／未选页”等历史快照，不追认尚未完成的质量和部署。

缓存frame性能8 fresh child／24 frame已实际终态，不同作者file-only独审通过。范围准确为当前登记GCC50／SCC22单元的完整wire／成员／CAS闭包，不是新GCC74页全部法律质量。四个warm observations合均值：SCC5.372967→0.598359秒，GCC13.653336→2.693887秒；cold均值SCC5.295550→5.480822秒（慢3.50%）、GCC13.662539→15.106829秒（慢10.57%）。72完整输出逐字节相同、143固定证据bytes／SHA／mtime保持；每frame实际CAS双端SCC82次／251839166B、GCC1476次／820100160B保持。软件guard为私有完整callback，167py＋pypdf准确variant入口／出口保持；不是实际GET／Java或H800成绩。独审报告 `.local/state/oer-derived-reconstruction-perf-independent-20261011/review-01/INDEPENDENT-PERFORMANCE-REVIEW.md`；terminal SHA `40f496356e3db63f41768baa61bd7aaa27388413e7f4e4c8c7b12ed3187ac0e9`。

实际composed canonical GET候选02已经作者封存，Root fresh不同作者复跑44项实际ASGI／frame／collection契约和4项既有负例均绿，363复制输入及6个主源码before pins保持。两GET小fixture完整重建2→1、每GET完整CAS trace保持；响应／headers／ETag／scope／unknown／caller ownership不变，出口漂移在发送200前拒绝。Root把delta-before误当main baseline及漏复制私有test依赖的准备失败保留，修的是owned runner而非产品。机械terminal `.local/state/oer-derived-reconstruction-independent-20261011/review-02/independent-mechanical-terminal-receipt.json` SHA `5be2ff48621b6f24521f3f9e83c86ae23e7ea9dc3dbb76f97ff67735ad6c8b0b`；大来源实际GET性能只在准备，candidate01／02均未主采纳或运行切换。

Q1.3新`wsl --exec` source-only重试实际exit0，原GCC74物理19–20完整对象复制到2页provider PDF；Root及不同作者独立pypdf重开复核decoded content／resources／5boxes／rotation／userunit逐页全等，原件bytes／SHA／mtime保持。实际空用户密码认证USER_PASSWORD／1，原/P=-1852及copy/extractbit=false保持；无猜密码或更改原权限。独审 `.local/state/oer-q13-source-independent-20261011/review-02/terminal-receipt.json` SHA `83c725812e06a40e015868420ef9a764505c020c368e0e227289498c52cb58f4`；只证明对象及完整模型job身份，pixel／geometry／semantic／legal采用仍未接受。

Rootfresh核7保护process、OER／relay连接idle、GPUfree13681MiB后，Paddle按原frozen完整配方执行成功：gpu:0、FP32、300DPI、8192输出cap，2完整selected provider页、整作业85.5193094秒；34实际generation输入token IDs与完整无截断tokenization相同，输出均EOS-before-cap，native plain／required vendor JSON保存。terminal SHA `48accb052d90e09e5c18e97c7747d991f670f14252f739b7d4d456398a24ea69`。这是执行／完整性证据，不是文字／条款读序质量得分。随后freshGPUfree14505MiB和7保护process核验后串行MinerU官方full CUDAFP16／8192、CPUtable；没有两主模型并发。

实际MinerU作业由Root session2609运行，模型阶段日志已完成2页／142.290510秒，native-vlm-terminal sequence34；截至本次记录仍无launcher terminal，不能报告整作业成功或质量通过。Root只读观察EngineCore的wchan为p9_client_rpc，说明当时正等待WSL文件系统RPC；精确代码的close含profiler.stop／trace导出及runtime核验，尚未将该采样归为独占耗时或模型算力不足。作业仍由原owned launcher的900秒上限管理，不另起重复作业、不停止其他服务。

新source／Paddle lineage还有真实normal-producer接缝缺口：main仅识别旧plain selected-source schema；真实Paddle输入为原selected PDF渲染出的完整PNG，不能标成直接PDF。新v2 schema被现门拒绝、无marker时被当普通JSON跳过的实际红已保留。当前在ignored候选补显式schema／recipe及可复现完整PNG像素lineage，未执行旧bind脚本冒称支持；小自造CPU render契约已授权，真实35region／原件pixel质量与正常producer／登记／新索引／业务仍待完成。模型材料进一步去重复也仅在ignored候选，完整正文、跨页条件及unknown保留，未新发M3。Q1.6第三轮旧业务失败、完整官方题／Vetting报告／训练质量收益／H800未完成，goal继续active。


当前只有 OER 一个持续目标；这是其业务接入验证，Drafting 继续冻结。

## 最新结果：第三轮独审完成，图形通过、业务内容仍有实质错误

同45项source-only独审第三轮为26 pass／13 partial／6 fail，覆盖32.5/45；GCC baseline27/37，optional SCC2/4，citation／limits3.5/4；GCC加citation30.5/41。不是比赛得分或模型准确率，不强制给GCC-only原题加入未声称采用的SCC。完整53来源和跨页relations一致，仍漏17.1(16)90天仲裁／失败调解分支及ADR触发，shall弱化为may，7天due-date错成demand，扩大(17)规则；SCC正文引用不准确。图形能展示不代表条文正确。

独审quality SHA `76c1021e2861ed9876061194e34783a4facb7fc181835d6a606bd0fd43d1dfe4`，terminal SHA `836946105827638405e7b83b3f06b5b2114784adb00c8bb19dd8f214e36bddcf`，路径 `.local/state/oer-q16-answer-independent-audit-20261011/review-03/`。保留第二轮失败，不把grounded=true升格验收。下一步在完整字值／顺序／关系／unknown保全下调查模型材料重复表达，继续补官方题来源，不能按评分答案改prompt或只反复同题重问。当前只有OER，Drafting冻结。

## 历史接续：第二轮已独审失败，当时第三轮条文待独审

第二次正常前端原题回答经45项source-only独审为13 pass／20 partial／12 fail，覆盖积分23/45（pass=1、partial=0.5、fail=0），不是比赛分数或模型准确率。53完整来源单元及跨页relations匹配。主要残差在输出：省略重要期限／触发分支，改变可选提交调解的期限事件，并误断已提供条文缺失。结果 `.local/state/oer-q16-answer-independent-audit-20261011/review-01/quality-results-02.json` SHA `cd4481c6b9fcb5a093cf3d2095d92cb58944218d53730820562f8e593d285931`；terminal SHA `7c025513c863ab556e649b84406a75fb738f29fdcb9e5844405bf93ce6af4cfe`。评价ledger仅用于事后评分，不进入prompt、索引或训练。

renderer02已42项独审通过、7文件精确主采纳、OER契约26／typecheck／build通过及主静态发布。当前主页面遇到第二轮无效原Mermaid明确失败并保留完整图及prose，现场收据 `.local/state/oer-advice-renderer-main-deploy-20261011/browser-acceptance-receipt.json` SHA `d6e1b1e3511e6bb5ee799f1624b4e5237a5b624d0d4352ba5963d1df6d0ff61d`。合法合成图、无效图和字面HTML的真实隔离浏览器收据SHA `2a700a49a35dd3d4a0b2a425068240c910b390556c0426ca55529c590564cd84`；不是实际答案内容通过。下方未采纳、仅文本展示等段落按此前状态读取。

基于实际遗漏，Root正常PUT／GET只调整 `advice.ask`：保留actor、action、modal、trigger、earlier-of、default及failure routes；禁止把referral期限改成resolution期限，依据完整实际来源判断缺段；简单引号Mermaid节点，无HTML／CSS／classDef／:::／click。候选不包含题号、具体条款或期限／评分答案；其他三项prompt及模型／来源／索引／预算／守卫保持。fidelity＋语法是两项通用输出要求的工程候选，不宣称单变量实验。完整配置及原配置保存在ignored `.local/state/oer-advice-output-fidelity-20261011/candidate-01/`，applied收据SHA `bf47ded6fcc13fe81e39ac7cff1dd0d6c94656a3384eca3a093223a667c58e42`。

第三次同一官方原题已经主前端正常终态，项目／profile／scope保持，无replyTo，requestId `115236.161`、start timestamp `1442776.285221`、loadingFinished `1443110.604294`，端到端334.319073秒。HTTP200／API code0、grounded=true／reference_only／0clarifications，英文8683UTF16字符、10sources／2citations。完整原响应19747608B／SHA `55b5cb5063b5e744ef3e3ea08cd95b72c1e5350af6585937c79d78eb8f170e56`，原图5024B／SHA `26a8a02fbbc0c6cb5f17b639bccba3b84957f928ce87063c16e7b2c25b929ee1`，观察收据SHA `145293634b419b5362159fd4db90877b63106fa1f9f2f3d3f592979a66490ad4`。真实主前端svg1／41个DOM标签记录（16个非空textContent）／0activeNodes，完整图及前后prose逐字核验；第一次不匹配prose selector的readback保留，另存正确direct-child读回。真实图形通过，独立45项内容核验尚待完成，不能升格业务通过。provider raw／usage及本次input_tokens尚未捕获。只有OER目标，Drafting继续冻结。

## 已核失败与诊断边界

官方 Q1.6 原文要求流程图总结 GCC17.1。首次已部署 Responses 实测 HTTP200、端到端420.661412秒，返回 `I don't know` 和五条项目事实澄清，没有答案或流程图。实测依据为 `.local/state/oer-q16-live-responses-20261011/browser-01/business-observation-receipt.json`，SHA `c2ba65152b686db7b4ba8180fe4dd511b8406f17dc480ef4d03b8999f0def027`。

不同作者读回10条返回来源的109次成员出现、57个唯一成员，既有 GCC31／SCC22 共53个基线完整单元文字及结构全部匹配。45项衍生评价为41项未答、1项拒答范围混淆、3项项目overlay不适用，score=null；不能称45处事实错误。完整正文在保存的返回来源中，不等于准确provider generation wire已捕获。原模型 AnswerRecord 的 grounded／basisScope／answer 未观察，不由最终无正文反推。独审收据SHA `15b35f9b8438897c781c4a92d0253e62b0db709c07382de34e547212c7e6178d`。

当前候选假设按验证顺序是：

1. 提示同时默认 `project_contract` 并突出采用／版本门，使模型没有正确选择标准原文解释例外。只调整任务意图，保持其他条件，用同一原题复测。
2. 来源完整性或版本声明中的真实unknown导致过度澄清。若第一项仍失败，逐缺口核实，不把unknown自动改成已核。
3. 模型自身任务判断或 JSON／输出gate导致正文未交付。最终history不能重建原AnswerRecord；需要准确调用证据，不能据保存稿猜原生成正文。

## 单变量提示候选

用 [完整候选配置](./oer-advice-task-intent-candidate-20261011.json) 留存实际应用的 systemText／userTemplate，包含既有规则和通用意图说明，没有题号、具体期限、原题答案或评价ledger。该配置是本地调参候选，不代表业务验收或已改变源码默认值。

规则按用户真正所问内容选择：

- 总结、解释或画出已提供标准文字的流程，不要求判本项目实际适用时，用 `reference_only`。保留所给原文条件、相对触发事件、期限、分支、来源和真正的缺段／版本歧义。项目实际日期、阶段、任命或采用状态不自动成为原文总结的前提。
- 对本项目适用、实际期限计算或行动建议，用 `project_contract`，继续核采用、版本、项目修订、条件及来源。不能把未知事实猜成已知，也不能为了得到答案换成参考解释范围。
- 所选项目、检索scope和检索到的项目修订不能自行改写用户任务。标准baseline与项目overlay分开；外部附录未提供的细节不补造。
- `clarifications` 只列回答用户原问题确实必需的澄清。可选应用核对写限定说明，不阻断已支持的原文解释。原有grounded、引用、采用和source-current门不改。
- 用户要求流程图时，answer可包含普通 fenced Mermaid flowchart，标签及分支保留引用。展示层不执行自定义配置、外部图片、点击处理或CSS；失败保留完整图表原文。

## 实际配置状态与下一门

Root 已通过正常 `PUT /api/prompts/advice.ask` 然后 GET 读回，只改此一key，其他三项prompt完整对象SHA保持，包括两个Drafting prompt及Vetting。原完整配置在ignored `before-prompts.json`保留可恢复；源码默认未改，当前保存配置为自定义。应用收据 `.local/state/oer-advice-task-intent-20261011/applied-receipt.json` SHA `2e5cd59e6894fed6dadc42b1ec9b98bd0a7456abe712733940fa3581095dce46`。

同一官方原题、相同项目／minimax-cn／fullset已从正常前端再次提交并取得终态，requestId `115236.138`，没有replyTo背景。模型、权重、来源／索引、采用门和Java运行均未因本次对照改变。实际HTTP200／API code0，requestWillBeSent timestamp1441271.281665、loadingFinished1441625.792777，端到端354.511112秒；`grounded=true`、`basisScope=reference_only`、clarifications为空，英文正文6290 UTF16字符／6308 UTF8B、10来源／3引用。正文从前轮拒答转为GCC baseline流程图源码及分列SCC overlay，条文是否完整正确仍待独立45项source-only评价，不能把grounded或引用数量当作通过。

前端CDP扩大response buffer后完整捕获本次原ask响应19740326B，SHA `77f3f068a3aff8c734bbb0163379e1b01b9f5c770ca9f0e459362b5b2daac314`，位于 `.local/state/oer-advice-task-intent-20261011/live-01/ask-response.json`。业务终态收据SHA `a27719155ac0c7e103e32edc5733f81dba18d7e7f0288546df9440287fd0075e`，绑定request／response／loadingFinished事件、完整正文和截图；正文另存`answer.en.txt` SHA `7d6b60e22b149eb067ad792cf5fddf08ebe460a81d8b6ba990345d54eb687e9b`。这是带sources的前端API响应，不是LLM请求大小；provider raw／usage和本次实时input_tokens仍未捕获，不能套用旧冻结计数。

前端 flowchart candidate仍在ignored隔离副本。候选01的32作者契约及合成真实浏览器节点检查通过，但不同作者8项边界7通过／1失败：actual Mermaid对128902字符源返回错误SVG，helper误当成功，故NO-GO。候选02只加调用前100000字符拒绝，34作者契约／typecheck／build通过，待不同作者fresh审核，未采纳主前端或热部署。这些不是官方答案成绩。

最新实际回答中的原始Mermaid用固定11.15.0真实parse失败：第二行A节点未引号文字含括号，遇到`PS` token；还包含`classDef end fill...`和`:::end`，不符合本次提示的无自定义CSS规则。当前主前端仅文本展示，实际流程图未通过；必须保留原图全文、解析失败和未知状态，不用展示修复补写缺失条文。后续分别验条文正确性、引用、真实图形，以及项目适用／版本冲突／真缺段负例；单题误拒答消除不追认整批八题或Vetting通过。

第三轮DOM标签数量口径补充：41是selector记录数量，16条textContent非空，未据此宣称完整读过全部图上标签。原观察收据保留不覆盖；修正收据SHA `7770fdfb0bb8d96434c62e128a03c6a35fa30fd9cdbb13f33b2f00f3a7d1800b`。真实SVG展示及完整原图／prose逐字核验保持。
