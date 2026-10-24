<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { adviceApi } from '@/api'
import type { ChatMessage, Citation, IndexStatus, QuickQuestion, AdviceSourceDocument, AdviceSourcePreparation, AdviceSession, AdviceBasisScope, LlmSelection } from '@/api/types'
import { useAppStore } from '@/stores/app'
import { useLocalized } from '@/composables/useLocalized'
import AppIcon from '@/components/AppIcon.vue'
import CanonicalEvidencePanel from '@/components/CanonicalEvidencePanel.vue'
import FixedBaselineEvidencePanel from '@/components/FixedBaselineEvidencePanel.vue'
import AdviceAnswer from '@/components/AdviceAnswer.vue'
import AdviceSourceReader from '@/components/AdviceSourceReader.vue'
import { streamAdvice, stopAdvice } from '@/advice/adviceStream'
import { claimReviewHandoff, handoffSourceWarnings, reviewHandoffCopy, reviewQuestion, type ReviewHandoff } from '@/advice/reviewHandoff'

const { t } = useI18n()
const store = useAppStore()
const route = useRoute()
const router = useRouter()
const { pick } = useLocalized()

const messages = ref<ChatMessage[]>([])
const quickQuestions = ref<QuickQuestion[]>([])
const indexStatus = ref<IndexStatus | null>(null)
const question = ref('')
const scope = ref('fullset')
const basisScope = ref<AdviceBasisScope>('project_contract')
const sessions = ref<AdviceSession[]>([])
const sessionId = ref('')
const sessionCreating = ref(false)
const sessionError = ref('')
let sessionSequence = 0
const asking = ref(false)
const indexing = ref(false)
const activeCitation = ref<Citation | null>(null)
const activeSources = ref<Citation[]>([])
const activeSourceScope = ref('fullset')
const historyEl = ref<HTMLElement | null>(null)
const followLatest = ref(true)
const streamPhase = ref('')
const elapsedSeconds = ref(0)
const sourceDetails = ref(false)
let elapsedTimer: number | undefined
let activeRequest: { projectId: string; sessionId: string; requestId: string; selection: LlmSelection; controller: AbortController; message: ChatMessage } | null = null
const scopeDocuments = ref<AdviceSourceDocument[]>([])
const clarificationReply = ref<ChatMessage | null>(null)
const handoff = ref<ReviewHandoff | null>(null)
const handoffError = ref<'invalid' | 'wrongProject' | null>(null)
const scopeLoaded = ref(false)
const indexError = ref('')
const sourceChecking = ref(false)
const preparingSources = ref(false)
const preparation = ref<AdviceSourcePreparation | null>(null)
const sourceErrorReason = ref<string | null>(null)
let viewEpoch = 0
let reloadSequence = 0
let scopeSequence = 0
let indexSequence = 0
let rebuildSequence = 0
let scopeEpoch = 0
let askSequence = 0
let sourceCheckSequence = 0
let preparationSequence = 0
let activeRebuildBusy: { sequence: number; message: string } | null = null

const evidenceCopy = {
  referenceScope: { zhHans: '本次仅查询固定标准；每次咨询会核验来源与版本。当前界面尚未确认固定检索就绪。', zhHant: '本次僅查詢固定標準；每次諮詢會核驗來源與版本。目前介面尚未確認固定檢索就緒。', en: 'This session queries fixed standards only. Each question verifies its sources and versions; this view has not confirmed fixed retrieval readiness.' },
  session: { zhHans: '咨询会话', zhHant: '諮詢會話', en: 'Advisory session' },
  legacy: { zhHans: '历史对话／新会话范围', zhHant: '歷史對話／新會話範圍', en: 'Legacy history / new session scope' },
  newSession: { zhHans: '建立新会话', zhHant: '建立新會話', en: 'Create new session' },
  project: { zhHans: '项目', zhHant: '項目', en: 'Project' },
  basis: { zhHans: '证据用途', zhHant: '證據用途', en: 'Evidence basis' },
  projectBasis: { zhHans: '本项目合同咨询', zhHant: '本項目合約諮詢', en: 'Project contract advice' },
  referenceBasis: { zhHans: '仅解释固定标准（未确认项目采用）', zhHant: '僅解釋固定標準（未確認項目採用）', en: 'Fixed standard interpretation (adoption unconfirmed)' },
  binding: { zhHans: '会话固定绑定范围；更改用途请建立新会话。切换会话不会自动提问。', zhHant: '會話固定綁定範圍；更改用途請建立新會話。切換會話不會自動提問。', en: 'Session scope is fixed. Create a new session to change its purpose. Switching sessions does not submit a question.' },
  indexFailed: { zhHans: '当前范围的索引请求失败。请检查后端配置后刷新索引。', zhHant: '目前範圍的索引請求失敗。請檢查後端設定後重新整理索引。', en: 'The index request for this scope failed. Check backend configuration and refresh the index.' },
  canonical_index_receipt_missing: { zhHans: '当前范围尚未建立可验证索引，请刷新索引。', zhHant: '目前範圍尚未建立可驗證索引，請重新整理索引。', en: 'No verified index receipt exists for this scope. Refresh the index.' },
  canonical_index_unavailable: { zhHans: '当前范围的索引或检索配置未通过核验，请检查后端配置后刷新索引。', zhHant: '目前範圍的索引或檢索設定未通過核驗，請檢查後端設定後重新整理索引。', en: 'Index or retrieval configuration verification failed for this scope. Check backend configuration and refresh the index.' },
  current_sources_unavailable: { zhHans: '当前范围的来源尚未就绪，请核对来源登记与解析状态。', zhHant: '目前範圍的來源尚未就緒，請核對來源登記與解析狀態。', en: 'Current sources for this scope are unavailable. Check source registration and parsing status.' },
  recheck: { zhHans: '重新检查来源', zhHant: '重新檢查來源', en: 'Recheck sources' },
  reviewSources: { zhHans: '前往审查页核对文件', zhHant: '前往審查頁核對文件', en: 'Review source files in Vetting' },
  prepare: { zhHans: '准备咨询资料', zhHant: '準備諮詢資料', en: 'Prepare advice sources' },
  preparing: { zhHans: '正在准备咨询资料，问题草稿已保留。', zhHant: '正在準備諮詢資料，問題草稿已保留。', en: 'Preparing advice sources. Your question draft is retained.' },
  checkingPrepared: { zhHans: '资料登记已结束，正在核对咨询状态，可先查看下方逐文件结果。', zhHant: '資料登記已結束，正在核對諮詢狀態，可先查看下方逐檔結果。', en: 'Source registration has finished. Checking advice status; you can review the per-file results below.' },
  preparationNote: { zhHans: '此操作仅核对已有解析并准备来源登记，不会重新解析文件或生成答复。', zhHant: '此操作僅核對已有解析並準備來源登記，不會重新解析文件或產生答覆。', en: 'This checks existing parsed material and prepares its source registration. It does not reparse files or generate an answer.' },
  preparationUnverified: { zhHans: '来源质量尚未由人工确认。', zhHant: '來源品質尚未由人工確認。', en: 'Source quality has not been confirmed by a person.' },
  preparationIncomplete: { zhHans: '资料准备尚未完成，请核对以下逐文件结果后重新检查来源。', zhHant: '資料準備尚未完成，請核對以下逐文件結果後重新檢查來源。', en: 'Source preparation is incomplete. Review each file result, then recheck sources.' },
  prepared: { zhHans: '已准备登记', zhHant: '已準備登記', en: 'Prepared' },
  already_registered: { zhHans: '已登记并核对', zhHant: '已登記並核對', en: 'Already registered' },
  failed: { zhHans: '失败', zhHant: '失敗', en: 'Failed' },
  unexecuted: { zhHans: '未执行', zhHant: '未執行', en: 'Not executed' },
  source_unregistered: { zhHans: '当前来源尚未完成登记，请前往审查页核对文件后重新检查来源。', zhHant: '目前來源尚未完成登記，請前往審查頁核對文件後重新檢查來源。', en: 'Current sources are not registered yet. Review the files in Vetting, then recheck sources.' },
  evidence_backend_unavailable: { zhHans: '来源证据服务暂不可用，请稍后重新检查；问题持续时请联系维护人员。', zhHant: '來源證據服務暫不可用，請稍後重新檢查；問題持續時請聯絡維護人員。', en: 'The source evidence service is unavailable. Recheck later; contact the maintainer if the problem persists.' },
  source_stale: { zhHans: '来源版本已变更，请核对当前文件后重新检查来源。', zhHant: '來源版本已變更，請核對目前文件後重新檢查來源。', en: 'The source version has changed. Review the current files, then recheck sources.' },
  unit: { zhHans: '结构证据单元与未确认项', zhHant: '結構證據單元與未確認項', en: 'Structural evidence unit and unresolved items' },
  observed: { zhHans: '已返回观测到的完整结构单元；语义适用范围与限定条件完整性仍未确认。', zhHant: '已返回觀測到的完整結構單元；語義適用範圍與限定條件完整性仍未確認。', en: 'The complete observed structural unit was returned. Semantic applicability and qualifier completeness remain unverified.' },
  unknown: { zhHans: '结构证据状态未确认，请核对原件及 unknown 属性。', zhHant: '結構證據狀態未確認，請核對原件及 unknown 屬性。', en: 'Structural evidence status is unresolved. Check the source and unknown attributes.' },
  context: { zhHans: '关联上下文，未独立重排评分。', zhHant: '關聯上下文，未獨立重排評分。', en: 'Related context; no independent rerank score.' },
  rerank: { zhHans: '重排分数', zhHant: '重排分數', en: 'Rerank score' }
}

