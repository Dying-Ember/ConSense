/* Mounted frontend contract only; no browser, application API, model or Word mutation. */
const assert = require('node:assert/strict'), path = require('node:path')
const { JSDOM } = require('jsdom'), window = new JSDOM('<main id="app"></main>').window
for (const key of ['window', 'document', 'Element', 'HTMLElement', 'SVGElement', 'Node', 'Document', 'ShadowRoot']) globalThis[key] = key === 'window' ? window : window[key]
const vue = require('vue'), { loadVue } = require('./helpers/load-vue.cjs'), { settle } = require('./helpers/render-controls.cjs')
let apiCalls = 0
const rejectApi = () => { apiCalls++; throw Error('Unexpected API') }
globalThis.fetch = window.fetch = rejectApi
const loaded = loadVue(path.join(__dirname, '../src/components/DraftingBodyEditor.vue'), { globals: { fetch: rejectApi } })
const { createBodyDraft, bodyPatch, bodyDirty, setBodyScopeDeletion, canDeleteBodyScope } = loaded.loadLocal(path.join(__dirname, '../src/drafting/body-edit.ts'))
const { draftWord } = loaded.loadLocal(path.join(__dirname, '../src/drafting/words.ts'))
const clone = value => JSON.parse(JSON.stringify(value)), root = 'word/document.xml#/w:document[1]/w:body[1]', p = n => `${root}/w:p[${n}]`
const table = `${root}/w:tbl[3]`, tableParagraph = `${table}/w:tr[1]/w:tc[1]/w:p[1]`, rangeId = `body-range:${'a'.repeat(64)}`
const range = { scopeId: rangeId, kind: 'body_range', operation: 'delete_empty_body_range', nodeSha256: 'a'.repeat(64), memberBindingIds: ['CSrange'], paragraphCount: 3, paragraphIds: [p(2), tableParagraph, p(3)], paragraphOrdinals: [2, 3, 4], nativeTargets: [{ id: p(2), kind: 'p', sha256: 'b'.repeat(64), paragraphIds: [p(2)] }, { id: table, kind: 'tbl', sha256: 'c'.repeat(64), paragraphIds: [tableParagraph] }, { id: p(3), kind: 'p', sha256: 'd'.repeat(64), paragraphIds: [p(3)] }], nativeNumberingCount: 1, enclosingRetainedBookmarks: [{ id: '345', name: '_TocEnclosingRetained' }], retainedBoundaryBookmarks: [{ id: '252', name: '_TocFollowingBoundaryRetained' }], previousText: 'Retained before range.', nextText: 'Retained after range.' }
const number = { scopeId: p(5), kind: 'paragraph_numbering', operation: 'clear_empty_numbering', nodeSha256: 'e'.repeat(64), memberBindingIds: [], paragraphCount: 1, paragraphIds: [p(5)], paragraphOrdinals: [6], nativeNumberingCount: 1, retainedCrossingBookmarks: [{ id: '204', name: '_TocCrossingRetained' }], previousText: 'Retained before numbering.', nextText: 'Retained after numbering.' }
const source = { revisionId: 'SYNTHETIC-new-kinds', docxSha256: 'f'.repeat(64), generated: true, stale: false, blocks: [{ id: p(1), bindingId: 'CSoutside', text: 'Retained before range.', textHash: 'outside', paragraphOrdinal: 1, editable: true }, { id: p(2), bindingId: 'CSrange', text: '', textHash: 'empty', paragraphOrdinal: 2, editable: true }, { id: p(5), text: '', textHash: 'empty', paragraphOrdinal: 6, editable: false }], bodyCleanupScopes: [range, number] }
const draft = vue.reactive(createBodyDraft(source)), locale = vue.ref('en'), readonly = vue.ref(false), disabled = vue.ref(false), events = []
const app = vue.createApp({ render: () => vue.h(loaded.default, { blocks: source.blocks, cleanupScopes: source.bodyCleanupScopes, draft, locale: locale.value, readonly: readonly.value, disabled: disabled.value, onScopeRemove: (id, remove) => { events.push([id, remove]); setBodyScopeDeletion(source, draft, id, remove) } }) })
const card = id => [...document.querySelectorAll('[data-cleanup-scope]')].find(node => node.dataset.cleanupScope === id)
const button = id => card(id)?.querySelector('[data-delete-empty-scope]'), w = key => draftWord(key, locale.value)
const expected = scope => ({ scopeId: scope.scopeId, expectedScopeHash: scope.nodeSha256, operation: scope.operation, memberBindingIds: [...scope.memberBindingIds] })
async function click(node) { assert.ok(node); node.click(); await settle() }

