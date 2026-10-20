**2026-10-20 三模型同条件对照记录，Goal 继续。** 首轮实际结果支持继续使用 Qwen：两个 Qwen 均取得三个可读取首答，MiniMax 两次 HTTP 529、一次返回答案；同一 Specification 题上两个 Qwen 的原件理解评分高于 MiniMax。三个模块仍有结构或语义缺陷，首答对照不等于应用端到端通过。

**三轮已全部实际结束：27 POST、20 个严格 JSON 对象回答、7 次 HTTP 529，0 retry/repair/cap1。** 两个 Qwen 各 9/9 返回；MiniMax 2/9 返回，其余七格保留未评分。正式15现已安装为后端 PID44596，升级前后39完整API响应逐字节一致；新SQL15逻辑备份完成，共享H2 owner38980保持。实际Qwen应用流程另行验收。

本记录按用户“控制变量”执行。初轮为三个案例 × 三模型 × 一次首答，共 **9 POST、7 HTTP 200、2 HTTP 529、7 个严格 JSON 回答**；每格 0 自动/人工重试、0 repair、0 cap1 预检生成、0 历史对话。HTTP 529 的语义评分保持未评分，不计零分或漏报。日常真实 LLM 使用 Qwen，MiniMax 在此处只作对照。

| 初轮实际首答 | Drafting | Vetting | Advisory |
|---|---|---|---|
| Qwen3.8 27B，`qwen3.8-27b` | 200 / stop / JSON；24.937s | 200 / stop / JSON；23.094s | 200 / stop / JSON；36.656s |
| Qwen3.8 Flash-Next，`qwen3.8-flash` | 200 / stop / JSON；18.422s | 200 / stop / JSON；18.563s | 200 / stop / JSON；18.782s |
| MiniMax-M3 | 529；1.891s；未评分 | 529；1.406s；未评分 | 200 / stop / JSON；56.297s |

每个 200 响应均实际返回所请求的 exact model、一个 choice 和正常 stop。表中时间是该次完整 HTTP 调用耗时，未把不同输出长度、tokenizer 或缓存行为当成同等计算量；不是延迟分布或长期可用率。

| 原件/业务维度 | Qwen 27B | Flash-Next | MiniMax-M3 |
|---|---|---|---|
| Drafting NTT1(e) 删除指导 | 5/5 来源事实；两个完整目标引用；可保留一个独立待答问句。完整图有 Boolean parent 和无 scopeId 的 scopeMode 错误 | 5/5 来源事实；两个完整目标引用；可保留一个独立待答问句。Boolean parent 错误；25 字符 ID 超过冻结的 24 字符要求，产品有显式唯一 ID 映射兼容层 | 本格 529，没有可评分答案 |
| Vetting 已知份数冲突 | 1/1 定位、漏报 0；四个 quote/anchor 精确；99.8 保持窗口外未知。另有不受来源支持的正式 Clause 1/2/3 归属推定 | 1/1 定位、漏报 0；四个 quote/anchor 精确；99.8 保持窗口外未知；使用 Paragraph 位置，没有该项正式归属推定 | 本格 529，没有可评分答案 |
| Advisory 原件评分 /100 | 已确认 70，另 20 待核，审计范围 70–90 | 68 | 已确认 30，另 10 待核，审计范围 30–40 |

Drafting 没有把局部结果判成全无用。独立适用性问句均有完整来源指令、项目答案未知，完整图的坏关系另列。后继已用正式15真实 `DraftReviewDiscovery.discover` 回放六份Qwen原始回答：六次实际送往fake gateway的system/user逐UTF-8字节等同冻结fixture；返回图各只新增一个pending Boolean input，五次partial、一次完成问句识别，0采用。该有限离线JVM512MiB、2.922秒、exit0、自然Job0；没有真实模型/API/SQL调用，不等于HTTP持久化、原生绑定、采用或编辑验收。27B 的 Boolean `eq yes/no` 会由现有 `truth` 归一支持，不误记为类型错误；Flash 的 `decision` 需要人工决定，不当作自动 `derived`。

实际消费回放另外发现产品解码缺陷：合法可选JSON null被 `.asText()` 转成字面字符串 `"null"`，导致Flash初轮先发生 `DISCOVERY_FOREIGN_REFERENCE: null` 的branches/effects隔离，再因真实Boolean parent错误拒掉第二节点。这个nullable字段解码问题属于产品，不能全部归为模型坏引用；已进入最小修复和定向回归，旧答案和旧评分保持。无scopeId的标量scope错误在部分坏parent之后未实际到达，不能把源码发现统称为每次运行均执行了全部拒绝门。

