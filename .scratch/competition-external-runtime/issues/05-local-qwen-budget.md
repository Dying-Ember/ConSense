# 05: 配置 Qwen 接口并核验兼容性

<!-- competition-frontend-full-acceptance-official-qwen-20261101 -->
**最新验收与 LLM 决定（2026-11-01，按 11-02 用户补充更新）：**赛方现场环境不作为本轮验收依据；本机验证容器与外部文件通信顺畅并符合已确认的比赛运行要求。纯前端以模拟题完成 Drafting；Vetting 使用 2b 业务原件和 2d Checklist，以 2d 既有 Comments 离线对照新结果；Advisory 使用 2e 问题和答案对照并复测 Mermaid 流程图生成、展示及刷新保留。先确保流程畅通和答案齐全，再评估正确率；Drafting 图谱人工核对及其余细节见[票 14](14-local-full-stack-rehearsal.md)。图纸按已确认规则识别并排除。LLM 本机通过外置配置指向千问官方 API、模型 `qwen3.8-27b`；比赛部署通过配置切回自部署 Qwen/vLLM，保持相同模型，不改业务调用链。凭据不得泄露；可用性、实际模型身份与完整结果须实测。本条优先于较早本机仅用替身的历史记录，保留此前失败与证据。


## 2026-11-01 替身接线验收（历史时点）

沿用冻结基准的 Qwen 客户端，通过外置配置连接；本机只使用明确标记的兼容 API 替身检查接线，不新增赛方必须使用的替身中转。赛方通过实际配置接自部署 vLLM，缺少地址时不得静默转外网；真实权重、认证、预算及推理质量按未验记录。已有本机单次替身 HTTP 200 与配置软件检查可复用，不为 demo 建设额外计数或持久化系统。

- [x] 本机已通过真实容器链调用标记的协议替身，返回和合成计数不冒充真实模型。
- [x] 确认当时运行配置指向本机替身；比赛配置的软件检查覆盖外置 vLLM 地址和缺失时明确失败。真实 vLLM 部署另记未验。
- [x] 使用已有接口完成与 demo 相关的代表性请求和限定错误处理；真实 Qwen／vLLM、H800 现场仍单独注明未验证。业务结构化结果和逐段／停止分别由 06、07 处理，不为本票重复建设验收链。
- [x] 已有失败及 CPU 替身进程清理记录保留，调试凭据不写入普通日志。

**2026-11-01 14:25 换版后独立只读核对：**运行 App `db2ae27fb04d…` 使用候选 JAR SHA-256 `3e13085a4de853448c068ea537a2e75217ed4bedf231e3cc143e8d0b92657998`，包内已有 `application-competition.yml`；本机仍启用 `h2,oer,rehearsal-source,app-source`，外置 `app-source` 的 Qwen 地址解析为本机回环，未发现 Qwen 地址环境变量或命令行覆盖。App `/api/system/llm-profiles` 返回 Qwen `configured=true`、模型 `qwen3.8-27b`；这不是 Spring 运行时最终地址的直接读回。当前 Source 配置的业务 profile 明确为 `cpu-protocol-stub`，模型同名、命令指向协议替身；本次 `/v1/models` 无凭据返回 401、有凭据返回 200，`modelReady=false`、`targetAccepted=false`。该只读检查未发模型请求或占用 GPU。

此前 Source→worker→替身的真实容器请求 `63115bea…` 已返回标记的成功响应，历史 Source 无凭据 401 和其他失败保留；新 App 换版后未重复实际聊天，现有代码/配置证据支持接线判断但不能当作新版本端到端请求成功。competition profile 的空地址拒绝与显式地址沿用原客户端已有 4 项软件检查；真实 vLLM、赛方认证与预算仍未验。结合下述同一 App 配置与真实请求的证据，按现行 demo 验收复用已有结果，不将替身当真实模型。

**05 关票依据：**复用 06 的真实 Drafting 发现请求：App 入口带 `X-ConSense-Llm-Profile: qwen`，HTTP 200、业务码 0，并保存、读回草稿 `5005ee98-aed9-465e-9751-9d3cf375c8cb`；请求前后只新增 `business-llm` operation `6893e706b096464b8091bfb6fd298bc8`，其请求含此次合成模板特有文本，响应为标记的协议替身，结果内容 SHA 与本次 fixture 相同，worker 记录 `completed/released_cpu_only`。App、Source 的回执没有贯穿各层的同一个 request ID，关联依据为这一唯一新增 operation、输入文本与输出身份。请求脚本及 HTTP、草稿与 operation 状态原始回执在 `.local/container-rehearsal-20261029/ticket06-dual-synthetic-fixture-20261101-a1/discover_once.py` 和同目录 `discover-receipt-20261101-a1/`；operation 原始请求、响应、状态位于专用 WSL `/srv/consense-rehearsal-20261029/instances/local01/gpu-serial04-r7-20261030/workspace/gpu-operations/6893e706b096464b8091bfb6fd298bc8/`。

