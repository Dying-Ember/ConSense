# 04: 从硬盘执行显卡任务并统一控制占用

**2026-10-31 范围纠偏（历史）：**当时按原票目标、验收清单和 Blocked by 核对；当前 demo 验收以本页顶部当前清单为准。下方历次运行状态、串行方式、额外开放理由与百分比均为历史记录；当前运行状态以看板和当次现场核对为准。

**What to build:** 在选定运行方式下，从硬盘启动一个真实显卡任务并保存必要结果；各任务入口争用资源、停止和失败时都有可信的占用与清理行为。

**Blocked by:** 01 — 确定本次保留的行为和本机实际运行方案。

**Status:** done — 已按本票 demo 验收完成；本机验证边界和历史失败见下

## 比赛 demo 当前验收

- [x] 真实 Docker GPU 任务从外置代码和现有模型运行，非 root 身份和一笔 BGE 输入、输出已有记录。
- [x] 已测 GPU 入口竞争能避免重复占用；已有取消、超时和模型驻留时崩溃的清理记录，观察范围与限制见下方。
- [x] 按现场状态处理 MinerU 任务的 `release_unknown`，确认所属任务已停止并以最小必要方式恢复后续 GPU 使用；旧任务 `d98db775ceb740088dae09fc330a1fa4` 已归档，后续 `56cb13715a204b1eb638c8a6f4504d6c` 也曾释放未知，当前 owner 以现场核对为准；不改写原状态，不为单进程显存量化、采样器或无依据限额反复重启。
- [x] 资源配置按冻结基准、赛方要求与本机可用资源确定；修复确实妨碍实际 GPU 共用的限制后，沿真实路径确认可继续使用。08 的完整 OCR 业务验收仍属 08，不倒挂为本票整体验收。
- [x] 必要的任务结果和错误记录可查看，历史 unknown 与 WSL／H800 差异如实保留；不为 demo 建设额外持久化或全依赖取证体系。

## 2026-11-01 14:10 复验与关票

本票现行五条 demo 验收逐项核对完成：下方 BGE、竞争、取消、自然超时和崩溃各有对应真实记录，观察边界不变。历史 MinerU `d98…` 和 `56cb…` owner 已分别归档，状态仍为 `release_unknown`；不追认其历史释放。受控修复后，真实单页 MinerU operation `e63ee854a7014ac9851e576f89c35f9b` 完成，worker 与 Source 均记录 `released`、任务子组 `cgroupPopulated=0`；Source `/ocr` 返回 HTTP 200。现场核对活动 owner 不存在，GPU 未列计算进程。运行容器为 `consense:consense`，14 CPU、180 GB 容器内存上限、无额外 PID 上限；本机 WSL 实际约 62.8 GiB，不将上限误认为物理内存。复验记录：`/srv/consense-rehearsal-20261029/evidence/local01/ticket08-b1-single-page-67f1bd62422943cfad324c91f5f3d8d9/summary.json` 及同目录 operation status；原状态与失败回执保留。由此确认受影响路径已恢复，按当前 demo 验收关闭 04。完整业务入口与 OCR 对应原页回查仍属 08；本机结果不代表 H800／赛方环境通过。

## 2026-11-01 本机 demo 进展与待确认问题（历史时点）

用户确认比赛以 demo 能运行、主要功能正确为目标，不再推进额外持久化工程改造。04 的主要功能已有下述真实运行证据，但 08 最新 MinerU 实跑出现 `release_unknown` 和进程数上限相关问题，实际 GPU 协作尚待解决，故 04 暂不关票。对现有问题做必要定位和修复，不增加繁杂取证或重复模型测试；下方旧状态与卡点是各次运行时的历史记录，不覆盖本节，也不删除失败证据。

