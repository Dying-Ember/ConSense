const assert = require('node:assert/strict')
const path = require('node:path')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const { createReviewReducer } = loadTypeScript(path.join(__dirname, '../src/drafting/source-review/reducer.js'), { globals: { structuredClone }, dependencies: { './value-dependencies.js': './value-dependencies.js' } })
const reduce = createReviewReducer({ sources: [] }).reduce
const relation = (fromId, toId) => ({ fromId, toId, kind: 'same_fact', fromQuote: 'Original A', toQuote: 'Original B', reason: 'Original model reason', status: 'pending', executable: false })
const state = { variables: [{ id: 'a', status: 'excluded', replacedBy: ['b'] }, { id: 'b', status: 'confirmed' }], relationProposals: [relation('a', 'b'), relation('b', 'a')], events: [] }
const before = structuredClone(state)
for (const [decision, status] of [['implemented', 'confirmed'], ['rejected', 'rejected'], ['not_applicable', 'excluded']]) {
  const result = reduce(state, { type: 'REVIEW_RELATIONS', indices: [0, 1], decision, reason: ' Both source ranges reviewed ' })
  assert.notEqual(result, state)
  assert.deepEqual(state, before)
  assert.deepEqual(result.variables, before.variables, 'A review does not execute an edge, merge variables or change ranges')
  for (let index = 0; index < 2; index++) {
    const saved = result.relationProposals[index]
    assert.equal(saved.status, status)
    assert.equal(saved.executable, false)
    assert.deepEqual(JSON.parse(JSON.stringify(saved.review)), { decision, reason: 'Both source ranges reviewed', basis: null }, 'Only the backend can stamp the current basis')
    for (const key of ['fromId', 'toId', 'kind', 'fromQuote', 'toQuote', 'reason']) assert.equal(saved[key], before.relationProposals[index][key])
  }
}
for (const indices of [[], [0, 0], [0, 2], [-1], [1.5], ['0'], null]) assert.equal(reduce(state, { type: 'REVIEW_RELATIONS', indices, decision: 'rejected', reason: 'Reason' }), state, 'Invalid groups must not partially change the graph')
for (const values of [{ reason: ' ' }, { decision: 'automatic' }, { decision: '__proto__' }]) assert.equal(reduce(state, { type: 'REVIEW_RELATIONS', indices: [0], decision: 'rejected', reason: 'Reason', ...values }), state)
console.log('PASS: relation reviews preserve original proposals and graph, support explicit groups, and reject invalid decisions atomically')
