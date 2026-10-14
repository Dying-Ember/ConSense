# Canonical 来源读取的配置超时与默认10秒冲突

2026-10-10，Asia/Shanghai。此故障在真实GCC＋SCC项目采用登记阶段暴露，发生于模型调用之前。遵循 [OER范围](../oer-scope-and-rules.md)，与 [完整原件准备](./oer-actual-official-source-business-preparation-20261010.md) 及 [LOB修复](./oer-native-evidence-lob-persistence-20261010.md) 分开记录。

## 原场景与单因素对照

实际fresh来源项目的GCC `registerReviewed` 首轮失败：`.local/state/oer-official-source-business-bridge-20261010/run-060145-06ba7b/register-061026.log`、对应receipt／Surefire保留。Maven42.394秒，`OER_EVIDENCE_UPSTREAM_UNAVAILABLE`，checkpoint成功且正常关闭，没有追认binding成功。准备配置已经 `timeout-ms=120000`，但 `CanonicalEvidenceBackend` 只配置OkHttp `callTimeout`；socket `readTimeout` 仍默认10000ms。

对同一实际GCC canonical地址进行两个独立客户端请求，不改服务／来源／配置／门控／模型：

| 实际客户端 | 结果 | wall seconds |
| --- | --- | ---: |
| call120000ms，read默认10000ms | `SocketTimeoutException: Read timed out` | 10.067145 |
| call120000ms，显式read120000ms | HTTP200；完整1,503,684bytes／SHA等于冻结public | 43.909924 |

收据为同run的 `canonical-actual-read-timeout-comparison.json`，SHA `ec8df078c7d78ffe333fd0ba2f9cfa0eb32212cb4b1a8087a9db2fa82aded515`。返回完整byte SHA `f0f42df3b3d96ed7a77620b9e5161fbbf5bbd9f1ec59b04122f75e34c09b7b85`，projection `94e151f2d3e4ebfd4442305181c974c52f1bf5d901f23b9df166dda2585176c6`。两个request都是实际来源读验，不是神经／LLM调用。43.9秒是当次服务冷验／并发环境耗时，不替代此前31.351秒测量，也不是硬件吞吐承诺。

该对照可区分默认读取限制、上游失败、总超时不足三种原因：同源仍返回完整200，耗时小于120秒，只改变read限制就消除本次症状。根因是read配置缺失。较大的总超时不自动覆盖OkHttp独立的读取超时。

## 最小产品修复与回归

`CanonicalEvidenceBackend` 新增一行 `readTimeout(properties.getTimeoutMs(), TimeUnit.MILLISECONDS)`。总call deadline、HTTP固定路径／不跟redirect、来源／projection身份、16MiB snapshot／batch cap、完整冷验均保持。当前默认配置仍30000ms；独立CPU开发runtime显式采用300000ms，配置与硬件耗时分开记录。`HttpSupport` 既有实现已经分别配置read／write／call timeout，不作无关修改。

新增 [CanonicalEvidenceBackendTimeoutTest](../../src/test/java/com/consense/service/sources/CanonicalEvidenceBackendTimeoutTest.java) 走真实loopback HTTP backend方法。11秒延迟响应在25秒配置下应完整读回，旧源码仍在默认10秒结束；另用100ms配置证明较短deadline仍拒绝慢响应。完整真实GCC对照仍另保存，synthetic延迟fixture不证明CAS／法律质量。

| 阶段 | 完整复制checkout | 结果 | receipt SHA |
| --- | --- | --- | --- |
| 旧源码red | `.local/state/oer-canonical-read-timeout-20261010/red-061409-eb1abc/` | 1 test／1 error／0 failure／0 skip；default10s读取失败；Maven31.366秒 | `8f93206ee62f1ff261bb95f38f4d3c512b409ab8dc01593f128500a236243558` |
| 最小fix | `.local/state/oer-canonical-read-timeout-20261010/fixed-061507-bad706/` | 9 suites／87 tests，0failure／error／skip；Maven45.755秒 | `cb787f6208b0aef873803d2b9499cc15396618dfa419d8c9b29100128522b50a` |

87由XML逐suite复算：timeout2、collection backend3、canonical service19、fresh transaction9、native DOCX8、native PDF10、retrieval17、PDF retrieval5、Advice integration14。相关历史58／151／63项存在重叠，不能相加。编译与test全在复制checkout；在线target、8080／5173／8092及DB未修改，没有GPU／OCR／LLM调用。

## 实际剩余门槛

修复源重新freeze／Jar并再次实际登记两个来源、正常关闭／重开／pointer读回正在进行。第一次超时失败和原Jar不覆盖。配置超时生效不代表已经解决重复cold复验／deepcopy／JSON成本，不引入validation cache来跳过当前来源核验。

当前官方问题实际执行0；此次source HTTP对照和87项tests均不增加官方分母。正式多来源Vetting语义评分0、权重训练0、H800实测未完成。首次Q1.6须由实际Java项目的准确两源binding／current BGE index生成；当前图交付unsupported仍单独记录。