const projectId = computed(() => store.activeProjectId)
const projectName = computed(() => store.activeProject?.id === projectId.value ? pick(store.activeProject.name) : t('common.empty'))
const activeSessionLabel = computed(() => {
  if (!sessionId.value) return pick(evidenceCopy.legacy)
  const selected = sessions.value.find(item => item.id === sessionId.value)
  return selected ? sessionLabel(selected) : t('common.unconfirmed')
})
const sourcesUnavailable = computed(() => basisScope.value !== 'reference_only' && (indexStatus.value?.indexReason === 'current_sources_unavailable' || sourceErrorReason.value === 'source_unregistered'))
const sourceProblemReason = computed(() => sourceErrorReason.value ?? indexStatus.value?.sourceReason ?? null)
const sourcePreparationAvailable = computed(() => basisScope.value !== 'reference_only' && sourceProblemReason.value === 'source_unregistered'
  && indexStatus.value?.sourceReason === 'source_unregistered' && indexStatus.value.preparable === true)
const handoffWarnings = computed(() => handoff.value ? handoffSourceWarnings(handoff.value, scopeDocuments.value, scopeLoaded.value) : null)

function clearImportedDraft() {
  handoff.value = null
  handoffError.value = null
  question.value = ''
}

function claimImportedDraft() {
  const token = route.query.review
  if (token === undefined || !projectId.value) return
  let result: ReturnType<typeof claimReviewHandoff>
  try { result = claimReviewHandoff(sessionStorage, token, projectId.value) }
  catch { result = { status: 'storage_unavailable' } }
  if (result.status === 'claimed') {
    handoff.value = result.handoff
    handoffError.value = null
    scope.value = 'fullset'
    sessionId.value = ''; basisScope.value = 'project_contract'
    clarificationReply.value = null
    question.value = reviewQuestion(result.handoff, value => pick(value))
  } else {
    handoffError.value = result.status === 'project_mismatch' ? 'wrongProject' : 'invalid'
  }
  const query = { ...route.query }
  delete query.review
  void router.replace({ query }).catch(() => undefined)
}

const SCOPES = [
  { value: 'fullset', label: () => t('advice.scope.fullset') },
  { value: 'tender', label: () => t('advice.scope.tender') },
  { value: 'contract', label: () => t('advice.scope.contract') }
]

function scopeLabel(value: string) {
  return SCOPES.find(item => item.value === value)?.label() ?? t('common.unconfirmed')
}

function sessionLabel(item: AdviceSession) {
  const date = new Date(item.createdAt)
  const time = Number.isNaN(date.getTime()) ? '' : date.toLocaleString(store.locale)
  const basis = item.basisScope === 'project_contract' ? pick(evidenceCopy.projectBasis)
    : item.basisScope === 'reference_only' ? pick(evidenceCopy.referenceBasis) : t('common.unconfirmed')
  return [time, scopeLabel(item.scope), basis].filter(Boolean).join(' · ')
}

const scopeNote = computed(() => basisScope.value === 'reference_only' ? pick(evidenceCopy.referenceScope) : t('advice.scopeNote'))

function sourceParseLabel(document: AdviceSourceDocument) {
  if (document.parseStatus === 'PARSED') return t('advice.sourceScope.parsed')
  if (document.parseStatus === 'PARTIAL') return t('advice.sourceScope.partiallyParsed')
  return t('advice.sourceScope.parseUnavailable')
}

function sourceRoleLabel(document: AdviceSourceDocument) {
  const role = ['tender', 'standard', 'project_fact', 'package_manifest'].includes(document.sourceRole) ? document.sourceRole : 'unknown'
  return t(`vetting.uploadRole.${role}`)
}

