# Drafting baseline：21 项 REVIEW 的人工证据核对

本报告只审查真实 `baseline-32k-off` 运行被自动评分器标为 REVIEW 的 21 项。已逐条读取候选 sourceQuote、实际上传 DOCX 的原生段及参考 inputEvidence，没有修改变量、候选、评分器、API 或提取 harness。

自动结果保持 **55 PASS / 21 REVIEW / 3 FAIL**；61 项有确定值的输入中，正确且有出处的候选检出仍为 **58/61**。人工核对不增加模型检出数，不把 REVIEW 改写成自动 PASS。

21 项中，20 项的实际引用支持对应答案；1 项 `designResponsibilities` 的引用只给矩阵导言，单独不足以核验三个 scope。其完整源矩阵和模型实际输入 part 均支持三个正确记录。没有发现本批引用存在 wrong_role；`contractPeriodMonths=31` 来自 SIM14 的明确测试起草决定，没有借用 SIM03 的 base/ceiling 月数。

项目 `drafting-exam-20261008-095654-9ecc884f`；提取运行 `e93fc9a3-cebc-4557-b2a9-8c7d777bde8f`；自动评分文件 SHA-256 `e90df1f3642630f8751b1423820c262a52a5643654c3b1b60509446fa7669c63`。

原生段号按 `word/document.xml` 中正文及表格单元格的全部段落顺序计数。含 ` | ` 的表格引用按连续单元格段逐一核对；这些符号是 DOCX parser 的表格渲染，不是伪造原文。

| 变量 | 引用判定 | 完整源上下文 | 原生段定位 |
|---|---|---|---|
| `foundationIncluded` | supported | supported | 14: 7 |
| `periodAtLeast39Months` | supported | supported | 03: 9; 08: 19/20/21; 14: 10 |
| `contractPeriodMonths` | supported | supported | 14: 10 |
| `subcontractArrangement` | supported | supported | 05: 7; 08: 28/29/30 |
| `twoEnvelopeTendering` | supported | supported | 06: 6; 08: 34/35/36 |
| `electronicTendering` | supported | supported | 06: 7; 08: 37/38/39 |
| `worksSubjectToExcision` | supported | supported | 09: 8 |
| `volumetricPrecastComponents` | supported | supported | 09: 14 |
| `precastFacadeTenderBasis` | supported | supported | 09: 17 |
| `precastFacadePermission` | supported | supported | 09: 20 |
| `contractorDesignedFoundations` | supported | supported | 14: 25 |
| `designResponsibilities` | insufficient | supported | 14: 28 |
| `wtoGpaApplies` | supported | supported | 09: 23 |
| `railwayProtectionAreaWorks` | supported | supported | 14: 41 |
| `oldValuableTrees` | supported | supported | 14: 44 |
| `allBqQuantitiesProvisional` | supported | supported | 14: 47 |
| `rseSubmissionsRequired` | supported | supported | 09: 26 |
| `additionalSubmissions` | supported | supported | 14: 53 |
| `refugeFloorInstallationIncluded` | supported | supported | 14: 50 |
| `buildingDemolitionSitesSeparated` | supported | supported | 14: 62 |
| `policeTrainingAdopted` | supported | supported | 14: 65 |

## foundationIncluded

候选引用明确说本测试场景不是包含基础工程的合并合约，直接支持 false。它是 SIM14 场景决定，不应宣称由原始 2b 工程范围推出。

实际引用判定：**supported**；完整源上下文：**supported**；自动结果仍为 **REVIEW**。

候选业务值：

```json
false
```

候选 0：[14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx](<D:/R&D/ConSense/Ref/simulated_2b_correspondence/03_missing_evidence_test/01_upload_documents/14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx>)；原生段 7；定位类型 `contiguous_native_paragraph`。

> For this test scenario only, classify the tender as not a combined contract with foundation works.

DOCX 字节 SHA-256：`ce83d01d4341aea810e900069b05be85edac46099dae5e9d770c9837976642b9`。应用解析来源 hash：`9a2cb19425a30f7c334929be4a22faae5a6ef4fa7ad6872083bfe0b85cdc344b`；两者身份范围不同。

用于角色或范围核验的原始上下文：

- 原生段 7：For this test scenario only, classify the tender as not a combined contract with foundation works. This is the simulated QS adoption resolving SIM02, not a conclusion extracted from the original 2b scope.

参考锚点 `SIM14` / `14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx` / 原生段 7：

> For this test scenario only, classify the tender as not a combined contract with foundation works. This is the simulated QS adoption resolving SIM02, not a conclusion extracted from the original 2b scope.

## periodAtLeast39Months

SIM03 引用直接写 threshold >=39 months = false；SIM08 表格同样明确答 No；SIM14 的 31 个月场景采用值可计算出 31 < 39。三条候选均支持本测试阈值，不应由其倒推现实中的 accepted contract duration。

实际引用判定：**supported**；完整源上下文：**supported**；自动结果仍为 **REVIEW**。

候选业务值：

```json
false
```

候选 0：[03_2024-12-11_email_programme_threshold_confirmed_from_appendix_g.docx](<D:/R&D/ConSense/Ref/simulated_2b_correspondence/03_missing_evidence_test/01_upload_documents/03_2024-12-11_email_programme_threshold_confirmed_from_appendix_g.docx>)；原生段 9；定位类型 `contiguous_native_paragraph`。

