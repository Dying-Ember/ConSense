<!-- current-consolidation-and-retirement-20261015:start -->
**当前代码/本机环境阶段已收口，按用户要求停止在效果验证之前。** 最终 Java full09 为 2,706 通过/0 failures/errors/123 既有外部条件 skipped，另有两历史重放类及两方法显式排除；Python recovery06 为 1,636/1,636、独立归档恢复 6/6；前端 166/166、五脚本、类型检查、构建及本机依赖检查通过。50 个 refs（49 个分支引用及 `origin/HEAD`）全部已覆盖。九项退役不计通过/跳过；旧失败与未执行门保持，详情见[最终实施记录](testing/consense-integrated-implementation-20261015.md)。不自动开展真实模型、原件、SL、端到端或目标部署验收，完整质量目标未 complete。

**2026-10-15 最新补充：先完成当前环境/代码自动化，统一代码分支，退役旧生成数据重放依赖；效果验证仍不开始。** 用户已确认未手工保存旧资产，并要求清理历史生成数据。旧兼容 receipt/core 回放 8 项及历史 64-query capture 回放 1 项已按此新指示明确退役，不计 passed 或 skipped；原失败紧凑记录保留。缺失资产没有实体可删，手写源码及生产可选兼容校验没有删除或放宽，当前自足的身份/SHA/AST/缓存失效保护继续验证。全部本地及已获取远端代码分支现统一到 `codex/consense-integrated-20261015`；逐分支核对后保留已有较新实现并补齐合并关系。赛方原件/gold/固定基线、活动服务和数据库不因分支整合切换；完整 OER 质量门仍未验收。
<!-- current-consolidation-and-retirement-20261015:end -->

<!-- automated-validation-only-20261015:start -->
**2026-10-15 最新用户边界：环境与代码实现的自动化测试继续完成并要求绿灯，效果验证暂不开始。** 包括本地代码回归、类型检查、构建及独立代码审查；赛方原件正常流程前后效果对照、模型效果评分、SL 版本比较及其后的业务效果验收全部暂缓。失败、缺精确历史资产和中断原样保留，不删断言或 skip 换绿。该指示覆盖下方“自动化后立即进入原件效果验证”的执行时序，不缩减原 OER 规格、完整目标或质量门。
<!-- automated-validation-only-20261015:end -->

<!-- integrated-implementation-authorized-20261015:start -->
**2026-10-15 用户已明确授权统一 spec 代码实现及自动化测试，目标北京时间今日 08:30。** 新指示覆盖下方本次规格编制期间的 spec-only／不启动 Goal 表述；原 08:30 目标已错过，原截止 heartbeat 已 PAUSED。本轮适用代码/本机环境自动化完成后按用户要求停手，效果阶段不自动开始；完整目标未 complete，本次 Goal 工具返回无活动目标。原计划的首要真实核验仍为赛方示例原件正常 OER 修复前后效果（同范围／同分母／旧正确），须待用户恢复效果验证后才执行；只有显著提升再做后续验收，SL 对比后置。端到端／浏览器验收先略过，不追认为已过。实际部署／活动服务或 DB／fixed05／Drafting／gold／权重发布边界保持。开发隔离分支为 codex/consense-integrated-20261015，既有状态、失败与未过门保留；详细当前进度在本地 .local/state/consense-implementation-20261015/execution-state.json，代码／契约与真实来源分开。
<!-- integrated-implementation-authorized-20261015:end -->

<!-- unified-spec-entry-20261014:start -->
> **2026-10-14 当前总体实施入口：** [统一实施与验收 spec](specs/consense-oer-vetting-advisory-integrated-20261014.md)及[统一任务图](specs/consense-oer-vetting-advisory-integrated-tickets-20261014.md)。OER 原 D1–D10／EAC01–22／ENG-00–10 按[受保护引擎子规格](specs/oer-engine-integration-repair-20261014.md)完整保留；产品问题 PRD01–08 与旧总 AC01–20 在统一入口逐项承接，业务成功不关闭 OER 来源质量门。下方原正文与状态保留原语境，已实现起点不重列未修，未验收／失败不追认通过。本次只调整规格与导航，Advisory 已停止测试保持，不启动实现、模型、训练、索引、自动 Goal 或部署。
<!-- unified-spec-entry-20261014:end -->

**当前引擎修复规格（2026-10-14）：** [整合与来源保真 spec](specs/oer-engine-integration-repair-20261014.md)及[ENG 本地任务图](specs/oer-engine-integration-repair-tickets-20261014.md)已发布为 ready-for-agent，沿用已确认的正常上传／解析／来源回取验收接缝。先完成观察先行、身份与阅读规则、布局传输、按区域补读和完整结构消费；本次仅写规格，不启动后续实现／训练／部署。主／交付首批修复、未验证门与 SL 后置状态在规格内分别记录；下方旧状态按历史读取。

**2026-10-14 引擎整合第一批修复已验证；整条自动解析链与真实来源质量尚未验收。** 已将主工程和独立交付的最终scope/role判断从文件名及展示fileKey移到显式用途和绑定原件的身份；未知文件保留原文与目录，显示身份待确认，确认前不进入可信业务源集，重复filename排除复用共同scope。用户显式范围、已确认答案/模板隔离和历史fixed05保持。旧绑定若仅靠旧默认角色，会保持pending/stale，未改活动数据库或重建索引。

独立交付另修PDF metadata图纸提示：弱提示仅形成UNKNOWN候选并保留原始Title/Subject；无确证时在详细parser前进入IDENTITY_PENDING，原件与SHA保留、页数未知，不冒称blank/成功解析/已确认图纸。显式drawing及CAD强格式排除保持；可信exact-byte非drawing身份可覆盖弱提示。两个scope消费端均拒绝pending由显式tender/package绕过。无metadata提示的真实PDF图纸自动身份仍未完成，主工程未虚称新增该自动能力。

验证：主工程87项、独立交付112项相关契约/内存数据库测试通过；每仓另19项canonical测试在JDK回环连接初始化失败，业务断言未执行，保留unverified，不报全绿。两仓前端类型检查及三语组件检查通过。原件字节登记与刷新测试含实际原件+人工parsed-state fixture，不是完整来源新解析质量。未调用真实模型/训练、未部署或发布权重、未核活动服务构建或浏览器验收。证据：主workspace`.local/state/oer-engine-integration-fix-20261014/root-result.json`。

架构调查主线已明确；下一批复用现有ParsedDocument/DocumentBlock与canonical结构，打通初步native/OCR/layout观察→Java身份/版本→阅读规则→按缺口补读→同源坐标/完整组组合。旧/ocr已有文字bbox，但上游未提供完整布局及原始polygon/来源契约；该接缝尚未接通。SL多版本工作按用户最新指示延后到引擎修好以后，目前仅目录登记及初步元数据探查，未下载官网大PDF、未启动全页版本比较或加入训练。以下既往记录按各次历史状态读取。

**2026-10-14 用户新增通用性与版本隔离规则：解析与路由不得把 SL 固定物理页、附录代号或本次样本答案写成跨来源判断。** 文件名、PDF Title/Subject 是身份候选；仅凭这些弱线索不能终局排除整份文件或确认其标准/项目事实角色。身份确认应保留实际内容/格式证据和版本来源；用户显式范围、2d/2e答案隔离、可信原件SHA注册继续有效。已冻结且原件SHA绑定的历史页范围只用于该版来源范围/库存校验，不推广到其他版本；人工source-reviewed框、读序和关系属于显式采用，不能冒充新原件自动能力。

本次主工程与独立交付只读审计及隔离CPU复现已证实：文件名可直接改变业务源集排除/角色，交付版PDF metadata可触发整份drawing跳过；另有VettingService非canonical文件名过滤。上述产品分支尚未修复，未部署。已检查通用入口未发现“SL第几页/APA或APC必为图纸”的算法分支；1127..4300命中为冻结基线库存guard，不删除旧证据。证据：`.local/state/oer-engine-hardcode-audit-20261014/root-audit-summary.json`及同根CPU原始输出。

用户另提供April2025 Batch3 SL并授权收集官网可得全部SL版本作对比。各原件按实际版本和SHA隔离，保存官网目录/下载来源/失败及不可达状态；旧基线不覆盖，不把相近版本或相同条文随机拆入训练与评测。新取得资料先留版本对照区，训练/校准/评测用途须在同源重复检查后另作明确登记；本次资料收集与原生结构对照不自动增加训练或质量分。

**2026-10-14 用户明确要求按整套解析引擎重新审视架构和数据流，先整合既有能力，再按真实残差决定训练。** 当前架构包含PDF原生文字/坐标/表格工具、Word POI/OOXML、PaddleOCR-VL1.6与PP-DocLayoutV3、MinerU2605及其布局/识别辅助，以及独立交付中的Camelot/pdfplumber；不能把全引擎概括成纯OCR或整页自由JSON微调。完整标题、表单编号、页内栏目和版本可作为文档族与子类型阅读规则依据；规则定义字段角色、锚点、读序、分支和关系，不预置当次原件值或未填状态。

本次已只读核对16个源码身份及已有真实运行证据，未调用模型/HTTP或启动训练。已有双解析器规范化、整区域/整表组选择、canonical units/parts/relations和下游model material应优先复用。已证实的当前接缝包括Java先parse后登记身份、扫描OcrResult仅text/confidence/lines、独立交付与主工程表格投影能力不同；完整parser的已有成果不能自动视为旧/ocr入口已接通。下游embedding读取原文窗口，业务模型消费选定完整来源单元和必要结构/引用；完整glyph/operator/CAS/软件审计保留在证据层。

后续最小实施优先打通标题/身份观察→阅读规则→原生/视觉任务选择→同源坐标与结构组合→完整语义输出及正常消费。近期整页RGB+prompt生成全字段JSON的训练不作为整个引擎主实现；只有工具/规则后仍存在、且有完整合格监督的残差才安排相应模型训练。本次仅架构核查与方法记录，未实施上述改造、未授新增质量或泛化；完整原适用范围与验收门保持。Root实际Goal工具已读回paused，未调用complete或暂停，自动Goal工作保持停止。

证据：`.local/state/oer-engine-flow-review-20261014/architecture-dataflow-review.json`。下方R158及更早状态按历史读取。

**2026-10-14 R158 当前状态：状态真实数据与参考修订已独审；独立校准家族仍缺，Goal继续active。** 本轮为真实数据和方法调查，0模型/GPU调用、0特征提取、0训练更新、0新增质量点。既有已暴露七页target仅1个filled与73个unfilled，不能拆成独立三家族。新IRS原件单次HTTP200取得；六完整表单页539项已不同作者逐项核验：340文字写区为115已填/225未填，178勾选框为2选中/176未选，21模板角色观察另列，不作unknown第三类监督。FEMA完整三页165人工状态参考为44已填/121未填/0unknown，隔离仅作评价；独审发现2框不完整后fresh修订，最终独审确认165有效写区/context、其余163单位完整保持、状态身份不变。PHMSA候选一次请求HTTP403，未重试/未取得PDF，独立校准家族仍缺；head与特征运行未准入。原同74写区31正确/0错误/43未知、状态和值各1正确/73未知、57完整字段全未知、11关系2正确/9未知沿R157，未新跑或授分。

先取得有真实已填及未填文字写区的独立校准家族，完成使用依据、完整双视图和不同作者状态复核；IRS同出版集不拆cal，FEMA答案和坐标继续与训练准备隔离。未执行的三分类提案改为受支持文字写区的filled/unfilled二分类加完整性、功能角色、控制类型和低置信弃权；checkbox分域，21模板常量不凑unknown类，状态头不生成原文值。数据、校准和精确运行预算通过后才做一次固定可行性对照；正常Java typed表单消费、完整字段/关系和旧正确仍须来源验收。连续3次可比实际无来源收益或关键缺陷减少即停该方向复盘；数据调查不重置此计数。

FEMA仅评价、Root与训练准备方未展开其具体答案/框；IRS六页来源参考不等35页全源或独立样本。状态头尚未实现/运行，数据参考不重置实际无收益计数。原普通CER≤0.1%、关键零错漏、完整表格/表单及定位跨页、正常最终parser、旧正确和独源门均不缩。默认BF16及必要FP32状态、原件/gold/旧失败/fixed05/Drafting/活动服务数据库保持；图纸仅身份路由后排除，未部署或发布权重。

证据根`.local/state/oer-quality-persistent-20261013/semantic-field-alignment-01/`：`state-data-readiness-root-result01.json`、`state-method-decision-02.json`、`state-irs-source-independent-review-01/review-summary.json`、`state-fema-source-independent-review-02/source-review-summary.json`及`report/state-data-readiness-01/publish-receipt.json`。下方按历史状态读取。

**2026-10-14 R157 当前状态：native唯一行关联与覆盖观察已完成完整来源复核，新增4项正确写区；质量Goal继续active。** 单次CPU实际1.984秒、exit0、worker已关闭，未使用Job；0模型/GPU调用、0训练更新。完整七页同74写区定位31正确/0错误/43未知，对R156的27正确/0错误/47未知新增4项：三处独立Occupation及option(b)见证人签名写行。R156全部27正确写区、16正确分区、TITLE首行状态和值及原48正确保持，未发生回退。实际20组/31行/6无锚标签；21 native词法原子12绑定旧组、4新增候选、5弃权。131补充观察为20个原编号候选及111原字符片段，不是131字段或新增来源分数；不同观察种类不合成准确率。写区状态和值仍各1正确/73未知，57完整语义字段仍全未知，11关系2正确/9未知沿旧。局部来源进步确认，完整来源/正常最终parser/独源泛化仍未通过，候选整体未采用，Goal保持active。

继续将无冒号行内placeholder/角色与原编号条目关联到完整写区和语义；原source窗口不能直接当writingBbox。填写状态另行补独立数据与有限输出方法：现装任务没有已验证的state分类头，不能由空OCR或mask判未填，也不续扫自由JSON提示。保持完整57字段/74写区/11关系、原正确、正常parser与独立来源标准；连续3次可比实际无来源收益或缺陷减少即停止该方向追加。

状态方案仅prepare-only：冻结视觉特征配有限分类头尚未执行，也未建立所提新状态数据/家族split；不得当新能力或泛化。默认BF16及必要FP32训练状态保持，原普通CER≤0.1%、关键零错漏、完整表单/表格类型状态值归属及跨页、正常最终parser、旧正确及独源门不缩。原件/gold/旧失败/fixed05/Drafting/活动服务数据库保持；图纸仅身份路由后排除；未部署或发布权重。

证据根`.local/state/oer-quality-persistent-20261013/semantic-field-alignment-01/`：`label-led-writing-regions-root-result03.json`、`label-led-writing-regions-source-review-03/source-summary.json`、`state-method-preparation-01.json`及`report/label-led-regions-result-03/publish-receipt.json`。下方按历史状态读取。

**2026-10-14 R156 当前状态：有界续行与原生标签竞争已完成完整来源复核，局部改善确认；完整质量Goal继续active。** 单次CPU实际1.687秒、exit0、worker已关闭，未使用Job；0模型/GPU调用、0训练更新。完整七页同74写区定位27正确/0错误/47未知，对R155的24正确/1错误/49未知净增3项正确：Address首行修正并补齐2续行。三处Name不再误收Occupation；16个已发射组分区正确、6无锚观察未知，extra错误断言7→0，对应消除4处来源缺陷，不是7个独立缺陷。原同分支48项正确保持。写区状态和值仍各1正确/73未知，57完整语义字段仍全未知；11关系2正确/9未知沿用原证据。局部方法进步门通过，完整来源/正常最终parser/独源泛化未通过，候选整体未采用，Goal继续active。

继续复用已有native标签与实际写行，补独立字段和完整语义的来源证据；多标签整块不能均分成虚构细框，OCR未识别到内容不能证明未填。沿完整七页/57字段/74写区/11关系对照并保持旧正确；同方向连续3次可比实际无来源收益或缺陷减少即停止追加，依据历史换方法，不盲加训练。

默认BF16及必要FP32训练状态保持。本轮复用已闭native119的112记录：111直接像素链合格，19标签块/21词法原子；1个无标签页码源crop与官方原crop一致，中间到prepared的尺寸变换未确立而不准入，不称来源损坏。局部修复不完成Goal；原普通CER≤0.1%、关键零错漏、完整表单/表格及跨页关系、正常最终parser、旧正确与独立来源泛化门不缩。原件/gold/旧失败/fixed05/Drafting/活动服务数据库保持，图纸仅身份路由后排除；未部署或发布模型。

证据根`.local/state/oer-quality-persistent-20261013/semantic-field-alignment-01/`：`label-led-writing-regions-root-result02.json`、`label-led-writing-regions-source-review-02/source-summary.json`、`method-history-r156-review.json`及`report/label-led-regions-result-02/publish-receipt.json`。下方按历史状态读取。

**2026-10-14 R155 当前状态：文字与多通道写区组合已完成完整来源复核；有局部收益，但候选拒绝采用，Goal继续active。** 单次CPU实际1.735秒、exit0、worker已关闭，未使用Job；完整七页，119去重实际支持、16标签组、28子行及6无锚标签。完整来源复核：74写区定位24正确/1错误/49未知；写区状态和值各1正确/73未知，唯一正确为TITLE首行。TITLE续行及整字段状态仍未知，57完整语义字段全未知；11文档关系2正确/9未知，正确者沿用原证据。原同分支48项正确保持。Address截断/漏续行、三处Name误收Occupation；extra判断12正确/7错误/6未知，不能合并为独立来源准确率。方法门未通过，候选拒绝采用；0模型/GPU调用、0训练更新。

先核已保存native识别能否补足Occupation标签，并查Address全通道实际支持是否被过滤；没有标签不能作为续行正证据。固定通用规则后再完整七页对照，不通过gold/页号/固定坐标取答案。

写区标准是完整包含并能区分邻项，不要求pixel-perfect。TITLE已填首行与未填续行应分别保留；本actual续行仍unknown，整字段不冒称filled或mixed。完整原域、普通CER≤0.1%、关键零错漏、正常最终parser、旧正确与独立来源泛化门不缩。原件/gold/旧失败/fixed05/Drafting/活动服务数据库保护，图纸仅身份路由后排除。未部署或发布模型。

证据根`.local/state/oer-quality-persistent-20261013/semantic-field-alignment-01/`：`label-led-writing-regions-root-result.json`、`label-led-writing-regions-source-review-01/source-summary.json`及`report/label-led-regions-result-01/publish-receipt.json`。下方按历史状态读取。

**2026-10-14 R154 当前状态：候选级双图属性实验已闭合，未形成可评分属性；质量Goal继续active。** 单次实际495.328秒、exit0、owned Job已关闭；七页完整库存，其中四页61候选进入双图推理，三页零候选不代表来源没有字段。61/61完整返回，31 stop但非属性协议、30 length；合法属性协议0/61、合法弃权0、可评分写区预测0。Root与不同作者已逐项核对实际输入、图像与返回。57字段/74写区/11关系均保持未评分null，不写0/57或0/74。0训练更新，未采用。

当前base的自定义属性提示方向停止追加，不升token、不扫LR/精度、不盲加步数。下一步复用现有同分支OCR文字、印刷标签和实际版面观察做确定性属性关联；不从gold/页号/固定坐标取答案，缺证保持unknown。普通CER≤0.1%、关键零错漏、完整表格/表单及关系、旧正确保持、正常最终parser、独立复核与独源验证门不缩。日志154轮；旧153轮、501质量指标和九段496训练计算/124更新完整保持。本轮61次推理不增加训练曲线。原件/gold/旧失败/fixed05/Drafting/服务数据库保护，图纸仅身份路由后排除；未部署或发布权重。

证据根`.local/state/oer-quality-persistent-20261013/semantic-field-alignment-01/`：`typed-attribute-root-result.json`、`typed-attribute-method-decision-01.json`、`typed-attribute-fixedbranch-result-independent-review-01/independent-result-review.complete.json`及`report/typed-attribute-result-01/publish-receipt.json`。下方按历史状态读取。

**2026-10-14 R153 当前状态：完整目标加首位置辅助固定对照已保存；新质量Goal保持active，完整解析仍待来源验收。** 16次参数更新/112次完整训练计算。另28/28次teacher评估已核验。匹配完整eval平均loss 2.968685→2.463222，实际下降0.505463，预定门槛0.029687；学习门通过。7/7页出现对象开头（入口诊断），对象起始门通过；后测0/7合法协议；重载已绑定2/7，其中0页合法，另5页未知。适配器候选未采用，来源质量与正常最终parser仍未通过。实际结果已由Root与不同作者分别核对，完整来源质量仍未通过。完整post七页逐token/原图/元数据绑定后均不合法，Root与不同作者独立核对；依据用户新增方法复盘要求，仅主动停止剩余fresh叶进程，原primary/supervisor正常finally闭Job。实际1559.938秒、exit1/incomplete_unknown，属于有意提前结束，原计划未全部执行；fresh已绑定2/7且0页合法，其余5页unknown，不能写fresh0/7或认定适配器加载缺陷。

