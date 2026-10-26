/* Actual Vue/controller behavior with PDF.js and browser geometry boundaries;
   these checks do not establish actual PDF pixels or browser CSS layout. */
const assert = require('node:assert/strict')
const path = require('node:path')
const { JSDOM } = require('jsdom')
const window = new JSDOM('<main id="app"></main>').window
for (const key of ['window', 'document', 'Element', 'HTMLElement', 'SVGElement', 'Node', 'Document', 'ShadowRoot']) globalThis[key] = key === 'window' ? window : window[key]
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { settle } = require('./helpers/render-controls.cjs')
let readerWidth = 900
const scrolls = [], loads = [], renders = [], observers = []
let deferRenders = false
Object.defineProperty(window, 'devicePixelRatio', { value: 2 })
Object.defineProperty(window.HTMLElement.prototype, 'clientWidth', { get() { return this.classList.contains('pdf-surface') ? readerWidth : 0 } })
Object.defineProperty(window.HTMLElement.prototype, 'clientHeight', { get() { return this.classList.contains('pdf-surface') ? 600 : 0 } })
window.HTMLElement.prototype.scrollTo = function (position) { this.scrollTop = position.top; this.scrollLeft = position.left; scrolls.push({ element: this, ...position }) }
window.HTMLCanvasElement.prototype.getContext = function () { return { canvas: this } }
window.requestAnimationFrame = callback => setTimeout(callback, 0)
window.cancelAnimationFrame = clearTimeout
class ResizeObserver {
  constructor(callback) { this.callback = callback; this.disconnected = false; observers.push(this) }
  observe(element) { this.element = element }
  disconnect() { this.disconnected = true }
  resize() { this.callback([{ target: this.element }]) }
}
function deferred() { let resolve, reject; const promise = new Promise((ok, fail) => { resolve = ok; reject = fail }); return { promise, resolve, reject } }
const loaded = loadVue(path.join(__dirname, '../src/components/DraftingPdfPreview.vue'), {
  globals: { window, ResizeObserver },
  boundaries: {
    'pdfjs-dist/build/pdf.worker.min.mjs?url': { default: 'external-pdf-worker' },
    'pdfjs-dist': {
      GlobalWorkerOptions: {},
      getDocument({ url }) {
        const load = { url, destroyed: false, async destroy() { this.destroyed = true } }
        loads.push(load)
        load.promise = Promise.resolve({
          numPages: url === 'replacement' ? 2 : 8,
          async destroy() {},
          async getPage(pageNumber) {
            return {
              getViewport({ scale }) { return { width: 612 * scale, height: 792 * scale, scale } },
              render(options) {
                const completion = deferRenders ? deferred() : { promise: Promise.resolve() }
                const task = { ...completion, url, pageNumber, options, cancelled: false, cancel() { this.cancelled = true; this.reject?.(new Error('Rendering cancelled')) } }
                renders.push(task)
                return task
              }
            }
          }
        })
        return load
      }
    }
  }
})
const component = loaded.default
const { draftWord } = loaded.loadLocal(path.join(__dirname, '../src/drafting/words.ts'))
const source = vue.ref('original'), immersive = vue.ref(false), locale = vue.ref('en'), initialPage = vue.ref(1)
const loadedPages = []
const app = vue.createApp({ render: () => vue.h(component, { source: source.value, title: 'Contract PDF', locale: locale.value, immersive: immersive.value, initialPage: initialPage.value, onLoaded: pages => loadedPages.push(pages) }) })
const button = key => [...document.querySelectorAll('button')].find(node => node.textContent.trim() === draftWord(key, locale.value))
const reader = () => document.querySelector('.pdf-surface')
const canvas = () => document.querySelector('canvas')
async function settled() { await settle(); await new Promise(resolve => setTimeout(resolve, 5)); await settle() }
function jump(number) {
  const input = document.querySelector('input[type="number"]')
  input.value = String(number); input.dispatchEvent(new window.Event('input', { bubbles: true }))
  document.querySelector('form').dispatchEvent(new window.Event('submit', { bubbles: true, cancelable: true }))
}

