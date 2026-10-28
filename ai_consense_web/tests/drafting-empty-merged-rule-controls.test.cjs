// G159: exercise the actual merge reducer and mounted source-review control; synthetic API only.
const assert = require('node:assert/strict'), path = require('node:path'), vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle } = require('./helpers/render-controls.cjs')
const copy = value => JSON.parse(JSON.stringify(value))
const source = { id: '41', sourceDocumentId: 41, docId: 'sct', editionId: 'SCT-docx-TEST', fileKey: 'SCT', fileName: 'SYNTHETIC SCT.docx', format: 'docx', sourceSha256: 'a'.repeat(64), parseStatus: 'PARSED', canEdit: true, evidenceStatus: 'bound' }
const emptyRule = () => ({ kind: 'rule', branches: [], inputIds: [], needsRebind: true })
const node = index => ({ id: 'atomic-' + index, definitionKind: 'discovered', nameZh: '使用电子发放平台', nameEn: 'L10Pro electronic dissemination', questionZh: '是否使用电子发放平台？', questionEn: 'Is L10Pro used for electronic dissemination?', parentId: null, type: { kind: 'boolean' }, role: { kind: 'input' }, status: 'pending', optional: false, options: [], docIds: ['41'], sourceAnchors: [{ docId: '41', sourceSha256: source.sourceSha256, text: 'SYNTHETIC L10Pro source ' + index }], sourceUnitIds: ['guidance:test-' + index], correction: emptyRule(), editTargets: [], relatedInputIds: [], relations: [], targets: [], evidence: [], catalogAlignment: { status: 'new', confirmed: true, reason: 'SYNTHETIC separately reviewed atomic definition' } })
let fixture, projectNumber = 0
const saves = [], confirmations = []
const api = {
  reviewHead: async () => ({ currentRevisionId: null, draftRevisionId: 'test-draft', current: null, draft: { id: 'test-draft', parentRevisionId: null, status: 'DRAFT', graphSha256: 'test-sha', sourceStale: false, graph: copy(fixture) }, sources: [source] }),
  reviewReading: async () => ({ ...source, paragraphs: [], sourceMembers: [], guidanceNotes: [], markerCandidates: [], groups: [], conditions: [], issues: [], blocks: [] }),
  reviewPreview: async () => { throw Error('SYNTHETIC preview boundary') },
  reviewSave: async (...args) => { saves.push(copy(args)); return { id: 'saved-test', status: 'DRAFT', sourceStale: false, graph: copy(args[1]) } },
  reviewConfirm: async (...args) => { confirmations.push(copy(args)); throw Error('Template confirmation was not requested') },
  baselineCatalog: async () => ({ ruleVersion: 'SYNTHETIC', groups: [], systemFields: [] })
}
const Modal = { props: ['open'], setup: (props, { slots }) => () => props.open ? vue.h('section', slots.default?.()) : null }
const loaded = loadVue(path.join(__dirname, '../src/components/DraftingSourceReview.vue'), {
  boundaries: { '@/api': { draftingApi: api }, '@/api/source-locations': { reviewSourceBindings: async () => ({ bindings: [] }) }, './AppModal.vue': { default: Modal }, './DraftingPdfPreview.vue': { default: { render: () => null } } },
  globals: { structuredClone, URL }
})
const { createReviewReducer } = loaded.loadLocal(path.join(__dirname, '../src/drafting/source-review/reducer.js'))
const original = { variables: Array.from({ length: 8 }, (_, index) => node(index)), events: [], sourceReviews: {}, nextId: 1, blank: false, guidanceMode: 'minimum' }
const reducer = createReviewReducer({ sources: [source] })
const merged = reducer.mergeMany(original, original.variables.map(item => item.id), 'atomic-0', 'SYNTHETIC time', 'SYNTHETIC human-reviewed duplicate meanings')
assert.equal(merged.problem, null)
assert.equal(merged.state.variables.filter(item => item.status === 'excluded').length, 7)
const retained = merged.state.variables.find(item => item.id === 'atomic-0')
assert.equal(retained.discoveryCoreRetained, undefined, 'The real merge does not invent the historical discovery flag')
assert.equal(retained.sourceAnchors.length, 8)
assert.deepEqual(copy(retained.correction), emptyRule())
retained.catalogAlignment = { status: 'new', confirmed: true, reason: 'SYNTHETIC explicit post-merge definition review' }

