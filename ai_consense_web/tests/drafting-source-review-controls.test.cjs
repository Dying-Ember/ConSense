const assert = require('node:assert/strict')
const path = require('node:path')
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle, deferred } = require('./helpers/render-controls.cjs')
const copy = value => JSON.parse(JSON.stringify(value))
const nativeWindow = new (require('jsdom').JSDOM)('').window
let nativeSelection
const nativeGlobals = { getSelection: () => nativeSelection ? { rangeCount: 1, getRangeAt: () => nativeSelection } : null }
const source = { id: '41', sourceDocumentId: 41, docId: 'ntt', editionId: 'NTT-docx-TEST', fileKey: 'NTT', fileName: 'TEST ONLY source.docx', format: 'docx', sourceSha256: 'a'.repeat(64), parseStatus: 'PARSED', canEdit: true }
const paragraph = { id: 'p1', ordinal: 1, text: 'TEST ONLY exact source requirement', paragraphSha256: 'b'.repeat(64), sourceSha256: source.sourceSha256 }
const item = { id: 'question-1', nameZh: '测试要求', nameEn: 'TEST requirement', docIds: ['41'], type: { kind: 'boolean', zh: '是／否' }, role: { kind: 'input', zh: '项目事实' }, status: 'pending', options: [], sourceAnchors: [{ docId: '41', sourceSha256: source.sourceSha256, text: paragraph.text, native: { paragraphId: 'p1', paragraphSha256: paragraph.paragraphSha256, start: 0, end: paragraph.text.length, expectedText: paragraph.text } }], sourceUnitIds: [], editTargets: [], relatedInputIds: [], relations: [], targets: [], evidence: [] }
const graph = { kind: 'consense-template-graph-v1', graph: { variables: [item, { ...copy(item), id: 'unrelated-2', nameEn: 'UNRELATED graph question', status: 'confirmed' }], events: [], sourceReviews: {}, nextId: 1, blank: false, guidanceMode: 'minimum' }, sources: [source], selectedDocuments: { NTT: '41' } }
const revision = { id: 'draft-1', parentRevisionId: null, status: 'DRAFT', graphSha256: 'graph-test', graph, sourceStale: false }
const extraParagraphs = [{ ...paragraph, id: 'p2', ordinal: 2, text: 'Second exact source passage', paragraphSha256: 'c'.repeat(64) }, { ...paragraph, id: 'p3', ordinal: 3, text: 'Third exact source passage', paragraphSha256: 'd'.repeat(64) }, { ...paragraph, id: 'p4', ordinal: 4, text: 'Duration: ____ months.', paragraphSha256: 'e'.repeat(64) }]
const reading = { ...source, paragraphs: [paragraph, ...extraParagraphs], blocks: [], guidanceNotes: [], sourceMembers: [], markerCandidates: [], groups: [], conditions: [], issues: [] }
const writes = [], confirms = [], originals = [], historyReads = [], emitted = [], pending = new Map()
let saveBarrier
const api = {
  reviewHead: async project => pending.has(project) ? pending.get(project).promise : { currentRevisionId: 'current-1', draftRevisionId: revision.id, draft: copy(revision), current: null, sources: [source] },
  reviewReading: async () => copy(reading),
  reviewSave: async (...args) => { writes.push(copy(args)); if (saveBarrier) await saveBarrier.promise; return { ...copy(revision), id: 'draft-' + (writes.length + 1), graph: copy(args[1]) } },
  reviewConfirm: async (...args) => { confirms.push(args); return revision },
  reviewCandidates: async () => ({ variables: [] }),
  reviewRevisions: async () => [{ id: 'old-confirmed', status: 'CONFIRMED', sourceStale: false, createdAt: 'TEST time', graphSha256: 'old-sha' }],
  reviewRevision: async (...args) => { historyReads.push(args); return { ...copy(revision), id: 'old-confirmed', status: 'CONFIRMED', graph: { ...copy(graph), graph: { ...copy(graph.graph), variables: [{ ...copy(item), nameEn: 'HISTORICAL TEST requirement' }] } } } },
  reviewPreview: async (...args) => { originals.push(args); throw Error('TEST renderer unavailable') },
  reviewSource: async () => { throw Error('Unexpected PDF fallback') }
}
const Modal = { props: ['open'], setup: (props, { slots }) => () => props.open ? vue.h('section', slots.default?.()) : null }
const loaded = loadVue(path.join(__dirname, '../src/components/DraftingSourceReview.vue'), { boundaries: { '@/api': { draftingApi: api }, './AppModal.vue': { default: Modal }, './DraftingPdfPreview.vue': { default: { render: () => null } } }, globals: { structuredClone, window: nativeGlobals, document: {}, Element: nativeWindow.Element, URL } })
const props = vue.reactive({ projectId: 'SOURCE-A', locale: 'en' })
const host = renderer(), app = host.createApp({ setup: () => () => vue.h(loaded.default, { ...props, onDirty: value => emitted.push(value) }) })
app.mount(host.root)
const control = (attr, value) => find(host.root, node => value === undefined ? node.props?.[attr] !== undefined : node.props?.[attr] === value)
const click = async attr => { const node = control(attr); assert.ok(node, attr); await node.props.onClick(); await settle() }
const title = async label => { const node = find(host.root, node => node.tag === 'button' && text(node).trim() === label); assert.ok(node, label); await node.props.onClick(); await settle() }
const choose = async () => { await control('data-review-question-select').props.onChange({ target: { value: item.id } }); await settle() }
;(async () => {
  await settle(); await choose()
  assert.equal(control('data-review-confirm').props.disabled, true, 'Pending question cannot confirm the whole template')
  control('data-review-name-en').props['onUpdate:modelValue']('Unsaved source question'); await settle()
  assert.equal(control('data-review-save').props.disabled, true, 'A dirty question form cannot be silently omitted from graph Save')
  props.projectId = 'SOURCE-B'; await settle(); assert.equal(control('data-review-question-select').props.value, '')
  props.projectId = 'SOURCE-A'; await settle()
  assert.equal(control('data-review-name-en').value, 'Unsaved source question', 'A project switch preserves the unsaved source form')
  assert.equal(writes.length, 0)
  const nativeNodes = reading.paragraphs.map(paragraph => { const node = nativeWindow.document.createElement('p'); node.dataset.nativeId = paragraph.id; node.textContent = paragraph.text; return node })
  const nativeContainer = nativeWindow.document.createElement('section'); nativeNodes.forEach(node => nativeContainer.append(node))
  const sourceHost = find(host.root, node => node.props?.class === 'native-source'); sourceHost.querySelectorAll = () => nativeNodes
  nativeSelection = nativeWindow.document.createRange(); nativeSelection.setStart(nativeNodes[0].firstChild, 5); nativeSelection.setEnd(nativeNodes[1].firstChild, 4)
  sourceHost.props.onMouseup({ target: nativeNodes[1] }); await settle(); await title('Use as amendment target')
  nativeSelection = nativeWindow.document.createRange(); nativeSelection.setStart(nativeNodes[2].firstChild, 3); nativeSelection.setEnd(nativeNodes[2].firstChild, 8)
  sourceHost.props.onMouseup({ target: nativeNodes[2] }); await settle(); await click('data-target-append')
  const wordParagraph = nativeNodes[3], wordText = wordParagraph.firstChild, emptyPrefix = nativeWindow.document.createTextNode(''); wordParagraph.insertBefore(emptyPrefix, wordText)
  const frames = new Map(); let frameId = 0; nativeGlobals.requestAnimationFrame = callback => { frames.set(++frameId, callback); return frameId }; nativeGlobals.cancelAnimationFrame = id => frames.delete(id)
  nativeSelection = nativeWindow.document.createRange(); nativeSelection.setStart(emptyPrefix, 0); nativeSelection.setEnd(wordText, 0)
  assert.equal(nativeSelection.collapsed, false); assert.equal(nativeSelection.toString(), '')
  sourceHost.props.onMouseup({ target: wordParagraph }); assert.equal(frames.size, 1)
  nativeSelection = nativeWindow.document.createRange(); nativeSelection.setStart(wordText, 0); nativeSelection.setEnd(wordText, 8)
  sourceHost.props.onDblclick({ target: wordParagraph }); assert.equal(frames.size, 1, 'Double-click replaces the earlier pending mouseup capture')
  for (const callback of frames.values()) callback(); frames.clear(); await settle()
  assert.match(text(find(host.root, node => node.props?.class === 'selection-tools')), /Duration/); assert.doesNotMatch(text(host.root), /Reduce of empty array/)
  await title('Use as amendment target')
  await click('data-review-save-form'); await click('data-review-confirm-item'); saveBarrier = deferred(); const saving = click('data-review-save'); await settle()
  assert.equal(control('data-review-controls').props.disabled, true, 'A pending graph save freezes all editable source controls as one fieldset')
  await click('data-review-confirm-item'); assert.equal(writes.length, 1)
  saveBarrier.resolve(); await saving; saveBarrier = undefined
  assert.equal(control('data-review-controls').props.disabled, false)
  assert.equal(writes.length, 1)
  assert.equal(writes[0][0], 'SOURCE-A'); assert.equal(writes[0][2], 'current-1'); assert.equal(writes[0][3], 'draft-1')
  const spans = writes[0][1].graph.variables[0].editTargets[0].native.spans
  assert.deepEqual(spans.map(span => [span.paragraphId,span.start,span.end,span.expectedText]), [['p1',5,paragraph.text.length,paragraph.text.slice(5)],['p2',0,4,extraParagraphs[0].text.slice(0,4)],['p3',3,8,extraParagraphs[1].text.slice(3,8)]], 'Cross-paragraph drag and sparse append retain all exact native intervals without expanding omitted source text')
  const wordSpan = writes[0][1].graph.variables[0].editTargets[1].native
  assert.deepEqual([wordSpan.paragraphId,wordSpan.start,wordSpan.end,wordSpan.expectedText], ['p4',0,8,'Duration'], 'Double-click at UTF16 offset zero after an empty DOM prefix creates a real exact amendment target')
  assert.equal(writes[0][1].graph.variables[0].nameEn, 'Unsaved source question')
  assert.equal(writes[0][1].graph.variables[0].sourceAnchors[0].native.expectedText, paragraph.text)
  assert.equal(confirms.length, 0, 'Saving reviewed graph does not confirm it')
  assert.equal(control('data-review-confirm').props.disabled, false)
  await title('View relationships'); assert.match(text(control('data-review-graph-node', item.id)), /Unsaved source question/, 'Graph questions retain the business name saved in the editor'); await click('data-review-graph-node')
  assert.equal(control('data-review-graph-node', item.id).props['aria-pressed'], true, 'Selection stays inside the open graph')
  assert.match(control('data-review-graph-node', 'unrelated-2').props.class, /dim/)
  assert.match(control('data-review-graph-node', 'file:41').props.class, /selected/, 'The exact amendment destination is in the highlighted chain')
  await click('data-review-graph-only'); assert.equal(control('data-review-graph-node', 'unrelated-2'), undefined)
  await click('data-review-graph-clear'); assert.ok(control('data-review-graph-node', 'unrelated-2'))
  await click('data-review-graph-node'); await click('data-review-graph-locate'); assert.equal(control('data-review-graph-node'), undefined)
  assert.equal(writes.length, 1, 'Graph selection, impact filtering and locating source are read-only')
  await title('View original layout')
  assert.deepEqual(originals, [['SOURCE-A', '41', source.sourceSha256]], 'The selected edition uses source ID and bytes hash, never family preview fallback')
  assert.match(text(host.root), /TEST renderer unavailable/)
  assert.match(text(host.root), /TEST ONLY exact source requirement/, 'Renderer failure keeps native source available')
  await title('Graph versions'); await title('Review as a new draft')
  assert.deepEqual(historyReads, [['SOURCE-A', 'old-confirmed']], 'Metadata-only history must load its full graph from the revision endpoint')
  assert.match(text(host.root), /HISTORICAL TEST requirement/)
  assert.equal(writes.length, 1); assert.equal(confirms.length, 0, 'Restoring history creates an unsaved review without replacing confirmed state')
  assert.equal(control('data-review-save').props.disabled, false)
  const slow = deferred(); pending.set('SOURCE-SLOW', slow)
  props.projectId = 'SOURCE-SLOW'; await settle(); props.projectId = 'SOURCE-B'; await settle()
  slow.resolve({ currentRevisionId: null, draftRevisionId: null, current: null, draft: { ...revision, graph: { ...graph, graph: { ...graph.graph, variables: [{ ...item, nameEn: 'OBSOLETE PROJECT SECRET' }] } } }, sources: [source] }); await settle()
  assert.doesNotMatch(text(host.root), /OBSOLETE PROJECT SECRET/)
  assert.equal(writes.length, 1); assert.ok(emitted.includes(true)); app.unmount()
  console.log('PASS: mounted source review retains dirty forms across projects, saves exact source identity with optimistic revisions, separates confirmation and survives a renderer failure and late project response')
})().catch(error => { console.error(error); process.exitCode = 1 })
