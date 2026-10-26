/* Mounted real cleanup controls and patch helper; save is a local receipt, with no application API or Word writes. */
const assert = require('node:assert/strict')
const path = require('node:path')
const { JSDOM } = require('jsdom')
const window = new JSDOM('<main id="app"></main>').window
for (const key of ['window', 'document', 'Element', 'HTMLElement', 'SVGElement', 'Node', 'Document', 'ShadowRoot']) globalThis[key] = key === 'window' ? window : window[key]
const apiCalls = []
const rejectApi = (...args) => { apiCalls.push(args); throw new Error('Unexpected application API call') }
globalThis.fetch = window.fetch = rejectApi
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { settle } = require('./helpers/render-controls.cjs')
const loaded = loadVue(path.join(__dirname, '../src/components/DraftingBodyEditor.vue'), { globals: { fetch: rejectApi } })
const { createBodyDraft, bodyPatch, bodyDirty, setBodyScopeDeletion } = loaded.loadLocal(path.join(__dirname, '../src/drafting/body-edit.ts'))
const { draftWord } = loaded.loadLocal(path.join(__dirname, '../src/drafting/words.ts'))
const clone = value => JSON.parse(JSON.stringify(value))
const root = 'word/document.xml#/w:document[1]/w:body[1]'
const tableId = `${root}/w:tbl[2]`, rowId = `${root}/w:tbl[3]/w:tr[2]`, ordinaryId = `${root}/w:p[9]`
const tableParagraphs = [1, 2, 3].map(n => `${tableId}/w:tr[1]/w:tc[${n}]/w:p[1]`)
const rowParagraph = `${rowId}/w:tc[1]/w:p[1]`
const source = {
  revisionId: 'SYNTHETIC-cleanup-revision', docxSha256: 'd'.repeat(64), generated: true, stale: false,
  blocks: [
    { id: tableParagraphs[0], bindingId: 'CStableA', text: '', textHash: 'empty-a', paragraphOrdinal: 210, cellId: 'table-cell-a', editable: true },
    { id: tableParagraphs[1], bindingId: 'CStableB', text: '\t\n', textHash: 'empty-b', paragraphOrdinal: 211, cellId: 'table-cell-b', editable: true },
    { id: tableParagraphs[2], text: '', textHash: 'unbound-empty', paragraphOrdinal: 212, cellId: 'table-cell-c', editable: true },
    { id: rowParagraph, bindingId: 'CSrowA', text: '', textHash: 'empty-row', paragraphOrdinal: 310, cellId: 'row-cell-a', editable: true },
    { id: ordinaryId, bindingId: 'CSoutside', text: 'Independent retained paragraph.', textHash: 'outside-text', paragraphOrdinal: 900, editable: true }
  ],
  bodyCleanupScopes: [
    { scopeId: tableId, kind: 'table', operation: 'delete_empty_table', nodeSha256: 'a'.repeat(64), memberBindingIds: ['CStableA', 'CStableB'], paragraphCount: 3, paragraphIds: tableParagraphs, paragraphOrdinals: [210, 211, 212], clearProofBindings: ['CStableA'], nativeNumberingCount: 2, nativeTabCount: 1, nativeRowHeightCount: 1, previousText: 'Retained text before the empty table.', nextText: 'Retained text after the empty table.' },
    { scopeId: rowId, kind: 'row', operation: 'delete_empty_row', nodeSha256: 'b'.repeat(64), memberBindingIds: ['CSrowA'], paragraphCount: 1, paragraphIds: [rowParagraph], paragraphOrdinals: [310], clearProofBindings: ['CSrowA'], nativeNumberingCount: 0, nativeTabCount: 0, nativeRowHeightCount: 1, previousText: 'Retained row before.', nextText: 'Retained row after.' }
  ]
}
const savedSource = clone(source)
const expectedScope = candidate => ({ scopeId: candidate.scopeId, expectedScopeHash: candidate.nodeSha256, operation: candidate.operation, memberBindingIds: [...candidate.memberBindingIds] })
const emptyPatch = () => ({ revisionId: source.revisionId, docxSha256: source.docxSha256, blocks: [] })

