# Drafting 变量识别考题验收

这套脚本通过真实 ConSense Drafting API 运行考题，并离线对照参考答案。模型输入仅包含 `03_missing_evidence_test/01_upload_documents` 的 DOCX；`02_reference_do_not_upload` 的内容只供评分器读取。赛方 2a 的 NTT、SCT、SCC 原模板分别上传到标准模板区。

## 运行

在 `D:\R&D\ConSense` 的 PowerShell 中：

```powershell
python scripts/run-drafting-exam.py --out-dir .local/state/drafting-exam/my-run
python scripts/validate-drafting-fixture.py --fixture Ref/simulated_2b_correspondence/03_missing_evidence_test --actual .local/state/drafting-exam/my-run/variables.json --trace .local/state/drafting-exam/my-run/trace.json --plan .local/state/drafting-exam/my-run/plan.json --out-dir .local/state/drafting-exam/my-run/scoring
```

一次实际识别会多次调用本机模型，耗时取决于返回长度和修复/召回次数。重新评分只读取已保存的结果，不调用模型。每次真实运行会创建独立、明确标注为自动验收的新项目；脚本拒绝复用已有项目或覆盖结果目录，不确认候选、不生成合同。

可以先准备资料，再单独启动识别：

```powershell
python scripts/run-drafting-exam.py --out-dir .local/state/drafting-exam/prepared --prepare-only
python scripts/run-drafting-exam.py --extract-prepared .local/state/drafting-exam/prepared
```

`--stage 1` 到 `--stage 15` 可用独立项目检查逐步披露的资料状态。评分命令的 `--stage SIM01` 等参数应与这次上传阶段一致。阶段测试不能复用已有项目的候选值作为本轮识别成功的证据。

## 判定范围

期望 79 项分别为：60 项直接提供、1 项推导、4 项刻意缺失、1 项投标阶段未知、11 项条件不适用、2 项可选且保持模板原文。不能将所有空值当作漏识别，也不能将 `false` 或 `[]` 当作未知。

核心验收包括业务值、正式名称与自由文本保真、真实引用、来源身份、缺失证据是否保持可编辑，以及候选是否未经人工确认。`confirmed=false` 与 `manuallyEdited=false` 是本轮应维持的边界；候选预填本身不代表人工采用。

四个刻意缺失字段是 `photocopyRateAboveA3`、`projectArchitectPhone`、`siteInspectionEndDate` 和 `quotationBeforeVariation`。它们应保持空值、没有填值候选、没有人工采用；特别是没有采用决定不能变成 `false`。

Bill 父行正确也不等于完整分发/用途图正确。参考答案另外列有 `billDistributionAndUsageOracle` 和 `knownRepresentationLimitations`；组件级 detailed SOR 分发与 trade links 须独立列出，不能用新增父 Bill 或修改正式描述来补齐结构限制。

每次运行目录保存项目标识、上传文件字节 SHA256、解析读回、目录、变量、原始模型返回和抽取决策、计划、进度、耗时及 GPU 快照。项目中 candidate 的 `sourceHash` 是应用解析身份哈希，不是 DOCX 字节 SHA256；两种身份分别核验。较新的运行器还保存本机配置/编译产物身份及模型日志区间。普通 Drafting chat 当前不保存 API usage/finish_reason，因此日志 token 数与输出上限命中只作诊断，不能将未知 finish_reason 伪装成 `stop`。

## 当前实测

首轮真实识别使用原先部署参数：32K 上下文、thinking 关闭、服务器默认输出上限 2,048。结果在 `.local/state/drafting-exam/baseline-32k-off`，已完成全部 15 份 DOCX：61 项需要取值中，58 项找到正确且有出处的候选；18 项应留空全部正确，四项刻意缺失全部正确。严格离线结果为 **55 PASS / 21 REVIEW / 3 FAIL**，耗时 659.422 秒。

三个 FAIL 是 `billNos` 的完整清单与支持引用、`otherWaterproofingSpecificationAreas` 的长文本保真、以及 `sections` 将明确的空列表回答成 null。21 项 REVIEW 已另行人工核对，20 项引用支持答案，1 项 `designResponsibilities` 只有矩阵导言引用，完整上下文支持值但引用单独不足；自动分数保持不变。具体证据见 `docs/drafting-baseline-evidence-review.md`。

baseline 的引擎区间记录 41 次调用、17,862 个生成 tokens，单次最大 1,592，没有达到 2,048 上限。这轮 baseline 的三项失败不能直接归因于输出截断。普通 Drafting adapter 不保存 API finish_reason，因此不将其补写为 stop。

两组 64K/F16、16K 总生成对照也已完整结束。全部保持业务 harness、提示词、参考答案、上传资料和模型/引擎不变，两组新实验均固定 seed 42。

| 配置 | 正确且有出处候选 | 应留空 | PASS / REVIEW / FAIL | 耗时 | 错误或冲突候选 |
|---|---:|---:|---|---:|---:|
| 原 32K / off / 2K | 58/61 | 18/18 | 55 / 21 / 3 | 659.422 秒 | 0 |
| 64K / medium / 4K 每思考块 / 16K 总生成 | 60/61 | 18/18 | 55 / 23 / 1 | 3,910.937 秒 | 0 |
| 64K / off / 16K 总生成 | 58/61 | 18/18 | 55 / 21 / 3 | 605.156 秒 | 1 |

目前最高已测候选覆盖率是 medium 的 60/61，仍为 FAIL；不代表全部 79 项通过。它补齐了 `billNos` 与 `sections`，唯一剩余 FAIL 为 `otherWaterproofingSpecificationAreas`。`domesticBlocks`、`foundationDesignSubmittedWithTender` 的业务值仍正确，自动引用支持诊断降为 REVIEW；`nscAlternativeText` 有弯引号变直引号的字面差异，业务等价仍 PASS。新增人工 delta 核对在该 run 的 `manual-evidence-review-delta.{json,md}`，保持原自动分数。

off16k 额外生成 `advancePaymentAdopted=false`：它把 SIM14 的“未改动标准还款参数”误当成取消 SIM13 明确采用 advance payment 的决定。真实引用与错误的业务解释并存，故正确候选覆盖仍 58，但显示非空建议只有 57。该配置被安全约束排除于 best 排名，不能为了速度把它当作推荐替代。

三轮都没有观测到总输出上限命中。medium 的防水实际两个请求分别覆盖前 16 项及后 7 项，没有单个请求包含全部 18 项；同时存在字面改写、quote/value 不配对及字符串误返数组。模型 server 窗口增加不自动改变这些实际证据分片。本文不据此否定用户提供的 MiniMax 成功经验，也未复跑官方 API；当前 local trace 需要与成功 trace 进一步对照后才能确定改动方向。

三组只读比较、每字段变化及来源 hash 位于 `.local/state/drafting-exam/comparison-all-20261008/drafting-comparison.{json,md}`。性能分解见 `docs/drafting-performance-assessment.md`。medium 首次恢复遇到瞬时端口占用检查失败，已保留初次回执并完成原配置恢复；off16k 和后续 55K 合成容量测试均自动恢复成功。当前服务为原 32K/off/2K 配置，三个服务 ready、原 runtime/JAR 身份恢复、watcher Running。