async function mount(item, variables = [item]) {
  fixture = { kind: 'consense-template-graph-v1', graph: { ...copy(merged.state), variables: copy(variables) }, sources: [source], selectedDocuments: { SCT: '41' } }
  const host = renderer(), editor = vue.ref()
  const app = host.createApp({ setup: () => () => vue.h(loaded.default, { projectId: 'EMPTY-MERGED-' + ++projectNumber, locale: 'en', ref: editor }) })
  app.mount(host.root); await settle()
  await editor.value.locateQuestion(item.id, 'test-draft'); await settle()
  const control = attr => find(host.root, element => element.props?.[attr] !== undefined)
  const click = async attr => { const element = control(attr); assert.ok(element, attr); await element.props.onClick(); await settle() }
  if (control('data-review-edit-question')) await click('data-review-edit-question')
  return { host, app, control, click }
}

;(async () => {
  const screen = await mount(retained, merged.state.variables)
  try {
    await screen.click('data-review-confirm-item')
    assert.match(text(screen.host.root), /binding or target needs review/, 'Reviewed must still reject unresolved empty rules')
    assert.ok(screen.control('data-review-remove-isolated-rule'), 'G159: the merged sourced atomic input must expose Remove isolated rule and retain variable without discoveryCoreRetained')
    await screen.click('data-review-remove-isolated-rule')
    assert.ok(!screen.control('data-review-remove-isolated-rule'))
    assert.equal(screen.control('data-review-confirm').props.disabled, true, 'Removing a placeholder must not confirm the template')
    await screen.click('data-review-save')
    const saved = saves.at(-1)[1].graph.variables.find(item => item.id === retained.id)
    assert.equal(saved.correction, null)
    assert.equal(saved.status, 'changed', 'Rule removal leaves explicit definition confirmation outstanding')
    for (const key of ['sourceAnchors', 'sourceUnitIds', 'type', 'role', 'options', 'catalogAlignment', 'editTargets', 'targets', 'questionEn', 'questionZh']) assert.deepEqual(saved[key], copy(retained[key]), key + ' survives empty-rule removal')
    assert.equal(saves.at(-1)[1].graph.variables.filter(item => item.status === 'excluded').length, 7, 'All seven excluded duplicate identities remain available')
    await screen.click('data-review-confirm-item'); await screen.click('data-review-save')
    assert.equal(saves.at(-1)[1].graph.variables.find(item => item.id === retained.id).status, 'confirmed', 'Only the subsequent Reviewed click confirms the surviving input')
    assert.equal(confirmations.length, 0, 'No template confirmation or answer adoption accompanies the repair')
  } finally { screen.app.unmount() }

  const protectedCases = [
    ['real branch', { correction: { ...emptyRule(), branches: [{ id: 'case-1', mode: 'all', clauses: [{ inputId: 'atomic-1', operator: 'yes' }] }] } }],
    ['legacy clause', { correction: { ...emptyRule(), clauses: [{ inputId: 'atomic-1', operator: 'yes' }] } }],
    ['input dependency', { correction: { ...emptyRule(), inputIds: ['atomic-1'] } }],
    ['amendment target', { editTargets: [{ id: 'target-1', action: 'check', docId: '41' }] }],
    ['legacy target', { targets: [{ docId: '41', labelZh: 'existing target' }] }],
    ['derived answer', { role: { kind: 'derived' } }],
    ['verification', { role: { kind: 'verification' } }],
    ['fixed wording', { role: { kind: 'fixed' } }],
    ['adoption decision', { role: { kind: 'decision' } }],
    ['content', { role: { kind: 'content' } }],
    ['source seed', { definitionKind: 'source_seed', discoveryCoreRetained: true }],
    ['ordinary unsourced origin', { definitionKind: 'added', discoveryCoreRetained: false }],
    ['missing source', { missingSource: true }],
    ['no linked provenance', { sourceAnchors: [], sourceUnitIds: [] }],
    ['extra rule metadata', { correction: { ...emptyRule(), otherwise: 'false' } }],
    ['omitted branch array', { correction: { kind: 'rule', inputIds: [], needsRebind: true } }],
    ['omitted input array', { correction: { kind: 'rule', branches: [], needsRebind: true } }],
    ['resolved rule', { correction: { ...emptyRule(), needsRebind: false } }]
  ]
  for (const [name, changes] of protectedCases) {
    const blocked = await mount({ ...copy(retained), ...changes })
    try { assert.ok(!blocked.control('data-review-remove-isolated-rule'), 'Do not discard ' + name) } finally { blocked.app.unmount() }
  }
  const legacy = { ...copy(retained), definitionKind: undefined, discoveryCoreRetained: true }
  const oldControl = await mount(legacy)
  try { assert.ok(oldControl.control('data-review-remove-isolated-rule'), 'The previously supported sourced retained-core input keeps its explicit repair action') } finally { oldControl.app.unmount() }
  console.log('PASS: eight merged atomic inputs retain sources and expose explicit empty-rule cleanup; Reviewed remains guarded and 18 nonempty/noninput/provenance cases stay protected')
})().catch(error => { console.error(error); process.exitCode = 1 })
