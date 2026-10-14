const assert = require('node:assert/strict')
const path = require('node:path')
const fs = require('node:fs')
const { test } = require('node:test')
const vue = require('vue')
const pinia = require('pinia')
const routerRuntime = require('vue-router')
const { loadVue } = require('./helpers/load-vue.cjs')
const { text, find, settle, deferred, renderer } = require('./helpers/render-controls.cjs')
const readingWindow = new (require('jsdom').JSDOM)('').window

const literalQuote = '  Clause A1: shall NOT apply.\nHK$M 1.20; COMPLTION 😀\n'
const localized = value => ({ zhHans: value, zhHant: value, en: value })
const finding = {
  code: 'F001', title: localized('Verify clause A1'), body: localized('Which exception governs this clause?'),
  impact: localized(''), suggestion: localized('SUGGESTED ANSWER NOT TO TRANSFER'), types: ['risk'], group: 'risk',
  scope: 'intra', severity: 'medium', status: 'Open', pattern: null, refs: 'A1', location: 'P4', expected: null,
  evidenceId: 'E1', fileKey: 'SCC', pageNo: 'P4', bucketKey: null, verification: 'partial',
  runId: 'run-1', generatedRevisionSha256: '6'.repeat(64), sourceSnapshotSha256: '7'.repeat(64),
  reviewRemarks: 'TEAM REMARKS NOT TO TRANSFER', actionTaken: '', addendumRequired: null
}
const source = { documentId: '12', fileKey: 'SCC', fileName: 'actual SCC.pdf', sourceRole: 'tender', parseStatus: 'PARSED', included: true, exclusionReason: null, sourceHash: 'source-v1' }
const evidence = { title: 'Original source', located: true, items: [{ code: 'E1', side: 'source', documentId: '12', fileKey: 'SCC', fileName: 'actual SCC.pdf', pageNo: 'P4', anchor: 'A1', sourceHash: 'source-v1', originalSourceSha256: '8'.repeat(64), located: true, quote: literalQuote, text: literalQuote }] }
const canonicalFixtureRoot = path.resolve(__dirname, '../../ai_consense_service/src/test/resources/oer/canonical')
const canonicalProjection = JSON.parse(fs.readFileSync(path.join(canonicalFixtureRoot, 'authored-three-page-projection.json'), 'utf8'))
function canonicalPointer(module, unitId = 'table') {
  const chunk = canonicalProjection.chunks.find(chunk => chunk.unitId === unitId)
  return JSON.parse(JSON.stringify({
    addressKind: 'canonical_adoption_unit', projectId: 'project-A', documentId: 11, module,
    unitId, canonicalChunkId: chunk.id, originalSourceSha256: canonicalProjection.source.sourceSha256,
    javaParsedSourceHash: '1'.repeat(64), sourceRevisionSha256: canonicalProjection.source.sourceRevisionSha256,
    canonicalSourceHash: canonicalProjection.sourceHash, projectionSha256: canonicalProjection.projectionSha256,
    projectionRevisionSha256: canonicalProjection.projectionRevisionSha256,
    sourceFilename: canonicalProjection.source.sourceFilename, sourceOrder: chunk.sourceOrder,
    bboxConvention: 'normalized_xyxy_original_page', humanConfirmed: false,
    physicalPages: chunk.physicalPages, parts: chunk.parts, sourceSpans: chunk.sourceSpans,
    eligibleModules: chunk.eligibleModules, tableGroup: chunk.tableGroup ?? null, assetRefs: chunk.assetRefs,
    relations: chunk.relations, adoption: chunk.adoption, sourceQuality: chunk.sourceQuality, unknowns: canonicalProjection.unknowns
  }))
}
function findAll(node, predicate) { return [...(predicate(node) ? [node] : []), ...(node.children ?? []).flatMap(child => findAll(child, predicate))] }
async function flushCanonical() { for (let i = 0; i < 12; i++) { await new Promise(resolve => setImmediate(resolve)); await settle() } }

