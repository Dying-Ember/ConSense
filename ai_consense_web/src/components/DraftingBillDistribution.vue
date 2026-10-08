<script setup lang="ts">
import { computed } from 'vue'
import { billDistribution } from '@/drafting/bill-distribution'
import { presentationRowKeys } from '@/drafting/presentation-row-keys'
import { draftWord, type DraftWord } from '@/drafting/words'
import type { DraftValue } from '@/drafting/state'
import type { AppLocale } from '@/i18n'
const props = defineProps<{ values: Record<string, DraftValue>; locale: AppLocale }>()
const view = computed(() => billDistribution(props.values))
const renderGroups = computed(() => view.value.groups.map(group => ({ ...group, rowKeys: presentationRowKeys(group.rows) })))
const w = (key: DraftWord) => draftWord(key, props.locale)
const placementLabel = (placement: DraftWord) => placement === 'discB' ? w(view.value.mode === 'Hardcopy' ? 'discBHardcopy' : view.value.mode === 'L10Pro' ? 'discBL10Pro' : 'discB') : w(placement)
</script>
<template>
  <section class="bill-distribution" aria-live="polite">
    <h4>{{ w('billDistribution') }}</h4><p>{{ w('distributionNote') }}</p><p class="mode-notice">{{ w(view.mode === 'L10Pro' ? 'l10Notice' : view.mode === 'Hardcopy' ? 'hardcopyNotice' : 'unknownIssueNotice') }}</p>
    <div v-for="group in renderGroups" :key="group.placement" class="distribution-group" :data-placement="group.placement"><h5>{{ placementLabel(group.placement) }}</h5><table><thead><tr><th>{{ w('billNumber') }}</th><th>{{ w('billDescription') }}</th><th>{{ w('issueParts') }}</th></tr></thead><tbody><tr v-for="(row, index) in group.rows" :key="group.rowKeys[index]" :data-bill-id="row.id"><td>{{ row.type }} {{ row.number }}</td><td>{{ row.description }}</td><td><p>{{ row.exactText || w(row.portion) }}</p><p v-if="row.sourceAmendmentRequired && row.reviewReason" class="source-review"><strong>{{ w('sourceAmendmentRequired') }}</strong><br />{{ w(row.reviewReason) }}</p><p v-if="row.rolePending" class="warning">{{ w('billRolePending') }}</p></td></tr></tbody></table><p v-if="['discB', 'hardcopy', 'discC'].includes(group.placement) && group.rows.some(row => row.type === 'BQ' && row.portion !== 'adoptedPlacement')">{{ w('generalSummary') }}</p></div>
    <p v-if="view.mode !== 'unknown'" class="return-note">{{ w(view.mode === 'L10Pro' ? 'l10Return' : 'paperReturn') }}</p><p v-if="view.customTendering" class="warning">{{ w('customReturnReview') }}</p><small>SCT1 / SCT3</small>
  </section>
</template>
<style scoped>
.bill-distribution { margin-top: 18px; background: var(--surface-subtle); border: 1px solid var(--line); border-radius: var(--radius); padding: 16px; font-size: 12px; line-height: 1.55; color: var(--ink-soft); }
h4 { font-size: 14px; margin: 0 0 6px; color: var(--ink); }
h5 { font-size: 12px; margin: 10px 0 5px; }
p { margin: 6px 0; }
.mode-notice { padding: 9px; background: var(--accent-soft); color: var(--accent-dark); border-radius: var(--radius-sm); }
.distribution-group { margin: 13px 0; overflow-x: auto; }
table { width: 100%; border-collapse: collapse; table-layout: fixed; font-size: 12px; min-width: 380px; }
th, td { text-align: left; vertical-align: top; border-top: 1px solid var(--line); padding: 8px; overflow-wrap: anywhere; white-space: pre-wrap; }
th { color: var(--muted); font-size: 11.5px; font-weight: 700; }
th:first-child { width: 15%; }
th:nth-child(2) { width: 35%; }
.warning { color: var(--amber); }
.source-review { padding: 9px 10px; border-left: 3px solid var(--amber); background: var(--amber-soft); color: var(--amber); border-radius: var(--radius-sm); }
.return-note { padding-top: 12px; border-top: 1px solid var(--line); }
small { color: var(--muted); }
</style>
