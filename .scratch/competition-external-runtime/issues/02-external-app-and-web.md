# 02: 从硬盘启动后端和完整网页

## 2026-11-01 比赛 demo 验收口径

本票保持已完成：在真实非 root 容器使用外置后端、网页和配置，代表性页面可访问且已有业务保存后可重读；必要数据和私密配置受保护。已有浏览器和数据库读回记录可复用，不为 demo 追加生产级持久化改造、完整路径穷举或重复重启。既往记录中的构造向量不代表真实检索，真实模型、其他业务和赛方环境仍由对应票据分别验证。

**2026-10-31 范围纠偏（历史）：**当时按原票目标、验收清单和 Blocked by 核对；当前 demo 口径以顶部说明为准。下方历次运行状态、串行方式、额外开放理由与百分比均为历史记录；当前运行状态以看板和当次现场核对为准。

## 2026-10-30 内置浏览器回环业务切片

专用 WSL 的临时 IPv4 回环转发经现有 wslrelay 接通 Windows 内置浏览器；在隔离 H2 测试项目 proj_mvud35x6e4k7 上，临时修改名称、保存并刷新读回，再恢复原名并刷新确认。浏览器的实际 UI 操作记录在本次会话；范围和转发退出证据见 .local/container-rehearsal-20261029/ticket02-browser-check-20261030/README.md。临时转发到期 exit0，Windows/WSL 监听均不再存在；它不是持久入口。此步骤不证明其它业务、真实模型、OCR/检索或容器重建，票02保持开放。


**What to build:** 使用固定运行镜像读取硬盘上的后端程序包、完整网页和实际配置，打开现有页面并执行一个已有业务操作；业务修改不再要求重做镜像。

**Blocked by:** 01 — 确定本次保留的行为和本机实际运行方案。

**Status:** done — 2026-10-31 本机按原票及下述限定证据关票；整体任务、源码提交与远端同步仍待后续完成

## 2026-10-31 原票关票记录（限定范围）

原票 01 已关闭；本票按原有七项验收，不等待 03／08 双路 OCR、真实模型或 14 全栈验收。运行对象、源码、构建、独立核查和失败证据分开记录：

