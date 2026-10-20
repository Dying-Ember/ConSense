const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { test } = require('node:test')
const vue = require('vue')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const { loadVue } = require('./helpers/load-vue.cjs')
const { text, find, settle, deferred, renderer } = require('./helpers/render-controls.cjs')

// A shared Python-authored wire fixture; it is intentionally not source-quality gold.
const fixtureRoot = path.resolve(__dirname, '../../ai_consense_service/src/test/resources/oer/canonical')
const projection = JSON.parse(fs.readFileSync(path.join(fixtureRoot, 'authored-three-page-projection.json'), 'utf8'))
const nativeProjection = JSON.parse(fs.readFileSync(path.join(fixtureRoot, 'authored-native-public-v2.json'), 'utf8'))
// Actual production Java EvidencePointer method + serializer, using the authored Python normal graph.
const normalPointers = JSON.parse(fs.readFileSync(path.join(__dirname, 'fixtures/authored-normal-java-pointers.json'), 'utf8'))
const table = projection.chunks.find(chunk => chunk.unitId === 'table')
const clone = value => JSON.parse(JSON.stringify(value))
const baseAddress = {
  addressKind: 'canonical_adoption_unit', projectId: 'project-A', documentId: 11, module: 'vetting',
  unitId: table.unitId, canonicalChunkId: table.id,
  originalSourceSha256: projection.source.sourceSha256, javaParsedSourceHash: '1'.repeat(64),
  sourceRevisionSha256: projection.source.sourceRevisionSha256, canonicalSourceHash: projection.sourceHash,
  projectionSha256: projection.projectionSha256, projectionRevisionSha256: projection.projectionRevisionSha256,
  sourceFilename: projection.source.sourceFilename, sourceOrder: table.sourceOrder
}
function pointer(address = baseAddress) {
  return clone({ ...address, bboxConvention: 'normalized_xyxy_original_page', humanConfirmed: false,
    physicalPages: table.physicalPages, eligibleModules: table.eligibleModules, parts: table.parts,
    sourceSpans: table.sourceSpans, tableGroup: table.tableGroup, assetRefs: table.assetRefs,
    relations: table.relations, adoption: table.adoption, sourceQuality: table.sourceQuality, unknowns: projection.unknowns })
}
function nativePointer(index = 1) {
  const chunk = nativeProjection.chunks[index]
  const address = { ...baseAddress, documentId: 42, unitId: chunk.unitId, canonicalChunkId: chunk.id,
    originalSourceSha256: nativeProjection.source.sourceSha256, sourceRevisionSha256: nativeProjection.source.sourceRevisionSha256,
    canonicalSourceHash: nativeProjection.sourceHash, projectionSha256: nativeProjection.projectionSha256,
    projectionRevisionSha256: nativeProjection.projectionRevisionSha256, sourceFilename: nativeProjection.source.sourceFilename,
    sourceOrder: chunk.sourceOrder }
  return clone({ ...address, projectionSchema: nativeProjection.schema, producerKind: nativeProjection.producerKind,
    storageShape: nativeProjection.storageShape, originalGeometryStatus: chunk.originalGeometryStatus,
    visualReturnStatus: nativeProjection.visualReturnStatus, humanConfirmed: false,
    physicalPages: chunk.physicalPages, eligibleModules: chunk.eligibleModules, parts: chunk.parts,
    sourceSpans: chunk.sourceSpans, tableGroup: chunk.tableGroup, assetRefs: chunk.assetRefs,
    relations: chunk.relations, adoption: chunk.adoption, sourceQuality: chunk.sourceQuality, unknowns: nativeProjection.unknowns })
}
function normalPointer(index = 1) {
  return clone(normalPointers[index])
}
const apiPath = path.resolve(__dirname, '../src/api/canonicalEvidence.ts')
const runtime = { Blob, AbortController, Uint8Array, ArrayBuffer, crypto: crypto.webcrypto }
const shaModulePath = path.resolve(__dirname, '../src/api/sha256Bytes.ts')
const portableSha = require('@noble/hashes/sha256')
function shaHelper(globals = {}) { return loadTypeScript(shaModulePath, { globals: { ...runtime, setTimeout, ...globals }, dependencies: { '@noble/hashes/sha256': portableSha } }) }
function api(fetch, globals = {}) { return loadTypeScript(apiPath, { globals: { ...runtime, ...globals, fetch }, dependencies: { './sha256Bytes': shaHelper(globals) } }) }
function rejects(reason) { return error => error.reason === reason }
function envelope(data) { return new Response(JSON.stringify({ code: 0, data }), { headers: { 'Content-Type': 'application/json' } }) }
function assetResponse(asset, { bytes, headers = {}, status = 200 } = {}) {
  return new Response(bytes ?? fs.readFileSync(path.join(fixtureRoot, asset.artifactName)), { status, headers: {
    'Content-Type': asset.mediaType, 'X-Source-Sha256': asset.sourceSha256,
    'X-Projection-Sha256': baseAddress.projectionSha256, ETag: `"${asset.sha256}"`, ...headers
  } })
}
function all(node, predicate) { return [...(predicate(node) ? [node] : []), ...(node.children ?? []).flatMap(child => all(child, predicate))] }
async function flush() { for (let i = 0; i < 12; i++) { await new Promise(resolve => setImmediate(resolve)); await settle() } }
async function waitFor(predicate) {
  const deadline = Date.now() + 5000
  while (!predicate() && Date.now() < deadline) { await new Promise(resolve => setTimeout(resolve, 10)); await settle() }
  assert.ok(predicate(), 'The observed async panel state did not complete within five seconds')
}
async function harness({ data = pointer(), initialPointer = baseAddress, respond, browserCrypto = crypto.webcrypto, pendingLoad = false } = {}) {
  const requests = [], created = [], revoked = []
  const fetch = async (url, options) => {
    requests.push({ url, options })
    if (respond) return respond(url, options, data)
    if (url.includes('/units/')) return envelope(data)
    const asset = data.assetRefs.find(asset => url.includes(encodeURIComponent(asset.assetId)))
    assert.ok(asset, `Unexpected URL ${url}`)
    return assetResponse(asset)
  }
  const loaded = loadVue(path.resolve(__dirname, '../src/components/CanonicalEvidencePanel.vue'), { boundaries: { '@noble/hashes/sha256': portableSha }, globals: {
    ...runtime, crypto: browserCrypto, fetch,
    URL: { createObjectURL(blob) { const url = `blob:canonical-${created.length}`; created.push({ url, blob }); return url },
      revokeObjectURL(url) { revoked.push(url) } }
  } })
  const props = vue.reactive({ pointer: clone(initialPointer), locale: 'en' })
  const rendered = renderer(), app = rendered.createApp({ render: () => vue.h(loaded.default, props) })
  app.mount(rendered.root); await flush()
  if (initialPointer && !pendingLoad) await waitFor(() => find(rendered.root, node => node.tag === 'button'))
  return { ...rendered, props, requests, created, revoked, dispose: () => app.unmount() }
}