function sourceFailureReason(error: unknown): 'source_unregistered' | 'evidence_backend_unavailable' | 'source_stale' | null {
  if (error && typeof error === 'object' && 'reason' in error
      && (error.reason === 'source_unregistered' || error.reason === 'evidence_backend_unavailable' || error.reason === 'source_stale')) return error.reason
  if (error instanceof Error && error.message === 'Current canonical source bindings are unavailable for the selected advice scope. Complete source review and registration before asking.') return 'source_unregistered'
  return null
}

function adviceError(error: unknown) {
  const reason = sourceFailureReason(error)
  return reason ? pick(evidenceCopy[reason]) : error instanceof Error ? error.message : String(error)
}

function sourceProblemText(reason: unknown) {
  const known = sourceFailureReason({ reason })
  return known ? pick(evidenceCopy[known]) : pick(evidenceCopy.current_sources_unavailable)
}

function preparationStatus(status: AdviceSourcePreparation['results'][number]['status']) {
  return ['prepared', 'already_registered', 'failed', 'unexecuted'].includes(status) ? pick(evidenceCopy[status]) : pick(evidenceCopy.failed)
}

const indexHint = computed(() => {
  if (basisScope.value === 'reference_only') return ''
  const status = indexStatus.value
  if (!status) return ''
  if (status.sourceReason) return sourceProblemText(status.sourceReason)
  if (status.indexReason && Object.prototype.hasOwnProperty.call(evidenceCopy, status.indexReason)) return pick(evidenceCopy[status.indexReason as keyof typeof evidenceCopy])
  return pick(status.message) || t('advice.indexReady', { count: status.chunks })
})

function clearRebuildBusy(sequence?: number) {
  if (!activeRebuildBusy || (sequence !== undefined && activeRebuildBusy.sequence !== sequence)) return
  if (store.busyMessage === activeRebuildBusy.message) store.clearBusy()
  activeRebuildBusy = null
}

function resetScopedState() {
  void stopCurrentRequest()
  ++scopeEpoch; ++indexSequence; ++rebuildSequence; ++askSequence; ++reloadSequence; ++sourceCheckSequence; ++preparationSequence
  indexStatus.value = null; indexError.value = ''
  sourceErrorReason.value = null
  sessionError.value = ''
  activeSources.value = []; activeCitation.value = null; clarificationReply.value = null
  asking.value = false; indexing.value = false; sourceChecking.value = false; preparingSources.value = false; preparation.value = null
  clearRebuildBusy()
}

async function reloadIndexStatus() {
  const id = projectId.value, selectedScope = scope.value, epoch = viewEpoch, sequence = ++indexSequence
  indexError.value = ''
  if (indexStatus.value?.indexReason !== 'current_sources_unavailable') indexStatus.value = null
  if (!id || basisScope.value === 'reference_only') return
  const current = () => epoch === viewEpoch && id === projectId.value && selectedScope === scope.value && sequence === indexSequence
  try {
    const status = await adviceApi.indexStatus(id, selectedScope)
    if (!current()) return
    if (status.sourceScope && status.sourceScope !== selectedScope) throw new Error('INDEX_SCOPE_MISMATCH')
    indexStatus.value = status
  } catch {
    if (current()) indexError.value = pick(evidenceCopy.indexFailed)
  }
}

async function reload() {
  const id = projectId.value
  const selectedSession = sessionId.value, selectedScopeEpoch = scopeEpoch
  const epoch = viewEpoch
  const sequence = ++reloadSequence
  if (!id) return
  const [messageList, quickList] = await Promise.all([
    adviceApi.messages(id, selectedSession || undefined),
    adviceApi.quickQuestions(id)
  ])
  if (epoch !== viewEpoch || sequence !== reloadSequence || id !== projectId.value || selectedSession !== sessionId.value || selectedScopeEpoch !== scopeEpoch) return
  messages.value = messageList
  quickQuestions.value = quickList
  activeSources.value = []
  activeCitation.value = null
  await scrollToBottom()
}

async function reloadSessions() {
  const id = projectId.value, epoch = viewEpoch, sequence = ++sessionSequence
  sessions.value = []; sessionError.value = ''
  if (!id) return
  try {
    const list = await adviceApi.sessions(id)
    if (epoch === viewEpoch && id === projectId.value && sequence === sessionSequence) sessions.value = list.filter(item => item.projectId === id)
  } catch (caught) { if (epoch === viewEpoch && sequence === sessionSequence) sessionError.value = adviceError(caught) }
}

async function createSession() {
  if (sessionCreating.value || !projectId.value) return false
  const id = projectId.value, selectedScope = scope.value, basis = basisScope.value, epoch = viewEpoch, selectedScopeEpoch = scopeEpoch
  sessionCreating.value = true; sessionError.value = ''
  try {
    const created = await adviceApi.createSession(id, selectedScope, basis)
    if (epoch !== viewEpoch || id !== projectId.value || selectedScopeEpoch !== scopeEpoch) return false
    if (created.projectId !== id || created.scope !== selectedScope || created.basisScope !== basis || !created.id) throw new Error('ADVICE_SESSION_IDENTITY_MISMATCH')
    sessions.value.push(created); sessionId.value = created.id
    await nextTick()
    return true
  } catch (caught) { if (epoch === viewEpoch) sessionError.value = adviceError(caught); return false }
  finally { if (epoch === viewEpoch) sessionCreating.value = false }
}

async function reloadScope() {
  const id = projectId.value, selectedScope = scope.value, sequence = ++scopeSequence
  scopeLoaded.value = false
  scopeDocuments.value = []
  if (!id || basisScope.value === 'reference_only') { scopeDocuments.value = []; return }
  const documents = await adviceApi.sources(id, selectedScope)
  if (id === projectId.value && selectedScope === scope.value && sequence === scopeSequence) {
    scopeDocuments.value = documents
    scopeLoaded.value = true
  }
}

async function recheckSources() {
  if (sourceChecking.value || preparingSources.value || !projectId.value || basisScope.value === 'reference_only') return
  const id = projectId.value, epoch = viewEpoch, selectedScopeEpoch = scopeEpoch, sequence = ++sourceCheckSequence
  const current = () => id === projectId.value && epoch === viewEpoch && selectedScopeEpoch === scopeEpoch && sequence === sourceCheckSequence
  sourceChecking.value = true; sessionError.value = ''
  try {
    await Promise.all([reloadScope(), reloadIndexStatus()])
    if (current() && !indexError.value && indexStatus.value) sourceErrorReason.value = null
  }
  catch { if (current()) indexError.value = pick(evidenceCopy.indexFailed) }
  finally { if (current()) sourceChecking.value = false }
}

