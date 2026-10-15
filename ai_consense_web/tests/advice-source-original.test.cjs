const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const digest = 'a'.repeat(64), parsed = 'b'.repeat(64), projection = 'c'.repeat(64)
const pdf = new TextEncoder().encode('%PDF-1.7\nAUTHORED').buffer
function helper({ fetch, fixed, pointer, actualHash = digest } = {}) {
  return loadTypeScript(path.join(__dirname, '../src/advice/sourceOriginal.ts'), { globals: { fetch, URLSearchParams, TextDecoder, Uint8Array }, dependencies: {
    '@/api/canonicalEvidence': { fetchCanonicalPointer: pointer ?? (async address => ({ ...address, physicalPages: [7], parts: [] })) },
    '@/api/fixedBaselineEvidence': { fetchFixedOriginal: async () => fixed, fetchFixedEvidence: async address => ({ pageNo: address.pageNo, payload: { originalPageNos: [2,3] } }) },
    '@/api/sha256Bytes': { sha256Bytes: async () => ({ hex: actualHash }) }
  } })
}
function citation() {
  return { documentId: '11', sourceHash: parsed, sourceRole: 'tender', fileLabel: 'same-name.pdf', canonicalEvidence: [{ addressKind: 'canonical_adoption_unit', projectId: 'project-a', documentId: '11', module: 'advisory',
    originalSourceSha256: digest, javaParsedSourceHash: parsed, projectionSha256: projection, unitId: 'native-unit', sourceFilename: 'original-SCC.pdf' }] }
}
test('project original binds precise project, module, parsed/raw digests and canonical unit rather than filename', async () => {
  let seen
  const { loadAdviceOriginal } = helper({ fetch: async (url, options) => { seen = { url, options }; return new Response(pdf, { headers: { 'Content-Type': 'application/pdf', 'X-Source-SHA256': digest, 'X-Source-Revision': parsed } }) } })
  const loaded = await loadAdviceOriginal('project-a', citation(), 'contract')
  assert.equal(loaded.type, 'pdf'); assert.equal(loaded.filename, 'original-SCC.pdf'); assert.deepEqual([...loaded.pages], [7])
  const url = new URL(seen.url, 'http://local.test')
  assert.equal(url.pathname, '/api/advice/project-a/sources/11/original')
  assert.equal(url.searchParams.get('scope'), 'contract'); assert.equal(url.searchParams.get('originalSourceSha256'), digest)
  assert.equal(url.searchParams.get('sourceRole'), 'tender')
  assert.equal(url.searchParams.get('projectionSha256'), projection); assert.equal(url.searchParams.get('unitId'), 'native-unit')
  assert.equal(seen.options.redirect, 'error'); assert.equal(seen.options.cache, 'no-store')
})
test('fixed source IDs never collide with project IDs, and retained page claims match the verified fixed member', async () => {
  let calls = 0
  const { loadAdviceOriginal } = helper({ fetch: async () => { calls++; throw new Error('PROJECT_FETCH_FORBIDDEN') }, fixed: new Blob([pdf]) })
  const fixed = { fileLabel: 'same-name.pdf', documentId: '11', fixedBaselineEvidence: { originalSourceSha256: digest, pageNo: 2, originalPageNos: [2,3] } }
  const original = await loadAdviceOriginal('project-a', fixed, 'fullset')
  assert.equal(calls, 0); assert.equal(original.type, 'pdf'); assert.deepEqual([...original.pages], [2,3])
  await assert.rejects(loadAdviceOriginal('project-a', { ...fixed, fixedBaselineEvidence: { ...fixed.fixedBaselineEvidence, originalPageNos: [99] } }, 'fullset'), /FIXED_PAGE_MISMATCH/)
  await assert.rejects(loadAdviceOriginal('project-a', { ...citation(), fixedBaselineEvidence: fixed.fixedBaselineEvidence }, 'fullset'), /AMBIGUOUS_SOURCE/)
})
test('cross project, wrong module, parsed/raw mismatch and ambiguous multi-document addresses stop before fetch', async () => {
  let calls = 0
  const { loadAdviceOriginal } = helper({ fetch: async () => { calls++; throw new Error('FORBIDDEN') } })
  const valid = citation()
  await assert.rejects(loadAdviceOriginal('project-b', valid, 'fullset'), /ADDRESS_MISMATCH/)
  for (const change of [{ module: 'vetting' }, { javaParsedSourceHash: 'd'.repeat(64) }, { documentId: '12' }]) {
    await assert.rejects(loadAdviceOriginal('project-a', { ...valid, canonicalEvidence: [{ ...valid.canonicalEvidence[0], ...change }] }, 'fullset'), /ADDRESS_MISMATCH/)
  }
  await assert.rejects(loadAdviceOriginal('project-a', { ...valid, canonicalEvidence: [...valid.canonicalEvidence, { ...valid.canonicalEvidence[0], originalSourceSha256: 'e'.repeat(64) }] }, 'fullset'), /ADDRESS_MISMATCH/)
  assert.equal(calls, 0)
})
test('server staleness and mismatched raw bytes reject without silently reading newer files', async () => {
  let { loadAdviceOriginal } = helper({ fetch: async () => new Response(JSON.stringify({ message: 'SOURCE_DOCUMENT_SUPERSEDED' }), { status: 409 }) })
  await assert.rejects(loadAdviceOriginal('project-a', citation(), 'fullset'), /SOURCE_DOCUMENT_SUPERSEDED/)
  ;({ loadAdviceOriginal } = helper({ actualHash: 'f'.repeat(64), fetch: async () => new Response(pdf, { headers: { 'Content-Type': 'application/pdf', 'X-Source-SHA256': digest, 'X-Source-Revision': parsed } }) }))
  await assert.rejects(loadAdviceOriginal('project-a', citation(), 'fullset'), /BYTES_MISMATCH/)
})
test('legacy sources require a distinct original digest and never guess it from parsed hash', async () => {
  let seen
  const { loadAdviceOriginal } = helper({ fetch: async url => { seen = url; return new Response(pdf, { headers: { 'Content-Type': 'application/pdf', 'X-Source-SHA256': digest, 'X-Source-Revision': parsed } }) } })
  const legacy = { documentId: '11', sourceHash: parsed, sourceRole: 'tender', pageNo: '17', fileLabel: 'original.pdf' }
  await assert.rejects(loadAdviceOriginal('project-a', legacy, 'fullset'), /IDENTITY_UNAVAILABLE/)
  const original = await loadAdviceOriginal('project-a', { ...legacy, originalSourceSha256: digest }, 'fullset')
  assert.deepEqual([...original.pages], [17]); assert.doesNotMatch(seen, /projectionSha256/)
})