test('canonical address pins every identity and rejects retrieval episode IDs as adoption addresses', () => {
  const helper = api()
  assert.equal(helper.validateCanonicalPointer(baseAddress, pointer()).humanConfirmed, false)
  for (const field of ['addressKind', 'projectId', 'unitId', 'canonicalChunkId', 'sourceFilename', 'sourceOrder',
    'originalSourceSha256', 'javaParsedSourceHash', 'sourceRevisionSha256', 'canonicalSourceHash', 'projectionSha256', 'projectionRevisionSha256']) {
    const data = pointer(); data[field] = field === 'sourceOrder' ? 8 : `changed-${data[field]}`
    assert.throws(() => helper.validateCanonicalPointer(baseAddress, data), rejects(`CANONICAL_IDENTITY_MISMATCH:${field}`))
  }
  const data = pointer(); data.documentId = 12
  assert.throws(() => helper.validateCanonicalPointer(baseAddress, data), rejects('CANONICAL_IDENTITY_MISMATCH:documentId'))
  assert.throws(() => helper.validateCanonicalAddress({ ...baseAddress, addressKind: 'retrieval_episode_unit' }), rejects('CANONICAL_ADOPTION_ADDRESS_REQUIRED'))
  assert.throws(() => helper.validateCanonicalPointer({ ...baseAddress, unitId: 'episode-1:source-unit-0' }, pointer()), rejects('CANONICAL_IDENTITY_MISMATCH:unitId'))
})

test('normal observed citations read complete bound Word and table units with unknown authority and no fabricated pages', async () => {
  for (const index of [0, 1]) {
    const data = normalPointer(index), requests = []
    const helper = api(async (url, options) => { requests.push({ url, options }); return envelope(data) })
    const received = await helper.fetchCanonicalPointer(data)
    assert.deepEqual(clone(received.parts), data.parts)
    assert.deepEqual(clone(received.sourceSpans), data.sourceSpans)
    assert.deepEqual(clone(helper.orderedEvidenceParts(received)), data.parts)
    assert.equal(received.humanConfirmed, false)
    assert.equal(received.sourceQuality.sourceQualityAccepted, false)
    assert.equal(received.originalGeometryStatus, 'unknown')
    assert.equal(helper.evidenceCoverage(received).status, 'no_assets')
    assert.equal(requests.length, 1)
    assert.equal(requests[0].options.method, 'GET')
    assert.match(requests[0].url, /\/units\/candidate-unit%3A[a-f0-9]+\?module=vetting$/)
  }
})

