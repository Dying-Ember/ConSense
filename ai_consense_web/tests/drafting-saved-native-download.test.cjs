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
  let confirmed, currentDocument
  const downloads = [], revoked = [], savedBytes = new Blob(['INCOMPLETE DRAFT\nTEST ONLY SAVED BYTES'])
  const testUrl = { createObjectURL(blob) { const url = `blob:test-draft-${downloads.length + 1}`; downloads.push({ blob, url }); return url }, revokeObjectURL(url) { revoked.push(url) } }
  let sourceReply = async () => savedBytes
  let documentsReply = async id => JSON.parse(JSON.stringify(server.get(id) ?? []))
  const sourceReview = vue.defineComponent({ emits: ['confirmed'], setup(_, { emit, expose }) { confirmed = () => emit('confirmed'); expose({ ready: async () => {} }); return () => vue.h('button', { 'data-test-review-confirm': '', onClick: confirmed }, 'TEST ONLY completed review') } })
  const workspace = vue.defineComponent({ props: ['dirty', 'document'], emits: ['input', 'file'], setup(props, { slots }) { return () => { currentDocument = props.document; return vue.h('article', { 'data-test-body-workspace': '', 'data-dirty': String(props.dirty) }, slots.default?.()) } } })
  const api = {
    catalog: async () => ({ ruleVersion: 'TEST-ONLY', groups: [{ id: 'identity', label: { en: 'Identity' }, fields: [{ key: 'projectName', kind: 'text', label: { en: 'Project' } }] }] }),
    variables: async () => [{ key: 'projectName', value: 'TEST ONLY', confirmed: true }],
    templates: async () => ['NTT', 'SCT', 'SCC'].map(key => ({ key, tag: 'ok' })), inputs: async () => [], extractTrace: async () => null,
    documents: async id => { calls.push(['documents', id]); return documentsReply(id) }, plan: async () => ({ actions: [], unresolved: [] }), reviewHead: async () => ({}),
    documentSource: async (id, file, revision) => { calls.push(['documentSource', id, file, revision]); return sourceReply() },
    generate: async () => { calls.push(['generate']); throw Error('No generation belongs to this mounted watcher regression') },
    updateDocument: async () => { calls.push(['updateDocument']); throw Error('No body save belongs to this watcher regression') }
  }
  const loaded = loadVue(path.join(__dirname, '../src/views/DraftingView.vue'), {
    boundaries: { '@/api': { draftingApi: api, setLlmProfileResolver() {}, captureLlmSelection: () => ({ profileId: 'local' }) }, pinia: piniaRuntime, 'vue-i18n': require('vue-i18n'),
      '@/components/DraftingSourceReview.vue': { default: sourceReview }, '@/components/DraftingDocumentWorkspace.vue': { default: workspace },
      'pdfjs-dist': { GlobalWorkerOptions: {}, getDocument() { throw Error('No PDF request belongs to this watcher regression') } }, 'pdfjs-dist/build/pdf.worker.min.mjs?url': { default: 'test-only-worker' },
      'mammoth/mammoth.browser': { convertToHtml() { throw Error('No conversion belongs to this watcher regression') } } },
    globals: { window, document, HTMLElement, Element, Node, Blob, URL: testUrl, localStorage: { getItem: () => 'en', setItem() {} }, crypto: require('node:crypto').webcrypto }
  })
  const pinia = piniaRuntime.createPinia(); piniaRuntime.setActivePinia(pinia)
  const store = loaded.loadLocal(path.join(__dirname, '../src/stores/app.ts')).useAppStore()
  store.switchProject(Object.keys(initial)[0])
  const active = vue.ref(true)
  const root = vue.defineComponent({ setup: () => () => vue.h(vue.KeepAlive, null, () => active.value ? vue.h(loaded.default) : vue.h('div')) })
  const app = vue.createApp(root); app.use(pinia); app.use(loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n); app.mount(host); await settle()
  const button = title => [...host.querySelectorAll('button')].find(node => node.textContent.trim() === title)
  async function click(title) { const control = button(title); assert.ok(control, title); assert.equal(control.disabled, false, `${title} must be enabled`); control.click(); await settle() }
  async function prepareRefresh() { if (!confirmed) await click('2 Variables and graph') }
  async function refresh() { await prepareRefresh(); confirmed(); await settle() }
  function unlocked() { assert.ok([...host.querySelectorAll('.flow-steps button')].every(control => !control.disabled), 'A clean arriving document must not lock the five steps'); assert.equal(host.querySelector('[data-open-business-graph="header"]').disabled, false) }
  async function edit(text, additions) { await click('4 Preview and export'); const edit = host.querySelector('[aria-label="Edit paragraph 1"]'); assert.ok(edit); assert.equal(edit.disabled, false); edit.click(); await settle(); const textarea = host.querySelector('#body-p-1'); assert.ok(textarea); textarea.value = text; textarea.dispatchEvent(new window.Event('input', { bubbles: true })); await settle(); if (additions) { await click('Add paragraphs after this paragraph'); const insert = [...host.querySelectorAll('.body-block textarea')].find(node => node !== textarea); assert.ok(insert); insert.value = additions; insert.dispatchEvent(new window.Event('input', { bubbles: true })); await settle() } }
  const state = loaded.loadLocal(path.join(__dirname, '../src/drafting/state.ts')), cache = state.projectDrafts
  return { host, server, calls, downloads, revoked, savedBytes, savedDraftDownloadReady: state.savedDraftDownloadReady, setSourceReply(fn) { sourceReply = fn }, setDocumentsReply(fn) { documentsReply = fn }, updateCurrentDocument(patch) { Object.assign(currentDocument, patch) }, setActive(value) { active.value = value }, store, cache, button, click, refresh, prepareRefresh, unlocked, edit, dispose() { app.unmount(); host.remove() } }
}

