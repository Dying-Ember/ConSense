'use strict';
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const sourceCode = readFileSync(require.resolve('./review-pack.js'), 'utf8');
const commonjs = { exports: {} };
new Function('module', sourceCode)(commonjs);
const { validate, append } = commonjs.exports;
const browserWindow = {};
new Function('window', 'module', sourceCode)(browserWindow, undefined);
assert.equal(typeof browserWindow.ReviewPack.validate, 'function');
assert.equal(typeof browserWindow.ReviewPack.append, 'function');

const context = {
  sourceKey: 'synthetic-source',
  documents: Object.fromEntries(['ntt', 'sct', 'scc-word', 'scc-pdf'].map(id => [id, { pages: [{ words: [{ text: 'a' }, { text: 'b' }, { text: 'c' }] }] }])),
  types: ['text', 'boolean', 'number', 'choice', 'multiselect', 'record', 'list'],
  statuses: ['pending', 'changed', 'confirmed', 'help', 'excluded']
};
const source = { docId: 'ntt', page: 1, start: 0, end: 3, wordIds: [0, 2], text: 'a c' };
const variable = id => ({
  id, definitionKind: 'input', nameZh: '合成事项', nameEn: 'Synthetic matter', type: { kind: 'boolean', zh: '是非', en: 'Boolean' },
  role: { kind: 'input', zh: '项目资料', en: 'Project information' }, evidence: [{ docId: 'ntt', quote: 'a c' }], targets: [], relations: [],
  docIds: ['ntt'], status: 'pending', sourceAnchors: [structuredClone(source)], editTargets: [{ ...structuredClone(source), action: 'fill' }],
  sourceUnitIds: [], relatedInputIds: [], replacedBy: []
});
const makePack = () => ({ kind: 'consense-review-v1', sourceKey: context.sourceKey, state: {
  variables: [variable('a')], sourceReviews: { note1: false }, events: [{ zh: '合成', en: 'Synthetic', details: [], time: 'now' }], nextId: 1, blank: true, guidanceMode: 'imperfect'
} });
const valid = pack => assert.deepEqual(validate(pack, context), { ok: true, errors: [] });
const rejects = mutate => { const pack = makePack(); mutate(pack); assert.equal(validate(pack, context).ok, false); };
valid(makePack());
rejects(pack => { pack.kind = 'other'; });
rejects(pack => { pack.sourceKey = 'different'; });
rejects(pack => { pack.state.variables[0].nameEn = ' '; });
rejects(pack => { pack.state.variables[0].type.kind = 'unknown'; });
rejects(pack => { delete pack.state.variables[0].role; });
rejects(pack => { pack.state.variables[0].role = { kind: 'other', zh: '资料', en: 'Input' }; });
for (const field of ['evidence', 'targets', 'relations']) {
  rejects(pack => { delete pack.state.variables[0][field]; });
  rejects(pack => { pack.state.variables[0][field] = {}; });
  rejects(pack => { pack.state.variables[0][field] = [null]; });
}
rejects(pack => { pack.state.variables[0].evidence[0].quote = {}; });
rejects(pack => { pack.state.variables[0].targets = [{ docId: 'ntt', labelZh: {} }]; });
rejects(pack => { pack.state.variables[0].relations = [{ branches: [null] }]; });
rejects(pack => { pack.state.variables[0].relations = [{ branches: {} }]; });
rejects(pack => { pack.state.variables[0].status = 'open'; });
rejects(pack => { pack.state.variables.push(variable('a')); });
rejects(pack => { pack.state.variables[0].docIds = ['scc-word', 'scc-pdf']; });
rejects(pack => { pack.state.variables[0].docIds = {}; });
rejects(pack => { pack.state.variables[0].docIds = { includes: 7 }; });
rejects(pack => { pack.state.variables[0].sourceAnchors[0].docId = 'other'; });
rejects(pack => { pack.state.variables[0].sourceAnchors[0].page = 0; });
rejects(pack => { pack.state.variables[0].sourceAnchors[0].page = 2; });
rejects(pack => { pack.state.variables[0].sourceAnchors[0].wordIds = [3]; });
rejects(pack => { pack.state.variables[0].sourceAnchors[0].wordIds = [0, 0]; });
rejects(pack => { pack.state.variables[0].sourceAnchors[0].end = 4; });
rejects(pack => { pack.state.variables[0].editTargets[0].action = 'execute'; });
rejects(pack => { pack.state.variables[0].parentId = 'missing'; });
rejects(pack => { pack.state.variables.push({ ...variable('b'), parentId: 'a' }); });
rejects(pack => { pack.state.variables[0].type.kind = 'record'; pack.state.variables[0].parentId = 'a'; });
rejects(pack => { pack.state.variables = [{ ...variable('a'), type: { kind: 'record' }, parentId: 'b' }, { ...variable('b'), type: { kind: 'list' }, parentId: 'a' }]; });
rejects(pack => { pack.state.events[0].details = [7]; });
rejects(pack => { pack.state.sourceReviews.note1 = 'yes'; });
rejects(pack => { pack.state.nextId = -1; });

