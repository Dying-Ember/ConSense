# Canonical 原件证据组件与接线接口

更新：2026-10-10。组件是只读展示能力，business v3 已在 `AdviceView.vue` 和 `VettingView.vue` 源码做最小接线，尚未部署在线前端。它不启动 OCR、不重建索引、不生成答案、不修改合约。业务资料范围仍以服务端 [OER 规则](../../ai_consense_service/docs/oer-scope-and-rules.md) 为准；共享组件不能扩大模块权限。

实现文件为 `src/api/canonicalEvidence.ts`、`src/api/sha256Bytes.ts` 和 `src/components/CanonicalEvidencePanel.vue`。调用者传入明确的 canonical adoption 地址，组件从同源 Java 接口读取绑定单元，再读取该单元的全部 `assetRefs`。它不接受后端任意 URL，也不从文件名、相似文字、检索 episode ID 推导原件位置。

## 组件输入

```vue
<script setup lang="ts">
import { ref } from 'vue'
import CanonicalEvidencePanel from '../components/CanonicalEvidencePanel.vue'
import type { CanonicalEvidenceAddress } from '../api/canonicalEvidence'

// 由业务 API 返回的当前绑定与明确 canonical chunk 共同构造。
// 未取得完整身份时维持 null；不能猜测、补哈希或套用旧项目地址。
const selectedCanonicalAddress = ref<CanonicalEvidenceAddress | null>(null)
</script>

<template>
  <CanonicalEvidencePanel :pointer="selectedCanonicalAddress" locale="zh-Hans" />
</template>
```

`pointer` prop 实际表示待读取的地址；服务器返回的完整 pointer 是经核验后的内部状态。`locale` 支持 `zh-Hans`、`zh-Hant`、`en`，默认 `zh-Hans`。

地址必须包括：

| 字段 | 来源与含义 |
| --- | --- |
| `addressKind` | 固定为 `canonical_adoption_unit`，用于显式区别检索 episode 中的 source unit。 |
| `projectId`、`documentId`、`module` | 当前项目、实际 Java document ID 和有权限的模块；module 仅接受 `drafting`、`vetting`、`advisory`。 |
| `unitId`、`canonicalChunkId` | canonical projection 的 adoption unit ID 和确切 chunk ID。`retrievalEpisode.sourceUnits[].unitId` 是另一个身份空间，不能直接套进端点。 |
| `originalSourceSha256` | 原文件实际字节 SHA。 |
| `javaParsedSourceHash` | 当前 Java 原生 parsed revision。 |
| `sourceRevisionSha256`、`canonicalSourceHash` | 分别为来源修订身份与 canonical 文本／结构身份；不能用原文件 SHA 互相替代。 |
| `projectionSha256`、`projectionRevisionSha256` | 本次完整 canonical projection 与 projection revision。 |
| `sourceFilename` | projection 中保存的原来源文件名，按原值比较。 |
| `sourceOrder` | canonical chunk 原始读序整数或 `null`；不从 chunk ID 排序，不把 unknown 改成 0。 |

六个 SHA 必须是 64 位小写十六进制。所有字段与回包逐项匹配，数字／字符串 document ID 按其十进制身份匹配。来源、修订、模块、chunk 或文件名不匹配时，组件报告错误并停止读取资产。Java 仍必须在服务端复验当前文件、parsed revision、routing 与模块资格；前端比较不能替代当前绑定检查。

## 固定同源端点

```text
GET /api/evidence/{projectId}/documents/{documentId}/canonical/{projectionSha256}/units/{unitId}?module={module}
GET /api/evidence/{projectId}/documents/{documentId}/canonical/{projectionSha256}/assets/{assetId}?module={module}
```

Pointer 使用现有 `ApiResponse` 包装，要求 `code=0`。响应身份包含地址的全部比较字段和 `addressKind=canonical_adoption_unit`，还包含 `bboxConvention`、`physicalPages`、`eligibleModules`、`parts`、`sourceSpans`、`tableGroup`、`assetRefs`、`relations`、`adoption`、`sourceQuality`、`unknowns` 和 `humanConfirmed=false`。文件名及 sourceOrder 使用当前 Java DTO 返回的原值。

