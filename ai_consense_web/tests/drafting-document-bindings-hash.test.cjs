const assert = require('node:assert/strict')
const path = require('node:path')
const { createHash } = require('node:crypto')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')

const sha256Bytes = loadTypeScript(path.join(__dirname, '../src/api/sha256Bytes.ts'), {
  globals: { crypto: {}, setTimeout }, dependencies: { '@noble/hashes/sha256': require('@noble/hashes/sha256') }
})
const { pdfSha256, matchesBindingPdf } = loadTypeScript(path.join(__dirname, '../src/drafting/document-bindings.ts'), {
  dependencies: { '@/api/sha256Bytes': sha256Bytes }
})

;(async () => {
  const bytes = Buffer.from('synthetic PDF bytes')
  const hash = await pdfSha256(new Blob([bytes]))
  assert.equal(hash, createHash('sha256').update(bytes).digest('hex'))
  const binding = { fileKey: 'NTT', view: 'source', pdfSha256: hash, renderProfileHash: 'profile' }
  assert.equal(matchesBindingPdf(binding, 'NTT', 'source', hash), true)
  assert.equal(matchesBindingPdf(binding, 'NTT', 'source', '0'.repeat(64)), false)
  console.log('PASS: PDF hash and binding version checks work without SubtleCrypto')
})().catch(error => { console.error(error); process.exitCode = 1 })
