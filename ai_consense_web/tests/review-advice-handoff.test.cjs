const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const sha = loadTypeScript(path.join(__dirname, '../src/api/sha256Bytes.ts'), { dependencies: { '@noble/hashes/sha256': require('@noble/hashes/sha256') } })
const fixed = loadTypeScript(path.join(__dirname, '../src/api/fixedBaselineEvidence.ts'), { dependencies: { './sha256Bytes': sha } })
const helper = loadTypeScript(path.join(__dirname, '../src/advice/reviewHandoff.ts'), { globals: { crypto: require('node:crypto').webcrypto, Uint8Array }, dependencies: { '@/api/fixedBaselineEvidence': fixed } })

const token = '0123456789abcdef0123456789abcdef'
const label = { zhHans: '核对条件', zhHant: '核對條件', en: 'Check conditions' }
const quote = '  A1: “shall NOT”\n\tHK$M 1.20; COMPLTION 😀\n'
function input() {
  return { projectId: 'project-A', findingCode: 'F001', title: label, description: label, sourceSetStale: false,
    evidence: [{ side: 'source', documentId: 12, fileKey: 'SCC', fileName: 'actual SCC.pdf', pageNo: 'P4', anchor: 'A1', sourceHash: 'old-source-hash', located: true, quote }],
    reviewRemarks: 'PRIVATE TEAM EDIT', suggestion: 'SUGGESTED ANSWER', report: 'ENTIRE REPORT' }
}
function storage() {
  const data = new Map()
  return { data, getItem: key => data.get(key) ?? null, setItem: (key, value) => data.set(key, value), removeItem: key => data.delete(key) }
}
function saved(now = 1000) {
  const port = storage()
  const handoff = helper.createReviewHandoff(input(), token, now)
  helper.storeReviewHandoff(port, handoff)
  return { port, handoff }
}

test('claim applies once with exact literal quotations and only the authorized handoff fields', () => {
  const { port } = saved()
  const result = helper.claimReviewHandoff(port, token, 'project-A', 2000)
  assert.equal(result.status, 'claimed')
  assert.equal(result.handoff.evidence[0].quote, quote)
  assert.equal(result.handoff.evidence[0].sourceHash, 'old-source-hash')
  assert.equal(result.handoff.reviewRemarks, undefined)
  assert.equal(result.handoff.suggestion, undefined)
  assert.equal(result.handoff.report, undefined)
  assert.equal(port.data.size, 0)
  assert.equal(helper.claimReviewHandoff(port, token, 'project-A', 2000).status, 'missing')
})

test('fixed standard handoff retains baseline/source identity without matching its ID to project files', () => {
  const value = input(), pointer = { evidenceBackend: 'fixed_development_baseline', parentId: '12', manifestSha256: 'a'.repeat(64), baselineVersion: '05', sourceFamily: 'GCC', sourceRole: 'standard', originalSourceSha256: 'b'.repeat(64), pageNo: 4, projectApplicability: 'unconfirmed', qualifiersComplete: 'unknown', sourceQualityAccepted: false }
  value.evidence[0].fixedBaselineEvidence = pointer
  const handoff = helper.createReviewHandoff(value, token, 1000)
  assert.equal(handoff.evidence[0].fixedBaselineEvidence.manifestSha256, pointer.manifestSha256)
  assert.match(helper.reviewQuestion(handoff, value => value.en), /fixed_development_baseline/)
  assert.equal(helper.handoffSourceWarnings(handoff, [{ documentId: '12', included: true, sourceHash: 'new-project-hash' }], true).stale, false)
  assert.equal(helper.handoffSourceWarnings(handoff, [], true).unverified, true)
  pointer.manifestSha256 = 'invalid'
  assert.throws(() => helper.createReviewHandoff(value, token, 1000), /invalid_review_handoff/)
})

test('wrong project rejects and consumes the draft instead of leaking it on a later project switch', () => {
  const { port } = saved()
  assert.equal(helper.claimReviewHandoff(port, token, 'project-B', 2000).status, 'project_mismatch')
  assert.equal(helper.claimReviewHandoff(port, token, 'project-A', 2000).status, 'missing')
})

test('expired and future timestamp drafts cannot be applied', () => {
  for (const [createdAt, now] of [[1000, 1001 + helper.HANDOFF_TTL_MS], [100_000, 1000]]) {
    const { port } = saved(createdAt)
    assert.equal(helper.claimReviewHandoff(port, token, 'project-A', now).status, 'expired')
    assert.equal(port.data.size, 0)
  }
})

