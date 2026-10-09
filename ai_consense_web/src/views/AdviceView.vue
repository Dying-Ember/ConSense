<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { adviceApi } from '@/api'
import type { ChatMessage, Citation, IndexStatus, QuickQuestion, AdviceSourceDocument } from '@/api/types'
import { useAppStore } from '@/stores/app'
import { useLocalized } from '@/composables/useLocalized'
import AppIcon from '@/components/AppIcon.vue'

const { t } = useI18n()
const store = useAppStore()
const { pick } = useLocalized()

const messages = ref<ChatMessage[]>([])
const quickQuestions = ref<QuickQuestion[]>([])
const indexStatus = ref<IndexStatus | null>(null)
const question = ref('')
const scope = ref('fullset')
const asking = ref(false)
const indexing = ref(false)
const activeCitation = ref<Citation | null>(null)
const activeSources = ref<Citation[]>([])
const historyEl = ref<HTMLElement | null>(null)
const scopeDocuments = ref<AdviceSourceDocument[]>([])
const clarificationReply = ref<ChatMessage | null>(null)
let viewEpoch = 0
let reloadSequence = 0
let scopeSequence = 0

const projectId = computed(() => store.activeProjectId)

const SCOPES = [
  { value: 'fullset', label: () => t('advice.scope.fullset') },
  { value: 'tender', label: () => t('advice.scope.tender') },
  { value: 'contract', label: () => t('advice.scope.contract') }
]

const scopeNote = computed(() => t('advice.scopeNote'))

function sourceParseLabel(document: AdviceSourceDocument) {
  if (document.parseStatus === 'PARSED') return t('advice.sourceScope.parsed')
  if (document.parseStatus === 'PARTIAL') return t('advice.sourceScope.partiallyParsed')
  return t('advice.sourceScope.parseUnavailable')
}

const indexHint = computed(() => {
  const status = indexStatus.value
  if (!status) return ''
  return pick(status.message) || t('advice.indexReady', { count: status.chunks })
})

async function reload() {
  const id = projectId.value
  const epoch = viewEpoch
  const sequence = ++reloadSequence
  if (!id) return
  const [messageList, quickList, status] = await Promise.all([
    adviceApi.messages(id),
    adviceApi.quickQuestions(id),
    adviceApi.indexStatus(id)
  ])
  if (epoch !== viewEpoch || sequence !== reloadSequence || id !== projectId.value) return
  messages.value = messageList
  quickQuestions.value = quickList
  indexStatus.value = status
  activeSources.value = []
  activeCitation.value = null
  await scrollToBottom()
  await reloadScope()
}

async function reloadScope() {
  const id = projectId.value, selectedScope = scope.value, sequence = ++scopeSequence
  if (!id) { scopeDocuments.value = []; return }
  const documents = await adviceApi.sources(id, selectedScope)
  if (id === projectId.value && selectedScope === scope.value && sequence === scopeSequence) scopeDocuments.value = documents
}

onMounted(reload)
watch(projectId, () => {
  viewEpoch++
  messages.value = []; scopeDocuments.value = []; activeSources.value = []; activeCitation.value = null; clarificationReply.value = null
  asking.value = false; indexing.value = false
  reload()
})
watch(scope, () => { clarificationReply.value = null; reloadScope() })
watch(() => messages.value.length, scrollToBottom)

async function scrollToBottom() {
  await nextTick()
  if (historyEl.value) {
    historyEl.value.scrollTop = historyEl.value.scrollHeight
  }
}

async function ask(text?: string) {
  const content = (text ?? question.value).trim()
  if (!content || asking.value) return
  const llmSelection = store.captureLlmSelection()
  const id = projectId.value, selectedScope = scope.value, epoch = viewEpoch
  const replyToMessageId = clarificationReply.value?.id ?? undefined
  asking.value = true
  question.value = ''
  messages.value.push({
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
    const response = await adviceApi.ask(id, content, selectedScope, llmSelection, replyToMessageId)
    if (epoch !== viewEpoch || id !== projectId.value) return
    messages.value.push({
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
  } finally {
    if (epoch === viewEpoch) asking.value = false
  }
}

async function refreshIndex() {
  const id = projectId.value, epoch = viewEpoch
  indexing.value = true
  store.setBusy(t('advice.indexing'))
  try {
    const status = await adviceApi.rebuildIndex(id)
    if (epoch !== viewEpoch || id !== projectId.value) return
    indexStatus.value = status
    await reloadScope()
    store.notify(indexHint.value || t('common.done'))
  } finally {
    if (epoch === viewEpoch) indexing.value = false
    store.clearBusy()
  }
}

async function clearChat() {
  const id = projectId.value, epoch = viewEpoch
  try {
    await adviceApi.clearMessages(id)
    if (epoch !== viewEpoch || id !== projectId.value) return
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
  if (message.id === null || message.basisScope !== 'insufficient' || !message.clarifications?.length || message.sourceScope !== scope.value) return
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
        <button class="btn" type="button" :disabled="indexing" @click="refreshIndex">
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

    <details class="surface source-scope section-gap">
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
              <div class="muted small">{{ scopeNote }} · {{ indexHint }}</div>
            </div>
            <select v-model="scope" style="width: auto; min-width: 170px">
              <option v-for="item in SCOPES" :key="item.value" :value="item.value">{{ item.label() }}</option>
            </select>
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
                <p v-if="message.content">{{ pick(message.content) }}</p>
                <p v-else>{{ unknownText(message.unknownScope) }}</p>
                <div v-if="message.clarifications?.length" class="clarifications">
                  <strong>{{ t('advice.clarifications') }}</strong>
                  <ul><li v-for="item in message.clarifications" :key="item">{{ item }}</li></ul>
                  <button v-if="message.basisScope === 'insufficient'" class="btn" type="button" :disabled="asking || message.id === null || message.sourceScope !== scope" @click="replyToClarification(message)">{{ t('advice.replyClarification') }}</button>
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
          <div class="composer">
            <textarea
              v-model="question"
              :placeholder="t('advice.placeholder')"
              @keydown.enter.exact.prevent="submit"
            />
            <button class="btn primary" type="button" :disabled="asking || !question.trim()" @click="submit">
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
              <div class="sv-meta">
                {{ t('advice.citation.footer') }}
                <span class="mono"> · score {{ activeCitation.score.toFixed(3) }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.source-scope { padding: 14px 18px; margin-bottom: 18px; }
.source-scope summary { cursor: pointer; font-weight: 700; }
.source-scope p { color: var(--muted); }
.scope-excluded { color: var(--muted); }
.source-scope .table-wrap { max-height: 280px; overflow: auto; }
.clarifications { margin-top: 12px; }
</style>
