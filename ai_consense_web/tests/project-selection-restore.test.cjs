const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const pinia = require('pinia')
const { loadVue } = require('./helpers/load-vue.cjs')

const selectionKey = 'consense-active-project-id'
const copy = value => JSON.parse(JSON.stringify(value))
const project = id => ({ id, name: { zhHans: `Server ${id}`, zhHant: `Server ${id}`, en: `Server ${id}` }, contractNo: `SERVER-${id}` })

function harness({ stored = new Map(), initialProjects = [project('A'), project('B')], storage = {
  getItem: key => stored.get(key) ?? null,
  setItem: (key, value) => stored.set(key, value)
} } = {}) {
  let projects = copy(initialProjects), listError = false
  const saves = [], removals = [], document = { documentElement: {} }
  const loaded = loadVue(path.join(__dirname, '../src/stores/app.ts'), { boundaries: {
    pinia, 'vue-i18n': require('vue-i18n'),
    '@/api': {
      projectApi: {
        list: async () => { if (listError) throw Error('TEST project list unavailable'); return copy(projects) },
        save: async body => { saves.push(copy(body)); const saved = { ...project(body.id), name: { zhHans: body.nameZhHans, zhHant: body.nameZhHant, en: body.nameEn }, contractNo: body.contractNo }; projects.push(saved); return copy(saved) },
        remove: async id => { removals.push(id); projects = projects.filter(item => item.id !== id) }
      }, setLlmProfileResolver() {}
    }
  }, globals: { document, ...(storage === null ? {} : { localStorage: storage }) } })
  pinia.setActivePinia(pinia.createPinia())
  const store = loaded.useAppStore()
  return { store, stored, saves, removals, document, setProjects(next) { projects = copy(next) }, failList(value) { listError = value } }
}

test('the first project is selected by default and an empty server list clears the saved selection', async () => {
  const h = harness()
  assert.equal(h.store.activeProjectId, '', 'Do not publish an unverified stored ID before loading the server list')
  await h.store.loadProjects()
  assert.equal(h.store.activeProjectId, 'A'); assert.equal(h.stored.get(selectionKey), 'A')
  h.setProjects([]); await h.store.loadProjects()
  assert.equal(h.store.activeProjectId, ''); assert.equal(h.store.activeProject, null); assert.equal(h.stored.get(selectionKey), '')
})

test('a refreshed store restores an existing selected ID using only current server metadata', async () => {
  const h = harness()
  await h.store.loadProjects(); await h.store.switchProject('B')
  assert.deepEqual([...h.stored], [[selectionKey, 'B']], 'Only the project ID belongs in selection storage')
  const refreshed = harness({ stored: h.stored, initialProjects: [project('A'), { ...project('B'), name: { en: 'Current server title', zhHans: 'Current server title', zhHant: 'Current server title' }, contractNo: 'CURRENT-B' }] })
  assert.equal(refreshed.store.activeProjectId, '')
  await refreshed.store.loadProjects()
  assert.equal(refreshed.store.activeProjectId, 'B'); assert.equal(refreshed.store.activeProject.name.en, 'Current server title'); assert.equal(refreshed.store.activeProject.contractNo, 'CURRENT-B')
  assert.deepEqual(refreshed.saves, [], 'Selection restoration cannot save metadata or adopt package identity')
})

test('a missing or deleted saved ID falls back to the first available project and repairs the remembered ID', async () => {
  for (const savedId of ['deleted-project', '{"id":"B","contractNo":"UNTRUSTED"}', '']) {
    const h = harness({ stored: new Map([[selectionKey, savedId]]) })
    await h.store.loadProjects()
    assert.equal(h.store.activeProjectId, 'A'); assert.equal(h.stored.get(selectionKey), 'A'); assert.equal(h.store.activeProject.contractNo, 'SERVER-A')
    assert.deepEqual(h.saves, [])
  }
})

