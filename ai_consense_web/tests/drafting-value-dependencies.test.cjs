// Synthetic graph contracts call the real frontend modules; no business data or server writes.
const assert = require('node:assert/strict'), path = require('node:path')
const { loadVue } = require('./helpers/load-vue.cjs')
const loaded = loadVue(path.join(__dirname, '../src/drafting/source-review-state.ts'), { globals: { structuredClone } })
const { reviewProblem } = loaded
const { createReviewReducer } = loaded.loadLocal(path.join(__dirname, '../src/drafting/source-review/reducer.js'))
const overview = loaded.loadLocal(path.join(__dirname, '../src/drafting/source-review/graph-overview.js')).default
const business = loaded.loadLocal(path.join(__dirname, '../src/drafting/source-review/graph-business-view.js')).default
const copy = value => JSON.parse(JSON.stringify(value))
const source = { id: '41', fileKey: 'NTT', fileName: 'SYNTHETIC.docx', format: 'docx', sourceSha256: 'a'.repeat(64) }
const anchor = (text, start = 0, end = text.length) => ({ docId: '41', sourceSha256: source.sourceSha256, text: text.slice(start, end), native: { paragraphId: 'p:' + text, paragraphSha256: 'b'.repeat(64), start, end, expectedText: text.slice(start, end) } })
const node = (id, role = 'input', kind = 'boolean') => ({ id, nameZh: id, nameEn: id, docIds: ['41'], type: { kind }, role: { kind: role }, parentId: null, status: 'pending', options: [], sourceAnchors: [anchor('SOURCE ' + id)], editTargets: [], sourceUnitIds: [], relatedInputIds: [], relations: [], targets: [], evidence: [] })
const state = variables => ({ variables, events: [], sourceReviews: {}, nextId: 1, blank: false, guidanceMode: 'recognized' })
const rule = (id, inputId, result) => ({ kind: 'rule', branches: [{ id, mode: 'all', clauses: [{ inputId, operator: 'yes' }], ...(result === undefined ? {} : { resultValue: result }) }] })
const reducer = createReviewReducer({ sources: [source] }), failures = []
let checks = 0
function check(name, run) { checks++; try { run(); console.log('PASS: ' + name) } catch (error) { failures.push(name + ': ' + error.message); console.error('FAIL: ' + name + ': ' + error.message) } }

