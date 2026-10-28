const assert = require('node:assert/strict')
const path = require('node:path')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const { createReviewReducer } = loadTypeScript(path.join(__dirname, '../src/drafting/source-review/reducer.js'), { globals: { structuredClone }, dependencies: { './value-dependencies.js': './value-dependencies.js' } })
const copy = value => JSON.parse(JSON.stringify(value))
const reduce = createReviewReducer({ sources: [] }).reduce
const original = { kind: 'rule', branches: [{ id: 'a', mode: 'all', scopeId: 'scope', scopeMode: 'each', clauses: [{ inputId: 'former', operator: 'yes' }], resultValue: true, effectEn: 'Original condition' }], otherwise: 'needs_review' }
const placeholder = { kind: 'rule', needsRebind: true, branches: [], inputIds: [], supersededCorrection: original }
const field = { id: 'owner', nameZh: '变量', nameEn: 'Variable', role: { kind: 'input' }, type: { kind: 'boolean' }, status: 'pending', docIds: [], sourceUnitIds: [], relatedInputIds: [], editTargets: [], relations: [], evidence: [], targets: [], correction: placeholder }
const state = value => ({ variables: [value], events: [], sourceReviews: {}, nextId: 1 })
for (const replacement of [null, { kind: 'rule', needsRebind: true, branches: [], inputIds: [], note: 'Human replacement' }]) {
  const before = state(copy(field)), next = reduce(before, { type: 'RULE', id: 'owner', at: 'TEST-time', correction: replacement })
  assert.notEqual(next, before)
  assert.deepEqual(copy(next.variables[0].correction), replacement)
  assert.deepEqual(copy(next.variables[0].editorialReferenceHistory[0].correction), original)
  assert.equal(next.variables[0].editorialReferenceHistory[0].reason, 'legacy_superseded_correction')
  assert.deepEqual(before.variables[0].correction, placeholder, 'Original state is untouched')
  const loaded = JSON.parse(JSON.stringify(next))
  assert.deepEqual(loaded.variables[0].editorialReferenceHistory[0].correction, original, 'Save and reload preserves legacy history')
}
const unrelated = { variableId: 'different', correction: { kind: 'rule', branches: [{ id: 'old' }] }, targets: [], relatedInputIds: [] }
const withHistory = state({ ...copy(field), editorialReferenceHistory: [unrelated] })
const appended = reduce(withHistory, { type: 'RULE', id: 'owner', correction: null })
assert.deepEqual(copy(appended.variables[0].editorialReferenceHistory), [unrelated, { variableId: null, correction: original, targets: [], relatedInputIds: [], reason: 'legacy_superseded_correction' }])
const withDuplicate = state({ ...copy(field), editorialReferenceHistory: [{ variableId: 'former', correction: original, targets: [], relatedInputIds: [] }] })
assert.equal(reduce(withDuplicate, { type: 'RULE', id: 'owner', correction: null }).variables[0].editorialReferenceHistory.length, 1)
const retained = reduce(state(copy(field)), { type: 'RULE', id: 'owner', correction: { ...copy(placeholder), humanEdit: true } })
assert.equal(retained.variables[0].editorialReferenceHistory, undefined, 'No migration needed while the new rule still retains the exact history')
const malformed = state({ ...copy(field), editorialReferenceHistory: { old: 'must be preserved' } })
assert.equal(reduce(malformed, { type: 'RULE', id: 'owner', correction: null }), malformed, 'Do not discard malformed existing history')
