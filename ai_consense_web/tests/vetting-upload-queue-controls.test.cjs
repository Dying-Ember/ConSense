/* Offline mounted queue behavior and the real client timeout boundary; no actual uploads. */
const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, text, find, settle, deferred } = require('./helpers/render-controls.cjs')
const savedFile = name => ({ documentId: name, key: 'OTHER', fileName: name, role: { en: 'Tender' }, sourceRole: 'tender', parsed: true, status: 'PARSED', parseStatus: 'PARSED', warnings: [] })
const result = (overrides = {}) => ({ accepted: 1, parsed: 1, failed: 0, messages: ['Returned processing record'], ...overrides })
async function mount({ onUpload, onFiles, onProgress, timers = { setTimeout, clearTimeout }, storage = new Map(), locale = 'zh-Hans' } = {}) {
  const calls = [], state = { files: [savedFile('already-saved.docx')], fileReads: 0, progressReads: 0, stale: false }
  const store = vue.reactive({ activeProjectId: 'project-a', locale, busyMessage: '', notify() {}, setBusy(message) { this.busyMessage = message }, clearBusy() { this.busyMessage = '' }, captureLlmSelection: () => ({ profileId: null }) })
  const empty = { render: () => null }
  const run = { id: 'old-run', status: 'COMPLETED', phase: 'done', completedUnits: 1, totalUnits: 1, message: 'Done' }
  const loaded = loadVue(path.join(__dirname, '../src/views/VettingView.vue'), { boundaries: {
    'vue-i18n': require('vue-i18n'), 'vue-router': { useRouter: () => ({ push: async () => undefined }) }, '@/stores/app': { useAppStore: () => store },
    '@/composables/useLocalized': { useLocalized: () => ({ currentKey: vue.computed(() => ({ 'zh-Hans': 'zhHans', 'zh-Hant': 'zhHant', en: 'en' })[store.locale]), pick: value => typeof value === 'string' ? value : value?.[({ 'zh-Hans': 'zhHans', 'zh-Hant': 'zhHant', en: 'en' })[store.locale]] ?? value?.en ?? '' }) },
    '@/api/client': { ApiError: class ApiError extends Error {} },
    '@/api': { vettingApi: {
      sourceProgress: async () => {
        state.progressReads++
        return onProgress ? onProgress(state) : state.files.map(file => ({ id: file.documentId, fileName: file.fileName, parseStatus: file.parseStatus, parseMessage: file.parseMessage }))
      },
      files: async () => { state.fileReads++; return onFiles ? onFiles(state) : state.files }, findings: async () => [], metrics: async () => ({ total: 0 }),
      latestRunProgress: async () => run, getRun: async () => ({ ...run, sourceSetStale: state.stale }),
      getRunDisplay: async () => ({ ...run, sourceSetStale: state.stale }),
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
  }, globals: { URLSearchParams, window: { ...timers, sessionStorage: { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value) } } } })
  const i18n = loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n; i18n.global.locale.value = locale
  async function mountView() {
    const h = renderer(), app = h.createApp(loaded.default); app.use(i18n); app.mount(h.root); await settle()
    const button = key => find(h.root, node => node.tag === 'button' && text(node).trim() === i18n.global.t(key))
    return { ...h, calls, state, store, button, remount: mountView, dispose: () => app.unmount(), upload: names => find(h.root, node => node.tag === 'input' && node.props.type === 'file').props.onChange({ target: { files: names.map(name => ({ name })), value: 'chosen' } }) }
  }
  return mountView()
}

test('the queue preserves selected order and waits for each response and source refresh before the next file', async () => {
  const first = deferred(), refresh = deferred()
  const h = await mount({ onUpload: file => file.name === 'first.pdf' ? first.promise : result(), onFiles: state => state.fileReads === 2 ? refresh.promise : state.files })
  try {
    const operation = h.upload(['first.pdf', 'second.docx', 'third.docx']); await settle()
    assert.deepEqual(h.calls.map(call => call.names), [['first.pdf']], 'The original order is kept; no speculative second upload')
    assert.equal(h.calls[0].role, undefined, 'The review entry delegates source role to automatic routing')
    assert.equal(h.calls[0].scope, 'auto', 'The review entry delegates document scope to automatic routing')
    assert.equal(find(h.root, node => node.tag === 'select' && (node.children ?? []).some(child => child.tag === 'option' && child.props?.value === 'project_fact')), undefined, 'Upload does not ask the user to assign a corpus role')
    assert.equal(find(h.root, node => node.tag === 'select' && (node.children ?? []).some(child => child.tag === 'option' && child.props?.value === 'works_pss')), undefined, 'Upload does not expose excluded material categories as user choices')
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

function manualTimers() {
  let nextId = 1
  const pending = new Map()
  return {
    setTimeout(callback, delay) { const id = nextId++; pending.set(id, { callback, delay }); return id },
    clearTimeout(id) { pending.delete(id) },
    get delays() { return [...pending.values()].map(timer => timer.delay) },
    async runNext() {
      const [id, timer] = pending.entries().next().value ?? []
      assert.ok(timer, 'A retry or progress poll is scheduled')
      pending.delete(id); timer.callback(); await settle()
    }
  }
}

test('the active file shows its reported method category, physical page and changing step beside the queue count', async () => {
  const timers = manualTimers(), pending = deferred()
  let phase = 'visual', progressFailed = false
  const names = [...Array.from({ length: 7 }, (_, index) => `acknowledged-${index + 1}.pdf`), 'Annex1to6.pdf', ...Array.from({ length: 27 }, (_, index) => `queued-${index + 9}.pdf`)]
  const h = await mount({ timers, onUpload: file => file.name === 'Annex1to6.pdf' ? pending.promise : result(), onProgress: state => {
    if (progressFailed) throw Error('Progress connection unavailable')
    return [
      { id: 100, fileName: 'unrelated.pdf', parseStatus: 'PARSING', pageCount: 80, parseMessage: '第 60 / 80 页：读取文字、表格与版面' },
      { id: 101, fileName: 'Annex1to6.pdf', parseStatus: 'PARSING', pageCount: 47, parseMessage: phase === 'visual' ? '第 3 / 47 页：等待视觉识别结果 · 更新于 2026-10-23T08:00:00Z' : '第 4 / 47 页：渲染页面 · 更新于 2026-10-23T08:01:00Z' }
    ]
  } })
  const operation = h.upload(names); await settle(); await settle()
  const focus = () => find(h.root, node => node.props?.['data-vetting-current-file'] !== undefined)
  try {
    assert.equal(h.calls.length, 8)
    assert.ok(text(h.root).includes('当前第 8 / 35 份：Annex1to6.pdf'))
    assert.ok(text(h.root).includes('已收到 7 / 35 份处理结果'))
    assert.ok(focus(), 'The active source progress is directly visible at the upload summary')
    assert.match(text(focus()), /Annex1to6\.pdf/)
    assert.match(text(focus()), /处理方法：.*视觉识别/)
    assert.match(text(focus()), /具体引擎未报告/)
    assert.match(text(focus()), /当前物理页：.*3\s*\/\s*47/)
    assert.match(text(focus()), /当前步骤：.*等待视觉识别结果/)
    assert.doesNotMatch(text(focus()), /unrelated|60\s*\/\s*80|Paddle|MinerU|GPU/)
    const bar = find(h.root, node => node.tag === 'progress' && node.props.class === 'vetting-progress')
    assert.equal(bar.props.value, 7, 'The page index never becomes a completed-file or completed-page count')
    assert.equal(bar.props.max, 35)
    phase = 'render'; await timers.runNext()
    assert.match(text(focus()), /当前物理页：.*4\s*\/\s*47/)
    assert.match(text(focus()), /处理方法：.*页面渲染/)
    assert.match(text(focus()), /当前步骤：.*渲染页面/)
    assert.doesNotMatch(text(focus()), /等待视觉识别结果/)
    progressFailed = true; await timers.runNext()
    assert.match(text(focus()), /最近已报告进度/)
    assert.match(text(focus()), /当前状态未确认/)
    assert.equal(h.calls.length, 8, 'Progress observations never replay or advance uploads')
  } finally { h.dispose(); pending.resolve(result()); await operation }
})

test('a pending file never borrows an old completed row or another file method and page', async () => {
  const timers = manualTimers(), pending = deferred()
  const h = await mount({ timers, onUpload: () => pending.promise, onProgress: () => [
    { id: 90, fileName: 'pending.pdf', parseStatus: 'PARSED', pageCount: 99, parseMessage: 'Older completed source' },
    { id: 91, fileName: 'another.pdf', parseStatus: 'PARSING', pageCount: 47, parseMessage: '第 3 / 47 页：等待视觉识别结果' }
  ] })
  const operation = h.upload(['pending.pdf']); await settle()
  try {
    const focus = find(h.root, node => node.props?.['data-vetting-current-file'] !== undefined)
    assert.ok(focus)
    assert.match(text(focus), /pending\.pdf/)
    assert.match(text(focus), /后端尚未报告处理方法/)
    assert.match(text(focus), /后端尚未报告当前页码/)
    assert.match(text(focus), /等待后端报告当前文件处理进度/)
    assert.doesNotMatch(text(focus), /Older completed|another\.pdf|99|47|视觉识别/)
    assert.equal(h.calls.length, 1)
  } finally { h.dispose(); pending.resolve(result()); await operation }
})

test('a saved background parse becoming complete refreshes files once and never uploads again', async () => {
  const timers = manualTimers()
  const h = await mount({ timers, onProgress: state => {
    const parsing = state.progressReads === 1
    state.files = [{ ...savedFile('background.pdf'), parsed: !parsing, status: parsing ? 'PARSING' : 'PARSED', parseStatus: parsing ? 'PARSING' : 'PARSED' }]
    return [{ id: 91, fileName: 'background.pdf', parseStatus: state.files[0].parseStatus, parseMessage: '第 2 / 3 页：等待视觉识别结果' }]
  } })
  try {
    assert.equal(h.button('vetting.actions.run').props.disabled, true, 'The initial source list has no parsed file')
    assert.deepEqual(timers.delays, [3000])
    const initialFileReads = h.state.fileReads
    await timers.runNext()
    assert.equal(h.state.progressReads, 2)
    assert.equal(h.state.fileReads, initialFileReads + 1, 'Only the transition to a terminal state refreshes the source list')
    assert.equal(h.button('vetting.actions.run').props.disabled, false, 'The completed source is usable without another page refresh')
    assert.deepEqual(timers.delays, [], 'Polling stops when the saved parse completes')
    assert.equal(h.calls.length, 0, 'Observing a saved parse never resends the original')
  } finally { h.dispose() }
})

test('an initial progress read failure retries and recovers the saved parse without an upload queue', async () => {
  const timers = manualTimers()
  const h = await mount({ timers, onProgress: state => {
    state.files = [{ ...savedFile('recovering.pdf'), parsed: false, status: 'PARSING', parseStatus: 'PARSING' }]
    if (state.progressReads === 1) throw new Error('Backend restarting')
    return [{ id: 92, fileName: 'recovering.pdf', parseStatus: 'PARSING', parseMessage: '第 7 / 12 页：渲染页面' }]
  } })
  try {
    assert.equal(h.state.progressReads, 1)
    assert.deepEqual(timers.delays, [5000], 'The first failure retries even before any server progress was observed')
    await timers.runNext()
    assert.equal(h.state.progressReads, 2)
    assert.ok(text(h.root).includes('第 7 / 12 页：渲染页面'), 'A retry restores the saved per-page progress')
    assert.deepEqual(timers.delays, [3000], 'Successful recovery returns to the normal poll interval')
    assert.equal(h.calls.length, 0, 'Recovery performs reads only')
  } finally { h.dispose() }
  assert.deepEqual(timers.delays, [], 'Leaving the page clears the recovered poll')
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

test('a project change keeps the queue on its captured project without publishing progress into another project', async () => {
  const pending = deferred(), h = await mount({ onUpload: file => file.name === 'pending.pdf' ? pending.promise : result() })
  try {
    const operation = h.upload(['pending.pdf', 'next.docx']); await settle()
    h.store.activeProjectId = 'project-b'; await settle()
    assert.ok(!text(h.root).includes('pending.pdf'), 'The old queue is not shown in another project')
    pending.resolve(result()); await operation; await settle()
    assert.deepEqual(h.calls.map(call => ({ projectId: call.projectId, names: call.names })), [
      { projectId: 'project-a', names: ['pending.pdf'] }, { projectId: 'project-a', names: ['next.docx'] }
    ])
    assert.ok(!text(h.root).includes('当前第 2 / 2 份：next.docx'), 'The other project does not inherit the original queue')
    h.store.activeProjectId = 'project-a'; await settle()
    assert.ok(text(h.root).includes('已收到 2 / 2 份处理结果'))
    assert.ok(text(h.root).includes('本次队列已返回全部处理结果'))
  } finally { h.dispose() }
})

test('SPA unmount and remount while a file is pending retains the live queue and sends each file once', async () => {
  const pending = deferred(), first = await mount({ onUpload: file => file.name === 'pending.pdf' ? pending.promise : result() })
  const operation = first.upload(['pending.pdf', 'next.docx']); await settle()
  first.dispose()
  const restored = await first.remount()
  try {
    assert.ok(text(restored.root).includes('当前第 1 / 2 份：pending.pdf'))
    assert.ok(!text(restored.root).includes('后续文件已停止发送'), 'A live SPA queue is not demoted to an unknown journal')
    assert.equal(restored.button('vetting.actions.upload').props.disabled, true)
    await restored.upload(['duplicate.docx']); await settle()
    assert.equal(restored.calls.length, 1, 'The remounted page cannot start a duplicate queue')
    pending.resolve(result()); await operation; await settle()
    assert.deepEqual(restored.calls.map(call => call.names), [['pending.pdf'], ['next.docx']])
    assert.ok(text(restored.root).includes('已收到 2 / 2 份处理结果'))
    assert.ok(restored.state.files.some(file => file.fileName === 'next.docx'))
    assert.equal(restored.store.busyMessage, '')
  } finally { restored.dispose(); pending.resolve(result()); await operation }
})

test('SPA remount during source refresh still waits for that refresh before sending the next file', async () => {
  const refresh = deferred(), first = await mount({ onFiles: state => state.fileReads === 2 ? refresh.promise : state.files })
  const operation = first.upload(['first.docx', 'second.docx']); await settle()
  assert.equal(first.calls.length, 1)
  first.dispose()
  const restored = await first.remount()
  try {
    assert.equal(restored.calls.length, 1, 'Mounting a page neither replays the first file nor advances the queue')
    assert.ok(text(restored.root).includes('已收到 1 / 2 份处理结果'))
    refresh.resolve(restored.state.files); await operation; await settle()
    assert.deepEqual(restored.calls.map(call => call.names), [['first.docx'], ['second.docx']])
    assert.ok(text(restored.root).includes('已收到 2 / 2 份处理结果'))
  } finally { restored.dispose(); refresh.resolve(restored.state.files); await operation }
})

test('all locales explain that the current file can still be parsing and the result count is not a quality pass', async () => {
  for (const [locale, note, unknown] of [['zh-Hans', '完成数不代表质量通过', '后端尚未报告处理方法'], ['zh-Hant', '完成數不代表品質通過', '後端尚未回報處理方法'], ['en', 'the count does not establish quality', 'The backend has not reported the processing method']]) {
    const pending = deferred(), h = await mount({ locale, onUpload: () => pending.promise })
    try { const operation = h.upload(['one.pdf']); await settle(); assert.ok(text(h.root).includes(note)); assert.ok(text(find(h.root, node => node.props?.['data-vetting-current-file'] !== undefined)).includes(unknown)); pending.resolve(result()); await operation }
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


test('a hard reload restores only the interrupted journal without resending lost file handles or claiming unreceived results', async () => {
  const storage = new Map([['vetting-upload:project-a', JSON.stringify({ total: 2, completed: 0, current: 1, fileName: 'slow.pdf', failed: 0, state: 'running', messages: [] })]])
  const restored = await mount({ storage })
  try {
    assert.ok(text(restored.root).includes('slow.pdf'))
    assert.ok(text(restored.root).includes('已收到 0 / 2 份处理结果'))
    assert.ok(text(restored.root).includes('后续文件已停止发送'))
    assert.ok(text(restored.root).includes('待传文件需重新选择'))
    assert.equal(restored.calls.length, 0)
    restored.store.activeProjectId = 'another-project'; await settle()
    assert.ok(!text(restored.root).includes('slow.pdf'), 'No queue leaks between projects')
  } finally { restored.dispose() }
})
