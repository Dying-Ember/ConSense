const assert = require('node:assert/strict')
const path = require('node:path')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const { createBodyDraft, bodyPatch, bodyDirty } = loadTypeScript(path.join(__dirname, '../src/drafting/body-edit.ts'), {})
const document = { revisionId: 'revision-1', docxSha256: 'frozen-hash', generated: true, stale: false, blocks: [
  { id: 'original-cell/p[4]', text: 'Exact\tformal wording', textHash: 'expected-1', paragraphOrdinal: 4, editable: true },
  { id: 'toc/p[5]', text: 'Field result', textHash: 'expected-2', paragraphOrdinal: 5, editable: false }
] }
const draft = createBodyDraft(document)
assert.equal(bodyDirty(document, draft), false)
draft.texts[document.blocks[0].id] = 'Exact\tadopted wording'
draft.insertions[document.blocks[0].id] = ['Additional adopted paragraph.']
const patch = JSON.parse(JSON.stringify(bodyPatch(document, draft)))
assert.deepEqual(patch, { revisionId: 'revision-1', docxSha256: 'frozen-hash', blocks: [{ id: 'original-cell/p[4]', expectedTextHash: 'expected-1', text: 'Exact\tadopted wording', insertAfter: ['Additional adopted paragraph.'] }] })
assert.equal(bodyDirty(document, draft), true)
assert.equal(document.blocks[0].text, 'Exact\tformal wording')
assert.throws(() => bodyPatch({ ...document, revisionId: 'revision-2' }, draft), /REVISION_CONFLICT/)
assert.throws(() => bodyPatch({ ...document, stale: true }, draft), /STALE/)
draft.texts['toc/p[5]'] = 'Unsafe cached field'
assert.throws(() => bodyPatch(document, draft), /PROTECTED_BLOCK/)
console.log('PASS: exact revision/hash/block expectations, changed paragraphs only, native controls, insertion, immutable source, stale/replayed/protected rejection')
