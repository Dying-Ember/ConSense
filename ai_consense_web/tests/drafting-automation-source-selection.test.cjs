const assert = require('node:assert/strict')
const path = require('node:path')
const vue = require('vue')
const piniaRuntime = require('pinia')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle, deferred } = require('./helpers/render-controls.cjs')
const copy = value => JSON.parse(JSON.stringify(value))
const source = (id, fileKey, format = 'docx') => ({ id, sourceDocumentId: Number(id), docId: fileKey.toLowerCase(), editionId: fileKey + '-' + id, fileKey, fileName: fileKey + ' original ' + id + '.' + format, format, sourceSha256: String(id).padStart(64, 'a'), evidenceStatus: 'bound', analysisRevision: 'analysis-' + id, canEdit: format === 'docx', parseStatus: 'PARSED' })
const ntt = source(101, 'NTT'), sct = source('102', 'SCT'), sccWord = source('201', 'SCC'), sccPdf = source('202', 'SCC', 'pdf'), communication = source('301', 'PROJECT')
const run = (projectId = 'A', patch = {}) => ({ id: 'task-' + projectId, projectId, status: 'RUNNING', phase: 'DISCOVERING_VARIABLES', message: 'TEST identifying selected sources', currentFile: null, completedItems: 0, totalItems: 3, failedItems: 0, unresolvedItems: 0, requirementsCount: 0, nodesCount: 0, resultRevisionId: null, updatedAt: 'TEST time', ...patch })

function harness({ sources = [ntt], initial = null, overrides = {} } = {}) {
  const calls = [], timers = new Map(), sourceLists = new Map([['A', copy(sources)], ['B', [source('901', 'SCT')]]])
  let backend = initial, timerId = 0
  const graph = { graph: { variables: [], automaticDiscovery: { sources: [{ sourceId: String(ntt.id), sourceSha256: ntt.sourceSha256 }] } }, sources: [ntt], selectedDocuments: { NTT: String(ntt.id) } }
  const api = {
    templates: async () => [{ id: 'STD-NTT', key: 'NTT', fileName: ntt.fileName, tag: 'ok' }], inputs: async () => [], catalog: async () => ({ ruleVersion: 'TEST', groups: [] }), variables: async () => [], documents: async () => [], extractTrace: async () => null, plan: async () => ({ actions: [], unresolved: [] }),
    reviewSources: async id => { calls.push(['sources', id]); return copy(sourceLists.get(id) ?? []) },
    reviewHead: async id => { calls.push(['head', id]); return { currentRevisionId: 'confirmed-' + id, draftRevisionId: 'draft-' + id, current: null, draft: { id: 'draft-' + id, graph: copy(graph), sourceStale: false }, sources: copy(sourceLists.get(id) ?? []) } },
    reviewRevision: async (_id, revisionId) => ({ id: revisionId, graph: copy(graph), sourceStale: false }),
    automationLatest: async id => { calls.push(['latest', id]); return backend?.projectId === id ? copy(backend) : null },
    automationGet: async (id, taskId) => { calls.push(['get', id, taskId]); return copy(backend) },
    automationStart: async (id, body, profile) => { calls.push(['start', id, copy(body), copy(profile)]); backend = run(id); return copy(backend) },
    automationResume: async (id, taskId, body, profile) => { calls.push(['resume', id, taskId, copy(body), copy(profile)]); backend = run(id); return copy(backend) },
    replaceTemplate: async (id, key) => { calls.push(['upload', id, key]); sourceLists.set(id, [copy(ntt), copy(sct)]); return { accepted: 1, parsed: 1, failed: 0, messages: [] } },
    ...overrides
  }
  const Review = { setup(_props, { expose }) { expose({ ready: async () => {}, refresh: async () => {} }); return () => null } }
  const Empty = { render: () => null }
  const loaded = loadVue(path.join(__dirname, '../src/views/DraftingView.vue'), { boundaries: {
    '@/api': { draftingApi: api, captureLlmSelection: () => ({ profileId: 'qwen-27b' }), setLlmProfileResolver() {} }, pinia: piniaRuntime, 'vue-i18n': require('vue-i18n'),
    '@/components/DraftingSourceReview.vue': { default: Review }, '@/components/DraftingCandidateReview.vue': { default: Empty }, '@/components/DraftingAutomationResults.vue': { default: Empty }, '@/components/DraftingDocumentWorkspace.vue': { default: Empty }, '@/components/DraftingTemplatePreview.vue': { default: Empty }
  }, globals: { structuredClone, Blob, URL, crypto: require('node:crypto').webcrypto, localStorage: { getItem: () => 'en', setItem() {} }, window: { addEventListener() {}, removeEventListener() {}, setTimeout(callback, delay) { const id = ++timerId; timers.set(id, { callback, delay }); return id }, clearTimeout(id) { timers.delete(id) } }, document: { documentElement: {}, getElementById() { return null } },
    setTimeout(callback, delay) { const id = ++timerId; timers.set(id, { callback, delay }); return id }, clearTimeout(id) { timers.delete(id) }
  } })
  const pinia = piniaRuntime.createPinia(); piniaRuntime.setActivePinia(pinia)
  const store = loaded.loadLocal(path.join(__dirname, '../src/stores/app.ts')).useAppStore(); store.switchProject('A')
  const host = renderer(), app = host.createApp(loaded.default); app.use(pinia); app.use(loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n); app.mount(host.root)
  const control = (attribute, value) => find(host.root, node => value === undefined ? node.props?.[attribute] !== undefined : node.props?.[attribute] === value)
  return { root: host.root, calls, store, sourceLists, control, setRun(value) { backend = value },
    async choose(id, checked) { const checkbox = control('data-automation-source-id', String(id)); assert.ok(checkbox, 'Source ' + id); await checkbox.props.onChange({ target: { checked } }); await settle() },
    async click(attribute) { const button = control(attribute); assert.ok(button, attribute); assert.equal(!!button.props.disabled, false, attribute + ' enabled'); await button.props.onClick(); await settle() },
    async step(label) { const button = find(host.root, node => node.tag === 'button' && text(node) === label); assert.ok(button, label); await button.props.onClick(); await settle() },
    async poll() { const entry = [...timers].find(([, timer]) => timer.delay === 2000); assert.ok(entry); timers.delete(entry[0]); entry[1].callback(); await settle() },
    dispose() { app.unmount() }
  }
}

