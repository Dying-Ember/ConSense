# DOCX 派生 PDF 双视觉解析与 POI 对照实测

日期：2026-10-09，Asia/Shanghai。实验根目录：ConSense 根目录 `.local/state/docx-triple-validation-20261009/`。原件、权重和模型输出留在忽略的本机目录；本文记录实际覆盖、证据位置和采用限制。

## 结论

用户要求的“DOCX 打印为 PDF，再双路验证并结合 POI”已完成首轮真实解析：6 份原生 DOCX 的全量 POI 输出，与 11 个完整派生页的 PaddleOCR-VL 1.6／MinerU 4.0.10 Standard 输出进行了关键内容及结构对照。视觉模型只接收同源完整页图，POI 输出和参考检查项不进入模型输入。

这轮支持继续以 POI／OOXML 保存原生文字、编号、cell 与编辑状态，以渲染视觉保存版面／图像位置、补查嵌入资产，并保留两路差异。三路不能多数表决：EPS-F4 标题里被划掉的 RSE 被两套视觉输出共同保留成有效文字，当前 POI 却保存了局部 `strikeText=RSE`。视觉一致不能覆盖这一原生编辑状态。

该结果是已暴露开发样本的关键点对照，尚不是全部 DOCX 全文、全 cell／bbox 或新来源泛化通过。独立单页解析也不证明自动跨页合并已完成。线上 Java／OER profile 本轮未切换。

## 输入与原件身份

先复核此前 23 个 DOCX 和同源派生 PDF、39 个代表 PNG；本轮复用哈希一致的打印件，避免重新打印引入分页变化。从指定派生 PDF 用 bundled Poppler 重渲染 11 张 300 DPI 完整页。两套 parser 读取相同 PNG；各自内部缩放、布局裁块和 OCR 后处理仍属于各自配方，不能称模型实际看到了未缩放的全尺寸张量。

派生 renderer 为便携 LibreOffice 26.2.6.3。本文所有页码是该 renderer 的 PDF 物理页，不是 Word 原件权威分页。原件引用继续使用 source SHA 和稳定原生路径；当前 POI `pageNo=null`，没有伪造原生页码。

| 来源 | 派生页 | 检查重点 |
| --- | --- | --- |
| 03 - NTT.docx | 3 | 无边框条款表、(a)–(g)、Bill 正文引用 |
| 05 - SCT.docx | 48 | A／B 栏、31 months 与待填空值 |
| 10 - SCC.docx | 5 | 正文父子编号、定义与目录身份 |
| 05.1(2) - Marking Scheme (assessment std).docx | 1–2 | 四个主列、嵌套评分表、续页和公式 |
| 13 - Appendix G302 - insurance.docx | 27 | 原生 tabs 表单、Employer 与 Premium |
| 25 - Specification - PRE (base tender).docx | 139–140、149、524–525 | 两页 EPS-F4、局部 strike、22 ticks、注释、示例身份与图表 |

来源为 2b 实际项目材料；没有把 2a NTT／SCT／SCC 标准模板加入 Vetting／Advisory。NTT 里的 BQ／SOR／Bill 正文引用按已确认规则保留，本试验没有扩展到已排除的计价文件或项目工程技术 PSS。

证据：`sample-manifest.json`、`parser-inputs.json`、`identity-verification-receipt.json`、`identity-verification-after-all-model-runs.json`。最后一份在两个模型进程结束后重新核查 23 个 DOCX／23 个 PDF、39 个旧 PNG、11 个新 PNG、6 个 POI 输出，全部哈希一致。身份一致与质量接受分别报告。

## 三路真实执行

POI 使用当前业务源码在独立 Java helper 实际解析 6 份全量 DOCX，无在线 Spring／OCR／DB 调用。编译两次工程失败及第三次成功均保留。

| POI 原件 | 全量 blocks | Parser 操作状态 |
| --- | ---: | --- |
| NTT | 73 | PARSED |
| SCT | 355 | PARTIAL |
| SCC | 793 | PARSED |
| Assessment | 78 | PARTIAL |
| G302 | 272 | PARTIAL |
| PRE | 10,531 | PARTIAL |

`PARTIAL` 来自未解决的编号／符号／分支等状态，不等于整份失败；`PARSED` 也不表示内容质量通过。完整原生块和警告见 `poi-export-receipt.json`、`poi-native-review.json`、`poi/`、`poi-probes/`。

