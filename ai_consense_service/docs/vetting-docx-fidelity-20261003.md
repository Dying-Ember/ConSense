# DOCX 保真修复与两来源 vetting 复测

记录日期：2026-10-03（Asia/Shanghai）。实际执行时间以各 receipt 的 UTC 为准；目录名不是计时依据。

## 当前结论

原 Word 中的自动编号 `(e)` 和 HOMES 表格 22 个勾选已恢复到解析、分块、检索和实际模型输入。7 个源码／测试文件已接回后端仓库；针对测试 35 项、完整打包测试 246 项全部通过。原始文件字节保持相同，新的解析结果、派生 sourceHash、chunk 和索引使用新身份。

实际重新解析 SCC、PRE 两份文件，新建 CPU 索引并执行两个主题检索，再直连 MiniMax-M3 两次。付款主题得到合法空数组；HOMES 响应的可读文字正确识别 Drawings 为双列勾选，但 `suggestion` 字段少冒号，生产解码器拒绝整条响应。语义正确与结构有效分别评价，不能把这三段可读文字算作有效审查记录。

完整质量尚未验收。HOMES 的必要引用 `SCC 22.304(6)` 在原件存在，却没有进入本次上下文；查询关键词 DVD／addendum 也不能独立建立合同义务。剩余问题涉及条件召回、缺失推断、来源范围与输出协议，不能仅归因模型大小。

随后只针对同一 HOMES 输入新增一次格式／引文约束提示对照，生产 gateway 成功解码 2 条记录，未修 JSON。单次格式改善后，仍出现引号改写、PRE 条文误归 SCC 的错误，以及缺少来源义务支撑的编辑建议；机械门禁拒绝 1 条、接受 1 条信息不足记录、candidateEligible0，语义质量仍未通过。

## 修复与软件验证

| 项目 | 最终行为 | 实际验证 |
| --- | --- | --- |
| 自动编号 | 解析直接／样式／默认编号及继承、起始值、覆盖和受支持格式；记录定义、计数、标签与来源位置 | SCC `body/908/paragraph` 的 lowerLetter/start5 恢复为 `(e)`；17 项 resolver 测试 |
| Word 符号 | 解码经确认的 Wingdings 勾选；保留原 font、char 和删除／划线状态，未知符号明确标记 | PRE `body/2326` 表格 rows1–11、cells2/3 的 22 个 F0FC 恢复为 `✓`；10 项 decoder 测试 |
| 表格与故事范围 | 每个单元格只收集一次，行文本由单元格聚合，避免自动编号计数二次推进；对独立 story 保持计数边界 | 8 项 DOCX 结构集成测试，包含删除状态、兼容 JSON 与未知范围 |
| 完整构建 | 28 个测试套件、246 项通过，失败／错误／跳过均 0 | 冻结 JAR 与 212 个 production class 的字节逐项一致 |
| 接回仓库 | 7 个文件按测试版本精确复制；128 个 source entry 与最终测试树一致 | promotion manifest 记录每个文件的身份；未重启既有应用 |

本轮新增 `DocxNumberingResolver`、`WordSymbolDecoder` 及其测试，修改 `DocumentParser`、`DocumentBlock`，新增结构集成测试。`wordStructureVersion`、编号、符号和警告 provenance 保留在 block 中，旧 JSON 保持可读。仅恢复受支持且可确认的结构，未知状态不会伪装成已成功识别。

SCC 为 PARSED；PRE 为 PARTIAL：474 个编号成功，13 个超出当前字母范围的编号未知，22 个勾选成功，4 个 Symbol 字符未知，2 处 AlternateContent 分支选择警告。注释／尾注参考顺序和继承样式划线仍有验证边界。Word 页码未知保持 null；本轮 OCR 调用为 0。

`DocumentParser` 是共享组件。没有编辑其他队员的 `DraftingService`，但 PARTIAL 状态可能影响 drafting 模板状态显示或 PARSED 计数，不能宣称其行为完全不受影响。本轮没有把新 JAR 替换到旧服务，也没有覆盖旧 DB、解析缓存或索引。

## 实际输入与新材料身份

| 原来源 | 原始字节 SHA256 | 新解析与分块 |
| --- | --- | --- |
| 80：10 - SCC.docx | `8ba80d183f6adf7a11eeb08f75e32c2bffdc3960f969202fcbed357e477f7af3` | 1.5071453 s，793 blocks，228 chunks，PARSED |
| 95：25 - Specification - PRE (base tender).docx | `a4e61064c33ba2b67c5192bfa7756c1b792dbb74ffda32aea84f2b96509dc6ea` | 3.5754361 s，10,531 blocks，1,631 chunks，PARTIAL |

