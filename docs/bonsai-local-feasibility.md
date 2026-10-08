# Bonsai 2 27B 在 ConSense 本机的部署可行性

研究日期：2026-10-08（Asia/Shanghai）。ConSense 源码基线：`b4c7339e1871fc7eba92b32edd2b76cd52d734e8`。研究部分记录第一手发布、源码和本机只读观察；研究代理没有下载大权重、启动推理、停止 GPU/WSL 服务或更改驱动。主部署流程随后已实际完成下载与验收，结果追加在文末，并详见 `../LOCAL-DEPLOYMENT.md`。

## 决策

**本机可以部署官方三元 Bonsai 2 27B；当前最小成本路线是官方 PTQ1_0 GGUF + PrismML llama.cpp Windows CUDA 12.4 预编译服务。** RTX A4000 是 Ampere `sm_86`；官方 CUDA 构建默认包含 `86-real`，已实现 PTQ1_0、PQ2_0 和 Bonsai 2 所需 Hadamard 路径。不需要自行重写 CUDA 内核或把权重重新量化。这里的“可行”是硬件、源码、发布资产相容的判断，仍须用本机模型加载、中文及 JSON 输出验证收口。[NVIDIA 架构表](https://developer.nvidia.com/cuda/gpus)、[CUDA CMake 架构配置](https://github.com/PrismML-Eng/llama.cpp/blob/prism-b10743-adfffbe/ggml/src/ggml-cuda/CMakeLists.txt)、[官方后端矩阵](https://github.com/PrismML-Eng/Bonsai-demo/blob/main/BACKEND-SUPPORT.md)

NInfer 的情况须分版本：用户指定 Ada 工具包不能直接用于 A4000，但**不能概括为 NInfer 不支持 Ampere**。现有公开 `ninfer-all` 已有 `sm_86` 与 Bonsai 2 三元路径。其发布与模型方言不等同于用户指定包；对于眼下 Windows 本机部署，官方 GGUF 预编译路径更短、更容易固定来源。[原作者当前模型卡](https://modelscope.cn/api/v1/models/shensanshu/ninfer-ada-ternary/repo?Revision=master&FilePath=README.md)、[ninfer-all 固定源码](https://github.com/iamwavecut/ninfer-all/tree/64492cb14d246fa04a5241c42e771dcfdff40753)

## 本机实时条件

只读命令 `nvidia-smi --query-gpu=name,compute_cap,driver_version,memory.total,memory.used,memory.free --format=csv,noheader` 返回：`NVIDIA RTX A4000, 8.6, 582.78, 16376 MiB, 1669 MiB, 14501 MiB`。这是查询时的余量，不保证其他 GPU 工作负载启动后还保持相同空闲量。系统可见内存 `133898988 KiB`、空闲 `95035968 KiB`；D: 空闲 `280839499776 bytes`。这些数字来自本机查询，不来自宣传资料。

Windows PATH 未发现 `nvcc/cmake/ninja/cl`，指定 CUDA Toolkit 和 Visual Studio BuildTools 根目录也未发现可用安装。WSL Ubuntu-24.04 可见 `g++`、WSL NVIDIA shim，但未发现 `nvcc/cmake/ninja`；Linux 文件系统尚有约 855 GiB 可用。预编译 Windows 路线绕过这些构建依赖；NInfer 从源码构建则须先安装隔离工具链。未启动/停止任何 WSL 分发版。

CUDA 12.x 程序可在更新驱动上运行；本机 582.78 高于 CUDA 12.x 的兼容下限。NVIDIA 同时允许 CUDA 13.x 在 580+ 驱动上使用部分功能，但新 PTX 或驱动新增功能仍可能要求更新驱动，因此作者声称“610+”不能单独作为必须升级的证据。此次优先选择官方明确建议的 Windows CUDA 12.4，保持现有 GPU 服务环境。[NVIDIA minor/backward compatibility](https://docs.nvidia.com/deploy/cuda-compatibility/minor-version-compatibility.html)、[Bonsai 2 已知 CUDA 13.3 启动问题](https://huggingface.co/prism-ml/Ternary-Bonsai-2-27B-gguf/blob/b072e1d3b35a0a630cece372c2127528e0994386/KNOWN_ISSUES.md)

## 模型版本、三元值与存储格式

博客对应的是 **Ternary Bonsai 2 27B / Qwen3.8-27B 底座**；较早 `Ternary-Bonsai-27B` 使用 Qwen3.6-27B，不能只按“27B”混用运行器、辅助头与缓存估算。Bonsai 2 权重带固定符号的 Hadamard 旋转基，推理时须应用对应激活变换。stock llama.cpp 当前会拒绝 PQ2_0/PTQ1_0；即使能读某种 Q2_0 容器，也可能漏掉旋转而输出乱码。[官方 Bonsai 2 模型卡](https://huggingface.co/prism-ml/Ternary-Bonsai-2-27B-gguf/blob/b072e1d3b35a0a630cece372c2127528e0994386/README.md)、[格式与基变换兼容说明](https://github.com/PrismML-Eng/Bonsai-demo/blob/main/MODEL-FORMATS.md)、[旧代模型说明](https://github.com/PrismML-Eng/Bonsai-demo/blob/main/Bonsai1_README.md)

| 内容 | PTQ1_0（本次首选） | PQ2_0（性能备选） |
| --- | --- | --- |
| 取值字母表 | `{-1,0,+1}` | `{-1,0,+1}` |
| 包装 | 密集三进制 trit 编码 | 每个三元值占一个 2-bit slot |
| 官方部署口径 | 约 1.75 bits/weight，5.95 GB | 约 2.13 bits/weight，7.21 GB |
| 官方性能取舍 | 更省空间；Ada 上 decode 常更快 | prompt processing 更快；Ampere/Hopper/Blackwell 的 decode 常更快 |
| 本机策略 | 先验证正确性与资源预算 | 若 PTQ1 速度不够，换同权重的另一包装再做配对测试 |

上述两包都保存三元模型；“三元”描述权重取值，并不意味着整个文件或运行显存严格达到 `log2(3)=1.585` bits/weight。缩放值与少量高精度参数也占空间。官方将其列为同模型的两种包装，没有公开它们之间的专门质量退化对照。相同三元值意味着不会因包装本身重新改变量化字母表，但不同内核的实际数值与输出仍应测量。[官方权重表示说明](https://huggingface.co/prism-ml/Ternary-Bonsai-2-27B-gguf/blob/b072e1d3b35a0a630cece372c2127528e0994386/README.md)

## 可下载资产与校验

官方 demo 当前固定 `prism-b10743-adfffbe`。本研究按照该固定版本，而不是自动跟随 latest；Windows CUDA 12.4 构建与 CUDA DLL 均是正式 release 资产。GitHub API 给出的 digest 为 SHA-256，HF 大文件 lfs.sha256 为权重内容散列。[demo 的 release pin](https://github.com/PrismML-Eng/Bonsai-demo/blob/main/setup.ps1)、[release 元数据](https://api.github.com/repos/PrismML-Eng/llama.cpp/releases/tags/prism-b10743-adfffbe)、[模型文件元数据](https://huggingface.co/api/models/prism-ml/Ternary-Bonsai-2-27B-gguf?blobs=true)

| 资产 | 精确大小（bytes） | SHA-256 |
| --- | ---: | --- |
| `llama-prism-b10743-adfffbe-bin-win-cuda-12.4-x64.zip` | 257322810 | `1b849f713bee42fda258de83770cd422e8f48dd631ce370eb0641f6458c69d87` |
| `cudart-llama-bin-win-cuda-12.4-x64.zip` | 391443627 | `8c79a9b226de4b3cacfd1f83d24f962d0773be79f1e7b75c6af4ded7e32ae1d6` |
| `Ternary-Bonsai-2-27B-PTQ1_0.gguf` | 5946648928 | `53107f530aa52eb00912263ab1ee29bd199261c87cd7b4ad4ca1318c1fe33ee3` |
| `Ternary-Bonsai-2-27B-PQ2_0.gguf` | 7206168928 | `3907dc1658db1f78a9826bf8d5bcb8dc65db0d466388937af57f2294fae62ec1` |

模型 revision 固定为 `b072e1d3b35a0a630cece372c2127528e0994386`。下载 URL 和本次默认 PTQ1 选择已保存到 `.local/state/bonsai-download-plan.json`，供主部署流程下载后核对大小、SHA 并保存实际收据。不要只检查扩展名或 HTTP 200。

- [Windows CUDA 12.4 引擎](https://github.com/PrismML-Eng/llama.cpp/releases/download/prism-b10743-adfffbe/llama-prism-b10743-adfffbe-bin-win-cuda-12.4-x64.zip)
- [配套 CUDA DLL](https://github.com/PrismML-Eng/llama.cpp/releases/download/prism-b10743-adfffbe/cudart-llama-bin-win-cuda-12.4-x64.zip)
- [PTQ1_0 完整权重](https://huggingface.co/prism-ml/Ternary-Bonsai-2-27B-gguf/resolve/b072e1d3b35a0a630cece372c2127528e0994386/Ternary-Bonsai-2-27B-PTQ1_0.gguf)
- [PQ2_0 完整权重](https://huggingface.co/prism-ml/Ternary-Bonsai-2-27B-gguf/resolve/b072e1d3b35a0a630cece372c2127528e0994386/Ternary-Bonsai-2-27B-PQ2_0.gguf)

官方模型 LICENSE 为 Apache-2.0，PrismML llama.cpp 保留 llama.cpp 的 MIT 许可；打包 CUDA DLL 另外受 NVIDIA CUDA EULA 管理。部署资产应保留各自许可证，不把 CUDA DLL 误标为模型的 Apache-2.0。[模型 LICENSE](https://huggingface.co/prism-ml/Ternary-Bonsai-2-27B-gguf/blob/b072e1d3b35a0a630cece372c2127528e0994386/LICENSE)、[运行器 LICENSE](https://github.com/PrismML-Eng/llama.cpp/blob/prism-b10743-adfffbe/LICENSE)、[NVIDIA CUDA EULA](https://docs.nvidia.com/cuda/eula/index.html)

## 原作者包和社区 NInfer 路线核实

| 路线 | 实际可得内容及兼容性 | 对本机的判断 |
| --- | --- | --- |
| `Neroued/ninfer` 上游 | 当前源码主线固定 `sm_120a`，官方支持 RTX 5090 与选定 Qwen 制品 | 不能靠改一个启动参数在 sm86 上用；也不是官方 Bonsai 三元发布 |
| `shensanshu/ninfer-ada-ternary` | 原作者三元补丁、pack.py、映射与核验工具；当前 README 声称离线完整包并限 CC8.9/12.0 | 文件清单与 README 不一致，不能当成已取得整套引擎和权重 |
| `Don-Chad/ninfer-3090` | 公开完整 SM86 源码及 Windows releases，主要合格制品是 Qwen groupwise | 存在 Ampere 移植，但不是“任意 Bonsai .ninfer 都能加载”；官方 Qwen3.8 groupwise 本身约16.96GiB，超出本机空闲显存 |
| `wei231/ternary-bonsai-3080-sm86-ninfer` | 真实可得 3 个 exe、8,306,927,628-byte model.ninfer、打包/构建脚本 | 同 sm86 的重要实际经验；完整源码基线与模型身份不足以审计，应进一步核对后再用 |
| `iamwavecut/ninfer-all` + WaveCut v3 | 公开 SM86/SM89/120a 源码、三元转换器、专属 `t2_g128_fp16` 制品及3090参考测量 | 是可行的后续 NInfer 优化路径；不是 PTQ1 dense 包装，Windows 构建环境成本高于官方预编译 |

来源：[NInfer 主线 README](https://github.com/Neroued/ninfer)、[原作者魔搭 repo/files API](https://modelscope.cn/api/v1/models/shensanshu/ninfer-ada-ternary/repo/files?Revision=master&Recursive=true)、[Don-Chad README](https://github.com/Don-Chad/ninfer-3090)、[3080 模型发布](https://huggingface.co/wei231/ternary-bonsai-3080-sm86-ninfer)、[ninfer-all README](https://github.com/iamwavecut/ninfer-all/blob/64492cb14d246fa04a5241c42e771dcfdff40753/README.md)、[WaveCut 模型卡](https://huggingface.co/WaveCut/Ternary-Bonsai-2-27B-NInfer-v3)

原作者仓库实时 API 返回 89 个条目（含目录），文件总计仅 `1395132 bytes`，没有超过 1 MB 的文件；最新提交短号 `6a974530`，题为 `Delete files`。README 却让下载约18.4GiB zip，NOTICE 又写不分发权重。由此只能确认“工具/补丁可得”，不能确认“完整部署 zip 可得”。README 中以 SHA256 标记的示例散列也只有32个十六进制字符，不能作为64位 SHA-256 校验值使用。[文件清单 API](https://modelscope.cn/api/v1/models/shensanshu/ninfer-ada-ternary/repo/files?Revision=master&Recursive=true)、[README](https://modelscope.cn/api/v1/models/shensanshu/ninfer-ada-ternary/repo?Revision=master&FilePath=README.md)、[NOTICE](https://modelscope.cn/api/v1/models/shensanshu/ninfer-ada-ternary/repo?Revision=master&FilePath=NOTICE.md)

原作者补丁 README 指定基座为 `Ambolio/ninfer-4090-windows @ 6eb70a07 / v1.0.8`，而当前 GitHub API 对该仓返回404；另一公开部署工程也注明基座已转私有。仅覆盖这38个改动文件无法从上游当前 master 自动重现原作者树。硬门、Blackwell/FP8替代路径、artifact reader 与 kernel 数值验收须一并处理；这属于中高成本的版本移植，不宜叫低成本“改sm89成sm86”。[原作者补丁说明](https://modelscope.cn/api/v1/models/shensanshu/ninfer-ada-ternary/repo?Revision=master&FilePath=patches%2FREADME-%E6%94%B9%E5%8A%A8%E8%AF%B4%E6%98%8E.md)、[Ambolio API](https://api.github.com/repos/Ambolio/ninfer-4090-windows)、[公开5080部署工程](https://github.com/paicat1/Bonsai-27B-NInfer)

3080 发布确有与模型卡一致的 sm86 构建命令，但该发布没有完整源树。其 `pack_bonsai.py` 将输入 GGUF 写死为 `Ternary-Bonsai-2-27B-Abliterated-PQ2_0-MTP.gguf`（BoldingBuilds路径），与文字“官方Bonsai2”不能直接等同；README 的模型体积文字也与 API 精确文件大小存在差异。没有转换报告证明公开 model.ninfer 是未改的官方 PTQ1 权重，不能为了速度悄悄换成 abliterated 或微调模型。[3080 构建脚本](https://huggingface.co/wei231/ternary-bonsai-3080-sm86-ninfer/blob/664372512f1f13c75a89cf724f62dfb32c3a4cea/build_bonsai.cmd)、[3080 打包源码](https://huggingface.co/wei231/ternary-bonsai-3080-sm86-ninfer/blob/664372512f1f13c75a89cf724f62dfb32c3a4cea/pack_bonsai.py)、[发布精确大小/散列](https://huggingface.co/api/models/wei231/ternary-bonsai-3080-sm86-ninfer?blobs=true)

`ninfer-all` 固定研究提交 `64492cb14d246fa04a5241c42e771dcfdff40753` 的 CMake 正式接受80/86/89/120a并启用 SM8X 兼容路径，三元转换模块公开。WaveCut制品是 v3 方言，使用 PQ2 导入的2-bit三元码、额外 MTP/DFlash2/vision，9,520,051,456 bytes；普通上游引擎不认识该方言。维护者在RTX3090给出了数值/深度/资源测量，本次没有独立复现。其 GitHub `releases/latest` 当前404，所见主要配送路径是源码构建或LinuxDocker；已有CPU编译器不足以完成Windows CUDA构建，须安装CMake、CUDA、MSVC/vcpkg。**采用既有公开内核后，工作重点是构建和验收，预计中等成本；从Neroued主线自己编写Ampere三元路径则是高成本。** 这是工程评估，不是工期承诺。[固定 CMake](https://github.com/iamwavecut/ninfer-all/blob/64492cb14d246fa04a5241c42e771dcfdff40753/CMakeLists.txt)、[固定三元转换器](https://github.com/iamwavecut/ninfer-all/blob/64492cb14d246fa04a5241c42e771dcfdff40753/tools/convert/ternary.py)、[WaveCut 模型组成](https://huggingface.co/WaveCut/Ternary-Bonsai-2-27B-NInfer-v3/blob/main/README.md)、[3090参考测量](https://github.com/iamwavecut/ninfer-all/blob/64492cb14d246fa04a5241c42e771dcfdff40753/docs/performance/reference-2026-09.md)、[release API](https://api.github.com/repos/iamwavecut/ninfer-all/releases/latest)

## 网上经验与可转移范围

- 原作者博客报告 RTX4080SUPER 上的三元移植和质量探针；对应魔搭源码揭示 Hadamard、token布局、GDN value/z置换和融合路径等真实工程工作。它能支持“此移植有代码和核验工具”，不能证明 A4000 达到同样吞吐。B23短链接本次直接读取失败，未将视频宣传作为兼容证据。[原作者博客](https://blog.csdn.net/shensanshu/article/details/166012877)、[原作者补丁包](https://www.modelscope.cn/models/shensanshu/ninfer-ada-ternary)、[用户视频入口](https://b23.tv/Il3RrIa)
- 一位RTX3060 12GB使用者给出 Bonsai2 PQ2、`b10735-842b18804` 的原始 llama-bench 条件和约35.26±1.31tok/s decode；这是同属Ampere消费卡的运行经验，不能换算为本机A4000承诺，也不是PTQ1性能测试。[3060 原始经验帖](https://www.reddit.com/r/LLM/comments/1wpvd50/bonsai_2_27b_on_an_rtx_3060_12gb_3526_toks/)
- 5080部署工程保留日志口径、CUDA调度改动和对宣传峰值证据不足的修正，说明投机接受率与请求内容会改变速度。该工程的Blackwell量化KV与大上下文设置不能直接复制给sm86。[5080工程及日志口径](https://github.com/paicat1/Bonsai-27B-NInfer)

## ConSense API契约与建议启动

PrismML fork 的 `llama-server` 自带 `/v1/models` 与 `/v1/chat/completions`，支持 OpenAI样式 `response_format` 的JSON对象及schema约束。可用 `--alias ternary-bonsai-2-27b` 固定应用模型名。模型本身仍可能产生格式或tool错误；运行器支持schema不等于ConSense已使用schema。[固定版本 server 文档](https://github.com/PrismML-Eng/llama.cpp/blob/prism-b10743-adfffbe/tools/server/README.md)、[官方已知tool/JSON问题](https://huggingface.co/prism-ml/Ternary-Bonsai-2-27B-gguf/blob/b072e1d3b35a0a630cece372c2127528e0994386/KNOWN_ISSUES.md)

本地 ConSense `OpenAiLlmClient` 固定追加 `/v1/...`，所以 base URL 应为 `http://127.0.0.1:8091`，不能带 `/v1`。当前 structured调用只追加JSON提示词，检查single choice、非空content及`finish_reason=stop`，没有发送schema。普通chat不发送max_tokens，应用的numCtx也未传给这个客户端；须在服务器显式设置上下文与合理生成上限。依据：[OpenAiLlmClient 源码](../ai_consense_service/src/main/java/com/consense/ai/OpenAiLlmClient.java)。实际部署profile由主验收流程再检查。

基础JSON验收建议**先在服务端关闭thinking**。模型模板由 `enable_thinking=false` 生成已结束的think片段；fork的 `--reasoning off` 会向模板传递关闭状态。`--reasoning-budget 0` 不宜替代明确关闭；`--reasoning-format none` 会让思考混入content，建议用 `deepseek` 分离思考字段，避免未来重新开启时影响纯JSON解析。若需要思考能力另行比较medium；xhigh默认可能耗完token而没有最终答案，high会触发模板错误。[Qwen3.8官方模板](https://huggingface.co/Qwen/Qwen3.8-27B/blob/main/chat_template.jinja)、[fork参数实现](https://github.com/PrismML-Eng/llama.cpp/blob/prism-b10743-adfffbe/common/arg.cpp)、[fork模板变量接线](https://github.com/PrismML-Eng/llama.cpp/blob/prism-b10743-adfffbe/common/chat.cpp)、[Bonsai2已知推理预算问题](https://huggingface.co/prism-ml/Ternary-Bonsai-2-27B-gguf/blob/b072e1d3b35a0a630cece372c2127528e0994386/KNOWN_ISSUES.md)

启动命令示意（路径由实际下载目录决定；引擎/DLL已校验后执行）：

```powershell
& '<engine-dir>\llama-server.exe' `
  -m '<model-dir>\Ternary-Bonsai-2-27B-PTQ1_0.gguf' `
  --host 127.0.0.1 --port 8091 `
  --alias ternary-bonsai-2-27b `
  -ngl 99 -fa on -c 32768 -np 1 `
  --jinja --reasoning off --reasoning-format deepseek `
  -n 8192
```

先不装vision、不启投机、不申请262K最大上下文。训练上下文上限并非本机当前可承载的默认配置；32K单槽是先验收再扩大预算的保守起点。加载日志需确认CUDA GPU确为A4000、主要层全部offload、无未知格式或CPU意外回退，随后记录真实peak VRAM。

`/v1/embeddings` 路由存在不意味着Bonsai可以充当BGE-M3。server文档要求专用embedding模型与pooling；`--embeddings`还会限制为embedding用途。ConSense默认embed-model=bge-m3，后续长证据检索需要独立真实embedding服务；不能用随机向量或替换model字段伪造成功。[server embedding约束](https://github.com/PrismML-Eng/llama.cpp/blob/prism-b10743-adfffbe/tools/server/README.md)

## 验收边界与下一步

1. 下载固定资产，核对每个文件大小/SHA，保留license、revision、启动参数及日志。
2. 加载32K单槽模型，验证`/health`、`/v1/models`，保存实际CUDA设备与显存日志；原有GPU/WSL任务保持运行。
3. 用独立虚构中文合同片段做普通chat和纯JSON抽取，检查`finish_reason=stop`、字段值、缺失值、引用来源和中文字符；没有真实业务证据时明确这是合成验收，不能代替用户文件的业务验收。
4. 接入ConSense本地OpenAI provider，验证应用的local模型选项、实际HTTP请求与structured解析；记录embedding/OCR/vector哪些尚未配置。
5. 只有小样本正确性与资源预算通过后再适度增加文档长度。记录prompt tokens、completion tokens、TTFT、整请求耗时和peak显存；不引用博客或其他GPU的峰值当成本机成绩。

官方中文与通用benchmark资料没有给出中文建筑合同/条款证据抽取的专门验收结果。因此此模型能使用中文，与能可靠完成ConSense合同业务是两个需分别验证的命题。本研究确认可获取合法完整权重和兼容运行器，未声称业务质量已验收。

## 本机实际执行结果

主部署流程已下载并核对上述 PTQ1 权重、Windows CUDA 12.4 引擎及 DLL 的大小和 SHA-256。`llama-server --list-devices` 实际识别 `CUDA0: NVIDIA RTX A4000`，版本为 build 10743 / commit adfffbe41。以 32K 上下文、单槽、关闭 thinking 和默认输出上限 2048 tokens 启动 `http://127.0.0.1:8091` 后，普通聊天、严格 JSON 和中文合成字段抽取均正常 `finish_reason=stop`。

进一步通过当前编译的真实 `AiGateway → OpenAiLlmClient → 原 HttpSupport/OkHttp → Bonsai` 调用，验证无 grammar / response_format 的应用线协议、真实 strict JSON validator，以及四个中文合成字段精确匹配。中文请求 212 prompt / 62 completion tokens，HTTP 耗时 2.413 秒，服务端 decode 34.34 tokens/s。另一次直接请求 decode 为 35.13 tokens/s。原始请求、响应及 class hash 见 `../.local/state/consense-bonsai-adapter-acceptance.json`；这些仅为短合成样例，不是完整合同质量基准。

加载及短样例后的整卡 `nvidia-smi` 为 used 9455 MiB / free 6715 MiB，包含桌面和其他进程，不将整卡占用全部归为模型。ConSense 实际 Spring local profile 为 openai / ternary-bonsai-2-27b，`llm.available=true`；前端页面显示配置就绪，代理与 H2 API 已验证。OCR、BGE-M3 embeddings、外部检索与 PDF 原生排版仍未验收。当前已经得到可用的官方三元 LLM API，无需为了基础接入自行重写 CUDA 内核。
