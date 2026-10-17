const assert = require('node:assert/strict')
const path = require('node:path')
const vue = require('vue')
const piniaRuntime = require('pinia')
const { loadVue } = require('./helpers/load-vue.cjs')
const text = node => node.text || (node.children ?? []).map(text).join(' ')
const find = (node, predicate) => predicate(node) ? node : (node.children ?? []).map(child => find(child, predicate)).find(Boolean)
const settle = async () => { for (let i = 0; i < 25; i++) await Promise.resolve(); await vue.nextTick() }
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }
const report = {
  model: '7b', finishedAt: '2026-10-06T09:00:00Z', systemPrompt: 'exact system', userPrompt: 'exact user', rawResponses: ['bad raw'],
  runId: 'failed-A', status: 'failed', stale: true, failureCode: 'invalid_json', failureMessage: 'Last part failed',
  parts: [{ partId: 'p1', sourceDocumentId: 3, fileName: 'source.docx', sourceHash: 'source-hash-A', partIndex: 1, sourceText: 'Exact source text', attempts: [{ attemptIndex: 1, kind: 'primary', systemPrompt: 'attempt system', userPrompt: 'attempt user', rawResponse: 'bad raw', status: 'failed', errorCode: 'invalid_json' }] }],
  decisions: [{ partId: 'p1', attemptIndex: 1, itemIndex: 0, key: 'contractTitle', rawValue: 'Rejected title', normalizedValue: null, sourceQuote: 'Exact source text', reason: 'Mismatch', confidence: 0.8, status: 'rejected', codes: ['lexical_support_missing'] }],
  fields: [{ key: 'contractTitle', status: 'rejected', candidateCount: 0, rejectionCount: 1, unansweredCount: 0, decisionRefs: [0] }]
}
function harness(overrides = {}) {
  const root = { children: [] }
  const host = {
    createElement(tag) {
      const node = { tag, tagName: tag.toUpperCase(), children: [], props: {}, listeners: {}, style: {}, value: '',
        addEventListener(name, handler) { this.listeners[name] = handler }, removeEventListener(name) { delete this.listeners[name] },
        get options() { return this.children.filter(child => child.tag === 'option') } }
      return node
    }, createText: text => ({ text }), createComment: text => ({ comment: text }),
    insert(node, parent, anchor) { if (node.parent) host.remove(node); node.parent = parent; const index = anchor ? parent.children.indexOf(anchor) : -1; index < 0 ? parent.children.push(node) : parent.children.splice(index, 0, node) },
    remove(node) { const index = node.parent?.children.indexOf(node); if (index >= 0) node.parent.children.splice(index, 1); node.parent = null },
    setText: (node, text) => { node.text = text }, setElementText: (node, text) => { node.children = []; node.text = text },
    parentNode: node => node.parent, nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1] ?? null,
    patchProp: (node, key, _old, value) => { node.props[key] = value; if (key === 'value') node.value = value },
    insertStaticContent(content, parent, anchor) { const node = { text: content, props: {}, children: [] }; host.insert(node, parent, anchor); return [node, node] }
  }
  const field = { key: 'contractTitle', kind: 'text', label: { en: 'Contract identity', zhHans: '合约身份', zhHant: '合約身份' } }
  const writes = []
  const api = {
    catalog: async () => ({ ruleVersion: 'unchanged', groups: [{ key: 'contract', label: field.label, fields: [field] }] }),
    variables: async () => [{ key: 'contractTitle', value: 'Manual value', confirmed: true, manuallyEdited: true, candidates: [] }],
    templates: async () => [], inputs: async () => [{ id: 3, fileName: 'source.docx', status: 'PARSED' }], documents: async () => [],
    plan: async () => ({ actions: [], unresolved: [] }), extractTrace: async () => report,
    extractTraces: async () => [{ runId: 'older-A', model: '7b', status: 'completed', finishedAt: '2026-10-05T00:00:00Z' }],
    extractRun: async () => ({ ...report, runId: 'older-A', status: 'completed', stale: false, failureCode: null, failureMessage: null, rawResponses: ['old raw'] }),
    reviewHead: async () => ({ currentRevisionId: null, draftRevisionId: 'review-test', current: null, draft: { id: 'review-test', graph: { kind: 'consense-template-graph-v1', graph: { variables: [{ id: 'review-item', nameZh: 'TEST', nameEn: 'TEST', type: {kind: 'text'}, role: {kind: 'input'}, status: 'pending', docIds: [], options: [], sourceAnchors: [], editTargets: [], sourceUnitIds: [], relatedInputIds: [], relations: [], targets: [], evidence: [] }], events: [], sourceReviews: {}, nextId: 1 }, sources: [], selectedDocuments: {} } }, sources: [] }),
    reviewCandidates: async () => ({ variables: [] }),
    updateVariable: async (...args) => { writes.push(args); throw new Error('Read-only diagnostics must not save values') },
    ...overrides
  }
  const loaded = loadVue(path.join(__dirname, '../src/views/DraftingView.vue'), { boundaries: {
    '@/api': { draftingApi: api, setLlmProfileResolver() {}, captureLlmSelection: () => ({ profileId: null }) }, pinia: piniaRuntime, 'vue-i18n': require('vue-i18n'),
    'pdfjs-dist': { GlobalWorkerOptions: {}, getDocument() { throw new Error('PDF renderer is outside this diagnostic test') } },
    'pdfjs-dist/build/pdf.worker.min.mjs?url': { default: 'external-pdf-worker' }
  }, globals: { localStorage: { getItem: () => 'en', setItem() {} }, window: { addEventListener() {}, removeEventListener() {} }, document: { documentElement: {}, getElementById() { return null } } } })
  const pinia = piniaRuntime.createPinia(); piniaRuntime.setActivePinia(pinia)
  const store = loaded.loadLocal(path.join(__dirname, '../src/stores/app.ts')).useAppStore()
  store.switchProject('A')
  const app = vue.createRenderer(host).createApp(loaded.default); app.use(pinia); app.use(loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n); app.mount(root)
  return { root, store, writes, dispose: () => app.unmount(),
    async click(label) { const button = find(root, node => node.tag === 'button' && text(node).trim() === label); assert.ok(button, `Rendered button: ${label}`); await button.props.onClick(); await settle() },
    async filterStatus(status) { const select = find(root, node => node.tag === 'select' && node.options.some(option => option.props.value === 'conflict')); assert.ok(select, 'Question status filter rendered'); select.props['onUpdate:modelValue'](status); await settle() },
    async changeReport(id) { const select = find(root, node => node.tag === 'select' && node.props['aria-label'] === 'Extraction report'); assert.ok(select, 'Report selection rendered'); await select.props.onChange({ target: { value: id } }); await settle() }
  }
}
;(async () => {
  const recalled = { ...report, status: 'completed', stale: false, failureCode: null, failureMessage: null,
    parts: [{ ...report.parts[0], sourceText: 'Original core part', attempts: [{ ...report.parts[0].attempts[0], kind: 'recall', status: 'completed', errorCode: null,
      rawResponse: 'Recalled source suggestion', context: { trigger: 'Missing model output beside an adoption heading', keys: ['nscAlternativeText'], sourceStart: 5629, sourceEnd: 7715,
        sourceText: 'Adopted replacement wording\nUse the following exact paragraph.', stopReason: 'candidate_found' } }] }] }
  const recall = harness({ extractTrace: async () => recalled })
  await settle(); await recall.click('Enter inputs manually'); await recall.click('Extraction trace')
  assert.match(text(recall.root), /Context recall/)
  assert.match(text(recall.root), /Missing model output beside an adoption heading/)
  assert.match(text(recall.root), /Targeted inputs: nscAlternativeText/)
  assert.match(text(recall.root), /5629.*7715/)
  assert.match(text(recall.root), /Adopted replacement wording\nUse the following exact paragraph\./)
  assert.match(text(recall.root), /A supported suggestion was found/)
  assert.match(text(recall.root), /Original core part/)
  assert.equal(find(recall.root, node => node.props?.id === 'input-contractTitle').props.value, 'Manual value')
  assert.equal(recall.writes.length, 0); recall.dispose()
  console.log('PASS: actual extraction controls distinguish context recall, target keys and dispatched offsets/text from the original source part without changing manual inputs')
  const stopLabels = [
    ['candidate_found', 'A supported suggestion was found', '已找到有依据的建议', '已找到有依據的建議'],
    ['no_supported_candidate', 'No supported suggestion was found in this context', '本次上下文未找到有依据的建议', '本次上下文未找到有依據的建議'],
    ['explicit_pending', 'The source explicitly leaves this input pending', '原文明确将该输入留作待定', '原文明確將該輸入留作待定'],
    ['context_insufficient', 'The available context is insufficient', '可用上下文不足', '可用上下文不足'],
    ['budget_exhausted', 'The recall budget was reached', '已达到补查预算上限', '已達到補查預算上限'],
    ['model_call_failed', 'The model call failed', '模型调用失败', '模型呼叫失敗'],
    ['invalid_json', 'The response could not be parsed', '模型返回无法解析', '模型回傳無法解析'],
    ['inactive', 'Recall was skipped because this input is currently inapplicable', '该输入本次不适用，已跳过补查', '該輸入本次不適用，已跳過補查'],
    ['no_source_cue', 'No related source cue was recorded', '未记录相关资料线索', '未記錄相關資料線索']
  ]
  const stopped = { ...recalled,
    parts: [{ ...recalled.parts[0], attempts: stopLabels.map(([stopReason], index) => ({ ...recalled.parts[0].attempts[0], attemptIndex: index + 1, context: { ...recalled.parts[0].attempts[0].context, stopReason } })) }],
    decisions: [{ ...report.decisions[0], codes: ['recall_key_not_allowed'] }] }
  const reasons = harness({ extractTrace: async () => stopped })
  await settle(); await reasons.click('Enter inputs manually'); await reasons.click('Extraction trace')
  for (const [locale, column, recallLabel, keyGuard] of [
    ['en', 1, 'Context recall', 'Context recall returned a field outside the requested keys'],
    ['zh-Hans', 2, '上下文补查', '上下文补查返回了目标字段以外的取值'],
    ['zh-Hant', 3, '上下文補查', '上下文補查回傳了目標欄位以外的取值']
  ]) {
    reasons.store.changeLocale(locale); await settle()
    assert.ok(text(reasons.root).includes(recallLabel), `${locale}: recall attempt label`)
    for (const row of stopLabels) {
      assert.ok(text(reasons.root).includes(row[column]), `${locale}: ${row[0]} stop explanation`)
      assert.ok(text(reasons.root).includes(row[0]), `${locale}: raw ${row[0]} retained`)
    }
    assert.ok(text(reasons.root).includes(keyGuard), `${locale}: targeted-key guard explanation`)
    assert.match(text(reasons.root), /recall_key_not_allowed/)
    assert.equal(find(reasons.root, node => node.props?.id === 'input-contractTitle').props.value, 'Manual value')
  }
  assert.equal(reasons.writes.length, 0); reasons.dispose()
  console.log('PASS: actual extraction controls translate every recall stop and targeted-key guard in all three languages without adopting or clearing retained inputs')
  const triggerLabels = [
    ['original_neighbors', 'Original neighboring source context was included', '已包含原文相邻上下文', '已包含原文相鄰上下文'],
    ['missing_output', 'The primary extraction omitted the targeted input', '主识别未返回该目标输入', '主識別未回傳該目標輸入'],
    ['ordinary_null', 'The model returned an unknown value beside a related source cue', '模型在相关资料线索旁返回未定值', '模型在相關資料線索旁回傳未定值'],
    ['rejected_candidate', 'No usable source-local suggestion passed intake checks', '该资料中没有建议通过接收校验', '該資料中沒有建議通過接收校驗'],
    ['related_source', 'An explicit reference points to another uploaded source', '原文明示引用另一份已上传资料', '原文明示引用另一份已上傳資料']
  ]
  const planned = { ...recalled, parts: [{ ...recalled.parts[0], context: { trigger: 'missing_output', keys: ['nscAlternativeText'], sourceStart: null, sourceEnd: null, sourceText: null, stopReason: 'budget_exhausted' },
    attempts: [...triggerLabels.map(([trigger], index) => ({ ...recalled.parts[0].attempts[0], attemptIndex: index + 1, context: { ...recalled.parts[0].attempts[0].context, trigger, sourceStart: 0, sourceEnd: 74 } })),
      { ...recalled.parts[0].attempts[0], attemptIndex: 6, kind: 'future_attempt', context: { trigger: 'future_trigger', stopReason: 'future_stop' } }] }] }
  const planning = harness({ extractTrace: async () => planned })
  await settle(); await planning.click('Enter inputs manually'); await planning.click('Extraction trace')
  for (const [locale, column, planLabel, unknownLabel, unknownTrigger, unknownStop] of [
    ['en', 1, 'Source context plan', 'Recorded attempt type', 'Trigger description is not recorded', 'Stop description is not recorded'],
    ['zh-Hans', 2, '资料上下文计划', '记录的调用类型', '未记录此触发原因的说明', '未记录此停止原因的说明'],
    ['zh-Hant', 3, '資料上下文計劃', '記錄的呼叫類型', '未記錄此觸發原因的說明', '未記錄此停止原因的說明']
  ]) {
    planning.store.changeLocale(locale); await settle()
    for (const literal of [planLabel, unknownLabel, unknownTrigger, unknownStop]) assert.ok(text(planning.root).includes(literal), `${locale}: ${literal}`)
    for (const row of triggerLabels) assert.ok(text(planning.root).includes(row[column]), `${locale}: ${row[0]} trigger explanation`)
    assert.match(text(planning.root), /\[0, 74\)/)
    for (const rawCode of ['future_attempt', 'future_trigger', 'future_stop']) assert.ok(text(planning.root).includes(rawCode), `${locale}: raw ${rawCode} retained`)
    const plannedContext = find(planning.root, node => node.props?.class === 'context-plan')
    assert.ok(plannedContext, 'Non-dispatched planning diagnostics are present beside the source')
    assert.doesNotMatch(text(plannedContext), /Actual supplied source context|本次实际提供的原文上下文|本次實際提供的原文上下文/)
    const unknownAttempt = find(planning.root, node => node.tag === 'details' && node.props?.class === 'attempt' && text(node).includes('future_attempt'))
    assert.ok(unknownAttempt, 'Unknown attempt code remains inspectable')
    assert.doesNotMatch(text(unknownAttempt), /Primary extraction|主识别调用|主識別呼叫/)
  }
  assert.equal(planning.writes.length, 0); planning.dispose()
  console.log('PASS: planned stops are distinct from dispatched original context, known triggers translate and unknown metadata stays inspectable without claiming a primary attempt')
  const incomplete = { ...recalled, decisions: [{ ...report.decisions[0], rawValue: 'Partial replacement text', sourceQuote: 'Source paragraph fragment', codes: ['context_incomplete_source'] }] }
  const fragment = harness({ extractTrace: async () => incomplete })
  await settle(); await fragment.click('Enter inputs manually'); await fragment.click('Extraction trace')
  for (const [locale, label] of [
    ['en', 'The supplied source window cuts the paragraph or table; this fragment cannot establish a complete replacement or list'],
    ['zh-Hans', '提供的原文窗口截断了段落或表格；该片段不能确定完整替换文字或清单'],
    ['zh-Hant', '提供的原文視窗截斷了段落或表格；該片段不能確定完整替換文字或清單']
  ]) {
    fragment.store.changeLocale(locale); await settle()
    assert.ok(text(fragment.root).includes(label), `${locale}: incomplete source guard explanation`)
    assert.match(text(fragment.root), /context_incomplete_source/)
    assert.match(text(fragment.root), /Partial replacement text/)
    assert.match(text(fragment.root), /Source paragraph fragment/)
    assert.equal(find(fragment.root, node => node.props?.id === 'input-contractTitle').props.value, 'Manual value')
  }
  assert.equal(fragment.writes.length, 0); fragment.dispose()
  console.log('PASS: actual extraction controls explain the incomplete paragraph/table guard in all three languages while preserving rejected fragments and manual values without writes')
  const trades = { key: 'subcontractors', kind: 'multiselect', label: { en: 'Selected trades' }, options: [{ value: 'Electrical', label: { en: 'Electrical' } }] }
  const group = { id: 'services', label: { en: 'Building services scope' }, fields: [trades] }
  const conflict = { key: trades.key, value: '', confirmed: false, manuallyEdited: false, adoptionState: 'conflict', candidates: [
    { value: '["Electrical"]', fileName: 'Complete Electrical scope', sourceQuote: 'Complete list is Electrical only.' },
    { value: '[]', fileName: 'Complete empty scope', sourceQuote: 'Complete list is none.' }
  ] }
  const scoped = harness({ catalog: async () => ({ ruleVersion: 'unchanged', groups: [group] }), variables: async () => [conflict], extractTrace: async () => null })
  await settle(); await scoped.click('Enter inputs manually')
  const fieldCard = find(scoped.root, node => node.props?.['data-variable'] === trades.key)
  assert.equal(find(fieldCard, node => node.props?.class?.includes('state')).props.class, 'state conflict')
  assert.equal(find(find(scoped.root, node => node.props?.['data-group'] === group.id), node => node.props?.class?.includes('state')).props.class, 'state conflict')
  await scoped.filterStatus('conflict')
  assert.ok(find(scoped.root, node => node.props?.['data-group'] === group.id), 'Blank conflicting scope remains visible through the conflict filter')
  assert.doesNotMatch(text(scoped.root), /No matching questions/)
  assert.match(text(scoped.root), /Complete Electrical scope/); assert.match(text(scoped.root), /Complete empty scope/)
  assert.equal(scoped.writes.length, 0); scoped.dispose()
  console.log('PASS: a blank server value with two candidate scopes renders field/group conflict and survives the conflict filter')
  const edited = harness({ catalog: async () => ({ ruleVersion: 'unchanged', groups: [group] }), variables: async () => [conflict], extractTrace: async () => null })
  await settle(); await edited.click('Enter inputs manually')
  const checkbox = find(edited.root, node => node.tag === 'input' && node.props.type === 'checkbox' && node.props.value === 'Electrical')
  assert.ok(checkbox, 'User can select a trade'); checkbox.props.onChange({ target: { checked: true } }); await settle()
  assert.equal(find(find(edited.root, node => node.props?.['data-variable'] === trades.key), node => node.props?.class?.includes('state')).props.class, 'state manual')
  await edited.filterStatus('manual')
  assert.ok(find(edited.root, node => node.props?.['data-group'] === group.id), 'Edited scope appears in the manual filter')
  await edited.filterStatus('conflict'); assert.match(text(edited.root), /No matching questions/)
  assert.equal(edited.writes.length, 0); edited.dispose()
  console.log('PASS: locally selected trades override stale server conflict in field/group status and filters without saving or adopting')
  const months = { key: 'advanceRepaymentMonths', kind: 'number', label: { en: 'Repayment months' } }
  const ordinaryGroup = { id: 'repayment', label: { en: 'Repayment' }, fields: [months] }
  const ordinary = harness({ catalog: async () => ({ ruleVersion: 'unchanged', groups: [ordinaryGroup] }), variables: async () => [{ key: months.key, value: '', candidates: [], adoptionState: 'missing' }], extractTrace: async () => null })
  await settle(); await ordinary.click('Enter inputs manually'); await ordinary.filterStatus('missing')
  assert.ok(find(ordinary.root, node => node.props?.['data-group'] === ordinaryGroup.id), 'Ordinary unanswered group remains in the missing filter')
  assert.equal(find(find(ordinary.root, node => node.props?.['data-variable'] === months.key), node => node.props?.class?.includes('state')).props.class, 'state missing')
  await ordinary.filterStatus('all')
  const numberInput = find(ordinary.root, node => node.props?.id === 'input-'+months.key)
  numberInput.props.onInput({ target: { value: '-1' } }); await settle()
  assert.equal(find(find(ordinary.root, node => node.props?.['data-variable'] === months.key), node => node.props?.class?.includes('state')).props.class, 'state needs_review')
  await ordinary.filterStatus('needs_review')
  assert.ok(find(ordinary.root, node => node.props?.['data-group'] === ordinaryGroup.id), 'Invalid manual input remains subject to the review filter')
  assert.equal(ordinary.writes.length, 0); ordinary.dispose()
  console.log('PASS: ordinary missing values stay missing and invalid local edits retain their existing review validation')
  const h = harness(); await settle(); await h.click('Enter inputs manually'); await h.click('Extraction trace')
  assert.match(text(h.root), /This extraction failed/)
  assert.match(text(h.root), /Sources have changed since this extraction/)
  assert.match(text(h.root), /Last part failed/)
  assert.match(text(h.root), /source-hash-A/)
  assert.match(text(h.root), /Primary extraction/)
  assert.match(text(h.root), /bad raw/)
  const input = find(h.root, node => node.tag === 'input' && node.props.id === 'input-contractTitle')
  assert.equal(input.props.value, 'Manual value')
  assert.equal(h.writes.length, 0)
  h.dispose()
  console.log('PASS: actual Drafting controls expose failed/stale run and attempt evidence without replacing or saving manual values')
  let latest = { ...report, runId: 'previous-success', status: 'completed', stale: false, failureMessage: null }
  const failure = harness({ extractTrace: async () => latest, reviewExtract: async () => { latest = { ...report, runId: 'latest-failure', failureMessage: 'Current source part failed' }; throw new Error('Extraction API failed') } })
  await settle(); await failure.click('Identify inputs'); await failure.click('Enter inputs manually'); await failure.click('Extraction trace')
  assert.match(text(failure.root), /Current source part failed/)
  assert.match(text(failure.root), /latest-failure/)
  assert.equal(find(failure.root, node => node.tag === 'input' && node.props.id === 'input-contractTitle').props.value, 'Manual value')
  assert.equal(failure.writes.length, 0)
  failure.dispose()
  console.log('PASS: failed extraction fetches its own diagnostic report and keeps previous manual values')
  const history = harness(); await settle(); await history.click('Enter inputs manually'); await history.click('Extraction trace'); await history.changeReport('older-A')
  assert.match(text(history.root), /Viewing a historical report/)
  assert.match(text(history.root), /old raw/)
  assert.equal(find(history.root, node => node.tag === 'input' && node.props.id === 'input-contractTitle').props.value, 'Manual value')
  assert.equal(find(history.root, node => node.props?.['data-extraction-field'] === 'contractTitle').props['data-extraction-status'], 'rejected')
  await history.changeReport(''); assert.match(text(history.root), /Last part failed/)
  assert.doesNotMatch(text(history.root), /Viewing a historical report/)
  assert.equal(history.writes.length, 0); history.dispose()
  const pending = deferred()
  const race = harness({ extractRun: () => pending.promise }); await settle(); await race.click('Enter inputs manually'); await race.click('Extraction trace')
  const older = race.changeReport('older-A'); await settle(); await race.changeReport(''); pending.resolve({ ...report, rawResponses: ['Late obsolete report'] }); await older
  assert.doesNotMatch(text(race.root), /Late obsolete report/); assert.match(text(race.root), /Last part failed/); race.dispose()
  const pendingProject = deferred()
  const switched = harness({ extractRun: () => pendingProject.promise, extractTrace: async id => ({ ...report, runId: `${id}-current`, failureMessage: `${id} current failure` }) })
  await settle(); await switched.click('Enter inputs manually'); await switched.click('Extraction trace')
  const oldProject = switched.changeReport('older-A'); await settle(); await switched.store.switchProject('B'); await settle()
  pendingProject.resolve({ ...report, rawResponses: ['Previous project secret report'] }); await oldProject
  assert.doesNotMatch(text(switched.root), /Previous project secret report|Viewing a historical report/)
  assert.match(text(switched.root), /B current failure/); assert.equal(switched.writes.length, 0); switched.dispose()
  console.log('PASS: actual historical-report controls are read-only; late selections and previous-project responses cannot replace current reports or manual inputs')
  const unavailable = harness({ extractTraces: async () => { throw new Error('Offline') } }); await settle(); await unavailable.click('Extraction trace')
  assert.match(text(unavailable.root), /Historical reports could not be loaded/)
  unavailable.store.changeLocale('zh-Hans'); await settle()
  assert.match(text(unavailable.root), /历史报告暂时无法读取/)
  assert.doesNotMatch(text(unavailable.root), /Historical reports could not be loaded/)
  unavailable.dispose()
  console.log('PASS: historical-read failures remain visible and translate when the user changes language')
  const previous = { ...report, runId: 'Unrelated earlier success', status: 'completed', stale: false, failureMessage: null }
  const unchanged = harness({ extractTrace: async () => previous, reviewExtract: async () => { throw new Error('No current report saved') } })
  await settle(); await unchanged.click('Identify inputs'); await unchanged.click('Enter inputs manually')
  assert.doesNotMatch(text(unchanged.root), /Unrelated earlier success/)
  assert.equal(find(unchanged.root, node => node.tag === 'input' && node.props.id === 'input-contractTitle').props.value, 'Manual value')
  unchanged.dispose()
  console.log('PASS: a failed call cannot relabel an unchanged earlier report as its own diagnostics')
})().catch(error => { console.error(error); process.exitCode = 1 })
