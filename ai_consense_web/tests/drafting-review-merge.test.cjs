const assert = require('node:assert/strict')
const path = require('node:path')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const { createReviewReducer } = loadTypeScript(path.join(__dirname, '../src/drafting/source-review/reducer.js'), { globals: { structuredClone } })
const node = (id, kind = 'text') => ({ id, nameZh: id, nameEn: id, parentId: null, type: { kind }, role: { kind: 'input' }, docIds: ['41'], status: 'confirmed', options: [], sourceUnitIds: [], relatedInputIds: [], sourceAnchors: [], editTargets: [], evidence: [], targets: [], relations: [] })
const base = () => ({ variables: [node('old'), node('keep'), { ...node('dependent'), editTargets: [{ id: 'target', valueSourceId: 'old', itemKey: 'old' }] }], requirements: [{ id: 'req', nodeIds: ['old', 'keep'], sourceQuote: 'Original guidance' }], sourceReviews: {}, events: [] })
const reduce = createReviewReducer({ sources: [{ id: '41', fileKey: 'NTT', format: 'docx' }] }).reduce
const action = { type: 'MERGE', id: 'old', targetId: 'keep', reason: 'Explicit user decision' }
const linked = base(); linked.variables[2].correction = { kind: 'rule', branches: [{ id: 'case', clauses: [{ inputId: 'old', operator: 'known' }] }] }
const renamed = reduce(linked, { type: 'EDIT', id: 'old', values: { nameEn: 'Clearer label' } })
assert.equal(renamed.variables[2].correction.needsRebind, undefined, 'Omitted type in a partial edit is not an answer-type change')
const retyped = reduce(linked, { type: 'EDIT', id: 'old', values: { type: { kind: 'boolean' } } })
assert.equal(retyped.variables[2].correction.needsRebind, true, 'A real answer-type change still invalidates dependent rules')
const before = base(), original = structuredClone(before), merged = reduce(before, action)
assert.deepEqual(before, original, 'The original revision stays immutable')
assert.equal(merged.variables[0].status, 'excluded')
assert.equal(merged.variables[1].status, 'changed')
assert.equal(merged.variables[1].manual, true)
assert.deepEqual(Array.from(merged.requirements[0].nodeIds), ['keep'])
assert.equal(merged.requirements[0].sourceQuote, 'Original guidance')
assert.equal(merged.variables[2].editTargets[0].valueSourceId, 'keep')
assert.equal(merged.variables[2].editTargets[0].itemKey, 'keep')
assert.equal(merged.variables[2].editTargets[0].needsRebind, true, 'Matching a list row must be reviewed after its identity question is merged')
assert.equal(merged.variables[2].status, 'help')
for (const patch of [{ type: { kind: 'boolean' } }, { role: { kind: 'derived' } }, { status: 'excluded' }, { optional: true }]) {
  const state = base(); Object.assign(state.variables[1], patch)
  assert.equal(reduce(state, action), state, 'Incompatible answer semantics cannot be merged')
}
const requiredTarget = base(); requiredTarget.variables[0].optional = true
assert.equal(reduce(requiredTarget, action), requiredTarget, 'Merging must not silently change optional input into a required answer')
const defaultRequired = base(); defaultRequired.variables[0].optional = false
assert.equal(reduce(defaultRequired, action).variables[0].status, 'excluded', 'An absent optional flag has the existing required default')
const quarantined = base()
for (const item of quarantined.variables.slice(0, 2)) item.correction = { kind: 'rule', branches: [], inputIds: [], needsRebind: true }
const combined = reduce(quarantined, action)
assert.equal(combined.variables[0].status, 'excluded', 'The same empty unresolved rule does not prevent explicitly merging a proven duplicate question')
assert.deepEqual(JSON.parse(JSON.stringify(combined.variables[1].correction)), quarantined.variables[1].correction, 'The unresolved rule guard must survive merging')
assert.equal(combined.variables[1].status, 'changed')
const actualRules = base()
for (const item of actualRules.variables.slice(0, 2)) item.correction = { kind: 'rule', branches: [{ id: 'case', clauses: [{ inputId: 'dependent', operator: 'known' }] }], inputIds: ['dependent'], needsRebind: true }
assert.equal(reduce(actualRules, action), actualRules, 'Nonempty rules still require separate reconciliation even when serialized alike')
const extraRuleData = structuredClone(quarantined); extraRuleData.variables[0].correction.scopeId = 'unknown-scope'
assert.equal(reduce(extraRuleData, action), extraRuleData, 'An empty-looking rule with additional scope data is not a plain unresolved placeholder')
const groups = base(); groups.variables[0].type.kind = groups.variables[1].type.kind = 'record'; groups.variables[2].parentId = 'old'; groups.variables[2].editTargets = []
const moved = reduce(groups, action)
assert.equal(moved.variables[2].parentId, 'keep')
assert.equal(moved.variables[2].status, 'pending')
const batchReducer = createReviewReducer({ sources: [{ id: '41', fileKey: 'NTT', format: 'docx' }] })
const batch = base(); batch.variables.push({ ...node('third'), sourceAnchors: [{ docId: '41', text: 'Third source passage' }] })
const batchBefore = structuredClone(batch)
const batchResult = batchReducer.mergeMany(batch, ['old', 'keep', 'third'], 'keep', '2026-10-23T08:00:00Z', 'Explicit batch decision')
assert.equal(batchResult.problem, null)
assert.deepEqual(batch, batchBefore, 'Batch review preserves the previous revision')
assert.equal(batchResult.state.variables.find(item => item.id === 'old').status, 'excluded')
assert.equal(batchResult.state.variables.find(item => item.id === 'third').status, 'excluded')
assert.equal(batchResult.state.variables.find(item => item.id === 'keep').status, 'changed')
assert.equal(batchResult.state.variables.find(item => item.id === 'keep').nameEn, 'keep')
assert.deepEqual(JSON.parse(JSON.stringify(batchResult.state.variables.find(item => item.id === 'keep').sourceAnchors)), [{ docId: '41', text: 'Third source passage' }])
assert.equal(batchResult.state.events.length, 2)
assert.deepEqual(JSON.parse(JSON.stringify(batchReducer.reduce(batch, { type: 'MERGE_MANY', ids: ['old', 'keep', 'third'], targetId: 'keep', at: '2026-10-23T08:00:00Z', reason: 'Explicit batch decision' }))), JSON.parse(JSON.stringify(batchResult.state)))
const thirdConflict = structuredClone(batch)
thirdConflict.variables.find(item => item.id === 'third').optional = true
const thirdBefore = structuredClone(thirdConflict)
const rejectedThird = batchReducer.mergeMany(thirdConflict, ['old', 'keep', 'third'], 'keep')
assert.equal(rejectedThird.state, thirdConflict, 'A later conflicting member returns the exact original state, not an earlier partial merge')
assert.equal(rejectedThird.problem.code, 'MERGE_OPTIONAL_MISMATCH')
assert.equal(rejectedThird.problem.id, 'third')
assert.deepEqual(thirdConflict, thirdBefore, 'Rejected batches preserve graph, references and history')
assert.equal(batchReducer.reduce(thirdConflict, { type: 'MERGE_MANY', ids: ['old', 'keep', 'third'], targetId: 'keep' }), thirdConflict)

