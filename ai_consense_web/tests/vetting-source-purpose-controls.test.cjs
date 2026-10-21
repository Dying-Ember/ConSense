/* Mounted purpose confirmation with fake API boundaries; no real sources or network calls. */
const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, text, find, settle, deferred } = require('./helpers/render-controls.cjs')
const all = (node, predicate) => [...(predicate(node) ? [node] : []), ...(node.children ?? []).flatMap(child => all(child, predicate))]
const row = (id, overrides = {}) => ({ documentId: id, key: 'SCT', fileName: `SCT-${id}.docx`, sourceRole: 'unknown', role: { en: 'Unknown' }, status: 'PARSED', parseStatus: 'PARSED', parsed: false, textChars: 100,
  originalSourceSha256: 'a'.repeat(64), sourceHash: 'b'.repeat(64), routingSha256: 'c'.repeat(64), roleConfirmationAvailable: true, exclusionReason: 'source_identity_unresolved', ...overrides })
const job = { id: 'run-1', status: 'COMPLETED', phase: 'done', completedUnits: 1, totalUnits: 1, message: 'Done' }
class ApiError extends Error { constructor(code) { super('Rejected'); this.code = code } }

async function mount({ files = [row('101'), row('102'), row('103', { sourceRole: 'tender', parsed: true, roleConfirmationAvailable: false })], confirm, locale = 'zh-Hans' } = {}) {
  const store = vue.reactive({ activeProjectId: 'project-a', locale, notify() {}, setBusy() {}, clearBusy() {}, captureLlmSelection: () => ({ profileId: null }) })
  const calls = [], state = { files, stale: false }
  const empty = { render: () => null }
  const loaded = loadVue(path.join(__dirname, '../src/views/VettingView.vue'), { boundaries: {
    'vue-i18n': require('vue-i18n'), 'vue-router': { useRouter: () => ({ push: async () => undefined }) }, '@/stores/app': { useAppStore: () => store },
    '@/composables/useLocalized': { useLocalized: () => ({ currentKey: vue.ref('en'), pick: value => typeof value === 'string' ? value : value?.en ?? '' }) },
    '@/api/client': { ApiError },
    '@/api': { vettingApi: {
      files: async () => state.files, findings: async () => [], metrics: async () => ({ total: 0 }),
      latestRunProgress: async () => job, getRun: async () => ({ ...job, sourceSetStale: state.stale }),
      confirmSourceRole: async (projectId, documentId, request) => {
        calls.push({ type: 'confirm', projectId, documentId, request: JSON.parse(JSON.stringify(request)) })
        const updated = confirm ? await confirm(documentId, request) : { ...state.files.find(file => file.documentId === documentId), sourceRole: request.sourceRole, parsed: true, roleConfirmationAvailable: false, exclusionReason: null }
        state.files = state.files.map(file => file.documentId === documentId ? updated : file)
        state.stale = true
        return updated
      },
      uploadPackage: async () => { calls.push({ type: 'upload' }); throw new Error('Must not reupload') },
      startRun: async () => { calls.push({ type: 'run' }); throw new Error('Must not automatically review') },
      exportReport: async () => { calls.push({ type: 'export' }); throw new Error('Must not export a stale report') }
    } },
    '@/advice/reviewHandoff': { reviewHandoffCopy: {} }, '@/components/AppIcon.vue': { default: empty },
    '@/components/AppModal.vue': { default: { props: ['open', 'title'], setup: (props, { slots }) => () => props.open ? vue.h('div', { class: 'test-modal' }, slots.default?.()) : null } },
    '@/components/CanonicalEvidencePanel.vue': { default: empty }, '@/components/FixedBaselineEvidencePanel.vue': { default: empty }, '@/components/AdviceSourceReader.vue': { default: empty }
  }, globals: { URLSearchParams, window: { setTimeout, clearTimeout } } })
  const i18n = loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n; i18n.global.locale.value = locale
  const h = renderer(), app = h.createApp(loaded.default); app.use(i18n); app.mount(h.root); await settle()
  const button = label => find(h.root, node => node.tag === 'button' && text(node).trim() === i18n.global.t(label))
  button('vetting.actions.source').props.onClick(); await settle()
  const selects = () => all(h.root, node => node.tag === 'select' && node.props?.['aria-label']?.includes(i18n.global.t('vetting.sourcePurpose.label')))
  const sourceRow = id => find(h.root, node => node.tag === 'tr' && text(node).includes(`SCT-${id}.docx`))
  const confirmButton = id => find(sourceRow(id), node => node.tag === 'button')
  return { ...h, calls, store, state, selects, button, sourceRow, confirmButton, dispose: () => app.unmount(), choose: async (id, role) => {
    find(sourceRow(id), node => node.tag === 'select').props.onChange({ target: { value: role } }); await settle()
  } }
}

test('explicit choice confirms exactly one stable document with all version pins and blocks the old report', async () => {
  const h = await mount()
  try {
    assert.equal(h.selects().length, 2, 'Both same-key unknown documents have their own action')
    assert.equal(h.confirmButton('101').props.disabled, true, 'No role is silently selected')
    const values = all(h.selects()[0], node => node.tag === 'option').map(node => node.props.value)
    assert.deepEqual(values, ['', 'tender', 'standard', 'project_fact', 'package_manifest'])
    await h.choose('102', 'standard'); await h.confirmButton('102').props.onClick(); await settle()
    assert.deepEqual(h.calls, [{ type: 'confirm', projectId: 'project-a', documentId: '102', request: { sourceRole: 'standard', expectedOriginalSourceSha256: 'a'.repeat(64), expectedSourceHash: 'b'.repeat(64), expectedRoutingSha256: 'c'.repeat(64) } }])
    assert.equal(h.state.files.find(file => file.documentId === '101').sourceRole, 'unknown')
    assert.equal(h.state.files.find(file => file.documentId === '102').sourceRole, 'standard')
    assert.equal(h.button('vetting.actions.export').props.disabled, true)
    assert.ok(text(h.root).includes('用途已确认'))
    assert.ok(text(h.root).includes('不代表内容正确或适用性已确认'))
  } finally { h.dispose() }
})

