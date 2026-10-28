const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle, deferred } = require('./helpers/render-controls.cjs')
const copy = value => JSON.parse(JSON.stringify(value))
const source = { id: '841', sourceDocumentId: 841, docId: 'TEST-SCT', editionId: 'TEST-SCT-current', fileKey: 'SCT', fileName: 'TEST current SCT.docx', format: 'docx', sourceSha256: 'a'.repeat(64), analysisRevision: 'TEST-current-analysis', evidenceStatus: 'bound', parseStatus: 'PARSED', canEdit: true }
const variable = { id: 'TEST-restored', nameZh: '已核定义', nameEn: 'Reviewed definition', docIds: ['841'], type: { kind: 'text' }, role: { kind: 'input' }, status: 'pending', options: [], sourceAnchors: [], sourceUnitIds: [], editTargets: [], relatedInputIds: [], relations: [], targets: [], evidence: [] }
const graph = (variables = []) => ({ kind: 'consense-template-graph-v1', graph: { variables: copy(variables), events: [], sourceReviews: {}, nextId: 1, blank: false, guidanceMode: 'minimum' }, sources: [copy(source)], selectedDocuments: { SCT: source.id } })
const restoredGraph = () => ({ ...graph([variable]), graph: { ...graph([variable]).graph, baselineRestoration: { status: 'partial_requires_review', fieldIds: [variable.id], projectAnswersImported: false } } })
const revision = value => ({ id: 'TEST-review', parentRevisionId: null, status: 'DRAFT', graphSha256: 'TEST-hash', createdAt: '2026-10-28T00:00:00Z', sourceStale: false, graph: copy(value) })
const globals = { structuredClone, Blob, URL, crypto: require('node:crypto').webcrypto, localStorage: { getItem: () => 'en', setItem() {} }, window: { addEventListener() {}, removeEventListener() {} }, document: { documentElement: {}, getElementById() { return null } } }

function mountReview(project, overrides = {}, props = {}) {
  const calls = { heads: 0, bootstraps: 0, saves: [] }, locked = vue.ref(false), projectId = vue.ref(project)
  const api = {
    reviewHead: async () => { calls.heads++; return { currentRevisionId: null, draftRevisionId: null, current: null, draft: null, sources: [copy(source)] } },
    reviewReading: async () => ({ ...copy(source), paragraphs: [], blocks: [], guidanceNotes: [], sourceMembers: [], markerCandidates: [], groups: [], conditions: [], issues: [] }),
    reviewPreview: async () => new Blob(['TEST source preview']),
    reviewBootstrap: async () => { calls.bootstraps++; return { graph: restoredGraph(), sources: [copy(source)] } },
    reviewSave: async (_project, value, current, draft) => { calls.saves.push({ graph: copy(value), current, draft }); return revision(value) },
    ...overrides
  }
  const loaded = loadVue(path.join(__dirname, '../src/components/DraftingSourceReview.vue'), { boundaries: {
    '@/api/source-locations': { reviewSourceBindings: async () => { throw Error('TEST locations unavailable') } },
    '@/api': { draftingApi: api }, './AppModal.vue': { default: { render: () => null } }, './DraftingPdfPreview.vue': { default: { render: () => null } }
  }, globals })
  const component = vue.ref(), host = renderer(), app = host.createApp({ setup: () => () => vue.h(loaded.default, { ref: component, projectId: projectId.value, locale: 'en', locked: locked.value, ...props }) })
  app.mount(host.root)
  return { app, component, host, calls, locked, projectId, control: attr => find(host.root, node => node.props?.[attr] !== undefined) }
}

test('an empty manual review restores available definitions through the explicit button and saves a draft', async () => {
  const page = mountReview('TEST-PREPARE-EMPTY')
  try {
    await settle(); await page.component.value.ready()
    assert.equal(page.calls.bootstraps, 0, 'Opening or awaiting the review never starts restoration implicitly')
    const button = page.control('data-prepare-review')
    assert.equal(button.props.disabled, false)
    assert.match(text(page.control('data-empty-review')), /when available[\s\S]*does not call the model/)
    await button.props.onClick(); await settle()
    assert.equal(page.calls.bootstraps, 1)
    assert.equal(page.calls.saves.length, 1)
    assert.deepEqual([page.calls.saves[0].current, page.calls.saves[0].draft], [null, null])
    assert.equal(page.calls.saves[0].graph.graph.variables[0].id, variable.id)
    assert.equal(page.control('data-prepare-review'), undefined)
    assert.match(text(page.control('data-restored-edition-baseline')), /Some template definitions[\s\S]*no project answers/)
  } finally { page.app.unmount() }
})

