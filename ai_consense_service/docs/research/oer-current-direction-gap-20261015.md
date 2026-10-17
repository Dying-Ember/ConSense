# 当前解析方向与接线缺口：以长文档为主约束

日期：2026-10-15。状态：**只读研究／建议，未实施、未采用**。源码基点为 main `10c3eaf3057a3a45cbf8747468cd48ddf78287aa` 加本轮实际工作区修改；本文不把该提交号当作全部已改文件的冻结身份。只新建本文，没有模型、训练、服务、数据库、索引或原件操作。

本稿检查本仓库主入口、既有实验实现和实际来源残差。外部方案的经验研究由并行根任务负责。PRE 仅作为长正文、复杂表格、页眉页脚和 OOXML 对象密集的压力形态，不恢复已排除的具体资料执行；SL 不使用固定物理页号作为新来源的路由规则。图纸只识别身份后排除；不涉及检索、重排或下游业务效果。

**判断：原生优先、保留来源坐标和原始模型证据的底座应保留；主要方向偏差是把“重跑完整模型”当成“补齐字段语义及文档结构”的通用办法。** 当前先要补的是长文档任务选择与复用、普通解析入口的结构／表单投影，以及从局部观察到完整来源组的接线。七页复杂表单不能决定几千页技术正文的默认成本。

## 优先处理的三个问题

| 优先级／问题类别 | 当前证据与归因 | 保留／修正／放弃建议 |
| --- | --- | --- |
| 1．长文档任务路由与重复计算 | Java 已先走原生并判断视觉缺口；但进入完整视觉入口后固定串行运行 Paddle、MinerU，模型选择发生在两次运行之后。CAS 保存了原始资产，不等于已建立跨运行任务命中和断点续跑。分批传输解决了请求物化问题，Java 仍收集完整 `nativeEvidence` 和 blocks，不能据此宣称全链内存已恒定。 | **保留**原生优先和已有 64 页／32 MiB／8 MiB 传输；**修正**为按页／区域能力缺口发任务、按精确来源与配方复用结果；**放弃**全部技术页默认双 VLM，以及把扩大容量当作长文档算法。 |
| 2．文字／布局到字段、状态和关系的能力断档 | 七页两路均有原始文字遗漏；APL 的 19 个印刷编号两路均未输出。即使文字正确，主 `OerReadingPlan` 仍主要给值、owner 和关系记 unknown。R142／R157／R159 的部分结构成果在独立侧车，不能算主入口已接入。 | **保留**官方 parser、原始输出与部分 typed 契约；**修正**为复用现有源中立结构／写区算法，在正常 `DocumentParser` 的同一个结果模型中输出部分字段证据，并把状态和值分开；**放弃**整页自由 JSON 一次生成所有字段，以及由无 OCR、白像素或印刷引导线推导未填。 |
| 3．章节与跨页完整结构没有统一进入有效消费 | 当前相邻 OR／Notes 候选明确未赋语义；原生表格候选和完整视觉替代组保留，但不自动成为有效文字。COM11 主格已保持历史成绩，其 12 外部成员／87 关系／5 完整来源组未被本轮重证；GCC 169 行文字保持也不等于 66 定义框已恢复。 | **保留**完整表组／来源位置／不拼单格的约束；**修正**为维护章节路径、父条尾段、跨页续接和表外限定语的来源图，再作有证据的完整组采用；**放弃**按最近标题或相邻块直接授 owner／scope，以及为追求字数把冲突原生和视觉文本相加。 |

这些是不同的首个丢失层。模型原始输出缺字，不能靠 typed 投影补造；主入口没有调用已有结构算法，也不能归因于 OCR 权重不足。来源的原文结构与字段功能属于本稿范围，法律适用和 Vetting／Advisory 推理另行处理。

## 当前主链实际已经具备什么

