'use strict';
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const root = {}, overviewModule = { exports: {} }, viewModule = { exports: {} };
new Function('module', 'window', readFileSync(require.resolve('./graph-overview.js'), 'utf8'))(overviewModule, root);
root.GraphOverview = overviewModule.exports;
new Function('module', 'window', readFileSync(require.resolve('./graph-business-view.js'), 'utf8'))(viewModule, root);
const View = viewModule.exports;
const variable = (id, extra = {}) => ({ id, nameZh: '核对事项', nameEn: 'Review item', docIds: ['ntt'], status: 'changed', ...extra });
const state = { variables: [
  variable('yes'), variable('detail', { parentId: 'list' }), variable('list', { type: { kind: 'list' } }),
  variable('answer', { correction: { kind: 'rule', branches: [
    { id: 'first', mode: 'all', scopeId: 'list', scopeMode: 'each', resultValue: false,
      clauses: [{ inputId: 'yes', operator: 'yes', value: '' }, { inputId: 'detail', operator: 'known' }] },
    { id: 'second', mode: 'any', clauses: [{ inputId: 'yes', operator: 'no', value: '0' }] },
    { id: 'unknown', mode: 'unknown', clauses: [{ inputId: 'yes', operator: 'yes' }] }
  ] }, editTargets: [
    { docId: 'ntt', branchId: 'first', action: 'delete' },
    { docId: 'sct', branchId: 'first', action: 'replace', valueSourceId: 'detail', valueMode: 'join', listId: 'list', separator: '; ' },
    { docId: 'ntt', branchId: 'unknown', action: 'keep' },
    { docId: 'sct', action: 'fill', valueSourceId: 'yes' },
    { docId: 'ntt', branchId: 'no-such-case', action: 'check', valueSourceId: 'yes' }
  ] }),
  variable('no-destination', { docIds: ['sct'], correction: { kind: 'rule', mode: 'all', clauses: [] } }),
  variable('split', { correction: { kind: 'split', inputIds: ['yes', 'split'], mode: 'all', needsRebind: true } }),
  variable('stale', { correction: { kind: 'rule', needsRebind: true, branches: [
    { id: 'case', mode: 'all', clauses: [{ inputId: 'gone', operator: 'yes' }] }
  ] }, editTargets: [{ docId: 'ntt', branchId: 'case', action: 'replace', valueSourceId: 'old' }] }),
  variable('old', { status: 'excluded', replacedBy: ['yes'] }),
  variable('reference', { relatedInputIds: ['yes'], relations: [{ branches: [{ resultEn: 'Legacy text is reference.' }] }] }),
  variable('word', { docIds: ['scc-word'], editTargets: [{ docId: 'scc-word', action: 'delete' }] }),
  variable('pdf', { docIds: ['scc-pdf'], correction: { kind: 'rule', branches: [
    { id: 'case', mode: 'all', clauses: [{ inputId: 'word', operator: 'yes' }] }
  ] }, editTargets: [{ docId: 'scc-pdf', branchId: 'case', action: 'delete' }] }),
  variable('cycle-a', { parentId: 'cycle-b' }), variable('cycle-b', { parentId: 'cycle-a' }),
  variable('unrecognized-document', { editTargets: [{ docId: 'unknown-document', action: 'check' }] })
] };
const before = JSON.stringify(state), view = View.build(state);
assert.equal(JSON.stringify(state), before, 'Viewing never edits the saved graph.');
assert.equal(view.variables.length, state.variables.length, 'Every record stays visible, even one with no action.');
assert.equal(view.files.length, 4);
assert.deepEqual(view.files.map(file => file.docId), ['ntt', 'sct', 'scc-word', 'scc-pdf']);
assert(!view.files.some(file => file.docId === 'unknown-document'), 'Unknown document IDs are not assigned to a known template.');
assert(view.variables.every(item => item.x >= 26 && item.x < 100 && item.w >= 180));
assert(view.actions.every(item => item.x === 300 && item.w === 230));
assert(view.files.every(item => item.x === 600 && item.w === 170 && item.h >= 88));
assert.equal(view.width, 800);
assert.equal(view.variables.find(item => item.id === 'yes').w, 214);
assert(view.variables.every(item => item.x + item.w < 300));
assert(view.actions.every(item => item.x + item.w < 600));
assert(view.files.every(item => item.x + item.w < view.width));
assert.equal(view.variables.find(item => item.id === 'detail').depth, 1);
assert.equal(view.variables.find(item => item.id === 'detail').h, 70, 'A detail retains enough room for both languages.');
assert.equal(view.variables.find(item => item.id === 'yes').h, 80, 'A question retains enough room for both languages and its answer format.');
assert(view.variables.every((item, index) => !index || item.y >= view.variables[index - 1].y + view.variables[index - 1].h), 'Question boxes never overlap.');
assert(view.variables.findIndex(item => item.id === 'list') < view.variables.findIndex(item => item.id === 'detail'));
assert(view.variables.find(item => item.id === 'cycle-a').parentCycle);
assert(view.variables.find(item => item.id === 'cycle-b').parentCycle);
const answerActions = view.actions.filter(item => item.ownerId === 'answer');
assert.equal(answerActions.length, 5, 'Three cases, one all-cases action and one unresolved assignment remain separate.');
assert.equal(answerActions[0].y, view.variables.find(item => item.id === 'answer').y, 'A question and its first case share one visible row.');
assert(answerActions.every((action, index) => !index || action.y === answerActions[index - 1].y + 92));
const first = answerActions.find(item => item.branchId === 'first');
assert.equal(first.branch, state.variables[3].correction.branches[0], 'Condition metadata retains its source object.');
assert.equal(first.mode, 'all');
assert.equal(first.scopeMode, 'each');
assert.equal(first.resultValue, false);
assert.equal(first.targets.length, 2);
assert.equal(first.targets[0].target, state.variables[3].editTargets[0]);
assert.equal(first.targets[0].index, 0);
assert.deepEqual(first.operations, ['delete', 'replace'], 'A single case retains all its different operations.');
assert.equal(answerActions.find(item => item.branchId === 'second').mode, 'any');
assert.equal(answerActions.find(item => item.branchId === 'second').targets.length, 0, 'An untargeted branch is retained.');
assert(answerActions.find(item => item.branchId === 'second').missingDestination);
assert(answerActions.find(item => item.branchId === 'unknown').unresolved);
assert(answerActions.find(item => item.kind === 'missing-branch').unresolved);
assert.equal(answerActions.find(item => item.kind === 'all-cases').targets.length, 1);
assert.equal(view.edges.filter(edge => edge.from === first.id && edge.kind === 'destination').length, 2);
assert(!view.edges.some(edge => edge.ownerId === 'no-destination' && edge.kind === 'destination'), 'Owner document membership does not invent a modification destination.');
assert(view.actions.find(item => item.ownerId === 'no-destination').needsReview);
assert(view.actions.find(item => item.kind === 'split').referenceOnly);
assert(view.actions.find(item => item.ownerId === 'reference').referenceOnly);
assert(!view.edges.some(edge => edge.kind === 'action-input' && edge.ownerId === 'reference'), 'Legacy prose does not become an executable input.');
assert(view.actions.find(item => item.ownerId === 'stale').needsRebind);
assert(view.actions.find(item => item.ownerId === 'stale').missing);
assert(view.actions.find(item => item.ownerId === 'pdf').incompatible, 'Word SCC and PDF SCC cannot form a valid cross-version rule.');
assert(view.edges.filter(edge => edge.ownerId === 'pdf' && edge.kind === 'action-input').every(edge => edge.incompatible));
assert(view.edges.some(edge => edge.kind === 'ownership' && edge.from === 'answer' && edge.to === first.id && edge.ownershipOnly && edge.referenceOnly));
assert.equal(view.edges.filter(edge => edge.kind === 'condition' && edge.ownerId === 'answer').length, 4, 'The left-side original declared conditions remain present.');
assert(view.edges.some(edge => edge.kind === 'parent' && edge.from === 'list' && edge.to === 'detail'));
assert(view.edges.some(edge => edge.kind === 'reference' && edge.from === 'yes' && edge.to === 'reference'));
assert(view.edges.some(edge => edge.kind === 'action-input' && edge.role === 'join' && edge.separator === '; '));
assert(view.edges.some(edge => edge.kind === 'action-input' && edge.role === 'condition' && edge.operator === 'no' && edge.value === '0'));
assert(view.edges.filter(edge => edge.kind === 'action-input' && edge.to === first.id).every(edge => edge.path.startsWith('M')));
assert.equal(new Set(view.edges.map(edge => edge.id)).size, view.edges.length);
assert(view.edges.filter(edge => edge.from === 'yes' && edge.kind === 'action-input').map(edge => edge.path).length > 1);
assert.equal(View.build(state, { query: 'NO SUCH MATCH' }).variables.length, view.variables.length, 'Search never removes the full graph.');
assert(View.build(state, { query: 'NO SUCH MATCH' }).variables.every(item => !item.matched));
assert.equal(View.build(state, { includeExcluded: false }).variables.length, view.variables.length - 1);
assert.equal(View.build(state, { docId: 'scc-pdf' }).files.length, 4);
assert(View.build(state, { docId: 'scc-pdf' }).variables.find(item => item.id === 'word').contextual);
const selected = View.selection(view, 'answer');
assert(selected.variableIds.includes('yes') && selected.variableIds.includes('detail') && selected.variableIds.includes('list'));
assert.equal(selected.actionIds.length, answerActions.length, 'Selecting a question highlights all of its cases for comparison.');
assert(selected.fileIds.includes('file:ntt') && selected.fileIds.includes('file:sct'));
assert.equal(View.selection(view, first.id).actionIds.length, 1);
assert(View.selection(view, first.id).variableIds.includes('answer'));
assert.deepEqual(View.selection(view, first.id).fileIds.sort(), ['file:ntt', 'file:sct']);
assert(!view.edges.some(edge => edge.kind === 'condition' && edge.ownerId === 'answer' && edge.branchIndex === 1 && View.selection(view, first.id).edgeIds.includes(edge.id)), 'A different case\'s condition line is not highlighted even when it uses the same input.');
const second = answerActions.find(item => item.branchId === 'second');
assert(!View.selection(view, second.id).variableIds.includes('detail'), 'Selecting one case does not highlight another case\'s unrelated input.');
assert.equal(View.selection(view, second.id).fileIds.length, 0, 'An untargeted case never borrows another case\'s destinations.');
assert.equal(View.build({ variables: [] }).variables.length, 0);
assert.throws(() => View.build({}), TypeError);
assert.equal(JSON.stringify(state), before);