;(async () => {
  const selected = harness({ sources: [ntt, sccWord, sccPdf, communication] }); await settle()
  assert.equal(selected.calls.filter(call => call[0] === 'sources').length, 1, 'Opening a project loads actual sources once')
  for (const id of [ntt.id, sccWord.id, sccPdf.id]) assert.equal(selected.control('data-automation-source-id', String(id)).props.checked, true, 'First-seen template originals default to selected')
  assert.equal(selected.control('data-automation-source-id', 'STD-NTT'), undefined, 'Template catalogue placeholders are never source IDs')
  assert.equal(selected.control('data-automation-source-id', communication.id), undefined, 'Project correspondence is not a template identification choice')
  assert.match(text(selected.control('data-automation-source-selection')), /SCC original 201.docx.*DOCX.*SCC original 202.pdf.*PDF/s)
  assert.ok(selected.control('data-automation-source-record', String(ntt.id)))
  assert.equal(selected.control('data-automation-source-record', sccWord.id), undefined, 'An upload alone does not invent a saved identification badge')
  assert.equal(selected.calls.filter(call => call[0] === 'head').length, 1, 'Badges reuse the existing head read')
  await selected.choose(ntt.id, false); await selected.choose(sccPdf.id, false)
  await selected.click('data-start-automation')
  assert.deepEqual(selected.calls.find(call => call[0] === 'start'), ['start', 'A', { expectedCurrentRevisionId: 'confirmed-A', expectedDraftRevisionId: 'draft-A', sourceIds: ['201'] }, { profileId: 'qwen-27b' }])
  await selected.choose(sccWord.id, false)
  assert.equal(selected.control('data-automation-source-id', sccWord.id).props.checked, true, 'An active run cannot change the visible selection')
  await selected.poll(); await selected.poll()
  assert.equal(selected.calls.filter(call => call[0] === 'sources').length, 1, 'Progress polling does not reload source choices')
  selected.setRun(run('A', { status: 'PARTIAL', phase: 'READY_FOR_REVIEW', resultRevisionId: 'result-A', updatedAt: 'TEST later' })); await selected.poll(); await settle()
  assert.equal(selected.calls.filter(call => call[0] === 'sources').length, 1, 'Refreshing saved identification results does not reload source choices')
  await selected.choose(sccWord.id, false)
  assert.equal(selected.control('data-start-automation').props.disabled, true)
  await selected.control('data-start-automation').props.onClick(); await settle()
  assert.equal(selected.calls.filter(call => call[0] === 'start').length, 1, 'An empty selection cannot dispatch even through the handler')
  selected.dispose()

  const retained = harness(); await settle(); await retained.choose(ntt.id, false)
  const upload = find(retained.root, node => node.props?.id === 'template-upload-SCT')
  await upload.props.onChange({ target: { files: [{ name: sct.fileName }], value: 'TEST filename' } }); await settle()
  assert.equal(retained.calls.filter(call => call[0] === 'upload').length, 1, 'The real upload handler reaches its upload boundary')
  assert.equal(find(retained.root, node => node.props?.class === 'flow-error'), undefined, 'The upload and notification complete without a swallowed page error')
  assert.equal(retained.calls.filter(call => call[0] === 'sources').length, 2, 'Template upload refreshes actual sources exactly once')
  assert.equal(retained.control('data-automation-source-id', String(ntt.id)).props.checked, false, 'Uploading SCT does not reselect the existing NTT')
  assert.equal(retained.control('data-automation-source-id', sct.id).props.checked, true, 'The new actual source defaults to selected')
  await retained.step('2 Variables and graph'); await retained.step('1 File identification')
  assert.equal(retained.calls.filter(call => call[0] === 'sources').length, 3, 'Reopening identification refreshes the source list once')
  assert.equal(retained.control('data-automation-source-id', String(ntt.id)).props.checked, false)
  await retained.choose(sct.id, false)
  retained.store.switchProject('B'); await settle()
  assert.equal(retained.control('data-automation-source-id', '901').props.checked, true)
  assert.equal(retained.control('data-automation-source-id', String(ntt.id)), undefined)
  retained.store.switchProject('A'); await settle()
  assert.equal(retained.control('data-automation-source-id', String(ntt.id)).props.checked, false)
  assert.equal(retained.control('data-automation-source-id', sct.id).props.checked, false, 'Per-project selection, including empty selection, survives a project round trip')
  retained.dispose()

  const beforeUpload = deferred(), afterUpload = deferred()
  let uploadSourceReads = 0
  const uploadRace = harness({ overrides: { reviewSources: () => { uploadSourceReads++; return uploadSourceReads === 1 ? Promise.resolve([copy(ntt)]) : uploadSourceReads === 2 ? beforeUpload.promise : afterUpload.promise } } }); await settle()
  await uploadRace.choose(ntt.id, false)
  await uploadRace.step('2 Variables and graph'); await uploadRace.step('1 File identification')
  assert.equal(uploadSourceReads, 2, 'The pre-upload source read is in flight')
  const pendingUpload = find(uploadRace.root, node => node.props?.id === 'template-upload-SCT')
  await pendingUpload.props.onChange({ target: { files: [{ name: sct.fileName }], value: 'TEST filename' } }); await settle()
  assert.equal(uploadRace.calls.filter(call => call[0] === 'upload').length, 1)
  assert.equal(find(uploadRace.root, node => node.props?.class === 'flow-error'), undefined)
  assert.equal(uploadSourceReads, 2, 'Upload completion queues one refresh instead of opening a concurrent source read')
  assert.equal(uploadRace.control('data-start-automation').props.disabled, true)
  beforeUpload.resolve([copy(ntt), source('103', 'SCT')]); await settle()
  assert.equal(uploadSourceReads, 3, 'The stale completion starts the required post-upload source read')
  assert.equal(uploadRace.control('data-automation-source-id', '103'), undefined, 'A superseded source response is not published')
  assert.match(text(uploadRace.root), /Loading source choices/)
  assert.equal(uploadRace.control('data-start-automation').props.disabled, true, 'The start gate stays closed between the stale response and the refreshed response')
  await uploadRace.control('data-start-automation').props.onClick(); await settle()
  assert.equal(uploadRace.calls.filter(call => call[0] === 'start').length, 0)
  afterUpload.resolve([copy(ntt), copy(sct)]); await settle()
  assert.equal(uploadRace.control('data-automation-source-id', String(ntt.id)).props.checked, false, 'Queued refresh preserves the cancelled NTT')
  assert.equal(uploadRace.control('data-automation-source-id', sct.id).props.checked, true, 'The post-upload SCT is offered without reopening the page')
  assert.equal(uploadRace.control('data-start-automation').props.disabled, false)
  await uploadRace.click('data-start-automation'); await uploadRace.poll(); await uploadRace.poll()
  assert.deepEqual(uploadRace.calls.find(call => call[0] === 'start')[2].sourceIds, [sct.id])
  assert.equal(uploadSourceReads, 3, 'The queued refresh is one-shot; later run polls do not read sources')
  uploadRace.dispose()

  const pending = deferred(), waiting = harness({ overrides: { reviewSources: () => pending.promise } }); await settle()
  assert.match(text(waiting.root), /Loading source choices/)
  assert.equal(waiting.control('data-start-automation').props.disabled, true)
  await waiting.control('data-start-automation').props.onClick(); await settle()
  assert.equal(waiting.calls.filter(call => call[0] === 'start').length, 0, 'Source loading cannot dispatch an unbounded new task')
  pending.resolve([copy(ntt)]); await settle()
  assert.equal(waiting.control('data-start-automation').props.disabled, false)
  waiting.dispose()

  let sourceReads = 0
  const failed = harness({ overrides: { reviewSources: async () => { if (++sourceReads === 1) throw Error('TEST source read failed'); return [copy(ntt)] } } }); await settle()
  assert.match(text(failed.root), /TEST source read failed/)
  assert.equal(failed.control('data-start-automation').props.disabled, true)
  await failed.control('data-start-automation').props.onClick(); await settle()
  assert.equal(failed.calls.filter(call => call[0] === 'start').length, 0, 'Uploaded template catalogue rows cannot bypass a failed source read')
  await failed.click('data-reload-automation-sources')
  assert.equal(sourceReads, 2); assert.equal(failed.control('data-start-automation').props.disabled, false)
  failed.dispose()

  const oldSources = deferred(), race = harness({ overrides: { reviewSources: id => id === 'A' ? oldSources.promise : Promise.resolve([source('901', 'SCT')]) } }); await settle()
  race.store.switchProject('B'); await settle()
  oldSources.resolve([copy(ntt)]); await settle()
  assert.equal(race.control('data-automation-source-id', String(ntt.id)), undefined, 'Late source results cannot overwrite the new project')
  assert.equal(race.control('data-automation-source-id', '901').props.checked, true)
  await race.click('data-start-automation')
  assert.deepEqual(race.calls.find(call => call[0] === 'start')[2].sourceIds, ['901'])
  race.dispose()

  const resume = harness({ initial: run('A', { status: 'INTERRUPTED' }) }); await settle()
  await resume.choose(ntt.id, false); await resume.step('2 Variables and graph')
  assert.match(text(resume.control('data-automation-source-selection')), /Resuming always uses the original task/)
  await resume.click('data-resume-automation')
  assert.deepEqual(resume.calls.find(call => call[0] === 'resume'), ['resume', 'A', 'task-A', { expectedCurrentRevisionId: 'confirmed-A', expectedDraftRevisionId: 'draft-A' }, { profileId: 'qwen-27b' }], 'Resume passes only the head binding and cannot change the frozen source set')
  assert.equal(resume.calls.filter(call => call[0] === 'start').length, 0)
  assert.equal(resume.calls.filter(call => call[0] === 'sources').length, 1)
  resume.dispose()
  console.log('PASS: actual source selection preserves exclusions, bounds new runs, keeps resume frozen and stays outside polling')
})().catch(error => { console.error(error); process.exitCode = 1 })
