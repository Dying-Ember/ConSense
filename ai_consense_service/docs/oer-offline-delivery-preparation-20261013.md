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
| BGE／BM25／Qdrant | `tools/vetting_eval/requirements.txt` 和冻结05模型 publication | BGE-M3 revision `5617a9f61b028005a4858fdac845db406aefb181`；reranker revision `953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e`；不重新 OCR 或并开 Local05 | 独立 Linux retrieval 传递 hash lock、Server 1.19.2 新 collection 迁移／读回／查询／回滚未完成 |
| 业务 LLM | 已有独立 profile；Qwen3.8-27B 官方 FP8 首测方向 | 不加载未验模型、不改 Drafting | >=256K／优选512K实际输入计数及目标加载／峰值必须同模型、tokenizer、template 绑定；API mock不替代 |
| MySQL／字体／原资产 | 现数据库迁移、文件资产接缝 | 不切换活动 MySQL；8.4 LTS 是待验证目标 | 目标字体、许可证、OS包、PVC权限／恢复／扫描未闭合 |

native-table Linux lock SHA256：`dfa7d0d9460597163c230ac9a82ffe81ded21ec7d8964d0e30e3f69b36d97fbf`。实际新环境为 Linux 6.18.33.2 WSL2、glibc 2.39、Python 3.12.3，Camelot 2.0.0、pdfplumber 0.11.9、OpenCV headless 5.0.0.93、PDFium 5.14.0。两页 authored source 的 Network 返回 0、Stream 返回 2 个候选；这只是正常调用完成，不能把候选数当正确表数。

MinerU 原锁 SHA256 `f37a6e916dfc1ec7e7a77e2c90506112f3625b43bd160d2ed55140a7bfac49c1`，保留版本／hash主体后的源码文件 SHA256 `e65851cc725fb75c507b36068f796eb0d27c389222ebd62c965f71ec9de7ee24`。其已有 resolver 和离线安装／模型运行收据留在原忽略目录；本轮复用锁不追认新目标运行。

## Linux 离线安装入口

先在联网准备机下载与 Linux CPython 3.12／x86_64 对应的完整 wheels，逐文件保存 SHA、来源和许可证。目标只从已核 wheelhouse 安装，不在比赛现场解析或下载：

```sh
python3 -m venv /opt/consense/native-table
/opt/consense/native-table/bin/python -m pip install --no-index --find-links /artifacts/native-table-wheels --require-hashes -r requirements-native-table-linux.lock.txt
```

MinerU 使用独立 venv 和自己的 `.lock.txt`，retrieval 与业务 vLLM 也分别隔离；不要把 Windows venv 复制到 Linux。上述目标路径是交付操作示例，本轮未执行目标安装。

## 最终制品关闭条件

每套最终制品必须保存源码 commit、实际 OCI digest、OS package清单、Maven resolved tree、npm lock／静态产物 SHA、pip hash lock／对应 wheels、模型／tokenizer／processor／chat template／必要自定义代码、字体、许可证和与这些准确 digest 绑定的扫描报告。未提供实际 digest 的条目保持待验证，不能以 tag 或组件名称替代。

CPU 业务、SQL、Qdrant Server和资产持续运行；GPU阶段先持久化结果，前独占 Pod 真正退出并释放 `nvidia.com/gpu`，后阶段才加载。准入取正常阶段与切换峰值的最大值，并包含在线 query embedding／rerank。模型和原件只读挂载、工作目录与数据库／资产PVC分开。断网启动、GPU释放／恢复、等待／超时、备份恢复、源回取和实际扫描均属于 NEXT-09 的目标运行门。

本轮原始日志位于外部证据目录 `C:/Users/Administrator/Documents/Codex/consense-implementation-20261013/`：`maven-resolved-tree.txt`、`native-table-linux-resolve.log`、`native-table-linux-venv.log`、`native-table-linux-install.log`、`native-table-linux-smoke.log`。这些证据不提交原件、模型、密钥或本机配置。
