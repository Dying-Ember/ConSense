'use strict';
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const reviewModule = { exports: {} }, graphModule = { exports: {} };
new Function('module', readFileSync(require.resolve('./review-pack.js'), 'utf8'))(reviewModule);
const ReviewPack = reviewModule.exports;
new Function('module', 'require', readFileSync(require.resolve('./template-graph.js'), 'utf8'))(graphModule, () => ReviewPack);
const Graph = graphModule.exports;
const ids = ['ntt', 'sct', 'scc-word', 'scc-pdf'];
const context = {
  sourceKey: 'synthetic-template-source',
  documents: Object.fromEntries(ids.map(id => [id, { pages: [{ words: [{ text: 'a' }, { text: 'b' }] }] }])),
  templateBindings: ids.map(id => ({ id, fileName: id + '.docx', format: id === 'scc-pdf' ? 'pdf' : 'docx', originalSha256: 'a'.repeat(64), visualSha256: 'b'.repeat(64), layoutSha256: 'c'.repeat(64) })),
  types: ['boolean', 'text', 'list', 'record', 'choice', 'multiselect', 'number', 'date'],
  statuses: ['pending', 'changed', 'confirmed', 'help', 'excluded'],
  sourceText: (words, chosen) => chosen.map(i => words[i].text).join(' ')
};
const source = { docId: 'ntt', page: 1, wordIds: [0, 1], start: 0, end: 2, text: 'a b' };
const variable = id => ({ id, definitionKind: 'added', nameZh: '测试事项', nameEn: 'Synthetic item',
  type: { kind: 'boolean', zh: '是／否', en: 'Yes / No' }, role: { kind: 'input', zh: '项目资料', en: 'Project information' },
  docIds: ['ntt'], status: 'changed', evidence: [{ docId: 'ntt', quote: 'a b' }], targets: [], relations: [],
  sourceAnchors: [structuredClone(source)], editTargets: [], sourceUnitIds: [], relatedInputIds: [] });
const list = { ...variable('list'), type: { kind: 'list', zh: '清单', en: 'List' } };
const leaf = { ...variable('leaf'), parentId: 'list', type: { kind: 'text', zh: '文字', en: 'Text' }, fixedValue: '001' };
const joined = { ...variable('joined'), correction: { kind: 'rule', branches: [{ id: 'case-1', scopeId: 'list', scopeMode: 'all', mode: 'all',
  clauses: [{ inputId: 'leaf', operator: 'notempty', value: '' }], effectZh: '合并填写', effectEn: 'Join all answers' }] },
  editTargets: [{ ...structuredClone(source), action: 'fill', branchId: 'case-1', valueSourceId: 'leaf', valueMode: 'join', listId: 'list', separator: ', ', prefix: '(', suffix: ').' }] };
const derived = { ...variable('derived'), role: { kind: 'derived', zh: '由其他答案决定', en: 'Determined by other answers' },
  correction: { kind: 'rule', branches: [{ id: 'case-1', mode: 'all', clauses: [{ inputId: 'switch', operator: 'no', value: '' }],
    resultValue: false, effectZh: '不采用', effectEn: 'Not adopted' }] } };
const state = { variables: [variable('switch'), list, leaf, joined, derived, { ...variable('old'), status: 'excluded', replacedBy: ['switch'] }],
  sourceReviews: { guidance1: false }, events: [{ zh: '保留历史', en: 'Keep history', details: [], time: 'now' }], nextId: 7, blank: true, guidanceMode: 'minimum' };