请求固定使用 GET、`credentials: same-origin`、`cache: no-store` 和 `redirect: error`。ID 编码且拒绝路径穿越／查询注入；pointer 或资产里的 `url`、`backendUrl`、`artifactDescriptor.relativePath` 等不会作为请求目标。部署时由前端所在 origin 的 `/api` 转发至 Java，浏览器不需要 Python 模型服务地址或模型密钥。

资产响应要求：

| 校验项 | 预期 |
| --- | --- |
| `X-Source-Sha256`、`X-Projection-Sha256` | 与明确地址的原文件／projection SHA 一致。 |
| `ETag` | 等于 `"{asset.sha256}"`。 |
| `Content-Type` | 与声明 MIME 一致，目前支持 PNG、JPEG、WebP。 |
| 实际长度、magic | 与声明 byteCount、图像文件头一致。 |
| 浏览器 SHA | 始终复算实际字节；`crypto.subtle` 可用时优先原生 SHA，否则执行固定版本纯 JS SHA；不一致拒绝显示。 |

LAN v2 固定依赖 `@noble/hashes@1.8.0`，使用成熟的纯 JavaScript SHA-256 实现，不自行编写密码学算法。`http://127.0.0.1` 通常属于浏览器可信 loopback，局域网 `http://{IP}` 不属于 secure context；组件按实际 API 可用性检测，不能假定它们都有 SubtleCrypto。没有或无法使用原生 digest 时，portable 分支仍复算全部字节；较大资产分 1 MiB 更新并让出事件循环。两路都只有 SHA 匹配后才创建 Blob URL，界面显示已核对，内部 `browserShaImplementation` 区分 `webcrypto`／`portable`。SHA 门控未关闭，也没有因为 LAN 而永久禁用图片。Java 校验继续作为独立一层。

最初 v1 的 LAN 分支允许图片但保留 `browserShaVerification=unavailable`，只有响应身份／长度／magic 的浏览器检查。该行为及 v1 的 13 项测试收据保留在 `.local/state/oer-canonical-frontend-20261010/run-20261009165552887/`；它已被 LAN v2 的完整浏览器 SHA 门控替代，不能混写成 v1 已执行 portable hash。

## 完整证据与显示边界

组件先按原 PDF 物理页、同页可用 sourceOrder／原数组序排列 parts；资产按页及关联 part 源序排列。它加载并展示单元的全部资产，不按 asset ID 的字典顺序，也不只返回检索命中的 seed crop。跨页表组的页集合与单元一致；资产关联 block 必须存在且落在声明物理页内，sourceRegions 的原页 bbox 与关联 part 一致。空资产、缺页、缺 block、HTTP／身份／字节失败和浏览器图片解码失败均显式保留，不升级为完整表组。

展示内容包括原文件名、原文件 SHA、原 PDF 物理页、全部资产、各区域原页 bbox、按原文转义的结构文字，以及原始 sourceQuality／unknowns／humanConfirmed。HTML table 作为 literal text 放在 `pre` 中，不通过 `v-html` 执行，也不自动修拼写或重构成已验证的表格。图片标题使用中性的“跨页表组”，不将模型可运行或资产下载成功写成整源质量通过。

位置采用 `normalized_xyxy_original_page`，范围在 `[0,1]`，以原物理页为坐标参照。`null` 显示为未知。当前只显示 bbox 数值：资产可能是裁图，缺少可核验的完整原页／裁图映射时，不能把原页坐标直接叠在裁图上。未来 overlay 接线必须附带已验证的 canvas 与变换身份。

`humanConfirmed=false` 与所有 unknown 均保留；Codex 对源区域的采用记录不被组件改写成人工确认或整份原件语义验收。改变 pointer 时中止旧请求、忽略迟到响应并释放 Blob URL；卸载或清空 pointer 也释放资产。

## 最小业务接线

后端当前 `FindingEvidence`／`EvidenceItemVO` 可选单个 `canonicalEvidence` pointer；前端 `FindingEvidence`／`EvidenceSnippet` 对应可选 `CanonicalEvidenceAddress | null`。Vetting 的显式 `evidenceItems` map 透传该字段，在每个证据项的原文之后展示 shared panel。没有 pointer 时沿用原有文字／原件打开行为，不猜测 canonical 地址。旧 `/original?sourceHash=...` 仍使用 Java parsed sourceHash，不能替换成 canonicalSourceHash。

