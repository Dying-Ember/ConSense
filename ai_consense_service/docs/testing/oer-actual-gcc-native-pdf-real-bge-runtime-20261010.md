# GCC 六页 actual native PDF：隔离真实 BGE 检索及来源校验性能

2026-10-10，Asia/Shanghai。使用 [六页实际采用记录](./oer-actual-gcc-native-adoption-validation-20261010.md) 的实际原件／字形／完整双解析 CAS，在新 owned `127.0.0.1:8873` 验证 Python 来源登记→真实 BGE index→两次 source-only retrieve。本次没有 Java 项目绑定、LLM、官方题目、前端或 H800 验收，原件质量和整个 GCC 的覆盖仍为 unknown。

## 运行与冻结身份

绝对运行目录：`D:\R&D\ConSense\.local\state\oer-actual-gcc-pdf-runtime-20261010\run-214314-dd5f5c`。

| 证据 | bytes／SHA-256 |
| --- | --- |
| [real BGE receipt](../../../.local/state/oer-actual-gcc-pdf-runtime-20261010/run-214314-dd5f5c/real-bge-receipt.json) | 22,181／`b6896150ecf78a211ff685d09586e1f42da3bac36d348f9d43ac2f79fe2faf0a` |
| [runtime manifest](../../../.local/state/oer-actual-gcc-pdf-runtime-20261010/run-214314-dd5f5c/runtime-manifest.json) | 41,697／`511f741f0700e53301e27805cf5291e564c6b7bb1088c639942bcb104b45c2b3` |
| [final ledger](../../../.local/state/oer-actual-gcc-pdf-runtime-20261010/run-214314-dd5f5c/final-artifact-ledger.json) | SHA `bd05e480c4a9f488fa490b58df1fa78ba0e1bf980e9b0f2e2fc34b897b5b1c9f` |
| [compact journal audit](../../../.local/state/oer-actual-gcc-pdf-runtime-20261010/run-214314-dd5f5c/journal-audit-compact-v2.json) | SHA `6ee3af35daf57d80763e6971ca2835e435aae967d7577e01a7cb8688aee8ace7` |
| [final health response](../../../.local/state/oer-actual-gcc-pdf-runtime-20261010/run-214314-dd5f5c/health-after-response.json) | 9,366／`5c3b9e0375ed25ff0cdf5b9c6fa4cf0dc6cbd6a1e05eac939964a1f3af752a5e` |

130 个 Python 源码／samples 配置文件在复制前、复制后、冻结副本逐项 SHA 相同，probe／profile 后再次核对冻结副本没有变化。独立源码位于 run 的 `code/`；CAS 位于 `retrieval-state/artifacts/`；registry 位于 `retrieval-state/canonical/`；Qdrant、项目 metadata 与请求 journal 均在此独立 state。权重复用已存在的离线 HF cache，未下载或修改权重。

冻结 producer SHA `bf75da59df2c696cd56b7628b104d89ea12f57c783bfd5e9a4cc06f4de991416`、transport SHA `60c969f2e11b490b7618f973c592c584a62b971046298daa403910c048c05d23` 不变。PDF server 本轮冻结为 `05654e79df6cc30e51b783053ba61df5e4b0eda9441a97514ddfce0058f1f80f`，包含另一并行任务已新增的可选 factory seam；本次使用普通 PDF 默认分支，没有启用 unified collection coordinator。

`process-receipt.json`／`process-final-receipt.json` 保留 wrapper PID **65848**、listener child PID **120388**，UTC CreationDate 分别为 `2026-10-09T21:45:06.4579310Z`、`2026-10-09T21:45:06.6289370Z`，精确冻结 script 路径在两份 receipt。启动前再次核 `8873` 空闲，使用 Hidden 窗口启动；没有终止已有进程。`listener-receipt.json` 逐项核对旧 8080／5173／8092／8871／8872 的地址、端口和 PID 与启动前一致。

## Scope 绑定产生的新投影

既有 preparation 使用自有六页 scope-policy；`/index-canonical` 要求采用清单的 `scopePolicySha256` 与实际进程 competition policy 完全一致。因此本轮保留旧 snapshot，唯一派生修改为 `/scope/scopePolicySha256`，绑定冻结的实际 SL scope manifest SHA：

`bb1512de4b16bfa2c10f03a8197b0c798c105e3359fc649d35391f6fcb66e3d6`

采用页码、字形、分隔、读序、关系、whole units、质量和 documentId 不变。由冻结 producer 重新 produce／public publish／cold validate／register，没有放宽 scope gate 或直接改 sealed public JSON。旧准备身份与本次索引都保留。

