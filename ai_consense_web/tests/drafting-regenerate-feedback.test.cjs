const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const { JSDOM } = require('jsdom')
const window = new JSDOM('<main id="app"></main>').window
for (const key of ['window', 'document', 'Element', 'HTMLElement', 'SVGElement', 'Node']) globalThis[key] = key === 'window' ? window : window[key]
const vue = require('vue'), piniaRuntime = require('pinia')
const { loadVue } = require('./helpers/load-vue.cjs')
const wait = async () => { for (let i = 0; i < 10; i++) { await Promise.resolve(); await vue.nextTick() } }

test('stale regeneration retains one actionable status and one failure in expanded reading without unlocking the old revision', async () => {
  const old = { fileKey: 'SCT', title: 'AUTHORED previous SCT', content: 'AUTHORED retained old body', generated: true, stale: true, revisionId: 'authored-old-revision', docxSha256: '1'.repeat(64), blocks: [] }
  const generations = []
  const api = {
    catalog: async () => ({ ruleVersion: 'AUTHORED', groups: [{ id: 'identity', label: { en: 'Identity' }, fields: [{ key: 'projectName', kind: 'text', label: { en: 'Project' } }] }] }),
    variables: async () => [{ key: 'projectName', value: 'AUTHORED', confirmed: true }],
    templates: async () => ['NTT', 'SCT', 'SCC'].map(key => ({ key, tag: 'ok' })), inputs: async () => [], extractTrace: async () => null,
    documents: async () => [old], plan: async () => ({ actions: [], unresolved: [] }),
    generate: async (...args) => { generations.push(args); throw new Error('AUTHORED generation failed; previous revision retained') }
  }
  const loaded = loadVue(path.join(__dirname, '../src/views/DraftingView.vue'), {
    boundaries: { '@/api/source-locations': { reviewSourceBindings: async () => { throw Error('Source location transport not provided by this fixture') } }, '@/api': { draftingApi: api, setLlmProfileResolver() {}, captureLlmSelection: () => ({ profileId: 'local' }) }, pinia: piniaRuntime, 'vue-i18n': require('vue-i18n'),
      'pdfjs-dist': { GlobalWorkerOptions: {}, getDocument() { throw new Error('No PDF request belongs to this failure-feedback test') } },
      'pdfjs-dist/build/pdf.worker.min.mjs?url': { default: 'authored-worker' },
      'mammoth/mammoth.browser': { convertToHtml() { throw new Error('No template conversion belongs to this feedback test') } },
      '@/components/DraftingDocumentWorkspace.vue': { default: { render: () => vue.h('article', { 'data-authored-reading': '' }, old.content) } } },
    globals: { window, document, HTMLElement, Element, Node, Blob, localStorage: { getItem: () => 'en', setItem() {} }, crypto: require('node:crypto').webcrypto }
  })
  const pinia = piniaRuntime.createPinia(); piniaRuntime.setActivePinia(pinia)
  const store = loaded.loadLocal(path.join(__dirname, '../src/stores/app.ts')).useAppStore(); store.switchProject('AUTHORED-DRAFT-STATUS')
  const app = vue.createApp(loaded.default); app.use(pinia); app.use(loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n); app.mount('#app')
  const button = title => [...document.querySelectorAll('button')].find(node => node.textContent.trim() === title)
  const click = async title => { const control = button(title); assert.ok(control, title); assert.equal(control.disabled, false); control.click(); await wait() }
  try {
    await wait(); await click('4 Preview and export'); await click('SCT')
    assert.equal(document.querySelectorAll('[data-draft-stale-status]').length, 1)
    const status = document.querySelector('[data-draft-stale-status]').textContent
    assert.match(status, /Variable values or sources have changed/); assert.match(status, /read-only/)
    const layoutLimits = document.querySelector('[data-draft-layout-limits]')
    assert.ok(layoutLimits, 'Layout limitations remain directly visible beside the saved document')
    assert.equal(layoutLimits.closest('details'), null, 'Reading layout limitations requires no disclosure step')
    assert.match(layoutLimits.querySelector('strong').textContent, /Layout checks remain unverified/)
    assert.match(layoutLimits.textContent, /PDF font substitutions and TOC/)
    for (const [locale, notice] of [
      ['zh-Hans', '导出文件统一为英文；非英文内容自动翻译，来源译文会标注。'],
      ['zh-Hant', '匯出檔案統一為英文；非英文內容自動翻譯，來源譯文會標註。'],
      ['en', 'Exports are in English. Non-English content is translated automatically; translated source quotations are labelled.']
    ]) {
      store.changeLocale(locale); await wait()
      const languageNotice = document.querySelector('[data-generated-output-language]')
      assert.ok(languageNotice); assert.equal(languageNotice.textContent, notice); assert.equal(languageNotice.closest('details'), null)
    }
    await click('Expand document'); await click('Regenerate')
    const errors = [...document.querySelectorAll('[role="alert"]')].filter(node => node.textContent.includes('AUTHORED generation failed'))
    assert.equal(errors.length, 1, 'The expanded workspace owns the failed attempt; no duplicate background alert')
    assert.equal(generations.length, 1)
    assert.equal(generations[0][0], 'AUTHORED-DRAFT-STATUS')
    assert.equal(typeof generations[0][1], 'object', 'Generation captures the model selection without an interface language parameter')
    assert.equal(generations[0].length, 2)
    assert.match(document.querySelector('[data-authored-reading]').textContent, /retained old body/)
    assert.equal(button('Edit content').disabled, true); assert.equal(button('Export PDF').disabled, true); assert.equal(button('Export Word').disabled, true)
    assert.equal(button('Regenerate').disabled, false, 'The retained failed attempt can be retried explicitly')
  } finally { app.unmount() }
})
