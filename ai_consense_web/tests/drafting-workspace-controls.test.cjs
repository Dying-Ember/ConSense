/* Actual mounted DraftingView and child controls. Transport/PDF.js are external
   boundaries; synthetic fixtures do not establish native Word or browser layout. */
const assert = require('node:assert/strict')
const path = require('node:path')
const { JSDOM } = require('jsdom')
const window = new JSDOM('<button id="application-control">Application control</button><main id="app"></main>').window
for (const key of ['window', 'document', 'Element', 'HTMLElement', 'SVGElement', 'Node', 'Document', 'ShadowRoot']) globalThis[key] = key === 'window' ? window : window[key]
const vue = require('vue'), piniaRuntime = require('pinia')
const { loadVue } = require('./helpers/load-vue.cjs')
const { settle } = require('./helpers/render-controls.cjs')
const JSZip = require('jszip'), crypto = require('node:crypto')
const nativePath = 'word/document.xml#/w:document[1]/w:body[1]/w:p[2]'
const docxBytes = new Map()
async function docx(text) {
  const zip = new JSZip(), safe = text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  zip.file('[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
  zip.file('_rels/.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
  zip.file('word/document.xml', `<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>SYNTHETIC complete document heading</w:t></w:r></w:p><w:p><w:r><w:t xml:space="preserve">${safe}</w:t></w:r></w:p></w:body></w:document>`)
  const bytes = await zip.generateAsync({ type: 'arraybuffer' }), hash = crypto.createHash('sha256').update(Buffer.from(bytes)).digest('hex')
  docxBytes.set(hash, bytes); return hash
}
window.HTMLCanvasElement.prototype.getContext = function () { return { canvas: this } }
window.HTMLElement.prototype.scrollTo = function (position) { this.scrollTop = position.top; this.scrollLeft = position.left }
// Visible boxes are a browser-layout boundary; JSDOM does not calculate them.
window.HTMLElement.prototype.getClientRects = function () {
  for (let ancestor = this; ancestor; ancestor = ancestor.parentElement) if (ancestor.style.display === 'none') return []
  const closedDetails = this.closest('details:not([open])')
  return closedDetails && this !== closedDetails.querySelector('summary') ? [] : [{ width: 100, height: 30 }]
}
window.requestAnimationFrame = callback => setTimeout(callback, 0)
window.cancelAnimationFrame = clearTimeout
class ResizeObserver { observe() {} disconnect() {} }
const label = text => ({ en: text, zhHans: text, zhHant: text })
const targetAction = { id: 'SYNTHETIC-target', document: 'NTT', clause: 'SYNTHETIC clause', paragraphs: '2', action: 'amend', detail: label('SYNTHETIC unresolved target'), inputKeys: [] }
const unresolvedTarget = { id: targetAction.id, kind: 'SourceTarget', document: 'NTT', clause: targetAction.clause, inputKeys: [], message: label('SYNTHETIC unresolved target') }
const baseDocument = {
  fileKey: 'NTT', title: 'SYNTHETIC contract', content: 'Saved paragraph.', generated: true, stale: false,
  snapshotId: 'SYNTHETIC-snapshot', revisionId: 'SYNTHETIC-revision-1', docxSha256: 'SYNTHETIC-docx-1', pdfSha256: 'SYNTHETIC-pdf-1', renderProfileHash: 'SYNTHETIC-profile',
  unresolved: [unresolvedTarget], blocks: [{ id: 'source/p[2]', text: 'Saved paragraph.', textHash: 'SYNTHETIC-text-1', paragraphOrdinal: 2, editable: true }]
}
const clone = value => JSON.parse(JSON.stringify(value))
const servers = new Map(), writes = [], previews = [], exportCalls = [], loads = [], renders = []
const server = id => { if (!servers.has(id)) servers.set(id, clone(baseDocument)); return servers.get(id) }
let failedWrite = false
const api = {
  catalog: async () => ({ ruleVersion: 'SYNTHETIC', groups: [{ id: 'identity', label: label('Identity'), fields: [{ key: 'projectName', kind: 'text', label: label('Project name') }] }] }),
  variables: async () => [{ key: 'projectName', value: 'SYNTHETIC project', confirmed: true }],
  templates: async () => ['NTT', 'SCT', 'SCC'].map(key => ({ key, tag: 'ok' })), inputs: async () => [], extractTrace: async () => null,
  documents: async id => [clone(server(id))], plan: async () => ({ actions: [clone(targetAction)], unresolved: [clone(unresolvedTarget)] }),
  templateBindings: async (_id, file) => ({ fileKey: file, view: 'source', sourceSha256: baseDocument.sourceSha256, bindings: [] }),
  templateSource: async () => new Blob([docxBytes.get(baseDocument.sourceSha256)]),
  documentBindings: async (id, file) => ({ fileKey: file, view: 'result', sourceSha256: baseDocument.sourceSha256, docxSha256: server(id).docxSha256, revisionId: server(id).revisionId, bindings: [] }),
  documentSource: async id => new Blob([docxBytes.get(server(id).docxSha256)]),
  previewDocument: async (...args) => { previews.push(args); return new Blob(['SYNTHETIC PDF boundary']) },
  updateDocument: async (id, key, patch) => {
    writes.push([id, key, clone(patch)])
    if (failedWrite) throw new Error('SYNTHETIC revision conflict')
    const current = server(id), revised = patch.blocks[0].text
    Object.assign(current, { content: revised, revisionId: 'SYNTHETIC-revision-2', docxSha256: await docx(revised), pdfSha256: 'SYNTHETIC-pdf-2' })
    current.blocks[0].text = revised; current.blocks[0].textHash = 'SYNTHETIC-text-2'
    return clone(current)
  },
  exportDocument: async (...args) => { exportCalls.push(args) },
  generate: async () => { throw new Error('Workspace navigation must not generate') }, extract: async () => { throw new Error('Workspace navigation must not extract') }
}
const loaded = loadVue(path.join(__dirname, '../src/views/DraftingView.vue'), {
  globals: { window, document, HTMLElement, Element, Node, DOMParser: window.DOMParser, XMLSerializer: window.XMLSerializer, NodeFilter: window.NodeFilter, ResizeObserver, Blob, URL: { createObjectURL: () => `blob:SYNTHETIC-${previews.length}`, revokeObjectURL() {} }, crypto: crypto.webcrypto, localStorage: { getItem: () => 'en', setItem() {} } },
  boundaries: {
    '@/api': { draftingApi: api, setLlmProfileResolver() {} }, pinia: piniaRuntime, 'vue-i18n': require('vue-i18n'),
    'mammoth/mammoth.browser': { default: require('mammoth/mammoth.browser') },
    jszip: { default: JSZip }, 'mammoth/mammoth.browser.min.js': { default: require('mammoth/mammoth.browser.min.js') }, dompurify: { default: require('dompurify')(window) },
    'pdfjs-dist/build/pdf.worker.min.mjs?url': { default: 'external-worker' },
    'pdfjs-dist': { GlobalWorkerOptions: {}, getDocument({ url }) {
      loads.push(url)
      return { destroy: async () => {}, promise: Promise.resolve({ numPages: 8, destroy: async () => {}, getPage: async page => ({
        getViewport: ({ scale }) => ({ width: 612 * scale, height: 792 * scale }),
        render() { renders.push([url, page]); return { promise: Promise.resolve(), cancel() {} } }
      }) }) }
    } }
  }
})
const pinia = piniaRuntime.createPinia(); piniaRuntime.setActivePinia(pinia)
const store = loaded.loadLocal(path.join(__dirname, '../src/stores/app.ts')).useAppStore()
store.switchProject('SYNTHETIC-P1')
const app = vue.createApp(loaded.default); app.use(pinia); app.use(loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n)
function button(title, within = document) { return [...within.querySelectorAll('button')].find(node => node.textContent.trim() === title) }
async function settled() { await settle(); await new Promise(resolve => setTimeout(resolve, 35)); await settle() }
async function waitFor(description, condition) {
  const deadline = performance.now() + 2000
  while (performance.now() < deadline) {
    await settle()
    if (condition()) return
    await new Promise(resolve => setTimeout(resolve, 5))
  }
  const statuses = [...(workspace()?.querySelectorAll('[role="status"], [role="alert"]') ?? [])].map(node => node.textContent).join(' | ')
  assert.ok(condition(), `Timed out waiting for ${description}; visible status: ${statuses || 'none'}`)
}
async function click(title, within = document) { const target = button(title, within); assert.ok(target, `Visible control: ${title}`); assert.equal(target.disabled, false, `Enabled control: ${title}`); target.click(); await settled() }
function input(element, value) { assert.ok(element, 'Visible input'); element.value = value; element.dispatchEvent(new window.Event('input', { bubbles: true })) }
function escape() { window.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true })) }
function keydown(element, key, shiftKey = false) { const event = new window.KeyboardEvent('keydown', { key, shiftKey, bubbles: true, cancelable: true }); element.dispatchEvent(event); return event }
const workspace = () => document.querySelector('[data-document-workspace]')