1. **外置应用与登记身份。**修复版 JAR SHA256 `5f433afb9ba94cf9b936d1f283c833ccc60507ec6310218916e4ff378f1c73c4`，由 `ticket02-minimal-logging-source-20261031/` 的旧运行包对应源码独立构建；与旧运行 JAR 的 1241 条目逐项对照，只有 ProjectService、DraftingService、PromptService 三类变化，WebConfig MIME、资源及 90 个嵌套依赖不变。三组共5项定向测试通过。真实 App CID `24ee84d9a54278d5249b6ecc1b6dd4c7ba876b771fee245ad7cd08259a3f3a95` 以登记镜像 `sha256:7ef8a66d1ea612a044b60a4e89e16257b5dbefb349e0f55122e211ce23412395` 运行，JAR 和网页来自新只读 release；文件列表 pin `743af3e699b5762b086ed5da821338ec26d2ab4aba3a69c1e2a23083a4e7b1cd`，内容 pin `29d6d35247d27347a71eef15b41ec98720005e3a944b1689d4531cdf60048f12`。独立 UID10001 逐项读回89文件；应用与私有配置挂载分开登记。发布、切换和实际请求回执在专用发行版 `evidence/local01/ticket02-logging-release-b3-20261031/`、`ticket02-logging-switch-20261031-b3/`。
2. **非 root、挂载与业务资源。**App/H2/Qdrant 实际进程 UID/GID10001，rootfs 和发布资产只读、数据及临时目录按职责可写。旧构建快照53个资源与运行 JAR 对应文件逐项一致；当前提示词和 Drafting catalog 接口只读成功，证据 `ticket02-resource-readback-0ac9668a25d34263bb4aa59f5e28e6c8/receipt.json`。有限搜索没有找到搬迁前提示词自定义摘要，当前0条自定义不能证明历史上也是0；历史自定义状态未知，不作无损断言。
3. **真实副本数据库访问。**隔离真实 H2 工作副本在内置浏览器完成测试项目保存与读回，H2 数据和 Qdrant storage 持久目录 UID/GID10001、0700。Qdrant 独立测试集合单次写入并读回绑定真实项目、文档及来源 SHA 的**构造向量**，原三集合配置、点数和点 ID 摘要前后相同；回执 `evidence/local01/ticket02-realcopy-vector-probe-43e3cc3509b74638bfe6f701c9364521/receipt.json`。这是 Qdrant 直接持久服务存取证据，不证明 App 业务索引、真实 embedding 或浏览器检索；这些按后票处理。
4. **启动负例。**错误挂载导致网页缺失、错误清单摘要和无效 JDBC 在断网隔离容器中均 exit1/PID0/非 OOM；早前额外发布文件独立容器也 exit1。回执分别为 `evidence/local01/ticket02-negative-resume-0f3b96bbb3ff4e1a96ba044ba0ff7ebd/`、`.local/container-rehearsal-20261029/ticket02-independent-negative-terminal-3dbad3c2866f43cc84ecf5513702ad23/receipt.json`。前两项未采到短命进程 UID，也未独立监测 Java exec；结合入口控制流及已有正控解释，不夸大为全部错误配置均枚举。
5. **私有范围及日志。**指定配置、JAR、manifest、上传目录和编码穿越五次静态 HTTP 探针被拒，既有历史原件静态路径拒绝也有回执；这不是全部路径安全证明。已定位的五处异常日志已在 b3 源码修复，5项定向测试通过；当前容器一次合成业务错误请求的文件／Docker 日志增量未出现测试标识，框架日志过滤另有隔离测试。其余 logger、请求和攻击路径未穷举，不声称日志或安全绝对无泄露。
6. **浏览器现有业务。**b3 在内置浏览器对隔离项目改名、等待“已保存”、刷新读回，并恢复原名后刷新；API 最后只读确认后缀不存在。记录 `.local/container-rehearsal-20261029/ticket02-resume-browser-20261031/README.md`；项目接口和4004错误与冻结基线调用语义对照，早期 b2 立即刷新在异步保存完成前看到旧名的时序保留。
7. **证据与操作说明。**证据地址如上，独立启动和故障定位步骤在 `ai_consense_service/tools/offline_delivery/README.md`；说明代码已做静态检查，整套新说明未另外做人工照文复现。原票不要求该附加复现作为关票条件。

**仍未验：**搬迁前提示词自定义状态；真实历史业务向量、真实模型和全栈业务；所有潜在日志和私有路径；长期运行。Qwen 替身、OCR、索引、换版、日志全链和整体预演仍按各自票据处理。工作区现有未提交改动保留，源码提交和四个 main 引用对齐在本轮整体交付时完成；本票本机关票不代表赛方验收。

## 2026-10-30 12:38 三个 CPU 专用镜像实际切换

App、H2、Qdrant 三个独立外置角色镜像已构建、静态核身份，并在专用预演环境内按 App→H2→Qdrant 停、H2→Qdrant→App 起完成实际切换；旧对象和原数据保留。当前新三容器实际运行，镜像摘要分别为 `7ef8a66d1ea6…`、`965d5a05d1c4…`、`ee7f83e41b48…`，进程 UID/GID10001:10001、只读 rootfs、非 privileged。App API 读回原五个项目、历史原件 63,049 字节及原 SHA；Qdrant 既存合成点在新镜像下读回。实际切换与限定读回见 `.local/container-rehearsal-20261029/serial-role-images-20261030/{switch-readback-receipt.json,post-switch-readback-v2.json}`；Docker 的 swap limit 警告保留，未声称交换空间限额通过。

这是从通用底座到专用镜像的运行接缝验证，02 仍开放：App 向量点写／检索、生成 BLOB、完整浏览器业务、长期稳定与最终安全检查未完成；资料和 GPU 仍未运行，实际 3／5。看板 02 准备估计调至 78%，不是验收率。

随后对四个历史项目的 Drafting 文档列表各做一次只读 API 核查：每项目只有三个未生成的固定槽位，合计 0 个可下载成品；H2 登记的 18 条未匹配 PDF 行不能作为有效生成物。既有旧 Vetting 报告导出仍因来源 stale 返回业务 4104，未重跑或绕过。回执在 `.local/container-rehearsal-20261029/serial-role-images-20261030/h2-blob-readback/receipt.json`。因此本轮没有可用于正向验证生成 BLOB 的现存成品，此项继续开放，不据空列表增加完成估计。

