const assert = require('node:assert/strict')
const path = require('node:path')
const vue = require('vue')
const { renderToString } = require('vue/server-renderer')
const { loadVue } = require('./helpers/load-vue.cjs')

const decisionView = loadVue(path.join(__dirname, '../src/components/DraftingExtractionDecision.vue')).default
const inputView = loadVue(path.join(__dirname, '../src/components/DraftingInputField.vue')).default
const reportView = loadVue(path.join(__dirname, '../src/components/DraftingExtractionReport.vue'), { boundaries: { '@/api': { draftingApi: {} } } }).default
const cases = [
  ['bill_type_unsupported', ['该 Bill 的计价类型缺乏本行原文依据', '該 Bill 的計價類型缺乏本列原文依據', 'The Bill pricing type is not supported by its source row']],
  ['input_not_applicable', ['按照当前已知条件，此输入不适用，模型建议未被接收', '按照目前已知條件，此輸入不適用，模型建議未被接收', 'This input is not applicable under the current known conditions; the model suggestion was not accepted']],
  ['input_applicability_conflict', ['前置输入的候选值有分歧，无法确定此项是否适用，模型建议暂不接收', '前置輸入的候選值有分歧，無法確定此項是否適用，模型建議暫不接收', 'Prerequisite candidates disagree, so applicability could not be established and this suggestion was not accepted']],
  ['source_list_ambiguous', ['多个原文列表与建议匹配，无法确定唯一的完整来源列表', '多個原文列表與建議相符，無法確定唯一的完整來源列表', 'Several source lists match this suggestion; a unique complete source list could not be established']],
  ['source_list_invalid_sequence', ['原文列表存在缺号、重复编号或空项目，不能据此接收完整清单', '原文列表存在缺號、重複編號或空項目，不能據此接收完整清單', 'The source list has missing or repeated numbers, or empty items; it cannot establish a complete list']],
  ['source_list_incomplete', ['候选清单未保留原文列表的全部项目及编号', '候選清單未保留原文列表的全部項目及編號', 'The suggested list does not retain every original item and number']],
  ['source_list_value_mismatch', ['候选清单的项目正文或续文与原文不一致', '候選清單的項目正文或續文與原文不一致', 'An item body or continuation in the suggested list differs from the original source']],
  ['source_list_unresolved', ['列表的适用范围含待定或否定条件，当前清单建议未通过校验', '列表的適用範圍含待定或否定條件，目前清單建議未通過校驗', 'The list has pending or negative scope conditions; this suggestion did not pass validation']],
  ['evidence_quote_reanchored', ['引文已重新定位到同一份资料的完整原文范围', '引文已重新定位到同一份資料的完整原文範圍', 'The quotation was anchored to the complete original passage in the same source']],
  ['source_value_typography_restored', ['已按原文还原列表编号样式，模型原始值仍可查阅', '已按原文還原列表編號樣式，模型原始值仍可查閱', 'List numbering was restored from the original source; the raw model value remains available']]
]
const locales = ['zh-Hans', 'zh-Hant', 'en']
const base = {
  partId: 'native-source:0', attemptIndex: 1, itemIndex: 0, key: 'otherWaterproofingSpecificationAreas',
  rawValue: '1 Kitchen; 2 Bathroom;', normalizedValue: '1. Kitchen;\n2. Bathroom;',
  sourceQuote: 'Other waterproofing areas\n1. Kitchen;\n2. Bathroom;',
  reason: 'Original model explanation', confidence: 0.95, status: 'rejected', codes: []
}
const trace = {
  model: 'test-only-frozen-answer', status: 'completed', rawResponses: ['unchanged raw response'],
  parts: [{ partId: base.partId, fileName: 'native-source.docx', sourceDocumentId: 1, sourceHash: 'original-hash', partIndex: 0, sourceText: base.sourceQuote, attempts: [] }],
  decisions: [], fields: []
}
const field = { key: base.key, kind: 'text', label: { zhHans: '其他防水区域', zhHant: '其他防水區域', en: 'Other waterproofing areas' } }

;(async () => {
  for (const [index, locale] of locales.entries()) {
    for (const [code, labels] of cases) {
      const status = ['evidence_quote_reanchored', 'source_value_typography_restored'].includes(code) ? 'accepted' : 'rejected'
      const decision = { ...base, status, codes: [code] }
      const before = JSON.stringify(decision)
      const rendered = await renderToString(vue.createSSRApp(decisionView, { decision, trace, locale }))
      assert.ok(rendered.includes(labels[index]), `${locale}: ${code} must explain the actual diagnostic`)
      assert.ok(rendered.includes(code), `${locale}: original code must remain visible`)
      assert.match(rendered, new RegExp(`data-decision-status="${status}"`))
      assert.match(rendered, /1 Kitchen; 2 Bathroom;/)
      assert.match(rendered, /1\. Kitchen;\n2\. Bathroom;/)
      assert.match(rendered, /native-source\.docx/)
      assert.match(rendered, /Original model explanation/)
      assert.doesNotMatch(rendered, /Other diagnostic reason|其他诊断原因|其他診斷原因|Adopt this candidate|采用此候选值|採用此候選值/)
      assert.equal(JSON.stringify(decision), before, 'Rendering may not rewrite raw values, quotes, codes or statuses')
    }
    for (const code of ['future_reason', 'toString', 'constructor', '__proto__']) {
      const rendered = await renderToString(vue.createSSRApp(decisionView, { decision: { ...base, codes: [code] }, trace, locale }))
      assert.ok(rendered.includes(['其他诊断原因', '其他診斷原因', 'Other diagnostic reason'][index]), 'Unknown legacy diagnostic codes retain a readable fallback')
      assert.ok(rendered.includes(code), 'Unknown diagnostic code remains inspectable')
      assert.match(rendered, /data-decision-status="rejected"/)
    }
    const recovered = { ...base, status: 'accepted', codes: ['evidence_quote_reanchored', 'source_value_typography_restored'] }
    const conflicted = { ...trace, decisions: [recovered], fields: [{ key: field.key, status: 'candidate_conflict', candidateCount: 2, rejectionCount: 0, unansweredCount: 0, decisionRefs: [0] }] }
    const controls = await renderToString(vue.createSSRApp(inputView, { field, value: 'Preserved human input', values: {}, locale, trace: conflicted }))
    assert.ok(controls.includes(['候选取值不一致', '候選取值不一致', 'Candidate values disagree'][index]), 'A neutral recovery diagnostic does not resolve a review conflict')
    assert.match(controls, /Preserved human input/)
    const rejected = { ...recovered, status: 'rejected', codes: [...recovered.codes, 'source_list_incomplete'] }
    const report = await renderToString(vue.createSSRApp(reportView, { trace: { ...trace, decisions: [rejected] }, projectId: 'test', locale, open: false }))
    assert.match(report, /data-decision-status="rejected"/)
    assert.ok(report.includes(cases.find(item => item[0] === 'source_list_incomplete')[1][index]))
    assert.doesNotMatch(report, /Adopt this candidate|采用此候选值|採用此候選值/)
  }
  console.log('PASS: 10 evidence/applicability diagnostics have specific explanations in all three languages; raw and normalized values, exact codes, quotations and statuses stay visible')
  console.log('PASS: neutral recovery descriptions preserve accepted status, unresolved review conflicts and independent rejection reasons; manual values stay editable')
})().catch(error => { console.error(error); process.exitCode = 1 })
