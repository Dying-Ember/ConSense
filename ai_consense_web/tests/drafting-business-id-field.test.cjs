const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const { JSDOM } = require('jsdom')
const dom = new JSDOM('<!doctype html><html><body></body></html>')
for (const key of ['window', 'document', 'Document', 'ShadowRoot', 'Element', 'HTMLElement', 'SVGElement', 'Node']) globalThis[key] = key === 'window' ? dom.window : dom.window[key]
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const globals = { document, window, Element, HTMLElement, SVGElement, Node, crypto: require('node:crypto').webcrypto }
const loaded = loadVue(path.join(__dirname, '../src/components/DraftingInputField.vue'), { globals })
const state = loaded.loadLocal(path.join(__dirname, '../src/drafting/state.ts'))
const presentation = loaded.loadLocal(path.join(__dirname, '../src/drafting/value-presentation.ts'))
const label = en => ({ en, zhHans: en, zhHant: en })
const field = { key: 'review-list', kind: 'list', label: label('Reviewed items'), hidden: false, columnFields: [{ key: 'id', kind: 'text', hidden: false, label: label('Business identifier') }] }
const plain = value => JSON.parse(JSON.stringify(value))
const settle = async () => { for (let index = 0; index < 12; index++) { await Promise.resolve(); await vue.nextTick() } }
async function mount(component, props) {
  const element = document.createElement('div'); document.body.append(element)
  const app = vue.createApp({ render: () => vue.h(component, props) }); app.mount(element); await settle()
  return { element, dispose() { app.unmount(); element.remove() } }
}

test('reviewed id detail is editable separately from the _itemId row marker', async () => {
  const rows = vue.ref(null)
  const element = document.createElement('div'); document.body.append(element)
  const app = vue.createApp({ render: () => vue.h(loaded.default, { field, rowIdentityKey: '_itemId', value: rows.value, values: {}, locale: 'en', onUpdate: value => { rows.value = value } }) }); app.mount(element)
  try {
    const add = [...element.querySelectorAll('button')].find(button => button.textContent.trim() === '+ Add item')
    add.click(); await settle()
    assert.equal(typeof rows.value[0], 'object', 'A reviewed record must keep its business fields and its separate row marker')
    assert.equal(typeof rows.value[0]._itemId, 'string')
    assert.equal(rows.value[0].id, null)
    const input = element.querySelector('#input-review-list-1-id'); assert.ok(input, 'The required business id has its own editor')
    input.value = 'PROJECT-ITEM-42'; input.dispatchEvent(new window.Event('input', { bubbles: true })); await settle()
    assert.equal(rows.value[0].id, 'PROJECT-ITEM-42'); assert.ok(rows.value[0]._itemId)
  } finally { app.unmount(); element.remove() }
})

test('reviewed required id is validated instead of being exempted as row metadata', () => {
  assert.equal(state.validationIssue(field, [{ _itemId: 'ROW-PRIVATE', id: null }]), 'incomplete')
  assert.equal(state.validationIssue(field, [{ _itemId: 'ROW-PRIVATE', id: 'PROJECT-ITEM-42' }]), null)
  assert.equal(state.validationIssue(field, []), null)
})

test('reviewed id values remain visible in exact-value presentation', async () => {
  assert.deepEqual(plain(presentation.presentationColumns(field, [])), field.columnFields)
  const component = loaded.loadLocal(path.join(__dirname, '../src/components/DraftingValueDisplay.vue')).default
  const h = await mount(component, { field, value: [{ _itemId: 'ROW-PRIVATE', id: 'PROJECT-ITEM-42' }], locale: 'en' })
  try { assert.match(h.element.textContent, /Business identifier/); assert.match(h.element.textContent, /PROJECT-ITEM-42/); assert.doesNotMatch(h.element.textContent, /ROW-PRIVATE/) } finally { h.dispose() }
})

test('reviewed business graph exposes the id field and its value', async () => {
  const component = loaded.loadLocal(path.join(__dirname, '../src/components/DraftingBusinessGraph.vue')).default
  const values = { 'review-list': [{ _itemId: 'ROW-PRIVATE', id: 'PROJECT-ITEM-42' }] }
  const catalog = { ruleVersion: 'SYNTHETIC-RULE', graphRevisionId: 'SYNTHETIC-GRAPH', groups: [{ id: 'reviewed', label: label('Reviewed inputs'), fields: [field] }] }
  const plan = { ruleVersion: catalog.ruleVersion, graphRevisionId: catalog.graphRevisionId, inputValues: values, effectiveValues: values, derived: {}, actions: [], unresolved: [] }
  const h = await mount(component, { projectId: 'SYNTHETIC-ID', catalog, plan, values, variables: [], fieldStates: {}, dirtyKeys: [], locale: 'en' })
  try { h.element.querySelector('[data-graph-input="review-list"]').dispatchEvent(new window.MouseEvent('click', { bubbles: true })); await settle(); assert.match(h.element.querySelector('[data-graph-structure]')?.textContent ?? '', /Business identifier/); assert.match(h.element.querySelector('[data-graph-current-value]').textContent, /PROJECT-ITEM-42/); assert.doesNotMatch(h.element.textContent, /ROW-PRIVATE/) } finally { h.dispose() }
})

test('legacy id columns and explicitly hidden fields keep their existing treatment', async () => {
  for (const hidden of [undefined, true]) {
    const column = { ...field.columnFields[0] }; if (hidden === undefined) delete column.hidden; else column.hidden = hidden
    const legacy = { ...field, columnFields: [column] }
    assert.deepEqual(plain(presentation.presentationColumns(legacy, [])), [])
    assert.equal(state.validationIssue(legacy, [{ id: null }]), null)
  }
})
