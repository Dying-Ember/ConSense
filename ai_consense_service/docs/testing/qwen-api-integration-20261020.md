<!-- flash-vetting-live-progress-20261020:start -->
**当前增量（2026-10-20T03:48:41.173334+00:00）：真实 Flash Vetting 新 run 已成功提交并运行中，尚未业务验收。** run `a5681c88-5b6c-44e1-804a-0f5012192969`，实际4个来源/旧run预检GET通过、唯一POST返回HTTP200，绑定`qwen3.8-flash`及当前source snapshot；driver每60秒观察既有run，不重发。首次01预检失败仍保留为1GET/0POST。本次新增数据库写在SQL15之后，后继升级前需新备份。真实付费ledger/终态findings/99.8及来源断言待验，不能把17topic计划或51预计generation当实际次数。

Drafting 新null回归已真实RED：29项=28通过+1失败，失败用例覆盖六个可空关系字段；另外两条字符串`null`/foreign隔离及26旧测试均通过。最小修复GREEN进行中，主树parent提示词与本修复均未进入活动正式15。Advisory正常Flash ask及保存重读在本次Vetting付费动作终态后继续；三轮27次受控模型比较不代替应用闭环。Goal保持active。
<!-- flash-vetting-live-progress-20261020:end -->

<!-- formal15-installed-controlled-models-20261020:start -->
**当前核验状态（2026-10-20T03:35:39.928936+00:00）：正式15已实际安装，后端18084/PID44596，精确FILETIME字符串134369401181141747。** 同冻结1506文件的A1235/82与同target/JAR B1/1均全绿，合计1236/83、0failure/error/skip；A32/B38实际属性与自然Job0均有票。旧15首轮1219pass+8failure+8error由两测试旧反射签名引起，9处显式null/45bytes修复后复验，旧红轮不改判。升级前后39完整API body逐字节一致，SQL15为1272078624字节/SHA`3b75228304bfd549705818ceefba15be2f5a2eff397702a65968208ec11c2cd5`，仅逻辑备份未新恢复。H2owner38980未停；安装票`.local/e2e-20261020/runtime/round15-installed-verification.json`。

三模块同冻输入模型对照已实际完成3轮/27POST，0retry/repair/cap1预检；两个Qwen各9/9返回exactmodel/stop/严格JSON，MiniMax2/9返回、7次HTTP529未评分。原文、schema、应用契约与可用局部成果分别核验，不能把此对照当应用端到端或统计稳定性通过。详细逐项证据见[三模型受控对照](qwen-controlled-model-comparison-20261020.md)。新的真实Flash Vetting应用run尚未提交；Qwen持久计数链真实运行仍待验。

Drafting按用户规则保留有来源的独立问句，坏关系另列待人工补齐。三轮Qwen回答暴露parent包含与标量依赖混用；一条parent契约提示已采主树（3158→3377字符），尚未构建/部署，不在上述正式15包内，也未改动原比较输入。新版实际浏览器切换仍因owned TaskSpace丢失未验；记账后继续其他测试。日常真实LLM继续Qwen，MiniMax只作本次对照；Goal保持active。
<!-- formal15-installed-controlled-models-20261020:end -->

<!-- round14-installed-and-controlled-models-20261020:start -->
**当前状态（2026-10-20T10:38:43.551853+08:00）：正式14已安装，后端PID61472／18084。** A1190/76与独立同target/JAR B1/38属性均实际通过，合计1191/77、0failure/error/skip；九份原件完整输出保持。升级前后26完整API响应逐字节一致，新SQL14为1266093696字节、SHA`0dcddc6afb02cc4b23eb15b19de9d038b9682cbcfba4a40d1d4b143ef4a5a568`，仅逻辑备份未新恢复。H2 owner38980未重启。安装票`runtime/round14-installed-verification.json`。活动目录已返回local、MiniMax和两个Qwen profile，27B/Flash均configured=true，两种`X-ConSense-Llm-Profile`头实际接受；这还不是模型推理或浏览器验收。原326error轮及B输出目录遗漏失败保留；B脚本已在发前创建新目录，仅复验1项，A未重跑。

