const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { JSDOM } = require('jsdom')
const window = new JSDOM('<main id="app"></main>').window
for (const key of ['window', 'document', 'Element', 'HTMLElement', 'SVGElement', 'Node', 'Document', 'ShadowRoot']) globalThis[key] = key === 'window' ? window : window[key]
const elementScrolls = [], localScrolls = []
window.HTMLElement.prototype.scrollIntoView = function (options) { elementScrolls.push({ id: this.id, options }) }
// JSDOM omits the browser's public local-scroll API. Record the real element
// target so reverse navigation must keep its panel reveal scoped to the reader.
window.HTMLElement.prototype.scrollTo = function (options) { localScrolls.push({ id: this.id, className: this.className, options }); this.scrollTop = options.top }
const vue = require('vue')
const piniaRuntime = require('pinia')
const { loadVue } = require('./helpers/load-vue.cjs')
const { settle } = require('./helpers/render-controls.cjs')
const bytes = fs.readFileSync(path.join(__dirname, 'fixtures/template-reading.synthetic.docx'))
const paragraphs = [
  { id: 'body-1', ordinal: 1, text: 'Invitation to tender' },
  { id: 'table-2', ordinal: 2, text: 'Complete the Works in [period] months.' },
  { id: 'body-3', ordinal: 3, text: 'Complete the Works in [period] months.' }
]
const fields = [
  { key: 'foundationIncluded', kind: 'boolean', label: { en: 'Foundation' }, affects: [{ document: 'NTT', clause: 'Foundation', paragraphs: 'P2' }] },
  { key: 'projectArchitectPost', kind: 'text', label: { en: 'Architect post' }, affects: [{ document: 'NTT', clause: 'Architect', paragraphs: 'P1' }] },
  { key: 'designResponsibilities', kind: 'list', label: { en: 'Design responsibilities' }, affects: [{ document: 'NTT', clause: 'Design', paragraphs: 'P3' }] }
]
const actions = fields.map((field, index) => ({ id: ['NTT-10', 'NTT-architect', 'NTT-design'][index], ...field.affects[0], inputKeys: [field.key], sourceText: paragraphs[[1, 0, 2][index]].text, action: 'pending' }))
const writes = []
const api = {
  catalog: async () => ({ ruleVersion: 'unchanged', groups: fields.map(field => ({ id: field.key, label: field.label, fields: [field] })) }),
  variables: async () => fields.map((field, index) => ({ key: field.key, value: ['false', 'Source post', '[]'][index], confirmed: false, manuallyEdited: false, candidates: [] })),
  templates: async () => ['NTT', 'SCT', 'SCC'].map(key => ({ key, fileName: `${key}.docx`, tag: 'ok' })),
  inputs: async () => [], documents: async () => [], extractTrace: async () => null,
  plan: async () => ({ actions, unresolved: [] }), updateVariable: async (...args) => { writes.push(args) },
  templateReading: async (_project, fileKey) => ({ fileKey, fileName: 'actually-uploaded.docx', sourceHash: require('node:crypto').createHash('sha256').update(bytes).digest('hex'), format: 'docx', catalogueSourceVerified: true, paragraphs }),
  templateSource: async () => new Blob([bytes])
}
const loaded = loadVue(path.join(__dirname, '../src/views/DraftingView.vue'), { boundaries: {
  '@/api': { draftingApi: api, setLlmProfileResolver() {} }, pinia: piniaRuntime, 'vue-i18n': require('vue-i18n'),
  'pdfjs-dist': { GlobalWorkerOptions: {}, getDocument() { throw new Error('No generated PDF requested') } },
  'pdfjs-dist/build/pdf.worker.min.mjs?url': { default: 'external-worker' },
  'mammoth/mammoth.browser.min.js': { default: require('mammoth/mammoth.browser.min.js') },
  dompurify: { default: require('dompurify')(window) }
}, globals: { window, document, HTMLElement, Blob, URL, crypto: require('node:crypto').webcrypto, localStorage: { getItem: () => 'en', setItem() {} } } })

