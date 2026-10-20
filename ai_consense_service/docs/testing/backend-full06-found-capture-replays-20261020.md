# Full06 找回旧捕获后的五项独立回放（2026-10-20）

实际新增补证为 **5个原skip入口、5条测试＝4 pass＋1 failure，0 error／skip**。原Full06仍为 **3,759＝3,660 pass＋9 failure＋14 error＋76 skip，BUILD FAILURE**；原76节点账本字节不变。此前60个原入口对应110条通过的补证见[既有跳过项补跑](backend-full06-skipped-replays-20261020.md)，本票没有重复那些执行。

总证据为[4＋1汇总](../../../.local/e2e-20261020/vetting/legacy-found5-combined-summary.json)，SHA `6590e9b4140ecbab0a7a18c61053123bc3c4bc98aff8f7a9176ff39dc5ca6e98`。它记录每组新XML、旧身份映射、源树及全部原文件／复制件的bytes／SHA。新4通过不把旧language失败算作origin门通过，也不把任何独立补证加进Full06正式pass分母。

## 定位范围与原件身份

此前对主工作区`.local`的限定文件名扫描未定位这些材料，不能外推成其他工作树也不存在。本轮从仓库`git worktree list --porcelain`及既有作者／独立审计收据追到原调用目录，仅只读查看、将原字节复制到忽略的专项目录，未更改任何原工作树。

| 材料 | 找回的原路径／匹配条件 | 本次结果 |
| --- | --- | --- |
| Advice Observed | `C:/Users/Administrator/Documents/Codex/advisory-alignment-20261014/actual-reference-smoke-01/official-1.6/`：`provider-answer-attempt-1.txt`与`captured-public-aliases.json`；3 sections、32 edges、10 alias，保留extra edge id及缺失actor的原错误 | 1 pass |
| Advice Run02 | 同原父目录`actual-reference-smoke-02/official-1.6/`：两个`provider-answer-attempt-*.txt`与`exact-emitted-sources.json`；两答各4 sections、45／29 edges、10 sources | 1 pass |
| Advice Run03 | 同原父目录`run03-protocol-fix-01/`：`provider-answer-attempt-0.txt`与`published-source-dtos.json`；4 sections、4 sources，sections1–3原checks缺失保持 | 1 pass |
| 291变量图 | `C:/Users/Administrator/.codex/worktrees/drafting-human-review-implementation/ConSense/ai_consense_service/target/review-291-fixture.json`，5,698,309 bytes，SHA `5c002b7a1a01c7998b64287cfe7ff37ea1796ccb306f1c5bb9fe6680b25cd804`；kind=`consense-review-v1`、`state.variables=291` | 1 pass |
| 旧language来源原票 | `C:/Users/Administrator/Documents/Codex/consense-implementation-20261013/language-origin-gate-01/`：`00695-model_parsed_records.json`与`00667-context_selection.json`，原记录index2为issue／language，affected Chunk精确找到 | 1 failure，未到origin门 |

上述291文件名不含`graph`，其变量也位于`state.variables`；没有用24变量新图替代或构造291节点。原工作树和主`.local`留存XML中的旧通过只作历史定位，不作为本次通过。本次结果来自新的独立执行。

语言两原票分别为8,395 bytes／SHA `e0ee7cc9bf001dfa85bb488f4fca7cf00e066b58a01a408cf9a5a2070ebcb00d`，321,536 bytes／SHA `5e5632152a73b9ebc4c1de7a553e108852d47191a81bc37c976b83c0d4ebe3ff`；均与旧独立`language-author-receipt-audit.json`声明的expected SHA精确匹配。

## 两次新执行及五个身份

两独立项目均直接复制Full06原冻结树`534f0925749971bec2fc6bb7325aa7a841da98537c1ef17dccce4fc5725abb52`，各1,482个源文件执行前后逐一核验bytes／SHA，源代码和测试改动为0。10份旧fixture原件及复制件同样在执行前后核验。每次使用独立TEMP、4 GiB堆、180秒总进程上限。

第一组实际Maven执行为4 suites／4 pass，exit0／BUILD SUCCESS，证据[found4 summary](../../../.local/e2e-20261020/vetting/legacy-fixture-found4/summary.json)，SHA `d3d91772c6f122d55238310869df1e17be727ef2a3204a488f353d8401b79912`。第二组为1 suite／1 failure，exit1／BUILD FAILURE，证据[language summary](../../../.local/e2e-20261020/vetting/legacy-fixture-language/summary.json)，SHA `d18747a4a9ff3d9bbb086d76cff54f66ae602ddcb98836572139a5390f07f5ed`。

