# 中文项目身份的生成字体与 PDF 能力清单修复

本轮实际前端专项使用明确的临时中文项目名称。旧 NTT revision `89424817-72a8-4cbf-8ad4-e9d2e305e2c7` 的 Word 下载成功，PDF 导出曾返回 `FONT_CAPABILITY_MISSING: Active source font families are unavailable: [新細明體]`。随后修复生成身份字体时，又实际发现已安装的 Microsoft YaHei 被共享字体清单误拒。两项失败分别保留；目前修复已部署，三模板的 Word／PDF 六文件已由真实前端下载，但新成品全页视觉仍未通过。

## 两个首损失层与采用方案

第一处发生在生成身份：原件样式 `a3` 为新生成页眉的中文槽指定新細明體，字体表给出 PMingLiU 名称。本机只有 ExtB 系列，没有常规 PMingLiU；normal10 的英文身份未激活这个中文槽。产品现为自己新写入的项目名称和合约编号明确设置东亚字体 Microsoft YaHei，复用现有 Vetting 报告方案。触发范围与现有 eastAsia 码点分类一致，包含汉字、全角、假名和补充平面；Latin 槽、字号和段落样式继承保持。原正文、来源字体表／样式、字段和相邻来源文字不改，缺少来源正文字体仍拒绝。这是新生成身份的明确排版策略，不表示缺失来源字体已安装，也不认证全部字形。

覆盖新增／追加身份、SCT 首／默认页眉重建及已有合约号标签替换。标签中的生成值独立成新 run，保留字号／粗斜体、hyperlink、原前后缀和字段；rFonts 位于已有 rStyle 后，字号与字体共用一个 rPr；中文合约号与保留的页脚控制文字分 run。集成版本为 `source-bound-native-stories-integration12-east-asian-identity-font-v1`，按既有快照机制重新生成新 revision，旧 DOCX 字节不变。

第二处发生在 PDF 字体能力清单：真实 `msyh.ttc` 已安装，JVM 的 ROOT 列表却只给“微软雅黑”，英文列表给“Microsoft YaHei”；SimSun／宋体也有相同现象。`DraftPdfRenderProfile` 现仅把同一实际 GraphicsEnvironment 的 ROOT 与 ENGLISH 字体族列表取并集。没有硬编码字体等同、构造请求字体后接受 fallback、安装字体或把 ExtB 等同普通字体；`DraftPdfSourceCapabilities` 的缺字体拒绝门未改。清单及现有 render-profile 身份随真实名称集合更新。此修复使用已保存的新 revision 重试明确失败的 PDF 导出，无须再次生成或修改 DOCX。

## 已执行验证

- **生成身份字体：** 原六方法 RED 为4 FAIL／2 PASS／0 ERROR／0 SKIP，26.073秒；后加两边界方法的原产品失败另存。GREEN01为70次执行中的67 PASS／3 SKIP，新增八方法实际全过。三个旧 SKIP 是实际2b页脚参数化场景缺目录；GREEN02提供 `consense.acceptance.project2bDir` 后，NTT／SCT／SCC三次调用已全部PASS／0 SKIP，5.727秒。原跳过窗保留，不改写为单窗全通过。
- **英文保持与正常生成：** normal11 正常三模板 compiler／JPA／LibreOffice／预览导出实际1 PASS／0 SKIP，272.035秒；其英文身份三份完整 DOCX 与 normal10 逐字节一致。SCC239关系、92目录及1311 CL／2500点仍由正常生成断言保护；这份英文78页包与前端中文专项包分别记录。
- **字体族别名：** ALIAS-RED01准确为3方法中1 FAIL／2 PASS／0 ERROR／0 SKIP，唯一失败是清单未等于实际 ROOT＋ENGLISH 并集。另一个方法在同JVM后序已通过，可能受字体API初始化影响，不追认为第二个RED；真实首次导出误拒和独立JVM的 ROOT=false／ENGLISH=true 证据保留。ALIAS-GREEN01为3个新别名方法＋13个converter方法＋8个身份方法，共24 PASS／0 FAIL／0 ERROR／0 SKIP。
- **已部署及真实下载：** runtime32 在18086／PID10404／64GiB运行，包SHA `1bc11431f45e211bb8b9058a2cf22501fa5eeaf444605e686c286da19b9f6b4a`；受保护H2 owner39876及runtime30／29604的APB原解析保持。专项项目 `proj_mvifhslpws1m` 的新 NTT `ca78a2be-c266-4526-b472-30bd69dbc5c4`、SCT `86366a33-21a7-4c6a-8e4e-56f142f8da26`、SCC `9eb2775c-b88c-4528-8af9-60c500cc3933` 均已有真实前端 Word／PDF 下载回执，六项 `downloadFailure=null`。

