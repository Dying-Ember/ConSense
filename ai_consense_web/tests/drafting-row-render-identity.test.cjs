const assert = require('node:assert/strict')
const path = require('node:path')
const { webcrypto } = require('node:crypto')
const { JSDOM } = require('jsdom')
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost.invalid/' })
globalThis.window = dom.window
globalThis.document = dom.window.document
for (const name of ['Element', 'Node', 'HTMLElement', 'SVGElement', 'Document', 'ShadowRoot', 'Event', 'HTMLInputElement', 'HTMLTextAreaElement', 'HTMLSelectElement']) globalThis[name] = dom.window[name]
Object.defineProperty(globalThis, 'navigator', { value: dom.window.navigator, configurable: true })
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const root = path.resolve(__dirname, '..')
const input = loadVue(path.join(root, 'src/components/DraftingInputField.vue'), { globals: { crypto: webcrypto } }).default
const distributionLoader = loadVue(path.join(root, 'src/components/DraftingBillDistribution.vue'))
const distribution = distributionLoader.default
const { billDistribution } = distributionLoader.loadLocal(path.join(root, 'src/drafting/bill-distribution.ts'))
const field = { key: 'billNos', kind: 'list', label: { en: 'Bills' }, columnFields: [
  { key: 'id', kind: 'text', label: { en: 'id' } },
  { key: 'number', kind: 'text', label: { en: 'Number' } },
  { key: 'description', kind: 'text', label: { en: 'Description' } },
  { key: 'type', kind: 'select', label: { en: 'Type' }, options: [{ value: 'BQ', label: { en: 'BQ' } }, { value: 'SOR', label: { en: 'SOR' } }] },
] }
const copy = value => JSON.parse(JSON.stringify(value))
const originals = [
  { id: 'shared-id', number: '1', description: 'BQ first', type: 'BQ', placement: 'DiscB', extra: { preserved: 'BQ metadata' } },
  { id: 'shared-id', number: '1', description: 'SOR first', type: 'SOR', placement: 'DiscB', extra: { preserved: 'SOR metadata' } },
  { id: 'separate-id', number: '2', description: 'BQ second', type: 'BQ', placement: 'DiscB' },
]
const settle = async () => { await vue.nextTick(); await vue.nextTick() }
const testResults = []

async function check(name, initial, exercise) {
  const container = document.createElement('div'); document.body.append(container)
  const values = vue.reactive({ billNos: copy(initial), electronicTendering: 'L10Pro', twoEnvelopeTendering: true })
  const warnings = []
  const app = vue.createApp({ render: () => vue.h('main', [
    vue.h(input, { field, value: values.billNos, values, locale: 'en', onUpdate: value => { values.billNos = value } }),
    vue.h(distribution, { values, locale: 'en' }),
  ]) })
  app.config.warnHandler = message => warnings.push(message)
  const rows = () => [...container.querySelectorAll('.draft-field[data-field="billNos"] > .collection-editor > .table-scroll > table > tbody > tr')]
  const description = i => rows()[i].querySelector('textarea[id$="-description"]')
  const snapshots = []
  function assertView(label) {
    const actual = rows().map(tr => ({ number: tr.querySelector('input[id$="-number"]').value, description: tr.querySelector('textarea[id$="-description"]').value, type: tr.querySelector('select[id$="-type"]').value }))
    const expected = copy(values.billNos.map(r => ({ number: String(r.number ?? ''), description: String(r.description ?? ''), type: String(r.type ?? '') })))
    const distributionActual = [...container.querySelectorAll('.distribution-group')].map(group => ({ placement: group.dataset.placement, rows: [...group.querySelectorAll('tbody > tr')].map(tr => ({ id: tr.dataset.billId, identity: tr.children[0].textContent.trim(), description: tr.children[1].textContent.trim() })) }))
    const distributionExpected = copy(billDistribution(values).groups.map(group => ({ placement: group.placement, rows: group.rows.map(r => ({ id: r.id, identity: `${r.type} ${r.number}`.trim(), description: r.description })) })))
    snapshots.push({ label, modelRows: copy(values.billNos), editorCount: actual.length, distributionCount: distributionActual.reduce((n, group) => n + group.rows.length, 0) })
    assert.deepEqual(actual, expected, `${name}: ${label}: editor reflects each current row exactly once and in order`)
    assert.deepEqual(distributionActual, distributionExpected, `${name}: ${label}: every derived group reflects each current row exactly once`)
    assert.equal(warnings.filter(message => /duplicate keys/i.test(message)).length, 0, `${name}: no duplicate Vue render keys`)
  }
  async function edit(i, text) {
    const before = copy(values.billNos)
    const element = description(i)
    element.focus(); element.value = text; element.dispatchEvent(new Event('input', { bubbles: true })); await settle()
    assert.deepEqual(copy(values.billNos), before.map((row, index) => index === i ? { ...row, description: text } : row), `${name}: editing the visible row updates only its current logical index and preserves ids/metadata`)
    assert.strictEqual(description(i), element, `${name}: editing does not remount the current row control`)
  }
  try {
    app.mount(container); await settle(); assertView('initial')
    await exercise({ values, rows, description, assertView, edit, settle,
      async reorder(order) { values.billNos = order.map(index => values.billNos[index]); await settle() },
      async replace(next) { values.billNos = copy(next); await settle() },
      async remove(index) { rows()[index].querySelector('.row-action button').click(); await settle() },
      async add() { [...container.querySelectorAll('.draft-field[data-field="billNos"] > .collection-editor > .list-controls button')].find(button => button.textContent.trim().startsWith('+')).click(); await settle() },
    })
    testResults.push({ name, status: 'PASS', snapshots })
  } catch (error) {
    testResults.push({ name, status: 'FAIL', snapshots, error: error.stack })
  } finally {
    try { app.unmount() } finally { container.remove() }
  }
}

