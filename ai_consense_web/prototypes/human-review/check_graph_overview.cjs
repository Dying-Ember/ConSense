'use strict';
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const overviewModule = { exports: {} };
new Function('module', readFileSync(require.resolve('./graph-overview.js'), 'utf8'))(overviewModule);
const Overview = overviewModule.exports;
const variable = (id, extra = {}) => ({ id, nameZh: '测试事项', nameEn: 'Synthetic item', docIds: ['ntt'], status: 'changed', ...extra });
const state = { variables: [
  variable('switch'), variable('list', { type: { kind: 'list' }, correction: { kind: 'rule', branches: [
    { id: 'each-detail', mode: 'all', scopeId: 'list', scopeMode: 'each', clauses: [{ inputId: 'detail', operator: 'known' }] }
  ] } }),
  variable('record', { parentId: 'list', type: { kind: 'record' } }), variable('detail', { parentId: 'record' }),
  variable('answer', { correction: { kind: 'rule', branches: [
    { id: 'first', scopeId: 'list', scopeMode: 'all', mode: 'all', resultValue: false,
      clauses: [{ inputId: 'switch', operator: 'no', value: '' }, { inputId: 'detail', operator: 'eq', value: 'x' }] },
    { id: 'second', mode: 'any', clauses: [{ inputId: 'switch', operator: 'yes', value: '' }] }
  ] }, editTargets: [{ docId: 'ntt', branchId: 'first', valueSourceId: 'detail', valueMode: 'join', listId: 'list', separator: '; ' }] }),
  variable('referenced', { relatedInputIds: ['switch'], relations: [{ branches: [{ whenEn: 'Do not infer dependencies from prose.', resultEn: 'Legacy reference' }] }] }),
  variable('legacy', { status: 'excluded', replacedBy: ['switch'] }),
  variable('new', { originId: 'legacy', correction: { kind: 'split', mode: 'unknown', needsRebind: true, inputIds: ['new', 'switch'] } }),
  variable('stale', { correction: { kind: 'rule', mode: 'all', needsRebind: true, clauses: [{ inputId: 'gone', operator: 'known' }] },
    editTargets: [{ valueSourceId: 'legacy', needsRebind: true }] }),
  variable('unknown-target', { correction: { kind: 'rule', branches: [
    { id: 'uncertain', mode: 'unknown', clauses: [{ inputId: 'switch', operator: 'yes' }] }
  ] }, editTargets: [
    { branchId: 'uncertain', valueSourceId: 'detail', valueMode: 'join', listId: 'list' },
    { branchId: 'missing-case', valueSourceId: 'switch' }
  ] }),
  variable('word', { docIds: ['scc-word'] }), variable('pdf', { docIds: ['scc-pdf'], parentId: 'word' }),
  variable('cycle-a', { parentId: 'cycle-b' }), variable('cycle-b', { parentId: 'cycle-a' }),
  variable('condition-cycle-a', { correction: { kind: 'rule', mode: 'all', clauses: [{ inputId: 'condition-cycle-b', operator: 'yes' }] } }),
  variable('condition-cycle-b', { correction: { kind: 'rule', mode: 'all', clauses: [{ inputId: 'condition-cycle-a', operator: 'no' }] } }),
  variable('transitive', { relatedInputIds: ['referenced'] })
] };
const before = JSON.stringify(state);
const graph = Overview.project(state);
assert.equal(JSON.stringify(state), before, 'Projection never edits review data.');
assert.equal(graph.nodes.length, state.variables.length);
assert.equal(graph.nodes[0], state.variables[0], 'The overview reuses the authoritative variable record.');
assert.equal(graph.edges.filter(edge => edge.kind === 'condition' && edge.ownerId === 'answer').length, 3);
assert.equal(graph.edges.find(edge => edge.kind === 'condition' && edge.ownerId === 'answer').mode, 'all');
assert.equal(graph.edges.find(edge => edge.kind === 'scope' && edge.ownerId === 'answer').scopeMode, 'all');
assert.equal(graph.edges.find(edge => edge.kind === 'join').separator, '; ');
assert.equal(graph.edges.find(edge => edge.kind === 'value' && edge.ownerId === 'answer').valueMode, 'join');
assert.equal(graph.edges.find(edge => edge.kind === 'condition' && edge.from === 'detail' && edge.to === 'list').cyclic, false, 'Membership and list scope do not create a circular derivation.');
assert.equal(graph.edges.find(edge => edge.ownerId === 'referenced').kind, 'reference');
assert.equal(graph.edges.find(edge => edge.ownerId === 'referenced').referenceOnly, true);
assert.equal(graph.edges.filter(edge => edge.ownerId === 'referenced').length, 1, 'Legacy prose is never promoted to an executable dependency.');
assert.equal(graph.edges.find(edge => edge.kind === 'parent' && edge.to === 'detail').from, 'record');
assert.equal(graph.edges.find(edge => edge.kind === 'split-origin').excluded, true);
assert.equal(graph.edges.find(edge => edge.kind === 'replaced-by').referenceOnly, true);
assert.equal(graph.edges.find(edge => edge.ownerId === 'new' && edge.kind === 'condition').unresolved, true);
assert.equal(graph.edges.find(edge => edge.ownerId === 'stale' && edge.kind === 'condition').missing, true);
assert.equal(graph.edges.find(edge => edge.ownerId === 'stale' && edge.kind === 'value').needsRebind, true);
assert.equal(graph.edges.find(edge => edge.ownerId === 'pdf').incompatible, true, 'Inherited document ownership cannot mix SCC versions.');
assert(graph.warnings.some(warning => warning.kind === 'parent-cycle'));
assert.equal(graph.edges.find(edge => edge.ownerId === 'condition-cycle-a').cyclic, true);
assert.equal(graph.edges.find(edge => edge.ownerId === 'condition-cycle-b').cyclic, true);
assert.equal(graph.edges.find(edge => edge.ownerId === 'new' && edge.from === edge.to).self, true);
assert.equal(graph.edges.find(edge => edge.ownerId === 'new' && edge.from === edge.to).cyclic, false, 'A question checking its own answer is not a circular derivation.');
assert(Overview.neighborhood(graph, 'condition-cycle-a').warnings.some(warning => warning.kind === 'cycle'));
const local = Overview.neighborhood(graph, 'switch', { docId: 'ntt' });
assert.equal(local.node.id, 'switch');
assert(local.nodes.some(node => node.id === 'answer'));
assert(!local.nodes.some(node => node.id === 'transitive'), 'The local view does not recursively expand links.');
assert.equal(Overview.neighborhood(graph, 'switch', { docId: 'sct' }).edges.length, 0);
assert.equal(Overview.neighborhood(graph, 'absent').node, null);
assert.equal(graph.nodes.find(node => node.id === 'answer').correction.branches[0].resultValue, false);
assert.throws(() => Overview.project({}), TypeError);
const switchImpact = Overview.impact(graph, 'switch');
assert(switchImpact.downstreamIds.includes('answer'));
assert(!switchImpact.downstreamIds.includes('referenced'), 'Reference links do not transmit impact.');
assert(!switchImpact.downstreamIds.includes('new'), 'Unknown split conditions do not transmit impact.');
assert.equal(Overview.impact(graph, 'record').downstreamIds.length, 0, 'Grouping alone is not a causal link.');
assert.equal(Overview.impact(graph, 'legacy').downstreamIds.length, 0, 'Historical links do not transmit impact.');
assert.equal(Overview.impact(graph, 'stale').upstreamIds.length, 0, 'Invalid and missing sources do not transmit impact.');
assert.equal(Overview.impact(graph, 'unknown-target').upstreamIds.length, 0, 'Unknown and missing branch assignments block target value/join impact.');
assert(graph.edges.filter(edge => edge.ownerId === 'unknown-target').every(edge => edge.unresolved));
const cycleImpact = Overview.impact(graph, 'condition-cycle-a');
assert.deepEqual(cycleImpact.downstreamIds, ['condition-cycle-b']);
assert.deepEqual(cycleImpact.upstreamIds, ['condition-cycle-b']);
assert.equal(cycleImpact.downstreamEdges.length, 2, 'A declared cycle remains visible and traversal terminates.');
const chained = { nodes: ['a', 'b', 'c', 'd', 'e'].map(id => variable(id)), edges: [
  { from: 'a', to: 'b', kind: 'condition' }, { from: 'b', to: 'c', kind: 'scope' },
  { from: 'c', to: 'd', kind: 'value' }, { from: 'd', to: 'e', kind: 'join' }
] };
assert.deepEqual(Overview.impact(chained, 'a').downstreamIds, ['b', 'c', 'd', 'e']);
assert.deepEqual(Overview.impact(chained, 'e').upstreamIds, ['a', 'b', 'c', 'd']);
for (const flag of ['needsRebind', 'excluded', 'missing', 'incompatible', 'unresolved', 'referenceOnly']) {
  const blocked = { nodes: [variable('source'), variable('result')], edges: [{ from: 'source', to: 'result', kind: 'value', [flag]: true }] };
  assert.equal(Overview.impact(blocked, 'source').downstreamIds.length, 0, `${flag} links cannot transmit impact.`);
}
const selfGraph = { nodes: [variable('self')], edges: [{ from: 'self', to: 'self', kind: 'condition', self: true }] };
assert.equal(Overview.impact(selfGraph, 'self').downstreamIds.length, 0);
assert.equal(Overview.impact(selfGraph, 'self').downstreamEdges.length, 1, 'Self links remain drawable without expanding traversal.');
const fullLayout = Overview.layout(graph, { includeExcluded: true });
assert.equal(fullLayout.nodes.length, state.variables.length);
assert.equal(Overview.layout(graph).nodes.length, state.variables.filter(node => node.status !== 'excluded').length);
assert(fullLayout.edges.some(edge => edge.missing), 'Missing endpoints remain explicit and are never fabricated as nodes.');
assert(!fullLayout.nodes.some(item => item.node.id === 'gone'));
assert.deepEqual(Overview.layout(graph, { includeExcluded: true }), fullLayout, 'Grid positions are deterministic.');
const crossGraph = Overview.project({ variables: [variable('ntt-source'), variable('sct-target', { docIds: ['sct'], editTargets: [{ valueSourceId: 'ntt-source' }] })] });
const crossLayout = Overview.layout(crossGraph, { docId: 'ntt' });
assert.equal(crossLayout.nodes.length, 2, 'A cross-file endpoint remains visible when filtering one template.');
assert.equal(crossLayout.nodes.find(item => item.node.id === 'sct-target').contextual, true);
assert.equal(crossLayout.edges.length, 1);
assert.equal(Overview.layout({ nodes: [], edges: [] }).nodes.length, 0);
const largeGroup = Overview.layout({ nodes: Array.from({ length: 61 }, (_, i) => variable('grid-' + i)), edges: [] });
assert.equal(new Set(largeGroup.nodes.map(item => item.x)).size, 6, 'Large template groups use six columns.');
assert(largeGroup.nodes.every(item => item.x + 210 <= largeGroup.groups[0].x + largeGroup.groups[0].width));
const smallGroup = Overview.layout({ nodes: Array.from({ length: 60 }, (_, i) => variable('grid-' + i)), edges: [] });
assert.equal(new Set(smallGroup.nodes.map(item => item.x)).size, 3);
assert.equal(JSON.stringify(state), before, 'Layout and reachability never edit review data.');