| 本轮身份 | SHA-256 |
| --- | --- |
| public registry address | `d6feb22a6c439e85938240e98ec302fd72bc96e212eb1c109ce3f37e921c871c` |
| projectionRevisionSha256 | `f50ec9e30b6957aaeb894f73e3691535382adca8415e3f0599ff5240dea268a6` |
| canonical sourceHash | `a0fb872e7c7223c90c798d2ee2ad7eb344260d75a3ca9b44976011ab1af75456` |
| actual index signature | `044538c3a0e8e61829b7d040f0a3065e5b1de7dfdb1d4173bfb5338a8a6e1ade` |

target module=`advisory`；投影继续声明 Vetting／Advisory eligible。当前 documentId 仍为 preparation 的 `actual-gcc2013-standard-native-physical65-70`，不是 Java DB documentId。

## 实际模型与结果

初始 health 两个神经模型都未加载；完成后 embedding／reranker 已加载，OCR 未加载。实际 tensor journal 的 `window-model-loaded` 记录 `cpu`、`torch.float32`：

- BGE-M3：`BAAI/bge-m3@5617a9f61b028005a4858fdac845db406aefb181`。
- Reranker：`BAAI/bge-reranker-v2-m3@953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e`。

按已有明确 OER CPU FP32 recipe，短窗口 512／overlap64，长结构与结构复评 2048，batch size 2，offline=true；没有改成 GPU 或恢复 Bonsai。

实际 `/index-canonical` 50 parents 生成 **101** embedding windows，encoded=101、fixedCacheHits=0、cached=false。保存的 metadata 的完整 parents／顺序与 published public chunks 相等，signature／window manifest 与回复一致；无旧向量缓存冒充真实调用。

两题均由这六张原页制定 source-only 问句和最小必要单元集，非官方题，未读取 2d／2e。候选池各 50 parents，同一 query／同一池做 51 short baseline pairs 与 50 structure pairs，contextUnavailable=0。实际结构与短窗口比较不等于已证明排序改善。

| Source-only 题 | 最小必要 whole units 及返回 rank | 结果 |
| --- | --- | --- |
| DARA／senior management 未解决后的替代程序、short form arbitration 与 arbitration，时限和书面拒绝条件 | 17.1(12) rank3；17.1(13)(a) rank1；17.1(14) rank2；17.1(15) rank7 | 四个最小单元都在 top8；跨页 12、完整 14 列表／尾句随单元返回 |
| Contractor／CM／Surveyor／Employer 的通知送达方法、地址及传真条件 | 18.1(1) rank5；18.1(2) rank2；18.1(3) rank3；18.1(4) rank1 | 四个最小单元都在 top8；18.1(1) 保留完整嵌套送达列表 |

全部返回 hit payload 与 published parent 完全相等；每个 sourceUnit 对冻结 `SourceUnits` 重算结果完全相等，成员各自仍是实际 parent。`qualifiersComplete=unknown`、`semanticScopeVerified=false` 保留；本次最小必要集 **2/2** 完整不能写成两个法律答案都做对、全部合法依赖闭包或正式准确率。

第一题还有侧栏标题／邻近正文进入 top8；第二题 18.1(1) 在 rank5。侧栏用于相关性定位，不变为合同义务。完整排名、分数与局限均留在 actual response／receipt，没有以零遗漏的最小集结果掩盖排序噪声。

## 请求与 token 证据

3 份 request journal 全部 completed，receipt 的 request／response／events SHA 和 bytes 可复验；events 连续 ordinal、完整 newline、日志 response 与实际 HTTP JSON 逐对象相等。原始数组与 full candidate／pair manifests 均留在 `retrieval-state/run-evidence/`；compact audit v2 只保留计数，原 audit v1 的重复 pair arrays 未删除。

| 请求 | events | actual tensor rows | 最大实际完整 tokens |
| --- | ---: | --- | ---: |
| index | 56 | embedding 101 | 646 |
| dispute retrieve | 64 | embedding 1＋reranker 101 | query 53／reranker 699 |
| notices retrieve | 62 | embedding 1＋reranker 101 | query 46／reranker 692 |

全部实际 tensor rows 的 `effectiveTruncation=false`，完整 token 数没有超过对应 cap。646／699 这类结构输入超过短窗口 512，但低于实际长结构 2048；不能只看旧 health 的兼容 `maxTokens=512` 字段判断这次结构输入被截断。无 M3 或其他 LLM 请求。