function mount(cached) {
  const draft = vue.reactive(cached ?? createBodyDraft(source)), readonly = vue.ref(false), disabled = vue.ref(false), locale = vue.ref('en'), saves = [], events = []
  const app = vue.createApp({ render: () => vue.h('div', [
    vue.h(loaded.default, { blocks: source.blocks, cleanupScopes: source.bodyCleanupScopes, draft, readonly: readonly.value, disabled: disabled.value, locale: locale.value,
      onText: (id, text) => { draft.texts[id] = text }, onInsert: (id, lines) => { draft.insertions[id] = lines },
      onScopeRemove: (id, remove) => { events.push([id, remove]); setBodyScopeDeletion(source, draft, id, remove) } }),
    vue.h('button', { 'data-test-save': '', onClick: () => saves.push(clone(bodyPatch(source, draft))) }, 'Save fixture')
  ]) })
  app.mount(document.getElementById('app'))
  return { draft, readonly, disabled, locale, saves, events, dispose: () => app.unmount() }
}
const scopeCard = id => [...document.querySelectorAll('[data-cleanup-scope]')].find(node => node.dataset.cleanupScope === id)
const scopeButton = id => [...document.querySelectorAll('[data-delete-empty-scope]')].find(node => node.dataset.deleteEmptyScope === id)
const ordinaryArticle = () => [...document.querySelectorAll('[data-block-id]')].find(node => node.dataset.blockId === ordinaryId)
const word = (key, locale = 'en') => draftWord(key, locale)
async function click(button) { assert.ok(button, 'The requested control is rendered'); button.click(); await settle() }
async function input(node, value) { assert.ok(node, 'The requested editor is rendered'); node.value = value; node.dispatchEvent(new window.Event('input', { bubbles: true })); await settle() }
function selected(document = source, scopeId = tableId) { const draft = createBodyDraft(document); draft.scopeDeletions = { [scopeId]: true }; return draft }
function rejects(document, draft, label, pattern = /SCOPE|CONFLICT|INVALID/) { assert.throws(() => bodyPatch(document, draft), error => pattern.test(error.message), label) }
async function check(label, run) { await run(); console.log(`PASS: ${label}`) }

