# 真实 Advisory bridge 的最短复用路径：只读调查

日期：2026-10-10，Asia/Shanghai。调查发生于 [Advisory 接线的 38 项隔离测试](./oer-advisory-canonical-implementation-validation-20261010.md) 之后。只读现有源码、配置、历史 probe 和 GCT reviewed request，并逐字节核查其 CAS descriptors；本文新增记录，未改生产代码／配置、启动新服务、连接实际数据库或调用神经模型。以下是待执行路径，不是运行验收。

## 可直接复用及必须区别的入口

共享当前源码已有 `OerBusinessEvidence`、Advisory 业务消费、module-specific retrieval URL、current binding／pointer／asset 和历史冻结。真实原件入口可复用 [上一轮 Java→Python bridge](./oer-real-java-python-bridge-validation-20261010.md) 的完整 GCT PDF、原生 parse、24 个 source-reviewed page-1 chunks、原 raster 与完整 parser captures，不需要为接口接通重新跑 Paddle／MinerU或修改采用质量。

旧 `.local/state/oer-java-python-bridge-20261010/run-reviewed-bridge.ps1` 固定过往 client／closure SHA，当前 client 已加 index status；它会正确拒绝新源码，不能删掉 hash 门控或覆盖旧成功证据以继续使用。应创建新的独立 opt-in launcher，绑定本次新 SHA、复制 checkout、run root 和终端收据。

旧 `RealCanonicalJavaPythonBridgeTest` 为 `@DataJpaTest`，source 在长测试事务中未提交，直接 `new CanonicalEvidenceService(...)`，Java controller 使用 standalone MockMvc。这个组合不能验证当前 injected `REQUIRES_NEW` reader 或 Advice HTTP业务。最短真实 HTTP 验证改用复制目录内 opt-in `@SpringBootTest(webEnvironment=RANDOM_PORT)`，真实 Spring beans／proxies、真实 H2/JPA 和 HTTP客户端；测试本身不加宽泛 `@Transactional`，seed保存必须先提交，否则独立 reader看不到 source。

## Python module／状态隔离

`oer_server.py` 接受 `CONSENSE_OER_TARGET_MODULE=advisory`，`/index-canonical`明确拒绝与该 target不同的请求。现有 `scripts/oer-local.ps1` 无 module／port／data参数，receipt和8868均固定，默认target为vetting；本次不能拿它启动并声称Advisory已接通。

新 local launcher使用已有 `.local/oer/venvs/retrieval/Scripts/python.exe` 和当前 `oer_server.py`，独立空闲端口（8869仅为拟用值，执行前核验）、独立 `CONSENSE_RETRIEVAL_DATA`、独立process receipt、logs和request journal。保持当前CPU FP32、短512／overlap64／结构2048的已固定recipe，先验证业务桥接，不在本轮顺带改精度、预算或启用其他模型。源码的显式OER入口和Java health握手都硬性要求这个recipe，直接设置cuda或fp16当前会被拒绝；相关精度工作需要另一个绑定新recipe与测试的版本。

必须隔离整个retrieval state：`server.database()`使用 `QdrantClient(path=STATE/'qdrant')`，不能让两进程共用已有vetting本地Qdrant目录。HF模型文件可只读复用；项目索引、Qdrant、journal和Session receipts不能冒称跨模块通用。

新state的CAS也不能为空。`/project`会核两套parser bytes、原件和review reference的descriptors，不接受仅复制JSON的hash声明。可复用 `stage-gct1-runtime-input.py` 的递归收集descriptor、source.verify、target.put_file并比较exact descriptor流程；旧脚本本身硬编码v1输入和vetting运行目录，不直接执行它。

本轮只读核查exact已存请求：

- 路径：`.local/state/oer-implementation-20261009/canonical-integration/http-development-20261009T163014Z/gct1-project-request.json`。
- 实际SHA-256：`4c02a8a9f83be6521932c435550a389fe7b5cc7a1a96ab08dbdfc29548d09bb9`。
- 递归发现167个descriptor身份、167个唯一CAS blob；对 `.local/oer/retrieval-state/artifacts` 中每项实际bytes重新核SHA和byteCount，bad=[]。此次只读，没有新增／复制blob或解析／模型调用。
- 完整原件SHA：`ca3944c16890cb5ea7f8b24e2fab613c7ffd621809f7f850529901649e6f15f8`；reference v2 SHA：`9fe521114c974a071e01d1fc74408c4e860865798f5e62f8f007fc315ee3163a`。
- manifest已声明module=`vetting`、eligibleModules=`[vetting, advisory]`。Registry允许这两个明确eligible的业务模块共用同一sealed snapshot；不需为Advisory修改质量或扩大采用范围。actual documentId仍须重绑定到本轮DB值。