async function prepareSources() {
  if (preparingSources.value || sourceChecking.value || !projectId.value || !sourcePreparationAvailable.value) return
  const id = projectId.value, selectedScope = scope.value, epoch = viewEpoch, selectedScopeEpoch = scopeEpoch, sequence = ++preparationSequence
  const current = () => id === projectId.value && epoch === viewEpoch && selectedScopeEpoch === scopeEpoch && sequence === preparationSequence
  ++indexSequence; ++scopeSequence
  preparingSources.value = true; preparation.value = null; sessionError.value = ''; indexError.value = ''
  try {
    const result = await adviceApi.prepareSources(id, selectedScope)
    if (!current()) return
    if (result.sourceScope !== selectedScope || result.sourceQualityAccepted !== false || result.humanConfirmed !== false
        || !Number.isInteger(result.selectedDocuments) || result.selectedDocuments < 1 || !Array.isArray(result.results)
        || result.results.length !== result.selectedDocuments || new Set(result.results.map(item => item.documentId)).size !== result.selectedDocuments
        || result.results.some(item => !['prepared', 'already_registered', 'failed', 'unexecuted'].includes(item.status))
        || result.preparedDocuments !== result.results.filter(item => item.status === 'prepared' || item.status === 'already_registered').length
        || typeof result.complete !== 'boolean' || result.complete && result.preparedDocuments !== result.selectedDocuments) throw new Error('SOURCE_PREPARATION_RESPONSE_MISMATCH')
    preparation.value = result
    await Promise.all([reloadScope(), reloadIndexStatus()])
    if (current() && !indexError.value) sourceErrorReason.value = null
  } catch (caught) {
    if (current()) { sourceErrorReason.value = sourceFailureReason(caught); indexError.value = sourceErrorReason.value ? adviceError(caught) : pick(evidenceCopy.preparationIncomplete) }
  } finally { if (current()) preparingSources.value = false }
}

onMounted(() => { void reload(); void reloadSessions(); void reloadScope(); void reloadIndexStatus() })
watch(projectId, () => {
  viewEpoch++
  ++sessionSequence; sessions.value = []; sessionId.value = ''; sessionCreating.value = false; sessionError.value = ''
  resetScopedState()
  if (handoff.value || handoffError.value) clearImportedDraft()
  scopeLoaded.value = false
  messages.value = []; scopeDocuments.value = []; activeSources.value = []; activeCitation.value = null; clarificationReply.value = null
  void reload(); void reloadSessions(); void reloadScope(); void reloadIndexStatus()
})
watch(() => [projectId.value, route.query.review], claimImportedDraft, { immediate: true })
watch(() => [scope.value, basisScope.value], () => { resetScopedState(); messages.value = []; void reload(); void reloadScope(); void reloadIndexStatus() })
watch(sessionId, () => {
  resetScopedState(); messages.value = []
  const selected = sessions.value.find(item => item.id === sessionId.value)
  if (selected) { scope.value = selected.scope; basisScope.value = selected.basisScope }
  void reload(); void reloadScope(); void reloadIndexStatus()
})
watch(() => messages.value.length, () => void scrollToBottom())
onBeforeUnmount(() => { ++viewEpoch; ++scopeSequence; resetScopedState() })

async function scrollToBottom(force = false) {
  if (force) followLatest.value = true
  if (!followLatest.value) return
  await nextTick()
  if (historyEl.value) {
    historyEl.value.scrollTop = historyEl.value.scrollHeight
  }
}

function onHistoryScroll() {
  const element = historyEl.value
  if (element) followLatest.value = element.scrollHeight - element.scrollTop - element.clientHeight < 96
}

function streamCopy(en: string, zhHans: string, zhHant = zhHans) { return pick({ en, zhHans, zhHant }) }
function phaseLabel(phase: string) {
  const labels: Record<string, [string, string, string?]> = {
    received: ['Question received', '已收到问题', '已收到問題'], queued: ['Waiting to process', '等待处理', '等待處理'],
    sources: ['Confirming source scope', '正在确认来源范围', '正在確認來源範圍'], retrieving: ['Retrieving contract evidence', '正在检索合同依据', '正在檢索合約依據'],
    context: ['Organizing evidence and context', '正在组织依据和上下文', '正在組織依據和上下文'], budget: ['Checking model input budget', '正在核对模型输入预算', '正在核對模型輸入預算'],
    generating: ['Generating the answer', '正在生成回答', '正在生成回答'], validating: ['Checking references and answer conditions', '正在核对引用和答复条件', '正在核對引用和答覆條件'],
    saving: ['Saving the answer', '正在保存答复', '正在儲存答覆'], stream_unavailable: ['This model provides status events and a final answer; early answer streaming is unavailable.', '此模型提供实时状态与最终答复，暂不支持提前分段输出正文。', '此模型提供即時狀態與最終答覆，暫不支援提前分段輸出正文。']
  }
  const label = labels[phase] ?? ['Processing', '正在处理', '正在處理']
  return streamCopy(label[0], label[1], label[2])
}

async function stopCurrentRequest() {
  const pending = activeRequest
  if (!pending) return
  activeRequest = null
  pending.message.deliveryState = 'stopped'
  pending.controller.abort()
  if (elapsedTimer !== undefined) window.clearTimeout(elapsedTimer)
  try { await stopAdvice(pending.projectId, pending.requestId, pending.sessionId, pending.selection) }
  catch { if (pending.projectId === projectId.value && pending.sessionId === sessionId.value) sessionError.value = streamCopy('The display stopped; remote cancellation could not be confirmed.', '已停止显示，尚未确认远端取消成功。', '已停止顯示，尚未確認遠端取消成功。') }
}

