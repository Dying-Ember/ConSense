'use strict';
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const logic = readFileSync(require.resolve('./prototype.logic.js'), 'utf8');
const between = (start, end) => {
  const from = logic.indexOf(start), to = logic.indexOf(end, from);
  assert(from >= 0 && to > from, `The real UI function must exist: ${start}`);
  return logic.slice(from, to);
};
const declaration = name => {
  const line = logic.split('\n').find(value => value.startsWith(`  const ${name}`));
  assert(line, `The real UI declaration must exist: ${name}`);
  return line;
};
const functions = [
  declaration('ruleBranches ='), declaration('operators='), declaration('actionNames='),
  between('  function renderConditions(v){', '  const actionNames='),
  between('  function renderOverviewDetail(){', '  function overviewSource('),
  between('  function overviewScopeTargets(){', '  function renderOverviewScope(')
].join('\n');
// Exercise the actual rendering functions without claiming browser-layout coverage.
const loadUI = new Function('state', 'overviewFocus', 'overviewImpact', 'overviewActionId', 'overviewBusinessView', `
  const detail = { innerHTML: '' }, $ = () => detail;
  const bi = (zh, en) => String(zh) + ' | ' + String(en);
  const escape = value => String(value ?? ''), getName = id => state.variables.find(v => v.id === id) || {};
  const scopeNotice = () => '', overviewPast = edge => edge.referenceOnly;
  const window = { GraphOverview: { project: () => ({}), neighborhood: () => ({ edges: [] }) } };
  const renderOverviewMap = () => {}, overviewLinks = () => '', anchorsFor = () => [];
  const button = (action, zh, en, extra = '') => '<button data-action="' + action + '" ' + extra + '>' + bi(zh, en) + '</button>', targetMeaning = () => '', note = bi;
  const docNames = ids => ids.join(' / '), status = () => '', quote = escape, renderOverviewScope = () => '';
  ${functions}
  return { renderConditions, overviewScopeTargets, renderDetail: () => { renderOverviewDetail(); return detail.innerHTML; } };
`);
const variable = (id, extra = {}) => ({ id, nameZh: '测试事项', nameEn: 'Synthetic item', docIds: ['ntt'],
  type: { kind: 'boolean', zh: '是／否', en: 'Yes / No' }, role: { kind: 'input', zh: '项目资料', en: 'Project information' },
  status: 'changed', editTargets: [], ...extra });
const branch = mode => ({ id: 'case-1', mode, clauses: [{ inputId: 'selected', operator: 'yes', value: '' }],
  effectZh: '测试结果', effectEn: 'Synthetic effect' });
const item = variable('selected');
const state = { variables: [item] }, ui = loadUI(state, item.id, { downstreamIds: [] });
for (const [mode, chinese, english, absent] of [
  ['all', '同时满足以下条件', 'All conditions must hold', 'Any condition may hold'],
  ['any', '满足以下任一条件', 'Any condition may hold', 'All conditions must hold'],
  ['unknown', '这些条件如何共同作用尚未确认', 'How these conditions combine is not confirmed', 'Any condition may hold']
]) {
  item.correction = { kind: 'rule', branches: [branch(mode)] };
  const output = ui.renderConditions(item);
  assert(output.includes(chinese) && output.includes(english), `${mode} keeps its own bilingual meaning.`);
  assert(!output.includes(absent), `${mode} cannot silently become another combination.`);
  if (mode === 'unknown') assert(!output.includes('All conditions must hold'));
}
for (const [mode, expected] of [['all', 'Both answers must be Yes'], ['any', 'Either answer may be Yes'], ['unknown', 'How these combine is not yet confirmed']]) {
  item.correction = { kind: 'split', mode, inputIds: [item.id] };
  assert(ui.renderConditions(item).includes(expected), `Split ${mode} remains explicit.`);
}
item.correction = { kind: 'rule', branches: [branch('all')], otherwise: 'needs_review' };
let output = ui.renderDetail();
assert(output.includes('没有落入以上情况时，仍需人工核对，不能自动决定修改。'));
assert(output.includes('If none of these cases applies, manual review is still required.'));
delete item.correction.otherwise;
assert(!ui.renderDetail().includes('If none of these cases applies'), 'The fallback is read from the current rule.');
item.correction = null;
item.relations = [{ branches: [{ whenZh: '旧条件描述', whenEn: 'Earlier condition', resultZh: '旧结果描述', resultEn: 'Earlier effect' }] }];
output = ui.renderDetail();
assert(output.includes('以下是较早的文字描述，仅供参考，尚未形成明确条件。'));
assert(output.includes('The following earlier descriptions are references, not explicit conditions.'));
assert(output.includes('Earlier condition') && output.includes('Earlier effect'), 'Reference wording remains available.');

const first = { page: 1, branchId: 'case-a' }, samePassageOtherCase = { page: 1, branchId: 'case-b', needsRebind: true }, last = { page: 3 };
item.editTargets = [first, samePassageOtherCase];
state.variables.push(variable('downstream', { editTargets: [last] }), variable('retired', { status: 'excluded', editTargets: [{ page: 4 }] }),
  variable('unrelated', { editTargets: [{ page: 5 }] }));
