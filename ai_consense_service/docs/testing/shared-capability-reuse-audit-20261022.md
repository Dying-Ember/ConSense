# 公共能力复用第二轮排查与修复

用户再次要求仔细核查后，确认上一轮不能给出“已经没有重复大数据处理”的结论。本轮实际补修以下生产路径；随后用户进一步明确应把业务按需使用公共 OER／RAG 写入 spec 并实施，因此微优化在本批收口，后继以[现行按需 spec](../specs/oer-demand-driven-business-access-20261022.md)为主线。

## 已实现并验证

| 位置 | 原问题 | 当前修复与证据 |
| --- | --- | --- |
| Drafting 文稿列表 | 缓存存在时，为两个 hash 加载 PDF 字节与 manifest LOB | 显式 JPA metadata projection；只查 DOCX／PDF／profile 身份。定位／下载仍读取完整文件。真实 SQL 与 wrong identity 负控通过 |
| 公共外部渲染入口 | 不同项目／模板／Vetting 可以无限并行启动渲染进程 | `DraftPdfProcess` 使用 JVM 内公平许可 1，等待计入原 deadline，超时不启动，失败／中断释放，只终止自己的进程树。未共享可变 LO profile |
| renderer 版本探测 | 每次生成 `profile-probe-*` 却不清理 | 成功／异常／超时均清理本次目录，核定 work root，不跟随 symlink。实际 `render-*` 文件和证据保留；没有引入永不失效 profile cache |
| 原件字节核验 | StorageService／CurrentSourceReader／RoutingRefresh 三份相同 realpath＋SHA 实现 | 共用 `StorageService.storedSha256`，路径与实际文件逐次核验。等长且恢复 mtime 的修改、越界／不存在／目录均拒绝；原有错误码区分保持 |
| 当前 binding 选择 | 查询首个适用修订前，先加载全部历史 snapshot LOB | 公共 keyset resolver 每次 SQL 只取 1 条、`id < beforeId` 续读，找到适用项停止。foreign normal 完整验字节和 typed pins 后才跳过，reviewed 共享权威保持，坏最新不回退；已跳过实体 detach，历史字节不改 |
| Python unified registry | 冷 miss 重读／解析 root 两次，并给不用的普通来源结果保留完整序列化副本 | 复用刚验证的 wire；普通来源只保存完整依赖证明，collection 仍保存所需 hydration。四类来源实际 CAS 入出轨迹及文件依赖完全相同 |
| normal 来源装配 | 每个 unit 遍历全部 relations，相同 quality 每 chunk 再哈希 | 单次装配建立有序端点索引，quality hash 一次；重复关系、自环、空／缺失目标、unknown、空文本单元及逐关系 deepcopy 保持 |

## 实际执行与未执行

证据均位于忽略的 `.local/source-compact-hotfix75/`，失败记录保留，不改写为通过。

- `reuse-deep-red06`：7 项中 3 个预期 failure，0 error；实际复现列表读 LOB、无排队中断约束、probe 目录泄漏。
- 初次 queue deadline 用子进程 marker 的测试意外通过，不能证明无进程启动；改用排队时提交不存在的 exe 后，`reuse-queue-red07` 精确得到旧实现 IOException 与期望队列 timeout 不符的 RED。
- `reuse-deep-green08`：6 套 71 项，70 PASS／1 真实三原件夹具 skip／0 failure／error；覆盖 18 个进程／converter 方法、真实 JPA metadata、列表、原件／用途 HTTP 与公共 SHA。只使用小型 Java renderer fixture，不是实际 LibreOffice 格式验收。峰值工作集 2,529,411,072 B、整机最低可用 104,491 MiB。
- `reuse-history-red09`：3 tests／3 failure／0 error，实际 JPA SQL 无 limit，以及旧全历史读取入口被精确拒绝。
- `reuse-history-green10`：11 套 161 项全部通过、0 skip；包括真实 JPA seek SQL／1 实体加载、跨模块权威、9 fresh transaction 方法、93 真实 Spring／临时 H2／模型 double seam。峰值工作集 2,389,020,672 B、整机最低可用 104,318 MiB。
- `reuse-registry-red01.log` 保留 3 项修前失败；`reuse-registry-suite01.log` 保留 87 PASS／1 旧预算断言失败。预算测试改按实际保留证明体积减一，仍验证不足预算时重新校验；完整 88 项随后通过。
- `reuse-relations-red01.log`：4 项中 2 个计数 failure、2 个保全负控 PASS。最终 `reuse-registry-relations-final01.log`：92 项全部通过，66.683 秒；其中 37,767,684 B authored 容量合同保持完整，0 模型调用。它不是真实原件质量验收。

确定性小夹具实测如下，不外推为真实整套材料的内存或耗时降幅：

| 测量 | 旧 | 新 |
| --- | ---: | ---: |
| 四类来源冷 frame root 严格解析 | 2 | 1 |
| 普通 normal／DOCX／PDF 的未消费派生 bytes | 18,824／90,308／43,497 | 全为 0 |
| collection 所需派生 bytes | 90,243 | 90,243 保持 |
| 8／16／32 单位环图端点读取 | 64／256／1,024 | 8／16／32 |
| 同一组 quality 的 hash | 8／16／32 | 均 1 |

完整旧新输出与证明等价见 `reuse-registry-equivalence01.json`；五组关系输出／非法端点负控见 `reuse-relations-equivalence01.json`。代码身份固定以控制变量；改动 Python 代码会使实际 producer 软件身份变化，不把不同 producer 版本冒充同一修订。

所有 Java 验证串行使用既有 32 GiB heap 和整机资源守卫，无业务 H2、模型或 OER 服务启动。当前 Goal 工具仍 paused；旧 958 MB binding 未迁移，实际 LO 小文件补验因用户要求优先统一按需接入而暂缓。未把这些测试称为三模块端到端完成。

## 剩余架构问题

业务目前仍有完整 snapshot／corpus、Java 侧闭包与 Drafting 两处本地 3000 字符切块。normal Word 条件范围与模板 observation 不能由普通检索片段替换。上传后的重复 capture／CAS、跨模块冷 embedding、同 key 进行中 PDF 转换合并及 profile 探测调用内复用也未全部处理。这些由统一 spec 约束和实施，不宣称已清除所有重复工作，不继续新增各业务专用的替代实现。

GitHub 上一轮实际 push 已被凭据失效阻断；本轮保留本地提交和待推送状态，不反复启动登录。三份既有未知 untracked 文件保持不提交。
