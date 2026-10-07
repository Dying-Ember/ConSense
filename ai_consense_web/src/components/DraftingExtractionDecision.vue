<script setup lang="ts">
import type { ExtractDecision, ExtractTrace } from '@/api/types'
import type { AppLocale } from '@/i18n'
import { draftWord, type DraftWord } from '@/drafting/words'
import { diagnosticCode, diagnosticValue } from '@/drafting/extraction-diagnostics'
const props = defineProps<{ decision: ExtractDecision; trace: ExtractTrace; locale: AppLocale }>()
const w = (key: DraftWord) => draftWord(key, props.locale)
function emptySuggestion(value: unknown): boolean {
  if (Array.isArray(value)) return value.length === 0
  if (typeof value !== 'string') return false
  try { const parsed: unknown = JSON.parse(value); return Array.isArray(parsed) && parsed.length === 0 } catch { return false }
}
</script>

<template>
  <article class="extraction-decision" :data-decision-status="decision.status">
    <p><strong>{{ w(decision.status === 'accepted' ? 'intakeAccepted' : decision.status === 'rejected' ? 'intakeRejected' : 'intakeUnanswered') }}</strong> · {{ decision.key || w('unidentifiedKey') }}</p>
    <p class="source-identity">{{ trace.parts?.find(part => part.partId === decision.partId)?.fileName || decision.partId }} · {{ w('attempt') }} {{ decision.attemptIndex }} · {{ w('item') }} {{ decision.itemIndex + 1 }}</p>
    <p v-if="decision.status === 'accepted' && emptySuggestion(decision.normalizedValue)">{{ w('emptyListSuggestion') }}</p>
    <dl><dt>{{ w('rawValue') }}</dt><dd><pre>{{ diagnosticValue(decision.rawValue) }}</pre></dd><template v-if="decision.normalizedValue !== undefined && decision.normalizedValue !== null"><dt>{{ w('normalizedValue') }}</dt><dd><pre>{{ diagnosticValue(decision.normalizedValue) }}</pre></dd></template></dl>
    <ul v-if="decision.codes?.length"><li v-for="(code, index) in decision.codes" :key="`${code}-${index}`"><span>{{ diagnosticCode(code, locale) }}</span> <code>{{ code }}</code></li></ul>
    <blockquote v-if="decision.sourceQuote">{{ decision.sourceQuote }}</blockquote>
    <p v-if="decision.reason" class="raw-text">{{ decision.reason }}</p>
    <p v-if="decision.confidence !== null && decision.confidence !== undefined">{{ w('confidence') }}: {{ decision.confidence }}</p>
  </article>
</template>

<style scoped>
.extraction-decision { padding: 12px 14px; margin: 10px 0; border: 1px solid var(--line); border-radius: var(--radius); background: var(--surface); font-size: 12px; line-height: 1.55; }
.source-identity, dt { color: var(--muted); }
p { margin: 4px 0; }
dl { margin: 8px 0; }
dd { margin: 0; }
pre, .raw-text, blockquote { white-space: pre-wrap; overflow-wrap: anywhere; }
pre { margin: 4px 0; max-height: 240px; overflow: auto; font-family: var(--font-mono); }
blockquote { margin: 10px 0; padding-left: 12px; border-left: 3px solid var(--line-strong); }
ul { padding-left: 20px; }
code { font-size: 11px; color: var(--muted); }
</style>
