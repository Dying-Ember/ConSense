<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { vettingApi } from '@/api'
import type { EvidenceBundle, Finding, FindingEvidence, VettingFile, VettingJob, VettingMetrics, VettingReportFormat, VettingSemanticTopic, VettingSourceRole, DocumentScope, VettingChecklist, VettingChecklistItem } from '@/api/types'
import { useAppStore } from '@/stores/app'
import { useLocalized } from '@/composables/useLocalized'
import AppIcon from '@/components/AppIcon.vue'
import AppModal from '@/components/AppModal.vue'

const { t } = useI18n()
const store = useAppStore()
const { pick, currentKey } = useLocalized()

function reviewScopeStatus(topic: VettingSemanticTopic): string {
  const known = ['failed', 'not_submitted', 'partial', 'observed_requests_decoded_scope_unknown']
  return topic.aggregateReviewStatus && known.includes(topic.aggregateReviewStatus) ? topic.aggregateReviewStatus : 'unknown'
}

function callStatusLabel(status?: string): string {
  const known = ['not_submitted', 'not_submitted_over_budget', 'not_submitted_budget_unknown', 'completed', 'completed_empty', 'completed_with_rejections', 'failed']
  return status && known.includes(status) ? t(`vetting.coverage.callStates.${status}`) : `${t('vetting.coverage.callStates.unknown')}${status ? ` (${status})` : ''}`
}

function requestStateLabel(status: string, kind: 'transportStates' | 'requestStates'): string {
  const known = kind === 'transportStates'
    ? ['already_global', 'transported_extra_pack', 'oversized', 'omitted_pack_cap', 'unknown', 'budget_unknown', 'over_budget']
    : ['decoded', 'decoded_provider_estimate', 'decoded_with_rejections', 'decoded_provider_estimate_with_rejections', 'over_budget', 'not_submitted_over_budget', 'not_submitted_budget_unknown', 'not_submitted_oversized', 'not_submitted_omitted_pack_cap', 'not_submitted_unknown', 'failed', 'not_submitted']
  return known.includes(status) ? t(`vetting.coverage.${kind}.${status}`) : `${t('vetting.coverage.callStates.unknown')} (${status})`
}

function packetBudgetStatus(status?: string): string {
  return status && ['budget_unknown', 'over_budget', 'observed_tokens', 'provider_estimated_fit'].includes(status) ? status : 'budget_unknown'
}

const files = ref<VettingFile[]>([])
const findings = ref<Finding[]>([])
const metrics = ref<VettingMetrics | null>(null)
const job = ref<VettingJob | null>(null)
const starting = ref(false)
const uploading = ref(false)
const statusSaving = ref(false)
const reviewSaving = ref(false)
const reviewFailed = ref(false)
type ReviewDraft = { reviewRemarks: string; actionTaken: string; addendum: 'undecided' | 'required' | 'notRequired' }
const emptyReview = (): ReviewDraft => ({ reviewRemarks: '', actionTaken: '', addendum: 'undecided' })
const reviewDraft = ref<ReviewDraft>(emptyReview())
const reviewBaseline = ref<ReviewDraft>(emptyReview())
const reviewCode = ref<string | null>(null)
const reviewDrafts = ref(new Map<string, ReviewDraft>())
const reviewDirty = computed(() => JSON.stringify(reviewDraft.value) !== JSON.stringify(reviewBaseline.value))
const hasUnsavedReview = computed(() => reviewDirty.value || [...reviewDrafts.value.keys()].some(code => code !== reviewCode.value))
const unsavedReviewCodes = computed(() => [...new Set([
  ...[...reviewDrafts.value.keys()].filter(code => code !== reviewCode.value),
  ...(reviewDirty.value && reviewCode.value ? [reviewCode.value] : [])
])])
const exporting = ref(false)
const pollingPaused = ref(false)
const reportFormat = ref<VettingReportFormat>('pdf')
const uploadRole = ref<'auto' | VettingSourceRole>('auto')
const uploadDocumentScope = ref<DocumentScope>('auto')
const checklist = ref<VettingChecklist | null>(null)
const checklistBaseline = ref<VettingChecklistItem[]>([])
const checklistLoading = ref(false)
const checklistSaving = ref<string | null>(null)
let checklistSequence = 0
const checklistDirty = computed(() => checklist.value !== null && JSON.stringify(checklist.value.items) !== JSON.stringify(checklistBaseline.value))
const loading = ref(false)
const selectedDetail = ref<Finding | null>(null)
const evidenceLoading = ref(false)
const evidenceFailed = ref(false)
let pollTimer: number | undefined
let searchTimer: number | undefined
let viewEpoch = 0
let reloadSequence = 0
let evidenceSequence = 0
let jobSequence = 0
let disposed = false
const packageInput = ref<HTMLInputElement | null>(null)
const sourceOpen = ref(false)

const filters = ref({ search: '', group: 'all', scope: 'all', fileKey: 'all', page: 'all' })
const selectedCode = ref<string | null>(null)
const drawerOpen = ref(false)
const evidence = ref<EvidenceBundle | null>(null)

const projectId = computed(() => store.activeProjectId)

const GROUPS = ['reference', 'conflict', 'language', 'risk'] as const

const GROUP_CLASS: Record<string, string> = {
  reference: 'type-ref',
  conflict: 'type-conflict',
  language: 'type-lang',
  risk: 'type-risk'
}

const GROUP_TAG: Record<string, string> = {
  reference: 'info',
  conflict: 'danger',
  language: 'warn',
  risk: 'demo'
}

/** 三栏定位器第二栏的标签（变量 / 错误类别） */
const BUCKET_LABELS: Record<string, { zhHans: string; zhHant: string; en: string }> = {
  evalWeight: { zhHans: '评审权重', zhHant: '評審權重', en: 'Evaluation weighting' },
  bondForm: { zhHans: '保函表格', zhHant: '保函表格', en: 'Bond form' },
  billRange: { zhHans: '提交范围', zhHant: '提交範圍', en: 'Submission scope' },
  warranty: { zhHans: '保养期 / 保用期', zhHant: '保養期 / 保用期', en: 'Maintenance / warranty' },
  particulars: { zhHans: '项目资料', zhHant: '項目資料', en: 'Particulars' },
  programme: { zhHans: '工期', zhHant: '工期', en: 'Programme' },
  scope: { zhHans: '范围', zhHant: '範圍', en: 'Scope' },
  missing: { zhHans: '引用缺失', zhHant: '引用缺失', en: 'Missing reference' },
  wrongNo: { zhHans: '编号错误', zhHant: '編號錯誤', en: 'Wrong clause number' },
  blank: { zhHans: '空白未定稿', zhHant: '空白未定稿', en: 'Blank placeholder' },
  version: { zhHans: '版本引用错误', zhHant: '版本引用錯誤', en: 'Version reference' },
  grammar: { zhHans: '语法', zhHant: '語法', en: 'Grammar' },
  spelling: { zhHans: '拼写', zhHant: '拼寫', en: 'Spelling' },
  american: { zhHans: '英式 / 美式', zhHant: '英式 / 美式', en: 'British / American' }
}

