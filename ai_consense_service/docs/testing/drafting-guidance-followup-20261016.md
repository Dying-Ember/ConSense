# Drafting guidance 两路复验：M3预算与细化任务编排

日期：2026-10-16，Asia/Shanghai。状态：**全部18次模型调用已收口，停止新增；未授完整流程稳定性或业务通过。** 上轮[8次失败及未执行阶段](drafting-guidance-minimax-stability-20261015.md)保持。本轮原文、提示、请求响应、独立评分与脚本在忽略本地 `.local/drafting-guidance-followup-20261016/`；不向Git复制原件、密钥或完整运行证据。

## 共同业务与预算

NTT、SCT、Word SCC属于同一项目，全部97 guidance联合归集，不按文档切断共享变量和跨文件关系。PDF SCC、Qwen27B、GPT-5.6 Luna、OCR、产品和实际模板编辑不在本轮。目标仅用测试标识，完整来源与来源版本各自保留。

共享官方transport不自动重试，不重定向，不改原始JSON、重复键或引用来授通过；全局文件槽限定并发2，调用账限定A≤5、B≤22、合计≤27，总声明最大生成预算累计≤1,048,576 tokens。超时的供应商实际用量和取消状态保unknown。原始失败与程序生成的来源投影分开报告。

## 路A：只改变M3预算／思考模式

固定上轮Phase B输入和system；请求中的用户内容、温度0.2、非流式及reasoning_split=true均保持。第一方依据已核：stable M3可用thinking adaptive/disabled；reasoning_effort档只对M3.1-Flash-Preview生效；总生成预算包括reasoning；没有独立reasoning budget字段依据。新集成推荐max_completion_tokens，本次沿用原max_tokens字段以少改变一个实验因素。

