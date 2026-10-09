# Vetting 独立章节边界修复与同参数复测

本记录承接 `vetting-reference-closure-20261003.md` 的冻结结果。上一轮 MiniMax-M3 的唯一候选能定位三处引文，但原件独审证明它把下一附录的独立标题当作 HOMES 附表页脚，confirmedIssues=0、falsePositiveIssues=1。不能依据 JSON 合法、逐字引文命中或比较来源齐全，把该候选计为有效意见。

## 已证实的触发位置

PRE 原件的 body2323 是独立 Subtitle `APPENDIX PRE.B6/III`，body2324 为 HOMES 标题，body2326 为其原生表，body2328 结束该 section，其 header10.xml 对应 III。body2329 是下一附录的独立 Subtitle `APPENDIX PRE.B6/IV`，body2330 是 `PRE.B6.APPEND4.9` 冲洗标题，body2332 是下一原生表，后续 section 的 header11.xml 对应 IV。实际 footer1.xml 为 `20250101PRE.B/8`。

Parser 已保留 Subtitle 样式、文字和 body 位置。旧 `VettingCorpus` 的独立附录边界正则不接受带点/斜杠的完整标识，导致 body2329 继承前一个条款的 owner 并进入上一 chunk。此次修复针对通用章节边界，原件附录编号无需修改。

来源证明冻结在 `tmp/docx_reference_closure/independent-homes-appendix-audit-20261002T184824Z/proof_v2/audit_receipt.json`，SHA256 为 `5c3efcb84315bae9e0eb6efa3fca01d147a25289fc5d88b8103b8c9efda36abf`。该检查使用原生 DOCX XML、relationships、保存的 block/Part offsets；未通过 Word 渲染验证物理分页。

## 本轮实验约束

- 仅调整 `VettingCorpus` 及新增边界回归测试，不修改源文、Parser、Drafting 或先前冻结报告。
- 独立 APPENDIX/ANNEX/SCHEDULE 标题与 Word Title/Subtitle 终止前一条款的继承；没有明确条款标题时保持 owner unknown，不向后猜测。
- 表格里的附录标识值、正文说明句及 `TO BE SUBMITTED` 不应触发章节切分；保留这些反例的首次失败和修复后结果。
- owner metadata 形状仍为 `owner-clause-v2`，新分块算法标识为 `standalone-section-boundary-v1`，加入 chunk ID 身份。复用保存的 parse 不等于复用索引；本轮必须建立全新的本地向量索引。
- 来源限定为此前真实解析的 SCC/source80 与 PRE/source95；不新增 OCR 或解析调用。新 projectId 为 `vetting-docx-appendix-boundary-two-source-20261002T1850Z`。
- 检索保持 CPU/float32、BGE-M3、BGE-reranker-v2-m3、maxTokens=512、batchSize=4、torchThreads=4、interopThreads=1、topK=10、candidates=50、RRF=60、串行1、retry0，与前一次实际 CPU 对照相同。
- 生成对照保持 MiniMax-M3、temperature=0.2、max_tokens=16384、adaptive thinking、reasoning_split=true、stream=false、n=1、串行1、retry0，以及原 445-byte 格式指令。请求不加入预期答案、原件独审判定或旧模型回复。

## 当前验证状态

隔离完整 Maven package 已完成：268 tests、30 suites，0 failures/errors/skipped；原 257 项 testcase identities 和旧测试源码全部保留。针对 Corpus 与新增边界测试共 28 项通过。冻结的 214 个生产类与打包 JAR 逐字节一致，源码范围为原 Corpus 修改与新增 11 个边界测试。原 129 份基线源码均已核对，其中其余 128 份逐字保留；新增一份测试文件后共 130 份。两个测试通过的文件已精确接回工作区，当前运行服务未部署替换。

独立同 14 个反例在初版 v1 发现 5 个表格值/正文 `TO BE SUBMITTED` 的误切分，v2 全部通过。原始失败证明保留。首次软件 freeze helper 因旧 XML 报告目录选择错误得到 old_ids=0，随后修正证明路径并确认原 257 identities；它是证明脚本 fixture 错误，不是生产分块失败，初次 partial artifacts 同样保留。