const selectedFinding = computed(() => selectedDetail.value)
const running = computed(() => starting.value || isActiveJob(job.value))
const jobModelIdentity = computed(() => job.value?.modelIdentity ?? job.value?.result?.modelIdentity)
const recordedJobModel = computed(() => jobModelIdentity.value?.model || job.value?.result?.model)
const exportDisabled = computed(() => running.value || uploading.value || exporting.value || statusSaving.value || reviewSaving.value || hasUnsavedReview.value || checklistDirty.value || checklistSaving.value !== null || job.value?.sourceSetStale === true || job.value?.status !== 'COMPLETED')
const coverage = computed(() => job.value?.coverage ?? job.value?.result?.coverage ?? null)
const jobPercent = computed(() => {
  if (!job.value?.totalUnits) return null
  return Math.min(100, Math.max(0, Math.round(job.value.completedUnits / job.value.totalUnits * 100)))
})
const sourceWarnings = computed(() => files.value.filter((file) => !file.parsed || file.warnings?.length))
const evidenceItems = computed<FindingEvidence[]>(() => {
  if (evidence.value) {
    return evidence.value.items.map((item) => ({
      side: item.side ?? 'source', documentId: item.documentId,
      fileKey: item.fileKey ?? item.code, fileName: item.fileName ?? '',
      pageNo: item.pageNo, anchor: item.anchor, quote: item.quote ?? item.text,
      located: item.located ?? evidence.value!.located, sourceHash: item.sourceHash, bbox: item.bbox,
      packetId: item.packetId, packetSourceSnapshotSha256: item.packetSourceSnapshotSha256
    }))
  }
  return selectedFinding.value?.evidence ?? []
})

function isActiveJob(value: VettingJob | null) {
  return value?.status === 'QUEUED' || value?.status === 'RUNNING'
}

function statusLabel(status: string) {
  const key = status.toUpperCase()
  return ['OPEN', 'HANDLED', 'ASSIGNED'].includes(key) ? t(`vetting.review.${key}`) : status
}

function evidenceSide(side: string) {
  const known = ['source', 'target', 'baseline', 'reference', 'left', 'right']
  return known.includes(side.toLowerCase()) ? t(`vetting.evidenceSides.${side.toLowerCase()}`) : side
}

function originalSourceUrl(item: FindingEvidence) {
  if (!projectId.value || !item.documentId || !item.sourceHash) return null
  const page = /^P(\d+)$/i.exec(item.pageNo ?? '')
  const fragment = page && item.fileName.toLowerCase().endsWith('.pdf') ? `#page=${page[1]}` : ''
  return `/api/vetting/${encodeURIComponent(projectId.value)}/sources/${encodeURIComponent(String(item.documentId))}/original?sourceHash=${encodeURIComponent(item.sourceHash)}${fragment}`
}

const canUseBuckets = computed(() => filters.value.group === 'conflict')
const sourceFileKeys = computed(() => [...new Set(files.value.map((file) => file.key))])

const fileBuckets = computed(() => {
  const map = new Map<string, number>()
  findings.value.forEach((item) => {
    const key = item.fileKey || '—'
    map.set(key, (map.get(key) ?? 0) + 1)
  })
  return [...map.entries()].map(([key, count]) => ({ key, count }))
})

const secondBuckets = computed(() => {
  const map = new Map<string, number>()
  findings.value.forEach((item) => {
    const key = canUseBuckets.value ? item.bucketKey || 'particulars' : item.pageNo
    if (!key) return
    map.set(key, (map.get(key) ?? 0) + 1)
  })
  return [...map.entries()]
    .map(([key, count]) => ({ key, count }))
    .sort((a, b) => (canUseBuckets.value ? b.count - a.count : a.key.localeCompare(b.key)))
})

function bucketLabel(key: string) {
  const labels = BUCKET_LABELS[key]
  if (!labels) return key
  return labels[currentKey.value] || labels.zhHans
}

async function reload() {
  const id = projectId.value
  const epoch = viewEpoch
  const seq = ++reloadSequence
  if (!id) return
  loading.value = true
  try {
    const [fileList, findingList, metricData] = await Promise.all([
      vettingApi.files(id), vettingApi.findings(id, { ...filters.value }), vettingApi.metrics(id)
    ])
    if (disposed || epoch !== viewEpoch || seq !== reloadSequence || id !== projectId.value) return
    files.value = fileList
    findings.value = findingList
    metrics.value = metricData
    const refreshed = findingList.find((item) => item.code === selectedCode.value)
    if (refreshed) selectedDetail.value = refreshed
  } finally {
    if (seq === reloadSequence && epoch === viewEpoch) loading.value = false
  }
}

function stopPolling() {
  if (pollTimer) window.clearTimeout(pollTimer)
  pollTimer = undefined
}

function schedulePoll(id: string, runId: string, epoch: number) {
  stopPolling()
  if (disposed || epoch !== viewEpoch || id !== projectId.value) return
  pollTimer = window.setTimeout(() => { void pollRun(id, runId, epoch) }, 2000)
}

async function acceptJob(value: VettingJob, id: string, epoch: number, notifyCompletion = false) {
  if (disposed || epoch !== viewEpoch || id !== projectId.value) return
  job.value = value
  pollingPaused.value = false
  if (isActiveJob(value)) {
    schedulePoll(id, value.id, epoch)
  } else {
    stopPolling()
    if (notifyCompletion && value.status === 'COMPLETED') {
      store.notify(t('vetting.run.finished', { count: value.result?.total ?? 0 }))
      await reload()
      if (disposed || epoch !== viewEpoch || id !== projectId.value) return
      // Findings may have acquired new codes. Close a detail from the previous run.
      selectedCode.value = null
      selectedDetail.value = null
      drawerOpen.value = false
      evidence.value = null
    }
    if (notifyCompletion && value.status === 'FAILED') store.notify(value.error || value.message || t('vetting.job.failed'), 6000)
  }
}

async function pollRun(id: string, runId: string, epoch: number) {
  if (disposed || epoch !== viewEpoch || id !== projectId.value) return
  try {
    const value = await vettingApi.getRun(id, runId)
    if (job.value?.id !== runId) return
    await acceptJob(value, id, epoch, true)
  } catch {
    if (epoch === viewEpoch && id === projectId.value) pollingPaused.value = true
  }
}

async function restoreJob() {
  const id = projectId.value
  const epoch = viewEpoch
  const seq = ++jobSequence
  if (!id) return
  try {
    const value = await vettingApi.latestRun(id)
    if (seq !== jobSequence) return
    if (value) await acceptJob(value, id, epoch)
  } catch {
    if (epoch === viewEpoch && id === projectId.value) pollingPaused.value = true
  }
}

function retryPolling() {
  pollingPaused.value = false
  if (job.value && isActiveJob(job.value)) void pollRun(projectId.value, job.value.id, viewEpoch)
  else void restoreJob()
}

onMounted(() => { void reload().catch(() => undefined); void restoreJob() })
watch(projectId, () => {
  viewEpoch++
  jobSequence++
  evidenceSequence++
  stopPolling()
  if (searchTimer) window.clearTimeout(searchTimer)
  job.value = null
  files.value = []
  findings.value = []
  metrics.value = null
  selectedDetail.value = null
  evidence.value = null
  starting.value = false
  uploading.value = false
  uploadRole.value = 'auto'
  uploadDocumentScope.value = 'auto'
  checklist.value = null
  checklistBaseline.value = []
  checklistSequence++
  checklistSaving.value = null
  checklistLoading.value = false
  statusSaving.value = false
  reviewSaving.value = false
  reviewFailed.value = false
  reviewCode.value = null
  reviewDrafts.value.clear()
  reviewDraft.value = emptyReview()
  reviewBaseline.value = emptyReview()
  evidenceLoading.value = false
  evidenceFailed.value = false
  pollingPaused.value = false
  selectedCode.value = null
  drawerOpen.value = false
  filters.value = { search: '', group: 'all', scope: 'all', fileKey: 'all', page: 'all' }
  void reload().catch(() => undefined)
  void restoreJob()
})