| 层次 | 当前主入口／可复用实现 | 实际限制 |
| --- | --- | --- |
| 身份与版本 | `VettingService` 先 `observe`、捕获身份，再决定详细解析；`OerSourceRouting` 将文件名当候选，支持实际标题／项目号及外置精确 SHA 注册。 | 这是身份判断，不是新模板的字段理解。未知 PDF 可停在 `IDENTITY_PENDING`；当前首轮 PDF 观察主要是第一页，不能声称任意新来源自动识别。 |
| PDF 原生 | PDFBox 的页框、glyph、Unicode、字体、渲染模式、alpha、原生表单 `/V` 与既有 `/AP`、图像／绘制范围被保留；正文原生提取先于 OCR。 | `operator_visible_unverified` 不证明最终像素可见；遮挡、对比度、精确 clipping、有效读序仍有未知。存在文字层也不证明隐藏 OCR 对齐、文字及顺序正确。 |
| DOCX 原生 | POI／OOXML stories、物理 cell／merge、编号／修订、原生对象及公式树已存在。当前 20 份同 SHA Word 的已核来源覆盖保持；项目 SCC 三公式原生树恢复。 | 不能把所有 OOXML 对象称已渲染。R129 的 42 typed 角色及 684 producer nodeID／位置未自动进入本轮正常输出。大 Word 同样不能整本送进 VLM。 |
| 轻量 OCR | 现有 RapidOCR／ORT CPU 承担 identity；配方含权重／配置身份。 | 当前 local 模式不允许把 identity 结果冒充详细完整结果。启用 complete 配置后，非 identity 请求直接进入双 parser，没有现成的“按缺口选择轻量详细 OCR”策略。 |
| 完整视觉 | 官方 Paddle／MinerU、实际 sourceContext、坐标变换、CAS 原始输出、Paddle token／EOS／数组和进程释放证据均可复用。 | `complete_ocr` 固定双跑；无唯一已准入特征规则时，普通 regions 仍取 Paddle。两路结果保存不等于自动择优，更不等于字段状态与文档关系已输出。 |
| 原生表格 | 已有 pdfplumber、Camelot Network／Stream 的独立方法、页框、glyph 关联、完整原件身份和分批传输。最新 COM11 全 55 页已完成。 | 各方法质量不同，不能跨方法拼最好单格；候选自报 table 存在不能授完整条件组正确。GCT／GCC 的 Camelot 权限错误仍保留。 |
| 语义／跨页结构 | `readingContext`、`NormalSourceCandidateGraph`、canonical unit／part／relation 和完整采用契约已有承载位置；实验算法也已有。 | 主入口的 form 状态／owner 与 OR／Notes 邻接候选多数 unknown；canonical 校验器验证来源和显式采用，不会替生产者自动发现缺失关系。 |

主链源码依据：

- [VettingService.java:193](D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/service/vetting/VettingService.java:193)：初步观察、身份准入、详细解析及正常候选图捕获；[OerSourceRouting.java:56](D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/document/OerSourceRouting.java:56) 与 [OerSourceRouting.java:105](D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/document/OerSourceRouting.java:105)：内容／精确来源与文件名候选分别处理。
- [DocumentParser.java:246](D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/document/DocumentParser.java:246)：完整页库存、原生提取及内存列表；[DocumentParser.java:300](D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/document/DocumentParser.java:300) 与 [DocumentParser.java:543](D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/document/DocumentParser.java:543)：原生文本／图像／绘制缺口与表单布局的 OCR 选择；[PdfNativeSupport.java:96](D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/document/PdfNativeSupport.java:96)：原生 glyph 及可见性声明。
- [PaddleOcrClient.java:80](D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/ocr/PaddleOcrClient.java:80)：identity／parse 接口及 local identity 不复用规则；[server.py:430](D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/server.py:430)：complete／RapidOCR 分流；[complete_ocr.py:407](D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/complete_ocr.py:407)：固定顺序双跑；[complete_ocr.py:500](D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/complete_ocr.py:500)：普通区域的实际主通道；[whole_parser_groups.py:36](D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/whole_parser_groups.py:36)：运行后完整候选选择。
- [DocumentParser.java:458](D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/document/DocumentParser.java:458)：原生 table 候选保留；[DocumentParser.java:612](D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/document/DocumentParser.java:612)：原生／视觉冲突处理；[DocumentBlock.java:66](D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/document/DocumentBlock.java:66)：候选不进入 effective text；[OerReadingPlan.java:55](D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/document/OerReadingPlan.java:55) 与 [OerReadingPlan.java:90](D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/document/OerReadingPlan.java:90)：状态、owner 与关系候选的实际行为。
- [NormalSourceCandidateGraph.java:157](D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/service/vetting/NormalSourceCandidateGraph.java:157)：关系候选仍未来源赋义；[native_pdf_table_projection.py:28](D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/native_pdf_table_projection.py:28) 和 [canonical_projection.py:263](D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/canonical_projection.py:263)：显式来源采用／完整组的现有 seam，不能当自动关系发现器。

