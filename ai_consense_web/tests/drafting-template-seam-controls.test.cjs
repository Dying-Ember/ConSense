const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const vue = require('vue'), piniaRuntime = require('pinia')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle } = require('./helpers/render-controls.cjs')
const copy = value => JSON.parse(JSON.stringify(value))
const source = { id: '41', sourceDocumentId: 41, docId: 'scc-word', editionId: 'SCC-docx-TEST', fileKey: 'SCC', fileName: 'TEST same-family Word.docx', format: 'docx', sourceSha256: 'a'.repeat(64), analysisRevision: 'TEST-old-analysis', evidenceStatus: 'invalid', parseStatus: 'PARSED', canEdit: true }
const span = { paragraphId: 'p1', paragraphSha256: 'b'.repeat(64), start: 3, end: 3, expectedText: '', operation: 'insert', sourceSha256: source.sourceSha256 }
const item = { id: 'bill-description', nameZh: '项目描述', nameEn: 'Project description', docIds: ['41'], type: { kind: 'text' }, role: { kind: 'input' }, status: 'confirmed', options: [], sourceAnchors: [{ docId: '41', sourceSha256: source.sourceSha256, text: 'Bills: ', native: { ...span, start: 0, end: 7, expectedText: 'Bills: ', operation: undefined } }], sourceUnitIds: [], editTargets: [{ docId: '41', sourceSha256: source.sourceSha256, action: 'insert', native: span, valueSourceId: 'bill-description', valueMode: 'single' }], relatedInputIds: [], relations: [], targets: [], evidence: [] }
const graph = { kind: 'consense-template-graph-v1', graph: { variables: [item], events: [], sourceReviews: {}, nextId: 1, blank: false, guidanceMode: 'minimum' }, sources: [source], selectedDocuments: { SCC: '41' } }
const revision = { id: 'review-1', parentRevisionId: null, status: 'DRAFT', graphSha256: 'TEST', graph, sourceStale: false }
const Modal = { props: ['open'], setup: (props, { slots }) => () => props.open ? vue.h('section', slots.default?.()) : null }
const globals = { structuredClone, Blob, URL, crypto: require('node:crypto').webcrypto, localStorage: { getItem: () => 'en', setItem() {} }, window: { addEventListener() {}, removeEventListener() {} }, document: { documentElement: {}, getElementById() { return null } } }

