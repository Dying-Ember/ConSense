// Single-value adoption must not rehydrate unchanged originals/graph/documents; all APIs are local stubs.
const assert = require('node:assert/strict'), path = require('node:path'), vue = require('vue'), piniaRuntime = require('pinia')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle, deferred } = require('./helpers/render-controls.cjs')
const copy = value => JSON.parse(JSON.stringify(value)), label = en => ({ en, zhHans: en, zhHant: en })
let projectNumber = 0
async function harness(change) {
  let written = false, serial = 0, variablesReady = false
  const reads = [], patches = [], planStates = [], block = change === 'ordered' ? deferred() : null
  const fields = [{ key: 'foundation', kind: 'boolean', label: label('Foundation') }, { key: 'notes', kind: 'text', label: label('Notes') }]
  const source = { id: '41', sourceDocumentId: 41, fileKey: 'NTT', fileName: 'SYNTHETIC original.docx', format: 'docx', canEdit: true, evidenceStatus: 'bound', sourceSha256: 'a'.repeat(64) }
  const oldValues = () => [{ key: 'foundation', value: 'true', kind: 'boolean', label: label('Foundation'), graphRevisionId: 'revision-1', fieldDefinitionHash: 'hash-foundation', confirmed: true, reviewRequired: true, candidates: [] }, { key: 'notes', value: 'Saved note', kind: 'text', label: label('Notes'), graphRevisionId: 'revision-1', fieldDefinitionHash: 'hash-notes', confirmed: true, reviewRequired: false, candidates: [] }]
  const server = oldValues()
  if (change === 'legacy') server.forEach(item => { delete item.graphRevisionId; delete item.fieldDefinitionHash })
  if (change === 'identity') { fields[0] = { key: 'contractTitle', kind: 'text', label: label('Identity') }; server[0].key = 'contractTitle'; delete server[0].graphRevisionId; delete server[0].fieldDefinitionHash }
  const graph = { kind: 'consense-template-graph-v1', graph: { variables: [], events: [], sourceReviews: {} }, sources: [source], selectedDocuments: { NTT: '41' } }
  const revision = { id: 'revision-1', parentRevisionId: 'parent-1', status: 'CONFIRMED', graphSha256: 'graph-sha', graph, sourceStale: false }
  const read = name => { reads.push(name) }
  const api = {
    catalog: async () => { read('catalog'); return { ruleVersion: 'review-graph-sha', ...(change === 'legacy' ? {} : { graphRevisionId: 'revision-1' }), groups: [{ id: 'facts', label: label('Facts'), fields }] } },
    variables: async () => {
      read('variables'); const result = copy(server)
      if (written && block) await block.promise
      if (written && change === 'revision') result[0].graphRevisionId = 'revision-2'
      if (written && change === 'keys') result.push({ ...result[1], key: 'new-key', fieldDefinitionHash: 'new-hash' })
      if (written && change === 'definition') result[0].fieldDefinitionHash = 'changed-hash'
      variablesReady = true; return result
    },
    documents: async () => { read('documents'); return [{ fileKey: 'NTT', title: 'SYNTHETIC NTT', generated: true, content: 'Saved immutable text', revisionId: 'document-1', docxSha256: 'docx-sha', sourceSha256: source.sourceSha256, snapshotId: 'old-snapshot', stale: false }] },
    templates: async () => { read('templates'); return ['NTT', 'SCT', 'SCC'].map(key => ({ key, tag: 'ok', fileName: key + '.docx' })) },
    inputs: async () => { read('inputs'); return [] }, extractTrace: async () => { read('trace'); return null },
    reviewHead: async () => { read('head'); return change === 'legacy' ? { currentRevisionId: null, draftRevisionId: null, current: null, draft: null, sources: [source] } : { currentRevisionId: revision.id, draftRevisionId: revision.parentRevisionId, current: copy(revision), draft: null, sources: [source] } },
    plan: async () => {
      read('plan'); planStates.push({ written, variablesReady, value: server[0].value })
      if (written && change === 'plan-failure') throw Error('SYNTHETIC post-write plan failure')
      const result = { ruleVersion: 'graph-sha', graphRevisionId: 'revision-1', reviewStatus: 'CONFIRMED', evidenceRevision: 'evidence-1', sourceIdentities: [{ id: '41', sourceSha256: source.sourceSha256 }], preview: false, snapshotId: 'snapshot-' + serial, savedSnapshotId: 'snapshot-' + serial, inputValues: { foundation: server[0].value }, effectiveValues: { foundation: server[0].value }, derived: { threshold: server[0].value === 'true' ? 'false' : 'true' }, actions: [], unresolved: [] }
      if (written && change === 'plan-revision') result.graphRevisionId = 'revision-2'
      if (written && change === 'plan-hash') result.ruleVersion = 'different-graph-sha'
      if (written && change === 'plan-status') result.reviewStatus = 'DRAFT'
      if (written && change === 'source') result.sourceIdentities[0].sourceSha256 = 'b'.repeat(64)
      if (written && change === 'evidence') result.evidenceRevision = 'evidence-2'
      if (written && change === 'stale') result.unresolved.push({ id: 'graph_source_changed', kind: 'GraphIssue', inputKeys: [], message: label('Source changed') })
      return result
    },
    updateVariable: async (_project, key, patch) => {
      patches.push(copy(patch)); const item = server.find(value => value.key === key)
      if (change !== 'legacy' && change !== 'identity') { assert.equal(patch.graphRevisionId, item.graphRevisionId); assert.equal(patch.fieldDefinitionHash, item.fieldDefinitionHash) }
      if (patch.value !== undefined) item.value = patch.value
      item.reviewRequired = false; item.manuallyEdited = true; item.confirmed = true; written = true; variablesReady = false; serial++
      return copy(item)
    }, automationLatest: async () => null
  }
  const Modal = { props: ['open'], setup: (props, { slots }) => () => props.open ? vue.h('section', slots.default?.()) : null }
  const loaded = loadVue(path.join(__dirname, '../src/views/DraftingView.vue'), { boundaries: {
    '@/api': { draftingApi: api, setLlmProfileResolver() {}, captureLlmSelection: () => ({ profileId: 'TEST' }) }, '@/api/source-locations': { reviewSourceBindings: async () => ({ bindings: [] }) }, pinia: piniaRuntime, 'vue-i18n': require('vue-i18n'),
    '@/components/AppModal.vue': { default: Modal }, './AppModal.vue': { default: Modal }, '@/components/DraftingDocumentWorkspace.vue': { default: { render: () => null } }, '@/components/DraftingTemplatePreview.vue': { default: { render: () => null } }, './DraftingPdfPreview.vue': { default: { render: () => null } }
  }, globals: { structuredClone, Blob, URL, crypto: require('node:crypto').webcrypto, localStorage: { getItem: () => 'en', setItem() {} }, window: { addEventListener() {}, removeEventListener() {} }, document: { documentElement: {}, getElementById() { return null } } } })
  const id = 'ADOPT-REFRESH-' + ++projectNumber
  const state = loaded.loadLocal(path.join(__dirname, '../src/drafting/state.ts'))
  state.projectDrafts.set(id, { values: {}, baseline: {}, view: { step: 'variables', activeFile: 'NTT' } })
  const pinia = piniaRuntime.createPinia(); piniaRuntime.setActivePinia(pinia)
  const store = loaded.loadLocal(path.join(__dirname, '../src/stores/app.ts')).useAppStore(); store.switchProject(id)
  const host = renderer(), app = host.createApp(loaded.default); app.use(pinia); app.use(loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n); app.mount(host.root); await settle()
  const get = (attr, value) => find(host.root, element => value === undefined ? element.props?.[attr] !== undefined : element.props?.[attr] === value)
  const button = name => find(host.root, element => element.tag === 'button' && text(element).trim() === name)
  const adopt = () => { const field = get('data-variable', fields[0].key); assert.ok(field, change + ' selected field'); return find(field, element => element.tag === 'button' && text(element).trim() === 'Retain this value and complete review').props.onClick() }
  const reset = () => { reads.length = 0; planStates.length = 0 }
  return { app, host, get, button, adopt, reads, patches, planStates, block, reset, store }
}
;(async () => {
  const h = await harness(); h.reset()
  try {
    const notes = h.get('id', 'input-notes'); notes.props.onInput({ target: { value: 'Unsaved independent note' } }); await settle()
    h.get('data-manual-note', 'notes').props['onUpdate:modelValue']('Preserve unrelated reason'); await settle()
    await h.adopt(); await settle()
    assert.deepEqual(h.reads, ['variables', 'plan'], 'A same-revision single adoption must read current values then plan without reloading graph/original/documents/catalogue')
    assert.equal(h.patches.length, 1)
    assert.equal(h.patches[0].suggestionSnapshot.value, 'true')
    assert.equal(h.patches[0].suggestionSnapshot.graphRevisionId, 'revision-1')
    assert.equal(h.patches[0].suggestionSnapshot.fieldDefinitionHash, 'hash-foundation')
    assert.equal(h.get('id', 'input-notes').value, 'Unsaved independent note')
    assert.equal(h.get('data-manual-note', 'notes').value, 'Preserve unrelated reason')
    await h.button('4 Preview and export').props.onClick(); await settle()
    assert.match(text(h.host.root), /previous draft; regenerate/, 'Conservative stale state remains visible and disables old-document actions')
  } finally { h.app.unmount() }
  const ordered = await harness('ordered'); ordered.reset()
  try {
    const pending = ordered.adopt(); await settle()
    assert.deepEqual(ordered.reads, ['variables'], 'Plan must not race candidate restoration in the variables request')
    assert.equal(ordered.get('data-save-input', 'notes').props.disabled, true, 'Global protection remains while fresh values are pending')
    ordered.block.resolve(); await pending; await settle()
    assert.deepEqual(ordered.reads, ['variables', 'plan']); assert.equal(ordered.planStates[0].variablesReady, true)
  } finally { ordered.app.unmount() }
  const saved = await harness(); saved.reset()
  try {
    saved.get('id', 'input-notes').props.onInput({ target: { value: 'New explicitly adopted note' } }); await settle()
    saved.get('data-manual-note', 'notes').props['onUpdate:modelValue']('Checked source'); await settle()
    await saved.get('data-save-input', 'notes').props.onClick(); await settle()
    assert.deepEqual(saved.reads, ['variables', 'plan'], 'Save this variable uses the same guarded narrow refresh')
    assert.equal(saved.patches[0].value, 'New explicitly adopted note'); assert.equal(saved.patches[0].note, 'Checked source'); assert.equal(saved.patches[0].fieldDefinitionHash, 'hash-notes')
  } finally { saved.app.unmount() }
  for (const change of ['revision', 'keys', 'definition', 'plan-revision', 'plan-hash', 'plan-status', 'source', 'evidence', 'stale', 'legacy', 'identity']) {
    const blocked = await harness(change); blocked.reset()
    try { await blocked.adopt(); await settle(); for (const required of ['catalog', 'documents', 'templates', 'inputs', 'trace', 'head']) assert.ok(blocked.reads.includes(required), change + ' must use the original full refresh'); assert.equal(blocked.patches.length, 1, 'Fallback never retries the adopted write') } finally { blocked.app.unmount() }
  }
  const failed = await harness('plan-failure'); failed.reset()
  try { await failed.adopt(); await settle(); assert.equal(failed.patches.length, 1); assert.match(text(failed.host.root), /post-write plan failure/); assert.equal(failed.reads.includes('documents'), false, 'A read error cannot resubmit the successful write') } finally { failed.app.unmount() }
  const switched = await harness('ordered'); switched.reset()
  try {
    const pending = switched.adopt(); await settle(); switched.store.switchProject('OTHER-PROJECT'); await settle()
    switched.block.resolve(); await pending; await settle()
    assert.equal(switched.planStates.length, 0, 'A late adoption refresh cannot initialize or request a plan for another project')
  } finally { switched.app.unmount() }
  console.log('PASS: adopt/saveInput read variables then plan only; 11 changed/unsupported contexts keep full fallback, snapshots/dirty values survive, old drafts stay stale, failed reads never retry writes and switched projects reject late refreshes')
})().catch(error => { console.error(error); process.exitCode = 1 })
