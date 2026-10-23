<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { draftingApi } from '@/api'
import type { ExtractRunSummary, ExtractTrace } from '@/api/types'
import type { AppLocale } from '@/i18n'
import { draftWord, type DraftWord } from '@/drafting/words'
import { extractionAttemptWord, extractionRunWord } from '@/drafting/extraction-diagnostics'
import DraftingExtractionDecision from './DraftingExtractionDecision.vue'
import DraftingExtractionContext from './DraftingExtractionContext.vue'
import DraftingJointEvidence from './DraftingJointEvidence.vue'
import { jointWord } from '@/drafting/joint-evidence-words'

const props = defineProps<{ trace: ExtractTrace | null; projectId: string; locale: AppLocale; open: boolean }>()
const w = (key: DraftWord) => draftWord(key, props.locale)
const history = ref<ExtractRunSummary[]>([])
const selectedId = ref('')
const historical = ref<ExtractTrace | null>(null)
const loading = ref(false)
const historyError = ref(false)
let sequence = 0, alive = true
const displayed = computed(() => selectedId.value ? historical.value : props.trace)
const counts = computed(() => {
  const decisions = displayed.value?.decisions ?? []
  return { accepted: decisions.filter(item => item.status === 'accepted').length, rejected: decisions.filter(item => item.status === 'rejected').length, unanswered: decisions.filter(item => item.status === 'unanswered').length }
})
function current(project: string, token: number) { return alive && props.open && project === props.projectId && token === sequence }
async function loadHistory() {
  const project = props.projectId, token = ++sequence
  selectedId.value = ''; historical.value = null; history.value = []; historyError.value = false
  if (!props.open || !project) { loading.value = false; return }
  loading.value = true
  try { const result = await draftingApi.extractTraces(project, 20); if (current(project, token)) history.value = result ?? [] }
  catch { if (current(project, token)) historyError.value = true }
  finally { if (current(project, token)) loading.value = false }
}
async function selectReport(event: Event) {
  const id = (event.target as HTMLSelectElement).value, project = props.projectId, token = ++sequence
  selectedId.value = id; historical.value = null; historyError.value = false; loading.value = false
  if (!id || !props.open || !project) return
  loading.value = true
  try { const result = await draftingApi.extractRun(project, id); if (current(project, token) && selectedId.value === id) historical.value = result }
  catch { if (current(project, token)) historyError.value = true }
  finally { if (current(project, token)) loading.value = false }
}
watch(() => [props.projectId, props.open, props.trace?.runId], () => { void loadHistory() }, { immediate: true })
onBeforeUnmount(() => { alive = false; sequence++ })
</script>