test('creating, switching and deleting projects all persist the final selected ID across a new store', async () => {
  const h = harness()
  await h.store.loadProjects()
  const created = await h.store.createProject('Temporary project metadata', 'TEMP-NEW')
  assert.equal(h.store.activeProjectId, created.id); assert.equal(h.stored.get(selectionKey), created.id)
  const refreshed = harness({ stored: h.stored, initialProjects: h.store.projects })
  await refreshed.store.loadProjects(); assert.equal(refreshed.store.activeProjectId, created.id)
  await refreshed.store.deleteProject('B')
  assert.equal(refreshed.store.activeProjectId, created.id); assert.equal(h.stored.get(selectionKey), created.id)
  await refreshed.store.deleteProject(created.id)
  assert.deepEqual(refreshed.removals, ['B', created.id]); assert.equal(refreshed.store.activeProjectId, 'A'); assert.equal(h.stored.get(selectionKey), 'A')
  const afterDelete = harness({ stored: h.stored, initialProjects: refreshed.store.projects })
  await afterDelete.store.loadProjects(); assert.equal(afterDelete.store.activeProjectId, 'A')
  await afterDelete.store.deleteProject('A'); assert.deepEqual(afterDelete.removals, [], 'The last-project deletion guard remains intact')
})

test('reloading project metadata retains the current session selection and never adopts it into package values', async () => {
  const h = harness({ stored: new Map([[selectionKey, 'A']]) })
  await h.store.loadProjects(); await h.store.switchProject('B')
  h.setProjects([{ ...project('B'), contractNo: 'SERVER-RENAMED-B' }, project('A')])
  await h.store.loadProjects()
  assert.equal(h.store.activeProjectId, 'B'); assert.equal(h.store.activeProject.contractNo, 'SERVER-RENAMED-B'); assert.equal(h.stored.get(selectionKey), 'B')
  assert.deepEqual(h.saves, [])
})

test('denied or absent browser storage cannot prevent real i18n/store initialization or session selection', async () => {
  const denied = { getItem() { throw Error('TEST storage access denied') }, setItem() { throw Error('TEST storage access denied') } }
  for (const storage of [denied, null]) {
    const h = harness({ storage })
    assert.equal(h.store.locale, 'zh-Hans')
    h.store.changeLocale('en'); assert.equal(h.store.locale, 'en'); assert.equal(h.document.documentElement.lang, 'en')
    await h.store.loadProjects(); assert.equal(h.store.activeProjectId, 'A')
    await h.store.switchProject('B'); await h.store.loadProjects(); assert.equal(h.store.activeProjectId, 'B')
    const created = await h.store.createProject('Storage-free project', 'TEMP')
    assert.equal(h.store.activeProjectId, created.id)
    await h.store.deleteProject(created.id); assert.equal(h.store.activeProjectId, 'A')
  }
})

test('a storage write failure preserves this session and its current project through list reloads', async () => {
  const stored = new Map([[selectionKey, 'B']])
  const h = harness({ stored, storage: { getItem: key => stored.get(key) ?? null, setItem() { throw Error('TEST quota exceeded') } } })
  await h.store.loadProjects(); assert.equal(h.store.activeProjectId, 'B')
  await h.store.switchProject('A'); await h.store.loadProjects()
  assert.equal(h.store.activeProjectId, 'A'); assert.equal(h.store.activeProject.contractNo, 'SERVER-A')
})

test('a failed server list read preserves the stored ID for a later successful load', async () => {
  const h = harness({ stored: new Map([[selectionKey, 'B']]) })
  h.failList(true); await assert.rejects(h.store.loadProjects(), /project list unavailable/)
  assert.equal(h.store.activeProjectId, ''); assert.equal(h.stored.get(selectionKey), 'B')
  h.failList(false); await h.store.loadProjects()
  assert.equal(h.store.activeProjectId, 'B'); assert.equal(h.store.activeProject.contractNo, 'SERVER-B'); assert.deepEqual(h.saves, [])
})