test('ordinary DOCX retains native locations independently of canonical adoption pointers', async () => {
  const positions = [{ kind: 'docx_native', sourceSha256: digest, partName: 'word/document.xml', partSha256: 'd'.repeat(64), nodePath: '/{urn:word}document[1]/{urn:word}body[1]/{urn:word}tr[2]' }]
  let seen, pointerCalls = 0
  const { loadAdviceOriginal } = helper({
    pointer: async () => { pointerCalls++; throw new Error('ORDINARY_SOURCE_HAS_NO_CANONICAL_POINTER') },
    fetch: async url => { seen = url; return new Response(new Uint8Array([80,75,3,4]), { headers: { 'Content-Type': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', 'X-Source-SHA256': digest, 'X-Source-Revision': parsed } }) }
  })
  const loaded = await loadAdviceOriginal('project-a', { documentId: '11', sourceHash: parsed, sourceRole: 'tender', originalSourceSha256: digest, nativePositions: positions, fileLabel: 'original.docx', pageNo: '' }, 'contract')
  assert.equal(loaded.type, 'docx'); assert.equal(pointerCalls, 0)
  assert.deepEqual([...loaded.pointers], []); assert.deepEqual([...loaded.nativePositions], positions); assert.deepEqual([...loaded.pages], [])
  assert.doesNotMatch(seen, /projectionSha256|unitId/)
})

test('Vetting uses its own source endpoint and the selected module must match every canonical address', async () => {
  let seen, calls = 0
  const { loadAdviceOriginal } = helper({ fetch: async (url, options) => { calls++; seen = { url, options }; return new Response(pdf, { headers: { 'Content-Type': 'application/pdf', 'X-Source-SHA256': digest, 'X-Source-Revision': parsed } }) } })
  const value = { ...citation(), canonicalEvidence: citation().canonicalEvidence.map(address => ({ ...address, module: 'vetting' })) }
  await assert.rejects(loadAdviceOriginal('project-a', value, 'fullset'), /ADDRESS_MISMATCH/)
  await assert.rejects(loadAdviceOriginal('project-a', citation(), 'fullset', undefined, 'vetting'), /ADDRESS_MISMATCH/)
  const loaded = await loadAdviceOriginal('project-a', value, 'fullset', undefined, 'vetting')
  const url = new URL(seen.url, 'http://local.test')
  assert.equal(url.pathname, '/api/vetting/project-a/sources/11/original'); assert.equal(url.searchParams.has('scope'), false)
  assert.equal(url.searchParams.get('sourceHash'), parsed); assert.equal(url.searchParams.get('originalSourceSha256'), digest); assert.equal(url.searchParams.get('sourceRole'), 'tender')
  assert.equal(seen.options.redirect, 'error'); assert.equal(loaded.type, 'pdf'); assert.equal(calls, 1)
})

test('native source identity, declared raw SHA, role and response headers cannot be bypassed', async () => {
  let calls = 0
  const { loadAdviceOriginal } = helper({ fetch: async () => { calls++; return new Response(pdf, { headers: { 'Content-Type': 'application/pdf', 'X-Source-SHA256': digest, 'X-Source-Revision': 'f'.repeat(64) } }) } })
  const ordinary = { documentId: '11', sourceHash: parsed, sourceRole: 'tender', originalSourceSha256: digest, fileLabel: 'original.docx' }
  await assert.rejects(loadAdviceOriginal('project-a', { ...ordinary, nativePositions: [{ kind: 'docx_native', sourceSha256: 'e'.repeat(64) }] }, 'fullset', undefined, 'vetting'), /NATIVE_POSITION_MISMATCH/)
  await assert.rejects(loadAdviceOriginal('project-a', { ...ordinary, sourceRole: 'unknown' }, 'fullset', undefined, 'vetting'), /IDENTITY_UNAVAILABLE/)
  await assert.rejects(loadAdviceOriginal('project-a', { ...citation(), originalSourceSha256: 'e'.repeat(64) }, 'fullset'), /ADDRESS_MISMATCH/)
  assert.equal(calls, 0, 'Invalid identity rejects before either module can fetch an original')
  await assert.rejects(loadAdviceOriginal('project-a', ordinary, 'fullset', undefined, 'vetting'), /HEADER_MISMATCH/)
  assert.equal(calls, 1)
})
