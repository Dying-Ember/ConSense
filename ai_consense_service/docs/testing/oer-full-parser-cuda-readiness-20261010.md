# 完整解析链 CUDA 准入：MinerU 布局辅助模型的实际探针

日期：2026-10-10，Asia/Shanghai。本文记录完整处理链随 H800 发布包交付前的一项依赖／执行设备核验，不是完整 parser、原件质量、固定六来源或 H800 验收。

## 最新进度：Paddle v3 完整两页已实际完成并独立评分

新owned `.local/state/oer-paddle-complete-worker-20261010/root-gct-v3-execution-01/` 已prepare和实际run，exit0／worker completed，execution SHA `09966b7d712cfbc891ff9c47dbc89d23552795290993698e90f7448e3540b546`；worker65.29739970015362秒／process68.99162869993597秒，postflightErrors=[]。实际工程存盘审计receipt SHA `84d797e5171b8447970ebaeef0d88c2e86918506251747bc6f512a34d21dc62e`：36 attempts、1199 completion IDs含EOS、cap8192均EOS before cap且未截断；主模型全GPU0 FP32，layout1static session／2runs GPU0，内部tensor未知。两页forms输入完整300DPI2480×3509，原vendor null页索引不改；607 unique CAS／280451345bytes。65个整卡样本最高14084MiB不当isolated peak；listeners[]不当实际服务PID保全证据。

生成后独立评分receipt SHA `4266a57fbe4a4fd3e31859040b18e12ffede63976d6a3bb77d34aaf804ff906b`：24/24区域有内容、18/24逐字、22/24空白归一化、24/24数字；正文11/11存在／空白相同／数字相同，8/8旧form显示恢复。两页页脚dash遗漏保留；四边注4/4文字及几何支持，但explicit edges0/4，三边注paragraph_title误label及整体读序未过。参考只评价，36/36 raw blocks保全；质量／canonical／wholeSource接受仍false／null，训练0步。完整运行入口已在该GCT贯通，不代表完整固定来源或前端验收。

GCT原生PDFBox捕获与新组合双路材料CAS已完成，详见handoff当前增量。消费者fileCaptureProvenance重建接缝的copied修复33相关方法及实际单页／MinerU整源green；独立审查另发现旧Paddle聚合身份依赖producer源码SHA，被新reader替换导致旧batch回放失败，root-fix-01保持HOLD，新root-fix-02修复中，不剥字段／改旧raw或重跑OCR。当前没有将此候选或编号候选模块应用产品，主target／在线服务未因此更新。

## 较早进度：Paddle v3 导入修复完成，真实作业尚未发起

copied 候选 `.local/state/oer-paddle-complete-worker-20261010/root-worker-v3-import-order/` 已停止编辑，handle SHA `d2b71a0a2db1e333b2ba5237ee51bc0fefbcceaf4bd3ec472644c4414763a96a`，freeze SHA `1373590726120585e7213d71787e9648cb170b4c1a995aff747604a05ecb5bd6`。先初始化官方公开 `PretrainedModel` namespace 再读取公开 `GenerationMixin` 的最小修复，已在真实 fresh-process 旧顺序 red／新顺序 green 检查中验证；observer 仍先于 pipeline 构造安装。原 37 个唯一方法与新增 3 个合计 40 个通过；依赖、资产、完整页、FP32、预算、forms、offline 及输入／终态门控保持。当前是独立准入和新 v3 run／prepare 适配阶段，尚未加载该候选的模型或做真实 OCR，不与已完成的 MinerU GCT 内容评分混淆。保留 v2 实际失败，训练 0 步；本次进度读取未发起新的模型调用。

## 当前增量：v6 新鲜准入后真实完整 GCT 执行成功

真实 v6 后续独立存盘审计 8/8 domains 满足，review SHA `959e6b038dc2805cc299e0a9a18f4604e7736047953d486cfd7786d8f433458f`，见 `.local/state/oer-mineru-gct-v6-independent-review-20261010/run-stored-01/`。38 VLM requests／images／responses／EOS 全绑定、1135 completion含EOS；main实际 CUDA0 FP32无量化、detection 12 CPU forwards、layout1 CPU forward，recognizer0、其他5未执行unknown。1 child／1 opening终态闭合，networkAttempts／failures为空。最高8120MiB是54次whole-card采样，非process allocation peak；纯生成token/s没有直接计时，不从整process或nested adapter倒推为纯速度。

新24/4评分和38crop像素复算均已终态完成：24/24存在，8旧widgets8/8文字恢复；middle逐字15/24、raw16/24、折叠空白20/24。正文primary11/11存在，空白／数字相同各9/11；实际body crop漏1号，3号在实际aside crop成功识别，不直接归神经忘字，不几何回拼提升主成绩。四边注文字／几何支持4/4，explicit parser edges0/4，wrongOwnershipProven0；未canonical采用。评分receipt SHA `a58a179d39e7b809fa3a13f082fdf88930ffb98f5f8a19adbd605fe032129075`，目录 `.local/state/oer-gct-full-output-independent-review-20261010/actual-v6-score-01/`；38/38 actual crops与含forms controls decoded pixels相同，crop receipt SHA `792404bf95565971ebc4933d60733a62d4eb9218572334b867ffecce817d86f0`、终报告SHA `deabc89723d7a4d025197f6a2658871731ab9e4281bc449312d7306d089b2888`。原件／raw／reference／旧评分不改，qualityAccepted=false／semanticQuality=unknown保持。

Paddle v2独立worker7/7准入＋wrapper适配限定审阅通过后，首个实际两页作业在模型构造前发生circular import而FAILED／pages=[]，不是成功OCR输出。Root execution SHA `1ac24b8c4da1b20a9707886087f66cb7048591deb607edbaa1398ad4212fff32`，目录 `.local/state/oer-paddle-complete-worker-20261010/root-gct-v2-execution-01/`；worker10.410767699824646秒／process11.893024199875072秒。真实forms输入两页保留；direct `GenerationMixin` import 的 generation↔transformers 初始化顺序正做新copied v3最小真实依赖红绿，不改vendor或旧证据、不调整GPU／FP32／预算／页数。此次模型forward0、没有内容评分分母；完整双parser仍未完成。