**历史勾选（2026-10-31 已验范围见上方关票记录，不表示所有路径均已穷举）：**

- [x] 在本机真实容器内启动后端和网页，使用登记的外置发布资产与实际配置；记录镜像、源码、构建产物和有效配置身份，不以宿主直接启动替代。
- [x] 在 Docker 镜像准备具名非 root 运行账户，记录名称、UID/GID；核验实际进程身份、只读版本挂载与可写临时目录并验证页面、静态资源和业务接口。程序实际读取的业务提示策略等资源完整保留。
- [x] 通过具名非 root 运行的持久关系数据库和向量数据库访问隔离的真实项目副本，核验数据库进程及持久目录权限和读取与保存；内存数据库、内存向量库和健康检查只作为辅助检查。
- [x] 缺失文件、错误挂载、额外发布文件、校验不符及错误配置均明确失败；启动脚本和实际启动命令读取同一登记版本。
- [x] 网页可访问范围与私有数据范围分离，实际请求无法获取密钥、数据库、原件或受限配置；日志只记录脱敏有效值。
- [x] 在浏览器完成页面进入、一个已有业务请求和保存后重读，核对接口路径、返回和错误含义与基线一致。
- [x] 保存容器启动、挂载权限、实际请求和重读的可核查证据，并补充本票启动及错误定位步骤；软件检查和真实运行分别记录。

## 2026-10-30 11:43 串行核验增量

四个历史项目的 Vetting／Advice 来源列表 43 条身份逐项一致（计数 4、3、1、35；Advice 41 included／2 excluded），回执见 `.local/container-rehearsal-20261029/serial02-historical-assets-20261030/cross-module-source-list.json`，SHA-256 `9a5b163a60ced13c5ddcd66489c1bf41261819bf19625dec896e4c9ada2211f4`。四项目 Drafting 当前均仅三个未生成模板位。一个历史 Vetting 已完成运行的 display CLOB 在现有 App 读回 HTTP200/code0、47,360 字节、SHA-256 `232b718364c9f2fcdafb490cdf9c3d94cc6958a8f322936aede6b5cd3fb51ec3`；来源集已 stale，两个旧报告导出均被业务 4104 拒绝，没有验证生成报告 BLOB。专用 daemon 的实际 socket 为 `/run/consense-docker/docker.sock`，现场 App/H2/Qdrant 仍 3 个运行容器。02 仍开放，GPU 释放 unknown。

## 2026-10-30 09:28 +08 串行实施实证

