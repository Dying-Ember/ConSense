const assert = require('node:assert/strict')
const path = require('node:path')
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle, deferred } = require('./helpers/render-controls.cjs')
const copy = value => JSON.parse(JSON.stringify(value))
const nativeWindow = new (require('jsdom').JSDOM)('').window
const ntt = { id: '41', sourceDocumentId: 41, docId: 'ntt', editionId: 'NTT-TEST', fileKey: 'NTT', fileName: 'TEST NTT.docx', format: 'docx', sourceSha256: 'a'.repeat(64), analysisRevision: 'analysis-41', analysisInputSha256: 'b'.repeat(64), analysisSha256: 'c'.repeat(64), evidenceStatus: 'bound', parseStatus: 'PARSED', canEdit: true }
const sct = { ...ntt, id: '42', sourceDocumentId: 42, docId: 'sct', editionId: 'SCT-TEST', fileKey: 'SCT', fileName: 'TEST SCT.docx', sourceSha256: 'd'.repeat(64), analysisRevision: 'analysis-42' }
const alternate = { ...sct, id: '43', sourceDocumentId: 43, editionId: 'SCT-OTHER-TEST', fileName: 'TEST other SCT.docx', sourceSha256: 'e'.repeat(64), analysisRevision: 'analysis-43' }
const scc = { ...sct, id: '44', sourceDocumentId: 44, docId: 'scc', editionId: 'SCC-PDF-TEST', fileKey: 'SCC', fileName: 'TEST SCC.pdf', format: 'pdf', sourceSha256: 'f'.repeat(64), analysisRevision: 'analysis-44', canEdit: false }
const material = { ...sct, id: '45', sourceDocumentId: 45, docId: 'communication', editionId: 'COMMUNICATION-TEST', fileKey: 'PROJECT' }
const item = { id: 'existing-ntt', nameZh: '既有变量', nameEn: 'Existing NTT question', docIds: ['41'], type: { kind: 'boolean' }, role: { kind: 'input' }, status: 'pending', options: [], sourceAnchors: [], sourceUnitIds: [], editTargets: [], relatedInputIds: [], relations: [], targets: [], evidence: [] }
const originalGraph = { kind: 'consense-template-graph-v1', graph: { variables: [item], events: [], sourceReviews: { '41': true }, nextId: 2, blank: false, guidanceMode: 'minimum' }, sources: [ntt], selectedDocuments: { NTT: '41' } }
const reading = source => ({ ...copy(source), paragraphs: [], blocks: [], guidanceNotes: [], sourceMembers: [], markerCandidates: [], groups: [], conditions: [], issues: [] })
const Modal = { props: ['open'], setup: (props, { slots }) => () => props.open ? vue.h('section', slots.default?.()) : null }
const Empty = { setup: () => () => null }
let sequence = 0

async function mountCase({ source = sct, readingPatch = {}, stale = false, locked = false } = {}) {
  const writes = [], confirms = [], adopted = []
  const head = { currentRevisionId: 'confirmed-1', draftRevisionId: 'draft-1', workingRevisionId: 'draft-1', current: null, draft: { id: 'draft-1', parentRevisionId: 'confirmed-1', status: 'DRAFT', graphSha256: 'TEST-graph', sourceStale: stale, graph: copy(originalGraph) }, sources: copy([ntt, source, alternate, scc, material]) }
  let saveBarrier
  const api = {
    reviewHead: async () => copy(head),
    reviewReading: async (_project, id) => ({ ...reading(head.sources.find(value => String(value.id) === id)), ...(id === String(source.id) ? readingPatch : {}) }),
    reviewSave: async (...args) => { writes.push(copy(args)); if (saveBarrier) await saveBarrier.promise; return { ...copy(head.draft), id: 'draft-' + (writes.length + 1), graph: copy(args[1]) } },
    reviewConfirm: async (...args) => { confirms.push(args); return copy(head.draft) },
    reviewCandidates: async (...args) => { adopted.push(args); return { variables: [] } },
    reviewPreview: async () => { throw Error('TEST preview not needed for edition selection') },
    reviewSource: async () => { throw Error('TEST source download not needed for edition selection') }
  }
  const loaded = loadVue(path.join(__dirname, '../src/components/DraftingSourceReview.vue'), {
    boundaries: { '@/api': { draftingApi: api }, '@/api/source-locations': { reviewSourceBindings: async () => ({ bindings: [] }) }, './AppModal.vue': { default: Modal }, './DraftingPdfPreview.vue': { default: Empty }, './DraftingPdfSccReference.vue': { default: Empty } },
    globals: { structuredClone, window: { getSelection: () => null }, document: nativeWindow.document, Element: nativeWindow.Element, URL }
  })
  const props = vue.reactive({ projectId: 'EDITION-CONTROL-' + ++sequence, locale: 'en', locked }), editor = vue.ref()
  const host = renderer(), app = host.createApp({ setup: () => () => vue.h(loaded.default, { ...props, ref: editor }) })
  app.mount(host.root)
  await settle()
  const control = attr => find(host.root, node => node.props?.[attr] !== undefined)
  const click = async attr => { const node = control(attr); assert.ok(node, attr); await node.props.onClick(); await settle() }
  const title = async label => { const node = find(host.root, value => value.tag === 'button' && text(value).trim() === label); assert.ok(node, label); await node.props.onClick(); await settle() }
  const select = async id => { await editor.value.selectSource(id); await settle() }
  return { host, app, props, editor, writes, confirms, adopted, control, click, title, select, barrier: value => { saveBarrier = value } }
}