test('saved native draft downloads unchanged, pinned to project and revision; dirty draft is blocked', async () => {
  const h = await harness({ 'TEST-PROJECT': [saved('NTT', 'ntt-revision', 'TEST ONLY body')] })
  const originalClick = window.HTMLAnchorElement.prototype.click
  let filename = null
  window.HTMLAnchorElement.prototype.click = function () { filename = this.download }
  try {
    await h.click('4 Preview and export')
    const button = h.host.querySelector('[data-download-original-language-draft]')
    assert.ok(button); assert.equal(button.disabled, false)
    assert.match(button.textContent, /prepare.*original-language draft.*language not reviewed/i)
    assert.match(h.host.querySelector('[data-original-language-draft-notice]').textContent, /incomplete-draft notice/i)
    button.click(); await settle()
    assert.deepEqual(h.calls.filter(x => x[0] === 'documentSource'), [['documentSource', 'TEST-PROJECT', 'NTT', 'ntt-revision']])
    assert.equal(h.downloads.length, 1); assert.equal(h.downloads[0].blob, h.savedBytes)
    assert.equal(await h.downloads[0].blob.text(), 'INCOMPLETE DRAFT\nTEST ONLY SAVED BYTES')
    assert.equal(filename, null, 'the async response does not synthesize a click')
    const link = h.host.querySelector('[data-ready-original-language-draft]')
    assert.ok(link); assert.equal(link.getAttribute('href'), h.downloads[0].url)
    link.click()
    assert.equal(filename, 'ConSense_NTT_original-language_unreviewed_draft.docx')
    const clean = { inputs: false, review: false, candidate: false, automation: false }
    assert.equal(h.savedDraftDownloadReady(true, 'ntt-revision', clean), true)
    for (const key of Object.keys(clean)) assert.equal(h.savedDraftDownloadReady(true, 'ntt-revision', { ...clean, [key]: true }), false, key)
    assert.equal(h.savedDraftDownloadReady(false, 'ntt-revision', clean), false)
    assert.equal(h.savedDraftDownloadReady(true, undefined, clean), false)
    await h.edit('changed but unsaved')
    assert.equal(h.host.querySelector('[data-download-original-language-draft]').disabled, true)
    assert.equal(h.host.querySelector('[data-ready-original-language-draft]'), null)
    assert.deepEqual(h.revoked, [h.downloads[0].url])
    assert.equal(h.calls.filter(x => x[0] === 'documentSource').length, 1)
  } finally { window.HTMLAnchorElement.prototype.click = originalClick; h.dispose() }
})

test('a prepared link is revoked on file or project changes and on unmount', async () => {
  const h = await harness({ 'TEST-OLD': [saved('NTT', 'ntt-revision', 'TEST ONLY NTT'), saved('SCT', 'sct-revision', 'TEST ONLY SCT')], 'TEST-NEW': [saved('NTT', 'new-revision', 'TEST ONLY new')] })
  try {
    await h.click('4 Preview and export')
    h.host.querySelector('[data-download-original-language-draft]').click(); await settle()
    assert.ok(h.host.querySelector('[data-ready-original-language-draft]'))
    await h.click('SCT')
    assert.equal(h.host.querySelector('[data-ready-original-language-draft]'), null)
    assert.deepEqual(h.revoked, [h.downloads[0].url])
    h.host.querySelector('[data-download-original-language-draft]').click(); await settle()
    h.store.switchProject('TEST-NEW'); await settle()
    assert.ok(h.revoked.includes(h.downloads[1].url))
    await h.click('4 Preview and export')
    h.host.querySelector('[data-download-original-language-draft]').click(); await settle()
    assert.ok(h.host.querySelector('[data-ready-original-language-draft]'))
  } finally { h.dispose() }
  assert.ok(h.revoked.includes(h.downloads[2].url))
})