const before = JSON.stringify(state);
const scopeUI = loadUI(state, item.id, { downstreamIds: ['downstream', 'retired', 'downstream', 'missing'] });
const scope = scopeUI.overviewScopeTargets();
assert.deepEqual(scope.map(entry => [entry.owner.id, entry.index]), [['selected', 0], ['selected', 1], ['downstream', 0]]);
assert.equal(scope[0].target, first);
assert.equal(scope[1].target, samePassageOtherCase, 'Different cases and stale targets are retained rather than collapsed.');
assert.equal(scope[2].target, last);
assert.equal(loadUI(state, item.id, null).overviewScopeTargets().length, 2, 'A selection without downstream links retains its own passages.');
scopeUI.renderDetail();
assert.equal(JSON.stringify(state), before, 'Rendering and scope projection never mutate review data.');

item.correction = { kind: 'rule', branches: [{ ...branch('all'), id: 'case-a' }, { ...branch('any'), id: 'case-b' }] };
first.docId = samePassageOtherCase.docId = 'ntt';
first.text = 'PASSAGE-OF-ANOTHER-CASE';
samePassageOtherCase.text = 'PASSAGE-OF-SELECTED-CASE';
const selectedAction = { id: 'action:selected:branch:1', ownerId: item.id, labelZh: '条件方案 2', labelEn: 'Conditional action 2',
  summaryZh: '仅在第二种情况下修改', summaryEn: 'Amend only in the second case', branchIndex: 1,
  branch: item.correction.branches[1], targets: [{ target: samePassageOtherCase, index: 1 }], needsReview: true };
const actionSnapshot = JSON.stringify(state), actionUI = loadUI(state, item.id, { downstreamIds: [] }, selectedAction.id, { actions: [selectedAction] });
output = actionUI.renderDetail();
assert(output.includes('Conditional action 2') && output.includes('Amend only in the second case'));
assert(output.includes('Any condition may hold'), 'A selected alternative case retains its actual combination.');
assert(output.includes('This instruction requires review.'), 'Unresolved instructions cannot appear ready for execution.');
assert(output.includes('data-action="overview-action-target" data-index="1"'), 'Selected action targets retain the owner target index for source navigation.');
assert(!output.includes('data-action="overview-action-target" data-index="0"'), 'A filtered target must never be renumbered to zero.');
assert(output.includes(samePassageOtherCase.text) && !output.includes(first.text), 'The selected instruction shows its own passages without borrowing another case.');
assert(output.includes('no changes have been selected using actual project answers'), 'Recorded instructions are distinguished from executed project amendments.');
assert.equal(JSON.stringify(state), actionSnapshot, 'Action selection never changes the saved graph.');
assert(!readFileSync(require.resolve('./prototype-shell.html'), 'utf8').includes('graph-group'), 'The graph shell does not reintroduce inferred theme containers.');

const documentChange = logic.split('\n').find(line => line.startsWith("  $('overview-document').onchange="));
assert(documentChange, 'The actual graph document change handler must exist.');
const loadNavigation = new Function(`
  let docId = 'ntt', page = 40, selectedId = 'earlier-selection', overviewAll = true, overviewSearch = '', overviewOpen = true;
  const elements = {}, $ = id => elements[id] ||= { setAttribute() {}, focus() {} };
  const original = { hidden: false }, document = { querySelector: () => original };
  let header, paper;
  const pages = () => Array(4), firstGuidancePage = () => 2, clearSelection = () => {};
  const renderHeader = () => { header = { docId, page }; }, renderPage = () => { paper = { docId, page }; };
  const showOverview = () => {};
  ${between('  function goPage(n,range){', '  function navigateVariable(')}
  ${between('  function closeOverview(){', '  function showOverview(')}
  ${documentChange}
  function clickFile(action, b) {
    ${between("    if(action==='overview-file'){", "    if(action==='overview-action-source')")}
  }
  return {
    select: value => $('overview-document').onchange({ target: { value } }),
    click: value => clickFile('overview-file', { dataset: { docScope: value } }),
    close: closeOverview,
    current: () => ({ docId, page, selectedId, header, paper, closed: !overviewOpen && !original.hidden })
  };
`);
for (const method of ['select', 'click']) {
  const navigation = loadNavigation();
  navigation[method]('scc-pdf');
  navigation.close();
  const current = navigation.current();
  assert.equal(current.selectedId, '', 'A file filter cannot retain an unrelated source selection.');
  assert.equal(current.page, 2, 'Changing files replaces a possibly out-of-range old page.');
  assert.deepEqual(current.header, { docId: 'scc-pdf', page: 2 });
  assert.deepEqual(current.paper, current.header, 'Closing the graph must reveal the same document and page used by word selection.');
  assert(current.closed);
}
console.log('PASS: actual UI condition wording, references, full scope, selected-case source indexes, no inferred groups and synchronized source navigation after file filtering.');
