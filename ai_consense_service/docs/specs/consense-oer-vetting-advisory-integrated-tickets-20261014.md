---
title: ConSense 统一实施 — 产品任务与受保护 ENG 依赖图
status: ready-for-agent
labels: [ready-for-agent]
date: 2026-10-14
timezone: Asia/Shanghai
tracker: local-markdown
execution: spec-only; no-new-execution-started
---

<!-- implementation-progress-20261015:start -->
> **2026-10-15 实施状态：** 用户已授权统一规格代码实现，下方原日期的 spec-only／未开工文字保留历史语境。当前代码、完整自动化与审查进度见[统一实施记录](../testing/consense-integrated-implementation-20261015.md)。原 08:30 目标未达，完整 Goal 继续；真实模型与赛方原件效果尚未验收，UNI/ENG 均不因局部绿灯自动关闭。
<!-- implementation-progress-20261015:end -->

# 统一任务入口

主规格：[ConSense OER／Vetting／Advisory 统一实施与验收](consense-oer-vetting-advisory-integrated-20261014.md)。引擎子图：[ENG-00–ENG-10](oer-engine-integration-repair-tickets-20261014.md)。产品问题：[PRD01–PRD08](consense-product-issues-agent-handoff-20261014.md)。历史完整范围：[NEXT-00–NEXT-10](oer-vetting-advisory-next-development-tickets-20261012.md)。

本图编排已有需求，不重开历史完成任务，不创建第二套 parser 任务。`ready-for-agent` 表示任务可交接，当前只有文档工作；Advisory 模型／浏览器测试停止状态保持。后续执行遵从当时明确授权、有限预算及实际证据，训练、索引采用、切换和部署不由本图自动触发。

## 状态和依赖语义

- `Ready`：规格可执行，未启动本次任务；`Waiting`：集成依赖待满足；`Conditional`：只在明确条件及准入通过后执行；`Deferred`：用户要求后置。
- 既有实现／契约与待复现／待真实验收分开记录。关闭需要指定版本和该任务全部验收，不能从旧任务的“全部未开工”或局部通过推导当前全链状态。
- 下表依赖约束集成及关闭。契约明确后可提前设计／开发；业务可使用明示身份和缺陷的 fixed05，不等待 OER 全部最终质量通过。新 OER generation 采用及最终整链接受仍等待 ENG-09。
- 真实业务回归可以先测旧基准并报告实际失败；它不能关闭新引擎门。未解除的测试停止状态不因 Ready／依赖满足而自动解除。

## 产品任务

| ID | 唯一负责结果／主规格 | 复用历史范围／验收 | 集成依赖 | 当前状态 |
| --- | --- | --- | --- | --- |
| UNI-00 | 冻结实际 checkout／commit＋dirty、源码／运行／配置、已实现能力与证据缺口，保护固定05／Drafting／停止状态。U0／U11。 | 所有任务基线；原 NEXT-01 身份起点，不重复 ENG-00。 | 无 | Ready；只读基线准备，尚无实现关闭结论。 |
| UNI-01 | 固定／项目／pending 的共同身份与完整证据契约，原件／版本／模块／group／unknown 与回取保持。U1／U3。 | NEXT-03；AC06／07／10；UAC02／10／11。 | UNI-00；与 ENG-01／02／05 对齐同一契约 | Ready；可用 fixed05 接入，候选整合不提前接受。 |
| UNI-02 | canonical→索引候选→完整结构闭包→模型材料逐跳审计及最小缺口修复。U3。 | PRD07 下游段；NEXT-03；UAC02。 | UNI-01 | Waiting；与 ENG-05 共用责任界面，不另写 parser。 |
| UNI-03 | 同实际 profile／wire 的预算、reasoning、输出与总修复契约；有限实验计划，先核离线。U4。 | PRD04；PRD03 准入；NEXT-03；UAC03。 | UNI-01 | Waiting；真实调用未启动，既有停止状态保持。 |
| UNI-04 | Vetting 实际材料提交、覆盖与应发现集合；checkpoint／owner／幂等／进度／恢复，人工版本记录及同 snapshot 报告。U5／U6。 | PRD03；NEXT-05；AC11–15；UAC04–06。 | UNI-02、UNI-03 | Waiting；先核已实现能力，新版真实完整审查未验。 |
| UNI-05 | Advisory 自然问题／T6、命题来源语义、有限分析、已核单元／前缀／保存及历史；实际运行规范身份。U7／U8。 | PRD01／02／06；NEXT-06；UAC07–09／11。 | UNI-02、UNI-03 | Waiting；A1–A4 为已有起点，A5 未接受，先离线定位。 |
| UNI-06 | 完整 PDF／DOCX 引用阅读、唯一 native 定位、原图／整表、真实 Vue 联动及竞态。U8。 | PRD05；NEXT-07；AC13–18；UAC10／11。 | UNI-01；最终跨模块联动等 UNI-04／05 | Waiting；阅读开发可与业务修复并行，三类真实链分别待验。 |
| UNI-07 | Qdrant Server 新 collection manifest 全量验证、真实 query、原件回取与回滚／单 owner。U10。 | NEXT-04；AC08；UAC13。 | UNI-01、UNI-02；使用准确冻结来源及向量 | Waiting；fixed05 可作迁移源，活动切换另有执行边界。 |
| UNI-08 | 四类同题输入及 raw 重放归因，独立应发现／无问题／新来源，后续有限模型／浏览器／报告业务验证。U9。 | PRD08 及 PRD01–07 开放业务门；NEXT-08；UAC02–12。 | UNI-04、UNI-05、UNI-06；真实执行前需明确有限授权 | Waiting；fixed05 可分项测，未通过 ENG-09 不称新引擎最终业务接受。 |
| UNI-09 | 独立 Linux 锁／模型／字体／镜像、MySQL 迁移恢复计划、扫描清单及离线制品准备。U10。 | NEXT-00；NEXT-09 准备段；AC19／20；UAC14 制品段。 | UNI-00；最终制品绑定各模块集成版本 | Ready；不因准备启动活动迁移／部署。 |
| UNI-10 | 在最终制品上验 MySQL／Linux 断网、K8s 单 H800 真交卡、加载／ready／峰值／恢复和扫描，按授权采用。U10。 | NEXT-09；AC19／20；UAC14 目标段。 | UNI-07、UNI-08、UNI-09、ENG-09；目标执行须具体授权及计划 | Waiting；目标真实接受未完成，不能用本机成绩关闭。 |
| UNI-11 | 独立检查 EAC／PRD／AC／UAC 全量状态、已采用身份、旧正确及失败；更新交接／既有日志，分别交付产品与引擎结论。U0／U9／U11。 | NEXT-10；全 EAC、PRD、AC、UAC。 | ENG-09、UNI-08、UNI-10；未通过门保持开放 | Waiting；整体 Closed 需全部规定门，不删除未知换完成。 |

