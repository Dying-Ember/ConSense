# Vetting 在 RAG 初筛之后：当前任务证据与模型选择

日期：2026-10-12，Asia/Shanghai。沿用研究技能；本轮仅读取当前入口／handoff、已有任务与质量报告、已保存业务记录，并补查五组第一方方法资料。未执行模型／API推理、下载权重／benchmark原件、运行测试、修改代码／harness／服务／scope或handoff。**本文建议是暂定研究意见，未默认成为已采纳选型。**

## 1. 结论先明确

用户最新明确要求同时评价Drafting、Vetting和Advisory；后两者先经RAG筛出相关证据。对Vetting，选择的是**在相关、角色化的证据包里判断真正问题，并完整说明依据的LLM**。RAG减少无关材料和上下文压力；它没有替LLM解决责任对象、触发条件、例外、采用关系、真正冲突和完整发现，也没有把未检索到的内容证明为全文不存在。

当前公开证据足以支持：**Qwen3.8-27B的有源多文档推理值得作本地基线；Flash-Next在专业／办公任务上可作较大模型挑战者；旧122B与Nemotron Super继续作长文推理／长窗对照。** 现有证据不足以宣布四者在RAG后的Vetting已经形成胜负排序。没有核得它们同源的合同矛盾＋条件例外＋完整finding＋双侧引文评价；82%的AA-LCR也不能叫82%的Vetting准确率。本机Bonsai受限配方、M3的Drafting成绩及部署容量分别保留，不跨任务继承。

最有价值的新界限是：**LegalBench-RAG只测检索，ContractNLI测给定合同的三分类与证据，CLAUSE测合同缺陷发现／解释，Legal RAG Bench测检索后有源答案。** 它们分别约束不同失败；不能把含RAG字样的榜单都当作LLM审查排名。

## 2. 当前LLM实际在做什么

现有源码边界采用[当前任务证据核对](llm-task-baseline-current-evidence-20261012.md)第1／2／4节，业务边界以[Vetting说明](../vetting.md)为准：RAG及reference expansion提交tender／standard／project／manifest角色化窗口和JSON schema；LLM返回`assessment`、类型／严重度、问题、影响／建议及`{chunkId,side,quote}`。程序做提交chunk身份、原文定位、两侧比较依据、来源有效性及schema检查；仅通过的`issue`进入候选，`consistent`和`insufficient_context`不变成finding。**引文可定位与语义正确是两道不同门。**

| RAG后任务 | 给定证据时LLM必须做的判断 | RAG／程序能提供的帮助 | 当前可以确定的缺口 |
| --- | --- | --- | --- |
| 跨文件责任与数量协调 | 比较同主体、工作对象、地域／阶段、时间、义务语气及触发；兼容最低值不误报为冲突 | 提供来源角色和成组正文、显式引用目标 | 同角色名或不同数值不足以证明互斥；当前四候选无该任务统一成绩 |
| 条件／例外／引用闭包 | 保留AND／OR、unless／provided／notwithstanding、父子范围、Not used与明确修订；判断谁在何条件下生效 | parent／continuation／reference expansion、来源版本和结构 | 找到主句不保证例外已入包；引用闭包成功传输不保证模型理解其作用 |
| 真冲突与协调风险 | 区分两规则不能同时满足、不同对象／时期、补充、明确替换、依据不足；不能由日期／上传先后自动定优先级 | 显式角色、版本与采用metadata | 无来源证据时不能臆定法律优先级或替用户裁定采用；缺文保持insufficient |
| 完整发现与报告 | 输出证据包内全部可审问题，按问题实体去重，保持类型、严重度、影响、建议和覆盖限定 | 多topic、packet ledger、重复范围去重、报告归并 | “给定问题答对”不证明未知问题能全部找出；0finding也不证明无问题 |
| 双侧引文与论证 | 两侧引用支持同一比较命题，包含责任主体／否定／例外；解释为何矛盾或有协调风险 | exact quote定位与chunk membership检查 | 字面引文存在仍可能所属章节、对象或逻辑关系错误；引文连续性不能由语义相似替代 |
| 缺证与absence | 区分“此包没给”“当前完整适用范围未出现”和“合同必须有但缺失” | 显式omitted／partial／pending及范围状态 | RAG top-k为空或没有返回该项不能证明全文absence；必要unknown仍需模型校准 |