## 时间与单次来源性能剖析

下面是本机 CPU FP32 开发测量，不推算 GPU／H800 或 LLM tokens/s。HTTP wall time 来自客户端 perf_counter；内部 time 来自实际服务 response，二者相差部分包含前后来源 gate／tokenizer／signature 与传输，不能全部叫“模型慢”。内部 index／retrieve 也包含计划、向量／检索与其他 CPU 操作，不是纯神经时间。

| 请求 | HTTP wall seconds | 服务内部 seconds |
| --- | ---: | ---: |
| canonical GET／实际 CAS cold read | 31.351 | 无神经调用 |
| index | 80.192 | 27.884 |
| dispute retrieve | 77.434 | 46.679 |
| notices retrieve | 86.096 | 51.698 |

从初始 health 到最终 health 全链 **275.258s**，不包含此前离线 capture／reprojection／启动准备。index 前后差约 52.308s，两个 retrieve 差约 30.756s／34.398s；这些差值是实际 gate 等边界开销，不是额外的 GPU 模型推理。

另外只做一次离线、冻结代码、同 CAS 的 `NativePdfCanonicalSourceRegistry.load` cProfile，没有新 HTTP 或神经调用、没有清 OS cache、没有取消 cold gate／引入 validation cache。

- [profile receipt](../../../.local/state/oer-actual-gcc-pdf-runtime-20261010/run-214314-dd5f5c/profile-215220-3f5d79/profile-receipt.json)：15,155 bytes，SHA `f953b99726dd612f7f79d5a18fab6c6d118a9623d75df51cc6546b002dd92885`。
- [完整 cumulative top20](../../../.local/state/oer-actual-gcc-pdf-runtime-20261010/run-214314-dd5f5c/profile-215220-3f5d79/cumulative-top20.txt) 与原 `.pstats` 保留。

一次 instrumented load 为 **21.500s**、40,975,686 calls／35,584,999 primitive calls，完整 public 对象相等。主要调用链及累计时间：

| 函数 | calls | cumulative seconds |
| --- | ---: | ---: |
| hydrate_native_pdf_transport | 1 | 21.449 |
| validate_native_pdf_projection | 1 | 18.541 |
| _assemble | 1 | 16.443 |
| _dual | 1 | 6.972 |
| ArtifactStore._read | 4,873 | 6.494 |
| deepcopy | 4,985,798 | 5.683 |
| ArtifactStore.verify | 4,015 | 5.166 |
| _rebuild_run | 14 | 4.557 |
| _run_completeness | 52 | 4.299 |
| JSON dumps | 1,960 | 4.001 |

累计项互相包含，不能求和。self time 最高项包含 JSON iterencode 3.994s、deepcopy 2.895s、file read 2.355s、Windows `_getfinalpathname` 1.673s、JSON decode 1.231s。结果能指向重复完整读验／双路重建、深复制、序列化和路径复验；不能只归因于磁盘带宽不足或本机 GPU 不足。cProfile 有插桩开销，OS cache 未清，21.5s 不替代服务延迟或硬件吞吐基准。本轮不实施性能修复。

## Java fresh ID 绑定交接

后续独立 Java upload／持久 H2 reopen 成功后，GCC 仍复用上述实际原件、native／dual／source-reference CAS。必须取同一轮 fresh 成功上传的 actual documentId 与 `VettingCorpus.sourceHash(document)`；失败 DB、历史 marker 或 preparation ID 不作实际绑定。

新的 adoption 只把 documentId 改成 actual Long ID 的字符串；使用相同冻结 scope SHA，重新 produce／public／cold validate／register，不覆盖本轮的 snapshot／index metadata。当前 `CanonicalEvidenceService.ReviewedRegistration` 的七个 SHA 字段为 `projectionSha256`、`originalSourceSha256`、`javaParsedSourceHash`、`sourceRevisionSha256`、`scopePolicySha256`、`adoptionManifestSha256`、`reviewReferenceSha256`，`module` 另列。分别取新 producer identity、实际原件 SHA、fresh Java sourceHash、native revision、冻结 policy SHA、新 adoption exact bytes SHA、source-only reference SHA；fresh role／sourcePurpose／eligibleModules 同时对照。

此段是精确接入流程，尚不把 Python 窄链路追认为 Java／官方题目／Vetting 报告接通。GCC Java 全 74 页若存在 partial 页面，其实际状态必须继续保留；本次 65–70 全部 observed 的独立 adopted source 不能将全件 PARTIAL 改写成完整。
