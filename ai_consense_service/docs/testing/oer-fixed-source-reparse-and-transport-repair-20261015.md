# 固定来源重解析、历轮未决比较与传输修复

日期：2026-10-15，Asia/Shanghai。工作目录为 main；Vetting PM 规则保全和能力补齐已提交 `17965365889c6984bb896692db5a84f507b24cc4`，见[该轮实施记录](vetting-competition-prompt-implementation-20261015.md)。本文记录其后的真实解析与必要工程修复，不把代码测试当来源质量。

## 本次授权、原件和分母

用户要求先完成 Vetting，再重解析固定 NTT/SCC/SCT 模板及 GCC/GCT/SL，并特别要求核查 fixed05 之后多轮训练仍未解决的问题。最新字段任务实际来自 AoA 扫描件 4 页和 APL 分包商表 3 页，因此一并保留其原 57 字段、74 写区、11 关系分母。原件、gold、fixed05/Local05、活动服务和 DB 不覆盖，不训练、调参或自动采用后期候选权重。

八原件均已核实 SHA。执行库存为 **186 个完整输入**：3 DOCX + GCC + GCT + 179 个完整 SL 技术分册 + AoA + APL。PDF 原页位置共 **3,257 = 74 + 2 + 3,174 + 4 + 3**。SL 的已确认技术范围为该冻结版本物理 1127–4300，3,174 个唯一原页无缺失/重叠；没有先解析 4,300 页再删除排除页。Word 缓存页数不作为本次物理排版页数。

对比沿各轮同源旧产物和候选采用状态进行：GCC R128 的 66 定义及其 169 成员行；GCT 的 43 可见单元、11 组及引用/条件/空控件；SL R116 五个 COM11 开发组的 672 格、12 外部成员、87 关系和五完整组；R157/R158 的全量字段域。旧侧车或未采用候选不冒充 main 已具备的正常入口能力。三份 2a DOCX 未找到相同 SHA 的正常旧质量基线，首次完整新输出不据此授改善。

## 首次真实执行与保留的失败

首轮 `actual-native-01` 使用刚通过 Vetting 自动化的隔离 JAR，经过当前真实 observe → 身份/阅读规则 → parse 接缝；不开 Spring/DB/索引，不进行视觉 OCR。完整输出采用流式 gzip，另保存比较投影。原件 SHA 和原页映射在每项入口校验。

实际发现共享传输阻塞：GCC 和多份 SL 在 `PaddleOcrClient.observeNativePdf` 的整棵 JSON 树/字符串物化阶段 heap OOM；GCT 和 SL 合法上下文被 Starlette 普通 multipart 字段的 1 MiB 限制以 HTTP 400 拒绝。后续重复失败尝试由拥有者停止，仅关闭该轮进程，原服务保持。

停止后的完整 186 输入账本为：**22 返回但未评分、5 失败、1 中断无关闭 driver、158 未执行**。这些状态不计质量通过。GCT 原生字面诊断为 42 exact/1 unknown，与旧原生投影相同；历史侧车合并的 footer 在原始输出中是两行，没有为匹配答案强行合并。GCC 无新闭合输出不能评分。旧失败、完整分母和未知逐项保留。

本地证据根为 `.local/oer-fixed-six-reparse-20261015/`：`actual-native-01/owner-stop-receipt.json`、`comparison-native-stopped-01/comparison-report.json`（SHA `27bf281e6e023af0922c7fa541608dcfdffe732aa920db19e27f238edd7d040b`）及原运行日志。完整合法请求前的 OpenAPI/缺字段 500 也保留，未冒充就绪。

## 最小修复及适用自动化

- 完整 Java 原生证据流式写入有界 JSON 文件，作为 `nativeContext` 文件 part 发送；旧文本表单仍兼容。每批保留完整原始 PDF，按不超过 64 选页和 32 MiB 实际 UTF-8 分批，超限只在发送前拆分；单页仍超限明确失败，不能截字段或提高上限。
- 每个真实批次严格核源身份、页域和三方法，组合引用明确与单个 CAS 区分；每页候选保自身真实批次引用。失败批次、后续未执行和 unknown 保持完整分母，不能伪造页/方法或把首批 CAS 当整源 CAS。
- native 专用五参 `HttpSupport.postMultipart` 在解压后读取阶段执行 8 MiB+1 边界，超限先取消调用，避免无界物化及关闭时继续读取。普通三/四参调用保持；multipart 的 one-shot 传播进入真实发送边界，禁止自动重发/重定向。
- 临时文件清理失败进入显式账本，保残留路径、异常和 `not_cleaned`；已取得的页/ref 及原失败不丢，后续批次停止。单批也保显式组合封套。alternate client 在前批失败后伪造成功会被拒绝。