copied MinerU v6 的 10 个新增独立 cases 满足，准确准入 review SHA `273effec0494b492c73a5f06e66509d8cfa5fb54428f03458e81099cd571d51f`，目录 `.local/state/oer-mineru-v6-independent-review-20261010/run-no-model-v6-02/`。150 candidate／144 root baseline／846 installed pins／35 handle bindings 前后复算，819 既有 pins 不变＋27 PDFium code/native。58 作者 methods 未由独立审计重跑。candidate handle SHA `3846942a4634aa6b092ab8ab7ffefc8d1d7e192982dd6fdb69cc0c9865349b08`、freeze SHA `9ae2205ecbff098161075123fe9b983508210304e19104860c6f202100ae4f7b`；最终代码真实无模型两页 lifecycle 收据 SHA `b5619b36ee59d46ff360414f67b50555830ffb704c0cfbf60c9e56731eebb31f`，保留旧红绿和首次夹具失败，不把检查分母相加。

Root 新 `.local/state/oer-mineru-complete-worker-20261010/root-gct-v6-execution-01/` 已建立新 job／plan／admission（SHA `dea2f4c4df6959e5b5055300e64b07974a4bcbddb3edfe2fce4b9476e95facda`）并实际完成完整 GCT 两页，exit 0／worker completed。execution receipt SHA `1a17f11480329ce702e5f2583ed981673318560cb0af0ae2bc5b533218eba44f`，worker 54.32293110014871 秒、whole process 57.35423599998467 秒，1 秒 polling；38 attempts，postflightErrors=[]。捕获 complete／eligibleForCanonical=true 只表示完整终态可进入采用评价，qualityAccepted=false／无 canonical adoption 保持。设备／EOS／child forms／source/prepared/raw/capture 独立存盘审计和 24/4 新评分尚在进行。listener 前后 []，Preserved=true 仅空清单比较。旧 v3 FAILED 不改，FP32／token budgets 未调整。

新 wrapper scoped 14/14 和 prepare 来源导入前 scoped 7/7 分别满足；损坏 listener、非有限 worker summary 及候选源码漂移原 red 均保留，不因边界契约绿色宣称真实质量。Paddle v2 已 checkpoint（原25＋新增12分别绿、实际 forms full pages一致），独立准入／owned wrapper schema 适配尚在进行，Paddle 神经 GPU 实际作业仍 0。所有候选及实际捕获仍是本地开发，不是最终发布冻结。

## 较早增量：v5 离线审计结束，旧完整评分结束，新 forms 候选准备

MinerU v5 新鲜独立 34 unique 行为均满足（24 既有＋10 离线 capability），作者 50 methods 未重复执行，两个分母不相加。收据 `.local/state/oer-mineru-v5-independent-review-20261010/run-no-model-v5-02/review-receipt.json` SHA `5fc470647c38da7d8c40c1e08e16857b90c8905c74f87d2646bfe22e1174ec14`；决策 `READY_FOR_OFFLINE_INITIALIZATION_CONTRACTS_ONLY_FORMS_EXECUTION_HOLD`、`allowActualExecution=false`。main／单活跃 Python thread 前置检查闭合，不声称枚举 native threads 或通用线程安全。原 case06 literal 20/1 结果保留，其复合期望允许 actual failed，行为满足为 21/21；独立工具自身 schema 错误保留，未修改候选来迎合收集器。

第二次 FAILED 实际 GCT 的 24 regions／4 relations 诊断评分已全部完成。区域 16/24 存在、8/24 缺失；middle 逐字 12/24，raw model 逐字 13/24，middle 仅折叠空白 13/24。正文 11/11 存在，10/11 折叠空白逐字相同，数字序列 11/11 一致；四个边注缺失，四关系保持 unknown。两处弯撇号变直撇号、两页页号横线遗漏、header LF 被 middle 合空格和 page1 footer_version 早于 header 的扁平块顺序均原样记录。报告 `.local/state/oer-gct-full-output-independent-review-20261010/actual-02-score-render-01/QUALITY-REVIEW.md` SHA `ae67fd0722572ebf3f571f86f2e8e76e3bd94298722a8895e0c4ca04ab6c3984`；`score-02/quality-review-receipt.json` SHA `de9288dff464b5ffebc88f0c4890e931ba05c722b088e102da8b63073978b6e7`。参考未注入模型／索引，历史暴露开发集及 humanConfirmed=false 保持；无表／图，不虚构其质量分母，FAILED／eligibleForCanonical=false 不提升。

copied MinerU v6 正在固定 `forms-session-v2` 配方，Windows spawn 的实际 child 初始化 rendering document forms，parent 独立 inspect 同步处理。真实无神经两页 spawn red/green 已证明 8/8 populated widgets 恢复、green RGB 与独立 forms 全页逐像素相同，parent/red/green child 的 networkAttempts 均 0，pool／temporary／owned children 终态闭合；收据 `.local/state/oer-mineru-complete-worker-20261010/root-worker-v6-forms-session/actual-no-model-spawn-red-green-01/actual-spawn-receipt.json` SHA `1a7b83fd18faddd0e5204437651e309351c02df1af9b72231adbefa97145193f`。作者正补失败契约和 freeze，独立准入及模型实际执行尚未完成。copied Paddle v2 正做 forms＋offline 初始化最小修复，原 25 methods 已绿，实际无神经渲染／新锁仍在核验；未 GPU 执行／未落主。