test('no purpose action is offered for incomplete pins, ongoing parse, known roles or explicit exclusions', async () => {
  const h = await mount({ files: [row('1', { documentId: undefined }), row('2', { sourceHash: null }), row('3', { parseStatus: 'IDENTITY_OBSERVED' }), row('4', { sourceRole: 'tender' }), row('5', { exclusionReason: 'drawing_outside_scope', documentScope: 'drawing' }), row('6', { documentScope: 'pricing' }), row('7', { roleConfirmationAvailable: false })] })
  try { assert.equal(h.selects().length, 0); assert.equal(h.calls.length, 0) } finally { h.dispose() }
})

test('a pending confirmation blocks conflicting mutations, report export and a second confirmation', async () => {
  const pending = deferred(), h = await mount({ confirm: () => pending.promise })
  try {
    await h.choose('101', 'tender'); const operation = h.confirmButton('101').props.onClick(); await settle()
    for (const label of ['vetting.actions.run', 'vetting.actions.upload', 'vetting.actions.export', 'vetting.sourcePurpose.refresh']) assert.equal(h.button(label).props.disabled, true, label)
    await h.button('vetting.actions.run').props.onClick(); await h.button('vetting.actions.export').props.onClick()
    assert.equal(h.calls.length, 1)
    assert.equal(h.selects().every(select => select.props.disabled === true), true)
    pending.resolve({ ...row('101'), sourceRole: 'tender', parsed: true, roleConfirmationAvailable: false, exclusionReason: null }); await operation; await settle()
  } finally { h.dispose() }
})

test('a version conflict retains the explicit selection and requires a fresh source check', async () => {
  const h = await mount({ confirm: () => Promise.reject(new ApiError(4104)) })
  try {
    await h.choose('101', 'project_fact'); await h.confirmButton('101').props.onClick(); await settle()
    assert.equal(h.selects()[0].props.value, 'project_fact')
    assert.ok(text(h.root).includes('文件版本或状态已变化'))
    assert.equal(h.confirmButton('101').props.disabled, true, 'A version conflict requires refresh before another confirmation')
    assert.equal(h.button('vetting.actions.export').props.disabled, true, 'Uncertain source versions cannot export the old report')
    assert.equal(h.state.files[0].sourceRole, 'unknown')
    assert.equal(h.calls.length, 1)
    await h.button('vetting.sourcePurpose.refresh').props.onClick(); await settle()
    assert.equal(h.selects()[0].props.value, '', 'A refreshed version requires a new explicit selection')
  } finally { h.dispose() }
})

test('a late successful response after project ABA cannot adopt an old source or display a success', async () => {
  const pending = deferred(), h = await mount({ confirm: () => pending.promise })
  try {
    await h.choose('101', 'package_manifest'); const operation = h.confirmButton('101').props.onClick(); await settle()
    h.store.activeProjectId = 'project-b'; await settle(); h.store.activeProjectId = 'project-a'; await settle()
    pending.resolve({ ...row('101'), sourceRole: 'package_manifest', parsed: true, roleConfirmationAvailable: false, exclusionReason: null }); await operation; await settle()
    assert.ok(!text(h.root).includes('用途已确认'))
    assert.equal(h.calls.length, 1)
    assert.equal(h.button('vetting.actions.export').props.disabled, false, 'The old reply does not mark the newly loaded report stale')
  } finally { h.dispose() }
})

test('all locales provide an explicit purpose action and distinguish role from content verification', async () => {
  for (const [locale, explanation] of [['zh-Hans', '不代表内容正确或适用性已确认'], ['zh-Hant', '不代表內容正確或適用性已確認'], ['en', 'It does not verify its contents or applicability']]) {
    const h = await mount({ locale })
    try { assert.ok(h.selects().length); assert.ok(text(h.root).includes(explanation)); assert.equal(h.calls.length, 0) }
    finally { h.dispose() }
  }
})

test('a response for another document is not adopted and keeps the old report blocked', async () => {
  const h = await mount({ confirm: async (_id, request) => ({ ...row('999'), sourceRole: request.sourceRole, parsed: true, roleConfirmationAvailable: false }) })
  try {
    await h.choose('101', 'tender'); await h.confirmButton('101').props.onClick(); await settle()
    assert.ok(text(h.root).includes('文件版本或状态已变化'))
    assert.ok(h.sourceRow('101'), 'The displayed document is not replaced with a different ID')
    assert.equal(h.button('vetting.actions.export').props.disabled, true)
    assert.equal(h.calls.length, 1)
  } finally { h.dispose() }
})

test('the real API call site sends stable ID and role pins via the existing JSON PUT transport', async () => {
  const requests = [], api = { put: async (url, body) => { requests.push({ url, body }); return row('42') } }
  const loaded = loadVue(path.join(__dirname, '../src/api/index.ts'), { boundaries: { './client': { api } } })
  const body = { sourceRole: 'tender', expectedOriginalSourceSha256: 'a'.repeat(64), expectedSourceHash: 'b'.repeat(64), expectedRoutingSha256: 'c'.repeat(64) }
  await loaded.vettingApi.confirmSourceRole('project /a', '42', body)
  assert.deepEqual(requests, [{ url: '/vetting/project%20%2Fa/sources/42/role', body }])
})
