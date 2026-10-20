/* Mounted View watchers and the real body editor; only HTTP and the heavy document/source-review carriers are boundaries. */
const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const { JSDOM } = require('jsdom')
const window = new JSDOM('<main></main>').window
for (const key of ['window', 'document', 'Element', 'HTMLElement', 'SVGElement', 'Node']) globalThis[key] = key === 'window' ? window : window[key]
const vue = require('vue'), piniaRuntime = require('pinia')
const { loadVue } = require('./helpers/load-vue.cjs')
const settle = async () => { for (let n = 0; n < 16; n++) { await Promise.resolve(); await vue.nextTick() } }
const bodyId = file => `word/document.xml#TEST-ONLY-${file}-paragraph`
const saved = (file, revision, text) => ({ fileKey: file, title: `TEST ONLY ${file}`, content: text, generated: true, stale: false, revisionId: revision, docxSha256: revision.padEnd(64, '0'), blocks: [{ id: bodyId(file), text, textHash: `TEST-ONLY-${revision}`, paragraphOrdinal: 1, editable: true }] })

async function harness(initial) {
  const server = new Map(Object.entries(initial)), calls = [], host = document.createElement('div'); document.body.append(host)
  let confirmed
  const sourceReview = vue.defineComponent({ emits: ['confirmed'], setup(_, { emit, expose }) { confirmed = () => emit('confirmed'); expose({ ready: async () => {} }); return () => vue.h('button', { 'data-test-review-confirm': '', onClick: confirmed }, 'TEST ONLY completed review') } })
  const workspace = vue.defineComponent({ props: ['dirty', 'document'], emits: ['input', 'file'], setup(props, { slots }) { return () => vue.h('article', { 'data-test-body-workspace': '', 'data-dirty': String(props.dirty) }, slots.default?.()) } })
  const api = {
    catalog: async () => ({ ruleVersion: 'TEST-ONLY', groups: [{ id: 'identity', label: { en: 'Identity' }, fields: [{ key: 'projectName', kind: 'text', label: { en: 'Project' } }] }] }),
    variables: async () => [{ key: 'projectName', value: 'TEST ONLY', confirmed: true }],
    templates: async () => ['NTT', 'SCT', 'SCC'].map(key => ({ key, tag: 'ok' })), inputs: async () => [], extractTrace: async () => null,
    documents: async id => { calls.push(['documents', id]); return JSON.parse(JSON.stringify(server.get(id) ?? [])) }, plan: async () => ({ actions: [], unresolved: [] }),
    generate: async () => { calls.push(['generate']); throw Error('No generation belongs to this mounted watcher regression') },
    updateDocument: async () => { calls.push(['updateDocument']); throw Error('No body save belongs to this watcher regression') }
  }
  const loaded = loadVue(path.join(__dirname, '../src/views/DraftingView.vue'), {
    boundaries: { '@/api': { draftingApi: api, setLlmProfileResolver() {}, captureLlmSelection: () => ({ profileId: 'local' }) }, pinia: piniaRuntime, 'vue-i18n': require('vue-i18n'),
      '@/components/DraftingSourceReview.vue': { default: sourceReview }, '@/components/DraftingDocumentWorkspace.vue': { default: workspace },
      'pdfjs-dist': { GlobalWorkerOptions: {}, getDocument() { throw Error('No PDF request belongs to this watcher regression') } }, 'pdfjs-dist/build/pdf.worker.min.mjs?url': { default: 'test-only-worker' },
      'mammoth/mammoth.browser': { convertToHtml() { throw Error('No conversion belongs to this watcher regression') } } },
    globals: { window, document, HTMLElement, Element, Node, Blob, localStorage: { getItem: () => 'en', setItem() {} }, crypto: require('node:crypto').webcrypto }
  })
  const pinia = piniaRuntime.createPinia(); piniaRuntime.setActivePinia(pinia)
  const store = loaded.loadLocal(path.join(__dirname, '../src/stores/app.ts')).useAppStore()
  store.switchProject(Object.keys(initial)[0])
  const app = vue.createApp(loaded.default); app.use(pinia); app.use(loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n); app.mount(host); await settle()
  const button = title => [...host.querySelectorAll('button')].find(node => node.textContent.trim() === title)
  async function click(title) { const control = button(title); assert.ok(control, title); assert.equal(control.disabled, false, `${title} must be enabled`); control.click(); await settle() }
  async function prepareRefresh() { if (!confirmed) await click('3 Source review') }
  async function refresh() { await prepareRefresh(); confirmed(); await settle() }
  function unlocked() { assert.ok([...host.querySelectorAll('.flow-steps button')].every(control => !control.disabled), 'A clean arriving document must not lock the five steps'); assert.equal(host.querySelector('[data-open-business-graph="header"]').disabled, false) }
  async function edit(text, additions) { await click('5 Preview and export'); const edit = host.querySelector('[aria-label="Edit paragraph 1"]'); assert.ok(edit); assert.equal(edit.disabled, false); edit.click(); await settle(); const textarea = host.querySelector('#body-p-1'); assert.ok(textarea); textarea.value = text; textarea.dispatchEvent(new window.Event('input', { bubbles: true })); await settle(); if (additions) { await click('Add paragraphs after this paragraph'); const insert = [...host.querySelectorAll('.body-block textarea')].find(node => node !== textarea); assert.ok(insert); insert.value = additions; insert.dispatchEvent(new window.Event('input', { bubbles: true })); await settle() } }
  const cache = loaded.loadLocal(path.join(__dirname, '../src/drafting/state.ts')).projectDrafts
  return { host, server, calls, store, cache, button, click, refresh, prepareRefresh, unlocked, edit, dispose() { app.unmount(); host.remove() } }
}

