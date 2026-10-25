/* Mounted real DraftingView controls; HTTP and the heavy document reader are declared boundaries. */
const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const { JSDOM } = require('jsdom')
const window = new JSDOM('<main></main>').window
for (const key of ['window', 'document', 'Element', 'HTMLElement', 'SVGElement', 'Node', 'Document', 'ShadowRoot']) globalThis[key] = key === 'window' ? window : window[key]
const vue = require('vue'), piniaRuntime = require('pinia')
const { loadVue } = require('./helpers/load-vue.cjs')
const { settle, deferred } = require('./helpers/render-controls.cjs')
const copy = value => JSON.parse(JSON.stringify(value))
const saved = (file = 'SCC', revision = 'saved-1') => ({ fileKey: file, title: file, content: 'Exact retained manual body.', generated: true, stale: false, sourceSha256: 'a'.repeat(64), revisionId: revision, docxSha256: revision.padEnd(64, '0'), blocks: [{ id: `source-${file}`, text: 'Exact retained manual body.', textHash: 'saved-body-hash', paragraphOrdinal: 1, editable: true }] })
const passed = doc => ({ document: { ...copy(doc), revisionId: 'saved-2', docxSha256: 'b'.repeat(64) }, updated: true, tocFinalization: { status: 'PASS', headingFlowStatus: 'UNKNOWN', headingFlowReason: 'SYNTHETIC retained empty endpoint' } })

async function harness(options = {}) {
  const initial = { ...saved(), ...options.document }, calls = [], host = document.createElement('div'); document.body.append(host)
  let response = options.response ?? passed(initial), emitReview, emitCandidate
  const docs = new Map([['TEST-TOC-A', ['NTT', 'SCT'].map(file => saved(file)).concat(copy(initial))], ['TEST-TOC-B', [saved('SCC', 'other-project')]]])
  const Review = { emits: ['dirty'], setup(_, { emit, expose }) { emitReview = value => emit('dirty', value); expose({ ready: async () => {}, refresh: async () => {} }); return () => vue.h('span', 'TEST source review boundary') } }
  const Candidate = { emits: ['dirty'], setup(_, { emit }) { emitCandidate = value => emit('dirty', value); return () => vue.h('span', { 'data-test-candidate': '' }, 'TEST candidate boundary') } }
  const Workspace = { props: ['document', 'dirty'], emits: ['input', 'file'], setup(props, { slots }) { return () => vue.h('article', { 'data-test-reading-revision': props.document?.revisionId, 'data-test-reading-hash': props.document?.docxSha256 }, slots.default?.()) } }
  const api = {
    catalog: async () => ({ ruleVersion: 'TEST', ...(options.graph ? { graphRevisionId: 'confirmed-test' } : {}), groups: [{ id: 'identity', label: { en: 'Identity' }, fields: [{ key: 'projectName', kind: 'text', label: { en: 'Project name' } }] }] }),
    variables: async () => [{ key: 'projectName', value: 'TEST retained adopted input', confirmed: true }], templates: async () => ['NTT', 'SCT', 'SCC'].map(key => ({ key, tag: 'ok' })), inputs: async () => [], extractTrace: async () => null,
    documents: async id => copy(docs.get(id)), plan: async () => ({ actions: [], unresolved: [] }),
    reviewHead: async () => ({ currentRevisionId: 'confirmed-test', draftRevisionId: null, current: null, draft: null, sources: [] }),
    automationLatest: async () => options.automation ? { id: 'TEST-run', projectId: 'TEST-TOC-A', status: 'RUNNING', phase: 'PREPARING_SOURCES', updatedAt: 'TEST' } : null,
    refreshToc: async (...args) => { calls.push(['refreshToc', ...copy(args)]); return typeof response === 'function' ? response(...args) : await response },
    exportDocument: async (...args) => { calls.push(['exportDocument', ...args]) },
    generate: async () => { throw Error('TOC refresh must not generate') }, updateDocument: async () => { throw Error('TOC refresh must not save body edits') },
    saveVariable: async () => { throw Error('TOC refresh must not adopt values') }, extract: async () => { throw Error('TOC refresh must not identify variables') }
  }
  const loaded = loadVue(path.join(__dirname, '../src/views/DraftingView.vue'), { boundaries: {
    '@/api': { draftingApi: api, setLlmProfileResolver() {}, captureLlmSelection: () => ({ profileId: null }) }, pinia: piniaRuntime, 'vue-i18n': require('vue-i18n'),
    '@/components/DraftingSourceReview.vue': { default: Review }, '@/components/DraftingCandidateReview.vue': { default: Candidate }, '@/components/DraftingDocumentWorkspace.vue': { default: Workspace },
    '@/components/DraftingTemplatePreview.vue': { default: { render: () => null } }
  }, globals: { window, document, Element, HTMLElement, Node, Blob, structuredClone, crypto: require('node:crypto').webcrypto, localStorage: { getItem: () => 'en', setItem() {} } } })
  const pinia = piniaRuntime.createPinia(); piniaRuntime.setActivePinia(pinia)
  const store = loaded.loadLocal(path.join(__dirname, '../src/stores/app.ts')).useAppStore(); store.switchProject('TEST-TOC-A')
  const app = vue.createApp(loaded.default); app.use(pinia); app.use(loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n); app.mount(host); await settle()
  const button = title => [...host.querySelectorAll('button')].find(node => node.textContent.trim() === title)
  async function click(title) { const control = button(title); assert.ok(control, title); assert.equal(control.disabled, false, `${title} is enabled`); control.click(); await settle() }
  async function preview() { await click('4 Preview and export'); await click('SCC') }
  const cache = loaded.loadLocal(path.join(__dirname, '../src/drafting/state.ts')).projectDrafts
  return { host, calls, docs, store, cache, button, click, preview, refresh: () => host.querySelector('[data-refresh-toc]'), setResponse(value) { response = value }, reviewDirty(value) { assert.ok(emitReview); emitReview(value) }, candidateDirty(value) { assert.ok(emitCandidate); emitCandidate(value) }, dispose() { app.unmount(); host.remove() } }
}