新 projectId 为 `vetting-docx-fidelity-two-source-20261002T1720Z`，只含两份 tender 来源，不包含 standard、project_fact 或 package_manifest。原始字节 SHA 与由文本／结构生成的 sourceHash 是不同身份。新 sourceHash 分别为 `67225d2368d8a7d530a8b73ba3ed9c6b36198a478af4f09f347e38de2bbbf418`、`3c37508578770d18ff16f34f65d919c26438d35a70e13f6c678464873ca8fd50`。

独立审计核对全部 1,859 chunks、11,342 source parts 的内容和 UTF-16 偏移。实际解析与上下文生成使用的 37 个 production class 与冻结 JAR 一致。审计确认这两个已知源丢失被修复，没有建立完整语料解析准确率。

## 向量、候选、rerank 与上下文探针

真实 CPU BGE-M3、BM25／dense RRF、reranker 和独立 Qdrant 本地 state 完成 1 次新索引、2 次检索。参数为 float32、embedding／reranker maxTokens512、batch4、torch threads4／interop1、topK10、candidates50、并发1、重试0；使用既有离线模型权重。

1,859×1,024 向量有限值与单位范数通过独立核对，15 组 upsert 的顺序一致。新索引 signature 为 `fb1cc9a9e654cab93f9676f656ce81e97f41697f52ffb39784408ee6f53187cb`，cached=false。索引内计时 953.2694 s，调用边界计时 953.9897 s；两查询分别 36.7748、34.2033 s，整个进程 1,035.3082 s。这些口径分别保留，不相加冒充另一层计时。没有本轮 CPU／GPU 峰值观测，H800 耗时未知。

| 目标片段 | dense 名次 | BM25 候选名次 | RRF 名次 | rerank 名次 | 最终提交 |
| --- | --- | --- | --- | --- | --- |
| SCC `(e)` | 12 | 38 | 15 | 16，未进 top10 | 现有 ContextBuilder 的邻接扩展自然带入；12 chunks／3,306 chars |
| HOMES 22 个勾选 | 1 | 14 | 2 | 1 | 9 chunks／9,671 chars，完整保留勾选与图纸备注 |

两个目标的 embedding／rerank 输入均未截断：SCC 301／317 tokens，HOMES 448／463 tokens。这只排除了这两个目标的 token 截断，并不能证明其他条款或条件全部召回。上下文预算 10,000 chars；HOMES explicit-reference 扩展有部分选择与一次预算丢弃，qualifierCompleteness 保持 unknown。

探针包含 1,084 个 artifact 身份、1,067 个事件；独立审计核对 RRF、排序、selection 与实际 user JSON。进程退出后 Qdrant destructor 打印 `sys.meta_path is None`，原日志保留；索引与检索此前均完成，exit0。该退出清理信息未被归作模型失败。

## MiniMax-M3 生成与生产门禁

大陆直连接口为 `https://api.minimax.cn/v1/chat/completions`。使用既有受测 TLS transport，Windows DPAPI 凭据经瞬时环境变量注入，密钥没有进入模型输入或工件。两次请求均为实际生产 system/schema envelope 加实际新 user，没有额外 Codex agent system、SDK 或自动修复。参数固定 adaptive thinking、reasoning_split=true、temperature0.2、max_tokens16384、stream=false、n1、串行、retry0；180 s 是 socket timeout，不是绝对运行上限。

| 新 dispatch / 原 topic | 实际传输 | 实际生产解码 | 独立可读源审 |
| --- | --- | --- | --- |
| 1 / 1：付款 | HTTP200／base_resp0／MiniMax-M3／stop，128.516 s；prompt3,080、completion8,551 | 原响应外层 JSON fence 由既有 gateway 解开，合法 `[]`，0 records | 没有重复缺 `(e)` 的误报；空数组不代表付款主题全部义务审查通过 |
| 2 / 6：HOMES | HTTP200／base_resp0／MiniMax-M3／stop，52.203 s；prompt3,873、completion6,267 | `suggestion` 字段缺冒号，line25/column19，整条解析失败，parsedRecords=null；schema／evidence gate 未达到 | Drawings `✓/✓` 读对；SCC22.304(6) 限定为提交片段未见；DVD／addendum 缺少来源义务；notice 建议只获局部文本支持，省略号引文不能字面定位 |

