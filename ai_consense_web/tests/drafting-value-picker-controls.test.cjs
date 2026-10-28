// Mounted controls with authored transport only; no browser, product API or real model calls.
const assert = require('node:assert/strict'), path = require('node:path'), vue = require('vue')
const { createHash } = require('node:crypto')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle } = require('./helpers/render-controls.cjs')
const copy = value => JSON.parse(JSON.stringify(value))
const source = { id: '41', sourceDocumentId: 41, docId: 'ntt', editionId: 'NTT-picker-test', fileKey: 'NTT', fileName: 'SYNTHETIC NTT.docx', format: 'docx', sourceSha256: 'a'.repeat(64), parseStatus: 'PARSED', evidenceStatus: 'bound', canEdit: true }
const paragraph = { id: 'p1', ordinal: 1, text: 'SYNTHETIC exact source passage', paragraphSha256: 'b'.repeat(64), sourceSha256: source.sourceSha256 }
const node = (id, role = 'input', kind = 'boolean', extra = {}) => ({ id, nameZh: id, nameEn: id, definitionKind: 'discovered', type: { kind }, role: { kind: role }, status: 'pending', optional: false, options: [], sourceAnchors: [{ docId: '41', sourceSha256: source.sourceSha256, text: paragraph.text }], docIds: ['41'], sourceUnitIds: [], editTargets: [], relatedInputIds: [], targets: [], relations: [], evidence: [], ...extra })
const business = node('business-root'), list = node('business-list', 'input', 'list'), record = node('business-record', 'input', 'record')
const detail = node('business-detail', 'input', 'text', { parentId: list.id })
const calculated = node('calculated-business-value', 'derived', 'number', { correction: { kind: 'rule', inputIds: [business.id], branches: [{ id: 'calculation', mode: 'all', clauses: [{ inputId: business.id, operator: 'yes' }], resultValue: 10 }] } })
const compiled = node('compiled-delete-passage', 'derived', 'boolean', { origin: 'scope_rules_compilation' })
const compiledList = node('compiled-list-shaped-treatment', 'derived', 'list', { origin: 'scope_rules_compilation' })
const compiledRecord = node('compiled-record-shaped-treatment', 'derived', 'record', { origin: 'scope_rules_compilation' })
const compiledDetail = node('compiled-detail-shaped-treatment', 'derived', 'text', { origin: 'scope_rules_compilation', parentId: list.id })
const selected = node('facade-treatment', 'derived', 'boolean', { origin: 'scope_rules_compilation', parentId: compiledRecord.id, correction: { kind: 'rule', inputIds: [compiled.id, business.id], branches: [
  { id: 'legacy', mode: 'all', scopeId: list.id, scopeMode: 'each', clauses: [{ inputId: compiled.id, operator: 'yes' }, { inputId: business.id, operator: 'yes' }], resultValue: true },
  { id: 'legacy-list', mode: 'all', scopeId: compiledList.id, scopeMode: 'each', clauses: [{ inputId: business.id, operator: 'yes' }], resultValue: true }
] }, editTargets: [{ docId: '41', sourceSha256: source.sourceSha256, text: paragraph.text, action: 'fill', branchId: 'legacy', valueSourceId: compiled.id, valueMode: 'join', listId: compiledList.id, itemKey: compiledDetail.id, itemValue: 'retained-value', native: { paragraphId: paragraph.id, paragraphSha256: paragraph.paragraphSha256, sourceSha256: source.sourceSha256, start: 0, end: paragraph.text.length, expectedText: paragraph.text } }] })
const check = node('source-check', 'verification'), excluded = node('excluded-business', 'input', 'boolean', { status: 'excluded' })
const fixture = { kind: 'consense-template-graph-v1', graph: { variables: [selected, business, list, record, detail, calculated, compiled, compiledList, compiledRecord, compiledDetail, check, excluded], events: [], sourceReviews: {}, nextId: 1, blank: false, guidanceMode: 'recognized' }, sources: [source], selectedDocuments: { NTT: '41' } }
const originalFixture = JSON.stringify(fixture), writes = [], previewBytes = 'SYNTHETIC PDF boundary'
const revision = { id: 'picker-draft', parentRevisionId: null, status: 'DRAFT', graphSha256: 'test-sha', sourceStale: false, graph: fixture }
const bindings = { fileKey: '41', view: 'source', sourceSha256: source.sourceSha256, docxSha256: 'synthetic-docx', pdfSha256: createHash('sha256').update(previewBytes).digest('hex'), renderProfileHash: 'synthetic-profile', geometryStatus: 'ready', bindings: [] }
const api = { reviewHead: async () => ({ draftRevisionId: revision.id, currentRevisionId: null, draft: copy(revision), current: null, sources: [source] }), reviewReading: async () => ({ ...source, paragraphs: [paragraph], sourceMembers: [], guidanceNotes: [], markerCandidates: [], groups: [], conditions: [], issues: [], blocks: [] }), reviewPreview: async () => new Blob([previewBytes]), reviewSave: async (...args) => { writes.push(args); return copy(revision) } }
const Modal = { props: ['open'], setup: (props, { slots }) => () => props.open ? vue.h('section', slots.default?.()) : null }
const Pdf = { props: ['source'], emits: ['selection'], setup: (props, { slots, emit }) => () => vue.h('section', [vue.h('button', { 'data-picker-source-selection': true, onClick: () => emit('selection', { source: props.source, page: 1, words: [{ itemIndex: 0, start: 0, end: paragraph.text.length, text: paragraph.text, context: paragraph.text, rectangle: { x: 0, y: 0, width: 10, height: 10 } }] }) }, 'Synthetic source selection'), slots.selection?.({ clear: () => {} })]) }
const loaded = loadVue(path.join(__dirname, '../src/components/DraftingSourceReview.vue'), { boundaries: { '@/api': { draftingApi: api }, '@/api/source-locations': { reviewSourceBindings: async () => bindings }, './AppModal.vue': { default: Modal }, './DraftingPdfPreview.vue': { default: Pdf } }, globals: { structuredClone, URL, crypto: { subtle: { digest: async (_algorithm, bytes) => Uint8Array.from(createHash('sha256').update(Buffer.from(bytes)).digest()).buffer } } } })
const descendants = node => [node, ...(node.children ?? []).flatMap(descendants)]
const pickerOptions = node => descendants(node).filter(item => item.props?.['data-picker-option'] !== undefined).map(item => ({ id: item.props['data-picker-option'], disabled: item.props.disabled !== undefined && item.props.disabled !== false }))
const selectOptions = node => descendants(node).filter(item => item.tag === 'option').map(item => ({ id: item.props.value, disabled: item.props.disabled !== undefined && item.props.disabled !== false }))
;(async () => {
  const host = renderer(), editor = vue.ref(), app = host.createApp({ setup: () => () => vue.h(loaded.default, { projectId: 'VALUE-PICKER-AUTHORED', locale: 'en', ref: editor }) })
  const attr = (key, value) => find(host.root, node => value === undefined ? node.props?.[key] !== undefined : node.props?.[key] === value)
  const click = async node => { assert.ok(node); await node.props.onClick(); await settle() }
  app.mount(host.root); await settle()
  try {
    await editor.value.locateQuestion(selected.id, revision.id); await settle(); await click(attr('data-review-edit-question'))
    const condition = pickerOptions(attr('data-condition-input', 'legacy:0'))
    assert.ok(condition.some(item => item.id === business.id && !item.disabled)); assert.ok(condition.some(item => item.id === calculated.id && !item.disabled), 'Calculated business values remain valid candidates')
    assert.ok(condition.some(item => item.id === compiled.id && item.disabled), 'An old treatment reference stays visible but disabled')
    for (const id of [selected.id, compiledList.id, compiledRecord.id, compiledDetail.id, check.id, excluded.id]) assert.equal(condition.some(item => item.id === id && !item.disabled), false, id)
    assert.match(text(attr('data-condition-reference-problem', 'legacy:0')), /treatment rule/)
    assert.ok(pickerOptions(attr('data-condition-scope', 'legacy-list')).some(item => item.id === compiledList.id && item.disabled)); assert.match(text(attr('data-condition-scope-reference-problem', 'legacy-list')), /treatment rule/)
    const parents = selectOptions(attr('data-review-parent')); assert.ok(parents.some(item => item.id === record.id && !item.disabled)); assert.ok(parents.some(item => item.id === compiledRecord.id && item.disabled)); assert.match(text(attr('data-parent-reference-problem')), /treatment rule/)
    const answers = selectOptions(attr('data-target-answer-source')); assert.ok(answers.some(item => item.id === calculated.id && !item.disabled)); assert.ok(answers.some(item => item.id === compiled.id && item.disabled)); assert.match(text(attr('data-target-value-reference-problem', 0)), /treatment rule/)
    const lists = selectOptions(attr('data-target-list', 0)); assert.ok(lists.some(item => item.id === list.id && !item.disabled)); assert.ok(lists.some(item => item.id === compiledList.id && item.disabled)); assert.match(text(attr('data-target-list-reference-problem', 0)), /treatment rule/)
    const details = selectOptions(attr('data-target-item-key', 0)); assert.ok(details.some(item => item.id === detail.id && !item.disabled)); assert.ok(details.some(item => item.id === compiledDetail.id && item.disabled)); assert.match(text(attr('data-target-item-reference-problem', 0)), /treatment rule/)
    assert.match(text(attr('data-review-item-problem')), /treatment rule/i, 'An invalid old reference cannot be confirmed without correction')
    await click(attr('data-review-new-variable')); const newParents = pickerOptions(attr('data-new-variable-parent'))
    assert.ok(newParents.some(item => item.id === list.id && !item.disabled)); for (const id of [compiledRecord.id, compiledList.id]) assert.equal(newParents.some(item => item.id === id), false)
    await click(attr('data-condition-cancel-input')); assert.equal(JSON.stringify(fixture), originalFixture); assert.equal(writes.length, 0)
    await click(attr('data-picker-source-selection')); await click(find(host.root, node => node.tag === 'button' && text(node) === 'Use as affected passage'))
    assert.equal(writes.length, 0); assert.equal(JSON.stringify(fixture), originalFixture)
  } finally { app.unmount() }
  const snapshot = loaded.loadLocal(path.join(__dirname, '../src/drafting/source-review-state.ts')).sourceReviewDrafts.get('VALUE-PICKER-AUTHORED')
  assert.equal(snapshot.form.targets.at(-1).valueSourceId, '', 'A newly added treatment target must ask for its value source instead of referring to its own compiled flag')
  assert.equal(snapshot.form.branches[0].clauses[0].inputId, compiled.id); assert.equal(snapshot.form.branches[1].scopeId, compiledList.id); assert.equal(snapshot.form.targets[0].valueSourceId, compiled.id); assert.equal(snapshot.form.targets[0].listId, compiledList.id); assert.equal(snapshot.form.targets[0].itemKey, compiledDetail.id); assert.equal(snapshot.form.parentId, compiledRecord.id)
  assert.equal(snapshot.graph.graph.variables.length, fixture.graph.variables.length, 'Candidate projection does not delete graph nodes')
})().catch(error => { console.error(error); process.exitCode = 1 })
