/* Synthetic mounted UI checks only; this does not establish source or business quality. */
const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, text, find, settle, deferred } = require('./helpers/render-controls.cjs')

const visibleText = node => node.tag === 'details' && !node.props?.open
  ? (node.children ?? []).filter(child => child.tag === 'summary').map(visibleText).join(' ')
  : node.text || (node.children ?? []).map(visibleText).join(' ')
const all = (node, predicate) => [ ...(predicate(node) ? [node] : []), ...(node.children ?? []).flatMap(child => all(child, predicate)) ]
const closedAncestor = node => { for (let parent = node.parent; parent; parent = parent.parent) if (parent.tag === 'details' && !parent.props?.open) return true; return false }
const finding = (code, status) => ({ code, status, group: 'reference', scope: 'intra', severity: 'low', types: [], title: { en: code }, body: {}, impact: {}, suggestion: {}, evidence: [], fileKey: 'SCC' })
const file = (key, overrides = {}) => ({ key, role: { en: 'Tender document' }, sourceRole: 'tender', fileName: `${key}.pdf`, status: 'PARSED', parsed: true, pageCount: 1, warnings: [], ...overrides })
const completed = () => ({ id: 'run-1', status: 'COMPLETED', phase: 'done', completedUnits: 2, totalUnits: 2, message: 'Raw worker completion message', recoveryStatus: 'COMPLETED', reportSnapshotId: 'a'.repeat(64),
  modelIdentity: { profileId: 'saved-minimax-profile', provider: 'saved-provider-protocol', model: 'Saved MiniMax model' },
  coverage: { reviewedDocuments: 1, totalDocuments: 2, warnings: ['Raw canonical UTF16 parser warning'], documents: [] } })

async function mount({ locale = 'zh-Hans', files = [], findings = [], metrics = { total: 10 }, job = completed(), pendingDetails = false, pendingRefresh = false } = {}) {
  const notifications = []
  const store = vue.reactive({ activeProjectId: 'project-a', locale, notify(message) { notifications.push(message) }, setBusy() {}, clearBusy() {}, captureLlmSelection: () => ({ profileId: null }) })
  const deferredDetail = deferred()
  let detailReads = 0
  const empty = { render: () => null }
  const loaded = loadVue(path.join(__dirname, '../src/views/VettingView.vue'), { boundaries: {
    'vue-i18n': require('vue-i18n'),
    'vue-router': { useRouter: () => ({ push: async () => undefined }) },
    '@/stores/app': { useAppStore: () => store },
    '@/composables/useLocalized': { useLocalized: () => ({ currentKey: vue.computed(() => locale === 'en' ? 'en' : locale === 'zh-Hant' ? 'zhHant' : 'zhHans'), pick: value => typeof value === 'string' ? value : value?.en ?? '' }) },
    '@/api/client': { ApiError: class ApiError extends Error {} },
    '@/api': { vettingApi: { files: async () => files, findings: async () => findings, metrics: async () => metrics,
      sourceProgress: async () => files.map(source => ({ id: source.documentId ?? source.key, fileName: source.fileName, parseStatus: source.parseStatus ?? source.status, parseMessage: source.parseMessage, pageCount: source.pageCount })),
      latestRunProgress: async () => job, getRun: async () => { detailReads++; return pendingDetails || pendingRefresh && detailReads > 1 ? deferredDetail.promise : job },
      exportReport: async () => undefined, evidence: async () => ({ located: true, items: [] }), startRun: async () => job } },
    '@/advice/reviewHandoff': { reviewHandoffCopy: {} },
    '@/components/AppIcon.vue': { default: empty },
    '@/components/AppModal.vue': { default: { props: ['open', 'title'], setup: (props, { slots }) => () => props.open ? vue.h('div', { class: 'test-modal' }, slots.default?.()) : null } },
    '@/components/CanonicalEvidencePanel.vue': { default: empty },
    '@/components/FixedBaselineEvidencePanel.vue': { default: empty },
    '@/components/AdviceSourceReader.vue': { default: empty }
  }, globals: { URLSearchParams, window: { setTimeout, clearTimeout } } })
  const i18n = loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n
  i18n.global.locale.value = locale
  const h = renderer(), app = h.createApp(loaded.default)
  app.use(i18n); app.mount(h.root); await settle()
  return { ...h, notifications, dispose: () => app.unmount(), resolveDetails: async () => { deferredDetail.resolve(job); await settle() }, openSources: async () => {
    const button = find(h.root, node => node.tag === 'button' && text(node).trim() === i18n.global.t('vetting.actions.source'))
    assert.ok(button); button.props.onClick(); await settle()
  } }
}

