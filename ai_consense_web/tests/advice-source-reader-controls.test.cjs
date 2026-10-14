const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, text, find, settle, deferred } = require('./helpers/render-controls.cjs')
async function component(loadOriginal, loadPdf = () => ({ promise: Promise.resolve({ numPages: 20, destroy: async () => {} }), destroy: async () => {} })) {
  const citation = vue.ref({ evidenceId: '[1]', fileLabel: 'first.pdf' }), project = vue.ref('project-a'), scope = vue.ref('contract')
  const revoked = [], created = []
  const loaded = loadVue(path.join(__dirname, '../src/components/AdviceSourceReader.vue'), { boundaries: {
    'vue-i18n': require('vue-i18n'), 'pdfjs-dist': { getDocument: loadPdf, GlobalWorkerOptions: {} }, 'pdfjs-dist/build/pdf.worker.min.mjs?url': { default: 'worker-url' },
    '@/components/DraftingPdfPreview.vue': { default: { props: ['source','title','initialPage'], render() { return vue.h('div', { class: 'actual-reader', source: this.source, physicalPage: this.initialPage }, this.title) } } },
    '@/advice/sourceOriginal': { loadAdviceOriginal: loadOriginal }, '@/advice/docxSourceReading': { readAdviceDocx: () => {}, docxPositionKey: () => {} }
  }, globals: { AbortController, Blob, Uint8Array, URL: { createObjectURL(blob) { const url = 'blob:source-' + (created.length + 1); created.push({ blob, url }); return url }, revokeObjectURL(url) { revoked.push(url) } } } })
  const h = renderer(), app = h.createApp({ render: () => vue.h(loaded.default, { citation: citation.value, projectId: project.value, scope: scope.value }) })
  app.use(require('vue-i18n').createI18n({ legacy: false, locale: 'en', messages: { en: {} } })); app.mount(h.root)
  await settle()
  return { ...h, citation, project, scope, revoked, created, dispose: () => app.unmount() }
}
const original = (name, pages = [7]) => ({ filename: name, type: 'pdf', sha256: 'a'.repeat(64), pages, pointers: [], bytes: new TextEncoder().encode('%PDF-1.7').buffer })
test('new answer and scope discard a late old original and abort its request; object URLs are released', async () => {
  const first = deferred(), second = deferred(), calls = []
  const h = await component((project, citation, scope, signal) => { calls.push({ project, citation, scope, signal }); return calls.length === 1 ? first.promise : second.promise })
  try {
    h.citation.value = { evidenceId: '[2]', fileLabel: 'second.pdf' }; await settle()
    assert.equal(calls[0].signal.aborted, true)
    second.resolve(original('second-original.pdf', [13,14])); await settle()
    first.resolve(original('old-first.pdf')); await settle()
    assert.match(text(h.root), /second-original/); assert.doesNotMatch(text(h.root), /old-first/)
    assert.equal(h.created.length, 1)
    assert.equal(find(h.root, node => node.props?.class === 'actual-reader').props.physicalPage, 13)
    h.scope.value = 'fullset'; await settle(); assert.equal(calls.at(-1).scope, 'fullset')
    assert.ok(h.revoked.includes('blob:source-1'))
  } finally { h.dispose() }
})
test('late PDF preflight cannot replace the next citation, and out-of-range physical page is explicit', async () => {
  const oldPdf = deferred(), tasks = []; let count = 0
  const h = await component(async (_project, citation) => original(citation.fileLabel, citation.evidenceId === '[1]' ? [17] : [99]), () => {
    const task = { promise: ++count === 1 ? oldPdf.promise : Promise.resolve({ numPages: 5, destroy: async () => {} }), cancelled: false, destroy: async () => { task.cancelled = true } }; tasks.push(task); return task
  })
  try {
    h.citation.value = { evidenceId: '[2]', fileLabel: 'second.pdf' }; await settle()
    assert.equal(tasks[0].cancelled, true)
    oldPdf.resolve({ numPages: 20, destroy: async () => {} }); await settle()
    assert.match(text(h.root), /physical page could not be verified/)
    assert.equal(find(h.root, node => node.props?.class === 'actual-reader').props.physicalPage, 1)
    assert.doesNotMatch(text(h.root), /first.pdf/)
  } finally { h.dispose() }
})
test('stale original shows a clear error and never an excerpt masquerading as a file', async () => {
  const h = await component(async () => { throw new Error('SOURCE_DOCUMENT_SUPERSEDED') })
  try { assert.match(text(h.root), /identity has changed/); assert.match(text(h.root), /SOURCE_DOCUMENT_SUPERSEDED/); assert.equal(h.created.length, 0) }
  finally { h.dispose() }
})
