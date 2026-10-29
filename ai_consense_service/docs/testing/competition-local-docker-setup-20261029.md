# 本机 Docker 最小环境操作说明（2026-10-29）

票据 01 **未关闭**。已验证的是 Windows／WSL 本机的隔离 Docker 最小试验，不是业务栈、五容器完整生命周期或赛方平台验收。本说明仅整理已有回执、脚本和 inspect；编写时没有启动、停止或重跑服务／容器。

执行范围以 [OER 规则](../oer-scope-and-rules.md) 和[实施交接](../HANDOFF-ocr-embedding-rerank-20261008.md)为准。实际业务部署仍按[容器实施说明](../specs/competition-container-external-runtime-20261029.md)推进，已选职责、H2 TCP、固定 GPU 容器与挂载约定见[运行方案](../specs/competition-runtime-plan-20261029.md)，实际资产、固定目标及排除范围见[保护登记](competition-protected-assets-20261029.md)。本机 Qwen 使用明确标识的假权重占位和协议替身；占位不能证明 Qwen／vLLM 推理。真实 OCR、embedding、rerank 容器加载与推理均未验收。

## 证据位置和使用边界

本机忽略目录（不随仓库发布）为 `.local/container-rehearsal-20261029/isolated-docker-01/`，下文简称 `E`。维护人员应在本机查看：

| 事实 | 可复核来源 |
| --- | --- |
| daemon、镜像、挂载、GPU、保护检查及失败 | `E/receipt.md`；安装、镜像、执行与 postcheck 日志 |
| 挂载和实际账户 | `E/test-mounts.sh`、`E/receipts/*.inspect.json`、`E/inspect-summary.json` |
| 权限收紧和 GPU 查询 | `E/test-unprivileged-gpu.sh`、对应 inspect 与执行日志 |
| host 网络交换与停止 | `E/host-network-test/receipt.md`、`run-check.sh`、`server.py`、`client.py`、inspect、`stop-owned-daemon.sh` |
| ownedBoot／LinuxTree 精确 cgroup 委派 | `E/cgroup-owned-boot/` 三轮证据、启动／核查脚本与 `SHA256SUMS`；最终为 `cgroup-probe-20261029-1859-c39e/` |
| 最小镜像与外置用户空间 | `E/external-runtime-e41f/{receipt.md,result.json,inspect-summary.json,positive-tar-list.json,export-verification.json}`；命令和断言在 `probe.py` |
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

host 网络与后继外置用户空间探针均记录：确认无任务容器并核实 daemon 身份后发送 SIGTERM，前台退出码 0，工具会话出现 `Daemon shutdown complete`。已有多次显式启动／正常停止，不再将“再次启动”写作完全未测。这不是 systemd 管理的 Docker 服务；后续使用必须显式启动。

### 启动前检查与待验证项

Windows 维护终端可用只读 `wsl --list --verbose` 核对发行版名称。不要用进入已停止发行版的命令冒充只读状态查询。进入专用发行版并启动 daemon 属于实际运行操作，须在已安排的维护执行中进行。

`E/postcheck.log` 的实际进程参数与执行人员保存的原工具调用一致。核对专用发行版、路径归属、无冲突 socket／PID、无任务容器、现有服务及监听后，在专门保留的 Windows 前台维护终端使用以下已执行命令；本次文档整理没有执行它：

```powershell
wsl -d ConSense-Rehearsal-20261029 -u root -- dockerd --host=unix:///run/consense-docker/docker.sock --pidfile=/run/consense-docker/docker.pid --data-root=/srv/consense-docker/data --exec-root=/run/consense-docker/exec --iptables=false --ip6tables=false --ip-masq=false --bridge=none --userland-proxy=false
```

前台终端须持续保留，另开维护终端访问专用 socket；不要用关闭窗口作为停机流程。完整 daemon 启动／停机输出未完整落盘，前台终端替代原受控工具长会话的保活及断会话恢复仍待验证；不能写成自启动或稳定无人值守方案。首次 `nohup` 后台尝试没有保持，未获得详细诊断；不要照此后台化。不得改用 `service docker start` 或省略禁用网络参数的通用命令。专用路径已不存在或身份冲突时停止处理，不自行删除 PID 文件或拼接初始化命令。

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

首次 create 因 swap-limit warning 混入标准输出而污染 ID，实例未启动，失败与精确清理记录保留；后继仅修正标准输出／错误分离及完整 ID 校验后执行。该轮只证明宿主 Docker 拥有整个容器的 CPU 进程树释放可行，不能证明现有 ownedBoot 在默认容器 cgroup 下兼容。证据位于 `E/cpu-lifecycle-probe/` 与 `E/cpu-lifecycle-probe-retry/`；后续精确委派另见下节。