test('reader summary keeps snapshot documents, total findings and filtered pending findings separate in all locales', async () => {
  for (const [locale, coverageText, findingText, pendingText, riskText] of [
    ['zh-Hans', '1 / 2 份文件', '审查意见共 10 条', '当前筛选列表 4 条，其中 3 条待人工处理', '不代表整套文件已审查完整'],
    ['zh-Hant', '1 / 2 份文件', '審查意見共 10 條', '目前篩選清單 4 條，其中 3 條待人工處理', '不代表整套文件已審查完整'],
    ['en', '1 / 2 documents', '10 review findings in total', '4 in the current filtered list, including 3 awaiting human handling', 'not necessarily been fully reviewed']
  ]) {
    const h = await mount({ locale, files: [file('SCC'), file('PRE'), file('APA', { exclusionReason: 'drawing_outside_scope', status: 'EXCLUDED', parsed: false }), file('APC', { documentScope: 'drawing', parseStatus: 'EXCLUDED', parsed: false })], findings: [finding('F1', 'OPEN'), finding('F2', 'HANDLED'), finding('F3', 'ASSIGNED'), finding('F4', 'future_state')] })
    try {
      const main = visibleText(h.root)
      for (const expected of [coverageText, findingText, pendingText, riskText, 'Saved MiniMax model']) assert.ok(main.includes(expected), expected)
      for (const raw of ['a'.repeat(64), 'saved-provider-protocol', 'saved-minimax-profile', 'Raw canonical UTF16 parser warning', 'Raw worker completion message']) {
        assert.ok(text(h.root).includes(raw), `Raw record retained: ${raw}`)
        assert.ok(!main.includes(raw), `Raw record initially folded: ${raw}`)
        assert.ok(all(h.root, node => node.text?.includes(raw)).every(closedAncestor), `Raw record has a closed details ancestor: ${raw}`)
      }
      const coverage = find(h.root, node => node.tag === 'details' && String(node.props?.class).includes('coverage-panel'))
      assert.ok(coverage); assert.ok(!coverage.props.open, 'Warnings do not force the coverage panel open')
    } finally { h.dispose() }
  }
})

test('explicitly excluded drawings are neutral, excluded from parse warnings and retain raw records in source details', async () => {
  const h = await mount({ job: null, files: [
    file('APA', { exclusionReason: 'drawing_outside_scope', documentScope: 'drawing', status: 'EXCLUDED', sourceRole: 'unknown', parsed: false, parseMessage: 'Legacy parser warning APA', warnings: ['Raw drawing warning APA'], textChars: 0, ocrUsed: false }),
    file('APC', { documentScope: 'drawing', status: 'EXCLUDED', sourceRole: 'unknown', parsed: false, parseMessage: 'Legacy parser warning APC' })
  ] })
  try {
    assert.ok(visibleText(h.root).includes('2 份图纸已按范围排除，不参与审查。'))
    assert.ok(!find(h.root, node => node.tag === 'details' && String(node.props?.class).includes('coverage-panel')), 'Expected drawing exclusion does not create a parsing failure panel')
    await h.openSources()
    const modal = find(h.root, node => String(node.props?.class).split(' ').includes('test-modal'))
    assert.ok(modal, 'The source dialog opens with its inherited classes')
    assert.ok(modal.props.class.split(' ').includes('vetting-source-modal'), 'The source dialog retains the layout class passed by the view')
    const rows = all(modal, node => node.tag === 'tr').slice(1)
    assert.equal(rows.length, 2)
    for (const row of rows) {
      assert.ok(visibleText(row).includes('图纸')); assert.ok(visibleText(row).includes('已按范围排除')); assert.ok(visibleText(row).includes('不参与审查'))
      assert.ok(!visibleText(row).includes('身份待确认')); assert.ok(!visibleText(row).includes('Legacy parser warning'))
      assert.equal(find(row, node => String(node.props?.class).split(' ').includes('tag')).props.class.includes('neutral'), true)
      assert.ok(text(row).includes('unknown')); assert.ok(text(row).includes('EXCLUDED')); assert.ok(text(row).includes('Legacy parser warning'))
      assert.ok(all(row, node => node.text?.includes('Legacy parser warning')).every(closedAncestor))
    }
  } finally { h.dispose() }
})