## 长文档应怎样走

无需每页双模型。当前 Java 已经有原生优先，后续应沿这条主路补细分任务，而不是让七页表单的完整视觉验证成为全书默认路径：

1. **源／版本先绑定，CPU 建完整库存。** 保原始字节身份、物理页框、原生段／glyph、目录与页内编号候选、图像／控件、文字层与可见性风险。标题和页码均为当次观察；章节路径基于实际目录、标题／编号与连续正文证据，不能套用 SL 固定页号。身份不足时保 pending；不能因文件名像 PRE／SL 就排除或授角色。
2. **正文使用原生结果，风险处升级。** 可读的原生正文不进视觉；扫描正文可考虑复用轻量 OCR 作为显式详细文字任务，布局密集区／表格／公式／表单再进入相应既有 parser。该轻量详细路线尚未接入、质量未验，不能将 identity 结果直接改名当完成。隐藏 OCR、图文错位、重复文字和读序风险应触发局部像素核验或页面上下文核验，而不是仅以字数过阈值放行。
3. **表格与跨页单元保完整。** 先用原生表格方法；保表头、span、续页、表外 note／例外及父章节。必要视觉读取须包含能判定归属的完整区域／相邻页上下文。第二视觉模型用于明确的能力缺口、冲突或抽样复核；在建立来源证据前，不指定一个全局赢家，也不把单路记录伪装成满足现有双路完整契约。
4. **章节／来源组组装独立于识字。** 复用实际编号、页内读序、重复页眉页脚、explicit continued／OR／Notes 与几何，形成有来源端点的父子／延续候选。作用范围没有充分证据就 unknown；不做最近 owner 自动授义。技术正文不要求伪造表单字段，表单状态也不从段落切块推导。

本轮七页实际总耗时 943.156 秒，含 2 次 identity、7 次详细双视觉请求及生命周期开销；均摊约 134.74 秒／页。若机械地把这套形态套到 3,174 页，算术为 `943.156 / 7 × 3174 / 3600 ≈ 118.79 小时`。这是说明逐页双跑线性成本的**假设情景，不是 SL 实测吞吐或工期预测**；页面形态、加载复用和目标硬件均不同。实际证据与分母见[七页运行记录第51行](D:/R&D/ConSense/ai_consense_service/docs/testing/oer-recognition-repair-20261015.md:51)和[库存守恒与3174页分母](D:/R&D/ConSense/ai_consense_service/docs/testing/oer-all-document-reparse-comparison-20261015.md:14)。

### 已修复的容量与尚缺的复用

现有原生传输按最多 64 页、32 MiB context 写临时文件，过大批次二分；响应最多 8 MiB；单页过大则明确失败，失败后保留未执行账。源码：[PaddleOcrClient.java:119](D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/ocr/PaddleOcrClient.java:119)、[PaddleOcrClient.java:145](D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/ocr/PaddleOcrClient.java:145)。这是应复用的长文档工程成果。视觉 512 MiB 数组和 1 GiB 响应／投影容量是另一层按需边界，不能混成原生批次预算，也不能证明整本 Java 对象树已流式化。

最小缓存／断点方案应建立在现有 ArtifactStore 上，新增“任务完成记录”，不另存一份完整原件／模型输出。缓存键至少绑定原件 SHA、物理页／区域及输入资产 SHA、实际裁切／旋转／DPI／坐标变换、任务种类、parser／模型／依赖配方和投影版本；章节组装另绑定完整成员集合与结构规则版本。命中前验证 CAS、终态及完整必需资产；失败和未执行可见，不能命中为空白或完成。配方或输入变了只失效相关任务，不能静默沿用旧正确。

