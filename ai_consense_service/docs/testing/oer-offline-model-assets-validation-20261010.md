# OER 模型准备与独立离线文件导出

日期：2026-10-10，Asia/Shanghai。执行边界以 [中央规则](../oer-scope-and-rules.md) 和 [部署清单](../../../docs/oer-source-deployment-manifest-20261009.md) 为准。本轮完成模型仓库资源的准备入口和独立文件导出；完整 Linux 运行环境、双 parser、六来源原件质量和 H800 发布仍未完成。

## 已应用的入口

`tools/vetting_eval/prepare_oer_models.py` 保留 embedding、reranker、mineru-raw 的现有选择，新增 Paddle VL、Paddle layout、MinerU auxiliaries 的准确 revision 与完整仓库资源清单。三个新角色分别要求 18、4、15 个文件，含 custom Python、processor、tokenizer.model、Paddle graph/parameters、8 ONNX、5 YAML 等。上游清单、实际路径和最终已核验集合必须完整；缺文件、错误 LFS SHA、错误 Git blob、缺上游身份和混入非所选资源均拒绝。普通文件 cache symlink 按实际字节校验，目录 symlink 不能用仅存在性检查冒充完成哈希。

新增 `tools/vetting_eval/materialize_oer_models.py` 从外部准确 SHA 绑定的 preparation receipt 导出新独立目录。它只重建允许的文件身份字段，不带入源缓存绝对路径；输出文件为常规文件，保留原版本、大小、SHA 和上游身份。导出目标必须不存在，不覆盖旧目录；复制中发现源变化时保留 `.incomplete` 失败目录，不能通过 `verify`。原 preparation receipt 中的 `isCacheSymlink` 是源缓存 provenance，不表示输出仍是链接。导出 manifest 明确声明仅模型仓库资源；runtime flags 必为 false、模型加载及导出网络计数必为严格整数 0，矛盾声明即使提供新正确 manifest SHA 仍拒绝。

精确应用为 2 个产品文件、2 个新增测试文件；原 `test_prepare_oer_models.py` 字节不变。root Python inventory 144→147，仅预定四文件变化，其他源码与 HEAD 保持。主 Java target、服务、DB、原模型和 vendor 安装未修改。apply receipt：`.local/state/oer-model-materialize-20261010/run-121900/root-source-apply/apply-receipt.json`，SHA `f8e6d8d14f0ff4eeffd9e56765dccab693a18302bc29e089fb537d81a8a77358`。

## 验证层次

| 层次 | 本轮结果 | 证据与限制 |
| --- | --- | --- |
| copied contract tests | 36 个唯一测试，0 failure/error/skip | 5 旧准备测试＋18 新角色测试＋13 导出测试；逐项上游/本地缺失另为 37＋37 个子测试，不重复计作独立 case |
| 独立规范/需求审阅 | Standards 0 硬问题，Spec 0 未解决；10 个独立合成 probe 通过 | 初次发现两个 P2 清单声明问题，以两项回归最小修复；旧失败和 freeze-v1 保留 |
| 真实仓库资产字节 | 3 角色、37 资源，共 2,920,695,603 bytes；12 LFS＋25 Git blob | 实际已下载资源与固定上游元数据匹配；没有重新下载、加载权重或推理 |
| 独立导出 | 37 常规文件＋manifest，无 symlink | 源链接视图移至本轮其他目录后，原三路径不可访问；新进程只用输出目录和 SHA 校验通过 |
| 已应用仓库入口 | root `materialize_oer_models.py verify` exit 0，3 models/37 files | root 另核同一实际导出；仅文件包校验，不加载模型、不联网、不当 Linux/H800 运行完成 |

真实模型文件逐项大小、SHA、LFS/Git 身份在 `.local/state/oer-real-model-assets-20261010/run-v2-122127-4896/evidence/actual-preparation-receipt.json`。可按每个 model 的 `files` 重新求和：

| 角色 | 文件 | bytes | LFS SHA | Git blob |
| --- | ---: | ---: | ---: | ---: |
| paddle-vl | 18 | 1,930,459,435 | 3 | 15 |
| paddle-layout | 4 | 132,017,036 | 1 | 3 |
| mineru-small | 15 | 858,219,132 | 8 | 7 |

首轮以 `HfApi(token=False)` 取得 3 份准确 revision 的公开元数据，实际 HTTP200，Authorization absent。保存原响应、normalized siblings 和 SHA；最终 v2 在禁止网络下从已绑定响应逐值核对并重放同一 SDK metadata。`snapshot_download` 明确替换为本轮受控的已有资源链接视图，实际下载 0；真实文件字节核验完整执行。两轮不混报为真实在线下载测试。v1 完成后收到更新通知，旧证据保留并标 superseded，最终使用 v2。

## 命令与使用边界

下面在仓库根目录、已准备的 Python 环境执行。`prepare` 是准备机下载/核验入口；比赛目标进程使用打包的本地资产，不临时访问 HF。所有 receipt 和 output 使用新路径，不覆写原证据。