assert.equal(ReviewPack.validate({ kind: 'consense-review-v1', sourceKey: context.sourceKey, state }, context).ok, true);
const saved = Graph.create(state, context, { id: 'snapshot-1', name: '模板图谱 / Template graph', savedAt: '2026-10-17T01:00:00.000Z' });
const roundTrip = JSON.parse(JSON.stringify(saved));
assert.equal(Graph.validate(roundTrip, context).ok, true);
assert.deepEqual(Graph.toReview(roundTrip, context).state, state, 'Every field, historical item, pending state, typed false, join and source survives.');
roundTrip.graph.variables[0].nameZh = '独立副本';
assert.equal(state.variables[0].nameZh, '测试事项', 'Graph data cannot mutate live review state.');
const rejects = mutate => { const bad = structuredClone(saved); mutate(bad); assert.equal(Graph.validate(bad, context).ok, false); assert.throws(() => Graph.toReview(bad, context)); };
rejects(g => { g.templates[0].originalSha256 = 'd'.repeat(64); });
rejects(g => { g.templates[0].visualSha256 = 'd'.repeat(64); });
rejects(g => { g.templates[0].layoutSha256 = 'd'.repeat(64); });
rejects(g => { g.templates[0].format = 'pdf'; });
rejects(g => { g.templates.pop(); });
rejects(g => { g.templates[1] = structuredClone(g.templates[0]); });
rejects(g => { g.sourceKey = 'different-word-membership'; });
rejects(g => { g.name = ' '; });
rejects(g => { g.savedAt = 'not-a-date'; });
rejects(g => { g.graph.variables[3].editTargets[0].valueSourceId = 'missing'; });
rejects(g => { g.graph.variables[4].correction.branches[0].resultValue = 'false'; });
rejects(g => { g.graph.variables[0].docIds.push('scc-word', 'scc-pdf'); });
rejects(g => { g.graph.variables[0].sourceAnchors[0].text = 'wrong source wording'; });
const copies = Graph.toReview(saved, context); copies.state.variables[0].nameZh = '另一个副本';
assert.equal(saved.graph.variables[0].nameZh, '测试事项');
const logic = readFileSync(require.resolve('./prototype.logic.js'), 'utf8');
const integrationBody = logic.slice(logic.indexOf('  function integrationSummary(){'), logic.indexOf('  // shortcut: the preview demonstrates manual correction;'));
const previewBody = logic.slice(logic.indexOf('  function previewGraph(saved){'), logic.indexOf('  async function readGraph(id){'));
const restoreBody = logic.slice(logic.indexOf('  function useGraph(){'), logic.indexOf('  function savePack(){'));
assert.ok(integrationBody && previewBody && restoreBody, 'Integration and existing graph restoration boundaries must remain identifiable.');
const pureBoundary = logic.indexOf("  const source = JSON.parse($('source-data').textContent);");
assert.ok(pureBoundary > 0);
const reviewState = new Function('document', 'return ' + logic.slice(0, pureBoundary) + 'return {initial,reduce};})();')({
  getElementById: id => { assert.equal(id, 'prototype-data'); return { textContent: JSON.stringify({ variables: state.variables, sourceUnits: [] }) }; }
});
const makeBridge = new Function('Graph', 'state', 'context', 'reviewState', `
  let receive,pendingGraph=null,draft=null,undo=[],selectedId='',docId='ntt',page=1,overviewRange=null,graphName='',search='';
  const sent=[],calls=[],copy=structuredClone;
  const initial=reviewState.initial,reduce=reviewState.reduce;
  const data={reviewSourceKey:context.sourceKey,templateBindings:context.templateBindings};
  const window={parent:{postMessage:(message,origin)=>sent.push({message,origin})},
    addEventListener:(type,handler)=>{if(type==='message')receive=handler;},
    TemplateGraph:Object.fromEntries(['validate','toReview','create'].map(method=>[method,(...args)=>{calls.push(method);return Graph[method](...args);}] ))};
  ${logic.slice(logic.indexOf('  const integrationOrigin='), logic.indexOf('  const current='))}
  const graphContext=()=>context,$=()=>({}),noop=()=>'',openPanel=noop,title=noop,graphTime=noop,note=noop,
    bi=noop,graphDownload=noop,button=noop,clearSelection=noop,goPage=noop,firstGuidancePage=()=>1,
    showCatalog=()=>integrationChanged(),notify=noop,selectQuestion=id=>{selectedId=id;};
  ${previewBody}
  ${restoreBody}
  ${integrationBody}
  integrationChanged();
  return {sent,calls,parent:window.parent,origin:integrationOrigin,
    receive:(data,origin=integrationOrigin,source=window.parent)=>receive({data,origin,source}),
    current:()=>state,selected:()=>selectedId,editing:value=>{draft=value;},enterEdit:()=>integrationChanged(true),
    changed:value=>{state=value;integrationChanged();}};
`);
const bridge = makeBridge(Graph, structuredClone(state), context, reviewState);
const message = (type, details = {}) => ({ type: 'consense:review-prototype:' + type, ...details });
bridge.changed(structuredClone(state));
bridge.enterEdit();
assert.equal(bridge.sent.length, 0, 'Initial rendering and pre-load state changes must not report user changes.');
bridge.receive(message('load', { graph: saved }), 'https://unrelated.example');
bridge.receive(message('load', { graph: saved }), bridge.origin, {});
assert.equal(bridge.sent.length, 0, 'An unrelated origin or window cannot read, replace or trigger review state.');
assert.deepEqual(bridge.calls, []);
const wrongBinding = structuredClone(saved); wrongBinding.templates[0].originalSha256 = 'd'.repeat(64);
bridge.receive(message('load', { graph: wrongBinding }));
assert.equal(bridge.sent.at(-1).message.type, message('error').type);
assert.deepEqual(bridge.current(), state, 'A mismatched template must not alter current work or prevent a later valid load.');
bridge.calls.length = 0;
bridge.receive(message('load', { graph: saved }));
assert.deepEqual(bridge.calls, ['validate', 'toReview'], 'A parent load uses the existing real graph validation and restoration.');
assert.deepEqual(bridge.current(), state);
assert.deepEqual(bridge.sent.map(entry => entry.message.type), [message('error').type, message('loaded').type], 'Restoring a graph acknowledges loading without reporting an edit.');
bridge.editing({ kind: 'edit' });
const countBeforeDraft = bridge.sent.length;
bridge.receive(message('request-snapshot'));
assert.equal(bridge.sent.length, countBeforeDraft + 1);
assert.equal(bridge.sent.at(-1).message.type, message('error').type, 'An in-progress edit produces no snapshot.');
bridge.editing(null);
bridge.enterEdit();
assert.equal(bridge.sent.at(-1).message.type, message('changed').type);
assert.equal(bridge.sent.at(-1).message.inProgress, true, 'Entering an unsaved form reports pending work even when the saved state reference is unchanged.');
const editedState = structuredClone(state); editedState.variables[0].nameZh = '人工核对后的名称';
bridge.changed(editedState);
assert.equal(bridge.sent.at(-1).message.inProgress, false);
const changedCount = bridge.sent.length;
bridge.changed(editedState);
assert.equal(bridge.sent.length, changedCount, 'Unchanged state must not emit duplicate change notices.');
assert.equal(bridge.sent.at(-1).message.type, message('changed').type);
bridge.receive(message('request-snapshot'));
const snapshot = bridge.sent.at(-1);
assert.equal(snapshot.origin, bridge.origin);
assert.equal(snapshot.message.type, message('snapshot').type);
assert.deepEqual(snapshot.message.pack, { kind: 'consense-review-v1', sourceKey: context.sourceKey, state: editedState });
assert.deepEqual(snapshot.message.templates, context.templateBindings);
snapshot.message.pack.state.variables[0].nameZh = '外部修改'; snapshot.message.templates[0].originalSha256 = 'f'.repeat(64);
assert.deepEqual(bridge.current(), editedState, 'Snapshot payloads cannot mutate the active review.');
assert.equal(context.templateBindings[0].originalSha256, 'a'.repeat(64));
bridge.receive(message('load', { graph: saved }));
assert.equal(bridge.sent.at(-1).message.type, message('error').type);
assert.deepEqual(bridge.current(), editedState, 'A repeated parent load cannot overwrite manual work.');
bridge.receive(message('focus', { id: 'missing' }));
assert.equal(bridge.sent.at(-1).message.type, message('error').type);
bridge.receive(message('focus', { id: 'switch' }));
assert.equal(bridge.selected(), 'switch');
const blankBridge = makeBridge(Graph, structuredClone(state), context, reviewState);
const originalBindings = structuredClone(context.templateBindings);
blankBridge.receive(message('load', { graph: null }));
assert.deepEqual(blankBridge.sent.map(entry => entry.message.type), [message('loaded').type]);
assert.deepEqual(blankBridge.sent[0].message.summary, { records: 0, active: 0, unconfirmed: 0 });
assert.deepEqual(blankBridge.current().variables, [], 'A new review starts with no inherited variable pool.');
assert.equal(blankBridge.current().blank, true);
assert.equal(blankBridge.current().guidanceMode, 'minimum', 'Starting empty keeps source guidance recognition available.');
blankBridge.receive(message('request-snapshot'));
assert.equal(blankBridge.sent.at(-1).message.type, message('snapshot').type);
assert.deepEqual(blankBridge.sent.at(-1).message.pack.state, blankBridge.current());
assert.deepEqual(blankBridge.sent.at(-1).message.templates, originalBindings, 'Empty reviews retain exact original, visual and layout bindings.');
const saveHandler = logic.slice(logic.indexOf('  async function saveGraph(b){'), logic.indexOf('  function previewGraph(saved){'));
const raceCheck = new Function('Graph', 'state', 'context', `return (async()=>{
  let panel='graphs',failedGraph=null,graphName='',rejectSave;
  const notifications=[];
  const $=()=>({querySelector:()=>({value:'保存失败场景'})});
  const window={TemplateGraph:Graph},crypto={randomUUID:()=> 'snapshot-async'};
  const graphContext=()=>context,notify=(...message)=>notifications.push(message);
  const graphStore=()=>({save:()=>new Promise((resolve,reject)=>{rejectSave=reject;})});
  ${saveHandler}
  const saving=saveGraph({disabled:false,isConnected:false});
  panel='catalog';rejectSave(Error('quota failure after leaving the graph panel'));await saving;
  return {notifications,failedGraph};
})()`);
raceCheck(Graph, state, context).then(result => {
  assert.equal(result.notifications.length, 1, 'A late storage failure must remain visible after leaving the graph panel.');
  assert.match(result.notifications[0][0], /未存入/);
  assert.deepEqual(result.failedGraph.graph, state, 'The exact failed snapshot remains available for a backup after reopening.');
  console.log('PASS: template graph round trip, exact binding, source text, SCC isolation, typed results, joins, history, parent-window bridge and late storage-failure recovery.');
}).catch(error => { console.error(error); process.exitCode = 1; });
