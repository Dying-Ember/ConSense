# 识别模块修复与完整正常入口验证

日期：2026-10-15，Asia/Shanghai。当前状态：完整七页正常 Java 解析、常驻真实页验证及活动 8080 OCR 接线均已完成；表单来源语义质量仍未通过。

用户要求是在当前代码上重新解析全部范围内资料，并比较最后训练后遗留的问题。前轮实际视觉调用为 0，只覆盖原生／POI；其结果不能关闭扫描表单问题。随后用户明确要求修好识别模块，并依据[完整 Mermaid 路线图](../diagrams/oer-recognition-after-repair-flow.md)落实。

本轮只修复必要工程接缝并核验现有配方。没有新增训练、调参、采用未过门 LoRA；原件、gold、fixed05／Local05、历史失败和并发 Drafting 工作保留。图纸识别路由后排除。解析来源质量与检索、重排及业务判定分别处理。

## 已验证的原生 PDF 修复

Camelot 的 PLAYA Document 文件映射在完整投影完成前没有被正确释放，导致 Windows 临时原件无法删除，正常响应失败而部分候选已写入 CAS。本轮将该 Document 的生命周期收在共享候选生产函数内，完整迭代、投影后在 finally 释放；保留密码及提取权限检查，不全局修改第三方包。

红／绿真实三页复现、完整候选树等价比较与相关 73 项测试记录在忽略目录 `.local/oer-recognition-repair-20261015/native-lifecycle-repair-receipt.json`。实际正常 Java 入口又执行 COM11 55 页、GCT 2 页、GCC 74 页，共 3 个完整输入／131 页；完成时间 145.594 秒，正常结果均为 PARTIAL，视觉 OCR 未启用。自有 CPU server 在 owner STOP 后退出码 1，Job 和子树释放已确认，未将该退出码改称 0。

独立评价复用冻结 R116 原评分函数及原参考，不向生产者提供答案：

| 同源检查 | 本轮结果 | 与旧结果的可比结论 |
| --- | --- | --- |
| COM11 Stream，5 完整网格／672 来源格 | 672／672 文字及拓扑正确 | 同方法 gain 0、regression 0 |
| COM11 Network，同 672 格 | 650／672 | 同方法 gain 0、regression 0 |
| COM11 pdfplumber，同 672 格 | 0／672 | 同方法 gain 0、regression 0，不跨方法冒称回退 |
| GCC 原 169 定义成员文字 | 169／169 保持 | 不据此授 66 个定义框架或法律归属通过 |
| GCT 原 43 可见单元 | 42 一致、1 未知 | 不拼接未知页脚以凑答案 |

COM11 三方法全部 55 页均有实际候选，11 个批次没有 cleanup 错误；本轮真实消除了该原生运行阻塞。GCC 72 个选页、GCT 1 个选页的两种 Camelot 方法仍明确返回 `PDFTextExtractionNotAllowed`；pdfplumber 保留独立观察，不绕过权限，也不把正常进程返回称所有方法通过。

独立记录在 `.local/oer-recognition-repair-20261015/native-evaluation-independent-01/`：`comparison-report.json` SHA `ec0e2bd9aeec2dc668de8fe129d230122095cb682ad691d4b4f9e91a22b80077`，同方法 2,016 格账本 `same-method-old-correct-comparison.json` SHA `76517979c6d95bfe95714c99a8bdf35417498fdd0dd5d6587c3ee4c9c09e80c7`。621 个 CAS blob／1,600,809,599 字节逐内容 SHA 已核；563 个由比较投影直接引用，其余 58 个仅核存储哈希，不虚构语义关联。

## 视觉入口及当前失败

新隔离 Linux 环境实际安装并核验 Paddle GPU 3.3.1、PaddleOCR／PaddleX 3.6.0、RapidOCR 3.9.2、ONNXRuntime 1.22.1 和 web／multipart 依赖；旧完整 MinerU 4.0.10／vLLM 0.19.1 环境复用。两环境 import、pip check 和设备 BF16 支持检查分别保留，依赖成功不等于模型已运行。