- 当前源码在独立目录完成 386 文件逐项核同；Maven 离线打包编译 333 个主 Java 源并产出 JAR SHA-256 `99c2100fcdd04d5ddb3fda326dd3f5326844139198b0791a970dd651d63df84d`，本轮跳过测试。Vue 类型检查通过；Vite 首次因系统 Temp 中 esbuild 文件访问被拒失败，改用本票私有 Temp 后构建成功。产物、失败及成功日志保存在 `.local/container-rehearsal-20261029/serial-ticket02-build-20261030/`，发布到专用发行版 `releases/serial02-20261030`，未覆盖仓库运行产物。
- 实际运行 App 容器 `2d5bf20a9f64d4a346e87b18b36d35bdf0289d5488dfd864ad383978261166b8` 与持久 H2 合成库容器 `5dd38a72416fe1f3f2cbed74f9c91d221beddf824f3c796293eaaeed27fd8355`；两者使用固定镜像 `sha256:d607e8ae9fbcbba5b5651ca13f722600d956fb420f6d0192f40ba08fd5778ef1`。两容器配置为 `consense:consense`、只读 rootfs、非 privileged；`docker top` 在最后一次重启后分别确认 Java PID 16479、16394 的 UID/GID 均为 10001:10001。App 发布目录及 JRE 均为只读 bind，上传与临时目录独立可写；H2 数据目录仅由 H2 容器可写。最后清单恰 2 个运行容器，低于 5。
- 浏览器 TaskSpace 1 在 App 的 Drafting 页面新建合成项目 `proj_mvuacmtm8cwv`，名称 `串行02容器合成项目 20261030`、合约编号 `TEST-02-20261030`。刷新后项目下拉仍选中该项目，`GET /api/projects/proj_mvuacmtm8cwv` 为 200 且字段读回相同。随后精确停止并重启 App，再读回；再按 App→H2 停止、H2→App 重启顺序操作，浏览器刷新与接口读回仍通过。H2 停止时 `db/acceptance.mv.db` 为 397312 字节、UID/GID 10001:10001，父目录 0700。三份只含合成身份的回执 SHA-256 分别为 `browser-readback.json` `906e9f4c681e97b7bdbe8f653d85456d02b1760e4d90184d0d7e4971180ca75f`、`browser-app-restart-readback.json` `5bc4f06816707b74f1889031c71d9f6d665e01fb4f5739cf27ad774fca3e8b29`、`browser-app-h2-restart-readback.json` `b234089ee3d4bdb033ecd7603562d7ff826b051baee9676b44f5d591cf8bd4ad`，均在上述独立目录。Docker 最后一次启动日志显示 Hikari、网页 welcome page 和 Tomcat 28680 正常启动，明确警告向量库仍为内存实现。
- 这仅证明合成项目的 App/H2 容器读写及跨重启持久。H2 当前使用空密码、回环 TCP、合成库；App 关闭 LLM/OCR、向量库为内存，未接真实项目副本与 Qdrant Server。既有 6.5 GB H2 工作副本当前身份和历史记录需先核对；旧 Qdrant 第三次写入仍为 unknown，不重放。网页私有路径拒绝、实际来源/模型、跨角色隔离和完整业务范围未验，票据继续开放。

## 2026-10-30 09:40 +08 新空 Qdrant 只读实证

新建独立空实例的 Qdrant 1.19.2 实际容器 `34f889565773ebdeb7a311aa19e9f518ce7513a504dbbc558e58a08ac20fbbf4`，外置二进制 SHA-256 `7dba403e3b1e1b68e8b4ec9f241b6152361a12c58f17d2d8146d1ce343d091a6`，进程 PID 16775、UID/GID 10001:10001，监听 `127.0.0.1:28683`。程序和 root 私有配置只读 bind，独立 storage/tmp 可写；启动时 3 个运行容器。`/collections`、`/aliases` 的无凭据与错误凭据各 401，admin 与 read-only 凭据各 200、空清单；总计 8 次 GET，无业务写入。精确停止后容器 exited 0/PID 0，28683 监听消失，当前 2 个运行容器。新实例和原失败/未知实例及各自存储均保留。

私有证据位于 `/srv/consense-rehearsal-20261029/evidence/local01/qdrant-serial02-auth-readonly-20261030/`；`readonly-probe.json` SHA-256 `c9361aa68bf626dfd6f7f606cdc096a66d84226c63d7ec80dd3523764d116c97`，`terminal-privacy.json` SHA-256 `5400864689983763f626f768537df7e76cfee1e98a3f587b8ddc0befdc64fbbc`。后者仅证明两个合成密钥未出现在本次 Docker inspect、容器完整日志和三份回执中。只读 key 的写拒绝、真实单写者、持久向量读写、旧索引迁移及 03 业务均未验；02 仍开放。

2026-10-30 09:41 +08 只读复核：6.5 GB H2 工作文件正式名与 `.partial` 两条硬链接当前均为 dev2112/inode56081、6,531,010,560 B、root:root0600、nlink2，mtime/ctime 与 `ticket02-work-link-result.json` 发布后记录一致；此前接力摘要中的元数据不一致疑虑未重现。本轮未重新读取全文件字节，原库有效凭据仍未确定，也未给 UID10001 授权或启动真实项目库。

## 2026-10-30 09:48 +08 合成 Qdrant 写入与重启读回

