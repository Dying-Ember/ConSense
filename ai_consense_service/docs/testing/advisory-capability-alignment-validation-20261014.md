# Advisory 能力对齐：提交与分层验收状态

2026-10-14，Asia/Shanghai。用户明确要求“现在先把代码提交，把测试停了吧”。代码提交 `363096b76f1921ac5f0e43c9f470f3e94c45ba6d` 已推送到私仓分支 `codex/advisory-alignment-20261014`，对应 [PR #10](https://github.com/Dying-Ember/ConSense/pull/10)。远端记录于2026-10-14 21:08:04（北京时间；13:08:04Z）由Dying-Ember合并为 `85dc5efe720dafbe465b674cef637f37f7c6c49b`，本任务未执行合并。远端main为该merge commit，父提交为 `3d582f1` 与被测代码 `363096b`，旧feature分支已删除；本轮文档收尾拟独立提交，不替代实际被测代码身份。本轮测试和未完成开发已停止，不追加模型调用、预算、浏览器或来源验收；A5 未通过；远端合并不表示业务验收通过。

对应[已确认规格](../specs/advisory-capability-alignment-20261014.md)、[任务图](../specs/advisory-capability-alignment-tickets-20261014.md)与[有限分析决定](../adr/0004-preserve-validated-partial-advisory-under-insufficient-state.md)。实施基线 `3d582f10233d744509effc927813981c3d467e69`；PM运行正文 SHA256 `ec23c7a665f803598a82d8aaf41ace65df1e431ac5e4ab24610e61d11ccf0470`。原访谈、源码阅读与旧实验按原日期读取，不追认为本次实际结果。

## 已提交能力

A0完成；A1–A4源码及相关契约已实现：必需专家规范与补充自定义提示词、独立答复单元、真实引用／范围／项目采用门、内部 R1–R10 记录、有据有限分析及必要补问、完整 PDF／DOCX 阅读接线、真实 provider streaming、POST 业务事件、当前请求取消和自然问法的结构化流程图计划。缺证仍为 `grounded=false / insufficient`，模型自检不授法律通过。

已经发布的单元由应用持有；唯一修复只补尚未发布的单元或 envelope，不重交已接受 ID。仅实际流式发布过非空前缀时允许空 sections 元数据修复；同步调用／尚未发布单元须修复完整答案。最终组合重新校验来源、范围、采用、语言、引用、自检与图，再保存历史；错误或取消不落成完成答案。

MiniMax-only 适配器有界处理两种实际偏差：空字符串 finish reason 视为非终态；明确 stop 且 HTTP 正常结束时接受缺 DONE 的 EOF。普通 OpenAI 仍要求 stop＋DONE，无 stop、length、tool、refusal、provider error、异常 JSON、迟到事件、取消及字节边界保持阻断。官方 verifier 将这些列为非标准偏差，本修补不声称官方保证该格式。

仅 Advisory 在本轮改动。活动 dirty 主目录、活动服务／数据库、fixed05 原基准、共享模型、Drafting／Vetting和并行 OER 保持；原件、权重、密钥、DB、provider raw及私有法律台账不进入代码提交。

## 构建、契约与审查

证据根：本机仓库外 `C:\Users\Administrator\Documents\Codex\advisory-alignment-20261014`。下表是相关选定套件，不称全仓测试；重叠结果不相加。

| 项目 | 已核结果 | 证据 |
| --- | --- | --- |
| 后端测试 | worker `66f83b2` 实际291项，290 passed、1 existing optional prepared replay skip、0 failure/error；84 focused 与其重叠 | `minimax-stream-compatibility-01/test-package-receipt.json`，SHA `9d19ed9674fb094701aba55f342e71d867c4b096ff7762cebb1dfe08be0e4a50`；本地 HTTP/H2 fixtures及冻结旧输出回放，不是新模型／部署验收 |
| 后端主构建 | `363096b` package12 exit0，`-DskipTests`；backend tree与被测worker精确相等 `8da43d6b69e5ab85285bc547150efc331007abc5`，继承上述测试；JAR79,520,426 bytes，SHA `e0885913c15909108a7ac2a7e924a815b0b49307b71dc0580321ec07436ce003` | `final-java-package-12-result.json`，SHA `205c2b457572bea7d28b51d60695089ab9fba732b416828b1aeaa896d607fa59`；本次未重跑测试 |
| 前端 | `final-front-05` 实际112/112；主build08 exit0，未重跑测试，精确相同tree `0e6f4755f29fac5f191177a12d14e08cc7d7aa22`继承112项 | `final-front-build-08-result.json`，SHA `3008dbfb4c0eb47597e0907ec1e0993a952c334e1335dceb4b6e80951072389d`；reader worker15项重叠不另加 |
| stage07 | 固定 `363096b`、上述JAR、完整frontend dist与批准prompt | `runtime-01/prepared-code07-receipt.json`，SHA `f7960675fd52108062e66db06fd0af09a78973b1328b968d5357bdf9a4cd79ab`；准备不等于运行／业务接受 |
| 两轴审查 | 既有语言、前缀修复、同步兼容与最终引用发现已闭合；最新MiniMax delta Standards 0 hard findings／0 smells、独立Spec 0 findings | `merge-minimax-stream-compatibility-receipt.json`，SHA `40f05f13fadc5746a622815f13ebce9f92404f27fc7a242ec42c00836b7db483`；A6有界审查完成，A5业务门未通过；远端PR已合并 |

历史 package10的4 failures和1 replay路径error保留；后续278项／277 pass／1 skip已修闭相应回归，最新291项覆盖相关变更。旧失败收据、report-scope correction与run01–03回放不改写，不能重复计数或追认为新模型通过。

## 真实流程图与 M2.7 对照

用户已批准204,800总上下文tokens的MiniMax-M2.7短任务对照。官方API支持M2.7／M2.5；同页AA-LCR v1.1为Qwen3.8-27B Xhigh82%、M2.7 78%、M2.5 73%，只是长文推理信号，专业交付仍有差距，不能视为合同正确率相同。研究原始来源receipt为 `model-comparison-01/research-source-receipt.json`，SHA `7b7bdff7aa280aba5da94a76d9428a81482c6aa5477b576e05839d7c4c370e50`。

修补后capability对照使用同一冻结证据包、普通官方1.6问句、当前同步Publication最终Harness、最多一次服务端修复、0 transport retry。它不是fresh retrieval／真实项目session／前端业务验收；没有在provider生成中发布正文单元。输入预算来自UTF8字节与framing／output／safety启发式，不是tokenizer或provider count实测；provider usage为null。至少256K的主模型守卫及M3 Responses 1M契约保持，未把M2.7采用为主模型。

| 实际采集 | 结果／耗时 | 判定及证据 |
| --- | --- | --- |
| 修补前 `m27-official-01`／`m3-official-01` | M2.7确返回该型号，明确stop但缺DONE，84.5667秒，旧adapter拒绝且正文JSON不完整；M3第一个空finish header即拒绝，9.0426秒，剩余流未观察 | 两者保留失败／unscored；不能比较这两段耗时优劣。`minimax-stream-compatibility-01/original-evidence-receipt.json` |
| 16K输出 `m27-official-02` | 两次正常stop；首答缺图单元checks，修复JSON不完整；71.4073＋64.2364＝135.6437秒 | `contract_invalid_after_one_repair`，无接受答案。receipt SHA `0decba222bf1d7d53205133b785e94ade673eeba42fd8368c4c7e91f90998be5` |
| 16K输出 `m3-official-02` | adaptive，176.3652秒，finish_reason=length，无最终正文 | `failed_no_blind_retry`。receipt SHA `bf8d3f3b508fd94fa7d07fb25df9be7363afc44eb4f2c42fac3fe85671e3f428` |
| 32K输出 `m27-official-03` | 首答JSON不完整，93.7008秒；唯一修复JSON合法但limits／scope／graph actor等契约不满足，92.7998秒 | `contract_invalid_after_one_repair`，无接受答案。receipt SHA `c5b9092abb64134ff18b0e0cbefe2151141c1073f09b9ceb3fd88438ddc23387` |
| 32K输出 `m3-official-03` | adaptive，293.0791秒，finish_reason=length，无最终答案 | `failed_no_blind_retry`。receipt SHA `cb0300add1ba733d136320ced97a23b8594b2147ccb443e521c5657750dc0334` |

各重测receipt路径为 `model-comparison-01/<采集名>/receipt.json`，返回型号与请求预算逐次记录。所有这些采集均未得到可接受最终图或答案；本次不能宣布M2.7比M3更好。M3 disabled native baseline仅准备后被用户停止，未运行，不补算一次实际试验。

独立安全packet availability收据确认选中38项来源语义均存在于该冻结输入，10/10 literal source spans匹配；这是选中内容可用性，不是38份文件、全源质量或图／法律正确率。路径 `model-comparison-01/evaluation-source-only-01/packet-availability-01/published-safe-availability-receipt.json`；官方答案、图及私有法律纠错未进入生成／修复。其余candidate法律检查仍单列，不公布法律准确率。

此前真实应用run04为 `a94e4d2`／stage05／project06：9 phase、2 narrative sections、1 error4205、0 complete、无图、失败history为空。初图被分支完整性检查拒绝，修复改变已发布前缀后失效；该问题驱动现已提交的应用持有前缀修补，但新契约通过不能改写旧真实失败。普通中文短问因首错误未执行，图及最终答案分数保持null。安全结果见 `evaluation-source-only-04/published-output-summary.md`。

## 原件浏览器与开放验收门

| 验收组 | 已观察事实 | 未通过范围 |
| --- | --- | --- |
| 原正常项目索引 | 单次index02 rebuild实际77/77、1 document、stale=false，独立后续status确认同key/signature；原60秒collector timeout保留 | 索引不是来源质量／采用／问答验收；本轮未再rebuild |
| PDF原件阅读 | 旧真实history citation，非fixture；完整658,169bytes，SHA `d7d74db6d9aee390093b9e22bb30dccdbf3c0316d47635922109422278d161e7`，74物理页，准确17／13，邻页14可读，旧460px外框cap解除 | 无精确几何高亮，warning可见；这是旧reader sample，不是新T6图引用接受。证据 `browser-source-01/pdf-final-browser-receipt.json` |
| DOCX | 原包／part hash／唯一native节点／整表上下文有代码和契约 | 实际citation→完整DOCX→唯一节点／部件与邻文浏览器链未通过 |
| 新图浏览器 | 隔离组件准备／build完成，0已接受candidate | 未实际渲染业务图；不能将准备／build称为浏览器通过 |
| 有限分析、专家语义、实时交互 | 已有契约与旧实际错误终态，无伪造完成history | 项目缺条件自然题、R1–R10正反语义、成功history、完整provider发布时序、停止／断线／滚动及来源／会话竞态未完整接受 |

用户要求“能画出来最重要，并保证例题画对，同时可能换成其他问题”已记入规格；通用图生成／渲染、例题合同路径、另一类自然图问题分别未通过。generic graph-first及一次返回全部诊断的后续方案仅为只读建议，未进入当前代码，用户停止后不继续开发或测试。不新增GCC17.1／题号固定答案，不把官方答案或私有纠错提供给同题生成。

本轮owned测试进程已停止、provider调用已结束；历史project06等隔离运行按其独立stop receipts读取，旧closed snapshot07 SHA `d85b0f7e26db33d5ef63fad51156a9a0ea1b7589f594568a7329bab970a30042`仅表示该历史闭库，不是新的运行存续证明。停止测试不授业务通过，也不自动部署。目标MySQL nullable `advice_checks_json`迁移、Linux／H800与生产切换未验。

**提交状态：代码已推送，远端PR已合并；A1–A4代码及相关契约已实现，A5未通过且本轮测试由用户停止，A6有界审查完成。** 合并是现场读回状态，不是本任务主动执行合并，也不授业务／部署接受。 失败与未执行状态保留，不安排后续自动测试。
