// Real DOM input sanitization + DraftingView/InputField; transport and heavy readers are declared boundaries.
const assert = require('node:assert/strict')
const path = require('node:path')
const { JSDOM } = require('jsdom')
const window = new JSDOM('<main id="app"></main><section id="nested"></section><section id="legacy"></section>').window
for (const key of ['window', 'document', 'Element', 'HTMLElement', 'SVGElement', 'Node', 'Document', 'ShadowRoot']) globalThis[key] = key === 'window' ? window : window[key]
const vue = require('vue'), piniaRuntime = require('pinia')
const { loadVue } = require('./helpers/load-vue.cjs')
const { settle } = require('./helpers/render-controls.cjs')
const copy = value => JSON.parse(JSON.stringify(value))
const label = title => ({ en: title, zhHans: title, zhHant: title })
const field = (key, kind = 'text', extra = {}) => ({ key, kind, label: label(key), ...extra })
const fields = [field('review-18'), field('review-preview'), field('review-candidate'), field('review-number', 'number'), field('review-date', 'date'), field('review-choice', 'choice', { options: [{ value: 'A', label: label('A') }] }), field('contractTitle', 'contract'), field('unconfirmed-parent','boolean'), field('conditional-child','text',{condition:{all:[{field:'unconfirmed-parent',operator:'eq',value:true}]}})]
const schema = { ruleVersion: 'TEST', graphRevisionId: 'TEST-draft', groups: [{ id: 'TEST-group', label: label('TEST group'), fields }] }
const source = { id: '41', fileKey: 'NTT', fileName: 'TEST ONLY template.docx', format: 'docx', sourceSha256: 'a'.repeat(64), parseStatus: 'PARSED', canEdit: true, evidenceStatus: 'bound' }
const revision = { id: schema.graphRevisionId, status: 'DRAFT', sourceStale: false, graph: { kind: 'consense-template-graph-v1', graph: { variables: [], events: [] }, selectedDocuments: { NTT: '41' }, sources: [source] } }
const values = Object.fromEntries(fields.map(item => [item.key, '']))
values['unconfirmed-parent']='false'
const patches = [], corrections = [], modelCalls = []
let candidateRun = 'TEST-run'
let candidate = { value: 'TEST initial single line', sourceDocumentId: 42, fileName: 'TEST ONLY evidence.docx', sourceQuote: 'TEST exact quotation', fieldDefinitionHash: 'TEST-candidate-definition', graphRevisionId: revision.id, extractionRunId: candidateRun }
const variable = item => ({ key: item.key, kind: item.kind, label: item.label, value: values[item.key], confirmed: item.key === 'unconfirmed-parent' ? false : !!values[item.key], manuallyEdited: item.key === 'unconfirmed-parent' ? false : !!values[item.key], graphRevisionId: revision.id, fieldDefinitionHash: 'TEST-definition-' + item.key, candidates: item.key === 'review-candidate' ? [copy(candidate)] : [] })
const candidates = () => ({ runId: candidateRun, graphRevisionId: revision.id, evidenceRevision: 'TEST-evidence', variables: [variable(fields[2])], catalog: schema, candidateDefinitions: { 'TEST-candidate-definition': fields[2] } })
const api = {
  automationLatest: async () => null, catalog: async () => schema,
  variables: async () => fields.map(variable), templates: async () => ['NTT', 'SCT', 'SCC'].map(key => ({ key, tag: 'ok', fileName: 'TEST ONLY ' + key + '.docx' })),
  inputs: async () => [], documents: async () => [], extractTrace: async () => null,
  reviewHead: async () => ({ currentRevisionId: null, current: null, draftRevisionId: revision.id, draft: revision, sources: [source] }),
  reviewCandidates: async () => candidates(), plan: async () => ({ actions: [], unresolved: [] }),
  updateVariable: async (_id, key, patch) => { patches.push({ key, ...copy(patch) }); values[key] = patch.value; return variable(fields.find(item => item.key === key)) },
  reviewCorrectCandidate: async (_id, revisionId, key, patch) => { corrections.push({ revisionId, key, ...copy(patch) }); candidateRun = 'TEST-corrected'; candidate = { ...candidate, value: patch.value, sourceQuote: patch.sourceQuote, extractionRunId: candidateRun }; return candidates() },
  templateReading: async (_id, fileKey) => ({ fileKey, fileName: 'TEST ONLY template.docx', sourceHash: 'TEST', format: 'unsupported', paragraphs: [], catalogueSourceVerified: false }),
  templateSource: async () => new Blob(['TEST ONLY']),
  generate: async () => { modelCalls.push('generate'); throw Error('Input must not generate') },
  extractVariables: async () => { modelCalls.push('extract'); throw Error('Input must not extract') }
}
const Modal = { props: ['open'], setup: (props, { slots }) => () => props.open ? vue.h('section', { 'data-test-modal': '' }, slots.default?.()) : null }
const TemplatePreview = { props: ['selectedKey'], emits: ['edit'], setup: (props, { emit }) => () => vue.h('button', { 'data-test-preview-edit': '', onClick: () => emit('edit', props.selectedKey) }, 'TEST open preview editor') }
const loaded = loadVue(path.join(__dirname, '../src/views/DraftingView.vue'), { boundaries: {
  '@/api': { draftingApi: api, setLlmProfileResolver() {} }, pinia: piniaRuntime, 'vue-i18n': require('vue-i18n'),
  '@/components/AppModal.vue': { default: Modal }, './AppModal.vue': { default: Modal },
  '@/components/DraftingSourceReview.vue': { default: { render: () => null } },
  '@/components/DraftingDocumentWorkspace.vue': { default: { render: () => null } },
  '@/components/DraftingTemplatePreview.vue': { default: TemplatePreview },
  './DraftingPdfPreview.vue': { default: { render: () => null } }
}, globals: { window, document, structuredClone, Blob, URL, crypto: require('node:crypto').webcrypto, localStorage: { getItem: () => 'en', setItem() {} } } })
const pinia = piniaRuntime.createPinia(); piniaRuntime.setActivePinia(pinia)
const store = loaded.loadLocal(path.join(__dirname, '../src/stores/app.ts')).useAppStore(); store.switchProject('TEST-multiline')
const app = vue.createApp(loaded.default); app.use(pinia); app.use(loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n); app.mount('#app')
const multiline = [...Array(10).fill('x'.repeat(217)), 'z'.repeat(222)].join('\n')
const button = title => [...document.querySelectorAll('button')].find(node => node.textContent.trim() === title)
async function click(node) { assert.ok(node, 'Rendered control'); node.click(); await settle() }
async function input(node, value) { assert.ok(node, 'Rendered input'); node.value = value; node.dispatchEvent(new window.Event('input', { bubbles: true })); await settle() }

