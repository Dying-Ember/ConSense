/* Real mounted View, workspace, native reader and body editor. HTTP/PDF.js are
   declared boundaries; synthetic DOCX fixtures do not establish business or browser acceptance. */
const assert = require('node:assert/strict')
const path = require('node:path')
const { JSDOM } = require('jsdom')
const window = new JSDOM('<main id="app"></main>').window
for (const key of ['window', 'document', 'Element', 'HTMLElement', 'SVGElement', 'Node', 'Document', 'ShadowRoot']) globalThis[key] = key === 'window' ? window : window[key]
const vue = require('vue'), piniaRuntime = require('pinia'), JSZip = require('jszip'), crypto = require('node:crypto')
const { loadVue } = require('./helpers/load-vue.cjs')
const { settle, deferred } = require('./helpers/render-controls.cjs')
const copy = value => JSON.parse(JSON.stringify(value))
const root = 'word/document.xml#/w:document[1]/w:body[1]', scopeId = `${root}/w:tbl[1]`, emptyId = `${scopeId}/w:tr[1]/w:tc[1]/w:p[1]`
const candidate = { scopeId, kind: 'table', operation: 'delete_empty_table', nodeSha256: 'c'.repeat(64), memberBindingIds: ['CSemptyFixture'], paragraphCount: 1, paragraphIds: [emptyId], paragraphOrdinals: [3], clearProofBindings: ['CSemptyFixture'], nativeNumberingCount: 0, nativeTabCount: 1, nativeRowHeightCount: 0, previousText: 'Retained before cleanup.', nextText: 'Retained after cleanup.' }
const docxBytes = new Map(), servers = new Map(), writes = [], previews = [], exportCalls = [], tocCalls = [], pdfLoads = [], forbidden = []
let saveResponse = () => { throw Error('SYNTHETIC save conflict; pending cleanup retained') }

