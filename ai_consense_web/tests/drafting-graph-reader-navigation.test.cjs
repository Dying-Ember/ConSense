// Public rendered reader navigation. All native identities/content are explicit synthetic inputs.
const assert = require('node:assert/strict'), path = require('node:path')
const { JSDOM } = require('jsdom'), dom = new JSDOM('<!doctype html><html><body></body></html>')
Object.assign(globalThis, { window: dom.window, document: dom.window.document, Element: dom.window.Element, HTMLElement: dom.window.HTMLElement, SVGElement: dom.window.SVGElement, Node: dom.window.Node })
const vue = require('vue'), { loadVue } = require('./helpers/load-vue.cjs')
const sourcePath = ordinal => `word/document.xml#/w:document[1]/w:body[1]/w:p[${ordinal}]`
const fields = [
  { key: 'fee', kind: 'text', label: { en: 'Fee' }, affects: [{ document: 'NTT', clause: 'Shared label', paragraphs: 'P2' }] },
  { key: 'method', kind: 'text', label: { en: 'Method' }, affects: [{ document: 'NTT', clause: 'Shared label', paragraphs: 'P3' }] }
]
const reading = { docxSha256: 'synthetic-source', paragraphs: [[1, 'Complete opening'], [2, 'Fee [value]'], [3, 'Method [value]'], [4, 'Complete closing']].map(([ordinal, text]) => ({ id: sourcePath(ordinal), path: sourcePath(ordinal), ordinal, text, htmlText: text })), html: '<p data-native-paragraph="' + sourcePath(1) + '">Complete opening</p><p data-native-paragraph="' + sourcePath(2) + '">Fee [value]</p><p data-native-paragraph="' + sourcePath(3) + '">Method [value]</p><p data-native-paragraph="' + sourcePath(4) + '">Complete closing</p>' }
const binding = (bindingId, ordinal, key, actionId, text) => ({ bindingId, bookmarkName: bindingId, sourceParagraphId: sourcePath(ordinal), sourceParagraphOrdinal: ordinal, sourceText: text, text, fieldKeys: [key], actionIds: [actionId], appliedActionIds: [], operationIds: [], applicationStatus: 'unchanged', locationStatus: 'exact', geometryStatus: 'unavailable' })
const navigation = (sequence, extra = {}) => ({ sequence, projectId: 'SYNTHETIC-P1', fileKey: 'NTT', fieldKey: 'method', actionId: 'method-action', clause: 'Shared label', ...extra })
const base = () => ({ projectId: 'SYNTHETIC-P1', fileKey: 'NTT', original: reading, sourceBindings: { fileKey: 'NTT', view: 'source', sourceSha256: 'synthetic-source', bindings: [binding('fee-binding', 2, 'fee', 'fee-action', 'Fee [value]'), binding('method-binding', 3, 'method', 'method-action', 'Method [value]')] }, fields, values: { fee: 'unsaved fee', method: 'unsaved method' }, variables: [], actions: [{ id: 'fee-action', document: 'NTT', clause: 'Shared label', inputKeys: ['fee'], action: 'amend' }, { id: 'method-action', document: 'NTT', clause: 'Shared label', inputKeys: ['method'], action: 'amend' }], fieldStates: { fee: 'manual', method: 'manual' }, dirtyKeys: ['fee', 'method'], locale: 'en', loading: false })
const settle = async () => { for (let index = 0; index < 12; index++) { await Promise.resolve(); await vue.nextTick() } }
async function harness(extra = {}) {
  const props = vue.reactive({ ...base(), ...extra }), events = []
  dom.window.HTMLElement.prototype.scrollTo = function (position) { this.scrollTop = position.top ?? 0 }
  const component = loadVue(path.join(__dirname, '../src/components/DraftingDocumentReview.vue'), { globals: { document, window, Element, Node, HTMLElement }, boundaries: { dompurify: { default: require('dompurify')(dom.window) } } }).default
  const element = document.createElement('div'); document.body.append(element)
  const app = vue.createApp({ render: () => vue.h(component, { ...props, onInput: key => events.push(['input', key]), onFile: file => events.push(['file', file]) }) }); app.mount(element); await settle()
  return { props, element, events, dispose() { app.unmount(); element.remove() } }
}
async function exactGraphAction() {
  const h = await harness({ graphNavigation: navigation(1) })
  assert.equal(h.element.querySelector('[data-review-mode="original"]').getAttribute('aria-pressed'), 'true')
  assert.equal(h.element.querySelector('.review-inspector select').value, 'method')
  assert.equal(h.element.querySelector('.review-focused').dataset.reviewBinding, 'method-binding', 'Exact action identity wins despite identical clause labels')
  assert.match(h.element.querySelector('[data-current-value]').textContent, /unsaved method/)
  assert.match(h.element.querySelector('[data-document-paper]').textContent, /Complete closing/)
  assert.deepEqual(h.events, [])
  h.dispose(); console.log('PASS: graph request selects the exact input/action in complete original context without emitting mutations')
}
async function missingGraphAction() {
  const h = await harness({ graphNavigation: navigation(1) })
  h.props.graphNavigation = navigation(2, { actionId: 'unmapped-action' }); await settle()
  assert.equal(h.element.querySelector('.review-focused'), null, 'An exact missing action must not fall back to another action with the same clause label')
  assert.match(h.element.querySelector('.navigation-notice').textContent, /requested.*no verified/i)
  assert.equal(h.element.querySelector('.review-inspector select').value, 'method')
  assert.match(h.element.querySelector('[data-document-paper]').textContent, /Complete opening/)
  h.props.result = reading; await settle()
  assert.equal(h.element.querySelector('.review-focused'), null, 'A later saved receipt cannot replace a missing graph target with the first paragraph')
  assert.match(h.element.querySelector('.navigation-notice').textContent, /requested.*no verified/i)
  h.dispose(); console.log('PASS: unavailable requested action clears old focus and reports the limitation without clause fallback')
}
async function asyncRequestAndProjectIdentity() {
  const h = await harness({ graphNavigation: navigation(1), loading: true, original: undefined, sourceBindings: undefined })
  h.props.original = reading; h.props.loading = false; await settle()
  assert.equal(h.element.querySelector('.review-focused'), null)
  h.props.sourceBindings = base().sourceBindings; await settle()
  assert.equal(h.element.querySelector('.review-focused').dataset.reviewBinding, 'method-binding', 'Request waits for the matching native receipt as well as original content')
  h.element.querySelector('[data-review-field="fee"]').click(); await settle()
  h.props.graphNavigation = navigation(2); await settle()
  assert.equal(h.element.querySelector('.review-focused').dataset.reviewBinding, 'method-binding', 'A new sequence can select the same requested action again')
  h.props.projectId = 'SYNTHETIC-P2'; await settle()
  assert.equal(h.element.querySelector('.review-inspector select').value, 'fee', 'Old-project graph selection is not replayed')
  h.props.graphNavigation = navigation(1, { projectId: 'SYNTHETIC-P2' }); await settle()
  assert.equal(h.element.querySelector('.review-focused').dataset.reviewBinding, 'method-binding')
  h.dispose(); console.log('PASS: graph navigation waits for async native receipts, repeats by sequence and rejects old-project requests')
}
async function potentialTargetIdentity() {
  const h = await harness({ graphNavigation: navigation(1, { actionId: '' }) })
  assert.equal(h.element.querySelector('.review-focused').dataset.reviewBinding, 'method-binding', 'A potential target may use its unique exact registered action association')
  h.props.actions.push({ id: 'another-method', document: 'NTT', clause: 'Shared label', inputKeys: ['method'], action: 'retain' })
  h.props.graphNavigation = navigation(2, { actionId: '' }); await settle()
  assert.equal(h.element.querySelector('.review-focused'), null, 'Ambiguous potential clause associations cannot invent an action choice')
  assert.match(h.element.querySelector('.navigation-notice').textContent, /requested.*no verified/i)
  h.dispose(); console.log('PASS: potential targets use only unique exact registered associations and ambiguous targets remain explicit')
}
async function guardedPendingRequest() {
  const h = await harness({ graphNavigation: navigation(1), disabled: true })
  assert.equal(h.element.querySelector('.review-focused'), null)
  h.props.disabled = false; await settle()
  assert.equal(h.element.querySelector('.review-focused')?.dataset.reviewBinding, 'method-binding', 'A pending request is applied after the busy guard clears')
  h.dispose(); console.log('PASS: pending graph navigation resumes only when existing reader guards permit it')
}
;(async () => { await guardedPendingRequest(); await exactGraphAction(); await missingGraphAction(); await asyncRequestAndProjectIdentity(); await potentialTargetIdentity(); dom.window.close() })().catch(error => { console.error(error); dom.window.close(); process.exitCode = 1 })
