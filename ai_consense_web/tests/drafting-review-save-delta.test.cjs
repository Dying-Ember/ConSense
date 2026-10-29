const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const { reviewSaveDelta, restoreReviewSave } = loadTypeScript(path.join(__dirname, '../src/drafting/review-save.ts'))
const copy = value => JSON.parse(JSON.stringify(value))
const plain = value => JSON.parse(JSON.stringify(value))
const base = () => ({ id: 'base', graphSha256: 'a'.repeat(64), status: 'DRAFT', sourceStale: false, graph: { kind: 'consense-template-graph-v1', sources: [{ id: '41', sourceSha256: 'b'.repeat(64) }], selectedDocuments: { SCT: '41' }, graph: { variables: [{ id: 'a', role: { kind: 'input' }, catalogAlignment: { confirmed: true }, sourceAnchors: [{ text: 'full source' }], editTargets: [{ id: 't', native: { start: 1, end: 5 }, needsRebind: false }] }, { id: 'dependency', status: 'confirmed' }], events: [{ en: 'Prior history' }], requirements: [{ id: 'unknown', kind: 'verification', nodeIds: [] }], sourceScopeObservations: { original: { text: 'Full retained range' } }, automaticDiscovery: { sources: [{ memberCoverage: ['full audit'] }] } } } })

test('all changed dependent nodes are full replacements; untouched evidence and history are inherited', () => {
  const original = base(), before = JSON.stringify(original), proposed = copy(original.graph)
  proposed.graph.variables[0].role.kind = 'derived'; delete proposed.graph.variables[0].catalogAlignment
  proposed.graph.variables[1].status = 'pending'; proposed.graph.events.push({ en: 'Explicit manual shared choice' })
  const delta = reviewSaveDelta(original, proposed, null, 'base')
  assert.deepEqual(delta.variables.map(row => row.id), ['a', 'dependency'])
  assert.equal(Object.hasOwn(delta.variables[0], 'catalogAlignment'), false)
  assert.equal(JSON.stringify(delta).includes('full audit'), false)
  const result = restoreReviewSave(original, { ...original, id: 'saved', graph: undefined, graphDelta: { ...delta, stateChanges: { definitionGraphReview: { valid: false } }, removedStateKeys: [] } })
  assert.equal(Object.hasOwn(result.graph.graph.variables[0], 'catalogAlignment'), false)
  assert.deepEqual(plain(result.graph.graph.requirements), original.graph.graph.requirements)
  assert.deepEqual(plain(result.graph.graph.sourceScopeObservations), original.graph.graph.sourceScopeObservations)
  assert.equal(result.graph.graph.events.length, 2); assert.equal(JSON.stringify(original), before)
})

test('missing/stale baseline, additions, deletions, reorder, changed sources or other state all explicitly fall back', () => {
  for (const defect of ['missing', 'stale', 'wrong-head', 'no-hash', 'add', 'delete', 'reorder', 'duplicate', 'source', 'range-table', 'requirements', 'history']) {
    const original = base(), proposed = copy(original.graph); proposed.graph.variables[0].nameEn = 'Changed'
    if (defect === 'stale') original.sourceStale = true
    if (defect === 'no-hash') original.graphSha256 = 'unknown'
    if (defect === 'add') proposed.graph.variables.push({ id: 'new' })
    if (defect === 'delete') proposed.graph.variables.pop()
    if (defect === 'reorder') proposed.graph.variables.reverse()
    if (defect === 'duplicate') proposed.graph.variables[1].id = 'a'
    if (defect === 'source') proposed.sources[0].sourceSha256 = 'changed'
    if (defect === 'range-table') proposed.graph.sourceScopeObservations.original.text = 'Edited range table'
    if (defect === 'requirements') proposed.graph.requirements[0].nodeIds.push('a')
    if (defect === 'history') proposed.graph.events[0].en = 'Rewritten history'
    assert.equal(reviewSaveDelta(defect === 'missing' ? undefined : original, proposed, null, defect === 'wrong-head' ? 'another' : 'base'), undefined, defect)
  }
})

test('mismatched or incomplete receipt requires a full saved-revision read; normalized state deletions are explicit', () => {
  const original = base(), proposed = copy(original.graph); proposed.graph.variables[0].nameEn = 'Changed'
  const delta = reviewSaveDelta(original, proposed, null, 'base')
  const receipt = { ...original, id: 'saved', graph: undefined, graphDelta: { ...delta, stateChanges: {}, removedStateKeys: ['automaticDiscovery'] } }
  const result = restoreReviewSave(original, receipt)
  assert.equal(Object.hasOwn(result.graph.graph, 'automaticDiscovery'), false)
  for (const defect of ['base-id', 'base-sha', 'unknown-node', 'state-variable', 'no-state', 'duplicate-removal']) {
    const bad = copy(receipt)
    if (defect === 'base-id') bad.graphDelta.baseRevisionId = 'other'
    if (defect === 'base-sha') bad.graphDelta.baseGraphSha256 = 'other'
    if (defect === 'unknown-node') bad.graphDelta.variables[0].id = 'unknown'
    if (defect === 'state-variable') bad.graphDelta.stateChanges.variables = []
    if (defect === 'no-state') delete bad.graphDelta.stateChanges
    if (defect === 'duplicate-removal') bad.graphDelta.removedStateKeys.push('automaticDiscovery')
    assert.equal(restoreReviewSave(original, bad), undefined, defect)
  }
  assert.equal(restoreReviewSave(undefined, original), original, 'The compatible full response needs no local baseline')
})

test('the transport keeps full saves compatible and sends the same explicit CAS on lightweight saves', async () => {
  const calls = [], api = { post: async (url, body) => { calls.push({ url, body }); return body } }
  const { draftingApi } = loadTypeScript(path.join(__dirname, '../src/api/index.ts'), { dependencies: { './client': { api }, './types': {}, './source-locations': {} } })
  const original = base(), proposed = copy(original.graph); proposed.graph.variables[0].nameEn = 'Changed'
  const delta = reviewSaveDelta(original, proposed, null, 'base')
  await draftingApi.reviewSave('p', proposed, null, 'base'); await draftingApi.reviewSaveDelta('p', delta, null, 'base')
  assert.deepEqual(Object.keys(calls[0].body), ['graph', 'expectedCurrentRevisionId', 'expectedDraftRevisionId'])
  assert.deepEqual(Object.keys(calls[1].body), ['graphDelta', 'expectedCurrentRevisionId', 'expectedDraftRevisionId'])
  assert.equal(calls[1].body.graphDelta, delta); assert.equal(calls[1].body.expectedDraftRevisionId, 'base'); assert.equal(calls[1].body.expectedCurrentRevisionId, null)
})
