(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.GraphOverview = factory();
})(typeof window === 'object' ? window : globalThis, function () {
  'use strict';

  function project(state) {
    if (!Array.isArray(state?.variables)) throw TypeError('A review state with variables is required.');
    const nodes = state.variables.slice(), byId = new Map(nodes.map(node => [node.id, node]));
    const edges = [], warnings = [], docsById = new Map();
    for (const node of nodes) {
      const docs = new Set(node.docIds || []), seen = new Set();
      let cursor = node;
      while (cursor?.parentId && !seen.has(cursor.id)) {
        seen.add(cursor.id);
        cursor = byId.get(cursor.parentId);
        for (const doc of cursor?.docIds || []) docs.add(doc);
      }
      docsById.set(node.id, [...docs]);
      if (cursor?.parentId && seen.has(cursor.id)) warnings.push({ kind: 'parent-cycle', ownerId: node.id });
    }
    const add = (owner, from, to, kind, details = {}) => {
      if (!from || !to) return;
      const input = byId.get(from), output = byId.get(to);
      const docs = new Set([...(docsById.get(from) || []), ...(docsById.get(to) || [])]);
      const edge = { from, to, kind, ownerId: owner.id, docIds: docsById.get(owner.id),
        referenceOnly: false, needsRebind: false, unresolved: false, ...details,
        self: from === to, cyclic: false,
        missing: !input || !output, excluded: input?.status === 'excluded' || output?.status === 'excluded',
        incompatible: docs.has('scc-word') && docs.has('scc-pdf') };
      edges.push(edge);
      for (const flag of ['missing', 'excluded', 'incompatible', 'needsRebind', 'unresolved']) {
        if (edge[flag]) warnings.push({ kind: flag, ownerId: owner.id, edgeIndex: edges.length - 1 });
      }
    };
    for (const node of nodes) {
      if (node.parentId) add(node, node.parentId, node.id, 'parent');
      if (node.originId) add(node, node.originId, node.id, 'split-origin', { referenceOnly: true });
      for (const id of node.replacedBy || []) add(node, node.id, id, 'replaced-by', { referenceOnly: true });
      for (const id of node.relatedInputIds || []) add(node, id, node.id, 'reference', { referenceOnly: true });
      const correction = node.correction;
      if (correction?.kind === 'split') {
        for (const [predicateIndex, id] of (correction.inputIds || []).entries()) {
          add(node, id, node.id, 'condition', { predicateIndex, mode: correction.mode, operator: 'yes',
            needsRebind: Boolean(correction.needsRebind), unresolved: correction.mode === 'unknown' });
        }
      } else if (correction?.kind === 'rule') {
        const branches = correction.branches || [{ ...correction, id: 'case-1' }];
        for (const [branchIndex, branch] of branches.entries()) {
          const details = { branchIndex, branchId: branch.id, mode: branch.mode,
            needsRebind: Boolean(correction.needsRebind), unresolved: branch.mode === 'unknown' };
          if (branch.scopeId) add(node, branch.scopeId, node.id, 'scope', { ...details, scopeMode: branch.scopeMode });
          for (const [predicateIndex, clause] of (branch.clauses || []).entries()) {
            add(node, clause.inputId, node.id, 'condition', { ...details, predicateIndex, operator: clause.operator, value: clause.value });
          }
        }
      }
      for (const [targetIndex, target] of (node.editTargets || []).entries()) {
        const branch = target.branchId && (correction?.kind === 'rule' ? correction.branches || [{ ...correction, id: 'case-1' }] : [])
          .find(item => item.id === target.branchId);
        const details = { targetIndex, branchId: target.branchId, needsRebind: Boolean(correction?.needsRebind || target.needsRebind),
          unresolved: Boolean(target.branchId && (!branch || branch.mode === 'unknown')) };
        if (target.valueSourceId) add(node, target.valueSourceId, node.id, 'value', { ...details, valueMode: target.valueMode || 'single' });
        if (target.valueMode === 'join' && target.listId) add(node, target.listId, node.id, 'join', { ...details, separator: target.separator });
      }
    }
    const adjacency = new Map(), reachable = new Map();
    const dependency = edge => !edge.self && ['condition', 'value'].includes(edge.kind);
    for (const edge of edges.filter(dependency)) {
      if (!adjacency.has(edge.from)) adjacency.set(edge.from, new Set());
      adjacency.get(edge.from).add(edge.to);
    }
    const reaches = id => {
      if (!reachable.has(id)) {
        const seen = new Set(), pending = [...(adjacency.get(id) || [])];
        while (pending.length) {
          const next = pending.pop();
          if (seen.has(next)) continue;
          seen.add(next);
          for (const target of adjacency.get(next) || []) if (!seen.has(target)) pending.push(target);
        }
        reachable.set(id, seen);
      }
      return reachable.get(id);
    };
    edges.forEach((edge, edgeIndex) => {
      edge.cyclic = dependency(edge) && reaches(edge.to).has(edge.from);
      if (edge.cyclic) warnings.push({ kind: 'cycle', ownerId: edge.ownerId, edgeIndex });
    });
    // shortcut: declared cycles are shown for review; this projection does not evaluate rules.
    return { nodes, edges, warnings };
  }

  function neighborhood(graph, id, { docId } = {}) {
    const node = graph.nodes.find(item => item.id === id) || null;
    const edges = graph.edges.filter(edge => (edge.from === id || edge.to === id) && (!docId || edge.docIds.includes(docId)));
    const ids = new Set(node ? [id] : []);
    for (const edge of edges) { ids.add(edge.from); ids.add(edge.to); }
    const indices = new Set(edges.map(edge => graph.edges.indexOf(edge)));
    return { node, nodes: graph.nodes.filter(item => ids.has(item.id)), edges,
      incoming: edges.filter(edge => edge.to === id), outgoing: edges.filter(edge => edge.from === id),
      warnings: graph.warnings.filter(warning => warning.edgeIndex === undefined ? ids.has(warning.ownerId) : indices.has(warning.edgeIndex)) };
  }

  function impact(graph, id) {
    // Reachability shows possible linked changes; it does not evaluate condition answers.
    const eligible = graph.edges.filter(edge => ['condition', 'scope', 'value', 'join'].includes(edge.kind) &&
      !['needsRebind', 'excluded', 'missing', 'incompatible', 'unresolved', 'referenceOnly'].some(flag => edge[flag]));
    const walk = direction => {
      const from = direction === 'downstream' ? 'from' : 'to', to = from === 'from' ? 'to' : 'from';
      const adjacency = new Map(), visited = new Set([id]), links = new Set(), pending = [id];
      for (const edge of eligible) {
        if (!adjacency.has(edge[from])) adjacency.set(edge[from], []);
        adjacency.get(edge[from]).push(edge);
      }
      while (pending.length) {
        for (const edge of adjacency.get(pending.pop()) || []) {
          links.add(edge);
          if (!visited.has(edge[to])) { visited.add(edge[to]); pending.push(edge[to]); }
        }
      }
      return { ids: graph.nodes.filter(node => node.id !== id && visited.has(node.id)).map(node => node.id),
        edges: graph.edges.filter(edge => links.has(edge)) };
    };
    const upstream = walk('upstream'), downstream = walk('downstream');
    return { selectedId: id, upstreamIds: upstream.ids, downstreamIds: downstream.ids,
      upstreamEdges: upstream.edges, downstreamEdges: downstream.edges };
  }

  function layout(graph, { docId, includeExcluded = false } = {}) {
    const available = graph.nodes.filter(node => includeExcluded || node.status !== 'excluded');
    const selected = new Set(available.filter(node => !docId || (node.docIds || []).includes(docId)).map(node => node.id));
    const shown = new Set(selected);
    if (docId) for (const edge of graph.edges) {
      if (selected.has(edge.from) || selected.has(edge.to)) { shown.add(edge.from); shown.add(edge.to); }
    }
    const nodes = available.filter(node => shown.has(node.id));
    const groupFor = node => {
      const docs = node.docIds || [];
      if (docs.includes('scc-word')) return 'scc-word';
      if (docs.includes('scc-pdf')) return 'scc-pdf';
      if (docs.includes('ntt') && docs.includes('sct')) return 'shared';
      return docs.includes('ntt') ? 'ntt' : docs.includes('sct') ? 'sct' : 'other';
    };
    const definitions = [
      ['shared', 'NTT 与 SCT 共用', 'Shared by NTT and SCT'], ['ntt', 'NTT', 'NTT'], ['sct', 'SCT', 'SCT'],
      ['scc-word', 'SCC · Word 版', 'SCC · Word edition'], ['scc-pdf', 'SCC · PDF 版', 'SCC · PDF edition'],
      ['other', '其他资料', 'Other material']
    ];
    const positioned = [], groups = [];
    let x = 24, height = 180;
    for (const [id, labelZh, labelEn] of definitions) {
      const members = nodes.filter(node => groupFor(node) === id);
      if (!members.length) continue;
      const columns = members.length > 60 ? 6 : 3;
      const group = { id, labelZh, labelEn, x, y: 24, width: columns * 234 + 24, height: 64 + Math.ceil(members.length / columns) * 112 };
      members.forEach((node, index) => positioned.push({ node, group: id, contextual: !selected.has(node.id),
        x: group.x + 24 + index % columns * 234, y: group.y + 64 + Math.floor(index / columns) * 112 }));
      groups.push(group);
      x += group.width + 36;
      height = Math.max(height, group.y + group.height + 24);
    }
    const visible = new Set(positioned.map(item => item.node.id));
    const edges = graph.edges.filter(edge => (visible.has(edge.from) && visible.has(edge.to)) ||
      (edge.missing && (visible.has(edge.from) || visible.has(edge.to))));
    return { nodes: positioned, edges, groups, width: groups.length ? x - 12 : 480, height };
  }

  return { project, neighborhood, impact, layout };
});
