const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const { JSDOM } = require('jsdom')
const window = new JSDOM('<main></main>').window
for (const key of ['window', 'document', 'history', 'location', 'Element', 'HTMLElement', 'SVGElement', 'Node']) globalThis[key] = key === 'window' ? window : window[key]
const vue = require('vue'), piniaRuntime = require('pinia'), routerRuntime = require('vue-router')
const { loadVue } = require('./helpers/load-vue.cjs')
const { deferred } = require('./helpers/render-controls.cjs')
const copy = value => JSON.parse(JSON.stringify(value))
const label = value => ({ en: value, zhHans: value, zhHant: value })
const settle = async () => { for (let n = 0; n < 20; n++) { await Promise.resolve(); await vue.nextTick() } }
const adopted = JSON.stringify({ number: 'TEST-2026/1', title: 'TEST adopted project name' })
class ApiError extends Error { constructor(code, message) { super(message); this.code = code } }

async function harness({ confirmed = false } = {}) {
  const projects = ['A', 'B'].map(id => ({ id, name: label(`TEST old ${id}`), contractNo: `OLD-${id}`, titleLines: [] }))
  const values = new Map(projects.map(project => [project.id, { key: 'contractTitle', kind: 'contract', value: adopted, confirmed, reviewRequired: false, adoptionState: confirmed ? 'adopted' : 'suggested', candidates: [{ value: adopted, fileName: 'TEST ONLY correspondence.docx', sourceDocumentId: 42, sourceHash: 'TEST-SOURCE', sourceQuote: 'TEST ONLY name and number', reason: 'TEST ONLY evidence', confidence: 1 }] }]))
  const docs = new Map(projects.map(project => [project.id, ['NTT', 'SCT', 'SCC'].map(fileKey => ({ fileKey, content: 'TEST ONLY saved body', generated: true, stale: false, revisionId: 'TEST-OLD', docxSha256: 'a'.repeat(64), blocks: [] }))]))
  const calls = []; let failure, barrier
  const api = {
    catalog: async () => ({ ruleVersion: 'TEST', graphRevisionId: 'TEST-GRAPH', groups: [{ id: 'identity', label: label('Project identity'), fields: [{ key: 'contractTitle', kind: 'contract', label: label('Contract identity') }] }] }),
    variables: async id => copy([values.get(id)]), templates: async () => ['NTT', 'SCT', 'SCC'].map(key => ({ key, tag: 'ok' })), inputs: async () => [], extractTrace: async () => null,
    documents: async id => { calls.push(['documents', id]); return copy(docs.get(id)) }, plan: async () => ({ actions: [], unresolved: [] }),
    reviewHead: async () => ({ currentRevisionId: 'TEST-GRAPH', current: { id: 'TEST-GRAPH', sourceStale: false, graph: { selectedDocuments: {} } }, sources: [] }),
    updateVariable: async (id, key, patch) => { calls.push(['adopt', id, key, copy(patch)]); values.get(id).confirmed = true; values.get(id).adoptionState = 'adopted'; return copy(values.get(id)) },
    confirmIdentity: async (id, request) => { calls.push(['confirmIdentity', id, copy(request)]); if (barrier) await barrier.promise; if (failure) throw failure; const project = projects.find(project => project.id === id); project.name = label('TEST adopted project name'); project.contractNo = 'TEST-2026/1'; docs.get(id).forEach(document => { document.stale = true }); return copy(project) },
    generate: async () => { throw Error('Identity confirmation must not generate drafts or call a model') }
  }
  const workspace = vue.defineComponent({ props: ['document'], setup(props) { return () => vue.h('p', { 'data-test-stale': String(props.document?.stale) }) } })
  const loaded = loadVue(path.join(__dirname, '../src/views/DraftingView.vue'), { boundaries: {
    '@/api/source-locations': { reviewSourceBindings: async () => { throw Error('Source location transport not provided by this fixture') } }, '@/api': { ApiError, draftingApi: api, projectApi: { list: async () => { calls.push(['projects']); return copy(projects) } }, setLlmProfileResolver() {}, captureLlmSelection: () => ({ profileId: 'local' }) },
    pinia: piniaRuntime, 'vue-i18n': require('vue-i18n'), 'vue-router': routerRuntime,
    '@/components/DraftingDocumentWorkspace.vue': { default: workspace },
    'pdfjs-dist': { GlobalWorkerOptions: {}, getDocument() { throw Error('No PDF rendering belongs to this test') } }, 'pdfjs-dist/build/pdf.worker.min.mjs?url': { default: 'TEST-ONLY-WORKER' },
    'mammoth/mammoth.browser': { convertToHtml() { throw Error('No conversion belongs to this test') } }
  }, globals: { window, document, HTMLElement, Element, Node, Blob, localStorage: { getItem: () => 'en', setItem() {} }, crypto: require('node:crypto').webcrypto } })
  const pinia = piniaRuntime.createPinia(); piniaRuntime.setActivePinia(pinia)
  const store = loaded.loadLocal(path.join(__dirname, '../src/stores/app.ts')).useAppStore(); store.changeLocale('en'); await store.loadProjects()
  const topbar = loaded.loadLocal(path.join(__dirname, '../src/components/AppTopbar.vue')).default
  const router = routerRuntime.createRouter({ history: routerRuntime.createMemoryHistory(), routes: [{ path: '/', name: 'drafting', component: { render() {} } }] }); await router.push('/'); await router.isReady()
  const host = document.createElement('div'); document.body.append(host)
  const app = vue.createApp({ render: () => vue.h('main', [vue.h(topbar), vue.h(loaded.default)]) }); app.use(pinia); app.use(router); app.use(loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n); app.mount(host); await settle()
  const button = title => [...host.querySelectorAll('button')].find(node => node.textContent.trim() === title)
  async function click(target) { const control = target.startsWith('[') ? host.querySelector(target) : button(target); assert.ok(control, target); assert.equal(control.disabled, false, target); control.click(); await settle() }
  const visibleModal = () => [...host.querySelectorAll('.modal-backdrop')].find(node => node.classList.contains('show') && node.querySelector('[data-project-identity-comparison]'))
  await click('Enter inputs manually')
  return { host, store, projects, values, docs, calls, click, visibleModal, fail(code) { failure = new ApiError(code, 'TEST ONLY identity failure') }, pause() { barrier = deferred(); return barrier }, dispose() { app.unmount(); host.remove() } }
}

