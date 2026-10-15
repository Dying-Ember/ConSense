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

async function mount(items) {
  const store = vue.reactive({ activeProjectId: 'project-a', locale: 'en', notify() {}, setBusy() {}, clearBusy() {}, captureLlmSelection: () => ({ profileId: null }) })
  const findings = [finding('F1', items), finding('F2', [evidence({ documentId: 'other', fileName: 'other.docx' })])]
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

test('canonical/fixed panels and PDF links keep their existing paths; incomplete source identity cannot open', async () => {
  const h = await mount([
    evidence({ canonicalEvidence: { source: 'synthetic-canonical' }, fileName: 'canonical.docx' }),
    evidence({ fixedBaselineEvidence: { source: 'synthetic-fixed' }, documentId: 'fixed:1', fileName: 'fixed.docx' }),
    evidence({ fileName: 'actual.pdf', pageNo: 'P7', nativePositions: undefined }),
    evidence({ fileName: 'missing-hash.docx', originalSourceSha256: undefined })
  ])
  try {
    await h.openFinding('F1')
    assert.ok(find(h.root, node => node.props?.class === 'canonical-panel'))
    assert.ok(find(h.root, node => node.props?.class === 'fixed-panel'))
    assert.equal(originalButton(h.root), undefined, 'Only an ordinary DOCX with a complete original identity enters the shared reader')
    const pdf = find(h.root, node => node.tag === 'a' && node.props?.href?.includes('#page=7'))
    assert.match(pdf.props.href, /^\/api\/vetting\/project-a\/sources\/42\/original\?/)
    assert.equal(new URL(pdf.props.href, 'http://localhost').searchParams.get('sourceRole'), 'tender')
    assert.equal(h.readCalls.length, 0)
  } finally { h.dispose() }
})

test('ordinary originals without a valid recorded role remain unavailable rather than adopting the current role', async () => {
  for (const sourceRole of [undefined, '', 'drawing', 'TENDER']) {
    const h = await mount([evidence({ sourceRole }), evidence({ fileName: 'actual.pdf', sourceRole })])
    try {
      await h.openFinding('F1')
      assert.ok(!originalButton(h.root), 'Missing or invalid recorded role cannot open a DOCX original')
      assert.ok(!find(h.root, node => node.tag === 'a' && node.props?.href?.includes('/original?')), 'Missing or invalid recorded role cannot download an original')
      assert.equal(h.readCalls.length, 0)
    } finally { h.dispose() }
  }
})