> threshold >=39 months = false

DOCX 字节 SHA-256：`f3f6cef97e753e3cca4ff1b4eabfbd0fd5cf269097987bf7e44f5b8a1c0ea978`。应用解析来源 hash：`384a01a732c2c6910c0a25cbe01c022e938383708e9211248bc4c63e79174cc6`；两者身份范围不同。

用于角色或范围核验的原始上下文：

- 原生段 9：The Appendix expressly states that the accepted proposed period in Column (B) forms part of the Contract and that Column (A) does not. Record base time = 31 months, threshold >=39 months = false, and exact accepted contract period = not supplied.

候选 1：[08_2024-12-18_minutes_consolidated_drafting_input_review_meeting.docx](<D:/R&D/ConSense/Ref/simulated_2b_correspondence/03_missing_evidence_test/01_upload_documents/08_2024-12-18_minutes_consolidated_drafting_input_review_meeting.docx>)；原生段 19、20、21；定位类型 `consecutive_native_table_cell_paragraphs`。

> Contract period of 39 months or more | No; proposed-period ceiling is 31 months | SIM03

DOCX 字节 SHA-256：`b5bd58cb5998195ece9d1529a6d5edd2b604731ab5a3591134d6c2cb316de8b6`。应用解析来源 hash：`c646bb1f9465ec5c1b84d66ebd0b60b8c7e7e5ef08737cd063f8cd42d93ea9e2`；两者身份范围不同。

用于角色或范围核验的原始上下文：

- 原生段 19：Contract period of 39 months or more
- 原生段 20：No; proposed-period ceiling is 31 months
- 原生段 21：SIM03

候选 2：[14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx](<D:/R&D/ConSense/Ref/simulated_2b_correspondence/03_missing_evidence_test/01_upload_documents/14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx>)；原生段 10；定位类型 `contiguous_native_paragraph`。

> For this test draft only, use a period of 31 months.

DOCX 字节 SHA-256：`ce83d01d4341aea810e900069b05be85edac46099dae5e9d770c9837976642b9`。应用解析来源 hash：`9a2cb19425a30f7c334929be4a22faae5a6ef4fa7ad6872083bfe0b85cdc344b`；两者身份范围不同。

用于角色或范围核验的原始上下文：

- 原生段 10：For this test draft only, use a period of 31 months. It is a newly adopted simulated drafting assumption compatible with the source tender ceiling, not evidence of an awarded or accepted contract period. Derive the inclusive 39-month test from this one value.

参考锚点 `SIM03` / `03_2024-12-11_email_programme_threshold_confirmed_from_appendix_g.docx` / 原生段 7：

> Note 3(a) requires the proposed time in Column (B) not to exceed the 31-month base time. For this tender drafting exercise, the answer to “Is the contract period 39 months or more?” is therefore No, based on the permitted proposed-period ceiling. This is not a confirmation that an awarded contract has a period of exactly 31 months.

## contractPeriodMonths

唯一候选来自 SIM14 原生段 10：For this test draft only, use a period of 31 months. 这是对本次测试 draft 的明确采用指示，来源角色正确。没有从 SIM03 的 Column A base time 或 proposed-period ceiling 借数；同段后文明确它不是现实 awarded/accepted contract 的证据。

实际引用判定：**supported**；完整源上下文：**supported**；自动结果仍为 **REVIEW**。

候选业务值：

```json
31
```

候选 0：[14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx](<D:/R&D/ConSense/Ref/simulated_2b_correspondence/03_missing_evidence_test/01_upload_documents/14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx>)；原生段 10；定位类型 `contiguous_native_paragraph`。

> For this test draft only, use a period of 31 months.

DOCX 字节 SHA-256：`ce83d01d4341aea810e900069b05be85edac46099dae5e9d770c9837976642b9`。应用解析来源 hash：`9a2cb19425a30f7c334929be4a22faae5a6ef4fa7ad6872083bfe0b85cdc344b`；两者身份范围不同。

用于角色或范围核验的原始上下文：

- 原生段 10：For this test draft only, use a period of 31 months. It is a newly adopted simulated drafting assumption compatible with the source tender ceiling, not evidence of an awarded or accepted contract period. Derive the inclusive 39-month test from this one value.

参考锚点 `SIM14` / `14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx` / 原生段 10：

> For this test draft only, use a period of 31 months. It is a newly adopted simulated drafting assumption compatible with the source tender ceiling, not evidence of an awarded or accepted contract period. Derive the inclusive 39-month test from this one value.

## subcontractArrangement

SIM05 引用明确要求在应用变量列表选择 BSSSC；SIM08 表格也将 Building services arrangement 配对 BSSSC。BSSSC 是应用映射标签，不能据此声称原 2b 使用这个缩写。

实际引用判定：**supported**；完整源上下文：**supported**；自动结果仍为 **REVIEW**。

候选业务值：

```json
"BSSSC"
```

