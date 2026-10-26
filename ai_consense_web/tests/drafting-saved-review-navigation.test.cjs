// Real DraftingView, SourceReview and candidate controls; only transport and heavy document readers are boundaries.
const assert = require('node:assert/strict')
const path = require('node:path')
const vue = require('vue')
const piniaRuntime = require('pinia')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle, deferred } = require('./helpers/render-controls.cjs')
const copy = value => JSON.parse(JSON.stringify(value))
const label = title => ({ en: title, zhHans: title, zhHant: title })
const source = { id: '41', sourceDocumentId: 41, docId: 'ntt', editionId: 'NTT-TEST', fileKey: 'NTT', fileName: 'TEST ONLY template.docx', format: 'docx', sourceSha256: 'a'.repeat(64), parseStatus: 'PARSED', canEdit: true, evidenceStatus: 'bound' }
const paragraph = { id: 'p1', ordinal: 1, text: 'TEST ONLY requirement', paragraphSha256: 'b'.repeat(64), sourceSha256: source.sourceSha256 }
const item = { id: 'TEST-question', nameZh: '测试变量', nameEn: 'TEST variable', docIds: ['41'], type: { kind: 'text' }, role: { kind: 'input' }, status: 'pending', options: [], sourceAnchors: [{ docId: '41', text: paragraph.text, sourceSha256: source.sourceSha256, native: { paragraphId: 'p1', paragraphSha256: paragraph.paragraphSha256, start: 0, end: paragraph.text.length, expectedText: paragraph.text } }], editTargets: [], sourceUnitIds: [], relatedInputIds: [], relations: [], targets: [], evidence: [] }
const graph = { kind: 'consense-template-graph-v1', graph: { variables: [item], events: [], sourceReviews: {}, nextId: 1, blank: false, guidanceMode: 'minimum' }, sources: [source], selectedDocuments: { NTT: '41' } }

