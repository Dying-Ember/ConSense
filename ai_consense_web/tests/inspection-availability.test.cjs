/* Mounted controls over authored saved-artifact states; no business API or source-quality acceptance. */
const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, text, find, settle, deferred } = require('./helpers/render-controls.cjs')

const absent = { id: 'missing-snapshot', label: 'Saved native observation', status: 'unavailable', unavailableReason: 'saved_experiment_not_present_on_this_host' }
const available = { id: 'available-snapshot', label: 'Available saved observation', status: 'available' }

async function mount(query = {}, options = {}) {
  let datasets = [absent, available], runs = options.runs ?? []
  const calls = [], route = vue.reactive({ query: { ...query } })
  const record = (name, id) => { calls.push([name, id]); if (id === absent.id) throw Error('Authored missing snapshot') }
  const loaded = loadVue(path.join(__dirname, '../src/views/VettingInspectionView.vue'), { boundaries: {
    'vue-router': { useRoute: () => route, useRouter: () => ({ replace: async value => { route.query = value.query } }) },
    '@/stores/app': { useAppStore: () => vue.reactive({ locale: 'en' }) },
    '@/api/inspection': { inspectionJson: value => JSON.stringify(value), inspectionOriginalUrl: () => null,
      inspectionApi: {
        catalog: async () => ({ datasets, runs, readOnly: true }),
        documents: async id => { record('documents', id); return { items: [], total: 0 } },
        chunks: async id => { record('chunks', id); return options.chunkResult ?? { items: [], total: 0, status: 'available' } },
        pages: async id => { record('pages', id); return { items: [], total: 0 } },
        tasks: async () => ({ items: [], total: 0 })
      } },
    '@/components/AppIcon.vue': { default: { render: () => vue.h('span') } },
    '@/components/InspectionOcrDiagnostics.vue': { default: { render: () => vue.h('div') } }
  }, globals: { URL, window: { location: { origin: 'http://fixture.local' } } } })
  const h = renderer(), app = h.createApp(loaded.default)
  app.mount(h.root); await settle()
  return { ...h, calls, route, setDatasets(value) { datasets = value },
    setRuns(value) { runs = value },
    async refresh() { find(h.root, node => node.tag === 'button' && text(node).includes('Refresh')).props.onClick(); await settle() },
    dispose: () => app.unmount() }
}

test('an explicit unavailable deep link remains selected with its reason and performs no artifact calls', async () => {
  const h = await mount({ dataset: absent.id })
  try {
    assert.equal(h.route.query.dataset, absent.id)
    assert.deepEqual(h.calls, [])
    assert.ok(text(h.root).includes('Saved experiment unavailable on this host'))
    assert.ok(!text(h.root).includes('Inspection service is unavailable.'))
  } finally { h.dispose() }
})

test('an initial visit without a deep link selects the first available saved snapshot', async () => {
  const h = await mount()
  try {
    assert.equal(h.route.query.dataset, available.id)
    assert.ok(h.calls.some(([name, id]) => name === 'documents' && id === available.id))
    assert.ok(h.calls.some(([name, id]) => name === 'chunks' && id === available.id))
    assert.ok(h.calls.every(([, id]) => id !== absent.id))
  } finally { h.dispose() }
})

test('refreshing a selected snapshot that became unavailable clears the old view without new artifact calls', async () => {
  const h = await mount({ dataset: available.id })
  try {
    const before = h.calls.length
    h.setDatasets([{ ...available, status: 'unavailable', unavailableReason: absent.unavailableReason }])
    await h.refresh()
    assert.equal(h.route.query.dataset, available.id)
    assert.equal(h.calls.length, before)
    assert.ok(text(h.root).includes('Saved experiment unavailable on this host'))
  } finally { h.dispose() }
})

test('a late chunk response cannot overwrite the selected snapshot unavailable state', async () => {
  const pending = deferred(), h = await mount({ dataset: available.id }, { chunkResult: pending.promise,
    runs: [{ id: 'saved-run', datasetId: available.id, label: 'Saved retrieval' }] })
  try {
    h.setDatasets([{ ...available, status: 'unavailable', unavailableReason: absent.unavailableReason }])
    h.setRuns([])
    await h.refresh()
    pending.resolve({ items: [{ id: 'old', content: 'OLD SAVED CONTENT' }], total: 1, status: 'available' })
    await settle()
    assert.ok(text(h.root).includes('Saved experiment unavailable on this host'))
    assert.ok(!text(h.root).includes('OLD SAVED CONTENT'))
    assert.ok(!text(h.root).includes('Reading actual saved records'))
  } finally { h.dispose() }
})
