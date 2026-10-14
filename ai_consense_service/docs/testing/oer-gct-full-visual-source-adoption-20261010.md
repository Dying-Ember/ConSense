# 完整 GCT：原页审阅后的视觉块采用

日期：2026-10-10，Asia/Shanghai。依据 [范围规则](../oer-scope-and-rules.md)、[实施交接](../HANDOFF-ocr-embedding-rerank-20261008.md) 和完整双解析记录。此轮使用已经完成的 Paddle v3／MinerU v6 实际产物，没有重新运行 OCR、渲染、LLM、训练或线上发布。

## 原页核对与采用范围

根任务已目视 Paddle 实际输入的两张完整原页图：2480×3509、300 DPI、forms 初始化，原 PDF SHA `ca3944c16890cb5ea7f8b24e2fab613c7ffd621809f7f850529901649e6f15f8`。第 1 页有项目标题、合约号、条款 1–3、三条边注及版本／合约页脚；第 2 页有条款 4–11、一条边注及版本／合约页脚。BQ／General Summary 的正文引用保留，不将计价文件加入输入范围。

采用直接使用 Paddle 的完整原始 block 文本，不从评价参考抄写、补字、拼回编号或修正拼词。条款 1 的 intro 和 (a)–(c)、条款 2 的 intro／(a)／(b)／检查文件说明、条款 3 的 intro／(a)(i–iv)／(b)(c)／三个尾段，各自合为完整主条款单元；页 2 每个主条款独立。项目／合约 header、两页版本／合约 footer 和四条边注也保留为单元。

页码 `GCT/1`、`GCT/2` 的外围破折号在 OCR 输出中遗漏，两个 block 留在完整 raw 及未采用清单中，不修补或删除原始产物。36 个实际 Paddle blocks 逐个记账：34 个采用，2 个页码 block 未采用。18 个 whole units 包含 11 条主条款、4 条边注、1 个 header 与 2 个 footer；这不是 18 个法律义务的计数。

四个 `margin_note` 关系由原页目视审阅声明，分别指向 1、2(b)、3、10。2(b) 关系同时绑定条款 2 单元内实际 (b) block，不能只把边注笼统挂到整个 2 条。原 parser 的 label／order／relationships 不改；这些关系不能追认为模型自动归属准确率。

## 两路坐标与表单文字

新 capture 从原 vendor 文件和实际输入重建，只新增原页 transform 及其新运行身份；旧 unknown run 保留。Paddle 使用实际 PNG 的 identity；MinerU 的完整原 PDF page/pt 595×842 映射至同页 Paddle 原图，矩阵为 `[2480/595,0,0,0,3509/842,0]`，约定 `x'=a*x+b*y+c; y'=d*x+e*y+f`。两路均标为 `source_reviewed_declared`，保留 `exactPixelVerified=false`。MinerU 内部完整 raster 没有封存，不冒称内部图 SHA 已确认。

依据为独立调查 `.local/state/oer-source-raster-transform-review-20261010/run-readonly-01/source-raster-seam-review.md`，SHA `80ef48ff4805e570398583a46926f0890d7b25406c0d6d861cd0684aa7bddc26`：实际执行源码与固定配方一致，完整原件 crop/media 均 595×842、rotation0／userUnit1，layout 在完整页上归一化 bbox；有限人工采用可声明该映射，不能推广为逐像素或 glyph 可见性验证。

GCT 已填字段的显示内容来自 initialized forms 的实际原图，再由 Paddle 整 block 保留；不把 AP 字面／stored V 或 OCR 文本插入 PDFBox 的 page-native glyph 通道。完整 native bundle 继续独立保留，effective visibility 和 AP-to-native 采用仍未知／不支持。

## 代码与实际回取结果

视觉 canonical projector 的 copied 候选仅扩展关系类型允许 `sidebar`／`margin_note`；unit、block、cell 端点、未知类型拒绝、重复关系与 parent 环检测保持。5 个新增方法先实际 red（2 errors、2 failures），修复后 5 新＋22 旧＝27 个唯一方法全绿。候选 `canonical_projection.py` SHA `5607a84ad5e106e9857c93e65dd9f44670cf92692525255540931d5c68536d1c`，基线 SHA `04ad81189bea16829be9f23353a81479b56a2ee945386bdd8b376fd37b724953`。这不是模型质量或 Java 业务验收。

实际产物在根目录 `.local/state/oer-gct-visual-adoption-20261010/root-source-review-01/actual-01/`。首次 `build_actual.py` 在最后资产验证处漏传 `source_sha256`，TypeError／exit1；此前 projection 和 cold registry 已生成，失败记录保留在 `first-execution-failed.json`，不把首次运行改成成功。新的 `validate_actual.py` 仅读现存输出，没有重新 capture 或覆盖产物：cold registry 全对象相等、34 adopted blocks 的文字逐字等于实际 raw、四关系端点属于相应 whole unit、18 个 source-bound PNG assets 实际回取 hash／bytes 一致。18 assets 共享两个完整原图字节对象，不重复存 18 张图。

followup receipt：`actual-01/validation-receipt.json`，15963 bytes，SHA `4b04b4aeb6fee781ca3e637bc44eb5ecef974e008bf21627ab96637e65617cf4`；状态 `followup_cold_projection_and_image_retrieval_passed`。所有原有 JSON／源码在本次回取前后不变。独立审阅进行中，候选未应用主产品。

## 未完成的边界

后续增量：actual-01 独立有限审阅已完成，receipt SHA `f99c9780d888b4a76cc53089aef72671a284107d03ae0f81ab74cbf10ebf1a02`，74 个新旧 capture block 的 text／label／provider bbox 不变、四条实际 margin 端点和18资产经核验。视觉 projector 已与另外三处存量来源兼容修复精确应用到主源码；组合后的72个唯一契约方法通过，见根目录 `.local/state/oer-gct-main-apply-20261010/`。Java关系保全目前只有静态证据，自动检索闭包和业务质量仍待实跑。

为通过统一 runtime 的已存在 scope policy 门禁，创建独立 `actual-02-runtime-policy/`，仅改变 adoption 的 `scope.scopePolicySha256`，绑定完整 SL 技术 policy；不改旧 actual-01。新 sourceHash `bf71ad10ff1b2af78bc789f1d4eb3e99ccc830c2fdcea6e7f36ce000883d3852`，projection address `5a975279eeeac32c2346df59075733943fba7482b8243d82d35a1e06d91b424f`，完整JSON文件SHA `42a259a795c6348d6e8c016a1ecbfd46a1bbc57d9575ef88d1824872af8a99e6`。34 adopted blocks／18 units／四关系／18资产保持，仍复用 actual-01 的实际 CAS；新 cold registry 及独立 policy delta 核验通过，独立收据位于根目录 `.local/state/oer-gct-visual-adoption-independent-review-20261010/runtime-policy-delta-01/delta-review-receipt.json`，SHA `583f3f3c5c1b5556b2305a5b5ffaf256de1f776ee44af876741b8c5461b31ed8`。后续新隔离runtime健康／完整canonical／18资产GET通过，真实神经检索仍是独立验收，不重跑本轮OCR。

当前有完整源页对应的视觉块规范产物及来源图片回取，不等于整源法律质量或自动版式处理通过。新产物尚未经过 Java 真实消费者登记、BGE／rerank、正式题、Vetting 与前端验收；没有表格／图片内容质量分母，没有六固定来源整体验收、训练或 H800 发布。source-reviewed 关系与模型原始关系分别保存。
