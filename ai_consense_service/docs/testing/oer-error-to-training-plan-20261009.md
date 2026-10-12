# 原始 PDF 错误归因、工程改进与训练路线

日期：2026-10-09，Asia/Shanghai。本文基于已完成的 **23 个完整原物理页／14 份原始 PDF／37 个先行冻结的关键检查**，解释该修哪一层、哪些情况值得训练，以及如何判断训练是否真正回到生产解析链。本文是实施规划；本轮未训练模型、未修改业务代码、未切换在线服务。

用户最新明确部署方式是 **本机调模型参数并完善代码，冻结完整包后部署超算**；平台改代码需重新扫描，不适合作为迭代场地。因此本文默认本机 RTX A4000 16GB 完成参数／配置消融、gold 回归及确有必要的轻量 pilot，H800 用于冻结版本的推理部署验收；不把 H800 训练、平台现场调试或配置免扫描当既定路线。

建议顺序是 **先补原生证据恢复、覆盖检查及跨页关系，再对仍存在的布局错误做对应组件 pilot，最后对完整裁图中的字符或表格结构错误做识别／表格 LoRA pilot**。布局并非必须训练的中间步骤：主路不存在系统性布局残差时直接跳过。Paddle 作为优先建设的 PDF 骨干，MinerU 保留完整第二路；两路共同错误继续局部门控。将完整双解析、来源恢复与表组采用接通，比立即对两个 VLM 都训练更有确定性。

## 1. 当前证据能回答到哪里

范围以 [OER 已确认规则](../oer-scope-and-rules.md) 和 [handoff](../HANDOFF-ocr-embedding-rerank-20261008.md) 为准：SL 仅原物理页 1127–4300 的技术范围；BQ／SOR／General Summary、非 PRE 的项目工程技术 PSS、SL 非技术章节排除。NTT／SCT／SCC 未填标准模板只供 Drafting，项目实际版本另按角色路由；2d／2e 对应答案不进入模型输入、监督目标或生产依据。

主实验和逐项质量结论见 [原始 PDF 双解析实测](./original-pdf-dual-parser-bf16-validation-20261009.md)，精度出处见 [FP16／BF16 核查](./oer-fp16-bf16-support-20261009.md)。23 页是已暴露的风险开发集，37 项只覆盖选定关键内容／布局，**尚无完整文字、区域、cell/span、编辑状态及跨页关系 gold**；不能用这 37 项计算整个 OCR 的准确率，也不能把现有候选输出当训练标准答案。

本机可复核证据根为 ConSense 根目录下忽略的 `.local/state/original-pdf-dual-validation-20261009/`。每项独立审阅绑定原件、页图、候选 JSON pointer／原始响应及 SHA，具体来源如下：

| 证据 | 用途及边界 |
| --- | --- |
| `sample-selection-v2.json`、`parser-inputs-v2.json` | 23 完整页／14 原 PDF；包含 2b 全部 12 PDF 的 17 页、GCC 1 页、SL 技术 5 页；原件 SHA、原物理页、300 DPI 完整 PNG 与输入身份 |
| `reference-layout-checks-v2.json`、`reference-freeze-receipt-v2.json` | 37 个原件关键检查于新运行前 UTC `2026-10-09T14:00:18.710940` 冻结；历史暴露明确，不是全文训练 gold |
| `mineru-original-pdf-independent-review.json/md` | MinerU 37 项逐条审阅、源页与 raw／structured pointers；JSON SHA `20838360168adb2200ad903999a9ad2043fe5dfd0c07231096f70d2a68af80d8` |
| `paddle-original-pdf-independent-review.json/md` | Paddle 对同一批原页的独立审阅；JSON SHA `e76adf0ff6e5ad1672e2f1b24f33c81855b7c2225a37605b48b496d5c497ee1a` |
| `two-engine-original-pdf-independent-comparison.json/md` | 两家共同／互补缺陷与完整表组采用边界；JSON SHA `2f5681adb04a31e93c1f988c144016fc5bf7ebbc828f117c083c0c20cef472fa` |
| 两家的 `*-same-engine-precision-independent-review.json/md` | 预定四页同引擎 FP32 控制；不能把跨引擎差异或未控制原页的错误归因精度 |

输入 manifest SHA 为 `5a7cef3402da8adea9efe3cf54faa57f9115af7ded42fcacc001e8c8c857192f`，冻结关键参考 SHA 为 `8d1f86e8e6a34f32bf091e48216ae5d06f3d93bdf3781a95e448c7798e8c65ea`。原件、页图、raw 产物和模型留在忽略目录；本文及训练／评价契约可以随代码交付，不能将本机路径存在误认为异机已有资料。

### 精度不是当前主要修复手段

两套 BF16 已完整执行 23 页。预先固定四页的 MinerU FP32 对照中，49 个实际请求的响应文本及完整 block 对象相同；Paddle 同输入 layout 几何、顺序、类别及 ID 相同，Annex／SL 对照输出相同，仅 APB 原拼词仍被读错及 GCC 引号表示变化。**FP32 没有修复本批主要漏块、错列或编辑状态错误**。不能据四页推断所有原件精度等价，FoT／APL 没有同页 FP32 因果控制，本轮也未测 FP16。

