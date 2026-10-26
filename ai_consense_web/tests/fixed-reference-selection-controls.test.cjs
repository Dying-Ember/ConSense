const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const { text, find, settle, deferred, renderer } = require('./helpers/render-controls.cjs')
const all = (node, predicate) => [...(predicate(node) ? [node] : []), ...(node.children ?? []).flatMap(child => all(child, predicate))]
const a = 'a'.repeat(64), b = 'b'.repeat(64), manifest = 'f'.repeat(64)
const refreshedManifest = '9'.repeat(64)
const selectionStatus = (selection = null) => ({ manifestSha256: manifest, availableSlSources: [{ editionId: 'official-2024', sha256: a }, { editionId: 'official-2025', sha256: b }], selection })
const libraryStatus = (overrides = {}) => ({ versions: [2021, 2022, 2023, 2024, 2025, 2026].map(year => ({ editionId: `official-${year}`, label: `SL ${year} official`, technicalPages: 3000, parsedPages: year === 2021 ? null : 42, clauseCount: null, indexedChunks: 0, status: 'partial' })), job: { status: 'idle' }, retrievalAvailable: false, ...overrides })
const saved2024 = { manifestSha256: manifest, slSources: [{ editionId: 'official-2024', sha256: a }] }
const hit = (extra = {}) => ({ id: 'authored-fixed-parent', fileLabel: 'SL2024', content: 'Literal requirement <script>stays text</script>.', pageNo: 7, sourceFamily: 'SL', sourceUnit: null, tableArtifact: null,
  payload: { fixedBaselineManifestSha256: manifest, fixedBaselineVersion: 'official-v1', originalSourceSha256: a, sourceRole: 'standard', fixedBaselineEditionId: 'official-2024', originalPageNos: [7, 8], sourceQuality: { family: 'SL' }, chapter: 'External works', observedClause: 'FWP3.1.5.030.B' }, ...extra })
const modal = { props: ['open', 'title'], emits: ['close'], setup: (props, { slots, emit }) => () => vue.h('section', { 'data-test-modal': true }, [vue.h('button', { 'data-test-close': true, onClick: () => emit('close') }, 'Close'), slots.default?.()]) }

async function mount(options = {}) {
  const writes = [], queries = [], panelMounts = [], timers = new Map(), project = vue.ref(options.projectId || 'B'), moduleName = vue.ref(options.module || 'vetting')
  let nextTimer = 0
  const fixedReferenceApi = {
    status: options.health || (async () => ({ configured: true })),
    selection: options.selection || (async () => selectionStatus()),
    save: options.save || (async (id, body) => { writes.push({ id, body: JSON.parse(JSON.stringify(body)) }); return selectionStatus(body) }),
    search: async (id, module, query) => { queries.push({ id, module, query }); return options.search ? options.search(id, module, query) : [] }
  }
  const slLibraryApi = { status: options.library || (async () => libraryStatus()), build: options.build || (async () => { writes.push({ build: true }); return {} }) }
  const globals = { setTimeout: callback => { const id = ++nextTimer; timers.set(id, callback); return id }, clearTimeout: id => timers.delete(id) }
  const loaded = loadVue(path.join(__dirname, '../src/components/FixedReferenceSelection.vue'), { boundaries: { '@/api': { fixedReferenceApi, slLibraryApi }, './AppModal.vue': { default: modal },
    './FixedBaselineEvidencePanel.vue': { __esModule: true, default: { props: ['pointer', 'locale'], setup: props => { vue.onMounted(() => panelMounts.push(JSON.parse(JSON.stringify(props.pointer)))); return () => vue.h('div', { 'data-fixed-source-panel': true }, 'Existing fixed-source evidence boundary') } } } }, globals })
  const host = renderer(), app = host.createApp({ setup: () => () => vue.h(loaded.default, { projectId: project.value, module: moduleName.value, locale: options.locale || 'en' }) })
  app.mount(host.root); await settle()
  const control = attribute => find(host.root, node => node.tag === 'button' && Object.hasOwn(node.props, attribute))
  const click = async attribute => { await control(attribute).props.onClick(); await settle() }
  return { ...host, writes, queries, panelMounts, project, module: moduleName, timers, control, click, open: () => click('data-sl-library-open'),
    query: async value => { find(host.root, node => node.tag === 'input' && Object.hasOwn(node.props, 'data-fixed-search-query')).props['onUpdate:modelValue'](value); await settle() },
    search: async () => { await find(host.root, node => node.tag === 'form').props.onSubmit({ preventDefault() {} }); await settle() },
    dispose: () => app.unmount(), poll: async () => { const [id, callback] = timers.entries().next().value; timers.delete(id); callback(); await settle() } }
}

