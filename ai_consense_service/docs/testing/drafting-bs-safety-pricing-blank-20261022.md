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

## 实际项目对照与前端复验准备

实际 2b 的 NTT 与 SCC 相互印证所选工种为 Electrical、Fire services and water pump、Lift；SCC 明定 BS Specialist Sub-contractors，可透明映射为待人工采用的 BSSSC。NTT 三项专业工程本身使用 SOR，但安全／环境／卫生费用明确作为例外位于 BQ Bills `12.2 to 12.4`。因此输入行的工程工种、计价类型和安全用途必须分开确认：不能由“该专业工程使用 SOR”推导安全引用也用 SOR。

消防及水泵安全 `12.3`、电气安全 `12.2` 与一般 BS 安全范围均已有逐字位置证据；没有自动选入纯消防，也没有从区间减法猜单独 Lift 编号。两件资料未找到明确的正式项目名称；页脚一致出现裸值 `20250101`，保留为待核身份候选，未自动认定或采用为正式合约编号。正式 Bill 标题也未在这些引用段落给出，继续 UNKNOWN。

事实准备保留在 `.local/drafting-bs-valuation-blank30/frontend-manual-source-facts01.json`，含两件原件 hash 和 15 条原文／原生 part 位置。新的前端专项使用临时验收项目，三件真实 2a Word 已经一次上传并返回 HTTP200；这属于人工补填专项准备，不是自动提取、真实项目身份或最终生成闭环通过。

## 已部署的前端与最终文件专项结果

integration30已实际部署18084/PID29604/64GiB。真实前端读回规则 `drafting-20261022.1-bs-safety-trade` 及四处明确填空待办；采用原件所列BSSSC及三个专业工种，安全BQ三行逐项保存、刷新后编号和用途均保持。未取得正式项目身份，专项明确采用临时名称及 `BS-BLANK-20261022-TEST`，经过“是否替换当前项目身份”确认后才进入生成，不把测试身份当实际2b事实。Bill说明列使用上述逐字来源用途，正式Bill标题仍未知。

前端唯一生成已完成，NTT／SCT／SCC分别保存新revision；SCC为 `e5663ad6-73da-41c4-ac51-33f2f6403b73`。通过界面下载的DOCX为286,709 bytes／SHA `666c0b18f29a37a9a2c2fecb9bd1368cc2c2a46ab4278f8d522952899b181b7c`；PDF为74页／631,277 bytes／SHA `189b8ec7dce623a60ef9f20c73c41a28611ad010e945a118ce9b74f9309affda`。三处实际正文为 `Bill No(s). 12.2 to 12.4`、`Bill No. 12.2`、`Bill No. 12.3`，均采用Bills of Quantities，关联下划线、候选斜杠及星号已整组处理；DOCX与PDF吻合。未选择纯消防，SCC20.303在成品为Not used，其原正文不再残留，也没有代用12.3。

新PDF物理53／57／61页已用fresh120dpi整页图亲看，三处正文可读、页眉临时项目名／左下测试合约号及印刷页50／54／58清楚。测试合约号在全部74页可提取；这不是全部74页视觉验收，其他未回答空栏和复杂续段仍须继续核。手工来源值专项不替代自动抽取，也不授完整实际2b项目身份或全成品通过。

证据：`.local/drafting-bs-valuation-blank30/browser103.json`、`browser110.json`、`browser111.json`、`browser114.json`至`browser124.json`、`frontend-download-checks01.json`、`target-visual-review01.json`及`downloads/`。浏览器自动操作曾在页面刷新未结束及隐藏modal控件处遇到超时，先查实际状态后才继续；没有重发生成或重复上传原件。

## 用户再次指出空栏后的当前回读（2026-10-22）

新截图仍是纯消防 SCC20.303(5) 的整组待填空，不能仅凭图片判定它属于哪个保存 revision。Root 已重新核实际前端的保存输入：BSSSC、Electrical／Fire services and water pump／Lift，以及一般 BS `12.2 to 12.4`、电气 `12.2`、消防及水泵 `12.3`，三行均为 BQ 且分别采用对应安全用途；未选纯消防。计划回读确认三个安全目标 applied，纯消防整条为 not_adopted。

runtime32 下载的 SCC revision `9eb2775c-b88c-4528-8af9-60c500cc3933` 的 Word SHA 与保存稿相同，PDF 为75页。三处引用仍在物理53／57／61页，关联空栏、候选斜杠及星号已清除；纯消防正文不存在，物理57页为 `SCC20.303 Not used`。Root 对这三张 fresh 整页图逐张亲看，未把不同工种编号代用。本专项验证的是实际所选三专业输入；纯消防明确 BQ／SOR、缺值与错误用途的真实2a编译回归虽已有覆盖，不能追认为纯消防前端完整业务验收。

缺安全费用编号或类型时，产品保留可用草稿与该目标原空栏，并在未决项说明具体条款、需补的 Bill／Schedule 类型、编号及用途；不因局部缺值整单失败，不将未知当作不适用。一般 BS、电气、纯消防、消防及水泵四目标继续分别处理；NSC／BSSSC 安排与工种选择独立。

同轮发现的独立限制保留：新 SCC 至少三项目录值仍错，精确保存稿的目录定版 ledger 为 UNKNOWN，原因为生成时 runtime31 的 YaHei 清单误判。runtime32 已修字体清单，Root 已在保留旧稿后唯一发送一次有意新生成，目录及新最终文件须接原请求后复核，不能凭三处填空通过授全成品通过。另发现同一计价身份需多种安全用途时，当前 `type:number` 去重与下游实体清单存在复用边界，正在准备私有复现；没有真实项目采用同号证据，不自动改写既有 Bill 输入或放松冲突保护。

新证据：`.local/drafting-blank-screenshot39/{variables-readback01.json,plan-readback01.json,before-deliberate-regeneration01.json,regeneration-after-alias-once01.json}`、`.local/runtime-integration32/readback-audit39/`及`visual39/`。旧74页包、新75页包、正常生成英文78页包分别保留，不能互相继承全页视觉结论。

原一次新生成随后已终态保存，三份 DOCX 与上一 revision 字节相同，故三处填空保持；新 SCC revision 为 `b8305f0f-ab06-45eb-906b-8b76a82d8082`。实际 ledger 的目录定版已通过字体能力门，继而在 `HEADING_FLOW_BOOKMARK_AMBIGUOUS: CS198a982ddd2cc46e6fe149b385e7` 保留 UNKNOWN；目录失败仍在修，旧可用稿／输入保留，未因局部定版失败整单报错。回读见 `after-deliberate-generation-observation02.json`。
