/* Real Vue component and HTTP boundary checks; authored fixtures are not source-quality acceptance. */
const assert = require('node:assert/strict')
const path = require('node:path')
const crypto = require('node:crypto')
const { test } = require('node:test')
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, text, find, settle, deferred } = require('./helpers/render-controls.cjs')
const normalPointers = require('./fixtures/authored-normal-java-pointers.json')
const clone = value => JSON.parse(JSON.stringify(value))
const all = (node, predicate) => [...(predicate(node) ? [node] : []), ...(node.children ?? []).flatMap(child => all(child, predicate))]

async function mountPanels({ count = 3, deferredMode = true, automatic = false } = {}) {
  const requests = [], waiting = deferred()
  const loaded = loadVue(path.resolve(__dirname, '../src/components/CanonicalEvidencePanel.vue'), {
    boundaries: { '@noble/hashes/sha256': require('@noble/hashes/sha256') },
    globals: { Blob, Response, Uint8Array, ArrayBuffer, crypto: crypto.webcrypto,
      fetch: (url, options) => { requests.push({ url, options }); return waiting.promise },
      URL: { createObjectURL: () => 'blob:authored-evidence', revokeObjectURL() {} } }
  })
  const state = vue.reactive({ open: true, pointers: Array.from({ length: count }, (_, index) => ({ ...clone(normalPointers[0]),
    unitId: `candidate-unit:${String(index + 1).repeat(64)}`, canonicalChunkId: `canonical:${String(index + 1).repeat(64)}` })) })
  const host = renderer(), app = host.createApp({ render: () => vue.h('main', state.open ? state.pointers.map((pointer, index) =>
    vue.h(loaded.default, { key: index, pointer, locale: 'en', ...(automatic ? {} : { deferred: deferredMode }) })) : []) })
  app.mount(host.root); await settle()
  return { ...host, state, requests, dispose: () => app.unmount() }
}

test('three deferred quotations make no source GET until one visible context action is clicked', async () => {
  const h = await mountPanels()
  try {
    assert.equal(h.requests.length, 0, 'Opening a finding must not hydrate all three source frames')
    const buttons = all(h.root, node => node.tag === 'button' && text(node) === 'View source context')
    assert.equal(buttons.length, 3)
    for (const button of buttons) for (let ancestor = button.parent; ancestor; ancestor = ancestor.parent) assert.notEqual(ancestor.tag, 'details')
    buttons[1].props.onClick(); await settle()
    assert.equal(h.requests.length, 1, 'One explicit action loads only its selected quotation context')
    assert.match(h.requests[0].url, /\/units\/candidate-unit%3A2{64}\?module=vetting$/)
  } finally { h.dispose() }
})

test('closing the source context host cancels its in-flight evidence request', async () => {
  const h = await mountPanels({ count: 1 })
  try {
    find(h.root, node => node.tag === 'button').props.onClick(); await settle()
    assert.equal(h.requests.length, 1)
    assert.equal(h.requests[0].options.signal.aborted, false)
    h.state.open = false; await settle()
    assert.equal(h.requests[0].options.signal.aborted, true)
    assert.equal(h.requests.length, 1)
  } finally { h.dispose() }
})

test('changing a deferred source address cancels and resets without an implicit replacement request', async () => {
  const h = await mountPanels({ count: 1 })
  try {
    find(h.root, node => node.tag === 'button').props.onClick(); await settle()
    h.state.pointers[0] = { ...h.state.pointers[0], unitId: `candidate-unit:${'3'.repeat(64)}`, canonicalChunkId: `canonical:${'3'.repeat(64)}` }
    await settle()
    assert.equal(h.requests[0].options.signal.aborted, true)
    assert.equal(h.requests.length, 1)
    const explicit = find(h.root, node => node.tag === 'button' && text(node) === 'View source context')
    assert.ok(explicit); explicit.props.onClick(); await settle()
    assert.equal(h.requests.length, 2)
    assert.match(h.requests[1].url, /\/units\/candidate-unit%3A3{64}\?module=vetting$/)
  } finally { h.dispose() }
})

test('callers without deferred retain automatic initial and changed-address loading', async () => {
  const h = await mountPanels({ count: 1, automatic: true })
  try {
    assert.equal(h.requests.length, 1)
    h.state.pointers[0] = { ...h.state.pointers[0], unitId: `candidate-unit:${'4'.repeat(64)}`, canonicalChunkId: `canonical:${'4'.repeat(64)}` }
    await settle()
    assert.equal(h.requests[0].options.signal.aborted, true)
    assert.equal(h.requests.length, 2)
  } finally { h.dispose() }
})