- **真实任务：**真实 BGE operation `ad66fb4b681e442da8ee81ca4454e25b` 经固定 GPU worker 使用外置环境和模型，输出 1×1024 有限非零向量，worker 记录 `completed/released`；记录实际容器、非 root 进程、输入输出和任务身份。原始证据位于专用预演环境 `evidence/local01/ticket04-sample-fix-bge-model-once-20261101-a1/`。
- **互斥与取消：**operation `3fb182cc586145e797f501c50325ca0e` 持有 owner 时，另一个 business 客户端请求被 400 拒绝且没有产生第二个 operation；随后首任务取消一次，客户端和 worker 均报告 `cancelled/released`。这是已测 worker 接口，不代表绕过该接口的任意手工启动均受控。回执 `evidence/local01/ticket04-owner-competition-observation-only-20261101-a1/result.json`。
- **自然超时：**operation `96dd6dde21744c8d9ab92df934f6b8c8` 的活动样本、批次文件和期限记录可关联同一任务，worker 记录 `expired/released`，终态观察 owner 不存在、任务组仅剩 worker、驱动 compute 列表为空。验收脚本因采样间出现一个未记录身份的短暂 PID 而退出 1，并将身份和客户端生命周期保守标为未知；活动样本、原始批次及最终事件可独立读回，不改写原脚本结果。回执 `evidence/local01/ticket04-exit-race-expiry-90s-20261101-c1/`。
- **崩溃：**operation `88735d14bcc7498198af673046083890` 在核对任务身份后对该任务进程发送一次信号；记录进程消失、owner 不存在，worker 报告 `failed/released`。设备总显存回落至任务前附近，只是整卡趋势，不能量化该进程的显存。回执 `evidence/local01/ticket04-exit-race-model-crash-20261101-c4/`。
- **历史失败和恢复：**此前 `fdee3ce6db084294a16afad5279107fe` 等 `release_unknown` 的原状态保留，已按记录受控恢复，不将恢复追认为历史释放成功。旧 GPU 容器退出后的成功结果和失败状态读回见 [持久结果读回](../../../.local/container-rehearsal-20261029/ticket04-exited-container-readback-20261101.md)；相关历史记录及恢复步骤仍保留。

上述五容器数量是相应运行和采样范围内的记录，并非全程连续监控。WSL 的逐进程 GPU 信息受限；本机卡、驱动和环境不能代表赛方 H800，目标环境尚未验收。已有启动资产校验、非 root 权限、离线依赖安装检查与真实运行证据不等于所有动态导入或子进程的完整版本闭包；不以新增全量检查阻塞本次 demo；赛方 H800／目标环境尚未验收。

## 2026-11-01 历史卡点（当时状态）

本轮新版本真实自然超时 operation `fdee3ce6db084294a16afad5279107fe` 已到 90 秒并持久写出 1×1024 向量；GPU 采样前任务组和驱动均见 PID 17，采样后任务组列表为空但进程状态仍显示活动，worker 报 `release_unknown`。22 个设备总显存样本任务前 1729 MiB、最高 4150 MiB、终态 1759 MiB，仅为趋势。原 owner 已按身份和字节归档；原 status SHA-256 `c7ba407cf23fae38597bc376f9c7afd5abf695c3470eba31f6c3f4b7a5799172` 保持 `release_unknown`，GPU/Source 同 CID 恢复，五角色、owner、健康和资料指针独立读回通过。

针对已见证的同一任务主进程在退出时跨采样的不一致，源码窄修允许最多等待两秒确认该主进程退出并检查子组为空，再沿用终态 GPU 和完整委派树检查；陌生 PID、身份变化、超时及残留继续保护。共享源码组合测试 100 项通过，独立发布树已准备；尚未换版或真实复验，崩溃仍未验证。

真实 BGE 已有持久输出和一次 worker `released` 回执；90 秒超时任务 `807c8982cd5447c395ebc6fdc80395b0` 仍为 `release_unknown`，崩溃尝试 `3fa66e500a5149329ecdf9f5895f20c1` 在发送信号前即进入未知状态，崩溃场景尚未验证。历史状态不改写。

原票要求核对任务进程和显存释放，但未要求事先观察到非空 GPU PID。当前实现将其作为硬条件；WSL 的活动 compute 进程查询受限，实测曾可见 PID，也有任务存活时列表为空。应寻找适合实际平台的释放证据，而不是反复重试相同探测。整卡显存前后变化可作为辅助趋势，但不能单独排除其他进程变化造成的误判；证据不足仍保留未知及受控恢复。现有正常完成、取消和互斥证据复用，仍需补原票必要的超时和崩溃验证。

## 2026-11-01 完成时采样竞态修复与真实 BGE

模型 session 可能在一次空的 GPU 采样之后完成；现有 worker 在完成后若尚无可信进程观察，停止前补采一次，保留原有身份和释放未知处理。修复版 worker SHA-256 `e2783f96cfef9c1748b91e56d6dd83a59e6540671f5d29616b9e208c099183c6` 已部署。切换后五个非 root 角色、GPU 子 cgroup、Source 双 health 和 App 文档 23 指针独立读回通过。

一次真实 BGE operation `ad66fb4b681e442da8ee81ca4454e25b` 完成；持久输出为 1×1024，全部有限且非零，输出 SHA-256 `7c12320173ef462cdfea60fd49ac57abcd58db049b414f403fd54ad4edb225ff`。worker 记录 `released`，其任务组为空、观察到任务 GPU PID 11；独立只读核对任务身份、回执与当前 owner 不存在。证据位于 `/srv/consense-rehearsal-20261029/evidence/local01/ticket04-sample-fix-bge-model-once-20261101-a1/`。本次不声称独立连续显存见证，也不追认之前 `release_unknown` 的原因或释放。原票并发、超时及崩溃项目仍待验。