test('adoption asks before replacing project identity; cancel preserves it and explicit confirmation refreshes the full package', async () => {
  const h = await harness()
  try {
    assert.ok(h.host.querySelector('[data-group="identity"]'), 'The fixed identity group is visible in graph mode')
    assert.equal(h.visibleModal(), undefined); assert.equal(h.calls.some(call => call[0] === 'confirmIdentity'), false)
    await h.click('Adopt this candidate')
    assert.ok(h.visibleModal(), 'A valid differing adopted identity automatically asks for confirmation')
    assert.match(h.visibleModal().textContent, /TEST old A/); assert.match(h.visibleModal().textContent, /TEST adopted project name/)
    await h.click('[data-cancel-project-identity]')
    assert.equal(h.store.activeProject.name.en, 'TEST old A'); assert.equal(h.store.activeProject.contractNo, 'OLD-A')
    assert.equal(h.values.get('A').confirmed, true, 'Declining replacement keeps the separately adopted fact')
    assert.equal(h.docs.get('A').some(document => document.stale), false)
    assert.equal(h.calls.some(call => call[0] === 'confirmIdentity'), false)
    await h.click('[data-review-project-identity]'); await h.click('[data-confirm-project-identity]')
    const request = h.calls.find(call => call[0] === 'confirmIdentity')
    assert.deepEqual(request, ['confirmIdentity', 'A', { expectedProjectIdentity: { nameZhHans: 'TEST old A', nameZhHant: 'TEST old A', nameEn: 'TEST old A', contractNo: 'OLD-A' }, expectedAdoptedIdentity: { value: adopted } }])
    assert.equal(h.store.activeProject.name.en, 'TEST adopted project name'); assert.equal(h.store.activeProject.contractNo, 'TEST-2026/1')
    assert.equal(h.docs.get('A').every(document => document.stale), true)
    assert.equal(h.visibleModal(), undefined); assert.equal(h.host.querySelector('[data-identity-pending]'), null)
    const confirmIndex = h.calls.findIndex(call => call[0] === 'confirmIdentity')
    assert.ok(h.calls.slice(confirmIndex).some(call => call[0] === 'projects'))
    assert.ok(h.calls.slice(confirmIndex).some(call => call[0] === 'documents' && call[1] === 'A'))
    for (const locale of ['zh-Hans', 'zh-Hant', 'en']) { h.store.changeLocale(locale); await settle(); assert.match(h.host.querySelector('.project-switcher').textContent, /TEST adopted project name/) }
  } finally { h.dispose() }
})

test('an identity conflict preserves existing metadata and drafts and offers a fresh review', async () => {
  const h = await harness({ confirmed: true })
  try {
    h.fail(4090); await h.click('[data-review-project-identity]'); await h.click('[data-confirm-project-identity]')
    assert.ok(h.visibleModal()); assert.match(h.host.querySelector('[data-identity-error]').textContent, /changed.*Reload/)
    assert.equal(h.store.activeProject.name.en, 'TEST old A'); assert.equal(h.store.activeProject.contractNo, 'OLD-A')
    assert.equal(h.docs.get('A').some(document => document.stale), false)
    assert.equal(h.values.get('A').value, adopted); assert.equal(h.values.get('A').confirmed, true)
    h.projects[0].contractNo = 'CHANGED-BY-OTHER-EDITOR'
    await h.click('[data-reload-project-identity]')
    assert.ok(h.visibleModal()); assert.match(h.visibleModal().textContent, /CHANGED-BY-OTHER-EDITOR/)
    assert.equal(h.calls.filter(call => call[0] === 'confirmIdentity').length, 1, 'Reloading cannot retry the write')
  } finally { h.dispose() }
})

test('a pending identity confirmation is scoped to its captured project across switches', async () => {
  const h = await harness({ confirmed: true })
  try {
    await h.click('[data-review-project-identity]'); const barrier = h.pause()
    await h.click('[data-confirm-project-identity]'); await h.store.switchProject('B'); await settle()
    assert.equal(h.visibleModal(), undefined); barrier.resolve(); await settle()
    assert.equal(h.calls.find(call => call[0] === 'confirmIdentity')[1], 'A')
    assert.equal(h.store.activeProjectId, 'B'); assert.equal(h.store.activeProject.name.en, 'TEST old B')
    assert.equal(h.docs.get('B').some(document => document.stale), false)
    assert.equal(h.store.projects.find(project => project.id === 'A').contractNo, 'TEST-2026/1')
  } finally { h.dispose() }
})
