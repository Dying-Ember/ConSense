<script setup lang="ts">
import { computed } from 'vue'
import type { DraftField } from '@/api/types'
import type { DraftValue } from '@/drafting/state'
import type { AppLocale } from '@/i18n'
import { localized } from '@/drafting/words'
import { previewWord, type PreviewWord } from '@/drafting/preview-words'
import { presentationColumns, presentationValue, scalarValueLabel } from '@/drafting/value-presentation'

const props = defineProps<{ field: DraftField; value: DraftValue | undefined; locale: AppLocale }>()
const w = (key: PreviewWord) => previewWord(key, props.locale)
const l = (field: DraftField) => localized(field.label, props.locale)
const presented = computed(() => presentationValue(props.field, props.value))
const rows = computed(() => Array.isArray(presented.value.value) ? presented.value.value : [])
const object = computed(() => record(presented.value.value))
const columns = computed(() => presentationColumns(props.field, object.value ? [object.value] : rows.value))
const billList = computed(() => props.field.key === 'billNos' || props.field.kind === 'bills')
const primaryColumns = computed(() => billList.value ? ['number', 'description', 'type'].flatMap(key => columns.value.filter(column => column.key === key)) : columns.value)
const detailColumns = computed(() => columns.value.filter(column => !primaryColumns.value.includes(column)))
function record(value: DraftValue | undefined): Record<string, DraftValue> | undefined { return value && typeof value === 'object' && !Array.isArray(value) ? value : undefined }
function cell(row: DraftValue, key: string): DraftValue | undefined { return record(row)?.[key] }
function rawText(): string { return typeof props.value === 'object' ? JSON.stringify(props.value, null, 2) : String(props.value ?? '') }
</script>

<template>
  <div class="draft-value-display">
    <template v-if="presented.invalid"><p class="value-warning">{{ w('invalidValue') }}</p><details class="raw-value"><summary>{{ w('rawValue') }}</summary><pre>{{ rawText() }}</pre></details></template>
    <p v-else-if="presented.value == null || presented.value === ''" class="value-unknown">{{ w('unknown') }}</p>
    <template v-else-if="Array.isArray(presented.value)">
      <p v-if="!rows.length">{{ w('noValueItems') }}</p>
      <ul v-else-if="field.kind === 'multiselect'" class="value-options"><li v-for="(item, index) in rows" :key="index">{{ scalarValueLabel(field, item, locale) }}</li></ul>
      <div v-else-if="billList && primaryColumns.length" class="value-table"><table><thead><tr><th v-for="column in primaryColumns" :key="column.key" scope="col">{{ l(column) }}</th><th v-if="detailColumns.length" scope="col">{{ w('details') }}</th></tr></thead><tbody><tr v-for="(row, index) in rows" :key="index"><td v-for="column in primaryColumns" :key="column.key"><DraftingValueDisplay :field="column" :value="cell(row, column.key)" :locale="locale" /></td><td v-if="detailColumns.length"><details><summary>{{ w('details') }}</summary><dl><template v-for="column in detailColumns" :key="column.key"><dt>{{ l(column) }}</dt><dd><DraftingValueDisplay :field="column" :value="cell(row, column.key)" :locale="locale" /></dd></template></dl></details></td></tr></tbody></table></div>
      <ol v-else class="value-records"><li v-for="(row, index) in rows" :key="index"><dl v-if="record(row) && columns.length"><template v-for="column in columns" :key="column.key"><dt>{{ l(column) }}</dt><dd><DraftingValueDisplay :field="column" :value="cell(row, column.key)" :locale="locale" /></dd></template></dl><p v-else>{{ scalarValueLabel(field, row, locale) }}</p></li></ol>
    </template>
    <dl v-else-if="object"><template v-for="column in columns" :key="column.key"><dt>{{ l(column) }}</dt><dd><DraftingValueDisplay :field="column" :value="object[column.key]" :locale="locale" /></dd></template></dl>
    <p v-else class="scalar-value">{{ scalarValueLabel(field, presented.value, locale) }}</p>
  </div>
</template>

<style scoped>
.draft-value-display { min-width: 0; font-size: 13px; line-height: 1.55; color: var(--ink-soft); }
.draft-value-display p { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.value-table { max-height: 320px; overflow: auto; border: 1px solid var(--line); border-radius: var(--radius-sm); }
table { width: 100%; min-width: 380px; border-collapse: collapse; table-layout: fixed; }
th, td { border-bottom: 1px solid var(--line); padding: 8px; vertical-align: top; text-align: left; overflow-wrap: anywhere; }
th { position: sticky; top: 0; background: var(--surface-subtle); font-size: 11.5px; font-weight: 700; color: var(--muted); }
th:first-child { width: 14%; }
th:nth-child(2) { width: 45%; }
th:last-child { width: 15%; }
dl { display: grid; grid-template-columns: minmax(95px, 1fr) minmax(0, 2fr); gap: 5px 12px; margin: 0; }
dt { font-size: 11.5px; font-weight: 700; color: var(--muted); overflow-wrap: anywhere; }
dd { margin: 0; min-width: 0; }
.value-table details dl { display: block; }
.value-table details dt { margin-top: 7px; }
.value-records, .value-options { padding-left: 20px; margin: 0; }
.value-records > li { padding: 8px 0; border-bottom: 1px solid var(--line); }
summary { cursor: pointer; font-size: 12px; color: var(--accent-dark); }
.value-unknown { color: var(--muted); }
.value-warning { color: var(--amber); }
.raw-value pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 150px; overflow: auto; font-size: 11px; font-family: var(--font-mono); }
</style>
