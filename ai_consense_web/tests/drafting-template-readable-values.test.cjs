const assert = require('node:assert/strict')
const path = require('node:path')
const fs = require('node:fs')
const { JSDOM } = require('jsdom')
const window = new JSDOM('<main id="app"></main>').window
for (const key of ['window', 'document', 'Element', 'HTMLElement', 'SVGElement', 'Node', 'Document', 'ShadowRoot']) globalThis[key] = key === 'window' ? window : window[key]
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { settle } = require('./helpers/render-controls.cjs')

// Synthetic uploaded DOCX and catalogue-shaped values; no provider or project writes.
const bytes = fs.readFileSync(path.join(__dirname, 'fixtures/template-reading.synthetic.docx'))
const metadata = { fileKey: 'NTT', fileName: 'readable-values.synthetic.docx', sourceHash: require('node:crypto').createHash('sha256').update(bytes).digest('hex'), format: 'docx', catalogueSourceVerified: true, paragraphs: [{ id: 'body-1', ordinal: 1, text: 'Invitation to tender' }, { id: 'table-2', ordinal: 2, text: 'Complete the Works in [period] months.' }, { id: 'body-3', ordinal: 3, text: 'Complete the Works in [period] months.' }] }
const labels = (en, zhHans, zhHant = zhHans) => ({ en, zhHans, zhHant })
const bills = { key: 'billNos', kind: 'list', label: labels('Bill / Schedule numbers, descriptions and use', 'Bill／Schedule编号、说明及采用用途', 'Bill／Schedule編號、說明及採用用途'), affects: [{ document: 'NTT', clause: 'Synthetic Bill location', paragraphs: '2' }], columnFields: [
  { key: 'id', kind: 'text', hidden: true, label: labels('Internal record ID', '内部记录标识', '內部記錄標識') },
  { key: 'number', kind: 'text', label: labels('Bill / Schedule number', 'Bill／Schedule编号', 'Bill／Schedule編號') },
  { key: 'description', kind: 'text', label: labels('Formal description', '正式说明', '正式說明') },
  { key: 'type', kind: 'select', label: labels('Pricing document type', '计价文件类型', '計價文件類型'), options: [{ value: 'BQ', label: labels('Bills of Quantities (BQ)', '工程量清单（BQ）', '工程量清單（BQ）') }, { value: 'SOR', label: labels('Schedule of Rates (SOR)', '单价表（SOR）', '單價表（SOR）') }] },
  { key: 'placementText', kind: 'text', label: labels('Exact distribution wording', '分发原文', '分發原文'), optional: true }
] }
const period = { key: 'contractPeriodMonths', kind: 'number', label: labels('Completion period', '工期'), affects: [{ document: 'NTT', clause: 'Completion', paragraphs: '2' }] }
const descriptions = ['Preliminaries', 'Preambles', 'Substructure', 'Superstructure for Podium', 'Superstructure for Domestic Block', 'Plumbing Works for Podium and Domestic Block', 'Drainage', 'External Works', 'Electrical Works', 'Fire Services and Water Pump Works', 'Lift Works', 'Site Safety, Environmental Management and Other Sundry Requirements (All Provisional)', 'Provisional Sums']
const dangerousText = '<img src="invalid" onerror="throw new Error(\'untrusted row\')"> Exact project wording'
const rows = descriptions.map((description, index) => ({ number: String(index + 1), description, ...(index >= 8 && index <= 10 ? { type: 'SOR' } : {}), id: `bill-internal-record-${index + 1}`, ...(index === 8 ? { placementText: dangerousText } : {}) }))
const values = vue.reactive({ billNos: rows, contractPeriodMonths: '40', otherDirty: 'retain this unrelated edit' })
const selected = vue.ref(period.key)
const locale = vue.ref('en')
const events = []
const actions = [{ id: 'synthetic-bill-target', document: 'NTT', clause: 'Synthetic Bill location', paragraphs: '2', sourceText: metadata.paragraphs[1].text, inputKeys: [period.key, bills.key], action: 'amend', overrideable: true }]
const component = loadVue(path.join(__dirname, '../src/components/DraftingTemplatePreview.vue'), { boundaries: {
  '@/api': { draftingApi: { templateReading: async () => metadata, templateSource: async () => new Blob([bytes]) } },
  'mammoth/mammoth.browser.min.js': { default: require('mammoth/mammoth.browser.min.js') },
  dompurify: { default: require('dompurify')(window) },
  'pdfjs-dist': { GlobalWorkerOptions: {}, getDocument() { throw new Error('PDF boundary') } },
  'pdfjs-dist/build/pdf.worker.min.mjs?url': { default: 'external-pdf-worker' }
}, globals: { document: window.document, HTMLElement: window.HTMLElement, URL, crypto: require('node:crypto').webcrypto } }).default

