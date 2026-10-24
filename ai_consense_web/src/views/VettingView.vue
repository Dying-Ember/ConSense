<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { vettingApi } from '@/api'
import { ApiError } from '@/api/client'
import type { Citation, EvidenceBundle, Finding, FindingWriteBinding, FindingEvidence, VettingFile, VettingJob, VettingProgress, VettingMetrics, VettingReportFormat, VettingSemanticTopic, VettingSourceRole, VettingChecklist, VettingChecklistItem } from '@/api/types'
import { useAppStore } from '@/stores/app'
import { useLocalized } from '@/composables/useLocalized'
import AppIcon from '@/components/AppIcon.vue'
import AppModal from '@/components/AppModal.vue'
import CanonicalEvidencePanel from '@/components/CanonicalEvidencePanel.vue'
import FixedBaselineEvidencePanel from '@/components/FixedBaselineEvidencePanel.vue'
import AdviceSourceReader from '@/components/AdviceSourceReader.vue'
import { createReviewHandoff, newReviewHandoffToken, removeReviewHandoff, reviewHandoffCopy, storeReviewHandoff } from '@/advice/reviewHandoff'
import { uploadVettingFiles, vettingUploadQueue } from '@/vetting/upload-queue'

const { t } = useI18n()
const store = useAppStore()
const projectId = computed(() => store.activeProjectId)
const router = useRouter()
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
function checkStateLabel(status?: string): string { return t(`vetting.checkStates.${status && ['checked_pass', 'issue', 'insufficient_evidence', 'not_completed', 'not_applicable'].includes(status) ? status : 'not_completed'}`) }

function jobFailureMessageKey(value?: string | null): string | null {
  const message = value?.trim() ?? ''
  if (message.startsWith('Model response source attribution invalid:')) return 'vetting.job.sourceAttributionFailed'
  if (message.startsWith('Structured output must be one complete JSON value')) return 'vetting.job.resultFormatFailed'
  return null
}
function readerJobMessage(value?: string | null): string {
  const key = jobFailureMessageKey(value)
  return key ? t(key) : value ?? ''
}

const files = ref<VettingFile[]>([])
const findings = ref<Finding[]>([])
const metrics = ref<VettingMetrics | null>(null)
type ObservedJob = VettingJob & { sourceVerification?: 'NOT_CHECKED' }
const job = ref<ObservedJob | null>(null)
const starting = ref(false)
const uploadQueue = computed(() => projectId.value ? vettingUploadQueue(projectId.value) : null)
const uploading = computed(() => uploadQueue.value?.running ?? false)
const uploadProgress = computed(() => uploadQueue.value?.progress ?? null)
watch(() => uploadQueue.value?.sources, sources => { if (sources) files.value = sources }, { immediate: true })
const sourceProgress = ref<import('@/api/types').VettingSourceProgress[]>([])
let sourceProgressTimer: number | undefined
let sourceProgressSequence = 0
const sourceProgressFailed = ref(false)
const sourceProgressCopy = {
  currentFile: { zhHans: '当前文件：', zhHant: '目前檔案：', en: 'Current file: ' },
  method: { zhHans: '处理方法：', zhHant: '處理方法：', en: 'Reported method: ' },
  page: { zhHans: '当前物理页：', zhHant: '目前實體頁：', en: 'Current physical page: ' },
  step: { zhHans: '当前步骤：', zhHant: '目前步驟：', en: 'Current step: ' },
  unknownMethod: { zhHans: '后端尚未报告处理方法', zhHant: '後端尚未回報處理方法', en: 'The backend has not reported the processing method' },
  unknownPage: { zhHans: '后端尚未报告当前页码', zhHant: '後端尚未回報目前頁碼', en: 'The backend has not reported the current page' },
  waiting: { zhHans: '等待后端报告当前文件处理进度', zhHant: '等候後端回報目前檔案處理進度', en: 'Waiting for backend progress for this file' },
  visual: { zhHans: '视觉识别（具体引擎未报告）', zhHant: '視覺識別（具體引擎未回報）', en: 'Visual recognition (specific engine not reported)' },
  native: { zhHans: '原生文字读取', zhHant: '原生文字讀取', en: 'Native text reading' },
  render: { zhHans: '页面渲染', zhHant: '頁面渲染', en: 'Page rendering' },
  textLayout: { zhHans: '文字、表格与版面读取', zhHant: '文字、表格與版面讀取', en: 'Reading text, tables and layout' },
  layout: { zhHans: '表格与版面核对', zhHant: '表格與版面核對', en: 'Checking tables and layout' },
  file: { zhHans: '文件正文与结构读取', zhHant: '檔案正文與結構讀取', en: 'Reading file content and structure' },
  lastReported: { zhHans: '最近已报告进度；本次读取失败，当前状态未确认。', zhHant: '最近已回報進度；本次讀取失敗，目前狀態未確認。', en: 'Last reported progress; this read failed and the current state is unconfirmed.' }
}
const currentFileProgress = computed(() => {
  const queue = uploadProgress.value
  const pending = queue && queue.state !== 'completed'
  const row = pending
    ? sourceProgress.value.find(item => item.fileName === queue.fileName && (item.parseStatus === 'PARSING' || queue.completed >= queue.current))
    : sourceProgress.value.find(item => item.parseStatus === 'PARSING')
  return pending || row ? { fileName: pending ? queue.fileName : row!.fileName, row } : null
})
const currentFileDetails = computed(() => {
  const row = currentFileProgress.value?.row, message = row?.parseMessage?.trim() ?? ''
  const page = message.match(/^第\s*(\d+)\s*\/\s*(\d+)\s*页[：:]\s*(.*)$/s)
  const validPage = page && Number(page[1]) > 0 && Number(page[1]) <= Number(page[2])
  let method = sourceProgressCopy.unknownMethod
  if (message.includes('等待视觉识别结果')) method = sourceProgressCopy.visual
  else if (/读取原生(?:PDF)?(?:文字|文本)/.test(message)) method = sourceProgressCopy.native
  else if (message.includes('渲染页面')) method = sourceProgressCopy.render
  else if (message.includes('读取文字、表格与版面')) method = sourceProgressCopy.textLayout
  else if (message.includes('核对表格')) method = sourceProgressCopy.layout
  else if (message.startsWith('读取文件正文与结构')) method = sourceProgressCopy.file
  return {
    method: pick(method),
    page: validPage ? `${page![1]} / ${page![2]}` : `${pick(sourceProgressCopy.unknownPage)}${row?.pageCount ? ` · ${row.pageCount} ${t('vetting.uploadProgress.pages')}` : ''}`,
    step: validPage ? page![3] : message || pick(sourceProgressCopy.waiting)
  }
})
async function pollSourceProgress() {
  if (sourceProgressTimer) window.clearTimeout(sourceProgressTimer)
  const id = projectId.value, epoch = viewEpoch, sequence = ++sourceProgressSequence
  if (!id || disposed) return
  try {
    const rows = await vettingApi.sourceProgress(id)
    if (disposed || epoch !== viewEpoch || id !== projectId.value || sequence !== sourceProgressSequence) return
    const finished = sourceProgress.value.some(previous => previous.parseStatus === 'PARSING'
      && rows.some(row => row.id === previous.id && row.parseStatus !== 'PARSING'))
    if (finished) await reload()
    if (disposed || epoch !== viewEpoch || id !== projectId.value || sequence !== sourceProgressSequence) return
    sourceProgress.value = rows
    sourceProgressFailed.value = false
    if (rows.some(row => row.parseStatus === 'PARSING') || uploading.value) {
      sourceProgressTimer = window.setTimeout(() => { void pollSourceProgress() }, 3000)
    }
  } catch {
    if (!disposed && epoch === viewEpoch && id === projectId.value && sequence === sourceProgressSequence) {
      sourceProgressFailed.value = true
      sourceProgressTimer = window.setTimeout(() => { void pollSourceProgress() }, 5000)
    }
  }
}
const statusSaving = ref(false)
const reviewSaving = ref(false)
const reviewFailed = ref(false)
type ReviewDraft = { reviewRemarks: string; actionTaken: string; addendum: 'undecided' | 'required' | 'notRequired' }
const emptyReview = (): ReviewDraft => ({ reviewRemarks: '', actionTaken: '', addendum: 'undecided' })
const reviewDraft = ref<ReviewDraft>(emptyReview())
const reviewBaseline = ref<ReviewDraft>(emptyReview())
const reviewCode = ref<string | null>(null)
const reviewVersion = ref<string | null>(null)
const reviewChanged = ref(false)
const reviewDrafts = ref(new Map<string, { code: string; draft: ReviewDraft }>())
const reviewDirty = computed(() => JSON.stringify(reviewDraft.value) !== JSON.stringify(reviewBaseline.value))
const hasUnsavedReview = computed(() => reviewDirty.value || [...reviewDrafts.value.keys()].some(key => key !== reviewVersion.value))
const retainedReviews = computed(() => [...reviewDrafts.value.entries()].filter(([key]) => key !== reviewVersion.value).map(([key, saved]) => ({ key, ...saved })))
const unsavedReviewCodes = computed(() => [...new Set([
  ...[...reviewDrafts.value.entries()].filter(([key]) => key !== reviewVersion.value).map(([, saved]) => saved.code),
  ...(reviewDirty.value && reviewCode.value ? [reviewCode.value] : [])
])])
const versionCopy = {
  changed: { zhHans: '报告或来源版本已变化。草稿已保留，请刷新并核对当前版本后再填写；旧草稿不会自动写入新报告。', zhHant: '報告或來源版本已變化。草稿已保留，請刷新並核對目前版本後再填寫；舊草稿不會自動寫入新報告。', en: 'The report or source version changed. Your draft is retained. Refresh and review the current version before editing; old drafts are not applied to the new report.' },
  refresh: { zhHans: '刷新当前版本', zhHant: '刷新目前版本', en: 'Refresh current version' },
  retained: { zhHans: '保留的旧版本草稿', zhHant: '保留的舊版本草稿', en: 'Retained draft from an earlier version' },
  discard: { zhHans: '丢弃保留草稿', zhHant: '捨棄保留草稿', en: 'Discard retained drafts' }
}
const exporting = ref(false)
const pollingPaused = ref(false)
const reportFormat = ref<VettingReportFormat>('pdf')
const sourceRoleSaving = ref<string | null>(null)
const sourceRoleDrafts = ref<Record<string, string>>({})
const sourceIdentityConfirmations = ref<Record<string, boolean>>({})
const sourceRoleFeedback = ref('')
const sourceRoleNeedsRefresh = ref(false)
const SOURCE_ROLES: VettingSourceRole[] = ['tender', 'standard', 'project_fact', 'package_manifest']
const checklist = ref<VettingChecklist | null>(null)
const checklistBaseline = ref<VettingChecklistItem[]>([])
const checklistLoading = ref(false)
const checklistSaving = ref<string | null>(null)
let checklistSequence = 0
const retainedChecklists = ref<VettingChecklist[]>([])
const checklistChanged = ref(false)
const currentChecklistDirty = computed(() => checklist.value !== null && JSON.stringify(checklist.value.items) !== JSON.stringify(checklistBaseline.value))
const checklistDirty = computed(() => currentChecklistDirty.value || retainedChecklists.value.length > 0)
const checklistWritable = computed(() => sourceRoleSaving.value === null && checklist.value?.writable && !checklistChanged.value && checklist.value.runId === job.value?.id)
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
let jobReadSequence = 0
let pendingJobRead: { projectId: string; epoch: number; sequence: number; runId?: string; readSequence: number } | null = null
let disposed = false
const packageInput = ref<HTMLInputElement | null>(null)
const sourceOpen = ref(false)

