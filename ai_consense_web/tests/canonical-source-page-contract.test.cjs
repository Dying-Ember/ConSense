const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { test } = require('node:test')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')

// Existing public authored bytes exercise a typed protocol; they are not OCR accuracy gold.
const fixtureRoot = path.resolve(__dirname, '../../ai_consense_service/src/test/resources/oer/canonical')
const projection = JSON.parse(fs.readFileSync(path.join(fixtureRoot, 'authored-three-page-projection.json'), 'utf8'))
const nativeProjection = JSON.parse(fs.readFileSync(path.join(fixtureRoot, 'authored-native-public-v2.json'), 'utf8'))
const table = projection.chunks.find(chunk => chunk.unitId === 'table')
const identity = 'e'.repeat(64)
const clone = value => JSON.parse(JSON.stringify(value))
const address = {
  addressKind: 'canonical_adoption_unit', projectId: 'authored-project', documentId: 11, module: 'advisory',
  unitId: table.unitId, canonicalChunkId: table.id, originalSourceSha256: projection.source.sourceSha256,
  javaParsedSourceHash: '1'.repeat(64), sourceRevisionSha256: projection.source.sourceRevisionSha256,
  canonicalSourceHash: projection.sourceHash, projectionSha256: projection.projectionSha256,
  projectionRevisionSha256: projection.projectionRevisionSha256,
  sourceFilename: projection.source.sourceFilename, sourceOrder: table.sourceOrder,
  sourceRenderEvidenceIdentitySha256: identity
}
function pointer() {
  return clone({ ...address, bboxConvention: 'normalized_xyxy_original_page', humanConfirmed: false,
    physicalPages: table.physicalPages, eligibleModules: table.eligibleModules, parts: table.parts,
    sourceSpans: table.sourceSpans, tableGroup: table.tableGroup, assetRefs: table.assetRefs,
    relations: table.relations, adoption: table.adoption, sourceQuality: table.sourceQuality, unknowns: projection.unknowns,
    sourceRenderCaptureCompleteness: { status: 'complete', eligibleForCanonical: true },
    sourceRenderLimitations: ['authored protocol fixture; no semantic quality claim'],
    sourcePageRenderRefs: table.assetRefs.map(asset => {
      const bytes = fs.readFileSync(path.join(fixtureRoot, asset.artifactName))
      return { ...asset, kind: 'source_page_render', assetId: `source-render:${asset.sha256}`,
        physicalPageNo: asset.physicalPages[0], widthPixels: bytes.readUInt32BE(16), heightPixels: bytes.readUInt32BE(20),
        sourceRevisionSha256: address.sourceRevisionSha256, projectionSha256: address.projectionSha256,
        combinedEvidenceIdentitySha256: identity, humanConfirmed: false }
    }) })
}
function nativePointer() {
  const chunk = nativeProjection.chunks[1]
  const base = { ...address, documentId: 42, unitId: chunk.unitId, canonicalChunkId: chunk.id,
    originalSourceSha256: nativeProjection.source.sourceSha256, sourceRevisionSha256: nativeProjection.source.sourceRevisionSha256,
    canonicalSourceHash: nativeProjection.sourceHash, projectionSha256: nativeProjection.projectionSha256,
    projectionRevisionSha256: nativeProjection.projectionRevisionSha256, sourceFilename: nativeProjection.source.sourceFilename,
    sourceOrder: chunk.sourceOrder }
  delete base.sourceRenderEvidenceIdentitySha256
  return clone({ ...base, projectionSchema: nativeProjection.schema, producerKind: nativeProjection.producerKind,
    storageShape: nativeProjection.storageShape, originalGeometryStatus: chunk.originalGeometryStatus,
    visualReturnStatus: nativeProjection.visualReturnStatus, humanConfirmed: false,
    physicalPages: chunk.physicalPages, eligibleModules: chunk.eligibleModules, parts: chunk.parts,
    sourceSpans: chunk.sourceSpans, tableGroup: chunk.tableGroup, assetRefs: chunk.assetRefs,
    relations: chunk.relations, adoption: chunk.adoption, sourceQuality: chunk.sourceQuality, unknowns: nativeProjection.unknowns })
}
const runtime = { Blob, AbortController, Uint8Array, ArrayBuffer, crypto: crypto.webcrypto }
const portableSha = require('@noble/hashes/sha256')
function api(fetch) {
  const helper = loadTypeScript(path.resolve(__dirname, '../src/api/sha256Bytes.ts'), {
    globals: { ...runtime, setTimeout }, dependencies: { '@noble/hashes/sha256': portableSha }
  })
  return loadTypeScript(path.resolve(__dirname, '../src/api/canonicalEvidence.ts'), {
    globals: { ...runtime, fetch }, dependencies: { './sha256Bytes': helper }
  })
}
function rejects(reason) { return error => error.reason === reason }
function image(asset, changes = {}) {
  return new Response(fs.readFileSync(path.join(fixtureRoot, asset.artifactName)), { headers: {
    'Content-Type': 'image/png; charset=UTF-8', 'X-Source-Sha256': address.originalSourceSha256,
    'X-Projection-Sha256': address.projectionSha256, 'X-Source-Revision-Sha256': address.sourceRevisionSha256,
    'X-Evidence-Identity-Sha256': identity, ETag: `"${asset.sha256}"`, ...changes
  } })
}