训练及后续推理先使用 checkpoint 支持的 BF16 路线，Paddle 保留官方视觉／projector FP32 模块。不要以“训练后一定更准”为由取消这些保留计算，也不要将 BF16 训练路线写成 FP16 已通过。MinerU 实测 258 个响应全部 stop，最大输出含 EOS 369 tokens；其漏检测／错裁图不能由单纯提高输出预算解决。Paddle native 逐请求 token／EOS 终态尚未插桩，应先补观测再判断是否存在输出截断。

## 2. 错误应落到实际组件

Paddle 当前主路为 PP-DocLayoutV3 布局检测与读序、PaddleOCR-VL-1.6 区域识别及管线规范化。MinerU 4.0.10 **Standard／high** 当前使用独立 **PP-DocLayoutV2 小模型 → 布局规范化／裁图 → VLM `batch_extract_with_layout`**。Advanced／xhigh 的 `two_step_extract` 是另一配方。Standard 不调用 VLM 自身的 Layout Detection 任务；训练 MinerU raw VLM 的 layout 任务，不能声称已修复 Standard 的漏检。Paddle 主路 V3 的训练成果也不会自动替换 MinerU 的 V2。

MinerU Standard／high 还在编排中强制 `image_analysis=False`。SL1329 等图内相关性文字未索引，先检查任务是否实际执行及资产返回接口；只训练 Image／Chart Analysis 不会自动启用这个任务。首版仍按 Q10=A 建立准确原图／表组回取与相关性文字，采用何种源文字／title crop 路线须保留实际输入和来源，不因此切换 Advanced 或增加箭头语义任务。

每个残差都按原页→检测区域→规范化坐标／实际裁图→模型请求／原始响应→转换产物→跨页组依次定位。以下是已有证据支持的归因；未隔离的阶段明确保留待查，不用表面症状替代根因。

| 原件缺陷与已有证据 | 当前归因 | 先做的工程改进 | 残留时的训练目标与 gold | 难度判断 |
| --- | --- | --- | --- | --- |
| GCT1：MinerU condition 1 的 `1` 漏，condition 3 的 `3` 落入左侧 See Special 侧注；实际 body crop／aside crop 支持错覆盖 | Standard 布局／规范化裁图与读序链；不能靠识别阶段补不存在于输入 crop 的像素 | 对照可靠可见字框与完整页；检查正文编号覆盖、侧注分离、crop padding／坐标变换，建立 clause no 及侧注角色 | 仅在多来源相同残差持续时训练实际 V2 检测／读序组件，或明确验证更换布局组件；gold 要正文／侧注完整区域与顺序。Paddle V3 pilot 只解决主路自身残差 | 工程中等；训练依赖实际组件训练支持，不能直接套 V3 结论 |
| APB3：MinerU raw 和结构输出缺 GENERAL NOTES、GN-01／GN-02；Paddle 找回 | 布局→crop→识别链需要逐阶段隔离；现有覆盖缺口不能先判识别 LoRA | 先保留 Paddle 完整覆盖候选并对照原页；核对 GN 区域是否被检测、是否送到 VLM、是否在转换时丢失，保持组名／行身份 | crop 不存在则训练 detector／修 crop；crop 完整而 raw 漏才进入识别 pilot。gold 含整个清单与 notes 区域，非只补两句 | 隔离中等；训练不确定性由缺陷层决定 |
| APU3：MinerU 漏 `SP13/-/SITE/A/`，保留 BL/A；Paddle 两组前缀都漏，重复 ICU-01 仍在 | 布局还是识别尚未隔离；分组上下文不能从重复短 ID 安全推回 | 保留整页、分组标题与完整列表；覆盖前缀；hidden `Tr=3` 旧 OCR 仅作原证据，不替代可见正文 | 完整 crop 中仍漏时训练标题／分组识别，gold 精确前缀、行归属、revision 与换行描述；未确认完整 ID 标 unknown | 中等，主要依赖分组 gold；不能按固定前缀硬补 |
| APL3：两家三 trade 标题在，19 个 Roman 编号空槽及表单资产全漏 | 空表单检测／表示链待隔离；不是“没有内容” | 从原页保留 5／5／9 槽位、Roman marker 与空线几何，建立 `blank` 状态及父组；无姓名不等于无字段 | crop 缺失训练表单区域检测；完整 crop 被省略时训练空槽表示。gold 含全部 19 槽位，未知与真正空白分开，不生成承包商姓名 | 工程中等；定向数据需求，普通文本 SFT 不够 |
| Appendix H28–31：MinerU 已在 raw VLM 表格结构响应中将压力／单位放到 Fresh Water 空 offer 列，末页伪六列；Paddle 五列／四空 offer 候选更好 | 识别阶段的表格拓扑／序列错误；跨页自动组边另属应用层 | 对照原页采用 Paddle **完整四页组**候选；验证五列、items 1–20、Description 内单位与空 offer，不逐格拼答案；实现完整组与 item→subfield 边 | 持续跨来源错列才表格 LoRA；gold 用真实 cells／spans／多层表头／空 offer，转换为该引擎实际训练方言。MinerU 当前 raw target 是 OTSL，而不是直接 HTML | LoRA 中等，目标方言与导出回接需验证；跨页组仍要工程实现 |
| SL3436–3438：Paddle 首页面三列／表头错位，末页两个状态框同格；9 项／18 字形仍在；MinerU 完整三页关键网格更好 | Paddle 表格识别拓扑；两家 row 3／7 续行、Remarks 父边均未生成 | 采用经原件复核的 MinerU **完整三页组**候选；检查四列与状态 cell，对 row 3／7 建立跨页续行边和 Remarks 资格关系 | 训练 Paddle 单表正确 columns／cells／checkbox 状态；gold 必须完整三页组及逐页网格，跨页关系独立评价。不能以 glyph 数正确当列正确 | 单页表格训练中等；跨页关系工程中等，单页 SFT 不能独自完成 |
| Annex `HK$M`→Paddle `HKM`；APB 原件 `COMPLTION`→BF16 `COMPLIEN`／FP32 `COMPLIION`；FoT `PRE.B4.070` 两家截尾 | 关键字符／完整定位符识别；FoT 尚须验证 crop 覆盖尾部 | 可靠可见 PDF text／POI 优先；检查实际字框、crop、方向、分辨率与解码控制。保留原件拼错 `COMPLTION`，不要语法“修正”源文 | 确認完整像素进入模型仍错，才做关键 ID／单位／符号识别 LoRA；原件字面 gold，单列金额、单位、否定和编号 exact match | 小型定向识别 pilot 中等；不等于全域 OCR 训练 |
| FoT8／9：20M retained、25M struck 变普通备选，SCC8.305／final measurement 删除态丢；颜色本身不代表删除 | 原件编辑状态恢复；普通 OCR 文本目标不表达 live／deleted | 可靠 PDF glyph／线条／颜色／stroke 与文字映射，DOCX 用 run strike／revision；扫描态保留带位置视觉证据。没有可靠状态时 unknown，限制依赖该态的判断 | 真扫描编辑态需要单独源标注任务；不并入普通拼词 LoRA。gold 记录 retained／struck 及支撑 stroke 区域，图形指向自动解释按 Q10=A 保持范围外 | 原生恢复中等；扫描专用能力更高，不是本轮首个 pilot |
| APA／APC／SL1329：正确大图存在但支撑边条裁紧、内部标题少；MinerU APC19 判 table 并生成伪 cell，Paddle 图方向与 GONDOLA 标题不足 | 资产边界／分类／方向／文字关联分别定位；APC19 不采用伪 cell | 保存完整相关图、边框／titleblock、可读方向及 transforms；将可见标题和可检索文字绑定同一 asset 与父条、源页，返回原图验证 Q10=A | 残留分类错误在实际 detector；完整 title crop 仍读错才标题识别／caption pilot。gold 不包括首版不要求的箭头拓扑 | 工程中等；训练针对残差，不能把生成 caption 当原证据 |
| GCC／SL 普通 clause 内容基本在，Appendix I 注释／资格文本在；parent／footnote／crosspage 边没有 | 层级与关系规范化、来源路由 | clause no 主层级，保留子项／条件／例外、原页及 bbox；书签／点分隔编号仅作线索，不自动认祖先；unknown 关系不强造 | 初期不训练 VLM 重写法律文字；只有规则与原生证据仍不足的关系任务才另立数据／评价 | 工程中等；训练不是首选 |

