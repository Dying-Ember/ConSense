# OER 离线监督、基线及轻量训练入口

此目录独立于在线服务和 Drafting 变量识别 harness。入口默认只做 CPU 数据／processor 预检；`baseline` 和 `train` 要求明确 `--cuda-lease`，由主任务安排独占显存窗口后执行。不会卸载服务、启动监听、替换在线模型或发布索引。

现有 23 页／37 关键检查是已暴露开发回归，不能拿来当完整 gold 或独立训练留出。GCT 完整条款 crop 的少量监督只能做 `compatibility_only`：可验证完整输入→loss→LoRA→HF 导出→新进程重载，不得宣称泛化或准确率提升。SL 表组 53 cells 是结构库存，仍需逐 cell 原件监督；不因有结构数组就自动用于训练。

先运行 `dataset.py MANIFEST --output RECEIPT`。manifest schema 为 `consense.oer-training-manifest.v1`，`purpose` 是 `compatibility_only` 或 `quality_training`。source 必须含原始文件 path/bytes/sha256，`sourceFamily`、`editionFamily`、`nearDuplicateGroup`、`partition`、`exposure`、`route`。所有源版本、派生 crop／DOCX-PDF 和近重复按家族整体分组；同组不能跨 train/dev/test。SL 内部 worksection 留出仍属同源内部诊断。2d／2e 只可记录为 quarantine，绝不能成为 supervised sample、生产 index 或答案提示。

sample 记录 `sourceId`、原物理页、完整 image／target descriptor、`imageTransform` 原页 bbox，`coverage=complete_task_unit`、`unknownRanges=[]`，以及 source-only 审阅 artifact、actor、真实 `humanConfirmed`。本 pilot 不支持 unknown mask；有未知的实例保留在 gold 库，但排除训练，不能改写成真空。target 是 UTF-8 原件字面全文，不润色源拼词。table 必须为 MinerU raw OTSL，HTML 是转换产物。结构完整性仍需另用 source-cell/span gold 评价。

```powershell
& $Python ai_consense_service/tools/oer_training/mineru_lora_pilot.py `
  --manifest $Manifest --snapshot $Pinned2605Snapshot --output $NewCpuRun
```

预检使用当前 processor、真实完整图像和完整 target。报告实际图像 grid、输入 token、完整 target＋EOS token、prompt／image loss mask、全部 OTSL 结构 token 监督。超过模型有效 8,192 context 就失败；不截取目标或悄悄减小图像。

GPU 兼容起点：当前 2605 1.2B，BF16、SDPA（独立于当前线上 FP32/eager 配方）、LLM-only rank8，冻结视觉／aligner／embedding／lm_head，microbatch1、gradient checkpointing、完整 target、一步 AdamW。依赖为已有兼容 Torch CUDA、Transformers、Pillow、safetensors，加 PEFT／accelerate；运行收据保存实际版本，需在独立环境安装，不能改服务环境。约 2.153 GiB BF16 参数和 4,399,104 LoRA 参数是静态算术；不代表训练峰值。先完整 forward/backward/optimizer 实测，OOM 保留，不能用短目标追认通过。

```powershell
& $Python ai_consense_service/tools/oer_training/mineru_lora_pilot.py `
  --manifest $Manifest --snapshot $Pinned2605Snapshot --output $NewCudaRun `
  --mode train --cuda-lease $Lease --steps 1 --dtype bfloat16 --attention sdpa
