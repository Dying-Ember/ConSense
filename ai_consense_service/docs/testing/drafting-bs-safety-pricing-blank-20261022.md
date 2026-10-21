# SCC 的 BS 安全计价引用填空：用户截图缺口与验证边界

2026-10-22 用户指出 SCC20.303(5) 的 `Bill No. _____ / Schedule No. _____*` 看起来没有处理。调查使用实际 2a 原件、实际 2b 项目 SCC，以及 normal06 已保存的 source、compiled、output、inputSnapshot、bindings 和 unresolved；未调用 Java、LibreOffice、浏览器、模型、API 或数据库。

## 已确认的来源与完整同族

2a 标准 SCC 与 normal06/source-SCC.docx 的 SHA256 均为 `9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208`。本同族为四个正文填写目标与四段对应编制指示，不能把每个下划线或星号算成独立变量：

| 实际条款 | 编制指示全局段号 | 正文全局段号 | 正文原生位置 | 安全用途 |
|---|---:|---:|---|---|
| SCC20.301(7)，一般 BS | P1334 | P1428 | `word/document.xml#/w:document[1]/w:body[1]/w:p[1420]` | `bsSafety` |
| SCC20.302(5)，电气 | P1441 | P1548 | `word/document.xml#/w:document[1]/w:body[1]/w:p[1523]` | `electricalSafety` |
| SCC20.303(5)，消防 | P1558 | P1663 | `word/document.xml#/w:document[1]/w:body[1]/w:p[1621]` | `fireSafety` |
| SCC20.304(5)，消防及水泵 | P1673 | P1777 | `word/document.xml#/w:document[1]/w:body[1]/w:p[1718]` | `fireWaterSafety` |

下划线是原件中的文字 `_____`，不是丢失的 Word underline 控件。三个专门工种的引用跨两个 run，但原件及 normal06 输出中都完整存在。`(5)` 后的 tab 也保留。编制指示要求填入对应 Bill／Schedule 编号、删除不适用计价分支及其关联斜杠和 `*`／`**`；不能只删除星号或猜编号。

本版没有另设升降机或空调的同格式专门填写段，它们的专业范围仍需选择，并参与一般 BS 目标。不能为凑齐工种名称而新增原件没有的条款或填空。

## 根因分类

- **不是本例漏识别。** 四个 `scc-safety-pricing-*` action 均存在，四处正文 bindings 均为 `locationStatus=exact`，并绑定 `billNos`、`subcontractors`、`subcontractArrangement`。
- **本例没有“已给值丢绑定”的证据。** normal06 只采用了合成 `contractTitle`，没有采用上述三项输入。四个 action 均为 pending，四处正文在 source → compiled → output 中逐字保留。该输出是部分填写测试稿，不是实际项目填写完成。
- **Bill／Schedule 及编辑标记尚未有业务取值。** 实际编译规则已有整组替换路径；后续真实 2a 原件回归已核四目标分别采用 BQ／SOR 时整组替换、另一分支及关联标记删除。该结果不代表截图中的部分稿已经有真实项目取值。
- **可读性有实质缺口。** 生成已经清除了四段编制指示，PDF 保留四处原始空白、两条计价选择和编辑标记。现有未决说明没有明确告诉用户它们对应哪些空白、需在哪些字段补填。

实际 2b 项目 SCC 使用一般 BS `12.2 to 12.4`、电气 `12.2`、消防及水泵 `12.3`，且没有保留同格式的纯消防 SCC20.303(5)。它只能作为对应来源的对照证据；不能把消防及水泵的 `12.3` 自动带入纯消防条款。

## 最小修复与使用方式

继续使用现有 `billNos` 表格：准确编号、说明、BQ／SOR 类型及安全用途；专业工种与 NSC／BSSSC 分判安排分别确认。未决项沿用现有跳回字段和逐目标人工改文入口。NSC 选择本身不意味着可把本版 BSSSC 原件全局换名。

候选只细化四个 pending action 的三语说明，逐项写明实际 SCC 条号、`Bill No(s).`／`Schedule No(s).` 空白及关联 `*`／`**` 待办，并补全 `billNos.affects` 的八个指导／正文位置。说明只进入应用界面的未决列表，不添加到生成的合同 PDF。