const filters = ref({ search: '', group: 'all', scope: 'all', fileKey: 'all', page: 'all' })
const selectedCode = ref<string | null>(null)
const drawerOpen = ref(false)
const evidence = ref<EvidenceBundle | null>(null)
const sourceCitation = ref<Citation | null>(null)

const GROUPS = ['reference', 'conflict', 'language', 'risk', 'gap', 'substantive', 'requirement'] as const

const GROUP_CLASS: Record<string, string> = {
  reference: 'type-ref',
  conflict: 'type-conflict',
  language: 'type-lang',
  risk: 'type-risk',
  gap: 'type-gap',
  substantive: 'type-substantive',
  requirement: 'type-requirement'
}

const GROUP_TAG: Record<string, string> = {
  reference: 'info',
  conflict: 'danger',
  language: 'warn',
  risk: 'demo',
  gap: 'warn',
  substantive: 'info',
  requirement: 'danger'
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
const handoffOpening = ref(false)
const handoffDisabled = computed(() => !projectId.value || !selectedFinding.value || handoffOpening.value || evidenceLoading.value || running.value || job.value?.sourceVerification === 'NOT_CHECKED' || sourceRoleSaving.value !== null || uploading.value || reviewSaving.value || statusSaving.value || hasUnsavedReview.value || checklistDirty.value || checklistSaving.value !== null)
const jobDetailsPending = computed(() => job.value?.status === 'COMPLETED' && job.value.sourceVerification === 'NOT_CHECKED')
const running = computed(() => starting.value || isActiveJob(job.value) || jobDetailsPending.value)
const reviewActive = computed(() => starting.value || isActiveJob(job.value))
const jobModelIdentity = computed(() => job.value?.modelIdentity ?? job.value?.result?.modelIdentity)
const recordedJobModel = computed(() => jobModelIdentity.value?.model || job.value?.result?.model)
const jobFailureMessages = computed(() => [...new Set([readerJobMessage(job.value?.error), readerJobMessage(job.value?.message)].filter(Boolean))])
const exportDisabled = computed(() => running.value || sourceRoleSaving.value !== null || uploading.value || exporting.value || statusSaving.value || reviewSaving.value || hasUnsavedReview.value || checklistDirty.value || checklistSaving.value !== null || job.value?.sourceSetStale === true || job.value?.status !== 'COMPLETED' || job.value?.sourceVerification === 'NOT_CHECKED')
const coverage = computed(() => job.value?.coverage ?? job.value?.result?.coverage ?? null)
const sourceRoleBlocked = computed(() => running.value || uploading.value || exporting.value || sourceRoleSaving.value !== null
  || sourceRoleNeedsRefresh.value || reviewSaving.value || statusSaving.value || hasUnsavedReview.value || checklistDirty.value || checklistSaving.value !== null)

function sourceParseLabel(status?: string) {
  if (status === 'PARSED') return t('advice.sourceScope.parsed')
  if (status === 'PARTIAL') return t('advice.sourceScope.partiallyParsed')
  const states: Record<string, string> = { PARSING: 'parsing', IDENTITY_OBSERVED: 'awaitingResult', IDENTITY_PENDING: 'identityPending', PENDING: 'pending', FAILED: 'failed', PARSE_FAILED: 'failed', EXCLUDED: 'excluded', GENERATED: 'generated' }
  return t(`vetting.sourceProcessing.${states[status ?? ''] ?? 'unknown'}`)
}
function sourcePurposeLabel(file: VettingFile) {
  if (isExcludedDrawing(file)) return t('vetting.coverage.drawingType')
  if (file.sourceRole === 'unknown') return t(`vetting.sourceProcessing.${canConfirmSourceRole(file) ? 'purposePending' : 'purposeUnresolved'}`)
  return pick(file.role)
}
function sourceRoleVersion(file: VettingFile) {
  return [file.documentId, file.originalSourceSha256, file.sourceHash, file.routingSha256].join('|')
}
function isPendingSourceIdentity(file: VettingFile) {
  return (file.parseStatus ?? file.status) === 'IDENTITY_PENDING'
}
function canConfirmSourceRole(file: VettingFile) {
  return (isPendingSourceIdentity(file)
    ? file.identityConfirmationAvailable === true && ['unknown', ...SOURCE_ROLES].includes(file.sourceRole ?? '')
    : file.roleConfirmationAvailable === true && file.sourceRole === 'unknown')
    && (!file.exclusionReason || file.exclusionReason === 'source_identity_unresolved')
    && !['drawing', 'pricing', 'works_pss', 'example'].includes(file.documentScope ?? '')
    && ['PARSED', 'PARTIAL', 'IDENTITY_PENDING'].includes(file.parseStatus ?? file.status) && /^\d+$/.test(file.documentId ?? '')
    && [file.originalSourceSha256, file.sourceHash, file.routingSha256].every(value => /^[a-f0-9]{64}$/.test(value ?? ''))
}
function sourceFileOriginalUrl(file: VettingFile) {
  if (!projectId.value || !/^\d+$/.test(file.documentId ?? '') || !file.documentId || !file.originalSourceSha256 || !file.sourceHash || !file.sourceRole
    || ![file.originalSourceSha256, file.sourceHash].every(value => /^[a-f0-9]{64}$/.test(value))
    || !['unknown', ...SOURCE_ROLES].includes(file.sourceRole ?? '')) return null
  return `/api/vetting/${encodeURIComponent(projectId.value)}/sources/${encodeURIComponent(file.documentId)}/original?${new URLSearchParams({ sourceHash: file.sourceHash, originalSourceSha256: file.originalSourceSha256, sourceRole: file.sourceRole })}`
}
async function refreshSources() {
  if (sourceRoleSaving.value !== null || uploading.value) return
  const id = projectId.value, epoch = viewEpoch
  try {
    await reload()
    if (disposed || epoch !== viewEpoch || id !== projectId.value) return
    sourceRoleDrafts.value = {}
    sourceIdentityConfirmations.value = {}
    sourceRoleFeedback.value = ''
    sourceRoleNeedsRefresh.value = false
    await restoreJob()
  } catch {
    if (!disposed && epoch === viewEpoch && id === projectId.value) sourceRoleFeedback.value = t('vetting.sourcePurpose.refreshFailed')
  }
}
async function confirmSourceRole(file: VettingFile) {
  const version = sourceRoleVersion(file)
  const chosen = SOURCE_ROLES.find(role => role === sourceRoleDrafts.value[version])
  const resumeParsing = isPendingSourceIdentity(file)
  if (sourceRoleBlocked.value || !canConfirmSourceRole(file) || !chosen || !file.documentId
    || (resumeParsing && sourceIdentityConfirmations.value[version] !== true)
    || !file.originalSourceSha256 || !file.sourceHash || !file.routingSha256) return
  const id = projectId.value, epoch = viewEpoch, documentId = file.documentId
  const binding = { sourceRole: chosen, expectedOriginalSourceSha256: file.originalSourceSha256,
    expectedSourceHash: file.sourceHash, expectedRoutingSha256: file.routingSha256,
    ...(resumeParsing ? { confirmTextContractMaterial: true } : {}) }
  sourceRoleSaving.value = documentId
  sourceRoleFeedback.value = ''
  let saved = false
  try {
    const updated = await vettingApi.confirmSourceRole(id, documentId, binding)
    if (disposed || epoch !== viewEpoch || id !== projectId.value) return
    // A successful mutation invalidates the displayed report until a current source check says otherwise.
    if (job.value) job.value = { ...job.value, sourceSetStale: true }
    if (selectedDetail.value) reviewChanged.value = true
    const index = files.value.findIndex(current => sourceRoleVersion(current) === version)
    if (index < 0 || updated.documentId !== documentId || updated.sourceRole !== chosen
      || updated.originalSourceSha256 !== binding.expectedOriginalSourceSha256
      || (resumeParsing ? !/^[a-f0-9]{64}$/.test(updated.sourceHash ?? '') || !/^[a-f0-9]{64}$/.test(updated.routingSha256 ?? '')
        || !['PARSING', 'PARSED', 'PARTIAL', 'FAILED', 'PARSE_FAILED'].includes(updated.parseStatus ?? updated.status)
        : updated.sourceHash !== binding.expectedSourceHash)) {
      sourceRoleFeedback.value = t('vetting.sourcePurpose.changed')
      sourceRoleNeedsRefresh.value = true
      return
    }
    files.value.splice(index, 1, updated)
    delete sourceRoleDrafts.value[version]
    delete sourceIdentityConfirmations.value[version]
    saved = true
    sourceRoleFeedback.value = t(`vetting.sourcePurpose.${resumeParsing ? 'parseStarted' : 'saved'}`)
    await reload()
    if (disposed || epoch !== viewEpoch || id !== projectId.value) return
    await restoreJob()
  } catch (error) {
    if (!disposed && epoch === viewEpoch && id === projectId.value) {
      if (resumeParsing || error instanceof ApiError && error.code === 4104) {
        sourceRoleNeedsRefresh.value = true
        if (job.value) job.value = { ...job.value, sourceSetStale: true }
      }
      sourceRoleFeedback.value = saved ? t(`vetting.sourcePurpose.${resumeParsing ? 'parseRefreshFailed' : 'savedRefreshFailed'}`)
        : error instanceof ApiError && error.code === 4104 ? t('vetting.sourcePurpose.changed')
          : t(`vetting.sourcePurpose.${resumeParsing ? 'parseUncertain' : 'failed'}`)
    }
  } finally {
    if (epoch === viewEpoch && id === projectId.value) sourceRoleSaving.value = null
  }
}
const jobPercent = computed(() => {
  if (!job.value?.totalUnits) return null
  return Math.min(100, Math.max(0, Math.round(job.value.completedUnits / job.value.totalUnits * 100)))
})
function isExcludedDrawing(file: VettingFile) {
  return file.exclusionReason === 'drawing_outside_scope'
    || (file.documentScope === 'drawing' && (file.parseStatus === 'EXCLUDED' || file.status === 'EXCLUDED'))
}
const excludedDrawings = computed(() => files.value.filter(isExcludedDrawing))
const sourceWarnings = computed(() => files.value.filter((file) => !isExcludedDrawing(file) && (!file.parsed || file.warnings?.length)))
const pendingListFindings = computed(() => findings.value.filter((finding) => finding.status?.toUpperCase() !== 'HANDLED').length)
const hasCoverageLimits = computed(() => !!coverage.value?.warnings?.length || !!sourceWarnings.value.length
  || (!!coverage.value && coverage.value.reviewedDocuments < coverage.value.totalDocuments))
const evidenceItems = computed<FindingEvidence[]>(() => {
  if (evidence.value) {
    return evidence.value.items.map((item) => ({
      side: item.side ?? 'source', documentId: item.documentId,
      fileKey: item.fileKey ?? item.code, fileName: item.fileName ?? '',
      clauseId: item.clauseId,
      pageNo: item.pageNo, anchor: item.anchor, quote: item.quote ?? item.text,
      located: item.located ?? evidence.value!.located, sourceHash: item.sourceHash, originalSourceSha256: item.originalSourceSha256, sourceRole: item.sourceRole, nativePositions: item.nativePositions, bbox: item.bbox,
      packetId: item.packetId, packetSourceSnapshotSha256: item.packetSourceSnapshotSha256,
      canonicalEvidence: item.canonicalEvidence, fixedBaselineEvidence: item.fixedBaselineEvidence
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
  if (item.fixedBaselineEvidence || /^(?:fixed:|fixed-baseline:|fixed_reference:)/.test(String(item.documentId ?? ''))) return null
  if (!projectId.value || !item.documentId || !item.sourceHash || !item.originalSourceSha256
    || !['tender', 'standard', 'project_fact', 'package_manifest'].includes(item.sourceRole ?? '')) return null
  const page = /^P(\d+)$/i.exec(item.pageNo ?? '')
  const fragment = page && item.fileName.toLowerCase().endsWith('.pdf') ? `#page=${page[1]}` : ''
  return `/api/vetting/${encodeURIComponent(projectId.value)}/sources/${encodeURIComponent(String(item.documentId))}/original?${new URLSearchParams({ sourceHash: item.sourceHash, originalSourceSha256: item.originalSourceSha256, sourceRole: item.sourceRole! })}${fragment}`
}

function isOrdinaryDocx(item: FindingEvidence) {
  return !item.canonicalEvidence && !item.fixedBaselineEvidence && item.fileName.toLowerCase().endsWith('.docx')
}

function openOriginalSource(item: FindingEvidence) {
  if (!isOrdinaryDocx(item) || !originalSourceUrl(item)) return
  sourceCitation.value = {
    evidenceId: selectedFinding.value?.code, documentId: String(item.documentId),
    fileLabel: item.fileName, pageNo: item.pageNo ?? '', anchor: item.anchor ?? '', content: item.quote, score: 0,
    sourceHash: item.sourceHash, originalSourceSha256: item.originalSourceSha256,
    sourceRole: item.sourceRole, nativePositions: item.nativePositions
  }
}

async function consultFinding() {
  if (handoffDisabled.value || !selectedFinding.value) return
  const finding = selectedFinding.value
  let token: string | undefined
  let draftStorage: Storage | undefined
  handoffOpening.value = true
  try {
    token = newReviewHandoffToken()
    draftStorage = sessionStorage
    storeReviewHandoff(draftStorage, createReviewHandoff({
      projectId: projectId.value, findingCode: finding.code, title: finding.title, description: finding.body,
      evidence: evidenceItems.value, sourceSetStale: job.value?.sourceSetStale === true
    }, token))
    const failure = await router.push({ name: 'advice', query: { review: token } })
    if (failure) throw new Error('review_handoff_navigation_failed')
  } catch {
    if (token && draftStorage) removeReviewHandoff(draftStorage, token)
    store.notify(pick(reviewHandoffCopy.transferFailed))
  } finally { handoffOpening.value = false }
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

async function reload(owner?: { sequence: number; runId: string }) {
  const id = projectId.value
  const epoch = viewEpoch
  const seq = ++reloadSequence
  if (!id) return
  loading.value = true
  try {
    const [fileList, findingList, metricData] = await Promise.all([
      vettingApi.files(id), vettingApi.findings(id, { ...filters.value }), vettingApi.metrics(id)
    ])
    if (disposed || epoch !== viewEpoch || seq !== reloadSequence || id !== projectId.value || (owner && !ownsJob(id, epoch, owner.sequence, owner.runId))) return
    files.value = fileList
    findings.value = findingList
    metrics.value = metricData
    const refreshed = findingList.find((item) => item.code === selectedCode.value)
    if (refreshed && selectedDetail.value) {
      if (findingVersion(refreshed) !== findingVersion(selectedDetail.value) && (reviewDirty.value || reviewSaving.value || statusSaving.value)) {
        rememberReviewDraft()
        reviewChanged.value = true
      } else {
        if (findingVersion(refreshed) !== findingVersion(selectedDetail.value)) openFinding(refreshed)
        else {
          selectedDetail.value = refreshed
          if (!reviewDirty.value && !reviewSaving.value) loadReviewDraft(refreshed)
        }
      }
    }
  } finally {
    if (seq === reloadSequence && epoch === viewEpoch) loading.value = false
  }
}

function stopPolling() {
  if (pollTimer) window.clearTimeout(pollTimer)
  pollTimer = undefined
}

function ownsJob(id: string, epoch: number, sequence: number, runId?: string) {
  return !disposed && epoch === viewEpoch && id === projectId.value && sequence === jobSequence
    && (!runId || runId === job.value?.id)
}

function schedulePoll(id: string, runId: string, epoch: number, sequence: number) {
  stopPolling()
  if (!ownsJob(id, epoch, sequence, runId)) return
  pollTimer = window.setTimeout(() => { void pollRun(id, runId, epoch, sequence) }, 2000)
}

async function acceptJob(value: VettingJob, id: string, epoch: number, sequence: number, notifyCompletion = false) {
  if (!ownsJob(id, epoch, sequence)) return
  job.value = value
  pollingPaused.value = false
  if (isActiveJob(value)) {
    schedulePoll(id, value.id, epoch, sequence)
  } else {
    stopPolling()
    if (notifyCompletion && value.status === 'COMPLETED') {
      store.notify(t('vetting.run.finished', { count: value.result?.total ?? 0 }))
      await reload({ sequence, runId: value.id })
      if (!ownsJob(id, epoch, sequence, value.id)) return
      if (!hasUnsavedReview.value && !reviewSaving.value && !statusSaving.value) {
        selectedCode.value = null
        selectedDetail.value = null
        drawerOpen.value = false
        sourceCitation.value = null
        evidence.value = null
      }
    }
    if (notifyCompletion && value.status === 'FAILED') {
      const message = value.error || value.message || t('vetting.job.failed')
      // Known technical failures already have one reader-facing alert on this page.
      if (!jobFailureMessageKey(message)) store.notify(readerJobMessage(message), 6000)
    }
  }
}

async function acceptProgress(value: VettingProgress, id: string, epoch: number, sequence: number, readSequence: number, notifyCompletion = false) {
  if (!ownsJob(id, epoch, sequence) || readSequence !== jobReadSequence) return
  const previous = job.value?.id === value.id ? job.value : null
  // Copy only progress fields. A previous successful source check is not a current one.
  job.value = {
    id: value.id, status: value.status, phase: value.phase, completedUnits: value.completedUnits,
    totalUnits: value.totalUnits, message: value.message, error: value.error,
    startedAt: value.startedAt, finishedAt: value.finishedAt, sourceSnapshotSha256: value.sourceSnapshotSha256,
    modelIdentity: value.modelIdentity, sourceVerification: 'NOT_CHECKED',
    ...(previous?.sourceSetStale === true ? { sourceSetStale: true } : {})
  }
  pollingPaused.value = false
  if (isActiveJob(value)) {
    schedulePoll(id, value.id, epoch, sequence)
    return
  }
  stopPolling()
  const detail = await vettingApi.getRun(id, value.id)
  if (!ownsJob(id, epoch, sequence, value.id) || readSequence !== jobReadSequence) return
  if (detail.id !== value.id) throw new Error('Vetting detail belongs to another run')
  await acceptJob(detail, id, epoch, sequence, notifyCompletion)
  if (!ownsJob(id, epoch, sequence, value.id) || readSequence !== jobReadSequence) return
}

async function pollRun(id: string, runId: string, epoch: number, sequence = jobSequence) {
  if (!ownsJob(id, epoch, sequence, runId)) return
  if (pendingJobRead?.projectId === id && pendingJobRead.epoch === epoch && pendingJobRead.sequence === sequence) return
  const owner = { projectId: id, epoch, sequence, runId, readSequence: ++jobReadSequence }
  pendingJobRead = owner
  stopPolling()
  try {
    const value = await vettingApi.getRunProgress(id, runId)
    if (!ownsJob(id, epoch, sequence, runId) || owner.readSequence !== jobReadSequence) return
    if (value.id !== runId) throw new Error('Vetting progress belongs to another run')
    await acceptProgress(value, id, epoch, sequence, owner.readSequence, true)
    if (!ownsJob(id, epoch, sequence, runId) || owner.readSequence !== jobReadSequence) return
  } catch {
    if (ownsJob(id, epoch, sequence, runId) && owner.readSequence === jobReadSequence) pollingPaused.value = true
  } finally {
    if (pendingJobRead === owner) pendingJobRead = null
  }
}

async function restoreJob() {
  const id = projectId.value
  const epoch = viewEpoch
  if (!id || disposed || starting.value) return
  if (pendingJobRead?.projectId === id && pendingJobRead.epoch === epoch && pendingJobRead.sequence === jobSequence) return
  const sequence = ++jobSequence
  const owner = { projectId: id, epoch, sequence, readSequence: ++jobReadSequence }
  pendingJobRead = owner
  stopPolling()
  try {
    const value = await vettingApi.latestRunProgress(id)
    if (!ownsJob(id, epoch, sequence) || owner.readSequence !== jobReadSequence) return
    if (value) {
      await acceptProgress(value, id, epoch, sequence, owner.readSequence)
      if (!ownsJob(id, epoch, sequence, value.id) || owner.readSequence !== jobReadSequence) return
    }
  } catch {
    if (ownsJob(id, epoch, sequence) && owner.readSequence === jobReadSequence) pollingPaused.value = true
  } finally {
    if (pendingJobRead === owner) pendingJobRead = null
  }
}

function retryPolling() {
  if (pendingJobRead?.projectId === projectId.value && pendingJobRead.epoch === viewEpoch && pendingJobRead.sequence === jobSequence) return
  pollingPaused.value = false
  if (job.value) void pollRun(projectId.value, job.value.id, viewEpoch)
  else void restoreJob()
}

onMounted(() => { void pollSourceProgress(); void reload().catch(() => undefined); void restoreJob() })
watch(() => [projectId.value, uploading.value] as const, ([id, active], [previousId, wasActive]) => {
  if (id === previousId && wasActive && !active) void reload().then(() => restoreJob()).catch(() => undefined)
})
watch(projectId, () => {
  viewEpoch++
  jobSequence++
  evidenceSequence++
  stopPolling()
  if (searchTimer) window.clearTimeout(searchTimer)
  job.value = null
  files.value = []
  sourceRoleSaving.value = null
  sourceRoleDrafts.value = {}
  sourceIdentityConfirmations.value = {}
  sourceRoleFeedback.value = ''
  sourceRoleNeedsRefresh.value = false
  findings.value = []
  metrics.value = null
  selectedDetail.value = null
  evidence.value = null
  sourceCitation.value = null
  starting.value = false
  sourceProgress.value = []
  void pollSourceProgress()
  checklist.value = null
  checklistBaseline.value = []
  checklistSequence++
  checklistSaving.value = null
  checklistLoading.value = false
  checklistChanged.value = false
  retainedChecklists.value = []
  statusSaving.value = false
  reviewSaving.value = false
  reviewFailed.value = false
  reviewCode.value = null
  reviewVersion.value = null
  reviewChanged.value = false
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
  if (sourceProgressTimer) window.clearTimeout(sourceProgressTimer)
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
  sourceCitation.value = null
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
  sourceCitation.value = null
  drawerOpen.value = false
  evidenceSequence++
}

async function updateStatus(status: string) {
  if (!selectedFinding.value || statusSaving.value || reviewSaving.value || running.value || sourceRoleSaving.value !== null || reviewChanged.value) return
  const id = projectId.value
  const shown = selectedFinding.value, code = shown.code, version = findingVersion(shown), binding = findingBinding(shown)
  if (!binding) { reviewChanged.value = true; return }
  const epoch = viewEpoch
  statusSaving.value = true
  try {
    const updated = await vettingApi.updateStatus(id, code, status, binding)
    if (disposed || epoch !== viewEpoch || id !== projectId.value) return
    const index = findings.value.findIndex((item) => findingVersion(item) === version)
    if (index < 0 || findingVersion(updated) !== version) { retainChangedReview(version); return }
    if (index >= 0) findings.value.splice(index, 1, updated)
    if (selectedDetail.value && findingVersion(selectedDetail.value) === version && !reviewChanged.value) {
      selectedDetail.value = updated
      if (!reviewDirty.value) loadReviewDraft(updated)
    }
    store.notify(`${updated.code} · ${statusLabel(updated.status)}`)
  } catch (error) {
    if (epoch === viewEpoch && id === projectId.value && error instanceof ApiError && error.code === 4104) retainChangedReview(version)
  } finally {
    if (epoch === viewEpoch) statusSaving.value = false
  }
}

function findingVersion(finding: Finding) {
  return JSON.stringify([finding.code, finding.runId, finding.generatedRevisionSha256, finding.sourceSnapshotSha256])
}

function findingBinding(finding: Finding): FindingWriteBinding | null {
  return finding.runId && finding.generatedRevisionSha256 && finding.sourceSnapshotSha256 ? {
    expectedRunId: finding.runId, expectedGeneratedRevisionSha256: finding.generatedRevisionSha256,
    expectedSourceSnapshotSha256: finding.sourceSnapshotSha256
  } : null
}

function retainChangedReview(version: string) {
  if (reviewVersion.value === version) { rememberReviewDraft(); reviewChanged.value = true }
  store.notify(pick(versionCopy.changed), 6000)
}

function rememberReviewDraft() {
  if (!reviewCode.value || !reviewVersion.value) return
  if (reviewDirty.value) reviewDrafts.value.set(reviewVersion.value, { code: reviewCode.value, draft: { ...reviewDraft.value } })
  else reviewDrafts.value.delete(reviewVersion.value)
}

function loadReviewDraft(finding: Finding) {
  const saved: ReviewDraft = {
    reviewRemarks: finding.reviewRemarks ?? '', actionTaken: finding.actionTaken ?? '',
    addendum: finding.addendumRequired === true ? 'required' : finding.addendumRequired === false ? 'notRequired' : 'undecided'
  }
  reviewCode.value = finding.code
  reviewVersion.value = findingVersion(finding)
  reviewBaseline.value = { ...saved }
  reviewDraft.value = { ...(reviewDrafts.value.get(reviewVersion.value)?.draft ?? saved) }
  reviewFailed.value = false
  reviewChanged.value = !findingBinding(finding)
}

function discardReview() {
  if (!selectedFinding.value || reviewSaving.value) return
  reviewDrafts.value.delete(findingVersion(selectedFinding.value))
  loadReviewDraft(selectedFinding.value)
}

function discardRetainedReviews() {
  for (const saved of retainedReviews.value) reviewDrafts.value.delete(saved.key)
}

async function refreshFinding() {
  if (reviewSaving.value || statusSaving.value) return
  rememberReviewDraft()
  const id = projectId.value, code = selectedCode.value, epoch = viewEpoch
  await reload()
  if (disposed || epoch !== viewEpoch || id !== projectId.value || code !== selectedCode.value) return
  const current = findings.value.find(item => item.code === code)
  if (current) openFinding(current)
}

async function saveReview() {
  if (!selectedFinding.value || reviewSaving.value || statusSaving.value || running.value || sourceRoleSaving.value !== null || !reviewDirty.value || reviewChanged.value) return
  const id = projectId.value, code = selectedFinding.value.code, epoch = viewEpoch
  const version = findingVersion(selectedFinding.value), binding = findingBinding(selectedFinding.value)
  if (!binding) { reviewChanged.value = true; return }
  const draft = { ...reviewDraft.value }
  rememberReviewDraft()
  reviewSaving.value = true
  reviewFailed.value = false
  try {
    const updated = await vettingApi.updateReview(id, code, {
      ...binding,
      reviewRemarks: draft.reviewRemarks, actionTaken: draft.actionTaken,
      addendumRequired: draft.addendum === 'undecided' ? null : draft.addendum === 'required'
    })
    if (disposed || epoch !== viewEpoch || id !== projectId.value) return
    const index = findings.value.findIndex(item => findingVersion(item) === version)
    if (index < 0 || findingVersion(updated) !== version) { retainChangedReview(version); return }
    reviewDrafts.value.delete(version)
    if (index >= 0) findings.value.splice(index, 1, updated)
    if (selectedDetail.value && findingVersion(selectedDetail.value) === version && !reviewChanged.value) { selectedDetail.value = updated; loadReviewDraft(updated) }
    store.notify(t('vetting.review.saved'))
  } catch (error) {
    if (epoch === viewEpoch && id === projectId.value) {
      if (error instanceof ApiError && error.code === 4104) retainChangedReview(version)
      else if (reviewVersion.value === version) reviewFailed.value = true
    }
  } finally {
    if (epoch === viewEpoch) reviewSaving.value = false
  }
}

async function runVetting() {
  if (running.value || sourceRoleSaving.value !== null || uploading.value || reviewSaving.value || statusSaving.value || hasUnsavedReview.value || checklistDirty.value || checklistSaving.value !== null || !projectId.value) return
  const llmSelection = store.captureLlmSelection()
  const id = projectId.value
  const epoch = viewEpoch
  const sequence = ++jobSequence
  stopPolling()
  starting.value = true
  try {
    const value = await vettingApi.startRun(id, store.locale, llmSelection)
    if (!ownsJob(id, epoch, sequence)) return
    await acceptJob(value, id, epoch, sequence, true)
    if (!ownsJob(id, epoch, sequence, value.id)) return
  } finally {
    if (ownsJob(id, epoch, sequence)) starting.value = false
  }
}

async function refreshUploadStatus() {
  await pollSourceProgress()
  try { await reload() } catch { store.notify(t('vetting.uploadProgress.refreshFailed')) }
}
async function uploadPackage(event: Event) {
  const selected = Array.from((event.target as HTMLInputElement).files ?? [])
  if (hasUnsavedReview.value || reviewSaving.value || checklistDirty.value || checklistSaving.value !== null) {
    (event.target as HTMLInputElement).value = ''
    return
  }
  if (!selected.length || running.value || sourceRoleSaving.value !== null || uploading.value) return
  const id = projectId.value
  jobSequence++
  if (job.value) job.value = { ...job.value, sourceSetStale: true }
  store.setBusy(t('vetting.uploadProgress.title'))
  ;(event.target as HTMLInputElement).value = ''
  try {
    const operation = uploadVettingFiles(id, selected)
    void pollSourceProgress()
    await operation
  } finally {
    store.clearBusy()
  }
}

async function exportReport() {
  if (exportDisabled.value) return
  const id = projectId.value
  const format = reportFormat.value
  const epoch = viewEpoch
  exporting.value = true
  try {
    await vettingApi.exportReport(id, `ConSense_Vetting_Report.${format}`, format)
    if (!disposed && epoch === viewEpoch && id === projectId.value) {
      store.notify(t('vetting.actions.exportStarted', { format: format.toUpperCase() }), 5000)
      await restoreJob()
    }
  } finally {
    exporting.value = false
  }
}

watch(() => job.value?.id, () => {
  checklistSequence++
  if (currentChecklistDirty.value || checklistSaving.value) checklistChanged.value = true
  else { checklist.value = null; checklistBaseline.value = []; checklistChanged.value = false }
  checklistLoading.value = false
  checklistSaving.value = null
})

async function openChecklist(event?: Event) {
  if ((event && !(event.target as HTMLDetailsElement).open) || checklist.value || job.value?.status !== 'COMPLETED' || job.value?.sourceVerification === 'NOT_CHECKED') return
  const id = projectId.value, runId = job.value.id, epoch = viewEpoch, sequence = ++checklistSequence
  checklistLoading.value = true
  try {
    const value = await vettingApi.checklist(id, runId)
    if (disposed || epoch !== viewEpoch || runId !== job.value?.id || sequence !== checklistSequence) return
    checklist.value = value
    checklistBaseline.value = structuredClone(value.items)
    checklistChanged.value = !value.sourceSnapshotSha256 || value.runId !== runId
  } finally {
    if (epoch === viewEpoch && sequence === checklistSequence) checklistLoading.value = false
  }
}

async function refreshChecklist() {
  if (checklistSaving.value || checklistLoading.value) return
  if (checklist.value && currentChecklistDirty.value) retainedChecklists.value.push(JSON.parse(JSON.stringify(checklist.value)))
  checklist.value = null
  checklistBaseline.value = []
  await openChecklist()
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
  if (!checklist.value || !checklistWritable.value || checklistSaving.value || !checkDirty(item)) return
  if (item.status === 'not_fulfilled' && !item.remarks.trim()) {
    store.notify(t('vetting.checklist.reasonRequired'))
    return
  }
  const id = projectId.value, loaded = checklist.value, epoch = viewEpoch, sequence = checklistSequence
  const binding = { runId: loaded.runId, runSnapshotSha256: loaded.runSnapshotSha256, sourceSnapshotSha256: loaded.sourceSnapshotSha256 }
  const submitted = { id: item.id, status: item.status, remarks: item.remarks }
  checklistSaving.value = item.id
  try {
    const saved = await vettingApi.saveChecklist(id, binding, [submitted])
    if (disposed || epoch !== viewEpoch || id !== projectId.value || sequence !== checklistSequence || checklist.value !== loaded
      || binding.runId !== job.value?.id || saved.runId !== binding.runId || saved.runSnapshotSha256 !== binding.runSnapshotSha256
      || saved.sourceSnapshotSha256 !== binding.sourceSnapshotSha256) return
    const savedRow = saved.items.find(row => row.id === item.id)
    if (savedRow) {
      const original = checklistBaseline.value.find(row => row.id === item.id)
      if (original) Object.assign(original, savedRow)
      // A further edit while the request was in flight remains unsaved.
      if (item.status === submitted.status && item.remarks === submitted.remarks) Object.assign(item, savedRow)
    }
    store.notify(t('vetting.checklist.saved'))
  } catch (error) {
    if (epoch === viewEpoch && id === projectId.value && checklist.value === loaded && error instanceof ApiError && error.code === 4104) {
      checklistChanged.value = true
      store.notify(pick(versionCopy.changed), 6000)
    }
  } finally {
    if (epoch === viewEpoch && sequence === checklistSequence) checklistSaving.value = null
  }
}

function metricCards() {
  const data = metrics.value
  return GROUPS.map(group => ({ group, label: t(`vetting.metrics.${group}`), detail: t(`vetting.metrics.${group}Detail`), value: data?.[group] ?? 0 }))
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
        <button class="btn" type="button" :disabled="running || sourceRoleSaving !== null || uploading || hasUnsavedReview || reviewSaving || checklistDirty || checklistSaving !== null" @click="packageInput?.click()">
          <AppIcon name="upload" :size="15" />{{ t('vetting.actions.upload') }}
        </button>
        <button class="btn primary" type="button" :disabled="running || sourceRoleSaving !== null || uploading || reviewSaving || statusSaving || hasUnsavedReview || checklistDirty || checklistSaving !== null || !files.some(file => file.parsed)" @click="runVetting">
          <AppIcon name="play" :size="15" />
          {{ reviewActive ? t('vetting.actions.running') : t('vetting.actions.run') }}
        </button>
        <select v-model="reportFormat" class="report-format" :aria-label="t('vetting.report.format')" :disabled="exporting">
          <option value="pdf">PDF</option>
          <option value="docx">Word (.docx)</option>
          <option value="json">JSON</option>
        </select>
        <button class="btn" type="button" :disabled="exportDisabled" @click="exportReport">
          <AppIcon name="download" :size="15" />{{ t('vetting.actions.export') }}<span v-if="exporting"> · {{ t('common.loading') }}</span>
        </button>
      </div>
    </div>

    <p class="small" data-generated-output-language>{{ t('common.generatedOutputLanguage') }}</p>

    <div v-if="uploadProgress || sourceProgress.some(row => row.parseStatus === 'PARSING' || row.parseStatus === 'FAILED')" class="surface section-gap" aria-live="polite">
      <div class="surface-body">
        <strong>{{ t('vetting.uploadProgress.title') }}</strong>
        <p v-if="sourceProgressFailed" class="warning-note">{{ t('vetting.uploadProgress.refreshFailed') }}</p>
        <template v-if="uploadProgress">
        <p class="small">{{ t('vetting.uploadProgress.current', { current: uploadProgress.current, total: uploadProgress.total, file: uploadProgress.fileName }) }}</p>
        </template>
        <section v-if="currentFileProgress" data-vetting-current-file role="status" class="source-note">
          <strong>{{ pick(sourceProgressCopy.currentFile) }}{{ currentFileProgress.fileName }}</strong>
          <p v-if="sourceProgressFailed" class="warning-note">{{ pick(sourceProgressCopy.lastReported) }}</p>
          <p class="small">{{ pick(sourceProgressCopy.method) }}{{ currentFileDetails.method }}</p>
          <p class="small">{{ pick(sourceProgressCopy.page) }}{{ currentFileDetails.page }}</p>
          <p class="small">{{ pick(sourceProgressCopy.step) }}{{ currentFileDetails.step }}</p>
        </section>
        <template v-if="uploadProgress">
        <p class="small">{{ t('vetting.uploadProgress.received', { count: uploadProgress.completed, total: uploadProgress.total, failed: uploadProgress.failed }) }}</p>
        <progress class="vetting-progress" :value="uploadProgress.completed" :max="uploadProgress.total" :aria-label="t('vetting.uploadProgress.title')" />
        <p :class="uploadProgress.state === 'running' || uploadProgress.state === 'completed' ? 'muted small' : 'warning-note'">{{ t(`vetting.uploadProgress.${uploadProgress.state}`) }}</p>
        <button class="btn" type="button" :disabled="loading" @click="refreshUploadStatus">{{ t('vetting.sourcePurpose.refresh') }}</button>
        <details v-if="uploadProgress.messages.length" class="small section-gap">
          <summary>{{ t('vetting.uploadProgress.records') }}</summary>
          <p v-for="(message, index) in uploadProgress.messages" :key="index">{{ message }}</p>
        </details>
        </template>
        <p v-for="row in sourceProgress" :key="row.id" class="small">
          <strong>{{ row.fileName }}</strong> · {{ sourceParseLabel(row.parseStatus) }}
          <span v-if="row.pageCount"> · {{ row.pageCount }} {{ t('vetting.uploadProgress.pages') }}</span>
          <br />{{ row.parseMessage }}
        </p>
      </div>
    </div>

    <p v-if="hasUnsavedReview" class="warning-note" role="status">{{ t('vetting.review.unsavedExport') }} {{ unsavedReviewCodes.join(' · ') }}</p>
    <p v-if="checklistDirty" class="warning-note" role="status">{{ t('vetting.checklist.dirty') }}</p>
    <p v-if="job?.sourceSetStale" class="warning-note" role="status">{{ t('vetting.sourceSetStale') }}</p>
    <details v-if="retainedReviews.length" class="surface section-gap">
      <summary>{{ pick(versionCopy.retained) }}</summary>
      <div v-for="saved in retainedReviews" :key="saved.key">
        <strong>{{ saved.code }}</strong><pre>{{ saved.draft.reviewRemarks }}{{ '\n' }}{{ saved.draft.actionTaken }}{{ '\n' }}{{ saved.draft.addendum }}</pre>
      </div>
      <button class="btn" type="button" @click="discardRetainedReviews">{{ pick(versionCopy.discard) }}</button>
    </details>
    <details v-if="retainedChecklists.length" class="surface section-gap">
      <summary>{{ pick(versionCopy.retained) }}</summary>
      <div v-for="saved in retainedChecklists" :key="saved.runId + saved.runSnapshotSha256">
        <strong>{{ saved.runId }}</strong><pre v-for="item in saved.items" :key="item.id">{{ item.clause }} · {{ item.status }}{{ '\n' }}{{ item.remarks }}</pre>
      </div>
      <button class="btn" type="button" @click="retainedChecklists = []">{{ pick(versionCopy.discard) }}</button>
    </details>

    <details v-if="job?.status === 'COMPLETED' && !jobDetailsPending" class="surface checklist-panel section-gap" @toggle="openChecklist">
      <summary>{{ t('vetting.checklist.title') }}</summary>
      <p class="muted small">{{ t('vetting.checklist.note') }}</p>
      <p v-if="checklist?.items.some(item => item.dependencyStatus === 'required_member_dependencies_unknown')" class="muted small">{{ t('vetting.checklist.dependencyUnknown') }}</p>
      <p v-if="checklistChanged" class="warning-note" role="alert">{{ pick(versionCopy.changed) }}
        <button class="btn" type="button" :disabled="!!checklistSaving || checklistLoading" @click="refreshChecklist">{{ pick(versionCopy.refresh) }}</button>
      </p>
      <p v-if="checklistLoading">{{ t('common.loading') }}</p>
      <section v-if="checklist?.automaticResults?.length" class="automatic-checklist">
        <h4>{{ t('vetting.checklist.automaticTitle') }}</h4>
        <p class="muted small">{{ t('vetting.checklist.automaticNote') }}</p>
        <details v-for="group in checklist.automaticResults" :key="group.id">
          <summary>{{ group.id }} · {{ checklist.logicalGroups?.find(item => item.id === group.id)?.clause }}
            <span class="tag" :class="group.status === 'not_fulfilled' ? 'danger' : group.status === 'fulfilled' ? 'ok' : 'neutral'">{{ group.symbol }} {{ t(`vetting.checklist.automaticStates.${group.status}`) }}</span>
          </summary>
          <p>{{ group.reason }}</p>
          <div v-for="item in group.subItemAssessments" :key="item.itemId" class="automatic-checklist-item">
            <strong>{{ item.itemId }} · {{ t(`vetting.checklist.automaticStates.${item.status}`) }}</strong>
            <p>{{ checklist.items.find(row => row.id === item.itemId)?.description }}</p>
            <p>{{ item.reason }}</p>
            <div v-for="(assessment, index) in item.assessments" :key="index">
              <p><span v-if="assessment.issueTypeCode" class="mono">{{ assessment.issueTypeCode }} · </span>{{ t(`vetting.checklist.automaticStates.${assessment.status}`) }} · {{ assessment.reason }}</p>
              <div v-for="(evidenceItem, evidenceIndex) in assessment.evidence" :key="evidenceIndex" class="evidence-item">
                <p>{{ evidenceItem.fileName }} · {{ evidenceItem.pageNo }} · {{ evidenceItem.clauseId }} · {{ evidenceItem.anchor }}</p>
                <div class="quote">{{ evidenceItem.quote }}</div>
                <FixedBaselineEvidencePanel v-if="evidenceItem.fixedBaselineEvidence" :pointer="evidenceItem.fixedBaselineEvidence" :locale="store.locale" />
                <CanonicalEvidencePanel v-else-if="evidenceItem.canonicalEvidence" :pointer="evidenceItem.canonicalEvidence" :locale="store.locale" />
              </div>
            </div>
          </div>
        </details>
      </section>
      <h4 v-if="checklist">{{ t('vetting.checklist.manualTitle') }}</h4>
      <div v-if="checklist" class="table-wrap checklist-table">
        <table>
          <thead><tr><th>PSS Clause</th><th>Description</th><th>{{ t('common.status') }}</th><th>{{ t('vetting.checklist.remarks') }}</th></tr></thead>
          <tbody><tr v-for="item in checklist.items" :key="item.id">
            <td>{{ item.clause }}<div class="muted small">{{ item.section }}</div></td>
            <td>{{ item.description }}<p v-if="item.scopeNote" class="warning-note small">{{ item.scopeNoteText ? pick(item.scopeNoteText) : item.scopeNote }}</p>
              <p v-if="item.dependencyStatus === 'required_member_scope_closed' && item.reuseScope === 'exact_required_material_members'" class="muted small">{{ t('vetting.checklist.scopeMembers') }}</p>
              <p v-else-if="item.reuseScope === 'exact_whole_generation'" class="muted small">{{ t('vetting.checklist.scopeWhole') }}</p>
            </td>
            <td><select v-model="item.status" :disabled="!checklistWritable || checklistSaving === item.id">
              <option v-for="status in ['unchecked', 'fulfilled', 'not_fulfilled', 'not_applicable']" :key="status" :value="status">{{ t(`vetting.checklist.${status}`) }}</option>
            </select><p v-if="item.reuseStatus === 'matched_exact_required_material_members'" class="muted small">{{ t('vetting.checklist.reusedMembers') }} {{ item.reviewedRunId }}</p>
            <p v-else-if="item.reuseStatus === 'matched_exact_whole_generation'" class="muted small">{{ t('vetting.checklist.reusedWhole') }} {{ item.reviewedRunId }}</p></td>
            <td><textarea v-model="item.remarks" :aria-label="`${item.clause} ${t('vetting.checklist.remarks')}`" maxlength="4000" :disabled="!checklistWritable || checklistSaving === item.id" />
              <p v-if="item.status === 'not_fulfilled' && !item.remarks.trim()" class="warning-note small">{{ t('vetting.checklist.reasonRequired') }}</p>
              <details v-if="item.previousDecisionStatus" class="small">
                <summary>{{ t('vetting.checklist.previous') }}</summary>
                <p>{{ t(`vetting.checklist.${item.previousDecisionStatus}`) }} · {{ item.previousReviewedRunId }}</p>
                <pre v-if="item.previousRemarks">{{ item.previousRemarks }}</pre>
              </details>
              <div class="row"><button class="btn" type="button" :disabled="!checklistWritable || !!checklistSaving || !checkDirty(item) || (item.status === 'not_fulfilled' && !item.remarks.trim())" @click="saveCheck(item)">{{ t('vetting.checklist.save') }}</button>
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
        <p v-if="job?.status === 'COMPLETED' && !jobDetailsPending" class="warning-note">{{ t('vetting.job.executionCompleteNote') }}</p>
        <div v-if="job?.status === 'COMPLETED' && !jobDetailsPending" class="small">
          <p v-if="coverage">{{ t('vetting.job.documentSummary', { reviewed: coverage.reviewedDocuments, total: coverage.totalDocuments }) }}</p>
          <p>{{ t('vetting.job.findingSummary', { total: metrics?.total ?? job.result?.total ?? t('vetting.coverage.unknownCount'), listed: findings.length, pending: pendingListFindings }) }}</p>
        </div>
        <p v-if="running && job?.status !== 'FAILED' && job?.message" class="small">{{ readerJobMessage(job.message) }}</p>
        <template v-if="job?.status === 'FAILED'">
          <p v-for="message in jobFailureMessages" :key="message" class="run-error" role="alert">{{ message }}</p>
        </template>
        <p v-if="job?.recoveryStatus === 'NEEDS_RERUN'" class="warning-note">{{ t('vetting.recovery.NEEDS_RERUN') }}</p>
        <p v-if="job" class="muted small">{{ recordedJobModel ? `${t('llm.recordedModel')}: ${recordedJobModel}` : t('llm.identityUnknown') }}</p>
        <details v-if="job" class="small section-gap">
          <summary>{{ t('vetting.job.executionDetails') }}</summary>
          <p v-if="!running && job.status !== 'FAILED' && job.message">{{ job.message }}</p>
          <template v-if="job.status === 'FAILED'">
            <h5>{{ t('vetting.job.rawFailureRecord') }}</h5>
            <pre v-if="job.message">{{ job.message }}</pre>
            <pre v-if="job.error && job.error !== job.message">{{ job.error }}</pre>
          </template>
          <p v-if="job.recoveryStatus">{{ t(`vetting.recovery.${job.recoveryStatus}`) }}</p>
          <p v-if="job.reportSnapshotId" class="mono" style="overflow-wrap:anywhere">{{ t('vetting.report.snapshot') }}: {{ job.reportSnapshotId }}</p>
          <p v-if="jobModelIdentity" class="muted">{{ jobModelIdentity.profileId }} · {{ jobModelIdentity.provider }}</p>
          <p v-if="jobModelIdentity" class="muted">{{ t('llm.identityNote') }}</p>
          <details v-if="job.reliablePackets?.length"><summary>{{ t('vetting.recovery.reliablePackets') }} · {{ job.reliablePackets.length }}</summary>
            <div v-for="saved in job.reliablePackets" :key="saved.packet.packetId" class="small section-gap"><strong>{{ checkStateLabel(saved.packet.checkState) }}</strong> · {{ saved.packet.status }}<p class="mono" style="overflow-wrap:anywhere">{{ saved.packet.packetId }} · {{ saved.packet.sourceSnapshotSha256 }}</p><p v-for="warning in saved.warnings" :key="warning" class="warning-note">{{ warning }}</p><details><summary>{{ t('vetting.drawer.evidence') }}</summary><pre>{{ JSON.stringify(saved, null, 2) }}</pre></details></div>
          </details>
        </details>
        <progress v-if="reviewActive && jobPercent !== null" class="vetting-progress" :value="jobPercent" max="100" :aria-label="t('vetting.job.title')" />
        <p v-if="pollingPaused" class="warning-note">{{ t(jobDetailsPending ? 'vetting.job.sourceCheckPaused' : 'vetting.job.connectionPaused') }}</p>
        <p v-else-if="jobDetailsPending" class="muted small" role="status">{{ t('vetting.job.checkingSources') }}</p>
        <p v-else-if="reviewActive" class="muted small">{{ t('vetting.job.background') }}</p>
        <p v-if="job?.status === 'COMPLETED' && !jobDetailsPending" class="muted small">{{ t('vetting.review.retained') }}</p>
      </div>
    </div>

    <p v-if="hasCoverageLimits" class="warning-note section-gap">{{ t('vetting.coverage.readerLimit') }}</p>
    <p v-if="excludedDrawings.length" class="muted small section-gap">{{ t('vetting.coverage.drawingExcludedSummary', { count: excludedDrawings.length }) }}</p>
    <details v-if="coverage || sourceWarnings.length" class="surface section-gap coverage-panel">
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
          <strong>{{ file.fileName || file.key }}</strong> · {{ file.parseMessage || sourceParseLabel(file.parseStatus ?? file.status) }}
          <span v-for="(warning, index) in file.warnings" :key="index"> · {{ warning }}</span>
        </div>
        <div v-if="coverage?.documents?.length" class="table-wrap">
          <table>
            <thead><tr><th>{{ t('common.file') }}</th><th>{{ t('vetting.coverage.parse') }}</th><th>{{ t('vetting.coverage.segments') }}</th><th>{{ t('vetting.coverage.characters') }}</th><th>{{ t('vetting.coverage.warnings') }}</th></tr></thead>
            <tbody>
              <tr v-for="document in coverage.documents" :key="document.fileKey + document.fileName">
                <td><strong>{{ document.fileName }}</strong><div class="mono muted small">{{ document.fileKey }}</div></td>
                <td>{{ sourceParseLabel(document.parseStatus) }}</td>
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
                  <strong>{{ checkStateLabel(topic.checkState) }}</strong>
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
                      <div>{{ checkStateLabel(packet.checkState) }}</div>
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
                      <details v-if="request.packetId" class="evidence-technical-details">
                        <summary>{{ t('vetting.coverage.packetIdentity') }}</summary>
                        <div class="mono" style="overflow-wrap:anywhere">{{ t('vetting.coverage.fullInputPacket') }}: {{ request.packetId }}</div>
                      </details>
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
            <option v-for="group in GROUPS" :key="group" :value="group">{{ t(`vetting.metrics.${group}`) }}</option>
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
          <button class="btn" type="button" :disabled="loading" @click="reload()">
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

          <div v-if="selectedFinding.businessDetails" class="detail-block">
            <h5>{{ t('vetting.drawer.businessDetails') }}</h5>
            <dl class="business-details">
              <dt>{{ t('vetting.drawer.subDimension') }}</dt><dd>{{ selectedFinding.businessDetails.subDimension }}</dd>
              <dt>{{ t('vetting.drawer.factBasis') }}</dt><dd>{{ selectedFinding.businessDetails.factBasis }}</dd>
              <dt>{{ t('vetting.drawer.suggestedAction') }}</dt><dd>{{ t(`vetting.actions.${selectedFinding.businessDetails.suggestedAction}`) }}</dd>
            </dl>
            <p v-if="selectedFinding.businessDetails.checklistItemIds?.length" class="mono">{{ selectedFinding.businessDetails.checklistItemIds.join(' · ') }}</p>
            <template v-if="selectedFinding.businessDetails.pendingFacts.length">
              <h5>{{ t('vetting.drawer.pendingFacts') }}</h5>
              <ul><li v-for="fact in selectedFinding.businessDetails.pendingFacts" :key="fact">{{ fact }}</li></ul>
            </template>
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
                  <span v-if="item.clauseId" class="mono">{{ item.clauseId }}</span>
                  <span v-if="item.anchor" class="mono muted small">{{ item.anchor }}</span>
                  <span class="tag" :class="item.located ? 'ok' : 'warn'">{{ item.located ? t('vetting.drawer.located') : t('vetting.drawer.unverified') }}</span>
                </div>
                <div class="quote">{{ item.quote }}</div>
                <button v-if="isOrdinaryDocx(item) && originalSourceUrl(item)" class="btn" type="button" @click="openOriginalSource(item)">{{ t('vetting.drawer.openOriginal') }}</button>
                <a v-else-if="originalSourceUrl(item)" class="btn" :href="originalSourceUrl(item) ?? undefined"
                  :target="item.fileName.toLowerCase().endsWith('.pdf') ? '_blank' : undefined"
                  :download="item.fileName.toLowerCase().endsWith('.pdf') ? undefined : item.fileName" rel="noopener noreferrer">
                  {{ t(item.fileName.toLowerCase().endsWith('.pdf') ? 'vetting.drawer.openOriginal' : 'vetting.drawer.downloadOriginal') }}
                </a>
                <p v-if="!item.located" class="warning-note">{{ t('vetting.drawer.notLocated') }}</p>
                <FixedBaselineEvidencePanel v-if="item.fixedBaselineEvidence" :pointer="item.fixedBaselineEvidence" :locale="store.locale" />
                <CanonicalEvidencePanel v-else-if="item.canonicalEvidence" :pointer="item.canonicalEvidence" :locale="store.locale" />
                <details v-if="item.packetId || item.packetSourceSnapshotSha256" class="small evidence-technical-details">
                  <summary>{{ t('vetting.coverage.packetIdentity') }}</summary>
                  <div class="mono" style="overflow-wrap:anywhere">{{ t('vetting.coverage.fullInputPacket') }}: {{ item.packetId ?? t('vetting.coverage.unknownCount') }}</div>
                  <div class="mono" style="overflow-wrap:anywhere">{{ t('vetting.coverage.packetSnapshot') }}: {{ item.packetSourceSnapshotSha256 ?? t('vetting.coverage.unknownCount') }}</div>
                </details>
                <p v-else class="muted small">{{ t('vetting.coverage.legacyPacketUnknown') }}</p>
              </div>
            </div>
            <div v-else-if="!evidenceLoading && !evidenceFailed" class="evidence-missing">
              <AppIcon name="alert" :size="14" />
              <span>{{ t('vetting.drawer.notLocated') }}</span>
            </div>
            <div class="row" style="margin-top: 12px">
              <button class="btn" type="button" :disabled="handoffDisabled" @click="consultFinding">
                <AppIcon name="advice" :size="15" />{{ pick(reviewHandoffCopy.consult) }}
              </button>
            </div>
            <p class="muted small">{{ pick(reviewHandoffCopy.entryNote) }}</p>
            <p v-if="hasUnsavedReview || checklistDirty" class="warning-note" role="status">{{ pick(reviewHandoffCopy.saveFirst) }}</p>
          </div>

          <div class="detail-actions">
            <div class="review-fields">
              <h5>{{ t('vetting.review.team') }}</h5>
              <label class="review-field">
                <span>{{ t('vetting.review.remarks') }}</span>
                <textarea v-model="reviewDraft.reviewRemarks" rows="3" maxlength="4000" :disabled="reviewSaving || running || sourceRoleSaving !== null" />
              </label>
              <label class="review-field">
                <span>{{ t('vetting.review.actionTaken') }}</span>
                <textarea v-model="reviewDraft.actionTaken" rows="3" maxlength="4000" :disabled="reviewSaving || running || sourceRoleSaving !== null" />
              </label>
              <label class="review-field">
                <span>{{ t('vetting.review.addendum') }}</span>
                <select v-model="reviewDraft.addendum" :disabled="reviewSaving || running || sourceRoleSaving !== null">
                  <option value="undecided">{{ t('vetting.review.undecided') }}</option>
                  <option value="required">{{ t('vetting.review.required') }}</option>
                  <option value="notRequired">{{ t('vetting.review.notRequired') }}</option>
                </select>
              </label>
              <p v-if="selectedFinding.reviewUpdatedAt" class="small muted">{{ t('vetting.review.savedAt') }} {{ new Date(selectedFinding.reviewUpdatedAt).toLocaleString() }}</p>
              <p v-if="reviewFailed" class="warning-note" role="alert">{{ t('vetting.review.saveFailed') }}</p>
              <p v-if="reviewChanged" class="warning-note" role="alert">{{ pick(versionCopy.changed) }}
                <button class="btn" type="button" :disabled="reviewSaving || statusSaving" @click="refreshFinding">{{ pick(versionCopy.refresh) }}</button>
              </p>
              <div class="row">
                <button class="btn primary" type="button" :disabled="reviewSaving || statusSaving || running || sourceRoleSaving !== null || reviewChanged || !reviewDirty" @click="saveReview">{{ t('vetting.review.save') }}</button>
                <button class="btn" type="button" :disabled="reviewSaving || !reviewDirty" @click="discardReview">{{ t('vetting.review.discard') }}</button>
                <span v-if="reviewDirty" class="small muted">{{ t('vetting.review.unsaved') }}</span>
              </div>
            </div>
            <button
              class="btn success"
              type="button"
              :disabled="statusSaving || reviewSaving || running || sourceRoleSaving !== null || reviewChanged || selectedFinding.status.toUpperCase() === 'HANDLED'"
              @click="updateStatus('Handled')"
            >
              <AppIcon name="check" :size="15" />{{ t('vetting.drawer.markHandled') }}
            </button>
            <button
              class="btn"
              type="button"
              :disabled="statusSaving || reviewSaving || running || sourceRoleSaving !== null || reviewChanged || selectedFinding.status.toUpperCase() === 'ASSIGNED'"
              @click="updateStatus('Assigned')"
            >
              <AppIcon name="external" :size="15" />{{ t('vetting.drawer.assign') }}
            </button>
            <button class="btn" type="button" :disabled="statusSaving || reviewSaving || running || sourceRoleSaving !== null || reviewChanged || selectedFinding.status.toUpperCase() === 'OPEN'" @click="updateStatus('Open')">
              {{ t('vetting.review.reopen') }}
            </button>
          </div>
        </div>
      </div>
    </aside>

    <AppModal :open="sourceCitation !== null" :title="t('vetting.drawer.openOriginal')" wide @close="sourceCitation = null">
      <AdviceSourceReader v-if="sourceCitation" :project-id="projectId" :citation="sourceCitation" source-module="vetting" />
    </AppModal>

    <!-- 审查源集 -->
    <AppModal class="vetting-source-modal" :open="sourceOpen" :title="t('vetting.actions.source')" wide @close="sourceOpen = false">
      <div class="row section-gap">
        <p class="muted small">{{ t('vetting.sourcePurpose.note') }}</p>
        <button class="btn" type="button" :disabled="sourceRoleSaving !== null || uploading" @click="refreshSources">{{ t('vetting.sourcePurpose.refresh') }}</button>
      </div>
      <p v-if="sourceRoleFeedback" class="warning-note" role="status">{{ sourceRoleFeedback }}</p>
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
            <tr v-for="file in files" :key="file.documentId || file.key + ':' + file.fileName">
              <td>
                <strong>{{ file.fileName || file.key }}</strong>
                <div v-if="!['OTHER', 'DRAWING'].includes(file.key)" class="mono muted small">{{ file.key }}</div>
                <a v-if="sourceFileOriginalUrl(file)" :href="sourceFileOriginalUrl(file)!" class="small"
                  :target="file.fileName.toLowerCase().endsWith('.pdf') ? '_blank' : undefined"
                  :download="file.fileName.toLowerCase().endsWith('.pdf') ? undefined : file.fileName" rel="noopener noreferrer">
                  {{ t(file.fileName.toLowerCase().endsWith('.pdf') ? 'vetting.drawer.openOriginal' : 'vetting.drawer.downloadOriginal') }}
                </a>
              </td>
              <td>
                {{ sourcePurposeLabel(file) }}
                <div v-if="canConfirmSourceRole(file)" class="section-gap">
                  <p v-if="isPendingSourceIdentity(file)" class="small">{{ t('vetting.sourcePurpose.identityNote') }}</p>
                  <label class="small">{{ t('vetting.sourcePurpose.label') }}
                    <select :value="sourceRoleDrafts[sourceRoleVersion(file)] || ''" :aria-label="`${file.fileName} ${t('vetting.sourcePurpose.label')}`"
                      :disabled="sourceRoleBlocked" @change="sourceRoleDrafts[sourceRoleVersion(file)] = ($event.target as HTMLSelectElement).value">
                      <option value="" disabled>{{ t('vetting.sourcePurpose.choose') }}</option>
                      <option v-for="role in SOURCE_ROLES" :key="role" :value="role">{{ t(`vetting.uploadRole.${role}`) }}</option>
                    </select>
                  </label>
                  <label v-if="isPendingSourceIdentity(file)" class="source-identity-confirm small">
                    <input type="checkbox" :checked="sourceIdentityConfirmations[sourceRoleVersion(file)] === true" :disabled="sourceRoleBlocked"
                      @change="sourceIdentityConfirmations[sourceRoleVersion(file)] = ($event.target as HTMLInputElement).checked" />
                    {{ t('vetting.sourcePurpose.identityConfirm') }}
                  </label>
                  <button class="btn" type="button" :disabled="sourceRoleBlocked || !SOURCE_ROLES.includes(sourceRoleDrafts[sourceRoleVersion(file)] as VettingSourceRole) || (isPendingSourceIdentity(file) && sourceIdentityConfirmations[sourceRoleVersion(file)] !== true)"
                    @click="confirmSourceRole(file)">{{ sourceRoleSaving === file.documentId ? t('common.loading') : t(`vetting.sourcePurpose.${isPendingSourceIdentity(file) ? 'confirmAndParse' : 'confirm'}`) }}</button>
                </div>
              </td>
              <td>
                <span class="tag" :class="isExcludedDrawing(file) ? 'neutral' : file.parsed && (file.parseStatus ?? file.status) === 'PARSED' ? 'ok' : 'warn'">{{ isExcludedDrawing(file) ? t('vetting.coverage.excludedByScope') : sourceParseLabel(file.parseStatus ?? file.status) }}</span>
                <p v-if="isExcludedDrawing(file)" class="small">{{ t('vetting.coverage.drawingNotReviewed') }}</p>
                <template v-else>
                  <p v-if="['PARSING', 'IDENTITY_OBSERVED'].includes(file.parseStatus ?? file.status)" class="small">{{ t('vetting.sourceProcessing.pendingNote') }}</p>
                  <p v-if="file.documentScope === 'drawing'" class="small">{{ t('vetting.documentScope.drawing') }}</p>
                  <p v-if="file.exclusionReason && !['PARSING', 'IDENTITY_OBSERVED', 'IDENTITY_PENDING', 'PENDING'].includes(file.parseStatus ?? file.status)" class="small">{{ file.exclusionReason === 'source_identity_unresolved' ? t('vetting.sourceProcessing.purposeNote') : t(`advice.sourceScope.reasons.${file.exclusionReason}`) }}</p>
                </template>
              </td>
              <td>{{ file.pageCount || '—' }}</td>
              <td>
                <details>
                  <summary>{{ t('vetting.coverage.fileProcessingDetails') }}</summary>
                  <p class="mono small">{{ file.status }}<template v-if="file.parseStatus"> · {{ file.parseStatus }}</template><template v-if="file.sourceRole"> · {{ file.sourceRole }}</template><template v-if="file.key"> · {{ file.key }}</template></p>
                  <p v-if="file.documentScope" class="small">{{ t(`vetting.documentScope.${file.documentScope}`) }}</p>
                  <p v-if="file.exclusionReason" class="small">{{ t(`advice.sourceScope.reasons.${file.exclusionReason}`) }}</p>
                  <div class="small">{{ file.parseMessage }}</div>
                  <div v-for="(warning, index) in file.warnings" :key="index" class="warning-note">{{ warning }}</div>
                </details>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </AppModal>
  </section>
</template>

<style scoped>
.vetting-source-modal :deep(.modal.wide) { width: min(1520px, 100%); }
.source-identity-confirm { display: flex; align-items: flex-start; gap: 8px; margin: 8px 0; min-width: 220px; max-width: 320px; white-space: normal; }
.source-identity-confirm input { flex: none; width: auto; margin-top: 3px; }
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
.automatic-checklist { margin-block: 16px; }
.automatic-checklist details { padding-block: 8px; border-bottom: 1px solid var(--border); }
.automatic-checklist-item { margin: 12px 0 12px 16px; }
.business-details dt { font-weight: 600; margin-top: 10px; }
.business-details dd { margin: 4px 0 0; white-space: pre-wrap; }
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
