<script setup lang="ts">
import type { DraftField, DraftPlanAction, DraftUnresolved } from '@/api/types'
import type { AppLocale } from '@/i18n'
import { actionForUnresolved, applicability, type DraftValue } from '@/drafting/state'
import { draftWord, localized } from '@/drafting/words'

const props = defineProps<{ items: DraftUnresolved[]; fields: DraftField[]; values: Record<string, DraftValue>; actions: DraftPlanAction[]; locale: AppLocale }>()
const emit = defineEmits<{ input: [key: string]; target: [item: DraftUnresolved] }>()
const l = (text: Parameters<typeof localized>[0]) => localized(text, props.locale)
function inputs(item: DraftUnresolved) {
  return (item.inputKeys ?? []).map(key => props.fields.find(field => field.key === key))
    .filter((field): field is DraftField => !!field && !field.hidden && applicability(field.condition, props.values) !== 'no')
}
</script>

<template>
  <div v-for="item in items" :key="item.id" class="pending-item">
    <strong>{{ item.document }} {{ item.clause }}</strong><p>{{ l(item.message) }}</p>
    <div class="row">
      <button v-for="field in inputs(item)" :key="field.key" class="btn" @click="emit('input', field.key)">{{ l(field.label) }}</button>
      <button v-if="actionForUnresolved(item, actions)" class="btn" @click="emit('target', item)">{{ draftWord('exactEdit', locale) }}</button>
    </div>
  </div>
</template>

<style scoped>
.pending-item { padding: 14px 0; border-bottom: 1px solid var(--line); line-height: 1.55; }
.pending-item p { margin: 7px 0; }
.row { display: flex; align-items: center; justify-content: flex-start; gap: 8px; flex-wrap: wrap; }
</style>