async function harness({ stale = false, currentSources = [source], sourceGate, evidenceGate, initialView = 'vetting', importedProject, storageDenied = false,
  canonicalEvidence, adviceMessages = [], browserCrypto = require('node:crypto').webcrypto, respondOverride, canonicalRespond, answerRendererBoundaries = {} } = {}) {
  const requests = [], stored = new Map(), canonicalRequests = [], createdUrls = [], revokedUrls = []
  for (const message of adviceMessages) for (const citation of message.sources ?? []) if (citation.canonicalEvidence?.length && citation.sourceRole === undefined) citation.sourceRole = 'tender'
  const currentEvidence = canonicalEvidence ? { ...evidence, items: evidence.items.map(item => ({ ...item,
    documentId: String(canonicalEvidence.documentId), fileName: canonicalEvidence.sourceFilename,
    sourceHash: canonicalEvidence.javaParsedSourceHash, originalSourceSha256: canonicalEvidence.originalSourceSha256, canonicalEvidence })) } : evidence
  const sessionStorage = {
    getItem: key => stored.get(key) ?? null,
    setItem: (key, value) => { if (storageDenied) throw new Error('Storage denied'); stored.set(key, value) },
    removeItem: key => stored.delete(key)
  }
  const respond = (method, url, body, config) => {
    requests.push({ method, url, body, config })
    const override = respondOverride?.({ method, url, body, config })
    if (override !== undefined) return Promise.resolve(override).then(data => ({ data: typeof data?.code === 'number' ? data : { code: 0, data } }))
    let data
    if (url.includes('/findings/F001/evidence')) data = evidenceGate ? evidenceGate.promise : currentEvidence
    else if (url.includes('/findings')) data = [finding]
    else if (url.endsWith('/files')) data = []
    else if (url.endsWith('/metrics')) data = { reference: 0, conflict: 0, risk: 1, language: 0, ocrReady: true, message: localized('') }
    else if (url.endsWith('/runs/latest')) data = stale ? { id: 'run-1', status: 'COMPLETED', sourceSetStale: true } : null
    else if (url.includes('/sources?')) data = sourceGate ? sourceGate.promise : currentSources
    else if (url.endsWith('/sessions')) data = method === 'get' ? [] : { id: 'session-1', projectId: url.split('/')[2], scope: body.scope, basisScope: body.basisScope, createdAt: '2026-10-13T00:01:00Z' }
    else if (/\/messages(?:\?|$)/.test(url)) data = method === 'get' ? (url.includes('sessionId=') ? [] : adviceMessages) : null
    else if (url.endsWith('/quick-questions')) data = []
    else if (url.includes('/index/status?')) data = { chunks: 1, message: localized('Ready') }
    else if (url.includes('/index/rebuild?')) data = { chunks: 1, message: localized('Rebuilt') }
    else if (url.endsWith('/ask/stream')) data = { sessionId: body.sessionId, grounded: true, content: localized('Independent reply'), title: localized('Reply'), citations: [], sources: [], unknownScope: null, evidenceId: null }
    else throw new Error(`Unexpected API request: ${method} ${url}`)
    return Promise.resolve(data).then(data => ({ data: { code: 0, data } }))
  }
  const fetch = async (url, options) => {
    if (url.includes('/ask/stream') && options?.method === 'POST') {
      const body = options.body ? JSON.parse(options.body) : undefined
      if (url.includes('/stop?')) { requests.push({ method: 'post', url: url.slice(4), body, config: { headers: options.headers } }); return new Response(JSON.stringify({ code: 0 }), { headers: { 'Content-Type': 'application/json' } }) }
      const response = await respond('post', url.slice(4), body, { headers: options.headers })
      if (response.data.code !== 0) return new Response(JSON.stringify(response.data), { headers: { 'Content-Type': 'application/json' } })
      return new Response(`event: complete\ndata: ${JSON.stringify({ requestId: options.headers['X-ConSense-Advice-Request'], response: response.data.data })}\n\n`, { headers: { 'Content-Type': 'text/event-stream' } })
    }
    canonicalRequests.push({ url, options })
    if (canonicalRespond) return canonicalRespond(url, options)
    const request = new URL(url, 'http://127.0.0.1:5173')
    if (request.pathname.includes('/original')) {
      const bound = adviceMessages.flatMap(message => message.sources ?? []).flatMap(citation => citation.canonicalEvidence ?? []).find(pointer => String(pointer.documentId) === request.pathname.split('/').at(-2))
      assert.ok(bound, `Unexpected original source ${url}`)
      return new Response(fs.readFileSync(path.join(canonicalFixtureRoot, 'authored-source.pdf')), { headers: {
        'Content-Type': 'application/pdf', 'Content-Disposition': 'inline; filename="authored.pdf"',
        'X-Source-Sha256': bound.originalSourceSha256, 'X-Source-Revision': bound.javaParsedSourceHash
      } })
    }
    const id = decodeURIComponent(request.pathname.split('/').at(-1))
    const candidates = [canonicalEvidence, ...adviceMessages.flatMap(message => message.sources ?? []).flatMap(citation => citation.canonicalEvidence ?? [])].filter(Boolean)
    if (request.pathname.includes('/units/')) {
      const bound = candidates.find(address => address.unitId === id && address.module === request.searchParams.get('module'))
      assert.ok(bound, `Unexpected canonical unit address ${url}`)
      return new Response(JSON.stringify({ code: 0, data: bound }), { headers: { 'Content-Type': 'application/json' } })
    }
    const bound = candidates.find(address => address.assetRefs.some(asset => asset.assetId === id))
    const asset = bound?.assetRefs.find(asset => asset.assetId === id)
    assert.ok(asset, `Unexpected canonical asset address ${url}`)
    return new Response(fs.readFileSync(path.join(canonicalFixtureRoot, asset.artifactName)), { headers: {
      'Content-Type': asset.mediaType, ETag: `"${asset.sha256}"`,
      'X-Source-Sha256': bound.originalSourceSha256, 'X-Projection-Sha256': bound.projectionSha256
    } })
  }
  class EvidenceURL extends URL {
    static createObjectURL() { const url = `blob:business-evidence-${createdUrls.length}`; createdUrls.push(url); return url }
    static revokeObjectURL(url) { revokedUrls.push(url) }
  }
  const axios = { create: () => ({ get: (url, config) => respond('get', url, undefined, config), post: (url, body, config) => respond('post', url, body, config), put: (url, body, config) => respond('put', url, body, config), delete: (url, config) => respond('delete', url, undefined, config) }), isAxiosError: () => false }
  const loaded = loadVue(path.join(__dirname, '../src/views/VettingView.vue'), {
    boundaries: { axios: { default: axios }, pinia, 'vue-router': routerRuntime, 'vue-i18n': require('vue-i18n'), '@noble/hashes/sha256': require('@noble/hashes/sha256'),
      'jszip': { default: require('jszip') }, 'mammoth/mammoth.browser.min.js': { default: require('mammoth/mammoth.browser.min.js') },
      'dompurify': { default: require('dompurify')(readingWindow) },
      'pdfjs-dist': { GlobalWorkerOptions: {}, getDocument: () => ({ promise: Promise.reject(new Error('Real PDF rendering is outside this control fixture')), destroy: async () => {} }) },
      'pdfjs-dist/build/pdf.worker.min.mjs?url': { default: 'fixture-worker' }, ...answerRendererBoundaries },
    globals: { sessionStorage, crypto: browserCrypto, Uint8Array, ArrayBuffer, URLSearchParams, URL: EvidenceURL, Blob, FormData, File, AbortController, TextDecoder, DOMException, fetch, structuredClone,
      DOMParser: readingWindow.DOMParser, XMLSerializer: readingWindow.XMLSerializer, NodeFilter: readingWindow.NodeFilter, Element: readingWindow.Element, HTMLElement: readingWindow.HTMLElement, Node: readingWindow.Node,
      localStorage: { getItem: () => null, setItem() {} }, document: { documentElement: { lang: '' }, getElementById: () => null },
      window: { setTimeout(callback, ms) { const timer = setTimeout(callback, ms); timer.unref(); return timer }, clearTimeout }, console }
  })
  const instance = pinia.createPinia(); pinia.setActivePinia(instance)
  const store = loaded.loadLocal(path.join(__dirname, '../src/stores/app.ts')).useAppStore()
  store.activeProjectId = 'project-A'; store.changeLocale('en')
  const helper = loaded.loadLocal(path.join(__dirname, '../src/advice/reviewHandoff.ts'))
  let initialToken
  if (importedProject) {
    initialToken = helper.newReviewHandoffToken()
    helper.storeReviewHandoff(sessionStorage, helper.createReviewHandoff({ projectId: importedProject, findingCode: finding.code, title: finding.title, description: finding.body, evidence: evidence.items, sourceSetStale: stale }, initialToken))
  }
  const router = routerRuntime.createRouter({ history: routerRuntime.createMemoryHistory(), routes: [
    { path: '/vetting', name: 'vetting', component: loaded.default },
    { path: '/advice', name: 'advice', component: loaded.loadLocal(path.join(__dirname, '../src/views/AdviceView.vue')).default }
  ] })
  await router.push({ name: initialView, query: initialToken ? { review: initialToken } : {} }); await router.isReady()
  const rendered = renderer(), app = rendered.createApp({ render: () => vue.h(routerRuntime.RouterView) })
  app.use(instance); app.use(router); app.use(loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n)
  app.mount(rendered.root); await settle()
  return { ...rendered, requests, canonicalRequests, createdUrls, revokedUrls, router, store, stored, sessionStorage, helper, initialToken,
    adviceApi: loaded.loadLocal(path.join(__dirname, '../src/api/index.ts')).adviceApi, dispose: () => app.unmount() }
}

function button(h, label) { return find(h.root, node => node.tag === 'button' && text(node).trim() === label) }
async function openFinding(h) {
  const item = find(h.root, node => node.tag === 'button' && node.props.class?.includes('finding '))
  assert.ok(item)
  item.props.onClick(); await settle()
}

test('real Vetting entry hands off literal sources to editable Advice, localizes controls, and never auto-asks', async () => {
  const h = await harness()
  try {
    await openFinding(h)
    for (const [locale, label] of [['zh-Hans', '带入合约咨询'], ['zh-Hant', '帶入合約諮詢'], ['en', 'Consult in Advisory']]) {
      h.store.changeLocale(locale); await settle()
      assert.equal(button(h, label).props.disabled, false)
    }
    await button(h, 'Consult in Advisory').props.onClick(); await settle(); await settle()
    assert.equal(h.router.currentRoute.value.name, 'advice')
    assert.equal(h.router.currentRoute.value.query.review, undefined)
    assert.equal(h.stored.size, 0, 'The source content is consumed once and never remains in the URL')
    assert.match(text(h.root), /Question draft from Vetting/)
    assert.doesNotMatch(text(h.root), /Quotation versions have not been verified/)
    const textarea = find(h.root, node => node.tag === 'textarea')
    assert.ok(textarea.value.includes(literalQuote))
    assert.ok(textarea.value.includes('"sourceHash":"source-v1"'))
    assert.doesNotMatch(textarea.value, /SUGGESTED ANSWER|TEAM REMARKS/)
    assert.equal(h.requests.some(request => request.method !== 'get'), false)
    assert.equal(h.requests.some(request => /index\/rebuild|\/ask$/.test(request.url)), false)
    textarea.props['onUpdate:modelValue']('Edited question: verify current clause A1'); await settle()
    await button(h, 'Ask').props.onClick(); await settle()
    const asks = h.requests.filter(request => request.url.endsWith('/ask/stream'))
    assert.equal(asks.length, 1)
    assert.equal(asks[0].url, '/advice/project-A/ask/stream')
    assert.equal(asks[0].body.question, 'Edited question: verify current clause A1')
    assert.equal(asks[0].body.scope, 'fullset')
    assert.match(text(h.root), /Independent reply/)
    assert.doesNotMatch(text(h.root), /Question draft from Vetting/)
  } finally { h.dispose() }
})

test('unsaved review edits and pending evidence block handoff without writing or navigating', async () => {
  const gate = deferred(), h = await harness({ evidenceGate: gate })
  try {
    await openFinding(h)
    assert.equal(button(h, 'Consult in Advisory').props.disabled, true)
    await button(h, 'Consult in Advisory').props.onClick(); await settle()
    assert.equal(h.stored.size, 0)
    gate.resolve(evidence); await settle()
    const remarks = find(h.root, node => node.tag === 'textarea' && node.props.rows === '3')
    remarks.props['onUpdate:modelValue']('Unsaved change'); await settle()
    assert.equal(button(h, 'Consult in Advisory').props.disabled, true)
    assert.match(text(h.root), /Save or discard unsaved review or checklist edits/)
    await button(h, 'Consult in Advisory').props.onClick(); await settle()
    assert.equal(h.router.currentRoute.value.name, 'vetting')
    assert.equal(h.stored.size, 0)
    assert.equal(h.requests.some(request => request.method !== 'get'), false)
  } finally { h.dispose() }
})