function harness({ strictCandidateRevision = false, staleSources = false } = {}) {
  let revision = { id: 'TEST-draft-1', status: 'DRAFT', graph: copy(graph), sourceStale: false }, number = 1
  let failCatalog = false, barrier, correctionBarrier, catalogReads = 0
  let candidateRun = 'TEST-original-run', adoptedCount = 0
  const writes = [], modelCalls = [], candidate = { value: 'TEST candidate', fileName: 'TEST ONLY evidence.docx', sourceDocumentId: 42, sourceQuote: 'TEST ONLY exact evidence', extractionRunId: candidateRun, originalSha256: 'c'.repeat(64), fieldDefinitionHash: 'TEST-definition', graphRevisionId: revision.id }
  const field = { key: item.id, kind: 'text', label: label('TEST variable') }
  const variable = () => ({ key: item.id, label: field.label, kind: 'text', value: '', confirmed: false, manuallyEdited: false, graphRevisionId: revision.id, fieldDefinitionHash: 'TEST-definition', candidates: [copy(candidate)] })
  const candidateResult = () => ({ runId: candidateRun, graphRevisionId: revision.id, evidenceRevision: 'TEST-evidence', stale: staleSources, variables: [variable()] })
  const api = {
    automationLatest: async () => null,
    catalog: async id => { catalogReads++; if (barrier && id === 'TEST-P1') await barrier.promise; if (failCatalog) throw Error('TEST saved revision read failed'); return { ruleVersion: 'TEST', graphRevisionId: revision.id, groups: [{ id: 'TEST-group', label: label('TEST group'), fields: [field] }] } },
    variables: async () => [variable()],
    templates: async () => ['NTT', 'SCT', 'SCC'].map(key => ({ key, tag: 'ok', fileName: `TEST ONLY ${key}.docx` })),
    inputs: async () => [], documents: async () => [], extractTrace: async () => null,
    plan: async () => ({ actions: [], unresolved: [] }),
    baselineCatalog: async () => ({ ruleVersion: 'TEST-baseline', groups: [] }),
    reviewHead: async () => ({ currentRevisionId: null, draftRevisionId: revision.id, current: null, draft: copy(revision), sources: [source] }),
    reviewReading: async () => ({ ...source, paragraphs: [paragraph], blocks: [], guidanceNotes: [], sourceMembers: [], markerCandidates: [], groups: [], conditions: [], issues: [] }),
    reviewCandidates: async () => candidateResult(),
    reviewCorrectCandidate: async (_id, targetRevision, key, patch) => { assert.equal(targetRevision, revision.id); assert.equal(patch.expectedRunId, candidateRun); writes.push({ kind: 'correctCandidate', targetRevision, key, patch: copy(patch) }); if (correctionBarrier) await correctionBarrier.promise; candidateRun = 'TEST-corrected-run'; Object.assign(candidate, { value: patch.value, sourceQuote: patch.sourceQuote, graphRevisionId: targetRevision, extractionRunId: candidateRun }); return candidateResult() },
    reviewSave: async (_id, proposed) => { writes.push('reviewSave'); revision = { ...revision, id: `TEST-draft-${++number}`, parentRevisionId: revision.id, graph: copy(proposed) }; return copy(revision) },
    reviewConfirm: async () => { writes.push('confirm'); throw Error('Navigation must not confirm') },
    updateVariable: async (_id, _key, patch) => { writes.push(copy(patch)); if (strictCandidateRevision && candidate.graphRevisionId !== revision.id) throw Error('GRAPH_REVISION_CONFLICT: Candidate belongs to another graph revision; identify again.'); adoptedCount++; return { ...variable(), value: candidate.value, confirmed: true } },
    automationStart: async () => { modelCalls.push('automation'); throw Error('Navigation must not start automation') },
    reviewExtract: async () => { modelCalls.push('extract'); throw Error('Navigation must not extract') },
    generate: async () => { modelCalls.push('generate'); throw Error('Navigation must not generate') }
  }
  const Modal = { props: ['open'], setup: (props, { slots }) => () => props.open ? vue.h('section', slots.default?.()) : null }
  const loaded = loadVue(path.join(__dirname, '../src/views/DraftingView.vue'), { boundaries: {
    '@/api': { draftingApi: api, setLlmProfileResolver() {} }, pinia: piniaRuntime, 'vue-i18n': require('vue-i18n'),
    '@/api/source-locations': { reviewSourceBindings: async () => { throw Error('TEST location boundary') } },
    '@/components/AppModal.vue': { default: Modal }, './AppModal.vue': { default: Modal },
    '@/components/DraftingDocumentWorkspace.vue': { default: { render: () => null } },
    '@/components/DraftingTemplatePreview.vue': { default: { render: () => null } },
    './DraftingPdfPreview.vue': { default: { render: () => null } }
  }, globals: { structuredClone, Blob, URL, crypto: require('node:crypto').webcrypto, localStorage: { getItem: () => 'en', setItem() {} }, window: { addEventListener() {}, removeEventListener() {} }, document: { documentElement: {}, getElementById() { return null } } } })
  const pinia = piniaRuntime.createPinia(); piniaRuntime.setActivePinia(pinia)
  const store = loaded.loadLocal(path.join(__dirname, '../src/stores/app.ts')).useAppStore(); store.switchProject('TEST-P1')
  const host = renderer(), app = host.createApp(loaded.default); app.use(pinia); app.use(loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n); app.mount(host.root)
  const control = (attr, value) => find(host.root, node => value === undefined ? node.props?.[attr] !== undefined : node.props?.[attr] === value)
  const button = title => find(host.root, node => node.tag === 'button' && text(node).trim() === title)
  const h = { get root() { return host.root }, store, writes, modelCalls, candidate, control, button,
    get reads() { return catalogReads }, get adoptions() { return adoptedCount }, failRead(value) { failCatalog = value }, pause() { barrier = deferred(); return barrier },
    pauseCorrection() { correctionBarrier = deferred(); return correctionBarrier },
    cached() { return loaded.loadLocal(path.join(__dirname, '../src/drafting/state.ts')).projectDrafts.get('TEST-P1') },
    dispose() { app.unmount() },
    async click(attr, value) { const node = control(attr, value); assert.ok(Boolean(node), `Rendered ${attr}`); await node.props.onClick(); await settle() },
    async title(title) { const node = button(title); assert.ok(Boolean(node), `Rendered ${title}`); await node.props.onClick(); await settle() },
    async saveReview() { await h.title('2 Variables and graph'); await h.click('data-review-question', item.id); await h.click('data-review-confirm-item'); await h.click('data-review-save') }
  }
  return h
}

