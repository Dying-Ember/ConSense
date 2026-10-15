# Advice 普通来源准备热修（2026-10-15）

状态：来源热修前后端已部署，四源真实准备仍未完成；已定位 SCC 的 Word 整数回读自绑定缺陷，正在修复。本文不授原件质量或模型回答效果通过。

## 触发与原因

本机 Advice 在已纳入四份 `PARSED` 文件的 `fullset` 范围中，首问被 `current_sources_unavailable` 阻止。旧 UI 仅显示纳入数量，仍允许快捷问题提交，失败会清空问题；后端又将来源未登记、服务不可用和来源变化合并为同一句英文。

当前项目的四份文件没有 canonical binding，现有绑定属于其他项目，不得借用。配置的 canonical 与 Advisory 检索服务也没有运行。普通上传已有绑定原件、解析与覆盖信息的候选图，但 Advice 的 canonical 路径此前只支持人工核验登记，缺少从普通解析观察到规范来源的独立桥。

## 修复契约

- 显式 `POST /api/advice/{projectId}/sources/prepare?scope=...` 只准备当前实际纳入的来源；不解析、不 OCR、不调用生成模型。已登记来源仅校验；旧 reviewed 来源过期时拒绝，不降级为普通观察。
- `consense.normal-observed.v1`、Python `/normal-observed` 使用独立 authority。`actor=application`、`method=normal_parser_observation`，始终 `candidate_unverified`，`humanConfirmed/sourceQualityAccepted/semanticScopeVerified=false`。不伪造 reviewed/adoption 摘要，沿用 V15 nullable 列，不新增迁移。
- Java 在准备前后、登记前后及消费时重新核对实际原件 SHA、当前已提交的解析、路由、覆盖与完整候选代。只通过窄 CAS 更新 `normal_candidate_json`，不对缺少其他 LOB 的投影对象执行 save/merge。
- Python 将完整 graph、source binding 与观察者声明存入实际 CAS；每次 registry load 重读并重建派生输出。Java generation pin 原样保存，另记 Python 规范 JSON 摘要，不用不同序列化方式猜算 Java pin。
- 所有原单元、成员、关系、unknown 和 UTF16 定位完整保留。无文字单元列入显式 `nonRetrievableUnits`，原因 `no_observed_text`；不伪造文本，只有非空白原文单元进入模型检索候选。
- 入站流与完整出站报文沿用 16 MiB 上限。超限完整拒绝，不截单元换成功，不回落旧索引。
- 普通 API、HTTP 错误及 SSE 区分 `source_unregistered`、`evidence_backend_unavailable`、`source_stale`。前端在明确不可用时阻止各提问入口，保留问题草稿，并提供只读重查与显式准备入口。缺少初次索引 receipt 仍允许既有首问建索引。
- 准备结果逐源保留 prepared、already_registered、failed、unexecuted 全部分母；不自动问答或宣称人工核验。

## 当前四源离线完整容量检查

这里只对已提取的候选图做 shape/序列化检查，绑定为说明性本地检查输入，未向活动 registry 发布。

| 文件 | 完整单元 | 有文字检索单元 | 无文字保留单元 | 完整 response 字节 |
| --- | ---: | ---: | ---: | ---: |
| GCT | 87 | 84 | 3 | 507693 |
| Annex 1 to 6 | 1739 | 1739 | 0 | 9594469 |
| SCC | 799 | 793 | 6 | 5212583 |
| The Specification | 21 | 21 | 0 | 129802 |

四份均低于 16777216 字节；完整 graph.units 和原 generation pin 保持。证据在忽略目录 `.local/state/advice-binding-hotfix-20261015/normal-observed-python/`，具体文件身份以该目录 freeze/容量收据为准。此项不证明原件保真、人工审核或实际索引成功。

## 验证与部署账本

- 前端 freeze14：184/184 通过，0 失败、跳过或取消；默认类型检查、五个既定 MJS 专项及隔离构建退出 0。normal 引用指针已沿真实 Java DTO → 前端校验 → Advice 点击 → 正文/原件回取链验证，保无物理页的 Word 原生定位，不制造 P0。冻结与真实 RED 收据：`.local/advice-source-readiness-hotfix-20261015/web-freeze14-receipt.json`。
- 启动健康契约先留真实 RED：旧脚本拒绝六来源类型；新增 normal observed 精确能力与 false 质量声明校验后，旧四类型、五类型以及新六类型均通过。未因此启动模型服务。
- Python domain full-final-04：1615/1615 执行通过，1506 唯一 ID、109 次既有重复执行，115 测试文件（113 直接、2 staged），0 failure/error/skip/unraisable；227 源码无漂移。此为本域实际完整回归，不冒称与旧 1636 同分母。此前 full-final-03 为 1599 passed/16 setup errors，因缺测试依赖；旧失败完整保留，reportlab/pdfplumber/pdfminer 只补入忽略的 QA overlay，相关 22 项再全量通过，活动 venv 未改。
- Java full02 在 Word 实际接缝发现前为 2810 passed/0 failure/error/123 原有条件 skip；旧 2911 用例多重集保留、22 项新增、skip 集合无变化。原 30 个 JPA slice 上下文错误补真实 service import 后通过，不能把先前失败计入通过。
- 冻结启动封装实际出现 coordinator 缺包导致 /health 500。只补 server 既有四个标准库依赖的冻结副本和 SHA，保 sibling 路径；先留缺文件 RED，再 36 项纯配置/导入检查 GREEN。8874/8875 均恢复 HTTP200，沿原模型 publication、范围与模块隔离状态。
- 首次来源热修安装的 JAR 为 `22fc2c5f77e6ad304a2c9fb8a5ed593e373507a2d7942732ec6e75866732de13`，该版尚有下方真实 Word 问题。修复后的 Java full03 为 2812 passed/0 failure/error/123 原条件 skip（2935 总数），保留 full02 全部用例并新增两项，793 源码/资源无漂移；Word Long/Int wire 往返及类型混淆负例通过。
- 当前已部署 package03，JAR `3fe51ad2dd8a6f41e468fb612fbf44e907ce017545a541b04358551c1649d971`、后端 PID36736；前端 freeze15、PID36852。该包另含 [Drafting Regenerate 兼容热修](drafting-regenerate-hotfix-20261015.md)，其增量单独验证，不冒称新包又跑了 Java 全量。前端完整 185/185、类型检查和隔离构建通过；原 relay、模型 publication 与来源服务保持。

