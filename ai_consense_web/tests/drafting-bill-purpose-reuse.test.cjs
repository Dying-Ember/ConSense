const assert = require('node:assert/strict')
const path = require('node:path')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const runtime = loadTypeScript(path.join(__dirname, '../src/drafting/bill-identities.ts'))
const { conflictingBillDuplicates, physicalBillRows } = runtime
const { validationIssue } = loadTypeScript(path.join(__dirname, '../src/drafting/state.ts'), { dependencies: { './field-kinds': './field-kinds.ts', './bill-identities': runtime } })
const { billDistribution } = loadTypeScript(path.join(__dirname, '../src/drafting/bill-distribution.ts'), { dependencies: { './bill-identities': runtime } })
const field = { key: 'billNos', kind: 'list', columnFields: [{ key: 'number', kind: 'text' }, { key: 'description', kind: 'text' }] }
const general = { id: 'old-stable-id', type: 'BQ', number: '7', description: 'AUTHORED identical safety description', purpose: 'bsSafety', placement: 'DiscB', placementText: '' }
const fire = { ...general, purpose: 'fireSafety', trade: 'Fire services' }
const original = JSON.stringify([general, fire])
assert.equal(conflictingBillDuplicates([general, fire]), false)
assert.equal(validationIssue(field, [general, fire]), null)
assert.equal(physicalBillRows([general, fire]).length, 1)
for (const pair of [[general, general], [general, { ...fire, description: `${fire.description} ` }], [general, { ...fire, purpose: 'general' }], [general, { ...fire, placement: 'DiscA' }], [general, { ...fire, placementText: 'different exact arrangement' }]]) {
  assert.equal(conflictingBillDuplicates(pair), true)
  assert.equal(validationIssue(field, pair), 'duplicate')
  assert.equal(physicalBillRows(pair).length, 2, 'invalid rows remain visible')
}
assert.equal(conflictingBillDuplicates([general, fire, { ...fire, id: 'third' }]), true)
assert.equal(physicalBillRows([general, fire, { ...fire, id: 'third' }]).length, 3)
assert.equal(validationIssue(field, [general, { ...fire, type: 'SOR' }]), null)
assert.equal(physicalBillRows([general, { ...fire, type: 'SOR' }]).length, 2)
const view = billDistribution({ electronicTendering: 'L10Pro', billNos: [general, fire] })
assert.equal(view.groups.find(group => group.placement === 'discB').rows.length, 1)
assert.equal(view.groups[0].rows[0].description, general.description)
assert.equal(JSON.stringify([general, fire]), original, 'source rows and explicit ids stay unchanged')
console.log('PASS: explicit distinct safety purposes share one physical Bill; conflicts preserve editable rows')
