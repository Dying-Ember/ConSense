# Vetting 技术讨论与决策记录

记录日期：2026-10-02。本文记录本轮用户确认的方向、已经验证的结果和下一步执行要求，供团队继续实施和比较实验。项目目标是把 vetting 做到可核查、可审核、可导出；当前瓶颈是源语义审查质量，已经跑通的技术链路不能等同于质量验收。修订后的目标正文见 [当前执行目标](vetting-current-goal.md)。

## 用户确认的范围和硬件

- Drafting 由其他队员负责，本轮集中完成 vetting。赛方材料中的实际 tender、standard、project fact 和 manifest 按明确范围进入 corpus；2d 审查 comments 和 2e 问答仅用于独立 evaluation，不进入生产 corpus、索引或模型提示词。房署 ASIR 确认的通用条件逻辑另作规则及验收参考，不据此扩展 drafting 工作。
- 参考材料位于 C:/Coding/ConSense/2026-09-04_Documents to Shortlisted Candidates (Contract Advisory)。两个项目分别为 ai_consense_web 和 ai_consense_service。
- 本机为 RTX 4060 Laptop GPU，8GB 显存。用户同意先在本机验证解析、OCR、RAG、rerank 和信息整合。
- 比赛环境由用户确认：27 vCPU、250GB RAM、1×NVIDIA H800 80GB、650GB High-Performance Storage、1250GB General-Purpose Storage。比赛 OS、依赖、权重和索引预带条件仍需核对，尚无 H800 实测。
- 用户要求规划和调整本机模型相关技术参数，用每次结果做比较并写日志；同时允许提示词工程对照。已测提示词方案效果不足时可以上 agent runtime，自研或改造开源实现均可。

房署反馈中的条件必须保留到审查规则：例如 combined foundation works 与合约期不少于 39 个月同时成立，才触发 NTT clause 10 保留 G1a；G1/G1a 是派生修改位置；NTT clause 13 的 NSC 调整还需判断原文是否有指定的 Amend 指示。不能把这些条件降成独立无条件的字符串匹配。

## 技术路线与职责

文档先通过原生 PDF/DOCX 解析保留物理页、段落、表格和原文范围，扫描页再进入 OCR。建立有文件版本和来源范围的 corpus，以 embedding 和 BM25 混合召回候选，再用 reranker 排序。信息整合模型只读取本轮明确提交的来源；最终问题保留原文引文、适用条件和 unknown，交由用户审核后导出结构化报告。

本机现有实现使用 RapidOCR/ONNX CPU 路径、BGE-M3 embedding、BGE reranker、本地 Qdrant、BM25 与 RRF；本地 LLM 已比较多个实际配方。具体权重身份、版本、参数默认和未验证项以 [本机调优计划](vetting-local-tuning-plan.md) 及各轮冻结 manifest 为准。这些是当前基线，不代表已选定比赛环境的最终配置。

Agent 的职责是协调冻结输入、检索或单元选择、来源校验、比较、失败处理和独立审查。模型承担语义选择与解释；程序承担可机械核验的身份、列角色、原文范围和引用约束。模型自行给出的来源 ID、用途标签或重写引文不能直接成为可靠证据。

## 参数规划和每轮日志

参数规划覆盖 OCR 的渲染 DPI、阈值和线程，分块长度与 overlap，embedding/reranker 的 token 上限、batch、device、dtype，混合检索候选量及 RRF，LLM 的模型身份、量化、上下文、输出上限、temperature、实际继承参数，以及 system/user/schema 和工作流版本。

每轮先登记假设、基线和改变的因素，再冻结输入、原文件、source/chunk/Part、原表格、提示词、参数、模型 digest 和执行代码。运行后保存原始 request/response、逐次调用计数与耗时、实际 token/stop 信息、资源采样、错误、覆盖范围和独立源审。失败、中断、拒绝字段与错误结论同样留档，不以新的结果覆盖旧结果。

单因素实验优先。只改提示词必须验证其余实际请求相同；更改 schema、工作流和上下文属于多因素实验。整机采样显存包含驻留服务，不能当作模型独占显存，也不能外推 H800 性能。未知继承参数为 null；字符数和 stop 状态不证明输入未被服务截断。

统一日志入口为 tools/vetting_eval/experiment_log.py。工作区 tmp/vetting_experiment_logs 保存不可变 records 与可重建的 comparison.json、comparison.csv、comparison.md。后续源审只追加 independent_audit 事件，保留原调用后的待审状态。详情见 [实验日志约定](vetting-experiment-log.md)。

## 提示词与 runtime 的决策

提示词对照保留冻结材料和评价口径，比较是否准确区分 Required input 与 Reply、明确否定与缺失、同一对象及用途、模板占位符与项目采用关系。独立评价材料不进入提示词，不注入参考答案，不通过人工改写模型响应补齐结果。

本轮只追加通用 system 约束的 3B 对照已完成：3 次真实调用，11 个引文中 9 个能定位，但 5 条输出均未通过独立语义审核，仍出现否定已有值、来源错配和用途混淆。这支持实施更强的来源约束原型；它不能证明所有提示词工程都无效。

第一版 runtime 采用自研 Python 串行状态机，复用当前 gateway 和日志，不引入新的通用框架依赖。节点依次为冻结来源、生成原文单元、选择单元、验证绑定、比较、校验和结束。Stage 1 只让模型输出 unitId 与原值；程序绑定原来源、Reply 列、UTF-16 范围和连续引文。Stage 2 接收全部冻结原文、原请求/回复状态和来源绑定结果，再比较对象、用途、条件及采用关系。

第一版每个 scope 最多一次选择、一次比较，三个 scope 上限为六次串行调用，重试为零。调用前记录 attempt，实际尝试与完成次数分开；选择调用失败时按冻结策略跳过对应比较并继续剩余范围。拒绝选择不会删去原文，空选择和空结果不等于源中没有值或审查正确。

来源绑定通过仍不证明语义正确。旧输出 schema 尚不能绑定比较的原子属性，同值检查只能记录警告，不能凭一个字段相等直接删掉涉及其他属性的问题。若后续需要复杂检索分支、恢复或工具调度，可以在同一节点和事件接口上改造开源 runtime；是否引入框架由实际实验和维护需求决定。详细设计见 [runtime 计划](vetting-agent-runtime-plan.md)。

## 本轮实际状态

| 工作 | 已完成的验证 | 当前边界 |
| --- | --- | --- |
| 参数入口与检索对照 | embedding/reranker 的 token 与 batch 已拆分，索引身份校验已补充；最新 18 项离线测试通过；固定 2739 chunks、8 queries、400 pairs 的 rerank 512/1024 实际对照已完成 | 两组均命中同一 11 个 needle；2 组真实截断消除但未进入 top10，不表示合同语义改善；新的 embedding 1024/batch2 配置仍未实测 |
| 实验日志 | 不可变事件、失败保存、审计追加、比较视图及 CLI 五阶段自动日志已实现；真实 probe 已留档 | 日志可靠性不计入模型质量；probe 没有运行 OCR、索引或 LLM |
| 提示词对照 | 3B system-only 对照 3 次实际调用；原始结果与独立审核已保存 | 语义未通过；不能将 9/11 引文定位率当作审查准确率 |
| 来源约束原型 | 24 项离线测试通过；独立回原始块检查 139 个 Part、71 个单元及范围、45 个表格上下文通过；首轮六次真实调用及独立语义审核已结束 | 三条比较记录均未通过语义审核；本轮仅三个冻结 scope，非全项目，生产未集成 |
| Span 比较原型 | 29 项离线测试通过；原 139 个 Part、214 个单元、120 个 native cells 及 23 chunks 的完整字节重建独立核验通过；三次新的比较及独立源审完成 | 3 条均为 unknown、左右值为空；61 次 selector 中 40 次源定位、21 次失败；34 项中 33 项未评，13 个字段全部未评，质量未达标 |
| 人工审核与报告 | 156项人审版本的实际隔离API/UI及26页报告证明保留；当前结构化输出版本完整163项Java测试通过 | 新应用正在跑，功能/软件通过不等于合同质量；原用户运行环境与新隔离实例分开 |
| 完整案例与比赛机器 | 历史证明保留；Bonsai新44-source应用run5355已唯一POST启动，fresh CPU index运行中 | 19次模型比较为计划，实际完成、覆盖及语义待终态；没有H800实测 |

