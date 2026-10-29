const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle } = require('./helpers/render-controls.cjs')
const copy = value => JSON.parse(JSON.stringify(value))

async function reviewScenario(scenario) {
  const source = { id: '41', fileKey: 'SCT', fileName: 'TEST SCT.docx', format: 'docx', sourceSha256: 'b'.repeat(64), evidenceStatus: 'bound', canEdit: true }
  const node = { id: 'a', nameZh: '测试', nameEn: 'TEST fact', type: { kind: 'boolean' }, role: { kind: 'input' }, status: 'pending', docIds: ['41'], sourceAnchors: [], editTargets: [], options: [], sourceUnitIds: [], relatedInputIds: [], relations: [], targets: [], evidence: [] }
  const graph = { kind: 'consense-template-graph-v1', sources: [source], selectedDocuments: { SCT: '41' }, graph: { variables: [node], events: [], requirements: [{ id: 'unknown', kind: 'verification', nodeIds: [] }], automaticDiscovery: { sources: [{ memberCoverage: ['retained large evidence'] }] } } }
  const baseline = { id: 'base', graphSha256: 'a'.repeat(64), parentRevisionId: null, status: 'DRAFT', sourceStale: false, graph: copy(graph) }
  let heads = 0, fullSaves = 0, deltas = 0, fullReads = 0, savedGraph
  const api = {
    reviewHead: async () => { heads++; return { currentRevisionId: null, draftRevisionId: 'base', current: null, draft: copy(baseline), sources: [source] } },
    reviewReading: async () => ({ ...source, paragraphs: [], sourceMembers: [], guidanceNotes: [], markerCandidates: [], groups: [], conditions: [], issues: [], blocks: [] }),
    reviewPreview: async () => { throw Error('Synthetic source has no renderer') },
    reviewSave: async (_project, proposed) => { fullSaves++; return { ...baseline, id: 'saved', graph: copy(proposed) } },
    reviewSaveDelta: async (_project, delta, current, draft) => {
      deltas++; assert.equal(current, null); assert.equal(draft, 'base'); assert.equal(delta.baseRevisionId, 'base')
      if (scenario === 'conflict') throw Error('CAS conflict; retain the unsaved edit')
      assert.deepEqual(Object.keys(delta).sort(), ['appendedEvents', 'baseGraphSha256', 'baseRevisionId', 'variables'])
      assert.equal(delta.variables.length, 1); assert.equal(delta.variables[0].nameEn, 'Changed fact')
      assert.equal(JSON.stringify(delta).includes('retained large evidence'), false)
      const normalized = { ...copy(delta.variables[0]), nameEn: 'Normalized fact' }
      savedGraph = copy(baseline.graph); savedGraph.graph.variables = [normalized]; savedGraph.graph.events.push(...copy(delta.appendedEvents)); savedGraph.graph.definitionGraphReview = { valid: false }
      return { ...baseline, id: 'saved', graphSha256: 'c'.repeat(64), graph: undefined, graphDelta: { ...copy(delta), baseRevisionId: scenario === 'mismatch' ? 'other' : delta.baseRevisionId, variables: [normalized], stateChanges: { definitionGraphReview: { valid: false } }, removedStateKeys: [] } }
    },
    reviewRevision: async (_project, id) => { fullReads++; assert.equal(scenario, 'mismatch'); assert.equal(id, 'saved'); return { ...baseline, id, graphSha256: 'c'.repeat(64), graph: savedGraph } }
  }
  const loaded = loadVue(path.join(__dirname, '../src/components/DraftingSourceReview.vue'), { boundaries: { '@/api': { draftingApi: api }, '@/api/source-locations': { reviewSourceBindings: async () => { throw Error('No synthetic PDF binding') } }, './AppModal.vue': { default: { render: () => null } }, './DraftingPdfPreview.vue': { default: { render: () => null } } }, globals: { structuredClone, Blob, URL, window: { addEventListener() {}, removeEventListener() {} }, document: { getElementById() { return null } } } })
  const host = renderer(), editor = vue.ref(), receipts = [], app = host.createApp({ setup: () => () => vue.h(loaded.default, { ref: editor, projectId: 'LIGHT-SAVE', locale: 'en', onSaved: value => receipts.push(value) }) })
  const control = attr => find(host.root, value => value.props?.[attr] !== undefined)
  app.mount(host.root)
  try {
    await settle(); await editor.value.locateQuestion('a', 'base'); await settle()
    await control('data-review-edit-question').props.onClick(); await settle()
    control('data-review-name-en').props['onUpdate:modelValue']('Changed fact'); await settle()
    await control('data-review-save-form').props.onClick(); await settle()
    await editor.value.save(); await settle()
    assert.equal(deltas, 1); assert.equal(fullSaves, 0); assert.equal(heads, 1); assert.equal(fullReads, scenario === 'mismatch' ? 1 : 0)
    if (scenario === 'conflict') { assert.equal(control('data-review-save').props.disabled, false); assert.match(text(host.root), /CAS conflict/); assert.match(text(host.root), /Changed fact/); return }
    const saved = receipts.at(-1)
    assert.equal(saved.graph.graph.variables[0].nameEn, 'Normalized fact')
    assert.deepEqual(saved.graph.graph.requirements, graph.graph.requirements)
    assert.deepEqual(saved.graph.graph.automaticDiscovery, graph.graph.automaticDiscovery)
    assert.equal(control('data-review-save').props.disabled, true)
    assert.match(text(host.root), /Normalized fact/)
  } finally { app.unmount() }
}

test('an existing-node edit saves only changed records and hydrates the normalized receipt without another full read', () => reviewScenario('success'))
test('a stale CAS keeps the local edit dirty without a full retry or refresh', () => reviewScenario('conflict'))
test('a mismatched delta receipt explicitly reads only the saved revision', () => reviewScenario('mismatch'))
