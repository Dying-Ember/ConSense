# 冻结 Round06 全量后端与独立环境补证

全量默认发现正式结果是 **BUILD FAILURE**：359 个 suite、3,759 个 testcase，3,660 通过、9 failure、14 error、76 skip；Maven 总数与完整 JUnit XML 节点逐项一致，exit 1，Maven 用时 42:07。不能把后面的独立补证或新版定向测试写回为这次全量通过。

运行从主任务冻结 Round06 项目复制全部 1,482 个源文件，逐字节／SHA256 核验，源快照身份为 `534f0925749971bec2fc6bb7325aa7a841da98537c1ef17dccce4fc5725abb52`。不从工作树拼接代码，不复制其 target，不排除原六个 H2 恢复测试。16 GiB、`forkCount=0`、专用 TEMP，显式三份真实 Word 原件与现成 LibreOffice。完成后再核全部 1,482 源，差异为零。没有部署、ACL 修改、新 OCR 或真实模型调用。

完整全量与原 Round02 对照：

| 原 Round02 身份 | Full06 实际结果 |
| --- | --- |
| 3,565 个原通过 | 3,564 仍通过；1 个旧正确用例变为失败，保留回归记录。 |
| 22 个原 failure | 12 个同名用例通过；8 个仍 failure；2 个旧用例名被已确认规则替换，不能写作旧同名通过。 |
| 14 个原 error | 全部 14 仍 error，未排除／改写。 |
| 79 个原 skip | 3 个变为通过，76 个仍 skip。 |
| 当前新增身份 | 81 个全部通过；移除的 2 个旧名字与新增 81 个身份使总数净增 79。 |

Drafting 长链路中，真实原件 `DraftingArtifactIntegrationTest` 28/28（含实际 PDF 分数同页门）、`DraftingReviewSourceIntegrationTest` 7/7、`DraftTemplateGraphServiceTest` 36/36 均为本次正式 XML，0 skip。所有其余非通过和条件 skip 仍按各自原身份列在 failure ledger。

全量 23 个非通过分布：

| 类／门 | 本次非通过 | 已确认的状态 |
| --- | ---: | --- |
| OerAttachmentPythonDifferentialTest | 4 failure | 未给显式原 `CONSENSE_OER_REPLAY_ROOT`。 |
| OerSharedNoteAuthorDifferentialTest | 3 failure | 未给显式原 `CONSENSE_SHARED_NOTE_FIXTURE`。 |
| OerSharedNoteBusinessPolicyAuthorTest | 2 error | 原保存材料环境未选时的 NPE；按原源补全环境后独立复验通过。 |
| VettingCurrentPythonWireTest | 1 failure | 真实 Python 子进程的三个 stderr 保存 `ModuleNotFoundError: fastapi`；所选系统 Python312 版本正确但缺运行依赖。 |
| VettingCurrentPythonWireWorkflowTest | 2 error | 同一 Python fixture 启动错误导致上下文加载失败。 |
| VettingInspectionRetrievalBindingTest | 4 error | `File scope/size differs`。本次 JVM 的 `java.io.tmpdir` 为相对路径；原测试按 workspace 校验绝对描述符。独立补证使用绝对专用 TempDir，未改验证器或断言。 |
| VettingProcessRecoveryTest | 6 error | 停止测试自己创建的子进程后，`UPDATE vetting_run SET lease_until=?` 报 H2 90097 只读，全部原票保留；没有改 ACL 或额外复跑新恢复子进程实验。 |
| AdviceDocumentScopeIntegrationTest | 1 failure | 唯一旧 pass→fail：自然中文流程图题只提供英文回答 fixture，code 4205。后续 Advisory08 另有显式英语题意的 fixture 修复，不能抹去本次回归。 |

两个旧名字的替换有明确证据映射：

- `DraftApplicabilityEvidenceTest#allCatalogueConditionsApplyToCollectionsAndScalars` 的还款／影印费标量条件仍由 `catalogueScalarConditionsStillApplyToRepaymentAndPhotocopyRates` 检查；消防／电梯 BS 类别按用户本轮确认，在 NSC／BSSSC 两种指定方式下都可选择，由 `designationDoesNotSuppressIndependentlySupportedBsTradeSuggestions` 逐种检查。两个新用例在 Full06 正式通过。
- `LlmSourceSelectionIntegrationTest#unconfiguredSelectedModelActionFailsButItsFailureHistoryAndInputsStayReadable` 按“局部已核结果可用、人手完成”规则，换为 `unconfiguredSelectedModelLeavesPartialHistoryAndInputsReadableForManualCompletion`。新用例在 Full06 正式通过，同时保留 `partial_source_failed`、`model_call_failed`、`missing_api_key`、输入可读和 provider 零调用断言，没有把缺钥匙当模型成功。

