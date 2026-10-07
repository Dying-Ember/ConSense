/* Public mounted Vue controls; these checks do not establish native Word layout. */
const assert = require('node:assert/strict')
const path = require('node:path')
const { JSDOM } = require('jsdom')
const window = new JSDOM('<main id="app"></main>').window
for (const key of ['window', 'document', 'Element', 'HTMLElement', 'SVGElement', 'Node', 'Document', 'ShadowRoot']) globalThis[key] = key === 'window' ? window : window[key]
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { settle } = require('./helpers/render-controls.cjs')
const loaded = loadVue(path.join(__dirname, '../src/components/DraftingBodyEditor.vue'))
const { createBodyDraft, bodyPatch } = loaded.loadLocal(path.join(__dirname, '../src/drafting/body-edit.ts'))
const original = 'Clause 12. Exact\tformal wording; <untrusted> text.  '
const source = { revisionId: 'SYNTHETIC-revision-1', docxSha256: 'SYNTHETIC-docx-hash', generated: true, stale: false, blocks: [
  { id: 'source-cell/p[2]', text: original, textHash: 'SYNTHETIC-block-2', paragraphOrdinal: 2, editable: true },
  { id: 'toc/p[3]', text: 'Protected TOC field result', textHash: 'SYNTHETIC-block-3', paragraphOrdinal: 3, editable: false },
  { id: 'source/p[4]', text: 'Clause 13. Second editable paragraph.', textHash: 'SYNTHETIC-block-4', paragraphOrdinal: 4, editable: true }
] }
function mount() {
  const draft = vue.reactive(createBodyDraft(source)), events = []
  const locale = vue.ref('en'), disabled = vue.ref(false), readonly = vue.ref(false)
  const app = vue.createApp({ render: () => vue.h(loaded.default, { blocks: source.blocks, draft, locale: locale.value, disabled: disabled.value, readonly: readonly.value,
    onText(id, value) { events.push(['text', id, value]); draft.texts[id] = value },
    onInsert(id, paragraphs) { events.push(['insert', id, paragraphs]); draft.insertions[id] = paragraphs }
  }) })
  app.mount('#app')
  return { draft, events, locale, disabled, readonly, dispose: () => app.unmount() }
}
function paragraph(id) { return [...document.querySelectorAll('[data-block-id]')].find(node => node.dataset.blockId === id) }
async function click(label, within = document) { const button = [...within.querySelectorAll('button')].find(node => node.textContent.trim() === label); assert.ok(button, `Visible control: ${label}`); button.click(); await settle() }
function input(element, value) { assert.ok(element, 'Editable control is visible'); element.value = value; element.dispatchEvent(new window.Event('input', { bubbles: true })) }

