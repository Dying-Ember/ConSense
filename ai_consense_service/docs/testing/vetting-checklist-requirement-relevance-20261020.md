# Vetting Checklist 要求相关性：2026-10-20取证及未修复边界

本记录针对保存的实际Vetting归档中Checklist要求与理由的错挂。它不是新模型执行或已完成的产品修复；原始raw、16个一般finding、69条人工记录及旧视觉票保持原样。当前checkpoint `eb9a10ecda339044bfe9a80d19066203a53d9e5f` 的代码/前端通过结果不能替代这项业务相关性验收。

## 当前实际映射

| 细行 | 逻辑组及来源位置 | 要求范围 | 最终保存关联 |
|---|---|---|---|
| APP-I-002 | CK-002，GENERALLY / 1 | 合同类型及SL Guidance Note条件与所选条款的关系 | 6条not_fulfilled，4条insufficient_evidence |
| APP-I-007 | CK-003，GENERALLY / 2 | PRE条件指令、确定工作/可选工作及时间/地点指示；BQ子问题排除 | 1条not_fulfilled |
| APP-I-008 | CK-004，GENERALLY / 3 | appendix条款号、heading appendix reference与页右上header一致 | 2条not_fulfilled，5条insufficient_evidence |

reader04当前第15页的Generally / 2属于APP-I-007；旧初步定位为APP-I-002的记录已经纠正，不能沿用。

## 保存证据所证明的错误层

对reader04归档的18个最终目标关联，逐条比对最终decoded model attempt、packet itemId/status/reason及引用顺序，均一致。9个负判（002×6、007×1、008×2）仅用SCT的两个copies段落位置，未证明三项各自要求的业务谓词。另一次历史structured_output_invalid attempt若一起计入，raw关联数为20；20不能混作18条最终关联。

这证明最早可观察错误已在保存的模型raw Checklist关联中；topic顺序并不证明全局墙钟先后。packet/聚合没有移换ID，Writer按当前catalogue description精确取行，因此不应在Writer把copies硬挪到另一要求。通用copies finding是否最终法律成立仍须独立判断，不能因Checklist错挂删除有据局部观察。

取证输入的run ID为`a5681c88-5b6c-44e1-804a-0f5012192969`，保存状态COMPLETED；三项目标人工行均为unchecked、空备注。历史profile配置为qwen-flash/qwen3.8-flash；这里只说明捕获配置，未独立证明实际provider身份，也未新增调用。

## 旧prompt已加载

当前政策资源SHA为`a14a3261203cc04b9e0db10d3dc230d047feb846c0d188f649433b928238c8c8`，与归档promptPolicy相同。对9个相关最终packet的静态核对均显示：

- 当前system字面量拼接与捕获system在schema附加前一致，保留政策资源CRLF字节解码。
- 附加schema与相同attempt捕获的input.schema一致。
- 002/007/008的ID、section、clause、description、templatePage与当前catalogue一致。
- prompt已要求仅关联被该record精确证据实际核查的APP-I细行。

所以“旧prompt未加载”或“catalogue传错”不能解释本次错挂。静态拼接核对不是Java执行；捕获serializedInputSha只作为原声明保留，不声称独立重算了整个请求。哈希只标识输入，不能证明业务相关性。

## 当前可控缺口与候选边界

SemanticReview已有逐record引文/来源角色/比较provision/项目采用等门，但ChecklistAssessment仍只有itemId/status/reason；publication把该issue整组证据复制到每个声明的Checklist项。AutomaticResults复核producer/policy/packet/quote身份，但没有要求谓词关系；正判完整scope门也不能替代负判反例的相关性。

最小候选先在真实semantic发布和aggregate接缝准备纯作者红测，保一般finding、原claim及目录分母。尚无真实红测执行，尚无产品代码修复或新业务验收。模型Record/schema小契约仅是候选，不已采用。

首个literal alignment若要落地，必须能从原生来源独立重读每个字段及其角色，并证实同一个对象/页面/原件修订/采用范围；仅三个相等字符串、同clause、table首行、模型自报relevant或正确hash都不能代替。当前尚无该正向字段绑定，002/007的法律意图继续保留为模型建议、相关性未核。不得不经分析把所有旧自动结果统一改unknown；旧raw/manual/finding不迁移、不改写。

## 本地证据入口

原件、引文正文与运行取证保留在忽略目录，不纳入本文件：

- `.local/vetting-checklist-applicability-diagnosis01/evidence-trace.json`：18个最终/20个含无效历史关联的逐项链。
- `.local/vetting-checklist-applicability-diagnosis01/effective-prompt-and-code.json`：9个实际packet的有效prompt/schema/catalogue核对。
- `.local/vetting-checklist-relevance-candidate01/independent-diagnosis-review01.json`：独立复核计数，不含原件正文。
- `.local/vetting-checklist-relevance-candidate01/repair-design.md`：未闭合前提、最小契约、旧run影响及10组合成计划状态。

本轮仅静态读取及候选准备，0 JVM/HTTP/SQL/provider/service执行；这项实际业务缺陷仍为未修复。
