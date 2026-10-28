const assert = require('node:assert/strict')
const path = require('node:path')
const vue = require('vue')
const piniaRuntime = require('pinia')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle } = require('./helpers/render-controls.cjs')

const globals = { structuredClone, Blob, URL, crypto: require('node:crypto').webcrypto,
  localStorage: { getItem: () => 'en', setItem() {} }, window: { addEventListener() {}, removeEventListener() {} },
  document: { documentElement: {}, getElementById() { return null }, body: { classList: { toggle() {} } } } }

;(async () => {
  const calls = [], head = { currentRevisionId: 'current', draftRevisionId: 'draft', current: null, draft: null, sources: [] }
  let reviewInstances = 0
  const read = (name, value) => async id => { calls.push([name, id]); return typeof value === 'function' ? value(id) : value }
  const api = {
    templates: read('templates', [{ key: 'NTT', tag: 'ok' }]), inputs: read('inputs', []),
    catalog: read('catalog', { ruleVersion: 'test', groups: [] }), variables: read('variables', []),
    documents: read('documents', []), extractTrace: read('trace', null),
    reviewHead: read('head', head),
    automationLatest: read('automation', null), plan: read('plan', { actions: [], unresolved: [] })
  }
  const Review = { props: ['active'], emits: ['dirty'], setup(props, { expose, emit }) {
    reviewInstances++; expose({ ready: async () => {}, refresh: async () => {} })
    return () => vue.h('button', { 'data-review': props.active ? 'active' : 'inactive', onClick: () => emit('dirty', true) }, 'unsaved review')
  } }
  const loaded = loadVue(path.join(__dirname, '../src/views/DraftingView.vue'), { boundaries: {
    '@/api': { draftingApi: api, captureLlmSelection: () => ({}), setLlmProfileResolver() {} }, pinia: piniaRuntime,
    'vue-i18n': require('vue-i18n'), '@/components/DraftingSourceReview.vue': { default: Review },
    '@/components/DraftingDocumentWorkspace.vue': { default: { render: () => vue.h('div', 'document workspace') } },
    '@/components/DraftingCandidateReview.vue': { default: { render: () => null } }, '@/components/DraftingTemplatePreview.vue': { default: { render: () => null } }
  }, globals })
  const pinia = piniaRuntime.createPinia(); piniaRuntime.setActivePinia(pinia)
  const store = loaded.loadLocal(path.join(__dirname, '../src/stores/app.ts')).useAppStore(); store.switchProject('A')
  const route = vue.ref('drafting'); let otherMounts = 0, otherUnmounts = 0
  const Other = { setup() { vue.onMounted(() => otherMounts++); vue.onBeforeUnmount(() => otherUnmounts++); return () => vue.h('div', 'other') } }
  const RouterView = { setup(_, { slots }) { return () => slots.default({ Component: route.value === 'drafting' ? loaded.default : Other, route: { name: route.value } }) } }
  const empty = { render: () => null }
  const shell = loadVue(path.join(__dirname, '../src/App.vue'), { boundaries: {
    '@/stores/app': { useAppStore: () => ({ bootstrap() {}, loadSkills: async () => {} }) },
    '@/components/AppSidebar.vue': { default: empty }, '@/components/AppTopbar.vue': { default: empty },
    '@/components/AppToast.vue': { default: empty }, '@/components/SkillLabModal.vue': { default: empty }
  }, globals })
  const host = renderer(), app = host.createApp(shell.default); app.use(pinia); app.use(loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n); app.component('router-view', RouterView); app.mount(host.root)
  const control = attr => find(host.root, n => n.props?.[attr] !== undefined)
  await settle(); await settle()
  const nav = find(host.root, n => n.tag === 'button' && text(n) === '2 Variables and graph')
  await nav.props.onClick(); await settle(); assert.equal(reviewInstances, 1)
  await control('data-review').props.onClick(); await settle()
  const count = calls.length
  route.value = 'other'; await settle(); assert.equal(otherMounts, 1)
  route.value = 'drafting'; await settle(); await settle()
  assert.equal(otherUnmounts, 1); assert.equal(reviewInstances, 1, 'return reuses the review instance')
  assert.equal(control('data-review').props['data-review'], 'active')
  assert.deepEqual(calls.slice(count).map(x => x[0]), ['automation'], 'resume only reads task progress, never re-reads graph, variables or source files')
  assert.ok(control('data-resume-status')); assert.equal(control('data-resume-reload').props.disabled, true, 'reload cannot discard unsaved review')
  assert.equal(control('data-start-automation').props.disabled, true, 'unsaved review survives route switch')

  route.value = 'other'; await settle(); store.switchProject('B'); await settle()
  route.value = 'drafting'; await settle(); await settle()
  assert.ok(calls.some(([name, id]) => name === 'catalog' && id === 'B'), 'switching projects while away loads the new project on return')
  assert.equal(control('data-resume-status'), undefined, 'a new project is loaded instead of restoring the previous project view')
  app.unmount()
  console.log('PASS: actual App caches only Drafting, preserves dirty review and loads a project switched while away')
})().catch(error => { console.error(error); process.exitCode = 1 })