async function run() {
  const saved = harness()
  try {
    await settle(); await saved.saveReview()
    const before = saved.reads
    await saved.title('3 Adopt and plan')
    assert.equal(Boolean(saved.control('data-pending-graph-review')), false, 'Saving a working DRAFT then entering step 3 aligns the catalog without browser reload')
    assert.ok(Boolean(saved.control('data-partial-draft-notice')), 'The ordinary working-DRAFT adoption notice is shown')
    assert.ok(Boolean(saved.control('id', 'input-' + item.id)), 'The saved working DRAFT exposes ordinary value controls')
    assert.equal(saved.reads, before + 1, 'Only a mismatched saved revision requires a read')
    assert.deepEqual(saved.writes, ['reviewSave'], 'Entering step 3 only reads; it never confirms or adopts')
    assert.deepEqual(saved.modelCalls, [])
    await saved.title('2 Variables and graph'); await saved.title('Review candidate values and adoption plan')
    assert.equal(saved.reads, before + 1, 'The footer shares alignment and does not refetch an already aligned revision')
    await saved.title('Adopt this candidate')
    const adoption = saved.writes.find(value => typeof value === 'object')
    assert.equal(adoption.graphRevisionId, 'TEST-draft-2')
    assert.deepEqual(adoption.candidateSnapshot, { ...saved.candidate, sourceHash: null }, 'Reading a saved revision preserves original candidate provenance and extraction identity')
    console.log('PASS: real review-save and both step-3 controls align DRAFT values without reload, confirmation, model runs or candidate rewrite')
  } finally { saved.dispose() }

  const dirty = harness()
  try {
    await settle(); await dirty.title('3 Adopt and plan')
    dirty.control('id', 'input-' + item.id).props.onInput({ target: { value: 'TEST unsaved value' } }); await settle()
    await dirty.saveReview(); const reads = dirty.reads
    await dirty.title('3 Adopt and plan')
    assert.equal(dirty.reads, reads, 'Revision alignment cannot refresh over unsaved variable input')
    assert.equal(dirty.cached().values[item.id], 'TEST unsaved value', 'Unsaved input remains in its existing draft cache')
    assert.match(text(dirty.root), /Save or cancel unsaved/)
    assert.equal(dirty.button('2 Variables and graph').props['aria-current'], 'step')
    assert.deepEqual(dirty.writes, ['reviewSave'])
    console.log('PASS: unsaved value input blocks the revision refresh and retains the draft')
  } finally { dirty.dispose() }

  const form = harness()
  try {
    await settle(); await form.saveReview()
    await form.click('data-review-edit-question')
    const name = form.control('data-review-name-en')
    assert.ok(Boolean(name), 'The real variable editor is mounted')
    name.props['onUpdate:modelValue']('TEST unrecorded definition'); await settle()
    const reads = form.reads
    await form.title('3 Adopt and plan')
    assert.equal(form.reads, reads)
    assert.equal(form.control('data-review-name-en').value, 'TEST unrecorded definition')
    assert.equal(form.button('2 Variables and graph').props['aria-current'], 'step')
    console.log('PASS: an unsaved definition form blocks revision refresh and remains mounted')
  } finally { form.dispose() }

  const auxiliary = harness()
  try {
    await settle(); await auxiliary.saveReview()
    await auxiliary.click('data-review-new-variable')
    auxiliary.control('data-condition-new-name-en').props['onUpdate:modelValue']('TEST unfinished new variable'); await settle()
    const before = auxiliary.reads
    await auxiliary.title('3 Adopt and plan')
    assert.equal(auxiliary.reads, before, 'An unfinished new-variable form is included in review dirty protection')
    assert.equal(auxiliary.control('data-condition-new-name-en').value, 'TEST unfinished new variable')
    await auxiliary.click('data-condition-cancel-input')
    await auxiliary.click('data-review-catalog'); await auxiliary.click('data-catalog-tab', 'new')
    await auxiliary.click('data-picker-option', item.id)
    auxiliary.control('data-catalog-reason').props['onUpdate:modelValue']('TEST unfinished baseline reason'); await settle()
    await auxiliary.click('data-review-check-current'); await auxiliary.click('data-review-open-batch')
    await auxiliary.click('data-batch-mode', 'duplicates')
    auxiliary.control('data-batch-ack').props['onUpdate:modelValue'](true); await settle()
    const reason = auxiliary.control('data-catalog-reason'), acknowledgement = auxiliary.control('data-batch-ack')
    await auxiliary.title('3 Adopt and plan')
    assert.ok(Boolean(auxiliary.control('data-partial-draft-notice')))
    await auxiliary.title('2 Variables and graph')
    assert.equal(auxiliary.control('data-catalog-reason') === reason, true, 'Alignment retains the same mounted baseline editor')
    assert.equal(reason.value, 'TEST unfinished baseline reason')
    assert.equal(auxiliary.control('data-batch-ack') === acknowledgement, true, 'Alignment retains the same mounted batch editor')
    assert.equal(acknowledgement.checked, true)
    assert.deepEqual(auxiliary.writes, ['reviewSave'])
    console.log('PASS: new-variable edits block alignment; baseline and batch drafts remain mounted and unchanged through safe alignment')
  } finally { auxiliary.dispose() }

  const failure = harness()
  try {
    await settle(); await failure.saveReview(); failure.failRead(true)
    await failure.title('Review candidate values and adoption plan')
    assert.match(text(failure.root), /TEST saved revision read failed/)
    assert.equal(failure.button('2 Variables and graph').props['aria-current'], 'step', 'A failed read cannot enter stale adoption controls')
    assert.equal(Boolean(failure.control('data-partial-draft-notice')), false)
    assert.deepEqual(failure.writes, ['reviewSave']); assert.deepEqual(failure.modelCalls, [])
    failure.failRead(false); await failure.title('3 Adopt and plan')
    assert.ok(Boolean(failure.control('data-partial-draft-notice')), 'The same normal transition can retry after failure')
    console.log('PASS: alignment read failure stays in review and a later normal navigation can retry')
  } finally { failure.dispose() }

  const switched = harness()
  try {
    await settle(); await switched.saveReview(); const barrier = switched.pause()
    const moving = switched.title('3 Adopt and plan'); await settle()
    const controls = find(switched.root, node => node.tag === 'fieldset' && node.props?.class === 'automation-review')
    assert.equal(controls.props.disabled, true, 'Review controls are frozen during alignment read')
    switched.store.switchProject('TEST-P2'); await settle(); barrier.resolve(); await moving; await settle()
    assert.equal(switched.button('1 File identification').props['aria-current'], 'step', 'Late project-1 navigation cannot move project 2 to adoption')
    assert.deepEqual(switched.writes, ['reviewSave']); assert.deepEqual(switched.modelCalls, [])
    console.log('PASS: a project switch invalidates late revision alignment and navigation')
  } finally { switched.dispose() }
}
module.exports = { harness }
if (require.main === module) run().catch(error => { console.error(error); process.exitCode = 1 })
