/* Mounted preview behavior using explicit PDF.js and DOM geometry boundaries.
   This synthetic fixture proves Range offsets, lifecycle and controls; actual
   PDF/font pixels and browser layout still require real browser acceptance. */
const assert = require('node:assert/strict')
const path = require('node:path')
const { JSDOM } = require('jsdom')
const window = new JSDOM('<main id="app"></main>').window
for (const key of ['window', 'document', 'Element', 'HTMLElement', 'SVGElement', 'Node', 'Document', 'ShadowRoot']) globalThis[key] = key === 'window' ? window : window[key]
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { settle } = require('./helpers/render-controls.cjs')
const deferred = () => { let resolve; const promise = new Promise(ok => { resolve = ok }); return { promise, resolve } }
const textPositions = new WeakMap(), layers = [], renders = [], reads = [], warnings = [], available = [], missingPoints = [], inspected = [], unavailableRanges = new Set()
const pendingOldText = deferred()
const pendingCurrentText = deferred()
let readerWidth = 660
let measurementReady = true
Object.defineProperty(window.HTMLElement.prototype, 'clientWidth', { get() { return this.classList.contains('pdf-surface') ? readerWidth : 0 } })
Object.defineProperty(window.HTMLElement.prototype, 'clientHeight', { get() { return this.classList.contains('pdf-surface') ? 200 : 0 } })
window.HTMLElement.prototype.scrollTo = function ({ top, left }) { this.scrollTop = top; this.scrollLeft = left }
window.HTMLElement.prototype.getBoundingClientRect = function () { return { left: 100, top: 50, width: parseFloat(this.style.width) || 0, height: parseFloat(this.style.height) || 0 } }
window.HTMLCanvasElement.prototype.getContext = function () { return { canvas: this } }
window.requestAnimationFrame = callback => setTimeout(callback, 0)
window.cancelAnimationFrame = clearTimeout
window.Range.prototype.getClientRects = function () {
  if (!measurementReady) return []
  const position = textPositions.get(this.startContainer)
  if (!position || this.endContainer !== this.startContainer) return []
  const before = position.advances.slice(0, this.startOffset).reduce((sum, width) => sum + width, 0)
  const width = position.advances.slice(this.startOffset, this.endOffset).reduce((sum, advance) => sum + advance, 0)
  return [{ left: 100 + (position.x + before) * position.scale, top: 50 + position.y * position.scale, width: width * position.scale, height: 12 * position.scale }]
}
function item(str, x, y, advances) { return { str, x, y, advances: advances ?? Array.from(str, () => 7) } }
const firstRun = item('preface — saved contract', 20, 198, [...Array(10).fill(6), ...Array(14).fill(11)])
const secondRun = item('paragraph. tail', 80, 214, [...Array(10).fill(9), ...Array(5).fill(6)])
const pageText = page => page === 1 ? [firstRun, secondRun] : page === 2 ? [item('same target', 80, 198), item('same target', 81, 199)] : [item('different saved paragraph.', 80, 198)]
class SyntheticTextLayer {
  constructor({ textContentSource, container, viewport, textDivs, textContentItemsStr }) {
    Object.assign(this, { textContentSource, container, viewport, textDivs, textContentItemsStr, cancelled: false }); layers.push(this)
  }
  async render() {
    if (this.textContentSource.failLayer) throw new Error('Synthetic text-layer failure')
    for (const text of this.textContentSource.items) {
      const span = document.createElement('span'); span.textContent = text.str
      this.textDivs.push(span); this.textContentItemsStr.push(text.str); this.container.append(span)
      textPositions.set(span.firstChild, { ...text, scale: this.viewport.scale })
    }
  }
  cancel() { this.cancelled = true }
}
function renderTextLayer(options) {
  const layer = new SyntheticTextLayer(options)
  return { promise: layer.render(), cancel() { layer.cancel() } }
}
const loaded = loadVue(path.join(__dirname, '../src/components/DraftingPdfPreview.vue'), {
  globals: { window }, boundaries: {
    'pdfjs-dist/build/pdf.worker.min.mjs?url': { default: 'synthetic-worker' },
    'pdfjs-dist': { GlobalWorkerOptions: {}, renderTextLayer, getDocument({ url }) { return {
      promise: Promise.resolve({ numPages: 3, async destroy() {}, async getPage(page) { return {
        rotate: 0,
        getViewport({ scale }) { return { width: 612 * scale, height: 792 * scale, scale } },
        getTextContent() { reads.push({ url, page }); if (url === 'broken-text-request') throw new Error('Synthetic text request failure'); return url === 'waiting-old' ? pendingOldText.promise : url === 'waiting-current' ? pendingCurrentText.promise : Promise.resolve({ items: pageText(page), styles: {}, lang: 'en', failLayer: url === 'broken-text-layer' }) },
        render(options) { const task = { url, page, options, promise: Promise.resolve(), cancelled: false, cancel() { this.cancelled = true } }; renders.push(task); return task }
      } } }), async destroy() {}
    } } }
  }
})
const geometry = pageNumber => ({ kind: 'point', pageNumber, x: 80, y: 200, pageWidth: 612, pageHeight: 792, unit: 'pt', origin: 'top-left', pageBox: 'crop', rotation: 0 })
const source = vue.ref('saved-current'), selected = vue.ref('SYNTHETIC-exact'), request = vue.ref(0), selectWords=vue.ref(false), immersive=vue.ref(false), selections=[]
const focusText=vue.ref(''),focusFailures=[]
const locations = vue.ref([
  { bindingId: 'SYNTHETIC-exact', label: 'Saved paragraph', text: 'saved contract\nparagraph.', geometry: geometry(1) },
  { bindingId: 'SYNTHETIC-ambiguous', label: 'Repeated paragraph', text: 'same target', geometry: geometry(2) },
  { bindingId: 'SYNTHETIC-not-found', label: 'Absent paragraph', text: 'saved absent paragraph.', geometry: geometry(3) }
])
const app = vue.createApp({ render: () => vue.h(loaded.default, { source: source.value, title: 'Synthetic saved PDF', locale: 'en', locations: locations.value, selectedBindingId: selected.value, locationRequest: request.value, selectWords:selectWords.value,immersive:immersive.value,focusText:focusText.value,onFocusUnavailable:reason=>focusFailures.push(reason),onSelection:value=>selections.push(value), onBinding: id => inspected.push(id), onRangeUnavailable: id => { warnings.push(id); unavailableRanges.add(id) }, onRangeAvailable: id => { available.push(id); unavailableRanges.delete(id) }, onLocationUnavailable: id => missingPoints.push(id) }) })
const ranges = () => [...document.querySelectorAll('.pdf-binding-range')]
const anchors = () => [...document.querySelectorAll('.pdf-binding-point')]
async function settled() { await settle(); await new Promise(resolve => setTimeout(resolve, 5)); await settle() }
const click = text => [...document.querySelectorAll('button')].find(button => button.textContent.trim() === text).click()

