const assert = require('node:assert/strict')
const path = require('node:path')
const { JSDOM } = require('jsdom')
const window = new JSDOM('<main id="app"></main>').window
for (const key of ['window', 'document', 'Element', 'HTMLElement', 'SVGElement', 'Node', 'Document', 'ShadowRoot']) globalThis[key] = key === 'window' ? window : window[key]
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { settle } = require('./helpers/render-controls.cjs')
const field = { key: 'contractPeriodMonths', kind: 'number', label: { en: 'Completion period', zhHans: '工期', zhHant: '工期' }, affects: [{ document: 'NTT', clause: 'Completion', paragraphs: '2' }] }
const events = []
const values = vue.reactive({ contractPeriodMonths: '40', otherDirty: 'retain this edit' })
const fieldState = vue.ref('manual')
let metadata = { fileKey: 'NTT', fileName: 'uploaded.txt', sourceHash: 'unused', format: 'unsupported', catalogueSourceVerified: false, paragraphs: [] }
let source = new Blob(['source'])
let pendingReading
const readingRequests = [], sourceRequests = []
const component = loadVue(path.join(__dirname, '../src/components/DraftingTemplatePreview.vue'), { boundaries: {
  '@/api': { draftingApi: { templateReading: async (project, fileKey) => { readingRequests.push([project, fileKey]); return pendingReading?.project === project ? pendingReading.promise : metadata }, templateSource: async (project, fileKey) => { sourceRequests.push([project, fileKey]); return source } } },
  'mammoth/mammoth.browser.min.js': { default: require('mammoth/mammoth.browser.min.js') },
  dompurify: { default: require('dompurify')(window) },
  'pdfjs-dist': { GlobalWorkerOptions: {}, getDocument() { throw new Error('PDF boundary') } },
  'pdfjs-dist/build/pdf.worker.min.mjs?url': { default: 'external-pdf-worker' }
}, globals: { document: window.document, HTMLElement: window.HTMLElement, URL, crypto: require('node:crypto').webcrypto } }).default
;(async () => {
  const missingLocale = vue.ref('en')
  const missingEvents = []
  const absent = vue.createApp({ render: () => vue.h(component, { projectId: 'MISSING-TEMPLATE-TEST', fileKey: 'NTT', sourceAvailable: false, locale: missingLocale.value, fields: [field], values, variables: [], actions: [], selectedKey: field.key, selectedActionId: '', fieldStates: { [field.key]: 'manual' }, dirtyKeys: [field.key], disabled: false, onUpload: fileKey => missingEvents.push(['upload', fileKey]) }) })
  absent.mount('#app'); await settle()
  for (const [locale, explanation, action, unsaved] of [
    ['en', 'No NTT template has been uploaded to this project.', 'Go to template upload', 'Unsaved'],
    ['zh-Hans', '当前项目尚未上传 NTT 标准模板。', '前往上传模板', '未保存'],
    ['zh-Hant', '目前項目尚未上傳 NTT 標準模板。', '前往上傳模板', '未儲存']
  ]) {
    missingLocale.value = locale; await settle()
    assert.ok(document.body.textContent.includes(explanation), `Missing source has an actionable localized state: ${locale}`)
    assert.doesNotMatch(document.body.textContent, /SOURCE_NOT_UPLOADED|Unable to read|无法读取|無法讀取|Retry|重试|重試/)
    assert.ok(document.querySelector('[data-preview-card="contractPeriodMonths"]'), 'The current input card remains available without an uploaded source')
    assert.ok(document.body.textContent.includes(unsaved))
    assert.match(document.body.textContent, /40/)
    const button = [...document.querySelectorAll('button')].find(node => node.textContent.trim() === action)
    assert.ok(button, `Upload navigation action: ${locale}`)
    button.click(); await settle()
  }
  assert.deepEqual(readingRequests, [], 'Known missing templates must not issue a reading request or generate a backend failure toast')
  assert.deepEqual(sourceRequests, [], 'Known missing templates must not issue a source request')
  assert.deepEqual(missingEvents, [['upload', 'NTT'], ['upload', 'NTT'], ['upload', 'NTT']])
  assert.deepEqual(JSON.parse(JSON.stringify(values)), { contractPeriodMonths: '40', otherDirty: 'retain this edit' })
  absent.unmount()
  console.log('PASS: known missing templates show localized upload navigation while preserving the selected card and unsaved values without failed API requests')

  const app = vue.createApp({ render: () => vue.h(component, { projectId: 'A', fileKey: 'NTT', locale: 'en', fields: [field], values, variables: [{ key: field.key, value: '38', adoptionState: 'suggested', source: 'Email', candidates: [{ value: '38', fileName: 'Project email', sourceQuote: 'Complete in 38 months.' }] }], actions: [], selectedKey: field.key, selectedActionId: '', fieldStates: { [field.key]: fieldState.value }, dirtyKeys: [field.key], disabled: false, onEdit: key => events.push(['edit', key]), onInput: key => events.push(['input', key]) }) })
  app.mount('#app'); await settle()
  assert.match(document.body.textContent, /Unsupported template format/)
  assert.match(document.body.textContent, /Unsaved/)
  assert.match(document.body.textContent, /40/)
  assert.match(document.body.textContent, /Project email/)
  document.querySelector('[data-preview-edit="contractPeriodMonths"]').click(); await settle()
  assert.deepEqual(events, [['edit', field.key]])
  assert.deepEqual(JSON.parse(JSON.stringify(values)), { contractPeriodMonths: '40', otherDirty: 'retain this edit' })
  fieldState.value = 'inactive'; await settle()
  assert.equal(document.querySelector('[data-preview-edit="contractPeriodMonths"]').disabled, true)
  assert.match(document.body.textContent, /40/)
  app.unmount()
  console.log('PASS: rendered selected-field card remains useful after unsupported-source response, carries current state/candidates, and requests parent editing without mutating dirty values')

  const bytes = require('node:fs').readFileSync(path.join(__dirname, 'fixtures/template-reading.synthetic.docx'))
  source = new Blob([bytes])
  metadata = { fileKey: 'NTT', fileName: 'actually-uploaded.docx', sourceHash: require('node:crypto').createHash('sha256').update(bytes).digest('hex'), format: 'docx', catalogueSourceVerified: true, paragraphs: [{ id: 'body-1', ordinal: 1, text: 'Invitation to tender' }, { id: 'table-2', ordinal: 2, text: 'Complete the Works in [period] months.' }, { id: 'body-3', ordinal: 3, text: 'Complete the Works in [period] months.' }] }
  const threshold = { key: 'periodAtLeast39Months', kind: 'boolean', label: { en: 'At least 39 months' }, affects: [{ document: 'NTT', clause: 'Completion', paragraphs: '2-3' }] }
  const actions = [{ id: 'completion-table', document: 'NTT', clause: 'Completion', paragraphs: '2', sourceText: metadata.paragraphs[1].text, inputKeys: [field.key, threshold.key], action: 'fill', overrideable: true }, { id: 'completion-body', document: 'NTT', clause: 'Completion', paragraphs: '3', sourceText: metadata.paragraphs[2].text, inputKeys: [field.key, threshold.key], action: 'fill', overrideable: true }, { id: 'scc-completion', document: 'SCC', clause: 'Completion period', inputKeys: [field.key, threshold.key], action: 'amend', overrideable: true }]
  const selectedKey = vue.ref(field.key)
  const scrolls = []
  const outerScrolls = []
  window.HTMLElement.prototype.scrollIntoView = function () { outerScrolls.push(this.dataset.nativeParagraph) }
  window.HTMLElement.prototype.scrollTo = function (options) { this.scrollTop = options.top; scrolls.push({ className: this.className, top: options.top }) }
  window.HTMLElement.prototype.getBoundingClientRect = function () {
    const container = this.closest('.reading-surface')
    const top = this.classList.contains('reading-surface') ? 100 : ({ 'table-2': 600, 'body-3': 900 }[this.dataset.nativeParagraph] ?? 100) - (container?.scrollTop ?? 0)
    return { top, bottom: top + 40, height: 40, left: 0, right: 400, width: 400 }
  }
  const reader = vue.createApp({ render: () => vue.h(component, { projectId: 'A', fileKey: 'NTT', locale: 'en', fields: [{ ...field, affects: [{ ...field.affects[0], paragraphs: '2-3' }] }, threshold], values, variables: [], actions, selectedKey: selectedKey.value, selectedActionId: '', fieldStates: { [field.key]: 'manual', [threshold.key]: 'missing' }, dirtyKeys: [field.key], disabled: false, onSelect: key => { events.push(['select', key]); selectedKey.value = key }, onEdit: key => events.push(['edit', key]), onTarget: id => events.push(['target', id]), onLocate: id => events.push(['locate', id]), onFile: file => events.push(['file', file]) }) })
  reader.mount('#app')
  for (let i = 0; i < 50 && !document.querySelector('[data-native-paragraph]'); i++) { await new Promise(resolve => setTimeout(resolve, 10)); await settle() }
  assert.ok(document.querySelector('table [data-native-paragraph="table-2"]'))
  assert.equal(outerScrolls.length, 0, 'Locating source text must keep the containing panel/card at its current scroll position')
  assert.equal(scrolls.at(-1).className, 'reading-surface', 'Only the source reading surface may be scrolled')
  assert.equal(document.querySelectorAll('table [data-preview-field]').length, 2)
  const sourceEdit = document.querySelector('table [data-preview-edit="contractPeriodMonths"]')
  assert.ok(sourceEdit)
  sourceEdit.click(); await settle()
  assert.deepEqual(events.at(-1), ['edit', field.key])
  assert.match(document.querySelector('[data-preview-navigation]').textContent, /1 of 2/)
  document.querySelector('[data-preview-next]').click(); await settle()
  assert.match(document.querySelector('[data-preview-navigation]').textContent, /2 of 2/)
  assert.equal(scrolls.at(-1).top, 800)
  document.querySelector('[data-preview-location="completion-table:table-2"]').click(); await settle()
  assert.match(document.querySelector('[data-preview-navigation]').textContent, /1 of 2/)
  assert.equal(scrolls.at(-1).top, 500)
  assert.deepEqual(outerScrolls, [], 'Next/direct location selection must preserve access to the selected-field card and panel header')
  document.querySelector('table [data-preview-field="periodAtLeast39Months"]').click(); await settle()
  assert.deepEqual(events.at(-1), ['select', threshold.key])
  document.querySelector('[data-preview-target="completion-table"]').click(); await settle()
  assert.deepEqual(events.at(-1), ['target', 'completion-table'])
  document.querySelector('[data-preview-locate="scc-completion"]').click(); await settle()
  assert.deepEqual(events.at(-1), ['locate', 'scc-completion'])
  document.querySelector('[data-preview-file="SCC"]').click(); await settle()
  assert.deepEqual(events.at(-1), ['file', 'SCC'])
  assert.equal(values.otherDirty, 'retain this edit')
  reader.unmount()
  console.log('PASS: real DOCX conversion keeps table identities, all field markers, location next/direct selection, and source-to-input/exact-action events without dirty-state mutation')

  const plainMetadata = metadata, plainSource = source
  const numberedZip = await require('jszip').loadAsync(bytes)
  numberedZip.file('word/document.xml', (await numberedZip.file('word/document.xml').async('string')).replace('<w:p>', '<w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr>'))
  numberedZip.file('word/numbering.xml', '<w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:abstractNum w:abstractNumId="1"><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="lowerLetter"/><w:lvlText w:val="(%1)"/></w:lvl></w:abstractNum><w:num w:numId="1"><w:abstractNumId w:val="1"/></w:num></w:numbering>')
  const numberedBytes = await numberedZip.generateAsync({ type: 'nodebuffer' })
  source = new Blob([numberedBytes]); metadata = { ...plainMetadata, sourceHash: require('node:crypto').createHash('sha256').update(numberedBytes).digest('hex') }
  const numberedLocale = vue.ref('en')
  const numberedReader = vue.createApp({ render: () => vue.h(component, { projectId: 'LIST-TEMPLATE-TEST', fileKey: 'NTT', locale: numberedLocale.value, fields: [field], values, variables: [], actions: [], selectedKey: field.key, selectedActionId: '', fieldStates: { [field.key]: 'manual' }, dirtyKeys: [field.key], disabled: false }) })
  numberedReader.mount('#app')
  for (let i = 0; i < 50 && !document.querySelector('[data-reading-numbering-notice]'); i++) { await new Promise(resolve => setTimeout(resolve, 10)); await settle() }
  const markerStyle = document.createElement('style'); markerStyle.textContent = require('node:fs').readFileSync(path.join(__dirname, '../src/styles/main.css'), 'utf8'); document.head.append(markerStyle)
  assert.equal(window.getComputedStyle(document.querySelector('.reading-surface ol')).listStyleType, 'none')
  assert.equal(document.querySelector('.reading-surface li[data-native-paragraph="body-1"]').textContent, 'Invitation to tender')
  for (const [locale, message] of [['en', 'Web-generated list markers are hidden.'], ['zh-Hans', '网页自动标号已隐藏，原编号请核对 PDF。'], ['zh-Hant', '網頁自動標號已隱藏，原編號請核對 PDF。']]) {
    numberedLocale.value = locale; await settle()
    for (let i = 0; i < 50 && !document.querySelector('[data-reading-numbering-notice]'); i++) { await new Promise(resolve => setTimeout(resolve, 10)); await settle() }
    const notice = document.querySelector('[data-reading-numbering-notice]')
    assert.ok(notice && !notice.closest('details,.reading-surface'))
    assert.ok(notice.textContent.includes(message))
  }
  assert.equal(values.otherDirty, 'retain this edit')
  numberedReader.unmount(); markerStyle.remove(); metadata = plainMetadata; source = plainSource
  console.log('PASS: real Word list conversion hides browser markers and shows visible trilingual PDF guidance without changing source text or dirty inputs')

  let resolveOld
  pendingReading = { project: 'old', promise: new Promise(resolve => { resolveOld = resolve }) }
  const project = vue.ref('old'), locale = vue.ref('en')
  const oldDocx = metadata
  const switcher = vue.createApp({ render: () => vue.h(component, { projectId: project.value, fileKey: 'NTT', locale: locale.value, fields: [field], values, variables: [], actions: [], selectedKey: field.key, selectedActionId: '', fieldStates: { [field.key]: 'manual' }, dirtyKeys: [field.key], disabled: false }) })
  switcher.mount('#app'); await settle()
  assert.match(document.body.textContent, /Loading uploaded template/)
  metadata = { ...oldDocx, fileName: 'new-project.txt', format: 'unsupported', paragraphs: [] }
  project.value = 'new'; await settle()
  resolveOld(oldDocx); await settle()
  assert.match(document.body.textContent, /new-project.txt/)
  assert.doesNotMatch(document.body.textContent, /actually-uploaded.docx/)
  assert.equal(document.querySelector('[data-native-paragraph]'), null)
  locale.value = 'zh-Hant'; await settle()
  assert.match(document.body.textContent, /不支援此模板格式/)
  assert.equal(values.otherDirty, 'retain this edit')
  switcher.unmount(); pendingReading = undefined
  console.log('PASS: pending previous-project results cannot reappear, locale switching uses current labels, and parent dirty values survive switching')

  metadata = { ...oldDocx, sourceHash: 'a'.repeat(64) }
  const mismatch = vue.createApp({ render: () => vue.h(component, { projectId: 'A', fileKey: 'NTT', locale: 'en', fields: [field], values, variables: [], actions: [], selectedKey: field.key, selectedActionId: '', fieldStates: { [field.key]: 'manual' }, dirtyKeys: [field.key], disabled: false }) })
  mismatch.mount('#app')
  for (let i = 0; i < 30 && !/source changed/.test(document.body.textContent); i++) { await new Promise(resolve => setTimeout(resolve, 10)); await settle() }
  assert.match(document.body.textContent, /source changed while loading/)
  assert.equal(document.querySelector('[data-native-paragraph]'), null)
  assert.ok(document.querySelector('[data-preview-edit="contractPeriodMonths"]'))
  mismatch.unmount()
  console.log('PASS: reading/source hash inconsistency fails honestly without rendered stale anchors and retains the independent input editor request')

  metadata = { ...oldDocx, format: 'unsupported', paragraphs: [] }
  const structured = { key: 'designResponsibilities', kind: 'list', label: { en: 'Design responsibilities' } }
  values.designResponsibilities = [{ component: 'footings', design: 'true' }]
  const returnEditor = vue.createApp({ render: () => vue.h(component, { projectId: 'A', fileKey: 'NTT', locale: 'en', fields: [structured], values, variables: [], actions: [], selectedKey: structured.key, selectedActionId: '', fieldStates: { [structured.key]: 'manual' }, dirtyKeys: [structured.key], disabled: false, onInput: key => events.push(['input', key]) }) })
  returnEditor.mount('#app'); await settle()
  assert.equal(document.querySelector('[data-preview-edit]'), null)
  document.querySelector('[data-preview-input="designResponsibilities"]').click(); await settle()
  assert.deepEqual(events.at(-1), ['input', structured.key])
  assert.equal(values.otherDirty, 'retain this edit')
  returnEditor.unmount()
  console.log('PASS: structured values return to the main input, cross-file clauses retain their action ID, and manual file tabs emit a separate file request')
})().catch(error => { console.error(error); process.exitCode = 1 })