async function docx(withTable) {
  const zip = new JSZip()
  zip.file('[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
  zip.file('_rels/.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
  zip.file('word/document.xml', `<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>SYNTHETIC complete cleanup fixture</w:t></w:r></w:p><w:p><w:r><w:t>Retained before cleanup.</w:t></w:r></w:p>${withTable ? '<w:tbl><w:tblPr/><w:tblGrid><w:gridCol w:w="4000"/></w:tblGrid><w:tr><w:tc><w:p><w:r><w:tab/></w:r></w:p></w:tc></w:tr></w:tbl>' : ''}<w:p><w:r><w:t>Retained after cleanup.</w:t></w:r></w:p></w:body></w:document>`)
  const bytes = await zip.generateAsync({ type: 'arraybuffer' }), hash = crypto.createHash('sha256').update(Buffer.from(bytes)).digest('hex')
  docxBytes.set(hash, bytes); return hash
}
const getDocument = (project, file) => servers.get(project).find(document => document.fileKey === file)
const label = text => ({ en: text, zhHans: text, zhHant: text })
const rejected = action => (...args) => { forbidden.push([action, ...args]); throw Error(`Unexpected ${action} in cleanup workspace fixture`) }
window.HTMLCanvasElement.prototype.getContext = function () { return { canvas: this } }
window.HTMLElement.prototype.scrollTo = function (position) { this.scrollTop = position.top; this.scrollLeft = position.left }
window.requestAnimationFrame = callback => setTimeout(callback, 0)
window.cancelAnimationFrame = clearTimeout
class ResizeObserver { observe() {} disconnect() {} }
const api = {
  catalog: async () => ({ ruleVersion: 'SYNTHETIC', groups: [{ id: 'identity', label: label('Identity'), fields: [{ key: 'projectName', kind: 'text', label: label('Project name') }] }] }),
  variables: async () => [{ key: 'projectName', value: 'SYNTHETIC adopted project', confirmed: true }],
  templates: async () => ['NTT', 'SCT', 'SCC'].map(key => ({ key, tag: 'ok' })), inputs: async () => [], extractTrace: async () => null,
  documents: async project => copy(servers.get(project)), plan: async () => ({ actions: [], unresolved: [] }),
  templateBindings: async (project, file) => ({ fileKey: file, view: 'source', sourceSha256: getDocument(project, file).sourceSha256, bindings: [] }),
  templateSource: async (project, file) => new Blob([docxBytes.get(getDocument(project, file).sourceSha256)]),
  documentBindings: async (project, file) => { const document = getDocument(project, file); return { fileKey: file, view: 'result', sourceSha256: document.sourceSha256, revisionId: document.revisionId, docxSha256: document.docxSha256, bindings: [] } },
  documentSource: async (project, file) => new Blob([docxBytes.get(getDocument(project, file).docxSha256)]),
  previewDocument: async (...args) => { previews.push(copy(args)); return new Blob(['SYNTHETIC PDF boundary']) },
  exportDocument: async (...args) => { exportCalls.push(copy(args)) },
  updateDocument: async (...args) => { writes.push(copy(args)); return await saveResponse(...args) },
  refreshToc: async (...args) => { tocCalls.push(copy(args)); return { document: copy(getDocument(args[0], args[1])), updated: false, tocFinalization: { status: 'PASS', headingFlowStatus: 'UNKNOWN' } } },
  generate: rejected('generation'), extract: rejected('extraction'), saveVariable: rejected('variable adoption')
}
const loaded = loadVue(path.join(__dirname, '../src/views/DraftingView.vue'), {
  globals: { window, document, HTMLElement, Element, Node, DOMParser: window.DOMParser, XMLSerializer: window.XMLSerializer, NodeFilter: window.NodeFilter, ResizeObserver, Blob, URL: { createObjectURL: () => `blob:SYNTHETIC-cleanup-${previews.length}`, revokeObjectURL() {} }, crypto: crypto.webcrypto, fetch: rejected('network fetch'), localStorage: { getItem: () => 'en', setItem() {} } },
  boundaries: {
    '@/api': { draftingApi: api, setLlmProfileResolver() {} }, '@/api/source-locations': { reviewSourceBindings: rejected('source location transport') },
    pinia: piniaRuntime, 'vue-i18n': require('vue-i18n'),
    jszip: { default: JSZip }, 'mammoth/mammoth.browser': { default: require('mammoth/mammoth.browser') }, 'mammoth/mammoth.browser.min.js': { default: require('mammoth/mammoth.browser.min.js') }, dompurify: { default: require('dompurify')(window) },
    'pdfjs-dist/build/pdf.worker.min.mjs?url': { default: 'external-worker' },
    'pdfjs-dist': { GlobalWorkerOptions: {}, getDocument({ url }) { pdfLoads.push(url); return { destroy: async () => {}, promise: Promise.resolve({ numPages: 1, destroy: async () => {}, getPage: async () => ({ getViewport: ({ scale }) => ({ width: 612 * scale, height: 792 * scale }), render: () => ({ promise: Promise.resolve(), cancel() {} }) }) }) } } }
  }
})
const pinia = piniaRuntime.createPinia(); piniaRuntime.setActivePinia(pinia)
const store = loaded.loadLocal(path.join(__dirname, '../src/stores/app.ts')).useAppStore()
const cache = loaded.loadLocal(path.join(__dirname, '../src/drafting/state.ts')).projectDrafts
const app = vue.createApp(loaded.default); app.use(pinia); app.use(loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n)
const workspace = () => document.querySelector('[data-document-workspace]')
const button = title => [...document.querySelectorAll('button')].find(node => node.textContent.trim() === title)
const mark = () => document.querySelector('[data-delete-empty-scope]')
async function settled() { await settle(); await new Promise(resolve => setTimeout(resolve, 35)); await settle() }
async function click(control) { const target = typeof control === 'string' ? button(control) : control; assert.ok(target, `Rendered control: ${control}`); assert.equal(target.disabled, false, `Enabled control: ${target.textContent.trim()}`); target.click(); await settled() }
async function waitFor(label, condition) { const deadline = performance.now() + 3000; while (performance.now() < deadline) { await settled(); if (condition()) return }; assert.ok(condition(), `Timed out: ${label}; ${workspace()?.textContent}`) }
function assertPendingLocks() {
  assert.ok(workspace().textContent.includes('Pending body changes'))
  assert.ok(button('Save content and preview')); assert.ok(button('Discard unsaved changes'))
  for (const name of ['Export Word', 'Export PDF', 'NTT', 'SCT', 'SCC']) assert.equal(button(name).disabled, true, `${name} is locked while cleanup is pending`)
  assert.equal(document.querySelector('[data-refresh-toc]').disabled, true)
  assert.equal(document.querySelector('[data-review-pdf]').disabled, true)
  assert.equal(document.querySelector('[data-cleanup-pending-count]').textContent.trim(), '1 cleanup selections pending save')
}

;(async () => {
  const originalHash = await docx(true), cleanedHash = await docx(false)
  for (const project of ['SYNTHETIC-CLEANUP-A', 'SYNTHETIC-CLEANUP-B']) servers.set(project, ['NTT', 'SCT', 'SCC'].map(file => ({ fileKey: file, title: `SYNTHETIC ${file}`, content: 'Retained before cleanup.\n\t\nRetained after cleanup.', generated: true, stale: false, sourceSha256: originalHash, revisionId: `${project}-${file}-saved-1`, docxSha256: originalHash,
    blocks: [{ id: `${root}/w:p[2]`, bindingId: 'CSretainedFixture', text: 'Retained before cleanup.', textHash: 'SYNTHETIC-retained', paragraphOrdinal: 2, editable: true }, { id: emptyId, bindingId: 'CSemptyFixture', text: '\t', textHash: 'SYNTHETIC-empty', paragraphOrdinal: 3, cellId: 'SYNTHETIC-cell', editable: true }], bodyCleanupScopes: file === 'SCC' && project.endsWith('-A') ? [copy(candidate)] : [] })))
  store.switchProject('SYNTHETIC-CLEANUP-A'); app.mount('#app'); await settled()
  await click('4 Preview and export'); await click('SCC')
  await waitFor('actual complete saved DOCX reader', () => workspace()?.querySelector('[data-document-paper]')?.textContent.includes('SYNTHETIC complete cleanup fixture'))
  assert.equal(document.querySelector('[data-refresh-toc]').disabled, false)
  assert.equal(document.querySelector('[data-review-pdf]').disabled, false)
  assert.equal(writes.length + previews.length + tocCalls.length + exportCalls.length, 0)
  await click('Edit content')
  assert.ok(mark()); assert.equal(document.querySelector('[data-cleanup-pending-count]').textContent.trim(), '0 cleanup selections pending save')
  assert.equal(button('Save content and preview'), undefined)
  await click(mark()); assertPendingLocks()
  for (const control of [button('Export Word'), button('Export PDF'), button('NTT'), document.querySelector('[data-refresh-toc]'), document.querySelector('[data-review-pdf]')]) control.click()
  await settled()
  assert.equal(writes.length + previews.length + tocCalls.length + exportCalls.length, 0, 'Scope marking and disabled controls cause no mutation, preview or export request')
  assert.ok(button('SCC').classList.contains('primary'))
  console.log('PASS: real View scope-only marking activates Save/Discard and locks file switching, PDF layout, TOC and both exports without immediate requests')

  const originalRevision = getDocument('SYNTHETIC-CLEANUP-A', 'SCC').revisionId
  store.switchProject('SYNTHETIC-CLEANUP-B'); await settled()
  assert.equal(workspace(), null)
  assert.equal(cache.get('SYNTHETIC-CLEANUP-A').documents.SCC.body.scopeDeletions[scopeId], true)
  assert.equal(cache.get('SYNTHETIC-CLEANUP-A').documents.SCC.body.revisionId, originalRevision)
  assert.equal(cache.get('SYNTHETIC-CLEANUP-B')?.documents?.SCC, undefined, 'A different project does not inherit the pending cleanup')
  store.switchProject('SYNTHETIC-CLEANUP-A'); await settled()
  await waitFor('cached cleanup selection on the original SCC', () => mark()?.textContent.trim() === 'Cancel scope deletion')
  assertPendingLocks(); assert.ok(button('SCC').classList.contains('primary'))
  assert.equal(writes.length, 0)
  console.log('PASS: project cache restores exact pending scope selection, revision and active SCC without saving or leaking to another project')

  const expectedPatch = { revisionId: originalRevision, docxSha256: originalHash, blocks: [], scopeEdits: [{ scopeId, expectedScopeHash: candidate.nodeSha256, operation: 'delete_empty_table', memberBindingIds: ['CSemptyFixture'] }] }
  await click('Save content and preview')
  assert.deepEqual(writes, [['SYNTHETIC-CLEANUP-A', 'SCC', expectedPatch]])
  assert.ok(document.querySelector('[role="alert"]')?.textContent.includes('SYNTHETIC save conflict'))
  assert.equal(getDocument('SYNTHETIC-CLEANUP-A', 'SCC').revisionId, originalRevision)
  assert.equal(cache.get('SYNTHETIC-CLEANUP-A').documents.SCC.body.scopeDeletions[scopeId], true)
  assertPendingLocks()
  await click('Discard unsaved changes')
  assert.equal(button('Save content and preview'), undefined)
  assert.equal(button('Discard unsaved changes'), undefined)
  assert.equal(cache.get('SYNTHETIC-CLEANUP-A').documents.SCC, undefined)
  assert.equal(document.querySelector('[data-refresh-toc]').disabled, false)
  assert.equal(button('NTT').disabled, false)
  assert.equal(writes.length, 1, 'Discard changes local draft state without issuing another save')
  console.log('PASS: failed save retains the exact pending scope and old saved head; explicit Discard clears the cache and unlocks controls without another write')

  await click('Edit content'); await click(mark())
  const gate = deferred(); saveResponse = () => gate.promise
  await click('Save content and preview')
  assert.equal(button('Discard unsaved changes').disabled, true)
  assert.equal(button('Save content and preview').disabled, true)
  assert.equal(mark().disabled, true)
  mark().click(); button('Save content and preview').click(); await settled()
  assert.equal(writes.length, 2, 'Busy cleanup controls cannot submit twice or cancel the in-flight scope')
  assert.deepEqual(writes[1], ['SYNTHETIC-CLEANUP-A', 'SCC', expectedPatch])
  const updated = { ...copy(getDocument('SYNTHETIC-CLEANUP-A', 'SCC')), content: 'Retained before cleanup.\nRetained after cleanup.', revisionId: 'SYNTHETIC-cleaned-saved-2', docxSha256: cleanedHash, blocks: [copy(getDocument('SYNTHETIC-CLEANUP-A', 'SCC').blocks[0])], bodyCleanupScopes: [], bodyCleanupReceipts: [{ scopeId, operation: 'delete_empty_table', status: 'SYNTHETIC-REMOVED' }] }
  servers.set('SYNTHETIC-CLEANUP-A', servers.get('SYNTHETIC-CLEANUP-A').map(document => document.fileKey === 'SCC' ? updated : document)); gate.resolve(copy(updated)); await settled()
  await waitFor('returned cleaned DOCX reader', () => workspace()?.querySelector('[data-document-paper]')?.textContent.includes('Retained after cleanup.') && !workspace()?.querySelector('[data-review-mode="saved"]')?.disabled)
  assert.ok(!workspace().textContent.includes('Pending body changes'))
  assert.equal(button('Save content and preview'), undefined)
  assert.equal(button('Discard unsaved changes'), undefined)
  assert.equal(cache.get('SYNTHETIC-CLEANUP-A').documents.SCC, undefined)
  assert.equal(previews.length, 0, 'Body save does not silently render a PDF')
  assert.equal(document.querySelector('[data-refresh-toc]').disabled, false)
  await click(document.querySelector('[data-review-mode="saved"]'))
  assert.equal(workspace().querySelector('[data-document-paper] table'), null, 'The reader uses the returned synthetic DOCX without its removed table')
  await click('Edit content'); assert.equal(document.querySelector('[data-cleanup-scope]'), null); await click('Read document')
  console.log('PASS: exact scope save remains busy until its response, then adopts the returned revision, clears pending selection/cache and reads the returned DOCX')

  await click(document.querySelector('[data-review-pdf]'))
  assert.deepEqual(previews, [['SYNTHETIC-CLEANUP-A', 'SCC', updated.revisionId]])
  assert.equal(pdfLoads.length, 1)
  await click('Close layout check')
  await click('Export Word'); await click('Export PDF')
  assert.deepEqual(exportCalls, [['SYNTHETIC-CLEANUP-A', 'SCC', 'docx', updated.revisionId], ['SYNTHETIC-CLEANUP-A', 'SCC', 'pdf', updated.revisionId]])
  await click(document.querySelector('[data-refresh-toc]'))
  assert.deepEqual(tocCalls, [['SYNTHETIC-CLEANUP-A', 'SCC', { revisionId: updated.revisionId, docxSha256: cleanedHash }]])
  await click('SCT'); await click('SCC')
  assert.equal(button('Save content and preview'), undefined)
  assert.equal(writes.length, 2); assert.equal(forbidden.length, 0)
  assert.equal(cache.get('SYNTHETIC-CLEANUP-A').values.projectName, 'SYNTHETIC adopted project')
  console.log('PASS: after cleanup save, explicit PDF, both exports and SCC TOC use the returned saved head; file navigation preserves clean state and adopted values')
  app.unmount()
})().catch(error => { console.error(error); process.exitCode = 1; app.unmount() })