;(async () => {
  baseDocument.docxSha256 = await docx(baseDocument.content); baseDocument.sourceSha256 = baseDocument.docxSha256
  baseDocument.blocks[0].id = nativePath
  servers.clear()
  app.mount('#app'); await settled(); await click('3. Preview and export')
  await waitFor('the actual complete DOCX in the primary view', () => workspace()?.querySelector('[data-document-paper]')?.textContent.includes('SYNTHETIC complete document heading'))
  const paper = workspace().querySelector('[data-document-paper]'), loadCount = loads.length
  assert.ok(paper?.textContent.includes('SYNTHETIC complete document heading'), 'The primary view loads the actual full DOCX')
  assert.equal(document.querySelector('canvas'), null, 'PDF rendering is an explicit optional layout check')
  assert.equal(previews.length, 0)
  assert.equal(workspace().querySelector('.draft-unresolved').open, true, 'Ordinary preview keeps existing review items visible')
  await click('Expand document')
  assert.equal(workspace().getAttribute('role'), 'dialog')
  assert.equal(workspace().getAttribute('aria-modal'), 'true')
  assert.equal(workspace().querySelector('[data-document-paper]'), paper, 'Expanding retains the mounted native reading')
  assert.equal(loads.length, loadCount, 'Expanding does not start PDF conversion or rendering')
  const reviewItems = workspace().querySelector('.draft-unresolved')
  assert.equal(reviewItems.open, false, 'Expanded reading starts with review items collapsed')
  assert.ok(workspace().querySelector('.review-inspector'), 'The native reader has a separate read-only evidence panel')
  assert.equal(workspace().querySelector('.document-info').style.display, 'none', 'Expanded reading moves revision metadata out of the document flow')
  await click('Document information')
  assert.notEqual(workspace().querySelector('.document-info').style.display, 'none')
  const informationToggle = button('Document information', workspace())
  const versionSummary = workspace().querySelector('.document-versions summary')
  versionSummary.focus(); keydown(versionSummary, 'Escape'); await settled()
  assert.equal(workspace().getAttribute('role'), 'dialog', 'Escape closes the information panel before leaving expanded view')
  assert.equal(workspace().querySelector('.document-info').style.display, 'none')
  assert.equal(document.activeElement, informationToggle, 'Closing focused document information restores its visible opener')
  reviewItems.querySelector('summary').click(); await settled()
  assert.equal(reviewItems.open, true, 'Review items remain explicitly accessible while expanded')
  assert.ok(reviewItems.textContent.includes('SYNTHETIC unresolved target'))
  const pendingAction = button('Adopt a manual change for this target', reviewItems)
  pendingAction.focus(); keydown(pendingAction, 'Escape'); await settled()
  assert.equal(reviewItems.open, false)
  assert.equal(document.activeElement, reviewItems.querySelector('summary'), 'Closing focused pending content restores its visible summary')
  escape(); await settled()
  assert.equal(workspace().getAttribute('role'), null)
  assert.equal(workspace().querySelector('[data-document-paper]'), paper)
  assert.equal(document.activeElement, button('Expand document'), 'Escape restores keyboard focus to the expansion control')
  assert.equal(workspace().querySelector('.draft-unresolved').open, true, 'Returning to ordinary preview restores its existing expanded review presentation')
  assert.equal(writes.length, 0); assert.equal(exportCalls.length, 0)
  console.log('PASS: mounted native document expands without reopening an artifact, exits on Escape and restores focus without writes or exports')
  assert.ok(workspace().textContent.includes('Saved revision'), 'The saved artifact revision is identified explicitly')
  await click('Edit content'); await click('Edit paragraph')
  const revised = '  Pending exact\tparagraph.\nSecond line.  '
  input(document.getElementById('body-p-2'), revised); await settled()
  await click('Expand document')
  assert.ok(workspace().textContent.includes('Pending body changes'), 'Unsaved body text is labeled as pending rather than assigned to the saved PDF revision')
  assert.ok(workspace().textContent.includes('SYNTHETIC-revision-1'))
  assert.equal(workspace().querySelector('canvas'), null, 'An old saved PDF is not displayed as the current pending body')
  assert.equal(button('Export PDF').disabled, true); assert.equal(button('NTT', workspace()).disabled, true)
  escape(); await settled()
  assert.equal(document.getElementById('body-p-2').value, revised, 'Escape leaves exact pending body text untouched')
  assert.equal(writes.length, 0)
  store.switchProject('SYNTHETIC-P2'); await settled()
  assert.equal(workspace(), null, 'Switching projects closes immersive view and does not show the prior project workspace')
  store.switchProject('SYNTHETIC-P1'); await settled()
  await waitFor('the restored pending body after returning to its project', () => workspace()?.textContent.includes(revised))
  assert.ok(workspace().textContent.includes(revised), 'Returning to the project restores the pending body')
  assert.ok(workspace().textContent.includes('Pending body changes'))
  assert.equal(writes.length, 0, 'Exiting and switching projects do not save or discard body edits')
  console.log('PASS: pending body identity is distinct from the saved revision; Escape and project switching retain exact dirty edits and block old PDF/export')
  await click('Expand document'); failedWrite = true
  await click('Save content and preview')
  assert.ok(workspace().querySelector('[role="alert"]')?.textContent.includes('SYNTHETIC revision conflict'), 'A save conflict is visible inside the expanded workspace')
  assert.ok(workspace().textContent.includes(revised), 'A failed save leaves the exact pending body available to retry')
  assert.equal(workspace().querySelector('canvas'), null)
  assert.deepEqual(writes[0], ['SYNTHETIC-P1', 'NTT', { revisionId: 'SYNTHETIC-revision-1', docxSha256: baseDocument.docxSha256, blocks: [{ id: nativePath, expectedTextHash: 'SYNTHETIC-text-1', text: revised }] }])
  failedWrite = false; await click('Save content and preview')
  assert.ok(workspace().textContent.includes('Saved revision: SYNTHETIC-revision-2'))
  assert.ok(!workspace().textContent.includes('Pending body changes'))
  assert.ok(workspace().textContent.includes(server('SYNTHETIC-P1').docxSha256)); assert.ok(workspace().textContent.includes('SYNTHETIC-pdf-2')); assert.ok(workspace().textContent.includes('SYNTHETIC-profile'))
  assert.equal(previews.length, 0, 'Saving does not silently request an optional PDF')
  await waitFor('the returned saved DOCX ready for reading', () => {
    const control = workspace()?.querySelector('[data-review-mode="saved"]')
    return control && !control.disabled
  })
  const savedMode = workspace().querySelector('[data-review-mode="saved"]')
  assert.ok(savedMode && !savedMode.disabled); savedMode.click(); await settled()
  assert.ok(workspace().querySelector('[data-document-paper]').textContent.includes('Pending exact'), 'Successful save reads the returned DOCX revision')
  const layoutInvoker = workspace().querySelector('[data-review-pdf]'); layoutInvoker.focus(); layoutInvoker.click(); await settled()
  const layout = document.querySelector('.layout-check[role="dialog"]')
  assert.ok(layout, 'An explicit layout check opens a separate modal')
  assert.equal(previews.at(-1)[2], 'SYNTHETIC-revision-2', 'The layout check requests the returned saved revision')
  assert.ok(layout.querySelector('canvas'))
  keydown(layout, 'Escape'); await settled()
  assert.equal(document.querySelector('.layout-check'), null)
  assert.equal(workspace().getAttribute('aria-modal'), 'true', 'Closing the PDF child leaves expanded native reading open')
  assert.equal(document.activeElement, layoutInvoker, 'Closing the PDF check restores its invoker')
  await click('Export PDF'); await click('Export Word')
  assert.deepEqual(exportCalls, [['SYNTHETIC-P1', 'NTT', 'pdf', 'SYNTHETIC-revision-2'], ['SYNTHETIC-P1', 'NTT', 'docx', 'SYNTHETIC-revision-2']])
  assert.equal(server('SYNTHETIC-P1').content, revised)
  console.log('PASS: expanded save conflicts stay visible without losing exact dirty text; retry, PDF preview and both exports use the returned saved revision and retain hash/profile metadata')
  const words = loaded.loadLocal(path.join(__dirname, '../src/drafting/words.ts'))
  for (const locale of ['zh-Hans', 'zh-Hant', 'en']) {
    store.locale = locale; await settled()
    assert.ok(workspace().textContent.includes(words.draftWord('savedBodyRevision', locale)))
    await click(words.draftWord('exitImmersive', locale))
    await click(words.draftWord('immersivePreview', locale))
    assert.ok(workspace().querySelector('[data-document-paper]').textContent.includes('Pending exact'))
  }
  assert.equal(writes.length, 2); assert.equal(exportCalls.length, 2, 'Three-language view navigation does not repeat writes or exports')
  console.log('PASS: expanded controls and saved-revision feedback localize in three languages while preserving the returned native document and causing no extra write or export')
  const firstControl = button('NTT', workspace()), lastControl = button('Regenerate', workspace())
  firstControl.focus()
  assert.equal(keydown(firstControl, 'Tab', true).defaultPrevented, true, 'Shift+Tab at the first workspace control cannot reach the background application')
  assert.equal(document.activeElement, lastControl)
  assert.equal(keydown(lastControl, 'Tab').defaultPrevented, true)
  assert.equal(document.activeElement, firstControl, 'Tab at the last control cycles to the first visible enabled workspace control')
  const applicationControl = document.getElementById('application-control'); applicationControl.focus()
  assert.equal(keydown(applicationControl, 'Tab').defaultPrevented, true)
  assert.equal(document.activeElement, firstControl, 'Tab from background focus returns into the modal workspace')
  await click('Edit content'); await click('Edit paragraph')
  const pendingKeyboardText = '  Keyboard pending\tparagraph.  '
  input(document.getElementById('body-p-2'), pendingKeyboardText); await settled()
  const firstPendingControl = button('Discard unsaved changes', workspace()), lastPendingControl = button('Adopt a manual change for this target', workspace()).closest('details').querySelector('summary')
  if (lastPendingControl.parentElement.open) { lastPendingControl.click(); await settled() }
  assert.equal(lastPendingControl.parentElement.open, false, 'A collapsed pending-target section leaves only its summary visible')
  firstPendingControl.focus(); keydown(firstPendingControl, 'Tab', true)
  assert.equal(document.activeElement, lastPendingControl, 'Cycling skips disabled tabs, exports, regeneration and controls inside collapsed details')
  keydown(lastPendingControl, 'Tab')
  assert.equal(document.activeElement, firstPendingControl)
  assert.equal(document.getElementById('body-p-2').value, pendingKeyboardText)
  const handledEscape = new window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }); handledEscape.preventDefault()
  document.getElementById('body-p-2').dispatchEvent(handledEscape); await settled()
  assert.equal(workspace().getAttribute('aria-modal'), 'true', 'An already-handled Escape remains owned by its control')
  keydown(document.getElementById('body-p-2'), 'Escape'); await settled()
  assert.equal(workspace().getAttribute('role'), null)
  assert.equal(document.activeElement, button('Expand document'))
  assert.equal(document.getElementById('body-p-2').value, pendingKeyboardText, 'Keyboard exit never discards body changes')
  assert.equal(writes.length, 2); assert.equal(exportCalls.length, 2)
  console.log('PASS: modal Tab boundaries cycle visible enabled controls, recover background focus, respect handled keys and preserve dirty text/Escape focus restoration')
  await click('Expand document')
  const targetControl = button('Adopt a manual change for this target', workspace())
  targetControl.closest('details').querySelector('summary').click(); await settled()
  targetControl.focus(); keydown(targetControl, 'Enter')
  await click('Adopt a manual change for this target', workspace())
  const childDialog = document.querySelector('.modal-backdrop.show [role="dialog"]'), childEditor = childDialog?.querySelector('textarea')
  const childClose = childDialog.querySelector('button[aria-label="Close"]')
  assert.equal(document.activeElement, childClose, 'Keyboard opening a pending target enters the first enabled child dialog control')
  input(childEditor, '  Child pending target.  '); await settled(); childEditor.focus()
  assert.equal(keydown(childEditor, 'Escape').defaultPrevented, false, 'The workspace does not consume a child dialog Escape')
  await settled()
  assert.equal(workspace().getAttribute('aria-modal'), 'true', 'A child dialog owns Escape while the expanded workspace stays open')
  assert.equal(document.activeElement, childEditor)
  assert.equal(keydown(childEditor, 'Tab').defaultPrevented, false, 'The workspace does not pull a child dialog Tab back into its controls')
  assert.equal(document.activeElement, childEditor)
  assert.equal(childEditor.value, '  Child pending target.  ')
  assert.equal(document.getElementById('body-p-2').value, pendingKeyboardText)
  const childLast = [...childDialog.querySelectorAll('button, input, select, textarea')].filter(control => !control.disabled).at(-1)
  childLast.focus()
  assert.equal(keydown(childLast, 'Tab').defaultPrevented, true, 'Tab at the child boundary cannot reach workspace or application controls')
  assert.equal(document.activeElement, childClose)
  assert.equal(keydown(childClose, 'Tab', true).defaultPrevented, true)
  assert.equal(document.activeElement, childLast, 'Shift+Tab cycles within the active child dialog')
  targetControl.focus()
  assert.equal(keydown(targetControl, 'Tab').defaultPrevented, true, 'Background focus is recovered into the active child dialog')
  assert.equal(document.activeElement, childClose)
  assert.equal(childEditor.value, '  Child pending target.  ')
  assert.equal(document.getElementById('body-p-2').value, pendingKeyboardText)
  assert.equal(writes.length, 2); assert.equal(exportCalls.length, 2, 'Child focus navigation performs no API writes or exports')
  childClose.click(); await settled()
  assert.equal(document.activeElement, targetControl, 'Explicit child close restores the pending target invoker')
  document.getElementById('body-p-2').focus(); keydown(document.getElementById('body-p-2'), 'Escape'); await settled()
  assert.equal(workspace().getAttribute('role'), 'dialog', 'Escape dismisses the pending-target overlay before leaving the document')
  assert.equal(workspace().querySelector('.draft-unresolved').open, false)
  keydown(document.getElementById('body-p-2'), 'Escape'); await settled()
  assert.equal(workspace().getAttribute('role'), null)
  assert.equal(document.activeElement, button('Expand document'))
  assert.equal(document.getElementById('body-p-2').value, pendingKeyboardText)
  assert.equal(writes.length, 2); assert.equal(exportCalls.length, 2)
  console.log('PASS: public target opening autofocuses its child, contains boundary Tab and restores its invoker on explicit close; middle Tab/Escape ownership and both pending texts remain unchanged without writes')
  app.unmount()
})().catch(error => { console.error(error); process.exitCode = 1; app.unmount() })