| 来源 | 旧 chunks | 新 chunks | 差值 | 派生 sourceHash |
| --- | ---: | ---: | ---: | --- |
| source80 / SCC | 228 | 229 | +1 | 与保存解析完全相同 |
| source95 / PRE | 1,631 | 1,666 | +35 | 与保存解析完全相同 |
| 合计 | 1,859 | 1,895 | +36 | 原件和正文 bytes 不变 |

两源 11,342 个 Part occurrences 的 UTF-16 原片段、offsets、page/bbox 与全体非空 blocks coverage 均经独立逐项核对。复用的 PRE parseStatus 仍为 PARTIAL；这些核对证明保存解析到分块的传输完整性，不代表原件所有结构已经完整解析。所有新 IDs 按新版本 salt 重算，与旧 IDs 交集为 0；1,818 个分组在忽略版本身份后保持相同内容和 metadata，但仍须重新索引。36 个 Subtitle 和 SCC 的两个 Title 终止继承，未凭标题数值生成条款身份。HOMES `PRE.B6.APPEND3.P` 的显式 heading 保留，其原生 17 rows、22 ticks 和表内文字完整；body2329 的 IV 已单独归为 unknown，body2330 的 APPEND4.9 仍为后续明确条款。

新 CPU 实际执行已完成：`actual_cpu` 使用全新 state 和本地 Qdrant，固定两次原查询，没有复用旧 vectors/rankings。索引 1,895 条，cached=false，索引签名为 `03db4a13ae95e6ad8e4ffcb4b735efa167548b0fb53fae2c50fc62a5741a173e`。prepare 的 encode/predict 均为 0；execute 的 17 次 encode（15 组索引与 2 次 query）和 2 次 rerank 全部完成、失败 0。这些是 wrapper 方法边界计数，内部 forward 微调用数未知。

| 同 CPU 参数的实际观察 | 上一轮 | 新分块本轮 |
| --- | ---: | ---: |
| 索引 chunks | 1,859 | 1,895 |
| 索引 wall 秒 | 953.990 | 743.476 |
| 原主题1 query 秒 | 36.775 | 28.545 |
| 原主题6 query 秒 | 34.203 | 29.240 |
| 本轮整个工具 wall 秒 | 以旧 receipt 为准 | 810.421 |

这些时间不是干净的性能实验：新 IDs 改变索引分组和排序身份，分块内容、资源采样 instrumentation 及机器负载有差异，不能据此宣称速度优化。62 个资源采样观测的最大当前进程 RSS 为约 4.516 GiB，机器可用 RAM 最低约 10.218 GiB；不是完整真实峰值，GPU 未观察。

独审核对 1,085 events、1,102 probe artifacts 的 SHA，所有 1,895 个 index 与两个 query 向量均为 1,024 维 float32、finite 且 unit norm；本地 SQLite 在 immutable/read-only 下 points=1,895、quick_check=ok，所有落盘 payload 与 normalized corpus 相同，全部向量逐元素匹配实际编码输出。进程退出后出现 QdrantClient 析构 ImportError（Python shutdown 的 sys.meta_path 已空），属于完成后清理警告；落盘数据完整性已核实，没有把它记作 encode/index 失败，也没有重跑或验证重新启动 client。

512 token 裁剪确实存在：index 46/1,895 条、共裁剪 4,655 tokens；rerank 3/100 对、共裁剪 73 tokens。但本轮焦点 `(e)`、HOMES、SCC22.304(6)/(8) 的实际 embedding/rerank tokenizer 输入均未裁剪。HOMES 的 dense/BM25/RRF/rerank 名次为 1/14/2/1；(6) 的 BM25/RRF/rerank 为 26/46/21，(8) 为 dense20/BM2513/RRF8/rerank14。后两处未进入 top10，引用闭包把它们完整补进模型输入。此处是必要条件的覆盖瓶颈，不能归为焦点文本截断或数值向量故障；两查询的排序抽查不能代表全量检索准确率。

