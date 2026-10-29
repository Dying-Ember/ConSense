# 本机 Docker 最小环境操作说明（2026-10-29）

票据 01 **未关闭**。已验证的是 Windows／WSL 本机的隔离 Docker 最小试验，不是业务栈、五容器完整生命周期或赛方平台验收。本说明仅整理已有回执、脚本和 inspect；编写时没有启动、停止或重跑服务／容器。

执行范围以 [OER 规则](../oer-scope-and-rules.md) 和[实施交接](../HANDOFF-ocr-embedding-rerank-20261008.md)为准。实际业务部署仍按[容器实施说明](../specs/competition-container-external-runtime-20261029.md)推进。本机 Qwen 使用明确标识的假权重占位和协议替身；占位不能证明 Qwen／vLLM 推理。真实 OCR、embedding、rerank 容器加载与推理均未验收。

## 证据位置和使用边界

本机忽略目录（不随仓库发布）为 `.local/container-rehearsal-20261029/isolated-docker-01/`，下文简称 `E`。维护人员应在本机查看：

| 事实 | 可复核来源 |
| --- | --- |
| daemon、镜像、挂载、GPU、保护检查及失败 | `E/receipt.md`；安装、镜像、执行与 postcheck 日志 |
| 挂载和实际账户 | `E/test-mounts.sh`、`E/receipts/*.inspect.json`、`E/inspect-summary.json` |
| 权限收紧和 GPU 查询 | `E/test-unprivileged-gpu.sh`、对应 inspect 与执行日志 |
| host 网络交换与停止 | `E/host-network-test/receipt.md`、`run-check.sh`、`server.py`、`client.py`、inspect、`stop-owned-daemon.sh` |
| 文件完整性 | 两级 `receipt-hashes.json`；实际文件仍需逐项比对 |

公开文档不复制私有 PID、业务原件、凭据、测试 nonce 或原始日志内容。回执中的时间点状态不自动代表当前状态。脚本含写入、创建和删除操作，阅读脚本不代表授权重新执行。

## 必须保护的现有环境

保留既有 `Ubuntu-24.04`、其 PostgreSQL、独立 `blz-automation-readback.service`，以及 Windows 既有 Java、Native／OER、SL、Vite 和独立 Drafting 服务。Docker 回执记录了八个 Windows 监听端口：5187、18183、18185、18186、18189、18198、18199、18201，postcheck 与先前记录的进程身份一致；该端口快照本身不证明每个业务服务健康，也不足以单独推断所有端口的业务归属。

操作前应读取实际进程身份、监听及任务目录归属，再与本机保留证据对照；禁止按历史 PID 或仅凭端口结束进程。不得运行全局 `wsl --shutdown`、重启既有 Ubuntu、全局停止 Docker／containerd、清空防火墙、改路由或执行 prune。不得删除或注销试验 WSL、data-root、模型、原件和证据。

前后快照显示 PostgreSQL active、路由未变；原 Ubuntu 缺少 `iptables-save`，因此没有完整共享防火墙前后差异证明。独立 readback 服务曾显示 activating，回执未作因果归属；不能写成所有服务持续健康。

## 独立 WSL 与 daemon 生命周期

已使用专用发行版 `ConSense-Rehearsal-20261029`。Ubuntu `docker.io` 29.1.3 及依赖只安装在该发行版，安装时用 `policy-rc.d` 阻止服务自动启动。发行版与既有 WSL **共享网络命名空间**，发行版独立不等于网络隔离。

| daemon 属性 | 已记录值／状态 |
| --- | --- |
| socket | `unix:///run/consense-docker/docker.sock` |
| data-root | `/srv/consense-docker/data` |
| exec-root | `/run/consense-docker/exec` |
| PID 文件 | `/run/consense-docker/docker.pid` |
| 运行身份 | daemon 为 root；容器进程另行以具名非 root 运行 |
| 网络功能 | 禁用 iptables、ip6tables、masquerade、bridge、userland proxy |
| 生命周期 | 前台会话绑定；首次 detached 启动未持久化且无诊断输出；跨会话／WSL 终止恢复未验证 |

