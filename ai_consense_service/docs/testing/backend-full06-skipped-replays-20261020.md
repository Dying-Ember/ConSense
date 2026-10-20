# Full06 条件跳过项：逐项核对与保存观察补跑（2026-10-20）

Full06 原正式结果保持 **359 suites / 3,759 tests = 3,660 pass + 9 fail + 14 error + 76 skip，BUILD FAILURE**。本次对全部76个原跳过节点建立逐项账本，使用同一冻结源补跑已有保存观察与原件门；原失败、原skip及其分母没有修改。

原全量执行、旧正确回归及109条环境失败补证见[Full06与环境回放](backend-full-round06-and-environment-replays-20261020.md)。本次总账本 SHA256 为 `9fe5697d0ba7d7f11e4a5869af21537dafabe41cc00d784f8af922dea2a54745`，每批摘要另记录下列数字到完整XML的映射及文件hash。

原代码树为 `534f0925749971bec2fc6bb7325aa7a841da98537c1ef17dccce4fc5725abb52`，共1,482个文件。每个新项目在复制前、运行后重核全部源文件哈希；原件、保存观察、已有确认值及必要副本均在执行前后核对。没有产品或测试代码改动。

| 独立补跑 | 对应原skip节点 | 实际执行通过 | suites | fail / error / skip |
| --- | ---: | ---: | ---: | --- |
| skip-replay01 | 20 | 37 | 10 | 0 / 0 / 0 |
| skip-replay02 | 33 | 63 | 7 | 0 / 0 / 0 |
| skip-replay03 | 1 | 1 | 1 | 0 / 0 / 0 |
| skip-replay04 | 1 | 1 | 1 | 0 / 0 / 0 |
| skip-replay05 | 1 | 4 | 1 | 0 / 0 / 0 |
| round06-bindings4 | 4 | 4 | 1 | 0 / 0 / 0 |

合计60个原skip节点有独立通过结果，对应110条实际测试；其中本次五批新补跑106条，既有bindings4专项4条。原整类和动态工厂skip会在启用后展开，因此110不能从76直接相减，也不能加到Full06正式pass总数。每个原节点到实际XML的方法、参数序号或整类子方法映射都保存在总账本。

保存观察补跑包括完整层级上下文、模型材料、literal/unknown/dedup、原Python模式与同源PDF家族、Q1.6材料、Specification精确原件身份和持久路由。它们只消费原捕获，不新调用parser引擎或模型。路由JPA测试保留`verifyNoInteractions(ai,parser,oer)`；模式client只用自有回环fixture。

Drafting补跑包含23个原件负向输出、23个源绑定规则、9个修改/错源/人工保留/相邻范围反例、合并SCT/SCC输出，以及7个原件生成状态、domestic和SCT子项方法，共63条。它们使用2026-10-10已保存的确认值，只在测试内存和独立输出中运行，不为当前项目采纳这些值。另用两份精确NTT原件完成standard/tender指令差异1条，配置禁用LLM/OCR并使用隔离H2。

Advisory的完整wire准备只执行prepare，1条通过。复制输入唯一改变`outputDirectory`，完整system/user、safe profile、历史来源hash和声明策略保持；transport在任何counter/generation请求时直接抛错，实际provider请求0。历史声明的1M窗口没有被当成当前实测窗口或当前来源session接受。

UTF-8大输入专项在独立JVM中执行原有4个方法，固定715,827,883个UTF-16 code units，16GiB堆、180秒总进程上限；启动前确认至少48GiB物理内存空闲。该专项检验既有UTF-8和canonical构造的溢出/字节/摘要保护，不证明模型容量或业务质量。

仍有16个原节点未执行：

| 原节点数 | 原因和保留边界 |
| ---: | --- |
| 9 | desktop-x2t门要求独立exe、assets/font cache和冻结六份包，断言版本9.4.0.129；当前`.local`及已检查的Program Files位置没有确认该runtime。现有LibreOffice验收不替代此引擎。没有安装新runtime。 |
| 3 | 旧Advice Observed/Run02/Run03特定raw、alias和source捕获尚未定位；Run02/03精确文件名在`.local`无匹配。Observed允许任意文件名，其特定输入对只记未定位，不宣称所有位置都不存在。当前新模型capture没有改名灌入。 |
| 1 | language-origin旧记录/context两个精确文件名未找到；既有普通语言门与该次旧捕获回放分开。 |
| 1 | 保存291-variable图未定位。限定范围内只找到当前24-variable图，不替代，不造291节点。 |
| 2 | 精确opt-in LibreOffice converter/binding方法本次不重复；Full06真实Artifact28、独立bindings4及126页PDF概览已有相应实际原件/marker/字节保护覆盖。这两条exact方法仍是未执行。 |

完整可重算账本位于 `.local/e2e-20261020/drafting/round06-all76-skip-classification.json`，含76条原身份、原skip原因、每条补跑XML/hash或未执行原因、源门行号、全部收据hash。各批完整XML/log存放在`.local/e2e-20261020/build/skip-replay01-final`至`skip-replay05-final`。首批PowerShell launcher在Maven前失败、0测试的证据单独保留，成功后继使用direct Java参数数组。

本次没有新OCR、外部引擎、embedding/rerank、LLM/provider、共享18084、活动DB/index、服务重启或ACL操作。此前109条环境失败补跑另列，不计入本次110条。现部署09/后继10、真实MiniMax、浏览器、来源完整质量与产品业务接受继续分别报告。