首轮来源约束原型的真实配方为 qwen2.5:3b，temperature 0.2、num_ctx 16384、num_predict 2048。六次调用全部结束，逐次 wall time 合计 42.819 秒，整机采样显存最高 5731 MiB；比较阶段输出分别为 1、0、2 条原始记录。该实验改变了工作流和上下文，属于多因素实验；三条输出和中间的空数组均不能直接视为质量改善。

Stage 1 的 11 条原始选择中，8 条通过字面来源绑定、3 条因值不是原单元连续子串而被拒绝；Stage 2 的三条记录均为 insufficient_context，均未通过本原型的证据门禁。SCT7 返回空数组，相关项保持未评价。程序门禁和独立语义结论分开记录，不能将零候选视为通过。

后续独立审核已追加到同一实验日志，质量结论为未通过。SCT6 仍将明确的否定回复称为缺值；SCT8 虽承认回复已给日期，却无来源依据地要求回复复制完整模板条款，并存在错源引用与删除原换行的非逐字引文。8 个比较引文中 4 个精确定位、2 个错源、2 个跨 Part 换行拼接；这些统计不是准确率。34 个评价项中 31 个未评价，不能由局部结果推导整体覆盖。

第二版 span 比较实际实验为 `span-stage2-only-3b-ctx32768-20261002T052731136669Z`。严格复用首轮 Stage1 的 11 raw / 8 bound / 3 rejected，只新增三次串行比较，retry=0；3B digest 不变，temperature 0.2、num_ctx 32768、num_predict 4096。schema、提示词、来源呈现、上下文与输出上限同时改变，属于多因素流程实验。来源呈现精简了重复文本，每个原 chunk 的有序片段重建后与原 UTF-8 字节相同。程序生成引用并保留同子串警告，不能从相同 `used` 子串推导 `used/not used` 完整属性一致。

三个调用的 wall time 合计 56.189 秒，返回 input/output tokens 分别为 10785/807、7337/1884、5612/998，均观察到 stop。整机 60 次采样峰值为 GPU 4853/8188 MiB、RAM 17804550144 B；当时 retrieval/OCR 常驻服务缺席，与旧常驻环境不能直接作资源因果比较。native 日志观察到 context 32768 与 truncated=0；输入和输出预算、完整语义阅读及字段覆盖仍分开保留未验证状态。

独立审核判定质量未达标：SCT6 的 20 次 selector 均错绑 Clause 列；SCT7 的 30 次 selector 定位到标准模板，未比较实际项目值；SCT8 的 10 次 selector 定位到真实 tender 日期、书面申请和七天条件，但没有选择 Architect 日期回复，另一次模板选段跨 Part 拼接且错绑标题。没有新增 false absence 或日期冲突主张，同时也没有有源支持的局部正向或负向比较。下一版从原生 Required input 与来源引用归组生成任务，减少自由文本抄写和嵌套输出，由程序生成证据；评价矩阵仍只用于独立审核，不进入模型输入。

## 证明与继续执行

主要证明保存在仓库外的工作区 tmp 目录，后续运行不得覆盖：

- 参数及 CLI 离线测试：tmp/local_model_parameter_tests/20261002T042903376420Z/test_manifest.json。
- 提示词对照原件：tmp/prompt_comparison_runs/system-only-3b-20261002T041800Z/technical_prompt_only_comparison_manifest.json。
- 提示词独立审核：tmp/independent_fact_response_audits/system-only-3b-20261002T042218482914Z/evaluation.json。
- 来源约束原型测试：tmp/test_snapshots/source-unit-runtime-24-ctx16384-20261002T043800Z/test_summary.json。
- 首轮真实 runtime：tmp/source_unit_runtime_runs/source-units-3b-ctx16384-20261002T044000Z/runtime_manifest.json。
- 首轮 runtime 独立源审：tmp/independent_fact_response_audits/source-unit-runtime-3b-20261002T044420636122Z/evaluation.json。
- 独立原文单元审计：tmp/source_unit_runtime_prototypes/independent-original-source-audit-20261002T043507123333Z/independent_frozen_catalog_original_source_audit.json。
- 固定 rerank 参数实测与比较：tmp/rerank_token_experiments/rerank512-1024-20261002T050558263488Z-f914dc11/comparison_sidecar.json。
- Span 原型实际调用：tmp/span_comparison_runtime_runs/span-stage2-only-3b-ctx32768-20261002T052731136669Z/technical_span_comparison_manifest.json。
- Span 原型独立源审：tmp/independent_fact_response_audits/span-stage2-only-3b-20261002T053529469329Z/evaluation.json。
- 独立评价矩阵：tmp/independent_evaluation_matrices/span-comparison-reference-20261002T052832741958Z/source_grounded_evaluation_matrix.json；evaluator-only，禁止进入 runtime、prompt 或索引。

## 逐原始请求的第三版实际结果

`request_task_runtime.py` 已完成 21 项离线测试和独立源审。任务直接按原表格 Required input 原文及引用归组，当前源材料推导出 SCT6、SCT7(3)、SCT7(1)+(2)、SCT8 四组，每组保留三份原版本。任务数量没有写死，也未读取 34 项/13 字段评价矩阵。模型只选择真实单元 ID；左侧只允许 tender，右侧只允许本请求已填 Reply，standard 仅作支持。

真实实验 `request-task-id-only-3b-20261002T0624105353167Z` 于 06:24:10–06:24:43 UTC 完成四次串行调用，retry0、没有新增选择调用。原请求 bytes 与授权准备文件逐条相同；ctx32768、temperature0.2、output4096。调用 wall time 合计 26.368 秒，输入 tokens 为 9910/6857/6869/5023，输出为 181/226/196/212，全部 stop。32 次整机采样峰值 GPU 5094/8188 MiB、RAM 17950347264 B；当时两个 Java 服务驻留，不能与第二版资源直接作因果比较。

四条原始响应均声称 match，独立源审判为两条错误、两条部分支持，完整支持的局部比较为零，qualityAccepted=false。四条均只选条款标题作为左证据；图纸、标准资料和项目人员用途混淆，SCT6 的明确否定被当成同一提交要求。SCT8 虽识别实际相同日期，仍同时给出 match 和 value_difference，且解释中的来源归属错误。20 个程序引文全部能绑定原文；34 个评价项为错误7/部分8/未评价19，13 字段为错误1/部分5/未评价7。整个原单元能绑定不等于原子字段比较、条件或采用依据已经验证。

独立评价为 `tmp/independent_fact_response_audits/request-task-id-only-3b-20261002T063655118721Z/evaluation.json`，SHA256 `d17d2fb56a4de897a4272fc2d8f4c7c86cf0b6cf3194880b69f452d26ea10a36`；评价矩阵只用于 evaluator。日志追加事件 SHA256 `ede29f4a62eadec434e3f7f23547b6a580b50a095d681a276fc8a2946f0d4650`，原始调用后 pending 状态保留。当前不接入 Java 自动发现。

本轮技术观测：`tmp/request_task_runtime_runs/request-task-id-only-3b-20261002T0624105353167Z/technical_observation.json`，SHA256 `49d4282718b09c0077b3cfa9569030b52b389a972809f1b9e06c84586fc0977e`。root 的进程清单实际在实验结束后采集，原记录误标 during；原文件保留，另有 `root_process_observation_correction.json` 明确改为 after-run，不能将其当作活动模型或 Python 进程证据。

## 第三版的两组单因素实际对照

