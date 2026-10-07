<script setup lang="ts">
import { computed } from 'vue'
import type { ExtractRelation } from '@/api/types'
import type { AppLocale } from '@/i18n'
import { jointWord, jointStatusWord } from '@/drafting/joint-evidence-words'
import DraftingExtractionContext from './DraftingExtractionContext.vue'
const props = defineProps<{ relation: ExtractRelation; locale: AppLocale }>()
const w = (key: string) => jointWord(key, props.locale)
const direction = computed(() => {
  if (props.relation.status !== 'proposed' || !['supplement', 'explicit_replacement'].includes(props.relation.relation ?? '')) return null
  const from = props.relation.evidence?.find(item => item?.decisionRef === props.relation.fromDecisionRef)
  const to = props.relation.evidence?.find(item => item?.decisionRef === props.relation.toDecisionRef)
  return from && to && from !== to ? `${from.fileName} → ${to.fileName}` : null
})
function raw(value: unknown): string { return value === undefined ? '—' : typeof value === 'string' ? value : JSON.stringify(value, null, 2) }
</script>

<template>
  <article class="joint-evidence" :data-joint-review="relation.key" :data-joint-status="relation.status">
    <h4>{{ w(jointStatusWord(relation)) }}</h4>
    <p>{{ w('input') }}: <code>{{ relation.key }}</code></p>
    <p class="joint-note">{{ w('note') }}</p>
    <p v-if="direction"><strong>{{ w('direction') }}:</strong> {{ direction }}</p>
    <p v-if="relation.reason">{{ relation.reason }}</p>
    <div v-if="relation.scopeQuote"><strong>{{ w('scope') }}</strong><blockquote>{{ relation.scopeQuote }}</blockquote></div>
    <ul v-if="relation.codes?.length" class="joint-reasons"><li v-for="code in relation.codes" :key="code">{{ w(code) }} <code>{{ code }}</code></li></ul>
    <details v-if="relation.evidence?.length" open><summary>{{ w('evidence') }}</summary><section v-for="(item, index) in relation.evidence" :key="index" class="joint-source">
      <h5>{{ item?.fileName || w('source') }}</h5>
      <template v-if="item"><p class="source-identity">{{ item.sourceDocumentId }} · {{ item.partId }} · {{ item.sourceHash }}</p><blockquote v-if="item.sourceQuote">{{ item.sourceQuote }}</blockquote><DraftingExtractionContext v-if="item.context" :context="item.context" :locale="locale" dispatched /><details><summary>{{ w('candidate') }}</summary><pre>{{ raw(item.value) }}</pre></details></template>
    </section></details>
    <details><summary>{{ w('details') }}</summary><details><summary>{{ w('system') }}</summary><pre>{{ relation.systemPrompt }}</pre></details><details><summary>{{ w('user') }}</summary><pre>{{ relation.userPrompt }}</pre></details><details><summary>{{ w('raw') }}</summary><pre>{{ raw(relation.rawResponse) }}</pre></details><details><summary>{{ w('rawProposal') }}</summary><pre>{{ raw(relation.rawProposal) }}</pre></details></details>
  </article>
</template>

<style scoped>
.joint-evidence { padding: 10px 12px; margin: 10px 0; border: 1px solid var(--line); border-radius: var(--radius); background: var(--surface); min-width: 0; font-size: 12px; line-height: 1.55; }
h4, h5 { margin: 4px 0; overflow-wrap: anywhere; }
p { margin: 5px 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.joint-note, .source-identity, code { color: var(--muted); }
.joint-source { margin: 8px 0; padding: 8px 10px; background: var(--surface-subtle); border-left: 2px solid var(--line-strong); }
blockquote { white-space: pre-wrap; overflow-wrap: anywhere; margin: 6px 0; padding-left: 10px; border-left: 2px solid var(--line); }
summary { cursor: pointer; padding: 6px 0; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; font: 11px var(--font-mono); max-height: 320px; overflow: auto; }
.joint-reasons { padding-left: 18px; }
</style>