沿用上述新空实例，read-only key 的 collection PUT 返回 403、管理员读回仍为空；管理员随后创建独立合成 collection、写入点 314159 并查询，三个请求均 HTTP 200，写入 operation 为 completed。同一容器停止重启后进程 PID 17003→17178，read-only key 仍查到同一 marker。再次停止后 exited 0/PID 0、`ss` 无 28683 监听，当前仅 App 与合成 H2 两个运行容器。终态脚本的 socket bind 检查遭内核拒绝，不作为监听证据；修正为 `ss` 检查后完成，不重放写入。新实例的完整 Docker 日志 9289 B，SHA-256 `9b151b94fddd9a7c076f98290b2865815fbe6ee7ae3dd96af0724df7dd3e013f`，合成密钥未出现在 inspect、日志及七份回执中。

私有证据仍在上述 `qdrant-serial02-auth-readonly-20261030/`，其中 `readonly-write-result.json` SHA-256 `311323349146e8c70171504ad5420cca3ff796d5cbbec9b0b09400b0832f39c9`、`synthetic-write-journal.jsonl` SHA-256 `29b730a02a2221fd1bfbbad37ec16dbf3c4eba6c1e2a98ee0b857339a73dd89c`、`restart-readback.json` SHA-256 `67ec802c9973bfa9c4ba5125a17014c1c90922e33da3ba9a3bd7e0e7a5632412`、`final-terminal.json` SHA-256 `c08e6546529e42ce4afd71e37f584df13bd64db859a9b6150b847abf53686781`。仅证明合成 Qdrant Server 单实例重启持久读回；App 仍接内存向量库，真实项目副本、跨进程单写者、历史迁移、私有范围及完整业务未验，02 不关票。旧 attempt1 第三次写入 unknown 保留，不重放。

## 2026-10-30 09:55 +08 封闭 H2 基线只读认证

两个本票诊断容器均以 consense UID/GID10001:10001、network=none、只读 rootfs 运行，H2 2.1.214 只读 bind 封闭基线单文件。首次 URL 漏原库的 lowercase/case-insensitive 配置，schema `public` 重放失败 `[90079-214]`、exit1；修正 URL 后新容器以 `sa` 空密码完成 `SELECT 1`，输出 `auth_probe=1`、1 row、exit0。两次基线 metadata 前后一致，容器均 exited/PID0、当前只有 App 和合成 H2 两个运行。私有回执初次 SHA-256 `e6216e93d7c99ccdf6afd3878e37270155af97dcfd153ac5a6563a502b44bd33`，修正版 SHA-256 `bf75b822570d89ec035bc911780c8ed31fcbf5f376fb6566944246a06583d84c`，原始输出留在各自证据目录。这只证封闭基线的只读认证和简单查询；可写工作副本、非空凭据、项目表/LOB/触发器、App 接入与实际历史业务均未验。

## 2026-10-30 10:06 +08 工作副本凭据变更 UNKNOWN

参数化 `ALTER USER` 先在独立合成库实跑通过旧/错误密码拒绝和新密码读回。真实工作副本与封闭基线分别为 inode56081/55897；准备时工作副本改为 UID/GID10001:10001、0600，硬链接 `.partial` 同步变化，私有密码文件 root:10001/0440，封闭基线元数据不变。准备回执 SHA-256 `3c1a562bb7e0bf85b7a6db4a7432c60dc02b2d8e6fbbb45fa167c26c44635be4`。首个真实 init 容器在 Java 主类加载前因 Windows→WSL 参数转换失败，exit1，保留容器/日志；未见业务写入。

第二容器从 WSL 内构造参数数组，实际返回 `OLD_AUTH_OK`、`ALTER_RETURNED`，新凭据重连报 `JdbcSQLNonTransientConnectionException`/SQLState `90108`，容器 exit1/PID0。工作文件 mtime/ctime 已变，大小仍 6,531,010,560 B；封闭基线元数据未变，当前只有 App 与合成 H2 运行。此时密码是否生效、文件是否可继续打开均未证明，**工作副本 UNKNOWN，停止重放和任何服务启动/写入**。私有 intent SHA-256 `66a36affe5e4ac554931d81d98ea1ec858ea946938ad13a8bad16c8afc73759d`、result SHA-256 `9229a102268f76c943899a231c24bec39542fbe5f4d9c0541acc5072114dec64`，输出留私有目录；下一步仅只读分类 SQLState 和文件状态。票02不关。

## 2026-10-30 10:13 +08 只读复核收窄 UNKNOWN