候选 0：[05_2024-12-13_minutes_building_services_subcontract_coordination_meeting.docx](<D:/R&D/ConSense/Ref/simulated_2b_correspondence/03_missing_evidence_test/01_upload_documents/05_2024-12-13_minutes_building_services_subcontract_coordination_meeting.docx>)；原生段 7；定位类型 `contiguous_native_paragraph`。

> Decision: select Building Services Specialist Sub-Contracts (BSSSC) in the user’s variable list.

DOCX 字节 SHA-256：`5371128c42a1629916cf86d9d95e326c6b54e04d57129aaaebd5a35d8d6bcbc1`。应用解析来源 hash：`ad59b66b20513465dde32f2e30669fd7a40df81e093bbd974f2ba628578d9543`；两者身份范围不同。

用于角色或范围核验的原始上下文：

- 原生段 7：Decision: select Building Services Specialist Sub-Contracts (BSSSC) in the user’s variable list. This is the application label mapped from the source wording “BS Specialist Sub-contractors”; it is not a claim that the source uses the acronym BSSSC.

候选 1：[08_2024-12-18_minutes_consolidated_drafting_input_review_meeting.docx](<D:/R&D/ConSense/Ref/simulated_2b_correspondence/03_missing_evidence_test/01_upload_documents/08_2024-12-18_minutes_consolidated_drafting_input_review_meeting.docx>)；原生段 28、29、30；定位类型 `consecutive_native_table_cell_paragraphs`。

> Building services arrangement | BSSSC | SIM05

DOCX 字节 SHA-256：`b5bd58cb5998195ece9d1529a6d5edd2b604731ab5a3591134d6c2cb316de8b6`。应用解析来源 hash：`c646bb1f9465ec5c1b84d66ebd0b60b8c7e7e5ef08737cd063f8cd42d93ea9e2`；两者身份范围不同。

用于角色或范围核验的原始上下文：

- 原生段 28：Building services arrangement
- 原生段 29：BSSSC
- 原生段 30：SIM05

参考锚点 `SIM05` / `05_2024-12-13_minutes_building_services_subcontract_coordination_meeting.docx` / 原生段 7：

> Decision: select Building Services Specialist Sub-Contracts (BSSSC) in the user’s variable list. This is the application label mapped from the source wording “BS Specialist Sub-contractors”; it is not a claim that the source uses the acronym BSSSC.

## twoEnvelopeTendering

SIM06 引用是明确 Record ... = Yes，SIM08 表格为同一问题配对 Yes。它回答双信封制度，不把 Envelope 1a/1b/1c 当成额外顶层信封。

实际引用判定：**supported**；完整源上下文：**supported**；自动结果仍为 **REVIEW**。

候选业务值：

```json
true
```

候选 0：[06_2024-12-16_email_two_envelope_tender_and_l10pro_return_requirements.docx](<D:/R&D/ConSense/Ref/simulated_2b_correspondence/03_missing_evidence_test/01_upload_documents/06_2024-12-16_email_two_envelope_tender_and_l10pro_return_requirements.docx>)；原生段 6；定位类型 `contiguous_native_paragraph`。

> Record Two-envelope Tendering System = Yes.

DOCX 字节 SHA-256：`9d85ba819544bc27c0ca89eae04c4888dac211aa7f00069902e9d37f9b79507d`。应用解析来源 hash：`71088c01d4e19bbfa8aa5fb4bb5a4a17575ec4536ba30ae662a2e223412bd289`；两者身份范围不同。

用于角色或范围核验的原始上下文：

- 原生段 6：SCT5(2) requires two envelopes. Envelope 1 contains technical proposals, conviction and serious accident records, and other submissions; Envelope 2 contains tender price documents. Record Two-envelope Tendering System = Yes. Envelope 1a, 1b and 1c are subdivisions of Envelope 1 and do not change this into a three-envelope system.

候选 1：[08_2024-12-18_minutes_consolidated_drafting_input_review_meeting.docx](<D:/R&D/ConSense/Ref/simulated_2b_correspondence/03_missing_evidence_test/01_upload_documents/08_2024-12-18_minutes_consolidated_drafting_input_review_meeting.docx>)；原生段 34、35、36；定位类型 `consecutive_native_table_cell_paragraphs`。

> Two-envelope Tendering System | Yes | SIM06

DOCX 字节 SHA-256：`b5bd58cb5998195ece9d1529a6d5edd2b604731ab5a3591134d6c2cb316de8b6`。应用解析来源 hash：`c646bb1f9465ec5c1b84d66ebd0b60b8c7e7e5ef08737cd063f8cd42d93ea9e2`；两者身份范围不同。

用于角色或范围核验的原始上下文：

- 原生段 34：Two-envelope Tendering System
- 原生段 35：Yes
- 原生段 36：SIM06

参考锚点 `SIM06` / `06_2024-12-16_email_two_envelope_tender_and_l10pro_return_requirements.docx` / 原生段 6：

> SCT5(2) requires two envelopes. Envelope 1 contains technical proposals, conviction and serious accident records, and other submissions; Envelope 2 contains tender price documents. Record Two-envelope Tendering System = Yes. Envelope 1a, 1b and 1c are subdivisions of Envelope 1 and do not change this into a three-envelope system.

## electronicTendering

