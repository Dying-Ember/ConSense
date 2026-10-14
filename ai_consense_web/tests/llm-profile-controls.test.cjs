const assert = require('node:assert/strict')
const path = require('node:path')
const piniaRuntime = require('pinia')
const routerRuntime = require('vue-router')
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { text, find, settle, deferred, renderer } = require('./helpers/render-controls.cjs')
// Real native-reading dependencies are available even when a provider-control
// scenario has no generated document. The HTTP boundary remains simulated.
const nativeWindow = new (require('jsdom').JSDOM)('').window

const profiles = { defaultProfile: 'local', profiles: [
  { id: 'local', provider: 'ollama', model: 'qwen2.5:7b-instruct-q4_K_M', configured: true },
  { id: 'minimax-cn', provider: 'openai-compatible', model: 'MiniMax-M3', configured: false, unavailableReason: 'missing_api_key' }
] }

async function harness({ stored = new Map(), metadata = profiles, metadataError = false, view, respond } = {}) {
  const requests = []
  const transport = (method, url, body, config) => {
    requests.push({ method, url, body, config })
    if (metadataError && url === '/system/llm-profiles') return Promise.reject(new Error('Metadata unavailable'))
    const data = url === '/system/llm-profiles' ? metadata : url === '/system/health' ? { ready: false } : respond ? respond({ method, url, body, config }) : []
    return Promise.resolve(data).then(data => ({ data: { code: 0, data } }))
  }
  const axios = { create: () => ({ get: (url, config) => transport('get', url, undefined, config), post: (url, body, config) => transport('post', url, body, config), put: (url, body, config) => transport('put', url, body, config), delete: (url, config) => transport('delete', url, undefined, config) }), isAxiosError: () => false }
  const loaded = loadVue(path.join(__dirname, '../src/components/AppTopbar.vue'), { boundaries: {
    axios: { default: axios }, pinia: piniaRuntime, 'vue-i18n': require('vue-i18n'), 'vue-router': routerRuntime, '@noble/hashes/sha256': require('@noble/hashes/sha256'),
    'pdfjs-dist': { GlobalWorkerOptions: {}, getDocument() { throw new Error('PDF renderer is outside the source-selection test') } },
    'pdfjs-dist/build/pdf.worker.min.mjs?url': { default: 'external-pdf-worker' },
    jszip: { default: require('jszip') }, 'mammoth/mammoth.browser.min.js': { default: require('mammoth/mammoth.browser.min.js') }, dompurify: { default: require('dompurify')(nativeWindow) }
  }, globals: { crypto: require('node:crypto').webcrypto, DOMParser: nativeWindow.DOMParser, XMLSerializer: nativeWindow.XMLSerializer, NodeFilter: nativeWindow.NodeFilter, Element: nativeWindow.Element, HTMLElement: nativeWindow.HTMLElement, Node: nativeWindow.Node, localStorage: { getItem: key => stored.get(key) ?? null, setItem: (key, value) => stored.set(key, value) }, window: { setTimeout: (callback, ms) => { const timer = setTimeout(callback, ms); timer.unref(); return timer }, clearTimeout, addEventListener() {}, removeEventListener() {} }, document: nativeWindow.document } })
  const pinia = piniaRuntime.createPinia(); piniaRuntime.setActivePinia(pinia)
  const store = loaded.loadLocal(path.join(__dirname, '../src/stores/app.ts')).useAppStore()
  store.changeLocale('en')
  const router = routerRuntime.createRouter({ history: routerRuntime.createMemoryHistory(), routes: [{ path: '/', name: 'drafting', component: { render() {} } }] })
  await router.push('/'); await router.isReady()
  const content = view ? loaded.loadLocal(path.join(__dirname, `../src/views/${view}.vue`)).default : null
  const rendered = renderer(); const app = rendered.createApp(content ? { render: () => vue.h('main', [vue.h(loaded.default), vue.h(content)]) } : loaded.default)
  app.use(pinia); app.use(router); app.use(loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n); app.mount(rendered.root)
  await store.bootstrap(); await settle()
  return { ...rendered, store, stored, requests, api: loaded.loadLocal(path.join(__dirname, '../src/api/index.ts')), dispose: () => app.unmount() }
}

