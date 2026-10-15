# Vetting 比赛提示词与业务能力补齐记录

日期：2026-10-15，Asia/Shanghai。当前开发目录为 `D:\R&D\ConSense` 的 `main`，本轮代码基线为 `69f3409cd3c6f8842bb48b2579eaf8d71ad78035`。其他分支的可恢复归档见 [main 统一记录](main-consolidation-20261015.md)。

## 业务权威与资产

用户明确：附件是产品经理业务规则，只做比赛运行改写，不以旧实现能力删除或降低业务要求；能力不足须补实现。[改写说明和保全映射](../specs/contract-vetting-system-prompt-competition-20261015.md)记录十二规则、七类、六步原顺序、13 字段、三档严重度和两种官方交付。

| 资产 | SHA256 |
| --- | --- |
| [PM 原文](../specs/inputs/contract-vetting-system-prompt-product-20261015.md) | `515a9962931274868634f175564f0f6c4e19f8b8fdace0c777b1284dba51ee54` |
| [比赛运行正文](../../src/main/resources/vetting/contract-system-competition-20261015.md) | `a14a3261203cc04b9e0db10d3dc230d047feb846c0d188f649433b928238c8c8` |
| [44 主项映射](../../src/main/resources/vetting/checklist-logical-groups-v1.json) | `98c753b3264c74ff21cb73995444f528cd6f5198cd2d9a7d1d6cd3db7a1271ab` |

PM 原文按字节保存；三个身份绑定文件使用精确 `-text` 属性。运行正文不预置附件样例项目的意见、日期、条号、数量分布或答案。44 主项对应原 67 个检查细行，另有 Annex 2 行；原 69 个 APP-I ID 及人工记录保留，不删行凑数。

## 已补实现与兼容边界

- REF/CONF/LANG/RISK/GAP/SUB/REQ 七类分别解码、保存、计数、筛选、展示和导出。未知模型类型明确拒绝。原四类指标键和旧记录兼容，不把后三类并入 risk。
- 每条新发现保留子维度、事实基线、待确认事实、四动作及有据的核对项连接。模型问题在信任边界校验；确定性规则只记录实际检测依据，不伪填项目适用性。旧记录缺底稿时明确未记录、需复检。
- 必需比赛规则进入实际语义调用、默认提示词和恢复身份。持久化用户补充按每次运行冻结，不能取代必需规则；补充身份进入恢复契约和发现指纹。历史报告继续使用当次快照。
- 语言校对须有项目新增/改写来源证明，原封标准措辞保持；两个旧规则中的待确认双重定义/缩写歧义按七类归 REQ，保留实质问题和原始证据。真实已观察条款号与原稳定地址同时保存，不臆造分条款号。
- 自动核对只消费实际调用、成功解码且经证据校验的服务器账本，绑定当次规则、packet、source、chunk 和引文。未核、缺证、冲突、范围外、不适用分别保存；不以零发现或依赖闭合生成 ✓。待确认事实未解决的失败意见仍可保存为复检发现，不据此输出确定 ✗。
- 人工 69 行决定与自动建议独立；新增“未满足”须非空理由。人工复用同时核真实依赖和自动依据，不能用变化后的自动建议复用旧决定，不能覆写历史。
- 44 主项和细项自动结果可展开核对；失败同格保留七类线索与原因。正式 01 维持五列、Appendix I 维持三列；JSON、Word、PDF 共用展示模型。各实际分册均输出来源覆盖小结；计数完整也不能自行授“全范围无意见”。

## 本轮验证状态

本轮适用代码自动化已通过，效果验证另行开始，不沿用此前统一实现的绿灯冒充本轮通过。

