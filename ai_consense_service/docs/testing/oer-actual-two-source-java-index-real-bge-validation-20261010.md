# 实际 GCC／2b SCC 的 Java→统一 OER 索引与真实 BGE 核验

日期：2026-10-10，Asia/Shanghai。本页只记录本轮实际来源索引和只读核验；官方题答案、Vetting 报告及浏览器验收分别记录。遵循 [范围规则](../oer-scope-and-rules.md) 和 [实施交接](../HANDOFF-ocr-embedding-rerank-20261008.md)。

## 当前事实和边界

本轮 root 是唯一主动 Java index／模型请求者；只读审计代理没有调用 index、retrieve、LLM，没有加载第二套模型或打开运行中的 Qdrant。实际 8875 `/index-canonical` 已完成真实 BGE-M3 调用。Java 外层 rebuild 和随后 status GET 均首次 HTTP200／code0，`vectorReady=true`、`stale=false`、`evidenceBackend=canonical`，`expected-sources.json` 已封存；最终只读身份／journal 核验通过。这个结果证明实际两源索引就绪，不追认为官方题通过或前端验收。

实际 Java 项目为 `official-q16-source-90602e8f-85b8-4e1a-ad61-2625c654115e`，documentId 为 GCC=`1`、实际 2b SCC=`2`。这两个 ID 来自同一个 fresh 持久项目及 source-ID handoff，均不是历史准备 ID。NTT／SCT／SCC 标准模板继续仅供 Drafting；本轮 SCC 是实际 2b 项目原件。

GCC Java 全件仍为 PARTIAL；本轮只采用原 PDF 物理 65–70 页的 50 parents。SCC 有 22 parents，SCC17 继续 partial，`wholeSccAccepted=false`、有效编辑验收 unknown、原生视觉返回 unavailable。完整保留 published payload 不代表整份合约语义或编辑指引处理已经验真，没有权重训练。

## 冻结来源和实际请求

运行与原始证据留在忽略的本地目录：

- Java/source handoff：`.local/state/oer-official-source-business-bridge-20261010/run-060145-06ba7b/actual-source-id-handoff.json`，SHA `8e8b9d138d4e657fe02780b3be8d19b19d8e713b8638448852a7cd69530f84e2`。
- Java preparation：`.local/state/oer-official-q16-first-run-20261010/prepare-225151-d858b0/`。
- Advisory unified runtime：`.local/state/oer-unified-actual-preparation-20261010/run-222702-e68e99/`，runtime manifest SHA `f88b1c538344abbf6522faf924306eadf95706d68c9e85caaf108e379cbbf9e8`。
- 实际 index journal：上述 runtime 下 `retrieval-state/run-evidence/20261009T225551.300529Z-d0f5435c94254b4b98139a3ec55671c0/`。
- root `expected-sources.json`：上述 preparation 下，1,245 bytes、SHA `26cbc94315f1385cc9cb1e8497923417b8a03c4eb5b98c919b42a416c18295c1`。
- 最终 [只读 audit receipt](../../../.local/state/oer-official-actual-index-readonly-audit-20261010/audit-231211-b15036/readonly-index-audit-receipt.json)，SHA `715d6b38e7440e3340dfb345dc3c33030b6cdddd5bcc50686faa91d8ea6469ae`。其 input ledger 保存每个实际读入文件的 SHA／bytes，audit network／DB／model／index／retrieve／LLM 操作均为 0。

| 实际来源 | original source SHA | public file SHA | registry address |
| --- | --- | --- | --- |
| GCC，ID1 | `d7d74db6d9aee390093b9e22bb30dccdbf3c0316d47635922109422278d161e7` | `f0f42df3b3d96ed7a77620b9e5161fbbf5bbd9f1ec59b04122f75e34c09b7b85` | `94e151f2d3e4ebfd4442305181c974c52f1bf5d901f23b9df166dda2585176c6` |
| 实际 2b SCC，ID2 | `8ba80d183f6adf7a11eeb08f75e32c2bffdc3960f969202fcbed357e477f7af3` | `2a5dae22b390e9c04648b9620e3977ad9ec7c9d3d01f624a7c6fc77fb0058325` | `88a6396324a4d3f6b7f4ce4362bde5dee4e1e34fa12340657cb3f67a06005f4b` |

actual canonical request 的地址顺序是 SCC→GCC；`nested-operation-input` 的完整 derived IndexRequest 和持久 metadata 的完整 `chunks` 均逐对象、顺序等于 SCC public 22 parents＋GCC public 50 parents。不能只凭 72 的总数证明没有错源或丢字段。

实际 index key 为 `official-q16-source-90602e8f-85b8-4e1a-ad61-2625c654115e::canonical::advisory::a652a9d35d5d407d75aea6b31f63ab8e9e708e9ae061deab85e3fc38cbca8b12`；Python response signature 为 `b7bf76d25534183567abe0ae1edaf4fd764ad73dee34b7b82e8bd2c93e3d12b9`。Java 最终 expected-sources／rebuild／status、实际 OER request／response／metadata 的 indexKey／indexSignature 全部一致。

持久 metadata 文件为 `retrieval-state/projects/6f1f490252b1f726c16b75b35495cd04032632fe3c4ce5ae5276040a6837bbff.json`，4,532,999 bytes，SHA `7e67db58cc193df3a616cb3fddcad91e84019467f09a4408772048d98908b9cc`；实际 window manifest SHA 为 `6bf0f1a96218c158fcb83fe25faf8ceb645c8b60f7e96d8783e056d1775c16c4`。132 个 frozen code／sample 文件全部与 runtime manifest 中 SHA 一致。