Qwen usage-preflight后继19路径已采主树，隔离106项全绿，25为新policy、81为旧回归。与A1190的精确交集61，后继最小A应1235/82，加B1为1236/83；尚未构建／安装，活动14的Vetting/Advisory不能因此追认预算链通过。fresh Specification正常上传重解析、首次变量HTTP耗时及真实Qwen业务仍待验。外置浏览器旧space2已丢失，按ego技能记录未执行并继续其他任务。

**用户最新要求模型对照控制变量，期望两个Qwen型号改善MiniMax的质量与可靠性。** 三模型须用相同冻结来源／范围、题目、提示词、schema、共同温度及输出上限、同一runner版本；必要provider字段单列。首答比较0自动repair／retry／history，独立评价来源完整性、漏检/误报、引用/条件、错补事实及可用局部成果；官方答案／评价oracle只在模型外保存。首批拟3场景×3模型＝9次回答，尚未释放或执行，不以一次对照授予稳定性。MiniMax仅作同条件对照，日常测试继续Qwen。Goal保持active。
<!-- round14-installed-and-controlled-models-20261020:end -->

<!-- formal14-a-red-20261020:start -->
**当前进度（2026-10-20T09:56:11.241788+08:00）：正式14 A首轮已结束，尚未升级后端。** 实际76类／1190项＝864通过＋326 errors，0 failure／skip；首个已证根因是新增测试helper `ValueTextTcpJdbcProbe` 在未启用TCP测试时注册空server，正在隔离验证整helper仅在 `consense.probe.tcp.enabled=true` 时装配。原失败轮、76原XML及完整身份／错误台账保留，九份完整原件输出逐字节保持，实际32属性逐类齐全，测试Job自然active0且close前active0。独立终态审票：`drafting/formal14-a-independent-audit/terminal-independent-review.json`。原runner还有未走到的69类常量遗漏，后继独立runner将按精确expected身份集合推导类数；不修改原轮追认通过。B未执行、SQL14及26API保全未执行；应用仍为正式13 PID47748，H2 owner38980及前端／OER／OCR监听保持。Qwen预算预检与持久调用预留仅在后继候选实现，业务未通过结论保持。Goal继续。
<!-- formal14-a-red-20261020:end -->

# Qwen API 接入记录（2026-10-20，进行中）

用户最新指示：后续真实模型调用使用系统／用户环境变量 `ConSense-Qwen-API` 对应的千问AI平台，替换本Goal前段使用的MiniMax。前端提供显式选择；`qwen3.8-flash` 即用户所称 Flash-Next。默认先验证27B。原MiniMax运行及失败按实际历史保留。

| 前端名称 | 实际API model | 当前验证 |
| --- | --- | --- |
| Qwen3.8 27B | `qwen3.8-27b` | 已真实完成一次小JSON调用，HTTP200、实际model精确、单choice、stop、内容匹配 |
| Qwen3.8 Flash-Next | `qwen3.8-flash` | 已真实完成一次小JSON调用，HTTP200、实际model精确、单choice、stop、内容匹配 |

## 已核实的接口与凭据边界

- 官方OpenAI兼容base：`https://maas.qianwenaiapi.com/compatible-mode/v1`；Chat路径 `/chat/completions`。
- 当前Java客户端会追加 `/v1/chat/completions`，因此其配置base应为 `https://maas.qianwenaiapi.com/compatible-mode`，避免重复 `/v1`。
- 凭据只在受控进程内读取Windows Machine环境变量，不写明文文件、命令行或日志。前端不能读取该密钥。
- 已真实GET一次 `/v1/models`：HTTP200，包含两个实际model ID；未据目录结果冒称生成验收。
- 随后每个model各执行一次有限Chat调用：`max_tokens=128`、`n=1`、`temperature=0`、`enable_thinking=false`、`response_format={"type":"json_object"}`、`stream=false`，0重试、禁止重定向。两次均通过全部协议检查，分别0.656秒／1.813秒。
- 上述只证明凭据、网络、两个model和小JSON协议可用，不是三模块业务验收，不证明长输入、流式、实际上下文或tokenizer/template身份。

原始脱敏请求／响应和保留重放标记在忽略的 `.local/e2e-20261020/qwen-provider-discovery/`，主要为 `models-api-once.json`、`qwen3.8-27b-json-smoke-once.json`、`qwen3.8-flash-json-smoke-once.json`。脚本 `chat-smoke-once.py` 先独占建立dispatch标记，禁止覆盖／自动重放。

