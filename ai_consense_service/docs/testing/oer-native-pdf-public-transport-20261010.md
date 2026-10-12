# Native PDF：完整 CAS 与有界 public wire

日期：2026-10-10，Asia/Shanghai。遵循 [范围规则](../oer-scope-and-rules.md)及 [实施交接](../HANDOFF-ocr-embedding-rerank-20261008.md)。本段是原生 PDF joint projection 后的独立接口封装，不改变 native DOCX、扫描或完整表组的门控。

## 接口与保全

新增 `tools/vetting_eval/native_pdf_projection_transport.py`，提供 `publish_native_pdf_transport`、`hydrate_native_pdf_transport`、`validate_native_pdf_transport`。schema 为 `consense.native-pdf-canonical-projection.v1`；public storage 为 `cas-native-pdf-joint-adopted-public-v1`，version 为 `source-reviewed-native-pdf-cas-public-projection-v1`。full internal storage 不能当 public wire，DOCX／scanned validator也不接受这个独立分支。

public保留完整已采用 prose unit 的原生 literal、UTF16起止、原物理页、region box／frame／真实来源声明、独立whole visual block的SHA／raw provenance／geometry与raster review、条件／父条／续接关系及unknown状态。native glyph inventory、literal span inventory、完整region、region review与未采用inventory使用typed CAS pointer，原生bundle、完整raw Java输出／PDF／三份源码／AP原始和解码流、完整双parser bundle、reference及采用清单仍是实际CAS bytes。文本没有替换为OCR／reference／gold，AP与table text不在当前prose分支中采用。

每次public读取都冷读实际internal bytes，验证native／dual／adoption／reference／source／raster及observer完整身份，重建internal，再精确重建public并要求equality。seal重新计算本身不能作为内容正确证明；缺失CAS不在读取时重建，没有validation cache。transport源码descriptor需严格等于actual code bytes派生的标准descriptor，拒绝改`identityScope`或追加annotation后重新封印；源码还须等于当前进程的实际文件。

单public snapshot上限继续为16,777,216bytes，可降低不可调高；超限拒绝，不删除glyph、blank、AP或拆开已采用whole unit。当前PDF未借用DOCX document-collection／batch schema。后续HTTP完整reply和业务入口仍要独立验证上限。

## 首轮实际 checkpoint

初始运行目录为根目录忽略路径 `.local/state/native-pdf-transport-20261010/run-045921-fa6df2/`。第一次8个focused tests通过，之后独立审计提出transport code descriptor重新封印的alias风险，已收紧并增加第9项回归；初始8项日志和export保留，不能追认成后续最终源码结果。

首个actual authored导出使用实际Java生成的原PDF和observer、实际CPU PNG以及明确标记的synthetic declared双parser输出，没有神经推理／HTTP。internal217,815bytes；public43,060bytes；cold hydration通过。`authored-export-receipt.json`绑定原件、三模块代码及两个工件SHA。其projection源码后来新增负例与门控，因此旧public只作为该历史代码身份的fixture，不当最新已注册来源。

focused cases检查完整inputs往返、不用gold修文本、UTF16／geometry／typed refs／unknown保留、whole-unit commitments、固定cap与完整成员、各CAS家族冷读篡改、缺失不修复、重新封印的正文／位置／pointer／source／关系／scope／unknown升级拒绝、独立internal／DOCX／scanned形状拒绝及code descriptor canonicalization。测试没有评估OCR／法律答案或H800吞吐。

独立只读审计的首轮probe拒绝修改code descriptor `identityScope`后重新封印；收据 `.local/state/oer-native-pdf-independent-20261010/run-043238-7a15b0/read-only-transport-audit-final/probe-receipt.json`，SHA `997740067128742a35880e57be121164f4b06f7c65a69f49f805884de8ec2fc7`。对应代码稳定性也在收据内，尚未独立验真实GCC public采用。

## 仍需完成

后续冻结与必要接缝（2026-10-10）：root原生PDF单次55 tests通过，分母为27 observation＋19 joint projection＋9 public transport，0 failure/error/skip；receipt `.local/state/native-pdf-transport-20261010/run-045921-fa6df2/final-050716-05da8f/validation-receipt.json` SHA `440ab965307386763be2646b223a1085a6a3582f273bdf22e277492acf5c0ea8`。此快照仍未绑定统一检索source-unit必需的`nativeTableMetadataVersion`；新fake-HTTP在retrieve时503暴露这个实际接缝，不能追认为55项已验证检索成功。

随后producer仅增加明确的`nativeTableMetadataVersion=native-pdf-no-table-adoption-v1`，不在runtime偷偷补字段，也不宣称支持表格。joint代码新SHA `bf75da59df2c696cd56b7628b104d89ea12f57c783bfd5e9a4cc06f4de991416`，transport仍 `60c969f2e11b490b7618f973c592c584a62b971046298daa403910c048c05d23`。fresh typed public为43,123bytes、SHA `18b927d8c32d2ba8bca38b47be00ddf8b3b4fe2b8def59000251e46febbeacd6`，internal217,878bytes，保存在同root的`source-identity-compat-051410-9dcbec/`，cold hydration通过。作者新的19项／139项相关回归及9项transport复验通过；19项包含于139项，9项transport另行复验，均不与历史55相加当新的质量分母。新registry/server的完整final receipt待独立记录。

独立PDF registry／server已经实现上述精确dispatch、防降级、exact parents／source order、immutable locator及逐读冷验；最终作者14项registry／fakeHTTP和31项旧消费者回归通过。收据见 `.local/state/native-pdf-projection-20261010/run-044305-c59746/schema-identity-v2-051305/validation-receipt.json`，SHA `0c71311a0fc9ecc017cce127a12b368f75a07c46d6a2e6ae2408bca55f4a1e34`。root在冻结源码独立复验23/23（9transport＋4registry＋10fakeHTTP），收据 `.local/state/native-pdf-transport-20261010/run-045921-fa6df2/root-consumer-check-051829-072468/validation-receipt.json` SHA `b40294232e7367b0581bb63e4fced96fc69b686d3b4953074a4d5d83a7839eeb`。详见 [registry验证](./oer-native-pdf-registry-http-validation-20261010.md)。fake providers／TestClient不等于真实BGE或运行TCP服务。

[Java独立声明contract](./oer-native-pdf-java-declaration-contract-20261010.md)已25/25通过，收据SHA `17f18101b83efb269302ce33f1effd8bc49870b239097ec5becf71f0b8764f33`；正式CanonicalEvidenceService／OerBusinessEvidence接线继续单独验证，typed ref声明校验不等同Python实际CAS冷验。尚未登记真实GCC65–70业务来源或接在线业务。首版这里没有published image asset、AP／native table采用、自动上传identity路由或图生成能力。

官方八题实际生成0、正式多来源Vetting评分0、权重训练0保持；后续真实源覆盖及实际API／前端验收通过后，按 [官方验收计划](./oer-official-business-acceptance-plan-20261010.md)执行。原件、旧错误／成功结果和运行服务保持。
