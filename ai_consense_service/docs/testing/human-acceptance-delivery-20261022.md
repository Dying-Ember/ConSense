# ConSense 三模块验收入口

**Drafting导航热修已生效：** [原第二步已合并到来源文件页](drafting-identification-step-merge-20261022.md)，现为四步；“下一步：识别模板要求”按钮已移除。识别及人工采用机制保留，旧identify页面状态返回来源页。Typecheck及19项测试通过，真实浏览器已核四个导航和合并入口；0新模型／OCR／上传。本次未补DOCX视觉处理链，高级识别按钮文案仍待简化。

**当前手测解析能力校正：** [DOCX上传生产链缺口](docx-upload-production-gap-20261022.md)已核实：当前只做原生POI／OOXML读取，尚未接入自动转PDF、双视觉对照及页码映射，后台没有补跑。Sean项目18份DOCX均页数0、OCR未用；PARSED不代表完整视觉核验。此前“整体没有重复实现”的笼统保证撤回，公共compact及Drafting提取后继仍未完成。上传状态样式已在5177变得更明显；第二步交互含义不清已记录，识别逻辑未改。

**2026-10-22 19:25后继：** [Drafting NTT上传数据库写入热修](drafting-template-upload-hotfix-20261022.md)已部署，当前后端PID38868／64GiB、前端5177保持。相同原件在隔离项目真实上传及刷新回读通过，40项组合回归通过；原来源／草稿身份状态保持。用户原项目的失败上传未自动重放，刷新后可重新上传继续手测；本次未新验最终文件或完整审查报告，Goal仍paused。

**2026-10-22 18:30 手测环境已恢复并部署本批公共 OER 接线。** [当前交接、回归失败与数据库恢复范围](oer-demand-access-handoff-20261022.md)为现行状态，前端仍为 `http://127.0.0.1:5177/`。数据库恢复到02:56检查点：23项目、80专项变量、35件来源（17 PARSED／14 PARTIAL／2待确认／2排除）；上午三份新保存稿及咨询52不在该数据库中，原下载文件保持在下列本地路径。不要把下文重启前runtime37／新任务／保存稿页面当作当前已恢复状态。用户接下来手测，再按反馈热修；不是本轮已全部验收通过。

**2026-10-22复用后继：** [公共能力复用热修](shared-capability-reuse-hotfix-20261022.md)已完成离线集成（160业务通过／1原件skip、57公共hash、Python132及接缝42通过），历史PRE全部10,930单位回放一致；尚未恢复服务或业务H2、未迁移旧binding，不构成新的前端／模型／最终文件验收。Goal工具paused，远端推送仍受凭据阻断。

**现行补充：当前Goal工具paused；用户另行授权的[来源内存热修](source-evidence-memory-hotfix-20261022.md)已完成本轮离线验证。** 同一历史PRE图480.5MB→238.5MB，全部10,930单位展开内容逐项相同；Python30项通过、Java257项覆盖经组合与唯一旧断言后继收口。未重新封存当前PRE、未恢复服务／迁移旧binding；GitHub凭据失效，推送待恢复。下方active／已部署／运行任务保留为重启前历史，不能作为当前在线入口。

**当前在线状态校正（2026-10-22 12:35）：两次桌面卡死后的Windows重启已使原前后端、OER和H2内存库退出，下方地址暂不能验收在线流程。** 六份已下载文件及原件仍在；来源／稿件／咨询记录恢复正在盘点，尚未恢复数据库或重启重任务。下文runtime37／RUNNING是重启前历史，不能作为当前状态。根因只读核对见`.local/desktop-freeze-20261022/ASSESSMENT.md`，不将旧WER报告或堆上限当作本次卡死的因果证明。

状态：准备交付中，Goal保持active。Drafting六份文件与Advisory已保存问答可以查看；PRE生产保存／完整回读及来源准备冻结顺序修复已过，runtime37已部署。真实35件新任务已唯一启动并取得句柄，尚无正式整套报告；本文不代表Goal已完成。

## 打开前端