test('API refresh posts only the exact saved head with no model selection', async () => {
  const calls = [], loaded = loadVue(path.join(__dirname, '../src/api/index.ts'), { boundaries: { './client': { api: { post: async (...args) => { calls.push(args); return 'TEST response' } } } } })
  const head = { revisionId: 'revision', docxSha256: 'c'.repeat(64) }
  assert.equal(await loaded.draftingApi.refreshToc('project', 'SCC', head), 'TEST response')
  assert.deepEqual(copy(calls), [['/drafting/project/documents/SCC/refresh-toc', head, { timeout: 300000, llmSelection: { profileId: null }, reportError: false }]])
})

test('actual button preserves manual body and adopted values, rebinds the saved reader and both exports, and retains heading UNKNOWN', async () => {
  const gate = deferred(), h = await harness({ response: gate.promise })
  try {
    await h.preview(); assert.equal(h.refresh().disabled, false); assert.equal(h.calls.length, 0)
    h.refresh().click(); await settle(); assert.equal(h.refresh().disabled, true); assert.equal(h.button('SCT').disabled, true); assert.equal(h.button('Export Word').disabled, true)
    gate.resolve(passed(saved())); await settle()
    assert.deepEqual(h.calls[0], ['refreshToc', 'TEST-TOC-A', 'SCC', { revisionId: 'saved-1', docxSha256: saved().docxSha256 }])
    assert.equal(h.host.querySelector('[data-test-reading-revision]').dataset.testReadingRevision, 'saved-2')
    assert.equal(h.host.querySelector('[data-test-reading-hash]').dataset.testReadingHash, 'b'.repeat(64))
    assert.match(h.host.querySelector('.paragraph-text').textContent, /Exact retained manual body/)
    assert.match(h.host.querySelector('[data-toc-refresh-status]').textContent, /updated.*Pagination between clause headings and body text still requires review/)
    assert.ok(h.button('SCC').classList.contains('primary'))
    await h.click('Export Word'); await h.click('Export PDF')
    assert.deepEqual(h.calls.slice(1), [['exportDocument', 'TEST-TOC-A', 'SCC', 'docx', 'saved-2'], ['exportDocument', 'TEST-TOC-A', 'SCC', 'pdf', 'saved-2']])
    assert.equal(h.cache.get('TEST-TOC-A').values.projectName, 'TEST retained adopted input')
  } finally { h.dispose() }
})

test('body, input and source-review dirty states disable refresh without posting or discarding the edit', async () => {
  const h = await harness()
  try {
    await h.click('2 Variables and graph'); await h.preview(); h.reviewDirty(true); await settle(); assert.equal(h.refresh().disabled, true); h.refresh().click(); assert.equal(h.calls.length, 0); h.reviewDirty(false); await settle()
    await h.click('Edit paragraph'); const textarea = h.host.querySelector('#body-p-1'); textarea.value = 'Pending manual body retained'; textarea.dispatchEvent(new window.Event('input', { bubbles: true })); await settle(); assert.ok(h.refresh(), 'The real View remains in preview while typing body text'); assert.equal(h.refresh().disabled, true); h.refresh().click(); assert.equal(h.calls.length, 0); assert.equal(textarea.value, 'Pending manual body retained')
    await h.click('Restore original'); await h.click('3 Adopt and plan'); const input = h.host.querySelector('#input-projectName'); assert.ok(input); input.value = 'Pending input retained'; input.dispatchEvent(new window.Event('input', { bubbles: true })); await settle(); await h.preview(); assert.equal(h.refresh().disabled, true); assert.equal(h.calls.length, 0)
  } finally { h.dispose() }
})