test('unknown or failed non-drawings and drawings without an exclusion are not mistaken for completed exclusions', async () => {
  const h = await mount({ job: null, files: [
    file('failed', { status: 'FAILED', sourceRole: 'unknown', parsed: false, textChars: 0, ocrUsed: false, parseMessage: 'Ordinary parse failed' }),
    file('notdrawing', { status: 'EXCLUDED', documentScope: 'package', sourceRole: 'unknown', parsed: false, parseMessage: 'Other exclusion unresolved' }),
    file('drawing-pending', { status: 'PARSING', documentScope: 'drawing', sourceRole: 'unknown', parsed: false, parseMessage: 'Drawing exclusion not yet recorded' })
  ] })
  try {
    assert.ok(!visibleText(h.root).includes('份图纸已按范围排除'))
    assert.ok(visibleText(h.root).includes('仍有覆盖或来源限制待人工核对'))
    assert.ok(text(h.root).includes('Ordinary parse failed'))
    await h.openSources()
    const modal = find(h.root, node => String(node.props?.class).split(' ').includes('test-modal'))
    const visible = visibleText(modal)
    for (const label of ['解析失败', '未纳入解析', '解析中', '用途尚未确定']) assert.ok(visible.includes(label), label)
    for (const raw of ['FAILED', 'EXCLUDED', 'PARSING']) { assert.ok(text(modal).includes(raw)); assert.ok(!visible.includes(raw), raw) }
    assert.ok(!visible.includes('已按范围排除'))
  } finally { h.dispose() }
})

test('failure, rerun and changed-source risks remain visible and continue to block export', async () => {
  for (const error of ['Actual failed run', null]) {
    const h = await mount({ job: { ...completed(), status: 'FAILED', message: 'Actual failure message', error, recoveryStatus: 'NEEDS_RERUN', sourceSetStale: true } })
    try {
      const main = visibleText(h.root)
      assert.ok(main.includes('Actual failure message'), 'A failure reason in message stays visible even without error')
      if (error) assert.ok(main.includes(error))
      assert.ok(main.includes('无法安全恢复，需重新审查'))
      assert.ok(main.includes('源集') && main.includes('变化'))
      assert.ok(!main.includes('审查意见共'))
      const exportButton = find(h.root, node => node.tag === 'button' && text(node).trim() === '导出审查报告')
      assert.equal(exportButton.props.disabled, true)
    } finally { h.dispose() }
  }
})