async function ask(text?: string) {
  const content = (text ?? question.value).trim()
  if (!content || asking.value || sessionCreating.value) return
  if (sourcesUnavailable.value || preparingSources.value) {
    if (!question.value.trim()) question.value = content
    sessionError.value = preparingSources.value ? pick(evidenceCopy.preparing) : sourceProblemText(sourceProblemReason.value)
    return
  }
  const llmSelection = store.captureLlmSelection()
  if (!sessionId.value && !await createSession()) return
  const id = projectId.value, selectedSession = sessionId.value, selectedScope = scope.value, selectedBasis = basisScope.value, epoch = viewEpoch, selectedScopeEpoch = scopeEpoch, sequence = ++askSequence
  const replyToMessageId = clarificationReply.value?.id ?? undefined
  ++reloadSequence
  asking.value = true
  handoff.value = null
  handoffError.value = null
  question.value = ''
  messages.value.push({
    sessionId: selectedSession,
    id: null,
    role: 'user',
    title: null,
    content: { zhHans: content, zhHant: content, en: content },
    unknownScope: null,
    citations: [],
    evidenceId: null,
    sources: []
  })
  const pendingMessage: ChatMessage = { sessionId: selectedSession, id: null, role: 'assistant', title: null, content: null,
    unknownScope: null, citations: [], evidenceId: null, sources: [], sourceScope: selectedScope, deliveryState: 'processing' }
  messages.value.push(pendingMessage)
  const message = messages.value[messages.value.length - 1]
  const controller = new AbortController()
  const requestId = Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => byte.toString(16).padStart(2, '0')).join('')
  activeRequest = { projectId: id, sessionId: selectedSession, requestId, selection: llmSelection, controller, message }
  const current = () => epoch === viewEpoch && id === projectId.value && selectedScopeEpoch === scopeEpoch && sequence === askSequence && selectedSession === sessionId.value
  const started = Date.now()
  streamPhase.value = 'received'; elapsedSeconds.value = 0
  const tick = () => { if (current() && asking.value) { elapsedSeconds.value = Math.floor((Date.now() - started) / 1000); elapsedTimer = window.setTimeout(tick, 1000) } }
  tick()
  await scrollToBottom(true)

  try {
    const response = await streamAdvice(id, { question: content, scope: selectedScope, sessionId: selectedSession, basisScope: selectedBasis, replyToMessageId }, {
      requestId, signal: controller.signal, llmSelection,
      phase(phase) { if (current()) streamPhase.value = phase },
      section(part) {
        if (!current() || controller.signal.aborted) return
        const text = [message.content?.en, part.content].filter(Boolean).join('\n\n')
        message.content = { en: text, zhHans: text, zhHant: text }
        message.citations = [...new Set([...message.citations, ...part.citations])]
        for (const source of part.sources) if (!message.sources.some(existing => existing.evidenceId === source.evidenceId)) message.sources.push(source)
        void scrollToBottom()
      }
    })
    if (!current() || controller.signal.aborted) return
    if (response.sessionId !== selectedSession) throw new Error('ADVICE_SESSION_RESPONSE_MISMATCH')
    Object.assign(message, {
      sessionId: response.sessionId,
      id: response.messageId ?? null,
      role: 'assistant',
      modelIdentity: response.modelIdentity,
      model: response.model,
      sourceScope: response.sourceScope ?? selectedScope,
      basisScope: response.basisScope,
      clarifications: response.clarifications,
      title: response.title,
      content: response.content,
      unknownScope: response.unknownScope,
      citations: response.citations,
      evidenceId: response.evidenceId,
      sources: response.sources,
      deliveryState: 'complete'
    })
    if (!activeCitation.value) { activeSources.value = response.sources ?? []; activeSourceScope.value = response.sourceScope ?? selectedScope; activeCitation.value = response.sources?.[0] ?? null }
    clarificationReply.value = null
    if (!response.grounded) {
      store.notify(t('advice.unknown.title'))
    }
    await scrollToBottom()
  } catch (caught) {
    if (current()) {
      message.deliveryState = controller.signal.aborted ? 'stopped' : 'failed'
      if (!question.value) question.value = content
      if (!controller.signal.aborted) { sourceErrorReason.value = sourceFailureReason(caught); sessionError.value = adviceError(caught) }
    }
  } finally {
    if (current()) { asking.value = false; if (elapsedTimer !== undefined) window.clearTimeout(elapsedTimer); if (activeRequest?.requestId === requestId) activeRequest = null }
  }
}

async function refreshIndex() {
  if (indexing.value || preparingSources.value || !projectId.value || basisScope.value === 'reference_only') return
  const id = projectId.value, selectedScope = scope.value, epoch = viewEpoch, selectedScopeEpoch = scopeEpoch, sequence = ++rebuildSequence
  ++indexSequence // A pending status read must not overwrite the newer rebuild result.
  indexError.value = ''
  indexing.value = true
  activeRebuildBusy = { sequence, message: t('advice.indexing') }
  store.setBusy(activeRebuildBusy.message)
  const current = () => epoch === viewEpoch && id === projectId.value && selectedScopeEpoch === scopeEpoch && selectedScope === scope.value && sequence === rebuildSequence
  try {
    const status = await adviceApi.rebuildIndex(id, selectedScope)
    if (!current()) return
    if (status.sourceScope && status.sourceScope !== selectedScope) throw new Error('INDEX_SCOPE_MISMATCH')
    indexStatus.value = status
    sourceErrorReason.value = null
    await reloadScope()
    if (current()) store.notify(indexHint.value || t('common.done'))
  } catch (caught) {
    if (current()) {
      sourceErrorReason.value = sourceFailureReason(caught)
      if (indexStatus.value?.indexReason !== 'current_sources_unavailable') indexStatus.value = null
      indexError.value = sourceErrorReason.value ? adviceError(caught) : pick(evidenceCopy.indexFailed)
    }
  } finally {
    if (current()) indexing.value = false
    clearRebuildBusy(sequence)
  }
}

async function clearChat() {
  const id = projectId.value, epoch = viewEpoch, selectedSession = sessionId.value, selectedScopeEpoch = scopeEpoch
  const stopped = stopCurrentRequest()
  ++askSequence; ++reloadSequence; asking.value = true
  await stopped
  if (epoch !== viewEpoch || id !== projectId.value || selectedSession !== sessionId.value || selectedScopeEpoch !== scopeEpoch) return
  try {
    await adviceApi.clearMessages(id, selectedSession || undefined)
    if (epoch !== viewEpoch || id !== projectId.value || selectedSession !== sessionId.value || selectedScopeEpoch !== scopeEpoch) return
    messages.value = []
    clarificationReply.value = null
    activeSources.value = []
    activeCitation.value = null
  } catch (error) {
    // 全局 reporter 已经 toast 出 message；这里把堆栈打 console 方便排查
    console.error('[clearChat] failed', error)
    throw error
  } finally {
    if (epoch === viewEpoch && id === projectId.value && selectedSession === sessionId.value && selectedScopeEpoch === scopeEpoch) asking.value = false
  }
}

function replyToClarification(message: ChatMessage) {
  if (message.id === null || message.basisScope !== 'insufficient' || !message.clarifications?.length || message.sourceScope !== scope.value || message.sessionId !== sessionId.value) return
  clarificationReply.value = message
}

function selectCitation(citation: Citation) {
  activeCitation.value = citation
}

function citationLabel(citation: Citation, index: number) {
  return citation.evidenceId && /^\[[1-9]\d*\]$/.test(citation.evidenceId)
    ? citation.evidenceId : `${streamCopy('Excerpt', '摘录', '摘錄')} ${index + 1}`
}

function openMessageSources(message: ChatMessage, citationText?: string) {
  if (!message.sources?.length) return
  activeSources.value = message.sources
  // 若点的是具体引用按钮（citationText 非空），按 fileLabel 找到对应的 source 并联动到它；
  // 否则默认联动到第一个 source（向后兼容）。
  const matches = citationText ? message.sources.filter(source => source.fileLabel === citationText || source.evidenceId === citationText) : []
  if (citationText && matches.length !== 1) return
  activeSourceScope.value = message.sourceScope ?? scope.value
  activeCitation.value = matches[0] ?? message.sources[0]
  sourceDetails.value = false
}

