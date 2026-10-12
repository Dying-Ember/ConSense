// Compile and mount the real SFC. Only the HTTP, project context and time boundaries are controlled.
import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'
import { JSDOM } from 'jsdom'
import ts from 'typescript'

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' })
for (const key of ['window', 'document', 'Node', 'Element', 'HTMLElement', 'SVGElement', 'Event']) globalThis[key] = dom.window[key]
const vue = await import('vue')
const { parse, compileScript } = await import('vue/compiler-sfc')
const source = await readFile(new URL('../src/views/VettingView.vue', import.meta.url), 'utf8')
const compiled = compileScript(parse(source).descriptor, { id: 'vetting-progress-test', inlineTemplate: true })
const apiSource = await readFile(new URL('../src/api/index.ts', import.meta.url), 'utf8')

function load(source, dependencies) {
  const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, esModuleInterop: true } }).outputText
  const module = { exports: {} }
  new Function('require', 'module', 'exports', code)(name => {
    assert.ok(Object.hasOwn(dependencies, name), `declared boundary dependency ${name}`)
    return dependencies[name]
  }, module, module.exports)
  return module.exports
}

const active = (id = 'run-A', completedUnits = 1) => ({ id, status: 'RUNNING', phase: 'segments', completedUnits, totalUnits: 4, message: 'Reviewing', sourceVerification: 'NOT_CHECKED' })
const done = (id = 'run-A') => ({ ...active(id, 4), status: 'COMPLETED' })
const fullActive = (id = 'run-A') => { const { sourceVerification, ...value } = active(id); return value }
const detail = (id = 'run-A', stale = false) => ({ ...fullActive(id), status: 'COMPLETED', completedUnits: 4, sourceSetStale: stale, result: { total: 0, metrics: { total: 0 }, messages: [] } })
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
const settle = async () => { await new Promise(resolve => setImmediate(resolve)); await vue.nextTick() }

