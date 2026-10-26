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

async function mount({ files = [row('101'), row('102'), row('103', { sourceRole: 'tender', parsed: true, roleConfirmationAvailable: false })], confirm, onProgress, timers = { setTimeout, clearTimeout }, locale = 'zh-Hans' } = {}) {
  const store = vue.reactive({ activeProjectId: 'project-a', locale, notify() {}, setBusy() {}, clearBusy() {}, captureLlmSelection: () => ({ profileId: null }) })
  const calls = [], state = { files, stale: false, progressReads: 0, fileReads: 0 }
  const empty = { render: () => null }
  const loaded = loadVue(path.join(__dirname, '../src/views/VettingView.vue'), { boundaries: {
    'vue-i18n': require('vue-i18n'), 'vue-router': { useRouter: () => ({ push: async () => undefined }) }, '@/stores/app': { useAppStore: () => store },
    '@/composables/useLocalized': { useLocalized: () => ({ currentKey: vue.ref('en'), pick: value => typeof value === 'string' ? value : value?.en ?? '' }) },
    '@/api/client': { ApiError },
    '@/api': { vettingApi: {
      sourceProgress: async () => { state.progressReads++; return onProgress ? onProgress(state) : [] },
      files: async () => { state.fileReads++; return state.files }, findings: async () => [], metrics: async () => ({ total: 0 }),
      latestRunProgress: async () => job, getRun: async () => ({ ...job, sourceSetStale: state.stale }),
      getRunDisplay: async () => ({ ...job, sourceSetStale: state.stale }),
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
  }, globals: { URLSearchParams, window: timers } })
  const i18n = loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n; i18n.global.locale.value = locale
  const h = renderer(), app = h.createApp(loaded.default); app.use(i18n); app.mount(h.root); await settle()
  const button = label => find(h.root, node => node.tag === 'button' && text(node).trim() === i18n.global.t(label))
  button('vetting.actions.source').props.onClick(); await settle()
  const selects = () => all(h.root, node => node.tag === 'select' && node.props?.['aria-label']?.includes(i18n.global.t('vetting.sourcePurpose.label')))
  const sourceRow = id => find(h.root, node => node.tag === 'tr' && text(node).includes(state.files.find(file => file.documentId === id)?.fileName ?? `SCT-${id}.docx`))
  const confirmButton = id => find(sourceRow(id), node => node.tag === 'button' && Object.hasOwn(node.props, 'data-source-purpose-confirm'))
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

test('large saved source keeps a readable purpose selector and confirms with its trusted content pin', async () => {
  const h = await mount({ files: [row('201', { sourceHash: null, businessContentSha256: 'd'.repeat(64), status: 'PARTIAL', parseStatus: 'PARTIAL' })] })
  try {
    assert.equal(h.selects().length, 1)
    await h.choose('201', 'tender'); await h.confirmButton('201').props.onClick(); await settle()
    assert.deepEqual(h.calls, [{ type: 'confirm', projectId: 'project-a', documentId: '201', request: {
      sourceRole: 'tender', expectedOriginalSourceSha256: 'a'.repeat(64), expectedBusinessContentSha256: 'd'.repeat(64), expectedRoutingSha256: 'c'.repeat(64)
    } }])
    assert.equal(h.state.files[0].sourceRole, 'tender')
  } finally { h.dispose() }
})

test('no purpose action is offered for incomplete pins, ongoing parse, known roles or explicit exclusions', async () => {
  const h = await mount({ files: [row('1', { documentId: undefined }), row('2', { sourceHash: null }), row('3', { parseStatus: 'IDENTITY_OBSERVED' }), row('4', { sourceRole: 'tender' }), row('5', { exclusionReason: 'drawing_outside_scope', documentScope: 'drawing' }), row('6', { documentScope: 'pricing' }), row('7', { roleConfirmationAvailable: false })] })
  try { assert.equal(h.selects().length, 0); assert.equal(h.calls.length, 0) } finally { h.dispose() }
})

test('Vetting shows localized OER exclusion reasons instead of internal keys', async () => {
  const files = ['evaluation_answer_isolated', 'drafting_standard_template', 'source_not_eligible_for_vetting'].map((exclusionReason, index) =>
    row(String(index + 1), { exclusionReason, roleConfirmationAvailable: false }))
  for (const [locale, reasons] of [
    ['zh-Hans', ['评估答案仅作独立对照', '起草标准模板仅供起草使用', '此文件未获准用于审查']],
    ['zh-Hant', ['評估答案僅作獨立對照', '起草標準範本僅供起草使用', '此檔案未獲准用於審查']],
    ['en', ['Evaluation answer for separate comparison only', 'Drafting standard template for Drafting only', 'This file is not eligible for Vetting']]
  ]) {
    const h = await mount({ files, locale })
    try {
      reasons.forEach((reason, index) => assert.ok(text(h.sourceRow(String(index + 1))).includes(reason), `${locale}: ${reason}`))
      assert.doesNotMatch(text(h.root), /advice\.sourceScope\.reasons\./)
    } finally { h.dispose() }
  }
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

const pendingRow = (id, overrides = {}) => row(id, { status: 'IDENTITY_PENDING', parseStatus: 'IDENTITY_PENDING', textChars: 0,
  roleConfirmationAvailable: false, identityConfirmationAvailable: true, documentScope: 'package', ...overrides })

test('manual confirmed-text parsing keeps visible page progress while the confirmation or retry is pending', async () => {
  for (const initialStatus of ['IDENTITY_PENDING', 'FAILED']) {
    const pending = deferred(), scheduled = new Map()
    let nextId = 1, phase = 'idle'
    const timers = {
      setTimeout(callback, delay) { const id = nextId++; scheduled.set(id, { callback, delay }); return id },
      clearTimeout(id) { scheduled.delete(id) }
    }
    const nextPoll = async () => {
      assert.equal(scheduled.size, 1, 'The existing progress loop has one pending timer')
      const [id, timer] = scheduled.entries().next().value
      assert.equal(timer.delay, 3000)
      scheduled.delete(id); timer.callback(); await settle()
    }
    const source = pendingRow('51', { fileName: 'complete-APB.pdf', pageCount: 61, status: initialStatus,
      parseStatus: initialStatus, sourceRole: initialStatus === 'FAILED' ? 'tender' : 'unknown' })
    const h = await mount({ files: [source], timers, locale: 'en', confirm: () => { phase = 'starting'; return pending.promise }, onProgress: () => {
      if (phase === 'idle' || phase === 'starting') return [{ id: 51, fileName: source.fileName, pageCount: 61, parseStatus: initialStatus }]
      return [{ id: 51, fileName: source.fileName, pageCount: 61, parseStatus: phase === 'done' ? 'PARTIAL' : 'PARSING',
        parseMessage: phase === 'page-2' ? '第 2 / 61 页：渲染页面' : '第 1 / 61 页：等待视觉识别结果' }]
    } })
    try {
      assert.equal(scheduled.size, 0, 'An idle source list does not keep polling')
      if (initialStatus === 'IDENTITY_PENDING') await h.choose('51', 'tender')
      find(h.sourceRow('51'), node => node.tag === 'input' && node.props.type === 'checkbox').props.onChange({ target: { checked: true } }); await settle()
      const readsBefore = h.state.progressReads, operation = h.confirmButton('51').props.onClick(); await settle()
      assert.ok(h.state.progressReads > readsBefore, 'Confirmation starts observing progress without waiting for its HTTP result')
      assert.equal(h.calls.length, 1)
      assert.equal(h.confirmButton('51').props.disabled, true)
      phase = 'page-1'; await nextPoll()
      const focus = () => find(h.root, node => node.props?.['data-vetting-current-file'] !== undefined)
      assert.match(text(focus()), /complete-APB\.pdf/)
      assert.match(text(focus()), /Current physical page:.*1\s*\/\s*61/)
      assert.match(text(focus()), /Visual recognition/)
      phase = 'page-2'; await nextPoll()
      assert.match(text(focus()), /Current physical page:.*2\s*\/\s*61/)
      assert.match(text(focus()), /Page rendering/)
      assert.equal(h.calls.length, 1, 'Progress reads do not replay confirmation or upload')
      phase = 'done'
      const updated = { ...source, sourceRole: 'tender', status: 'PARTIAL', parseStatus: 'PARTIAL', parsed: true,
        textChars: 200, identityConfirmationAvailable: false, sourceHash: 'd'.repeat(64), routingSha256: 'e'.repeat(64), exclusionReason: null }
      h.state.files = [updated]
      const fileReadsBeforeResponse = h.state.fileReads
      await nextPoll()
      assert.equal(h.state.fileReads, fileReadsBeforeResponse, 'Terminal progress cannot reload new pins before the pending mutation adopts its response')
      pending.resolve(updated)
      await operation; await settle(); await nextPoll()
      assert.equal(scheduled.size, 0, 'Polling stops after the saved terminal state and response')
      assert.equal(h.state.files[0].parseStatus, 'PARTIAL')
      assert.equal(h.calls.length, 1)
    } finally { h.dispose(); pending.resolve(source) }
  }
})

test('pending identity requires a separate explicit text-material confirmation and preserves original access', async () => {
  const h = await mount({ files: [pendingRow('51')] })
  try {
    assert.equal(h.selects().length, 1, 'Pending sources can choose a purpose without being parsed first')
    assert.ok(text(h.sourceRow('51')).includes('尚未进行详细解析'))
    const original = find(h.sourceRow('51'), node => node.tag === 'a')
    assert.match(original.props.href, /sources\/51\/original\?sourceHash=/)
    assert.match(original.props.href, /sourceRole=unknown/)
    assert.equal(original.props.download, 'SCT-51.docx'); assert.equal(original.props.target, undefined)
    assert.equal(text(original).trim(), '下载原始文件')
    const check = find(h.sourceRow('51'), node => node.tag === 'input' && node.props.type === 'checkbox')
    assert.ok(check)
    await h.choose('51', 'tender')
    assert.equal(h.confirmButton('51').props.disabled, true)
    await h.confirmButton('51').props.onClick(); await settle()
    assert.equal(h.calls.length, 0, 'Choosing a purpose alone must not start parsing')
    check.props.onChange({ target: { checked: true } }); await settle()
    assert.equal(h.confirmButton('51').props.disabled, false)
    assert.ok(text(h.confirmButton('51')).includes('继续解析'))
  } finally { h.dispose() }
})

test('source-list originals keep downloads and PDF preview buttons with the original identity', async () => {
  const files = ['word-source.DOCX', 'legacy-source.DOC', 'outlook-message.msg', 'email-message.eml', 'plain-source.txt', 'pdf-source.PDF']
    .map((fileName, index) => pendingRow(String(51 + index), { fileName }))
  for (const [locale, downloadLabel, openLabel] of [
    ['zh-Hans', '下载原始文件', '打开原始文件'], ['zh-Hant', '下載原始文件', '開啟原始文件'], ['en', 'Download original', 'Open original']
  ]) {
    const h = await mount({ locale, files })
    try {
      for (const source of files) {
        const sourceRow = find(h.root, node => node.tag === 'tr' && text(node).includes(source.fileName))
        const original = find(sourceRow, node => node.tag === 'a'), pdf = source.fileName.toLowerCase().endsWith('.pdf')
        assert.ok(original); assert.equal(text(original).trim(), downloadLabel)
        assert.equal(original.props.target, undefined)
        assert.equal(original.props.download, source.fileName)
        if (pdf) { const preview = find(sourceRow, node => node.tag === 'button' && Object.hasOwn(node.props, 'data-source-original-preview')); assert.ok(preview); assert.equal(text(preview).trim(), openLabel); assert.equal(preview.props.disabled, false) }
        const url = new URL(original.props.href, 'http://localhost')
        assert.equal(url.pathname, `/api/vetting/project-a/sources/${source.documentId}/original`)
        for (const key of ['sourceHash', 'originalSourceSha256', 'sourceRole']) assert.equal(url.searchParams.get(key), source[key])
      }
      assert.equal(h.calls.length, 0, 'Rendering original links does not parse, review, export or confirm a source')
    } finally { h.dispose() }
  }
})

test('an explicitly confirmed pending source accepts the new parse revision of the same original only', async () => {
  const h = await mount({ files: [pendingRow('51')], confirm: async (_id, request) => ({ ...pendingRow('51'), sourceRole: request.sourceRole,
    parseStatus: 'PARTIAL', status: 'PARTIAL', textChars: 200, parsed: true, sourceHash: 'd'.repeat(64), routingSha256: 'e'.repeat(64),
    identityConfirmationAvailable: false, exclusionReason: null }) })
  try {
    await h.choose('51', 'tender')
    find(h.sourceRow('51'), node => node.tag === 'input' && node.props.type === 'checkbox').props.onChange({ target: { checked: true } }); await settle()
    await h.confirmButton('51').props.onClick(); await settle()
    assert.equal(h.calls.length, 1)
    assert.equal(h.calls[0].request.confirmTextContractMaterial, true)
    assert.equal(h.calls[0].request.expectedSourceHash, 'b'.repeat(64))
    assert.equal(h.state.files[0].sourceHash, 'd'.repeat(64))
    assert.ok(!text(h.root).includes('文件版本或状态已变化'))
    assert.ok(text(h.root).includes('解析请求已提交'))
    assert.equal(h.button('vetting.actions.export').props.disabled, true)
  } finally { h.dispose() }
})

test('pending identity actions cannot override known drawing, excluded scope, answers or missing server permission', async () => {
  const h = await mount({ files: [pendingRow('1', { identityConfirmationAvailable: false }), pendingRow('2', { documentScope: 'drawing' }),
    pendingRow('3', { documentScope: 'example' }), pendingRow('4', { documentScope: 'pricing' }), pendingRow('5', { documentScope: 'works_pss' }),
    pendingRow('6', { exclusionReason: 'example_answer' }), pendingRow('7', { parseStatus: 'EXCLUDED' }), pendingRow('8', { sourceHash: null })] })
  try { assert.equal(h.selects().length, 0); assert.equal(h.calls.length, 0) } finally { h.dispose() }
})

test('refresh clears a pending text-material confirmation so a fresh source needs a new decision', async () => {
  const h = await mount({ files: [pendingRow('51')] })
  try {
    await h.choose('51', 'tender')
    find(h.sourceRow('51'), node => node.tag === 'input' && node.props.type === 'checkbox').props.onChange({ target: { checked: true } }); await settle()
    await h.button('vetting.sourcePurpose.refresh').props.onClick(); await settle()
    await h.choose('51', 'tender')
    assert.equal(h.confirmButton('51').props.disabled, true)
    assert.equal(h.calls.length, 0)
  } finally { h.dispose() }
})

test('an uncertain parse response blocks duplicate submission and requires observing the same source', async () => {
  const h = await mount({ files: [pendingRow('51')], confirm: () => Promise.reject(new Error('transport timeout')) })
  try {
    await h.choose('51', 'tender')
    find(h.sourceRow('51'), node => node.tag === 'input' && node.props.type === 'checkbox').props.onChange({ target: { checked: true } }); await settle()
    await h.confirmButton('51').props.onClick(); await settle()
    assert.ok(text(h.root).includes('后台可能仍在处理'))
    assert.equal(h.confirmButton('51').props.disabled, true)
    await h.confirmButton('51').props.onClick(); await settle()
    assert.equal(h.calls.length, 1, 'No automatic replay or immediate second submission')
    assert.equal(h.button('vetting.actions.export').props.disabled, true)
    await h.button('vetting.sourcePurpose.refresh').props.onClick(); await settle()
    assert.equal(h.calls.length, 1, 'Refreshing observes state only')
    assert.equal(h.confirmButton('51').props.disabled, true, 'Refresh requires a new explicit decision')
  } finally { h.dispose() }
})

test('a completed parse response for replaced original bytes cannot be adopted', async () => {
  const h = await mount({ files: [pendingRow('51')], confirm: async (_id, request) => ({ ...pendingRow('51'), sourceRole: request.sourceRole,
    parseStatus: 'PARSED', status: 'PARSED', parsed: true, originalSourceSha256: 'f'.repeat(64), sourceHash: 'd'.repeat(64), routingSha256: 'e'.repeat(64) }) })
  try {
    await h.choose('51', 'tender')
    find(h.sourceRow('51'), node => node.tag === 'input' && node.props.type === 'checkbox').props.onChange({ target: { checked: true } }); await settle()
    await h.confirmButton('51').props.onClick(); await settle()
    assert.ok(text(h.root).includes('文件版本或状态已变化'))
    assert.equal(h.confirmButton('51').props.disabled, true)
    assert.equal(h.calls.length, 1)
  } finally { h.dispose() }
})

test('each locale explains unparsed state and requires the same explicit text-material decision', async () => {
  for (const [locale, note, label] of [['zh-Hans', '尚未进行详细解析', '我已核对原件'], ['zh-Hant', '尚未進行詳細解析', '我已核對原件'], ['en', 'Detailed parsing has not started', 'I checked the original']]) {
    const h = await mount({ files: [pendingRow('51')], locale })
    try {
      assert.ok(text(h.sourceRow('51')).includes(note))
      assert.ok(text(h.sourceRow('51')).includes(label))
      await h.choose('51', 'tender')
      assert.equal(h.confirmButton('51').props.disabled, true)
      assert.equal(h.calls.length, 0)
    } finally { h.dispose() }
  }
})

test('only identity-resume PUT requests use the existing two-hour parsing timeout', async () => {
  const requests = [], axios = { create: () => ({ put: async (url, body, config) => { requests.push({ url, body, config }); return { data: { code: 0, data: row('51') } } } }), isAxiosError: () => false }
  const loaded = loadVue(path.join(__dirname, '../src/api/index.ts'), { boundaries: { axios: { default: axios } }, globals: { FormData, URLSearchParams } })
  const body = { sourceRole: 'tender', expectedOriginalSourceSha256: 'a'.repeat(64), expectedSourceHash: 'b'.repeat(64), expectedRoutingSha256: 'c'.repeat(64) }
  await loaded.vettingApi.confirmSourceRole('project /a', '51', { ...body, confirmTextContractMaterial: true })
  await loaded.vettingApi.confirmSourceRole('project /a', '52', body)
  assert.equal(requests[0].config.timeout, 7_200_000)
  assert.equal(requests[1].config.timeout, undefined, 'Parsed-purpose updates retain their configured default timeout')
  assert.equal(requests[0].body.confirmTextContractMaterial, true)
  assert.equal(requests[1].body.confirmTextContractMaterial, undefined)
  assert.equal(requests[0].url, '/vetting/project%20%2Fa/sources/51/role')
})

test('a failed resumed parse stays unavailable with a readable retained-original explanation in every locale', async () => {
  for (const [locale, note] of [['zh-Hans', '详细解析尚未完成或失败，原件已保留'], ['zh-Hant', '詳細解析尚未完成或失敗，原件已保留'], ['en', 'Detailed parsing is incomplete or failed. The original is retained']]) {
    const h = await mount({ locale, files: [pendingRow('51')], confirm: async (_id, request) => ({ ...pendingRow('51'), sourceRole: request.sourceRole,
      parseStatus: 'FAILED', status: 'FAILED', parsed: false, identityConfirmationAvailable: false,
      sourceHash: 'd'.repeat(64), routingSha256: 'e'.repeat(64), exclusionReason: 'source_parse_unavailable' }) })
    try {
      await h.choose('51', 'tender')
      find(h.sourceRow('51'), node => node.tag === 'input' && node.props.type === 'checkbox').props.onChange({ target: { checked: true } }); await settle()
      await h.confirmButton('51').props.onClick(); await settle()
      assert.equal(h.selects().length, 0)
      assert.equal(h.state.files[0].parsed, false)
      assert.ok(text(h.sourceRow('51')).includes(note))
      assert.ok(!text(h.sourceRow('51')).includes('advice.sourceScope.reasons.source_parse_unavailable'))
      assert.equal(h.button('vetting.actions.export').props.disabled, true)
      assert.equal(h.calls.length, 1)
    } finally { h.dispose() }
  }
})

test('server-approved pending sources with an explicitly uploaded legal role can confirm text identity and resume', async () => {
  for (const role of ['tender', 'standard', 'project_fact', 'package_manifest']) {
    const h = await mount({ files: [pendingRow('51', { sourceRole: role })], confirm: async (_id, request) => ({ ...pendingRow('51'), sourceRole: request.sourceRole,
      parseStatus: 'PARTIAL', status: 'PARTIAL', textChars: 200, parsed: true, identityConfirmationAvailable: false,
      sourceHash: 'd'.repeat(64), routingSha256: 'e'.repeat(64), exclusionReason: null }) })
    try {
      assert.equal(h.selects().length, 1, role)
      assert.equal(h.selects()[0].props.value, '', 'The previous upload choice is not silently reconfirmed')
      assert.match(find(h.sourceRow('51'), node => node.tag === 'a').props.href, new RegExp(`sourceRole=${role}`))
      await h.choose('51', role)
      assert.equal(h.confirmButton('51').props.disabled, true, 'The upload purpose is not text-identity consent')
      await h.confirmButton('51').props.onClick(); await settle()
      assert.equal(h.calls.length, 0)
      find(h.sourceRow('51'), node => node.tag === 'input' && node.props.type === 'checkbox').props.onChange({ target: { checked: true } }); await settle()
      await h.confirmButton('51').props.onClick(); await settle()
      assert.equal(h.calls.length, 1)
      assert.equal(h.calls[0].request.sourceRole, role)
      assert.equal(h.calls[0].request.confirmTextContractMaterial, true)
      assert.equal(h.state.files[0].parsed, true)
    } finally { h.dispose() }
  }
})

test('existing upload roles do not bypass server permission, excluded scopes or ordinary role-confirmation restrictions', async () => {
  const h = await mount({ files: [pendingRow('1', { sourceRole: 'tender', identityConfirmationAvailable: false }),
    pendingRow('2', { sourceRole: 'sample_answer' }), pendingRow('3', { sourceRole: 'drawing' }),
    pendingRow('4', { sourceRole: 'tender', documentScope: 'drawing' }), pendingRow('5', { sourceRole: 'tender', documentScope: 'example' }),
    pendingRow('6', { sourceRole: 'tender', exclusionReason: 'example_answer' }),
    row('7', { sourceRole: 'tender', identityConfirmationAvailable: true })] })
  try { assert.equal(h.selects().length, 0); assert.equal(h.calls.length, 0) } finally { h.dispose() }
})

test('failed textual originals have a visible retry with a fixed saved purpose and a fresh explicit material check', async () => {
  for (const [locale, label] of [['zh-Hans', '重试解析'], ['zh-Hant', '重試解析'], ['en', 'Retry parsing']]) {
    const failed = pendingRow('51', { fileName: 'SCT-51.docx', status: 'FAILED', parseStatus: 'FAILED', sourceRole: 'tender', exclusionReason: null })
    const h = await mount({ locale, files: [failed], confirm: async (_id, request) => ({ ...failed, sourceRole: request.sourceRole,
      status: 'PARTIAL', parseStatus: 'PARTIAL', parsed: true, identityConfirmationAvailable: false,
      sourceHash: 'd'.repeat(64), routingSha256: 'e'.repeat(64) }) })
    try {
      assert.equal(text(h.confirmButton('51')).trim(), label)
      assert.equal(h.selects()[0].props.value, 'tender')
      assert.equal(h.selects()[0].props.disabled, true, 'Retry keeps the saved purpose')
      assert.equal(h.confirmButton('51').props.disabled, true)
      await h.confirmButton('51').props.onClick(); await settle(); assert.equal(h.calls.length, 0)
      find(h.sourceRow('51'), node => node.tag === 'input' && node.props.type === 'checkbox').props.onChange({ target: { checked: true } }); await settle()
      assert.equal(h.confirmButton('51').props.disabled, false)
      await h.confirmButton('51').props.onClick(); await settle()
      assert.equal(h.calls.length, 1); assert.equal(h.calls[0].request.sourceRole, 'tender')
      assert.equal(h.calls[0].request.confirmTextContractMaterial, true)
      assert.equal(h.calls[0].request.expectedOriginalSourceSha256, failed.originalSourceSha256)
      assert.equal(h.state.files[0].parsed, true)
    } finally { h.dispose() }
  }
})

test('a failed source with unknown purpose needs an explicit valid purpose and text check before retry', async () => {
  for (const role of ['tender', 'standard', 'project_fact', 'package_manifest']) {
    const failureMessage = 'Preserved failed attempt: NORMAL_PDF_COMPACT_REFERENCE_LITERAL_TARGET'
    const failed = pendingRow('51', { fileName: 'unconfirmed-annex.pdf', status: 'FAILED', parseStatus: 'FAILED', sourceRole: 'unknown', parseMessage: failureMessage })
    const h = await mount({ locale: 'en', files: [failed], confirm: async (_id, request) => ({ ...failed, sourceRole: request.sourceRole,
      status: 'PARTIAL', parseStatus: 'PARTIAL', parsed: true, identityConfirmationAvailable: false,
      sourceHash: 'd'.repeat(64), routingSha256: 'e'.repeat(64) }) })
    try {
      assert.equal(h.selects().length, 1)
      assert.equal(h.selects()[0].props.disabled, false, 'Unknown failed purpose is a human choice, not a fixed saved purpose')
      assert.equal(h.selects()[0].props.value, '')
      assert.equal(h.confirmButton('51').props.disabled, true)
      await h.choose('51', role)
      await h.confirmButton('51').props.onClick(); await settle()
      assert.equal(h.calls.length, 0, 'Choosing a purpose alone cannot retry failed parsing')
      assert.equal(h.state.files[0].parseStatus, 'FAILED'); assert.equal(h.state.files[0].parseMessage, failureMessage)
      find(h.sourceRow('51'), node => node.tag === 'input' && node.props.type === 'checkbox').props.onChange({ target: { checked: true } }); await settle()
      await h.choose('51', 'sample_answer'); await h.confirmButton('51').props.onClick(); await settle()
      assert.equal(h.calls.length, 0, 'The four supported purposes cannot be bypassed by a forged selection')
      await h.choose('51', role); await h.confirmButton('51').props.onClick(); await settle()
      assert.equal(h.calls.length, 1); assert.equal(h.calls[0].documentId, '51')
      assert.deepEqual(h.calls[0].request, { sourceRole: role, expectedOriginalSourceSha256: failed.originalSourceSha256,
        expectedSourceHash: failed.sourceHash, expectedRoutingSha256: failed.routingSha256, confirmTextContractMaterial: true })
      assert.equal(h.state.files[0].parseStatus, 'PARTIAL'); assert.equal(h.state.files[0].originalSourceSha256, failed.originalSourceSha256)
      assert.equal(failed.parseStatus, 'FAILED'); assert.equal(failed.parseMessage, failureMessage)
    } finally { h.dispose() }
  }
})

test('failed PDF keeps its readable original preview before reconfirmation and never enables failed saved text', async () => {
  const files = [pendingRow('51', { fileName: 'failed-original.pdf', status: 'FAILED', parseStatus: 'FAILED', sourceRole: 'tender', exclusionReason: null })]
  const h = await mount({ locale: 'en', files })
  try {
    const sourceRow = find(h.root, node => node.tag === 'tr' && text(node).includes('failed-original.pdf'))
    const preview = find(sourceRow, node => node.tag === 'button' && Object.hasOwn(node.props, 'data-source-original-preview'))
    assert.equal(preview.props.disabled, false); assert.equal(text(preview).trim(), 'Open original')
    const savedText = find(sourceRow, node => node.tag === 'button' && text(node).trim() === 'View parsed text')
    assert.equal(savedText.props.disabled, true)
    assert.match(find(sourceRow, node => node.tag === 'a').props.href, /sources\/51\/original\?sourceHash=/)
    assert.equal(h.calls.length, 0)
  } finally { h.dispose() }
})

test('failed sources without server permission or with excluded identity never offer retry parsing', async () => {
  const failed = overrides => pendingRow(overrides.documentId, { parseStatus: 'FAILED', status: 'FAILED', sourceRole: 'tender', ...overrides })
  const h = await mount({ files: [failed({ documentId: '1', identityConfirmationAvailable: false }),
    failed({ documentId: '2', documentScope: 'drawing' }), failed({ documentId: '3', documentScope: 'pricing' }),
    failed({ documentId: '4', sourceRole: 'sample_answer' }), failed({ documentId: '5', exclusionReason: 'evaluation_answer_isolated' })] })
  try { assert.equal(h.selects().length, 0); assert.equal(h.calls.length, 0) } finally { h.dispose() }
})