test('a prepared link is revoked when a refreshed document revision changes', async () => {
  const h = await harness({ 'TEST-PROJECT': [saved('NTT', 'old-revision', 'TEST ONLY old')] })
  try {
    await h.prepareRefresh()
    await h.click('4 Preview and export')
    h.host.querySelector('[data-download-original-language-draft]').click(); await settle()
    assert.ok(h.host.querySelector('[data-ready-original-language-draft]'))
    h.server.set('TEST-PROJECT', [saved('NTT', 'new-revision', 'TEST ONLY new')])
    await h.refresh()
    assert.deepEqual(h.revoked, [h.downloads[0].url])
    await h.click('4 Preview and export')
    assert.equal(h.host.querySelector('[data-ready-original-language-draft]'), null)
  } finally { h.dispose() }
})

test('another busy operation revokes a prepared link before its asynchronous work completes', async () => {
  const h = await harness({ 'TEST-PROJECT': [saved('NTT', 'old-revision', 'TEST ONLY old')] })
  let release
  try {
    await h.prepareRefresh(); await h.click('4 Preview and export')
    h.host.querySelector('[data-download-original-language-draft]').click(); await settle()
    assert.ok(h.host.querySelector('[data-ready-original-language-draft]'))
    h.setDocumentsReply(() => new Promise(resolve => { release = resolve }))
    await h.refresh()
    assert.equal(typeof release, 'function')
    assert.equal(h.host.querySelector('[data-ready-original-language-draft]'), null)
    assert.deepEqual(h.revoked, [h.downloads[0].url])
    release([saved('NTT', 'new-revision', 'TEST ONLY new')]); await settle()
    await h.click('4 Preview and export')
    assert.equal(h.host.querySelector('[data-ready-original-language-draft]'), null)
  } finally { h.dispose() }
})

test('a SHA change and route deactivation each revoke prepared links', async () => {
  const h = await harness({ 'TEST-PROJECT': [saved('NTT', 'same-revision', 'TEST ONLY body')] })
  try {
    await h.click('4 Preview and export')
    h.host.querySelector('[data-download-original-language-draft]').click(); await settle()
    h.updateCurrentDocument({ docxSha256: 'different-sha' }); await settle()
    assert.equal(h.host.querySelector('[data-ready-original-language-draft]'), null)
    assert.deepEqual(h.revoked, [h.downloads[0].url])
    h.host.querySelector('[data-download-original-language-draft]').click(); await settle()
    assert.ok(h.host.querySelector('[data-ready-original-language-draft]'))
    h.setActive(false); await settle()
    assert.ok(h.revoked.includes(h.downloads[1].url))
    h.setActive(true); await settle()
    assert.equal(h.host.querySelector('[data-ready-original-language-draft]'), null)
  } finally { h.dispose() }
})

test('a revision change in the same project while the source loads rejects the stale result with a notice', async () => {
  const h = await harness({ 'TEST-PROJECT': [saved('NTT', 'old-revision', 'TEST ONLY body')] })
  let release
  h.setSourceReply(() => new Promise(resolve => { release = resolve }))
  try {
    await h.click('4 Preview and export')
    h.host.querySelector('[data-download-original-language-draft]').click(); await settle()
    h.updateCurrentDocument({ revisionId: 'new-revision', docxSha256: 'new-sha' }); await settle()
    release(h.savedBytes); await settle()
    assert.equal(h.downloads.length, 0)
    assert.equal(h.host.querySelector('[data-ready-original-language-draft]'), null)
    assert.match(h.host.textContent, /document or pending changes have changed/i)
  } finally { h.dispose() }
})

test('project change while the saved draft is loading cannot download a different project', async () => {
  const h = await harness({ 'TEST-OLD': [saved('NTT', 'old-revision', 'TEST ONLY old')], 'TEST-NEW': [saved('NTT', 'new-revision', 'TEST ONLY new')] })
  let release
  h.setSourceReply(() => new Promise(resolve => { release = resolve }))
  try {
    await h.click('4 Preview and export')
    h.host.querySelector('[data-download-original-language-draft]').click(); await settle()
    assert.equal(typeof release, 'function')
    h.store.switchProject('TEST-NEW'); await settle()
    release(h.savedBytes); await settle()
    assert.equal(h.downloads.length, 0)
  } finally { h.dispose() }
})

test('unsaved project input disables the native-draft download with a save-or-cancel notice', async () => {
  const h = await harness({ 'TEST-INPUT': [saved('NTT', 'input-revision', 'TEST ONLY body')] })
  try {
    await h.click('3 Adopt and plan')
    const input = h.host.querySelector('[data-variable="projectName"] input')
    assert.ok(input)
    input.value = 'TEST ONLY unsaved new value'; input.dispatchEvent(new window.Event('input', { bubbles: true })); await settle()
    await h.click('4 Preview and export')
    assert.equal(h.host.querySelector('[data-download-original-language-draft]').disabled, true)
    assert.match(h.host.querySelector('[data-saved-draft-pending]').textContent, /save or cancel unsaved/i)
    assert.equal(h.calls.filter(x => x[0] === 'documentSource').length, 0)
  } finally { h.dispose() }
})