新 root wrapper 保存已准入的 runtime lock rows 和全部 metadata descriptors，后验沿同基线比较；listener／身份／worker receipt 读取错误保留为 postflightErrors 和失败终态。该 wrapper 仍待独立复核，尚无新 job／plan／admission，也未启动新实际 worker。后续只能用 v6 的新鲜独立 allowActualExecution 绑定后串行运行，不能复用 v5 的 offline-only 收据。主模型继续 CUDA、正常 CPU 辅助按官方设计保留，不因本地 checkpoint 宣称最终发布包。

## 较早执行记录：第二次完整 GCT 已失败，保留输出做诊断

第二次 v3 作业已终态 `failed`，不再是待执行。运行目录 `.local/state/oer-mineru-complete-worker-20261010/root-gct-v3-execution-02/`；execution receipt SHA `8e6b7d2050592f2e7c97f511f07b78d2b027a4a82373c1174d72515e80f5286f`。两页 prepared 输入和 raw／model observation／assets／capture 已保存，runtime 记录 32 个 VLM attempts。最终 `validate_runtime` 在 `runtime-or-final-identity` 阶段拒绝：`Runtime has failed/swallowed attempts or forbidden network activity`。实际 `failures=[]`，`networkAttempts` 只有 `bind ('::1', 0)`、`status=blocked`；原运行没有保存这个调用的 stack，不能绝对追认调用者。worker 内部耗时 52.306988 秒，外层进程墙钟 55.919271 秒（5 秒轮询粒度）；vendor 两页推理日志的 42.034630 秒是子阶段，不能与外层相加。execution 的受保护 listener 前后均为空数组，`protectedListenersPreserved=true` 仅为空清单比较，不能称实际 PID 已保全。

capture `materialStatus=complete`、`eligibleForCanonical=false`、`status=incomplete`，两页 observed／requested parser indices 均 `[0,1]`，物理页 `[1,2]`；原因 `page_terminal_not_completed`／`run_terminal_not_completed` 保留。receipt SHA `2f7b9993f9b0b7b7c876a0a0cc05c7a4efc2c9ea018a44744609493051bd34a9`。raw 可作失败运行的诊断性内容评分，不能追认成功或发布 canonical。

root 随后在新鲜无模型进程复现完全相同的 bind 操作：准确 installed `urllib3.util.connection` 导入时运行 `_has_ipv6('::1')`，guard 阻断探测后库捕获异常并继续导入；heavy imports 为空。repro `.local/state/oer-mineru-complete-worker-20261010/root-offline-guard-diagnosis-01/red-receipt.json` 保存 stack 和原 source SHA。这是原失败归因的强支持证据，仍保留原运行未跟踪调用者的限制。

新 copied v4 `.local/state/oer-mineru-complete-worker-20261010/root-worker-v4-offline-capability/` 只在 guard 生效后、模型导入前初始化离线 HTTP capability：临时 `socket.has_ipv6=False`，导入 urllib3 后 `finally` 恢复宿主值，库 `HAS_IPV6=False`、transport 仍禁用；不允许 bind、不删除 `networkAttempts`、不放松最终 validator。拒绝已初始化的 urllib3，完整 36 个 urllib3 Python 源加入 lock。作者 48 个唯一方法通过（继承 42＋新 6），0 failure／error／heavy neural import／model／GPU／API／训练。最初 47 个方法中旧 inert prepare fixture 未含新 pin 的错误及修正保留。独立 own-copy 48 方法通过，但一个并发 witness 显示 helper 未检查 before-threads 前置条件，其他 thread 可短暂看到 global hint 变化，故 v4 为 HOLD；它未证明实际 one-shot 路径发生线程故障，不能重贴为原运行失败原因。并发 receipt SHA `85088cc485fa3555f491375b4427108379e2ceb35b3cecffc82ba0885bf02032`。后续 v5 只在修改 hint 前拒绝非 main thread 和其他活跃 Python threads，原 48＋新 2＝50 个作者方法全绿，冻结 SHA `203c02dd041dd0d1d8f116b8d66571faf6972d7551e973de949a1117b57ed2fa`，正在新鲜独立审查。全部候选未应用主源码／实际执行，非发布冻结。

另一个独立内容残差已完成原页像素定位：实际 middle 输出含 clauses 1–11，但 8 个 populated AcroForm 的显示区域没有输出，其中含四个 SCT 侧注。准确 pypdfium2 5.14.0 的 `draw_annots` 默认是 True，AcroForm 另需 `PdfDocument.init_forms()`。同原件两页／四配方实际 PNG 对照显示初始化 forms 后 8／8 populated widgets 全部恢复，未初始化时单独 draw_annots 开／关两页像素相同；worker 保存的两张 source raster 与无 forms 的 scale=2 渲染 decoded pixels 逐页完全一致。receipt `.local/state/oer-gct-full-output-independent-review-20261010/actual-02-score-render-01/render-diagnosis-receipt.json` SHA `a980225f15b0944bf82d7cc8c5f1bb3bad2225beb8495b85498ce2ed7ea8add8`，原件前后字节不变。docvortex session 在 Windows spawn child 内构造 PDF，parent-only constructor patch 不覆盖它；新 v6 正做 child 内、原构造后且取页前 init_forms 的最小接入，同时修独立 raster。未改 installed vendor、不 flatten／repair 原件、不向模型注入字段转录。

