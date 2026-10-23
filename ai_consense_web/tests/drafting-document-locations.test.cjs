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
let original, result, guard = '', slowSource, slowResult
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
  reviewHead: async () => ({ currentRevisionId: null, draftRevisionId: null, current: null, draft: null }),
  catalog: async () => ({ ruleVersion: 'SYNTHETIC', groups: [{ id: 'scope', label: label('Scope'), fields }] }), variables: async () => clone(variables),
  templates: async () => ['NTT', 'SCT', 'SCC'].map(key => ({ key, tag: 'ok' })), inputs: async () => [], extractTrace: async () => clone(extractionTrace),
  documents: async () => ['NTT', 'SCT', 'SCC'].map(fileKey => ({ ...clone(saved), fileKey })), plan: async () => ({ actions, unresolved: [] }),
  templateBindings: async (...args) => { calls.push(['source-bindings', ...args]); const value = bundle('source', args[1]); if (guard === 'source') value.sourceSha256 = 'wrong-source'; return value },
  templateSource: async (...args) => { calls.push(['source-docx', ...args]); return args[0] === 'SYNTHETIC-LATE-SOURCE' ? slowSource.promise : new Blob([original.bytes]) },
  documentBindings: async (...args) => { calls.push(['result-bindings', ...args]); const value = bundle('result', args[1]); if (guard === 'revision') value.revisionId = 'wrong-revision'; if (guard === 'result') value.docxSha256 = 'wrong-result'; return args[0] === 'SYNTHETIC-LATE-RESULT' ? slowResult.promise : value },
  documentSource: async (...args) => { calls.push(['result-docx', ...args]); return new Blob([result.bytes]) },
  templatePreview: async () => { throw new Error('Ordinary native navigation must not render PDF') }, previewDocument: async () => { throw new Error('Ordinary native navigation must not render PDF') },
  updateVariable: async (...args) => { writes.push(args); throw new Error('Navigation must not adopt or save an input') },
  updateDocument: async (...args) => { writes.push(args); throw new Error('Navigation must not save body text') },
  exportDocument: async (...args) => { writes.push(args); throw new Error('Navigation must not export') },
  generate: async () => { throw new Error('Navigation must not generate') }, extract: async () => { throw new Error('Navigation must not extract') }
}
const loaded = loadVue(path.join(__dirname, '../src/views/DraftingView.vue'), {
  globals: { window, document, Element, HTMLElement, Node, DOMParser: window.DOMParser, XMLSerializer: window.XMLSerializer, NodeFilter: window.NodeFilter, ResizeObserver, Blob, crypto: crypto.webcrypto, URL: { createObjectURL: () => 'blob:SYNTHETIC', revokeObjectURL() {} }, localStorage: { getItem: () => 'en', setItem() {} } },
  boundaries: { '@/api/source-locations': { reviewSourceBindings: async () => { throw Error('Source location transport not provided by this fixture') } }, '@/api': { draftingApi: api, setLlmProfileResolver() {} }, pinia: piniaRuntime, 'vue-i18n': require('vue-i18n'), jszip: { default: JSZip }, dompurify: { default: require('dompurify')(window) }, 'mammoth/mammoth.browser.min.js': { default: require('mammoth/mammoth.browser.min.js') }, 'mammoth/mammoth.browser': { default: require('mammoth/mammoth.browser') }, 'pdfjs-dist/build/pdf.worker.min.mjs?url': { default: 'external-worker' }, 'pdfjs-dist': { GlobalWorkerOptions: {}, getDocument() { throw new Error('Native location navigation must not open a PDF') } } }
})
const pinia = piniaRuntime.createPinia(); piniaRuntime.setActivePinia(pinia)
const store = loaded.loadLocal(path.join(__dirname, '../src/stores/app.ts')).useAppStore(); store.switchProject('SYNTHETIC-BOUND-P1')
const app = vue.createApp(loaded.default); app.use(pinia); app.use(loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n)
const workspace = () => document.querySelector('[data-document-workspace]'), paper = () => workspace()?.querySelector('[data-document-paper]')
async function settled() { await settle(); await new Promise(resolve => setTimeout(resolve, 40)); await settle() }
async function until(predicate) { for (let n = 0; n < 100; n++) { await settled(); if (predicate()) return }; throw new Error(`Rendered state did not arrive: ${workspace()?.textContent}`) }
function button(title, within = document) { return [...within.querySelectorAll('button')].find(node => node.textContent.trim() === title) }
async function click(selector) { const control = document.querySelector(selector); assert.ok(control, `Public control ${selector}`); assert.equal(control.disabled, false); control.click(); await settled() }
async function clickTitle(title) { const control = button(title); assert.ok(control, `Public control ${title}`); assert.equal(control.disabled, false); control.click(); await settled() }
async function project(id, expectedError = false) { store.switchProject(id); await settled(); await clickTitle('4 Preview and export'); if (expectedError) await until(() => workspace()?.querySelector('[role="alert"]')); else await until(() => paper()?.querySelector('[data-native-paragraph]')) }