## Java 配置与实际 DB ID

`application-oer.yml`只设置旧 `consense.vetting` retrieval参数，单独激活该profile **不会**启用canonical。当前 `OerBusinessEvidence.enabled()`明确要求 `consense.oer-evidence.base-url` 非空。新隔离Java应使用独立H2、storage和日志，并在run-only配置提供：

```yaml
consense:
  oer-evidence:
    base-url: http://127.0.0.1:8869
    retrieval-base-urls:
      advisory: http://127.0.0.1:8869
    timeout-ms: 120000
```

该最小单模块bridge让snapshot／asset和Advisory index都指向同一新独立Python registry，避免修改现有vetting进程或共享Qdrant。未来同时提供两模块时，base URL所服务的registry／assets和各retrieval进程均必须有其已登记snapshot及真实bytes；不能只配置map而假设两个独立state自动共享投影。

source seed最短顺序为：实际Project落库→完整真实PDF逐字节复制到该run的storage→实际 `DocumentParser` 和 `OerSourceMetadata.capture`→repository保存并提交取得实际generated ID→只替换request中的development documentId→向新Python `/project`重投影→读取exact snapshot→以实际original、Java parsed revision、source revision、scope policy、manifest和reference SHA调用**注入的** `registerReviewed`→实际 `currentBindings`复验。公开CanonicalEvidenceController是只读接口；没有通用HTTP注册入口，不应为了probe增加面向用户的snapshot／review写入接口。

按当前真实数据，这个最小project只有GCT，角色tender，因此先验收fullset／tender；它们若选定集合完全相同，可拥有同一indexKey。contract为空应诚实报告没有选定来源，不能为了展示三个scope捏造SCC binding或强行把GCT分类成contract。

## 问答、历史与实际原图的验收顺序

在隔离Java实际HTTP执行sources→status应无receipt→rebuild(fullset)→status ready→ask→messages→pointer／asset。Ask沿用已暴露的development query `What happens if the tender booklet is a photocopy rather than the original version?`，candidateLimit=24／topK=3仅为该小样本的拟用预算；正式profile仍由独立任务选择。

如使用当前MiniMax中继，独立配置加载已有私有relay credential，不打印或写入公共收据。`minimax-relay` profile只定义client配置；actual ask还必须发送 `X-ConSense-Llm-Profile: minimax-cn`，否则default仍为local。记录modelIdentity与安全configuration SHA，不用本地模型availability或仅配置存在冒称真实MiniMax回答。

回答验证至少核实际GCT3完整members进入prompt、引用ID来自该次sources、pointer中module=`advisory`和actualDB ID／source／projection身份一致，以及真实PNG的全bytes／SHA／byteCount／headers。Source质量仍unknown、humanConfirmed=false、整份第二页未接受；条件句和例外是否忠实需对照原文，不能只把grounded=true或GCT3 rank1当语义通过。

随后读实际history比较完整引用JSON未丢字段。在该隔离DB创建新同名版本或改变当前Java parsed revision，重读history应保留旧证据；旧pointer／asset应返回明确current-binding失效，不自动重链到新projection。只改变本run的资料／DB，真实原件和在线数据不动。

实际Java HTTP测试完成后，如需要浏览器验收，另启动该run独立H2/storage的持续Java实例及独立前端端口，`CONSENSE_API_TARGET`指向新Java；不覆盖在线5173的dist或8080。可复用当前前端完整引用／scope接线，但HTTP测试或独立bundle均不代替实际浏览器验收。

这一最短路径只验证已source-reviewed的GCT page1真实业务接通、当前来源与引用，不能宣称SL三页表组、整源质量、native canonical producer、留出检索、全任务正确率或H800通过。当前调查没有执行这些步骤，也没有新增运行状态。