后续 A 只在原 3B system 之后追加通用正文、回复角色及局部比较约束；B 只更换为已安装的 `qwen2.5:7b-instruct-q4_K_M`。两组均相对 062410 基线，保留四个来源推导任务、214 units、139 Parts、23 chunks、user、schema、options 和完整原文；实际请求在允许字段之外字节完全相同。A 与 B 互相比时同时改变 system 和模型，不能作单因素归因。新 runner 的 27 项离线测试及独立预检通过，旧冻结代码及实际结果均保留。

| 项目 | 旧 3B 基线 | A：只改 system，3B | B：只改模型，7B |
| --- | --- | --- | --- |
| 实际调用/完成 | 4/4 | 4/4 | 4/4 |
| 调用 wall time 合计 | 26.367708 s | 25.387558 s | 41.537067 s |
| 整机采样显存峰值 | 5094 MiB | 5083 MiB | 7548 MiB |
| 实际 PS context | 32768 | 32768 | 32768 |
| 原始 match | 4 | 4 | 4 |
| 独立记录判定 | 错误2、部分2 | 错误2、部分2 | 错误2、部分2 |
| 完整有源支持的局部比较 | 0 | 0 | 0 |
| 仅标题的 left | 4 | 4 | 1 |
| 程序引文字面定位 | 20/20 | 19/19 | 19/19 |

两组均 temperature0.2、num_predict4096、每任务一次、retry0，采样和进程已结束。7B 的 PS 实际 `size=6819854415 B`、`size_vram=6248223210 B`，存在 571631205 B 非显存尺寸差；CPU layer 数未知，不能称全 GPU。整机采样峰值包含驻留服务，单次耗时不证明稳定性能差异，也不能外推 H800。

A 仍将请求当成已填回复，忽略 VPC 的明确否定，并以标题或标准占位符支持具体值和采用关系。B 减少标题选择、正确读出一处否定，但把不同用途的 8th/12th floor 文件查阅位置与 drawings 的 B1 floor 回复称为同一地址；人员和 Library 用途也仍混淆。两组日期值观察均只有部分支持。程序门禁 A1/4、B4/4 通过，均不构成语义通过。

固定 34 项中 A 为错误6/部分4/未评24，B 为错误6/部分5/未评23；13 字段中 A 为错误1/部分5/未评7，B 为部分3/未评10。未评项不能计作正确；accuracy、precision、recall 均为 null，qualityAccepted=false。本次对照没有证明可接入自动发现，下一步仍需约束实际正文、比较属性、对象用途、条件与采用依据。

实际目录为 `tmp/request_task_profile_runs/A-system-only-3b-20261002T065758491Z`、`B-model-only-7b-20261002T065921253Z`。技术对照 `tmp/request_task_profile_audits/technical-20261002T0709329058223Z/technical_comparison.json` SHA256 `04ce1995264e61dc32486dfc9117342a751cbec588945f2e16c65a19767b57bd`；独立源审位于 `tmp/independent_fact_response_audits/request-profile-comparison-20261002T071226032020Z`。使用更正后的 `baseline_A_B_comparison_corrected.json`，原叙述计数错误文件保留。两份评价已分别追加不可变 audit 事件，SHA256 为 `806df84688bd013b98b996ef0f93668dcfa461cbbb478663ac9d1aeff83596e6`、`4a19d27bdf6cf1bec18849e6b38bf126486959480c46018729074f17d0f85336`，原 manifest 的调用后待审状态未覆盖。

## OCR 软件修复及下一组对照

200/250/300 DPI 对照已实测，同五页、同权重，原固定 smoke needles 均为 21/22；三组 Global 预处理及检测输入都为 1408×1984，关键数字和限定没有显示明确改善。APL 的空名字槽位仍会把罗马编号识别为其它字符；不得将空槽或编号当作人数、分包商姓名或采用事实。目前保留 200 DPI。

OCR 的 max_side_len、text_score、box_thresh、CPU intra/inter threads 已拆为独立 CLI 参数。缓存 schema2 同时绑定来源、权重、实际参数、配置/源码与运行环境；生成和 parse 消费都校验签名，旧 unsigned 缓存明确要求 refresh。离线模式会在初始化前校验默认官方权重 SHA，随后用显式本地 model_path 绕过下载/修复；非法参数以零调用 preflight failed 事件留档。最新 29 项离线测试及独立纯文件/mock 反例验证通过，不能算新 OCR 质量结果。有效命令的部分 bootstrap IO 失败仍可能在 started 日志前发生，这个边界尚未闭合。

下一组固定 200 DPI，只比较内部 max_side_len2000 与 3000，两组使用同冻结代码、来源及配方；准备完成后再真实运行。信息整合下一步分别准备 generic system-only 3B 与 model-only 7B，对照原请求和 schema，不能同时改多个因素后归因提示词或模型。后续仍需完整案例、生产接入和实际 H800 离线复测。

以上为准备时状态。新版 `maxside2000-3000-dpi200-v2-20261002T070656398717Z-59c14349` 后续已于 07:18:19 UTC 完成真实对照，两个 arm 各五页、各一次、无重试，整页 OCR 和 preprocess 共各10次尝试/10次完成，资源采样全部停止。旧组在日志 planned→started 衔接处的 stage0 失败保留，未重用；新组绑定14项日志回归及11项 observer/准备回归证明。两组使用同一新版 logger/observer，不能与旧 DPI 组直接作单因素归因。

| 固定 DPI200 | max_side2000 | max_side3000 |
| --- | --- | --- |
| 渲染尺寸 | 1654×2339 | 1654×2339 |
| 实际 preprocess | 1408×1984 | 1654×2339 |
| 实际 det tensor | [1,3,1984,1408] | [1,3,2336,1664] |
| 原22个 needle | 21/22 | 21/22 |
| 原14区域、31字面单元 | 30/31 | 29/31 |
| 五页 OCR 合计 | 13.668669s | 15.820364s |
| CLI wall time | 18.993472s | 20.609542s |

模型会话均为 CPUExecutionProvider、intra4/inter1，开始前实际 PS=[]，child network attempts=0。3000组的 FT2 有效期起点文字被识别成 `frdom` 和 `Tenderer`；APL消防编号部分改善，电气和升降机部分编号反而合并。较大输入不等于更好识别，目前保留2000；空名字槽位不能解释为实际人员或分包商数量。14区域字面检查不是完整 CER、表格关系准确率或合同审查准确率，qualityAccepted=null。

最终采用该目录 `comparison_sidecar_revision1.json`，SHA256 `573b7503a86313b89f56aa935cfd61df85ae4ed505600098c869f6c5e70fd98d`；来源、输出及不可变副本核验为 `final_comparison_audit_manifest.json`，SHA256 `673fb95bd41c95103b4cb7a2ff3e5d1ccda48645b24a62b1d00b4e56ba959f1d`。初版“条件均保留”的宽泛措辞和一次额外 CPU 核验脚本失败均保留，修订只重做文本说明/文件核验，没有重跑 OCR；原两份 CLI finished record 和全部原 OCR 产物未修改。

## 当前人审预览与报告验证

独立预览为 `http://127.0.0.1:5174/#/vetting`，后端为 18081/PID14068，使用与已通过 156 项测试的 classes/resources 相同的新 JAR 和克隆数据库。语义、LLM、OCR 均关闭；这是已保存历史结果的人审/导出验证环境。用户原 5173/18080、126 JAR 及数据库保持原样。77 份实际原件已在克隆环境复制并重新绑定路径，44 份目标案例原件的 bytes/SHA 均一致；stale hash 和跨项目原件请求被拒绝。

人工记录的界面保存、刷新后持久化、未保存编辑时禁止导出，以及三格式下载发起提示已实测。代理取得的 JSON/Word/PDF 包含同一保存版本的回复、处理说明、补遗决定和原 Open 状态。临时验证字段及原 timestamp 已精确恢复。IAB 下载事件超时，没有观察到浏览器保存文件；服务端报告文件和界面下载发起分别记录。