Linux CPU 身份 OCR 及 `/health` 原先无条件导入 Torch，阻止无 Torch CPU 协调服务使用。修复仅让明确 CPU 分支免于该依赖，保留实际 GPU 分支检查。已实际从 Windows 访问隔离协调服务 `127.0.0.1:18780/health` 返回 200，CPU 身份 OCR 两次真实请求返回 200。

正常 Java 入口首轮完整请求 AoA 4 页及 APL 3 页，共 7 个详细请求；全部在 owned Linux provider 启动／释放接缝返回 503，没有取得模型结果。该轮 108.485 秒，原始请求、原件 SHA、页号、200 DPI 图像与原页坐标变换，以及失败 CAS 全部保留在 `visual-actual-02/` 和 `integration-preparation/linux-config-01/`。失败信息为 `Native Linux process exit remains unconfirmed`。实际 root cause 是 `wsl --exec` 进程处于 `/init.scope`，向用户委托 cgroup 迁移被 EACCES 拒绝；临时文件对象的缓冲写入异常在析构阶段被吞掉，仍发出了假 ACK。

生产修复改为明确 write／flush／close，并同时核 `/proc/self/cgroup` 及目标组 PID 后才 ACK／exec；释放须同时确认完整组为空和根进程已退出。构造失败保留整树清理，主错误和清理错误分别落盘。真实 delegated user service 下的父／子／孙、失败退出、丢失 ACK 和双错误 4 项 CPU 测试通过；init.scope 拒绝迁移另 1 项实际通过。旧逸出 coordinator PID33733 已按精确 cmdline／startTicks／pidfd 恢复释放，旧失败不改写。最终相关窄套件 29／29 通过；较早一次 29 项中的 Windows 一秒 deadline 文本时序失败及单项复跑通过分别保留。

修后 `visual-actual-03/` 完整七页实际证实接管与释放正常，但配置用了逐文件模型符号链接，严格的实际文件归属检查拒绝该别名目录；198.422 秒、七页失败保持。随后只修正 Paddle 的真实模型目录，22 个文件／2,062,476,471 字节模型保持同一 SHA；没有放宽来源门或修改 capture 判断。03 owner STOP 后 `treeReleased=true`、transient unit inactive 已核。

`visual-actual-04/` 已出现真实主模型及 CUDA 推理证据：570 个实际参数全部在 GPU0，111 BF16、459 为官方允许的 FP32 参数；首份失败 manifest 的布局推理完成，前 6 次生成均 `completed/eos_before_cap`。该轮新阻塞是每页全部原始数组的 64 MiB 采集容量：布局输出的 int32 `[300,200,200]` 数组单独占 48,000,128 字节，叠加模型输入后超限；不是 token 输出爆量。首份完整 503 体 87,451,714 字节已保留。另有 AoA3／APL1／APL2 实际完成双 provider 原始输出，后续 Paddle JSON 投影仍沿旧 64 MiB 默认值，遇到 78,372,843 字节响应时保全 raw 但没有页面，最终 `next()` 抛 StopIteration；该结果不能称正常 Java 入口成功。

容量及缺页修复已做红／绿测试：仅 Paddle page_json 获得专用投影边界，其他 generic JSON 仍保 64 MiB；缺主候选页或双路无页均明确 failclosed，不改选另一模型掩盖问题。实际 APL1 原始响应＋MinerU ZIP＋当次保存的完整 sourceContext 已以零 HTTP／零新模型调用回放到 2 个正常区域，两路 raw／asset 完整、来源质量仍未验收。此前 77 项相关测试通过。

随后用户明确目标超算平台有 250 GB 高速内存，要求增大预算。最终视觉配方将原始数组上限设为 **512 MiB／页**，完整 provider 响应及 Paddle JSON 投影上限各为 **1 GiB／次**，按需使用，不预分配。该工程容量不改变模型、输入、生成长度或来源标准；超限仍保失败，不裁掉原数组。最终边界自检与七页真实运行另记，不用离线回放替代本轮真实调用。

