const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const pinia = require('pinia')
const vue = require('vue')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')

const profileKey = 'consense-llm-profile'
const storeSource = process.env.CONSENSE_APP_STORE_UNDER_TEST || path.join(__dirname, '../src/stores/app.ts')
const metadata = () => ({ defaultProfile: 'local', profiles: ['local', 'minimax-cn', 'qwen', 'qwen-flash'].map(id => ({ id, configured: true, model: id === 'qwen' ? 'qwen3.8-27b' : id })) })

// Real store/Pinia/reactivity; only storage and service boundaries are simulated.
// No DOM, browser, HTTP client, provider or credential is loaded by this suite.
function harness({ stored = new Map(), profiles = metadata(), storage, missingStorage = false } = {}) {
  const writes = [], calls = []
  let selection, fail = false, current = profiles
  const browserStorage = storage || { getItem: key => stored.get(key) ?? null, setItem: (key, value) => { writes.push([key, value]); stored.set(key, value) } }
  const api = {
    captureLlmSelection: () => ({ profileId: selection?.() ?? null }),
    setLlmProfileResolver: resolver => { selection = resolver },
    setApiErrorReporter() {},
    projectApi: { list: async () => { calls.push('projects'); return [] } },
    skillApi: { list: async () => { calls.push('skills'); return [] } },
    systemApi: {
      health: async () => { calls.push('health'); return { ready: false } },
      llmProfiles: async () => { calls.push('profiles'); if (fail) throw Error('TEST metadata unavailable'); return current }
    }
  }
  const loaded = loadTypeScript(storeSource, {
    dependencies: { pinia, vue, '@/api': api, '@/i18n': { currentLocale: () => 'en', setLocale() {} } },
    globals: missingStorage ? {} : { localStorage: browserStorage }
  })
  pinia.setActivePinia(pinia.createPinia())
  return { store: loaded.useAppStore(), stored, writes, calls, setProfiles(value) { current = value }, failMetadata(value) { fail = value } }
}

test('a cold store selects Qwen 27B before metadata and retains it when backend metadata defaults local', async () => {
  const h = harness()
  assert.equal(h.store.llmProfileId, 'qwen')
  assert.equal(h.store.captureLlmSelection().profileId, 'qwen')
  await h.store.loadLlmProfiles()
  assert.equal(h.store.llmProfileId, 'qwen')
  assert.equal(h.store.selectedLlmProfile.model, 'qwen3.8-27b')
  assert.equal(h.store.llmProfiles.defaultProfile, 'local', 'The backend metadata contract is unchanged')
  assert.deepEqual(h.writes, [], 'Loading a UI default does not pretend it was a saved explicit choice')
})

test('every valid saved profile remains selected even when that profile is unconfigured', async () => {
  for (const saved of ['local', 'minimax-cn', 'qwen', 'qwen-flash']) {
    const profiles = metadata(); profiles.profiles.find(item => item.id === saved).configured = false
    profiles.profiles.find(item => item.id === saved).unavailableReason = 'missing_api_key'
    const h = harness({ stored: new Map([[profileKey, saved]]), profiles })
    assert.equal(h.store.llmProfileId, saved)
    await h.store.loadLlmProfiles()
    assert.equal(h.store.llmProfileId, saved)
    assert.equal(h.store.selectedLlmProfile.configured, false)
    assert.equal(h.store.selectedLlmProfile.unavailableReason, 'missing_api_key')
    assert.equal(h.store.captureLlmSelection().profileId, saved)
    assert.deepEqual(h.writes, [])
  }
})

test('absent or invalid persisted IDs use the UI Qwen default without persisting an invented preference', async () => {
  for (const saved of [undefined, '', 'retired-provider', 'https://untrusted.invalid']) {
    const stored = saved === undefined ? new Map() : new Map([[profileKey, saved]])
    const h = harness({ stored })
    await h.store.loadLlmProfiles()
    assert.equal(h.store.llmProfileId, 'qwen')
    assert.deepEqual(h.writes, [])
    assert.equal(stored.get(profileKey), saved)
  }
})

