// Runs the actual <script setup> with API and lifecycle boundaries controlled.
// No browser or backend is required; Vue's real reactivity handles project changes.
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parse } from 'vue/compiler-sfc'
import ts from 'typescript'
import * as vue from 'vue'

const source = await readFile(new URL('../src/views/VettingView.vue', import.meta.url), 'utf8')
const script = parse(source).descriptor.scriptSetup.content.replace(/^import .*\r?\n/gm, '')
const compiled = ts.transpileModule(script, { compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.None } }).outputText
const setup = new Function('deps', `
  const { computed, ref, watch, onMounted, onBeforeUnmount, useI18n, useRouter, useAppStore, useLocalized, vettingApi, ApiError, window } = deps;
  ${compiled}
  return { job, files, findings, running, exportDisabled, pollingPaused, selectedFinding, evidenceItems, reportFormat,
    uploadRole, uploadPackage, runVetting, restoreJob, retryPolling, openFinding, closeDrawer, updateStatus, exportReport,
    reviewDraft, reviewDirty, reviewSaving, reviewFailed, hasUnsavedReview, saveReview, discardReview,
    uploadDocumentScope, checklist, checklistDirty, openChecklist, saveCheck, checkDirty, restoreCheck, GROUPS, metricCards, sourceRoleBlocked, handoffDisabled };
`)

const metrics = { total: 1, reference: 1, conflict: 0, language: 0, risk: 0, crossFile: 0 }
const finding = (code, status = 'Open') => ({ code, status, runId: 'run-1', generatedRevisionSha256: 'generated', sourceSnapshotSha256: 'source', fileKey: 'NTT', types: [], title: {}, body: {}, impact: {}, suggestion: {}, evidence: [] })
const active = (id = 'run-1') => ({ id, status: 'RUNNING', phase: 'segments', completedUnits: 1, totalUnits: 2, message: 'Reviewing' })
const completed = (id = 'run-1') => ({ ...active(id), status: 'COMPLETED', completedUnits: 2, result: { total: 1, metrics, messages: [], model: 'test' } })
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
const settle = async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); await vue.nextTick() }

function harness(overrides = {}) {
  const mounted = [], unmounted = [], timers = new Map(), downloads = [], uploads = [], notifications = []
  let timerId = 0
  const store = vue.reactive({ activeProjectId: 'A', locale: 'en', notify: text => notifications.push(text), setBusy() {}, clearBusy() {}, captureLlmSelection: () => ({ profileId: null }) })
  const api = {
    files: async () => [{ key: 'NTT', parsed: true }], findings: async () => [finding('F1', 'Handled')], metrics: async () => metrics,
    latestRunProgress: async () => null, startRun: async () => active(), getRunProgress: async () => completed(), getRun: async (_project, id) => completed(id),
    evidence: async () => ({ title: 'Evidence', located: true, items: [] }),
    updateStatus: async (_project, code, status) => finding(code, status),
    updateReview: async (_project, code, review) => ({ ...finding(code), ...review, reviewUpdatedAt: '2026-10-02T03:00:00Z' }),
    uploadPackage: async (...args) => { uploads.push(args); return { messages: [] } },
    checklist: async (_project, runId) => ({ runId, runSnapshotSha256: 'snapshot', sourceSnapshotSha256: 'source', writable: true, items: [
      { id: 'APP-I-001', clause: 'PRE.B2', status: 'unchecked', remarks: '' },
      { id: 'APP-I-002', clause: 'PRE.B3', status: 'unchecked', remarks: '' }
    ] }),
    saveChecklist: async (_project, binding, items) => ({ ...binding, writable: true, items }),
    exportReport: async (...args) => downloads.push(args), ...overrides
  }
  const scope = vue.effectScope()
  const view = scope.run(() => setup({
    computed: vue.computed, ref: vue.ref, watch: vue.watch,
    onMounted: fn => mounted.push(fn), onBeforeUnmount: fn => unmounted.push(fn),
    useI18n: () => ({ t: key => key }), useRouter: () => ({ push: async () => undefined }), useAppStore: () => store,
    useLocalized: () => ({ pick: value => value, currentKey: vue.ref('en') }), vettingApi: api, ApiError: class ApiError extends Error {},
    window: { setTimeout: (fn, delay) => { const id = ++timerId; timers.set(id, { fn, delay }); return id }, clearTimeout: id => timers.delete(id) }
  }))
  return { view, api, store, timers, downloads, uploads, notifications,
    mount: async () => { mounted.forEach(fn => fn()); await settle() },
    fire: async delay => { const entry = [...timers.entries()].find(([, timer]) => timer.delay === delay); assert.ok(entry, `timer ${delay} exists`); timers.delete(entry[0]); entry[1].fn(); await settle() },
    dispose: () => { unmounted.forEach(fn => fn()); scope.stop() }
  }
}