test('normal citation panel retains native locations and literal text without claiming reviewed geometry or images', async () => {
  const data = normalPointer(), h = await harness({ data, initialPointer: data })
  try {
    const shown = text(h.root)
    assert.match(shown, /Normal parser observations/)
    assert.match(shown, /body\/2/)
    assert.ok(shown.includes(data.parts[0].text))
    assert.match(shown, /Not human-confirmed/)
    assert.doesNotMatch(shown, /P0|normalized xyxy position|Original PDF physical pages/)
    assert.equal(all(h.root, node => node.tag === 'article').length, 1)
    assert.equal(all(h.root, node => node.tag === 'img').length, 0)
    assert.equal(h.requests.length, 1)
  } finally { h.dispose() }
})

test('normal discriminator cannot promote review authority, invent frames, or change complete UTF16 source spans', () => {
  const helper = api()
  for (const mutate of [
    data => { data.addressKind = 'canonical_adoption_unit' }, data => { data.projectionSchema = 'consense.canonical-projection.v1' },
    data => { data.producerKind = 'native_docx' }, data => { data.adoption = {} }, data => { data.humanConfirmed = true },
    data => { data.sourceQuality.sourceQualityAccepted = true }, data => { data.sourceQuality.semanticScopeVerified = true },
    data => { data.sourceQuality.legalClosure = 'accepted' }, data => { data.originalGeometryStatus = 'reviewed' },
    data => { data.bboxConvention = 'normalized_xyxy_original_page' }, data => { data.physicalPages = [0] },
    data => { data.parts[0].physicalPageNo = 0 }, data => { data.parts[0].endOffset-- },
    data => { data.sourceSpans[0].sourcePosition.nodePath = 'borrowed' }, data => { data.sourceSpans.pop() },
    data => { data.sourcePageRenderRefs = [] }, data => { data.assetRefs = pointer().assetRefs }
  ]) {
    const data = normalPointer(); mutate(data)
    assert.throws(() => helper.validateCanonicalPointer(data, data), error => error.name === 'CanonicalEvidenceError')
  }
})

test('known current-binding failure reasons require the exact safe enum and matching HTTP status', async () => {
  for (const [status, reason] of [[404, 'CANONICAL_BINDING_UNAVAILABLE'], [409, 'CANONICAL_SOURCE_STALE'], [409, 'SOURCE_ROUTING_STALE'], [409, 'SOURCE_ORIGINAL_BYTES_CHANGED']]) {
    await assert.rejects(api(async () => new Response(JSON.stringify({ code: 4300, message: reason }), { status })).fetchCanonicalPointer(baseAddress), rejects(reason))
  }
  for (const [status, message] of [[409, 'UNKNOWN_CONFLICT'], [500, 'SOURCE_ORIGINAL_BYTES_CHANGED'], [404, 'CANONICAL_SOURCE_STALE'], [409, 'SOURCE_ROUTING_STALE https://other.invalid/new-image'], [409, '<img src=x>']]) {
    await assert.rejects(api(async () => new Response(JSON.stringify({ code: 4300, message }), { status })).fetchCanonicalPointer(baseAddress), rejects(`HTTP_${status}`))
  }
  await assert.rejects(api(async () => new Response('not JSON', { status: 409 })).fetchCanonicalPointer(baseAddress), rejects('HTTP_409'))
})

test('region pointer accepts omitted optional group from Spring NON_NULL and normalizes it to null', async () => {
  const data = pointer()
  data.adoption.scope = 'region'
  delete data.tableGroup
  const received = await api(async () => envelope(data)).fetchCanonicalPointer(baseAddress)
  assert.equal(received.tableGroup, null)
  assert.deepEqual(received.parts, data.parts)
  assert.deepEqual(received.assetRefs, data.assetRefs)
  assert.deepEqual(received.sourceQuality, data.sourceQuality)
  assert.deepEqual(received.unknowns, data.unknowns)
  assert.equal(Object.hasOwn(data, 'tableGroup'), false, 'Wire object is not rewritten')
  data.tableGroup = null
  assert.equal(api().validateCanonicalPointer(baseAddress, data).tableGroup, null)
})

