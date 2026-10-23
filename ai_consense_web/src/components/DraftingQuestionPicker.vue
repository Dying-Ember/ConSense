<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue'
import { questionSearchMatches } from '@/drafting/question-search'

const props = defineProps<{ modelValue?: string; query: string; items: { id: string; label: string; searchText: string }[]; locale: string; label: string; emptyLabel: string; searchAttribute: string; context: string; retainedLabel?: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: string]; 'update:query': [value: string]; change: [] }>()
const t = (zh: string, en: string) => props.locale === 'en' ? en : zh
const listId = useId(), active = ref(-1)
const matches = computed(() => props.items.filter(item => questionSearchMatches(item.searchText, props.query)))
const choices = computed(() => props.query.trim() ? matches.value : [{ id: '', label: props.emptyLabel }, ...matches.value])
const selectedLabel = computed(() => props.items.find(item => item.id === props.modelValue)?.label ?? (props.modelValue ? props.retainedLabel : props.emptyLabel))
const retained = computed(() => !!props.modelValue && !props.items.some(item => item.id === props.modelValue))
watch(() => props.query, () => { active.value = -1 })
function choose(id: string) { if (id === props.modelValue) return; emit('update:modelValue', id); emit('change') }
function keydown(event: KeyboardEvent) {
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault(); event.stopPropagation()
    if (choices.value.length) active.value = (active.value + (event.key === 'ArrowDown' ? 1 : choices.value.length - 1) + choices.value.length) % choices.value.length
    return
  }
  if (event.key === 'Enter' && active.value >= 0 && choices.value[active.value]) { event.preventDefault(); event.stopPropagation(); choose(choices.value[active.value]!.id) }
  if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); emit('update:query', ''); active.value = -1 }
}
</script>

<template>
  <div class="question-picker" :data-selected-id="modelValue ?? ''">
    <label>{{ label }}<input :value="query" type="search" role="combobox" aria-autocomplete="list" :aria-controls="listId" :aria-expanded="true" :aria-activedescendant="active >= 0 ? listId + '-' + active : undefined" :placeholder="t('输入关键词，下面立即显示匹配项', 'Type to show matching choices below')" v-bind="{ [searchAttribute]: context }" @input="emit('update:query', ($event.target as HTMLInputElement).value)" @keydown="keydown"></label>
    <p class="picker-current" data-picker-current>{{ t('当前选择：', 'Selected: ') }}<strong>{{ selectedLabel }}</strong></p>
    <div class="picker-summary"><span role="status" data-picker-count>{{ matches.length }} {{ t('个匹配项', 'matching choices') }}</span><button v-if="query" type="button" class="btn" data-picker-clear-search @click="emit('update:query', '')">{{ t('清空搜索', 'Clear search') }}</button><button v-if="query && modelValue" type="button" class="btn" data-picker-clear-selection @click="choose('')">{{ emptyLabel }}</button></div>
    <div :id="listId" role="listbox" :aria-label="label" class="picker-results" data-picker-results>
      <button v-if="retained" type="button" role="option" aria-selected="true" disabled class="picker-option picker-retained" data-picker-retained :data-picker-option="modelValue">{{ selectedLabel }} · {{ t('原引用需核对', 'Retained reference needs review') }}</button>
      <button v-for="(item, index) in choices" :id="listId + '-' + index" :key="item.id" type="button" role="option" :aria-selected="item.id === modelValue" :class="['picker-option', { 'picker-active': active === index }]" :data-picker-option="item.id" @click="choose(item.id)">{{ item.label }}<span v-if="item.id === modelValue" aria-hidden="true"> ✓</span></button>
    </div>
    <p v-if="query && !matches.length" class="picker-empty" data-picker-empty>{{ t('没有匹配项。请更换关键词或清空搜索。', 'No matching choices. Try another keyword or clear the search.') }}</p>
  </div>
</template>

<style scoped>
.question-picker{width:100%;min-width:0;font-size:12px}.question-picker label{display:block;margin:8px 0}.question-picker input{display:block;box-sizing:border-box;width:100%;padding:8px;margin-top:5px;font:inherit;border:1px solid var(--line-strong);border-radius:5px;background:var(--surface);color:var(--ink)}.picker-current{margin:6px 0;overflow-wrap:anywhere}.picker-summary{display:flex;align-items:center;flex-wrap:wrap;gap:8px;justify-content:space-between;margin:5px 0;color:var(--muted)}.picker-summary .btn{font-size:11px;padding:3px 6px}.picker-results{max-height:190px;overflow-y:auto;border:1px solid var(--line);border-radius:5px;background:var(--surface)}.picker-results:empty{display:none}.picker-option{display:block;width:100%;border:0;border-bottom:1px solid var(--line);padding:9px 10px;font:inherit;text-align:left;line-height:1.5;color:var(--ink);background:var(--surface);cursor:pointer;overflow-wrap:anywhere}.picker-option:last-child{border-bottom:0}.picker-option[aria-selected="true"],.picker-active{background:var(--accent-soft);font-weight:600}.picker-option:hover,.picker-option:focus-visible{background:var(--surface-subtle);outline:2px solid var(--accent);outline-offset:-2px}.picker-retained{color:var(--muted);cursor:default}.picker-empty{margin:8px 0;color:var(--muted)}
</style>