test('known technical failures have one localized reader alert, retain raw evidence and do not repeat it in a toast', async () => {
  const failures = [
    'Model response source attribution invalid: [{"quotedSourceBindings":[{"chunkId":"canonical:' + 'a'.repeat(64) + '"}]}]',
    'Structured output must be one complete JSON value without duplicate keys or surrounding text.'
  ]
  for (const [locale, runLabel, exportLabel, attribution, format] of [
    ['zh-Hans', '运行审查', '导出审查报告', '条款归属未通过来源核对', '未符合完整、单一结果的格式要求'],
    ['zh-Hant', '執行審查', '匯出審查報告', '條款歸屬未通過來源核對', '未符合完整、單一結果的格式要求'],
    ['en', 'Run vetting', 'Export report', 'clause attribution could not be verified', 'did not meet the required result format']
  ]) {
    for (const [index, raw] of failures.entries()) {
      const job = { ...completed(), status: 'FAILED', message: raw, error: raw, recoveryStatus: 'NEEDS_RERUN' }
      const savedBefore = JSON.stringify(job), h = await mount({ locale, job, files: [file('SCC')] })
      try {
        await find(h.root, node => node.tag === 'button' && text(node).trim() === runLabel).props.onClick(); await settle()
        const main = visibleText(h.root), explanation = index ? format : attribution
        assert.equal(all(h.root, node => node.props?.role === 'alert' && text(node).includes(explanation)).length, 1)
        assert.ok(main.includes(explanation)); assert.ok(!main.includes(raw)); assert.ok(!main.includes('quotedSourceBindings'))
        assert.ok(!/[a-f0-9]{64}/i.test(main), 'Technical source identifiers stay out of the reader failure alert')
        assert.ok(text(h.root).includes(raw), 'The complete original failure remains available')
        assert.ok(all(h.root, node => node.text?.includes(raw)).every(closedAncestor), 'Raw failure data is optional technical detail')
        assert.equal(h.notifications.length, 0, 'The visible failure alert does not trigger a duplicate toast')
        assert.equal(find(h.root, node => node.tag === 'button' && text(node).trim() === exportLabel).props.disabled, true)
        assert.equal(JSON.stringify(job), savedBefore, 'Reader rendering cannot rewrite the saved failure')
      } finally { h.dispose() }
    }
  }
})

test('expanded source request states keep full input identities optional while states and missing counts remain readable', async () => {
  const requests = Array.from({ length: 28 }, (_, index) => ({ requestId: `request-${index}`,
    packetId: `topic-1-packet-${index}-input-${index.toString(16).padStart(64, '0')}-source-${'b'.repeat(64)}`,
    transportStatus: 'budget_unknown', reviewExecutionStatus: 'not_submitted_budget_unknown',
    requiredChunkIds: ['required-1', 'required-2'], missingChunkIds: ['required-2'], unknownReason: 'Source request remains unverified' }))
  for (const [locale, requestLabel, state, required, missing] of [
    ['zh-Hans', '查看来源请求状态', '未提交：预算未知', '必需片段', '缺少片段'],
    ['zh-Hant', '查看來源請求狀態', '未提交：預算未知', '必需片段', '缺少片段'],
    ['en', 'Show source request states', 'Not submitted: budget unknown', 'Required segments', 'Missing segments']
  ]) {
    const job = completed(); job.coverage.semanticTopics = [{ topicIndex: 1, topic: 'Recorded source request scope', status: 'not_submitted_budget_unknown',
      aggregateReviewStatus: 'not_submitted', sourceRequestCount: 28, pendingSourceRequestCount: 28, sourceRequests: requests,
      submittedChunkIds: [], submittedChars: 0, issueAssessments: 0, consistentAssessments: 0, insufficientContextAssessments: 0,
      acceptedFindings: 0, rejectedRecords: 0, budgetDroppedGroups: 0, partialContextGroups: 0, unresolvedSegments: 0 }]
    const savedBefore = JSON.stringify(job), h = await mount({ locale, job })
    try {
      const section = find(h.root, node => node.tag === 'summary' && text(node) === requestLabel).parent
      section.props.open = true
      for (let parent = section.parent; parent; parent = parent.parent) if (parent.tag === 'details') parent.props.open = true
      const rows = all(section, node => node.tag === 'div' && node.props?.class === 'small section-gap')
      assert.equal(rows.length, 28)
      for (const [index, row] of rows.entries()) {
        const main = visibleText(row), technical = all(row, node => node.tag === 'details')
        assert.ok(main.includes(state)); assert.ok(main.includes(`${required}: 2`)); assert.ok(main.includes(`${missing}: 1`))
        assert.ok(main.includes('Source request remains unverified')); assert.ok(!/[a-f0-9]{64}/i.test(main))
        assert.ok(text(row).includes(requests[index].packetId), 'Every complete input identity is retained')
        assert.equal(technical.length, 1); assert.ok(!technical[0].props.open)
        assert.equal(all(technical[0], node => ['button', 'a', 'input', 'select', 'textarea'].includes(node.tag)).length, 0)
      }
      const firstIdentity = find(rows[0], node => node.tag === 'details'); firstIdentity.props.open = true
      assert.ok(visibleText(rows[0]).includes(requests[0].packetId), 'One optional technical disclosure reveals the exact identity')
      assert.equal(JSON.stringify(job), savedBefore, 'Source request records are not rewritten for presentation')
    } finally { h.dispose() }
  }
})