两套视觉 parser 串行使用 RTX A4000 16 GB，没有同时加载。既有 CPU 环境保留，GPU 环境分别位于 `.local/oer/venvs/paddle-gpu` 和 `mineru-gpu`。

| 路线 | 实际配方 | 本轮终态 | 整轮秒数 |
| --- | --- | --- | ---: |
| Paddle | PaddleGPU 3.3.1 cu129／cuDNN 9.9；PaddleOCR-VL 1.6＋PP-DocLayoutV3；GPU FP32；max_new_tokens 8192 | 11 页识别完成，0 parser 失败／OOM；11 个可选 pickle 错误，保留 `completed_with_errors`／exit 1 | 147.292 |
| MinerU | 4.0.10 Standard；ONNX small backend CPU；固定 raw VLM CUDA FP32／eager／TF32 off；输入＋生成实验总预算 8192 | run1 缺 psutil 初始化失败；补依赖后的 run2 11 页完成，155 个实际 VLM 调用全部 stop，exit 0 | 556.266 |

Paddle 权重固定为 `PaddlePaddle/PaddleOCR-VL-1.6@c5630abae1d940eafe0697512a0325494b02ab42`、`PaddlePaddle/PP-DocLayoutV3@241f8bdfc77a7c7bee915a5057aaee58c235a8d3`。实际 VLM 参数张量均为 `paddle.float32`／`Place(gpu:0)`。MinerU raw VLM 为 `opendatalab/MinerU2.5-Pro-2605-1.2B@08aaea840498d49ce16247b6263196cf02814885`，Torch 2.11.0+cu128／Transformers 4.57.6。该 Torch 与此前 CPU 环境不同，不能把旧 CPU／新 GPU 差异归因于设备一项。

MinerU 以前台单进程执行，LocalClient 直接调用本地模型，无 HTTP listener 或实际网络 transport。原拟新增的 GPU 后台 adapter 启动被自动审批以 `blocked by policy` 拒绝，命令未执行；随后采用无监听前台方式完成试验，没有重试后台启动动作。

## 关键对照发现

以下是局部已核对事实，接受范围不能扩大到整份原件。三路完整表格／跨页组的采用仍遵循完整组规则，不逐格拼接。

| 检查 | POI／原生证据 | Paddle 本轮 | MinerU 本轮 | 采用限制 |
| --- | --- | --- | --- | --- |
| NTT (a)–(g) 与 Bill 9／10／11 | 原生 `body/53/table-row/2` 保留条款文字 | 保留七子款及 Bill 正文引用 | 保留七子款及 Bill 正文引用 | 当前 POI 未输出逐子款父边／bbox；无边框 XML table 不应按普通数据表解释 |
| SCT A／B | `body/585`：A=31 months，B=点线空栏＋单位 | A／B 归属正确 | A／B 归属正确 | 这是未填表单；不能把 A 值自动填成 B 值 |
| SCC1 父子标题 | 正文 heading style／native paths 与 TOC 可区分 | 保存 SCC1 父标题、1.301／1.302 | 丢失 SCC1 父标题，子条保留；独立审阅确认 parent 未进入 model_output | 视觉检测遗漏；原生层级不能依赖这一缺失的视觉父节点 |
| Assessment p1 | 四主列、C.1／C.1.1 及对应文字保留 | 关键分数、25 storeys／100m、A3 4 pages／Annex 4 保留 | 相同关键点保留 | 仅关键点接受，全文字符保真另测 |
| Assessment p2 | 原件在 Assessment Standards cell 内有四列嵌套评分表，随后 Steps 1–4；当前 POI 压平嵌套结构 | HTML 压平且漏 Steps／公式 | 保留 Steps、6×5、0.35%×Pt/Pm；嵌套表仍压成外表列 | 完整表组拓扑尚不接受；当前 POI 补原生嵌套树，视觉用于定位／复核 |
| G302 tabs 表单 | 原件无 XML table；当前 POI 保存文字却把 tabs 转空格；独立 OOXML 映射保留 tabs／tabstops | 完整 bbox 内输出遗漏 Employer、Testing Period 限定和 General Condition 5.6 | 保留 Employer 正文、各空栏、Testing Period 限定和 Premium／5.6 | 本样本的视觉字段关联 MinerU 更完整；不能泛化为所有表单自动优选 |
| EPS-F4 title RSE strike | `body/2227/table-row/2` 单列 `strikeText=RSE`，其他正文 RSE 仍有效 | 划线 RSE 当普通字保留 | 同样当普通字保留 | 两路一致仍错误；原生局部编辑状态应独立保留／门控 |
| EPS-F4 Case 2／3 选择连接词 | `body/2225` 对应 POI block2436 保留 (i) 后独立 or、再 (ii) | final blocks／公开 JSON 没有独立 or | 所有20个相关 model blocks／responses没有独立 or；两条分支 crop 之间漏出该位置 | 两路共同遗漏；保留分支标签不等于选择关系完整，不能凭顺序补法律关系 |
| EPS-F4 双列签名区 | cell 内容与原生布局可查 | 双列被混读，部分签名标签遗漏 | 同样有混读及 Signature of Authorized 遗漏 | 不能把线性 OCR 文字直接当正确 field→value 关系 |
| HOMES 22 ticks | 11 功能行×两栏，共22个 Wingdings F0FC，当前全部 resolved 为 ✓ | 22 个勾号与两栏正确 | 22 个勾号与两栏正确 | Legend 勾号单列，不纳入功能栏分母；行 iv 子项关联仍需精细 bbox |
| HOMES notes | 原件 note groups 为 *, **, ***, **** | 底部 *** 识成 ****、**** 识成 *****；含义关联受影响 | 1／2／3／4 星号及限制文字保留 | Paddle 完整表组不能按“勾号正确”接受；星号属于业务关系证据 |
| PRE sample p524 | `for illustration only` 在正文，日期／进度为样例 | 保留示例说明及关键五列表值 | 保留示例说明及关键五列表值 | 不把样例数据变成项目事实；自动内容角色传播尚未接入 |
| PRE sample p525 | 表是 EMF `word/media/image28.emf`；chart 是 `word/charts/chart1.xml`；当前 POI 未输出完整资产内容 | 保存 summary table 及 chart 图像，chart 轴／legend 没有独立文字输出；chart crop 不含独立标题 | 保存 table 及 chart 图像，标题另存 caption，chart crop 不含原图标题 | 返回完整图组要绑定标题＋原图＋父条；单 crop 尚不足以交付带标题全图；遮黑数据保持未知 |