{
  const h = harness()
  await h.mount()
  await h.view.runVetting()
  assert.equal(h.view.running.value, true)
  await h.fire(2000)
  assert.equal(h.view.job.value.status, 'COMPLETED')
  assert.equal(h.view.running.value, false)
  assert.equal(h.view.findings.value[0].status, 'Handled', 'completion respects server review status')
  assert.equal([...h.timers.values()].some(timer => timer.delay === 2000), false, 'completed task stops polling')
  h.view.reportFormat.value = 'docx'
  await h.view.exportReport()
  assert.equal(h.downloads[0][3], 'docx')
  h.view.reportFormat.value = 'json'
  await h.view.exportReport()
  assert.equal(h.downloads[1][3], 'json')
  h.dispose()
}

{
  const h = harness()
  await h.mount()
  assert.equal(h.view.findings.value.length, 1, 'legacy seeded finding visible')
  assert.equal(h.view.job.value, null)
  assert.equal(h.view.exportDisabled.value, true, 'legacy findings without a completed run do not enable export')
  await h.view.exportReport()
  assert.equal(h.downloads.length, 0)
  h.view.job.value = { ...completed(), result: { total: 0, metrics: { ...metrics, total: 0 }, messages: [], model: 'test' } }
  h.view.findings.value = []
  assert.equal(h.view.exportDisabled.value, false, 'completed zero-finding task permits export')
  await h.view.exportReport()
  assert.equal(h.downloads.length, 1)
  h.view.job.value.sourceSetStale = true
  assert.equal(h.view.exportDisabled.value, true, 'restoring a completed old source run does not re-enable current report export')
  await h.view.exportReport()
  assert.equal(h.downloads.length, 1)
  h.view.job.value = { ...active(), status: 'FAILED' }
  assert.equal(h.view.exportDisabled.value, true, 'failed latest task cannot export a previous report')
  h.dispose()
}

{
  const h = harness()
  await h.mount()
  const files = [{ name: 'SCC.docx' }]
  await h.view.uploadPackage({ target: { files } })
  assert.equal(h.uploads[0][2], undefined, 'auto role keeps server inference available')
  h.view.uploadRole.value = 'standard'
  h.view.uploadDocumentScope.value = 'example'
  await h.view.uploadPackage({ target: { files } })
  assert.equal(h.uploads[1][2], 'standard', 'explicit upload purpose reaches the API')
  assert.equal(h.uploads[1][3], 'example', 'scope exclusion is independent of source role')
  h.store.activeProjectId = 'B'
  await settle()
  assert.equal(h.view.uploadRole.value, 'auto', 'project change resets the next upload purpose')
  assert.equal(h.view.uploadDocumentScope.value, 'auto')
  h.dispose()
}

{
  const h = harness(); await h.mount()
  h.view.job.value = completed(); await settle()
  await h.view.openChecklist({ target: { open: true } })
  const [first, second] = h.view.checklist.value.items
  first.status = 'fulfilled'; second.remarks = 'Separate pending check'
  assert.equal(h.view.checklistDirty.value, true)
  let discardedRunAttempts = 0
  h.api.startRun = async () => { discardedRunAttempts++; return active('should-not-start') }
  await h.view.runVetting()
  assert.equal(discardedRunAttempts, 0, 'a new run cannot discard unsaved manual checklist judgments')
  await h.view.uploadPackage({ target: { files: [{ name: 'replacement.docx' }] } })
  assert.equal(h.uploads.length, 0, 'a source replacement cannot discard unsaved manual judgments')
  assert.equal(h.view.exportDisabled.value, true, 'unsaved checklist must not silently disappear from report')
  await h.view.saveCheck(first)
  assert.equal(h.view.checkDirty(first), false)
  assert.equal(h.view.checkDirty(second), true, 'saving a row preserves another row draft')
  h.view.restoreCheck(second)
  assert.equal(h.view.exportDisabled.value, false)
  h.view.job.value = completed('new-run'); await settle()
  assert.equal(h.view.checklist.value, null, 'old human checks do not cross into a new run')
  h.dispose()
}