test('edition selection saves exact manifest, SHA and project and ignores an old project response', async () => {
  const pending = deferred(), h = await mount({ projectId: 'A', selection: async id => id === 'A' ? pending.promise : selectionStatus() })
  try {
    h.project.value = 'B'; await settle(); pending.resolve(selectionStatus({ manifestSha256: manifest, slSources: [{ editionId: 'official-2024', sha256: a }] })); await settle()
    await h.open()
    find(h.root, node => node.tag === 'input' && node.props.value === b).props['onUpdate:modelValue']([b]); await settle()
    assert.match(text(h.root), /changes are not saved/)
    assert.match(text(find(h.root, node => Object.hasOwn(node.props || {}, 'data-sl-saved-scope'))), /No SL edition selected/)
    await h.click('data-sl-editions-save')
    assert.deepEqual(h.writes, [{ id: 'B', body: { manifestSha256: manifest, slSources: [{ editionId: 'official-2025', sha256: b }] } }])
    assert.match(text(h.root), /does not establish project adoption/)
    assert.match(text(find(h.root, node => Object.hasOwn(node.props || {}, 'data-sl-saved-scope'))), /SL 2025 official/)
  } finally { h.dispose() }
})

test('a stale saved edition stays readable and can be reconfirmed without changing the checkbox', async () => {
  const stale = { ...selectionStatus(saved2024), manifestSha256: refreshedManifest }
  const h = await mount({ selection: async () => stale, save: async (id, body) => { h.writes.push({ id, body }); return { ...stale, selection: body } } })
  try {
    await h.open()
    assert.match(text(find(h.root, node => Object.hasOwn(node.props || {}, 'data-sl-saved-scope'))), /Needs reconfirmation: SL 2024 official/)
    assert.match(text(find(h.root, node => Object.hasOwn(node.props || {}, 'data-sl-selection-stale'))), /Review and save the project editions/)
    assert.doesNotMatch(text(h.root), /Queries still use the saved scope/)
    assert.equal(h.control('data-sl-editions-save').props.disabled, false)
    await h.click('data-sl-editions-save')
    assert.deepEqual(JSON.parse(JSON.stringify(h.writes)), [{ id: 'B', body: { manifestSha256: refreshedManifest, slSources: [{ editionId: 'official-2024', sha256: a }] } }])
    assert.match(text(find(h.root, node => Object.hasOwn(node.props || {}, 'data-sl-saved-scope'))), /Saved: SL 2024 official/)
  } finally { h.dispose() }
})

test('save and search re-read the current manifest after a backend restart', async () => {
  const stale = { ...selectionStatus(saved2024), manifestSha256: refreshedManifest }
  let reads = 0
  const h = await mount({ selection: async () => ++reads < 3 ? selectionStatus(saved2024) : stale,
    save: async (id, body) => { h.writes.push({ id, body }); return { ...stale, selection: body } }, library: async () => libraryStatus({ retrievalAvailable: true }) })
  try {
    await h.open()
    assert.equal(h.control('data-sl-editions-save').props.disabled, false, 'The visible saved choice can be sent again')
    await h.click('data-sl-editions-save')
    assert.equal(h.writes[0].body.manifestSha256, refreshedManifest)
    assert.match(text(find(h.root, node => Object.hasOwn(node.props || {}, 'data-sl-saved-scope'))), /Saved: SL 2024 official/)
  } finally { h.dispose() }

  reads = 0
  const query = await mount({ selection: async () => ++reads < 3 ? selectionStatus(saved2024) : stale, library: async () => libraryStatus({ retrievalAvailable: true }) })
  try {
    await query.open(); await query.query('COM7.D010.9'); await query.search()
    assert.deepEqual(query.queries, [])
    assert.match(text(query.root), /The SL library changed. Review and save the project editions/)
    assert.match(text(find(query.root, node => Object.hasOwn(node.props || {}, 'data-sl-saved-scope'))), /Needs reconfirmation: SL 2024 official/)
  } finally { query.dispose() }
})