test('whole table cannot use an omitted or null group and malformed present groups are rejected', () => {
  const helper = api()
  for (const group of [undefined, null]) {
    const data = pointer()
    data.adoption.scope = 'table_group'
    if (group === undefined) delete data.tableGroup
    else data.tableGroup = group
    assert.throws(() => helper.validateCanonicalPointer(baseAddress, data), rejects('CANONICAL_TABLE_GROUP_INVALID'))
  }
  for (const group of [false, 0, '', []]) {
    const data = pointer()
    data.adoption.scope = 'region'
    data.tableGroup = group
    assert.throws(() => helper.validateCanonicalPointer(baseAddress, data), rejects('CANONICAL_TABLE_GROUP_INVALID'))
  }
})

test('actual native compact producer wire retains empty nested parts, channels and complete topology', () => {
  const helper = api()
  for (const index of [0, 1, 2]) {
    const data = nativePointer(index), received = helper.validateCanonicalPointer(data, data)
    assert.equal(helper.isNativeEvidence(received), true)
    assert.deepEqual(received.parts, nativeProjection.chunks[index].parts)
    assert.deepEqual(received.tableGroup, nativeProjection.chunks[index].tableGroup ?? null)
    assert.deepEqual(clone(helper.orderedEvidenceParts(received)), received.parts)
    assert.equal(helper.evidenceCoverage(received).status, 'no_assets')
    assert.deepEqual(received.physicalPages, [])
  }
  const data = nativePointer()
  assert.equal(data.parts.filter(part => part.text === '').length, 3)
  assert.equal(data.tableGroup.nativeTableTopology.length, 2)
  assert.equal(nativePointer(0).parts[0].text.length, 40, 'Actual astral text uses UTF16 range')
})

test('native discriminator cannot bypass scanned gates or invent Word page and bbox aliases', () => {
  const helper = api()
  for (const mutate of [
    data => { data.producerKind = 'scanned_pdf' }, data => { data.storageShape = 'unknown' },
    data => { data.visualReturnStatus = 'verified' }, data => { data.originalGeometryStatus = 'normalized_xyxy_original_page' },
    data => { data.bboxConvention = 'normalized_xyxy_original_page' }, data => { data.physicalPages = [1] },
    data => { data.assetRefs = pointer().assetRefs }, data => { data.projectionSchema = 'consense.canonical-projection.v1' },
    data => { delete data.projectionSchema }
  ]) {
    const data = nativePointer(); mutate(data)
    assert.throws(() => helper.validateCanonicalPointer(data, data), error => error.name === 'CanonicalEvidenceError')
  }
  for (const alias of ['pageNo', 'physicalPageNo', 'bbox', 'bboxConvention']) {
    const data = nativePointer(); data.parts[0][alias] = null
    assert.throws(() => helper.validateCanonicalPointer(data, data), rejects('CANONICAL_NATIVE_PDF_ALIAS_FORBIDDEN'))
  }
  const data = pointer(); data.projectionSchema = 'consense.canonical-projection.v2'
  assert.throws(() => helper.validateCanonicalPointer(baseAddress, data), rejects('CANONICAL_NATIVE_DISPLAY_CONTRACT_INVALID'))
})

test('native exact position, UTF16 channel and CAS source links reject altered wire', () => {
  const helper = api()
  for (const mutate of [
    data => { data.parts[0].sourcePosition.partName = '../word/document.xml' },
    data => { data.parts[0].sourcePosition.nodePath = '/paragraph-1' },
    data => { data.parts[0].sourcePosition.sourceSha256 = '0'.repeat(64) },
    data => { data.parts[0].textTransform.sourceEndUtf16 -= 1 },
    data => { data.parts[0].textTransform.textChannel = 'effectiveFinal' },
    data => { data.parts[0].nativeTextChannels[data.parts[0].textTransform.textChannel] = 'invented text' },
    data => { data.parts[0].nativeNodeRef.nativeNodeId = data.parts[1].blockId },
    data => { data.parts[0].nativeNodeRef.sourceRevisionSha256 = '0'.repeat(64) },
    data => { data.parts[0].nativeNodeRef.artifactDescriptor.relativePath = 'outside/private.json' },
    data => { data.parts[0].nativeSemanticQuality = 'correct' }
  ]) {
    const data = nativePointer(); mutate(data)
    assert.throws(() => helper.validateCanonicalPointer(data, data), error => error.name === 'CanonicalEvidenceError')
  }
})

test('native whole table requires blank and nested membership without a fake PDF group', () => {
  const helper = api()
  for (const mutate of [
    data => { delete data.tableGroup }, data => { data.tableGroup = null },
    data => { data.tableGroup.physicalPages = [1] }, data => { data.parts.pop() },
    data => { data.tableGroup.nativeParagraphIds.pop() },
    data => { data.tableGroup.nativeTableTopology.pop() },
    data => { data.tableGroup.nativeTableIds = [] },
    data => { data.tableGroup.rootTableIds = ['native-' + '0'.repeat(64)] },
    data => { data.tableGroup.nativeTablesRef.payloadSha256 = 'not-a-sha' }
  ]) {
    const data = nativePointer(); mutate(data)
    assert.throws(() => helper.validateCanonicalPointer(data, data), error => error.name === 'CanonicalEvidenceError')
  }
})

