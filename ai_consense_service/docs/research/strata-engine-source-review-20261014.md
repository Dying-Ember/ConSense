# Strata + Flash-Next 在 ConSense 的可行性与 27B 能力对比（2026-10-14）

本记录用于 ConSense 评估 Strata + Qwen3.8-Flash-Next，仅分析上游资料和源码。没有安装 Strata、下载权重、启动引擎、占用 GPU、发送业务资料，亦没有修改 ConSense 的代码、配置或已确认规则。

## 1. 核查对象与证据等级

- 仓库：[Niko1221/Strata](https://github.com/Niko1221/Strata)。默认分支为 `main`；本次 GitHub API 实时读取并固定到 [`296b59d2f73c8f04a805df29c2fa97655adace78`](https://github.com/Niko1221/Strata/commit/296b59d2f73c8f04a805df29c2fa97655adace78)，提交时间为 `2026-10-13T23:51:12Z`（北京时间 2026-10-14 07:51:12）。后面的源码链接均固定到该提交。
- 实时 [`releases/latest`](https://api.github.com/repos/Niko1221/Strata/releases/latest) 返回 `v0.1.44.1`，发布时间 `2026-10-14T00:13:09Z`。这是当时的发布元数据；没有下载其二进制包或核查包内 BUILD.json。
- 以下“源码确认”是静态阅读结果；“作者实测”来自维护者文档，不能当作本机或 H800 的实测；“建议”是本次根据这些证据得出的工程判断。
- 引擎为 MIT，但模型另有授权条件；MIT 没有自动覆盖模型。[LICENSE:1–21](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/LICENSE#L1-L21)、[HOW_IT_WORKS.md:72–77](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/docs/HOW_IT_WORKS.md#L72-L77)。

## 2. 结论

**作为本地低并发、可替换的 LLM 服务候选，方案可行，值得做隔离试验；现有证据不足以确认 ConSense 业务质量、长期稳定性、256K/512K 验收或 H800 部署通过。** Strata 本来就是面向 Qwen3.8-Flash-Next 的引擎，该组合无需另写模型架构适配器。[README.md:12–14](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/README.md#L12-L14)。

最值得关注的阻力是：JSON 输出没有约束解码；消费级显卡路径依赖 CPU/RAM/PCIe/SSD；当前默认某些配置会省略低排名专家计算；长上下文扩展和多用户并发仍有明确限制。不能只根据“OpenAI-compatible”“125B”“512K”或首页 tok/s 决定替换现有模型。

## 3. 硬件与 GPU 构建

| 对象 | 证据 | 结论与限制 |
| --- | --- | --- |
| RTX A4000 16 GB | NVIDIA 官方 CUDA GPU 表将 RTX A4000 列在 compute capability 8.6；上游有同型号作者实测。 | 有直接型号证据，适合本地试验；不能把 RTX 5070/5090 的吞吐照搬。 |
| CUDA 驱动 | `setup.py` 默认 CUDA 13 runtime/cuBLAS，`MIN_DRIVER=580`。 | 父任务实时读到的本机 582.78 高于该阈值；运行兼容性仍需实际启动核验。 |
| H800 / sm90 | CMake 接受显式 >=75 架构；Docker 默认架构列表为 `75;80;86;89;120`，没有 90。 | 不能承诺默认镜像/二进制即用；应针对实际 compute capability 编译，例如 `CUDA_ARCHITECTURES=90`，并核查 BUILD.json 和加载结果。 |
| 单独源码构建 | CMake 未指定架构时默认 `120`。 | 在 A4000/H800 上不能照抄不带架构的 cmake 命令。 |

来源：[NVIDIA CUDA GPU 表](https://developer.nvidia.com/cuda-gpus)、[setup.py:182–196](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/setup.py#L182-L196)、[CMakeLists.txt:160–199](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/CMakeLists.txt#L160-L199)、[Dockerfile:57–59](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/Dockerfile#L57-L59)、[INSTALL.md:95–105](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/docs/INSTALL.md#L95-L105)。

源码 `engine_runs_on` 检查的是准确架构命中，或存在 PTX 且目标架构高于包内最大架构；不能假定包内 sm86 的代码自动覆盖 sm90。[setup.py:1249–1273](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/setup.py#L1249-L1273)。本次没有拿到 H800 实机和发布包内的架构清单，因此 H800 是“源码上可针对性构建”，不是运行通过。

维护者当前给出的直接 A4000 例子为：RTX A4000 16 GB、Xeon E5-2643 v3、62 GB RAM，UD-Q4_K_XL 有 39 GiB 专家在 RAM，剩余从 2.1 GB/s NVMe 读取；作者记录生成 **14–20 tok/s**、4K prompt **134–144 tok/s**。这是磁盘受限的特定配置；上游首页另有“64 GB PC 的 7–8.5 tok/s”概述，不能当作同一条件。优先引用这个具备具体硬件、内存模式和工作负载的区段。[QUANTS.md:134–142](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/docs/QUANTS.md#L134-L142)、[README.md:141–144](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/README.md#L141-L144)。

父任务实时读到本机物理内存 `137112563712 bytes`（约 127.7 GiB）、A4000 总显存 16376 MiB、当时已使用 3269 MiB。**总内存满足配置的量级不等于当前可分配内存，更不等于可以同 OCR/embedding/rerank 并行运行。** 本次没有做负载试验。

## 4. 权重格式、内存及计算机制

Strata 用 GPU 保持 attention/GDN、shared experts、head、MTP、部分 KV 和热门专家缓存；CPU 同时计算未驻留 GPU 的专家。RAM 足够时保持所有专家；RAM 不足时使用映射/预算驻留模式，从 SSD 补取。PLE 大表也依赖 SSD/OS cache。它不是把整个 125B 权重塞入 16 GB VRAM。[HOW_IT_WORKS.md:20–31](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/docs/HOW_IT_WORKS.md#L20-L31)、[generate.cpp:452–460](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/src/program/generate.cpp#L452-L460)、[generate.cpp:928–939](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/src/program/generate.cpp#L928-L939)。

当前实现已经超出最早的 2–3 bit 方案：

| 系列 | 下载大小（作者标注 GB） | 路由专家（GiB） | 取舍 |
| --- | ---: | ---: | --- |
| UD-IQ4_XS | 93.7 | 55.4 | 质量比较接近 IQ3_S，较省 RAM。 |
| UD-Q4_K_XL | 111.3 | 71.7 | 本机值得首先考虑的质量/内存平衡点。 |
| UD-Q5_K_XL | 158.3 | 91.6 | 更接近 Q8，剩余内存需同时覆盖系统、其他服务、KV 和会话。 |
| UD-Q6_K_XL | 169.2 | 101.7 | 本机余量更紧，不适合作为首个试验配置。 |
| Q8_0 | 188.2 | 119.5 | 本机全部专家常驻后几乎没有业务及上下文余量，不能按总 RAM 强行认定适合。 |

来源：[QUANTS.md:14–31](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/docs/QUANTS.md#L14-L31)。上游 GB/GiB 标注混用；实际资源预算应使用文件字节数和进程实测。表中的下载量不是最终磁盘占用：MTP、pack、临时文件另占空间。当前 Unsloth 路径避免重复写一份专家 bin；安装器按所选路径计算新增空间，不能用首页通用“80 GB”预算 Q4/Q5/Q6。[setup.py:6181–6186](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/setup.py#L6181-L6186)、[setup.py:6411–6424](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/setup.py#L6411-L6424)。

Q2_0、IQ2_XS、IQ3_XXS、IQ3_S 也受支持。Coder 删除了每层一半专家，维护者明确提示中文/CJK 和非编码任务较弱，因此不适合作为 ConSense 中文业务首选。[MODELS.md:20–22](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/docs/MODELS.md#L20-L22)、[MODELS.md:94–103](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/docs/MODELS.md#L94-L103)。NVFP4 也已实现，但 RTX 20–40 没有 Blackwell 的 FP4 tensor core 优势，文档记录 prompt 运算比 IQ3_S/IQ4_NL 慢 1.35–1.6 倍，不能因名称含 NVIDIA 就优先用于 A4000。[NVFP4.md:99–105](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/docs/NVFP4.md#L99-L105)。

**需要显式锁定质量策略。** 当前 CUDA solo 路径在 GPU 缓存未接近全驻留、没有 batch/peer、不是 NVFP4 等条件下默认 `STRATA_ROUTE_TAIL_SKIP=7`：部分低排名且缓存未命中的专家既不复制也不计算。它会改变答案；并非只有无损缓存优化。源码条件见 [generate.cpp:8618–8664](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/src/program/generate.cpp#L8618-L8664)。作者 Q4 比较中，对 Q8 的 KL 从 0.056 增至 0.086（全专家 RAM），A4000 budget 模式从 0.053 增至 0.115。[QUANTS.md:158–162](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/docs/QUANTS.md#L158-L162)。初次严谨业务比对建议显式设 `STRATA_ROUTE_TAIL_SKIP=0`，再独立评估是否值得开启。

维护者的 quant 质量表是 4,995 teacher-forced token 位置与 Q8 比较：Q4 93.09% top-1 一致、Q5 94.55%、Q6 94.61%。这些数字衡量与 Q8 的分布接近程度，**不是合同审查准确率、中文事实正确率或引用可核验率**。[QUANTS.md:56–84](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/docs/QUANTS.md#L56-L84)。

## 5. 256K、512K 与吞吐证据

- Strata 文档将 **262,144 positions** 视为训练范围；超过它需要显式 RoPE 扩展。512K 对应 YaRN factor 2，属于实验功能，不能写成原生 512K 保真。[DETAILS.md:1402–1437](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/docs/DETAILS.md#L1402-L1437)。
- 作者扩展证据来自一台 RTX 5080、一个 IQ3_S 配置：293K 的 NLL 比较；421K/714K 实际文档问答各 8/8；一次 1M 完整读入。维护者自己说明仍然实验性。针测试成功或完整读入不等于复杂引用、跨文档矛盾审查通过。[DETAILS.md:1410–1416](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/docs/DETAILS.md#L1410-L1416)。
- 默认 setup 从 64K 起可把大部分 KV 放 RAM，文档给出 int8 约 13.7 KB/上下文 token。更长上下文及更多 slot 都占内存；缩减 VRAM 的驻留 KV 只是换成 RAM/传输成本。[DETAILS.md:79–94](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/docs/DETAILS.md#L79-L94)。
- 上下文是 prompt + 输出 + 8 token 余量，不是可全部塞满的输入窗口。[server.py:216](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/serve/server.py#L216)。
- 32K、128K、262K 吞吐表是不同硬件/quant/引擎版本的作者测试。不能把首页 RTX 5070 短聊天的 tok/s 解释为 A4000 256K 的端到端速度。[DETAILS.md:16–94](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/docs/DETAILS.md#L16-L94)。

32K/64K 只能用于协议和资源 smoke；随后必须验证 ConSense 已确认的至少 262,144 总窗口，优选 524,288。512K 需要单独的扩窗和质量验证，短窗通过不能作为本项目主模型验收。

## 6. OpenAI API、JSON、thinking 与并发

**协议层可接。** 提供 `/v1/chat/completions`、`/v1/responses`、`/v1/messages`，并有 stream/non-stream 路径和 metadata/health。最终 ConSense 兼容性仍取决于其使用的路由、请求字段和错误处理，不能仅凭兼容标签验收。[DETAILS.md:685–737](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/docs/DETAILS.md#L685-L737)。

**JSON 必须区分请求支持与生成保证。** `response_format` 接受 `json_object`/`json_schema`，但引擎没有 grammar decoder：它只增加提示词，生成结束后校验。`jsonschema` 是可选依赖，setup 不会安装；没有该包时即使请求 `strict:true` 也只检查根对象，无法保证字段/schema。根 array、远程 schema `$ref` 被拒绝。[structured.py:1–8](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/serve/structured.py#L1-L8)、[structured.py:83–151](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/serve/structured.py#L83-L151)、[DETAILS.md:1985–2005](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/docs/DETAILS.md#L1985-L2005)。

JSON 格式失败、重复 key、非有限数、schema 失败、输出截断会以非流式 502 `structured_output_failed` 返回；非法请求 schema 为 400。一次请求只生成一次，无暗中格式重试。结构化 SSE 会缓冲整个答案，验证成功才放内容，期间只有 keep-alive；所以首个可见内容时间约为完整生成时间。Chat Completions 路径 JSON + tools/MCP 明确不支持。[structured.py:192–218](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/serve/structured.py#L192-L218)、[server.py:5807–5867](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/serve/server.py#L5807-L5867)。

Thinking 支持 `reasoning_effort=none|low|medium|high`，也接受 `chat_template_kwargs.enable_thinking=false`。未设置时默认 high，模板内部映射为 xhigh；reasoning 与 content 分开。业务调用应显式选择并测量，而不依赖默认。可选硬 thinking budget 会主动插入收束文本关闭思考，因此其质量也需测试。[DETAILS.md:751–776](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/docs/DETAILS.md#L751-L776)、[chat_template.jinja:57–70](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/serve/chat_template.jinja#L57-L70)。

默认一次一个请求，其余排队；`parallel=N` 分配独立会话 slot，增加 VRAM/RAM。维护者在单张 12 GB 卡的 HTTP 测量中，parallel=2/4 的某些条件使单请求速率下降约 9–25%。批处理还有限制：默认 batch window 无 MTP drafts；repetition/frequency/presence penalties 在 batch window 不应用；新 prompt admission 串行，长 prompt 仅在 chunk 边界让出，图片不能这样让出。[BATCHING.md:3–19](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/docs/BATCHING.md#L3-L19)、[BATCHING.md:210–222](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/docs/BATCHING.md#L210-L222)、[BATCHING.md:224–239](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/docs/BATCHING.md#L224-L239)。

有客户端断开/取消路径；batch 使用 `BSTOP`，在下一个 window 停止；prompt cooperative preemption 在 chunk 边界进行。因此有取消能力，但没有本次实测的毫秒级取消 SLA。源码同时有静默 watchdog，默认 300 秒，prompt 读取另有 allowance；不能把 watchdog 当作业务请求总超时。[BATCHING.md:126–142](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/docs/BATCHING.md#L126-L142)、[BATCHING.md:311–318](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/docs/BATCHING.md#L311-L318)、[server.py:221–233](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/serve/server.py#L221-L233)。

## 7. 安装、离线与运行边界

- 默认 START-HERE/setup 会安装依赖、下载模型并启动服务；本任务仅分析，不应直接执行这一入口。首次启动可能载入/锁定数十 GB RAM，维护者提示机器可能 1–3 分钟缓慢或无响应。[README.md:91–117](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/README.md#L91-L117)。`--no-start` 可以只安装，但安装仍然是下载和系统资源变更，不是只读检查。[setup.py:5771](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/setup.py#L5771)。
- 可以预先准备模型目录 `--gguf-dir`、本地引擎 `--prebuilt`，但源码构建还会取 llama.cpp；Python、CUDA runtime、MTP、tokenizer/pack 都需要形成完整离线物料，不能只复制 GGUF 宣称离线可用。[INSTALL.md:203–209](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/docs/INSTALL.md#L203-L209)、[INSTALL.md:278–295](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/docs/INSTALL.md#L278-L295)。
- 引擎下载先尝试 checkout 对应 tag，失败可回退 latest。下载包会尝试 GitHub-native size/SHA-256 校验，但拿不到 digest（离线、限流或镜像）时警告后继续安装；因此受控环境需自行固定并验证 commit、release、依赖及文件 digest，不能把该安装器当成强制校验门禁。[setup.py:170–184](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/setup.py#L170-L184)、[setup.py:3317–3378](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/setup.py#L3317-L3378)。
- HF mirror 保持 pin；ModelScope 模式按当前仓库文件下载，hash 是同一上游自报，维护者说明无 pinned revision。这会影响可重现性。[INSTALL.md:285–295](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/docs/INSTALL.md#L285-L295)。
- 裸机默认 loopback；Docker 默认 `0.0.0.0:8080`，并且 `--gpus all` 会使用所有可用卡，必须明确 GPU、监听地址和 API key，才适合与其他服务共存。[INSTALL.md:124–136](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/docs/INSTALL.md#L124-L136)。
- 推理可以本地运行，但如果配置 MCP，模型能以本机用户权限调用工具；如果请求传远程图片 URL，server 有下载代码。离线试验应禁用未需要的 vision/MCP，使用实际断网验证，不能把首页“Nothing leaves your PC”当作完整安全证明。[DETAILS.md:1392–1400](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/docs/DETAILS.md#L1392-L1400)、[server.py:3867–3872](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/serve/server.py#L3867-L3872)。

最小验证方案：固定上述源码/二进制/模型 revision；A4000 单独端口、单并发、vision/MCP 关闭，以 32K/64K 做协议 smoke；优先完整专家的 UD-Q4_K_XL，并显式关闭 route-tail skip；依次确认非流式请求、真实 schema 校验、截断/502/超时/取消，再用 ConSense 同一批封存输入比较可核验业务结果、首 token、总时长和资源峰值。主模型至少 256K、优选 512K 的总窗口要求继续执行，不能被 smoke 降低。这里只提出验证方案，没有执行这些运行操作。

## 8. Flash-Next 与 Qwen3.8-27B 的纸面能力

**官方同表支持 Flash-Next 整体更强，专业任务差距较大；独立抽取评测没有显示它在长文抽取上全面超过 27B。** 这里的 27B 指官方 Qwen3.8-27B，不能把它等同于本机先前失败的三进制 Bonsai。以下数字本轮重新读取了第一方来源。

| 指标 | Qwen3.8-27B | Flash-Next | Flash − 27B（分） |
| --- | ---: | ---: | ---: |
| JobBench 专业工作任务 | 33.4 | 55.7 | +22.3 |
| CoWorkBench 长程办公 | 70.7 | 73.9 | +3.2 |
| Agents' Last Exam Score | 42.9 | 51.2 | +8.3 |
| HLE 综合高难推理 | 30.8 | 35.9 | +5.1 |
| GPQA Diamond 科学推理 | 89.2 | 91.7 | +2.5 |
| IFBench 指令遵从 | 79.5 | 81.3 | +1.8 |
| DeepSWE 1.1 代码 agent | 42.2 | 58.7 | +16.5 |
| SWE-bench Pro | 61.7 | 62.5 | +0.8 |

全部原始分数来自 [Qwen 官方 Flash-Next 固定模型卡的同一 Language 表](https://huggingface.co/Qwen/Qwen3.8-Flash-Next/blob/de4b8e4d43b917e7706784d8bb445c9af86a3540/README.md)。差值为表内直接相减，不是不同任务相加的综合分，也不代表统计显著性。官方没有逐项声明权重精度，不自行称为全部同 BF16 实测。CoWork 是内部 benchmark；DeepSWE 取两种 harness 的最高结果；SWE Pro 修正了问题任务并重评 baselines。它们给出专业任务研究信号，不能当作合同审查、忠实引用或遗漏率验收。

| ExtractBench FP8 pipeline | Qwen3.8-27B | Flash-Next | Flash − 27B（分） |
| --- | ---: | ---: | ---: |
| Overall value F1 | 89.75 | 89.88 | +0.13 |
| Long value F1 | 38.45 | 37.74 | −0.71 |
| Long value precision | 92.29 | 88.68 | −3.61 |
| Long value recall | 33.00 | 33.69 | +0.69 |

这四行逐项来自 [ExtractBench 固定 leaderboard.csv](https://raw.githubusercontent.com/run-llama/ExtractBench/c972087fe7a5132132f7230d1bdb841d3fbf07ed/leaderboard.csv) 第 43、44 行；FP8 pipeline 身份见 [同提交 pipelines.md](https://github.com/run-llama/ExtractBench/blob/c972087fe7a5132132f7230d1bdb841d3fbf07ed/docs/pipelines.md)。Long 指超过 50 页，不是 256K/512K token。文档平均和当前版本身份沿用 [既有评测审计](./llm-independent-task-evaluations-20261012.md)，不把该提交表改称所有历史模型已在最新数据完整重测。两者长文 recall 都低，不能从总 F1 推出长清单完整。

对 ConSense 的判断：Drafting 的长清单抽取没有足够纸面理由承诺 Flash 大幅领先；复杂 Vetting/Advisory 值得优先挑战 Flash 的能力上限，但缺少同源、完整合同审查及引用/缺证处理的直接可比成绩。官卡分数不是 Strata GGUF 分数，FP8 pipeline 分数也不能给 Q4/Q6 或 IQ3 直接继承。

## 9. 本机和单 H800 的实际选择

本轮 Win32 实时观察：总物理 RAM 为 137,112,563,712 bytes（127.7 GiB），当时 OS FreePhysicalMemory 为 77,036,024 KiB（73.47 GiB）。这是瞬时观察，不是已经为模型预留的预算。Q4 routed experts 本身约 71.7 GiB，再加 KV、锁页、服务和系统余量，不能声称现在全部专家都能放入空闲 RAM；本轮没有停止或挤占现有活动进程。

| 环境 | 本次建议 | 仍需确认 |
| --- | --- | --- |
| 本机 A4000 16GB / 约 128GiB RAM | 完整专家 Q4 作为第一份较高精度试验候选；按实际可用 RAM 选择全驻留或预算模式，必要时用 IQ3_S 做单独对照 | 首次加载峰值、锁页上限、CPU/RAM 带宽、SSD、256K/512K 首 token 和总时长；不能用总 RAM 判当前可共驻 |
| 1×H800 80GB / 250GB RAM | 容量上可以试 Q5/Q6，必要时以 Q8 做同引擎质量参照；没有必要仅为了显存而先限于 2bit | sm90 构建、实际 CPU/PCIe 带宽、峰值、长窗、完整业务与离线恢复 |

Unsloth 固定仓库 [766911a6…](https://huggingface.co/unsloth/Qwen3.8-Flash-Next-GGUF/tree/766911a6b7369840a91dbcd95f9f997acaab6cd6) 内各格式 GGUF 文件大小的 API 求和为：Q4 111,334,654,784 bytes（103.688 GiB）、Q5 158,286,406,650（147.416 GiB）、Q6 169,165,382,688（157.548 GiB）、Q8 188,225,033,248（175.298 GiB）。下载体积包括可留 SSD 的 PLE 大表，不等于 RAM 或 VRAM 常驻量，也不包括额外 pack/MTP/临时空间。当前固定 Strata 文档注明其较早 pin 与该 revision 相关文件逐字节相同；运行前仍应固定 manifest 并核 hash。

80GB VRAM + 250GB RAM 让 Flash 的混合驻留成为现实候选；它没有把官方完整 FP8 变为 80GB 内可全驻留的模型。Strata 是 GGUF 专用路径，不直接复用官方 safetensors FP8 的运行成绩。Q8 是同引擎的量化参考，也不能称为未量化原始模型。

Flash 使用 [Qwen Community License 1.0](https://huggingface.co/Qwen/Qwen3.8-Flash-Next/blob/de4b8e4d43b917e7706784d8bb445c9af86a3540/LICENSE)，与 Strata 的 MIT 分开；对外商业 MaaS/AI Work Assistant 涉及额外条款。第三方卡片的 Apache 标签不能覆盖基座许可。这里记录上游条款身份，未完成具体产品使用方式的许可判定。

## 10. ConSense 最小接入和当前决策

本地代码已经有 `provider=openai` 的非流式 Chat adapter，POST `{baseUrl}/v1/chat/completions`，因此不需要新增 SDK 或重写三个模块。`baseUrl` 必须为服务根地址，不再次带 `/v1`。[OpenAiLlmClient.java:43–80](../../src/main/java/com/consense/ai/OpenAiLlmClient.java#L43-L80)、[AiConfig.java](../../src/main/java/com/consense/config/AiConfig.java)。

最小完整范围包括：

1. **Chat 与 embedding 分开。** 现有 `AiGateway.embed` 还会调用部署 `LlmClient.embed`；直接把整个部署根地址改成 Strata，会让 `/v1/embeddings` 也指向 Strata。BGE/OER 的既有入口继续承担 embedding/rerank，不能把换 chat endpoint 当成所有 AI 调用已接通。[AiGateway.java:159](../../src/main/java/com/consense/ai/AiGateway.java#L159)、[OpenAiLlmClient.java:148](../../src/main/java/com/consense/ai/OpenAiLlmClient.java#L148)。
2. **结构化结果仍在应用侧严格验证。** 当前 adapter 仅把 schema 放 system，没有发送 `response_format`；不能指望 Strata 新增 hard grammar。严格处理非 stop、截断、空 final 和 schema 失败，不把它们当作“零发现”。[OpenAiLlmClient.java](../../src/main/java/com/consense/ai/OpenAiLlmClient.java)、[StructuredJsonValidator.java](../../src/main/java/com/consense/ai/StructuredJsonValidator.java)。
3. **绑定服务端真实上下文和 Qwen 模板。** Java 的 `numCtx` 不会进入 OpenAI 请求体；Strata 要独立配置 262,144 或 524,288，按同 tokenizer/template 给输出和 thinking 留空间。现有 H800 yml 仍是历史 131,072 总窗，不能直接当新版配置。[VettingInputBudget.java](../../src/main/java/com/consense/service/vetting/VettingInputBudget.java)、[application-vetting-h800.yml](../../src/main/resources/application-vetting-h800.yml)。
4. **不复用 M3 专用 Responses 限制为通用适配。** Vetting/Advisory 的 Responses 路线存在 MiniMax 模型、host 和计数接口约束；最小方案先用已有 Chat adapter。当前字符串 content 也不会因 Strata 支持 vision 就自动把原图传给模型。[VettingResponsesTransport.java](../../src/main/java/com/consense/service/vetting/VettingResponsesTransport.java)、[AdviceResponsesTransport.java](../../src/main/java/com/consense/service/advice/AdviceResponsesTransport.java)、[LlmClient.java](../../src/main/java/com/consense/ai/LlmClient.java)。
5. **阶段式 GPU 所有权保持。** OCR/embedding/rerank 与业务 LLM 依次执行，按整批释放和恢复；Strata 单请求队列不管理其他进程或 K8s Pod 的 GPU 所有权。默认单并发适合作为首个资源/质量基线。[既有部署决策](../delivery-stack-decision-20261012.md)。

**最终研究建议：Strata 将 Flash 从“官方 FP8 单卡资源难闭合”推进为“可在本机做有意义试验、可针对 H800 编译验证”的候选；没有证明它已经比 27B 更适合 ConSense。** 质量上优先比较 Flash 的完整证据理解与问题发现上限，资源上选 Q4 本机/Q5–Q6 目标候选，再决定是否值得其额外运行复杂度。27B 官方 FP8 仍是现行文档的第一顺位验证建议，Flash 是复杂任务挑战者；本研究没有作正式采用或改变实施顺序。[已确认规则](../oer-scope-and-rules.md)、[当前交接](../HANDOFF-ocr-embedding-rerank-20261008.md)。图纸文件按既有身份路由排除；来源解析质量、RAG 和模型业务质量分别评估，Strata 不修复前序解析遗漏。

## 11. 本机上下文与速度预算（同日后续只读评估）

用户进一步要求估计本机部署后的上下文和 token/s。本节更新瞬时资源与预测，不覆盖前面的历史观察，也没有执行模型、带宽或压力测试。原始只读库存和计算常数保存在忽略目录 [.local/state/strata-local-estimate-20261014/hardware-snapshot.json](../../../.local/state/strata-local-estimate-20261014/hardware-snapshot.json)。

本轮实际核到 i9-12900（16 核/24 线程），四条 32 GiB DDR5；WMI ConfiguredClockSpeed 为 3600，Speed 为 4800，前者是当前 SMBIOS 配置值而非带宽实测。OS 可见 127.70 GiB，当时可用 72.61 GiB。A4000 16376 MiB 中已用 3349 MiB，空余 12821 MiB（12.52 GiB）。PCIe 最大为 Gen4 x16；空闲时 Gen1 是瞬时读数，不能据此认定推理链路只有 Gen1。D 盘为 KINGSTON NVMe，约 597.27 GiB 空余；E 盘为 HGST SATA HDD。

### 11.1 上下文容量

setup 的 INT8 KV 保守规划公式是 `context × 13 × 1056 bytes`，含 12 个 QSA 层和一个 MTP 层。原生 Windows 从 64K 起可将完整 KV 放 host RAM，仅保 32K resident window（约 0.419 GiB）在 GPU；另算模型 dense、MTP、indexer/DeltaNet、prompt buffer 和会话检查点。[setup.py:4056–4069](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/setup.py#L4056-L4069)、[setup.py:6605–6621](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/setup.py#L6605-L6621)。

| 总窗口 | INT8 KV | IQ3_S 全专家 + KV | 从当前 free 扣除后剩余（仍需覆盖运行开销） |
| --- | ---: | ---: | ---: |
| 131,072 | 1.676 GiB | 48.51 GiB | 24.10 GiB |
| 262,144 | 3.352 GiB | 50.19 GiB | 22.42 GiB |
| 524,288 | 6.703 GiB | 53.54 GiB | 19.07 GiB |

IQ3_S 专家精确字节为 50,292,326,400（46.838 GiB），见 [相近平台的 resident pack 记录](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/bench/results/2026-10-09-community-rtx-3060-0141/README.md)。这些是增量规划下限，不含所有启动和运行分配。256K 有合理容量余量；512K 也不是首先被主 KV 容量挡住，但必须 YaRN factor 2，仍是实验扩窗，不能承诺长文理解正确。

Q4 routed experts 为 71.729 GiB；加 256K/512K KV 分别约 75.08/78.43 GiB，已经高于当前 72.61 GiB free。若另外留 16 GiB 运行余量，需约 91.08/94.43 GiB free，即先腾约 18.47/21.82 GiB；实际按约 20–25 GiB 准备更稳妥。本轮没有释放任何活动进程。installer 用总 RAM 选模式，不能用其推荐证明当前 free 已够。Windows 注册整段 pinned RAM 失败后会分片注册并保留 CPU 侧数据，约半 RAM 映射限制不是硬性“不能跑”，但会影响传输。[setup.py:582](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/setup.py#L582)、[setup.py:1886](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/setup.py#L1886)、[pinned.cu:410–497](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/src/core/pinned.cu#L410-L497)。

WSL 被该版本显式排除在 KV streaming 路径外；同样 512K 的完整 KV 会占约 6.7 GiB 显存，在当前空余 12.52 GiB 的条件下会严重挤压专家缓存和缓冲。因此本节推荐以 Windows 原生为准，不给 WSL 512K 相同承诺。

### 11.2 吞吐与延迟：预测与实测分开

**本机工程预估：IQ3_S 生成约 25–40 token/s，长文首次读取约 600–1,100 token/s。** 假设原生 Windows、单请求、INT8 KV streaming、MTP 开启、无 vision、route tail skip 0，全部 IQ3_S 专家留 RAM、模型放 NVMe、不触发分页。本机当前其他显存占用保留，实际分配前仍需测峰值。此范围主要用于 32K–256K 的开发预算，不是本机跑分或对每一长度的保证；512K 不给窄吞吐承诺。

两类直接锚点支持这个数量级：

- [i7-12700 + RTX3060 12GB 的 Windows 原始 Flash-Next 实测](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/bench/results/2026-10-09-community-rtx-3060-0141/README.md)：IQ3_S 在 32K/128K/260K 的 PP 为 820/803.2/739.3，TG 为 20.2/18.9/18.9。它仅以 20 GiB RAM 缓存专家，余下从 NVMe 取；本机有容量让该模型全部专家留 RAM，但不能据此按固定倍率放大。版本 0.1.41，尚未默认启用 route-tail skip，thinking off，MTP on。该报告观测到热降频并明确保留其他后台负载。
- [A4000 全专家 RAM 的作者实测](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/docs/DETAILS.md#L1755-L1768)：IQ3_XXS 按 12GB 卡预算约 39.3–41.5 TG，按 16GB 卡约 48.6–52.6；Q2_0 约 51.3–54.2 / 62.5–66.2。这里量化、版本与近似策略不同，不能直接赋给 IQ3_S skip0。本机 DDR5 配置速率与 AVX2 路径也不支持照搬首页 RTX5070 的速度。

按本机预测 PP 范围计算，完全未缓存的新输入只计读取阶段：

| 实际新输入 | 估计读取时间 |
| --- | ---: |
| 32,768 tokens | 30–55 秒 |
| 131,072 | 2.0–3.6 分钟 |
| 约 250K–256K | 约 4–7.3 分钟，仍需给输出留窗口 |
| 接近 512K、仍为输出留位的长输入 | 约 8–15 分钟仅为线性外推，不是扩窗速度保证 |

完整 262,144/524,288 是总窗口，不能全作为输入。4,096 个生成 token 按 25–40 TG 约 1.7–2.7 分钟；若开启 thinking，思考 token 也需要时间。复用同一前缀可以减少新输入读取，但不能把缓存命中速度当首个长文请求。结构化 SSE 还要等待完整 JSON 校验才放内容，界面首字可能晚于 prefill 结束。

本机第一份运行候选因此收敛为 **IQ3_S / 总窗 262,144 / 单请求 / 原生 Windows**；512K 作为独立扩窗分支。前文 Q4 仍是质量对照候选，但按当前 free 资源不宜作为首次共存部署的默认档。Q4 预算模式在别的 A4000 上有 14–20 TG 的记录，不过缺乏本机对应配置与 skip0 的完整证据，本轮不给窄速度承诺。

## 12. 官方 FP8 在本机的性能估算边界（同日后续核查）

用户继续询问官方 FP8 性能。本轮再次只读查询：OS free 为 73.246 GiB；A4000 free 为 12773 MiB（12.474 GiB），合计 85.720 GiB。这是两个不同存储域的容量上限相加，不是一个可任意合并的分配池。

**现有 Strata + 官方 FP8 没有可直接运行的配方，不能把 IQ3_S 的 25–40 TG 按位数线性换算成 FP8 数字。** 固定 Strata 的 routed-expert GPU 内核白名单不含 FP8 E4M3/E5M2，启动会验证实际专家格式；`ple-fp8.gguf` 支持的是 lookup table，不能据此推断支持 FP8 routed experts。Q8_0 也是不同的整数块量化，不等同官方 FP8。[iq_kernels.cu:644–654](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/src/kernels/cuda/iq_kernels.cu#L644-L654)、[native_expert_supported](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/src/kernels/cuda/iq_kernels.cu#L2969-L2972)、[generate.cpp:2812–2823](https://github.com/Niko1221/Strata/blob/296b59d2f73c8f04a805df29c2fa97655adace78/src/program/generate.cpp#L2812-L2823)。

官方 FP8 文件总量约 172.8 GiB，其中 PLE 约 47.7 GiB，除 PLE 后仍约 125.1 GiB；主 routed-expert FP8 数值本体就约 112.5 GiB（未另加 scales）。当前 RAM+VRAM 空余合计约 85.7 GiB，连 routed experts 本体都不能全驻，不能按全专家 RAM 模型估速。文件总大小与 index.metadata.total_size 的 tensor 字节有封装差异；本轮重新读到固定 index 的 tensor 总量为 185,502,232,570 bytes（172.7624 GiB）。[固定 FP8 index](https://huggingface.co/Qwen/Qwen3.8-Flash-Next-FP8/resolve/236dfdf285828023ca3bcd3f37366c58a3469b13/model.safetensors.index.json)、[官方维护 recipe](https://recipes.vllm.ai/Qwen/Qwen3.8-Flash-Next)。

标准 vLLM 的 PLE CPU offload 将表放进 pinned host RAM；不是把 47.7 GiB 自动改为只占 SSD。即使假设清空其他应用，约 127.7 GiB host + 16 GiB GPU 也不足以同时容纳全部约 172.8 GiB 权重和运行开销。若另外实现 PLE SSD lookup，除表后的 125.1 GiB 在全空闲机器上只有紧边界的理论空间，不能断言所有定制方案永远不能跑；但当前标准配置没有这条闭合路线。[固定 vLLM Engram 配置](https://github.com/vllm-project/vllm/blob/d1b4028d7e/vllm/config/engram.py#L44)、[实际 pinned CPU allocation](https://github.com/vllm-project/vllm/blob/d1b4028d7e/vllm/models/qwen4_exp/nvidia/ngram_embedding.py#L441)。

A4000 属 Ampere，不能获得原生 FP8 W8A8 Tensor Core 吞吐。vLLM 对部分 FP8 格式/模型可用 Marlin 的 W8A16 路径，这不代表该 Flash-Next 所有算子与 PLE 在 sm86 已验证。[vLLM FP8 文档](https://docs.vllm.ai/en/latest/features/quantization/llm_compressor/fp8/)。通用 cpu-offload 是权重经互连供 GPU 使用，也不同于 Strata 的 CPU 专家并算。[offload 参数语义](https://docs.vllm.ai/en/latest/configuration/engine_args/#offloadconfig)。

因此本机官方 FP8 的现成 Strata 路线记“不支持，t/s 不适用”；标准其他引擎路线先被当前容量与兼容性门挡住，暂无可信吞吐范围。磁盘按需加载/分页是另一方案，token/s 由实际每窗口读取字节、缓存命中、FP8执行内核和 MTP 接受率共同决定，不能无证据填写 1–3 或 10–20 TG，也不把 Strata INT8 KV 的预算直接给 vLLM 继承。本轮未执行安装、加载或任何性能测试。

未检查：本机/H800 实际安装运行、完整离线闭包、ConSense 端到端业务质量和生产并发稳定性。风险：静态预算及作者基准不能替代这些验收，默认质量近似、分页和显存争抢尤其需要控制。

## 13. 同日本机实际部署后续

用户随后明确授权IQ3部署测试并要求继续，已完成安装／真实生成／约250K合成上下文及512token流式测速。实际采用28GiB CPU专家预算以避Windows提交额度；短／长512样本为24.9／28.5 TG，250K首字约217.4秒，取代前述纯预测作为本机证据。测试后已安全停止Strata，让出OER显存，保留按需起停。完整结果、原始证据和未验范围见[本机部署测试](./strata-iq3s-local-deployment-test-20261014.md)。前文“未安装／未授权／未运行”按当时研究阶段读取；本次独立测试不等于正式采用或业务／H800验收。