test('source review exposes invalid/missing revisions, keeps original reading and insertion details, and blocks bootstrap before a source refresh', async () => {
  let selected = copy(source), bootstraps = 0, replacements = 0, saves = []
  const auxiliary = { ...source, id: '99', fileName: 'TEST unselected same-family PDF.pdf', format: 'pdf', canEdit: false }
  const correspondence = { ...source, id: '100', fileKey: null, fileName: 'TEST correspondence.docx', evidenceStatus: 'missing' }
  const currentGraph = () => ({ ...copy(graph), sources: [copy(selected)] })
  const reading = () => ({ ...copy(selected), paragraphs: [{ id: 'p1', ordinal: 1, text: 'Bills: ', paragraphSha256: span.paragraphSha256, sourceSha256: selected.sourceSha256 }], blocks: [], guidanceNotes: [{ id: 'unclosed', text: '(Guidance Note: TEST original without closing bracket', captureComplete: false, sourceSyntaxKnown: true, sourceSyntaxComplete: false }], sourceMembers: [], markerCandidates: [{ id: 'human', symbol: '*', localSentenceObservation: { text: '*Mr/Ms' }, disposition: 'unknown' }], groups: [], conditions: [], issues: [{ code: 'TEST_SOURCE_REQUIRES_HUMAN', original: 'TEST preserve source issue' }] })
  const api = {
    reviewHead: async () => ({ currentRevisionId: null, draftRevisionId: revision.id, current: null, draft: { ...copy(revision), graph: currentGraph() }, sources: [copy(selected), auxiliary, correspondence] }),
    reviewReading: async () => reading(), reviewBootstrap: async () => { bootstraps++; throw Error('Unexpected bootstrap') },
    reviewSave: async (_project, proposed) => { saves.push(copy(proposed)); return { ...copy(revision), graph: copy(proposed) } }
  }
  const loaded = loadVue(path.join(__dirname, '../src/components/DraftingSourceReview.vue'), { boundaries: { '@/api/source-locations': { reviewSourceBindings: async () => { throw Error('Source location transport not provided by this fixture') } }, '@/api': { draftingApi: api }, './AppModal.vue': { default: Modal }, './DraftingPdfPreview.vue': { default: { render: () => null } } }, globals })
  const component = vue.ref(), host = renderer(), app = host.createApp({ setup: () => () => vue.h(loaded.default, { ref: component, projectId: 'TEST-EVIDENCE', locale: 'en', onReplace: () => replacements++ }) })
  app.mount(host.root)
  const control = (attr, value) => find(host.root, node => value === undefined ? node.props?.[attr] !== undefined : node.props?.[attr] === value)
  try {
    await settle()
    assert.match(text(control('data-source-evidence-status')), /invalid.*old analysis revision/)
    assert.match(text(control('data-source-evidence-block')), /unchanged original.*expired analysis revision/)
    assert.equal(control('data-review-confirm').props.disabled, true)
    await assert.rejects(component.value.prepareCandidates(), /Replace or re-upload/)
    assert.equal(bootstraps, 0)
    const original = control('data-source-page-review')
    assert.ok(original, 'Evidence failure does not hide the original-page reader')
    await control('data-source-replace').props.onClick(); assert.equal(replacements, 1)
    assert.match(text(control('data-source-unresolved')), /cannot yet authorize automatic edits[\s\S]*\*Mr\/Ms/)
    assert.match(text(host.root), /Source requires review/)
    selected.evidenceStatus = 'missing'; selected.analysisRevision = null; await component.value.refresh(false); await settle()
    assert.match(text(control('data-source-evidence-status')), /Current source evidence missing/)
    await assert.rejects(component.value.prepareCandidates(), /Replace or re-upload/)
    selected.evidenceStatus = 'bound'; selected.analysisRevision = 'TEST-current-analysis'; await component.value.refresh(false); await settle()
    assert.equal(control('data-source-evidence-block'), undefined, 'Unselected same-family PDF and non-template correspondence do not invalidate a bound selected Word')
    assert.match(text(control('data-source-evidence-status')), /Source evidence bound/)
    await control('data-review-question',item.id).props.onClick(); await settle(); await control('data-review-edit-question').props.onClick(); await settle()
    assert.equal(typeof control('data-target-action', 0).props.onChange, 'function')
    assert.match(text(host.root), /List size and contents require explicit adoption/)
    control('data-review-name-en').props['onUpdate:modelValue']('Human-reviewed Bill description'); await settle()
    await control('data-review-save-form').props.onClick(); await settle(); await control('data-review-save').props.onClick(); await settle()
    assert.equal(saves.length, 1)
    const target = saves[0].graph.variables[0].editTargets[0]
    assert.equal(target.action, 'insert'); assert.equal(target.valueSourceId, item.id)
    assert.deepEqual([target.native.start, target.native.end, target.native.expectedText, target.native.operation], [3, 3, '', 'insert'])
    assert.equal(bootstraps, 0)
  } finally { app.unmount() }
})

