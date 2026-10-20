const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const { createRequire } = require('node:module')
const webRoot = process.env.CONSENSE_WEB_ROOT || path.resolve(__dirname, '..')
const webRequire = createRequire(path.join(webRoot, 'package.json'))
const { JSDOM } = webRequire('jsdom')
const window = new JSDOM('<main id="app"></main>').window
for (const key of ['window', 'document', 'Element', 'HTMLElement', 'SVGElement', 'Node', 'Document', 'ShadowRoot']) globalThis[key] = key === 'window' ? window : window[key]
window.HTMLElement.prototype.scrollIntoView = function () { this.dataset.scrollRequested = 'true' }
const vue = webRequire('vue'), piniaRuntime = webRequire('pinia')
const { loadVue } = webRequire('./tests/helpers/load-vue.cjs')
const copy = value => JSON.parse(JSON.stringify(value))
const label = value => ({ en: value, zhHans: value, zhHant: value })
const settle = async () => { for (let n = 0; n < 25; n++) { await Promise.resolve(); await vue.nextTick() } }
class ApiError extends Error { constructor(code, message) { super(message); this.code = code } }

test('missing adopted identity redirects the mounted generation click without a write, retains drafts, and permits generation after manual adoption', async () => {
  const project = { id: 'AUTHORED-IDENTITY-PREFLIGHT', name: label('AUTHORED temporary workspace title'), contractNo: 'TEMPORARY-MIRROR', titleLines: [] }
  const old = { fileKey: 'SCT', title: 'AUTHORED existing SCT', content: 'AUTHORED retained previous body', generated: true, stale: true, revisionId: 'AUTHORED-OLD', docxSha256: '1'.repeat(64), blocks: [] }
  const oldBytes = JSON.stringify(old), calls = []
  const variable = { key: 'contractTitle', kind: 'contract', value: JSON.stringify({ number: null, title: null }), confirmed: false, reviewRequired: false, graphRevisionId: 'AUTHORED-GRAPH', fieldDefinitionHash: '2'.repeat(64) }
  const missing = ['number', 'title'].map(field => ({ id: 'input-contractTitle-' + field, kind: 'InputMissing', inputKeys: ['contractTitle'], message: label('AUTHORED backend identity field missing') }))
  const identityComplete = () => { const identity = JSON.parse(variable.value); return variable.confirmed && identity.number && identity.title }
  const api = {
    catalog: async () => ({ ruleVersion: 'AUTHORED', graphRevisionId: 'AUTHORED-GRAPH', groups: [{ id: 'identity', label: label('Project identity'), fields: [{ key: 'contractTitle', kind: 'contract', label: label('Contract identity'), optional: false }] }] }),
    variables: async () => copy([variable]), templates: async () => ['NTT', 'SCT', 'SCC'].map(key => ({ key, tag: 'ok' })), inputs: async () => [], extractTrace: async () => null,
    documents: async () => { calls.push(['documents']); return copy([old]) },
    reviewHead: async () => ({ currentRevisionId: 'AUTHORED-GRAPH', current: { id: 'AUTHORED-GRAPH', sourceStale: false, graph: { selectedDocuments: {} } }, sources: [] }),
    plan: async () => { calls.push(['plan']); return { actions: [], unresolved: identityComplete() ? [] : copy(missing) } },
    updateVariable: async (_id, key, patch) => { calls.push(['adopt', key, copy(patch)]); Object.assign(variable, { value: patch.value, confirmed: true, manuallyEdited: true, adoptionState: 'adopted' }); return copy(variable) },
    generate: async () => { calls.push(['generate']); if (!identityComplete()) throw Error('GRAPH_PLAN_REVIEW_REQUIRED'); return [{ ...old, content: 'AUTHORED generated after adopted identity', stale: false, revisionId: 'AUTHORED-NEW' }] }
  }
  const workspace = vue.defineComponent({ props: ['document'], setup(props) { return () => vue.h('article', { 'data-authored-workspace': '' }, props.document?.content) } })
  const loaded = loadVue(process.env.CONSENSE_DRAFTING_VIEW || path.join(webRoot, 'src/views/DraftingView.vue'), {
    boundaries: { '@/api': { ApiError, draftingApi: api, setLlmProfileResolver() {}, captureLlmSelection: () => ({ profileId: 'local' }) }, pinia: piniaRuntime, 'vue-i18n': webRequire('vue-i18n'),
      '@/components/DraftingDocumentWorkspace.vue': { default: workspace },
      'pdfjs-dist': { GlobalWorkerOptions: {}, getDocument() { throw Error('No PDF request belongs to identity preflight') } }, 'pdfjs-dist/build/pdf.worker.min.mjs?url': { default: 'AUTHORED-WORKER' },
      'mammoth/mammoth.browser': { convertToHtml() { throw Error('No native conversion belongs to identity preflight') } } },
    globals: { window, document, Element, HTMLElement, Node, Blob, localStorage: { getItem: () => 'en', setItem() {} }, crypto: require('node:crypto').webcrypto }
  })
  const pinia = piniaRuntime.createPinia(); piniaRuntime.setActivePinia(pinia)
  const store = loaded.loadLocal(path.join(webRoot, 'src/stores/app.ts')).useAppStore()
  store.changeLocale('en'); store.projects = [copy(project)]; await store.switchProject(project.id)
  const app = vue.createApp(loaded.default); app.use(pinia); app.use(loaded.loadLocal(path.join(webRoot, 'src/i18n/index.ts')).i18n); app.mount('#app')
  const button = title => [...document.querySelectorAll('button')].find(node => node.textContent.trim() === title)
  async function click(target) { const control = target.startsWith('[') || target.startsWith('.') ? document.querySelector(target) : button(target); assert.ok(control, target); assert.equal(control.disabled, false, target); control.click(); await settle() }
  async function input(selector, value) { const control = document.querySelector(selector); assert.ok(control, selector); assert.equal(control.disabled, false); control.value = value; control.dispatchEvent(new window.Event('input', { bubbles: true })); await settle() }
  try {
    await settle(); await click('5 Preview and export'); await click('SCT')
    assert.match(document.querySelector('[data-authored-workspace]').textContent, /retained previous body/)
    calls.length = 0
    await click('Regenerate')
    assert.deepEqual(calls, [['plan']], 'Reuse the existing plan read; do not generate or fetch extra data for identity preflight')
    assert.match(document.querySelector('[role="alert"]').textContent, /Complete and adopt.*project name.*contract number.*step 4/)
    assert.doesNotMatch(document.querySelector('[role="alert"]').textContent, /GRAPH_PLAN_REVIEW_REQUIRED/)
    assert.ok(document.querySelector('[data-variable="contractTitle"]'), 'The existing step 4 fact input is opened')
    assert.equal(document.querySelector('[data-variable="contractTitle"]').dataset.scrollRequested, 'true')
    assert.ok(document.activeElement.closest('[data-variable="contractTitle"]'), 'Focus returns to the existing fact input')
    assert.equal(JSON.stringify(old), oldBytes); assert.equal(store.activeProject.contractNo, 'TEMPORARY-MIRROR'); assert.equal(variable.confirmed, false)
    await click('5 Preview and export')
    assert.match(document.querySelector('[data-authored-workspace]').textContent, /retained previous body/)
    assert.equal(calls.filter(call => call[0] === 'generate').length, 0)
    await click('4 Adopt and plan')
    await input('[data-variable="contractTitle"] input[aria-label="Contract number"]', 'AUTHORED-2026/1')
    await input('[data-variable="contractTitle"] input[aria-label="Contract title"]', 'AUTHORED adopted project title')
    await input('[data-manual-note="contractTitle"]', 'AUTHORED user supplied identity')
    await click('[data-save-input="contractTitle"]')
    assert.equal(variable.confirmed, true)
    assert.deepEqual(JSON.parse(variable.value), { number: 'AUTHORED-2026/1', title: 'AUTHORED adopted project title' })
    assert.equal(calls.filter(call => call[0] === 'adopt').length, 1)
    if (document.querySelector('[data-cancel-project-identity]')) await click('[data-cancel-project-identity]')
    assert.equal(store.activeProject.contractNo, 'TEMPORARY-MIRROR', 'Manual fact adoption does not silently replace topbar metadata')
    calls.length = 0
    await click('.input-stack > .flow-actions button.primary')
    assert.deepEqual(calls, [['plan'], ['generate']], 'The ordinary generation path remains available after explicit identity adoption')
    assert.match(document.querySelector('[data-authored-workspace]').textContent, /generated after adopted identity/)
    assert.equal(variable.confirmed, true); assert.equal(JSON.stringify(old), oldBytes)
  } finally { app.unmount() }
})
