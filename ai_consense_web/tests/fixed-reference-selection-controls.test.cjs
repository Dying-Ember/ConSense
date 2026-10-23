const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const { loadVue } = require('./helpers/load-vue.cjs')
const { text, find, settle, deferred, renderer } = require('./helpers/render-controls.cjs')

test('edition selection saves exact SHA and project and ignores an old project response', async () => {
  const writes = [], pending = deferred(), a = 'a'.repeat(64), b = 'b'.repeat(64)
  const status = { manifestSha256: 'f'.repeat(64), availableSlSources: [{ editionId: 'old-edition', sha256: a }, { editionId: 'new-edition', sha256: b }], selection: null }
  const loaded = loadVue(path.join(__dirname, '../src/components/FixedReferenceSelection.vue'), { boundaries: {
    '@/api': { fixedReferenceApi: { status: async () => ({ configured: true }), selection: async id => id === 'A' ? pending.promise : status, save: async (id, body) => { writes.push({ id, body: JSON.parse(JSON.stringify(body)) }); return { ...status, selection: body } } } }
  } })
  const host = renderer(), vue = require('vue'), id = vue.ref('A')
  const app = host.createApp({ setup: () => () => vue.h(loaded.default, { projectId: id.value, locale: 'en' }) }); app.mount(host.root); await settle()
  id.value = 'B'; await settle(); pending.resolve({ ...status, selection: { slSources: [{ editionId: 'old-edition', sha256: a }] } }); await settle()
  const checkbox = find(host.root, node => node.tag === 'input' && node.props.value === b)
  checkbox.props['onUpdate:modelValue']([b]); await settle()
  await find(host.root, node => node.tag === 'button' && text(node).includes('Save')).props.onClick(); await settle()
  assert.deepEqual(writes, [{ id: 'B', body: { manifestSha256: status.manifestSha256, slSources: [{ editionId: 'new-edition', sha256: b }] } }])
  assert.match(text(host.root), /does not establish project adoption/)
  app.unmount()
})