官方依据：[OpenAI SDK](https://platform.minimax.io/docs/api-reference/text-openai-api)、[Chat reference](https://platform.minimax.io/docs/api-reference/text-chat-openai)。M3当前文档推荐总生成131072、最大524288；本轮较小预算是试验边界，不是官方最大能力。

| 条件 | 总生成上限 | 思考 | 重复 | 目的 |
| --- | ---: | --- | ---: | --- |
| 历史对照 | 32768 | provider默认 | 已有2 | 复用原始长度截断／完整但不合格结果，不称本轮重跑 |
| A1 | 65536 | adaptive | 2 | 看增加预算是否能稳定给出完整最终内容 |
| A2 | 32768 | disabled | 2 | 看关闭思考的完整性、耗时与原文语义变化 |
| A3（条件性） | 131072 | adaptive | 最多1 | 仅在新A1实际length截断时调用，不修复语义／JSON失败 |

保存实际usage、可用的reasoning_tokens、最终内容、耗时和严格校验。字符数不是token数；两次完成只能给当前样本的观察边界，不能证明最小推理上限。

## 路B：按原子职责拆开编排

1. 三份guidance一起归集要求和局部候选；先不同时输出全局类型、完整图、反向来源表或准确抄写引文。
2. 模型单独提出可审alias，程序统一重写所有引用、派生反向来源并按来源ID回取原文。不能猜alias填补悬空引用。
3. 每变量或完整语义簇用独立上下文。来源保相关父条、选项、Notes和跨文件引用；类型、前置、共同作用、分支和动作分别表达，修改目标用测试ID。
4. 程序合图片段，模型集中复核；明确疑点才有限回查。

每模型全量要求归集重复两次；3个代表簇各重复两次；alias和合图复核各一次；回查最多一次／模型，累计B≤22。3簇为三文档发放格式×双信封、NTT／SCC保证金、Word SCC屋面共同作用；不为凑三文档给保证金或屋面强造无来源关联。私有断言仅独立评分，不输入模型。

为识别编排效果，B中M3显式adaptive、M2.7省略thinking，均最多32768生成、temperature0.2；不把M2.x接受但忽略disabled当作关闭。每模型至少一份原始输出经当前schema／来源校验，可按最小合格序号继续下游；只有两份都合格才授该阶段的本轮重复通过。选择可消费分支不抹去另一份失败。没有合格输出时保blocked／未执行；不以手工答案顶替。

## 当前结果

### 路A已结束：5次

| 条件 | 秒 | 实际总生成tokens | 其中报告的reasoning tokens | 原始最终内容 |
| --- | ---: | ---: | ---: | --- |
| adaptive 65536／1 | 163.641 | 36550 | 26442 | stop，但JSON封套不合法；诊断items仅NTT11条 |
| adaptive 65536／2 | 301.875 | 65536 | 38475 | length；items虽已列97，后续变量表截断 |
| disabled 32768／1 | 116.156 | 24536 | 未单列；可见reasoning为空 | stop，内部JSON语法错误，变量定义还缺meaning字段 |
| disabled 32768／2 | 136.657 | 25394 | 未单列；可见reasoning为空 | stop，原始JSON完整；1处known type与unknown_reason契约冲突 |
| adaptive 131072／1 | 130.328 | 28760 | 6127 | stop，原始JSON完整；3项来源／origin契约错误 |

A原始严格契约0/5，原始JSON完整2/5。关闭思考两次均未长度截断，但不能由此授语义正确。128K配置那次实际只用了28760，不能声称它证明需要128K；同输入adaptive推理量从6127到38475波动明显，没有测出最小独立推理上限。三档messages SHA均一致，实际比较只改既定mode和max_tokens。

独立原文检查见本地 `route-a/independent-review.json`。预算与语义分别报告：若干条件／专业范围仍有误合并、前提未变量化、跨文件采用引用缺关联，另出现NSC无源扩写风险；局部正确观察和未执行typed链也分别保留。不把诊断移除封套、拼接对象或修语法当原响应通过。

### 用户新增prefill假设：不删证据的压缩对照

用户2026-10-16询问是否应减少prefill、提示词和参考长度。为避免同时改任务，首个对照仅压输入表示：保持路B系统提示、输出契约、模型参数和全部97guidance／194原段及ID不变，逐note改为紧凑数组，去掉重复字段和包装。原始顺序、ID、原文逐项自检一致。

wire用户正文从38809字节降到26497字节，约减少32%；字节不等于模型token，实际用量以APIusage为准。此测试同时改变了输入序列化语法，不作纯token数量因果证明。独立复核另发现压缩包移除了`source_bracket_complete`等元数据，其中NTT-GN011原为false；97notes／194段的ID、原文和顺序虽保持，来源完整性状态没有等价保留。因此这不是无信息损失的纯长度控制，不能将变化唯一归因于prefill；原请求和失败保持，不为修正此控制追加调用。

两个模型各同输入重复两次，共4次，从B的22调用额度中扣除，不扩大总27。结果如下；以下结构通过均指当前严格schema和来源ID门，不等于语义准确率：

| 模型／输入 | 实际输入tokens（r1／r2） | 秒（r1／r2） | 实际总生成tokens（r1／r2） | 严格结构通过 |
| --- | --- | --- | --- | --- |
| M2.7／B原输入 | 11229／11227 | 468.781／200.047 | 27716／16155 | 0/2 |
| M2.7／紧凑输入 | 7938／7938 | 314.719／304.422 | 23174／23391 | 1/2，仅r2 |
| M3／B原输入 | 11383／11383 | 217.922／214.609 | 21893／21554 | 2/2 |
| M3／紧凑输入 | 8092／8092 | 101.828／92.234 | 18827／18830 | 0/2 |

两模型输入约少29%，M3紧凑两次总耗时也更短，但M3原契约2/2变0/2。r1将evidence_ids写成evidence；r2为requirements普遍缺unknowns、note增加issues。M2.7 r1漏reason并增加note-level issues；r2原样通过结构门。原错误不修改。四次均保全部97note ID与顺序，但note出现不等于内部要求完整。独立核对4/4发现：M2.7合格r2仍捆combined contract与foundation works、漏独立安装存在候选；Specification区域候选有所改善，但局部删除reference被概括为整条删除有过宽风险。M3两次保屋面前提，bond原子化和屋面删除范围仍有波动。全部仅为代表要点诊断，不授全97note语义通过；详见本地`evaluation/prefill-semantic-results.json`。

非流式只有请求总耗时，没有TTFT／纯prefill分段时长。第二次有大量cached_tokens，缓存、生成长度、服务状态和输入语法／元数据均为混杂，不估算纯prefill提速。M2.7 raw usage虽报告reasoning_tokens=0，但可见reasoning_content非空，保持供应商原usage并把精确token拆分标unknown，不能说没有思考。M3紧凑两次报告reasoning5704／5658。

按预先最小合格序号规则，M2.7紧凑仅选r2继续单独METHOD诊断，最多5调用（局部alias1／atomic2／review1／回查≤1），其他簇不执行；M2.7原基线仍0/2阻断。该分支及独立M3 local METHOD均不替换原失败或补作全流程验收。只读因果复核和协议勘误分别在本地`prefill-control/causal-review.md`、`protocol-errata.json`。

### 路B基线已收口：5次实际调用

M2.7 discovery严格结构0/2：首轮为完整JSON但note封套新增unknowns、残缺note的reason为空；第二轮原始JSON语法错误。M3 discovery严格结构2/2，每次均覆盖全部97notes；首轮130要求／199候选，第二轮134要求／182候选。M3在屋面前提、双信封与BQ、Tin Shui Wai删除方向等局部语义上有进展，但bond的combined contract与foundation works在第二轮又捆为一个候选，不能授完整语义重复稳定。

M3全局alias输入33306 tokens，总生成达到32768后length截断；raw最终JSON不完整。该调用耗时327.594秒，reasoning字符112838、最终内容8472字符，字符量不作token分摊。全局alias门失败，原子三簇、合图复核与回查均未执行。两模型×三簇×两次×六断言的72个计划原子评分保持not_observed／blocked_not_executed。模型自评、修JSON或截断片段均不能顶替该门。

单独标识的M3 local METHOD诊断只把合格基线r1中的候选按冻结METHOD源集合筛选，保完整相关notes及来源状态；33notes／35要求／53候选，alias实际输入9075tokens，总生成9720（其中reasoning8333），64.234秒、stop且JSON完整。然而最后一组distinctions将o0070与自身比较，并明确写placeholder／N/A，触发duplicate reference，严格门失败。保原始输出，不删该组换绿；atomic／review／recheck零调用。局部化本次没有截断，但没有解决引用／指令遵循，且不是跨簇覆盖已验收。该variant最多5、实际仅1调用，结果及元数据勘误在本地`route-b/m3-local-method/`。

### M2.7紧凑输入后的METHOD诊断：3次实际调用，已停止

合格紧凑r2按冻结METHOD集合机械筛选33notes／33要求／36候选，局部alias用原来源状态，不人工改候选。alias实际7540输入／4077生成tokens、79.563秒，原始JSON及严格schema／来源ID门通过。独立源核仍发现部分候选含义被上级requirement干扰、相同Bill组的引证描述不准；结构通过不授这些语义正确。

| 阶段 | 输入tokens | 生成tokens | 秒 | 原始结果与门 |
| --- | ---: | ---: | ---: | --- |
| alias | 7540 | 4077 | 79.563 | stop、JSON完整、结构／来源ID通过 |
| atomic r1 | 18096 | 8104 | 111.062 | stop、JSON完整；V1布尔domain含字符串unknown，严格类型门失败 |
| atomic r2 | 18094 | 8358 | 120.687 | stop、JSON完整；5个V1变量domain把true／false写为字符串，严格类型门失败 |

r1为15变量／12规则，r2为15变量／13规则。没有把字符串转布尔、删除unknown或修输出后授绿；布尔值域与未知状态的区分仍是契约要求。atomic0/2严格合格，review／recheck被阻断、零调用。原始结果在本地`route-b/m27-slim-method/`；两次语义诊断单独核，不用类型错误直接代替语义准确率，也不回填原基线72个未观察评分。

独立源核12项局部断言结果为2 pass／6 fail／1 unknown／3返回内容未观察，不作总体准确率。r1另有V2域使用字段名字符串、条件对V8列表按字符串比较，不能说只修一个unknown就全绿。具体语义缺陷包括Technical Proposals包组成误扩、未保LoA仅接受部分的条件、非双信封分支缺联系SQS出口、用E2(Not used)标局部删短语，以及跨SCT格式变量的一致性不足。36候选到15变量、33要求的程序账零丢失已核，但账保全不证明alias语义正确。详见本地`evaluation/m27slim-method-semantic-results.json`；这些反例承接到新活动作为已暴露开发回归。

### 本地Bonsai评估：本轮不加载、不试跑

用户补充本地Bonsai27B可运行但速度慢、显存限制上下文与生成，并要求若不适合就不试。只读核对确认模型文件`Ternary-Bonsai-2-27B-PTQ1_0.gguf`仍在，5946648928字节；磁盘runtime为Enabled=false、65536上下文、16384总生成、单槽、F16 K/V、reasoning medium／4096。当前未观察到llama/Bonsai进程，未查询有效运行窗口或实际GPU offload，不把配置当当前运行读回。GPU快照为RTX A4000／16376MiB，快照不是可安全新增负载的资源承诺。

重读已保存且hash绑定的round5性能证据：28调用共53089生成tokens（含思考）、1749.474秒wall；generation1706.283秒／97.53%，prefill37.880秒／2.17%，generation约31.1138tokens/s。这是旧运行实测，不是本轮新benchmark。按旧速率粗算4096生成tokens约132秒，且本轮MiniMax多阶段任务常有长输出；不同tokenizer不可直接换算。本轮决定不启动Bonsai整流程稳定性试验，优先缩小生成任务和上下文职责；压缩版结果也不能直接验收H800上的原版BF16／FP8 27B。

64K保存窗口本身不构成本轮guidance输入必然装不下的证据；本轮MiniMax输入约8K–11K只作任务规模参考，tokenizer不同不能精确套给Bonsai。实际显存峰值、全部offload、峰值KV和适合本轮的有效窗口没有实测。因此不把本次决定写成“硬件绝对跑不了”或“原版27B能力不行”；决定基于旧吞吐、生成余量、量化版代表性和当前试验收益。

保存的只读评估为本地`bonsai-readonly-assessment.json`，历史来源与边界详见[本机上下文归因](../research/bonsai-local-context-capacity-attribution-20261012.md)。本轮没有Bonsai新benchmark或加载，当前不宣称完整流程或27B通过。

## 当前最小设计建议（未实施到产品）

- 联合要求入口保NTT／SCT／Word SCC全部notes及来源缺口状态；原文只存一份、按ID引用，减少重复document／SHA包装，不删决定条件、否定、例外或续文。
- 归并与原子拆解按完整语义簇给跨文档原证据，模型只回答当前待判问题；跨簇未决保持账本，不能默认为没有其他关联。
- 程序接管稳定ID、引用重写、原引文回取、反向来源、重复／自比较和计数；模型不再抄全量inventory或替程序维护多份来源表。
- 各步使用固定输出形状、最小必要字段，未知及来源不足明确表达。单次更短的输入或stop不是业务门；完整来源覆盖、正确分支、修改范围及重复性独立核验。
- 仅缩短prompt无法消除本轮已观察的schema／语义错误；不追加本地Bonsai、1M扩窗或新模型试验来绕过这些失败。

## 执行收口

本轮实际18调用（A5、B原基线5、紧凑对照4、M3局部METHOD1、M2.7局部METHOD3），低于27调用硬上限。声明生成上限累计753664，供应商实际completion合计415343、prompt合计320022；不同阶段、模型和tokenizer混合用量仅为资源账，不作准确率。16次stop／2次length，所有18份请求SHA与派发账匹配、回执齐全、公共租约清空；不再新增、重试或扩变体。完成回执在本地`final-dispatch-summary.json`。

共享transport的离线负例自检通过；ID／194段原文顺序、系统提示相同和请求hash机器核查通过；来源元数据不等同另记勘误。这些只证明实验边界和记录一致，不授语义／业务通过。完整图谱、跨簇发现、可执行派生结果、真实目标定位／改文、PDF SCC、Qwen原版／本地Bonsai、本机OER、浏览器业务及H800均没有在本轮验收。未变更产品、活动服务、DB／索引、OCR配方、原件或权重，也没有commit／push。