这里的“优先采用”是有原件审核依据的区域／完整表组采用设计，不是按样本页码、固定项目值或 parser confidence 写分支。当前 23 页的页面输出并未实现完整 canonical projector、跨页 merger 或图表检索返回；不能把此表写成这些修复已上线。

## 3. 工程先行的实施批次

第一批补证据与观测：源字节 SHA、原物理页、render／crop 输入 SHA、非 identity 坐标变换、实际请求和终态、候选 raw 及完整 asset descriptor；Paddle 补 native token／EOS。检查每页是否完整解析及两路页映射是否对齐，超大 raw 不仅保存 size／SHA 后丢可回取内容。先区分检测漏、裁图漏、raw 漏及转换漏，避免把关系工程问题混入训练统计。

参数消融在本地独立实验目录进行：以已实跑 BF16 配方为基线，逐项检查布局阈值／读序、crop 覆盖与 padding、方向／resize、图像 token／输出长度和 batch；必要 attention 优化单列新配方。每次保留相同输入与完整输出，比较恢复了什么及丢了什么，参数调整仍须源页支持，confidence 不成为真值。不能同时调整多个组件后将效果都称训练收益，也不能仅为适应 16GB 静默丢掉表尾、前缀、脚注或原图边界。

第二批恢复可确定内容：原生 DOCX 继续 POI／OOXML；PDF 可见字框、Widget／appearance、可靠编辑线条与视觉结构联合恢复。GCT 普通抽文空 alias 不覆盖可见合同字段；APU 的 hidden 旧 OCR 不接管扫描内容；APL 真空字段保留。使用源证据恢复的 canonical 内容仍带原生／视觉来源与 adopted revision，原候选保持原貌。

第三批建立结构与可回取资产：clause 父层级、notes／qualifications、完整跨页 table group、header／cell/span 与 row continuation；完整图及标题／相关性文字与父条绑定。表组采用不逐格拼、不逐页挑较好片段；需要融合的关系仅由完整组原件证据支持。单页识别训练不能替代这一批。

第四批用完整 gold 重跑并冻结残差。保留未训练 checkpoint 及同输入基线，把剩余错误归到实际组件；布局改善对识别的连带影响与识别本身改善分报。尚未有可靠源证据的字段／关系继续 unknown，不用新模型生成值充当核验结果。

## 4. 建立可训练的完整 gold

