const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, text, find, settle, deferred } = require('./helpers/render-controls.cjs')
async function component(loadOriginal, loadPdf = () => ({ promise: Promise.resolve({ numPages: 20, destroy: async () => {} }), destroy: async () => {} }), readDocx = () => {}, initialModule, initialAutoLoad) {
  const citation = vue.ref({ evidenceId: '[1]', fileLabel: 'first.pdf' }), project = vue.ref('project-a'), scope = vue.ref('contract')
  const sourceModule = vue.ref(initialModule)
  const autoLoad = vue.ref(initialAutoLoad)
  const revoked = [], created = []
  const loaded = loadVue(path.join(__dirname, '../src/components/AdviceSourceReader.vue'), { boundaries: {
    'vue-i18n': require('vue-i18n'), 'pdfjs-dist': { getDocument: loadPdf, GlobalWorkerOptions: {} }, 'pdfjs-dist/build/pdf.worker.min.mjs?url': { default: 'worker-url' },
    '@/components/DraftingPdfPreview.vue': { default: {
      props: ['source','title','initialPage'], emits: ['loaded'],
      setup(props, { emit }) {
        let alive = true, task
        vue.onMounted(() => { task = loadPdf({ url: props.source }); void task.promise.then(pdf => { if (alive) emit('loaded', pdf.numPages) }) })
        vue.onBeforeUnmount(() => { alive = false; if (task) void task.destroy() })
        return () => vue.h('div', { class: 'actual-reader', source: props.source, physicalPage: props.initialPage }, props.title)
      }
    } },
    '@/advice/sourceOriginal': { loadAdviceOriginal: loadOriginal }, '@/advice/docxSourceReading': { readAdviceDocx: readDocx }
  }, globals: { AbortController, Blob, Uint8Array, URL: { createObjectURL(blob) { const url = 'blob:source-' + (created.length + 1); created.push({ blob, url }); return url }, revokeObjectURL(url) { revoked.push(url) } } } })
  const h = renderer(), app = h.createApp({ render: () => vue.h(loaded.default, { citation: citation.value, projectId: project.value, scope: scope.value, sourceModule: sourceModule.value, autoLoad: autoLoad.value }) })
  app.use(require('vue-i18n').createI18n({ legacy: false, locale: 'en', messages: { en: {} } })); app.mount(h.root)
  await settle()
  return { ...h, citation, project, scope, sourceModule, autoLoad, revoked, created, dispose: () => app.unmount() }
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
test('late PDF load cannot replace the next citation, and the single preview reports out-of-range pages', async () => {
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
test('an explicit Open original fetches once, and cited page changes reuse that PDF reader', async () => {
  let originalReads = 0, pdfLoads = 0
  const h = await component(async () => { originalReads++; return original('requested.pdf', [7,9]) }, () => {
    pdfLoads++; return { promise: Promise.resolve({ numPages: 20 }), destroy: async () => {} }
  }, undefined, undefined, false)
  try {
    assert.equal(originalReads, 0, 'Selecting a citation must not download its complete original')
    assert.equal(pdfLoads, 0)
    const open = find(h.root, node => node.tag === 'button' && text(node) === 'Open original')
    assert.ok(open, 'The original control is directly visible')
    await open.props.onClick(); await settle()
    assert.equal(originalReads, 1); assert.equal(pdfLoads, 1, 'Only the preview parses the PDF')
    const page = find(h.root, node => node.tag === 'button' && text(node) === 'Physical page 9')
    await page.props.onClick(); await settle()
    assert.equal(find(h.root, node => node.props?.class === 'actual-reader').props.physicalPage, 9)
    assert.equal(pdfLoads, 1, 'Changing the cited page must not reopen the document')
    h.citation.value = { evidenceId: '[2]', fileLabel: 'next.pdf' }; await settle()
    assert.equal(originalReads, 1, 'A new citation waits for another explicit open')
    assert.ok(h.revoked.includes('blob:source-1'))
  } finally { h.dispose() }
})
test('stale original shows a clear error and never an excerpt masquerading as a file', async () => {
  const h = await component(async () => { throw new Error('SOURCE_DOCUMENT_SUPERSEDED') })
  try { assert.match(text(h.root), /identity has changed/); assert.match(text(h.root), /source file or its registered identity changed/); assert.equal(h.created.length, 0) }
  finally { h.dispose() }
})

test('ordinary native positions reach the DOCX reader intact and module changes abort the old source request', async () => {
  const sourceSha256 = 'a'.repeat(64), nodePath = '/{urn:word}document[1]/{urn:word}body[1]/{urn:word}tr[2]'
  const native = { kind: 'docx_native', sourceSha256, partName: 'word/document.xml', partSha256: 'b'.repeat(64), nodePath }
  const conflicting = { ...native, partSha256: 'c'.repeat(64) }, calls = [], readings = []
  const h = await component(async (project, citation, scope, signal, sourceModule) => {
    calls.push({ project, citation, scope, signal, sourceModule })
    return { ...original('actual.docx'), type: 'docx', nativePositions: [native, conflicting], pointers: [] }
  }, undefined, async (_bytes, _sha, positions) => {
    readings.push(positions)
    throw new Error('DOCX_PART_VERSION_MISMATCH')
  }, 'vetting')
  try {
    assert.equal(calls[0].sourceModule, 'vetting')
    assert.equal(readings[0].length, 2, 'Conflicting part digests must reach the validating reader before deduplication')
    assert.deepEqual(Array.from(readings[0], position => position.partSha256), [native.partSha256, conflicting.partSha256])
    assert.match(text(h.root), /complete original could not be loaded/i)
    assert.equal(find(h.root, node => node.props?.class === 'docx-source-flow'), undefined, 'Conflicting locations never display source HTML')
    h.sourceModule.value = 'advisory'; await settle()
    assert.equal(calls[0].signal.aborted, true); assert.equal(calls[1].sourceModule, 'advisory')
    assert.ok(h.revoked.includes('blob:source-1'))
  } finally { h.dispose() }
})
