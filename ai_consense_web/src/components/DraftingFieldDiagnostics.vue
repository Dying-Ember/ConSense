<script setup lang="ts">
import { computed } from 'vue'
import type { ExtractTrace } from '@/api/types'
import type { AppLocale } from '@/i18n'
import { draftWord, type DraftWord } from '@/drafting/words'
import { fieldDiagnosticWord } from '@/drafting/extraction-diagnostics'
import DraftingExtractionDecision from './DraftingExtractionDecision.vue'
import DraftingJointEvidence from './DraftingJointEvidence.vue'
import { jointWord } from '@/drafting/joint-evidence-words'
const props = defineProps<{ fieldKey: string; trace: ExtractTrace; locale: AppLocale }>()
const w = (key: DraftWord) => draftWord(key, props.locale)
const field = computed(() => props.trace.fields?.find(item => item.key === props.fieldKey))
const decisions = computed(() => (field.value?.decisionRefs ?? []).flatMap(index => props.trace.decisions?.[index] ? [props.trace.decisions[index]] : []))
const sourceUnresolved = computed(() => field.value?.status === 'source_unresolved' ? decisions.value.filter(decision => decision.status === 'unanswered' && decision.codes?.includes('source_unresolved')) : [])
const relations = computed(() => props.trace.relations?.filter(item => item.key === props.fieldKey) ?? [])
</script>

<template>
  <aside v-if="field" class="field-diagnostics" :data-extraction-field="fieldKey" :data-extraction-status="field.status">
    <p><strong>{{ w('latestExtraction') }}</strong> · {{ w(fieldDiagnosticWord(field.status)) }}</p>
    <p v-if="['rejected', 'model_unanswered', 'no_candidate'].includes(field.status)">{{ w('noUsableSuggestion') }}</p>
    <p v-if="field.status === 'source_unresolved'">{{ w('sourceUnresolvedNote') }}</p>
    <div v-for="(decision, index) in sourceUnresolved" :key="index" class="unresolved-source"><strong>{{ trace.parts?.find(part => part.partId === decision.partId)?.fileName || decision.partId }}</strong><p v-if="decision.reason">{{ decision.reason }}</p><blockquote v-if="decision.sourceQuote">{{ decision.sourceQuote }}</blockquote></div>
    <p v-if="field.status === 'coverage_unresolved'">{{ w('coverageUnresolvedNote') }}</p>
    <p v-if="field.status === 'candidate_conflict'">{{ w('candidateConflictNote') }}</p>
    <p v-if="field.status === 'suggested'">{{ w('intakeNotFact') }}</p>
    <p v-if="trace.stale" class="diagnostic-warning">{{ w('traceStale') }}</p>
    <p v-if="trace.status === 'failed'" class="diagnostic-warning">{{ w('traceFailedNote') }}</p>
    <p v-if="trace.status === 'partial'" class="diagnostic-warning">{{ w('tracePartialNote') }}</p>
    <details v-if="relations.length" open><summary>{{ jointWord('title', locale) }}</summary><DraftingJointEvidence v-for="(relation, index) in relations" :key="index" :relation="relation" :locale="locale" /></details>
    <details v-if="decisions.length"><summary>{{ w('diagnosticDetails') }} · {{ w('intakeAccepted') }} {{ field.candidateCount }} / {{ w('intakeRejected') }} {{ field.rejectionCount }} / {{ w('intakeUnanswered') }} {{ field.unansweredCount }}</summary><DraftingExtractionDecision v-for="(decision, index) in decisions" :key="index" :decision="decision" :trace="trace" :locale="locale" /></details>
  </aside>
</template>

<style scoped>
.field-diagnostics { margin: 10px 0 0; padding: 12px 14px; border: 1px solid var(--line); border-radius: var(--radius); background: var(--surface-subtle); font-size: 12px; line-height: 1.55; color: var(--ink-soft); }
.field-diagnostics p { margin: 3px 0; }
.field-diagnostics summary { cursor: pointer; padding: 6px 0; color: var(--muted); }
.diagnostic-warning { color: var(--amber); background: var(--amber-soft); padding: 6px 8px; border-radius: var(--radius-sm); }
.unresolved-source { margin: 8px 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.unresolved-source blockquote { margin: 6px 0; padding-left: 10px; border-left: 2px solid var(--line-strong); }
</style>