三套原始来源的具体路径／JSON pointer 和 OOXML 证据见 `poi-reference-mapping.json/md`。独立视觉审阅分别记录在 `paddle-independent-review.json/md` 和 `mineru-independent-review.json/md`，不能将审阅中局部 `pass` 写成整页／全文质量通过。

## 参考检查与证据保留限制

31 个关键检查项在模型运行前制作；`reference-checks.json` 的创建／最后修改 UTC13:00:04，Paddle UTC13:01:06 开始。SHA 封存收据 `reference-freeze-receipt.json` 在运行开始后 UTC13:01:41 记录，晚于首页完成的 UTC13:01:33；当前参考文件哈希与收据一致，不能将这份 SHA 收据称为预运行生成或不可变预注册。旧收据措辞保留，时间解释另见 `reference-freeze-timing-audit.json`。它不是完整全文／cell／bbox gold。首版 SCC1.302 被摘要误写为 Interpretation；后续重新核对原页／OOXML确认实际为 Definitions (related to BS Specialist Sub-contractors)，单列来源纠正，保留冻结 v1。Assessment 的 Criteria／Quantity Limit 是概括列义，审阅使用原件实际 Attributes/Sub-attributes／Quantity and Format。不能把参考错误算成模型错误，也不能默改成“全部预先正确”。

Paddle 的公开 vendor JSON、完整 final blocks、Markdown、布局图及相关 crop 已保存。可选原封装对象 pickle 因 `CopyableWeakMethod` 序列化失败，留下的失败文件不能当可恢复快照；早期内存 VLM／table／spotting 字段未完整保存，不能宣称已检查其全部早期内部结果或精确归因识别子阶段。以后 runner 改为 plain Mapping／已知 block 内容＋类型 metadata，保留 NumPy／PIL，明确不含原 wrapper 全部 attrs；仅本地工程回归通过，未重跑模型，不追认 run1 成功。该首轮配方的 `use_chart_recognition=false`／`use_ocr_for_image_block=false`，图内文字未提取首先属于配方能力限制，不能记录为模型已开启该能力却漏读。

MinerU 保存实际 vendor prompts／请求图、155 个 response、model／middle／Markdown／assets，以及 155 个 completed attempt ledger 和对应生成／GPU证据。全部响应 finish_reason=stop，本轮这些分歧未伴随 length 终止；扩大生成预算不能直接视为修复本轮检测遗漏／编辑状态／拓扑问题。Paddle 没有暴露等价逐请求 token 证据，不能据 MinerU 结果追认 Paddle 无截断。