Advisory 使用事前冻结的 A30/B20/C20/D20/E10，共每模型 13 个子项、三模型 39 个。unknown 不授分，仅保留到审计上界；范围不是统计置信区间。MiniMax 将已有四行排除名单误称缺失，扩大站界外道路/排水条件，错读 Subject to (1)(2)，并无据扩大 PSS 为全合同最高。Flash 漏掉 Subject to (1)(2) 和 wherever applicable；27B 保留前者但漏后者及 2020 Edition，中文替代规则与正确英文引句的范围仍待核。各项 JSON pointer、回答原文、原件引句、实际 alias/range 和判定理由保存在逐项评分文件。

三份 Advisory 的字段 schema 均通过，另有按现有应用要求检查出的缺陷：MiniMax 在 analysis 留 `missing_evidence` 且漏列六个正文引用；Flash 用繁体回应简体问题；27B 填写了要求留空的 `uncertainty.content`。这是保存首答的契约检查，未冒称完整运行时 `AdvisoryHarness` 或生产 ask 通过。

共同控制为每案例相同的完整 messages、来源范围、问题、schema/格式要求、temperature 0.2、n 1、stream false；Drafting/Advisory 输出上限 16,384，Vetting 4,096。同一案例三模型上限相同。全部省略 `response_format`、top_p、seed、tools；Vetting/Advisory 的 schema 已在公共 system 中，Drafting 的字段要求在公共提示词中，独立 responseSchema 只供离线评分。供应商差异仅 endpoint/auth、model 与非思考字段：Qwen `enable_thinking=false`；MiniMax `thinking.type=disabled`、`reasoning_split=true`。原始请求 bytes 直接发送，无重定向；每 POST 子进程 300 秒上限，响应上限 16 MiB，任何失败均消费该格、不重发。

三个案例的来源边界分别为：

- Drafting：真实标准 NTT 的一个完整删除指导及完整 1(e) 目标，44/208 UTF-16 单位；Specification/Drawings 只作为原文引用，没有处理图纸。
- Vetting：此前实际上传的 authored E2E DOCX，九段、497 字符；不是正式招标原件。只能据此检查已知两份/三份冲突及窗口外未知边界。
- Advisory：真实 Specification doc24，21 个完整 canonical 字面来源、88 个 Word 成员；保留真实 public alias 顺序，十份材料由真实 Java `OerModelMaterial.Request.project` 捕获，另十一份明确 unavailable，完整字面仍保留。没有补造持久化 SourceDocument 字段，也不是完整生产 ask/RAG 捕获。

官方答案、评价材料、旧模型答案及评分 oracle 均在模型输入白名单之外。原件、请求、响应和评分逐份 pin；密钥只由受控子进程从 Machine 环境读取，不写入请求文件、命令行或报告。

**重复验证：** 初轮封存后预声明的两轮独立首答已全部完成，共额外18 POST。复用同一runner、原请求bytes和原评分口径；每模型在每案例的次序轮换，三轮中各占一次首/中/末位置。每格一首答、0 retry/repair，不替换任何失败。缓存状态未冻结，第三轮Flash部分请求已有cached tokens，原始耗时不作冷请求速度排名；三个已知案例各三次仍不能当作holdout或统计稳定性验收。

| Advisory同一来源 /100 | 第01轮 | 第02轮 | 第03轮 |
|---|---:|---:|---:|
| MiniMax-M3 | 30–40 | 18–28 | HTTP529，未评分 |
| Flash-Next | 68 | 96 | 58–70 |
| Qwen27B | 70–90 | 90 | 74–80 |

上述117子项中，104项对应八份完整回答，13项对应无答案的未评分格，另有六项判断unknown；每项都有本轮回答与原件证据。Qwen27B第02轮遗漏必填basisScope，字段schema失败与来源分90分分别记录；Flash三轮均有简繁体不匹配。两个Qwen对wherever applicable、2020 Edition、GS CEW绑定和PSS替代范围的遗漏会随轮次变化。Vetting六份Qwen回答均命中已知冲突并保留99.8未知；27B三次都推定正式Clause归属，Flash未见该项越界。Drafting六份Qwen均留下一个可用待答问题，完整图仍未通过；27B两次Boolean parent/标量scope错误，Flash三次ID超24、两次sourceQuote与scope命名空间错误。三轮的重复出现和变化都保留，不能用一次高分盖过其他轮次。

