const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const piniaRuntime = require('pinia')
const routerRuntime = require('vue-router')
const { loadVue } = require('./helpers/load-vue.cjs')
const { text, find, settle, deferred, renderer } = require('./helpers/render-controls.cjs')
const copy = value => JSON.parse(JSON.stringify(value))
const project = id => ({ id, name: { zhHans: `TEST ${id}`, zhHant: `TEST ${id}`, en: `TEST ${id}` }, contractNo: `OLD-${id}`, packageRef: `package-${id}`, outputReferenceFile: 'output.docx', pages: '1–9', nttRange: '1–2', sctRange: '3–4', sccRange: '5–9', specification: 'TEST source specification', titleLines: [] })

async function harness() {
  const projects = [project('A'), project('B')], writes = [], removals = []
  let barrier, reloadFailure = false
  const loaded = loadVue(path.join(__dirname, '../src/components/AppTopbar.vue'), { boundaries: {
    '@/api': { projectApi: { list: async () => { if (reloadFailure) { reloadFailure = false; throw Error('TEST ONLY project reload failed') }; return copy(projects) }, save: async body => { writes.push(copy(body)); if (barrier) await barrier.promise; const index = projects.findIndex(item => item.id === body.id); projects[index] = { ...projects[index], name: { zhHans: body.nameZhHans, zhHant: body.nameZhHant, en: body.nameEn }, contractNo: body.contractNo }; return copy(projects[index]) }, remove: async id => { removals.push(id); projects.splice(projects.findIndex(item => item.id === id), 1) } }, setLlmProfileResolver() {} },
    pinia: piniaRuntime, 'vue-i18n': require('vue-i18n'), 'vue-router': routerRuntime
  }, globals: { document: { documentElement: {} }, localStorage: { getItem: () => 'en', setItem() {} }, window: { setTimeout: callback => { const timer = setTimeout(callback, 1); timer.unref(); return timer }, clearTimeout } } })
  const pinia = piniaRuntime.createPinia(); piniaRuntime.setActivePinia(pinia)
  const store = loaded.loadLocal(path.join(__dirname, '../src/stores/app.ts')).useAppStore()
  store.changeLocale('en'); await store.loadProjects()
  const router = routerRuntime.createRouter({ history: routerRuntime.createMemoryHistory(), routes: [{ path: '/', name: 'drafting', component: { render() {} } }] })
  await router.push('/'); await router.isReady()
  const host = renderer(), app = host.createApp(loaded.default)
  app.use(pinia); app.use(router); app.use(loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n); app.mount(host.root); await settle()
  const button = title => find(host.root, node => node.tag === 'button' && (node.props.title === title || text(node).trim() === title))
  const input = label => find(host.root, node => node.tag === 'input' && node.props['aria-label'] === label)
  return { ...host, store, projects, writes, removals, button, input, pause() { barrier = deferred(); return barrier }, failReload() { reloadFailure = true }, dispose: () => app.unmount() }
}

test('the mounted project editor saves the contract number and preserves every other project setting', async () => {
  const h = await harness()
  try {
    await h.button('Rename project').props.onClick(); await settle()
    h.input('Project name').props['onUpdate:modelValue']('Updated TEST project')
    h.input('Contract no.').props['onUpdate:modelValue'](' NEW-A ')
    await h.button('Save').props.onClick(); await settle()
    assert.equal(h.writes.length, 1)
    assert.deepEqual(h.writes[0], { id: 'A', nameZhHans: 'Updated TEST project', nameZhHant: 'Updated TEST project', nameEn: 'Updated TEST project', contractNo: 'NEW-A', packageRef: 'package-A', outputReferenceFile: 'output.docx', pages: '1–9', nttRange: '1–2', sctRange: '3–4', sccRange: '5–9', specification: 'TEST source specification' })
    assert.equal(h.store.activeProject.contractNo, 'NEW-A')
    await h.button('Rename project').props.onClick(); await settle()
    h.input('Contract no.').props['onUpdate:modelValue'](' ')
    await h.button('Save').props.onClick(); await settle()
    assert.equal(h.writes[1].contractNo, null, 'An explicitly cleared contract number remains cleared')
  } finally { h.dispose() }
})