证据汇总脚本 `collect-run-evidence.py` 不加载 gold／模型，不解 pickle，也不判断质量；重新核对记录 artifact 的 SHA。初始化失败、可选导出失败和真实模型质量分别保留。最终全量索引为 `evidence-index-20261009T131654315256Z/execution-evidence-index.json/md`：3 个运行记录（含0调用初始化失败）、22 个页终态收据、0 个读取／哈希 warning。旧索引的默认发现把 VLM-evidence 目录误列为 run，已保留旧事实并修复发现逻辑，未将其算模型调用。执行源码封存在各 run 的 executed source 路径，MinerU 独立来源收据为 `mineru-run2-executed-source-provenance.json`。

## 性能与资源

| 输入页 | Paddle 页阶段秒数 | MinerU 页阶段秒数 |
| --- | ---: | ---: |
| NTT p3 | 13.955 | 31.238 |
| SCT p48 | 8.913 | 13.029 |
| SCC p5 | 15.720 | 22.572 |
| Assessment p1 | 15.751 | 122.015 |
| Assessment p2 | 6.659 | 98.218 |
| G302 p27 | 4.914 | 91.759 |
| PRE p139 | 16.288 | 22.797 |
| PRE p140 | 13.213 | 15.847 |
| PRE p149 | 11.366 | 99.898 |
| PRE p524 | 11.073 | 14.804 |
| PRE p525 | 15.330 | 21.810 |

逐页来源：两 run 的 `receipt.json`，各 case 的 input id 与原件 SHA 可复算。整轮含加载／导出等成本；Paddle 首次模型加载 4.685 秒。

整卡共享采样峰值分别为 Paddle 15,581 MiB、MinerU 16,045 MiB，包含未干预的其他进程。Paddle allocator cumulative peak allocated=8,646,053,632 bytes、reserved=14,720,455,424 bytes；MinerU 各请求生成阶段峰值见 VLM evidence，reset 在模型／输入加载后，不能当完整加载峰值。整卡、allocated、reserved 是不同记账方式，不能相加或全部称某 parser 独占显存。

大表显著慢于短文字块，当前 FP32／eager 配方对16GB机器形成很大内存压力；例如 assessment p1 的主表请求输入2040 tokens、输出559 tokens、117.924秒，allocator reserved峰值23,674,748,928 bytes超过物理显存，整卡采样接近上限。它不是“只在CPU跑”，但 Windows 驱动／共享显存及各子阶段实际成本尚未独立 profiling，不把推测写成已确认的唯一慢因。后续 BF16／SDPA／官方高吞吐后端应各立新配方，不能把本轮数字称为 GPU 最佳性能；H800 的真实性能也未测。

## 运行后状态与后续实现

`after-all-model-runs-services.json`：前端 PID67984、Java PID95412、MiniMax中继 PID73776 与运行前相同，三者 HTTP200；Bonsai `Model.Enabled=false`，无 llama-server／8091监听；实验无18083监听。两个试验进程均已结束，观察时GPU已用374MiB／空闲15,796MiB，这是瞬时整卡快照。没有停止／重启其他服务，原件和权重保留。

后续实施顺序：

1. 补 POI 的完整嵌套表／gridSpan／vMerge、tabs、图片／chart资产和局部编辑状态接口；以真实原生位置绑定，保持继承样式／控件等未处理项的 unknown。
2. 增加 native-to-rendered region 映射，保存 renderer／字体／配置及坐标变换；SCC 父节点和标题 strike 不依赖 OCR 一致性猜测。
3. 将视觉原图、标题、父条、完整表组与跨页续组关系持久化。候选失败保留分歧，对完整表组做接受，禁止逐格拼接补成貌似完整的表。
4. 在这些代表页之外做完整页／全 cell 标注及文件级留出；再评估通用配方、模型调整和真实检索。当前31关键项不作为全文准确率分母。
5. 验证链路稳定后再接在线 OER，并分别做应用／前端验收。原生 PDF 文字层＋视觉联合方案仍为另一实施细化，本轮两路视觉只读页图，没有将派生 PDF 文字层或 POI 文字喂给它们。

上述发现与采用限制已同步中央范围规则和 handoff；本轮只新增实验脚本／环境／证据及文档，没有因局部样本优势切换在线业务 parser。