实际导出的 Word 渲染为 14 页，原生 PDF 为 12 页；698 项全文内容对照通过，26 页均经独立原图视觉核验，8 处证据标签与原句开头同页。PDF 人审字段存在正常跨页，Word 最后一页只有最终覆盖提示与页脚，均无漏文或截断。证明位于 `tmp/human_review_runtime_156_20261002T0548432799797Z/layout_qa_20261002T0613079021351Z/content_audit.json` 及 `independent_visual_qa_20261002T062545609616Z/visual_qa.json`；视觉 proof SHA256 为 `7705ade42e6c27af57ec96cfac8c7e29f8f6e15aab69e0cfcd06c4f2910b54c2`。功能及排版通过仍不能代替审查质量验收。

克隆数据库精确恢复后，已打开的详情抽屉仍保留本地测试草稿。重载隔离预览、重新选择实际项目并等待最新列表后，VT-003 显示两个空文本框、补遗待决定、原待处理状态，未执行新的保存。界面观察与截图分别保存为该预览目录下 `ui_probe/restored-ui-observation.json` 和 `ui_probe/review-fields-restored-after-reload.jpg`；服务器三条完整 finding VO、timestamp 和 latest run 的精确恢复证明单独保留。

## 用户关于模型能力与 H800 选型的修正

2026-10-02 用户提出：当前失败可能主要来自模型能力；比赛的单张 H800 80GB 不会以 4B/7B 作为核心审查模型。接受这个判断作为待验证的主要解释，并据此调整实验顺序。当前 3B/7B 的四任务结果不足以宣告纯提示词方案效果堪忧，尤其没有 27B/32B 以上模型对照。前述“继续增强 runtime”的方向修订为：冻结最小来源结构原型作为可选对照，先验证比赛级模型，复杂 agent 步骤只在同模型比较中有增益后采用。

既有 7B 实测将仅选标题的 left 从四条减少到一条，说明换模型配方已改变行为；但它仍将不同用途的查阅地点和不同对象信息称为一致。四条整体记录的独立结论仍是错误两条、部分支持两条。这个小样本只能描述具体配方，不能测定“模型不足”与“上下文呈现不足”各自的贡献，也不能证明更大的模型会失败或成功。

本机与比赛任务分开：4060 8GB 用于解析/OCR、embedding、RAG/rerank、来源绑定、接口与低成本回归；H800 用于主模型的审查质量、上下文/思考预算及吞吐验证。检索模型和 OCR 模型的规模不必随主 LLM 增长，保留准确的原文、条件、例外和项目来源关系仍属于共同输入要求。

当日核对的首批候选如下，仅为研究及部署预算，尚无本项目大模型实测或权重下载：