test('switching projects closes an old edit or delete modal and cannot mutate the newly selected project', async () => {
  const h = await harness()
  try {
    await h.button('Rename project').props.onClick(); await settle()
    const staleSave = h.button('Save').props.onClick
    await h.store.switchProject('B'); await settle(); await staleSave(); await settle()
    assert.equal(h.writes.length, 0)
    await h.button('Delete project').props.onClick(); await settle()
    const staleDelete = h.button('Delete').props.onClick
    await h.store.switchProject('A'); await settle(); await staleDelete(); await settle()
    assert.deepEqual(h.removals, [])
    await h.button('Rename project').props.onClick(); await settle()
    h.input('Contract no.').props['onUpdate:modelValue']('CAPTURED-A')
    const barrier = h.pause(), saving = h.button('Save').props.onClick(); await settle()
    await h.store.switchProject('B'); await settle(); barrier.resolve(); await saving; await settle()
    assert.equal(h.writes.length, 1); assert.equal(h.writes[0].id, 'A')
    assert.equal(h.projects[1].contractNo, 'OLD-B'); assert.equal(h.store.activeProjectId, 'B')
  } finally { h.dispose() }
})

test('a saved rename keeps its response and reports reload failure without retrying or changing another project', async () => {
  for (const switchWhileSaving of [false, true]) {
    const h = await harness()
    try {
      await h.button('Rename project').props.onClick(); await settle()
      h.input('Project name').props['onUpdate:modelValue']('Saved TEST project A')
      h.input('Contract no.').props['onUpdate:modelValue']('SAVED-A')
      const save = h.button('Save').props.onClick, barrier = h.pause(); h.failReload()
      const saving = save(); await settle()
      if (switchWhileSaving) { await h.store.switchProject('B'); await settle() }
      barrier.resolve(); await saving; await settle()
      const saved = h.store.projects.find(item => item.id === 'A')
      assert.equal(saved.name.en, 'Saved TEST project A'); assert.equal(saved.contractNo, 'SAVED-A')
      assert.equal(saved.packageRef, 'package-A'); assert.equal(saved.outputReferenceFile, 'output.docx'); assert.equal(saved.sccRange, '5–9'); assert.equal(saved.specification, 'TEST source specification')
      assert.equal(h.store.activeProjectId, switchWhileSaving ? 'B' : 'A')
      assert.equal(h.store.projects.find(item => item.id === 'B').contractNo, 'OLD-B')
      assert.match(h.store.toast.message, /Saved TEST project A.*saved.*reload.*Refresh/)
      assert.equal(find(h.root, node => node.props?.['aria-hidden'] === false), undefined, 'The completed editor closes even when only reloading failed')
      await save(); await settle(); assert.equal(h.writes.length, 1, 'A stale Save callback cannot repeat the committed write')
    } finally { h.dispose() }
  }
})

test('the real identity client sends only the frozen optimistic-concurrency snapshots to the narrow endpoint', async () => {
  const calls = []
  const expected = { expectedProjectIdentity: { nameZhHans: 'TEST', nameZhHant: 'TEST', nameEn: 'TEST', contractNo: null }, expectedAdoptedIdentity: { value: '{"number":"TEST-1","title":"TEST title"}' } }
  const loaded = loadVue(path.join(__dirname, '../src/api/index.ts'), { boundaries: { axios: { default: { create: () => ({ post: async (url, body) => { calls.push({ url, body: copy(body) }); return { data: { code: 0, data: project('A') } } } }) } } } })
  const result = await loaded.draftingApi.confirmIdentity('A', expected)
  assert.deepEqual(calls, [{ url: '/drafting/A/identity/confirm', body: expected }]); assert.equal(result.id, 'A')
})
