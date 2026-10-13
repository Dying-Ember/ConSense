<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { adviceApi } from '@/api'
import type { ChatMessage, Citation, IndexStatus, QuickQuestion, AdviceSourceDocument, AdviceSession, AdviceBasisScope } from '@/api/types'
import { useAppStore } from '@/stores/app'
import { useLocalized } from '@/composables/useLocalized'
import AppIcon from '@/components/AppIcon.vue'
import CanonicalEvidencePanel from '@/components/CanonicalEvidencePanel.vue'
import FixedBaselineEvidencePanel from '@/components/FixedBaselineEvidencePanel.vue'
import AdviceAnswer from '@/components/AdviceAnswer.vue'
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
const historyEl = ref<HTMLElement | null>(null)
const scopeDocuments = ref<AdviceSourceDocument[]>([])
const clarificationReply = ref<ChatMessage | null>(null)
const handoff = ref<ReviewHandoff | null>(null)
const handoffError = ref<'invalid' | 'wrongProject' | null>(null)
const scopeLoaded = ref(false)
const indexError = ref('')
let viewEpoch = 0
let reloadSequence = 0
let scopeSequence = 0
let indexSequence = 0
let rebuildSequence = 0
let scopeEpoch = 0
let askSequence = 0
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
  unit: { zhHans: '结构证据单元与未确认项', zhHant: '結構證據單元與未確認項', en: 'Structural evidence unit and unresolved items' },
  observed: { zhHans: '已返回观测到的完整结构单元；语义适用范围与限定条件完整性仍未确认。', zhHant: '已返回觀測到的完整結構單元；語義適用範圍與限定條件完整性仍未確認。', en: 'The complete observed structural unit was returned. Semantic applicability and qualifier completeness remain unverified.' },
  unknown: { zhHans: '结构证据状态未确认，请核对原件及 unknown 属性。', zhHant: '結構證據狀態未確認，請核對原件及 unknown 屬性。', en: 'Structural evidence status is unresolved. Check the source and unknown attributes.' },
  context: { zhHans: '关联上下文，未独立重排评分。', zhHant: '關聯上下文，未獨立重排評分。', en: 'Related context; no independent rerank score.' },
  rerank: { zhHans: '重排分数', zhHant: '重排分數', en: 'Rerank score' }
}

const projectId = computed(() => store.activeProjectId)
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

const scopeNote = computed(() => basisScope.value === 'reference_only' ? pick(evidenceCopy.referenceScope) : t('advice.scopeNote'))

function sourceParseLabel(document: AdviceSourceDocument) {
  if (document.parseStatus === 'PARSED') return t('advice.sourceScope.parsed')
  if (document.parseStatus === 'PARTIAL') return t('advice.sourceScope.partiallyParsed')
  return t('advice.sourceScope.parseUnavailable')
}

const indexHint = computed(() => {
  if (basisScope.value === 'reference_only') return ''
  const status = indexStatus.value
  if (!status) return ''
  if (status.indexReason && Object.prototype.hasOwnProperty.call(evidenceCopy, status.indexReason)) return pick(evidenceCopy[status.indexReason as keyof typeof evidenceCopy])
  return pick(status.message) || t('advice.indexReady', { count: status.chunks })
})

function clearRebuildBusy(sequence?: number) {
  if (!activeRebuildBusy || (sequence !== undefined && activeRebuildBusy.sequence !== sequence)) return
  if (store.busyMessage === activeRebuildBusy.message) store.clearBusy()
  activeRebuildBusy = null
}

function resetScopedState() {
  ++scopeEpoch; ++indexSequence; ++rebuildSequence; ++askSequence; ++reloadSequence
  indexStatus.value = null; indexError.value = ''
  sessionError.value = ''
  activeSources.value = []; activeCitation.value = null; clarificationReply.value = null
  asking.value = false; indexing.value = false
  clearRebuildBusy()
}

async function reloadIndexStatus() {
  const id = projectId.value, selectedScope = scope.value, epoch = viewEpoch, sequence = ++indexSequence
  indexStatus.value = null; indexError.value = ''
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
  } catch (caught) { if (epoch === viewEpoch && sequence === sessionSequence) sessionError.value = caught instanceof Error ? caught.message : String(caught) }
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
  } catch (caught) { if (epoch === viewEpoch) sessionError.value = caught instanceof Error ? caught.message : String(caught); return false }
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
watch(() => messages.value.length, scrollToBottom)
onBeforeUnmount(() => { ++viewEpoch; ++scopeSequence; resetScopedState() })