for (const role of ['input', 'decision', 'content']) check(role + ' own answer controls its amendment', () => {
  const n = node(role, role); n.correction = rule('case', n.id); n.editTargets = [{ ...anchor('DELETE'), action: 'delete', branchId: 'case' }]
  assert.equal(reviewProblem(n, state([n])), null)
  assert.equal(overview.project(state([n])).warnings.some(w => w.kind === 'cycle'), false)
})
check('derived self result remains invalid', () => { const n = node('A', 'derived'); n.correction = rule('case', n.id, true); assert.match(reviewProblem(n, state([n])), /循环|cyclic|self/i) })
check('derived mutual cycle blocks review and graph actions', () => {
  const a = node('A', 'derived'), b = node('B', 'derived'); a.correction = rule('a', b.id, true); b.correction = rule('b', a.id, true)
  for (const n of [a, b]) n.editTargets = [{ ...anchor(n.id), action: 'delete', branchId: n.correction.branches[0].id }]
  const s = state([a, b]); for (const n of s.variables) assert.match(reviewProblem(n, s), /循环|cyclic/i)
  assert.equal(reducer.reduce(s, { type: 'REVIEW', id: a.id }), s, 'The reducer cannot confirm a derived cycle')
  const view = business.build(s, { sources: [source] }); assert.ok(view.actions.every(a => a.needsReview))
  assert.ok(view.variables.every(n => n.cyclic), 'Cycle members stay visibly invalid in the graph')
  assert.deepEqual(overview.impact(view.graph, 'A').downstreamIds, [])
})
check('independent reciprocal amendment conditions are not value cycles', () => {
  const a = node('A'), b = node('B'); a.correction = rule('a', b.id); b.correction = rule('b', a.id)
  const s = state([a, b]); assert.ok(s.variables.every(n => reviewProblem(n, s) === null)); assert.equal(overview.project(s).warnings.filter(w => w.kind === 'cycle').length, 0)
})
check('printing another derived answer is not an answer derivation edge', () => {
  const fact = node('FACT'), a = node('A', 'derived'), b = node('B', 'derived'); a.correction = rule('a', fact.id, true); b.correction = rule('b', a.id, true)
  a.editTargets = [{ ...anchor('PRINT'), action: 'replace', valueSourceId: b.id }]
  const s = state([fact, a, b]); assert.equal(reviewProblem(a, s), null); assert.equal(overview.project(s).warnings.filter(w => w.kind === 'cycle').length, 0)
})
check('amendment inputs select their actions without changing an independent owner answer', () => {
  const a = node('A'), b = node('B'), c = node('C', 'derived'); a.correction = rule('a', b.id); a.editTargets = [{ ...anchor('A'), action: 'delete', branchId: 'a' }]; c.correction = rule('c', a.id, true); c.editTargets = [{ ...anchor('C'), action: 'delete', branchId: 'c' }]
  const s = state([a, b, c]), view = business.build(s, { sources: [source] }), selected = business.selection(view, b.id)
  assert.deepEqual(overview.impact(view.graph, b.id).downstreamIds, []); assert.deepEqual(Array.from(selected.actionIds), ['action:A:branch:0']); assert.ok(selected.variableIds.includes(a.id)); assert.equal(selected.variableIds.includes(c.id), false)
})
check('derived record reading its input child is a value cycle', () => {
  const record = node('RECORD', 'derived', 'record'), child = node('CHILD'); child.parentId = record.id; record.correction = rule('case', child.id, {})
  const s = state([record, child]); assert.match(reviewProblem(record, s), /循环|cyclic/i); assert.equal(overview.project(s).warnings.filter(w => w.kind === 'cycle').length, 2)
})
check('list row context does not create a value cycle', () => {
  const list = node('ROWS', 'input', 'list'), child = node('CHILD', 'derived'); child.parentId = list.id; child.correction = rule('row', 'FACT', true)
  child.correction.branches[0].scopeId = list.id; child.correction.branches[0].scopeMode = 'each'
  const s = state([list, child, node('FACT')]); assert.equal(reviewProblem(child, s), null); assert.equal(overview.project(s).warnings.filter(w => w.kind === 'cycle').length, 0)
})
for (const patch of [{ role: { kind: 'verification' } }, { definitionKind: 'source_seed', manual: true }]) check('source check cannot supply an executable condition ' + JSON.stringify(patch), () => {
  const checkNode = { ...node('CHECK'), ...patch }, owner = node('OWNER'); owner.correction = rule('case', checkNode.id)
  owner.editTargets = [{ ...anchor('DELETE'), action: 'delete', branchId: 'case' }]
  assert.match(reviewProblem(owner, state([owner, checkNode])), /来源|source check/i)
  const view = business.build(state([owner, checkNode]), { sources: [source] }); assert.ok(view.actions.every(a => a.needsReview)); assert.ok(view.graph.warnings.some(w => w.kind === 'invalidSource'))
})
check('source checks cannot supply amendment text or item matching values', () => {
  const checkNode = { ...node('CHECK'), definitionKind: 'source_seed', manual: true }, owner = node('OWNER')
  for (const key of ['valueSourceId', 'listId', 'itemKey']) { owner.editTargets = [{ ...anchor('REPLACE'), action: 'replace', [key]: checkNode.id }]; assert.match(reviewProblem(owner, state([owner, checkNode])), /source check/i) }
})
check('merge rejects a newly manufactured derived self cycle atomically', () => {
  const old = node('OLD', 'derived'), keep = node('KEEP', 'derived'); old.correction = rule('case', keep.id, true)
  const s = state([old, keep]), before = copy(s); assert.equal(reducer.mergeProblem(s, old.id, keep.id).code, 'MERGE_DEPENDENCY_CYCLE'); assert.equal(reducer.reduce(s, { type: 'MERGE', id: old.id, targetId: keep.id }), s); assert.deepEqual(s, before)
})
check('merge still permits independent own-answer amendment routes', () => {
  const old = node('OLD'), keep = node('KEEP'); old.correction = rule('case', old.id)
  const s = state([old, keep]); assert.equal(reducer.mergeProblem(s, old.id, keep.id), null); const next = reducer.reduce(s, { type: 'MERGE', id: old.id, targetId: keep.id }); assert.equal(reviewProblem(next.variables[1], next), null)
})
check('split retains active prerequisite branches and exact target ownership', () => {
  const old = node('OLD'), prerequisite = node('PREREQUISITE'); old.correction = rule('case', prerequisite.id); old.editTargets = [{ ...anchor('FIRST'), action: 'delete', branchId: 'case' }, { ...anchor('SECOND'), action: 'keep', branchId: 'case' }]
  const s = state([old, prerequisite]); s.requirements = [{ id: 'requirement', nodeIds: [old.id], sourceQuote: 'Original requirement' }]
  const before = copy(s), next = reducer.reduce(s, { type: 'SPLIT', id: old.id, mode: 'unknown', items: [{ nameZh: 'first', nameEn: 'first', sourceAnchors: [anchor('FIRST')] }, { nameZh: 'second', nameEn: 'second', sourceAnchors: [anchor('SECOND')] }] })
  const children = next.variables.filter(n => n.originId === old.id); assert.equal(children.length, 2)
  for (const [index, child] of children.entries()) { assert.equal(child.editTargets.length, 1); assert.equal(child.editTargets[0].text, index ? 'SECOND' : 'FIRST'); assert.equal(child.correction.branches[0].clauses[0].inputId, prerequisite.id); assert.equal(child.correction.needsRebind, true); assert.equal(child.editTargets[0].needsRebind, true) }
  assert.equal(children[0].splitReview.mode, 'unknown'); assert.deepEqual(s, before); assert.deepEqual(copy(next.variables[0].correction), old.correction)
  assert.deepEqual(Array.from(next.requirements[0].nodeIds), children.map(n => n.id)); assert.equal(next.requirements[0].sourceQuote, 'Original requirement')
})
check('split refuses unmatched or overlapping target ownership without changing the graph', () => {
  const old = node('OLD'); old.editTargets = [{ ...anchor('WHOLE'), action: 'delete' }]; const s = state([old])
  for (const selections of [[anchor('FIRST'), anchor('SECOND')], [anchor('WHOLE'), anchor('WHOLE')]]) assert.equal(reducer.reduce(s, { type: 'SPLIT', id: old.id, mode: 'all', items: selections.map((a, i) => ({ nameZh: '' + i, nameEn: '' + i, sourceAnchors: [a] })) }), s)
})
check('unknown split relationships keep amendment actions pending until explicitly reviewed', () => {
  const n = node('A'); n.correction = rule('case', n.id); n.editTargets = [{ ...anchor('DELETE'), action: 'delete', branchId: 'case' }]; n.splitReview = { mode: 'unknown', inputIds: [n.id] }
  const s = state([n]); assert.match(reviewProblem(n, s), /split variables/); assert.ok(business.build(s, { sources: [source] }).actions.every(a => a.needsReview))
  n.splitReview.mode = 'all'; assert.equal(reviewProblem(n, s), null); assert.equal(business.build(s, { sources: [source] }).actions.find(a => a.kind === 'branch').needsReview, false)
})
check('native exact selection guard is still enforced before rule review', () => {
  const n = node('A'); n.correction = rule('case', n.id); n.editTargets = [{ ...anchor('T'), action: 'delete', needsRebind: true }]; assert.match(reviewProblem(n, state([n])), /needs review/)
})
console.log(`${checks - failures.length}/${checks} dependency and identity contracts passed`)
if (failures.length) process.exitCode = 1
