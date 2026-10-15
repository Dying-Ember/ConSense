# OER 离线制品准备 — 2026-10-13

本轮按 [实施规格](./specs/oer-vetting-advisory-next-development-20261012.md) 准备有限组件和可复用的依赖锁。代码位于隔离交付分支；活动服务、原件、模型和冻结 Local05 保持。这里的准备结果不等于最终 Linux 镜像、扫描或 H800 验收。

## 已准备的锁和实际核验

| 组件 | 固定输入／锁 | 本轮事实 | 未过门 |
| --- | --- | --- | --- |
| Vue／TypeScript／PDF.js／Mermaid／DOMPurify | `ai_consense_web/package-lock.json` | 复用现锁；构建 Node 24.19.0、npm 11.17.0 | 最终静态产物 hash、依赖扫描另录 |
| Spring／JPA／POI／PDFBox | `pom.xml`；本轮 Maven resolved tree 留外部证据目录 | JDK 17.0.20.1、Maven 3.9.11；离线 resolved tree 成功 | Boot 2.7.18 等旧版须按实际扫描整改；仍保当前 Java 8 字节码以避免扩大变更 |
| native-table CPU | [输入](../tools/vetting_eval/requirements-native-table-linux.in)、[Linux hash lock](../tools/vetting_eval/requirements-native-table-linux.lock.txt) | Linux CPython 3.12、x86_64 manylinux_2_28 解析得到 20 个完整传递包；新 WSL 隔离环境实际 hash 安装、导入、Network／Stream 全页运行成功 | 这是环境执行，不能证明完整表、条件／Notes／跨页质量；候选默认不发布 |
| MinerU full | [输入](../tools/vetting_eval/requirements-mineru-linux-full.in)、[已有 Linux hash lock](../tools/vetting_eval/requirements-mineru-linux-full.lock.txt) | 从已有成功独立 Linux 配方保留 238 包的准确版本／hash，去除机器路径注释；本轮未重复加载 GPU 模型 | 最终 wheel 集、OS／CUDA／镜像、目标断网启动、H800、扫描另验 |
| PaddleOCR／PaddleX | 当前独立模型／worker 配方保留 | 当前候选家族 3.6.0；不与 MinerU／retrieval 混合环境 | 最终 Linux hash lock、模型／processor／字体清单和目标质量闭合未完成 |
| BGE／BM25／Qdrant | [独立 Linux 输入](../tools/vetting_eval/requirements-retrieval-linux.in)、[完整传递 hash lock](../tools/vetting_eval/requirements-retrieval-linux.lock.txt)及冻结05模型 publication | 82 包的新 WSL venv 实际离线 hash 安装、pip check、CPU import／张量／BM25 检查通过；保持 Torch 2.11.0＋cu128、Transformers 4.57.6、Sentence Transformers 5.2.0、Qdrant client 1.19.1；[Server 新 collection 全量与正常冷启存储读回](./testing/qdrant-server-migration-candidate-20261013.md)另有实际证据 | 模型前向／语义查询、现业务运行配方兼容、目标 OCI／PVC／H800／扫描未验；Server 候选未采用 |
| 业务 LLM | 已有独立 profile；Qwen3.8-27B 官方 FP8 首测方向 | 不加载未验模型、不改 Drafting | >=256K／优选512K实际输入计数及目标加载／峰值必须同模型、tokenizer、template 绑定；API mock不替代 |
| MySQL／字体／原资产 | 现数据库迁移、文件资产接缝 | 不切换活动 MySQL；8.4 LTS 是待验证目标 | 目标字体、许可证、OS包、PVC权限／恢复／扫描未闭合 |

native-table Linux lock SHA256：`dfa7d0d9460597163c230ac9a82ffe81ded21ec7d8964d0e30e3f69b36d97fbf`。实际新环境为 Linux 6.18.33.2 WSL2、glibc 2.39、Python 3.12.3，Camelot 2.0.0、pdfplumber 0.11.9、OpenCV headless 5.0.0.93、PDFium 5.14.0。两页 authored source 的 Network 返回 0、Stream 返回 2 个候选；这只是正常调用完成，不能把候选数当正确表数。

MinerU 原锁 SHA256 `f37a6e916dfc1ec7e7a77e2c90506112f3625b43bd160d2ed55140a7bfac49c1`，保留版本／hash主体后的源码文件 SHA256 `e65851cc725fb75c507b36068f796eb0d27c389222ebd62c965f71ec9de7ee24`。其已有 resolver 和离线安装／模型运行收据留在原忽略目录；本轮复用锁不追认新目标运行。