同版 H2 2.1.214 的 `ErrorCode` 常量将 `90108` 定义为 `OUT_OF_MEMORY`；前次 init 在 `-Xmx384m` 的新凭据重连失败。新独立非 root、network=none、只读 rootfs 容器以 `-Xmx768m`/1280MiB 容器限额、只读 bind 工作文件和私有密码文件，返回 `NEW_AUTH_READONLY_OK` 与 `OLD_AUTH_DENIED`（旧空密码 SQLState28000），exit0/PID0。工作和封闭基线元数据在本次检查前后相同；容器 ID `aaae2ad381f03c07b83f0da787d8540f83ae9bff448c95864556799902b6664c`，私有 `readonly-verify-result.json` SHA-256 `ca4f7472f6f9e4d81afb270d465ab5d3e33168d9cfa090d9bfbfffbdf57fdcd7`。前次 OOM 及 argv 失败继续保留；当前只确认新凭据只读可用和旧空密码拒绝，真实 TCP、项目表/LOB/触发器、App 与 Qdrant 联动及历史业务仍未验，02 不关票。

## 2026-10-30 10:17 +08 真实 H2 TCP 首次失败

按 App→合成 H2 顺序精确停止两容器后，只有工作副本 H2 容器 `9ba1946c158e07e0a372b025e4c065d903089a7dad0d28355516844fc08e233c` 以 consense UID/GID10001、只读 rootfs、非 privileged 运行，监听 127.0.0.1:28682。实际同容器新凭据 TCP 查询输出 `TCP_READONLY_OK tables=25`，但连接关闭时报 SQLState90108、客户端 exit1；服务器停止前约 967.4MiB 内存，堆上限1024MiB。精确停止后容器 exited143/PID0、OOMKilled=false、端口无监听，当前0运行容器。工作文件mtime/ctime在调用时变化，封闭基线未变。后续独立只读验证新凭据成功、旧空密码拒绝，工作/基线在本次只读检查前后metadata一致；`posttcp-verify-result.json` SHA-256 `ca4f7472f6f9e4d81afb270d465ab5d3e33168d9cfa090d9bfbfffbdf57fdcd7`。首次客户端stdout/stderr只在命令输出和本记录，未保存原始文件；不能把25张表读取升级为稳定TCP或业务通过。下一步是有界内存边界诊断，02仍开放。

## 2026-10-30 10:27 +08 真实工作副本接入 App，仍待浏览器保存

真实 H2 工作副本已在容器 `6643e422bca0ee5cf823c2a9378c1785116bd8161e1506f3edb95457b139d0cc` 以 `-Xmx2048m`/3GiB 限额运行；外置 App 容器为 `3ae13a5c2f50189626f790bdc858728e408223bb6fb18de07c9d6ba952373267`，2GiB 限额。当前专用 daemon 恰2个运行容器，实际 Java PID 18989/19190 均为 UID/GID10001:10001、只读 rootfs、OOMKilled=false；H2/App 分别只监听回环 28682/28680。App 日志显示 Hikari 连接池、JPA 与网页初始化完成；专用发行版回环 `GET /api/projects` 为 HTTP200、4条项目记录，`GET /api/projects/proj_mvnhrh8eemyb` 为 HTTP200。H2 容器时点内存 1.813GiB/3GiB。私有凭据仅在 root:10001/0440 外置文件和 App 私有配置，未打印值。此轮没有网页保存或重启；Windows 侧直连回环 28680 被拒，尚未完成浏览器历史项目读回。App 当前向量库仍为内存，LLM/OCR关闭，真实 Qdrant 接入、项目 LOB/触发器、私有路径与完整业务仍未验。旧 1GiB 堆 TCP 关闭时90108失败保留，02不关票。

## 2026-10-30 10:41 +08 真实 H2 浏览器保存与双容器重启读回