function fixture(overrides = {}) {
  const calls = [], timers = new Map(), notifications = []
  let timerId = 0
  const handlers = {
    latestProgress: async () => active(), progress: async () => active('run-A', 3),
    latest: async () => fullActive(), full: async () => detail(), start: async () => fullActive(),
    ...overrides,
  }
  const client = {
    get: async path => {
      calls.push({ method: 'GET', path })
      if (path.endsWith('/runs/latest/progress')) return handlers.latestProgress(path)
      if (path.endsWith('/progress')) return handlers.progress(path)
      if (path.endsWith('/runs/latest')) return handlers.latest(path)
      if (/\/runs\//.test(path)) return handlers.full(path)
      if (path.endsWith('/files')) return [{ key: 'SCC', parsed: true }]
      if (path.endsWith('/metrics')) return { total: 0, reference: 0, conflict: 0, language: 0, risk: 0, crossFile: 0 }
      if (path.includes('/findings')) return []
      throw new Error(`Unexpected GET ${path}`)
    },
    post: async path => { calls.push({ method: 'POST', path }); return handlers.start(path) },
  }
  const { vettingApi } = load(apiSource, { './client': { api: client }, './types': {} })
  const store = vue.reactive({ activeProjectId: 'A', locale: 'en', notify: message => notifications.push(message), captureLlmSelection: () => ({}), setBusy() {}, clearBusy() {} })
  const placeholder = { __esModule: true, default: vue.defineComponent({ setup: () => () => null }) }
  const component = load(compiled.content, {
    vue, 'vue-i18n': { useI18n: () => ({ t: key => key }) }, 'vue-router': { useRouter: () => ({ push: async () => undefined }) },
    '@/api': { vettingApi }, '@/stores/app': { useAppStore: () => store },
    '@/api/client': { ApiError: class ApiError extends Error {} },
    '@/composables/useLocalized': { useLocalized: () => ({ pick: value => typeof value === 'string' ? value : value?.en ?? '', currentKey: vue.ref('en') }) },
    '@/components/AppIcon.vue': placeholder, '@/components/AppModal.vue': placeholder, '@/components/CanonicalEvidencePanel.vue': placeholder,
    '@/components/FixedBaselineEvidencePanel.vue': placeholder,
    '@/advice/reviewHandoff': { reviewHandoffCopy: {} },
  }).default
  window.setTimeout = (fn, delay) => { const id = ++timerId; timers.set(id, { fn, delay }); return id }
  window.clearTimeout = id => timers.delete(id)
  const container = document.createElement('div')
  document.body.appendChild(container)
  const app = vue.createApp(component)
  app.mount(container)
  return {
    calls, store, timers, notifications, handlers, container,
    mount: settle,
    fullCalls: () => calls.filter(call => call.method === 'GET' && /\/runs\//.test(call.path) && !call.path.endsWith('/progress')),
    fire: async () => {
      const entry = [...timers.entries()].find(([, timer]) => timer.delay === 2000)
      assert.ok(entry, 'active task has a progress timer')
      timers.delete(entry[0]); entry[1].fn(); await settle()
    },
    button: label => [...container.querySelectorAll('button')].find(button => button.textContent.trim() === label),
    dispose: () => { app.unmount(); container.remove() },
  }
}

test('running recovery and progress updates avoid full source-detail reads', async () => {
  const h = fixture()
  try {
    await h.mount()
    assert.equal(h.fullCalls().length, 0, 'restoring an active job reads lightweight progress only')
    await h.fire()
    assert.match(h.container.textContent, /3 \/ 4/)
    assert.equal(h.fullCalls().length, 0, 'ordinary progress polling never calls the full source verifier')
    assert.equal(h.button('vetting.actions.export').disabled, true)
  } finally { h.dispose() }
})

test('terminal progress waits for one full detail and does not enable reports prematurely', async () => {
  const pending = deferred()
  const h = fixture({ progress: async () => done(), full: () => pending.promise })
  try {
    await h.mount(); await h.fire()
    assert.equal(h.fullCalls().length, 1)
    assert.equal(h.button('vetting.actions.export').disabled, true)
    assert.doesNotMatch(h.container.textContent, /vetting.job.executionCompleteNote/)
    assert.equal(h.timers.size, 0, 'terminal detail is not fetched again on a two-second timer')
    pending.resolve(detail()); await settle()
    assert.equal(h.fullCalls().length, 1)
    assert.equal(h.button('vetting.actions.export').disabled, false)
    assert.match(h.container.textContent, /vetting.job.executionCompleteNote/)
  } finally { h.dispose() }
})

test('restoring a terminal latest progress verifies full detail once and preserves stale warnings', async () => {
  const h = fixture({ latestProgress: async () => done(), full: async () => detail('run-A', true) })
  try {
    await h.mount()
    assert.equal(h.fullCalls().length, 1)
    assert.match(h.container.textContent, /vetting.sourceSetStale/)
    assert.equal(h.button('vetting.actions.export').disabled, true)
    assert.equal(h.timers.size, 0)
  } finally { h.dispose() }
})

test('failed fresh detail pauses on the same handle and retries without another start POST', async () => {
  const h = fixture({ progress: async () => done(), full: async () => { throw new Error('source freshness could not be checked') } })
  try {
    await h.mount(); await h.fire()
    assert.equal(h.fullCalls().length, 1)
    assert.doesNotMatch(h.container.textContent, /vetting.job.executionCompleteNote/)
    assert.equal(h.button('vetting.actions.export').disabled, true)
    assert.ok(h.button('vetting.job.resume'))
    assert.equal(h.timers.size, 0)
    h.handlers.full = async () => detail()
    h.button('vetting.job.resume').click(); await settle()
    assert.equal(h.fullCalls().length, 2, 'explicit reconnect may retry the one failed detail read')
    assert.equal(h.calls.filter(call => call.method === 'POST').length, 0)
    assert.ok(h.calls.filter(call => call.path.endsWith('/runs/run-A/progress')).length >= 2, 'the original job handle is reused')
    assert.equal(h.button('vetting.actions.export').disabled, false)
  } finally { h.dispose() }
})

test('late terminal detail cannot refill the view after switching project', async () => {
  const pending = deferred()
  const h = fixture({ progress: async () => done(), full: () => pending.promise })
  try {
    await h.mount(); await h.fire()
    h.handlers.latestProgress = async () => ({ ...active('run-B', 2), message: 'Current B worker' })
    h.store.activeProjectId = 'B'; await settle()
    pending.resolve({ ...detail(), message: 'Old A terminal detail' }); await settle()
    assert.match(h.container.textContent, /Current B worker/)
    assert.doesNotMatch(h.container.textContent, /Old A terminal detail|vetting.job.executionCompleteNote/)
    assert.equal(h.button('vetting.actions.export').disabled, true)
  } finally { h.dispose() }
})

test('late progress cannot refill a later project or start a terminal detail read', async () => {
  const pending = deferred()
  const h = fixture({ progress: () => pending.promise })
  try {
    await h.mount(); await h.fire()
    h.handlers.latestProgress = async () => ({ ...active('run-B', 2), message: 'Current B progress' })
    h.store.activeProjectId = 'B'; await settle()
    pending.resolve({ ...done(), message: 'Old A progress' }); await settle()
    assert.match(h.container.textContent, /Current B progress/)
    assert.doesNotMatch(h.container.textContent, /Old A progress/)
    assert.equal(h.fullCalls().length, 0)
  } finally { h.dispose() }
})

test('a newly started task owns the view over a late latest-progress restoration', async () => {
  const pending = deferred()
  const h = fixture({ latestProgress: () => pending.promise, start: async () => ({ ...fullActive('new-run'), message: 'New task worker' }) })
  try {
    await h.mount()
    h.button('vetting.actions.run').click(); await settle()
    pending.resolve({ ...done('old-run'), message: 'Old restore' }); await settle()
    assert.match(h.container.textContent, /New task worker/)
    assert.doesNotMatch(h.container.textContent, /Old restore/)
    assert.equal(h.calls.filter(call => call.method === 'POST').length, 1)
    assert.equal(h.fullCalls().length, 0)
  } finally { h.dispose() }
})

test('unmount makes a late terminal response inert and leaves no poll timer', async () => {
  const pending = deferred()
  const h = fixture({ progress: async () => done(), full: () => pending.promise })
  await h.mount(); await h.fire(); h.dispose()
  pending.resolve(detail()); await settle()
  assert.equal(h.timers.size, 0)
  assert.equal(h.notifications.length, 0)
  assert.equal(h.calls.filter(call => call.path.endsWith('/metrics')).length, 1, 'no completion reload after unmount')
})

test('progress clears previous results and a previous fresh false, while preserving a true stale warning', async () => {
  for (const stale of [false, true]) {
    const h = fixture({ latestProgress: async () => null, start: async () => ({ ...fullActive(), sourceSetStale: stale,
      coverage: { totalDocuments: 1, reviewedDocuments: 1, documents: [], warnings: [] }, result: { total: 1, metrics: {}, messages: [] } }) })
    try {
      await h.mount(); h.button('vetting.actions.run').click(); await settle()
      assert.match(h.container.textContent, /vetting.coverage.documents/)
      await h.fire()
      assert.doesNotMatch(h.container.textContent, /vetting.coverage.documents/)
      assert.equal(h.button('vetting.actions.export').disabled, true)
      assert.equal(h.container.textContent.includes('vetting.sourceSetStale'), stale)
    } finally { h.dispose() }
  }
})

test('an empty latest progress tolerates null or an omitted envelope data field', async () => {
  for (const empty of [null, undefined]) {
    const h = fixture({ latestProgress: async () => empty })
    try {
      await h.mount()
      assert.equal(h.fullCalls().length, 0)
      assert.equal(h.timers.size, 0)
      assert.equal(h.button('vetting.actions.export').disabled, true)
      assert.equal(h.button('vetting.job.resume'), undefined)
    } finally { h.dispose() }
  }
})