const sequentialRules = structuredClone(batch)
for (const id of ['old', 'third']) sequentialRules.variables.find(item => item.id === id).correction = { kind: 'rule', branches: [{ id: id + '-case', clauses: [{ inputId: 'dependent', operator: 'known' }] }], inputIds: ['dependent'] }
assert.equal(batchReducer.mergeProblem(sequentialRules, 'old', 'keep'), null)
assert.equal(batchReducer.mergeProblem(sequentialRules, 'third', 'keep'), null)
const sequentialBefore = structuredClone(sequentialRules)
for (const ids of [['old', 'keep', 'third'], ['third', 'old', 'keep']]) {
  const result = batchReducer.mergeMany(sequentialRules, ids, 'keep')
  assert.equal(result.state, sequentialRules, 'Each simulated merge must recheck the accumulated retained rule')
  assert.equal(result.problem.code, 'MERGE_RULE_CONFLICT')
}
assert.deepEqual(sequentialRules, sequentialBefore)

for (const [ids, targetId, code] of [
  [[], 'keep', 'MERGE_SELECTION_REQUIRED'],
  [['keep'], 'keep', 'MERGE_SELECTION_REQUIRED'],
  [null, 'keep', 'MERGE_SELECTION_REQUIRED'],
  [['old', 'keep', 'old'], 'keep', 'MERGE_DUPLICATE_ID'],
  [['old', 'keep'], 'third', 'MERGE_TARGET_REQUIRED'],
  [['old', 'keep', 'absent'], 'keep', 'MERGE_NODE_MISSING'],
  [['old', 'keep', null], 'keep', 'MERGE_NODE_MISSING']
]) {
  const result = batchReducer.mergeMany(batch, ids, targetId)
  assert.equal(result.state, batch)
  assert.equal(result.problem.code, code)
}
const guardCases = [
  [{ type: { kind: 'boolean' } }, 'MERGE_TYPE_MISMATCH'],
  [{ role: { kind: 'verification' }, definitionKind: 'source_seed', manual: false }, 'MERGE_ROLE_MISMATCH'],
  [{ status: 'excluded' }, 'MERGE_EXCLUDED'],
  [{ parentId: 'different-group' }, 'MERGE_PARENT_MISMATCH'],
  [{ optional: true }, 'MERGE_OPTIONAL_MISMATCH'],
  [{ options: [{ value: 'different' }] }, 'MERGE_OPTIONS_MISMATCH']
]
for (const [patch, code] of guardCases) {
  const state = base(); Object.assign(state.variables[1], patch)
  const saved = structuredClone(state)
  assert.equal(batchReducer.mergeProblem(state, 'old', 'keep').code, code)
  assert.equal(batchReducer.reduce(state, action), state)
  assert.deepEqual(state, saved)
}
assert.equal(batchReducer.mergeProblem(batch, 'absent', 'keep').code, 'MERGE_NODE_MISSING')
assert.equal(batchReducer.mergeProblem(batch, 'keep', 'keep').code, 'MERGE_SAME_NODE')
const fixed = base()
for (const item of fixed.variables.slice(0, 2)) { item.role.kind = 'fixed'; item.fixedValue = item.id }
assert.equal(batchReducer.mergeProblem(fixed, 'old', 'keep').code, 'MERGE_FIXED_VALUE_MISMATCH')
assert.equal(batchReducer.reduce(fixed, action), fixed)
assert.equal(batchReducer.mergeProblem(actualRules, 'old', 'keep').code, 'MERGE_RULE_CONFLICT')
assert.equal(batchReducer.mergeProblem(extraRuleData, 'old', 'keep').code, 'MERGE_RULE_CONFLICT')
assert.equal(batchReducer.mergeProblem(quarantined, 'old', 'keep'), null, 'Empty unresolved rule placeholders keep their existing explicit-merge exception')

const editionReducer = createReviewReducer({ sources: [{ id: '41', fileKey: 'NTT', format: 'docx' }, { id: '42', fileKey: 'SCC', format: 'docx' }, { id: '43', fileKey: 'SCC', format: 'pdf' }] })
const editions = base(); editions.variables[0].docIds = ['42']; editions.variables[1].docIds = ['43']
assert.equal(editionReducer.mergeProblem(editions, 'old', 'keep').code, 'MERGE_SOURCE_EDITION_CONFLICT')
assert.equal(editionReducer.reduce(editions, action), editions)
const inheritedScope = base()
inheritedScope.variables[1].docIds = ['42']
inheritedScope.variables[2].docIds = ['43']
inheritedScope.variables[2].relatedInputIds = ['old']
inheritedScope.variables[2].editTargets = []
assert.equal(editionReducer.isolatedSources(inheritedScope), true)
assert.equal(editionReducer.mergeProblem(inheritedScope, 'old', 'keep').code, 'MERGE_SOURCE_SCOPE_CONFLICT')
assert.equal(editionReducer.reduce(inheritedScope, action), inheritedScope)
console.log('PASS: existing single-merge guards and source history; explicit three-question batch; atomic rollback, accumulated-rule conflicts and invalid selections')