本轮同原BF16底座及必要FP32状态，LR2e-4/rank8/16更新/完整原目标EOS保持；同hidden增加权重1.0首assistant位置CE，full/aux/combined分列，匹配的teacher eval与逐步训练loss曲线分列。组合loss不与旧完整CE混比，学习门仍只取匹配完整CE；post/fresh仍使用未合并适配器；完整学习门后七页各一token入口仅控制完整长输出成本；未进入完整生成则协议和来源记unobserved，不写0/7。合法协议也不授来源分。只有合法完整输出才做完整七页/57字段/74写区来源核验，11文档关系另列。

日志153轮/2210证据/41卡/501质量指标；完成训练历史为9段、496次训练计算/124次更新，额外teacher计算不混入训练曲线。152旧轮次、八旧训练段与全部旧质量指标和历史点完整保持。用户新增规则已生效：连续3次可比实际实验无来源收益必须停止同方向追加并复盘方法；R145–R152历史已触发，本轮完整post仍0/7，已停止自由JSON同方向盲加训。下一步复用已存native/layout与来源支持的结构规则，补type/state/value实据和正常Java结构消费；不重跑闭合119识别，不从gold复制blank或值。当前仍仅一个已暴露来源家族；完整原域、普通CER≤0.1%、关键零错漏、完整表格/表单及定位关系、旧正确保持、正常最终parser、独审及独源泛化门不缩，Goal未取消或完成。原件/gold/旧失败/fixed05/Drafting/活动服务数据库及已发布模型保持；图纸仅识别路由后排除。

证据根`.local/state/oer-quality-persistent-20261013/semantic-field-alignment-01/`：`first-position-aux-root-result.json`、`first-position-aux-root-actual-tool-receipt.json`、`first-position-aux-root-admission-01.json`及`report/first-position-aux-result-01/publish-receipt.json`。下方按历史状态读取。

**2026-10-14 用户新增方法迭代规则：已按明确要求重建Goal并读回active，质量目标未完成。** 连续3次可比实际实验若没有可复核的完整来源收益或关键缺陷减少，必须停止同方向追加训练/参数扫描，回顾已有输入、输出、错误族和失败轨迹，给出继续/切换/停止依据并实施最小有证据的方法调整。loss下降、首token或JSON合法只算中间信号；以同域完整来源质量、旧正确保持和正常最终parser为主。缺失/超时/未观察保留unknown，不拼分支最佳、不缩分母。

已有R145–R152连续多轮没有可验收完整字段输出，本轮立即触发历史方法复盘，不额外等三轮。当前first-position-aux-01已单次启动，允许同一作业在原2400秒预算内完成完整post/fresh；不因观测等待重启。若仍只改善拟合而无可核验完整输出，停止自由JSON表单监督盲加训，优先比较已有native/布局/专用识别及确定性结构组合等方法；调查或prepare不称已成功。

默认BF16及必要FP32训练状态、全部原件/目标/EOS、旧正确、完整质量门和独立来源泛化标准保持。原件/gold/旧失败/fixed05/Drafting/活动服务数据库保护，图纸仅路由后排除；不部署或发布权重。本次用户主动取消的是旧Goal；新Goal实际证据在`.local/state/oer-quality-persistent-20261013/semantic-field-alignment-01/goal-method-review-20261014.json`。下方旧Goal状态按历史读取。

**2026-10-14 R152 当前状态：首输出三路径实际诊断与不同作者复核已闭，完整解析质量仍未通过，工作继续。** 七页21次实际前向、37.0秒、0新增更新，owned Job已关闭。完整teacher、仅提示词前向和实际generate每页首选token一致，7/7复现R151。生成raw logits与processed scores逐值相同；重载完整teacher CE与R151 post逐页差0。不同作者重放七原图processor及全部目标/EOS、独立复算546字段通过。当前证据支持优先加强首输出位置监督；不宣称生成路径数值完全相等或所有失败已有唯一归因。

R151完整监督损失2.9686845→1.5158027（下降48.94%），训练样本拟合明显改善，不能称来源质量已显著提高。下一轮只准备完整CE加首assistant位置CE的固定对照；同hidden、完整原图/目标/EOS保留，full/aux/combined分列。尚未执行该新训练。完整原域、普通CER≤0.1%、关键零错漏、完整表格/表单及定位跨页、正常最终parser、保旧正确、独立复核与独立来源泛化标准保持，候选不采用。

日志152轮/2183证据/41卡/501质量指标；八段384次训练计算/108次更新及所有旧质量历史保持。R151发布时独审pending按历史保留；其本次复核状态见snapshot.trainingResultReview，本次更正训练loss证据标签28→112；原文件/实际loss不变。原件/gold/旧失败/fixed05/Drafting/活动服务数据库及已发布模型保护，图纸仅识别路由后排除。

原生Goal本轮读回为blocked，非本轮Root设置；目标未取消或完成，实际诊断和下一轮准备继续。真实状态保存在goal-readback-r152.json，不把旧报告active当作当前原生状态。

证据根`.local/state/oer-quality-persistent-20261013/semantic-field-alignment-01/`：`first-token-path-root-result.json`、`first-token-path-independent-result-review.json`、`report/first-token-path-result-01/publish-receipt.json`；R151训练独审如已保存则单独附证。下方按历史状态读取。

**2026-10-14 R151 当前状态：固定完整监督实验已由Root实际核对，不同作者结果复核待完成；质量Goal保持active，完整解析未验收。** 16次参数更新/112次完整训练计算。另28/28次teacher评估已核验。匹配完整eval平均loss 2.968685→1.515803，实际下降1.452882，预定门槛0.029687；学习门通过。0/7页出现对象开头（入口诊断），对象起始门未通过；后测未执行；重载未执行。适配器候选未采用，来源质量与正常最终parser仍未通过。实际结果已由Root核对；不同作者结果复核仍在排队，不能据此授原件质量或采用结论。

本轮同原BF16底座，保留必要FP32 LoRA/梯度/AdamW；固定LR2e-4、rank8、16次更新及原完整目标/EOS，匹配的teacher eval与逐步训练loss曲线分列。相对R150只增加训练长度，post/fresh仍使用未合并适配器；完整学习门后七页各一token入口仅控制完整长输出成本；未进入完整生成则协议和来源记unobserved，不写0/7。合法协议也不授来源分。只有合法完整输出才做完整七页/57字段/74写区来源核验，11文档关系另列。

日志151轮/2171证据/41卡/501质量指标；完成训练历史为8段、384次训练计算/108次更新，额外teacher计算不混入训练曲线。150旧轮次、七旧训练段与全部旧质量指标和历史点完整保持。当前仍仅一个已暴露来源家族；完整原域、普通CER≤0.1%、关键零错漏、完整表格/表单及定位关系、旧正确保持、正常最终parser、独审及独源泛化门不缩，Goal未取消或完成。原件/gold/旧失败/fixed05/Drafting/活动服务数据库及已发布模型保持；图纸仅识别路由后排除。

证据根`.local/state/oer-quality-persistent-20261013/semantic-field-alignment-01/`：`learning-length-root-result.json`、`learning-length-root-actual-tool-receipt.json`、`learning-length-root-admission-01.json`及`report/learning-length-result-01/publish-receipt.json`。下方按历史状态读取。

**2026-10-14 R150 当前状态：固定完整监督实验结果已保存，质量Goal保持active，完整解析未验收。** 4次参数更新/28次完整训练计算。另28/28次teacher评估已核验。匹配完整eval平均loss 2.968685→2.758635，实际下降0.210049，预定门槛0.029687；学习门通过。后测0/7合法协议；重载0/7合法协议。适配器候选未采用，来源质量与正常最终parser仍未通过。

本轮同原BF16底座，保留必要FP32 LoRA/梯度/AdamW；固定LR2e-4、rank8及原完整目标/EOS，匹配的teacher eval与逐步训练loss曲线分列。post/fresh使用未合并适配器，与R147 merged整体差异不全部归因LR；原始未预填任务生成与R149 metadata诊断不混分母，合法协议也不授来源分。只有合法完整输出才做完整七页/57字段/74写区来源核验，11文档关系另列。

日志150轮/2152证据/41卡/501质量指标；完成训练历史为7段、272次训练计算/92次更新，额外teacher计算不混入训练曲线。149旧轮次、六旧训练段与全部旧质量指标和历史点完整保持。当前仍仅一个已暴露来源家族；完整原域、普通CER≤0.1%、关键零错漏、完整表格/表单及定位关系、旧正确保持、正常最终parser、独审及独源泛化门不缩，Goal未取消或完成。原件/gold/旧失败/fixed05/Drafting/活动服务数据库及已发布模型保持；图纸仅识别路由后排除。

证据根`.local/state/oer-quality-persistent-20261013/semantic-field-alignment-01/`：`learning-probe-root-result.json`、`learning-probe-independent-result-review.json`、`learning-probe-root-admission-02.json`及`report/learning-probe-result-01/publish-receipt.json`。下方按历史状态读取。

**2026-10-14 R149 当前状态：metadata预填诊断已按预算闭合，完整解析质量仍未通过，Goal保持active。** 单次作业实际1200.188秒外部预算耗尽，owned Job已关闭；6页完整returned/continuation/composite逐项绑定，已观察0/6合法协议（2stop/4length），apl3无完整返回保持unknown，全七页protocol计数null，不写0/7。Root与不同作者均已核原prefix、metadata增量/mask、双EOS及总5500额度；raw不repair，source grader调用0，原七页57字段/74写区与11文档关系质量仍unknown/unscored。尺寸数字循环与OCR/叙述续写仅说明本固定预填没有解决已观察页面，不归因为未经证实的BPE故障，也不宣称模型已学会未预填任务。

默认BF16及必要FP32训练状态按用户确认保持。下一项固定完整监督实验已做CPU准备及独立准入：仍4更新/28训练计算，增加匹配的两次pre/两次post全7页teacher测量（额外28forward）；无学习信号则保存adapter/梯度/优化器证据并停止生成，未观察协议/来源记unknown；有信号才做post7/fresh7。实际模型/资源准入和真实训练另行记录，CPU检查不授原件质量。

日志149轮/2133证据/41卡/501质量指标；本轮0训练更新，六段244样本计算/88更新和全部旧质量历史整对象保持。完整范围、普通CER≤0.1%、关键零错漏、完整表格表单关系、旧正确保持、正常最终parser、独审及独立来源泛化门不变，候选不采用。证据根`.local/state/oer-quality-persistent-20261013/semantic-field-alignment-01/`：`metadata-prefill-root-result.json`、`metadata-prefill-independent-result-review.json`、`precision-decision-20261014.json`及`report/metadata-result-01/publish-receipt.json`。下方按历史状态读取。

**2026-10-14 用户确认精度策略：后续默认 BF16（16位），质量优先。** 大模型底座原已BF16；R148仅为同BF16 base上的未合并FP32 LoRA对照，并非全模型FP32/BF16比较。R147 merged与R148对照均0/7合法form，目前无已证实质量收益，不再无依据重复FP32精度对照。训练的FP32 LoRA、梯度、AdamW状态及现有FP32 loss保留，不能将小更新提前舍入；BF16不自动替换成FP16。已封版metadata-prefill诊断仍按原BF16 base+FP32 adapter完成一次，保持仅预填metadata一个变量；后续只有具体质量证据指向精度才重开固定对照。尚未证明全来源精度等价，也未测得可比速度提升或整卡显存减半；全部原件质量门与Goal未完成状态保持。决策证据：`.local/state/oer-quality-persistent-20261013/semantic-field-alignment-01/precision-decision-20261014.json`。

**2026-10-14 13:11 Goal状态校正：原生质量目标当前active，未达标且未取消或完成。** 最终get_goal读回空记录，区别于本轮开头的blocked快照；本轮没有调用取消/complete。依用户此前明确要求重新挂回同等质量门，create_goal成功并再次读回active；实际记录见`.local/state/oer-quality-persistent-20261013/semantic-field-alignment-01/goal-restored-20261014.json`。后续首先用不含语义gold、仅调用方已知metadata的固定诊断区分协议起始失败与字段可学习性，再依据真实学习信号决定固定监督实验；独立来源和正常最终parser等原质量门不变。R147/R148实际结果保持：4训练更新/28完整计算，导出重载已过；merged及未合并FP32对照均0/7合法form，候选不采用。下方blocked为空记录前的历史状态，不能当作当前Goal状态；训练与质量历史均未重写。

**2026-10-14 R148 当前状态：本轮训练与未合并适配器对照均已闭合，完整字段质量仍未通过。** R147实际4更新/28完整计算、导出及post7/fresh7逐token一致；完整训练目标、原图、EOS与资源预算保持，峰值Torch allocated约3.28GiB。四轮均loss2.9686845→2.9703333未见下降，baseline/post/fresh合法字段协议均0/7，不称残差收敛或质量提升。

R148原始BF16 base+保存FP32 adapter固定对照已实际exit0、859.781秒、7/7完整generate、0新增更新，owned Job已关闭。实际worker核336个FP32 A/B与保存值逐值相等、168层enabled/unmerged；其他参数BF16，完整七页prompt/prefix/mask/metadata/seed/解码保持。不同作者复核原base全部11文件与code/adapter pins、保存336 tensor及完整runtime、7页实际returned前缀/后缀绑定。相对merged有5/7页完整token改变，aoa3/apl3保持；合法form仍0/7（4stop/3length），与Root重算一致。FP32保留改变了行为，但单独改变合并方式未改善本固定字段任务；不将微小delta的BF16舍入现象独自解释为质量失败原因。不调用source grader，不授JSON CER或完整字段来源正确分。

当前仍仅一个已暴露同源家族；完整字段类型/状态/值/owner、精确印刷定位、跨页关系、正常最终parser、普通CER≤0.1%、关键零错漏、旧正确保持与独立来源泛化继续未通过。候选不采用，线上fixed05/Drafting/数据库/服务及已发布模型保持；原件/gold/旧失败保留，图纸仅识别身份后排除。质量Goal未取消或完成，原生API状态仍blocked；本轮有界模型进程均已关闭，后续先分清协议起始与字段任务可学习性，再据可观察信号安排固定监督实验，验收门不缩。

日志已更新148轮/2122证据/41卡/501质量指标；六段已完成训练244次样本计算/88参数更新，R148是零更新推理诊断，不增加训练曲线。旧147轮、全部旧质量历史与六段训练历史整对象保持。证据根`.local/state/oer-quality-persistent-20261013/semantic-field-alignment-01/`：`train-03-root-result.json`、`train-03-independent-result-review.json`、`adapter-control-root-result.json`、`adapter-control-independent-result-review.json`、`train-03-merged-cpu-audit.json`与`report/adapter-result-01/publish-receipt.json`。下方R147及更早文字按历史状态读取。

**2026-10-14 R147 当前执行：完整字段训练与导出重载已完成，字段协议仍未改善。** 用户释放显存后先后保留两次OOM：各0更新，第二次已过第一页backward；不计入完成训练曲线。最小修复仅为完整assistant CE按256-token checkpoint计算，以及完整训练forward/backward固定cuDNN-only，排除本机无Flash且GQA默认math路径；原图、全部目标+EOS、每sample平均loss、LoRA目标与rank8/LR2e-5、4更新/28计算及9GiB allocator预算保持。CPU数学/梯度5项与真实tiny多模态LoRA接缝1项通过，旧契约22项通过；kernel校准不授原件质量。

实际train-03终态exit0、1015.953秒，4参数更新/28完整计算、28 backward返回与4×336 finite梯度记录；完整HF导出，post7/fresh7完整token逐页一致。Torch峰值allocated3,526,574,080B、reserved5,437,915,136B，非整卡峰值。baseline/post/fresh合法form均0/7（各4stop/3length）；四轮均loss2.9686845/2.9689462/2.9687052/2.9703333，没有下降，不能称收敛或质量提升。独审已核完整prefix/实际returned IDs、11导出文件与真实权重hash。仅168目标tensor实际改变；全部513非adapter tensor与共享embedding/视觉权重保持。CPU重建确认97.825%微小delta合并BF16后回原值，此现象不单独解释质量结果。

当前正做一次原始BF16 base+保存FP32 adapter的完整七页未merge对照，0新增训练，固定prompt/metadata/prefix/seed/5500输出和8192context，只隔离合并精度变量。当前候选不采用；当前仍仅一个已暴露来源家族，完整字段/跨页关系、正常最终parser、普通CER≤0.1%、关键零错漏、旧正确保持与独立泛化门均不缩。质量目标未完成或取消；原生Goal当前blocked，本地工作按用户继续授权推进。日志147轮/2117证据/41卡/501质量指标，六段244训练计算/88参数更新；所有旧质量点和五段历史训练保持。

实际证据：`.local/state/oer-quality-persistent-20261013/semantic-field-alignment-01/train-03-root-result.json`、`train-03-independent-result-review.json`、`train-03-merged-cpu-audit.json`及`report/training-complete-01/publish-receipt.json`。未合并对照计划为`adapter-control-prepare-01/adapter-control-plan.json`，实际尚未终态。原件/gold/旧失败/fixed05/Drafting/活动服务数据库与已发布权重保持；图纸只识别身份后排除。下方内容按历史状态读取。

**2026-10-14 11:50 执行状态：完整语义目标准备与基线已闭合，小规模训练等待显存。** 七页57字段/74填写子区仅接受开发兼容性监督；真实processor最长6623/8192 tokens。实际新基线7/7请求完成、4stop/3length，合法form协议0/7，不授完整字段/CER分。固定4更新/28完整样本计算计划已准备，Windows真实审计边界已修正；当前显存空闲2095MiB低于9728MiB准入，训练未启动，其他模型与fixed05保持运行。日志146轮/2099证据/41卡/501指标，216计算/84更新及原质量历史保持。原生Goal实际快照blocked，质量目标未取消或标为完成。证据：`.local/state/oer-quality-persistent-20261013/semantic-field-alignment-01/train-admission-01.json`、`baseline-result-readback.json`及`report/resource-state-01/publish-receipt.json`。

## 2026-10-14 方法调整：先对齐完整字段语义，再决定训练

用户已确认停止无收益依据的参数扫描，转为完整来源语义监督及有明确成败条件的对照。新增 `form` 任务沿用现有 dataset 与完整 target+EOS encoder，表达字段类型、可见填写状态、原文值、owner、重复出现和页内关系；TITLE 的已填首行与未填续行分开保留。`writingBbox` 仅为填写区域包络，不冒称完整印刷字段框；11条文档来源关系单独保留，跨页/条件关系最终输出仍须验收。

逐非白像素整组件唯一归属不属于原最终定位标准，不再作为语义监督的先决条件。扫描灰底/压缩晕边不能直接解释为填写内容；空白状态仍须完整原件上下文的正面来源复核，不能由OCR缺字、空mask或白窗推断。结构validator与协议合法输出均不授原件准确率，JSON字符差异不作为普通正文CER。

7页57字段/74填写子区的语义候选已不同作者来源复核，仅接受 `compatibility_only`。训练清单绑定原PDF、物理页、实际图像、逐页target及独审；仅完整原页可进入此任务，未知目标拒绝，独审文件同样排除2d/2e。现有材料属一个已暴露来源家族，独立同任务train/dev和blind均未建立，不能称质量训练或泛化。证据入口：`.local/state/oer-quality-persistent-20261013/semantic-field-alignment-01/`；候选与独审在 `semantic-field-targets-01/`。

最终范围、普通CER≤0.1%、关键零错漏、完整表格/表单与关系、正常最终parser、旧正确保持、独审及泛化门不变。下方R145及更早段落为历史状态；质量目标未完成，不以准备、编码或运行成功关闭。

# OER 已确认范围与执行规则

**2026-10-14 R145 当前状态：双方向 OCR 已完成完整来源评分，存在旧正确回退而拒绝采用；完整字段角色实验 0/7 合法输出，整体解析质量仍未合格，Goal 保持 active。** 日志已发布145轮／2083证据／41文档卡／501指标；旧144轮、2051证据与全部501指标整对象及历史点保持。本轮来源对照只登记完整字面诊断，没有新增正式质量metric或历史评分点。五段216次样本计算／84参数更新／epoch=null与原最终验收门保持。

Root单次双方向actual03已完成7原页＋7同源180°对照，共14/14页、91.066秒、errors=[]；完整14 PNG和734实际C0/C180裁图、367检测记录及285真实page/list/session调用闭合已独立复核。52旧whole payload共10533071 bytes及963 master pins保持。旧failed actual01/02、v3保存审计失败全部保留，fresh v4仅修库存生命周期投影，未放宽像素/tuple/过滤/来源标准；native C构造精确次数仍unknown。