[ConSense 本地前端](http://127.0.0.1:5177/)。模型默认选择 **Qwen3.8 27B**，页面也保留 Flash-Next 选项。

| 模块 | 在顶部选择的项目 | 当前可以查看的内容 |
| --- | --- | --- |
| Drafting 起草 | 合约填写专项验收（临时项目） | 已采用输入、待填内容、三份草稿预览及 Word／PDF 下载 |
| Vetting 审查 | 真实2b项目验收-恢复20261021 | 已上传35件资料与当前审查进度；报告尚未完成 |
| Advice 咨询 | Qwen controlled source verification | 已保存的真实27B问答；选择历史会话后可打开引用原件 |

## 已有起草文件

以下是已经从实际前端下载、且本次后端升级前后保持相同修订的文件。SCC 使用正文修改后已经恢复的当前保存稿；NTT／SCT复用未改变的当前下载，不重新生成。

| 文件 | Word | PDF |
| --- | --- | --- |
| NTT | [打开 NTT Word](<D:/R&D/ConSense/.local/runtime-integration34/downloads01/NTT-current.docx>) | [打开 NTT PDF](<D:/R&D/ConSense/.local/runtime-integration34/downloads01/NTT-current.pdf>) |
| SCT | [打开 SCT Word](<D:/R&D/ConSense/.local/runtime-integration34/downloads01/SCT-current.docx>) | [打开 SCT PDF](<D:/R&D/ConSense/.local/runtime-integration34/downloads01/SCT-current.pdf>) |
| SCC | [打开 SCC Word](<D:/R&D/ConSense/.local/runtime-integration34/downloads02/SCC-current.docx>) | [打开 SCC PDF](<D:/R&D/ConSense/.local/runtime-integration34/downloads02/SCC-current.pdf>) |

这些草稿使用临时验收项目身份，合约编号为 `BS-BLANK-20261022-TEST`，仍含待填变量和待定选择。当前SCC目录、连续页码、项目身份文本及正文保存恢复已有实际检查；四处大留白、部分缩进和原生Word应用视觉等已列待办。正式定稿时仍须确认真实项目身份及未决业务内容。

本次后端恢复后，NTT（11页）、SCT（25页）当前保存稿已分别在实际前端打开PDF版式核对，确认PDF画布、页数与首页正文可读；没有重新生成。SCC当前74页逐张检查复用既有记录。浏览器当前预览见[NTT](<D:/R&D/ConSense/.local/runtime-integration36/ntt-current-browser-preview01.png>)、[SCT](<D:/R&D/ConSense/.local/runtime-integration36/sct-current-browser-preview01.png>)；这两次首页查看不代表重新逐页核过全部NTT／SCT。

## 查看已保存咨询

进入 Advice 咨询，选择上述项目与“项目合同”范围，打开已保存的同题27B会话。问题是解释本项目 Specification 对 SL、PSS、GS CEW 的适用及优先关系；回答中已有来源标记，点击可回到完整原件的对应位置。

提问、真实模型回答、保存、刷新历史以及引用定位已有实际记录。当前回答仍有部分条件解释不足和观察核验状态混用，已记入反馈待办；页面中的来源核验描述不能当作法律采用确认。当前咨询资料是本轮同源验证材料，不代表实际35件整包已完成咨询验收。

本次恢复后已重新打开同一会话，历史回答及28个行内引用按钮可读，引用[8]再次回到原Specification并高亮GS CEW段落，保留原件下载入口；没有新建会话或再次提问。[当前页面证据](<D:/R&D/ConSense/.local/runtime-integration36/advice52-current-highlight01.png>)。

## 真实整套审查报告

在 Vetting 审查中选择“真实2b项目验收-恢复20261021”。35件为33件范围内文字材料及2件仅识别排除的图纸，部分解析和来源质量限制保留。旧run844e5e5b的封存保存故障已解除，随后runf6e305b8在来源准备后冻结快照失配FAILED／0/19。公共来源入口顺序修复已clean回归84项全过并部署，原件资料、取值与文稿升级保全通过。11:13:41前端仅激活一次，11:14:47取得新任务`022d925f-4a5d-4291-879c-1a88ed2c4a14`，11:15回读QUEUED；模型为Qwen27B。只刷新接回此任务，不重复点击运行。

待后继真实任务完成后，从页面分别导出Word和PDF，打开实际文件，核对Comments与Checklist的可读性、原件引用、人工处理保存回读及2d参考版式。此处尚无可供替代的正式整套报告，不以旧测试报告填充。

## 已知问题与后续范围

新增反馈统一见[待办清单](human-acceptance-feedback-backlog-20261022.md)。先走完本轮既定用户流程；普通格式和内容改进之后集中处理。SL全量质量、版本隔离检索与最终比赛包仍在后续范围，质量未过不打包。

<details>
<summary>维护接续信息</summary>

- 当前业务后端 runtime37：18086／PID51296，64GiB；前端5177，测试空间15/p1。
- 真实审查项目 `proj_mvi0kncwu0as`；旧410／844／f6均FAILED且保留，新任务`022d925f-4a5d-4291-879c-1a88ed2c4a14`在11:15为QUEUED，沿同一句柄观察；唯一激活标记`.local/runtime-integration37/start-real35-once.json`。
- PRE恢复为binding33；`.local/runtime-integration36/recover-pre01/result.json`记录生产保存、独立连接完整读回及0重复POST／0模型调用。
- 起草项目 `proj_mvifhslpws1m`；咨询项目 `e2e-qwen-controlled-spec-20261020-r14`，会话 `6a7a4262-e773-4e1c-bfe2-922f9f10d51e`。
- 已交付代码提交 `96d3fe8816ba3a4ad1aa1bedaf0829d401a7eb16`，远端同分支精确核同；本文件是随后整理的验收入口。

</details>
