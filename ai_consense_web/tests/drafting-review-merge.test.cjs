const assert = require('node:assert/strict')
const path = require('node:path')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const { createReviewReducer } = loadTypeScript(path.join(__dirname, '../src/drafting/source-review/reducer.js'), { globals: { structuredClone }, dependencies: { './value-dependencies.js': './value-dependencies.js' } })
const { valueDependencies } = loadTypeScript(path.join(__dirname, '../src/drafting/source-review/value-dependencies.js'))
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
const reviewedCatalog = { status: 'matched', confirmed: true, baselineKey: 'electronicTendering', baselineVersion: 'test', reason: 'Previously reviewed BQ issue-format definition' }
const reviewedMerge = base(), donor = reviewedMerge.variables[0], primary = reviewedMerge.variables[1]
donor.docIds = ['44']; donor.catalogAlignment = { status: 'unreviewed', confirmed: false }; donor.modelLocalId = 'n1'
primary.catalogAlignment = structuredClone(reviewedCatalog); primary.currentDefinitionReference = { baselineKey: 'electronicTendering', sourceReferences: [{ sourceId: '41' }] }
for (const [item, docId] of [[donor, '44'], [primary, '41']]) {
  item.type = { kind: 'choice' }; item.options = [{ value: 'L10Pro', labelZh: '电子', labelEn: 'Electronic' }, { value: 'Hardcopy', labelZh: '纸质', labelEn: 'Hardcopy' }]
  item.definitionReview = { confirmed: true, basis: 'old-' + item.id }
  item.sourceAnchors = [1, 2, 3].map(index => ({ docId, text: item.id + ' source ' + index }))
}
const mergeWithSct = createReviewReducer({ sources: [{ id: '41', fileKey: 'NTT', format: 'docx' }, { id: '44', fileKey: 'SCT', format: 'docx' }] })
const reviewedBefore = structuredClone(reviewedMerge), retained = mergeWithSct.mergeMany(reviewedMerge, ['old', 'keep'], 'keep').state
assert.deepEqual(reviewedMerge, reviewedBefore, 'The source revision and original review receipts stay immutable')
assert.deepEqual(retained.variables[1].catalogAlignment, reviewedCatalog, 'Explicit same-fact merging retains the chosen primary fixed identity')
assert.deepEqual(retained.variables[1].type, primary.type); assert.deepEqual(retained.variables[1].options, primary.options, 'The retained BQ answer domain is unchanged')
assert.equal(retained.variables[1].definitionReview, undefined, 'Added sources invalidate the old definition confirmation and its basis')
assert.equal(retained.variables[1].sourceAnchors.length, 6)
assert.deepEqual(Array.from(retained.variables[1].docIds), ['41', '44'])
assert.deepEqual(retained.variables[1].currentDefinitionReference, primary.currentDefinitionReference)
assert.equal(retained.variables[0].modelLocalId, 'n1'); assert.deepEqual(retained.variables[0].definitionReview, donor.definitionReview)
assert.equal(retained.variables[0].status, 'excluded'); assert.equal(retained.variables[1].manual, true)
assert.equal(retained.variables[2].editTargets[0].needsRebind, true, 'Catalog retention does not relax dependent range review')
for (const mainAlignment of [undefined, { status: 'unreviewed', confirmed: false }, { ...reviewedCatalog, confirmed: false }]) {
  const state = base(); state.variables[0].catalogAlignment = structuredClone(reviewedCatalog)
  if (mainAlignment) state.variables[1].catalogAlignment = structuredClone(mainAlignment)
  state.variables[1].definitionReview = { confirmed: true, basis: 'stale' }
  const result = batchReducer.mergeMany(state, ['old', 'keep'], 'keep')
  assert.equal(result.problem, null); assert.equal(result.state.variables[1].catalogAlignment.confirmed, false, 'An unreviewed primary never inherits the donor confirmation')
  assert.equal(result.state.variables[1].definitionReview, undefined)
}
for (const values of [{ nameEn: 'Changed subject' }, { questionEn: 'A different independently answerable fact?' }, { questionZh: '不同业务事实？' }, { definitionScope: 'Different contractual scope' }, { unit: 'different-unit' }]) {
  const state = base(); state.variables[1].catalogAlignment = structuredClone(reviewedCatalog); state.variables[0].catalogAlignment = structuredClone(reviewedCatalog)
  const edited = reduce(state, { type: 'EDIT', id: 'keep', values })
  assert.equal(edited.variables[1].catalogAlignment.confirmed, false, 'A changed primary core must first lose its old catalog confirmation')
  const result = batchReducer.mergeMany(edited, ['old', 'keep'], 'keep')
  assert.equal(result.problem, null); assert.equal(result.state.variables[1].catalogAlignment.confirmed, false, 'Merging a reviewed donor cannot restore a changed primary definition')
}
for (const kind of ['record', 'list']) {
  const state = base(); state.variables[0].type.kind = state.variables[1].type.kind = kind; state.variables[1].catalogAlignment = structuredClone(reviewedCatalog)
  state.variables[2].parentId = 'old'; state.variables[2].editTargets = []
  const result = batchReducer.mergeMany(state, ['old', 'keep'], 'keep')
  assert.equal(result.problem, null); assert.equal(result.state.variables[2].parentId, 'keep')
  assert.equal(result.state.variables[1].catalogAlignment.confirmed, false, 'Merged detail membership requires baseline review; scalar identity retention must not cover record/list shapes')
}
const changedCondition = base(); changedCondition.variables[1].catalogAlignment = structuredClone(reviewedCatalog)
changedCondition.variables[0].correction = { kind: 'rule', inputIds: ['dependent'], branches: [{ id: 'condition', mode: 'all', clauses: [{ inputId: 'dependent', operator: 'known' }] }] }
const conditionMerge = batchReducer.mergeMany(changedCondition, ['old', 'keep'], 'keep')
assert.equal(conditionMerge.problem, null); assert.equal(conditionMerge.state.variables[1].catalogAlignment.confirmed, false, 'A copied condition changes the retained definition prerequisites and still needs alignment review')
const applicableBase = base()
for (const item of applicableBase.variables.slice(0, 2)) item.catalogAlignment = structuredClone(reviewedCatalog)
applicableBase.variables.push({ ...node('prerequisite', 'boolean'), catalogAlignment: { status: 'matched', confirmed: true, baselineKey: 'prerequisite', baselineVersion: 'test' } })
const applicability = { kind: 'fixed_catalog', ruleVersion: 'test', targetBaselineKey: 'electronicTendering', bindingFingerprint: 'current-source-basis', mode: 'all', confirmed: true, reason: 'Compared the current template scope', clauses: [{ baselineKey: 'prerequisite', inputId: 'prerequisite', operator: 'eq', value: true }] }
const setApplicability = (state, id, rule = applicability) => {
  const result = reduce(state, { type: 'SET_FIXED_APPLICABILITY', id, applicability: rule })
  assert.notEqual(result, state, 'Applicability fixtures must pass the real reviewer action guards')
  assert.deepEqual(result.variables.find(item => item.id === id).applicability, rule)
  return result
}
const donorApplicability = setApplicability(applicableBase, 'old'), primaryApplicability = setApplicability(applicableBase, 'keep')
const conflictingApplicability = setApplicability(donorApplicability, 'keep', { ...applicability, clauses: [{ ...applicability.clauses[0], value: false }] })
for (const state of [donorApplicability, primaryApplicability, conflictingApplicability]) {
  const snapshot = structuredClone(state)
  assert.equal(batchReducer.mergeProblem(state, 'old', 'keep').code, 'MERGE_APPLICABILITY_CONFLICT')
  assert.equal(reduce(state, action), state, 'A single merge cannot drop or extend either variable applicability')
  const result = batchReducer.mergeMany(state, ['old', 'keep'], 'keep')
  assert.equal(result.problem.code, 'MERGE_APPLICABILITY_CONFLICT'); assert.equal(result.state, state)
  assert.deepEqual(structuredClone(state), snapshot, 'Rejected applicability merges preserve both variables, conditions and confirmations')
}
const equivalentApplicability = Object.fromEntries(Object.entries(applicability).reverse())
// Synthetic equal fingerprints cover structural comparison only; real source binding remains a server check.
equivalentApplicability.clauses = applicability.clauses.map(clause => Object.fromEntries(Object.entries(clause).reverse()))
const identicalApplicability = setApplicability(donorApplicability, 'keep', equivalentApplicability)
identicalApplicability.variables[0].definitionReview = { confirmed: true, basis: 'donor-history' }
identicalApplicability.variables[1].definitionReview = { confirmed: true, basis: 'primary-history' }
const sameApplicability = batchReducer.mergeMany(identicalApplicability, ['old', 'keep'], 'keep')
assert.equal(sameApplicability.problem, null, 'Identical applicability permits the existing same-fact merge independent of object key order')
assert.equal(JSON.stringify(sameApplicability.state.variables[1].applicability), JSON.stringify(identicalApplicability.variables[1].applicability), 'The retained primary keeps its original applicability and serialization')
assert.equal(sameApplicability.state.variables[1].catalogAlignment.confirmed, true)
assert.equal(sameApplicability.state.variables[1].definitionReview, undefined, 'The expanded source cohort still requires a fresh definition review')
assert.deepEqual(sameApplicability.state.variables[0].definitionReview, identicalApplicability.variables[0].definitionReview, 'The excluded donor keeps its history')
const unreviewedApplicablePrimary = reduce(identicalApplicability, { type: 'EDIT', id: 'keep', values: { catalogAlignment: { ...reviewedCatalog, confirmed: false } } })
const noInheritedApplicabilityReview = batchReducer.mergeMany(unreviewedApplicablePrimary, ['old', 'keep'], 'keep')
assert.equal(noInheritedApplicabilityReview.problem, null)
assert.equal(noInheritedApplicabilityReview.state.variables[1].catalogAlignment.confirmed, false, 'Equal conditions do not transfer the donor catalog confirmation')
for (const primaryConfirmed of [false, true]) for (const externalStatus of ['confirmed', 'help', 'changed', 'pending']) {
  const initial = base()
  for (const item of initial.variables.slice(0, 2)) {
    item.type = { kind: 'boolean' }; item.catalogAlignment = { status: 'matched', confirmed: item.id === 'old' || primaryConfirmed, baselineKey: 'twoEnvelopeTendering', baselineVersion: 'test' }
  }
  const external = initial.variables[2]
  external.catalogAlignment = { status: 'matched', confirmed: true, baselineKey: 'otherTenderingArrangement', baselineVersion: 'test' }
  external.sourceAnchors = [{ docId: '41', text: 'Original alternative tender procedure' }]; external.editTargets = [{ id: 'independent-target', docId: '41', text: 'Retained source treatment', action: 'check' }]
  const externalRule = { ...applicability, targetBaselineKey: 'otherTenderingArrangement', bindingFingerprint: 'previous-source-proof', clauses: [{ baselineKey: 'twoEnvelopeTendering', inputId: 'old', operator: 'eq', value: false }] }
  let reviewedExternal = setApplicability(initial, external.id, externalRule)
  if (externalStatus === 'help') reviewedExternal = reduce(reviewedExternal, { type: 'HELP', id: external.id, reason: 'The original scope still needs clarification' })
  if (externalStatus === 'changed') reviewedExternal = reduce(reviewedExternal, { type: 'EDIT', id: external.id, values: { nameEn: 'Revised alternative tender procedure' } })
  if (externalStatus === 'confirmed') reviewedExternal = reduce(reviewedExternal, { type: 'REVIEW', id: external.id })
  assert.equal(reviewedExternal.variables[2].status, externalStatus, 'The fixture establishes the external state through real review actions')
  reviewedExternal.variables[2].definitionReview = { confirmed: true, basis: 'external-reviewed-before-merge' }
  reviewedExternal.variables.push({ ...node('unrelated'), definitionReview: { confirmed: true, basis: 'unrelated-basis' } }, { ...structuredClone(reviewedExternal.variables[2]), id: 'archived-external', status: 'excluded', parentId: 'old', relatedInputIds: ['old'], correction: { kind: 'rule', inputIds: ['old'], branches: [{ id: 'historical', scopeId: 'old', clauses: [{ inputId: 'old', operator: 'known' }] }] }, editTargets: [{ id: 'historic-target', docId: '41', text: 'Original historical target', valueSourceId: 'old', listId: 'old', itemKey: 'old' }] })
  const originalExternal = structuredClone(reviewedExternal.variables[2]), archived = structuredClone(reviewedExternal.variables.at(-1)), untouched = structuredClone(reviewedExternal.variables.at(-2)), snapshot = structuredClone(reviewedExternal)
  assert.ok(valueDependencies(reviewedExternal).some(edge => edge.kind === 'applicability' && edge.from === 'old' && edge.to === external.id))
  const result = batchReducer.mergeMany(reviewedExternal, ['old', 'keep'], 'keep')
  assert.equal(result.problem, null)
  const after = result.state.variables.find(item => item.id === external.id)
  assert.deepEqual(structuredClone(after.applicability.clauses), [{ ...externalRule.clauses[0], inputId: 'keep' }], 'The external condition keeps its value and meaning while referencing the retained variable')
  assert.equal(after.applicability.confirmed, false); assert.equal(after.applicability.bindingFingerprint, undefined); assert.equal(after.definitionReview, undefined); assert.equal(after.status, externalStatus === 'confirmed' ? 'pending' : externalStatus)
  assert.equal(after.reason, originalExternal.reason, 'Migrating applicability does not hide an existing request for help or its reason')
  assert.equal(after.applicability.reason, externalRule.reason); assert.deepEqual(after.sourceAnchors, originalExternal.sourceAnchors); assert.deepEqual(after.editTargets, originalExternal.editTargets)
  assert.ok(valueDependencies(result.state).some(edge => edge.kind === 'applicability' && edge.from === 'keep' && edge.to === external.id), 'The pending applicability relationship remains visible after merging')
  assert.equal(valueDependencies(result.state).some(edge => edge.from === 'old'), false)
  assert.deepEqual(structuredClone(result.state.variables.find(item => item.id === archived.id)), archived, 'Excluded historical conditions are not rewritten')
  assert.deepEqual(structuredClone(result.state.variables.find(item => item.id === untouched.id)), untouched, 'Unrelated confirmations remain current')
  assert.deepEqual(structuredClone(reviewedExternal), snapshot, 'The original graph and source evidence remain immutable')
  const main = result.state.variables.find(item => item.id === 'keep')
  assert.equal(main.catalogAlignment.confirmed, primaryConfirmed, 'Remapping an external reference never inherits donor alignment')
  const realigned = reduce(result.state, { type: 'EDIT', id: 'keep', values: { catalogAlignment: { ...main.catalogAlignment, confirmed: true } } })
  const primaryReviewed = reduce(realigned, { type: 'CONFIRM_DEFINITION', id: 'keep' })
  assert.notEqual(primaryReviewed, realigned); assert.equal(primaryReviewed.variables.find(item => item.id === 'keep').definitionReview.confirmed, true, 'The fixture actually reconfirms the retained primary')
  const stillPending = primaryReviewed.variables.find(item => item.id === external.id)
  assert.equal(stillPending.applicability.confirmed, false); assert.equal(stillPending.applicability.bindingFingerprint, undefined); assert.equal(stillPending.definitionReview, undefined, 'Confirming the primary cannot silently restore an external condition basis')
  assert.equal(reduce(primaryReviewed, { type: 'SET_FIXED_APPLICABILITY', id: external.id, applicability: { ...externalRule, clauses: [{ ...externalRule.clauses[0], inputId: 'keep' }] } }), primaryReviewed, 'The existing condition cannot be overwritten without a separate explicit review')
}
const donorHistory = base()
donorHistory.variables[0].relatedInputIds = ['old', 'dependent']
donorHistory.variables[0].editTargets = [{ id: 'donor-own-target', docId: '41', text: 'Donor original target', action: 'fill', valueSourceId: 'old' }]
donorHistory.variables.push({ ...node('active-child'), parentId: 'old', relatedInputIds: ['old'], correction: { kind: 'rule', inputIds: ['old'], branches: [{ id: 'active-branch', scopeId: 'old', clauses: [{ inputId: 'old', operator: 'known' }] }] }, editTargets: [{ id: 'active-target', docId: '41', valueSourceId: 'old', listId: 'old', itemKey: 'old' }] })
const donorHistorySnapshot = structuredClone(donorHistory), preservedDonor = structuredClone(donorHistory.variables[0])
const remappedHistory = batchReducer.mergeMany(donorHistory, ['old', 'keep'], 'keep')
assert.equal(remappedHistory.problem, null)
const archivedDonor = remappedHistory.state.variables[0], retainedHistory = remappedHistory.state.variables[1], activeChild = remappedHistory.state.variables.at(-1)
assert.deepEqual(structuredClone(archivedDonor), { ...preservedDonor, status: 'excluded', replacedBy: ['keep'], reason: '人工合并 / Merged during review' }, 'The newly excluded donor retains its original references and targets')
assert.equal(archivedDonor.editTargets[0].valueSourceId, 'old'); assert.equal(retainedHistory.editTargets[0].valueSourceId, 'keep'); assert.notEqual(archivedDonor.editTargets[0], retainedHistory.editTargets[0], 'The active target is a copy, so remapping it cannot rewrite donor history')
assert.equal(activeChild.parentId, 'keep'); assert.deepEqual(Array.from(activeChild.relatedInputIds), ['keep']); assert.deepEqual(Array.from(activeChild.correction.inputIds), ['keep'])
assert.equal(activeChild.correction.branches[0].scopeId, 'keep'); assert.equal(activeChild.correction.branches[0].clauses[0].inputId, 'keep')
for (const key of ['valueSourceId', 'listId', 'itemKey']) assert.equal(activeChild.editTargets[0][key], 'keep')
assert.equal(activeChild.editTargets[0].needsRebind, true); assert.equal(activeChild.status, 'help', 'Active item matching still requires review after remapping')
assert.deepEqual(donorHistory, donorHistorySnapshot, 'Remapping active copies leaves the original revision intact')
for (const [mainDoc, donorDoc] of [['42', '43'], ['43', '42']]) {
  const state = structuredClone(reviewedMerge); state.variables[1].docIds = [mainDoc]; state.variables[0].docIds = [donorDoc]
  state.variables[1].sourceAnchors = [{ docId: mainDoc, text: 'Primary SCC source' }]; state.variables[0].sourceAnchors = [{ docId: donorDoc, text: 'Other SCC edition' }]
  if (mainDoc === '43') state.variables[1].pdfReferenceAlignment = { confirmed: true, sourceSha256: 'pdf-edition' }
  const snapshot = structuredClone(state), result = editionReducer.mergeMany(state, ['old', 'keep'], 'keep')
  assert.equal(result.problem.code, 'MERGE_SOURCE_EDITION_CONFLICT'); assert.equal(result.state, state); assert.deepEqual(state, snapshot, 'Existing catalog or PDF review never permits merging SCC editions')
}
const reordered = structuredClone(reviewedMerge); reordered.variables[1].type = { kind: 'choice', zh: '单选', en: 'Choice' }; reordered.variables[1].role = { kind: 'input', zh: '变量' }
const reorderedOptions = reordered.variables[1].options.map(option => ({ labelEn: option.labelEn, labelZh: option.labelZh, value: option.value }))
const equivalentValues = { type: { en: 'Choice', zh: '单选', kind: 'choice' }, role: { zh: '变量', kind: 'input' }, options: reorderedOptions }, equivalentValuesJson = JSON.stringify(equivalentValues), reorderedJson = JSON.stringify(reordered)
const equivalentEdit = reduce(reordered, { type: 'EDIT', id: 'keep', values: equivalentValues })
assert.equal(equivalentEdit.variables[1].catalogAlignment.confirmed, true, 'Object key order does not change the reviewed definition')
assert.deepEqual(equivalentEdit.variables[1].definitionReview, reordered.variables[1].definitionReview)
for (const key of ['type', 'role', 'options']) assert.equal(JSON.stringify(equivalentEdit.variables[1][key]), JSON.stringify(reordered.variables[1][key]), 'Equivalent EDIT values preserve the saved serialization used by the backend basis: ' + key)
assert.equal(JSON.stringify(equivalentValues), equivalentValuesJson, 'EDIT does not reorder its action input'); assert.equal(JSON.stringify(reordered), reorderedJson, 'EDIT does not rewrite the prior saved state')
assert.equal(equivalentEdit.variables[2].editTargets[0].needsRebind, undefined, 'Reordered option keys do not invalidate dependent ranges')
const equivalentMerge = mergeWithSct.mergeMany(equivalentEdit, ['old', 'keep'], 'keep')
assert.equal(equivalentMerge.problem, null); assert.equal(equivalentMerge.state.variables[1].catalogAlignment.confirmed, true, 'Equivalent option objects remain merge-compatible')
for (const values of [{ options: [...reorderedOptions].reverse() }, { options: reorderedOptions.map((option, index) => index ? option : { ...option, value: 'Different value' }) }, { questionEn: 'A different question' }]) {
  const changed = reduce(reordered, { type: 'EDIT', id: 'keep', values })
  assert.equal(changed.variables[1].catalogAlignment.confirmed, false, 'Array order and actual content changes remain significant')
  assert.equal(changed.variables[1].definitionReview, undefined, 'A changed definition cannot retain its old review basis')
}
for (const alignment of [undefined, { status: 'new', confirmed: true, reason: 'Local fact' }, reviewedCatalog]) {
  const state = base(), main = state.variables[1]; main.definitionReview = { confirmed: true, basis: 'before-link' }; main.sourceAnchors = [{ docId: '41', text: 'Original proof' }]
  if (alignment) main.catalogAlignment = structuredClone(alignment)
  const linked = reduce(state, { type: 'LINK', id: 'keep', anchor: { docId: '41', text: 'Additional current proof' } })
  assert.equal(linked.variables[1].definitionReview, undefined); assert.deepEqual(linked.variables[1].catalogAlignment, main.catalogAlignment, 'Adding proof changes the cohort, not its fixed identity')
  const unlinked = reduce(state, { type: 'UNLINK', id: 'keep', index: 0 })
  assert.equal(unlinked.variables[1].definitionReview, undefined); assert.deepEqual(unlinked.variables[1].catalogAlignment, main.catalogAlignment)
  assert.equal(state.variables[1].definitionReview.basis, 'before-link', 'Original source-review receipts remain unchanged')
}
const targetScope = base(); targetScope.variables[1].definitionReview = { confirmed: true, basis: 'same-documents' }
const sameDocumentTarget = mergeWithSct.reduce(targetScope, { type: 'TARGETS', id: 'keep', targets: [{ id: 't1', docId: '41', action: 'check' }] })
assert.deepEqual(sameDocumentTarget.variables[1].definitionReview, targetScope.variables[1].definitionReview, 'A range-only edit does not pretend that the variable definition changed')
const newDocumentTarget = mergeWithSct.reduce(targetScope, { type: 'TARGETS', id: 'keep', targets: [{ id: 't2', docId: '44', action: 'check' }] })
assert.equal(newDocumentTarget.variables[1].definitionReview, undefined, 'Adding a document changes the manual definition source basis')
for (const alignment of [undefined, { status: 'new', confirmed: true, reason: 'Local fact' }, reviewedCatalog]) {
  const state = base(), main = state.variables[1]
  main.definitionOrigin = { kind: 'manual', reason: 'Original definition evidence' }; main.definitionReview = { confirmed: true, basis: 'before-origin-edit' }
  main.editorialReview = { kind: 'business_value', confirmed: true }
  if (alignment) main.catalogAlignment = structuredClone(alignment)
  const equivalent = reduce(state, { type: 'EDIT', id: 'keep', values: { definitionOrigin: { reason: 'Original definition evidence', kind: 'manual' }, editorialReview: { confirmed: true, kind: 'business_value' } } })
  assert.deepEqual(equivalent.variables[1].definitionReview, main.definitionReview, 'Equivalent origin and editorial objects retain the review regardless of key order')
  for (const key of ['definitionOrigin', 'editorialReview']) assert.equal(JSON.stringify(equivalent.variables[1][key]), JSON.stringify(main[key]), 'Equivalent review evidence preserves its backend basis serialization: ' + key)
  const changed = reduce(equivalent, { type: 'EDIT', id: 'keep', values: { definitionOrigin: { kind: 'manual', reason: 'Different definition evidence' } } })
  assert.equal(changed.variables[1].definitionReview, undefined, 'Changing manual definition evidence invalidates its saved definition basis')
  assert.deepEqual(changed.variables[1].catalogAlignment, main.catalogAlignment, 'A definition evidence edit does not revoke an unchanged fixed identity')
  assert.deepEqual(main.definitionReview, { confirmed: true, basis: 'before-origin-edit' }, 'The previous review receipt remains immutable')
}
console.log('PASS: existing single-merge guards and source history; explicit three-question batch; atomic rollback, accumulated-rule conflicts and invalid selections')