{
  const pending = deferred()
  const h = harness({ uploadPackage: () => pending.promise })
  await h.mount()
  h.view.job.value = completed()
  const upload = h.view.uploadPackage({ target: { files: [{ name: 'updated.docx' }] } })
  assert.equal(h.view.exportDisabled.value, true, 'source upload in progress disables previous report export')
  await h.view.exportReport()
  assert.equal(h.downloads.length, 0)
  pending.resolve({ messages: [] })
  await upload
  assert.equal(h.view.job.value.id, 'run-1', 'Uploading sources retains the previous run and its human-work context')
  assert.equal(h.view.job.value.sourceSetStale, true, 'The retained report remains explicitly stale after source upload')
  assert.equal(h.view.exportDisabled.value, true, 'Retaining the old run never permits exporting its outdated report')
  await h.view.exportReport()
  assert.equal(h.downloads.length, 0, 'The stale report cannot reach the export API after upload completes')
  h.dispose()
}

{
  const pending = deferred()
  let latestReads = 0
  const h = harness({ latestRunProgress: () => ++latestReads === 1 ? pending.promise : Promise.resolve(null) })
  await h.mount()
  await h.view.uploadPackage({ target: { files: [{ name: 'updated.docx' }] } })
  pending.resolve(completed('old-snapshot'))
  await settle()
  assert.equal(h.view.job.value, null, 'pre-upload restore cannot re-enable an old snapshot report')
  assert.equal(h.view.exportDisabled.value, true)
  h.dispose()
}

{
  const h = harness({ latestRunProgress: async () => active('restored') })
  await h.mount()
  assert.equal(h.view.job.value.id, 'restored', 'active task restored on open')
  h.dispose()
  assert.equal(h.timers.size, 0, 'unmount clears timers')
}

{
  const pending = deferred()
  const h = harness({ latestRunProgress: async () => active('old'), getRunProgress: () => pending.promise })
  await h.mount()
  await h.fire(2000)
  h.api.latestRunProgress = async () => completed('new')
  h.store.activeProjectId = 'B'
  await settle()
  pending.resolve(completed('old'))
  await settle()
  assert.equal(h.view.job.value.id, 'new', 'old project poll cannot overwrite current project')
  h.dispose()
}

{
  const first = deferred(), second = deferred()
  const h = harness({ findings: async () => [finding('F1'), finding('F2')], evidence: (_id, code) => code === 'F1' ? first.promise : second.promise })
  await h.mount()
  h.view.openFinding(finding('F1'))
  h.view.openFinding(finding('F2'))
  second.resolve({ title: 'second', located: true, items: [{ code: 'SCT', text: 'correct evidence', pageNo: 'P2', side: 'target', located: true }] })
  await settle()
  first.resolve({ title: 'first', located: true, items: [{ code: 'NTT', text: 'stale evidence' }] })
  await settle()
  assert.equal(h.view.selectedFinding.value.code, 'F2')
  assert.equal(h.view.evidenceItems.value[0].quote, 'correct evidence', 'selection ignores stale evidence response')
  assert.equal(h.view.evidenceItems.value[0].side, 'target')
  await h.view.updateStatus('Assigned')
  assert.equal(h.view.selectedFinding.value.status, 'Assigned')
  h.dispose()
}

{
  const h = harness({ getRunProgress: async () => { throw new Error('offline') } })
  await h.view.runVetting()
  await h.fire(2000)
  assert.equal(h.view.pollingPaused.value, true)
  assert.equal(h.view.job.value.status, 'RUNNING', 'network failure is not job failure')
  h.api.getRunProgress = async () => completed()
  h.view.retryPolling()
  await settle()
  assert.equal(h.view.job.value.status, 'COMPLETED')
  h.dispose()
}