test('progress alone cannot publish a completed summary or enable export before source verification', async () => {
  const h = await mount({ pendingDetails: true })
  try {
    assert.ok(!visibleText(h.root).includes('审查意见共'))
    assert.equal(find(h.root, node => node.tag === 'button' && text(node).trim() === '导出审查报告').props.disabled, true)
  } finally { h.dispose() }
})

test('export refresh shows terminal source verification without pretending that vetting restarted or enabling writes', async () => {
  for (const [locale, runLabel, activeLabel, exportLabel, saveLabel, waiting, background, working] of [
    ['zh-Hans', '运行审查', '正在审查…', '导出审查报告', '保存复核记录', '正在核对来源与报告记录', '任务在后台运行', '处理中…'],
    ['zh-Hant', '執行審查', '正在審查…', '匯出審查報告', '儲存覆核記錄', '正在核對來源與報告記錄', '任務在背景執行', '處理中…'],
    ['en', 'Run vetting', 'Vetting…', 'Export report', 'Save review record', 'Checking source and report records', 'The task runs in the background', 'Working…']
  ]) {
    const h = await mount({ locale, pendingRefresh: true, files: [file('SCC')], findings: [finding('F1', 'OPEN')] })
    try {
      find(h.root, node => node.tag === 'button' && String(node.props?.class).split(' ').includes('finding')).props.onClick(); await settle()
      const exportButton = find(h.root, node => node.tag === 'button' && text(node).trim() === exportLabel)
      assert.equal(exportButton.props.disabled, false)
      const downloading = exportButton.props.onClick(); await settle()
      const main = visibleText(h.root)
      assert.ok(main.includes(waiting), 'Source verification remains visible while full terminal detail is pending')
      assert.ok(!main.includes(background)); assert.ok(!main.includes(activeLabel), 'A completed task is not relabelled as actively vetting')
      assert.ok(text(exportButton).includes(working), 'Export has its own busy label')
      assert.equal(exportButton.props.disabled, true)
      assert.equal(find(h.root, node => node.tag === 'button' && text(node).trim() === runLabel).props.disabled, true)
      assert.equal(find(h.root, node => node.tag === 'button' && text(node).trim() === saveLabel).props.disabled, true)
      assert.ok(all(h.root, node => node.tag === 'textarea').every(control => control.props.disabled), 'Human review inputs remain blocked until source verification')
      assert.equal(find(h.root, node => node.tag === 'progress' && node.props?.class === 'vetting-progress'), undefined)
      await h.resolveDetails(); await downloading; await settle()
      assert.ok(!visibleText(h.root).includes(waiting)); assert.equal(exportButton.props.disabled, false)
      assert.ok(all(h.root, node => node.tag === 'textarea').some(control => !control.props.disabled), 'A verified terminal record restores the editing controls')
    } finally { h.dispose() }
  }
})

test('a missing total is displayed as unknown instead of adopting the filtered list count', async () => {
  const job = completed(); delete job.coverage
  const h = await mount({ job, metrics: null, findings: [finding('F1', 'OPEN')] })
  try { assert.ok(visibleText(h.root).includes('审查意见共 未知 条；当前筛选列表 1 条，其中 1 条待人工处理。')) }
  finally { h.dispose() }
})


