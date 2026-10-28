const assert = require('node:assert/strict')
const path = require('node:path')
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle } = require('./helpers/render-controls.cjs')
const copy = value => JSON.parse(JSON.stringify(value))
const original = { kind: 'rule', branches: [{ id: 'case', mode: 'all', clauses: [{ inputId: 'former', operator: 'yes' }], effectEn: 'Prior condition' }] }
const source = { id: '41', sourceDocumentId: 41, docId: 'ntt', editionId: 'ntt-one', fileKey: 'NTT', fileName: 'synthetic.docx', format: 'docx', sourceSha256: 'a'.repeat(64), canEdit: true, evidenceStatus: 'bound', analysisRevision: 'test' }
const item = { id: 'owner', nameZh: '变量', nameEn: 'Variable', type: { kind: 'boolean' }, role: { kind: 'input' }, status: 'pending', definitionKind: 'discovered', docIds: ['41'], options: [], sourceAnchors: [{ docId: '41', sourceSha256: source.sourceSha256, text: 'Source text' }], sourceUnitIds: [], editTargets: [], relatedInputIds: [], relations: [], targets: [], evidence: [], correction: { kind: 'rule', needsRebind: true, branches: [], inputIds: [], supersededCorrection: original } }
let saved = { id: 'draft-1', status: 'DRAFT', sourceStale: false, graph: { kind: 'consense-template-graph-v1', graph: { variables: [item], events: [], sourceReviews: {}, nextId: 1 }, sources: [source], selectedDocuments: { NTT: '41' } } }
let writes = 0
const api = {
  reviewHead: async () => ({ currentRevisionId: null, draftRevisionId: saved.id, current: null, draft: copy(saved), sources: [copy(source)] }),
  reviewReading: async () => ({ ...copy(source), paragraphs: [], blocks: [], guidanceNotes: [], sourceMembers: [], markerCandidates: [], groups: [], conditions: [], issues: [] }),
  reviewPreview: async () => { throw Error('Synthetic preview unavailable') },
  reviewSave: async (_project, graph) => { writes++; saved = { ...saved, id: 'draft-2', graph: copy(graph) }; return copy(saved) },
  reviewRevisions: async () => [], reviewCandidates: async () => ({ variables: [] })
}
const Modal = { props: ['open'], setup: (props, { slots }) => () => props.open ? vue.h('section', slots.default?.()) : null }
const Pdf = { render: () => null }
const nativeWindow = new (require('jsdom').JSDOM)('').window
const loaded = loadVue(path.join(__dirname, '../src/components/DraftingSourceReview.vue'), { boundaries: { '@/api': { draftingApi: api }, '@/api/source-locations': { reviewSourceBindings: async () => { throw Error('Unexpected bindings') } }, './AppModal.vue': { default: Modal }, './DraftingPdfPreview.vue': { default: Pdf } }, globals: { structuredClone, window: nativeWindow, document: nativeWindow.document, Element: nativeWindow.Element, URL } })
const editor = vue.ref(), host = renderer(), app = host.createApp({ setup: () => () => vue.h(loaded.default, { projectId: 'HISTORY-TEST', locale: 'en', ref: editor }) })
app.mount(host.root)
const control = attr => find(host.root, node => node.props?.[attr] !== undefined)
const click = async attr => { const button = control(attr); assert.ok(button, attr); assert.notEqual(button.props.disabled, true, attr); await button.props.onClick(); await settle() }
;(async () => {
  await settle(); await editor.value.locateQuestion('owner', 'draft-1'); await settle()
  await click('data-review-edit-question'); await click('data-review-remove-isolated-rule')
  assert.equal(writes, 0, 'Removing an isolated rule changes only the unsaved review')
  await click('data-review-save')
  assert.equal(writes, 1)
  assert.equal(saved.graph.graph.variables[0].correction, null)
  assert.deepEqual(saved.graph.graph.variables[0].editorialReferenceHistory[0].correction, original)
  await editor.value.refresh(false); await settle()
  await editor.value.locateQuestion('owner', 'draft-2'); await settle()
  assert.match(text(host.root), /Prior condition|former|View conditions and targets before classification/)
  assert.deepEqual(saved.graph.graph.variables[0].editorialReferenceHistory[0].correction, original, 'History survives mocked save and reload')
  app.unmount()
})().catch(error => { app.unmount(); console.error(error); process.exitCode = 1 })
