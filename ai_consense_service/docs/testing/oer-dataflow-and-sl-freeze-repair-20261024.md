# OER 第二轮复审 R01–R07 修复与验证

日期：2026-10-24，Asia/Shanghai。基线为 `0b475c3a3601e18dcac80b1632e784334b7c4448`，对应[第二轮复审](oer-dataflow-and-sl-freeze-second-review-20261023.md)。仅收口 R01–R07 的源码、兼容契约及隔离回归；首轮另外的缺口、七版原件质量、完整比赛包不是本轮完成项。原审计与失败证据保留。

## 实现与兼容

| 项目 | 已实现 | 保留的边界 |
| --- | --- | --- |
| R01 完整组重复 | 显式 dictionary-v2／manifest v2；member 每 origin 一份，组保存成员地址及声明模板；扫描不重复发已声明持有的模板，Java Session 共享模板，模型同包仅提交一次。 | 旧 v1 读法保留，历史 manifest／CAS 不改写。HMAC、顺序、来源、权限和实际字节核验不变，不从 cursor 猜缓存。单 member／模板超限记录 blocked 并继续可靠范围，完整分母及报告 incomplete 保留。 |
| R02 排队期限 | 随机 task ID 绑定输入 SHA、purpose、sourceContext、配置 SHA 和 caller deadline；排队、得锁及每次派发检查同一期限。原子认领、持久状态与终态结果，owned provider／Job 取消接通。 | 同 ID 不同输入拒绝；重复或重启后未知任务不重派。正常 persisted 事务握手与真实取消分离，UID／子树释放保护保留。 |
| R03 普通页传输 | identity／parse 都使用 one-shot 和 32 MiB 解码上限；异常后仅用有限短预算查询同 ID，已完成时获取既有结果，不补 POST。 | 超限完整结果保存在 CAS。普通 multipart 默认、原生 PDF one-shot／8 MiB 契约保持。 |
| R04 工作文件 | 每次独立临时输入文件，成功、capture 失败及后处理失败均保全证据后 finally 清理。 | 不删除 CAS，不用可写硬链接，不自动清理历史 `inputs` 或活跃任务。 |
| R05 SL 选版 | `slSources` manifest、逐版 scope-set／builder、项目持久 editionId＋原件 SHA＋manifest SHA；Vetting／Advisory 查询过滤、逐成员返回核验、提交前守卫和共享前端入口。 | 旧单版默认唯一版，多版必须显式选择。检索不授项目采用，历史记录不重写。V27 仅提供源码，本轮未安装到业务库。 |
| R06 路径身份 | 新窗口／结构签名为 5／6，逻辑模型身份与新 window IDs 不含 loader 目录；旧 3／4 通过外部 SHA pin 的明确源码 adapter 只读兼容。 | 模型／tokenizer 字节、runtime、配方、来源和向量 seal 保持。整模块 AST 对为静态已审登记，不自动接受未来代码、额外语句、encoder 替换或模型门删除。migrated 继续只读，index 与 query identity 分列。 |
| R07 索引恢复 | rebuild 意图贯通按钮／Java／Python；创建唯一 generation，完整点数、payload、向量 seal 通过后发布；旧 metadata 流式归档，旧 collection 保留。 | 普通复用仍拒坏索引；提交点之前失败不替换原 metadata，提交后响应丢失保留结果未知，不使 migrated 固定索引可写。 |

R01 不提高来源质量或语义完整性，缺成员、unknown 及超限范围仍显示。R02 对已经开始的任意第三方同步 CPU 调用不宣称强制中断，边界是零新派发、拒绝晚到结果和保全证据。owned Kubernetes 取消只经过替身回归，不是实际 K8s／GPU 验收。

## 旧索引搬目录的准入

新进程使用实际模型 locator，模型／tokenizer 字节与配方不变；运行中直接换目录仍受冻结模型生命周期限制。通过以下工具生成全新收据：

```text
python tools/vetting_eval/portable_index_signature.py --predecessor <冻结旧工具源码目录> --current <新工具源码目录> --output <全新收据文件>
```

显式配置 `CONSENSE_INDEX_SIGNATURE_ADAPTER` 和 `CONSENSE_INDEX_SIGNATURE_ADAPTER_SHA256`。收据携带旧源码，读取时不依赖旧 checkout 的物理路径，仍核新包实际源码。当前 adapter 仅适配本轮基线及登记的新源码；任意历史版本／未来改动不能自动获准。无收据或 pin 不符时，旧同源索引明确拒绝，不自动重嵌入，不删除 metadata 绕过。

仓库的窗口默认及 `application-vetting-local` 为 5，`application-oer` 为 6；profile 保留 `CONSENSE_VETTING_RETRIEVAL_EXPECTED_SIGNATURE_VERSION` 显式旧 pin 3／4。Java observed 必须等于 expected，旧 query pin 不自动接受新版。固定 manifest、migration receipt、query runtime pin 分别核验。本轮未改任何 `.local` 私有运行 pin 或切换服务。

