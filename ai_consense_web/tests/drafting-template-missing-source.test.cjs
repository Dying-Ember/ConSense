const assert = require('node:assert/strict')
const path = require('node:path')
const { JSDOM } = require('jsdom')
const window = new JSDOM('<main id="app"></main>').window
for (const key of ['window', 'document', 'Element', 'HTMLElement', 'SVGElement', 'Node', 'Document', 'ShadowRoot']) globalThis[key] = key === 'window' ? window : window[key]
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { settle, deferred } = require('./helpers/render-controls.cjs')
const componentPath = process.env.DRAFTING_PREVIEW_COMPONENT_UNDER_TEST || path.join(__dirname, '../src/components/DraftingTemplatePreview.vue')
const unsupported = { fileKey: 'NTT', fileName: 'newly-uploaded.txt', sourceHash: 'unused', format: 'unsupported', catalogueSourceVerified: false, paragraphs: [] }
const field = { key: 'contractPeriodMonths', kind: 'number', label: { en: 'Completion period' }, affects: [{ document: 'NTT', clause: 'Completion', paragraphs: '2' }] }

function harness({ readingResult = unsupported, readingError, sourceError, availability } = {}) {
  const requests = [], events = []
  const values = vue.reactive({ contractPeriodMonths: '40', otherDirty: 'Keep the human edit' })
  const available = vue.ref(availability), disabled = vue.ref(false)
  const responses = { readingResult, readingError, sourceError }
  const component = loadVue(componentPath, { boundaries: {
    '@/api': { draftingApi: {
      async templateReading(project, fileKey) { requests.push(['reading', project, fileKey]); if (responses.readingError) throw responses.readingError; return responses.readingResult },
      async templateSource(project, fileKey) { requests.push(['source', project, fileKey]); if (responses.sourceError) throw responses.sourceError; return new Blob(['source']) }
    } },
    'mammoth/mammoth.browser.min.js': { default: require('mammoth/mammoth.browser.min.js') },
    dompurify: { default: require('dompurify')(window) },
    'pdfjs-dist': { GlobalWorkerOptions: {}, getDocument() { throw new Error('No PDF requested') } },
    'pdfjs-dist/build/pdf.worker.min.mjs?url': { default: 'external-pdf-worker' }
  }, globals: { document: window.document, HTMLElement: window.HTMLElement, URL, crypto: require('node:crypto').webcrypto } }).default
  const app = vue.createApp({ render: () => vue.h(component, { projectId: 'MISSING-RACE-TEST', fileKey: 'NTT', locale: 'en', sourceAvailable: available.value, fields: [field], values, variables: [], actions: [], selectedKey: field.key, selectedActionId: '', fieldStates: { [field.key]: 'manual' }, dirtyKeys: [field.key], disabled: disabled.value, onUpload: fileKey => events.push(['upload', fileKey]) }) })
  app.mount('#app')
  return { responses, requests, events, values, available, disabled, dispose: () => app.unmount() }
}

function button(label) { return [...document.querySelectorAll('button')].find(node => node.textContent.trim() === label) }
function missingState() {
  assert.match(document.body.textContent, /No NTT template has been uploaded to this project\./)
  assert.ok(button('Go to template upload'))
  assert.equal(button('Retry'), undefined, 'A missing source is not an operation that can be fixed by retrying the same read')
  assert.doesNotMatch(document.body.textContent, /SOURCE_NOT_UPLOADED|Could not read the uploaded template|Unsupported template format/)
  assert.ok(document.querySelector('[data-preview-card="contractPeriodMonths"]'))
  assert.match(document.body.textContent, /Unsaved/)
  assert.match(document.body.textContent, /40/)
}

;(async () => {
  for (const stage of ['reading', 'source']) {
    const missing = new Error('SOURCE_NOT_UPLOADED: Upload the NTT standard template first.')
    const h = harness(stage === 'reading' ? { readingError: missing } : { availability: true, readingResult: { ...unsupported, format: 'docx' }, sourceError: missing })
    await settle(); missingState()
    assert.deepEqual(h.requests.map(request => request[0]), stage === 'reading' ? ['reading'] : ['reading', 'source'])
    button('Go to template upload').click(); await settle()
    assert.deepEqual(h.events, [['upload', 'NTT']])
    assert.deepEqual(JSON.parse(JSON.stringify(h.values)), { contractPeriodMonths: '40', otherDirty: 'Keep the human edit' })
    h.dispose()
  }
  console.log('PASS: missing-source races on metadata and bytes show the upload state without raw backend errors, retaining selected input and dirty values; omitted availability still discovers the API')

  for (const message of ['SOURCE_FILE_MISSING: The stored original is unavailable.', 'Proxy failed: SOURCE_NOT_UPLOADED appeared in the upstream response.']) {
    const failure = new Error(message)
    failure.code = 4011
    const h = harness({ availability: true, readingError: failure })
    await settle()
    assert.match(document.body.textContent, /Could not read the uploaded template/)
    assert.ok(document.body.textContent.includes(message))
    assert.ok(button('Retry'), 'Real read failures retain retry even if they share the missing-source business code or mention its text')
    assert.equal(button('Go to template upload'), undefined, 'Only the explicit missing-source reason can claim that the template was not uploaded')
    h.responses.readingError = undefined
    button('Retry').click(); await settle()
    assert.match(document.body.textContent, /Unsupported template format/)
    assert.doesNotMatch(document.body.textContent, /Could not read the uploaded template/)
    assert.equal(h.requests.length, 2)
    assert.equal(h.values.otherDirty, 'Keep the human edit')
    h.dispose()
  }
  console.log('PASS: corrupt/missing storage and unrelated errors sharing code 4011 retain honest failure detail and working retry')

  const pending = deferred()
  const switching = harness({ readingResult: pending.promise })
  await settle()
  assert.match(document.body.textContent, /Loading uploaded template/)
  switching.available.value = false; await settle(); missingState()
  pending.resolve(unsupported); await settle(); missingState()
  assert.equal(switching.requests.length, 1, 'Becoming unavailable invalidates a pending result without another read')
  switching.responses.readingResult = unsupported
  switching.available.value = true; await settle()
  assert.match(document.body.textContent, /newly-uploaded\.txt/)
  assert.match(document.body.textContent, /Unsupported template format/)
  assert.equal(button('Go to template upload'), undefined)
  assert.equal(switching.requests.length, 2, 'A source becoming available permits a fresh reading request')
  assert.equal(switching.values.otherDirty, 'Keep the human edit')
  switching.dispose()
  console.log('PASS: availability changes invalidate late old content and start a fresh read after an actual upload without dirty-state mutation')

  const busy = harness({ availability: false })
  await settle(); missingState()
  busy.disabled.value = true; await settle()
  assert.equal(button('Go to template upload').disabled, true)
  button('Go to template upload').click(); await settle()
  assert.deepEqual(busy.events, [], 'Busy disabled navigation cannot emit an upload action')
  assert.deepEqual(busy.requests, [])
  busy.dispose()
  console.log('PASS: busy missing-template controls remain disabled without navigation or source requests')
})().catch(error => { console.error(error); process.exitCode = 1 })