test('a failed search reloads a changed selection and gives an English reconfirmation action', async () => {
  let reads = 0
  const stale = { ...selectionStatus(saved2024), manifestSha256: refreshedManifest }
  const h = await mount({ selection: async () => ++reads < 4 ? selectionStatus(saved2024) : stale, library: async () => libraryStatus({ retrievalAvailable: true }),
    search: async () => { throw new Error('服务内部错误，请稍后重试') } })
  try {
    await h.open(); await h.query('COM7.D010.9'); await h.search()
    assert.equal(h.queries.length, 1)
    assert.match(text(h.root), /The SL library changed. Review and save the project editions/)
    assert.doesNotMatch(text(h.root), /服务内部错误/)
  } finally { h.dispose() }
})

test('all six source states and explicit build remain available before the fixed manifest is configured', async () => {
  const build = deferred(), h = await mount({ health: async () => ({ configured: false }), build: async () => { h.writes.push({ build: true }); return build.promise } })
  try {
    assert.ok(h.control('data-sl-library-open')); assert.deepEqual(h.writes, [])
    await h.open()
    const table = find(h.root, node => Object.hasOwn(node.props || {}, 'data-sl-library-catalog'))
    assert.equal(all(table, node => node.tag === 'tr').length, 7)
    assert.match(text(table), /SL 2021 official[\s\S]*Unknown[\s\S]*SL 2026 official/)
    assert.match(text(h.root), /application edition catalog is not ready/)
    const first = h.control('data-sl-library-build').props.onClick(); await settle()
    assert.equal(h.control('data-sl-library-build').props.disabled, true)
    await h.control('data-sl-library-build').props.onClick(); assert.equal(h.writes.length, 1)
    build.resolve({ accepted: true }); await first; await settle()
    assert.equal(h.writes.length, 1)
  } finally { h.dispose() }
})

test('official SL names stay readable without inferred months in both shared reference surfaces', async () => {
  const official = [
    ['sl2022-original', 'SL2022 Original', 'May 2022'],
    ['sl2022-batch01', 'SL2022 Batch 1', 'April 2023'],
    ['sl2022-batch02-rev1', 'SL2022 Batch 2 revision 1', 'April 2024'],
    ['sl2022-batch03', 'SL2022 Batch 3', 'April 2025'],
    ['sl2022-batch04', 'SL2022 Batch 4', 'April 2026'],
    ['sl2026-original', 'SL2026 Original', 'July 2026']
  ]
  const sources = official.map(([editionId, name, month], index) => ({ editionId, sha256: String(index + 1).repeat(64), label: `${name} (${month})` }))
  const selected = { manifestSha256: manifest, slSources: [sources[5]] }
  const result = hit()
  result.payload.originalSourceSha256 = sources[5].sha256
  result.payload.fixedBaselineEditionId = sources[5].editionId
  for (const module of ['vetting', 'advisory']) {
    const h = await mount({ module, selection: async () => ({ manifestSha256: manifest, availableSlSources: sources, selection: selected }),
      library: async () => libraryStatus({ versions: sources.map(source => ({ ...source, technicalPages: 3000, status: 'indexed' })), retrievalAvailable: true }), search: async () => [result] })
    try {
      await h.open()
      const catalog = text(find(h.root, node => Object.hasOwn(node.props || {}, 'data-sl-library-catalog')))
      for (const [, name, month] of official) { assert.match(catalog, new RegExp(name)); assert.doesNotMatch(catalog, new RegExp(month)) }
      assert.match(text(find(h.root, node => Object.hasOwn(node.props || {}, 'data-sl-saved-scope'))), /Saved: SL2026 Original/)
      await h.query('COM7.D010.9'); await h.search()
      assert.match(text(find(h.root, node => Object.hasOwn(node.props || {}, 'data-fixed-search-hit'))), /^SL2026 Original/)
      assert.deepEqual(h.queries, [{ id: 'B', module, query: 'COM7.D010.9' }])
    } finally { h.dispose() }
  }
})

