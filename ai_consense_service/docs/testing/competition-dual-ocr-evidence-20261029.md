# 比赛运行基线：完整双路 OCR 证据更正

日期：2026-10-29。范围：只读复核既存配置、回执和文档；未打开原件，未启动、停止或修改任何运行服务，未发出模型请求。按[当前规则](../oer-scope-and-rules.md)及[本次实施说明](../specs/competition-container-external-runtime-20261029.md)区分历史真实推理、当前资产状态与新 Docker 验收。

**更正：完整 OER 并非只有 RapidOCR。** 18780 的 CPU 协调进程负责调度完整 PaddleOCR-VL-1.6 与 MinerU 4.0.10 Standard；CPU 协调器身份不意味着下游仅执行 CPU 识别。18190 的 CPU OCR 是另一支线，不能据此否定完整 OER 已有接入和实际双路推理。历史证据也不代表本次 Docker 运行已通过。

## 配置与采用身份

本机证据目录 `.local/e2e-20261020/runtime21-current-source-preparation01/` 中，`complete-current-source.json` 本次重算 SHA-256 为 `eb85feca2a58d2382d11191c0da8717010167b221dd85b7d2763dbbdc0da91ec`；`coordinator-boot-current-source.json` 的配置环境变量及 runtime asset 同时固定此哈希并声明 18780 入口。以下 pins 直接读取该配置，不从目录名称推断采用版本。

| 配置组件 | 模型身份 | revision |
| --- | --- | --- |
| PaddleOCR-VL1.6/native，parserVersion 3.6.0 | PaddlePaddle/PaddleOCR-VL-1.6 | `c5630abae1d940eafe0697512a0325494b02ab42` |
| Paddle 布局 | PaddlePaddle/PP-DocLayoutV3 | `241f8bdfc77a7c7bee915a5057aaee58c235a8d3` |
| MinerU/official-api/vllm-async-engine，parserVersion 4.0.10，tier standard | opendatalab/MinerU2.5-Pro-2605-1.2B | `08aaea840498d49ce16247b6263196cf02814885` |
| MinerU 小模型 | opendatalab/MinerU-4_models_onnx | `358310b4f64b95f9fefc372ad899356e4111f376` |

`root-restart02/receipt.json` 固定 boot SHA `1029fec53109fdebc96763e0a25c60539865d78e881d3eace2e9cc0a120103d5`，记录 2026-10-21 同一协调 unit 的受控恢复与 health 200；其中 `providerPosts=0`，不能把该恢复本身计为模型推理。当前历史模型软链接部分缺失；本轮已找到同 pin 的替代资产路径，48 项 checkpoint／配置资产的 SHA-256 与历史清单一致，另 1 项本地完成标记从旧 runtime 独立补证核同。主验证窗口唯一的标记路径缺失仍保留为 FAIL，不改写为单窗 49 项全通过；详情留在本机受控验证收据。尚未修复软链接或将这些资产用于 Docker 运行。

资产核验收据位于本机忽略目录 `.local/container-rehearsal-20261029/dual-ocr-identity/`：主票 `receipt.json` SHA-256 为 `878f4d0441aa6e20469d05c9707b5f4d1440b805b7fc5be26be8101bcad71804`，补票 `marker-supplement.json` SHA-256 为 `2ac051a171fee56d018254a292cc485f7de308378bd91a30c0d6afd0631dfd83`。主票分组为 Paddle 18、布局 4、MinerU 小模型 15、主 checkpoint 11 项通过；本地标记另有 1 项补证。

## 实际执行与验收边界

[2026-10-15 识别修复记录](oer-recognition-repair-20261015.md)已记录正常 Java `DocumentParser`／`PaddleOcrClient` 接入 18780，完整两输入的七页真实双路运行及后续常驻接线；其来源质量未通过，不能把完整返回等同于语义准确。

本次逐项读取 `.local/sl-fwp-seven-semantic-review01/actual-dual/<版本>/receipt.json` 和同目录 `engine-page-ledger.json`，复算如下。每行 POST 是协调入口请求数；fresh 是 provider 页执行数，不能当作全部内部计算次数。每行 ledger 均有四条 `completed`／`fresh_provider`，与 receipt 的 fresh 数一致。

| 版本目录 | 协调 POST | fresh provider | receipt SHA-256 |
| --- | ---: | ---: | --- |
| competition-batch02 | 2 | 4 | `88ad207572fb7b4b3baf6ed291db9a6b9a068ad693b495e7d2f25f04febdcb91` |
| sl2022-batch01 | 2 | 4 | `a3f42177100173cfe52924504dcfc1b9299beda17d1658acd6d455ca6d6a5aec` |
| sl2022-batch02-rev1 | 2 | 4 | `a76111f8f3020719b9862acb9bddecac72b260c962118f3c954ac1fdc862fee9` |
| sl2022-batch03 | 2 | 4 | `4480f66840eaf373fd1355ae1799196054e97a0339523180f015b7465a042243` |
| sl2022-batch04 | 2 | 4 | `2da1e72049438cb551b56d3d68d7f802dcf40041813344c37a105889368d5fc9` |
| sl2022-original | 2 | 4 | `7fd8a928c01707d6f43cd58daf3a6b8e52edc9a8ab9b70eb5df1210ada8abc06` |
| sl2026-original | 2 | 4 | `cad33e973bb8d682b1aae55a5588345d179547d9b4f8561ff84792480930cd95` |
| 以上逐行相加 | 14 | 28 | 七份 receipt 与七份 ledger 分别核对 |

以上每份 receipt 均记录 `providerCacheHits=0`、`retryCount=0`、`sourceQualityAccepted=false`、`canonicalPublished=false`。因此真实新推理与完整回执已经存在，来源质量、全局 canonical 发布及完整业务验收仍不能授予通过。[三模块进展](three-module-runtime-progress-20261021.md)的 progress 相关记录保留了两路源评中的 UNKNOWN／FAIL，不能以本次身份更正覆盖。

## 后续记录与本次限制

[2026-10-27 Unlimited OCR 2 比较](../research/unlimited-ocr2-oer-comparison-20261027.md)仍以这两套完整 pipeline 为现有基线，引用实际双路回执；Unlimited 的 CPU 加载研究没有替换现有视觉引擎。[2026-10-28 OER 查询接缝检查点](oer-workflow-query-bottleneck-checkpoint-20261028.md)记录服务与下游证据协议修复，不能据此宣称更换视觉模型或源质量通过。

本次已核对所列资产的历史同 pin 字节来源，但未验证环境可加载、真实 Docker 推理、Docker 下完整业务或赛方验收；也未停止旧任务，不把历史受控恢复写成自动重启已清理旧任务。此前未完成工作依最新决定作废，不因本次证据更正重新启动旧质量整改；历史原件、失败和回执继续保留。
