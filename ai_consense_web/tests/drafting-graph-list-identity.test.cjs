const assert = require('node:assert/strict')
const path = require('node:path')
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle } = require('./helpers/render-controls.cjs')
const loaded = loadVue(path.join(__dirname, '../src/components/DraftingInputField.vue'), { globals: { crypto: require('node:crypto').webcrypto } })
const field = { key: 'review-list', kind: 'list', label: { en: 'Reviewed items' }, columnFields: [{ key: 'item-name', kind: 'text', label: { en: 'Item name' } }] }
async function exercise(rowIdentityKey) {
  const rows = vue.ref(null), host = renderer()
  const app = host.createApp({ setup: () => () => vue.h(loaded.default, { field, rowIdentityKey, value: rows.value, values: {}, locale: 'en', onUpdate: value => { rows.value = value } }) }); app.mount(host.root)
  const add = () => find(host.root, node => node.tag === 'button' && text(node).trim() === '+ Add item')
  await add().props.onClick(); await settle()
  const firstMarker = rows.value[0][rowIdentityKey ?? 'id']
  assert.equal(typeof firstMarker, 'string'); assert.ok(firstMarker)
  assert.equal(rows.value[0][rowIdentityKey === '_itemId' ? 'id' : '_itemId'], undefined, 'Only the selected persistence identity is added')
  const input = find(host.root, node => node.props?.id === 'input-review-list-1-item-name')
  input.props.onInput({ target: { value: 'First exact item' } }); await settle()
  await add().props.onClick(); await settle()
  assert.notEqual(rows.value[1][rowIdentityKey ?? 'id'], firstMarker)
  rows.value = [rows.value[1], rows.value[0]]; await settle()
  assert.equal(rows.value[1]['item-name'], 'First exact item')
  assert.equal(rows.value[1][rowIdentityKey ?? 'id'], firstMarker)
  if (rowIdentityKey === '_itemId') assert.strictEqual(find(host.root, node => node.props?.id === 'input-review-list-2-item-name'), input, 'The graph item marker moves the same control with its row')
  app.unmount()
}
;(async () => {
  await exercise('_itemId'); await exercise(undefined)
  console.log('PASS: graph list creation, editing and reorder retain the backend item marker without adding an unknown id detail; legacy list identities remain unchanged')
})().catch(error => { console.error(error); process.exitCode = 1 })
