/* Controller behavior only: these tests do not establish that a real browser rendered PDF pixels. */
const assert = require('node:assert/strict')
const path = require('node:path')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const runtime = loadTypeScript(path.join(__dirname, '../src/drafting/pdf-preview.ts'), {})
const { createPdfPreview } = runtime
const tick = () => new Promise(resolve => setImmediate(resolve))
function deferred() { let resolve, reject; const promise = new Promise((ok, fail) => { resolve = ok; reject = fail }); return { promise, resolve, reject } }
function harness() {
  const state = { status: 'idle', page: 1, pages: 0, error: '' }
  const loads = [], renders = []
  const adapter = {
    load(source) { const task = { ...deferred(), source, destroyed: false, async destroy() { this.destroyed = true } }; loads.push(task); return task },
    async render(document, page, current) { const task = { ...deferred(), document, page, current, cancelled: false, cancel() { this.cancelled = true; this.reject(new Error('Rendering cancelled')) } }; renders.push(task); return task }
  }
  return { state, loads, renders, preview: createPdfPreview(adapter, state) }
}
function document(pages) { return { numPages: pages, destroyed: false, async destroy() { this.destroyed = true } } }
async function ready(h, pages = 3) { const opened = h.preview.open('current'); h.loads.at(-1).resolve(document(pages)); await tick(); h.renders.at(-1).resolve(); await opened }
let checks = 0
async function check(name, run) { await run(); checks++; console.log(`PASS: ${name}`) }

;(async () => {
  await check('an explicitly cited physical page is the first render of its original document', async () => {
    const h = harness(); const opened = h.preview.open('cited-original', 17)
    h.loads.at(-1).resolve(document(74)); await tick()
    assert.equal(h.renders.length, 1); assert.equal(h.renders[0].page, 17)
    h.renders[0].resolve(); await opened
    assert.equal(h.state.page, 17); assert.equal(h.state.status, 'ready'); h.preview.close()
  })
  await check('only the requested current page becomes ready and page bounds are respected', async () => {
    const h = harness(); await ready(h, 5)
    assert.equal(h.state.status, 'ready'); assert.equal(h.state.page, 1); assert.equal(h.state.pages, 5)
    const next = h.preview.showPage(2); await tick(); assert.equal(h.renders.at(-1).page, 2)
    h.renders.at(-1).resolve(); await next; assert.equal(h.state.page, 2)
    const last = h.preview.showPage(99); await tick(); assert.equal(h.renders.at(-1).page, 5)
    h.renders.at(-1).resolve(); await last; assert.equal(h.state.page, 5)
    const invalid = h.preview.showPage(Number.NaN); await tick(); assert.equal(h.renders.at(-1).page, 5)
    h.renders.at(-1).resolve(); await invalid; assert.equal(h.state.page, 5)
    h.preview.close(); assert.equal(h.loads[0].destroyed, true)
  })
  await check('a replaced source load is destroyed and its late result cannot replace the current document', async () => {
    const h = harness(); const old = h.preview.open('old'); const current = h.preview.open('new')
    assert.equal(h.loads[0].destroyed, true)
    h.loads[1].resolve(document(2)); await tick(); h.renders[0].resolve(); await current
    const obsolete = document(17); h.loads[0].resolve(obsolete); await old
    assert.equal(obsolete.destroyed, true); assert.equal(h.state.pages, 2); assert.equal(h.state.status, 'ready')
    h.preview.close()
  })
  await check('changing pages cancels the former render and cannot publish its completion', async () => {
    const h = harness(); await ready(h)
    const first = h.preview.showPage(2); await tick(); const obsolete = h.renders.at(-1)
    const second = h.preview.showPage(3); await tick()
    assert.equal(obsolete.cancelled, true); assert.equal(obsolete.current(), false)
    h.renders.at(-1).resolve(); await Promise.all([first, second])
    assert.equal(h.state.page, 3); assert.equal(h.state.status, 'ready'); h.preview.close()
  })
  await check('leaving preview destroys loading work and a late page preparation is cancelled', async () => {
    const h = harness(); const preparation = deferred()
    let cancelled = false
    h.preview = createPdfPreview({ load: source => h.loads[0] = { ...deferred(), source, destroyed: false, async destroy() { this.destroyed = true } }, render: () => preparation.promise }, h.state)
    const opened = h.preview.open('leaving'); h.loads[0].resolve(document(4)); await tick()
    h.preview.close(); assert.equal(h.loads[0].destroyed, true)
    preparation.resolve({ promise: Promise.resolve(), cancel() { cancelled = true } }); await opened
    assert.equal(cancelled, true); assert.equal(h.state.status, 'idle')
  })
  await check('a source switch waits for cancellation to release the shared canvas before rendering its page', async () => {
    const h = harness(); await ready(h)
    const oldPage = h.preview.showPage(2); await tick(); const oldRender = h.renders.at(-1)
    oldRender.cancel = () => { oldRender.cancelled = true }
    const switched = h.preview.open('replacement'); h.loads.at(-1).resolve(document(2)); await tick()
    assert.equal(oldRender.cancelled, true); assert.equal(h.renders.length, 2)
    oldRender.reject(new Error('Cancellation completed')); await tick()
    assert.equal(h.renders.length, 3); h.renders.at(-1).resolve(); await Promise.all([oldPage, switched])
    assert.equal(h.state.page, 1); assert.equal(h.state.status, 'ready'); h.preview.close()
  })
  await check('render errors are observable and retry can render the same page without reusing an old job', async () => {
    const h = harness(); await ready(h)
    const failed = h.preview.showPage(2); await tick(); h.renders.at(-1).reject(new Error('Page render failed')); await failed
    assert.equal(h.state.status, 'error'); assert.match(h.state.error, /Page render failed/)
    const retried = h.preview.showPage(2); await tick(); h.renders.at(-1).resolve(); await retried
    assert.equal(h.state.status, 'ready'); assert.equal(h.state.error, ''); assert.equal(h.state.page, 2); h.preview.close()
  })
  console.log(`PASS: ${checks} PDF preview lifecycle scenarios; real canvas visibility still requires browser acceptance`)
})().catch(error => { console.error(error); process.exitCode = 1 })
