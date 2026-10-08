<script setup lang="ts">
import { computed } from 'vue'
import type { DraftField, ExtractTrace } from '@/api/types'
import DraftingFieldDiagnostics from './DraftingFieldDiagnostics.vue'
import { applicability, clone, type DraftValue } from '@/drafting/state'
import { presentationRowKeys } from '@/drafting/presentation-row-keys'
import { isObjectKind, isRecordCollectionKind } from '@/drafting/field-kinds'
import { draftWord, localized, type DraftWord } from '@/drafting/words'
import type { AppLocale } from '@/i18n'

const props = defineProps<{ field: DraftField; value: DraftValue | undefined; values: Record<string, DraftValue>; locale: AppLocale; disabled?: boolean; idPrefix?: string; compact?: boolean; hideLabel?: boolean; trace?: ExtractTrace | null }>()
const emit = defineEmits<{ update: [value: DraftValue] }>()
const w = (key: DraftWord) => draftWord(key, props.locale)
const l = (text: Parameters<typeof localized>[0]) => localized(text, props.locale)
const showOptional = (field: DraftField) => field.optional && !/[（(]\s*(?:optional|可选|可選)\s*[）)]\s*$/i.test(l(field.label))
const id = computed(() => `${props.idPrefix || 'input'}-${props.field.key}`)
const multilineTextKeys = new Set([
  'nscAlternativeText', 'otherTenderingArrangement', 'ovtProjectSpecificText', 'otherWaterproofingSpecificationAreas',
  'description', 'placementText', 'scope', 'text', 'location'
])
const multiline = computed(() => ['textarea', 'longtext'].includes(props.field.kind) || (props.field.kind === 'text' && (multilineTextKeys.has(props.field.key) || (typeof props.value === 'string' && /[\r\n]/.test(props.value)))))
const rows = computed(() => Array.isArray(props.value) ? props.value : [])
const rowKeys = computed(() => presentationRowKeys(rows.value))
const object = computed(() => props.value && typeof props.value === 'object' && !Array.isArray(props.value) ? props.value : {})
const collection = computed(() => isRecordCollectionKind(props.field.kind))
const objectKind = computed(() => isObjectKind(props.field.kind))
const columns = computed<DraftField[]>(() => props.field.columnFields?.length ? props.field.columnFields.filter(column => column.key !== 'id') : props.field.kind === 'contract' ? [
  { key: 'number', kind: 'text', label: { zhHans: '合约编号', zhHant: '合約編號', en: 'Contract number' } },
  { key: 'title', kind: 'text', label: { zhHans: '合约名称', zhHant: '合約名稱', en: 'Contract title' } }
] : [])
const options = computed(() => props.field.options?.length ? props.field.options : props.field.kind === 'boolean' ? [
  { value: 'true', label: { zhHans: '是', zhHant: '是', en: 'Yes' } },
  { value: 'false', label: { zhHans: '否', zhHant: '否', en: 'No' } }
] : [])
function updateRaw(event: Event) { emit('update', (event.target as HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement).value || null) }
function updateObject(key: string, value: DraftValue) { emit('update', { ...clone(object.value), [key]: value }) }
function updateRow(index: number, value: DraftValue, key?: string) {
  const next = clone(rows.value)
  next[index] = key ? { ...(next[index] && typeof next[index] === 'object' && !Array.isArray(next[index]) ? next[index] as Record<string, DraftValue> : {}), [key]: value } : value
  emit('update', next)
}
function addRow() {
  const next = clone(rows.value)
  const recordId = typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : `row-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`
  next.push(columns.value.length ? { ...Object.fromEntries(columns.value.map(column => [column.key, null])), id: recordId } : '')
  emit('update', next)
}
function removeRow(index: number) { const next = clone(rows.value); next.splice(index, 1); emit('update', next) }
function rowValue(row: DraftValue, key: string): DraftValue { return row && typeof row === 'object' && !Array.isArray(row) ? row[key] ?? null : null }
function toggle(value: string | boolean | number, event: Event) {
  const selected = clone(rows.value)
  emit('update', (event.target as HTMLInputElement).checked ? [...selected.filter(item => item !== value), value] : selected.filter(item => item !== value))
}
</script>