;(async () => {
  app.mount('#app'); await settled()
  assert.equal(ranges().length, 2, 'The full saved paragraph spans two measured text runs')
  assert.equal(anchors().length, 0, 'An exact paragraph uses rectangular fragments instead of an anchor pin')
  assert.equal(ranges()[0].style.left, '80px', 'The first range begins at its exact substring offset after the prefix')
  assert.equal(ranges()[0].style.width, '154px', 'The first range width comes from Range glyph measurements')
  assert.equal(ranges()[1].style.width, '90px')
  assert.equal(document.querySelector('.pdf-surface').scrollTop, 98, 'Selected navigation centers the measured first line')
  assert.equal(warnings.length, 0)
  assert.ok(available.includes('SYNTHETIC-exact'), 'A proven exact range publishes positive availability')
  ranges()[1].click(); assert.deepEqual(inspected, ['SYNTHETIC-exact'])
  assert.equal(document.querySelector('.pdf-text-layer').getAttribute('aria-hidden'), 'true')
  console.log('PASS: saved text maps to precise measured multi-line rectangles with public click and scroll controls')
  selectWords.value=true;await settled()
  const word=document.querySelector('[data-source-word="0"]');assert(word)
  word.dispatchEvent(new window.KeyboardEvent('keydown',{key:' ',bubbles:true}));await settled()
  assert.equal(selections.at(-1).words[0].text,'preface');assert.equal(selections.at(-1).source,'saved-current')
  const paper=document.querySelector('.pdf-paper')
  paper.dispatchEvent(new window.MouseEvent('pointerdown',{button:0,clientX:110,clientY:240,bubbles:true}))
  paper.dispatchEvent(new window.MouseEvent('pointermove',{clientX:165,clientY:265,bubbles:true}))
  paper.dispatchEvent(new window.MouseEvent('pointerup',{clientX:165,clientY:265,bubbles:true}));await settled()
  assert.deepEqual(Array.from(selections.at(-1).words,word=>word.text),['preface'],'Geometry box excludes words and columns outside the selected rectangle')
  const readsBeforeExpansion=reads.length,eventsBeforeExpansion=selections.length
  immersive.value=true;readerWidth=1000;await settled()
  assert.equal(document.querySelector('[data-source-word="0"]').getAttribute('aria-checked'),'true','Refitting the original page restores the selected exact word')
  immersive.value=false;readerWidth=660;await settled()
  assert.equal(document.querySelector('[data-source-word="0"]').getAttribute('aria-checked'),'true')
  assert.deepEqual(Array.from(selections.at(-1).words,word=>word.text),['preface'])
  assert.equal(reads.length,readsBeforeExpansion,'Immersive layout reuses cached page text')
  assert.ok(selections.slice(eventsBeforeExpansion).every(value=>value.words.length===1),'Resizing does not publish an empty selection')
  assert.equal(renders.at(-1).page,1,'Immersive entry and exit keep the current page')
  selectWords.value=false;await settled();assert.equal(document.querySelectorAll('[data-source-word]').length,0)
  console.log('PASS: original-page word selection supports keyboard and exact geometry without changing existing binding navigation')

  const initialLocations = locations.value
  locations.value = initialLocations.map(location => ({ ...location, highlight: false })); request.value++; await settled()
  assert.equal(ranges().length, 0); assert.equal(anchors().length, 0, 'Navigation-only targets draw neither range boxes nor anchor marks')
  const warningCount = warnings.length
  selected.value = 'SYNTHETIC-not-found'; request.value++; await settled()
  assert.ok(document.querySelector('canvas').getAttribute('aria-label').includes('Page 3'), 'Suppressing furniture highlights preserves its selected physical-page navigation')
  assert.equal(ranges().length + anchors().length, 0)
  assert.equal(warnings.length, warningCount, 'Intentional display suppression is not a failed text-range measurement')
  locations.value = initialLocations; selected.value = 'SYNTHETIC-exact'; request.value++; await settled()
  assert.equal(ranges().length, 2, 'Reselecting a drawable variable target restores measured boxes')
  console.log('PASS: navigation-only targets remain readable and locate their own page without boxes, crosshairs or false range warnings')

  measurementReady = false; request.value++; await settled()
  assert.equal(ranges().length, 0); assert.equal(anchors().length, 1)
  assert.ok(unavailableRanges.has('SYNTHETIC-exact'), 'An unavailable browser measurement retains the anchor with an explicit range warning')
  measurementReady = true; request.value++; await settled()
  assert.equal(ranges().length, 2); assert.equal(anchors().length, 0)
  assert.equal(unavailableRanges.has('SYNTHETIC-exact'), false, 'A newly proven exact range clears the previous warning through the positive event')
  console.log('PASS: recovered exact measurements publish availability and clear prior range warnings')

  click('Zoom in'); await settled()
  assert.equal(ranges()[0].style.left, '100px')
  assert.equal(ranges()[0].style.width, '192.5px')
  assert.equal(reads.filter(read => read.url === 'saved-current' && read.page === 1).length, 1, 'Zoom reuses the current page text content and rerenders its text layer at the new viewport')
  assert.equal(layers[0].cancelled, true)
  console.log('PASS: zoom remeasures browser ranges in the canvas viewport and caches page text without caching stale rectangles')

  selected.value = 'SYNTHETIC-ambiguous'; request.value++; await settled()
  assert.equal(renders.at(-1).page, 2)
  assert.equal(ranges().length, 0)
  assert.equal(anchors().length, 1)
  assert.match(anchors()[0].getAttribute('aria-label'), /anchor/i)
  assert.equal(anchors()[0].textContent, '⌖')
  assert.ok(warnings.includes('SYNTHETIC-ambiguous'))
  assert.equal(missingPoints.length, 0, 'Range ambiguity does not remove the independently verified paragraph anchor')
  selected.value = 'SYNTHETIC-not-found'; request.value++; await settled()
  assert.equal(ranges().length, 0); assert.equal(anchors().length, 1)
  assert.ok(warnings.includes('SYNTHETIC-not-found'))
  console.log('PASS: ambiguous or absent full text remains an explicitly labeled anchor and emits a separate range warning')

  focusText.value='same target';await settled()
  assert.equal(renders.at(-1).page,2)
  assert.match(document.querySelector('[data-source-text-matches]').textContent,/2 text matches/)
  assert.equal(document.querySelectorAll('.source-focus-range').length,2,'Same-page repeated text is displayed at every measured occurrence')
  focusText.value='paragraph.';await settled()
  const matchingPage=document.querySelector('[aria-label="Matching page"]')
  assert.deepEqual(Array.from(matchingPage.options,option=>option.value),['','1','3'],'Cross-page repeated text offers each matching page')
  assert.equal(focusFailures.length,0,'Multiple matches are not a location error')
  matchingPage.value='3';matchingPage.dispatchEvent(new window.Event('change',{bubbles:true}));await settled()
  assert.equal(renders.at(-1).page,3)
  assert.equal(document.querySelectorAll('.source-focus-range').length,1)
  focusText.value='THIS PASSAGE DOES NOT EXIST';await settled()
  assert.deepEqual(focusFailures,['not_found'],'Missing source text has a distinct diagnosis')
  assert.equal(document.querySelector('[data-source-text-matches]'),null,'Obsolete matches disappear when the linked passage changes')
  focusText.value='';await settled()
  console.log('PASS: same-page and cross-page source occurrences remain navigable without granting edit authority; missing text is distinct')

  source.value = 'waiting-current'; selected.value = 'SYNTHETIC-exact'; request.value++; await settled()
  assert.equal(document.querySelector('.pdf-surface').getAttribute('aria-busy'), 'false')
  assert.equal(document.querySelector('.pdf-paper').style.display, '', 'Pending optional text preparation leaves the completed canvas readable')
  assert.equal(ranges().length, 0); assert.equal(anchors().length, 1)
  assert.ok(unavailableRanges.has('SYNTHETIC-exact'))
  pendingCurrentText.resolve({ items: pageText(1), styles: {}, lang: 'en' }); await settled()
  assert.equal(ranges().length, 2); assert.equal(anchors().length, 0)
  assert.equal(unavailableRanges.has('SYNTHETIC-exact'), false, 'Late current text publishes exact ranges and clears the transient warning')
  console.log('PASS: slow optional text preparation leaves the rendered canvas readable and publishes verified ranges when ready')

  source.value = 'waiting-old'; selected.value = 'SYNTHETIC-exact'; request.value++; await settled()
  assert.equal(ranges().length, 0, 'The old PDF ranges disappear while replacement content is loading')
  source.value = 'new-current'; await settled()
  assert.equal(renders.at(-1).url, 'new-current', 'Pending old text content cannot block the replacement canvas')
  assert.equal(ranges().length, 2)
  pendingOldText.resolve({ items: [item('old wrong text', 80, 198)], styles: {}, lang: 'en' }); await settled()
  assert.equal(ranges().length, 2, 'A late previous PDF text result cannot replace the current ranges')
  assert.equal(reads.filter(read => read.url === 'new-current').length, 1)
  console.log('PASS: source replacement cancels pending text preparation, clears content caches and rejects late old ranges')
  for (const failedSource of ['broken-text-request', 'broken-text-layer']) {
    source.value = failedSource; request.value++; await settled()
    assert.equal(renders.at(-1).url, failedSource)
    assert.equal(document.querySelector('canvas').style.display, '')
    assert.equal(document.querySelector('.pdf-surface').getAttribute('aria-busy'), 'false', 'An optional text-layer failure cannot block successful canvas reading')
    assert.equal(document.querySelector('.pdf-error'), null)
    assert.equal(ranges().length, 0); assert.equal(anchors().length, 1)
  }
  console.log('PASS: text extraction or text-layer failure leaves the valid canvas readable with honest anchor-only navigation')
  app.unmount(); assert.equal(layers.at(-1).cancelled, true)
})().catch(error => { app.unmount(); console.error(error); process.exitCode = 1 })