SIM06 引用明确选择 L10Pro preparation/pricing workflow，SIM08 表格配对 L10Pro。支持本字段，但没有取消纸本和 DVD-ROM return requirements。参考 inputEvidence 指到文档标题段 1；实际候选段 7 比该参考标题更直接地支持答案。

实际引用判定：**supported**；完整源上下文：**supported**；自动结果仍为 **REVIEW**。

候选业务值：

```json
"L10Pro"
```

候选 0：[06_2024-12-16_email_two_envelope_tender_and_l10pro_return_requirements.docx](<D:/R&D/ConSense/Ref/simulated_2b_correspondence/03_missing_evidence_test/01_upload_documents/06_2024-12-16_email_two_envelope_tender_and_l10pro_return_requirements.docx>)；原生段 7；定位类型 `contiguous_native_paragraph`。

> For the user’s choice “Electronic tendering using L10Pro or hardcopy tendering”, select the L10Pro option for the preparation and pricing workflow.

DOCX 字节 SHA-256：`9d85ba819544bc27c0ca89eae04c4888dac211aa7f00069902e9d37f9b79507d`。应用解析来源 hash：`71088c01d4e19bbfa8aa5fb4bb5a4a17575ec4536ba30ae662a2e223412bd289`；两者身份范围不同。

用于角色或范围核验的原始上下文：

- 原生段 7：SCT3(1)(a) requires tenderers to install and use L10Pro to price the relevant documents. For the user’s choice “Electronic tendering using L10Pro or hardcopy tendering”, select the L10Pro option for the preparation and pricing workflow.

候选 1：[08_2024-12-18_minutes_consolidated_drafting_input_review_meeting.docx](<D:/R&D/ConSense/Ref/simulated_2b_correspondence/03_missing_evidence_test/01_upload_documents/08_2024-12-18_minutes_consolidated_drafting_input_review_meeting.docx>)；原生段 37、38、39；定位类型 `consecutive_native_table_cell_paragraphs`。

> Tendering preparation option | L10Pro | SIM06

DOCX 字节 SHA-256：`b5bd58cb5998195ece9d1529a6d5edd2b604731ab5a3591134d6c2cb316de8b6`。应用解析来源 hash：`c646bb1f9465ec5c1b84d66ebd0b60b8c7e7e5ef08737cd063f8cd42d93ea9e2`；两者身份范围不同。

用于角色或范围核验的原始上下文：

- 原生段 37：Tendering preparation option
- 原生段 38：L10Pro
- 原生段 39：SIM06

参考锚点 `SIM06` / `06_2024-12-16_email_two_envelope_tender_and_l10pro_return_requirements.docx` / 原生段 1：

> Two envelope tender and L10Pro return requirements

## worksSubjectToExcision

SIM09 引用明确 no parts of the Works are subject to excision，直接支持 false；同文件原生段 7 的 No 回答是独立相邻核验。

实际引用判定：**supported**；完整源上下文：**supported**；自动结果仍为 **REVIEW**。

候选业务值：

```json
false
```

候选 0：[09_2024-12-19_email_works_scope_and_precast_construction_reply.docx](<D:/R&D/ConSense/Ref/simulated_2b_correspondence/03_missing_evidence_test/01_upload_documents/09_2024-12-19_email_works_scope_and_precast_construction_reply.docx>)；原生段 8；定位类型 `contiguous_native_paragraph`。

> The project Specification expressly states no parts of the Works are subject to excision.

DOCX 字节 SHA-256：`cfd7fa219489872cc90483ada5e5588e7facaa5308e8da6ae3df26cfd121b51d`。应用解析来源 hash：`7397e1da67721479d5b791e51dc0cd2237be261ac0fe6eb3a6de2e48aa7cd3ec`；两者身份范围不同。

用于角色或范围核验的原始上下文：

- 原生段 7：Works Subject to Excision specified: No
- 原生段 8：The project Specification expressly states no parts of the Works are subject to excision.

参考锚点 `SIM09` / `09_2024-12-19_email_works_scope_and_precast_construction_reply.docx` / 原生段 7：

> Works Subject to Excision specified: No

## volumetricPrecastComponents

SIM09 引用说明项目要求 domestic flats 的 MiC-BK 且该构件列入 CON8 清单；原生段 13 另明确 CON8 ... used: Yes。两者共同支持 true，且不是仅因 facade precasting 推定 CON8。

实际引用判定：**supported**；完整源上下文：**supported**；自动结果仍为 **REVIEW**。

候选业务值：

```json
true
```

候选 0：[09_2024-12-19_email_works_scope_and_precast_construction_reply.docx](<D:/R&D/ConSense/Ref/simulated_2b_correspondence/03_missing_evidence_test/01_upload_documents/09_2024-12-19_email_works_scope_and_precast_construction_reply.docx>)；原生段 14；定位类型 `contiguous_native_paragraph`。

> Case expressly requires MiC-BK for the domestic flats and includes MiC-BK in the component list referring to CON8.

DOCX 字节 SHA-256：`cfd7fa219489872cc90483ada5e5588e7facaa5308e8da6ae3df26cfd121b51d`。应用解析来源 hash：`7397e1da67721479d5b791e51dc0cd2237be261ac0fe6eb3a6de2e48aa7cd3ec`；两者身份范围不同。