function unknownText(scopeKey: string | null) {
  const key = (scopeKey ?? 'fullset') as 'fullset' | 'tender' | 'contract'
  return t(`advice.unknown.${key}`)
}

/** 把命中切片按当前问题的关键词做高亮 */
const HIGHLIGHT_STOPWORDS = new Set(['的', '是', '在', '和', '与', 'what', 'is', 'the', 'in', 'of', 'under', 'a', 'an'])

function highlightHtml(content: string): string {
  const escaped = content
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
  const terms = questionTerms()
  if (!terms.length) {
    return escaped
  }
  let result = escaped
  for (const term of terms) {
    const pattern = new RegExp(`(${term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')})`, 'gi')
    result = result.replace(pattern, '<mark>$1</mark>')
  }
  return result
}

let lastQuestionTerms: string[] = []

function questionTerms(): string[] {
  return lastQuestionTerms
}

function rebuildTerms(text: string) {
  lastQuestionTerms = (text.match(/[A-Za-z0-9.\-]{3,}|[\u4e00-\u9fa5]{2,}/g) ?? [])
    .filter((term) => !HIGHLIGHT_STOPWORDS.has(term.toLowerCase()))
    .slice(0, 6)
}

async function submit() {
  const content = question.value.trim()
  if (!content) return
  rebuildTerms(content)
  await ask(content)
}

async function askQuick(item: QuickQuestion) {
  clarificationReply.value = null
  const text = pick(item.text)
  rebuildTerms(text)
  await ask(text)
}

/** 优先次序依据（SCC 4.1）：以固定问题查询，命中后引用面板自动展示来源原文 */
async function askPrecedence() {
  clarificationReply.value = null
  const content = 'What is the order of precedence of contract documents under SCC Clause 4.1?'
  rebuildTerms(content)
  await ask(content)
}
</script>