{
  const requests = []
  const h = harness({ updateReview: async (project, code, review) => {
    requests.push({ project, code, review }); return { ...finding(code, 'Handled'), ...review, reviewUpdatedAt: '2026-10-02T03:00:00Z' }
  } })
  await h.mount()
  h.view.job.value = completed()
  h.view.openFinding(finding('F1', 'Handled'))
  h.view.reviewDraft.value.reviewRemarks = '  Not adopted: different objects.\nKeep the source exception.  '
  h.view.reviewDraft.value.actionTaken = 'Clarification recorded; no tender change.'
  h.view.reviewDraft.value.addendum = 'notRequired'
  assert.equal(h.view.exportDisabled.value, true, 'unsaved project-team reply cannot silently be omitted from export')
  await h.view.saveReview()
  assert.deepEqual(requests[0], { project: 'A', code: 'F1', review: {
    expectedRunId: 'run-1', expectedGeneratedRevisionSha256: 'generated', expectedSourceSnapshotSha256: 'source',
    reviewRemarks: '  Not adopted: different objects.\nKeep the source exception.  ',
    actionTaken: 'Clarification recorded; no tender change.', addendumRequired: false
  } })
  assert.equal(h.view.selectedFinding.value.status, 'Handled')
  assert.equal(h.view.reviewDirty.value, false)
  assert.equal(h.view.exportDisabled.value, false)
  h.view.reviewDraft.value.addendum = 'undecided'
  await h.view.saveReview()
  assert.equal(requests[1].review.addendumRequired, null, 'undecided is not a negative addendum decision')
  h.dispose()
}

{
  const h = harness()
  await h.mount()
  h.view.openFinding(finding('F1'))
  h.view.reviewDraft.value.reviewRemarks = 'Unsaved original reviewer reply'
  await h.view.updateStatus('Assigned')
  assert.equal(h.view.reviewDraft.value.reviewRemarks, 'Unsaved original reviewer reply', 'status change does not erase review edits')
  h.view.openFinding(finding('F2'))
  assert.equal(h.view.reviewDraft.value.reviewRemarks, '')
  assert.equal(h.view.hasUnsavedReview.value, true, 'draft for another finding remains pending')
  h.view.openFinding(finding('F1', 'Assigned'))
  assert.equal(h.view.reviewDraft.value.reviewRemarks, 'Unsaved original reviewer reply', 'reopening restores the original finding draft')
  h.view.discardReview()
  assert.equal(h.view.hasUnsavedReview.value, false)
  h.dispose()
}

{
  const pending = deferred()
  const h = harness({ updateReview: () => pending.promise })
  await h.mount()
  h.view.openFinding(finding('F1'))
  h.view.reviewDraft.value.reviewRemarks = 'Old project reply'
  const saving = h.view.saveReview()
  assert.equal(h.view.reviewSaving.value, true)
  h.store.activeProjectId = 'B'
  await settle()
  h.view.openFinding(finding('F2'))
  pending.resolve({ ...finding('F1'), reviewRemarks: 'Old project reply', addendumRequired: true })
  await saving
  assert.equal(h.view.selectedFinding.value.code, 'F2', 'stale review save cannot select or overwrite another project')
  assert.equal(h.view.reviewDraft.value.reviewRemarks, '')
  assert.equal(h.view.hasUnsavedReview.value, false)
  h.dispose()
}

{
  const h = harness({ updateReview: async () => { throw new Error('offline') } })
  h.view.openFinding(finding('F1'))
  h.view.reviewDraft.value.reviewRemarks = 'Keep this reply after failure'
  await h.view.saveReview()
  assert.equal(h.view.reviewFailed.value, true)
  assert.equal(h.view.reviewSaving.value, false)
  assert.equal(h.view.reviewDraft.value.reviewRemarks, 'Keep this reply after failure')
  assert.equal(h.view.reviewDirty.value, true)
  h.dispose()
}

{
  const seven = { ...metrics, gap: 3, substantive: 4, requirement: 5 }
  const h = harness({ metrics: async () => seven })
  await h.mount()
  assert.deepEqual(h.view.GROUPS, ['reference', 'conflict', 'language', 'risk', 'gap', 'substantive', 'requirement'])
  const cards = h.view.metricCards()
  assert.equal(cards.length, 7)
  assert.deepEqual(cards.slice(4).map(card => card.value), [3, 4, 5], 'PM extension types retain their own counts instead of falling into risk')
  assert.equal(cards.find(card => card.group === 'risk').value, 0)
  h.dispose()
}