当前 [ArtifactStore.put_file](D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/artifact_store.py:63) 和 [ArtifactStore.verify](D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/artifact_store.py:183) 已提供内容存储／校验；[complete_ocr.py:407](D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/complete_ocr.py:407) 每次进入仍执行模型循环，本稿未发现该入口有跨运行结果命中。Java 一次解析内的 identity 缓存也不是上述持久任务缓存。原始 glyph／raw 留在证据层，派生正文与章节组引用它们，不把整本 glyph／CAS metadata 重复序列化到每个模型任务。

## R142／R159 应复用到哪里

R142 已有打印行／声明语法与多分支来源审查，完整 whole-OCR 分支曾达到 AoA 结构 18/38、owner 25/55、关系 2/11；正常分支仅 7/38、9/55、2/11。R159 则将已有写区与原始标签投影为部分 typed 对象：type 仅 TITLE 1/57，定位 31/74，状态和值各 1/74，关系 2/11，完整字段仍 57 unknown。它们是可借用的局部算法及契约，**不是一个已验证完整表单解析器**。

R159 的实际 [projection.py:67](D:/R&D/ConSense/.local/state/oer-quality-persistent-20261013/semantic-field-alignment-01/typed-field-projection-prepare-01/projection.py:67) 读取旧分支、写区／label、原始 grammar，并在独立结果中增加 `typedFieldProjection`；owner 和关系仍保留竞争候选。独立[source-summary.json](D:/R&D/ConSense/.local/state/oer-quality-persistent-20261013/semantic-field-alignment-01/typed-field-projection-independent-review-01/source-summary.json) 明确 `normalFinalParserAccepted=false`、`candidateAdopted=false`。当前主 `DocumentParser` 只调用 `readingPlan.annotate`，不调用这个投影器，见[DocumentParser.java:225](D:/R&D/ConSense/ai_consense_service/src/main/java/com/consense/document/DocumentParser.java:225)。[form_semantics.py:38](D:/R&D/ConSense/ai_consense_service/tools/oer_training/form_semantics.py:38) 是字段／写区／状态／引用合法性契约，现有调用来自训练 dataset／evaluate；契约合法不等于来源正确，也不是生产提取入口。

最小可交付的下一步应在现有 `ParsedDocument/DocumentBlock` 的 reading／结构 seam 接入一个源绑定的部分投影模块，内部复用历史已验证算法，输出真实 `fields/writing/groups/relations` 证据与各自 unknown，而不让调用方组装实验脚本或读取参考答案。首版不要求有新状态分类器，不推断缺失写区或法律角色；先证明已具备的局部能力能从**新解析观察**沿普通入口到达结果。不能直接把 R142／R159 保存输出挂到新文档上授分，不能把本次 57 个参考字段写成生产模板。

状态另有不可绕过的缺口：扫描表单的“未填”须有完整写区及实际内容观察，OCR 没读到字、编号／引导线或白背景都不能单独作证；印刷模板文字与用户填写内容也须分开。现有原生 AcroForm 保留 `/V` 和 `/AP` 的做法继续使用；扫描件不因此具备同等能力。未知值不强归空白；此处不建议新训练或整页 JSON 提示试探。

## 修改提示词也是一条路线，但要放到正确的任务接缝

**可以通过提示词改善任务理解和有限结构输出；历史自由字段协议失败不能证明所有提示都无效。** 先分清三种“提示词”，否则会把前端文字修改、官方 OCR 的 task token 和原文结构提取混为一谈。

| 提示所在层 | 已只读确认的当前机制 | 可改接缝与适用范围 |
| --- | --- | --- |
| 官方识别任务 | 已装 PaddleX `pipeline.py` 按 layout label 使用 `OCR:`、`Table Recognition:`、`Formula Recognition:` 等短任务；`prompt_label` 在关闭 layout detection 时选择有限任务。已装 MinerU `mineru_client.py` 也有固定 `DEFAULT_PROMPTS`，其底层构造函数支持 `prompts`／`system_prompt`。 | 任务选择可能影响文字／表格识别，但不是给现有 parser 任意长指令就有字段语义。Paddle 本地 wrapper 当前固定 layout=true，只把 raw 送 `runtime.recognize`；MinerU 当前 job 请求只发 files／ocr_mode／output_formats。现有应用 HTTP 没有任意 prompt 透传；库构造参数可改，不等于当前正常入口已支持。 |
| 原文结构提取 | 主入口尚无一个按章节／完整表组、只基于已捕获证据产生 typed 结构的已验提示接缝；`form_semantics` 则是另一项训练／评价任务。 | 这是更值得准备的最小对照：给完整来源文字、occurrence IDs、实际章节／表组上下文，要求只选择成员、角色及有证据的关系。程序绑定原文、检查引用与完整性；模型不重抄整章、不改写引文、不决定法律适用。 |
| 局部属性／状态 | R153 完整七页后测 0/7 合法协议，R154 的61候选双图属性实验0/61合法协议，当前来源无可评分新语义。 | 这些失败反对继续对同 OCR 模型盲扫整页自由 JSON／复杂属性指令；不能据此否定具备相应能力的既定模型对更小中间结构任务的提示。状态任务仍须真实写区和图像上下文，文字提示不能补充没有观察到的视觉事实。 |