全14页来源视觉完成后，Root单次原字节scorer核177冻结来源参考的354方向判断。原图字面诊断：AoA82/127、APL上下文26/31、printed marker15/19；同源180°对照分别94/127、28/31、18/19。原图比old2400 AoA77/127有新增正确，也有旧正确回退；全部旧通道比较6条回退记录对应4个distinct来源，15个旧合法180°正确见证保持。对最近original-new099新增回退仅aoa4-line-03的(b)→(9)一项；其余三项相对更早old2000构成回退，不能称四项最新新增故障。Occupation标签保持但额外点线导致字面非exact，不称标签漏失。regressionGate=false、candidateAdopted=false；不同分支不拼最佳，同源旋转不是泛化，字面诊断不是CER或完整glyph覆盖。旧normal APL19/19原产物保留，新候选原图替换会损4项、control替换会损1项，故不替换。

Root单次完整七页字段角色actual02已完成7请求/7generate、277.734秒，合法新角色协议0/7：前五页虽正常stop，输出为数组/LaTeX/正文而非Page协议；后两页4096token到length且无EOS，保持拒绝，不repair、不接Java、不调用来源grader。输出完整前缀与2700输入长度已保存，但实际input_ids未保存，输入张量独立一致性保持unknown。结论仅限本固定query、原BF16 adapter和token预算，不推广成模型全部JSON能力不支持。actual01重复环境keyword的启动失败及两行最小修复/AST回归独审保留。

下一完整七页方案已prepare-only终封，沿现有官方Layout→全部实际blocks→Text/Table/Formula任务；七页完整layout库存先齐，recognizable全集超过256则整段content不开始，不截前N冒称完成。不同作者exact审查随后复现三个阻断：table内text过滤被误记caption删除、generate前没有逐项完整raw写入预留、无EOS布局仍可启动content。当前prepare01拒绝执行、实际native调用0；fresh02只修这三处并重新准入，不改已封版本。该路径尚未取得实际来源结果。合同内image/chart按块保留unknown，不将其冒称图纸排出原域；已确认drawing文件仍仅身份识别后整件排除。

AoA完整38 typed／37未填／11关系／55owner及APL19完整状态／3 typed组最终域仍未通过；R142同分支局部结构、owner和关系成果保持，完整normal最终parser、普通CER≤0.1%、关键零错漏、旧正确回归、独立复核、全源与泛化门不缩。本轮训练更新／权重发布／生产部署0，原件、gold、历史失败、fixed05、Drafting、活动数据库/服务及已发布模型保持。

来源根目录.local/state/oer-quality-persistent-20261013/：R145报告report-update-19/report-update-receipt.json；完整字面对照assessment-independent/dual-orientation-full14-source-review-score-01/complete-source-comparison.json；来源视觉final assessment-independent/dual-orientation-full14-source-visual-prepare-01/final-paths.json；Root完整源评回读assessment-independent/quality-root-next-execution-readback-01/full14-complete-source-score-root-readback.json；role独审assessment-independent/full-form-role-actual02-result-review-01/final-paths.json；native拒绝准入assessment-independent/full-native-seven-execution-review-01/final-paths.json。四文档完整旧字节及本次Root一致回读存于quality-current-doc-sync-20261014-09/。下方R144及更早文字为历史快照，不覆盖本条。


**2026-10-14 R144 当前状态：有限诊断与实际导入失败均已独立复核，完整解析质量仍未合格，Goal 保持 active。** 日志已发布144轮／2051证据／41文档卡／501指标；本轮仅新增24个核心权威及完整目录catalog，来源评分／质量metric或历史评分点新增0。这些证据是库存，不是24个独立来源。旧143轮、2027证据顺序与值、全部501 metric整对象及全部历史点、五段216次样本计算／84参数更新／epoch=null、原验收标准保持；R142同分支局部结构／owner／关系成果不变。

Root单次import-only诊断在第四阶段隔离实际ocr_runtime.py:165表达式，首次socket.gethostname动作前拒绝；前三阶段返回。不同作者已核完整首栈和固定源码。它证明固定表达式可复现旧守卫拒绝，不补写旧actual01未记录的准确phase／event／stack、source decode或Session创建数；这些旧缺证仍unknown。

fresh03仅允许空参数的本机hostname元数据读取并记录动作前phase／stack。不同作者exact准入后，Root唯一实际actual02正常worker exit1，supervisor9.823秒、非观测超时／非watchdog终止。完整14输入decode尝试14、验证返回14；Torch／ORT import已返回，RapidOCR模块导入失败；Session wrapper构造尝试、initialization helper尝试、helper返回带Session对象分别观测0，精确native C构造数仍unknown。全部page／detector-list／classifier-list／recognizer-list／stage-session／candidate记录0，完成OCR页0，未评分。

actual02首拒绝有49事件中的完整84frame栈：RapidOCR→download_file→requests→urllib3 HAS_IPV6／_has_ipv6→socket.__new__，phase为model_engine_initialization，socket创建动作前已拒，后续bind未到；参数实际值没记录，不声称发生HTTP下载。不同作者保存18份实际源码完整快照，核106actualfiles、52完整旧payload共10533071 bytes、219masterpins保持。原失败和初版独审行索引断言错误都保留；v2只修行索引，未重跑producer或source grader。

完整14页来源复核准备02已终封，但不对failed actual02评分。正常最终caller只读调查确认Java DocumentParser→server.py:/ocr→OcrResult→ocrBlocks当前只text／lines，未连接MinerU表单扩展，扫描form状态尚无最终返回。完整七页字段角色能力与严格同帧支持归属正在prepare-only；候选代码／synthetic反例不等于原件正确或normal最终接缝已落地。AoA完整38 typed／37未填／11关系／55owner及APL19完整状态／3 typed组、blank／value、正常最终parser、普通CER≤0.1%、关键零错漏、旧正确回归及全源／泛化门不缩。

后续离线兼容方案保socket创建／bind／connect／DNS／额外进程禁令，只考虑在已安装guard内临时跳过导入IPv6探测并finally恢复；skip不等于机器IPv6实测False。fresh04当前未获执行准入：不同作者stdlib并发witness证明另一活跃Python线程可观察到临时全局False，需先补已有主线程／无其它Python线程前置并fresh封版。这是软件反例，不是actual03失败；尚未执行actual03，也不自动扩大socket例外。

原件、gold、历史失败、fixed05、Drafting、活动数据库／服务及已发布模型保护。本轮训练更新／权重发布／生产部署0，整体采用false。来源根目录.local/state/oer-quality-persistent-20261013/：R144报告report-update-18/report-update-receipt.json；actual02两terminal及Root真实工具收据；独立失败assessment-independent/dual-orientation-actual02-failure-review-01/final-paths.json；Root失败回读assessment-independent/dual-orientation-root-execution-readback-02/；import诊断独立result-review-01与Root-result-readback-01；正常caller调查assessment-independent/full-typed-normal-parser-seam-investigation-01/；source prepared assessment-independent/dual-orientation-full14-source-review-prepare-02/；当前并发witness在assessment-independent/dual-orientation-execution-review-03/。四入口原全部字节备份及Root历史／501指标／训练／HTML一致回读存于quality-current-doc-sync-20261014-08/。下方R143及更早文字按历史阶段读取，不覆盖本条。

**2026-10-14 R143 当前状态：双方向识别唯一实际启动在首次页面预留前失败，未产生新解析质量成绩，Goal 保持 active。** 日志已发布143轮／2027证据／41文档卡／501指标；本轮新增88证据、质量metric或评分点0。旧142轮、1939证据顺序与值、全部501 metric整对象及全部历史点、五段216次样本计算／84参数更新／epoch=null、原验收标准保持；R142局部来源结构收益保留，不由本轮未完成候选重写。

Root按不同作者exact准入单次实际启动Hard14（完整原七页＋七页同源180对照），actual01已正常exit1并终态incomplete_unknown，非观测超时、非watchdog终止。supervisor约1.956秒；0完成页，全部记录到的page／detector-list／classifier-list／recognizer-list／session／candidate尝试为0，52完整旧payload副本、原201pins保持。错误为Worker forbids network and extra processes。未重跑该producer或source grader，新source分0、整体未采用；软件反例通过和source参考准备不算原件质量。

失败发生首次page reservation之前；worker在此之前有完整control图像验证、runtime导入及Session创建路径，原终态未记录准确phase／event／stack。因此source decode数和model Session创建数保持null／unknown，不能由0记录推理次数推断为0。静态强候选为ocr_runtime配方中的platform.platform()经platform.uname/_node调用socket.gethostname，被全socket.*守卫拒绝；尚未以actual01调用栈证明实际首触发，不能写成已确认归因。下一fresh有界import-only诊断捕获首个事件和栈，不打开原件、不装载权重或推理；本条不预报其成功，也不盲放宽网络／进程守卫。

完整来源复核准备已终封：原177文字参考行（AoA127／APL31上下文／APL19marker）、旧21完整调用、全部selected／rejected／漏检及旧正确范围保留；官方最终过滤省略空word_results的审查器投影已在fresh v2匹配，旧v1和全部反例／失败保留，未改producer或源标准、未执行本轮source评分。AoA38完整typed／37未填／11关系／55owner及APL19状态／3完整typed组原域保持，blank／value／最终normal／CER／全源及泛化继续未过。

Root完整saved-output共因审计覆盖92 page branches／588 field instances，当前producer确实统一发射fieldState／printedBlank／storedValue unknown、residual ink语义未归属；这只是库存和发射缺口，不是588个独立来源错误或准确率。后续需要在正常最终parser中实现并源核实际字段类型／状态／值证据，不复制gold blank、不改grader追认通过。方向文字改善不能自动授完整表单分。

最终普通CER≤0.1%、关键零错漏、完整表单与来源关系、正常最终parser、旧正确回归、独立复核和全源／泛化门保持。原件、gold、历史失败、fixed05、Drafting、活动数据库／服务及已发布模型保护；本轮训练更新／权重发布／生产部署0。

来源根目录.local/state/oer-quality-persistent-20261013/：R143报告report-update-17/report-update-receipt.json；actual01 terminal.json、payloads/terminal.json及root-actual-tool-receipt.json；Root失败回读assessment-independent/dual-orientation-root-execution-readback-01/failed-actual-root-readback.json；不同作者失败回读assessment-independent/dual-orientation-import-failure-review-01/failed-actual-independent-readback.json；完整源评prepared assessment-independent/dual-orientation-full14-source-review-prepare-01/complete-final-paths.json；typed发射共因assessment-independent/full-typed-state-next-cause-root-01/final-paths.json。四文档完整旧字节及Root独立历史／501指标／训练／HTML回读收据存于quality-current-doc-sync-20261014-07/。下方R142及更早文字按历史阶段读取，不覆盖本条当前状态。

**2026-10-14 R142 当前状态：完整七页打印行与边界修订已完成来源复核，局部结构／owner／关系改善且保旧正确，整体质量 Goal 保持 active。** 日志已发布142轮／1939证据／41文档卡／501指标。本轮新增249证据、metric对象0；原20个完整AoA分支的三个来源域各追加一个R142点，共60个同域点。旧141轮、1690证据顺序与值、441旧metric整对象、另60个R141指标的ID／分母／全部旧点及旧来源数值保持，scope／note／evidence仅追加；旧C/I/U明确为R141历史快照。

Root一次实际完成7页adaptation／2整件projection，9.281秒、24whole／92page branches、588 field instances，保全93完整输入副本；不同作者全7原图和49 distinct全页overlay来源复核并终封222产物、1854 immutable pins，Root逐项回读。最终域仅以form-row-statement-full7-source-review-01/final-source-domain-review.json及其完整矩阵为准，执行库存和软件断言不当作准确率。逐3680个重复domain记录核232个旧correct保持、定义不变、丢失0；232不是独立来源数。partialSourceDomainRegressionGate=true，整体质量／泛化／采用仍false。

同完整continuous分支原分母比较：2000 wholeOCR字段来源结构12正确／3错误／23unknown→18／0／20（38项），owner14→25（55项），完整关系1→2（11项）；2400 wholeOCR结构15／2／21→18／0／20、owner19→23、关系1保持。normal使用同whole variant的唯一contained实际OCR边界cue，方法明确为normal_with_same_variant_wholeOCR_boundary_cues；两variant各自continuous结构6／1／31→7／0／31、owner7→9、关系2保持，旧pure normal历史含义和产物不改。不同分支不拼最佳，不合成总体准确率；2000五channel新增关系记录只对应一个distinct完整来源关系。

AoA完整typed fields38／unfilled37、APL完整typed states19／typed groups3仍每分支全部unknown；复用APL来源组结构3/3不算新grammar收益。旧normal APL19/19、物理33完整／22局部／0未观察及union35诊断保持。四条初评C→U因人工额外要求producer selected状态，旧新bbox／target／parent／邻项实际未变；不同作者按原完整包含且能区分邻项的来源标准视觉纠正，初评、回退账、schema失败和修订均原字节保留，不冒称生产回退或改producer状态。

方向分类误转共因已只读定位；下一proposal使用同一实际detector crop的0／180完整识别对照与固定模型分数选择／abstain规则，必须fresh observer分开记录原classifier选择和实际输入。旧数据只有C0像素SHA没有完整像素，不能从bbox重建冒称同crop；单一全局cls threshold无可行区间，停止盲扫。下一候选目前仅proposal／不同作者静态准入，尚未实现、原件decode或模型调用；Hard14完整页预算须fresh exact计划与独审后Root单次执行，合法180旧正确回退即拒绝。模型分数不是来源正确率。

最终普通CER≤0.1%、关键零错漏、完整表单与来源关系、正常最终parser、旧正确回归、独立复核和全源／泛化门保持。五段216样本计算／84参数更新／epoch=null保持，loss下降只说明训练样本拟合。此次saved-input修订OCR／render／模型／GPU／训练更新／生产部署0；原件、gold、历史失败、fixed05、Drafting、活动数据库／服务及已发布模型保护。

来源根目录.local/state/oer-quality-persistent-20261013/：R142正式报告report-update-16/report-update-receipt.json；追加账report-update-16/metric-metadata-append-ledger.R142.json；最终源评assessment-independent/form-row-statement-full7-source-review-01/final-paths.json与final-source-domain-review.json；Root全源回读assessment-independent/form-row-statement-root-actual-readback-01/root-full-source-final-authority-readback.json；方向proposal assessment-independent/dual-orientation-interface-proposal-01/complete-final-paths.json。四文档完整旧字节及Root独立历史／60追加点／训练／HTML回读收据存于quality-current-doc-sync-20261014-06/。下方R141及更早文字按历史阶段读取，不覆盖本条当前状态。

**2026-10-14 R141 当前状态：完整七页 grammar 来源复核已结束，确认部分字段结构／owner／关系，整体解析质量 Goal 保持 active。** 日志已发布141轮／1690证据／41文档卡／501指标。旧140轮、1561证据、全部441旧指标及其历史点、五段216次训练计算／84次参数更新保持；本轮129新增证据，60个首次来源结构评分点按20个完整AoA分支的三个独立域登记，没有编造旧0基线或跨分支拼最佳。

Root已一次实际完成7页adaptation／2整件projection，保全75完整输入副本；不同作者全7页、24个whole branches／92个page branches来源复核并终封106产物、1568 frozen pins，Root逐项回读。最终来源判断仅以final-source-domain-review.json及其完整分支矩阵为准；初版source checker、初次误判、reconciliation修订证据全部原字节保留，未重跑producer或旧checker。588 field instances和软件断言只是库存／软件检查。

完整字段来源结构域按38项、owner按55项、关系按11项逐完整分支评分。以2400 wholeOCR／continuous完整分支为例：字段结构15正确／2错误／21unknown；owner19正确／36unknown；关系1正确／10unknown。normal每完整物理通道的关系域为2正确／9unknown，含完整a→b→c→d（跨页b→c）和独立Employer。上述局部来源结构与AoA完整typed fields38／unfilled37分开，后两域每分支仍全部unknown；APL完整typed states19／typed groups3仍unknown，复用的APLGroupSourceStructure3/3不记新grammar收益。保旧normal APL19/19、物理33完整／22局部／0未观察及union35诊断和全部旧正确输出。

完整原件视觉复核纠正了19个重复branch的初次字段结构判断：部分范围包含目标同时跨入完整OR或下一witness引导语，按原完整包含且能区分邻项的标准拒绝；不是19个独立来源错误，也不因subpixel包络或pixel-perfect判失败。共享原因已定位为同行prefix与SIGNED／EXECUTED被分开处理、OR／所选parent关闭遗漏、normal粗全文块与field-local定位混用。下一fresh候选同时修这三个接缝，竞争／缺证保持unknown；same-source／page／frame／whole variant的唯一contained实际OCR角色cue可统一用于normal边界，须保全全部匹配竞争并显式标记方法。尚未执行下一候选或预授收益。

最终普通CER≤0.1%、关键零错漏、完整表单与来源关系、正常最终parser、旧正确回归、独立复核和全源／泛化门保持，整体采用仍false。页首loss下降只说明训练样本拟合改善；216是样本计算数、84是参数更新数，不称216个epoch。新模型／OCR／GPU／训练更新／生产部署0，原件、gold、历史失败、fixed05、Drafting、活动数据库／服务和已发布模型保护。

来源根目录为.local/state/oer-quality-persistent-20261013/：R141正式报告report-update-15/report-update-receipt.json；权威聚合assessment-independent/form-grammar-full7-source-review-01/final-paths.json；最终域assessment-independent/form-grammar-source-domain-reconciliation-01/final-source-domain-review.json；Root精确回读assessment-independent/form-grammar-root-source-contract-review-01/root-final-authority-readback.json；三个接缝诊断assessment-independent/form-grammar-actual-rule-diagnostic-01/shared-cause-final-paths.json。四文档完整旧字节及Root独立140轮／1561证据／441旧指标／60新分支来源点／训练／HTML回读收据存于quality-current-doc-sync-20261014-05/。下方R140及更早文字按历史阶段读取，不覆盖本条当前状态。

**2026-10-14 R140 当前状态：完整七页填写区域来源复核已结束，候选不采用，整体质量 Goal 保持 active。** 日志已发布140轮／1561证据／41文档卡／441指标；本轮新增75证据、质量指标或评分点0。旧139轮、1486证据的顺序及内容、全部旧指标与历史点、五段216次训练计算／84次参数更新保持。下方R139“写区来源复核正在进行”等文字为旧阶段快照，本条为当前状态。

Root已实际完成完整7页、两套旧whole variants的7次CPU填写区域投影，58完整输入副本保持。不同作者随后完成全页RGB atlas／全部候选和anchor incidence／完整语义来源复核，最终1406 immutable pins和36产物封口，Root实际逐项回读产物bytes／SHA。墨迹和组件库存、执行成功与软件断言不当作原件准确率。

完整域每whole variant保持AoA38字段／37未填／11关系、APL19完整状态／3组及55语义owner；两套共326项仍unknown，新增typed质量正确0。完整大区域会跨AoA的OR签署分支或Contractor／Employer；APL全部38个窄marker-parent候选漏其已有右侧writing观察。候选拒因可重叠，仅作诊断，不当错误总数。原物理33完整／22局部／0未观察及union35诊断、旧normal APL19/19和全部旧正确输出保持。

两原PDF完整七页的原生内容与既有Pdfium实际render／RGB身份链已核：没有可复用text／font／glyph／annotation／AcroForm，正文由228次image draw及APL3另3个graphics Form白色patch构成；这些graphics Form不是表单字段。该原生补充路线停止，不把空native层当作未填写证明。下一候选沿完整normal＋whole OCR实际文字、编号、标题和OR构造可选择的字段区域与parent关系；软件控制和执行准入不预授原件分。

定位仍按原标准“框完整包含目标并可区分相邻范围，无须像素级全等”，不新增pixel-perfect最终门。blank／value和打印／填写残墨缺证保持unknown，但与已证明的独立结构域分别判断。最终普通CER≤0.1%、关键零错漏、完整表单与来源关系、正常最终parser、旧正确回归、独立复核及全源／泛化门保持。loss下降仅说明训练样本拟合，216不称epoch。

来源均在本地.local/state/oer-quality-persistent-20261013/：R140正式报告report-update-14/report-update-receipt.json；完整写区来源assessment-independent/writing-region-full7-source-review-01/final-paths.json；Root精确回读writing-region-root-source-readback-01/root-final-readback.json；原生缺证assessment-independent/full-writing-next-cause-01/native-final-paths.json。本次四文档完整旧字节及Root独立历史前缀／HTML数据回读收据存于quality-current-doc-sync-20261014-04/。原件、gold、历史失败、fixed05、Drafting、活动数据库／服务和已发布模型保护；本轮未更新权重或部署。

**2026-10-14 R139 当前状态：方向阈值完整来源复核已结束，候选拒绝采用，整体质量 Goal 保持 active。** 已发布139轮／1486证据／41文档卡／441指标；本轮新增64证据、质量指标或评分点0，旧138轮、1422证据、全部指标与历史点及五段216次训练计算／84次参数更新完整保持。下方R138阈值待执行说明是旧阶段记录，本条为当前状态。