;(async () => {
  const h = await harness()
  const select = find(h.root, node => node.tag === 'select' && node.props['aria-label'] === 'LLM source')
  assert.ok(select, 'The common topbar exposes a labelled LLM source control')
  assert.equal(select.props.value, 'local')
  const remote = find(select, node => node.tag === 'option' && node.props.value === 'minimax-cn')
  assert.equal(remote.props.disabled, true)
  assert.match(text(h.root), /qwen2\.5:7b-instruct-q4_K_M/)
  assert.match(text(h.root), /Configuration ready/)
  assert.match(text(h.root), /Changes apply to new operations/)
  assert.equal(h.requests.some(request => request.method !== 'get'), false)
  h.dispose()
  console.log('PASS: actual common topbar shows selected configured model, truthful readiness, disabled missing-key choice and new-operation hint using real Pinia/i18n/API modules')
  const available = { ...profiles, profiles: profiles.profiles.map(profile => ({ ...profile, configured: true })) }
  const switched = await harness({ metadata: available })
  await find(switched.root, node => node.tag === 'select' && node.props['aria-label'] === 'LLM source').props.onChange({ target: { value: 'minimax-cn' } }); await settle()
  assert.match(text(switched.root), /Model: MiniMax-M3/)
  assert.equal(switched.stored.get('consense-llm-profile'), 'minimax-cn')
  await switched.store.switchProject('another-project'); await settle()
  assert.match(text(switched.root), /Model: MiniMax-M3/)
  assert.equal(switched.requests.some(request => request.method !== 'get'), false)
  const stored = switched.stored; switched.dispose()
  const reloaded = await harness({ stored })
  assert.equal(find(reloaded.root, node => node.tag === 'select' && node.props['aria-label'] === 'LLM source').props.value, 'minimax-cn')
  assert.match(text(reloaded.root), /API key not configured/)
  assert.doesNotMatch(text(reloaded.root), /Configuration ready/)
  for (const [locale, label, hint, missing] of [
    ['zh-Hans', '模型来源', '切换仅影响新任务', '未配置 API 密钥'],
    ['zh-Hant', '模型來源', '切換僅影響新任務', '未配置 API 金鑰'],
    ['en', 'LLM source', 'Changes apply to new operations', 'API key not configured']
  ]) {
    reloaded.store.changeLocale(locale); await settle()
    assert.ok(find(reloaded.root, node => node.tag === 'select' && node.props['aria-label'] === label))
    assert.ok(text(reloaded.root).includes(hint)); assert.ok(text(reloaded.root).includes(missing)); assert.match(text(reloaded.root), /MiniMax-M3/)
  }
  reloaded.dispose()
  const invalid = await harness({ stored: new Map([['consense-llm-profile', 'attacker-supplied-endpoint']]) })
  assert.equal(find(invalid.root, node => node.tag === 'select' && node.props['aria-label'] === 'LLM source').props.value, 'local')
  invalid.dispose()
  console.log('PASS: rendered selection persists only browser profile ID; unavailable persisted choice remains visible; invalid IDs use server default; all three locales preserve configured model identity')
  const outbound = await harness({ metadata: available })
  const started = outbound.api.draftingApi.extract('project-A')
  await find(outbound.root, node => node.tag === 'select' && node.props['aria-label'] === 'LLM source').props.onChange({ target: { value: 'minimax-cn' } }); await settle(); await started
  await outbound.api.adviceApi.ask('project-A', 'Question', 'fullset')
  const first = outbound.requests.find(request => request.url === '/drafting/project-A/variables/extract')
  const next = outbound.requests.find(request => request.url === '/advice/project-A/ask')
  assert.equal(first.config?.headers?.['X-ConSense-Llm-Profile'], 'local')
  assert.equal(first.config?.timeout, 7200000, 'A full local reasoning extraction must stay alive for two hours')
  assert.equal(next.config?.headers?.['X-ConSense-Llm-Profile'], 'minimax-cn')
  assert.equal(next.config?.timeout, undefined, 'Other operations must retain the normal client timeout')
  assert.equal(Object.keys(next.config.headers).some(key => /authorization|api.key/i.test(key)), false)
  await outbound.api.draftingApi.extract('legacy-operation', { profileId: null })
  assert.equal(outbound.requests.find(request => request.url === '/drafting/legacy-operation/variables/extract').config.headers['X-ConSense-Llm-Profile'], undefined)
  outbound.dispose()
  console.log('PASS: actual client snapshots selected profile in request headers; later selection affects only subsequent requests and sends no key')
  const save = deferred()
  const field = { key: 'contractTitle', kind: 'text', label: { en: 'Contract title', zhHans: '合约名称', zhHant: '合約名稱' } }
  const original = { key: 'contractTitle', value: 'Original title', confirmed: true, manuallyEdited: true, candidates: [] }
  const drafting = await harness({ metadata: available, view: 'DraftingView', respond: request => {
    if (request.url === '/projects') return [{ id: 'A', name: field.label }]
    if (request.method === 'put') return save.promise
    if (request.url.endsWith('/catalog')) return { ruleVersion: 'unchanged', groups: [{ key: 'contract', label: field.label, fields: [field] }] }
    if (request.url.endsWith('/variables')) return [original]
    if (request.url.endsWith('/inputs')) return [{ id: 1, fileName: 'source.docx', status: 'PARSED' }]
    if (request.url.endsWith('/plan')) return { actions: [], unresolved: [] }
    return []
  } })
  await find(drafting.root, node => node.tag === 'button' && text(node).trim() === 'Enter inputs manually').props.onClick(); await settle()
  find(drafting.root, node => node.tag === 'input' && node.props.id === 'input-contractTitle').props.onInput({ target: { value: 'User edit' } }); await settle()
  const extraction = find(drafting.root, node => node.tag === 'button' && text(node).trim() === 'Identify inputs').props.onClick(); await settle()
  assert.ok(drafting.requests.some(request => request.method === 'put'))
  assert.equal(drafting.requests.some(request => request.url.endsWith('/variables/extract')), false)
  await find(drafting.root, node => node.tag === 'select' && node.props['aria-label'] === 'LLM source').props.onChange({ target: { value: 'minimax-cn' } }); await settle()
  save.resolve({ ...original, value: 'User edit' }); await extraction; await settle()
  assert.equal(drafting.requests.find(request => request.url.endsWith('/variables/extract')).config.headers['X-ConSense-Llm-Profile'], 'local')
  assert.match(text(drafting.root), /MiniMax-M3/)
  drafting.dispose()
  console.log('PASS: rendered Drafting extraction retains click-time local selection while a manual save awaits and the topbar changes to MiniMax')
  const plan = deferred(); let waitForPlan = false
  const generation = await harness({ metadata: available, view: 'DraftingView', respond: request => {
    if (request.url === '/projects') return [{ id: 'G', name: field.label }]
    if (request.url.endsWith('/catalog')) return { ruleVersion: 'unchanged', groups: [{ key: 'contract', label: field.label, fields: [field] }] }
    if (request.url.endsWith('/variables')) return [original]
    if (request.url.endsWith('/templates')) return ['NTT', 'SCT', 'SCC'].map(key => ({ key, fileName: `${key}.docx`, tag: 'ok' }))
    if (request.url.endsWith('/plan')) return waitForPlan ? plan.promise : { actions: [], unresolved: [] }
    return []
  } })
  await find(generation.root, node => node.tag === 'button' && text(node).trim() === 'Enter inputs manually').props.onClick(); await settle()
  waitForPlan = true
  const generating = find(generation.root, node => node.tag === 'button' && text(node).trim() === 'Generate English drafts').props.onClick(); await settle()
  await find(generation.root, node => node.tag === 'select' && node.props['aria-label'] === 'LLM source').props.onChange({ target: { value: 'minimax-cn' } }); await settle()
  plan.resolve({ actions: [], unresolved: [] }); await generating; await settle()
  const generated = generation.requests.find(request => request.url.includes('/generate?'))
  assert.equal(generated.config.headers['X-ConSense-Llm-Profile'], 'local')
  assert.equal(generated.config.timeout, 3600000)
  generation.dispose()
  console.log('PASS: rendered generation retains operation-entry profile across plan await and preserves full generation timeout')
  const advice = await harness({ metadata: available, view: 'AdviceView', respond: request => {
    if (request.url === '/projects') return [{ id: 'Q', name: field.label }]
    if (request.url.includes('/index/status?')) return { chunks: 0 }
    if (request.url.endsWith('/ask')) return { grounded: true, title: field.label, content: { en: 'Answer', zhHans: 'Answer', zhHant: 'Answer' }, citations: [], sources: [], modelIdentity: { profileId: 'local', provider: 'ollama', model: 'captured-local-model', configurationSha256: 'a'.repeat(64), identityScope: 'configured_profile' } }
    return []
  } })
  find(advice.root, node => node.tag === 'textarea').props['onUpdate:modelValue']('A question'); await settle()
  const asking = find(advice.root, node => node.tag === 'button' && text(node).trim() === 'Ask').props.onClick()
  find(advice.root, node => node.tag === 'select' && node.props['aria-label'] === 'LLM source').props.onChange({ target: { value: 'minimax-cn' } })
  await asking; await settle()
  assert.equal(advice.requests.find(request => request.url.endsWith('/ask')).config.headers['X-ConSense-Llm-Profile'], 'local')
  assert.match(text(advice.root), /Answer/)
  assert.match(text(advice.root), /Recorded model: captured-local-model/)
  assert.match(text(advice.root), /Configured profile; provider execution is not independently attested/)
  advice.dispose()
  console.log('PASS: rendered Advice keeps click-time source across its pre-request scroll await')
  const queued = deferred()
  const vetting = await harness({ metadata: available, view: 'VettingView', respond: request => {
    if (request.url === '/projects') return [{ id: 'V', name: field.label }]
    if (request.method === 'post' && request.url.includes('/runs?')) return queued.promise
    if (request.url.endsWith('/runs/latest') || request.url.endsWith('/metrics')) return null
    if (request.url.endsWith('/files')) return [{ key: 'NTT', fileName: 'source.docx', parsed: true, label: field.label }]
    return []
  } })
  const reviewing = find(vetting.root, node => node.tag === 'button' && text(node).trim() === 'Run vetting').props.onClick()
  find(vetting.root, node => node.tag === 'select' && node.props['aria-label'] === 'LLM source').props.onChange({ target: { value: 'minimax-cn' } })
  queued.resolve({ id: 'saved-local-job', status: 'QUEUED', phase: 'queued', completedUnits: 0, totalUnits: 1, message: 'Queued', modelIdentity: { profileId: 'local', provider: 'ollama', model: 'captured-local-job-model', configurationSha256: 'b'.repeat(64), identityScope: 'configured_profile' } })
  await reviewing; await settle()
  assert.equal(vetting.requests.find(request => request.url.includes('/runs?')).config.headers['X-ConSense-Llm-Profile'], 'local')
  assert.match(text(vetting.root), /Recorded model: captured-local-job-model/)
  assert.match(text(vetting.root), /Model: MiniMax-M3/)
  vetting.dispose()
  console.log('PASS: rendered Vetting submits its entry source and displays queued saved identity independently of the next-operation selector')
  const trace = { model: 'captured-legacy-label', finishedAt: '2026-10-06', systemPrompt: 'saved system', userPrompt: 'saved source', rawResponses: [], status: 'completed', runId: 'saved-trace', modelIdentity: { profileId: 'local', provider: 'ollama', model: 'configured-local-trace', configurationSha256: 'c'.repeat(64), identityScope: 'configured_profile' } }
  const traced = await harness({ metadata: available, view: 'DraftingView', stored: new Map([['consense-llm-profile', 'minimax-cn']]), respond: request => {
    if (request.url === '/projects') return [{ id: 'T', name: field.label }]
    if (request.url.endsWith('/catalog')) return { ruleVersion: 'unchanged', groups: [{ key: 'contract', label: field.label, fields: [field] }] }
    if (request.url.endsWith('/plan')) return { actions: [], unresolved: [] }
    if (request.url.endsWith('/extract-trace')) return trace
    return []
  } })
  await find(traced.root, node => node.tag === 'button' && text(node).trim() === 'Extraction trace').props.onClick(); await settle()
  assert.match(text(traced.root), /Recorded model: configured-local-trace/)
  assert.match(text(traced.root), /local · ollama/)
  assert.match(text(traced.root), /Model: MiniMax-M3/)
  for (const [locale, label] of [['zh-Hans', '记录的模型'], ['zh-Hant', '記錄的模型'], ['en', 'Recorded model']]) {
    traced.store.changeLocale(locale); await settle()
    assert.ok(text(traced.root).includes(`${label}: configured-local-trace`))
  }
  delete trace.modelIdentity
  await traced.store.switchProject('T2'); await settle()
  await find(traced.root, node => node.tag === 'button' && text(node).trim() === 'Extraction trace').props.onClick(); await settle()
  const legacyReport = find(traced.root, node => node.tag === 'section' && node.props.class === 'extraction-report')
  assert.match(text(legacyReport), /captured-legacy-label/)
  assert.match(text(legacyReport), /No saved profile identity/)
  assert.doesNotMatch(text(legacyReport), /MiniMax-M3|minimax-cn/)
  traced.dispose()
  console.log('PASS: historical Drafting report displays its own saved configured profile independently of current MiniMax selection')
  const unavailable = await harness({ metadataError: true, stored: new Map([['consense-llm-profile', 'minimax-cn']]) })
  const unavailableSelect = find(unavailable.root, node => node.tag === 'select' && node.props['aria-label'] === 'LLM source')
  assert.equal(unavailableSelect.props.disabled, true)
  assert.ok(find(unavailableSelect, node => node.tag === 'option' && node.props.value === 'minimax-cn'), 'Failed metadata read retains the visible browser choice')
  assert.match(text(unavailable.root), /Model configuration could not be loaded/)
  assert.doesNotMatch(text(unavailable.root), /Configuration ready/)
  await unavailable.api.draftingApi.documents('safe-read')
  assert.ok(unavailable.requests.some(request => request.url === '/drafting/safe-read/documents'))
  unavailable.dispose()
  console.log('PASS: unavailable metadata remains honest, retains the visible browser choice and does not block deterministic/history reads')
})().catch(error => { console.error(error); process.exitCode = 1 })