定向软件测试通过；Linux 系统及 retrieval Python 的一项旧 OCR 联合测试因缺 Pillow 导入失败，保留该结果。

## 2026-10-31 provider 取消发送竞态修复

本机回环 TCP 定向测试证明旧 worker 在两个派发窗口中可于取消返回后仍发送请求。共享 `fixed_gpu_worker.py` 已在取消时关闭该操作持有的 provider socket，并在派发前持锁核查 operation、session 与取消状态；清理时移除 socket 引用。先前未跟踪的共享工作文件在合入前后逐项核 SHA，原字节备份在 `.local/container-rehearsal-20261029/ticket04-cancel-shared-before-20261031/`；独立静审未发现确定阻断。

共享源码上非 root、单 CPU、隔离临时目录复测92项、exit0；含回环 TCP 与合成 provider，执行数包含重复导入测试。日志 `.local/container-rehearsal-20261029/ticket04-cancel-shared-regression-20261031.log` SHA256 `686784b1dc033388a315ad83f70752ec124302a210d1317dacc0141cd3d3d8fd`。候选早期失败和夹具调整记录保留在 `.local/container-rehearsal-20261029/ticket04-cancel-repair-20261031/`。

当前 GPU 容器仍使用旧挂载源码；未换版或发真实 provider/GPU 请求。关闭 socket 不证明已经被 provider 接收的任务停止或 GPU 已释放；原票实际取消、崩溃、资源与占用验收仍未完成。

## 2026-10-30 18:09 固定 BGE worker 单次真实请求失败

Source/GPU 配置 pin、五角色和空 owner 现场预检通过，但 operation `ec8abf8877f945ebafc3a42d1bb713f6` 在模型会话 ready 前失败；GPU stderr 为 `model identity or runtime differs`，终态 `release_unknown`、owner 保留，无模型输出。请求 embedding batchSize=4，GPU 阶段 boot env batchSize=2 是已确认差异，完整身份差异仍待核。点查五容器 5／5、目标 GPU compute 列表空、cgroup 只剩 worker，不能把该任务释放算通过。禁止自动重试/重启，04 不关，准备百分比不提高。原始证据见本地 `ticket-04-progress.md`。

## 2026-10-30 16:18 GPU 微型镜像候选已构建，运行版未变

现场预检固定五个原运行容器、GPU StartedAt/PID、Source 空闲连接和镜像底座后，Source 暂停让出一个运行槽；离线、禁拉取地构建 GPU 微型镜像候选 `sha256:36e5d4e408c6ff56d0ff20afe179a59fdde3a393b82ca99daf3e837bc4f954d4`，Source 随后恢复，五个原 CID 均仍运行、GPU PID/StartedAt 未变。候选导出 tar 10,752 字节、SHA-256 `3200c2adb0c414b8ae3fa8111b606dd26685046e73ec2f50ab29730d8a0215ba`；OCI blob／描述符／layer diff ID 均核同，静态 rootfs 仅有 0644 的账户文件，用户 `consense:consense`。回执在 `.local/container-rehearsal-20261029/serial04-gpu-micro-build-20261030/`。**该镜像未启动、重载或扫描，当前 GPU 仍运行旧镜像和 r2 tools**；固定 worker 真模型、释放和 Source 会话尚未通过，`release_unknown` 与禁自动重启不变。

## 2026-10-30 15:16 真实 BGE 校准，交付性释放仍未通过

现有 GPU 容器内以 UID10001 对一条合成文本做真实 BGE embedding，得到1024维有限非零向量、CUDA参数、exit0；10／10宿主样本将目标 GPU compute PID45 对应到该 Docker child 内同 startTicks／namespace／cgroup 的进程，终态驱动列表空、child只余 worker，五容器保持5／5。原始证据与复算回执在 `.local/container-rehearsal-20261029/serial04-bge-calibration-20261030/`。这是直接 `docker exec` 校准，未经过固定 worker 的任务协议、子组、owner 和 Source 会话验收，不能证明交付性释放；两处可信 pin／释放硬拒绝仍待完整接线，原 `release_unknown` 与禁止自动重启不变。04／GPU 容器准备估计54%／58%，非验收率，票据开放。

## 2026-10-30 13:39 两阶段准入与委派接缝已落地