test('preparation is single flight and ready waits for an already started bootstrap and save', async () => {
  const bootstrap = deferred(), save = deferred(); let bootstraps = 0, saves = 0
  const page = mountReview('TEST-PREPARE-SINGLE', {
    reviewBootstrap: async () => { bootstraps++; return await bootstrap.promise },
    reviewSave: async () => { saves++; return await save.promise }
  })
  try {
    await settle()
    const first = page.component.value.prepareCandidates(), second = page.component.value.prepareCandidates()
    assert.equal(first, second)
    let ready = false; const waiting = page.component.value.ready().then(() => { ready = true })
    await settle(); assert.equal(bootstraps, 1); assert.equal(ready, false)
    bootstrap.resolve({ graph: restoredGraph(), sources: [copy(source)] })
    await settle(); assert.equal(saves, 1); assert.equal(ready, false)
    save.resolve(revision(restoredGraph())); await Promise.all([first, second, waiting]); await settle()
    assert.equal(ready, true)
  } finally { page.app.unmount() }
})

test('an existing empty revision is retained rather than replaced with a bootstrap', async () => {
  const existing = revision(graph())
  const page = mountReview('TEST-PREPARE-EXISTING-EMPTY', { reviewHead: async () => ({ currentRevisionId: null, draftRevisionId: existing.id, workingRevisionId: existing.id, current: null, draft: existing, sources: [copy(source)] }) })
  try {
    await settle()
    assert.equal(page.control('data-prepare-review'), undefined)
    const result = await page.component.value.prepareCandidates()
    assert.equal(result.id, existing.id)
    assert.equal(page.calls.bootstraps, 0)
    assert.equal(page.calls.saves.length, 0)
  } finally { page.app.unmount() }
})

test('locked operations and even a pristine open new-variable form block preparation', async () => {
  const page = mountReview('TEST-PREPARE-EDIT-GUARDS')
  try {
    await settle(); page.locked.value = true; await settle()
    assert.equal(page.control('data-prepare-review').props.disabled, true)
    await assert.rejects(page.component.value.prepareCandidates(), /current operation first/)
    page.locked.value = false; await settle()
    await page.control('data-review-new-variable').props.onClick(); await settle()
    assert.equal(page.control('data-prepare-review').props.disabled, true)
    await assert.rejects(page.component.value.prepareCandidates(), /Save or cancel the current variable edit/)
    assert.equal(page.calls.bootstraps, 0)
  } finally { page.app.unmount() }
})

test('a save conflict keeps the restored local draft dirty and never refreshes over it', async () => {
  const page = mountReview('TEST-PREPARE-CAS-CONFLICT', { reviewSave: async () => { throw Error('TEST revision conflict; keep local edits') } })
  try {
    await settle(); await page.control('data-prepare-review').props.onClick(); await settle()
    assert.equal(page.calls.bootstraps, 1)
    assert.equal(page.calls.heads, 1)
    assert.match(text(page.host.root), /TEST revision conflict; keep local edits/)
    assert.match(text(page.host.root), /Reviewed definition/)
    assert.equal(page.control('data-review-save').props.disabled, false)
    assert.equal(page.control('data-prepare-review'), undefined, 'Restored nodes remain available without bootstrapping again')
  } finally { page.app.unmount() }
})

test('a bootstrap response for a previous project cannot overwrite the next project', async () => {
  const bootstrap = deferred(); let saves = 0
  const page = mountReview('TEST-PREPARE-OLD-PROJECT', { reviewBootstrap: async () => bootstrap.promise, reviewSave: async () => { saves++; throw Error('Unexpected old-project save') } })
  try {
    await settle(); const pending = page.component.value.prepareCandidates(); await settle()
    page.projectId.value = 'TEST-PREPARE-NEXT-PROJECT'; await settle()
    bootstrap.resolve({ graph: restoredGraph(), sources: [copy(source)] }); await pending; await settle()
    assert.equal(saves, 0)
    assert.equal(page.control('data-prepare-review').props.disabled, false)
    assert.doesNotMatch(text(page.host.root), /Reviewed definition/)
  } finally { page.app.unmount() }
})
