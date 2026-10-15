---
title: OER 解析引擎整合修复 — 本地任务图
status: ready-for-agent
labels: [ready-for-agent]
date: 2026-10-14
tracker: local-markdown
execution: spec-only; no-new-implementation-started
---

<!-- implementation-progress-20261015:start -->
> **2026-10-15 实施状态：** 用户已授权统一规格代码实现，下方原日期的 spec-only／未开工文字保留历史语境。当前代码、完整自动化及两轴审查进度见[统一实施记录](../testing/consense-integrated-implementation-20261015.md)。原 08:30 目标未达，完整 Goal 继续；赛方原件前后对照尚未执行，ENG 来源质量门均未因契约测试而关闭，ENG-10 仍后置。
<!-- implementation-progress-20261015:end -->

<!-- unified-spec-entry-20261014:start -->
> **2026-10-14 当前总体实施入口：** [统一实施与验收 spec](consense-oer-vetting-advisory-integrated-20261014.md)及[统一任务图](consense-oer-vetting-advisory-integrated-tickets-20261014.md)。OER 原 D1–D10／EAC01–22／ENG-00–10 按[受保护引擎子规格](oer-engine-integration-repair-20261014.md)完整保留；产品问题 PRD01–08 与旧总 AC01–20 在统一入口逐项承接，业务成功不关闭 OER 来源质量门。下方原正文与状态保留原语境，已实现起点不重列未修，未验收／失败不追认通过。本次只调整规格与导航，Advisory 已停止测试保持，不启动实现、模型、训练、索引、自动 Goal 或部署。
<!-- unified-spec-entry-20261014:end -->

# OER 解析引擎整合修复任务图

规格：[OER 解析引擎整合与来源保真修复](oer-engine-integration-repair-20261014.md)。本图沿用项目本地 Markdown tracker 和 ready-for-agent 标签，细化原总任务图 NEXT-01／NEXT-02 的引擎部分及必要的完整来源接口；不重开其他模块或追改既往任务关闭状态。

ready-for-agent 表示规格和依赖已可交给执行者，不代表本次文档请求启动实现、训练、索引或部署。第一批代码修复已经发生，按既有证据记录，不改写为本次 spec 完成或整个引擎通过。

| 任务 | 交付行为与规格范围 | 依赖 | 当前状态 |
| --- | --- | --- | --- |
| ENG-00 | 保留第一批身份／scope／目录／metadata pending 修复、原件／fixed05及 dirty 起点；维持主／交付能力差异。D1–D3、D10；EAC01–EAC04、EAC20。 | 无 | 已有局部实现及契约证据；整体来源未验收，不重复实现。 |
| ENG-01 | 在正常入口落实初步观察先行和 Java 身份／版本判断，补无 metadata 图纸候选的身份级分流；首轮观察真实覆盖，pending 可见且不细读。D1–D3；EAC03–EAC07。 | ENG-00 | Ready；本次未开工。 |
| ENG-02 | 将真正上游 OCR／layout 观察经兼容接口传入 Java，保留原文、非文字区域、polygon／frame／provenance；旧文字框契约不回退。D4；EAC07–EAC08。 | ENG-00；与 ENG-01 对齐同一观察契约 | Ready；本次未开工。 |
| ENG-03 | 按实际区域补混合页缺口，保完整输入上下文；验证同源、变换、复用和冲突保留，避免整页覆盖旧正确。D5、D7；EAC07、EAC09–EAC11、EAC15。 | ENG-01、ENG-02 | Waiting。 |
| ENG-04 | 将身份阅读规则落实为完整表单、逻辑表格／条件组、复杂正文和 Word story／修订结构；不写固定样本答案或坐标。D6–D7；EAC11–EAC15。 | ENG-01、ENG-03 | Waiting。 |
| ENG-05 | 经正常 parser 持久化完整 units／parts／groups／relations／unknown，验证 consumer 和原件回取保真；审计与紧凑模型材料分层。D8；EAC16–EAC18、EAC20。 | ENG-03、ENG-04 | Waiting。 |
| ENG-06 | 在隔离测试环境重验现有 canonical 初始化受阻项和来源／角色守卫，不删断言、不改活动系统网络；单列环境与业务断言结果。Testing Decisions；EAC16、EAC20。 | ENG-00；可与 ENG-01／ENG-02 并行 | Ready；每仓原 19 项仍未验证。 |
| ENG-07 | 沿已确认正常上传／解析／回取主接缝，对完整适用来源、旧正确、不同作者和独立来源验收；实际模型、浏览器与契约分列。D9；EAC01–EAC22。 | ENG-01–ENG-06 | Waiting；当前没有整链或原件质量通过结论。 |
| ENG-08 | 如仍有已归因模型残差，先完成完整监督及训练／校准／评测隔离、运行计划和有限预算，再做必要固定对照；连续三次可比实际既无来源质量收益也无关键缺陷减少则复盘。D9；EAC19、EAC21。 | ENG-07 的实际归因及数据准入；不以准备动作重置历史 | Conditional；未准入训练，不自动启动。 |
| ENG-09 | 汇总独立规格／范围／回归复核和实际来源验收；同步既有质量日志与交接，明确仍未过门。D9–D10；EAC19、EAC21–EAC22。 | ENG-07；存在必要 ENG-08 时等待其实际结论 | Waiting；原质量门不缩减。 |
| ENG-10 | 引擎修好后恢复 SL 官网版本取得与对照，按原件 SHA／版本／来源谱系隔离并做重复检查，不覆盖 fixed05、不自动入训。D10。 | ENG-09；已知关键残差不得被版本扩展掩盖 | Deferred；用户明确后置，当前仅目录登记。 |

## 主验收与关闭要求

- 使用现有真实上传／正常解析／持久化来源／原件回取接缝；不得以额外实验入口绕开最终 parser 或 consumer。
- 每项保留实际输入与来源、版本、模型／配置、动作、期望／实际、完整范围、错误／未知及失败／未执行证据；关键反例先证明缺陷再验证最小修复。
- 契约与内存数据库、真实模型执行、原件质量、不同作者复核、浏览器和目标运行环境分别记录；不得互相替代。
- ENG-07／ENG-09 的来源门包含普通 CER≤0.1%、关键零错漏、完整表单／表格／跨页及定位、旧正确和独立来源；未测／缺证不写零错误，不缩分母。
- source-reviewed canonical 框、读序、拓扑和关系的采用状态独立标明；不计为自动新来源预测或用暴露参考给 producer 自评分。
- 训练只进入 ENG-08 条件任务；发布权重、重建活动索引、服务切换、部署或自动 Goal 不由本图启动。

## 当前证据起点

| 范围 | 已有状态 | 后续门 |
| --- | --- | --- |
| 主工程首批边界修复 | 87 项相关测试通过。 | 观察／布局／补读／完整语义与正常链来源验收未完成。 |
| 独立交付首批边界及 metadata pending | 112 项相关测试通过。 | 无 hint 图纸身份仍未自动确证，不推广为主工程已支持。 |
| canonical 契约 | 每仓 19 项环境初始化失败，未到业务断言。 | ENG-06 实际重验；现状保持未验证。 |
| 前端 | 两仓类型检查与三语离线组件检查通过。 | 正常运行构建及浏览器验收未完成。 |
| SL | 当前官网目录已登记，官网大 PDF 下载及全页版本比较未启动。 | ENG-10 后置，不改变训练／评测划分。 |

以上为 spec 编制前已保存证据，详细引用在规格 Further Notes。本次仅静态检查规格、任务依赖和链接；没有重跑上述测试或执行任何任务。