用于角色或范围核验的原始上下文：

- 原生段 13：CON8 volumetric precast components used: Yes
- 原生段 14：Case expressly requires MiC-BK for the domestic flats and includes MiC-BK in the component list referring to CON8. Not inferred merely from facade precasting.

参考锚点 `SIM09` / `09_2024-12-19_email_works_scope_and_precast_construction_reply.docx` / 原生段 13：

> CON8 volumetric precast components used: Yes

## precastFacadeTenderBasis

SIM09 引用明确 tender basis requires precast concrete facades for domestic flat units，支持 true，并保留 Specification/Drawings 的范围限定。

实际引用判定：**supported**；完整源上下文：**supported**；自动结果仍为 **REVIEW**。

候选业务值：

```json
true
```

候选 0：[09_2024-12-19_email_works_scope_and_precast_construction_reply.docx](<D:/R&D/ConSense/Ref/simulated_2b_correspondence/03_missing_evidence_test/01_upload_documents/09_2024-12-19_email_works_scope_and_precast_construction_reply.docx>)；原生段 17；定位类型 `contiguous_native_paragraph`。

> NTT tender basis expressly requires precast concrete facades for domestic flat units, qualified by the Specification and Drawings.

DOCX 字节 SHA-256：`cfd7fa219489872cc90483ada5e5588e7facaa5308e8da6ae3df26cfd121b51d`。应用解析来源 hash：`7397e1da67721479d5b791e51dc0cd2237be261ac0fe6eb3a6de2e48aa7cd3ec`；两者身份范围不同。

用于角色或范围核验的原始上下文：

- 原生段 17：NTT tender basis expressly requires precast concrete facades for domestic flat units, qualified by the Specification and Drawings.

参考锚点 `SIM09` / `09_2024-12-19_email_works_scope_and_precast_construction_reply.docx` / 原生段 16：

> Tender based on precast concrete facades: Yes

## precastFacadePermission

SIM09 引用明确 SCT6/SCT10 对 APE2.A410 的 required/proposed facades 及付款条款作安排；相邻原生段 19 明确 Yes。支持该 permission/requirement 字段，与 tender basis 是不同问题。

实际引用判定：**supported**；完整源上下文：**supported**；自动结果仍为 **REVIEW**。

候选业务值：

```json
true
```

候选 0：[09_2024-12-19_email_works_scope_and_precast_construction_reply.docx](<D:/R&D/ConSense/Ref/simulated_2b_correspondence/03_missing_evidence_test/01_upload_documents/09_2024-12-19_email_works_scope_and_precast_construction_reply.docx>)；原生段 20；定位类型 `contiguous_native_paragraph`。

> SCT6 and SCT10 expressly provide required/proposed facades under APE2.A410 and special terms of payment.

DOCX 字节 SHA-256：`cfd7fa219489872cc90483ada5e5588e7facaa5308e8da6ae3df26cfd121b51d`。应用解析来源 hash：`7397e1da67721479d5b791e51dc0cd2237be261ac0fe6eb3a6de2e48aa7cd3ec`；两者身份范围不同。

用于角色或范围核验的原始上下文：

- 原生段 19：Precast facades required or proposed under APE2.A410: Yes
- 原生段 20：SCT6 and SCT10 expressly provide required/proposed facades under APE2.A410 and special terms of payment. This is distinct from the tender basis.

参考锚点 `SIM09` / `09_2024-12-19_email_works_scope_and_precast_construction_reply.docx` / 原生段 19：

> Precast facades required or proposed under APE2.A410: Yes

## contractorDesignedFoundations

SIM14 引用明确 Contractor does not design the foundations，支持 false；这是场景决定，不否定后面 specialist components 的 design responsibilities。

实际引用判定：**supported**；完整源上下文：**supported**；自动结果仍为 **REVIEW**。

候选业务值：

```json
false
```

候选 0：[14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx](<D:/R&D/ConSense/Ref/simulated_2b_correspondence/03_missing_evidence_test/01_upload_documents/14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx>)；原生段 25；定位类型 `contiguous_native_paragraph`。

> For this scenario only, the Contractor does not design the foundations.

DOCX 字节 SHA-256：`ce83d01d4341aea810e900069b05be85edac46099dae5e9d770c9837976642b9`。应用解析来源 hash：`9a2cb19425a30f7c334929be4a22faae5a6ef4fa7ad6872083bfe0b85cdc344b`；两者身份范围不同。

用于角色或范围核验的原始上下文：

- 原生段 25：For this scenario only, the Contractor does not design the foundations. This classification is distinct from the specialist design responsibilities below.

参考锚点 `SIM14` / `14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx` / 原生段 25：

> For this scenario only, the Contractor does not design the foundations. This classification is distinct from the specialist design responsibilities below.

## designResponsibilities

实际候选引用只含 matrix 导言，明确 each listed specialist component requires both design and execution、且不新增 piling/pilecaps/footings；它没有逐一列出三个 scope，单独引用不足以核验完整矩阵。实际上传 DOCX 紧随的原生段 29/30/31 完整列出三个 component=other、design=Yes、execution=Yes 与精确 scope；这些行也确实存在于模型所见 part 17:0。完整源上下文支持所有业务值，但本条引用本身仍记 insufficient，不能通过人工补上下文改为自动 PASS。

