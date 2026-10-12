// Actual rendered reader interaction; all records below are explicit synthetic data.
const assert = require('node:assert/strict'), path = require('node:path')
const { JSDOM } = require('jsdom'), dom = new JSDOM('<!doctype html><html><body></body></html>')
Object.assign(globalThis, { window: dom.window, document: dom.window.document, Element: dom.window.Element, HTMLElement: dom.window.HTMLElement, SVGElement: dom.window.SVGElement, Node: dom.window.Node })
const vue = require('vue'), { loadVue } = require('./helpers/load-vue.cjs')
const component = loadVue(path.join(__dirname, '../src/components/DraftingDocumentReview.vue'), { globals: { document, window, Element, Node, HTMLElement }, boundaries: { dompurify: { default: require('dompurify')(dom.window) } } }).default
const nativePath = ordinal => `word/document.xml#/w:document[1]/w:body[1]/w:p[${ordinal}]`
const reading = (sha, rows) => ({ docxSha256: sha, paragraphs: rows.map(([ordinal, text]) => ({ id: nativePath(ordinal), path: nativePath(ordinal), ordinal, text, htmlText: text })), html: rows.map(([ordinal, text]) => `<p data-native-paragraph="${nativePath(ordinal)}">${text}</p>`).join('') })
const field = (key, label) => ({ key, kind: 'text', label: { en: label, zhHans: label, zhHant: label } })
function base() {
  const fields = [field('contractIdentity', 'Contract identity'), field('fee', 'Fee'), field('method', 'Tender method')]
  const rows = [[1, 'Invitation'], [2, 'Pay [fee] dollars.'], [3, 'Old instructions.']]
  const binding = (id, ordinal, extra = {}) => ({ bindingId: id, bookmarkName: id, sourceParagraphId: nativePath(ordinal), resultParagraphId: nativePath(ordinal), sourceParagraphOrdinal: ordinal, resultParagraphOrdinal: ordinal, sourceText: rows[ordinal - 1][1], text: rows[ordinal - 1][1], fieldKeys: ['contractIdentity', 'fee', 'method'], actionIds: ['price', 'method-retain', 'identity-retain'], appliedActionIds: [], operationIds: [], applicationStatus: 'unchanged', locationStatus: 'exact', geometryStatus: 'unavailable', ...extra })
  return {
    projectId: 'SYNTHETIC-REVIEW', fileKey: 'SCT', loading: false, locale: 'en', dirtyKeys: [],
    document: { fileKey: 'SCT', title: 'Synthetic SCT', generated: true, revisionId: 'synthetic-r1', sourceSha256: 'synthetic-source', docxSha256: 'synthetic-result' },
    original: reading('synthetic-source', rows), result: reading('synthetic-result', [[1, 'Invitation'], [2, 'Pay 100 dollars.'], [3, 'Old instructions.']]),
    sourceBindings: { fileKey: 'SCT', view: 'source', sourceSha256: 'synthetic-source', bindings: [binding('price-block', 2), binding('instruction-block', 3)] },
    resultBindings: { fileKey: 'SCT', view: 'result', sourceSha256: 'synthetic-source', docxSha256: 'synthetic-result', revisionId: 'synthetic-r1', bindings: [binding('price-block', 2, { text: 'Pay 100 dollars.', appliedActionIds: ['price'], operationIds: ['price-fill'], applicationStatus: 'applied' }), binding('instruction-block', 3)] },
    fields, values: { contractIdentity: 'UNRELATED-2025', fee: '100', method: 'Electronic' }, fieldStates: { contractIdentity: 'adopted', fee: 'adopted', method: 'adopted' },
    variables: fields.map(({ key }) => ({ key, value: key === 'fee' ? '100' : key === 'method' ? 'Electronic' : 'UNRELATED-2025', source: `${key}-source`, candidates: [{ value: key === 'fee' ? '100' : key === 'method' ? 'Electronic' : 'UNRELATED-2025', sourceDocumentId: key === 'fee' ? 21 : key === 'method' ? 22 : 23, sourceHash: `${key}-hash`, fileName: `${key}-message.pdf`, sourceQuote: `${key}-quotation`, reason: `${key}-reason` }] })),
    actions: [{ id: 'price', document: 'SCT', clause: 'SCT fee', inputKeys: ['fee'], action: 'fill', detail: { en: 'Fill the recorded fee.' } }, { id: 'method-retain', document: 'SCT', clause: 'SCT method', inputKeys: ['method'], action: 'retain' }, { id: 'identity-retain', document: 'SCT', clause: 'SCT identity', inputKeys: ['contractIdentity'], action: 'retain' }]
  }
}
const settle = async () => { for (let index = 0; index < 12; index++) { await Promise.resolve(); await vue.nextTick() } }
async function harness(extra = {}) {
  const props = vue.reactive({ ...base(), ...extra }), events = []
  dom.window.HTMLElement.prototype.scrollTo = function (position) { this.scrollTop = position.top ?? 0 }
  const element = document.createElement('div'); document.body.append(element)
  const app = vue.createApp({ render: () => vue.h(component, { ...props, onInput: key => events.push(['input', key]), onFile: key => events.push(['file', key]) }) }); app.mount(element); await settle()
  return { props, element, events, async click(selector) { const node = element.querySelector(selector); assert(node, `Rendered ${selector}`); node.click(); await settle() }, async key(selector, key) { const node = element.querySelector(selector); assert(node); node.dispatchEvent(new dom.window.KeyboardEvent('keydown', { key, bubbles: true, cancelable: true })); await settle() }, dispose() { app.unmount(); element.remove() } }
}
async function appliedInputWinsOverBroadRegistration() {
  const h = await harness(), before = JSON.stringify(h.props)
  assert.equal(h.element.querySelector('.review-inspector select').value, 'contractIdentity')
  await h.click('[data-review-binding="price-block"]')
  assert.equal(h.element.querySelector('.review-inspector select').value, 'fee', 'Click selects the sole input of the verified saved edit, not an unrelated old selection or retained association')
  const context = h.element.querySelector('[data-change-context]'); assert(context, 'The clicked saved action and its evidence have immediate priority')
  assert.match(context.textContent, /Fee/); assert.match(context.textContent, /100/); assert.match(context.textContent, /fee-message\.pdf/); assert.match(context.textContent, /fee-quotation/)
  assert.doesNotMatch(context.textContent, /contractIdentity-quotation|method-quotation|UNRELATED-2025/)
  assert.equal(h.element.querySelector('[data-association-choice="method"]'), null, 'A broad paragraph association cannot dilute an exclusively applied input')
  assert.equal(context.querySelector('[data-change-action="price"]')?.textContent.includes('SCT fee'), true)
  assert.equal(context.querySelector('[data-change-action="method-retain"]'), null, 'Retained associated actions are not presented as the cause of this edit')
  assert.match(h.element.querySelector('[data-current-value]').textContent, /100/)
  assert.equal(h.element.querySelector('.review-inspector').scrollTop, 0, 'New click returns the evidence inspector to its action context at the top')
  assert.deepEqual(h.events, []); assert.equal(JSON.stringify(h.props), before, 'Inspection cannot mutate adopted inputs, receipts or generated content')
  h.dispose(); console.log('PASS: actual saved input outranks broad associations and old evidence, with no mutations')
}
async function jointEditShowsEveryCauseWithoutAnotherClick() {
  const props = base(); props.actions[0].inputKeys = ['fee', 'method']
  const h = await harness(props)
  await h.key('[data-review-binding="price-block"]', 'Enter')
  const context = h.element.querySelector('[data-change-context]'); assert(context)
  assert.equal(context.querySelectorAll('[data-change-variable]').length, 2, 'Every input of the joint saved action is immediately visible')
  for (const key of ['fee', 'method']) { assert(context.querySelector(`[data-change-variable="${key}"]`)); assert.match(context.textContent, new RegExp(`${key}-quotation`)); assert.match(context.textContent, new RegExp(`${key}-message\\.pdf`)) }
  assert.doesNotMatch(context.textContent, /contractIdentity-quotation|UNRELATED-2025/)
  assert.equal(h.element.querySelector('.review-inspector select').value, '', 'An unrelated former input is not shown as the sole cause of a joint action')
  assert.match(context.textContent, /Joint saved change/i)
  assert.equal(h.element.querySelector('[data-current-value]'), null, 'Old single-input detail must not survive a joint click')
  await h.click('[data-association-choice="method"]'); assert.match(h.element.querySelector('[data-current-value]').textContent, /Electronic/)
  assert.equal(h.element.querySelector('[data-change-context]').querySelectorAll('[data-change-variable]').length, 2, 'Choosing a detail does not erase the joint action evidence')
  h.element.querySelector('.review-inspector').scrollTop = 200
  await h.key('[data-review-binding="price-block"]', ' ')
  assert.equal(h.element.querySelector('.review-inspector').scrollTop, 0)
  assert.equal(h.element.querySelector('[data-change-context]').querySelectorAll('[data-change-variable]').length, 2)
  await h.click('[data-review-field="contractIdentity"]'); assert.equal(h.element.querySelector('[data-change-context]'), null, 'Explicit independent input navigation ends the clicked-action context')
  assert.match(h.element.querySelector('.review-evidence').textContent, /contractIdentity-quotation/)
  h.dispose(); console.log('PASS: keyboard joint selection immediately shows all causes and preserves optional detail navigation')
}
async function onlyMatchedSavedActionsSupplyEvidenceKeys() {
  for (const missing of ['operation', 'binding-action']) {
    const props = base(), binding = props.resultBindings.bindings[0]
    binding.appliedActionIds.push('method-retain')
    if (missing === 'binding-action') binding.actionIds = binding.actionIds.filter(id => id !== 'method-retain')
    const h = await harness(props); await h.click('[data-review-binding="price-block"]')
    const context = h.element.querySelector('[data-change-context]'); assert(context)
    assert.equal(h.element.querySelector('.review-inspector select').value, 'fee', `An isolated applied id without its ${missing} cannot add another causal variable`)
    assert.equal(context.querySelectorAll('[data-change-variable]').length, 1)
    assert.equal(context.querySelector('[data-change-variable="method"]'), null)
    assert.equal(context.querySelector('[data-change-action="method-retain"]'), null)
    h.dispose()
  }
  const props = base(); props.resultBindings.bindings[0].appliedActionIds.push('method-retain'); props.resultBindings.bindings[0].operationIds.push('method-retain-P2')
  const h = await harness(props); await h.click('[data-review-binding="price-block"]')
  const context = h.element.querySelector('[data-change-context]'); assert.equal(context.querySelectorAll('[data-change-variable]').length, 2)
  for (const id of ['price', 'method-retain']) assert(context.querySelector(`[data-change-action="${id}"]`), 'Distinct fully matched actions remain visible as a genuine joint edit')
  assert.match(context.textContent, /Joint saved change/i); assert.doesNotMatch(context.textContent, /contractIdentity-quotation/)
  h.dispose(); console.log('PASS: causal input keys and displayed actions require the same saved action and operation identities')
}
async function nonInputChangesDoNotReuseOldEvidence() {
  for (const kind of ['editorial', 'manual', 'unattributed']) {
    const props = base(), row = props.resultBindings.bindings[1]
    if (kind !== 'unattributed') for (const binding of [props.sourceBindings.bindings[1], row]) { binding.fieldKeys = []; binding.actionIds = [] }
    props.result = reading('synthetic-result', [[1, 'Invitation'], [2, 'Pay 100 dollars.']])
    Object.assign(row, { text: '', locationStatus: 'removed', operationIds: [kind === 'editorial' ? 'SCT-editorial-note-01-P3' : 'unknown-delete'], appliedActionIds: [] })
    if (kind === 'editorial') Object.assign(row, { documentEditStatus: 'applied', editorialOperationIds: ['SCT-editorial-note-01-P3'] })
    if (kind === 'manual') Object.assign(row, { applicationStatus: 'body_edited', bodyOperationIds: ['manual-delete'] })
    const h = await harness(props); await h.click('[data-review-binding="instruction-block"]')
    const context = h.element.querySelector('[data-change-context]'); assert(context)
    assert.match(context.textContent, kind === 'editorial' ? /template instruction cleanup/i : kind === 'manual' ? /manual document change/i : /cannot be attributed|unverified/i)
    assert.equal(context.querySelectorAll('[data-change-variable]').length, 0, `${kind} must not borrow source evidence from a registered business variable`)
    assert.equal(h.element.querySelector('[data-current-value]'), null); assert.equal(h.element.querySelector('.review-evidence:not(.change-context)'), null)
    assert.equal(h.element.querySelector('.review-inspector select').value, '')
    h.dispose()
  }
  console.log('PASS: editorial, manual and unverified edits explain their status without stale business evidence')
}
async function resetGuardAndRegisteredContext() {
  const h = await harness(); h.props.disabled = true; await settle()
  await h.click('[data-review-binding="price-block"]'); assert.equal(h.element.querySelector('[data-change-context]'), null)
  assert.equal(h.element.querySelector('.review-inspector select').value, 'contractIdentity')
  h.props.disabled = false; await settle(); await h.click('[data-review-binding="price-block"]')
  h.props.projectId = 'SYNTHETIC-OTHER'; await settle(); assert.equal(h.element.querySelector('[data-change-context]'), null)
  await h.click('[data-review-binding="price-block"]'); h.props.fileKey = 'NTT'; await settle(); assert.equal(h.element.querySelector('[data-change-context]'), null)
  h.props.fileKey = 'SCT'; await settle(); await h.click('[data-review-mode="original"]'); await h.click('[data-review-binding="price-block"]')
  const context = h.element.querySelector('[data-change-context]'); assert(context)
  assert.match(context.textContent, /registered.*related inputs/i, 'Original context declares registration, not an applied saved cause')
  assert.doesNotMatch(context.textContent, /Joint saved change/i)
  await h.click('[data-review-mode="review"]'); assert.equal(h.element.querySelector('[data-change-context]'), null, 'Changing the carrier ends the prior clicked-context claim')
  h.dispose(); console.log('PASS: disabled, project, file and carrier transitions do not reuse clicked-action evidence')
}
;(async () => { await appliedInputWinsOverBroadRegistration(); await jointEditShowsEveryCauseWithoutAnotherClick(); await onlyMatchedSavedActionsSupplyEvidenceKeys(); await nonInputChangesDoNotReuseOldEvidence(); await resetGuardAndRegisteredContext(); dom.window.close() })().catch(error => { console.error(error); dom.window.close(); process.exitCode = 1 })