test('native panel shows literal nodes and blank inventory with visual unavailable and zero asset requests', async () => {
  const data = nativePointer(), h = await harness({ data, initialPointer: data })
  try {
    const shown = text(h.root)
    assert.match(shown, /Native DOCX package parts/)
    assert.match(shown, /word\/document.xml/)
    assert.match(shown, /no fixed physical page/)
    assert.match(shown, /effective contract text remains unverified/)
    assert.match(shown, /Complete native table-group observation/)
    assert.equal(all(h.root, node => node.tag === 'article').length, 5)
    assert.equal(all(h.root, node => node.tag === 'img').length, 0)
    assert.equal(all(h.root, node => node.tag === 'p' && text(node).includes('Blank text observation')).length, 3)
    assert.equal(h.requests.length, 1)
    assert.equal(h.created.length, 0)
    assert.equal(h.revoked.length, 0)
    assert.doesNotMatch(shown, /Original PDF physical pages|\bP0\b|undefined/)
  } finally { h.dispose() }
})

test('historical pointer failure retains its identity, localizes its reason and never relinks to new assets', async () => {
  const h = await harness({ respond: async () => new Response(JSON.stringify({ code: 4300, message: 'CANONICAL_SOURCE_STALE', data: { url: 'https://other.invalid/new-image' } }), { status: 409 }) })
  try {
    assert.match(text(h.root), /source or parsed revision changed/)
    assert.equal(h.requests.length, 1); assert.equal(h.created.length, 0)
    assert.deepEqual(h.props.pointer, baseAddress)
    h.props.locale = 'zh-Hant'; await settle()
    assert.match(text(h.root), /來源或解析修訂已變化/)
    h.props.locale = 'zh-Hans'; await settle()
    assert.match(text(h.root), /来源或解析修订已变化/)
    find(h.root, node => node.tag === 'button').props.onClick(); await flush()
    assert.equal(h.requests.length, 2)
    assert.equal(h.requests[0].url, h.requests[1].url)
    assert.ok(h.requests.every(request => request.url.includes('/units/') && request.url.includes(baseAddress.projectionSha256)))
    assert.equal(h.created.length, 0)
  } finally { h.dispose() }
})

test('routing uses only fixed same-origin endpoints, encoded IDs and a valid eligible module', async () => {
  const calls = [], data = pointer()
  data.url = 'https://other-host.invalid/private'; data.backendUrl = 'http://127.0.0.1:8868/assets/secrets'
  const helper = api(async (url, options) => { calls.push({ url, options }); return envelope(data) })
  await helper.fetchCanonicalPointer(baseAddress)
  assert.equal(calls.length, 1)
  assert.equal(calls[0].url, `/api/evidence/project-A/documents/11/canonical/${baseAddress.projectionSha256}/units/table?module=vetting`)
  assert.equal(calls[0].options.method, 'GET'); assert.equal(calls[0].options.redirect, 'error')
  assert.equal(calls[0].options.credentials, 'same-origin'); assert.equal(calls[0].options.cache, 'no-store')
  assert.match(helper.canonicalEvidencePath(baseAddress, 'assets', table.assetRefs[0].assetId), /\/assets\/asset%3A[0-9a-f]+\?module=vetting$/)
  for (const invalid of ['..', '.', '../outside', 'https://host.invalid/', 'id?module=drafting']) {
    assert.throws(() => helper.canonicalEvidencePath(baseAddress, 'units', invalid), rejects('CANONICAL_ROUTE_ID_INVALID'))
  }
  data.eligibleModules = ['advisory']
  assert.throws(() => helper.validateCanonicalPointer(baseAddress, data), rejects('CANONICAL_MODULE_INELIGIBLE'))
})

test('full three-page table uses physical/source order and preserves literal text and unknown values', () => {
  const helper = api(), data = pointer()
  data.parts.reverse(); data.assetRefs.reverse()
  const verified = helper.validateCanonicalPointer(baseAddress, data)
  assert.deepEqual([...helper.orderedEvidenceParts(verified)].map(part => part.physicalPageNo), [3, 4, 5])
  assert.deepEqual([...helper.orderedEvidenceAssets(verified)].map(asset => asset.physicalPages[0]), [3, 4, 5])
  assert.equal(helper.orderedEvidenceAssets(verified)[2].assetId, table.assetRefs[2].assetId, 'Lexically first asset belongs on the last page')
  assert.deepEqual(verified.sourceQuality, table.sourceQuality); assert.deepEqual(verified.unknowns, projection.unknowns)
  assert.equal(verified.parts[0].text, table.parts[2].text)
  assert.equal(helper.evidenceCoverage(verified).status, 'complete')
  data.humanConfirmed = true
  assert.throws(() => helper.validateCanonicalPointer(baseAddress, data), rejects('CANONICAL_DISPLAY_CONTRACT_INVALID'))
})