当前应用源码依据：[paddle_native_server.py:107](D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/paddle_native_server.py:107)、[paddle_native_server.py:159](D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/paddle_native_server.py:159)、[complete_ocr.py:417](D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/complete_ocr.py:417)、[complete_ocr.py:452](D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/complete_ocr.py:452)；[mineru_native_server.py:14](D:/R&D/ConSense/ai_consense_service/tools/vetting_eval/mineru_native_server.py:14) 明确保留官方 parsing／prompts／sampling。训练任务另见 [dataset.py:18](D:/R&D/ConSense/ai_consense_service/tools/oer_training/dataset.py:18)，不能将其 `form` prompt 当生产 parser 已使用。

已安装官方源码只读核查位置：

- `/home/ocr/.local/share/consense-oer/paddle-native-20261015-02/lib/python3.12/site-packages/paddlex/inference/pipelines/paddleocr_vl/pipeline.py`：308／313／328 行为识别任务提示，855行为无layout时的有限 `prompt_label` 分支；SHA `32966d14aad6ac1bc043737c96352a4f0b4efcbead96f9354254dd4b9d1c6675`。
- `/home/ocr/.local/share/consense-oer/mineru-full-20261010-candidate03-vllm0191-pypdf6190/venv/lib/python3.12/site-packages/mineru_vl_utils/mineru_client.py`：58行 `DEFAULT_PROMPTS`，509行 `prompts`／`system_prompt` 构造参数；SHA `ba801298e8d14cde54bbb8b5da52a79f603941a4c5a4869e6b1a0e05b244c063`。这里仅阅读源码，没有导入模型或改变它们。

### 最小、可归因的提示对照设计（未执行）

先在既定模型／配方上设计**一组固定输入 A/B**，只改提示：A 为基线任务说明，B 将任务缩为“选择现有来源单元与关系”，不同时改权重、采样、分辨率、token cap、裁切或后处理。两臂固定同一章／完整表组及其边界上下文、同一 source／input／recipe，单独保存 prompt hash、实际输入和原始输出。若随后需要改裁切／补前后页，这属于第二种输入因素，不能把收益记成纯提示收益。

适合技术章节的 B 类指令可以是：

> 只依据本次提供的章节路径、原文 occurrenceId 和相邻页续段，返回本条的正文成员、表头／表外限定语及显式续接关系。只能引用已提供的 ID 和逐字跨度；每条关系给出支撑原句或版面依据。不能确定归属就返回 unknown 及缺口。不改写原文，不补未见内容，不判断项目是否采用或法律是否适用。

先让程序保全文字与几何，再让模型提出有限关系，最后校验源ID、跨度、端点和竞争解释。这与要求 OCR 模型从整页直接生成全套字段／state／value／owner JSON 是不同任务；schema 合法仍不能授来源正确。按章任务也须跨越必要的页／章边界保完整限定语，不能先按token截断，再要求模型猜缺失范围。

评价只看同源来源正确项、遗漏、误赋 owner／续接、unknown、旧正确保持及正常入口消费，另记合法率、耗时和token量；不能只看 JSON 通过率或输出更长。长正文、跨页完整表组、局部表单分别评分，随后用未参与提示选择的完整来源／版本核泛化。已有 FoT8 Text→Table 的局部路由收益可作“任务表达有用”的证据，但只是同一crop的19文字区／13组，不扩大为长文档或字段状态通过，详见[历轮账本R02及comparisons](D:/R&D/ConSense/ai_consense_service/docs/testing/oer-progress-ledger.json)。