真实 RED 历史均保留：原传输五红；首 focus 的 148 pass/7 fail 暴露库不传播 one-shot；priorFailure 一红；cleanup 三个真实删除故障；bounded HTTP 的 declared/chunked/gzip 64 MiB 旧实现完整读取事实。适用验证如下，后续终态另续记：

| 验证 | 当前可核结论 |
| --- | --- |
| Java 聚焦 | focus03：13 类/164 passed，0 failure/error/skip；仅取消时机最后修正的 focus04：7/7。两轮有重叠，不相加。 |
| Java 完整集合与打包 | full03 实际退出 0：296 类/2,911 reported，2,788 passed、0 failure/error、123 既有条件 skip。相对 full02 新增四类/18 用例且全过，原 selector 和 skip 逐项集合不变、782 源文件无漂移。package02 实际退出 0，本步跳过重跑测试并绑定 full03 四份 SHA 收据；JAR 79,672,518 bytes、SHA `22926e3dc74abb9b38009e054f56eb60997c5f93590f487bdcc5add78afa3c6f`，626 classes+42 resources 逐项一致，90 依赖 JAR 与前绿构建完全相同。 |
| Python focused | 真实旧源码 RED 后，当前 route/candidates 22/22，0 failure/error/skip。 |
| Python full02 | 实际退出 0，1,642/1,642 次测试执行，1,533 个唯一 ID；0 failure/error/skip/unraisable，244 源文件运行中及终态后无漂移。旧 1,636 次执行的 ID 与原重复次数全保留，新增六个 route 用例。原有重复 discovery 未改，不把执行数当唯一 ID 数。 |
| Python 中断 | full01 无终态收据，原进程已不存在，原因/退出码未知；原日志与 interruption receipt 保留，不授通过。full02 由主任务持有至实际结束。 |
| 独立审查 | Standards 两项 P2、Spec 一项 P2 均经修复复审关闭；没有以源码审查代替来源质量。 |

Python full02 收据 SHA 为 `84c4e917d47045fce40676156b02b970864f45e43643027fb6aaeaab0ac0a590`；核算见 `python-full02-count-and-drift-audit.json`。Java/前端前轮及本轮各次日志在 `.local/vetting-prompt-competition-20261015/`，主 `target`/`dist` 未替换。

## 修后效果阶段与剩余门

修后完整原件重跑仍待最终完整自动化、打包和运行身份冻结。执行保留全部 186 输入，优先 AoA/APL、GCT 和完整 COM11 分册等旧未决，不能以预算先耗于无关页而缩掉问题域。隔离 Java 8 GiB、CPU 服务另 2 GiB，最多 60 分钟；两输出根（含 CPU CAS）合计 32 GiB。实际 HTTP 派发前独立计数，最坏每原页一批共 3,257 请求；逻辑观察与 wire 不混用，0 自动重试、0 视觉/模型调用。到限保失败/中断/未执行。

真实视觉层仍须另核就绪和预算。历史 FP32/FP16 配方不能冒充当前 BF16 complete-provider 身份；本轮新 Linux Paddle 环境安装缺 NCCL 包下载失败且未 runtimeReady，没有加载模型或沿用未采用 LoRA。总 SDK 内部尝试 8,192 上限尚未落实，不能把 HTTP 次数冒充内部 forward/generate 次数。本轮尚未执行视觉模型，不关闭 57 字段/74 写区/11 关系等原质量门。

解析完成后按冻结比较器重算各独立方法和原正确保持，失败/unknown/未执行分别报告。端到端、浏览器、检索/重排、合同业务效果、生产/云部署和盲测不在本次恢复范围；本记录不宣称完整质量目标达成。
