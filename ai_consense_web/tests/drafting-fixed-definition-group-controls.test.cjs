const assert = require('node:assert/strict')
const path = require('node:path')
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle } = require('./helpers/render-controls.cjs')

const copy = value => JSON.parse(JSON.stringify(value))
const source = { id: '41', sourceDocumentId: 41, docId: '41', editionId: 'NTT-TEST', fileKey: 'NTT', fileName: 'TEST template.docx', format: 'docx', sourceSha256: 'a'.repeat(64), evidenceStatus: 'bound', parseStatus: 'PARSED', canEdit: true }
const variable = id => ({ id, nameZh: id, nameEn: id, docIds: ['41'], type: { kind: 'boolean' }, role: { kind: 'input' }, status: 'pending', options: [], sourceAnchors: [], sourceUnitIds: [], editTargets: [], relatedInputIds: [], relations: [], targets: [], evidence: [] })
const fixed = { ...variable('fixed-bq'), definitionKind: 'fixed_catalog', definitionReview: { confirmed: false }, catalogAlignment: { status: 'matched', confirmed: false, baselineKey: 'bqIssueFormat', baselineVersion: 'test' } }
const custom = { ...variable('custom'), definitionReview: { confirmed: true } }
const guidance = { ...variable('guidance'), definitionKind: 'source_seed', role: { kind: 'verification' } }
const target = { ...variable('edit-object'), role: { kind: 'system' } }
const child = { ...variable('fixed-detail'), parentId: fixed.id }
const graph = { kind: 'consense-template-graph-v1', graph: { variables: [fixed, custom, guidance, target, child, variable('unreviewed-custom')], events: [], sourceReviews: {}, nextId: 1, blank: false, guidanceMode: 'minimum' }, sources: [source], selectedDocuments: { NTT: '41' } }
let saved = { id: 'draft-1', status: 'DRAFT', sourceStale: false, graph: copy(graph), definitionGraphReview: { confirmed: false, valid: false, confirmedFieldIds: [], pendingFieldIds: [fixed.id, custom.id], fixedCandidateFieldIds: [], unresolvedRelationCount: 0, baselineReuseSupported: true, completeBaselineReuseSupported: true } }
const confirmations = [], writes = []
const api = {
  reviewHead: async () => ({ currentRevisionId: null, draftRevisionId: saved.id, current: null, draft: copy(saved), sources: [source] }),
  reviewReading: async () => ({ ...source, paragraphs: [], blocks: [], guidanceNotes: [], sourceMembers: [], markerCandidates: [], groups: [], conditions: [], issues: [] }),
  reviewPreview: async () => { throw Error('TEST preview unavailable') },
  reviewCandidates: async () => ({ variables: [] }),
  reviewSave: async (...args) => { writes.push(args); throw Error('Group confirmation must not manufacture a local review save') },
  reviewConfirmDefinitions: async (...args) => {
    confirmations.push(copy(args)); saved = { ...copy(saved), id: 'draft-' + (confirmations.length + 1), definitionGraphReview: { ...saved.definitionGraphReview, valid: true, confirmedFieldIds: copy(args[5]), pendingFieldIds: [] } }
    return copy(saved)
  }
}
const Modal = { props: ['open'], setup: (props, { slots }) => () => props.open ? vue.h('section', slots.default?.()) : null }
const nativeWindow = new (require('jsdom').JSDOM)('').window
const loaded = loadVue(path.join(__dirname, '../src/components/DraftingSourceReview.vue'), { boundaries: {
  '@/api': { draftingApi: api }, '@/api/source-locations': { reviewSourceBindings: async () => { throw Error('TEST locations unavailable') } }, './AppModal.vue': { default: Modal }, './DraftingPdfPreview.vue': { default: { render: () => null } }
}, globals: { structuredClone, Blob, URL, crypto: require('node:crypto').webcrypto, window: nativeWindow, document: nativeWindow.document, Element: nativeWindow.Element } })
const editor = vue.ref(), host = renderer(), app = host.createApp({ setup: () => () => vue.h(loaded.default, { ref: editor, projectId: 'TEST-FIXED-DEFINITION-GROUP', locale: 'en' }) })
const control = (attribute, value) => find(host.root, node => value === undefined ? node.props?.[attribute] !== undefined : node.props?.[attribute] === value)
const update = async (attribute, value) => { const node = control(attribute); assert.ok(node, attribute); node.props['onUpdate:modelValue'](value); await settle() }
const choose = async ids => { const node = control('data-definition-group-choice', ids[0]); assert.ok(node, ids[0]); node.props['onUpdate:modelValue'](ids); await settle() }
const confirm = async () => { const node = control('data-definition-graph-confirm'); assert.equal(node.props.disabled, false); await node.props.onClick(); await settle() }