async function ready() {
  for (let i = 0; i < 80 && !document.querySelector('.reading-surface [data-preview-field="billNos"]'); i++) { await new Promise(resolve => setTimeout(resolve, 10)); await settle() }
  assert.ok(document.querySelector('.reading-surface [data-preview-field="billNos"]'), 'Real uploaded DOCX is converted and linked to the Bill field')
}

;(async () => {
  const before = JSON.stringify(values)
  const app = vue.createApp({ render: () => vue.h(component, { projectId: 'SYNTHETIC-READABLE-VALUES', fileKey: 'NTT', locale: locale.value, fields: [period, bills], values, variables: [{ key: bills.key, value: JSON.stringify(rows), adoptionState: 'suggested', source: 'SIMULATED bill evidence', candidates: [{ value: JSON.stringify(rows), fileName: 'SIMULATED Bill email.docx', sourceQuote: 'Bill 9 | Electrical Works | Schedule of Rates.', reason: 'Test-only quotation for the Bill list.' }] }], actions, selectedKey: selected.value, selectedActionId: '', fieldStates: { [bills.key]: 'suggested', [period.key]: 'manual' }, dirtyKeys: [bills.key], disabled: false, onSelect: key => { events.push(['select', key]); selected.value = key }, onInput: key => events.push(['input', key]), onTarget: id => events.push(['target', id]) }) })
  app.mount('#app'); await ready()
  const marker = document.querySelector('.reading-surface [data-preview-field="billNos"]')
  assert.match(marker.textContent, /13 items/, 'Long Bill lists use a count in the source marker')
  assert.doesNotMatch(marker.textContent, /"number"|"description"|\[\{|bill-internal-record|Preliminaries/, 'Source marker is a concise readable summary, not the full record payload')
  assert.ok(marker.textContent.length < 180, 'A long Bill list does not expand the source marker across the whole reading panel')
  assert.match(marker.textContent, /Suggested value/)
  assert.match(marker.textContent, /Unsaved/)
  marker.click(); await settle()
  assert.deepEqual(events.at(-1), ['select', 'billNos'])
  let card = document.querySelector('[data-preview-card="billNos"]')
  assert.ok(card, 'Clicking the concise marker still selects the linked input')
  const table = card.querySelector('table')
  assert.ok(table, 'Current structured Bill value is a readable table')
  assert.match(table.querySelector('thead').textContent, /Bill \/ Schedule number/)
  assert.match(table.querySelector('thead').textContent, /Formal description/)
  assert.match(table.querySelector('thead').textContent, /Pricing document type/)
  const bodyRows = [...table.querySelectorAll(':scope > tbody > tr')]
  assert.equal(bodyRows.length, 13, 'All Bill rows remain available in the current-value table')
  descriptions.forEach((description, index) => {
    const cells = [...bodyRows[index].querySelectorAll(':scope > td')]
    assert.equal(cells[0].textContent.trim(), String(index + 1))
    assert.equal(cells[1].textContent.trim(), description, 'Formal English Bill description must remain exact')
  })
  assert.match(bodyRows[8].textContent, /Schedule of Rates \(SOR\)/, 'Stable type option is displayed with its business label')
  assert.ok(bodyRows[8].querySelector('details'), 'Extra business row wording is accessible through expandable labelled details')
  bodyRows[8].querySelector('details').open = true
  assert.match(bodyRows[8].textContent, /Exact distribution wording/)
  assert.ok(bodyRows[8].textContent.includes(dangerousText), 'Untrusted exact wording is preserved as text')
  assert.equal(card.querySelector('img'), null, 'Row wording cannot become executable HTML')
  assert.doesNotMatch(card.textContent, /bill-internal-record|"number"|"description"|\[\{/, 'Current value and candidate presentation do not leak internal IDs or JSON syntax')
  assert.match(card.textContent, /SIMULATED Bill email\.docx/)
  assert.match(card.textContent, /Bill 9 \| Electrical Works \| Schedule of Rates\./)
  assert.match(card.textContent, /Test-only quotation for the Bill list\./)
  document.querySelector('[data-preview-card="billNos"] [data-preview-input="billNos"]').click(); await settle()
  assert.deepEqual(events.at(-1), ['input', 'billNos'], 'Structured values keep the existing return-to-input action')
  assert.equal(JSON.stringify(values), before, 'Readable display and navigation never edit or adopt the parent values')

  for (const [language, count, numberLabel, descriptionLabel, typeLabel] of [
    ['zh-Hans', '13 项', 'Bill／Schedule编号', '正式说明', '单价表（SOR）'],
    ['zh-Hant', '13 項', 'Bill／Schedule編號', '正式說明', '單價表（SOR）'],
    ['en', '13 items', 'Bill / Schedule number', 'Formal description', 'Schedule of Rates (SOR)']
  ]) {
    locale.value = language; await settle(); await ready()
    assert.ok(document.querySelector('.reading-surface [data-preview-field="billNos"]').textContent.includes(count), `The compact count follows ${language}`)
    card = document.querySelector('[data-preview-card="billNos"]')
    assert.ok(card.textContent.includes(numberLabel), `The displayed column name follows ${language}`)
    assert.ok(card.textContent.includes(descriptionLabel), `The formal description heading follows ${language}`)
    assert.ok(card.textContent.includes(typeLabel), `Stable type options have ${language} labels`)
    assert.ok(card.textContent.includes(descriptions[11]), 'Language switching never translates or truncates formal English descriptions')
    assert.doesNotMatch(card.textContent, /bill-internal-record|"number"|"description"|\[\{/)
  }
  assert.equal(JSON.stringify(values), before)
  app.unmount()
  console.log('PASS: real DOCX Bill markers stay concise while selected values and candidates are readable, exact, safely escaped, trilingual and read-only; source selection and return-to-input are retained')

  const workTypes = { key: 'workTypes', kind: 'multiselect', label: labels('Work types', '工程类别', '工程類別'), options: [{ value: 'building', label: labels('Building works', '建筑工程', '建築工程') }, { value: 'foundation', label: labels('Foundation works', '基础工程', '基礎工程') }] }
  const mixedFields = [
    { key: 'contractTitle', kind: 'contract', label: labels('Contract number and title', '合约编号与名称', '合約編號與名稱') },
    { key: 'subcontractors', kind: 'multiselect', label: labels('Selected subcontract trades', '分包工种', '分包工種'), options: [{ value: 'electrical', label: labels('Electrical installation', '电气工程', '電氣工程') }, { value: 'fire', label: labels('Fire services installation', '消防工程') }] },
    { key: 'designResponsibilities', kind: 'list', label: labels('Design responsibilities', '设计责任', '設計責任'), columnFields: [{ key: 'component', kind: 'choice', label: labels('Component', '组成部分', '組成部分'), options: [{ value: 'foundation', label: labels('Foundation works', '基础工程', '基礎工程') }] }, { key: 'design', kind: 'boolean', label: labels('Design by contractor', '承建商设计', '承建商設計') }, { key: 'execution', kind: 'boolean', label: labels('Execution by contractor', '承建商施工') }, { key: 'scope', kind: 'text', label: labels('Scope', '范围', '範圍') }] },
    { key: 'sections', kind: 'list', label: labels('Sections', '分区', '分區'), columnFields: [{ key: 'id', kind: 'text', label: labels('Record ID', '记录标识', '記錄標識') }, { key: 'designation', kind: 'text', label: labels('Section designation', '分区编号', '分區編號') }, workTypes, { key: 'location', kind: 'text', label: labels('Location', '位置') }] },
    { key: 'oldValuableTrees', kind: 'list', label: labels('Old and valuable trees', '古树', '古樹'), columnFields: [{ key: 'serial', kind: 'text', label: labels('Tree serial number', '树木编号', '樹木編號') }] },
    { key: 'quantity', kind: 'number', label: labels('Quantity', '数量', '數量') },
    { key: 'explicitFalse', kind: 'boolean', label: labels('Explicit false answer', '明确否', '明確否') },
    { key: 'additionalSubmissions', kind: 'list', label: labels('Additional submissions', '额外文件', '額外文件'), columnFields: [{ key: 'text', kind: 'text', label: labels('Submission', '文件') }] }
  ]
  const mixedValues = vue.reactive({ contractTitle: { number: '20250101', title: 'Construction of Public Housing Development at Tung Chung Area 98', id: 'technical-contract-ID' }, subcontractors: ['electrical', 'fire'], designResponsibilities: [{ component: 'foundation', design: false, execution: true, scope: 'Exact scope remains English.', id: 'technical-design-ID' }], sections: [{ id: 'technical-section-ID', designation: 'Section A', workTypes: ['building', 'foundation'], location: 'Domestic Block and Podium' }], oldValuableTrees: null, quantity: 0, explicitFalse: false, additionalSubmissions: '[{"text":"incomplete' })
  const mixedSelected = vue.ref('contractTitle')
  const mixedLocale = vue.ref('en')
  const mixedBefore = JSON.stringify(mixedValues)
  const mixed = vue.createApp({ render: () => vue.h(component, { projectId: 'SYNTHETIC-STRUCTURED-VALUES', fileKey: 'NTT', sourceAvailable: false, locale: mixedLocale.value, fields: mixedFields, values: mixedValues, variables: [{ key: 'additionalSubmissions', source: 'SIMULATED malformed candidate evidence', candidates: [{ value: '[{"text":"incomplete', fileName: 'SIMULATED pending instruction.docx', sourceQuote: 'Additional submissions remain pending.' }] }], actions: [], selectedKey: mixedSelected.value, selectedActionId: '', fieldStates: { contractTitle: 'suggested', sections: 'inactive', additionalSubmissions: 'needs_review' }, dirtyKeys: [], disabled: false }) })
  mixed.mount('#app'); await settle()
  const selectedCard = () => document.querySelector(`[data-preview-card="${mixedSelected.value}"]`)
  const valueDisplay = () => selectedCard().querySelector(':scope > .draft-value-display')
  assert.match(valueDisplay().textContent, /Contract number/)
  assert.match(valueDisplay().textContent, /20250101/)
  assert.match(valueDisplay().textContent, /Contract title/)
  assert.match(valueDisplay().textContent, /Construction of Public Housing Development at Tung Chung Area 98/)
  assert.doesNotMatch(valueDisplay().textContent, /technical-contract-ID|"number"|"title"/)
  mixedSelected.value = 'subcontractors'; await settle()
  assert.deepEqual([...valueDisplay().querySelectorAll('li')].map(node => node.textContent), ['Electrical installation', 'Fire services installation'])
  mixedSelected.value = 'designResponsibilities'; await settle()
  assert.match(valueDisplay().textContent, /Component/)
  assert.match(valueDisplay().textContent, /Foundation works/)
  const definitions = [...valueDisplay().querySelectorAll('dd')].map(node => node.textContent.trim())
  assert.deepEqual(definitions, ['Foundation works', 'No', 'Yes', 'Exact scope remains English.'])
  assert.doesNotMatch(valueDisplay().textContent, /technical-design-ID|"component"|"execution"/)
  mixedSelected.value = 'sections'; await settle()
  assert.match(valueDisplay().textContent, /Section designation/)
  assert.match(valueDisplay().textContent, /Section A/)
  assert.match(valueDisplay().textContent, /Work types/)
  assert.match(valueDisplay().textContent, /Building works/)
  assert.match(valueDisplay().textContent, /Foundation works/)
  assert.match(valueDisplay().textContent, /Domestic Block and Podium/)
  assert.doesNotMatch(valueDisplay().textContent, /technical-section-ID|"workTypes"/)
  assert.equal(selectedCard().querySelector('[data-preview-input="sections"]').disabled, true, 'Inactive values remain readable without exposing an enabled edit action')
  mixedLocale.value = 'zh-Hant'; await settle()
  assert.match(valueDisplay().textContent, /分區編號/)
  assert.match(valueDisplay().textContent, /工程類別/)
  assert.match(valueDisplay().textContent, /建築工程/)
  assert.match(valueDisplay().textContent, /基礎工程/)
  assert.match(valueDisplay().textContent, /Domestic Block and Podium/)
  mixedLocale.value = 'en'; await settle()
  mixedSelected.value = 'oldValuableTrees'; await settle()
  assert.equal(valueDisplay().textContent.trim(), 'Unknown', 'Unanswered collection does not become an empty confirmed collection')
  mixedValues.oldValuableTrees = []; await settle()
  assert.equal(valueDisplay().textContent.trim(), 'No items', 'Explicit empty collection stays distinct from an unanswered collection')
  mixedValues.oldValuableTrees = null; await settle()
  mixedSelected.value = 'quantity'; await settle()
  assert.equal(valueDisplay().textContent.trim(), '0', 'Numeric zero is a supplied value')
  mixedSelected.value = 'explicitFalse'; await settle()
  assert.equal(valueDisplay().textContent.trim(), 'No', 'Boolean false is a supplied answer')
  mixedSelected.value = 'additionalSubmissions'; await settle()
  assert.match(valueDisplay().textContent, /Unable to display this value/)
  assert.ok(valueDisplay().querySelector('details'), 'Malformed structured values keep a labelled original-value disclosure')
  assert.match(valueDisplay().querySelector('summary').textContent, /Original value/)
  assert.equal(valueDisplay().querySelector('pre').textContent, '[{"text":"incomplete')
  assert.doesNotMatch(valueDisplay().textContent, /No items|Unknown/, 'Malformed values are never relabelled as empty or unknown')
  assert.match(selectedCard().textContent, /SIMULATED pending instruction\.docx/)
  assert.match(selectedCard().textContent, /Additional submissions remain pending\./)
  assert.equal(JSON.stringify(mixedValues), mixedBefore, 'Presentation preserves all current values, including unknown, false, zero and malformed text')
  mixed.unmount()
  console.log('PASS: contract, multiple-choice trades, design records and nested section work types use business labels; inactive/current values, explicit empty, unknown, zero, false and malformed originals remain distinct and read-only')

  const malformedValues = vue.reactive({ billNos: [{ number: '9', description: 'Electrical Works' }], subcontractors: [] })
  const malformedSelected = vue.ref('billNos')
  const malformed = vue.createApp({ render: () => vue.h(component, { projectId: 'SYNTHETIC-MALFORMED-MEMBERS', fileKey: 'NTT', sourceAvailable: false, locale: 'en', fields: [bills, mixedFields[1]], values: malformedValues, variables: [], actions: [], selectedKey: malformedSelected.value, selectedActionId: '', fieldStates: { billNos: 'needs_review', subcontractors: 'needs_review' }, dirtyKeys: [], disabled: false }) })
  malformed.mount('#app'); await settle()
  const malformedDisplay = () => document.querySelector(`[data-preview-card="${malformedSelected.value}"] > .draft-value-display`)
  assert.ok(malformedDisplay().querySelector('table'), 'A valid partial Bill record remains a readable table')
  assert.match(malformedDisplay().textContent, /Electrical Works/)
  assert.match(malformedDisplay().textContent, /Unknown/, 'A genuinely absent optional type is marked unknown rather than making the Bill row invalid')
  for (const shape of [['incomplete'], [false], [null], [[]]]) {
    malformedValues.billNos = shape; await settle()
    const display = malformedDisplay()
    assert.match(display.textContent, /Unable to display this value/, 'Malformed Bill members cannot silently appear as blank or unknown business cells')
    assert.equal(display.querySelector('table'), null, 'Malformed members are not shown as a successfully rendered Bill table')
    assert.match(display.querySelector('summary').textContent, /Original value/)
    assert.deepEqual(JSON.parse(display.querySelector('pre').textContent), shape, 'Malformed Bill members retain their entire original value for review')
    assert.deepEqual(JSON.parse(JSON.stringify(malformedValues.billNos)), shape, 'Displaying a malformed record never rewrites the input')
  }
  malformedSelected.value = 'subcontractors'; await settle()
  for (const shape of [[{}], [[]]]) {
    malformedValues.subcontractors = shape; await settle()
    const display = malformedDisplay()
    assert.match(display.textContent, /Unable to display this value/, 'A selected trade must be a scalar option value, not a record or nested array')
    assert.match(display.querySelector('summary').textContent, /Original value/)
    assert.deepEqual(JSON.parse(display.querySelector('pre').textContent), shape, 'Malformed multiple-choice members retain their original form')
  }
  malformedValues.subcontractors = []; await settle()
  assert.equal(malformedDisplay().textContent.trim(), 'No items', 'A legitimate empty selection remains explicit no-items, independently of malformed members')
  malformed.unmount()
  console.log('PASS: malformed record and multiple-choice members retain a warning and original value instead of appearing as empty cells or selected options; valid partial records and explicit empty remain usable')
})().catch(error => { console.error(error); process.exitCode = 1 })
