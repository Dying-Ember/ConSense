# PDF SCC 变量／处理点独立提取与版本隔离

状态：已完成本地原生文字提取、人工全文分析和独立清单审阅；未接入产品动态目录，未开展自动引擎效果或实际文稿生成验收。

用户在当前变量图谱分析后，明确要求按同一方法提取另版 PDF SCC，并隔离两份 SCC 的变量。该授权覆盖本轮原件只读核对、离线提取与人工分析；不启动模型、OCR、训练、索引、活动服务／数据库变更或规则自动采用。

## 两份来源分别绑定

| 来源 | 独立 sourceId | SHA-256 |
| --- | --- | --- |
| PDF：2013 Edition，合订至 2023-01-01 | `scc-pdf-1bebab1afbc0` | `1bebab1afbc0faaf8aa9c0b9e6c51b023c24e53a094d4987d746fdeb65b30741` |
| 现用 Drafting SCC DOCX | `scc-docx-9aa2fa066836` | `9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208` |

PDF 原件在用户 Downloads 的 `email from Smart QS Hackathon Team/07_Special Conditions of Contract (SCC).pdf`；Word 原件核对本地既存 `SCC-original.private.docx` 副本。两原件不改、不放入 Git。Word 原件 2,074 个正文／表内段落与现行目录嵌入参考按同一 1-based 段号逐项核对，忽略空白差异后零不符。

## 完整分母与结果口径

- PDF 66/66 物理页均有原生文字；物理 1–3 页为封面及目录，物理第 4 页对应纸面第 1 页。新读取的全部页面文字与前次缓存完全一致。
- 三段全文分析原始共 75 个处理点候选；将 SCC20.2 消防 NSC 角色／范围核查跨页重复项 `PDF-SCC-2328` 归并到 `PDF-SCC-4501`，保留 alias、全部证据及原始分段文件，最终为 **74 个处理点候选**。
- 类型为填写值 5、选择 7、结构化清单 5、条款采用 16、编号／引用 11、外部核查 30。这里按语义处理点和位置归并，同一事实可参与多处处理，**不能把 74 当作新增用户问题数，也不能直接与现有 79 个顶层字段比较**。
- 144 段引文全部通过对应物理页内的精确连续子串校验；行号由原文位置重算。155 行标记／编辑词候选全部逐行处置；目录点线、装饰横线和普通履约正文误命中单独保留理由。
- 关系状态为原文明示 62、人工分析 2、待确认 10。此状态不授予完整因果、项目适用或自动采用；NSC 对象明文与项目是否采用未知可同时存在。

以上数字可由本地 `inventory.json` 与 `validation.json` 重算。填空、编制选择、条款采用、引用联动和外部核查分区保存；固定期限、比例、履约指数与日期不因出现在正文就变成新增待填变量。

## 已确认的隔离重点

1. PDF SCC4.2 具完整 A(1)–(8)／B(1)–(9) 两套分支，选择依据为投标阶段是否要求提交拟用分包商资料与经验；SCC5.2 随该结果删留不同段号引用。既有双信封事实不能独自代答此选择。
2. PDF 专业波动条款使用 Nominated Sub-contractor。Word 对应使用 Specialist／BSSSC 语境，不能仅凭工种名称或相似标题套用其对象、付款及波动规则。
3. PDF SCC11.2 正文与 Word SCC11.302 Option A 基本对应；Word 另有 B 及新增指导，不能将整条 A/B 结构移植。
4. PDF SCC8.2／Word8.304 构件删改正文相近，Word 新增采用指导；PDF8.3／Word8.305 均为 Additional Liquidated Damages，差异在采用指导限制，不能因旧字段 `additionalLoss*` 名称判断成不同救济。
5. PDF SCC14.1 比 Word14.304 多估值、付款、声明、标准分包及保留金安排，是正文实质差异。
6. PDF SCC22.8 的 A/B 读取本版 SCC8.2 采用结果，并含 NSC 延误分支；Word22.307／8.304 的条号、选项和对象独立保留。
7. 质保部分缺少 Word 新增采用指导时保留 unknown。星号脚注只授权对应标星的 footings／footings(raft-pad) 文字候选；更广的 piles／pilecaps 或整列删除另需依据。
8. 古树三处树号归并为同一身份事实，多株改文、金额、两期间、规范和同步条款分开核对。本版没有识别到对应处理点的 Word 预付款能力与标准 6／7 默认不带入 PDF。

Word 对应均为候选；`targetOverrides` 只可作为人工目标决定容器，不能标成这些新处理点的同义变量。PDF 清单所有记录均带 PDF sourceId／SHA／独立 ID，旧字段 key 仅出现在候选对应区，不回写 catalog 或项目采用值。

## 本地交付与验证

全部原文证据与页面图位于忽略目录 `.local/scc-pdf-variable-extraction-20261015/`：

- `index.html`：独立交互清单、版本差异及逐页证据。
- `inventory.json`／`inventory.md`：完整结构化及可阅读清单。
- `validation.json`／`source-manifest.json`／`source-verification.json`：分母、精确引文、原件身份及保持证明。
- `part-01-22.json`／`part-23-44.json`／`part-45-66.json`：原始人工分段分析，含交叉复核后的范围修订。
- `rendered/`：66 页渲染图；`contact-*.png`：完整总览；关键物理页 4、54、64 另行放大核查。

当前产品 catalog SHA 为 `adb592439047876ee5877b5b94655fa3ddd89714743e805cfa29b98589df7247`，本轮保持。先前 Word 变量地图和本次 PDF 报告为两个独立文件目录及本地预览，不覆盖、不自动合并。

Poppler 渲染报告 Symbol／ArialUnicode 显示字体警告；本轮不授予全部公式字形保真。原生文字与人工全文分析没有替代正常 OER 流程、模型自动变量发现、动态规则接入或保格式生成质量门。后续沿 [DYN 前置能力与任务图](../specs/drafting-template-adaptation-prerequisites-20261015.md) 推进，原 ENG／EAC 门保持。