由 Codex 对照真实原页制作，另做一遍源页复核；不要求用户承担标注、不将 `humanConfirmed` 设为 true。先冻完整页／完整表组 gold，再从中派生 stage-specific 训练样本。模型候选只在 gold 冻结后用于错误分析，不从候选或 2d／2e 答案反推源文。

| 标注层 | 必须保存的内容 | 不接受的代替物 |
| --- | --- | --- |
| 来源与角色 | 原件 SHA、版次、范围／模块角色、项目与原件／近重复／派生家族；原物理页或 POI 稳定路径；render/input SHA 及变换 | 仅文件名、文本＋结构 `sourceHash` 冒充原始字节 SHA，或按缓存样板推新项目身份 |
| 全文与区域 | 准确可见文本，包括源拼词、标点、ID、金额、单位、否定；bbox／polygon、区域类型、实际读序；未知字符有位置与 mask | 按语言习惯润色原件，或只标错词和漏项而遗漏完整页 |
| 表格／表单 | 全部 cells、row/col span、多层表头与字段归属；空白／unknown、checked／unchecked 的源状态；表组全部页及续行证据 | 文本中有 18 方框就算状态列正确，省略空 cell／空槽，逐格混合两家输出 |
| 关系 | clause／子项、条件／例外／footnote、parent、续页／续行、Remarks、表组边界及对应源位置 | 点分隔字符串自动 ancestry，书签错误层级，当前页面扁平 blocks 冒称完整树 |
| 编辑态 | 源 glyph／stroke／线条或 OOXML 属性、retained／struck 支撑范围及未知态 | 红色一律 deleted、模型语法润色决定有效数字、未证关系当确定 |
| 资产 | 完整图表区域、title／caption／图内可读文字、可读方向、父条／表组、原页和 artifact SHA | 浅标签或生成概述取代原图、关联到相似的另一资产、自动箭头含义作为首版要求 |
| 版本与审阅 | gold 版本、source-only 初标和二次复核记录、未知区域清单、冻结时间、训练 export lineage | 完整解析成功收据、软件测试通过、Codex 审阅冒称第三方或人类已确认 |

中间 gold 使用引擎无关的 cells／spans／regions／relations。训练 export 分别生成对应引擎支持的 category／read order／mask 或实际 VLM target 方言，并验证 parser 能还原相同拓扑。MinerU 当前表格 raw 方言为 **OTSL**（`<fcel>/<ecel>/<lcel>/<ucel>/<xcel>/<nl>`）；public HTML 是转换产物，不能直接拿 HTML 作为当前 raw VLM 的训练标签。Paddle 也须依据选定训练 recipe 和 native runtime 的实际任务输出核对 target，不仅根据最终 JSON 推断。

unknown 区域在训练格式中正确 mask 或不参加对应 target loss；无法局部 mask 的格式先排除该训练实例，保留覆盖缺口。不能将 unknown 转成错误负例或真空字段。完整 gold 仍保留这些区域，不因训练 export 排除就改变验收分母。

答案的结构 token 与输入占位 token 分别处理：MinerU 的上述 OTSL token 在词表中是 `special=true`，不能按“特殊 token”全部设为 `labels=-100`，也不能在 serving／decode 用 `skip_special_tokens=True` 抹掉结构。prompt、padding、图像占位、EOS 与 assistant 表格结构监督各自核验。训练 loss 有数值而空 cell／span token 没参与监督，仍不能通过数据门槛。

## 5. 留出与样本规划

先按 **原文件／近重复／派生家族**划分，再派生 crop、扫描降质或变值。单个源页的 full page、crop、旋转版、压缩版、DOCX 派生 PDF 均进入同一 partition，跨页组全部页同组；不能让同一内容的不同清晰度分别进入 train 和 test。

SL 是 **一份 4,300 页 source 容器**，活动技术范围是其中 3,174 页。可赛前精修和调整，并按 worksection／clause family 保留未用于训练的内部诊断集；但其他 SL 章节／工作分项仍是同源，**不冒称原文件级盲测或新来源泛化**。近似条款不按随机段落拆作独立训练与评价样本。真实泛化需另有不同实际来源／版次／项目，且冻结前没有同内容或派生泄漏。

本批 14 份 PDF 均来自已暴露资料；14 不表示 14 个独立项目。现有 23 页继续作为固定开发与回归，不转写为未见测试。在剩余资料内另冻文件／家族级独立留出并记录暴露史；没有真正新来源时明确只能报告内部／同资料包效果，保留新来源验收待完成。合成变值、模糊、扫描方向等另报压力测试，不填补真实来源泛化这一项。

建议初始预算是 **60–100 个完整原页，含现有 23 页；其中覆盖 5–10 个完整跨页组**。跨页组页数计入同一个完整页分母；不是额外增添 5–10 组后还声称只有 60–100 页。覆盖正文＋侧注、标题／编号、混合方向、空表／空槽、notes、相似编号组、编辑态及图资产，同时保留普通正确页用于回归。布局区域数、字符数、cells 与 crop 数由完整标注后实际统计；裁成更多 region 不增加独立 source 家族数。

上述是首轮工作量规划，不是统计最小样本量或训练有效性保证。若留出错误持续且分布明确，再扩大到 200–400 页；若失败来自 target／回接兼容性或工程关系，不用更多标注掩盖接口问题。独立留出比例依实际 family 数确定，在训练前冻结；不能为了方便固定一个页级百分比，再将同源近重复随机混入。

## 6. 训练路线与回接支持层级