两次合计 wall180.719 s、prompt6,953、completion14,818 tokens。provider 的 reasoning_tokens 一次为0、一次为5,058，保留原值，不用0推断没有思考。远程 GPU、prefill／decode 性能未知。

保存后 helper 使用实际 `AiGateway.completeStructuredJsonList` 和 `VettingOutputSchema.forChunkIds`，绑定真实 `VettingCorpus.evidence` 与 `hasComparisonBasis` 方法；本次 call1 为空数组、call2 在解码失败，因此引文检查和 comparison basis 循环均未进入。编排部分是逐项核对生产源码的机械投影，不是完整 SemanticReview、应用 run 或 DB 结果。投影聚合为 parsedRecords0、parseFailedCalls1、mechanicallyAccepted0、candidateEligible0，后两个0不能解释为质量通过。

当前生产 shape 检查未独立执行 provider JSON Schema 的所有 enum、长度、maxItems 或 additionalProperties 规则，此限制保留。新增 direct driver 的16项 mock 和 gate 的9项 synthetic 检查通过，它们只验证工具边界。

## 与上一轮的可比较范围

上一轮 M3 直接对照首五使用旧44来源整包的冻结材料，4个合法数组、1个JSON解析失败，两个缺陷候选由原 Word 反证。这次换成修复后的解析、两来源新 CPU 检索和当前 ContextBuilder，因此不能把前后差异单独归因编号修复、模型或某个参数，也不能称原19次同材料复跑。旧 outputs、输入、失败、DB 和索引保持原样。

已有证据支持：强模型也会受到输入丢失和必要条件遗漏影响；原件结构损坏属于解析缺陷；本次输出 JSON 不合法发生在模型生成／响应边界，生产解码器按现行协议拒绝正确。模型语义能力、检索覆盖、协议可靠性需要分别解决。尚无证据凭这一次语法失败证明复杂 agent framework 必要。

