<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { adviceApi } from '@/api'
import type { ChatMessage, Citation, IndexStatus, QuickQuestion } from '@/api/types'
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

const projectId = computed(() => store.activeProjectId)

const SCOPES = [
  { value: 'fullset', label: () => t('advice.scope.fullset') },
  { value: 'tender', label: () => t('advice.scope.tender') },
  { value: 'contract', label: () => t('advice.scope.contract') }
]

const scopeNote = computed(() => t('advice.scopeNote'))

const indexHint = computed(() => {
  const status = indexStatus.value
  if (!status) return ''
  return t('advice.indexReady', { count: status.chunks })
})

async function reload() {
  if (!projectId.value) return
  const [messageList, quickList, status] = await Promise.all([
    adviceApi.messages(projectId.value),
    adviceApi.quickQuestions(projectId.value),
    adviceApi.indexStatus(projectId.value)
  ])
  messages.value = messageList
  quickQuestions.value = quickList
  indexStatus.value = status
  activeSources.value = []
  activeCitation.value = null
  await scrollToBottom()
}

onMounted(reload)
watch(projectId, reload)
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
    const response = await adviceApi.ask(projectId.value, content, scope.value, llmSelection)
    messages.value.push({
      id: null,
      role: 'assistant',
      modelIdentity: response.modelIdentity,
      model: response.model,
      title: response.title,
      content: response.grounded ? response.content : null,
      unknownScope: response.unknownScope,
      citations: response.citations,
      evidenceId: response.evidenceId,
      sources: response.sources
    })
    activeSources.value = response.sources ?? []
    activeCitation.value = response.sources?.[0] ?? null
    if (!response.grounded) {
      store.notify(t('advice.unknown.title'))
    }
    await scrollToBottom()
  } finally {
    asking.value = false
  }
}

async function refreshIndex() {
  indexing.value = true
  store.setBusy(t('advice.indexing'))
  try {
    indexStatus.value = await adviceApi.rebuildIndex(projectId.value)
    store.notify(indexHint.value || t('common.done'))
  } finally {
    indexing.value = false
    store.clearBusy()
  }
}

async function clearChat() {
  try {
    await adviceApi.clearMessages(projectId.value)
    messages.value = []
    activeSources.value = []
    activeCitation.value = null
  } catch (error) {
    // 全局 reporter 已经 toast 出 message；这里把堆栈打 console 方便排查
    console.error('[clearChat] failed', error)
    throw error
  }
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
    ? message.sources.find((source) => source.fileLabel === citationText)
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
  const text = pick(item.text)
  rebuildTerms(text)
  await ask(text)
}

/** 优先次序依据（SCC 4.1）：以固定问题查询，命中后引用面板自动展示来源原文 */
async function askPrecedence() {
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
                <p v-if="message.content">{{ pick(message.content) }}</p>
                <p v-else>{{ unknownText(message.unknownScope) }}</p>

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
