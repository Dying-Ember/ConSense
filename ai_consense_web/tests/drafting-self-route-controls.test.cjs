// Real source-review controls with synthetic API boundaries; never saves business data.
const assert = require('node:assert/strict'), path = require('node:path'), vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle } = require('./helpers/render-controls.cjs')
const copy = value => JSON.parse(JSON.stringify(value)), native = new (require('jsdom').JSDOM)('').window
const source = { id: '41', sourceDocumentId: 41, docId: 'ntt', editionId: 'SYNTHETIC', fileKey: 'NTT', fileName: 'SYNTHETIC.docx', format: 'docx', sourceSha256: 'a'.repeat(64), evidenceStatus: 'bound', canEdit: true, parseStatus: 'PARSED' }
const anchor = { docId: source.id, sourceSha256: source.sourceSha256, text: 'Delete if not applicable', native: { paragraphId: 'p1', paragraphSha256: 'b'.repeat(64), start: 0, end: 24, expectedText: 'Delete if not applicable' } }
const variable = id => ({ id, nameZh: id, nameEn: id, type: { kind: 'boolean' }, role: { kind: 'input' }, status: 'pending', parentId: null, docIds: [source.id], options: [], sourceAnchors: [copy(anchor)], sourceUnitIds: [], relatedInputIds: [], relations: [], targets: [], evidence: [], correction: { kind: 'rule', branches: [{ id: 'case', mode: 'all', clauses: [{ inputId: id, operator: 'no' }] }] }, editTargets: [{ ...copy(anchor), action: 'delete', branchId: 'case' }] })
const route = variable('Own answer'), split = variable('Split child'), seed = variable('Edited source seed')
split.splitReview = { mode: 'unknown', inputIds: [route.id, split.id], originalZh: 'Original question', originalEn: 'Original question' }
seed.definitionKind = 'source_seed'; seed.manual = true; seed.correction = null; seed.editTargets = []
const graph = { kind: 'consense-template-graph-v1', graph: { variables: [route, split, seed], sourceReviews: {}, events: [], nextId: 1, blank: false, guidanceMode: 'recognized' }, sources: [source], selectedDocuments: { NTT: source.id } }
const revision = { id: 'SYNTHETIC-DRAFT', graph, status: 'DRAFT', sourceStale: false }, writes = [], confirms = []
const api = { reviewHead: async () => ({ draft: copy(revision), draftRevisionId: revision.id, sources: [source] }), reviewReading: async () => ({ ...source, paragraphs: [], blocks: [], guidanceNotes: [], sourceMembers: [], markerCandidates: [], groups: [], conditions: [], issues: [] }), reviewPreview: async () => { throw Error('SYNTHETIC preview unavailable') }, reviewSave: async (...args) => { writes.push(copy(args)); return { ...copy(revision), graph: copy(args[1]) } }, reviewConfirm: async (...args) => { confirms.push(args) } }
const loaded = loadVue(path.join(__dirname, '../src/components/DraftingSourceReview.vue'), { boundaries: { '@/api': { draftingApi: api }, '@/api/source-locations': { reviewSourceBindings: async () => ({ bindings: [] }) }, './AppModal.vue': { default: { props: ['open'], setup: (props, { slots }) => () => props.open ? vue.h('section', slots.default?.()) : null } }, './DraftingPdfPreview.vue': { default: { render: () => null } } }, globals: { structuredClone, window: native, document: native.document, Element: native.Element, URL } })
const host = renderer(), editor = vue.ref(), app = host.createApp({ render: () => vue.h(loaded.default, { projectId: 'SELF-ROUTE-CONTROLS', locale: 'en', ref: editor }) })
const get = (attr, value) => find(host.root, n => value === undefined ? n.props?.[attr] !== undefined : n.props?.[attr] === value)
const click = async attr => { const n = get(attr); assert.ok(n, attr); assert.notEqual(n.props.disabled, true, attr); await n.props.onClick(); await settle() }
;(async () => {
  app.mount(host.root); await settle(); await editor.value.locateQuestion(route.id, revision.id); await settle()
  assert.doesNotMatch(text(host.root), /self-referential|cannot be its own prerequisite/)
  await click('data-review-confirm-item'); await editor.value.save(); assert.equal(writes.length, 1)
  assert.equal(writes[0][1].graph.variables[0].status, 'confirmed'); assert.equal(writes[0][1].graph.variables[0].correction.branches[0].clauses[0].inputId, route.id)
  assert.equal(confirms.length, 0, 'Per-item source review does not confirm the whole template or adopt an answer')
  await editor.value.locateQuestion(split.id, revision.id); await settle(); await click('data-review-confirm-item')
  assert.match(text(host.root), /relationship between the split variables/)
  await click('data-review-edit-question'); get('data-review-split-mode').props['onUpdate:modelValue']('all'); await settle(); await click('data-review-save-form')
  await click('data-review-confirm-item'); await editor.value.save()
  const saved = writes.at(-1)[1].graph.variables[1]; assert.equal(saved.splitReview.mode, 'all'); assert.equal(saved.status, 'confirmed'); assert.deepEqual(saved.correction, split.correction); assert.equal(saved.editTargets[0].branchId, 'case')
  await editor.value.locateQuestion(seed.id, revision.id); await settle(); await click('data-review-edit-question')
  assert.ok(get('data-review-source-check-detail')); assert.equal(get('data-review-role').options.find(option => option.props.value === 'input').props.disabled, true)
  assert.equal(confirms.length, 0); app.unmount()
  console.log('PASS: own-answer amendment review/save roundtrip, explicit split-relationship review with retained rules, and immutable source-check identity (3 control scenarios; synthetic API only)')
})().catch(error => { console.error(error); process.exitCode = 1 })
