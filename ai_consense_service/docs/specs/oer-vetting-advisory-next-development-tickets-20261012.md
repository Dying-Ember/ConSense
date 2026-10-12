---
title: ConSense 下一轮 OER／Vetting／Advisory 本地任务图
status: in-progress
labels: [implementation-in-progress]
date: 2026-10-12
execution: implementation-in-progress; authorized-2026-10-13
---

# 下一轮开发任务图

**2026-10-13 执行增量：** 用户已授权 implement-spec 实现，截止今天北京时间08:30准备本地代码，09:00由用户提交GitHub。Ponytail安装和hooks启用验证在代码修改前完成；当前在隔离工作区并行实施。下方“未开工”表为2026-10-12发布快照，当前逐任务提交与分门证据以[实施状态](./oer-next-implementation-status-20261013.md)为准；未验收任务不提前关闭。

规格：[OER、Vetting、Advisory 与超算交付](./oer-vetting-advisory-next-development-20261012.md)。本图为仓库现有 Markdown tracker 惯例的新增独立任务，不修改历史 Drafting ticket 或关闭记录。未配置本轮远端 issue tracker，未发布远端 issue。

2026-10-12发布时用户仅要求写spec、任务为ready-for-agent。2026-10-13实现授权已覆盖该历史状态；下表按02:40实际收据更新，不把既有实验、软件测试次数或局部有限样本当本轮整任务完成。

## 2026-10-13 06:31 当前实施状态（任务未提前关闭）

代码69c3e091e5e2c9ac7c467d8ecb2032a5a4600ad6。源码69c3e09完整Maven275 suites／2736 tests／0 failure／0 error／111 skipped、实际exit0；新jar79426422bytes、SHA9048035d6bff4aaf6a12ed139fa6f4f5ccad79c91dfb1777a610a9c305072fc5，mtime06:11:23。后续报告文档HEAD不冒重新编译。源码bdf真实SCC run05 bec47b60完成34/34、32可靠包及2发现；34raw/attempt含2retry，26有效record中21被拒、5机械接受，仅1candidateEligible；17主题全部partial。代码69完整构建已通过，本轮完成本地提交准备；具体冻结HEAD与时间以外置final pack manifest为准。目标今天08:30准备本地代码，09:00由用户提交GitHub，未push。现有HTML与ledger追加R120–R152，152轮／908证据／41文档卡／438旧指标；旧119轮／783证据定义／41卡／438指标及全部documentCatalogue、comparisons、training和失败逐项保持。仅准确非密钥收据逐SHA复制到忽略的report-evidence目录，不搬原件、模型、密钥或运行DB。 下方02:40表格保留历史语境，当前状态见本表及[实施状态](./oer-next-implementation-status-20261013.md)、[handoff](../HANDOFF-ocr-embedding-rerank-20261008.md)、[ledger](../testing/oer-progress-ledger.json)。

| 任务 | 已保存的实际增量 | 当前未通过门与采用边界 |
| --- | --- | --- |
| NEXT-00／09 | Linux82 hash离线CPU导入；本轮无新依赖 | 最终OCI/字体/license/漏洞扫描/PVC/H800未过 |
| NEXT-01／03 | D4 v6 compact/binary64、native262144/provider/thinking fail closed、69完整2736/0/0/111 | 全源/必要证据/actual native exact-template未认证；111skip保留 |
| NEXT-02 | 226Stream主672/672、源监督4/5；GCT visible8/8/blank3/3/strictAP7/8 | AC02=false；Window续注/父条legal/flat/scanned unknown、候选未正式采用 |
| NEXT-04 | Serverformal05 LF207闭合/3Java问/full20700seal、Local复制输入10行 | 09d201exact/6CRLF closure=false；题3跨专业，semantic/PVC/H800未过、未采用 |
| NEXT-05 | SCC源码bdf34/34、32可靠包/2发现；UI/reload/restart/stale两门真实验证 | 26records仅1eligible，17topics partial；人工非QS，prepared/reservation/outcome未独证 |
| NEXT-06／07 | 原GCC有限两轮/原引用canvas；69新实际图/history8旧6逐字/2generation含1retry | reference_only/采用未确认；完整官方业务/新来源/Q10/legal未过 |
| NEXT-08 | 同manual export56e16三格式/222cells/PDF47overview/36fullsize，原件SHA回取 | jobSnapshot07a1分列；opaque人工复核、Word NOT_RENDERED，QS/source/legal未过 |
| NEXT-10 | 准确69完整build、有限两轴0open、旧119/783/41/438及失败精确保存 | 不认证整spec；默认CRLF exit2保留/policy exit0；准确冻结身份见外置final pack manifest |