async function click(selector) { const control = document.querySelector(selector); assert.ok(control, selector); control.click(); await settle() }
async function waitFor(selector) {
  for (let i = 0; i < 50 && !document.querySelector(selector); i++) { await new Promise(resolve => setTimeout(resolve, 10)); await settle() }
  assert.ok(document.querySelector(selector), selector)
}
;(async () => {
  const pinia = piniaRuntime.createPinia(); piniaRuntime.setActivePinia(pinia)
  const store = loaded.loadLocal(path.join(__dirname, '../src/stores/app.ts')).useAppStore()
  store.switchProject('SELECTION-TEST')
  const app = vue.createApp(loaded.default)
  app.use(pinia); app.use(loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n); app.mount('#app')
  await settle()
  const manual = [...document.querySelectorAll('button')].find(button => button.textContent.trim() === 'Enter inputs manually')
  assert.ok(manual); manual.click(); await settle()
  const post = document.getElementById('input-projectArchitectPost')
  post.value = 'Keep this unsaved post'; post.dispatchEvent(new window.Event('input', { bubbles: true })); await settle()
  const action = document.querySelector('[data-action-id="NTT-10"]')
  const locate = [...action.querySelectorAll('button')].find(button => button.textContent.trim() === 'Locate in template')
  assert.ok(locate); locate.click(); await settle()
  await waitFor('[data-preview-location="NTT-10:table-2"]')
  const firstScroll = localScrolls.length
  await click('[data-native-paragraph="body-1"] [data-preview-field="projectArchitectPost"]')
  assert.ok(document.querySelector('[data-preview-card="projectArchitectPost"]'))
  assert.ok(document.querySelector('[data-preview-location="NTT-architect:body-1"]'), 'Source field selection must navigate the new field rather than the previous action')
  assert.equal(document.querySelector('[data-preview-location="NTT-10:table-2"]'), null)
  assert.equal(document.querySelector('[data-native-paragraph="body-1"]').getAttribute('aria-current'), 'location')
  assert.equal(localScrolls.slice(firstScroll).filter(scroll => scroll.id === 'drafting-template-panel').length, 1, 'Selecting a source marker reveals its card by scrolling only the reading side panel')
  assert.ok(elementScrolls.some(scroll => scroll.id === 'field-projectArchitectPost'), 'The selected main-list input remains located')
  assert.equal(post.value, 'Keep this unsaved post')
  assert.deepEqual(writes, [])
  locate.click(); await settle()
  await waitFor('[data-preview-location="NTT-10:table-2"]')
  const secondScroll = localScrolls.length
  await click('[data-native-paragraph="body-3"] [data-preview-input="designResponsibilities"]')
  assert.ok(document.querySelector('[data-preview-card="designResponsibilities"]'))
  assert.ok(document.querySelector('[data-preview-location="NTT-design:body-3"]'), 'Structured Return to input also replaces the previous action anchor')
  assert.equal(document.querySelector('[data-preview-location="NTT-10:table-2"]'), null)
  assert.equal(document.querySelector('[data-native-paragraph="body-3"]').getAttribute('aria-current'), 'location')
  assert.equal(localScrolls.slice(secondScroll).filter(scroll => scroll.id === 'drafting-template-panel').length, 1, 'Structured return-to-input reveals the selected card in the same side panel')
  assert.ok(elementScrolls.some(scroll => scroll.id === 'field-designResponsibilities'), 'Structured reverse navigation retains main-list location')
  assert.ok(localScrolls.every(scroll => scroll.id === 'drafting-template-panel' || scroll.className === 'reading-surface'), 'Only the source reading surface and its side panel use local scrolling; outer page containers are untouched')
  assert.equal(post.value, 'Keep this unsaved post')
  assert.deepEqual(writes, [])
  const focusLayout = [...document.querySelectorAll('button')].find(button => button.textContent.trim() === 'B · One question')
  assert.ok(focusLayout); focusLayout.click(); await settle()
  assert.deepEqual([...document.querySelectorAll('[data-group]')].map(node => node.dataset.group), ['designResponsibilities'], 'B starts at the business question selected by the source return')
  const search = document.querySelector('input[aria-label="Search questions, inputs or clauses"]')
  search.value = 'Foundation'; search.dispatchEvent(new window.Event('input', { bubbles: true })); await settle()
  assert.deepEqual([...document.querySelectorAll('[data-group]')].map(node => node.dataset.group), ['foundationIncluded'])
  await click('[data-native-paragraph="body-1"] [data-preview-field="projectArchitectPost"]')
  assert.equal(search.value, '', 'A native source marker clears incompatible filters')
  assert.deepEqual([...document.querySelectorAll('[data-group]')].map(node => node.dataset.group), ['projectArchitectPost'], 'Native source selection chooses the B group before input location')
  assert.equal(document.getElementById('input-projectArchitectPost').value, 'Keep this unsaved post')
  assert.ok(document.querySelector('[data-preview-card="projectArchitectPost"]'))
  await click('[data-native-paragraph="body-3"] [data-preview-input="designResponsibilities"]')
  assert.deepEqual([...document.querySelectorAll('[data-group]')].map(node => node.dataset.group), ['designResponsibilities'], 'Native structured return selects its own group in B')
  assert.ok(elementScrolls.some(scroll => scroll.id === 'field-designResponsibilities'))
  assert.deepEqual(writes, [], 'Native source navigation and layout switching never adopts or saves')
  app.unmount()
  console.log('PASS: reverse source selection replaces the old action anchor, reveals its readable card through scoped side-panel scrolling, retains main-list location and preserves unsaved inputs without adoption')
})().catch(error => { console.error(error); process.exitCode = 1 })