```powershell
python ai_consense_service/tools/vetting_eval/prepare_oer_models.py `
  --cache .local/oer/hf `
  --receipt .local/oer/release-preparation/models-new.json `
  --models embedding reranker mineru-raw paddle-vl paddle-layout mineru-small

$oerPreparationSha = (Get-FileHash -LiteralPath .local/oer/release-preparation/models-new.json -Algorithm SHA256).Hash.ToLowerInvariant()
python ai_consense_service/tools/vetting_eval/materialize_oer_models.py export `
  --receipt .local/oer/release-preparation/models-new.json `
  --receipt-sha256 $oerPreparationSha `
  --output .local/oer/release-preparation/independent-model-assets-new

$oerExportSha = (Get-FileHash -LiteralPath .local/oer/release-preparation/independent-model-assets-new/manifest.json -Algorithm SHA256).Hash.ToLowerInvariant()
python ai_consense_service/tools/vetting_eval/materialize_oer_models.py verify `
  --output .local/oer/release-preparation/independent-model-assets-new `
  --manifest-sha256 $oerExportSha
```

实际本轮导出位于 `.local/state/oer-real-model-assets-20261010/run-v2-122127-4896/independent-model-assets/`，SHA 参数为该目录 manifest 的 `67ce144e95cb4c13cab49dda5507301a4ba2ec5b227c230fd8429b740b1d6c6d`。它只含新增三个 OCR 角色，不能当已准备六个模型角色或完整发布包。MinerU package 字典、Linux wheel/shared libraries、字体/renderer、完整入口与配置另须固定；原三个角色的全面运行资源闭包也未由本次新角色清单追认。

## 证据身份

- 最终 copied 源清单：`.local/state/oer-model-materialize-20261010/run-121900/freeze-v2/source-manifest.json`，SHA `b5616740880bfb1893b58ac8fb4e9f07aaabbadfae6d69031dca8dc6da577680`。
- 36-case receipt：同目录 `validation-receipt.json`，SHA `0212bf09f68abba1c72f12eff6ee24687712ac634c5e7cb2ad65259308122dcf`。
- 独立接受 receipt：`.local/state/oer-model-materialize-independent-review-20261010/run-freeze-v2/review-receipt.json`，SHA `f61eef9b42073452215be58958ce983ec752b6543c0db3e5355a60e92191ea2b`。
- 实际模型资产 receipt：`.local/state/oer-real-model-assets-20261010/run-v2-122127-4896/evidence/actual-model-assets-closure-receipt.json`，SHA `1e047b03c9c87dd0c7b9269c1a07a26ac734eafba0ebbef3afcf0f832ffb699f`。
- 实际 preparation receipt：同 evidence 目录，SHA `69d011ad15f5cdb2b04768ca84f274d90e90ebe13be69242bf4e899cadd2bb8d`。
- 实际小证据清单：同 run 的 `freeze/evidence-manifest.json`，SHA `35bd6f90fe317fa399159d9dbc544bc929c905b43617ff453d4830639617ea5f`。
- 应用后实际 CLI 验证：root apply 目录的 `applied-cli-verification.json`，保存完整命令结果及 exit0。

## 完整处理链仍需关闭的发布门槛

完整链审计快照在 `.local/state/oer-complete-chain-release-closure-audit-20261010/run-120924-readonly/`，gap receipt SHA `34e59407640170ceda12cc0ec09eabea14aa7677014bd01240a19498f41d2517`。该审计发生在本轮模型 helper 应用前；其中“root 仅有三模型准备”已被本记录更新，其他未完成门槛继续有效。

1. 公共原件作业入口贯通原生 producer、固定 renderer/字体、Paddle 完整链和 MinerU 完整第二路；完整 CUDA adapter、实际辅助算子与公式/VLM 联接分别验证。私有探针不替代正式全链。
2. Linux/Python/CUDA 依赖闭包、GPU wheel/shared libraries、package 字典及字体等离线物化；Windows venv、链接环境层和模型资产清单不能作为 Linux 运行包。
3. 固定六来源按范围、模块权限、条款、坐标、编辑态和完整表组完成原件质量验收，保存完整 raw/adoption/index/generation。
4. 当前主索引 signature 含 tokenizer `resolvedSnapshot` 绝对路径和 package versions；Windows→Linux 必须显式验证可移植身份/兼容或目标预构建，不能手改 seal 或静默重贴旧索引。
5. 完整 parse→adoption→scope→index generation staging/发布/回滚；现有新 collection 和 metadata pending 替换仅是局部索引提交。演练同版冷启动无文档 OCR/embedding 重做、真实新字节/SL新范围、失败不覆盖旧完整成果。
6. 单 H800 阶段调度/模型卸载恢复、host峰值、加载时间和实际目标推理验收。正式可加速模型 CUDA-only；不能用当前包准备结果宣布这些门槛完成。

本轮代码和模型资源导出不提高任何原件质量、业务报告得分或 coverage，不解决旧 MiniMax `no_bound_token_observer` 的 119 pending 残差；业务预算接入及实际赛题另按已记录研究和门控继续实施。
