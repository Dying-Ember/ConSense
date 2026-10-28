const assert = require('node:assert/strict')
const path = require('node:path')
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle } = require('./helpers/render-controls.cjs')

const combined = { value: '[{"number":"1","type":"BQ"},{"number":"1","type":"SOR"}]', runId: 'run-1', memberIndexes: [0, 1], mergeHash: 'proof', partial: true }
const response = { runId: 'run-1', graphRevisionId: 'rev-1', variables: [{ key: 'bill', label: { en: 'Bills' }, kind: 'list', compositeBillSuggestion: combined, candidates: [
  { value: '[{"number":"1","type":"BQ"}]', fileName: 'first.docx', sourceQuote: 'BQ source quotation', sourceDocumentId: 1, originalSha256: 'sha-one' },
  { value: '[{"number":"1","type":"SOR"}]', fileName: 'second.docx', sourceQuote: 'SOR source quotation', sourceDocumentId: 2, originalSha256: 'sha-two' }
] }, { key: 'waterproof', label: { en: 'Waterproofing' }, kind: 'text', candidates: [{ value: '1. Roof;', fileName: 'waterproof.docx', sourceQuote: 'Roof source', completenessStatus: 'needs_review' }] }], catalog: { groups: [{ fields: [{ key: 'bill', label: { en: 'Bills' }, kind: 'list' }, { key: 'waterproof', label: { en: 'Waterproofing' }, kind: 'text' }] }] } }
const writes = [], events = [], enabled = vue.ref(true)
const loaded = loadVue(path.join(__dirname, '../src/components/DraftingCandidateReview.vue'), { boundaries: { '@/api': { draftingApi: {
  reviewCandidates: async () => response, reviewCorrectCandidate: async (...args) => { writes.push(args); return response }
} } } })
const host = renderer(), app = host.createApp({ setup: () => () => vue.h(loaded.default, { projectId: 'P', revisionId: 'rev-1', locale: 'en', reloadKey: 0, allowAdoption: enabled.value, onAdoptComposite: (...args) => events.push(args), onLocate: (...args) => events.push(['locate', ...args]) }) })
app.mount(host.root)
;(async () => {
  await settle(); const composite = find(host.root, node => node.props?.['data-composite-bill'] !== undefined)
  assert.ok(composite); assert.match(text(composite), /Extraction was incomplete/); assert.match(text(composite), /first\.docx.*BQ source quotation/); assert.match(text(composite), /second\.docx.*SOR source quotation/)
  assert.match(text(find(host.root, node => node.props?.['data-candidate-incomplete'] !== undefined)), /not verified.*enter the value manually/)
  const adopt = find(composite, node => node.props?.['data-adopt-composite-bill'] !== undefined); assert.equal(!!adopt.props.disabled, false)
  assert.deepEqual(writes, []); assert.deepEqual(events, [], 'Reading the combined candidate does not adopt it')
  await adopt.props.onClick(); assert.deepEqual(events[0], ['bill', combined], 'Only the deliberate action emits the compact combined identity')
  const source = find(composite, node => node.tag === 'button' && text(node) === 'Locate this source document'); await source.props.onClick(); assert.deepEqual(events[1], ['locate', '1', 'sha-one'])
  enabled.value = false; await settle(); assert.equal(adopt.props.disabled, true, 'A nonworking graph or unsaved changes prevent adoption')
  app.unmount(); console.log('PASS: combined Bill candidate exposes each source, labels incomplete runs and needs explicit adoption')
})().catch(error => { console.error(error); process.exitCode = 1 })
