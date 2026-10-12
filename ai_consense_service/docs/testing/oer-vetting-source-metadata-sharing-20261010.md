# Vetting 请求级元数据共享：离线回放与业务回归

日期：2026-10-10，Asia/Shanghai。遵循 [范围规则](../oer-scope-and-rules.md)、[实施交接](../HANDOFF-ocr-embedding-rerank-20261008.md) 和 [完整输入开销调查](./oer-minimax-input-budget-and-provenance-review-20261010.md)。本轮实现无损输入编码，不训练权重、不重新调用模型、不替换在线服务。官方八题实际执行和正式多来源Vetting评分继续为0。

## 改动与目的

新增 `VettingSourceMetadataWire`，版本 `lossless-source-metadata-dictionary-v3`，在整次请求共享重复的非原文metadata。完整来源树仍在同一模型输入内：`metadataValues` 存实际值，单字段 `metadataRef` 对象表示零起算索引；值可引用其他metadata值，但循环、非法索引、未知marker及不含合法字段的对象拒绝。字典每项包含在实际wire中，所有项都验证，包括未引用项。

`content/cells/text/quote/raw*` 及全部后代始终保留literal，不进入字典，不解析其中JSON字串或marker。含这些字段的父对象也不作为一个metadata值移走。原对象碰撞 `metadataRef` 或 `metadataEncoding` 时，转成有序 `[key,value]` entries，解码只恢复原对象一次。字段顺序、null／missing、整数／浮点／boolean、unicode、数字字面量、完整表组、blanks、关系、source positions、quality和unknown保留。原审计树不修改，原始CAS和报告payload不删除。

已有 `VettingSourcePromptWire` v2 record encoding保持；v3先建立共享metadata，再用v2处理有序records。实际Vetting `user()`只改wire writer和legend，预览、预算和dispatch仍使用同一完整提示；schema、quote／citation门控、scope、extra packet预算规则不放宽。Advisory提示本轮未切换到此编码。

这属于新的输入配方，实际prompt SHA和字节会变化。传完整字典仍要求模型阅读引用；不能凭Java／Python成功解码证明M3或Bonsai的神经理解不变。后续真实同题对照分别记录答案质量和输出／引用失败。原生完整审计信息的语义精简是另一条配方，本次没有用sidecar替代未发送字段。

## 真实保存G305输入的离线回放

输入取唯一G305实际Vetting job已保存首个AiGateway prompt的source段，没有新generation／counter／index／HTTP。只比较source wire的实际保存UTF8文本；日志中的换行表示与原provider request bytes不同，不能用它反推完整wire tokens。

| 项目 | v2保存source wire | v3重新编码source wire |
| --- | ---: | ---: |
| UTF16字符 | 265,383 | 159,156 |
| UTF8 bytes | 265,384 | 159,157 |
| 来源records | 16 | 16 |
| 完整有序JSON树往返 | 输入基准 | 完全一致 |

来源字符减少106,227，即40.0278088649%；这是字符结果，不是M3 tokens或吞吐。120个metadata项仍在同一wire内。原G305所有paragraph、完整签署表的19paragraphs／8blanks／15cells、位置、采用及unknown逐字段恢复；相等断言覆盖完整树，不以正文needle相等代替结构保全。

初版较长typed reference减少幅度仅约28.6%；该产物保留，后来使用一字段 `metadataRef` 并保持reserved-object escape，才得到上述最终wire。首次offline collector未接受CRLF导致失败、首轮合并复制tests仅实际跑17项，均记录为准备问题，没有算作真实模型或最终63项通过。

## 复制checkout验证

最终run：`.local/state/oer-source-metadata-wire-20261010/run-final-053116-ce4ac3/`。源码、tests及资源复制后才执行Maven；没有写在线 `ai_consense_service/target`。

最终 **63 tests、0 failure、0 error、0 skip**：保存G305回放1、v3编码16、v2编码9、literal数值9、semantic packets12、canonical business6、canonical report4、input budget6。日志 `integrated-after-null-fix.log`，27.461秒。每个XML／源码／实际产物SHA及明确分母见 `validation-receipt.json`，SHA `6f36992487e6044274f764331399ff05d9f68fc0979a13c3cb43bd856d75ceec`。

首轮63项有4项失败：并行PDF接入增加optional `nativePdfEvidence` 后，旧来源JSON往返把Java null变为NullNode，使typed Chunk相等失败并被严格corpus门控拒绝。修复沿用既有optional `tableGroup` 的 `@JsonSetter(nulls=SKIP)`，没有放宽相等／来源门控；复制更新DTO后的同63项才全通过。原red日志与XML保留。

神经调用、完整M3输入token observer、实际新版业务服务、官方答案和正式报告质量均未由本轮验证；输入字符下降不消除 `budget_unknown`，也不追认旧17次空数组为正常合同结论。后续先完成真实多源采用／同registry接入，再按 [官方验收计划](./oer-official-business-acceptance-plan-20261010.md)作真实业务对照。

## 独立实际wire审计

独立Python没有调用Java decoder；按v2协议恢复records、再解v3字典，保留数字lexeme和对象键顺序，分别对比actual v2与actual decoded完整树，16records均一致。89个literal边界、344个literal strings（含重复）仍在v3 inline，missing=0；120个字典项无literal后代、全部从source可达，unused=0，精确引用DAG最大深度2。三个实际输入SHA前后不变。

收据 `.local/state/oer-source-metadata-wire-independent-20261010/actual-audit2/independent-actual-wire-receipt.json` SHA `3af98d1ea7341b647635231acbab83beb66e3376bcdc9c28b93bc5964dc58a66`。审查v3源码SHA `9a4f0d26cd833afa6fb14126a0f65971868250ccd388b03c85a44c7ea67f41a6`。本审计0模型／HTTP／DB、0产品修改，不再跑Maven；机器可逆不作为神经效果证明。
