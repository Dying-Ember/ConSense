const assert = require('node:assert/strict')
const path = require('node:path')
const crypto = require('node:crypto')
const { test } = require('node:test')
const vue = require('vue')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, text, find, settle, deferred } = require('./helpers/render-controls.cjs')
const hash = value => crypto.createHash('sha256').update(value).digest('hex')
// Authored contract bytes; no claim of source quality or real PDF rendering.
const pdf = Buffer.from('%PDF-authored fixture')
const address = { evidenceBackend: 'fixed_development_baseline', parentId: 'same/project?id', manifestSha256: 'a'.repeat(64), baselineVersion: '05', sourceFamily: 'GCC', sourceRole: 'standard', originalSourceSha256: hash(pdf), originalPageNos: [3,4], pageNo: 3, projectApplicability: 'unconfirmed', qualifiersComplete: 'unknown', sourceQualityAccepted: false }
const table = { id: address.parentId, originalSourceSha256: address.originalSourceSha256, observedCells: [{ text: 'Full note <script>bad()</script>' }], sourceQualityAccepted: false }
const tableBytes = Buffer.from(JSON.stringify(table))
const evidence = () => ({ id: address.parentId, fileLabel: 'GCC fixed reference', sourceFamily: 'GCC', content: 'Literal complete standard\nException remains.', pageNo: 3, sourceUnit: null, tableArtifact: table,
  payload: { fixedBaselineManifestSha256: address.manifestSha256, fixedBaselineVersion: address.baselineVersion, originalSourceSha256: address.originalSourceSha256, sourceRole: 'standard', role: 'standard', documentId: 'GCC:fixture', sourceHash: 'c'.repeat(64), tableArtifact: { sha256: hash(tableBytes), bytes: tableBytes.length }, sourceQuality: { finalQualityAccepted: false }, unknowns: ['condition relation unknown'] } })
const envelope = data => new Response(JSON.stringify({ code: 0, data }), { headers: { 'Content-Type': 'application/json' } })
const globals = { Blob, Response, AbortController, Uint8Array, ArrayBuffer, TextDecoder, Error, crypto: crypto.webcrypto }
function api(fetch) {
  const sha = loadTypeScript(path.join(__dirname, '../src/api/sha256Bytes.ts'), { globals, dependencies: { '@noble/hashes/sha256': require('@noble/hashes/sha256') } })
  return loadTypeScript(path.join(__dirname, '../src/api/fixedBaselineEvidence.ts'), { globals: { ...globals, fetch }, dependencies: { './sha256Bytes': sha } })
}
async function flush() { for (let i = 0; i < 10; i++) { await new Promise(resolve => setImmediate(resolve)); await settle() } }
async function waitFor(predicate) {
  const deadline = Date.now() + 5000
  while (!predicate() && Date.now() < deadline) { await new Promise(resolve => setTimeout(resolve, 10)); await settle() }
  assert.ok(predicate(), 'The observed async panel state did not complete within five seconds')
}

test('fixed source paths ignore supplied URLs and pin original/manifest/version/source kind despite ID collisions', async () => {
  const calls = [], client = api(async url => { calls.push(url); return envelope(evidence()) })
  assert.match(client.fixedEvidencePath({ ...address, originalUrl: 'https://bad.invalid/' }, 'original'), /^\/api\/oer\/fixed-baseline\/original\?sourceSha256=/)
  await client.fetchFixedEvidence(address)
  assert.equal(calls[0], '/api/oer/fixed-baseline/evidence?id=same%2Fproject%3Fid')
  for (const key of ['fixedBaselineManifestSha256', 'fixedBaselineVersion', 'originalSourceSha256', 'sourceRole']) {
    const changed = evidence(); changed.payload[key] = 'changed'
    await assert.rejects(api(async () => envelope(changed)).fetchFixedEvidence(address), /FIXED_EVIDENCE_IDENTITY_MISMATCH/)
  }
  const changed = evidence(); changed.id = 'project-source-with-same-name'
  await assert.rejects(api(async () => envelope(changed)).fetchFixedEvidence(address), /FIXED_EVIDENCE_IDENTITY_MISMATCH/)
  await assert.rejects(api(async () => new Response('', { status: 409 })).fetchFixedEvidence(address), /FIXED_EVIDENCE_STALE/)
})