;(async () => {
  await check('duplicate explicit ids survive three-row reorder, actual edit, remove and add', originals, async h => {
    await h.reorder([2, 0, 1]); h.assertView('three-row rotation')
    await h.edit(1, 'BQ first edited after rotation'); h.assertView('edit current second row')
    assert.equal(h.values.billNos[1].id, 'shared-id'); assert.equal(h.values.billNos[2].id, 'shared-id')
    await h.remove(1); h.assertView('remove current second row')
    await h.add(); h.assertView('add unknown new row')
  })
  await check('duplicate explicit ids survive source replacement, filtering and edits', originals, async h => {
    await h.replace([originals[2], originals[1], originals[0], { ...originals[2], id: 'last-id', number: '3', description: 'New third' }]); h.assertView('source replacement and append')
    await h.edit(2, 'BQ replacement edited'); h.assertView('edit replacement third row')
    await h.replace([originals[2], originals[0]]); h.assertView('filtered replacement')
    await h.edit(1, 'Filtered BQ edited'); h.assertView('edit filtered second row')
  })
  const unique = originals.map((r, i) => ({ ...r, id: `unique-${i}` }))
  await check('unique explicit ids retain their actual DOM controls through edits and reorder', unique, async h => {
    const first = h.description(0)
    await h.edit(0, 'Unique BQ edited'); h.assertView('edit stable unique id')
    assert.strictEqual(h.description(0), first)
    await h.reorder([2, 0, 1]); h.assertView('unique-id rotation')
    assert.strictEqual(h.description(1), first, 'stable unique id moves the existing control with the row')
    await h.edit(1, 'Unique rotated BQ edited'); h.assertView('edit rotated unique id')
    await h.remove(2); h.assertView('remove unique third row')
    await h.add(); h.assertView('append unknown unique row')
  })
  const numeric = originals.map((row, i) => ({ ...row, id: i < 2 ? (i === 0 ? 1 : '1') : 'duplicate:"1":0' }))
  await check('numeric and string id collision stays presentation-only', numeric, async h => {
    await h.reorder([2, 0, 1]); h.assertView('typed-id rotation')
    await h.edit(2, 'String id SOR edited'); h.assertView('edit typed-id third row')
    assert.deepEqual(copy(h.values.billNos.map(row => row.id)), ['duplicate:"1":0', 1, '1'], 'ids retain original values and types')
  })
  const missing = originals.map((row, i) => i === 1 ? { ...row, id: null } : { ...row, id: i === 0 ? 'row-1' : 'other-id' })
  await check('derived fallback id collision does not duplicate distribution rows', missing, async h => {
    await h.reorder([2, 0, 1]); h.assertView('fallback-id rotation')
    await h.edit(2, 'Missing id SOR edited'); h.assertView('edit missing-id third row')
    assert.deepEqual(copy(h.values.billNos.map(row => row.id)), ['other-id', 'row-1', null], 'missing raw id remains missing')
  })
  console.log(JSON.stringify({ vueVersion: vue.version, jsdomVersion: require('jsdom/package.json').version, tests: testResults }, null, 2))
  const failed = testResults.filter(result => result.status === 'FAIL')
  if (failed.length) { console.error(`FAIL: ${failed.length}/${testResults.length} row presentation identity scenarios`); process.exitCode = 1 }
  else console.log(`PASS: ${testResults.length} actual Vue/DOM row presentation identity scenarios; source ids and metadata preserved`)
})().catch(error => { console.error(error); process.exitCode = 1 })
