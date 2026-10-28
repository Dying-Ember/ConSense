const assert = require('node:assert/strict')
const path = require('node:path')
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle } = require('./helpers/render-controls.cjs')
const copy = value => JSON.parse(JSON.stringify(value))
const source = { id: '41', sourceDocumentId: 41, docId: '41', editionId: 'NTT-TEST', fileKey: 'NTT', fileName: 'TEST template.docx', format: 'docx', sourceSha256: 'a'.repeat(64), evidenceStatus: 'bound', parseStatus: 'PARSED', canEdit: true }
const node = id => ({ id, nameZh: id, nameEn: id, docIds: ['41'], type: { kind: 'text' }, role: { kind: 'input' }, status: 'pending', options: [], sourceAnchors: [], sourceUnitIds: [], editTargets: [], relatedInputIds: [], relations: [], targets: [], evidence: [] })
const proposal = kind => ({ fromId: 'a', toId: 'b', kind, fromQuote: 'Original note A', toQuote: 'Original note B', reason: 'Original model reason', status: 'pending', executable: false })
const graph = { kind: 'consense-template-graph-v1', graph: { variables: [node('a'), node('b')], relationProposals: [proposal('same_fact'), proposal('dependency')], events: [], sourceReviews: {}, nextId: 1, blank: false, guidanceMode: 'minimum' }, sources: [source], selectedDocuments: { NTT: '41' } }
let current = { id: 'draft-1', status: 'DRAFT', sourceStale: false, graph: copy(graph) }
const historical = { ...copy(current), id: 'historical', graph: copy(graph) }
historical.graph.graph.relationProposals = []
historical.graph.graph.variables[0].nameEn = 'Historical variable definition'
const writes = []
const api = {
  reviewHead: async () => ({ currentRevisionId: null, draftRevisionId: current.id, current: null, draft: copy(current), sources: [source] }),
  reviewReading: async () => ({ ...source, paragraphs: [], blocks: [], guidanceNotes: [], sourceMembers: [], markerCandidates: [], groups: [], conditions: [], issues: [] }),
  reviewPreview: async () => { throw Error('TEST preview unavailable') },
  reviewRevisions: async () => [copy(historical)], reviewRevision: async () => copy(historical),
  reviewSave: async (_project, proposed, expectedCurrent, expectedDraft) => {
    assert.equal(expectedCurrent, null); assert.equal(expectedDraft, current.id)
    writes.push(copy(proposed)); current = { ...copy(current), id: 'draft-' + (writes.length + 1), graph: copy(proposed) }
    // This is a transport response fixture; backend stamp authority has separate service tests.
    for (const row of current.graph.graph.relationProposals) if (row.review?.basis === null) Object.assign(row.review, { basis: 'SERVER-TEST-basis', status: 'reviewed', confirmedAt: '2026-10-28T00:00:00Z' })
    return copy(current)
  }
}
const Modal = { props: ['open'], setup: (props, { slots }) => () => props.open ? vue.h('section', slots.default?.()) : null }
const loaded = loadVue(path.join(__dirname, '../src/components/DraftingSourceReview.vue'), { boundaries: {
  '@/api': { draftingApi: api }, '@/api/source-locations': { reviewSourceBindings: async () => { throw Error('TEST locations unavailable') } }, './AppModal.vue': { default: Modal }, './DraftingPdfPreview.vue': { default: { render: () => null } }
}, globals: { structuredClone, Blob, URL, crypto: require('node:crypto').webcrypto, window: { addEventListener() {}, removeEventListener() {} }, document: { documentElement: {}, getElementById() { return null } } } })
const editor = vue.ref(), host = renderer(), app = host.createApp({ setup: () => () => vue.h(loaded.default, { ref: editor, projectId: 'TEST-RELATION-HISTORY', locale: 'en' }) })
const click = async title => { const button = find(host.root, node => node.tag === 'button' && text(node).trim() === title); assert.ok(button, title); assert.ok(!button.props.disabled, title); await button.props.onClick(); await settle() }
;(async () => {
  app.mount(host.root); await settle(); await editor.value.ready()
  await editor.value.reviewRelations({ revisionId: 'draft-1', indices: [0], decision: 'implemented', reason: 'Both source ranges reviewed' }); await settle()
  assert.deepEqual(writes[0].graph.relationProposals[0].review, { decision: 'implemented', reason: 'Both source ranges reviewed', basis: null }, 'The actual SourceReview save request leaves basis and timestamp to the server')
  assert.equal(writes[0].graph.relationProposals[0].executable, false)
  assert.equal(writes[0].graph.relationProposals[1].review, undefined, 'Unselected proposals are untouched')
  const currentProposals = copy(current.graph.graph.relationProposals)
  await click('Graph versions')
  assert.match(text(find(host.root, node => node.props?.['data-history-relations-notice'] !== undefined)), /keeps the current saved relationship proposals/)
  await click('Review as a new draft'); await editor.value.save(); await settle()
  assert.equal(writes[1].graph.variables[0].nameEn, 'Historical variable definition', 'The requested historical variable is restored')
  assert.deepEqual(writes[1].graph.relationProposals, currentProposals, 'Current immutable proposals and server review records survive restoration of a graph that predates them')
  assert.equal(historical.graph.graph.relationProposals.length, 0, 'The historical revision remains unchanged')
  app.unmount()
  console.log('PASS: actual SourceReview saves a null-basis review and restores historical variables without deleting current relationship evidence')
})().catch(error => { console.error(error); process.exitCode = 1 })