test('stale review and changed current source hashes produce explicit warnings; scope changes recheck sources', async () => {
  const h = await harness({ stale: true, currentSources: [{ ...source, sourceHash: 'source-v2' }] })
  try {
    await openFinding(h); await button(h, 'Consult in Advisory').props.onClick(); await settle()
    assert.match(text(h.root), /Sources changed after this review/)
    const scope = find(h.root, node => node.tag === 'select')
    scope.props['onUpdate:modelValue']('contract'); await settle()
    assert.ok(h.requests.some(request => request.url === '/advice/project-A/sources?scope=contract'))
    assert.match(text(h.root), /Sources changed after this review/)
    assert.equal(h.requests.some(request => request.url.endsWith('/ask/stream')), false)
    h.store.changeLocale('zh-Hant'); await settle()
    assert.match(text(h.root), /審查時的來源已變化/)
    h.store.changeLocale('zh-Hans'); await settle()
    assert.match(text(h.root), /审查时的来源已变化/)
  } finally { h.dispose() }
})

test('unloaded or excluded current sources stay unverified or unavailable and never pretend to match', async () => {
  const gate = deferred(), h = await harness({ initialView: 'advice', importedProject: 'project-A', sourceGate: gate })
  try {
    assert.match(text(h.root), /Quotation versions have not been verified/)
    assert.doesNotMatch(text(h.root), /Some quoted sources are outside/)
    gate.resolve([{ ...source, included: false, exclusionReason: 'outside_contract_scope', sourceHash: null }]); await settle()
    assert.match(text(h.root), /Quotation versions have not been verified/)
    assert.match(text(h.root), /Some quoted sources are outside/)
    assert.equal(h.requests.some(request => request.url.endsWith('/ask/stream')), false)
  } finally { h.dispose() }
})

test('wrong-project, replay, and later project switch cannot apply old contract text to another project', async () => {
  const wrong = await harness({ initialView: 'advice', importedProject: 'project-B' })
  try {
    assert.match(text(wrong.root), /belongs to another project/)
    assert.equal(find(wrong.root, node => node.tag === 'textarea').value, '')
    assert.equal(wrong.stored.size, 0)
    assert.equal(wrong.requests.some(request => request.method !== 'get'), false)
  } finally { wrong.dispose() }
  const h = await harness({ initialView: 'advice', importedProject: 'project-A' })
  try {
    const oldToken = h.initialToken
    assert.ok(find(h.root, node => node.tag === 'textarea').value.includes(literalQuote))
    await h.store.switchProject('project-B'); await settle()
    assert.equal(find(h.root, node => node.tag === 'textarea').value, '')
    assert.doesNotMatch(text(h.root), /Question draft from Vetting/)
    await h.router.push({ name: 'advice', query: { review: oldToken } }); await settle()
    assert.match(text(h.root), /already used, expired, or cannot be read/)
    assert.equal(find(h.root, node => node.tag === 'textarea').value, '')
    assert.equal(h.requests.some(request => request.url.endsWith('/ask/stream')), false)
  } finally { h.dispose() }
})

test('browser storage rejection keeps Vetting open and reports the actual transfer failure', async () => {
  const h = await harness({ storageDenied: true })
  try {
    await openFinding(h); await button(h, 'Consult in Advisory').props.onClick(); await settle()
    assert.equal(h.router.currentRoute.value.name, 'vetting')
    assert.equal(h.stored.size, 0)
    assert.equal(h.store.toast.message, 'The question draft could not be transferred. Please try again.')
    assert.equal(h.requests.some(request => request.method !== 'get'), false)
  } finally { h.dispose() }
})

test('real Vetting evidence mapping retains the optional canonical pointer and original Java parsed source link', async () => {
  const bound = canonicalPointer('vetting'), h = await harness({ canonicalEvidence: bound, browserCrypto: null })
  try {
    await openFinding(h); await flushCanonical()
    assert.equal(h.canonicalRequests.length, 4)
    assert.ok(h.canonicalRequests.every(request => request.options.method === 'GET' && request.url.includes('?module=vetting')))
    assert.equal(findAll(h.root, node => node.tag === 'img').length, 3)
    assert.deepEqual(findAll(h.root, node => node.tag === 'figure').map(node => node.props['data-asset-id']), bound.assetRefs.map(asset => asset.assetId))
    const original = find(h.root, node => node.tag === 'a' && node.props.href?.includes('/original?'))
    assert.ok(original)
    assert.ok(original.props.href.includes(`sourceHash=${bound.javaParsedSourceHash}`))
    assert.ok(original.props.href.includes(`originalSourceSha256=${bound.originalSourceSha256}`))
    assert.equal(original.props.href.includes(bound.canonicalSourceHash), false)
    assert.equal(h.requests.some(request => request.method !== 'get'), false)
    assert.equal(h.requests.some(request => request.url.endsWith('/ask/stream')), false)
    assert.match(text(h.root), /Not human-confirmed/)
    await h.store.switchProject('project-B'); await flushCanonical()
    assert.equal(findAll(h.root, node => node.tag === 'img').length, 0)
    assert.equal(h.canonicalRequests.length, 4, 'Project switch does not reuse the old evidence address')
    assert.equal(h.revokedUrls.length, 3)
  } finally { h.dispose() }
  assert.deepEqual(h.revokedUrls.sort(), h.createdUrls.sort())
})

test('real Advice citation displays every explicit canonical member and complete table assets on LAN without auto-asking', async () => {
  const members = [canonicalPointer('advisory'), canonicalPointer('advisory', 'heading')]
  const citation = { fileLabel: 'AUTHORED project original', pageNo: 'P3', anchor: 'A1', content: literalQuote, score: 0.9,
    documentId: '11', sourceHash: members[0].javaParsedSourceHash, canonicalEvidence: members }
  const message = { id: 7, role: 'assistant', title: localized('Reply'), content: localized('Inspect its evidence'),
    unknownScope: null, citations: [citation.fileLabel], evidenceId: 'document:11', sources: [citation] }
  const h = await harness({ initialView: 'advice', adviceMessages: [message], browserCrypto: null })
  try {
    assert.equal(h.canonicalRequests.length, 0, 'No selected citation means no image requests')
    const chip = find(h.root, node => node.tag === 'button' && node.props.class === 'citation')
    assert.ok(chip); chip.props.onClick(); await flushCanonical()
    assert.ok(h.canonicalRequests.some(request => request.url.includes('/original?')), 'Citation loads its entire bound source before auxiliary images')
    await button(h, 'Show related original images / tables').props.onClick(); await flushCanonical()
    assert.equal(findAll(h.root, node => node.props?.class === 'canonical-evidence-panel').length, 2)
    assert.deepEqual([...new Set(h.canonicalRequests.filter(request => request.url.includes('/units/')).map(request => decodeURIComponent(new URL(request.url, 'http://127.0.0.1').pathname.split('/').at(-1))))], ['table', 'heading'])
    assert.ok(h.canonicalRequests.every(request => (request.options.method ?? 'GET') === 'GET' && (request.url.includes('?module=advisory') || request.url.includes('/api/advice/project-A/sources/11/original?'))))
    assert.equal(findAll(h.root, node => node.tag === 'img').length, 3)
    assert.equal(findAll(h.root, node => node.props?.['data-sha-implementation'] === 'portable').length, 3)
    assert.match(text(h.root), /No image assets are bound/)
    assert.equal(h.requests.some(request => request.method !== 'get'), false)
    assert.equal(h.requests.some(request => request.url.endsWith('/ask/stream')), false)
  } finally { h.dispose() }
  assert.deepEqual(h.revokedUrls.sort(), h.createdUrls.sort())
})

test('legacy source-only finding evidence remains usable without fabricated canonical addresses', async () => {
  const h = await harness()
  try {
    await openFinding(h); await flushCanonical()
    assert.equal(h.canonicalRequests.length, 0)
    assert.equal(findAll(h.root, node => node.props?.class === 'canonical-evidence-panel').length, 0)
    assert.ok(find(h.root, node => node.tag === 'a' && node.props.href?.includes('sourceHash=source-v1')))
    assert.equal(button(h, 'Consult in Advisory').props.disabled, false)
  } finally { h.dispose() }
})

