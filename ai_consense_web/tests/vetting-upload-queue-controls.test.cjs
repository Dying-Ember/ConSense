/* Offline mounted queue behavior and the real client timeout boundary; no actual uploads. */
const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, text, find, settle, deferred } = require('./helpers/render-controls.cjs')
const savedFile = name => ({ documentId: name, key: 'OTHER', fileName: name, role: { en: 'Tender' }, sourceRole: 'tender', parsed: true, status: 'PARSED', parseStatus: 'PARSED', warnings: [] })
const result = (overrides = {}) => ({ accepted: 1, parsed: 1, failed: 0, messages: ['Returned processing record'], ...overrides })
async function mount({ onUpload, onFiles, locale = 'zh-Hans' } = {}) {
  const calls = [], state = { files: [savedFile('already-saved.docx')], fileReads: 0, stale: false }
  const store = vue.reactive({ activeProjectId: 'project-a', locale, busyMessage: '', notify() {}, setBusy(message) { this.busyMessage = message }, clearBusy() { this.busyMessage = '' }, captureLlmSelection: () => ({ profileId: null }) })
  const empty = { render: () => null }
  const run = { id: 'old-run', status: 'COMPLETED', phase: 'done', completedUnits: 1, totalUnits: 1, message: 'Done' }
  const loaded = loadVue(path.join(__dirname, '../src/views/VettingView.vue'), { boundaries: {
    'vue-i18n': require('vue-i18n'), 'vue-router': { useRouter: () => ({ push: async () => undefined }) }, '@/stores/app': { useAppStore: () => store },
    '@/composables/useLocalized': { useLocalized: () => ({ currentKey: vue.ref('en'), pick: value => typeof value === 'string' ? value : value?.en ?? '' }) },
    '@/api/client': { ApiError: class ApiError extends Error {} },
    '@/api': { vettingApi: {
      files: async () => { state.fileReads++; return onFiles ? onFiles(state) : state.files }, findings: async () => [], metrics: async () => ({ total: 0 }),
      latestRunProgress: async () => run, getRun: async () => ({ ...run, sourceSetStale: state.stale }),
      uploadPackage: async (projectId, files, role, scope) => {
        calls.push({ projectId, names: JSON.parse(JSON.stringify(files.map(file => file.name))), role, scope }); state.stale = true
        const value = onUpload ? await onUpload(files[0], state) : result()
        state.files = [...state.files, savedFile(files[0].name)]
        return value
      }
    } },
    '@/advice/reviewHandoff': { reviewHandoffCopy: {} }, '@/components/AppIcon.vue': { default: empty },
    '@/components/AppModal.vue': { default: { props: ['open'], setup: (props, { slots }) => () => props.open ? vue.h('div', {}, slots.default?.()) : null } },
    '@/components/CanonicalEvidencePanel.vue': { default: empty }, '@/components/FixedBaselineEvidencePanel.vue': { default: empty }, '@/components/AdviceSourceReader.vue': { default: empty }
  }, globals: { URLSearchParams, window: { setTimeout, clearTimeout } } })
  const i18n = loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n; i18n.global.locale.value = locale
  const h = renderer(), app = h.createApp(loaded.default); app.use(i18n); app.mount(h.root); await settle()
  const button = key => find(h.root, node => node.tag === 'button' && text(node).trim() === i18n.global.t(key))
  return { ...h, calls, state, store, button, dispose: () => app.unmount(), upload: names => find(h.root, node => node.tag === 'input' && node.props.type === 'file').props.onChange({ target: { files: names.map(name => ({ name })), value: 'chosen' } }) }
}

test('the queue preserves selected order and waits for each response and source refresh before the next file', async () => {
  const first = deferred(), refresh = deferred()
  const h = await mount({ onUpload: file => file.name === 'first.pdf' ? first.promise : result(), onFiles: state => state.fileReads === 2 ? refresh.promise : state.files })
  try {
    const operation = h.upload(['first.pdf', 'second.docx', 'third.docx']); await settle()
    assert.deepEqual(h.calls.map(call => call.names), [['first.pdf']], 'The original order is kept; no speculative second upload')
    assert.ok(text(h.root).includes('当前第 1 / 3 份：first.pdf'))
    assert.equal(h.button('vetting.actions.export').props.disabled, true)
    first.resolve(result()); await settle()
    assert.equal(h.calls.length, 1, 'The response alone does not advance before source refresh')
    refresh.resolve(h.state.files); await operation; await settle()
    assert.deepEqual(h.calls.map(call => call.names), [['first.pdf'], ['second.docx'], ['third.docx']])
    assert.ok(text(h.root).includes('已收到 3 / 3 份处理结果'))
    assert.ok(text(h.root).includes('本次队列已返回全部处理结果'))
    assert.ok(h.state.fileReads >= 4)
    assert.equal(h.button('vetting.actions.export').props.disabled, true, 'The old report remains stale')
  } finally { h.dispose() }
})

