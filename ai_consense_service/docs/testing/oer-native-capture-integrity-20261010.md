# 原生 DOCX 实际捕获与 Python 独立门控

2026-10-10。原生观察 adapter 为 `ooxml-observation-2`，实现与 Java 原件验证见 [adapter 记录](./oer-native-docx-observation-validation-20261010.md)。本记录验证实际 ZIP／OOXML 来源保真及 Python 捕获，不表示参考答案质量、有效合同文本、Word 视觉布局或业务验收通过。范围遵循 [OER 已确认规则](../oer-scope-and-rules.md)，项目 PRE 在范围内；没有使用 2d／2e 答案作为模型输入。

## 已完成的工程

`tools/vetting_eval/native_observation.py` 接收 Java 的实际 JSON、原始 DOCX 和对应冻结 adapter 源码。捕获前核原 ZIP 字节、全部 parts、实际 content types、observer 配方与源码 SHA、source revision 和唯一 native node 身份；逐 story 复核原 XML 的所有 paragraph／run／table／row／cell、DOM 顺序和最近父关系。节点身份包括 actual partName，两个字节相同的不同页眉不能合并。

Token 对照 actual DOM 原文字、控制符、字段指令、直接 strike／dstrike 和删除／moveFrom 属性，重算四个 channel 与每个 UTF-16 offset／included 标记。原生 table inventory 逐一对照实际所有行、单元格、空槽、raw merge 属性和嵌套关系。`inventoryComplete` 仅说明 native inventory 全部保留，不推断 Word 的视觉 grid、rowspan、页眉适用关系或有效编辑态。

CAS 保留实际 source package、adapter 源、原始 JSON 和每一个原 ZIP part。Bundle 对外保留 quality、limitations、story／unsupported story／relationship inventories；复读时核 CAS 字节、bundle seal 与原始 JSON 内容，重新执行原件门控。Relationship inventory 原样保留，关系的实际业务适用性仍 unknown，不据它推断合同关系。校验不拼写纠错，也不把 independent reference 的文本写回候选。

`source_reference.py` 新增明确的 exact native anchor：`kind/sourceSha256/partName/partSha256/nodePath`。提供实际源字节时，核对应 part 和 exact XML path；缺少实际字节时只报告声明形状有效，不称已定位。旧 bodyElementIndex／tablePath 分支保留为历史声明地址；exact 分支不混入这些地址或 Word 页码／bbox。`nativeTables` 对 actual XML 整个 roots 的 native table／paragraph membership 复核，缺少嵌套表或空段落拒绝。原件位置验证不等于独立标注文本或法律语义正确。

Native canonical v2 是独立 pure producer，具体记录见 [实现与边界](./oer-native-canonical-producer-implementation-20261010.md)。当前没有修改 scanned v1 的 complete dual-parser 门控；v2 的真实业务消费、rendition 与 Q10 完整图像回取仍需后续接线。没有把 native observation 自身当 reviewed adoption。

## 实际来源与结果

输入冻结目录为 `.local/state/oer-native-docx-observer-20261010/run-021313-f25c92/real-exports-021404-ae8675`，对应 adapter 源 SHA 为 `2a28cf5ce37d72407fe7d36e7162600e65a6d0bc0539a6ce929f22bfdb2fc265`。

Python 最终实际 capture 收据：[actual-v2-7bd3798a/receipt.json](../../../.local/state/oer-native-capture-20261010/actual-v2-7bd3798a/receipt.json)。每份 row 含确切 source／export 路径及 SHA、bundle seal／文件 SHA、字节数、原件前后一致和以下计数／时间。三个输入全通过 source bytes、positions、channels、membership 和 revision 门控。

| 实际原件 | Source SHA | 原 ZIP parts | Native nodes | Native tables | Bundle bytes | 本机 capture＋复核＋写入秒数 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| `13 - Appendix G302 - insurance.docx` | `5047630bf82cbf5613b0f280dcf116a1b67176e9fef8ff3f930ce270a26af0ae` | 30 | 1,904 | 0 | 12,424,016 | 1.265 |
| `05.1(2) - Marking Scheme (assessment std).docx` | `56dcdea23a87dd49946053063a11c217348314751c2b9fde50caf4af581656a0` | 29 | 18,851 | 34 | 172,785,077 | 15.141 |
| `25 - Specification - PRE (base tender).docx` | `a4e61064c33ba2b67c5192bfa7756c1b792dbb74ffda32aea84f2b96509dc6ea` | 125 | 72,265 | 224 | 418,855,750 | 47.859 |

这是同机 CPU 和磁盘工程测量，包括重复 CAS／bundle 校验与 JSON 写入，不是 OCR／embedding／LLM token 吞吐或 H800 性能。每份来源 serial capture 后释放临时内存。逐 node 对同一大 part 重复 SHA 没有增加检查信息，已改为本次独立验证调用内复用 actual part SHA；没有跨请求信任旧 source、删 token 或减少 inventory 检查。旧第一版捕获和 Java 失败／旧版本输出仍保留。

上述完整 JSON 体积较大。尚未证明这些 bundle 能直接通过当前 Java／Python snapshot 大小限制或适合每次问答重新解释；后续消费设计需用 actual CAS descriptor 和来源绑定缓存保全全部证据，并明确首次验证与复读的成本。不能仅放大限制或删原始内容来冒称业务已接通。

## 测试及实际限制

聚焦测试使用 Java 实际运行得到的 authored OOXML capture，压缩保存在 `test_native_fixture.py`；它含空白格、内表、原生 controls、NBSP、astral 字符、删除／直接划线和 header。它是互验契约，不是原件 gold。新增 `test_native_observation.py` 的20项、`test_native_source_reference.py` 的8项与 native producer 的28项，以及既有 source reference／scanned canonical／capture／structured evidence／server 回归共同运行。

统一收据：[final-tests-3840f363/receipt.json](../../../.local/state/oer-native-capture-20261010/final-tests-3840f363/receipt.json)，对应实际日志同目录 `unittest.log`。本次8个模块共175 tests，0 failure／0 error／0 skip；源码 SHA 逐文件列在收据。后续 producer 性能更改属于新 snapshot，须新收据，不沿用本次175的源码身份。Java adapter 的10 tests 有独立收据，不与重复的 Python 分轮回归相加成质量样本数。

没有调用 OCR／embedding／rerank／LLM，没有发生权重训练，没有改原件、在线 Java target 或部署配置。`displayedTextVerified=false`、`tableTopologySemanticsVerified=false`、semantic quality unknown、`humanConfirmed=false`。全范围原件 gold、有效删除态／编号／符号、DOCX→实际 rendition mapping、native v2 业务接线、赛方题目及 Vetting 结构化报告、最终 Linux／H800 发布验收尚未完成。
