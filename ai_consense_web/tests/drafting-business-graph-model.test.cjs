/* Public readonly catalogue/plan graph seam. Fixtures are explicit business examples. */
const assert = require('node:assert/strict')
const path = require('node:path')
const { loadVue } = require('./helpers/load-vue.cjs')
const { buildBusinessGraph } = loadVue(path.join(__dirname, '../src/drafting/business-graph.ts'))
const plain = value => JSON.parse(JSON.stringify(value))
const field = (key, kind = 'text', more = {}) => ({ key, kind, label: { en: key, zhHans: key, zhHant: key }, ...more })
const label = text => ({ en: text, zhHans: text, zhHant: text })
const catalog = () => ({ ruleVersion: 'test-business-v1', groups: [
  { id: 'contractType', label: label('Contract type'), fields: [field('foundationIncluded', 'boolean'), field('periodAtLeast39Months', 'boolean'), field('contractPeriodMonths', 'number', { optional: true })] },
  { id: 'bills', label: label('Bills'), fields: [field('billNos', 'list', { columnFields: [field('number'), field('description')], affects: [{ document: 'SCT', clause: 'SCT1', paragraphs: 'P55–387' }] })] }
] })
const plan = (inputValues, actions) => ({ ruleVersion: 'test-business-v1', inputValues, effectiveValues: inputValues, derived: {}, actions, unresolved: [] })
let checks = 0
function check(name, task) { task(); checks++; console.log(`PASS: ${name}`) }

check('the same business inputs remain inspectable inside their groups and structural child fields', () => {
  const schema = catalog(), values = { foundationIncluded: null, billNos: [] }, before = JSON.stringify({ schema, values })
  const graph = buildBusinessGraph({ catalog: schema, plan: null, values })
  assert.deepEqual(plain(graph.groups.map(group => [group.id, group.inputKeys])), [['contractType', ['foundationIncluded', 'periodAtLeast39Months', 'contractPeriodMonths']], ['bills', ['billNos']]])
  assert.equal(graph.inputs.length, 4)
  const bills = graph.inputs.find(input => input.key === 'billNos')
  assert.deepEqual(plain(bills.subfields.map(child => [child.id, child.parentKey, child.key])), [['subfield:billNos.number', 'billNos', 'number'], ['subfield:billNos.description', 'billNos', 'description']])
  assert.deepEqual(plain(bills.value), [])
  assert.equal(graph.inputs.find(input => input.key === 'foundationIncluded').value, null)
  assert.equal(graph.planStatus, 'unavailable')
  assert.equal(JSON.stringify({ schema, values }), before)
})

check('actual shared action identities connect every input to its precise file and clause without claiming an applied edit', () => {
  const values = { foundationIncluded: true, periodAtLeast39Months: false, contractPeriodMonths: '36', billNos: [] }
  const bond = { id: 'NTT-10-BOND-REFERENCE', document: 'NTT', clause: 'NTT 10', paragraphs: 'P484; guidance P486', inputKeys: ['foundationIncluded', 'periodAtLeast39Months', 'contractPeriodMonths'], action: 'amend', value: 'G1', detail: 'Select G1a only when combined foundation AND at least 39 months are true; an explicit No selects G1.' }
  const pricing = { id: 'sct-shared-bill-list', document: 'SCT', clause: 'SCT1', paragraphs: 'P55–387', inputKeys: ['billNos'], action: 'amend', value: [] }
  const sourceOnly = { id: 'NTT-GUIDANCE-CLEANUP', document: 'NTT', clause: 'Editing guidance', inputKeys: [], action: 'pending' }
  const current = plan(values, [bond, pricing, sourceOnly]), before = JSON.stringify(current)
  const graph = buildBusinessGraph({ catalog: catalog(), plan: current, values })
  assert.equal(graph.planStatus, 'current')
  assert.deepEqual(plain(graph.actions.map(action => action.id)), ['NTT-10-BOND-REFERENCE', 'sct-shared-bill-list', 'NTT-GUIDANCE-CLEANUP'])
  assert.equal(graph.actions.find(action => action.id === bond.id).result.detail, bond.detail)
  assert.deepEqual(plain(graph.edges.filter(edge => edge.kind === 'dependency').map(edge => [edge.from, edge.to])), [['input:foundationIncluded', bond.id], ['input:periodAtLeast39Months', bond.id], ['input:contractPeriodMonths', bond.id], ['input:billNos', pricing.id]])
  assert.deepEqual(plain(graph.inputs.find(input => input.key === 'foundationIncluded').actionIds), [bond.id])
  assert.deepEqual(plain(graph.files.find(file => file.key === 'NTT').actionIds), [bond.id, sourceOnly.id])
  assert.equal(graph.edges.some(edge => edge.kind === 'destination' && edge.from === pricing.id && edge.to === 'file:SCT'), true)
  assert.equal(Object.hasOwn(graph.actions[0], 'applied'), false)
  assert.equal(JSON.stringify(current), before)
})