onBeforeUnmount(() => {
  disposed = true
  viewEpoch++
  evidenceSequence++
  stopPolling()
  if (searchTimer) window.clearTimeout(searchTimer)
})

watch(
  () => ({ ...filters.value }),
  () => {
    if (searchTimer) window.clearTimeout(searchTimer)
    searchTimer = window.setTimeout(() => { void reload().catch(() => undefined) }, 220)
  },
  { deep: true }
)

function setGroup(group: string) {
  filters.value.group = filters.value.group === group ? 'all' : group
  filters.value.page = 'all'
}

function setFile(fileKey: string) {
  filters.value.fileKey = filters.value.fileKey === fileKey ? 'all' : fileKey
  filters.value.page = 'all'
}

function setBucket(key: string) {
  if (canUseBuckets.value) {
    filters.value.page = 'all'
    filters.value.search = ''
    // 变量维度直接在高亮状态展示，不做后端过滤（保持列表可对照）
    filters.value.search = bucketSearchKey(key)
  } else {
    filters.value.page = filters.value.page === key ? 'all' : key
  }
}

function bucketSearchKey(key: string) {
  const finding = findings.value.find((item) => (item.bucketKey || 'particulars') === key)
  return finding ? finding.refs || finding.location || '' : ''
}

function bucketActive(key: string) {
  if (canUseBuckets.value) {
    return filters.value.search === bucketSearchKey(key)
  }
  return filters.value.page === key
}

function openFinding(finding: Finding) {
  rememberReviewDraft()
  loadReviewDraft(finding)
  const seq = ++evidenceSequence
  const id = projectId.value
  selectedCode.value = finding.code
  selectedDetail.value = finding
  drawerOpen.value = true
  evidence.value = null
  evidenceLoading.value = true
  evidenceFailed.value = false
  vettingApi.evidence(id, finding.code).then((data) => {
    if (disposed || seq !== evidenceSequence || id !== projectId.value) return
    evidence.value = data
  }).catch(() => {
    if (seq === evidenceSequence && id === projectId.value) evidenceFailed.value = true
  }).finally(() => {
    if (seq === evidenceSequence && id === projectId.value) evidenceLoading.value = false
  })
}

function closeDrawer() {
  rememberReviewDraft()
  drawerOpen.value = false
  evidenceSequence++
}

async function updateStatus(status: string) {
  if (!selectedFinding.value || statusSaving.value || reviewSaving.value || running.value) return
  const id = projectId.value
  const code = selectedFinding.value.code
  const epoch = viewEpoch
  statusSaving.value = true
  try {
    const updated = await vettingApi.updateStatus(id, code, status)
    if (epoch !== viewEpoch || id !== projectId.value) return
    const index = findings.value.findIndex((item) => item.code === updated.code)
    if (index >= 0) findings.value.splice(index, 1, updated)
    if (selectedCode.value === code) {
      selectedDetail.value = updated
      if (!reviewDirty.value) loadReviewDraft(updated)
    }
    store.notify(`${updated.code} · ${statusLabel(updated.status)}`)
  } finally {
    if (epoch === viewEpoch) statusSaving.value = false
  }
}

function rememberReviewDraft() {
  if (!reviewCode.value) return
  if (reviewDirty.value) reviewDrafts.value.set(reviewCode.value, { ...reviewDraft.value })
  else reviewDrafts.value.delete(reviewCode.value)
}

function loadReviewDraft(finding: Finding) {
  const saved: ReviewDraft = {
    reviewRemarks: finding.reviewRemarks ?? '', actionTaken: finding.actionTaken ?? '',
    addendum: finding.addendumRequired === true ? 'required' : finding.addendumRequired === false ? 'notRequired' : 'undecided'
  }
  reviewCode.value = finding.code
  reviewBaseline.value = { ...saved }
  reviewDraft.value = { ...(reviewDrafts.value.get(finding.code) ?? saved) }
  reviewFailed.value = false
}

function discardReview() {
  if (!selectedFinding.value || reviewSaving.value) return
  reviewDrafts.value.delete(selectedFinding.value.code)
  loadReviewDraft(selectedFinding.value)
}

async function saveReview() {
  if (!selectedFinding.value || reviewSaving.value || statusSaving.value || running.value || !reviewDirty.value) return
  const id = projectId.value, code = selectedFinding.value.code, epoch = viewEpoch
  const draft = { ...reviewDraft.value }
  reviewSaving.value = true
  reviewFailed.value = false
  try {
    const updated = await vettingApi.updateReview(id, code, {
      reviewRemarks: draft.reviewRemarks, actionTaken: draft.actionTaken,
      addendumRequired: draft.addendum === 'undecided' ? null : draft.addendum === 'required'
    })
    if (disposed || epoch !== viewEpoch || id !== projectId.value) return
    reviewDrafts.value.delete(code)
    const index = findings.value.findIndex(item => item.code === code)
    if (index >= 0) findings.value.splice(index, 1, updated)
    if (selectedCode.value === code) { selectedDetail.value = updated; loadReviewDraft(updated) }
    store.notify(t('vetting.review.saved'))
  } catch {
    if (epoch === viewEpoch && id === projectId.value) reviewFailed.value = true
  } finally {
    if (epoch === viewEpoch) reviewSaving.value = false
  }
}

async function runVetting() {
  if (running.value || uploading.value || reviewSaving.value || statusSaving.value || hasUnsavedReview.value || checklistDirty.value || checklistSaving.value !== null || !projectId.value) return
  const llmSelection = store.captureLlmSelection()
  const id = projectId.value
  const epoch = viewEpoch
  jobSequence++
  starting.value = true
  try {
    const value = await vettingApi.startRun(id, store.locale, llmSelection)
    await acceptJob(value, id, epoch, true)
  } finally {
    if (epoch === viewEpoch) starting.value = false
  }
}

async function uploadPackage(event: Event) {
  const selected = Array.from((event.target as HTMLInputElement).files ?? [])
  if (hasUnsavedReview.value || reviewSaving.value || checklistDirty.value || checklistSaving.value !== null) {
    (event.target as HTMLInputElement).value = ''
    return
  }
  if (!selected.length || running.value || uploading.value) return
  const id = projectId.value
  const epoch = viewEpoch
  const sourceRole = uploadRole.value === 'auto' ? undefined : uploadRole.value
  jobSequence++
  uploading.value = true
  store.setBusy(t('common.loading'))
  try {
    const result = await vettingApi.uploadPackage(id, selected, sourceRole, uploadDocumentScope.value)
    if (epoch !== viewEpoch || id !== projectId.value) return
    store.notify(result.messages.join('\n') || t('common.done'), 5000)
    await reload()
    if (epoch !== viewEpoch || id !== projectId.value) return
    job.value = null
    // Restore coverage only once a new run has reviewed the uploaded source set.
  } finally {
    store.clearBusy()
    if (epoch === viewEpoch) uploading.value = false
    if (packageInput.value) packageInput.value.value = ''
  }
}

async function exportReport() {
  if (exportDisabled.value) return
  const id = projectId.value
  const format = reportFormat.value
  const epoch = viewEpoch
  exporting.value = true
  try {
    await vettingApi.exportReport(id, store.locale, `ConSense_Vetting_Report.${format}`, format)
    if (!disposed && epoch === viewEpoch && id === projectId.value) {
      store.notify(t('vetting.actions.exportStarted', { format: format.toUpperCase() }), 5000)
    }
  } finally {
    exporting.value = false
  }
}