另有两个取证脚本问题保留：原生 staged server 因未复制 sibling `offline_delivery` 导致一次 `/health` 500，但独立原生候选接口实际返回；首视觉脚本因 Windows 无法遍历 Linux 模型符号链接在模型／OCR 调用前中断。后续视觉快照保留正确 sibling 布局，输出预算仅统计实际运行证据目录，不遍历模型链接。

## 修后完整七页真实运行

`visual-actual-05/` 使用完整 AoA 4 页和完整 APL 3 页，正常 `DocumentParser`／`PaddleOcrClient` 实际执行。两输入全部返回，root 对 exec session `67371` 的实际工具观察为退出码 0，耗时 **943.156 秒**；2 次 CPU 身份观察和 7 次详细双视觉请求共 9 次 wire，配额用尽但没有越界、阻断或自动重试。AoA 覆盖 4／4、APL 覆盖 3／3，失败页均为空，原件 SHA 保持，数据库打开和原件写入均为 0。

七次详细请求分别完成 Paddle 和 MinerU 后进入正常 Java 消费；两原件状态均为 `PARTIAL`，保留 `needs_review`、`dualParserComplete=false` 和 `sourceQualityAccepted=false`。完整处理、完整取证与来源准确性是不同判断。不能把这七页称全部 39 件的新视觉重跑；其他原生／POI 的实际运行身份见前轮全量报告。

实际 exec 观察、逐输入 terminal、完整正常输出和原始 provider 证据在忽略目录 `.local/oer-recognition-repair-20261015/visual-actual-05/`；`execution-terminal.json` 保留 2／2 输入及完整调用账本，`root-execution-observation.json` 绑定真实工具退出码。driver 的 `independentDescendantReleaseObserved=false` 原样保留，不能根据 Job handle 关闭擅改为 true；Linux provider 和 coordinator 释放另核真实 cgroup／根进程终态。

05 测试 owner 已在运行器结束后接收 `OWNER_STOP`，实际 `owner-state/terminal.json` 为 `reason=owner_stop`、`treeReleased=true`，对应测试 unit 为 inactive、MainPID 0，18780 已释放。该测试 owner 不作为常驻服务。

独立闭合凭证为 `.local/oer-recognition-repair-20261015/final05-closure-02/closure-receipt.json`，SHA `84c5ba416c9159b2fc416f04abb911b05505751842748d32e9bf5f7ff7fae21c`：14 次 provider 均 `released`／`cgroupPopulated=0`，主错误及清理错误均为 0；417 个唯一引用 CAS 资产／1,149,873,191 字节逐读校验。该 CAS 字节数是此闭合凭证的引用资产集合，不等于整个输出目录占用。

逐次生成另作独立核验：Paddle 七页生成次数为 AoA `12／14／6／11`、APL `2／5／9`，共 59 次。59／59 的已保存 int64 token 数组逐行重算均在实际 cap 8192 前遇到 EOS，EOS 后仅 pad，7 个 layout run completed；引用数组集合、SHA、shape 与 NPY 长度均核过。各页唯一数组为 75,217,212／76,742,724／66,785,124／73,361,664／58,517,108／63,939,624／71,490,360 字节，均低于最终 512 MiB，但多页高于旧 64 MiB。没有采集、生成、主错误或清理错误。MinerU 七页官方 middle／model 文件均为单完整页，middle 声明 `is_full_document=true`；其实际输出没有 token／EOS 终态字段，因此 `officialEosBeforeCapVerified=false`，不根据 HTTP 或归一化 completed 补造该证据。摘要凭证为 `final05-generation-audit-01/summary-receipt.json`，SHA `49473a1309f912f2f2b1482647b3468d6c88ad6ecd071671d49eb5439473df99`；来源质量仍未据此通过。

## 正常本地入口接线

可复用本地服务配置在 `.local/oer-recognition-local-20261015/`。启动和停止明确使用 `Ubuntu-24.04` 的 `ocr` 用户；协调进程直接出生在 `consense-recognition-local.service` 的委托 cgroup 中。它使用纯 CPU runtime 和真实仓库源代码，逐资产校验 boot 清单中的 246 项；不依赖旧 replay 结果，不采用未过门 LoRA。模型进程仅在详细请求中顺序启动与释放。该 transient 服务无 timer、无自动重启，当前本机手动管理；此状态不代表目标超算部署已完成。

