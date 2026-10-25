// Public rendered step-3 seam. Synthetic native identities are explicit test data.
const assert = require('node:assert/strict')
const path = require('node:path')
const { JSDOM } = require('jsdom')
const dom = new JSDOM('<!doctype html><html><body></body></html>')
Object.assign(globalThis, { window: dom.window, document: dom.window.document, Element: dom.window.Element, HTMLElement: dom.window.HTMLElement, SVGElement: dom.window.SVGElement, Node: dom.window.Node })
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const purify = require('dompurify')(dom.window)
const sourcePath = n => `word/document.xml#/w:document[1]/w:body[1]/w:p[${n}]`
const reading = (sha, paragraphs) => ({ docxSha256: sha, paragraphs: paragraphs.map(([n, text]) => ({ id: sourcePath(n), path: sourcePath(n), ordinal: n, text, htmlText: text })), html: paragraphs.map(([n, text]) => `<p data-native-paragraph="${sourcePath(n)}" data-native-ordinal="${n}">${text}</p>`).join('') })
const fee = { key: 'fee', kind: 'text', label: { en: 'Fee', zhHans: '费用', zhHant: '費用' }, affects: [{ document: 'NTT', clause: 'Price', paragraphs: 'P2' }, { document: 'SCT', clause: 'Price return', paragraphs: 'P8' }] }
const binding = (id, n, sourceText, text, extra = {}) => ({ bindingId: id, bookmarkName: id, sourceParagraphId: sourcePath(n), resultParagraphId: sourcePath(n), sourceParagraphOrdinal: n, resultParagraphOrdinal: n, sourceText, text, fieldKeys: ['fee'], actionIds: ['price'], appliedActionIds: ['price'], operationIds: ['price-fill'], applicationStatus: 'applied', locationStatus: 'exact', geometryStatus: 'unavailable', ...extra })
const base = () => ({ projectId: 'TEST-READER', fileKey: 'NTT', document: { fileKey: 'NTT', title: 'Notes', content: '', generated: true, revisionId: 'r1', sourceSha256: 'source-sha', docxSha256: 'result-sha' }, original: reading('source-sha', [[1, 'Complete invitation'], [2, 'Pay [fee] dollars.'], [3, 'Complete closing paragraph']]), result: reading('result-sha', [[1, 'Complete invitation'], [2, 'Pay 100 dollars.'], [3, 'Complete closing paragraph']]), sourceBindings: { fileKey: 'NTT', view: 'source', sourceSha256: 'source-sha', bindings: [binding('price-binding', 2, 'Pay [fee] dollars.', 'Pay [fee] dollars.')] }, resultBindings: { fileKey: 'NTT', view: 'result', sourceSha256: 'source-sha', docxSha256: 'result-sha', revisionId: 'r1', bindings: [binding('price-binding', 2, 'Pay [fee] dollars.', 'Pay 100 dollars.')] }, fields: [fee], values: { fee: 'Unsaved current value' }, variables: [{ key: 'fee', value: '100', candidates: [] }], actions: [{ id: 'price', document: 'NTT', clause: 'Price', paragraphs: 'P2', inputKeys: ['fee'], action: 'fill' }], fieldStates: { fee: 'adopted' }, dirtyKeys: ['fee'], locale: 'en', loading: false })
const settle = async () => { for (let i = 0; i < 8; i++) { await Promise.resolve(); await vue.nextTick() } }
async function harness(overrides = {}, slots) {
  const props = vue.reactive({ ...base(), ...overrides }), events = [], scrolls = []
  dom.window.HTMLElement.prototype.scrollTo = function (value) { scrolls.push(value); this.scrollTop = value.top ?? 0 }
  const component = loadVue(path.join(__dirname, '../src/components/DraftingDocumentReview.vue'), { boundaries: { dompurify: { default: purify } }, globals: { document, window, Element, Node, HTMLElement } }).default
  const element = document.createElement('div'); document.body.append(element)
  const app = vue.createApp({ render: () => vue.h(component, { ...props, onInput: key => events.push(['input', key]), onFile: key => events.push(['file', key]), onRetry: () => events.push(['retry']), onPdf: view => events.push(['pdf', view]) }, slots) })
  app.mount(element); await settle()
  return { props, element, events, scrolls, async click(selector) { const control = element.querySelector(selector); assert(control, `Rendered control ${selector}`); control.click(); await settle() }, dispose() { app.unmount(); element.remove() } }
}
async function failedAuxiliaryReadDoesNotHideSavedRevisionEditing() {
  const authored = vue.ref('Exact saved block from revision r1')
  const h = await harness({ error: 'Request failed with status code 404', original: undefined, result: undefined, sourceBindings: undefined, resultBindings: undefined }, { default: () => vue.h('textarea', { 'data-authored-body': '', value: authored.value, onInput: event => { authored.value = event.target.value } }) })
  assert.match(h.element.querySelector('[role="alert"]').textContent, /404/)
  assert.equal(h.element.querySelector('[data-authored-body]'), null, 'Reading mode keeps its real failure visible')
  h.props.editing = true; await settle()
  const editor = h.element.querySelector('[data-authored-body]')
  assert(editor, 'A failed auxiliary reading request must not hide the saved revision block editor')
  assert.equal(editor.value, 'Exact saved block from revision r1')
  assert.match(h.element.querySelector('[role="alert"]').textContent, /404/, 'Editing does not certify the failed reading as repaired')
  editor.value = 'Explicit manual revision'; editor.dispatchEvent(new window.Event('input', { bubbles: true })); await settle()
  await h.click('[role="alert"] button'); assert.deepEqual(h.events, [['retry']])
  h.props.loading = true; await settle(); assert.equal(h.element.querySelector('[data-authored-body]').value, 'Explicit manual revision', 'Retrying the reader preserves the separately owned edit')
  h.props.loading = false; h.props.editing = false; await settle(); assert.equal(h.element.querySelector('[data-authored-body]'), null)
  assert.match(h.element.querySelector('[role="alert"]').textContent, /404/); assert.equal(h.element.querySelector('[data-document-paper]'), null, 'Failed reading never falls back to fabricated source/result content')
  h.dispose(); console.log('PASS: auxiliary read failure remains visible and retryable while saved-revision manual editing stays available')
}
async function unverifiedMappingStillReadsCurrentSavedBody() {
  for (const mismatch of ['missing', 'unknown-native-path', 'unattributed']) {
    const props = base(); props.dirtyKeys = []
    props.original = reading('source-sha', [[1, 'Duration: ____ months. Keep NTT original context.']])
    props.result = reading('result-sha', [[1, 'Duration: 42 months. Keep NTT generated context.']])
    props.sourceBindings.bindings = [binding('duration', 1, props.original.paragraphs[0].text, props.original.paragraphs[0].text)]
    props.resultBindings.bindings = mismatch === 'missing' ? [] : [binding('duration', 1, props.original.paragraphs[0].text, props.result.paragraphs[0].text, mismatch === 'unknown-native-path' ? { resultParagraphId: 'word/document.xml#/ns0:document[1]/ns0:body[1]/ns0:p[1]', locationStatus: 'unavailable' } : { appliedActionIds: [] })]
    const h = await harness(props), paper = () => h.element.querySelector('[data-document-paper]')
    assert.match(paper().textContent, /Duration: 42 months\. Keep NTT generated context\./, `${mismatch}: unverified review mapping must still display the actual current saved draft`)
    assert.doesNotMatch(paper().textContent, /____|original context/, `${mismatch}: source text must not stand in for the saved draft`)
    assert.equal(paper().querySelectorAll('del,ins').length, 0, 'Unknown mapping must not fabricate a verified inline change')
    assert.match(h.element.textContent, /saved.*unverified|unverified.*saved/i)
    for (const [locale, message] of [['zh-Hans','当前显示真实已保存文稿'],['zh-Hant','目前顯示真實已儲存文稿'],['en','Showing the current saved draft']]) { h.props.locale = locale; await settle(); assert.match(h.element.querySelector('[data-review-carrier-status]').textContent, new RegExp(message)); assert.match(paper().textContent, /Duration: 42 months\./) }
    await h.click('[data-review-mode="original"]'); assert.match(paper().textContent, /Duration: ____ months\. Keep NTT original context\./)
    assert.equal(h.events.length, 0, 'Changing read mode cannot regenerate, write or adopt')
    h.dispose()
  }
  console.log('PASS: missing, unknown native and unattributed mappings display the exact current saved body, while source mode stays original and no change is certified')
}
async function completeDocumentModes() {
  const h = await harness()
  const paper = () => h.element.querySelector('[data-document-paper]')
  assert.match(paper().textContent, /Complete invitation/)
  assert.match(paper().textContent, /Complete closing paragraph/)
  assert.doesNotMatch(paper().textContent, /Unsaved current value/, 'Current input cannot simulate saved document changes')
  assert.match(h.element.querySelector('[data-current-value]').textContent, /Unsaved current value/, 'Inspector shows the real dirty input independently')
  await h.click('[data-review-mode="original"]'); assert.match(paper().textContent, /Pay \[fee\] dollars\./)
  await h.click('[data-review-mode="saved"]'); assert.match(paper().textContent, /Pay 100 dollars\./); assert.equal(paper().querySelectorAll('del,ins').length, 0)
  h.dispose(); console.log('PASS: complete original/review/saved content and real current values stay separate')
}
async function searchableVariableSelection() {
  const props = base(), duration = { key: 'duration', kind: 'number', label: { en: 'Contract duration in months', zhHans: '合约工期（月）', zhHant: '合約工期（月）' } }
  props.fields.push(duration); props.values.duration = 39; props.variables.push({ key: 'duration', value: '39', candidates: [] }); props.dirtyKeys = []
  props.actions[0].inputKeys.push('duration')
  for (const bindings of [props.sourceBindings, props.resultBindings]) bindings.bindings[0].fieldKeys.push('duration')
  const h = await harness(props), picker = () => h.element.querySelector('[data-review-field-picker]')
  const search = async value => { const input = h.element.querySelector('[data-review-field-search]'); input.value = value; input.dispatchEvent(new window.Event('input', { bubbles: true })); await settle() }
  await h.click('[data-review-binding="price-binding"]')
  assert(h.element.querySelector('[data-change-context]'))
  await search('合同期限')
  assert(picker().querySelector('[data-picker-option="duration"]'), 'Search uses both labels and the shared duration alias')
  assert.equal(picker().querySelector('[data-picker-option="fee"]'), null)
  assert.match(picker().querySelector('[data-picker-current]').textContent, /Fee/, 'Search does not replace the current selection')
  assert.deepEqual(h.events, [], 'Searching does not save, adopt or navigate to another editor')
  await h.click('[data-review-field-picker] [data-picker-option="duration"]')
  assert.equal(picker().dataset.selectedId, 'duration')
  assert(h.element.querySelector('[data-change-context]'), 'Selecting a variable from the inspected action preserves the same binding evidence')
  assert.match(h.element.querySelector('[data-current-value]').textContent, /39/)
  await search('no match')
  assert.equal(picker().dataset.selectedId, 'duration'); assert.match(picker().querySelector('[data-picker-count]').textContent, /^0 /)
  h.props.fields = h.props.fields.filter(item => item.key !== 'duration'); await settle()
  assert.equal(picker().dataset.selectedId, 'duration', 'A missing definition never silently changes the selected variable')
  assert.match(picker().querySelector('[data-picker-current]').textContent, /definition unavailable.*duration/)
  assert(picker().querySelector('[data-picker-option="duration"]').disabled, 'Retain the missing variable as a disabled option')
  await search('Fee'); h.props.disabled = true; await settle()
  assert(h.element.querySelector('.review-field-picker').disabled)
  picker().querySelector('[data-picker-option="fee"]').dispatchEvent(new window.MouseEvent('click', { bubbles: true })); await settle()
  assert.equal(picker().dataset.selectedId, 'duration', 'The existing blocked guard also rejects captured selection events')
  h.props.disabled = false; h.props.projectId = 'OTHER-READER'; await settle()
  assert.equal(h.element.querySelector('[data-review-field-search]').value, ''); assert.equal(picker().dataset.selectedId, 'fee')
  assert.deepEqual(h.events, []); assert.equal(h.props.values.fee, 'Unsaved current value')
  h.dispose(); console.log('PASS: variable search preserves current and missing selections, binding inspection and blocked state without writes')
}
async function selectedActualTokenDiff() {
  const props = base()
  props.fields.push({ key: 'method', kind: 'text', label: { en: 'Method' } })
  props.original = reading('source-sha', [[1, 'Complete invitation'], [2, 'Pay [fee] dollars.'], [3, 'Other old wording']])
  props.original.html = props.original.html.replace('Pay [fee]', '<b>Pay</b> [fee]')
  props.result = reading('result-sha', [[1, 'Complete invitation'], [2, 'Pay 100 dollars.'], [3, 'Other new wording']])
  props.sourceBindings.bindings.push(binding('other-binding', 3, 'Other old wording', 'Other old wording', { fieldKeys: ['method'], actionIds: ['method-action'] }))
  props.resultBindings.bindings.push(binding('other-binding', 3, 'Other old wording', 'Other new wording', { fieldKeys: ['method'], actionIds: ['method-action'] }))
  const h = await harness(props), paper = h.element.querySelector('[data-document-paper]')
  assert.match(paper.textContent, /Pay 100 dollars\./)
  assert.match(paper.textContent, /Other new wording/, 'A partial ledger cannot leave another changed paragraph as old template wording')
  assert.doesNotMatch(paper.textContent, /\[fee\]|Other old wording/)
  assert.equal(paper.querySelectorAll('del,ins').length, 0, 'An incomplete whole-document overlay falls back to the actual saved carrier without certifying individual changes')
  assert.match(h.element.querySelector('[data-review-carrier-status]').textContent, /current saved draft.*unverified/)
  h.dispose(); console.log('PASS: a verified change beside an unattributed change reads the complete saved result rather than mixing current and original text')
}
async function nativeLineBreakTokenDiff() {
  const props = base(), before = 'Pay\n[fee] dollars.', after = 'Pay\n100 dollars.'
  props.fields.push({ key: 'method', kind: 'text', label: { en: 'Method' } }); props.values.method = 'Other input'
  props.original = reading('source-sha', [[1, 'Complete invitation'], [2, before], [3, 'Complete closing paragraph']])
  props.result = reading('result-sha', [[1, 'Complete invitation'], [2, after], [3, 'Complete closing paragraph']])
  props.original.html = props.original.html.replace(before, '<b>Pay</b><br><em>[fee]</em> dollars.')
  props.result.html = props.result.html.replace(after, '<b>Pay</b><br><strong>100</strong> dollars.')
  props.sourceBindings.bindings = [binding('price-binding', 2, before, before)]
  props.resultBindings.bindings = [binding('price-binding', 2, before, after)]
  const h = await harness(props), paragraph = () => h.element.querySelector('[data-review-binding="price-binding"]')
  assert(paragraph(), 'BR-aware native text receipt verifies the paragraph and its interactive binding')
  assert.equal(paragraph().querySelector('del').textContent, '[fee]')
  assert.equal(paragraph().querySelector('ins strong').textContent, '100', 'Inserted formatting comes from the actual saved carrier')
  assert.equal(paragraph().querySelector('b').textContent, 'Pay'); assert.equal(paragraph().querySelectorAll('br').length, 1)
  assert.equal(paragraph().querySelector('del br,ins br'), null, 'The unchanged line break is outside the changed token range')
  await h.click('[data-review-field="method"]'); await h.click('[data-review-binding="price-binding"]')
  assert.match(h.element.querySelector('[data-current-value]').textContent, /Unsaved current value/, 'BR paragraph reverse navigation selects its registered field')
  const count = h.scrolls.length; await h.click('[data-review-location="price-binding"]'); assert(h.scrolls.length > count)
  await h.click('[data-review-mode="original"]'); assert(paragraph()); assert.equal(paragraph().querySelectorAll('del,ins').length, 0)
  h.dispose(); console.log('PASS: BR-aware verification, exact token ranges, inline formatting and bidirectional native navigation')
}
async function linkedNativeNavigation() {
  const props = base()
  props.fields.push({ key: 'method', kind: 'text', label: { en: 'Method', zhHans: '方式', zhHant: '方式' }, affects: [{ document: 'NTT', clause: 'Price', paragraphs: 'P2' }] })
  props.values.method = 'L10Pro'
  props.sourceBindings.bindings[0].fieldKeys.push('method'); props.resultBindings.bindings[0].fieldKeys.push('method')
  const h = await harness(props)
  await h.click('[data-review-mode="original"]') // Original associations remain selectable; a saved exclusive fee edit prioritizes fee instead.
  const linked = h.element.querySelector('[data-review-binding="price-binding"]')
  linked.dispatchEvent(new dom.window.KeyboardEvent('keydown', { key: 'Enter', bubbles: true })); await settle()
  assert(h.element.querySelector('[data-association-choice="method"]'), 'Shared wording offers explicit input selection')
  await h.click('[data-association-choice="method"]')
  assert.match(h.element.querySelector('[data-current-value]').textContent, /L10Pro/)
  await h.click('[data-review-field="fee"]')
  const oldScrolls = h.scrolls.length
  await h.click('[data-review-location="price-binding"]')
  assert(h.scrolls.length > oldScrolls, 'Input/location navigation scrolls the native carrier')
  assert(h.element.querySelector('[data-review-binding="price-binding"]').classList.contains('review-focused'))
  await h.click('[data-review-location-file="SCT"]')
  assert.deepEqual(h.events.at(-1), ['file', 'SCT'], 'Other-file locations use the parent file navigation contract')
  await h.click('[data-return-input]')
  assert.deepEqual(h.events.at(-1), ['input', 'fee'], 'Return to step 2 emits only input navigation')
  assert.equal(h.props.values.fee, 'Unsaved current value')
  h.dispose(); console.log('PASS: keyboard reverse navigation, explicit shared-field selection and cross-file input navigation')
}
async function branchGuidanceAndAddedMapping() {
  const props = base(); props.dirtyKeys = []
  props.original = reading('source-sha', [[1, 'Retained plain words'], [2, 'Paper route full paragraph'], [3, 'Choose alternative guide'], [4, 'Bill No. 1 native parent']])
  props.result = reading('result-sha', [[1, 'Retained plain words'], [4, 'Bill No. 1 native parent'], [5, 'Bill No. 2 saved addition']])
  props.actions = [{ id: 'route', document: 'NTT', clause: 'Alternative (a)', paragraphs: 'P1; guidance P3', inputKeys: ['fee'], action: 'retain' }, { id: 'paper-route', document: 'NTT', clause: 'Alternative (b)', paragraphs: 'P2', inputKeys: ['fee'], action: 'delete' }]
  const make = (id, n, text, extra) => binding(id, n, text, text, { actionIds: n === 2 ? ['paper-route'] : ['route'], appliedActionIds: n === 2 ? ['paper-route'] : ['route'], operationIds: [], ...extra })
  props.sourceBindings.bindings = [make('retained', 1, 'Retained plain words'), make('removed', 2, 'Paper route full paragraph'), make('guidance', 3, 'Choose alternative guide'), make('parent', 4, 'Bill No. 1 native parent')]
  props.resultBindings.bindings = [make('retained', 1, 'Retained plain words'), make('removed', 2, 'Paper route full paragraph', { text: '', locationStatus: 'removed', operationIds: ['paper-route-P2'] }), make('guidance', 3, 'Choose alternative guide', { text: '', locationStatus: 'removed', operationIds: ['route-P3'] }), make('parent', 4, 'Bill No. 1 native parent'), make('added', 5, null, { sourceParagraphId: null, sourceParagraphOrdinal: null, text: 'Bill No. 2 saved addition', parentBindingId: 'parent', applicationStatus: 'generated_added', operationIds: ['route-new-row'] })]
  const h = await harness(props)
  const node = id => h.element.querySelector(`[data-review-binding="${id}"]`)
  assert(node('retained').classList.contains('review-retained')); assert.equal(node('retained').querySelectorAll('del,ins').length, 0)
  assert(node('removed').classList.contains('review-alternative-removed')); assert(node('guidance').classList.contains('review-guidance'))
  assert(node('added').classList.contains('review-added')); assert(!node('added').hasAttribute('data-native-paragraph'))
  assert.match(node('added').getAttribute('aria-description'), /no verified one-to-one original mapping/)
  await h.click('[data-review-location="added"]'); assert(node('added').classList.contains('review-focused'))
  await h.click('[data-review-mode="original"]'); assert(node('parent').classList.contains('review-focused')); assert.match(h.element.textContent, /recorded parent context/)
  h.props.dirtyKeys = ['fee']; await h.click('[data-review-mode="review"]'); assert(!node('retained').classList.contains('review-retained'), 'Pending current plan cannot relabel saved retention')
  assert(node('removed').classList.contains('review-removed'), 'Actual saved deletion remains visible')
  h.dispose(); console.log('PASS: retained context, whole alternative deletion, guidance cleanup and added-row provenance remain distinct')
}
async function verifiedReadingGuardsAndLocales() {
  const props = base(); props.original.html += '<p data-review-binding="forged" tabindex="0">Unlinked native text</p><script>window.injected=true</script>'
  const h = await harness(props)
  assert.equal(h.element.querySelector('[data-review-binding="forged"]'), null, 'Incoming markup cannot manufacture an interactive association')
  assert.equal(h.element.querySelector('script'), null)
  for (const [locale, label] of [['zh-Hans', '已保存文稿'], ['zh-Hant', '已儲存文稿'], ['en', 'Saved result']]) { h.props.locale = locale; await settle(); assert.equal(h.element.querySelector('[data-review-mode="saved"]').textContent, label) }
  h.props.result.docxSha256 = 'wrong-result'; await h.click('[data-review-mode="saved"]'); assert.equal(h.element.querySelector('[data-document-paper]').textContent, ''); assert.match(h.element.textContent, /Document versions differ/)
  h.props.disabled = true; await settle(); const oldEvents = h.events.length; h.element.querySelector('[data-return-input]').click(); h.element.querySelector('[data-review-pdf]').click(); await settle(); assert.equal(h.events.length, oldEvents, 'Body/input navigation and PDF controls respect parent disabled state')
  h.props.disabled = false; h.props.error = 'Identity load failed'; await settle(); await h.click('[role="alert"] button'); assert.deepEqual(h.events.at(-1), ['retry'])
  h.dispose(); console.log('PASS: only verified native associations are interactive; identities, locales, retry and navigation guards stay visible')
}
async function replacedTemplateKeepsCurrentOriginal() {
  const props = base(); props.document.stale = true
  props.original = reading('new-source-sha', [[1, 'Current replacement introduction'], [2, 'Current replacement price [fee]'], [3, 'Current replacement ending']])
  props.sourceBindings.sourceSha256 = 'new-source-sha'
  props.sourceBindings.bindings = [binding('current-price', 2, 'Current replacement price [fee]', 'Current replacement price [fee]')]
  const h = await harness(props)
  assert.match(h.element.querySelector('[data-document-paper]').textContent, /Current replacement introduction/, 'A stale saved revision cannot hide the current uploaded original')
  assert.equal(h.element.querySelector('[data-document-paper]').querySelectorAll('del,ins').length, 0, 'The old revision cannot overlay changes on a new source')
  await h.click('[data-review-mode="original"]'); assert.match(h.element.querySelector('[data-document-paper]').textContent, /Current replacement ending/)
  await h.click('[data-review-mode="saved"]'); assert.equal(h.element.querySelector('[data-document-paper]').textContent, ''); assert.match(h.element.textContent, /Document versions differ/)
  h.dispose(); console.log('PASS: current replacement source remains readable while stale saved changes are rejected')
}
async function recordedEvidenceContext() {
  const props = base(), quote = 'The fee is 100 dollars.', fullContext = `Earlier request.\n${quote}\nLater qualification.`
  const candidate = { value: '100', sourceDocumentId: 27, fileName: 'actual-message.pdf', sourceHash: 'message-sha', sourceQuote: quote, reason: 'Recorded explanation' }
  props.variables = [{ key: 'fee', value: '100', candidates: [candidate] }]
  props.trace = { model: 'test-record', finishedAt: '2026-10-07', systemPrompt: '', userPrompt: '', rawResponses: [], parts: [{ partId: 'part-2', sourceDocumentId: 27, fileName: 'actual-message.pdf', sourceHash: 'message-sha', partIndex: 2, sourceText: 'Whole record', attempts: [{ attemptIndex: 4, kind: 'recall', status: 'completed', systemPrompt: '', userPrompt: '', rawResponse: '', context: { sourceText: fullContext, sourceStart: 10, sourceEnd: 10 + fullContext.length } }] }], decisions: [{ partId: 'part-2', attemptIndex: 4, itemIndex: 0, key: 'fee', rawValue: '100', normalizedValue: '100', sourceQuote: quote, status: 'accepted', codes: [] }] }
  const h = await harness(props), details = () => h.element.querySelector('[data-evidence-context]')
  assert(details(), 'An exact accepted source identity exposes read-only quotation context')
  await h.click('[data-evidence-context] summary'); assert(details().open)
  assert.equal(h.element.querySelector('[data-evidence-context-text]').textContent, fullContext, 'Actual before/after record is visible without manufacturing context')
  h.props.trace.parts[0].sourceHash = 'other-message-sha'; await settle(); assert.equal(details(), null); assert(h.element.querySelector('[data-evidence-context-unavailable]'))
  h.props.trace.parts[0].sourceHash = 'message-sha'; h.props.trace.parts[0].fileName = 'wrong-file.pdf'; await settle(); assert.equal(details(), null)
  h.props.trace.parts[0].fileName = 'actual-message.pdf'; h.props.trace.decisions[0].normalizedValue = '999'; await settle(); assert.equal(details(), null, 'An accepted decision for a different candidate is not reused')
  h.props.trace.decisions[0].normalizedValue = '100'; h.props.trace.parts[0].attempts[0].status = 'failed'; await settle(); assert.equal(details(), null, 'Failed source-context dispatch is not shown as accepted evidence')
  h.props.trace.parts[0].attempts[0].status = 'completed'; h.props.trace.parts.push({ ...h.props.trace.parts[0], partId: 'another-part' }); h.props.trace.decisions.push({ ...h.props.trace.decisions[0], partId: 'another-part' }); await settle(); assert.equal(details(), null, 'Ambiguous accepted context identities remain unavailable')
  h.dispose(); console.log('PASS: recorded evidence expansion verifies file/hash/part/attempt/candidate and rejects absent or ambiguous context')
}
async function appliedSharedParagraphAttribution() {
  const props = base(); props.dirtyKeys = []
  props.fields.push({ key: 'method', kind: 'text', label: { en: 'Method' } }); props.values.method = 'Electronic'
  props.actions.push({ id: 'method-retain', document: 'NTT', clause: 'Price method', paragraphs: 'P2', inputKeys: ['method'], action: 'retain' })
  for (const receipt of [props.sourceBindings.bindings[0], props.resultBindings.bindings[0]]) { receipt.fieldKeys = ['fee', 'method']; receipt.actionIds = ['price', 'method-retain'] }
  const h = await harness(props), paper = () => h.element.querySelector('[data-document-paper]')
  assert.equal(paper().querySelector('ins').textContent, '100', 'An exclusively attributed saved fee operation can show its exact token diff')
  await h.click('[data-review-field="method"]')
  assert.equal(paper().querySelector('ins').textContent, '100', 'Changing the focused input keeps the complete saved revision visible')
  assert(paper().querySelector('[data-review-change-kind="other"]'), 'A different input does not claim the applied fee edit')
  h.props.actions[0].inputKeys = ['fee', 'method']; await h.click('[data-review-field="fee"]')
  assert.equal(paper().querySelector('ins').textContent, '100', 'A joint action displays the complete actual revision')
  assert(paper().querySelector('[data-review-change-kind="joint"]'), 'A joint action cannot claim selected-input-only token ranges')
  assert.match(h.element.textContent, /Joint changes.*Individual character ranges/s)
  await h.click('[data-review-mode="saved"]'); assert.match(paper().textContent, /Pay 100 dollars\./)
  await h.click('[data-review-mode="review"]'); h.props.actions[0].inputKeys = ['fee']; h.props.resultBindings.bindings[0].appliedActionIds = []; await settle()
  assert.equal(paper().querySelectorAll('del,ins').length, 0, 'Operation membership alone cannot invent an applied action')
  h.props.resultBindings.bindings[0].appliedActionIds = ['price']; h.props.resultBindings.bindings[0].operationIds = ['price-other-prefix-is-valid']; h.props.resultBindings.bindings[0].bodyOperationIds = ['body-edit-1']; await settle()
  assert.equal(paper().querySelector('ins').textContent, '100'); assert(paper().querySelector('[data-review-change-kind="manual"]')); assert.match(h.element.textContent, /manual.*cannot.*attribut/i)
  h.dispose(); console.log('PASS: complete saved action changes remain visible with explicit sole-input, other-input, joint and manual provenance')
}
async function unavailableSavedLedger() {
  const props = base(); props.sourceBindings.bindings[0].operationIds = []; props.sourceBindings.bindings[0].appliedActionIds = []
  props.resultBindings.bindings = []
  const h = await harness(props), paper = () => h.element.querySelector('[data-document-paper]')
  assert.match(h.element.textContent, /Saved revision bindings are unavailable/, 'An empty historical ledger cannot silently imply no changes')
  assert.equal(paper().querySelector('[data-review-binding="price-binding"]'), null, 'An absent result mapping cannot reuse a source-only interactive binding')
  assert.match(paper().textContent, /Pay 100 dollars\./)
  await h.click('[data-review-mode="original"]'); assert(paper().querySelector('[data-review-binding="price-binding"]'), 'Verified source navigation remains available explicitly in original mode'); await h.click('[data-review-mode="review"]')
  assert.doesNotMatch(h.element.querySelector('.binding-status')?.textContent ?? '', /unchanged in the saved document/)
  assert.match(h.element.querySelector('[data-review-carrier-status]').textContent, /current saved draft.*unverified/i)
  assert.equal(paper().querySelectorAll('del,ins').length, 0)
  await h.click('[data-review-mode="saved"]'); assert.match(paper().textContent, /Pay 100 dollars\./, 'Actual saved reading remains usable without a change ledger')
  await h.click('[data-review-mode="review"]')
  h.props.resultBindings.bindings = [binding('unrelated', 1, 'Complete invitation', 'Complete invitation', { fieldKeys: [] })]; await settle()
  assert.match(h.element.textContent, /saved mapping.*selected/i, 'A partial ledger missing this source binding reports that specific limitation')
  assert.doesNotMatch(h.element.querySelector('.binding-status')?.textContent ?? '', /unchanged in the saved document/)
  h.dispose(); console.log('PASS: empty and partial saved ledgers cannot claim unchanged wording while actual saved reading and source navigation remain available')
}
async function missingCrossFileTargetDoesNotFallback() {
  const h = await harness(); await h.click('[data-review-location-file="SCT"]')
  assert.deepEqual(h.events.at(-1), ['file', 'SCT'])
  h.props.loading = true; await settle()
  const original = reading('sct-source', [[1, 'Other SCT wording']]), result = reading('sct-result', [[1, 'Other SCT wording']])
  Object.assign(h.props, { fileKey: 'SCT', document: { fileKey: 'SCT', title: 'SCT', generated: true, revisionId: 'sct-r1', sourceSha256: 'sct-source', docxSha256: 'sct-result' }, original, result, sourceBindings: { fileKey: 'SCT', view: 'source', sourceSha256: 'sct-source', bindings: [binding('other-sct', 1, 'Other SCT wording', 'Other SCT wording', { actionIds: ['other'], operationIds: [], appliedActionIds: [] })] }, resultBindings: { fileKey: 'SCT', view: 'result', sourceSha256: 'sct-source', docxSha256: 'sct-result', revisionId: 'sct-r1', bindings: [binding('other-sct', 1, 'Other SCT wording', 'Other SCT wording', { actionIds: ['other'], operationIds: [], appliedActionIds: [] })] }, actions: [{ id: 'other', document: 'SCT', clause: 'Other location', paragraphs: 'P1', inputKeys: ['fee'], action: 'retain' }], loading: false })
  const count = h.scrolls.length; await settle()
  assert.equal(h.element.querySelector('.review-focused'), null, 'A missing requested clause cannot focus another field-related paragraph')
  assert.equal(h.scrolls.length, count, 'An unverified cross-file target must not scroll to an arbitrary first paragraph')
  assert.match(h.element.querySelector('.navigation-notice').textContent, /requested.*no verified/i)
  await h.click('[data-review-location="other-sct"]'); assert(h.element.querySelector('[data-review-binding="other-sct"]').classList.contains('review-focused'), 'Explicitly choosing another registered location remains available')
  h.dispose(); console.log('PASS: missing requested cross-file target reports unavailable without undefined-ID matching or first-location fallback')
}
async function completeSavedRevisionChanges() {
  const props = base(); props.dirtyKeys = []
  props.fields.push({ key: 'method', kind: 'text', label: { en: 'Method' } })
  props.values.method = 'Electronic'; props.actions[0].inputKeys.push('method')
  for (const row of [props.sourceBindings.bindings[0], props.resultBindings.bindings[0]]) row.fieldKeys.push('method')
  props.original.paragraphs.push({ id: sourcePath(4), path: sourcePath(4), ordinal: 4, text: 'Remove this editor instruction', htmlText: 'Remove this editor instruction' })
  props.original.html += `<p data-native-paragraph="${sourcePath(4)}" data-native-ordinal="4">Remove this editor instruction</p>`
  props.sourceBindings.bindings.push(binding('cleanup', 4, 'Remove this editor instruction', 'Remove this editor instruction', { fieldKeys: [], actionIds: [], appliedActionIds: [], operationIds: [] }))
  props.resultBindings.bindings.push(binding('cleanup', 4, 'Remove this editor instruction', '', { fieldKeys: [], actionIds: [], appliedActionIds: [], operationIds: ['NTT-editorial-note-01-P4'], editorialOperationIds: ['NTT-editorial-note-01-P4'], documentEditStatus: 'applied', locationStatus: 'removed' }))
  const h = await harness(props), paper = () => h.element.querySelector('[data-document-paper]')
  assert.equal(paper().querySelector('ins')?.textContent, '100', 'Joint saved action must display the actual document change')
  assert(paper().querySelector('[data-review-change-kind="joint"]'), 'Joint changes declare group attribution rather than selected-input-only ranges')
  assert(paper().querySelector('.review-guidance')?.textContent.includes('editor instruction'), 'Unlinked verified editorial removal must remain visible in the complete revision')
  await h.click('[data-review-field="method"]'); assert.equal(paper().querySelector('ins')?.textContent, '100', 'Changing input focus does not hide shared saved changes')
  await h.click('[data-review-mode="saved"]'); assert.equal(paper().querySelectorAll('del,ins,.review-guidance').length, 0)
  assert.doesNotMatch(paper().textContent, /editor instruction/, 'Saved mode shows actual final content')
  await h.click('[data-review-mode="review"]')
  h.props.resultBindings.bindings[1].editorialOperationIds = ['NTT-editorial-note-01-P999']; await settle()
  assert.equal(paper().querySelector('.review-guidance'), null, 'A cleanup category cannot authorize a different operation')
  assert.match(h.element.textContent, /cannot be attributed|unverified/i)
  h.dispose(); console.log('PASS: complete saved revision displays joint changes and independently verified editorial cleanup without inventing per-input ranges')
}
async function warningSourceLabels() {
  const mixedProps = base(); mixedProps.dirtyKeys = []
  Object.assign(mixedProps.resultBindings.bindings[0], { documentEditStatus: 'applied', operationIds: ['price-fill', 'NTT-editorial-note-01-P2'], editorialOperationIds: ['NTT-editorial-note-01-P2'] })
  const mixed = await harness(mixedProps)
  assert.equal(mixed.element.querySelector('ins')?.textContent, '100')
  assert(mixed.element.querySelector('[data-review-change-kind="joint"]'), 'A paragraph with both business and editorial operations cannot assign all changed tokens to the sole business input')
  mixed.dispose(); console.log('PASS: mixed business and editorial changes preserve the real diff with shared paragraph attribution')
  const props = base(); props.original.warnings = ['Native numbering labels and continuation sequence are not verified in this reading (22 numbered paragraph(s)). Check the PDF layout for the original list markers.']; props.result.warnings = ['Native numbering labels and continuation sequence are not verified in this reading (15 numbered paragraph(s)). Check the PDF layout for the original list markers.']
  for (const reading of [props.original, props.result]) reading.html = reading.html.replace('<p ', '<ol class="docx-unverified-list"><li ').replace('</p>', '</li></ol>')
  const h = await harness(props)
  assert.match(h.element.querySelector('.reading-warnings').textContent, /Original.*22/s)
  assert.match(h.element.querySelector('.reading-warnings').textContent, /Saved result.*15/s)
  const markerStyle = document.createElement('style'); markerStyle.textContent = require('node:fs').readFileSync(path.join(__dirname, '../src/styles/main.css'), 'utf8'); document.head.append(markerStyle)
  for (const [locale, message] of [['en', 'Web-generated list markers are hidden.'], ['zh-Hans', '网页自动标号已隐藏，原编号请核对 PDF。'], ['zh-Hant', '網頁自動標號已隱藏，原編號請核對 PDF。']]) {
    h.props.locale = locale; await settle()
    const notice = h.element.querySelector('[data-reading-numbering-notice]')
    assert.ok(notice && !notice.closest('details'), 'Numbering limits remain visible outside collapsed diagnostics')
    assert.ok(notice.textContent.includes(message))
  }
  h.props.locale = 'en'; await settle()
  for (const mode of ['original', 'review', 'saved']) {
    await h.click(`[data-review-mode="${mode}"]`)
    const list = h.element.querySelector('[data-document-paper] ol.docx-unverified-list')
    assert.ok(list, 'Trusted marker class survives the review sanitization pass')
    assert.equal(dom.window.getComputedStyle(list).listStyleType, 'none')
    assert.equal(list.textContent, 'Complete invitation', 'Warning and marker policy do not change native paragraph text')
  }
  await h.click('[data-review-mode="saved"]'); assert.doesNotMatch(h.element.querySelector('.reading-warnings').textContent, /Original/)
  h.props.result.warnings = []; await settle(); assert.equal(h.element.querySelector('[data-reading-numbering-notice]'), null, 'Saved mode does not inherit warnings from the original carrier')
  markerStyle.remove()
  h.dispose(); console.log('PASS: reading warnings identify original and saved carriers independently')
}
async function compactLimitsKeepEveryRiskAndParentOwnedStaleStatus() {
  await deliveryNoticesPreserveVerifiedOverlay()
  const props = base(); props.document.stale = true; props.staleNoticePresented = true; props.result = undefined; props.resultBindings = undefined
  props.original.warnings = ['Native numbering labels and continuation sequence are not verified in this reading (22 numbered paragraph(s)). Check the PDF layout for the original list markers.']
  const h = await harness(props)
  const limits = h.element.querySelector('details[data-reading-limits]')
  assert(limits, 'Reading and binding limitations share one explicit disclosure')
  assert.equal(limits.open, false); assert.match(limits.querySelector('summary').textContent, /Unverified reading and change mapping/)
  assert.match(limits.textContent, /Original.*22/s); assert.match(limits.textContent, /Saved revision bindings are unavailable/)
  assert.equal([...h.element.querySelectorAll('.review-notice')].filter(node => /Current variables differ/.test(node.textContent)).length, 0, 'A parent-owned stale state is not repeated by its reader')
  assert.equal(h.element.querySelector('[data-review-mode="saved"]').disabled, true)
  assert.match(h.element.querySelector('[data-document-paper]').textContent, /Complete invitation/)
  h.dispose(); console.log('PASS: one collapsed risk disclosure preserves numbering/binding failures while parent stale status remains singular')
}
async function deliveryNoticesPreserveVerifiedOverlay() {
  const props = base()
  props.result = reading('result-sha', [[10, 'INCOMPLETE DRAFT'], [1, 'Complete invitation'], [2, 'Pay 100 dollars.'], [3, 'Complete closing paragraph'], [11, 'Outstanding items']])
  for (const [n, text, position] of [[10, 'INCOMPLETE DRAFT', 'before'], [11, 'Outstanding items', 'after']]) props.resultBindings.bindings.push(binding(`notice-${n}`, n, null, text, { sourceParagraphId: null, sourceParagraphOrdinal: null, fieldKeys: [], actionIds: [], appliedActionIds: [], operationIds: [], applicationStatus: 'delivery_notice', deliveryPosition: position }))
  const h = await harness(props), paper = h.element.querySelector('[data-document-paper]')
  assert.match(paper.textContent, /^INCOMPLETE DRAFT/); assert.match(paper.textContent, /Outstanding items$/)
  assert.equal(paper.querySelector('ins').textContent, '100', 'Registered notices do not discard the verified business diff')
  assert.equal(paper.querySelectorAll('[data-review-added][data-review-binding]').length, 0, 'Delivery notices are not business inputs')
  h.props.resultBindings.bindings.find(row => row.bindingId === 'notice-10').text = 'Unverified replacement'
  await settle()
  assert.equal(h.element.querySelectorAll('[data-document-paper] ins').length, 0, 'A changed notice receipt retains the complete-reading fallback')
  h.dispose(); console.log('PASS: revision-bound delivery notices preserve complete order and do not relax mismatch guards')
}
;(async () => { await failedAuxiliaryReadDoesNotHideSavedRevisionEditing(); await unverifiedMappingStillReadsCurrentSavedBody(); await compactLimitsKeepEveryRiskAndParentOwnedStaleStatus(); await completeSavedRevisionChanges(); await warningSourceLabels(); await completeDocumentModes(); await searchableVariableSelection(); await selectedActualTokenDiff(); await nativeLineBreakTokenDiff(); await linkedNativeNavigation(); await branchGuidanceAndAddedMapping(); await verifiedReadingGuardsAndLocales(); await replacedTemplateKeepsCurrentOriginal(); await recordedEvidenceContext(); await appliedSharedParagraphAttribution(); await unavailableSavedLedger(); await missingCrossFileTargetDoesNotFallback() })().catch(error => { console.error(error); process.exitCode = 1 })