实际 `_prepare_pdf_window` 经 `load_images_from_pdf_bytes_range` 的页图供 layout 和 VLM 共用。原 v3 未保存有效 `DOCVORTEX_PDF_RENDER_BACKEND`，只能核默认 auto→session，不能追认真实 backend 已直接观察；新候选需显式绑定 session 与新渲染配方身份。32／32 实际 VLM PNG 已按准确 `prepare_for_extract` float-Pillow crop→PNG 从无 forms 的 200DPI 页图裁出并逐一 decoded pixels 完全复现；初始化 forms 的版本为 31／32，header crop 底部多出了项目标题的像素。缺失的 8 项没有独立 layout 块，故不是输出后投影丢字段，而是页图先漏外观再影响布局／识别。receipt `.local/state/oer-gct-full-output-independent-review-20261010/actual-02-score-render-01/actual-vlm-crop-reproduction-receipt.json` SHA `34e2f25641c13a67d4e1e55240456348b27895b8f434d2053741bdfeed3dcdf3`；新增 4 个 layout／VLM 接缝 source SHA 均与运行锁匹配，原件／raw／source 前后哈希不变。先前误用 docvortex JPEG crop 的不匹配保留为诊断工具路径错误。24 regions／4 relations 的完整评分与 failed 准入分开，尚不称修复后模型质量已通过。

Paddle copied v1 的新鲜独立最小审查同样为 HOLD：6 个独立 cases 中 4 符合、2 个接缝残差。147 candidate／144 baseline／1030 Python＋1 YAML 和模型／字体实际字节复验；inert 完整 11 页、尾失败保存和 EOS unknown 门控正常，没有重复跑作者 25 methods。`page_inputs()` 也在构造后直接取 len／页并 render，缺 `init_forms()`；全页像素相等只相对于同一不含表单外观的基线，不能称覆盖实际外观。Paddle guard 下 fresh urllib3 import 也复现同一 blocked `::1` bind，未 import 神经 Paddle 库。review `.local/state/oer-paddle-complete-worker-independent-review-20261010/run-no-model-v1-01/review-receipt.json` SHA `fedee110d90ebd00182f9170bb382c0f6233e779b06f4c7b3ce2ca1b5e1cc378`。两条接缝需最小修正后再新鲜独立核验，不准入真实 GPU；该结果与作者无模型绿色分别报告。

第二次实际运行的独立存盘审计已完成，目录 `.local/state/oer-mineru-gct02-independent-review-20261010/run-read-only-02/`，receipt SHA `9df15b99351e62b000b11e2f0c4eff987b10493d5612cfcc146dfc97ce793536`。原 PDF／prepared／两页 map、438 文件／217 capture 文件／CAS、32 attempts／32 图像／1069 native IDs（包含 EOS）均复算一致；8 个审计域通过不改变真实 `failed`。辅助 inventory 8 项中 detection 实际 11 forwards、layout 1 forward，均有 CPU 证据；recognizer 为 0 forwards，其余 5 项未执行、保持 unknown，不能从候选模块清单追认全部 OCR／table／formula 执行或整源质量。审计器第一次误用 max_completion 参数键的 7／1 原失败保留，修正审计器后新审计完成，没有修改实际运行数据。

## 最新：统一 CUDA factory 候选已完成两处实际调用

**后续设备规则修正优先：**用户允许经官方建议／实际收益支持的 CPU 辅助分工，要求灵活处理。本文严格全 CUDA 候选及旧失败保留为实验事实，不再把所有辅助层 CUDA 当唯一正式准入。候选未落主；独立审计已终态 HOLD，失败请求可能未记入终态、CUDA telemetry 容器／shape 未严格校验、公开 getter 可泄露 raw session 三类通用完整性／生命周期 P2 保留。这些问题与是否合法采用 CPU 辅助分别处理。当前先用官方支持的辅助 CPU／主 VLM CUDA 完整 worker，不继续为全 CUDA 加码改图。独立目录 `.local/state/oer-mineru-cuda-independent-review-20261010/run-124319-4293/`，report SHA `54f3a72269834c881b9b6d96f4ed15a03c38011350b2150e3aa6adf898598e90`，receipt SHA `c1b68db495b2aef05c5b5966b4b5554ed25e1609531ea0f44ec3ad00290d217f`；20 个独立合成 cases 中 12 符合、8 违反，collector exit 0 不是通过结论。