常驻服务首轮实际启动时 MainPID 为 116144，`Delegate=yes`、`Restart=no`、Active／running，18780 的健康接口为 CPU、offline、`Complete Paddle/MinerU API candidates`。随后一次完整真实 APL1 请求返回 503：Paddle 完成，MinerU 的实际作业返回 `Errno 40 Too many levels of symbolic links`，路径为新常驻模型目录的 `.locks/MinerU-4_models_onnx.lock`。两 provider 均实际 released／cgroup 为空；该失败及完整响应保留在 `normal-activation-01/normal-one-page-apl1-actual-01/`。root 随后只停止这个精确的 normal unit 并核 18780 释放。根因是本地准备脚本把可变 `.locks` 也作为模型链接，而实际 FileLock 使用 `O_NOFOLLOW`；随后仅排除该类运行状态，保存原链接记录、移除两个精确本地 alias 并以真实 FileLock 加锁／解锁通过，seed 锁及权重均未变，boot／complete SHA 保持。修复凭证在 `.local/oer-recognition-local-20261015/lock-repair-01/`。

修后常驻 MainPID **121907**。`normal-one-page-apl1-actual-02/` 实际完整请求为 HTTP 200、2 个区域、两路 parser 均完成且 released／cgroupPopulated 0、主错误及清理错误为空；这是新真实模型请求，未使用旧结果 replay。当前 coordinator boot SHA 为 `6e2c7f719d4109fc1a01f40c90412082a4a20814dc439c4f86ca3d33faf534eb`，complete 配置 SHA 为 `97a7c48a08eb0f7d19a3b76a03751bf385b87f433a458012247e0af49f0cf118`。

随后按 fresh preflight 的运行配置、JAR、additional config 与后端 PID／创建时间核验，只新增 `BackendOcr`：Enabled true、Mode local、BaseUrl `http://127.0.0.1:18780`、TimeoutMs 1800000、TextLayerMinChars 40；双向 JSON roundtrip 确认其他配置语义保持。沿现有 `scripts/local.ps1 -Action restart-backend` 替换进程，当前后端 PID **7164**；原 JAR SHA `3fe51ad2dd8a6f41e468fb612fbf44e907ce017545a541b04358551c1649d971`、additional config、16 GiB 堆、原 15 个项目 ID 和其他组件 receipt PID 均保持。14:03:41 UTC 再读活动 `/api/system/health`，OCR 为 **enabled=true／available=true／mode=local／18780**。既有 supervisor 原先已 Running，没有新增 timer 或计划任务。接线凭证为 `normal-activation-01/activation-receipt.json` 和 `active-root-observation.json`。没有向活动项目上传原件、创建业务结果或执行写 API，真实正常 Java 解析证据来自上方完整七页；不冒称浏览器或业务上传验收。

## 来源复核与最后训练的比较

七张本轮实际输入、原件 SHA、原页 affine、CAS 与输出引用重新绑定后，独立复核 Paddle、MinerU 和正常 Java 实际消费三分支。normal 的 60 个块逐项等于本轮 Paddle provider block／文字，没有拼入 MinerU。以下文字参考只是固定的 177 条来源子集，折叠排版空白后逐行判断，不是全文 CER。最后 R153 训练候选未产生该完整域的合法语义输出，不能由此计算训练权重提升率。此前报告未充分追溯同 177 行历史 OCR 评分；本轮追问后已找回 R145，补充对照如下，不能再泛称没有任何历史同域文字评分。

| 本轮分支／177 条来源参考 | 保留 | 遗漏 | 改字 |
| --- | ---: | ---: | ---: |
| Paddle | 124 | 45 | 8 |
| MinerU | 137 | 33 | 7 |
| 正常 Java 实际消费 | 124 | 45 | 8 |