数量例子由当前业务规则推导：同范围的“至少2名”和“至少3名”可以同时满足，应避免真冲突误报；“恰好2名”和“至少3名”也只有主体、时期与范围相同且无例外／修订时才可能互斥。这是任务解释，**不是已运行模型题目或新采纳规则**。

## 3. 第一方方法证据：哪些真的测RAG后审查

### 3.1 AA-LCR：有源多文档推理强代理，法律子集很小

[AA-LCR作者固定dataset card](https://huggingface.co/datasets/ArtificialAnalysis/AA-LCR/blob/9a77ef56b717057ade24ceab4d273712a0b4f19e/README.md)明确将该题全部相关文档按既定顺序放入同一prompt，再提问；答案需要跨资料推理，不能直接检索一句取得。全数据**100题／30个document sets／234份文档**，document set平均**99,325 `cl100k_base` tokens**；Legal只有**6题／2 sets／23文档**，平均116,525 tokens。数量来自card的统计表，不把82%总分说成法律子集得分。

它覆盖多源联系、时间／条件规则、排除规则及法律适用解释，因此对**证据足够后的**Vetting语义有价值。它没有独立检索阶段；不测RAG召回，不要求返回合同全部缺陷，也不按本项目的两侧连续quote／scope判断评分。当前judge比较候选与参考答案，v1.1改16个answer keys并加judge system prompt；作者明确v1.0不可与v1.1直接比较。约100K平均输入也不证明完整256K／512K能力。

### 3.2 LegalBench-RAG：这是检索测量，不是生成／审查成绩

[作者仓库固定README](https://github.com/zeroentropy-ai/legalbenchrag/blob/431bc8f2488a81569ab7259fa633dcc50ab77f9a/README.md)明确称其为IR benchmark，按query检索有源snippets，可确定性算到**character-level precision／recall**；每个gold snippet绑定file path及字符范围，来源包括ContractNLI／CUAD／MAUD／PrivacyQA。LLM参与题目生成不能使它变成LLM审查评测。

对ConSense，它可约束“初筛有没有取到正确正文、少取／多取多少”；无法据其分数选择Qwen27／Flash／122B／Super，也不证明例外闭包、source role、最终判定或finding completeness。其“最小相关片段”目标不等同本项目需要完整条件／父条／表组；定位最小gold与安全补齐范围可并存，不能为检索precision砍掉必要例外。

### 3.3 ContractNLI：合同判定与证据最直接，但仍不是开放审查

[Stanford作者任务页](https://stanfordnlp.github.io/contract-nli/)定义**607份NDA、17个固定hypotheses**，判`Entailment／Contradiction／NotMentioned`并标sentence／list-item证据；强调negation by exceptions。Evidence在有矛盾／蕴含时应全面识别冗余支持，可以不连续。该方法直接支持区分“真冲突”与“未提及”，以及单句主张必须带条件与证据。

限制同样具体：固定17种NDA断言，不是跨文件工程投标职责，也不要求自行发现所有问题；其允许不连续的多个evidence spans，不能当ConSense单条`quote`允许删掉中间正文。RAG后若只给局部证据包，NotMentioned仅约束包内观察，不能自动外推为整份合同。作者原始基线是Span NLI BERT，本轮没有当前四候选在同数据／配方上的分数。

### 3.4 CLAUSE：发现、漏报、误报与解释分开，最接近Vetting输出

[作者论文v1](https://arxiv.org/html/2511.00340v1)的in-text部分涉及歧义、遗漏、术语错配、结构缺陷及互斥；Eval3要求找出全部discrepancy spans及解释，分别记录漏报／额外发现、位置与解释质量。它比闭卷法律考试更接近Vetting报告。**这是方法相关性，不是四候选的已有成绩。**

可引用的一项具体实验反例来自论文Table3：旧Gemini2.5 Flash在CUAD的in-text inconsistency precision／recall／F1为**48.2／90.1／62.8%**；说明高召回可伴随大量误报。该行是Eval1 binary discrepancy检测，模型同时看original与perturbed，不能叫开放审查的全部finding recall。Eval3又分span matching与解释；一处sentence intersection就可能形成位置match，不能当本项目精确双侧引文正确率。论文只测GPT4o-mini、Gemini2.0／2.5 Flash和Llama3.3 70B，没有Qwen3.8或Nemotron3 Super。

法律／external-law部分基于美法及RAG生成校验的扰动，和本项目当前source-grounded合同判断不同。本文优先借in-text的发现／解释及Miss／Extra分解，不引入未提供法律当依据，也不将生成阶段RAG混同正式审查时的检索质量。

### 3.5 Legal RAG Bench：分开检索、correctness与groundedness

[Isaacus作者仓库固定README](https://github.com/isaacus-dev/legal-rag-bench/blob/9e30a36d1ef58cd35ec4ed724ef3e5edc6b0d00b/README.md)保存每题retrieved context、human answer、judge的`correct`／`grounded`及`relevant_passage_in_context`，可以区分正确材料未供应和给了材料仍推理错误。该拆分正好支持ConSense的证据供应与LLM语义责任划分。

其现有默认研究为embedding×generation组合、k=5、100题；报告生成日是2026-02-20。题目是刑法程序问答，仍不是开放Vetting finding completeness；作者也是法律embedding产品供应者，方法与自报比较的商业背景应保留。本轮未取得当前Qwen3.8／122B／Super同源生成分数，不搬旧GPT／Gemini结果替它们排名。该方法更多支持Advisory的RAG后有据答复，但也说明Vetting对每个claim／finding须独立核support。

## 4. 四候选已有纸面证据与可比限制

| 候选 | 现有相关数字／精确出处 | 对RAG后Vetting可以作的推断 | 尚不能作的结论 |
| --- | --- | --- | --- |
| Qwen3.8-27B Xhigh | AA-LCRv1.1 **82%**、GDP.pdf All-pass **17%**；[AA与3.6-35B同页](https://artificialanalysis.ai/models/comparisons/qwen3-8-27b-vs-qwen3-6-35b-a3b)，研究日已重新读回 | 有源多文档推理和完整交付代理较强，支持本地基线研究 | 不等于82%合同审查、不等于FP8或Bonsai同配方成绩，不证明完整finding／256K／512K |
| 同3.8-27B non-reasoning | AA-LCRv1.1 **69%**、GDP.pdf **5%**；[与Xhigh同页](https://artificialanalysis.ai/models/comparisons/qwen3-8-27b-non-reasoning-vs-qwen3-8-27b)，本轮重新读取 | reasoning配方确实影响这两类任务，不能直接把Xhigh分数给off | 不证明Xhigh在真实Vetting更稳或总延迟更好；medium／bounded thinking没有这张表的已验分数 |
| Qwen3.8-Flash-Next | 官方同表对27B：IFBench **81.3／79.5**、CoWork **73.9／70.7**、Job **55.7／33.4**；[固定card](https://huggingface.co/Qwen/Qwen3.8-Flash-Next/blob/de4b8e4d43b917e7706784d8bb445c9af86a3540/README.md) | 专业／长程办公任务提供挑战者价值，尤其复杂输出和持续工作 | 不证明合同语义增益；未核同口径AA-LCRv1.1或Vetting专门成绩，容量／offload另行准入 |
| Qwen3.5-122B-A10B | 自己Qwen历史同表AA-LCR **66.9**、LongBenchv2 **60.2**、BFCLv4 **72.2**；[固定3.5-27B card](https://huggingface.co/Qwen/Qwen3.5-27B/blob/fc05daec18b0a78c049392ed2e771dde82bdf654/README.md)。NVIDIA对照表AA-LCR **66.90**、RULER512K **95.95** | 有多源推理／长窗对照价值，RAG工具交接只给辅助权重 | 不跨表与当前82%相减；AA版本／recipe未齐，不是GPTQ4合同质量或更大必胜 |
| Nemotron3 Super120B-A12B | 自己NVIDIA同表AA-LCR **58.31**、IFBench prompt **72.56**、RULER256／512／1M **96.30／95.67／91.75**；[固定card](https://huggingface.co/nvidia/NVIDIA-Nemotron-3-Super-120B-A12B-BF16/blob/2dc98e2afe4face0e4ce40972a915c45368bd34a/README.md) | 明确长窗合成检索表现、中文支持，适合作更大长窗研究对照 | RULER不是责任／冲突／引文；同表AA-LCR弱于旧122B，不能用窗口或active参数代替语义比较；没有Hopper4bit业务成绩 |

NVIDIA同表Super／Qwen122的AA-LCR58.31／66.90、IFBench72.56／73.77及RULER512K95.67／95.95，只能作为其作者配方里的相对证据。当前AA82／69基于v1.1，历史card未给该版本；四模型不能拼出一个伪统一榜单。BFCL／TAU只对真实工具／多轮协作提高辅助权重，coding和闭卷法律／知识分不主导本次语义选型。

[ExtractBench固定作者CSV](https://github.com/run-llama/ExtractBench/blob/c972087fe7a5132132f7230d1bdb841d3fbf07ed/leaderboard.csv)另给3.8-27B FP8长文precision／recall **92.29／33.00**，Flash-Next **88.68／33.69**。该分母是>50页schema抽取，不是Vetting问题gold；它提供“已返回項相对准确仍可能严重不全”的证据，不能据此计算finding recall。RAG缩窄证据包可能减轻这一负担是合理推断，**目前没有同模型同任务的RAG有／无对照证明改善幅度**。

## 5. 本项目已保存Vetting证据比Drafting外推更直接

| 保存观察 | 可报告数字／事实 | 它实际证明什么 | 不能推成什么 |
| --- | --- | --- | --- |
| HOMES reference closure预算对照 | 10k旧包父条 **5/10**、新10k **6/10**、新20k **10/10**；必要(6)/(8)在20k入包；[来源报告](../vetting-reference-closure-20261003.md) | RAG后的引用扩展及完整组预算会改变证据供应，窗口缺失不能算合同原文缺失 | 10/10传输不证明条件理解或语义审查通过 |
| M3合法issue＋机械gate通过后源审否定 | 新20k请求returned issue **1**、mechanical accepted **1**；独立源审confirmed **0**、false **1**；[同报告模型结果](../vetting-reference-closure-20261003.md) | 已定位三处引用仍可能把下一附录标题误当上一附表页脚；证据角色／章节scope与LLM误读可同时失败 | 不能称整库precision=0%或recall已知；仅一个已审false候选，没有完整正例分母 |
| 修正边界后的M3 thinking受控对照 | adaptive completion **16,384**、reasoning **16,383**、无正式content；disabled同messages返回合法 **[]**；[边界报告](../vetting-appendix-boundary-20261003.md) | 输出预算耗尽与合法空回答是不同终态，思考更多不能自动提高审查质量 | disabled空数组没有正例召回可测，不能证明它整体优于thinking或默认禁用thinking |
| 官方两源已完成任务仍partial | **19/19**流水线进度、**17**semantic topics、finding **0**；source requests **119/119 pending**，SCC提交22/22、GCC24/50；[只读终态报告](../testing/oer-official-two-source-vetting-completed-partial-20261010.md) | completed只表明终态；实际补充包因`no_bound_token_observer`未dispatch、覆盖有限 | 0finding不证明无问题；119不是模型请求工具119次，也不是已证token耗尽；没有语义recall分母 |

这些是已保存实际记录，本轮没有复跑。M3的Drafting58/58＋21/21及37REVIEW语义复核支持Drafting参考；**不覆盖上述Vetting误报、预算和partial事实**。当前Qwen3.8-27B、Flash-Next、122B和Super均没有同一充分证据包的新Vetting实际成绩，不能由M3或Bonsai的另一任务成绩补空。

### 5.1 HOMES false issue的准确运行与源核位置

[保存报告](../vetting-reference-closure-20261003.md)登记实验ID为`minimax-m3-homes-reference-closure-20k-20261002T1830Z`，实际reservation时间`2026-10-02T18:37:21Z`，不能把实验名时间当派发时间。报告记录唯一实际POST直连MiniMax-M3，temperature0.2、max_tokens16,384、thinking adaptive、reasoning_split=true、n=1、stream=false、串行1／retry0；HTTP200／finish_reason=stop，158.656秒，provider prompt10,625／completion10,907／total21,532 tokens。这个模型名称与当时路由来自保存报告，**没有远端weights hash；本轮未重读原provider envelope或确认当前live路由**。

唯一issue认为HOMES附表“页脚”IV与标题APPEND3／正文III冲突。gate实际是对该保存回答的机械回放：1个decoded／accepted／candidateEligible，3处`VettingCorpus.evidence`定位及1次`hasComparisonBasis`；gate HTTP=0、没有新模型调用，也未执行完整SemanticReview／应用审查。

独立原生DOCX源核指出：HOMES表在**body2326**，该section在body2328结束，`rId20/header10.xml`为`APPENDIX PRE.B6/III`；**body2329**是下一附录独立Subtitle，body2330为`PRE.B6.APPEND4.9`，body2332开始冲洗表，所属`rId21/header11.xml`为`APPENDIX PRE.B6/IV`。真正`footer1.xml`是`20250101PRE.B/8`。所以模型把下一附录标题误当上一表页脚；旧corpus又将该Subtitle继承为上一clause owner，两者共同造成错误比较。源审结论是该唯一候选**confirmedIssues=0、falseIssues=1、qualityAccepted=false**，不是“原合同缺陷已确认”。源审receipt历史SHA为`5c3efcb84315bae9e0eb6efa3fca01d147a25289fc5d88b8103b8c9efda36abf`；本轮依据保存报告转述，没有重算该旧工件SHA，也没有Word物理页渲染。

### 5.2 17topics／119pending的身份和分母

准确job是`97ca407a-1fa3-48b0-a1d2-bc884e7574ab`、project是`official-q16-source-90602e8f-85b8-4e1a-ad61-2625c654115e`。本轮已直接读回保存的`model_identity_json.json`：profile=`minimax-cn`、provider=`openai`、model=`MiniMax-M3`、`identityScope=configured_profile`，configuration SHA=`eeefbf7cdffe77dcf194698a9654ac9ceeaebb4a5132bcc0bcd029cc43c68688`。`openai`是兼容client provider标签，**不是OpenAI远端模型证明**；配置身份也不提供远端checkpoint hash。

本轮直接读回的`completed-business-summary.json`显示19/19是流水线进度；17是semantic topic entries／global statuses `completed_empty`，returned／issue／consistent／insufficientContext assessments均0，17个aggregate全部`partial`、semanticScopeVerified全部false。该ledger保存实际运行终态与调用观察，但本轮没有逐POST provider envelope／usage的完整核对，**17不能作为17个正确审查样本或独立可评分模型回答**。119是planner observed source requests且全部pending，extraPacketCount=0、contextExpanded=false，不是119次实际provider请求。

46唯一submitted chunks及SCC22/22、GCC24/50是这次局部输入的传输／coverage分母，不是全两合同的缺陷gold分母；保存`legalSemanticScore=not_run`、`fullContractCompleteness=unknown`。因此目前没有由该run产生可报告的Vetting模型accuracy／precision／recall。对应两份JSON位于根`.local/state/oer-official-q16-first-run-20261010/vetting-original-h2-readonly-20261010-113733-700/readonly-query/`；详细身份、零finding和预算原因仍由[只读终态报告](../testing/oer-official-two-source-vetting-completed-partial-20261010.md)绑定。

## 6. 现在可以缩小选择，仍未完成的是什么

纸面筛选可以先以3.8-27B有源推理为本地基线候选、Flash-Next的专业任务增益为较大挑战者、122B／Super作长窗对照；**这是研究排序，不是已采纳、部署或Vetting通过顺序**。任何部署方案都还须满足至少262,144总tokens及优选524,288，RAG初筛不取消这个门；实际包通常更短也不能把完整窗口需求抹去。若RAG后完整例外和两侧证据只有几万tokens，应优先观察判断／引文／误报残差，不能将所有失败都归于最大窗口不足。

当前可确认的缺口并非泛泛“将来测一下”：①没有四候选同源的责任／条件／真正冲突标签成绩；②没有RAG证据供应充分时的finding precision、recall和整份完整正确；③没有双侧引文支持、scope及建议与问题一致的分母；④没有缺证包与足证包成对的unknown／absence校准；⑤没有固定FP8／GPTQ／offload／reasoning配方后的256K／512K及完整输出证据。已有论文能够说明这些维度应分开，已有实际失败能够定位优先风险，但它们不能填成未执行模型的成绩。

本轮只新增本文。没有修改任何已确认业务规则、模型路由、冻结识别harness、输入预算、评测代码或运行状态。