76 个 skip 的逐项原因、原 XML 哈希与用例名完整保留。主要是未选择私有保存原件／冻结快照／语言来源／家庭回放及实际模型材料目录，并非全部本地模型已验证。`DraftingDocumentBindingsIntegrationTest` 的四个 skip 使用独立属性 `bindingSourceDir`，随后单独补证如下。

独立 bindings4 从完全相同 Round06 源派生，没有源码变更。三原件保留各自完整字节，复制为 `NTT-source.docx`、`SCT-source.docx`、`SCC-source.docx`，映射和原 SHA 均登记；显式 `consense.acceptance.bindingSourceDir`、短 renderer root 与独立 H2／target。正式 4/4、0 failure／error／skip，suite 311.429 秒，BUILD SUCCESS；结束后全部 1,482 源、三份原件与副本再次核同。它补的是原四项未执行门，不改原 Full06 的四个 skip。正式07的51选择器原本没有这四项，没有重复它们。

独立 **round09-wire 环境补证**同样固定 Round06 的全部 1,482 源，0 产品／测试代码变更，与另一个 Vetting09 业务 shape 补丁无关。先核旧关闭 receipt：它使用可变主 target/classes，115 项全部启动，109 成功、6 个 H2 失败；原 16 个环境身份的正例按完整类汇总与字节码元数据推断，缺本冻结源逐 case XML。保留旧记录后，才补当前同冻结源的正式证据。

环境补证使用已经存在的 `.local/oer/venvs/retrieval-gpu/Scripts/python.exe`，实核其 FastAPI／Pydantic／Uvicorn／multipart 依赖，不安装、不启动该服务；显式选择原 replay root、原 shared-note fixture 和冻结项目内现有 wire fixture。四份保存 JSON 的完整字节分别与 Java 原硬编码 SHA 一致，没有新造 fixture、OCR 或模型结果。绝对专用 TempDir 与原 scope/hash 门保持。

其六类正式结果为 **109/109、0 failure／error／skip**，BUILD SUCCESS，56.987 秒。完整 JUnit XML 逐一映射 Full06 原 16 个环境失败身份：两个动态工厂保留全部具体子例；一个 BeforeAll 类级失败以新 XML 中真实四个方法执行成功记录，不虚构“类名 testcase 通过”。原 NPE 和 scope/size 在同一源代码下通过，因此没有修改或放宽其代码。

两份 Python `server_final.json` 记录总共 60 次测试拥有的 loopback 请求；实际 index／neural forward／tokenizer／vector DB／OCR 均为 0，`modelVectorTokenizerEnginesLoaded=false`，未创建 unused model state；共享 18084 网络请求为 0。该证据验证当前 Python wire、保存材料和 Java 消费契约，不能称 GPU／模型质量或业务验收。六个 H2 90097 原 error 没有被该六类选择器选中，仍保留在原 Full06。

独立证据入口（均在忽略目录 `.local/e2e-20261020/`）：

- `build/round06-full-summary.json`：正式 359／3759 总数、完整新旧用例身份对照、1482 源复核、完整日志与全部报告哈希。SHA `f6c2d549aa806536c2781c91dc6f7d1b4f1eccc142079e303e72a3bab1043e2b`。
- `build/round06-full-project-snapshot.json`：完整源身份清单。文件 SHA `6396a1f9beab05d4cb463ca2292656b98368128c6d42137dd9246609771f943a`。
- `build/round06-full-final/`：完整原 Surefire reports；`round06-full-maven.log` 为原 Maven FAIL 日志。
- `drafting/round06-full-failure-ledger.json`：23 非通过、76 skip、旧正确回归、两项规则替换映射和后续 Advisory08 引用。SHA `c22c3e3d1c9541aa515b44a41d135406b352400c909242dc92bdd6231a7d955e`。
- `drafting/round06-bindings4-summary.json` 与 `build/round06-bindings4-final/`：四项正式门、原件改名映射及源／原件复核。摘要 SHA `19fb9571c6f65b01d7140e850793a153c8c819d6fc614175cd5cceb03e2831a6`。
- `drafting/round09-wire-summary.json` 与 `build/round09-wire-final/`：109 项完整 XML、16 旧失败身份映射、4 原 fixture、2 Python 结束计数与源复核。摘要 SHA `6443e38af842ffc753ca6144e1d494c636f5dfd2e4659d4fa76286749011b112`。

这些结果分别是原全量红票、同源环境补证以及后续版本定向修复；不合并成一个虚构的全量绿色批次。真实三模块 GUI／MiniMax 调用、来源质量和用户业务验收仍由主任务分别登记。
