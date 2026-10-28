const assert = require('node:assert/strict')

;(async () => {
  const Overview = (await import('../src/drafting/source-review/graph-overview.js')).default
  const Business = (await import('../src/drafting/source-review/graph-business-view.js')).default
  const sources = [{ id: '11', fileKey: 'SCC', format: 'docx', fileName: 'First.docx' }, { id: '12', fileKey: 'SCC', format: 'docx', fileName: 'Second.docx' }]
  const state = { variables: [
    { id: 'a', nameEn: 'Input', docIds: ['11'], status: 'confirmed', role: { kind: 'input' }, type: { kind: 'boolean' } },
    { id: 'b', nameEn: 'Affected target', docIds: ['12'], status: 'confirmed', role: { kind: 'derived' }, type: { kind: 'boolean' }, correction: { kind: 'rule', branches: [{ id: 'yes', mode: 'all', clauses: [{ inputId: 'a', operator: 'yes' }], resultValue: true }] }, editTargets: [{ id: 'target', docId: '12', action: 'delete', branchId: 'yes' }] }
  ] }
  assert.equal(Overview.project(state, { sources }).edges.find(edge => edge.kind === 'condition').incompatible, true)
  assert.equal(Business.build(state, { sources }).actions.find(action => action.ownerId === 'b').incompatible, true)
  state.variables[1].docIds = ['11']; state.variables[1].editTargets[0].docId = '11'
  assert.equal(Overview.project(state, { sources }).edges.find(edge => edge.kind === 'condition').incompatible, false)
  assert.equal(Business.build(state, { sources }).actions.find(action => action.ownerId === 'b').incompatible, false)
  console.log('PASS: separate SCC originals remain distinct even when both are Word files')
})().catch(error => { console.error(error); process.exitCode = 1 })