## 受保护 ENG 工作线

原 ENG 图是引擎唯一工作清单，任务内容、原 EAC 和状态不在本表重写。以下仅给总体接入点：

| 引擎任务 | 原依赖保持 | 总体接入与保护 |
| --- | --- | --- |
| ENG-00 | 无 | 保首批局部修复与主／交付差异；UNI-00 不重复实现。 |
| ENG-01 | ENG-00 | 观察先行、身份／阅读规则及无 metadata 候选；与 UNI-01 对齐。 |
| ENG-02 | ENG-00；同 ENG-01 契约 | 真上游到 Java 的兼容 layout／frame／provenance；不能只改 DTO。 |
| ENG-03 | ENG-01／02 | 混合 coverage、完整上下文与同源坐标；正确 native 保持。 |
| ENG-04 | ENG-01／03 | 完整表单／整表／复杂正文／Word，保双完整解析及整组采用。 |
| ENG-05 | ENG-03／04 | normal parser→canonical→consumer，和 UNI-01／02 分清首次丢失位置。 |
| ENG-06 | ENG-00，可并行 01／02 | 隔离重验每仓原 19 canonical 受阻项，不动活动系统网络。 |
| ENG-07 | ENG-01–06 | 完整来源诊断与 EAC01–22 验收；未过质量不以 fixture／产品单题关闭。 |
| ENG-08 | ENG-07 实际归因＋数据／计划准入 | Conditional；残差诊断完成不等最终质量通过。必要训练保历史三次无收益规则，无残差时记录不需要而非虚构运行。 |
| ENG-09 | ENG-07；必要时 ENG-08 实际结论 | 独审与原质量门；新引擎采用及 UNI-10／11 最终整链关闭依赖此门。 |
| ENG-10 | ENG-09；关键残差不得被扩版掩盖 | Deferred；引擎修好后 SL 版本工作，不是 UNI-01–11 的额外前置，不自动入训。 |

## 可并行范围与防重复

UNI-00 固定基线后，ENG-01／02／06、UNI-01 契约和 UNI-09 制品准备可并行。共同契约明确后，UNI-02 的下游闭包、UNI-03 的预算与 profile 可并行；UNI-04 Vetting、UNI-05 Advisory、UNI-06 阅读可分模块推进。共享文件由一个实施 owner 集成，另一工作线通过契约和验收反馈，不能各自改出不同来源模型。

引擎的 parser→canonical 保真由 ENG-05 负责，canonical→检索→wire 由 UNI-02 负责；最终 wire 准入由 UNI-03 负责，业务语义／完整发现由 UNI-04／05 负责。同一缺陷只建立一个修复 owner，跨层验收分别记录。业务源码或契约完工不能关闭 ENG；来源质量过门也不能关闭业务答案、报告、浏览器或目标部署。

## 验收账本和报告格式

每项至少记录任务／验收 ID、实际源码和 dirty 身份、来源／generation／模型／prompt／配置、范围与原分母、期望／实际、旧正确回归、证据地址和未过门。证据列为代码／契约、真实模型／wire、原件／独审、业务／浏览器／导出、MySQL／Linux／H800／扫描，未执行保持未执行；未知不记零错。

已有主工程 87／交付 112 及 canonical 每仓 19 受阻、A1–A4 已实现／A5 失败已停等是原文档状态。本图没有重跑、核 PR 或活动构建；当前不能填为 UNI 或 ENG 的新增通过。实际执行后更新本图与来源子图的相关条目，保留旧失败和原验收编号。