实际引用判定：**insufficient**；完整源上下文：**supported**；自动结果仍为 **REVIEW**。

候选业务值：

```json
[
  {
    "component": "other",
    "design": true,
    "execution": true,
    "scope": "Roofing and associated waterproofing systems"
  },
  {
    "component": "other",
    "design": true,
    "execution": true,
    "scope": "Precast concrete facades and CON8 volumetric precast concrete components"
  },
  {
    "component": "other",
    "design": true,
    "execution": true,
    "scope": "Play equipment and Impact Absorbing Surfacing Materials (IASM)"
  }
]
```

候选 0：[14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx](<D:/R&D/ConSense/Ref/simulated_2b_correspondence/03_missing_evidence_test/01_upload_documents/14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx>)；原生段 28；定位类型 `contiguous_native_paragraph`。

> For this scenario only, the following is the complete adopted Contractor design and execution matrix. Each listed specialist component requires both design and execution. No piling, pilecaps or footings component is added.

DOCX 字节 SHA-256：`ce83d01d4341aea810e900069b05be85edac46099dae5e9d770c9837976642b9`。应用解析来源 hash：`9a2cb19425a30f7c334929be4a22faae5a6ef4fa7ad6872083bfe0b85cdc344b`；两者身份范围不同。

用于角色或范围核验的原始上下文：

- 原生段 28：For this scenario only, the following is the complete adopted Contractor design and execution matrix. Each listed specialist component requires both design and execution. No piling, pilecaps or footings component is added. This authored matrix is not claimed to be an exhaustive transcription of the original PRE.
- 原生段 29：component: other; design: Yes; execution: Yes; scope: Roofing and associated waterproofing systems
- 原生段 30：component: other; design: Yes; execution: Yes; scope: Precast concrete facades and CON8 volumetric precast concrete components
- 原生段 31：component: other; design: Yes; execution: Yes; scope: Play equipment and Impact Absorbing Surfacing Materials (IASM)

参考锚点 `SIM14` / `14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx` / 原生段 28：

> For this scenario only, the following is the complete adopted Contractor design and execution matrix. Each listed specialist component requires both design and execution. No piling, pilecaps or footings component is added. This authored matrix is not claimed to be an exhaustive transcription of the original PRE.

原生段 29–31 的完整矩阵确实存在于该次模型输入 `partId=17:0` 的 `sourceText`（长度 2,892 字符）；这解释业务值为何正确，但不能补写原候选的 sourceQuote。

## wtoGpaApplies

SIM09 引用直接说 case NTT and SCT expressly apply WTO GPA，支持 true；不是从价格或金额阈值推断。

实际引用判定：**supported**；完整源上下文：**supported**；自动结果仍为 **REVIEW**。

候选业务值：

```json
true
```

候选 0：[09_2024-12-19_email_works_scope_and_precast_construction_reply.docx](<D:/R&D/ConSense/Ref/simulated_2b_correspondence/03_missing_evidence_test/01_upload_documents/09_2024-12-19_email_works_scope_and_precast_construction_reply.docx>)；原生段 23；定位类型 `contiguous_native_paragraph`。

> Both the case NTT and SCT expressly apply WTO GPA; no monetary-threshold calculation used.

DOCX 字节 SHA-256：`cfd7fa219489872cc90483ada5e5588e7facaa5308e8da6ae3df26cfd121b51d`。应用解析来源 hash：`7397e1da67721479d5b791e51dc0cd2237be261ac0fe6eb3a6de2e48aa7cd3ec`；两者身份范围不同。

用于角色或范围核验的原始上下文：

- 原生段 23：Both the case NTT and SCT expressly apply WTO GPA; no monetary-threshold calculation used.

参考锚点 `SIM09` / `09_2024-12-19_email_works_scope_and_precast_construction_reply.docx` / 原生段 22：

> Contract subject to WTO GPA: Yes

## railwayProtectionAreaWorks

SIM14 引用明确 no part of the Works is within MTRCL Railway Protection Area，支持 false；这是明确场景 site-scope 决定，不是看到 Not used 后推定没有工程。

实际引用判定：**supported**；完整源上下文：**supported**；自动结果仍为 **REVIEW**。

候选业务值：

```json
false
```

候选 0：[14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx](<D:/R&D/ConSense/Ref/simulated_2b_correspondence/03_missing_evidence_test/01_upload_documents/14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx>)；原生段 41；定位类型 `contiguous_native_paragraph`。

> For this scenario only, no part of the Works is within the MTRCL Railway Protection Area.

DOCX 字节 SHA-256：`ce83d01d4341aea810e900069b05be85edac46099dae5e9d770c9837976642b9`。应用解析来源 hash：`9a2cb19425a30f7c334929be4a22faae5a6ef4fa7ad6872083bfe0b85cdc344b`；两者身份范围不同。

用于角色或范围核验的原始上下文：

- 原生段 41：For this scenario only, no part of the Works is within the MTRCL Railway Protection Area. This is an explicitly authored site-scope assumption, not an inference from a Not used clause.