临时诊断桥只绑定 WSL 内网 `172.19.170.149:28685` 并只接受 Windows WSL 主机 `172.19.160.1`，将浏览器请求转至 App 的 WSL 回环28680；浏览器 TaskSpace 2 的 Drafting 页面实际显示原4个项目，按既有ID详情读回HTTP200。仅在隔离工作副本内新增测试项目 `proj_mvud35x6e4k7`、合约编号 `TEST-REAL-H2-02-20261030`，旧4 ID均保留；保存后详情HTTP200，页面刷新后仍读回。随后 App→H2 依次停止，均 exited143/PID0/OOMKilled=false，运行容器清零、28680/28682不监听；H2→App 依次重启，浏览器再次刷新后选中同一测试项目，详情名称及合约编号一致、项目总数5。重启后 Java PID19751/19841 均为 UID/GID10001:10001、只读rootfs，恰2个运行容器，H2时点内存1.835GiB/3GiB、两者OOMKilled=false。浏览器任务已结束，临时桥已退出，28685无监听；App/H2仍只监听WSL回环。

本地回执在 `.local/container-rehearsal-20261029/serial-ticket02-build-20261030/`：`browser-real-h2-readback.json` SHA-256 `f337ba590e5dafd91f54d2be8ecdeb65923f7d730491723fd418b05b5aa7187b`；`browser-real-h2-save-readback.json` `d718a7271f05344dad9da085e6286dc37ae4c98477e608ba5b6edcf6ff297e37`；`browser-real-h2-page-reload-readback.json` `eff9d3e4217de7c4ec8f3fc86f746bd4ef887334461eda30b86536ba82df9b57`；`browser-real-h2-app-h2-restart-readback.json` `caf0c301655aac86a8b4e1c614bbec3565dcc7579ac56aa198e7292e14b681af`。这些证明真实工作副本的项目元数据写读及按序重启持久；页面选中历史项目时模板显示未上传，不能据项目列表推断历史原件可读。项目LOB/触发器、App接Qdrant Server、私有路径/错误配置拒绝、长期稳定及完整业务未验，票02仍开放。旧1GiB堆90108和旧Qdrant unknown原样保留。

## 2026-10-30 10:58 +08 App 对鉴权 Qdrant 的真实接入

发现 `ConsenseProperties.VectorCfg.apiKey` 已存在，但 `QdrantVectorStore` 所有 HTTP 调用没有发送它。已在 `HttpSupport` 增加带请求头的 GET/POST/PUT 最小入口，并将向量客户端的集合检查、创建、点写入、查询、计数、删除及健康检查统一发送 `api-key`；无 key 配置继续不发送该头。隔离 D 盘源码定向 `QdrantQueryHttpContractTest` 4项通过/0失败/0跳过，实际 HTTP fixture 核到集合GET及点查询POST均带标记 key；日志 `focused-test-attempt3.log` SHA-256 `62e1ce68d16e46f1e4f9721ec0b831242df030511ef1dc7d31d807295cffc583`。首次 Maven 因 `JAVA_HOME` 缺失失败、第二次 Surefire 因含 `&` 路径 fork 子进程失败且0项执行，两次失败留存；第三次无 fork 通过。离线 package exit0，日志 `package.log` SHA-256 `2ec9706e1caa9a19d959064e0a2e863434e44f3c2a762548f7d9d9828b1af0d7`，JAR SHA-256 `fc6cec94b751c728c3c7e0e69447e3cd13085d2bb1ea9eb6ebdc93031e4414b8`；发布目录 `serial02-qdrant-auth-20261030` 全manifest核对通过，旧版未覆盖。

已重启原独立空 Qdrant 容器 `34f889565773ebdeb7a311aa19e9f518ce7513a504dbbc558e58a08ac20fbbf4`，停止旧 App 后以新发布目录及私有磁盘配置启动 App 容器 `75cb0d5d6b5aac51471c5595fafa6776df5d4b37c351ebf32a7d4158d8d10084`；H2工作副本容器继续运行。当前专用 Docker 恰 App/H2/Qdrant 3个运行容器；新 App PID20392、Qdrant PID20174 的实际 UID/GID均10001:10001。新 App `GET /api/system/health` 返回 `vector.provider=qdrant`、`vector.available=true`，同一Qdrant无凭据 `/collections` 返回401，App按前述测试项目ID读回HTTP200。私有外置配置 mode0440、root:10001，密钥不在启动参数中。此结果证明 App 通过鉴权向量服务做了 GET 可用性检查，不等于 App 已完成向量业务写入、查询结果或跨重启；历史索引迁移、真实原件、LOB/触发器、私有路径及完整业务仍未验，02不关。旧 Qdrant attempt1 第三次写入 unknown 与旧 H2 90108 原失败均保留。

