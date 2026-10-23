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
    <p v-if="context.trigger">{{ w('contextTrigger') }}: {{ contextTrigger(context.trigger, locale) }}</p>
    <section v-if="context.sourceText !== undefined && context.sourceText !== null"><p><strong>{{ w(dispatched ? 'suppliedContextText' : 'recordedContextText') }}</strong></p><pre>{{ context.sourceText }}</pre></section>
    <p v-if="context.stopReason">{{ w('contextStopReason') }}: {{ recallStopReason(context.stopReason, locale) }}</p>
  </div>
</template>

<style scoped>
.extraction-context { padding: 8px 12px; background: var(--surface-subtle); border: 1px solid var(--line); border-radius: var(--radius-sm); }
p { margin: 4px 0; overflow-wrap: anywhere; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; font-family: inherit; max-height: 400px; overflow: auto; }
</style>