## 独立 Linux retrieval 实际制品

2026-10-13 02:18，完整锁 SHA256 `3fc98c75db7762b08503d52b91f2fc545bc9f09d2cb100b460250784b171ff33`。新环境为 `/home/ocr/.local/share/consense-oer/retrieval-offline-strict-20261013/venv`，Linux 6.18.33.2 WSL2／x86_64、glibc 2.39、Python 3.12.3。没有复制 Windows venv，也没有更改活动服务或打开 Local05。

本机实际 BGE 路径是 Sentence Transformers；源码和原 retrieval 环境均未使用 FlagEmbedding，因此没有为其名字另加依赖。新输入保持实际核心版本，独立解析并固定所有 Linux 传递版本，不修改原环境。旧 `requirements.txt` 保留原功能；此 retrieval 环境不安装 RapidOCR／ONNX Runtime／OpenCV，OCR 由独立 worker 配方承担。

外部制品根目录：`C:/Users/Administrator/Documents/Codex/consense-implementation-20261013/retrieval-linux-artifacts-03/`。

| 实际检查 | 结果 | 对应原始证据 |
| --- | --- | --- |
| 完整 wheels | 82 个，4,066,465,995 bytes，全部官方 PyPI／PyTorch／NVIDIA 来源，与每个锁定 hash 相等 | `offline-artifact-smoke-receipt.json.wheelCount`／`wheelBytes`／`wheels`；`network-source-report.json` |
| 离线安装 | `--no-index --find-links ... --require-hashes --only-binary=:all:` 实际成功 | `offline-install.log`／`offline-install-report.json` |
| 依赖一致性 | `No broken requirements found` | `pip-check.log` |
| CPU selfcheck | Torch CPU 张量、BM25、服务及主要包导入通过；模型／向量DB构造、GPU context、OCR调用均0 | `offline-artifact-smoke-receipt.json.smoke`；`offline-artifact-smoke.log` |
| 制品边界测试 | 9 tests 通过，包括变更 hash、凭证URL、额外包、嵌入权重、vendor metadata及Python startup `.pth`区分 | `boundary-tests.log`；[测试源码](../tools/vetting_eval/test_verify_retrieval_offline.py) |
| 许可记录 | 每个 wheel 的原始 License／License-Expression／classifier、识别出的许可文件 SHA保留，未补猜或认定扫描通过 | `offline-artifact-smoke-receipt.json.wheels` |

完整制品／CPU收据 SHA256：`695b893604ba6ca903e8e4641e80e008fbc65bf9cdf113ee2fd6b27f4050e225`。原始工具及锁的字节副本保存在 `tooling/`，实际 wheels 保存在 `wheelhouse/`；均在外部目录，不提交二进制。

[校验入口](../tools/vetting_eval/verify_retrieval_offline.py) 校验精确包／版本／官方来源／锁 hash，拒绝 wheel 中的 ONNX、safetensors、Torch checkpoint 等权重文件；NVIDIA wheel 内的根目录小型 Python startup `.pth` 是命名空间启动脚本，与序列化 tensor checkpoint 分开检查。CPU selfcheck 设置离线模型标志、禁用可见GPU并禁止模型／GPU初始化／向量DB构造。它不是断网目标机、GPU前向、源质量或业务测试。

**独立审查后的校验器修复：** 旧入口只检查 hash 出现在整个 lock 中，两个包交换各自锁 hash 仍被接受。新增真实两 wheel 反例先复现失败，再按每个归一化包名／版本的 requirement stanza 绑定允许 hash；重复归一化 lock／source-report 条目和 source 版本不符也拒绝。13 个边界测试在正常 Python 与 `python -O` 下均通过，原 9 项及实际 82 包 `pip --require-hashes` 安装证据保留。CPU smoke 改用不能被 `-O` 删除的 `require`；错误 embedding revision 的进程内反例在 `-O` 下实际被拒绝。

复核仅使用既有 82 wheels 和新严格环境，不重复下载／安装。新 `python -O` 制品／CPU收据为外部 `retrieval-verifier-fix-01/offline-artifact-optimized-smoke-receipt.json`，SHA256 `5c4bcd4927ac2681979047d3bc5a8b6c59cd2626cb7d06879fef82afd3142931`；同目录保留 `boundary-red.log`、`boundary-green.log`、`boundary-optimized-green.log` 和 `optimized-smoke-counterexample.py/log`。旧收据不覆盖，模型／业务／OCI／H800门保持未验。