test('progress polls without overlapping GETs and unmount clears future polling', async () => {
  let calls = 0
  const pending = deferred(), running = libraryStatus({ job: { status: 'running', stage: 'parsing', currentEdition: 'official-2024', processedPages: 19, totalPages: 18000, message: 'Reading original technical pages' } })
  const h = await mount({ library: async () => ++calls <= 2 ? running : pending.promise })
  try {
    await h.open(); assert.equal(h.control('data-sl-library-build').props.disabled, true)
    assert.match(text(h.root), /19 \/ 18000/); assert.match(text(h.root), /Reading original technical pages/)
    await h.poll(); const before = calls
    await h.click('data-sl-library-refresh'); assert.equal(calls, before)
    h.dispose(); assert.equal(h.timers.size, 0)
    pending.resolve(libraryStatus({ job: { status: 'completed' }, retrievalAvailable: true })); await settle(); assert.equal(h.timers.size, 0)
  } finally { if (h.timers.size) h.dispose() }
})

test('failed build is retained through status polling and never automatically retried', async () => {
  let builds = 0
  const h = await mount({ build: async () => { ++builds; throw new Error('Original build request failed') } })
  try {
    await h.open(); await h.click('data-sl-library-build'); assert.match(text(h.root), /Original build request failed/)
    await h.poll(); assert.match(text(h.root), /Original build request failed/); assert.equal(builds, 1)
    await h.click('data-test-close'); assert.equal(h.timers.size, 0)
  } finally { h.dispose() }
})

test('unknown totals do not become zero and malformed status does not claim readiness', async () => {
  let malformed = false
  const h = await mount({ library: async () => malformed ? { ...libraryStatus(), job: { status: 'running', stage: 7 } } : libraryStatus({ job: { status: 'running', processedPages: null, totalPages: null } }) })
  try {
    await h.open(); assert.match(text(h.root), /Unknown \/ Unknown/); assert.equal(find(h.root, node => node.tag === 'progress'), undefined)
    malformed = true; await h.poll(); assert.match(text(h.root), /Invalid SL library status/)
    assert.doesNotMatch(text(h.root), /Library retrieval available/)
  } finally { h.dispose() }
})

test('a stale save cannot overwrite the displayed scope after a project ABA switch', async () => {
  const pending = deferred(), h = await mount({ save: async () => pending.promise })
  try {
    await h.open(); find(h.root, node => node.tag === 'input' && node.props.value === a).props['onUpdate:modelValue']([a]); await settle()
    const save = h.control('data-sl-editions-save').props.onClick(); await settle()
    h.project.value = 'C'; await settle(); h.project.value = 'B'; await settle()
    pending.resolve(selectionStatus({ manifestSha256: manifest, slSources: [{ editionId: 'official-2024', sha256: a }] })); await save; await settle()
    assert.match(text(find(h.root, node => Object.hasOwn(node.props || {}, 'data-sl-saved-scope'))), /No SL edition selected/)
  } finally { h.dispose() }
})

test('the real API binds selection to project and sends only one explicit empty-body library build', async () => {
  const calls = [], api = Object.fromEntries(['get', 'post', 'put'].map(method => [method, async (...args) => { calls.push(JSON.parse(JSON.stringify({ method, args }))); return {} }]))
  const loaded = loadTypeScript(path.join(__dirname, '../src/api/index.ts'), { dependencies: { './client': { api }, './source-locations': {}, './types': {} } })
  await loaded.slLibraryApi.status(); await loaded.slLibraryApi.build()
  await loaded.fixedReferenceApi.save('project/A', { manifestSha256: manifest, slSources: [{ editionId: 'official-2025', sha256: b }] })
  await loaded.fixedReferenceApi.search('project/A', 'advisory', 'literal clause')
  assert.equal(calls[0].args[0], '/oer/sl-library/status'); assert.equal(calls[1].args[0], '/oer/sl-library/build'); assert.deepEqual(calls[1].args[1], {})
  assert.equal(calls[2].args[0], '/oer/fixed-baseline/projects/project%2FA/selection')
  assert.deepEqual(calls[2].args[1], { manifestSha256: manifest, slSources: [{ editionId: 'official-2025', sha256: b }] })
  assert.equal(calls[3].args[0], '/oer/fixed-baseline/search'); assert.deepEqual(calls[3].args[1], { projectId: 'project/A', module: 'advisory', query: 'literal clause', limit: 10 })
})