function indexReply(label, scope = 'fullset') {
  return { chunks: 1, message: localized(label), evidenceBackend: 'canonical', sourceScope: scope, indexReason: null }
}
async function selectScope(h, value) {
  find(h.root, node => node.tag === 'select').props['onUpdate:modelValue'](value)
  await settle()
}

test('Advice status and rebuild use the selected scope; ABA and project changes reject late status reads', async () => {
  const first = deferred(), second = deferred(), contract = deferred(), projectLate = deferred()
  let fullsetReads = 0, contractReads = 0
  const h = await harness({ initialView: 'advice', respondOverride: ({ url }) => {
    if (url === '/advice/project-A/index/status?scope=fullset') return (++fullsetReads === 1 ? first : second).promise
    if (url === '/advice/project-A/index/status?scope=contract') return (++contractReads === 1 ? contract : projectLate).promise
    if (url === '/advice/project-B/index/status?scope=fullset') return indexReply('B CURRENT')
  } })
  try {
    assert.ok(h.requests.some(request => request.url === '/advice/project-A/index/status?scope=fullset'))
    await selectScope(h, 'contract'); await selectScope(h, 'fullset')
    second.resolve(indexReply('A CURRENT')); await settle()
    first.resolve(indexReply('A OBSOLETE')); contract.resolve(indexReply('CONTRACT OBSOLETE', 'contract')); await settle()
    assert.match(text(h.root), /A CURRENT/); assert.doesNotMatch(text(h.root), /OBSOLETE/)
    await selectScope(h, 'contract')
    h.store.activeProjectId = 'project-B'; await settle()
    await selectScope(h, 'fullset')
    projectLate.resolve(indexReply('PROJECT A LATE', 'contract')); await settle()
    assert.match(text(h.root), /B CURRENT/); assert.doesNotMatch(text(h.root), /A CURRENT|OBSOLETE/)
    assert.doesNotMatch(text(h.root), /PROJECT A LATE/)
    await button(h, 'Refresh evidence index').props.onClick(); await settle()
    assert.ok(h.requests.some(request => request.method === 'post' && request.url === '/advice/project-B/index/rebuild?scope=fullset'))
  } finally { h.dispose() }
})

test('scoped index APIs encode identifiers, default to fullset and reject a response from another scope', async () => {
  const h = await harness({ initialView: 'advice', respondOverride: ({ url }) => url.includes('/index/status?') || url.includes('/index/rebuild?') ? indexReply('WRONG SCOPE', 'tender') : undefined })
  try {
    assert.match(text(h.root), /index request for this scope failed/)
    assert.doesNotMatch(text(h.root), /WRONG SCOPE/)
    await h.adviceApi.indexStatus('project /?')
    await h.adviceApi.rebuildIndex('project /?', 'contract &')
    assert.ok(h.requests.some(request => request.url === '/advice/project%20%2F%3F/index/status?scope=fullset'))
    assert.ok(h.requests.some(request => request.url === '/advice/project%20%2F%3F/index/rebuild?scope=contract%20%26'))
    await button(h, 'Refresh evidence index').props.onClick(); await settle()
    assert.doesNotMatch(text(h.root), /WRONG SCOPE/)
    assert.equal(h.store.toast.visible, false)
  } finally { h.dispose() }
})

test('late rebuild and its finally cannot replace scoped state, notify, or clear the newer busy operation', async () => {
  const old = deferred(), current = deferred()
  const h = await harness({ initialView: 'advice', respondOverride: ({ url }) => {
    if (url === '/advice/project-A/index/rebuild?scope=fullset') return old.promise
    if (url === '/advice/project-A/index/rebuild?scope=contract') return current.promise
    if (url === '/advice/project-A/index/status?scope=contract') return indexReply('CONTRACT STATUS', 'contract')
  } })
  try {
    const oldRequest = button(h, 'Refresh evidence index').props.onClick(); await settle()
    await selectScope(h, 'contract')
    assert.equal(h.store.busyMessage, '')
    const currentRequest = button(h, 'Refresh evidence index').props.onClick(); await settle()
    const busy = h.store.busyMessage
    assert.ok(busy); assert.equal(button(h, 'Refresh evidence index').props.disabled, true)
    old.resolve(indexReply('OBSOLETE REBUILD')); await oldRequest; await settle()
    assert.equal(h.store.busyMessage, busy)
    assert.equal(button(h, 'Refresh evidence index').props.disabled, true)
    assert.doesNotMatch(text(h.root), /OBSOLETE REBUILD/)
    assert.equal(h.store.toast.visible, false)
    current.resolve(indexReply('CURRENT REBUILT', 'contract')); await currentRequest; await settle()
    assert.match(text(h.root), /CURRENT REBUILT/)
    assert.equal(h.store.busyMessage, ''); assert.equal(button(h, 'Refresh evidence index').props.disabled, false)
    assert.equal(h.store.toast.message, 'CURRENT REBUILT')
  } finally { h.dispose() }
})

test('newer rebuild result wins over pending status and a current failure is an actionable inline error', async () => {
  const status = deferred()
  let rebuilds = 0
  const h = await harness({ initialView: 'advice', respondOverride: ({ url }) => {
    if (url.includes('/index/status?')) return status.promise
    if (url.includes('/index/rebuild?')) return ++rebuilds === 1 ? indexReply('VERIFIED REBUILD') : Promise.reject(new Error('UNTRUSTED RAW MESSAGE'))
  } })
  try {
    await button(h, 'Refresh evidence index').props.onClick(); await settle()
    status.resolve(indexReply('OBSOLETE GET')); await settle()
    assert.match(text(h.root), /VERIFIED REBUILD/); assert.doesNotMatch(text(h.root), /OBSOLETE GET/)
    await button(h, 'Refresh evidence index').props.onClick(); await settle()
    assert.match(text(find(h.root, node => node.props?.role === 'alert')), /Check backend configuration and refresh the index/)
    assert.doesNotMatch(text(h.root), /UNTRUSTED RAW MESSAGE/)
    assert.equal(h.store.busyMessage, '')
  } finally { h.dispose() }
})

test('stale status failures and unmounted rebuild completion leave current view and other busy state untouched', async () => {
  const oldStatus = deferred(), oldRebuild = deferred()
  const h = await harness({ initialView: 'advice', respondOverride: ({ url }) => {
    if (url === '/advice/project-A/index/status?scope=fullset') return oldStatus.promise
    if (url.includes('/index/rebuild?')) return oldRebuild.promise
    if (url.includes('/index/status?')) return indexReply('NEW SCOPE', 'contract')
  } })
  await selectScope(h, 'contract')
  oldStatus.resolve(Promise.reject(new Error('OLD FAILURE'))); await settle()
  assert.match(text(h.root), /NEW SCOPE/)
  assert.equal(find(h.root, node => node.props?.role === 'alert'), undefined)
  const rebuild = button(h, 'Refresh evidence index').props.onClick(); await settle()
  h.store.setBusy('OTHER OPERATION')
  h.dispose()
  oldRebuild.resolve(indexReply('UNMOUNTED', 'contract')); await rebuild; await settle()
  assert.equal(h.store.busyMessage, 'OTHER OPERATION')
  assert.equal(h.store.toast.visible, false)
})

test('scope switch releases selected citation images and late ask cannot restore the old scope citation', async () => {
  const bound = canonicalPointer('advisory'), askGate = deferred()
  const citation = { fileLabel: 'OLD CITATION', pageNo: 'P3', anchor: 'A1', content: literalQuote, score: 0.9, canonicalEvidence: [bound] }
  const message = { id: 7, role: 'assistant', title: localized('Reply'), content: localized('Inspect evidence'), unknownScope: null, citations: [citation.fileLabel], evidenceId: 'E1', sources: [citation] }
  const h = await harness({ initialView: 'advice', adviceMessages: [message], respondOverride: ({ url }) => url.endsWith('/ask/stream') ? askGate.promise : undefined })
  try {
    find(h.root, node => node.tag === 'button' && node.props.class === 'citation').props.onClick(); await flushCanonical()
    await button(h, 'Show related original images / tables').props.onClick(); await flushCanonical()
    assert.ok(h.createdUrls.length >= 3)
    const textarea = find(h.root, node => node.tag === 'textarea')
    textarea.props['onUpdate:modelValue']('Question in old scope'); await settle()
    const request = button(h, 'Ask').props.onClick(); await settle()
    await selectScope(h, 'contract'); await flushCanonical()
    assert.equal(findAll(h.root, node => node.tag === 'img').length, 0)
    assert.deepEqual(h.revokedUrls.sort(), h.createdUrls.sort())
    const requestCount = h.canonicalRequests.length
    askGate.resolve({ grounded: true, title: localized('Late reply'), content: localized('LATE OLD ANSWER'), sources: [citation], citations: [citation.fileLabel], unknownScope: null, evidenceId: 'E1' })
    await request; await flushCanonical()
    assert.doesNotMatch(text(h.root), /LATE OLD ANSWER/)
    assert.equal(h.canonicalRequests.length, requestCount)
    assert.equal(button(h, 'Ask').props.disabled, true, 'Empty new-scope composer remains empty')
  } finally { h.dispose() }
})