参考锚点 `SIM14` / `14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx` / 原生段 41：

> For this scenario only, no part of the Works is within the MTRCL Railway Protection Area. This is an explicitly authored site-scope assumption, not an inference from a Not used clause.

## oldValuableTrees

SIM14 引用明确 adopted list ... is explicitly empty，支持显式 []。它不是因为没收到树木资料而默认空清单。

实际引用判定：**supported**；完整源上下文：**supported**；自动结果仍为 **REVIEW**。

候选业务值：

```json
[]
```

候选 0：[14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx](<D:/R&D/ConSense/Ref/simulated_2b_correspondence/03_missing_evidence_test/01_upload_documents/14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx>)；原生段 44；定位类型 `contiguous_native_paragraph`。

> the adopted list of Old and Valuable Trees to be preserved is explicitly empty.

DOCX 字节 SHA-256：`ce83d01d4341aea810e900069b05be85edac46099dae5e9d770c9837976642b9`。应用解析来源 hash：`9a2cb19425a30f7c334929be4a22faae5a6ef4fa7ad6872083bfe0b85cdc344b`；两者身份范围不同。

用于角色或范围核验的原始上下文：

- 原生段 44：For this scenario only, the adopted list of Old and Valuable Trees to be preserved is explicitly empty. Do not treat an unknown tree list as this empty list. No registry serial number is invented.

参考锚点 `SIM14` / `14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx` / 原生段 44：

> For this scenario only, the adopted list of Old and Valuable Trees to be preserved is explicitly empty. Do not treat an unknown tree list as this empty list. No registry serial number is invented.

## allBqQuantitiesProvisional

SIM14 引用直接说 not all BQ quantities are provisional，支持 false；没有从 Bill 12/13 的名称倒推出全局问题。

实际引用判定：**supported**；完整源上下文：**supported**；自动结果仍为 **REVIEW**。

候选业务值：

```json
false
```

候选 0：[14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx](<D:/R&D/ConSense/Ref/simulated_2b_correspondence/03_missing_evidence_test/01_upload_documents/14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx>)；原生段 47；定位类型 `contiguous_native_paragraph`。

> For this scenario only, not all BQ quantities are provisional.

DOCX 字节 SHA-256：`ce83d01d4341aea810e900069b05be85edac46099dae5e9d770c9837976642b9`。应用解析来源 hash：`9a2cb19425a30f7c334929be4a22faae5a6ef4fa7ad6872083bfe0b85cdc344b`；两者身份范围不同。

用于角色或范围核验的原始上下文：

- 原生段 47：For this scenario only, not all BQ quantities are provisional. Bill 12 and Bill 13 keep their original descriptions; this global decision is not inferred merely from their names.

参考锚点 `SIM14` / `14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx` / 原生段 47：

> For this scenario only, not all BQ quantities are provisional. Bill 12 and Bill 13 keep their original descriptions; this global decision is not inferred merely from their names.

## rseSubmissionsRequired

SIM09 引用明确所有 specified structural drawings/layouts/details/calculations 须由 RSE prepared/signed，支持 true；该 Yes 保留 specified structural submissions 限定，不能泛化成全部图纸。

实际引用判定：**supported**；完整源上下文：**supported**；自动结果仍为 **REVIEW**。

候选业务值：

```json
true
```

候选 0：[09_2024-12-19_email_works_scope_and_precast_construction_reply.docx](<D:/R&D/ConSense/Ref/simulated_2b_correspondence/03_missing_evidence_test/01_upload_documents/09_2024-12-19_email_works_scope_and_precast_construction_reply.docx>)；原生段 26；定位类型 `contiguous_native_paragraph`。

> All specified structural drawings, layouts, details and calculations must be prepared/signed by RSE as required by the Specification.

DOCX 字节 SHA-256：`cfd7fa219489872cc90483ada5e5588e7facaa5308e8da6ae3df26cfd121b51d`。应用解析来源 hash：`7397e1da67721479d5b791e51dc0cd2237be261ac0fe6eb3a6de2e48aa7cd3ec`；两者身份范围不同。

用于角色或范围核验的原始上下文：

- 原生段 25：Specific structural submissions require preparation/signature by RSE: Yes
- 原生段 26：All specified structural drawings, layouts, details and calculations must be prepared/signed by RSE as required by the Specification. Do not turn this into a submission of all drawings without its scope qualification.

参考锚点 `SIM09` / `09_2024-12-19_email_works_scope_and_precast_construction_reply.docx` / 原生段 25：

> Specific structural submissions require preparation/signature by RSE: Yes

## additionalSubmissions

SIM14 引用明确 additional project-specific general tender submission requirements 列表 explicitly empty，支持显式 []，不删除保留的标准要求或 Appendix H 上下文。

实际引用判定：**supported**；完整源上下文：**supported**；自动结果仍为 **REVIEW**。

候选业务值：

```json
[]
```

候选 0：[14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx](<D:/R&D/ConSense/Ref/simulated_2b_correspondence/03_missing_evidence_test/01_upload_documents/14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx>)；原生段 53；定位类型 `contiguous_native_paragraph`。

> the list of additional project-specific general tender submission requirements is explicitly empty.