后端 `CitationVO` 可选 `canonicalEvidence` pointer 列表；前端 `Citation` 对应可选 `CanonicalEvidenceAddress[] | null`。Advice 在现有 activeCitation 来源面板中逐一展示列表全部明确成员，包括完整跨页表组和关联 heading。选择引用后读取图片，未选择引用不预取全部聊天资产；不从 episode ID 推导 adoption ID，不把列表降成 seed 单元。business v3 保留原有引用文字、分数和选择行为；后续 scope v4 的评分语义见下节。

Q11 的审查→可编辑咨询草稿入口继续使用原规则；没有自动问答，也没有将原件证据展示变成新的审查／咨询工作流。项目切换清空已选证据并释放图像 URL；不能把上一个项目的 canonical 地址延续为新项目证据。

## Advisory 范围与来源状态（scope v4）

索引状态和显式刷新请求均传当前选定范围，缺省值为 `fullset`：

```text
GET  /api/advice/{projectId}/index/status?scope={fullset|tender|contract}
POST /api/advice/{projectId}/index/rebuild?scope={fullset|tender|contract}
```

项目与范围均 URL 编码。项目／范围切换立即清空旧索引状态、错误、选中引用和图像；响应绑定项目、范围、切换 epoch 与请求序号。A→B→A 不接受先前 A 的迟到返回。新 rebuild 启动时失效旧 status 读取，迟到 rebuild 的结果、toast 和 finally 不覆盖新状态或清除新操作的 busy。卸载使所有请求结果失效；此组件只清除自身仍拥有的 busy 提示。若回包明确声明另一 `sourceScope`，当失败处理；旧无该字段回包维持兼容。

`IndexStatus` 原样保存 optional `evidenceBackend`、`sourceScope`、`indexKey`、`indexSignature` 和 `indexReason`。三种已知失败原因 `canonical_index_receipt_missing`、`canonical_index_unavailable`、`current_sources_unavailable` 用局部说明提示刷新或核对配置／来源。当前 status/rebuild 失败在界面说明，旧失败忽略；底层 API 的全局错误提示在这两个由界面负责的请求上关闭，避免旧失败污染新项目 toast。当前 rebuild 失败清除旧成功状态。scope 切换后旧 ask 返回也不能设置当前回答／引用，新的请求仍保存点击时的 LLM profile。

`Citation.canonicalSourceUnit` 为 optional 原始 metadata，通过 Vue 文本插值显示完整 JSON。`complete_observed_unit` 只说明已返回观测到的完整结构单元，不能推升为语义适用、限定条件完整或人工确认；`unknown`、`reason`、`requiredMemberIds`、`members`、`qualifiersComplete='unknown'` 和 `semanticScopeVerified=false` 均保留。`scoreMeaning='actual_reranked_seed'` 显示实际重排分数；`unranked_context` 说明未独立评分，不展示其兼容数值 0。旧记录无 scoreMeaning 时继续显示旧 score，未知新含义不冒称重排分数。

历史来源失败保留原 saved pointer，绝不重链新 projection 或图片。共享证据 GET 只识别符合 HTTP status 的确切安全枚举：404 的 `CANONICAL_BINDING_UNAVAILABLE`，409 的 `CANONICAL_SOURCE_STALE`／`SOURCE_ROUTING_STALE`／`SOURCE_ORIGINAL_BYTES_CHANGED`。三语说明分别区分绑定不可用、来源／parsed revision、身份／范围及原字节变化；未知、非 JSON 或不匹配的失败维持 generic HTTP 错误。重新读取仍请求同一历史地址，不跟随错误回包中的 URL，不自动重新问答。

## 验证与未完成事项

使用服务端共享的 Python-authored 三页 fixture，页 3／4／5 的资产 ID 字典序与页序不同，可核验源顺序。该 fixture 是契约样本，不是实际合约质量 gold。