;(async () => {
  assert.equal(canDeleteBodyScope(range), true); assert.equal(canDeleteBodyScope(number), true)
  app.mount('#app'); await settle()
  assert.equal(document.querySelectorAll('[data-cleanup-scope]').length, 2)
  assert.equal(bodyDirty(source, draft), false); assert.equal(events.length, 0)
  for (const lang of ['zh-Hans', 'zh-Hant', 'en']) {
    locale.value = lang; await settle()
    assert.ok(card(rangeId).textContent.includes(w('bodyCleanupRange')))
    assert.ok(card(rangeId).textContent.includes('2–4')); assert.ok(card(rangeId).textContent.includes('_TocEnclosingRetained')); assert.ok(card(rangeId).textContent.includes('_TocFollowingBoundaryRetained'))
    assert.ok(card(number.scopeId).textContent.includes(w('bodyCleanupNumberingScope')))
    assert.ok(card(number.scopeId).textContent.includes('_TocCrossingRetained'))
    assert.equal(button(number.scopeId).textContent.trim(), w('bodyCleanupNumberingMark'))
  }
  console.log('PASS: body range and unregistered numbering paragraph remain visible in three locales with exact ranges and retained bookmark evidence')
  await click(button(rangeId)); await click(button(number.scopeId))
  assert.equal(document.querySelector('[data-cleanup-delete-count]').textContent.trim(), `1 ${w('bodyCleanupDeleteCount')}`)
  assert.equal(document.querySelector('[data-cleanup-numbering-count]').textContent.trim(), `1 ${w('bodyCleanupFormatCount')}`)
  assert.ok(card(rangeId).textContent.includes(w('bodyCleanupPending')))
  assert.ok(card(number.scopeId).textContent.includes(w('bodyCleanupNumberingPending')))
  assert.equal(button(number.scopeId).textContent.trim(), w('bodyCleanupNumberingCancel'))
  assert.deepEqual(clone(bodyPatch(source, draft)), { revisionId: source.revisionId, docxSha256: source.docxSha256, blocks: [], scopeEdits: [expected(range), expected(number)] })
  assert.equal(events.length, 2); assert.equal(apiCalls, 0)
  const cached = clone(draft); assert.deepEqual(clone(bodyPatch(source, cached)), clone(bodyPatch(source, draft)))
  console.log('PASS: selected range and numbering generate exact different operations, disjoint native coverage and an empty local member array without immediate requests')
  await click(button(number.scopeId)); assert.equal(draft.scopeDeletions[rangeId], true); assert.equal(draft.scopeDeletions[number.scopeId], undefined)
  assert.equal(document.querySelector('[data-cleanup-numbering-count]').textContent.trim(), `0 ${w('bodyCleanupFormatCount')}`)
  readonly.value = true; await settle(); assert.equal(document.querySelectorAll('[data-delete-empty-scope]').length, 0)
  readonly.value = false; disabled.value = true; await settle(); const before = clone(draft)
  assert.equal(button(rangeId).disabled, true); await click(button(rangeId)); assert.deepEqual(clone(draft), before)
  console.log('PASS: numbering cancel preserves pending structural range; readonly and busy controls preserve selection')
  for (const invalid of [{ operation: 'delete_empty_table' }, { memberBindingIds: [] }, { scopeId: 'body-range:wrong' }, { nativeTargets: [] }, { nativeTargets: [...range.nativeTargets, range.nativeTargets[0]] }, { nativeTargets: range.nativeTargets.map((target, i) => i ? target : { ...target, sha256: 'old' }) }, { paragraphIds: [p(2), p(3), tableParagraph] }]) assert.equal(canDeleteBodyScope({ ...range, ...invalid }), false, JSON.stringify(invalid))
  for (const invalid of [{ operation: 'delete_paragraph' }, { paragraphIds: [p(4)] }, { scopeId: tableParagraph }, { memberBindingIds: ['CSdup', 'CSdup'] }]) assert.equal(canDeleteBodyScope({ ...number, ...invalid }), false, JSON.stringify(invalid))
  const overlap = clone(source); overlap.bodyCleanupScopes[1] = { ...number, scopeId: p(3), paragraphIds: [p(3)] }; const selected = createBodyDraft(overlap); selected.scopeDeletions = { [rangeId]: true, [p(3)]: true }
  assert.throws(() => bodyPatch(overlap, selected), /BODY_SCOPE_OVERLAP/, 'Shared unbound paragraph is protected despite different scope IDs and empty numbering members')
  const conflicting = createBodyDraft(source); conflicting.scopeDeletions[number.scopeId] = true; conflicting.texts[number.scopeId] = 'Pending unbound edit'; assert.throws(() => bodyPatch(source, conflicting), /BODY_SCOPE_BLOCK_CONFLICT|PROTECTED_BLOCK/)
  const stale = createBodyDraft(source); stale.scopeDeletions[rangeId] = true; assert.throws(() => bodyPatch({ ...source, revisionId: 'changed' }, stale), /REVISION_CONFLICT/)
  assert.equal(apiCalls, 0); app.unmount()
  console.log('PASS: malformed targets, old revisions, overlapping unbound native paragraphs and concurrent text edits fail before a patch can be saved')
})().catch(error => { console.error(error); process.exitCode = 1 })