test('asset mapping cannot relabel source, physical page, block or original-page bbox', () => {
  const helper = api()
  for (const [mutate, reason] of [
    [data => { data.assetRefs[0].sourceSha256 = 'f'.repeat(64) }, 'CANONICAL_ASSET_SOURCE_MISMATCH'],
    [data => { data.assetRefs[0].physicalPages = [4] }, 'CANONICAL_ASSET_BLOCK_MISMATCH'],
    [data => { data.assetRefs[0].sourceRegions[0].bbox = [0.2, 0.1, 0.9, 0.8] }, 'CANONICAL_ASSET_REGION_BBOX_MISMATCH'],
    [data => { data.parts[0].bbox = [0, 0, 2, 1] }, 'CANONICAL_PART_BBOX_INVALID'],
    [data => { data.assetRefs[0].sourceRegions[0].bboxConvention = 'crop_pixels' }, 'CANONICAL_ASSET_REGION_MISMATCH'],
    [data => { data.assetRefs[0].humanConfirmed = true }, 'CANONICAL_ASSET_CONTRACT_INVALID']
  ]) {
    const data = pointer(); mutate(data)
    assert.throws(() => helper.validateCanonicalPointer(baseAddress, data), rejects(reason))
  }
})

test('asset download verifies source/projection headers, ETag, MIME, bytes, magic and SHA', async () => {
  const asset = table.assetRefs[0]
  const valid = await api(async () => assetResponse(asset)).fetchCanonicalAsset(baseAddress, asset)
  assert.equal(valid.browserShaVerification, 'verified'); assert.equal(valid.blob.size, asset.byteCount)
  const bytes = fs.readFileSync(path.join(fixtureRoot, asset.artifactName))
  const changed = Buffer.from(bytes); changed[changed.length - 1] ^= 1
  for (const [options, reason] of [
    [{ headers: { 'X-Source-Sha256': 'f'.repeat(64) } }, 'CANONICAL_ASSET_HEADER_IDENTITY_MISMATCH'],
    [{ headers: { 'X-Projection-Sha256': 'f'.repeat(64) } }, 'CANONICAL_ASSET_HEADER_IDENTITY_MISMATCH'],
    [{ headers: { ETag: `"${'f'.repeat(64)}"` } }, 'CANONICAL_ASSET_HEADER_CONTENT_MISMATCH'],
    [{ headers: { 'Content-Type': 'text/html' } }, 'CANONICAL_ASSET_HEADER_CONTENT_MISMATCH'],
    [{ bytes: bytes.subarray(0, 8) }, 'CANONICAL_ASSET_SIZE_MISMATCH'],
    [{ bytes: Buffer.alloc(bytes.length) }, 'CANONICAL_ASSET_MAGIC_MISMATCH'],
    [{ bytes: changed }, 'CANONICAL_ASSET_BYTES_SHA_MISMATCH']
  ]) {
    await assert.rejects(api(async () => assetResponse(asset, options)).fetchCanonicalAsset(baseAddress, asset), rejects(reason))
  }
})

test('LAN HTTP without browser SubtleCrypto still recomputes SHA and rejects same-length altered bytes', async () => {
  const result = await api(async () => assetResponse(table.assetRefs[0]), { crypto: undefined }).fetchCanonicalAsset(baseAddress, table.assetRefs[0])
  assert.equal(result.browserShaVerification, 'verified'); assert.equal(result.browserShaImplementation, 'portable')
  const changed = Buffer.from(fs.readFileSync(path.join(fixtureRoot, table.assetRefs[0].artifactName)))
  changed[changed.length - 1] ^= 1
  await assert.rejects(api(async () => assetResponse(table.assetRefs[0], { bytes: changed }), { crypto: null }).fetchCanonicalAsset(baseAddress, table.assetRefs[0]), rejects('CANONICAL_ASSET_BYTES_SHA_MISMATCH'))
})