;(async () => {
  assert.equal(typeof setBodyScopeDeletion, 'function', 'Cleanup selection uses the real exported helper')
  let view = mount(); await settle()
  try {
    await check('server candidates remain reviewable when their empty Word paragraphs are hidden; default selection makes no save or API call', async () => {
      assert.deepEqual(clone(view.draft.scopeDeletions), {})
      assert.equal(bodyDirty(source, view.draft), false)
      assert.deepEqual(clone(bodyPatch(source, view.draft)), emptyPatch())
      assert.equal(document.querySelectorAll('[data-cleanup-scope]').length, 2)
      assert.equal(document.querySelectorAll('[data-delete-empty-scope]').length, 2)
      assert.equal(document.querySelector('[data-cleanup-pending-count]').textContent.trim(), `0 ${word('bodyCleanupPendingCount')}`)
      assert.equal(document.querySelectorAll('[data-block-id]').length, 1, 'Empty/control-only source paragraphs stay out of the body reader')
      for (const candidate of source.bodyCleanupScopes) {
        const card = scopeCard(candidate.scopeId)
        assert.ok(card.textContent.includes(word(candidate.kind === 'table' ? 'bodyCleanupTable' : 'bodyCleanupRow')))
        assert.equal(scopeButton(candidate.scopeId).textContent.trim(), word('bodyCleanupMark'))
        assert.ok(!card.textContent.includes(word('bodyCleanupPending')))
        const details = card.querySelector('details')
        if (details) { await click(details.querySelector('summary')); assert.equal(details.open, true) }
        for (const value of [candidate.previousText, candidate.nextText, ...candidate.memberBindingIds, ...candidate.paragraphOrdinals.map(String)]) assert.ok(card.textContent.includes(value), `Review includes exact source detail: ${value}`)
        for (const key of ['bodyCleanupMembers', 'bodyCleanupParagraphs', 'bodyCleanupDetails', 'bodyCleanupPrevious', 'bodyCleanupNext']) assert.ok(card.textContent.includes(word(key)), `Review label: ${key}`)
        const escaped = key => word(key).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
        const countPattern = (key, count) => new RegExp(`(?:${count}\\s*${escaped(key)}|${escaped(key)}\\s*[:：·]?\\s*${count}(?!\\d))`)
        assert.match(card.textContent, countPattern('bodyCleanupMembers', candidate.memberBindingIds.length), 'The bound-member count is exact')
        assert.match(card.textContent, countPattern('bodyCleanupParagraphs', candidate.paragraphCount), 'All native paragraphs are counted, including the unbound empty paragraph')
        assert.match(card.textContent, countPattern('bodyCleanupNumbering', candidate.nativeNumberingCount), 'The native numbering count is exact')
      }
      await click([...document.querySelectorAll('button')].find(button => button.textContent.trim() === word('bodyShowChanges')))
      assert.equal(document.querySelectorAll('[data-block-id]').length, 0)
      assert.equal(document.querySelectorAll('[data-cleanup-scope]').length, 2, 'Cleanup candidates survive the ordinary changes-only filter')
      await input(document.querySelector('.body-toolbar input'), 'No matching body text')
      assert.equal(document.querySelectorAll('[data-cleanup-scope]').length, 2, 'Cleanup candidates survive an unrelated body search')
      await input(document.querySelector('.body-toolbar input'), '')
      await click([...document.querySelectorAll('button')].find(button => button.textContent.trim() === word('bodyShowAll')))
      assert.equal(view.events.length, 0)
      assert.equal(view.saves.length, 0)
      assert.equal(apiCalls.length, 0)
    })

    await check('scope deletion is explicit, cancellable, dirty and retained by draft cache/remount until save', async () => {
      await click(scopeButton(tableId))
      assert.deepEqual(view.events, [[tableId, true]])
      assert.equal(bodyDirty(source, view.draft), true)
      assert.ok(scopeCard(tableId).textContent.includes(word('bodyCleanupPending')))
      assert.equal(document.querySelector('[data-cleanup-pending-count]').textContent.trim(), `1 ${word('bodyCleanupPendingCount')}`)
      assert.equal(scopeButton(tableId).textContent.trim(), word('bodyCleanupCancel'))
      assert.deepEqual(clone(bodyPatch(source, view.draft)), { ...emptyPatch(), scopeEdits: [expectedScope(source.bodyCleanupScopes[0])] })
      assert.equal(view.saves.length, 0, 'Marking a scope does not submit a save')
      await click(scopeButton(tableId))
      assert.deepEqual(view.events, [[tableId, true], [tableId, false]])
      assert.equal(bodyDirty(source, view.draft), false)
      assert.deepEqual(clone(bodyPatch(source, view.draft)), emptyPatch())
      await click(scopeButton(tableId))
      const cached = clone(view.draft); view.dispose(); view = mount(cached); await settle()
      assert.equal(bodyDirty(source, view.draft), true)
      assert.ok(scopeCard(tableId).textContent.includes(word('bodyCleanupPending')))
      assert.equal(view.events.length, 0)
      assert.equal(view.saves.length, 0)
      await click(document.querySelector('[data-test-save]'))
      assert.deepEqual(view.saves, [{ ...emptyPatch(), scopeEdits: [expectedScope(source.bodyCleanupScopes[0])] }])
      assert.equal(apiCalls.length, 0, 'The fixture save records the exact patch locally')
    })

    await check('selected cleanup and an independent mounted paragraph edit/insertion preserve both exact patch operations', async () => {
      await click([...ordinaryArticle().querySelectorAll('button')].find(button => button.getAttribute('aria-label') === `${word('bodyEditParagraph')} 900`))
      await input(ordinaryArticle().querySelector('textarea'), 'Revised independent paragraph.')
      await click([...ordinaryArticle().querySelectorAll('button')].find(button => button.textContent.trim() === word('insertParagraphs')))
      await input(ordinaryArticle().querySelectorAll('textarea')[1], 'Independent inserted paragraph.')
      const block = { id: ordinaryId, bindingId: 'CSoutside', expectedTextHash: 'outside-text', text: 'Revised independent paragraph.', insertAfter: ['Independent inserted paragraph.'] }
      assert.deepEqual(clone(bodyPatch(source, view.draft)), { ...emptyPatch(), blocks: [block], scopeEdits: [expectedScope(source.bodyCleanupScopes[0])] })
      await click(scopeButton(tableId))
      assert.equal(bodyDirty(source, view.draft), true, 'Cancelling cleanup preserves the independent body edit')
      assert.deepEqual(clone(bodyPatch(source, view.draft)), { ...emptyPatch(), blocks: [block] }, 'Ordinary body saves do not gain a scopeEdits property')
    })

    await check('all three locales show cleanup purpose, pending status, scope kind and review controls; readonly hides deletion and busy disables it', async () => {
      await click(scopeButton(tableId))
      for (const locale of ['zh-Hans', 'zh-Hant', 'en']) {
        view.locale.value = locale; await settle()
        for (const key of ['bodyCleanupTitle', 'bodyCleanupNote']) assert.ok(document.getElementById('app').textContent.includes(word(key, locale)), `${locale}: ${key}`)
        for (const key of ['bodyCleanupPending', 'bodyCleanupTable', 'bodyCleanupMembers', 'bodyCleanupParagraphs', 'bodyCleanupDetails', 'bodyCleanupPrevious', 'bodyCleanupNext']) assert.ok(scopeCard(tableId).textContent.includes(word(key, locale)), `${locale}: ${key}`)
        assert.ok(scopeCard(rowId).textContent.includes(word('bodyCleanupRow', locale)))
        assert.equal(scopeButton(tableId).textContent.trim(), word('bodyCleanupCancel', locale))
        assert.equal(scopeButton(rowId).textContent.trim(), word('bodyCleanupMark', locale))
      }
      const before = clone(view.draft), events = view.events.length
      view.readonly.value = true; await settle()
      assert.equal(document.querySelectorAll('[data-delete-empty-scope]').length, 0)
      assert.equal(document.querySelectorAll('[data-cleanup-scope]').length, 2, 'Readonly keeps the scope review available')
      view.readonly.value = false; view.disabled.value = true; await settle()
      for (const candidate of source.bodyCleanupScopes) { assert.equal(scopeButton(candidate.scopeId).disabled, true); await click(scopeButton(candidate.scopeId)) }
      assert.equal(view.events.length, events)
      assert.deepEqual(clone(view.draft), before)
    })
  } finally { view.dispose() }

  await check('patch contains only selected scopes, copies exact hashes/members and accepts legacy or all-false caches without scopeEdits', () => {
    const draft = createBodyDraft(source)
    setBodyScopeDeletion(source, draft, rowId, true)
    const patch = bodyPatch(source, draft)
    assert.deepEqual(clone(patch), { ...emptyPatch(), scopeEdits: [expectedScope(source.bodyCleanupScopes[1])] })
    patch.scopeEdits[0].memberBindingIds.push('not-a-source-member')
    assert.deepEqual(source, savedSource, 'Patch arrays cannot mutate the saved source candidate')
    setBodyScopeDeletion(source, draft, rowId, false)
    draft.scopeDeletions = { [tableId]: false, unknown: false }
    assert.equal(bodyDirty(source, draft), false)
    assert.deepEqual(clone(bodyPatch(source, draft)), emptyPatch())
    delete draft.scopeDeletions
    assert.deepEqual(clone(bodyPatch(source, draft)), emptyPatch())
    assert.deepEqual(clone(bodyPatch({ ...source, bodyCleanupScopes: undefined }, createBodyDraft(source))), emptyPatch())
  })

  await check('stale, old-revision, unknown and malformed scope selections fail before any patch can be saved', () => {
    for (const changed of [{ stale: true }, { generated: false }, { revisionId: 'new-revision' }, { docxSha256: 'e'.repeat(64) }]) rejects({ ...source, ...changed }, selected(), JSON.stringify(changed), /STALE|REVISION_CONFLICT/)
    rejects(source, selected(source, 'unknown-scope'), 'Unknown selected scope')
    assert.throws(() => setBodyScopeDeletion(source, createBodyDraft(source), 'unknown-scope', true), /SCOPE|INVALID/)
    for (const invalid of [{ nodeSha256: '' }, { operation: 'delete_empty_row' }, { memberBindingIds: [] }, { memberBindingIds: ['CStableA', 'CStableA'] }, { memberBindingIds: ['CStableA', 'unknown-member'] }]) {
      const malformed = clone(source); Object.assign(malformed.bodyCleanupScopes[0], invalid)
      rejects(malformed, selected(malformed), `Malformed scope: ${JSON.stringify(invalid)}`)
    }
    const duplicate = clone(source); duplicate.bodyCleanupScopes.push(clone(duplicate.bodyCleanupScopes[0]))
    rejects(duplicate, selected(duplicate), 'Duplicate scope IDs')
  })

  await check('selected native scopes reject conflicting text, insertions, paragraph removals and overlapping member selections', () => {
    for (const id of [tableParagraphs[0], tableParagraphs[2]]) {
      const text = selected(); text.texts[id] = 'Conflicting text'
      rejects(source, text, `Scope member text conflict: ${id}`)
      const insertion = selected(); insertion.insertions[id] = ['Conflicting insertion']
      rejects(source, insertion, `Scope member insertion conflict: ${id}`)
      const deletion = selected(); deletion.deletions = { [id]: true }
      rejects(source, deletion, `Scope member paragraph deletion conflict: ${id}`, /SCOPE|CONFLICT|PROTECTED_PARAGRAPH_DELETE/)
    }
    const overlapping = clone(source)
    overlapping.bodyCleanupScopes[1].memberBindingIds = ['CStableA']
    const draft = selected(overlapping); draft.scopeDeletions[rowId] = true
    rejects(overlapping, draft, 'Two selected scopes cannot share a bound member')
    assert.equal(apiCalls.length, 0)
    assert.deepEqual(source, savedSource)
  })
})().catch(error => { console.error(error); process.exitCode = 1 })