test('generation uses the exact confirmed selected source, disables PDF-only editions early, and never borrows a same-family Word', async () => {
  const word = { ...source, evidenceStatus: 'bound', analysisRevision: 'TEST-current' }
  const pdf = { ...word, id: '42', sourceDocumentId: 42, editionId: 'SCC-pdf-OTHER', format: 'pdf', fileName: 'TEST selected independent PDF.pdf', sourceSha256: 'c'.repeat(64), canEdit: false }
  let selectedId = '42', stale = false, generations = 0, plans = 0
  const current = () => ({ ...copy(revision), status: 'CONFIRMED', sourceStale: stale, graph: { ...copy(graph), selectedDocuments: { SCC: selectedId }, sources: [word, pdf] } })
  const api = {
    catalog: async () => ({ ruleVersion: 'TEST', graphRevisionId: revision.id, groups: [{ id: 'description', label: { en: 'Description' }, fields: [{ key: item.id, kind: 'text', label: { en: 'Bill description' } }] }] }), variables: async () => [],
    templates: async () => ['NTT', 'SCT', 'SCC'].map(key => ({ key, tag: 'ok', fileName: 'TEST misleading same-family Word.docx' })), inputs: async () => [], documents: async () => [], extractTrace: async () => null,
    reviewHead: async () => ({ currentRevisionId: revision.id, draftRevisionId: null, current: current(), draft: null, sources: [word, pdf] }),
    plan: async () => { plans++; return { actions: [{ id: 'TEST-insert', document: 'SCC', clause: 'Bill description', action: 'insert', inputKeys: [item.id] }], unresolved: [] } }, generate: async () => { generations++; return [] }
  }
  const loaded = loadVue(path.join(__dirname, '../src/views/DraftingView.vue'), { boundaries: {
    '@/api/source-locations': { reviewSourceBindings: async () => { throw Error('Source location transport not provided by this fixture') } }, '@/api': { draftingApi: api, setLlmProfileResolver() {}, captureLlmSelection: () => ({ profileId: 'TEST' }) }, pinia: piniaRuntime, 'vue-i18n': require('vue-i18n'),
    '@/components/AppModal.vue': { default: Modal }, '@/components/DraftingSourceReview.vue': { default: { render: () => null } }, '@/components/DraftingCandidateReview.vue': { default: { render: () => null } },
    '@/components/DraftingDocumentWorkspace.vue': { default: { render: () => null } }, '@/components/DraftingTemplatePreview.vue': { default: { render: () => null } }
  }, globals })
  const pinia = piniaRuntime.createPinia(); piniaRuntime.setActivePinia(pinia)
  const store = loaded.loadLocal(path.join(__dirname, '../src/stores/app.ts')).useAppStore(); store.switchProject('TEST-PDF-ONLY')
  const host = renderer(), app = host.createApp(loaded.default); app.use(pinia); app.use(loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n); app.mount(host.root)
  const button = label => find(host.root, node => node.tag === 'button' && text(node).trim() === label)
  const warning = () => find(host.root, node => node.props?.['data-generation-source-block'] !== undefined)
  try {
    await settle(); await button('3 Adopt and plan').props.onClick(); await settle()
    assert.match(text(host.root), /Insert at source anchor/, 'The plan card presents insertion as insertion, without downgrading it to Pending')
    assert.match(text(warning()), /TEST selected independent PDF\.pdf/)
    assert.match(text(warning()), /Another Word edition cannot be substituted/)
    assert.equal(button('Generate English drafts').props.disabled, true)
    const before = plans; await button('Generate English drafts').props.onClick(); await settle()
    assert.equal(generations, 0); assert.equal(plans, before, 'Unsupported source is rejected before saves, plan calls or generation')
    selectedId = '41'; store.switchProject('TEST-WORD-SELECTED'); await settle(); await button('3 Adopt and plan').props.onClick(); await settle()
    assert.equal(warning(), undefined, 'An unselected PDF does not block the selected editable Word')
    assert.equal(button('Generate English drafts').props.disabled, false)
    stale = true; store.switchProject('TEST-STALE-SELECTED'); await settle(); await button('3 Adopt and plan').props.onClick(); await settle()
    assert.match(text(warning()), /source binding changed/); assert.equal(button('Generate English drafts').props.disabled, true)
    assert.equal(generations, 0)
  } finally { app.unmount() }
})

test('insert actions keep their label in the actual template preview', async () => {
  const loaded = loadVue(path.join(__dirname, '../src/components/DraftingTemplatePreview.vue'), { boundaries: {
    '@/api/source-locations': { reviewSourceBindings: async () => { throw Error('Source location transport not provided by this fixture') } }, '@/api': { draftingApi: {} }, './DraftingPdfPreview.vue': { default: { render: () => null } }
  }, globals })
  const host = renderer(), app = host.createApp(loaded.default, { projectId: 'TEST-INSERT-PREVIEW', fileKey: 'SCC', locale: 'en', fields: [{ key: item.id, kind: 'text', label: { en: 'Bill description' } }], values: { [item.id]: 'TEST adopted description' }, variables: [], actions: [{ id: 'TEST-insert', document: 'SCC', clause: 'Bill description', action: 'insert', inputKeys: [item.id] }], selectedKey: item.id, selectedActionId: '', fieldStates: { [item.id]: 'manual' }, dirtyKeys: [], sourceAvailable: false })
  app.mount(host.root)
  try { await settle(); assert.match(text(host.root), /Insert at source anchor/); assert.match(text(host.root), /TEST adopted description/) } finally { app.unmount() }
})