test('real panel requests all assets in source order, escapes HTML, retains unknown and makes no business calls', async () => {
  const data = pointer(); data.assetRefs.reverse(); data.parts.reverse()
  data.parts[0].text += '\n<script>do_not_execute()</script>'
  data.assetRefs[0].url = 'https://other-host.invalid/image.png'
  const h = await harness({ data })
  try {
    const figures = all(h.root, node => node.tag === 'figure')
    assert.deepEqual(figures.map(node => node.props['data-asset-id']), table.assetRefs.map(asset => asset.assetId))
    assert.equal(all(h.root, node => node.tag === 'img').length, 3)
    assert.deepEqual(all(h.root, node => node.tag === 'article').map(node => node.props['data-block-id']), table.parts.map(part => part.blockId))
    assert.match(text(h.root), /AUTHORED-project.pdf/); assert.match(text(h.root), /0.1, 0.1, 0.9, 0.8/)
    assert.match(text(h.root), /Not human-confirmed/); assert.match(text(h.root), /"semanticQuality": "unknown"/)
    assert.match(text(h.root), /<script>do_not_execute\(\)<\/script>/)
    assert.equal(all(h.root, node => node.tag === 'script' || node.props?.innerHTML !== undefined).length, 0)
    assert.equal(h.requests.length, 4); assert.ok(h.requests.every(request => request.options.method === 'GET' && request.url.startsWith('/api/evidence/')))
    assert.equal(h.requests.some(request => /\/ask|\/index|https:/.test(request.url)), false)
    assert.equal(h.created.length, 3)
  } finally { h.dispose() }
  assert.deepEqual(h.revoked.sort(), h.created.map(row => row.url).sort())
})

test('no assets and missing pages remain explicit incomplete states', async () => {
  const empty = pointer(); empty.assetRefs = []
  const h = await harness({ data: empty })
  try {
    assert.match(text(h.root), /No image assets are bound/)
    assert.equal(all(h.root, node => node.tag === 'img').length, 0)
    assert.doesNotMatch(text(h.root), /Complete cross-page table group/)
  } finally { h.dispose() }
  const missing = pointer(); missing.assetRefs.splice(1, 1)
  const incomplete = await harness({ data: missing })
  try {
    assert.match(text(incomplete.root), /Cross-page evidence is incomplete/)
    assert.equal(all(incomplete.root, node => node.tag === 'img').length, 2)
    assert.match(text(incomplete.root), /complete table group\. 4/)
  } finally { incomplete.dispose() }
})

test('one HTTP asset failure preserves every other asset and records incomplete table status', async () => {
  const h = await harness({ respond: (url, options, data) => {
    if (url.includes('/units/')) return envelope(data)
    const asset = data.assetRefs.find(asset => url.includes(encodeURIComponent(asset.assetId)))
    return assetResponse(asset, asset.physicalPages[0] === 4 ? { status: 409 } : {})
  } })
  try {
    assert.equal(h.requests.length, 4); assert.equal(all(h.root, node => node.tag === 'figure').length, 3)
    assert.equal(all(h.root, node => node.tag === 'img').length, 2)
    assert.match(text(h.root), /Image unavailable/); assert.match(text(h.root), /HTTP 409/)
    assert.match(text(h.root), /Cross-page evidence is incomplete/)
  } finally { h.dispose() }
})

test('image decoding failure revokes its object URL and cannot masquerade as completed visual evidence', async () => {
  const h = await harness()
  try {
    const image = find(h.root, node => node.tag === 'img'), url = image.props.src
    image.props.onError(); await flush()
    assert.match(text(h.root), /IMAGE_DECODE_FAILED/); assert.match(text(h.root), /Cross-page evidence is incomplete/)
    assert.equal(all(h.root, node => node.tag === 'img').length, 2)
    assert.equal(h.revoked.filter(item => item === url).length, 1)
  } finally { h.dispose() }
  assert.equal(h.revoked.length, 3)
})

test('stale pointer response is ignored and aborts; unmount drops pending assets before URL creation', async () => {
  const gate = deferred(), first = pointer(), secondAddress = { ...baseAddress, projectId: 'project-B' }, second = pointer(secondAddress)
  const h = await harness({ pendingLoad: true, respond: (url, options) => {
    if (url.includes('/units/')) return url.includes('/project-A/') ? gate.promise : envelope(second)
    return assetResponse(second.assetRefs.find(asset => url.includes(encodeURIComponent(asset.assetId))))
  } })
  const firstSignal = h.requests[0].options.signal
  h.props.pointer = secondAddress; await flush()
  assert.equal(firstSignal.aborted, true)
  gate.resolve(envelope(first)); await flush()
  await waitFor(() => h.created.length === 3)
  assert.equal(h.created.length, 3)
  assert.equal(h.requests.filter(request => request.url.includes('/assets/')).every(request => request.url.includes('/project-B/')), true)
  h.dispose(); assert.equal(h.revoked.length, 3)

  const assetGate = deferred()
  const pending = await harness({ pendingLoad: true, respond: (url, options, data) => url.includes('/units/') ? envelope(data) : assetGate.promise })
  const signals = pending.requests.map(request => request.options.signal)
  pending.dispose(); assetGate.resolve(assetResponse(table.assetRefs[0])); await flush()
  assert.ok(signals.every(signal => signal.aborted)); assert.equal(pending.created.length, 0)
})

