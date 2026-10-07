/* Actual mounted status component. Synthetic receipts test presentation only;
   native pagination, preservation and PDF appearance are verified separately. */
const assert = require('node:assert/strict'), path = require('node:path')
const { JSDOM } = require('jsdom'), window = new JSDOM('<main id="app"></main>').window
for (const key of ['window', 'document', 'Element', 'HTMLElement', 'SVGElement', 'Node']) globalThis[key] = key === 'window' ? window : window[key]
const vue = require('vue'), { loadVue } = require('./helpers/load-vue.cjs')
const component = loadVue(path.join(__dirname, '../src/components/DraftingNativeLayoutStatus.vue')).default
const state = vue.reactive({ locale: 'en', layout: undefined })
const app = vue.createApp({ render: () => vue.h(component, state) }); app.mount('#app')
;(async () => {
  assert.equal(document.querySelector('[data-native-layout-status]'), null)
  state.layout = { kind: 'native_layout', status: 'applied', actions: [{ action: 'page_break_before', status: 'APPLIED' }] }
  await vue.nextTick()
  assert.match(document.body.textContent, /applied to this document revision/)
  assert.doesNotMatch(document.body.textContent, /fully verified|Word-identical|guaranteed/i)
  state.layout = { kind: 'native_layout', status: 'not_applied_to_saved_revision', actions: [] }
  await vue.nextTick()
  assert.match(document.body.textContent, /saved revision.*unchanged/)
  assert.equal(document.querySelector('button'), null, 'Reading a legacy receipt cannot generate or overwrite a saved body')
  state.layout = { kind: 'native_layout', status: 'skipped_unverified_source', actions: [] }
  await vue.nextTick()
  assert.match(document.body.textContent, /Original layout retained/)
  state.locale = 'zh-Hans'; await vue.nextTick(); assert.match(document.body.textContent, /保留原排版/)
  state.locale = 'zh-Hant'; await vue.nextTick(); assert.match(document.body.textContent, /保留原排版/)
  state.layout.status = 'unavailable'; await vue.nextTick(); assert.match(document.body.textContent, /無法.*核對/)
  state.layout.status = 'future_unknown_status'; await vue.nextTick()
  assert.equal(document.querySelector('[data-native-layout-status]'), null, 'Unknown receipts must not be presented as applied')
  app.unmount()
  console.log('PASS: applied, legacy, unverified and unavailable receipts remain distinct in all three locales without mutation controls')
})().catch(error => { console.error(error); process.exitCode = 1 })