- LAN v2 Node／真实 Vue SFC 自定义 host tests：16 项通过，0 失败、0 跳过。覆盖身份错配、episode 地址误用、固定同源路由、三页全部资产／文字顺序、block／页／bbox 关系、SHA／headers／magic／长度、literal HTML、unknown、空资产／缺页／HTTP／解码失败、stale／abort／URL 清理。新增已知 SHA 向量、padding 边界、二进制与超过 1 MiB 的实际 bytes、原生 digest 失败、LAN 完整三图显示及同长度篡改被拒绝。
- 当前 Java DTO 单独编译到忽略目录，再用 Jackson 2.13.5 生成真实 JSON，前端校验 2 项通过：TextNode filename→原 string、整数 JsonNode→原 number、NullNode→null，均不包成 `{value: ...}` 或强转成 0。旧 compiled DTO 因缺少本轮新增 addressKind 被 SHA 门控拒绝；JShell local 类加载失败保留，静态 probe 执行成功。没有改 Java 来源，没有启动 Spring 服务；这不是在线 MVC／真实原件验收。
- 直接运行 `node .\node_modules\vue-tsc\bin\vue-tsc.js --noEmit` 通过。此前 `npm run typecheck` 未启动 vue-tsc：Windows npm `.cmd` shim 把路径 `R&D` 中的 `&` 当 shell 分隔符；该失败保留，不能记成 TS 检查失败或通过。
- LAN v2 阶段 Q11 handoff／controls 14 项通过，0 失败、0 跳过；该阶段未修改两个业务 View，收据不追认后续业务接线。
- 单独 Vite 组件 library bundle 输出忽略目录通过，确认 portable SHA 浏览器依赖可打包；没有覆盖在线 `dist`。package-lock 保持既有 package entries，只新增 root noble 依赖及固定包 identity；npm 首次刷新已有 optional metadata 的变动已从 preinstall 快照恢复。

LAN v2 日志与源码／Java DTO／fixture 的 SHA 收据位于根目录 `.local/state/oer-canonical-frontend-20261010/lan-v2-20261009170220433/`。

business v3 独立验证位于 `.local/state/oer-canonical-frontend-20261010/business-v3-20261009171746334/`：

- 33 项 Node tests 通过，0 失败、0 跳过：16 canonical tests＋14 既有 Q11 tests＋3 新业务数据流 tests。实际渲染两个 Vue View，验证可选 pointer 经显式 map 到全部 3 页、Advice 全部 table／heading 成员、LAN portable SHA、legacy 原件链接和无 pointer 时零 canonical 请求。项目切换释放全部图像且不复用旧地址。
- 9 个 LLM profile 回归场景记录 PASS；自定义 host 的原有 Teleport 目标警告保留，没有据此声明浏览器 UI 通过。
- vue-tsc 通过；完整应用 Vite bundle 输出本轮独立忽略目录通过，未覆盖在线 `dist`。原有 DraftingPdfPreview 静态／动态 import 混合提示保留，不扩为本轮修复。

没有启动前端／Java／模型，没有部署在线 bundle，没有访问真实 canonical Java→Python→PDF 接口；真实浏览器图片呈现、业务 API 回包与全表组验收仍待执行。测试中触发图片 error 事件验证失败处理，不冒称执行了浏览器图像解码质量评估。

scope v4 独立验证位于 `.local/state/oer-canonical-frontend-20261010/scope-v4-20261010012935536/`：43 项 Node tests（18 canonical＋17 controls＋8 handoff）通过、9 个 LLM profile PASS 场景、vue-tsc 与完整应用隔离 Vite bundle 通过。新增 10 项验证覆盖 scope query／编码／缺省、A→B→A／项目迟到读取、rebuild 与 GET 排序、旧 finally／卸载、当前失败／旧失败、引用图像释放与旧 ask、observed／unknown metadata 和 context score、历史来源拒绝与零重链。第一次新增 controls 有 4 项因测试定位使用了不存在的按钮英文文案而失败，改为实际 `Refresh evidence index` 后通过；未将该工具运行冒称模型或在线失败。原有 Teleport 自定义 host 和 PDF import 警告保留；本轮未启动服务、改在线 dist、调用模型或进行真实浏览器业务验收。

