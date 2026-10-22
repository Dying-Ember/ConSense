# ConSense 三模块验收入口

状态：准备交付中，Goal当前active。Drafting 的现有六份文件、Advisory 已保存问答可以查看；PRE生产保存及完整回读已通过，真实35件Vetting后继任务已唯一启动，当前来源准备中，尚无正式整套报告。本文随实际结果补齐，不代表 Goal 已完成。

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

在 Vetting 审查中选择“真实2b项目验收-恢复20261021”。35件为33件范围内文字材料及2件仅识别排除的图纸；部分解析和来源质量限制保留。旧run844e5e5b在封存保存时FAILED／0/19；同一PRE已复用并通过生产保存和独立完整读回。10:21已从前端唯一启动后继任务，刷新后接回同一新任务，当前RUNNING／来源准备中；不要重复点击运行。模型为Qwen27B，原件资料快照保持。

待后继真实任务完成后，从页面分别导出Word和PDF，打开实际文件，核对Comments与Checklist的可读性、原件引用、人工处理保存回读及2d参考版式。此处尚无可供替代的正式整套报告，不以旧测试报告填充。

## 已知问题与后续范围

新增反馈统一见[待办清单](human-acceptance-feedback-backlog-20261022.md)。先走完本轮既定用户流程；普通格式和内容改进之后集中处理。SL全量质量、版本隔离检索与最终比赛包仍在后续范围，质量未过不打包。

<details>
<summary>维护接续信息</summary>

- 当前业务后端 runtime36：18086／PID60772，64GiB；前端5177，测试空间14/p1。
- 真实审查项目 `proj_mvi0kncwu0as`；旧运行 `844e5e5b-545b-4034-bf6e-a75e37daef9a` 已FAILED，后继 `f6e305b8-5fb7-4cc2-9ecb-94e4aa0c27d8` 当前RUNNING/preparing，只接回此句柄，不盲重发。
- PRE恢复为binding33；`.local/runtime-integration36/recover-pre01/result.json`记录生产保存、独立连接完整读回及0重复POST／0模型调用。
- 起草项目 `proj_mvifhslpws1m`；咨询项目 `e2e-qwen-controlled-spec-20261020-r14`，会话 `6a7a4262-e773-4e1c-bfe2-922f9f10d51e`。
- 已交付代码提交 `96d3fe8816ba3a4ad1aa1bedaf0829d401a7eb16`，远端同分支精确核同；本文件是随后整理的验收入口。

</details>