<template>
  <section class="screen">
    <div class="screen-head">
      <div>
        <span class="eyebrow">{{ t('screen.advice') }}</span>
        <h3>{{ t('advice.title') }}</h3>
        <p>{{ t('advice.subtitle') }}</p>
      </div>
      <div class="row">
        <span class="tag ok">{{ t('advice.evidenceMode') }}</span>
        <button v-if="basisScope !== 'reference_only'" class="btn" type="button" :disabled="indexing || preparingSources" @click="refreshIndex">
          <AppIcon name="refresh" :size="15" />{{ t('advice.actions.refreshIndex') }}
        </button>
        <button v-if="basisScope !== 'reference_only'" class="btn" type="button" :disabled="sourceChecking || preparingSources" @click="recheckSources">
          <AppIcon name="refresh" :size="15" />{{ pick(evidenceCopy.recheck) }}
        </button>
        <!-- FR-A-03 / 原型 Precedence basis：直接以快捷问题形式查询合约文件优先次序（SCC 4.1） -->
        <button class="btn" type="button" :disabled="asking || sourcesUnavailable || preparingSources" @click="askPrecedence">
          <AppIcon name="info" :size="15" />{{ t('advice.actions.precedence') }}
        </button>
        <button class="btn" type="button" @click="clearChat">
          <AppIcon name="trash" :size="15" />{{ t('advice.actions.clear') }}
        </button>
      </div>
    </div>

    <details v-if="basisScope !== 'reference_only'" class="surface source-scope section-gap">
      <summary>{{ t('advice.sourceScope.title') }} · {{ t('advice.sourceScope.includedCount', { count: scopeDocuments.filter(item => item.included).length }) }}</summary>
      <p class="muted small">{{ pick(evidenceCopy.project) }} ID: {{ projectId }} · {{ pick(evidenceCopy.session) }} ID: {{ sessionId || '—' }}</p>
      <p v-if="indexStatus?.evidenceBackend" class="muted small">{{ indexStatus.evidenceBackend }} · {{ indexStatus.sourceScope || scope }}</p>
      <p>{{ t('advice.sourceScope.note') }}</p>
      <div class="table-wrap">
        <table>
          <thead><tr><th>{{ t('common.file') }}</th><th>{{ t('advice.sourceScope.role') }}</th><th>{{ t('common.status') }}</th></tr></thead>
          <tbody><tr v-for="document in scopeDocuments" :key="document.documentId" :class="{ 'scope-excluded': !document.included }">
            <td>{{ document.fileName }}</td>
            <td>{{ sourceRoleLabel(document) }}</td>
            <td>
              <div>{{ document.included ? t('advice.sourceScope.included') : t(`advice.sourceScope.reasons.${document.exclusionReason}`) }}</div>
              <div>{{ sourceParseLabel(document) }}</div>
            </td>
          </tr></tbody>
        </table>
      </div>
    </details>

    <div class="layout-grid two-col-wide">
      <!-- 对话 -->
      <div class="surface">
        <div class="chat-shell">
          <div class="chat-header">
            <div>
              <h4>{{ t('advice.chat.title') }}</h4>
              <div class="muted small">{{ pick(evidenceCopy.project) }}: {{ projectName }} · {{ pick(evidenceCopy.session) }}: {{ activeSessionLabel }}</div>
              <div class="muted small">{{ scopeNote }} · {{ indexHint }}</div>
              <p v-if="indexError" class="warning-note" role="alert">{{ indexError }}</p>
              <div v-if="sourcesUnavailable" class="warning-note" role="alert">
                <p>{{ sourceProblemText(sourceProblemReason) }}</p>
                <template v-if="sourcePreparationAvailable">
                  <p>{{ pick(evidenceCopy.preparationNote) }}</p>
                  <button class="btn" type="button" :disabled="preparingSources || sourceChecking" @click="prepareSources">{{ pick(evidenceCopy.prepare) }}</button>
                </template>
                <button class="btn" type="button" @click="router.push({ name: 'vetting' })">{{ pick(evidenceCopy.reviewSources) }}</button>
              </div>
              <p v-if="preparingSources" class="muted small" role="status" aria-live="polite">{{ pick(preparation ? evidenceCopy.checkingPrepared : evidenceCopy.preparing) }}</p>
              <div v-if="preparation" class="muted small" role="status">
                <p>{{ t('advice.sourceScope.preparedCount', { prepared: preparation.preparedDocuments, selected: preparation.selectedDocuments }) }} · {{ pick(evidenceCopy.preparationUnverified) }}</p>
                <p v-if="!preparation.complete" class="warning-note">{{ pick(evidenceCopy.preparationIncomplete) }}</p>
                <ul><li v-for="result in preparation.results" :key="result.documentId">{{ result.fileName }} · {{ preparationStatus(result.status) }}<span v-if="result.reason"> · {{ sourceProblemText(result.reason) }}</span></li></ul>
              </div>
            </div>
            <select v-model="scope" :aria-label="t('advice.sourceScope.title')" :disabled="!!sessionId || sessionCreating" style="width: auto; min-width: 170px">
              <option v-for="item in SCOPES" :key="item.value" :value="item.value">{{ item.label() }}</option>
            </select>
          </div>

          <div class="session-controls">
            <label>{{ pick(evidenceCopy.session) }}
              <select v-model="sessionId" :disabled="sessionCreating">
                <option value="">{{ pick(evidenceCopy.legacy) }}</option>
                <option v-for="item in sessions" :key="item.id" :value="item.id">{{ sessionLabel(item) }}</option>
              </select>
            </label>
            <label>{{ pick(evidenceCopy.basis) }}
              <select v-model="basisScope" :disabled="!!sessionId || sessionCreating">
                <option value="project_contract">{{ pick(evidenceCopy.projectBasis) }}</option>
                <option value="reference_only">{{ pick(evidenceCopy.referenceBasis) }}</option>
              </select>
            </label>
            <button class="btn" type="button" :disabled="sessionCreating || asking" @click="createSession">{{ pick(evidenceCopy.newSession) }}</button>
            <p class="muted small">{{ pick(evidenceCopy.binding) }}</p>
            <p v-if="sessionError" class="warning-note" role="alert">{{ sessionError }}</p>
            <p v-if="basisScope === 'reference_only'" class="warning-note">{{ t('advice.referenceOnly') }}</p>
          </div>

          <div ref="historyEl" class="chat-history" @scroll="onHistoryScroll">
            <div v-if="!messages.length" class="empty-state">{{ t('advice.citation.empty') }}</div>

            <div
              v-for="(message, index) in messages"
              :key="index"
              class="bubble"
              :class="{ user: message.role === 'user' }"
            >
              <div class="who">
                {{ message.role === 'user' ? t('advice.chat.user') : t('advice.chat.assistant') }}
              </div>

              <template v-if="message.role === 'user'">
                <p>{{ pick(message.content) }}</p>
              </template>

              <template v-else>
                <div v-if="message.deliveryState === 'processing'" class="advice-progress" role="status" aria-live="polite">
                  <span class="progress-dot" aria-hidden="true" /> {{ phaseLabel(streamPhase) }} · {{ elapsedSeconds }}s
                  <p v-if="message.content" class="muted small">{{ streamCopy('The answer is continuing; the complete result has not been saved yet.', '答复正在继续，完整结果尚未保存。', '答覆正在繼續，完整結果尚未儲存。') }}</p>
                </div>
                <p v-if="message.deliveryState === 'failed' || message.deliveryState === 'stopped'" class="warning-note" role="alert">{{ message.deliveryState === 'stopped' ? streamCopy('Stopped. Any displayed portions are not a completed answer.', '已停止，已显示的部分不构成完整答复。', '已停止，已顯示的部分不構成完整答覆。') : streamCopy('This request failed before completion. Any displayed portions are not a completed answer.', '本次请求未完成，已显示的部分不构成完整答复。', '本次請求未完成，已顯示的部分不構成完整答覆。') }}</p>
                <p v-if="!message.deliveryState || message.deliveryState === 'complete'" class="muted small">{{ message.modelIdentity?.model || message.model ? `${t('llm.recordedModel')}: ${message.modelIdentity?.model || message.model}` : t('llm.identityUnknown') }}<template v-if="message.modelIdentity"> · {{ message.modelIdentity.profileId }} · {{ message.modelIdentity.provider }}</template></p>
                <p v-if="message.modelIdentity && (!message.deliveryState || message.deliveryState === 'complete')" class="muted small">{{ t('llm.identityNote') }}</p>
                <strong v-if="!message.deliveryState || message.deliveryState === 'complete'">{{ message.content ? pick(message.title) : t('advice.unknown.title') }}</strong>
                <p v-if="message.basisScope === 'reference_only'" class="warning-note">{{ t('advice.referenceOnly') }}</p>
                <p v-if="message.basisScope === 'insufficient'" class="warning-note">{{ streamCopy('Evidence is insufficient for the complete requested conclusion. The following analysis is limited.', '现有依据不足以支持所请求的完整结论，以下为有限分析。', '現有依據不足以支持所請求的完整結論，以下為有限分析。') }}</p>
                <AdviceAnswer v-if="message.content" :content="pick(message.content)" :citation-ids="message.citations" @citation="id => openMessageSources(message, id)" />
                <p v-else-if="!message.deliveryState || message.deliveryState === 'complete'">{{ unknownText(message.unknownScope) }}</p>
                <div v-if="message.clarifications?.length" class="clarifications">
                  <strong>{{ t('advice.clarifications') }}</strong>
                  <ul><li v-for="item in message.clarifications" :key="item">{{ item }}</li></ul>
                  <button v-if="message.basisScope === 'insufficient'" class="btn" type="button" :disabled="asking || message.id === null || message.sourceScope !== scope || message.sessionId !== sessionId" @click="replyToClarification(message)">{{ t('advice.replyClarification') }}</button>
                </div>

                <div v-if="message.citations?.length" class="citation-row">
                  <button
                    v-for="citation in message.citations"
                    :key="citation"
                    type="button"
                    class="citation"
                    @click="openMessageSources(message, citation)"
                  >
                    {{ citation }}
                  </button>
                </div>
              </template>
            </div>
          </div>

          <button v-if="!followLatest" type="button" class="btn latest-message" @click="scrollToBottom(true)">{{ streamCopy('Return to latest', '回到最新内容', '回到最新內容') }}</button>

          <div v-if="clarificationReply" class="clarifications" role="status">
            <strong>{{ t('advice.replyMode') }}</strong>
            <ul><li v-for="item in clarificationReply.clarifications" :key="item">{{ item }}</li></ul>
            <button class="btn" type="button" :disabled="asking" @click="clarificationReply = null">{{ t('advice.newQuestion') }}</button>
          </div>
          <div v-if="handoffError" class="review-handoff warning-note" role="alert">
            {{ pick(reviewHandoffCopy[handoffError]) }}
            <button class="btn" type="button" @click="clearImportedDraft">{{ t('common.close') }}</button>
          </div>
          <div v-if="handoff" class="review-handoff" role="status">
            <strong>{{ pick(reviewHandoffCopy.draft) }} · {{ handoff.findingCode }}</strong>
            <p>{{ pick(reviewHandoffCopy.draftNote) }}</p>
            <p v-if="handoffWarnings?.stale" class="warning-note">{{ pick(reviewHandoffCopy.stale) }}</p>
            <p v-if="handoffWarnings?.unverified" class="warning-note">{{ pick(reviewHandoffCopy.unverified) }}</p>
            <p v-if="handoffWarnings?.unavailable" class="warning-note">{{ pick(reviewHandoffCopy.sourceUnavailable) }}</p>
            <p v-if="!handoff.evidence.length" class="warning-note">{{ pick(reviewHandoffCopy.noQuotes) }}</p>
            <ul v-if="handoff.evidence.length" class="small">
              <li v-for="(item, index) in handoff.evidence" :key="index">
                {{ item.fileKey }} · {{ item.fileName }} <span v-if="item.pageNo"> · {{ item.pageNo }}</span><span v-if="item.anchor"> · {{ item.anchor }}</span>
              </li>
            </ul>
            <button class="btn" type="button" :disabled="asking" @click="clearImportedDraft">{{ pick(reviewHandoffCopy.remove) }}</button>
          </div>
          <div class="composer">
            <textarea
              v-model="question"
              :placeholder="t('advice.placeholder')"
              @keydown.enter.exact.prevent="submit"
            />
            <button class="btn primary" type="button" :disabled="asking || sessionCreating || sourcesUnavailable || preparingSources || !question.trim()" @click="submit">
              <AppIcon name="advice" :size="15" />
              {{ asking ? t('common.loading') : t('advice.actions.ask') }}
            </button>
            <button v-if="asking" class="btn" type="button" @click="stopCurrentRequest">{{ streamCopy('Stop', '停止', '停止') }}</button>
          </div>
        </div>

        <div class="surface-body" style="border-top: 1px solid var(--line)">
          <div class="muted small" style="margin-bottom: 8px">{{ t('advice.quick') }}</div>
          <div class="quick-list">
            <button v-for="(item, i) in quickQuestions" :key="i" type="button" :disabled="asking || sourcesUnavailable || preparingSources" @click="askQuick(item)">
              {{ pick(item.text) }}
            </button>
          </div>
        </div>
      </div>

      <!-- 引用面板 -->
      <div class="surface">
        <div class="surface-head">
          <div>
            <h4>{{ t('advice.citation.title') }}</h4>
            <p>{{ t('advice.citation.subtitle') }}</p>
          </div>
          <span v-if="activeSources.length" class="tag info">{{ activeSources.length }}</span>
        </div>
        <div class="surface-body">
          <div v-if="!activeSources.length" class="empty-state">
            {{ t('advice.citation.emptySource') }}
          </div>

          <div v-else class="citation-pane">
            <div class="citation-chips">
              <button
                v-for="(source, i) in activeSources"
                :key="i"
                type="button"
                class="citation-chip"
                :class="{ active: activeCitation === source }"
                :title="source.fileLabel"
                @click="selectCitation(source)"
              >
                {{ citationLabel(source, i) }}
              </button>
            </div>

            <div v-if="activeCitation" class="source-view">
              <div class="sv-file">{{ activeCitation.fileLabel }} · {{ activeCitation.pageNo }}</div>
              <p v-if="activeCitation.fixedBaselineEvidence" class="warning-note">{{ streamCopy('This is a fixed standard original. Its source identity does not establish project adoption.', '这是固定标准原件，其来源身份本身不证明本项目采用。', '這是固定標準原件，其來源身份本身不證明本項目採用。') }}</p>
              <AdviceSourceReader :project-id="projectId" :citation="activeCitation" :scope="activeSourceScope" />
              <section class="source-context"><h4>{{ streamCopy('Quoted excerpt and related original images', '引用摘录及关联原图', '引用摘錄及關聯原圖') }}</h4>
              <div class="sv-para" v-html="highlightHtml(activeCitation.content)" />
              <button class="btn" type="button" @click="sourceDetails = !sourceDetails">{{ streamCopy('Show related original images / tables', '查看关联原图／整表', '查看關聯原圖／整表') }}</button>
              <template v-if="sourceDetails">
              <CanonicalEvidencePanel
                v-for="(address, index) in activeCitation.fixedBaselineEvidence ? [] : activeCitation.canonicalEvidence ?? []"
                :key="`${address.projectionSha256}:${address.canonicalChunkId}:${index}`"
                :pointer="address"
                :locale="store.locale"
              />
              <FixedBaselineEvidencePanel v-if="activeCitation.fixedBaselineEvidence" :pointer="activeCitation.fixedBaselineEvidence" :locale="store.locale" />
              </template>
              </section>
              <div class="sv-meta">
                {{ t('advice.citation.footer') }}
                <span v-if="activeCitation.scoreMeaning === 'unranked_context'"> · {{ pick(evidenceCopy.context) }}</span>
                <span v-else-if="activeCitation.scoreMeaning === 'actual_reranked_seed'" class="mono"> · {{ pick(evidenceCopy.rerank) }} {{ activeCitation.score.toFixed(3) }}</span>
                <span v-else-if="!activeCitation.scoreMeaning" class="mono"> · score {{ activeCitation.score.toFixed(3) }}</span>
              </div>
              <details v-if="activeCitation.canonicalSourceUnit" class="source-unit">
                <summary>{{ pick(evidenceCopy.unit) }}</summary>
                <p>{{ pick(activeCitation.canonicalSourceUnit.status === 'complete_observed_unit' ? evidenceCopy.observed : evidenceCopy.unknown) }}</p>
                <pre>{{ JSON.stringify(activeCitation.canonicalSourceUnit, null, 2) }}</pre>
              </details>
            </div>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.source-scope { padding: 14px 18px; margin-bottom: 18px; }