57 字段的 type／state／value／完整字段、74 写区的定位／state／value、11 文档关系均为 unknown：实际输出主要是通用文字／布局块，不能用评估器补语义 sidecar 后给正常引擎授分。Java 仍保留 4 个 OR／Notes anchor 对应的 8 条 adjacency 候选，均为 `unknown_not_assigned`；候选存在不证明这 11 条来源关系已识别。665 条判断由当前多轴 461 条加旧域 204 条构成，不是 665 个独立对象。R157 的 83 条旧 correct 域行逐 ID 匹配后，本轮全部缺语义见证、未重证；不能把它们改称 83 条错误，也不能称旧正确已经保持。

来源缺陷已落到实际首个丢失层：AoA 签署分支和 Employer witness 文字在两家 provider 原始输出即有遗漏；APL3 的 19 个 Roman marker 两路均未输出，只有 trade headings；AoA4 另有重复文字。普通块的独立额外声明判断为 Paddle／normal 41 correct、9 unknown、10 incorrect，MinerU 42／13／9；这些完整块判断与 177 文字子集不是同一分母，不能混算准确率或补齐表单得分。

最终来源账本在 `seven-page-review-preparation/normal-complete-dual-review05/source-review-02/`，`validation-receipt.json` SHA `19df6a5ea0980db88f85843b8b1dfb07b96ce6e5893111493cb6429f857bf7d1`。三分支均经 sealed checker 重算完整域 ID／顺序、actual assertions 覆盖、算术与分支引用，另经独立窄审；`sourceQualityAccepted=false` 保持。最后实际训练仍为 R153 且未采用，本轮不授完整扫描表单语义提升。

## 与历史最好成绩的补充对照

“最好训练权重”“最好历史解析候选”和“当前正常引擎”分别列示；没有一份已通过完整来源质量并采用的总体最佳权重。当前七页沿既定原模型运行，没有加载历史未采用 LoRA，不能称同权重架构 A／B。历史依据为 `docs/testing/oer-progress-ledger.json` 的原轮次、trainingHistory／comparisons 及其精确来源收据。

| 真正的权重训练评价域 | 历史有证据的训练后最好结果 | 本轮同域结果 |
| --- | --- | --- |
| SL 三张完整训练表段，R05，35 origins | 严格文字28／35、空白等价34／35、未勾18／18、真空白9／9、拓扑3／3；仅训练图 fit | 未重测该域，不能与 COM11 的672格互换 |
| 同 SL 原始完整页正常 parser，R06 | 严格18／35、空白等价20／35、未勾10／18、真空白5／9、拓扑2／3、续接0／2；候选未采用 | 尚无本轮相同35 origins测量 |
| FWP 完整四页，R62训练后R63正常回接 | 文字76／82、目标框10／10、列4／4；同recipe训练前75／82、8／10、4／4，旧correct保持；候选离线未采用 | 尚无本轮同82／10／4评分；本轮新双视觉只含AoA／APL七页 |
| MinerU公开两表，两个独立20更新实验 | 严格109／112、去空白110／112，与原base输出相同；无训练输出收益，未采用 | 不属于本轮赛方原件域，未据此计算提升 |

FWP 历史另有未训练 stock 基线 80／82、10／10，不得把“训练后最好76／82”误称该来源所有方法的最好80／82。R63源评分权威为 `.local/state/oer-sl-ap2-fwp-closed-four-page-scorer-preparation-20261012/prepare-04/root-executions/root-closed-original-and-trained-quality-01/complete-quality-diagnostic.json`，SHA `417c78495ecad9a30af534a8b98b53972f1075315ac16cd9b6660e139f95396c`。

R145 是历史 OCR／方向方法的来源对照，**不是新权重训练成绩**。其原七页分支与本轮同177文字子域可列出已记录的确认数；旧侧采用 retained literal exact，当前来源复核折叠版面空白，存在评分口径差异，不能把下表相减当成严格同评分器的质量增益。R145 的180度同源控制另有94／127、28／31、18／19，不当成原页分支或与其逐行拼优。

