# Strata × Flash-Next IQ3_S：本机部署与实际测试

日期：2026-10-14。

**已完成安装、真实模型调用、约250K输入合成诊断及两项512-token流式测速。本机可共存配方为原版IQ3_S＋262,144总窗＋28GiB CPU专家预算。** 较长输出实测24.9／28.5 token/s，此前25–40仅为预测，不再作为本机保证范围。

**当前已停止Strata，让出正在等待显存的OER训练；安装和一键起停保留。** 停止后空闲显存13,803MiB，Windows提交占用75.488／135.696GiB。没有启动该训练或宣称其已完成。原ConSense Java PID49244、固定检索PID25948/64128的创建时间保持相同。[停止后资源](../../../.local/strata-iq3s-20261014/evidence/final-stopped-resource.json)、[原服务身份](../../../.local/strata-iq3s-20261014/evidence/protected-processes-after-stop.json)、[停止收据](../../../.local/strata-iq3s-20261014/evidence/normal-final-stop.txt)。

## 1. 制品与校验

- Windows原生；i9-12900、128GiB RAM、RTX A4000 16GiB，D盘NVMe存储。[初始硬件快照](../../../.local/state/strata-local-estimate-20261014/hardware-snapshot.json)。
- Strata源码：`296b59d2f73c8f04a805df29c2fa97655adace78`；引擎0.1.44.1、CUDA13，含sm86。
- 主模型：`ISTA-DASLab/Qwen3.8-Flash-Next-GSQ-RCO-GGUF@ed59f92082b1e93c0e96d60a8b11aab089b52f09`的原版IQ3_S，两片合计83,617,662,656 bytes＝77.875GiB；不是Swift制品。
- MTP：`Qwen/Qwen3.8-Flash-Next@de4b8e4d43b917e7706784d8bb445c9af86a3540`的31个tensors，共5,214,301,696 bytes，仅取byte ranges。本地打包Q2_0草拟层；主模型仍为IQ3_S。tokenizer从GGUF提取。
- 完整155项校验通过：两GGUF的size/SHA、引擎ZIP及解包文件、Git、profile/CJK词表、原MTP tensors、pack/runtime布局和venv依赖。CUDA wheels为cublas13.0.2.14、runtime13.0.96。[完整核验](../../../.local/strata-iq3s-20261014/evidence/install-verification.json)、[固定HF清单](../../../.local/strata-iq3s-20261014/evidence/model-hf-pinned-tree.json)。派生布局检查本身不证明推理保真，实际调用另列。

## 2. 运行配方和提交额度

目录：`D:\R&D\ConSense\.local\strata-iq3s-20261014`。仅监听`127.0.0.1:18080`，模型ID为`qwen3.8-flash-next-iq3_s`。单请求、vision关闭、thinking默认none、MTP spec4、`STRATA_ROUTE_TAIL_SKIP=0`，experimental speed projection未启用。

核心参数：`--max-context 262144 --kv int8 --kv-resident 32768 --spec 4 --resident-budget-gib 28 --vram-reserve-mib 2048`。28GiB仅指CPU专家副本预算；另有约5GiB GPU专家缓存、dense/MTP、KV及驱动，其他专家仍可通过GGUF／OS文件缓存读取。KV streaming实报3.09GiB pinned host RAM；此前规划公式3.352GiB含MTP和保守余量，两者口径不同。

| 配方及来源 | 加载后Windows commit／limit GiB | 余量GiB | 实际处理 |
| --- | ---: | ---: | --- |
| [全专家RAM](../../../.local/strata-iq3s-20261014/evidence/load-resource-01.json) | 141.343／141.800 | 0.457 | 加载到ready，未生成，安全停止 |
| [36GiB预算](../../../.local/strata-iq3s-20261014/evidence/budget36-loaded-resource.json) | 131.557／135.696 | 4.139 | 驻留成功但仍告警，未生成，安全停止 |
| [28GiB预算](../../../.local/strata-iq3s-20261014/evidence/budget28-load-resource.json) | 123.488／135.696 | 12.208 | 完成全部下述基准 |

