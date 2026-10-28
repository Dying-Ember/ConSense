/* Mount the real Vetting view and canonical panel; only HTTP and application boundaries are controlled. */
const assert = require('node:assert/strict')
const path = require('node:path')
const crypto = require('node:crypto')
const { test } = require('node:test')
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, text, find, settle, deferred } = require('./helpers/render-controls.cjs')
const base = require('./fixtures/authored-normal-java-pointers.json')[0]
const all = (node, predicate) => [...(predicate(node) ? [node] : []), ...(node.children ?? []).flatMap(child => all(child, predicate))]
const contextButtons = root => all(root, node => node.tag === 'button' && text(node) === 'View source context')

async function mount() {
  const requests = [], pending = deferred(), metadata = deferred()
  const evidence = Array.from({ length: 3 }, (_, index) => ({ side: 'source', documentId: String(base.documentId), fileKey: 'PRE', fileName: 'PRE.docx', pageNo: '',
    quote: `Source quotation ${index + 1}.`, located: true, sourceRole: 'tender', sourceHash: base.javaParsedSourceHash, originalSourceSha256: base.originalSourceSha256,
    canonicalEvidence: { ...base, unitId: `candidate-unit:${String(index + 1).repeat(64)}`, canonicalChunkId: `canonical:${String(index + 1).repeat(64)}` } }))
  const finding = { code: 'VT-026', status: 'Open', group: 'reference', scope: 'intra', severity: 'low', types: [], title: { en: 'Review candidate' }, body: {}, impact: {}, suggestion: {},
    runId: 'run-1', generatedRevisionSha256: 'generated', sourceSnapshotSha256: 'source', fileKey: 'PRE', evidence }
  const job = { id: 'run-1', status: 'COMPLETED', phase: 'done', completedUnits: 27, totalUnits: 27, message: 'Review ended', result: { total: 1, metrics: { total: 1 }, messages: [] }, coverage: { documents: [], warnings: [] } }
  const checklist = { runId: 'run-1', sourceSnapshotSha256: 'source', items: [], logicalGroups: [], automaticResults: [{ id: 'G1', status: 'insufficient_evidence', symbol: '?', reason: 'Review remains incomplete',
    subItemAssessments: [{ itemId: 'I1', status: 'insufficient_evidence', reason: 'Needs review', assessments: [{ status: 'insufficient_evidence', reason: 'Source quotations remain visible', evidence }] }] }] }
  const store = vue.reactive({ activeProjectId: 'project-a', locale: 'en', notify() {}, setBusy() {}, clearBusy() {}, captureLlmSelection: () => ({ profileId: null }) })
  const loaded = loadVue(path.resolve(__dirname, '../src/views/VettingView.vue'), { boundaries: {
    '@noble/hashes/sha256': require('@noble/hashes/sha256'),
    'vue-i18n': { useI18n: () => ({ t: key => key, locale: vue.ref('en') }) }, 'vue-router': { useRouter: () => ({ push: async () => undefined }) },
    '@/stores/app': { useAppStore: () => store }, '@/api/client': { ApiError: class ApiError extends Error {} },
    '@/api': { vettingApi: { files: async () => [], sourceProgress: async () => [], findings: async () => [finding], metrics: async () => ({ total: 1 }),
      latestRunProgress: async () => job, getRunDisplay: async () => job, evidence: () => metadata.promise, checklist: async () => checklist } },
    '@/components/FixedBaselineEvidencePanel.vue': { default: { render: () => null } }, '@/components/AdviceSourceReader.vue': { default: { render: () => null } }
  }, globals: { Blob, Response, Uint8Array, ArrayBuffer, crypto: crypto.webcrypto, URLSearchParams, structuredClone,
    fetch: (url, options) => { requests.push({ url, options }); return pending.promise },
    URL: { createObjectURL: () => 'blob:authored-source', revokeObjectURL() {} }, window: { setTimeout, clearTimeout } } })
  const host = renderer(), app = host.createApp(loaded.default)
  app.mount(host.root); await settle()
  return { ...host, requests, evidence, resolveEvidence: async () => { metadata.resolve({ located: true, items: evidence.map(item => ({ ...item, code: 'PRE', text: item.quote })) }); await settle() },
    openFinding: async () => { const button = find(host.root, node => node.tag === 'button' && String(node.props?.class).split(' ').includes('finding')); assert.ok(button); button.props.onClick(); await settle() },
    closeDrawer: async () => { const button = find(host.root, node => node.tag === 'button' && node.props?.['aria-label'] === 'common.close'); assert.ok(button); button.props.onClick(); await settle() },
    dispose: () => app.unmount() }
}

test('Vetting drawer loads only explicitly selected context and cancels it on close while preserving quotations and review draft', async () => {
  const h = await mount()
  try {
    await h.openFinding()
    assert.equal(h.requests.length, 0, 'Finding fallback quotations must not automatically load canonical frames')
    assert.equal(contextButtons(h.root).length, 3)
    await h.resolveEvidence()
    assert.equal(h.requests.length, 0, 'Evidence metadata replacement must not trigger hidden reloads')
    const drawer = find(h.root, node => node.tag === 'aside')
    assert.match(text(drawer), /PRE\.docx.*Source quotation 1\./s)
    assert.ok(find(drawer, node => node.tag === 'a' && text(node) === 'vetting.drawer.downloadOriginal'))
    const remarks = find(drawer, node => node.tag === 'textarea')
    assert.ok(remarks); remarks.props['onUpdate:modelValue']('Unsaved review note'); await settle()
    contextButtons(drawer)[1].props.onClick(); await settle()
    assert.equal(h.requests.length, 1)
    await h.closeDrawer()
    assert.equal(h.requests[0].options.signal.aborted, true)
    assert.equal(contextButtons(drawer).length, 0, 'Closed drawer must unmount source panels')
    assert.match(text(drawer), /Source quotation 1\./)
    await h.openFinding()
    assert.equal(h.requests.length, 1, 'Reopening does not restart a previously requested context')
    assert.equal(find(drawer, node => node.tag === 'textarea').value, 'Unsaved review note')
  } finally { h.dispose() }
})

test('collapsed Vetting checklist evidence remains deferred and one explicit context action loads one unit', async () => {
  const h = await mount()
  try {
    const checklist = find(h.root, node => node.tag === 'details' && String(node.props?.class).includes('checklist-panel'))
    assert.ok(checklist); await checklist.props.onToggle({ target: { open: true } }); await settle()
    assert.equal(h.requests.length, 0, 'Mounted children of collapsed checklist rows must not read complete sources')
    assert.equal(contextButtons(checklist).length, 3)
    contextButtons(checklist)[0].props.onClick(); await settle()
    assert.equal(h.requests.length, 1)
  } finally { h.dispose() }
})