<template>
  <section class="extraction-report">
    <label class="report-selector">{{ w('reportSelection') }}<select :value="selectedId" :aria-label="w('reportSelection')" @change="selectReport"><option value="">{{ w('latestReport') }}</option><option v-for="run in history.filter(item => item.runId !== trace?.runId)" :key="run.runId" :value="run.runId">{{ run.finishedAt }} · {{ run.modelIdentity?.model || run.model }} · {{ w(extractionRunWord(run.status)) }}</option></select></label>
    <p class="hint">{{ w('traceHistoryBound') }}</p>
    <p v-if="loading" role="status">{{ w('loading') }}</p><p v-if="historyError" class="diagnostic-warning" role="alert">{{ w('traceHistoryUnavailable') }}</p><p v-else-if="!loading && !history.length" class="hint">{{ w('emptyTraceHistory') }}</p>
    <p v-if="selectedId" class="diagnostic-warning">{{ w('historicalReport') }}</p>
    <template v-if="displayed">
      <h3>{{ displayed.status ? w(extractionRunWord(displayed.status)) : w('trace') }}</h3>
      <p>{{ w('recordedModel') }}: {{ displayed.modelIdentity?.model || displayed.model || '—' }} · {{ displayed.finishedAt }}</p>
      <template v-if="displayed.modelIdentity"><p>{{ displayed.modelIdentity.provider }}</p><p class="hint">{{ w('profileIdentityNote') }}</p></template>
      <p v-else class="hint">{{ w('profileIdentityUnknown') }}</p>
      <p v-if="displayed.stale" class="diagnostic-warning">{{ w('traceStale') }}</p>
      <div v-if="displayed.status === 'failed'" class="diagnostic-warning"><p>{{ w('traceFailedNote') }}</p><p class="raw-text">{{ displayed.failureCode }} · {{ displayed.failureMessage }}</p></div>
      <div v-if="displayed.status === 'partial'" class="diagnostic-warning" role="status"><p>{{ w('tracePartialNote') }}</p><p class="raw-text">{{ displayed.failureCode }} · {{ displayed.failureMessage }}</p></div>
      <dl class="run-identity"><template v-if="displayed.startedAt"><dt>{{ w('startedAt') }}</dt><dd>{{ displayed.startedAt }}</dd></template></dl>
      <template v-if="displayed.decisions"><p class="intake-summary">{{ w('intakeAccepted') }}: {{ counts.accepted }} · {{ w('intakeRejected') }}: {{ counts.rejected }} · {{ w('intakeUnanswered') }}: {{ counts.unanswered }}</p><p class="hint">{{ w('intakeRecordCounts') }}</p><p class="hint">{{ w('intakeNotFact') }}</p></template>
      <p v-else class="hint">{{ w('legacyTrace') }}</p>
      <details v-if="displayed.relations?.length" open><summary>{{ jointWord('title', locale) }} · {{ displayed.relations.length }}</summary><DraftingJointEvidence v-for="(relation, index) in displayed.relations" :key="index" :relation="relation" :locale="locale" /></details>
      <details v-if="displayed.parts?.length" open><summary>{{ w('sourceParts') }}</summary><article v-for="part in displayed.parts" :key="part.partId" class="source-part"><h4>{{ part.fileName }} · {{ w('sourcePart') }} {{ part.partIndex }}</h4><details><summary>{{ w('sourcePartText') }}</summary><pre>{{ part.sourceText }}</pre></details><details v-if="part.context" class="context-plan"><summary>{{ w('contextPlan') }}</summary><DraftingExtractionContext :context="part.context" :locale="locale" /></details><details v-for="attempt in part.attempts ?? []" :key="attempt.attemptIndex" class="attempt"><summary>{{ w('attempt') }} {{ attempt.attemptIndex }} · {{ w(extractionAttemptWord(attempt.kind)) }} · {{ w(attempt.status === 'partial' ? 'attemptPartial' : ['completed', 'succeeded', 'success'].includes(attempt.status) ? 'attemptCompleted' : ['failed', 'error'].includes(attempt.status) ? 'attemptFailed' : 'attemptInvalid') }}</summary><p v-if="attempt.errorMessage" class="raw-text">{{ attempt.errorMessage }}</p><DraftingExtractionContext v-if="attempt.context" :context="attempt.context" :locale="locale" dispatched /><details><summary>{{ w('systemPrompt') }}</summary><pre>{{ attempt.systemPrompt }}</pre></details><details><summary>{{ w('userPrompt') }}</summary><pre>{{ attempt.userPrompt }}</pre></details><details open><summary>{{ w('rawResponses') }}</summary><pre>{{ attempt.rawResponse }}</pre></details><details v-if="attempt.providerResponse || attempt.providerFailureKind"><summary>{{ w('providerResponse') }}</summary><p>{{ attempt.providerFailureKind }}</p><pre>{{ JSON.stringify(attempt.providerResponseMetadata, null, 2) }}</pre><pre>{{ attempt.providerResponse }}</pre></details></details></article></details>
      <details v-if="displayed.decisions?.length"><summary>{{ w('returnedItems') }} · {{ displayed.decisions.length }}</summary><DraftingExtractionDecision v-for="(decision, index) in displayed.decisions" :key="index" :decision="decision" :trace="displayed" :locale="locale" /></details>
      <details><summary>{{ w('systemPrompt') }}</summary><pre>{{ displayed.systemPrompt }}</pre></details><details><summary>{{ w('userPrompt') }}</summary><pre>{{ displayed.userPrompt }}</pre></details><details :open="!displayed.parts?.length"><summary>{{ w('rawResponses') }}</summary><pre>{{ (displayed.rawResponses ?? []).join('\n\n') }}</pre></details>
    </template>
    <p v-else-if="!loading" class="hint">{{ w('noTrace') }}</p>
  </section>
</template>

<style scoped>
.extraction-report { font-size: 13px; line-height: 1.55; min-width: 0; }
.report-selector { display: flex; flex-direction: column; gap: 6px; font-size: 11.5px; font-weight: 700; color: var(--muted); margin-bottom: 8px; }
.report-selector select { font: inherit; font-size: 14px; min-height: 36px; padding: 6px 10px; border: 1px solid var(--line); border-radius: 6px; width: 100%; min-width: 0; background: var(--surface); color: var(--ink); }
.extraction-report h3 { margin-top: 18px; }
.extraction-report summary { cursor: pointer; padding: 8px 0; }
.source-part { border: 1px solid var(--line); border-radius: var(--radius); padding: 12px 14px; margin: 10px 0; background: var(--surface-subtle); }
.source-part h4 { margin: 0; overflow-wrap: anywhere; }
.attempt { padding-left: 12px; border-left: 2px solid var(--line-strong); }
.run-identity { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 4px 16px; }
.run-identity dd { margin: 0; overflow-wrap: anywhere; }
.hint, dt { color: var(--muted); }
.diagnostic-warning { background: var(--amber-soft); color: var(--amber); padding: 8px 12px; border-radius: var(--radius-sm); }
.intake-summary { background: var(--surface-subtle); border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 10px; }
.raw-text, pre { white-space: pre-wrap; overflow-wrap: anywhere; }
pre { font-size: 12px; font-family: var(--font-mono); max-height: 400px; overflow: auto; }
code { font-size: 11px; color: var(--muted); }
</style>