.session-controls { padding: 12px; border-bottom: 1px solid var(--line); display: flex; flex-wrap: wrap; gap: 10px; }
.session-controls label { flex: 1; min-width: 180px; }.session-controls p { width: 100%; margin: 0; }
.source-scope summary { cursor: pointer; font-weight: 700; }
.source-scope p { color: var(--muted); }
.scope-excluded { color: var(--muted); }
.source-scope .table-wrap { max-height: 280px; overflow: auto; }
.clarifications { margin-top: 12px; }
.review-handoff { padding: 12px; margin-top: 12px; border: 1px solid var(--line); border-radius: 8px; overflow-wrap: anywhere; }
.review-handoff p { margin: 8px 0; }
.source-unit { margin-top: 12px; }
.source-unit pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 24rem; overflow: auto; }
.advice-progress { color: var(--muted); padding: 4px 0 10px; }
.progress-dot { display: inline-block; width: 7px; height: 7px; margin-right: 6px; border-radius: 50%; background: currentColor; animation: advice-pulse 1.4s ease-in-out infinite; }
.latest-message { margin: 6px 18px; }
.source-view { max-height: none; }
.source-context { margin-top: 12px; }
.source-context summary { cursor: pointer; }
@keyframes advice-pulse { 50% { opacity: .35; } }
@media (prefers-reduced-motion: reduce) { .progress-dot { animation: none; } }
</style>