;(async () => {
  app.mount('#app'); await settled()
  assert.equal(loads.length, 1)
  assert.equal(renders.at(-1).options.viewport.width, 852)
  assert.equal(canvas().style.width, '852px')
  assert.equal(canvas().width, 1704, 'Fit width rerenders at the device pixel ratio instead of stretching CSS pixels')
  assert.equal(canvas().height, Math.ceil(792 * (852 / 612) * 2), 'The whole page viewport retains its aspect ratio')
  assert.deepEqual(Array.from(renders.at(-1).options.transform), [2, 0, 0, 2, 0, 0])
  assert.equal(canvas().style.display, '')
  assert.equal(document.querySelector('label').htmlFor, document.querySelector('input').id)
  assert.equal(reader().tabIndex, 0, 'The scrollable reader is reachable by keyboard')
  console.log('PASS: the actual reader renders a full, high resolution page fitted to its available width')

  assert.deepEqual(loadedPages, [8], 'Page-count validation reuses the document already opened by the preview')
  initialPage.value = 3; await settled()
  assert.equal(renders.at(-1).pageNumber, 3)
  assert.equal(loads.length, 1, 'A changed cited page renders from the same open PDF')
  initialPage.value = 1; await settled()
  assert.equal(loads.length, 1)
  console.log('PASS: cited page changes and page-count reporting do not parse a second PDF')

  reader().scrollTop = 350
  jump(5); await settled()
  assert.equal(renders.at(-1).pageNumber, 5)
  assert.match(canvas().getAttribute('aria-label'), /Page 5$/)
  assert.equal(document.querySelector('input').value, '5')
  assert.equal(reader().scrollTop, 0, 'Direct page navigation begins at the new page top inside the reader')
  assert.ok(scrolls.every(scroll => scroll.element === reader()), 'Navigation must not scroll the outer workspace')
  button('pdfPreviousPage').click(); await settled(); assert.equal(renders.at(-1).pageNumber, 4)
  button('pdfNextPage').click(); await settled(); assert.equal(renders.at(-1).pageNumber, 5)
  assert.equal(loads.length, 1, 'Navigation uses the already loaded PDF')
  console.log('PASS: direct page jump and previous/next render the requested page without reloading or moving the outer workspace')

  const originalScale = renders.at(-1).options.viewport.scale
  reader().scrollTop = 300
  button('pdfZoomIn').click(); await settled()
  const larger = renders.at(-1).options.viewport
  assert.ok(larger.scale > originalScale)
  assert.equal(canvas().style.width, `${larger.width}px`)
  assert.ok(canvas().width > 1704, 'Zoom must request more PDF pixels')
  assert.equal(document.querySelector('.pdf-fit-width').getAttribute('aria-pressed'), 'false')
  assert.ok(reader().scrollTop > 300, 'Zoom keeps the current reading area in view')
  button('pdfZoomOut').click(); await settled()
  assert.ok(renders.at(-1).options.viewport.scale < larger.scale)
  button('pdfFitWidth').click(); await settled()
  assert.equal(renders.at(-1).options.viewport.width, 852)
  assert.equal(document.querySelector('.pdf-fit-width').getAttribute('aria-pressed'), 'true')
  console.log('PASS: zoom changes the PDF render resolution, preserves the reading area, and fit width returns to the available width')

  button('pdfZoomOut').click(); await settled()
  const normalScale = renders.at(-1).options.viewport.scale
  readerWidth = 1300; immersive.value = true; observers.at(-1).resize(); await settled()
  assert.equal(renders.at(-1).options.viewport.width, 1252)
  assert.equal(document.querySelector('.pdf-fit-width').getAttribute('aria-pressed'), 'true', 'Expanded view starts fitted even after an explicit normal-view zoom')
  assert.equal(renders.at(-1).pageNumber, 5)
  assert.equal(document.querySelector('input').value, '5')
  assert.ok(document.querySelector('.pdf-pages--immersive'))
  assert.equal(loads.length, 1, 'Expanding the parent workspace preserves the document and page')
  readerWidth = 900; immersive.value = false; observers.at(-1).resize(); await settled()
  assert.equal(renders.at(-1).options.viewport.scale, normalScale, 'Leaving expanded view restores the previous normal-view zoom')
  assert.equal(renders.at(-1).pageNumber, 5)
  readerWidth = 1300; immersive.value = true; observers.at(-1).resize(); await settled()
  assert.equal(renders.at(-1).options.viewport.width, 1252)
  button('pdfZoomIn').click(); await settled()
  const manualScale = renders.at(-1).options.viewport.scale, renderCount = renders.length
  readerWidth = 700; observers.at(-1).resize(); await settled()
  assert.equal(renders.length, renderCount, 'Explicit zoom keeps its chosen scale when the parent narrows')
  assert.equal(renders.at(-1).options.viewport.scale, manualScale)
  assert.ok(parseFloat(canvas().style.width) > readerWidth, 'A zoomed page remains full sized for internal horizontal scrolling')
  button('pdfFitWidth').click(); await settled()
  assert.equal(renders.at(-1).options.viewport.width, 652)
  const beforeRapidClicks = renders.at(-1).options.viewport.scale
  button('pdfZoomIn').click(); button('pdfZoomIn').click(); await settled()
  assert.ok(renders.at(-1).options.viewport.scale > beforeRapidClicks + .45, 'Successive clicks before page preparation completes must both count')
  button('pdfFitWidth').click(); await settled()
  console.log('PASS: parent expansion rerenders fit width without resetting page; fixed zoom remains readable and can be fitted again after narrowing')

  deferRenders = true
  button('pdfZoomIn').click(); await settled(); const obsolete = renders.at(-1)
  button('pdfZoomIn').click(); await settled(); const latest = renders.at(-1)
  assert.notEqual(latest, obsolete)
  assert.equal(obsolete.cancelled, true)
  latest.resolve(); await settled()
  assert.equal(canvas().style.display, '')
  assert.equal(reader().getAttribute('aria-busy'), 'false')
  button('pdfZoomOut').click(); await settled(); const replacedRender = renders.at(-1)
  source.value = 'replacement'; await settled()
  assert.equal(replacedRender.cancelled, true)
  assert.equal(loads[0].destroyed, true)
  assert.equal(renders.at(-1).url, 'replacement')
  assert.equal(renders.at(-1).pageNumber, 1)
  renders.at(-1).resolve(); await settled()
  assert.equal(document.querySelector('input').value, '1')
  assert.equal(document.querySelector('input').max, '2')
  assert.equal(canvas().style.display, '')
  assert.equal(document.querySelector('.pdf-fit-width').getAttribute('aria-pressed'), 'true')
  console.log('PASS: rapid zoom and source replacement cancel stale jobs and only the latest page becomes available')

  app.unmount()
  assert.equal(observers.at(-1).disconnected, true)
  assert.equal(loads.at(-1).destroyed, true)
  console.log('PASS: leaving the reader releases the PDF and resize observer; browser pixel/layout acceptance remains separate')
})().catch(error => { app.unmount(); console.error(error); process.exitCode = 1 })