test('an unconfirmed transport failure stops the queue without replay or losing saved sources', async () => {
  const h = await mount({ onUpload: file => file.name === 'slow.pdf' ? Promise.reject(new Error('timeout')) : result() })
  try {
    await h.upload(['done.docx', 'slow.pdf', 'never-sent.docx']); await settle()
    assert.deepEqual(h.calls.map(call => call.names), [['done.docx'], ['slow.pdf']])
    assert.ok(text(h.root).includes('后台可能仍在解析'))
    assert.ok(text(h.root).includes('后续文件已停止发送'))
    assert.ok(text(h.root).includes('已收到 1 / 3 份处理结果'))
    assert.deepEqual(h.state.files.map(file => file.fileName), ['already-saved.docx', 'done.docx'])
    await h.button('vetting.sourcePurpose.refresh').props.onClick(); await settle()
    assert.equal(h.calls.length, 2, 'A refresh only observes; it never resumes or resends')
    assert.equal(h.button('vetting.actions.export').props.disabled, true)
  } finally { h.dispose() }
})

test('a source-read failure after an acknowledged file stops later uploads while retaining the prior displayed files', async () => {
  const h = await mount({ onFiles: state => state.fileReads > 1 ? Promise.reject(new Error('read failed')) : state.files })
  try {
    await h.upload(['acknowledged.docx', 'never-sent.pdf']); await settle()
    assert.equal(h.calls.length, 1)
    assert.ok(text(h.root).includes('当前文件已返回结果，但源集刷新失败'))
    assert.ok(text(h.root).includes('已收到 1 / 2 份处理结果'))
    assert.ok(h.state.files.some(file => file.fileName === 'already-saved.docx'))
  } finally { h.dispose() }
})

test('a known parse failure is counted as a returned result requiring review, not a passing file or a retry', async () => {
  const h = await mount({ onUpload: file => file.name === 'failed.pdf' ? result({ parsed: 0, failed: 1 }) : result() })
  try {
    await h.upload(['failed.pdf', 'next.docx']); await settle()
    assert.deepEqual(h.calls.map(call => call.names), [['failed.pdf'], ['next.docx']])
    assert.ok(text(h.root).includes('已收到 2 / 2 份处理结果，其中 1 份接收或解析失败'))
    assert.ok(text(h.root).includes('请核对解析状态'))
  } finally { h.dispose() }
})

test('a project change while a request is pending does not send the remaining queue or publish its old progress', async () => {
  const pending = deferred(), h = await mount({ onUpload: () => pending.promise })
  try {
    const operation = h.upload(['pending.pdf', 'never-sent.docx']); await settle()
    h.store.activeProjectId = 'project-b'; await settle(); h.store.activeProjectId = 'project-a'; await settle()
    pending.resolve(result()); await operation; await settle()
    assert.equal(h.calls.length, 1)
    assert.ok(!text(h.root).includes('当前第 1 / 2 份：pending.pdf'))
    assert.ok(!text(h.root).includes('本次队列已返回全部处理结果'))
  } finally { h.dispose() }
})

test('all locales explain that the current file can still be parsing and the result count is not a quality pass', async () => {
  for (const [locale, note] of [['zh-Hans', '完成数不代表质量通过'], ['zh-Hant', '完成數不代表品質通過'], ['en', 'the count does not establish quality']]) {
    const pending = deferred(), h = await mount({ locale, onUpload: () => pending.promise })
    try { const operation = h.upload(['one.pdf']); await settle(); assert.ok(text(h.root).includes(note)); pending.resolve(result()); await operation }
    finally { h.dispose() }
  }
})

test('the real API and JSON client select a two-hour per-file wait without changing other uploads', async () => {
  const requests = [], axios = { create: () => ({ post: async (url, body, config) => { requests.push({ url, body, config }); return { data: { code: 0, data: result() } } } }), isAxiosError: () => false }
  const loaded = loadVue(path.join(__dirname, '../src/api/index.ts'), { boundaries: { axios: { default: axios } }, globals: { FormData, URLSearchParams } })
  const file = new File(['authored'], 'authored.docx')
  await loaded.vettingApi.uploadPackage('project-a', [file], 'tender', 'package')
  await loaded.draftingApi.uploadInputs('project-a', [file])
  assert.equal(requests[0].config.timeout, 7_200_000)
  assert.equal(requests[1].config.timeout, undefined, 'Existing unrelated uploads retain the configured default')
  assert.match(requests[0].url, /sourceRole=tender.*documentScope=package/)
  assert.equal(requests[0].body.getAll('files').length, 1)
})