test('unconfigured or absent Qwen metadata never silently switches a cold store to another provider', async () => {
  const unconfigured = metadata(); unconfigured.profiles.find(item => item.id === 'qwen').configured = false
  unconfigured.profiles.find(item => item.id === 'qwen').unavailableReason = 'missing_api_key'
  const missing = metadata(); missing.profiles = missing.profiles.filter(item => item.id !== 'qwen')
  for (const profiles of [unconfigured, missing]) {
    const h = harness({ profiles }); await h.store.loadLlmProfiles()
    assert.equal(h.store.llmProfileId, 'qwen')
    assert.equal(h.store.selectedLlmProfile?.configured ?? false, false)
    assert.equal(h.store.captureLlmSelection().profileId, 'qwen')
    assert.deepEqual(h.writes, [])
  }
})

test('an explicit saved profile absent from returned metadata is retained as unavailable', async () => {
  for (const saved of ['local', 'minimax-cn', 'qwen-flash']) {
    const profiles = metadata(); profiles.profiles = profiles.profiles.filter(item => item.id !== saved)
    const h = harness({ stored: new Map([[profileKey, saved]]), profiles })
    await h.store.loadLlmProfiles()
    assert.equal(h.store.llmProfileId, saved)
    assert.equal(h.store.selectedLlmProfile, null)
    assert.deepEqual(h.writes, [])
  }
})

test('metadata failure and retry preserve the selected profile and report loading failure honestly', async () => {
  for (const saved of [undefined, 'local', 'minimax-cn', 'qwen-flash']) {
    const h = harness({ stored: saved ? new Map([[profileKey, saved]]) : new Map() })
    h.failMetadata(true); await h.store.loadLlmProfiles()
    assert.equal(h.store.llmProfileId, saved || 'qwen')
    assert.equal(h.store.llmProfilesFailed, true)
    assert.equal(h.store.llmProfilesLoading, false)
    h.failMetadata(false); await h.store.loadLlmProfiles()
    assert.equal(h.store.llmProfileId, saved || 'qwen')
    assert.equal(h.store.llmProfilesFailed, false)
    assert.deepEqual(h.writes, [])
  }
})

test('only an explicit configured choice changes and persists the model preference across reload', async () => {
  const h = harness(); await h.store.loadLlmProfiles()
  for (const choice of ['local', 'minimax-cn', 'qwen-flash', 'qwen']) {
    h.store.changeLlmProfile(choice)
    assert.equal(h.store.llmProfileId, choice)
    assert.equal(h.stored.get(profileKey), choice)
    const reloaded = harness({ stored: h.stored }); await reloaded.store.loadLlmProfiles()
    assert.equal(reloaded.store.llmProfileId, choice)
  }
  assert.deepEqual(h.writes, ['local', 'minimax-cn', 'qwen-flash', 'qwen'].map(choice => [profileKey, choice]))
})

test('invalid and unconfigured change attempts cannot replace a valid current profile or stored preference', async () => {
  const profiles = metadata(); profiles.profiles.find(item => item.id === 'minimax-cn').configured = false
  const h = harness({ stored: new Map([[profileKey, 'local']]), profiles }); await h.store.loadLlmProfiles()
  for (const choice of ['minimax-cn', 'invalid', '']) h.store.changeLlmProfile(choice)
  assert.equal(h.store.llmProfileId, 'local')
  assert.equal(h.stored.get(profileKey), 'local')
  assert.deepEqual(h.writes, [])
})

test('blocked or absent storage retains the cold default and a later explicit session selection', async () => {
  const denied = { getItem() { throw Error('TEST denied') }, setItem() { throw Error('TEST denied') } }
  for (const options of [{ storage: denied }, { missingStorage: true }]) {
    const h = harness(options)
    assert.equal(h.store.llmProfileId, 'qwen')
    await h.store.loadLlmProfiles(); h.store.changeLlmProfile('local'); await h.store.loadLlmProfiles()
    assert.equal(h.store.llmProfileId, 'local')
  }
})

test('bootstrap preserves the Qwen UI selection without executing a model operation', async () => {
  const h = harness(); await h.store.bootstrap()
  assert.equal(h.store.llmProfileId, 'qwen')
  assert.equal(h.store.captureLlmSelection().profileId, 'qwen')
  assert.deepEqual(h.calls.sort(), ['health', 'profiles', 'projects', 'skills'])
  assert.equal(h.writes.some(([key]) => key === profileKey), false)
})