## 应用接入状态

独立聊天profile、适配器和前端选项已精确合入主代码；当前运行后端仍为正式13，尚未完成运行服务切换。前端实际组件／store／i18n／API模块测试、项目typecheck和生产build均通过；这是模拟HTTP边界的组件验证，不是浏览器或业务验收。embedding继续使用原local/BGE链，不能把共享local endpoint直接改成Qwen。配置需保留旧local／MiniMax选择和历史身份。

首轮独立静审发现的底层HTTP隐式重发与JSON转义密钥脱敏两项Must已修。最终真实JUnit29/29、0failure/error/skip，独立二审0Must；两模型×307/308/408/503八组合各只发送一次POST。HTTP错误、无效provider raw和一层合法业务JSON的成功／Incomplete内容按限定契约处理；普通／局部JSON保持字面脱敏，保护Drafting局部结果。旧RED及GREEN02的fixture重stub失败票原样保留。主树采纳及测试通过不代表部署。

Vetting／Advisory现有Chat通道需要绑定完整输入的可信token计数、template/context身份。当前官方Chat／Responses／Anthropic文档尚未证明Qwen兼容的无生成计数端点。不能冒用旧MiniMax计数器、伪造本地tokenizer/template hash或让两个model共用一份只绑定其中一个model的recipe。缺证时保留 `budget_unknown`；继续调查，不阻断其他修复。

官方Responses文档明确最大输入约为模型窗口80%，超出会自动截断，未列参数可能被忽略；因此当前不直接把MiniMax Responses传输重指Qwen。`max_tokens=1` 再读取usage仍是一次真实generation，不得冒称无生成counter，也未纳入当前计数契约。

## 官方文档与浏览器证据

- [首次调用](https://platform.qianwenai.com/docs/developer-guides/getting-started/first-api-call)
- [OpenAI Chat](https://platform.qianwenai.com/docs/api-reference/chat/openai-chat)
- [结构化输出](https://platform.qianwenai.com/docs/developer-guides/text-generation/structured-output)
- [OpenAI Responses及兼容限制](https://platform.qianwenai.com/docs/api-reference/chat/openai-responses)
- [Anthropic兼容](https://platform.qianwenai.com/docs/api-reference/chat/anthropic)
- [Flash模型页](https://www.qianwenai.com/models/qwen3.8-flash)：显示1M上下文、最大输入991K／思考983K；这不是本次服务端完整窗口实测，27B参数不得直接借用。
- [27B模型页](https://www.qianwenai.com/models/qwen3.8-27b)：在模型市场开源家族下拉中明确选择`qwen3.8-27b`快照后，页面也显示1M上下文、最大输入991K／思考983K及最大输出131K。保存`27b-model-context-browser.json`，保留其旧家族标题尚未刷新的实际状态；这个独立页面证据仍不是托管部署完整窗口／tokenizer／template实测。

以上页面使用用户指定ego-browser同一TaskSpace读取，保存浏览器text／links。浏览器还在同一接入任务内，未新建空间绕过控制权。

## 2026-10-20 09:39:17 +08:00 追加的真实协议证据

当前累计6次真实Qwen generation、1次目录GET、0重试：前述小JSON两次；每模型另一次max_tokens=1预检可行性调用（两次prompt usage均为42，与其max128同输入相同，completion各不超过1）；每模型另一次SSE调用（正常Content-Type、单choice index0、精确model、无reasoning、唯一stop及[DONE]，完整拼接JSON匹配）。预检可行性调用是实际generation，不是原生无生成counter。SSE仅验证远端协议，不冒称Java adapter或三模块应用验收。六原票均在qwen-provider-discovery目录，后两对文件后缀分别usage-preflight-viability-once.json、stream-protocol-once.json。

最终main采纳票build/round14-qwen-adoption/receipt.json、独立审票vetting/qwen-profile-final-independent-review-02.json、前端票qwen-provider-discovery/web-green01/receipt.json。正式14的A1190／76类＋B1／1类＝1191／77为精确身份并集计划，尚未执行；不与定向29重复累加。运行服务仍13，Vetting／Advisory输入预算仍unknown。
