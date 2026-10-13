# 冻结 OER 模型与固定05数据库

本制品由用户于2026-10-13明确授权随[PR #8](https://github.com/Dying-Ember/ConSense/pull/8)交付到私仓。实际权重、数据库和来源文件位于[同一私仓的Release](https://github.com/Dying-Ember/ConSense/releases/tag/oer-frozen-20261012-05-20261013)；本目录的manifest锁定其完整内容。

- 可信manifest SHA256：`0ce643f66a9208338e827c063f77254d20bfc8aba6e7f1d1f23f53a24e616950`。
- 清单内1751个文件、解压总计16614909465 bytes；压缩分卷共9个、9212695152 bytes，每卷不超过1 GiB。
- BGE-M3与BGE-reranker-v2-m3的05 publication均含真实权重及完整声明配置/tokenizer。PaddleOCR-VL1.6、PP-DocLayoutV3、MinerU2.5-Pro-2605-1.2B及full torch辅助模型属于独立历史静态快照。旧ONNX-only路线省略；保留的旧37文件OCR receipt是历史身份依据，并不表示本包仍附带该旧路线全部文件。
- 向量库来自既有独立静态副本，含storage.sqlite、项目metadata、Qdrant meta与copy provenance。原冻结身份为19,371父记录、20,700向量点；本次复算全部文件SHA，不打开活动Local05。完整corpus、固定原PDF及其引用的解析/表格来源文件一并保存。
- 不包含活动H2演示数据库、密钥、服务日志或未采用LLM缓存。原冻结清单中的解析、业务、H800未通过状态和历史recipe/source pin保持原字节。实际制品字节已在全新目录完成完整恢复验证；这不表示新主机运行或业务验收通过。

## 下载和验证

使用有该私仓读取权限的GitHub账号登录`gh`，从仓库根目录运行：

```powershell
gh release download oer-frozen-20261012-05-20261013 --repo Dying-Ember/ConSense --dir D:\ConSense-frozen-download --pattern "manifest.json" --pattern "*.part*"
python ai_consense_service/tools/restore_frozen_artifacts.py --manifest D:\ConSense-frozen-download\manifest.json --manifest-sha256 0ce643f66a9208338e827c063f77254d20bfc8aba6e7f1d1f23f53a24e616950 --parts-dir D:\ConSense-frozen-download
```

确认后恢复到一个尚不存在的绝对目录：

```powershell
python ai_consense_service/tools/restore_frozen_artifacts.py --manifest D:\ConSense-frozen-download\manifest.json --manifest-sha256 0ce643f66a9208338e827c063f77254d20bfc8aba6e7f1d1f23f53a24e616950 --parts-dir D:\ConSense-frozen-download --destination D:\ConSense-frozen-05
```

工具逐卷、整体归档和每个文件核对大小及SHA，拒绝不符清单的成员、危险路径和链接；现有目标目录也会拒绝。系统临时盘需容纳完整压缩归档，目标盘需容纳上面的解压总量，下载盘另需压缩分卷容量。可以在运行前把`TEMP`/`TMP`指向容量足够的临时目录。写盘失败会保留已创建的部分新目录，不自动删除；只有成功才生成`RESTORED.json`。

## 恢复范围

恢复保持冻结内容原字节，并生成`path-map.json`与`RESTORED.json`。旧corpus、metadata、模型receipt和runtime-plan仍含原C/D绝对路径；工具不会改写它们、打开数据库、加载模型或启动服务。异机运行需要按路径映射显式准备新runtime并发布新身份，不能原地改写后声称原baseline身份不变。新提交的业务代码与冻结历史recipe hash分列；完整许可证/OCI扫描、来源质量、QS业务与H800仍未验收。
