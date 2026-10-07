/* Mounted read-only evidence diagnostics; synthetic transport records do not certify model quality. */
const assert = require('node:assert/strict')
const path = require('node:path')
const { loadVue } = require('./helpers/load-vue.cjs')
const { text, find, settle, renderer } = require('./helpers/render-controls.cjs')
const component = loadVue(path.join(__dirname, '../src/components/DraftingExtractionDecision.vue')).default
const quote = 'No additional submissions can be established from the available facts. <pending>'
const trace = { parts: [{ partId: 'SYNTHETIC-part', fileName: 'SYNTHETIC-meeting.docx' }] }
const base = { partId: 'SYNTHETIC-part', attemptIndex: 1, itemIndex: 0, key: 'additionalSubmissions', rawValue: [], normalizedValue: null, sourceQuote: quote, confidence: 0.9, status: 'rejected', codes: ['unsupported_empty_list'] }
;(async () => {
  for (const [locale, explanation, noneLabel] of [
    ['en', 'The quotation and its original context do not establish that there are no applicable items', 'Empty-list suggestion: no applicable items'],
    ['zh-Hans', '引文及原始上下文不能证明没有适用项目', '空清单建议：没有适用项目'],
    ['zh-Hant', '引文及原始上下文不能證明沒有適用項目', '空清單建議：沒有適用項目']
  ]) {
    const rejected = renderer(), rejectedApp = rejected.createApp(component, { decision: base, trace, locale })
    rejectedApp.mount(rejected.root); await settle()
    assert.ok(text(rejected.root).includes(explanation), `${locale}: rejected empty-list explanation`)
    assert.ok(text(rejected.root).includes('unsupported_empty_list'))
    assert.ok(text(rejected.root).includes(quote), 'Original quotation is retained verbatim as text')
    assert.ok(text(rejected.root).includes('SYNTHETIC-meeting.docx'))
    assert.ok(find(rejected.root, node => node.tag === 'pre' && text(node) === '[]'), 'Raw model empty list remains inspectable')
    assert.ok(!text(rejected.root).includes(noneLabel), 'Rejected emptiness is never labelled as an accepted empty-list suggestion')
    rejectedApp.unmount()
    const accepted = renderer(), acceptedApp = accepted.createApp(component, { decision: { ...base, status: 'accepted', normalizedValue: '[]', codes: [], sourceQuote: 'No additional submissions are required for this project.' }, trace, locale })
    acceptedApp.mount(accepted.root); await settle()
    assert.ok(text(accepted.root).includes(noneLabel), `${locale}: accepted empty-list candidate meaning is explained without certifying its business accuracy`)
    assert.ok(!text(accepted.root).includes(explanation))
    assert.equal(find(accepted.root, node => node.tag === 'button'), undefined, 'Evidence diagnostics never adopt or overwrite values')
    acceptedApp.unmount()
    const unknown = renderer(), unknownApp = unknown.createApp(component, { decision: { ...base, status: 'unanswered', rawValue: null, codes: ['value_unanswered'] }, trace, locale })
    unknownApp.mount(unknown.root); await settle()
    assert.ok(!text(unknown.root).includes(noneLabel), 'Unknown is distinct from an empty-list suggestion')
    assert.ok(find(unknown.root, node => node.tag === 'pre' && text(node) === 'null'))
    unknownApp.unmount()
  }
  console.log('PASS: mounted three-language diagnostics distinguish rejected empty evidence, accepted empty-list suggestions and unknown, preserving raw values/quotes without adoption controls or business certification')
})().catch(error => { console.error(error); process.exitCode = 1 })