| 同源文字子域 | R145原七页分支 | 本轮normal实际消费 | 本轮MinerU独立候选 |
| --- | ---: | ---: | ---: |
| AoA文字／127 | 82 | 93 | 106 |
| APL正文上下文／31 | 26 | 31 | 31 |
| APL印刷编号／19 | 15 | 0 | 0 |
| 同一完整分支合计／177 | 123 | 124 | 137 |

原报告记录为123、当前为124，仅是诊断计数对照，口径差异使这1条不能称严格净提升；不能用合计掩盖编号15→0的明确输出丢失。历史另一份正常 parser 保存输出有完整19／19编号，故按该最好旧输出看，19个编号全部没有保持。其独立权威为 `.local/state/oer-quality-persistent-20261013/assessment-independent/normal-actual02-apl-independent-source-score.json`，SHA `415141ae804907e5684f42a86864cd972fdfa58454280e04ace4f222a40b1abf`；这19项是编号文字，不能扩大为19完整字段状态或全组语义通过。

补充逐ID核对凭证在 `.local/oer-recognition-repair-20261015/historical-comparison-01/r145-final05-id-comparison.json`，SHA `ae3f9b8348a3f27ca6d89db57c00b4266f424175084eaa0c4e590859afa75612`：177个唯一ID、预期文字及域均相同，旧normal的19项编号亦逐ID相同，6个旧上游pins重新逐SHA通过。旧normal19来自三页35次CUDA BF16识别加自动CPU投影，不是新的权重训练或生产采用。附录统一字串存在诊断因缺源位置约束会误收别处标点，明确不采纳为来源质量分，不能据其计算新提升。

历史24个完整R142分支已全核，AoA结构最高18／38、owner最高25／55、关系最高2／11；三个最高值同在一个2000 whole-OCR continuous完整分支，不能把它称为正常主入口已采用。R142正常分支最高结构7／38、owner9／55、关系2／11。R159另有真实CPU typed投影独审（不能因展示ledger只到R158而漏掉）：字段type为1correct／56unknown，仅TITLE；写区定位31／74、state和值各1／74、关系2／11，完整57字段仍全unknown，候选未采用。R159权威为 `semantic-field-alignment-01/typed-field-projection-independent-review-01/source-summary.json`，SHA `8204463436517ecce6bea2ad21495df84804ce4ecf61b2b6611e177e699648cf`。这些是工程／CPU来源候选，非权重训练；本轮正常输出缺相应声明，没有重证这些局部能力。运行恢复与局部文字变化尚不足以判新架构超过历史最好。

## 代码与配置验证

原生生命周期相关 73 项、最终 ownership／清理窄套件 29 项、采集／投影／缺页相关 77 项均通过；最终 512 MiB／1 GiB 边界调整后受影响 6 项再通过。正常后端 OCR 配置检查 42 项及真实 Spring Boot 2.7.18／application.yml 五字段绑定通过。Linux delegated 真实父／子／孙及失败场景 4 项、拒绝迁移 1 项分别保留，未与上述套件相加为独立总数。此次只有 Python 运行接缝与本地启动配置变化，生产 Java 未修改；各轮旧失败、一次 Windows deadline 时序失败与后续复跑结果仍保留。

## 剩余验收

1. 表单来源语义仍未通过，57／74／11 完整分母及旧 correct 未重证保持；不能以运行成功或输出字数关闭。
2. 已确认的模型输出残差继续开放：AoA 部分签署条款漏读，APL3 的 19 个 Roman marker 未被两路输出，通用 OCR 文字不自动具备字段 type／state／value／owner 或文档关系语义。
3. 常驻服务当前为本机手动管理，没有开机自启／自动重启；超算 H800／250 GB 目标环境、浏览器上传及下游业务效果均未在本轮验收。

最后实际训练为 R153，候选未采用；最新有效来源判定为 R157／R158。本轮工程修复、完整七页运行和原生旧正确保持已经有证据，尚未取得可授予完整扫描表单语义提升的结果。完整来源质量、浏览器／业务端到端与目标部署均未据此通过。
