/* Mounted Vetting controls and the shared-reader boundary; synthetic evidence is not source-quality acceptance. */
const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, text, find, settle } = require('./helpers/render-controls.cjs')

const nativePositions = [
  { kind: 'docx_native', sourceSha256: 'a'.repeat(64), partName: 'word/document.xml', partSha256: 'b'.repeat(64), nodePath: '/{urn:word}document[1]/{urn:word}body[1]/{urn:word}tbl[2]/{urn:word}tr[1]/{urn:word}tc[2]/{urn:word}p[1]' },
  { kind: 'docx_native', sourceSha256: 'a'.repeat(64), partName: 'word/header1.xml', partSha256: 'c'.repeat(64), nodePath: '/{urn:word}hdr[1]/{urn:word}p[3]' }
]
const evidence = (overrides = {}) => ({ side: 'source', documentId: 42, fileKey: 'SCC', fileName: 'same-wording.docx', pageNo: null,
  quote: 'Repeated wording exists elsewhere.', located: true, sourceHash: 'registered-source-hash', originalSourceSha256: 'a'.repeat(64), sourceRole: 'tender', nativePositions, ...overrides })
const finding = (code, items) => ({ code, status: 'Open', group: 'reference', scope: 'intra', severity: 'low', types: [], title: { en: code }, body: {}, impact: {}, suggestion: {}, evidence: items,
  runId: 'run-1', generatedRevisionSha256: 'generated', sourceSnapshotSha256: 'source', fileKey: 'SCC', pageNo: null })
const plain = value => JSON.parse(JSON.stringify(value))

async function mount(items, findingOverrides = {}) {
  const store = vue.reactive({ activeProjectId: 'project-a', locale: 'en', notify() {}, setBusy() {}, clearBusy() {}, captureLlmSelection: () => ({ profileId: null }) })
  const findings = [{ ...finding('F1', items), ...findingOverrides }, finding('F2', [evidence({ documentId: 'other', fileName: 'other.docx' })])]
  const readCalls = []
  const loaded = loadVue(path.join(__dirname, '../src/views/VettingView.vue'), { boundaries: {
    '@noble/hashes/sha256': require('@noble/hashes/sha256'),
    'vue-i18n': { useI18n: () => ({ t: key => key, locale: vue.ref('en') }) },
    'vue-router': { useRouter: () => ({ push: async () => undefined }) },
    '@/stores/app': { useAppStore: () => store },
    '@/api/client': { ApiError: class ApiError extends Error {} },
    '@/api': { vettingApi: {
      files: async () => [], findings: async () => findings,
      metrics: async () => ({ total: 2, reference: 2, conflict: 0, language: 0, risk: 0, crossFile: 0 }),
      latestRunProgress: async () => null,
      evidence: async (_project, code) => ({ title: code, located: true, items: findings.find(item => item.code === code).evidence.map(item => ({ ...item, code: item.fileKey, text: item.quote })) })
    } },
    '@/components/AdviceSourceReader.vue': { default: { props: ['projectId', 'citation', 'sourceModule'],
      setup(props) { readCalls.push(props); return () => vue.h('div', { class: 'shared-original-reader' }, props.citation.fileLabel) } } },
    '@/components/CanonicalEvidencePanel.vue': { default: { render: () => vue.h('div', { class: 'canonical-panel' }) } },
    '@/components/FixedBaselineEvidencePanel.vue': { default: { render: () => vue.h('div', { class: 'fixed-panel' }) } }
  }, globals: { URLSearchParams, window: { setTimeout, clearTimeout } } })
  const h = renderer(), app = h.createApp(loaded.default)
  app.mount(h.root); await settle()
  const openFinding = async code => {
    const control = find(h.root, node => node.tag === 'button' && String(node.props?.class).split(' ').includes('finding') && text(node).includes(code))
    assert.ok(control, `Finding ${code} is visible`); control.props.onClick(); await settle()
  }
  return { ...h, store, readCalls, openFinding, dispose: () => app.unmount() }
}
const originalButton = root => find(root, node => node.tag === 'button' && text(node) === 'vetting.drawer.openOriginal')
const reader = root => find(root, node => node.props?.class === 'shared-original-reader')