## 2026-10-30 11:00 +08 集合级写读与私有路径点查

对隔离测试项目调用现有 `GET /api/advice/{projectId}/index/status`，响应 `code=0`、`vectorProvider=qdrant`、`vectorReady=true`、`collection=consense_evidence`、`indexed=0`、`chunks=0`；App 日志确认本请求创建 1024维/Cosine 的 Qdrant `consense_evidence` 集合。这是 App 到 Qdrant 的集合级写和计数读，不能替代向量点 upsert、检索或真实 embedding。直接 HTTP 请求 `/runtime/config/application-h2.yml`、`/artifacts/app.jar`、`/app-files/sha256sums`、`/runtime/data/app/uploads` 均 404；`/%2e%2e/runtime/config/application-h2.yml` 为 400，响应正文未输出。仅证明这五个探针未暴露文件，不能据此称完整私有路径审计通过。02仍开放。


## 2026-10-30 11:12 +08 历史原件路径实证

2b历史项目第10号原件在旧App的reader-original请求先返回业务4104。资产登记SHA e3947e23ff30c58d61eaf75df5d68d575298506b3b940c8ab31f23e31531e98d绑定44份封闭原件副本；本轮逐件核来源/目标SHA，复制100,563,992字节到隔离实例。copy-receipt.json SHA 7eebf5535a1e178a0cc5bb4dfea324b5a8df5e0f71657b558e7c6e82aeaadc33；旧路径映射SHA dd883855e9b78f5d10db44072a587fa63559e26909f2c2384c3d5f5510f183f9。文件root:10001/0440、目录0550，经Docker只读nested bind进入App；配置映射也以root:10001/0440只读挂载，Java进程PID48036 UID/GID10001:10001。旧App75cb0...已按名停止，exited143/PID0/OOMKilled=false；新App6dad11e19e206469eacb2d2db8107baf42d6cdaf9c1fa6ec9907017363d874ce运行。相同第10号原件按original/routing/business三哈希请求返回DOCX HTTP200/63,049字节；静态路径GET该原件404，真实H2测试项目详情HTTP200。App/H2/Qdrant当前3个运行容器。独立运行回执见 .local/container-rehearsal-20261029/serial02-historical-assets-20261030/runtime-readback.json。这只证明单个历史原件及映射接缝；其他43份API读回、全部历史关联、生成LOB、向量点写入/检索、真实embedding、长期稳定及完整业务未验。所选2d/2e项目的三个Drafting模板位为固定missing槽位，与2b项目35条Vetting原件记录不同，不作为恢复失败计数。02保持开放；旧H2 90108、Qdrant unknown、GPU释放unknown保留。
## 2026-10-30 11:35 +08 历史原件批量业务读回

主控在当前App/H2/Qdrant三个运行容器上顺序读取4个历史项目的Vetting `/files`，共列出43条来源；对每条使用列表返回的 original/routing/business 三个身份字段调用当前原件接口，逐条核响应字节长度、正文SHA及 `X-Source-SHA256` 与封闭副本登记一致。41条完全匹配。第41、42号的列表状态为 `EXCLUDED`，两次调用均为HTTP200内业务code4104，保留预期范围拒绝；不把JSON错误正文当作PDF。44份封闭副本中的第39号未列入当前来源列表，登记SHA与第40号相同；当前只确认未列出和字节相同，未推断旧记录的数据库状态。原第一轮脚本误将这两条业务错误记为字节不符，回执 `all-reader-originals-readback.json` SHA-256 `6adfb5536d7800b1d18fcc1996e25c4f266884ab9084cc938f62309e86251606` 保留；修正分类后第二轮 `all-reader-originals-readback-v2.json` SHA-256 `28ccc537cd6fdd86e49259184c3c81a4738164ea473b4b615b67be884b9c4b52`，探针exit0。两轮均未写业务库或替换原件。原件读取接缝得到更完整证据，生成BLOB、App向量点写读、真实embedding、长期稳定及完整业务仍未验，02不关票。GPU释放unknown、禁止自动重启。