2400 recipe单变量 Cls.cls_thresh .9→.99 的Hard21 CPU整页调用已全部实际结束（21／21、61.782536秒、errors=[]）；完整7原图与两套完整180度同源控制已独立全域来源封口。原图同127行raw79、retained78（caret ^被below_text_score删除）只作字面诊断，不称最终准确率或CER。倒置控制产生15个distinct旧正确retained回退；23个分类gate变化不当作23错误。抑制全部3个源证误转需要有效float32阈值t≥0.9517964124679565，保住已见合法纠正需要t<0.9004489183425903，单一全局标量区间为空，停止仅阈值盲扫。regressionGate=false、noadopt、新typed质量通过0；旧normal APL19/19及所有旧正确输出保持。

完整字段／空态／关系门继续保持原分母：AoA38／37／11、APL完整19状态／3组及55语义owner全部unknown；物理33完整／22局部／0未观察和union35诊断保持。五段loss下降只证明训练样本拟合改善，不能读成解析错误百分比或全范围收敛；216不是216个epoch。最终普通CER≤0.1%、关键零错漏、完整表单及来源关系、正常最终流程、旧正确回归、独审与全源／泛化门不放宽。

下一轮填写区域分析已完成不同作者执行准入，并由Root一次实际回放完整7页、每页两套旧whole variants（不混入新099）：7次CPU投影、19.125秒、7完整产物、58完整输入副本、errors=[]、所有输入pins保持；OCR／模型／GPU／训练更新0。整页nonwhite RGB／8连通atlas、完整墨迹带候选、整组件跨界与未归属墨迹均只作观察，printed bounds／glyph／blank／field／semantic owner继续unknown；不同作者完整来源复核正在进行，不能由产物成功提分。

来源均在本地 .local/state/oer-quality-persistent-20261013/：R139正式报告report-update-13/report-update-receipt.json；分类全源assessment-independent/classifier-threshold-full7-source-review-01/final-paths.json；填写区域准入assessment-independent/writing-region-execution-review-01/final-paths.json及实际writing-region-actual-01/terminal.json、root-actual-tool-receipt.json。本次四文档完整旧字节、Root独立R139前缀回读和同步收据存于quality-current-doc-sync-20261014-03/。原件、gold、历史失败、fixed05、Drafting、活动数据库／服务和已发布模型保护；未发布权重或部署。

**2026-10-14 R138 当前状态：完整字段归属独审已结束，整体质量 Goal 保持 active。** 日志已发布138轮／1422证据／41文档卡／441指标；本轮新增质量指标或评分点0，旧137轮、1375证据、全部指标与历史点及五段216次计算／84次参数更新保持。下方R137“owner独审正在闭合”等文字是旧阶段记录，本条为当前状态。

完整七页、两套 whole OCR variant 的字段归属回放已由不同作者完成全域原件复核并封口：AoA完整字段38／未填37／关系11及55引导线语义owner，APL完整状态19／完整组3，全部仍unknown；每套候选新增正确通过0，不建议质量采用。完整填写区域、glyph归属和parent/group范围缺证；不能将白像素、编号、几何边或物理33/55及union35/55替代完整语义通过。14次CPU保存输入投影不含OCR、模型或权重更新；首次PNG插件audit启动失败保留。来源封口1164 immutable pins保持，原normal APL19/19及所有旧正确输出不改。

下一轮仅准备全7页方向分类阈值单变量消融：2400 recipe保持，Cls.cls_thresh .9→.99；原图新阈值7页、完整180度派生图旧／新阈值各7页，Hard21 CPU整页调用、不重试、不逐字段选择、不读gold、不改共享runtime。须不同作者审核exact master后由Root实际执行，再完整来源评分与旧正确回归；当前不先写质量收益。派生旋转页是同源控制，不是新来源泛化。完整最终门与原件、gold、fixed05、Drafting、活动数据库／服务、已发布模型保护规则保持；本条未宣布权重发布或生产部署。

来源均在本地 .local/state/oer-quality-persistent-20261013/：R138正式报告 report-update-12/report-update-receipt.json；字段归属 assessment-independent/form-ownership-full7-source-review-01/final-paths.json；窄屏显示 assessment-independent/round-layout-ui-source-review-01/final-paths.json；阈值 full-form-classifier-threshold-prepare-01/execution-plan.json；最新实际Goal goal-current-checkpoint-16.json。四文档完整旧字节与本次收据存于 quality-current-doc-sync-20261014-02/。

**2026-10-14 R137 最新状态：整体解析质量未完成，原生 Goal 保持 active。** 正式日志为137轮／1375份SHA证据／41文档卡／441指标；Root已逐项核旧136轮、1340证据、全部441指标和历史点、五段216次计算／84次参数更新保持。本条为当前状态，下方旧日期的blocked、prepare-only或尚未评分说明按历史快照读取，不覆盖本条。

R134–R136已闭合完整来源复核：AoA同55来源域任一单观察完整引导线物理支持16→33，新增17完整项，旧16正确／116对象／4155组件／51005来源像素全保；新通道29完整／2局部／24未观察，全通道33完整／22局部／0未观察。union35/55仅作物理诊断。字段38／未填37／关系11及APL完整19状态／3组仍unknown。R135完整七页2000参数的176文字出现来源链已封，APL新通道字面13/19不替换旧normal19/19；库存与组件数不是准确率。

R137完整七页单变量max_side_len 2000→2400已Root实际CPU执行、不同作者全域独审并封口：181出现，127来源行全部纳入比较，APL同19域字面13→15但4项改善伴随2项旧正确回退；AoA亦有旧正确文字被180度方向分类误转等回退。regressionGate=false，拒绝采用，没有新增质量点或typed分；旧normal和所有旧正确输出保留。独审999 immutable pins保持，断言数与变换控制不当原件准确率。下一分类阈值消融仅prepare，不关闭分类器、不按字段调参，完整源及派生180度图作为回归对照；源变换控制不称未见新来源泛化。

完整七页、两套whole OCR variant的字段归属候选已实际CPU回放（14次投影／7页／errors=[]），保全完整normal、五物理频道、所有文字、未关联与竞争关系；不同作者完整38/37/11及19/3原件独审正在闭合，完整填写区域、glyph归属及parent/group界仍缺证，不先提分。首次PNG插件惰性加载被精确输入audit阻断的actual01 exit1保留；fresh03仅在audit前预加载PNG插件，guard不放宽，15软件控制通过后actual02 exit0。候选关系、软件通过和来源质量通过分开报告。

页首五段loss曲线说明训练样本拟合，不能读成解析错误百分比或全范围收敛；216次计算不称216个epoch。轮卡兼容result与validation的显示修复已独审及Root真实浏览器验收，136历史轮结果逐项exact、undefined 4→0；历史ledger不改。整体最终门仍包括普通CER≤0.1%、关键零错漏、完整表单/来源关系、正常最终流程、旧正确回归、独立复核及全源/泛化，不缩范围或分母。原件、gold、fixed05、Drafting、活动数据库/服务及已发布模型保护；本轮未部署或发布权重。

来源收据均位于`.local/state/oer-quality-persistent-20261013/`：正式发布`report-update-11/report-update-receipt.json`；R136源评`assessment-independent/component-x-projection-source-review-02/final-paths.json`；R135源评`assessment-independent/textual-occurrence-full7-source-review-01/final-paths.json`；R137回退`assessment-independent/full-form-resolution-full7-source-review-02/final-paths.json`；显示修复`round-result-ui-20261014-01/round-result-display-receipt.json`；最新实际Goal`goal-current-checkpoint-15.json`；owner候选`form-ownership-actual-02/terminal.json`。此前sealed收据不改，本次四文档完整旧字节另存`quality-current-doc-sync-20261014-01/`。

**2026-10-14 R133 与全程训练图最新状态：整体解析质量目标未完成。** 已发布133条迭代记录／1251 SHA证据／41文档卡／441指标；旧132轮、1169证据与全部旧评分和失败保持。AoA同55域任一单观察完整引导线物理支持15/55→16/55（历史14→15→16），唯一新正确为aoa3-leader-d-witness-address-3；旧15正确与50995来源像素missing0。新通道11完整／2局部／42未观察，全部频道16／39／0；union23/55只作物理诊断。双作者来源复核、50控制及638 input pins通过；七个旧遗漏组件89来源像素仍未被新频道覆盖，不能把新增观察数量当质量。字段38／未填37／关系11继续unknown。GCC第1.1完整定义结构66/66、SCC完整XML公式来源结构3/3保持；全文CER、正常最终流程、完整表组／来源关系、全源／泛化与语义门仍待，最终标准不放宽。

针对用户反馈“图太抽象，训练了200多轮”，已逐行回读并展示五段已完成独立训练的84 optimizer updates／216 micro forwards，epochCount=null。五段首末完整更新步平均loss分别为0.167002→0.163011（下降2.4%）、0.167002→0.148519（11.1%）、0.103032→0.028083（72.7%）、0.035112→0.006612（81.2%）、1.641892→1.627548（0.9%）。这是五段完成作业的训练计算，不是216个全数据集epoch，也不是133条解析／工程迭代都在更新权重。全部216原始点、84步均值、独立run边界、原件结果与旧局部评分均保留；loss下降不作为原件准确率。两段MinerU无可观测原件识别收益；Paddle表段有fit改善但完整结构／空态未过；FWP20有局部正常四页收益；FWP4协议仍0/4且一页新增循环，未采用。新版已经静态／DOM自检、独立原始loss／来源结论复核及Root实际桌面浏览器核验，完整loss可展开回溯。手机布局与屏幕阅读器未实测。

原生Goal checkpoint10实际回读为2026-10-13 16:33:00 UTC、status=blocked（createdAt1791891681、updatedAt1791898151）；持续目标未complete、cancel或pause，本地工作继续与原生active分开报告。主发布收据为`.local/state/oer-quality-persistent-20261013/report-update-09/report-update-receipt.json`；独立来源终封为同根`assessment-independent/index-fit-source-review-03/index-fit-independent-final-paths.json`；历史loss审计为`quality-trend-ui-20261014/history-audit-01/history-audit-receipt.json`；本次UI与文档增量另封于`training-history-ui-20261014/`，此前报告和UI收据不改。下一步先沿完整全页组件资格、mutual-next邻接、链形成与拟合的真实拒绝支路定位未入链支撑；新拒因诊断尚未执行，不能借旧spacing原因替代。新增权重训练／模型／GPU／OCR／生产采用0，原件、gold、fixed05、Drafting、已发布模型与活动服务保持。

**2026-10-13 R131 最新实际状态：整体解析质量仍不够，持续目标未完成。** 已发布131轮／1149 SHA证据／41文档卡／441指标；旧130轮、1095证据、440指标对象与分母、失败和sealed收据保持。AoA完整四页dot04实际CPU执行后，经独立原件RGB／组件／两端点复核及真实同55域scorer，任一单观察完整打印引导线物理支持由14/55提高到15/55，旧14项及33849旧源像素missing0；新频道36观察中只有一条完整支持，其余不充作完整。新频道来源域为1完整／22局部／32未观察；三频道为15完整／40局部／0未观察。累计union17→22/55只作reviewer物理诊断，第三页旧状态保留，不称新的全部端点重评或producer语义join。新增图表明确为来源物理诊断，字段／空态／关系未采用。

APL补充5个synthetic控制确认旧CTC归属门不能区分active strip内额外浅／深墨迹，含与marker连接且组件数不变的反例；只登记准入旁证，不另造成功轮，原件模型请求0。完整blank／value／typed-empty19及三组、AoA38字段／37未填写／11新关系继续unknown；SCC R129完整XML公式来源结构3/3与GCC R128完整1.1来源结构66/66保持，渲染、数学／法律语义、完整正常最终采用、全文CER及全源／泛化门仍待。原参考、分母、普通CER≤0.1%与关键零错漏等最终门不放宽。

原生Goal实际checkpoint08为2026-10-13 15:25:38 UTC、status=blocked（createdAt1791891681、updatedAt1791898151）；未complete、cancel或pause，已有unfinished目标的新建拒绝历史保持。本地质量工作继续，不能假写原生active。R131主收据为`.local/state/oer-quality-persistent-20261013/report-update-07/report-update-receipt.json`，独立源终态为`assessment-independent/aoa-dot04-source-review-01/aoa-dot04-independent-final-paths.json`（同persistent根目录）。renderer staged／final均actual exit0，ledger＝generated JSON＝HTML嵌入数据；Root已实际浏览器核AoA14/55→15/55图表及unknown边界，console warn/error为空。下一轮完整四页sealed04只读branch trace已Root actual exit0、原9/27/0/0观察exact，独立来源漏点到真实支路映射仍待；此诊断没有新来源分或参数修复。新模型／GPU／权重训练／生产采用0，原件、gold、fixed05、Drafting、已发布模型及活动服务保持。


**2026-10-13 R130 最新实际状态：整体解析质量目标未完成，继续本地迭代。** 已发布 R129–R130，训练日志为130轮／1095 SHA证据／41文档卡／440指标；旧128轮、1057证据、439指标对象、分母、阈值和全部已封收据完整保留。SCC实际2b原件完整包的保存normal／native CPU后处理已由Root实际执行exit0，再由不同作者沿预先冻结来源参考独立评分：完整parsed XML公式树3/3、分数3/3、下标18/18、来源角色42/42、m:t文字叶及原parent／child ordinal57/57、原父段落锚3/3、新节点ID与来源位置684/684、来源顺序2/2，各域incorrect／unknown均0；完整6185 native节点、34 rawParts与sourcePackage原字节、799 legacy块及旧3个flattened公式块全部保持。新数学ID明确是producer／source／part／path派生观察，仅向两个完整副本追加字段；不是旧native math token、不是主parser或线上采用。sample-14新增一个首actual3/3来源结构图表，不编造旧0/3基线。XML原字节由旧rawParts保全，树只记录parsed XML结构；完整显示／有效修订态、全文CER、数学值、法律含义、全源及泛化仍待。

AoA完整4页sealed dot02 CPU拒绝轨迹actual exit0，accepted5/12/35/0与旧new频道52观察exact；页2缺失于new频道的5个旧run／86组件均仍在实际连续链中，唯一拒绝原因是global_absolute_slope。此为已确认共享原因诊断，没有新候选来源收益；初次Python312缺numpy的exit1已保留，改复用原已安装环境，无新增依赖。旧03完整物理频道及原件保持；倾斜regular component run独立物理观察正在prepare-only，尚未实际执行或来源评分，不能把它称为水平、field或blank。APL完整blank／value／typed-empty19及三完整组、AoA38字段／37未填写／11新关系继续unknown；GCC R128完整1.1已闭，但其余深层条件／全文及全源门仍待。

原生Goal最新实际回读为2026-10-13 14:43:40 UTC、status=blocked（createdAt1791891681、updatedAt1791898151），收据`goal-current-checkpoint-06.json`；原持续目标未complete或取消，新建unfinished拒绝历史保持，本地质量工作继续，不能假写原生active。主发布收据`.local/state/oer-quality-persistent-20261013/report-update-06/report-update-receipt.json`；SCC独立终态`assessment-independent/scc-omml-actual-score-01/scc-native-math01-independent-final-paths.json`；AoA诊断`aoa-dot02-rejection-trace-01/terminal.json`，均在同一persistent证据根目录。既有renderer staged／final实际exit0，ledger＝generated JSON＝HTML嵌入数据。新OCR／模型／GPU／权重训练／生产采用均0，原件、gold、fixed05、Drafting及活动服务保持。

**2026-10-13 R128 最新实际状态：质量目标未完成，继续本地迭代。** 已追加 R126–R128：128轮／1057 SHA证据／41文档卡／439指标（原438指标对象与分母完整保留，仅新增一个GCC同66域指标）。原renderer staged／final实际exit0，ledger＝generated JSON＝HTML嵌入数据；旧125轮、997证据、训练失败、阈值、比较矩阵及sealed收据保持。GCC完整74页保存正常捕获的两次CPU结构回放与独立源评已闭：第1.1节完整66定义结构从62 correct／0 incorrect／4 unknown提高到66／0／0，定义成员行162/169→169/169、顺序关系60/65→65/65、边界62/66→66/66；两子项2/2与共用尾句1/1保持，第1.1完整来源结构组1/1。本轮只用通用配对引号＋means／shall be as defined与实际列几何，不按术语、页码或gold补造；旧01整个payload及63观察、617节点、3790行、314000 glyph库存与完整74页捕获保持。此为已暴露开发源的局部结构通过，完整GCC文字CER、全源／泛化／法律适用及业务验收仍未取得；定义引用目标解析保持unknown。

CTC空态方案在准备阶段被实际synthetic反例拒绝：35软件控制通过不能抵销额外浅色墨迹在active token strip内被误归编号的expected-red exit1；原件模型请求0，没有表单质量收益。现装MinerU官方合同无可直接复用Form／field／entry-state接口，逻辑空cell及矩阵补齐不证明源blank；未自造新VLM task。APL完整blank／value／typed-empty19及三完整组、AoA38字段／37未填写／11新关系继续unknown，原件、gold、分母和最终门不放宽。SCC实际2b原件三OMML的分子／分母、base／sub结构正在prepare-only，未执行、未评分，不提前提分。

Root于2026-10-13 14:23:37 UTC实际回读原生Goal仍blocked（createdAt1791891681、updatedAt1791898151）；`goal-current-checkpoint-05.json`保存当前工具状态。已有unfinished目标使新建被拒绝，未取消或complete旧目标来换一个小目标；工作继续与原生active分开报告。主发布收据为 `.local/state/oer-quality-persistent-20261013/report-update-05/report-update-receipt.json`；GCC独立源终态为 `assessment-independent/listentry-definition-prepare-01/gcc-definition02-independent-final-terminal.json`。本次新OCR／模型／GPU／权重训练／生产采用0，fixed05、Drafting、原件、已发布模型和活动服务保持。

**2026-10-13 R125 最新实际状态：质量目标未完成，本地迭代继续。** 当前原生 `get_goal` 回读 createdAt=1791891681、status=blocked；新建被 unfinished Goal 拒绝，未调用 complete 来换新目标。工具状态收据 `goal-current-checkpoint-04.json`／`goal-create-rejection-04.json` 与下方旧 active 快照分时读取，不能把工作继续写作原生 active。R122–R125 已追加原日志：125轮／997 SHA证据／41卡／438原metric；原renderer staged／final实际exit0，旧121轮、919证据、指标对象、分母、阈值及失败历史保持。APL完整3页真实35次CUDA BF16正常识别及自动CPU gap尾段已独立源核：身份／标题归属19/19、序列3/3、fresh gap19/19，旧35请求／raw-middle对象和更早16请求／块保全；完整blank／value／typed-empty19unknown、三完整组unknown。AoA点线02虽有14/55单观察完整支持，因丢旧1001像素／3旧leader明确拒绝替换；03独立数组保旧13对象／321组件／3959像素／10旧触及leader、missing0，新增52单列，完整leader14/55；combined union17/55仅独立诊断，字段38／未填写37／新关系11仍unknown。收据及独立评分位于 `.local/state/oer-quality-persistent-20261013/report-update-04/` 与 `assessment-independent/normal02-dot03-independent-final-paths.json`。最终质量门不放宽；表单字符支持与GCC definition frame尚在准备，不记质量收益。权重更新／生产采用0，原件、gold、fixed05、Drafting及活动服务保持。

**2026-10-13 R121实际独立来源评分已闭，持续Goal保持active：** 候选03仅新增源中立 `sourceGapObservation`，从相邻实际marker／heading／footer边界形成完整源观察区，拒绝跨标题、邻编号相叠和目标裁缺；APL完整三页的Root fresh CPU重放及独立视觉／像素核验确认新域19/19完整包含并区分相邻编号。旧midpoint域4/19、身份19/19、标题关系19/19及三组顺序3/3逐项保留；新观察区不表示打印字段边界或墨迹ownership，完整blank／value／typed-empty仍19unknown、三完整groupunknown，未计通过或错误。AoA完整四页孤立(iv)因缺前heading源边界unsupported且不造gap图，旧源域及R117三个OR保持。677先前pins、03代码12pins、169APL／271AoA输入重哈希保持；参考、分母、contains门和失败历史不改。实际封口 `assessment-independent/candidate03-independent-final-terminal.json`，新域源评 `candidate03-apl-actual-gap-source-score.json`；R121报告已追加：121轮／919SHA证据／41卡／438原metric，原renderer staged与final实际exit0，源ledger＝JSON＝HTML；Root已实际刷新回读active与sourceGap19/19，收据 `report-update-03/report-update-receipt.json`。此前R120尚未评分属于历史快照。新正常GPU自动caller仅完成CPU准备，须实际完整原件流程和独立来源回归后报告；持续Goal createdAt=1791891681不因局部轮次complete。

