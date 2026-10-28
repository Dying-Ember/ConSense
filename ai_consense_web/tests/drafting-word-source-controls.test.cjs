const assert = require('node:assert/strict')
const path = require('node:path')
const { JSDOM } = require('jsdom')
const window = new JSDOM('<!doctype html><html><head></head><body><main></main></body></html>', { pretendToBeVisual: true }).window
Object.assign(global, { window, document: window.document, Element: window.Element, HTMLElement: window.HTMLElement, SVGElement: window.SVGElement, Node: window.Node })
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const review = loadTypeScript(path.join(__dirname, '../src/drafting/source-review-state.ts'), { dependencies: { './source-review/value-dependencies.js': './source-review/value-dependencies.js' } })
const helper = loadTypeScript(path.join(__dirname, '../src/drafting/word-source-selection.ts'), { dependencies: { './source-review-state': review } })
const registry = new Map()
window.CSS = { highlights: registry }
window.Highlight = class extends Array { constructor(...ranges) { super(...ranges) } }

const ns = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main', sha = 'a'.repeat(64), paragraphSha = 'b'.repeat(64)
const pathId = n => `word/document.xml#/w:document[1]/w:body[1]/w:p[${n}]`
const position = n => `/{${ns}}document[1]/{${ns}}body[1]/{${ns}}p[${n}]`
const paragraph = (n, text) => ({ id: `p${n}`, text, ordinal: n, sourceSha256: sha, paragraphSha256: paragraphSha, sourcePosition: { partName: 'word/document.xml', nodePath: position(n), sourceSha256: sha } })
const reading = { id: '41', fileName: 'TEST.docx', format: 'docx', canEdit: true, sourceSha256: sha, paragraphs: [paragraph(1, 'Bound text'), paragraph(2, 'Original text')] }
const converted = { docxSha256: sha, coverage: { complete: true }, warnings: [], paragraphs: [{ id: pathId(1), text: 'Bound text' }, { id: pathId(2), text: 'Original text' }], html: `<p data-native-paragraph="${pathId(1)}">Bound text</p><p data-native-paragraph="${pathId(2)}">Changed text</p>` }
const api = { reviewSource: async () => ({ arrayBuffer: async () => new ArrayBuffer(0) }) }
const component = loadVue(path.join(__dirname, '../src/components/DraftingWordSource.vue'), { boundaries: { '@/api': { draftingApi: api }, '@/drafting/document-reading': { convertDocumentReading: async () => converted }, '@/drafting/word-source-selection': helper } }).default
const events = [], props = vue.reactive({ projectId: 'TEST', reading, locale: 'en', locations: [], locationRequest: 0, spans: [] })
const app = vue.createApp({ setup: () => () => vue.h(component, { ...props, onSelection: value => events.push({ selection: value }), onError: value => events.push({ error: value }) }) })
const flush = async () => { await Promise.resolve(); await vue.nextTick(); await new Promise(resolve => setImmediate(resolve)); await vue.nextTick() }

;(async () => {
  app.mount(document.querySelector('main')); await flush()
  const surface = document.querySelector('[data-word-source-surface]'), paragraphs = surface.querySelectorAll('p')
  assert.ok(document.querySelector('[data-word-coverage-warning]'), 'Partially unbound Word text receives an explicit coverage warning')
  const unbound = document.createRange(); unbound.setStart(paragraphs[1].firstChild, 0); unbound.setEnd(paragraphs[1].firstChild, 7)
  window.getSelection().removeAllRanges(); window.getSelection().addRange(unbound)
  surface.dispatchEvent(new window.MouseEvent('mouseup', { bubbles: true })); await flush()
  assert.match(events.at(-1).error, /not exactly bound/, 'Selecting an unbound passage provides actionable feedback')
  const bound = document.createRange(); bound.setStart(paragraphs[0].firstChild, 0); bound.setEnd(paragraphs[0].firstChild, 5)
  window.getSelection().removeAllRanges(); window.getSelection().addRange(bound)
  surface.dispatchEvent(new window.MouseEvent('mouseup', { bubbles: true })); await flush()
  assert.equal(events.at(-2).selection.text, 'Bound', 'Unbound content does not prevent selecting a verified paragraph')
  assert.equal(events.at(-1).error, '')
  const span = events.at(-2).selection.native
  const unchanged = surface.textContent
  props.locations = ['p1']; props.spans = [span]; props.locationRequest++
  await flush()
  assert.equal(registry.size, 1)
  assert.equal(Array.from(registry.values())[0][0].toString(), 'Bound', 'Only the verified exact character range is highlighted')
  assert.equal(surface.textContent, unchanged, 'Highlighting cannot change original Word selection offsets')
  props.spans = [{ ...span, sourceSha256: 'c'.repeat(64) }]; props.locationRequest++
  await flush()
  assert.equal(registry.size, 0)
  assert.match(document.querySelector('[data-word-location-notice]').textContent, /could not be verified/)
  window.CSS = { highlights: { delete() {}, set() { throw Error('unsupported highlight') } } }
  props.spans = [span]; props.locationRequest++
  await flush()
  assert.match(document.querySelector('[data-word-location-notice]').textContent, /cannot highlight exact characters/, 'A failed browser registry also falls back safely')
  assert.equal(document.head.querySelectorAll('style').length, 0, 'Failed highlight registration does not leave stale styles')
  window.CSS = undefined; props.spans = [span]; props.locations = []; props.locationRequest++
  await flush()
  assert.match(document.querySelector('[data-word-location-notice]').textContent, /cannot highlight exact characters/, 'Unsupported browsers report paragraph-only location')
  assert.ok(paragraphs[0].classList.contains('word-source-location'), 'A verified span supplies its own fallback paragraph location')
  props.spans = []; props.locations = ['missing']; props.locationRequest++
  await flush()
  assert.match(document.querySelector('[data-word-location-notice]').textContent, /could not be located/, 'Unmapped locations never fail silently')
  app.unmount()
  assert.equal(document.head.querySelectorAll('style').length, 0, 'Unmount removes generated highlight styling')
  console.log('PASS: Word controls expose partial bindings, invalid selections, precise highlights and location fallback')
})().catch(error => { console.error(error); process.exitCode = 1; app.unmount() })