test('closing and reopening during a save cannot read back an old saved scope', async () => {
  const pending = deferred(), oldSelection = { manifestSha256: manifest, slSources: [{ editionId: 'official-2024', sha256: a }] }
  const newSelection = { manifestSha256: manifest, slSources: [{ editionId: 'official-2025', sha256: b }] }
  let reads = 0, saved = oldSelection
  const h = await mount({ selection: async () => { ++reads; return selectionStatus(saved) }, save: async () => { await pending.promise; saved = newSelection; return selectionStatus(saved) } })
  try {
    await h.open(); find(h.root, node => node.tag === 'input' && node.props.value === b).props['onUpdate:modelValue']([b]); await settle()
    const saving = h.control('data-sl-editions-save').props.onClick(); await settle(); const beforeReopen = reads
    await h.click('data-test-close'); await h.open(); assert.equal(reads, beforeReopen, 'No project selection GET may overlap a pending write')
    pending.resolve(); await saving; await settle()
    assert.match(text(find(h.root, node => Object.hasOwn(node.props || {}, 'data-sl-saved-scope'))), /SL 2025 official/)
    await h.click('data-sl-library-refresh'); assert.equal(reads, beforeReopen + 1)
    assert.match(text(find(h.root, node => Object.hasOwn(node.props || {}, 'data-sl-saved-scope'))), /SL 2025 official/)
  } finally { h.dispose() }
})

test('refreshing library progress and reopening preserves unsaved edition choices', async () => {
  let reads = 0, statusReads = 0
  const h = await mount({ selection: async () => { ++reads; return selectionStatus({ manifestSha256: manifest, slSources: [{ editionId: 'official-2024', sha256: a }] }) }, library: async () => { ++statusReads; return libraryStatus() } })
  try {
    await h.open(); find(h.root, node => node.tag === 'input' && node.props.value === b).props['onUpdate:modelValue']([b]); await settle()
    const selectionReads = reads, progressReads = statusReads
    await h.click('data-sl-library-refresh'); await h.click('data-test-close'); await h.open()
    assert.ok(reads > selectionReads); assert.ok(statusReads > progressReads)
    assert.equal(h.control('data-sl-editions-save').props.disabled, false); assert.match(text(h.root), /changes are not saved/)
    assert.match(text(find(h.root, node => Object.hasOwn(node.props || {}, 'data-sl-saved-scope'))), /SL 2024 official/)
    await h.click('data-sl-editions-save')
    assert.equal(h.writes[0].body.slSources[0].editionId, 'official-2025')
  } finally { h.dispose() }
})

test('actual backend stages and edition statuses have readable labels in every locale', async () => {
  const values = [
    ['awaiting_build', 'Waiting to start', '等待开始建库', '等待開始建庫'],
    ['verify_originals_and_scope', 'Checking originals and technical scope', '核验原件与技术范围', '核驗原件與技術範圍'],
    ['fresh_native_read', 'Reading original text', '读取原件文字', '讀取原件文字'],
    ['native_clause_preparation', 'Organizing available text and clauses', '整理可用文字与条款', '整理可用文字與條款'],
    ['embedding_and_persistence', 'Indexing and saving', '建立并保存索引', '建立並儲存索引'],
    ['retrieval_ready', 'Retrieval ready', '检索已就绪', '檢索已就緒'],
    ['original_available', 'Original available', '原件已就绪', '原件已就緒'],
    ['prepared', 'Available text and clauses prepared', '可用文字与条款已整理', '可用文字與條款已整理'],
    ['indexed', 'Indexed', '已建立索引', '已建立索引'],
    ['interrupted', 'Interrupted — resume explicitly', '已中断，需手动继续', '已中斷，需手動繼續']
  ]
  for (const [index, locale] of ['en', 'zh-Hans', 'zh-Hant'].entries()) {
    const h = await mount({ locale, library: async () => libraryStatus({ versions: values.map(([status], i) => ({ editionId: `official-${i}`, label: `SL ${i} official`, status })), job: { status: 'running', stage: 'fresh_native_read' } }) })
    try { await h.open(); for (const [value, ...labels] of values) { assert.ok(text(h.root).includes(labels[index]), `${locale}: ${value}`); if (value.includes('_')) assert.ok(!text(h.root).includes(value)) } }
    finally { h.dispose() }
  }
})