test('finding evidence keeps hashes in one optional layer and leaves original and review actions visible', async () => {
  const packetId = 'packet:' + 'd'.repeat(64), snapshot = 'e'.repeat(64)
  const h = await mount([evidence({ packetId, packetSourceSnapshotSha256: snapshot })])
  const visible = node => node.tag === 'details' && !node.props?.open
    ? (node.children ?? []).filter(child => child.tag === 'summary').map(visible).join(' ')
    : node.text || (node.children ?? []).map(visible).join(' ')
  try {
    await h.openFinding('F1')
    const drawer = find(h.root, node => node.tag === 'aside' && String(node.props?.class).includes('finding-drawer'))
    assert.ok(visible(drawer).includes('same-wording.docx')); assert.ok(visible(drawer).includes('Repeated wording exists elsewhere.'))
    assert.ok(!visible(drawer).includes(packetId)); assert.ok(!visible(drawer).includes(snapshot))
    assert.ok(text(drawer).includes(packetId)); assert.ok(text(drawer).includes(snapshot))
    const technical = find(drawer, node => node.tag === 'details' && String(node.props?.class).includes('evidence-technical-details'))
    assert.ok(technical); assert.ok(!technical.props.open)
    for (let parent = technical.parent; parent; parent = parent.parent) assert.notEqual(parent.tag, 'details')
    for (const control of [originalButton(drawer), find(drawer, node => node.tag === 'button' && text(node) === 'vetting.review.save')]) {
      assert.ok(control)
      for (let parent = control.parent; parent; parent = parent.parent) assert.notEqual(parent.tag, 'details', 'Business actions never require technical expansion')
    }
  } finally { h.dispose() }
})

test('ordinary Vetting DOCX opens the existing reader with exact identity and every original native position', async () => {
  const h = await mount([evidence()])
  try {
    await h.openFinding('F1')
    const open = originalButton(h.root); assert.ok(open, 'DOCX has an in-app original-reading control')
    open.props.onClick(); await settle()
    assert.ok(reader(h.root))
    assert.equal(h.readCalls[0].projectId, 'project-a')
    assert.equal(h.readCalls[0].sourceModule, 'vetting', 'The shared reader uses Vetting authorization')
    assert.deepEqual(plain(h.readCalls[0].citation), {
      evidenceId: 'F1', documentId: '42', fileLabel: 'same-wording.docx', pageNo: '', anchor: '', content: 'Repeated wording exists elsewhere.', score: 0,
      sourceHash: 'registered-source-hash', originalSourceSha256: 'a'.repeat(64), sourceRole: 'tender', nativePositions
    }, 'Evidence API normalization retains part/SHA/Clark paths without a canonical pointer or invented Word page')
    await h.openFinding('F2')
    assert.equal(reader(h.root), undefined, 'A different finding releases the old source reader')
    originalButton(h.root).props.onClick(); await settle()
    assert.equal(h.readCalls[1].citation.documentId, 'other')
    h.store.activeProjectId = 'project-b'; await settle()
    assert.equal(reader(h.root), undefined, 'Changing project discards the old source selection')
  } finally { h.dispose() }
})

test('source close controls release selection; legacy DOCX remains readable without fabricated location', async () => {
  const h = await mount([evidence({ nativePositions: undefined, located: false })])
  try {
    await h.openFinding('F1'); originalButton(h.root).props.onClick(); await settle()
    assert.equal(h.readCalls[0].citation.nativePositions, undefined)
    const modal = find(h.root, node => String(node.props?.class).includes('modal-backdrop') && reader(node))
    find(modal, node => node.tag === 'button' && node.props?.['aria-label'] === 'common.close').props.onClick(); await settle()
    assert.equal(reader(h.root), undefined, 'The source modal close control releases the reader')
    originalButton(h.root).props.onClick(); await settle()
    const drawer = find(h.root, node => node.tag === 'aside')
    find(drawer, node => node.tag === 'button' && node.props?.['aria-label'] === 'common.close').props.onClick(); await settle()
    assert.equal(reader(h.root), undefined, 'Closing the finding also closes its source')
  } finally { h.dispose() }
})