## 四源真实准备保留失败

首次产品 prepare 明确只调用一次，范围仍为原 282/286/293/307；HTTP200/code0 不代表完整成功。实际结果：286 Annex prepared，293 SCC failed/source_stale，282 GCT 与 307 Specification unexecuted。完整分母及报文保留在 `actual-source-prepare-response.private.json`，没有删除失败、借用其他项目绑定或自动重发。

已从旧数据库只读副本精确复现 SCC：正文、结构和覆盖信息均存在，真实 JSON 字节没有变化。WordNumbering.value 是 Java Long；build 生成 LongNode，JSON 保存回读产生 IntNode，旧自绑定直接比较节点对象，误判 14 个编号值为来源变化。另三源自绑定原可通过。修复只按真实 JSON wire 统一当前图节点表示，保原 generation SHA、原件/解析/覆盖校验和 boolean/string 类型区别；定向 RED/GREEN 与最终完整 Java full03 均已通过。

修复并部署后，在原项目和原四源范围执行一次明确的完成调用，无网络自动重发。首次 1/4 失败记录保留；两次产品 POST 的总执行账本明确，不把第二次成功覆盖首轮失败。第二次返回 selectedDocuments=4、preparedDocuments=4、complete=true：286 already_registered，293/282/307 prepared，humanConfirmed=false、sourceQualityAccepted=false。报文为忽略目录中的 `actual-source-prepare-after-word-fix-02-response.private.json`。

另从 Word 293 的新 normal projection 只读请求产品 units 指针及原件，均 HTTP200；Word physicalPages 为空、使用原生位置，原件回取 SHA `8ba80d183f6adf7a11eeb08f75e32c2bffdc3960f969202fcbed357e477f7af3` 与实际原件一致。286 PDF 的既有引用/原件回取证据也保留。未调用索引重建、模型问答或原件重解析；准备成功不代表索引、检索/问答效果或来源质量验收通过。

本次不开展 OCR、重新解析、训练、模型换型、广泛业务效果或端到端验收，不切换项目来换成功；原件、fixed05、gold 和其他活动工作保持。

## 提示词配置页补齐

用户随后指出配置页仍有旧预设，并明确要求 Vetting 和 Advice 一起更新。现场 API 核实：Vetting 当前值和默认值已是完整比赛规范；Advice 的出厂 system 只有 181 字符摘要，当前自定义 system/user 仍是旧版。运行时追加必需规范不能替代配置页、保存值和恢复默认的完整交付。

已将两项当前配置通过既有 PUT 保存并以独立 GET 逐字读回，旧值与精确目标留在忽略目录。Vetting 的已确认业务正文不改；Advice 使用已确认的 2026-10-14 完整规范，清除旧 user 模板中的 answer/Mermaid 直出协议，改用现有独立答复单元协议与三个既有占位符。

代码让 Advice 的默认值与运行时读取同一资源，完整默认值不再被当作自定义补充重复追加；真正的用户补充仍保留，不能覆盖必需规范。两项真实 RED 测试分别证明此前默认值不一致、运行时会重复完整 policy；专项 21 项通过，已部署并对两个真实 reset 接口及独立 GET 逐字核验 current=default、customized=false。原业务规范资源均未修改，不删减 PM 规则。专项代码已提交 `82278ed`，见[独立交付记录](competition-prompt-defaults-hotfix-20261015.md)。

初始持久化收据 `.local/state/advice-binding-hotfix-20261015/prompt-persistence-initial.json`：Vetting system SHA `a14a3261203cc04b9e0db10d3dc230d047feb846c0d188f649433b928238c8c8`，Advice system SHA `ec23c7a665f803598a82d8aaf41ace65df1e431ac5e4ab24610e61d11ccf0470`。初始仅保存当前值时，Advice factory default 尚旧，用户点击 reset 再现覆盖；后续专项部署和两项实际 reset 已关闭此缺陷，不能将初始保存追认为当时默认值已经部署。