host 网络回执最后记录：确认无任务容器并核实 daemon 身份后发送 SIGTERM，前台退出码 0，日志出现 `Daemon shutdown complete`。这不是 systemd 管理的 Docker 服务；后续使用必须显式启动。

### 启动前检查与待验证项

Windows 维护终端可用只读 `wsl --list --verbose` 核对发行版名称。不要用进入已停止发行版的命令冒充只读状态查询。进入专用发行版并启动 daemon 属于实际运行操作，须在已安排的维护执行中进行。

`E/postcheck.log` 的实际进程参数与执行人员保存的原工具调用一致。核对专用发行版、路径归属、无冲突 socket／PID、无任务容器、现有服务及监听后，在专门保留的 Windows 前台维护终端使用以下已执行命令；本次文档整理没有执行它：

```powershell
wsl -d ConSense-Rehearsal-20261029 -u root -- dockerd --host=unix:///run/consense-docker/docker.sock --pidfile=/run/consense-docker/docker.pid --data-root=/srv/consense-docker/data --exec-root=/run/consense-docker/exec --iptables=false --ip6tables=false --ip-masq=false --bridge=none --userland-proxy=false
```

前台终端须持续保留，另开维护终端访问专用 socket；不要用关闭窗口作为停机流程。完整 daemon 启动／停机输出未完整落盘，前台终端替代原受控工具长会话的保活、再次启动及断会话恢复仍待验证；不能写成自启动或稳定无人值守方案。首次 `nohup` 后台尝试没有保持，未获得详细诊断；不要照此后台化。不得改用 `service docker start` 或省略禁用网络参数的通用命令。专用路径已不存在或身份冲突时停止处理，不自行删除 PID 文件或拼接初始化命令。

daemon 已由授权流程启动后，在专用 WSL 内，所有 Docker 操作必须显式指定专用 socket；已有脚本采用：

```sh
D=unix:///run/consense-docker/docker.sock
docker -H "$D" info
docker -H "$D" ps -a --no-trunc
```

不得回退到默认 socket 或混用其他发行版的 daemon。`info`／`ps` 成功仅说明指定引擎可访问。

### 安全停止与容器清理

本次使用的停止脚本为 `E/host-network-test/stop-owned-daemon.sh`。脚本不具备完整身份与 PID 竞态保护，只有在维护人员独立确认目标是本次专用 daemon、且无其他操作者同时修改容器或进程的情况下才可考虑使用；身份不明时停止处理。它的步骤是：指定专用 socket，要求 `ps -aq` 数量为零，读取专用 PID 文件，确认 `/proc/<pid>/exe` 等于 `/usr/bin/dockerd`，核实 cmdline 含专用 `--data-root=/srv/consense-docker/data`，最后只向该进程发送 SIGTERM。任一校验失败即停止；不得换成全局 kill 或强制清理。

该脚本发送信号不等于已完成退出；维护人员还须观察持有前台会话的正常退出和 `Daemon shutdown complete`。现有脚本没有全套防竞态／身份重用保护，不应在其他操作者同时创建容器时使用。

若存在任务容器，先逐个按已记录完整 ID 核对 inspect、镜像、名字、挂载和任务归属，保存 inspect、日志和退出状态；仅对核实归属的容器停止，再按完整 ID 删除。本次脚本按此范围保存证据并删除容器。不得使用 `docker rm $(docker ps -aq)`、prune 或按名字通配删除。历史 ID 只供取证，不作为下一轮删除清单。停止 daemon 不要求终止／注销 WSL，也不要求删除持久目录。

## 已验证的最小容器条件

测试镜像 `consense-rehearsal/account-test:20261029` 由已签名及 SHA 校验的 Ubuntu rootfs 导入，离线创建 `consense`，UID/GID 均为 10001，默认 `USER consense:consense`、工作目录 `/home/consense`；准备过程没有 builder 容器。`prepare-image.sh` 会解包并创建账户，不是可无条件重复执行的安装器。