## ownedBoot／LinuxTree 的精确委派探针

在任务专属 ownparent 内采用 host cgroup namespace、private PID，只将该 ownparent 挂载为 RW，其余 `/sys/fs/cgroup` 为 RO。上层 cpu／memory／pids 限制由宿主 root 控制，不把整个 cgroup 树或 Docker socket 暴露给容器。最终 c39e 中实际 UID/GID 为 10001，现有 ownedBoot／LinuxTree 路径把父、子、孙三个 CPU 进程收入该任务 cgroup，取消后回执为 `released`、`activeProcesses:0`、`cgroupPopulated:0`，ownparent 最后 `populated 0`。

证据位于 `E/cgroup-owned-boot/`，三轮共 39 文件已由 `SHA256SUMS` 校验，源与导出副本逐轮 diff 一致；最终 `container.stdout`、`parent-after.txt`、`container-id.txt` 和 `remove.txt` 保留运行、释放和精确清理依据。前两轮因探针对 controller 空值／子集的错误假设而失败，仍保留原始结果，不将其改为成功。最终成功只证明本机精确委派下 CPU 入组与取消释放；没有证明默认 cgroup 自动可用，也没有 GPU 上下文释放、资源限额压力或模型验收。赛方是否允许同样委派须另核。

## 最小镜像及外置用户空间的正反检查

19:11–19:12（上海）在专用发行版执行一次实际用户空间探针。正例镜像 tar 为 20,480 字节，仅 12 目录、4 个 merged-usr 链接及 passwd 47／group 18 字节，共 18 条目，没有二进制、业务或模型。镜像使用具名 `USER consense:consense`；RO 外置 `/usr`、`/etc/nsswitch.conf`、`/etc/ld.so.cache`、`/etc/python3` 与 `/etc/python3.12`，独立 16 MiB noexec／nosuid／nodev tmpfs 提供 `/tmp`。实际 `id`／`getent`／shell 与 Python 3.12 的 `pwd/json/hashlib/tempfile` 断言通过，UID/GID 均为 10001。

反例移除镜像内账户文件并挂载外部 passwd／group，在具名账户启动时按预期报 `unable to find user consense`。本次因此保留最小账户记录在镜像；它是已测 Docker 具名启动的挂载前解析约束，不能推出 numeric USER、其他平台或所有账户数据都不能外置。镜像 USER／PATH／HOME／WORKDIR 可由启动参数覆盖，不能列作“无法外置”的文件。最小镜像保留表见运行方案；Java、数据库及 GPU 精确依赖闭包仍按后票实际运行检查。

两例均 network none、RO rootfs、cap-drop ALL、NNP，无设备、GPU、业务卷或 Docker socket，串行单容器；inspect 和独立退出／错误文件完整保留。正例通过、反例保持预期失败，外置源 9,633 条文件／链接 manifest 前后一致，71 份 Linux 证据源／副本 SHA 匹配；容器及两镜像均按完整身份取证后精确清理。首次 WSL 命令只在含 `&` 的 Windows 路径解析失败，未执行探针；随后经 Python stdin 完成一次实际运行。swap-limit unsupported warning、完整 daemon stdout 未全部落盘的限制保留。不能由此声称业务依赖、GPU 推理或完全离线业务通过。

## 准备票与后续实际验收的分界

票据 01 整理现有行为、环境调查、保护副本、资产与目标路径、方案和可复核的最小接缝证据，由票据所有者逐条核定；本说明不关闭 01，也不单独授予 02–04 开工。保护副本、H2／44原件切点、五份索引 phase-B 全 seal 冷读及 73/74 绑定（另 1 项历史 unknown）见运行方案与保护登记，不把这批准备证据写成后票完整恢复验收。

02–14 仍须按依赖完成真实 App／网页／Python、H2 TCP 与 Qdrant server、业务挂载和各角色 10001 权限、稳定双路 OCR／embedding／rerank Docker 真实推理、流式／停止、浏览器业务、历史关联、换版、重建／恢复、日志和断开外部依赖后的重启与必要业务链。五容器上限须覆盖整个真实生命周期；旧 local Qdrant／SQLite 不能直接作为 server storage。前台会话中断恢复与完整 daemon 日志亦随实际启停／恢复留证。真实角色权限、数据库和模型业务验收是这些后票的执行门，不循环加回 01 的准备关闭条件。

赛方 H800、GPU 运行时、委派权限、网络及扫描受理另列目标环境差异。本机 Qwen 假权重／协议替身持续标记，真实 Qwen／vLLM 的接口、推理、计数和业务质量未验；任何准备或软件检查均不升级为这些结果。