**2026-10-13 持续Goal实际推进到R120，保持active：** 新Goal createdAt=1791891681未取消、未complete。完整APL三页／AoA四页的保存正常结果已进行CPU源结构投影和独立源评；APL19条目身份、19标题归属与三组顺序源对应，旧正确项保持。初版编号bbox外白像素被误推为完整blank，真实补充反例4/5、exit1，R119明确拒绝；R120安全版只保可证结构和精确选区像素，完整blank／value／typed-empty19unknown，冻结观察包含4/19、15残差仍待，不计unknown为错误或通过。原参考、分母、历史失败不改，模型／GPU／新OCR／权重更新0。原生PDF三页无文字对象，不能用空文字层证明blank。实际证据在 `.local/state/oer-quality-persistent-20261013/assessment-independent/`；报告120轮／892证据／41卡／438原metric，原renderer及Root浏览器回读通过，收据 `report-update-02/`。候选03仍待实际来源评分，不提前记收益；固定05、Drafting、活动服务及已发布模型保持。

**2026-10-13 最新持续质量Goal已active：** 用户明确“不够好goal就别取消啊，再写个goal，搞起来”。Root已实际新建createdAt=1791891681、无token预算的持续Goal，从R118继续；只有已确认范围的解析质量最终门达到、必需质量项无未证明缺口且旧正确项保持时才complete，局部提分／完成轮次仅作为进展，继续active。先攻完整表单区域、空态／字段身份与来源归属，再处理复杂正文层级／条件范围等高优先级残差；沿用冻结原件参考与QUALITY-PLAN最终门，不降低标准或分母。新证据目录 `.local/state/oer-quality-persistent-20261013/`，创建收据 `goal-start.json`；下方旧有限Goal complete是R117／R118历史，不表示质量已达标。保护项和本地解析迭代授权保持，不自行部署或发布新权重。

**2026-10-13 当前Goal终态：本次两轮有限质量闭环完成，整体解析仍未验收。** R117／R118已完成完整输入、冻结同域源评、旧正确项回归、独立核验和日志追加；Root实际浏览器回读最新成绩后，工具将createdAt=1791886342的Goal标为complete（updatedAt=1791888392，用时2050秒）。此Goal目标是从R116选取下一真实缺陷并完成最小质量迭代，完成范围为AoA导出保全和APL印刷编号覆盖两项；不表示全源最终门、完整表单空态／关系或新来源泛化通过。实际工具收据 `.local/state/oer-quality-goal-20261013/goal-completion-readback-01.json`；下一来源缺口优先为完整表单区域、typed空态／槽位身份与关系，其次复杂正文层级／作用范围。不因下方历史active／blocked快照重复旧Goal或已完成的APL相同覆盖因素。

**2026-10-13 Goal质量迭代的新源证据：** 以R116为基准，已完成AoA完整四页保存产物的官方CPU导出回放，以及APL完整三页原件的真实CUDA BF16正常解析；两轮均有独立来源评分和旧正确项回归。AoA默认导出的三个源OR从2/3→3/3，只在副本导出视图投影误分类的一个OR，47旧导出块逐字节保持；同53区域导出域32/53→33/53与原raw域39/53分开，原33/38关键文字、18/21符号不改。APL只复用既有源中立区域覆盖因子，19个印刷Roman从0/19→19/19，三组5+5+9原序从0/3→3/3；16旧请求及16旧raw块保全，无新增重复或捏造名称。新增裁图仅覆盖编号，19空栏的typed身份／完整空白面积仍unknown，普通全文CER未建立完整gold，不能追认为表单全部正确。原参考、分母、失败和保护项保持，权重更新0；局部候选保留本地，整体来源质量仍未达到最终门。实际终态见 `.local/state/oer-quality-goal-20261013/assessment-independent/` 与handoff顶部，日志按R117／R118追加。

**2026-10-13 用户明确要求设Goal继续迭代解析质量：** 用户询问目前质量，不够好则再来一轮，并要求“给自己设置goal目标任务，然后迭代它”。Root已创建active Goal（createdAt=1791886342，无token预算），沿用R116与既有冻结来源参考，优先重新核实表单／复杂正文的内容和结构残差，实施最小规则／参数／必要训练改进，做完整输入同域源评、旧正确项回归和独立复核，每轮更新现有日志。未评范围不能记成错误率，局部高分不能记成全源通过；真实失败及未决归属保留。当前授权覆盖本地质量迭代，不自动切换固定05、Drafting、活动库、已发布模型或生产部署。

**2026-10-13 质量优先的本地调整恢复：** 用户确认技术栈已定、Camelot已加入PDF表格处理，要求“按照之前的方法和基准继续调整，第一优先级是保证质量”，并再次明确“继续”。允许沿用既有原件参考、同域分母、完整来源输入和旧正确项回归进行本地解析规则／参数调整及按残差必要的训练；不将微调限定为权重更新。当前Camelot实施与完整226页产物来自独立交付目录，主工作区旧R113报告不能代表今天代码状态；恢复现有报告、补登实际基准与每轮证据，不覆盖旧失败。先修复已证实的外成员续接评测可达性，再核源中立跨页关系候选；缺乏确定归属证据时保持unknown，不按目标页／预设缩写／gold补造owner或为高分放宽参考。新增质量收益须真实同域源评与旧正确项回归证明；Drafting、固定05库、原件和已发布模型保持。本条覆盖下方旧“仅调查／未启动”阶段限制，不自行授权生产切换、上传或推送新调整。

**本次局部终态（2026-10-13）：** 三方法各226页保存产物关系回放及冻结参考源评actual exit0；Stream完整已标来源组4/5→5/5、表外成员11/12→12/12、typed关系86/87→87/87，主格672/672与完整主表5/5保持。收益来自原1316→1317 Note9联合语法续接，已闭合的原1321列表不向1322延伸；不是新provider／权重训练。参考、分母及原同页组不改，开发来源评分不等于父条／法律／blind／通用或业务验收，不自行采用到固定05。详细代码、完整回放和独立评分见[handoff顶部](./HANDOFF-ocr-embedding-rerank-20261008.md)及 `.local/state/oer-quality-adjustment-20261013/`；后续扩大来源和复杂跨页形式时继续沿用完整输入、明确拒绝及旧正确项回归规则。

<!-- public-split-delivery-20261013:start -->
**2026-10-13 公开分离交付授权与最终结果：** 用户已授权实施下一轮规格并要求先安装 Ponytail、激活 hooks；本轮代码已实施、独立拆分并推送公开 fork，下方“仅编制 spec／尚未开工／未 push”属于旧快照，不覆盖本条最新状态。私仓交付之后，用户进一步要求将本地代码、冻结模型权重和数据库交付到同事的两个公开项目，并在告知数据库包含完整 GCC／GCT／SL 条文正文及历史来源路径后明确确认“模型和这份数据库一起公开”。这次授权允许公开该原字节固定05库及冻结模型，覆盖旧的仅本地／仅私仓上传限制；密钥与运行日志仍保留本地。