两个原模型 publication 清单的 SHA与冻结05相同：embedding `13c9d08ae6e1d3d73fb6d284d473f493446c4a276c864cf5a02338ded3552a15`，reranker `70d656dfe6639100b1c42d01e202f022c14f06378f09685e37ecb21596c0a80b`。按各自固定 revision 只读取 README：BGE-M3 的声明为 MIT，README SHA `0b81ccf9134e5874d620a86e6905062ea999e779c34eb1a7e65eaeb7fe00e450`；reranker 的声明为 Apache-2.0，README SHA `c887aa6dd2598f908bf0582ca7068cc816585c7a1b6a07df305b631ede0cb174`。这两个 revision 的独立 LICENSE 请求均实际404，缺失状态保留，不补造文件。`model-publications/model-publication-license-inventory.json` SHA为 `2d112abd4052ce5e33b36ce16257bdfcc755bd22e46c8b43ba17d5af5ed9e5b4`，含完整原清单、实际URL和HTTP状态；未重新下载或装载 BGE／reranker 权重，许可审查未关闭。

**范围偏差与失败保留：** 初版输入继承完整旧 `requirements.txt`，得到94包，其中 RapidOCR 3.9.2 wheel内嵌3个ONNX权重，随依赖已下载但没有装载或调用。该94包候选明确未采用，原日志／锁／`candidate-admission.json`在 `retrieval-linux-artifacts-01/` 保留。随后拆出严格 retrieval 输入，从已取得的普通运行时 wheels建立上述82包新候选，未再下载模型权重。初次制品校验还遇到 wheel内vendor metadata及 `.pth` 命名歧义，已按顶层 package身份和启动脚本内容区分并添加测试；失败日志保留。不能将这些旧尝试写成0权重下载或已通过CPU运行。

## Linux 离线安装入口

先在联网准备机下载与 Linux CPython 3.12／x86_64 对应的完整 wheels，逐文件保存 SHA、来源和许可证。目标只从已核 wheelhouse 安装，不在比赛现场解析或下载：

```sh
python3 -m venv /opt/consense/native-table
/opt/consense/native-table/bin/python -m pip install --no-index --find-links /artifacts/native-table-wheels --require-hashes -r requirements-native-table-linux.lock.txt
```

MinerU 使用独立 venv 和自己的 `.lock.txt`，retrieval 与业务 vLLM 也分别隔离；不要把 Windows venv 复制到 Linux。上述目标路径是交付操作示例，本轮未执行目标安装。

retrieval采用同样的 `--no-index --require-hashes` 方式，使用 `requirements-retrieval-linux.lock.txt` 和其准确 wheelhouse；安装后运行 `pip check` 及 `verify_retrieval_offline.py --lock <lock> --source-report <network-source-report.json> --wheelhouse <wheelhouse> --out <fresh-receipt.json>`。本轮实际执行仅在上述新WSL环境完成，`targetOfflineBootAccepted=false`／`OCIscanAccepted=false`／`H800Accepted=false` 保持。

## 最终制品关闭条件

每套最终制品必须保存源码 commit、实际 OCI digest、OS package清单、Maven resolved tree、npm lock／静态产物 SHA、pip hash lock／对应 wheels、模型／tokenizer／processor／chat template／必要自定义代码、字体、许可证和与这些准确 digest 绑定的扫描报告。未提供实际 digest 的条目保持待验证，不能以 tag 或组件名称替代。

CPU 业务、SQL、Qdrant Server和资产持续运行；GPU阶段先持久化结果，前独占 Pod 真正退出并释放 `nvidia.com/gpu`，后阶段才加载。准入取正常阶段与切换峰值的最大值，并包含在线 query embedding／rerank。模型和原件只读挂载、工作目录与数据库／资产PVC分开。断网启动、GPU释放／恢复、等待／超时、备份恢复、源回取和实际扫描均属于 NEXT-09 的目标运行门。

本轮原始日志位于外部证据目录 `C:/Users/Administrator/Documents/Codex/consense-implementation-20261013/`：`maven-resolved-tree.txt`、`native-table-linux-resolve.log`、`native-table-linux-venv.log`、`native-table-linux-install.log`、`native-table-linux-smoke.log`。这些证据不提交原件、模型、密钥或本机配置。
