---
title: ConSense 下一轮 OER／Vetting／Advisory 本地任务图
status: ready-for-agent
labels: [ready-for-agent]
date: 2026-10-12
execution: spec-only; implementation-not-started
---

# 下一轮开发任务图

规格：[OER、Vetting、Advisory 与超算交付](./oer-vetting-advisory-next-development-20261012.md)。本图为仓库现有 Markdown tracker 惯例的新增独立任务，不修改历史 Drafting ticket 或关闭记录。未配置本轮远端 issue tracker，未发布远端 issue。

用户仅要求写 spec，已确认主验收业务链；`ready-for-agent` 是规格／任务就绪标签，不表示本轮开始执行。全部任务当前未开工，不能从既有实验或测试次数推导本轮完成。

## 状态与关闭规则

- `Ready`：规格明确且依赖已满足，仍未启动；`Waiting`：依赖尚未交付；`In progress`：未来实际开始；`Verified`：规定验收有真实证据；`Closed`：依赖和对应验收通过并绑定集成提交／交付身份。
- 后继任务可在已定义契约上提前设计与编写，依赖限制的是集成验收／关闭，不必串行等所有上游实现完成。
- 原件质量、契约替身、真实模型、浏览器与 H800 分别报告；mock 通过不能关闭对应真实质量门，失败和未执行项保留。
- 需要最终质量采用的新候选保持默认不发布，固定05允许带明确缺陷供业务开发；不恢复“全解析最终通过后才开始业务开发”的旧阶段约束。

## 任务及依赖

| ID | 待实现结果 | 规格／验收 | 集成依赖 | 当前状态 |
| --- | --- | --- | --- | --- |
| NEXT-00 | 确定有限组件集合、独立 Linux 锁／制品及扫描清单，记录旧业务版本整改需求；目标运行验收留NEXT-09 | D12–D13；AC19–AC20的制品部分 | 无 | Ready；未开工 |
| NEXT-01 | 文件身份／版本／业务角色与范围路由，以当前规则及05为起点，保护图纸排除、模板隔离、SL技术范围和Drafting | D1–D2；AC01、AC10、AC20 | 无 | Ready；未开工 |
| NEXT-02 | 原生表格候选／逻辑组装、表单和复杂正文共享缺口，独立源评与必要训练判断 | D3–D4；AC02–AC05、AC18源资产部分 | NEXT-00、NEXT-01 | Waiting；未开工 |
| NEXT-03 | 固定／项目共同证据、来源回取、完整组、模型投影与最终token准入契约；可先用05，不等待NEXT-02全部最终通过 | D5–D6；AC06–AC07、AC09–AC10 | NEXT-01 | Waiting；未开工 |
| NEXT-04 | Qdrant Server新collection迁移、全量读回、真实查询与回滚，保Local05 | D7；AC08、AC10 | NEXT-00、NEXT-03 | Waiting；未开工 |
| NEXT-05 | Vetting持久packet恢复、执行owner／幂等、覆盖状态、人工记录和三格式报告；可继续明确owner的现检索入口 | D8–D9；AC11–AC15 | NEXT-03 | Waiting；未开工 |
| NEXT-06 | Advisory显式session／范围、受控历史、逐答案引用与Vetting草稿交接，不另建Agent或memory平台 | D10；AC16–AC17 | NEXT-03 | Waiting；未开工 |
| NEXT-07 | demo到真实Vue交互：文件／条款／finding联动、完整证据、裁决／备注、咨询／流程图；候选源资产与NEXT-02对齐 | D11；AC13–AC18 | NEXT-03、NEXT-05、NEXT-06 | Waiting；未开工 |
| NEXT-08 | 按已确认主链跑官方样本／新来源／真实模型／报告及浏览器，修归因缺陷并更新已有HTML；候选未采用时如实测旧基准并列未过门 | Testing Decisions；AC01–AC18、AC20的业务部分 | NEXT-02、NEXT-03、NEXT-04、NEXT-05、NEXT-06、NEXT-07 | Waiting；未开工 |
| NEXT-09 | 最终Linux制品离线启动、K8s单H800资源交接／恢复、真实模型与来源回取、digest绑定扫描；可提前准备制品，最终验收后置 | D12–D13；AC19–AC20 | NEXT-00、NEXT-04、NEXT-08 | Waiting；未开工 |
| NEXT-10 | 独立Spec／范围与回归复核，汇总实际证据、未知／失败及最终交接，不代替未过质量／H800门 | D1、D14、Testing Decisions；AC20 | NEXT-08、NEXT-09 | Waiting；未开工 |

## 验收证据记录格式

每个执行任务应记录来源／数据／模型／配方身份、源码集成提交、具体场景、预期与实际、失败／skip／未完成项，以及可回取收据。真实质量和运行证据留受控本地／PVC；此图记录简明结论及身份。

| 任务 | 代码／契约 | 原件／检索 | 真实模型 | 浏览器／导出 | H800／制品 | 集成提交与关闭 |
| --- | --- | --- | --- | --- | --- | --- |
| NEXT-00–NEXT-10 | 本轮未执行 | 本轮未执行 | 本轮未执行 | 本轮未执行 | 本轮未执行 | 尚无；仅规格发布 |

现有05入库、实验、候选契约测试和历史训练只作为起点，不能填成上述任务本轮通过。局部证据不确定按受影响事项处理，既有失败保留；后续更新本图和现有handoff／HTML时分别列明实际门。