const minimalAdd = makePack();
delete minimalAdd.state.variables[0].editTargets;
valid(minimalAdd);
const group = makePack();
group.state.variables = [{ ...variable('group'), definitionKind: 'added', type: { kind: 'record' } }, { ...variable('child'), parentId: 'group' }];
valid(group);
const rule = makePack();
rule.state.variables[0].correction = { kind: 'rule', mode: 'all', clauses: [{ inputId: 'a', operator: 'yes', value: '' }], effectZh: '保留', effectEn: 'Keep' };
rule.state.variables[0].editTargets[0].branchId = 'case-1';
valid(rule);
const split = makePack();
split.state.variables[0].correction = { kind: 'split', mode: 'any', inputIds: ['a'] };
valid(split);

const inDocument = (id, docId, kind = 'boolean') => ({ ...variable(id), docIds: [docId], type: { kind, zh: kind, en: kind },
  sourceAnchors: [{ ...source, docId }], editTargets: [{ ...source, docId, action: 'fill' }], evidence: [{ docId, quote: source.text }] });
const mixedParent = makePack();
mixedParent.state.variables = [inDocument('parent', 'scc-word', 'record'), { ...inDocument('child', 'scc-pdf'), parentId: 'parent' }];
assert.equal(validate(mixedParent, context).ok, false);
const crossRule = makePack();
crossRule.state.variables = [{ ...inDocument('a', 'scc-word'), status: 'confirmed', correction: {
  kind: 'rule', mode: 'all', clauses: [{ inputId: 'b', operator: 'yes', value: '' }], effectZh: '保留', effectEn: 'Keep'
} }, inDocument('b', 'scc-pdf')];
assert.equal(validate(crossRule, context).ok, false);
for (const historical of ['excluded', 'needsRebind']) {
  const pack = structuredClone(crossRule);
  if (historical === 'excluded') pack.state.variables[0].status = 'excluded';
  else { pack.state.variables[0].status = 'help'; pack.state.variables[0].correction.needsRebind = true; }
  valid(pack);
}

const logic = readFileSync(require.resolve('./prototype.logic.js'), 'utf8');
const compatibleLine = logic.split('\n').find(line => line.includes('const compatible ='));
const clauseBody = logic.slice(logic.indexOf('  function validateClauses('), logic.indexOf('  function openCorrections('));
const actualClauseGuard = variables => new Function('getName', compatibleLine + '\n' + clauseBody + '\nreturn validateClauses;')(id => variables.find(item => item.id === id) || {});
for (const [kind, operator, value, expected] of [
  ['boolean', 'yes', '', true], ['text', 'yes', '', false], ['text', 'no', '', false],
  ['number', 'ge', '39', true], ['number', 'ge', 'many', false], ['text', 'ge', '39', false],
  ['number', 'lt', '39', true], ['number', 'gt', '0', true], ['text', 'lt', '39', false], ['number', 'lt', '', false],
  ['number', 'le', '', false], ['record', 'eq', 'a', false], ['list', 'eq', 'a', false],
  ['multiselect', 'eq', 'a', false], ['choice', 'eq', 'a', true], ['choice', 'eq', 'missing', false],
  ['multiselect', 'has', 'a', true], ['multiselect', 'lacks', 'b', true], ['multiselect', 'has', 'missing', false], ['text', 'has', 'a', false],
  ['number', 'eq', '0', true], ['text', 'known', '', true]
]) {
  const pack = makePack(), input = inDocument('input', 'ntt', kind), clause = { inputId: 'input', operator, value };
  if(['choice','multiselect'].includes(kind))input.options=[{value:'a',labelZh:'甲',labelEn:'A'},{value:'b',labelZh:'乙',labelEn:'B'}];
  pack.state.variables.push(input);
  pack.state.variables[0].correction = { kind: 'rule', mode: 'all', clauses: [clause], effectZh: '保留', effectEn: 'Keep' };
  let uiAccepted = true;
  try { actualClauseGuard(pack.state.variables)([clause], pack.state.variables[0]); } catch { uiAccepted = false; }
  assert.equal(uiAccepted, expected, `UI guard: ${kind}/${operator}/${value}`);
  assert.equal(validate(pack, context).ok, uiAccepted, `Import guard: ${kind}/${operator}/${value}`);
}
const excludedInput = makePack();
excludedInput.state.variables.push({ ...variable('input'), status: 'excluded' });
excludedInput.state.variables[0].correction = { kind: 'rule', mode: 'all', clauses: [{ inputId: 'input', operator: 'yes', value: '' }] };
assert.equal(validate(excludedInput, context).ok, false);
const invalidSplit = makePack();
invalidSplit.state.variables[0].type.kind = 'text';
invalidSplit.state.variables[0].correction = { kind: 'split', mode: 'all', inputIds: ['a'] };
assert.equal(validate(invalidSplit, context).ok, false);
invalidSplit.state.variables[0].correction.needsRebind = true;
valid(invalidSplit);