准确 [MinerU 4.0.10 README](https://github.com/opendatalab/MinerU/blob/f232373fb319d63e82aefd30be40a2ccc6005d73/README.md) 明写 default install “small models run ONNX CPU inference” 为广泛兼容，同时推荐 NVIDIA 用户安装 `mineru[full]>=4.0` 获得最佳吞吐。这支持正常 CPU 辅助／GPU 主模型分工，不支持“有 GPU 时 CPU 辅助一定更快”的结论。官方 wheel SHA `9731eb8afb9756bb2e3a8e3e6a69252786434602eeb4ff36f7e7a5f2aa8ac107`，通用 factory 源码与 installed 字节一致（SHA `e107f4ce5dc156ca3181ed6a7280ce0740e0e0ced2b35f9cd3bed0f3545a3be3`）；表格另有独立 device 选择和 fallback 日志。原始下载／源码证据保留在 `.local/state/oer-mineru-official-device-review-20261010/run-124458-fedd29/`。本轮只核源码／文档，未安装 wheel、未加载模型；完整 worker 和设备选择的实际整链效果仍待验证。

2026-10-10 后续版本纠正及用户决定：早前链接的 `c221cc41...` 实际 version.py 为4.0.9，README内容与已核对4.0.10字节相同，今后使用上方4.0.10链接；旧下载及误版本探测保留。准确wheel METADATA与installed字节相同，4.0.10固定源码commit的四组Win/Linux torch/full extras也与wheel相同。用户已决定H800以 `mineru[full]==4.0.10` 为目标，Linux需要Torch extras及vLLM>=0.19.1,<0.29，Transformers>=5.10.1,<6和accelerate>=1.5.1；当前Windows TF4.57.6／缺accelerate的旧worker未满足该配方。repo direct候选输入见 [requirements-mineru-linux-full.in](../../tools/vetting_eval/requirements-mineru-linux-full.in)，不用于升级现有Windows环境，也不是已验收的依赖锁。

`full` 仍保留ONNX表格且不会自动添加onnxruntime-gpu；新worker需要准确Torch小模型资产、显式small_backend=torch／主模型Linux vLLM、实际device／dtype／token／fallback记录。不能从extra名称推断所有辅助CUDA，也不能把旧v6结果改称full。细节和准确来源记录见本机 `.local/state/oer-mineru-full-dependency-review-20261010/read-only-01/MINERU-FULL-DEPENDENCY-REVIEW.md`（SHA `8cd7fecb249bc515164a8ce2e4c986c21e66e1223db1bf8202fb73c61fb245cd`）；新独立Linux环境、Torch资产及full完整作业尚未实施。

用户最新顺序要求优先本地算力／入口、架构和 OER 代码／模型调优，尚未到最终冻结打包阶段。本节是本地入口准备事实，不改变完整 parser 和质量验收状态。

完整 worker 在 `.local/state/oer-mineru-complete-worker-20261010/run-143200-worker-v1/` 的 copied-tools 实现：显式 Standard／OCR／image_analysis、`page_range='all'`、ONNX 辅助 CPU／table CPU／主 VLM CUDA，实际 prepared PDF bytes 和全 outputs／assets 进入 capture，capture 仅为候选而非 canonical 自动采用。作者最终 33 个无模型 test methods 通过（23 新 worker／runtime＋10 capture，含 13 页、终态／失败／取消／资产／实际设备观察契约及 DOCX rendition 身份）；heavy modules imported=[]，0 GPU／训练。freeze SHA `bb6ebd72780fb4bdadff84c61972eab28e155a6655118db0fd877b6942a88d96`。随后独立审查已终态 HOLD：21 unique witnesses，14 符合／7 违反，对应 5 类 P2，见下表；未应用 root、未真实 GPU 执行。root preflight `.local/state/oer-mineru-complete-worker-20261010/root-gct-preflight-01/preflight-receipt.json` SHA `65272bee7596c98179d3d05bb6eb1c6d73b79314b98a1b330b70420626142799`，实际核对 147 copied 及 144 原 top-level 条目、783 installed runtime 条目、26 原模型资产，没有加载模型。APB 原件实际 61 页，旧第 3 页探针不能称完整 APB 原件；首个完整作业计划用真实 GCT 两页。

| v1 独立问题 | witness | 当前处理 |
| --- | --- | --- |
| prepared rewrite／pruning／重复 callback 的异常被 backend 捕获后仍完成 | 03／04／05 | v2 持久记录失败并最终核实际 prepared 字节及完整 map |
| 失败／矛盾 renderer receipt 仍声明 verified derived PDF lineage | 06 | 仅按实际 renderer v1 schema 的唯一 source／PDF 路径、SHA、状态、页数和 unchanged 关系核验；DOCX 专属 |
| asyncio.CancelledError 被记 failed | 08 | 沿既有 cancelled 生命周期修分类 |
| 正常 CPU event 掩盖缺 provider 的计算 kernel Node | 15 | 计算 provider unknown 不准入；host fence／control 不误作未知计算 |
| malformed base64 staging 在 try/finally 外，attempt 永久 running／无 receipt | 21 | 扩现有终态保护范围，保留真实失败；不改变已拒绝的准入结果 |

独立目录 `.local/state/oer-mineru-complete-worker-independent-review-20261010/run-131009-3505f2/`；report SHA `37d03dd9582468940626b7c622fc7547a199fb1b914955d294d87e3e9b5712a0`，receipt SHA `5efb3f2819f3378c45efd09c12d502bef5c441c0483943ce6267a86035d2ad11`，probe receipt SHA `09d8e32514aec84799276f0086dd1164f51b09eabd6613b851924b72dcfb8304`。Standards 0 hard／0 actionable smell，Spec 5 P2；审阅 0 heavy imports／GPU／API／训练／root writes。原 21 witnesses 继续复用，case06 旧象征字符串 matcher 单独核合法结果集合，原始 v1 失败与 matcher 都保留，不改变分母。worker elapsedSeconds 不含最终 artifactInventory 哈希／receipt 写入，真实运行另保存外层进程墙钟。

后续 v2 修正五类门控及实际 prepare callback 的 self.evidence，原 21 行为＋2 实际 closure 独立通过，但另 1 普通 terminal receipt I/O 故障仍可能被 vendor 捕获并以成功重试掩盖，继续 HOLD。v2 receipt SHA `548d088fa845118fb6ad7094a194eac57c341e321186819ca557ef47a8106322`，原失败不追认通过。新 v3 仅包住既有 finally write/event 的 sticky failure/rethrow，不重试、不改输入／设备／token／parser；作者 42 methods（32 worker/runtime＋10 capture）通过。新鲜独立实际三组分别 21／21 行为、2／2 真实 prepare closure、1／1 普通 terminal I/O，通过共 24 unique witnesses，不与作者分母相加。v3 freeze SHA `5a8fa10c2af832bae619cdd1c4af209497cd99e6b947c368b9d66e33ae505f82`，readiness receipt SHA `9c45a4e6b265a43c3eaf025204996e88f7e21c859206e08ff8282065693543be`，长报告 SHA `71afb63c34d57d0436f75462bc7148dd5cea6fa9a8829723191e4bcf7b9bb343`；仅准入 root 新鲜资源/源码/设备核验后的一次 GCT 实验，不等于模型质量。

v3 首真实 GCT 作业已经保留为 failed：`.local/state/oer-mineru-complete-worker-20261010/run-173000-worker-v3/actual-gct-two-pages-01/`，错误 `Model member escapes snapshot`。旧 raw model receipt 的 11 个 HF cache 链接 resolve 到共享 blobs，被现有 containment 正确拒绝；发生于模型加载前，不计 OCR 内容错误。worker 内部 0.0126242 秒、外层进程墙钟 5.0922864 秒（5 秒轮询粒度），execution receipt `.local/state/oer-mineru-complete-worker-20261010/root-gct-v3-execution-01/execution-receipt.json` SHA `8e0204aa79116e53663aeedd18c1d77d51b94c757d51feb94f1151ab6a362bb8`；保护 listener 前后均保持。没有改变 containment 或原件来通过。

root 随后将同一 11 文件／2,328,011,610 bytes 导出为独立常规文件，每文件流复制、源前后及导出后 SHA 与原声明一致；small 15 个原常规文件也完整复验。目录 `.local/state/oer-mineru-complete-worker-20261010/root-gct-regular-model-assets-01/`，regular receipt SHA `f1c86bc917694d923aafaa3c19ad41f28da0817678830dac15d0c47419cc3eaf`，export validation SHA `d3cc62068ee047100999137452dae823ebeb35ce8ea3a5e36b2fa2d7c869f596`。这是本地开发字节重定位：保留旧 upstream 已知／未知字段，未增加 Git／LFS 认证，非最终发布包，0 model/GPU/network。新 job `.local/state/oer-mineru-complete-worker-20261010/root-gct-v3-execution-02/gct-job.json` SHA `ccb110597958d6d93786bad96dbab5ec6e61db8d7fa38e8c331e8603a2d6a9f7` 仅改 modelReceipt，source／lock／device／token 不变；新 output `actual-gct-two-pages-02` 待第二次准入运行。内容 24 regions／4 relations 目前全部待评，不因资源修复而变为 OCR 正确。

GCT 完整可见参考与原生表单观察在 `.local/state/oer-gct-full-reference-20261010/run-131000/`：实际原件 SHA `ca3944c16890cb5ea7f8b24e2fab613c7ffd621809f7f850529901649e6f15f8`，v3 reference SHA `6705a43840f32f633f15e6de4c2248d7adebd8a27c23c1e42f9ef753532fcaa3`。第 1 页精确复用历史 v2；第 2 页完整原栅格转录的 12 个区域由独立 Codex 对照全幅／3 crops 核对，0 文本或结构归属错误。独立 receipt `.local/state/oer-gct-reference-independent-review-20261010/run-125604-caac8a/reference-review-receipt.json` SHA `effd738ecd16be82abc0348fdbee2173b16fd3f273c20ace0f576f3024c175b6`。该开发参考具有历史暴露，非盲测；不会注入模型输入／生产索引，humanConfirmed=false／wholeSourceAcceptance=false 保留。

新参考 v4 在 `.local/state/oer-gct-reference-reader-20261010/run-v4-141900/`，SHA `58a622592287f53c7e667ab7c4ebe8d07201adf944379968475175616e5e2211`；仅转为现有 source_reference v2 envelope。2 页／24 regions／4 relations 经逆投影精确一致；document-wide ordinal 明确保留 legacyDocumentOrdinal，page reading order 单独声明。root 现有 reader＋validator 核对实际原件与两 raster bytes 成功，缺失 role 保持 unknown，不把 integrity 升级为模型质量。两次 helper 错误分别是 page-local ordinal 假设及误用 reader 参数，失败记录保留；外部逆投影 25 项全部通过，原文本／ID／metadata／relations 及 6 个受保护路径 SHA 不变。独立目录 `.local/state/oer-gct-reference-v4-independent-review-20261010/run-130441-b24482/`，receipt SHA `282eaf7a8b4e6fb8f6642e62c419641dd294198fe736b818e3a087aaf6cc8a76`。

原生表单→可见外观独立核验 11／11 一致，10 个 roots／12 个 field-tree 节点／11 个 page widgets 全可达且 Parent 同树。8 populated widgets 可见文本匹配，3 个有效 `/V` 缺失且无正常 AP 的字段没有显示字段值；两页 footer widgets 66／16 本地无 `/V`，继承 parent 18 的 `20250101`。Clause 10 原值尾 CR 原样保留，视觉比较单独归一化显示空白。空 Contract Number widget 63 的 rect 下边缘包含静态 clause 1 字形，不能拿矩形正文当字段值、不能说整个 rect 白。独立目录 `.local/state/oer-gct-native-visible-independent-review-20261010/run-130044-42c5eb/`，receipt SHA `c2ca1690677c3eb8a657bf3a20585e46a5df6c6a5ad5cab33ea34b703b28d01c`，report SHA `dedd311a25fd36325bdc786f03f682bac0690e2f7ae7ec485a03028a4f36630f`。原件／作者 observation／reference 不变，无 repair／flatten／模型／训练。只验当前 Poppler 原外观，不追认未来 fill、跨 renderer、法律或整源 parser 质量。

候选目录 `.local/state/oer-mineru-cuda-adapter-20261010/run-121307-a28681/` 准确新增 `mineru_cuda_runtime.py` 和对应测试，未改原 vendor／父模型／root。29 个唯一 authored contracts 通过；真实 fresh process 先 install adapter 再 import 通用 layout 和 SLANet table callsites，串行处理同一 APB 第 3 页完整 300DPI raster，实际 2 sessions／2 forwards，exit 0／PID33340 已退出。实际 receipt SHA `d07e63d5ca5fe70d46c1133f2f23b4a6132127e72371b61ad277d257b19b4baf`，`finish()` receipt SHA `0459555f25e5661d950a6e785ae035ad900075ce5404aab01a608742daaa536c`。作者读取保存证据复算，无新增 forward；后续独立 HOLD 结论见本节开头，仍未应用 root。

实际 layout 的 CUDA 浮点事件 1,310、已审核小整数／布尔 CPU 控制事件 176（最大单 tensor 5 元素）；SLANet 对应 11,714、2,125（最大 4 元素）。两路 CPU 浮点／未知 provider 均 0，SLANet 189 个 OneHot 均在 CUDA。Layout 与冻结同输入保存结果相等；SLANet 两组 raw FP32 arrays 位级相等、194 structure tokens 和 102 bboxes 相等。以上数字逐项在 actual receipt、两份原始 profile 与作者复算收据中保留，不能当完整 OCR／公式／VLM／parser 或原件质量、吞吐通过。Nominal vendor `device=cpu` 标签及 ScatterND warning 保留，实际设备按 profile 判断。

入口候选禁 ORT constructor 内部及运行 fallback；必须在六个已知 factory import 前 install，CPU／auto 明确拒绝，派生 SLANet 只按准确父 SHA 绑定；`finish()` 之前不得采用成果，失败保留 raw/profile，完成后拒绝 session 继续调用。当前下一步是 copied 完整 MinerU worker 接口与无模型契约，再按审计结论准入 GPU 完整运行；不以这两 callsites 成功追认公式或全 parser 已接通。

## 已确认缺口

当前 `.local/oer/venvs/mineru-gpu` 安装的是 MinerU 4.0.10、Torch 2.11.0+cu128 和 ONNX Runtime 1.31.0 CPU 包。实际 import 返回 available providers 为 Azure／CPU，没有 CUDA。该 vendor 的通用 `ort_providers` 也固定返回 CPU，`ort_session` 忽略 CUDA 请求；表格工厂单独支持 CUDA，但遇到不可用／初始化失败仅警告并回退 CPU。因此，VLM 上 CUDA、设置 `MINERU_DEVICE_MODE=cuda` 或 `MINERU_TABLE_DEVICE=cuda` 都不证明完整布局／表格链已经符合当前正式规则。

源位置为 `mineru/model/runtime/onnx.py` 和 `mineru/model/layout/pp_doclayout_v2_onnx.py`。后者还将 nominal `self.device` 写为 cpu；后续正式适配必须依据实际 session／推理设备记录，不能只修改或读取这个标签。

## 隔离依赖与实际调用

全部实验使用新的私有环境层，仅只读复用旧环境的其他依赖，不修改旧 venv、vendor 安装、权重、原件或在线服务。它们是本机诊断环境，不能复制 Windows venv／共享 `.pth` 当 Linux 离线发布包。

| 探针 | 实际结果 |
| --- | --- |
| `run-111955`：新装 `onnxruntime-gpu==1.31.0` | 包已安装且列出 CUDA provider，但实际初始化要求 CUDA13。与现有 Torch cu128 的 DLL 组合不能创建 CUDA provider；拒绝 CPU 回退的会话失败。旧失败、receipt 和日志保留。 |
| `run-cu12-112700`：新装 `onnxruntime-gpu==1.26.0` | PyPI 元数据声明 CUDA12／cuDNN9依赖。实际同一原页 CPU baseline 完成；CUDA会话在“完全禁止任何CPU节点”的额外探针中拒绝图内CPU放置。 |
| `run-cu12-profile-112945`：CUDA12实际算子轨迹诊断 | 在明确允许记录CPU图节点的私有诊断里，完整一页布局 forward 已执行，1479个CUDA节点事件、176个CPU节点事件。严格零CPU诊断仍报告失败，不将诊断视为正式完整链通过。 |

后两轮依赖／输入相同；图节点策略是诊断变量。第一轮CUDA13与CUDA12依赖不同，不当作纯设备加速对照。上述 CPU baseline 只用于隔离复现 vendor 的错误设备选择，不是正式 OER 的 CPU 服务配方。

输入来自真实 APB 第3物理页的完整300DPI派生PNG，不读 gold、不裁块、不注入参考答案。原件 SHA `37097f68a95390b2cf7a8b4f81286954e13a337b3924b4032bba72321fc22674`，PNG SHA `28fe45108a4a7f486d52c5045042dd51526d9eb6db23549566f77d8694833402`；model来自已保存的 MinerU-4 ONNX snapshot `358310b4f64b95f9fefc372ad899356e4111f376`。各轮调用前实际核对原件、PNG、layout权重及配置字节，完整运行收据位于 ConSense 根 `.local/state/oer-full-parser-cuda-readiness-20261010/` 下的同名目录。

完整CUDA12轨迹保存为 `strict-cuda-layout_2026-10-10_11-29-26_081.json`，SHA `d31f9d313c83b73e5efc391892dc88f31af286f6dc52187f674418e3d1491964`。从实际事件复算，卷积、MatMul／FusedMatMul、Softmax及归一化等浮点模型计算在CUDA执行；176个CPU事件的全部输入类型只有int64／bool，单个输入最多5元素，用于形状／控制信息。本轮没有观察到浮点模型计算落CPU。该事实不外推到其他布局模型、表格、OCR、formula、不同输入形状或完整parser。

“能用GPU的模型计算用GPU”不等于禁止文件读写、预处理、后处理或所有host形状控制；完全禁止任何CPU图节点是本轮用于识别放置行为的额外严格诊断，不能将其失败直接当作模型能力或原件质量失败。正式方案仍须拒绝整个模型／可加速数值计算退回CPU，真实记录辅助模型及其输入的执行设备。仅看到 CUDA provider 名称不足以证明已运行 CUDA。

## 接下来的接入及验收

### 后续实际辅助模型调用

新私有 `run-auxiliary-114800` 复用同CUDA12隔离环境，在process内、vendor callsite import之前注入显式CUDA session工厂；安装目录未修改。session禁运行异常fallback，创建后核实际CUDA provider，保存每次原始算子profile。输入仍为同APB第3物理页完整300DPI图，不输入参考答案。六case／七ONNX sessions的vendor预处理、forward及后处理全部完成，进程exit0；不含VLM和公式调用，不等于完整parser运行。

| 实际session | CUDA节点事件 | CPU节点事件 | CPU观察 |
| --- | ---: | ---: | --- |
| layout | 1479 | 176 | int64/bool，最大5元素 |
| OCR检测 | 190 | 0 | 全部观察节点CUDA |
| OCR识别 | 8789 | 94 | int64，最大4元素 |
| 印章检测 | 270 | 0 | 无印章质量标签；只证明同原页实际forward |
| 表格分类 | 102 | 2 | int64，最大4元素 |
| 无线表格slanet-plus | 27031 | 2314 | 含189个OneHot浮点输入／输出事件，最大50元素；需要单独判明放置原因 |
| 有线表格unet | 198 | 30 | int64，最大4元素 |

节点事件按原始profile复算，包含重复forward，不是模型层数或准确率分母。layout／OCR／seal／classifier／wired本轮未观察到浮点模型CPU节点；无线表格的189个OneHot都有float[2]值输入和float[1,50]输出，不能将其全部称为整数形状控制，也不能仅凭CUDA provider或大部分节点CUDA宣称整路满足正式规则。它是当前剩余执行设备审计项，不能静默忽略或立即硬改图。公式尚未调用，当前页没有确定的formula target，不制造监督样本以追认覆盖。

所有固定辅助模型／配置字节前后核验相等；原件和PNG前后SHA相等，vendor runtime文件前后SHA相等。完整OCR基于该页的detected crops执行识别；表格分支使用完整页，只评运行设备，不将其当已校准的自动区域路由／完整表组质量。实际前后显存观察6292／6223MiB不构成峰值或因果节约测量；自有进程已退出，无常驻新模型。

各case完整输出、七原profile、`auxiliary-receipt.json`、`operator-placement-audit.json`及两份实际执行脚本在ConSense根 `.local/state/oer-full-parser-cuda-readiness-20261010/run-auxiliary-114800/`。receipt SHA `1ccd9d69efb85f4d80c945f412adb323c051280e085a794f2b424627052fd5c1`，placement audit SHA `b94ed6b74200369238dffa1d4382342d041217f6117061a7a87bf7cb141ab093`，probe源码 SHA `5c381fafeb6c8a6a156e8cf4cc97cbb2e3279a8d8ebeb328e606ca68b793345d`。LLM、训练、质量接受均0。此次process factory注入是受控诊断；尚未接入正式入口、不复制成Linux已验收发行包。无线OneHot原因的独立审计另存，失败／unknown保持。

### 后续：无线表格 OneHot 最小派生与同页 CUDA 对照

独立审计确认 slanet 的 OneHot indices 为 INT64、depth 为 INT32、values 为 FLOAT；当前 ORT 1.26 的 CUDA 注册不覆盖这一组合。新候选只在 `main/Loop.0/body/OneHot.0` 的 depth 输入前插入 INT64 Cast，保留原 Constant 和其他消费者。撤销这一 Cast 及该输入变更后，deterministic protobuf 与父模型精确相等。父权重和 vendor 安装未修改；派生模型另存并绑定父 SHA、配方、依赖及新 SHA，不能作为原 revision 的字节使用。

对同一 APB 第 3 物理页的完整 300DPI 原图执行两次 vendor TableStructurer forward，预处理 tensor SHA 相同。保存后离线复算确认：

| 观察项 | 父模型 | 派生候选 |
| --- | --- | --- |
| OneHot 实际 provider events | CPU 189 | CUDA 189 |
| CPU 浮点 events | 189 | 0 |
| CPU 单 tensor 最大元素数 | 50 | 4，均为整数控制 |
| 原始 FLOAT32 数组 `[1,190,8]`、`[1,190,50]` | 两路逐字节相等 | 两路逐字节相等 |
| 194 个结构 token、102 个 cell bbox | 精确相等 | 精确相等 |

原／派生 basic checker 和新增 Cast／OneHot 的局部严格类型、形状检查通过；两图的全图 strict shape checker 都保留父模型既有 `Cast.64` rank 冲突失败，未扩大修图。这是一个完整原页的数值和设备对照，未加载 gold、未训练，不证明自动表格路由、全表质量、其他页／batch、公式或完整 parser 已通过。节点次数不是准确率分母，也不据此报告吞吐提升。

证据位于 `.local/state/oer-full-parser-cuda-readiness-20261010/run-slanet-depth-cast-115653-f62323/`：`freeze-receipt.json` SHA `6b8d20c7e6663f076d90f93ab2e9cabe130670f4f272cb3f102b4c3bd3207be4`，`comparison-receipt.json` SHA `9b21d9ee07f4984ba9ee89a3bf2532f037132e82c4ed38b2d6bc9f765c81a73a`，离线复算 SHA `aa4b7e3254d7c674c5108b4e4d8fe8e04ec707bf7bea52e29b0080d9022b81c0`。父模型 SHA `d57a942af6a2f57d6a4a0372573c696a2379bf5857c45e2ac69993f3b334514b`，派生 SHA `abcc3dccc8f6556df0cfef6515fc6f48524c4b4f0f096a0cd3c60a41f9fc4eca`。两个 forward 所属进程已退出；本段候选尚未接入 root 正式入口或目标 Linux／H800 包。

### 仍需完成的准入

1. 将完整 parser 的可加速辅助模型纳入正式启动准入与实际设备记录，避免 vendor 默认CPU及 warning fallback；兼容依赖和适配代码在本地确定后冻结，保留完整第二路。
2. 使用实际布局／OCR／表格／公式调用分别核验 CUDA 数值计算和必要host控制；当前覆盖同一原页的七个辅助 session 和无线派生的数值对照，公式、完整 VLM＋parser、其他输入／batch 及 Paddle 完整 CUDA 仍未闭包。
3. 完整页／跨页表组按既定原件、坐标、raw、采用及 unknown 规则保存，再完成 parser→canonical→检索→原图回取的真实链路。
4. Linux目标依赖、辅助模型、DLL／共享库对应组件、权重和完整入口一起冻结，进行无网络冷启动、同版成果复用及真实换版处理演练。以上 Windows probe不证明目标包已制作／扫描／上传或 H800通过。

LLM调用0、模型训练0，未提高任何来源质量状态；正式主服务未切换。GPU forward探针已经退出，不保留常驻模型。
