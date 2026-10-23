# 同能力复用第二次复查与实测（2026-10-22）

用户要求再次仔细检查重复处理大数据与同能力多实现。本次确认上一轮不能宣称全部排除，并实际补了以下生产路径。随后用户明确要求把“业务按需向公共 OER／RAG 取依据”写成 [现行 spec](../specs/oer-demand-driven-business-access-20261022.md) 并实施；本次资源修复不冒充该架构已经全部完成。

| 已修路径 | 实际变化 | 保留的约束 |
| --- | --- | --- |
| PDF 文稿列表 | 显式 JPA projection 只读 DOCX／PDF／profile 三个 hash，不加载 PDF／manifest LOB | 实际定位与下载仍读完整文件；profile 身份与错误身份拒绝保持 |
| 公共渲染入口 | JDK 公平 Semaphore(1) 统一限制本 JVM 的 LO／既有 desktop adapter 外部进程；排队计入原 deadline | 中断标志、启动失败释放及只停止自有进程树保持；不约束用户手动启动的进程／其他 JVM |
| 版本探测目录 | 本次 `profile-probe-*` 用 try-with-resources 清理，成功／超时／失败均收口 | 核本次真实根及直接子目录、不跟随 symlink；正常 `render-*` 证据不删；无永久版本 cache |
| 原件 SHA | `StorageService`／CurrentSourceReader／RoutingRefresh 共用 `StorageService.storedSha256` | 实际文件每次重读；路径真实解析／根范围、同大小同 mtime 改字节拒绝和原错误分类保持 |
| 当前 binding 查询 | 两个公共消费者使用同一按 ID 降序的 keyset resolver，SQL 每次一实体，找到适用项就停止 | foreign normal 先校验字节及 typed pins 才跳过，reviewed 共享权威保持，坏最新不回退；只 detach 已验证且明确跳过的实体 |
| Python 来源 registry | 冷 miss 使用刚解析的 wire，不再重新读根；普通来源不再保留不用的完整序列化副本 | collection 需要的派生 payload 保留；每个新 frame 仍读根，实际 CAS 入出轨迹与文件依赖不减少 |
| normal 关系装配 | 每次装配先建立有序端点索引，quality hash 算一次 | 自环一份、重复关系／顺序／空目标／空文字单元／deepcopy 及非法端点拒绝保持 |

## 实际证据

证据均在忽略目录 `.local/source-compact-hotfix75/`，没有模型、OER 服务或业务数据库调用。Java 复用既有隔离目录和 32 GiB 单 worker／整机资源守卫，未启动真实 LO；LO 进程测试使用小型 Java fixture。

- `reuse-deep-red06`：7 项，3 failure／0 error，精确复现 PDF 列表整份 LOB、排队中断和 probe 目录泄漏；另外 4 通过，不能称 7 项全 RED。
- `reuse-queue-red07`：改用队列中提交不存在 executable 的测试，旧实现实际抛 IOException，而非等待原 deadline 后超时；原较弱 marker 测试因 JVM 启动延迟假绿的记录保留。
- `reuse-deep-green08`：71 项中 70 通过／1 真实三原件夹具 skip／0 failure 或 error。实际 JPA 两条 projection 查询不含 PDF／manifest LOB；随后完整读回确认原文件仍在。18 个 converter 方法全部通过；原件改变、越界、缺文件及人工用途流程也通过。
- `reuse-history-red09`：3 项／3 failure／0 error，复现最新来源选择的全历史 LOB 查询及无 SQL limit。
- `reuse-history-green10`：11 套 161 项全部通过，含实际 SQL limit、seek、只加载 1 实体、foreign normal／reviewed 权威、坏最新不回退、fresh 新事务及真实 Spring／H2／模型 double seam。后继 9 份测试仅同步公共查询 seam 与负控；没有改用业务或模型通过口径。
- `reuse-registry-red01.log`：3 项修前失败。后继完整 88 项通过；首轮 87 PASS／1 旧预算断言失败保留，未改写为整轮通过。
- `reuse-registry-equivalence01.json`：四类小夹具完整返回、CAS 入出读取轨迹及文件依赖逐项相同。冷根严格解析从 2 次到 1 次，暖 frame 仍 1 次；普通 DOCX 90,308 B、PDF 43,497 B、normal 18,824 B 的无用派生副本降为 0，collection 所需 90,243 B 保持。
- `reuse-relations-red01.log`：8 单元／8 关系，修前端点读取 64 次、quality hash 8 次；4 项中 2 failure／2通过。
- `reuse-relations-equivalence01.json`：8／16／32 单元的端点读取为 64→8、256→16、1024→32；quality hash 均改为 1 次。五组完整 canonical 字节相同，非法端点仍以相同 code 拒绝。
- `reuse-registry-relations-final01.log`：最终组合 92 项全部通过，66.683 秒。含正常 ASGI／实际临时 CAS、family 证明、严格数字类型及 37,767,684 B 自造容量合同；不是比赛原件、真实模型或来源质量通过。

Java green08／green10 采样峰值工作集分别 2,529,411,072 B／2,389,020,672 B，整机最低可用 104,491／104,318 MiB，均未触资源停止界限。不同批次有重叠，不相加冒充唯一方法总数；本轮不重跑大 PRE 制造新负载。

## 尚未解决的架构事项

业务仍有全量 snapshot／corpus 装载、Java／Python 双份闭包核验、Drafting 的两套本地 3000 字符切窗及上下文构造、重复 normal preparation，以及跨 module 冷 embedding。既有 Word 条款范围与模板 guidance／编辑目标关系也不能被普通检索片段替换而丢失。现在按用户指定的统一 spec 改接，不继续把每个位置微优化都当成完成架构。

旧约 958 MB binding 未迁移、业务服务未恢复、真实 LO 与三模块前端尚未复验。GitHub 凭据之前实际失败，本批先本地提交并保留推送待办；三份未知 untracked 保持不纳入。