前后端已独立拆分：[前端 PR #6](https://github.com/s1765029698/ai_consense_web/pull/6) 与[后端 PR #5](https://github.com/s1765029698/ai_consense_service/pull/5) 均 Open、Ready for review；准确 HEAD 分别为 `d4939eca9ec68ec44e0c1302e4c4f3f287dc1d0c`、`9f1e785c4ecfcb816513fe45486fd38fb5d8e49e`。当前账号对同事 upstream 只有 READ，因此使用对应公开 fork 的 `codex/oer-vetting-advisory-20261013` 分支提交 PR，未合并或直接改 upstream main。后端公开 fork 的[模型／数据库 Release](https://github.com/Dying-Ember/ai_consense_service/releases/tag/oer-models-fixed05-20261013) 已发布，`draft=false`、`prerelease=true`；北京时间 2026-10-13 11:02:25 完成最后卷核验，11:05:28.514701+08:00 最终回读确认 9 个附件（7 卷、README、manifest）的 size 和 GitHub native SHA256 全部匹配、两 PR 均 `isDraft=false`／`MERGEABLE`、远端 HEAD＝本地＝fork、两个独立 clone clean。GitHub checks 均为空，不称 CI 通过。

公开包包含冻结模型、05 独立向量库及 provenance、模型许可说明；未额外附原始 PDF、来源 descriptor 闭包、corpus／runtime plans、活动 H2、私有上传项目、密钥或运行日志。可信 manifest SHA256 为 `5d13e569422e87a7428792dd7be5795ce1a110c699e1bd82fdee67637ce6b3c6`；Release manifest 已实际下载，并与远端源码 manifest 原字节匹配，未重新下载全部 7.23 GB 分卷。完整本地包已在全新目录实际恢复 82 文件，并核原 72 模型／数据库文件 SHA 一致、SQLite 只读 `quick_check=ok`；[本地验证收据](https://github.com/Dying-Ember/ai_consense_service/blob/9f1e785c4ecfcb816513fe45486fd38fb5d8e49e/artifacts/oer-freeze-20261012/verification.json) 与[远端核验收据](https://github.com/Dying-Ember/ai_consense_service/blob/9f1e785c4ecfcb816513fe45486fd38fb5d8e49e/artifacts/oer-freeze-20261012/remote-verification.json) 已提交推送。发布与字节恢复不证明异机 runtime、原件回取、完整业务、OCI 或 H800 验收；活动 Local05、H2、模型服务、Drafting 和主 dirty 工作区不切换，原绝对路径需显式映射，未改写冻结库。
<!-- public-split-delivery-20261013:end -->

**下一轮开发 spec 入口（仅编制，尚未开工）：** 用户指定 to-spec，要求将整体技术栈、新 demo、OER／Vetting／Advisory 写入下一次开发规格，并明确“先别开发，就写 spec”。[主规格](./specs/oer-vetting-advisory-next-development-20261012.md)及[本地任务图](./specs/oer-vetting-advisory-next-development-tickets-20261012.md)已按 ready-for-agent 编制；用户确认主验收链为真实上传→固定／项目取证→Vetting报告或Advisory答案→对应原文回取，离线解析与H800制品另验。图纸排除、Drafting冻结、05开发基准及已知缺陷保持；候选版本经验证后采用。本轮不启动代码、安装、训练、模型、迁移或部署，不创建远端issue。

**2026-10-12 新demo HTML须共同纳入整体技术栈评估：** 用户再次明确`Ref/communications`的新demo也要考虑。当前目录已核为`ConSense-CAC-Proposal-DemoV2.0.html`，SHA与已审版一致。demo的Vetting底稿／导航／裁决／报告、Advisory咨询／引用，以及现有代码的交接展示，与实际代码、源证据和既定范围共同作为[交付评估第3.5节](./delivery-stack-decision-20261012.md#35-新demo-html也是交付评估依据交互要求到真实依赖的映射)输入；不把demo固定答案、无finding默认pass、localStorage状态、已排除材料或已撤回风险上限自动采纳。整体含OER＋Vetting＋Advisory＋共用推理／检索／部署，解析子调查的限定不缩小整体范围。图纸排除、Drafting冻结保持，本条不宣称demo浏览器验收或功能实现完成。

**2026-10-12 整体交付技术栈必须包含Vetting和Advisory：** 用户再次明确两模块必须纳入评估。前述“仅解析质量”的范围限制只适用于原件残差子调查，不排除整体技术栈中的检索／证据／业务LLM／任务、会话、引用、报告与部署。整体模块及共用服务的依赖与源码缺口见[交付技术栈评估第3.4节](./delivery-stack-decision-20261012.md#34-完整交付范围vettingadvisory及共用服务的技术栈判断)。这是技术评估范围澄清，不表示所有功能已实现、已采用候选或已通过业务／H800验收；Drafting冻结与图纸文件排除保持。

**2026-10-12 本次解析技术栈评估的优先级：** 用户按内容漏失严重程度与影响范围要求优先处理表单、复杂正文，原第三项中的图纸文件随后被明确完全排除。范围内规范插图／流程示意的保全沿用原范围，后续有线复杂表、公式、扫描／隐藏OCR及Word特殊内容按原件残差处理；不将embedding、召回、排序和业务界面缺口混入解析清单。三路源证据／官方能力研究的统一结论见[技术栈评估第2.3节](./delivery-stack-decision-20261012.md#23-其他解析残差与用户最新优先级先补引擎图纸只识别身份)：先补表单结构、正文层级／读序、区域覆盖和导出保全引擎，当前没有新增第三套OCR／IDP框架的必要性证据；未宣称现有模型质量已通过，未据评估提问启动实施或切换活动库。

**2026-10-12 最新明确排除：图纸文件不参与解析。** 用户严肃明确“图纸文件不在解析之列，完全不在”，并补充“不要在图纸的细节解析上下功夫，知道它是图纸就行”。上传文件身份／路由层仅识别并标记其为图纸，然后从解析任务排除；不开展图纸正文OCR、尺寸／轴线／符号／图例精读、CAD／矢量结构或工程关系解析，不为图纸增加模型、训练或依赖。图纸以PDF、扫描图像等格式提供也仍排除，不能因格式可解析而纳入。本条覆盖旧文档中把图纸列入项目解析范围的表述；既有原件和历史记录保留。范围内合同／规范／清单中提及图纸的文字不等于图纸文件，仍按其已确认文件身份处理。

**2026-10-12 无框表之外调查的范围澄清：** 用户明确本次只问原件解析质量；embedding、召回和排序问题由后续Vetting／Advisory模块调整，不纳入本次解析改进清单。本次核查限定为完整输入／区域覆盖、正常parser文字与导出保全、读序／条款层级／源注释归属、完整图像裁切、公式／表单控件／空白／修订可见态和旧正确项回归。原件内结构归属属于解析，项目采用／法律适用判断、模型入包、业务覆盖与引用UI另行评价。补充记录见[源解析质量审计](../../.local/state/oer-beyond-borderless-review-20261012/source-quality.md)；同目录RAG／业务及封存工程调查作为后续参考，不作为本次解析残差。本澄清只限定本次调查，不更改21:00开发基准冻结后的既有安排；未启动新的解析、模型或业务作业。

**2026-10-12 Camelot评估问题澄清：** 用户询问的是“直接读取原生PDF文字层、恢复表格候选结构，再加代码解析引擎”的接入价值，不是全局provider替换。评估结论及当前代码接缝见[交付技术栈评估第2.2节](./delivery-stack-decision-20261012.md#22-用户澄清camelot文字层恢复代码解析引擎的接入价值)：该分支值得接入验证，逻辑表／Notes条件组装和native PDF表格投影仍待实现；未将用户的评估提问作为已采用、实施或活动索引切换授权。

更新：2026-10-12，Asia/Shanghai。本文是 OCR → embedding → rerank（OER）的当前范围与规则入口。准备资料、安装模型、解析、标注、切块、索引、评价或接入服务前先读取本文。规则来自用户明确指示、已采纳的 Q1–Q8、用户纠正后的 Q9、已采纳的 Q10=A、限定为轻量入口的 Q11=B、赛方范围核对记录和本轮原件核验；实现进度单独记在 [handoff](./HANDOFF-ocr-embedding-rerank-20261008.md)，通过规则讨论不等于通过实验验收。

用户后续明确更改某条规则时，在本文更新该条及依据，并同步相关文档的入口或历史说明。已确认规则继续有效；技术参数、可查证的文件事实和常规实现选择由执行者核验解决，不重复把它们交给用户决定。

**2026-10-12 今晚交付技术栈评估入口：** 用户要求结合[无框表隔离调查](./oer-borderless-table-isolated-investigation-20261012.md)、communications原型及Vetting／Advisory共同评估，尽快确定超算依赖范围以减少安全扫描变动，并说明同事尚未搭好环境。统一结论见[交付技术栈评估与锁定建议](./delivery-stack-decision-20261012.md)。这是研究与部署建议，尚未采纳／安装／扫描／部署：主体框架保持，建议独立Camelot传统候选与服务式Qdrant目标；原件质量、当前05、Drafting冻结与Q10=A边界不因该评估改变。实际镜像／Linux依赖／模型／字体制品和扫描状态不得用候选名称替代。


**2026-10-12 19:40 最新硬截止安排：北京时间21:00冻结开发基准并实际入库，随后推进Vetting与Advisory。** 用户明确“到晚上九点的时候不论效果如何，都冻结一套作为基准，并写入向量数据库”，并要求在剩余约一个半小时内最大化整体质量。此安排覆盖本文和旧Goal中“全部解析最终门通过后才做固定库/业务”的阶段顺序：21:00使用当前最佳已完成正常解析结果，允许带明确已知缺陷/unknown的开发baseline入库；不把该开发基准称为最终质量采用、blind或完整比赛验收。原最终质量标准保留为后续提高目标。范围排除、原件/版本/定位保全及Drafting冻结保持。

截止前优先有共享收益的输入/路由/整表缺口，不再做逐字符精修、盲目SFT或无收益参数轮。计划19:40–20:15执行有界完整原件解析改进并并行准备固定GCC/GCT/全部适用SL技术资料manifest与切块；20:15停止追加解析试验并把GPU交给实际CUDA embedding，20:40前准备持久向量库；20:40–21:00冻结/读回/来源回取和交接。时间为任务预算，不是已完成事实；每段实际终态在handoff和HTML记录。新provider只按完整表组比较、保native正文和旧正确项；未完成/无收益候选不替换旧完整结果、不推迟截止。使用新独立baseline Qdrant目录或collection，避免与现有Local DB锁并写；冻结记录源码/解析配方/模型制品、来源身份、覆盖/切块/实际向量计数与已知缺陷。开发baseline供Vetting/Advisory开始测试和修改，不复用未填2a模板。


## 授权与比赛任务

**2026-10-12 当前页面热修例外：** 用户报告外部浏览器审阅修改缺少标记，连续授权“继续修”，并要求增加内存、明确本机128GB。此次只恢复旧前端页面与受2GB堆耗尽影响的后端运行；后端堆配置已可持久设置为16GB。随后用户指出点击修改块后仍展示无关变量证据，并明确“直接热修吧”：允许修审阅块→实际已保存动作→相关变量取值／证据来源的点击联动，单变量直接展示，共同动作立即展示相关变量及各自证据，人工修改／指引清理／无法归因分别说明，不把上次选中的变量当本次来源。此为窄范围交互修复，识别harness、确认值、已保存正文及排版仍冻结，OER原件质量主线继续；实际部署与浏览器验收以handoff增量为准。

**2026-10-11 最新阶段安排：先覆盖各类文件的正常解析基线，再逐轮提高最终准确度。** 用户明确“先保证每种文件都有个差不多的水平，然后再微调”，并解释微调是慢慢提高最终准确度，随后指出逐字符细抠会影响时间。当前解析阶段先做覆盖轮：按范围内文件／任务家族复用已有真实完整输出，补齐缺少正常基线的家族，优先修漏页、漏条款、输入缺失、错误读序／整表归属、条件范围错挂等明显业务缺陷，形成逐类能力矩阵与主要残差。随后提高轮按共享影响范围和业务风险处理问题，解析规则、参数和必要权重训练都是手段，不把微调理解成必须SFT。已有作业安全收尾；没有新归因不为同一小区域追加精修轮次。

现有“CER≤0.1%、关键项零已知错漏”等是最终解析采用的验收线，不是每类探索基线才能展示或继续调查下一类的前置。基线覆盖完成不冒称最终门通过；所有所需固定资料仍须最终解析验收后才进入固定库质量。字符差异由脚本批量计算，原件参考与输出分离；人工优先核关键文字、作用范围、复杂表格和自动差异区域，避免把所有页逐字符手工雕琢当当前默认工作。范围排除、质量第一、固定库优先、Drafting冻结及后续工程后置保持。

**2026-10-11 当前 Goal 与验收顺序：各类正常完整解析基线 → 渐进提高解析质量 → 固定资料向量库质量 → 项目接入与业务 → H800。** 10-11 创建时 Root 已验证新 Goal 为 active（createdAt=1791724559，无 token 预算）；10-12 曾读回 blocked，用户明确“继续”后实际原件质量工作恢复，本次最新工具已读回 active（updatedAt=1791768556），目标身份不变，未自行重建。此前报告保留当时工具状态，不冒称目标完成。createdAt=1791721859 是此前目标的历史创建记录，不作为当前目标身份。当前18个任务家族均已有完整正常输入基线，继续按共享缺陷和业务风险提高；当且仅当固定 GCC／GCT／SL 技术资料的全部所需来源范围解析验收通过，才进入固定库切块、embedding／rerank 与检索质量。固定库通过后再做项目资料接入、来源兼容、官方题／Vetting／前端及发布，最后进行 H800 部署与推理验收。当前阶段只推进原件解析、监督与必要训练，每轮训练／有新质量证据后更新现有 HTML。

**2026-10-11 最终部署目标已确认：Kubernetes（K8s）＋单张 H800 80GB。** 后续从独立、已验证的 Linux 环境建立目标镜像及依赖锁，不复制 Windows venv；已有本地 Linux MinerU full 成功路线与具体证据见 [handoff 当前部署记录](./HANDOFF-ocr-embedding-rerank-20261008.md)。冻结前核验 NVIDIA GPU device plugin、容器 runtime 与 GPU resource 配置，持久保存 models／raw／parsed／index，并用单卡阶段资源调度避免 OCR 与业务 LLM 共驻冲突。集群版本、runtime、storage／PVC 能力及最终所选 LLM 的同卡管理方式尚未确认，必须按实际集群验证；不把 H800 总容量当作 OER 可用余量。约120B为历史候选，不是规模要求，选型遵循下方2026-10-12最新任务质量／上下文规则。当前仅确认部署目标，尚未制作 K8s manifest、构建目标镜像或部署集群；部署准备后置，不阻挡当前原件基线与渐进提高解析质量。

K8s GPU 与存储边界：默认 NVIDIA device plugin 的 `nvidia.com/gpu` 通常按整卡资源分配，不等于为每个 Pod 隔离显存额度；time-slicing 等共享配置也不能自动证明显存隔离或与赛方 MoE 安全共驻。GPU 调度仍须结合实际模型所有者、显存峰值与阶段释放／恢复验收。当前 Qdrant Local 为文件式、单进程所有者，不能让多个 Pod 直接共写同一个 local 路径；后续需要多副本或独立部署时，验证服务式 Qdrant 的持久卷／集群配方，或保持明确单写者方案，不在当前解析阶段迁移。这些集群能力在后续工程阶段实际核验，不把预先指定集群版本或访问方式作为用户必须补答的前置。

**2026-10-12 部署研究澄清：OCR 与业务 LLM 不会同时使用，允许按阶段分别加载／卸载。** 用户要求全面分析各策略，并明确业务逻辑已经清楚，不重开业务访谈。随后要求按 LLM 实际参与的任务类型建立选型与评估基线，**不限定约 120B MoE**，其他 dense／MoE 模型均可比较；用户报告满血 MiniMax M3 官方 API 已取得不错的 Drafting 效果，作为质量参照保留。约 120B 继续是历史资源候选／赛方模型待核信息，不是最终主模型必须达到的规模。正式比赛模型供应／替换规则仍按实际赛方资料核验，官方 API 的开发质量参照不自动成为额外比赛算力授权。具体研究见 [部署策略](./oer-llm-deployment-strategy-research-20261012.md) 与 [任务评估基线](./llm-task-evaluation-baseline-20261012.md)；它们的技术建议和未实测成绩不作为已部署状态。当前解析质量主线、Drafting harness 冻结及工程／H800 后置保持；研究不会启动新的 Drafting 实现或服务切换。

**同日最新选型修正：先依据实际任务分类的公开评测表与可复核测试经验选择，显存放在质量筛选后的部署准入。** 用户明确新的小模型可能优于旧的大模型，不能由参数量、dense／MoE或静态权重排主次，也不能仅列未来测试方案代替当前证据调查。主方案按**至少256K上下文、优选512K**决策；精确token上限、原生／官方扩窗、实际长文质量和部署验证分别记录，窗口包含实际输入、历史、推理及最终输出，短窗模型只能保留作能力对照。用户指出本机Bonsai27B已试且效果不足，实际制品为Qwen3.8-27B底座的Ternary-Bonsai-2-27B-PTQ1_0；须把本机失败与M3正面运行纳入选型，不能把未测旧版27B重新列为默认首选，也不把三进制制品成绩泛化为同底座BF16／FP8实测结论。研究更新见 [质量与长上下文选型](./llm-task-quality-selection-256k-512k-20261012.md)，没有因此授权修改冻结harness、加载新模型或切换服务。

**同日最新归因补充：用户指出本机显存不足、上下文配置可能影响Bonsai效果，并要求调查更大模型进入单H800的办法。** 已保存第五轮实际配置为64K，尚无完整256K／512K业务验证；本机配方FAIL不能升级为底座或H800足窗口下的模型能力否定。历史18项清单确实被输入分片，但第五轮Bills／Sections完整短源已入包，保存引擎记录没有窗口耗尽证据，具体资源因果仍待受控验证。权重量化、GPU读取CPU权重、CPU专家计算、专用lookup offload及活跃KV优化分别研究；prefix缓存offload不当作单条活跃512K容量证明。具体证据与候选见 [更大模型与上下文归因](./llm-h800-larger-model-and-context-strategy-20261012.md)。Qwen3.8-27B官方FP8仍是本地第一顺位验证建议，Flash-Next混合驻留为更大质量挑战者，均未采纳或完成H800验收；本轮不启动新推理、改冻结harness或切换服务。

**同日最新联合选型口径：Drafting、Vetting与Advisory必须一起评估，Vetting和Advisory均先通过RAG初筛。** 不从Drafting的抽取成绩或M3正面体验推定另外两模块通过；重点比较相关完整证据包之后的条件／例外／修订理解、跨来源审查、完整发现、有据回答、引用和缺证处理。RAG召回／结构扩展／排包缺口与LLM拿到证据后的错误分别评价；初筛命中不等于必要依据完整，零发现不等于全面无问题。闭卷知识、coding及纯检索分数不主导三个模块的LLM排名。至少256K、优选512K总窗口保持，不因RAG改成短窗或裁掉完整条款／表组。研究判断见 [三模块RAG联合选型](./llm-three-module-rag-selection-20261012.md)，当前第一顺位与挑战者仍是未采用建议；不启动新业务推理或修改冻结harness，原件质量主线及后续工程顺序保持。

**同日完整架构分析报告：** 用户要求将上述研究整理为可交给架构agent的详尽报告，统一入口为 [ConSense LLM选型与OER部署完整分析报告](./consense-llm-oer-architecture-analysis-report-20261012.md)。报告整合三模块任务／RAG责任、公开分数与本机结果、256K／512K、单卡全部主要显存路线、K8s所有权、材料投影、评估分母与后续实施交付物；第一顺位与挑战者保持研究建议，报告交付不改变当前解析主线、冻结项或运行授权。

**2026-10-12 用户同步可能的 LLM 定稿依据：** 用户指定 [ConSense LLM选型与OER部署完整分析报告](./consense-llm-oer-architecture-analysis-report-20261012.md) “可能会作为我们最后的LLM定稿”。后续设计沿用该报告作为最新候选依据：Qwen3.8-27B官方FP8为共用第一顺位验证建议，Flash-Next为复杂Vetting/Advisory挑战者，M3官方API为分模块质量参照；至少256K、优选512K总窗口、完整RAG证据、单H800/K8s及OCR/LLM分阶段使用保持。用户的本次同步不是正式模型采用或运行切换；三模块质量、精确制品/引擎与目标环境仍按报告待验证项记录。当前OER原件质量顺序及Drafting冻结不变，不因此提前下载/启动候选、改在线配置或做调度工程。首版图形目标仍是准确返回完整原图/表组与必要文字限定，不扩为解释箭头或隐含工程关系。

**“解析质量过关”的统一标准**见 [质量计划顶部](../tools/oer_training/QUALITY-PLAN-20261010.md)：完整适用页／条款覆盖100%，关键文字／层级／条件／例外与完整表组零已知错漏，来源定位正确，正常完整parser产物及旧正确关键项回归通过，独立源核的普通正文CER≤0.1%。此数字是本项目新设工程验收线，不是模型官方保证或已取得成绩；抽检分母、全核关键项、未逐字核对范围分别报告，不能以抽检成绩追認全库字面正确。固定版本成果验收与新来源／换版泛化分别判断，必要未知不自动放行。当前完整解析门未过，旧文档的兼容／接入优先安排仅为历史，不再执行。

**2026-10-11 最新执行顺序：先确认原件解析质量，来源兼容与业务接入后置。** 用户再次明确纠正第一优先级：先核范围内原件的完整文字／条款、表格／空白／勾选、旁注、读序及跨页归属；针对正常完整parser内的真实缺陷修解析代码、补完整监督并做必要训练。哈希OOM、来源资格／绑定、Java或测试启动兼容、发布／登记／索引接入不再作为当前主线，也不作为离线原件解析质量与训练的前置。停止这些方向的新实现、测试、编译和部署；已经启动的自有作业仅安全收尾并保留真实终态。已有候选、失败和服务保持，不回滚或删除，不暂停整个OER目标。解析质量确认后再恢复这些后续工程，仍需它们通过后才能完成全链业务验收。不能用兼容测试、来源失配拒绝或index ready替代解析成绩，也不以工程修补吞掉模型／原件质量调优时间。

**2026-10-11 用户最新明确优先级：质量第一，速度慢可以接受。** 先保原文、完整条款／表组、条件和例外、表格单元格归属、勾选／空白、跨页续接及引用正确，再做性能优化。当前提速基准和吞吐调优后置；不以提速减少来源核验、截断内容、降低解析分辨率或采用尚未通过整链质量验收的权重。先定位完整解析链路的实际失败，单因素对照并保全旧正确项，补齐正常来源接入后用赛方样题和 Vetting 报告验收。训练页拟合、程序成功退出和索引 ready 均不能代替业务质量通过。

**2026-10-11 最新任务口径：当前只有 OER 一个持续实施任务。** 解析质量、参数／必要训练、来源核验、索引与业务接入是该任务内部的并行工作，不是同时继续 Drafting。已交付 Drafting 内容实现及现有运行保持，识别 harness 与确认值继续冻结，新的 Drafting 功能／排版实现、测试和部署不在本轮执行；OER 修改时仍核验这些保护项。下文 Drafting 热修与交付记录按当时授权的历史阶段读取，不能据此重新开启第二条任务。

2026-10-09 用户明确要求了解这些文件并实现、部署 OER。此前 2026-10-08 的“只改文档、不改代码”是历史阶段限制，已被本次 OER 实施授权取代。当前实施包含 OER 必要的模型、运行环境、来源结构、索引、接口及其验证。其他模块的业务规则不因这次授权自动扩展；此前撤回的风险数量上限试验仍保持撤回。

2026-10-10 用户同时授权 Drafting 直接热修，并明确“变量识别的 harness 不要再动了，保持现状”。当前 Drafting 工作限定在已有确认值到生成正文、源码定位／绑定、审阅差异和 DOCX／PDF 版式的一致落实；不修改识别提示词、候选门控、recall、识别预算或用户确认值。现有识别文件的工作区增量包含此前历史修复，不能为了此次限制回滚那些文件；本轮按冻结 SHA 保持。OER 仍并行实施，解析或检索调整不据此重新发起用户变量识别。

同日后续用户要求继续OER，并补充PDF中变量影响范围的星号清理不彻底、页眉页脚位置不统一，要求对照2b样板文件格式修正。这项授权继续属于Drafting生成／审阅／排版层：先比对2b项目NTT／SCT／SCC与本次三保存稿，区分模板编辑星号、实际正文字符、原生header/footer和正文中的手工页眉页码，再形成可复核的清理与版式规则。不能全局删除所有星号、改用户确认值或重调识别harness；原件不改，生成稿变化进入操作／布局记录。2b格式可以作为样板证据，不据此复制其中另一项目的取值、正文采用决定或答案。星号、页眉页脚、空页／孤标题及目录页码缓存分别验收，不以其中一项通过宣称完整PDF已通过。

同日用户询问上述页眉页脚、删后过空、重编号及重新排版是否需要等OER确定部署，若需要则暂停排版。当前三份原生DOCX的已核源段落／分节及业务操作可精确定位，故已查明原因的生成／渲染修复继续，不以OER为前置：原生footer、已清空正文范围的分页属性、Word原生编号与PAGE／TOC字段各按自身证据核验，不做PDF坐标后加工或从OCR读序重造Word文稿。未知业务分支、法律上仍需保留的Not used／章结构不由排版裁定；陌生PDF／扫描原件或无法可靠定位来源归属的布局问题，才保留待OER验证。此为当前工程依赖判断，不追认尚未完成的footer、编号或目录验收。

**最新顺序覆盖上一段的继续执行安排：用户随后明确指出模板换版适应是关键，要求“先确认变量和修改内容对，版式问题等OER”。立即暂停新的页眉页脚迁移、分页／空白压缩、重编号、目录更新、重新排版及纯格式星号清理的实现／测试／部署；已启动的自有渲染只安全收尾并保留证据，不发起下一轮。已部署历史修复保持；未部署候选保留在忽略的owned目录，不应用主源码或用户文稿。变量识别harness仍冻结，继续核对确认值→修改计划→实际保存正文一致性，未知业务决定不按排版需求补造。OER继续推进，并须先确认新模板的来源身份、原生story／条款层级、修改范围定位及版本失配守卫，再恢复版式工作。OER解析本身不等于Word编号／字段／排版执行器已实现；换版来源结构与生成执行接缝分别验收。**

用户随后进一步明确交付次序：先完成NTT／SCT／SCC对应变量值的修改功能，整理给业务确认，同时全力推进OER。内容阶段的完成门为确认值、实际修改计划、源／保存DOCX、操作／范围记录与前端审阅一致；有值却漏写／错删是执行缺陷，缺少采用形式／映射则逐项列入业务确认，不把未决值猜成确定。供业务确认的文稿按真实保存revision导出，版式未验收状态明确保留；不擅自向业务人员发消息或发送文件。

### Drafting 指导说明的条款作用范围（2026-10-10 三处截图核准）

指导说明按其明确条款引用、原生条款树和表格／单元格关系确定作用范围，不能按最近一个段落归属，也不能把删除指导说明等同于业务正文已改写。完整条款的适用条件与其中括号短语的局部条件分别记录、执行和展示。原生 DOCX 优先保留 story／表格／行／单元格及条款层级；跨页或 PDF 来源由 OER 保全物理页、框及续接关系。该结构分析继续实施，最终分页／页眉页脚／重编号／纯格式清理仍暂停。

| 指导说明 | 完整内容范围及条件 | 已核处理方式 |
| --- | --- | --- |
| SCT6(1)(c) 右侧 “For use where site formation works are involved and where … PRE.B9.260(4) is used” | 完整 (c) 引导和 (i)／(ii)／(iii)，跨两个原生表格行；两个适用条件是 AND | 已确认 `siteFormationWorks=false` 足以判不适用，采用同条款2b样板的 `(c) Not used.` 形态并清三个子项及所属注，保留前 (b) 和后 SCT6(2)。true 但 PRE 采用未知、或工程事实未知时保留 pending。此前因缺 PRE 将明确 false 也留 pending 的分类过严，已由本次原件核验纠正。 |
| “Condition SCT6(2) below applies where … domestic block(s)” | 完整 SCT6(2) 及其 (a)／(b)，止于下一个父级 SCT6(3)；不包含 SCT6(3)(c) | domestic=false 时 `(2) Not used` 并清整个 (a)／(b)；true 时保留整组。当前 true 的正文保留正确，删除注不得据此把父动作报成业务 applied。 |
| “Condition SCT6(3)(c) below applies where … domestic block(s)” 和右侧 “Delete the words if precast façade is not applicable” | 左注管完整 SCT6(3)(c)；右注仅管其中 “and the submission for special terms of payment” 括号短语 | domestic=false 时整项 `(c) Not used`；domestic=true、facade=false 时保留主句，仅删除指定付款短语；两者 true 时保留正文与付款字词。未知值不当作 No。两个条件及注清理不能合并为同一个业务作用范围。 |

当前标准 SCT 源 SHA 为 `7def01d62e07b35dd86beeff995e0e37d73ce18adca2746e2a6f6d9bafb5aaa4`；本次2b处理形态证据 SHA 为 `6d7802ea7b094124c0ddebb0b448c7e6bfe72828ffbc91ad1c30133902a6f64c`。2b只支持同条款的处理形态，不提供当前项目的事实或采用值。表中规则不是固定段落序号的换版通行证：来源版本／条款树／边界不匹配时拒绝自动写入，重新确认完整范围。独立原件核验记录见 [三稿审阅现场排查](./testing/drafting-review-live-audit-20261010.md) 的最新作用范围增量；实现、部署和实际三稿验收分别更新。

本轮scope内容执行已热部署并验证实际capture04；正文执行、编辑清理、导航条号标签分别验收，不能因其一通过追认另两项。原生同一outer table含多个条款时，whole_table证据不作为whole_clause修改范围；局部付款短语必须独立native channel／UTF16范围／文字身份，保留父条件。通用换版实现候选继续，自动discovery尚未实现，不以人工source-only sidecar冒充自动条款识别。业务确认包保留14待核事项及暂停版式状态，用户自行发送。

同日后续指示规定当前顺序：先完成 SL、2b 页面布局及文件身份调查，再与用户沟通整体 OER 方案。已有实验、实现草稿和失败记录保留；整体方案沟通并确定前不继续激活新的整体架构或切换在线业务后端。调查结论、方案决定和部署验收分别记录。

上述调查已完成并汇总在 [原件布局与上传路由调查](./oer-source-layout-and-routing-investigation-20261009.md)：2b 全 35 文件逐件调查、12 PDF 的 258 页布局普查／缩略图目视和 24 个高清样本、23 DOCX 原生普查及 39 个代表派生页目视，另有 SL 原页／条款边界和现有上传源码核查。该完成状态指调查阶段；全部原页文字／cell、真实检索和在线业务验收仍未完成。Q1–Q11 的业务取舍已经收敛，用户回复“好的”认可 [整体收敛稿](./vetting-ocr-embedding-rerank-grill-20261008.md)，接下来按方案分阶段实施与验收，不重启共同理解确认。

### GPU 优先与辅助层设备分工（2026-10-10 用户最新修正）

2026-10-10 用户在核对官方三项依据后进一步明确：**最终部署在 H800，MinerU 按 `mineru[full]` NVIDIA 安装与吞吐路径处理。** 当前固定版本仍为 MinerU 4.0.10；后续目标环境依赖配方和完整 worker 的调优以这个 extra 为准，不以默认小模型 ONNX CPU 安装作为 H800 最终配方。已完成的 CPU 辅助运行保留实际身份及成绩。`[full]` 安装成功不等于全部辅助模块已使用 GPU；逐模块核验准确版本的 provider、实际设备、精度、完整输入、峰值和整链吞吐。官方明确支持或推荐 CPU 的具体辅助路径仍可按证据保留，主 VLM／embedding／rerank 的 CUDA 规则保持。本机 Windows 不支持的目标依赖须在本地隔离的 Linux 目标环境核验，不能为安装 extra 干预其他服务、静默退回或追认 H800 已运行。

用户先要求“入口统一改 cuda，别显式选了”“能用 GPU 的一律 GPU”，随后进一步明确：**如果 MinerU 辅助层确实官方推荐 CPU 就可以，须灵活处理，不能机械执行全 CUDA。** 最新修正优先。统一入口继续默认把 OCR 视觉语言主模型、embedding 和 rerank 的主要数值计算放在 CUDA，调用者不必手选设备；完整 parser 辅助层按准确版本的官方建议／设计及实际全链精度、耗时、显存选择已声明的分工，不为设备标签一致而强行改图或移植全部小模型。原生 POI／PDF 文本层、文件读写、JSON／SHA、BM25、数据库及当前 Qdrant Local 查询继续使用 CPU。

对 MinerU layout／OCR／table／formula 等逐模块核对官方文档和源码，区分明确性能推荐、基础兼容路径、代码默认与异常 fallback。单纯默认 CPU 不能冒称官方性能推荐，列出 CUDA provider 也不能冒称真实 GPU 运行。官方建议使用 CPU 的辅助路径可按正常分工保留，结合端到端实测判断是否值得迁移；依赖未支持、设备初始化失败后意外切换则保留失败和真实状态，不用正常 CPU 分工掩盖异常。当前研究结论及选择在 handoff 更新，历史严格全 CUDA 探针保留原实验身份，不作为新规则的必过门槛。

启动及模型调用核验所选设备；health、运行配方、模型日志和缓存身份记录每个模块实际 device／dtype，CPU 辅助与 CUDA 主模型分别表达。主模型 CUDA 不可用或显存不足时不静默换成 CPU，也不让入口接受未知 auto 配方。CPU 文档 embedding 或兼容收据不得冒充 CUDA 配方；辅助层分工不改变这个索引身份要求。设备、依赖、精度、token 预算或模型版本变化绑定相应新运行身份，不据设备选择追认 FP16／BF16 对照通过。

本机 A4000 16GB 和赛方单 H800 80GB 按主模型阶段调度处理。容量不足时保持完整输入并排队、减小已验证 batch 或按阶段卸载／恢复自有 GPU 模型；无法准入时明确报告资源问题，不截断关键上下文／表组。其他 GPU／WSL 服务继续保全，Bonsai 继续抑制。历史 CPU 基线和严格 CUDA 候选分别保留，不因新规则重贴设备或质量结论。

### 本地调试与超算发布边界

2026-10-10 用户进一步明确当前实施顺序：**现在还不到冻结打包阶段，先配置本地算力资源和统一入口、修正架构，再贯通并调优 OER 流程代码及模型，最后验收并冻结发布。** 当前优先主模型 CUDA／辅助设备按证据选择的完整 parser 和原生 producer 作业入口、离线准备与在线查询职责、来源保全及索引／预算残差；按原件标注和归因开展参数对照及必要训练。模型仓库资产的准备、测试源码副本和运行证据保留继续进行，但不将它们当作最终发布包，也不以打包工作替代尚未完成的全链与质量调优。完整链随包交付、固定版本复用及换版应对要求保持，发布制作与 H800 验收放在本地代码／模型／业务质量验收之后。

2026-10-09 用户明确纠正：**在本地调试模型参数，再打包部署到超算；超算平台修改代码会触发大量扫描，不作为开发迭代环境。** 默认在本机 RTX A4000 16GB 完成解析配置／参数消融、数据标注、必要的轻量训练兼容试验、完整 parser 回接及业务回归，再冻结发布包。H800 80GB 用于部署和推理验收，核验运行兼容、真实显存／吞吐、整批调度与模型恢复；不默认在那里训练或现场改代码。

发布包绑定代码、目标环境依赖、权重／合并模型、tokenizer／processor、任务模板、配置、规范化／路由规则、索引／缓存身份、回归样本清单、启动／验收脚本及回滚版本／SHA manifest。Linux／CUDA／H800 的目标适配在本地可复现的隔离环境中尽量预验证；本机 Windows／A4000 通过不追认 H800 运行通过。模型或配置变更也不假定免扫描；目标环境发现问题时回本地修复并形成新发布版本，不能依赖平台现场补码。此边界是当前执行要求，不表示发布包已制作、已提交扫描或已部署。

本机16GB是本地训练评估预算，80GB的训练算术仅为技术参考，不作为本次默认训练地点。先本地校准与工程修复，再按残差决定是否做小batch／冻结模块／gradient checkpointing的轻量微调。若本机无法保留完整关键输入／target或完成导出回接，不以静默截断／降质强行过检，也不自动转到超算训练；训练地点与规模需另行评估。

比赛的三个功能及文件范围依据 [Brief 核对记录](./archive/team-update-20261004/communications-alignment.md) 与 [业务范围表](./archive/team-update-20261004/business-question-scope.md)：

| 功能 | 已确定边界 |
| --- | --- |
| 起草 | 输出 NTT 正文、SCT 正文、SCC 正文；按标准编辑指引处理并移除最终文稿中的编辑指引。附录和其他文件可提供参数／关联依据，不扩为起草交付。 |
| 审查 | 范围内的合约文件、附录、项目 PRE、适用标准及项目要求；跨文件冲突、引用、采用版本、条件及文字问题均可核查。审查可独立执行，不要求先由系统起草。 |
| 咨询 | 按选定合约及适用依据回答，包括签约后问题，提供原文出处；背景不足时询问，条款不能支持时明确未知。 |

### Q9：起草模板与审查／咨询资料隔离

用户在本次总体方案访谈明确纠正：NTT／SCT／SCC **标准模板仅供 Drafting**，不参与 Vetting／Advisory 的待审对象、检索候选、审查／咨询依据或提示上下文；不能因六来源可预部署就把三份模板加入它们的索引。Vetting 与 Advisory 共用范围内的项目合约原件、适用标准及其解析／索引证据。解析工具和存储实现可以复用，业务资料权限不因此共用。

项目实际提供的 NTT／SCT／SCC、或明确作为待审项目稿提供的起草成果，与未填标准模板分别识别；范围内的项目文件继续作为实际对象处理，不按 NTT／SCT／SCC 文件名一律排除。GCC、适用 GS／SL 技术参考与项目已纳入的 GCT 等保持原有角色／采用规则。Drafting 需要的项目事实不因模板隔离而被删除。

Q9 接受的是上述边界和 Vetting／Advisory 共用，不表示用户采纳了此前“三模块共用”的任何选项，也不表示当前源码已实现模板隔离。历史固定六来源和运行收据保留原事实，活动方案按本节的模块权限处理。依据见 [ADR 0003](./adr/0003-separate-drafting-templates-from-vetting-advisory.md)。

### Q11：审查发现作为 Advisory 的轻量入口

用户采纳 Q11=B，并限定“只作为 advisory 模块的一个入口，不要搞得太麻烦了”。首版将选中审查发现的问题描述和原文引用带入现有 Advisory 问答，作为可编辑的问题草稿与检索线索；用户可以继续提问，普通独立咨询仍可直接使用。该入口不另建咨询模块，不增加审批、整改状态或复杂的跨模块工作流，也不自动修改审查报告或合同。

Advisory 仍核对当前选定项目原件并独立检索回答，审查意见不成为权威证据、不进入原件条款索引。关联来源已变化时使用现有版本核对提示，并根据当前原件回答，不能把旧位置当作当前证据。问题、引用及必要的来源身份即可表达入口上下文；不复制整份报告或另建专用索引。这是已确认的产品边界，入口尚未实现，不据此激活新架构。

上述 Brief 的原始 PDF 当前不在本工作区。本轮重新读取了已同步的原件核读记录、赛方 `00_List` 原件及相关 Q&A，并以用户最新明确指示固定下面的范围；不能把既有记录写成本轮重新读取了 Brief PDF。

### 2026-10-14 Advisory 能力对齐入口

用户已明确本轮模块及产品经理确认的合同咨询提示词均属于 Advisory。已采纳：不足依据状态保留经验证的有限分析和补问；自检内部记录、正文说明限制；参考 Codex 实时报真实阶段，正文核验后分段流式发布。另明确要求点击引用加载完整源文件并准确定位，以及自然问法的 T6 流程图 harness 重点验收。范围与隔离规则保持；当前产品分支已收敛，实施候选及待确认状态见 [Advisory 能力对齐](./specs/advisory-capability-alignment-20261014.md)，有限分析边界见 [ADR 0004](./adr/0004-preserve-validated-partial-advisory-under-insufficient-state.md)。旧 Vetting 访谈稿为历史核查，不是本轮实施输入；本段不表示业务代码、运行提示词、数据库、模型或浏览器验收已完成。

## 直接排除与仍须处理的内容

用户 2026-10-09 明确表示比赛不会提供以下文件，要求直接排除；又明确排除 SL 的非技术章节。这是已确定范围，不能再写成“最终比赛可能需要补齐”。

| 内容 | 当前处理 |
| --- | --- |
| BQ、SOR、General Summary 等计价文件 | 范围外；不下载／部署、不作为业务条款索引、不要求补齐、不作为解析或验收缺口，不开展工程量、报价或价格表核算。 |
| PRE 以外的 works-related PSS，即项目工程技术 PSS | 范围外；不作为审查／咨询文件，不部署或要求补齐。目录中的技术分项数量不构成待交付数量。 |
| SL 标准库的非技术章节 | 范围外；从活动参考索引排除，完整原件仍保留作身份及溯源容器。 |
| 项目 PRE | 范围内；与 SL 的 Preliminaries 参考库区别处理。不能因排除工程技术 PSS 或 SL PRE 而排除项目 PRE。 |
| 适用的 GS／SL 技术章节 | 保留为参考依据，按项目实际采用的版本和适用关系使用。不能因排除项目工程技术 PSS 而连同技术标准库一起排除。 |
| NTT／SCT／SCC 中 Bill 编号、名称、发放／递交方式及关联引用 | 保留这些正文所需输入及同步关系。出现 BQ 或 General Summary 字样不使整个正文范围外。 |
| GCC／SCC 付款、错误修正及价格调整机制 | 保留条款解释与应用；这一职责不扩大为计价文件审查。 |

赛方 `00_List` 明写 “A set of tender documents excluding Bills of Quantities and General Summary”。目录中技术 PSS 的 `Not provided` 属于材料组成事实，不能报告成比赛必需资料缺失。此前将它们称为“缺失项／部署缺口”的表述已纠正；13／14 的目录计数差异不增加任何任务或阻拦。

APZ 等已提供文件中的未提供页只记录具体覆盖事实；有明确的范围内条款依赖时，才局部说明该判断无法核验。范围外内容的缺席不会使整份结果标为不完整。

## SL 的原件与章节选择

当前原件：`26_Specification Library_SL2022_042024_Batch02.pdf`，4,300 物理页，SHA-256：

`6331c283da806846a06644814fa2271182314dbc2235c711a16e8492faaa0492`

以下边界来自原始目录／书签并已对照 25 个原始页面视觉核验。页码均为从 1 开始的原 PDF 物理页，区间包含两端；印刷页号不作为范围路由依据。

| 原件章节 | 物理页 | 活动索引用途 |
| --- | --- | --- |
| 封面、README、总目录 | 1–21 | 保留组成／版本元数据，不作条款检索 |
| SL Preliminaries Specification | 22–1126 | 整库排除 |
| Architectural Works | 1127–1855 | 技术参考，保留 |
| Drainage and External Works | 1856–2151 | 技术参考，保留 |
| Structural Works | 2152–2602 | 技术参考，保留 |
| Demolition Works | 2603–2658 | 技术参考，保留 |
| Foundation Works | 2659–2757 | 技术参考，保留 |
| Soft Landscape Works | 2758–2830 | 技术参考，保留 |
| Building Services | 2831–3911 | 技术参考，保留 |
| Civil Engineering Works | 3912–4138 | 技术参考，保留 |
| Geotechnical Works | 4139–4300 | 技术参考，保留 |

活动技术库共 3,174 页；21 + 1,105 + 3,174 = 4,300，无顶层区间缺口或重叠。技术库内部的 General、定义、技术附录、条件和例外仍保留，不能按这些单词删段。技术库中引用 SL PRE 的文字仍保存原文，但不据此将已排除的 SL PRE 重新纳入检索。

对应机器边界见本机 [SL scope manifest](../../.local/state/sl-scope-bookmarks-20261009/sl-technical-scope-manifest.json)，原页视觉核验见 [scope receipt](../../.local/state/sl-scope-bookmarks-20261009/sl-technical-scope-receipt.json)。这些证据位于 ConSense 根目录下忽略的 `.local/state/sl-scope-bookmarks-20261009/`；异机需自行准备资料和可复算清单，仓库可携带规则见 [来源部署清单](../../docs/oer-source-deployment-manifest-20261009.md)。库边界核验不代表 3,174 页 OCR 或逐字语义验收。

历史 12 个 SL 风险页中，492、494、496、747、986、987、1006、1008、1105 位于排除库，不属于当前验收分母；1329、3437、3438 仍在技术范围内。历史结果保留，不重写为新范围内通过或失败。

范围配置绑定原件 SHA、版本和完整章节清单。更换 SL 版次或字节后重新识别目录并建立新清单，不能将上述页码直接套到新文件。跨越纳入／排除边界的 chunk 应在上游依据原始块拆分，保留位置；不能截取模型输出字符串后冒称完整结构。

2026-10-09 用户进一步指定：SL 技术库按 clause no 构建主层级和条款切块，完整保留所属表格／图像定位；高度相似且量大的资料需要模型调整与质量验证。具体规则、编号变体和模型调整顺序见 [SL 条款层级与模型调整](./sl-clause-hierarchy-and-model-adaptation.md)。编号是定位与结构依据；无独立编号的标题、子项、续表、图例、注释继续归属于实际父节点，无法确认归属时保留 unknown，不为整齐而发明编号。

## 预部署、比赛替换与答案隔离

| 资料 | 生命周期规则 |
| --- | --- |
| 2a 的 NTT／SCT／SCC 标准模板 | 按准确 SHA／版次预解析、精修、切块和嵌入，仅供 Drafting；不进入 Vetting／Advisory 的对象、候选池或上下文。 |
| GCC、SL 技术范围，以及 2b 的 GCT | 按准确 SHA／版次预解析、精修、切块和嵌入，Vetting／Advisory 按项目实际采用及范围共用。GCT 保留 tender 角色；SL 是经过章节选择的容器。固定表示缓存生命周期，不表示本项目自动采用所有条款。 |
| 2b 的范围内项目文件、附录、Specification／项目 PRE及清单，2c 的项目邮件／纪要；图纸文件排除 | 每个比赛项目读取实际原件、实际采用声明和实际内容；不得继承上一项目的取值、条款采用决定或样板答案。图纸文件只做身份识别后排除，不解析其细节。 |
| 封面、资料目录、flysheet | 组成与身份元数据；不能当作合约义务正文或缺文件的唯一判断。 |
| 2d 样板审查意见／countercheck、2e 咨询答案／flowchart | 保持对应题目答案隔离；只供格式参考、原件理解和独立评价，不混入生产条款检索、同题模型输入或 OCR 监督目标。 |

本地资料包 48 个原件、6 个固定来源、4,684 个 PDF 物理页是容器清点数，不是比赛全部有效检索页数或全部审查对象数。范围内文件的内容、分页、版式、组成和采用版本可变化；这一事实不能推翻已明确排除的文件类别。

固定集合是所有模块预处理来源的并集：三份 NTT／SCT／SCC 标准模板进入 Drafting 所需处理，GCT／GCC／SL 按实际采用与范围进入 Vetting／Advisory。缓存或通用解析可复用，不混合应用候选池。

### 固定版本验收后持久化，完整处理链随包发布（2026-10-10 用户再次明确）

NTT 模板、GCT、SCT 模板、GCC、SCC 模板及 SL 技术部分是本轮固定预处理集合。先在本地调整解析代码、模型参数和必要的模型权重，对照原件确认文字、编辑状态、条款层级、坐标、图像、完整表格组和范围质量；通过对应验收后，按准确版本持久化并冻结。目标是质量合格的固定成果复用，不是每次提问、审阅、查看进度或服务重启时重新解析这些文件。当前局部实验不表示六来源已全部验收冻结。

持久化至少包括原件及实际字节身份、两路完整 raw／原生观察和资产、采用后的规范结构及采用依据、clause 层级与稳定定位、原图／完整跨页表组映射、范围及模块角色、文档切块与文档向量、词法检索准备结果，以及相应模型／参数／源码／依赖／质量收据和 generation 清单。未确认内容及 unknown 原样保留，不因持久化变为已验证。业务进程可把已准备的检索对象载入 RAM；冷启动也从持久成果恢复，不把“内存缓存未命中”解释为必须重跑 OCR 或文档 embedding。

| 变化 | 重新处理边界 |
| --- | --- |
| 问题或审阅目标变化，原件及处理配方不变 | 执行查询向量、范围内召回、query-specific rerank 和业务判断；复用原件解析、文档切块及文档向量，不重建固定资料。 |
| 文档切块或 embedding 配方变化 | 更新受影响的切块／文档索引；未受影响且身份完整匹配的原生／视觉解析成果继续复用，不因此重跑 OCR。 |
| 角色、项目采用、模块权限或范围变化 | 重建受影响的采用／范围视图及索引身份；保留原始完整解析。若既有成果不覆盖新增合法范围，才补齐该范围处理；范围外类别仍排除。 |
| 原件字节、parser 代码、OCR／布局／表格模型或解析参数变化 | 生成新的相关解析版本，再更新依赖成果。可以复用经完整身份核验的不受影响来源；不能仅凭相同文件名、旧页码或相似文字复用旧版定位。跨页表组及层级依赖作为完整单位处理。 |

2026-10-10 用户同时明确：**完整 OCR 链路必须一起打包上传超算，为黑客松中这些样板／标准文件换版提供应对措施。** 发布包同时携带已验收的固定解析／索引成果和完整可离线运行的处理能力：POI／OOXML、原生 PDF／表单与渲染工具及字体、Paddle 的 OCR／布局／表格完整链、MinerU 的完整第二路、必要辅助模型、权重及 processor／tokenizer、Linux／CUDA 依赖、已冻结配置、身份路由、范围识别、采用／质量门控、切块／embedding／索引构建及验收／回滚脚本。不以元素级 VLM 演示、预计算 JSON 或单一索引代替完整链；主模型 CUDA 优先，辅助层按本文当前设备分工规则执行。

比赛现场按冻结代码处理新数据是正常运行：核对新原件身份→识别类别／版次／实际范围与采用→用已验证配方生成候选成果→核验原文、结构、位置、完整表组、范围及索引→发布完整新 generation。无需变化的固定版本直接复用。SL 新版必须重新建立章节范围，不能沿用旧版物理页区间。新版本未过核验时保留局部 unknown／阻断依赖判断，不能把旧成果冒充新版本。旧原件、成果及失败收据保留作历史／回滚；旧版仅在仍被项目明确采用时作为该版证据使用。

本地调参、训练和代码修复／扫描发布的边界保持；超算现场运行已发布的 OCR／解析／索引任务不属于现场开发，不默认允许现场改码、临时换模型或未经验证调参。配置变更也须遵循已有配方身份与质量验证规则。发布验收须演练同版冷启动复用、真实变版重新处理、新版范围／定位核验、失败不覆盖旧完整成果，以及单卡阶段调度和恢复；准备发布包不表示已上传、扫描或通过 H800 实测。

来源完整性校验与解析是不同阶段。当前 actual-CAS-every-read、实际原件字节及业务查询前后 fresh-source 规则保持；完整字节校验不授权重跑 OCR、重做结构恢复或文档 embedding。按实际输入 SHA 绑定的纯计算准备可复用，不能缓存来源有效性 verdict。轻量任务进度与显式来源核验必须区分，进度读取不冒称本次已验证 fresh，也不能因查看进度反复准备整套来源。

正常 index／retrieve 的同一请求内部可复用第一次完整验证后得到的纯重构结果，但只存于该请求的私有 frame，返回值须与私有状态分离，每次 load 仍重新读取实际有界 public root 并核准确字节身份。frame 必须自己拥有完整 actual-CAS 校验会话：每个用到的实际对象首次完整读核、成功退出前完整重新哈希，root／revision alias／软件身份在退出复验；已有无关外层 CAS 会话时拒绝进入，不能把尚未结束的外层 rehash 当成当前请求已通过。退出拒绝须落在正常请求 failed journal 内，成功响应必须在所有退出守卫之后；不跨请求保存 verdict，不跳过完整向量／payload／seal 验证。独立 canonical snapshot／asset 的未 framed load 继续原边界，health须区分该能力与正常业务请求内的 frame，不能把重复计算省去说成省去来源校验。候选、完整隔离计时、主源码采纳及线上时延分别记录。

**业务证据与模型输入分层。** 完整raw／CAS、captureCompleteness、逐glyph定位／unknown清单及软件／来源校验声明保留在来源存储、验证和引用展示层，不因RAG只选少量hit而在每个成员的LLM提示中再次完整序列化。模型材料应独立投影，保留实际选中正文、完整条款／表组及必要条件／例外、明确来源身份／页／成员引用、采用与coverage限制、相关unknown和关系；源全文校验材料通过引用身份保留，不能冒充合约正文。同一来源的重复校验声明可以共享或生成有明确字段契约的摘要；不得删正文／语义限定、把unknown改成已核、失去完整组或削弱来源守卫。模型材料预算按实际序列化体积与模型输入核验，不能只计正文字符，也不以增超时掩盖巨型metadata。2026-10-11 Q1.6实际13,295,121字符提示已证实旧接缝不符合该分层；候选／回归／主采纳／真实问答分别报告，不以架构规则追认修复上线。

原件不修改。派生图、裁图、降质件和修订文本记录原件 SHA 与派生关系。源字节哈希 `sourceSha256`／`originalSourceSha256` 和文本＋结构的 canonical `sourceHash` 是两种身份，分别保存，不能互相替代。文本、结构、quality、角色、范围、模型或配方变化时产生相应新 revision／index／cache 身份。已生成的 rerank 分数随 query 改变，不能预缓存成通用项目事实。

上传文件需要身份路由层，这是用户已提出的整体方案要求：分别识别文件类别、标准／项目／事实／评价角色、版本与采用关系、竞争范围以及原生／扫描／混合区域。已知固定原件可用准确字节 SHA 验明；变更文件结合实际标题、编号结构、正文、页眉及资料包关系判断并保存依据。文件名是线索，无法充分确认的身份保留候选／unknown，不静默套用模板、上一项目值或 parser。具体路由接口和判断方法在上述调查完成后讨论确定。

文件内部也可能有多种内容身份。SCT 可含正文与不同附录；项目 PRE 可含要求、待填表单、技术图表和明确标注 `for illustration only` 的示例报告。保留其章节／附录／内容角色和位置；示例中的日期、进度、数值不自动变为本项目事实。这种局部示例不使整个范围内合约文件成为应排除的 2d／2e 评价答案。

样板项目采用 SL2022 August 2024，实发标准 PDF 为 April 2024；清单 enrollment 部分另有 SL2026 链接。三者分别记录，保留范围内版次不一致及具体核验限制，不能静默用最新版本覆盖历史合约。

## 已采纳的 OER 设计

以下是 Q1–Q8 已采纳决定，不重新作为选项询问；细节及当时理由见 [设计访谈](./vetting-ocr-embedding-rerank-grill-20261008.md)。

| 编号 | 执行要求 |
| --- | --- |
| Q1 | 原生 DOCX 继续 POI／原生结构；扫描区域执行两套完整解析，保存全部原始输出、assets 和分歧。一致或高 confidence 不是正确性证明。 |
| Q2 | 按原文件、近重复及派生家族分组，再划分开发／留出，再进行增强。记录历史暴露；历史样板不能冒称未见盲测。 |
| Q3 | 长结构单元＋短局部窗口双粒度索引，保留父条、条件、表头、cell/span、来源及顺序关系。 |
| Q4 | 窗口定位后结合完整结构上下文 rerank；同一 query、同一候选池比较最高窗口基线和结构复评。召回遗漏与排序损失分别归因。 |
| Q5 | Codex 对照真实原页制作代表性完整页／跨页组参考标注，记录全文、区域、读序、表格及跨页关系；标注前不从候选答案反推原文。 |
| Q6 | 建立必要证据集及冻结候选池分级相关性标签。正文、条件、例外和相反约定均可能相关；未知项不是负例。 |
| Q7 | 真实材料与明确标识的合成补充分别统计；合成变值／扫描压力测试不等于真正新来源泛化。 |
| Q8 | 在参考标注及文件级评价支持下，按区域／完整表格组接受规范输出；完整保留两套证据和采用依据。表格不逐格拼接，不按固定项目值、页码或 confidence 选真值。 |

完整解析候选为 PaddleOCR-VL-1.6＋PP-DocLayoutV3 和 MinerU 4.0.10 Standard。RapidOCR 保留现有基线；PP-StructureV3 可作传统参照，不增加第三个同级主候选。元素级 VLM 调用、检测到 table 或只有纯文本，不能当完整解析器已接入。

保存原始 artifact 的实际字节与 SHA，不只保存规范化 JSON：Paddle 页面 JSON、MinerU model／middle／structured content、图片及关联资源。缺失／截断、坐标变换不明、物理页映射不明或表头／跨页关系不明，局部标 unknown／incomplete；不能凭模型自评补为确定。

### GCC1.3 的目录／旁注／标题规则（2026-10-12 原件核准）

当前固定GCC的1.3（原PDF物理16页、印刷4页）明确："The index, marginal notes and headings in the General Conditions of Contract, Special Conditions of Contract and the Specification shall not be deemed to be part thereof or be taken into consideration in the interpretation or construction thereof." 该版SHA为 `d7d74db6d9aee390093b9e22bb30dccdbf3c0316d47635922109422278d161e7`。Root亲看完整原页，来源调查与图像收据保存在 `C:\Users\Administrator\.codex\local-state\consense-oer\gcc-editorial-scope-investigation-20261012\survey-01\scope-survey-terminal.json`（SHA `4403bfee8bdae2afb28d978c77dd1c9b35c23ce03ea3046ef821787e8a901b74`）；这是一条原件规则，不是新增业务猜测。

解析仍完整保存index／marginal notes／headings及来源位置，可用于结构导航和主题定位，但与规范性正文分开表达，不能单凭这些编辑性文字确定义务、解释正文或扩大适用范围。旁注与源显示的起始条款可记录为 `observed start association`，其完整主题跨度未核时保持unknown；不按最近正文、下个旁注或列宽自动赋法律owner／scope。正文的编号、嵌套子项、跨页尾段、provided／notwithstanding、条件／例外及引用按原件自己的完整单元保全。

上述规则绑定本版GCC及项目实际采用，不能仅凭文件名或相似版式继承到新版本；也不能据此把NTT／SCT的起草指导说明或其他明确条件注一律判为同类旁注。各文件的规范性角色由原件声明与实际采用确认，guidance的内容作用范围继续按已核条款树／明确引用／表格关系判断。23组旁注的源调查完成不等于正常parser已实现完整nested树或法律范围验收。

### Q10：首版图像／表格返回的能力边界

用户采纳 Q10=A，并说明文字用于表征目标图片／表格的相关性，先确保给用户返回正确的图片／表格。首版保存实际原件图像、可读文字、标题／caption、所属条款／表组及位置，使文字命中能够回到对应 artifact。返回须验证来源文件／版本、原页或原生结构位置、完整相关区域和跨页表组映射；相似文字不能链接到别的图或表。

首版不将流程箭头、图形连接或局部修改指向的自动推断作为已支持能力。没有可靠关系证据时，不用图像标签推导关系性结论；可展示原图供查阅，并局部说明无法确认的判断。既有条款层级、表头／cell/span／续表关联、完整双解析和表组采用规则继续有效；A 不等于放弃表格结构或来源定位。文字描述不是原图／表格的替代证据。

PDF 可抽文字不自动属于可靠原生正文。2026-10-09 的 2b 调查发现 APU 全部 20 页同时有全页扫描图像和 `Tr=3` 不可见搜索文字层，其中含错词／乱码；保留该文字层的原始来源与图像证据，并按扫描区域规则对照恢复，不能据“文字数足够”跳过正文或将隐藏文字层称为已验真的原生文字。PDF `/Rotate` 与页面实际文字／图纸朝向也分别记录，原图方向及坐标变换须可追溯。

PDF 身份与正文调查同时检查 page content、AcroForm field tree 和 per-page widget／appearance。本包 GCT 的项目名、合同号等可见填充值来自表单 appearance，普通文字抽取会遗漏；同类字段的一处 null 不能证明整份文件没有合同号。保留每种表示、字段／widget 关系、显示位置和冲突，不为解析便利修改或 flatten 原件。固定 GCT 的预处理只绑定当前实际字节，项目值或表单状态变化时重新产生来源身份。

非扫描／混合 PDF 的实施路线为原生文字与页面视觉结构联合解析：保留原生字框及表单表示，结合页图布局／读序／表格关系，按区域恢复或校验文字。可靠可见原生文字优先；扫描区域继续完整双解析；分歧、坐标变换及完整表组接受按既定证据规则保存。详细方案和当前页级 fallback 的实现差距见 [技术选型](./vetting-layout-stack-selection-20261008.md) 的“原生及混合 PDF 的文字层与视觉结构联合解析”；这是实施细化，不能写成联合产线已接通或已验收。

DOCX 的 XML table 可能承载条款排版、表单、目录或真正数据表，不能按 `w:tbl` 数量一律套用“第一行是表头”的策略。2b NTT 代表原页显示无可见边框的条款排版表，SCT 则混合正文、附录及填表；这些实际结构分别保留。DOCX `docProps/app.xml` 的 Pages 是缓存属性，本轮 LibreOffice 派生分页已与它不同；原件引用使用稳定的段落／表格／cell 身份，派生视觉页另绑定 renderer、字体及配置身份，不冒称 Word 原件权威分页。

用户随后明确要求将 DOCX 打印／渲染为 PDF，执行两套视觉解析并结合 POI 验证。这是已授权的验证路径：原件只读，现有同源派生 PDF 可在核验哈希后复用，保留 renderer／字体／配置身份；Paddle／MinerU 接收相同完整页或完整跨页组，POI 原生结构与两套视觉结果事后对照。POI 结果、参考标注和候选答案不进入视觉模型输入。保留三路差异并对照原件判断，不多数表决，也不自动用 OCR 覆盖原生正文；完整表组采用及 unknown 规则继续有效。该验证可以检验 POI 结构覆盖和视觉呈现，但派生分页不是 Word 原件权威分页，三路一致也不自动证明法律语义或全文准确率已通过。

首轮真实验证已完成，见 [DOCX 双视觉＋POI 实测](./testing/docx-poi-dual-visual-validation-20261009.md)：6 份全量 POI、11 个同源完整视觉页。两套视觉输出共同丢失标题 RSE 的删除状态，而 POI 保存了局部 strike；因此原生编辑状态和 OCR 一致性必须分别记录。SCT 的待填空栏、图中遮黑数据和模型漏读是不同状态，不合并成统一空值。星号／勾号／否定词／选择连接词／字段角色均可能影响义务及适用关系，不能当普通拼词自动修正。视觉图组返回须关联原图、独立标题、所属父条和来源位置，单独 crop 不包含标题时不能冒称完整图组。本轮关键点对照不是全文 gold／新来源留出，单页运行不追认自动跨页组关系，完整表组规则保持。

2026-10-09 用户进一步纠正模型选型的主证据范围：**GCC、GCT、SL 技术部分及 2b 文件夹示例中的原始 PDF**。DOCX 打印成 PDF 的双轨试验仍有价值，但不能据它宣布原始 PDF 的 Paddle／MinerU 胜负或替代真正 PDF 的布局质量核验。已完成上述原始 PDF 的 23 个完整风险页、14 份原件、两路 BF16 实跑和每路四页 FP32 控制，见 [原始 PDF 实测](./testing/original-pdf-dual-parser-bf16-validation-20261009.md)。这不是全部资料逐页验收或生产选型已上线。

本轮支持的工程优先级是先建设 Paddle 骨干、完整保留 MinerU 第二路，并按实际原件／区域／完整表组核验采用；SL AMR 三页状态表的 MinerU 候选明显保留得更好，不能用 Paddle 全局优先覆盖它。该建议不撤回扫描双解析，不按本题固定页码／值选答案。两路共同丢失的删除状态、空槽及编号前缀须局部恢复或标 unknown；相同答案、空框字形计数、confidence 与更快耗时均不证明结构正确。原图内相关性文字与完整资产映射仍须实现 Q10=A 的真实检索／返回验收。

16 位推理首先采用固定 checkpoint 原生支持的 BF16，不能将 BF16 结果冒称 FP16 通过。Paddle native BF16 应遵循 vendor 的 `visual`／`mlp_AR` FP32 保留策略并核验实际模块 dtype；MinerU 本轮为全 VLM BF16。四页精度对照不外推到全部资料；精度、attention、DPI、batch 和输出预算分别变更、分别记录。参数字节下降不证明完整峰值减半；本机 GPU 实验仍需串行和退出后释放。

质量标注由 Codex 执行并注明执行者；`humanConfirmed` 继续只表示真人确认。局部未知暂停依赖该证据的判断，可靠部分继续；只有无法界定缺失范围时整份结果才标不完整。规范输出选择、OCR 字符质量、结构质量、检索相关性和法律判断是分开的验收层次。

2026-10-09 用户要求评估 OCR 训练难度和改进方法。当前实施建议按误差阶段推进：原生／表单／编辑态保真与完整组门控→布局／裁块校准及同型号布局 pilot→crop 完整但 raw response 仍错误的识别／表格微调→完整 parser 与来源回取回归。先做零步导出重载及极小训练兼容试验，再扩大标注，不将约 1B 的参数规模直接等同于当前 trainer／导出已兼容。PP-DocLayoutV3 有官方同型号训练与导出；Paddle VL1.6、MinerU 2605-Pro 的现有训练框架／部署通路均须当前 checkpoint 实跑验证。原件拼写、空槽、删除线和表头归属作为不同监督目标，语言润色不能修改证据。现有 23 页／37 关键检查只作暴露过的开发回归，完整 gold 与新来源泛化仍待完成。具体证据、训练建议和未验证门槛见 [SL/OCR 模型调整](./sl-clause-hierarchy-and-model-adaptation.md)及其三份训练研究；“未训练、未替换模型或切换在线配置”仅为当时研究快照。2026-10-11 MinerU已完成两轮fresh-base各20更新的LoRA质量pilot，五项同输入任务未见识别收益；Paddle已完成首轮20次更新／60次完整页前向的LoRA质量pilot，SL三页训练家族测到结构与文字匹配改善，独立盲测及完整解析／业务质量仍未通过；embedding／rerank权重更新仍为0，训练后权重未上线。实际收据、质量及后续原件监督以 [handoff 顶部当前状态](./HANDOFF-ocr-embedding-rerank-20261008.md) 和 [训练质量计划](../tools/oer_training/QUALITY-PLAN-20261010.md) 为准，历史快照不覆盖当前状态。

## 配方、资源与实际完成标准

2026-10-10 native实施补充：DOCX准确原生锚使用实际source SHA、partName／part SHA、Clark nodePath与所选UTF16通道。原生观察／public transport无固有PDF物理页与原页bbox；派生renderer视觉映射单独核验，未实现时明确unavailable，不用P0/P1或假原图绕过扫描门控。完整raw package／observer／parts／tokens／table inventory留在实际CAS，public采用与源身份保持版本／seal绑定，索引前与持久metadata读取／query后仍核实际bytes。删除部分native convenience标记不能使仍声明native版本／typed ref的payload降级为legacy。

当前public单snapshot上限16,777,216bytes。2026-10-10新增原生document collection：一份文件登记一个root，按完整采用结构单元／原表组分批，root和每批均保持该上限；完整单元超限仍拒绝，不能删除blank／nested、切开原表组或升高cap。reference／adoption的实际语义范围、scope／sourceOrder／unknown和未验rendering继续传递，跨批关系绑定完整CAS header。所有root／batch／持久index读取与query后仍逐次复验实际完整CAS，无validation cache或读时修复；批次不是独立document，DB和Session身份继续以整源root绑定。源码／fixture或真实BGE契约样本不当全文接通，真实PRE采用与完整容量仍未验证。当前证据见 [collection契约](./testing/oer-native-document-collection-20261010.md)、[消费者与实际BGE](./testing/oer-native-collection-consumer-validation-20261010.md)及[Java接入](./testing/oer-native-collection-java-20261010.md)；单snapshot历史见 [Python消费者记录](./testing/oer-native-python-consumer-implementation-20261010.md)及[transport记录](./testing/oer-native-cas-public-transport-implementation-20261010.md)。

模型、依赖、配置及实际 tokenizer 绑定固定版本，服务运行采用本地已下载权重。当前 embedding 为 `BAAI/bge-m3` revision `5617a9f61b028005a4858fdac845db406aefb181`；reranker 为 `BAAI/bge-reranker-v2-m3` revision `953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e`。

短窗口既有配方为 512 tokens／64 overlap；显式 `dual-structure-v1` 初始实验配方将结构 embedding 与结构 rerank 分别设为 2,048 tokens。它们是独立参数，不是最终优选成绩或模型官方最大预算。模型 loader 和 tokenizer guard 必须覆盖所用最大配方，记录实际 token 计数／完整输入，超预算按有来源映射的结构分组；静默截断不算成功。结构分组完整覆盖不自动证明义务和例外被模型联合理解。

新双粒度契约为 `source-bound-dual-granularity-structure-results-v1`／signature v4，使用新索引。既有 `windows-v1`／v3 作为可复现基线保留；旧缓存兼容证明只对准确绑定的源码及配方有效，不能扩大证明或重贴新内容。来源顺序依据实际原件／规范请求顺序，随机 ID 排序不是物理读序。

当前工作机为 Windows／RTX A4000 16GB。2026-10-09 初次核查约 12.6GB 显存占用时优先执行独立 CPU FP32 实验，这是历史基线；2026-10-10 的正式模型入口按上方 CUDA 规则执行，保持现有 GPU 和其他 WSL 业务。历史 4060 8GB 是另一环境，不再当当前设备。比赛硬件按用户报告的 1×H800 80GB、27 vCPU、250GB RAM、650GB 高性能＋1250GB 通用存储规划；部署前再测真实模型共驻峰值。历史 CPU 时间不代表 CUDA／H800 性能。

2026-10-09 用户再次明确总显存预算仅上述单卡80GB，并进一步确认模型卸载／加载可控，要求同时考虑权重读入VRAM耗时。按整批／阶段调度规划，不能以所有模型同时常驻或每页切换大LLM为前提；扫描全面双解析及完整表组采用规则保持。赛方实际约120B MoE的名称、量化与推理引擎尚未确认，不能把激活参数用于估驻留或把候选规格冒充已选模型。RAM保留兼容权重副本的暖回迁、冷加载、模型ready及首次prefill分别测量；250GB RAM缓存可行性也须完整host峰值支持。具体建议及示例算术见[H800混合解析／切换设计补充](./vetting-h800-moe-plan-20261004.md)，该设计尚未部署或完成融合/H800验证。

用户随后强调内存和高速盘资源已给定：250GB RAM、650GB高性能存储及1250GB普通存储是明确规划输入，无需反复索要。工程默认方向采用RAM兼容量化权重热缓存＋本地高速盘备用，按整批让出／恢复VRAM；实际引擎确认后的缓存兼容、带宽与host峰值测试是实现验收，不是要求用户再次提供已有硬件配置。热缓存、范围和原始产物存储仍由同一赛方节点承担。

同日后续显存调查确认主要可定位占用为本项目 Bonsai llama-server，而非已证明的 WSL OCR 占用。用户明确要求仅将 Bonsai 清出显存、其他不动、代码不删：已仅停止准确绑定的 Bonsai 进程并关闭本地 Model.Enabled，保留代码、引擎、权重、历史配置与其他服务。当前为临时抑制，后续执行不得擅自重新加载 Bonsai；显存快照及保全核查见 handoff 和本机停止收据。释放显存不等于 CPU 试验入口已迁移 GPU，GPU 依赖／精度／完整峰值及质量须独立验证。

质量优先；先降低并发／batch、分阶段串行及必要卸载。量化、降分辨率、截断和关闭模块作为分别记录的消融，不混入原始精度基线。MinerU 默认 llama-cpp Q8 与 raw FP32／FP16 是不同实验，不能换了精度仍写原始基线。模型调整是 SL 实施工作的一部分：按错误归因校准解析、embedding 和 rerank 的各自配方；残留系统性问题及合适标注支持时进行目标模型微调，记录新权重身份和独立验证。是否必须更新权重由实际对照决定，不能用资料相似或数量大直接证明某种微调已有效。

运行服务使用仓库启动脚本，保存进程路径、启动时间、配置、端口及健康收据。只操作已验证属于本项目的服务；保留旧配置／原件／失败证据用于恢复。密钥放运行环境或忽略的本地配置，文档、日志和提交不写实际密钥。

交付陈述分别提供：依赖安装／文件哈希、真实模型推理、原页文字／结构、范围筛选、必要证据召回、同池排序及最终应用调用的证据。单元测试、HTTP 200、缓存命中、平均 confidence 或 benchmark overall 各说明各自事实，不替代业务验收。尚未运行或核验的步骤明确列为未完成，不把计划写成成果。

2026-10-10 用户补充验收要求：本地解析、切块、检索参数及按残差必要的模型微调收敛后，使用赛方提供的问题样本执行真实 Advisory 问答，并执行真实 Vetting 结构化报告测试。每轮冻结原件／问题／参考版本、模型／配置／源码身份，逐题记录实际回答、必要证据覆盖、引用原文／原图／完整表组、条件与例外的忠实性及缺证时的处理；报告另核对字段、发现／分类／依据／来源地址及完整性。2d／2e 标准答案只供隔离评分，禁止进入同题解析、索引、检索上下文或模型提示；用过的题注明开发暴露，不能称盲测。契约测试与真实业务测试分开，不因测试通过宣称已经微调权重或覆盖全部赛题。尚未准备好真实链路时，先补齐工程与来源门控，验收要求持续有效。

## 接手检查

1. 读取本文及 handoff 最新进度；按已确认 scope 准备原件和 source manifest。
2. 复核原件、模型、配方及范围 identity；SL 新版建立新章节清单；范围外材料缺席直接忽略，不请求补齐。
3. 当前调查、业务取舍及共享理解已确认，读取 [调查汇总](./oer-source-layout-and-routing-investigation-20261009.md) 的覆盖限制及 [整体收敛稿](./vetting-ocr-embedding-rerank-grill-20261008.md)，按方案执行；保留现有草稿和实验，不重启已答选择题，不把方案认可写成全链质量验收完成。
4. 方案确定后继续用独立运行环境与新索引验证范围内真实资料；依据原件标注分别评价解析、候选召回和同池排序，完整保留双输出、实际输入、引用映射和失败，并更新 handoff 后进行必要接入。

本文件持久化规则，不自行授权对外发送题包、推送代码、合并或切换比赛生产环境；这些动作仍按用户在会话中的明确授权执行。

## 2026-10-14 Strata IQ3_S 本机独立部署测试

用户已授权IQ3部署测试并要求继续，已在忽略目录`.local/strata-iq3s-20261014`完成安装及真实调用。实际可共存配方为原版Flash-Next GSQ-RCO IQ3_S、262144总窗、INT8 KV streaming、MTP4、单请求、vision关、tail-skip0、28GiB CPU专家预算。全专家RAM及36GiB配置因Windows提交余量不足，仅加载后安全停止；没有改变全局分页文件或终止原任务。制品155项校验通过；4087／32757／131064／249987四档合成输入均精确计数、cache0、五字段JSON正确及stop。250083输入＋512输出为28.5 TG、1151.7 PP、TTFT217.399秒；短输入512输出为24.9 TG／TTFT2.049秒。

普通配置重启和短smoke再次通过。测试后已停止Strata，为正在等待≥9728MiB显存的OER训练让出资源；停止后NVML空闲13803MiB，原Java／固定检索PID及创建时间相同。保留按需起停，未切换ConSense业务入口、未接原件／RAG业务、未训练／发布或正式采用新模型。完整证据、失败保留及起停见[本机部署测试](./research/strata-iq3s-local-deployment-test-20261014.md)。本条仅补充独立LLM测试，不覆盖文首并行解析／语义训练状态及原质量门。