<template>
  <div class="draft-field" :class="{ compact }" :data-field="field.key">
    <label v-if="!hideLabel" class="field-label" :for="id">{{ l(field.label) }} <small v-if="showOptional(field)">({{ w('optional') }})</small></label>
    <p v-if="field.description" class="field-description">{{ l(field.description) }}</p>
    <div v-if="objectKind" :id="id" class="object-fields">
      <DraftingInputField v-for="column in columns.filter(item => applicability(item.condition, { ...values, ...object }) !== 'no')" :key="column.key" :field="column" :value="object[column.key]" :values="{ ...values, ...object }" :locale="locale" :disabled="disabled" :id-prefix="id" compact @update="updateObject(column.key, $event)" />
    </div>
    <div v-else-if="field.kind === 'multiselect'" :id="id" class="multi-options">
      <label v-for="option in options" :key="String(option.value)"><input type="checkbox" :checked="rows.includes(option.value)" :disabled="disabled" :value="option.value" @change="toggle(option.value, $event)" />{{ l(option.label) }}</label>
      <p v-if="Array.isArray(value) && !value.length" class="hint">{{ w('emptyConfirmed') }}</p>
      <div class="list-controls"><button type="button" class="btn soft" :disabled="disabled" @click="emit('update', [])">{{ w('noItems') }}</button><button type="button" class="btn" :disabled="disabled" @click="emit('update', null)">{{ w('resetUnknown') }}</button></div>
    </div>
    <div v-else-if="collection" :id="id" class="collection-editor">
      <div v-if="rows.length" class="table-scroll"><table><thead><tr><template v-if="columns.length"><th v-for="column in columns" :key="column.key">{{ l(column.label) }} <small v-if="showOptional(column)">({{ w('optional') }})</small></th></template><th v-else>{{ w('columns') }}</th><th></th></tr></thead><tbody><tr v-for="(row, index) in rows" :key="rowKeys[index]"><template v-if="columns.length"><td v-for="column in columns" :key="column.key"><DraftingInputField v-if="applicability(column.condition, { ...values, ...(row && typeof row === 'object' && !Array.isArray(row) ? row : {}) }) !== 'no'" :field="column" :value="rowValue(row, column.key)" :values="{ ...values, ...(row && typeof row === 'object' && !Array.isArray(row) ? row : {}) }" :locale="locale" :disabled="disabled" :id-prefix="`${id}-${index + 1}`" compact hide-label @update="updateRow(index, $event, column.key)" /></td></template><td v-else><textarea :value="typeof row === 'string' ? row : JSON.stringify(row)" :disabled="disabled" :aria-label="`${l(field.label)} ${index + 1}`" rows="2" @input="updateRow(index, ($event.target as HTMLTextAreaElement).value)" /></td><td class="row-action"><button type="button" class="btn" :disabled="disabled" :aria-label="`${w('remove')} ${index + 1}`" @click="removeRow(index)">×</button></td></tr></tbody></table></div>
      <p v-if="Array.isArray(value) && !value.length" class="hint">{{ w('emptyConfirmed') }}</p>
      <div class="list-controls"><button type="button" class="btn" :disabled="disabled" @click="addRow">+ {{ w('addRow') }}</button><button type="button" class="btn soft" :disabled="disabled" @click="emit('update', [])">{{ w('noItems') }}</button><button type="button" class="btn" :disabled="disabled" @click="emit('update', null)">{{ w('resetUnknown') }}</button></div>
    </div>
    <select v-else-if="options.length" :id="id" :value="value === null || value === undefined ? '' : String(value)" :disabled="disabled" @change="updateRaw"><option value="">{{ w('unknown') }}</option><option v-for="option in options" :key="String(option.value)" :value="String(option.value)">{{ l(option.label) }}</option></select>
    <textarea v-else-if="multiline" :id="id" :aria-label="l(field.label)" :value="value == null ? '' : String(value)" :disabled="disabled" rows="4" wrap="soft" @input="updateRaw" />
    <input v-else :id="id" :aria-label="l(field.label)" :type="field.kind === 'number' ? 'number' : field.kind === 'date' ? 'date' : 'text'" :step="field.kind === 'number' ? 'any' : undefined" :min="field.kind === 'number' ? 0 : undefined" :value="value == null ? '' : String(value)" :disabled="disabled" @input="updateRaw" />
    <slot name="actions" />
    <DraftingFieldDiagnostics v-if="trace" :field-key="field.key" :trace="trace" :locale="locale" />
  </div>
</template>

<style scoped>
.draft-field { min-width: 0; margin: 14px 0; }
.field-label { display: block; margin-bottom: 6px; font-size: 11.5px; font-weight: 700; color: var(--muted); }
.field-label small { font-weight: 400; }
.field-description, .hint { font-size: 12px; color: var(--muted); line-height: 1.55; margin: 6px 0; }
.object-fields { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(220px, 100%), 1fr)); gap: 12px; }
.multi-options { display: flex; flex-direction: column; gap: 8px; }
.multi-options > label { display: flex; gap: 8px; align-items: center; font-size: 13px; }
.multi-options input { width: 16px; height: 16px; accent-color: var(--accent); }
.list-controls { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 10px; }
.table-scroll { overflow-x: auto; border: 1px solid var(--line); border-radius: var(--radius-sm); }
table { width: 100%; border-collapse: collapse; min-width: 560px; }
th { text-align: left; font-size: 11.5px; font-weight: 700; color: var(--muted); padding: 8px; background: var(--surface-subtle); }
td { padding: 8px; vertical-align: top; border-top: 1px solid var(--line); }
td:not(.row-action) { min-width: 120px; }
.row-action { width: 40px; }
.draft-field[data-field="billNos"] > .collection-editor > .table-scroll > table { min-width: 940px; }
.draft-field[data-field="billNos"] > .collection-editor > .table-scroll > table th:first-child,
.draft-field[data-field="billNos"] > .collection-editor > .table-scroll > table td:first-child { width: 76px; min-width: 76px; }
.draft-field[data-field="billNos"] > .collection-editor > .table-scroll > table th:nth-child(2),
.draft-field[data-field="billNos"] > .collection-editor > .table-scroll > table td:nth-child(2) { width: 240px; min-width: 240px; }
.draft-field[data-field="billNos"] > .collection-editor > .table-scroll > table th:nth-child(3),
.draft-field[data-field="billNos"] > .collection-editor > .table-scroll > table td:nth-child(3) { width: 110px; min-width: 110px; }
.compact { margin: 0; }
.compact .object-fields { grid-template-columns: 1fr; }
input:not([type=checkbox]), select, textarea { width: 100%; min-width: 0; min-height: 36px; border: 1px solid var(--line); border-radius: 6px; padding: 6px 10px; font: inherit; box-sizing: border-box; background: var(--surface); color: var(--ink); }
textarea { resize: vertical; line-height: 1.55; white-space: pre-wrap; overflow-wrap: anywhere; }
select { max-width: 620px; }
button:disabled { opacity: .5; }
</style>