test('Citation retains observed and unknown source-unit metadata without treating context zero as a rerank score', async () => {
  const unit = { status: 'complete_observed_unit', reason: null, requiredMemberIds: ['parent', 'condition'], members: ['parent', 'condition'], qualifiersComplete: 'unknown', semanticScopeVerified: false, unsafe: '<img src=x onerror=run()>' }
  const sources = [
    { fileLabel: 'CONTEXT', pageNo: 'P3', anchor: 'Context', content: literalQuote, score: 0, scoreMeaning: 'unranked_context', canonicalSourceUnit: unit },
    { fileLabel: 'SEED', pageNo: 'P3', anchor: 'Seed', content: literalQuote, score: 0.937, scoreMeaning: 'actual_reranked_seed', canonicalSourceUnit: { ...unit, status: 'unknown', reason: 'unknown_clause' } }
  ]
  const message = { id: 7, role: 'assistant', title: localized('Reply'), content: localized('Inspect evidence'), unknownScope: null, citations: ['CONTEXT'], evidenceId: 'E1', sources }
  const h = await harness({ initialView: 'advice', adviceMessages: [message] })
  try {
    find(h.root, node => node.tag === 'button' && node.props.class === 'citation').props.onClick(); await settle()
    assert.match(text(h.root), /no independent rerank score/)
    assert.doesNotMatch(text(h.root), /score 0\.000|Rerank score 0\.000/)
    assert.match(text(h.root), /complete observed structural unit/)
    const metadata = find(h.root, node => node.tag === 'pre')
    assert.deepEqual(JSON.parse(text(metadata)), unit)
    assert.equal(find(h.root, node => node.tag === 'img'), undefined)
    find(h.root, node => node.tag === 'button' && node.props.class?.includes('citation-chip') && text(node) === 'Seed').props.onClick(); await settle()
    assert.match(text(h.root), /Rerank score 0\.937/)
    assert.match(text(h.root), /Structural evidence status is unresolved/)
    assert.match(text(h.root), /unknown_clause/)
  } finally { h.dispose() }
})

test('a historical Advice citation keeps its saved pointer when current source validation rejects it', async () => {
  const bound = canonicalPointer('advisory')
  const citation = { fileLabel: 'HISTORICAL', pageNo: 'P3', anchor: 'A1', content: literalQuote, score: 0.8, scoreMeaning: 'actual_reranked_seed', canonicalEvidence: [bound] }
  const message = { id: 7, role: 'assistant', title: localized('Reply'), content: localized('Inspect historical evidence'), unknownScope: null, citations: [citation.fileLabel], evidenceId: 'E1', sources: [citation] }
  const h = await harness({ initialView: 'advice', adviceMessages: [message], canonicalRespond: async () => new Response(JSON.stringify({ code: 4300, message: 'SOURCE_ORIGINAL_BYTES_CHANGED' }), { status: 409 }) })
  try {
    find(h.root, node => node.tag === 'button' && node.props.class === 'citation').props.onClick(); await flushCanonical()
    assert.match(text(h.root), /source file or its registered identity changed/)
    assert.equal(h.canonicalRequests.length, 1)
    assert.equal(h.createdUrls.length, 0)
    assert.ok(h.canonicalRequests[0].url.includes(bound.projectionSha256))
    assert.deepEqual(message.sources[0].canonicalEvidence[0], bound)
    assert.equal(h.requests.some(request => request.url.endsWith('/ask/stream') || request.url.includes('/index/rebuild')), false)
  } finally { h.dispose() }
})