可期待提示改善的是任务范围、结构化程度、成员／关系证据约束及适当弃权；它不能恢复输入中根本没有的19个编号、修好native mmap／传输、建立未接入的Java投影、凭空辨认未填或把已暴露样本变成新源泛化。当前模型与配方冻结边界继续有效，本稿只是设计，不修改官方任务提示、wrapper、配置或发起A/B调用。

## 本轮来源结果如何约束方向

| 来源域 | 最新实际结果 | 对架构判断的含义 |
| --- | --- | --- |
| COM11 五个主网格／672 格 | 完整 55 页／11 批已完成；Stream 672、Network 650、pdfplumber 0。同方法 gain 0、regression 0。外12／关系87／完整组5仍未重证。 | 原生强方法已可工作，不能仍写成早轮失败／全部 unknown；下一缺口是完整组结构及正常采用，不是再用整页 OCR 覆盖主格。 |
| GCC／GCT | GCC169成员文字保持；GCT42字面一致＋1unknown，11组未重证。 | 字面与结构分开。GCT页脚粒度未知不冒称识别错误；不能把文字保持当定义／章节／条件组已恢复。 |
| AoA／APL 七页 | normal 的177文字子集为124保留／45遗漏／8改字；MinerU候选137／33／7，不能拼成新成绩。19个APL编号两路均没有；字段／写区／关系完整域均缺实际语义声明。 | 同时存在 provider 内容遗漏和主入口结构缺口；增加第二路不会自动恢复双方都漏的编号，typed 投影也不能补造缺字。应分别定位、提出相应任务。 |
| 历史训练最好 | SL训练表段 fit28/35、完整normal18/35；FWP训练后normal76/82文字、10/10框，候选均未采用。本轮没有这两个同域测量。 | 不能说新架构已经超过训练最好；更不能拿COM11的672格代替SL的35 origins。 |

最新原生来源依据是 [native-evaluation-independent-01/comparison-report.json](D:/R&D/ConSense/.local/oer-recognition-repair-20261015/native-evaluation-independent-01/comparison-report.json) 和 [same-method-old-correct-comparison.json](D:/R&D/ConSense/.local/oer-recognition-repair-20261015/native-evaluation-independent-01/same-method-old-correct-comparison.json)，不能回退使用早轮 failed 对比。七页依据是[来源评分与历史对照](D:/R&D/ConSense/ai_consense_service/docs/testing/oer-recognition-repair-20261015.md:77)及其 sealed 收据；R142／R159 的独立身份和采用限制见该文件[第115行](D:/R&D/ConSense/ai_consense_service/docs/testing/oer-recognition-repair-20261015.md:115)。

## 最小演进及验收

先把任务计划／来源库存做成可持久复用的记录，复用现有原生批次和 CAS，确保长文档失败只留下准确缺口、不迫使整源重跑；同时保留当前完整双模型入口作为明确的双路审查任务。随后仅在一个结构 seam 接入历史局部算法，使新产物沿正常入口输出部分字段／章节／关系，不建第二套 parser。状态能力不足时继续 unknown，不为交付先造分类器或硬编码答案。

新来源只能以原生格式、当次标题／编号／布局与控件证据进入通用路线，遇到未覆盖结构保 unknown。需要用未参与规则选择的完整文档／版本证明这一点；本次七页是已暴露开发源，运行成功不证明泛化。章节分册也须绑定其母源 SHA 与实际页映射，不能把历史 SL 页范围推广到新版。

未来实施验收须同时检查：既有同域旧正确项保留、遗漏与错误不过滤、完整章节／跨页表组及字段分母不缩；任务命中／失效和失败续跑可重算；CPU／GPU次数、耗时、峰值内存、CAS／传输体积分别记录；单路和双路契约诚实标注；新来源保持独立。七页表单的57／74／11、COM11的672／12／87／5和长正文结构各自评分，不合成一个“总体准确率”。

本稿没有开展新原件效果测试、隐藏 OCR 对齐实验、缓存性能实验或目标超算验收。建议尚未采纳，是否降低视觉调用量和补回局部结构，必须由后续正常入口、同源回归及独立新来源结果证明。