## 真实模型、token 与向量

本轮 actual `window-model-loaded` 记录 BGE-M3 revision `5617a9f61b028005a4858fdac845db406aefb181`，模型参数 `actualDevice=cpu`、`actualDtype=torch.float32`。recipe 继续 CPU FP32、batch2、short512／overlap64、long structure2048、strict、offline；没有恢复 Bonsai、切换 GPU 或改变运行中的其他服务。

72 parents 实际生成 125 embedding points：73 short＋52 structure，实际 encodedWindows=125、fixedCacheHits=0、cached=false。52 个结构 context 保存在 metadata 和实际计划日志；组成不升级语义采用。

实际 `featureMode=inference_tensor_features` 共 125 rows，完整 tokens 最大 1,115，对应 actual cap 2,048，全部 `effectiveTruncation=false`。这不是只读取 tokenizer 计划；但现有 guard 没有序列化原始 `input_ids`／`attention_mask` 的 device／dtype。实际模型参数 CPU FP32 有直接日志，原始输入 tensor device／dtype 继续“未记录”，不能从模型参数推填。

实际 journal 68 events 已 terminal completed，事件原文 7,115,146 bytes、SHA `2c869d806885f00c71708124fca277e6fad60ce22d00f143000f005367b254c8`。最终只读审计已核 request／response／events bytes、SHA、ordinal／newline、完整 plan／upsert／metadata。125 个实际向量均 1024 维且有限、归一化；norm min=0.9999999218、max=1.0000001146；1 次 actual upsert 的 window／parent 顺序与 plan 完全一致。该审计不对运行中的 Qdrant 做第二个数据库打开操作；服务的 exact db.count 和已保存 upsert 证据与独立数据库复查区别报告。

short window 的 parentId 直接绑定原始 parent；structure window 的 parentId 绑定保存的合成完整 context，`retrievalSeedParentId`／`originParentIds` 仍绑定实际原始 parents，不能把 context 当新原件或新 rerank origin。首个只读 helper 错把所有 window parentId 都当作原始 parent，保留失败 `audit-231149-c9282c/` 与原 helper `audit-index-attempt1.py`。第二次仅修正 helper 对既有结构 schema 的理解，产品代码、来源、模型、索引、服务和 gate 均未改变；新独立 audit `audit-231211-b15036/` 通过。

## 本机耗时的证据和限制

| 已完成阶段 | 实测秒数 | 证据含义 |
| --- | ---: | --- |
| Java sources-before GET | 8.025 | root preparation client wall；零 LLM／官方题 |
| Java index/rebuild POST | 738.542 | root 首次真实 HTTP200／code0，包含下列 OER journal 与其他 Java 工作 |
| OER index journal | 116.143 | outer `/index-canonical`，包含 canonical source read／derived request／signature 等 |
| OER response 内部 index | 35.474 | 计划、实际模型编码、向量写入等；不是纯神经时间 |
| Java status-after GET | 398.573 | 独立 current scope／index status 读验；回复字节与首次 rebuild 相同 |

OER journal 与内部 index 的差为 80.668s，包含 wrapper／来源读验及 signature 等边界工作，不能全部归为 BGE 推理。Java rebuild 与 OER journal 的差为 622.400s，包含 Java 变换、ORM、前后身份重验、网络及其他工作；两份栈只能证明采样瞬间的重验等待，不能把全部差值叫“纯 gate 耗时”。738.542s 包含 116.143s，后者也包含 35.474s，三者不能相加。模型参数加载与 actual upsert 的实际时点由 event elapsed 提供；不据 CPU 数据推算 H800、GPU 或 M3 的 tokens/s。

root 的两次 owned Java Thread.print 同样留在 preparation 目录：

- `java-thread-0655.txt`，2026-10-10 06:55:13：`OerBusinessEvidence.index→current→currentBindings→readBinding→recheckNativeSnapshot→HTTP response headers`。
- `java-thread-0701.txt`，2026-10-10 07:01:01：`AdviceService.rebuildIndex→canonicalStatus→assertCurrentSelection→OerBusinessEvidence.assertSelected→currentBindings→recheckNativeSnapshot→HTTP response headers`。

这两份栈证明对应瞬间的等待发生在 canonical snapshot 重验，后一份是索引后的当前来源选择／status 复验；不是模型 forward 栈。它们是时间点样本，不能推出全部等待均来自某一函数或精确占比。Java rebuild 原响应 971 bytes，SHA `2dafb5ce92715bcdf12c14b89f817be44cdcdc24789954d75b1fc17a13606f70`，结束于北京时间 07:04:18.547671。

status-after 同为 971 bytes／同 SHA，结束于北京时间 07:10:57.126266；这次独立 GET 的398.573s不能并入前面的rebuild耗时。本轮 preparation 阶段真实 LLM／官方题／权重训练调用均 0，之后的业务运行按各自实际 receipt 记录，不能沿用准备时零调用状态。

## 后续验收

本页不能作为官方 Q1.6 答案、图输出、整源覆盖或正式 Vetting 报告已通过的证据。本轮 Java preparation 与 actual index 身份已经核验，官方题和报告由 root 唯一主动执行。后续只读核验实际 BGE retrieve journal、returned sourceUnits 和引用来源，回答内容／报告质量由各自评分记录承接。