真实UI备注/核对/复开及新runtime回读保持；actionTaken实际空字符串、addendumRequired=null，不作QS裁决。过严handled-reload null断言失败保raw，随后按实际blank serialization verified；4104/4123拒写0accepted，前后findings/checklist各自SHA相同；重启run字段变化不称全run byteexact。 独立账本1361原文件SHA manifest/248textArtifact相符，194uniqueSCCchunks、975项目selected-content occurrences/170fixed projections完整保留；语义194/229块、174617/186855字符来自terminal，enriched content176564不同分母不混算。9comparison generation requests与run32distinct generationEntityHashes匹配0；reservation/prepared/generation outcome未独证，不推算网络次数。17topics partial/qualifiersunknown，VT002专业结论unknown，language trusted origin未证明；预算只provider estimate，非exact template/H800峰值。 完整源/gold、Window续注/父条法律范围、flat/scanned forms、官方整套业务/QS法律裁决、Word物理分页/视觉、opaque建议人工条款复核、Server语义gold/最新working-byte运行闭合、PVC、最终OCI/字体/license/漏洞扫描及单H800交接恢复仍未通过。主脏worktree/图纸排除/Drafting冻结/05原plan/原件/模型/密钥保护，未push/远端issue/PR/生产部署；所有NEXT保持In progress。

**末次独立复核：** 26图文件SHA、旧6完整history、两实际wire的系统append与counter核心、275XML及JAR904803…已再核。代码默认diff-check340提示全部新增CR行，16000新增行真实末尾space/tab0；显式Windows CR-at-EOL等policy通过，默认exit2日志保持，不改EOL。累计23既有收据/18修补或后续账目（并非18finding）核69 ancestry；已审代码范围Standards/Spec 0open，不扩大为整spec认证，所有真实剩余门保持。

## 状态与关闭规则

- `Ready`：规格明确且依赖已满足，仍未启动；`Waiting`：依赖尚未交付；`In progress`：已实际开始，仍有规定子门未通过；`Verified`：规定验收有真实证据；`Closed`：依赖和对应验收通过并绑定集成提交／交付身份。
- 后继任务可在已定义契约上提前设计与编写，依赖限制的是集成验收／关闭，不必串行等所有上游实现完成。
- 原件质量、契约替身、真实模型、浏览器与 H800 分别报告；mock 通过不能关闭对应真实质量门，失败和未执行项保留。
- 需要最终质量采用的新候选保持默认不发布，固定05允许带明确缺陷供业务开发；不恢复“全解析最终通过后才开始业务开发”的旧阶段约束。

## 任务及依赖