生产 context 实际回放完成：主题1为12 chunks/3,306 UTF-16字符；主题6为18/17,166。新 HOMES 及本次整个 selected context 的 IV marker occurrences=0，SCC22.304 的10个父条 chunks全部提交，第(6)/(8)原文保留。30个选中 chunks、102个Parts与corpus逐字段一致。14条 literal traces（2 exact clause、12 full parent）的required/submitted均完整、missing=0；`unresolvedComparisonIds` 的4个origin涉及 comparison/group hints，不是4条literal target未解。精确子条款metadata及限定条件完整性仍为unknown，不能由完整父条传输推断全文absence或语义正确。

## 同模型 adaptive 对照的实际终态

唯一 POST 使用相同 M3/.2/max_tokens16384/adaptive/reasoning_split/n1/streamfalse、同20k预算与445-byte格式包。HTTP200、provider base_resp成功、实际模型MiniMax-M3，wall133.235秒；prompt10686/completion16384/total27070，provider报告reasoning_tokens=16383，finish_reason=length，message没有content字段。只有reasoning相关字段被保存，未将其作为正式答案或填补JSON。

因此本次正式状态为failed / output budget exhausted / semanticNotAssessable / gateNotReached。它不是合法 `[]`，没有证据称JSON语法错误，也不能判定旧IV误报已消失；gateway/schema/evidence/semantic源审尚无正式content可处理。原始失败与不可重复的dispatch reservation保留。