test('the common topbar exposes the same exact saved editions in both Vetting and Advice', async () => {
  for (const module of ['vetting', 'advice']) {
    const timers = new Map(), queries = [], store = vue.reactive({ activeProjectId: 'P', locale: 'en', health: null, projects: [], activeProject: null })
    const fixedReferenceApi = { status: async () => ({ configured: true }), selection: async id => { assert.equal(id, 'P'); return selectionStatus({ manifestSha256: manifest, slSources: [{ editionId: 'official-2025', sha256: b }] }) }, search: async (id, module, query) => { queries.push({ id, module, query }); return [] } }
    const loaded = loadVue(path.join(__dirname, '../src/components/AppTopbar.vue'), { boundaries: {
      '@/api': { fixedReferenceApi, slLibraryApi: { status: async () => libraryStatus({ retrievalAvailable: true }) } },
      'vue-i18n': { useI18n: () => ({ t: value => value }) }, 'vue-router': { useRoute: () => ({ name: module }) },
      '@/stores/app': { useAppStore: () => store }, '@/composables/useLocalized': { useLocalized: () => ({ pick: value => value?.en || '' }) },
      '@/i18n': { SUPPORTED_LOCALES: ['en'], LOCALE_LABELS: { en: 'English' } }, '@/i18n/inspection': { inspectionWords: () => ({ title: 'Inspection' }) },
      './AppIcon.vue': { default: { render: () => null } }, './AppModal.vue': { default: modal }
    }, globals: { setTimeout: callback => { timers.set(1, callback); return 1 }, clearTimeout: id => timers.delete(id) } })
    const h = renderer(), app = h.createApp(loaded.default); app.mount(h.root); await settle()
    try {
      assert.ok(find(h.root, node => Object.hasOwn(node.props || {}, 'data-sl-library-open')), module)
      const summary = text(find(h.root, node => Object.hasOwn(node.props || {}, 'data-sl-saved-scope')))
      assert.match(summary, /SL 2025 official/); assert.doesNotMatch(summary, /[a-f0-9]{64}/)
      find(h.root, node => Object.hasOwn(node.props || {}, 'data-sl-library-open')).props.onClick(); await settle()
      find(h.root, node => node.tag === 'input' && Object.hasOwn(node.props, 'data-fixed-search-query')).props['onUpdate:modelValue']('literal'); await settle()
      await find(h.root, node => node.tag === 'form').props.onSubmit({ preventDefault() {} }); await settle()
      assert.deepEqual(queries, [{ id: 'P', module: module === 'advice' ? 'advisory' : 'vetting', query: 'literal' }])
    } finally { app.unmount(); assert.equal(timers.size, 0) }
  }
})

test('search uses saved scope despite unsaved choices and opens only a strictly pinned source on request', async () => {
  const h = await mount({ selection: async () => selectionStatus(saved2024), library: async () => libraryStatus({ retrievalAvailable: true }), search: async () => [hit()] })
  try {
    await h.open(); find(h.root, node => node.tag === 'input' && node.props.value === b).props['onUpdate:modelValue']([b]); await settle()
    await h.query('  contractor requirement  '); await h.search()
    assert.deepEqual(h.queries, [{ id: 'B', module: 'vetting', query: 'contractor requirement' }])
    const row = find(h.root, node => Object.hasOwn(node.props || {}, 'data-fixed-search-hit'))
    assert.match(text(row), /SL 2024 official/); assert.match(text(row), /External works/); assert.match(text(row), /FWP3\.1\.5\.030\.B/); assert.match(text(row), /7, 8/)
    assert.match(text(row), /<script>stays text<\/script>/); assert.equal(find(row, node => node.tag === 'script'), undefined)
    assert.doesNotMatch(text(row), /[a-f0-9]{64}|authored-fixed-parent/); assert.equal(h.panelMounts.length, 0)
    await h.click('data-fixed-search-view'); await settle(); assert.equal(h.panelMounts.length, 1)
    assert.deepEqual(h.panelMounts[0], { evidenceBackend: 'fixed_development_baseline', parentId: 'authored-fixed-parent', manifestSha256: manifest,
      baselineVersion: 'official-v1', sourceFamily: 'SL', sourceRole: 'standard', originalSourceSha256: a, editionId: 'official-2024', originalPageNos: [7, 8], pageNo: 7,
      projectApplicability: 'unconfirmed', qualifiersComplete: 'unknown', sourceQualityAccepted: false })
    await h.click('data-test-close'); assert.equal(find(h.root, node => Object.hasOwn(node.props || {}, 'data-fixed-source-panel')), undefined)
  } finally { h.dispose() }
})