watch(() => job.value?.id, () => {
  checklistSequence++
  checklist.value = null
  checklistBaseline.value = []
  checklistLoading.value = false
  checklistSaving.value = null
})

async function openChecklist(event: Event) {
  if (!(event.target as HTMLDetailsElement).open || checklist.value || job.value?.status !== 'COMPLETED') return
  const id = projectId.value, runId = job.value.id, epoch = viewEpoch, sequence = ++checklistSequence
  checklistLoading.value = true
  try {
    const value = await vettingApi.checklist(id, runId)
    if (disposed || epoch !== viewEpoch || runId !== job.value?.id || sequence !== checklistSequence) return
    checklist.value = value
    checklistBaseline.value = structuredClone(value.items)
  } finally {
    if (epoch === viewEpoch && sequence === checklistSequence) checklistLoading.value = false
  }
}

function checkDirty(item: VettingChecklistItem) {
  const saved = checklistBaseline.value.find(row => row.id === item.id)
  return !saved || item.status !== saved.status || item.remarks !== saved.remarks
}

function restoreCheck(item: VettingChecklistItem) {
  const saved = checklistBaseline.value.find(row => row.id === item.id)
  if (saved) Object.assign(item, saved)
}

async function saveCheck(item: VettingChecklistItem) {
  if (!checklist.value?.writable || checklistSaving.value || !checkDirty(item)) return
  const id = projectId.value, runId = checklist.value.runId, epoch = viewEpoch
  const submitted = { id: item.id, status: item.status, remarks: item.remarks }
  checklistSaving.value = item.id
  try {
    const saved = await vettingApi.saveChecklist(id, runId, checklist.value.runSnapshotSha256, [submitted])
    if (disposed || epoch !== viewEpoch || runId !== job.value?.id) return
    const savedRow = saved.items.find(row => row.id === item.id)
    if (savedRow) {
      const original = checklistBaseline.value.find(row => row.id === item.id)
      if (original) Object.assign(original, savedRow)
      // A further edit while the request was in flight remains unsaved.
      if (item.status === submitted.status && item.remarks === submitted.remarks) Object.assign(item, savedRow)
    }
    store.notify(t('vetting.checklist.saved'))
  } finally {
    if (epoch === viewEpoch) checklistSaving.value = null
  }
}

function metricCards() {
  const data = metrics.value
  return [
    { group: 'reference', label: t('vetting.metrics.reference'), detail: t('vetting.metrics.referenceDetail'), value: data?.reference ?? 0 },
    { group: 'conflict', label: t('vetting.metrics.conflict'), detail: t('vetting.metrics.conflictDetail'), value: data?.conflict ?? 0 },
    { group: 'language', label: t('vetting.metrics.language'), detail: t('vetting.metrics.languageDetail'), value: data?.language ?? 0 },
    { group: 'risk', label: t('vetting.metrics.risk'), detail: t('vetting.metrics.riskDetail'), value: data?.risk ?? 0 }
  ]
}

const severityClass = (finding: Finding) => finding.severity || 'low'

function tagClass(group: string) {
  return GROUP_TAG[group] ?? 'neutral'
}
</script>