;(async () => {
  try {
    await settle(); await click(button('3 Adopt and plan'))
    assert.ok(document.querySelector('[data-variable="conditional-child"]'),'An unconfirmed false candidate does not hide a dependent input')
    assert.equal(document.getElementById('input-unconfirmed-parent').value,'false','The unconfirmed candidate stays visible')
    const initial = document.getElementById('input-review-18')
    await input(initial, multiline)
    assert.equal(initial.value.length, 2402, 'A first paste into empty dynamic text preserves all 2402 characters before any save')
    assert.equal(initial.value.split('\n').length, 11, 'A first paste retains all 11 lines')
    assert.equal(initial.tagName, 'TEXTAREA')
    await input(document.querySelector('[data-manual-note="review-18"]'), 'TEST exact project source checked')
    await click(document.querySelector('[data-save-input="review-18"]'))
    assert.equal(patches.length, 1)
    assert.equal(patches[0].value, multiline, 'The existing parent update/encode/save chain sends the exact first multiline value')
    assert.equal(document.getElementById('input-review-18').value, multiline, 'The saved readback preserves all line breaks')
    assert.equal(patches[0].graphRevisionId, revision.id)
    console.log('PASS: actual DOM first input preserves 2402 characters / 11 lines through ordinary parent save and readback')

    await click(document.querySelector('[data-open-template="review-preview"]'))
    await click(document.querySelector('[data-test-preview-edit]'))
    const preview = document.getElementById('preview-edit-review-preview')
    assert.equal(preview.tagName, 'TEXTAREA', 'The real preview-edit caller opts in for empty dynamic text too')
    await input(preview, multiline)
    assert.equal(preview.value, multiline)
    await click(button('Close'))
    assert.equal(patches.length, 1, 'Preview input remains unsaved until an explicit save')
    console.log('PASS: the real preview-edit modal preserves a first multiline draft without automatically saving')

    await click(button('Correct candidate or assignment'))
    const candidateInput = document.getElementById('candidate-review-candidate')
    assert.equal(candidateInput.tagName, 'TEXTAREA', 'The real typed candidate editor accepts initial multiline text')
    await input(candidateInput, multiline)
    await click(button('Save correction without adoption'))
    assert.equal(corrections.length, 1); assert.equal(corrections[0].value, multiline)
    assert.equal(patches.length, 1, 'Candidate correction does not adopt or save an input fact')
    assert.deepEqual(modelCalls, [])
    console.log('PASS: typed candidate correction keeps every line and remains separate from fact adoption')

    assert.equal(document.getElementById('input-review-number').type, 'number')
    assert.equal(document.getElementById('input-review-date').type, 'date')
    assert.equal(document.getElementById('input-review-choice').tagName, 'SELECT')
    assert.equal(document.getElementById('input-contractTitle-number').tagName, 'INPUT')
    assert.equal(document.getElementById('input-contractTitle-title').tagName, 'INPUT', 'Fixed contract identity layout stays single-line')

    const Component = loaded.loadLocal(path.join(__dirname, '../src/components/DraftingInputField.vue')).default
    const record = field('dynamic-record', 'object', { columnFields: [field('dynamic-detail')] })
    const list = field('dynamic-list', 'list', { columnFields: [field('dynamic-cell', 'text', { condition: { reviewRequired: true, all: [] } })] })
    const nestedValues = vue.reactive({ record: {}, list: [{ _itemId: 'TEST-row' }] })
    const nested = vue.createApp({ render: () => vue.h('div', [vue.h(Component, { field: record, value: nestedValues.record, values: {}, locale: 'en', multilineText: true, 'onUpdate': value => { nestedValues.record = value } }), vue.h(Component, { field: list, value: nestedValues.list, values: {}, locale: 'en', multilineText: true, rowIdentityKey: '_itemId', 'onUpdate': value => { nestedValues.list = value } })]) })
    nested.mount('#nested')
    try {
      const recordInput = document.getElementById('input-dynamic-record-dynamic-detail'), rowInput = document.getElementById('input-dynamic-list-1-dynamic-cell')
      assert.equal(recordInput.tagName, 'TEXTAREA'); assert.equal(rowInput.tagName, 'TEXTAREA')
      assert.match(document.querySelector('#nested [data-applicability-review-required]').textContent, /needs review.*existing answer is retained/i)
      await input(recordInput, multiline); await input(rowInput, multiline)
      assert.equal(nestedValues.record['dynamic-detail'], multiline); assert.equal(nestedValues.list[0]['dynamic-cell'], multiline); assert.equal(nestedValues.list[0]._itemId, 'TEST-row')
    } finally { nested.unmount() }
    const legacy = vue.createApp(Component, { field: field('legacy-short-name'), value: null, values: {}, locale: 'en' }); legacy.mount('#legacy')
    try { assert.equal(document.getElementById('input-legacy-short-name').tagName, 'INPUT', 'Legacy fixed text callers retain the existing single-line default') } finally { legacy.unmount() }
    console.log('PASS: recursive dynamic text opts in; fixed identity, number/date/choice and legacy text retain their controls')
  } finally { app.unmount(); window.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