{
  const saved = []
  const h = harness({ saveChecklist: async (_project, binding, items) => { saved.push(items); return { ...binding, writable: true, items } } })
  await h.mount()
  h.view.job.value = completed(); await settle()
  await h.view.openChecklist({ target: { open: true } })
  const item = h.view.checklist.value.items[0]
  item.status = 'not_fulfilled'; item.remarks = ' \n '
  await h.view.saveCheck(item)
  assert.equal(saved.length, 0, 'a failed human countercheck requires a substantive reason')
  assert.ok(h.notifications.includes('vetting.checklist.reasonRequired'))
  assert.equal(h.view.checkDirty(item), true, 'invalid submission retains the human draft')
  item.remarks = 'Required clause is absent from the checked adopted PRE.'
  await h.view.saveCheck(item)
  assert.equal(saved.length, 1)
  assert.equal(saved[0][0].status, 'not_fulfilled')
  assert.equal(h.view.checkDirty(item), false)
  h.dispose()
}

{
  let exported = false
  const h = harness({
    exportReport: async () => { exported = true },
    latestRunProgress: async () => completed(),
    getRun: async () => ({ ...completed(), reportSnapshotId: exported ? 'after-human-review' : 'run-completion' })
  })
  await h.mount()
  assert.equal(h.view.job.value.reportSnapshotId, 'run-completion')
  await h.view.exportReport()
  assert.equal(h.view.job.value.reportSnapshotId, 'after-human-review', 'export refreshes the report snapshot created from current human review records')
  h.dispose()
}

{
  const pending = deferred(), reads = []
  const h = harness({ exportReport: () => pending.promise, latestRunProgress: async project => { reads.push(project); return null } })
  await h.mount()
  h.view.job.value = { ...completed(), reportSnapshotId: 'project-A-snapshot' }
  const downloading = h.view.exportReport()
  h.store.activeProjectId = 'B'; await settle()
  h.view.job.value = { ...completed('run-B'), reportSnapshotId: 'project-B-snapshot' }
  const before = reads.length
  pending.resolve(); await downloading
  assert.equal(reads.length, before, 'a completed download from the old project cannot refresh the new project')
  assert.equal(h.view.job.value.reportSnapshotId, 'project-B-snapshot')
  h.dispose()
}

{
  for (const status of ['FAILED', 'COMPLETED', 'QUEUED', 'RUNNING']) {
    let starts = 0, details = 0, checklistReads = 0
    const h = harness({
      latestRunProgress: async () => ({ ...active(), status, completedUnits: 0, totalUnits: 19,
        ...(status === 'FAILED' ? { recoveryStatus: 'NEEDS_RERUN', error: 'Authored terminal failure' } : {}) }),
      getRun: async () => { details++; throw new Error('Authored full-detail timeout') },
      checklist: async () => { checklistReads++; throw new Error('Unverified report must not load a checklist') },
      startRun: async () => { starts++; return active('explicit-new-run') }
    })
    try {
      await h.mount()
      h.view.openFinding(h.view.findings.value[0]); await settle()
      const failed = status === 'FAILED'
      assert.equal(h.view.job.value.sourceVerification, 'NOT_CHECKED')
      assert.equal(h.view.job.value.status, status, 'A failed detail read must preserve the observed progress status')
      assert.equal(h.view.running.value, !failed, `${status}: only a completed report waits for source verification`)
      assert.equal(h.view.sourceRoleBlocked.value, !failed, `${status}: terminal failure allows fixing source inputs`)
      assert.equal(h.view.exportDisabled.value, true, `${status}: an unverified report cannot be exported`)
      assert.equal(h.view.handoffDisabled.value, true, `${status}: an unverified finding cannot be handed to Advisory`)
      await h.view.openChecklist({ target: { open: true } })
      assert.equal(checklistReads, 0, `${status}: source verification is still required for a report checklist`)
      assert.equal(details, ['FAILED', 'COMPLETED'].includes(status) ? 1 : 0, 'Active jobs continue lightweight progress polling')
      assert.equal(h.view.pollingPaused.value, ['FAILED', 'COMPLETED'].includes(status))
      await h.view.runVetting()
      assert.equal(starts, failed ? 1 : 0, `${status}: only a terminal failed run may be explicitly restarted`)
      if (failed) assert.equal(h.view.job.value.id, 'explicit-new-run')
    } finally { h.dispose() }
  }
}

console.log('Vetting view regressions passed: completion, restore, project/evidence/review races, review status and team replies, tri-state addendum, draft retention, exports and snapshot refresh, upload purpose, reconnect, cleanup and seven independent issue types.')
