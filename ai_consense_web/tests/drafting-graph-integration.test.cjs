/* Actual mounted DraftingView, native DOCX converter and read-only review controls.
   Fixtures are synthetic. Only transport and optional PDF/browser layout are boundaries. */
const assert = require('node:assert/strict'), path = require('node:path'), crypto = require('node:crypto')
const { JSDOM } = require('jsdom'), window = new JSDOM('<main id="app"></main>').window
for (const key of ['window', 'document', 'Element', 'HTMLElement', 'SVGElement', 'Node', 'Document', 'ShadowRoot']) globalThis[key] = key === 'window' ? window : window[key]
const vue = require('vue'), piniaRuntime = require('pinia'), JSZip = require('jszip')
const { loadVue } = require('./helpers/load-vue.cjs'), { settle, deferred } = require('./helpers/render-controls.cjs')
window.HTMLElement.prototype.scrollTo = function (position) { this.scrollTop = position.top; this.scrollLeft = position.left }
window.HTMLElement.prototype.scrollIntoView = function () { this.dataset.scrollRequested = 'true' }
window.HTMLElement.prototype.getClientRects = () => [{ width: 100, height: 30 }]
window.requestAnimationFrame = callback => setTimeout(callback, 0); window.cancelAnimationFrame = clearTimeout
class ResizeObserver { observe() {} disconnect() {} }
const label = en => ({ en, zhHans: en, zhHant: en }), hash = value => crypto.createHash('sha256').update(value).digest('hex')
const nativePath = ordinal => `word/document.xml#/w:document[1]/w:body[1]/w:p[${ordinal}]`
const escapeXml = text => text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
async function docx(paragraphs) {
  const zip = new JSZip()
  zip.file('[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
  zip.file('_rels/.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
  zip.file('word/document.xml', `<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>${paragraphs.map(text => `<w:p><w:r><w:t xml:space="preserve">${escapeXml(text)}</w:t></w:r></w:p>`).join('')}</w:body></w:document>`)
  const bytes = await zip.generateAsync({ type: 'arraybuffer' }); return { bytes, sha: hash(Buffer.from(bytes)) }
}
const fields = [
  { key: 'foundationIncluded', kind: 'boolean', label: label('Foundation works'), affects: [{ document: 'NTT', clause: 'Route', paragraphs: 'P2' }, { document: 'SCT', clause: 'SCT route', paragraphs: 'P2' }] },
  { key: 'periodAtLeast39Months', kind: 'boolean', label: label('Period at least 39 months') },
  { key: 'contactTelephone', kind: 'text', label: label('Contact telephone') }
]
const quote = 'SYNTHETIC correspondence: Foundation works are included in this contract.'
const evidenceHash = hash('SYNTHETIC meeting DOCX')
const variables = [
  { key: 'foundationIncluded', value: 'true', confirmed: true, candidates: [{ value: 'true', sourceDocumentId: 44, fileName: 'SYNTHETIC-correspondence.docx', sourceHash: evidenceHash, sourceQuote: quote, reason: 'SYNTHETIC model explanation; not the quoted source.' }] },
  { key: 'periodAtLeast39Months', value: '', confirmed: false },
  { key: 'contactTelephone', value: '', confirmed: false }
]
const sourceContext = `SYNTHETIC earlier request.\n${quote}\nSYNTHETIC later qualification.`
const extractionTrace = { model: 'SYNTHETIC recorded model', finishedAt: '2026-10-07', systemPrompt: '', userPrompt: '', rawResponses: [], parts: [{ partId: 'SYNTHETIC-part', sourceDocumentId: 44, fileName: 'SYNTHETIC-correspondence.docx', sourceHash: evidenceHash, partIndex: 0, sourceText: sourceContext, attempts: [{ attemptIndex: 1, kind: 'recall', status: 'completed', systemPrompt: '', userPrompt: '', rawResponse: '', context: { sourceText: sourceContext, sourceStart: 0, sourceEnd: sourceContext.length } }] }], decisions: [{ partId: 'SYNTHETIC-part', attemptIndex: 1, itemIndex: 0, key: 'foundationIncluded', rawValue: 'true', normalizedValue: 'true', sourceQuote: quote, status: 'accepted', codes: [] }] }
const sourceText = ['SYNTHETIC complete opening context.', 'Tender route [choice] applies.', 'SYNTHETIC second Foundation works target.', 'SYNTHETIC ordinary heading.', 'SYNTHETIC cleanup unrelated to any input.', 'SYNTHETIC obsolete field metadata.', 'SYNTHETIC complete ending context.']
const resultText = [...sourceText]; resultText[1] = 'Tender route A applies.'
const clone = value => JSON.parse(JSON.stringify(value)), calls = [], writes = []
let original, result, guard = '', slowSource, slowResult, slowPlan
const saved = { fileKey: 'NTT', title: 'SYNTHETIC contract', content: resultText[1], generated: true, stale: false, snapshotId: 'SYNTHETIC-snapshot', revisionId: 'SYNTHETIC-revision-1', unresolved: [], blocks: [] }
const binding = (id, ordinal, keys, applied = false) => ({
  bindingId: id, bookmarkName: id, sourceParagraphId: nativePath(ordinal), sourceParagraphOrdinal: ordinal, sourceText: sourceText[ordinal - 1],
  resultParagraphId: nativePath(ordinal), resultParagraphOrdinal: ordinal, text: resultText[ordinal - 1],
  fieldKeys: keys, actionIds: applied ? ['SYNTHETIC-A'] : [], appliedActionIds: applied ? ['SYNTHETIC-A'] : [], operationIds: applied ? ['SYNTHETIC-A-operation'] : [],
  applicationStatus: applied ? 'applied' : 'unchanged', locationStatus: 'exact', geometryStatus: 'unavailable'
})
let rows = [binding('SYNTHETIC-b1', 2, fields.slice(0, 2).map(field => field.key), true), binding('SYNTHETIC-b2', 3, ['foundationIncluded']), binding('SYNTHETIC-ordinary', 4, []), binding('SYNTHETIC-cleanup', 5, []), binding('SYNTHETIC-unknown', 6, ['retiredField'])]
const actions = [{ id: 'SYNTHETIC-A', document: 'NTT', clause: 'Route', paragraphs: 'P2', inputKeys: ['foundationIncluded'], action: 'amend', detail: label('SYNTHETIC route amendment') }]
const bundle = (view, file) => ({ fileKey: file, view, sourceSha256: original.sha, ...(view === 'result' ? { revisionId: saved.revisionId, docxSha256: result.sha } : {}), bindings: clone(rows).map(row => view === 'source' ? { ...row, text: row.sourceText, appliedActionIds: [], operationIds: [], applicationStatus: 'unchanged' } : row) })
const api = {
  catalog: async () => ({ ruleVersion: 'SYNTHETIC', groups: [{ id: 'scope', label: label('Scope'), fields }] }), variables: async () => clone(variables),
  templates: async () => ['NTT', 'SCT', 'SCC'].map(key => ({ key, tag: 'ok' })), inputs: async () => [], extractTrace: async () => clone(extractionTrace),
  documents: async () => ['NTT', 'SCT', 'SCC'].map(fileKey => ({ ...clone(saved), fileKey })), plan: async (id, inputValues) => { if (id === 'SYNTHETIC-GRAPH-LATE') return slowPlan.promise; return { ruleVersion: 'SYNTHETIC', inputValues: clone({ ...Object.fromEntries(variables.map(item => [item.key, item.value])), ...(inputValues ?? {}) }), effectiveValues: {}, actions, unresolved: [] } },
  templateBindings: async (...args) => { calls.push(['source-bindings', ...args]); const value = bundle('source', args[1]); if (guard === 'source') value.sourceSha256 = 'wrong-source'; return value },
  templateSource: async (...args) => { calls.push(['source-docx', ...args]); return args[0] === 'SYNTHETIC-LATE-SOURCE' ? slowSource.promise : new Blob([original.bytes]) },
  documentBindings: async (...args) => { calls.push(['result-bindings', ...args]); const value = bundle('result', args[1]); if (guard === 'revision') value.revisionId = 'wrong-revision'; if (guard === 'result') value.docxSha256 = 'wrong-result'; return args[0] === 'SYNTHETIC-LATE-RESULT' ? slowResult.promise : value },
  documentSource: async (...args) => { calls.push(['result-docx', ...args]); return new Blob([result.bytes]) },
  templatePreview: async () => { throw new Error('Ordinary native navigation must not render PDF') }, previewDocument: async () => { throw new Error('Ordinary native navigation must not render PDF') },
  updateVariable: async (...args) => { writes.push(args); throw new Error('Navigation must not adopt or save an input') },
  updateDocument: async (...args) => { writes.push(args); throw new Error('Navigation must not save body text') },
  exportDocument: async (...args) => { writes.push(args); throw new Error('Navigation must not export') },
  generate: async (...args) => { writes.push(['generate', ...args]); throw new Error('Navigation must not generate') }, extract: async (...args) => { writes.push(['extract', ...args]); throw new Error('Navigation must not extract') }
}
const loaded = loadVue(path.join(__dirname, '../src/views/DraftingView.vue'), {
  globals: { window, document, Element, HTMLElement, Node, DOMParser: window.DOMParser, XMLSerializer: window.XMLSerializer, NodeFilter: window.NodeFilter, ResizeObserver, Blob, crypto: crypto.webcrypto, URL: { createObjectURL: () => 'blob:SYNTHETIC', revokeObjectURL() {} }, localStorage: { getItem: () => 'en', setItem() {} } },
  boundaries: { '@/api': { draftingApi: api, setLlmProfileResolver() {} }, pinia: piniaRuntime, 'vue-i18n': require('vue-i18n'), jszip: { default: JSZip }, dompurify: { default: require('dompurify')(window) }, 'mammoth/mammoth.browser.min.js': { default: require('mammoth/mammoth.browser.min.js') }, 'mammoth/mammoth.browser': { default: require('mammoth/mammoth.browser') }, 'pdfjs-dist/build/pdf.worker.min.mjs?url': { default: 'external-worker' }, 'pdfjs-dist': { GlobalWorkerOptions: {}, getDocument() { throw new Error('Native location navigation must not open a PDF') } } }
})
const pinia = piniaRuntime.createPinia(); piniaRuntime.setActivePinia(pinia)
const store = loaded.loadLocal(path.join(__dirname, '../src/stores/app.ts')).useAppStore(); store.switchProject('SYNTHETIC-BOUND-P1')
const app = vue.createApp(loaded.default); app.use(pinia); app.use(loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n)
const workspace = () => document.querySelector('[data-document-workspace]'), paper = () => workspace()?.querySelector('[data-document-paper]')
async function settled() { await settle(); await new Promise(resolve => setTimeout(resolve, 40)); await settle() }
async function until(predicate) { for (let n = 0; n < 100; n++) { await settled(); if (predicate()) return }; throw new Error(`Rendered state did not arrive: ${workspace()?.textContent}`) }
function button(title, within = document) { return [...within.querySelectorAll('button')].find(node => node.textContent.trim() === title) }
async function click(selector) { const control = document.querySelector(selector); assert.ok(control, `Public control ${selector}`); assert.notEqual(control.disabled, true); control.dispatchEvent(new window.MouseEvent('click', { bubbles: true })); await settled() }
async function clickTitle(title) { const control = button(title); assert.ok(control, `Public control ${title}`); assert.notEqual(control.disabled, true); control.dispatchEvent(new window.MouseEvent('click', { bubbles: true })); await settled() }
;(async () => {
  original = await docx(sourceText); result = await docx(resultText)
  Object.assign(saved, { sourceSha256: original.sha, docxSha256: result.sha, blocks: [{ id: nativePath(2), bindingId: rows[0].bindingId, text: resultText[1], textHash: hash(resultText[1]), paragraphOrdinal: 2, editable: true }] })
  saved.generated = false
  app.mount('#app'); await settled()
  const entry = document.querySelector('[data-open-business-graph="header"]')
  assert(entry, 'A graph entry is available outside the existing source/input steps')
  assert.equal(entry.disabled, false)
  assert.equal(entry.textContent, 'Variable relationships')
  assert.equal(document.querySelector('[data-graph-close]'), null, 'Graph overlay mounts only after explicit opening')
  assert.equal(calls.filter(call => ['source-docx', 'result-docx'].includes(call[0])).length, 0)
  assert.equal(writes.length, 0)
  console.log('PASS: production header exposes a localized graph entry without mounting the graph or reading documents')
  await clickTitle('4 Adopt and plan')
  const phone = document.querySelector('#input-contactTelephone')
  phone.value = '  +852 1234 5678  '; phone.dispatchEvent(new window.Event('input', { bubbles: true })); await settled()
  await click('[data-open-business-graph="header"]'); await until(() => document.querySelector('[data-graph-close]'))
  await click('[data-graph-input="contactTelephone"]')
  assert.match(document.querySelector('[data-graph-current-value]').textContent, /\+852 1234 5678/)
  assert.equal(document.querySelector('[data-graph-current-value] input'), null, 'Graph values are readonly')
  assert.equal(calls.filter(call => ['source-docx', 'result-docx'].includes(call[0])).length, 0, 'Graph opening and inspection fetch no DOCX')
  await click('[data-graph-return-input]')
  assert.equal(document.querySelector('[data-graph-close]'), null, 'Input navigation closes the graph')
  assert.equal(document.activeElement, document.querySelector('#input-contactTelephone'))
  assert.equal(document.querySelector('#input-contactTelephone').value, '  +852 1234 5678  ', 'Existing dirty input survives round-trip without encoding or saving')
  assert.equal(writes.length, 0)
  console.log('PASS: actual graph inspector returns to the existing edit control with the exact unsaved value and no document reads/writes')
  await click('[data-open-business-graph="header"]'); await until(() => document.querySelector('[data-graph-close]'))
  await click('[data-graph-input="foundationIncluded"]'); await until(() => document.querySelector('[data-graph-clause="SYNTHETIC-A"]'))
  await click('[data-graph-clause="SYNTHETIC-A"]')
  assert.equal(document.querySelector('[data-graph-close]'), null, 'Document navigation closes graph instead of leaving an overlay above the reader')
  await until(() => paper()?.querySelector('.review-focused'))
  assert.equal(paper().querySelector('.review-focused').dataset.reviewBinding, 'SYNTHETIC-b1')
  assert.equal(workspace().querySelector('[data-review-mode="original"]').getAttribute('aria-pressed'), 'true')
  assert.equal(workspace().querySelector('select[aria-label="Select input"]').value, 'foundationIncluded')
  assert.match(paper().textContent, /SYNTHETIC complete opening context/); assert.match(paper().textContent, /SYNTHETIC complete ending context/)
  assert.equal(paper().querySelectorAll('[data-native-paragraph]').length, sourceText.length)
  assert.equal(calls.filter(call => ['result-docx', 'result-bindings'].includes(call[0])).length, 0, 'Source-only documents navigate without generating a saved artifact')
  await click('[data-review-field="contactTelephone"]')
  assert.match(workspace().querySelector('[data-current-value]').textContent, /\+852 1234 5678/)
  await click('[data-return-input]')
  assert.equal(document.activeElement, document.querySelector('#input-contactTelephone'))
  assert.equal(document.querySelector('#input-contactTelephone').value, '  +852 1234 5678  ')
  assert.equal(writes.length, 0, 'Graph→original reader→existing input never generates, saves, adopts, exports or extracts')
  console.log('PASS: actual parent/graph/native-DOCX flow selects original exact action context before any saved generation and preserves the dirty input')
  await click('[data-open-business-graph="header"]'); await until(() => document.querySelector('[data-graph-close]'))
  await click('[data-graph-input="foundationIncluded"]'); await click('[data-graph-potential="SCT:SCT route"]')
  await until(() => workspace()?.querySelector('.navigation-notice'))
  assert.ok(button('SCT', workspace()).classList.contains('primary'))
  assert.equal(paper().querySelector('.review-focused'), null, 'A potential clause without a unique source action never falls back to unrelated first paragraphs')
  assert.match(workspace().querySelector('.navigation-notice').textContent, /requested.*no verified/i)
  assert.equal(workspace().querySelector('select[aria-label="Select input"]').value, 'foundationIncluded')
  assert.equal(workspace().querySelector('[data-review-mode="original"]').getAttribute('aria-pressed'), 'true')
  assert.equal(writes.length, 0)
  console.log('PASS: actual graph potential navigation forwards the exact field/file/clause and shows a truthful missing-source notice without fallback')
  await click('[data-open-business-graph="header"]'); await until(() => document.querySelector('[data-graph-close]'))
  slowPlan = deferred(); store.switchProject('SYNTHETIC-GRAPH-LATE'); await settled()
  assert.equal(document.querySelector('[data-graph-close]'), null, 'Project changes unmount graph selection immediately')
  assert.equal(document.querySelector('[data-open-business-graph="header"]').disabled, true, 'Busy project loading blocks graph navigation')
  saved.generated = true; store.switchProject('SYNTHETIC-GRAPH-AFTER-LATE'); await settled()
  slowPlan.resolve({ ruleVersion: 'SYNTHETIC', inputValues: { foundationIncluded: true }, effectiveValues: {}, actions: [{ ...actions[0], id: 'SYNTHETIC-OLD', clause: 'Old project only' }], unresolved: [] }); await settled()
  await click('[data-open-business-graph="header"]'); await until(() => document.querySelector('[data-graph-close]'))
  await click('[data-graph-input="foundationIncluded"]')
  assert.equal(document.querySelector('[data-graph-action="SYNTHETIC-OLD"]'), null, 'Late old-project plan cannot relabel the current graph')
  assert.ok(document.querySelector('[data-graph-clause="SYNTHETIC-A"]'))
  document.querySelector('[data-graph-search]').dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })); await settled()
  assert.equal(document.querySelector('[data-graph-close]'), null)
  assert.equal(document.activeElement, document.querySelector('[data-open-business-graph="header"]'), 'Explicit graph close restores its originating header control')
  await click('[data-open-business-graph="header"]'); await until(() => document.querySelector('[data-graph-close]'))
  await click('[data-graph-input="foundationIncluded"]'); await click('[data-graph-clause="SYNTHETIC-A"]')
  await until(() => paper()?.querySelector('.review-focused'))
  assert.ok(calls.some(call => call[0] === 'result-docx'), 'Existing revision-guarded saved GET loading remains available')
  await click('[data-review-mode="saved"]'); assert.match(paper().textContent, /Tender route A applies/)
  assert.equal(writes.length, 0)
  console.log('PASS: project/busy guards close old graph, reject late old plans, restore close focus, and keep normal saved reading available through readonly GETs')

  await clickTitle('Edit content'); await clickTitle('Edit paragraph')
  const pendingBody = '  SYNTHETIC body draft stays untouched.  ', body = document.getElementById('body-p-2')
  body.value = pendingBody; body.dispatchEvent(new window.Event('input', { bubbles: true })); await settled()
  assert.equal(document.querySelector('[data-open-business-graph="header"]').disabled, true)
  await clickTitle('Expand document')
  const immersiveEntry = document.querySelector('[data-open-business-graph="immersive"]')
  assert(immersiveEntry, 'Immersive toolbar retains its graph entry'); assert.equal(immersiveEntry.disabled, true)
  immersiveEntry.click(); await settled(); assert.equal(document.querySelector('[data-graph-close]'), null)
  assert.equal(document.getElementById('body-p-2').value, pendingBody, 'Dirty body edits survive the blocked graph entry unchanged')
  await clickTitle('Discard unsaved changes')
  await click('[data-open-business-graph="immersive"]'); await until(() => document.querySelector('[data-graph-close]'))
  await click('[data-graph-fullscreen]')
  document.querySelector('[data-graph-search]').dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })); await settled()
  assert.ok(document.querySelector('[data-graph-close]'), 'First Escape exits graph fullscreen without closing graph or immersive reader')
  document.querySelector('[data-graph-fullscreen]').dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })); await settled()
  assert.equal(document.querySelector('[data-graph-close]'), null)
  assert.ok(workspace().classList.contains('preview-card--immersive'), 'Graph Escape does not exit the underlying immersive workspace')
  assert.equal(document.activeElement, document.querySelector('[data-open-business-graph="immersive"]'))
  for (const [locale, expected] of [['zh-Hans', '变量关系图'], ['zh-Hant', '變量關係圖'], ['en', 'Variable relationships']]) { store.locale = locale; await settled(); assert.equal(document.querySelector('[data-open-business-graph="immersive"]').textContent, expected) }
  assert.equal(writes.length, 0)
  console.log('PASS: dirty body guards, immersive entry/fullscreen Escape ownership and all three entry locales preserve the existing workspace with zero writes')
  app.unmount(); window.close()
})().catch(error => { console.error(error); app.unmount(); window.close(); process.exitCode = 1 })