训练框架能 forward／backward、能保存 adapter、能恢复完整解析 pipeline 是三个不同关口。固定上游 checkpoint、依赖、tokenizer／processor、task template、精度和实际 trainable modules；相近版本文档仅作候选，不追认为当前 revision 已支持。

同日固定官方文档／源码及本机小配置的详细核查分别见 [Paddle 当前 checkpoint 训练研究](./paddle-current-checkpoint-training-feasibility-20261009.md) 和 [MinerU 当前 checkpoint 训练研究](./mineru-current-checkpoint-training-feasibility-20261009.md)。它们证明接口／候选通路，不证明训练、梯度、merge、零步往返或新模型推理已完成。研究用最新 pinned 源码与本机已实测 wheel 分别记录，不将新源码机制追认为已安装依赖具备相同能力。

### A. 布局 pilot：对应残差，避免机械训练

Paddle 主路 PP-DocLayoutV3 有官方同模型训练／导出路径。PaddleX 固定源码教程规定 **COCO instance segmentation＋每区域 `read_order`**，25 类，read order 从 0 连续编号；训练损失包括 bbox／mask／dice／order。官方训练初始化是单独的 `PP-DocLayoutV3_pretrained.pdparams`，不是当前 HF 静态 inference artifact。不得将 `.pdiparams` 直接当训练权重，也不能假定两份产物哈希或参数格式一致。

保留当前 25 类 ID／名称／顺序和对应 model head；模块 demo 的 `num_classes=11` 不能直接套到本机 25 类配方。完整页的其他区域同样标注，不仅框目标条款号而将旁边正文／表头当无标签背景。训练初始化与当前推理权重无法证明等价时，另建该训练起点的未训练基线，再区分起点变化与 fine-tune 收益；native 主路优先官方静态导出，不顺便更换 inference backend。

只有主路自身反复漏正文编号、侧注归类、读序或完整图范围时才开 V3 pilot。先验证 dataset export、类映射、同输入未训练导出基线，再小量 overfit 检查 loss 与区域／read order 是否学习，最后文件／家族留出评价；不为解决 MinerU V2 错 crop 而无目标地训练 Paddle V3。MinerU Standard 若需布局训练，独立核实 V2 recipe／导出／registry 回接，或明确替换布局组件后以新配方完成全链评价；V3 的官方支持不等于 V2 已验证。

### B. 识别／表格 LoRA pilot：只训完整 crop 中仍错的目标

先以源 gold 提供正确诊断 crop，比较原模型和微调模型对同一 crop 的字符／拓扑；再把微调模型接回未改或明示改动的 detector 做端到端评价。前者隔离识别收益，后者检查实际布局造成的残差，不混成一个训练准确率。初期保持 detector、vision 与其他非目标组件不变，训练模块与 tokenizer/task target 兼容性先验证；如果需要解冻 vision／projector，另立实验和资源预算。

限定实际语言层的完整模块前缀并保存匹配名称／shape／trainable count；不只给 `q_proj/v_proj` 后缀或 `all-linear` 后就假定视觉层未被匹配。固定 processor resize、图像 grid／token、system／task prompt、answer mask 和 EOS；通用训练 template 名相同不足以证明与当前 OCR 请求等价。

| 候选 | 目前可支持的判断 | 进入 pilot 前的最小门槛 | 导出／回接风险 |
| --- | --- | --- | --- |
| PaddleOCR-VL-1.6 | 1.6 卡说明与 1.5 架构兼容；公开 SFT 文档当前明确针对旧 PaddleOCR-VL-0.9B／v1.5，PaddleX `doc_vlm` trainer 不支持普通 `.train`。不能直接称 native 1.6 有已验收训练 recipe | 固定 Transformers 实现核对实际 `labels→loss`、trainable modules／PEFT、processor／tokenizer、任务模板；先完成零步 HF 导出→native reload 对照，再少量梯度／保存试验 | 官方 Transformers 逆 weight conversion 和 `save_original_format=True` 是可用候选通路，不能预判必需自写转换器；但当前同 revision 往返未实测。PEFT adapter-only 不能直接让 native 替换；需 merge 完整模型，再验证 key／shape／转置／融合与 runtime 加载 |
| MinerU2.5-Pro-2605-1.2B | 固定 config 为 `Qwen2VLForConditionalGeneration`／`qwen2_vl`／BF16，实际 1,156,026,624 参数。通用 Qwen2-VL LoRA 可作架构候选；固定 MS-SWIFT 源码明确注册 2604-Pro，不能追认 2605 已适配 | 当前完整 task template／token、OTSL、processor 与 Standard extraction 请求等价；2509 官方教程要求 `mineru_ext`／`run_mineru.py sft`，不能仅执行普通 `swift sft` 宣称同配方。先零步加载／回接再梯度小试 | Standard 的 `model.vlm.server_url`／`model` 可回接独立 serving 的 **merged 完整 HF VLM**；不把 adapter-only 路径当整模型，不认为会自动替换 weight registry。保持 Standard 布局组件并复验其实际请求、响应与转换；Advanced 是独立新配方 |

推理混合精度支持不代替训练支持；能保存 safetensors 不代替 native pipeline 回接。若零步往返已经改变输出、target 格式或关键关系，先修兼容性并建立新基线，不归因训练，也不急于扩大训练集。

