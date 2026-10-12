# Native DOCX public v2：独立 Python 消费入口及索引复验

日期：2026-10-10，Asia/Shanghai。范围遵循 [OER 规则](../oer-scope-and-rules.md)。本轮接入 actual-CAS native transport；没有执行官方问题、真实原件法律质量评分、权重训练或 H800 部署。

## 实现与运行边界

新增 `tools/vetting_eval/native_source_registry.py`、`oer_native_server.py` 及各自聚焦测试。原 `oer_server.py`、`oer_source_registry.py`、`server.py`、`window_runtime.py` 保持本轮检查的旧字节；没有修改正在运行的扫描入口，未重启 8080／5173／8868／8092 或隔离 Advisory 18081／8869。

扫描 v1 继续使用其完整双解析 producer／原页 bbox／完整表组门控。新入口仅把确定的 public v2 接入实际 CAS，不接受 full internal view 冒充 HTTP snapshot，也不通过 Word 假页或缺失视觉资产满足扫描条件。

接口固定为 `consense.canonical-projection.v2`／`native_docx`／`cas-native-observation-adopted-public-v1`／`source-reviewed-native-cas-public-projection-v1`。`/project/native` 先运行独立 native producer，再捕获 compact public transport，复验当前 scope policy 后发布不可覆盖的 snapshot。已有 `/project` 扫描路径保留。

`/health` 的 `oer.nativeCanonical` 明确声明上述 schema、producer、version、storageShape、`projectionEndpoint=/project/native` 和 `artifactReadValidation=actual-cas-every-read`。六个新增 native 算法实际文件 SHA 纳入 runtime 及 Java handshake；导入后源码变化使进程拒绝旧身份。health 不加载 tokenizer／神经模型，不认证原件或法律质量。

启动仍采用 CPU FP32 的既有冻结开发配方；这不是最终性能优选或 H800 配方。新入口缺省端口 8870、缺省独立状态 `.local/oer/native-retrieval-state`，拒绝旧扫描默认 `.local/oer/retrieval-state`。Advisory／Vetting 两进程实际启动时分别给显式状态目录与 module，不能混用一份持久索引。当前没有启动新入口。

## 持久索引不能绕过原始证据

registry publish 为准确 `projectionRevisionSha256` 建立不可覆盖的硬链接定位；内容仍是原始 sealed public snapshot，不是可信缓存或另一套 manifest。原件、adapter、原始 observer 输出、所有 ZIP parts、reference、internal snapshot、bundle、table inventory／nodes 和 transport 源码均留在 CAS。

读取 native snapshot 每次运行完整实际 CAS 验证及 public 重建。索引 signature 前按每份 native revision 重新定位并加载准确 snapshot，要求持久 parent JSON 的全部内容、完整集合与源顺序和已发布 chunks 相等。未登记的直接 `/index`、少 parent、错序、改变文字或 quality 均拒绝；不只依赖 `/index-canonical` 的初次准入。

`server.read_project` 在 query inference 前计算该 signature，因此内存索引和从磁盘恢复的 metadata 同样触发源复验。检索结束后再次复验，源在长 query 过程中变化时不返回结果。没有跨请求验证缓存；大型真实源的冷验证与序列化成本仍须测量和优化，不能把人工 fixture 的时间外推。

独立审查发现一个实际绕过：仅删除 convenience storage／geometry／part.native／part.sourcePosition 标记，仍保留 native version／refs／sourceSpans 的 parent 会逃开最初 detector。旧实证保留在 `.local/state/oer-native-registry-read-audit-20261010/marker-strip-025334-a33308/receipt.json`（SHA `6281769d53ead7048f382cf71cf72d62bc5c7edda91de08375b8d4a86d5cfcc7`）：fake `/index`、内存／磁盘 `/retrieve` 曾返回 200，原 CAS 已坏时 snapshot 为404。没有真实模型调用。

现 detector 使用独立声明信号：schema／producer、native projection／metadata version、storage、geometry、Parts／sourceSpans 的 native ref／position／channel／block ID，以及 native table topology。相同 partial stripping 现在报422 `NATIVE_INDEX_PARENT_MISMATCH`，不会降级为 legacy。真正没有 native 声明的历史来源不被冒称 native proof；Java typed contract 继续限制其 canonical 接入。

## 当前验证与证据

最终联合运行：`.local/state/oer-native-python-consumer-20261010/run-025539-c07d62/validation-receipt.json`，**115 tests，0 failures/errors/skips，exit0，源码 SHA 无 drift**。实际运行目标列表和全部 Python 源 SHA 留在收据；包含新 native registry／HTTP、transport／producer、capture／source-reference以及旧扫描 HTTP／registry 回归。它们与之前的113、86、175等运行有重复，不能累加为独立质量样本。

actual authored compact fixture 共90,153bytes，位于 `src/test/resources/oer/canonical/authored-native-public-v2.json`，SHA `9e50fe75904a6b92c8a082f036f6d5985adc6f02305e6a01d161d95791ab8182`。它是由真正 Java observer、native capture、producer／transport产出的人工 OOXML wire，不是官方原件 gold。HTTP用 TestClient，BGE／vector provider用明确 fake；请求不触发真实 neural或外部服务。

独立最终七项 probe 位于 `.local/state/oer-native-transport-20261010/entry-readonly-audit-025556-87b36f/audit-receipt.json`（SHA `5bd64d9f903d782f439cf1a777a294db62d74f039d8dc160adea7dc4b5cabf92`）：partial stripping、CAS损坏、quality漂移、未登记、缺 revision alias均在fake model前拒绝；新 app/runtime从磁盘正常读200，继而删除原package CAS后冷读422。两源码 SHA 与本报告版本相符：registry `ea255bdb44157f1dd045657fc7c956368541dcaad6d80460b309c7e9cb0e5279`，entrypoint `277039d3feffbe76f0877dd62556bdbc831e4f88f7fdc00f799da980b37c1102`。

一次115项运行的唯一失败来自 Windows TEMP 的 `ADMINI~1` 短路径与 `.resolve()` 后长路径比较；测试改为比较准确解析后的路径。失败收据 `.local/state/oer-native-python-consumer-20261010/run-025428-0ea84e/` 保留。Starlette httpx弃用提示保留，没有因此变更在线依赖。

Java consumer 的94项代码验证另见 [Java报告](./oer-native-java-consumer-validation-20261010.md)，前端50项及真实GCT回显另见 [组件记录](../../../ai_consense_web/docs/canonical-evidence-panel.md)。当前新 native入口→实际CAS→运行Java→真实原件→前端尚未贯通，不能把三个独立代码测试合成业务验收。

## 必须继续处理的门槛

[compact transport报告](./oer-native-cas-public-transport-implementation-20261010.md) 的 capacity-only 准确下界表明完整PRE必超16MiB；分批须保留完整采用单元／原表组，当前未实现 sharding，不能提高cap或丢掉blank／nested来算支持。原生表inventory完整也不证明 rendered grid／header／最终有效编辑态或法律适用。

Q10原生视觉 mapping／实际图表返回仍未核验。完整原件参考、实际来源登记与BGE／M3贯通、跨页全表、Q11前端、[官方八题与Vetting报告](./oer-official-business-acceptance-plan-20261010.md)、残差调参／必要训练以及冻结Linux发布包继续为 active goal。当前没有权重训练或最终部署包。