test('the first backend document arriving after the initial load initializes its body instead of inventing unsaved edits', async () => {
  const h = await harness({ 'TEST-ARRIVAL': [] })
  try {
    await h.prepareRefresh(); h.server.set('TEST-ARRIVAL', [saved('NTT', 'first-revision', 'TEST ONLY first backend body')]); await h.refresh()
    h.unlocked(); await h.click('5 Preview and export')
    assert.equal(h.host.querySelector('[data-test-body-workspace]').dataset.dirty, 'false')
    assert.match(h.host.querySelector('.body-block .paragraph-text').textContent, /first backend body/)
    assert.equal(h.host.querySelector('[data-draft-stale-status]'), null)
    assert.equal(h.calls.some(call => ['generate', 'updateDocument'].includes(call[0])), false)
  } finally { h.dispose() }
})

test('a clean existing document follows a new saved revision and its new paragraph text', async () => {
  const h = await harness({ 'TEST-CLEAN-REVISION': [saved('NTT', 'old-revision', 'TEST ONLY old saved body')] })
  try {
    await h.prepareRefresh(); h.server.set('TEST-CLEAN-REVISION', [saved('NTT', 'new-revision', 'TEST ONLY new saved body')]); await h.refresh()
    h.unlocked(); await h.click('5 Preview and export')
    assert.equal(h.host.querySelector('[data-test-body-workspace]').dataset.dirty, 'false')
    assert.match(h.host.querySelector('.body-block .paragraph-text').textContent, /new saved body/)
    assert.doesNotMatch(h.host.querySelector('.body-block .paragraph-text').textContent, /old saved body/)
    assert.equal(h.cache.get('TEST-CLEAN-REVISION')?.documents.NTT, undefined)
  } finally { h.dispose() }
})

test('a real paragraph edit and insertion survive a later backend revision and project cache round trip', async () => {
  const h = await harness({ 'TEST-DIRTY': [saved('NTT', 'old-revision', 'TEST ONLY saved body')], 'TEST-OTHER': [saved('NTT', 'other-revision', 'TEST ONLY other project')] })
  try {
    await h.prepareRefresh(); await h.edit('TEST ONLY retained manual body', 'TEST ONLY inserted paragraph')
    assert.equal(h.host.querySelector('[data-test-body-workspace]').dataset.dirty, 'true')
    h.server.set('TEST-DIRTY', [saved('NTT', 'new-revision', 'TEST ONLY newer backend body')]); await h.refresh()
    assert.ok([...h.host.querySelectorAll('.flow-steps button')].every(control => control.disabled), 'A genuine old-revision edit remains protected')
    h.store.switchProject('TEST-OTHER'); await settle(); h.unlocked(); h.store.switchProject('TEST-DIRTY'); await settle()
    const pending = h.cache.get('TEST-DIRTY').documents.NTT.body
    assert.equal(pending.revisionId, 'old-revision'); assert.equal(pending.texts[bodyId('NTT')], 'TEST ONLY retained manual body'); assert.deepEqual([...pending.insertions[bodyId('NTT')]], ['TEST ONLY inserted paragraph'])
    assert.equal(h.calls.some(call => ['generate', 'updateDocument'].includes(call[0])), false)
  } finally { h.dispose() }
})

test('a cached SCT body returns to the correct project and file without leaking into clean NTT', async () => {
  const h = await harness({ 'TEST-FILES': [saved('NTT', 'ntt-revision', 'TEST ONLY clean NTT'), saved('SCT', 'sct-revision', 'TEST ONLY clean SCT')], 'TEST-OTHER': [saved('NTT', 'other-revision', 'TEST ONLY other project')] })
  try {
    await h.click('5 Preview and export'); await h.click('SCT'); await h.edit('TEST ONLY cached SCT edit')
    h.store.switchProject('TEST-OTHER'); await settle(); h.unlocked(); h.store.switchProject('TEST-FILES'); await settle()
    assert.ok(h.host.querySelector('.body-block .paragraph-text'), JSON.stringify({ cached: h.cache.get('TEST-FILES'), text: h.host.textContent }))
    assert.match(h.host.querySelector('.body-block .paragraph-text').textContent, /cached SCT edit/)
    assert.equal(h.host.querySelector('[data-test-body-workspace]').dataset.dirty, 'true')
    assert.equal(h.cache.get('TEST-FILES').view.activeFile, 'SCT'); assert.equal(h.cache.get('TEST-FILES').documents.NTT, undefined)
    await h.click('Discard unsaved changes'); await h.click('NTT')
    assert.equal(h.host.querySelector('[data-test-body-workspace]').dataset.dirty, 'false'); assert.match(h.host.querySelector('.body-block .paragraph-text').textContent, /clean NTT/)
    assert.equal(h.cache.get('TEST-FILES').documents.SCT, undefined)
  } finally { h.dispose() }
})