;(async () => {
  const reader = mount(); await settle()
  assert.equal(document.querySelectorAll('textarea').length, 0, 'Reading begins with document paragraphs, not a wall of edit controls')
  assert.ok(paragraph('source-cell/p[2]').textContent.includes(original), 'Exact tabs, spaces and untrusted text are readable')
  assert.ok(paragraph('toc/p[3]').textContent.includes('Protected TOC field result'))
  assert.equal(document.querySelector('untrusted'), null)
  await click('Edit paragraph', paragraph('source-cell/p[2]'))
  assert.equal(document.querySelectorAll('textarea').length, 1, 'Only the selected paragraph opens an editor')
  assert.equal([...paragraph('source-cell/p[2]').querySelectorAll('button')].find(node => node.textContent.trim() === 'Done editing').getAttribute('aria-label'), 'Done editing 2', 'The selected paragraph control exposes the visible close action to assistive technology')
  assert.equal(document.getElementById('body-p-2').value, original)
  assert.ok(paragraph('source-cell/p[2]').textContent.includes('Original text'))
  const revised = '  Clause 12. Revised\twording; unchanged spaces.\nSecond line.  '
  input(document.getElementById('body-p-2'), revised); await settle()
  assert.equal(reader.draft.texts['source-cell/p[2]'], revised)
  assert.equal(source.blocks[0].text, original, 'Saved source text remains immutable while editing')
  await click('Done editing', paragraph('source-cell/p[2]'))
  assert.equal(document.querySelectorAll('textarea').length, 0)
  assert.ok(paragraph('source-cell/p[2]').textContent.includes(revised), 'Closing the editor retains and displays the pending value')
  assert.equal(reader.events.length, 1, 'Reading and editor selection do not save, reset or emit edits')
  reader.dispose()
  console.log('PASS: actual body controls begin as readable exact paragraphs and edit only the selected paragraph, preserving pending text and immutable saved source')
  const searching = mount(); await settle()
  await click('Edit paragraph', paragraph('source-cell/p[2]'))
  input(document.getElementById('body-p-2'), 'Unique pending revised wording.'); await settle()
  input(document.querySelector('input'), '  pending revised  '); await settle()
  assert.ok(paragraph('source-cell/p[2]'), 'Search matches the current unsaved body, with surrounding whitespace ignored')
  assert.equal(paragraph('source/p[4]'), undefined)
  input(document.querySelector('input'), 'formal wording'); await settle()
  assert.equal(paragraph('source-cell/p[2]'), undefined, 'Removed original words are not searched as current text')
  input(document.querySelector('input'), '3'); await settle()
  assert.ok(paragraph('toc/p[3]'), 'Original paragraph ordinal remains searchable for protected text')
  assert.equal(paragraph('source-cell/p[2]'), undefined)
  assert.equal(searching.events.length, 1, 'Search does not edit or save pending text')
  searching.dispose()
  console.log('PASS: body search follows current pending text and stable paragraph ordinals without treating replaced saved text as current')
  const changes = mount(); await settle()
  await click('Edit paragraph', paragraph('source-cell/p[2]'))
  input(document.getElementById('body-p-2'), 'Pending first paragraph.'); await settle()
  assert.ok(paragraph('source-cell/p[2]').textContent.includes('Modified'), 'Pending edits are explicitly marked beside the readable paragraph')
  await click('Add paragraphs after this paragraph', paragraph('source-cell/p[2]'))
  input(paragraph('source-cell/p[2]').querySelectorAll('textarea')[1], 'First inserted paragraph.\n\nSecond inserted paragraph.'); await settle()
  assert.deepEqual(Array.from(changes.draft.insertions['source-cell/p[2]']), ['First inserted paragraph.', '', 'Second inserted paragraph.'])
  await click('Done editing', paragraph('source-cell/p[2]'))
  assert.equal(document.querySelectorAll('textarea').length, 0)
  assert.ok(paragraph('source-cell/p[2]').textContent.includes('Second inserted paragraph.'), 'Added paragraphs stay readable with the editor closed')
  await click('Edit paragraph', paragraph('source/p[4]'))
  input(document.getElementById('body-p-4'), 'Independent pending paragraph.'); await settle()
  await click('Show changes only')
  assert.equal(paragraph('toc/p[3]'), undefined, 'Changed-only view excludes unchanged protected text')
  assert.ok(paragraph('source-cell/p[2]')); assert.ok(paragraph('source/p[4]'))
  await click('Restore original', paragraph('source-cell/p[2]'))
  assert.equal(changes.draft.texts['source-cell/p[2]'], original)
  assert.deepEqual(Array.from(changes.draft.insertions['source-cell/p[2]']), [])
  assert.equal(changes.draft.texts['source/p[4]'], 'Independent pending paragraph.', 'Restoring one paragraph retains another pending edit')
  assert.equal(paragraph('source-cell/p[2]'), undefined)
  const patch = JSON.parse(JSON.stringify(bodyPatch(source, changes.draft)))
  assert.deepEqual(patch, { revisionId: 'SYNTHETIC-revision-1', docxSha256: 'SYNTHETIC-docx-hash', blocks: [{ id: 'source/p[4]', expectedTextHash: 'SYNTHETIC-block-4', text: 'Independent pending paragraph.' }] }, 'Existing public save patch retains exact source/revision identity and only outstanding edits')
  await click('Show all paragraphs')
  assert.ok(paragraph('toc/p[3]')); assert.ok(paragraph('source-cell/p[2]').textContent.includes(original))
  changes.dispose()
  console.log('PASS: pending text and added paragraphs are readable, changed-only filtering and per-paragraph restore retain unrelated edits and exact existing save identities')
  const protection = mount(); await settle()
  assert.equal(paragraph('toc/p[3]').querySelector('button'), null, 'Protected source paragraphs have no edit or insertion action')
  protection.readonly.value = true; await settle()
  assert.equal(document.querySelectorAll('[data-block-id] button').length, 0, 'A read-only saved revision remains readable without edit controls')
  protection.readonly.value = false; protection.disabled.value = true; await settle()
  const disabledEdit = paragraph('source-cell/p[2]').querySelector('button')
  assert.equal(disabledEdit.disabled, true); disabledEdit.click(); await settle()
  assert.equal(document.querySelectorAll('textarea').length, 0, 'Busy UI cannot start body editing')
  protection.disabled.value = false
  for (const locale of ['zh-Hans', 'zh-Hant', 'en']) {
    protection.locale.value = locale; await settle()
    assert.equal(document.querySelectorAll('[data-block-id]').length, 3)
    assert.ok(paragraph('source-cell/p[2]').textContent.includes(original), 'Locale changes leave exact English source text unchanged')
  }
  assert.equal(protection.events.length, 0)
  protection.dispose()
  console.log('PASS: protected and read-only paragraphs never open editors, disabled actions cannot edit, and locale switching preserves exact English content')
})().catch(error => { console.error(error); process.exitCode = 1 })