证据：`.local/ntt-source-font-export37/{green01,green02,alias-red01,alias-green01}/`、`english-full-docx-comparison01.json`、`jvm-font-families01.txt`、独立实际字体探针；`.local/drafting-main-integration27/normal11/`；`.local/runtime-integration32/{package.json,state-heap64.json,downloads/}`。源码／软件回归、正常生成、运行部署、前端下载与视觉分层记录，不新增审计框架。

## 成品范围与剩余问题

Root 已亲看新 NTT／SCT 首页和 SCC 物理53／57／61／75页，所见内容及中文身份可读。新 SCC 共75页，旧安全计价专项稿为74页；页数变化原因仍待核，不能据六页样张或旧78页包宣称新包全页通过。当前六文件的内容／家具／目录及逐页视觉仍在验，正式2b项目身份也未确认；专项继续使用已显式标识的临时身份，不代表完整正式业务验收或原生Word应用验收。

实际下载回读已补齐：三份 DOCX SHA 与对应保存 revision 一致；三 PDF 分别11／25／75页，共111页均能提取中文项目名、测试合约号及连续页脚号。SCC 三安全引用位于物理53／57／61页，整组替换已保留；纯消防 SCC20.303 在物理57页为 Not used。此处的全文文本／身份核验与上述六页视觉分别记录。

目录仍有独立失败：新 SCC 的三项目录页值与正文实页不符。Root 对精确 SCC revision 的只读 ledger 查询核实，生成时目录定版状态为 UNKNOWN，原因是当时 runtime31 的字体清单误拒 Microsoft YaHei；后续 runtime32 成功导出同一已保存稿，不会自动改写 Word 中的旧目录缓存。因此“导出字体修复无需重新生成”仅指可下载原 revision，不能据此声称旧目录已刷新。Root 随后在修复后的 runtime32 从真实前端有意重新生成一次，发送前保留旧三稿及输入，未因观察超时重发；新 revision 及其目录结果待接回原请求后核验。

与旧74页专项 SCC 比较，新稿仅四个 header 部件的身份 run 字体属性变化，document.xml、全部 footer 和其余 ZIP 部件逐字节一致；去家具后的 PDF 规范化正文全文一致。首个分页差异在物理9→10页，旧末句移入新页；这些观察不能单独证明字体是唯一分页原因，也不能把新增一页直接判作空页。证据在 `.local/runtime-integration32/readback-audit39/` 与 `.local/drafting-blank-screenshot39/`。

原一次新生成已接回终态，前端没有 alert，三份新 revision 的 DOCX SHA 均与前一保存稿相同；旧 revision 保持。新 SCC 为 `b8305f0f-ab06-45eb-906b-8b76a82d8082`，实际只读 ledger 已越过字体门，但定版仍 UNKNOWN，原因为 `HEADING_FLOW_BOOKMARK_AMBIGUOUS: CS198a982ddd2cc46e6fe149b385e7`。这证明字体误拒已解除，同时暴露已删专业条款与原239条分页约束之间的独立接缝，不能将它记为目录修复通过。后继按实际已授权编辑及保留范围核对，不按缺书签静默缩分母。