| 验证 | 实际终态与证据 |
| --- | --- |
| Java `full02` | 292 类，2,893 reported：2,770 passed、0 failures/errors、123 既有条件 skipped；`java-full02-terminal.json`、`java-full02-count-audit.json`。 |
| Java 定向回归 | `focus08` 325 passed/1 既有 opt-in skip，含六项真实进程恢复；`focus09` 166/166，含非 BMP 字符边界。均 0 failures/errors。 |
| Java 隔离打包 | `java-package01-terminal.json`：`package -DskipTests` 退出 0，本步未重跑测试。JAR 79,659,438 bytes、SHA256 `f9e2302c3d46ca85e1beeac6a37aff0761864f629c53294e5efaec7246540017`；623 classes + 42 resources 与包内逐项 SHA 一致，活动主 JAR 保持。 |
| 前端主目录 | `web-main-full04.log`：167/167、0 failures/skip；五个既定 MJS 专项通过。 |
| 默认 npm 入口 | `web-default-typecheck05.log`、`web-default-build05.log`：`npm run typecheck` 与构建实际退出 0；构建输出为忽略目录，未覆盖活动 dist。 |
| 未改 Python | 244 份源码/测试与已有 1,636/1,636 绿灯版本逐字节一致，`python-unchanged-receipt.json`；本轮明确复用该版本结果，没有宣称重新执行。 |
| 独立审查与完整性 | PM 保全及 Standards 审查发现的业务缺口、局部检查误授全范围 ✓、两种 source hash 混用均已修；`full02` 核对 778 源文件无运行中漂移，123 skip 逐 case 集合与修前一致；`git diff --check` 通过。 |

Java `full01` 的真实结果为 2,740 passed/5 failures/4 errors/123 skipped，完整保留。根因分别为：新增字段未同步到作者编写的 schema 正例；旧双 null 候选的历史指纹兼容；新增核对目录派生属性在不同 JVM 中顺序变化，导致相同要求内容的完整输入 SHA 不同。只补正例、保留新旧候选边界，并给新增 wire/指纹对象明确属性顺序；没有放宽恢复门。`full02` 对原九个红项逐 case 证实通过，123 skip 零增减。修改后的正向范围门同时覆盖泛项未知、完整局部允许、遗漏必需成员拒绝及不受无关预算丢弃影响。

主目录依赖曾缺 `@babel/parser` 等文件，三条命令在断言前失败。使用相同 package-lock 的已安装依赖仅补 405 个缺失文件（7,604,050 bytes），覆盖/删除 0 文件、服务重启 0 次；随后主目录回归通过。Windows `R&D` 路径下 npm 默认 cmd shim 另出现路径拆分，已将四个 npm 脚本改为直接调用已安装的 Node CLI，保留原参数与跨平台行为；默认 `npm run typecheck` 及输出到隔离目录的 `npm run build` 均已实际通过，原失败记录保留。

独立历史脚本 `test-inspection-ocr-diagnostics.mjs` 依赖未纳管的 SL1006/APC19 原始捕获，当前缺 `inspection-ocr-diagnostics.actual.json`，在断言前 ENOENT。依用户先前清理历史生成资产的决定，将此入口单列历史回放，保留源码/断言和实际错误，**不计本轮 passed 或 skipped**，不重新识别或伪造旧数据换绿。其 SSR 性质本身不等于真实模型效果测试。

本地证据根：`.local/vetting-prompt-competition-20261015/`；含各次 Java focus 日志、隔离镜像、前端失败/修复收据、全量回归、类型检查与构建日志。既有失败历史保留。

## 后续原件验证的新授权

用户随后要求：本轮代码任务完成后，重解析固定 NTT/SCC/SCT 模板及 GCC/GCT/SL。比较不局限 fixed05，而以随后多轮训练及引擎修复仍未解决的问题为主线。已核最新字段训练七页来自另两原件 AoA4/APL3，故一并按原 57 字段/74 写区/11 关系分母回归；不能六类固定文件测完便关闭这些未覆盖的问题。先核原件 SHA、版本/技术范围、实际运行与候选采用身份、最近可比旧输出和完整分母，独立保存新输出；缺证/未执行继续开放。只读准备在 `.local/oer-fixed-six-reparse-20261015/preparation/`，不是执行或模型质量通过收据。

本轮代码检查没有实际重新解析、模型训练/调参、检索/重排、下游合同效果或浏览器/E2E验收；活动 JAR/dist、数据库、fixed05/Local05 与 gold 保持。指定原件解析在代码收尾后进入另行记录的执行阶段；其余暂停门不自动恢复。