导出在全新进程重载，检查 key／shape／tensor 及 config、processor、tokenizer、chat template 身份。Paddle 原始 flat config 与新实现 nested text／vision config 的兼容单独核验；不能只逆转键名后让未知字段使用默认值，或用旧 config 掩盖真实架构变化。未知 missing／unexpected／mismatched keys 阻止继续，避免新初始化参数混入训练收益。MinerU remote serving 同样保留实际 raw OTSL 与 finish reason，普通聊天成功不等于 Standard 全页通过。

本阶段主张先各做一个小而可回接的目标：布局以实际系统残差为触发；识别以完整裁图内的精确 ID／单位或表格空列／状态 cell 为目标。不要同时更换 checkpoint、attention、图像尺寸、检测器和训练参数后将综合变化称 LoRA 收益。对当前 Paddle 骨干无残留目标就保留原模型；MinerU 表格 pilot 也须证明它在完整组上优于当前可采用候选，才能增加上线复杂度。

## 7. 本地完善、完整打包与超算推理验收

### 本地 RTX A4000 16GB 是默认迭代场地

本机已完成两路串行 BF16 完整推理及预定四页 FP32 控制；这证明当前本地参数消融和原件回归可继续建立在实际运行基线上，**未证明本地训练通过**。16GB 是整卡总量，不是可全部占用的空闲量；其他 GPU／WSL 服务保持用户要求的运行边界，Bonsai 继续抑制。只回收本次实验自有模型，不为 pilot 停其他业务服务。

训练若确有必要，先在独立本地环境一次一个目标模型，微批次 1、低 rank 语言 LoRA、模块冻结、梯度累积及实现支持的 recompute／gradient checkpointing；具体模块／rank／学习率在本地验证后冻结。分别测 load、forward、backward、optimizer step、保存／merge／reload 的 allocated／reserved、整卡采样和 host 峰值。不能以 2–3GB 权重字节推断训练也只占这些显存，本机 eager 推理的高峰也不能直接换成训练峰值。

若超出本机实际显存，先降低 microbatch、数据并行预取和无必要的激活驻留，核查可用 checkpoint／冻结策略；调整 resize／crop 时验证完整关键字符、表尾、空槽及原图边界。正确完整区域无法保全时记录本机训练未通过，另评估合适训练地点／方案，**不自动转到超算现场训练、不静默降质后报告修复成功**。训练地点的改变是另一个决定；80GB 训练只保留技术储备与未实测资源推论，不作为当前默认流程。

### 冻结一个完整 release，再走平台扫描发布

完成本地工程、参数消融及必要训练／导出回归后，冻结一个可重建的完整版本包：业务和解析代码 commit／源码 SHA、目标 Linux／CUDA 对应依赖锁与离线安装或镜像、两路模型权重及父 revision、processor／tokenizer／task template、布局／精度／crop／生成配置、scope／角色路由、canonical projection／索引身份、回归脚本与检查清单、资源观测脚本、恢复／回滚材料及统一 manifest。模型／原件仍不进 Git；部署包中允许预处理的固定来源缓存按原件 SHA／scope／版次和模块角色绑定，不夹带样板项目取值或 2d／2e 答案进生产候选池。

本地尽量按目标环境在独立 WSL／容器检查安装、导出新进程重载和完整两路处理，再冻结 package hash；未能等价验证的 H800／CUDA 项明确列入部署验收清单。**完整 release 按平台要求一次扫描发布，配置、权重或脚本变更也不假定免扫描。**平台只执行冻结流程与收集验收收据，不作为反复调代码／调模型的试验场。若暴露兼容或质量问题，回本地修复、回归并生成新版本包，再按平台要求重走扫描发布；不在平台 hotfix，也不覆盖旧包伪装同一版本。

### H800 只验收冻结包的真实推理表现

超算资源是 **1×H800 80GB、27 vCPU、250GB RAM、650GB 高性能存储＋1250GB 普通存储**。详细推理阶段调度见 [H800 计划补充](../vetting-h800-moe-plan-20261004.md)。约 120B MoE 的具体 checkpoint、量化和引擎未定；兼容性、真实 OER 峰值、全批吞吐和 LLM 冷／暖恢复均以发布包实际运行测量，不从 A4000 时长或静态权重大小推断。

部署验收按 Paddle 整批→MinerU 整批→embedding／已知 query 取证→LLM 的已确认设计，正常一批恢复 LLM 一次；Advisory 保持 LLM 热驻留，小检索模型 CPU 或经证明的安全共驻。量化权重 RAM 热缓存、H2D／ready／prefill、host 临时副本及实际 VRAM 释放独立观测。该流程是推理与模型切换验收，不是赛中训练。80GB 可训练小 VLM 的算术空间与当前发布目标分开，不把平台资源充足作为改动上线流程的理由。