test('source status labels distinguish pending parsing from purpose confirmation without assuming a missing eligibility field', async () => {
  for (const [locale, pending, unresolved, confirmable, partial, note] of [
    ['zh-Hans', '解析结果待更新', '用途尚未确定', '用途待确认', '部分解析', '后台可能仍在处理'],
    ['zh-Hant', '解析結果待更新', '用途尚未確定', '用途待確認', '部分解析', '背景可能仍在處理'],
    ['en', 'Parsing result pending update', 'Purpose not established', 'Purpose pending confirmation', 'Partially parsed', 'background processing may continue']
  ]) {
    const pins = { originalSourceSha256: 'a'.repeat(64), sourceHash: 'b'.repeat(64), routingSha256: 'c'.repeat(64) }
    const h = await mount({ locale, job: null, files: [
      file('OTHER', { ...pins, documentId: '1', fileName: 'Annex.pdf', sourceRole: 'unknown', parsed: false, status: 'IDENTITY_OBSERVED', parseStatus: 'IDENTITY_OBSERVED', roleConfirmationAvailable: true, exclusionReason: 'source_identity_unresolved' }),
      file('SCT', { ...pins, documentId: '2', fileName: 'Ready.docx', sourceRole: 'unknown', parsed: false, status: 'PARTIAL', parseStatus: 'PARTIAL', roleConfirmationAvailable: true, exclusionReason: 'source_identity_unresolved' }),
      file('NTT', { ...pins, documentId: '3', fileName: 'Old backend.docx', sourceRole: 'unknown', parsed: false, status: 'PARSED', parseStatus: 'PARSED', exclusionReason: 'source_identity_unresolved' }),
      file('GCT', { documentId: '4', fileName: 'Partial.pdf', parsed: true, status: 'PARTIAL', parseStatus: 'PARTIAL' })
    ] })
    try {
      await h.openSources()
      const modal = find(h.root, node => String(node.props?.class).split(' ').includes('test-modal'))
      const row = name => find(modal, node => node.tag === 'tr' && text(node).includes(name))
      const waiting = row('Annex.pdf'), ready = row('Ready.docx'), legacy = row('Old backend.docx'), limited = row('Partial.pdf')
      assert.ok(visibleText(waiting).includes(pending)); assert.ok(visibleText(waiting).includes(unresolved)); assert.ok(visibleText(waiting).includes(note))
      const waitingBadge = find(waiting, node => String(node.props?.class).split(' ').includes('tag'))
      assert.equal(visibleText(waitingBadge).trim(), pending); assert.ok(!waitingBadge.props.class.includes('ok'), 'Identity observation is not a completed parse')
      assert.ok(!visibleText(waiting).includes(confirmable), 'Identity observation does not imply a confirmable purpose')
      assert.ok(!find(waiting, node => node.tag === 'select'), 'Incomplete parsing cannot confirm purpose even when a stale eligibility flag is true')
      for (const raw of ['IDENTITY_OBSERVED', 'OTHER']) { assert.ok(text(waiting).includes(raw)); assert.ok(!visibleText(waiting).includes(raw), raw) }
      assert.ok(visibleText(ready).includes(partial)); assert.ok(visibleText(ready).includes(confirmable)); assert.ok(find(ready, node => node.tag === 'select'))
      assert.ok(visibleText(legacy).includes(unresolved)); assert.ok(!find(legacy, node => node.tag === 'select'), 'Missing backend eligibility is not inferred from PARSED')
      const badge = find(limited, node => String(node.props?.class).split(' ').includes('tag'))
      assert.ok(visibleText(limited).includes(partial)); assert.ok(badge.props.class.includes('warn')); assert.ok(!badge.props.class.includes('ok'), 'Partial content is not shown as a complete parse')
    } finally { h.dispose() }
  }
})
