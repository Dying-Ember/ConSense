const assert = require('node:assert/strict')
const path = require('node:path')
const vue = require('vue')
const { renderToString } = require('vue/server-renderer')
const { loadVue } = require('./helpers/load-vue.cjs')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const catalog = require('../../ai_consense_service/src/main/resources/drafting/input-catalog.json')
const { applicability, dirtyPatch } = loadTypeScript(path.join(__dirname, '../src/drafting/state.ts'), { dependencies: { './field-kinds': './field-kinds.ts', './bill-identities': './bill-identities.ts' } })
const input = loadVue(path.join(__dirname, '../src/components/DraftingInputField.vue')).default
const fields = catalog.groups.flatMap(group => group.fields)
const trade = fields.find(field => field.key === 'subcontractors')

;(async () => {
  const selected = ['Fire services', 'Lift']
  for (const arrangement of ['NSC', 'BSSSC', null]) {
    const values = { subcontractArrangement: arrangement, subcontractors: selected }
    assert.equal(applicability(trade.condition, values), 'yes', 'BS trade scope remains applicable for both designation methods and unknown designation')
    const html = await renderToString(vue.createSSRApp(input, { field: trade, value: selected, values, locale: 'en', disabled: false }))
    for (const option of ['Fire services', 'Lift']) assert.ok(html.includes(option))
    assert.doesNotMatch(html, /disabled/)
    assert.deepEqual(JSON.parse(JSON.stringify(dirtyPatch([trade], values, { subcontractors: '[]' }))), { subcontractors: JSON.stringify(selected) })
  }
  assert.match(trade.label.en, /BS/)
  assert.doesNotMatch(trade.label.en, /^BSSSC/)
  console.log('PASS: fire and lift trade scope stays visible, editable and saveable under NSC, BSSSC and unknown designation without changing the selected scope')
})().catch(error => { console.error(error); process.exitCode = 1 })