const branched = makePack();
branched.state.variables[0].correction = { kind: 'rule', branches: [
  { id: 'adopt', mode: 'all', clauses: [{ inputId: 'a', operator: 'yes', value: '' }], effectZh: '填写', effectEn: 'Fill' },
  { id: 'omit', mode: 'all', clauses: [{ inputId: 'a', operator: 'no', value: '' }], effectZh: '删除', effectEn: 'Delete' }
] };
Object.assign(branched.state.variables[0].editTargets[0], { branchId: 'adopt', valueSourceId: 'a', replacementZh: '新内容', replacementEn: 'New text', prefix: 'Section ', suffix: ':-' });
valid(branched);
const confirmed = structuredClone(branched);
confirmed.state.variables[0].status = 'confirmed';
valid(confirmed);
const definitionOnly = structuredClone(confirmed);
delete definitionOnly.state.variables[0].editTargets;
delete definitionOnly.state.variables[0].correction;
valid(definitionOnly);
const fixedReplacement = structuredClone(confirmed);
Object.assign(fixedReplacement.state.variables[0].editTargets[0], { action: 'replace', valueSourceId: '', replacementEn: 'New wording' });
valid(fixedReplacement);
const derived = structuredClone(confirmed);
derived.state.variables[0].role.kind = 'derived';
derived.state.variables[0].correction.branches.forEach((branch,index) => { branch.resultValue = index === 0; });
valid(derived);
const missingDerivedAnswer = structuredClone(derived);
delete missingDerivedAnswer.state.variables[0].correction.branches[0].resultValue;
assert.equal(validate(missingDerivedAnswer,context).ok,false);
missingDerivedAnswer.state.variables[0].status='changed';
valid(missingDerivedAnswer);
for(const value of ['true',null,1,{},[]]){
  const invalidDerivedAnswer=structuredClone(derived);
  invalidDerivedAnswer.state.variables[0].correction.branches[0].resultValue=value;
  assert.equal(validate(invalidDerivedAnswer,context).ok,false);
}
for (const legacy of [false, true]) {
  const pendingDerived = structuredClone(derived), owner = pendingDerived.state.variables[0];
  owner.status = 'pending';
  owner.editTargets = [];
  owner.correction.branches = [owner.correction.branches[0]];
  pendingDerived.state.variables.push(variable('independent-input'));
  owner.correction.branches[0].clauses = [{ inputId: 'independent-input', operator: 'yes', value: '' }];
  if (legacy) owner.correction = { kind: 'rule', ...owner.correction.branches[0] };
  const answerCase = legacy ? owner.correction : owner.correction.branches[0];
  answerCase.resultValue = 'false';
  assert.equal(validate(pendingDerived, context).ok, false,
    'Pending legacy and branched boolean cases must both reject a defined string answer.');
  delete answerCase.resultValue;
  valid(pendingDerived);
  answerCase.resultValue = 'false';
  owner.correction.needsRebind = true;
  owner.status = 'help';
  valid(pendingDerived);
  owner.status = 'confirmed';
  assert.equal(validate(pendingDerived, context).ok, false,
    'A recoverable historical result cannot be imported as confirmed.');
  owner.status = 'pending';
  delete owner.correction.needsRebind;
  answerCase.resultValue = false;
  valid(pendingDerived);
  const recovered = append({ variables: [], events: [], sourceReviews: {}, nextId: 1 }, pendingDerived);
  assert.equal(legacy ? recovered.variables[0].correction.resultValue : recovered.variables[0].correction.branches[0].resultValue, false);
  owner.type.kind = 'choice';
  owner.options = [{ value: 'false', labelZh: '甲', labelEn: 'A' }];
  answerCase.resultValue = 'false';
  valid(pendingDerived);
  answerCase.resultValue = 'removed';
  assert.equal(validate(pendingDerived, context).ok, false,
    'Pending legacy and branched choice cases must both reject a removed option unless rebinding.');
  owner.correction.needsRebind = true;
  valid(pendingDerived);
}
const actualReduce = new Function('document', 'return ' + logic.slice(0, logic.indexOf("  const source = JSON.parse($('source-data').textContent);")) + 'return reduce;})();')({
  getElementById: () => ({ textContent: JSON.stringify({ variables: [], sourceUnits: [] }) })
});
for (const change of ['options', 'type']) {
  const editedDerived = structuredClone(derived), owner = editedDerived.state.variables[0];
  editedDerived.state.variables.push(variable('independent-input'));
  owner.editTargets = [];
  owner.type.kind = 'choice';
  owner.options = [{ value: 'old', labelZh: '旧', labelEn: 'Old' }];
  owner.correction.branches.forEach(branch => {
    branch.clauses = [{ inputId: 'independent-input', operator: 'yes', value: '' }];
    branch.resultValue = 'old';
  });
  valid(editedDerived);
  const values = { type: { ...owner.type }, role: { ...owner.role }, options: [...owner.options] };
  if (change === 'options') values.options = [{ value: 'new', labelZh: '新', labelEn: 'New' }];
  else values.type = { kind: 'boolean', zh: '是非', en: 'Boolean' };
  editedDerived.state = actualReduce(editedDerived.state, { type: 'EDIT', id: owner.id, values, at: 'now' });
  const changedOwner = editedDerived.state.variables[0];
  assert.equal(changedOwner.correction.needsRebind, true,
    'Editing a derived question invalidates its own prior result even when its clauses reference another question.');
  assert.equal(changedOwner.status, 'help');
  assert.equal(changedOwner.correction.branches[0].resultValue, 'old', 'The old result remains as recoverable review history.');
  valid(editedDerived);
}
for (const pack of [rule, split]) {
  const reviewed = structuredClone(pack);
  reviewed.state.variables[0].status = 'confirmed';
  reviewed.state.variables[0].editTargets = [];
  valid(reviewed);
}
for (const [name, mutate] of [
  ['missing source', item => { item.missingSource = true; }],
  ['no explicit source', item => { item.sourceAnchors = []; }],
  ['derived without conditions', item => { item.role.kind = 'derived'; delete item.correction; item.editTargets = []; }],
  ['undecided action', item => { item.editTargets[0].action = 'check'; }],
  ['fill without content', item => { Object.assign(item.editTargets[0], { action: 'fill', valueSourceId: '', replacementEn: '' }); }],
  ['replace without content', item => { Object.assign(item.editTargets[0], { action: 'replace', valueSourceId: '', replacementEn: ' ' }); }],
  ['no rule cases', item => { item.correction.branches = []; item.editTargets = []; }],
  ['unknown rule case', item => { item.correction.branches[0].mode = 'unknown'; }],
  ['empty rule case', item => { item.correction.branches[0].clauses = []; }],
  ['missing Chinese effect', item => { item.correction.branches[0].effectZh = ''; }],
  ['missing English effect', item => { delete item.correction.branches[0].effectEn; }],
  ['unknown legacy rule', item => { item.correction = { kind: 'rule', mode: 'unknown', clauses: [{ inputId: 'a', operator: 'yes', value: '' }], effectZh: '保留', effectEn: 'Keep' }; item.editTargets = []; }],
  ['empty legacy rule', item => { item.correction = { kind: 'rule', mode: 'all', clauses: [], effectZh: '保留', effectEn: 'Keep' }; item.editTargets = []; }],
  ['unknown split', item => { item.correction = { kind: 'split', mode: 'unknown', inputIds: ['a'] }; item.editTargets = []; }],
  ['empty split', item => { item.correction = { kind: 'split', mode: 'all', inputIds: [] }; item.editTargets = []; }]
]) {
  const pack = structuredClone(confirmed);
  mutate(pack.state.variables[0]);
  assert.equal(validate(pack, context).ok, false, `Cannot confirm: ${name}`);
  for (const status of ['pending', 'changed', 'help', 'excluded']) {
    pack.state.variables[0].status = status;
    valid(pack);
    assert.equal(append(makePack().state, pack).variables.at(-1).status, status, `Keep unresolved status: ${name}`);
  }
}
for (const mutate of [
  pack => { pack.state.variables[0].correction.branches[1].id = 'adopt'; },
  pack => { pack.state.variables[0].correction.branches[0].clauses[0].inputId = 'missing'; },
  pack => { pack.state.variables[0].editTargets[0].branchId = 'missing'; },
  pack => { pack.state.variables[0].editTargets[0].replacementEn = {}; },
  pack => { pack.state.variables[0].editTargets[0].prefix = {}; },
  pack => { pack.state.variables[0].editTargets[0].suffix = null; },
  pack => { pack.state.variables[0].editTargets[0].valueSourceId = 'missing'; }
]) {
  const pack = structuredClone(branched); mutate(pack); assert.equal(validate(pack, context).ok, false);
}
const excludedValue = structuredClone(branched);
excludedValue.state.variables.push({ ...variable('other'), status: 'excluded' });
excludedValue.state.variables[0].editTargets[0].valueSourceId = 'other';
assert.equal(validate(excludedValue, context).ok, false);
excludedValue.state.variables[0].correction.needsRebind = true;
valid(excludedValue);
const targetRebind = structuredClone(branched);
targetRebind.state.variables[0].status = 'help';
Object.assign(targetRebind.state.variables[0].editTargets[0], { valueSourceId: 'removed-source', needsRebind: true });
valid(targetRebind);
const importedRebind = append(makePack().state, targetRebind).variables.at(-1);
assert.equal(importedRebind.editTargets[0].needsRebind, true);
assert.equal(importedRebind.editTargets[0].valueSourceId, 'removed-source');
assert.equal(importedRebind.status, 'help');
for (const mutate of [
  pack => { pack.state.variables[0].status = 'confirmed'; },
  pack => { pack.state.variables[0].editTargets[0].needsRebind = 'yes'; },
  pack => { pack.state.variables[0].editTargets[0].needsRebind = false; }
]) { const pack = structuredClone(targetRebind); mutate(pack); assert.equal(validate(pack, context).ok, false); }
const confirmedRuleRebind = structuredClone(branched);
confirmedRuleRebind.state.variables[0].status = 'confirmed';
confirmedRuleRebind.state.variables[0].correction.needsRebind = true;
assert.equal(validate(confirmedRuleRebind, context).ok, false);
confirmedRuleRebind.state.variables[0].status = 'excluded';
valid(confirmedRuleRebind);
const mixedValue = structuredClone(crossRule);
delete mixedValue.state.variables[0].correction;
mixedValue.state.variables[0].editTargets[0].valueSourceId = 'b';
assert.equal(validate(mixedValue, context).ok, false);
const groupValue = makePack();
groupValue.state.variables.push({ ...variable('group'), type: { kind: 'record' } });
groupValue.state.variables[0].editTargets[0].valueSourceId = 'group';
assert.equal(validate(groupValue, context).ok, false);