<template>
  <section class="screen">
    <div class="screen-head">
      <div>
        <span class="eyebrow">{{ t('screen.vetting') }}</span>
        <h3>{{ t('vetting.title') }}</h3>
        <p>{{ t('vetting.subtitle') }}</p>
      </div>
      <div class="row">
        <button class="btn" type="button" @click="sourceOpen = true">
          <AppIcon name="layers" :size="15" />{{ t('vetting.actions.source') }}
        </button>
        <input ref="packageInput" type="file" multiple class="hidden"
               accept=".pdf,.doc,.docx,.txt,.md,.msg,.eml,.emlx" @change="uploadPackage" />
        <label class="upload-role">
          <span>{{ t('vetting.uploadRole.label') }}</span>
          <select v-model="uploadRole" :disabled="running || uploading">
            <option value="auto">{{ t('vetting.uploadRole.auto') }}</option>
            <option value="tender">{{ t('vetting.uploadRole.tender') }}</option>
            <option value="standard">{{ t('vetting.uploadRole.standard') }}</option>
            <option value="project_fact">{{ t('vetting.uploadRole.project_fact') }}</option>
            <option value="package_manifest">{{ t('vetting.uploadRole.package_manifest') }}</option>
          </select>
        </label>
        <label class="upload-role">
          <span>{{ t('vetting.documentScope.label') }}</span>
          <select v-model="uploadDocumentScope" :disabled="running || uploading">
            <option v-for="value in ['auto', 'package', 'works_pss', 'pricing', 'example']" :key="value" :value="value">{{ t(`vetting.documentScope.${value}`) }}</option>
          </select>
        </label>
        <button class="btn" type="button" :disabled="running || uploading || hasUnsavedReview || reviewSaving || checklistDirty || checklistSaving !== null" @click="packageInput?.click()">
          <AppIcon name="upload" :size="15" />{{ t('vetting.actions.upload') }}
        </button>
        <button class="btn primary" type="button" :disabled="running || uploading || reviewSaving || statusSaving || hasUnsavedReview || checklistDirty || checklistSaving !== null || !files.some(file => file.parsed)" @click="runVetting">
          <AppIcon name="play" :size="15" />
          {{ running ? t('vetting.actions.running') : t('vetting.actions.run') }}
        </button>
        <select v-model="reportFormat" class="report-format" :aria-label="t('vetting.report.format')" :disabled="exporting">
          <option value="pdf">PDF</option>
          <option value="docx">Word (.docx)</option>
          <option value="json">JSON</option>
        </select>
        <button class="btn" type="button" :disabled="exportDisabled" @click="exportReport">
          <AppIcon name="download" :size="15" />{{ t('vetting.actions.export') }}
        </button>
      </div>
    </div>

    <p v-if="hasUnsavedReview" class="warning-note" role="status">{{ t('vetting.review.unsavedExport') }} {{ unsavedReviewCodes.join(' · ') }}</p>
    <p v-if="checklistDirty" class="warning-note" role="status">{{ t('vetting.checklist.dirty') }}</p>
    <p v-if="job?.sourceSetStale" class="warning-note" role="status">{{ t('vetting.sourceSetStale') }}</p>

    <details v-if="job?.status === 'COMPLETED'" class="surface checklist-panel section-gap" @toggle="openChecklist">
      <summary>{{ t('vetting.checklist.title') }}</summary>
      <p class="muted small">{{ t('vetting.checklist.note') }}</p>
      <p v-if="checklistLoading">{{ t('common.loading') }}</p>
      <div v-if="checklist" class="table-wrap checklist-table">
        <table>
          <thead><tr><th>PSS Clause</th><th>Description</th><th>{{ t('common.status') }}</th><th>{{ t('vetting.checklist.remarks') }}</th></tr></thead>
          <tbody><tr v-for="item in checklist.items" :key="item.id">
            <td>{{ item.clause }}<div class="muted small">{{ item.section }}</div></td>
            <td>{{ item.description }}<p v-if="item.scopeNote" class="warning-note small">{{ item.scopeNoteText ? pick(item.scopeNoteText) : item.scopeNote }}</p></td>
            <td><select v-model="item.status" :disabled="!checklist.writable || checklistSaving === item.id">
              <option v-for="status in ['unchecked', 'fulfilled', 'not_applicable']" :key="status" :value="status">{{ t(`vetting.checklist.${status}`) }}</option>
            </select></td>
            <td><textarea v-model="item.remarks" :aria-label="`${item.clause} ${t('vetting.checklist.remarks')}`" maxlength="4000" :disabled="!checklist.writable || checklistSaving === item.id" />
              <div class="row"><button class="btn" type="button" :disabled="!checklist.writable || !!checklistSaving || !checkDirty(item)" @click="saveCheck(item)">{{ t('vetting.checklist.save') }}</button>
              <button v-if="checkDirty(item)" class="btn ghost" type="button" :disabled="checklistSaving === item.id" @click="restoreCheck(item)">{{ t('vetting.checklist.reset') }}</button></div>
            </td>
          </tr></tbody>
        </table>
      </div>
    </details>

    <div v-if="job || pollingPaused" class="surface section-gap vetting-run-panel" aria-live="polite">
      <div class="surface-body">
        <div class="row">
          <strong>{{ t('vetting.job.title') }}</strong>
          <span v-if="job" class="tag" :class="job.status === 'FAILED' ? 'danger' : 'info'">
            {{ t(`vetting.job.status.${job.status}`) }}
          </span>
          <span v-if="job?.totalUnits" class="mono muted small">{{ t('vetting.job.progressLabel') }}: {{ job.completedUnits }} / {{ job.totalUnits }}</span>
          <span class="spacer" />
          <button v-if="pollingPaused" class="btn" type="button" @click="retryPolling">
            <AppIcon name="refresh" :size="14" />{{ t('vetting.job.resume') }}
          </button>
        </div>
        <p v-if="job?.status === 'COMPLETED'" class="warning-note">{{ t('vetting.job.executionCompleteNote') }}</p>
        <p v-if="job?.message" class="small">{{ job.message }}</p>
        <p v-if="job" class="muted small">{{ recordedJobModel ? `${t('llm.recordedModel')}: ${recordedJobModel}` : t('llm.identityUnknown') }}<template v-if="jobModelIdentity"> · {{ jobModelIdentity.profileId }} · {{ jobModelIdentity.provider }}</template></p>
        <p v-if="jobModelIdentity" class="muted small">{{ t('llm.identityNote') }}</p>
        <progress v-if="running && jobPercent !== null" class="vetting-progress" :value="jobPercent" max="100" :aria-label="t('vetting.job.title')" />
        <p v-if="job?.status === 'FAILED' && job.error" class="run-error">{{ job.error }}</p>
        <p v-if="pollingPaused" class="warning-note">{{ t('vetting.job.connectionPaused') }}</p>
        <p v-else-if="running" class="muted small">{{ t('vetting.job.background') }}</p>
        <p v-if="job?.status === 'COMPLETED'" class="muted small">{{ t('vetting.review.retained') }}</p>
      </div>
    </div>

    <details v-if="coverage || sourceWarnings.length" class="surface section-gap coverage-panel" :open="!!coverage?.warnings?.length || !!sourceWarnings.length">
      <summary>
        <strong>{{ t('vetting.coverage.title') }}</strong>
        <span v-if="coverage" class="tag neutral">{{ coverage.reviewedDocuments }} / {{ coverage.totalDocuments }} {{ t('vetting.coverage.documents') }}</span>
        <span v-if="coverage?.warnings?.length || sourceWarnings.length" class="tag warn">{{ t('vetting.coverage.warnings') }}</span>
      </summary>
      <div class="surface-body">
        <p class="muted small">{{ t('vetting.coverage.explanation') }}</p>
        <ul v-if="coverage?.warnings?.length" class="coverage-warnings">
          <li v-for="(warning, index) in coverage.warnings" :key="index">{{ warning }}</li>
        </ul>
        <div v-for="file in sourceWarnings" :key="file.key + file.fileName" class="warning-note">
          <strong>{{ file.fileName || file.key }}</strong> · {{ file.parseMessage || file.status }}
          <span v-for="(warning, index) in file.warnings" :key="index"> · {{ warning }}</span>
        </div>
        <div v-if="coverage?.documents?.length" class="table-wrap">
          <table>
            <thead><tr><th>{{ t('common.file') }}</th><th>{{ t('vetting.coverage.parse') }}</th><th>{{ t('vetting.coverage.segments') }}</th><th>{{ t('vetting.coverage.characters') }}</th><th>{{ t('vetting.coverage.warnings') }}</th></tr></thead>
            <tbody>
              <tr v-for="document in coverage.documents" :key="document.fileKey + document.fileName">
                <td><strong>{{ document.fileName }}</strong><div class="mono muted small">{{ document.fileKey }}</div></td>
                <td>{{ document.parseStatus }}</td>
                <td>{{ document.reviewedSegments }} / {{ document.totalSegments }}</td>
                <td>{{ document.reviewedChars }} / {{ document.textChars }}</td>
                <td><div v-for="(warning, index) in document.warnings" :key="index" class="warning-note">{{ warning }}</div><span v-if="!document.warnings?.length">—</span></td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </details>

    <details v-if="coverage?.semanticTopics?.length" class="surface section-gap coverage-panel">
      <summary>{{ t('vetting.coverage.callLedger') }}</summary>
      <div class="coverage-body">
        <p class="muted small">{{ t('vetting.coverage.callLedgerNote') }}</p>
        <p class="warning-note">{{ t('vetting.coverage.pendingScopeNote') }}</p>
        <div class="table-wrap vetting-call-table">
          <table>
            <thead><tr><th>{{ t('vetting.coverage.callTopic') }}</th><th>{{ t('vetting.coverage.reviewScope') }}</th><th>{{ t('vetting.coverage.sourceRequests') }}</th><th>{{ t('vetting.coverage.callSubmitted') }}</th><th>{{ t('vetting.coverage.callAssessments') }}</th><th>{{ t('vetting.coverage.callFindings') }}</th><th>{{ t('vetting.coverage.callContext') }}</th></tr></thead>
            <tbody>
              <tr v-for="(topic, topicRow) in coverage.semanticTopics" :key="`${topic.reviewKind ?? 'topic'}:${topic.topicIndex}:${topicRow}`">
                <td><strong>#{{ topic.topicIndex }}</strong><span v-if="topic.reviewKind === 'project_reference'" class="tag neutral">{{ t('vetting.coverage.projectReference') }}</span><div class="small muted">{{ topic.topic }}</div><div v-if="topic.referenceIds?.length" class="small">{{ topic.referenceIds.join(' · ') }}</div></td>
                <td>
                  <span class="tag" :class="['failed', 'partial', 'not_submitted'].includes(reviewScopeStatus(topic)) ? 'warn' : 'neutral'">{{ t(`vetting.coverage.aggregateStates.${reviewScopeStatus(topic)}`) }}</span>
                  <div class="small muted">{{ t('vetting.coverage.globalCall') }}: {{ callStatusLabel(topic.globalCallStatus ?? topic.status) }}</div>
                  <div v-if="topic.error" class="warning-note">{{ topic.error }}</div>
                </td>
                <td>
                  <div>{{ t('vetting.coverage.pendingRequests') }}: {{ topic.pendingSourceRequestCount ?? t('vetting.coverage.unknownCount') }} / {{ topic.sourceRequestCount ?? t('vetting.coverage.unknownCount') }}</div>
                  <div class="small muted">{{ t('vetting.coverage.extraPackets') }}: {{ topic.extraPacketCount ?? t('vetting.coverage.unknownCount') }}</div>
                  <div v-if="topic.failedPacketCount || topic.notSubmittedPacketCount" class="warning-note">{{ t('vetting.coverage.packetFailures') }}: {{ topic.failedPacketCount ?? 0 }} / {{ topic.notSubmittedPacketCount ?? 0 }}</div>
                  <details v-if="topic.packetAudits?.length">
                    <summary class="small">{{ t('vetting.coverage.packetDetails') }}</summary>
                    <div v-for="packet in topic.packetAudits" :key="packet.packetId" class="small section-gap">
                      <strong>#{{ packet.packetIndex }}</strong> {{ callStatusLabel(packet.status) }}
                      <div class="muted">{{ t(`vetting.coverage.budgetStates.${packetBudgetStatus(packet.inputBudgetStatus)}`) }}</div>
                      <div>{{ t('vetting.coverage.actualSubmission') }}: {{ packet.actualGatewayCallStarted === true ? t('vetting.coverage.submitted') : packet.actualGatewayCallStarted === false ? t('vetting.coverage.notSubmitted') : t('vetting.coverage.unknownCount') }}</div>
                      <div v-if="packet.inputBudgetMetadata?.observation">{{ t('vetting.coverage.tokenCounts') }}: {{ packet.inputBudgetMetadata.observation.inputTokens ?? t('vetting.coverage.unknownCount') }} + {{ packet.inputBudgetMetadata.observation.outputReserveTokens ?? t('vetting.coverage.unknownCount') }} / {{ packet.inputBudgetMetadata.observation.effectiveContextTokens ?? t('vetting.coverage.unknownCount') }}</div>
                      <div v-if="packet.failureKind" class="warning-note">{{ t('vetting.coverage.failureReason') }}: {{ packet.failureKind }}</div>
                      <div v-if="packet.error" class="warning-note">{{ packet.error }}</div>
                      <details><summary>{{ t('vetting.coverage.packetIdentity') }}</summary>
                        <div class="mono" style="overflow-wrap:anywhere">{{ t('vetting.coverage.fullInputPacket') }}: {{ packet.packetId }}</div>
                        <div v-if="packet.sourceObservationPacketId" class="mono" style="overflow-wrap:anywhere">{{ t('vetting.coverage.sourceObservationPacket') }}: {{ packet.sourceObservationPacketId }}</div>
                        <div v-if="packet.sourceSnapshotSha256" class="mono" style="overflow-wrap:anywhere">{{ t('vetting.coverage.packetSnapshot') }}: {{ packet.sourceSnapshotSha256 }}</div>
                      </details>
                    </div>
                  </details>
                  <details v-if="topic.sourceRequests?.length">
                    <summary class="small">{{ t('vetting.coverage.requestDetails') }}</summary>
                    <div v-for="request in topic.sourceRequests" :key="request.requestId" class="small section-gap">
                      <div>{{ requestStateLabel(request.transportStatus, 'transportStates') }} · {{ requestStateLabel(request.reviewExecutionStatus, 'requestStates') }}</div>
                      <div v-if="request.unknownReason" class="warning-note">{{ request.unknownReason }}</div>
                      <div>{{ t('vetting.coverage.requiredMembers') }}: {{ request.requiredChunkIds?.length ?? t('vetting.coverage.unknownCount') }} · {{ t('vetting.coverage.missingMembers') }}: {{ request.missingChunkIds?.length ?? t('vetting.coverage.unknownCount') }}</div>
                      <div v-if="request.packetId" class="mono" style="overflow-wrap:anywhere">{{ t('vetting.coverage.fullInputPacket') }}: {{ request.packetId }}</div>
                    </div>
                  </details>
                </td>
                <td>{{ topic.submittedChunkIds.length }} / {{ topic.submittedChars }}</td>
                <td>{{ topic.issueAssessments }} / {{ topic.consistentAssessments }} / {{ topic.insufficientContextAssessments }}</td>
                <td>{{ topic.acceptedFindings }} / {{ topic.rejectedRecords }}</td>
                <td>{{ topic.budgetDroppedGroups }} / {{ topic.partialContextGroups }} / {{ topic.unresolvedSegments }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </details>

    <!-- 指标卡 -->
    <div class="layout-grid grid-4">
      <button
        v-for="card in metricCards()"
        :key="card.group"
        type="button"
        class="metric"
        :class="[GROUP_CLASS[card.group], { active: filters.group === card.group }]"
        @click="setGroup(card.group)"
      >
        <span class="label">{{ card.label }}</span>
        <span class="value">{{ card.value }}</span>
        <span class="detail">{{ card.detail }}</span>
      </button>
    </div>

    <!-- 工具栏 -->
    <div class="surface section-gap">
      <div class="surface-body">
        <div class="toolbar">
          <input
            type="search"
            v-model="filters.search"
            :placeholder="t('vetting.toolbar.searchPlaceholder')"
          />
          <select v-model="filters.group">
            <option value="all">{{ t('vetting.toolbar.allTypes') }}</option>
            <option value="reference">{{ t('vetting.metrics.reference') }}</option>
            <option value="conflict">{{ t('vetting.metrics.conflict') }}</option>
            <option value="language">{{ t('vetting.metrics.language') }}</option>
            <option value="risk">{{ t('vetting.metrics.risk') }}</option>
          </select>
          <select v-model="filters.scope">
            <option value="all">{{ t('vetting.toolbar.allScopes') }}</option>
            <option value="intra">{{ t('vetting.toolbar.intra') }}</option>
            <option value="inter">{{ t('vetting.toolbar.inter') }}</option>
          </select>
          <select v-model="filters.fileKey">
            <option value="all">{{ t('common.file') }}</option>
            <option v-for="fileKey in sourceFileKeys" :key="fileKey" :value="fileKey">{{ fileKey }}</option>
          </select>
          <span class="spacer" />
          <span class="muted small">{{ findings.length }} / {{ metrics?.total ?? 0 }}</span>
          <button class="btn" type="button" :disabled="loading" @click="reload">
            <AppIcon name="refresh" :size="15" />{{ t('common.refresh') }}
          </button>
        </div>
      </div>
    </div>

    <!-- 三栏工作台 -->
    <div class="vetting-workbench section-gap">
      <div class="locator-panel">
        <div class="locator-title">{{ t('vetting.locator.file') }}</div>
        <div class="locator-list">
          <button
            type="button"
            class="locator-item"
            :class="{ active: filters.fileKey === 'all' }"
            @click="filters.fileKey = 'all'"
          >
            <span>{{ t('vetting.locator.all') }}</span>
            <span class="count">{{ metrics?.total ?? 0 }}</span>
          </button>
          <button
            v-for="bucket in fileBuckets"
            :key="bucket.key"
            type="button"
            class="locator-item"
            :class="{ active: filters.fileKey === bucket.key }"
            @click="setFile(bucket.key)"
          >
            <span>{{ bucket.key }}</span>
            <span class="count">{{ bucket.count }}</span>
          </button>
        </div>
      </div>

      <div class="locator-panel">
        <div class="locator-title">
          {{ canUseBuckets ? t('vetting.locator.variable') : t('vetting.locator.page') }}
        </div>
        <div class="locator-list">
          <button
            type="button"
            class="locator-item"
            :class="{ active: canUseBuckets ? !filters.search : filters.page === 'all' }"
            @click="
              canUseBuckets ? (filters.search = '') : (filters.page = 'all')
            "
          >
            <span>{{ t('vetting.locator.all') }}</span>
          </button>
          <button
            v-for="bucket in secondBuckets"
            :key="bucket.key"
            type="button"
            class="locator-item"
            :class="{ active: bucketActive(bucket.key) }"
            @click="setBucket(bucket.key)"
          >
            <span>{{ canUseBuckets ? bucketLabel(bucket.key) : bucket.key }}</span>
            <span class="count">{{ bucket.count }}</span>
          </button>
        </div>
      </div>

      <div class="finding-list">
        <div v-if="!findings.length" class="empty-state">{{ t('vetting.list.empty') }}</div>
        <button
          v-for="finding in findings"
          :key="finding.code"
          type="button"
          class="finding"
          :class="[severityClass(finding), { active: selectedCode === finding.code }]"
          @click="openFinding(finding)"
        >
          <div class="finding-top">
            <span class="code mono">{{ finding.code }}</span>
            <span class="tag" :class="tagClass(finding.group)">
              {{ t(`vetting.metrics.${finding.group}`) }}
            </span>
            <span class="tag" :class="finding.scope === 'inter' ? 'scope-inter' : 'neutral'">
              {{ finding.scope === 'inter' ? t('vetting.toolbar.inter') : t('vetting.toolbar.intra') }}
            </span>
            <span class="tag neutral">{{ finding.severity }}</span>
            <span class="tag" :class="finding.status.toUpperCase() === 'HANDLED' ? 'ok' : 'neutral'">{{ statusLabel(finding.status) }}</span>
            <span class="spacer" />
            <span class="finding-meta">{{ finding.fileKey }} · {{ finding.pageNo }}</span>
          </div>
          <div class="finding-title">{{ pick(finding.title) }}</div>
          <div class="finding-meta">
            <span class="mono">{{ finding.refs }}</span>
            <span v-if="finding.verification" class="tag" :class="finding.verification === 'verified' ? 'ok' : 'warn'">
              {{ t(`vetting.verification.${finding.verification}`) }}
            </span>
            <span v-else-if="finding.evidenceId === 'unverified'" class="tag warn">
              {{ t('vetting.drawer.unverified') }}
            </span>
            <span v-if="finding.source" class="tag neutral">{{ t(`vetting.findingSource.${finding.source}`) }}</span>
          </div>
          <div class="finding-body">{{ pick(finding.body) }}</div>
        </button>
      </div>
    </div>

    <!-- 详情抽屉 -->
    <div class="drawer-backdrop" :class="{ show: drawerOpen }" @click="closeDrawer" />
    <aside class="finding-drawer" :class="{ open: drawerOpen }" role="dialog"
      :aria-label="t('vetting.drawer.title')" :aria-hidden="!drawerOpen" :inert="!drawerOpen">
      <div class="finding-drawer-head">
        <div>
          <h4>{{ t('vetting.drawer.title') }}</h4>
          <p>{{ t('vetting.drawer.subtitle') }}</p>
        </div>
        <button class="btn icon-only" type="button" :aria-label="t('common.close')" @click="closeDrawer">
          <AppIcon name="close" />
        </button>
      </div>

      <div v-if="selectedFinding" class="finding-drawer-body">
        <div class="finding-detail">
          <div>
            <span class="code mono">{{ selectedFinding.code }}</span>
            <h4 style="margin: 6px 0 0">{{ pick(selectedFinding.title) }}</h4>
          </div>

          <div class="detail-grid">
            <div class="detail-cell">
              <div class="detail-title">{{ t('vetting.drawer.type') }}</div>
              <div class="detail-value">
                {{ t(`vetting.metrics.${selectedFinding.group}`) }}
                <span class="mono muted">（{{ selectedFinding.types.join(' / ') }}）</span>
              </div>
            </div>
            <div class="detail-cell">
              <div class="detail-title">{{ t('vetting.drawer.scope') }}</div>
              <div class="detail-value">
                {{ selectedFinding.scope === 'inter' ? t('vetting.toolbar.inter') : t('vetting.toolbar.intra') }}
              </div>
            </div>
            <div class="detail-cell">
              <div class="detail-title">{{ t('vetting.drawer.reference') }}</div>
              <div class="detail-value mono">{{ selectedFinding.refs }}</div>
            </div>
            <div class="detail-cell">
              <div class="detail-title">{{ t('vetting.drawer.status') }}</div>
              <div class="detail-value">{{ statusLabel(selectedFinding.status) }}</div>
            </div>
          </div>

          <div class="detail-block">
            <h5>{{ t('vetting.drawer.location') }}</h5>
            <p>{{ selectedFinding.location }}</p>
          </div>

          <div class="detail-block" v-if="selectedFinding.expected">
            <h5>{{ t('vetting.drawer.expected') }}</h5>
            <p class="mono">{{ selectedFinding.expected }}</p>
          </div>

          <div class="detail-block" v-if="selectedFinding.pattern">
            <h5>{{ t('vetting.drawer.pattern') }}</h5>
            <p class="mono">{{ selectedFinding.pattern }}</p>
          </div>

          <div class="detail-block">
            <h5>{{ t('vetting.drawer.comment') }}</h5>
            <p>{{ pick(selectedFinding.body) }}</p>
          </div>

          <div class="detail-block" v-if="pick(selectedFinding.impact)">
            <h5>{{ t('vetting.drawer.reason') }}</h5>
            <p>{{ pick(selectedFinding.impact) }}</p>
          </div>

          <div class="detail-block">
            <h5>{{ t('vetting.drawer.suggestion') }}</h5>
            <p>{{ pick(selectedFinding.suggestion) }}</p>
          </div>

          <div class="detail-block">
            <h5>
              {{ t('vetting.drawer.evidence') }}
              <span v-if="selectedFinding.verification" class="tag" :class="selectedFinding.verification === 'verified' ? 'ok' : 'warn'">
                {{ t(`vetting.verification.${selectedFinding.verification}`) }}
              </span>
            </h5>
            <p class="muted small">{{ t('vetting.coverage.candidateScopeNote') }}</p>
            <div v-if="evidenceLoading" class="muted small">{{ t('vetting.drawer.locating') }}</div>
            <div v-if="evidenceFailed" class="evidence-missing">
              <span>{{ t('vetting.drawer.evidenceFailed') }}</span>
              <button class="btn" type="button" @click="openFinding(selectedFinding)">{{ t('common.refresh') }}</button>
            </div>
            <div v-if="evidenceItems.length" class="evidence-stack">
              <div
                v-for="(item, index) in evidenceItems"
                :key="index"
                class="evidence-item"
              >
                <div class="meta">
                  <span class="tag info">{{ evidenceSide(item.side) }}</span>
                  <code>{{ item.fileKey }}</code>
                  <span>{{ item.fileName }}</span>
                  <span v-if="item.pageNo" class="tag neutral mono">{{ item.pageNo }}</span>
                  <span v-if="item.anchor" class="mono muted small">{{ item.anchor }}</span>
                  <span class="tag" :class="item.located ? 'ok' : 'warn'">{{ item.located ? t('vetting.drawer.located') : t('vetting.drawer.unverified') }}</span>
                </div>
                <div class="quote">{{ item.quote }}</div>
                <div v-if="item.packetId || item.packetSourceSnapshotSha256" class="small">
                  <div class="mono" style="overflow-wrap:anywhere">{{ t('vetting.coverage.fullInputPacket') }}: {{ item.packetId ?? t('vetting.coverage.unknownCount') }}</div>
                  <div class="mono" style="overflow-wrap:anywhere">{{ t('vetting.coverage.packetSnapshot') }}: {{ item.packetSourceSnapshotSha256 ?? t('vetting.coverage.unknownCount') }}</div>
                </div>
                <p v-else class="muted small">{{ t('vetting.coverage.legacyPacketUnknown') }}</p>
                <a v-if="originalSourceUrl(item)" class="btn" :href="originalSourceUrl(item) ?? undefined" target="_blank" rel="noopener noreferrer">{{ t('vetting.drawer.openOriginal') }}</a>
                <p v-if="!item.located" class="warning-note">{{ t('vetting.drawer.notLocated') }}</p>
              </div>
            </div>
            <div v-else-if="!evidenceLoading && !evidenceFailed" class="evidence-missing">
              <AppIcon name="alert" :size="14" />
              <span>{{ t('vetting.drawer.notLocated') }}</span>
            </div>
          </div>

          <div class="detail-actions">
            <div class="review-fields">
              <h5>{{ t('vetting.review.team') }}</h5>
              <label class="review-field">
                <span>{{ t('vetting.review.remarks') }}</span>
                <textarea v-model="reviewDraft.reviewRemarks" rows="3" maxlength="4000" :disabled="reviewSaving || running" />
              </label>
              <label class="review-field">
                <span>{{ t('vetting.review.actionTaken') }}</span>
                <textarea v-model="reviewDraft.actionTaken" rows="3" maxlength="4000" :disabled="reviewSaving || running" />
              </label>
              <label class="review-field">
                <span>{{ t('vetting.review.addendum') }}</span>
                <select v-model="reviewDraft.addendum" :disabled="reviewSaving || running">
                  <option value="undecided">{{ t('vetting.review.undecided') }}</option>
                  <option value="required">{{ t('vetting.review.required') }}</option>
                  <option value="notRequired">{{ t('vetting.review.notRequired') }}</option>
                </select>
              </label>
              <p v-if="selectedFinding.reviewUpdatedAt" class="small muted">{{ t('vetting.review.savedAt') }} {{ new Date(selectedFinding.reviewUpdatedAt).toLocaleString() }}</p>
              <p v-if="reviewFailed" class="warning-note" role="alert">{{ t('vetting.review.saveFailed') }}</p>
              <div class="row">
                <button class="btn primary" type="button" :disabled="reviewSaving || statusSaving || running || !reviewDirty" @click="saveReview">{{ t('vetting.review.save') }}</button>
                <button class="btn" type="button" :disabled="reviewSaving || !reviewDirty" @click="discardReview">{{ t('vetting.review.discard') }}</button>
                <span v-if="reviewDirty" class="small muted">{{ t('vetting.review.unsaved') }}</span>
              </div>
            </div>
            <button
              class="btn success"
              type="button"
              :disabled="statusSaving || reviewSaving || running || selectedFinding.status.toUpperCase() === 'HANDLED'"
              @click="updateStatus('Handled')"
            >
              <AppIcon name="check" :size="15" />{{ t('vetting.drawer.markHandled') }}
            </button>
            <button
              class="btn"
              type="button"
              :disabled="statusSaving || reviewSaving || running || selectedFinding.status.toUpperCase() === 'ASSIGNED'"
              @click="updateStatus('Assigned')"
            >
              <AppIcon name="external" :size="15" />{{ t('vetting.drawer.assign') }}
            </button>
            <button class="btn" type="button" :disabled="statusSaving || reviewSaving || running || selectedFinding.status.toUpperCase() === 'OPEN'" @click="updateStatus('Open')">
              {{ t('vetting.review.reopen') }}
            </button>
          </div>
        </div>
      </div>
    </aside>

    <!-- 审查源集 -->
    <AppModal :open="sourceOpen" :title="t('vetting.actions.source')" wide @close="sourceOpen = false">
      <div class="table-wrap vetting-source-table">
        <table>
          <thead>
            <tr>
              <th>{{ t('common.file') }}</th>
              <th>{{ t('common.type') }}</th>
              <th>{{ t('common.status') }}</th>
              <th>{{ t('common.page') }}</th>
              <th>{{ t('vetting.coverage.warnings') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="file in files" :key="file.key + ':' + file.fileName">
              <td>
                <strong class="mono">{{ file.key }}</strong>
                <div class="muted small">{{ file.fileName }}</div>
              </td>
              <td>{{ pick(file.role) }}</td>
              <td>
                <span class="tag" :class="file.parsed ? 'ok' : 'warn'">{{ file.status }}</span>
              </td>
              <td>{{ file.pageCount || '—' }}</td>
              <td><div class="small">{{ file.parseMessage }}</div><div v-for="(warning, index) in file.warnings" :key="index" class="warning-note">{{ warning }}</div><span v-if="!file.parseMessage && !file.warnings?.length">—</span></td>
            </tr>
          </tbody>
        </table>
      </div>
    </AppModal>
  </section>
</template>

<style scoped>
.vetting-source-table table { min-width: 860px; }
.vetting-source-table th:nth-child(1), .vetting-source-table td:nth-child(1) { min-width: 200px; }
.vetting-source-table th:nth-child(2), .vetting-source-table td:nth-child(2) { min-width: 110px; }
.vetting-source-table th:nth-child(3), .vetting-source-table td:nth-child(3),
.vetting-source-table th:nth-child(4), .vetting-source-table td:nth-child(4) { white-space: nowrap; }
.vetting-source-table th:nth-child(5), .vetting-source-table td:nth-child(5) { min-width: 300px; }
.vetting-call-table table { min-width: 1050px; }
.vetting-call-table th:first-child, .vetting-call-table td:first-child { width: 250px; }
.vetting-call-table th:nth-child(3), .vetting-call-table td:nth-child(3) { min-width: 300px; width: 300px; overflow-wrap: anywhere; }
.report-format { width: auto; min-width: 112px; }
.upload-role { display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--muted); }
.upload-role select { width: auto; min-width: 150px; }
.checklist-panel { padding: 16px; }
.checklist-panel summary { cursor: pointer; font-weight: 700; }
.checklist-table { max-height: 520px; overflow: auto; }
.checklist-table th { position: sticky; top: 0; background: var(--surface, #fff); z-index: 1; }
.checklist-table td:nth-child(2) { min-width: 280px; }
.checklist-table textarea { min-width: 260px; min-height: 70px; }
.vetting-run-panel p { margin: 10px 0 0; }
.vetting-progress { width: 100%; height: 9px; margin-top: 10px; accent-color: var(--accent); }
.coverage-panel summary { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; padding: 14px 18px; cursor: pointer; }
.coverage-panel summary::before { content: '▸'; color: var(--muted); }
.coverage-panel[open] summary::before { content: '▾'; }
.coverage-panel .surface-body { padding-top: 0; }
.coverage-warnings { margin: 8px 0 12px; padding-left: 20px; color: var(--amber); font-size: 12px; }
.warning-note { color: var(--amber); font-size: 12px; margin: 5px 0; overflow-wrap: anywhere; }
.run-error { color: var(--red, #b42318); }
.evidence-item .quote { overflow-wrap: anywhere; }
.detail-actions { flex-wrap: wrap; }
.review-fields { flex: 0 0 100%; display: grid; gap: 12px; margin-bottom: 14px; }
.review-field { display: grid; gap: 6px; font-size: 12px; }
.review-field textarea { width: 100%; resize: vertical; font: inherit; padding: 9px 10px; }
</style>