test('canonical DOCX opens the guarded reader while retaining secondary download, fixed panels and PDF links', async () => {
  const canonicalEvidence = { addressKind: 'canonical_adoption_unit', projectId: 'project-a', module: 'vetting', documentId: 42,
    canonicalChunkId: 'source-unit', originalSourceSha256: 'a'.repeat(64), javaParsedSourceHash: 'registered-source-hash' }
  const h = await mount([
    evidence({ canonicalEvidence, fileName: 'canonical.docx', clauseId: 'SCC 4.301', anchor: 'native/location' }),
    evidence({ fixedBaselineEvidence: { source: 'synthetic-fixed' }, documentId: 'fixed:1', fileName: 'fixed.docx' }),
    evidence({ fileName: 'actual.pdf', pageNo: 'P7', nativePositions: undefined }),
    evidence({ fileName: 'missing-hash.docx', originalSourceSha256: undefined })
  ])
  try {
    await h.openFinding('F1')
    assert.ok(find(h.root, node => node.props?.class === 'canonical-panel'))
    assert.ok(find(h.root, node => node.props?.class === 'fixed-panel'))
    const open = originalButton(h.root)
    assert.ok(open, 'Canonical DOCX has the same first-level original-reading action')
    for (let parent = open.parent; parent; parent = parent.parent) assert.notEqual(parent.tag, 'details')
    assert.ok(text(h.root).includes('SCC 4.301'), 'The readable clause survives evidence detail normalization')
    const canonical = find(h.root, node => node.tag === 'a' && node.props?.download === 'canonical.docx')
    assert.ok(canonical, 'The exact canonical original remains available as a secondary download')
    assert.equal(canonical.props.target, undefined, 'A non-PDF original does not open a new browsing tab')
    assert.equal(text(canonical).trim(), 'vetting.drawer.downloadOriginal', 'The download has a visible text label')
    const canonicalUrl = new URL(canonical.props.href, 'http://localhost')
    assert.equal(canonicalUrl.searchParams.get('sourceHash'), 'registered-source-hash')
    assert.equal(canonicalUrl.searchParams.get('originalSourceSha256'), 'a'.repeat(64))
    assert.equal(canonicalUrl.searchParams.get('sourceRole'), 'tender')
    assert.ok(!find(h.root, node => node.tag === 'a' && node.props?.download === 'fixed.docx'), 'Fixed originals keep their separate evidence route')
    const pdf = find(h.root, node => node.tag === 'a' && node.props?.href?.includes('#page=7'))
    assert.match(pdf.props.href, /^\/api\/vetting\/project-a\/sources\/42\/original\?/)
    assert.equal(new URL(pdf.props.href, 'http://localhost').searchParams.get('sourceRole'), 'tender')
    assert.equal(pdf.props.target, '_blank'); assert.equal(pdf.props.download, undefined)
    assert.equal(text(pdf).trim(), 'vetting.drawer.openOriginal')
    open.props.onClick(); await settle()
    assert.equal(h.readCalls.length, 1)
    assert.equal(h.readCalls[0].sourceModule, 'vetting')
    assert.deepEqual(plain(h.readCalls[0].citation), {
      evidenceId: 'F1', documentId: '42', fileLabel: 'canonical.docx', pageNo: '', anchor: 'native/location', content: 'Repeated wording exists elsewhere.', score: 0,
      sourceHash: 'registered-source-hash', originalSourceSha256: 'a'.repeat(64), sourceRole: 'tender', nativePositions, canonicalEvidence: [canonicalEvidence]
    }, 'The existing source loader receives the recorded original identity and canonical address without substitution')
  } finally { h.dispose() }
})

test('ordinary and canonical originals without a valid recorded role remain unavailable', async () => {
  for (const sourceRole of [undefined, '', 'drawing', 'TENDER']) {
    const h = await mount([evidence({ sourceRole }), evidence({ canonicalEvidence: { source: 'synthetic-canonical' }, sourceRole }), evidence({ fileName: 'actual.pdf', sourceRole })])
    try {
      await h.openFinding('F1')
      assert.ok(!originalButton(h.root), 'Missing or invalid recorded role cannot open a DOCX original')
      assert.ok(!find(h.root, node => node.tag === 'a' && node.props?.href?.includes('/original?')), 'Missing or invalid recorded role cannot download an original')
      assert.equal(h.readCalls.length, 0)
    } finally { h.dispose() }
  }
})

test('rule provenance does not require a model packet and unresolved location remains visible', async () => {
  const h = await mount([evidence({ located: false, nativePositions, clauseId: 'SCC 4.301' })], { source: 'rule' })
  try {
    await h.openFinding('F1')
    const drawer = find(h.root, node => node.tag === 'aside')
    assert.ok(text(drawer).includes('vetting.coverage.ruleEvidence'))
    assert.ok(!text(drawer).includes('vetting.coverage.legacyPacketUnknown'))
    assert.ok(!find(drawer, node => node.tag === 'details' && String(node.props?.class).includes('evidence-technical-details')), 'No model packet is invented for rule evidence')
    assert.ok(text(drawer).includes('vetting.drawer.notLocated'), 'Original reading never promotes unresolved canonical evidence')
    const open = originalButton(drawer); assert.ok(open)
    for (let parent = open.parent; parent; parent = parent.parent) assert.notEqual(parent.tag, 'details')
    open.props.onClick(); await settle()
    assert.deepEqual(plain(h.readCalls[0].citation.nativePositions), nativePositions)
    assert.ok(text(drawer).includes('vetting.drawer.notLocated'))
  } finally { h.dispose() }
})

test('model packets and historical unknown provenance keep their separate messages', async () => {
  for (const source of ['llm', undefined]) {
    const h = await mount([evidence()], { source })
    try {
      await h.openFinding('F1')
      const drawer = find(h.root, node => node.tag === 'aside')
      assert.ok(text(drawer).includes('vetting.coverage.legacyPacketUnknown'))
      assert.ok(!text(drawer).includes('vetting.coverage.ruleEvidence'))
    } finally { h.dispose() }
  }
  const h = await mount([evidence({ packetId: 'actual-model-packet', packetSourceSnapshotSha256: 'e'.repeat(64) })], { source: 'llm' })
  try {
    await h.openFinding('F1')
    const drawer = find(h.root, node => node.tag === 'aside')
    assert.ok(text(drawer).includes('actual-model-packet'))
    assert.ok(!text(drawer).includes('vetting.coverage.ruleEvidence'))
    assert.ok(!text(drawer).includes('vetting.coverage.legacyPacketUnknown'))
  } finally { h.dispose() }
})