test('real Advice view displays a fenced flowchart, retains prose and citations, and makes no model call', async () => {
  const graph = 'flowchart TD\n A["Start [1]"] --> B["Finish [1]"]'
  let renders = 0
  const sanitize = require('dompurify')(new (require('jsdom').JSDOM)('').window)
  const h = await harness({ initialView: 'advice', adviceMessages: [{ id: 9, role: 'assistant', title: localized('Procedure'),
    content: localized('Before [1]\n```mermaid\n' + graph + '\n```\nAfter [1]'), citations: ['[1]'], sources: [], basisScope: 'reference_only' }],
    answerRendererBoundaries: {
      mermaid: { __esModule: true, default: { initialize() {}, async render(_id, source) { assert.equal(source.trim(), graph.trim()); renders++; return { svg: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 300 200"><text>Start [1]</text><script>run()</script></svg>' } } } },
      dompurify: { __esModule: true, default: sanitize }
    }
  })
  try {
    await flushCanonical()
    const graphic = find(h.root, node => typeof node.props?.innerHTML === 'string' && node.props.innerHTML.includes('<svg'))
    assert.ok(graphic, 'The assistant answer must include a graphic, not just Mermaid source as paragraph text; renders=' + renders + '; rendered=' + text(h.root))
    assert.doesNotMatch(graphic.props.innerHTML, /script|run\(\)/)
    assert.match(text(h.root), /Before \[1\]/); assert.match(text(h.root), /After \[1\]/)
    assert.equal(renders, 1)
    assert.ok(find(h.root, node => node.tag === 'button' && node.props.class === 'citation' && text(node) === '[1]'))
    assert.equal(h.requests.some(request => request.method !== 'get'), false)
  } finally { h.dispose() }
})

const sessions = [
  { id: 'session-A', projectId: 'project-A', scope: 'fullset', basisScope: 'project_contract', createdAt: '2026-10-13T00:01:00Z' },
  { id: 'session-B', projectId: 'project-A', scope: 'contract', basisScope: 'reference_only', createdAt: '2026-10-13T00:02:00Z' }
]
function sessionSelect(h) { return find(h.root, node => node.tag === 'select' && find(node, option => option.tag === 'option' && option.props.value === 'session-A')) }

test('explicit session switch binds scope/basis and isolates late answer and history without submitting a model call', async () => {
  const oldMessages = deferred(), oldAsk = deferred()
  let messageReads = 0
  const h = await harness({ initialView: 'advice', respondOverride: ({ url }) => {
    if (url.endsWith('/sessions')) return sessions
    if (url.endsWith('/messages?sessionId=session-A')) return ++messageReads === 1 ? [] : oldMessages.promise
    if (url.endsWith('/messages?sessionId=session-B')) return [{ sessionId: 'session-B', role: 'assistant', id: 20, title: localized('B title'), content: localized('B current answer'), citations: [], sources: [], basisScope: 'reference_only' }]
    if (url.endsWith('/ask/stream')) return oldAsk.promise
  } })
  try {
    assert.equal(h.requests.some(request => request.method !== 'get'), false)
    sessionSelect(h).props['onUpdate:modelValue']('session-A'); await settle()
    find(h.root, node => node.tag === 'textarea').props['onUpdate:modelValue']('Question for A'); await settle()
    const asking = button(h, 'Ask').props.onClick(); await settle()
    assert.equal(h.requests.find(request => request.url.endsWith('/ask/stream')).body.sessionId, 'session-A')
    const projectReads = h.requests.filter(request => /\/(?:sources|index\/status)\?/.test(request.url)).length
    sessionSelect(h).props['onUpdate:modelValue']('session-B'); await settle()
    assert.match(text(h.root), /B current answer/)
    assert.match(text(h.root), /Adoption of this edition/)
    const scopeControl = find(h.root, node => node.tag === 'select' && node.props['aria-label'] === 'Documents in this advice scope')
    assert.equal(scopeControl.props.disabled, true)
    assert.equal(h.requests.filter(request => /\/(?:sources|index\/status)\?/.test(request.url)).length, projectReads)
    assert.equal(button(h, 'Refresh evidence index'), undefined)
    assert.match(text(h.root), /fixed standards only[\s\S]*has not confirmed fixed retrieval readiness/)
    oldAsk.resolve({ sessionId: 'session-A', grounded: true, title: localized('A title'), content: localized('A late answer'), sources: [], citations: [] }); await asking; await settle()
    assert.doesNotMatch(text(h.root), /A late answer/)
    sessionSelect(h).props['onUpdate:modelValue']('session-A'); await settle()
    sessionSelect(h).props['onUpdate:modelValue']('session-B'); await settle()
    oldMessages.resolve([{ id: 99, role: 'assistant', content: localized('A obsolete history'), citations: [], sources: [] }]); await settle()
    assert.doesNotMatch(text(h.root), /A obsolete history/)
    assert.equal(h.requests.filter(request => request.url.endsWith('/ask/stream')).length, 1)
    assert.equal(h.requests.some(request => /\/findings\/.+\/(review|status)/.test(request.url) && request.method !== 'get'), false)
  } finally { h.dispose() }
})

test('new session and reference-only question preserve click-time purpose; budget failure preserves the editable question', async () => {
  const h = await harness({ initialView: 'advice', respondOverride: ({ url }) => url.endsWith('/ask/stream') ? { code: 4205, message: 'budget_unknown: final wire counter unavailable', data: null } : undefined })
  try {
    const basis = find(h.root, node => node.tag === 'select' && find(node, option => option.tag === 'option' && option.props.value === 'reference_only'))
    basis.props['onUpdate:modelValue']('reference_only'); await settle()
    find(h.root, node => node.tag === 'textarea').props['onUpdate:modelValue']('Explain this fixed standard'); await settle()
    await button(h, 'Ask').props.onClick(); await settle()
    const created = h.requests.find(request => request.url.endsWith('/sessions') && request.method === 'post')
    assert.equal(created.body.basisScope, 'reference_only')
    const submitted = h.requests.find(request => request.url.endsWith('/ask/stream'))
    assert.equal(submitted.body.sessionId, 'session-1'); assert.equal(submitted.body.basisScope, 'reference_only')
    assert.equal(find(h.root, node => node.tag === 'textarea').value, 'Explain this fixed standard')
    assert.match(text(h.root), /budget_unknown/)
    assert.equal(button(h, 'Ask').props.disabled, false)
  } finally { h.dispose() }
})

test('clear current session invalidates an in-flight response and leaves other sessions untouched', async () => {
  let asks = 0
  const pending = deferred(), h = await harness({ initialView: 'advice', respondOverride: ({ url, body }) => {
    if (url.endsWith('/sessions')) return sessions
    if (url.endsWith('/ask/stream')) return ++asks === 1 ? pending.promise : { sessionId: body.sessionId, grounded: true, title: localized('New'), content: localized('NEW ANSWER'), sources: [], citations: [] }
  } })
  try {
    sessionSelect(h).props['onUpdate:modelValue']('session-A'); await settle()
    find(h.root, node => node.tag === 'textarea').props['onUpdate:modelValue']('Old question'); await settle()
    const asking = button(h, 'Ask').props.onClick(); await settle()
    await button(h, 'Clear chat').props.onClick(); await settle()
    assert.equal(h.requests.find(request => request.method === 'delete').url, '/advice/project-A/messages?sessionId=session-A')
    const stop = h.requests.find(request => request.url.includes('/stop?'))
    assert.ok(stop); assert.match(stop.url, /sessionId=session-A$/)
    assert.ok(h.requests.indexOf(stop) < h.requests.findIndex(request => request.method === 'delete'))
    find(h.root, node => node.tag === 'textarea').props['onUpdate:modelValue']('New question'); await settle()
    await button(h, 'Ask').props.onClick(); await settle(); assert.equal(asks, 2); assert.match(text(h.root), /NEW ANSWER/)
    pending.resolve({ sessionId: 'session-A', grounded: true, title: localized('Late'), content: localized('CLEARED ANSWER'), sources: [], citations: [] }); await asking; await settle()
    assert.doesNotMatch(text(h.root), /CLEARED ANSWER|Old question/)
  } finally { h.dispose() }
})

test('stopping before an answer has only the stopped state, without a completed unknown answer or model placeholder', async () => {
  const pending = deferred(), h = await harness({ initialView: 'advice', respondOverride: ({ url }) => {
    if (url.endsWith('/sessions')) return sessions
    if (url.endsWith('/ask/stream')) return pending.promise
  } })
  try {
    sessionSelect(h).props['onUpdate:modelValue']('session-A'); await settle()
    find(h.root, node => node.tag === 'textarea').props['onUpdate:modelValue']('Question to stop'); await settle()
    const asking = button(h, 'Ask').props.onClick(); await settle()
    await button(h, 'Stop').props.onClick(); await settle()
    const stopped = find(h.root, node => node.tag === 'div' && node.props.class?.includes('bubble') && text(node).includes('Stopped.'))
    assert.ok(stopped); assert.match(text(stopped), /Stopped\. Any displayed portions are not a completed answer/)
    assert.doesNotMatch(text(stopped), /I don.t know|No saved model identity|No model identity|没有保存模型标识/)
    pending.resolve({ sessionId: 'session-A', grounded: true, title: localized('Late'), content: localized('LATE STOPPED ANSWER'), sources: [], citations: [] }); await asking; await settle()
    assert.doesNotMatch(text(h.root), /LATE STOPPED ANSWER/); assert.doesNotMatch(text(stopped), /I don.t know|No saved model identity/)
  } finally { h.dispose() }
})

test('drawing upload sends explicit identity-only scope and source list retains excluded original identity', async () => {
  const drawing = { key: 'DRAWING', fileName: 'drawing.pdf', role: localized('Drawing identity'), status: 'EXCLUDED', parsed: false, documentScope: 'drawing', exclusionReason: 'drawing_outside_scope', parseStatus: 'EXCLUDED', parseMessage: 'Identity only; original retained', pageCount: 0 }
  const h = await harness({ respondOverride: ({ url }) => {
    if (url.endsWith('/files')) return [drawing]
    if (url.includes('/package/upload')) return { accepted: 1, parsed: 0, failed: 0, messages: ['Drawing excluded from parsing'] }
  } })
  try {
    const selector = find(h.root, node => node.tag === 'select' && find(node, option => option.tag === 'option' && option.props.value === 'drawing'))
    assert.ok(find(selector, node => node.tag === 'option' && node.props.value === 'drawing'))
    selector.props['onUpdate:modelValue']('drawing'); await settle()
    const upload = find(h.root, node => node.tag === 'input' && node.props.type === 'file')
    await upload.props.onChange({ target: { files: [new File(['%PDF fixture'], 'drawing.pdf')], value: '' } }); await settle()
    const request = h.requests.find(request => request.url.includes('/package/upload'))
    assert.equal(request.url, '/vetting/project-A/package/upload?documentScope=drawing')
    assert.equal(request.body.getAll('files')[0].name, 'drawing.pdf')
    assert.match(text(h.root), /drawing\.pdf/); assert.match(text(h.root), /EXCLUDED/)
    assert.match(text(h.root), /Drawing identity only; excluded from parsing and advice/)
    assert.equal(button(h, 'Run vetting').props.disabled, true)
    assert.equal(h.requests.some(request => /\/runs$|\/ask$/.test(request.url)), false)
  } finally { h.dispose() }
})

test('historical fixed answer retains its own source lane after a later project answer with a colliding document ID', async () => {
  const project = canonicalPointer('advisory'), fixed = { evidenceBackend: 'fixed_development_baseline', parentId: '11', manifestSha256: 'a'.repeat(64), baselineVersion: '05', sourceFamily: 'GCC', sourceRole: 'standard', originalSourceSha256: 'b'.repeat(64), pageNo: 4, originalPageNos: [4], projectApplicability: 'unconfirmed', qualifiersComplete: 'unknown', sourceQualityAccepted: false }
  const fixedCitation = { fileLabel: 'ANSWER A FIXED', pageNo: '4', anchor: 'A1', content: 'Fixed source A literal', score: .8, documentId: '11', fixedBaselineEvidence: fixed }
  const projectCitation = { fileLabel: 'ANSWER B PROJECT', pageNo: '3', anchor: 'B1', content: 'Project source B literal', score: .9, documentId: '11', canonicalEvidence: [project] }
  const messages = [fixedCitation, projectCitation].map((citation, index) => ({ id: index + 1, role: 'assistant', title: localized('Saved answer'), content: localized('Saved answer with its original sources'), sources: [citation], citations: [citation.fileLabel] }))
  const h = await harness({ initialView: 'advice', adviceMessages: messages, canonicalRespond: async url => {
    if (url.startsWith('/api/oer/fixed-baseline/evidence?')) return new Response(JSON.stringify({ code: 0, data: { id: '11', content: fixedCitation.content, fileLabel: 'GCC standard', sourceFamily: 'GCC', payload: { fixedBaselineManifestSha256: fixed.manifestSha256, fixedBaselineVersion: '05', sourceRole: 'standard', originalSourceSha256: fixed.originalSourceSha256 }, sourceUnit: null, tableArtifact: null } }), { headers: { 'Content-Type': 'application/json' } })
    if (url.includes('/units/')) return new Response(JSON.stringify({ code: 0, data: project }), { headers: { 'Content-Type': 'application/json' } })
    const asset = project.assetRefs.find(asset => url.includes(encodeURIComponent(asset.assetId)))
    assert.ok(asset)
    return new Response(fs.readFileSync(path.join(canonicalFixtureRoot, asset.artifactName)), { headers: { 'Content-Type': asset.mediaType, ETag: `"${asset.sha256}"`, 'X-Source-Sha256': project.originalSourceSha256, 'X-Projection-Sha256': project.projectionSha256 } })
  } })
  try {
    button(h, 'ANSWER B PROJECT').props.onClick(); await flushCanonical()
    await button(h, 'Show related original images / tables').props.onClick(); await flushCanonical()
    assert.equal(findAll(h.root, node => node.tag === 'img').length, 3)
    const count = h.canonicalRequests.length
    button(h, 'ANSWER A FIXED').props.onClick(); await flushCanonical()
    assert.equal(findAll(h.root, node => node.tag === 'img').length, 0)
    assert.equal(h.canonicalRequests.length, count + 1)
    assert.equal(h.canonicalRequests.at(-1).url, '/api/oer/fixed-baseline/evidence?id=11')
    assert.match(text(h.root), /does not establish project adoption/)
    assert.match(text(h.root), /Fixed source A literal/)
    assert.equal(h.requests.some(request => request.method !== 'get'), false)
  } finally { h.dispose() }
})

function reviewRemarks(h) { return find(h.root, node => node.tag === 'textarea' && node.props.rows === '3') }
const expectedFindingBinding = value => ({ expectedRunId: value.runId,
  expectedGeneratedRevisionSha256: value.generatedRevisionSha256, expectedSourceSnapshotSha256: value.sourceSnapshotSha256 })

test('Vetting manual status and review writes bind the displayed finding revision and source snapshot', async () => {
  let current = { ...finding }
  const h = await harness({ respondOverride: ({ method, url, body }) => {
    if (method === 'post' && url.includes('/status?')) return current = { ...current, status: 'Handled' }
    if (method === 'put' && url.endsWith('/review')) return current = { ...current, ...body }
    if (url.includes('/findings') && !url.endsWith('/evidence')) return [current]
  } })
  try {
    await openFinding(h)
    await button(h, 'Mark handled').props.onClick(); await settle()
    const status = h.requests.find(request => request.url.includes('/status?'))
    const query = new URL(status.url, 'http://localhost').searchParams
    assert.equal(query.get('status'), 'Handled')
    for (const [key, value] of Object.entries(expectedFindingBinding(finding))) assert.equal(query.get(key), value)
    reviewRemarks(h).props['onUpdate:modelValue']('Reviewed the current exception'); await settle()
    await button(h, 'Save review record').props.onClick(); await settle()
    const review = h.requests.find(request => request.method === 'put' && request.url.endsWith('/review'))
    assert.deepEqual(JSON.parse(JSON.stringify(review.body)), { ...expectedFindingBinding(finding), reviewRemarks: 'Reviewed the current exception', actionTaken: '', addendumRequired: null })
    assert.equal(button(h, 'Save review record').props.disabled, true)
    assert.equal(reviewRemarks(h).value, 'Reviewed the current exception')
    assert.match(h.store.toast.message, /Review record saved/)
  } finally { h.dispose() }
})

test('4104 preserves manual drafts and refresh loads the new version without applying a draft with the same finding code', async () => {
  let current = { ...finding }
  const h = await harness({ respondOverride: ({ method, url }) => {
    if (method === 'put' && url.endsWith('/review')) return { code: 4104, message: 'SOURCE_SNAPSHOT_CHANGED', data: null }
    if (url.includes('/findings') && !url.endsWith('/evidence')) return [current]
  } })
  try {
    await openFinding(h)
    reviewRemarks(h).props['onUpdate:modelValue']('Keep my old-version draft'); await settle()
    await button(h, 'Save review record').props.onClick(); await settle()
    assert.equal(reviewRemarks(h).value, 'Keep my old-version draft')
    assert.match(text(h.root), /report or source version changed[\s\S]*draft is retained/i)
    assert.equal(button(h, 'Save review record').props.disabled, true)
    assert.equal(button(h, 'Mark handled').props.disabled, true)
    current = { ...finding, runId: 'run-2', generatedRevisionSha256: '9'.repeat(64), sourceSnapshotSha256: 'a'.repeat(64), reviewRemarks: 'Current report remarks' }
    await button(h, 'Refresh current version').props.onClick(); await settle()
    assert.equal(reviewRemarks(h).value, 'Current report remarks')
    assert.match(text(h.root), /Retained draft from an earlier version[\s\S]*Keep my old-version draft/)
    assert.equal(h.requests.filter(request => request.method === 'put').length, 1)
    reviewRemarks(h).props['onUpdate:modelValue']('New version reviewed explicitly'); await settle()
    await button(h, 'Save review record').props.onClick(); await settle()
    const last = h.requests.filter(request => request.method === 'put').at(-1)
    assert.equal(last.body.expectedRunId, 'run-2')
    assert.equal(last.body.expectedGeneratedRevisionSha256, '9'.repeat(64))
    assert.equal(last.body.expectedSourceSnapshotSha256, 'a'.repeat(64))
    assert.equal(last.body.reviewRemarks, 'New version reviewed explicitly')
  } finally { h.dispose() }
})

test('late successful review cannot replace a newer report or remove the retained draft', async () => {
  let current = { ...finding }
  const pending = deferred()
  const h = await harness({ respondOverride: ({ method, url }) => {
    if (method === 'put' && url.endsWith('/review')) return pending.promise
    if (url.includes('/findings') && !url.endsWith('/evidence')) return [current]
  } })
  try {
    await openFinding(h)
    reviewRemarks(h).props['onUpdate:modelValue']('Draft still under review'); await settle()
    const saving = button(h, 'Save review record').props.onClick(); await settle()
    current = { ...finding, runId: 'run-2', generatedRevisionSha256: 'b'.repeat(64), reviewRemarks: 'New report has its own remarks' }
    find(h.root, node => node.tag === 'input' && node.props.type === 'search').props['onUpdate:modelValue']('A1')
    await settle(); await new Promise(resolve => setTimeout(resolve, 260)); await settle()
    assert.match(text(h.root), /report or source version changed/i)
    pending.resolve({ ...finding, reviewRemarks: 'Draft still under review' }); await saving; await settle()
    assert.equal(reviewRemarks(h).value, 'Draft still under review')
    assert.equal(button(h, 'Save review record').props.disabled, true)
    assert.doesNotMatch(h.store.toast.message, /Review record saved/)
    await button(h, 'Refresh current version').props.onClick(); await settle()
    assert.equal(reviewRemarks(h).value, 'New report has its own remarks')
    assert.match(text(h.root), /Retained draft from an earlier version[\s\S]*Draft still under review/)
  } finally { h.dispose() }
})

test('late status success cannot overwrite the currently selected newer report', async () => {
  let current = { ...finding }
  const pending = deferred()
  const h = await harness({ respondOverride: ({ method, url }) => {
    if (method === 'post' && url.includes('/status?')) return pending.promise
    if (url.includes('/findings') && !url.endsWith('/evidence')) return [current]
  } })
  try {
    await openFinding(h)
    const saving = button(h, 'Mark handled').props.onClick(); await settle()
    current = { ...finding, runId: 'run-2', generatedRevisionSha256: 'c'.repeat(64), title: localized('New report finding') }
    find(h.root, node => node.tag === 'input' && node.props.type === 'search').props['onUpdate:modelValue']('A1')
    await settle(); await new Promise(resolve => setTimeout(resolve, 260)); await settle()
    await openFinding(h)
    pending.resolve({ ...finding, status: 'Handled' }); await saving; await settle()
    assert.match(text(h.root), /New report finding/)
    assert.equal(button(h, 'Mark handled').props.disabled, false)
    assert.doesNotMatch(h.store.toast.message, /F001 · Handled/)
  } finally { h.dispose() }
})

const checklistFixture = { projectId: 'project-A', runId: 'run-1', runSnapshotSha256: 'd'.repeat(64), sourceSnapshotSha256: finding.sourceSnapshotSha256, writable: true,
  items: [{ id: 'pre-1', section: 'PRE', clause: 'PSS A1', description: 'Verify exception', templatePage: 1, status: 'unchecked', remarks: '' }] }
async function expandChecklist(h) {
  const panel = find(h.root, node => node.tag === 'details' && node.props.class?.includes('checklist-panel'))
  assert.ok(panel)
  await panel.props.onToggle({ target: { open: true } }); await settle()
}
function checklistRemarks(h) { return find(h.root, node => node.tag === 'textarea' && node.props['aria-label'] === 'PSS A1 Remarks by Contract Team') }

test('checklist writes bind the loaded snapshot; 4104 retains the row and refresh archives it without carrying values forward', async () => {
  let current = JSON.parse(JSON.stringify(checklistFixture))
  const h = await harness({ respondOverride: ({ method, url }) => {
    if (url.endsWith('/runs/latest/progress') || url.endsWith('/runs/run-1')) return { id: 'run-1', status: 'COMPLETED', sourceSnapshotSha256: finding.sourceSnapshotSha256 }
    if (url.endsWith('/checklist')) return method === 'put' ? { code: 4104, message: 'CHECKLIST_SNAPSHOT_CHANGED', data: null } : current
  } })
  try {
    await expandChecklist(h)
    checklistRemarks(h).props['onUpdate:modelValue']('Do not lose the old checklist note'); await settle()
    await button(h, 'Save this check').props.onClick(); await settle()
    const write = h.requests.find(request => request.method === 'put' && request.url.endsWith('/checklist'))
    assert.deepEqual(JSON.parse(JSON.stringify(write.body)), { expectedRunId: 'run-1', expectedSourceSnapshotSha256: finding.sourceSnapshotSha256,
      runSnapshotSha256: checklistFixture.runSnapshotSha256, items: [{ id: 'pre-1', status: 'unchecked', remarks: 'Do not lose the old checklist note' }] })
    assert.equal(checklistRemarks(h).value, 'Do not lose the old checklist note')
    assert.equal(button(h, 'Save this check').props.disabled, true)
    assert.match(text(h.root), /report or source version changed/i)
    current = { ...current, runSnapshotSha256: 'e'.repeat(64), sourceSnapshotSha256: 'f'.repeat(64), items: [{ ...current.items[0], remarks: 'Fresh checklist baseline' }] }
    await button(h, 'Refresh current version').props.onClick(); await settle()
    assert.equal(checklistRemarks(h).value, 'Fresh checklist baseline')
    assert.match(text(h.root), /Retained draft from an earlier version[\s\S]*Do not lose the old checklist note/)
    assert.equal(h.requests.filter(request => request.method === 'put').length, 1)
  } finally { h.dispose() }
})

test('project original link requires the saved raw byte SHA and never substitutes the current job snapshot', async () => {
  const h = await harness({ respondOverride: ({ url }) => url.endsWith('/evidence')
    ? { ...evidence, items: evidence.items.map(item => ({ ...item, originalSourceSha256: null })) } : undefined })
  try {
    await openFinding(h)
    assert.equal(find(h.root, node => node.tag === 'a' && node.props.href?.includes('/sources/12/original?')), undefined)
    assert.match(text(h.root), /Clause A1: shall NOT apply/)
    assert.equal(h.requests.some(request => request.method !== 'get'), false)
  } finally { h.dispose() }
})

test('checklist late success after project ABA cannot alter the freshly loaded checklist or erase its new edits', async () => {
  const pending = deferred()
  let current = JSON.parse(JSON.stringify(checklistFixture))
  const h = await harness({ respondOverride: ({ method, url }) => {
    if (url.endsWith('/runs/latest/progress') || url.endsWith('/runs/run-1')) return { id: 'run-1', status: 'COMPLETED', sourceSnapshotSha256: finding.sourceSnapshotSha256 }
    if (url.endsWith('/checklist')) return method === 'put' ? pending.promise : current
  } })
  try {
    await expandChecklist(h)
    checklistRemarks(h).props['onUpdate:modelValue']('Submitted old checklist'); await settle()
    const saving = button(h, 'Save this check').props.onClick(); await settle()
    await h.store.switchProject('project-B'); await settle()
    current = { ...checklistFixture, runSnapshotSha256: '2'.repeat(64), sourceSnapshotSha256: '3'.repeat(64), items: [{ ...checklistFixture.items[0], remarks: 'Fresh loaded checklist' }] }
    await h.store.switchProject('project-A'); await settle(); await expandChecklist(h)
    checklistRemarks(h).props['onUpdate:modelValue']('Fresh unsaved checklist note'); await settle()
    pending.resolve({ ...checklistFixture, items: [{ ...checklistFixture.items[0], remarks: 'Submitted old checklist' }] }); await saving; await settle()
    assert.equal(checklistRemarks(h).value, 'Fresh unsaved checklist note')
    assert.equal(button(h, 'Save this check').props.disabled, false)
    assert.doesNotMatch(h.store.toast.message, /Check saved/)
    await button(h, 'Restore saved value').props.onClick(); await settle()
    assert.equal(checklistRemarks(h).value, 'Fresh loaded checklist')
  } finally { h.dispose() }
})

test('4104 status rejection preserves existing unsaved review remarks and blocks reapplication to a stale report', async () => {
  const h = await harness({ respondOverride: ({ method, url }) => method === 'post' && url.includes('/status?')
    ? { code: 4104, message: 'GENERATED_REVISION_CHANGED', data: null } : undefined })
  try {
    await openFinding(h)
    reviewRemarks(h).props['onUpdate:modelValue']('Unsaved draft before status change'); await settle()
    await button(h, 'Mark handled').props.onClick(); await settle()
    assert.equal(reviewRemarks(h).value, 'Unsaved draft before status change')
    assert.equal(button(h, 'Mark handled').props.disabled, true)
    assert.equal(button(h, 'Save review record').props.disabled, true)
    assert.match(text(h.root), /report or source version changed[\s\S]*draft is retained/i)
    assert.equal(h.requests.filter(request => request.method === 'post').length, 1)
  } finally { h.dispose() }
})

test('reference-only session skips project index reads and late project failures cannot contaminate its readiness message', async () => {
  const oldStatus = deferred()
  const h = await harness({ initialView: 'advice', respondOverride: ({ url }) => {
    if (url.endsWith('/sessions')) return sessions
    if (url.includes('/index/status?')) return oldStatus.promise
    if (url.endsWith('/ask/stream')) return { code: 4205, message: 'fixed final-wire budget unavailable', data: null }
  } })
  try {
    const reads = h.requests.filter(request => /\/(?:sources|index\/status)\?/.test(request.url)).length
    sessionSelect(h).props['onUpdate:modelValue']('session-B'); await settle()
    assert.equal(h.requests.filter(request => /\/(?:sources|index\/status)\?/.test(request.url)).length, reads)
    assert.equal(button(h, 'Refresh evidence index'), undefined)
    oldStatus.resolve({ code: 4104, message: 'BGE-M3 unavailable', data: null }); await settle()
    assert.doesNotMatch(text(h.root), /BGE-M3|could not be loaded|Ready|Index ready/)
    assert.match(text(h.root), /fixed standards only[\s\S]*has not confirmed fixed retrieval readiness/)
    find(h.root, node => node.tag === 'textarea').props['onUpdate:modelValue']('Verify the fixed provision'); await settle()
    await button(h, 'Ask').props.onClick(); await settle()
    assert.match(text(h.root), /fixed final-wire budget unavailable/)
    assert.equal(find(h.root, node => node.tag === 'textarea').value, 'Verify the fixed provision')
    assert.equal(h.requests.find(request => request.url.endsWith('/ask/stream')).body.basisScope, 'reference_only')
  } finally { h.dispose() }
})
