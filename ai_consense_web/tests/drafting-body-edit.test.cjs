const assert = require('node:assert/strict')
const path = require('node:path')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const { createBodyDraft, bodyPatch, bodyDirty, canDeleteBodyBlock, setBodyDeletion } = loadTypeScript(path.join(__dirname, '../src/drafting/body-edit.ts'), {})
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
const native = { id: 'word/document.xml#/w:document[1]/w:body[1]/w:p[1613]', bindingId: 'CSnative-math', text: '(i.e. \n', textHash: 'native-text-hash', paragraphOrdinal: 1655, editable: true }
const saved = { ...document, blocks: [native] }, removal = createBodyDraft(saved)
assert.equal(canDeleteBodyBlock(native), true)
removal.texts[native.id] = 'Superseded pending text'
removal.insertions[native.id] = ['Superseded insertion']
setBodyDeletion(native, removal, true)
assert.equal(bodyDirty(saved, removal), true)
assert.deepEqual(JSON.parse(JSON.stringify(bodyPatch(saved, removal))), { revisionId: saved.revisionId, docxSha256: saved.docxSha256, blocks: [{ id: native.id, bindingId: native.bindingId, expectedTextHash: native.textHash, operation: 'delete_paragraph' }] })
const restoredCache = JSON.parse(JSON.stringify(removal))
assert.equal(bodyDirty(saved, restoredCache), true)
assert.equal(bodyPatch(saved, restoredCache).blocks[0].operation, 'delete_paragraph')
setBodyDeletion(native, removal, false)
assert.equal(bodyDirty(saved, removal), false)
delete removal.deletions
assert.equal(bodyPatch(saved, removal).blocks.length, 0, 'Old cached body drafts remain compatible')
removal.texts[native.id] = ''
assert.equal(bodyPatch(saved, removal).blocks[0].text, '', 'Empty text retains ordinary clearing semantics')
assert.equal(bodyPatch(saved, removal).blocks[0].operation, undefined)
setBodyDeletion(native, removal, true)
removal.insertions[native.id] = ['Invalid mixed operation']
assert.throws(() => bodyPatch(saved, removal), /DELETE_CONFLICT/)
delete removal.insertions[native.id]
removal.texts[native.id] = 'Invalid mixed text'
assert.throws(() => bodyPatch(saved, removal), /DELETE_CONFLICT/)
removal.texts[native.id] = native.text
assert.throws(() => bodyPatch({ ...saved, docxSha256: 'new-package' }, removal), /REVISION_CONFLICT/)
for (const blocked of [{ ...native, cellId: 'cell' }, { ...native, editable: false }, { ...native, bindingId: undefined }, { ...native, id: 'word/header1.xml#/w:hdr[1]/w:p[1]' }]) {
  assert.equal(canDeleteBodyBlock(blocked), false)
  assert.throws(() => setBodyDeletion(blocked, removal, true), /PROTECTED_PARAGRAPH_DELETE/)
}
removal.deletions.unknown = true
assert.throws(() => bodyPatch(saved, removal), /SOURCE_BLOCK_INVALID/)
console.log('PASS: explicit bound native deletion, cancel/dirty/cache preservation, ordinary clear compatibility and malformed/protected/stale rejection')