test('search remains disabled without a configured saved available scope and during a save', async () => {
  for (const option of [{ health: async () => ({ configured: false }) }, { selection: async () => selectionStatus() }, { library: async () => libraryStatus() }]) {
    const h = await mount({ selection: async () => selectionStatus(saved2024), library: async () => libraryStatus({ retrievalAvailable: true }), ...option })
    try { await h.open(); await h.query('question'); assert.equal(h.control('data-fixed-search-submit').props.disabled, true); await h.search(); assert.equal(h.queries.length, 0) }
    finally { h.dispose() }
  }
  const pending = deferred(), h = await mount({ selection: async () => selectionStatus(saved2024), library: async () => libraryStatus({ retrievalAvailable: true }), save: async () => pending.promise })
  try {
    await h.open(); await h.query('question'); find(h.root, node => node.tag === 'input' && node.props.value === b).props['onUpdate:modelValue']([b]); await settle()
    const saving = h.control('data-sl-editions-save').props.onClick(); await settle(); assert.equal(h.control('data-fixed-search-submit').props.disabled, true)
    await h.search(); assert.equal(h.queries.length, 0); pending.resolve(selectionStatus(saved2024)); await saving
  } finally { h.dispose() }
})

test('late search results cannot cross project ABA, module changes, edits or a saved scope change', async () => {
  for (const change of ['project', 'module', 'query', 'save']) {
    const pending = deferred(), h = await mount({ selection: async () => selectionStatus(saved2024), library: async () => libraryStatus({ retrievalAvailable: true }), search: async () => pending.promise })
    try {
      await h.open(); await h.query('question'); const searching = find(h.root, node => node.tag === 'form').props.onSubmit({ preventDefault() {} }); await settle()
      if (change === 'project') { h.project.value = 'C'; await settle(); h.project.value = 'B'; await settle() }
      if (change === 'module') { h.module.value = 'advisory'; await settle() }
      if (change === 'query') await h.query('different question')
      if (change === 'save') { find(h.root, node => node.tag === 'input' && node.props.value === b).props['onUpdate:modelValue']([b]); await settle(); await h.click('data-sl-editions-save') }
      pending.resolve([hit()]); await searching; await settle()
      assert.equal(find(h.root, node => Object.hasOwn(node.props || {}, 'data-fixed-search-hit')), undefined, change); assert.equal(h.panelMounts.length, 0)
    } finally { h.dispose() }
  }
})

test('search rejects mismatched source pins and keeps GCC/GCT references with at most ten displayed results', async () => {
  const invalid = [result => { result.payload.fixedBaselineManifestSha256 = '0'.repeat(64) }, result => { result.payload.originalSourceSha256 = b; result.payload.fixedBaselineEditionId = 'official-2025' },
    result => { result.payload.sourceRole = 'tender' }, result => { result.payload.originalPageNos = [0] }, result => { result.payload.sourceQuality.family = 'GCC' }]
  for (const mutate of invalid) {
    const h = await mount({ selection: async () => selectionStatus(saved2024), library: async () => libraryStatus({ retrievalAvailable: true }), search: async () => { const result = hit(); mutate(result); return [result] } })
    try { await h.open(); await h.query('question'); await h.search(); assert.match(text(h.root), /do not match the saved source scope/); assert.equal(h.panelMounts.length, 0) }
    finally { h.dispose() }
  }
  const h = await mount({ selection: async () => selectionStatus(saved2024), library: async () => libraryStatus({ retrievalAvailable: true }), search: async () => Array.from({ length: 12 }, (_, i) => {
    const result = hit({ id: `GCC-${i}`, fileLabel: 'General Conditions of Contract', sourceFamily: 'GCC' }); result.payload.sourceQuality.family = 'GCC'; delete result.payload.fixedBaselineEditionId; result.payload.originalSourceSha256 = 'e'.repeat(64); return result
  }) })
  try { await h.open(); await h.query('question'); await h.search(); assert.equal(all(h.root, node => Object.hasOwn(node.props || {}, 'data-fixed-search-hit')).length, 10); assert.match(text(h.root), /General Conditions of Contract/) }
  finally { h.dispose() }
})