DOCX 字节 SHA-256：`ce83d01d4341aea810e900069b05be85edac46099dae5e9d770c9837976642b9`。应用解析来源 hash：`9a2cb19425a30f7c334929be4a22faae5a6ef4fa7ad6872083bfe0b85cdc344b`；两者身份范围不同。

用于角色或范围核验的原始上下文：

- 原生段 53：For this scenario only, the list of additional project-specific general tender submission requirements is explicitly empty. Retained standard requirements and existing case Appendix H context remain separate; no extra obligation is invented by an unanswered list.

参考锚点 `SIM14` / `14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx` / 原生段 53：

> For this scenario only, the list of additional project-specific general tender submission requirements is explicitly empty. Retained standard requirements and existing case Appendix H context remain separate; no extra obligation is invented by an unanswered list.

## refugeFloorInstallationIncluded

SIM14 引用明确 no refuge-floor installation is included，支持 false；其 warranty 子问题应保持不适用而不是独立 No。

实际引用判定：**supported**；完整源上下文：**supported**；自动结果仍为 **REVIEW**。

候选业务值：

```json
false
```

候选 0：[14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx](<D:/R&D/ConSense/Ref/simulated_2b_correspondence/03_missing_evidence_test/01_upload_documents/14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx>)；原生段 50；定位类型 `contiguous_native_paragraph`。

> For this scenario only, no refuge-floor installation is included.

DOCX 字节 SHA-256：`ce83d01d4341aea810e900069b05be85edac46099dae5e9d770c9837976642b9`。应用解析来源 hash：`9a2cb19425a30f7c334929be4a22faae5a6ef4fa7ad6872083bfe0b85cdc344b`；两者身份范围不同。

用于角色或范围核验的原始上下文：

- 原生段 50：For this scenario only, no refuge-floor installation is included. Its dependent warranty question is inactive, not an independently answered No.

参考锚点 `SIM14` / `14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx` / 原生段 50：

> For this scenario only, no refuge-floor installation is included. Its dependent warranty question is inactive, not an independently answered No.

## buildingDemolitionSitesSeparated

SIM14 引用明确 building/demolition sites 不形成 SCC8.303 所需 separate/detached arrangement，支持 false；不新增 demolition-only Section。

实际引用判定：**supported**；完整源上下文：**supported**；自动结果仍为 **REVIEW**。

候选业务值：

```json
false
```

候选 0：[14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx](<D:/R&D/ConSense/Ref/simulated_2b_correspondence/03_missing_evidence_test/01_upload_documents/14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx>)；原生段 62；定位类型 `contiguous_native_paragraph`。

> For this scenario only, the building and demolition sites do not form the separate, detached arrangement needed for SCC8.303.

DOCX 字节 SHA-256：`ce83d01d4341aea810e900069b05be85edac46099dae5e9d770c9837976642b9`。应用解析来源 hash：`9a2cb19425a30f7c334929be4a22faae5a6ef4fa7ad6872083bfe0b85cdc344b`；两者身份范围不同。

用于角色或范围核验的原始上下文：

- 原生段 62：For this scenario only, the building and demolition sites do not form the separate, detached arrangement needed for SCC8.303. No demolition-only Section is invented; SIM13 already states there are no phased-completion Sections.

参考锚点 `SIM14` / `14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx` / 原生段 62：

> For this scenario only, the building and demolition sites do not form the separate, detached arrangement needed for SCC8.303. No demolition-only Section is invented; SIM13 already states there are no phased-completion Sections.

## policeTrainingAdopted

SIM14 引用明确 do not adopt optional police-training provision，直接支持 false；有关 building/suitability 子项保持不适用。

实际引用判定：**supported**；完整源上下文：**supported**；自动结果仍为 **REVIEW**。

候选业务值：

```json
false
```

候选 0：[14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx](<D:/R&D/ConSense/Ref/simulated_2b_correspondence/03_missing_evidence_test/01_upload_documents/14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx>)；原生段 65；定位类型 `contiguous_native_paragraph`。

> For this scenario only, do not adopt the optional police-training provision.

DOCX 字节 SHA-256：`ce83d01d4341aea810e900069b05be85edac46099dae5e9d770c9837976642b9`。应用解析来源 hash：`9a2cb19425a30f7c334929be4a22faae5a6ef4fa7ad6872083bfe0b85cdc344b`；两者身份范围不同。

用于角色或范围核验的原始上下文：

- 原生段 65：For this scenario only, do not adopt the optional police-training provision. The associated demolition-building and first-month suitability details are inactive.

参考锚点 `SIM14` / `14_2024-12-30_minutes_scenario_only_supplementary_drafting_decisions.docx` / 原生段 65：

> For this scenario only, do not adopt the optional police-training provision. The associated demolition-building and first-month suitability details are inactive.

## 本次人工核对的边界

这份报告仅解释 21 项自动 REVIEW 的证据。原 baseline 的三个 FAIL、58/61 检出数、四项刻意缺失的留空验证及人工采用状态保持原评分结果；没有将人工阅读作为模型输出。

它不证明最终合同生成、Bill 9/10/11 细项 SOR 发放安排、安全子项 12.2/12.3/12.4 的结构表示、还款条款保留或视察条款去重已经验收。