test('fixed original and complete table recompute byte hashes and reject same-size mutations or different table source', async () => {
  const client = api(async url => url.includes('/original?')
    ? new Response(pdf, { headers: { 'Content-Type': 'application/pdf', 'X-Source-SHA256': address.originalSourceSha256 } })
    : new Response(tableBytes, { headers: { 'Content-Type': 'application/json', 'X-Source-SHA256': hash(tableBytes) } }))
  assert.equal((await client.fetchFixedOriginal(address)).size, pdf.length)
  assert.deepEqual(JSON.parse(JSON.stringify(await client.fetchFixedTable(address, evidence().payload.tableArtifact))), table)
  await assert.rejects(api(async () => new Response(Buffer.from('%PDF-mutated fixture'), { headers: { 'Content-Type': 'application/pdf', 'X-Source-SHA256': address.originalSourceSha256 } })).fetchFixedOriginal(address), /FIXED_ORIGINAL_SHA_MISMATCH/)
  const changed = Buffer.from(JSON.stringify({ ...table, id: 'other' }))
  await assert.rejects(api(async () => new Response(changed, { headers: { 'Content-Type': 'application/json', 'X-Source-SHA256': hash(changed) } })).fetchFixedTable(address, { sha256: hash(changed), bytes: changed.length }), /FIXED_TABLE_IDENTITY_MISMATCH/)
})

test('rendered fixed panel displays full table and unknown adoption, only reads fixed lane and defers original until click', async () => {
  const calls = [], urls = [], revoked = []
  const loaded = loadVue(path.join(__dirname, '../src/components/FixedBaselineEvidencePanel.vue'), { globals: { ...globals,
    URL: { createObjectURL() { const value = 'blob:fixed-source'; urls.push(value); return value }, revokeObjectURL(value) { revoked.push(value) } },
    fetch: async url => { calls.push(url); return url.includes('/evidence?') ? envelope(evidence()) : url.includes('/table?')
      ? new Response(tableBytes, { headers: { 'Content-Type': 'application/json', 'X-Source-SHA256': hash(tableBytes) } })
      : new Response(pdf, { headers: { 'Content-Type': 'application/pdf', 'X-Source-SHA256': address.originalSourceSha256 } }) }
  }, boundaries: { '@noble/hashes/sha256': require('@noble/hashes/sha256'), '@/components/DraftingPdfPreview.vue': { __esModule: true, default: { props: ['source', 'initialPage'], render() { return vue.h('div', `Existing PDF.js viewer: ${this.source} page ${this.initialPage}`) } } } } })
  const rendered = renderer(), props = vue.reactive({ pointer: { ...address }, locale: 'en' }), app = rendered.createApp({ render: () => vue.h(loaded.default, props) })
  app.mount(rendered.root); await flush()
  try {
    assert.equal(calls.length, 2); assert.ok(calls.every(url => url.startsWith('/api/oer/fixed-baseline/')))
    assert.match(text(rendered.root), /project adoption is unconfirmed/)
    assert.match(text(rendered.root), /Exception remains/); assert.match(text(rendered.root), /Full note/)
    assert.equal(find(rendered.root, node => node.tag === 'script'), undefined)
    find(rendered.root, node => node.tag === 'button' && text(node) === 'Read the complete original PDF').props.onClick()
    await waitFor(() => /byte SHA-256 verified/.test(text(rendered.root)))
    assert.match(text(rendered.root), /byte SHA-256 verified/); assert.equal(urls.length, 1)
    await waitFor(() => /Existing PDF.js viewer: blob:fixed-source page 3/.test(text(rendered.root)))
    props.pointer = { ...address, parentId: 'unavailable-source' }; await flush()
    assert.equal(revoked.length, 1); assert.match(text(rendered.root), /fixed baseline or original changed/)
    assert.equal(urls.length, 1)
  } finally { app.unmount() }
})