| ID | 待实现结果 | 规格／验收 | 集成依赖 | 当前状态 |
| --- | --- | --- | --- | --- |
| NEXT-00 | 确定有限组件集合、独立 Linux 锁／制品及扫描清单，记录旧业务版本整改需求；目标运行验收留NEXT-09 | D12–D13；AC19–AC20的制品部分 | 无 | In progress；82包实际Linux hash离线安装／CPU导入通过，最终OCI／扫描／字体及全部目标制品尚待；旧94包含3内嵌ONNX候选拒绝保留 |
| NEXT-01 | 文件身份／版本／业务角色与范围路由，以当前规则及05为起点，保护图纸排除、模板隔离、SL技术范围和Drafting | D1–D2；AC01、AC10、AC20 | 无 | In progress；图纸早期身份排除／原件SHA守卫已实现，真实SCC tender上传通过；完整范围业务／全回归仍待 |
| NEXT-02 | 原生表格候选／逻辑组装、表单和复杂正文共享缺口，独立源评与必要训练判断 | D3–D4；AC02–AC05、AC18源资产部分 | NEXT-00、NEXT-01 | In progress；完整226候选Stream主格672/672／5/5、源监督组4/5；Window续注／父条法律范围unknown，未正式采用；表单／复杂正文全门未关闭 |
| NEXT-03 | 固定／项目共同证据、来源回取、完整组、模型投影与最终token准入契约；可先用05，不等待NEXT-02全部最终通过 | D5–D6；AC06–AC07、AC09–AC10 | NEXT-01 | In progress；共同身份／原件GET／最低262144与未知预算fail closed已实现；真实咨询有限预算和回取通过，完整必要证据及实际精确template未验 |
| NEXT-04 | Qdrant Server新collection迁移、全量读回、真实查询与回滚，保Local05 | D7；AC08、AC10 | NEXT-00、NEXT-03 | In progress；20700点全payload／float32hash／cold seal、3self-vector＋3PDF／2表回取通过；自然语言语义、排序及PVC未验，候选未采用 |
| NEXT-05 | Vetting持久packet恢复、执行owner／幂等、覆盖状态、人工记录和三格式报告；可继续明确owner的现检索入口 | D8–D9；AC11–AC15 | NEXT-03 | In progress；V17 owner／lease／checksum／report snapshot及recovery-v3已集成，5 child JVM恢复边界契约通过；真实SCC run／五状态／人工保存／三格式导出仍待 |
| NEXT-06 | Advisory显式session／范围、受控历史、逐答案引用与Vetting草稿交接，不另建Agent或memory平台 | D10；AC16–AC17 | NEXT-03 | In progress；V16／V18、范围session／有界history／逐答案引用已集成；GCC2.1真实两轮SQL／history逐字／原页通过有限样本，官方整套与草稿业务仍待 |
| NEXT-07 | demo到真实Vue交互：文件／条款／finding联动、完整证据、裁决／备注、咨询／流程图；候选源资产与NEXT-02对齐 | D11；AC13–AC18 | NEXT-03、NEXT-05、NEXT-06 | In progress；126/126＋build／mjs12/12，真实咨询session往返与物理17页canvas已验；Vetting业务联动／人工动作／导出、完整Q10图表资产尚待 |
| NEXT-08 | 按已确认主链跑官方样本／新来源／真实模型／报告及浏览器，修归因缺陷并更新已有HTML；候选未采用时如实测旧基准并列未过门 | Testing Decisions；AC01–AC18、AC20的业务部分 | NEXT-02、NEXT-03、NEXT-04、NEXT-05、NEXT-06、NEXT-07 | In progress；真实咨询有限样本、SCC上传与既有HTML逐SHA已执行；官方／新来源整链及Vetting／报告尚待，Java两轮全回归失败保留 |
| NEXT-09 | 最终Linux制品离线启动、K8s单H800资源交接／恢复、真实模型与来源回取、digest绑定扫描；可提前准备制品，最终验收后置 | D12–D13；AC19–AC20 | NEXT-00、NEXT-04、NEXT-08 | In progress（制品准备）；retrieval Linux smoke通过；最终OCI／扫描／离线目标启动／K8s PVC／单H800交接恢复未执行，不关闭AC19–20 |
| NEXT-10 | 独立Spec／范围与回归复核，汇总实际证据、未知／失败及最终交接，不代替未过质量／H800门 | D1、D14、Testing Decisions；AC20 | NEXT-08、NEXT-09 | In progress；早期独立审查已发现并修原件／版本／预算与制品绑定问题；最终HEAD独审／全回归／08:30交接尚待，无任务提前Closed |

## 验收证据记录格式

