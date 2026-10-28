const assert = require('node:assert/strict')
const path = require('node:path')
const vue = require('vue')
const piniaRuntime = require('pinia')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle, deferred } = require('./helpers/render-controls.cjs')
const copy = value => JSON.parse(JSON.stringify(value))
const label = title => ({ en: title, zhHans: title, zhHant: title })
const source = { id: '41', sourceDocumentId: 41, docId: 'ntt', editionId: 'NTT-docx-TEST', fileKey: 'NTT', fileName: 'TEST ONLY template.docx', format: 'docx', sourceSha256: 'a'.repeat(64), parseStatus: 'PARSED', canEdit: true, evidenceStatus: 'bound', analysisRevision: 'TEST-analysis-current' }
const correspondence = { ...source, id: '42', sourceDocumentId: 42, docId: '42', fileKey: null, sourceSha256: 'c'.repeat(64), fileName: 'TEST ONLY correspondence.docx' }
const paragraph = { id: 'p1', ordinal: 1, text: 'TEST ONLY exact template guidance', paragraphSha256: 'b'.repeat(64), sourceSha256: source.sourceSha256 }
const item = { id: 'question-1', nameZh: '测试要求', nameEn: 'TEST requirement', docIds: ['41'], type: { kind: 'text', zh: '文字' }, role: { kind: 'input', zh: '项目事实' }, status: 'pending', options: [], sourceAnchors: [{ docId: '41', sourceSha256: source.sourceSha256, text: paragraph.text, native: { paragraphId: 'p1', paragraphSha256: paragraph.paragraphSha256, start: 0, end: paragraph.text.length, expectedText: paragraph.text } }], sourceUnitIds: [], editTargets: [], relatedInputIds: [], relations: [], targets: [], evidence: [] }
const graph = { kind: 'consense-template-graph-v1', graph: { variables: [item], events: [], sourceReviews: {}, nextId: 1, blank: false, guidanceMode: 'minimum' }, sources: [source], selectedDocuments: { NTT: '41' } }
let workingDraftMode = false
const working = () => current ?? (workingDraftMode ? draft : null)
let correctionBarrier
let draft, current, count = 0, candidate, candidateRun = null, adopted = '', adoptedManual = false
const calls = [], patches = [], plans = [], corrections = []
const field = { key: item.id, kind: 'text', label: label('TEST requirement') }
const variable = () => ({ key: item.id, label: field.label, kind: 'text', value: adopted, confirmed: !!adopted, manuallyEdited: adoptedManual, candidates: candidate ? [candidate] : [], graphRevisionId: working()?.id, fieldDefinitionHash: 'definition-TEST', adoptionState: adopted ? 'adopted' : candidate ? 'suggested' : 'missing' })
const response = () => ({ runId: candidateRun, graphRevisionId: draft?.id ?? current?.id, evidenceRevision: 'evidence-TEST', variables: [{ ...variable(), value: candidate?.value ?? '', graphRevisionId: draft?.id ?? current?.id }] })
const saveRevision = proposed => ({ id: 'draft-' + ++count, status: 'DRAFT', parentRevisionId: draft?.id ?? current?.id ?? null, graphSha256: 'graph-' + count, graph: copy(proposed), sourceStale: false })
const api = {
  catalog: async () => ({ ruleVersion: 'TEST', ...(working() ? { graphRevisionId: working().id } : {}), groups: working() ? [{ id: 'reviewed', label: label('Reviewed questions'), fields: [field] }] : [] }),
  variables: async () => working() ? [variable()] : [],
  templates: async () => ['NTT', 'SCT', 'SCC'].map(key => ({ key, tag: 'ok', fileName: 'TEST ONLY ' + key + '.docx' })),
  inputs: async () => [{ id: 42, status: 'PARSED', fileName: correspondence.fileName }], documents: async () => [], extractTrace: async () => null,
  plan: async (_id, values) => { plans.push(values); return { actions: [], unresolved: workingDraftMode ? [{kind: 'InputMissing', inputKeys: ['contractTitle'], message: label('Missing identity')}] : [] } },
  reviewHead: async () => ({ currentRevisionId: current?.id ?? null, draftRevisionId: draft?.id ?? null, current: copy(current ?? null), draft: copy(draft ?? null), sources: [source, correspondence] }),
  reviewSources: async () => copy([source, correspondence]),
  automationLatest: async () => null,
  reviewRevision: async (_id, revisionId) => { assert.equal(revisionId, draft.id); return copy(draft) },
  automationStart: async (_id, binding, profile) => {
    calls.push('automation'); assert.deepEqual(copy(binding), { expectedCurrentRevisionId: null, expectedDraftRevisionId: null, sourceIds: ['41'] }); assert.ok(profile)
    draft = saveRevision(graph); candidateRun = 'run-TEST'; candidate = { value: 'Unadopted source candidate', sourceDocumentId: 42, fileName: correspondence.fileName, sourceHash: 'logical-TEST', sourceQuote: 'TEST ONLY exact evidence', reason: 'TEST ONLY candidate reason', confidence: .8, graphRevisionId: draft.id, fieldDefinitionHash: 'definition-TEST', extractionRunId: candidateRun, originalSha256: correspondence.sourceSha256 }
    return { id: 'automation-TEST', projectId: _id, status: 'COMPLETED', phase: 'READY_FOR_REVIEW', message: 'TEST task finished', currentFile: null, completedItems: 1, totalItems: 1, failedItems: 0, unresolvedItems: 1, requirementsCount: 1, nodesCount: 1, resultRevisionId: draft.id, updatedAt: '2026-10-22T10:00:00Z' }
  },
  reviewReading: async (_project, id) => ({ ...(id === '42' ? correspondence : source), paragraphs: [{ ...paragraph, sourceSha256: id === '42' ? correspondence.sourceSha256 : source.sourceSha256 }], blocks: [], guidanceNotes: [], sourceMembers: [], markerCandidates: [], groups: [], conditions: [], issues: [] }),
  reviewBootstrap: async () => { calls.push('bootstrap'); return { graph: copy(graph), sources: [source] } },
  reviewSave: async (_id, proposed, expectedCurrent, expectedDraft) => { calls.push('save'); assert.equal(expectedCurrent, current?.id ?? null); assert.equal(expectedDraft, draft?.id ?? null); draft = saveRevision(proposed); return copy(draft) },
  reviewDiscover: async (_id, revisionId, expectedCurrent, budget, profile) => { calls.push('discover'); assert.equal(revisionId, draft.id); assert.equal(expectedCurrent, null); assert.equal(budget, 2); assert.ok(profile); draft = saveRevision(draft.graph); return copy(draft) },
  reviewExtract: async (_id, revisionId, profile) => { calls.push('extract'); assert.equal(revisionId, draft.id); assert.ok(profile); candidateRun = 'run-TEST'; candidate = { value: 'Unadopted source candidate', sourceDocumentId: 42, fileName: correspondence.fileName, sourceHash: 'logical-TEST', sourceQuote: 'TEST ONLY exact evidence', reason: 'TEST ONLY candidate reason', confidence: .8, graphRevisionId: revisionId, fieldDefinitionHash: 'definition-TEST', extractionRunId: candidateRun, originalSha256: correspondence.sourceSha256 }; return response().variables },
  reviewCandidates: async () => copy(response()),
  reviewCorrectCandidate: async (...args) => { corrections.push(copy(args)); if (correctionBarrier) await correctionBarrier.promise; assert.equal(args[3].expectedRunId, candidateRun); candidate = { ...candidate, value: args[3].value, sourceQuote: args[3].sourceQuote }; candidateRun = 'corrected-run-TEST'; return copy(response()) },
  reviewConfirm: async (_id, revisionId, expectedCurrent) => { calls.push('confirm'); assert.equal(revisionId, draft.id); assert.equal(expectedCurrent, null); current = { ...draft, id: 'confirmed-revision', parentRevisionId: draft.id, status: 'CONFIRMED' }; return copy(current) },
  updateVariable: async (_id, _key, patch) => { patches.push(copy(patch)); if (patch.candidateIndex !== undefined) { assert.deepEqual(copy(patch.candidateSnapshot), copy(candidate)); adopted = candidate.value } else { adopted = patch.value; adoptedManual = true }; return variable() },
  generate: async () => { calls.push('generate'); return [] }
}
const Modal = { props: ['open'], setup: (props, { slots }) => () => props.open ? vue.h('section', slots.default?.()) : null }
const loaded = loadVue(path.join(__dirname, '../src/views/DraftingView.vue'), { boundaries: {
  '@/api/source-locations': { reviewSourceBindings: async () => { throw Error('Source location transport not provided by this fixture') } }, '@/api': { draftingApi: api, setLlmProfileResolver() {}, captureLlmSelection: () => ({profileId: 'TEST-PROFILE'}) }, pinia: piniaRuntime, 'vue-i18n': require('vue-i18n'),
  '@/components/AppModal.vue': { default: Modal }, './AppModal.vue': { default: Modal },
  '@/components/DraftingDocumentWorkspace.vue': { default: { render: () => null } },
  '@/components/DraftingTemplatePreview.vue': { default: { render: () => null } },
  './DraftingPdfPreview.vue': { default: { render: () => null } }
}, globals: { structuredClone, Blob, URL, crypto: require('node:crypto').webcrypto, localStorage: { getItem: () => 'en', setItem() {} }, window: { addEventListener() {}, removeEventListener() {} }, document: { documentElement: {}, getElementById() { return null } } } })
const pinia = piniaRuntime.createPinia(); piniaRuntime.setActivePinia(pinia)
const store = loaded.loadLocal(path.join(__dirname, '../src/stores/app.ts')).useAppStore(); store.switchProject('FLOW-TEST')
const host = renderer(), app = host.createApp(loaded.default); app.use(pinia); app.use(loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n); app.mount(host.root)
const control = (attribute, value) => find(host.root, node => value === undefined ? node.props?.[attribute] !== undefined : node.props?.[attribute] === value)
const click = async (attribute, value) => { const node = control(attribute, value); assert.ok(node, attribute); await node.props.onClick(); await settle() }
const title = async label => { const node = find(host.root, node => node.tag === 'button' && text(node).trim() === label); assert.ok(node, label); await node.props.onClick(); await settle() }
;(async () => {
  await settle(); assert.match(text(host.root), /1 File identification.*2 Variables and graph.*3 Adopt and plan.*4 Preview and export/)
  assert.doesNotMatch(text(host.root), /2 Identify|Next: identify requirements/)
  assert.deepEqual(calls, [], 'Opening merged sources never starts discovery or extraction')
  assert.equal(control('data-discovery-budget'), undefined, 'Users do not manage model batches')
  assert.ok(control('data-automation-source-id', '41'), 'The template original is available to this identification')
  assert.equal(control('data-automation-source-id', '42'), undefined, 'Project correspondence is not selected as a template original')
  assert.equal(control('data-start-automation').props.disabled, false, 'The explicit source list must finish loading before starting identification')
  await click('data-start-automation')
  assert.deepEqual(calls, ['automation']); assert.equal(patches.length, 0)
  await title('3 Adopt and plan')
  assert.match(text(host.root), /Unadopted source candidate/); assert.equal(patches.length, 0)
  await title('Correct candidate or assignment'); control('data-candidate-value').props['onUpdate:modelValue']('Human-corrected candidate'); await settle()
  assert.equal(control('data-review-confirm').props.disabled, true, 'Unfinished candidate form blocks graph confirmation')
  const form = find(host.root, node => node.tag === 'form'); correctionBarrier = deferred(); const correcting = form.props.onSubmit({ preventDefault() {} }); await settle()
  assert.equal(control('data-candidate-controls').props.disabled, true, 'A pending correction freezes the candidate value, quotation and assignment controls')
  correctionBarrier.resolve(); await correcting; await settle(); correctionBarrier = undefined
  assert.equal(corrections.length, 1); assert.equal(corrections[0][3].targetKey, item.id); assert.equal(patches.length, 0)
  await title('2 Variables and graph')
  await click('data-review-question',item.id)
  await click('data-review-confirm-item'); await click('data-review-save'); await click('data-review-confirm')
  assert.equal(calls.at(-1), 'confirm'); assert.equal(patches.length, 0, 'Confirming a template cannot adopt the candidate')
  assert.match(text(host.root), /incomplete-draft notice and outstanding-items list/)
  assert.doesNotMatch(text(host.root), /Unresolved items are recorded with the drafts/)
  await title('Adopt this candidate')
  assert.equal(patches.length, 1); assert.equal(patches[0].graphRevisionId, current.id); assert.equal(patches[0].fieldDefinitionHash, 'definition-TEST'); assert.equal(patches[0].candidateSnapshot.originalSha256, correspondence.sourceSha256)
  const valueInput = control('id', 'input-' + item.id); valueInput.props.onInput({ target: { value: 'Manual project fact' } }); await settle()
  await title('4 Preview and export'); assert.equal(calls.includes('generate'), false, 'Preview navigation cannot call a model')
  await click('data-save-input', item.id); assert.equal(patches.length, 1, 'A manual adoption requires its own reason')
  control('data-manual-note', item.id).props['onUpdate:modelValue']('Checked against project instructions'); await settle(); await click('data-save-input', item.id)
  assert.equal(patches.length, 2); assert.equal(patches[1].note, 'Checked against project instructions'); assert.equal(patches[1].value, 'Manual project fact'); assert.equal(patches[1].graphRevisionId, current.id)
  assert.ok(plans.every(values => values === undefined), 'Graph-mode plans use explicitly adopted server facts, never unsaved form patches')
  await control('data-explicit-empty', item.id).props.onChange({ target: { checked: true } }); await settle()
  assert.equal(control('data-save-input', item.id).props.disabled, false, 'An explicitly selected empty answer is a deliberate unsaved change')
  await click('data-save-input', item.id)
  assert.equal(patches[2].value, ''); assert.equal(patches[2].explicitEmpty, true); assert.equal(patches[2].note, 'Checked against project instructions')
  app.unmount()
  workingDraftMode = true; current = null; draft = saveRevision(graph); adopted = ''; adoptedManual = false
  const callsBeforePartial = calls.length, patchesBeforePartial = patches.length
  const partialApp = host.createApp(loaded.default); partialApp.use(pinia); partialApp.use(loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n); partialApp.mount(host.root)
  await settle(); await title('3 Adopt and plan')
  assert.ok(control('data-partial-draft-notice')); assert.ok(control('id', 'input-' + item.id))
  const generateButton = find(host.root, node => node.tag === 'button' && text(node).trim().includes('Generate') && !node.props.disabled)
  assert.ok(generateButton); await generateButton.props.onClick(); await settle()
  assert.deepEqual(calls.slice(callsBeforePartial), ['generate']); assert.equal(patches.length, patchesBeforePartial)
  partialApp.unmount()
  console.log('PASS: four-step automation dispatches one task, corrects candidates without adoption, confirms rules separately, adopts exact candidate snapshots with graph versions and requires a reason for manual facts')
})().catch(error => { console.error(error); process.exitCode = 1 })
