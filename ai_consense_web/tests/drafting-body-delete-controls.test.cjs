/* Mounted public body controls and real patch builder; no application API or native Word writes. */
const assert = require('node:assert/strict')
const path = require('node:path')
const { JSDOM } = require('jsdom')
const window = new JSDOM('<main id="app"></main>').window
for (const key of ['window', 'document', 'Element', 'HTMLElement', 'SVGElement', 'Node', 'Document', 'ShadowRoot']) globalThis[key] = key === 'window' ? window : window[key]
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { settle } = require('./helpers/render-controls.cjs')
const loaded = loadVue(path.join(__dirname, '../src/components/DraftingBodyEditor.vue'))
const { createBodyDraft, bodyPatch, bodyDirty, setBodyDeletion } = loaded.loadLocal(path.join(__dirname, '../src/drafting/body-edit.ts'))
const { draftWord } = loaded.loadLocal(path.join(__dirname, '../src/drafting/words.ts'))
const id = 'word/document.xml#/w:document[1]/w:body[1]/w:p[1613]'
const source = { revisionId: 'SYNTHETIC-revision', docxSha256: 'SYNTHETIC-sha', generated: true, stale: false, blocks: [
  { id, bindingId: 'CSmath', text: '(i.e. \n', textHash: 'math-text', paragraphOrdinal: 1655, editable: true },
  { id: 'word/document.xml#/w:document[1]/w:body[1]/w:tbl[1]/w:tr[1]/w:tc[1]/w:p[1]', bindingId: 'CScell', text: 'Native cell', textHash: 'cell-text', cellId: 'cell', paragraphOrdinal: 1656, editable: true },
  { id: 'word/document.xml#/w:document[1]/w:body[1]/w:p[1614]', bindingId: 'CSfield', text: 'Protected field', textHash: 'field-text', paragraphOrdinal: 1657, editable: false }
] }
function mount(cached) {
  const draft = vue.reactive(cached ?? createBodyDraft(source)), readonly = vue.ref(false), disabled = vue.ref(false), locale = vue.ref('en'), saves = [], events = []
  const app = vue.createApp({ render: () => vue.h('div', [vue.h(loaded.default, { blocks: source.blocks, draft, readonly: readonly.value, disabled: disabled.value, locale: locale.value,
    onText: (id, text) => { draft.texts[id] = text }, onInsert: (id, lines) => { draft.insertions[id] = lines },
    onRemove: (id, remove) => { events.push([id, remove]); setBodyDeletion(source.blocks.find(b => b.id === id), draft, remove) } }),
    vue.h('button', { 'data-test-save': '', onClick: () => saves.push(JSON.parse(JSON.stringify(bodyPatch(source, draft)))) }, 'Save fixture')]) })
  app.mount(document.getElementById('app'))
  return { draft, readonly, disabled, locale, saves, events, dispose: () => app.unmount() }
}
const removal = () => document.querySelector('[data-delete-paragraph]')
const article = () => [...document.querySelectorAll('[data-block-id]')].find(node => node.dataset.blockId === id)
async function click(label) { const button = [...document.querySelectorAll('button')].find(node => node.textContent.trim() === label); assert.ok(button, `Visible control: ${label}`); button.click(); await settle() }
;(async () => {
  const first = mount(); await settle()
  assert.equal(document.querySelectorAll('[data-delete-paragraph]').length, 1, 'Only a bound direct body paragraph exposes native removal')
  removal().click(); await settle()
  assert.equal(bodyDirty(source, first.draft), true)
  assert.ok(article().textContent.includes('Entire paragraph pending deletion'))
  assert.ok(article().textContent.includes(source.blocks[0].text))
  assert.equal(first.saves.length, 0, 'Marking deletion does not save or call an API')
  await click('Show changes only')
  assert.equal(document.querySelectorAll('[data-block-id]').length, 1)
  removal().click(); await settle()
  assert.equal(bodyDirty(source, first.draft), false)
  assert.equal(document.querySelectorAll('[data-block-id]').length, 0)
  await click('Show all paragraphs')
  removal().click(); await settle()
  const cached = JSON.parse(JSON.stringify(first.draft)); first.dispose()
  const restored = mount(cached); await settle()
  assert.ok(article().textContent.includes('Entire paragraph pending deletion'), 'The same pending removal survives draft remount')
  document.querySelector('[data-test-save]').click(); await settle()
  assert.deepEqual(restored.saves, [{ revisionId: source.revisionId, docxSha256: source.docxSha256, blocks: [{ id, bindingId: 'CSmath', expectedTextHash: 'math-text', operation: 'delete_paragraph' }] }])
  for (const locale of ['zh-Hans', 'zh-Hant', 'en']) {
    restored.locale.value = locale; await settle()
    assert.ok(article().textContent.includes(draftWord('bodyPendingDeletion', locale)))
    assert.equal(removal().textContent.trim(), draftWord('bodyCancelDeletion', locale))
  }
  restored.readonly.value = true; await settle()
  assert.equal(document.querySelectorAll('[data-block-id] button').length, 0)
  restored.readonly.value = false; restored.disabled.value = true; await settle()
  assert.equal(removal().disabled, true)
  removal().click(); await settle()
  assert.equal(restored.events.length, 0)
  assert.equal(source.blocks[0].text, '(i.e. \n', 'The saved source remains immutable throughout review')
  restored.dispose()
  console.log('PASS: native paragraph deletion is explicit, cancellable, reviewable, cached, save-bound, localized and unavailable for cells/fields/readonly/busy states')
})().catch(error => { console.error(error); process.exitCode = 1 })