容器创建时同时显式设置 `--user consense:consense`。实际 `id`／`getent` 证明测试账户身份；权限加强试验还使用 `--cap-drop ALL`、`--security-opt no-new-privileges:true`，观察到 `CapEff=0000000000000000`、`NoNewPrivs=1`。这些结果仅适用于该测试镜像，不证明 Java、数据库、OCR、检索或初始化容器都能以同样限制运行。构建／安装使用 root 和实际容器进程身份应分开记录。

两个串行 `--network none` 挂载容器把同一任务自有 D 盘 marker 映射到 `/view-a` 和 `/different/location`，内容 SHA 与 Windows 相同；只读写入尝试失败，独立可写挂载 `/write` 的输出由 Windows 读回，均退出 0。它证明该任务文件在两个路径的读写接线；DrvFS Unix mode／ownership 不能代替 Windows ACL、业务目录权限、数据库一致性或换版恢复证明。

GPU 查询使用 `--network none`、`--device /dev/dxg:/dev/dxg`，只读挂载 `/usr/lib/wsl/lib` 和 `/usr/lib/wsl/drivers`，并设置 `LD_LIBRARY_PATH=/usr/lib/wsl/lib`。同一非 root 账户执行 `nvidia-smi`，报告 RTX A4000、16376 MiB、驱动 582.99，退出 0；收紧 capabilities 的第四次串行试验同样成功。这是 WSL 设备可见性检查，没有模型加载、CUDA 推理或实际显存负载，也没有证明赛方 H800 的 `--gpus`／驱动运行时配置。

## host 网络有限验证

2026-10-29 17:36–17:37（上海）使用两个 Docker 容器作为 server／client，均 `--network host`、具名非 root、cap-drop ALL、no-new-privileges，inspect 显示 `Privileged=false`。启动前 Windows／WSL 快照确认 `127.0.0.1:28641` 空闲，server 精确绑定该 loopback，若占用则绑定失败；client 只连接此端口，交换本轮 nonce。启动 client 前确认 server Running 且 `ss` 监听进程身份匹配容器进程；两者退出 0。取证后按完整 ID 删除，Docker 无剩余容器、监听消失。

这是 WSL 共享 host 网络的定点连通测试。它不证明 bridge／端口发布、网络隔离、对外暴露安全、业务端口连通、完整离线或赛方平台可用。固定测试端口也须每轮重新核对占用。两个容器同时存在的记录和本 daemon 清空快照，不等于整个赛方平台任意时刻不超过五容器。

## CPU 进程树的补充探针

本轮后继使用同一具名非 root 账户，串行检查正常退出、忽略 TERM 后取消、主进程提前退出三种场景。正常与提前退出的容器退出码为 0，取消场景由 Docker 超时兜底后退出 137；每例预先记录的父、子、孙三个宿主进程身份均不再存在，精确默认 cgroup 已删除。各容器无 GPU、业务数据和 Docker socket 挂载，网络为 none，capabilities 为空并禁止提权；取证后按完整 ID 清理，专属 daemon 受控停止。

首次 create 因 swap-limit warning 混入标准输出而污染 ID，实例未启动，失败与精确清理记录保留；后继仅修正标准输出／错误分离及完整 ID 校验后执行。通过结果只证明宿主 Docker 拥有整个容器的 CPU 进程树释放可行，不能证明现有 ownedBoot 在默认容器 cgroup 下兼容，也不证明 GPU 上下文释放、模型或最终显卡方案通过。证据位于 `E/cpu-lifecycle-probe/` 与 `E/cpu-lifecycle-probe-retry/`。

## 票据 01 尚欠的证据

前台终端保活、再次启动及断会话恢复；真实应用镜像和所有实际进程的非 root 身份；业务持久挂载／Windows ACL；真实数据库备份、恢复与持久化；稳定 OCR、embedding、rerank 的实际权重加载和推理；完整业务／停止／换版／恢复；完整离线及全平台五容器上限；赛方 H800、GPU 运行时和扫描条件。Qwen 本机假权重及协议替身须持续显式标记，真实 Qwen／vLLM 接口、推理和业务质量另行验收。以上未完成项不得由本最小环境结果代替。