共享 `container_admission.py` 已支持 GPU create→真实 ID 配置→精确启动，03 可显式登记唯一 created GPU 并预留第五槽；GPU profile／inspect 核 cgroup parent、namespace、bind、实际 Docker child 和 `gpu.json` 的 ID/cgroupRoot。共享 worker 与 `_LinuxTree` 已按实际 cgroup 身份迁组并扫描整个委派子树，根或兄弟组残留保持 unknown。专用 WSL 中准入 80 项、worker 定向 6 项通过。外置发布字节与 03 实际调用方仍待同步，本次未创建／启动 GPU，也未验模型、释放或业务；旧 `release_unknown` 禁自动重启不变。

## 2026-10-30 13:25 CPU cgroup 委派实测

首轮诊断脚本最终断言把必须含基线 PID 的根 cgroup 误当作空后代，原失败对象和回执保留。修正版的第二个非 root、无 GPU、无网络、受限 Docker 诊断容器实际完成子进程迁组与祖先逃逸拒绝，终态根组仅 PID1、后代空，exited0／PID0／非 OOM；App/H2/Qdrant 仍为原 3 个运行容器。证据见 `.local/container-rehearsal-20261029/serial04-cgroup-cpu-probe-20261030/run-two-receipt.json` 及票 04 进度。此结果只解除 CPU 拓扑探针自身的失败，不证明共享准入器、真实 GPU worker 或 GPU 上下文释放；旧 release unknown 禁自动重启和本票所有真实任务复选框保持。

## 2026-10-30 12:33 GPU 镜像入口与准入实阻断

固定 GPU 镜像原 ENTRYPOINT 的完整 argv 已保持不变地拆为非空 ENTRYPOINT 与 CMD，满足现有 `container_admission._profile` 的字段约束；重建后 image `sha256:134e977fd34d…`，仍未启动。两份真实 BGE 发布资产有 10／6 项 manifest 登记，本轮仅逐项 stat 大小并核小 manifest SHA；双阶段配置候选位于本地 `serial04-bge-two-stage-candidate-20261030/`，保留未决字段，不可运行。

独立只读核查确认更关键的实际阻断：已验证的非 root cgroup 进程迁移要求委派父 `cgroup.procs` 可写，当前 worker 在任务准入时要求它不可写；现有 `container_admission` 还缺已验证拓扑所需的 cgroup namespace／parent、精确嵌套挂载及“先 create 取得 ID、再绑定配置、最后 start”的阶段。仅给准入器补字段的隔离候选和 6 项静态测试不能解决生命周期矛盾，尚未合入。检索 venv 的 Python 是指向宿主解释器的符号链接，也不满足现行 builder 的小文件 pin 输入；真实 UID/设备、驱动、模型加载和释放均未验。旧 `cuInit=100` 与 GPU release unknown 保留，禁止自动重启；04 不关票。

2026-10-30 11:46：无 GPU 设备的同镜像库查找对照证实原环境找不到 `libdxcore.so`，仅增加 `LD_LIBRARY_PATH=/probe/driver/lib` 后能找到；见 `.local/container-rehearsal-20261029/gpu-loader-path-20261030.json`。此结果不是 `cuInit=100` 根因定案，也不证明 GPU 释放；原正控不重启，实际 GPU 任务和第五容器仍未验收。

**原始验收清单（历史）：**保留供追溯；较早的逐项详尽验证及工程化门槛不再作为 demo 的额外前置。当前完成条件与仍待处理的真实问题以本文顶部为准。

- [ ] 本机 Docker 容器使用外置脚本、模型配套和登记运行环境完成一个代表性实际显卡任务；镜像准备具名非 root 账户，核验账户名、UID/GID、实际进程、挂载及 GPU 设备权限，记录任务、输入、代码、模型及容器身份。Qwen 本机占位不算真实显卡任务。
- [ ] 实际启动程序、模块导入和所有子进程均来自登记的同一外置版本；缺路径、缺依赖、权限不足或版本不符时在执行前明确失败。
- [ ] 手工、业务请求及其他实际显卡启动入口共享可靠准入控制；并发竞争实测只有一个合规占用，不以卷挂载模式或单次检查代替互斥。
- [ ] 开始、执行、结束、取消和崩溃全过程均记录容器数量，满足五容器限制；不同时保留额外固定执行位置与临时任务。
- [ ] 任务输出写入约定的持久目录，结果确认前核对输入输出身份与实际持久字节；容器退出后仍能读回结果和失败记录。
- [ ] 取消、超时和崩溃只处理该任务拥有的进程树，核对实际进程、任务实例与显存释放；释放不明确时阻止后续任务并进入受控恢复。
- [ ] 保留真实失败及软件冲突检查证据，写清手工查看占用、判断残留和恢复的步骤；不停止无关活动服务，不把替身任务计作真实显卡运行。