MiniMax当前官方说明：thinking token计入生成上限，预算不足可导致length且content为空；MiniMax-M3支持thinking.type=disabled。[官方兼容接口说明](https://platform.minimax.cn/docs/api-reference/text-openai-api)。据此完成新的单参数对照：只把thinking.type从adaptive改为disabled，max_tokens、模型、温度、完整messages、user/schema、20k材料、格式指令和transport保持一致。它是独立参数实验，不是重发原prepared请求。

API v1适配曾把完整查询文件包装与内部flat request比较，30个synthetic mocks漏检；真实prepare在任何HTTP/密钥读取/输出创建前拒绝。旧v1证明保留，新v2严格绑定完整包装、ordinal和内部request，33个guards通过后实际prepare/validate及独审通过。该开发缺陷与M3的输出预算失败分别登记，均不伪装成模型质量结果。

## Thinking 单参数对照的实际结果

新请求与此前实际adaptive POST逐JSON递归比对，唯一差异为 `/thinking/type`：adaptive→disabled；完整messages字节一致，request长度均34,330bytes。每个prepared独立预约一次，重试0；没有改素材、先前失败记录或实际content。

disabled POST 返回HTTP200、provider0、MiniMax-M3、finish_reason=stop、bodyComplete，wall1.484秒；provider报告prompt10673/completion2/total10675，原content逐字为 `[]`。与adaptive相比provider报告prompt tokens相差13，但实际传入messages完全相同，该usage差异属于provider观察，不能据此称素材变化。此次不把missing content转换成数组，正式空数组来自实际响应。

保存回答经正式AiGateway实际解码一次，动态schema生成一次，parsedRecords=0、parseFailedCalls=0、shapeRejectedCalls=0；因没有记录，evidence与hasComparisonBasis调用均为0。该gate没有HTTP，不执行完整SemanticReview/application。它证明这次有合法正式答案；本次没有重现旧IV误报，但没有正例可测召回，precision/recall/accuracy、整体语义验收及重复稳定性均保持未知。关闭thinking不能自动成为最终生产参数，需要在带有已确认真问题的固定样本上继续比较。

| 实际单次HOMES比较 | 上轮旧分块adaptive | 新分块adaptive | 新分块disabled |
| --- | --- | --- | --- |
| 模型 / max_tokens | M3 / 16384 | M3 / 16384 | M3 / 16384 |
| 提交材料UTF-16字符 | 17,203 | 17,166 | 17,166（完全同新adaptive） |
| 正式content | 合法1条issue | 缺少content | 合法 `[]` |
| finish_reason | stop | length | stop |
| wall秒 | 158.656 | 133.235 | 1.484 |
| completion / reasoning tokens | 10,907 / 原provider0（同时有reasoning字段） | 16,384 / 16,383 | 2 / 未提供该明细 |
| 判定 | 原件反证唯一issue为误报 | 语义不可评，正式gate未到达 | 未重现该误报；正例召回未知 |

旧分块与新分块之间同时改变了分块身份、索引、排序和下游材料，不能当作仅prompt或仅模型差异。两个新请求之间才是单thinking参数对照。上述是单次观察，没有胜率或全量准确率结论。

## 结论与下一执行点

当前已确认并修复章节归属失真，确认pure top10会漏掉必须保留的条件，并完成引用闭包补齐；新材料与真实向量索引的来源身份、offsets、数值及持久化均通过核验。生成阶段又定位到adaptive思考共用输出预算而耗尽正式答案空间。该证据说明harness和模型运行参数均有实际瓶颈，不能把此前现象单独归因4B/7B或Bonsai本身。

下一步以固定、人工核实的正例和反例做修正后同材料的Bonsai/M3与thinking/output budget对照，记录漏报、误报、覆盖和资源成本。若纯prompt仍不能可靠验证字段、条件和来源角色，再加入有预算的检索扩展、来源核查与claim复核步骤；自研状态机、开源runtime改造及生产参数由后续对照决定。目前限定条件完整性和整体语义质量保持unknown。

本轮真实调用总计：复用parse且新chunk构建1，实际parse/OCR0，新本地向量索引1，query2，encode17/rerank2，直连API POST2（adaptive失败、disabled正式返回），保存正式回答gateway解码1。软件fixtures与file-onlyhelpers的调用数单列，未相加冒充真实推理。仓库代码已推广两文件，已有运行服务、UI、旧DB/旧索引未替换；M3对照不等于完整应用run。

## 真实结果入口

- 新CPU结果：`tmp/docx_appendix_boundary/tooling-20261002T185610Z/actual_cpu/result.json`，SHA `58a34a99563ccfacd5c91805eb12d0321f54a5db0302cb2071498ff8824db4dd`。
- 新20k材料：同tooling的 `context20000_actual/material_manifest.json`，SHA `c09100e3a9c8c6a00cae679c3df2c6ed5c90d3004a96200785c6f91f3bd4fbd9`。
- CPU向量、token截断与排序独审：`tmp/docx_appendix_boundary/independent-review-20261002T1855Z/actual-cpu-probe-audit/audit_result.json`，SHA `5a25c7b3dda25295731abb49522729d01d01421ba1b510950ab577a370801ca4`。
- 材料来源与引用独审v2：同独审目录的 `actual-context-data-audit-v2/audit_result.json`，SHA `1bc415f4eca44e1ef7e951dc4da8fcff693efd8b118c7a82e3d6c54c71e24dbd`；早期文字将4个unresolved origin误描述为literal targets的证明保留，更正不改变模型输入。
- 落盘/新context独审：`tmp/docx_appendix_boundary/independent-phase3-run-audit-20261002T193552Z/audit_receipt.json`，SHA `db177203a87626f9a74956bf9e81d89006bbde017683bac4f27237b23fca3a06`。
- adaptive实际结果：`tmp/docx_appendix_boundary/minimax20k-tooling-20261002T192118442262Z/v2/actual/run_result.json`，SHA `e106e54367d8908bd6113508014b96288ac1798e0ce517763cf92372d6038c6b`。
- adaptive缺少正式content独审：`tmp/docx_appendix_boundary/independent-review-20261002T1855Z/phase3-adaptive-output-exhaustion-audit/audit_result.json`，SHA `2d87cf9ec3ce03e5bc77738c626ef3ffcd1f77c559cc0804cd66dba6ff7a298c`。
- thinking差异证明：`tmp/docx_appendix_boundary/thinking-disabled-tooling-20261002T194554Z/actual_prepared/parameter_only_request_diff.json`，SHA `a97bf02f91b50e22d0bd39c37d0ea656d1e29e03f518f953e70769779e237f25`。
- disabled实际结果：同thinking工具的 `actual/run_result.json`，SHA `ff9b0eb6e57ac359af7844c091e2b3f9658329b77657ce56e5537e49f00470e1`。
- disabled正式解码回放：同thinking工具的 `gate_actual/gate_result.json`，SHA `d739542851ea11a1ade062ae3a8e87f330fce452eed0ab4baf7f91de03ff4219`。
- disabled公开输出/源质量独审：`tmp/docx_appendix_boundary/independent-review-20261002T1855Z/thinking-disabled-public-output-quality-audit/audit_result.json`，SHA `114c51ff46418708f93125defaea3c4ad9640fc4b4611b205f32623e65cd36ad`。
- 实际两请求thinking唯一参数独审：`tmp/docx_appendix_boundary/independent-thinking-control-audit-20261002T194701Z/thinking_only_actual_audit.json`，SHA `5122a9f727addbd0c1f73dadda6e047abcdcae3495c04af2235d73f8bf7479d0`。
- 独立总收据：`tmp/docx_appendix_boundary/independent-review-20261002T1855Z/phase3-final-independent-receipt/receipt.json`，SHA `5159b6703cd7db8eefd25a9b9127c78c8e6a463ecbb54b21466f456857f9f560`。
- Root最终对照机器记录：[evaluation.json](../../tmp/docx_appendix_boundary/final-comparison-20261002T2000Z/evaluation.json)。统一registry在保留原参数、输入、调用状态和观测前缀的前提下追加本轮CPU、adaptive及disabled的独审评价；不删除失败、不把synthetic计入实际模型。

## 软件与输入证明

- 冻结软件：`tmp/docx_appendix_boundary/service-20261002T185006505814Z/frozen_build_v2/manifest.json`，SHA `96f244d73effa447dc434e85660059a296466f50114522ae46ae0649717fc4dc`；JAR SHA `06f08cc0bc178cfe8d0ae9fb5c863d3dddb4ef3546edf7eed31ef6d4714b9364`。
- 新 corpus：同 stage 的 `saved_parse_corpus/corpus_manifest.json`，SHA `26fe77de992cd5168c0d6682d4500f5427146197bee4944876d9c24497cf7a4e`；chunks JSONL SHA `8c5bddd499963eced0ad0b53cbf264b4adbd3cb095b5255f9a2174d54b928bd3`。
- 独立 14 反例执行：`tmp/docx_appendix_boundary/independent-review-20261002T1855Z/v2-final-probe/run_receipt.json`，SHA `822f06277f0c48fa8a9ea0022bd283aa3585df6cad9569407f4aff1492c5ab93`。
- 源文、package 与全 corpus 独审：同独审目录的 `final-source-package-corpus-audit/audit_result.json`，SHA `dd9692da891fac9a6c3ec11e8dbc3e71b4a57cb6955b9ba636e8c551f0c3d917`。
- 两文件推广：`tmp/docx_appendix_boundary/promotion-20261002T191638898488Z/promotion_manifest.json`，SHA `007f8611ce412803acbe71f3be1b9d361f5664c95a55f231fe01d39c30a2bbc8`；Root 推广前 129 份来源及 POM 与基线相符，推广后全 130 份相符。
- CPU 参数计划：`tmp/docx_appendix_boundary/tooling-20261002T185610Z/prepared_actual/approved_cpu_plan.json`，SHA `f6b1b6d71289af4ff915f742dde145b00908932b78ce859782b4f268f766cf7f`；driver SHA `97f960bdddc2e3da578d857adbb0a335d602d01b8f67e01f984029fbf92bba45`，32 个纯文件 guards 全部通过。
- 软件/新 corpus 检查已在统一 registry 追加独立 completed event：`standalone-appendix-boundary-software-20261002T1850Z`。它的 scope 是软件与来源完整性，不代表实际检索、生成或语义验收完成。

本轮尚未证明 44 来源完整审查质量、OCR 全量精度、应用人审和 Word/PDF 导出、H800 部署性能。当前 Codex goal 查询为空；本文件的阶段工作记录不代表已建立新的 app goal 或完成整体验收。