| 工作包 | 规划工作量／工期，非实测 | 完成条件 |
| --- | --- | --- |
| 原生恢复、覆盖观测、资产／完整表组与关系第一版 | 3–7 个工作日，一名执行者、资料及依赖已具备时的初始估计 | 23 页开发集与已知互补组可完整解释；未解决共同缺陷保留 unknown；接口与源位置可以回取 |
| 60–100 完整页／5–10 完整组 gold、源复核和 family 留出 | 5–10 个工作日；复杂表格／编辑态可能增加 | 全页／全组分母冻结，完整标注、未知区域与训练 export 可复算；不只标选定 37 项 |
| 必要的本地对应组件训练／LoRA 小 pilot | 兼容性与本机 16GB 资源门槛通过后 2–5 个工作日 | 梯度／loss、小量 overfit、家族留出与原模型对照；超显存且关键区域不能保全时另评估，不默认用平台训练 |
| 本地 merge／导出回接及完整 pipeline 回归 | 已有可用通路时 2–5 个工作日；框架 bridge 未验证可能主导工期 | 零步／训练后新进程回接、全组质量、旧正确项回归、身份／回滚和本机资源峰值 |
| 冻结完整 package／平台扫描／H800 推理验收 | 本地验证完成后准备；平台扫描等待、首次兼容与实测时长尚无数据 | manifest／package SHA 完整，按平台扫描发布；只跑冻结脚本，失败回本地修新包 |

这些数字仅用于分配工程与标注预算，不是承诺总工期、最小有效样本或质量提升幅度。标注与接口核查可部分并行，不把上述区间机械相加；目前没有测得训练 GPU 小时、H800 吞吐或最后的上线时间。若 recipe／导出关口失败，先解决它，暂停更大训练规模，而不是继续消耗标注和 GPU。

## 8. 训练前后都要通过的门槛

| 关口 | 必須留存的证据 | 不能据此声称的结果 |
| --- | --- | --- |
| 原件／gold 冻结 | 原始 SHA、完整页／组、来源家族、暴露史、全文／cell／关系／资产数量和 unknown 分母、Codex 源复核 | 标注是第三方人工验真、历史 23 页是新盲测 |
| 阶段归因 | 实际 detector、normalized bbox／crop、请求／raw response／转换产物链；正确诊断 crop 与真实 pipeline 分开 | OCR 错都属于 VLM、图未入 crop 也能靠识别 LoRA 修 |
| 数据／loss 小试 | 引擎真实 target、tokenizer／processor；labels／loss、梯度、少量样本 overfit 及保存再读；unknown mask 行为 | 小样本拟合就代表新来源能力，通用模型名兼容就代表当前 recipe 可用 |
| 独立留出 | train／dev／source-family holdout manifest；不在 holdout 调参数；原模型与新模型同输入／任务／候选池 | SL 不同章节是文件级泛化，合成变值等于真实新项目 |
| 精确质量 | 文本 CER／WER 及关键 ID／金额／单位／否定 exact match，区域覆盖／类别／读序，cell/span／空栏／checkbox，完整组／续行／父边，资产来源与完整边界，edit state；分别报告 source 支持与 unknown | 平均 CER 下降可掩盖一个金额或状态列变错，18 字形正确等于四列关系正确 |
| 配方／导出回接 | 零步完整模型往返、PEFT merge／save、相同 full pipeline reload、新 checkpoint／tokenizer／config／artifact SHA、实际 task dialect 与坐标映射 | adapter 文件存在即已部署，standalone HF 输出代表 Standard／native 全链通过 |
| 回归与增量收益 | 旧原模型独立保存；现有 23 页／37 项全量回归并加入完整 gold 分母，普通正确页与互补完整组；至少不丢旧关键依据 | 只在训练错误 crop 做对就全局变好，主路替换不再需要完整第二路 |
| 索引／业务 | canonical 新 revision、source SHA 与 `sourceHash` 分开、parser／model／recipe／scope／quality／projection 身份，索引重建；必要证据召回、同池 rerank、真实原图／表组返回 | 软件测试或 parser completed 等于检索、答案或前端业务验收 |
| 本地资源／平台恢复 | 本地 peak allocated／reserved、整卡采样、host RSS、必要训练及 merge／reload；H800 冻结包推理峰值／吞吐、实际释放、LLM warm restore／prefill，旧版本回滚 | 参数字节等于峰值、16GB 训练已经通过、80GB 平台默认用于训练、GPU 时间是总等待时间 |
| 完整发布边界 | 本地 release 回归、代码／依赖／权重／配置／task／processor／scope／index／rollback manifest、package hash、平台扫描与发布收据 | 模型或配置文件可免扫描、平台现场调试／hotfix、不同字节仍称同一 release |

不新增一个笼统“训练准确率”盖住以上分母。学习目标可以只覆盖识别，但完整业务 gold 仍检验漏块、错列、关系与源图是否保真。对新增关键错误即使平均分上升也要保持对应局部门控，修复或回滚；没有证明的提升不发布为通过。

## 9. 当前实施接口对训练回接的限制

只读核查的现有 `structured_parser_evidence.py` raw 默认 64MiB；超出后只记 size／SHA 并 `contentOmitted`，asset bytes 默认 16MiB；save bundle 当前写 JSON，尚无磁盘 descriptor／流式完整捕获。dual run 核查 parser／source，却尚无全页完成／对齐门槛。table group 当前只有单 block 身份、crosspage／header 关系未知，route 接受 identity transform；这些接口不足以证明训练后全量产物与跨页组正确。

`oer_server.py` 要求上游 canonical projection，不直接消费候选 bundle；未找到完成的 projector／assetRefs 返回实现。Java `DocumentBlock` 的 normalized xywh 与 parser xyxy 须显式转换，`VettingCorpus` 还需承载 source-byte identity、组／资产与 provenance；其文本＋结构 `sourceHash` 不是原件字节 SHA。OCR table 不能标作 POI 的 `nativeCell/nativeTable`。