test('late fixed evidence cannot replace a newly selected source and aborts before any assets', async () => {
  const pending = deferred(), calls = []
  const loaded = loadVue(path.join(__dirname, '../src/components/FixedBaselineEvidencePanel.vue'), { globals: { ...globals, URL: { revokeObjectURL() {} }, fetch: async (url, options) => { calls.push({ url, options }); return calls.length === 1 ? pending.promise : new Response('', { status: 404 }) } }, boundaries: { '@noble/hashes/sha256': require('@noble/hashes/sha256') } })
  const rendered = renderer(), props = vue.reactive({ pointer: address, locale: 'en' }), app = rendered.createApp({ render: () => vue.h(loaded.default, props) })
  app.mount(rendered.root); await flush(); props.pointer = { ...address, parentId: 'new' }; await flush()
  pending.resolve(envelope(evidence())); await flush()
  assert.equal(calls[0].options.signal.aborted, true); assert.equal(calls.length, 2)
  assert.match(text(rendered.root), /cited source is unavailable/); assert.doesNotMatch(text(rendered.root), /Literal complete standard/)
  app.unmount()
})

test('whole fixed group verifies every required member against its saved source identity and keeps reliable members when another is missing', async () => {
  const unit = { requiredMemberIds: [address.parentId, 'qualifier', 'missing', 'wrong-source'], sourceIdentity: { documentId: 'GCC:fixture', sourceHash: 'c'.repeat(64), role: 'standard' }, qualifiersComplete: 'unknown', semanticScopeVerified: false }
  const calls = [], client = api(async url => {
    calls.push(url)
    const id = new URL(url, 'http://local').searchParams.get('id')
    if (id === 'missing') return new Response('', { status: 404 })
    if (url.includes('/table?')) return new Response(tableBytes, { headers: { 'Content-Type': 'application/json', 'X-Source-SHA256': hash(tableBytes) } })
    const member = evidence(); member.id = id
    if (id !== address.parentId) { member.tableArtifact = null; delete member.payload.tableArtifact }
    if (id === 'qualifier') { member.content = 'Complete exception and condition'; member.payload.originalSourceSha256 = 'd'.repeat(64); delete member.payload.sourceRole }
    if (id === 'wrong-source') member.payload.sourceHash = 'e'.repeat(64)
    return envelope(member)
  })
  const result = await client.fetchFixedEvidenceGroup(address, unit)
  assert.deepEqual(Array.from(result.members, member => member.evidence.id), [address.parentId, 'qualifier'])
  assert.deepEqual(Array.from(result.missingMemberIds), ['missing', 'wrong-source'])
  assert.equal(result.members[1].evidence.payload.originalSourceSha256, 'd'.repeat(64), 'Member bytes retain their own returned source SHA; the origin hash is not substituted')
  assert.equal(result.members[0].table.originalSourceSha256, address.originalSourceSha256)
  assert.ok(unit.requiredMemberIds.every(id => calls.includes('/api/oer/fixed-baseline/evidence?id=' + encodeURIComponent(id))))
})

test('saved pointer supplies complete group when read-only parent response has no sourceUnit and marks unavailable conditions', async () => {
  const unit = { requiredMemberIds: [address.parentId, 'condition', 'missing'], sourceIdentity: { documentId: 'GCC:fixture', sourceHash: 'c'.repeat(64), role: 'standard' }, presentMemberIds: [address.parentId, 'condition'], missingMemberIds: [], unknown: ['qualifier relation'], qualifiersComplete: 'unknown', semanticScopeVerified: false }
  const calls = []
  const loaded = loadVue(path.join(__dirname, '../src/components/FixedBaselineEvidencePanel.vue'), { globals: { ...globals, URL: { revokeObjectURL() {} }, fetch: async url => {
    calls.push(url); const id = new URL(url, 'http://local').searchParams.get('id')
    if (id === 'missing') return new Response('', { status: 404 })
    const data = evidence(); data.id = id; data.tableArtifact = null; delete data.payload.tableArtifact
    if (id === 'condition') data.content = 'A complete required exception on another page'
    return envelope(data)
  } }, boundaries: { '@noble/hashes/sha256': require('@noble/hashes/sha256') } })
  const rendered = renderer(), app = rendered.createApp({ render: () => vue.h(loaded.default, { pointer: { ...address, sourceUnit: unit }, locale: 'en' }) })
  app.mount(rendered.root); await flush()
  try {
    assert.equal(calls.length, 4)
    assert.match(text(rendered.root), /A complete required exception on another page/)
    assert.match(text(rendered.root), /Missing members \/ assets: missing/)
    assert.match(text(rendered.root), /relations remain unverified/)
    assert.equal(calls.some(url => /\/ask|\/projects/.test(url)), false)
  } finally { app.unmount() }
})