const pending = makePack();
pending.state.variables[0].sourceAnchors = [];
pending.state.variables[0].editTargets = [];
pending.state.variables[0].originId = 'historical-origin';
pending.state.variables[0].replacedBy = ['historical-child'];
pending.state.variables[0].correction = { kind: 'rule', mode: 'unknown', inputIds: ['historical-input'], clauses: [{ inputId: 'historical-input', operator: 'known', value: '' }], needsRebind: true, effectZh: '待重绑', effectEn: 'Needs rebinding' };
valid(pending);
assert.equal(pending.state.variables[0].status, 'pending');
const excluded = structuredClone(pending);
excluded.state.variables[0].status = 'excluded';
excluded.state.variables[0].correction.needsRebind = false;
valid(excluded);
const active = structuredClone(pending);
active.state.variables[0].correction.needsRebind = false;
assert.equal(validate(active, context).ok, false);

const current = makePack().state;
current.variables.push(variable('import-1'));
current.variables.push(variable('b'));
current.sourceReviews.note1 = true;
const incoming = makePack();
incoming.state.variables = [
  { ...variable('a'), type: { kind: 'record' }, definitionKind: 'added', relatedInputIds: ['b'], replacedBy: ['b'], originId: 'b', correction: { kind: 'split', mode: 'all', inputIds: ['b'], clauses: [{ inputId: 'b', operator: 'yes', value: '' }], needsRebind: false } },
  { ...variable('b'), parentId: 'a', originId: 'historic-unknown' }
];
incoming.state.sourceReviews.note2 = true;
valid(incoming);
const originalCurrent = structuredClone(current);
const originalIncoming = structuredClone(incoming);
const merged = append(current, incoming);
assert.deepEqual(current, originalCurrent);
assert.deepEqual(incoming, originalIncoming);
assert.equal(merged.variables.length, 5);
assert.equal(new Set(merged.variables.map(item => item.id)).size, 5);
const importedParent = merged.variables[3];
const importedChild = merged.variables[4];
assert.notEqual(importedParent.id, 'a');
assert.notEqual(importedChild.id, 'b');
assert.equal(importedChild.parentId, importedParent.id);
assert.deepEqual(importedParent.relatedInputIds, [importedChild.id]);
assert.deepEqual(importedParent.replacedBy, [importedChild.id]);
assert.equal(importedParent.originId, importedChild.id);
assert.deepEqual(importedParent.correction.inputIds, [importedChild.id]);
assert.equal(importedParent.correction.clauses[0].inputId, importedChild.id);
assert.equal(importedChild.originId, 'historic-unknown');
assert.equal(merged.events.length, 2);
assert.deepEqual(merged.sourceReviews, { note1: true, note2: true });
assert.equal(importedParent.status, 'pending');
assert.ok(merged.nextId > current.nextId);
valid({ ...incoming, state: merged });
const appendedBranches = append(current, branched), importedBranchItem = appendedBranches.variables[current.variables.length];
assert.notEqual(importedBranchItem.id, 'a');
assert.equal(importedBranchItem.correction.branches[0].clauses[0].inputId, importedBranchItem.id);
assert.equal(importedBranchItem.correction.branches[1].clauses[0].inputId, importedBranchItem.id);
assert.equal(importedBranchItem.editTargets[0].valueSourceId, importedBranchItem.id);
assert.equal(importedBranchItem.correction.branches[0].id, 'adopt');
assert.equal(importedBranchItem.editTargets[0].branchId, 'adopt');
assert.equal(importedBranchItem.editTargets[0].replacementEn, 'New text');
assert.equal(importedBranchItem.editTargets[0].prefix, 'Section ');
assert.equal(importedBranchItem.editTargets[0].suffix, ':-');
assert.equal(importedBranchItem.status, 'pending');
assert.equal(Object.hasOwn(importedBranchItem.correction, 'clauses'), false);
valid({ ...branched, state: appendedBranches });
for (const pack of [minimalAdd, rule, split, group]) {
  const before = structuredClone(pack);
  const appended = append(current, pack);
  assert.deepEqual(pack, before);
  valid({ ...pack, state: appended });
  const item = appended.variables[current.variables.length];
  if (!Object.hasOwn(pack.state.variables[0], 'editTargets')) assert.equal(Object.hasOwn(item, 'editTargets'), false);
  if (item.correction && !Object.hasOwn(pack.state.variables[0].correction, 'inputIds')) assert.equal(Object.hasOwn(item.correction, 'inputIds'), false);
  if (item.correction && !Object.hasOwn(pack.state.variables[0].correction, 'clauses')) assert.equal(Object.hasOwn(item.correction, 'clauses'), false);
  assert.equal(item.status, 'pending');
}
const vm = require('node:vm');
const cut = logic.indexOf("  const source = JSON.parse($('source-data').textContent);");
assert.ok(cut > 0);
const reducerContext = vm.createContext({ structuredClone, document: { getElementById: () => ({ textContent: JSON.stringify({ variables: [], sourceUnits: [] }) }) } });
vm.runInContext(logic.slice(0, cut) + '\nglobalThis.api = {initial, reduce};})();', reducerContext);
const reducer = reducerContext.api;
const step = (state, action) => reducer.reduce(state, { ...action, at: 'synthetic' });
const actualAdd = state => step(state, { type: 'ADD', docId: 'ntt', anchor: structuredClone(source), reason: 'Synthetic source', values: {
  nameZh: '真实形状', nameEn: 'Actual shape', questionZh: '真实形状', questionEn: 'Actual shape',
  type: { kind: 'boolean', zh: '是非', en: 'Boolean' }, role: { kind: 'input', zh: '资料', en: 'Input' }
} });
let actualState = actualAdd(actualAdd(step(reducer.initial(), { type: 'CLEAR', guidanceMode: 'minimum' })));
actualState = step(actualState, { type: 'RULE', id: 'review-1', correction: { kind: 'rule', mode: 'all', clauses: [{ inputId: 'review-2', operator: 'yes', value: '' }], effectZh: '保留', effectEn: 'Keep' } });
actualState = step(actualState, { type: 'SPLIT', id: 'review-2', mode: 'all', items: [
  { nameZh: '甲', nameEn: 'A', type: { kind: 'boolean', zh: '是非', en: 'Boolean' }, sourceAnchors: [structuredClone(source)] },
  { nameZh: '乙', nameEn: 'B', type: { kind: 'boolean', zh: '是非', en: 'Boolean' }, sourceAnchors: [structuredClone(source)] }
] });
const actualPack = { ...makePack(), state: actualState };
valid(actualPack);
valid({ ...actualPack, state: append(actualState, actualPack) });
assert.equal(actualState.variables[1].status, 'excluded');
assert.equal(actualState.variables[0].correction.needsRebind, true);
assert.equal(actualState.variables[0].status, 'help');
let branchSplitState=actualAdd(step(reducer.initial(),{type:'CLEAR',guidanceMode:'minimum'}));
branchSplitState=step(branchSplitState,{type:'RULE',id:'review-1',correction:{kind:'rule',branches:[{id:'keep',mode:'all',clauses:[{inputId:'review-1',operator:'yes',value:''}],effectZh:'保留',effectEn:'Keep'}]}});
branchSplitState=step(branchSplitState,{type:'TARGETS',id:'review-1',targets:[{...structuredClone(source),action:'fill',branchId:'keep',valueSourceId:'review-1'}]});
const excludedSelf=step(branchSplitState,{type:'EXCLUDE',id:'review-1',reason:'Synthetic correction'});
assert.equal(excludedSelf.variables[0].status,'excluded','A self-condition must not reactivate an excluded item.');
valid({...makePack(),state:excludedSelf});
branchSplitState=step(branchSplitState,{type:'SPLIT',id:'review-1',mode:'all',items:[
  {nameZh:'甲',nameEn:'A',sourceAnchors:[structuredClone(source)]},
  {nameZh:'乙',nameEn:'B',sourceAnchors:[structuredClone(source)]}
]});
assert.equal(branchSplitState.variables[1].editTargets[0].needsRebind,true);
valid({...makePack(),state:branchSplitState});
const joinedPack=makePack();
joinedPack.state.variables=[{...variable('rows'),type:{kind:'list',zh:'清单',en:'List'}},{...variable('leaf'),type:{kind:'text',zh:'文字',en:'Text'},parentId:'rows'},{...variable('owner'),status:'confirmed'}];
joinedPack.state.variables[2].editTargets=[{...structuredClone(source),action:'fill',valueSourceId:'leaf',valueMode:'join',listId:'rows',separator:', ',prefix:'Sections ',suffix:'.'}];
valid(joinedPack);
for(const separator of ['', '  /  ', '\n']){const pack=structuredClone(joinedPack);pack.state.variables[2].editTargets[0].separator=separator;valid(pack);}
for(const change of [{listId:'leaf'},{listId:'missing'},{valueSourceId:'rows'},{separator:null},{separator:7},{valueMode:'execute'},{action:'delete'},{valueMode:'single'}]){
  const pack=structuredClone(joinedPack);Object.assign(pack.state.variables[2].editTargets[0],change);assert.equal(validate(pack,context).ok,false,JSON.stringify(change));
}
for(const kind of ['record','list','multiselect']){const pack=structuredClone(joinedPack);pack.state.variables[1].type.kind=kind;assert.equal(validate(pack,context).ok,false,'Joins require scalar leaf answers.');}
const wrongList=structuredClone(joinedPack);wrongList.state.variables.push({...variable('other-rows'),type:{kind:'list'}});wrongList.state.variables[2].editTargets[0].listId='other-rows';
assert.equal(validate(wrongList,context).ok,false,'An existing but unrelated list must not bind a join.');
const crossJoin=structuredClone(joinedPack);crossJoin.state.variables[0].docIds.push('scc-word');crossJoin.state.variables[2].docIds.push('scc-pdf');
assert.equal(validate(crossJoin,context).ok,false,'Parent ancestry preserves SCC edition isolation.');
const recoveryJoin=step(joinedPack.state,{type:'EDIT',id:'rows',values:{type:{kind:'record',zh:'组',en:'Record'}}});
assert.equal(recoveryJoin.variables[2].status,'help');assert.equal(recoveryJoin.variables[2].editTargets[0].needsRebind,true);
valid({...joinedPack,state:recoveryJoin});
const confirmedStaleJoin=structuredClone(recoveryJoin);confirmedStaleJoin.variables[2].status='confirmed';
assert.equal(validate({...joinedPack,state:confirmedStaleJoin},context).ok,false,'A historical join remains saveable but cannot be confirmed.');
const movedJoin=step(joinedPack.state,{type:'EDIT',id:'leaf',values:{parentId:null}});
valid({...joinedPack,state:movedJoin});assert.equal(movedJoin.variables[2].editTargets[0].needsRebind,true);
const appendedJoin=append(joinedPack.state,joinedPack),joinedIncoming=appendedJoin.variables.slice(3);
assert.notEqual(joinedIncoming[0].id,'rows');assert.notEqual(joinedIncoming[1].id,'leaf');
assert.equal(joinedIncoming[1].parentId,joinedIncoming[0].id);
assert.equal(joinedIncoming[2].editTargets[0].listId,joinedIncoming[0].id);
assert.equal(joinedIncoming[2].editTargets[0].valueSourceId,joinedIncoming[1].id);
assert.equal(joinedIncoming[2].editTargets[0].separator,', ');assert.equal(joinedIncoming[2].editTargets[0].suffix,'.');
valid({...joinedPack,state:appendedJoin});valid({...joinedPack,state:append(recoveryJoin,{...joinedPack,state:recoveryJoin})});
console.log('review-pack: synthetic validation and append checks passed, including scalar/list joins, historical recovery, SCC isolation and colliding join IDs');