test('pointer identity mismatch fails before assets and reload/null transition releases prior evidence', async () => {
  const wrong = pointer(); wrong.projectionSha256 = 'f'.repeat(64)
  const rejected = await harness({ data: wrong })
  try {
    assert.match(text(rejected.root), /CANONICAL_IDENTITY_MISMATCH:projectionSha256/)
    assert.equal(rejected.requests.length, 1); assert.equal(rejected.created.length, 0)
  } finally { rejected.dispose() }
  const h = await harness()
  try {
    find(h.root, node => node.tag === 'button').props.onClick(); await flush()
    await waitFor(() => h.created.length === 6)
    assert.equal(h.created.length, 6); assert.equal(h.revoked.length, 3)
    h.props.pointer = null; await flush()
    assert.match(text(h.root), /Select an explicit canonical source address/)
    assert.equal(all(h.root, node => node.tag === 'img').length, 0); assert.equal(h.revoked.length, 6)
  } finally { h.dispose() }
})

test('LAN panel displays all verified images and localizes status without upgrading source confidence', async () => {
  const h = await harness({ browserCrypto: null })
  try {
    assert.match(text(h.root), /Image byte SHA-256 was verified in the browser/)
    assert.equal(all(h.root, node => node.tag === 'img').length, 3)
    assert.equal(all(h.root, node => node.props?.['data-sha-implementation'] === 'portable').length, 3)
    h.props.locale = 'zh-Hant'; await flush(); assert.match(text(h.root), /未經過人工確認/)
    h.props.locale = 'zh-Hans'; await flush(); assert.match(text(h.root), /未经过人工确认/)
    assert.match(text(h.root), /瀏覽器|浏览器/); assert.match(text(h.root), /"humanConfirmed": false/)
  } finally { h.dispose() }
})

test('portable SHA matches standard vectors, padding boundaries, binary data and multiple chunks', async () => {
  const helper = shaHelper({ crypto: null })
  for (const [input, expected] of [
    ['', 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'],
    ['abc', 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'],
    ['abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq', '248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1']
  ]) {
    const bytes = Uint8Array.from(Buffer.from(input)).buffer
    const result = await helper.sha256Bytes(bytes)
    assert.equal(result.hex, expected); assert.equal(result.implementation, 'portable')
  }
  for (const length of [1, 55, 56, 63, 64, 65, 127, 128, 129, 1024 * 1024 + 73]) {
    const bytes = Uint8Array.from({ length }, (_, index) => (index * 37 + 255) & 255)
    const result = await helper.sha256Bytes(bytes.buffer)
    assert.equal(result.hex, crypto.createHash('sha256').update(bytes).digest('hex'), `binary length ${length}`)
  }
})

test('SubtleCrypto digest failure falls back to actual hashing instead of bypassing validation', async () => {
  const browserCrypto = { subtle: { async digest() { throw new Error('Disabled by browser policy') } } }
  const result = await api(async () => assetResponse(table.assetRefs[0]), { crypto: browserCrypto }).fetchCanonicalAsset(baseAddress, table.assetRefs[0])
  assert.equal(result.browserShaVerification, 'verified'); assert.equal(result.browserShaImplementation, 'portable')
})

test('LAN panel refuses a corrupt asset before creating any Blob URL for it and keeps other pages', async () => {
  const h = await harness({ browserCrypto: null, respond: (url, options, data) => {
    if (url.includes('/units/')) return envelope(data)
    const asset = data.assetRefs.find(asset => url.includes(encodeURIComponent(asset.assetId)))
    const bytes = Buffer.from(fs.readFileSync(path.join(fixtureRoot, asset.artifactName)))
    if (asset.physicalPages[0] === 4) bytes[bytes.length - 1] ^= 1
    return assetResponse(asset, { bytes })
  } })
  try {
    assert.equal(h.requests.length, 4); assert.equal(h.created.length, 2)
    assert.equal(all(h.root, node => node.tag === 'img').length, 2)
    assert.match(text(h.root), /CANONICAL_ASSET_BYTES_SHA_MISMATCH/)
    assert.match(text(h.root), /Cross-page evidence is incomplete/)
  } finally { h.dispose() }
})