```

运行会保存 baseline 完整生成、finite loss／gradient、各阶段 CUDA allocated/reserved/free 指标、adapter、完整 merged HF（含 processor/tokenizer/template）、逐文件 SHA 清单。释放父进程模型后，以同一 Python 的新进程重载 merged，再比较完整生成 token IDs。`--steps 0` 可先验证零更新 export/reload；零更新和 tiny 训练分别标识，不等于质量提升。

此处新进程 HF 重载只是第一道兼容 gate。下一道必须通过 MinerU 4.0.10 官方 remote VLM seam，以同一完整 source page/table group 验证实际 parser 请求方言、raw OTSL、全部资产、canonical 来源和关系。不要直接修改固定权重 registry、完成 marker 或现有 FP32 服务，让未验收模型悄悄在线。Paddle DocLayoutV3 训练、Paddle VL1.6 导出及完整 native reload 属于另一路，不能用 MinerU 成功追认。

2026-10-10 已在本机 A4000 完成第一轮实际兼容作业：新 render 的 GCT 原件第 2 页完整第 4／5／6 条款，3 crops／433 字符，完整 input＋target 为 341／336／599 tokens，3 optimizer steps。原件 source-only 再复核，`humanConfirmed=false`，同一个历史暴露 source 全部归 train；不是独立质量 holdout。Torch 2.11.0+cu128、Transformers 4.57.6，PEFT 0.18.0／accelerate 1.12.0 采用独立 `--target` overlay，没有安装到服务环境。

真实作业 31.489 秒完成训练、完整 HF merge/export 和新进程 reload；后者退出 0，完整生成 token IDs 与内存 merged 逐条一致。allocator peak allocated 为 3,731,759,616 bytes（约 3.475 GiB）、reserved 为 4,846,518,272 bytes（约 4.514 GiB），不是整卡峰值。首轮未采样整卡连续峰值，也未记录运行时每个参数 dtype census；分别保留这两个观测缺口。CPU 导出 header 核验 merged 681 tensors 实际 BF16，adapter 336 tensors 实际 FP32，原模型与 merged 全部 tensor shapes 0 差异；168 个初始为零的 LoRA B 矩阵均出现有限非零保存值，支持真实参数更新。

这三条在训练前和训练后都为原文字面 exact 2／3，只忽略空白 exact 3／3，前后完整生成 token IDs 相同。**本轮证明可训练、可导出和可新进程重载，没有证明准确率改善。** 表格结构监督和独立 source-family 质量留出继续待验。真实 receipt／baseline／loss／模型／artifact SHA／独立评分位于忽略目录 `.local/state/oer-training-quality-20261010/cuda-lora-three-complete-crops-01/`；原始失败／未完成状态不改绿。

同日后续在独占窗口 `oer-training-20261010-parser-seam-02` 完成第二道真实兼容 gate：完整 merged BF16／SDPA 模型通过独立 localhost endpoint 接入官方 MinerU 4.0.10 `MinerUParser` 的 remote VLM seam。完整 GCT 2 原页和 SL 物理 3436–3438 完整表组均实际解析，38＋39＝77 次调用全部 stop／0 length；保存完整原图片、请求、processor grid／tensor、raw OTSL、token IDs、middle／model output、vendor 资产和原件 form-aware 渲染生命周期。两个 parser worker 分别 42.843／42.557 秒，全作业含加载、原件核验、进程边界共 96.700 秒，77 calls 的实际累计时间为 70.054 秒。主模型实查 681 tensors／1,156,026,624 参数全部 CUDA BF16，attention 为 SDPA；官方 ONNX 辅助 CPU 另记实际 provider，不将该 Windows 兼容试验当作 H800 full/torch 部署验收。

本轮主模型 allocator peak allocated 3,017,497,600 bytes／reserved 3,955,228,672 bytes。整卡 153 次连续采样的峰值 6,743 MiB，包括原有约 2.8 GiB 保护进程占用；采样末项仍是释放前驱动读数，关闭 owned endpoint 后另行读回 2,768 MiB／13,402 MiB free／0% GPU，不能把 allocator、整卡或最后采样三者混用。临时端口 18104 已关闭，无线上模型／registry 更新。

原件离线开发回归：GCT 24 regions 中 20 只忽略空白 exact；11 正文中 9 exact，原有 condition 1／3 的条号布局及两页 `- GCT/1 -`／`- GCT/2 -` 外侧横线漏裁残差保留。SL 53 cells／18 空框；11 完整描述、11 四单位 span 行和 controls、3 页外 remarks 全部符合已知 source-only 观察，仍不等于精确 cell gold 或跨页归属验收。独立 19 项输入／终态／form-session 检查通过；与历史输出的逐页原始文字 multiset 和三页完整 table 内容全同。该 DocVortex model-output 接缝的 table 内容已经是转换 HTML，实际 raw OTSL 另存在 endpoint 原始响应中，不能把 HTML 字段重命名为 raw OTSL。GCT 历史为 base FP32/eager，SL 历史为 base FP16/vLLM/full-torch，当前则为三步 merged BF16/SDPA/ONNX；这组历史比较混杂，不能据此声称训练带来收益。证据位于 `.local/state/oer-training-quality-20261010/parser-seam-02/`，质量和 blind holdout 仍为 false。

随后完成受控原始 base BF16／SDPA 对照，lease 为 `oer-training-20261010-bf16-controlled-03`。重放官方 parser 已产生的 **全部 77 个完整 recognition 请求**，不重新运行 layout／crop／parser 辅助；因此这是同输入主 VLM 权重消融，不能称为新一次完整 parser 或布局效果验收。owned base snapshot 使用原始 revision `08aaea840498d49ce16247b6263196cf02814885` 权重，processor／tokenizer／chat template／model config／generation config 的十个文件与 merged 字节相同。请求只替换 transport model ID；全部实际 prompt、图像字节、processor tensor／grid、输入 tokens、生成参数、penalties、effective cap 和运行 census 都逐请求核验相同。独立 CPU 预检 100 项、11 个契约通过，依赖漂移在 CUDA 加载前拒绝。

实际 **77／77 完整 token IDs、77／77 文字、3／3 完整 raw OTSL 完全相同**，77 stop／0 length，输入累计 13,841、生成累计 2,194 tokens；本轮没有训练。真实参数全部 CUDA BF16，attention=SDPA，allocator peak allocated 3,017,497,600 bytes／reserved 3,955,228,672 bytes。全作业 79.326 秒，77 请求累计 68.314 秒、中位 0.394 秒；整卡 128 样本峰值 11,989 MiB，包含约 7.7 GiB 原有保护进程，与前轮整卡 6,743 MiB 的背景负载不同，不能当作模型显存增长。仅 owned 18105/PID 已关闭，退出后单独读回 7,995 MiB used／8,175 MiB free。证据在 `.local/state/oer-training-quality-20261010/base-bf16-controlled-03/actual-exact-77-01/`，执行 manifest SHA 为 `da4c7512df37ef4a013f7bc88fc79c11c41f77136b91b9e91c2dfe963b57e99d`。

生成完成后的独立 source-only CPU 评分：八条既有正确 GCT 完整正文为 literal／whitespace／digits 全部 8／8；SL 十一 physical row parts 的描述、编号、四单位 span 与空框 controls 均 11／11，三条 remarks 3／3。53 cells／18 空框仍只是结构观察，非精确完整 cell gold。原完整 parser 的 condition 1／3、两个页脚共四项残差保留；本轮不重新跑 parser，不用 recognition 相同追认布局已修好。**三步 LoRA 在这个完整同输入开发池没有观察到 recognition 收益**，不代表全部数据／步数都无法训练。历史暴露、humanConfirmed=false、source-disjoint train/dev/blind families=0 的缺口继续保留；不把 merged 输出当 gold，也不为凑训练轮数重复强化已正确的文字。

`evaluate.py` 对完整 OCR 逐条输出原件 exact、只忽略空白 exact、字符编辑距离、数字序列和截断终态，保留全部 sample 分母。语义、bbox、cell/span、跨页 adoption 单独核验。retrieval 输入必须固定全部 candidate pool ID/digest；embedding 与 rerank 在同一池分别算 recall/MRR/nDCG，不把不同 candidate pool 的差异叫 rerank 改善。完整相关 corpus 分母必须 source-only 独立标注，不能只给返回正例计 recall。

官方业务入口复用 `../vetting_eval/official_business_capture.py`。赛方 8 个 Advisory 题 ID 为 1.1–1.6、2.1–2.2。必须先冻结 source/version/parser/config/index，生成只用 question-only 文本；2d comments/countercheck 和 2e 答案由独立评分阶段读取。collector 保存首次 HTTP failure，不自动补传／adopt／rebuild／重试或追认一次失败为通过。当前 G305 开发题和单文件报告不等于官方 8 题／Vetting 成绩。

下一轮 gold 缺口、14 原件／23 暴露选页清单、真实 source-family 隔离方案及最小精度／训练／检索消融见 [质量工作交接](./QUALITY-PLAN-20261010.md)。同源未选页只能作内部诊断，尚无已确认的独立质量 train／dev／blind test 家族。该文不登记未核原件标签，不把当前模型输出、官方答案或结构库存自动转换成 gold。

下一轮已从原件手工全文和独立source-only核对，闭合Appendix H Part C物理28–31四完整table任务：44实际cells／16空body报价cell／20文字项目，header含Description rowspan2与OfferedMaterial colspan4；body每页五个高cell，没有逐item横分隔。独立110检查通过，target保存原大小写、标点、视觉单位上标²及原行顺序，空报价仍业务unknown。正式compatibility-only manifest SHA为68e0c6ae4147e5ebdc6025bc8f5ced15ba8fd4f37c8f77a54c293c1b75546514，4samples／3169目标UTF8bytes／1sourcefamily，全train／humanConfirmed0／blind0。dataset验证、15契约和真实产品encoder默认CPU预检通过；后者4.723秒、0steps、未加载模型。表外文档身份／Part标题／footer未列入target，不称全页gold。

CPU同时预检旧150dpi兼容crop及同PDFium／form设置／同原PDF点框fresh150/300共12版本。预算helper的read_text把目标CRLF投影成LF，最大input＋LF target＋EOS为2232／8192；独立read_bytes实际raw CRLF目标最大2240／8192，均完整不截断。dataset读字节保留目标，不能混用两个预算。默认训练task prompt另缺官方开头LF，同图相差1输入token；八baseline均为官方`\nTable Recognition:`且不含目标，因此DPI对照不受影响。下一次训练须显式UTF8 LF目标和官方prompt的新冻结协议，不覆盖旧targets／manifest；目前产品训练代码未改。

八完整表格baseline已在lease `oer-training-20261010-h28-dpi-control-04` 下实际完成，计划SHA为2c0b4a8c1062e81d262cde25b42b3af838a35adbfffa27f66ce9150a019f4bff，run receipt SHA为8bad6196913e7e0fd8fdd4873d28d0d468c74fa77e509154af20a5478aace3d8；target仅后评分、不传入请求。base CUDA BF16／SDPA，8stop／0length；输入14655／生成2131tokens，总85.693秒／调用累计75.980秒。allocator峰allocated3016270336／reserved5316280320bytes，整卡137样本峰13282MiB包含保护服务。仅owned18106／PID74536关闭，lease释放，退出后7989used／8181freeMiB；本轮0训练、0线上切换。独立实际214检查0失败，初次tokenizer路径假设失败保留。

完整source-scoring-v2两DPI相同：20设备编号、22单位和16空Offered区域都保留；原字面仅忽略空白9／20items，严格单位LaTex encoding归一14／20，再单列working字段单位读序指标20／20。后两项不称literal exact。原物理topology0／4：原件每表11cells，模型分别66／51／90／108cells，两DPI拓扑完全同；p30/31Description单位子列未进入Offered四业务列。先用原件native ruling／坐标研究canonical结构，不因序列化／逻辑拆行继续训练文字。第一评分器rowspan占列／LaTex wrapper错误的脚本和评分原样保留，v2只修CPU评分、不改原推理／gold；独立直接OTSL grid核验226项0失败，全部source 44cells／20items指标重算一致。Annex完整EXP-01(Building)已核为27–29三页，42/54/44 nativecells仍是库存、完整targets0；两个原件同一20250101项目家族，不能因SHA不同或旋转派生制造独立split。完整指标、协议缺口和证据入口见上述质量交接文档。

随后完整GCT横向crop单因素17请求已实际完成：4残差任务×0／1／2／3 detected line heights，再加原正确detached3 control；固定200DPI、baseCUDA BF16／SDPA、官方prompt与参数，按邻区2pixel guard防侧注混入。manifest SHA b43eaa81e7b3f87727a2d030268ce490099f5ee36145b0eeee41a2cd6d3a79e2，17完整CPU预算最大201／8192，无截断／0模型。Root lease `oer-training-20261010-gct-horizontal-padding-05`下17stop／0length，12controls通过；输入1924／生成225tokens，总18.257秒／调用7.709秒，allocated2388142592／reserved2772434944bytes，整卡29samples峰10763MiB含保护进程。owned18107／PID93364已退出、端口关闭、lease释放，0training／0online mutation。run SHA e2324823fa689ff01ce9c138bdc90c0cc4fb8a42c1c478fd9d44e39113429a4c。

source严格逐字：4baseline完整region literal0／4，factor3 literal4／4；正文1／3全marker入图后正确，两个footer的slash本来已识别、外侧双dash factor1入图后逐字正确。condition3 factor2只有partial3而被读作6，raw数字失败保留；绝不通过归一吞编号／横线。4baseline＋detached3共5完整IDs与base77同。首次CPU scorer旧run schema假设失败保留，修复只读取已有逐request base evidence。独立实际回读524项／0失败，receipt SHA为5fcc38bf7bdb426c9acbe1e1915b0d9069ec2d9404cd31c876c460326acfd166；不追认整个legal clause、asideownership、globalorder、其他来源或production adoption。完整算法、coverage／负例与业务接回边界见质量交接。

官方MinerU helper的源图crop发生于recognition前，而`_apply_block_content_and_line_metadata`在recognition之后构造lines。因此上述17请求采用saved post-OCR line高度的开发成绩不能直接进入生产crop；白底padding／resize也不能恢复框外字符。新隔离candidate只用识别前原图pixels和type／bbox／angle，以row ink projection估计行高，横向factor3、纵向不变、2pixel邻区guard；aside／table／image／equation／旋转保持vendor crop，稀疏／异质／重叠／扩展边界有墨迹时保留unknown，不读content／lines／filename／page／gold，不改原layout bbox。此新recipe与17post-line实验分开验收。

新CPU准备完整GCT两页＋SL原3436–3438三页saved geometry pool，原77完整inputs全部pixel exact复现；candidate改44／保留33，三完整SL table inputs不变。冻结manifest SHA为3132041790f48fd5bbd89f16949f9e6cf4f8d841fbf9baf13e051059c7d34984。独立pixel1395项／processor699项各0失败；154次原／candidate完整processor输入预检最大2041tokens、最少6151输出空间，无target／weights／CUDA。owned273项安全probes及169项完整helper observer CPU演练通过。独审发现reading初版只检查横向coverage，actual-policy synthetic半个垂直marker可能误隐藏，原FAIL和旧代码保留；v2最小补全x/y四边及有效有限bbox，独立250项0失败。raw aside不删、完整exact唯一marker才隐藏reading，unknown保留两份。CPU replay不是fresh parser／模型质量结果。

fresh official完整parser stock／candidate各GCT2＋SL3计划冻结08，SHA为90729a22dcb5a15a8d13464442b7968438de2ec5589cb7d9597ec4e6f50d2978；执行前default runner完成CPU身份验证，独立50项／0失败在GPU sample／subprocess／network traps下确认没有GPU调用或进程启动，receipt SHA为91ecd3d16ce57a3406cda0af012f4b22b77c89dc68d3627f519a91e62019a01c。计划保护全77实际inputs／recipe、实际processor grid／tensor／token／prompt与CPU证明、33原图不改请求的全IDs、三整表rawOTSL、既有正确source文字及strict4完整region；全GCT24regions和SL11parts／3remarks另作source-only开发诊断。准确installed vendor helper SHA在GPU前拒漂移，数值endpoint只有模型名／recipe两个身份标签更改。此前06／07均未执行，因reading修复及准确source drift guard已由08取代，不覆盖旧manifest。隔离observer初次ledger父目录缺失的CPU失败保留；只补mkdir后03演练全77通过。0线上采用／0新增训练。证据在 `.local/state/oer-training-quality-20261010/fullparser-pixel-crop-candidate-prepared-06/` 及 `fresh-fullparser-pixel-crop-execution-prepared-08/`。

随后08已获Root精确lease并真实完成四次完整parser：stock GCT38＋SL39，candidate GCT38＋SL39，共154stop／0length、四worker exit0，实际run receipt SHA为c75a21f661310374e6720b0760ca9337e32fa6b9b5747b0abbb4d1ceff3073f7。主模型CUDA BF16／SDPA，原版与candidate使用同forms／官方ONNX辅助CPU配方，仍不是H800 full/torch验收。实际input28408／output4396tokens，模型请求累计143.344秒，全作业186.672秒；allocated峰3017497600／reserved3955228672bytes，整卡296样本峰12310MiB含保护resident。只关owned18108／PID56172，OS回读PID不存在／listener0，lease释放；退出单独sample8335used／7835freeMiB。完整requests／images／processor／IDs／rawOTSL／vendor／forms证据全部保留。

source-scoring-v1 summary SHA为e9800f8a7ac712e26a6ccc8c7bf0e52cacd6f24ff9db0e0169af12eb29c50684：全77输入／配方／actual processor控制77／77通过，33未改图完整IDs33／33相同，3整表rawOTSL及IDs3／3相同；四完整intro／footer原件严格literal4／4。GCT24regions只忽略空白为stock20／24→candidate23／24，SL11parts描述／编号／span／controls均保留，3remarks保留。唯一GCT剩余差异为condition3长段两处U+2019弯撇号变U+0027普通撇号；另声明smart／ASCII quote＋空白等价为24／24，不能称literal。源reference与raw都无U+FFFD；临时Windows console编码导致显示误判的解释已撤回并持久化原UTF8/codepoints证明（SHA59e84448d3e08028f604a69028ee174a8069aee3f7dd65728c7ae03eaf16f87b），没有写新gold／target或更改旧score，不为该表示差异训练。

08实际reading原projection安全保留unknown：准确vendor normalization无条件pop angle及merge_prev，pre0与raw缺字段导致整页匹配失败。新owned after-run CPU adapter只对完整pre页angles为None／0且merges全false使用已证省略等价；旋转、true merge、坏/漂移/多义geometry继续unknown。24项CPU回读通过，唯一GCT3重复marker仅在reading隐藏，raw aside及正文完整保留；原08代码、raw、unknown projection与评分未改，0新neural。after-run receipt SHA357b4698b7ea44548e90e93b6dc6311cd6047ab711d0f6561afafdbd311e696d。该阶段当时待独立回读；最新独审与后续候选见下段，production/canonical adoption、全法律ownership、其他来源泛化及blind仍未验收。

2026-10-11 后续独审已经完成：Root 对 fresh08 完整实际结果回读 2,278 项／0 失败，receipt `.local/state/oer-training-quality-20261010/root-fresh08-independent-readback-01/receipt.json` SHA `e21eb4c6f6b8f50ea812f46f9b2ae93c1e69e33cf9f2e3c415e0b056ab2ffde5`。Root 对 reading-v2 的实际 raw 38／39 保全、唯一 GCT marker 及旋转／merge／漂移／多义／错 marker 守卫回读 31 项／0 失败，receipt `.local/state/oer-training-quality-20261010/root-reading-v2-independent-02/receipt.json` SHA `0520412f36012a9186cc8a6c3ad8fe1bd290e06d6d4e4e50533f46d4b7e34d04`。这些证明实验和 reading 守卫；不自动采用在线 parser。

新 CPU canonical 准备在 `fresh-gct-canonical-cpu-prepared-09/run-01/`，182 项／0 失败，receipt SHA `84ab87d9b193d973fec0d5bf7d2a69123332124a37883f5daf1b148942c40138`。原 GCT 38 raw blocks 全保留，17 个真实 geometry policy 正负场景、24 source-only 评分 region 对应和 strict ws 23／24 保留。纯 fresh 候选因两个完整 header blocks 的真实 bbox overlap 被拒；不能改 bbox 或拿 reference 替换输出。

经明确来源复核选择，低能力 mixed 候选以同原件、此前已审的 whole Paddle header 替代两个 overlapping fresh headers，其余 35 fresh whole blocks 构成 20 完整单元（11 条款、4 侧注、1 whole header、2 footers、2 印刷页号），另 3 fresh raw blocks 原样未采用。`canonical-low-capability-mixed-close-06/receipt.json` 实际冷加载／重建 89 项／0 失败，SHA `68a15ba266640b79b85273a0a6dd06fbaf3f3673705250280c4d4d3e23cd62ab`；projection content SHA `28bf74b6e8be792175b6d34994a7cb51bb21b5f06a2d37b8f9452f1d9d3301fa`。每个 part 仍是实际 parser middle 的完整文字，raw model 独立保留；只有 1 个合法 Paddle header 原页资产，fresh 两整页 PNG 在 raw CAS 中保留但未链接。不是纯 fresh 自动 producer，也没有在线采用；此前依赖 pin、aggregation 身份断言、overlap、asset linkage 和验证签名失败各目录保留原字节。

原 raster mapping 的 owned v1 design 已收尾，`raster-mapping-design-07/receipt.json` 为 38 项／0 失败、33 个拒绝负例，SHA `788093ea463feb7a444babb8a5ace60a1c6ab28cc8745d609536aa4b504c7cea`。正例明确是 synthetic contract fixture，0 renderer／model／GPU；不代表原件图像已认证。fresh08 helper 只有 ordinal／RGB digest／geometry，forms receipt 只证明完整 source bytes 和初始化先于 page，缺少实际 render page-index、effective scale、recipe、returned pixels 的 join。保持 unknown，不能从 ordinal、默认 200 DPI 或同尺寸推断来源映射。以后须实际 capture 该事件，或另用 independently captured source-page renderer 的来源证据；不能把派生 PNG 改称 provider 的 PDF input。

质量工作已转到新的完整原件单元，不继续停在 schema 测试。`next-source-only-quality-prepared-10/run-03-body-pages/` 准备 SL 2701–2702／3960–3961、FoT 2–3、APZ 5–6 共 8 完整页，27 项／0 失败；再由 `annex5-source-quality-prepared-12/run-01/` 准备 Annex 5 的原物理 33–34，两页 CPU form-aware 原图及完整 native objects／characters。现在共 10 候选页、4 原件容器、5 配对组，但仅两个历史暴露 parent union（SL 与 20250101 project）。APZ 标签在 `source-label-review-04/source-label-review-v2.json` 新补充而不覆盖原 manifest：physical5 是 version2 cover＋空签署表，6 是目录；此六页提供原件不能证明目录所列的长篇正文能力。候选页不等于完整 gold 或新 blind。

两个完整 table source-only 候选已建立，均未读取模型输出或 2d／2e，待独立原图／literal／physical grid 复核，不能先训练或采用。SL FEN 原 physical3961 为 14×4 grid、35 物理 cells，40 nonempty native text objects 全归属、255 ruled paths 保留；literal 非空白 codepoints 35／35 对应，原 `1, 2 or. 3` 的点保留，前页 FEN.W250.9(3) 的 following-table 引用单独记账。receipt `sl-fen-source-gold-prepared-11/source-gold-candidate-02/receipt.json` 为 135 项／0 失败，SHA `720d9fd1d7857011b2d0c4529884fe648cef5b386cf3c86e0db1408fa63f4bcc`；完整 source OTSL 为 715 bytes，SHA `fbdd918668b5b1b2d8adab56b1eb772b529b888e662ecae86f2f8c02199762af`。

2b Annex 5 原 physical34 的 Envelopes and Copies table 为 8×3 grid、16 physical cells，36 nonempty source objects 全归属；rowspan4／2、header／blank0／copies 与 envelope identifiers 分别记录。原 `Session of Submission`、弯撇号、em dash 及 `1 original +1 PDF` 保留，physical33 是 Notes1–4 邻页而不是 table 跨页续行。receipt `annex5-source-quality-prepared-12/source-table-gold-candidate-02/receipt.json` 为 65 项／0 失败，SHA `e40fc10f190ed673fdcbc8c4acb342905e6e0fa704a402a527f5e205d76d0194`；完整 source OTSL 为 687 bytes，SHA `582ecc7e5b7aa49457e608974908f387f3524c2100d0e53c079114cf6c4d22e9`。两者是 whole-table target，明确排除表外／邻页 prose，不冒称完整 page gold；native rule coords 也不冒称 independently pixel-certified。当前 humanConfirmed=false、trainingEligible=false、真实独立 train／dev／blind family 仍 0／0／0，同 source／template／edition／project 与近重复必须 union 后分组。新的 GPU fullparser／训练窗口和在线 adoption 由主任务单独安排，Drafting harness 继续冻结。

两份新 whole-table 的独立 source-only 核验随后已闭合，当前不再缺这两份表的独立原件复核：`base-control-independent-cpu-audit-03/fen-annex-source-gold-independent-02/receipt.json` 为 1,048 项／0 失败，SHA `1890c01940a574da000c190eb131fff9fee7334591511590cc5d8f344060f860`。独审实际查看四完整页原图并直接回读原 PDF，完整 objects／chars／text／frame 与 native capture 逐字段相同；51 cells、76 nonempty text objects 唯一归属、35／16 physical grid／spans、数字／原符号、每 cell 四边的原 ink 全覆盖及长 rule 中心、全部 OTSL bytes 通过。FEN255 与 Annex97 是各 table 所在完整原页的 path 对象数，不称都是 table 边线。原目标各仅末尾 LF1、CR0／无 BOM，row 用 `<nl>` token，visual in-cell newline 按声明表示为空格。

首轮独审 `fen-annex-source-gold-independent-01/receipt.json` 的 842 检查／28 失败（SHA `12a4b8cc4045afe0aee5b831af032922f2af58f29fc0f645ef66cb98dd690a67`）及其脚本保留：26 项只用小 corner 矩形中心轴核边、2 项漏了实际 OTSL 末尾 LF 的重建假设。第二轮只纠正独立检查方法；候选／target／author receipt 均保持 SHA，没有修原拼词或补造 gold。审阅者曾误解释为“每 row LF”，准确 bytes 已在新收据纠正。1,048 不与首轮 842 累加冒称终态分母。独审0新 renderer／model／GPU／服务／DB／主代码动作；该验收是完整 source-only table 参考的字面与 native 物理结构，仍不升级 human、blind、独立 family、真实 pixel mapping 或 trainingEligible。下一步是新的完整模型 recognition／parser baseline，以这些表为生成后评分 reference，不能把 target 传入请求。


2026-10-11 后续 CPU 准备已冻结新的完整 stock parser 作业：SL 完整物理页3960–3961（前条款与整表）、Annex5完整物理页33–34（Notes与整表）。输入采用准确pypdf6.19.0的完整选页recipe，原件SHA／页对象payload／资源／表单／页frame保持，warm与cold CAS重放均通过；selected input是原件派生输入，不重标为完整原件。`.local/state/oer-training-quality-20261010/fen-annex-stock-fullparser-prepared-13/execution-manifest-v2.json` SHA `d96252451e2aa30a171635df04b078a8ebff00ae5bb9dcd1d26e5ee2f140d44f`，13 owned source文件、149 runtime文件分别固化。拟独立port18113与lease `oer-training-20261011-fen-annex-stock-fullparser-13`；CPU默认入口为`run_fen_annex_stock_fullparser_v2.py --manifest-sha256 <该SHA>`。只有Root独审后给出绑定此SHA的直接GPU授权，才可执行。当前没有新完整parser输出或GPU成绩；训练、线上服务／索引adoption、Drafting变量识别均未变更。

新renderer证据来自真实执行边界：spawn child实际`get_page(index)`与`_render_page_bitmap`调用记录actual physical mapping、DPI200、max-edge3500、effective scale／原生frame／bitmap原bytes和RGB；父进程在官方decode前读实际协议返回bitmap，decode后唯一精确RGB与同一PIL object对应到helper。helper只调用一次原版prepare，记录官方返回四数组等长及任务分母、实际RGB转换与`Image.crop`裁框／裁图像素、旋转／mask／resize后完整PNG、prompt和sampling。四个完整选页的实际返回RGB与从原件对应物理页按同配方独立渲染的RGB逐字节相同。全页／原表bbox的数学覆盖和semantic/legal归属仍分开评价；不使用helper ordinal、文件名或same dimensions推断物理页，不为fresh08补造映射。

`cpu-real-render-lineage-05-actual-layout-preparation/receipt.json`为56checks／0fail，SHA `deb9bc09757f438038fff99db68e0597c1bd95b7606d75cfc17a20bf2977ecb6`；这是实际4页渲染、官方layout准备和helper接缝演练，recognition的全页text几何明确为synthetic，0layout模型／神经模型／endpoint／GPU。`cpu-contract-negative-probes-03-layout-recognition/receipt.json`为29checks／0fail，SHA `b72ec132d22e02eb0345a9954accce428be3ec93de872df3847414af712859bd`；含严格OTSL格／span／blank拒绝、四数组尾项守卫、真实worker gold open拒绝终态，以及fixture layout＋recognition完整请求正向、extra／missing／duplicate ID／错PNG／ledger尾项负例。fixture响应没有reference文本，不算真实模型结果。作者03、04、Root独立01以及所有较早收据／源码保持原样，不累加这些分母。

完整endpoint accounting现在分别精确匹配实际layout与recognition完整PNG SHA＋官方prompt，最后核两类之和与全部endpoint请求相等，多余或未归属请求失败。旧fresh08实际GCT38、SL39次调用全部是recognition，0VLM layout prompt：standard/ocr的准确路线用ONNX小模型layout，high VLM接已给blocks。一般`layout_detect`／`aio_layout_detect`可能产生单独请求，新observer仍覆盖这个入口；不把可能路线追认为本配方已有调用。新的任务数量须由新布局产生，尚未预造分母。

两份独审整表只放在独立`post-generation-scoring-manifest-v2.json`（SHA `9cb4af8bd74b9c68e77bbaed36d24336ba8e7710e6938f6e1c1a9d47a26d0f6e`），scorer须等两次完整parser退出且owned endpoint终止才读。worker审计明确拒绝两gold目录和2d／2e；实际拒绝probe已保留最终失败receipt。初版`cpu-contract-negative-probes-01`因finally重复读被拒绝的路径，真实PermissionError已发生但terminal receipt缺失；该失败及原worker/v1计划保留，v3 worker只修缺失／拒绝来源的terminal核验，不将其追认为parser或OCR能力错误。v1计划c850被v2取代而未执行。模型输出仍不成为gold；humanConfirmed／blindHoldout／trainingEligible保持false、独立train/dev/blind family仍0/0/0。下一步先跑这两份新整表stock baseline并分格查看字面／数字／span／blank／裁图覆盖残差，不能先训练权重。


2026-10-11 独立准入复核随后将上述 d962/v2 四页计划判为 NOT_ADMITTED，不能执行GPU：`fen-annex-admission-independent-01/independent-admission-receipt-01.json` SHA `2c9de0a3ff7003dddacf98b6be288b777cd57c116f1feff4aebc87c720221085`。34项附加probe为18过／16失败，全部属于同一recognition lineage核验缺口在两来源的8字段变体；完整图像SHA／官方prompt／四数组分母与layout守卫原本通过。该状态不是OCR准确率失败，当前真实4页模型baseline仍未发生。

最小修复以全新`stock_fullparser_request_join_v2.py`实施：识别call的物理页、provider索引、fullInputPixelSha须等于唯一actual event；helper link的物理页、provider索引及detached fullRenderLineage完整值也须等于该event。layout守卫、renderer、实际crop／helper、worker、来源／模型／精度／预算均保持。`execution-manifest-v3.json` SHA `51bebfbb7f406d068ded6ddcbb0da107d103080584f00acd942e6637d093714a`固化14 owned code／149 runtime；`run_fen_annex_stock_fullparser_v3.py --manifest-sha256 <该SHA>`默认CPU完整pins与原件warm/cold重放已通过。新计划仍等待Root独立准入及绑定新SHA的直接GPU授权。

原独审34个正负例只调整versioned import／owned输出与UTF8-LF写入后，作者重放34／0（`author-independent-34-replay-01/independent-join-probes-receipt-01.json` SHA `61b0affbbcc76785c52f318c71d030b2fe362b732545915d973719e8ad78a338`）；这是作者重放独审用例，不冒称新的独立准入。真实四页CPU接缝新06为56／0（SHA `7e7f4bc8508d1b944d4b75255fdbe28abd880d16d8b168b8c816e28968d13cf9`）、版本化完整请求／数组／strict OTSL／真实gold拒绝负例新04为29／0（SHA `2a532c93fefd5f423ef3033bae8c40570bbd1252347dd978c415213dade0e01f`）；分母不累加。d962计划、原独审失败、旧代码与所有旧证据在v3冻结时逐SHA核对保持原样。0新模型／endpoint／GPU／main code／installed／live服务修改，后续仍应先观察两完整表的实际stock baseline残差，再决定调参或训练。


## 2026-10-11 实际 SL 两页成功、事后 wire join 失败及 Annex 单来源补跑准备

本节替代上一段「v3 尚待 Root 准入／没有模型输出」的当前状态，旧记录保留历史身份。Root 实际独审 v3 的 14 owned／149 runtime pins、warm/cold、56／34／29 CPU 复验后准入；独审收据 SHA `8a0b68e8b3279f98d148052c845c8818a3589ee84dd25b9448da6baf8504cf8e`，精确 GPU grant SHA `65b0d996bb7abcb998370ecdda1d1f1ccb86328df88b96d1e776cc5f4fa23327`。准确 v3 manifest 仍为 `51bebfbb7f406d068ded6ddcbb0da107d103080584f00acd942e6637d093714a`。

实际 tool session63204 已 exit1，不是观察等待超时。完整 SL 原物理3960–3961 worker 成功：45 recognition／0 VLM layout、45／45 finish_reason=stop，完整 model／middle、原始 OTSL、PNG／crop、renderer→PIL→helper、ONNX／forms证据已保留。worker43.625秒，45实际调用累计33.263121500378475秒、中位0.3955125999636948秒，input6296／output1025tokens；whole-card sampled peak8581MiB（含其他保护任务），不能把它称为本模型独占显存。成功 worker receipt `actual-stock-baseline-01/sl-fen-full-context-two-pages/receipt.json` SHA `1e49bead43c04153b4ecaa98b60c993b6e6f8d22966e297b79e14dbcc084c5dd`。

整作业在 SL 生成后 CPU request join 失败，Annex33–34尚未执行：官方 `http_client.build_request_body` 实际发送 exact system字符串 `You are a helpful assistant.` 加 user内容数组，旧 consumer把所有content假设为数组，引发 `TypeError: string indices must be integers, not 'str'`。原始 `actual-stock-baseline-01/run-receipt.json` SHA `3421d3ff9b2c2f3b9ee55adffbb3a7262bee191ddf240489b96ed5ee56b84ed6` 仍 failed、runs=[]，不得补写成功。owned endpoint18113／PID58236已正常终止，listener=0，保护服务未停；这次是事后验证代码缺陷，不是模型未完成45次生成，也不是表格准确率结论。

窄修保留为全新 `stock_fullparser_request_join_v3.py`（SHA `ebdbb866c4ea2c48f2dec42938f038199c9e73353bf0c5a6e54c54f65da94c71`）：仅接纳上述官方exact system字符串；其他string role／字符串变化／未知shape拒绝，recognition来源／页／pixel／完整lineage及layout守卫均保持。以45真实保存请求全量CPU重放，`actual-sl45-system-wire-cpu-close-01/receipt.json` 52checks／0fail，SHA `68c05f69140912d0a7c2852ed5602654e50de97c8e7a4889026383a0e1aa4713`。原独审34例的新consumer作者复验为34／0（SHA `fb2bef1541391c17f21959e54a005a2445c5b8cd7dcedc5564263be8825d1e18`），原29例以实际system:str＋user:list fixture复验29／0（SHA `49235eb766db81d5cc57fac5fd19ec758f3e5dd6ba7ec31c267ebeff9acf7978`）；不将三个分母累加为独立质量样本。

为取得两个完整表的baseline，已准备**只补Annex33–34完整两页**的 `execution-manifest-v4-annex-only.json`，SHA `9a65dbd1cd28318fb12b5d517e1adfd2918e80e72f39d4d3be49e17e4ea88045`，13owned code／149runtime。准确CPU入口为 `run_annex_stock_fullparser_v4.py --manifest-sha256 <该SHA>`，本次实际exit0；硬1source／2pages，Annex原件与派生完整页warm/cold重验sourceCount=1。新owned lease为 `oer-training-20261011-annex-stock-fullparser-14`、port18113、输出新目录 `actual-annex-stock-baseline-02`。旧SL不重跑：`retained-sl45-original-job-pins-01.json` SHA `be25e1ed64a8047b154a3fd3a25ed0b18391633ce29c0442e82e577e98256cc7` 固化原baseline全部208files及原manifest／failed／worker／grant；`saved_sl45_continuation_replay_cpu.py` 按原身份实际208逐SHA和45完整PNG＋prompt＋lineage join通过，receipt SHA `e66dd9545d943d73f4fa6f73590658fb69a81199ecebb9bcecda8c007c0db321`，此重验也在v4 CPU预检中执行。

v4当前仅准备，仍须Root独审和绑定新SHA的精确GPU授权，未启动新endpoint／未跑Annex／未读gold或评分。后续Root分别核两作业的准确generation manifest、完整worker、raw／requests及owned endpoint终止，再联合source-only评分；明确这是跨两次作业的已暴露开发baseline，旧SL `generatedByCurrentManifest=false`。不追认旧failed状态，不用单一成功receipt掩盖失败；humanConfirmed／blindHoldout／trainingEligible仍false，独立source family train／dev／blind为0／0／0。来源字面／数字／span／blank／裁图覆盖的准确率尚未评分，参数调整与必要训练须以该残差为依据。没有主代码／installed文件／线上服务／索引／模型权重变更，Drafting识别13文件保持冻结。上述本机证据路径均相对于忽略的 `.local/state/oer-training-quality-20261010/fen-annex-stock-fullparser-prepared-13/`，脚本在其父目录。


### 2026-10-11 Annex-only v4 已实际完成，两来源等待 Root 联合评分

Root 对 v4 精确 manifest `9a65dbd1cd28318fb12b5d517e1adfd2918e80e72f39d4d3be49e17e4ea88045` 独审后准入：13owned／149runtime逐SHA、Annex warm/cold1source、保留SL208files／45实际完整join、原34／29复验及实际system-shape负例均通过。独审 `root-annex-v4-admission-01/root-independent-admission-receipt.json` SHA `3eaa37eda35eba9f30365493c9b422e73e0f19f51336caf7699962716d2f7a32`，精确grant SHA `8d2f26db1ba74d8f1ec95f1462f7f8259c6046fe6918f09f2eeb4304931c4b09`。随后仅执行Annex物理33–34两完整页，不重跑SL、不读取gold。

实际session87514已exit0。`actual-annex-stock-baseline-02/run-receipt.json` 状态 `stock_complete_one_source_parser_finished_quality_unscored`，SHA `3e37de0e2ec0d02346a5f2195eda3063305eaa3ef518d3a0db1c66115433cb7c`；完整worker receipt SHA `bf3baa5e41717e950ddc9045b89f3a374cf6ba80b9f17b05a9359a73e6efdeda`。完整18recognition／0VLM layout，18exact PNG＋prompt＋page provenance joined、18／18finish_reason=stop；worker32.92200000002049秒，整作业43.188000000081956秒，实际调用累计23.292735599912703秒，input4380／output615tokens。原始OTSL、middle/model、完整渲染／crop／helper／请求、forms／ONNX证据均留存。

whole-card sampledpeak9407MiB（69samples）包括原有保护服务；本作业allocator peak allocated2927704576bytes／reserved3915382784bytes，不能用整卡峰值冒称模型独占。ownedendpointPID64344已退出，18113listener实际0；退出后GPU5439used／10731freeMiB，未停止其他保护服务。终态摘要 `annex-v4-terminal-summary-01/receipt.json` SHA `4801c09d04ce62049dbcf7941a0904b16eaf2b365836a80aad71eb01a3578335`。

这组Annex成功不改写此前v3失败：原SL仍归属于51be generation manifest，3421failed／1e49successful worker保持原SHA，v4 `retainedSlGeneratedByCurrentManifest=false`。两来源分别完成完整生成，现在由Root核准确双manifest／双worker／raw与request完整性／两ownedendpoint退出后开展联合source-only评分；本agent仍未读source gold、未评分、未训练／改权重或在线adoption。此为跨两次作业、历史暴露的开发baseline，独立family0／0／0、humanConfirmed／blindHoldout／trainingEligible=false保持；成功运行尚不表示表格字面／数字／span／blank／裁图覆盖质量通过，也不是H800 full/Torch性能证明。Drafting冻结、主代码、installed文件、线上服务及索引均未修改。


### 2026-10-11 两次作业的生成后 source-only 逐格评分

Root复核双worker成功、原generation manifest与owned endpoints均退出后授权CPU评分。新consumer `score_fen_annex_cross_two_jobs_v5.py`（SHA `ce3868e0ad3dfc0da86c23bd79553258df1b8e4d53478846e1ee5b43e45b39c8`）消费SL原51be manifest／3421 failed job／1e49 successful worker与Annex9a65／3e37 successful run／bf3 successful worker，各自身份分别记录。`cross-two-jobs-scoring-manifest-v5.json` SHA `945638f1d38851ab31c6b50b15240607d9f70dc5b8a6cd62a6683266433ace4b`；实际 `cross-two-jobs-source-scoring-02/receipt.json` SHA `250fd4024c9e3c8842d1504e49f9e19d960a1ac3ce669ed6e5efe6bb311e07e7`。旧v4 score因作者误用独审字段`failures`而KeyError（实际是exact schema的checksCount1048／failed0／status=pass），原failed score receipt SHA `8a3b724addf5dab14cd9cb01033e6de19bc520751638abfec658565538a28513` 和旧consumer／manifest均保留；只在新v5绑定正确独审schema，未改任何target／raw或generation receipt。

| 整表 | 原件完整分母／实际结构 | 字面／仅空白折叠 | 数字、span、blank | 明确文字残差 |
| --- | --- | --- | --- | --- |
| SL FEN physical3961 | 原与实际14×4／35physicalcells，缺格0、extra0 | 34／35 literal；34／35 whitespace-only | 35／35span、digit-token、numeric-token及blank状态；真正含数字格12／12 | `fen3961-r08-c1`原两行`Line wire`／`Mesh wire`按target声明成为`Line wire Mesh wire`，raw是`Line wireMesh wire`，词边界少一空格；rowspan3仍正确。 |
| Annex5 physical34 | 原与实际8×3／16physicalcells，缺格0、extra0 | 14／16 literal；14／16 whitespace-only | 16／16span、digit-token、numeric-token及blank状态；真正含数字格10／10 | `annex5-34-r1-c0`和`r2-c0`原U+2019弯撇变成ASCII撇；首格两处、次格一处。原Session、em dash、copies及identifiers均保留。 |

完整51格全部计分，literal48／51、whitespace-only48／51；数字51／51包含没有数字的格，实质数字分母另报22／22，不能把空digit序列当作22以外的独立数字测试。两表expectedBlankCells=0、actualBlankCells=0；51／51blank状态一致不代表已证明空白报价表能力。整表strict门保留**0／2**，不因3局部文字差异只涉及空格／撇号而放宽目标或归一化。没有缺／invalid／ambiguous表任务，下一步等待不同作者按实际raw逐格独审；作者评分不冒称新的独立质量验收。

几何上两表已核native rule-centre／完整cell参考box均在真实crop数学边界内，但下沿余量FEN约0.527825px、Annex约0.0000068px。该结果**不是完整border stroke／双外线像素覆盖证明**，independentPixelMappingCertified=false，完整ink边线及完整page／legal范围仍unknown／未验收。不能把这个2／2center-bounds指标写成「完整裁图质量通过」，也不能未经归因便把3文字残差归咎于漏裁。两原始table raw分别固化为SHA `4713084a666fbe4027e6fe416827aa4f0918f60ffd05ffb7d93e556937e4061a`和`493377899c71cdd63cc394bb6ee8588791d1e2dfae5eca76a0955125258bcdc6`，原模型文字不后修。FEN数字1–5与法律a–e的映射仍unknown。

新增真实source-scorer接缝的CPU synthetic负例8／0：每来源分别missing table／ambiguous table／invalid raw／单数字变异，完整35或16格分母保持；前三类全格失败、不排除分母，数字变异准确少一格，原generation输出不动。`cross-score-denominator-negative-probes-01/receipt.json` SHA `df7a63471fe74c1227fabd3d6795dd553af9136c82ee2c38b46889aa98fef221`，这是评分器契约验证、qualitySampleCount=0，不能与51格或独立样本相加。

当前只保留跨两次作业、已暴露开发baseline，humanConfirmed／blindHoldout／trainingEligible=false，独立family0／0／0。0新GPU／模型调用／SFT／权重变更、0线上adoption，不读2d／2e。暂不为这3局部文字残差启动训练或改gold；下一CPU工作优先调查Appendix H的物理拓扑0／4：native ruling／文本层的自动whole-table候选，保留MinerU原raw逻辑行并列，不能逐格拼接两个引擎、用手工gold作生产输出或写死SHA／页号／3×5形状。候选与误merge／断线／缺边／跨页／布局范围负例先在忽略目录验证，主代码及live不采纳。上述评分证据位于忽略的 `fen-annex-stock-fullparser-prepared-13/`，相对于 `.local/state/oer-training-quality-20261010/`。


### 2026-10-11 Appendix H 自动原生线框候选，仅 CPU 开发验证

Appendix H 的物理拓扑残差先作工程归因，没有启动新模型调用或训练。全新 source-only capture 按原 PDF 保全物理27–32页的实际填充／描边颜色、原生 path、TEXT fragments、逐字符位置及完整页上下文；算法只接收这些新鲜原件观测，不读手工 gold、模型输出、2d／2e，也不写死来源 SHA、页号或期望3×5形状。通用有界算法仅支持实际 opaque nonwhite、axis-aligned thin filled rectangles／joints；断边、部分内墙、未知图形、旋转、跨格文字和布局裁断保留 unknown，缺完整外框不能降成一个看似闭合的局部子表。

`appendix-h-native-table-generic-cpu-14/native-candidates-02-conservative/receipt.json` SHA `087a5401f0e42f219e79c173cc0752f46b8313d76d78af7f70b84d324d005729`：四页Part C各自动恢复3×5、11物理cells、4视觉空cells，格内nonwhite字符594／471／504／467全部唯一归属（总2036／2036、ambiguous0）。邻页Part B2物理27为3×2／5cells，Part D物理32为2×2／4cells，各自独立；没有自动跨页拼表。CPU正负契约17／0，SHA `3666004fed302dc0dace10269f63c338edb46339c1068a40f2b8be78f739cb03`，含无关2×2正向、缺外框／断线／误连／跨页／旋转／layout范围／空白及跨格字符／五种禁止I/O；synthetic质量样本数0，不累加到原件质量分母。

算法完成并冻结后，另一独立脚本才读取原8次150／300DPI MinerU结果。`native-mineru-parallel-evidence-01/receipt.json` SHA `31ed8d0238e765bcd75ccfe8ad4882fd2b6100a8cb6b46825df798d897527720` 完整保留原endpoint bytes、raw OTSL和全部token IDs；原logical grid分别14×5／66、11×5／51、16×6／90、19×6／108 origins，两DPI相同，不把这些模型origin cells冒称源物理cells。原生候选与完整raw分别保存，不逐格映射／拼接，不以gold生成生产表。旧run含gold descriptor元数据，但没有打开任何gold文件，native discovery输入未变。

六原页新鲜Poppler150DPI渲染和坐标overlay分别保留，receipt SHA `16ca2304bec18d7408e1410b11bba61c45f10b3fe427aa4a67f216e5bdcd83d5`；作者目视四Part C完整原图和六页contact支持高body cells／没有逐item横线，记录SHA `7aa982acd8e39364fd5a2ac02dce1bd1fafe9094c73fe9f203dd00de2fa31946`。Symbol／ArialUnicode显示字体warning原样保留；尚未独立pixel／逐glyph验收，不能报原生拓扑质量4／4通过。`observedNonWhitespaceGlyphSequence`只是逐字符保全视图，不是完整重构文字；原始空白、TEXT fragments及坐标均保留，读序／语义／空格业务意义／续表关系仍unknown。humanConfirmed／blind／trainingEligible／independentQualityAccepted／productionAdopted均false，独立family仍0／0／0；主代码、installed、线上模型／服务／索引不变。证据路径相对于忽略的 `.local/state/oer-training-quality-20261010/`，后续先由另一作者独审原件、候选和完整raw，必要调参／训练仍由具体残差决定。详细支持边界及证据入口见质量交接本节。


### 2026-10-11 H15：完整原生 cell 阅读候选及来源 producer 接缝

上一H14不同作者独审现已闭合有界physical structure：四表44cells／span及16视觉空格、2036非白glyph相符，终态receipt SHA `09611a266a9b9ccf0f16d702f4da3036d006b72e83f67a71bf58a47e1ff92f33`；完整cell文字未重构，literal仍null。其40／44非白诊断四body的22处差异仅原PDF U+0032、较小字号和raised baseline与gold声明U+00B2的约定，不改gold、全局替换²或启动SFT。

新H15只沿用已核六原页／H14 v2线框，读取原PDF补完整source文字样式，没有OCR／GPU／模型输出输入。style receipt SHA `2a7d57f9aa7c523c1b30283368781e3d0d4cf2598da52c046206dfa245e0680e`逐object／TEXT／matrix／bounds及全char/index/text/box与H14原件capture精确一致，新增source TEXT pointer→ordinal、原Unicode／font raw名称／字号／flag／origin／matrix／color／generated／hyphen／Unicode map状态等；全4340chars和548 PDFium-generated records都保留，generated controls明确区别于source TEXT对象字符。

通用reading v2选择每页全部完整且不相互重叠的whole-table candidates，不按first／页号／3×5或项目值选择；source／frame／capture／glyph／fragment／matrix／box／typed span／grid coverage和完整分母逐项核。char首先按实际TEXT对象与唯一完整cell几何归属；generated空白／CRLF只有连续source sequence两端的实际TEXT anchors同属一格、control几何也在该格内才归属，跨格／缺锚点／表外／未知状态不默认nearest。输出分别保留全原native observation／style、source extraction顺序的exact cell text、全部TEXT fragments、visual lines及未裁定control；不归一空白或原Unicode。raised smaller glyph由相邻源字符字号／基线／间距产生独立displayStyle候选，原`2`不替换为`²`，superscriptSemanticAccepted仍false。

实际`appendix-h-native-reading-cpu-15/reading-candidates-02/receipt.json` SHA `ceb5aef963be048188e5dd648d223d79096fca5df6161acd81e2d0cff2bd0dbc`：六页4340完整source records分为3585 cell归属、127 unknown controls、628表外；Part C四页2964完整records中2473归属、95unknown、396表外。这些是整页记录分区，不是表内错字数或质量分数。Part C保留44物理cells／16视觉空格、2036非白glyph；非白cell的原native顺序与geometry visual-line候选一致、display glyph unknown0，22raised-style原字符不变，仅作者诊断而非独立全文／读序通过。

来源／型别／control／boundary契约v1为28项／4失败：未直接核结构glyph字面／TEXT fragment及非法span／boolean列号；失败receipt SHA `f73f323e2baefb523fcee1f14876a65399b0461985b64cb2739eb93f546c451c`与v1代码／输出保留。新v2补严格源记录与完整grid型别守卫，same28全部通过，receipt SHA `a106f7e2cd30987acb71de70c64d237bb43f37a241fc61dd8d4974a0c4e449ab`，六实际page输出SHA与v1相同，未调分或改源。算法SHA `39d29ce5b1cc8720fedb5aa5955c844b325b4c7d04a363a4c5f7803246e65fd6`、runner SHA `fab0284efe5ee042fb287a37b91096017977ab79845c6741800b43b86972f11e`。测试包括真实全record保全、错source/page/tail/codepoint/matrix/literal/span/type、cross-cell/zero-area/unknown flags、overlap whole table、非水平／Unicode map unknown和I/O拒绝，qualitySampleCount=0。

仍待不同作者验证完整字面、词间空白、CRLF/显示行、source controls及style／通用边界，literalAccepted／readingOrderIndependentlyAccepted／productionAdopted=false。原编码与raised显示关系分别保留，不将PDFium生成换行冒称PDF内容流中的编码换行；法律Part归属、续表、空格业务含义仍unknown，未知／旋转／不可证明的源样式保留原记录。0GPU／neural／training／live／main／DB／index修改；不读gold、旧模型输出或2d／2e作为H15输入。证据相对忽略的 `.local/state/oer-training-quality-20261010/`；完整细则与实际逐页ledger见质量交接。


### 2026-10-11 H15 v3：不同作者红例后的原生整数契约窄修

H15 v2 不同作者终态 `496593f7435907d74064cbde2a6adab4b2cb5eaac78073bac1cf0da093c02725` 确认原件/source 保全，但自写 producer seam 22 例仅14达预期、8项把bool／非正页号当作原生整数接受。新 v3 只加非bool整数、正物理页、非负唯一component ordinal及同源对象ordinal／指针／index／数量类型守卫；原空白归属、字序、Unicode、样式与whole-table选择规则不改。旧v2、失败、raw／gold及冻结原件全保留；未改主代码或在线服务。

作者重放原不同作者确切22个冻结fixtures：22／22、输入SHA保持，receipt SHA `fa37b16529afd23360f80c13cc97234a586cd81147c49643923605216c8d6996`；原作者28契约用原脚本同配方重跑28／28，receipt SHA `4645212c8e9330eaab87d46769ef4e3bcd856b77918b22832ce5dc9b23d429ec`。这是作者复跑契约，qualitySampleCount=0，不称新的独审或模型精度。六原页v3完整输出与v2逐bytes／SHA完全相同，204项旧原件／代码／证据重核一致，证明receipt SHA `346982fcf2f635148b1c8b9f4aba43f76b641fa5b0a983c9a097d8f878851af0`。六页仍4340=3585归属＋127unknown＋628表外；53physical cells／18blank、19行内空白定位unknown及24 TEXT/extraction不同通道保持，完整语义／法律读序和全字面质量仍未接受。

证据在忽略的 `.local/state/oer-training-quality-20261010/appendix-h-native-reading-cpu-15/h15-v3-typed-source-01/`，新scripts在父目录。两份质量文档此次append前完整实际bytes保存于该目录 `retained-current-docs-before-v3-append-01/`，不重构旧历史或追称历史doc SHA仍当前。0新GPU／OCR／model／HTTP／训练／main／installed-runtime／DB／index写，Drafting冻结；humanConfirmed／blindHoldout／trainingEligible／productionAdopted=false，独立train/dev/blind families=0／0／0。下一步交不同作者核v3 typed边界，由Root决定采纳；不得把机械一致升级为全文score通过。