新旧 App JAR 实际逐条目比较仅新增未在本机启用的 competition YAML，其余文件内容相同；容器 inspect 的启动命令、entrypoint 和 profiles 相同，环境只变 release 清单 pin，挂载只变 release 两处。当前 Source 业务 profile 与该 operation 记录的 profile 字段逐项相同。因此该真实请求可用于现行 demo 接线验收，不重复发请求；新 App 换版后未重新发送聊天。当前 Source `/v1/models` 的 401 是接口鉴权负例，不代表 Java 所有错误或超时路径已验。competition 配置的空地址与显式 vLLM 地址为软件检查，真实 vLLM、赛方认证、预算、H800 和模型质量仍未验；06 的结构化业务与 07 的页面流式／停止各自继续。

**2026-11-01 真实容器替身请求：**Source→固定 worker→CPU 协议替身 operation `63115beaeabd42509e0547bc68af975b` 返回 HTTP 200，响应明确标记为替身和合成计数，持久响应与请求身份核同；worker 记录 `released_cpu_only`，当次独立读回时 owner 不存在。内容为空 records，token 用量为合成值，不代表真实 Qwen 或业务质量。此前 d91c 的 CUDA 初始化失败和 release_unknown、78e5 的 cgroup 路径误判均保留；两处修复已部署。独立读回见 `.local/container-rehearsal-20261029/ticket05-cpu-chat-6311-independent-readback-20261101.md`。该段为当时记录，后续关票依据见上；现时 GPU owner 以看板和现场为准。

**比赛配置候选：**已准备包含 `application-competition.yml` 的离线 App 候选；4 项配置软件检查覆盖空地址、无云端回退、显式地址和既有客户端。候选当时尚未换版，不能当作当前生效；当前 App 生效地址仍需实际核对。赛方须启用 `competition` profile 并提供自己的 vLLM 基址和认证，详见 `.local/container-rehearsal-20261029/ticket05-competition-build-20261101.md`。

**What to build:** 保留现有 Qwen 客户端；按用户决定本机不放真实 Qwen3.8 27B 权重，使用明确标识的假文件和兼容 API 协议替身，验证外置配置与现有请求链。赛方仍使用选定的 vLLM；真实 Qwen／vLLM 的权重、协议兼容及预算保持未验。

**原关联（按实际需要）:** 04 — 从硬盘执行显卡任务并统一控制占用；本机 CPU 替身及配置可在实际资源允许时独立验证。

**Status:** done — 按本机 demo 范围关票；真实 Qwen／vLLM、H800 和赛方环境未验

**阶段门（2026-10-30 历史记录）：**当前范围以顶部 demo 验收为准；准备和实现按实际资源并行，不新增全票等待或额外证明。

- **准备／实现：**可提前并行准备明确标记的占位、外置配置、现有兼容接口接线与轻量检查；涉及 02／04 持有文件先协调，不另起栈。
- **真实运行：**共用的 Docker 环境、所需固定角色槽及对应身份、资源和释放条件就绪后执行；CPU 协议替身的身份和进程释放须单独记录。
- **关票：**核对本票原始 Blocked by 和下方验收条件及实际证据；具备条件的准备与验证可提前并行，不增加其他票的额外关卡。 Qwen 替身不证明真实 Qwen／vLLM 推理。

**原始验收清单（历史）：**保留供追溯；demo 以顶部当前验收为准。

- [ ] 假文件及替身明确标记、登记身份与作用，不向 vLLM 冒充真实权重，不把替身响应或输入计数声称为真实模型结果。
- [ ] 通过外置配置切换本机替身地址，核对配置来源、覆盖顺序、加载时机、模型名和脱敏有效值；赛方的 vLLM 地址和认证由实际配置提供，思考关闭和业务并发保持原值。
- [ ] 按此票可观察的接口检查聊天、结构化结果、错误、超时及输入计数；Responses 仅在实际调用链使用时检查。替身的计数仅用于协议检查；逐段输出、页面停止及跨层传播由票据 07 完成。
- [ ] 使用真实 Docker 容器链、具名非 root 账户及选定的共用预演环境；登记账户、UID/GID、网络和输入输出身份，不另拉超出五容器的实例。
- [ ] 缺配置、错误地址、模型名或文件身份时明确失败；不静默切外网、关闭已有能力或伪造实际模型就绪。
- [ ] 记录替身调用与配置证据，并单独列出赛方真实 Qwen 权重、vLLM 实际调用、兼容性、认证和上下文／输出预算待验证。只有真实不兼容才最小修复，不预先重写协议。
- [ ] 提供人手检查占位身份、地址、有效配置及赛方真实模型服务状态的步骤；秘密单独受限保存。