function assertCoverage(reviewState) {
  const snapshot = JSON.stringify(reviewState), full = View.build(reviewState);
  assert.equal(full.variables.length, reviewState.variables.length);
  let branchCount = 0, targetCount = 0;
  for (const owner of reviewState.variables) {
    const actions = full.actions.filter(action => action.ownerId === owner.id);
    const ownerBox = full.variables.find(item => item.id === owner.id), ownerIndex = full.variables.indexOf(ownerBox);
    if (actions.length) {
      assert.equal(actions[0].y, ownerBox.y);
      assert(actions.every((action, index) => action.y === ownerBox.y + index * 92));
    }
    const next = full.variables[ownerIndex + 1];
    if (next) {
      assert(next.y >= ownerBox.y + ownerBox.h, 'Variable boxes do not overlap.');
      assert(actions.every(action => action.y + action.h <= next.y), 'All owned cases fit before the next question starts.');
    }
    const branches = owner.correction?.kind === 'rule' ? owner.correction.branches || [{ ...owner.correction, id: 'case-1' }] : [];
    branchCount += branches.length;
    for (const [branchIndex, branch] of branches.entries()) {
      const action = actions.find(item => item.kind === 'branch' && item.branchIndex === branchIndex);
      assert(action, 'Every real explicit case is represented.');
      assert.equal(action.branchId, branch.id);
      assert.equal(action.mode, branch.mode);
      assert.equal(action.scopeMode, branch.scopeMode);
      assert.deepEqual(action.branch, branch);
    }
    for (const [index, target] of (owner.editTargets || []).entries()) {
      targetCount++;
      const containing = actions.filter(action => action.targets.some(entry => entry.index === index));
      assert.equal(containing.length, 1, 'Each real target belongs to exactly one independent action.');
      assert.equal(containing[0].targets.find(entry => entry.index === index).target, target);
      if (['ntt', 'sct', 'scc-word', 'scc-pdf'].includes(target.docId)) assert(full.edges.some(edge => edge.kind === 'destination' && edge.actionId === containing[0].id && edge.docId === target.docId && edge.targets.some(entry => entry.index === index)));
    }
  }
  assert.equal(full.actions.filter(action => action.kind === 'branch').length, branchCount);
  assert.equal(full.actions.reduce((sum, action) => sum + action.targets.length, 0), targetCount);
  assert(full.variables.every(item => item.y + item.h <= full.height));
  assert(full.actions.every(item => item.y + item.h <= full.height));
  const positions = JSON.stringify([full.variables, full.actions, full.files]);
  for (const id of [full.variables[0]?.id, full.actions[0]?.id].filter(Boolean)) View.selection(full, id);
  assert.equal(JSON.stringify([full.variables, full.actions, full.files]), positions, 'Selection does not move the layout.');
  assert.equal(JSON.stringify(reviewState), snapshot);
  return { records: full.variables.length, branches: branchCount, targets: targetCount, actions: full.actions.length };
}
assertCoverage(state);
if (process.argv[2]) {
  const saved = JSON.parse(readFileSync(process.argv[2], 'utf8'));
  console.log('PASS: real saved graph coverage ' + JSON.stringify(assertCoverage(saved.graph || saved.state || saved)));
}
console.log('PASS: three-column graph, original case/target coverage, safe references and SCC isolation, stable routes, selection and no data mutation.');