test('a discovered exact multi-span amendment becomes confirmable only after the human adopts its action and placement', async () => {
  for (const mismatch of [null, 'unbound', 'scope-incomplete', 'stale-analysis', 'changed-native']) {
    const selected = { ...source, analysisRevision: 'TEST-current', evidenceStatus: 'bound' }
    const spans = [{ ...span, start: 0, end: 4, expectedText: 'Bill', operation: undefined }, { ...span, paragraphId: 'p2', paragraphSha256: 'd'.repeat(64), start: 0, end: 6, expectedText: 'second', operation: undefined }]
    const target = { id: 'discovered-target-0', docId: '41', sourceSha256: selected.sourceSha256, text: 'Bill\nsecond', native: { spans: copy(spans) }, nativeBinding: { status: mismatch === 'unbound' ? 'missing' : 'bound', sourceSha256: selected.sourceSha256, spans: copy(spans) }, observedRanges: [{ memberId: 'native-1', channel: 'text', start: 0, end: 4 }, { memberId: 'native-2', channel: 'text', start: 0, end: 6 }], action: 'check', proposedAction: 'replace', scopeComplete: false, sourceScopeId: 'exact-source-group', needsRebind: true, scopeReviewReason: 'The source spans are exact; choose one replacement placement before applying an amendment.', sourceScopeObservation: { id: 'exact-source-group', scopeComplete: mismatch !== 'scope-incomplete', memberRanges: [{ memberId: 'native-1', channel: 'text', start: 0, end: 4 }, { memberId: 'native-2', channel: 'text', start: 0, end: 6 }] }, branchId: '', valueSourceId: item.id }
    if (mismatch === 'changed-native') target.native.spans[0].expectedText = 'tampered'
    const question = { ...copy(item), status: 'pending', editTargets: [target] }
    const reviewGraph = { ...copy(graph), graph: { ...copy(graph.graph), variables: [question] }, sources: [{ ...selected, analysisRevision: mismatch === 'stale-analysis' ? 'TEST-old' : selected.analysisRevision }] }
    let saved = { ...copy(revision), graph: reviewGraph }, confirms = 0, writes = []
    const api = {
      reviewHead: async () => ({ currentRevisionId: null, draftRevisionId: saved.id, current: null, draft: copy(saved), sources: [copy(selected)] }),
      reviewReading: async () => ({ ...copy(selected), paragraphs: [], blocks: [], guidanceNotes: [], sourceMembers: [], markerCandidates: [], groups: [], conditions: [], issues: [] }),
      reviewSave: async (_project, proposed) => { writes.push(copy(proposed)); saved = { ...copy(saved), graph: copy(proposed) }; return copy(saved) },
      reviewConfirm: async () => { const accepted = saved.graph.graph.variables[0]; assert.equal(accepted.status, 'confirmed'); assert.equal(accepted.editTargets[0].needsRebind, false); assert.equal(accepted.editTargets[0].scopeComplete, true); confirms++; return copy(saved) }
    }
    const loaded = loadVue(path.join(__dirname, '../src/components/DraftingSourceReview.vue'), { boundaries: { '@/api/source-locations': { reviewSourceBindings: async () => { throw Error('Source location transport not provided by this fixture') } }, '@/api': { draftingApi: api }, './AppModal.vue': { default: Modal }, './DraftingPdfPreview.vue': { default: { render: () => null } } }, globals })
    const host = renderer(), app = host.createApp(loaded.default, { projectId: 'TEST-DISCOVERY-PLACEMENT-' + mismatch, locale: 'en' }); app.mount(host.root)
    const control = (attr, value) => find(host.root, node => value === undefined ? node.props?.[attr] !== undefined : node.props?.[attr] === value)
    const click = async attr => { await control(attr).props.onClick(); await settle() }
    try {
      await settle(); await control('data-review-question',question.id).props.onClick(); await settle(); await control('data-review-edit-question').props.onClick(); await settle()
      control('data-target-action', 0).props.onChange({ target: { value: 'replace' } }); await settle()
      await click('data-review-save-form'); await click('data-review-confirm-item')
      assert.equal(control('data-review-confirm').props.disabled, true, 'Choosing only the action leaves the placement gate unresolved')
      control('data-target-placement', 0).props.onChange({ target: { value: '1' } }); await settle()
      await click('data-review-save-form'); await click('data-review-confirm-item'); await click('data-review-save')
      const last = writes.at(-1).graph.variables[0].editTargets[0]
      if (mismatch) {
        assert.equal(last.needsRebind, true, mismatch + ' must retain its review flag')
        assert.equal(last.scopeComplete, false, mismatch + ' cannot be promoted to source-complete')
        assert.equal(control('data-review-confirm').props.disabled, true)
      } else {
        assert.equal(last.needsRebind, false)
        assert.equal(last.scopeComplete, true)
        assert.equal(last.action, 'replace'); assert.equal(last.proposedAction, 'replace')
        assert.equal(last.replacementPlacement.spanIndex, 1)
        assert.match(last.scopeReviewReason, /placement reviewed/)
        assert.deepEqual(last.native, target.native, 'The human placement decision never rewrites exact source spans')
        assert.equal(control('data-review-confirm').props.disabled, false)
        await click('data-review-confirm'); assert.equal(confirms, 1)
      }
    } finally { app.unmount() }
  }
})