## 2026-10-10 实际GCT引用回显与region omission修复

本补充是本轮新验收，不重贴历史v4的完成状态。隔离前端5174代理隔离Java18081／对应Advisory8869，读取此前真实M3问答的持久历史。原件为 `04 - GCT.pdf`，SHA `ca3944c16890cb5ea7f8b24e2fab613c7ffd621809f7f850529901649e6f15f8`，仅原物理页1 reviewed局部，不是整源／官方八题验收。本轮没有新模型调用。

先前stale来自独立probe负例未持久恢复的H2 text marker，准确恢复原文本并正常checkpoint／close后源绑定和历史hash保持不变；真实HTTP结果详见 [实际bridge报告](../../ai_consense_service/docs/testing/oer-actual-advisory-canonical-bridge-validation-20261010.md)。随后确定另一个wire问题：Spring NON_NULL省略普通region的optional tableGroup，前端将undefined误当非空表组。现在只将缺席optional field规范为null；whole table缺组、null组或非法非空组仍拒绝，sealed原payload不重写。

内置浏览器实测十二处明确region pointer全部加载，各图解码为2480×3509，十二个浏览器字节SHA核对状态可见、没有alert。**它们是同一原物理页的十二个不同区域，不是十二页覆盖。** 另修citation-pane的grid最小宽度，在实测376px侧栏下scrollWidth同为376px，原图不再把两栏撑出视口。返回的是完整原页；缩放图片和位置文字不等于已实现高亮裁框或高精度桌面阅读。

证据在根目录 `.local/state/oer-browser-acceptance-20261010/region-group-v2/`：`browser-receipt.json`、`dom-snapshot.txt`、`actual-advisory-original-page.png`与isolated build／receipt。旧stale截图和旧构建／进程收据均保留。当前5174仍为该扫描验收bundle；后续native源码没有覆盖在线5173/dist，也未在当前5174声称native通过。Java重启后live index receipt缺席，界面提示刷新索引；历史原地址本轮能核验，不因此自动重建或重链。

## Native public v2 展示分支（代码验证，未做真实原件前端验收）

按已验证pointer的 `projectionSchema=consense.canonical-projection.v2`／`producerKind=native_docx`明确选择native分支；要求当前compact storage、`not_defined_for_native_docx`和`unavailable_pending_verified_rendition`。root bboxConvention接受NON_NULL造成的缺席／null，Parts禁止任何pageNo／physicalPageNo／bbox／bboxConvention alias，包括null；scan v1原页／bbox／资产门控继续有效，未知schema或生产者拒绝。

保留部件名、准确Clark node path、原SHA、文字通道、UTF16范围、blank paragraphs、完整nativeTableTopology与unknown。DOM展示原生位置及未核验视觉边界，不能显示P0、undefined页或假Word原图。原文字面文本不润色，nonDeletedNonDirectStrike也不被称最终有效正文。完整原生表观察可展开原JSON，不能冒称已完成表格可视化。

输入使用与Java同一actual producer fixture `ai_consense_service/src/test/resources/oer/canonical/authored-native-public-v2.json`（90,153bytes，SHA `9e50fe75904a6b92c8a082f036f6d5985adc6f02305e6a01d161d95791ab8182`），不是手写瘦pointer。五项新契约验证覆盖literal/UTF16／blank／nested、明确discriminator／禁止假页、改source／CAS ref／channel拒绝、whole group完整性、native panel零资产请求与真实位置文案。

最终50项Node tests（25 canonical＋17 controls＋8 handoff）全部通过，vue-tsc exit0，完整app isolated Vite build exit0。收据 `.local/state/oer-canonical-frontend-20261010/native-v1-20261010/validation-receipt.json`，SHA `2ecf66b687a1697ee76d45bbd114b4b59a7af3ed35e01e25ef5394938bdc9673`。最初native测试的跨VM数组prototype比较和非所选channel mutation期望已修正；TS target不支持Object.hasOwn，改用hasOwnProperty.call。没有修改TS target、线上dist或已运行Java；现有PDF import提示保留。这50项与v4／region阶段测试重叠，不相加；新native bundle尚未启动或进行真实浏览器验收。