if (process.argv[2]) {
  const saved = JSON.parse(readFileSync(process.argv[2], 'utf8'));
  const realState = saved.graph || saved.state || saved, real = Overview.project(realState);
  assert.equal(real.nodes.length, realState.variables.length);
  const realLayout = Overview.layout(real, { includeExcluded: true });
  assert.equal(realLayout.nodes.length, realState.variables.length, 'All real records, including historical items, can be displayed.');
  assert.equal(Overview.layout(real).nodes.length, realState.variables.filter(node => node.status !== 'excluded').length);
  assert.equal(new Set(realLayout.nodes.map(item => item.node.id)).size, realState.variables.length);
  for (const item of realLayout.nodes) {
    const group = realLayout.groups.find(group => group.id === item.group);
    assert(item.x >= group.x && item.x + 210 <= group.x + group.width);
    assert(item.y >= group.y && item.y + 88 <= group.y + group.height);
    const reached = Overview.impact(real, item.node.id);
    assert(!reached.upstreamIds.includes(item.node.id) && !reached.downstreamIds.includes(item.node.id));
  }
  for (const node of realState.variables) {
    if (node.parentId) assert(real.edges.some(edge => edge.kind === 'parent' && edge.from === node.parentId && edge.to === node.id));
    const branches = node.correction?.kind === 'rule' ? node.correction.branches || [{ ...node.correction, id: 'case-1' }] : [];
    for (const [branchIndex, branch] of branches.entries()) {
      if (branch.scopeId) assert(real.edges.some(edge => edge.kind === 'scope' && edge.from === branch.scopeId && edge.to === node.id && edge.branchIndex === branchIndex));
      for (const [predicateIndex, clause] of (branch.clauses || []).entries()) assert(real.edges.some(edge => edge.kind === 'condition' && edge.from === clause.inputId && edge.to === node.id && edge.branchIndex === branchIndex && edge.predicateIndex === predicateIndex));
    }
    for (const [targetIndex, target] of (node.editTargets || []).entries()) {
      if (target.valueSourceId) assert(real.edges.some(edge => edge.kind === 'value' && edge.from === target.valueSourceId && edge.to === node.id && edge.targetIndex === targetIndex));
      if (target.valueMode === 'join' && target.listId) assert(real.edges.some(edge => edge.kind === 'join' && edge.from === target.listId && edge.to === node.id && edge.targetIndex === targetIndex));
    }
  }
  console.log(`PASS: real graph coverage (${real.nodes.length} records, ${real.edges.length} declared links, ${real.warnings.length} retained warnings).`);
}
console.log('PASS: graph projection, condition cases, list scope/join, source ownership, references, stale/history flags, cycle-safe impact and complete deterministic layout.');