| 候选 | 用途与权重预算 | 待验证事项 |
| --- | --- | --- |
| [Qwen3.6-27B](https://huggingface.co/Qwen/Qwen3.6-27B) | 当前代际 dense 主模型候选。按 27B 语言参数、BF16 两字节粗算约 50.3 GiB；实际还需额外模型组件、运行时与缓存 | 合同关系推理；BF16/必要时量化；思考预算；实际支持的文本部署与单卡上下文 |
| [Qwen3-32B](https://huggingface.co/Qwen/Qwen3-32B) | 32.8B dense 参考候选；BF16 纯权重粗算约 61.1 GiB | 压缩上下文或量化的质量/资源权衡；不能默认全部 OCR/RAG 服务同时驻留 |
| [Qwen3.6-35B-A3B](https://huggingface.co/Qwen/Qwen3.6-35B-A3B) | MoE 候选，总参数35B、每 token 激活3B；按总量估算 BF16 纯权重约65.2 GiB。激活量不能按 dense 3B 理解，也不能用3B估显存 | 量化/缓存/专家运行开销，实际吞吐和合同质量；不凭激活参数数认定优劣 |
| [Qwen2.5-72B-Instruct-AWQ](https://huggingface.co/Qwen/Qwen2.5-72B-Instruct-AWQ) | 与现有 Qwen2.5 同族的大模型挑战者；72B INT4 的理想纯权重约33.5 GiB，实际文件包含非4bit部分、量化元数据和额外开销 | 代际与量化的权衡，完整上下文/缓存与质量；不是预先确定的最终主模型 |

上述容量均是 `参数量 × 每参数字节数 / 2^30` 的预算推算，不是实际模型占用。以 [Qwen3-32B 原配置](https://huggingface.co/Qwen/Qwen3-32B/blob/main/config.json) 的64层、8个KV head、head_dim128计算，单序列总长度32768且KV为BF16时，传统完整KV粗算另需8 GiB，尚未计运行时及临时激活。这个公式不适用于直接推算 Qwen3.6 的混合注意力缓存；候选均须在实际引擎测量。单张80GB能容纳权重不证明能满足长上下文、并发或整套服务驻留。

Qwen3.6 官方卡列出 vLLM/SGLang 部署与文本模式；其长上下文示例为8卡，不能改口称为已经验证的单 H800 命令。其默认长上下文和思考模式需要针对我们的有限证据范围单独规划，不沿用 Qwen2.5 的 temperature0.2/output4096 作为能力公平基线。精确 revision、量化身份、引擎版本、chat template、采样参数、thinking 开关和输出/思考预算在实际运行前冻结，源码/参数只保存被执行的事实。

下一阶段采用交叉实验：小模型直接提示词、小模型最小结构流程、比赛大模型直接提示词、比赛大模型最小结构流程。同模型比较工作流，同工作流比较模型配方；相同原文内容、条件/交叉引用和来源范围保留，评价矩阵仍只供独立 evaluator。直接提示词也保留最小的原文身份与报告安全绑定，不能以关闭引用校验制造不公平比较。若结构流程引入额外模型调用，另列预算，不能只比较最后一次调用的耗时。

原34项/13字段口径保持；正确局部观察、误报、遗漏、unknown、未评价及字面绑定分别记录。没有完整标注的部分不生成虚假 accuracy/precision/recall。模型家族、量化、chat template 或 thinking 配方变化时，比较整套模型配方而非单独参数规模；4060/H800 的速度和显存不同也不能归因于模型质量。至少加入未用于调优的独立材料与重复运行，防止围绕当前四个任务优化。

目前没有 H800 或其它比赛级大模型实测，能力不足这个解释仍未确认。准备最小可回放流程、冻结大模型测试配方与独立比较协议，同时继续本机 OCR/RAG 参数验证；不再因3B/7B失败持续扩展多层 agent 或循环重试。应用 Goal 的既有交付正文保持一致，执行优先级已同步修订。

## Bonsai2 + NInfer 本机试验与大上下文要求

用户授权“试一下这个方案”，随后要求“上下文窗口尽量大一些”。核对 [作者文章](https://blog.csdn.net/shensanshu/article/details/166012877) 后，实际目标修正为 Bonsai2 27B / Qwen3.8 路线；早先检索到的 Bonsai27B / Qwen3.6 卡不作为此次权重身份。文章中的“不做权重下载”描述其发布内容，不是用户禁止本项目下载。Modelscope 当前公开树仅工具和部分补丁，旧完整包已不在当前目录；因此试用 [公开 Windows sm89 引擎](https://github.com/JGamboa/ninfer-4090-windows) 和 [Bonsai2 转换权重](https://huggingface.co/jgamboa/Ternary-Bonsai-2-27B-NInfer-4090)，明确为改造后的独立部署配方。

引擎固定 revision `e7d309e31260052b10e0500433661b03dcdc6e0e`。权重固定 revision `83da6efe62cfd9857c994a2003808f268ccabd52`，文件 `bonsai2_27b_vl_mtp_q4q5.ninfer`，实际下载 6,869,420,800 bytes，SHA256 `96f1f241753d8e6e01c3f742907523a5b78a2c6dd0eee1f81114a61d29b4d671` 校验通过。使用 v3 `.ninfer`，不能混用 Modelscope 旧 v2 pack 脚本。文件中含 vision/MTP，不代表本试验启用；首轮按源码默认仅绑定 text，省略启用 vision/spec 的参数。

本机实际查询得到 RTX 4060 Laptop、24 SM、compute capability 8.9、8,585,216,000 bytes 显存。五文件适配将原 4090 调度和安全 split 上限分开，并对 GDN cooperative kernel 查询实际 occupancy；独立源码复核暂未发现机械阻断，但不构成编译或 CUDA 数值通过。预编译发行包要求 driver595+，本机 driver572.83 不满足；因此使用工作区便携 CUDA12.8/MSVC/CMake/Ninja 构建，不更换系统驱动。

权重目录实际 config 为64层、16层 full attention、KV4、head_dim256，原生最大位置262144。仅 main KV 的源码容量下界如下，不含权重、workspace、GDN状态和桌面显存：32K BF16 为2GiB、FP8约1.008GiB、rk4v4约0.531GiB、rk2v4-e8约0.406GiB。不能将这一推算当实测占用。先短 smoke 确认可运行，再逐档测32K/64K/128K，条件允许时256K；低比特 KV 的质量独立核对，配置值与真实已验证窗口分别报告。完整旧四任务输入保留，较大输出/思考预算也计入窗口。

截至这一准备记录，权重下载、CUDA12.8组件和隔离 CMake/Ninja 已完成；MSVC/SDK 与 native 依赖在准备。原 urllib SDK 下载无进展后被任务 supervisor 停止，保留局部文件及失败观察；第二次限30秒的 urllib 传输也超时，当前改用有连接/总时限的 Windows curl 传输、retry0，重新校验各缓存 publisher package SHA。工具链准备不是模型调用，实际模型效果尚未产生。既有 embedding512/1024 实验已准备但暂未执行 GPU，优先完成用户新指定路线。

### 2026-10-02 后续实际执行：构建与上下文边界

以上是准备时状态。后续便携 MSVC14.39/SDK10.0.22621、FFmpeg9.0.2、curl8.22.0_2 均已完成。实际使用 CUDA12.8 编译；三处局部 `constexpr dim3` 改为 `const dim3`，NVTX 补 `<cstdlib>`，保留首次编译失败日志，未改算子精度或模型数学。最终构建 `build-attempt-20261002T092653265779Z/build_manifest.json` 成功，二进制位于早先的有效 build cache `build-attempt-20261002T090843087563Z/build/apps`。实际三个 GPU 数值测试（GDN、完整 softmax attention、rk4v4-e8-only）均 exit0，CPU 独立 oracle 与真实 GPU 输出比较；它们不是预训练模型语义验收，未观测数值测试的资源峰值。

文字权重目录精确 payload 为5,930,303,488B（5.523GiB），共710个对象；整个文件6,869,420,800B还含vision/MTP等组件，本轮未启用。模型首次短输入成功返回两个字段：contract_number=AB-17、period_months=42。此前口头“三个字段”措辞不准确，实际为两个；三个测试值属于后面的长输入测试。

| 实际配方 | 实际输入/输出tokens | 生成调用 | HTTP全程 | 整机采样GPU峰值 | 结果 |
| --- | --- | --- | --- | --- | --- |
| ctx2048、FP8 KV、graph开启 | 67/25 | 1 | 约0.77s | 7281MiB | 两字段正确 |
| ctx32768、rk2v4-e8、graph开启 | 31443/65 | 1 | 40.797s | 7655MiB | 三测试值正确、cache0 |
| ctx65536、rk2v4-e8、graph开启 | 未生成 | 0 | 不适用 | 不作为有效容量结果 | 启动预算不足6,920,960B |
| ctx131072、rk2v4-e8、graph开启 | 未生成 | 0 | 不适用 | 不作为有效容量结果 | 启动预算不足900,512,512B |
| ctx65536、rk2v4-e8、graph关闭 | 未生成 | 0 | 不适用 | 不作为有效容量结果 | 启动预算不足4,827,904B |
| ctx61440、rk2v4-e8、graph关闭 | 59394/65 | 1 | 88.479s | 7802MiB | 三测试值正确、cache0 |
| ctx64512、rk2v4-e8、graph关闭 | 62386/65 | 1 | 94.879s | 7812MiB | 三测试值正确、cache0 |

长输入三组均stop，native计数与Chat usage一致，采样器已停止。32K独立审核重算源中三个值各仅出现一次、位置在开头/约中点/末尾，system及源外指令没有答案；native computed_prefill=31443、cache各0。60K/63K独审另行留档。每组仅一次合成输入与65-token短输出，不证明完整配置边界、全部中间文本理解、长输出或真实vetting质量。GPU值是约1秒间隔的整机采样峰值，含桌面和其它进程，不是模型专属/绝对峰值；主动停止owned server后的exit1原样保留，不称干净退出。

原生引擎当前必须把整条逻辑context的main KV放在GPU；host checkpoint不提供单条长序列的KV分页卸载。prefill chunk最小128，64/32不合法。关闭graph释放12MiB预留但64K仍不足；128K缺约859MiB，不再执行资源预算已明显不满足的256K。最大成功配置暂为64,512，质量pilot选择61,440以保留更多余量；这是试验选择，应用默认尚未调整。

四任务plain pilot `plain-pilot60k-rk2v4-graph-off-actual-20261002T1008038444000Z` 已实际4/4完成、retry0、stop、cache0。输入10774/7512/7524/5596，输出144/139/142/220，调用wall合计52.048s，整机采样峰值7772MiB。保留旧7B的user对象和system前缀，在system后追加通用schema说明；NInfer仅有text输出格式，没有原生JSON约束，本轮结果四个均能解析但不能据此称语义通过。temperature0.2、max_tokens8192、no-thinking、并发1、prefill128、无vision/MTP、无prefix reuse。模型家族、三进制转换、引擎、模板/tokenizer、KV、上下文、输出预算4096→8192及schema约束方式同时改变，比较的是整套配方，不能只归因27B参数量。34项/13字段矩阵仅在独立评估侧，独立语义结论见后续补记。

### 四任务plain pilot独立源审与实际采样补记

最终保守源审位于 `tmp/bonsai_ninfer_trials/independent_quality/actual-source-semantic-audit-20261002T102548257955Z`，五个原DOCX实际OOXML、旧B输入、当前raw和独立矩阵共110份保护文件前后SHA不变。四个JSON能解析；任务schema为3/4，第四条reason超过长度约束。18个选定单元引文均能逐字定位，第四条reason额外提到的s053正文也独立定位，但两条primary left仍为标题。

| 局部任务 | 独立判断 | 改善与尚未支持的部分 |
| --- | --- | --- |
| VPC / SCT6 | 部分支持 | 正确读出if any请求及not used回复；所选tender left仍仅标题，不能由此建立条件冲突 |
| 绘图查阅地址 / SCT7(3) | 部分支持 | 正确读出B1 floor/Block2图纸回复；实际left仍选SCT7(4)/(5)其它文件用途，而不是同用途绘图正文 |
| 人员身份 / SCT7(1) | 错误 | 对有名字/职位/电话的回复推断身份缺失，并混入(2)Library用途；复述到名字不等于整条absence推断正确 |
| 现场查阅日期 / SCT8 | 部分支持 | 明确比较真实正文s053与Reply完整17–21 February2025，日期局部有支持；primary left仍标题，条件/采用支持不足且reason超长 |

整条记录为错误1、部分3、完整有源支持0，qualityAccepted=false；34项为支持1/错误4/部分6/未评23，13原子字段为支持2（日期）/部分5/未评6，accuracy/precision/recall均null。先前源审v1把人员整record错误过度映射为姓名/职位原子值错误，保留v1并新增保守revision：姓名只partial、职位未评，不能由整体错误推所有已读出的值错误。最终`evaluation.json` SHA256 `c198cb7cdb020a15f90ea0ee19e700125b054b0e52b07bf8f4d0b2270d4bb032`，`old7b_vs_bonsai2_local_comparison.json` SHA256 `d39d7324a6918e8fca0597f791e3406e3cc9cdab53930b7390ee31b9267e2559`。与旧7B相比，Reply解释和完整日期局部更清楚，人员absence推断退化；不是全面准确率或模型参数量的因果证明。

原生server_start/request_start另显示继承non-thinking采样值：top_p0.8、top_k20、min_p0、presence_penalty1.5、frequency_penalty0，client仅显式覆盖temperature0.2，实际seed随机解析后逐条记录为精确uint64。补充观察位于 `effective-sampling-observations/revision-20261002T102600111216Z`；top_k候选域实际上限20，public 0规范到20，不代表无限。下一组保持同模型、同60K、同原文/prompt/输出/graph/KV及原四实际seed，仅调整presence1.5→0，单独比较，不用默认惩罚假定解释既有错误。

下一轮结构流程的多行编号反例已作软件修复：当前`tools/vetting_eval/structural_scope_runtime.py`按原文行首编号建立连续UTF16范围，片段按自身路径路由；跨兄弟整段、未编号父项及边界未知的普通表格原文保留为未知context。28项离线回归实际通过（旧22+6必要用例），源码冻结SHA256 `6c47db52aa61b56b7f077755442e783bbf838f4d987c66e6d161696a77eae0fe`。新版`tmp/structural_scope_runtime_prototypes/multiline-scope-v2-20261002T102728688762Z`保留全部139Part/214units/120cells/23chunk出现项及451旧片段，469新片段均原文精确定位，全文、cell身份和xref ledger不变。实际s063中“(5) For the purpose…”旧误归SCT7(4)，现明确为SCT7(5)。机械编号纠正不认证用途、条件、采用或完整覆盖。新prepared-only仍0模型/HTTP/GPU调用，未把继承离线7B参数当成27B流程实测；下一阶段才进行同27B流程对照。

### 可用显存波动与48K日常候选

随后原60K配方的presence0组 `presence0-controlled-prepared-20261002T103700Z` 真正执行时在startup失败、0生成：runtime需999,123,456B、当时可用987,680,768B，差11,442,688B。没有发送生成请求，不能评价presence0质量。它与前一成功60K在运行时可用预算相差约51.56MiB，证明原60K/63K单次成功不等于稳定日常配置；波动原因未单独测定，不归因惩罚参数。

改48K（49,152）后，`capacity48k-rk2v4-graph-off-actual-20261002T1041062471109Z` 一次长输入成功：47,415输入、65输出、三值正确/cache0/stop，HTTP66.668s，整机GPU采样7697MiB；本次启动planned slack211,200,256B。独立27项文件证据检查通过，proof SHA256 `fca34455ff05b3d85b66dae390e150650c8382bbda7378fef6c6ab76f4d1294a`，位于 `independent_capacity_audits/capacity48k-20261002T1043358525585Z`，仅证明本单次完整合成输入。最大一次成功配置仍64,512；当前以49,152作余量更大候选，并做同48K、原四实际seed的presence1.5/0成对调参，重复稳定性和语义验收仍待实测。原failed group、原60K回答和原8组comparison保留，新增context因素单独标明。

### 此前12组阶段快照与同48K单因素源审

最终技术汇总为 `tmp/bonsai_ninfer_trials/pretrained-runtime-comparison/revision-20261002T105301892352Z`，保留旧8组汇总并新建12组快照。12组中4组startup失败、0生成，另外8组共17次实际预训练生成；所有sampler停止，核验时414份原proof和420个已有registry事件字节未变。技术汇总不包含语义验收，quality保持null，源审单独追加。manifest SHA256 `f65d7b5836210f0d621fa97a77aac1ff44c6096f8a8eea163bd0580502e87a16`。

原60K→48K的presence1.5控制保持resolved采样和四个实际uint64 seed，四条输出content的UTF8字节与原60K相同。48K两臂的四条原生start/done及完整wire对象随后确认，唯一有效采样和wire差异为presence_penalty1.5→0；输入10774/7512/7524/5596、max_tokens8192、模型、KV、graph、系统和用户全文均相同。context控制proof SHA256 `1ba0dc23f48176f542550f0ff3a2110a9899adc39642fde1ff8083c82c36ef5f`；presence控制proof SHA256 `5eda90ff6d0b51a0ee043f9f69625f391ffac848ec89019c497c98534cad5c5f`，位于 `effective-sampling-observations/controlled-context-20261002T105223753015Z` 与 `controlled-presence-20261002T105301824941Z`。

| 同48K、同源、同seed的四任务 | presence1.5 | presence0 |
| --- | --- | --- |
| 整record错误/部分/完整支持 | 1/3/0 | 2/2/0 |
| JSONparse通过/任务schema通过 | 4/4、3/4 | 4/4、2/4 |
| 选定原文引文逐字匹配 | 18/18 | 22/22 |
| 34项支持/错误/部分/未评 | 1/4/6/23 | 1/5/7/21 |
| 13原子字段支持/部分/未评 | 2/5/6 | 4/5/4 |
| 四调用HTTP合计 | 51.933518s | 54.613078s |
| 整机采样GPU峰值 | 7714MiB | 7714MiB |
| qualityAccepted | false | false |

presence0正确读出VPC不采用及正向触发不生效，且明确承认SCT7(1)人员/电话相同；这些是独立支持的局部改进。但任务2现在明确声称s063证明图纸查阅B1地址，实际s063是其它文件用途、8楼及另一个注册指引地址，构成错误来源归因；任务3仍由广义标题推断Project Architect缺失。任务1主比较换成不相同用途的RSE签图条款，任务4日期正确但主证据仍为标题。0臂任务1支持ID超3且reason超500、任务4支持ID超3，因此schema仅2/4。引文字面正确不等于源命题正确，局部原子字段更完整不自动使整record成立。

48K presence1.5最终源审 `independent_quality/presence15-48k-source-audit-20261002T105254770978Z/evaluation.json` SHA256 `6a98b9b5da626211a69473ae89939b440a48dda82dcc5cf448413de5038b77f4`。presence0最终源审 `independent_quality/presence0-48k-source-audit-20261002T110253282271Z/evaluation.json` SHA256 `9022ebf1e02f5840edf2c21afb6f09caf3d17e79c4a18950bfc7f090652b5150`；完整源审SHA256 `4d7e4c26adc4631bef591377fa91aff2b7609753a6388882930a3663334804ab`；成对语义比较 `presence15_vs0_48k_actual_comparison.json` SHA256 `36ab82dba17a5be728d8d81097648a431915b0f8107e15ca346b24889df0b558`。后一独审保护121份文件，增选s075/s095的原Word单元另核对，既有五文档原OOXML证明复用；标注与答案仍未进入模型。accuracy/precision/recall保持null。

决定保留presence1.5诊断基线，presence0本单次对照没有改善完整审查记录；不能外推所有合同或总体模型能力。48K作为当前资源候选，64,512保留为一次成功的容量记录。这是48K阶段的决定。随后32K KV对照已完成并收尾；最小结构流程仍为prepared，不追加独立容量或框架运行，先执行下述真实44份应用案例。比赛H800更高精度与独立材料复测仍在目标内。

### 用户纠偏与转入实际应用vetting

随后32K的rk2/rk4两个四任务组均实际完成，完整请求对象、输出8192预算和原seed相同，唯一engine差异为KV dtype；这些实验收尾保存，不继续容量/参数探索。用户明确要求“确定bonsai稳定可以运行就去跑vetting，别把目标搞偏了”，因此停止扩展单独5branch工作流准备，转当前真实44文件案例的应用整包审查。

实际新目录为 `tmp/bonsai_vetting_application/full-case-20261002T1133137425760Z`，当前源码已完成163项Java测试（18suite、0failure/error/skip）并成功package到独立immutable jar。原兼容客户端结构化审查未设置输出预算且未验证finish_reason，已补structuredMaxTokens（默认2048、此轮8192）、单choice、非stop明确失败、结构化HTTP retry0、失败模型原文保留；Ollama结构化调用共用可配置预算。普通对话的请求格式保留。测试证明来自模拟协议和真实CPU Java/H2测试，不代表新整包LLM通过。

Bonsai应用服务实际启动18082，模型别名bonsai2-27b-vetting、context/KV32768、rk4v4-e8、graph关闭、no-thinking、prefill128、并发1、vision/MTP/prefix reuse未启用。检索服务实际启动8869，CPU float32/BGE-M3与reranker512/batch4、fresh state、offline；健康端点已读取，模型revision与有效配置已保存。这套应用部署配方与原GPU检索/小模型配方不同，不能只把整包差异归因参数量。

案例已从既有156隔离实例18081一致性备份准备fresh DB/原件目录；新后端18083/PID30784已就绪，44份来源身份绑定完成。数据库104条source metadata中77条有物理原件，但本目标项目实际44份原件（35 tender/5 standard/3 project_fact/1 package_manifest）；不把全库77条称为单项目范围。此轮复用既有解析/表格/OCR结果和原文身份，不能称为本轮重新解析或OCR44份。整包实际调用、任务覆盖、问题/误报/遗漏、审核和报告证据在终态后追加；Gold与原独立矩阵仅供验收，禁止进入模型源集。

### 最后32K KV两臂收尾与应用run启动

最终技术快照为`tmp/bonsai_ninfer_trials/pretrained-runtime-comparison/revision-20261002T113026987154Z`，manifest SHA256 `d544b123f7322b86e98dc01e7726d04fdddc3d189d39371984f875687525af85`。14组、25次实际生成和4组startup失败分别计数；旧快照不覆盖。最后rk2/rk4均4/4 stop、cache0，四条prompt tokens都是10774/7512/7524/5596；output分别144/139/142/220及136/132/144/142。真实wire、messages、四个精确uint64 seed及start/done采样相同，engine仅KV dtype改变。单次HTTP合计51.491631s与47.875629s；整机GPU采样7557/7627MiB、58/53样本，owned RSS约2.16GB。实际Maven开始在rk4 sampler停止22.404651秒以后，没有测试重叠；其他CPU/桌面背景未完全控制，单次wall差不能作性能因果。

独立rk2源审`independent_quality/rk2-32k-source-audit-20261002T112632771954Z/evaluation.json` SHA256 `5567448de605333c9ab25f8a3bacbf1cfff3505b7b59fca1a5b7f5c8f75d10ce`：错误1/部分3/完整支持0，schema3/4；34项支持1/错误4/部分6/未评23，13字段支持2/部分5/未评6。四条正文实际UTF8 SHA逐条与旧48K/60K相同，不是由token数推断。

rk4源审`independent_quality/rk4-32k-source-audit-20261002T113836455736Z/evaluation.json` SHA256 `3eb4341ac5cbbaad0cb59c11c5ccfb88223779df68db033e58998129d7f5bf4c`：局部完整支持1/部分2/错误1，schema4/4、18/18选定引文字面定位；34项支持3/错误2/部分5/未评24，13字段支持6/部分3/未评4。图纸查阅地址与HD officer的局部关系改善，但SCT6仍选标题、officer adoptionClaim缺支持，SCT8由无日期标题归因日期出现退化。两臂qualityAccepted=false，不能外推总体精度或整包质量；各质量审计只追加一次，未改变raw。

应用163项测试证明为`tmp/test_snapshots/structured-native-output-20261002T1127212477119Z/test_manifest.json` SHA256 `fa53317266b98db657739ad50e1261892e86f755a9dafcc68762229d9070bc50`。实际新JAR SHA256 `324d4e11ba4a85f0ab833b76eff20892342c7aa1cc4602822f04fd79252a1d8c`；185 production classes与已编译源码匹配，97 tested source/POM文件逐hash匹配。package本身未重跑163 tests，不将skip-test package当第二次全量测试。

实际run `5355ca9c-093e-4f9c-b879-06d15011a7c3`已在11:45:32Z唯一POST启动。44files API核对通过、规则阶段已运行；本补记时fresh CPU index仍在运行。16主题+3 project references组成19次计划比较、21个progress units，非实际完成数量。统一事件ID `bonsai-full-application-20261002T114532-5355ca9c` 首次完整身份在11:52:48Z补建，明确晚于POST；不会伪造运行前planned。原件身份、SourceHash、已存正文/结构JSON与launch/runner绑定于应用`planning_identity/application-bound-20261002T115248411162Z`，identity SHA256 `a0a120a277e0aa7d8d80ef247e0de8d958c4e4ba448e8b33d1475d915cd36ff6`。后续以实际terminal/API/native日志追加调用、引用、错误、资源与报告证明；quality当前pending，Goal继续active。


### 实际44份应用完成及用户关于模型效果的追问

2026-10-02 12:26:59Z，run `5355ca9c-093e-4f9c-b879-06d15011a7c3` 实际完成21/21、原生19 starts/19 dones，最终7条（3规则+4模型）。此前“index仍在运行/19为计划”的段落是启动时快照；终态明确CPU冷索引Read timed out后采用词项回退，不称BGE/rerank已通过。进入成功模型调用的是22/44份来源、147/13,293个唯一片段，不能将整包来源分母误称完整审查。samplerStopped=true，2,475样本；整机GPU峰7,763MiB、RAM峰24,513,654,784B。12:40:28Z在采样结束后停止仅本任务过期CPU索引父/子进程，partial state与全部原日志保留；该清理不改变原资源样本，不补称index完成。

独立源审最终3条规则有限观察成立；4条模型中2条明确误报、2条局部但问题未充分成立，没有完整源支持的模型缺陷。尤其保险附录的notwithstanding已提交且出现在自身引文中；Marking Scheme完整原Note1在原Word存在，模型把@0切片尾端当作源缺文。程序逐字定位15/15不能证明这些评语真。34项范围是5局部支持/8部分/21未评，13字段只是局部值与用途支持，不能说完整13项合同采用关系通过。evaluation SHA256 `5e7b7b49b663c38655b1cb05d2693e492625b2881d29f1aa161a9ad5995b0659`，qualityAccepted=false。

用户询问相对4B/7B的效果。最新只读比较`tmp/bonsai_vetting_application/model_result_comparisons/20261002T124232198855Z/existing_model_quality_comparison.json` SHA256 `390db9f4a81e15d13162a0c3336e0e3b8f0c3158d7b0916998c8139fc63ee947`逐条核实旧7B与Bonsai32K/rk4的四个user/source任务全文相同，四个system均不同；旧输出4096并有native grammar，Bonsai8192且无native grammar。旧7B为2false/2partial/0full，Bonsai为1false/2partial/1个局部完整支持的图纸地址记录，存在有限局部改善。4B历史仅三个不同范围SCT packs，不能套入四任务统计；其没有源支持的缺陷、只有一条bounded officer观察。跨模型、system、预算、引擎、KV及grammar变动只说明整个配方的已测局部结果，不能得出参数量因果或总体准确率。本轮19-call整包还存在lexical fallback，3规则不能credit给Bonsai。

实际导出Word16页/原生PDF16页已逐页查看、全文核对；原生PDF16页仅warning尾句，EN第8页global fallback前缀混语，当前布局/系统语言未验收。原报告保留不改。新的loopback5175静态前端来自本轮hash一致dist副本，已实际显示完成与回退；这不自动证明人审保存/导出交互已验收。后续只围绕本次实际vetting问题修复与同范围复测，容量/KV实验已经收尾。

## 每一步的实际探针

用户新增要求：“需要在每一步都布下探针，看看效果的瓶颈是在哪一步发生的”。按source/parse/OCR/chunk/embedding/index/dense/BM25/RRF/rerank/context/model/schema/evidence/applicability/export/资源逐步保存输入、原输出、身份、原文范围和失败。GPU实际完整13293+64已完成；179-test Java与27-test Python及15-fixture body proxy已接入13:49:10Z唯一POST的新run `1661b036-c465-477d-9d96-7d548d9c7ca6`。旧解析复用保持0freshOCR、historical elapsed未知；新的逐页OCR和导出内部timer在独立版本补齐，不影响正在运行的配方。

实际已经区分AQCC重排17/top10之外、Note1首块融合68/cap50之外及续块无候选、保险与两定义已完整返回。六重点目标无token截断。新模型前两topic已出现忽略实际submitted删除修改的错误；门禁拦截不能计为模型正确。definitiontopic3两定义实际进入context后，本次正确说明BSI属于SiteStaff且兼CM representative；这只是该局部记录，不称整体定义主题或模型质量通过。完整来源链、实际参数与效果边界见[逐阶段探针文档](vetting-pipeline-observability.md)，Goal仍active且聚焦真实vetting缺口。


## 2026-10-02 全阶段探针终态与当前账号 Codex 对照

1661 run 在14:01:44Z完成21/21、19 native starts/dones、19完整proxy captures，唯一POST。GPU fresh13293-index/64queries预计算完成并独审；应用回放外部SHA封存响应，strictHybrid失败/失配中止，新增解析/OCR/embedding/rerank模型调用0。实际158唯一chunks/26docs提交；不能写成全13,293语义审查。全阶段identity、原输入/输出、实际token features、rank、context丢弃、模型sampling/seed/raw bytes、schema/quote/角色gate、report bytes和资源证据保留。

最终4发现=3保守规则观察+1模型保险unknown-as-issue。独立47 raw审计、87quotes、固定34项和13字段已完成：34为6局部支持/4部分/1反例/23未评，13字段只是局部值/用途支持，qualityAccepted=false，precision/recall/accuracy未知。保险原Word Schedule段617已列A3，但该段未进入prompt；override已正确理解，此轮缺口在context，再由issue分类/risk gate放行。payment忽略submitted Not used和HOMES忽略已提交星号显示模型解读也有瓶颈。AQCC重排17被top10截掉；Note1首块RRF68被candidates50截掉、续块未入候选，重点源目标没有512截断。

应用754.05s；gateway19累计669.82s，64全语料身份hash43.66s，sealed检索HTTP0.558s，16context selection15.69s，Java1539events已测observer1.504s。GPU阶段fresh index receipt239.17s单独计量。嵌套阶段不可相加，probe开启不是净性能基准。应用samplerStopped、753samples，整机GPU有限采样峰7734MiB。Word14+原PDF13共27页逐页实际查看、字段/原bytes核对完成；null省略差44/实质差0、人审字段仍全null，版式保留轻微分页记录。

解析/OCR/corpus/导出内部探针已完成targeted75和full197/23suites（0failure/error/skip），原legacy parse即使配置probe也保持无新增I/O，drafting调用不改变。12文件精确复制并核对120个manifest源码/测试条目；当前实际179 JAR/原输出不替换。配置consense.document.probe-directory、consense.vetting.probe-directory、consense.vetting.export-probe-directory默认off。Typed OCR result不冒称provider transport原body；新软件证明不代表新扫描件OCR精度通过。软件与原调用失败链、JSON artifact命名冲突、旧XML摘要纠正、GBK辅助输出失败均保留。

用户进一步提出强模型API排查，并明确“就用本账号的codex”。已冻结19份实际messages/schema，先选call1/2/3/6/11独立无父历史盲审；只读取该call输入，不读Bonsai输出/gold/额外原件，输出冻结后才评价。当前账号继承模型/推理设置与Bonsai的harness、采样、tokenizer、输出预算不同，不能称严格模型单因素API对照。空array不是理解/覆盖证明；强模型错误同样保留。比赛本地部署及H800复测目标继续active。

证明入口：

- `tmp/bonsai_vetting_application/full-case-probed-20261002T1330Z/planning_identity/actual_terminal_registry_event.json`，terminal event SHA1c448416fcb08b57167a5b6003a11b8e1020b54ab635f93e40609110c3394a3b；pre-POST parameters/inputFingerprints不变。
- `tmp/bonsai_vetting_application/full-case-probed-20261002T1330Z/probes/timing-final-20261002T1413Z/java_probe_timing_summary.json`。
- `tmp/bonsai_vetting_application/independent_quality/1661-final-source-audit-20261002T141853931992Z/evaluation.json`，SHA590bef8fba7afc3c9d16be942ddd3c38d4feb0234f0b685a25db0cb16d1d0ea0。
- `tmp/bonsai_vetting_application/full-case-probed-20261002T1330Z/qa-report-1661-20261002T140916Z/qa_manifest.json`，SHA2c7bc410b96cb1e9b7173918feacfe7aa26db0614b71291bae976f670e3db4e1。
- `tmp/staged_retrieval_cache/java-document-probes-20261002T1334/frozen_proof/final-legacy-isolated-197/manifest.json`，SHA70c31ed93de3fb81cdd6d0c28624d8753e51e4e66af66f822731f0d8bda48a2c；promotion SHAffebe2d0950d82ed5be3b3fc2d48b30b1ca843bf79597f8cfbcb374fb6f81d96。
- `tmp/codex_material_control/prepared-1661-20261002T1419Z/prepared_manifest.json`，SHAc247d315d5ee316bb7628c89f5bf74b7f88d8301407fe49e64c64cc5ce9ae8f8；准备19份不等于已运行19次。

完整证据边界与逐步表见[逐阶段探针文档](vetting-pipeline-observability.md)。此前运行中/待终态段落保留为对应时间快照，由本段实际终态更新当前状态。


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

按“模型稳定后聚焦 vetting”和“每步布探针”的要求，本轮完成已经明确的 DOCX 信息丢失修复，没有扩大 Bonsai 容量实验或凭较大模型引入框架。冻结全包 246 项/针对 35 项测试全部通过；7 个解析及测试文件精确接回仓库，保留旧历史结果。SCC 自动编号 `(e)`、PRE HOMES 22 个 Wingdings 勾选与 provenance 已恢复，删除/划线文本、来源段落/表格位置和 Word 页码未知边界继续保留。实际 PRE 的不支持编号/字体和 AlternateContent 为可见 unknown/PARTIAL，不能宣称整个 Word 布局均已复现。

实际重新解析 source80/95，使用当前生产 chunker 生成 1,859 chunks，完成一次新 CPU float32 BGE-M3 索引和两个真实混合检索/rerank query，再生成当前生产上下文与两个新的 MiniMax-M3 请求。原主题映射是 payment1、HOMES6 → 新 dispatch1/2，旧 definitions3 并非 HOMES。两处修复目标确实保留到 submitted user，但两源范围、CPU dtype、参考来源缺席与10000字符预算均为改变因素；不能把本轮当原19调用纯 parser 单因素比较或44份完整项目审查。

两次 HTTP 成功不等于有效审查：call1 为合法空数组，未再出现旧缺(e)主张，但空数组没有证明付款审查全面正确；call2 的原始 suggestion 少冒号，正式 AiGateway parser 失败，有效 records=null，不能把可读三段算作三条通过记录，也没有人工修响应以扩大成功率。完整源有 SCC22.304(6)，却未进入 HOMES call2 的9个 chunks，后续仍需修必要条件召回及 unknown/absence/scope hints 规则。较强 API 模型排除了 Codex 额外 system 的这一项干扰，仍暴露格式和局部范围理解问题；无法由本轮给出模型与 harness 的单一责任比例。

格式控制下一步用单独、预先登记的实验比较同材料提示词、较简洁输出表示或函数工具调用。当前所查 [官方 Chat Completions 文档](https://platform.minimax.cn/docs/api-reference/text-chat-openai) 列出 function tools，未文档列出 response_format/json_schema；不宣称已验证 MiniMax 原生严格 schema，也不把结构通过当语义通过。检索及 source/quote/条件约束先保持可回放、可核验，再根据实测必要性选择自研或修改开源 runtime。

生产服务及前端未重启，新代码尚未接入旧 UI；本轮没有新应用/报告导出、44源完整run、OCR、Word 物理页渲染或 H800 验证。DocumentParser 共享给 drafting，新 PARTIAL 可能改变其上传及模板状态计数；未修改其他队员 DraftingService，这个边界需在部署时核对。root 最新实时 App Goal 查询为 null，保留过去创建 active Goal 的历史记录，不称目前 active，也未擅自创建新 Goal。项目交付目标仍未验收。

软件、source/检索链、两次原始 API、正式解码及最终模型独审统一入口：[本轮 DOCX 保真结果](vetting-docx-fidelity-20261003.md)。主实验日志与最终 comparison 由 root 单独追加，本补记不改旧冻结配方、响应或评价。