test('candidate edits and active identification cannot expose an enabled refresh route', async () => {
  const candidate = await harness({ graph: true })
  try { await candidate.click('3 Adopt and plan'); candidate.candidateDirty(true); await settle(); assert.equal(candidate.button('4 Preview and export').disabled, true); assert.equal(candidate.refresh(), null); assert.equal(candidate.calls.length, 0) } finally { candidate.dispose() }
  const automation = await harness({ automation: true })
  try { assert.equal(automation.button('4 Preview and export').disabled, true); assert.equal(automation.refresh(), null); assert.equal(automation.calls.length, 0) } finally { automation.dispose() }
})

test('stale or missing saved revision/hash disables the actual refresh button', async () => {
  for (const patch of [{ stale: true }, { revisionId: undefined }, { docxSha256: undefined }]) {
    const h = await harness({ document: patch })
    try { await h.preview(); assert.equal(h.refresh().disabled, true); h.refresh().click(); await settle(); assert.equal(h.calls.length, 0) } finally { h.dispose() }
  }
})

test('UNKNOWN and transport failures keep the old reader and saved body with a visible reason', async () => {
  for (const response of [{ document: saved(), updated: false, tocFinalization: { status: 'UNKNOWN', reason: 'SYNTHETIC footer could not be verified' } }, () => { throw Error('SYNTHETIC revision conflict') }]) {
    const h = await harness({ response })
    try { await h.preview(); h.refresh().click(); await settle(); assert.equal(h.host.querySelector('[data-test-reading-revision]').dataset.testReadingRevision, 'saved-1'); assert.match(h.host.textContent, /SYNTHETIC footer could not be verified|SYNTHETIC revision conflict/); assert.equal(h.host.querySelector('[data-toc-refresh-status]'), null); assert.match(h.host.querySelector('.paragraph-text').textContent, /Exact retained manual body/); assert.equal(h.calls.length, 1) } finally { h.dispose() }
  }
})

test('a late project response cannot replace the current project or its manual body', async () => {
  const gate = deferred(), h = await harness({ response: gate.promise })
  try { await h.preview(); h.refresh().click(); await settle(); h.store.switchProject('TEST-TOC-B'); await settle(); gate.resolve(passed(saved())); await settle(); await h.preview(); assert.equal(h.host.querySelector('[data-test-reading-revision]').dataset.testReadingRevision, 'other-project'); assert.equal(h.host.querySelector('[data-toc-refresh-status]'), null); assert.equal(h.calls.length, 1) } finally { h.dispose() }
})

test('an idempotent PASS keeps the same saved head and reports verification without inventing an update', async () => {
  const h = await harness({ response: { document: saved(), updated: false, tocFinalization: { status: 'PASS', headingFlowStatus: 'UNKNOWN' } } })
  try { await h.preview(); h.refresh().click(); await settle(); assert.equal(h.host.querySelector('[data-test-reading-revision]').dataset.testReadingRevision, 'saved-1'); assert.match(h.host.querySelector('[data-toc-refresh-status]').textContent, /verified; no change was needed.*still requires review/); assert.equal(h.calls.length, 1) } finally { h.dispose() }
})

test('PASS responses with a foreign source or an inconsistent unchanged head are rejected', async () => {
  const foreign = passed(saved()); foreign.document.sourceSha256 = 'f'.repeat(64)
  const inconsistent = passed(saved()); inconsistent.updated = false
  for (const response of [foreign, inconsistent]) {
    const h = await harness({ response })
    try { await h.preview(); h.refresh().click(); await settle(); assert.equal(h.host.querySelector('[data-test-reading-revision]').dataset.testReadingRevision, 'saved-1'); assert.match(h.host.textContent, /refreshed document identity does not match/); assert.equal(h.host.querySelector('[data-toc-refresh-status]'), null); assert.match(h.host.querySelector('.paragraph-text').textContent, /Exact retained manual body/) } finally { h.dispose() }
  }
})

test('a queued body input arriving during refresh cannot be discarded by the late result', async () => {
  const gate = deferred(), h = await harness({ response: gate.promise })
  try {
    await h.preview(); await h.click('Edit paragraph'); const textarea = h.host.querySelector('#body-p-1'); assert.ok(textarea)
    h.refresh().click(); await settle(); assert.equal(textarea.disabled, true)
    textarea.value = 'Queued manual body must survive'; textarea.dispatchEvent(new window.Event('input', { bubbles: true })); await settle(); gate.resolve(passed(saved())); await settle()
    assert.equal(h.host.querySelector('[data-test-reading-revision]').dataset.testReadingRevision, 'saved-1'); assert.equal(textarea.value, 'Queued manual body must survive'); assert.match(h.host.textContent, /document or pending edits changed/); assert.equal(h.host.querySelector('[data-toc-refresh-status]'), null)
  } finally { h.dispose() }
})