长测试保存样本中的最高commit为125.555／135.696GiB，余量10.141GiB；只是采样点，不称连续峰值。[运行样本](../../../.local/strata-iq3s-20261014/evidence/long-stream-resource-01.json)。普通配置复测后commit为123.289／135.696GiB，物理可用50.692GiB，NVML空闲1,860MiB；均为整机读数，含原服务。[普通配置资源](../../../.local/strata-iq3s-20261014/evidence/normal-final-resource.json)。

## 3. 新输入读取和合成正确性

benchmark显式使用`--prompt-cache 0 --conversation-cache-mib 0`。每项生成前精确计数，最终counter、usage和engine prompt_n相同，cache_n和cached_tokens均为0。GPU专家缓存及OS文件缓存可以为暖状态，不能称冷模型／冷磁盘基准。

材料为重复中性记录，首／中／尾放ORCHID、JASPER、COBALT，并要求判断R2的certified emergency覆盖R1。四档均返回准确五字段JSON、stop、无reasoning。计数调整不推理；250K的297031／250006两次较大计数没有发送生成，仅最终合规大小生成。

| 用例及证据 | 实际输入tokens | 输出tokens | 引擎读取秒 | PP token/s | 短JSON TG token/s |
| --- | ---: | ---: | ---: | ---: | ---: |
| [4K](../../../.local/strata-iq3s-20261014/evidence/20261014T035826Z-b39f7d13/4k/validation.json) | 4,087 | 39 | 6.210 | 658.1 | 17.9 |
| [32K](../../../.local/strata-iq3s-20261014/evidence/20261014T035826Z-b39f7d13/32k/validation.json) | 32,757 | 30 | 25.030 | 1,308.7 | 11.6 |
| [128K](../../../.local/strata-iq3s-20261014/evidence/20261014T035826Z-b39f7d13/128k/validation.json) | 131,064 | 52 | 107.497 | 1,219.2 | 4.7 |
| [250K](../../../.local/strata-iq3s-20261014/evidence/20261014T035826Z-b39f7d13/250k/validation.json) | 249,987 | 39 | 215.591 | 1,159.5 | 25.0 |

短JSON只有30–52输出tokens，TG波动较大，尤其128K的4.7；不能把它推广为所有长窗的稳态速度，也不能据此认定上下文长度是单一原因。此表为非流式，没有观测TTFT。[整轮summary](../../../.local/strata-iq3s-20261014/evidence/20261014T035826Z-b39f7d13/summary.json)所在目录保留完整request、raw response、HTTP receipt和每次count。

另有精确中英JSON与R2例外两个短smoke均通过。[smoke summary](../../../.local/strata-iq3s-20261014/evidence/20261014T035710Z-2c578b18/summary.json)。

## 4. 512-token流式样本

| 证据 | 输入／输出tokens | 引擎PP | 引擎TG | 首个非空可见内容TTFT秒 | HTTP完整耗时秒 |
| --- | ---: | ---: | ---: | ---: | ---: |
| [短输入](../../../.local/strata-iq3s-20261014/evidence/stream-20261014T035733Z-ab877645/receipt.json) | 119／512 | 60.3 | 24.9 | 2.049 | 22.577 |
| [长输入](../../../.local/strata-iq3s-20261014/evidence/stream-20261014T040606Z-b6f7de1b/receipt.json) | 250,083／512 | 1,151.7 | 28.5 | 217.399 | 235.279 |

TG＝generated_tokens／engine_decode_seconds；长样本512／17.9485＝28.5261。HTTP端到端completion速度包括首次读取，短／长分别为22.6781／2.17614 token/s；后者不等于解码只有2 token/s。TTFT直接取第一段非空白content SSE，role／headers不当首字。

两项都是HTTP200、单发、完整[DONE]、无provider error／reasoning、cache_n＝0。80行题目用于取得长输出，均在固定512token上限达length截断，仅有38／35条带内容连续编号；不是80行完整任务通过。长样本完整保留原250K user材料947,102 UTF-8 bytes，仅追加测速指令并更换合成system nonce；它是速度测试，不是新增needle质量复测。request／SSE／events／receipt及公式均已由另一作者只读独立复核。