字段 `affects` 主要是目录及前端位置元数据；`DraftBlueprint` 只抽取其中 document 集合。实际普通绑定使用 action 的 paragraphs／`DraftSourceAnchors.associationScope`，实际编译由 `DraftClauseRules` 经 `SourceListener` 指定 P1428／1548／1663／1777。此次修正 affects 不代表新增编译权限或修复已证实的漏绑定。

缺少取值时继续保留可用的部分结果与原始空白，不整单报错、不默填、不删除未知分支。明确采用对应取值后，编号、计价分支及本组编辑标记应一起应用，保留其他工种原文；不同工种／用途的编号不得相互代用。

## 回归与剩余验收

新增 `DraftBsSafetyPricingRealSourceTest`，准备覆盖四处未决提示与现有字段键、四处真实原件未知保全、每处明确 BQ／SOR 输入的整组替换、错用途与未确认类型不得填入纯消防。实际原件通过 `consense.acceptance.sourceDir` 显式提供并核固定 SHA；缺少原件时明确 SKIP，不能算源验证通过。

Root 已实际执行两轮负控及最终绿色回归：原产品四方法中三通过、一项因未决提示不明确而失败；仅采用提示修复后的六方法中五通过、一项因用途／工种冲突被误接受为 amend 而失败。最终采用提示、显式冲突门与同步规则版本后，六个真实原件方法全部通过；加上现有业务规则 14、输入规则 5、生成计划状态 4，共 29 方法通过，0 failure／error／skip。固定原件 SHA 已由测试实际核对，未依赖缺原件跳过。

真实原件检查覆盖四类未知正文保全、四目标各自 BQ／SOR 的八个整组替换、消防水泵编号不得填纯消防、三个专门工种矛盾只保留各自目标待办且独立正确目标继续应用、trade 缺省及六种合法工种的一般 BS 兼容。规则版本同步为 `drafting-20261022.1-bs-safety-trade`，避免旧冻结计划继续借用旧规则状态；读取新计划不改写保存稿和产物的断言也已通过。

本轮主树采用并通过回归，尚未升级运行后端，未执行新的前端填写／保存／生成 PDF 业务闭环。原 normal06 部分稿、旧失败与未知状态继续保留，不将局部真实原件编译等同完整项目填写或最终 PDF 验收。

## 同轮补充：明确的用途与工种冲突

静态检查还发现一项独立输入边界：原规则按安全 purpose 选行，未交叉检查可选 trade 列。用户截图没有给这些输入，因此该风险不是截图根因。经 Root 明确授权，后继候选复用现有三个专门工种的标准枚举匹配：对应安全用途的行若明确填写了另一工种，只让该专门目标保持 pending，并显示冲突说明。原值、其他可用目标与一般 BS 目标继续保留，不整单报错。

例如明确输入 `purpose=fireSafety`、`trade=Fire services and water pump`、编号 `12.3`，不得据此填写纯消防 SCC20.303(5)。trade 没给时保持现有兼容；一般 BS 安全用途覆盖所选 BS 工种，不套用单一工种的相等限制。没有新增工种别名推测或自动归一规则。

后继在冻结的原四方法外增加两方法：三个专门目标的显式 purpose／trade 冲突均保原文，同时不妨碍独立正确目标应用；trade 缺省与六种合法所选工种的一般 BS 原生填写均保持兼容。Root 实际 RED／GREEN 已完成，独立只读复审未发现阻断。一般 BS 继续沿用已选清单非空与 bsSafety 用途的既有边界，没有新增每行 trade 对所选清单的成员检查。

执行证据：`.local/drafting-bs-valuation-blank30/red01`、`red02` 和 `green01`，均保留各自原结果、命令、Maven 日志与 XML 报告。绿色窗总耗时 2 分 35 秒，没有模型、HTTP、共享数据库或 LibreOffice 调用。

本地诊断证据位于忽略目录 `.local/drafting-bs-valuation-blank30/source-inspection.json`，可用 `inspect_source.py` 重算。原件、运行材料和诊断产物不加入仓库。