**已落实与后继状态：** 正式15首轮 1,235 项中 1,219 pass、8 failure、8 error，根因为两组旧反射测试少传新增 Qwen 参数。九处补显式 null 共 45 bytes；同冻结源后继 A1,235/82 与同 target/JAR 的 B1/1 均绿，合计 **1,236/83，0 failure/error/skip**。旧红轮完整保留。独立发现的 parent 结构契约已通过一条提示词补充采入主树，旧提示词全部字符保持，3,158→3,377 字符；它尚未构建、部署或通过新模型效果验证，明确不在该正式15包内。

证据入口（所有数字均由以下逐项记录计算，不以模型自评为验收）：

- [初轮 Root plan](../../../.local/e2e-20261020/controlled-models/root-release01.json)，SHA `a4cd2670b3edf96deb33869632b9109e6b09354194df2dd06f3866418cf908b2`；[传输汇总](../../../.local/e2e-20261020/controlled-models/first-answers01/transport-summary.json)，SHA `bd8d9446169c5a94c925e81eedafe7786e3051459f4caa9af51c4824bb9c0f21`。
- [Drafting 逐项复核](../../../.local/e2e-20261020/drafting/controlled-model-comparison01/out-of-band/first-answers01/parallel-manual-review.json)，SHA `3d52837a6b4205324a9f719cd09cb7a588c21bcb785f00698093e157ab4ef6d6`。
- [Vetting 逐项复核](../../../.local/e2e-20261020/vetting/controlled-model-first-answers01-review/comparison-receipt.json)，SHA `69c09893837dfb471ab9da3a19cac0ff0c6158a5ef111aa2bfa976e388cedfba`；[生产接收链边界](../../../.local/e2e-20261020/vetting/controlled-model-first-answers01-review/production-receiving-chain-readonly.json)。该 standalone 对象与生产 schema 不兼容，不能据其 Clause 文字就声称生产已接纳该缺陷。
- [Advisory 逐项报告](../../../.local/e2e-20261020/advisory/controlled-model-comparison/advisory-specification-case01/first-answer-scoring01/parallel-scorecard.md)；[完整 JSON](../../../.local/e2e-20261020/advisory/controlled-model-comparison/advisory-specification-case01/first-answer-scoring01/parallel-scorecard.json)，SHA `c6ea7d8fdacdeb97071e2d7b73a7d14dcf4479a2d3afe620580d8404b726e305`。
- [第二轮 plan](../../../.local/e2e-20261020/controlled-models/root-release02.json)，SHA `3dfd95aff89ece92c288c118a132f9efb2cd4b32469e2a6a5f429e7057d2bc86`；[第三轮 plan](../../../.local/e2e-20261020/controlled-models/root-release03.json)，SHA `b96bc651084108cba323bcaae3b2b5d5c6bfceddb31f67637cc274fc6bf1e20d`。
- [三轮27POST传输汇总](../../../.local/e2e-20261020/controlled-models/three-round-transport-summary01.json)，SHA `1fca6f7022f90b68b6a9eff58760ba092916c28e610b2b501fad21d716966542`；[Advisory三轮逐项矩阵](../../../.local/e2e-20261020/advisory/controlled-model-comparison/advisory-specification-case01/three-round-observations01/criterion-matrix.json)。
- [Drafting真实消费回放](../../../.local/e2e-20261020/drafting/controlled-model-comparison01/out-of-band/production-consumer-replay01/consumer-summary.json)，SHA `18c5b96bc3bf1ad492d25e1e62e9d58d85773a672b3b8abdb3d866db84f2bb32`；[正式15安装与39响应保全](../../../.local/e2e-20261020/runtime/round15-installed-verification.json)，SHA `9f364ac41ea5e84d406666f9fcd1ae75013fe28149234e498608c52d2a9c1969`。
- [正式15 A+B](../../../.local/e2e-20261020/build/round15%20integration02/combined-verification.json)，SHA `e9f4bcd0a8a1af67a6850a395770bc2ae66fee5599f4ce89cf3803b5313fba04`；[主树提示词采纳](../../../.local/e2e-20261020/drafting/controlled-model-comparison01/out-of-band/first-answers01/parent-contract-main-adoption.json)。

实际三模块 Qwen 应用流程、持久化调用账本、原件业务验收、浏览器切换操作及长期稳定性分别保留自己的验收状态。旧 answer32、Vetting Mini07/Mini08 和旧全量失败不因本次局部改善而改判。
