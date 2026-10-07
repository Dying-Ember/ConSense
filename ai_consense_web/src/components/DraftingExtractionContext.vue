<script setup lang="ts">
import type { ExtractContext } from '@/api/types'
import type { AppLocale } from '@/i18n'
import { draftWord, type DraftWord } from '@/drafting/words'
import { contextTrigger, recallStopReason } from '@/drafting/extraction-diagnostics'
const props = defineProps<{ context: ExtractContext; locale: AppLocale; dispatched?: boolean }>()
const w = (key: DraftWord) => draftWord(key, props.locale)
</script>

<template>
  <div class="extraction-context">
    <p v-if="context.trigger">{{ w('contextTrigger') }}: {{ contextTrigger(context.trigger, locale) }} <code>{{ context.trigger }}</code></p>
    <p v-if="context.keys?.length">{{ w('contextKeys') }}: {{ context.keys.join(', ') }}</p>
    <p v-if="context.sourceStart !== undefined && context.sourceStart !== null && context.sourceEnd !== undefined && context.sourceEnd !== null">{{ w('contextOffsets') }}: [{{ context.sourceStart }}, {{ context.sourceEnd }})</p>
    <details v-if="context.sourceText !== undefined && context.sourceText !== null"><summary>{{ w(dispatched ? 'suppliedContextText' : 'recordedContextText') }}</summary><pre>{{ context.sourceText }}</pre></details>
    <p v-if="context.stopReason">{{ w('contextStopReason') }}: {{ recallStopReason(context.stopReason, locale) }} <code>{{ context.stopReason }}</code></p>
  </div>
</template>

<style scoped>
.extraction-context { padding: 8px 12px; background: var(--surface-subtle); border: 1px solid var(--line); border-radius: var(--radius-sm); }
p { margin: 4px 0; overflow-wrap: anywhere; }
summary { cursor: pointer; padding: 8px 0; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; font-family: var(--font-mono); max-height: 400px; overflow: auto; }
code { font-size: 11px; color: var(--muted); }
</style>
