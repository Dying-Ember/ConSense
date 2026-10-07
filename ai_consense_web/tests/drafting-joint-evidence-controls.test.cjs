/* Mounted additive diagnostics; synthetic transport records do not certify source semantics. */
const assert = require('node:assert/strict')
const path = require('node:path')
const { loadVue } = require('./helpers/load-vue.cjs')
const { text, find, settle, renderer } = require('./helpers/render-controls.cjs')
const vue = require('vue')
const field = loadVue(path.join(__dirname, '../src/components/DraftingFieldDiagnostics.vue')).default
const quote = 'For Zone A tender, this adds the revised requirement. <source text>'
const trace = { model: 'SYNTHETIC', finishedAt: '', systemPrompt: '', userPrompt: '', rawResponses: [], fields: [{ key: 'additionalSubmissions', status: 'candidate_conflict', candidateCount: 2, rejectionCount: 0, unansweredCount: 0, decisionRefs: [] }], relations: [{ key: 'additionalSubmissions', status: 'proposed', relation: 'supplement', fromDecisionRef: 5, toDecisionRef: 9, decisionRefs: [5, 9], scopeQuote: 'Zone A tender', reason: 'SYNTHETIC explanation', rawResponse: '{"relation":"supplement"}', rawProposal: { relation: 'supplement' }, confidence: .9, codes: [], systemPrompt: 'SYNTHETIC system', userPrompt: 'SYNTHETIC prompt', evidence: [{ decisionRef: 5, sourceDocumentId: 71, sourceHash: 'source-a', partId: '71:0', fileName: 'SYNTHETIC-first.docx', value: '["original"]', sourceQuote: 'Original requirement for Zone A tender.', context: { sourceStart: 0, sourceEnd: 45, sourceText: 'Original requirement for Zone A tender.' } }, { decisionRef: 9, sourceDocumentId: 72, sourceHash: 'source-b', partId: '72:0', fileName: 'SYNTHETIC-revision.docx', value: '["revision"]', sourceQuote: quote, context: { sourceStart: 41, sourceEnd: 120, sourceText: quote } }] }] }
;(async () => {
  for (const [locale, label, note, direction] of [
    ['en', 'Supplement proposed', 'does not adopt or replace', 'SYNTHETIC-first.docx → SYNTHETIC-revision.docx'],
    ['zh-Hans', '建议补充', '不会自动采用或替换', 'SYNTHETIC-first.docx → SYNTHETIC-revision.docx'],
    ['zh-Hant', '建議補充', '不會自動採用或替換', 'SYNTHETIC-first.docx → SYNTHETIC-revision.docx']
  ]) {
    const before = JSON.stringify(trace), host = renderer(), app = host.createApp(field, { fieldKey: 'additionalSubmissions', trace, locale })
    app.mount(host.root); await settle()
    assert.ok(text(host.root).includes(label), `${locale}: proposed supplement is readable`)
    assert.ok(text(host.root).includes(note), `${locale}: proposals do not resolve adoption`)
    assert.ok(text(host.root).includes(direction), 'Direction uses explicit validated decision refs, not source/date order')
    assert.ok(text(host.root).includes(quote), 'Original quotations are preserved as text')
    assert.ok(text(host.root).includes('source-b') && text(host.root).includes('[41, 120)'), 'Original source identity and exact context range are inspectable')
    assert.ok(text(host.root).includes(trace.relations[0].rawResponse), 'Original response is retained')
    assert.equal(find(host.root, node => ['button', 'input', 'textarea', 'select'].includes(node.tag)), undefined, 'Relationship diagnostics have no adoption/write controls')
    assert.equal(JSON.stringify(trace), before, 'Displaying proposals cannot mutate candidates or the trace')
    app.unmount()
  }
  for (const [status, expected] of [['undetermined', 'Relationship undetermined'], ['skipped', 'Review skipped'], ['rejected', 'Proposal not accepted'], ['failed', 'Review failed']]) {
    const host = renderer(), app = host.createApp(field, { fieldKey: 'additionalSubmissions', trace: { ...trace, relations: [{ ...trace.relations[0], status, codes: ['joint_scope_invalid'] }] }, locale: 'en' })
    app.mount(host.root); await settle()
    assert.ok(text(host.root).includes(expected), status)
    assert.ok(!text(host.root).includes('Supplement proposed'), 'Rejected/skipped/raw proposals are never displayed as validated relationships')
    assert.ok(text(host.root).includes('joint_scope_invalid') && text(host.root).includes('shared scope'), 'Reason and original diagnostic code remain visible')
    app.unmount()
  }
  for (const [locale, explanation] of [['en', 'linked pending qualification'], ['zh-Hans', '关联的待定限定'], ['zh-Hant', '關聯的待定限定']]) {
    const pendingHost = renderer(), pendingApp = pendingHost.createApp(field, { fieldKey: 'additionalSubmissions', trace: { ...trace, relations: [{ ...trace.relations[0], status: 'rejected', codes: ['joint_amendment_context_unresolved'] }] }, locale })
    pendingApp.mount(pendingHost.root); await settle()
    assert.ok(text(pendingHost.root).includes(explanation), `${locale}: linked pending amendment context is explained`)
    pendingApp.unmount()
  }
  const host = renderer(), app = host.createApp(field, { fieldKey: 'additionalSubmissions', trace: { ...trace, relations: undefined }, locale: 'en' })
  app.mount(host.root); await settle()
  assert.ok(!find(host.root, node => node.props?.['data-joint-review']), 'Legacy records do not invent a review')
  app.unmount()
  for (const relation of [
    { ...trace.relations[0], status: 'undetermined', evidence: [null, null], codes: ['joint_context_insufficient'] },
    { ...trace.relations[0], status: 'proposed', relation: 'explicit_replacement', fromDecisionRef: null, toDecisionRef: null, evidence: [null, trace.relations[0].evidence[1]] },
    { ...trace.relations[0], status: 'future_status', relation: 'future_relation', codes: ['future_reason'] }
  ]) {
    const nullHost = renderer(), nullApp = nullHost.createApp(field, { fieldKey: 'additionalSubmissions', trace: { ...trace, relations: [relation] }, locale: 'en' })
    nullApp.mount(nullHost.root); await settle()
    assert.ok(!text(nullHost.root).includes(' → '), 'Missing or unvalidated refs never imply a source order/direction')
    if (relation.status === 'future_status') assert.ok(text(nullHost.root).includes('Relationship status unknown') && text(nullHost.root).includes('future_reason'))
    nullApp.unmount()
  }
  const oldTrace = { ...trace, runId: 'SYNTHETIC-older-run', relations: [{ ...trace.relations[0], key: 'nscAlternativeText', relation: 'explicit_replacement', fromDecisionRef: 9, toDecisionRef: 5 }] }
  const loadedReport = loadVue(path.join(__dirname, '../src/components/DraftingExtractionReport.vue'), { boundaries: { '@/api': { draftingApi: { extractTraces: async () => [{ runId: oldTrace.runId, finishedAt: '2026-01-01', status: 'completed', model: 'SYNTHETIC' }], extractRun: async () => oldTrace } } } }).default
  const reportHost = renderer(), reportProps = vue.reactive({ trace: { ...trace, runId: 'SYNTHETIC-current-run' }, projectId: 'SYNTHETIC-project', locale: 'en', open: true })
  const reportApp = reportHost.createApp({ setup: () => () => vue.h(loadedReport, reportProps) })
  reportApp.mount(reportHost.root); await settle()
  assert.ok(text(reportHost.root).includes('Supplement proposed') && text(reportHost.root).includes('additionalSubmissions'), 'Latest report shows only its own source relationship')
  const selection = find(reportHost.root, node => node.tag === 'select' && node.props['aria-label'] === 'Extraction report')
  assert.ok(selection, 'Actual report selector is mounted')
  await selection.props.onChange({ target: { value: oldTrace.runId } }); await settle()
  assert.ok(text(reportHost.root).includes('Explicit replacement proposed') && text(reportHost.root).includes('nscAlternativeText'), 'Historical relationship remains bound to selected trace')
  assert.ok(!text(reportHost.root).includes('Supplement proposed'), 'Current relationship cannot leak into historical report')
  assert.ok(text(reportHost.root).includes('SYNTHETIC-revision.docx → SYNTHETIC-first.docx'), 'Explicit refs can reverse document order; no date or packet order infers direction')
  await selection.props.onChange({ target: { value: '' } }); await settle()
  assert.ok(text(reportHost.root).includes('Supplement proposed') && !text(reportHost.root).includes('Explicit replacement proposed'))
  reportApp.unmount()
  console.log('PASS: mounted trilingual joint evidence diagnostics retain direction, original context/raw result and non-adoption semantics across statuses and legacy records')
})().catch(error => { console.error(error); process.exitCode = 1 })