const ancestorPack=makePack();
ancestorPack.state.variables=[
  {...variable('word-group'),type:{kind:'record'},docIds:['ntt','scc-word']},
  {...variable('pdf-group'),type:{kind:'record'},docIds:['ntt','scc-pdf']},
  {...variable('input'),parentId:'word-group'},
  {...variable('owner'),parentId:'pdf-group'},
  {...variable('word-list'),parentId:'word-group',type:{kind:'list'}}
];
valid(ancestorPack);
const ancestorRule={kind:'rule',branches:[{id:'case-1',mode:'all',clauses:[{inputId:'input',operator:'yes',value:''}],effectZh:'保留',effectEn:'Keep'}]};
const blockedRule=step(ancestorPack.state,{type:'RULE',id:'owner',correction:ancestorRule});
assert.equal(blockedRule,ancestorPack.state,'Cross-edition ancestry cannot introduce a rule that makes the current package unsaveable.');
valid({...ancestorPack,state:blockedRule});
const invalidAncestor=structuredClone(ancestorPack);invalidAncestor.state.variables[3].correction=structuredClone(ancestorRule);
assert.equal(validate(invalidAncestor,context).ok,false,'The package validator independently rejects the original failure case.');
invalidAncestor.state.variables[3].correction.needsRebind=true;
valid(invalidAncestor);
const invalidConfirmed=structuredClone(invalidAncestor);invalidConfirmed.state.variables[3].status='confirmed';
assert.equal(validate(invalidConfirmed,context).ok,false,'A retained historical condition cannot be confirmed before rebinding.');
const compatibleAncestor=structuredClone(ancestorPack);compatibleAncestor.state.variables[3].parentId='word-group';
const allowedRule=step(compatibleAncestor.state,{type:'RULE',id:'owner',correction:ancestorRule});
assert.equal(allowedRule.variables[3].status,'changed');valid({...compatibleAncestor,state:allowedRule});
valid({...compatibleAncestor,state:append(allowedRule,{...compatibleAncestor,state:allowedRule})});
console.log('review-pack: ancestor-isolated rules stay saveable, historical references stay recoverable, and compatible rules append safely');