因此回接前须先建立可保存、回取及验证真实产物的接口；训练 checkpoint 更换后更新 model／recipe／projection／quality 身份并重建对应 index。保留旧 checkpoint、配置、原始候选、canonical revision 和旧 index 的回滚映射。接口缺口由工程实现解决，不以换模型或训练“顺便解决”作承诺。该核查不代表新接口已经实现。

## 10. 下一步具体决定

1. 以现有 23 页作为开发／回归，先落实可靠 native evidence、真实 crop／终态观测、完整图／表组与 clause／continuation 边；保留 FoT、APL、APU 的共同失败状态。
2. 冻结 60–100 完整页的 gold 预算和真正文件／家族留出，统计实际 regions／cells／字符／关系分母；SL 内部诊断与真实新来源测试分开。
3. 核实两个 VLM 的零步导出／完整回接及真实目标方言；PP-DocLayoutV3 官方训练路径只作为 Paddle 主路残差的可用 pilot。MinerU Standard 布局残差单独处理 V2／crop 链。
4. 本地工程／参数回归后仍有完整 crop 内字符／表格错误，才做必要的小 LoRA pilot；保留原模型对照，以留出关键证据和完整组收益决定是否扩大数据。16GB 不能保全关键区域时另评估，不转超算现场迭代。
5. 本地完成完整管线重载、身份／索引更新、真实必要证据召回及前端源图／表组返回，冻结完整 release 与回滚材料，扫描发布后在 H800 验收推理／资源／吞吐／恢复。平台问题回本地修新版本再发布。当前训练、export roundtrip、完整打包发布和 H800 推理／业务关口均尚未完成。

本文未生成新的 gold、未训练／导出模型、未运行模型或业务测试，也未将规划配置施加到在线服务。完成实测后在本报告补独立收据与失败状态，再由中央 scope／handoff 维护实际进度。

## 11. 固定支持来源

下表是训练决策的主要第一方入口；小文本副本、抓取时间／SHA、其他源码和失败收据分别保存在忽略的 `.local/state/oer-training-research-20261009/paddle/` 与 `mineru/`。完整源列表见两份 checkpoint 研究报告。这里未重新下载权重或测试这些 training APIs。

| 决策依据 | 固定第一方来源 |
| --- | --- |
| V3 数据／训练／静态导出及单独训练起点 | [PaddleX layout_analysis 文档](https://github.com/PaddlePaddle/PaddleX/blob/c852371c701d83cd21955b2963834825920ffebb/docs/module_usage/tutorials/ocr_modules/layout_analysis.en.md)、[V3 模块配置](https://github.com/PaddlePaddle/PaddleX/blob/c852371c701d83cd21955b2963834825920ffebb/paddlex/configs/modules/layout_analysis/PP-DocLayoutV3.yaml) |
| VL1.6 labels／loss 与官方逆权重映射候选 | [固定 VL1.6 model](https://huggingface.co/PaddlePaddle/PaddleOCR-VL-1.6/blob/c5630abae1d940eafe0697512a0325494b02ab42/modeling_paddleocr_vl.py)、[Transformers mapping](https://github.com/huggingface/transformers/blob/9c59c0c6733990bbf8b1be7cb93b97e57dc73c39/src/transformers/conversion_mapping.py)、[save_original_format](https://github.com/huggingface/transformers/blob/9c59c0c6733990bbf8b1be7cb93b97e57dc73c39/src/transformers/modeling_utils.py) |
| 旧 Paddle 训练 recipe 的版本边界 | [ERNIE OCR SFT 文档](https://github.com/PaddlePaddle/ERNIE/blob/790a50b045d1aca2753d5395d8bec0806b2e6925/docs/paddleocr_vl_sft.md) |
| MinerU 当前 Standard 布局／任务编排 | [4.0.10 window](https://github.com/opendatalab/MinerU/blob/c221cc41bc911ad0df3eaeda97acf6a1bfe9bf93/mineru/backend/analysis/pdf/window.py)、[小模型 registry](https://github.com/opendatalab/MinerU/blob/c221cc41bc911ad0df3eaeda97acf6a1bfe9bf93/mineru/model/registry.py) |
| 2605 架构与结构词表、MS-SWIFT 注册边界 | [2605 config](https://huggingface.co/opendatalab/MinerU2.5-Pro-2605-1.2B/blob/08aaea840498d49ce16247b6263196cf02814885/config.json)、[tokenizer](https://huggingface.co/opendatalab/MinerU2.5-Pro-2605-1.2B/blob/08aaea840498d49ce16247b6263196cf02814885/tokenizer_config.json)、[MS-SWIFT Qwen 注册](https://github.com/modelscope/ms-swift/blob/410a5d18930725d1d3cfe3acdd611eb9795c403b/swift/model/models/qwen.py) |
| MinerU actual prompt／OTSL 与完整 remote 回接 | [mineru-vl-utils client](https://github.com/opendatalab/mineru-vl-utils/blob/405520b8fe6be61725537984eb4a00d404dc272a/mineru_vl_utils/mineru_client.py)、[OTSL converter](https://github.com/opendatalab/mineru-vl-utils/blob/405520b8fe6be61725537984eb4a00d404dc272a/mineru_vl_utils/post_process/otsl2html.py)、[4.0.10 remote 模型源](https://github.com/opendatalab/MinerU/blob/c221cc41bc911ad0df3eaeda97acf6a1bfe9bf93/docs/en/usage/model_source.md) |
