// Real adoption cards and unresolved controls; only API/editor transport is synthetic.
const assert = require('node:assert/strict'), path = require('node:path'), vue = require('vue'), piniaRuntime = require('pinia')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle } = require('./helpers/render-controls.cjs')
const label = en => ({ en, zhHans: en, zhHant: en }), revisionId = 'TEST-working-revision'
const fields = [
  { key: 'bq', kind: 'choice', label: label('BQ format'), options: [{ value: 'Hardcopy', label: label('Hardcopy') }, { value: 'L10Pro', label: label('Electronic') }] },
  { key: 'negative', kind: 'boolean', label: label('Adopted No') },
  { key: 'unknown', kind: 'boolean', label: label('Unadopted answer') },
  { key: 'alignment', kind: 'text', label: label('Alignment variable') },
  { key: 'scope', kind: 'text', label: label('Scope variable') },
  { key: 'staleFact', kind: 'text', label: label('Fact needing recheck') }
]
const source = { id: '41', sourceDocumentId: 41, fileKey: 'NTT', fileName: 'TEST ONLY original.docx', format: 'docx', canEdit: true, evidenceStatus: 'bound', sourceSha256: 'a'.repeat(64) }
const graph = { kind: 'consense-template-graph-v1', graph: { variables: [], sourceReviews: {}, events: [] }, sources: [source], selectedDocuments: { NTT: source.id } }
const revision = { id: revisionId, status: 'DRAFT', graphSha256: 'TEST-graph', graph, sourceStale: false }
const variables = fields.map(field => ({ key: field.key, kind: field.kind, label: field.label, graphRevisionId: revisionId, fieldDefinitionHash: 'TEST-' + field.key, value: field.key === 'bq' ? 'Hardcopy' : field.key === 'negative' ? 'false' : field.key === 'unknown' ? '' : 'Saved value', confirmed: field.key !== 'unknown', reviewRequired: field.key === 'staleFact', adoptionState: field.key === 'unknown' ? 'missing' : field.key === 'staleFact' ? 'needs_review' : 'adopted', candidates: [] }))
const issues = ['bq', 'negative', 'unknown', 'staleFact'].map(key => ({ id: 'NodeReviewRequired:' + key, kind: 'NodeReviewRequired', inputKeys: [key], message: label('Template item still needs review') }))
issues.push({ id: 'CatalogAlignmentReview:alignment', kind: 'CatalogAlignmentReview', inputKeys: ['alignment'], message: label('Alignment needs its own review') }, { id: 'SourceBindingRequired:scope-action', actionId: 'scope-action', kind: 'SourceBindingRequired', inputKeys: ['scope'], message: label('Exact source binding is missing') })
const navigation = [], writes = [], focuses = []
const bodyId = 'word/document.xml#/w:document[1]/w:body[1]/w:p[1]'
const savedDocument = { fileKey: 'NTT', generated: true, content: 'TEST saved preview', revisionId: 'TEST-doc', docxSha256: 'TEST-doc-sha', sourceSha256: source.sourceSha256, stale: false, unresolved: issues, blocks: [{ id: bodyId, text: 'TEST saved preview', textHash: 'TEST-text-sha', paragraphOrdinal: 1, editable: true, bindingId: 'CSTEST1' }] }
const Editor = { setup(_props, { expose }) { expose({ ready: async () => {}, locateQuestion: async (key, id) => { navigation.push({ key, id }) } }); return () => vue.h('section', { 'data-test-template-editor': true }, 'Template review editor') } }
const api = {
  catalog: async () => ({ ruleVersion: 'TEST-graph', graphRevisionId: revisionId, groups: [{ id: 'facts', label: label('Project facts'), fields }] }),
  variables: async () => variables, templates: async () => ['NTT', 'SCT', 'SCC'].map(key => ({ key, tag: 'ok', fileName: `TEST ${key}.docx` })),
  inputs: async () => [], documents: async () => [savedDocument], extractTrace: async () => null, automationLatest: async () => null, reviewSources: async () => [source],
  reviewHead: async () => ({ currentRevisionId: null, draftRevisionId: revisionId, current: null, draft: revision, sources: [source] }),
  reviewCandidates: async () => ({ variables: [], graphRevisionId: revisionId }),
  plan: async () => ({ ruleVersion: 'TEST-graph', graphRevisionId: revisionId, reviewStatus: 'DRAFT', inputValues: { bq: 'Hardcopy', negative: false }, effectiveValues: {}, actions: [{ id: 'scope-action', action: 'pending', document: 'NTT', clause: 'TEST scope', sourceText: 'TEST exact source', inputKeys: ['scope'] }], unresolved: issues }),
  updateVariable: async () => { writes.push('adopt'); throw Error('Navigation must not adopt again') },
  reviewSave: async () => { writes.push('save'); throw Error('Navigation must not confirm the graph') },
  reviewExtract: async () => { writes.push('extract'); throw Error('Navigation must not call a model') },
  generate: async () => { writes.push('generate'); throw Error('Navigation must not generate') }
}
const Modal = { props: ['open'], setup: (props, { slots }) => () => props.open ? vue.h('section', slots.default?.()) : null }
const loaded = loadVue(path.join(__dirname, '../src/views/DraftingView.vue'), { boundaries: {
  '@/api': { draftingApi: api, setLlmProfileResolver() {} }, '@/api/source-locations': { reviewSourceBindings: async () => ({ bindings: [] }) }, pinia: piniaRuntime, 'vue-i18n': require('vue-i18n'),
  '@/components/DraftingSourceReview.vue': { default: Editor }, '@/components/AppModal.vue': { default: Modal },
  '@/components/DraftingDocumentWorkspace.vue': { default: { setup(_props, { slots }) { return () => vue.h('section', slots.default?.()) } } }, '@/components/DraftingTemplatePreview.vue': { default: { render: () => null } }
}, globals: { structuredClone, Blob, URL, crypto: require('node:crypto').webcrypto, localStorage: { getItem: () => 'en', setItem() {} }, window: { addEventListener() {}, removeEventListener() {} }, document: { documentElement: {}, getElementById(id) { focuses.push(id); return null } } } })
const pinia = piniaRuntime.createPinia(); piniaRuntime.setActivePinia(pinia)
const state = loaded.loadLocal(path.join(__dirname, '../src/drafting/state.ts'))
state.projectDrafts.set('TEST-TEMPLATE-REVIEW', { values: {}, baseline: {}, view: { step: 'variables', activeFile: 'NTT' } })
loaded.loadLocal(path.join(__dirname, '../src/stores/app.ts')).useAppStore().switchProject('TEST-TEMPLATE-REVIEW')
const host = renderer(), app = host.createApp(loaded.default); app.use(pinia); app.use(loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n)
const control = (attr, value) => find(host.root, node => node.props?.[attr] === value)
const button = title => find(host.root, node => node.tag === 'button' && text(node).trim() === title)
async function click(node) { assert.ok(node, 'Expected rendered control'); assert.notEqual(node.props.disabled, true); await node.props.onClick(); await settle() }
;(async () => {
  app.mount(host.root); await settle()
  const warning = control('data-template-review-pending', 'bq'); assert.ok(warning, 'Adopted BQ needs a separate template-review status beside its saved value')
  assert.match(text(warning), /Project value adopted.*Template item awaiting review/)
  assert.ok(control('data-template-review-pending', 'negative'), 'A deliberately adopted false is still a saved project fact')
  assert.equal(control('data-template-review-pending', 'unknown'), undefined, 'An unanswered field must not claim its value is adopted')
  assert.equal(control('data-template-review-pending', 'alignment'), undefined, 'Catalog alignment is not silently labelled as this node-review case')
  assert.equal(control('data-template-review-pending', 'scope'), undefined, 'Source-binding failures remain separate')
  assert.equal(control('data-template-review-pending', 'staleFact'), undefined, 'A fact requiring its own recheck must not be labelled currently adopted')
  await click(control('data-review-adopted-template', 'bq')); assert.deepEqual(navigation, [{ key: 'bq', id: revisionId }]); assert.deepEqual(writes, [])
  await click(button('3 Adopt and plan')); await click(control('data-unresolved-template', 'bq'))
  assert.deepEqual(navigation.at(-1), { key: 'bq', id: revisionId }); assert.equal(focuses.includes('field-bq'), false, 'NodeReviewRequired routes to the template, not to adopting the same value')
  await click(button('3 Adopt and plan')); const before = navigation.length
  await click(button('Alignment variable')); assert.equal(navigation.length, before); assert.ok(focuses.includes('field-alignment'))
  await click(button('Scope variable')); assert.equal(navigation.length, before); assert.ok(focuses.includes('field-scope'))
  await click(button('4 Preview and export')); await click(button('Edit paragraph'))
  const pendingBody = 'TEST unsaved body must remain in this preview', body = control('id', 'body-p-1'); assert.ok(body, 'The actual body editor is mounted')
  body.props.onInput({ target: { value: pendingBody } }); await settle()
  const blockedReview = control('data-unresolved-template', 'bq'), blockedByControl = blockedReview.props.disabled === true, beforeDirtyNavigation = navigation.length
  await blockedReview.props.onClick(); await settle()
  assert.equal(navigation.length, beforeDirtyNavigation, 'The template-review handler must refuse navigation even when a dirty-preview click is forced past the control')
  assert.equal(blockedByControl, true, 'The dirty-preview template-review control must also be disabled')
  assert.equal(button('4 Preview and export').props['aria-current'], 'step'); assert.equal(control('id', 'body-p-1').value, pendingBody)
  assert.equal(state.projectDrafts.get('TEST-TEMPLATE-REVIEW').documents.NTT.body.texts[bodyId], pendingBody); assert.equal(savedDocument.blocks[0].text, 'TEST saved preview'); assert.deepEqual(writes, [])
  const words = loaded.loadLocal(path.join(__dirname, '../src/drafting/words.ts')); await click(button(words.draftWord('discard', 'en')))
  await click(control('data-unresolved-template', 'bq')); assert.deepEqual(navigation.at(-1), { key: 'bq', id: revisionId }); assert.equal(navigation.length, before + 1, 'Saved-preview unresolved items use the same template-review route once body changes are deliberately discarded')
  await click(button('3 Adopt and plan')); const afterPreview = navigation.length
  const exactEdit = loaded.loadLocal(path.join(__dirname, '../src/drafting/words.ts')).draftWord('exactEdit', 'en'); await click(button(exactEdit)); assert.match(text(host.root), /TEST exact source/); assert.equal(navigation.length, afterPreview, 'A missing source binding keeps its existing exact-target route')
  assert.deepEqual(writes, []); assert.equal(variables[0].value, 'Hardcopy'); assert.equal(variables[1].value, 'false'); assert.equal(issues[0].kind, 'NodeReviewRequired')
  app.unmount(); console.log('PASS: adopted values retain separate template-review status, node-review actions open the current template, other unresolved routes and facts remain unchanged (synthetic transport only)')
})().catch(error => { app.unmount(); console.error(error); process.exitCode = 1 })
