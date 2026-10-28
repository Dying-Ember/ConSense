export const isSourceCheck = node => node?.role?.kind === 'verification' || node?.definitionKind === 'source_seed';
export const pendingEditorial = node => node?.editorialSuggestion?.kind === 'editorial' && !(node.editorialReview?.confirmed === true && node.editorialReview?.kind === 'business_value');
export const isTreatmentRule = node => node?.origin === 'scope_rules_compilation';
// A compiled amendment's Boolean flag is not a business answer or a calculated business value.
export const isValueVariable = node => !!node && node.status !== 'excluded' && !isSourceCheck(node) && !pendingEditorial(node) && !isTreatmentRule(node) && ['input', 'decision', 'content', 'derived', 'system', 'fixed'].includes(node.role?.kind);

// Only reading an answer creates a value dependency; amendment targets do not derive answers.
export function valueDependencies(state) {
  const nodes = new Map((state.variables || []).filter(node => node.status !== 'excluded' && !isSourceCheck(node)).map(node => [node.id, node]));
  const edges = [];
  const add = (from, to, kind) => { if (nodes.has(from) && nodes.has(to) && !edges.some(edge => edge.from === from && edge.to === to && edge.kind === kind)) edges.push({ from, to, kind, cyclic: false }); };
  for (const node of nodes.values()) {
    if (node.parentId && nodes.get(node.parentId)?.type?.kind !== 'list') add(node.parentId, node.id, 'parent');
    for (const clause of node.applicability?.clauses || []) add(clause.inputId, node.id, 'applicability');
    if (node.role?.kind !== 'derived') continue;
    const correction = node.correction, branches = correction?.branches || (correction?.kind === 'rule' && correction.clauses?.length ? [correction] : []);
    for (const branch of branches) {
      if (branch.scopeId) add(branch.scopeId, node.id, 'scope');
      for (const clause of branch.clauses || []) add(clause.inputId, node.id, 'condition');
    }
  }
  const adjacency = new Map(), reachable = new Map();
  for (const edge of edges) adjacency.set(edge.from, [...(adjacency.get(edge.from) || []), edge.to]);
  const reaches = id => {
    if (!reachable.has(id)) {
      const seen = new Set(), pending = [...(adjacency.get(id) || [])];
      while (pending.length) { const next = pending.pop(); if (seen.has(next)) continue; seen.add(next); pending.push(...(adjacency.get(next) || [])); }
      reachable.set(id, seen);
    }
    return reachable.get(id);
  };
  for (const edge of edges) edge.cyclic = reaches(edge.to).has(edge.from);
  return edges;
}