;(async () => {
  original = await docx(sourceText); result = await docx(resultText)
  Object.assign(saved, { sourceSha256: original.sha, docxSha256: result.sha, blocks: [{ id: nativePath(2), bindingId: rows[0].bindingId, text: resultText[1], textHash: hash(resultText[1]), paragraphOrdinal: 2, editable: true }] })
  app.mount('#app'); await settled(); await clickTitle('4 Preview and export'); await until(() => paper()?.querySelector('[data-review-binding="SYNTHETIC-b1"]'))
  assert.match(paper().textContent, /SYNTHETIC complete opening context/); assert.match(paper().textContent, /SYNTHETIC complete ending context/)
  assert.equal(paper().querySelectorAll('[data-native-paragraph]').length, sourceText.length)
  assert.ok(calls.some(call => call[0] === 'result-bindings' && call[3] === saved.revisionId && call[4] === result.sha), 'Saved metadata is requested with the exact revision and real SHA')
  assert.ok(calls.some(call => call[0] === 'result-docx' && call[3] === saved.revisionId), 'Actual saved bytes are requested by their revision')
  assert.equal(paper().querySelector('del').textContent, '[choice]'); assert.equal(paper().querySelector('ins').textContent, 'A')
  for (const id of ['SYNTHETIC-ordinary', 'SYNTHETIC-cleanup', 'SYNTHETIC-unknown']) assert.equal(paper().querySelector(`[data-review-binding="${id}"]`), null, 'Unlinked or retired fields do not annotate ordinary text')
  assert.equal(paper().querySelectorAll('del,ins').length, 2, 'Only actual changed tokens are coloured')
  await click('[data-review-location="SYNTHETIC-b2"]')
  await click('[data-review-mode="original"]')
  assert.equal(paper().querySelector('[data-review-binding="SYNTHETIC-b2"]').classList.contains('review-focused'), true, 'Mode changes retain the same native target')
  assert.equal(paper().querySelectorAll('del,ins').length, 0)
  await click('[data-review-mode="saved"]')
  assert.equal(paper().querySelector('[data-review-binding="SYNTHETIC-b2"]').classList.contains('review-focused'), true)
  assert.match(paper().textContent, /Tender route A applies/)
  assert.equal(document.querySelector('canvas'), null)
  assert.equal(writes.length, 0)
  console.log('PASS: real DOCX bytes and guarded revisions support full original/review/saved context, actual token differences and native target retention without PDF or writes')

  await click('[data-review-mode="review"]')
  const shared = paper().querySelector('[data-review-binding="SYNTHETIC-b1"]')
  shared.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true })); await settled()
  assert.equal(document.activeElement, workspace().querySelector('.review-inspector'), 'Keyboard native reverse selection moves focus into the inspector')
  assert.equal(workspace().querySelector('[data-review-field-picker]').dataset.selectedId, 'foundationIncluded', 'The verified saved action selects its sole actual input rather than a broad registered association')
  const changeContext = workspace().querySelector('[data-change-context]'); assert(changeContext, 'The clicked saved action exposes its evidence without another selection')
  assert.equal(changeContext.querySelectorAll('[data-change-variable]').length, 1)
  assert.ok(changeContext.querySelector('[data-change-variable="foundationIncluded"]'))
  assert.match(changeContext.textContent, /SYNTHETIC-correspondence\.docx/)
  assert.ok(changeContext.textContent.includes(quote), 'The original recorded quote is immediately visible for the actual adopted input')
  assert.equal(workspace().querySelector('[data-association-choice="periodAtLeast39Months"]'), null, 'The unused broad association is not attributed to the saved amendment')
  await click('[data-review-field-picker] [data-picker-option="periodAtLeast39Months"]')
  assert.equal(workspace().querySelector('[data-change-context]'), null, 'Independent input inspection ends the saved-action evidence context')
  assert.match(workspace().querySelector('[data-current-value]').textContent, /Unknown/)
  await click('[data-review-field="foundationIncluded"]')
  assert.equal(workspace().querySelectorAll('[data-review-location-file="NTT"]').length, 2)
  const evidence = workspace().querySelector('.review-evidence')
  assert.equal(evidence.querySelector('blockquote').textContent, quote)
  assert.match(evidence.textContent, /SYNTHETIC-correspondence.docx/)
  const recordedContext = evidence.querySelector('[data-evidence-context]'); assert(recordedContext, 'Parent extraction trace reaches the readonly reader through the workspace')
  recordedContext.querySelector('summary').click(); await settled(); assert.equal(recordedContext.open, true)
  assert.equal(recordedContext.querySelector('[data-evidence-context-text]').textContent, sourceContext, 'Before/after context is the identity-matched accepted record')
  assert.ok(!paper().textContent.includes(quote), 'Correspondence evidence is separate from the template and saved contract')
  await click('[data-review-location-file="SCT"]'); await until(() => workspace()?.querySelector('[data-review-location-file="SCT"][data-review-location="SYNTHETIC-b1"]'))
  assert.equal(workspace().querySelector('[data-review-field-picker]').dataset.selectedId, 'foundationIncluded', 'Cross-file navigation retains the chosen input')
  assert.ok(button('SCT', workspace()).classList.contains('primary'))
  await click('[data-return-input]')
  assert.equal(workspace(), null)
  assert.equal(document.activeElement, document.querySelector('#input-foundationIncluded'), 'Return to the existing step-2 control restores edit focus')
  assert.equal(document.querySelector('#input-foundationIncluded').value, 'true')
  const phone = document.querySelector('#input-contactTelephone'); phone.value = '  +852 1234 5678  '; phone.dispatchEvent(new window.Event('input', { bubbles: true })); await settled()
  await clickTitle('4 Preview and export'); await until(() => paper()?.querySelector('[data-native-paragraph]'))
  await click('[data-review-field="contactTelephone"]')
  assert.match(workspace().querySelector('[data-current-value]').textContent, /\+852 1234 5678/)
  assert.equal(workspace().querySelector('[data-current-value] input'), null, 'Preview value is read-only')
  assert.match(workspace().textContent, /Unsaved variable/)
  assert.ok(!paper().textContent.includes('+852'), 'Unsaved manual input is never invented as a saved document amendment')
  await click('[data-return-input]'); assert.equal(document.querySelector('#input-contactTelephone').value, '  +852 1234 5678  ')
  assert.equal(writes.length, 0)
  console.log('PASS: keyboard reverse navigation, shared-field choice, source evidence, cross-file focus and return to step 2 preserve exact dirty values without adoption or saves')

  for (const kind of ['source', 'revision', 'result']) {
    guard = kind; await project(`SYNTHETIC-GUARD-${kind}`, true)
    assert.match(workspace().querySelector('[role="alert"]').textContent, /version|revision/i)
    assert.equal(paper(), null, 'Mismatched source/revision/DOCX receipt cannot publish an annotated document')
  }
  guard = ''; await click('[role="alert"] button'); await until(() => paper()?.querySelector('[data-native-paragraph]'))
  assert.match(paper().textContent, /complete ending context/)
  const before = clone(rows[1]); rows[1].sourceParagraphId = nativePath(80); rows[1].resultParagraphId = nativePath(80)
  await project('SYNTHETIC-UNMAPPED')
  assert.equal(paper().querySelector('[data-review-binding="SYNTHETIC-b2"]'), null, 'No text search fabricates a native annotation for an unverified path')
  await click('[data-review-location="SYNTHETIC-b2"]')
  assert.match(workspace().textContent, /No verified native location/)
  Object.assign(rows[1], before)
  assert.equal(writes.length, 0)
  console.log('PASS: source, saved revision and DOCX identity guards reject mismatches; retry restores complete content; unavailable native targets stay explicit')

  slowSource = deferred(); store.switchProject('SYNTHETIC-LATE-SOURCE'); await settled(); await clickTitle('4 Preview and export'); await settled()
  await project('SYNTHETIC-AFTER-LATE-SOURCE'); const ownedPaper = paper()
  slowSource.resolve(new Blob([original.bytes])); await settled()
  assert.equal(paper(), ownedPaper, 'Late source bytes cannot replace the current project reader')
  slowResult = deferred(); store.switchProject('SYNTHETIC-LATE-RESULT'); await settled(); await clickTitle('4 Preview and export'); await settled()
  await project('SYNTHETIC-AFTER-LATE-RESULT'); const currentPaper = paper()
  slowResult.resolve(bundle('result', 'NTT')); await settled()
  assert.equal(paper(), currentPaper, 'Late saved metadata cannot replace the current project reader')
  assert.equal(workspace().querySelector('[data-review-field-picker]').dataset.selectedId, 'foundationIncluded', 'A new project resets to its own initial input')
  console.log('PASS: late source and saved-result responses cannot cross current project identity')

  await clickTitle('Edit content'); await clickTitle('Edit paragraph')
  const pending = '  SYNTHETIC pending exact\tparagraph.\nSecond line.  ', body = document.getElementById('body-p-2')
  body.value = pending; body.dispatchEvent(new window.Event('input', { bubbles: true })); await settled()
  assert.equal(paper(), null, 'Dirty body editor does not lend saved associations to pending text')
  assert.equal(workspace().querySelector('[data-return-input]').disabled, true)
  assert.equal(workspace().querySelector('[data-review-pdf]').disabled, true)
  const initialHash = saved.docxSha256, initialTextHash = saved.blocks[0].textHash
  api.updateDocument = async (id, key, patch) => {
    writes.push([id, key, clone(patch)])
    resultText[1] = patch.blocks[0].text; result = await docx(resultText)
    Object.assign(saved, { revisionId: 'SYNTHETIC-revision-2', docxSha256: result.sha, content: pending })
    Object.assign(saved.blocks[0], { text: pending, textHash: hash(pending) })
    Object.assign(rows[0], { text: pending, textHash: hash(pending), bodyOperationIds: ['SYNTHETIC-body-edit'], applicationStatus: 'body_edited' })
    return clone(saved)
  }
  await clickTitle('Save content and preview'); await until(() => paper()?.querySelector('[data-native-paragraph]'))
  assert.deepEqual(writes[0], ['SYNTHETIC-AFTER-LATE-RESULT', 'NTT', { revisionId: 'SYNTHETIC-revision-1', docxSha256: initialHash, blocks: [{ id: nativePath(2), bindingId: 'SYNTHETIC-b1', expectedTextHash: initialTextHash, text: pending }] }], 'Explicit save carries exact native ID, persistent binding, revision, SHA and original text guard')
  await click('[data-review-mode="saved"]')
  assert.match(paper().querySelector('[data-review-binding="SYNTHETIC-b1"]').textContent, /SYNTHETIC pending exact/)
  assert.ok(calls.some(call => call[0] === 'result-bindings' && call[3] === saved.revisionId && call[4] === result.sha))
  assert.equal(writes.length, 1)
  console.log('PASS: explicit bound-body save carries authoritative native identity and reloads the actual returned DOCX revision')

  for (const [locale, modeLabel] of [['zh-Hans', '\u5df2\u4fdd\u5b58\u6587\u7a3f'], ['zh-Hant', '\u5df2\u5132\u5b58\u6587\u7a3f'], ['en', 'Saved result']]) {
    store.locale = locale; await settled()
    assert.equal(workspace().querySelector('[data-review-mode="saved"]').textContent, modeLabel)
    assert.equal(workspace().querySelector('.review-evidence blockquote').textContent, quote)
    await click('[data-review-mode="original"]'); await click('[data-review-mode="saved"]')
    assert.match(paper().textContent, /SYNTHETIC pending exact/)
  }
  assert.equal(writes.length, 1)
  console.log('PASS: three-language reading modes preserve source quotations and saved native content without new writes')
  app.unmount()
})().catch(error => { console.error(error); process.exitCode = 1; app.unmount() })