check('stale plan values are withheld while typed transport equivalents can still identify the current plan', () => {
  const schema = catalog()
  schema.systemFields = [field('targetOverrides', 'list', { hidden: true })]
  const inputValues = { foundationIncluded: true, periodAtLeast39Months: false, contractPeriodMonths: 36, billNos: [{ number: '3', description: 'Exact building works\nSchedule', type: 'BQ' }], targetOverrides: [{ actionId: 'bond', adoptedText: 'Exact text', sourceMapping: 'Recorded mapping' }] }
  const values = { foundationIncluded: 'true', periodAtLeast39Months: 'false', contractPeriodMonths: '36', billNos: [{ type: 'BQ', description: 'Exact building works\nSchedule', number: '3' }], targetOverrides: [{ sourceMapping: 'Recorded mapping', adoptedText: 'Exact text', actionId: 'bond' }] }
  const action = { id: 'bond', document: 'NTT', clause: 'NTT10', inputKeys: ['foundationIncluded', 'periodAtLeast39Months', 'targetOverrides'], action: 'amend', value: 'G1' }
  const current = plan(inputValues, [action])
  assert.equal(buildBusinessGraph({ catalog: schema, plan: current, values }).planStatus, 'current')
  for (const changed of [
    { ...values, foundationIncluded: 'false' },
    { ...values, billNos: [{ number: '3', description: 'Exact building works Schedule', type: 'BQ' }] },
    { ...values, targetOverrides: [{ actionId: 'bond', adoptedText: 'Changed text', sourceMapping: 'Recorded mapping' }] }
  ]) {
    const graph = buildBusinessGraph({ catalog: schema, plan: current, values: changed })
    assert.equal(graph.planStatus, 'stale')
    assert.deepEqual(plain(graph.actions), [])
    assert.equal(graph.edges.some(edge => edge.kind === 'dependency' || edge.kind === 'destination'), false)
  }
  assert.equal(buildBusinessGraph({ catalog: schema, plan: { ...current, ruleVersion: 'other-version' }, values }).planStatus, 'stale')
  const empty = plan({ billNos: [] }, [])
  assert.equal(buildBusinessGraph({ catalog: catalog(), plan: empty, values: { billNos: null } }).planStatus, 'stale')
  assert.equal(buildBusinessGraph({ catalog: catalog(), plan: plan({}, []), values: { billNos: null, foundationIncluded: null } }).planStatus, 'current')
})

check('parent membership, all prerequisites and catalogue-only targets remain different inspectable relationships', () => {
  const schema = catalog()
  const all = { all: [{ field: 'domesticBlocks', operator: 'eq', value: true }, { field: 'volumetricPrecastComponents', operator: 'eq', value: true }] }
  schema.groups.push({ id: 'domestic', label: label('Domestic'), fields: [field('domesticBlocks', 'boolean'), field('volumetricPrecastComponents', 'boolean'), field('pseSubmissions', 'list', { condition: all, columnFields: [field('text')], affects: [{ document: 'SCT', clause: 'SCT6', paragraphs: 'P905–907' }] })] })
  for (const [values, expected] of [[{ domesticBlocks: null, volumetricPrecastComponents: null }, 'unknown'], [{ domesticBlocks: false, volumetricPrecastComponents: null }, 'no'], [{ domesticBlocks: true, volumetricPrecastComponents: null }, 'unknown'], [{ domesticBlocks: true, volumetricPrecastComponents: true }, 'yes']]) {
    const graph = buildBusinessGraph({ catalog: schema, plan: null, values }), input = graph.inputs.find(item => item.key === 'pseSubmissions')
    assert.equal(input.applicability, expected)
    assert.deepEqual(plain(input.condition), all)
    assert.deepEqual(plain(input.prerequisiteKeys), ['domesticBlocks', 'volumetricPrecastComponents'])
    assert.deepEqual(plain(graph.edges.filter(edge => edge.kind === 'prerequisite' && edge.to === input.id).map(edge => [edge.from, edge.conjunction])), [['input:domesticBlocks', 'all'], ['input:volumetricPrecastComponents', 'all']])
    assert.equal(graph.edges.some(edge => edge.kind === 'membership' && edge.from === 'group:domestic' && edge.to === input.id), true)
    assert.equal(graph.edges.some(edge => edge.kind === 'structure' && edge.from === input.id && edge.to === 'subfield:pseSubmissions.text'), true)
    assert.equal(graph.edges.some(edge => edge.kind === 'potential' && edge.from === input.id && edge.to === 'file:SCT'), true)
    assert.equal(graph.edges.some(edge => edge.kind === 'destination'), false)
    assert.deepEqual(plain(graph.groups.find(group => group.id === 'domestic').fileKeys), ['SCT'])
  }
})

check('the graph retains a visible unadopted value while its dependent applicability stays unknown', () => {
  const schema=catalog()
  schema.groups[0].fields.push(field('dependent','text',{condition:{all:[{field:'foundationIncluded',operator:'eq',value:true}]}}))
  const graph=buildBusinessGraph({catalog:schema,plan:null,values:{foundationIncluded:false},conditionValues:{foundationIncluded:null}})
  assert.equal(graph.inputs.find(input=>input.key==='foundationIncluded').value,false)
  assert.equal(graph.inputs.find(input=>input.key==='dependent').applicability,'unknown')
})

console.log(`${checks} business graph model checks passed`)