;(async () => {
  app.mount(host.root); await settle(); await editor.value.ready()
  assert.ok(control('data-definition-group-choice', 'custom'))
  assert.equal(control('data-definition-group-choice', fixed.id), undefined, 'The frontend cannot infer eligibility from an unconfirmed matched key')
  await choose(['custom']); await update('data-definition-reuse-edition', true); await update('data-definition-complete-edition', true)

  saved.graph.graph.fixedRuleBaseline = { kind: 'fixed_catalog', coverage: 'fixed_rules_with_unresolved_scopes' }
  saved.graph.graph.baselineRestoration = { kind: 'fixed_catalog_candidates', status: 'partial_requires_review', projectAnswersImported: false }
  saved.definitionGraphReview.fixedCandidateFieldIds = [fixed.id, guidance.id, target.id, child.id]
  await editor.value.refresh(false); await settle()
  assert.ok(control('data-definition-group-choice', fixed.id), 'Server-verified pending fixed candidates are available without individually confirming them first')
  for (const id of [guidance.id, target.id, child.id, 'unreviewed-custom']) assert.equal(control('data-definition-group-choice', id), undefined, id + ' must not become a root variable choice')
  assert.ok(control('data-restored-fixed-candidates')); assert.ok(control('data-definition-fixed-candidate', fixed.id))
  assert.equal(control('data-definition-reuse-edition').props.disabled, true, 'A descriptor blocks reuse even when only a custom root was selected')
  assert.equal(control('data-definition-reuse-edition').checked, false, 'An earlier reuse choice is cleared when a fixed-rule draft loads')
  assert.equal(control('data-definition-complete-edition'), undefined)
  assert.match(text(control('data-definition-fixed-reuse-blocked')), /not registered as a fully reviewed baseline/)
  await choose([fixed.id]); assert.equal(control('data-definition-graph-confirm').props.disabled, true, 'Selection alone does not confirm anything')
  await update('data-definition-graph-reason', 'Reviewed the fixed meaning and prerequisite'); assert.equal(control('data-definition-graph-confirm').props.disabled, true)
  await update('data-definition-graph-acknowledged', true)
  assert.equal(saved.graph.graph.variables.find(item => item.id === fixed.id).catalogAlignment.confirmed, false)
  assert.equal(writes.length, 0); assert.equal(confirmations.length, 0)
  // Even a stale control update cannot smuggle reuse flags into the confirmation request.
  await update('data-definition-reuse-edition', true); await update('data-definition-complete-edition', true); await confirm()
  assert.deepEqual(confirmations[0], ['TEST-FIXED-DEFINITION-GROUP', 'draft-1', null, 'Reviewed the fixed meaning and prerequisite', true, [fixed.id]])
  assert.equal(writes.length, 0, 'No per-node confirmed flags or empty-range baseline are written by the frontend')

  delete saved.graph.graph.fixedRuleBaseline; delete saved.graph.graph.baselineRestoration
  saved.definitionGraphReview = { ...saved.definitionGraphReview, valid: false, confirmedFieldIds: [fixed.id], pendingFieldIds: ['custom'], fixedCandidateFieldIds: [] }
  await editor.value.refresh(false); await settle(); await choose(['custom'])
  assert.equal(control('data-definition-reuse-edition').props.disabled, false)
  await update('data-definition-graph-reason', 'Reviewed the custom edition graph'); await update('data-definition-graph-acknowledged', true)
  await update('data-definition-reuse-edition', true); await update('data-definition-complete-edition', true); await confirm()
  assert.deepEqual(confirmations[1], ['TEST-FIXED-DEFINITION-GROUP', 'draft-2', null, 'Reviewed the custom edition graph', true, ['custom'], true, true], 'Ordinary custom graph reuse keeps its existing API contract')
  app.unmount()
  console.log('PASS: pending fixed definitions use explicit group confirmation; unknown ranges cannot enter edition reuse; ordinary custom reuse is preserved')
})().catch(error => { console.error(error); process.exitCode = 1 })
