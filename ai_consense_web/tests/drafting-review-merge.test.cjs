const assert = require('node:assert/strict')
const path = require('node:path')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const { createReviewReducer } = loadTypeScript(path.join(__dirname, '../src/drafting/source-review/reducer.js'), { globals: { structuredClone }, dependencies: { './value-dependencies.js': './value-dependencies.js' } })
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

// Step 082: identical excavation-permit booleans arrived with two explicit spellings.
const binaryOptions = values => values.map((value, index) => ({ value, labelZh: index ? '否' : '是', labelEn: index ? 'No' : 'Yes' }))
const permit = base()
for (const item of permit.variables.slice(0, 2)) {
  item.type.kind = 'boolean'
  item.nameZh = '工程是否涉及需要获取挖掘许可的工作'
  item.sourceAnchors = [{ docId: '41', text: '[Guidance Note: Do not adopt this SCC Clause in contracts that do not involve works requiring excavation permits to be obtained.]' }]
}
permit.variables[0].options = binaryOptions(['yes', 'no'])
permit.variables[1].options = binaryOptions(['true', 'false'])
permit.variables[2].correction = { kind: 'rule', inputIds: ['old'], branches: [{ id: 'yes-case', mode: 'all', clauses: [{ inputId: 'old', operator: 'yes', value: true }] }, { id: 'exact-case', mode: 'all', clauses: [{ inputId: 'old', operator: 'eq', value: 'yes' }] }] }
const permitBefore = structuredClone(permit)
const permitResult = batchReducer.mergeMany(permit, ['old', 'keep'], 'keep', '2026-10-26T05:00:00Z', 'Sources compared; same affirmative permit requirement')
assert.equal(permitResult.problem, null, 'Step 082: the real batch-merge entry must accept proven same-polarity yes/no and true/false boolean representations')
assert.deepEqual(permit, permitBefore, 'Preview and commit cannot mutate the captured prior revision')
assert.deepEqual(permitResult.state.variables[1].options, permitBefore.variables[1].options, 'Keep the human-selected definition options; do not rewrite project values')
assert.deepEqual(JSON.parse(JSON.stringify(permitResult.state.variables[2].correction)), { kind: 'rule', inputIds: ['keep'], branches: [{ id: 'yes-case', mode: 'all', clauses: [{ inputId: 'keep', operator: 'yes', value: true }] }, { id: 'exact-case', mode: 'all', clauses: [{ inputId: 'keep', operator: 'eq', value: 'yes' }] }] }, 'Redirect condition identities while preserving every predicate, polarity and original comparison value')
assert.equal(permitResult.state.variables[2].status, 'help', 'Existing item-match review protection survives the equivalent merge')
assert.equal(permitResult.state.variables[2].editTargets[0].needsRebind, true)
assert.deepEqual(Array.from(permitResult.state.requirements[0].nodeIds), ['keep'])
assert.equal(batchReducer.mergeMany(permit, ['old', 'keep'], 'old').problem, null, 'Either explicit representation may be retained')
for (const options of [[], null, undefined]) {
  const implicit = structuredClone(permit); implicit.variables[0].options = options
  const beforeImplicit = structuredClone(implicit)
  const result = batchReducer.mergeMany(implicit, ['old', 'keep'], 'keep')
  assert.equal(result.problem, null, 'Default boolean Yes/No options must merge with explicit standard true/false options')
  assert.deepEqual(implicit, beforeImplicit, 'Default-option comparison must preserve the captured revision')
  assert.deepEqual(result.state.variables[1].options, beforeImplicit.variables[1].options, 'Keep the selected explicit options')
  const keepImplicit = batchReducer.mergeMany(implicit, ['old', 'keep'], 'old')
  assert.equal(keepImplicit.problem, null, 'The default boolean definition may also be the retained identity')
  assert.deepEqual(keepImplicit.state.variables[0].options, options, 'An explicit merge must not rewrite default options into stored values')
  assert.equal(result.state.variables[2].correction.branches[0].clauses[0].inputId, 'keep', 'Retarget dependent conditions through the existing transaction')
}
for (const options of [binaryOptions([true, false]), binaryOptions(['yes', 'no']).reverse()]) {
  const same = structuredClone(permit); same.variables[0].options = options
  assert.equal(batchReducer.mergeMany(same, ['old', 'keep'], 'keep').problem, null)
}
for (const options of [
  [{ value: 'yes', labelZh: '否', labelEn: 'No' }, { value: 'no', labelZh: '是', labelEn: 'Yes' }],
  [{ value: 'yes', labelZh: '是', labelEn: 'No' }, { value: 'no', labelZh: '否', labelEn: 'Yes' }],
  binaryOptions(['include', 'exclude']),
  [{ value: 'yes', labelZh: '适用', labelEn: 'Applicable' }, { value: 'no', labelZh: '不适用', labelEn: 'Not applicable' }],
  [binaryOptions(['yes', 'no'])[0]],
  [...binaryOptions(['yes', 'no']), { value: 'unknown', labelZh: '未知', labelEn: 'Unknown' }],
  binaryOptions(['yes', 'yes']),
  [{ ...binaryOptions(['yes', 'no'])[0], disabled: true }, binaryOptions(['yes', 'no'])[1]],
  [{ ...binaryOptions(['yes', 'no'])[0], labelZh: 1 }, binaryOptions(['yes', 'no'])[1]],
  [{ ...binaryOptions(['yes', 'no'])[0], labelEn: null }, binaryOptions(['yes', 'no'])[1]],
  { length: 0 }
]) {
  const ambiguous = structuredClone(permit); ambiguous.variables[0].options = options
  assert.equal(batchReducer.mergeMany(ambiguous, ['old', 'keep'], 'keep').problem.code, 'MERGE_OPTIONS_MISMATCH', 'Reverse, custom, incomplete and ambiguous options remain rejected')
  ambiguous.variables[1].options = []
  assert.equal(batchReducer.mergeMany(ambiguous, ['old', 'keep'], 'keep').problem.code, 'MERGE_OPTIONS_MISMATCH', 'A default counterpart cannot make custom or partial options standard')
}
for (const kind of ['choice', 'multiselect', 'text']) {
  const nonBoolean = structuredClone(permit); nonBoolean.variables[0].type.kind = nonBoolean.variables[1].type.kind = kind
  assert.equal(batchReducer.mergeMany(nonBoolean, ['old', 'keep'], 'keep').problem.code, 'MERGE_OPTIONS_MISMATCH', 'Non-boolean options keep exact comparison')
  nonBoolean.variables[0].options = []
  assert.equal(batchReducer.mergeMany(nonBoolean, ['old', 'keep'], 'keep').problem.code, 'MERGE_OPTIONS_MISMATCH', 'The implicit Yes/No domain applies only to boolean types')
}
for (const [patch, code] of [
  [{ type: { kind: 'text' } }, 'MERGE_TYPE_MISMATCH'],
  [{ type: { kind: 'boolean', unit: 'days' } }, 'MERGE_UNIT_MISMATCH'],
  [{ role: { kind: 'verification' } }, 'MERGE_ROLE_MISMATCH'],
  [{ definitionKind: 'source_seed' }, 'MERGE_ROLE_MISMATCH'],
  [{ parentId: 'another-row' }, 'MERGE_PARENT_MISMATCH'],
  [{ optional: true }, 'MERGE_OPTIONAL_MISMATCH']
]) {
  const protectedState = structuredClone(permit); Object.assign(protectedState.variables[1], patch)
  assert.equal(batchReducer.mergeMany(protectedState, ['old', 'keep'], 'keep').problem.code, code, 'Boolean spelling equivalence cannot relax another merge guard')
}
const booleanRules = structuredClone(permit)
for (const item of booleanRules.variables.slice(0, 2)) item.correction = { kind: 'rule', branches: [{ clauses: [{ inputId: 'dependent', operator: 'known' }] }] }
assert.equal(batchReducer.mergeMany(booleanRules, ['old', 'keep'], 'keep').problem.code, 'MERGE_RULE_CONFLICT')
const booleanEditions = structuredClone(permit); booleanEditions.variables[0].docIds = ['42']; booleanEditions.variables[1].docIds = ['43']
assert.equal(editionReducer.mergeMany(booleanEditions, ['old', 'keep'], 'keep').problem.code, 'MERGE_SOURCE_EDITION_CONFLICT')
const booleanCycle = structuredClone(permit)
for (const item of booleanCycle.variables.slice(0, 2)) item.role.kind = 'derived'
booleanCycle.variables[0].correction = { kind: 'rule', branches: [{ clauses: [{ inputId: 'keep', operator: 'yes' }] }] }
assert.equal(batchReducer.mergeMany(booleanCycle, ['old', 'keep'], 'keep').problem.code, 'MERGE_DEPENDENCY_CYCLE')
const lateBooleanConflict = structuredClone(permit)
lateBooleanConflict.variables.push({ ...node('third', 'boolean'), options: [{ value: 'yes', labelZh: '否', labelEn: 'No' }, { value: 'no', labelZh: '是', labelEn: 'Yes' }] })
const lateBooleanBefore = structuredClone(lateBooleanConflict)
assert.equal(batchReducer.mergeMany(lateBooleanConflict, ['old', 'keep', 'third'], 'keep').state, lateBooleanConflict, 'A later ambiguous boolean aborts the whole batch, including the earlier equivalent pair')
assert.deepEqual(lateBooleanConflict, lateBooleanBefore)
for (const position of [0, 1]) {
  const editorial = base(), candidate = editorial.variables[position]
  candidate.editorialSuggestion = { kind: 'editorial', sourceQuote: 'Delete the guidance note.', reason: 'Editing instruction', confirmed: false }
  const prior = structuredClone(editorial)
  assert.equal(batchReducer.mergeProblem(editorial, 'old', 'keep').code, 'MERGE_EDITORIAL_REVIEW_REQUIRED')
  assert.equal(reduce(editorial, action), editorial, 'An unresolved editorial classification cannot disappear into the surviving business variable')
  assert.equal(batchReducer.mergeMany(editorial, ['old', 'keep'], 'keep').state, editorial)
  assert.deepEqual(editorial, prior, 'A rejected merge preserves the candidate, source evidence and classification')
  candidate.editorialReview = { kind: 'business_value', confirmed: true }
  const reviewed = reduce(editorial, action)
  assert.notEqual(reviewed, editorial, 'Explicit human business classification enables the existing compatible merge')
  assert.equal(reviewed.variables[0].editorialSuggestion?.kind ?? reviewed.variables[1].editorialSuggestion?.kind, 'editorial', 'The original classification suggestion remains in the saved graph history')
}
console.log('PASS: existing single-merge guards and source history; explicit three-question batch; atomic rollback, accumulated-rule conflicts and invalid selections')
