const assert = require('node:assert/strict')
const path = require('node:path')
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { text, find, settle, renderer } = require('./helpers/render-controls.cjs')

const input = loadVue(path.join(__dirname, '../src/components/DraftingInputField.vue'), { globals: { crypto: require('node:crypto').webcrypto } }).default
const distribution = loadVue(path.join(__dirname, '../src/components/DraftingBillDistribution.vue')).default
const descriptions = ['Preliminaries', 'Preambles', 'Substructure', 'Superstructure for Podium', 'Superstructure for Domestic Block', 'Plumbing Works for Podium and Domestic Block', 'Drainage', 'External Works', 'Electrical Works', 'Fire Services and Water Pump Works', 'Lift Works', 'Site Safety; Environmental Management and Other Sundry Requirements (All Provisional)', 'Provisional Sums']
const column = (key, kind = 'text', extra = {}) => ({ key, kind, label: { en: key, zhHans: key, zhHant: key }, ...extra })
const field = { key: 'billNos', kind: 'list', label: { en: 'Bill numbers and descriptions', zhHans: 'Bill 编号及名称', zhHant: 'Bill 編號及名稱' }, columnFields: [column('id'), column('number'), column('description'), column('type', 'select', { optional: true, options: [{ value: 'BQ', label: { en: 'BQ' } }, { value: 'SOR', label: { en: 'SOR' } }] }), column('purpose', 'text', { optional: true }), column('placement', 'text', { optional: true })] }

;(async () => {
  const values = vue.reactive({ electronicTendering: 'L10Pro', billNos: descriptions.map((description, index) => ({ id: `source-bill-${index + 1}`, number: String(index + 1), description, type: index >= 8 && index <= 10 ? 'SOR' : null, purpose: null, placement: null })) })
  const original = JSON.stringify(values.billNos)
  const rendered = renderer()
  const app = rendered.createApp({ render: () => vue.h('main', [vue.h(input, { field, value: values.billNos, values, locale: 'en', onUpdate: value => { values.billNos = value } }), vue.h(distribution, { values, locale: 'en' })]) })
  app.mount(rendered.root); await settle()
  for (const [index, description] of descriptions.entries()) {
    const number = find(rendered.root, node => node.tag === 'input' && node.props.id === `input-billNos-${index + 1}-number`)
    const name = find(rendered.root, node => node.tag === 'textarea' && node.props.id === `input-billNos-${index + 1}-description`)
    const type = find(rendered.root, node => node.tag === 'select' && node.props.id === `input-billNos-${index + 1}-type`)
    assert.equal(number.props.value, String(index + 1))
    assert.equal(name.props.value, description)
    assert.equal(type.props.value, index >= 8 && index <= 10 ? 'SOR' : '')
    assert.equal(type.props.disabled, false)
  }
  assert.match(text(rendered.root), /Confirm BQ or SOR type; no placement has been inferred/)
  assert.match(text(rendered.root), /Adopt the actual SOR issue/)
  assert.equal(JSON.stringify(values.billNos), original)
  await find(rendered.root, node => node.tag === 'button' && text(node).trim() === '+ Add item').props.onClick(); await settle()
  assert.equal(values.billNos.length, 14)
  assert.equal(values.billNos[13].type, null)
  assert.equal(values.billNos[13].purpose, null)
  assert.equal(values.billNos[13].placement, null)
  const firstType = find(rendered.root, node => node.tag === 'select' && node.props.id === 'input-billNos-1-type')
  firstType.props.onChange({ target: { value: 'BQ' } }); await settle()
  assert.equal(values.billNos[0].type, 'BQ')
  assert.equal(values.billNos[0].description, 'Preliminaries')
  firstType.props.onChange({ target: { value: '' } }); await settle()
  assert.equal(values.billNos[0].type, null)
  assert.equal(values.billNos[0].number, '1')
  assert.equal(values.billNos[0].description, 'Preliminaries')
  app.unmount()
  console.log('PASS: actual Bill editor retains all 13 formal identities, shows only sourced SOR 9–11, keeps unknown metadata editable, and adds rows without inventing BQ or issue placements')
})().catch(error => { console.error(error); process.exitCode = 1 })