## 测试与失败账本

Java 使用复制源码、单 worker、8 GiB 上限、owned Temp，不在在线 `target` 编译。Python 使用已有 retrieval 环境与冻结源码。测试只使用 authored 数据、临时 H2／Qdrant Local 和本测试拥有的短时 HTTP／子进程；没有真实模型或 OCR。

- Python 公共读取／旧协议／结构／窗口／签名／固定库／scope／OCR 生命周期／cache／CAS／provider：364 项通过，0 failure／error／skip，177.299 秒；`evidence/python-final01.json` 和逐案日志。
- owned Job 完整 7 项通过，0 failure／error／skip，2.486 秒，`evidence/python-owned-job-final.log`。先前两条正常会话被误判取消的失败保留，已修复，未删失败换绿。
- 前端选版／引用／项目切换 11 项通过，typecheck 通过，独立目录生产构建通过。首次 esbuild 因系统 Temp 删除权限失败，owned Temp 重跑成功，两个日志均保留。
- Java 首轮定向 194 项通过，0 failure／error／skip，40.358 秒；该窗口包含于后继宽回归，不另加分母。
- 宽回归 `java-final07` 原始结果为 249 suites／2,660 项：2,598 passed、32 failures、2 errors、28 skipped。其后的 `java-final-fixes09` 四类 76 项全通过，修复回放输出目录、旧三参固定检索 mock 和手工升级 fixture 漏 V27 的问题；旧断言保持，另核历史项目选版仍为 NULL。按完整 suite 替换汇总，原始失败 XML 和日志保留。
- 最后补充 Java 直接恢复分支：POST 响应丢失→同 ID completed→GET 已有 result，断言 1 POST、1 状态 GET、1 结果 GET、0 cancel。`java-final-transport10` 完整 transport suite 3 项通过。以其替换旧 2 项 suite 后，最终组合为 **249 suites／2,661 项：2,606 passed、26 failures、1 error、28 skipped**；本次回归未留下新增 failure／error，用例及来源 XML SHA 逐项列于 `evidence/final-verification.json`。这是完整 suite 替换后的组合收据，不是一次全绿运行。
- 来源回放显式绑定原冻结 fixture，并保持其 SHA 校验。残余五类失败已在原 `0b475c3` 独立源码中重现，case ID、failure／error 类型、异常 type 和 message 逐案相同，详见下表与 `evidence/final-verification.json`。不称全套绿色，不修改旧 golden 掩盖失败。
- 早期共享源码运行被 `SOFTWARE_CHANGED_SINCE_IMPORT` 拒绝；首次 snapshot 缺布局标记；首次 Maven cmd 路径含 `&` 未进测试；系统 Temp 拒绝访问。分别使用完整冻结副本、直接 Java launcher 和 owned Temp 修复环境，原失败保留，产品门未放宽。

| 已确认基线 suite | 测试数 | failures | errors | 独立基线运行 |
| --- | ---: | ---: | ---: | --- |
| OerSharedNoteAuthorDifferentialTest | 36 | 20 | 1 | java-baseline-author06 |
| VettingCanonicalReportCoverageTest | 4 | 3 | 0 | java-baseline-report04 |
| VettingPacketReportTest | 1 | 1 | 0 | java-baseline-report04 |
| VettingReportWriterTest | 12 | 1 | 0 | java-baseline-report08 |
| VettingSampleReportAlignmentTest | 9 | 1 | 0 | java-baseline-report08 |

本机证据根为忽略目录 `.local/oer-second-review-repair-20261024/`。最终 adapter 为 `evidence/portable-adapter-final.json`，1,056,114 字节，SHA256 为 `957cfa1de3e27fca52db6cba0efb94690f4d8b76bf847c566ec7d2486b0987e6`；旧候选保留，不能沿用其 SHA。静态 15 模块 AST 登记逐一核对基线及当前文件，收据所有 currentHashes 与当前原始字节一致；未来源码变化须重新审查，不能自动获准。

最终组合收据核对本轮改动的 Java／资源与 `java-final-transport10` 源码副本逐字节一致，Python 工具与 `python-final01` 副本逐字节一致。最终 helper／wire fixture 登记后另重跑 portable 9 项，均通过，包含旧短窗口及结构索引只搬模型目录、0 新 encode、metadata 字节不变；该 9 项与 364 项重叠，不另累计。R01 本轮证明结构与控制流回归，未补真实来源负载、模型 token 或吞吐曲线。

## 运行与验收边界

未重启产品 Java／公共读取服务，未恢复用户停止的 OCR，未安装 V27 到业务库，未改写旧采用／历史报告／CAS／索引，未加载模型、解析七版原件或制作完整比赛包。源码修复、软件回归、真实来源质量、浏览器业务、目标机恢复与 H800 分别验收。后续部署须先核全局活动和未保存页面，再按协议及精确 pin 联合升级必要组件，不能只换一端。