async function scrollToBottom() {
  await nextTick()
  if (historyEl.value) {
    historyEl.value.scrollTop = historyEl.value.scrollHeight
  }
}

async function ask(text?: string) {
  const content = (text ?? question.value).trim()
  if (!content || asking.value || sessionCreating.value) return
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
  await scrollToBottom()

  try {
    const response = await adviceApi.ask(id, content, selectedScope, llmSelection, replyToMessageId, selectedSession, selectedBasis)
    if (epoch !== viewEpoch || id !== projectId.value || selectedScopeEpoch !== scopeEpoch || sequence !== askSequence || selectedSession !== sessionId.value) return
    if (response.sessionId !== selectedSession) throw new Error('ADVICE_SESSION_RESPONSE_MISMATCH')
    messages.value.push({
      sessionId: response.sessionId,
      id: response.messageId ?? null,
      role: 'assistant',
      modelIdentity: response.modelIdentity,
      model: response.model,
      sourceScope: response.sourceScope ?? selectedScope,
      basisScope: response.basisScope,
      clarifications: response.clarifications,
      title: response.title,
      content: response.grounded ? response.content : null,
      unknownScope: response.unknownScope,
      citations: response.citations,
      evidenceId: response.evidenceId,
      sources: response.sources
    })
    activeSources.value = response.sources ?? []
    clarificationReply.value = null
    activeCitation.value = response.sources?.[0] ?? null
    if (!response.grounded) {
      store.notify(t('advice.unknown.title'))
    }
    await scrollToBottom()
  } catch (caught) {
    if (epoch === viewEpoch && selectedScopeEpoch === scopeEpoch && sequence === askSequence && selectedSession === sessionId.value) {
      if (!question.value) question.value = content
      sessionError.value = caught instanceof Error ? caught.message : String(caught)
    }
  } finally {
    if (epoch === viewEpoch && sequence === askSequence) asking.value = false
  }
}

async function refreshIndex() {
  if (indexing.value || !projectId.value || basisScope.value === 'reference_only') return
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
    await reloadScope()
    if (current()) store.notify(indexHint.value || t('common.done'))
  } catch {
    if (current()) { indexStatus.value = null; indexError.value = pick(evidenceCopy.indexFailed) }
  } finally {
    if (current()) indexing.value = false
    clearRebuildBusy(sequence)
  }
}

async function clearChat() {
  const id = projectId.value, epoch = viewEpoch, selectedSession = sessionId.value, selectedScopeEpoch = scopeEpoch
  ++askSequence; ++reloadSequence; asking.value = false
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
  }
}

function replyToClarification(message: ChatMessage) {
  if (message.id === null || message.basisScope !== 'insufficient' || !message.clarifications?.length || message.sourceScope !== scope.value || message.sessionId !== sessionId.value) return
  clarificationReply.value = message
}

function selectCitation(citation: Citation) {
  activeCitation.value = citation
}

