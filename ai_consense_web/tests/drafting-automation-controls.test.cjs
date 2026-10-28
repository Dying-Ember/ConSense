const assert = require('node:assert/strict')
const path = require('node:path')
const vue = require('vue')
const piniaRuntime = require('pinia')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle, deferred } = require('./helpers/render-controls.cjs')
const copy = value => JSON.parse(JSON.stringify(value))
const run = (projectId = 'A', patch = {}) => ({ id: 'task-' + projectId, projectId, status: 'RUNNING', phase: 'PREPARING_SOURCES', message: 'Checking exact TEST source revisions', currentFile: 'NTT TEST.docx', completedItems: 0, totalItems: 12, failedItems: 0, unresolvedItems: 0, requirementsCount: 0, nodesCount: 0, resultRevisionId: null, updatedAt: '2026-10-22T00:00:00Z', ...patch })
function harness(initial = null, overrides = {}) {
  let backend = initial, profile = 'qwen-27b', nextTimer = 0, reviewRefreshes = 0
  const timers = new Map(), calls = [], signals = []
  const api = {
    catalog: async () => ({ ruleVersion: 'TEST', groups: [] }), variables: async () => [],
    templates: async () => [{ key: 'NTT', tag: 'ok', fileName: 'NTT TEST.docx' }], inputs: async () => [], documents: async () => [], extractTrace: async () => null,
    plan: async () => ({ actions: [], unresolved: [] }),
    reviewHead: async () => ({ currentRevisionId: 'confirmed-TEST', draftRevisionId: 'draft-TEST', current: null, draft: null, sources: [] }),
    reviewRevision: async (_id, revisionId) => ({ id: revisionId, graph: { graph: { variables: [] }, sources: [] } }),
    automationLatest: async (id, signal) => { calls.push(['latest', id]); signals.push(signal); return backend?.projectId === id ? copy(backend) : null },
    automationGet: async (id, taskId, signal) => { calls.push(['get', id, taskId]); signals.push(signal); return copy(backend) },
    automationStart: async (id, binding, selection) => { calls.push(['start', id, copy(binding), copy(selection)]); backend = run(id); return copy(backend) },
    automationResume: async (id, taskId, binding, selection) => { calls.push(['resume', id, taskId, copy(binding), copy(selection)]); backend = { ...backend, status: 'RUNNING', phase: 'DISCOVERING_VARIABLES' }; return copy(backend) },
    automationCancel: async (id, taskId) => { calls.push(['cancel', id, taskId]); backend = { ...backend, status: 'CANCELLED', message: 'TEST task stopped' }; return copy(backend) },
    automationDiagnostics: async (id, taskId, onPrepared) => { calls.push(['diagnostics', id, taskId]); onPrepared({ url: 'blob:diagnostics-test', filename: 'diagnostics.json' }) },
    reviewExtract: async (id, revisionId, selection) => { calls.push(['extract', id, revisionId, copy(selection)]); return [] },
    ...overrides
  }
  const Review = { emits: ['dirty'], setup(_props, { expose, emit }) { expose({ ready: async () => {}, refresh: async () => { reviewRefreshes++ } }); return () => vue.h('button', { 'data-dirty-review': '', onClick: () => emit('dirty', true) }, 'TEST unsaved review') } }
  const loaded = loadVue(path.join(__dirname, '../src/views/DraftingView.vue'), { boundaries: {
    '@/api': { draftingApi: api, captureLlmSelection: () => ({ profileId: profile }), setLlmProfileResolver() {} }, pinia: piniaRuntime, 'vue-i18n': require('vue-i18n'),
    '@/components/DraftingSourceReview.vue': { default: Review }, '@/components/DraftingCandidateReview.vue': { default: { props: ['revisionId'], emits: ['adoptComposite'], setup: (props, { emit }) => () => vue.h('div', { 'data-candidate-revision': props.revisionId }, [vue.h('button', { 'data-test-adopt-composite': '', onClick: () => emit('adoptComposite', 'bill', { runId: 'run-1', memberIndexes: [0, 1], mergeHash: 'proof', value: '[]', partial: false }) })]) } },
    '@/components/DraftingDocumentWorkspace.vue': { default: { render: () => null } }, '@/components/DraftingTemplatePreview.vue': { default: { render: () => null } }
  }, globals: { structuredClone, Blob, URL, crypto: require('node:crypto').webcrypto,
    localStorage: { getItem: () => 'en', setItem() {} }, window: { addEventListener() {}, removeEventListener() {} }, document: { documentElement: {}, getElementById() { return null } },
    setTimeout(callback, delay) { const id = ++nextTimer; timers.set(id, { callback, delay }); return id }, clearTimeout(id) { timers.delete(id) }
  } })
  const pinia = piniaRuntime.createPinia(); piniaRuntime.setActivePinia(pinia)
  const store = loaded.loadLocal(path.join(__dirname, '../src/stores/app.ts')).useAppStore(); store.switchProject('A')
  const host = renderer(), app = host.createApp(loaded.default); app.use(pinia); app.use(loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n); app.mount(host.root)
  const control = attribute => find(host.root, node => node.props?.[attribute] !== undefined)
  return { root: host.root, calls, store, signals, control, timers,
    setRun(value) { backend = value }, setProfile(value) { profile = value }, get refreshes() { return reviewRefreshes },
    async click(attribute) { const button = control(attribute); assert.ok(button, attribute); assert.equal(!!button.props.disabled, false, attribute + ' enabled'); await button.props.onClick(); await settle() },
    async poll() { const entry = [...timers].find(([, timer]) => timer.delay === 2000); assert.ok(entry, 'Only an active task schedules a 2-second read'); timers.delete(entry[0]); entry[1].callback(); await settle() },
    dispose() { app.unmount() }
  }
}
;(async () => {
  const h = harness(); await settle()
  assert.deepEqual(h.calls, [['latest', 'A']], 'Opening the page only reads the persisted task')
  assert.doesNotMatch(text(h.root), /budget for this run|Prepare or continue template review|Start a new review from current sources/)
  await h.click('data-start-automation'); assert.equal(h.calls.filter(call => call[0] === 'start').length, 1)
  assert.deepEqual(h.calls.find(call => call[0] === 'start').slice(2), [{ expectedCurrentRevisionId: 'confirmed-TEST', expectedDraftRevisionId: 'draft-TEST' }, { profileId: 'qwen-27b' }])
  assert.match(text(h.control('data-automation-progress')), /Checking source files.*Checking exact TEST source revisions.*NTT TEST.docx.*0 \/ 12/)
  assert.equal(h.control('data-automation-historical'), undefined)
  assert.equal(h.control('data-automation-groups'), undefined, 'Legacy runs do not invent group progress')
  assert.equal(h.control('data-prepare-automation-diagnostics'), undefined, 'Active runs do not offer an inconsistent diagnostic download')
  const reviewFieldset = find(h.root, node => node.tag === 'fieldset' && node.props.class === 'automation-review'); assert.equal(reviewFieldset.props.disabled, true)
  h.setProfile('qwen-flash'); h.setRun(run('A', { phase: 'DISCOVERING_VARIABLES', message: 'TEST source item 4 is being decomposed', currentFile: 'SCT TEST.docx', completedItems: 4, requirementsCount: 7, nodesCount: 11, groupProgress: { total: 5, completed: 1, inFlight: 2, concurrency: 2, sourceTasksTotal: 12 } })); await h.poll()
  assert.match(text(h.control('data-automation-progress')), /Identifying variables and decomposing atomic capabilities.*TEST source item 4.*SCT TEST.docx.*4 \/ 12.*7.*11/)
  assert.match(text(h.control('data-automation-groups')), /Grouped processing:.*1 \/ 5.*In progress:.*2.*Concurrent group limit:.*2/)
  assert.match(text(h.control('data-automation-groups')), /Source items retain individual coverage.*not a human confirmation/)
  assert.equal(h.calls.filter(call => call[0] === 'start').length, 1, 'Progress never reposts a task when the selected model changes')
  h.setRun(run('A', { status: 'PARTIAL', phase: 'READY_FOR_REVIEW', message: 'TEST partial results retained', completedItems: 10, failedItems: 1, unresolvedItems: 2, resultRevisionId: 'result-TEST', updatedAt: '2026-10-22T00:10:00Z' })); await h.poll()
  assert.match(text(h.control('data-automation-progress')), /Partially finished.*TEST partial results retained.*Failed items:.*1.*2/)
  assert.match(text(h.control('data-automation-historical')), /do not change with later merges/)
  assert.match(text(h.control('data-automation-progress')), /Identification task record:.*10 \/ 12/)
  assert.equal([...h.timers.values()].filter(timer => timer.delay === 2000).length, 0, 'Terminal tasks stop polling')
  assert.equal(h.refreshes, 1, 'Saved result refreshes the source review once')
  assert.equal(h.control('data-resume-automation'), undefined, 'A saved partial result cannot blindly replay unknown or failed model calls')
  assert.equal(h.calls.filter(call => call[0] === 'diagnostics').length, 0, 'Polling never loads diagnostic receipts')
  await h.click('data-prepare-automation-diagnostics')
  assert.deepEqual(h.calls.at(-1), ['diagnostics', 'A', 'task-A'])
  assert.equal(h.control('data-download-automation-diagnostics').props.download, 'diagnostics.json')
  await h.click('data-start-automation'); assert.equal(h.calls.filter(call => call[0] === 'start').length, 2, 'A deliberate re-identification creates a new task')
  assert.equal(h.control('data-download-automation-diagnostics'), undefined, 'Restarting clears the old snapshot download')
  await h.click('data-cancel-automation'); assert.match(text(h.control('data-automation-progress')), /Stopped; available results are retained/)
  h.dispose(); assert.equal(h.timers.size, 0, 'Unmount cancels scheduled reads'); assert.ok(h.signals.at(-1).aborted)

  const restored = harness(run()); await settle()
  assert.ok(restored.control('data-automation-panel')); assert.deepEqual(restored.calls, [['latest', 'A']], 'Refresh restores the existing task without a model request')
  const pendingReview = harness(null); await settle()
  const adoptNav = find(pendingReview.root, node => node.tag === 'button' && text(node) === '3 Adopt and plan')
  await adoptNav.props.onClick(); await settle()
  assert.ok(pendingReview.control('data-pending-graph-review'), 'An unconfirmed graph presents its candidate review rather than the legacy adoption form')
  assert.match(text(pendingReview.root), /These candidate values belong to the graph saved in step 2/)
  assert.match(text(pendingReview.control('data-pending-graph-count')), /Current graph awaiting review/)
  const previewNav = find(pendingReview.root, node => node.tag === 'button' && text(node) === '4 Preview and export')
  await previewNav.props.onClick(); await settle()
  assert.ok(pendingReview.control('data-pending-graph-review'), 'Preview cannot generate from the legacy catalogue while a draft awaits confirmation')
  assert.match(text(pendingReview.root), /Generate documents from the adopted values and plan/)
  await pendingReview.click('data-open-business-graph')
  assert.equal(pendingReview.control('data-pending-graph-review'), undefined, 'Header opens the draft review instead of the legacy graph')
  assert.equal(pendingReview.calls.filter(call => ['start', 'resume'].includes(call[0])).length, 0, 'Reviewing existing candidates does not dispatch identification')
  pendingReview.dispose()
  const confirmedReview = harness(null, {
    catalog: async () => ({ ruleVersion: 'TEST', graphRevisionId: 'confirmed-TEST', groups: [] }),
    reviewHead: async () => ({ currentRevisionId: 'confirmed-TEST', draftRevisionId: 'draft-TEST', current: { id: 'confirmed-TEST', parentRevisionId: 'draft-TEST', graph: { selectedDocuments: {} } }, sources: [] })
  }); await settle()
  const confirmedNav = find(confirmedReview.root, node => node.tag === 'button' && text(node) === '3 Adopt and plan')
  await confirmedNav.props.onClick(); await settle()
  assert.equal(confirmedReview.control('data-pending-graph-review'), undefined, 'The retained parent draft of a confirmed revision does not block adoption')
  confirmedReview.dispose()
  const revised = harness(run('A', { status: 'PARTIAL', resultRevisionId: 'old-automation-result' }), {
    reviewHead: async () => ({ currentRevisionId: null, draftRevisionId: 'human-saved-draft', current: null, draft: { id: 'human-saved-draft', graph: { graph: { variables: [] }, selectedDocuments: {} } }, sources: [] })
  }); await settle()
  await settle(); await settle()
  const revisedNav = find(revised.root, node => node.tag === 'button' && text(node) === '3 Adopt and plan')
  await revisedNav.props.onClick(); await settle()
  assert.equal(revised.control('data-candidate-revision').props['data-candidate-revision'], 'human-saved-draft', 'Restoring a completed task must not select its old graph over a newer human draft')
  revised.dispose()
  const pending = deferred(); let staleSignal
  const race = harness(run(), { automationGet: (_id, _taskId, signal) => { staleSignal = signal; return pending.promise } }); await settle(); await race.poll()
  race.store.switchProject('B'); await settle(); assert.ok(staleSignal.aborted)
  pending.resolve(run('A', { message: 'OBSOLETE PROJECT A RESULT' })); await settle()
  assert.doesNotMatch(text(race.root), /OBSOLETE PROJECT A RESULT/)
  assert.equal(race.control('data-automation-progress'), undefined, 'Old task cannot appear in the new project')
  race.dispose(); restored.dispose()

  const interrupted = harness(run('A', { status: 'INTERRUPTED', resultRevisionId: null })); await settle()
  const interruptedNav = find(interrupted.root, node => node.tag === 'button' && text(node) === '2 Variables and graph'); await interruptedNav.props.onClick(); await settle()
  interrupted.setProfile('qwen-flash'); await interrupted.click('data-resume-automation')
  assert.deepEqual(interrupted.calls.find(call => call[0] === 'resume').slice(1), ['A', 'task-A', { expectedCurrentRevisionId: 'confirmed-TEST', expectedDraftRevisionId: 'draft-TEST' }, { profileId: 'qwen-flash' }]); interrupted.dispose()

  const dirty = harness(); await settle(); const reviewNav = find(dirty.root, node => node.tag === 'button' && text(node) === '2 Variables and graph'); await reviewNav.props.onClick(); await settle(); await dirty.click('data-dirty-review')
  assert.equal(dirty.control('data-start-automation').props.disabled, true, 'Unsaved human review blocks automatic graph changes'); assert.equal(dirty.calls.filter(call => call[0] === 'start').length, 0); dirty.dispose()
  const unconfirmed = harness(null, { reviewHead: async () => ({ currentRevisionId: null, draftRevisionId: 'unconfirmed', current: null, draft: { id: 'unconfirmed', status: 'DRAFT', definitionGraphReview:{confirmed:false,valid:false,unresolvedRelationCount:1}, graph: { graph: { variables: [] }, selectedDocuments: {} } }, sources: [] }) }); await settle()
  await find(unconfirmed.root, node => node.tag === 'button' && text(node) === '2 Variables and graph').props.onClick(); await settle()
  assert.equal(unconfirmed.control('data-extract-reviewed-candidates').props.disabled,true,'No new extraction before the entire usable graph is confirmed')
  assert.match(text(unconfirmed.root),/confirm the usable variable and relationship graph/i)
  assert.equal(unconfirmed.calls.filter(call=>call[0]==='extract').length,0);unconfirmed.dispose()
  const separate = harness(null, { reviewHead: async () => ({ currentRevisionId: null, draftRevisionId: 'reviewed-draft', current: null, draft: { id: 'reviewed-draft', status: 'DRAFT', definitionGraphReview:{confirmed:true,valid:true,unresolvedRelationCount:1}, graph: { graph: { variables: [] }, selectedDocuments: {} } }, sources: [] }) }); await settle()
  const reviewStep = find(separate.root, node => node.tag === 'button' && text(node) === '2 Variables and graph'); await reviewStep.props.onClick(); await settle()
  assert.equal(separate.calls.filter(call => call[0] === 'extract').length, 0, 'Opening a saved graph never starts project candidate extraction')
  await separate.click('data-extract-reviewed-candidates'); assert.deepEqual(separate.calls.find(call => call[0] === 'extract').slice(1), ['A', 'reviewed-draft', { profileId: 'qwen-27b' }])
  assert.equal(separate.calls.filter(call => call[0] === 'extract').length, 1, 'Only the explicit extraction action dispatches once'); separate.dispose()
  let currentBill = { key: 'bill', graphRevisionId: 'confirmed-bill', fieldDefinitionHash: 'field-hash', value: '', confirmed: false, manuallyEdited: false, reviewRequired: false, candidates: [] }; const compositeWrites = []
  const combined = harness(null, {
    catalog: async () => ({ ruleVersion: 'TEST', graphRevisionId: 'confirmed-bill', groups: [{ id: 'pricing', label: { en: 'Pricing' }, fields: [{ key: 'bill', kind: 'list', label: { en: 'Bills' }, columnFields: [] }] }] }),
    reviewHead: async () => ({ currentRevisionId: 'confirmed-bill', draftRevisionId: null, current: { id: 'confirmed-bill', status: 'CONFIRMED', graph: { graph: { variables: [] }, selectedDocuments: {} } }, draft: null, sources: [] }),
    variables: async () => [copy(currentBill)], updateVariable: async (id, key, patch) => { compositeWrites.push([id, key, copy(patch)]); currentBill = { ...currentBill, value: '[]', confirmed: true }; return copy(currentBill) }
  }); await settle(); const valuesStep = find(combined.root, node => node.tag === 'button' && text(node) === '3 Adopt and plan'); await valuesStep.props.onClick(); await settle()
  assert.equal(compositeWrites.length, 0); await combined.click('data-test-adopt-composite'); assert.deepEqual(compositeWrites[0], ['A', 'bill', { graphRevisionId: 'confirmed-bill', fieldDefinitionHash: 'field-hash', compositeBill: { runId: 'run-1', memberIndexes: [0, 1], mergeHash: 'proof', expectedValue: '', expectedConfirmed: false, expectedManuallyEdited: false, expectedReviewRequired: false } }]); combined.dispose()
  const candidate = { value: 'partial scope', fileName: 'source.docx', sourceQuote: 'partial scope', completenessStatus: 'needs_review' }
  let water = { key: 'water', graphRevisionId: 'confirmed-water', fieldDefinitionHash: 'water-hash', value: '', confirmed: false, manuallyEdited: false, reviewRequired: false, candidates: [candidate] }; const waterWrites = []
  const waterHarness = () => harness(null, {
    catalog: async () => ({ ruleVersion: 'TEST', graphRevisionId: 'confirmed-water', groups: [{ id: 'water-group', label: { en: 'Waterproofing' }, fields: [{ key: 'water', kind: 'text', label: { en: 'Water' } }] }] }),
    reviewHead: async () => ({ currentRevisionId: 'confirmed-water', draftRevisionId: null, current: { id: 'confirmed-water', status: 'CONFIRMED', graph: { graph: { variables: [] }, selectedDocuments: {} } }, draft: null, sources: [] }),
    variables: async () => [copy(water)], updateVariable: async (id, key, patch) => { waterWrites.push([id, key, copy(patch)]); water = { ...water, value: patch.value, confirmed: true, manuallyEdited: true, reviewRequired: false }; return copy(water) }
  })
  const emptyWater = waterHarness(); await settle(); await find(emptyWater.root, node => node.tag === 'button' && text(node) === '3 Adopt and plan').props.onClick(); await settle()
  const emptyField = find(emptyWater.root, node => node.props?.['data-variable'] === 'water'); assert.match(text(emptyField), /Review required/i); assert.equal(find(emptyField, node => node.props?.id === 'input-water').props.value ?? '', '', 'An incomplete candidate is not inserted into the project value')
  assert.equal(find(emptyField, node => node.tag === 'button' && text(node) === 'Adopt this candidate').props.disabled, true, 'Incomplete candidate cannot be directly adopted')
  assert.equal(waterWrites.length, 0); emptyWater.dispose()
  water = { ...water, value: 'Human checked scope', confirmed: true, manuallyEdited: true, reviewRequired: true }
  const manualWater = waterHarness(); await settle(); await find(manualWater.root, node => node.tag === 'button' && text(node) === '3 Adopt and plan').props.onClick(); await settle()
  const manualPanel = manualWater.control('data-confirm-manual-value'); assert.ok(manualPanel); const manualButton = find(manualPanel, node => node.tag === 'button'); assert.equal(manualButton.props.disabled, true, 'A reason is required')
  assert.equal(find(manualWater.root, node => node.tag === 'button' && text(node) === 'Retain this value and complete review').props.disabled, true, 'Suggestion review cannot adopt an incomplete candidate')
  manualWater.control('data-manual-note').props['onUpdate:modelValue']('Rechecked against source'); await settle(); assert.equal(manualButton.props.disabled, false); await manualButton.props.onClick(); await settle()
  assert.deepEqual(waterWrites[0], ['A', 'water', { value: 'Human checked scope', graphRevisionId: 'confirmed-water', fieldDefinitionHash: 'water-hash', note: 'Rechecked against source' }]); assert.equal(water.candidates[0].value, 'partial scope'); manualWater.dispose()
  const offline = harness(null, { automationLatest: async () => { throw Error('TEST latest status unavailable') } }); await settle()
  assert.match(text(offline.root), /TEST latest status unavailable/); assert.equal(offline.control('data-start-automation').props.disabled, true, 'Unknown current task state cannot start a duplicate task'); assert.ok(offline.control('data-reload-automation')); offline.dispose()
  const delayedDocuments = deferred(), reads = { catalog: 0, variables: 0, documents: 0, head: 0, revision: 0, plan: 0 }
  const initialResult = harness(run('A', { status: 'PARTIAL', resultRevisionId: 'old-result' }), {
    catalog: async () => { reads.catalog++; return { ruleVersion: 'TEST', groups: [{ id: 'identity', label: { en: 'Identity' }, fields: [{ key: 'projectName', kind: 'text', label: { en: 'Project name' } }] }] } },
    variables: async () => { reads.variables++; return [] },
    documents: async () => { reads.documents++; return delayedDocuments.promise },
    reviewHead: async () => { reads.head++; return { currentRevisionId: null, draftRevisionId: 'human-current', current: null, draft: { id: 'human-current', graph: { graph: { variables: [], automaticDiscovery: {} }, selectedDocuments: {} } }, sources: [] } },
    reviewRevision: async () => { reads.revision++; throw Error('Historical task graph must not be downloaded on page load') },
    plan: async () => { reads.plan++; return { actions: [], unresolved: [] } }
  }); await settle()
  assert.match(text(initialResult.root), /NTT TEST.docx/, 'The uploaded-file list must render before saved document bodies finish loading')
  assert.match(text(initialResult.root), /Files loaded; reading saved variables and documents/)
  delayedDocuments.resolve([]); await settle(); await settle()
  assert.deepEqual(reads, { catalog: 1, variables: 1, documents: 1, head: 1, revision: 0, plan: 0 }, 'Initial restored results read the current snapshot once and defer the plan outside the file step')
  const adoptedNav = find(initialResult.root, node => node.tag === 'button' && text(node) === '3 Adopt and plan')
  await adoptedNav.props.onClick(); await settle()
  assert.equal(reads.plan, 1, 'The plan is requested when the user opens the adoption step')
  assert.equal(initialResult.control('data-candidate-revision').props['data-candidate-revision'], 'human-current')
  initialResult.dispose()
  const savedGroups = harness(run('A', { status: 'PARTIAL', resultRevisionId: 'saved-result', canContinueSavedGroups: true, groupProgress: { total: 9, completed: 5, inFlight: 0, pending: 4, concurrency: 1, sourceTasksTotal: 23 } })); await settle(); await settle(); await settle()
  await find(savedGroups.root, node => node.tag === 'button' && text(node) === '2 Variables and graph').props.onClick(); await settle()
  assert.match(text(savedGroups.control('data-resume-automation')), /Continue groups not yet dispatched/)
  assert.match(text(savedGroups.control('data-resume-groups-notice')), /Failed and unknown calls are not replayed/)
  assert.equal(savedGroups.control('data-start-automation'), undefined, 'Saved continuation uses the original run, not a paid restart')
  await savedGroups.click('data-resume-automation'); assert.equal(savedGroups.calls.filter(call => call[0] === 'start').length, 0)
  assert.deepEqual(savedGroups.calls.find(call => call[0] === 'resume').slice(1, 4), ['A', 'task-A', { expectedCurrentRevisionId: 'confirmed-TEST', expectedDraftRevisionId: 'draft-TEST' }]); savedGroups.dispose()
  const pendingSave = harness(run('A', { status: 'PARTIAL', resultRevisionId: 'saved-result', canContinueSavedGroups: true, groupProgress: { total: 9, completed: 9, inFlight: 0, pending: 0, concurrency: 1, sourceTasksTotal: 23 } })); await settle(); await settle(); await settle()
  await find(pendingSave.root, node => node.tag === 'button' && text(node) === '2 Variables and graph').props.onClick(); await settle()
  assert.ok(pendingSave.control('data-resume-automation'), 'Returned continuation receipts can be saved after interruption without dispatching another group'); pendingSave.dispose()
  console.log('PASS: automated drafting uses real stage/file/counts, one start, bounded GET polling, persisted refresh, partial/resume/cancel, captured mutation profiles, dirty guards and stale-project cancellation')
})().catch(error => { console.error(error); process.exitCode = 1 })