test('malformed storage and evidence reject; an unrelated token does not consume the valid draft', () => {
  const { port } = saved()
  const key = [...port.data.keys()][0]
  const original = port.getItem(key)
  assert.equal(helper.claimReviewHandoff(port, 'abcdef0123456789abcdef0123456789', 'project-A', 2000).status, 'missing')
  assert.equal(port.getItem(key), original)
  for (const value of ['{', '{}', JSON.stringify({ ...JSON.parse(original), evidence: [{ quote: 4 }] })]) {
    port.setItem(key, value)
    assert.equal(helper.claimReviewHandoff(port, token, 'project-A', 2000).status, 'invalid')
    assert.equal(port.data.size, 0)
  }
  assert.equal(helper.claimReviewHandoff(port, [token], 'project-A', 2000).status, 'invalid')
})

test('storage failure is an explicit failure and oversized quotations are never silently truncated', () => {
  const broken = { getItem() { throw new Error('denied') }, setItem() { throw new Error('denied') }, removeItem() {} }
  const { handoff } = saved()
  assert.equal(helper.claimReviewHandoff(broken, token, 'project-A', 2000).status, 'storage_unavailable')
  assert.throws(() => helper.storeReviewHandoff(broken, handoff), /denied/)
  handoff.evidence[0].quote = 'x'.repeat(250_001)
  assert.throws(() => helper.storeReviewHandoff(storage(), handoff), /invalid_review_handoff/)
  assert.equal(handoff.evidence[0].quote.length, 250_001)
})

test('locale-specific editable questions retain exact quotes and identity while treating the finding as a lead', () => {
  const { handoff } = saved()
  for (const locale of ['zhHans', 'zhHant', 'en']) {
    const question = helper.reviewQuestion(handoff, value => value[locale])
    assert.ok(question.includes(quote))
    assert.ok(question.includes('"documentId":12'))
    assert.ok(question.includes('"sourceHash":"old-source-hash"'))
    assert.ok(question.includes(helper.reviewHandoffCopy.prompt[locale]))
    assert.ok(question.includes(helper.reviewHandoffCopy.unverified[locale]))
    assert.doesNotMatch(question, /PRIVATE TEAM EDIT|SUGGESTED ANSWER|ENTIRE REPORT/)
  }
  handoff.sourceSetStale = true
  assert.ok(helper.reviewQuestion(handoff, value => value.en).includes(helper.reviewHandoffCopy.stale.en))
})

test('current sources require document identity plus exact hash; unknown and scope exclusions remain explicit', () => {
  const { handoff } = saved()
  const warnings = (documents, loaded = true) => JSON.parse(JSON.stringify(helper.handoffSourceWarnings(handoff, documents, loaded)))
  assert.deepEqual(warnings([{ documentId: '12', included: true, sourceHash: 'old-source-hash' }]), { stale: false, unverified: false, unavailable: false })
  assert.deepEqual(warnings([{ documentId: '12', included: true, sourceHash: 'new-source-hash' }]), { stale: true, unverified: false, unavailable: false })
  assert.deepEqual(warnings([{ documentId: '12', included: true, sourceHash: null }]), { stale: false, unverified: true, unavailable: false })
  assert.deepEqual(warnings([{ documentId: '12', included: false, sourceHash: 'old-source-hash' }]), { stale: false, unverified: false, unavailable: true })
  assert.deepEqual(warnings([{ documentId: 'other', included: true, sourceHash: 'old-source-hash' }]), { stale: false, unverified: true, unavailable: true })
  assert.deepEqual(warnings([], false), { stale: false, unverified: true, unavailable: false })
  handoff.sourceSetStale = true
  assert.equal(warnings([{ documentId: '12', included: true, sourceHash: 'old-source-hash' }]).stale, true)
})

test('HTTP LAN token generation works without crypto.randomUUID and failed navigation removal is token-bound', () => {
  const generated = helper.newReviewHandoffToken()
  assert.match(generated, /^[0-9a-f]{32}$/)
  const { port } = saved()
  helper.removeReviewHandoff(port, generated)
  assert.equal(port.data.size, 1)
  helper.removeReviewHandoff(port, token)
  assert.equal(port.data.size, 0)
})