每个执行任务应记录来源／数据／模型／配方身份、源码集成提交、具体场景、预期与实际、失败／skip／未完成项，以及可回取收据。真实质量和运行证据留受控本地／PVC；此图记录简明结论及身份。

| 任务 | 代码／契约 | 原件／检索 | 真实模型 | 浏览器／导出 | H800／制品 | 集成提交与关闭 |
| --- | --- | --- | --- | --- | --- | --- |
| NEXT-00／09 | 82 Linux wheels离线hash安装／pip check／CPU导入；逐包hash绑定与python -O边界复验 | 无原件质量推定 | 新smoke模型／DB／GPU／OCR构造均0；原始模型publication保全 | 未执行目标启动 | 82包4066465995 bytes；旧94包3内嵌ONNX误下载候选拒绝；OCI／扫描／字体／H800未过 | `18e05e3`及后续校验器修复；未Closed |
| NEXT-01／03 | Python共同软件闭合49；真实HTTP规则原件54；所有新生成最低262144、预算未知拒绝 | 原件SHA冷守卫、corpus row及完整引用守卫；完整源／必要证据未过 | M3有限咨询provider estimate；exactTemplateObserved=false | 固定原PDF SHA／物理17页canvas实核 | H800未过 | 原件修复`34c1975`、全预算／recovery-v3 `de4a603`；未Closed |
| NEXT-02 | 完整226正常候选及共享Notes／星号软件控制；0权重更新 | Stream672/672主格／5/5主表、源监督组4/5、11/12成员、86/87关系；首完整组0/5保留；父条／Windowunknown | 未新增解析模型调用 | 完整Q10图表业务未验 | 未采用／未切05 | 主网格`38c6c21`及条件组候选；未Closed |
| NEXT-04 | 独立Server新collection迁移、回滚／cold seal | 19371父／20700点全payload／float32hash相等，3PDF／2表回取 | 3self-vector为存储探针；自然语言BGE查询未验 | 无业务推定 | PVC／H800未过 | `11ca229`候选；未采用／未Closed |
| NEXT-05 | V17 owner／lease／checksum packet／report snapshot；5child JVM恢复边界；人工过期写守卫 | SCC真实tender已上传799positions／186855chars，未运行完整审查 | 真实SCC Vetting尚待 | 五状态、人工保存、JSON／PDF／DOCX尚待 | H800未过 | `09d1857`、`de4a603`；未Closed |
| NEXT-06／07 | V16 session／V18 85字evidenceId；Vue126/126＋build、mjs12/12 | GCC2.1逐答案引用／物理页／session往返，reference_only且采用未确认 | 首问＋跟问原样SQL保存、wire历史逐字；跟问16866／339 tokens；首缺证／SQL失败保留 | 原页canvas与DOM已实核有限样本；全业务／草稿交接／导出尚待 | H800未过 | `4516a96`、`56771d6`、`f5ee146`、`69693b9`；未Closed |
| NEXT-08／10 | Java首全跑2631／25failure／103error／123skip；第二2701／1／86／111，均exit1，补跑pending | 源监督同域有限评分与历史438文档metrics保持 | 官方题／新来源全套未验 | 现有HTML119轮／783逐SHA／41卡／438metrics已生成；报告浏览器未验 | OCI／扫描／H800未验 | 不相加有重叠契约；最终独审／冻结待完成，无任务Closed |

各项完整来源／路径／SHA／运行收据见[ledger](../testing/oer-progress-ledger.json)R114–R119与[handoff顶部](../HANDOFF-ocr-embedding-rerank-20261008.md)。现有05入库、实验和历史训练只作为起点；本轮局部证据不替代整任务关闭。报告外部收据`progress-delivery-render-receipt.json`保存源码根／证据根差异和全部783份证据的实际SHA验证，无模型／运行DB读取；旧113轮／746证据／41卡／438metrics和失败逐项保持。后续补跑或真实Vetting结果须绑定新的实际收据更新，不能把pending写成通过。