test('three typed source pages preserve provider assets, explicit relations and unknowns', () => {
  const data = pointer(), helper = api()
  const verified = helper.validateCanonicalPointer(address, data)
  assert.equal(verified.sourcePageRenderRefs.length, 3)
  assert.deepEqual(verified.assetRefs, table.assetRefs)
  assert.deepEqual(verified.relations, table.relations)
  assert.deepEqual(verified.unknowns, projection.unknowns)
  assert.equal(verified.humanConfirmed, false)
})

test('a pin or any source-render declaration cannot downgrade to missing references', () => {
  const helper = api()
  for (const declaration of ['addressPin', 'sourceRenderEvidenceIdentitySha256', 'sourceRenderCaptureCompleteness', 'sourceRenderLimitations']) {
    const data = pointer(), request = { ...address }
    delete data.sourcePageRenderRefs
    delete data.sourceRenderEvidenceIdentitySha256
    delete data.sourceRenderCaptureCompleteness
    delete data.sourceRenderLimitations
    if (declaration !== 'addressPin') {
      delete request.sourceRenderEvidenceIdentitySha256
      data[declaration] = declaration === 'sourceRenderEvidenceIdentitySha256' ? identity : declaration === 'sourceRenderLimitations' ? [] : {}
    }
    assert.throws(() => helper.validateCanonicalPointer(request, data), rejects('CANONICAL_SOURCE_RENDER_IDENTITY_OR_COMPLETENESS_INVALID'))
  }
})

test('complete page and adopted block membership reject partial or readdressed sidecars', () => {
  const helper = api(), partial = pointer()
  partial.sourcePageRenderRefs.splice(1, 1)
  assert.throws(() => helper.validateCanonicalPointer(address, partial), rejects('CANONICAL_SOURCE_RENDER_IDENTITY_OR_COMPLETENESS_INVALID'))
  for (const field of ['sourceRevisionSha256', 'projectionSha256', 'combinedEvidenceIdentitySha256']) {
    const data = pointer(); data.sourcePageRenderRefs[0][field] = 'a'.repeat(64)
    assert.throws(() => helper.validateCanonicalPointer(address, data), rejects('CANONICAL_SOURCE_PAGE_IDENTITY_INVALID'))
  }
  const wrong = pointer(); wrong.sourcePageRenderRefs[0].blockIds = ['unadopted']
  assert.throws(() => helper.validateCanonicalPointer(address, wrong), rejects('CANONICAL_SOURCE_PAGE_MEMBERSHIP_INVALID'))
})

test('typed source PNG uses only the fixed source-pages route with unit and composite', async () => {
  const asset = pointer().sourcePageRenderRefs[0], calls = []
  const helper = api(async url => { calls.push(url); return image(asset) })
  const received = await helper.fetchCanonicalAsset(address, asset)
  assert.equal(received.blob.size, asset.byteCount)
  assert.equal(calls.length, 1)
  assert.ok(calls[0].includes(`/source-pages/${encodeURIComponent(asset.assetId)}?`))
  assert.ok(calls[0].includes(`&unitId=${address.unitId}&evidence=${identity}`))
  assert.equal(calls[0].includes('/assets/'), false)
})

test('wrong source revision and composite headers are rejected before a source-page Blob', async () => {
  const asset = pointer().sourcePageRenderRefs[0]
  for (const header of ['X-Source-Revision-Sha256', 'X-Evidence-Identity-Sha256']) {
    await assert.rejects(api(async () => image(asset, { [header]: 'a'.repeat(64) })).fetchCanonicalAsset(address, asset),
      rejects('CANONICAL_SOURCE_RENDER_HEADER_IDENTITY_MISMATCH'))
  }
})

test('native DOCX rejects source-render pins even when the response has no image references', () => {
  const data = nativePointer(), request = { ...data, sourceRenderEvidenceIdentitySha256: identity }
  assert.throws(() => api().validateCanonicalPointer(request, data), rejects('CANONICAL_NATIVE_SOURCE_PAGE_UNAVAILABLE'))
})