;(async () => {
  const test = await mountCase()
  await test.select(sct.id)
  assert.ok(test.control('data-review-select-edition'), 'A bound uploaded SCT absent from the graph must have an explicit selection control')
  assert.equal(test.control('data-review-save').props.disabled, true, 'Reading an uploaded source does not modify the graph')
  assert.equal(test.writes.length, 0, 'Reading a source does not save')
  await test.click('data-review-select-edition')
  assert.equal(test.control('data-review-select-edition'), undefined)
  assert.equal(test.control('data-review-save').props.disabled, false)
  await test.click('data-review-save')
  const first = test.writes[0]
  assert.deepEqual(first[1].sources, [ntt, sct], 'Append the authoritative head source only, without reading paragraphs or replacing NTT')
  assert.deepEqual(first[1].selectedDocuments, { NTT: '41', SCT: '42' })
  assert.deepEqual(first[1].graph, originalGraph.graph, 'Existing nodes, events and source reviews remain exactly intact')
  assert.deepEqual(first.slice(2), ['confirmed-1', 'draft-1'], 'Saving retains optimistic current/draft revision checks')

  await test.select(alternate.id)
  await test.click('data-review-select-edition')
  await test.click('data-review-save')
  await test.select(sct.id)
  await test.click('data-review-select-edition')
  await test.click('data-review-save')
  assert.deepEqual(test.writes.at(-1)[1].sources, [ntt, sct, alternate], 'Selecting an existing edition does not duplicate or discard sources')
  assert.deepEqual(test.writes.at(-1)[1].selectedDocuments, { NTT: '41', SCT: '42' })
  assert.deepEqual(test.writes.at(-1).slice(2), ['confirmed-1', 'draft-3'])

  await test.select(scc.id)
  await test.click('data-review-select-edition')
  await test.click('data-review-save')
  assert.deepEqual(test.writes.at(-1)[1].sources.at(-1), scc, 'Selecting a PDF does not grant DOCX edit authority')
  assert.deepEqual(test.writes.at(-1)[1].selectedDocuments, { NTT: '41', SCT: '42', SCC: '44' })
  assert.deepEqual(test.writes.at(-1)[1].graph, originalGraph.graph)
  await test.select(material.id)
  assert.equal(test.control('data-review-select-edition'), undefined, 'A project communication cannot become a drafting template')
  assert.equal(test.confirms.length, 0)
  assert.equal(test.adopted.length, 0, 'Source selection neither confirms definitions nor adopts project values')
  test.app.unmount()

  for (const options of [
    { locked: true }, { stale: true },
    { source: { ...sct, evidenceStatus: 'missing' } },
    { source: { ...sct, evidenceStatus: 'invalid' } },
    ...['id', 'sourceDocumentId', 'docId', 'editionId', 'fileKey', 'format', 'canEdit', 'sourceSha256', 'analysisRevision', 'analysisInputSha256', 'analysisSha256', 'evidenceStatus'].map(key => ({ readingPatch: { [key]: key === 'canEdit' ? false : 'OTHER-IDENTITY' } }))
  ]) {
    const guarded = await mountCase(options)
    await guarded.select(sct.id)
    const button = guarded.control('data-review-select-edition')
    if (button) await button.props.onClick() // Exercise the handler even when a disabled DOM control would block a real click.
    await settle()
    guarded.props.locked = false
    await settle()
    await guarded.editor.value.save()
    assert.deepEqual(guarded.writes[0][1], originalGraph, 'Invalid, stale, mismatched or locked source selection leaves the graph intact: ' + JSON.stringify(options))
    assert.equal(guarded.confirms.length, 0)
    assert.equal(guarded.adopted.length, 0)
    guarded.app.unmount()
  }

  const guarded = await mountCase()
  await guarded.editor.value.locateQuestion(item.id, 'draft-1')
  await settle()
  await guarded.click('data-review-edit-question')
  await guarded.select(sct.id)
  guarded.control('data-review-name-en').props['onUpdate:modelValue']('Unsaved variable change')
  await settle()
  assert.equal(guarded.control('data-review-select-edition').props.disabled, true)
  await guarded.click('data-review-select-edition')
  assert.match(text(guarded.host.root), /Save or cancel the current variable edit first/)
  await guarded.title('Cancel edit')
  assert.equal(guarded.control('data-review-save').props.disabled, true, 'A blocked edition change does not leave a hidden graph edit')
  assert.equal(guarded.control('data-review-name-en').value, item.nameEn, 'Cancel edit restores the definition and keeps its editor open')
  assert.equal(guarded.control('data-review-save-form').props.disabled, true)
  await guarded.title('Split into two variables')
  find(guarded.host.root, node => node.props?.placeholder === 'English name').props['onUpdate:modelValue']('Unfinished split')
  await settle()
  assert.equal(guarded.control('data-review-select-edition').props.disabled, true)
  await guarded.click('data-review-select-edition')
  await guarded.title('Cancel this split')
  assert.equal(guarded.control('data-review-save').props.disabled, true, 'An unfinished split cannot append or select a source')
  await guarded.click('data-review-select-edition')
  await guarded.select(alternate.id)
  const barrier = deferred()
  guarded.barrier(barrier)
  const saving = guarded.editor.value.save()
  await settle()
  assert.equal(guarded.control('data-review-select-edition').props.disabled, true)
  await guarded.click('data-review-select-edition')
  barrier.resolve()
  await saving
  guarded.barrier(undefined)
  await guarded.editor.value.save()
  assert.deepEqual(guarded.writes.at(-1)[1].sources, [ntt, sct], 'A busy save cannot append the other edition')
  assert.deepEqual(guarded.writes.at(-1)[1].selectedDocuments, { NTT: '41', SCT: '42' })
  assert.deepEqual(guarded.writes.at(-1)[1].graph, originalGraph.graph)
  guarded.app.unmount()
  console.log('Drafting source edition controls passed')
})().catch(error => { console.error(error); process.exitCode = 1 })
