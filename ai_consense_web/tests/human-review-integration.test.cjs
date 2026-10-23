const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const { readReviewSnapshot, evaluateBondExample } = loadTypeScript(path.join(__dirname, '../src/drafting/human-review-integration.ts'))
const clone = value => JSON.parse(JSON.stringify(value))
const variable = (id, kind, other = {}) => ({ id, status: 'changed', type: { kind }, ...other })
const clause = (inputId, operator, value = '') => ({ inputId, operator, value })
const snapshot = {
  pack: { kind: 'consense-review-v1', sourceKey: 'source-identity', state: { variables: [
    variable('review-5', 'boolean'), variable('review-6', 'boolean'), variable('review-7', 'number'),
    variable('review-8', 'choice', { role: { kind: 'derived' }, options: [{ value: 'g1', labelZh: 'G1', labelEn: 'G1' }, { value: 'g1a', labelZh: 'G1a', labelEn: 'G1a' }], correction: { branches: [
      { mode: 'all', clauses: [clause('review-5', 'yes'), clause('review-6', 'yes'), clause('review-7', 'ge', '39')], resultValue: 'g1a' },
      { mode: 'any', clauses: [clause('review-5', 'no'), clause('review-6', 'no'), clause('review-7', 'lt', '39')], resultValue: 'g1' }
    ] } })
  ], sourceReviews: {}, events: [], nextId: 9, blank: true, guidanceMode: 'minimum' } },
  summary: { records: 4, active: 4, unconfirmed: 4 },
  templates: ['ntt', 'sct', 'scc-word', 'scc-pdf'].map(id => ({ id, originalSha256: 'a'.repeat(64), visualSha256: 'b'.repeat(64), layoutSha256: 'c'.repeat(64) }))
}
const answers = { combined: 'yes', foundations: 'yes', months: '39' }
assert.ok(readReviewSnapshot(snapshot))
for (const corrupt of [
  state => { state.summary.unconfirmed = 0 },
  state => { state.pack.state.events = {} },
  state => { delete state.pack.state.guidanceMode },
  state => { state.templates[0].originalSha256 = '' },
  state => { state.templates[1].id = 'ntt' }
]) { const bad = clone(snapshot); corrupt(bad); assert.equal(readReviewSnapshot(bad), null) }
assert.equal(evaluateBondExample(snapshot, answers).resultEn, 'G1a')
assert.equal(evaluateBondExample(snapshot, { ...answers, months: '38.99' }).resultEn, 'G1')
assert.equal(evaluateBondExample(snapshot, { ...answers, months: 38 }).resultEn, 'G1')
assert.equal(evaluateBondExample(snapshot, { ...answers, months: 39 }).resultEn, 'G1a')
assert.equal(evaluateBondExample(snapshot, { ...answers, months: '' }).status, 'pending')
assert.equal(evaluateBondExample(snapshot, { combined: '', foundations: '', months: '' }).status, 'pending')
assert.equal(evaluateBondExample(snapshot, { combined: 'no', foundations: '', months: '' }).resultEn, 'G1')
assert.equal(evaluateBondExample(snapshot, { ...answers, months: '-1' }).status, 'unavailable')
assert.equal(evaluateBondExample(snapshot, { ...answers, months: 'invalid' }).status, 'unavailable')
const unsupported = clone(snapshot)
unsupported.pack.state.variables[3].correction.branches[0].clauses[0].operator = 'includes'
assert.equal(evaluateBondExample(unsupported, answers).status, 'unavailable')
const rebinding = clone(snapshot)
rebinding.pack.state.variables[3].correction.needsRebind = true
assert.equal(evaluateBondExample(rebinding, answers).status, 'unavailable')
for (const role of ['input', 'fixed', 'verification']) {
  const manual = clone(snapshot); manual.pack.state.variables[3].role.kind = role
  assert.equal(evaluateBondExample(manual, answers).status, 'unavailable')
}
const removed = clone(snapshot)
removed.pack.state.variables[0].status = 'excluded'
assert.equal(evaluateBondExample(removed, answers).status, 'unavailable')
const overlap = clone(snapshot)
overlap.pack.state.variables[3].correction.branches.push(clone(overlap.pack.state.variables[3].correction.branches[0]))
assert.equal(evaluateBondExample(overlap, answers).status, 'conflict')

if (process.argv[2]) {
  const saved = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'))
  const active = saved.graph.variables.filter(item => item.status !== 'excluded')
  const actual = { pack: { kind: 'consense-review-v1', sourceKey: saved.sourceKey, state: saved.graph }, summary: { records: saved.graph.variables.length, active: active.length, unconfirmed: active.filter(item => item.status !== 'confirmed').length }, templates: saved.templates }
  const before = JSON.stringify(actual)
  assert.ok(readReviewSnapshot(actual), 'Actual saved graph survives snapshot validation')
  assert.match(evaluateBondExample(actual, answers).resultEn, /G1a/)
  assert.match(evaluateBondExample(actual, { ...answers, months: '38' }).resultEn, /G1 standard/)
  assert.equal(evaluateBondExample(actual, { combined: '', foundations: '', months: '' }).status, 'pending')
  const modified = clone(actual)
  const owner = modified.pack.state.variables.find(item => item.id === 'review-8')
  owner.correction.branches[0].clauses = [clause('review-5', 'yes')]
  assert.equal(evaluateBondExample(modified, { combined: 'yes', foundations: '', months: '' }).status, 'pending', 'A matching branch is not unique while another edited case remains unknown')
  assert.equal(JSON.stringify(actual), before, 'Example evaluation never alters the saved graph or its review statuses')
  console.log(`PASS actual graph: ${actual.summary.records} records, ${actual.summary.active} active, ${actual.summary.unconfirmed} unconfirmed; only the bond example evaluated`)
}
console.log('PASS review snapshot validation and bond example: unknowns, 39-month boundary, negative branch, unsupported edits, rebinding and conflicts')
