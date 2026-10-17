import Overview from './graph-overview.js';
function create(Overview) {
  'use strict';

  const blocked = item => ['referenceOnly', 'needsRebind', 'unresolved', 'excluded', 'missing', 'incompatible'].some(flag => item[flag]);

  function routes(edges, boxes) {
    const outgoing = new Map(), incoming = new Map();
    const side = edge => edge.kind === 'destination' || edge.kind === 'ownership' || edge.kind === 'action-input' ? 'right' : 'left';
    for (const edge of edges) {
      const from = side(edge) + ':' + edge.from, to = side(edge) + ':' + edge.to;
      outgoing.set(from, [...(outgoing.get(from) || []), edge]);
      incoming.set(to, [...(incoming.get(to) || []), edge]);
    }
    const port = (edge, id, buckets) => {
      const list = buckets.get(side(edge) + ':' + id), index = list.indexOf(edge);
      return { fraction: (index + 1) / (list.length + 1), lane: list.length > 1 ? index / (list.length - 1) : .5 };
    };
    const n = value => Number(value.toFixed(3));
    for (const edge of edges) {
      const a = boxes.get(edge.from), b = boxes.get(edge.to);
      if (!a || !b) { edge.path = ''; continue; }
      const source = port(edge, edge.from, outgoing), target = port(edge, edge.to, incoming);
      const y = n(a.y + 8 + (a.h - 16) * source.fraction), by = n(b.y + 8 + (b.h - 16) * target.fraction);
      if (edge.kind === 'parent' && a.x < b.x && !edge.cyclic && !edge.incompatible) {
        edge.path = `M${a.x + 10},${a.y + a.h} V${by} H${b.x}`;
      } else if (side(edge) === 'left') {
        const lane = n(3 + source.lane * 12);
        edge.path = `M${a.x},${y} C${lane},${y} ${lane},${by} ${b.x},${by}`;
      } else {
        const x = a.x + a.w, gap = b.x - x;
        const first = n(x + gap * (.22 + source.lane * .18)), last = n(b.x - gap * (.22 + target.lane * .18));
        edge.path = `M${x},${y} C${first},${y} ${last},${by} ${b.x},${by}`;
      }
    }
  }

  function build(state, { docId, includeExcluded = true, query = '', sources = [] } = {}) {
    if (!Overview) throw Error('GraphOverview must load before GraphBusinessView.');
    const edition = id => { const source = sources.find(item => [String(item.id), item.docId, item.editionId].includes(String(id))); return source?.fileKey === 'SCC' ? source.format : id === 'scc-word' ? 'docx' : id === 'scc-pdf' ? 'pdf' : null; };
    const documents = sources.map(source => [String(source.id), source.fileName, `${source.fileKey ?? source.category ?? ''} · ${source.format}`]);
    const graph = Overview.project(state, { sources }), allActions = [], allEdges = graph.edges.map((edge, index) => ({ ...edge, id: 'relation:' + index, relation: edge }));
    const allById = new Map(graph.nodes.map(node => [node.id, node]));
    const addAction = (owner, details) => {
      const action = { ownerId: owner.id, node: owner, targets: [], referenceOnly: false,
        needsRebind: Boolean(owner.correction?.needsRebind), unresolved: false, excluded: owner.status === 'excluded', ...details };
      action.docIds = [...new Set(action.targets.map(entry => entry.target.docId).filter(Boolean))];
      action.missingDestination = !action.targets.length || action.targets.some(entry => !documents.some(doc => doc[0] === entry.target.docId));
      action.incompatible = new Set([...(owner.docIds || []), ...action.docIds].map(edition).filter(Boolean)).size > 1;
      action.inputIds = [];
      const addInput = (id, role, metadata = {}) => {
        if (!id) return;
        if (!action.inputIds.includes(id)) action.inputIds.push(id);
        const relations = graph.edges.filter(edge => edge.ownerId === owner.id && edge.from === id && edge.kind === role &&
          (metadata.targetIndex === undefined ? edge.branchIndex === action.branchIndex : edge.targetIndex === metadata.targetIndex));
        const relation = relations.find(edge => metadata.predicateIndex === undefined || edge.predicateIndex === metadata.predicateIndex);
        const input = allById.get(id), flags = relation || {};
        allEdges.push({ ...flags, ...metadata, id: action.id + ':input:' + (allEdges.length), from: id, to: action.id,
          kind: 'action-input', role, ownerId: owner.id, actionId: action.id, relation,
          referenceOnly: action.referenceOnly || Boolean(flags.referenceOnly), needsRebind: action.needsRebind || Boolean(flags.needsRebind),
          unresolved: action.unresolved || Boolean(flags.unresolved), missing: !input, excluded: action.excluded || input?.status === 'excluded',
          incompatible: action.incompatible || Boolean(flags.incompatible) });
      };
      if (action.branch) {
        addInput(action.branch.scopeId, 'scope', { scopeMode: action.branch.scopeMode, mode: action.branch.mode });
        for (const [predicateIndex, clause] of (action.branch.clauses || []).entries()) {
          addInput(clause.inputId, 'condition', { predicateIndex, mode: action.branch.mode, operator: clause.operator, value: clause.value });
        }
      }
      if (action.kind === 'split') for (const [predicateIndex, id] of (owner.correction.inputIds || []).entries()) {
        addInput(id, 'condition', { predicateIndex, mode: owner.correction.mode, operator: 'yes' });
      }
      for (const { target, index } of action.targets) {
        addInput(target.valueSourceId, 'value', { targetIndex: index, valueMode: target.valueMode || 'single' });
        if (target.valueMode === 'join') addInput(target.listId, 'join', { targetIndex: index, separator: target.separator });
      }
      const actionInputs = allEdges.filter(edge => edge.actionId === action.id);
      action.missing = actionInputs.some(edge => edge.missing);
      action.needsRebind = action.needsRebind || action.targets.some(entry => entry.target.needsRebind);
      action.incompatible = action.incompatible || actionInputs.some(edge => edge.incompatible);
      action.operations = [...new Set(action.targets.map(entry => entry.target.action))];
      action.summaryZh = action.branch?.effectZh || '';
      action.summaryEn = action.branch?.effectEn || '';
      action.needsReview = action.missingDestination || blocked(action) || actionInputs.some(blocked) ||
        action.targets.some(entry => entry.target.needsRebind || entry.target.action === 'check');
      allActions.push(action);
      allEdges.push({ id: action.id + ':owner', from: owner.id, to: action.id, kind: 'ownership', ownerId: owner.id,
        actionId: action.id, ownershipOnly: true, referenceOnly: true });
      for (const targetDocId of action.docIds) {
        if (!documents.some(doc => doc[0] === targetDocId)) continue;
        allEdges.push({ id: action.id + ':file:' + targetDocId, from: action.id, to: 'file:' + targetDocId,
          kind: 'destination', ownerId: owner.id, actionId: action.id, docId: targetDocId,
          targets: action.targets.filter(entry => entry.target.docId === targetDocId),
          referenceOnly: action.referenceOnly, needsRebind: action.needsRebind || action.targets.some(entry => entry.target.docId === targetDocId && entry.target.needsRebind),
          unresolved: action.unresolved, excluded: action.excluded, incompatible: action.incompatible });
      }
    };
    for (const owner of graph.nodes) {
      const correction = owner.correction, targets = (owner.editTargets || []).map((target, index) => ({ target, index }));
      const branches = correction?.kind === 'rule' ? correction.branches || [{ ...correction, id: 'case-1' }] : [];
      const assigned = new Set();
      for (const [branchIndex, branch] of branches.entries()) {
        const matched = targets.filter(entry => entry.target.branchId && entry.target.branchId === branch.id && !assigned.has(entry.index));
        matched.forEach(entry => assigned.add(entry.index));
        addAction(owner, { id: 'action:' + owner.id + ':branch:' + branchIndex, kind: 'branch', branchIndex,
          branchId: branch.id, branch, targets: matched, mode: branch.mode, scopeId: branch.scopeId, scopeMode: branch.scopeMode,
          resultValue: branch.resultValue, unresolved: branch.mode === 'unknown' || !branch.id || branches.some((other, index) => index !== branchIndex && other.id === branch.id),
          labelZh: '条件方案 ' + (branchIndex + 1), labelEn: 'Conditional action ' + (branchIndex + 1) });
      }
      const unassigned = targets.filter(entry => !entry.target.branchId);
      if (unassigned.length) addAction(owner, { id: 'action:' + owner.id + ':all', kind: 'all-cases', targets: unassigned,
        labelZh: '所有情况的处理', labelEn: 'Action for all cases' });
      for (const entry of targets.filter(entry => entry.target.branchId && !assigned.has(entry.index))) {
        addAction(owner, { id: 'action:' + owner.id + ':missing:' + entry.index, kind: 'missing-branch', branchId: entry.target.branchId,
          targets: [entry], unresolved: true, labelZh: '条件对应关系待核对', labelEn: 'Case assignment needs review' });
      }
      if (correction?.kind === 'split') addAction(owner, { id: 'action:' + owner.id + ':split', kind: 'split',
        branch: null, correction, mode: correction.mode, unresolved: true, referenceOnly: true,
        labelZh: '拆分后的关系待核对', labelEn: 'Review split relationships' });
      for (const [index, relation] of (owner.relations || []).entries()) addAction(owner, {
        id: 'action:' + owner.id + ':reference:' + index, kind: 'reference', referenceOnly: true, relation,
        labelZh: '原有关系说明 · 参考', labelEn: 'Previous relationship · reference' });
    }

    const available = graph.nodes.filter(node => includeExcluded || node.status !== 'excluded');
    const primary = new Set(available.filter(node => !docId || (node.docIds || []).includes(docId) ||
      allActions.some(action => action.ownerId === node.id && action.docIds.includes(docId))).map(node => node.id));
    const visible = new Set(primary);
    if (docId) for (const edge of graph.edges) if (primary.has(edge.from) || primary.has(edge.to)) {
      visible.add(edge.from); visible.add(edge.to);
    }
    const actions = allActions.filter(action => primary.has(action.ownerId) && (!docId || !action.docIds.length || action.docIds.includes(docId)));
    for (const action of actions) for (const id of action.inputIds) visible.add(id);
    const nodes = available.filter(node => visible.has(node.id)), byId = new Map(nodes.map(node => [node.id, node]));
    const ordered = [], visited = new Set(), children = new Map();
    for (const node of nodes) if (byId.has(node.parentId)) children.set(node.parentId, [...(children.get(node.parentId) || []), node]);
    const append = (node, depth) => {
      if (visited.has(node.id)) return;
      visited.add(node.id);
      ordered.push({ id: node.id, node, depth, parentCycle: graph.warnings.some(warning => warning.kind === 'parent-cycle' && warning.ownerId === node.id), contextual: !primary.has(node.id) });
      for (const child of children.get(node.id) || []) if (!visited.has(child.id)) append(child, depth + 1);
    };
    for (const node of nodes.filter(node => !byId.has(node.parentId))) append(node, 0);
    for (const node of nodes) if (!visited.has(node.id)) append(node, 0);
    const normalizedQuery = query.trim().toLocaleLowerCase();
    const actionsByOwner = new Map();
    for (const action of actions) actionsByOwner.set(action.ownerId, [...(actionsByOwner.get(action.ownerId) || []), action]);
    let y = 50;
    const variables = ordered.map(item => {
      const inset = Math.min(item.depth, 3) * 18, h = item.depth ? 70 : 80;
      const result = { ...item, x: 26 + inset, y, w: 214 - inset, h,
        matched: !normalizedQuery || (item.node.nameZh + ' ' + item.node.nameEn).toLocaleLowerCase().includes(normalizedQuery) };
      const ownActions = actionsByOwner.get(item.id) || [];
      ownActions.forEach((action, index) => Object.assign(action, { x: 300, y: y + index * 92, w: 230, h: 78 }));
      y += Math.max(h + 12, ownActions.length * 92);
      return result;
    });
    const height = Math.max(520, y + 70);
    const files = documents.map(([id, labelZh, labelEn], index) => ({ id: 'file:' + id, docId: id, labelZh, labelEn,
      x: 600, y: 60 + index * Math.max(110, (height - 200) / documents.length), w: 170, h: 88 }));
    const boxes = new Map([...variables, ...actions, ...files].map(item => [item.id, item]));
    const actionIds = new Set(actions.map(action => action.id));
    const edges = allEdges.filter(edge => edge.actionId ? actionIds.has(edge.actionId) : visible.has(edge.from) && visible.has(edge.to));
    routes(edges, boxes);
    return { graph, variables, actions, files, edges, width: 800, height, docId };
  }

  function selection(view, id) {
    const selectedAction = view.actions.find(action => action.id === id), ownerId = selectedAction?.ownerId || id;
    const impact = selectedAction ? { upstreamIds: [], downstreamIds: [] } : Overview.impact(view.graph, ownerId);
    const downstream = new Set([ownerId, ...impact.downstreamIds]);
    const actions = selectedAction ? [selectedAction] : view.actions.filter(action => action.ownerId === ownerId ||
      downstream.has(action.ownerId) && !blocked(action));
    const variableIds = new Set([ownerId, ...impact.upstreamIds, ...impact.downstreamIds]);
    for (const action of actions) for (const inputId of action.inputIds) variableIds.add(inputId);
    const actionIds = new Set(actions.map(action => action.id)), edgeIds = new Set();
    const fileIds = new Set();
    for (const edge of view.edges) {
      let related = edge.actionId ? actionIds.has(edge.actionId) : variableIds.has(edge.from) && variableIds.has(edge.to);
      if (related && selectedAction && !edge.actionId && edge.kind !== 'parent') {
        related = edge.ownerId === ownerId && (['condition', 'scope'].includes(edge.kind)
          ? ['branch', 'split'].includes(selectedAction.kind) && edge.branchIndex === selectedAction.branchIndex
          : ['value', 'join'].includes(edge.kind) && selectedAction.targets.some(entry => entry.index === edge.targetIndex));
      }
      if (related) {
        edgeIds.add(edge.id);
        if (edge.kind === 'destination') fileIds.add(edge.to);
      }
    }
    return { selectedId: id, variableIds: [...variableIds].filter(item => view.variables.some(variable => variable.id === item)),
      actionIds: [...actionIds], fileIds: [...fileIds], edgeIds: [...edgeIds],
      upstreamIds: impact.upstreamIds, downstreamIds: impact.downstreamIds };
  }

  return { build, selection };
}
export default create(Overview);
