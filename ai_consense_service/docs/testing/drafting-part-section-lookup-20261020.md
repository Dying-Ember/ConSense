# Drafting 原生 DOCX 只读 section 查询复用

2026-10-20；独立于同请求图谱核验的 Round05 修复。本轮只改 `DocxTemplateEditor.java` 的 `Paragraph` 构造路径及 `load()` 的调用，不改路径算法、来源身份、原件内容、编辑安全门或持久化缓存。

旧实现为每个段落重新调用所在 Document 的 `getElementsByTagNameNS(W, "sectPr")`。在已冻结 Round04b 专项测试 JVM 的 12 次有限线程采样中，4 次栈顶为该整份 XML 查询；这只是有限观察，不是完整 profiler 占比。相同三个固定 DOCX 共 3,999 个段落，本轮在每个只读 story part 中只取一次 section NodeList，传给新增的私有构造重载。`load()` 的段落、anchor 和 feature 建立过程均只读。另一个 caller `attachParagraphBookmarks()` 会修改 DOM，因此继续使用原四参数构造函数，每次重新获取当前 sections。

## 完整等价与安全验证

先从未改生产实现的新冻结快照生成三原件的完整私有 `TemplateIndex` 字段树，后在修复快照中逐值比较。快照包括全部段落原始 `snapshotXml` 字节、ID、path、nativeNodePath、文本、hash、section／row／cell／table 身份、entry hashes、全部 anchors 和 features。三个快照总计 19,508,994 字节；前后文件字节完全相同，覆盖 3,999 个段落、5,528 个 anchors、521 个 features。

| 原件 | 完整字段快照字节 | 前后相同的快照 SHA256 |
|---|---:|---|
| NTT | 3,138,884 | `60ed4d490e779d6c1d9ac7ce2939c0210d1859652fc92bceb6ebf85af4fb0462` |
| SCT | 5,983,668 | `ad5fff1ea23ff56711efada948c3ff401010b387d6428317d19d2c9f2fe9e885` |
| SCC | 10,386,442 | `996ab5c3ba3e3adfdf5b320737b3892056b2fa3443998368e168c65b52ab943f` |

新增 `DocxTemplateSectionLookupTest` 还覆盖多 section 的原生先后次序、表格内段落、header 与 body 的 story 隔离、无操作返回原包字节，以及同一 DOM 增删 section 后旧构造函数仍读取新状态。

固定验证结果：baseline 新测试 5/5；修复快照 4 套 28/28、0 fail、0 error、0 skip、BUILD SUCCESS，其中 SectionLookup 5、EditorSafety 14、PreparedReview 4、NativeLayoutGuard 5。Vetting agent 对差异和 DOM 读写边界只读复核，0 Must。baseline 快照 SHA256 `d4eb36a03448c686e1bf44a655c4a6998a0bb9202472263226335e1bc3acc915`；green 快照 `c6a40bbff564d4df17f8647b53ec49d441067e3b0b00735e0342c7a9ae36816a`。两者均使用独立 target，未改旧冻结快照或运行时。

## 本次实际计时

计时只包括 `editor.prepare(original)`，完整字段序列化与比较不在计时内。

| 原件 | 改前 | 改后 |
|---|---:|---:|
| NTT | 1.420799 s | 0.330008 s |
| SCT | 1.649350 s | 0.510258 s |
| SCC | 6.776566 s | 1.257918 s |

这是本机并行工作中的观察值。green 的既有安全套件先运行，可能使 JIT 或缓存预热程度与 baseline 不同，因此不能把比例当作稳定性能承诺。本轮没有设置容易抖动的墙钟阈值来制造 RED；缺陷依据是明确的每段整份 Document 重复查询及实际栈采样，正确性依据是完整原生字段树等价和安全反例。

可重算证据位于忽略目录 `.local/e2e-20261020/drafting/round06-equivalence-summary.json`，逐文件散列及原始指标均在该文件中；差异为同目录 `round06-part-section-lookup.patch`；原始测试日志为 `build/round06-baseline-maven.log`、`build/round06-green-maven.log`。运行时部署和真实 HTTP 后续验收由 root 统一控制，和 Round05 的同 revision／同源实际 HTTP 比较分开报告。