实测最大完整请求为**250,083＋512＝250,595 tokens**。262,144是包含输入、历史、thinking及输出的配置总窗，没有把全部窗口作为输入，也没有声称精确极限或512K已验。

## 5. 瓶颈与预测修正

首先遇到的是Windows提交额度：全RAM加载时物理仍可用约27.95GiB，但commit只剩0.46GiB。没有改全局分页文件或停止原有作业；28GiB私有专家预算解决了本轮资源准入。

当前路径混合CPU AVX2、GPU缓存、PCIe和文件缓存。启动PCIe probe实测18.8GB/s；短512样本日志给出CPU pool 82us/missed expert、24.9GB/s expert bytes，以及76.4%条件cache hit、7.1% PCIe routed share。它们分别是链路小测、专家字节率估计和计数比例；**没有测DRAM物理流量，不能宣布内存带宽已饱和**。hit率分母排除了PCIe offload，file_mb为逻辑读取且可能由OS cache满足，也不能当作物理磁盘字节归因。[引擎日志](../../../.local/strata-iq3s-20261014/logs/engine-benchmark-budget28.log)、[metrics](../../../.local/strata-iq3s-20261014/evidence/after-long-bench-metrics.json)。

这两个512token合成样本支持按约25–30 token/s作本机开发预算；本次32K–250K合成新输入约1150–1310 token/s。250K首字实际需约3分37秒。材料路由、缓存、草拟接受率和其他负载会影响速度，不能给所有工作负载保证。

## 6. 起停和验收边界

普通配置恢复引擎默认prompt checkpoint缓存（源码默认6），未增加多会话8GiB pool；已真实重新启动并通过两个短smoke。[普通配置smoke](../../../.local/strata-iq3s-20261014/evidence/20261014T041254Z-0bf5da06/summary.json)、[runtime](../../../.local/strata-iq3s-20261014/evidence/normal-runtime-status.json)。完整长基准均在请求缓存关闭下完成，普通缓存配置的完整长对话稳定性未验。

本次测试结束后已停；与OCR／训练错峰后可按需启动：

```powershell
& 'D:\R&D\ConSense\.local\strata-iq3s-20261014\control.ps1' -Action Check
& 'D:\R&D\ConSense\.local\strata-iq3s-20261014\control.ps1' -Action Start
& 'D:\R&D\ConSense\.local\strata-iq3s-20261014\control.ps1' -Action Stop
```

启动后UI为`http://127.0.0.1:18080/`，OpenAI API为`http://127.0.0.1:18080/v1`，模型名`qwen3.8-flash-next-iq3_s`。未安装开机自启或定时任务。Stop核PID、UTC创建时间、命令行、exe，仅停验证的自有树。离线自检覆盖JSON日期roundtrip、跨时区后代及复用父PID；最终真实捕获并停止wrapper→server→engine及自有conhost。[最终进程树](../../../.local/strata-iq3s-20261014/evidence/normal-final-tree-check.json)。

初版校验词表SHA多一字符、HTTP客户端读完后访问关闭socket、控制脚本日期／时区比较问题均已修复并加窄自检；初版失败和资源拒绝保留：[初版校验](../../../.local/strata-iq3s-20261014/evidence/install-verification.first-checker-typo.json)、[count客户端失败](../../../.local/strata-iq3s-20261014/evidence/20261014T035322Z-d5caee83/summary.json)、[全RAM日志](../../../.local/strata-iq3s-20261014/logs/engine-fullram-no-inference.log)、[36GiB日志](../../../.local/strata-iq3s-20261014/logs/engine-budget36-no-inference.log)。没有放宽模型输出标准追认通过。

安装、真实模型和合成诊断分别通过；没有切换ConSense endpoint、修改冻结harness、fixed05、索引／数据库或训练权重，正式采用和原质量门保持。未验ConSense原件及完整RAG／Vetting／Advisory／Drafting业务、H800、512K、精确带宽profiling或长时间并发稳定性；新重任务仍需错峰及复核提交余量。