| 原suite | 本次选中的原方法 | 原skip节点类型 | 新XML结果 |
| --- | --- | --- | --- |
| `AdviceObservedOutputReplayTest` | `capturedOutputReproducesAliasAndCheckAndKindFailuresWithoutLegalScoring` | 类级`EnabledIfSystemProperty`占位 | pass |
| `AdviceRun02OutputReplayTest` | `bothCapturedAttemptsRetainEveryObservedFieldRejectionAndValidIndependentAnalysis` | 类级占位 | pass |
| `AdviceRun03OutputReplayTest` | `validJsonStillRejectsAllThreeMissingChecksInOneSafeRepairDiagnosis` | 类级占位 | pass |
| `DraftReviewGraphTest` | `optionalLocalSaved291GraphIsValidatedWithoutCommittingSourceCorpus` | 精确方法 | pass |
| `VettingLanguageOriginGateTest` | `exactSavedActualRecordAndAffectedChunkRemainUnchangedAndCannotBecomeALanguageFinding` | 精确方法 | failure |

首次辅助预检只按method名查原skip，因前三个原XML是suite-name占位而在Maven前拒绝，0测试；完整记录在found4的`preflight-first-failure.json`。后继仅修辅助映射为类级原占位到该类唯一选中的原方法，未改源、断言或fixture，原0测试记录保留。两个新测试JVM均已退出。

## 语言旧原票的实际首拒绝层

旧raw只有assessment／type／severity／title／comment／impact／suggestion／evidence，实际缺少`subDimension`、`factBasis`、`pendingFacts`、`suggestedAction`四个现有业务字段。冻结06的完整数组业务形状门先返回`invalid_competition_business_details`，模型失败状态为`Model response schema invalid`；测试helper却要求模型失败为空并且每条记录都进入后续per-record来源门，于该断言失败。

这是当前严格前门对未变旧raw的实际安全拒绝，**不是已通过旧原票的origin验证**。未补填字段、改action、改旧答复、跳过前门或放宽测试断言。后继10使用同一原fixture时仍须记录其实际结果，不能为回到旧skip而省略配置。

现有Full06真实XML中，同类另外5个使用`VettingCompetitionFixture.details`完整业务字段的authored用例已全部通过，并在公共review helper中断言每条记录都到达真实per-record门。其精确身份如下，证据在[原XML](../../../.local/e2e-20261020/build/round06-full-final/TEST-com.consense.service.vetting.VettingLanguageOriginGateTest.xml)，SHA `d763670ed857d2d9619210ff248bb25ff1a756a4a8e7dd311ffea9cf7d1e3181`；本轮没有重复执行这5项。

- `explicitSameClauseAmendmentAllowsProjectLanguageButWrongClauseOrUnknownRawIdentityDoesNot`：同条款明确amendment正例，错条款／缺原件身份反例。
- `modelClaimWithoutBaselineCannotCertifyProjectAddedWording`：模型自称项目新增不能证明来源。
- `missingQuoteInSameClauseStandardFragmentDoesNotProveAnAmendment`：不同标准片段未命中原句不证明修订。
- `rejectedLanguageLeavesIndependentRiskAndReferenceInTheSamePacket`：语言拒绝保留独立risk／reference。
- `reliableConsistentAssessmentCannotHideRejectedLanguageAsCheckedPass`：consistent不掩盖被拒语言。

这些是已有完整业务形状的authored契约证据，不能代替此次旧原答、真实provider或项目法律／业务验收。

## 后继配置与保留分母

后继Full10所需6个精确JVM属性、10份owned fixture路径及逐文件hash已交Drafting，由[机器配置票](../../../.local/e2e-20261020/vetting/saved-fixture-replay-properties.json)固定，SHA `a09eb574e40f9814ccdd85304cffbf6e3706c35b3552ce0edf4d5104cc0feba3`。配置使用忽略的原字节复制件，无需读取或修改旧工作树。

此前60原入口／110 pass加本次5原入口／4 pass＋1 failure，构成**独立补证**65个原入口已有执行证据，其中64有通过、1有失败；实际115条测试＝114 pass＋1 failure。仍未执行的原入口为11个：9个未确认desktop-x2t runtime门，以及2个精确converter／binding opt-in方法。它们不从原76正式skip中删除。全量原3759、原23失败／错误、后继代码回归、真实模型及浏览器业务结果继续分别报告。

本轮0新模型／OCR／外部引擎／共享18084请求，未触活动DB、索引、ACL、服务重启、正式10项目或原工作树写入。通过旧错误捕获的拒绝回放不等于修复其原始模型答复；Vetting Mini08已知正确冲突召回失败等业务问题继续保留。
