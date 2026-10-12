# OER 业务证据接缝：来源生命周期与完整单元验证

日期：2026-10-10，Asia/Shanghai。执行边界遵循 [范围规则](../oer-scope-and-rules.md) 与 [实施交接](../HANDOFF-ocr-embedding-rerank-20261008.md)。本轮是业务接缝的源码／契约验证，未切换在线 Java、数据库或前端。

## 本轮实现

`OerBusinessEvidence` 在当前选定文件、模块和 sealed binding 下建立 Session。完整 canonical JSON 与原生字段身份保留在 read view 旁；索引直接交给 canonical client，不把 read view 当作 `/index-canonical` 的权威输入。

Session 冻结有序 binding 身份与每个完整 view 的 JSON，包括原始 payload、pointer、unknown 和所有结构字段。来源／采用／路由变化或 view 变更后拒绝本 Session，恢复旧对象也不能解除失效；必须重新加载来源。index／retrieve 的远程调用前后均复核当前 binding。成员列表不可增删，返回的 ranked view 使用独立副本。

`CanonicalEvidenceService.pointers` 对一份来源只执行一次完整绑定及原件验证，批量产生该来源的所有 pointer，避免为每个 chunk 重复读取／哈希整份大原件。load 与后续操作仍另有边界复验，不宣称每个业务请求只读一次原件。

canonical 的 XYXY 坐标完整保留在 pointer／raw payload；兼容 read view 显式换算为 XYWH。来源原字节、Java parsed hash、canonical hash 和 projection revision 不互换。原件打开引用使用 Java parsed hash；检索身份继续使用 canonical hash。

Advisory 接缝的一条 ranked seed 可以携带完整已观察 source unit 的成员、原始结构与所有 pointer。成员按当前 source snapshot 顺序呈现，不生成额外 rerank 分数或 origin。页码取成员实际物理页的并集；primary anchor 的 scope 明确为 ranked seed。unknown 单元只展示 seed，并保留 unknown trace；`applicabilityContextComplete` 仍为 null，不能据此认定项目采用。

FindingEvidence、EvidenceItemVO 与 CitationVO 增加可选 canonical pointer，保留旧构造器。Vetting 的来源弹窗 DTO 转换透传 pointer；这不表示语义审查已经使用新 Session。

## 隔离验证

复制 checkout：`.local/state/oer-business-evidence-20261010/run-011901-66c0ce`。使用专用 Java Maven Launcher／socket tmpdir／`forkCount=0`，没有编译在线 `ai_consense_service/target`。全量 main 与 test 源码编译后，执行五个聚焦 suite：

| Suite | 通过／执行 | 证明范围 |
| --- | ---: | --- |
| OerBusinessEvidenceTest | 8／8 | 作者 fixture、mocked registry/client；完整 payload、坐标／hash 区分、批量 pointer、source/view 变化、同步篡改、HTTP 期间变化、完整成员／跨页／unknown。 |
| CanonicalEvidenceServiceTest | 19／19 | 既有真实 localhost HTTP fixture 门控，加批量 pointer 的单来源 catalog/repository 读取及 stale 拒绝。 |
| OerCanonicalRetrievalClientTest | 12／12 | 作者 wire 的完整 JSON、health/module/index/payload/closure 身份。 |
| OerCanonicalSourceUnitsTest | 16／16 | 与 Python resolver 的观察语义及伪造／跨条款等拒绝。 |
| VettingDualStructureHealthTest | 8／8 | 模型配方与长短窗口身份。 |

总计 **63 项，0 failures、0 errors、0 skipped，BUILD SUCCESS**。这是各 suite 的执行数，不是业务准确率，也不与先前真实模型 queries 或 bridge test 合并。

收据：[validation receipt](../../../.local/state/oer-business-evidence-20261010/run-011901-66c0ce/validation-receipt-attempt1.json)；原日志：[Maven log](../../../.local/state/oer-business-evidence-20261010/run-011901-66c0ce/maven-test-attempt1.log)。复制源码和聚焦测试／reports SHA 由收据绑定；可复跑脚本位于 `.local/state/oer-implementation-20261009/canonical-integration/validate-java-business-evidence.ps1`。

早期失败原样保留：`run-011632-b98ec9` 的 EvidenceItemVO 旧构造器兼容编译错误，未执行测试；`run-011715-50b109` 与 `run-011804-cd14d2` 的资产篡改测试错误地假定 authored wire 的空 assetRefs 含成员，均为 63 项执行／1 error，不能计通过。最终改为显式注入未绑定资产后全部通过。真实资产字节门控由上述 service suite 及另行真实 GCT bridge 验证。

## 当轮尚未通过的业务门槛

以下列表是63项接缝验证时的断点。后续 Vetting／Advisory 接线、fresh DB复验和统一最新源码215项隔离回归见 [后续业务验证](./oer-vetting-canonical-business-validation-20261010.md)；源码完成不追认在线／真实模型业务验收。

- VettingSemanticReview／AdviceService 尚未完整消费 Session，不能回退混用旧 EvidenceChunk/Qdrant 候选；Advisory 必须使用实际 advisory 模块进程和选定 scope。
- 当前 Session 只复核固定 document IDs。LLM 完成及保存前还须从当前项目重新求 selected scope，覆盖新增不同名称的范围内来源。
- Advice 长事务、EntityManager identity cache 和 MySQL REPEATABLE READ 的 fresh DB 读取／短事务保存策略尚待实现与并发验证。fixture 中的前后复验不证明真实数据库该门控已解决。
- raw canonical 结构需要通过明确分支传给旧 planner 与模型，不伪装成 legacy metadata/quality 版本。
- 前端 Views、真实项目任务、图表回取与 Q11 用户入口验收尚未完成；在线数据库未迁移。
- 可靠 native producer、全范围固定来源、残差验证／必要训练和 Linux/H800 完整发布包仍为 goal 必做项。