function openMessageSources(message: ChatMessage, citationText?: string) {
  if (!message.sources?.length) return
  activeSources.value = message.sources
  // 若点的是具体引用按钮（citationText 非空），按 fileLabel 找到对应的 source 并联动到它；
  // 否则默认联动到第一个 source（向后兼容）。
  const matched = citationText
    ? message.sources.find((source) => source.fileLabel === citationText || source.evidenceId === citationText)
    : undefined
  activeCitation.value = matched ?? message.sources[0]
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
        <button v-if="basisScope !== 'reference_only'" class="btn" type="button" :disabled="indexing" @click="refreshIndex">
          <AppIcon name="refresh" :size="15" />{{ t('advice.actions.refreshIndex') }}
        </button>
        <!-- FR-A-03 / 原型 Precedence basis：直接以快捷问题形式查询合约文件优先次序（SCC 4.1） -->
        <button class="btn" type="button" :disabled="asking" @click="askPrecedence">
          <AppIcon name="info" :size="15" />{{ t('advice.actions.precedence') }}
        </button>
        <button class="btn" type="button" @click="clearChat">
          <AppIcon name="trash" :size="15" />{{ t('advice.actions.clear') }}
        </button>
      </div>
    </div>

    <details v-if="basisScope !== 'reference_only'" class="surface source-scope section-gap">
      <summary>{{ t('advice.sourceScope.title') }} · {{ scopeDocuments.filter(item => item.included).length }}</summary>
      <p>{{ t('advice.sourceScope.note') }}</p>
      <div class="table-wrap">
        <table>
          <thead><tr><th>{{ t('common.file') }}</th><th>{{ t('advice.sourceScope.role') }}</th><th>{{ t('common.status') }}</th></tr></thead>
          <tbody><tr v-for="document in scopeDocuments" :key="document.documentId" :class="{ 'scope-excluded': !document.included }">
            <td>{{ document.fileName }}</td>
            <td>{{ t(`vetting.uploadRole.${document.sourceRole}`) }}</td>
            <td>
              <div>{{ document.included ? t('advice.sourceScope.included') : t(`advice.sourceScope.reasons.${document.exclusionReason}`) }}</div>
              <div>{{ sourceParseLabel(document) }} · {{ document.parseStatus }}</div>
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
              <div class="muted small">{{ pick(evidenceCopy.project) }}: {{ projectId }} · {{ pick(evidenceCopy.session) }}: {{ sessionId || pick(evidenceCopy.legacy) }}</div>
              <div class="muted small">{{ scopeNote }} · {{ indexHint }}</div>
              <p v-if="indexError" class="warning-note" role="alert">{{ indexError }}</p>
              <div v-if="indexStatus?.evidenceBackend" class="muted small">{{ indexStatus.evidenceBackend }} · {{ indexStatus.sourceScope || scope }}</div>
            </div>
            <select v-model="scope" :aria-label="t('advice.sourceScope.title')" :disabled="!!sessionId || sessionCreating" style="width: auto; min-width: 170px">
              <option v-for="item in SCOPES" :key="item.value" :value="item.value">{{ item.label() }}</option>
            </select>
          </div>

          <div class="session-controls">
            <label>{{ pick(evidenceCopy.session) }}
              <select v-model="sessionId" :disabled="sessionCreating">
                <option value="">{{ pick(evidenceCopy.legacy) }}</option>
                <option v-for="item in sessions" :key="item.id" :value="item.id">{{ item.createdAt }} · {{ item.scope }} · {{ item.basisScope }}</option>
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

          <div ref="historyEl" class="chat-history">
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
                <p class="muted small">{{ message.modelIdentity?.model || message.model ? `${t('llm.recordedModel')}: ${message.modelIdentity?.model || message.model}` : t('llm.identityUnknown') }}<template v-if="message.modelIdentity"> · {{ message.modelIdentity.profileId }} · {{ message.modelIdentity.provider }}</template></p>
                <p v-if="message.modelIdentity" class="muted small">{{ t('llm.identityNote') }}</p>
                <strong>{{ message.content ? pick(message.title) : t('advice.unknown.title') }}</strong>
                <p v-if="message.basisScope === 'reference_only'" class="warning-note">{{ t('advice.referenceOnly') }}</p>
                <AdviceAnswer v-if="message.content" :content="pick(message.content)" />
                <p v-else>{{ unknownText(message.unknownScope) }}</p>
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
            <button class="btn primary" type="button" :disabled="asking || sessionCreating || !question.trim()" @click="submit">
              <AppIcon name="advice" :size="15" />
              {{ asking ? t('common.loading') : t('advice.actions.ask') }}
            </button>
          </div>
        </div>

        <div class="surface-body" style="border-top: 1px solid var(--line)">
          <div class="muted small" style="margin-bottom: 8px">{{ t('advice.quick') }}</div>
          <div class="quick-list">
            <button v-for="(item, i) in quickQuestions" :key="i" type="button" @click="askQuick(item)">
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
                {{ source.anchor || source.fileLabel }}
              </button>
            </div>

            <div v-if="activeCitation" class="source-view">
              <div class="sv-file">{{ activeCitation.fileLabel }} · {{ activeCitation.pageNo }}</div>
              <div class="sv-para" v-html="highlightHtml(activeCitation.content)" />
              <CanonicalEvidencePanel
                v-for="(address, index) in activeCitation.fixedBaselineEvidence ? [] : activeCitation.canonicalEvidence ?? []"
                :key="`${address.projectionSha256}:${address.canonicalChunkId}:${index}`"
                :pointer="address"
                :locale="store.locale"
              />
              <FixedBaselineEvidencePanel v-if="activeCitation.fixedBaselineEvidence" :pointer="activeCitation.fixedBaselineEvidence" :locale="store.locale" />
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
</style>