MiniMax 当前官方 [Chat Completions 参数](https://platform.minimax.cn/docs/api-reference/text-chat-openai) 列出 function tools；本次核对的文档未列 `response_format/json_schema`。这不证明接口绝对不支持它们，但不能据 OpenAI 兼容名称认定原生严格结构约束已经可用。格式提示工程使用新实验身份单列，不静默修复上述失败输出。

## 下一步与验收边界

1. 同一 HOMES 输入的格式提示对照已完成一次；保留其格式改善与语义失败，后续可靠性验证在独立实验中执行，不据单次成功切换生产默认。
2. 修必要引用召回和覆盖状态：对显式 SCC 交叉引用找实际目标，预算不足明确暴露；避免将局部片段 absence 或 query hint 转成合同义务／缺陷。
3. 在新的完整来源身份、独立评价范围下复跑44文件案例；完成同 run 的前端人审与 Word／PDF 导出，再准备 H800 离线验证。

本轮未重新 OCR、未运行 H800、未产生新的完整应用 run，旧服务与前端仍使用其原运行版本。OCR 的旧147页和固定抽查结论没有被这两个 DOCX 的结果替代。应用 Goal 工具在本轮实时返回 null；过去创建历史保留，交付目标未达到验收，不推定 active 或 complete。

## 同材料格式提示对照：可解析，但语义仍未通过

新实验 `minimax-m3-homes-format-prompt-20261002T1740Z`，baseline 为上述二来源 M3 实验的 topic6。driver 只读取 baseline request、source、schema、selection、index 身份，不读取旧回复、reasoning 或独立答案。冻结全部上游及新 driver；11 项 mock 检查通过。原 systemSource 末尾插入 445 bytes 中性协议，再接原 schema envelope。user、schema、chunks、来源、索引、模型与参数均相同。提示包同时包含裸数组／键冒号、逐字引文与信息不足规则，因此只能评价整个提示包，不能把语义变化单独归因缺冒号说明。

唯一新增 POST 于17:39:47Z发出、17:41:56Z完成，HTTP200/base_resp0/MiniMax-M3/stop，wall129.078s，prompt3,953/completion8,755 tokens。完整 response entity、headers、content 和 reasoning 保留；inner JSON 没有修复，生产 gateway 实际解析为 2 条。没有新增解析、OCR、索引或检索调用。

| 新记录 | 实际机械投影 | 独立源审判断 |
| --- | --- | --- |
| issue / language：Drawings 表格呈现歧义 | 勾选行逐字定位成功；第二引文将源中 `“**”` 改为 ASCII `"**"`，当前 evidence 策略判失败，后续第三引文因 prefix-break 未执行，整条拒绝 | 已正确看到双列勾选、drawing-list 限定并承认正文一致；所谓误解风险仅为可选编辑建议，未经 Word 呈现验证建立合同缺陷。另将 PRE.B6.820.P clause9 的绘图 hardcopy 内容误归 SCC22.304(7)(a)，原 SCC7(a) 涉及系统故障／维护 |
| insufficient_context / reference：SCC6/8 未在窗口 | 两段引文成功定位，机械接受；assessment 非 issue，所以 candidateEligible=false；comparison basis 未执行 | 全文存在目标而当前窗口未带入，局部 unknown 有依据；PRE clause9 再次被误归 SCC7。DVD/addendum 仍仅有 query hints，不能据此建立义务或要求的缺失 |

门禁投影 parsed2/parseFailed0/shapeRejected0、accepted1/rejected1/candidate0；实际引文检查3成功、1失败、1因prefix-break未查。独立遍历全部5段的源审与生产 prefix 口径分别保留。结构可用、引文可定位、条文归属准确和真实问题成立是不同检查。

为复用未修改的旧两调用 helper，adapter 生成显式 compatibility view：导入已存付款 call1（新增HTTP0）和新格式 call2（上游新增HTTP1），boundSavedAttempts2。receipt 明确旧 protocol 标签仅作兼容；adapter/gate 自身 HTTP、模型、DB、OCR、索引调用均0，非两个新请求或应用 run。adapter 6项 guard 通过。

本次完整 instruction package 的单次格式改善不能证明反复稳定，也不能证明相比其他模型的准确率。更强模型与提示约束仍会改引文、混条文来源、将 scope hints 扩成审查需要；下一修复点是必要引用闭合及问题接受规则，不由这次格式改善宣称 vetting 完成。

## 原始证明入口

- [冻结构建与测试](../../tmp/docx_fidelity/service-20261002T164116584033Z/frozen_build/manifest.json)
- [7文件接回证明](../../tmp/docx_fidelity/promotion-20261002T170236169290Z/promotion_manifest.json)
- [新解析与 corpus](../../tmp/docx_fidelity/actual-two-source-20261002T1658Z/fresh_parse/corpus_manifest.json)
- [真实 CPU 索引／检索](../../tmp/docx_fidelity/actual-two-source-20261002T1658Z/cpu_actual/result.json)
- [实际生产上下文](../../tmp/docx_fidelity/actual-two-source-20261002T1658Z/material/material_manifest.json)
- [两次 MiniMax 原始结果](../../tmp/docx_fidelity/actual-two-source-20261002T1658Z/minimax_actual/run_result.json)
- [保存响应的生产解码与机械投影](../../tmp/docx_fidelity/actual-two-source-20261002T1658Z/gate_actual/gate_result.json)
- [独立源／分块审计](../../tmp/docx_fidelity/independent-source-audit-20261002T1710Z/audit_manifest.json)
- [独立检索审计](../../tmp/docx_fidelity/independent-source-audit-20261002T1710Z/retrieval_audit_manifest.json)
- [独立模型与原件审计](../../tmp/docx_fidelity/independent-source-audit-20261002T1710Z/model_semantic_audit_manifest.json)
- [独立审计说明与执行边界](../../tmp/docx_fidelity/independent-source-audit-20261002T1710Z/README.md)
- [格式对照的冻结输入](../../tmp/docx_fidelity/format_prompt_control_prepared_20261002T1740Z/prepared_manifest.json)
- [格式对照唯一实际API结果](../../tmp/docx_fidelity/format_prompt_control_actual_20261002T1740Z/run_result.json)
- [保存内容兼容投影说明](../../tmp/docx_fidelity/format_prompt_control_gate_view_20261002T1740Z/compatibility_view_receipt.json)
- [格式对照的生产解码／机械门禁](../../tmp/docx_fidelity/format_prompt_control_gate_actual_20261002T1740Z/gate_result.json)
- [格式提示包的独立审计与原件复核](../../tmp/docx_fidelity/independent-source-audit-20261002T1710Z/README-format-prompt-control.md)
- [格式对照准备、原始响应、门禁审计索引](../../tmp/docx_fidelity/independent-source-audit-20261002T1710Z/format_prompt_control_audit_index.json)

统一实验日志 ID：`docx-fidelity-source80-source95-20261002T1658Z`、`docx-fidelity-two-source-cpu-20261002T1658Z`、`minimax-m3-docx-fidelity-two-source-20261002T1720Z`。各自输入、代码、参数和失败分别保留；总体质量指标仍未知。
